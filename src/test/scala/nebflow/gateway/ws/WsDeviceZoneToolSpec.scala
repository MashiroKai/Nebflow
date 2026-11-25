package nebflow.gateway.ws

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.{Dispatcher, Queue}
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentDef, AgentKind, AgentRecord, AgentStatus}
import nebflow.agent.{AgentActor, AgentLibrary, CompactionQueueStore, SharedResources, SubAgentTaskStore}
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.schedule.{FreezeScheduleConfig, FreezeSegment}
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
        captured <- Queue.unbounded[IO, Option[String]]
        done <- Queue.unbounded[IO, Unit]
        frozen <- Queue.unbounded[IO, Unit]
        duplicates <- Queue.unbounded[IO, Unit]
        holdNextText <- Ref.of[IO, Boolean](false)
        streamEntered <- Deferred[IO, Unit]
        releaseStream <- Deferred[IO, Unit]
        blockTool <- Ref.of[IO, Boolean](false)
        overrideCall <- Ref.of[IO, Option[Boolean]](None)
        toolEntered <- Queue.unbounded[IO, Unit]
        toolRelease <- Queue.unbounded[IO, Unit]
        schedule <- Ref.of[IO, FreezeScheduleConfig](FreezeScheduleConfig(enabled = false))
        calls <- Ref.of[IO, Int](0)
        recoveryRequests <- Queue.unbounded[IO, LlmRequest]
        recoveryWriteResults <- Queue.unbounded[IO, Either[ToolError, String]]
        fallbackAllowed <- Queue.unbounded[IO, Boolean]
        llm = new LlmHandle[IO]:
          def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new Exception("unexpected send"))
          def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
            Stream.eval((if req.sessionId == "zone-recovery" then recoveryRequests.offer(req) else IO.unit) *>
              (calls.getAndUpdate(_ + 1), overrideCall.getAndSet(None)).tupled).flatMap { (n, overrideTool) =>
              if overrideTool.getOrElse(n % 2 == 0) then Stream(StreamChunk.ToolCallChunk(ToolCall(s"zone-call-$n", toolName, JsonObject.empty)), StreamChunk.Done(None, None))
              else Stream.eval(holdNextText.getAndSet(false)).flatMap { hold =>
                val wait = if hold then Stream.eval(streamEntered.complete(()) *> releaseStream.get).drain else Stream.empty
                wait ++ Stream(StreamChunk.TextDelta("done"), StreamChunk.Done(None, None))
              }
            }
        probe = new Tool:
          def name = toolName
          def description = "capture the executing tool context"
          def inputSchema = JsonObject.empty
          def summarize(input: JsonObject) = "zone probe"
          def summarizeResult(input: JsonObject, result: String) = result
          def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
            captured.offer(ctx.deviceZone) *> fallbackAllowed.offer(ctx.deviceZoneFallbackAllowed) *> blockTool.getAndSet(false).flatMap { block =>
              val runWrite = if ctx.sessionId.contains("zone-recovery") && !block then
                val input = Json.obj("kind" -> "todo".asJson, "content" -> "recover reminder".asJson,
                  "triggerAt" -> "2035-01-01T09:00".asJson,
                  "decision" -> Json.obj("options" -> Json.arr(Json.obj("id" -> "a".asJson, "title" -> "yes".asJson))))
                nebflow.core.tools.ScheduleTool.call(input.asObject.get, ctx).flatMap(recoveryWriteResults.offer)
              else IO.unit
              (if block then toolEntered.offer(()) *> toolRelease.take else runWrite).as(Right("ok"))
            }
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
          subAgentTaskStore = new SubAgentTaskStore(root / "subagent-tasks"), voiceMutedRef = Ref.unsafe[IO, Boolean](false),
          freezeScheduleRef = schedule
        )
        ref <- system.spawn(AgentActor(agentDef = agent, resources = resources, wsSend = json =>
          json.hcursor.get[String]("type").toOption match
            case Some("done") => done.offer(())
            case Some("frozen") => frozen.offer(())
            case Some("messageDuplicate") => duplicates.offer(())
            case _ => IO.unit,
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
        zone <- captured.take.timeout(30.seconds)
        _ = assertEquals(zone, Some("Asia/Tokyo"))
        _ <- done.take.timeout(30.seconds)
        noZoneFrame = Json.obj("type" -> "userMessage".asJson, "sessionId" -> sid.asJson,
          "content" -> "execute without device".asJson)
        _ <- routes.handleMessagePublic(noZoneFrame.noSpaces, _ => IO.unit)
        nextZone <- captured.take.timeout(30.seconds)
        _ = assertEquals(nextZone, None)
        _ <- done.take.timeout(30.seconds)
        immediateFrame = Json.obj("type" -> "immediateInput".asJson, "sessionId" -> sid.asJson,
          "content" -> "immediate probe".asJson, "device" -> Json.obj("timeZone" -> "Europe/Paris".asJson))
        _ <- routes.handleMessagePublic(immediateFrame.noSpaces, _ => IO.unit)
        immediateZone <- captured.take.timeout(30.seconds)
        _ = assertEquals(immediateZone, Some("Europe/Paris"))
        _ <- done.take.timeout(30.seconds)
        immediateWithoutZone = Json.obj("type" -> "immediateInput".asJson, "sessionId" -> sid.asJson,
          "content" -> "immediate without device".asJson)
        _ <- routes.handleMessagePublic(immediateWithoutZone.noSpaces, _ => IO.unit)
        immediateNoZone <- captured.take.timeout(30.seconds)
        _ = assertEquals(immediateNoZone, None)
        _ <- done.take.timeout(30.seconds)
        typeless = Json.obj("sessionId" -> sid.asJson, "content" -> "typeless probe".asJson,
          "device" -> Json.obj("timeZone" -> "America/New_York".asJson))
        _ <- routes.handleMessagePublic(typeless.noSpaces, _ => IO.unit)
        typelessZone <- captured.take.timeout(30.seconds)
        _ = assertEquals(typelessZone, Some("America/New_York"))
        _ <- done.take.timeout(30.seconds)
        typelessNoZone = Json.obj("sessionId" -> sid.asJson, "content" -> "typeless no device".asJson)
        _ <- routes.handleMessagePublic(typelessNoZone.noSpaces, _ => IO.unit)
        typelessNextZone <- captured.take.timeout(30.seconds)
        _ = assertEquals(typelessNextZone, None)
        _ <- done.take.timeout(30.seconds)
        _ <- routes.handleMessagePublic(frame.noSpaces, _ => IO.unit)
        sourceBeforeMail <- captured.take.timeout(30.seconds)
        _ = assertEquals(sourceBeforeMail, Some("Asia/Tokyo"))
        _ <- done.take.timeout(30.seconds)
        _ <- ref ! AgentCommand.UserInput("mail wake", source = Some("mail"))
        mailZone <- captured.take.timeout(30.seconds)
        _ = assertEquals(mailZone, None)
        _ <- done.take.timeout(30.seconds)
        _ <- routes.handleMessagePublic(frame.noSpaces, _ => IO.unit)
        sourceBeforeEvent <- captured.take.timeout(30.seconds)
        _ = assertEquals(sourceBeforeEvent, Some("Asia/Tokyo"))
        _ <- done.take.timeout(30.seconds)
        _ <- fallbackAllowed.tryTakeN(None)
        _ <- ref ! AgentCommand.ExternalEvent("schedule", "trigger", "scheduled event")
        eventZone <- captured.take.timeout(30.seconds)
        eventFallback <- fallbackAllowed.take.timeout(30.seconds)
        _ = assertEquals(eventZone, None)
        _ = assert(eventFallback, "schedule-origin turn must allow the recent device fallback")
        _ <- done.take.timeout(30.seconds)
        now = java.time.LocalTime.now()
        minute = now.getHour * 60 + now.getMinute
        start = (minute + 1410) % 1440
        end = (minute + 30) % 1440
        format = (m: Int) => f"${m / 60}%02d:${m % 60}%02d"
        _ <- schedule.set(FreezeScheduleConfig(enabled = true, segments = List(FreezeSegment(format(start), format(end)))))
        _ <- ref ! AgentCommand.UserInput("system triggers freeze")
        _ <- frozen.take.timeout(30.seconds)
        _ <- ref ! AgentCommand.UserInput("wake with device", clientMessageId = Some("wake-with-zone"), deviceZone = Some("Asia/Tokyo"))
        wakeZone <- captured.take.timeout(30.seconds)
        _ = assertEquals(wakeZone, Some("Asia/Tokyo"))
        _ <- schedule.set(FreezeScheduleConfig(enabled = false))
        _ <- done.take.timeout(30.seconds)
        _ <- schedule.set(FreezeScheduleConfig(enabled = true, segments = List(FreezeSegment(format(start), format(end)))))
        _ <- ref ! AgentCommand.UserInput("system triggers second freeze")
        _ <- frozen.take.timeout(30.seconds)
        _ <- ref ! AgentCommand.UserInput("queued system input with zone", deviceZone = Some("Europe/Paris"))
        _ <- ref ! AgentCommand.UserInput("wake without device", clientMessageId = Some("wake-without-zone"))
        wakeWithoutZone <- captured.take.timeout(30.seconds)
        _ = assertEquals(wakeWithoutZone, None)
        _ <- schedule.set(FreezeScheduleConfig(enabled = false))
        _ <- done.take.timeout(30.seconds)
        queuedZone <- captured.take.timeout(30.seconds)
        _ = assertEquals(queuedZone, Some("Europe/Paris"))
        _ <- done.take.timeout(30.seconds)
        _ <- schedule.set(FreezeScheduleConfig(enabled = true, segments = List(FreezeSegment(format(start), format(end)))))
        _ <- ref ! AgentCommand.UserInput("scheduled freeze without source")
        _ <- frozen.take.timeout(30.seconds)
        _ <- schedule.set(FreezeScheduleConfig(enabled = false))
        _ <- ref ! AgentCommand.CheckFreezeGate
        thawZone <- captured.take.timeout(30.seconds)
        _ = assertEquals(thawZone, None)
        _ <- done.take.timeout(30.seconds)
        _ <- holdNextText.set(true)
        _ <- ref ! AgentCommand.UserInput("busy first", clientMessageId = Some("busy-start"), fromUser = true,
          deviceZone = Some("Asia/Tokyo"))
        firstBusy <- captured.take.timeout(30.seconds)
        _ = assertEquals(firstBusy, Some("Asia/Tokyo"))
        _ <- streamEntered.get.timeout(30.seconds)
        _ <- ref ! AgentCommand.UserInput("busy queued", clientMessageId = Some("busy-queued"), fromUser = true,
          deviceZone = Some("Europe/Paris"))
        // The duplicate receipt is emitted by this actor only after it processed
        // the preceding FIFO enqueue, while its LLM stream remains gated.
        _ <- ref ! AgentCommand.UserInput("duplicate start", clientMessageId = Some("busy-start"), fromUser = true)
        _ <- duplicates.take.timeout(30.seconds)
        _ <- releaseStream.complete(())
        queuedBusy <- captured.take.timeout(30.seconds)
        _ = assertEquals(queuedBusy, Some("Europe/Paris"))
        _ <- done.take.timeout(30.seconds)
        _ <- done.take.timeout(30.seconds)
        _ <- DeviceZoneBoundaryChecks.run(ref, captured, done, blockTool, overrideCall, toolEntered, toolRelease)
        _ <- (for
          _ <- CompactionQueueStore.save("zone-recovery", CompactionQueueStore.PersistedQueues(imms = List(
            AgentCommand.ImmediateInput("recovered-zone-probe", fromUser = true, deviceZone = Some("Asia/Tokyo")))))
          disk <- CompactionQueueStore.load("zone-recovery")
          _ = assertEquals(disk.map(_.imms.map(_.deviceZone)), Some(List(None)))
          _ = assert(!os.read(root / "sessions" / "zone-recovery" / "injection-queues.json").contains("deviceZone"))
          _ <- blockTool.set(true)
          _ <- overrideCall.set(Some(true))
          recoveredRef <- system.spawn(AgentActor(agentDef = agent, resources = resources, wsSend = _ => IO.unit,
            depth = 0, sessionId = Some("zone-recovery"), sessionName = Some("zone-recovery")), "zone-recovery")
          _ <- recoveredRef ! AgentCommand.UserInput("trigger recovered queue", clientMessageId = Some("recovery-trigger"), fromUser = true,
            deviceZone = Some("Asia/Tokyo"))
          first <- captured.take.timeout(30.seconds)
          _ = assertEquals(first, Some("Asia/Tokyo"))
          _ <- toolEntered.take.timeout(30.seconds)
          _ <- recoveryRequests.take.timeout(30.seconds)
          _ <- overrideCall.set(Some(true))
          _ <- toolRelease.offer(())
          continued <- recoveryRequests.take.timeout(30.seconds)
          _ = assert(continued.messages.exists(_.textContent.contains("recovered-zone-probe")))
          replayed <- captured.take.timeout(30.seconds)
          _ = assertEquals(replayed, None)
          // Probe calls the helper directly; real creation write ports remain unwired.
          writeResult <- recoveryWriteResults.take.timeout(30.seconds)
          _ = assert(writeResult.isLeft, s"replayed input must trigger real write-port clarification: $writeResult")
          _ = assert(writeResult.swap.toOption.get.message.toLowerCase.contains("clarif"))
          _ <- overrideCall.set(Some(false))
        yield ())
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
