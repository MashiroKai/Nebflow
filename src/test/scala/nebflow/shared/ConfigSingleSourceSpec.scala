package nebflow.shared

import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite

/**
 * config-single-source batch (author rulings ②③④ 2026-11-05) — the parts that
 * live in `nebflow.shared`: the config-file rename/migration single entry point
 * and the model-level→provider-level unavailable-window convergence.
 *
 * Assertion discipline: every assertion is mechanical and names the ruling it
 * pins. Paths are parameterized (`configJsonPath(dir)`) so the spec never reads
 * or writes the real HOME.
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

  // ── A. 文件改名 + 一次性迁移（裁定②）────────────────────────────────────

  test("A1: 只有旧 nebflow.json ⇒ 自动生成 stand.json，值一一相等（逐字节）") {
    val dir = tmpDir("migrate")
    os.write(dir / "nebflow.json", sampleConfig)
    val path = PathUtil.configJsonPath(dir)
    assertEquals(path.last, Branding.configFileName, "单一入口必须返回品牌文件名")
    assertEquals(path.last, "stand.json")
    assert(os.exists(path), "迁移后 stand.json 必须存在")
    assertEquals(os.read(path), sampleConfig, "迁移后配置值必须与旧文件逐字节相等（禁止静默丢失）")
    assert(os.exists(dir / "nebflow.json.bak"), "旧文件必须留 .bak 备份，不得删除")
    assert(!os.exists(dir / "nebflow.json"), "旧文件必须改名（否则两个真相并存）")
  }

  test("A2: 迁移幂等 + 已存在 stand.json 时不被旧文件覆盖") {
    val dir = tmpDir("idempotent")
    os.write(dir / "nebflow.json", sampleConfig)
    val first = PathUtil.configJsonPath(dir)
    os.write.over(first, """{"llm":{"providers":{}}}""")
    val second = PathUtil.configJsonPath(dir)
    assertEquals(second, first)
    assertEquals(os.read(second), """{"llm":{"providers":{}}}""", "旧 .bak 不得回灌覆盖新文件")
  }

  test("A3: 两文件都不存在 ⇒ 返回新文件名（首次创建走新名）") {
    val dir = tmpDir("fresh")
    val path = PathUtil.configJsonPath(dir)
    assertEquals(path.last, "stand.json")
    assert(!os.exists(path), "读面不得凭空造文件")
  }

  test("A4: 读写两面是同一个路径（不许两个真相）") {
    val dir = tmpDir("samepath2")
    os.write(dir / "nebflow.json", sampleConfig)
    assertEquals(
      PathUtil.configJsonWritePath(dir),
      PathUtil.configJsonReadPath(dir),
      "写面必须落到读面（迁移后）同一文件"
    )
    assertEquals(PathUtil.configJsonWritePath(dir).last, "stand.json")
  }

  test("A5: 读旧名配置文件的调用点零改动仍读得到（向后兼容）") {
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

  test("B1: llm.fallback 小节可解码（preferred + fallbacks，与 agent 链同形状）") {
    val json = parse("""{"llm":{"providers":{},"fallback":{"preferred":"a/m1","fallbacks":["b/m2"]}}}""").toOption.get
    val cfg = Config.normalizeConfigJson(json).as[NebflowServiceConfig].toOption.get
    assertEquals(FallbackChain.configured(cfg), List("a/m1", "b/m2"))
  }

  test("B2: 引用不存在的 provider ⇒ 可读告警（不静默跳过）") {
    val json = parse(
      """{"llm":{"providers":{"a":{"baseUrl":"http://a/","apiKey":"k","protocol":"openai","models":[{"id":"m1","contextWindow":1000}]}},
        |"fallback":{"preferred":"a/m1","fallbacks":["ghost/m1"]}}}""".stripMargin
    ).toOption.get
    val cfg = Config.normalizeConfigJson(json).as[NebflowServiceConfig].toOption.get
    val warnings = FallbackChain.validate(cfg)
    assertEquals(warnings.size, 1)
    assert(warnings.head.contains("ghost"), s"告警须点名坏 ref，实际 = $warnings")
    assert(warnings.head.contains("not configured"), s"告警须可读，实际 = $warnings")
    assertEquals(FallbackChain.resolvable(cfg), List(("a", "m1")), "不可解 ref 不得进可调用集")
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
    val dupCfg = Config.normalizeConfigJson(dup).as[NebflowServiceConfig].toOption.get
    assert(FallbackChain.validate(dupCfg).exists(_.contains("duplicate")))
  }

  test("B5: 无 fallback 小节 ⇒ configured 为空（零行为变化：走侧车/种子链）") {
    val cfg = Config.normalizeConfigJson(parse(sampleConfig).toOption.get).as[NebflowServiceConfig].toOption.get
    assertEquals(FallbackChain.configured(cfg), Nil)
    assertEquals(FallbackChain.validate(cfg), Nil)
  }
end ConfigSingleSourceSpec
