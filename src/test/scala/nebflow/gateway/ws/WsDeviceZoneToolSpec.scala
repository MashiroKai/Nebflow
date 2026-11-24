package nebflow.gateway.ws

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentDef, AgentKind, AgentRecord, AgentStatus}
import nebflow.agent.{AgentActor, AgentLibrary, SharedResources, SubAgentTaskStore}
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, Tool, ToolContext, ToolError, ToolRegistry}
import nebflow.gateway.{GatewaySessionStore, WebSocketRoutes}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, NebflowServiceConfig, PathUtil, ServiceLlmConfig, StreamChunk, ThinkingConfig, ToolCall}
import scala.concurrent.duration.*

class WsDeviceZoneToolSpec extends CatsEffectSuite:
  override def munitIOTimeout: FiniteDuration = 90.seconds

  test("userMessage frame reaches a real tool with its device zone") {
    val root = os.temp.dir(prefix = "zone-tool-")
    val previousRoot = PathUtil.dataRoot
    val system = ActorSystem("zone-tool-spec")
    val sid = "zone-tool-session"
    val toolName = "ZoneProbe_FrameSpec"
    val agent = AgentDef(name = "ZoneProbeAgent", description = "zone probe", tools = List(toolName), systemPrompt = "")
    val oldLog = nebflow.core.LlmLogWriter.isEnabled
    PathUtil.setDataRoot(root)
    nebflow.core.LlmLogWriter.setEnabled(false)
    val program = Dispatcher.parallel[IO].use { dispatcher =>
      for
        captured <- Deferred[IO, Option[String]]
        calls <- Ref.of[IO, Int](0)
        llm = new LlmHandle[IO]:
          def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new Exception("unexpected send"))
          def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
            Stream.eval(calls.getAndUpdate(_ + 1)).flatMap { n =>
              if n == 0 then Stream(StreamChunk.ToolCallChunk(ToolCall("zone-call", toolName, JsonObject.empty)), StreamChunk.Done(None, None))
              else Stream(StreamChunk.TextDelta("done"), StreamChunk.Done(None, None))
            }
        probe = new Tool:
          def name = toolName
          def description = "capture the executing tool context"
          def inputSchema = JsonObject.empty
          def summarize(input: JsonObject) = "zone probe"
          def summarizeResult(input: JsonObject, result: String) = result
          def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
            captured.complete(ctx.deviceZone).as(Right("ok"))
        _ <- IO(ToolRegistry.registerTool(probe))
        rate <- RateLimiter.create()
        tracker <- FileChangeTracker.create(root.toString)
        locks <- FileLockManager.create
        store = SessionStore(root / "sessions", root / "tasks")
        resources = SharedResources(
          llm = llm, dispatcher = dispatcher, sessionStore = store, projectRoot = root,
          thinkingConfigRef = Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()), rateLimiter = rate,
          fileChangeTracker = tracker, contextWindow = 100000, agentLibrary = new AgentLibrary(root / "agents"),
          taskStore = FileTaskStore, historyArchiver = HistoryArchiver.fileSystem(root / "archives"),
          fileLockManager = locks, sessionModelOverrides = Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
          providerRegistry = null, healthMonitor = ProviderHealthMonitor(null), actorSystem = system,
          subAgentTaskStore = new SubAgentTaskStore(root / "subagent-tasks"), voiceMutedRef = Ref.unsafe[IO, Boolean](false)
        )
        ref <- system.spawn(AgentActor(agentDef = agent, resources = resources, wsSend = _ => IO.unit,
          depth = 0, sessionId = Some(sid), sessionName = Some(sid)), sid)
        _ <- resources.agentRegistry.set(Map(sid -> AgentRecord(sid, ref, AgentKind.Root, sid, status = AgentStatus.Idle)))
        routes = new WebSocketRoutes(
          wsb = null, sessionService = null, agentService = null, configService = nebflow.service.ConfigService,
          configRef = Ref.unsafe[IO, NebflowServiceConfig](NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))),
          rateLimiter = rate, tokenRef = Ref.unsafe[IO, String]("test"), sessions = GatewaySessionStore.unsafeCreate(),
          fileChangeTracker = tracker, sessionStore = store, wsHub = null, sharedResources = resources, mcpManager = null
        )
        frame = Json.obj("type" -> "userMessage".asJson, "sessionId" -> sid.asJson,
          "content" -> "execute probe".asJson, "device" -> Json.obj("timeZone" -> "Asia/Tokyo".asJson))
        _ <- routes.handleMessagePublic(frame.noSpaces, _ => IO.unit)
        zone <- captured.get.timeout(30.seconds)
        _ = assertEquals(zone, Some("Asia/Tokyo"))
        _ <- store.flushPendingUiWrites
      yield ()
    }
    program.guarantee(
      system.stopAll.attempt.void *> IO {
        ToolRegistry.unregisterTool(toolName)
        nebflow.db.DbHost.release(root.toNIO)
        nebflow.core.LlmLogWriter.setEnabled(oldLog)
        PathUtil.setDataRoot(previousRoot)
        os.remove.all(root)
      }
    )
  }
