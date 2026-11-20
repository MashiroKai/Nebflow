package nebflow.gateway.ws

import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorPath, ActorRef, AgentCommand, AgentKind, AgentRecord, AgentStatus}
import nebflow.agent.SharedResources
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.core.task.FileTaskStore
import nebflow.gateway.{GatewaySessionStore, WebSocketRoutes}
import nebflow.llm.ModelCandidate
import nebflow.shared.{ContentBlock, NebflowServiceConfig, PathUtil, ServiceLlmConfig, ThinkingConfig}
import scala.concurrent.duration.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import java.nio.file.Files

class ConsoleQuoteDeliverySpec extends CatsEffectSuite:
  test("console input handler delivers structured quoted body to the registered agent") {
    val root = os.temp.dir(prefix = "console-delivery-")
    val previousRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(root)
    val store = SessionStore(root / "sessions", root / "tasks")
    val sid = "console-delivery-test"
    val body = "source text\nsecond line\tunchanged"
    (for
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      sink = new ActorRef[AgentCommand]:
        def path: ActorPath = null
        def !(msg: AgentCommand): IO[Unit] = received.update(_ :+ msg)
        def ?[R](f: ActorRef[R] => AgentCommand, timeout: Option[FiniteDuration] = Some(30.seconds)): IO[R] = IO.raiseError(new Exception("not used"))
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
      _ <- resources.agentRegistry.set(Map(sid -> AgentRecord(sessionId = sid, ref = sink, kind = AgentKind.Root, rootSessionId = sid, status = AgentStatus.Idle)))
      routes = new WebSocketRoutes(
        wsb = null, sessionService = null, agentService = null, configService = nebflow.service.ConfigService,
        configRef = Ref.unsafe[IO, NebflowServiceConfig](NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))),
        rateLimiter = rate, tokenRef = Ref.unsafe[IO, String]("test"), sessions = GatewaySessionStore.unsafeCreate(),
        fileChangeTracker = null, sessionStore = store, wsHub = null, sharedResources = resources, mcpManager = null
      )
      frame = Json.obj("sessionId" -> sid.asJson, "content" -> "execute".asJson,
        "refs" -> Json.arr(Json.obj("refType" -> "chat-message".asJson,
          "source" -> Json.obj("messageId" -> "row-1".asJson, "senderName" -> "author".asJson),
          "content" -> Json.obj("fullText" -> body.asJson, "fullTextComplete" -> true.asJson))))
      _ <- routes.handleMessagePublic(frame.noSpaces, _ => IO.unit)
      messages <- received.get
      _ <- store.flushPendingUiWrites
    yield
      val texts = messages.collect { case u: AgentCommand.UserInput => u.blocks.toList.flatten.collect { case ContentBlock.Text(t) => t } }.flatten
      assert(texts.exists(_.contains(body)), s"actual agent input: $texts")
    ).guarantee(IO { nebflow.db.DbHost.release(root.toNIO); PathUtil.setDataRoot(previousRoot); os.remove.all(root) })
  }
  test("immediate input preserves display text while adding device provenance to model input") {
    val root = os.temp.dir(prefix = "device-delivery-")
    val previousRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(root)
    val store = SessionStore(root / "sessions", root / "tasks")
    val sid = "device-delivery-test"
    val body = "original user text"
    (for
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      sink = new ActorRef[AgentCommand]:
        def path: ActorPath = null
        def !(msg: AgentCommand): IO[Unit] = received.update(_ :+ msg)
        def ?[R](f: ActorRef[R] => AgentCommand, timeout: Option[FiniteDuration] = Some(30.seconds)): IO[R] = IO.raiseError(new Exception("not used"))
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
      _ <- resources.agentRegistry.set(Map(sid -> AgentRecord(sessionId = sid, ref = sink, kind = AgentKind.Root, rootSessionId = sid, status = AgentStatus.Idle)))
      routes = new WebSocketRoutes(
        wsb = null, sessionService = null, agentService = null, configService = nebflow.service.ConfigService,
        configRef = Ref.unsafe[IO, NebflowServiceConfig](NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))),
        rateLimiter = rate, tokenRef = Ref.unsafe[IO, String]("test"), sessions = GatewaySessionStore.unsafeCreate(),
        fileChangeTracker = null, sessionStore = store, wsHub = null, sharedResources = resources, mcpManager = null
      )
      frame = Json.obj("type" -> "immediateInput".asJson, "sessionId" -> sid.asJson, "content" -> body.asJson,
        "device" -> Json.obj("clientId" -> "fixture-client".asJson, "platform" -> "phone".asJson, "browser" -> "Chrome".asJson))
      _ <- routes.handleMessagePublic(frame.noSpaces, _ => IO.unit)
      messages <- received.get
      _ <- store.flushPendingUiWrites
      rows <- store.getUiMessages(sid, 0, 100)
    yield
      val inputs = messages.collect { case msg: AgentCommand.ImmediateInput => msg.text }
      assertEquals(inputs.size, 1)
      assert(inputs.head.contains(body))
      assert(inputs.head != body, "model input must include device marker")
      assertEquals(rows._1.collect { case u: nebflow.shared.UiMessage.User => u.text }, List(body))
    ).guarantee(IO { nebflow.db.DbHost.release(root.toNIO); PathUtil.setDataRoot(previousRoot); os.remove.all(root) })
  }

  test("browser sends short long missing and split references through the real websocket") {
    val root = os.temp.dir(prefix = "console-browser-ws-")
    val previousRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(root)
    val store = SessionStore(root / "sessions", root / "tasks")
    val sid = "quote-browser-test"
    val evidence = java.nio.file.Path.of(sys.env.getOrElse("QUOTE_EVIDENCE", root.toString))
    (for
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      sink = new ActorRef[AgentCommand]:
        def path: ActorPath = null
        def !(msg: AgentCommand): IO[Unit] = received.update(_ :+ msg)
        def ?[R](f: ActorRef[R] => AgentCommand, timeout: Option[FiniteDuration] = Some(30.seconds)): IO[R] = IO.raiseError(new Exception("not used"))
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
      _ <- resources.agentRegistry.set(Map(sid -> AgentRecord(sessionId = sid, ref = sink, kind = AgentKind.Root, rootSessionId = sid, status = AgentStatus.Idle)))
      sessions = GatewaySessionStore.unsafeCreate()
      auth <- sessions.create("fixture-owner", "local-ticket", "fixture")
      mcp <- nebflow.core.mcp.McpManager.create
      hub = new nebflow.gateway.WsHub
      runner = EmberServerBuilder.default[IO].withHost(ipv4"127.0.0.1").withPort(port"0")
        .withHttpWebSocketApp { wsb =>
          val routes = new WebSocketRoutes(wsb = wsb, sessionService = new nebflow.service.SessionService(store),
            agentService = null, configService = nebflow.service.ConfigService,
            configRef = Ref.unsafe[IO, NebflowServiceConfig](NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))),
            rateLimiter = rate, tokenRef = Ref.unsafe[IO, String]("fixture"), sessions = sessions,
            fileChangeTracker = null, sessionStore = store, wsHub = hub, sharedResources = resources, mcpManager = mcp)
          val probe = HttpRoutes.of[IO] {
            case req @ GET -> _ if req.uri.path.renderString == "/js/input.js" =>
              StaticFile.fromPath(fs2.io.file.Path("src/main/resources/web/js/input.js"), Some(req)).getOrElseF(NotFound())
            case GET -> Root / "probe-token" / core => resources.msgTokens.lookup(core).flatMap {
              case Some(mapping) => Ok(Json.obj("text" -> mapping.text.asJson).noSpaces)
              case None => NotFound()
            }
            case GET -> Root / "probe" => received.get.flatMap { commands =>
              val text = commands.collect { case u: AgentCommand.UserInput =>
                u.blocks.toList.flatten.collect { case ContentBlock.Text(t) => t }.mkString("\n")
              }
              Ok(Json.fromValues(text.map(Json.fromString)).noSpaces)
            }
            case GET -> Root / "fixture" => Ok("<!doctype html><html><body>fixture</body></html>").map(_.withContentType(org.http4s.headers.`Content-Type`(MediaType.text.html)))
          }
          Router("/" -> (probe <+> routes.routes)).orNotFound
        }.build
      _ <- runner.use { server =>
        val port = server.address.getPort
        IO.blocking {
          val log = evidence.resolve("canvas-watch-ws.txt").toFile
          val pb = new ProcessBuilder("node", "tests/fixtures/canvas-watch-ws.mjs", port.toString, auth.sessionId,
            nebflow.gateway.GatewaySessionStore.CookieName, sid, root.toString)
          pb.redirectErrorStream(true)
          pb.redirectOutput(log)
          val code = pb.start().waitFor()
          assertEquals(code, 0, Files.readString(log.toPath))
        }
      }
      _ <- runner.use { server =>
        val port = server.address.getPort
        IO.blocking {
          val log = evidence.resolve("browser-ws-process.txt").toFile
          val pb = new ProcessBuilder("node", "tests/fixtures/console-quote-ws.mjs", port.toString, auth.sessionId,
            nebflow.gateway.GatewaySessionStore.CookieName, sid)
          pb.redirectErrorStream(true)
          pb.redirectOutput(log)
          val proc = pb.start()
          val code = proc.waitFor()
          assertEquals(code, 0, Files.readString(log.toPath))
        }
      }
      _ <- store.flushPendingUiWrites
    yield ()).guarantee(IO { nebflow.db.DbHost.release(root.toNIO); PathUtil.setDataRoot(previousRoot); os.remove.all(root) })
  }
end ConsoleQuoteDeliverySpec
