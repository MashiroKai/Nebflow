package nebflow.gateway.rest

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentDef, AgentState}
import nebflow.agent.{
  ExecutionCommitStore,
  ExecutionRunner,
  ProtectedAgentExecution,
  SharedResources,
  SubAgentTaskStore
}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{ExecutionCommitPort, FileChangeTracker, RateLimiter, SessionStore}
import nebflow.gateway.{GatewaySessionStore, RestApiRoutes, SpecRequests}
import nebflow.llm.ModelCandidate
import nebflow.shared.*
import org.http4s.*
import org.http4s.circe.*
import org.typelevel.ci.CIString

import scala.concurrent.duration.*

class ProtectedRecoveryRoutesSpec extends CatsEffectSuite:

  private val Session = "route-recovery-session"
  private val Run = "route-recovery-run"
  private val Parent = "route-recovery-parent"
  private val Token = "route-token"
  private val RequestId = "route-resume-request"

  test("protected resume requires authenticated exact ref and stable request identity") {
    checkRecovery(useLocalSession = false)
  }

  test("local tickets without network enrollment can observe and resume only on loopback") {
    checkRecovery(useLocalSession = true)
  }

  private def checkRecovery(useLocalSession: Boolean): IO[Unit] =
    for
      root <- IO.blocking(os.temp.dir(prefix = "protected-routes-"))
      _ <- (IO.blocking(os.makeDir.all(root / "sessions")) *>
        ExecutionCommitStore.resource((root / "sessions").toNIO).use { store =>
          val taskStore = new SubAgentTaskStore(root / "subagent-tasks")
          for
            _ <- seedTask(taskStore)
            _ <- seedAuthority(store)
            _ <- Dispatcher.parallel[IO].use { dispatcher =>
              val system = ActorSystem("protected-recovery-routes")
              (for
                calls <- Ref.of[IO, Int](0)
                llm = new LlmHandle[IO]:
                  def send(req: LlmRequest): IO[LlmResponse] =
                    IO.raiseError(new IllegalStateException("unexpected send"))
                  def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None)
                    : Stream[IO, StreamChunk] =
                    Stream.eval(calls.update(_ + 1)).map { n =>
                      StreamChunk.TextDelta(s"route-result-$n")
                    } ++ Stream(StreamChunk.Done(None, None))
                limiter <- RateLimiter.create()
                tracker <- FileChangeTracker.create(root.toString)
                locks <- FileLockManager.create
                thinking <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
                overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
                muted <- Ref.of[IO, Boolean](false)
                sessionStore = new SessionStore(root / "sessions", root / "tasks")
                library = new nebflow.agent.AgentLibrary(root / "agents")
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
                sessions <- GatewaySessionStore.create()
                local <- sessions.create("local", GatewaySessionStore.OriginLocalTicket, "")
                renewed <- sessions.create("local", GatewaySessionStore.OriginLocalTicket, "")
                relayWithoutNetwork <- sessions.create("account", GatewaySessionStore.OriginRelayMint, "")
                tokenRef = Ref.unsafe[IO, String](Token)
                authenticated = (req: Request[IO]) =>
                  if useLocalSession then localAuthed(req, local.sessionId) else authed(req)
                routes = new RestApiRoutes(
                  tokenRef = tokenRef,
                  sessions = sessions,
                  configRef = Ref.unsafe[IO, NebflowServiceConfig](
                    NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
                  ),
                  sharedResources = resources,
                  sessionStore = sessionStore,
                  wsRoutes = null
                )
                presence = routes.presenceWsRoutes(null)
                chain <- store.observeChain(Session, Run)
                observedHash = chain match
                  case ExecutionChainObservation.Verified(state) => state.ref.recordHash
                  case other => fail(s"unexpected chain: $other")
                unauthorized <- runRoute(
                  presence,
                  Request[IO](Method.POST, uri())
                    .withHeaders(Headers("If-Match" -> observedHash, "Idempotency-Key" -> RequestId))
                )
                invalidBearer <- runRoute(
                  presence,
                  Request[IO](Method.POST, uri())
                    .putHeaders(Header.Raw(CIString("Authorization"), "Bearer invalid-token"))
                )
                invalidCookie <- runRoute(presence, localAuthed(Request[IO](Method.POST, uri()), "invalid-session"))
                offLeg <- runRoute(
                  presence,
                  Request[IO](Method.POST, uri())
                    .addCookie(GatewaySessionStore.CookieName, local.sessionId)
                )
                lan <- runRoute(
                  presence,
                  SpecRequests.withRemote(
                    Request[IO](Method.POST, uri())
                      .addCookie(GatewaySessionStore.CookieName, local.sessionId),
                    Some(SpecRequests.Lan)
                  )
                )
                missingNetwork <- runRoute(
                  presence,
                  localAuthed(Request[IO](Method.POST, uri()), relayWithoutNetwork.sessionId)
                )
                recovery <- runRoute(
                  presence,
                  authenticated(Request[IO](Method.GET, Uri.unsafeFromString(s"/delegates/$Session/recovery")))
                )
                recoveryJson <- recovery.as[Json]
                stale <- runRoute(
                  presence,
                  authenticated(
                    Request[IO](Method.POST, uri())
                      .withHeaders(Headers("If-Match" -> ("0" * 64), "Idempotency-Key" -> RequestId))
                  )
                )
                afterStale <- store.observeChain(Session, Run)
                accepted <- runRoute(
                  presence,
                  authenticated(
                    Request[IO](Method.POST, uri())
                      .withHeaders(Headers("If-Match" -> observedHash, "Idempotency-Key" -> RequestId))
                  )
                )
                acceptedJson <- accepted.as[Json]
                acceptedAttempt = acceptedJson.hcursor.get[String]("attemptId").toOption.getOrElse("")
                duplicateRequest = Request[IO](Method.POST, uri()).withHeaders(
                  Headers("If-Match" -> observedHash, "Idempotency-Key" -> RequestId)
                )
                duplicate <- runRoute(
                  presence,
                  if useLocalSession then localAuthed(duplicateRequest, renewed.sessionId)
                  else authed(duplicateRequest)
                )
                duplicateJson <- duplicate.as[Json]
                _ <- IO.sleep(50.millis)
                _ <- resources.releaseProtectedRecoveries().handleErrorWith(_ => IO.unit)
                _ <- system.stopAll
              yield
                assertEquals(unauthorized.status, Status.Forbidden)
                assertEquals(invalidBearer.status, Status.Forbidden)
                assertEquals(invalidCookie.status, Status.Forbidden)
                assertEquals(offLeg.status, Status.Forbidden)
                assertEquals(lan.status, Status.Forbidden)
                assertEquals(missingNetwork.status, Status.Forbidden)
                assertEquals(recovery.status, Status.Ok)
                assertEquals(recoveryJson.hcursor.get[String]("suspensionReason").toOption, Some("route-fixture"))
                assertEquals(stale.status, Status.PreconditionFailed)
                assertEquals(afterStale.asInstanceOf[ExecutionChainObservation.Verified].state.attemptId, "attempt")
                assertEquals(accepted.status, Status.Ok, acceptedJson.noSpaces)
                assertEquals(acceptedJson.hcursor.get[Boolean]("duplicate").toOption, Some(false))
                assertEquals(acceptedAttempt, s"$RequestId-attempt")
                assertEquals(duplicate.status, Status.Ok)
                assertEquals(duplicateJson.hcursor.get[String]("attemptId").toOption, Some(acceptedAttempt))
                assertEquals(duplicateJson.hcursor.get[Boolean]("duplicate").toOption, Some(true))
                assertEquals(duplicateJson.hcursor.get[String]("runId").toOption, Some(Run))
              ).guarantee(system.stopAll)
            }
          yield ()
          end for
        }).guarantee(IO.blocking(os.remove.all(root)))
    yield ()

  private def runRoute(routes: HttpRoutes[IO], req: Request[IO]): IO[Response[IO]] =
    routes(req).value.map(_.getOrElse(fail("route fell through")))

  private def authed(req: Request[IO]): Request[IO] =
    req.putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $Token"))

  private def localAuthed(req: Request[IO], sessionId: String): Request[IO] =
    SpecRequests.withRemote(req.addCookie(GatewaySessionStore.CookieName, sessionId), Some(SpecRequests.LoopbackV4))

  private def uri(): Uri = Uri.unsafeFromString(s"/delegates/$Session/recovery/resume")

  private def seedTask(taskStore: SubAgentTaskStore): IO[Unit] =
    val row = SubAgentTask(
      Session,
      Parent,
      nebflow.core.entity.BuiltinAgents.ExecutorName,
      "brief",
      "route task",
      "running",
      0,
      System.currentTimeMillis(),
      None,
      None,
      "delegate"
    )
    for
      started <- taskStore.beginRun(row, Run, "root", nebflow.core.executor.ExecutorRegistry.DefaultId)
      _ <- IO.fromEither(started.left.map(new IllegalStateException(_)))
      done <- taskStore.completeRun(
        Parent,
        Session,
        Run,
        "interrupted",
        reason = Some("route-fixture"),
        notifyParent = false
      )
      _ <- IO.fromEither(done.left.map(new IllegalStateException(_)))
    yield ()

  end seedTask

  private def configBinding(definition: AgentDef): String =
    Json
      .obj(
        "agent" -> definition.name.asJson,
        "configuredModel" -> definition.model.asJson,
        "effectiveModel" -> Json.Null
      )
      .noSpaces

  private def seedAuthority(store: ExecutionCommitStore): IO[Unit] =
    val definition = nebflow.core.entity.BuiltinAgents
      .entry(nebflow.core.entity.BuiltinAgents.ExecutorName)
      .map(_.toAgentDef)
      .getOrElse(throw new IllegalStateException("executor definition unavailable"))
    val admission = ExecutionAdmission(
      Session,
      Run,
      "admission",
      "request",
      "attempt",
      "incarnation",
      Parent,
      "business",
      configBinding(definition),
      Json.fromString("initial"),
      "input"
    )
    for
      bound <- ExecutionCommitPort.admitHandle(store, admission)
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
          _ <- ProtectedAgentExecution.model(handle, runner, Json.fromString("request"), 0)(IO.pure("decision"))(
            value => ExecutionModelDecision(Json.fromString(value), Nil)
          )
          state = AgentState(messages =
            List(
              nebflow.shared.Message(nebflow.shared.MessageRole.User, Left("route prompt"), messageId = Some("input"))
            )
          )
          _ <- ProtectedAgentExecution.commitRound(handle, state, List("input"))
          _ <- ProtectedAgentExecution.suspendOwned(handle, runner, "route-fixture")
        yield ()
      }
    yield ()
    end for
  end seedAuthority
end ProtectedRecoveryRoutesSpec
