package nebflow.core.tools

import io.circe.JsonObject
import munit.FunSuite

/**
 * 设备面**接收语义**的机械对照（原「工具说明口径订正批」作者 2026-09-16 17:59 令，
 * mailunify-full 批 2026-09-23 re-pin）。
 *
 * 🔴 **本件的载体已换**：原件是**两面对照**（`Mail(device=…)` vs
 * `SendMessage(to="device:…")`）。合面后 `SendMessage` **退役** ⇒ 对照的**乙方**
 * （纯传输面）不复存在,设备面只剩**一面**（`Mail` 的 `device:` scheme）。
 * ⇒ 判据随之改向，但**强度不降**：
 *   - 原文「两面各自只带本支语义、禁互相借用」的极性判据 ⇒ 改为**单面内的历史极性**：
 *     被退役的「纯传输、对端 agent 不感知」语义**只能以已退役的历史形态出现**
 *     （必须显式注明它属于 former leg），**不得**被读成本腿的现行语义；
 *   - 原文「需要 agent 知道 ⇒ 用 `Mail`」的双向指引 ⇒ 改为单面内的**选靶指引**
 *     （「按谁应当知道来选靶，而不是按字节落在哪」）——这正是语义反转的落面。
 *
 * 判据全离线（零网络、零投递），读的都是**模型可见面**：`description`（基础 + 两个
 * 地址面变体）+ `inputSchema` 的参数级描述。地址面分段机制另有
 * `nebflow.agent.ToolFaceVariantSchemaSpec` 逐段断言（本 spec 不重复其判据面）。
 *
 * 断言一律走 `n(...)`（空白归一）——文案换行（≈80 列）不得成为判据的一部分。
 */
