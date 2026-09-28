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
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.*

import java.util.UUID
import scala.concurrent.duration.*

/** Flow-node LLM supervision P2 (2026-08-26 §5.P2, acceptance #2/#4):
  * a flow node that dies on a retryable LLM stall is checkpoint-RESTARTED
  * (same session, persisted messages reloaded, continue instruction) instead
  * of scrapping the flow; a non-retryable failure with explicit onError=stop
  * still fails immediately (predefined compatibility). */
class FlowNodeSupervisionSpec extends CatsEffectSuite:

  /** Stall N times, then succeed with a FlowReport pass. Distinguishes rounds
    * by per-session request count; captures every request for assertions.
    * `stamps` records a monotonic timestamp (ms) per request — the P1-backoff
    * timing lock needs inter-request gaps. */
  private class StallLlm(
    stallTimes: Int,
    capture: Ref[IO, Map[String, List[Int]]],
    texts: Ref[IO, Map[String, List[List[Message]]]],
    stamps: Ref[IO, Map[String, List[Long]]] = Ref.unsafe[IO, Map[String, List[Long]]](Map.empty)
  ) extends LlmHandle[IO]:
    private def isFlowNode(req: LlmRequest): Boolean = req.sessionId.startsWith("dag-")
    private def isP(req: LlmRequest): Boolean = req.sessionId.split("-").length > 3 &&
      req.sessionId.split("-").apply(2) == "p"
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(IO.monotonic.flatMap(t => stamps.update(m => m.updated(req.sessionId, m.getOrElse(req.sessionId, Nil) :+ t.toMillis)))) >>
        Stream.eval(capture.update(m => m.updated(req.sessionId, m.getOrElse(req.sessionId, Nil) :+ m.getOrElse(req.sessionId, Nil).size + 1))) >>
        Stream.eval(texts.update(m => m.updated(req.sessionId, m.getOrElse(req.sessionId, Nil) :+ req.messages))) >>
        Stream.eval(IO(isFlowNode(req) && isP(req))).flatMap {
          case true =>
            // Tool-result round (FlowReport already consumed) → plain text done.
            val lastUserIsToolResult = req.messages.reverse
              .find(_.role == MessageRole.User)
              .exists(_.content.fold(_ => false, bs => bs.exists {
                case ContentBlock.ToolResult(_, _, _) => true
                case _                                => false
              }))
            if lastUserIsToolResult then
              Stream(StreamChunk.TextDelta("done"), StreamChunk.Done(None, None))
            else
              Stream.eval(capture.get.map(_.getOrElse(req.sessionId, Nil).size)).flatMap { n =>
                if n <= stallTimes then
                  // Simulate the incident: a phase-2 mid-stream stall (typed,
                  // Transient) — the agent layer retries once, then the fatal
                  // Failed(retryable=true) reaches the executor's Restart path.
                  Stream.raiseError[IO](StreamInactivityTimeout(61_000L, "LLM stream inactive for 120s"))
                else
                  Stream(
                    StreamChunk.ToolCallChunk(
                      ToolCall(
                        id = "call-1",
                        name = "FlowReport",
                        input = io.circe.JsonObject.fromIterable(
                          List("verdict" -> Json.fromString("pass"), "output" -> Json.fromString("P done"))
                        )
                      )
                    ),
                    StreamChunk.Done(Some("tool_use"), None)
                  )
              }
          case false =>
            Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))
        }
  end StallLlm

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

  private def withEnv[A](llm: LlmHandle[IO])(test: (SharedResources, ActorSystem) => IO[A]): IO[A] =
    val system = ActorSystem(s"flow-supervision-${UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val dataRoot = tmp / "data"
    IO.delay {
      os.makeDir.all(dataRoot / "flows" / "sflow" / "agents" / "worker")
      os.write(
        dataRoot / "flows" / "sflow" / "agents" / "worker" / "agent.json",
        """{"name":"worker","description":"test worker","tools":["Read"]}"""
      )
      PathUtil.setDataRoot(dataRoot)
    }.guaranteeCase {
      case cats.effect.Outcome.Succeeded(_) => IO.unit
      case _ => IO.unit
    }.flatMap { _ =>
      // AgentActor writes flow sessions via the SHARED sessionStore from
      // resources (tmp), but FlowReportStore is a global keyed by sessionId —
      // isolated per test by unique session ids. LlmLogWriter off.
      nebflow.core.LlmLogWriter.setEnabled(false)
      mkResources(system, tmp, llm).flatMap(test(_, system))
    }.guarantee {
      IO.delay { PathUtil.setDataRoot(prevRoot) } *> system.stopAll
        .handleErrorWith(_ => IO.unit)
    }

  private def twoNodeFlow(onError: Option[OnError] = None, maxRetries: Int = 0): FlowDagDef =
    FlowDagDef(
      name = "sflow",
      description = "supervision spec flow",
      entry = "p",
      nodes = Map(
        "p" -> FlowNode(
          agent = "worker",
          input = "do P",
          onComplete = NodeRoute.Goto("j"),
          onError = onError,
          maxRetries = maxRetries
        ),
        "j" -> FlowNode(
          agent = "worker",
          input = "do J",
          onComplete = NodeRoute.Return
        )
      )
    )

  override def munitIOTimeout: scala.concurrent.duration.Duration = 120.seconds

  test("P2-1: retryable stall → checkpoint restart resumes the SAME session and the flow completes") {
    // Backoff floor down to 100ms so the two agent-level retries don't stall the spec.
    val prevBackoff = nebflow.agent.AgentActor.testInactivityBackoffMs
    nebflow.agent.AgentActor.testInactivityBackoffMs = Some(100L)
    val capture = Ref.unsafe[IO, Map[String, List[Int]]](Map.empty)
    val texts = Ref.unsafe[IO, Map[String, List[List[Message]]]](Map.empty)
    val llm = new StallLlm(stallTimes = 2, capture, texts)
    withEnv(llm) { (resources, system) =>
      for
        result <- FlowDagExecutor.execute(
          twoNodeFlow(onError = Some(OnError.Restart), maxRetries = 1),
          "task",
          resources,
          system,
          None,
          s"supervision-${UUID.randomUUID().toString.take(6)}"
        )
        caps <- capture.get
        pSessions = caps.keys.filter(_.contains("-p-")).toList
        // The checkpoint semantics: all P rounds share ONE session id.
        _ = assert(result.isRight, s"flow must complete after checkpoint restart, got: $result")
        _ = assert(pSessions.size == 1, s"P must reuse ONE stable session across restarts, got: $pSessions")
        pRounds = caps.getOrElse(pSessions.head, Nil)
        _ = assertEquals(pRounds.size, 4, s"P expected 4 LLM requests (stall, retry-stall, resume FlowReport, resume text-done), got ${pRounds.size}")
        // The resumed request carries the checkpoint history: its messages
        // include the ORIGINAL instruction plus the continue prompt.
        reqs <- texts.get.map(_.getOrElse(pSessions.head, Nil))
        // The resume round's FIRST request is the one that carries the
        // checkpoint (the last request is the FlowReport tool-result round).
        resumed = reqs(reqs.size - 2)
        hasOriginal = resumed.exists { m =>
          m.role == MessageRole.User && m.content.fold(_.contains("do P"), _.exists {
            case ContentBlock.Text(t) => t.contains("do P")
            case _                    => false
          })
        }
        hasContinue = resumed.lastOption.exists { m =>
          m.role == MessageRole.User && m.content.fold(_.contains("continue your task"), _.exists {
            case ContentBlock.Text(t) => t.contains("continue your task")
            case _                    => false
          })
        }
        _ = assert(hasOriginal, "resumed request must carry the checkpoint history (original instruction)")
        _ = assert(hasContinue, "resumed request must end with the continue instruction")
      yield ()
    }.guarantee {
      IO { nebflow.agent.AgentActor.testInactivityBackoffMs = prevBackoff }
    }
  }

  test("P2-2: non-retryable failure with explicit onError=stop fails immediately (predefined compat)") {
    val capture = Ref.unsafe[IO, Map[String, List[Int]]](Map.empty)
    val texts = Ref.unsafe[IO, Map[String, List[List[Message]]]](Map.empty)
    // A Permanent auth-shaped error — NOT retryable, NOT checkpointed.
    class AuthFailLlm extends LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
        Stream.eval(capture.update(m => m.updated(req.sessionId, m.getOrElse(req.sessionId, Nil) :+ 1))) >>
          (if req.sessionId.startsWith("dag-") && req.sessionId.split("-").apply(2) == "p"
           then Stream.raiseError[IO](new RuntimeException("401 unauthorized auth failed"))
           else Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None)))
    val llm = new AuthFailLlm
    withEnv(llm) { (resources, system) =>
      for
        result <- FlowDagExecutor.execute(
          twoNodeFlow(onError = Some(OnError.Stop)),
          "task",
          resources,
          system,
          None,
          s"stop-${UUID.randomUUID().toString.take(6)}"
        )
        caps <- capture.get
        pRounds = caps.filter(_._1.contains("-p-")).values.map(_.size).sum
      yield
        assert(result.isLeft, s"explicit stop must fail the flow, got: $result")
        assertEquals(pRounds, 1, "no restart dispatch after a non-retryable stop failure")
    }
  }

  test("P2-3: FlowDagCompiler.applyDynamicDefaults — dynamic defaults injected, user JSON wins") {
    import nebflow.core.entity.NodeRoute.Goto
    val flow = FlowDagDef(
      name = "dflow",
      description = "compiler defaults spec",
      entry = "a",
      nodes = Map(
        "a" -> FlowNode(agent = "X", input = "i", onComplete = Goto("b")),
        "b" -> FlowNode(agent = "X", input = "i", onComplete = NodeRoute.Return,
          onError = Some(OnError.Stop), maxRetries = 3)
      )
    )
    val compiled = FlowDagCompiler.applyDynamicDefaults(flow)
    // Undeclared node gets supervision defaults.
    assertEquals(compiled.nodes("a").onError, Some(OnError.Restart))
    assertEquals(compiled.nodes("a").maxRetries, 1)
    // Explicit declarations are preserved verbatim.
    assertEquals(compiled.nodes("b").onError, Some(OnError.Stop))
    assertEquals(compiled.nodes("b").maxRetries, 3)
    // The input flow is not mutated.
    assertEquals(flow.nodes("a").onError, None)
  }
  test("P1-backoff: inactivity retry waits the FULL backoff before re-dispatch") {
    // Regression lock for the fake-sleep bug (2026-08-26): the retry path was
    // `ctx.forkTurn(IO.sleep(delay)) *> pipeLlmCall(...)` — forkTurn is
    // fire-and-forget, so the re-dispatch ran IMMEDIATELY and the backoff
    // (both overload ≥5s and inactivity ≥30s) never delayed anything. With a
    // 400ms injected floor, the gap between the 1st (stalling) and 2nd
    // (retry) request must be ≥300ms; the fake-sleep form measured ~0-20ms.
    val prevBackoff = nebflow.agent.AgentActor.testInactivityBackoffMs
    nebflow.agent.AgentActor.testInactivityBackoffMs = Some(400L)
    val capture = Ref.unsafe[IO, Map[String, List[Int]]](Map.empty)
    val texts = Ref.unsafe[IO, Map[String, List[List[Message]]]](Map.empty)
    val stamps = Ref.unsafe[IO, Map[String, List[Long]]](Map.empty)
    val llm = new StallLlm(stallTimes = 2, capture, texts, stamps)
    withEnv(llm) { (resources, system) =>
      for
        result <- FlowDagExecutor.execute(
          twoNodeFlow(onError = Some(OnError.Restart), maxRetries = 1),
          "task",
          resources,
          system,
          None,
          s"backoff-${UUID.randomUUID().toString.take(6)}"
        )
        stampsNow <- stamps.get
        pSessions = stampsNow.keys.filter(_.contains("-p-")).toList
        times = stampsNow.getOrElse(pSessions.head, Nil)
        gap = if times.size >= 2 then times(1) - times(0) else -1L
        _ = assert(result.isRight, s"flow must still complete, got: $result")
        _ = assert(times.size >= 2, s"expected ≥2 P requests for a gap, got ${times.size}")
        _ = assert(gap >= 300, s"retry must wait the backoff floor; gap=${gap}ms (fake-sleep regression: forkTurn sleep is fire-and-forget)")
      yield ()
    }.guarantee {
      IO { nebflow.agent.AgentActor.testInactivityBackoffMs = prevBackoff }
    }
  }

end FlowNodeSupervisionSpec
