package nebflow.core

import munit.FunSuite
import nebflow.shared.AgentModelConfig
import nebflow.shared.PathUtil // W1 shim: main had nebflow.core.PathUtil; PR moved it to shared

/** ModelChainMigration — one-time preset→chain migration (boot call,
  * idempotent). Verifies the migration table: Nebula's effective preset
  * reference becomes its own model chain verbatim; the other role agents'
  * preset keys are removed; the catalog file is sealed with a timestamp
  * suffix; a second run is a no-op. */
class ModelChainMigrationSpec extends FunSuite:

  private val lowCost = AgentModelConfig(
    preferred = Some("zhipu/GLM-5.3-Flash"),
    fallbacks = List("cmdcode/deepseek/deepseek-v4.1-flash", "kimi/kimi-k3", "deepseek/deepseek-flash")
  )

  private def withRoot(name: String)(body: os.Path => Unit): Unit =
    val tmp = os.pwd / "target" / s"modelchain-mig-$name-${System.nanoTime().toHexString}"
    os.makeDir.all(tmp)
    PathUtil.setDataRoot(tmp)
    try body(tmp)
    finally
      PathUtil.setDataRoot(os.Path("/tmp"))
      os.remove.all(tmp)

  private def writeAgent(root: os.Path, name: String, json: String): Unit =
    val dir = root / "agents" / name
    os.makeDir.all(dir)
    os.write.over(dir / "agent.json", json)

  private def readAgent(root: os.Path, name: String): io.circe.Json =
    io.circe.parser.parse(os.read(root / "agents" / name / "agent.json")).toOption.get

  private def catalogJson: String =
    """{"defaultPreset":"general","presets":{""" +
      """"general":{"name":"general","description":"default","preferred":"cmdcode/deepseek/deepseek-v4.1-flash","fallbacks":["kimi/kimi-k3","zhipu/GLM-5.3-Flash","deepseek/deepseek-flash"]},""" +
      """"Vision":{"name":"Vision","description":"","preferred":"kimi/kimi-k3","fallbacks":["zhipu/GLM-5.3-Flash","deepseek/deepseek-flash"]},""" +
      """"LowCost":{"name":"LowCost","description":"","preferred":"zhipu/GLM-5.3-Flash","fallbacks":["cmdcode/deepseek/deepseek-v4.1-flash","kimi/kimi-k3","deepseek/deepseek-flash"]}}}"""

  test("migration table: Nebula chain verbatim, follower keys removed, catalog sealed") {
    withRoot("table") { root =>
      writeAgent(root, "Nebula", """{"preset":"LowCost","name":"Nebula"}""")
      writeAgent(root, "project-dispatcher", """{"preset":"LowCost","name":"project-dispatcher"}""")
      writeAgent(root, "kernel", """{"preset":"general","name":"kernel"}""")
      writeAgent(root, "general", """{"preset":"general","name":"general"}""")
      os.write.over(root / "model-presets.json", catalogJson)
      val catalogBefore = os.read(root / "model-presets.json")

      ModelChainMigration.migrateLegacyPresetRefs()

      val nebula = readAgent(root, "Nebula")
      assertEquals(nebula.hcursor.downField("model").as[AgentModelConfig].toOption, Some(lowCost))
      assert(!nebula.hcursor.downField("preset").succeeded, "Nebula preset key removed")
      for name <- List("project-dispatcher", "kernel", "general") do
        val j = readAgent(root, name)
        assert(!j.hcursor.downField("preset").succeeded, s"$name preset key removed")
        assert(!j.hcursor.downField("model").succeeded, s"$name gains no model key (follower)")

      // sealed: original path gone, timestamped copy keeps the original bytes
      assert(!os.exists(root / "model-presets.json"), "catalog path empty after sealing")
      val sealedFiles = os.list(root).filter(_.last.startsWith("model-presets.json.sealed-")).toList
      assertEquals(sealedFiles.size, 1, "exactly one sealed copy")
      assertEquals(os.read(sealedFiles.head), catalogBefore, "sealed copy byte-identical")
    }
  }

  test("idempotent: second run changes nothing") {
    withRoot("idempotent") { root =>
      writeAgent(root, "Nebula", """{"preset":"LowCost","name":"Nebula"}""")
      writeAgent(root, "kernel", """{"preset":"general","name":"kernel"}""")
      os.write.over(root / "model-presets.json", catalogJson)

      ModelChainMigration.migrateLegacyPresetRefs()
      val snapshot = os.walk(root).filter(os.isFile).map(p => p.last -> os.read(p)).toMap

      ModelChainMigration.migrateLegacyPresetRefs()
      val snapshot2 = os.walk(root).filter(os.isFile).map(p => p.last -> os.read(p)).toMap

      assertEquals(snapshot2, snapshot, "second run must not touch any file")
    }
  }

  test("degraded catalog: Nebula LowCost reference falls back to the built-in default chain") {
    withRoot("no-catalog") { root =>
      writeAgent(root, "Nebula", """{"preset":"LowCost","name":"Nebula"}""")
      ModelChainMigration.migrateLegacyPresetRefs()
      val nebula = readAgent(root, "Nebula")
      assertEquals(nebula.hcursor.downField("model").as[AgentModelConfig].toOption,
        Some(ModelChainMigration.DefaultNebulaChain))
      assertEquals(ModelChainMigration.DefaultNebulaChain, lowCost)
    }
  }

  test("dangling non-default reference: key removed, seed chain applies") {
    withRoot("dangling") { root =>
      writeAgent(root, "Nebula", """{"preset":"Bogus","name":"Nebula"}""")
      ModelChainMigration.migrateLegacyPresetRefs()
      val nebula = readAgent(root, "Nebula")
      assert(!nebula.hcursor.downField("preset").succeeded)
      assert(!nebula.hcursor.downField("model").succeeded)
    }
  }

  test("no-op home: no preset keys and no catalog file -> untouched") {
    withRoot("noop") { root =>
      writeAgent(root, "Nebula", """{"name":"Nebula","model":{"preferred":"a/b","fallbacks":[]}}""")
      ModelChainMigration.migrateLegacyPresetRefs()
      val nebula = readAgent(root, "Nebula")
      assertEquals(nebula.hcursor.downField("model").as[AgentModelConfig].toOption,
        Some(AgentModelConfig(Some("a/b"), Nil)))
      assertEquals(os.list(root).filter(_.last.startsWith("model-presets.json")).toList, Nil)
    }
  }

  test("non-role agents keep their stored references (audit-only)") {
    withRoot("non-role") { root =>
      writeAgent(root, "coder", """{"preset":"LowCost","name":"coder"}""")
      os.write.over(root / "model-presets.json", catalogJson)
      ModelChainMigration.migrateLegacyPresetRefs()
      val coder = readAgent(root, "coder")
      assertEquals(coder.hcursor.downField("preset").as[String].toOption, Some("LowCost"))
    }
  }
