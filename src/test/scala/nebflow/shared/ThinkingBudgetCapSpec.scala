package nebflow.shared

import munit.FunSuite

/**
 * 四修④（2026-10-07 修复令「subagent 单发调用设 thinking 上限（对齐 UI 客户端
 * budgetTokens=32000 形态），防 20 分钟空烧再现（worker 侧有预算帽）」）：
 *
 *  - `ThinkingConfig.cappedForSubagent` 纯函数钳口：高于帽 → 钳到帽；低于/等于
 *    帽 → 原样（只设上限，不抬线下）；
 *  - enabled=false 原样（预算本就不发）；
 *  - 帽值 = UI 客户端缺省形态（`ThinkingConfig()` 缺省 / `WsConfigHandlers`
 *    budgetTokens 缺省），常量不漂移；
 *  - 消费点语义（AgentSessionExecution）：depth>0 的派生会话钳，根会话
 *    （depth=0）零变化。
 *
 * 现场（判红锚的来历）：鹦鹉骑自行车 delegate——根会话 UI 调高 thinking 预算
 * 被 worker 原样继承，单轮 thinking 146K 字符、textLen=0、零工具调用、
 * stopReason=max_tokens，20 分钟空烧后仍报 completed。
 */
class ThinkingBudgetCapSpec extends FunSuite:

  test("budget above the cap is clamped to the cap; below/equal passes through untouched") {
    val raised = ThinkingConfig(enabled = true, budgetTokens = 128000)
    assertEquals(
      ThinkingConfig.cappedForSubagent(raised),
      ThinkingConfig(enabled = true, budgetTokens = Defaults.SubagentThinkingBudgetCap),
      "worker must not inherit the root session's enlarged thinking budget"
    )
    val atCap = ThinkingConfig(enabled = true, budgetTokens = 32000)
    assertEquals(ThinkingConfig.cappedForSubagent(atCap), atCap, "exactly at cap = untouched (only a ceiling)")
    val below = ThinkingConfig(enabled = true, budgetTokens = 8192)
    assertEquals(ThinkingConfig.cappedForSubagent(below), below, "below cap = untouched (never raises the floor)")
  }

  test("disabled thinking is clamped too (the invariant is 'worker budget <= cap', switch-independent)") {
    val off = ThinkingConfig(enabled = false, budgetTokens = 128000)
    assertEquals(
      ThinkingConfig.cappedForSubagent(off),
      ThinkingConfig(enabled = false, budgetTokens = Defaults.SubagentThinkingBudgetCap),
      "the budget field stays bounded even while disabled — flipping the switch on can never resurrect a >cap worker budget"
    )
  }

  test("the cap equals the UI client default form (32000) — no silent value drift") {
    assertEquals(Defaults.SubagentThinkingBudgetCap, 32000)
    assertEquals(
      ThinkingConfig().budgetTokens,
      Defaults.SubagentThinkingBudgetCap,
      "config default == the cap (same UI form)"
    )
    // toLlmJson on a capped worker config renders the wire shape the adapters read.
    val wire =
      ThinkingConfig.toLlmJson(ThinkingConfig.cappedForSubagent(ThinkingConfig(enabled = true, budgetTokens = 200000)))
    assertEquals(wire.hcursor.get[Int]("budget_tokens").toOption, Some(32000))
    assertEquals(wire.hcursor.get[String]("type").toOption, Some("enabled"))
  }

end ThinkingBudgetCapSpec
