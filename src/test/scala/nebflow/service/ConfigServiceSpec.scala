package nebflow.service

import nebflow.neblink.core.*

import cats.effect.unsafe.implicits.global
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.Json
import munit.FunSuite
import nebflow.shared.PathUtil

import scala.compiletime.uninitialized

class ConfigServiceSpec extends FunSuite:

  private var originalRoot: os.Path = uninitialized
  private var tmpRoot: os.Path = uninitialized

  override def beforeAll(): Unit =
    // Redirect PathUtil.dataRoot to a temp dir BEFORE any ConfigService access
    // (configPath is captured lazily on first use). Sequential test execution
    // is guaranteed by build.sbt (Test / parallelExecution := false).
    originalRoot = PathUtil.dataRoot
    tmpRoot = os.pwd / "target" / "config-service-test" / s"data-${System.nanoTime()}"
    os.makeDir.all(tmpRoot)
    PathUtil.setDataRoot(tmpRoot)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    try os.remove.all(tmpRoot)
    catch case _: Exception => ()

  private def validProvider(name: String): Json =
    Json.obj(
      "baseUrl" -> s"https://$name.example.com/v1".asJson,
      "apiKey" -> "sk-test".asJson,
      "protocol" -> "openai".asJson,
      "models" -> Json.arr(
        Json.obj(
          "id" -> "m1".asJson,
          "maxTokens" -> 4096.asJson,
          "contextWindow" -> 8192.asJson
        )
      )
    )

  // ── validateConfig: null provider (deletion marker) ─────

  test("validateConfig accepts null provider (deletion) without field errors") {
    val cfg =
      s"""{"llm":{"providers":{"Zai":null,"glm":${validProvider(
          "glm"
        ).noSpaces}},"model":{"default":"glm/m1","fallbacks":[]}}}"""
    assertEquals(ConfigService.validateConfig(cfg), Nil)
  }

  test("validateConfig still validates non-null providers") {
    val cfg = """{"llm":{"providers":{"bad":{"baseUrl":"","protocol":"","models":[]}}}}"""
    val errors = ConfigService.validateConfig(cfg)
    assert(errors.exists(_.contains("Base URL is required")))
    assert(errors.exists(_.contains("Protocol is required")))
    assert(errors.exists(_.contains("At least one model is required")))
  }

  test("validateConfig ignores llm.model chain refs (#339: field retired)") {
    // #339：llm.model 链校验已删——悬空引用不再是错误（字段退役，boot 迁移剥离）
    val cfg =
      """{"llm":{"providers":{"Zai":null},"model":{"default":"Zai/m1","fallbacks":["Zai/m2"]}}}"""
    val errors = ConfigService.validateConfig(cfg)
    assert(!errors.exists(_.contains("points to unknown provider")))
  }

  // ── updateConfig: provider deletion + graceful cleanup ──

  test("updateConfig deletes provider and scrubs global chain + agent fallbacks") {
    // Seed config with provider Zai referenced as the global default
    val seed =
      s"""{"llm":{"providers":{"Zai":${validProvider("Zai").noSpaces},"glm":${validProvider(
          "glm"
        ).noSpaces}},"model":{"default":"Zai/m1","fallbacks":["glm/m1"]}}}"""
    os.write.over(PathUtil.dataRoot / nebflow.shared.Branding.configFileName, seed)

    // Seed agent.json files across all three layers, referencing Zai
    val agentDirs = List(
      tmpRoot / "agents" / "Coder",
      tmpRoot / "teams" / "nebflow-project" / "agents" / "Backend",
      tmpRoot / "flows" / "code-review" / "agents" / "scanner"
    )
    agentDirs.foreach { dir =>
      os.makeDir.all(dir)
      os.write.over(
        dir / "agent.json",
        s"""{"name":"${dir.last}","tools":[],"model":{"preferred":"Zai/m1","fallbacks":["Zai/m2","glm/m1"]}}"""
      )
    }
    // Agent referencing Zai only in fallbacks
    os.makeDir.all(tmpRoot / "agents" / "Explorer")
    os.write.over(
      tmpRoot / "agents" / "Explorer" / "agent.json",
      """{"name":"Explorer","tools":[],"model":{"preferred":"glm/m1","fallbacks":["Zai/m2"]}}"""
    )
    // Agent with no Zai references — must stay byte-identical
    val nebulaJson = """{"name":"Nebula","tools":[],"model":{"preferred":"glm/m1","fallbacks":[]}}"""
    os.makeDir.all(tmpRoot / "agents" / "Nebula")
    os.write.over(tmpRoot / "agents" / "Nebula" / "agent.json", nebulaJson)

    // Partial update: only the deletion marker (existing file still references Zai
    // in the chain — the backend fallback scrub must clean it during merge)
    val incoming = """{"llm":{"providers":{"Zai":null}}}"""
    val result = ConfigService.updateConfig(incoming).unsafeRunSync()
    assertEquals(result, Right(()))

    // ── Config file: provider removed; llm.model 死字段原样保留（#339：scrub
    // 已删，boot 迁移整体剥离）──
    val saved = parse(os.read(PathUtil.dataRoot / nebflow.shared.Branding.configFileName)).toOption.get
    val llmHc = saved.hcursor.downField("llm")
    assert(llmHc.downField("providers").downField("Zai").focus.isEmpty, "Zai provider must be deleted")
    assertEquals(llmHc.downField("model").downField("default").as[String], Right("Zai/m1"))
    assertEquals(llmHc.downField("model").downField("fallbacks").as[List[String]], Right(List("glm/m1")))

    // ── Standalone agent: preferred scrubbed, fallbacks filtered ──
    val coder = parse(os.read(tmpRoot / "agents" / "Coder" / "agent.json")).toOption.get
    assertEquals(coder.hcursor.downField("model").downField("preferred").focus, None)
    assertEquals(
      coder.hcursor.downField("model").downField("fallbacks").as[Option[List[String]]],
      Right(Some(List("glm/m1")))
    )

    // ── Team agent: same ──
    val backend = parse(
      os.read(tmpRoot / "teams" / "nebflow-project" / "agents" / "Backend" / "agent.json")
    ).toOption.get
    assertEquals(backend.hcursor.downField("model").downField("preferred").focus, None)
    assertEquals(
      backend.hcursor.downField("model").downField("fallbacks").as[Option[List[String]]],
      Right(Some(List("glm/m1")))
    )

    // ── Flow agent: same ──
    val scanner = parse(
      os.read(tmpRoot / "flows" / "code-review" / "agents" / "scanner" / "agent.json")
    ).toOption.get
    assertEquals(scanner.hcursor.downField("model").downField("preferred").focus, None)
    assertEquals(
      scanner.hcursor.downField("model").downField("fallbacks").as[Option[List[String]]],
      Right(Some(List("glm/m1")))
    )

    // ── Explorer: preferred kept, emptied fallbacks key dropped ──
    val explorer = parse(os.read(tmpRoot / "agents" / "Explorer" / "agent.json")).toOption.get
    assertEquals(explorer.hcursor.downField("model").downField("preferred").as[String], Right("glm/m1"))
    assertEquals(explorer.hcursor.downField("model").downField("fallbacks").as[Option[List[String]]], Right(None))

    // ── Nebula: untouched ──
    assertEquals(os.read(tmpRoot / "agents" / "Nebula" / "agent.json"), nebulaJson)
  }

  test("updateConfig ignores dangling llm.model refs (#339: field retired, validation removed)") {
    // #339：llm.model 链校验段已删——字段退役（decoder 容忍、boot 迁移剥离）。
    // 悬空引用不再拒绝：preset 引用的清理由 scrubPresetRefs 承担。
    val cfg =
      """{"llm":{"providers":{"Zai":null},"model":{"default":"Zai/m1","fallbacks":[]}}}"""
    val result = ConfigService.updateConfig(cfg).unsafeRunSync()
    assertEquals(result, Right(()))
  }

  test("pure delete leaves retired llm.model field untouched (#339: scrubGlobalChain removed)") {
    // #339：scrubGlobalChain 已删——llm.model 是死字段，删除 provider 不再改写
    // 它（boot 迁移会整体剥离）。decoder 容忍其存在。
    val seed =
      s"""{"llm":{"providers":{"Zai":${validProvider("Zai").noSpaces},"glm":${validProvider(
          "glm"
        ).noSpaces}},"model":{"default":"Zai/m1","fallbacks":[]}}}"""
    os.write.over(PathUtil.dataRoot / nebflow.shared.Branding.configFileName, seed)

    val incoming = """{"llm":{"providers":{"Zai":null}}}"""
    val result = ConfigService.updateConfig(incoming).unsafeRunSync()
    assertEquals(result, Right(()))

    val saved = parse(os.read(PathUtil.dataRoot / nebflow.shared.Branding.configFileName)).toOption.get
    val modelHc = saved.hcursor.downField("llm").downField("model")
    // 死字段原样保留（不在 updateConfig 的清理职责内）
    assertEquals(modelHc.downField("default").as[String], Right("Zai/m1"))
    // 整个配置仍可解码（llm.model → Option，Some 容忍）
    assert(nebflow.shared.Config.loadServiceConfig().llm.model.map(_.default).contains("Zai/m1"))
  }

  // ── B2: provider rename → reference rewrite ─────────────

  // Same content as validProvider("Zai") but with a masked apiKey — the
  // real-world shape a frontend sends back after reading the redacted config.
  private val zaiMasked =
    """{"baseUrl":"https://Zai.example.com/v1","apiKey":"***","protocol":"openai","models":[{"id":"m1","maxTokens":4096,"contextWindow":8192}]}"""

  test("updateConfig detects rename and rewrites global chain + agent refs (partial update)") {
    val seed =
      s"""{"llm":{"providers":{"Zai":${validProvider("Zai").noSpaces},"glm":${validProvider(
          "glm"
        ).noSpaces}},"model":{"default":"Zai/m1","fallbacks":["Zai/m2","glm/m1"]}}}"""
    os.write.over(PathUtil.dataRoot / nebflow.shared.Branding.configFileName, seed)

    val coderDir = tmpRoot / "agents" / "Coder"
    os.makeDir.all(coderDir)
    os.write.over(
      coderDir / "agent.json",
      """{"name":"Coder","tools":[],"model":{"preferred":"Zai/m1","fallbacks":["Zai/m2","glm/m1"]}}"""
    )

    // Partial update: providers only. #339：llm.model 的 rename 改写已删（死
    // 字段原样留存，boot 迁移整体剥离）；agent.json/preset 引用仍改写。
    val incoming = s"""{"llm":{"providers":{"Zai":null,"zai-new":$zaiMasked}}}"""
    val result = ConfigService.updateConfig(incoming).unsafeRunSync()
    assertEquals(result, Right(()))

    val saved = parse(os.read(PathUtil.dataRoot / nebflow.shared.Branding.configFileName)).toOption.get
    val llmHc = saved.hcursor.downField("llm")
    assert(llmHc.downField("providers").downField("Zai").focus.isEmpty, "old name must be gone")
    // Masked updates retain the synthetic credential, stored as a managed reference.
    val savedKey = llmHc.downField("providers").downField("zai-new").downField("apiKey").as[String]
      .getOrElse(fail("renamed provider apiKey missing"))
    assert(savedKey.matches("secret:config-[a-f0-9]{32}"), "expected managed credential reference")
    assert(
      nebflow.core.secrets.ConfigCredentialStore.resolve(
        PathUtil.dataRoot, savedKey, "config-service-spec", (_, _) => ()
      ) == "sk-test",
      "renamed provider retains its synthetic credential"
    )
    // llm.model 死字段不再改写（旧名留存，boot 迁移剥离）
    assertEquals(llmHc.downField("model").downField("default").as[String], Right("Zai/m1"))

    val coder = parse(os.read(coderDir / "agent.json")).toOption.get
    assertEquals(coder.hcursor.downField("model").downField("preferred").as[String], Right("zai-new/m1"))
    assertEquals(
      coder.hcursor.downField("model").downField("fallbacks").as[List[String]],
      Right(List("zai-new/m2", "glm/m1"))
    )
  }

  test("updateConfig rename with old-name refs in incoming chain passes validation") {
    val seed =
      s"""{"llm":{"providers":{"Zai":${validProvider("Zai").noSpaces},"glm":${validProvider(
          "glm"
        ).noSpaces}},"model":{"default":"Zai/m1","fallbacks":[]}}}"""
    os.write.over(PathUtil.dataRoot / nebflow.shared.Branding.configFileName, seed)

    // #339：llm.model 校验/改写均已删——incoming 带旧名 refs 直接接受（无
    // 校验可触发拒绝），死字段按 merge 原样保留。
    val incoming =
      s"""{"llm":{"providers":{"Zai":null,"zai-new":$zaiMasked},"model":{"default":"Zai/m1","fallbacks":[]}}}"""
    val result = ConfigService.updateConfig(incoming).unsafeRunSync()
    assertEquals(result, Right(()))

    val saved = parse(os.read(PathUtil.dataRoot / nebflow.shared.Branding.configFileName)).toOption.get
    assertEquals(
      saved.hcursor.downField("llm").downField("model").downField("default").as[String],
      Right("Zai/m1")
    )
  }

  test("updateConfig pure delete does not create model-presets.json when absent") {
    val seed =
      s"""{"llm":{"providers":{"Zai":${validProvider("Zai").noSpaces},"glm":${validProvider(
          "glm"
        ).noSpaces}},"model":{"default":"glm/m1","fallbacks":[]}}}"""
    os.write.over(PathUtil.dataRoot / nebflow.shared.Branding.configFileName, seed)
    val presetsFile = PathUtil.dataRoot / "model-presets.json"
    if os.exists(presetsFile) then os.remove(presetsFile)

    val result = ConfigService.updateConfig("""{"llm":{"providers":{"Zai":null}}}""").unsafeRunSync()
    assertEquals(result, Right(()))
    // Provider cleanup must NOT initialize the presets store (PresetStore.load
    // owns the create/re-init semantics)
    assert(!os.exists(presetsFile), "model-presets.json must not be created by provider cleanup")
  }

  test("updateConfig pure delete scrubs preset refs with fallback promotion") {
    val seed =
      s"""{"llm":{"providers":{"Zai":${validProvider("Zai").noSpaces},"glm":${validProvider(
          "glm"
        ).noSpaces}},"model":{"default":"glm/m1","fallbacks":[]}}}"""
    os.write.over(PathUtil.dataRoot / nebflow.shared.Branding.configFileName, seed)

    val presets =
      """{"defaultPreset":"general","presets":{
        "coding":{"name":"coding","description":"d","preferred":"Zai/m1","fallbacks":["glm/m1","Zai/m2"]},
        "writing":{"name":"writing","description":"w","preferred":"glm/m1","fallbacks":["Zai/m2"]},
        "clean":{"name":"clean","description":"c","preferred":"glm/m1","fallbacks":["glm/m2"]}
      }}""".replaceAll("\n\\s*", "")
    os.write.over(PathUtil.dataRoot / "model-presets.json", presets)

    val result = ConfigService.updateConfig("""{"llm":{"providers":{"Zai":null}}}""").unsafeRunSync()
    assertEquals(result, Right(()))

    val saved = parse(os.read(PathUtil.dataRoot / "model-presets.json")).toOption.get
    assertEquals(saved.hcursor.downField("defaultPreset").as[String], Right("general"))
    val presetsHc = saved.hcursor.downField("presets")
    // coding: scrubbed preferred promotes from fallbacks head; Zai/m2 filtered
    assertEquals(presetsHc.downField("coding").downField("preferred").as[String], Right("glm/m1"))
    assertEquals(presetsHc.downField("coding").downField("fallbacks").as[List[String]], Right(Nil))
    // writing: preferred kept, Zai fallback filtered
    assertEquals(presetsHc.downField("writing").downField("preferred").as[String], Right("glm/m1"))
    assertEquals(presetsHc.downField("writing").downField("fallbacks").as[List[String]], Right(Nil))
    // clean: untouched — other fields preserved
    assertEquals(presetsHc.downField("clean").downField("preferred").as[String], Right("glm/m1"))
    assertEquals(presetsHc.downField("clean").downField("fallbacks").as[List[String]], Right(List("glm/m2")))
    assertEquals(presetsHc.downField("clean").downField("description").as[String], Right("c"))
  }

  test("updateConfig rename rewrites preset refs and preserves other fields") {
    val seed =
      s"""{"llm":{"providers":{"Zai":${validProvider("Zai").noSpaces},"glm":${validProvider(
          "glm"
        ).noSpaces}},"model":{"default":"glm/m1","fallbacks":[]}}}"""
    os.write.over(PathUtil.dataRoot / nebflow.shared.Branding.configFileName, seed)

    val presets =
      """{"defaultPreset":"general","presets":{
        "coding":{"name":"coding","description":"d","preferred":"Zai/m1","fallbacks":["glm/m1","Zai/m2"]}
      }}""".replaceAll("\n\\s*", "")
    os.write.over(PathUtil.dataRoot / "model-presets.json", presets)

    val incoming = s"""{"llm":{"providers":{"Zai":null,"zai-new":$zaiMasked}}}"""
    val result = ConfigService.updateConfig(incoming).unsafeRunSync()
    assertEquals(result, Right(()))

    val saved = parse(os.read(PathUtil.dataRoot / "model-presets.json")).toOption.get
    val coding = saved.hcursor.downField("presets").downField("coding")
    assertEquals(coding.downField("preferred").as[String], Right("zai-new/m1"))
    assertEquals(coding.downField("fallbacks").as[List[String]], Right(List("glm/m1", "zai-new/m2")))
    // untouched sibling fields survive the rewrite
    assertEquals(coding.downField("name").as[String], Right("coding"))
    assertEquals(coding.downField("description").as[String], Right("d"))
  }

  // ── runtime-authoritative overrides（冻结修复 2026-08-27）：陈旧快照不得回滚 ──

  /**
   * Seed nebflow.json with a frozen schedule + provider, mirroring the on-disk
   * state after the user has configured a freeze schedule.
   */
  private def seedConfigWithSchedule(enabled: Boolean, segs: String): Unit =
    os.write.over(
      PathUtil.dataRoot / nebflow.shared.Branding.configFileName,
      s"""{"workSchedule":{"enabled":$enabled,"segments":$segs},"llm":{"providers":{"glm":${validProvider(
          "glm"
        ).noSpaces}}}}""",
      createFolders = true
    )

  private def readWorkSchedule(): Option[Json] =
    parse(os.read(PathUtil.dataRoot / nebflow.shared.Branding.configFileName)).toOption.get.hcursor.downField("workSchedule").as[Json].toOption

  test(
    "updateConfig: stale incoming workSchedule is overridden by runtime-authoritative value (roll-back regression)"
  ) {
    seedConfigWithSchedule(enabled = true, segs = """[{"start":"09:00","end":"12:00"}]""")
    // The authoritative in-memory ref says: disabled BUT segments preserved (08-25 ruling).
    val authoritative = parse("""{"enabled":false,"segments":[{"start":"09:00","end":"12:00"}]}""").toOption.get
    // A stale full-config snapshot captured BEFORE the user toggled off — it still
    // carries enabled=true. Any provider save flushes this back; without the
    // override the disk rolls back and freeze resurrects after restart.
    val staleIncoming = s"""{"workSchedule":{"enabled":true,"segments":[]},"llm":{"providers":{"newp":${validProvider(
        "newp"
      ).noSpaces}}}}"""
    val result = ConfigService.updateConfig(staleIncoming, Map("workSchedule" -> authoritative)).unsafeRunSync()
    assertEquals(result, Right(()))
    val ws = readWorkSchedule().get
    // Disk keeps the runtime truth: disabled + original segment intact
    // (value-level, spacing-independent comparison).
    assert(!ws.noSpaces.replace(" ", "").contains("\"enabled\":true"), s"stale snapshot rolled the toggle back: $ws")
    assertEquals(
      ws.noSpaces.replace(" ", ""),
      """{"enabled":false,"segments":[{"start":"09:00","end":"12:00"}]}"""
    )
    // The non-runtime part of the update still landed (deep merge unchanged).
    val providers =
      parse(os.read(PathUtil.dataRoot / nebflow.shared.Branding.configFileName)).toOption.get.hcursor.downField("llm").downField("providers")
    assertEquals(providers.downField("newp").as[Json].isRight, true)
  }

  test("updateConfig: no overrides keeps legacy deep-merge semantics (incoming wins)") {
    seedConfigWithSchedule(enabled = true, segs = """[{"start":"23:00","end":"08:00"}]""")
    val incoming = """{"workSchedule":{"enabled":false,"segments":[]}}"""
    val result = ConfigService.updateConfig(incoming).unsafeRunSync()
    assertEquals(result, Right(()))
    val ws = readWorkSchedule().get.noSpaces
    assert(ws.contains("\"enabled\":false"), s"incoming should win without overrides: $ws")
  }

  test("updateConfig: override keys do not leak into providers scope or delete unrelated top-level keys") {
    seedConfigWithSchedule(enabled = false, segs = "[]")
    val thOverride = parse("""{"enabled":true,"budgetTokens":1234}""").toOption.get
    val real = s"""{"llm":{"providers":{"z2":${validProvider("z2").noSpaces}}}}"""
    val result = ConfigService.updateConfig(real, Map("thinkingConfig" -> thOverride)).unsafeRunSync()
    assertEquals(result, Right(()))
    val cfg = parse(os.read(PathUtil.dataRoot / nebflow.shared.Branding.configFileName)).toOption.get
    val tfFocus = cfg.hcursor.downField("thinkingConfig").focus
    assertEquals(cfg.hcursor.downField("thinkingConfig").downField("budgetTokens").as[Int], Right(1234))
    assertEquals(cfg.hcursor.downField("workSchedule").as[Json].isRight, true)
    assertEquals(cfg.hcursor.downField("llm").downField("providers").downField("z2").as[Json].isRight, true)
  }

  // ── providerTypes 投影（provider-add-tabs 批 2026-11-06）─────────────────
  // 类型枚举唯一出生地在后端：投影的 domains 必须与 ProviderDomains.All 一一
  // 对应（前端只消费，不拼写——静态闸 check-provider-panel.mjs P5 同钉）。

  test("providerTypes: domains mirror ProviderDomains.All one-to-one, in order") {
    val json    = ConfigService.providerTypes.unsafeRunSync()
    val domains = json.hcursor.downField("domains")
    val ids = domains.values
      .map(_.toList.map(d => d.hcursor.downField("id").as[String].getOrElse("<unreadable>")))
      .getOrElse(fail("domains[] missing"))
    assertEquals(ids, nebflow.shared.ProviderDomains.All)
    // 每个 domain 都声明 enabledSwitch 布尔（imagegen = true，llm = false）。
    val switches = domains.values.map(_.toList.map { d =>
      val id = d.hcursor.downField("id").as[String].getOrElse("<unreadable>")
      id -> d.hcursor
        .downField("enabledSwitch")
        .as[Boolean]
        .getOrElse(!nebflow.shared.ProviderDomains.hasEnabledSwitch(id))
    })
    assertEquals(
      switches,
      Some(nebflow.shared.ProviderDomains.All.map(d => d -> nebflow.shared.ProviderDomains.hasEnabledSwitch(d)))
    )
  }

  test("providerTypes: adapters come from the engine enums, zero literal duplication") {
    val json     = ConfigService.providerTypes.unsafeRunSync()
    val adapters = json.hcursor.downField("adapters")
    def strs(key: String): List[String] =
      adapters.downField(key).values.map(_.toList.flatMap(_.as[String].toOption)).getOrElse(fail(s"adapters.$key missing"))
    val llm      = strs("llm")
    val imagegen = strs("imagegen")
    // LLM 协议 = LlmProtocol 枚举（anthropic/openai）。
    assertEquals(llm.sortBy(identity), List("anthropic", "openai").sortBy(identity))
    // 生图适配器 = ImageGenAdapters.All 的 type 投影（单源比对）。
    assertEquals(imagegen.sortBy(identity), nebflow.llm.imagegen.ImageGenAdapters.All.map(_.`type`).sortBy(identity))
    // 🔴 jev-retire 批（2026-11-16）：jev 域连同 adapters.jev 退役——投影**不得**
    // 再出现 jev 键（residual-reference 回归锚）。
    val rendered = json.noSpaces
    assert(!rendered.contains("jev"), s"the retired jev domain must not leak into providerTypes: $rendered")
    // 载荷零配置值、零密钥——只有枚举与布尔。
    assert(!rendered.contains("apiKey"), s"payload must not carry apiKey: $rendered")
    assert(!rendered.contains("baseUrl"), s"payload must not carry config values: $rendered")
  }

  // ── 🔴 配置缺 jev 启动红绿（jev-retire 批 2026-11-16 ④）──────────────────
  test("boot without jev config: the engine read path stays clean — zero jev residue") {
    val cfg = s"""{"llm":{"providers":{"z3":${validProvider("z3").noSpaces}}}}"""
    assertEquals(ConfigService.updateConfig(cfg, Map.empty).unsafeRunSync(), Right(()))
    val written = parse(os.read(PathUtil.dataRoot / nebflow.shared.Branding.configFileName)).toOption.get
    assert(written.hcursor.downField("jev").focus.isEmpty, "a jev-free config must stay jev-free on write-back")
    assert(!ConfigService.providerTypes.unsafeRunSync().noSpaces.contains("jev"))
  }

  test("legacy config with a residual jev block still boots (zero-migration decode compatibility)") {
    val legacy = tmpRoot / "legacy-jev-config.json"
    os.write.over(legacy,
      s"""{"llm":{"providers":{"z4":${validProvider("z4").noSpaces}}},
         |"jev":{"enabled":true,"provider":"typesafe-jev","endpoint":"https://api.typesafe.ai/v1/systemone","keyRef":"typesafe-api-key"}}""".stripMargin)
    val loaded = nebflow.shared.Config.loadServiceConfig(Some(legacy.toString))
    assert(loaded.llm.providers.contains("z4"), "the llm providers decode normally alongside the dead jev block")
  }

end ConfigServiceSpec
