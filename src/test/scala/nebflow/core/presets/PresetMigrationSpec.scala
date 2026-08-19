package nebflow.core.presets

import munit.FunSuite
import nebflow.core.PathUtil

/** #339 D-a/D-b：种子源语义（llm.model 迁移优先 / providers 推导）与
  * llm.model 一次性迁移（播种→验证→剥离，失败幂等重试）。 */
class PresetMigrationSpec extends FunSuite:

  private def withRoot(name: String)(body: os.Path => Unit): Unit =
    val tmp = os.pwd / "target" / s"preset-mig-$name-${System.nanoTime().toHexString}"
    os.makeDir.all(tmp)
    PathUtil.setDataRoot(tmp)
    try body(tmp)
    finally
      PathUtil.setDataRoot(os.Path("/tmp"))
      os.remove.all(tmp)

  private def writeNebflow(root: os.Path, content: String): Unit =
    os.write.over(root / "nebflow.json", content.replaceAll("\\s+", " "))

  private val providerX =
    """{"baseUrl":"https://x.example.com/v1","apiKey":"k","protocol":"openai",
        "models":[{"id":"m1","maxTokens":4096,"contextWindow":8192},{"id":"m2","maxTokens":8192,"contextWindow":16384}]}"""
  private val providerY =
    """{"baseUrl":"https://y.example.com/v1","apiKey":"k","protocol":"openai",
        "models":[{"id":"y1","maxTokens":2048,"contextWindow":4096}]}"""

  // ── 种子链（D-a）──────────────────────────────────────────

  test("seed chain: llm.model wins over providers (migration-first)") {
    withRoot("seed-prio") { root =>
      writeNebflow(root,
        s"""{"llm":{"providers":{"X":$providerX,"Y":$providerY},"model":{"default":"X/m1","fallbacks":["Y/y1"]}}}""")
      assertEquals(PresetStore.readSeedChain(), List("X/m1", "Y/y1"))
    }
  }

  test("seed chain: providers-derived when no llm.model (first provider first model, JSON field order)") {
    withRoot("seed-prov") { root =>
      // JSON 字段序 Y 在前——"首个"按文件顺序而非字母序
      writeNebflow(root, s"""{"llm":{"providers":{"Y":$providerY,"X":$providerX}}}""")
      assertEquals(PresetStore.readSeedChain(), List("Y/y1"))
    }
  }

  test("seed chain: empty install → Nil") {
    withRoot("seed-empty") { root =>
      writeNebflow(root, """{"llm":{"providers":{}}}""")
      assertEquals(PresetStore.readSeedChain(), Nil)
      // 无 nebflow.json 同样 Nil
      os.remove(root / "nebflow.json")
      assertEquals(PresetStore.readSeedChain(), Nil)
    }
  }

  test("cold-start: ensureDefaultPreset seeds from providers (single-element chain, empty fallbacks)") {
    withRoot("cold-start") { root =>
      writeNebflow(root, s"""{"llm":{"providers":{"X":$providerX}}}""")
      val store = PresetStore()
      store.ensureDefaultPreset()
      val f = store.load()
      assertEquals(f.defaultPreset, "general")
      val general = f.presets("general")
      assertEquals(general.preferred, Some("X/m1"))
      assertEquals(general.fallbacks, Nil, "fallbacks 留空——用户显式决策，不自动塞满（#311 病灶）")
      assert(general.description.contains("初始配置"), s"description: ${general.description}")
    }
  }

  // ── 迁移（D-b：播种→验证→剥离）────────────────────────────

  test("migration: no preset + llm.model → seeded then stripped") {
    withRoot("mig-seed") { root =>
      writeNebflow(root, s"""{"llm":{"providers":{"X":$providerX},"model":{"default":"X/m2","fallbacks":["X/m1"]}}}""")
      assert(PresetStore.migrateGlobalModelChain())

      // preset 已播种（链来自 llm.model）
      val f = PresetStore().load()
      assertEquals(f.presets("general").preferred, Some("X/m2"))
      assertEquals(f.presets("general").fallbacks, List("X/m1"))

      // llm.model 节已剥离，providers 原样保留
      val saved = io.circe.parser.parse(os.read(root / "nebflow.json")).toOption.get
      assert(saved.hcursor.downField("llm").downField("model").focus.isEmpty)
      assert(saved.hcursor.downField("llm").downField("providers").downField("X").focus.isDefined)
    }
  }

  test("migration: healthy preset + llm.model residue → preset byte-identical, field stripped") {
    withRoot("mig-healthy") { root =>
      val presets =
        """{"defaultPreset":"general","presets":{
            "general":{"name":"general","description":"d","preferred":"X/m1","fallbacks":[]},
            "vision":{"name":"vision","description":"v","preferred":"Y/y1","fallbacks":[]}}}""".replaceAll("\\s+", "")
      os.write.over(root / "model-presets.json", presets)
      writeNebflow(root, s"""{"llm":{"providers":{"X":$providerX},"model":{"default":"X/m1","fallbacks":[]}}}""")
      assert(PresetStore.migrateGlobalModelChain())
      // 有感保护：健康 preset 文件逐字节不变（hash 断言）
      assertEquals(os.read(root / "model-presets.json"), presets)
      val saved = io.circe.parser.parse(os.read(root / "nebflow.json")).toOption.get
      assert(saved.hcursor.downField("llm").downField("model").focus.isEmpty)
    }
  }

  test("migration: idempotent — second boot no-op, files untouched") {
    withRoot("mig-idem") { root =>
      writeNebflow(root, s"""{"llm":{"providers":{"X":$providerX},"model":{"default":"X/m1","fallbacks":[]}}}""")
      assert(PresetStore.migrateGlobalModelChain())
      val afterFirst = os.read(root / "nebflow.json")
      val presetsAfterFirst = os.read(root / "model-presets.json")
      // 第二次：无 llm.model 节 → false（无事可做），零写入
      assert(!PresetStore.migrateGlobalModelChain())
      assertEquals(os.read(root / "nebflow.json"), afterFirst)
      assertEquals(os.read(root / "model-presets.json"), presetsAfterFirst)
    }
  }

  test("migration: preset not verifiable → strip deferred, field survives for retry") {
    withRoot("mig-defer") { root =>
      // llm.model 存在但链为空（default 缺失）且无 providers——播种不出健康
      // preset → 中止剥离，字段留存（下次启动重试）
      writeNebflow(root, """{"llm":{"providers":{},"model":{"fallbacks":[]}}}""")
      assert(!PresetStore.migrateGlobalModelChain())
      val saved = io.circe.parser.parse(os.read(root / "nebflow.json")).toOption.get
      assert(saved.hcursor.downField("llm").downField("model").focus.isDefined, "llm.model 必须留存待重试")
    }
  }

end PresetMigrationSpec
