package nebflow.core.delegate

import cats.effect.IO
import munit.CatsEffectSuite
import nebflow.shared.Defaults

/**
 * 会话状态 × 子任务/链 complete 的**通知矩阵单点**（作者 2026-11-03 18:14 裁定逐字：
 * 「我们有四个状态 idle、stand by、merge、finish。只有在 idle 状态下，进入等待
 * （complete），才会有 system reminder 通知。它都已经报了 stand by 了，还通知什么？
 * 所以这里的四个状态 idle complete 通知，stand by/merge complete 不通知 finish
 * complete 回报给主 agent。」）。
 *
 * 三行矩阵（[[DelegateCompletionMatrix.decide]]）+ **接线效果**：
 *   - idle   ⇒ InjectReminder（DelegateStandbyCare IdleNudge 退避提醒——唯一在提醒的腿）；
 *   - stand by ⇒ QueueSilently（BackoffSupervisor Standby 腿**零 caretaker**——
 *     DelegateStandbyCare.arm(kind=Declared) 零动作；完成事件静默入队）；
 *   - finish ⇒ ReportToParent（既有终态投递主路径，零额外 reminder）。
 */
class DelegateCompletionMatrixSpec extends CatsEffectSuite:

  // ── 矩阵逐行（纯函数判红锚）──────────────────────────────────────────

  test("matrix rows: idle⇒InjectReminder, standby⇒QueueSilently, merge⇒QueueSilently, finish⇒ReportToParent") {
    import DelegateCompletionMatrix.*
    assertEquals(
      decide(SessionState.Idle),
      NotifyDecision.InjectReminder,
      "作者 18:14：只有 idle×complete 才有 system reminder"
    )
    assertEquals(decide(SessionState.Standby), NotifyDecision.QueueSilently, "🔴 已经报了 stand by，还通知什么——零注入零唤醒")
    assertEquals(decide(SessionState.Finish), NotifyDecision.ReportToParent, "finish 结果照常回报主 agent，不额外 reminder")
  }

  test(
    "matrix totality: all three states map (idle/standby/finish), finish is the only ReportToParent"
  ) {
    import DelegateCompletionMatrix.*
    val all: List[SessionState] = List(SessionState.Idle, SessionState.Standby, SessionState.Finish)
    assertEquals(all.map(decide).size, 3, "三行齐全（无缺行）")
    assertEquals(
      all.map(decide).distinct.toSet,
      Set[NotifyDecision](NotifyDecision.InjectReminder, NotifyDecision.QueueSilently, NotifyDecision.ReportToParent),
      "决策面恰三值"
    )
    assertEquals(
      all.count(decide(_) == NotifyDecision.InjectReminder),
      1,
      "🔴 只有 idle 一行会注入 reminder（作者 18:14 矩阵的核心不变量）"
    )
    assertEquals(
      all.count(decide(_) == NotifyDecision.ReportToParent),
      1,
      "只有 finish 一行回报主 agent"
    )
  }

  // ── 接线效果：stand by ⇒ arm(Declared) 零 caretaker / 零记账 ─────────

  test(
    "wiring standby: DelegateStandbyCare.arm(kind=Declared) leaves NO caretaker and NO care state; arm(kind=IdleNudge) DOES arm"
  ) {
    val declaredId = "delegate-matrix-standby-wiring"
    val idleId = "delegate-matrix-idle-wiring"
    for
      _ <- IO(DelegateStandbyCare.clearAllForSpec())
      // 申报待命腿（矩阵 standby 行）：零动作——不建 caretaker、不写记账。
      _ <- DelegateStandbyCare.arm(declaredId, null, null, null, DelegateStandbyCare.Declared)
      declaredLive <- IO(DelegateStandbyCare.caretakerLive(declaredId))
      declaredState <- IO(DelegateStandbyCare.careStateOf(declaredId))
      // 未申报腿（矩阵 idle 行）：照常武装（矩阵唯一 InjectReminder 行）。
      _ <- IO(DelegateStandbyCare.clearAllForSpec())
      _ <- DelegateStandbyCare.arm(idleId, null, null, null, DelegateStandbyCare.IdleNudge)
      idleLive <- IO(DelegateStandbyCare.caretakerLive(idleId))
      idleState <- IO(DelegateStandbyCare.careStateOf(idleId))
      _ <- IO(DelegateStandbyCare.clearAllForSpec())
    yield
      assertEquals(declaredLive, false, "🔴 申报待命腿不得武装任何 caretaker（作者 18:14：已报 stand by 就不通知）")
      assertEquals(declaredState, None, "🔴 申报待命腿不得写 CareState")
      assertEquals(idleLive, true, "idle×complete 仍提醒：IdleNudge 腿照常武装")
      assertEquals(idleState.map(_.kind), Some(DelegateStandbyCare.KindIdleNudge), "记账面：idle 腿标签")
    end for
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
    assertEquals(
      DelegateStandbyCare.effectiveDelay(2, d, 50L, floorMs = 0L, answers = 1),
      50L,
      "floor=0 ⇒ 纯退避序（spec 提速用）"
    )
    assertEquals(Defaults.DelegateStandbyNudgeAnswerFloorMs, 600_000L, "缺省下限 = 10min")
  }

  // ── nudgePayload：Declared 文案退役后的形态纪律 ──────────────────────

  test("IdleNudge payload keeps the reminder contract (reminder form, bilingual, standby/finish escape)") {
    val idle = DelegateStandbyCare.nudgePayload(lastReminder = false)
    assert(
      idle.startsWith("<system-reminder>") && idle.trim.endsWith("</system-reminder>"),
      "reminder 注入形态保留（idle 行的唯一注入腿）"
    )
    assert(idle.contains("delegate_report(category=\"standby\""), "催其补报 standby")
    assert(idle.contains("finish"), "并给 finish/merge 出口")
  }

end DelegateCompletionMatrixSpec
