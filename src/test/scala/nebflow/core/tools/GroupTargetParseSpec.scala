package nebflow.core.tools

import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite

/**
 * `Mail` 单 `to` 参数的**群寻址面**（原 `SendMessage` 的群支，mailunify-full 批
 * 2026-09-23 并入 `Mail`）；本文件原钉 `SendMessage.parseToKind` 的五形态分派，
 * 现按合面后的**统一目标分类谓词** `MailTool.classifyTarget` 重钉（纯函数）。
 *
 * 本 spec 只钉两件**纯函数/静态**判据（端到端判据在
 * `nebflow.neblink.GroupSendMessageSpec`）：
 *  ① `classifyTarget` 的分派 + **既有四形态回归零变**（`friend:`/`device:`/裸串/
 *     `local`），以及 `group:` 的**遮蔽边界**（逃生口 = 显式 `friend:` 前缀）；
 *     🔴 **新增判据**（合面硬要求 §B-1.4）：**未知 scheme 必须 fail-closed** ——
 *     旧 `SendMessage.parseToKind` 对未知前缀**静默按好友解析**，分类学扩面后
 *     该兜底即静默重定向风险 ⇒ 已废（`a:b` 由 `Right(Friend)` 反转为 `Left`）。
 *  ② `inputSchema.properties.to.description` **显式**声明合法 scheme 面（作者令：
 *     能力必须落在 schema/描述层——字段说明 + 合法前缀 + 错误语义逐面写清，
 *     禁靠运行时错误兜）。
 *
 * 为什么本 spec 在 `nebflow.core.tools` 包：`Target` / `classifyTarget` 是
 * `private[tools]`（工具内部文法，不外泄）⇒ 只有同包可见。
 */
