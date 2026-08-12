package nebflow.core.presets

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.shared.AgentModelConfig

class PresetStoreSpec extends FunSuite:

  private def tempStore(suffix: String): (PresetStore, os.Path) =
    val dir = os.pwd / "target" / "preset-test" / s"store-$suffix"
    os.makeDir.all(dir)
    val path = dir / "model-presets.json"
    (new PresetStore(path), path)

  // ── Model codec ──────────────────────────────────────────

  test("ModelPreset encode/decode round-trip"):
    val p = ModelPreset("vision", "视觉模型优先", Some("kimi/k3-256k"), List("zhipu/GLM-5V-Turbo"))
    val json = p.asJson.noSpaces
    assertEquals(decode[ModelPreset](json), Right(p))

  test("ModelPreset decode tolerates missing description/preferred"):
    val json = """{"name":"basic"}"""
    val decoded = decode[ModelPreset](json)
    assert(decoded.isRight)
    assertEquals(decoded.toOption.get.name, "basic")
    assertEquals(decoded.toOption.get.description, "")
    assertEquals(decoded.toOption.get.preferred, None)
    assertEquals(decoded.toOption.get.fallbacks, Nil)

  test("PresetFile encode/decode round-trip"):
    val f = PresetFile("general", Map(
      "general" -> ModelPreset("general", "默认", Some("107/glm-5.2-107"), List("deepseek/deepseek-v4-flash")),
      "vision" -> ModelPreset("vision", "视觉优先", Some("kimi/k3-256k"), Nil)
    ))
    val json = f.asJson.noSpaces
    assertEquals(decode[PresetFile](json), Right(f))

  // ── resolve priority ─────────────────────────────────────

  test("resolve: explicit preset wins over legacy model"):
    val (store, _) = tempStore("explicit")
    store.save(PresetFile("general", Map(
      "general" -> ModelPreset("general", "", Some("default/model"), Nil),
      "vision" -> ModelPreset("vision", "", Some("kimi/k3-256k"), List("zhipu/GLM-5V-Turbo"))
    )))
    val legacy = AgentModelConfig(Some("legacy/model"), Nil)
    val (cfg, from) = store.resolve(Some("vision"), Some(legacy))
    assertEquals(cfg.preferred, Some("kimi/k3-256k"))
    assertEquals(cfg.fallbacks, List("zhipu/GLM-5V-Turbo"))
    assertEquals(from, "preset")

  test("resolve: legacy model wins over default preset"):
    val (store, _) = tempStore("legacy")
    store.save(PresetFile("general", Map(
      "general" -> ModelPreset("general", "", Some("default/model"), Nil)
    )))
    val legacy = AgentModelConfig(Some("legacy/model"), List("legacy/fallback"))
    val (cfg, from) = store.resolve(None, Some(legacy))
    assertEquals(cfg.preferred, Some("legacy/model"))
    assertEquals(cfg.fallbacks, List("legacy/fallback"))
    assertEquals(from, "legacy-model")

  test("resolve: default preset when no explicit preset and no legacy"):
    val (store, _) = tempStore("default")
    store.save(PresetFile("general", Map(
      "general" -> ModelPreset("general", "", Some("default/model"), List("default/fallback"))
    )))
    val (cfg, from) = store.resolve(None, None)
    assertEquals(cfg.preferred, Some("default/model"))
    assertEquals(cfg.fallbacks, List("default/fallback"))
    assertEquals(from, "default-preset")

  test("resolve: empty legacy model (None preferred + Nil fallbacks) falls through to default preset"):
    val (store, _) = tempStore("empty-legacy")
    store.save(PresetFile("general", Map(
      "general" -> ModelPreset("general", "", Some("default/model"), Nil)
    )))
    val (cfg, from) = store.resolve(None, Some(AgentModelConfig(None, Nil)))
    assertEquals(cfg.preferred, Some("default/model"))
    assertEquals(from, "default-preset")

  test("resolve: global chain when no preset, no legacy, and default preset is empty"):
    val (store, _) = tempStore("global")
    store.save(PresetFile("general", Map(
      "general" -> ModelPreset("general", "", None, Nil)
    )))
    val (cfg, from) = store.resolve(None, None)
    assertEquals(cfg, AgentModelConfig.empty)
    assertEquals(from, "global")

  test("resolve: dangling preset name falls back to legacy, then default"):
    val (store, _) = tempStore("dangling")
    store.save(PresetFile("general", Map(
      "general" -> ModelPreset("general", "", Some("default/model"), Nil)
    )))
    val legacy = AgentModelConfig(Some("legacy/model"), Nil)
    val (cfg, from) = store.resolve(Some("nonexistent"), Some(legacy))
    assertEquals(cfg.preferred, Some("legacy/model"))
    assertEquals(from, "legacy-model")

  test("resolve: dangling preset with no legacy falls back to default preset"):
    val (store, _) = tempStore("dangling-no-legacy")
    store.save(PresetFile("general", Map(
      "general" -> ModelPreset("general", "", Some("default/model"), Nil)
    )))
    val (cfg, from) = store.resolve(Some("nonexistent"), None)
    assertEquals(cfg.preferred, Some("default/model"))
    assertEquals(from, "default-preset")

  // ── First-use initialization ─────────────────────────────

  test("load: first-use creates general preset from global chain"):
    val dir = os.pwd / "target" / "preset-test" / s"init-${System.nanoTime()}"
    os.makeDir.all(dir)
    val path = dir / "model-presets.json"
    val store = new PresetStore(path)
    // Use the real dataRoot so the global chain can be read. If nebflow.json
    // doesn't exist (CI), the general preset will have empty preferred/fallbacks.
    val f = store.load()
    assertEquals(f.defaultPreset, "general")
    assert(f.presets.contains("general"))
    assertEquals(f.presets("general").name, "general")
    // The file should have been persisted
    assert(os.exists(path))

  test("load: re-reading the file returns saved content"):
    val (store, path) = tempStore("reread")
    val saved = PresetFile("custom", Map(
      "custom" -> ModelPreset("custom", "test", Some("p/m"), List("f1/m"))
    ))
    store.save(saved)
    val loaded = store.load()
    assertEquals(loaded.defaultPreset, "custom")
    assertEquals(loaded.presets("custom").preferred, Some("p/m"))

  test("load: empty presets file triggers re-initialization"):
    val (store, path) = tempStore("empty")
    os.write.over(path, """{"defaultPreset":"general","presets":{}}""")
    val f = store.load()
    assert(f.presets.nonEmpty)
    assert(f.presets.contains("general"))

  test("load: dangling defaultPreset gets repaired"):
    val (store, path) = tempStore("repair")
    store.save(PresetFile("gone", Map(
      "general" -> ModelPreset("general", "", Some("m/a"), Nil),
      "vision" -> ModelPreset("vision", "", Some("k/m"), Nil)
    )))
    // defaultPreset "gone" doesn't exist in presets → repair should pick an existing one
    val f = store.load()
    assert(f.presets.contains(f.defaultPreset))

end PresetStoreSpec
