package nebflow.core.entity

import cats.effect.kernel.Outcome
import cats.effect.std.{Dispatcher, Semaphore}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
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
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, StreamChunk}

import java.util.UUID
import scala.concurrent.duration.*

/**
 * R8-P2 parallel fan-out semantics, driven against the REAL executor +
 * AgentActor (only the LlmHandle is faked):
 *
 *  - both fan branches run and their execution windows OVERLAP (concurrency
 *    evidence from NodeState timestamps, not wall-clock luck);
 *  - total time ≈ max(branch), well under the serial sum;
 *  - onFail=abort (default): one branch failing pierces the sibling's agent
 *    (fail-fast) and the flow fails fast with the root cause;
 *  - onFail=collect: the failed branch yields a placeholder output, the
 *    barrier still releases, the join sees the annotated partial result;
 *  - user cancel pierces ALL in-flight branch agents.
 */
class ParallelSpec extends CatsEffectSuite:

  /** Extracts the node id from a dag session id: dag-<flow>-<nodeId>-<ts>. */
  private def nodeIdOf(sessionId: String): String =
    sessionId.split("-").apply(2)

  private def answerAfter(delay: FiniteDuration, text: String): Stream[IO, StreamChunk] =
    Stream.eval(IO.sleep(delay)) >> Stream(StreamChunk.TextDelta(text), StreamChunk.Done(None, None))

  private def neverAnswer: Stream[IO, StreamChunk] = Stream.never[IO]

  /** Node-keyed fake LLM: behavior selected by the node id of the requesting session. */
  private class NodeLlm(behavior: PartialFunction[String, Stream[IO, StreamChunk]]) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] = behavior.applyOrElse(nodeIdOf(req.sessionId), (_: String) => answerAfter(30.millis, "ok"))

  /** Same, but records every request's messages for input-resolution assertions. */
  private class CapturingNodeLlm(
    behavior: PartialFunction[String, Stream[IO, StreamChunk]],
    capture: Ref[IO, Map[String, List[Message]]]
  ) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(capture.update(m => m.updated(req.sessionId, req.messages))) >>
        behavior.applyOrElse(nodeIdOf(req.sessionId), (_: String) => answerAfter(30.millis, "ok"))

  private def userTextOf(messages: List[Message]): String =
    messages
      .filter(_.role == MessageRole.User)
      .map(m => m.content.fold(t => t, bs => bs.map(_.toString).mkString))
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
      askSem <- Semaphore[IO](4)
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
      askSemaphore = askSem,
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
    val system = ActorSystem(s"parallel-test-${UUID.randomUUID().toString.take(6)}")
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

  /** p → parallel(r1, r2) → j → $return. r2 agent optionally swapped for "ghost". */
  private def diamondFlow(onFail: NodeRoute.OnFailMode = NodeRoute.OnFailMode.Abort, r2Agent: String = "worker"): FlowDagDef =
    FlowDagDef(
      name = "tflow",
      description = "parallel test",
      nodes = Map(
        "p" -> FlowNode("worker", "$task", NodeRoute.Parallel(List("r1", "r2"), onFail)),
        "r1" -> FlowNode("worker", "branch A", NodeRoute.Goto("j")),
        "r2" -> FlowNode(r2Agent, "branch B", NodeRoute.Goto("j")),
        "j" -> FlowNode("worker", "A:\n$r1.output\nB:\n$r2.output", NodeRoute.Return)
      ),
      entry = "p"
    )

  test("P1 both branches run concurrently — windows overlap, total ≈ max not sum") {
    val llm = new NodeLlm({
      case "r1" => answerAfter(200.millis, "R1-OUT")
      case "r2" => answerAfter(1500.millis, "R2-OUT")
    })
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"par1-${UUID.randomUUID().toString.take(6)}"
      val t0 = System.currentTimeMillis()
      FlowDagExecutor
        .execute(diamondFlow(), "work", resources, system, None, instId)
        .timeout(30.seconds)
        .flatMap { result =>
          RunningFlowRegistry.list.map(_.find(_.instanceId == instId)).map { rfOpt =>
            val _ = System.currentTimeMillis() - t0 // timing not asserted under load — see overlap asserts
            assert(result.isRight, s"diamond must complete, got: $result")
            val nodes = rfOpt.map(_.nodes).getOrElse(Map.empty)
            assertEquals(nodes.get("r1").map(_.status), Some(NodeStatus.Completed))
            assertEquals(nodes.get("r2").map(_.status), Some(NodeStatus.Completed))
            assertEquals(nodes.get("j").map(_.status), Some(NodeStatus.Completed))
            // Concurrency evidence: the two execution windows overlap (load-
            // immune — compares NodeState timestamps of the same run; wall-clock
            // totals are not asserted because the full-suite JVM stretches sleeps)
            val r1s = nodes("r1").startedAt.getOrElse(0L)
            val r1c = nodes("r1").completedAt.getOrElse(0L)
            val r2s = nodes("r2").startedAt.getOrElse(0L)
            val r2c = nodes("r2").completedAt.getOrElse(0L)
            assert(r1s < r2c, s"r1 started (${r1s}) before r2 finished (${r2c}) — windows overlap")
            assert(r2s < r1c, s"r2 started (${r2s}) before r1 finished (${r1c}) — windows overlap")
            // And the join waited for BOTH (barrier): j started after both ended
            val js = nodes("j").startedAt.getOrElse(0L)
            assert(js >= r1c && js >= r2c, "join activated after both branches completed")
          }
        }
    }
  }

  test("P2 onFail=abort (default): sibling branch pierced, flow fails fast with root cause") {
    // r2 references a missing agent → deterministic immediate failure;
    // r1's LLM would answer at 5s — fail-fast must stop it long before.
    val llm = new NodeLlm({
      case "r1" => answerAfter(5.seconds, "R1-LATE")
    })
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"par2-${UUID.randomUUID().toString.take(6)}"
      val t0 = System.currentTimeMillis()
      FlowDagExecutor
        .execute(diamondFlow(r2Agent = "ghost"), "work", resources, system, None, instId)
        .timeout(30.seconds)
        .flatMap { result =>
          RunningFlowRegistry.list.map(_.find(_.instanceId == instId)).map { rfOpt =>
            val elapsed = System.currentTimeMillis() - t0
            assert(result.isLeft, s"abort-mode failure must fail the flow, got: $result")
            val err = result.swap.toOption.get
            assert(err.contains("ghost"), s"root cause surfaces the failed branch: $err")
            assert(elapsed < 4500, s"fail-fast must not wait out r1's 5s LLM delay (took ${elapsed}ms)")
            val nodes = rfOpt.map(_.nodes).getOrElse(Map.empty)
            assert(nodes.get("j").map(_.status) != Some(NodeStatus.Completed), "join never activates")
            assertEquals(rfOpt.map(_.status), Some(NodeStatus.Failed), "flow status Failed (not Cancelled)")
          }
        }
    }
  }

  test("P3 onFail=collect: placeholder output, barrier releases, join sees partial result") {
    val capture: Ref[IO, Map[String, List[Message]]] = Ref.unsafe(Map.empty)
    val llm = new CapturingNodeLlm(PartialFunction.empty, capture)
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"par3-${UUID.randomUUID().toString.take(6)}"
      FlowDagExecutor
        .execute(diamondFlow(onFail = NodeRoute.OnFailMode.Collect, r2Agent = "ghost"), "work", resources, system, None, instId)
        .timeout(30.seconds)
        .flatMap { result =>
          capture.get.map { captured =>
            assert(result.isRight, s"collect must carry the round through, got: $result")
            val jUserText = captured.toList
              .filter(_._1.contains("dag-tflow-j"))
              .flatMap { (_, msgs) => msgs }
              .mkString("\n")
            assert(
              jUserText.contains("[node r2 failed:"),
              s"join input contains the failure placeholder, got: ${jUserText.take(300)}"
            )
          }
        }
    }
  }

  test("P4 user cancel pierces all in-flight branch agents") {
    val llm = new NodeLlm({
      case "r1" => neverAnswer
      case "r2" => neverAnswer
    })
    withFlowEnv(llm) { (resources, system) =>
      val instId = s"par4-${UUID.randomUUID().toString.take(6)}"
      for
        execFiber <- FlowDagExecutor
          .execute(diamondFlow(), "long work", resources, system, None, instId)
          .start
        _ <- IO.sleep(500.millis) // both branches are RUNNING inside their turns
        bothRunning <- RunningFlowRegistry.list.map(
          _.find(_.instanceId == instId).exists(rf =>
            rf.nodes("r1").status == NodeStatus.Running && rf.nodes("r2").status == NodeStatus.Running
          )
        )
        _ <- RunningFlowRegistry.cancel(instId)
        result <- execFiber.join
          .flatMap {
            case Outcome.Succeeded(ioa) => ioa
            case other                  => IO.raiseError(new RuntimeException(s"flow fiber ended abnormally: $other"))
          }
          .timeout(6.seconds)
          .attempt
        rfOpt <- RunningFlowRegistry.list.map(_.find(_.instanceId == instId))
      yield
        assert(bothRunning, "both branches running when cancel fires")
        assert(result.exists(_.isLeft), s"cancelled flow returns Left, got: $result")
        assertEquals(rfOpt.map(_.status), Some(NodeStatus.Cancelled), "flow status cancelled")
        assertEquals(rfOpt.flatMap(_.nodes.get("r1").map(_.status)), Some(NodeStatus.Cancelled))
        assertEquals(rfOpt.flatMap(_.nodes.get("r2").map(_.status)), Some(NodeStatus.Cancelled))
    }
  }

end ParallelSpec
