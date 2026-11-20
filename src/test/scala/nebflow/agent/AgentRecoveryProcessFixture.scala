package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.{ActorSystem, AgentState}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{ExecutionCommitPort, FileChangeTracker, RateLimiter, SessionStore}
import nebflow.llm.ModelCandidate
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * Subprocess entry point for AgentRecoveryProcessSpec. It is not a gateway and
 * opens no sockets. Each invocation owns one isolated data root, then exits.
 */
object AgentRecoveryProcessFixture:

  private val Session = "recovery-session"
  private val Run = "recovery-run"
  private val Parent = "recovery-parent"
  private val Root = "recovery-root"

  def main(args: Array[String]): Unit =
    val root = os.Path(args(0))
    val mode = args(1)
    PathUtil.setDataRoot(root)
    os.makeDir.all(root / "sessions")
    val taskStore = new SubAgentTaskStore(root / "subagent-tasks")
    if mode != "recover" then seedTaskRow(taskStore)
    (mode match
      case "clean" => seed(root)
      case "started" => seedStarted(root)
      case "recover" => recover(root, taskStore)
      case other => IO.raiseError(new IllegalArgumentException(s"unknown fixture mode: $other"))
    ).unsafeRunSync()

  private def seedTaskRow(taskStore: SubAgentTaskStore): Unit =
    val row = SubAgentTask(
      Session,
      Parent,
      nebflow.core.entity.BuiltinAgents.ExecutorName,
      "seed brief",
      "seed task",
      "running",
      0,
      System.currentTimeMillis(),
      None,
      None,
      "delegate"
    )
    taskStore.beginRun(row, Run, Root, nebflow.core.executor.ExecutorRegistry.DefaultId).unsafeRunSync() match
      case Left(reason) => throw new IllegalStateException(reason)
      case Right(_) => ()
    taskStore
      .completeRun(Parent, Session, Run, "interrupted", reason = Some("fixture-exit"), notifyParent = false)
      .unsafeRunSync() match
      case Left(reason) => throw new IllegalStateException(reason)
      case Right(_) => ()

  end seedTaskRow

  private def seed(root: os.Path): IO[Unit] =
    ExecutionCommitStore.resource((root / "sessions").toNIO).use { store =>
      for
        bound <- ExecutionCommitPort.admitHandle(store, admissionFor())
        handle = bound.toOption.get
        _ <- store.transition(
          ExecutionTransition(
            Session,
            Run,
            "start",
            "start",
            handle.ref,
            "attempt",
            "incarnation",
            ExecutionEvent.AttemptStarted
          )
        )
        _ <- store.registerRunner(ExecutionRunner.Attempt(Session, Run, "attempt", "incarnation")).use { registered =>
          val runner = registered.toOption.get
          for
            _ <- ProtectedAgentExecution.model(handle, runner, Json.fromString("request"), 0)(IO.pure("seed-decision"))(
              value => ExecutionModelDecision(Json.fromString(value), Nil)
            )
            state = AgentState(messages =
              List(Message(MessageRole.User, Left("seed prompt"), messageId = Some("input")))
            )
            _ <- ProtectedAgentExecution.commitRound(handle, state, List("input"))
            _ <- ProtectedAgentExecution.suspendOwned(handle, runner, "fixture-exit")
          yield ()
        }
      yield ()
    }

  private def seedStarted(root: os.Path): IO[Unit] =
    ExecutionCommitStore.resource((root / "sessions").toNIO).use { store =>
      for
        bound <- ExecutionCommitPort.admitHandle(store, admissionFor())
        handle = bound.toOption.get
        _ <- store.transition(
          ExecutionTransition(
            Session,
            Run,
            "start",
            "start",
            handle.ref,
            "attempt",
            "incarnation",
            ExecutionEvent.AttemptStarted
          )
        )
        _ <- store.registerRunner(ExecutionRunner.Attempt(Session, Run, "attempt", "incarnation")).use { registered =>
          val runner = registered.toOption.get
          for
            _ <- ProtectedAgentExecution.model(handle, runner, Json.fromString("request"), 0)(IO.pure("seed-decision"))(
              value => ExecutionModelDecision(Json.fromString(value), Nil)
            )
            state = AgentState(messages =
              List(Message(MessageRole.User, Left("seed prompt"), messageId = Some("input")))
            )
            _ <- ProtectedAgentExecution.commitRound(handle, state, List("input"))
            calls <- Ref.of[IO, Int](0)
            result <- ProtectedAgentExecution.modelRound(
              handle,
              runner,
              LlmRequest(Nil, Session, "agent", searchAllowed = false),
              0
            ) {
              calls
                .update(_ + 1)
                .as(
                  nebflow.actor.ConsumeResult(
                    "tool round",
                    List(ToolCall("tool-1", "Read", io.circe.JsonObject("file_path" -> Json.fromString("seed.txt")))),
                    Nil,
                    None
                  )
                )
            }
            _ = assert(result.text == "tool round")
            current <- store.observeChain(Session, Run).map {
              case ExecutionChainObservation.Verified(value) => value
              case other => throw new IllegalStateException(s"unverified: $other")
            }
            intent = current.intents.values.head
            command = ExecutionTransition(
              Session,
              Run,
              "started",
              "started",
              current.ref,
              current.attemptId,
              current.incarnationId,
              ExecutionEvent.ToolStarted(intent.operationId)
            )
            dispatched <- runner.dispatch(command, intent)(IO.unit)
            _ <- dispatched match
              case Right((_, receipt)) => receipt.await.void
              case Left(error) => IO.raiseError(new IllegalStateException(s"dispatch: $error"))
            _ <- ProtectedAgentExecution.suspendOwned(handle, runner, "fixture-exit-started")
          yield ()
          end for
        }
      yield ()
    }

  private def recover(root: os.Path, taskStore: SubAgentTaskStore): IO[Unit] =
    ExecutionCommitStore.resource((root / "sessions").toNIO).use { store =>
      for
        calls <- Ref.of[IO, Int](0)
        counter = calls.getAndUpdate(_ + 1).flatMap(n => IO.blocking(os.write.over(root / "calls.txt", n.toString)))
        llm = new LlmHandle[IO]:
          def send(req: LlmRequest): IO[LlmResponse] =
            IO.raiseError(new IllegalStateException("unexpected send"))
          def sendStream(
            req: LlmRequest,
            onAttempt: Option[FallbackAttempt => IO[Unit]] = None
          ): Stream[IO, StreamChunk] =
            Stream.eval(counter).flatMap { n =>
              Stream(StreamChunk.TextDelta(s"recovered-$n"), StreamChunk.Done(None, None))
            }
        _ <- Dispatcher.parallel[IO].use { dispatcher =>
          val system = ActorSystem("protected-recovery-process")
          (for
            limiter <- RateLimiter.create()
            tracker <- FileChangeTracker.create(root.toString)
            locks <- FileLockManager.create
            thinking <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
            overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
            muted <- Ref.of[IO, Boolean](false)
            sessionStore = new SessionStore(root / "sessions", root / "tasks")
            library = new AgentLibrary(root / "agents")
            resources = SharedResources(
              llm = llm,
              dispatcher = dispatcher,
              sessionStore = sessionStore,
              projectRoot = root,
              thinkingConfigRef = thinking,
              rateLimiter = limiter,
              fileChangeTracker = tracker,
              contextWindow = 100000,
              agentLibrary = library,
              taskStore = FileTaskStore,
              historyArchiver = null,
              fileLockManager = locks,
              sessionModelOverrides = overrides,
              providerRegistry = null,
              healthMonitor = null,
              actorSystem = system,
              voiceMutedRef = muted,
              executionCommitPort = store,
              subAgentTaskStore = taskStore
            )
            agentDef <- library.get(nebflow.core.entity.BuiltinAgents.ExecutorName).flatMap {
              case Some(value) => IO.pure(value)
              case None => IO.raiseError(new IllegalStateException("agent definition unavailable"))
            }
            boot <- resources.executionProtection(Session)
            bootCalls <- calls.get
            _ <- IO.blocking(assert(boot.isInstanceOf[ExecutionProtection.Protected], s"boot: $boot"))
            _ <- IO.blocking(assert(bootCalls == 0, s"boot made $bootCalls model calls"))
            chain <- store.observeChain(Session, Run).map {
              case ExecutionChainObservation.Verified(state) => state
              case other => throw new IllegalStateException(s"unverified: $other")
            }
            request = ProtectedRecoveryRequest(Session, Run, chain.ref, "resume-request", "fixture-principal", None)
            outcome <- resources.resumeProtectedExecution(request)
            outcomeText = outcome match
              case Right(value) => "resumed:" + value.attemptId
              case Left(reason) => "rejected:" + reason
            _ <- outcome match
              case Right(_) =>
                waitFinished(taskStore).timeout(30.seconds)
              case Left(_) => IO.unit
            after <- calls.get
            finalChain <- store.observeChain(Session, Run)
            status <- taskStore.findByTaskId(Session).map(_.flatMap(_.executionStatus).getOrElse(""))
            _ <- IO.blocking(
              os.write.over(
                root / "result.json",
                Json
                  .obj(
                    "bootCalls" -> bootCalls.asJson,
                    "afterResumeCalls" -> after.asJson,
                    "outcome" -> outcomeText.asJson,
                    "status" -> status.asJson,
                    "attemptId" -> (finalChain match
                      case ExecutionChainObservation.Verified(state) => state.attemptId.asJson
                      case _ => Json.Null)
                  )
                  .noSpaces
              )
            )
            _ <- resources.releaseProtectedRecoveries().handleErrorWith(_ => IO.unit)
            _ <- system.stopAll
          yield ()).guarantee(system.stopAll)
        }
      yield ()
    }

  private def waitFinished(taskStore: SubAgentTaskStore): IO[Unit] =
    taskStore.findByTaskId(Session).flatMap {
      case Some(row) if row.executionStatus.contains("finished") => IO.unit
      case _ => IO.sleep(25.millis) *> waitFinished(taskStore)
    }

  private def admissionFor(): ExecutionAdmission =
    val definition = nebflow.core.entity.BuiltinAgents
      .entry(nebflow.core.entity.BuiltinAgents.ExecutorName)
      .map(_.toAgentDef)
      .getOrElse(throw new IllegalStateException("executor definition unavailable"))
    val binding = ProtectedAgentExecution.configBinding(definition)
    ExecutionAdmission(
      Session,
      Run,
      "admission",
      "request",
      "attempt",
      "incarnation",
      Parent,
      "business",
      binding,
      Json.fromString("initial"),
      "input"
    )
  end admissionFor
end AgentRecoveryProcessFixture
