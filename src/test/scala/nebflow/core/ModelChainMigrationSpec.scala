package nebflow.core

import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.AgentDef
import nebflow.core.presets.{ModelChainRegistry, PresetStore}
import nebflow.core.tools.PresetResolver
import nebflow.shared.AgentModelConfig
import nebflow.shared.PathUtil // W1 shim: main had nebflow.core.PathUtil; PR moved it to shared

/**
 * ModelChainMigration — one-time preset→chain migration (boot call,
 * idempotent). Verifies the migration table: Nebula's effective preset
 * reference becomes its own model chain verbatim; the other role agents'
 * preset keys are removed; the catalog file is sealed with a timestamp
 * suffix; a second run is a no-op.
 */
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
      assertEquals(
        nebula.hcursor.downField("model").as[AgentModelConfig].toOption,
        Some(ModelChainMigration.DefaultNebulaChain)
      )
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
      assertEquals(
        nebula.hcursor.downField("model").as[AgentModelConfig].toOption,
        Some(AgentModelConfig(Some("a/b"), Nil))
      )
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

  // ── preset→chain 迁移批（2026-10-30）：链注册表面 ──────────────

  private val generalChain: AgentModelConfig =
    AgentModelConfig(
      preferred = Some("cmdcode/deepseek/deepseek-v4.1-flash"),
      fallbacks = List("kimi/kimi-k3", "zhipu/GLM-5.3-Flash", "deepseek/deepseek-flash")
    )

  test("preset definitions migrate to the chain registry; legacy preset names keep resolving (SubTask preset face)") {
    withRoot("registry") { root =>
      writeAgent(root, "Nebula", """{"preset":"LowCost","name":"Nebula"}""")
      os.write.over(root / "model-presets.json", catalogJson)

      ModelChainMigration.migrateLegacyPresetRefs()

      // ① 注册表落盘：preset 原名逐字保留（旧名兼容 = 名随迁移带走，无别名表），链随定义走
      val pf = ModelChainRegistry.load(root).get
      assertEquals(pf.defaultPreset, "general")
      assertEquals(pf.presets.keySet, Set("general", "Vision", "LowCost"))
      assertEquals(pf.presets("general").preferred, generalChain.preferred)
      assertEquals(pf.presets("general").fallbacks, generalChain.fallbacks)
      assert(ModelChainRegistry.migratedAt(root).isDefined, "迁移完成戳必须落下")
      assert(!os.exists(root / "model-presets.json"), "原目录文件封存后不再出现于原路径")

      // ② PresetStore 默认路径读面切注册表：旧 preset 名经 resolveExplicit 解析到迁移后的链
      val store = new PresetStore()
      assertEquals(store.resolveExplicit("general"), Right(generalChain))
      assertEquals(store.availableNames, "LowCost, Vision, general")

      // ③ SubTask preset 参数腿（PresetResolver = SubTask 调用点）走迁移后的链
      val bare = AgentDef(name = "worker", description = "t")
      val applied = PresetResolver.applyPreset(store, bare, Some("general"))
      assert(applied.isRight, s"legacy preset name must resolve on migrated home: $applied")
      assertEquals(applied.toOption.get.model, Some(generalChain))
      assertEquals(applied.toOption.get.preset, Some("general"))
    }
  }

  test("migrated home: ensureDefaultPreset no churn; repair writes the registry, never re-seeds the sealed catalog") {
    withRoot("nochurn") { root =>
      writeAgent(root, "Nebula", """{"preset":"LowCost","name":"Nebula"}""")
      os.write.over(root / "model-presets.json", catalogJson)
      ModelChainMigration.migrateLegacyPresetRefs()
      val registryBytes = os.read(root / "model-chains.json")

      // healthy 注册表 ⇒ 不变量执行是纯读：不重写、不重建 legacy 目录文件
      PresetStore().ensureDefaultPreset()
      assert(!os.exists(root / "model-presets.json"), "封存的目录文件不得被 re-seed 重建")
      assertEquals(os.read(root / "model-chains.json"), registryBytes, "healthy 注册表逐字不动（零churn）")

      // dangling defaultPreset ⇒ repair 重指 AND 写回注册表（写面同帧落注册表）
      val broken = io.circe.parser
        .parse(registryBytes)
        .toOption
        .get
        .deepMerge(io.circe.Json.obj("defaultPreset" -> "Bogus".asJson))
      os.write.over(root / "model-chains.json", broken.noSpaces)
      val repaired = PresetStore().load()
      assertEquals(repaired.defaultPreset, "LowCost", "repair 必须重指到首个带链条目（渲染按名排序 ⇒ LowCost）")
      assert(!os.exists(root / "model-presets.json"), "repair 不得重建 legacy 目录文件")
      assertEquals(ModelChainRegistry.load(root).get.defaultPreset, "LowCost", "repair 写回注册表")
    }
  }

  test("registry merge is write-ahead idempotent: a replayed migration never overwrites existing entries") {
    withRoot("replay") { root =>
      writeAgent(root, "Nebula", """{"preset":"LowCost","name":"Nebula"}""")
      os.write.over(root / "model-presets.json", catalogJson)
      ModelChainMigration.migrateLegacyPresetRefs()

      // 模拟「写后封存前进程死」：注册表已迁移、目录文件仍在 ⇒ 重放合并
      // 既有条目恒胜 —— 用户后编辑的链不被陈旧目录覆盖。
      val edited = io.circe.Json.obj(
        "version"    -> 1.asJson,
        "migratedAt" -> "2026-10-30T08:29:00+08:00".asJson,
        "defaultPreset" -> "general".asJson,
        "chains" -> io.circe.Json.obj(
          "general" -> io.circe.Json.obj(
            "name"        -> "general".asJson,
            "description" -> "user edit after migration".asJson,
            "preferred"   -> "kimi/kimi-for-coding".asJson,
            "fallbacks"   -> io.circe.Json.arr()
          )
        )
      )
      os.write.over(root / "model-chains.json", edited.noSpaces)
      // 模拟「写后封存前进程死」：把封存文件挪回原始路径 ⇒ 二次启动重放迁移腿
      val sealedFile = os.list(root).find(_.last.startsWith("model-presets.json.sealed-")).get
      os.move(sealedFile, root / "model-presets.json")
      ModelChainMigration.migrateLegacyPresetRefs()

      val pf = ModelChainRegistry.load(root).get
      assertEquals(
        pf.presets("general").preferred,
        Some("kimi/kimi-for-coding"),
        "既有注册表条目必须胜过重放的目录数据（write-ahead 幂等）"
      )
      assertEquals(ModelChainRegistry.migratedAt(root).get, "2026-10-30T08:29:00+08:00", "迁移完成戳不重写")
      // 重放补齐缺失条目（LowCost/Vision 不在用户编辑版里）
      assertEquals(pf.presets.keySet, Set("general", "Vision", "LowCost"))
    }
  }
end ModelChainMigrationSpec
