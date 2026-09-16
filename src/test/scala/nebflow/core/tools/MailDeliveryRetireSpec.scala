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
 * delivery 退役批（**收窄版**，2026-09-15 作者裁定 (b)）· 落地断言。
 *
 * 裁定 (b) 逐字口径：「**`delivery` 归属 = (b)：保留字段、退役 queue 模式语义**」——
 * 退役的是 queue **模式**，不是字段本身：
 *   1. **schema 面**：`delivery` 键**保留**（键在；旧 caller 的同名键不得变成未知属性），
 *      `enum` 只剩 `"immediate"`、`default = "immediate"`；工具件数与 `required` 不变；
 *      描述**不再宣称 queue 模式可用**（这是「能力写在 schema/描述层」的落面）；
 *   2. **行为面（非设备腿）**：`delivery="queue"` ⇒ **统一显式拒绝**
 *      （`MAIL_DELIVERY_QUEUE_RETIRED`，零投递副作用、零队列落盘）。**禁静默立即化**
 *      （调用方声明的串行链语义无法被立即投递满足 ⇒ 静默改投 = 静默丢语义）；
 *      拒绝闸位置 = `call` 里**设备腿分支之后**、`layeredRoute` 之前 —— 单点兜住
 *      `node:` / `project:` / Nebula / team 短名 / 裸项目名**全部非设备腿**；
 *   3. **设备腿零改动**：`deliverToDevice` 的 v2.1「显式拒 queue」契约**逐字保持**
 *      （自有字面量，**不**走退役文案；本条以**逐字相等**断言钉死）；
 *   4. **对照腿**：`immediate` / 缺参 / 陌生值（如旧 `"ask"`）不受本闸影响。
 *
 * 双向钉（改前/改后，读数见交付报告与 `.nebflow/evidence/20260915_evfmt/b/`）：
 * 本文件在支基（改前）跑为**红**——team 腿 `delivery="queue"` 被接受并返回
 * `Right("Message queued to member. …")` 且落盘；改后跑为**绿**——同一调用
 * 返回退役拒绝、零落盘、零 turn。故本文件既是新语义的守卫，也是改前红侧的复现器。
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
      case Right(v)  => fail(s"$label: delivery=queue must be rejected, got: $v")

  // ============================================================
  // 1. schema 面：字段保留、enum 收成单一值
  // ============================================================

  private def propsOf: JsonObject =
    MailTool.inputSchema("properties").flatMap(_.asObject).getOrElse(fail("Mail schema has no properties"))

  private def deliveryProp: JsonObject =
    propsOf("delivery").flatMap(_.asObject).getOrElse(
      fail("ruling (b): the `delivery` key must be RETAINED in the schema (retired = the queue MODE, not the field)")
    )

  test("schema：`delivery` 键保留（裁定 (b)）—— enum 只剩 \"immediate\"、default=immediate、件数/required 不变"):
    assertEquals(
      deliveryProp("enum").flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil),
      List("immediate"),
      "enum must declare 'immediate' only — the retired 'queue' value must no longer be advertised as usable"
    )
    assertEquals(deliveryProp("default").flatMap(_.asString), Some("immediate"), "default stays 'immediate'")
    assertEquals(deliveryProp("type").flatMap(_.asString), Some("string"), "'delivery' stays a string field")
    // 件数不变：保留字段 ≠ 增删参数
    // mailattach 批（2026-09-17 作者四答 = 路线 A）re-pin：schema 新增**已批准**的
    // `attachments` 参数 ⇒ 本集合等值断言**逐字跟着新批准面走**。判据强度不变
    // （仍是精确集合等值：任何与本批无关的增 / 删 / 改名一律红），仅基线前移。
    assertEquals(
      propsOf.keys.toSet,
      Set("address", "device", "message", "type", "delivery", "chainId", "images", "attachments"),
      "the property set must be unchanged (the field is retained, not removed and not replaced)"
    )
    assertEquals(
      MailTool.inputSchema("required").flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil),
      List("message"),
      "required face unchanged"
    )
    assertEquals(MailTool.name, "Mail")

  test("描述面①：`delivery` 的 schema 描述不再宣称 queue 可用、且显式声明退役与拒绝码"):
    val d = deliveryProp("description").flatMap(_.asString).getOrElse(fail("delivery.description missing"))
    assert(!d.contains("'queue' = serialized FIFO"), s"the old queue-availability claim must be gone, got: $d")
    assert(!d.contains("queue would delay it"), s"the old queue trade-off wording must be gone, got: $d")
    assert(!d.contains("addresses are always immediate"), s"the per-leg queue note belongs to the routing face now, got: $d")
    assert(d.contains("one mode only"), s"the single-mode fact must be stated, got: $d")
    assert(d.contains("RETIRED"), s"the retirement must be stated, got: $d")
    assert(
      d.contains(MailTool.ErrDeliveryQueueRetired),
      s"the description face must name the retirement error code (capability declared in the description layer), got: $d"
    )
    assert(d.contains("backward compatibility"), s"why the key is kept must be stated, got: $d")

  test("描述面②：工具描述（base + 两个地址面变体）queue 可用措辞清零、退役语义显式声明"):
    val faces = List(
      "descriptionBase" -> MailTool.descriptionBase,
      "descriptionNebulaRoot" -> MailTool.descriptionNebulaRoot,
      "descriptionDispatcher" -> MailTool.descriptionDispatcher
    )
    for (label, face) <- faces do
      assert(!face.contains("Two delivery modes"), s"$label: the two-modes preamble must be gone")
      assert(!face.contains("Delivery modes (via `delivery` parameter)"), s"$label: the delivery-modes block must be gone")
      assert(!face.contains("queue: serialized FIFO"), s"$label: the queue availability claim must be gone")
      assert(!face.contains("You don't wait for a response.\n  queue"), s"$label: the queue leg must be gone from the block")
      assert(face.contains("There is no delivery mode to choose"), s"$label: the single-mode fact must be stated")
      assert(face.contains("RETIRED on"), s"$label: the retirement must be stated")
      assert(face.contains(MailTool.ErrDeliveryQueueRetired), s"$label: the retirement error code must be named")
      assert(
        !face.contains("or target a team agent in queue mode"),
        s"$label: no face may still advertise queue mode for team agents"
      )

  // ============================================================
  // 2. 非设备腿：统一显式拒绝（单点闸，位置先于一切路由/资源闸）
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
      assert(msg.contains(s"Target: '$addr'"), s"$label: must echo the target (proves the guard fired, not a routing gate)")
      // 闸位置证明：拒绝先于一切路由/授权/资源闸（这些腿的"自然"错误各不相同）
      assert(
        !msg.contains("outside your address face") && !msg.contains("is not mounted") &&
          !msg.contains("no project context") && !msg.contains("NEBULA_ROOT_UNRESOLVED") &&
          !msg.contains("TEAM names only"),
        s"$label: the retirement guard must fire BEFORE any routing/face gate, got: $msg"
      )

  test("非设备腿 delivery=queue（同 target）：拒绝文案与地址无关地一致 —— 不含任何旧 queue 专属文案"):
    val msg = rejectedMsg(
      MailTool.call(qJson("address" -> "node:n-9", "message" -> "hi", "delivery" -> "queue"), ctx(dispatcher = true))
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
  // 4. 对照腿：本闸只咬 queue 一个值
  // ============================================================

  test("对照腿：immediate / 缺参 / 陌生值（旧 \"ask\"）都不进退役闸"):
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
    // 陌生值仍收敛到立即路径（与缺参逐字同一结果）——本批未改动该既有观测面
    assertEquals(withAsk, withoutDelivery, "unknown delivery values still converge to immediate (unchanged)")

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
      memberMeta <- sessionStore.createSession(s"$teamName/member", agentName = Some("member"), flowName = Some(teamName))
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
      resImmediate <- MailTool.call(
        qJson("address" -> "member", "message" -> "EVFMTB_IMMEDIATE_MARKER", "delivery" -> "immediate"),
        ctxFor(resources, system, bossMeta.id)
      )
      _ <- IO.sleep(500.millis)
      reqsAfterImmediate <- llm.requests.get
    yield (resQueue, queuedItems, queueFileExists, reqsAfterQueue, resImmediate, reqsAfterImmediate)

    val (resQueue, queuedItems, queueFileExists, reqsAfterQueue, resImmediate, reqsAfterImmediate) =
      io.unsafeRunSync()
    // ① queue 被显式拒绝（红侧 = 改前这里返回 Right("Message queued to member. …")）
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
