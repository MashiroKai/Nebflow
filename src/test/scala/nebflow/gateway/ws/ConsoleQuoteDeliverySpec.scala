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
end ConsoleQuoteDeliverySpec
