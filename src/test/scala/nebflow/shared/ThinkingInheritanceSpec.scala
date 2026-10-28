package nebflow.shared

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite

/**
 * thinking-config 实施批（2026-10-28/29）——三层继承 + 配置面零破坏 spec（设计 §4.1/§4.3）。
 *
 * 钉四组语义：
 *   I1  旧单旋钮迁移等价：两层都缺省 ⇒ resolveEffective 逐字等于全局节。
 *   I2  逐字段就近（model > provider > 全局）：不整块覆盖——model 层只写 effort
 *       时 enabled/budgetTokens 沿上层。
 *   I3  配置解码/编码零破坏：旧形态 JSON（无新字段）解码结果与新字段缺省一致；
 *       未配新字段时 encoder 输出与旧 deriveEncoder 形态逐字节一致（落盘/广播面）。
 *   I4  per-model 层定点 patch（WS 写路的纯函数面）：设值/清除（显式 null）/
 *       空 patch 摘除整节点/未知 provider·model fail-loud；patch 后节点空 ⇒
 *       thinking 键整键摘除（= 完全继承）。
 *   I5  passback 族判定（旁支修复）：providerId 或任一模型 id 命中 DeepSeek 族
 *       ⇒ 缺省 true；显式配置永远赢。
 *
 * 变异验红：resolveEffective 改整块覆盖 → I2 红；decoder 恢复必填新字段 → I3 红；
 * patch 改静默容忍未知模型 → I4 红。
 */
