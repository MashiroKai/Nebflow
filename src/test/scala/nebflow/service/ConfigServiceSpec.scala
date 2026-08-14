package nebflow.service

import cats.effect.unsafe.implicits.global
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.Json
import munit.FunSuite
import nebflow.core.PathUtil

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

  test("validateConfig reports dangling chain ref to deleted (null) provider") {
    val cfg =
      """{"llm":{"providers":{"Zai":null},"model":{"default":"Zai/m1","fallbacks":["Zai/m2"]}}}"""
    val errors = ConfigService.validateConfig(cfg)
    assert(errors.exists(_.contains("Default model 'Zai/m1' points to unknown provider 'Zai'")))
    assert(errors.exists(_.contains("Fallback model 'Zai/m2' points to unknown provider 'Zai'")))
  }

  // ── updateConfig: provider deletion + graceful cleanup ──

  test("updateConfig deletes provider and scrubs global chain + agent fallbacks") {
    // Seed config with provider Zai referenced as the global default
    val seed =
      s"""{"llm":{"providers":{"Zai":${validProvider("Zai").noSpaces},"glm":${validProvider(
          "glm"
        ).noSpaces}},"model":{"default":"Zai/m1","fallbacks":["glm/m1"]}}}"""
    os.write.over(PathUtil.dataRoot / "nebflow.json", seed)

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

    // ── Config file: provider removed, global chain scrubbed (default promoted) ──
    val saved = parse(os.read(PathUtil.dataRoot / "nebflow.json")).toOption.get
    val llmHc = saved.hcursor.downField("llm")
    assert(llmHc.downField("providers").downField("Zai").focus.isEmpty, "Zai provider must be deleted")
    assertEquals(llmHc.downField("model").downField("default").as[String], Right("glm/m1"))
    assertEquals(llmHc.downField("model").downField("fallbacks").as[Option[List[String]]], Right(None))

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

  test("updateConfig with dangling chain ref in incoming is rejected (guides cleanup)") {
    val cfg =
      """{"llm":{"providers":{"Zai":null},"model":{"default":"Zai/m1","fallbacks":[]}}}"""
    val result = ConfigService.updateConfig(cfg).unsafeRunSync()
    assert(result.isLeft)
    assert(result.left.exists(_.contains("points to unknown provider 'Zai'")))
  }

  test("scrubbed default with no remaining fallback keeps required field as empty string") {
    // default references the deleted provider and there is no fallback to promote
    val seed =
      s"""{"llm":{"providers":{"Zai":${validProvider("Zai").noSpaces},"glm":${validProvider(
          "glm"
        ).noSpaces}},"model":{"default":"Zai/m1","fallbacks":[]}}}"""
    os.write.over(PathUtil.dataRoot / "nebflow.json", seed)

    val incoming = """{"llm":{"providers":{"Zai":null}}}"""
    val result = ConfigService.updateConfig(incoming).unsafeRunSync()
    assertEquals(result, Right(()))

    // model.default must remain present (required field) but empty — the
    // registry skips it and falls back to the first available model
    val saved = parse(os.read(PathUtil.dataRoot / "nebflow.json")).toOption.get
    val modelHc = saved.hcursor.downField("llm").downField("model")
    assertEquals(modelHc.downField("default").as[String], Right(""))
    assertEquals(modelHc.downField("fallbacks").as[Option[List[String]]], Right(None))
    // The whole config must still decode (default field not dropped)
    assert(nebflow.llm.Config.loadServiceConfig().llm.model.default == "")
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
    os.write.over(PathUtil.dataRoot / "nebflow.json", seed)

    val coderDir = tmpRoot / "agents" / "Coder"
    os.makeDir.all(coderDir)
    os.write.over(
      coderDir / "agent.json",
      """{"name":"Coder","tools":[],"model":{"preferred":"Zai/m1","fallbacks":["Zai/m2","glm/m1"]}}"""
    )

    // Partial update: providers only, llm.model omitted. The old chain refs
    // survive the merge and must be rewritten old→new, not scrubbed.
    val incoming = s"""{"llm":{"providers":{"Zai":null,"zai-new":$zaiMasked}}}"""
    val result = ConfigService.updateConfig(incoming).unsafeRunSync()
    assertEquals(result, Right(()))

    val saved = parse(os.read(PathUtil.dataRoot / "nebflow.json")).toOption.get
    val llmHc = saved.hcursor.downField("llm")
    assert(llmHc.downField("providers").downField("Zai").focus.isEmpty, "old name must be gone")
    // masked "***" must not overwrite the stored secret during merge
    assertEquals(
      llmHc.downField("providers").downField("zai-new").downField("apiKey").as[String],
      Right("sk-test")
    )
    assertEquals(llmHc.downField("model").downField("default").as[String], Right("zai-new/m1"))
    assertEquals(
      llmHc.downField("model").downField("fallbacks").as[List[String]],
      Right(List("zai-new/m2", "glm/m1"))
    )

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
    os.write.over(PathUtil.dataRoot / "nebflow.json", seed)

    // incoming still carries old-name refs — rewrite happens BEFORE validate,
    // so a rename must not be rejected as a dangling reference
    val incoming = s"""{"llm":{"providers":{"Zai":null,"zai-new":$zaiMasked},"model":{"default":"Zai/m1","fallbacks":[]}}}"""
    val result = ConfigService.updateConfig(incoming).unsafeRunSync()
    assertEquals(result, Right(()))

    val saved = parse(os.read(PathUtil.dataRoot / "nebflow.json")).toOption.get
    assertEquals(
      saved.hcursor.downField("llm").downField("model").downField("default").as[String],
      Right("zai-new/m1")
    )
  }

  test("updateConfig pure delete does not create model-presets.json when absent") {
    val seed =
      s"""{"llm":{"providers":{"Zai":${validProvider("Zai").noSpaces},"glm":${validProvider(
          "glm"
        ).noSpaces}},"model":{"default":"glm/m1","fallbacks":[]}}}"""
    os.write.over(PathUtil.dataRoot / "nebflow.json", seed)
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
    os.write.over(PathUtil.dataRoot / "nebflow.json", seed)

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
    os.write.over(PathUtil.dataRoot / "nebflow.json", seed)

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

end ConfigServiceSpec
