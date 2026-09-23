package nebflow.core.project

import munit.FunSuite
import nebflow.agent.InjectionAttribution

/**
 * 气泡四段式统一批（2026-09-15，作者 12:33 令）· 件① 快照测试：
 * **引擎侧唯一格式化函数** [[NotificationHeader]] 的逐字断言。
 *
 * 覆盖要求（任务书逐字）：三源（CHAIN / NODE / MAIL）+ 考古发现的**其他源一律纳入**。
 * 判据落法：
 *   1. 三源逐字 —— 每条断言写死整串（含分隔符 ` · ` 与四段全大写形态）；
 *   2. 其他源 —— `KindLabels.keySet == InjectionAttribution.BackendNamedSources`
 *      **硬门**（后端加新源而词表落后即红，同 `InjectionSourceContractSpec` 对前端表的
 *      纪律）+ 逐源 K 段逐字断言；
 *   3. 回落面 —— 词表外 source ⇒ `None`（帧不带 header，前端回落既有渲染）；
 *   4. 空段跳过 —— 不产生双分隔符 / 首尾分隔符。
 */
class NotificationHeaderSpec extends FunSuite:

  private def h(
    src: String,
    intake: Option[String] = None,
    project: Option[String] = None,
    sender: Option[String] = None,
    team: Option[String] = None,
    et: Option[String] = None
  ): Option[String] = NotificationHeader.header(src, intake, project, sender, team, et)

  // ---------- 0. 分隔符 + 词表覆盖硬门 ----------

  test("Sep 逐字 = 空格 · 空格（U+00B7），且组装串不出现双分隔符"):
    assertEquals(NotificationHeader.Sep, " · ")
    val s = NotificationHeader.render("NODE", Some("NEBFLOW"), Some("FMCARD-AUDIT"), Some("COMPLETED"))
    assertEquals(s, "NODE · NEBFLOW · FMCARD-AUDIT · COMPLETED")
    assert(!s.contains("  · "), "no doubled separator")
    assert(!s.startsWith(" · ") && !s.endsWith(" · "), "no leading/trailing separator")

  /**
   * 「专用分支源」：呈现归**各自批**的前端显式分支，本函数不接管（`h(...) == None`，
   * 见 §3）。口径与 main `InjectionSourceContractSpec` 的「已登记 = 表项 ∪ 显式分支」
   * 逐字同款（门 = `frontendRegistered = registeredSourceKeys ∪ explicitBranchSources`）：
   * device-mail 批（2026-09-15）把 `deviceMail` 定为 i18n 专用分支
   * 「来自 <from_device> 的 Nebula」（`chat.js:363-365`），故**不纳入 KIND 词表**——
   * 本函数不替它决定形态，前端回落既有渲染（逐字节不变）。
   */
  private val DedicatedBranchSources: Set[String] = Set("deviceMail")

  test("KIND 词表键集 == 后端自定名源全集（BackendNamedSources）——其他源一律纳入的硬门"):
    assertEquals(
      NotificationHeader.KindLabels.keySet,
      InjectionAttribution.BackendNamedSources -- DedicatedBranchSources,
      "KIND 词表落后于后端自定名源集合（新增源须显式纳入词表，或登记为 DedicatedBranchSources）"
    )
    // 三源必在词表内（任务书点名）
    Set("chain", "node", "mail").foreach(s => assert(NotificationHeader.KindLabels.contains(s), s"missing KIND for $s"))

  // ---------- 1. 三源逐字 ----------

  test("NODE 逐字：NODE · <项目> · <节点> · <状态>（sender 路径约定切分）"):
    assertEquals(
      h("node", None, None, Some("NEBFLOW/FMCARD-AUDIT"), None, Some("completed")),
      Some("NODE · NEBFLOW · FMCARD-AUDIT · COMPLETED")
    )
    assertEquals(
      h("node", None, None, Some("NEBFLOW/FMCARD-AUDIT"), None, Some("failed")),
      Some("NODE · NEBFLOW · FMCARD-AUDIT · FAILED")
    )
    // 一个字母的历史口径不得漂移：cancelled ⇒ CANCELED（NODE_STATUS_LABELS 同源）
    assertEquals(
      h("node", None, None, Some("NEBFLOW/n-abc123"), None, Some("cancelled")),
      Some("NODE · NEBFLOW · N-ABC123 · CANCELED")
    )

  test("CHAIN 逐字：CHAIN · <项目> · <链id> · <状态>"):
    assertEquals(
      h("chain", None, None, Some("NEBFLOW/chain-n-dde7a316"), None, Some("completed")),
      Some("CHAIN · NEBFLOW · CHAIN-N-DDE7A316 · COMPLETED")
    )
    assertEquals(
      h("chain", None, None, Some("NEBFLOW/chain-n-dde7a316"), None, Some("failed")),
      Some("CHAIN · NEBFLOW · CHAIN-N-DDE7A316 · FAILED")
    )

  test("MAIL 逐字：MAIL · <发送方项目> · <对端地址(发送方 agent 名)> · <消息类型>（= 作者成串）"):
    // 作者样串 `MAIL · PROJECT-DISPATCHER · RESULT` 的第 2 段是对端地址段 ⇒ 落 SUBJECT，
    // 项目段（发送方所属项目）补齐为第 2 段；成串逐字 = 指令给定形态。
    assertEquals(
      h("mail", Some("mail"), Some("Nebflow"), Some("project-dispatcher"), None, Some("result")),
      Some("MAIL · NEBFLOW · PROJECT-DISPATCHER · RESULT")
    )
    // intake 优先取 KIND 键（mailbadge 批口径：收件判别字段只做呈现判别）
    assertEquals(
      h("task", Some("mail"), Some("NebFlow"), Some("project-dispatcher"), None, Some("info")),
      Some("MAIL · NEBFLOW · PROJECT-DISPATCHER · INFO")
    )
    // Team 腿：SUBJECT = team/agent（旧呈现逐字保持）
    assertEquals(
      h("mail", None, Some("NEBFLOW"), Some("worker-a"), Some("nebflow-project"), Some("result")),
      Some("MAIL · NEBFLOW · NEBFLOW-PROJECT/WORKER-A · RESULT")
    )

  // ---------- 2. 其他源（逐源 K 段逐字） ----------

  test("其他源逐字：KIND 段 = 各源全大写词表值（CHAIN/NODE 之外的全部后端自定名源）"):
    val expected = Map(
      "task" -> "TASK",
      "dispatch" -> "DISPATCH",
      "system" -> "SYSTEM",
      "skill" -> "SKILL",
      "delegate" -> "DELEGATE",
      "subtask" -> "SUBTASK",
      "flow" -> "FLOW",
      "tool" -> "TOOL",
      "background" -> "BACKGROUND"
    )
    expected.foreach { (src, kind) =>
      assertEquals(
        h(src, None, Some("PROJ"), Some("worker-a"), None, Some("info")),
        Some(s"$kind · PROJ · WORKER-A · INFO"),
        s"source=$src"
      )
    }
    // 每个词表源都必须能产出 header（无 None）
    NotificationHeader.KindLabels.keys.foreach(src =>
      assert(h(src, None, Some("PROJ"), Some("x"), None, Some("info")).isDefined, s"$src must render")
    )

  // ---------- 3. 回落面（词表外 source ⇒ 帧不带 header） ----------

  test("词表外 source ⇒ None（帧不带 header 键，前端回落既有渲染，逐字节不变）"):
    // 在飞 device-mail 批新增源：其呈现由该批自己的前端显式分支负责，本函数不接管。
    assertEquals(h("deviceMail", None, Some("NEBFLOW"), Some("KAI"), None, Some("info")), None)
    // legacy 排空腿的 source 亦不在词表内（队列退役后仅 legacy 面可达）
    assertEquals(h("mail-queue", None, None, Some("a"), None, Some("queue")), None)

  // ---------- 4. 空段跳过 / 边界 ----------

  test("空段跳过：无项目/无 sender/无 eventType 时不产生双分隔符或尾分隔符"):
    assertEquals(h("tool", None, None, None, None, None), Some("TOOL"))
    assertEquals(h("tool", None, Some("PROJ"), None, None, None), Some("TOOL · PROJ"))
    assertEquals(NotificationHeader.render("TOOL", None, None, None), "TOOL")
    // 空白串视为空段
    assertEquals(NotificationHeader.render("TOOL", Some("  "), Some(""), Some("  ")), "TOOL")

  test("STATE 表外值 ⇒ 原样全大写（旧前端兜底口径，逐字保持）"):
    assertEquals(
      h("flow", None, Some("PROJ"), Some("n1"), None, Some("trigger")),
      Some("FLOW · PROJ · N1 · TRIGGERED")
    )
    assertEquals(
      h("flow", None, Some("PROJ"), Some("n1"), None, Some("some_new_state")),
      Some("FLOW · PROJ · N1 · SOME_NEW_STATE")
    )

  test("NODE sender 无 '/'（缺段旧形态）⇒ 项目走回落、SUBJECT 省略（旧降级口径 NODE · <状态>）"):
    assertEquals(
      h("node", None, None, Some("node"), None, Some("completed")),
      Some("NODE · COMPLETED")
    )
    assertEquals(
      h("node", None, Some("NEBFLOW"), Some("node"), None, Some("FAILED")),
      Some("NODE · NEBFLOW · FAILED")
    )
end NotificationHeaderSpec
