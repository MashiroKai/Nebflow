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

end ConfigServiceSpec
