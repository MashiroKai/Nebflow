package nebflow.agent

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentDef, AgentKind, AgentRecord, Behaviors}
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.mail.SessionMailbox
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, SubTaskTool, ToolContext}
import nebflow.llm.ModelCandidate
import nebflow.shared.{Branding, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, SubAgentTask, ThinkingConfig, *}

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

  test("startup reconfirms an acknowledged protected terminal with missing mailbox evidence") {
    fixture.use { case (base, system, calls, root) =>
      for
        _ <- IO.blocking(os.makeDir.all(root / "sessions"))
        parent <- base.sessionStore.createSession("recovery-parent", agentName = Some("worker"))
        _ <- ExecutionCommitStore.resource((root / "sessions").toNIO).use { authority =>
          val admission = ExecutionAdmission("protected-child", "run", "admission", "request", "attempt", "incarnation",
            parent.id, "business", "audit", Json.fromString("initial"), "input")
          val task = SubAgentTask(admission.sessionId, parent.id, "worker", "initial", "test", "running", 0,
            System.currentTimeMillis(), None, None, "delegate")
          def cmd(ref: ExecutionCommitRef, id: String, event: ExecutionEvent) = ExecutionTransition(admission.sessionId,
            admission.executionId, id, id, ref, admission.attemptId, admission.incarnationId, event)
          def ref(result: ExecutionCommitResult) = result.asInstanceOf[ExecutionCommitResult.Committed].ref
          val envelope = Json.obj("source" -> "delegate".asJson, "eventType" -> "completed".asJson,
            "payload" -> "done".asJson, "metadata" -> Json.obj(), "correlationId" -> admission.sessionId.asJson,
            "blocks" -> Json.Null)
          val resources = base.copy(executionCommitPort = authority)
          for
            _ <- base.subAgentTaskStore.beginRun(task, "run", parent.id, "nebflow")
            admitted <- authority.admit(admission)
            started <- authority.transition(cmd(ref(admitted), "start", ExecutionEvent.AttemptStarted))
            _ <- authority.registerRunner(ExecutionRunner.Attempt(admission.sessionId, "run", "attempt", "incarnation")).use { registered =>
              val runner = registered.toOption.get
              for
                model <- runner.startModel(cmd(ref(started), "model", ExecutionEvent.ModelRequested(Json.Null, 0)))(IO.unit)
                (modelRef, receipt) = model.toOption.get
                decision <- runner.modelDecision(cmd(modelRef, "decision", ExecutionEvent.ModelDecided(
                  ExecutionModelDecision(Json.Null, Nil))), receipt)
                checkpoint <- authority.commitRound(cmd(ref(decision), "round", ExecutionEvent.RoundCommitted(List("input"), Json.obj())))
                terminal <- runner.installTerminal(cmd(ref(checkpoint), "terminal", ExecutionEvent.TerminalInstallPrepared("", envelope)), envelope)
                _ = assert(terminal.isInstanceOf[ExecutionCommitResult.Committed])
                chain <- authority.observeChain(admission.sessionId, "run")
                resultRef = chain.asInstanceOf[ExecutionChainObservation.Verified].state.terminalResultRef.get
                projection <- runner.projectTerminal(base.subAgentTaskStore, resultRef)
                _ = assert(projection.isRight)
                _ <- base.subAgentTaskStore.acknowledgeNotification(parent.id, admission.sessionId, "run", s"${admission.sessionId}#run")
              yield ()
            }
            before <- base.subAgentTaskStore.findByTaskId(admission.sessionId)
            _ = assertEquals(before.flatMap(_.notificationPending), Some(false))
            _ <- SubAgentStartupRecovery.sweep(base.subAgentTaskStore, base.sessionStore,
              Some(SubAgentStartupRecovery.RevivalDeps(resources, system, None)), manual = true)
            _ <- SubAgentStartupRecovery.sweep(base.subAgentTaskStore, base.sessionStore,
              Some(SubAgentStartupRecovery.RevivalDeps(resources, system, None)), manual = true)
            pending <- SessionMailbox.scanPending(parent.id)
            taken <- SessionMailbox.scanTaken(parent.id)
            count <- calls.get
          yield
            assertEquals((pending ++ taken).count(_.identity == s"${admission.sessionId}#run"), 1)
            assertEquals(count, 0)
        }
      yield ()
    }
  }

  test("recovery commits and delivers a saved terminal envelope for an active subtask run") {
    fixture.use { case (resources, _, _, _) =>
      for
        parent <- resources.sessionStore.createSession("orphan-envelope-parent", agentName = Some("worker"))
        task = SubAgentTask("subtask-orphan-envelope", parent.id, "worker", "investigate", "test",
          "running", 0, System.currentTimeMillis(), None, None, "subtask")
        admitted <- resources.subAgentTaskStore.beginRun(task, "run-1", parent.id, "nebflow")
        _ = assert(admitted.isRight)
        running <- resources.subAgentTaskStore.updateRun(parent.id, task.taskId, "run-1", executionStatus = Some("running"))
        _ = assert(running.isRight)
        event = AgentCommand.ExternalEvent("subtask", "completed", "result from completed worker",
          JsonObject("parentSessionId" -> parent.id.asJson), Some(task.taskId))
        _ <- resources.sessionStore.saveTerminalEnvelope(task.taskId, "run-1", event)
        _ <- SubAgentStartupRecovery.recoverPendingInternal(resources.subAgentTaskStore, resources.sessionStore, Some(resources))
        _ <- SubAgentStartupRecovery.recoverPendingInternal(resources.subAgentTaskStore, resources.sessionStore, Some(resources))
        row <- resources.subAgentTaskStore.findByTaskId(task.taskId)
        pending <- SessionMailbox.scanPending(parent.id)
        taken <- SessionMailbox.scanTaken(parent.id)
      yield
        assertEquals(row.flatMap(_.executionStatus), Some("finished"))
        assertEquals((pending ++ taken).count(e => e.identity == s"${task.taskId}#run-1" && e.event == event), 1)
    }
  }

  test("legacy subagent recovery requires a matching parent tool result") {
    fixture.use { case (resources, _, _, _) =>
      for
        parent <- resources.sessionStore.createSession("legacy-parent", agentName = Some("worker"))
        task = SubAgentTask("subagent-legacy", parent.id, "worker", "inspect", "test", "running", 0,
          System.currentTimeMillis(), None, None, "subagent", toolCallId = Some("call-1"))
        other = task.copy(taskId = "subagent-other", toolCallId = Some("call-2"))
        _ <- resources.subAgentTaskStore.recordTask(task)
        _ <- resources.subAgentTaskStore.recordTask(other)
        _ <- resources.sessionStore.saveMessagesForSession(parent.id,
          List(Message(MessageRole.User, Right(List(nebflow.shared.ContentBlock.ToolResult("call-1", "result", Some(false)))))))
        _ <- SubAgentStartupRecovery.recoverPendingInternal(resources.subAgentTaskStore, resources.sessionStore, Some(resources))
        rows <- resources.subAgentTaskStore.loadTasks(parent.id)
      yield
        assertEquals(rows.find(_.taskId == task.taskId).map(_.status), Some("completed"))
        assertEquals(rows.find(_.taskId == other.taskId).map(_.status), Some("running"))
    }
  }

  test("recovery does not infer completion from a parked subtask transcript") {
    fixture.use { case (resources, _, _, _) =>
      for
        parent <- resources.sessionStore.createSession("parked-parent", agentName = Some("worker"))
        child <- resources.sessionStore.createSession("parked-child", agentName = Some("worker"))
        task = SubAgentTask(child.id, parent.id, "worker", "investigate", "test", "running", 0,
          System.currentTimeMillis(), None, None, "subtask")
        admitted <- resources.subAgentTaskStore.beginRun(task, "run-parked", parent.id, "nebflow")
        _ = assert(admitted.isRight)
        running <- resources.subAgentTaskStore.updateRun(parent.id, child.id, "run-parked", executionStatus = Some("running"))
        _ = assert(running.isRight)
        _ <- resources.sessionStore.saveMessagesForSession(child.id,
          List(Message(MessageRole.Assistant, Left("interim text while awaiting child"))))
        _ <- SubAgentStartupRecovery.recoverPendingInternal(resources.subAgentTaskStore, resources.sessionStore, Some(resources))
        row <- resources.subAgentTaskStore.findByTaskId(child.id)
        pending <- SessionMailbox.scanPending(parent.id)
        taken <- SessionMailbox.scanTaken(parent.id)
      yield
        assertEquals(row.flatMap(_.executionStatus), Some("running"))
        assertEquals((pending ++ taken).size, 0)
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
