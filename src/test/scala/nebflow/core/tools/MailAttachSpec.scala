package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.ActorSystem
import nebflow.actor.{
  AgentCommand,
  AgentDef,
  AgentKind,
  AgentRecord
} // W1 shim: main exported these from nebflow.agent (protocol/AgentState); the merge moved them to actor
import nebflow.agent.*
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.{
  RateLimiter,
  SessionStore
} // W1 shim: main had nebflow.gateway.{RateLimiter, SessionStore}; PR re-homed both to core
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{
  AttachContract,
  ContentBlock,
  LlmHandle,
  LlmRequest,
  LlmResponse,
  StreamChunk
} // W1 shim: main had nebflow.dropbox.AttachContract (PR-side home) + the shared protocol types
import nebflow.shared.PathUtil // W1 shim: main had nebflow.core.PathUtil; PR moved it to shared
import nebflow.shared.ThinkingConfig // W1 shim: main had nebflow.llm.ThinkingConfig; PR moved it to shared
import fs2.Stream
import scala.concurrent.duration.*

/**
 * mailattach batch (2026-09-17, author's four answers = route A) - landing assertions.
 * Re-pinned by the mailmodel batch (2026-09-25, ruling (e)): the cross-device `device`
 * leg of the Mail tool is retired, so the device-leg faces of the original batch are
 * gone with it - A2 (device-leg transport via the device file channel), B7 (the 4000-char
 * device-text gate) and A5 (the five-key relay payload, now covered by the tombstone-only
 * `DeviceMailSpec` in neblink) are deleted from this file, and the schema/description
 * assertions below pin the SAME-MACHINE-only wording.
 *
 * Scope of record = `.nebflow/reports/20260917_mailattach-plan.md` (faces 1-6 / route A /
 * the change list) with the mailmodel retirement applied. Surviving pins:
 *   - **A1** `attachments` in the schema (count/size caps reference `AttachContract` ONLY) - faces (1)(2);
 *   - **A3** same-machine note = absolute path + byte size + sha256, ZERO bytes moved - face (5)
 *     (end-to-end: the note really reaches the recipient session, read from the real LLM request body);
 *   - **A4** `images` face untouched (<=5 / 10 MiB) - face (1) (pinned reading by reading);
 *   - **B6** `node:` + `images` => explicit refusal (cures the silent
 *     "load/base64 then never use" drop; the `project:` leg is demolished outright
 *     — project-trigger demolition batch, refused before the vision gate) - face (4).
 *
 * Every assertion reads ONLY the model-visible face or observable behaviour (description
 * text, schema, `call`'s `Either`, the real LLM request body) - no implementation-detail
 * snapshots; the face-(5) end-to-end fixture reuses the `MailDeliveryRetireSpec` technique
 * (real ActorSystem + real session fixtures), zero network, zero real delivery.
 * 团队功能删除批阶段 3 起，face-(5) 的投递目标是 `Nebula`（delegate → root 同机腿）+
 * 接受方 actor 实收命令的正文——原「团队内置短名 + LLM 请求体」夹具随团队地址面退休。
 */
class MailAttachSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  // ============================================================
  // 夹具
  // ============================================================

  /** 全字符串字段的入参。 */
  private def qIn(fields: (String, String)*): JsonObject =
    JsonObject.fromIterable(fields.map((k, v) => k -> Json.fromString(v)))

  /** 一个路径数组字段（`attachments` / `images`）挂到字符串入参上。 */
  private def withArr(base: JsonObject, key: String, ps: String*): JsonObject =
    base.add(key, Json.arr(ps.map(_.asJson)*))

  private def ctx(
    dispatcher: Boolean = false,
    nebulaRoot: Boolean = false,
    projectName: Option[String] = None
  ): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some("sid-mailattach"),
      isDispatcher = dispatcher,
      projectName = projectName,
      agentDef = if nebulaRoot then Some(AgentDef(name = "Nebula", description = "spec fixture")) else None
    )

  /** 断言点收在「拒绝文案」上：成功即失败（红侧证据 = 这里的 got 原文）。 */
  private def rejected(res: Either[ToolError, String], label: String): String =
    res match
      case Left(err) => err.message
      case Right(v) => fail(s"$label: must be rejected, got: $v")

  private def callErr(input: JsonObject, c: ToolContext): String =
    rejected(MailTool.call(input, c).unsafeRunSync(), "call")

  private def prop(schema: JsonObject, name: String): JsonObject =
    schema("properties")
      .flatMap(_.asObject)
      .flatMap(_(name))
      .flatMap(_.asObject)
      .getOrElse(fail(s"parameter '$name' missing from the Mail schema"))

  private def propDesc(schema: JsonObject, name: String): String =
    prop(schema, name)("description").flatMap(_.asString).getOrElse(fail(s"$name has no description"))

  /** 空白归一：换行/缩进塌成单空格 ⇒ 断言语义稳定于折行方式。 */
  private def n(s: String): String = s.replaceAll("\\s+", " ").trim

  /** 与 `MailTool.sha256OfFile` 同款口径（独立复算 ⇒ 不是抄实现）。 */
  private def sha256Of(p: os.Path): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.digest(os.read.bytes(p)).map("%02x".format(_)).mkString

  // ============================================================
  // ① A1/A4：schema 面 —— `attachments` 入册（上限只引用 AttachContract），`images` 零改动
  // ============================================================

  test("① schema：`attachments` 入册（array<string>、maxItems=9、default=[]）、`images` 面零改动"):
    val a = prop(MailTool.inputSchema, "attachments")
    assertEquals(a("type").flatMap(_.asString), Some("array"), "attachments 必须是数组")
    assertEquals(
      a("items").flatMap(_.asObject).flatMap(_("type")).flatMap(_.asString),
      Some("string"),
      "attachments 的元素类型必须是 string（绝对路径串）"
    )
    // 上限**只**来自 AttachContract（作者给定数）——本断言即「零硬编码副本」的机械判据。
    assertEquals(
      a("maxItems").flatMap(_.asNumber).flatMap(_.toInt),
      Some(AttachContract.MaxAttachmentsPerMessage),
      "maxItems 必须恰等 AttachContract.MaxAttachmentsPerMessage（禁第二份字面量）"
    )
    assertEquals(AttachContract.MaxAttachmentsPerMessage, 9, "作者给定数：单条消息附件 ≤ 9 件")
    assertEquals(
      a("default").flatMap(_.asArray).map(_.toList),
      Some(Nil),
      "attachments 缺省 = 空数组（既有调用方零漂移）"
    )
    // The parameter description must state the (now single) same-machine leg in full
    // (mailmodel batch: the DEVICE leg is retired, so its wording must be GONE).
    val d = n(propDesc(MailTool.inputSchema, "attachments"))
    assert(d.contains("ABSOLUTE"), s"missing the absolute-path hard requirement: $d")
    assert(d.contains("any file type"), s"missing 'any file type': $d")
    assert(d.contains("same-machine targets"), s"missing the same-machine leg wording: $d")
    assert(!d.contains("DEVICE"), s"the DEVICE leg is retired - its wording must be gone: $d")
    assert(d.contains("sha256"), s"missing the same-machine sha256 declaration: $d")
    assert(d.contains(AttachContract.MaxFileBytesLabel), s"missing the author-given size cap label: $d")

    // ---- A4 零改动：`images` 面逐读数不动 ----
    val i = prop(MailTool.inputSchema, "images")
    assertEquals(i("type").flatMap(_.asString), Some("array"))
    assertEquals(i("maxItems").flatMap(_.asNumber).flatMap(_.toInt), Some(5), "images 上限必须仍是 5")
    assertEquals(ImageInject.MAX_ATTACHMENTS, 5, "vision 面的件数上限于 ImageInject 单一权威面")
    assertEquals(ImageInject.MAX_IMAGE_BYTES, 10 * 1024 * 1024, "vision 面单件上限 10 MiB 未动")
    assertEquals(
      MailTool.inputSchema("required").flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil),
      List("message"),
      "required 仍只应剩 message（attachments 是可选件）"
    )

  // ============================================================
  // ② P1：描述与 schema 不再互斥（逐字前后对照见交付报告）
  // ============================================================

  test("② P1：三面描述都宣称 `attachments` 参数存在，且零「没有该参数」类反向断言"):
    val faces = List(
      "descriptionBase" -> MailTool.descriptionBase,
      "descriptionNebulaRoot" -> MailTool.descriptionRoot,
      "descriptionDispatcher" -> MailTool.descriptionDispatcher
    )
    for (label, raw) <- faces do
      val f = n(raw)
      assert(f.contains("`attachments` parameter"), s"$label: 必须宣称 `attachments` 参数（模型只读描述/schema ⇒ 不宣称则能力实际不可用）")
      assert(!f.contains("Mail has no general attachments"), s"$label: 仍自称「无通用附件」= 与 schema 的 `attachments` 互斥")
      assert(!f.contains("no `attachments` parameter"), s"$label: 仍自称「没有 `attachments` 参数」= 与 schema 互斥")
      assert(
        !f.contains("`SendMessage`'s `attachments` — pure transport"),
        s"$label: still points the general-attachment landing at SendMessage (the tool has the parameter; the pointer is stale)"
      )
      // mailmodel batch (2026-09-25): the device leg (and its 4000-char budget sentence)
      // is retired - the description must no longer advertise the gate. (The one-line
      // retirement NOTE that names MAIL_DEVICE_RETIRED stays by design; only the
      // advertisement of the live device faces dies.)
      assert(!f.contains("4000"), s"$label: the device-leg 4000-char budget is retired and must be gone")
    // 反向：`images` 的参数级描述不得再宣称无通用附件，且必须声明 text-only 腿。
    // （project-trigger 拆除批：text-only 腿 = `node:` 一条——project 腿已拆除。）
    val img = n(propDesc(MailTool.inputSchema, "images"))
    assert(!img.contains("NO general attachments"), s"images 参数级描述仍宣称无通用附件: $img")
    assert(!img.contains("no `attachments` parameter"), s"images 参数级描述仍否认该参数: $img")
    assert(!img.contains("`project:`"), s"images 参数级描述不得再点名已拆除的 project 腿: $img")
    assert(img.contains("`node:`"), s"images 参数级描述缺 text-only 腿的现状声明: $img")
    assert(img.contains("max 5"), s"images 参数级描述缺件数上限: $img")

  // ============================================================
  // ③ A1/A3 闸位：缺省 / 形态 / 件数 / 存在 / 类型 / 大小，全部 fail-fast
  // ============================================================

  test("③ 闸位：缺省 / 空数组 ⇒ 不触发附件闸（既有调用方零漂移）"):
    val absent = callErr(qIn("address" -> "backend", "message" -> "hi"), ctx())
    assertEquals(absent, "No actor system available", "缺省 attachments 不得改变既有判定")
    val empty = callErr(withArr(qIn("address" -> "backend", "message" -> "hi"), "attachments"), ctx())
    assertEquals(empty, "No actor system available", "空数组等价于未带附件")

  test("③ 闸位：非绝对路径 ⇒ 显式拒绝并回显问题路径（判在原始串上）"):
    val msg = callErr(withArr(qIn("address" -> "backend", "message" -> "hi"), "attachments", "rel/x.bin"), ctx())
    assert(msg.contains("must be absolute"), s"相对路径必须显式拒绝: $msg")
    assert(msg.contains("rel/x.bin"), s"必须回显问题路径: $msg")
    assert(msg.contains("Nothing"), s"必须声明零投递意图: $msg")

  test("③ 闸位：不存在 / 是目录 ⇒ 各自显式拒绝（两种失败类不得混写）"):
    val tmp = os.temp.dir(prefix = "mailattach-gate")
    val dir = tmp / "adir"
    os.makeDir.all(dir)
    try
      val missing = callErr(
        withArr(qIn("address" -> "backend", "message" -> "hi"), "attachments", (tmp / "nope.bin").toString),
        ctx()
      )
      assert(missing.contains("does not exist"), s"不存在必须显式拒绝: $missing")
      assert(missing.contains("nope.bin"), s"必须回显问题路径: $missing")
      val isDir = callErr(
        withArr(qIn("address" -> "backend", "message" -> "hi"), "attachments", dir.toString),
        ctx()
      )
      assert(isDir.contains("directory"), s"目录必须显式拒绝: $isDir")
      assert(!isDir.contains("does not exist"), s"两种失败类不得混写: $isDir")
    finally os.remove.all(tmp)

  test("③ 闸位：件数 > 9 ⇒ ATTACH_TOO_MANY，先于一切逐件 IO"):
    val ten = (1 to 10).map(i => s"/nonexistent/part-$i.bin")
    val msg = callErr(withArr(qIn("address" -> "backend", "message" -> "hi"), "attachments", ten*), ctx())
    assert(msg.contains(AttachContract.Codes.AttachTooMany), s"件数闸必须走 AttachContract 词表: $msg")
    assert(msg.contains("9"), s"必须回显上限: $msg")
    assert(!msg.contains("does not exist"), s"件数闸必须先于逐件存在性判（否则报错面漂移）: $msg")

  test("③ 闸位：单件 > 1 GiB ⇒ ATTACH_TOO_LARGE + actual/limit（稀疏夹具，零实写）"):
    val tmp = os.temp.dir(prefix = "mailattach-big")
    val big = tmp / "big.bin"
    val raf = new java.io.RandomAccessFile(big.toIO, "rw")
    try raf.setLength(AttachContract.MaxFileBytes + 1)
    finally raf.close()
    try
      assertEquals(
        java.nio.file.Files.size(big.toNIO),
        AttachContract.MaxFileBytes + 1,
        "稀疏夹具的声明长度（只 ftruncate，不写数据）"
      )
      val msg = callErr(
        withArr(qIn("address" -> "backend", "message" -> "hi"), "attachments", big.toString),
        ctx()
      )
      assert(msg.contains(AttachContract.Codes.AttachTooLarge), s"大小闸必须走 AttachContract 词表: $msg")
      assert(msg.contains((AttachContract.MaxFileBytes + 1).toString), s"必须回显 actual: $msg")
      assert(msg.contains(AttachContract.MaxFileBytesLabel), s"必须回显 limit 量纲标签: $msg")
      assert(msg.contains("Nothing was sent"), s"fail-fast 必须自陈零投递: $msg")
    finally os.remove.all(tmp)

    end try

  test("③ 闸位先于投递腿：合法件 + 不存在件混合 ⇒ 仍零投递（先于 `no actor system`）"):
    val tmp = os.temp.dir(prefix = "mailattach-mix")
    val ok = tmp / "ok.bin"
    os.write(ok, Array[Byte](1, 2, 3))
    try
      val msg = callErr(
        withArr(
          qIn("address" -> "backend", "message" -> "hi"),
          "attachments",
          ok.toString,
          (tmp / "gone.bin").toString
        ),
        ctx()
      )
      assert(msg.contains("does not exist"), s"逐件判必须报出坏件: $msg")
      assert(msg != "No actor system available", s"附件闸必须先于投递腿判定（否则即静默面）: $msg")
    finally os.remove.all(tmp)

  // ============================================================
  // ④ B6：同机腿 `images` ⇒ 显式拒绝（治静默丢）
  // ============================================================

  /**
   * `address` 腿的 `images` 走 G3 共用管线（先 `resolveImages` 落盘校验，再路由）⇒
   * 要真的走到「同机腿拒绝」，图件必须**真实存在**（虚构路径会先被 G3 的存在性判拦下）。
   */
  private def realImage(tmp: os.Path): os.Path =
    val p = tmp / "img-1.png"
    os.write(p, Array[Byte](0x89.toByte, 'P'.toByte, 'N'.toByte, 'G'.toByte, 1, 2, 3))
    p

  test("④ B6：`node:` + images ⇒ 显式拒绝（判据先于「无项目上下文」）"):
    val tmp = os.temp.dir(prefix = "mailattach-b6a")
    val img = realImage(tmp)
    val system = ActorSystem(s"mailattach-b6a-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val msg = callErr(
        withArr(qIn("message" -> "hi"), "images", img.toString).add("address", Json.fromString("node:n-9")),
        ctx(dispatcher = true).copy(actorSystem = Some(system))
      )
      assert(msg.contains(MailTool.ErrVisionUnsupportedLeg), s"必须带显式拒码: $msg")
      assert(!msg.contains("no project context"), s"拒绝必须发生在路由判定之前: $msg")
      assert(msg.contains("attachments"), s"必须指明可用的替代路径: $msg")
    finally os.remove.all(tmp)

  test("④ 拆除：`project:` + images ⇒ 腿拒绝先于 vision 闸（MAIL_PROJECT_TRIGGER_UNSUPPORTED）"):
    val tmp = os.temp.dir(prefix = "mailattach-b6b")
    val img = realImage(tmp)
    val system = ActorSystem(s"mailattach-b6b-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val msg = callErr(
        withArr(qIn("message" -> "hi"), "images", img.toString).add("address", Json.fromString("project:any")),
        // project-trigger 拆除批：project 地址对一切角色 = 腿拒绝（零投递），
        // 先于 vision 闸——拒绝词点名 Delegate，与挂载状态无关。
        ctx(nebulaRoot = true).copy(actorSystem = Some(system))
      )
      assert(msg.contains(MailTool.ErrProjectTriggerUnsupported), s"必须带拆除拒码: $msg")
      assert(!msg.contains(MailTool.ErrVisionUnsupportedLeg), s"腿拒绝必须先于 vision 闸: $msg")
      assert(!msg.contains("is not mounted"), s"拒绝必须与挂载状态无关: $msg")
      assert(msg.contains("Delegate"), s"必须指路派发面唯一入口: $msg")
    finally os.remove.all(tmp)

  test("④ B6 对照：同两条腿**不带** images ⇒ node 判定逐字保留；project 腿 = 拆除拒绝"):
    val system = ActorSystem(s"mailattach-b6c-${java.util.UUID.randomUUID().toString.take(6)}")
    val node = callErr(
      qIn("address" -> "node:n-9", "message" -> "hi"),
      ctx(dispatcher = true).copy(actorSystem = Some(system))
    )
    assert(!node.contains(MailTool.ErrVisionUnsupportedLeg), s"无 images 不得命中本闸: $node")
    assert(node.contains("no project context"), s"既有判定须逐字保留: $node")
    val proj = callErr(
      qIn("address" -> "project:any", "message" -> "hi"),
      // project-trigger 拆除批：零触发面——拒绝与挂载状态无关。
      ctx(nebulaRoot = true).copy(actorSystem = Some(system))
    )
    assert(!proj.contains(MailTool.ErrVisionUnsupportedLeg), s"无 images 不得命中本闸: $proj")
    assert(
      proj.contains(MailTool.ErrProjectTriggerUnsupported),
      s"project 腿 = 拆除拒绝（既有「is not mounted」面随腿退役）: $proj"
    )

  // ============================================================
  // (6)(7) REMOVED by the mailmodel batch (2026-09-25, ruling (e)): the device
  // leg they pinned is retired. The B7 device-text gate (MaxDeviceMailTextChars /
  // MAIL_DEVICE_TEXT_TOO_LONG) and the device file-channel face are deleted from
  // MailTool; a stale `device=` call now refuses with MAIL_DEVICE_RETIRED before
  // any of these faces could run (pinned in MailModelRetiredSpec). The five-key
  // relay payload contract remains covered by `DeviceMailSpec` in
  // `src/test/scala/nebflow/neblink/` (the DeviceMail* objects stay in-tree as
  // tombstones; the Mail TOOL face no longer reaches them).
  // ============================================================

  // ============================================================
  // ⑤ A3：同机腿附注端到端
  // ============================================================
  // 团队功能删除批（team-feature-deletion-20261104）阶段 3 改写：原夹具走
  // 「团队内置短名 `address -> "member"` + MailTool.activateAgent 冷激活」——
  // 那条地址解析腿已随团队地址面硬删。附注本体的**单点**（`call()` 内的
  // `withAttachmentsNote`，先于一切路由副作用）不变，因此改钉**仍然可寻址**的那条
  // 同机腿：`address="Nebula"`（delegate 会话 → root 议会话，`deliverToRootAgent`
  // → `sendMail`）。判据面从「接受方 LLM 请求文本」改为「接受方 actor 实收的
  // `AgentCommand.ImmediateInput` 正文」——两者都是「附注真实到达接受方会话」，
  // 但后者不依赖 team def / LLM 回路（更窄、更直接）。

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

  /** 接受方 root actor：记录收到的所有命令（ImmediateInput 的正文即判据面）。 */
  private def mkRootFixture(
    system: ActorSystem,
    resources: SharedResources,
    sessionStore: SessionStore
  ): IO[Ref[IO, List[AgentCommand]]] =
    for
      received <- Ref.of[IO, List[AgentCommand]](Nil)
      rootMeta <- sessionStore.createSession("nebula-root", agentName = Some("Nebula"))
      rootRef <- system.spawn(
        {
          def loop: nebflow.actor.Behavior[AgentCommand] =
            nebflow.actor.Behaviors.receiveMessage[AgentCommand](cmd => received.update(_ :+ cmd).as(loop))
          loop
        },
        rootMeta.id
      )
      _ <- resources.agentRegistry.update(
        _ + (rootMeta.id -> AgentRecord(rootMeta.id, rootRef, AgentKind.Root, rootMeta.id))
      )
    yield received

  /** 有界等待接受方收到命令（空集即判据无效，禁假绿）。 */
  private def awaitCommands(
    received: Ref[IO, List[AgentCommand]],
    deadlineMs: Long
  ): IO[List[AgentCommand]] =
    IO.defer {
      received.get.flatMap { cs =>
        if cs.nonEmpty || System.currentTimeMillis() >= deadlineMs then IO.pure(cs)
        else IO.sleep(100.millis) *> awaitCommands(received, deadlineMs)
      }
    }

  /** 收件人实收正文（ImmediateInput.text + blocks 里的 Text 块）。 */
  private def deliveredText(cs: List[AgentCommand]): String =
    cs.collect { case ii: AgentCommand.ImmediateInput =>
      (ii.text :: ii.blocks.toList.flatten.collect { case ContentBlock.Text(t) => t }).mkString("\n")
    }.mkString("\n")

  private def delegateCtx(resources: SharedResources, system: ActorSystem): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      // delegate 会话身份（roleOf ⇒ SenderRole.Delegate，Nebula 腿对 delegate 开放）
      sessionId = Some(s"delegate-${java.util.UUID.randomUUID().toString.take(8)}"),
      agentDef = Some(AgentDef(name = "Tester", description = "attach e2e sender", tools = Nil)),
      sharedResources = Some(resources),
      actorSystem = Some(system)
    )

  test("⑤ A3 端到端：同机腿 `attachments` ⇒ 附注（绝对路径 + 字节数 + sha256）真实到达接受方会话；🔴 零搬字节"):
    val system = ActorSystem(s"mailattach-e2e-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "mailattach-e2e")
    PathUtil.setDataRoot(tmp / "data")
    // 附件夹具：放在**非** worktree 的临时目录（路径即取件，不依赖仓内相对位置）
    val file = tmp / "report.bin"
    os.write(file, Array[Byte](7, 7, 7, 7, 7))
    val expectedSha = sha256Of(file)
    val expectedSize = os.size(file)

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      resources <- mkResources(system, tmp, null, sessionStore)
      received <- mkRootFixture(system, resources, sessionStore)
      res <- MailTool.call(
        withArr(qIn("address" -> "Nebula", "message" -> "MAILATTACH_NOTE_MARKER"), "attachments", file.toString),
        delegateCtx(resources, system)
      )
      cmds <- awaitCommands(received, System.currentTimeMillis() + 8000)
      // 🔴 零搬字节：源件内容在投递后逐字节不变（附件只以「路径 + 读数」形式进文本）
      bytesAfter <- IO(os.read.bytes(file))
    yield (res, cmds, bytesAfter)

    val (res, cmds, bytesAfter) = io.unsafeRunSync()
    assert(res.isRight, s"同机腿带 attachments 必须成功（附注模式，零搬运）: $res")
    assert(cmds.nonEmpty, "🔴 判据前提：接受方必须收到命令（空集即判据无效，禁假绿）")
    val all = deliveredText(cmds)
    assert(all.contains("MAILATTACH_NOTE_MARKER"), s"邮件正文必须到达接受方会话: ${all.take(400)}")
    assert(all.contains(file.toString), "🔴 附注必须带**绝对路径**（接受方按路径 Read 取件）")
    assert(all.contains(s"$expectedSize B"), s"附注必须带字节数读数（期望 $expectedSize B）")
    assert(all.contains(expectedSha), s"附注必须带发送时算出的 sha256（期望 $expectedSha）")
    assert(all.contains("[Mail 附件]"), "附注必须自陈「通用附件」形态（与图片附注同族词表）")
    assertEquals(bytesAfter.toList, List[Byte](7, 7, 7, 7, 7), "🔴 同机腿零搬字节：源件内容不得被改动")
    assertEquals(os.size(file), expectedSize, "🔴 源件大小不变")
    system.stopAll.unsafeRunSync()

  test("⑤ A3 对照：同机腿**不带** attachments ⇒ 正文不含附注（字节级零漂移）"):
    val system = ActorSystem(s"mailattach-none-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "mailattach-none")
    PathUtil.setDataRoot(tmp / "data")

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      resources <- mkResources(system, tmp, null, sessionStore)
      received <- mkRootFixture(system, resources, sessionStore)
      res <- MailTool.call(
        qIn("address" -> "Nebula", "message" -> "MAILATTACH_PLAIN_MARKER"),
        delegateCtx(resources, system)
      )
      cmds <- awaitCommands(received, System.currentTimeMillis() + 8000)
    yield (res, cmds)

    val (res, cmds) = io.unsafeRunSync()
    assert(res.isRight, s"无附件时既有路径必须照常: $res")
    // 🔴 负控必须有内容才判「不含附注」——空集下的 `!contains` 是假绿。
    assert(cmds.nonEmpty, "🔴 判据前提：接受方必须收到命令（空集即负控无效，禁假绿）")
    val all = deliveredText(cmds)
    assert(all.contains("MAILATTACH_PLAIN_MARKER"), s"正文必须到达: ${all.take(400)}")
    assert(!all.contains("[Mail 附件]"), "未请求附件时不得出现附注（字节级零改动）")
    system.stopAll.unsafeRunSync()

end MailAttachSpec
