package nebflow.gateway.ws

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorPath, ActorRef, AgentCommand, AgentKind, AgentRecord, AgentStatus}
import nebflow.agent.SharedResources
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.core.task.FileTaskStore
import nebflow.gateway.{GatewaySessionStore, WebSocketRoutes}
import nebflow.llm.ModelCandidate
import nebflow.shared.{NebflowServiceConfig, PathUtil, ServiceLlmConfig, ThinkingConfig}
import scala.concurrent.duration.*

class WsSessionDeviceZoneSpec extends CatsEffectSuite:
  test("authenticated device reports are durable and select the newest zone deterministically") {
    val root = os.temp.dir(prefix = "device-zone-reports-")
    val reports = new nebflow.core.scheduler.DeviceZoneReports(root)
    val tokyo = Json.obj("type" -> "takeoverIdentify".asJson, "clientId" -> "a".asJson, "timeZone" -> "Asia/Tokyo".asJson)
    val paris = Json.obj("type" -> "takeoverIdentify".asJson, "clientId" -> "b".asJson, "timeZone" -> "Europe/Paris".asJson)
    for
      _ <- reports.recordIdentify(tokyo, authenticated = false, 100L)
      empty <- reports.latest
      _ = assertEquals(empty, None)
      _ <- reports.recordIdentify(tokyo, authenticated = true, 100L)
      _ <- reports.recordIdentify(paris, authenticated = true, 200L)
      loaded <- new nebflow.core.scheduler.DeviceZoneReports(root).latest
      _ = assertEquals(loaded.map(_.zoneId), Some("Europe/Paris"))
      _ = assertEquals(loaded.map(_.deviceId), Some("browser:b"))
      _ <- IO(os.remove.all(root))
    yield ()
  }
  test("gateway forwards a device zone with the user input") {
    val root = os.temp.dir(prefix = "device-zone-gateway-")
    val previousRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(root)
    val store = SessionStore(root / "sessions", root / "tasks")
    val sid = "device-zone-test"
    (for
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      sink = new ActorRef[AgentCommand]:
        def path: ActorPath = null
        def !(msg: AgentCommand): IO[Unit] = received.update(_ :+ msg)
        def ?[R](f: ActorRef[R] => AgentCommand, timeout: Option[FiniteDuration] = Some(30.seconds)): IO[R] =
          IO.raiseError(new Exception("not used"))
      rate <- RateLimiter.create()
      resources = SharedResources(
        llm = null, dispatcher = null, sessionStore = store, projectRoot = root,
        thinkingConfigRef = Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()), rateLimiter = rate,
        fileChangeTracker = null, contextWindow = 100000, agentLibrary = null, taskStore = FileTaskStore,
        historyArchiver = null, fileLockManager = null,
        sessionModelOverrides = Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
        providerRegistry = null, healthMonitor = null, actorSystem = null,
        voiceMutedRef = Ref.unsafe[IO, Boolean](false)
      )
      _ <- resources.agentRegistry.set(Map(sid -> AgentRecord(
        sessionId = sid, ref = sink, kind = AgentKind.Root, rootSessionId = sid, status = AgentStatus.Idle
      )))
      routes = new WebSocketRoutes(
        wsb = null, sessionService = null, agentService = null, configService = nebflow.service.ConfigService,
        configRef = Ref.unsafe[IO, NebflowServiceConfig](NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))),
        rateLimiter = rate, tokenRef = Ref.unsafe[IO, String]("test"), sessions = GatewaySessionStore.unsafeCreate(),
        fileChangeTracker = null, sessionStore = store, wsHub = null, sharedResources = resources, mcpManager = null
      )
      frame = Json.obj(
        "sessionId" -> sid.asJson, "content" -> "execute".asJson,
        "device" -> Json.obj("timeZone" -> "Asia/Tokyo".asJson)
      )
      _ <- routes.handleMessagePublic(frame.noSpaces, _ => IO.unit)
      messages <- received.get
      _ <- store.flushPendingUiWrites
    yield assertEquals(messages.collectFirst { case u: AgentCommand.UserInput => u.deviceZone }.flatten, Some("Asia/Tokyo")))
      .guarantee(IO { nebflow.db.DbHost.release(root.toNIO); PathUtil.setDataRoot(previousRoot); os.remove.all(root) })
  }
