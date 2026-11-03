package nebflow.core.delegate

import munit.CatsEffectSuite
import nebflow.shared.Defaults

/**
 * 会话状态 × 子任务/链 complete 的**通知矩阵单点**（作者 2026-11-03 18:14 裁定逐字：
 * 「我们有四个状态 idle、stand by、merge、finish。只有在 idle 状态下，进入等待
 * （complete），才会有 system reminder 通知。它都已经报了 stand by 了，还通知什么？
 * 所以这里的四个状态 idle complete 通知，stand by/merge complete 不通知 finish
 * complete 回报给主 agent。」）。
 *
 * 四行矩阵（[[DelegateCompletionMatrix.decide]]）+ **接线效果**：
 *   - idle   ⇒ InjectReminder（DelegateStandbyCare IdleNudge 退避提醒——唯一在提醒的腿）；
 *   - stand by ⇒ QueueSilently（BackoffSupervisor Standby 腿**零 caretaker**——
 *     DelegateStandbyCare.arm(kind=Declared) 零动作；完成事件静默入队）；
 *   - merge  ⇒ QueueSilently（merge 停靠腿零提醒注入；MergeQueue.notifyTurn 是引擎
 *     授命的合并轮执行指令，不是完成提醒，保留）；
 *   - finish ⇒ ReportToParent（既有终态投递主路径，零额外 reminder）。
 */
class DelegateCompletionMatrixSpec extends CatsEffectSuite:

  // ── 矩阵逐行（纯函数判红锚）──────────────────────────────────────────

  test("matrix rows: idle⇒InjectReminder, standby⇒QueueSilently, merge⇒QueueSilently, finish⇒ReportToParent") {
    import DelegateCompletionMatrix.*
    assertEquals(decide(SessionState.Idle), NotifyDecision.InjectReminder, "作者 18:14：只有 idle×complete 才有 system reminder")
    assertEquals(decide(SessionState.Standby), NotifyDecision.QueueSilently, "🔴 已经报了 stand by，还通知什么——零注入零唤醒")
    assertEquals(decide(SessionState.Merge), NotifyDecision.QueueSilently, "等合并停靠：零注入零唤醒")
    assertEquals(decide(SessionState.Finish), NotifyDecision.ReportToParent, "finish 结果照常回报主 agent，不额外 reminder")
  }

  test("matrix exhaustiveness: every SessionState has a decision (totality)") {
    import DelegateCompletionMatrix.*
    val all: List[SessionState] = List(SessionState.Idle, SessionState.Standby, SessionState.Merge, SessionState.Finish)
    assertEquals(all.map(decide).distinct.size, 4, "四个状态各自有判（无缺行）")
  }

  // ── 接线效果：stand by ⇒ arm(Declared) 零 caretaker / 零记账 ─────────

  test("wiring standby: DelegateStandbyCare.arm(kind=Declared) leaves NO caretaker and NO care state (zero nudge)") {
    DelegateStandbyCare.clearAllForSpec()
    DelegateStandbyCare.arm(
      "delegate-matrix-standby-wiring",
      childRef = null,
      supervisorRef = null,
      resources = null,
      kind = DelegateStandbyCare.Declared
    )
    assertEquals(DelegateStandbyCare.caretakerLive("delegate-matrix-standby-wiring"), false, "🔴 申报待命腿不得武装任何 caretaker")
    assertEquals(DelegateStandbyCare.careStateOf("delegate-matrix-standby-wiring"), None, "🔴 申报待命腿不得写 CareState")
    // IdleNudge 腿仍武装（矩阵 idle 行）：真接线的行为面。
    DelegateStandbyCare.clearAllForSpec()
    DelegateStandbyCare.arm(
      "delegate-matrix-idle-wiring",
      childRef = null,
      supervisorRef = null,
      resources = null,
      kind = DelegateStandbyCare.IdleNudge
    )
    assertEquals(
      DelegateStandbyCare.caretakerLive("delegate-matrix-idle-wiring"),
      true,
      "idle×complete 仍提醒：IdleNudge 腿照常武装（作者 18:14 矩阵唯一 InjectReminder 行）"
    )
  }

  // ── 应答下限（18:14 裁定的退避判据：答了无变化绝不再 1 分钟连戳）──────

  test("effectiveDelay: 答复过（answers>0）或第 2 档起的间隔 ≥ floor，首档照旧") {
    val d = List(50L)
    assertEquals(DelegateStandbyCare.effectiveDelay(1, d, 50L, floorMs = 600_000L, answers = 0), 50L, "首档（还没应答过）照旧")
    assertEquals(
      DelegateStandbyCare.effectiveDelay(1, d, 50L, floorMs = 600_000L, answers = 1),
      600_000L,
      "🔴 应答过 ⇒ 下一档 ≥ floor（绝不 1 分钟连戳）"
    )
    assertEquals(
      DelegateStandbyCare.effectiveDelay(2, d, 50L, floorMs = 600_000L, answers = 0),
      600_000L,
      "🔴 第 2 档起无条件 ≥ floor"
    )
    assertEquals(
      DelegateStandbyCare.effectiveDelay(4, List(10_000L, 30_000L, 60_000L), 600_000L, floorMs = 600_000L, answers = 1),
      600_000L,
      "长档与 floor 取大值"
    )
    assertEquals(DelegateStandbyCare.effectiveDelay(2, d, 50L, floorMs = 0L, answers = 1), 50L, "floor=0 ⇒ 纯退避序（spec 提速用）")
    assertEquals(Defaults.DelegateStandbyNudgeAnswerFloorMs, 600_000L, "缺省下限 = 10min")
  }

  // ── nudgePayload：Declared 文案退役后的形态纪律 ──────────────────────

  test("IdleNudge payload keeps the reminder contract (reminder form, bilingual, standby/finish escape)") {
    val idle = DelegateStandbyCare.nudgePayload(DelegateStandbyCare.IdleNudge, 1, "", "10s→30s→60s, then every 10min ×3", lastReminder = false)
    assert(idle.startsWith("<system-reminder>") && idle.trim.endsWith("</system-reminder>"), "reminder 注入形态保留（idle 行的唯一注入腿）")
    assert(idle.contains("delegate_report(category=\"standby\""), "催其补报 standby")
    assert(idle.contains("finish"), "并给 finish/merge 出口")
  }

end DelegateCompletionMatrixSpec
