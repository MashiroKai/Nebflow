package nebflow.core.entity

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.SharedResources
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.flow.{NodeStatus, RunningFlowRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, FlowReportData, FlowReportStore}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{ContentBlock, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, StreamChunk, ToolCall}

import java.util.UUID
import scala.concurrent.duration.*

/**
 * R8-P2 counting-barrier semantics (multi-in-edge joins), against the REAL
 * executor + AgentActor:
 *
 *  - the join activates only after ALL branches completed — verifier-style
 *    timing assertion j.startedAt ≥ max(r1, r2).completedAt from real
 *    IO.sleep-driven NodeState timestamps (no fake clock);
 *  - the join's input template resolves every upstream output (barrier
 *    guarantees completeness — nothing arrives late);
 *  - a revise back-edge re-fans the branches and RE-ARMS the barrier
 *    (dynamic indegree per activation round); maxLoop bounds the rounds.
 */
class BarrierSpec extends CatsEffectSuite:

  private def answerAfter(delay: FiniteDuration, text: String): Stream[IO, StreamChunk] =
    Stream.eval(IO.sleep(delay)) >> Stream(StreamChunk.TextDelta(text), StreamChunk.Done(None, None))

  /**
   * Verify-node fake: each verify session reports the next verdict from the
   * queue once (then answers text); all other nodes answer plain text after
   * their configured delay. Captures all requests.
   *
   * 2026-09-06 工具面裁撤批：FlowReport 工具退役——fake 改为直写
   * [[FlowReportStore]]（引擎消费面零变化），barrier/switch 语义断言全保留。
   */
  private class VerifyLlm(
    verdictQueue: Ref[IO, List[String]],
    capture: Ref[IO, Map[String, List[Message]]],
    answered: Ref[IO, Set[String]]
  ) extends LlmHandle[IO]:
    private def nodeIdOf(sessionId: String): String = sessionId.split("-").apply(2)
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(capture.update(m => m.updated(req.sessionId, req.messages))) >>
        Stream.eval(IO(nodeIdOf(req.sessionId))).flatMap {
          case "verify" =>
            Stream.eval(answered.get.flatMap(a => if a(req.sessionId) then IO.pure(false) else answered.update(_ + req.sessionId).as(true))).flatMap {
              case true => // first request of this verify session → report the queued verdict
                Stream.eval(verdictQueue.modify {
                  case v :: rest => (rest, v)
                  case Nil       => (Nil, "pass")
                }).evalTap { verdict =>
                  FlowReportStore.set(req.sessionId, FlowReportData(verdict, "verified"))
                } >> Stream(StreamChunk.TextDelta("done"), StreamChunk.Done(None, None))
              case false => Stream(StreamChunk.TextDelta("done"), StreamChunk.Done(None, None))
            }
          case "r1" => answerAfter(200.millis, "R1-OUT")
          case "r2" => answerAfter(700.millis, "R2-OUT")
          case _    => answerAfter(30.millis, "ok")
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
    val system = ActorSystem(s"barrier-test-${UUID.randomUUID().toString.take(6)}")
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

  /**
   * p → parallel(r1, r2) → verify (2-in-edge barrier) → switch:
   *   pass → out → $return; revise → parallel(r1, r2) [re-fan, re-arm]
   */
  private def loopFlow(maxLoop: Int = 5): FlowDagDef =
    FlowDagDef(
      name = "tflow",
      description = "barrier test",
      nodes = Map(
        "p" -> FlowNode("worker", "$task", NodeRoute.Parallel(List("r1", "r2"), NodeRoute.OnFailMode.Abort)),
        "r1" -> FlowNode("worker", "branch A", NodeRoute.Goto("verify")),
        "r2" -> FlowNode("worker", "branch B", NodeRoute.Goto("verify")),
        "verify" -> FlowNode(
          "worker",
          "check:\n$r1.output\n$r2.output",
          NodeRoute.Switch(
            "$verify.verdict",
            Map(
              "pass" -> NodeRoute.Goto("out"),
              "revise" -> NodeRoute.Parallel(List("r1", "r2"), NodeRoute.OnFailMode.Abort)
            ),
            None,
            lenient = false
          )
        ),
        "out" -> FlowNode("worker", "final: $verify.output", NodeRoute.Return)
      ),
      entry = "p",
      maxLoop = maxLoop
    )

  private def userTextOf(messages: List[Message]): String =
    messages
      .filter(_.role == MessageRole.User)
      .map(m => m.content.fold(t => t, bs => bs.collect { case ContentBlock.Text(t) => t }.mkString))
      .mkString("\n")

  test("B1 join activates only after both branches completed — startedAt ≥ max(completedAt)") {
    val verdicts: Ref[IO, List[String]] = Ref.unsafe(List("pass"))
    val capture: Ref[IO, Map[String, List[Message]]] = Ref.unsafe(Map.empty)
    val answered: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
    withFlowEnv(VerifyLlm(verdicts, capture, answered)) { (resources, system) =>
      val instId = s"bar1-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(loopFlow(), "work", resources, system, None, instId)
        .timeout(30.seconds)
        .flatMap { result =>
          RunningFlowRegistry.list.map(_.find(_.instanceId == instId)).flatMap { rfOpt =>
            capture.get.map { captured =>
              assert(result.isRight, s"barrier flow must complete, got: $result")
              val nodes = rfOpt.map(_.nodes).getOrElse(Map.empty)
              // THE timing assertion: join started only after the slowest branch
              val r1c = nodes("r1").completedAt.getOrElse(0L)
              val r2c = nodes("r2").completedAt.getOrElse(0L)
              val js = nodes("verify").startedAt.getOrElse(0L)
              assert(js >= r1c, s"verify.startedAt ($js) ≥ r1.completedAt ($r1c)")
              assert(js >= r2c, s"verify.startedAt ($js) ≥ r2.completedAt ($r2c)")
              // Completeness: verify's input had BOTH branch outputs
              val verifyUserText = captured.toList
                .filter(_._1.contains("dag-tflow-verify"))
                .flatMap { (_, msgs) => msgs }
                .mkString("\n")
              assert(verifyUserText.contains("R1-OUT"), "verify sees r1's output")
              assert(verifyUserText.contains("R2-OUT"), "verify sees r2's output")
            }
          }
        }
    }
  }

  test("B2 revise back-edge re-fans and re-arms the barrier — second round passes") {
    val verdicts: Ref[IO, List[String]] = Ref.unsafe(List("revise", "pass"))
    val capture: Ref[IO, Map[String, List[Message]]] = Ref.unsafe(Map.empty)
    val answered: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
    withFlowEnv(VerifyLlm(verdicts, capture, answered)) { (resources, system) =>
      val instId = s"bar2-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(loopFlow(maxLoop = 5), "work", resources, system, None, instId)
        .timeout(60.seconds)
        .flatMap { result =>
          RunningFlowRegistry.list.map(_.find(_.instanceId == instId)).flatMap { rfOpt =>
            capture.get.map { captured =>
              assert(result.isRight, s"revise→refan→pass must complete, got: $result")
              val nodes = rfOpt.map(_.nodes).getOrElse(Map.empty)
              assertEquals(nodes.get("out").map(_.status), Some(NodeStatus.Completed), "final node ran")
              // Both branches ran TWICE: round 1 + the revise re-fan. Evidence:
              // two distinct dag sessions per branch (session ids embed a ts).
              val r1Sessions = captured.keys.count(_.contains("dag-tflow-r1"))
              val r2Sessions = captured.keys.count(_.contains("dag-tflow-r2"))
              assert(r1Sessions >= 2, s"r1 re-executed after revise (sessions: $r1Sessions)")
              assert(r2Sessions >= 2, s"r2 re-executed after revise (sessions: $r2Sessions)")
            }
          }
        }
    }
  }

  test("B3 perpetual revise hits maxLoop — barrier rounds are bounded") {
    val verdicts: Ref[IO, List[String]] = Ref.unsafe(List("revise", "revise", "revise"))
    val capture: Ref[IO, Map[String, List[Message]]] = Ref.unsafe(Map.empty)
    val answered: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
    withFlowEnv(VerifyLlm(verdicts, capture, answered)) { (resources, system) =>
      val instId = s"bar3-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(loopFlow(maxLoop = 2), "work", resources, system, None, instId)
        .timeout(60.seconds)
        .map { result =>
          assert(result.isLeft, s"perpetual revise must fail, got: $result")
          val err = result.swap.toOption.get
          assert(
            err.contains("Max loop (2) exceeded"),
            s"failure is the loop cap with the offending edge, got: $err"
          )
        }
    }
  }

end BarrierSpec
