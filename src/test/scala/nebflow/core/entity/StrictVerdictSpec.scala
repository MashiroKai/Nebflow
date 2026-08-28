package nebflow.core.entity

import cats.effect.kernel.Outcome
import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentDef, FlowNodeContract, SharedResources}
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.flow.NodeStatus
import nebflow.core.flow.RunningFlowRegistry
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, FlowReportStore, FlowReportTool, ToolContext, ToolError}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{ContentBlock, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, StreamChunk, ToolCall}

import java.util.UUID
import scala.concurrent.duration.*

/**
 * R8-P1 structured verdict contract:
 *
 *  Tool level — FlowReportTool.call validates against the node's
 *   FlowNodeContract (injected via AgentDef.flowContract): verdict membership,
 *   slot presence/types, no undeclared slots. Violations → ToolError (agent
 *   self-corrects in-turn); valid calls store canonical verdict + slots.
 *
 *  Executor level — strictVerdict flows:
 *   - switch node ending WITHOUT a FlowReport verdict fails the node with an
 *     error listing the expected case keys (second line of defense);
 *   - Switch.lenient=true re-enables the legacy guess pipeline per switch;
 *   - strictVerdict=false (default) keeps legacy behavior unchanged;
 *   - a valid FlowReport verdict routes the switch; slots flow downstream
 *     via $<nodeId>.slots.<field>.
 */
