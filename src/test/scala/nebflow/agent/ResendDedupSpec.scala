package nebflow.agent

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{AgentCommand, AgentDef, AgentKind, AgentRecord, AgentStatus}
import nebflow.actor.{ActorSystem}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.core.schedule.FreezeScheduleConfig
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.compact.HistoryArchiver
import nebflow.core.FileChangeTracker
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{
  FallbackAttempt,
  LlmHandle,
  LlmRequest,
  LlmResponse,
  PathUtil,
  StreamChunk,
  ThinkingConfig
}

import scala.concurrent.duration.*

/**
 * resend-dedup 批（修复批 #569 · 客户端断线重连自动重发消息/重复气泡）服务端行为钉：
 * 客户端幂等键 clientMessageId 的去重判据在**三个** intake 分支都必须生效，且
 * 丢弃必须回 `messageDuplicate` 回执（客户端凭回执收掉同 id 重发乐观置位的幻影
 * busy —— 无回执则看门走满阶梯，凭空呈现「仍在处理」）。
 *
 *   - RD1 idle 直投：已受理的 id 再到 ⇒ 丢弃 + 回执 + 零第二次 LLM 调用
 *     （既有 checkDuplicate 行为的回执增量钉）。
 *   - RD2 busy 排队（本批新增判据）：processing 态收 UserInput，重复 id **不入
 *     pendingUserInputs** ⇒ turn 边界 drain 只派发一次；🔴 查而不记 —— 入队不注册
 *     id（注册点 = idle 直投），否则 head-to-self 转发会被自己的注册误判成重复，
 *     排队消息永远不派发（本 spec 的 RD2 兼任该反向回归钉）。
 *   - RD3 frozen 唤醒：冻结态同 id 重复唤醒 ⇒ 丢弃 + 回执（既有 B5 钉的回执增量）。
 *
 * harness 仿 FreezeGateSpec（真实 AgentActor + 计数 mock LLM + wsSend 帧捕获）。
 */
class ResendDedupSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

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

  /**
   * 工具轮 mock（RD2 专用）：首请求挂起在 `holdGate` 上（把 actor 钉在 Processing
   * 态，让「busy 中排队」窗口可控），释放后纯文本收尾；后续请求纯文本。
   */
  private class HeldFirstTurnLlm(
    counter: cats.effect.Ref[IO, Int],
    requests: cats.effect.Ref[IO, List[LlmRequest]],
    holdGate: cats.effect.Deferred[IO, Unit]
  ) extends LlmHandle[IO]:

    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(counter.update(_ + 1) *> requests.update(_ :+ req)).evalMap(_ => counter.get).flatMap {
        case 1 => Stream.eval(holdGate.get).drain ++ Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))
        case _ => Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))
      }

  end HeldFirstTurnLlm

  private def mkResources(
    system: ActorSystem,
    tmp: os.Path,
    llm: LlmHandle[IO],
    scheduleRef: cats.effect.Ref[IO, FreezeScheduleConfig] = IO
      .ref(FreezeScheduleConfig(enabled = false))
      .unsafeRunSync()
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
      freezeScheduleRef = scheduleRef,
      freezeSkipUntilRef = IO.ref(None).unsafeRunSync()
    )

  private def rootDef(name: String = "Nebula", category: String = ""): AgentDef =
    AgentDef(name = name, description = "test", tools = List("Read"), systemPrompt = "", category = category)

  private def waitUntil(deadlineMs: Long)(cond: IO[Boolean]): IO[Unit] =
    cond.flatMap {
      case true => IO.unit
      case false =>
        if System.currentTimeMillis() > deadlineMs then IO.raiseError(new RuntimeException("waitUntil timeout"))
        else IO.sleep(100.millis) *> waitUntil(deadlineMs)(cond)
    }

  private def receiptsFor(
    events: cats.effect.Ref[IO, List[Json]],
    clientMessageId: String
  ): IO[List[Json]] =
    events.get.map(_.filter(j =>
      j.hcursor.downField("type").as[String].contains("messageDuplicate") &&
        j.hcursor.downField("clientMessageId").as[String].contains(clientMessageId)
    ))

  /** 该请求**最后一条实义 user 消息**是否为 text —— 按「本轮投递的是哪条」计数，
   *  不按上下文包含（后继轮的 context 含历史文本，包含判定会把续轮错算进来）。
   *  跳过 reminders 注入的 `<system-reminder>…` User 行（时间注入会排在实义消息
   *  之后 —— 实测把「最后一条 user」位占掉）。 */
  private def lastUserIs(req: LlmRequest, text: String): Boolean =
    req.messages
      .filter(_.role == nebflow.shared.MessageRole.User)
      .filterNot(_.textContent.startsWith("<system-reminder>"))
      .lastOption
      .exists(_.textContent.contains(text))

  // ── RD1: idle 直投重复 ⇒ 丢弃 + 回执 + 零第二次调用 ─────────────────────

  test("RD1: idle re-send of an accepted clientMessageId is dropped with a messageDuplicate receipt") {
    val system = ActorSystem("resend-dedup-rd1")
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
        resources <- mkResources(system, tmp, CountingLlm(counter, requests))
        sid = "resend-dedup-rd1-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef(),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("rd1")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
        // 第一次派发：id 进入 recentMessageIds，turn 正常跑
        _ <- ref ! AgentCommand.UserInput("first message", None, Some("dup-1"))
        _ <- waitUntil(System.currentTimeMillis() + 30000)(counter.get.map(_ >= 1))
        _ <- waitUntil(System.currentTimeMillis() + 30000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Idle))
        )
        // 同 id 重发（断线重连补发形态）⇒ 必须丢弃 + 回执
        _ <- ref ! AgentCommand.UserInput("first message", None, Some("dup-1"))
        _ <- IO.sleep(700.millis)
        count <- counter.get
        _ = assert(count == 1, s"re-send of an accepted id must NOT dispatch a second turn, got $count calls")
        receipts <- receiptsFor(events, "dup-1")
        _ = assert(receipts.nonEmpty, "must emit a messageDuplicate receipt carrying the id")
        _ = assert(
          receipts.forall(_.hcursor.downField("sessionId").as[String].contains(sid)),
          "receipt must carry the sessionId routing key"
        )
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
    end try
  }

  // ── RD2: busy 排队去重（本批新增判据，两层）────────────────────────────
  //  层1：**已受理** id（本 turn 的 q-1）在 busy 中重到 ⇒ 排队闸直接丢弃（查而不记）。
  //  层2：新 id 连到两次 ⇒ 两份都入队（入队不注册 —— 🔴 查而不记的反向前提：注册点
  //        = idle 直投；若入队即注册，head-to-self 转发的队头会被自己的注册误判成
  //        重复 ⇒ 排队消息永不派发），drain 后**恰派发一次**（第二份在下一边界被
  //        idle 去重收掉）。

  test("RD2: busy-queue gate drops a duplicate of the in-flight turn's id; a double-queued fresh id drains exactly once") {
    val system = ActorSystem("resend-dedup-rd2")
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
        holdGate <- cats.effect.Deferred[IO, Unit]
        resources <- mkResources(system, tmp, HeldFirstTurnLlm(counter, requests, holdGate))
        sid = "resend-dedup-rd2-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef(),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("rd2")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
        // 第一 turn 挂起（actor 钉在 Processing；q-1 已注册 = 已受理）
        _ <- ref ! AgentCommand.UserInput("hold turn", None, Some("q-1"))
        _ <- waitUntil(System.currentTimeMillis() + 30000)(counter.get.map(_ >= 1))
        _ <- waitUntil(System.currentTimeMillis() + 30000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Processing))
        )
        // 层1：已受理 id 在 busy 中重到 ⇒ 排队闸丢弃 + 回执
        _ <- ref ! AgentCommand.UserInput("hold turn", None, Some("q-1"))
        _ <- IO.sleep(300.millis)
        r1 <- receiptsFor(events, "q-1")
        _ = assert(r1.nonEmpty, "duplicate of an in-flight turn's accepted id must emit a receipt at the queue gate")
        // 层2：新 id 连到两次 ⇒ 两份都入队（head drain 后恰派发一次）
        _ <- ref ! AgentCommand.UserInput("queued msg", None, Some("q-2"))
        _ <- IO.sleep(200.millis)
        _ <- ref ! AgentCommand.UserInput("queued msg", None, Some("q-2"))
        _ <- IO.sleep(200.millis)
        // 释放挂起轮 → turn 边界 drain 队头 → idle 直投 "queued msg"（q-2 注册点）
        _ <- holdGate.complete(()).attempt.void
        _ <- waitUntil(System.currentTimeMillis() + 30000)(counter.get.map(_ >= 2))
        _ <- waitUntil(System.currentTimeMillis() + 30000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Idle))
        )
        _ <- IO.sleep(800.millis) // 第二份 q-2 在下一边界的 idle 去重窗口
        count <- counter.get
        // 🔴 查而不记的反向钉：若入队时注册了 q-2，head-to-self 转发到 idle 会被
        // 自己的注册误判成重复 ⇒ "queued msg" 永不派发（count 停在 1）。
        _ = assert(count == 2, s"queued head must drain into its own turn (got $count calls, want 2)")
        reqs <- requests.get
        queuedTurns = reqs.count(lastUserIs(_, "queued msg"))
        _ = assert(queuedTurns == 1, s"double-queued fresh id must dispatch exactly once, got $queuedTurns; lastUsers=" +
          reqs.map(r => r.messages.filter(_.role == nebflow.shared.MessageRole.User)
            .filterNot(_.textContent.startsWith("<system-reminder>"))
            .lastOption.map(m => m.textContent.take(50)).getOrElse("-")).mkString(" | "))
        heldTurns = reqs.count(lastUserIs(_, "hold turn"))
        _ = assert(heldTurns == 1, s"in-flight turn must complete exactly once, got $heldTurns")
        r2 <- receiptsFor(events, "q-2")
        _ = assert(r2.nonEmpty, "the second copy must be dropped (at the idle gate) with a receipt")
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
    end try
  }

  // ── RD3: frozen 唤醒重复 ⇒ 丢弃 + 回执 ─────────────────────────────────

  test("RD3: duplicate wake clientMessageId while frozen is dropped with a messageDuplicate receipt") {
    val system = ActorSystem("resend-dedup-rd3")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")

    // 恒覆盖当前时刻的合法冻结段（同 FreezeGateSpec：start=now-30min、end=now+30min，
    // 跨午夜安全）。
    def frozenConfig(): FreezeScheduleConfig =
      val now = java.time.LocalTime.now()
      val minuteOfDay = now.getHour * 60 + now.getMinute
      val s = (minuteOfDay - 30 + 1440) % 1440
      val e = (minuteOfDay + 30) % 1440
      def fmt(m: Int): String = f"${m / 60}%02d:${m % 60}%02d"
      FreezeScheduleConfig(enabled = true, segments = List(nebflow.core.schedule.FreezeSegment(fmt(s), fmt(e))))

    try
      val program = for
        counter <- IO.ref(0)
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        schedRef <- IO.ref(frozenConfig())
        resources <- mkResources(system, tmp, CountingLlm(counter, requests), schedRef)
        sid = "resend-dedup-rd3-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef(),
            resources = resources,
            wsSend = json => events.update(_ :+ json),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("rd3")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))
        // 系统输入（无 id）→ Gated → 冻结
        _ <- ref ! AgentCommand.UserInput("initial task")
        _ <- waitUntil(System.currentTimeMillis() + 30000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Frozen))
        )
        // 用户唤醒（带 id）→ turn 完成 → 回 idle（id 已注册）
        _ <- ref ! AgentCommand.UserInput("wake once", None, Some("wake-1"))
        _ <- waitUntil(System.currentTimeMillis() + 30000)(counter.get.map(_ >= 1))
        _ <- waitUntil(System.currentTimeMillis() + 30000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Idle))
        )
        // 再次冻结后同 id 唤醒 ⇒ 丢弃 + 回执（不开第二个 turn）
        _ <- ref ! AgentCommand.UserInput("freeze again")
        _ <- waitUntil(System.currentTimeMillis() + 30000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Frozen))
        )
        _ <- ref ! AgentCommand.UserInput("wake once", None, Some("wake-1"))
        _ <- IO.sleep(700.millis)
        count <- counter.get
        _ = assert(count == 1, s"duplicate wake must NOT dispatch a second turn, got $count calls")
        receipts <- receiptsFor(events, "wake-1")
        _ = assert(receipts.nonEmpty, "frozen duplicate wake must emit a messageDuplicate receipt")
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
    end try
  }

end ResendDedupSpec
