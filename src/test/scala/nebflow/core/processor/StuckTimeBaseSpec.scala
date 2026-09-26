package nebflow.core.processor

import munit.FunSuite
import nebflow.actor.ActorRef
import nebflow.actor.{AgentCommand, AgentKind, AgentRecord, AgentStatus}

/**
 * TaskStuckWatcher 时间基修正消费面单测（hostresume 批 2026-09-22，设计卡 §4 #4 /
 * §6 口径 1、2）+ **反向臂对照**（任务书条件 2：真 stale 仍照常判——修正只减冻结秒、
 * 不把「宽限」做成全域静默，#1002 leg3 边界同向）。
 *
 * `elapsed` 参数 = 本批新增的注入缝（默认 = `PowerStateTracker.effectiveElapsed` 全局
 * 入口；此处显式注入以脱离全局单点状态、逐帧钉死判据）。
 */
class StuckTimeBaseSpec extends FunSuite:

  private val S = 1000L
  private val Threshold = 600 * S

  private def rec(
    now: Long,
    lastActivity: Long,
    tool: Option[String] = None,
    toolStartedAt: Long = 0
  ): AgentRecord =
    AgentRecord(
      sessionId = "s-timebase",
      ref = null.asInstanceOf[ActorRef[AgentCommand]],
      kind = AgentKind.Flow,
      rootSessionId = "root-1",
      status = AgentStatus.Processing,
      lastActivityMs = lastActivity,
      currentToolName = tool,
      currentToolStartedAt = toolStartedAt
    )

  /** 合成窗注入：窗 [now−900s, now] 的时间基修正（卡 §6 口径 2 的注入形态）。 */
  private val with900sWindow: (Long, Long) => Long =
    val now = 10_000_000L
    val windows = List(nebflow.shared.SleepWindow(now - 900 * S, now))
    (start, t) => nebflow.shared.PowerStateMath.effectiveElapsed(windows, start, t)
  private val identity: (Long, Long) => Long = (start, now) => now - start
  private val Now = 10_000_000L

  // ── 口径 1：空窗集 ⇒ 判词与 reason 文案逐字不变 ─────────────────────────────

  test("C1a 空窗等价：agent-stale 分支判词逐字（identity 注入 = 现状）") {
    val got = TaskStuckWatcher.assessDetailed(rec(Now, lastActivity = Now - 910 * S), Now, elapsed = identity)
    assert(got.isDefined)
    assertEquals(got.get.branch, TaskStuckWatcher.BranchAgentStale)
    assertEquals(got.get.secs, 910L)
    assertEquals(got.get.reason, "agent idle 910s (no LLM/tool event)")
  }

  test("C1b 空窗等价：tool-overdue 分支判词逐字") {
    // 单轴形态：agent 轴阈值内（10s）不 stale，仅工具轴 640s 超阈 ⇒ tool-overdue
    // （agent 轴也超阈的双超形态落 merged，见 C1c）。
    val got = TaskStuckWatcher.assessDetailed(
      rec(Now, lastActivity = Now - 10 * S, tool = Some("Bash"), toolStartedAt = Now - 640 * S),
      Now,
      elapsed = identity
    )
    assert(got.isDefined)
    assertEquals(got.get.branch, TaskStuckWatcher.BranchToolOverdue)
    assertEquals(got.get.secs, 640L)
    assertEquals(got.get.reason, "tool 'Bash' running 640s in an unfinished turn")
  }

  test("C1c 空窗等价：merged 分支判词逐字") {
    val got = TaskStuckWatcher.assessDetailed(
      rec(Now, lastActivity = Now - 610 * S, tool = Some("Bash"), toolStartedAt = Now - 620 * S),
      Now,
      elapsed = identity
    )
    assert(got.isDefined)
    assertEquals(got.get.branch, TaskStuckWatcher.BranchMerged)
    assertEquals(got.get.reason, "agent idle 610s and tool 'Bash' running 620s in an unfinished turn")
  }

  test("C1d 空窗等价：阈值内零判 + lastActivityMs=0 不判") {
    assertEquals(TaskStuckWatcher.assessDetailed(rec(Now, lastActivity = Now - 590 * S), Now, elapsed = identity), None)
    assertEquals(TaskStuckWatcher.assessDetailed(rec(Now, lastActivity = 0), Now, elapsed = identity), None)
  }

  // ── 口径 2：合成睡眠窗 [t−900s, t] ⇒ wall idle 910s ⇒ effective 10s ⇒ 不判 ──

  test("C2a 合成窗绿臂：wall idle 910s 跨 900s 窗 ⇒ effective 10s < 600s ⇒ 不判") {
    val got = TaskStuckWatcher.assessDetailed(rec(Now, lastActivity = Now - 910 * S), Now, elapsed = with900sWindow)
    assertEquals(got, None, "扣减冻结秒后不得误判（口径 2 绿臂）")
  }

  test("C2b 红臂对照：同帧同记录无修正（identity）⇒ 判 agent-stale（红绿对齐口径 2）") {
    val got = TaskStuckWatcher.assessDetailed(rec(Now, lastActivity = Now - 910 * S), Now, elapsed = identity)
    assert(got.isDefined, "无修正臂必须照旧判卡死（对照读数）")
    assertEquals(got.get.branch, TaskStuckWatcher.BranchAgentStale)
  }

  test("C2c 工具相位轴同款：910s 工具跨 900s 窗 ⇒ effective 10s ⇒ 不判") {
    val got = TaskStuckWatcher.assessDetailed(
      rec(Now, lastActivity = Now - 910 * S, tool = Some("Bash"), toolStartedAt = Now - 910 * S),
      Now,
      elapsed = with900sWindow
    )
    assertEquals(got, None)
  }

  // ── 反向臂（条件 2）：真 stale 不被宽限吞没 ────────────────────────────────

  test("R1 反向臂：真 stale（无窗、910s 真实静默）⇒ 照常判（修正零遮蔽）") {
    // 空窗集 = 真实醒时静默：判定必须原样成立（global 入口空窗集同构）
    val got = TaskStuckWatcher.assessDetailed(
      rec(Now, lastActivity = Now - 910 * S),
      Now,
      elapsed = nebflow.shared.PowerStateMath.effectiveElapsed(Nil, _, _)
    )
    assert(got.isDefined, "真 stale 必须照常升级——禁全域静默")
    assertEquals(got.get.branch, TaskStuckWatcher.BranchAgentStale)
    assertEquals(got.get.reason, "agent idle 910s (no LLM/tool event)")
  }

  test("R2 反向臂：部分窗（仅 300s 冻结）⇒ effective 610s 仍 > 600s ⇒ 仍判（宽限不成护身符）") {
    val windows = List(nebflow.shared.SleepWindow(Now - 300 * S, Now))
    val elapsed: (Long, Long) => Long =
      (start, t) => nebflow.shared.PowerStateMath.effectiveElapsed(windows, start, t)
    val got = TaskStuckWatcher.assessDetailed(rec(Now, lastActivity = Now - 910 * S), Now, elapsed = elapsed)
    assert(got.isDefined, "部分冻结只减冻结秒——真实停滞仍须浮出")
    assertEquals(got.get.secs, 610L)
  }

  test("R3 反向臂：跨窗真卡死（两窗扣减后仍 610s）⇒ merged 分支照常") {
    val windows = List(
      nebflow.shared.SleepWindow(Now - 900 * S, Now - 800 * S),
      nebflow.shared.SleepWindow(Now - 500 * S, Now - 400 * S)
    )
    val elapsed: (Long, Long) => Long =
      (start, t) => nebflow.shared.PowerStateMath.effectiveElapsed(windows, start, t)
    val got = TaskStuckWatcher.assessDetailed(
      rec(Now, lastActivity = Now - 1410 * S, tool = Some("Bash"), toolStartedAt = Now - 1410 * S),
      Now,
      elapsed = elapsed
    )
    // 1410s 墙钟 − (100s + 100s) 窗 = 1210s 真实醒时停滞 ⇒ 两轴均超 ⇒ merged
    assert(got.isDefined)
    assertEquals(got.get.branch, TaskStuckWatcher.BranchMerged)
    assertEquals(got.get.secs, 1210L)
  }
end StuckTimeBaseSpec