class StrictVerdictSpec extends CatsEffectSuite:

  // ==========================================================
  // Tool-level contract validation (no executor)
  // ==========================================================

  private def contractCtx(sessionId: String, contract: Option[FlowNodeContract]): ToolContext =
    ToolContext(
      projectRoot = "",
      sessionId = Some(sessionId),
      agentDef = Some(
        AgentDef(name = "worker", description = "", category = "flow", flowContract = contract)
      )
    )

  private val switchContract =
    Some(FlowNodeContract(caseKeys = Set("pass", "revise"), slots = Map("topics" -> "string", "issues" -> "array")))

  private def reportInput(verdict: String, output: String, slots: Option[Json]): io.circe.JsonObject =
    io.circe.JsonObject.fromIterable(
      List(
        Some("verdict" -> Json.fromString(verdict)),
        Some("output" -> Json.fromString(output)),
        slots.map(s => "slots" -> s)
      ).flatten
    )

  test("FlowReport invalid verdict rejected with expected keys listed") {
    val sid = s"svc-${UUID.randomUUID().toString.take(6)}"
    FlowReportTool
      .call(reportInput("unknown", "out", None), contractCtx(sid, switchContract))
      .map { result =>
        assert(result.isLeft, s"invalid verdict must be rejected, got: $result")
        val msg = result.swap.toOption.get.message
        assert(msg.contains("verdict must be one of"), s"error lists the enum: $msg")
        assert(msg.contains("pass"), s"error mentions 'pass': $msg")
        assert(msg.contains("revise"), s"error mentions 'revise': $msg")
      }
  }

  test("FlowReport verdict case-insensitively canonicalized to declared key") {
    val sid = s"svc-${UUID.randomUUID().toString.take(6)}"
    for
      result <- FlowReportTool.call(
        reportInput("  PASS ", "out", Some(Json.obj("topics" -> "t".asJson, "issues" -> Json.arr("a".asJson)))),
        contractCtx(sid, switchContract)
      )
      stored <- FlowReportStore.get(sid)
      _ <- FlowReportStore.remove(sid)
    yield
      assert(result.isRight, s"case-insensitive pass must be accepted: $result")
      assertEquals(stored.map(_.verdict), Some("pass"), "canonical declared key stored")
      assertEquals(stored.flatMap(_.slots("topics")), Some("t".asJson), "string slot stored")
      assertEquals(stored.flatMap(_.slots("issues")), Some(Json.arr("a".asJson)), "array slot stored")
  }

  test("FlowReport slot type mismatch rejected") {
    val sid = s"svc-${UUID.randomUUID().toString.take(6)}"
    FlowReportTool
      .call(
        reportInput("pass", "out", Some(Json.obj("topics" -> 42.asJson, "issues" -> Json.arr()))),
        contractCtx(sid, switchContract)
      )
      .map { result =>
        assert(result.isLeft, "number where string declared must be rejected")
        val msg = result.swap.toOption.get.message
        assert(msg.contains("slots.topics must be of type string"), s"names the offending slot: $msg")
      }
  }

  test("FlowReport missing declared slot and undeclared slot both rejected") {
    val sid = s"svc-${UUID.randomUUID().toString.take(6)}"
    // topics missing entirely; extra undeclared key present
    FlowReportTool
      .call(
        reportInput("pass", "out", Some(Json.obj("issues" -> Json.arr(), "bonus" -> "x".asJson))),
        contractCtx(sid, switchContract)
      )
      .map { result =>
        assert(result.isLeft)
        val msg = result.swap.toOption.get.message
        assert(msg.contains("slots.topics is declared as string but missing"), s"$msg")
        assert(msg.contains("slots.bonus is not declared"), s"$msg")
      }
  }

  test("FlowReport without contract keeps legacy behavior (any verdict, slots pass-through)") {
    val sid = s"svc-${UUID.randomUUID().toString.take(6)}"
    for
      result <- FlowReportTool.call(
        reportInput("whatever", "out", Some(Json.obj("any" -> "thing".asJson))),
        contractCtx(sid, None)
      )
      stored <- FlowReportStore.get(sid)
      _ <- FlowReportStore.remove(sid)
    yield
      assert(result.isRight, s"no contract → accept: $result")
      assertEquals(stored.map(_.verdict), Some("whatever"))
      assertEquals(stored.flatMap(_.slots("any")), Some("thing".asJson))
  }

  test("contract describe block lists verdict enum and slot schema") {
    val text = FlowNodeContract(caseKeys = Set("pass", "revise"), slots = Map("topics" -> "string")).describe
    assert(text.contains("pass | revise"), s"enum listed: $text")
    assert(text.contains("topics (string)"), s"slot schema listed: $text")
  }

  // ==========================================================
  // Executor level — strict verdict semantics
  // ==========================================================

  /** Answers with plain text after `delay` — no tool calls. */
  private def answerAfter(delay: FiniteDuration, text: String): Stream[IO, StreamChunk] =
    Stream.eval(IO.sleep(delay)) >> Stream(StreamChunk.TextDelta(text), StreamChunk.Done(None, None))

  /** Plain-text answerer — never calls FlowReport. */
  private class PlainTextLlm(text: String, delay: FiniteDuration = 50.millis) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] = answerAfter(delay, text)

  /**
   * Stateful fake: per-session request counter. Request n (up to the script
   * length) → FlowReport tool call; then → plain text. Makes a flow node
   * actually call the (real) FlowReport tool through the real tool pipeline.
   * firstVerdict optionally injects one bad call to exercise self-correction.
   */
  private class CountingFlowReportLlm(
    verdict: String,
    slots: Json,
    capture: Ref[IO, Map[String, List[Message]]],
    firstVerdict: Option[String] = None // optional bad verdict tried before self-correcting
  ) extends LlmHandle[IO]:
    private val counts: Ref[IO, Map[String, Int]] = Ref.unsafe(Map.empty)
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(capture.update(m => m.updated(req.sessionId, req.messages))) >>
        Stream
          .eval(
            counts.modify(m =>
              val n = m.getOrElse(req.sessionId, 0) + 1
              (m.updated(req.sessionId, n), n)
            )
          )
          .flatMap { n =>
            val v = if n == 1 then firstVerdict.getOrElse(verdict) else verdict
            if n <= (if firstVerdict.isDefined then 2 else 1) then
              Stream(
                StreamChunk.ToolCallChunk(
                  ToolCall(
                    id = s"call-$n",
                    name = "FlowReport",
                    input = io.circe.JsonObject.fromIterable(
                      List("verdict" -> Json.fromString(v), "output" -> Json.fromString("reported output"), "slots" -> slots)
                    )
                  )
                ),
                StreamChunk.Done(Some("tool_use"), None)
              )
            else Stream(StreamChunk.TextDelta("done"), StreamChunk.Done(None, None))
        }

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
    val system = ActorSystem(s"strict-verdict-test-${UUID.randomUUID().toString.take(6)}")
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

  /** n1 (switch pass→n2 / fail→$return) → n2 → $return */
  private def switchFlow(strict: Boolean, lenient: Boolean, n2Input: String = "second: $task"): FlowDagDef =
    FlowDagDef(
      name = "tflow",
      description = "test",
      nodes = Map(
        "n1" -> FlowNode(
          agent = "worker",
          input = "$task",
          onComplete = NodeRoute.Switch(
            "$n1.verdict",
            Map("pass" -> NodeRoute.Goto("n2"), "fail" -> NodeRoute.Return),
            None,
            lenient
          )
        ),
        "n2" -> FlowNode("worker", n2Input, NodeRoute.Return)
      ),
      entry = "n1",
      strictVerdict = strict
    )

  test("strict flow: switch node without FlowReport verdict fails with expected keys") {
    val llm = new PlainTextLlm("I think this passes: VERDICT: pass")
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"sv1-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(switchFlow(strict = true, lenient = false), "work", resources, system, None, instId)
        .timeout(30.seconds)
        .flatMap { result =>
          RunningFlowRegistry.list.map(_.find(_.instanceId == instId)).map { rfOpt =>
            assert(result.isLeft, s"strict flow without verdict must fail, got: $result")
            val err = result.swap.toOption.get
            assert(err.contains("no FlowReport verdict"), s"error names the missing verdict: $err")
            assert(err.contains("Expected verdict one of:"), s"error lists expected keys: $err")
            assert(err.contains("pass"), s"error mentions 'pass': $err")
            assertEquals(
              rfOpt.flatMap(_.nodes.get("n1").map(_.status)),
              Some(NodeStatus.Failed),
              "node failed (second line of defense)"
            )
          }
        }
    }
  }

  test("strict flow + lenient switch: legacy guess pipeline still routes") {
    val llm = new PlainTextLlm("analysis...\nVERDICT: pass")
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"sv2-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(switchFlow(strict = true, lenient = true), "work", resources, system, None, instId)
        .timeout(30.seconds)
        .map { result =>
          assert(result.isRight, s"lenient switch must allow the legacy guess: $result")
        }
    }
  }

  test("strictVerdict=false (default): legacy behavior unchanged — text verdict still routes") {
    val llm = new PlainTextLlm("analysis...\nVERDICT: pass")
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"sv3-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(switchFlow(strict = false, lenient = false), "work", resources, system, None, instId)
        .timeout(30.seconds)
        .map { result =>
          assert(result.isRight, s"legacy flows keep the guess pipeline: $result")
        }
    }
  }

  test("strict flow: FlowReport verdict routes the switch and slots resolve downstream") {
    val slots = Json.obj("topics" -> "quantum dots; perovskite; SiC".asJson)
    val capture = Ref.unsafe[IO, Map[String, List[Message]]](Map.empty)
    val llm = new CountingFlowReportLlm("pass", slots, capture)
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"sv4-${UUID.randomUUID().toString.take(6)}"
      // n1 declares a topics slot; n2's input template references it
      val flow = switchFlow(
        strict = true,
        lenient = false,
        n2Input = "subtopics: $n1.slots.topics"
      ).copy(
        nodes = Map(
          "n1" -> switchFlow(strict = true, lenient = false).nodes("n1").copy(outputs = Map("topics" -> "string")),
          "n2" -> FlowNode("worker", "subtopics: $n1.slots.topics", NodeRoute.Return)
        )
      )
      FlowDagExecutor
        .execute(flow, "work", resources, system, None, instId)
        .timeout(30.seconds)
        .flatMap { result =>
          capture.get.map { captured =>
            assert(result.isRight, s"valid FlowReport must route pass→n2: $result")
            // n2's LLM request must contain the RESOLVED slot value, not the template
            val n2Msgs: List[Message] =
              captured.toList.collect { case (sid, msgs) if sid.contains("n2") => msgs }.flatten
            val userTexts = n2Msgs
              .filter(_.role == MessageRole.User)
              .map(m => m.content.fold(t => t, blocks => blocks.collect { case ContentBlock.Text(t) => t }.mkString))
              .mkString("\n")
            assert(
              userTexts.contains("quantum dots; perovskite; SiC"),
              s"n2 must receive resolved $$n1 slots value, got: $userTexts"
            )
            assert(!userTexts.contains("$n1.slots.topics"), "template must be resolved, not passed raw")
          }
        }
    }
  }

  test("invalid FlowReport verdict → ToolError → agent self-corrects in the same node") {
    val slots = Json.obj()
    val capture = Ref.unsafe[IO, Map[String, List[Message]]](Map.empty)
    val llm = new CountingFlowReportLlm("pass", slots, capture, firstVerdict = Some("COMPLETELY-OFF"))
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"sv5-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(switchFlow(strict = true, lenient = false), "work", resources, system, None, instId)
        .timeout(30.seconds)
        .flatMap { result =>
          capture.get.map { captured =>
            assert(result.isRight, s"self-correction must complete the flow: $result")
            // The rejected call's ToolError must have come back to the agent
            // (it travels as a ToolResult block, not a Text block)
            val allText = captured.values.flatten.flatMap { m =>
              m.content.fold(t => t, blocks =>
                blocks.map {
                  case ContentBlock.Text(t)             => t
                  case ContentBlock.ToolResult(_, c, _) => c
                  case other                            => other.toString
                }.mkString
              ) :: Nil
            }.mkString("\n")
            assert(
              allText.contains("verdict must be one of"),
              s"agent must have seen the contract ToolError: ${allText.take(300)}"
            )
          }
        }
    }
  }

end StrictVerdictSpec
