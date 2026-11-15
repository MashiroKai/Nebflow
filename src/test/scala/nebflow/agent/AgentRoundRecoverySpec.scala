package nebflow.agent

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Json, JsonObject}
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentDef, AgentState}
import nebflow.core.{ExecutionCommitPort, FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.*

import scala.concurrent.duration.*

class AgentRoundRecoverySpec extends CatsEffectSuite:
  test("real actor does not request the next model round before checkpoint confirmation") {
    val system = ActorSystem("protected-round-barrier")
    for
      root <- IO.blocking(os.temp.dir(prefix = "protected-round-actor-"))
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      terminalEntered = new java.util.concurrent.CountDownLatch(1)
      terminalRelease = new java.util.concurrent.CountDownLatch(1)
      published <- Ref.of[IO, Boolean](false)
      firstStarted <- Deferred[IO, Unit]
      firstRelease <- Deferred[IO, Unit]
      calls <- Ref.of[IO, Int](0)
      requests <- Ref.of[IO, List[LlmRequest]](Nil)
      file = root / "input.txt"
      _ <- (IO.blocking {
        os.makeDir.all(root / "sessions")
        os.write(file, "protected read result")
      } *> ExecutionCommitStore.resourceWithFault((root / "sessions").toNIO, point =>
        if point == "before-terminal-confirm" then
          terminalEntered.countDown()
          if !terminalRelease.await(30, java.util.concurrent.TimeUnit.SECONDS) then
            throw new IllegalStateException("terminal test timed out")
      ).use { store =>
        val authority = new ExecutionCommitPort:
          def admit(a: ExecutionAdmission): IO[ExecutionCommitResult] = store.admit(a)
          def observe(s: String, e: String): IO[ExecutionAdmissionObservation] = store.observe(s, e)
          def reconcileAdmission(a: ExecutionAdmission): IO[ExecutionCommitResult] = store.reconcileAdmission(a)
          def transition(c: ExecutionTransition): IO[ExecutionCommitResult] = store.transition(c)
          override def acceptInput(c: ExecutionTransition): IO[ExecutionCommitResult] = store.acceptInput(c)
          def reconcileTransition(c: ExecutionTransition): IO[ExecutionCommitResult] = store.reconcileTransition(c)
          def observeChain(s: String, e: String): IO[ExecutionChainObservation] = store.observeChain(s, e)
          override def commitRound(c: ExecutionTransition): IO[ExecutionCommitResult] =
            entered.complete(()).void *> release.get *> store.commitRound(c)
        val llm = new LlmHandle[IO]:
          def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new IllegalStateException("unexpected send"))
          def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
            Stream.eval(requests.update(_ :+ req) *> calls.getAndUpdate(_ + 1)).flatMap {
              case 0 => Stream.eval(firstStarted.complete(()).void *> firstRelease.get).drain ++
                Stream.raiseError[IO](new FallbackExhaustedError(List(FallbackAttempt("provider", "model",
                  Some(FailoverReason.Overloaded), Some(ErrorPermanence.Transient), 1, 0, "test"))))
              case 1 => Stream(StreamChunk.ToolCallChunk(ToolCall("read-1", "Read",
                JsonObject("file_path" -> Json.fromString(file.toString)))), StreamChunk.Done(None, None))
              case 2 => Stream(StreamChunk.TextDelta("finished"), StreamChunk.Done(None, None))
              case _ => Stream.raiseError[IO](new IllegalStateException("unexpected model request"))
            }
        val admission = ExecutionAdmission("round-actor", "run", "admission", "request", "attempt", "incarnation",
          "owner", "business", "audit", Json.fromString("initial"), "initial")
        for
          bound <- ExecutionCommitPort.admitHandle(authority, admission)
          handle = bound.toOption.get
          _ <- store.transition(ExecutionTransition("round-actor", "run", "start", "start", handle.ref,
            "attempt", "incarnation", ExecutionEvent.AttemptStarted))
          _ <- (store.registerRunner(ExecutionRunner.Attempt("round-actor", "run", "attempt", "incarnation")),
            Dispatcher.parallel[IO]).tupled.use { (registered, dispatcher) =>
            val runner = registered.toOption.get
            (for
              limiter <- RateLimiter.create()
              tracker <- FileChangeTracker.create(root.toString)
              locks <- FileLockManager.create
              thinking <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
              overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
              muted <- Ref.of[IO, Boolean](false)
              taskStore = new SubAgentTaskStore(root / "subagent-tasks")
              row = SubAgentTask(admission.sessionId, admission.ownerBinding, "round-probe", "initial", "test",
                "running", 0, System.currentTimeMillis(), None, None, "delegate")
              _ <- taskStore.beginRun(row, admission.executionId, admission.ownerBinding, "local")
              base = SharedResources(llm = llm, dispatcher = dispatcher, subAgentTaskStore = taskStore,
                sessionStore = SessionStore(root / "sessions", root / "tasks"), projectRoot = root,
                thinkingConfigRef = thinking, rateLimiter = limiter, fileChangeTracker = tracker,
                contextWindow = 100000, agentLibrary = new AgentLibrary(root / "agents"), taskStore = FileTaskStore,
                historyArchiver = null, fileLockManager = locks, sessionModelOverrides = overrides,
                providerRegistry = null, healthMonitor = null, actorSystem = system, voiceMutedRef = muted)
              resources = base.copy(executionCommitPort = authority,
                protectedExecutions = Map(admission.sessionId -> handle),
                protectedRunners = Map(admission.sessionId -> runner))
              definition = AgentDef("round-probe", "round-probe", tools = List("Read"), systemPrompt = "")
              initial = List(Message(MessageRole.User, Left("initial"), messageId = Some("initial")))
              actor <- system.spawn(AgentActor(definition, resources, wsSend = (json: Json) =>
                IO.whenA(json.hcursor.get[String]("type").toOption.contains("done"))(published.set(true)),
                depth = 1, sessionId = Some(admission.sessionId), initialMessages = initial), "round-actor")
              _ <- actor ! AgentCommand.UserInput("read the file", clientMessageId = Some("input"))
              _ <- firstStarted.get.timeout(30.seconds)
              _ <- actor ! AgentCommand.ImmediateInput("later detail", clientMessageId = Some("immediate"))
              _ <- actor ! AgentCommand.ImmediateInput("later detail", clientMessageId = Some("immediate"))
              _ <- {
                def awaitInput: IO[Unit] = store.observeChain(admission.sessionId, admission.executionId).flatMap {
                  case ExecutionChainObservation.Verified(chain) if chain.pendingInputs.contains("immediate") => IO.unit
                  case _ => IO.sleep(20.millis) *> awaitInput
                }
                awaitInput.timeout(30.seconds)
              }
              _ <- firstRelease.complete(())
              _ <- entered.get.timeout(30.seconds)
              before <- calls.get
              beforeCommit <- store.observeChain(admission.sessionId, admission.executionId)
              _ = assertEquals(before, 2)
              _ = assertEquals(beforeCommit.asInstanceOf[ExecutionChainObservation.Verified].state.retryCharges, 1L)
              _ = assertEquals(beforeCommit.asInstanceOf[ExecutionChainObservation.Verified].state.roundIndex, 0L)
              _ = assert(beforeCommit.asInstanceOf[ExecutionChainObservation.Verified].state.pendingInputs.contains("immediate"))
              _ <- release.complete(())
              _ <- {
                def awaitSecond: IO[Unit] = calls.get.flatMap(n => if n >= 3 then IO.unit else IO.sleep(20.millis) *> awaitSecond)
                awaitSecond.timeout(30.seconds)
              }
              sent <- requests.get
              _ = assertEquals(sent.size, 3)
              _ = assert(sent(2).messages.exists(_.content.toString.contains("protected read result")))
              _ = assertEquals(sent(2).messages.count(_.messageId.contains("immediate")), 1)
              _ = assert(!sent(1).messages.exists(_.messageId.contains("immediate")))
              _ = assert(!sent.head.messages.exists(_.messageId.contains("immediate")))
              _ <- IO.blocking(assert(terminalEntered.await(30, java.util.concurrent.TimeUnit.SECONDS)))
              beforePublication <- published.get
              _ = assert(!beforePublication)
              beforeProjection <- taskStore.findByTaskId(admission.sessionId)
              _ = assert(!beforeProjection.flatMap(_.executionStatus).contains("finished"))
              _ <- IO(terminalRelease.countDown())
              terminal <- {
                def awaitTerminal: IO[ExecutionChainState] =
                  store.observeChain(admission.sessionId, admission.executionId).flatMap {
                    case ExecutionChainObservation.Verified(chain) if chain.phase == "terminal" => IO.pure(chain)
                    case _ => IO.sleep(20.millis) *> awaitTerminal
                  }
                awaitTerminal.timeout(30.seconds)
              }
              _ = assertEquals(terminal.roundIndex, 2L)
              _ = assertEquals(terminal.pendingInputs, Nil)
              restored <- IO.fromEither(ProtectedAgentExecution.restore(AgentState(), terminal.checkpoint.get)
                .left.map(new IllegalStateException(_)))
              _ = assert(restored.execution.messages.exists(_.textContent == "finished"))
              _ <- {
                def awaitPublication: IO[Unit] = published.get.flatMap(done =>
                  if done then IO.unit else IO.sleep(20.millis) *> awaitPublication)
                awaitPublication.timeout(30.seconds)
              }
              projected <- taskStore.findByTaskId(admission.sessionId)
              _ = assertEquals(projected.flatMap(_.executionStatus), Some("finished"))
              _ = assertEquals(projected.flatMap(_.resultRef), terminal.terminalResultRef)
              loaded <- resources.sessionStore.loadTerminalEnvelope(admission.sessionId, admission.executionId)
              _ = assertEquals(loaded.map(_.payload), Some("\"test\":\nfinished"))
            yield ()).guarantee(firstRelease.complete(()).void *> release.complete(()).void *>
              IO(terminalRelease.countDown()) *> system.stopAll)
          }
        yield ()
      }).guarantee(system.stopAll *> IO.blocking(os.remove.all(root)))
    yield ()
  }

  test("runner commits a model request and decision before a round checkpoint") {
    for
      root <- IO.blocking(os.temp.dir(prefix = "round-recovery-"))
      _ <- (IO.blocking(os.makeDir.all(root / "sessions")) *>
        ExecutionCommitStore.resource((root / "sessions").toNIO).use { store =>
          val admission = ExecutionAdmission("session", "run", "admission", "request", "attempt", "incarnation",
            "owner", "business", "audit", Json.fromString("initial"), "input")
          for
            bound <- ExecutionCommitPort.admitHandle(store, admission)
            handle = bound.toOption.get
            started <- store.transition(ExecutionTransition("session", "run", "start", "start", handle.ref,
              "attempt", "incarnation", ExecutionEvent.AttemptStarted))
            _ = assert(started.isInstanceOf[ExecutionCommitResult.Committed])
            _ <- store.registerRunner(ExecutionRunner.Attempt("session", "run", "attempt", "incarnation")).use { registered =>
              val runner = registered.toOption.get
              for
                result <- ProtectedAgentExecution.model(handle, runner, Json.fromString("request"), 0)(
                  store.observeChain("session", "run").map {
                    case ExecutionChainObservation.Verified(state) => state.phase
                    case _ => "invalid"
                  }
                )(value => ExecutionModelDecision(Json.fromString(value), Nil))
                _ = assertEquals(result, "model")
                decided <- store.observeChain("session", "run")
                _ = assertEquals(decided.asInstanceOf[ExecutionChainObservation.Verified].state.phase, "decided")
                baseState = AgentState(messages = List(Message(MessageRole.User, Left("initial"))))
                state = baseState.copy(loopTurnKey = 4L, loopCounters = Counters(lastCallCount = 1),
                  execution = baseState.execution.copy(turnStartMessageCount = 1, emptyResponseRetries = 2))
                _ <- ProtectedAgentExecution.commitRound(handle, state, List("input"))
                committed <- store.observeChain("session", "run")
                chain = committed.asInstanceOf[ExecutionChainObservation.Verified].state
                _ = assertEquals(chain.roundIndex, 1L)
                _ = assertEquals(chain.pendingInputs, Nil)
                restored = ProtectedAgentExecution.restore(AgentState(), chain.checkpoint.get)
                _ = assertEquals(restored.toOption.get.execution.messages, state.execution.messages)
                _ = assertEquals(restored.toOption.get.loopTurnKey, 4L)
                _ = assertEquals(restored.toOption.get.loopCounters, state.loopCounters)
                _ = assertEquals(restored.toOption.get.execution.emptyResponseRetries, 2)
                _ = assertEquals(restored.toOption.get.execution.turnStartMessageCount, 1)
                verifiedRestore <- ProtectedAgentExecution.restoreVerified(handle, AgentState())
                _ = assertEquals(verifiedRestore.execution.messages, state.execution.messages)
              yield ()
            }
          yield ()
        }).guarantee(IO.blocking(os.remove.all(root)))
    yield ()
  }

  test("live retry charges before a second provider attempt and preserves the charge") {
    for
      root <- IO.blocking(os.temp.dir(prefix = "round-retry-"))
      _ <- (IO.blocking(os.makeDir.all(root / "sessions")) *>
        ExecutionCommitStore.resource((root / "sessions").toNIO).use { store =>
          val admission = ExecutionAdmission("session", "run", "admission", "request", "attempt", "incarnation",
            "owner", "business", "audit", Json.fromString("initial"), "input")
          for
            bound <- ExecutionCommitPort.admitHandle(store, admission)
            handle = bound.toOption.get
            _ <- store.transition(ExecutionTransition("session", "run", "start", "start", handle.ref,
              "attempt", "incarnation", ExecutionEvent.AttemptStarted))
            _ <- store.registerRunner(ExecutionRunner.Attempt("session", "run", "attempt", "incarnation")).use { registered =>
              val runner = registered.toOption.get
              for
                calls <- Ref.of[IO, Int](0)
                charged <- Ref.of[IO, Long](-1L)
                request = LlmRequest(Nil, "session", "agent", searchAllowed = false)
                result <- ProtectedAgentExecution.modelRound(handle, runner, request, 4) {
                  calls.getAndUpdate(_ + 1).flatMap {
                    case 0 => IO.raiseError(new FallbackExhaustedError(List(FallbackAttempt("provider", "model",
                      Some(FailoverReason.Overloaded), Some(ErrorPermanence.Transient), 1, 0, "test"))))
                    case _ => store.observeChain("session", "run").flatMap {
                      case ExecutionChainObservation.Verified(state) => charged.set(state.retryCharges)
                      case _ => IO.raiseError(new IllegalStateException("unverified"))
                    }.as(nebflow.actor.ConsumeResult("done", Nil, Nil, None))
                  }
                }.timeout(20.seconds)
                count <- calls.get
                prior <- charged.get
                current <- store.observeChain("session", "run")
              yield
                assertEquals(result.text, "done")
                assertEquals(count, 2)
                assertEquals(prior, 1L)
                assertEquals(current.asInstanceOf[ExecutionChainObservation.Verified].state.retryCharges, 1L)
            }
          yield ()
        }).guarantee(IO.blocking(os.remove.all(root)))
    yield ()
  }

  test("a failed owned model boundary suspends and prevents a second request") {
    for
      root <- IO.blocking(os.temp.dir(prefix = "round-failed-model-"))
      _ <- (IO.blocking(os.makeDir.all(root / "sessions")) *>
        ExecutionCommitStore.resource((root / "sessions").toNIO).use { store =>
          val admission = ExecutionAdmission("session", "run", "admission", "request", "attempt", "incarnation",
            "owner", "business", "audit", Json.fromString("initial"), "input")
          for
            bound <- ExecutionCommitPort.admitHandle(store, admission)
            handle = bound.toOption.get
            _ <- store.transition(ExecutionTransition("session", "run", "start", "start", handle.ref,
              "attempt", "incarnation", ExecutionEvent.AttemptStarted))
            _ <- store.registerRunner(ExecutionRunner.Attempt("session", "run", "attempt", "incarnation")).use { registered =>
              val runner = registered.toOption.get
              for
                failure <- ProtectedAgentExecution.model(handle, runner, Json.Null, 0)(
                  IO.raiseError[String](new IllegalStateException("provider failed"))
                )(value => ExecutionModelDecision(Json.fromString(value), Nil)).attempt
                _ = assert(failure.isLeft)
                _ <- ProtectedAgentExecution.suspendOwned(handle, runner, "actor-terminated-review-required")
                chain <- store.observeChain("session", "run")
                _ = assertEquals(chain.asInstanceOf[ExecutionChainObservation.Verified].state.phase, "suspended")
                retried <- ProtectedAgentExecution.model(handle, runner, Json.Null, 0)(IO.pure("unexpected"))(
                  value => ExecutionModelDecision(Json.fromString(value), Nil)
                ).attempt
                _ = assert(retried.isLeft)
              yield ()
            }
          yield ()
        }).guarantee(IO.blocking(os.remove.all(root)))
    yield ()
  }

  test("checkpoint rejects unrepresented queued state") {
    val state = AgentState().copy(execution = nebflow.actor.ExecutionContext(outstandingSubagentResults = 1))
    assertEquals(ProtectedAgentExecution.checkpoint(state), Left("unsupported-checkpoint-state"))
  }

  test("checkpoint rejects invalid turn start and unsupported checkpoint versions") {
    val invalid = AgentState().copy(execution = nebflow.actor.ExecutionContext(turnStartMessageCount = 1))
    assertEquals(ProtectedAgentExecution.checkpoint(invalid), Left("unsupported-checkpoint-state"))
    val checkpoint = ProtectedAgentExecution.checkpoint(AgentState()).toOption.get
    val old = checkpoint.mapObject(_.add("version", Json.fromInt(1)))
    assertEquals(ProtectedAgentExecution.restore(AgentState(), old), Left("unsupported-checkpoint-version"))
  }
