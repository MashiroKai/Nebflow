package nebflow.llm.providers

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.llm.*
import nebflow.shared.*

/**
 * thinking-config 实施批（2026-10-28/29）——openai 面 effort 档位 spec（设计 §4.2
 * openai 行「reasoning_effort 直发档位（budgetToEffort 边界保留，旧 budget 值仍可
 * 换算）」）。
 *
 *   O1  effort 档位已配（三层解析产出 ResolvedThinking.effort）⇒ 直发该档位，
 *       三档面 medium→high 客户端映射（EffortWire.threeTier 单点）。
 *   O2  effort 未配 ⇒ 旧 budget 换算保留（budgetToEffort 边界不动：≤2048 low /
 *       ≤8192 medium / ≤32768 high / >32768 xhigh）——旧单旋钮值行为零变化。
 *   O3  glm 模型（openai 面）仍走 GLM 原生 thinking:{type:enabled}，effort 不上
 *       reasoning_effort（原生面专用参数形态不变）。
 *   O4  kimi 内置搜索互斥强制 disabled 不变（bodyWithSearchThinking 原语义）。
 *
 * 变异验红：thinkingBody 忽略 resolved.effort → O1 红；threeTier 丢映射 → O1b 红。
 */
class OpenAiThinkingEffortSpec extends FunSuite:

  private val thinkingEnabled: Option[Json] =
    Some(Json.obj("type" -> "enabled".asJson, "budget_tokens" -> 32000.asJson))

  private def adapter(): OpenAiAdapter = OpenAiAdapter("http://localhost:0", "k", null)

  private def body(model: String, thinking: Option[Json], resolved: Option[ResolvedThinking], search: Option[ProviderSearchKind] = None): Json =
    adapter().bodyWithSearchThinking(Json.obj(), model, thinking, search, resolved)

  test("explicit provider model and tier allowlist enables only that effort") {
    val adapter = OpenAiAdapter("http://localhost:0", "k", null,
      effortAllowlist = Some(Map("m" -> List(ThinkingEffort.Max))))
    val resolved = Some(ResolvedThinking(ThinkingKind.Budget, enabled = true, Some(ThinkingEffort.Max)))
    assertEquals(adapter.bodyWithSearchThinking(Json.obj(), "m", thinkingEnabled, None, resolved)
      .hcursor.get[String]("reasoning_effort").toOption, Some("max"))
    assertEquals(adapter.bodyWithSearchThinking(Json.obj(), "other", thinkingEnabled, None, resolved)
      .hcursor.downField("reasoning_effort").focus, None)
  }

  test("unknown endpoint never sends effort even with an explicit tier or legacy budget") {
    val explicit = body("unknown-model", thinkingEnabled,
      Some(ResolvedThinking(ThinkingKind.Budget, enabled = true, Some(ThinkingEffort.Max))))
    assertEquals(explicit.hcursor.downField("reasoning_effort").focus, None)
    assertEquals(body("unknown-model", thinkingEnabled, None).hcursor.downField("reasoning_effort").focus, None)
  }

  test("O1: unknown endpoint omits configured effort") {
    val b = body("deepseek/deepseek-v4.1-flash", thinkingEnabled,
      Some(ResolvedThinking(ThinkingKind.EffortAnthropic, enabled = true, Some(ThinkingEffort.Low))))
    assertEquals(b.hcursor.downField("reasoning_effort").as[String].toOption, None)
  }

  test("O1b: unknown endpoint omits medium despite legacy conversion helpers") {
    val b = body("deepseek/deepseek-v4.1-flash", thinkingEnabled,
      Some(ResolvedThinking(ThinkingKind.EffortAnthropic, enabled = true, Some(ThinkingEffort.Medium))))
    assertEquals(b.hcursor.downField("reasoning_effort").as[String].toOption, None)
    assertEquals(EffortWire.threeTier(ThinkingEffort.Medium), "high")
    assertEquals(EffortWire.threeTier(ThinkingEffort.Max), "max")
    assertEquals(EffortWire.threeTier(ThinkingEffort.Low), "low")
    assertEquals(EffortWire.threeTier(ThinkingEffort.High), "high")
    assertEquals(EffortWire.threeTier(ThinkingEffort.Minimal), "low")
    assertEquals(EffortWire.openAi(ThinkingEffort.Minimal), "minimal")
    assertEquals(EffortWire.openAi(ThinkingEffort.Medium), "medium")
    assertEquals(EffortWire.openAi(ThinkingEffort.High), "high")
    assertEquals(EffortWire.openAi(ThinkingEffort.Max), "max")
  }

  test("O2: unknown endpoint omits effort derived from legacy budget") {
    val high = body("deepseek/deepseek-v4.1-flash", thinkingEnabled, None)
    assertEquals(high.hcursor.downField("reasoning_effort").as[String].toOption, None, "unverified endpoint omits legacy derived effort")
    val mediumBudget = body("deepseek/deepseek-v4.1-flash",
      Some(Json.obj("type" -> "enabled".asJson, "budget_tokens" -> 8192.asJson)), None)
    assertEquals(mediumBudget.hcursor.downField("reasoning_effort").as[String].toOption, None, "unverified endpoint omits legacy derived effort")
  }

  test("O3: glm on the openai face keeps the native thinking shape (no reasoning_effort)") {
    val b = body("glm-5.3-flash", thinkingEnabled,
      Some(ResolvedThinking(ThinkingKind.Toggle, enabled = true, Some(ThinkingEffort.Max))))
    assertEquals(b.hcursor.downField("thinking").focus, Some(Json.obj("type" -> "enabled".asJson)))
    assertEquals(b.hcursor.downField("reasoning_effort").focus, None)
  }

  test("O4: kimi builtin search still forces thinking disabled (interplay unchanged)") {
    val b = body("kimi-k2-search", thinkingEnabled, None, Some(ProviderSearchKind.KimiBuiltinWebSearch))
    assertEquals(b.hcursor.downField("thinking").focus, Some(Json.obj("type" -> "disabled".asJson)))
  }

  test("O5: off (Some(Json.Null)) → empty fragment (no reasoning_effort)") {
    val b = body("deepseek/deepseek-v4.1-flash", Some(Json.Null),
      Some(ResolvedThinking(ThinkingKind.EffortAnthropic)))
    assertEquals(b, Json.obj())
  }
end OpenAiThinkingEffortSpec
