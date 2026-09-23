package nebflow.agent

import cats.effect.std.{Dispatcher, Semaphore}
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * 通知打包「解 b」批 · 红验面（notifypack-b · 实施位 r3，2026-09-23）。
 *
 * 主题 = **「气泡逐件」与「唤醒 N→1」同时成立**（作者裁定 A：改消费侧，真达 N→1）。
 * 载体 = `ImmediateInput.windowItems`（生产侧唯一写入点 = `NodeEngine.flushRootNotify`
 * 的 `case many`）；消费侧唯一读取点 = `TurnBoundaryDrains.expandWindowFlush`。
 *
 * 全部读数为**真投递**（真 `AgentActor` + 真队列 + 真持久化快照 + 真 WS 帧），**禁 mock**：
 * 唯一被替换的是 `LlmHandle`（进程内 LLM 是外部网络面）——它同时充当**唤醒计数器**与
 * **闸门**（`Semaphore` 每放行一个许可才结束一个 turn ⇒ 可在 turn 中途观测队列）。
 *
 * 四条红验（对应任务书 §8）：
 *  - **R1 队列层（载体占 1 槽）**：窗冲刷载体在队列里恒 **1 个元素**（载荷 N 件不改变
 *    元素数）⇒ 逐条语义的队列算术未被豁免动过；
 *  - **R2 展开（气泡逐件）**：同一 turn 内注入 N 条上下文消息 + N 个注入帧（逐件
 *    `sender`/`eventType` 各自保留 ⇒ 前端零改动下 N 气泡）；
 *  - **R3 唤醒 N→1**：N 件载荷只开**一个** turn（一次 `sendStream`），不是 N 次；
 *  - **R4 负对照（最关键）**：**无标记的用户消息腿仍逐条逐 turn**（每件各开一个 turn），
 *    且不产注入气泡（`fromUser=true` 既有语义）⇒ 例外**被限定在窗冲刷件**，未外溢。
 *
 * 变异复红（证据目录 `20-mutation-*.txt`）：把 `expandWindowFlush` 退回恒等 ⇒
 * 本 spec 的 R2 转红（1 个气泡含全部分节、无逐件消息）⇒ 证明该限定是**承力件**。
 * 变异在实现位自己的 worktree 内造、跑完即精确还原（文件 sha256 前后相等）。
 */
class RootNotifyWindowExpansionSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 90.seconds

  /** LLM stub = 唤醒计数器 + 闸门。每次 `sendStream` 先登记请求，再等一个许可 ⇒
    * 测试可在「turn 已发起但未结束」的窗口内观测队列与帧。 */
  private class GatedLlm(
    requests: Ref[IO, List[LlmRequest]],
    gate: Semaphore[IO]
  ) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(_ :+ req) *> gate.acquire) >>
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
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
      voiceMutedRef = voiceMuted
    )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 25.millis)(
    label: String
  )(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"waitUntil($label): condition not met within $timeout"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  /** 有界稳定轮询：等读数连续两次相等且 ≥1（不依赖帧的发射顺序，也不把变异体拖进超时）。 */
  private def stabilizeCounts(read: IO[Int], timeout: FiniteDuration): IO[Unit] =
    def go(deadline: Long, last: Option[Int]): IO[Unit] =
      read.flatMap { n =>
        if n >= 1 && last.contains(n) then IO.unit
        else if System.currentTimeMillis() >= deadline then IO.pure(()) // 交由后续精确断言判红
        else IO.sleep(150.millis) >> go(deadline, Some(n))
      }
    go(System.currentTimeMillis() + timeout.toMillis, None)

  /** 注入帧（`{type:"user", injected:true, ...}`）——一帧 = 一个前端气泡。 */
  private def injectedFrames(frames: List[Json]): List[Json] =
    frames.filter { j =>
      val hc = j.hcursor
      hc.downField("type").as[String].toOption.contains("user") &&
      hc.downField("injected").as[Boolean].toOption.contains(true)
    }

  private def field(j: Json, k: String): Option[String] =
    j.hcursor.downField(k).as[String].toOption

  /** 载荷（= 队列快照里的窗冲刷件）。 */
  private def carrierPayload(items: List[(String, String, String, String)]): AgentCommand.ImmediateInput =
    AgentCommand.ImmediateInput(
      text = "[Node 本批 2 件终态通知（root 通道打包窗合并，项目 rootnotifyproj）]\n── [1/2] [completed] node-1 (n-1) ──\nBODY_ONE\n\n── [2/2] [failed] node-2 (n-2) ──\nBODY_TWO",
      source = Some("node"),
      eventType = Some("failed"),
      sender = Some("rootnotifyproj/node-1"),
      fromUser = false,
      windowItems = Some(items.map((t, n, s, sd) => AgentCommand.WindowItem(t, n, s, sd)))
    )

  test("R1–R4: the tagged window carrier injects N messages + N bubbles in ONE turn, while untagged items stay item-by-item") {
    val system = ActorSystem("notifypack-expand")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    val sid = "notifypack-expand-root"
    try
      val program = for
        requests <- Ref.of[IO, List[LlmRequest]](Nil)
        frames <- Ref.of[IO, List[Json]](Nil)
        gate <- Semaphore[IO](0)
        resources <- mkResources(system, tmp, new GatedLlm(requests, gate))
        nebulaDef = AgentDef(name = "Nebula", description = "window expansion root", tools = List("Read"), systemPrompt = "")
        ref <- system.spawn(
          AgentActor(
            agentDef = nebulaDef,
            resources = resources,
            wsSend = j => frames.update(_ :+ j),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("notifypack-expand")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Root, sid, None)))

        // phase 1：真人 kickoff 开 turn 1（LLM 停在闸门后）
        _ <- ref ! AgentCommand.ImmediateInput("kickoff", fromUser = true)
        _ <- waitUntil(20.seconds, 25.millis)("turn-1 started")(requests.get.map(_.size >= 1))

        // phase 2：turn 进行中入队 —— ① 窗冲刷载体（载荷 2 件）② 无标记用户消息
        carrier = carrierPayload(
          List(
            ("BODY_ONE", "node-1", "completed", "rootnotifyproj/node-1"),
            ("BODY_TWO", "node-2", "failed", "rootnotifyproj/node-2")
          )
        )
        plain = AgentCommand.ImmediateInput("plain-user-text", source = None, fromUser = true)
        _ <- ref ! carrier
        _ <- ref ! plain
        // R1：**队列层读数**（真持久化快照，非内存探测）——载体占 **1 槽**，载荷 2 件
        _ <- waitUntil(20.seconds, 25.millis)("queue holds 2 elements")(
          CompactionQueueStore.load(sid).map(_.exists(_.imms.size == 2))
        )
        queued <- CompactionQueueStore.load(sid).map(_.map(_.imms).getOrElse(Nil))
        queueSizes = queued.map(_.windowItems.map(_.size))
        // 载荷序（保序）在入队面读一次（与展开面读一次对读）
        payloadOrder = queued.flatMap(_.windowItems).flatten.map(_.text)
        payloadSenders = queued.flatMap(_.windowItems).flatten.map(_.sender)

        // phase 3：放行 turn 1 ⇒ 边界只消费队首载体 ⇒ 展开为 N 件、同一 turn 注入
        _ <- gate.release
        _ <- waitUntil(30.seconds, 25.millis)("carrier turn started")(requests.get.map(_.size >= 2))
        // 帧在 forkTurn 内异步发射（单帧顺序非契约）⇒ 先用**有界轮询**等帧计数稳定
        // （连续两次读数相等且 ≥1 即判稳定），静置后再以精确断言判件数。轮询而非
        // 固定等待，使「展开恒等」的变异体落到**断言**上而不是超时上。
        _ <- stabilizeCounts(frames.get.map(f => injectedFrames(f).size), 20.seconds)
        _ <- IO.sleep(700.millis)
        afterCarrier <- requests.get
        framesAfterCarrier <- frames.get
        queueAfterCarrier <- CompactionQueueStore.load(sid).map(_.map(_.imms).getOrElse(Nil))

        // phase 4：放行载体 turn ⇒ 边界消费无标记的普通件（仍逐条、独立 turn）
        _ <- gate.release
        _ <- waitUntil(30.seconds, 25.millis)("plain turn started")(requests.get.map(_.size >= 3))
        _ <- waitUntil(30.seconds, 25.millis)("plain turn drained the queue")(
          CompactionQueueStore.load(sid).map(_.forall(_.imms.isEmpty))
        )
        afterPlain <- requests.get
        framesAfterPlain <- frames.get
        queueAfterPlain <- CompactionQueueStore.load(sid).map(_.map(_.imms).getOrElse(Nil))
      yield
        (queued, queueSizes, payloadOrder, payloadSenders, afterCarrier, framesAfterCarrier,
          queueAfterCarrier, afterPlain, framesAfterPlain, queueAfterPlain)

      val (queued, queueSizes, payloadOrder, payloadSenders, afterCarrier, framesAfterCarrier,
        queueAfterCarrier, afterPlain, framesAfterPlain, queueAfterPlain) = program.unsafeRunSync()

      // ── R1 · 队列层：载体占 1 个元素（载荷 2 件）──────────────────────────────
      assertEquals(queued.size, 2, s"queue must hold exactly TWO elements (carrier + plain): $queued")
      assertEquals(queueSizes, List(Some(2), None), "the carrier is ONE element carrying 2 payload items")
      assertEquals(payloadOrder, List("BODY_ONE", "BODY_TWO"), "payload order == arrival order")
      assertEquals(
        payloadSenders,
        List("rootnotifyproj/node-1", "rootnotifyproj/node-2"),
        "per-item sender is built at the WRITE POINT (project prefix intact)"
      )

      // ── R2 · 展开面：同一 turn 内 N 条上下文消息 + N 个注入帧 ──────────────────
      val carrierReq = afterCarrier(1)
      val carrierUserTexts = carrierReq.messages.filter(_.role == nebflow.shared.MessageRole.User).map(_.textContent)
      assert(carrierUserTexts.contains("BODY_ONE"), s"item 1 body must be its OWN context message:\n$carrierUserTexts")
      assert(carrierUserTexts.contains("BODY_TWO"), s"item 2 body must be its OWN context message:\n$carrierUserTexts")
      // 展开序（保序）由展开面单点断言——帧序是 forkTurn 异步发射，非契约面
      val expandedCarrier = TurnBoundaryDrains.expandWindowFlush(queued.head)
      assertEquals(
        expandedCarrier.map(_.text),
        List("BODY_ONE", "BODY_TWO"),
        "expansion order == payload arrival order (per-adapter序)"
      )
      val carrierFrames = injectedFrames(framesAfterCarrier)
      assertEquals(carrierFrames.size, 2, s"one frame = one bubble ⇒ 2 frames (per-item bubbles): $carrierFrames")
      assertEquals(
        carrierFrames.flatMap(f => field(f, "sender")).sorted,
        List("rootnotifyproj/node-1", "rootnotifyproj/node-2"),
        "each bubble carries its own identity (header SUBJECT differs per item)"
      )
      assertEquals(
        carrierFrames.flatMap(f => field(f, "eventType")).sorted,
        List("completed", "failed"),
        "each bubble carries its own state (not flattened to the batch's strongest status)"
      )
      assertEquals(
        carrierFrames.flatMap(f => field(f, "text")).sorted,
        List("BODY_ONE", "BODY_TWO"),
        "each bubble's body is the per-item text (NOT the merged digest)"
      )
      // 🔴 变异判别断言（永久保留）：气泡正文**决不能**是合并摘要本体——即
      // 「不展开」形态（1 气泡含全部分节）必须被此断言抓住，而不是靠超时。
      assert(
        !carrierFrames.exists(f => field(f, "text").exists(_.contains("── [1/2]"))),
        s"NEVER one merged-digest bubble (the unexpanded form is exactly what this batch removes): $carrierFrames"
      )
      assert(
        !carrierFrames.exists(f =>
          field(f, "text").exists(t => t.contains("BODY_ONE") && t.contains("BODY_TWO"))
        ),
        s"no bubble may carry BOTH items (that is route ② / the unexpanded regression): $carrierFrames"
      )
      // 身份↔正文的逐件配对（防「N 帧但串件」）：sender 的 node-N 必须配 BODY_ 同号
      val identityBodyPairs: List[(String, String)] =
        carrierFrames
          .map(f => (field(f, "sender").getOrElse("<none>"), field(f, "text").getOrElse("<none>")))
          .sorted
      assertEquals(
        identityBodyPairs,
        List(("rootnotifyproj/node-1", "BODY_ONE"), ("rootnotifyproj/node-2", "BODY_TWO")),
        "per-item pairing: each bubble's identity and body come from the SAME payload item"
      )

      // ── R3 · 唤醒 N→1：2 件载荷只开一个 turn（一次 sendStream）───────────────
      assertEquals(
        afterCarrier.size,
        2,
        s"2 payload items ⇒ ONE extra wake (kickoff + carrier turn), NOT per-item turns: ${afterCarrier.size}"
      )

      // ── R4 · 负对照（无标记件仍逐条）＋ 载体已出队、普通件留队 ────────────────
      assertEquals(queueAfterCarrier.map(_.windowItems), List(None), "only the untagged item stays queued after the carrier drain")
      assertEquals(afterPlain.size, 3, "the untagged item opens its OWN turn (per-item semantics unchanged)")
      val plainTurnUserTexts = afterPlain(2).messages
        .filter(_.role == nebflow.shared.MessageRole.User)
        .map(_.textContent)
      assert(
        plainTurnUserTexts.exists(_.contains("plain-user-text")),
        s"that turn carries the plain user text itself:\n$plainTurnUserTexts"
      )
      // 无标记件**不**展开 ⇒ 该 turn 只新增一件（plain 自身），载体载荷**不得被重复注入**。
      // 判据 = 出现次数（请求携带累计历史，故载体正文「在历史里」是正常的；异常形态是
      // 「又被注入一次」⇒ 计数 >1）。这条同时覆盖「载体载荷漏进普通件展开」的失败形态。
      val timesInjected = (needle: String) => plainTurnUserTexts.count(_.contains(needle))
      assertEquals(
        List("BODY_ONE", "BODY_TWO", "plain-user-text").map(timesInjected),
        List(1, 1, 1),
        s"each item must appear exactly ONCE in the plain turn's request (no re-injection):\n$plainTurnUserTexts"
      )
      assertEquals(
        injectedFrames(framesAfterPlain).size,
        2,
        "the plain leg emits NO injected bubble (fromUser=true semantics unchanged) ⇒ still exactly the carrier's 2"
      )
      assertEquals(queueAfterPlain.size, 0, "queue drained item-by-item to empty")

      // 展开读数的**逆运算**证据（幂等）：展开出的虚拟件不得再带窗标记
      val expanded = TurnBoundaryDrains.expandWindowFlush(carrierPayload(
        List(("BODY_ONE", "node-1", "completed", "rootnotifyproj/node-1"))
      ))
      assert(expanded.forall(_.windowItems.isEmpty), "expanded items must not carry the tag again (idempotence)")
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }
end RootNotifyWindowExpansionSpec
