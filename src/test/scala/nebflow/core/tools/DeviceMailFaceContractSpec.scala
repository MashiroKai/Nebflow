package nebflow.core.tools

import io.circe.JsonObject
import munit.FunSuite

/**
 * 工具说明口径订正批（作者 2026-09-16 17:59 令）——`Mail(device=…)` vs
 * `SendMessage(to="device:…")` 的**接收语义**（定义/提示词面）机械对照。
 *
 * 钉死口径（逐句实现）：
 *   ① `Mail(device=…)` = 投递进**对端 Nebula 会话**、**对端 agent 直收**（能读到并处理）；
 *   ② `SendMessage(to="device:…")` = **纯传输**（文件落对端 Downloads、文本进设备面板），
 *      🔴 **对端 agent 不感知**；
 *   ③ **需要 agent 知道 ⇒ 用 `Mail`** —— 该指引**两面都出现**且不得互相矛盾；
 *   ④ 现状如实：Mail 附件面 = 仅 `images`（≤5）、**无通用附件**（B 件未批 ⇒ 禁承诺）。
 *
 * 判据全离线（零网络、零投递），读的都是**模型可见面**：`description`（基础 + 两个地址面
 * 变体）+ 两侧 `inputSchema` 的参数级描述。地址面分段机制另有
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

  private val sendFace: String = FriendMessageTool.description

  private def prop(schema: JsonObject, name: String): String =
    schema("properties")
      .flatMap(_.asObject)
      .flatMap(_(name))
      .flatMap(_.asObject)
      .flatMap(_("description"))
      .flatMap(_.asString)
      .getOrElse(fail(s"参数级 description 缺失：$name"))

  private val mailDeviceParam: String = prop(MailTool.inputSchema, "device")
  private val mailImagesParam: String = prop(MailTool.inputSchema, "images")
  private val sendToParam: String = prop(FriendMessageTool.inputSchema, "to")
  private val sendAttachParam: String = prop(FriendMessageTool.inputSchema, "attachments")

  /** 定点取段（定位失败 = 文案被改动 ⇒ 本 spec 前提失效，显式红）。 */
  private def section(text: String, from: String, to: String): String =
    val a = text.indexOf(from)
    val b = text.indexOf(to)
    assert(a >= 0 && b > a, s"章节定位失败（判据前提失效）：'$from' … '$to'")
    text.substring(a, b)

  /** Mail 面的设备腿正文段（在两面语义对照块之前）。 */
  private val mailDeviceSection: String =
    n(section(MailTool.descriptionBase, "## Device target", "## `Mail` vs `SendMessage`"))

  /** Mail 面的两面语义对照块（在地址面之前 ⇒ 三个变体共有）。 */
  private val mailCompareSection: String =
    n(section(MailTool.descriptionBase, "## `Mail` vs `SendMessage`", "## Address face"))

  private val mailBulletInCompare: String =
    section(mailCompareSection, "- **`Mail(device=…)`", "- **`SendMessage(to=")

  private val sendBulletInCompare: String =
    mailCompareSection.substring(mailCompareSection.indexOf("- **`SendMessage(to="))

  /** SendMessage 面第 2 类目标（设备腿）段落本体。 */
  private val sendDeviceItem: String =
    n(section(sendFace, "2. Another of the user's own devices", "\n\nTarget-kind prefixes"))

  // ============================================================
  // ① Mail 面：对端 agent 直收
  // ============================================================

  test("① Mail 面（三变体）：`device=` 语义 = 投递进对端 Nebula 会话 + 对端 agent 直收"):
    for (label, raw) <- mailFaces do
      val face = n(raw)
      assert(face.contains("**The peer's agent receives it directly.**"),
        s"$label 缺「对端 agent 直收」独立断言句（作者口径 ①）")
      assert(face.contains("delivers *into the peer's Nebula session*"),
        s"$label 缺「投递进对端 Nebula 会话」")
      assert(face.contains("- **`Mail(device=…)` — the peer's AGENT receives it directly**"),
        s"$label 对照块缺 Mail 支的直收口径")
      assert(face.contains("that device's AGENT reads the mail and can act on it"),
        s"$label 缺「对端 agent 能读到并处理」")
      assert(face.contains("[DEVICE-MAIL · from <from_device>]"),
        s"$label 缺既有无头注入体（直收的机制面）")

  // ============================================================
  // ② SendMessage 面：纯传输 + 对端 agent 不感知
  // ============================================================

  test("② SendMessage 面：`device:` = 纯传输（Downloads + 设备面板）且 🔴 对端 agent 不感知"):
    assert(sendDeviceItem.contains("PURE TRANSPORT"),
      "缺「纯传输」定性（作者口径 ②）")
    assert(sendDeviceItem.contains("the peer's AGENT is NOT aware of either"),
      "🔴 缺「对端 agent 不感知」——本批订正的核心")
    assert(sendDeviceItem.contains("peer's Downloads"),
      "缺「文件落对端 Downloads」")
    assert(sendDeviceItem.contains("the message text appears in the peer's device panel"),
      "缺「文本进设备面板」")
    assert(sendDeviceItem.contains("nothing is injected into the peer's agent session or its LLM context"),
      "缺「零注入对端 agent 会话 / LLM 上下文」的机制说明")

  test("② SendMessage 面：文件传输能力留在本面（attachments 是唯一搬运参数）"):
    assert(sendDeviceItem.contains("the face that carries file transfer (`attachments`)"),
      "缺「文件传输能力在本面」的自陈")
    val keys = FriendMessageTool.inputSchema("properties").flatMap(_.asObject)
      .map(_.keys.toSet).getOrElse(fail("SendMessage schema has no properties"))
    assert(keys.contains("attachments"), "SendMessage 必须保留 attachments（文件搬运能力所在）")
    assert(n(sendAttachParam).contains("how a file is moved"), "attachments 参数级描述缺搬运定位")

  // ============================================================
  // ③ 指引双向：需要 agent 知道 ⇒ 用 Mail
  // ============================================================

  test("③ 指引两面同现：`If the peer's agent must be told, use Mail` 在 Mail 面与 SendMessage 面"):
    for (label, raw) <- mailFaces do
      assert(n(raw).contains("If the peer's agent must be told, use `Mail`"),
        s"$label 缺「需要 agent 知道 ⇒ 用 Mail」指引")
    assert(n(sendFace).contains("If the peer's agent must be told, use `Mail`"),
      "SendMessage 面缺「需要 agent 知道 ⇒ 用 Mail」指引")
    assert(sendDeviceItem.contains("use `Mail` with the `device` parameter"),
      "SendMessage 面必须把落点写全（Mail 的 `device` 参数），否则模型选不出正确形态")

  test("③ 双向点名：Mail 面点名 SendMessage、SendMessage 面点名 Mail（禁单向口径）"):
    for (label, raw) <- mailFaces do
      assert(n(raw).contains("""`SendMessage(to="device:…")` — pure transport"""),
        s"$label 缺对 SendMessage 的对比句")
    assert(sendDeviceItem.contains("Mail delivers into the peer's Nebula session"),
      "SendMessage 面缺 Mail 的落点说明")
    assert(sendDeviceItem.contains("`SendMessage` never reaches the peer's agent"),
      "SendMessage 面缺「本工具永不达对端 agent」的收束句")

  // ============================================================
  // ④ 极性双向断言（禁矛盾）
  // ============================================================

  test("④ 极性：对照块两支各自只带本支语义（禁互相借用）"):
    assert(mailBulletInCompare.contains("AGENT receives it directly"), "Mail 支缺直收语义")
    assert(!mailBulletInCompare.contains("NOT aware"),
      "🔴 Mail 支出现「不感知」= 口径混写")
    assert(sendBulletInCompare.contains("pure transport") && sendBulletInCompare.contains("NOT aware"),
      "SendMessage 支缺「纯传输 + 不感知」")
    assert(!sendBulletInCompare.contains("receives it directly"),
      "🔴 SendMessage 支出现直收语义 = 口径混写")
    assert(!mailDeviceSection.contains("NOT aware"),
      "🔴 Mail 的设备腿正文出现「不感知」= 与直收口径自相矛盾")

  test("④ 极性：SendMessage 面唯一出现的直收语义必须归属 Mail（带否证句）"):
    val idx = sendDeviceItem.indexOf("receives it directly")
    assert(idx >= 0, "SendMessage 面缺 Mail 直收语义（对比面不完整）")
    assert(sendDeviceItem.substring(0, idx).contains("use `Mail`"),
      "直收语义在 SendMessage 面未归属 Mail（禁无主语断言）")
    assert(sendDeviceItem.substring(idx).contains("`SendMessage` never reaches the peer's agent"),
      "直收语义后缺本工具的否证句（否则可被读成 SendMessage 也直收）")

  // ============================================================
  // ⑤ 现状如实：无通用附件（禁承诺未实现能力）
  // ============================================================

  test("⑤ Mail 附件面现状如实：仅 `images`（≤5）、无通用附件（B 件未批，禁承诺）"):
    val keys = MailTool.inputSchema("properties").flatMap(_.asObject)
      .map(_.keys.toSet).getOrElse(fail("Mail schema has no properties"))
    assert(!keys.contains("attachments"),
      "🔴 Mail 出现 `attachments` 参数 = 承诺未实现能力（B 件未批）")
    assert(keys.contains("images"), "Mail 必须保留 `images`（现状附件面）")
    val maxItems = MailTool.inputSchema("properties").flatMap(_.asObject).flatMap(_("images"))
      .flatMap(_.asObject).flatMap(_("maxItems")).flatMap(_.asNumber).flatMap(_.toInt)
    assertEquals(maxItems, Some(5), "images 上限必须仍是 5")
    for (label, raw) <- mailFaces do
      assert(n(raw).contains("Mail has no general attachments"),
        s"$label 缺「无通用附件」的现状声明")
      assert(n(raw).contains("up to 5 absolute local image paths"),
        s"$label 缺 `images` 现状上限（≤5）")
    assert(n(mailImagesParam).contains("NO general attachments"),
      "images 参数级描述缺「无通用附件」")
    assert(n(mailImagesParam).contains("max 5"), "images 参数级描述缺件数上限")

  // ============================================================
  // ⑥ 参数级描述一致（模型可见契约的第二层）
  // ============================================================

  test("⑥ 参数级一致：Mail.device / SendMessage.to / SendMessage.attachments 三处同口径"):
    val md = n(mailDeviceParam)
    assert(md.contains("AGENT receives this mail directly"),
      "Mail 的 `device` 参数级描述缺直收语义")
    assert(md.contains("pure transport") && md.contains("use `Mail`"),
      "Mail 的 `device` 参数级描述缺 SendMessage 对比 + Mail 落点指引")
    val st = n(sendToParam)
    assert(st.contains("pure transport") && st.contains("NOT aware"),
      "SendMessage 的 `to` 参数级描述缺「纯传输 + 不感知」")
    assert(st.contains("use `Mail`"), "SendMessage 的 `to` 参数级描述缺 Mail 指引")
    assert(n(sendAttachParam).contains("NOT told"),
      "SendMessage 的 `attachments` 参数级描述缺「对端 agent 不被通知」")
    assert(n(mailImagesParam).contains("`SendMessage`'s `attachments`"),
      "Mail 的 `images` 参数级描述缺「其它文件走 SendMessage」的落点")

  // ============================================================
  // ⑦ 已退役工具零命中（模型可见面 + 两源文件整体）
  // ============================================================

  test("⑦ 已退役工具零命中：两面模型可见面 + 两源文件整体"):
    for (label, raw) <- mailFaces do
      assert(!raw.contains("TransferFile"), s"$label 指向已退役工具")
    assert(!sendFace.contains("TransferFile"), "SendMessage 描述面指向已退役工具")
    for (label, text) <- List(
        "Mail.device"             -> mailDeviceParam,
        "Mail.images"             -> mailImagesParam,
        "SendMessage.to"          -> sendToParam,
        "SendMessage.attachments" -> sendAttachParam
      )
    do assert(!text.contains("TransferFile"), s"$label 参数级描述指向已退役工具")

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