class GroupTargetParseSpec extends FunSuite:

  import MailTool.Target

  private def kind(raw: String): Either[ToolError, Target] = MailTool.classifyTarget(raw)

  // ── ① 分派与遮蔽边界 ─────────────────────────────────────

  test("classifyTarget: `group:<名|id>` 分派到群支，rest = 前缀后的原始串") {
    assertEquals(kind("group:团队"), Right(Target.Group("团队")))
    assertEquals(kind("group:grp-1"), Right(Target.Group("grp-1")))
    // scheme 大小写不敏感 + 两侧空白 trim（与 `friend:`/`device:` 同一条既有口径）
    assertEquals(kind("  GROUP : 团队  "), Right(Target.Group("团队")))
    // rest 里的冒号原样保留（群名可含冒号）
    assertEquals(kind("group:team:prod"), Right(Target.Group("team:prod")))
  }

  test("classifyTarget: `group:` 残缺前缀 ⇒ 显式错误（与 friend:/device: 文案同构，不回落好友）") {
    val emptyGroup = kind("group:").left.toOption
    assert(emptyGroup.isDefined, "`group:` 必须报错，不得静默按好友解析")
    assert(
      emptyGroup.get.message.contains("missing the group name/id after `group:`"),
      s"文案必须是同族残缺前缀错误，实际 = ${emptyGroup.get.message}"
    )
    assert(kind("group:   ").isLeft, "全空白 rest 同为空")
    // 同族既有两形态的文案未变（回归基线）
    assertEquals(kind("friend:").left.toOption.map(_.message.nonEmpty), Some(true))
    assertEquals(kind("device:").left.toOption.map(_.message.nonEmpty), Some(true))
  }

  test("回归零变：既有四形态（裸串 / friend: / device: / local）解析逐字不动") {
    assertEquals(kind("local"), Right(Target.Local))
    assertEquals(kind("LOCAL"), Right(Target.Local)) // 整串忽略大小写
    assertEquals(kind("林小满"), Right(Target.Face("林小满")))
    assertEquals(kind("lin@example.com"), Right(Target.Face("lin@example.com")))
    assertEquals(kind("friend:林小满"), Right(Target.Friend("林小满")))
    assertEquals(kind("FRiend:林小满"), Right(Target.Friend("林小满")))
    // 🔴 支上对账重算批（2026-09-26 调和）re-pin：`device:` scheme 已随 main 侧
    // mailmodel 批 (e) 整腿退役 —— 原 `Right(Target.Device("MacBook"))` 改钉为
    // 统一墓碑读 MAIL_DEVICE_RETIRED（classifyTarget case "device"，先于一切解析）。
    val dev = kind("device:MacBook").left.toOption
    assert(dev.isDefined, "device: 形态必须显式报错（腿已退役，禁静默投递）")
    assert(dev.get.message.contains(MailTool.ErrDeviceLegRetired), s"墓碑码必须命中: ${dev.get.message}")
    assert(kind("").isLeft, "空串仍是 target missing")
  }

  test("🔴 §B-1.4 反转：未知 scheme 必须 fail-closed（旧 `a:b` 静默按好友解析 ⇒ 现已废）") {
    // 旧形态（已废）：`parseToKind("a:b")` = Right(Friend("a:b"))（静默重定向）。
    // 新形态：显式错误 + 列出合法 scheme 面。
    val err = kind("a:b").left.toOption
    assert(err.isDefined, "未知 scheme 必须显式报错，不得按好友解析")
    assert(err.get.message.contains("unknown target scheme 'a:'"), s"文案须指明未知 scheme：${err.get.message}")
    assert(err.get.message.contains("project:"), s"文案须列出合法 scheme 面：${err.get.message}")
    assert(err.get.message.contains("node:"), s"文案须列出合法 scheme 面：${err.get.message}")
    // 🔴 支上对账重算批（2026-09-26 调和）：合法 scheme 词表已随设备腿退役去 "device:"
    // 并入 "kernel:"（KnownSchemes 单点）——原 `contains("device:")` 判据随词表消亡，
    // 改钉新词表的 kernel 项与 device 的退役去留（退役说明在 schema/描述层，不在本报错）。
    assert(err.get.message.contains("kernel:"), s"文案须列出合法 scheme 面（kernel）: ${err.get.message}")
    assert(!err.get.message.contains("device:"), "退役 scheme 不得再出现在合法面词表: ${err.get.message}")
    assert(err.get.message.contains("friend:"), s"文案须列出合法 scheme 面：${err.get.message}")
    assert(err.get.message.contains("group:"), s"文案须列出合法 scheme 面：${err.get.message}")
    assert(err.get.message.contains("`local`"), s"文案须列出保留字面量：${err.get.message}")
    // 含冒号的裸地址（`project:` / `node:`）不受影响 —— 走角色地址面。
    assertEquals(kind("project:x"), Right(Target.Face("project:x")))
    assertEquals(kind("node:n-1"), Right(Target.Face("node:n-1")))
  }

  test("遮蔽边界：`group:` 前缀下字面 `group:xxx` 不再寻址好友——逃生口 = 显式 friend:") {
    // 新增的遮蔽：字面以 `group:` 开头的好友备注/用户名，裸串寻址已被遮蔽。
    assertEquals(kind("group:同事").isRight, true)
    assertEquals(kind("group:同事").map(_.getClass.getSimpleName), Right("Group"))
    // 逃生口（回归面）：显式 `friend:` 前缀仍原样取 rest ⇒ 该好友仍可达。
    assertEquals(kind("friend:group:同事"), Right(Target.Friend("group:同事")))
    // 既有遮蔽（`local` 整串）未被本批改变——两条遮蔽并列存在、互不影响。
    assertEquals(kind("Local"), Right(Target.Local))
    assertEquals(kind("friend:local"), Right(Target.Friend("local")))
  }

  // ── ② schema / 描述层（作者令：能力落在 schema 面，不靠运行时错误兜）──

  private def toDescription: String =
    MailTool.inputSchema("properties").flatMap(_.hcursor.downField("to").downField("description").as[String].toOption)
      .getOrElse(fail("inputSchema.properties.to.description 缺席"))

  test("schema: `to.description` 显式声明合法前缀（含 `group:`）与 device: 的退役声明") {
    val d = toDescription
    assert(d.contains("`friend:<remark|username|email|displayName>`"), s"缺 friend 前缀声明: $d")
    // 🔴 支上对账重算批（2026-09-26 调和）re-pin：`device:` 已退役 —— schema 面不再
    // 声明其参数形态，改为**显式退役声明**（RETIRED + MAIL_DEVICE_RETIRED，禁静默）。
    assert(!d.contains("`device:<deviceName|deviceId>`"), s"退役前缀的参数形态声明必须移除: $d")
    assert(d.contains("RETIRED"), s"缺 device: 退役声明: $d")
    assert(d.contains(MailTool.ErrDeviceLegRetired), s"缺墓碑码声明: $d")
    assert(d.contains("`group:<groupName|groupId>`"), s"🔴 缺 group 前缀声明（本批判据②）: $d")
    assert(d.contains("`local`"), s"缺 local 形态声明: $d")
  }

  test("schema: `to.description` 写明群支的解析序与错误语义（禁靠运行时错误兜）") {
    val d = toDescription
    assert(d.contains("exact group id"), s"缺 L1 群 id 精确: $d")
    assert(d.contains("exact group name"), s"缺 L2 群名精确: $d")
    assert(d.contains("unique group-name prefix"), s"缺 L3 群名前缀: $d")
    assert(d.contains("does not exist"), s"缺「群不存在」错误语义: $d")
    assert(d.contains("disbanded"), s"缺「已解散」错误语义: $d")
    assert(d.contains("not a member"), s"缺「非成员」错误语义: $d")
    assert(d.contains("candidate list"), s"缺多命中/零命中的候选列表语义: $d")
    assert(d.contains("no `attachments`"), s"缺一期纯文本声明（群 + 附件 ⇒ 不静默丢弃）: $d")
    assert(d.contains("case-insensitive"), s"缺前缀大小写口径: $d")
  }

  test("schema: `to.description` 声明未知 scheme 的 fail-closed 语义（§B-1.4 落点）") {
    val d = toDescription
    assert(d.contains("unknown scheme is an explicit error"), s"缺未知 scheme fail-closed 声明: $d")
    assert(d.contains("silent reinterpretation"), s"缺「禁静默重定向」声明: $d")
    assert(d.contains("escape hatch"), s"缺含冒号好友名的逃生口声明: $d")
  }

  test("schema: description 面声明目标类 + `group:` 的遮蔽后果（§6.3 变更说明）") {
    val d = MailTool.description
    assert(d.contains("`group:<groupName|groupId>`"), "description 缺群类目标声明")
    assert(d.contains("`friend:` / `group:`"), "description 缺 friend/group 目标面段")
    // 🔴 §6.3 的落点：遮蔽后果与逃生口语义必须落在**工具级 `description`**（模型读的第一面），
    // 不能只写在 `to` 参数的 schema description 里。本批已把该句补进工具级描述
    // （「Scheme resolution (mechanical, fail-closed).」段）——断言指向工具级面。
    assert(
      d.contains("escape hatch"),
      s"🔴 §6.3 要求把 `group:` 的遮蔽后果写进工具级描述面（否则模型永远试不出逃生口）"
    )
    assert(
      d.contains("`friend:` prefix"),
      "🔴 逃生口的具体形态（显式 `friend:` 前缀）必须在工具级描述面在册"
    )
    assert(d.contains("no longer addressable as a bare string") || d.contains("shadowed"),
      "🔴 遮蔽后果必须显式写明（字面以 `group:` 开头的好友名不再可作裸串寻址）"
    )
    assert(d.contains("local"), "`local` 保留字面量须在描述面在册")
  }

  test("schema: required 面 = to + message（合面后必填面）") {
    val req = MailTool.inputSchema("required").flatMap(_.asArray).getOrElse(Vector.empty)
      .map(_.asString.getOrElse("")).toSet
    assertEquals(req, Set("to", "message"))
  }

  test("schema: `message` 描述补上群支空文本口径（群总是要正文）") {
    val d = MailTool.inputSchema("properties").flatMap(_.hcursor.downField("message").downField("description").as[String].toOption)
      .getOrElse(fail("message description 缺席"))
    // 🔴 支上对账重算批（2026-09-26 调和）：设备腿退役 ⇒ 原 "friend/device/group"
    // 三腿措辞改钉为 "friend/group"（合并树现读）。
    assert(d.contains("friend/group"), s"message 描述未覆盖群支: $d")
    assert(!d.contains("friend/device/group"), "退役设备腿不得残留在 message 描述判据: $d")
    assert(d.contains("ignored for `local`"), s"缺 local 忽略正文口径: $d")
  }

end GroupTargetParseSpec
