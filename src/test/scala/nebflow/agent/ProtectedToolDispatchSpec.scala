package nebflow.agent

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Dispatcher
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.tools.FileLockManager
import nebflow.core.task.FileTaskStore
import nebflow.llm.ModelCandidate
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.core.ExecutionCommitPort
import nebflow.core.tools.{ToolContext, ToolError}
import nebflow.shared.*
import scala.concurrent.duration.*

class ProtectedToolDispatchSpec extends CatsEffectSuite:

  test("protected Read commits its result and reuses it without a second read") {
    val system = ActorSystem("protected-read-test")
    for
      root <- IO.blocking(os.temp.dir(prefix = "protected-read-"))
      _ <- (for
        _ <- IO.blocking(os.makeDir.all(root / "sessions"))
        _ <- IO.blocking(os.write(root / "file.txt", "original"))
        _ <- (ExecutionCommitStore.resource((root / "sessions").toNIO), Dispatcher.parallel[IO]).tupled.use {
          (store, dispatcher) =>
            val admission = ExecutionAdmission(
              "session",
              "run",
              "admission",
              "request",
              "attempt",
              "incarnation",
              "owner",
              "business",
              "audit",
              Json.fromString("initial"),
              "input"
            )
            val arguments = JsonObject("file_path" -> Json.fromString((root / "file.txt").toString))
            val call = ToolCall("call", "Read", arguments)
            val intent = ExecutionToolIntent("operation", call.id, call.name, Json.fromJsonObject(arguments))
            val missingCall =
              ToolCall("missing", "Read", JsonObject("file_path" -> Json.fromString((root / "missing.txt").toString)))
            val missingIntent = ExecutionToolIntent(
              "missing-operation",
              missingCall.id,
              missingCall.name,
              Json.fromJsonObject(missingCall.input)
            )
            val failedCall =
              ToolCall("failed", "Read", JsonObject("file_path" -> Json.fromString((root / "effect.txt").toString)))
            val failedIntent = ExecutionToolIntent(
              "failed-operation",
              failedCall.id,
              failedCall.name,
              Json.fromJsonObject(failedCall.input)
            )
            val cancelledCall = ToolCall(
              "cancelled",
              "Read",
              JsonObject("file_path" -> Json.fromString((root / "cancelled.txt").toString))
            )
            val cancelledIntent = ExecutionToolIntent(
              "cancelled-operation",
              cancelledCall.id,
              cancelledCall.name,
              Json.fromJsonObject(cancelledCall.input)
            )
            val racingCall =
              ToolCall("racing", "Read", JsonObject("file_path" -> Json.fromString((root / "racing.txt").toString)))
            val racingIntent = ExecutionToolIntent(
              "racing-operation",
              racingCall.id,
              racingCall.name,
              Json.fromJsonObject(racingCall.input)
            )
            def command(ref: ExecutionCommitRef, id: String, event: ExecutionEvent) =
              ExecutionTransition("session", "run", id, s"request-$id", ref, "attempt", "incarnation", event)
            def ref(result: ExecutionCommitResult) = result.asInstanceOf[ExecutionCommitResult.Committed].ref
            for
              limiter <- RateLimiter.create()
              tracker <- FileChangeTracker.create(root.toString)
              locks <- FileLockManager.create
              thinking <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
              overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
              muted <- Ref.of[IO, Boolean](false)
              base = SharedResources(
                llm = null,
                dispatcher = dispatcher,
                sessionStore = SessionStore(root / "sessions", root / "tasks"),
                projectRoot = root,
                thinkingConfigRef = thinking,
                rateLimiter = limiter,
                fileChangeTracker = tracker,
                contextWindow = 100000,
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
              bound <- ExecutionCommitPort.admitHandle(store, admission)
              execution = bound.toOption.get
              started <- store.transition(command(execution.ref, "start", ExecutionEvent.AttemptStarted))
              model <- store.transition(command(ref(started), "model", ExecutionEvent.ModelRequested(Json.Null, 0)))
              decision <- store.transition(
                command(
                  ref(model),
                  "decision",
                  ExecutionEvent.ModelDecided(
                    ExecutionModelDecision(
                      Json.Null,
                      List(intent, missingIntent, failedIntent, cancelledIntent, racingIntent)
                    )
                  )
                )
              )
              prepared <- store.transition(
                command(ref(decision), "prepare", ExecutionEvent.ToolPrepared(intent.operationId, intent.asJson))
              )
              preparedMissing <- store.transition(
                command(
                  ref(prepared),
                  "prepare-missing",
                  ExecutionEvent.ToolPrepared(missingIntent.operationId, missingIntent.asJson)
                )
              )
              preparedFailed <- store.transition(
                command(
                  ref(preparedMissing),
                  "prepare-failed",
                  ExecutionEvent.ToolPrepared(failedIntent.operationId, failedIntent.asJson)
                )
              )
              preparedCancelled <- store.transition(
                command(
                  ref(preparedFailed),
                  "prepare-cancelled",
                  ExecutionEvent.ToolPrepared(cancelledIntent.operationId, cancelledIntent.asJson)
                )
              )
              _ <- store.transition(
                command(
                  ref(preparedCancelled),
                  "prepare-racing",
                  ExecutionEvent.ToolPrepared(racingIntent.operationId, racingIntent.asJson)
                )
              )
              _ <- store.registerRunner(ExecutionRunner.Attempt("session", "run", "attempt", "incarnation")).use {
                registered =>
                  val resources = base.copy(
                    executionCommitPort = store,
                    protectedExecutions = Map("session" -> execution),
                    protectedRunners = Map("session" -> registered.toOption.get)
                  )
                  val context = ToolContext(projectRoot = root.toString, sessionId = Some("session"))
                  for
                    reads <- Ref.of[IO, Int](0)
                    entered <- Deferred[IO, Unit]
                    release <- Deferred[IO, Unit]
                    read = (input: JsonObject, ctx: ToolContext) =>
                      reads.update(_ + 1) *> entered.complete(()).void *> release.get *> nebflow.core.tools.ReadTool
                        .call(input, ctx)
                    beforeUnsupported <- store.observeChain("session", "run")
                    unsupportedCalls <- Ref.of[IO, Int](0)
                    unsupported <- AgentActor
                      .protectedRead(
                        call.copy(name = "UnknownTool"),
                        context,
                        resources,
                        "session",
                        (_, _) => unsupportedCalls.update(_ + 1) *> IO.pure(Right("unexpected effect"))
                      )
                      .attempt
                    afterUnsupported <- store.observeChain("session", "run")
                    unknownCalls <- unsupportedCalls.get
                    _ = assertEquals(unsupported.left.toOption.map(_.getMessage), Some("unsupported-boundary"))
                    _ = assertEquals(unknownCalls, 0)
                    _ = assertEquals(afterUnsupported, beforeUnsupported)
                    mismatch <- AgentActor
                      .protectedRead(
                        call.copy(input = arguments.add("offset", Json.fromInt(2))),
                        context,
                        resources,
                        "session"
                      )
                      .attempt
                    _ = assert(mismatch.isLeft)
                    firstFiber <- AgentActor.protectedRead(call, context, resources, "session", read).start
                    pair <- (for
                      _ <- entered.get
                      concurrent <- AgentActor.protectedRead(call, context, resources, "session", read).attempt
                      _ <- release.complete(())
                      first <- firstFiber.joinWithNever
                    yield (concurrent, first)).guarantee(release.complete(()).void)
                    (concurrent, first) = pair
                    _ <- IO.blocking(os.write.over(root / "file.txt", "changed"))
                    second <- AgentActor.protectedRead(call, context, resources, "session", read)
                    readCount <- reads.get
                    chain <- store.observeChain("session", "run")
                    _ <- IO.blocking(os.remove(root / "file.txt"))
                    reusedAfterDelete <- AgentActor.protectedRead(call, context, resources, "session")
                    missing <- AgentActor.protectedRead(missingCall, context, resources, "session")
                    _ <- IO.blocking(os.write(root / "missing.txt", "created after error"))
                    missingReused <- AgentActor.protectedRead(missingCall, context, resources, "session")
                    failedReads <- Ref.of[IO, Int](0)
                    failed <- AgentActor
                      .protectedRead(
                        failedCall,
                        context,
                        resources,
                        "session",
                        (_, _) =>
                          failedReads.update(_ + 1) *> IO.blocking(os.write(root / "effect.txt", "effect")) *>
                            IO.raiseError(new IllegalStateException("lost result"))
                      )
                      .attempt
                    afterFailure <- store.observeChain("session", "run")
                    replay <- AgentActor
                      .protectedRead(
                        failedCall,
                        context,
                        resources,
                        "session",
                        (_, _) => failedReads.update(_ + 1) *> IO.pure(Right("unexpected replay"))
                      )
                      .attempt
                    failureCalls <- failedReads.get
                    effect <- IO.blocking(os.read(root / "effect.txt"))
                    racingEntered <- Deferred[IO, Unit]
                    racingFinalizer <- Deferred[IO, Unit]
                    racingRelease <- Deferred[IO, Unit]
                    racingCount <- Ref.of[IO, Int](0)
                    racing <- AgentActor
                      .protectedRead(
                        racingCall,
                        context,
                        resources,
                        "session",
                        (_, _) =>
                          (racingCount.update(_ + 1) *> racingEntered.complete(()).void *> IO
                            .never[Either[ToolError, String]])
                            .guarantee(racingFinalizer.complete(()).void *> racingRelease.get.timeout(5.seconds))
                      )
                      .attempt
                      .start
                    _ <- racingEntered.get.timeout(5.seconds)
                    record = nebflow.actor.AgentRecord(
                      sessionId = "session",
                      ref = null,
                      kind = nebflow.actor.AgentKind.Delegate,
                      rootSessionId = "parent"
                    )
                    cancelled <- nebflow.core.tools.AgentControlTool.doCancel(resources, record, "operator")
                    _ <- racingFinalizer.get.timeout(5.seconds)
                    _ <- racingRelease.complete(())
                    racingResult <- racing.joinWithNever.timeout(5.seconds)
                    raceCount <- racingCount.get
                    afterCancel <- store.observeChain("session", "run")
                    refused <- AgentActor.protectedRead(call, context, resources, "session").attempt
                    cancelledReads <- Ref.of[IO, Int](0)
                    refusedFresh <- AgentActor
                      .protectedRead(
                        cancelledCall,
                        context,
                        resources,
                        "session",
                        (_, _) => cancelledReads.update(_ + 1) *> IO.pure(Right("unexpected"))
                      )
                      .attempt
                    countAfterCancel <- cancelledReads.get
                    finalState <- store.observeChain("session", "run")
                  yield
                    assert(concurrent.isLeft)
                    assertEquals(readCount, 1)
                    assert(first.content.contains("original"))
                    assertEquals(second, first)
                    assertEquals(reusedAfterDelete, first)
                    assert(missing.isError)
                    assertEquals(missingReused, missing)
                    assert(failed.isLeft)
                    assert(replay.isLeft)
                    assertEquals(failureCalls, 1)
                    assertEquals(effect, "effect")
                    val failureState = afterFailure.asInstanceOf[ExecutionChainObservation.Verified].state
                    assertEquals(failureState.operations.get("failed-operation"), Some("started"))
                    assert(!failureState.outcomes.contains("failed-operation"))
                    assertEquals(
                      chain.asInstanceOf[ExecutionChainObservation.Verified].state.operations.get("operation"),
                      Some("outcome")
                    )
                    assert(cancelled.toOption.exists(_.contains("Cancellation committed")))
                    assert(racingResult.isLeft)
                    assertEquals(raceCount, 1)
                    assertEquals(
                      afterCancel
                        .asInstanceOf[ExecutionChainObservation.Verified]
                        .state
                        .operations
                        .get("racing-operation"),
                      Some("started")
                    )
                    assertEquals(afterCancel.asInstanceOf[ExecutionChainObservation.Verified].state.phase, "cancelled")
                    assert(refused.isLeft)
                    assert(refusedFresh.isLeft)
                    assertEquals(countAfterCancel, 0)
                    assertEquals(finalState, afterCancel)
                  end for
              }
            yield ()
            end for
        }
      yield ()).guarantee(system.stopAll *> IO.blocking(os.remove.all(root)))
    yield ()
    end for
  }
end ProtectedToolDispatchSpec
