package nebflow.agent

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.processor.{FreezeScheduler, TaskStuckWatcher}
import nebflow.core.schedule.{FreezeScheduleConfig, FreezeSegment}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore, WsHub}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{ContentBlock, FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * freeze-schedule spec §6 后端行为验收：gate 拦截（B3/B4）、唤醒语义（B5）、
 * 自动恢复（B6）、TaskStuckWatcher 豁免（B7）、交互豁免（B11）、team busy
 * 时序（B12）。真实 AgentActor harness（仿 StopHangTurnSpec）+ 计数 mock LLM。
 */
class FreezeGateSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 90.seconds

  /** 计数 + 捕获请求的 mock：每请求一个 TextDelta + Done（纯文本收尾）。 */
  private class CountingLlm(
    counter: cats.effect.Ref[IO, Int],
    requests: cats.effect.Ref[IO, List[LlmRequest]]
  ) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(counter.update(_ + 1) *> requests.update(_ :+ req)) >>
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  /** 工具轮 mock：首请求返回 Read tool call（延迟 Done 制造关窗时间窗），后续纯文本。 */
  private class ToolThenTextLlm(
    counter: cats.effect.Ref[IO, Int],
    requests: cats.effect.Ref[IO, List[LlmRequest]],
    filePath: String
  ) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(counter.update(_ + 1) *> requests.update(_ :+ req)) >> Stream.eval(counter.get).flatMap {
        case 1 =>
          Stream(StreamChunk.ToolCallChunk(nebflow.shared.ToolCall(
            id = "tc-read-1",
            name = "Read",
            input = io.circe.JsonObject("file_path" -> filePath.asJson)
          ))) ++ Stream.sleep[IO](400.millis).drain ++
            Stream(StreamChunk.Done(None, None))
        case _ =>
          Stream(StreamChunk.TextDelta("done"), StreamChunk.Done(None, None))
      }

  private def mkResources(
    system: ActorSystem,
    tmp: os.Path,
    llm: LlmHandle[IO],
    scheduleRef: cats.effect.Ref[IO, FreezeScheduleConfig]
  ): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted,
      freezeScheduleRef = scheduleRef
    )

  /**
   * 恒覆盖当前时刻的合法冻结段（#337 黑名单语义；跨日安全）：start = now-30min、
   * end = now+30min（各模 1440）——now±30 落午夜两侧时即跨午夜段（start > end），
   * 恰好顺带覆盖 P2 环绕判定。start != end 恒成立（相隔 60min）。
   */
  private def frozenConfig(): FreezeScheduleConfig =
    val now = java.time.LocalTime.now()
    val minuteOfDay = now.getHour * 60 + now.getMinute
    val s = (minuteOfDay - 30 + 1440) % 1440
    val e = (minuteOfDay + 30) % 1440
    def fmt(m: Int): String = f"${m / 60}%02d:${m % 60}%02d"
    FreezeScheduleConfig(enabled = true, segments = List(FreezeSegment(fmt(s), fmt(e))))

  private val openConfig: FreezeScheduleConfig = FreezeScheduleConfig(enabled = false)

  private def rootDef(name: String = "Nebula", category: String = ""): AgentDef =
    AgentDef(name = name, description = "test", tools = List("Read"), systemPrompt = "", category = category)

  private def waitUntil(deadlineMs: Long)(cond: IO[Boolean]): IO[Unit] =
    cond.flatMap {
      case true => IO.unit
      case false =>
        if System.currentTimeMillis() > deadlineMs then IO.raiseError(new RuntimeException("waitUntil timeout"))
        else IO.sleep(100.millis) *> waitUntil(deadlineMs)(cond)
    }

  private def hasEvent(events: cats.effect.Ref[IO, List[Json]], tpe: String): IO[Boolean] =
    events.get.map(_.exists(_.hcursor.downField("type").as[String].contains(tpe)))

  // ── B3/B4: gate 拦截，零 LLM 调用 ──────────────────────────

  test("B3: freeze segment blocks system-initiated dispatch — zero LLM calls, Frozen status + event") {
    val system = ActorSystem("freeze-b3")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        schedRef <- IO.ref(frozenConfig())
        resources <- mkResources(system, tmp, CountingLlm(counter, requests), schedRef)
        sid = "freeze-b3-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef(),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("b3")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
        // 系统注入（clientMessageId=None）→ Gated → 冻结
        _ <- ref ! AgentCommand.UserInput("system-injected task")
        _ <- IO.sleep(600.millis)
        count <- counter.get
        _ = assert(count == 0, s"sendStream must NOT be called while frozen, got $count")
        regStatus <- resources.agentRegistry.get.map(_.get(sid).map(_.status))
        _ = assertEquals(regStatus, Some(AgentStatus.Frozen))
        frozenSeen <- hasEvent(events, "frozen")
        _ = assert(frozenSeen, "must emit frozen WS event")
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  test("B4: idle ExternalEvent dispatch path is gated (frozen, zero calls)") {
    val system = ActorSystem("freeze-b4-evt")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        schedRef <- IO.ref(frozenConfig())
        resources <- mkResources(system, tmp, CountingLlm(counter, requests), schedRef)
        sid = "freeze-b4-evt-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef(),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("b4evt")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
        // 非 subagent 结果事件（idle 直接注入 + dispatch 路径）
        _ <- ref ! AgentCommand.ExternalEvent("background-task", "completed", "job done", io.circe.JsonObject.empty)
        _ <- IO.sleep(600.millis)
        count <- counter.get
        _ = assert(count == 0, s"ExternalEvent-driven dispatch must be frozen, got $count")
        regStatus <- resources.agentRegistry.get.map(_.get(sid).map(_.status))
        _ = assertEquals(regStatus, Some(AgentStatus.Frozen))
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  test("B4: ToolsComplete continuation is gated — tool ran, next LLM round frozen") {
    val system = ActorSystem("freeze-b4-tools")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    val target = tmp / "readme.txt"
    os.write.over(target, "freeze-b4 content")
    try
      val program = for
        counter <- IO.ref(0)
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        schedRef <- IO.ref(openConfig) // 不冻结启动第一轮
        resources <- mkResources(system, tmp, ToolThenTextLlm(counter, requests, target.toString), schedRef)
        sid = "freeze-b4-tools-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef(),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("b4tools")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
        _ <- ref ! AgentCommand.UserInput("read the file", None, Some("b4-tools-1"))
        // 等首轮 LLM 请求发出（tool call 已流回、400ms 延迟 Done 之前）→ 关窗
        _ <- waitUntil(System.currentTimeMillis() + 5000)(counter.get.map(_ >= 1))
        _ <- schedRef.set(frozenConfig())
        // Done → 工具执行 → ToolsComplete → 续轮 dispatch 过 gate → 冻结
        _ <- waitUntil(System.currentTimeMillis() + 8000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Frozen))
        )
        _ <- IO.sleep(500.millis)
        count <- counter.get
        // 首轮 1 次（工具前）必须发生；续轮（工具结果后）必须被冻结 → 恒为 1
        _ = assert(count == 1, s"tool continuation must be frozen after window closed, got $count")
        frozenSeen <- hasEvent(events, "frozen")
        _ = assert(frozenSeen, "must emit frozen WS event at ToolsComplete boundary")
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  // ── B5: 唤醒语义 ──────────────────────────────────────────

  test("B5: user wake injects user message after tool results and dispatches once") {
    val system = ActorSystem("freeze-b5-wake")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    val target = tmp / "readme.txt"
    os.write.over(target, "freeze-b5 content")
    try
      val program = for
        counter <- IO.ref(0)
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        schedRef <- IO.ref(openConfig)
        resources <- mkResources(system, tmp, ToolThenTextLlm(counter, requests, target.toString), schedRef)
        sid = "freeze-b5-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef(),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("b5")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
        _ <- ref ! AgentCommand.UserInput("read the file", None, Some("b5-1"))
        _ <- waitUntil(System.currentTimeMillis() + 5000)(counter.get.map(_ >= 1))
        _ <- schedRef.set(frozenConfig())
        _ <- waitUntil(System.currentTimeMillis() + 8000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Frozen))
        )
        // 冻结中：用户消息唤醒（clientMessageId=Some）→ 工具结果 + 用户新指令同轮
        _ <- ref ! AgentCommand.UserInput("WAKE_UP_NOW continue with this", None, Some("b5-wake"))
        _ <- waitUntil(System.currentTimeMillis() + 8000)(counter.get.map(_ >= 2))
        _ <- IO.sleep(300.millis)
        reqs <- requests.get
        wakeReq = reqs.last
        // 注：turn 管线会在末条用户消息后追加 task-list/env-change reminder（缓存
        // 优化既有行为），故断言「wake 消息存在且位于冻结工具结果之后」而非严格末条。
        wakeIdx = wakeReq.messages.lastIndexWhere(_.textContent.contains("WAKE_UP_NOW"))
        toolResultIdx = wakeReq.messages.lastIndexWhere(m =>
          m.content.fold(_ => false, bs => bs.exists(_.isInstanceOf[ContentBlock.ToolResult]))
        )
        _ = assert(wakeIdx >= 0, "wake dispatch must carry the wake user message")
        _ = assert(toolResultIdx >= 0, "wake dispatch must carry the frozen tool results")
        _ = assert(
          wakeIdx > toolResultIdx,
          s"wake user message must come after the frozen tool results (wakeIdx=$wakeIdx toolResultIdx=$toolResultIdx)"
        )
        resumedSeen <- hasEvent(events, "resumed")
        _ = assert(resumedSeen, "must emit resumed WS event on wake")
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  test("B5: system input while frozen is queued, not dispatched; drained after resume") {
    val system = ActorSystem("freeze-b5-sys")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        schedRef <- IO.ref(frozenConfig())
        resources <- mkResources(system, tmp, CountingLlm(counter, requests), schedRef)
        sid = "freeze-b5-sys-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef(),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("b5sys")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
        // 冻结（系统输入触发）
        _ <- ref ! AgentCommand.UserInput("initial task")
        _ <- waitUntil(System.currentTimeMillis() + 5000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Frozen))
        )
        // 冻结期间再注入系统消息：排队不唤醒
        _ <- ref ! AgentCommand.UserInput("queued system message")
        _ <- IO.sleep(600.millis)
        count1 <- counter.get
        _ = assert(count1 == 0, s"system input must not wake a frozen agent, got $count1")
        // 解除冻结 + 恢复 → 首个挂起任务 dispatch；turn 结束 drain 排队消息 → 第二轮
        _ <- schedRef.set(openConfig)
        _ <- ref ! AgentCommand.CheckFreezeGate
        _ <- waitUntil(System.currentTimeMillis() + 8000)(counter.get.map(_ >= 1))
        _ <- waitUntil(System.currentTimeMillis() + 10000)(counter.get.map(_ >= 2))
        reqs <- requests.get
        _ = assert(
          reqs.exists(_.messages.exists(_.textContent.contains("queued system message"))),
          "queued system input must be drained into a follow-up turn after resume"
        )
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  test("B5: duplicate wake clientMessageId does not double-inject") {
    val system = ActorSystem("freeze-b5-dup")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        schedRef <- IO.ref(frozenConfig())
        resources <- mkResources(system, tmp, CountingLlm(counter, requests), schedRef)
        sid = "freeze-b5-dup-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef(),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("b5dup")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
        // 第一次冻结 + 唤醒 + 完整 turn 完成（回 idle）——clientMessageId 进入
        // recentMessageIds
        _ <- ref ! AgentCommand.UserInput("initial task")
        _ <- waitUntil(System.currentTimeMillis() + 5000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Frozen))
        )
        _ <- ref ! AgentCommand.UserInput("wake once", None, Some("dup-1"))
        _ <- waitUntil(System.currentTimeMillis() + 8000)(counter.get.map(_ >= 1))
        // 再次冻结（系统输入）后重发同一 clientMessageId → dedup，不 dispatch
        _ <- waitUntil(System.currentTimeMillis() + 8000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Idle))
        )
        _ <- ref ! AgentCommand.UserInput("freeze again")
        _ <- waitUntil(System.currentTimeMillis() + 5000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Frozen))
        )
        _ <- ref ! AgentCommand.UserInput("wake once", None, Some("dup-1"))
        _ <- IO.sleep(700.millis)
        count <- counter.get
        _ = assert(count == 1, s"duplicate wake must be deduped, expected 1 dispatch, got $count")
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  // ── B6: 自动恢复 ──────────────────────────────────────────

  test("B6: CheckFreezeGate outside freeze segment resumes the held dispatch + resumed event") {
    val system = ActorSystem("freeze-b6")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        schedRef <- IO.ref(frozenConfig())
        resources <- mkResources(system, tmp, CountingLlm(counter, requests), schedRef)
        sid = "freeze-b6-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef(),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("b6")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
        _ <- ref ! AgentCommand.UserInput("task to freeze")
        _ <- waitUntil(System.currentTimeMillis() + 5000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Frozen))
        )
        // 开窗 + CheckFreezeGate → 恢复
        _ <- schedRef.set(openConfig)
        _ <- ref ! AgentCommand.CheckFreezeGate
        _ <- waitUntil(System.currentTimeMillis() + 8000)(counter.get.map(_ >= 1))
        resumedSeen <- hasEvent(events, "resumed")
        _ = assert(resumedSeen, "must emit resumed WS event on window-open resume")
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  test("B6: FreezeScheduler.scan delivers CheckFreezeGate to frozen agents") {
    val system = ActorSystem("freeze-b6-scan")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        schedRef <- IO.ref(frozenConfig())
        resources <- mkResources(system, tmp, CountingLlm(counter, requests), schedRef)
        sid = "freeze-b6-scan-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef(),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("b6scan")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
        _ <- ref ! AgentCommand.UserInput("task to freeze")
        _ <- waitUntil(System.currentTimeMillis() + 5000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Frozen))
        )
        // 关窗状态 scan：重评估后仍冻结，零 dispatch（幂等无害）
        _ <- FreezeScheduler.scan(resources)
        _ <- IO.sleep(400.millis)
        count1 <- counter.get
        _ = assert(count1 == 0, "closed-window scan must not resume")
        // 解除冻结后 scan：CheckFreezeGate 送达 → 恢复
        _ <- schedRef.set(openConfig)
        _ <- FreezeScheduler.scan(resources)
        _ <- waitUntil(System.currentTimeMillis() + 8000)(counter.get.map(_ >= 1))
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  // ── Interrupt 放弃续跑 ────────────────────────────────────

  test("Interrupt during frozen abandons continuation — idle, then user wake works at idle") {
    val system = ActorSystem("freeze-int")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        schedRef <- IO.ref(frozenConfig())
        resources <- mkResources(system, tmp, CountingLlm(counter, requests), schedRef)
        sid = "freeze-int-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef(),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("int")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
        _ <- ref ! AgentCommand.UserInput("task to abandon")
        _ <- waitUntil(System.currentTimeMillis() + 5000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Frozen))
        )
        _ <- ref ! AgentCommand.Interrupt()
        _ <- waitUntil(System.currentTimeMillis() + 5000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Idle))
        )
        interruptedSeen <- hasEvent(events, "interrupted")
        _ = assert(interruptedSeen, "must emit interrupted event")
        // 放弃后：用户消息在 idle 直达（UserWake 放行，冻结时段也 dispatch）
        _ <- ref ! AgentCommand.UserInput("fresh start after abandon", None, Some("int-wake"))
        _ <- waitUntil(System.currentTimeMillis() + 8000)(counter.get.map(_ >= 1))
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  // ── B11: 交互豁免 ─────────────────────────────────────────

  test("B11: askMode turn is exempt — dispatches inside frozen window") {
    val system = ActorSystem("freeze-b11-ask")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        schedRef <- IO.ref(frozenConfig())
        resources <- mkResources(system, tmp, CountingLlm(counter, requests), schedRef)
        sid = "freeze-b11-ask-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef(),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("b11ask")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
        _ <- ref ! AgentCommand.AskQuestion("what next?", sid)
        _ <- waitUntil(System.currentTimeMillis() + 8000)(counter.get.map(_ >= 1))
        regStatus <- resources.agentRegistry.get.map(_.get(sid).map(_.status))
        _ = assert(regStatus.contains(AgentStatus.Processing) || regStatus.contains(AgentStatus.Idle),
          s"ask turn must not freeze, got $regStatus")
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  test("B11: freezeExempt agent dispatches inside frozen window") {
    val system = ActorSystem("freeze-b11-exempt")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        schedRef <- IO.ref(frozenConfig())
        resources <- mkResources(system, tmp, CountingLlm(counter, requests), schedRef)
        sid = "freeze-b11-exempt-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef(),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 1,
            sessionId = Some(sid),
            sessionName = Some("plan-like"),
            freezeExempt = true
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Plan, sid, None)))
        // 系统注入（Gated）也应放行——豁免优先于 cause
        _ <- ref ! AgentCommand.UserInput("plan this task")
        _ <- waitUntil(System.currentTimeMillis() + 8000)(counter.get.map(_ >= 1))
        regStatus <- resources.agentRegistry.get.map(_.get(sid).map(_.status))
        _ = assert(regStatus.forall(_ != AgentStatus.Frozen), s"exempt agent must not freeze, got $regStatus")
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  // ── B12: team busy 时序 ───────────────────────────────────

  test("B12: frozen team agent stays busy in TeamSessionRegistry") {
    val system = ActorSystem("freeze-b12")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        schedRef <- IO.ref(frozenConfig())
        resources <- mkResources(system, tmp, CountingLlm(counter, requests), schedRef)
        sid = "freeze-b12-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef("Backend", category = "team"),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 1,
            sessionId = Some(sid),
            sessionName = Some("b12")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Team, sid, None)))
        _ <- ref ! AgentCommand.UserInput("team task")
        _ <- waitUntil(System.currentTimeMillis() + 5000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Frozen))
        )
        busy <- nebflow.core.flow.TeamSessionRegistry.isBusy(sid)
        _ = assert(busy, "frozen team agent must read busy (Teams panel correctness)")
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  // ── B7: TaskStuckWatcher 豁免（纯 registry 层测试）────────

  test("B7: TaskStuckWatcher ignores Frozen records (exempt) but acts on Processing (control)") {
    val system = ActorSystem("freeze-b7")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp / "data")
    try
      val wsHub = new WsHub()
      val program = for
        probeEvents <- IO.ref(List.empty[AgentCommand])
        // probe actor 记录收到的所有命令
        probeRef <- system.spawn(
          {
            def loop: Behavior[AgentCommand] =
              Behaviors.receiveMessage[AgentCommand](cmd => probeEvents.update(_ :+ cmd).as(loop))
            loop
          },
          "b7-probe"
        )
        hubEvents <- IO.ref(List.empty[Json])
        _ <- wsHub.register(json => hubEvents.update(_ :+ json))
        dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
        rateLimiter <- RateLimiter.create()
        tracker <- FileChangeTracker.create(os.pwd.toString)
        fileLocks <- FileLockManager.create
        thinkingRef <- IO.ref(ThinkingConfig())
        modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
        voiceMuted <- IO.ref(false)
        schedRef <- IO.ref(openConfig)
        resources = SharedResources(
          llm = null,
          dispatcher = dispatcher,
          sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
          projectRoot = os.pwd,
          thinkingConfigRef = thinkingRef,
          rateLimiter = rateLimiter,
          fileChangeTracker = tracker,
          contextWindow = 100_000,
          agentLibrary = new AgentLibrary(tmp / "agents"),
          taskStore = FileTaskStore,
          historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
          fileLockManager = fileLocks,
          sessionModelOverrides = modelOverrides,
          providerRegistry = null,
          healthMonitor = ProviderHealthMonitor(null),
          actorSystem = system,
          subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
          voiceMutedRef = voiceMuted,
          freezeScheduleRef = schedRef
        )
        staleActivity = System.currentTimeMillis() - 20 * 60 * 1000L // 远超 10min 阈值
        // Frozen + 长时间无活动 → 豁免（零动作）
        _ <- resources.agentRegistry.update(_ + ("frozen-sid" -> AgentRecord(
          "frozen-sid", probeRef, AgentKind.Root, "frozen-sid", None,
          startedAt = staleActivity, status = AgentStatus.Frozen, lastActivityMs = staleActivity
        )))
        _ <- TaskStuckWatcher.scan(resources, wsHub, thresholdMs = 10 * 60 * 1000L)
        _ <- IO.sleep(300.millis)
        probeAfterFrozen <- probeEvents.get
        hubAfterFrozen <- hubEvents.get
        _ = assert(probeAfterFrozen.isEmpty, s"Frozen record must receive no Stop, got $probeAfterFrozen")
        _ = assert(hubAfterFrozen.isEmpty, s"Frozen record must not broadcast taskStuck, got $hubAfterFrozen")
        // 对照组：Processing + 同样陈旧的活动戳 → 根 agent 广播 taskStuck
        _ <- resources.agentRegistry.update(m =>
          m.updated("frozen-sid", m("frozen-sid").copy(status = AgentStatus.Processing))
        )
        _ <- TaskStuckWatcher.scan(resources, wsHub, thresholdMs = 10 * 60 * 1000L)
        _ <- waitUntil(System.currentTimeMillis() + 5000)(hubEvents.get.map(_.nonEmpty))
        hubAfterProcessing <- hubEvents.get
        _ = assert(
          hubAfterProcessing.exists(_.hcursor.downField("type").as[String].contains("taskStuck")),
          "Processing control must trigger taskStuck (positive control)"
        )
      yield ()
      program.unsafeRunSync()
    finally
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  // ── v2 冻结式错误恢复（20260824_frozen-error-recovery-plan §7 P0 后端验收）──

  /** 前 failCount 次 sendStream 抛 429（RateLimit → Transient），之后 TextDelta+Done。 */
  private class TransientFailNThenOkLlm(
    counter: cats.effect.Ref[IO, Int],
    failCount: Int
  ) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(counter.update(_ + 1)) >> Stream.eval(counter.get).flatMap { n =>
        if n <= failCount then
          Stream.raiseError[IO](new RuntimeException("HTTP 429 rate limit exceeded"))
        else Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))
      }

  /** 恒抛 Timeout（classifyError → Permanent）——不冻结直接 fatal 的对照 mock。 */
  private class PermanentFailLlm(counter: cats.effect.Ref[IO, Int]) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(counter.update(_ + 1)) >>
        Stream.raiseError[IO](new RuntimeException("connection timeout after 30s"))

  private def frozenEventOf(events: List[Json]): Option[Json] =
    events.find(_.hcursor.downField("type").as[String].contains("frozen"))

  test("ER-1: transient budget exhausted → ErrorFrozen (llm-transient, resumeAt set, zero further LLM calls)") {
    val system = ActorSystem("freeze-er1")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        schedRef <- IO.ref(openConfig)
        resources <- mkResources(system, tmp, TransientFailNThenOkLlm(counter, failCount = 99), schedRef)
        sid = "freeze-er1-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef(),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("er1")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
        _ <- ref ! AgentCommand.UserInput("trigger transient failure")
        // 失败1 → retry（5s backoff）→ 失败2（预算耗尽）→ ErrorFrozen
        _ <- waitUntil(System.currentTimeMillis() + 15000)(counter.get.map(_ >= 2))
        _ <- waitUntil(System.currentTimeMillis() + 15000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Frozen))
        )
        reg <- resources.agentRegistry.get.map(_.get(sid))
        _ = assertEquals(reg.flatMap(_.frozenReason), Some("llm-transient"), "frozenReason must be llm-transient")
        _ = assert(reg.exists(_.escalation.isEmpty), "first error-freeze must NOT enter escalation chain")
        evs <- events.get
        fz = frozenEventOf(evs)
        _ = assert(fz.isDefined, "must emit frozen WS event")
        _ = assert(
          fz.exists(_.hcursor.downField("reason").as[String].toOption.contains("llm-transient")),
          s"frozen event must carry reason=llm-transient, got ${fz.flatMap(_.hcursor.downField("reason").as[String].toOption)}"
        )
        _ = assert(
          fz.exists(_.hcursor.downField("resumeAt").as[Long].isRight),
          "frozen event must carry resumeAt for the backoff window"
        )
        countAtFreeze <- counter.get
        _ <- IO.sleep(800.millis)
        countAfter <- counter.get
        _ = assert(
          countAtFreeze == countAfter,
          s"ErrorFrozen must be zero-token: no LLM calls while frozen ($countAtFreeze vs $countAfter)"
        )
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  test("ER-2: backoff elapsed + CheckFreezeGate → resumed, dispatch continues from checkpoint") {
    val system = ActorSystem("freeze-er2")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        schedRef <- IO.ref(openConfig)
        resources <- mkResources(system, tmp, TransientFailNThenOkLlm(counter, failCount = 2), schedRef)
        sid = "freeze-er2-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef(),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("er2")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
        _ <- ref ! AgentCommand.UserInput("transient then ok")
        // 失败1(retry) → 失败2 → ErrorFrozen
        _ <- waitUntil(System.currentTimeMillis() + 15000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Frozen))
        )
        // 退避（~5s）到期后 CheckFreezeGate → resumed → 第 3 次调用成功 → turn 完成
        _ <- IO.sleep(6500.millis)
        _ <- ref ! AgentCommand.CheckFreezeGate
        _ <- waitUntil(System.currentTimeMillis() + 15000)(counter.get.map(_ >= 3))
        resumedSeen <- hasEvent(events, "resumed")
        _ = assert(resumedSeen, "must emit resumed WS event when backoff elapsed")
        _ <- waitUntil(System.currentTimeMillis() + 10000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Idle))
        )
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  test("ER-3: same-reason consecutive freezes ≥3 → escalate to parent, NOT fatal") {
    val system = ActorSystem("freeze-er3")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        parentEvents <- IO.ref(List.empty[AgentCommand])
        schedRef <- IO.ref(openConfig)
        resources <- mkResources(system, tmp, TransientFailNThenOkLlm(counter, failCount = 99), schedRef)
        // 父 probe：记录收到的所有命令（ExternalEvent 升级通知）
        parentRef <- system.spawn(
          {
            def loop: Behavior[AgentCommand] =
              Behaviors.receiveMessage[AgentCommand](cmd => parentEvents.update(_ :+ cmd).as(loop))
            loop
          },
          "freeze-er3-parent"
        )
        sid = "freeze-er3-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef("Backend", category = "team"),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 1,
            parentRef = Some(parentRef),
            sessionId = Some(sid),
            sessionName = Some("er3")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Team, sid, Some(parentRef))))
        _ <- ref ! AgentCommand.UserInput("keep failing")
        // 轮1：失败1(retry) → 失败2 → ErrorFrozen#1
        _ <- waitUntil(System.currentTimeMillis() + 15000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Frozen))
        )
        _ <- waitUntil(System.currentTimeMillis() + 15000)(counter.get.map(_ >= 2))
        // 轮2/轮3：退避到期 CheckFreezeGate → 续跑失败 → 再冻结（count 2→3→4）
        _ <- IO.sleep(6500.millis)
        _ <- ref ! AgentCommand.CheckFreezeGate
        _ <- waitUntil(System.currentTimeMillis() + 15000)(counter.get.map(_ >= 3))
        _ <- IO.sleep(6500.millis)
        _ <- ref ! AgentCommand.CheckFreezeGate
        // 轮3 冻结完成 → 升级链：父收到 error-escalated，agent 仍 Frozen（非 fatal）
        // 注意：waitUntil Frozen 在 CheckFreezeGate 前已满足（本就 Frozen），必须等
        // escalation 落盘（enterFrozen 的 updateRegistryEscalation 在 touch 之后）
        _ <- waitUntil(System.currentTimeMillis() + 15000)(counter.get.map(_ >= 4))
        _ <- waitUntil(System.currentTimeMillis() + 15000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.escalation.isDefined))
        )
        reg <- resources.agentRegistry.get.map(_.get(sid))
        _ = assert(reg.exists(_.escalation.isDefined), "3rd consecutive freeze must set escalation info")
        _ = assert(
          reg.flatMap(_.escalation).exists(_.level == 1),
          "escalation level must start at 1"
        )
        _ = assert(
          reg.exists(r => r.status == AgentStatus.Frozen),
          "3rd consecutive freeze must still be Frozen (not fatal)"
        )
        _ <- waitUntil(System.currentTimeMillis() + 5000)(
          parentEvents.get.map(_.exists {
            case AgentCommand.ExternalEvent(_, eventType, _, _, _) => eventType == "error-escalated"
            case _ => false
          })
        )
        parentGot <- parentEvents.get.map(_.collectFirst {
          case AgentCommand.ExternalEvent(_, eventType, _, metadata, _) if eventType == "error-escalated" =>
            metadata("reason").flatMap(_.as[String].toOption).getOrElse("")
        })
        _ = assertEquals(parentGot, Some("llm-transient"), "escalation event must carry the freeze reason")
        statusAfter <- resources.agentRegistry.get.map(_.get(sid).map(_.status))
        _ = assert(
          statusAfter.contains(AgentStatus.Frozen),
          s"3rd consecutive freeze must NOT be fatal — still Frozen, got $statusAfter"
        )
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  test("ER-4: Permanent failure does NOT freeze — fatal path (error event, no frozen event)") {
    val system = ActorSystem("freeze-er4")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        schedRef <- IO.ref(openConfig)
        resources <- mkResources(system, tmp, PermanentFailLlm(counter), schedRef)
        sid = "freeze-er4-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef(),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("er4")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
        _ <- ref ! AgentCommand.UserInput("permanent failure")
        _ <- IO.sleep(800.millis)
        evs <- events.get
        _ = assert(!frozenEventOf(evs).isDefined, "Permanent failure must NOT emit frozen")
        _ = assert(
          evs.exists(_.hcursor.downField("type").as[String].contains("error")),
          "Permanent failure must emit error (fatal path)"
        )
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  test("ER-5: Schedule freeze regression — reason defaults to 'schedule', behavior unchanged") {
    val system = ActorSystem("freeze-er5")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        schedRef <- IO.ref(frozenConfig())
        resources <- mkResources(system, tmp, CountingLlm(counter, requests), schedRef)
        sid = "freeze-er5-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef(),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("er5")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
        _ <- ref ! AgentCommand.UserInput("schedule freeze")
        _ <- waitUntil(System.currentTimeMillis() + 5000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Frozen))
        )
        evs <- events.get
        _ = assertEquals(
          frozenEventOf(evs).flatMap(_.hcursor.downField("reason").as[String].toOption),
          Some("schedule"),
          "time-schedule freeze must carry reason=schedule (default, backward compatible)"
        )
        _ = assert(counter.get.unsafeRunSync() == 0, "schedule freeze must keep zero LLM calls")
        // 段外 CheckFreezeGate 恢复（现状语义回归）
        _ <- schedRef.set(openConfig)
        _ <- ref ! AgentCommand.CheckFreezeGate
        _ <- waitUntil(System.currentTimeMillis() + 8000)(counter.get.map(_ >= 1))
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  test("ER-6: escalation target pure function — parent alive → Parent, parent missing → skip, no parent → User") {
    assertEquals(Escalation.nextTarget(1, hasParent = true, parentAlive = true), Escalation.Target.Parent)
    assertEquals(Escalation.nextTarget(1, hasParent = true, parentAlive = false), Escalation.Target.Grandparent)
    assertEquals(Escalation.nextTarget(1, hasParent = false, parentAlive = false), Escalation.Target.User)
    assertEquals(Escalation.nextTarget(3, hasParent = false, parentAlive = true), Escalation.Target.User)
  }

end FreezeGateSpec
