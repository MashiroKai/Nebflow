package nebflow.llm.providers

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.llm.SendMessageParams
import nebflow.shared.{Defaults, Message, MessageRole}

/**
 * 输出上限钳制批（2026-10-21，作者令「总输出上限改为128000…不许把 128000
 * 原样发给会被 400 拒的端点」）——`AnthropicAdapter.maxTokensFor` 的算式级
 * 钉版（线级钉版见 `MaxTokensRequestFaceSpec`）。
 *
 * 固定件：`Message(User, "hi")` 的 TokenEstimator 读数 = 0（"hi" 2 字符 / 4），
 * 无 system、无 tools ⇒ 窗口腿储备 = 0 + InputHeadroomTokens(8192)。
 *
 * 钉位：
 *   T1  override 腿：辅面值逐字上线，绕过一切钳制腿（探针/小判官零行为变化）。
 *   T2  无窗口、无 thinking ⇒ 常量 128000 逐字上线（钳制全休眠）。
 *   T3  家族帽：glm 系（含大小写/前缀变体）⇒ 32768；非家族模型不受影响。
 *   T4  窗口腿：cw=128000 ⇒ 128000 − 8192 = 119808（留输入余量）。
 *   T5  窗口腿地板：极小窗（cw=8192）⇒ 16384（升额前服务值，不发明新失败态）。
 *   T6  thinking 合法性地板压过钳制：预算 32768 + cw=32768 ⇒ 32769（硬不变量
 *       胜出——`budget_tokens < max_tokens` 被 400 强制，钳制不得把它翻成截断）。
 *   T7  输入体量进入算式：100000 拉丁字符 ≈ 25000 tokens ⇒ cw=131072 时上限
 *       = 131072 − 25000 − 8192 = 97880。
 *   T8  system 文本进入算式：4000 拉丁字符 ≈ 1000 tokens ⇒ 128000 − 9192 = 118808。
 *   T9  抬高腿原形保留：high thinking(32768) + 钳制休眠 ⇒ 32768 + 128000 = 160768。
 *
 * 变异验红：摘掉任一钳制腿（family/window/floor/override/invariant）⇒ 对应钉位红。
 */
class MaxTokensClampSpec extends FunSuite:

  private val adapter = AnthropicAdapter("http://localhost:0", "k", null)

  private val hi = List(Message(MessageRole.User, Left("hi")))

  private def thinkingJson(budget: Int): Option[Json] =
    Some(Json.obj("type" -> "enabled".asJson, "budget_tokens" -> budget.asJson))

  /** ~`n` estimated tokens of Latin payload (chars/4). */
  private def latinUserMessage(chars: Int): List[Message] =
    List(Message(MessageRole.User, Left("a" * chars)))

  private def capOf(
    model: String = "m1",
    messages: List[Message] = hi,
    contextWindow: Option[Int] = None,
    thinking: Option[Json] = None,
    systemStable: Option[String] = None,
    overrideTokens: Option[Int] = None
  ): Int =
    adapter.maxTokensFor(
      SendMessageParams(
        messages = messages,
        model = model,
        thinking = thinking,
        systemStable = systemStable,
        contextWindow = contextWindow,
        maxTokensOverride = overrideTokens
      )
    )

  // ── T1: override leg — verbatim, bypasses every clamp ───────────────────

  test("T1: 辅面 override 逐字上线，绕过家族帽/窗口腿/thinking 抬高") {
    assertEquals(
      capOf(overrideTokens = Some(Defaults.ProbeMaxTokens), contextWindow = Some(8192)),
      16384,
      "override 必须逐字上线（即使窗口极小）"
    )
    assertEquals(
      capOf(
        model = "glm-5.3-flash",
        contextWindow = Some(8192),
        thinking = thinkingJson(100000),
        overrideTokens = Some(Defaults.ProbeMaxTokens)
      ),
      16384,
      "override 同时绕过家族帽与 thinking 腿"
    )
  }

  // ── T2: clamp dormant — the constant reaches the wire verbatim ──────────

  test("T2: 无窗口无 thinking ⇒ 常量 128000 逐字上线") {
    assertEquals(capOf(), 128000, "防回退钉：Defaults.MaxTokens 必须是 128000 且原样出线")
  }

  // ── T3: family cap ──────────────────────────────────────────────────────

  test("T3: glm 家族帽 32768（大小写/前缀变体皆中），非家族模型不受影响") {
    assertEquals(capOf(model = "glm-5.3-flash"), 32768)
    assertEquals(capOf(model = "GLM-5.3"), 32768)
    assertEquals(capOf(model = "zhipu/glm-4.6"), 32768)
    assertEquals(capOf(model = "kimi-for-coding"), 128000, "非家族模型无家族帽")
  }

  // ── T4: window leg ──────────────────────────────────────────────────────

  test("T4: cw=128000 ⇒ 119808（留 8192 输入余量）") {
    assertEquals(capOf(contextWindow = Some(128000)), 119808)
  }

  // ── T5: window floor ────────────────────────────────────────────────────

  test("T5: 极小窗 cw=8192 ⇒ 地板 16384（升额前服务值）") {
    assertEquals(capOf(contextWindow = Some(8192)), 16384)
  }

  // ── T6: thinking-legal invariant dominates the clamp ────────────────────

  test("T6: budget 32768 + cw=32768 ⇒ 32769（budget_tokens < max_tokens 硬不变量胜出）") {
    assertEquals(
      capOf(contextWindow = Some(32768), thinking = thinkingJson(32768)),
      32769,
      "钳制不得把 max_tokens 压到 budget 以下——那是必然 400，不是截断"
    )
  }

  // ── T7: input volume enters the arithmetic ──────────────────────────────

  test("T7: 100k 拉丁字符输入（≈25k tokens）⇒ cw=131072 时上限 97880") {
    assertEquals(
      capOf(messages = latinUserMessage(100000), contextWindow = Some(131072)),
      97880
    )
  }

  // ── T8: system text enters the arithmetic ───────────────────────────────

  test("T8: system 4000 拉丁字符（≈1k tokens）⇒ 128000 − 9192 = 118808") {
    assertEquals(
      capOf(systemStable = Some("s" * 4000), contextWindow = Some(128000)),
      118808
    )
  }

  // ── T9: raise leg unchanged in form ─────────────────────────────────────

  test("T9: high thinking + 钳制休眠 ⇒ 32768 + 128000 = 160768（抬高腿原形）") {
    assertEquals(capOf(thinking = thinkingJson(32768)), 160768)
  }
end MaxTokensClampSpec