class ThinkingInheritanceSpec extends FunSuite:

  private val global = ThinkingConfig(enabled = true, budgetTokens = 32000)
  private val globalOff = ThinkingConfig(enabled = false, budgetTokens = 8192)

  // ── I1：迁移等价 ─────────────────────────────────────────────────────────

  test("I1: no layers → resolution equals the global node verbatim (single-knob migration)") {
    assertEquals(ThinkingMode.resolveEffective(None, None, global), global)
    assertEquals(ThinkingMode.resolveEffective(None, None, globalOff), globalOff)
  }

  // ── I2：逐字段就近 ───────────────────────────────────────────────────────

  test("I2: model layer overrides provider overrides global — field-wise, not block") {
    val provider = ThinkingLayerConfig(enabled = Some(true), effort = Some(ThinkingEffort.High))
    val model = ThinkingLayerConfig(effort = Some(ThinkingEffort.Low))
    val resolved = ThinkingMode.resolveEffective(Some(model), Some(provider), global)
    // model 只写 effort：effort 取 model 层；enabled 沿 provider 层（true）；budget 沿全局。
    assertEquals(resolved.effort, Some(ThinkingEffort.Low))
    assertEquals(resolved.enabled, true)
    assertEquals(resolved.budgetTokens, 32000)
  }

  test("I2b: provider layer fills what the model layer leaves unset; global is the base") {
    val provider = ThinkingLayerConfig(effort = Some(ThinkingEffort.Max), clearThinking = Some(false))
    val model = ThinkingLayerConfig(enabled = Some(false))
    val resolved = ThinkingMode.resolveEffective(Some(model), Some(provider), global)
    assertEquals(resolved.enabled, false) // model 层
    assertEquals(resolved.effort, Some(ThinkingEffort.Max)) // provider 层
    assertEquals(resolved.clearThinking, Some(false)) // provider 层
    assertEquals(resolved.budgetTokens, 32000) // 全局
  }

  test("I2c: three-layer effort chain — model > provider > global") {
    val g = ThinkingConfig(enabled = true, budgetTokens = 32000, effort = Some(ThinkingEffort.Medium))
    val p = ThinkingLayerConfig(effort = Some(ThinkingEffort.High))
    assertEquals(ThinkingMode.resolveEffective(None, Some(p), g).effort, Some(ThinkingEffort.High))
    assertEquals(ThinkingMode.resolveEffective(Some(ThinkingLayerConfig(effort = Some(ThinkingEffort.Max))), Some(p), g).effort,
      Some(ThinkingEffort.Max))
    assertEquals(ThinkingMode.resolveEffective(None, None, g).effort, Some(ThinkingEffort.Medium))
  }

  // ── I3：配置面零破坏 ─────────────────────────────────────────────────────

  test("I3: legacy config JSON (no new fields) decodes to the legacy values") {
    val legacy = parse("""{"enabled": true, "budgetTokens": 32000}""").toOption.getOrElse(fail("parse"))
    val tc = legacy.as[ThinkingConfig].toOption.getOrElse(fail("decode"))
    assertEquals(tc, ThinkingConfig(enabled = true, budgetTokens = 32000, effort = None, clearThinking = None))
  }

  test("I3b: legacy global node shape survives the encoder byte-identically (no effort/clearThinking keys)") {
    val tc = ThinkingConfig(enabled = false, budgetTokens = 8192)
    assertEquals(tc.asJson.noSpaces, """{"enabled":false,"budgetTokens":8192}""")
  }

  test("I3c: new fields decode when present and encode only-when-set") {
    val node = parse("""{"enabled":true,"budgetTokens":4096,"effort":"max","clearThinking":false}""")
      .toOption.getOrElse(fail("parse"))
    val tc = node.as[ThinkingConfig].toOption.getOrElse(fail("decode"))
    assertEquals(tc.effort, Some(ThinkingEffort.Max))
    assertEquals(tc.clearThinking, Some(false))
    assertEquals(tc.asJson.noSpaces, """{"enabled":true,"budgetTokens":4096,"effort":"max","clearThinking":false}""")
  }

  test("I3d: provider/model layers tolerate absence (derived decoders) and decode new keys") {
    val bare = parse("""{"id":"glm-5.3-flash","contextWindow":1000000}""").toOption.getOrElse(fail("parse"))
    val mc = bare.as[ModelConfig].toOption.getOrElse(fail("decode"))
    assertEquals(mc.thinking, None)
    assertEquals(mc.thinkingKind, None)
    val rich = parse("""{"id":"m","contextWindow":100000,"thinking":{"effort":"high"},"thinkingKind":"toggle"}""")
      .toOption.getOrElse(fail("parse"))
    val mc2 = rich.as[ModelConfig].toOption.getOrElse(fail("decode"))
    assertEquals(mc2.thinking, Some(ThinkingLayerConfig(effort = Some(ThinkingEffort.High))))
    assertEquals(mc2.thinkingKind, Some(ThinkingKind.Toggle))
  }

  // ── I4：per-model 层定点 patch ───────────────────────────────────────────

  private val configFixture: Json = parse(
    """{"llm":{"providers":{"kimi":{"protocol":"anthropic","apiKey":"k","models":[
      {"id":"k3.1-pro-highspeed","contextWindow":1048576},
      {"id":"kimi-for-coding","contextWindow":1000000}]}}}}"""
  ).toOption.getOrElse(fail("parse"))

  test("I4: patch sets the effort key at the targeted model node only") {
    val patched = ThinkingMode.patchLayerIntoConfig(
      configFixture, "kimi", "k3.1-pro-highspeed",
      ThinkingMode.ThinkingLayerPatch(effort = Some(Some(ThinkingEffort.High)))
    ).toOption.getOrElse(fail("patch"))
    val models = patched.hcursor.downField("llm").downField("providers").downField("kimi")
      .downField("models").as[List[Json]].toOption.getOrElse(fail("models"))
    val k31 = models.find(_.hcursor.downField("id").as[String].toOption.contains("k3.1-pro-highspeed")).getOrElse(fail("model"))
    val kfc = models.find(_.hcursor.downField("id").as[String].toOption.contains("kimi-for-coding")).getOrElse(fail("model"))
    assertEquals(k31.hcursor.downField("thinking").focus,
      Some(Json.obj("effort" -> "high".asJson)))
    assertEquals(kfc.hcursor.downField("thinking").focus, None, "sibling model untouched")
  }

  test("I4b: explicit null clears a key; empty node ⇒ whole thinking key removed (= full inherit)") {
    val withLayer = parse(
      """{"llm":{"providers":{"kimi":{"protocol":"anthropic","apiKey":"k","models":[
        {"id":"k3.1-pro-highspeed","thinking":{"effort":"high","enabled":true}}]}}}}"""
    ).toOption.getOrElse(fail("parse"))
    val cleared = ThinkingMode.patchLayerIntoConfig(
      withLayer, "kimi", "k3.1-pro-highspeed",
      ThinkingMode.ThinkingLayerPatch(effort = Some(None), enabled = Some(None))
    ).toOption.getOrElse(fail("patch"))
    val node = cleared.hcursor.downField("llm").downField("providers").downField("kimi")
      .downField("models").as[List[Json]].toOption.getOrElse(fail("models"))
      .find(_.hcursor.downField("id").as[String].toOption.contains("k3.1-pro-highspeed")).getOrElse(fail("model"))
    assertEquals(node.hcursor.downField("thinking").focus, None, "empty layer node must be removed entirely")
  }

  test("I4c: empty patch removes an existing layer node (inherit-all)") {
    val withLayer = parse(
      """{"llm":{"providers":{"kimi":{"protocol":"anthropic","apiKey":"k","models":[
        {"id":"k3.1-pro-highspeed","thinking":{"effort":"low"}}]}}}}"""
    ).toOption.getOrElse(fail("parse"))
    val cleared = ThinkingMode.patchLayerIntoConfig(
      withLayer, "kimi", "k3.1-pro-highspeed", ThinkingMode.ThinkingLayerPatch()
    ).toOption.getOrElse(fail("patch"))
    val node = cleared.hcursor.downField("llm").downField("providers").downField("kimi")
      .downField("models").as[List[Json]].toOption.getOrElse(fail("models"))
      .find(_.hcursor.downField("id").as[String].toOption.contains("k3.1-pro-highspeed")).getOrElse(fail("model"))
    assertEquals(node.hcursor.downField("thinking").focus, None)
  }

  test("I4d: unknown provider / unknown model → Left (fail-loud, never silent)") {
    val p = ThinkingMode.ThinkingLayerPatch(effort = Some(Some(ThinkingEffort.Low)))
    assert(ThinkingMode.patchLayerIntoConfig(configFixture, "nope", "k3.1-pro-highspeed", p).isLeft)
    assert(ThinkingMode.patchLayerIntoConfig(configFixture, "kimi", "nope", p).isLeft)
  }

  test("I4e: invalid effort value rejected by the patch decoder") {
    val bad = parse("""{"effort":"ultra"}""").toOption.getOrElse(fail("parse"))
    assert(bad.as[ThinkingMode.ThinkingLayerPatch].isLeft)
    val nullEffort = parse("""{"effort":null}""").toOption.getOrElse(fail("parse"))
    assertEquals(nullEffort.as[ThinkingMode.ThinkingLayerPatch].toOption,
      Some(ThinkingMode.ThinkingLayerPatch(effort = Some(None))))
  }

  // ── I5：passback 族判定 ──────────────────────────────────────────────────

  test("I5: passback default = providerId OR any model id hits the deepseek family") {
    val ds = ProviderConfig("https://x", "k", LlmProtocol.Anthropic,
      models = List(ModelConfig("deepseek/deepseek-v4.1-flash")))
    assertEquals(ThinkingFamily.requiresPassback("cmdcode", ds), true, "model-family fix: cmdcode serving deepseek")
    assertEquals(ThinkingFamily.requiresPassback("deepseek",
      ProviderConfig("https://x", "k", LlmProtocol.Anthropic, models = Nil)), true)
    val kimiOnly = ProviderConfig("https://x", "k", LlmProtocol.Anthropic,
      models = List(ModelConfig("kimi-for-coding")))
    assertEquals(ThinkingFamily.requiresPassback("cmdcode", kimiOnly), false)
  }

  test("I5b: explicit requireThinkingPassback always wins") {
    val over = ProviderConfig("https://x", "k", LlmProtocol.Anthropic,
      models = List(ModelConfig("kimi-for-coding")), requireThinkingPassback = Some(true))
    assertEquals(ThinkingFamily.requiresPassback("cmdcode", over), true)
    val under = ProviderConfig("https://x", "k", LlmProtocol.Anthropic,
      models = List(ModelConfig("deepseek/deepseek-v4.1-flash")), requireThinkingPassback = Some(false))
    assertEquals(ThinkingFamily.requiresPassback("cmdcode", under), false)
  }
end ThinkingInheritanceSpec
