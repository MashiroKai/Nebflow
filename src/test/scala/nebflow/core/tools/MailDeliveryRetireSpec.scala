package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import io.circe.{Json, JsonObject}
import munit.FunSuite
import nebflow.actor.ActorSystem
import nebflow.agent.*
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.flow.{MailQueueStore, TeamSessionRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}
import fs2.Stream
import scala.concurrent.duration.*

/**
 * delivery 退役批（2026-09-15 作者裁定 (b)）· **mailparams 批（2026-09-17 作者裁定 = 案 C
 * 「清死面」）re-pin** · 落地断言。
 *
 * 两条裁定合成后的**唯一**语义面（本文件钉的就是它）：
 *   1. **schema 面（mailparams 批改口）**：`delivery` 键**已退役出 schema** —— 参数面
 *      **8 → 7**，键集合恰为 `address` / `device` / `message` / `type` / `chainId` /
 *      `images` / `attachments`；`required` 仍是 `message`。其余 7 参**一字不动**
 *      （`images` / `attachments` / `device` = 当日刚落地的**活能力**，禁动）。
 *   2. **墓碑面（本批核心）**：键不在 schema，但 `call()` **保留一行墓碑读取**
 *      （`deliveryTombstone`）—— 一切**非设备腿**收到 `"queue"` 仍**统一显式拒绝**
 *      （`MAIL_DELIVERY_QUEUE_RETIRED`，零投递副作用、零队列落盘）。**禁静默立即化**
 *      （调用方声明的串行链语义无法被立即投递满足 ⇒ 静默改投 = 静默丢语义）；
 *      拒绝闸位置 = `call` 里**设备腿分支之后**、`layeredRoute` 之前 —— 单点兜住
 *      `node:` / `project:` / Nebula / team 短名 / 裸项目名**全部非设备腿**。
 *      该面**成立的前提** = 引擎零 JSON-Schema 校验（面外参数静默忽略，`protocol.scala`
 *      自陈）⇒ 旧键照样到达 `call()`，故「删 schema 键 + 墓碑判」是 fail-closed 一侧，
 *      而「删键 + 删判」才是静默降级。
 *   3. **设备腿零改动**：`deliverToDevice` 的 v2.1「显式拒 queue」契约**逐字保持**
 *      （自有字面量，**不**走退役文案；本条以**逐字相等**断言钉死）。
 *   4. **残差面（本批如实登记）**：非 `queue` 的旧值（`"immediate"` / 陌生值如 `"ask"`）
 *      与「键缺席」**逐字同一结果** —— 该键被静默忽略，且原「陌生值 ⇒ WARN 后收敛到
 *      immediate」分支随 `delivery match` 一并删除（设计件 §4.4(a)「其余值不再需要分支」）。
 *      本文件以**三值逐字相等**钉住该读数（原始读数见交付报告与
 *      `.nebflow/evidence/20260917_mailparams/impl/`）。
 *
 * 双向钉（改前 / 改后）：
 *   · **改前红侧** = mailparams 批落地**前**的树跑本文件 ⇒ 红（schema 里 `delivery` 键仍在、
 *     参数集合是 8 件 —— 本体 schema 面的两条断言当场红）；
 *   · **变异红侧** = 改后树把**墓碑读回删净**（等价变异：判据永假 ⇒ queue 静默走 immediate）
 *     ⇒ 本体 ② 面（五腿统一拒绝 / 零副作用 e2e）**必红**。
 * 两轮原始输出与红行逐字存证于 `.nebflow/evidence/20260917_mailparams/impl/`。
 *
 * 观测窗更正（本批，逐字登记）：端到端用例原先以**固定** `IO.sleep(500.millis)` 观测
 * 「immediate 是否到达活目标」。在本宿主当前负载下（swap 85–93% + 3–5 条他批构建在飞）
 * 实测该延迟 > 500ms ⇒ 固定窗偶发不足（两轮红读数：`run1-mail-surface.log` /
 * `run2-isolation.log`）。已改用本仓既有 idiom（私有 `waitUntil` 有界等待 + 实测延迟
 * println）；**断言实质不变**（immediate 仍必须到达，只是窗口有界且延迟被打印）。
 */
class MailDeliveryRetireSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  // ============================================================
  // 工具面小工具
  // ============================================================

  private def qJson(fields: (String, String)*): JsonObject =
    JsonObject.fromIterable(fields.map((k, v) => k -> Json.fromString(v)))

  private def ctx(
    dispatcher: Boolean = false,
    nebulaRoot: Boolean = false
  ): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some("sid-evfmtb"),
      isDispatcher = dispatcher,
      projectName = None,
      agentDef = if nebulaRoot then Some(AgentDef(name = "Nebula", description = "spec fixture")) else None
    )

  private def isRetired(res: Either[ToolError, String]): Boolean =
    res.left.exists(_.message.contains(MailTool.ErrDeliveryQueueRetired))

  /** 断言点收在「拒绝文案」上：成功即失败（红侧证据 = 这里的 got 原文）。 */
  private def rejectedMsg(res: Either[ToolError, String], label: String): String =
    res match
      case Left(err) => err.message
      case Right(v) => fail(s"$label: delivery=queue must be rejected, got: $v")

  /** 残差读数的渲染器（`Either` 逐字可见，供 println 行） */
  private def render(res: Either[ToolError, String]): String =
    res match
      case Left(err) => s"Left(${err.message})"
      case Right(msg) => s"Right($msg)"

  // ============================================================
  // 1. schema 面（mailparams 批 re-pin）：键已退役，参数面 8 → 7
  // ============================================================

  private def propsOf: JsonObject =
    MailTool.inputSchema("properties").flatMap(_.asObject).getOrElse(fail("Mail schema has no properties"))

  test("schema（mailparams 批 re-pin）：`delivery` 键已退役出 schema —— 参数集合恰为 7 件且不含该键"):
    // 机械锚 ①：该键**零命中** + 集合**精确等值**（任何与本批无关的增 / 删 / 改名一律红）。
    assert(
      !propsOf.keys.toSet.contains("delivery"),
      s"the `delivery` key must be GONE from the schema (2026-09-17 mailparams 批：参数面 8 → 7), got: ${propsOf.keys.toList.sorted}"
    )
    assertEquals(
      propsOf.keys.toSet,
      Set("address", "device", "message", "type", "chainId", "images", "attachments"),
      "the property set must be exactly the 7 surviving parameters (the other 7 are untouched by this batch)"
    )
    // 活能力面**必须在场**（禁顺带删活面：device / images / attachments 是当日刚落地的能力；
    // 判据 = 仍是**对象形态**的已声明属性，不是仅名字在场）。
    for k <- List("address", "device", "message", "type", "chainId", "images", "attachments") do
      assert(
        propsOf(k).flatMap(_.asObject).nonEmpty,
        s"`$k` must still be a declared object property with its own face (live capability, untouched)"
      )
    assertEquals(
      MailTool.inputSchema("required").flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil),
      List("message"),
      "required face unchanged"
    )
    assertEquals(MailTool.name, "Mail")

  test("描述面（mailparams 批 re-pin）：三面描述不再宣称存在 `delivery` 参数，且墓碑拒绝面仍然可见"):
    val faces = List(
      "descriptionBase" -> MailTool.descriptionBase,
      "descriptionNebulaRoot" -> MailTool.descriptionNebulaRoot,
      "descriptionDispatcher" -> MailTool.descriptionDispatcher
    )
    for (label, face) <- faces do
      // ① schema 键已删 ⇒ 描述面**不得**再让模型以为有个 `delivery` 参数可选
      //    （「能力写在 schema/描述层」的落面：schema 侧删了，描述侧必须同步）。
      assert(
        !face.contains("Delivery (via the `delivery` parameter"),
        s"$label: the scaladoc/description preamble still advertises a `delivery` parameter"
      )
      assert(
        !face.contains("Delivery (the `delivery` parameter"),
        s"$label: the description body still advertises a `delivery` parameter"
      )
      assert(!face.contains("kept for compatibility"), s"$label: 「为兼容保留该键」已随键删除作废")
      assert(
        face.contains("no delivery parameter"),
        s"$label: the single-mode fact must state that there is no delivery parameter, got: $face"
      )
      // ② 墓碑面必须对模型可见（旧调用方拿到的错误码 + 单一形态的事实）
      assert(!face.contains("Two delivery modes"), s"$label: the two-modes preamble must be gone")
      assert(
        !face.contains("Delivery modes (via `delivery` parameter)"),
        s"$label: the delivery-modes block must be gone"
      )
      assert(!face.contains("queue: serialized FIFO"), s"$label: the queue availability claim must be gone")
      assert(
        !face.contains("You don't wait for a response.\n  queue"),
        s"$label: the queue leg must be gone from the block"
      )
      assert(face.contains("There is no delivery mode to choose"), s"$label: the single-mode fact must be stated")
      assert(face.contains("RETIRED on"), s"$label: the retirement must be stated")
      assert(face.contains(MailTool.ErrDeliveryQueueRetired), s"$label: the retirement error code must be named")
      assert(
        !face.contains("or target a team agent in queue mode"),
        s"$label: no face may still advertise queue mode for team agents"
      )
    end for

  // ============================================================
  // 2. 非设备腿：统一显式拒绝（单点闸，位置先于一切路由/资源闸）
  //    本节的调用**逐字**保留 `delivery="queue"`（该键已不在 schema）——
  //    它们正是**墓碑路径**的复现器：引擎无 schema 校验 ⇒ 键仍到达 `call()`。
  // ============================================================

  test("非设备腿 delivery=queue ⇒ 统一显式拒绝（五腿：team 短名 / project: / node: / Nebula / 裸项目名）"):
    val legs = List(
      ("team 短名", "member", ctx()),
      ("project: 坐标", "project:p-x", ctx()),
      ("node: 坐标（分发器腿）", "node:n-9", ctx(dispatcher = true)),
      ("Nebula（分发器腿）", "Nebula", ctx(dispatcher = true)),
      ("裸项目名（root 腿）", "p-x", ctx(nebulaRoot = true))
    )
    for (label, addr, c) <- legs do
      val msg = rejectedMsg(
        MailTool.call(qJson("address" -> addr, "message" -> "hi", "delivery" -> "queue"), c).unsafeRunSync(),
        label
      )
      assert(clue(msg).contains(MailTool.ErrDeliveryQueueRetired), s"$label: must carry the retirement code")
      assert(msg.contains("retired"), s"$label: must state that queue is retired")
      assert(
        msg.contains("delivery=queue"),
        s"$label: must name the offending value so the caller can self-correct, got: $msg"
      )
      assert(
        msg.contains(s"Target: '$addr'"),
        s"$label: must echo the target (proves the guard fired, not a routing gate)"
      )
      // 闸位置证明：拒绝先于一切路由/授权/资源闸（这些腿的"自然"错误各不相同）
      assert(
        !msg.contains("outside your address face") && !msg.contains("is not mounted") &&
          !msg.contains("no project context") && !msg.contains("NEBULA_ROOT_UNRESOLVED") &&
          !msg.contains("TEAM names only"),
        s"$label: the retirement guard must fire BEFORE any routing/face gate, got: $msg"
      )

    end for

  test("非设备腿 delivery=queue（同 target）：拒绝文案与地址无关地一致 —— 不含任何旧 queue 专属文案"):
    val msg = rejectedMsg(
      MailTool
        .call(qJson("address" -> "node:n-9", "message" -> "hi", "delivery" -> "queue"), ctx(dispatcher = true))
        .unsafeRunSync(),
      "node: 腿"
    )
    // 旧 `node:` 腿专属文案已随退役消失（其内容与语气由本文案统一承担）
    assert(!msg.contains("always immediate"), s"the old node-leg queue text must be gone, got: $msg")
    assert(
      !msg.contains("Queue mode is only for team agents"),
      s"the old queue URL refusal must be unreachable, got: $msg"
    )
    assert(msg.contains("every Mail is delivered immediately"), s"the replacement semantics must be stated, got: $msg")

  // ============================================================
  // 3. 设备腿：v2.1 拒 queue 契约逐字保持（禁为退役而翻已落契约）
  //    mailparams 批对该腿**零改动**：它收到的仍是同一个旧值（现由墓碑读取转交）。
  // ============================================================

  test("设备腿：v2.1「显式拒 queue」契约逐字保持 —— 自有字面量，不走退役文案"):
    val msg = rejectedMsg(
      MailTool.call(qJson("device" -> "KAI", "message" -> "hi", "delivery" -> "queue"), ctx()).unsafeRunSync(),
      "设备腿"
    )
    // 逐字相等（不是 contains）：本批对设备腿**零改动**，故契约文本逐字节钉死。
    assertEquals(
      msg,
      "Device targets are always immediate — the peer's Nebula session is injected at its next turn boundary, " +
        "so a serialized FIFO queue would only delay it. Drop delivery=queue."
    )
    assert(
      !msg.contains(MailTool.ErrDeliveryQueueRetired),
      "the device leg must keep its OWN contract text (the retirement guard sits AFTER the device branch)"
    )

  // ============================================================
  // 4. 对照腿 + 残差读数：本闸只咬 queue 一个值；其余旧值 = 键被静默忽略
  // ============================================================

  test("对照腿 / 残差读数：非 queue 的 delivery 值（immediate / 陌生值）与「键缺席」逐字同一结果"):
    val c = ctx(dispatcher = true)
    val withImmediate = MailTool
      .call(qJson("address" -> "node:n-9", "message" -> "hi", "delivery" -> "immediate"), c)
      .unsafeRunSync()
    val withoutDelivery = MailTool.call(qJson("address" -> "node:n-9", "message" -> "hi"), c).unsafeRunSync()
    val withAsk = MailTool
      .call(qJson("address" -> "node:n-9", "message" -> "hi", "delivery" -> "ask"), c)
      .unsafeRunSync()
    assert(!isRetired(withImmediate), s"immediate must not hit the retirement guard, got: $withImmediate")
    assert(!isRetired(withoutDelivery), s"an absent delivery must not hit the retirement guard, got: $withoutDelivery")
    assert(!isRetired(withAsk), s"an unknown value must not hit the retirement guard, got: $withAsk")
    // 残差面（mailparams 批）：三值**逐字同一结果** —— 非 `queue` 的键一律被静默忽略
    // （`delivery match` 的「陌生值 ⇒ WARN 后收敛到 immediate」分支已随本批删除）。
    assertEquals(
      withImmediate,
      withoutDelivery,
      "delivery=\"immediate\" must be indistinguishable from the key being absent"
    )
    assertEquals(
      withAsk,
      withoutDelivery,
      "an unknown delivery value must be indistinguishable from the key being absent (no branch left)"
    )
    // 原始读数（供 root 判是否升级为「任何 `delivery` 键一律报错」；`logBuffered=false` 下逐字可见）
    println(
      "[residual-mailparams] delivery=\"immediate\" -> " + render(withImmediate) +
        " || delivery=\"ask\" -> " + render(withAsk) +
        " || key-absent -> " + render(withoutDelivery)
    )

  /** 有界等待（本仓既有 idiom：10+ 个 spec 各自持有同款私有副本，如 `MailDedupWiringSpec:87`） */
  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
    cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(
              new AssertionError("waitUntil: immediate Mail never reached the live target in time")
            )
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  // ============================================================
  // 5. 端到端（真 team fixture）：queue 被拒 + 零副作用；immediate 不受影响
  // ============================================================

  private class RecordingLlm extends LlmHandle[IO]:
    val requests: Ref[IO, List[LlmRequest]] = Ref.unsafe(Nil)

    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(req :: _)) >>
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkResources(
    system: ActorSystem,
    tmp: os.Path,
    llm: LlmHandle[IO],
    sessionStore: SessionStore
  ): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = sessionStore,
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

  private def fixtureTeam(tmp: os.Path, teamName: String): Unit =
    val data = tmp / "data"
    PathUtil.setDataRoot(data)
    val teamDir = data / "teams" / teamName
    os.makeDir.all(teamDir)
    os.write.over(
      teamDir / "team.json",
      s"""{"name": "${teamName}", "description": "delivery retire fixture", "lead": "boss", "members": ["member"]}"""
    )
    for name <- List("boss", "member") do
      val adir = teamDir / "agents" / name
      os.makeDir.all(adir)
      os.write.over(adir / "agent.json", """{"description": "fixture", "useWhen": "tests"}""")

  private def ctxFor(resources: SharedResources, system: ActorSystem, senderSid: String): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some(senderSid),
      sharedResources = Some(resources),
      actorSystem = Some(system)
    )

  test("端到端：team 腿 queue 被显式拒绝 —— 零队列落盘、零 queue 文件、零 turn；同 target 的 immediate 不受影响"):
    val teamName = s"evfmtb${java.util.UUID.randomUUID().toString.take(6)}"
    val system = ActorSystem(s"evfmtb-q-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "evfmtb-q")
    fixtureTeam(tmp, teamName)
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      bossMeta <- sessionStore.createSession(s"$teamName/boss", agentName = Some("boss"), flowName = Some(teamName))
      memberMeta <- sessionStore.createSession(
        s"$teamName/member",
        agentName = Some("member"),
        flowName = Some(teamName)
      )
      _ <- TeamSessionRegistry.registerSession(teamName, "boss", bossMeta.id)
      _ <- TeamSessionRegistry.registerSession(teamName, "member", memberMeta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      // 目标活着（真 fixture：若 queue 路径被走到，它会落盘并 drain —— 红侧即此形态）
      _ <- MailTool.activateAgent(memberMeta.id, resources, system, ctxFor(resources, system, bossMeta.id))
      resQueue <- MailTool.call(
        qJson("address" -> "member", "message" -> "EVFMTB_QUEUE_MARKER", "delivery" -> "queue"),
        ctxFor(resources, system, bossMeta.id)
      )
      queuedItems <- MailQueueStore.load(memberMeta.id)
      queueFileExists <- IO(os.exists(PathUtil.dataRoot / "sessions" / memberMeta.id / "mail-queue.json"))
      reqsAfterQueue <- llm.requests.get
      // 对照腿：同 target、同发送者，仅把 delivery 换成 immediate
      t0 <- IO(System.currentTimeMillis())
      resImmediate <- MailTool.call(
        qJson("address" -> "member", "message" -> "EVFMTB_IMMEDIATE_MARKER", "delivery" -> "immediate"),
        ctxFor(resources, system, bossMeta.id)
      )
      // 观测窗（本批更正，逐字登记）：原为固定 `IO.sleep(500.millis)`。本宿主当前负载下
      // （swap 85–93% + 3–5 条他批构建在飞）实测「immediate 调用 → 首个 LLM 请求」延迟
      // **> 500ms** ⇒ 该固定窗偶发不足（两轮红读数见交付报告）。改用本仓既有 idiom
      // （同款私有 `waitUntil`，见 `MailDedupWiringSpec:87` 等 10+ 处）并**打印实测延迟**。
      // 🔴 **断言实质零放宽**：immediate 仍必须到达活目标 —— 只是把「固定 500ms 窗内到没到」
      // 换成「有界窗内必到 + 实测延迟读数」。
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.nonEmpty))
      latencyMs <- IO(System.currentTimeMillis() - t0)
      _ <- IO(println(s"[immediate-latency] first LLM request ${latencyMs}ms after the immediate Mail call"))
      reqsAfterImmediate <- llm.requests.get
    yield (resQueue, queuedItems, queueFileExists, reqsAfterQueue, resImmediate, reqsAfterImmediate)

    val (resQueue, queuedItems, queueFileExists, reqsAfterQueue, resImmediate, reqsAfterImmediate) =
      io.unsafeRunSync()
    // ① queue 被显式拒绝（墓碑面；红侧 = 墓碑删净后这里返回 Right("Message queued to member. …") 形态）
    val msg = rejectedMsg(resQueue, "team 腿")
    assert(clue(msg).contains(MailTool.ErrDeliveryQueueRetired), s"team leg must carry the retirement code, got: $msg")
    assert(msg.contains(s"Target: 'member'"), s"must echo the target, got: $msg")
    // ② 零投递副作用：队列层结构上不可达
    assertEquals(queuedItems, Nil, "queue must not be written (MailQueueStore unreachable from the tool face)")
    assert(!queueFileExists, "no mail-queue.json may be created by a rejected queue Mail")
    assert(clue(reqsAfterQueue).isEmpty, "a rejected queue Mail must not trigger any turn")
    // ③ 对照腿：immediate 仍在库路径上（本闸只咬 queue 一个值）
    assert(!isRetired(resImmediate), s"immediate must not be rejected by the retirement guard, got: $resImmediate")
    assert(
      clue(reqsAfterImmediate).nonEmpty,
      "immediate Mail must still be delivered to the live target (the guard must not have broken the only mode)"
    )
    system.stopAll.unsafeRunSync()

end MailDeliveryRetireSpec