class DeviceMailFaceContractSpec extends FunSuite:

  /** 空白归一：换行/缩进塌成单空格 ⇒ 断言语义稳定于折行方式。 */
  private def n(s: String): String = s.replaceAll("\\s+", " ").trim

  /** Mail 的三个模型可见工具面（基础 + root 变体 + dispatcher 变体）。 */
  private val mailFaces: List[(String, String)] = List(
    "Mail.descriptionBase"       -> MailTool.descriptionBase,
    "Mail.descriptionNebulaRoot" -> MailTool.descriptionNebulaRoot,
    "Mail.descriptionDispatcher" -> MailTool.descriptionDispatcher
  )

  private def prop(schema: JsonObject, name: String): String =
    schema("properties")
      .flatMap(_.asObject)
      .flatMap(_(name))
      .flatMap(_.asObject)
      .flatMap(_("description"))
      .flatMap(_.asString)
      .getOrElse(fail(s"参数级 description 缺失：$name"))

  private val mailToParam: String      = prop(MailTool.inputSchema, "to")
  private val mailImagesParam: String  = prop(MailTool.inputSchema, "images")
  private val mailAttachParam: String  = prop(MailTool.inputSchema, "attachments")

  /** 定点取段（定位失败 = 文案被改动 ⇒ 本 spec 前提失效，显式红）。 */
  private def section(text: String, from: String, to: String): String =
    val a = text.indexOf(from)
    val b = text.indexOf(to)
    assert(a >= 0 && b > a, s"章节定位失败（判据前提失效）：'$from' … '$to'")
    text.substring(a, b)

  /** Mail 面的设备腿正文段（`## Device target` … `## \`local\``）。 */
  private val mailDeviceSection: String =
    n(section(MailTool.descriptionBase, "## Device target", "## `local`"))

  // ============================================================
  // ① 唯一存活面：对端 agent 直收
  // ============================================================

  test("① Mail 面（三变体）：`device:` 语义 = 投递进对端 Nebula 会话 + 对端 agent 直收"):
    for (label, raw) <- mailFaces do
      val face = n(raw)
      assert(face.contains("**The peer's agent receives it directly.**"),
        s"$label 缺「对端 agent 直收」独立断言句（作者口径 ①）")
      assert(face.contains("delivers *into the peer's"), s"$label 缺「投递进对端会话」")
      assert(face.contains("that device's AGENT reads the mail and can act on it"),
        s"$label 缺「对端 agent 能读到并处理」")
      assert(face.contains("[DEVICE-MAIL · from <from_device>]"),
        s"$label 缺既有无头注入体（直收的机制面）")
      assert(face.contains("it lands **directly in that device's Nebula session**"),
        s"$label 缺「落在对端 Nebula 会话」的机制句")

  // ============================================================
  // ② 🔴 退役面：纯传输 + 对端不感知 ⇒ 只能作为**历史形态**出现
  // ============================================================

  test("② 退役的纯传输语义：正文**在册**（如实写明历史），且一律归属于退役腿"):
    // 作者令（合面 §B-1.3 ①）：`device:` 的「纯传输、对端不知情」**退役**，且该变更
    // **须写明**（禁静默）——所以这段历史必须在模型可见面留下逐字读数。
    assert(mailDeviceSection.contains("REVERSED SEMANTICS"),
      "🔴 缺语义反转的显式声明（作者令：该变更须写明，禁静默）")
    assert(mailDeviceSection.contains("PURE TRANSPORT"),
      "缺「纯传输」这一历史定性（本批订正的核心，须如实登记）")
    assert(mailDeviceSection.contains("the peer's agent was NOT aware of either"),
      "🔴 缺「对端 agent 不感知」的历史读法（订正的核心）")
    assert(mailDeviceSection.contains("nothing entered its session") &&
      mailDeviceSection.contains("LLM context"),
      "缺「零注入对端 agent 会话 / LLM 上下文」的机制说明")
    assert(mailDeviceSection.contains("the frozen payload") || mailDeviceSection.contains("PURE TRANSPORT"),
      "设备腿的投递机制面在册")

  test("② 🔴 退役面：三处历史读法逐字**只**出现在「former leg」的叙述内（禁被读成现行语义）"):
    // 逐条判据：每一处历史读法之前，最近的「归属锚」必须是命名 former/retired 的语句。
    // 否则读者可把「不感知 / 纯传输」读成本工具的**现行**语义（= 口径混写）。
    val anchors = List("former", "retired", "REVERSED SEMANTICS", "Two facts changed")
    for phrase <- List("PURE TRANSPORT", "NOT aware", "nothing entered its session", "the peer's Downloads") do
      val idx = mailDeviceSection.indexOf(phrase)
      assert(idx >= 0, s"历史读法「$phrase」缺席")
      val head = mailDeviceSection.substring(0, idx)
      assert(
        anchors.exists(a => head.contains(a)),
        s"🔴 「$phrase」的归属锚缺席（近邻正文无 former/retired 声明）⇒ 可被读成现行语义"
      )
    // 反向：现行语义（直收）**不得**带任何「不感知」措辞。
    val directIdx = mailDeviceSection.indexOf("the mail enters the peer's Nebula session")
    assert(directIdx >= 0, "缺现行语义的收束句")
    assert(
      !mailDeviceSection.substring(directIdx).contains("NOT aware"),
      "🔴 现行语义段出现「不感知」= 与直收口径自相矛盾（原文极性判据的等价形态）"
    )

  test("② 退役面：能力丧失（目录请求）逐字写明，且明确「本腿无该能力」"):
    assert(mailDeviceSection.contains("LOST CAPABILITY"),
      "🔴 缺「能力丧失」的显式声明（作者令：`targetDir` 丧失须写明）")
    assert(mailDeviceSection.contains("no directory request on this leg") ||
      mailDeviceSection.contains("no way to request a directory"),
      "缺「本腿无法请求目录」的明确结论")
    assert(mailDeviceSection.contains("~/Downloads"),
      "缺「一律落对端缺省接收目录」的落点事实")

  // ============================================================
  // ③ 单面内的选靶指引（原文「需要 agent 知道 ⇒ 用 Mail」的等价形态）
  // ============================================================

  test("③ 选靶指引：按「谁应当知道」选靶（语义反转的落面，禁按字节落点选靶）"):
    for (label, raw) <- mailFaces do
      assert(n(raw).contains("pick the target by who should end up KNOWING about it"),
        s"$label 缺「按谁应当知道来选靶」的指引（语义反转的落面）")
    assert(
      mailDeviceSection.contains("that option no longer exists"),
      "缺「'悄悄搬字节'这一选项已不存在」的收束句（禁留错觉）"
    )

  // ============================================================
  // ④ 附件面现状如实（mailattach 批的判据**保留**，载体换到 `Mail`）
  // ============================================================

  test("④ Mail 附件面现状如实：`attachments` 已批（路线 A）⇒ schema 必须有、描述不得再宣称没有"):
    val keys = MailTool.inputSchema("properties").flatMap(_.asObject)
      .map(_.keys.toSet).getOrElse(fail("Mail schema has no properties"))
    assert(keys.contains("attachments"),
      "🔴 路线 A 已批（作者 2026-09-17）⇒ Mail 必须声明 `attachments` 参数")
    assert(keys.contains("images"), "Mail 必须保留 `images`（vision 面，未被 `attachments` 取代）")
    val maxItems = MailTool.inputSchema("properties").flatMap(_.asObject).flatMap(_("images"))
      .flatMap(_.asObject).flatMap(_("maxItems")).flatMap(_.asNumber).flatMap(_.toInt)
    assertEquals(maxItems, Some(5), "images 上限必须仍是 5（token 预算是另一个量纲）")
    for (label, raw) <- mailFaces do
      assert(!n(raw).contains("Mail has no general attachments"),
        s"$label 仍自称「无通用附件」= 描述与 schema（已有 `attachments`）互斥")
      assert(!n(raw).contains("no `attachments` parameter"),
        s"$label 仍自称「没有 `attachments` 参数」= 描述与 schema 互斥")
      assert(n(raw).contains("up to 5 absolute local image paths"),
        s"$label 缺 `images` 现状上限（≤5）")

  test("④ 描述与 schema 不再互斥（P1 机械判据：两面同契约、零反向断言）"):
    for (label, raw) <- mailFaces do
      assert(n(raw).contains("`attachments` parameter"),
        s"$label 未宣称 `attachments` 参数（模型读不到该能力 ⇒ 能力实际不可用）")
      assert(n(raw).contains("Same-machine targets") || n(raw).contains("SAME-MACHINE"),
        s"$label 缺同机腿（路径模式）语义说明")
      assert(n(raw).contains("4000"),
        s"$label 缺设备腿正文 4000 字符预算（B7 闸的模型可见面）")

  // ============================================================
  // ⑤ 参数级描述一致（模型可见契约的第二层）
  // ============================================================

  test("⑤ 参数级一致：`to` / `images` / `attachments` 三处同口径"):
    val mt = n(mailToParam)
    assert(mt.contains("`device:<deviceName|deviceId>`"),
      "`to` 参数级描述缺 device scheme 声明")
    assert(mt.contains("role-scoped"),
      "`to` 参数级描述缺角色域声明（授权面）")
    val mi = n(mailImagesParam)
    assert(mi.contains("DEVICE"), "images 参数级描述缺设备腿落点")
    assert(mi.contains("~/Downloads"), "images 参数级描述缺对端缺省接收目录")
    assert(mi.contains("NOT supported on `project:` / `node:`"),
      "images 参数级描述缺两条文本腿的显式拒绝声明")
    val ma = n(mailAttachParam)
    assert(ma.contains("SAME-MACHINE") || ma.contains("Same-machine"),
      "attachments 参数级描述缺同机腿语义")
    assert(ma.contains("sha256"), "attachments 参数级描述缺同机腿的 sha256 读数")
    assert(ma.contains("~/Downloads"), "attachments 参数级描述缺设备腿落点")

  // ============================================================
  // ⑥ 已退役工具零命中（模型可见面 + 两源文件整体）
  // ============================================================

  test("⑥ 已退役工具零命中：三面 + 参数级描述 + 两源文件整体（含注释面）"):
    for (label, raw) <- mailFaces do
      assert(!raw.contains("TransferFile"), s"$label 指向已退役工具")
    for (label, text) <- List(
        "Mail.to"          -> mailToParam,
        "Mail.images"      -> mailImagesParam,
        "Mail.attachments" -> mailAttachParam
      )
    do assert(!text.contains("TransferFile"), s"$label 参数级描述指向已退役工具")
    // `SendMessage` 名面：**允许**以「former / retired」形态出现（作者令：退役须写明），
    // 但**不得**以可调用形态出现（`SendMessage(to=…)` 的调用指引）。
    for (label, raw) <- mailFaces do
      val face = n(raw)
      val idx  = face.indexOf("SendMessage")
      if idx >= 0 then
        assert(
          face.contains("the former `Task`, `NodeMessage` and `SendMessage` tools are") ||
            face.contains("the former `SendMessage(to=\"device:…\")` leg was retired"),
          s"$label 提及 `SendMessage` 但未标注其为已退役 ⇒ 可被读成可用工具"
        )
      assert(
        !face.contains("use `SendMessage("),
        s"🔴 $label 仍给出 `SendMessage(...)` 的**调用**指引（退役工具不得有可调用形态）"
      )

    val root = os.pwd
    assert(os.exists(root / "src" / "main" / "scala"), s"本 spec 必须在仓根运行：$root")
    for rel <- List(
        "src/main/scala/nebflow/core/tools/MailTool.scala",
        "src/main/scala/nebflow/core/tools/FriendMessageTool.scala"
      )
    do
      val src = os.read(root / os.RelPath(rel))
      assert(!src.contains("TransferFile"), s"$rel 仍命中已退役工具名（含注释面）")

end DeviceMailFaceContractSpec
