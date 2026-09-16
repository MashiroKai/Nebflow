package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.ActorSystem
import nebflow.agent.*
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.flow.TeamSessionRegistry
import nebflow.core.task.FileTaskStore
import nebflow.dropbox.AttachContract
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.neblink.DeviceMail
import nebflow.shared.{ContentBlock, LlmHandle, LlmRequest, LlmResponse, StreamChunk}
import fs2.Stream
import scala.concurrent.duration.*

/**
 * mailattach 批（2026-09-17 作者四答 = 路线 A）· 落地断言。
 *
 * 本批的**范围真源** = `.nebflow/reports/20260917_mailattach-plan.md`（§1 面 1–6 /
 * §2 方案 A / §3 改动面清单）与任务书 §范围。逐条对应：
 *   - **A1** `attachments` 进 schema（件数/大小上限**只**引用 `AttachContract`）——§①②；
 *   - **A2** 设备腿承载（复用既有设备文件通道）+ 附注泛化 —— §⑥（附注进正文且计入 4000 预算）；
 *   - **A3** 同机腿附注 = 绝对路径 + 字节数 + sha256、**零搬字节** —— §⑤（端到端：附注真的
 *     到达接受方会话，读数取自真 LLM 请求体）；
 *   - **A4** `images` 面零改动（≤5 / 10 MiB）—— §①（逐读数钉住未改）；
 *   - **A5** relay 五键载荷一字不动 —— §⑦（键序 + 归一化逐字节）；
 *   - **B6** `project:` / `node:` 收到 `images` ⇒ **显式拒绝**（治「加载/base64 后不使用」的
 *     静默丢）—— §④；
 *   - **B7** 设备腿正文 4000 闸（含附注预算，前置于载荷构造）—— §⑥。
 *
 * 🔴 每个断言都**只**读模型可见面或可观察行为（描述文本、schema、`call` 的 `Either`、
 * 真 LLM 请求体），不含「实现细节快照」；§⑤ 的端到端夹具复用 `MailDeliveryRetireSpec`
 * 的既有手法（真 ActorSystem + 真 team fixture + RecordingLlm），零网络、零真实投递。
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
      case Right(v)  => fail(s"$label: must be rejected, got: $v")

  private def callErr(input: JsonObject, c: ToolContext): String =
    rejected(MailTool.call(input, c).unsafeRunSync(), "call")

  private def prop(schema: JsonObject, name: String): JsonObject =
    schema("properties").flatMap(_.asObject).flatMap(_(name)).flatMap(_.asObject)
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
    // 参数级描述必须把两条腿的语义分开写全（A2 设备腿 / A3 同机腿）。
    val d = n(propDesc(MailTool.inputSchema, "attachments"))
    assert(d.contains("ABSOLUTE"), s"缺「绝对路径」硬要求: $d")
    assert(d.contains("any file type"), s"缺「任意类型」: $d")
    assert(d.contains("SAME-MACHINE") && d.contains("DEVICE"), s"两条腿语义必须分开写全: $d")
    assert(d.contains("sha256"), s"缺同机腿的 sha256 读数声明: $d")
    assert(d.contains(AttachContract.MaxFileBytesLabel), s"缺作者给定的大小上限读数: $d")

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
      "descriptionBase"       -> MailTool.descriptionBase,
      "descriptionNebulaRoot" -> MailTool.descriptionNebulaRoot,
      "descriptionDispatcher" -> MailTool.descriptionDispatcher
    )
    for (label, raw) <- faces do
      val f = n(raw)
      assert(f.contains("`attachments` parameter"),
        s"$label: 必须宣称 `attachments` 参数（模型只读描述/schema ⇒ 不宣称则能力实际不可用）")
      assert(!f.contains("Mail has no general attachments"),
        s"$label: 仍自称「无通用附件」= 与 schema 的 `attachments` 互斥")
      assert(!f.contains("no `attachments` parameter"),
        s"$label: 仍自称「没有 `attachments` 参数」= 与 schema 互斥")
      assert(!f.contains("`SendMessage`'s `attachments` — pure transport"),
        s"$label: 仍把通用附件落点指向 SendMessage（本工具已有该参数，落点过期）")
      assert(f.contains("4000"), s"$label: 缺设备腿正文 4000 字符预算（B7 闸的模型可见面）")
    // 反向：`images` 的参数级描述不得再宣称无通用附件，且必须声明两条腿不支持 vision。
    val img = n(propDesc(MailTool.inputSchema, "images"))
    assert(!img.contains("NO general attachments"), s"images 参数级描述仍宣称无通用附件: $img")
    assert(!img.contains("no `attachments` parameter"), s"images 参数级描述仍否认该参数: $img")
    assert(img.contains("`project:` / `node:`"), s"images 参数级描述缺 text-only 腿的现状声明: $img")
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

  test("③ 闸位先于投递腿：合法件 + 不存在件混合 ⇒ 仍零投递（先于 `no actor system`）"):
    val tmp = os.temp.dir(prefix = "mailattach-mix")
    val ok = tmp / "ok.bin"
    os.write(ok, Array[Byte](1, 2, 3))
    try
      val msg = callErr(
        withArr(qIn("address" -> "backend", "message" -> "hi"), "attachments", ok.toString, (tmp / "gone.bin").toString),
        ctx()
      )
      assert(msg.contains("does not exist"), s"逐件判必须报出坏件: $msg")
      assert(msg != "No actor system available", s"附件闸必须先于投递腿判定（否则即静默面）: $msg")
    finally os.remove.all(tmp)

  // ============================================================
  // ④ B6：同机腿 `images` ⇒ 显式拒绝（治静默丢）
  // ============================================================

  /** `address` 腿的 `images` 走 G3 共用管线（先 `resolveImages` 落盘校验，再路由）⇒
    * 要真的走到「同机腿拒绝」，图件必须**真实存在**（虚构路径会先被 G3 的存在性判拦下）。 */
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

  test("④ B6：`project:` + images ⇒ 显式拒绝（裸项目名入口走同一单点）"):
    val tmp = os.temp.dir(prefix = "mailattach-b6b")
    val img = realImage(tmp)
    val system = ActorSystem(s"mailattach-b6b-${java.util.UUID.randomUUID().toString.take(6)}")
    try
      val msg = callErr(
        withArr(qIn("message" -> "hi"), "images", img.toString).add("address", Json.fromString("project:any")),
        ctx().copy(actorSystem = Some(system))
      )
      assert(msg.contains(MailTool.ErrVisionUnsupportedLeg), s"必须带显式拒码: $msg")
      assert(!msg.contains("is not mounted"), s"拒绝必须发生在挂载判定之前: $msg")
      assert(msg.contains("Nebula"), s"必须指出哪条腿能承载 vision: $msg")
    finally os.remove.all(tmp)

  test("④ B6 对照：同两条腿**不带** images ⇒ 既有判定逐字保留（本闸只咬 images 非空）"):
    val system = ActorSystem(s"mailattach-b6c-${java.util.UUID.randomUUID().toString.take(6)}")
    val node = callErr(
      qIn("address" -> "node:n-9", "message" -> "hi"),
      ctx(dispatcher = true).copy(actorSystem = Some(system))
    )
    assert(!node.contains(MailTool.ErrVisionUnsupportedLeg), s"无 images 不得命中本闸: $node")
    assert(node.contains("no project context"), s"既有判定须逐字保留: $node")
    val proj = callErr(
      qIn("address" -> "project:any", "message" -> "hi"),
      ctx().copy(actorSystem = Some(system))
    )
    assert(!proj.contains(MailTool.ErrVisionUnsupportedLeg), s"无 images 不得命中本闸: $proj")
    assert(proj.contains("is not mounted"), s"既有判定须逐字保留: $proj")

  // ============================================================
  // ⑥ B7：设备腿正文 4000 闸（含附注预算，前置于载荷构造）
  // ============================================================

  test("⑥ B7 边界：4000 字符过闸、4001 字符被拒（文案带实际长度与上限）"):
    assertEquals(MailTool.MaxDeviceMailTextChars, 4000, "服务端契约 MAX_TEXT_CHARS = 4000（唯一来源常量）")
    val atLimit = "x" * MailTool.MaxDeviceMailTextChars
    val over = "x" * (MailTool.MaxDeviceMailTextChars + 1)
    val passMsg = callErr(qIn("device" -> "KAI", "message" -> atLimit), ctx())
    assert(!passMsg.contains(MailTool.ErrDeviceMailTextTooLong), s"4000 恰在上限内，不得被拒: $passMsg")
    assert(passMsg.contains("Device messaging is unavailable"), s"过闸后应落到既有判定: $passMsg")
    val overMsg = callErr(qIn("device" -> "KAI", "message" -> over), ctx())
    assert(overMsg.contains(MailTool.ErrDeviceMailTextTooLong), s"4001 必须被拒: $overMsg")
    assert(overMsg.contains("4001"), s"必须回显实际长度: $overMsg")
    assert(overMsg.contains("4000"), s"必须回显上限: $overMsg")
    assert(overMsg.contains("Nothing was sent"), s"fail-fast 必须自陈零投递: $overMsg")

  test("⑥ B7 机制（plan 风险 2）：接近上限的正文 + 1 件附件 ⇒ 附注计入预算后即被拒"):
    val tmp = os.temp.dir(prefix = "mailattach-b7")
    val f = tmp / "note.bin"
    os.write(f, Array[Byte](1, 2, 3, 4))
    try
      val near = "x" * 3900
      val withoutAttach = callErr(qIn("device" -> "KAI", "message" -> near), ctx())
      assert(!withoutAttach.contains(MailTool.ErrDeviceMailTextTooLong),
        s"3900 无附件必须过闸（否则本判据的对照面不成立）: $withoutAttach")
      val withAttach = callErr(withArr(qIn("device" -> "KAI", "message" -> near), "attachments", f.toString), ctx())
      assert(withAttach.contains(MailTool.ErrDeviceMailTextTooLong),
        s"加附注后必须超预算即被拒（不得留到服务端 422）: $withAttach")
      assert(withAttach.contains("attachment note"), s"拒绝文案必须说明附注计入预算: $withAttach")
    finally os.remove.all(tmp)

  test("⑥ 设备腿承载通道不在场 ⇒ 显式拒绝（零字节；A-2 判据 ③「通道在场」）"):
    val tmp = os.temp.dir(prefix = "mailattach-chan")
    val f = tmp / "c.bin"
    os.write(f, Array[Byte](9))
    try
      val msg = callErr(withArr(qIn("device" -> "KAI", "message" -> "hi"), "attachments", f.toString), ctx())
      assert(msg.contains("file channel") && msg.contains("attachments"),
        s"通道不在场必须显式指名且先于任何投递: $msg")
      assert(!msg.contains("relay client is not initialized"), s"通道闸必须先于投递腿判定: $msg")
    finally os.remove.all(tmp)

  // ============================================================
  // ⑦ A5：relay 五键载荷一字不动
  // ============================================================

  test("⑦ A5：`DeviceMail` 契约恰五键、键序不变、同输入归一化逐字节相等"):
    assertEquals(DeviceMail.PayloadKeys.size, 5, "契约五键不可增删")
    val p = DeviceMail.payload("body", "devA", "idA")
    assertEquals(p.asObject.map(_.keys.toList), Some(DeviceMail.PayloadKeys), "键序须与契约书写顺序一致")
    assertEquals(
      p.noSpaces,
      """{"type":"agent_mail","from_device":"devA","from_device_id":"idA","to_nebula":true,"text":"body"}""",
      "四键取值 + 第五键名与值必须逐字节不变（本批零 wire 改动）"
    )

  // ============================================================
  // ⑤ A3：同机腿附注端到端（真 team fixture + RecordingLlm）
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
      s"""{"name": "${teamName}", "description": "mailattach fixture", "lead": "boss", "members": ["member"]}"""
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

  /** LLM 请求体全量文本（接受方会话实际看到的内容 = 附注真实到达的判据面）。 */
  private def reqText(r: LlmRequest): String =
    r.messages
      .map { m =>
        m.content match
          case Left(s)       => s
          case Right(blocks) => blocks.collect { case ContentBlock.Text(t) => t }.mkString("\n")
      }
      .mkString("\n")

  /** 轮询等待接受方 turn 落定（**有界等待**，不用固定 sleep：固定睡眠在连续 JVM 负载下会假红，
    * 而「等到了才断言」同时堵住负控的空集假绿 —— 空结果由调用方显式判红）。 */
  private def awaitRequests(llm: RecordingLlm, deadlineMs: Long): IO[List[LlmRequest]] =
    IO.defer {
      llm.requests.get.flatMap { rs =>
        if rs.nonEmpty || System.currentTimeMillis() >= deadlineMs then IO.pure(rs)
        else IO.sleep(150.millis) *> awaitRequests(llm, deadlineMs)
      }
    }

  test("⑤ A3 端到端：同机腿 `attachments` ⇒ 附注（绝对路径 + 字节数 + sha256）真实到达接受方会话；🔴 零搬字节"):
    val teamName = s"mailattach${java.util.UUID.randomUUID().toString.take(6)}"
    val system = ActorSystem(s"mailattach-e2e-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "mailattach-e2e")
    fixtureTeam(tmp, teamName)
    val llm = new RecordingLlm
    // 附件夹具：放在**非** worktree 的临时目录（路径即取件，不依赖仓内相对位置）
    val file = tmp / "report.bin"
    os.write(file, Array[Byte](7, 7, 7, 7, 7))
    val expectedSha = sha256Of(file)
    val expectedSize = os.size(file)

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      bossMeta <- sessionStore.createSession(s"$teamName/boss", agentName = Some("boss"), flowName = Some(teamName))
      memberMeta <- sessionStore.createSession(s"$teamName/member", agentName = Some("member"), flowName = Some(teamName))
      _ <- TeamSessionRegistry.registerSession(teamName, "boss", bossMeta.id)
      _ <- TeamSessionRegistry.registerSession(teamName, "member", memberMeta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      _ <- MailTool.activateAgent(memberMeta.id, resources, system, ctxFor(resources, system, bossMeta.id))
      res <- MailTool.call(
        withArr(qIn("address" -> "member", "message" -> "MAILATTACH_NOTE_MARKER"), "attachments", file.toString),
        ctxFor(resources, system, bossMeta.id)
      )
      reqs <- awaitRequests(llm, System.currentTimeMillis() + 8000)
      // 🔴 零搬字节：源件内容在投递后逐字节不变（附件只以「路径 + 读数」形式进文本）
      bytesAfter <- IO(os.read.bytes(file))
    yield (res, reqs, bytesAfter)

    val (res, reqs, bytesAfter) = io.unsafeRunSync()
    assert(res.isRight, s"同机腿带 attachments 必须成功（附注模式，零搬运）: $res")
    assert(reqs.nonEmpty, "🔴 判据前提：接受方 turn 必须落定（空集即判据无效，禁假绿）")
    val all = reqs.map(reqText).mkString("\n")
    assert(all.contains("MAILATTACH_NOTE_MARKER"), s"邮件正文必须到达接受方会话: ${all.take(400)}")
    assert(all.contains(file.toString), "🔴 附注必须带**绝对路径**（接受方按路径 Read 取件）")
    assert(all.contains(s"$expectedSize B"), s"附注必须带字节数读数（期望 $expectedSize B）")
    assert(all.contains(expectedSha), s"附注必须带发送时算出的 sha256（期望 $expectedSha）")
    assert(all.contains("[Mail 附件]"), "附注必须自陈「通用附件」形态（与图片附注同族词表）")
    assertEquals(bytesAfter.toList, List[Byte](7, 7, 7, 7, 7), "🔴 同机腿零搬字节：源件内容不得被改动")
    assertEquals(os.size(file), expectedSize, "🔴 源件大小不变")

  test("⑤ A3 对照：同机腿**不带** attachments ⇒ 正文不含附注（字节级零漂移）"):
    val teamName = s"mailattach${java.util.UUID.randomUUID().toString.take(6)}"
    val system = ActorSystem(s"mailattach-none-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "mailattach-none")
    fixtureTeam(tmp, teamName)
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      bossMeta <- sessionStore.createSession(s"$teamName/boss", agentName = Some("boss"), flowName = Some(teamName))
      memberMeta <- sessionStore.createSession(s"$teamName/member", agentName = Some("member"), flowName = Some(teamName))
      _ <- TeamSessionRegistry.registerSession(teamName, "boss", bossMeta.id)
      _ <- TeamSessionRegistry.registerSession(teamName, "member", memberMeta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      _ <- MailTool.activateAgent(memberMeta.id, resources, system, ctxFor(resources, system, bossMeta.id))
      res <- MailTool.call(
        qIn("address" -> "member", "message" -> "MAILATTACH_PLAIN_MARKER"),
        ctxFor(resources, system, bossMeta.id)
      )
      reqs <- awaitRequests(llm, System.currentTimeMillis() + 8000)
    yield (res, reqs)

    val (res, reqs) = io.unsafeRunSync()
    assert(res.isRight, s"无附件时既有路径必须照常: $res")
    // 🔴 负控必须有内容才判「不含附注」——空集下的 `!contains` 是假绿。
    assert(reqs.nonEmpty, "🔴 判据前提：接受方 turn 必须落定（空集即负控无效，禁假绿）")
    val all = reqs.map(reqText).mkString("\n")
    assert(all.contains("MAILATTACH_PLAIN_MARKER"), s"正文必须到达: ${all.take(400)}")
    assert(!all.contains("[Mail 附件]"), "未请求附件时不得出现附注（字节级零改动）")

end MailAttachSpec
