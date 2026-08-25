package nebflow.core.entity

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.JsonObject
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.SharedResources
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.flow.{NodeStatus, RunningFlowRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{ContentBlock, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, StreamChunk, ToolCall}

import java.util.UUID
import scala.concurrent.duration.*

/**
 * Dynamic fan-out (NodeRoute.ParallelDynamic) semantics, against the REAL
 * executor + AgentActor (only the LlmHandle is faked):
 *
 *  - D1 decode: `parallel` array → static Parallel; `parallel` object
 *    {slots, template} → ParallelDynamic; encoder round-trips both; switch
 *    cases recursively accept the dynamic form.
 *  - D2 main path: planner reports 3 topics → 3 runtime instances
 *    researcher#1..#3 run CONCURRENTLY (window overlap), the template's
 *    downstream join activates EXACTLY ONCE after all three, and
 *    `$researcher.all.output` aggregates in index order with Track headers.
 *  - D3 flowNodesAdded WS event announces the runtime instances + edges.
 *  - D4 len==0 + abort → flow fails with a clear reason.
 *  - D5 len==0 + collect → continues with an empty aggregate.
 *  - D6 len > maxFanout → clamped to maxFanout with only N instances
 *    registered (cost cap, not target).
 *  - D7 len==1 → serial-equivalent, completes.
 *  - D8 missing slot (abort) → fails; (collect) → continues.
 *  - D9 slot value not an array → fails with a clear reason.
 *  - D10 template node missing → EntityLoader.validateFlow rejects at load.
 *  - D11 $<template>.all.slots.<field> aggregates instance slot values.
 */
class DynamicFanoutSpec extends CatsEffectSuite:

  /** Extracts the node id from a dag session id: dag-<flow>-<nodeId>-<ts>. */
  private def nodeIdOf(sessionId: String): String =
    sessionId.split("-").apply(2)

  private def answerAfter(delay: FiniteDuration, text: String): Stream[IO, StreamChunk] =
    Stream.eval(IO.sleep(delay)) >> Stream(StreamChunk.TextDelta(text), StreamChunk.Done(None, None))

  /**
   * Node-keyed fake LLM. "p" (planner) emits one FlowReport with the given
   * slots on its first request of the session, then plain text; instance
   * nodes (researcher#N) answer with the per-index behavior; the join answers
   * plain text. Captures every request for input-resolution assertions.
   */
  private class PlannerLlm(
    topics: List[Json],
    instanceBehavior: PartialFunction[Int, Stream[IO, StreamChunk]],
    instanceSlots: Option[Map[String, Json]] = None,
    emitTopics: Boolean = true,
    capture: Ref[IO, Map[String, List[Message]]] = Ref.unsafe(Map.empty),
    answered: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
  ) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(capture.update(m => m.updated(req.sessionId, req.messages))) >>
        Stream.eval(IO(nodeIdOf(req.sessionId))).flatMap {
          case "p" =>
            Stream
              .eval(answered.get.flatMap(a => if a(req.sessionId) then IO.pure(false) else answered.update(_ + req.sessionId).as(true)))
              .flatMap {
                case true =>
                  val slotsObj = JsonObject.fromIterable(if emitTopics then List("topics" -> topics.asJson) else Nil)
                  Stream(
                    StreamChunk.ToolCallChunk(
                      ToolCall(
                        id = "call-p1",
                        name = "FlowReport",
                        input = JsonObject.fromIterable(
                          List(
                            "verdict" -> Json.fromString("done"),
                            "output" -> Json.fromString("PLANNER-OUT"),
                            "slots" -> slotsObj.asJson
                          )
                        )
                      )
                    ),
                    StreamChunk.Done(Some("tool_use"), None)
                  )
                case false => Stream(StreamChunk.TextDelta("planner done"), StreamChunk.Done(None, None))
              }
          case inst if inst.startsWith("researcher#") =>
            val idx = inst.split("#").last.toInt
            Stream
              .eval(answered.get.flatMap(a => if a(req.sessionId) then IO.pure(false) else answered.update(_ + req.sessionId).as(true)))
              .flatMap {
                case true if instanceSlots.nonEmpty =>
                  Stream(
                    StreamChunk.ToolCallChunk(
                      ToolCall(
                        id = s"call-i$idx",
                        name = "FlowReport",
                        input = JsonObject.fromIterable(
                          List(
                            "verdict" -> Json.fromString("done"),
                            "output" -> Json.fromString(s"OUT-$idx"),
                            "slots" -> instanceSlots.get.asJson
                          )
                        )
                      )
                    ),
                    StreamChunk.Done(Some("tool_use"), None)
                  )
                case _ =>
                  instanceBehavior.applyOrElse(idx, (_: Int) => answerAfter(30.millis, s"OUT-$idx"))
              }
          case _ => answerAfter(30.millis, "J-DONE")
        }

  private def userTextOf(messages: List[Message]): String =
    messages
      .filter(_.role == MessageRole.User)
      .map(m => m.content.fold(t => t, bs => bs.collect { case ContentBlock.Text(t) => t }.mkString))
      .mkString("\n")

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new nebflow.agent.AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new nebflow.agent.SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  private def withFlowEnv[A](llm: LlmHandle[IO])(
    test: (SharedResources, ActorSystem) => IO[A]
  ): IO[A] =
    val system = ActorSystem(s"dyn-${UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val dataRoot = tmp / "data"
    IO.delay {
      os.makeDir.all(dataRoot / "flows" / "tflow" / "agents" / "worker")
      os.write(
        dataRoot / "flows" / "tflow" / "agents" / "worker" / "agent.json",
        """{"name":"worker","description":"test worker","tools":["Read"]}"""
      )
      PathUtil.setDataRoot(dataRoot)
    }.bracket { _ =>
      mkResources(system, tmp, llm).flatMap(test(_, system))
    } { _ =>
      IO.delay(PathUtil.setDataRoot(prevRoot)) *>
        system.stopAll.attempt.void *>
        IO.delay(if os.exists(tmp) then os.remove.all(tmp)).attempt.void
    }

  /** p → ParallelDynamic(topics→researcher) → j ($researcher.all.output) → $return. */
  private def dynFlow(
    onFail: NodeRoute.OnFailMode = NodeRoute.OnFailMode.Abort,
    maxFanout: Int = 4,
    plannerOutputs: Map[String, String] = Map("topics" -> "array")
  ): FlowDagDef =
    FlowDagDef(
      name = "tflow",
      description = "dynamic fanout test",
      nodes = Map(
        "p" -> FlowNode(
          "worker",
          "$task",
          NodeRoute.ParallelDynamic("topics", "researcher", onFail),
          outputs = plannerOutputs
        ),
        "researcher" -> FlowNode("worker", "Track {{index}}: {{item}}", NodeRoute.Goto("j")),
        "j" -> FlowNode("worker", "=== ALL ===\n$researcher.all.output", NodeRoute.Return)
      ),
      entry = "p",
      maxFanout = maxFanout
    )

  test("D1 NodeRoute decode: parallel array → Parallel, parallel object → ParallelDynamic, round-trip, switch case") {
    val static = decode[NodeRoute]("""{"parallel": ["r1","r2"], "onFail": "collect"}""")
    assertEquals(
      static,
      Right(NodeRoute.Parallel(List("r1", "r2"), NodeRoute.OnFailMode.Collect))
    )
    val dynamic = decode[NodeRoute](
      """{"parallel": {"slots": "topics", "template": "researcher"}, "onFail": "collect"}"""
    )
    assertEquals(
      dynamic,
      Right(NodeRoute.ParallelDynamic("topics", "researcher", NodeRoute.OnFailMode.Collect))
    )
    // round-trip both forms
    assertEquals(decode[NodeRoute](static.toOption.get.asJson.noSpaces), static)
    assertEquals(decode[NodeRoute](dynamic.toOption.get.asJson.noSpaces), dynamic)
    // string form still decodes
    assertEquals(decode[NodeRoute](""""$return""""), Right(NodeRoute.Return))
    // switch cases recursively accept the dynamic form
    val sw = decode[NodeRoute](
      """{"switch": "$p.verdict", "cases": {"expand": {"parallel": {"slots": "topics", "template": "researcher"}}}}"""
    )
    assert(
      sw.exists {
        case NodeRoute.Switch(_, cases, _, _) =>
          cases.get("expand").contains(NodeRoute.ParallelDynamic("topics", "researcher", NodeRoute.OnFailMode.Abort))
        case _ => false
      },
      s"switch case decodes to ParallelDynamic, got: $sw"
    )
  }

  test("D2 3 topics → 3 concurrent instances, join activates exactly once, $all.output aggregated in index order") {
    val capture: Ref[IO, Map[String, List[Message]]] = Ref.unsafe(Map.empty)
    val answered: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
    val wsEvents: Ref[IO, List[Json]] = Ref.unsafe(Nil)
    val llm = new PlannerLlm(
      topics = List(Json.fromString("A"), Json.fromString("B"), Json.fromString("C")),
      instanceBehavior = {
        case 1 => answerAfter(300.millis, "OUT-1")
        case 2 => answerAfter(700.millis, "OUT-2")
        case 3 => answerAfter(1100.millis, "OUT-3")
      },
      capture = capture,
      answered = answered
    )
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"dyn2-${UUID.randomUUID().toString.take(6)}"
      val wsSend: Json => IO[Unit] = j => wsEvents.update(_ :+ j)
      FlowDagExecutor
        .execute(dynFlow(), "work", resources, system, Some(wsSend), instId)
        .timeout(30.seconds)
        .flatMap { result =>
          RunningFlowRegistry.list.map(_.find(_.instanceId == instId)).flatMap { rfOpt =>
            capture.get.flatMap { captured =>
              wsEvents.get.map { ws =>
                assert(result.isRight, s"dynamic fanout must complete, got: $result")
                val nodes = rfOpt.map(_.nodes).getOrElse(Map.empty)
                // exactly 3 instances, all Completed
                val insts = (1 to 3).map(i => s"researcher#$i")
                insts.foreach { id =>
                  assertEquals(nodes.get(id).map(_.status), Some(NodeStatus.Completed), s"$id completed")
                }
                assertEquals(
                  nodes.keys.filter(_.startsWith("researcher#")).size,
                  3,
                  "no extra instances registered"
                )
                assertEquals(nodes.get("j").map(_.status), Some(NodeStatus.Completed), "join ran")
                // join activated EXACTLY once: single j session
                val jSessions = captured.keys.count(_.contains("dag-tflow-j"))
                assertEquals(jSessions, 1, "barrier fires the join exactly once")
                // concurrency: all three instances were in flight together —
                // the latest starter began before the earliest completer
                val starts = insts.map(nodes(_).startedAt.getOrElse(0L))
                val completions = insts.map(nodes(_).completedAt.getOrElse(0L))
                assert(
                  starts.max < completions.min,
                  s"instance windows overlap: max(started)=${starts.max} must be < min(completed)=${completions.min}"
                )
                // join started only after ALL instances completed (barrier)
                val js = nodes("j").startedAt.getOrElse(0L)
                assert(
                  js >= completions.max,
                  s"join.startedAt ($js) ≥ max instance completedAt (${completions.max})"
                )
                // $researcher.all.output aggregated in index order with Track headers
                val jUserText = captured.toList
                  .filter(_._1.contains("dag-tflow-j"))
                  .flatMap { (_, msgs) => msgs }
                  .mkString("\n")
                val track1 = jUserText.indexOf("=== Track 1 ===\nOUT-1")
                val track2 = jUserText.indexOf("=== Track 2 ===\nOUT-2")
                val track3 = jUserText.indexOf("=== Track 3 ===\nOUT-3")
                assert(track1 >= 0 && track2 > track1 && track3 > track2, s"index-ordered aggregation, got:\n$jUserText")
                // {{item}}/{{index}} substitution in instance inputs
                val instUserTexts = captured.toList
                  .filter { (sid, _) => nodeIdOf(sid).startsWith("researcher#") }
                  .map { (sid, msgs) => nodeIdOf(sid) -> userTextOf(msgs) }
                  .toMap
                assert(instUserTexts("researcher#1").contains("Track 1: A"), s"#1 got item A: ${instUserTexts("researcher#1")}")
                assert(instUserTexts("researcher#2").contains("Track 2: B"), s"#2 got item B: ${instUserTexts("researcher#2")}")
                assert(instUserTexts("researcher#3").contains("Track 3: C"), s"#3 got item C: ${instUserTexts("researcher#3")}")
              }
            }
          }
        }
    }
  }

  test("D3 flowNodesAdded WS event announces runtime instances + edges") {
    val capture: Ref[IO, Map[String, List[Message]]] = Ref.unsafe(Map.empty)
    val answered: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
    val wsEvents: Ref[IO, List[Json]] = Ref.unsafe(Nil)
    val llm = new PlannerLlm(
      topics = List(Json.fromString("A"), Json.fromString("B")),
      instanceBehavior = { case _ => answerAfter(20.millis, "OUT") },
      capture = capture,
      answered = answered
    )
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"dyn3-${UUID.randomUUID().toString.take(6)}"
      val wsSend: Json => IO[Unit] = j => wsEvents.update(_ :+ j)
      FlowDagExecutor
        .execute(dynFlow(), "work", resources, system, Some(wsSend), instId)
        .timeout(30.seconds)
        .flatMap { result =>
          wsEvents.get.map { ws =>
            assert(result.isRight, s"flow must complete, got: $result")
            val added = ws.filter(_.hcursor.get[String]("type").toOption.contains("flowNodesAdded"))
            assertEquals(added.size, 1, s"exactly one flowNodesAdded event, got: ${ws.map(_.hcursor.get[String]("type"))}")
            val nodesArr = added.head.hcursor.downField("nodes").as[List[Json]].toOption.getOrElse(Nil)
            assertEquals(nodesArr.map(_.hcursor.get[String]("nodeId").toOption.getOrElse("")).toSet, Set("researcher#1", "researcher#2"))
            val edgesArr = added.head.hcursor.downField("edges").as[List[Json]].toOption.getOrElse(Nil)
            assertEquals(edgesArr.size, 2, s"one edge per instance to the join, got: $edgesArr")
            assert(edgesArr.forall(_.hcursor.downField("to").as[String].toOption.contains("j")), "edges converge on the join")
          }
        }
    }
  }

  test("D4 len==0 + abort → flow fails with clear reason") {
    val capture: Ref[IO, Map[String, List[Message]]] = Ref.unsafe(Map.empty)
    val answered: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
    val llm = new PlannerLlm(topics = Nil, instanceBehavior = PartialFunction.empty, capture = capture, answered = answered)
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"dyn4-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(dynFlow(onFail = NodeRoute.OnFailMode.Abort), "work", resources, system, None, instId)
        .timeout(30.seconds)
        .map { result =>
          assert(result.isLeft, s"empty topics + abort must fail, got: $result")
          val err = result.swap.toOption.get
          assert(err.contains("empty"), s"failure mentions empty topics, got: $err")
        }
    }
  }

  test("D5 len==0 + collect → continues with empty aggregate, join still runs") {
    val capture: Ref[IO, Map[String, List[Message]]] = Ref.unsafe(Map.empty)
    val answered: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
    val llm = new PlannerLlm(topics = Nil, instanceBehavior = PartialFunction.empty, capture = capture, answered = answered)
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"dyn5-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(dynFlow(onFail = NodeRoute.OnFailMode.Collect), "work", resources, system, None, instId)
        .timeout(30.seconds)
        .flatMap { result =>
          RunningFlowRegistry.list.map(_.find(_.instanceId == instId)).map { rfOpt =>
            assert(result.isRight, s"empty topics + collect must carry through, got: $result")
            assertEquals(rfOpt.flatMap(_.nodes.get("j").map(_.status)), Some(NodeStatus.Completed), "join ran with empty aggregate")
          }
        }
    }
  }

  test("D6 len > maxFanout → clamped to maxFanout (only N instances, join sees N tracks)") {
    val capture: Ref[IO, Map[String, List[Message]]] = Ref.unsafe(Map.empty)
    val answered: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
    val llm = new PlannerLlm(
      topics = List(Json.fromString("A"), Json.fromString("B"), Json.fromString("C"), Json.fromString("D")),
      instanceBehavior = { case _ => answerAfter(20.millis, "OUT") },
      capture = capture,
      answered = answered
    )
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"dyn6-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(dynFlow(maxFanout = 2), "work", resources, system, None, instId)
        .timeout(30.seconds)
        .flatMap { result =>
          RunningFlowRegistry.list.map(_.find(_.instanceId == instId)).flatMap { rfOpt =>
            capture.get.map { captured =>
              assert(result.isRight, s"clamped fanout must complete, got: $result")
              val nodes = rfOpt.map(_.nodes).getOrElse(Map.empty)
              val insts = nodes.keys.filter(_.startsWith("researcher#")).toSet
              assertEquals(insts, Set("researcher#1", "researcher#2"), s"only 2 instances for maxFanout=2, got: $insts")
              insts.foreach(id => assertEquals(nodes(id).status, NodeStatus.Completed))
              // join saw exactly 2 tracks (index order preserved)
              val jUserText = captured.toList
                .filter(_._1.contains("dag-tflow-j"))
                .flatMap { (_, msgs) => msgs }
                .mkString("\n")
              assert(jUserText.contains("=== Track 1 ===\nOUT"), s"track 1 present: $jUserText")
              assert(jUserText.contains("=== Track 2 ===\nOUT"), s"track 2 present: $jUserText")
              assert(!jUserText.contains("Track 3"), s"no track 3 after clamp: $jUserText")
            }
          }
        }
    }
  }

  test("D7 len==1 → serial-equivalent, completes") {
    val capture: Ref[IO, Map[String, List[Message]]] = Ref.unsafe(Map.empty)
    val answered: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
    val llm = new PlannerLlm(
      topics = List(Json.fromString("only")),
      instanceBehavior = { case _ => answerAfter(20.millis, "OUT-1") },
      capture = capture,
      answered = answered
    )
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"dyn7-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(dynFlow(), "work", resources, system, None, instId)
        .timeout(30.seconds)
        .flatMap { result =>
          RunningFlowRegistry.list.map(_.find(_.instanceId == instId)).map { rfOpt =>
            assert(result.isRight, s"single item must complete, got: $result")
            assertEquals(rfOpt.flatMap(_.nodes.get("researcher#1").map(_.status)), Some(NodeStatus.Completed))
            assertEquals(rfOpt.flatMap(_.nodes.get("j").map(_.status)), Some(NodeStatus.Completed))
          }
        }
    }
  }

  test("D8 missing slot: abort AND collect both fail clearly (#414 fix 4a — no silent empty aggregate)"):
    // planner declares NO outputs → FlowReport carries no slots → slot missing.
    // 引用未命中是 DAG/契约错误：无论 onFail=collect 都明确报错（区别于
    // D5「key 存在但数组为空」的正常降级语义——那是 planner 合法产出 0 项）。
    def run(onFail: NodeRoute.OnFailMode): IO[Either[String, String]] =
      val capture: Ref[IO, Map[String, List[Message]]] = Ref.unsafe(Map.empty)
      val answered: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
      val llm = new PlannerLlm(topics = Nil, instanceBehavior = PartialFunction.empty, emitTopics = false, capture = capture, answered = answered)
      withFlowEnv(llm) { (resources, system) =>
        val instId = s"dyn8-${UUID.randomUUID().toString.take(6)}"
        FlowDagExecutor
          .execute(dynFlow(onFail = onFail, plannerOutputs = Map.empty), "work", resources, system, None, instId)
          .timeout(30.seconds)
      }
    for
      abortResult <- run(NodeRoute.OnFailMode.Abort)
      collectResult <- run(NodeRoute.OnFailMode.Collect)
    yield
      assert(abortResult.isLeft, s"missing slot + abort must fail, got: $abortResult")
      assert(abortResult.swap.toOption.get.contains("not found"), s"clear missing-slot reason: $abortResult")
      assert(collectResult.isLeft, s"missing slot + collect must ALSO fail clearly (no silent empty aggregate), got: $collectResult")
      assert(collectResult.swap.toOption.get.contains("not found"), s"clear missing-slot reason: $collectResult")

  test("D9 slot value not an array → fails with clear reason") {
    val capture: Ref[IO, Map[String, List[Message]]] = Ref.unsafe(Map.empty)
    val answered: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
    // planner declares topics as string and FlowReport submits a STRING slot → fanout rejects
    val stringSlotLlm = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] =
        IO.raiseError(new RuntimeException("send not expected in this test"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream.eval(capture.update(m => m.updated(req.sessionId, req.messages))) >>
          Stream.eval(IO(nodeIdOf(req.sessionId))).flatMap {
            case "p" =>
              Stream
                .eval(
                  answered.get.flatMap(a => if a(req.sessionId) then IO.pure(false) else answered.update(_ + req.sessionId).as(true))
                )
                .flatMap {
                  case true =>
                    Stream(
                      StreamChunk.ToolCallChunk(
                        ToolCall(
                          id = "call-p1",
                          name = "FlowReport",
                          input = JsonObject.fromIterable(
                            List(
                              "verdict" -> Json.fromString("done"),
                              "output" -> Json.fromString("PLANNER-OUT"),
                              "slots" -> JsonObject.fromIterable(List("topics" -> Json.fromString("not-an-array"))).asJson
                            )
                          )
                        )
                      ),
                      StreamChunk.Done(Some("tool_use"), None)
                    )
                  case false => Stream(StreamChunk.TextDelta("planner done"), StreamChunk.Done(None, None))
                }
            case _ => answerAfter(20.millis, "J-DONE")
          }
    withFlowEnv(stringSlotLlm) { (resources, system) =>
      val instId = s"dyn9-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(
          dynFlow(onFail = NodeRoute.OnFailMode.Abort, plannerOutputs = Map("topics" -> "string")),
          "work",
          resources,
          system,
          None,
          instId
        )
        .timeout(30.seconds)
        .map { result =>
          assert(result.isLeft, s"non-array slot must fail, got: $result")
          val err = result.swap.toOption.get
          assert(err.contains("not an array"), s"clear non-array reason, got: $err")
        }
    }
  }

  test("D10 template node missing → EntityLoader.validateFlow rejects at load") {
    val badFlow = FlowDagDef(
      name = "tflow",
      description = "bad",
      nodes = Map(
        "p" -> FlowNode("worker", "$task", NodeRoute.ParallelDynamic("topics", "ghost", NodeRoute.OnFailMode.Abort)),
        "j" -> FlowNode("worker", "x", NodeRoute.Return)
      ),
      entry = "p"
    )
    val errs = EntityLoader.validateFlow(badFlow, Set("worker"))
    assert(
      errs.contains("node 'p' routes to unknown node 'ghost'"),
      s"template existence validated at load, got: $errs"
    )
  }

  test("D11 $<template>.all.slots.<field> aggregates instance slot values") {
    val capture: Ref[IO, Map[String, List[Message]]] = Ref.unsafe(Map.empty)
    val answered: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
    val instanceSlots: Map[String, Json] = Map(
      "sources" -> List(Json.fromString("s1"), Json.fromString("s2")).asJson
    )
    val llm = new PlannerLlm(
      topics = List(Json.fromString("A"), Json.fromString("B")),
      instanceBehavior = { case _ => answerAfter(20.millis, "OUT") },
      instanceSlots = Some(instanceSlots),
      capture = capture,
      answered = answered
    )
    // template declares sources:array so FlowReport contract validation passes
    val flow = FlowDagDef(
      name = "tflow",
      description = "all-slots test",
      nodes = Map(
        "p" -> FlowNode(
          "worker",
          "$task",
          NodeRoute.ParallelDynamic("topics", "researcher", NodeRoute.OnFailMode.Abort),
          outputs = Map("topics" -> "array")
        ),
        "researcher" -> FlowNode(
          "worker",
          "Track {{index}}: {{item}}",
          NodeRoute.Goto("j"),
          outputs = Map("sources" -> "array")
        ),
        "j" -> FlowNode("worker", "=== SOURCES ===\n$researcher.all.slots.sources", NodeRoute.Return)
      ),
      entry = "p"
    )
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"dyn11-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(flow, "work", resources, system, None, instId)
        .timeout(30.seconds)
        .flatMap { result =>
          capture.get.map { captured =>
            assert(result.isRight, s"all.slots flow must complete, got: $result")
            val jUserText = captured.toList
              .filter(_._1.contains("dag-tflow-j"))
              .flatMap { (_, msgs) => msgs }
              .mkString("\n")
            assert(jUserText.contains("""["s1","s2"]"""), s"instance 1 sources aggregated: $jUserText")
            // both instances' slot arrays present (order = index order)
            val target = """["s1","s2"]"""
            val first = jUserText.indexOf(target)
            val second = jUserText.indexOf(target, first + target.length)
            assert(first >= 0 && second > first, s"two instance slot arrays aggregated, got: $jUserText")
          }
        }
    }
  }

  test("D12 $params.<name> resolves into node inputs (provided value; missing → placeholder)") {
    // entry node references $params.fanout; no fanout, no parallel — pure resolution test
    val flow = FlowDagDef(
      name = "tflow",
      description = "params test",
      nodes = Map(
        "p" -> FlowNode("worker", "fanout=$params.fanout\nmode=$params.mode\n$task", NodeRoute.Return)
      ),
      entry = "p"
    )
    def run(params: Map[String, Json]): IO[String] =
      val capture: Ref[IO, Map[String, List[Message]]] = Ref.unsafe(Map.empty)
      val answered: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
      val llm = new LlmHandle[IO]:
        def send(req: LlmRequest): IO[LlmResponse] =
          IO.raiseError(new RuntimeException("send not expected in this test"))
        def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
        ): Stream[IO, StreamChunk] =
          Stream.eval(capture.update(m => m.updated(req.sessionId, req.messages))) >>
            Stream(StreamChunk.TextDelta("done"), StreamChunk.Done(None, None))
      withFlowEnv(llm) { (resources, system) =>
        val instId = s"dyn12-${UUID.randomUUID().toString.take(6)}"
        FlowDagExecutor
          .execute(flow, "work", resources, system, None, instId, params = params)
          .timeout(30.seconds)
          .flatMap { result =>
            capture.get.map { captured =>
              assert(result.isRight, s"params flow must complete, got: $result")
              val pUserText = captured.toList
                .filter(_._1.contains("dag-tflow-p"))
                .flatMap { (_, msgs) => msgs }
                .mkString("\n")
              pUserText
            }
          }
      }
    for
      provided <- run(Map("fanout" -> Json.fromInt(3), "mode" -> Json.fromString("fast")))
      missing <- run(Map.empty)
    yield
      assert(provided.contains("fanout=3"), s"int param inserted, got: $provided")
      assert(provided.contains("mode=fast"), s"string param inserted, got: $provided")
      assert(missing.contains("fanout=[param fanout not provided]"), s"missing param placeholder, got: $missing")
      assert(missing.contains("mode=[param mode not provided]"), s"missing param placeholder, got: $missing")
  }

  // ---------- #414 fixes ----------

  test("D13 {{len}} substitutes the total instance count (#414 fix 1)"):
    val flow = FlowDagDef(
      name = "tflow",
      description = "len test",
      nodes = Map(
        "p" -> FlowNode(
          "worker",
          "$task",
          NodeRoute.ParallelDynamic("topics", "researcher", NodeRoute.OnFailMode.Abort),
          outputs = Map("topics" -> "array")
        ),
        "researcher" -> FlowNode("worker", "item={{item}} idx={{index}} total={{len}}", NodeRoute.Goto("j")),
        "j" -> FlowNode("worker", "$researcher.all.output", NodeRoute.Return)
      ),
      entry = "p"
    )
    val capture: Ref[IO, Map[String, List[Message]]] = Ref.unsafe(Map.empty)
    val answered: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
    val llm = new PlannerLlm(
      topics = List(Json.fromString("A"), Json.fromString("B"), Json.fromString("C")),
      instanceBehavior = { case _ => answerAfter(20.millis, "OUT") },
      capture = capture,
      answered = answered
    )
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"dyn13-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(flow, "work", resources, system, None, instId)
        .timeout(30.seconds)
        .flatMap { result =>
          capture.get.map { captured =>
            assert(result.isRight, s"{{len}} flow must complete, got: $result")
            val instUserTexts = captured.toList
              .filter { (sid, _) => nodeIdOf(sid).startsWith("researcher#") }
              .map { (sid, msgs) => nodeIdOf(sid) -> userTextOf(msgs) }
              .toMap
            (1 to 3).foreach { i =>
              val t = instUserTexts(s"researcher#$i")
              assert(t.contains(s"idx=$i"), s"#$i carries its index: $t")
              assert(t.contains("total=3"), s"#$i carries the total count ({{len}}=3): $t")
            }
          }
        }
    }

  test("D14 slotField accepts $node.slots.field full-reference syntax (#414 fix 4a)"):
    // Manager 曾写 "slots": "$planner.slots.blocks"（全引用）被当字面量 key 查不到
    // → 0 实例静默空聚合。现在解析为 (p, topics) → 正常 fanout。
    val flow = FlowDagDef(
      name = "tflow",
      description = "ref-slot test",
      nodes = Map(
        "p" -> FlowNode(
          "worker",
          "$task",
          NodeRoute.ParallelDynamic("$p.slots.topics", "researcher", NodeRoute.OnFailMode.Abort),
          outputs = Map("topics" -> "array")
        ),
        "researcher" -> FlowNode("worker", "Track {{index}}: {{item}}", NodeRoute.Goto("j")),
        "j" -> FlowNode("worker", "$researcher.all.output", NodeRoute.Return)
      ),
      entry = "p"
    )
    val capture: Ref[IO, Map[String, List[Message]]] = Ref.unsafe(Map.empty)
    val answered: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
    val llm = new PlannerLlm(
      topics = List(Json.fromString("A"), Json.fromString("B")),
      instanceBehavior = { case _ => answerAfter(20.millis, "OUT") },
      capture = capture,
      answered = answered
    )
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"dyn14-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(flow, "work", resources, system, None, instId)
        .timeout(30.seconds)
        .flatMap { result =>
          RunningFlowRegistry.list.map(_.find(_.instanceId == instId)).map { rfOpt =>
            assert(result.isRight, s"full-reference slot must fan out, got: $result")
            val nodes = rfOpt.map(_.nodes).getOrElse(Map.empty)
            assertEquals(
              nodes.keys.filter(_.startsWith("researcher#")).toSet,
              Set("researcher#1", "researcher#2"),
              "full-reference slot fans out to the referenced node's array"
            )
          }
        }
    }

  test("D15 full-reference slot miss fails clearly regardless of onFail (#414 fix 4a)"):
    // 引用语法未命中（field 不存在 / 源节点无 slots）→ 明确报错，不静默 collect
    def run(slotField: String, onFail: NodeRoute.OnFailMode, emitTopics: Boolean): IO[Either[String, String]] =
      val flow = FlowDagDef(
        name = "tflow",
        description = "ref-miss test",
        nodes = Map(
          "p" -> FlowNode(
            "worker",
            "$task",
            NodeRoute.ParallelDynamic(slotField, "researcher", onFail),
            outputs = Map("topics" -> "array")
          ),
          "researcher" -> FlowNode("worker", "Track {{index}}: {{item}}", NodeRoute.Goto("j")),
          "j" -> FlowNode("worker", "$researcher.all.output", NodeRoute.Return)
        ),
        entry = "p"
      )
      val capture: Ref[IO, Map[String, List[Message]]] = Ref.unsafe(Map.empty)
      val answered: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
      val llm = new PlannerLlm(
        topics = Nil,
        instanceBehavior = PartialFunction.empty,
        emitTopics = emitTopics,
        capture = capture,
        answered = answered
      )
      withFlowEnv(llm) { (resources, system) =>
        val instId = s"dyn15-${UUID.randomUUID().toString.take(6)}"
        FlowDagExecutor
          .execute(flow, "work", resources, system, None, instId)
          .timeout(30.seconds)
      }
    for
      // 引用不存在的 field：无论 abort/collect 都报错
      missAbort <- run("$p.slots.ghost", NodeRoute.OnFailMode.Abort, emitTopics = true)
      missCollect <- run("$p.slots.ghost", NodeRoute.OnFailMode.Collect, emitTopics = true)
      // 源节点没有 slots（planner 未产出）→ 引用解析失败同样报错
      noSlots <- run("$p.slots.topics", NodeRoute.OnFailMode.Collect, emitTopics = false)
    yield
      assert(missAbort.isLeft && missAbort.swap.toOption.get.contains("no slots field 'ghost'"), s"got: $missAbort")
      assert(missCollect.isLeft, s"reference miss + collect must NOT silently aggregate empty, got: $missCollect")
      assert(noSlots.isLeft && noSlots.swap.toOption.get.contains("no slots field"), s"got: $noSlots")

  test("D16 unresolved {{...}} placeholder rejected before agent spawn (#414 fix 4b)"):
    // 模板变量替换后仍残留 {{...}}（引擎不认识的占位符）→ 节点明确失败，
    // 不再把坏模板静默喂给 agent（实测 redo-qa 带未替换 {{}} 运行）
    val flow = FlowDagDef(
      name = "tflow",
      description = "placeholder test",
      nodes = Map(
        "p" -> FlowNode("worker", "legit={{index}} bogus={{bogus}} $task", NodeRoute.Return)
      ),
      entry = "p"
    )
    val llm = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] =
        IO.raiseError(new RuntimeException("send not expected in this test"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream(StreamChunk.TextDelta("done"), StreamChunk.Done(None, None))
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"dyn16-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(flow, "work", resources, system, None, instId)
        .timeout(30.seconds)
        .map { result =>
          assert(result.isLeft, s"unknown placeholder must fail the node, got: $result")
          val err = result.swap.toOption.get
          assert(err.contains("Unknown template placeholder"), s"clear placeholder reason, got: $err")
          assert(err.contains("{{bogus}}"), s"mentions the offending placeholder, got: $err")
        }
    }

end DynamicFanoutSpec
