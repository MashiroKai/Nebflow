package nebflow.shared

import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite

/**
 * Config file single entry point (`nebflow.shared` face): the brand-named
 * config file (`stand.json`) is the only truth reads prefer and the only name
 * writes ever target; a legacy-named file is served IN PLACE when it is the
 * only one present (non-destructive compat). Also pins the model-level →
 * provider-level unavailable-window convergence.
 *
 * Assertion discipline: every assertion is mechanical. Paths are parameterized
 * (`configJsonPath(dir)`) so the spec never reads or writes the real HOME.
 */
class ConfigSingleSourceSpec extends FunSuite:

  private def tmpDir(name: String): os.Path =
    os.Path(java.nio.file.Files.createTempDirectory(s"nf-cfg-$name").toFile.getAbsolutePath)

  private val sampleConfig =
    """{
      |  "llm": {"providers": {"alpha": {"baseUrl": "http://a/", "apiKey": "k", "protocol": "openai",
      |    "models": [{"id": "m1", "contextWindow": 1000}, {"id": "m2", "contextWindow": 2000}]}}},
      |  "fleet": {"x": 1}
      |}""".stripMargin

  // ── A. Legacy-name read fallback (non-destructive; writes always canonical) ──

  test("A1: only legacy nebflow.json -> read face serves it IN PLACE (never renamed, no .bak, no stand.json created)") {
    val dir = tmpDir("legacy")
    os.write(dir / "nebflow.json", sampleConfig)
    val path = PathUtil.configJsonPath(dir)
    assertEquals(path, dir / "nebflow.json", "read face must resolve to the legacy file")
    assertEquals(os.read(path), sampleConfig, "legacy file must be served byte-identical")
    assert(os.exists(dir / "nebflow.json"), "legacy file must stay in place (never renamed or copied)")
    assert(!os.exists(dir / "nebflow.json.bak"), "no .bak may be created")
    assert(!os.exists(dir / Branding.configFileName), "a read must not create stand.json")
  }

  test("A2: only stand.json exists -> it is the truth") {
    val dir = tmpDir("canonical")
    os.write(dir / Branding.configFileName, sampleConfig)
    val path = PathUtil.configJsonPath(dir)
    assertEquals(path, dir / Branding.configFileName)
    assertEquals(os.read(path), sampleConfig)
  }

  test("A3: both files exist -> stand.json wins, legacy file untouched") {
    val dir = tmpDir("both")
    os.write(dir / "nebflow.json", """{"stale":"legacy"}""")
    os.write(dir / Branding.configFileName, sampleConfig)
    val path = PathUtil.configJsonPath(dir)
    assertEquals(path, dir / Branding.configFileName, "canonical file wins when present")
    assertEquals(os.read(dir / "nebflow.json"), """{"stale":"legacy"}""", "legacy file must not be touched")
  }

  test("A4: neither file exists -> canonical name returned, read face creates nothing") {
    val dir = tmpDir("fresh")
    val path = PathUtil.configJsonPath(dir)
    assertEquals(path.last, "stand.json")
    assert(!os.exists(path), "the read face must not create files")
  }

  test("A5: write face is ALWAYS the canonical stand.json (legacy-only state included)") {
    val dir = tmpDir("writeface")
    os.write(dir / "nebflow.json", sampleConfig)
    assertEquals(
      PathUtil.configJsonWritePath(dir),
      dir / Branding.configFileName,
      "writes must target the canonical name even while reads fall back to the legacy file"
    )
    // Intentional legacy-only asymmetry: read face = legacy file, write face =
    // canonical; read-modify-write callers carry the legacy content over on
    // their first write (copy-on-first-write convergence).
    assert(PathUtil.configJsonReadPath(dir) != PathUtil.configJsonWritePath(dir))
  }

  test("A6: legacy-only config stays readable through the read face (back-compat)") {
    val dir = tmpDir("legacyread")
    os.write(dir / "nebflow.json", sampleConfig)
    assertEquals(os.read(PathUtil.configJsonReadPath(dir)), sampleConfig)
  }

  // ── D. 不可用时段收敛到 provider 级（裁定④）────────────────────────────

  test("D1: model 级不可用时段向上合并到 provider 级，且不丢任何一条目") {
    val withModelWindows =
      """{"llm":{"providers":{"p":{"baseUrl":"http://x/","apiKey":"k","protocol":"openai",
        |"unavailableWindows":[{"from":"01:00","to":"02:00"}],
        |"models":[{"id":"m1","contextWindow":1000,"unavailableWindows":[{"from":"14:00","to":"18:00"},{"from":"01:00","to":"02:00"}]},
        |           {"id":"m2","contextWindow":1000,"unavailableWindows":[{"from":"22:00","to":"23:00","days":[1,3]}]}]}}}}""".stripMargin
    val json = parse(withModelWindows).toOption.get
    val merged = Config.mergeModelWindowsIntoProvider(json)
    val hc = merged.hcursor.downField("llm").downField("providers").downField("p")
    val providerWindows = hc.downField("unavailableWindows").as[List[Json]].toOption.get
    def key(w: Json) = (
      w.hcursor.downField("from").as[String].toOption.get,
      w.hcursor.downField("to").as[String].toOption.get,
      w.hcursor.downField("days").as[Option[List[Int]]].toOption.flatten
    )
    assertEquals(
      providerWindows.map(key),
      List(("01:00", "02:00", None), ("14:00", "18:00", None), ("22:00", "23:00", Some(List(1, 3)))),
      "provider 级 = 原 provider 条目 + 各 model 条目（去重、顺序保持）"
    )
    val models = hc.downField("models").as[List[Json]].toOption.get
    assert(models.forall(_.hcursor.downField("unavailableWindows").focus.isEmpty), "model 级键必须被移除（单一落点）")
  }

  test("D2: 已收敛配置再跑一次 == 幂等（逐字相同）") {
    val once =
      """{"llm":{"providers":{"p":{"baseUrl":"http://x/","apiKey":"k","protocol":"openai",
        |"unavailableWindows":[{"from":"09:00","to":"10:00"}],
        |"models":[{"id":"m1","contextWindow":1000}]}}}}""".stripMargin
    val j = parse(once).toOption.get
    assertEquals(Config.mergeModelWindowsIntoProvider(j).noSpaces, j.noSpaces)
  }

  test("D3: 无任何时段时配置逐字不变（零行为变化铁律）") {
    val j = parse(sampleConfig).toOption.get
    assertEquals(Config.mergeModelWindowsIntoProvider(j).noSpaces, j.noSpaces)
  }

  test("D4: 合并后运行时只读 provider 级（model 级为空）") {
    val withModelWindows =
      """{"llm":{"providers":{"p":{"baseUrl":"http://x/","apiKey":"k","protocol":"openai",
        |"models":[{"id":"m1","contextWindow":1000,"unavailableWindows":[{"from":"00:00","to":"23:59"}]}]}}}}""".stripMargin
    val cfg = Config
      .normalizeConfigJson(parse(withModelWindows).toOption.get)
      .as[NebflowServiceConfig]
      .toOption
      .get
    val provider = cfg.llm.providers("p")
    assertEquals(provider.unavailableWindows.map(_.size), Some(1), "合并后 provider 级持有该窗口")
    assertEquals(provider.models.head.unavailableWindows.getOrElse(Nil), Nil, "model 级已清空")
  }

  test("decisionRoutes: per-call route decodes as independent primary; missing map remains unset") {
    val json = parse("""{"llm":{"providers":{},"decisionRoutes":{"plugin-allocation":{"preferred":"p/m"},"rerank":{"preferred":"q/r"}}}}""").toOption.get
    val cfg = Config.normalizeConfigJson(json).as[NebflowServiceConfig].toOption.get
    assertEquals(cfg.llm.decisionRoutes.flatMap(_.get("plugin-allocation")).flatMap(_.preferred), Some("p/m"))
    assertEquals(cfg.llm.decisionRoutes.flatMap(_.get("rerank")).flatMap(_.preferred), Some("q/r"))
    val inherited = Config.normalizeConfigJson(parse("""{"llm":{"providers":{}}}""").toOption.get).as[NebflowServiceConfig].toOption.get
    assertEquals(inherited.llm.decisionRoutes, None)
  }

  // ── B. llm.fallback 小节的形状与校验告警（裁定①③）──────────────────────

  test("B1: llm.fallback 小节可解码（preferred 单根；fallbacks 数组退役为死数据）") {
    // fallback 废除批（作者令 2026-11-06 20:36「不再支持模型fallback」）：链深恒 1，
    // FallbackChain.refs = preferred 仅一（FallbackChain.scala 自注在案）——
    // fallbacks 数组不进链、不进校验（死数据，禁回流）。
    val json = parse("""{"llm":{"providers":{},"fallback":{"preferred":"a/m1","fallbacks":["b/m2"]}}}""").toOption.get
    val cfg = Config.normalizeConfigJson(json).as[NebflowServiceConfig].toOption.get
    assertEquals(FallbackChain.configured(cfg), List("a/m1"), "链 = preferred 单根；退役 fallbacks 不回流")
  }

  test("B2: preferred 引用不存在的 provider ⇒ 可读告警（不静默跳过）；退役 fallbacks 数组零告警（死数据）") {
    val json = parse(
      """{"llm":{"providers":{"a":{"baseUrl":"http://a/","apiKey":"k","protocol":"openai","models":[{"id":"m1","contextWindow":1000}]}},
        |"fallback":{"preferred":"ghost/m1","fallbacks":["ghost/m2"]}}}""".stripMargin
    ).toOption.get
    val cfg = Config.normalizeConfigJson(json).as[NebflowServiceConfig].toOption.get
    val warnings = FallbackChain.validate(cfg)
    assertEquals(
      warnings.size,
      1,
      s"退役 fallbacks 数组不得产生告警（死数据不参与校验），实际 = $warnings"
    )
    assert(warnings.head.contains("ghost"), s"告警须点名坏 ref，实际 = $warnings")
    assert(warnings.head.contains("not configured"), s"告警须可读，实际 = $warnings")
    assertEquals(FallbackChain.resolvable(cfg), Nil, "不可解 preferred 不得进可调用集")
  }

  test("B3: 引用 provider 未声明的 model ⇒ 也可读告警") {
    val json = parse(
      """{"llm":{"providers":{"a":{"baseUrl":"http://a/","apiKey":"k","protocol":"openai","models":[{"id":"m1","contextWindow":1000}]}},
        |"fallback":{"preferred":"a/nope","fallbacks":[]}}}""".stripMargin
    ).toOption.get
    val cfg = Config.normalizeConfigJson(json).as[NebflowServiceConfig].toOption.get
    assertEquals(FallbackChain.validate(cfg).size, 1)
    assert(FallbackChain.validate(cfg).head.contains("does not declare"))
  }

  test("B4: 健康链零告警；重复 ref 有独立告警") {
    val healthy = parse(
      """{"llm":{"providers":{"a":{"baseUrl":"http://a/","apiKey":"k","protocol":"openai","models":[{"id":"m1","contextWindow":1000}]}},
        |"fallback":{"preferred":"a/m1","fallbacks":[]}}}""".stripMargin
    ).toOption.get
    assertEquals(FallbackChain.validate(Config.normalizeConfigJson(healthy).as[NebflowServiceConfig].toOption.get), Nil)

    val dup = parse(
      """{"llm":{"providers":{"a":{"baseUrl":"http://a/","apiKey":"k","protocol":"openai","models":[{"id":"m1","contextWindow":1000}]}},
        |"fallback":{"preferred":"a/m1","fallbacks":["a/m1"]}}}""".stripMargin
    ).toOption.get
    // duplicate 告警腿随其世界退役（fallback 废除批）：链深恒 1（preferred 单根），
    // 「duplicate ref」结构性不可达——旧钉是链深>1 时代的产物，删除而非放宽。
    assert(FallbackChain.validate(Config.normalizeConfigJson(dup).as[NebflowServiceConfig].toOption.get) == Nil,
      "retired fallbacks array must stay dead data (no duplicate warnings from a depth-1 chain)")
  }

  test("B5: 无 fallback 小节 ⇒ configured 为空（零行为变化：走侧车/种子链）") {
    val cfg = Config.normalizeConfigJson(parse(sampleConfig).toOption.get).as[NebflowServiceConfig].toOption.get
    assertEquals(FallbackChain.configured(cfg), Nil)
    assertEquals(FallbackChain.validate(cfg), Nil)
  }
end ConfigSingleSourceSpec
