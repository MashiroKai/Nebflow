package nebflow.agent

import cats.effect.{Deferred, IO, Resource}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentKind, AgentState, Behavior}
import nebflow.core.{ExecutionCommitPort, FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.*

import scala.concurrent.duration.*

class ProtectedRecoverySpec extends CatsEffectSuite:
  private case class Fixture(
    resources: SharedResources,
    store: ExecutionCommitStore,
    admission: ExecutionAdmission,
    request: ProtectedRecoveryRequest,
    modelRequest: Deferred[IO, LlmRequest]
  )

  private def fixture(recovered: Boolean = false, pending: Boolean = false, finish: Boolean = false): Resource[IO, Fixture] =
    for
      root <- Resource.make(IO.blocking(os.temp.dir(prefix = "protected-recovery-")))(p => IO.blocking(os.remove.all(p)))
      _ <- Resource.make(IO.blocking {
        val previous = PathUtil.dataRoot
        PathUtil.setDataRoot(root)
        os.makeDir.all(root / "sessions")
        previous
      })(previous => IO.blocking(PathUtil.setDataRoot(previous)))
      dispatcher <- Dispatcher.parallel[IO]
      system <- Resource.make(IO(ActorSystem("protected-recovery-spec")))(_.stopAll)
      store <- ExecutionCommitStore.resource((root / "sessions").toNIO)
      observed <- Resource.eval(Deferred[IO, LlmRequest])
      llm = new LlmHandle[IO]:
        def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new IllegalStateException("unexpected send"))
        def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
          Stream.eval(observed.complete(req).void).drain ++
            (if finish then Stream(StreamChunk.TextDelta("recovered result"), StreamChunk.Done(None, None))
             else Stream.eval(IO.never[StreamChunk]))
      definition = nebflow.core.entity.BuiltinAgents.entry(nebflow.core.entity.BuiltinAgents.ExecutorName).get.toAgentDef
      admission = ExecutionAdmission("recovery-child", "run", "admission", "initial-request", "attempt", "incarnation",
        "parent", "business", ProtectedAgentExecution.configBinding(definition), Json.fromString("initial"), "initial")
      handle <- Resource.eval(ExecutionCommitPort.admitHandle(store, admission).map(_.toOption.get))
      _ <- Resource.eval(store.transition(ExecutionTransition(admission.sessionId, admission.executionId, "start", "start",
        handle.ref, admission.attemptId, admission.incarnationId, ExecutionEvent.AttemptStarted)))
      registration <- store.registerRunner(ExecutionRunner.Attempt(admission.sessionId, admission.executionId,
        admission.attemptId, admission.incarnationId))
      runner = registration.toOption.get
      _ <- Resource.eval(ProtectedAgentExecution.model(handle, runner, Json.fromString("seed"), 0)(IO.pure("seed"))(
        value => ExecutionModelDecision(Json.fromString(value), Nil)))
      _ <- Resource.eval(ProtectedAgentExecution.commitRound(handle,
        AgentState(messages = List(Message(MessageRole.User, Left("initial"), messageId = Some("initial")))), List("initial")))
      _ <- Resource.eval(if pending then
        InputLanding.protectedUserReceipt(handle, AgentCommand.UserInput("fallback", clientMessageId = Some("pending"),
          blocks = Some(List(ContentBlock.Text("accepted block"))), source = Some("accepted-source"))).void
        else IO.unit)
      _ <- Resource.eval(ProtectedAgentExecution.suspendOwned(handle, runner, "test"))
      chain <- Resource.eval(store.observeChain(admission.sessionId, admission.executionId).map {
        case ExecutionChainObservation.Verified(state) => state
        case other => throw new IllegalStateException(s"unexpected chain: $other")
      })
      request = ProtectedRecoveryRequest(admission.sessionId, admission.executionId, chain.ref, "resume", "principal", None)
      limiter <- Resource.eval(RateLimiter.create())
      tracker <- Resource.eval(FileChangeTracker.create(root.toString))
      locks <- Resource.eval(FileLockManager.create)
      thinking <- Resource.eval(cats.effect.Ref.of[IO, ThinkingConfig](ThinkingConfig()))
      overrides <- Resource.eval(cats.effect.Ref.of[IO, Map[String, ModelCandidate]](Map.empty))
      muted <- Resource.eval(cats.effect.Ref.of[IO, Boolean](false))
      tasks = new SubAgentTaskStore(root / "subagent-tasks")
      _ <- Resource.eval(tasks.beginRun(SubAgentTask(admission.sessionId, "parent", definition.name, "initial", "test",
        "running", 0, System.currentTimeMillis(), None, None, "delegate"), "run", "parent", "local"))
      resources = SharedResources(llm = llm, dispatcher = dispatcher, sessionStore = SessionStore(root / "sessions", root / "tasks"),
        projectRoot = root, thinkingConfigRef = thinking, rateLimiter = limiter, fileChangeTracker = tracker,
        contextWindow = 100000, agentLibrary = new AgentLibrary(root / "agents"), taskStore = FileTaskStore,
        historyArchiver = null, fileLockManager = locks, sessionModelOverrides = overrides,
        providerRegistry = null, healthMonitor = null, actorSystem = system, voiceMutedRef = muted,
        executionCommitPort = store, subAgentTaskStore = tasks)
      _ <- Resource.make(IO.pure(resources))(r => system.stopAll.guarantee(r.releaseProtectedRecoveries()))
      parentBehavior = new Behavior[AgentCommand]:
        def receive(ctx: nebflow.actor.ActorContext[AgentCommand], message: AgentCommand): IO[Behavior[AgentCommand]] =
          IO.pure(this)
      parent <- Resource.eval(system.spawn(parentBehavior, "parent"))
      _ <- Resource.eval(nebflow.core.node.NodeRunner.registerAgent(resources, "parent", parent, AgentKind.Root, "parent"))
      _ <- Resource.eval(if recovered then
        for
          result <- store.resumeInterrupted(request, admission.modelAuditBinding).map(_.toOption.get)
          nextAdmission = admission.copy(attemptId = result.attemptId, incarnationId = result.incarnationId)
          bound <- ExecutionCommitPort.bindRecovered(store, nextAdmission, result.ref).map(_.toOption.get)
          allocated <- store.registerRunner(ExecutionRunner.Attempt(admission.sessionId, "run", result.attemptId,
            result.incarnationId)).allocated
          _ <- resources.retainProtectedExecution(bound, allocated._1.toOption.get, allocated._2, result)
        yield ()
        else resources.retainProtectedExecution(handle, runner, IO.unit,
          ProtectedRecoveryResult(admission.sessionId, "run", "attempt", "incarnation", chain.ref, duplicate = false)))
    yield Fixture(resources, store, admission, request, observed)

  test("a retained initial attempt can resume after durable suspension and runner quiescence") {
    fixture().use { f =>
      for
        result <- f.resources.resumeProtectedExecution(f.request)
        _ = assert(result.isRight, result.toString)
        request <- f.modelRequest.get.timeout(5.seconds)
        _ = assert(request.messages.exists(_.textContent == "initial"))
        record <- f.resources.agentRegistry.get.map(_(f.admission.sessionId))
        _ = assert(record.supervisorRef.nonEmpty, "recovered delegate must retain its completion recipient")
        parent <- f.resources.agentRegistry.get.map(_("parent"))
        _ = assertEquals(record.parentRef, Some(parent.ref))
      yield ()
    }
  }

  test("retained recovery duplicate validates the durable principal and observed hash") {
    fixture(recovered = true).use { f =>
      for
        principal <- f.resources.resumeProtectedExecution(f.request.copy(principal = "other-principal"))
        hash <- f.resources.resumeProtectedExecution(f.request.copy(observed = f.request.observed.copy(recordHash = "wrong")))
        exact <- f.resources.resumeProtectedExecution(f.request)
      yield
        assertEquals(principal, Left("idempotency-conflict"))
        assertEquals(hash, Left("idempotency-conflict"))
        assert(exact.exists(_.duplicate), exact.toString)
    }
  }

  test("a recovered attempt can suspend and resume again without clearing retained ownership") {
    fixture(recovered = true).use { f =>
      for
        binding <- f.resources.activeProtectedRecovery(f.admission.sessionId).map(_.get)
        _ <- ProtectedAgentExecution.model(binding.handle, binding.runner, Json.fromString("second"), 0)(IO.pure("second"))(
          value => ExecutionModelDecision(Json.fromString(value), Nil))
        _ <- ProtectedAgentExecution.commitRound(binding.handle,
          AgentState(messages = List(Message(MessageRole.User, Left("initial"), messageId = Some("initial")))), Nil)
        _ <- ProtectedAgentExecution.suspendOwned(binding.handle, binding.runner, "second-suspension")
        chain <- f.store.observeChain(f.admission.sessionId, "run").map {
          case ExecutionChainObservation.Verified(state) => state
          case other => throw new IllegalStateException(s"unexpected chain: $other")
        }
        result <- f.resources.resumeProtectedExecution(f.request.copy(requestId = "second-resume", observed = chain.ref))
        _ = assert(result.exists(_.attemptId == "second-resume-attempt"), result.toString)
        _ <- f.modelRequest.get.timeout(5.seconds)
      yield ()
    }
  }

  test("a different request cannot replace a live recovered runner") {
    fixture(recovered = true).use { f =>
      f.resources.resumeProtectedExecution(f.request.copy(requestId = "another-resume"))
        .map(result => assertEquals(result, Left("runner-stop-unconfirmed")))
    }
  }

  test("a stale observed commit cannot resume a retained suspended attempt") {
    fixture().use { f =>
      f.resources.resumeProtectedExecution(f.request.copy(observed = f.request.observed.copy(recordHash = "wrong")))
        .map(result => assertEquals(result, Left("stale-observed-ref")))
    }
  }

  test("recovered completion reaches the durable parent mailbox exactly once") {
    fixture(finish = true).use { f =>
      for
        result <- f.resources.resumeProtectedExecution(f.request)
        _ = assert(result.isRight, result.toString)
        _ <- {
          def delivered: IO[Unit] = f.resources.subAgentTaskStore.findByTaskId(f.admission.sessionId).flatMap {
            case Some(row) if row.executionStatus.contains("finished") && row.notificationPending.contains(false) => IO.unit
            case _ => IO.sleep(20.millis) *> delivered
          }
          delivered.timeoutTo(15.seconds,
            f.resources.subAgentTaskStore.findByTaskId(f.admission.sessionId).flatMap(row =>
              IO.raiseError(new IllegalStateException(s"completion not delivered: $row"))))
        }
        duplicate <- f.resources.resumeProtectedExecution(f.request)
        conflict <- f.resources.resumeProtectedExecution(f.request.copy(principal = "other-principal"))
        entries <- nebflow.core.mail.SessionMailbox.scanPending("parent")
      yield
        assert(duplicate.exists(_.duplicate), duplicate.toString)
        assertEquals(conflict, Left("idempotency-conflict"))
        assertEquals(entries.count(_.identity == "recovery-child#run"), 1)
        assert(entries.exists(_.event.payload.contains("recovered result")))
    }
  }

  test("recovery preserves accepted input blocks source and identity") {
    fixture(pending = true).use { f =>
      for
        result <- f.resources.resumeProtectedExecution(f.request)
        _ = assert(result.isRight, result.toString)
        request <- f.modelRequest.get.timeout(5.seconds)
        pending = request.messages.filter(_.messageId.contains("pending"))
      yield
        assertEquals(pending.size, 1)
        assertEquals(pending.head.content, Right(List(ContentBlock.Text("accepted block"))))
        assertEquals(pending.head.source, Some("accepted-source"))
    }
  }
