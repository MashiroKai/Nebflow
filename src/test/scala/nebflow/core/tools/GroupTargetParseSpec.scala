package nebflow.core.tools

import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite

/**
 * SendMessage `to` 参数的**群寻址面**（gmsgsend 批，2026-09-15 · 补充卡 §6.1/§6.3）。
 *
 * 本 spec 只钉两件**纯函数/静态**判据（端到端判据在
 * `nebflow.neblink.GroupSendMessageSpec`）：
 *  ① `parseToKind` 的五形态分派 + **既有四形态回归零变**（`friend:`/`device:`/裸串/
 *     `local`），以及 `group:` 的**遮蔽边界**（§6.3：`group:xxx` 不再按好友解析，
 *     逃生口 = 显式 `friend:` 前缀）；
 *  ② `inputSchema.properties.to.description` **显式**声明 `group:`（作者令：能力必须
 *     落在 schema/描述层——字段说明 + 合法前缀 + 错误语义逐面写清，禁靠运行时错误兜）。
 *
 * 为什么本 spec 在 `nebflow.core.tools` 包：`ToKind` / `parseToKind` 是
 * `private[tools]`（工具内部文法，不外泄）⇒ 只有同包可见。
 */
class GroupTargetParseSpec extends FunSuite:

  import FriendMessageTool.ToKind

  // ── ① 分派与遮蔽边界 ─────────────────────────────────────

  test("parseToKind: `group:<名|id>` 分派到群支，rest = 前缀后的原始串") {
    assertEquals(FriendMessageTool.parseToKind("group:团队"), Right(ToKind.Group("团队")))
    assertEquals(FriendMessageTool.parseToKind("group:grp-1"), Right(ToKind.Group("grp-1")))
    // scheme 大小写不敏感 + 两侧空白 trim（与 `friend:`/`device:` 同一条既有口径）
    assertEquals(FriendMessageTool.parseToKind("  GROUP : 团队  "), Right(ToKind.Group("团队")))
    // rest 里的冒号原样保留（群名可含冒号；与好友支「冒号合法」口径同族）
    assertEquals(FriendMessageTool.parseToKind("group:team:prod"), Right(ToKind.Group("team:prod")))
  }

  test("parseToKind: `group:` 残缺前缀 ⇒ 显式错误（与 friend:/device: 文案同构，不回落好友）") {
    val emptyGroup = FriendMessageTool.parseToKind("group:").left.toOption
    assert(emptyGroup.isDefined, "`group:` 必须报错，不得静默按好友解析")
    assert(
      emptyGroup.get.contains("missing the group name/id after `group:`"),
      s"文案必须是同族残缺前缀错误，实际 = ${emptyGroup.get}"
    )
    assert(FriendMessageTool.parseToKind("group:   ").isLeft, "全空白 rest 同为空")
    // 同族既有两形态的文案未变（回归基线）
    assertEquals(
      FriendMessageTool.parseToKind("friend:").left.toOption.map(_.nonEmpty),
      Some(true)
    )
    assertEquals(
      FriendMessageTool.parseToKind("device:").left.toOption.map(_.nonEmpty),
      Some(true)
    )
  }

  test("回归零变：既有四形态（裸串 / friend: / device: / local）解析逐字不动") {
    assertEquals(FriendMessageTool.parseToKind("local"), Right(ToKind.Local))
    assertEquals(FriendMessageTool.parseToKind("LOCAL"), Right(ToKind.Local)) // :223 整串忽略大小写
    assertEquals(FriendMessageTool.parseToKind("林小满"), Right(ToKind.Friend("林小满")))
    assertEquals(FriendMessageTool.parseToKind("lin@example.com"), Right(ToKind.Friend("lin@example.com")))
    assertEquals(FriendMessageTool.parseToKind("friend:林小满"), Right(ToKind.Friend("林小满")))
    assertEquals(FriendMessageTool.parseToKind("FRiend:林小满"), Right(ToKind.Friend("林小满")))
    assertEquals(FriendMessageTool.parseToKind("device:MacBook"), Right(ToKind.Device("MacBook")))
    // 🔴 既有遮蔽边界之一：未知 scheme 带冒号 ⇒ 原样按好友解析（备注/邮箱可能含冒号）
    assertEquals(FriendMessageTool.parseToKind("a:b"), Right(ToKind.Friend("a:b")))
    assert(FriendMessageTool.parseToKind("").isLeft, "空串仍是 'to' is empty")
  }

  test("§6.3 遮蔽边界：`group:` 前缀加入后，字面 `group:xxx` 不再寻址好友——逃生口 = 显式 friend:") {
    // 新增的遮蔽：字面以 `group:` 开头的好友备注/用户名，裸串寻址已被本批遮蔽。
    assertEquals(FriendMessageTool.parseToKind("group:同事").isRight, true)
    assertEquals(FriendMessageTool.parseToKind("group:同事").map(_.getClass.getSimpleName), Right("Group"))
    // 逃生口（回归面）：显式 `friend:` 前缀仍原样取 rest ⇒ 该好友仍可达。
    assertEquals(FriendMessageTool.parseToKind("friend:group:同事"), Right(ToKind.Friend("group:同事")))
    // 既有遮蔽（`local` 整串）未被本批改变——两条遮蔽并列存在、互不影响。
    assertEquals(FriendMessageTool.parseToKind("Local"), Right(ToKind.Local))
    assertEquals(FriendMessageTool.parseToKind("friend:local"), Right(ToKind.Friend("local")))
  }

  // ── ② schema / 描述层（作者令：能力落在 schema 面，不靠运行时错误兜）──

  private def toDescription: String =
    FriendMessageTool.inputSchema("properties").flatMap(_.hcursor.downField("to").downField("description").as[String].toOption)
      .getOrElse(fail("inputSchema.properties.to.description 缺席"))

  test("schema: `to.description` 显式声明四个合法前缀（含 `group:`）") {
    val d = toDescription
    assert(d.contains("`friend:<remark|username|email|displayName>`"), s"缺 friend 前缀声明: $d")
    assert(d.contains("`device:<deviceName|deviceId>`"), s"缺 device 前缀声明: $d")
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
    assert(d.contains("case-insensitive") || d.contains("case-insensitive"), s"缺前缀大小写口径: $d")
  }

  test("schema: description 面声明四类目标 + `group:` 的遮蔽后果（§6.3 变更说明）") {
    val d = FriendMessageTool.description
    assert(d.contains("Four target kinds"), s"目标类数必须是 Four（本批加性扩面）: ${d.take(120)}")
    assert(d.contains("`group:<groupName|groupId>`"), "description 缺第 4 类目标声明")
    assert(d.contains("MUST be one of `friend:`, `device:`, `group:`"), s"缺合法前缀集声明: $d")
    assert(
      d.contains("explicit `friend:` prefix"),
      "🔴 §6.3 要求把 `group:` 的遮蔽后果写进描述面（否则模型永远试不出逃生口）"
    )
    assert(d.contains("local` has shadowed a friend of that name"), "既有 local 遮蔽作为同类先例同句声明")
  }

  test("schema: required 面零变更（to + message；本批不新增必填键）") {
    val req = FriendMessageTool.inputSchema("required").flatMap(_.asArray).getOrElse(Vector.empty)
      .map(_.asString.getOrElse("")).toSet
    assertEquals(req, Set("to", "message"))
  }

  test("schema: `message` 描述补上群支空文本口径（群总是要正文）") {
    val d = FriendMessageTool.inputSchema("properties").flatMap(_.hcursor.downField("message").downField("description").as[String].toOption)
      .getOrElse(fail("message description 缺席"))
    assert(d.contains("friend/device/group targets"), s"message 描述未覆盖群支: $d")
    assert(d.contains("a group target always requires non-empty text"), s"缺群支空文本口径: $d")
  }

end GroupTargetParseSpec
