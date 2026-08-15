package nebflow.agent

import cats.effect.std.{Dispatcher, Semaphore}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{ContentBlock, LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, StreamChunk, ToolCall}

import java.util.UUID
import scala.concurrent.duration.*

/**
 * 2026-08-15 FlowTrigger outage — actor-level regression test.
 *
 * Drives the REAL AgentActor through a full turn with only the LlmHandle
 * faked, reproducing the production failure mode: schema says FlowTrigger
 * exists (request.tools), executor says it doesn't (dropped call →
 * "Tool not available: FlowTrigger" injected as the ToolResult).
 *
 * Also pins the restart-restore lifecycle invariant: a session restored
 * with pre-restart history must rebuild systemStable from the CURRENT
 * AgentDef on its first turn (fresh actor = empty cachedSystemStable =
 * isLifecycleRebuild), so tool/flow sections never advertise a
 * pre-restart world.
 */
class FlowTriggerExecutionSpec extends CatsEffectSuite:

  /** Scripted LLM: records every LlmRequest, answers by request index. */
  private class RecordingLlm(responses: Vector[LlmRequest => Stream[IO, StreamChunk]]) extends LlmHandle[IO]:
    val requests: Ref[IO, List[LlmRequest]] = Ref.unsafe(Nil)
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(req :: _)) >>
        Stream.eval(requests.get.map(_.size - 1)).flatMap { idx =>
          responses(Math.min(idx, responses.size - 1))(req)
        }

  private def textAnswer(text: String): Stream[IO, StreamChunk] =
    Stream(StreamChunk.TextDelta(text), StreamChunk.Done(None, None))

  private def flowTriggerCall(flow: String): Stream[IO, StreamChunk] =
    Stream(
      StreamChunk.ToolCallChunk(ToolCall(
        "call-ft-1",
        "FlowTrigger",
        JsonObject("flow" -> flow.asJson, "prompt" -> "run the thing".asJson)
      )),
      StreamChunk.Done(Some("tool_use"), None)
    )

  private def toolResults(req: LlmRequest): List[String] =
    req.messages.flatMap(_.content.toOption.toList.flatten).collect {
      case ContentBlock.ToolResult(_, content, _) => content
    }

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
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
      agentLibrary = new AgentLibrary(tmp / "agents"),
      askSemaphore = askSem,
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
    cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  /** Temp dataRoot + fixture agents/flows; restores dataRoot and stops actors after. */
  private def withEnv[A](llm: LlmHandle[IO])(
    test: (SharedResources, ActorSystem, os.Path) => IO[A]
  ): IO[A] =
    val system = ActorSystem(s"ftf-exec-test-${UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val dataRoot = tmp / "data"
    IO.delay {
      os.makeDir.all(dataRoot)
      // Global agent "Restored" — declares a flow that exists on disk.
      os.makeDir.all(tmp / "agents" / "Restored")
      os.write(
        tmp / "agents" / "Restored" / "agent.json",
        """{"name":"Restored","description":"flows-declaring agent","tools":["Read"],"flows":["demo-flow"]}"""
      )
      // The declared flow — loadFlow must parse it for the Available Flows
      // catalog (FlowStructure requires >= 2 nodes: single agent = skill).
      os.makeDir.all(dataRoot / "flows")
      os.write(
        dataRoot / "flows" / "demo-flow.json",
        """{"name":"demo-flow","description":"Demo flow for catalog rendering","nodes":{"n1":{"agent":"Restored","input":"$task","onComplete":"n2"},"n2":{"agent":"Restored","input":"$n1.output","onComplete":"$return"}},"entry":"n1"}"""
      )
      PathUtil.setDataRoot(dataRoot)
    }.bracket { _ =>
      mkResources(system, tmp, llm).flatMap(test(_, system, tmp))
    } { _ =>
      IO.delay(PathUtil.setDataRoot(prevRoot)) *>
        system.stopAll.attempt.void *>
        IO.delay(if os.exists(tmp) then os.remove.all(tmp)).attempt.void
    }

  // ===== The outage repro: executor must accept what the schema advertised =====

  test("flows-declaring agent: FlowTrigger call executes, not dropped as 'Tool not available'"):
    val llm = new RecordingLlm(Vector(
      _ => flowTriggerCall("ghost-flow"), // turn 1: model calls FlowTrigger
      _ => textAnswer("done")             // turn 2: wrap up after the tool result
    ))
    withEnv(llm) { (resources, system, _) =>
      val memberDef = AgentDef(
        name = "Backend",
        description = "team member with flows",
        tools = List("Read"),
        systemPrompt = "",
        category = "team",
        flows = List("ghost-flow")
      )
      for
        ref <- system.spawn(
          AgentActor(
            agentDef = memberDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(s"ftf-exec-${UUID.randomUUID().toString.take(6)}"),
            sessionName = Some("Backend")
          ),
          "ftf-exec-member"
        )
        _ <- ref ! AgentCommand.UserInput("trigger the ghost flow")
        _ <- waitUntil(30.seconds)(llm.requests.get.map(_.size >= 2))
        reqs <- llm.requests.get.map(_.reverse)
        (schemaReq, toolReq) = (reqs.head, reqs(1))
        schemaTools = schemaReq.tools.toList.flatten
        results = toolResults(toolReq)
      yield
        // Layer 1 (schema): the LLM request must advertise FlowTrigger.
        assert(
          schemaTools.exists(_.name == "FlowTrigger"),
          s"request.tools must contain FlowTrigger (got: ${schemaTools.map(_.name)})"
        )
        // Layer 2 (execution): the call must reach FlowTriggerTool itself
        // ("Flow 'ghost-flow' not found") instead of being dropped by
        // buildAllowedToolSet ("Tool not available: FlowTrigger").
        assert(results.nonEmpty, "second request must carry the tool result")
        val joined = results.mkString("\n")
        assert(!joined.contains("Tool not available"), s"call was dropped by the executor: $joined")
        assert(joined.contains("not found"), s"FlowTriggerTool must have executed: $joined")
    }

  // ===== Restart-restore lifecycle invariant =====

  test("restored session rebuilds systemStable from the CURRENT def on its first turn"):
    val llm = new RecordingLlm(Vector(_ => textAnswer("ok")))
    withEnv(llm) { (resources, system, tmp) =>
      // The real restart chain: ensureRootAgent → resolveAgentDef →
      // agentLibrary.get(name) — exercised directly here.
      for
        defnOpt <- resources.agentLibrary.get("Restored")
        defn = defnOpt.getOrElse(fail("Restored agent must load from the agents dir"))
        restoredHistory = List(
          Message(MessageRole.User, Left("question from before the restart")),
          Message(MessageRole.Assistant, Left("answer from before the restart"))
        )
        ref <- system.spawn(
          AgentActor(
            agentDef = defn,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(s"ftf-restore-${UUID.randomUUID().toString.take(6)}"),
            sessionName = Some("Restored"),
            initialMessages = restoredHistory
          ),
          "ftf-restore-agent"
        )
        _ <- ref ! AgentCommand.UserInput("continue after restart")
        _ <- waitUntil(30.seconds)(llm.requests.get.map(_.nonEmpty))
        req <- llm.requests.get.map(_.reverse.head)
        stable = req.systemStable.getOrElse("")
        historyKept = req.messages.take(2).map(_.role)
      yield
        // The restored history is folded into the working state…
        assertEquals(historyKept, List(MessageRole.User, MessageRole.Assistant))
        // …and the first turn is a lifecycle rebuild: systemStable reflects
        // the CURRENT def (flows → Available Flows catalog), not a cached
        // pre-restart string.
        assert(stable.contains("Available Flows"), s"systemStable must carry the flows catalog: ${stable.take(400)}")
        assert(stable.contains("demo-flow"), "the flow declared in agent.json must appear in the catalog")
        assert(
          req.tools.toList.flatten.exists(_.name == "FlowTrigger"),
          "the rebuilt tool list must contain FlowTrigger"
        )
    }
end FlowTriggerExecutionSpec
