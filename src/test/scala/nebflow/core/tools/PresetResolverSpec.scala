package nebflow.core.tools

import munit.FunSuite
import nebflow.agent.AgentDef
import nebflow.core.presets.{ModelPreset, PresetFile, PresetStore}
import nebflow.shared.AgentModelConfig

/**
 * PresetResolver — Delegate/SubTask `preset` param override semantics
 * (issue #291, 2026-08-18).
 *
 * Priority: explicit preset (tool param) > agent.json preset/model > default
 * preset (terminal — the global-chain fallback level was removed, #311). The
 * explicit preset replaces the AgentDef's resolved model entirely — the LLM
 * layer then uses the preset's chain for the child.
 */
class PresetResolverSpec extends FunSuite:

  private def storeWith(presets: (String, ModelPreset)*): PresetStore =
    val dir = os.pwd / "target" / "preset-resolver-test" / s"store-${System.nanoTime()}"
    os.makeDir.all(dir)
    val path = dir / "model-presets.json"
    val store = new PresetStore(path)
    store.save(PresetFile("general", presets.toMap))
    store

  private val lowCostPreset =
    ModelPreset("LowCost", "", Some("107/deepseek-v4-flash-ascend"), List("107/deepseek-v4-pro"))

  private def defWithModel(preferred: String): AgentDef =
    AgentDef(
      name = "agent",
      description = "test",
      model = Some(AgentModelConfig(Some(preferred), Nil)),
      preset = Some("general")
    )

  test("no preset param → def unchanged"):
    val store = storeWith("LowCost" -> lowCostPreset)
    val def0 = defWithModel("zhipu/GLM-5.3")
    assertEquals(PresetResolver.applyPreset(store, def0, None), Right(def0))

  test("empty preset param string → treated as absent"):
    val store = storeWith("LowCost" -> lowCostPreset)
    val def0 = defWithModel("zhipu/GLM-5.3")
    assertEquals(PresetResolver.applyPreset(store, def0, Some("")), Right(def0))

  test("explicit preset overrides the def's own resolved model"):
    // The def's own model (from agent.json preset "general") is replaced by the
    // explicit LowCost chain — explicit > agent.json preset.
    val store = storeWith("LowCost" -> lowCostPreset, "general" -> ModelPreset("general", "", Some("zhipu/GLM-5.3"), Nil))
    val def0 = defWithModel("zhipu/GLM-5.3")
    val result = PresetResolver.applyPreset(store, def0, Some("LowCost"))
    assert(result.isRight)
    val applied = result.toOption.get
    assertEquals(applied.model, Some(AgentModelConfig(Some("107/deepseek-v4-flash-ascend"), List("107/deepseek-v4-pro"))))
    assertEquals(applied.preset, Some("LowCost"))
    // modelOverride carries the chain across the per-turn def refresh
    assertEquals(applied.modelOverride, Some(AgentModelConfig(Some("107/deepseek-v4-flash-ascend"), List("107/deepseek-v4-pro"))))

  test("explicit preset overrides even when the def has no model"):
    // Default-preset/global fallback would normally apply — explicit still wins.
    val store = storeWith("LowCost" -> lowCostPreset)
    val bare = AgentDef(name = "agent", description = "test")
    val result = PresetResolver.applyPreset(store, bare, Some("LowCost"))
    assert(result.isRight)
    assertEquals(result.toOption.get.model, Some(AgentModelConfig(Some("107/deepseek-v4-flash-ascend"), List("107/deepseek-v4-pro"))))

  test("missing preset → Left with available list"):
    val store = storeWith("LowCost" -> lowCostPreset, "Vision" -> ModelPreset("Vision", "", Some("kimi/k3-256k"), Nil))
    val result = PresetResolver.applyPreset(store, defWithModel("zhipu/GLM-5.3"), Some("Bogus"))
    assert(result.isLeft)
    val err = result.swap.toOption.get
    assert(err.contains("'Bogus' not found"), err)
    assert(err.contains("Available presets: LowCost, Vision"), err)

  test("defined-but-empty preset → Left with available list"):
    val store = storeWith("Empty" -> ModelPreset("Empty", "no chain", None, Nil), "LowCost" -> lowCostPreset)
    val result = PresetResolver.applyPreset(store, defWithModel("zhipu/GLM-5.3"), Some("Empty"))
    assert(result.isLeft)
    val err = result.swap.toOption.get
    assert(err.contains("no model chain"), err)
    assert(err.contains("Available presets: Empty, LowCost"), err)

end PresetResolverSpec
