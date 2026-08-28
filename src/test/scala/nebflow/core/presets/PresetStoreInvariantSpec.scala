package nebflow.core.presets

import munit.FunSuite
import nebflow.shared.AgentModelConfig

/**
 * #311 (2026-08-19): preset invariant — a usable default preset ALWAYS exists
 * once any model is configured, and the old level-4 silent fallback to the
 * global chain (nebflow.json `llm.model`) is REMOVED.
 *
 * Incident: the global default (provider-x/model-y) silently served every agent
 * without an explicit chain while the settings UI showed general/GLM-5.3 — a
 * hidden, hard-to-see routing bug. llm.model is retired (#339): it seeds the
 * initial preset once during migration, then the field is stripped at boot —
 * never a live fallback.
 * preset (created at first provider save / gateway boot); it is never a live
 * fallback.
 */
class PresetStoreInvariantSpec extends FunSuite:

  /** Temp-path store with an injected global chain (isolated from ~/.nebflow). */
  private def tempStore(globalChain: List[String] = Nil): (PresetStore, os.Path) =
    val dir = os.pwd / "target" / s"preset-invariant-test-${System.nanoTime()}"
    os.makeDir.all(dir)
    val path = dir / "model-presets.json"
    (new PresetStore(path, () => globalChain), path)

  test("ensureDefaultPreset creates a seeded default preset when the file is absent") {
    val (store, path) = tempStore(globalChain = List("zhipu/GLM-5.3", "deepseek/deepseek-v4-flash"))
    assert(!os.exists(path))
    store.ensureDefaultPreset()
    assert(os.exists(path))
    val f = store.load()
    assertEquals(f.defaultPreset, "general")
    assertEquals(f.presets("general").preferred, Some("zhipu/GLM-5.3"))
    assertEquals(f.presets("general").fallbacks, List("deepseek/deepseek-v4-flash"))
    // No preset ref, no legacy model → the seeded default, never empty
    val (cfg, from) = store.resolve(None, None)
    assertEquals(from, "default-preset")
    assertEquals(cfg.preferred, Some("zhipu/GLM-5.3"))
  }

  test("resolve never returns the removed global level — chain-less default repaired to a chained sibling") {
    val (store, _) = tempStore(globalChain = List("provider-x/model-y"))
    store.save(
      PresetFile(
        defaultPreset = "broken",
        presets = Map(
          "broken" -> ModelPreset("broken", "", None, Nil), // chain-less default
          "good" -> ModelPreset("good", "", Some("kimi/k3-256k"), Nil)
        )
      )
    )
    val (cfg, from) = store.resolve(None, None)
    assertEquals(from, "default-preset") // NOT "global" — that level is gone
    assertEquals(cfg.preferred, Some("kimi/k3-256k")) // re-pointed to the sibling
    assertEquals(store.load().defaultPreset, "good")   // repair persisted
  }

  test("dangling defaultPreset re-pointed to a preset with a chain") {
    val (store, _) = tempStore(globalChain = List("zhipu/GLM-5.3"))
    store.save(
      PresetFile(
        defaultPreset = "does-not-exist",
        presets = Map("general" -> ModelPreset("general", "", Some("zhipu/GLM-5.3"), Nil))
      )
    )
    val (cfg, from) = store.resolve(None, None)
    assertEquals(from, "default-preset")
    assertEquals(cfg.preferred, Some("zhipu/GLM-5.3"))
    assertEquals(store.load().defaultPreset, "general")
  }

  test("all presets chain-less + global chain available → re-seeded from the configured model") {
    val (store, _) = tempStore(globalChain = List("zhipu/GLM-5.3"))
    store.save(PresetFile("empty", Map("empty" -> ModelPreset("empty", "", None, Nil))))
    val (cfg, from) = store.resolve(None, None)
    assertEquals(from, "default-preset")
    assertEquals(cfg.preferred, Some("zhipu/GLM-5.3"))
  }

  test("explicit preset and legacy model keep precedence over the default") {
    val (store, _) = tempStore(globalChain = List("zhipu/GLM-5.3"))
    store.save(
      PresetFile(
        "general",
        Map(
          "general" -> ModelPreset("general", "", Some("zhipu/GLM-5.3"), Nil),
          "LowCost" -> ModelPreset("LowCost", "", Some("provider-x/model-y"), Nil)
        )
      )
    )
    assertEquals(store.resolve(Some("LowCost"), None)._2, "preset")
    assertEquals(store.resolve(Some("LowCost"), None)._1.preferred, Some("provider-x/model-y"))
    val (legacy, lfrom) = store.resolve(None, Some(AgentModelConfig(Some("deepseek/deepseek-v4-pro"), Nil)))
    assertEquals(lfrom, "legacy-model")
    assertEquals(legacy.preferred, Some("deepseek/deepseek-v4-pro"))
  }

  test("healthy file untouched by ensureDefaultPreset (no rewrite churn)") {
    val (store, path) = tempStore(globalChain = List("provider-x/model-y"))
    store.save(
      PresetFile(
        "general",
        Map("general" -> ModelPreset("general", "", Some("zhipu/GLM-5.3"), List("deepseek/deepseek-v4-flash")))
      )
    )
    val before = os.read(path)
    store.ensureDefaultPreset()
    assertEquals(os.read(path), before)
  }

end PresetStoreInvariantSpec
