package nebflow.agent

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentDef, AgentKind, AgentRecord, Behaviors}
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.mail.SessionMailbox
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, SubTaskTool, ToolContext}
import nebflow.llm.ModelCandidate
import nebflow.shared.{Branding, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, SubAgentTask, ThinkingConfig}

import scala.concurrent.duration.*

/** Real admission and recovery paths; neither test contacts an LLM or starts a gateway. */
class InternalRunAdmissionSpec extends CatsEffectSuite:
  override def munitIOTimeout: FiniteDuration = 30.seconds

  private def fixture: Resource[IO, (SharedResources, ActorSystem, Ref[IO, Int], os.Path)] =
    for
      roots <- Resource.make(IO.blocking {
        val previous = PathUtil.dataRoot
        val root = os.temp.dir(prefix = "internal-run-admission-")
        os.makeDir.all(root / "agents")
        os.write.over(root / Branding.configFileName, "{}")
        PathUtil.setDataRoot(root)
        (previous, root)
      }) { case (previous, root) =>
        IO.blocking {
          PathUtil.setDataRoot(previous)
          os.remove.all(root)
        }
      }
      root = roots._2
      system <- Resource.make(IO(ActorSystem(s"internal-admission-${java.util.UUID.randomUUID()}")))(
        _.stopAll.handleErrorWith(_ => IO.unit))
      dispatcher <- Dispatcher.parallel[IO]
      calls <- Resource.eval(Ref.of[IO, Int](0))
      resources <- Resource.eval {
        val llm = new LlmHandle[IO]:
          def send(req: LlmRequest): IO[LlmResponse] =
            calls.update(_ + 1) *> IO.raiseError(new IllegalStateException("LLM must not be called"))
          def sendStream(
            req: LlmRequest,
            onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
          ): Stream[IO, StreamChunk] =
            Stream.eval(calls.update(_ + 1)) >> Stream.raiseError[IO](
              new IllegalStateException("LLM must not be called"))
        for
          limiter <- RateLimiter.create()
          tracker <- FileChangeTracker.create(root.toString)
          locks <- FileLockManager.create
          thinking <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
          overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
          muted <- Ref.of[IO, Boolean](false)
        yield SharedResources(
          llm = llm,
          dispatcher = dispatcher,
          sessionStore = SessionStore(root / "sessions", root / "tasks"),
          projectRoot = root,
          thinkingConfigRef = thinking,
          rateLimiter = limiter,
          fileChangeTracker = tracker,
          contextWindow = 100_000,
          agentLibrary = new AgentLibrary(root / "agents"),
          taskStore = FileTaskStore,
          historyArchiver = null,
          fileLockManager = locks,
          sessionModelOverrides = overrides,
          providerRegistry = null,
          healthMonitor = null,
          actorSystem = system,
          voiceMutedRef = muted
        )
      }
    yield (resources, system, calls, root)

  test("SubTask spawn refusal after admission terminalizes its run without starting an LLM") {
    fixture.use { case (resources, system, calls, root) =>
      val parentId = "delegate-kernel-admission-parent"
      def idle: nebflow.actor.Behavior[AgentCommand] =
        Behaviors.receiveMessage[AgentCommand](_ => IO.pure(idle))
      for
        parent <- system.spawn(idle, "admission-parent")
        _ <- resources.agentRegistry.update(_.updated(parentId, AgentRecord(
          sessionId = parentId,
          ref = parent,
          kind = AgentKind.Delegate,
          rootSessionId = parentId
        )))
        result <- SubTaskTool.call(
          JsonObject("prompt" -> "Inspect a source file".asJson, "description" -> "admission refusal".asJson),
          ToolContext(
            sessionId = Some(parentId),
            rootSessionId = Some(parentId),
            agentDef = Some(AgentDef(name = "worker", description = "", tools = Nil, systemPrompt = "")),
            agentActorRef = Some(parent),
            actorSystem = Some(system),
            sharedResources = Some(resources),
            projectRoot = root.toString
          )
        ).attempt
        tasks <- resources.subAgentTaskStore.loadTasks(parentId)
        registry <- resources.agentRegistry.get
        llmCalls <- calls.get
      yield
        val error = result match
          case Left(e) => Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
          case Right(Left(e)) => e.message
          case Right(Right(value)) => fail(s"The kernel spawn gate must reject this child: $value")
        assert(error.contains("R-3a"), s"The real spawn gate must cause the failure: $error")
        assertEquals(tasks.size, 1, "The refusal must happen after durable run admission")
        val task = tasks.head
        assert(task.runId.nonEmpty, "The failed attempt must retain its run identity")
        assertEquals(task.executionStatus, Some("failed"), "No ownerless starting run may survive spawn failure")
        assert(task.completedAt.nonEmpty, "The rejected run needs a durable terminal timestamp")
        assert(task.terminalReason.exists(_.contains("R-3a")), "The spawn failure must remain inspectable")
        assert(!registry.contains(task.taskId), "The rejected child must not remain registered")
        assertEquals(llmCalls, 0)
    }
  }

  test("startup delivers a reason-only failed run without a transcript and unblocks the next run") {
    fixture.use { case (resources, _, calls, _) =>
      val reason = "Actor construction failed before the first session checkpoint"
      for
        parent <- resources.sessionStore.createSession("admission-parent", agentName = Some("worker"))
        task = SubAgentTask(
          taskId = "subtask-reason-only",
          parentSessionId = parent.id,
          agentName = "worker",
          prompt = "Inspect a source file",
          description = "reason-only failure",
          status = "running",
          retryCount = 0,
          spawnedAt = System.currentTimeMillis(),
          completedAt = None,
          lastError = None,
          source = "subtask"
        )
        admitted <- resources.subAgentTaskStore.beginRun(task, "failed-run", parent.id, "nebflow")
        terminal <- resources.subAgentTaskStore.completeRun(
          parent.id, task.taskId, "failed-run", "failed", reason = Some(reason), incomplete = true)
        _ = assert(admitted.isRight && terminal.isRight, "The failed run must exist before recovery")
        _ = assertEquals(terminal.toOption.flatMap(_.resultRef), None, "No transcript exists for a pre-spawn failure")
        _ <- SubAgentStartupRecovery.recoverPendingInternal(
          resources.subAgentTaskStore, resources.sessionStore, Some(resources))
        _ <- SubAgentStartupRecovery.recoverPendingInternal(
          resources.subAgentTaskStore, resources.sessionStore, Some(resources))
        pending <- SessionMailbox.scanPending(parent.id)
        taken <- SessionMailbox.scanTaken(parent.id)
        row <- resources.subAgentTaskStore.findByTaskId(task.taskId)
        next <- resources.subAgentTaskStore.beginRun(task, "next-run", parent.id, "nebflow")
        llmCalls <- calls.get
      yield
        val mail = pending ++ taken
        assertEquals(mail.size, 1, "Repeated recovery must deliver exactly one terminal notification")
        assertEquals(mail.head.event.eventType, "failed")
        assertEquals(mail.head.event.payload, reason)
        assertEquals(mail.head.identity, terminal.toOption.flatMap(_.notificationId).get)
        assertEquals(row.flatMap(_.notificationPending), Some(false), "Durable enqueue must settle pending delivery")
        assert(next.isRight, s"Delivered failure must not permanently block a new run: $next")
        assertEquals(llmCalls, 0)
    }
  }
end InternalRunAdmissionSpec
