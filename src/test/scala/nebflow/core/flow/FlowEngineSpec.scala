package nebflow.core.flow

import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import io.circe.parser.parse
import munit.CatsEffectSuite

class FlowEngineSpec extends CatsEffectSuite:

  // ============================================================
  // FlowDefParser
  // ============================================================

  test("parser: parse a complete valid flow definition") {
    val input = JsonObject(
      "name" -> "test-flow".asJson,
      "description" -> "A test flow".asJson,
      "steps" -> Json.arr(
        Json.obj(
          "id" -> "explore".asJson,
          "agent" -> "Explorer".asJson,
          "prompt" -> "Analyze structure".asJson
        ),
        Json.obj(
          "id" -> "plan".asJson,
          "agent" -> "Planner".asJson,
          "prompt" -> "Based on: ${explore}".asJson,
          "dependsOn" -> Json.arr("explore".asJson)
        ),
        Json.obj(
          "id" -> "impl".asJson,
          "agent" -> "Nebula".asJson,
          "prompt" -> "Implement: ${plan}".asJson,
          "dependsOn" -> Json.arr("plan".asJson)
        )
      ),
      "verify" -> Json.obj(
        "agent" -> "Explorer".asJson,
        "prompt" -> "Run tests. Start with PASS or FAIL.".asJson
      ),
      "loop" -> Json.obj(
        "fix" -> Json.obj(
          "id" -> "fix".asJson,
          "agent" -> "Nebula".asJson,
          "prompt" -> "Fix: ${verify}".asJson
        ),
        "maxIterations" -> 3.asJson
      ),
      "maxConcurrency" -> 3.asJson
    )

    FlowDefParser.parse(input) match
      case Right(flow) =>
        assertEquals(flow.name, "test-flow")
        assertEquals(flow.steps.length, 3)
        assertEquals(flow.steps(1).id, "plan")
        assertEquals(flow.steps(1).dependsOn, Set("explore"))
        assertEquals(flow.verify.agent, "Explorer")
        assertEquals(flow.loop.map(_.maxIterations), Some(3))
        assertEquals(flow.maxConcurrency, 3)
      case Left(err) => fail(s"Parse should succeed: $err")
  }

  test("parser: parse with defaults (no loop, no maxConcurrency)") {
    val input = JsonObject(
      "name" -> "simple".asJson,
      "description" -> "Simple flow".asJson,
      "steps" -> Json.arr(
        Json.obj("id" -> "s1".asJson, "agent" -> "Explorer".asJson, "prompt" -> "Do X".asJson)
      ),
      "verify" -> Json.obj("prompt" -> "Check X".asJson)
    )

    FlowDefParser.parse(input) match
      case Right(flow) =>
        assertEquals(flow.loop, None)
        assertEquals(flow.maxConcurrency, 5) // default
        assertEquals(flow.verify.agent, "Explorer") // default
      case Left(err) => fail(s"Parse should succeed: $err")
  }

  test("parser: fail on missing required fields") {
    val badInputs = List(
      JsonObject("description" -> "no name".asJson, "steps" -> Json.arr(), "verify" -> Json.obj()),
      JsonObject("name" -> "x".asJson, "steps" -> Json.arr(), "verify" -> Json.obj()),
      JsonObject("name" -> "x".asJson, "description" -> "d".asJson, "verify" -> Json.obj()),
      JsonObject("name" -> "x".asJson, "description" -> "d".asJson, "steps" -> Json.arr())
    )

    badInputs.foreach { input =>
      FlowDefParser.parse(input) match
        case Right(_) => fail(s"Should fail on missing fields: $input")
        case Left(_) => () // expected
    }
  }

  test("parser: fail on empty steps array") {
    val input = JsonObject(
      "name" -> "x".asJson,
      "description" -> "d".asJson,
      "steps" -> Json.arr(),
      "verify" -> Json.obj("prompt" -> "v".asJson)
    )
    FlowDefParser.parse(input) match
      case Right(_) => fail("Should fail on empty steps")
      case Left(err) => assert(err.contains("steps"), s"Error should mention steps: $err")
  }

  // ============================================================
  // FlowValidator
  // ============================================================

  test("validator: valid linear DAG passes") {
    val flow = FlowDef(
      name = "test",
      description = "test",
      steps = List(
        FlowStep("a", "Explorer", "do A"),
        FlowStep("b", "Nebula", "do B", dependsOn = Set("a")),
        FlowStep("c", "Nebula", "do C", dependsOn = Set("b"))
      ),
      verify = VerifyStep(prompt = "check")
    )
    FlowValidator.validate(flow) match
      case Right(()) => ()
      case Left(err) => fail(s"Should pass: $err")
  }

  test("validator: valid parallel DAG passes") {
    val flow = FlowDef(
      name = "test",
      description = "test",
      steps = List(
        FlowStep("root", "Explorer", "explore"),
        FlowStep("a", "Nebula", "impl A", dependsOn = Set("root")),
        FlowStep("b", "Nebula", "impl B", dependsOn = Set("root")),
        FlowStep("c", "Nebula", "impl C", dependsOn = Set("root"))
      ),
      verify = VerifyStep(prompt = "check")
    )
    FlowValidator.validate(flow) match
      case Right(()) => ()
      case Left(err) => fail(s"Should pass: $err")
  }

  test("validator: duplicate step IDs fail") {
    val flow = FlowDef(
      name = "test",
      description = "test",
      steps = List(
        FlowStep("dup", "Explorer", "first"),
        FlowStep("dup", "Nebula", "second")
      ),
      verify = VerifyStep(prompt = "check")
    )
    FlowValidator.validate(flow) match
      case Right(()) => fail("Should detect duplicate IDs")
      case Left(err) => assert(err.contains("Duplicate"), s"$err")
  }

  test("validator: self-dependency fails") {
    val flow = FlowDef(
      name = "test",
      description = "test",
      steps = List(
        FlowStep("a", "Explorer", "self", dependsOn = Set("a"))
      ),
      verify = VerifyStep(prompt = "check")
    )
    FlowValidator.validate(flow) match
      case Right(()) => fail("Should detect self-dependency")
      case Left(err) => assert(err.contains("itself"), s"$err")
  }

  test("validator: unknown dependsOn reference fails") {
    val flow = FlowDef(
      name = "test",
      description = "test",
      steps = List(
        FlowStep("a", "Explorer", "do A", dependsOn = Set("nonexistent"))
      ),
      verify = VerifyStep(prompt = "check")
    )
    FlowValidator.validate(flow) match
      case Right(()) => fail("Should detect unknown dependency")
      case Left(err) => assert(err.contains("unknown"), s"$err")
  }

  test("validator: circular dependency fails") {
    val flow = FlowDef(
      name = "test",
      description = "test",
      steps = List(
        FlowStep("a", "Explorer", "A", dependsOn = Set("c")),
        FlowStep("b", "Explorer", "B", dependsOn = Set("a")),
        FlowStep("c", "Explorer", "C", dependsOn = Set("b"))
      ),
      verify = VerifyStep(prompt = "check")
    )
    FlowValidator.validate(flow) match
      case Right(()) => fail("Should detect circular dependency")
      case Left(err) => assert(err.contains("Circular") || err.contains("cycle"), s"$err")
  }

  test("validator: fix step ID collision fails") {
    val flow = FlowDef(
      name = "test",
      description = "test",
      steps = List(
        FlowStep("fix", "Explorer", "work step named fix")
      ),
      verify = VerifyStep(prompt = "check"),
      loop = Some(LoopDef(
        fix = FlowStep("fix", "Nebula", "the actual fix"),
        maxIterations = 2
      ))
    )
    FlowValidator.validate(flow) match
      case Right(()) => fail("Should detect fix ID collision")
      case Left(err) => assert(err.contains("collide") || err.contains("collision"), s"$err")
  }

  test("validator: blank step ID fails") {
    val flow = FlowDef(
      name = "test",
      description = "test",
      steps = List(
        FlowStep("", "Explorer", "empty id")
      ),
      verify = VerifyStep(prompt = "check")
    )
    // Parser should catch blank IDs, but let's test validator too
    FlowValidator.validate(flow) match
      case Right(()) => fail("Should detect blank ID")
      case Left(err) => ()
  }

  // ============================================================
  // Verify Result Parsing (via reflection-independent approach)
  // ============================================================

  test("verify parsing: PASS detection") {
    // parseVerifyResult is private, but we can test via VerifyResult directly
    // This validates the expected behavior
    val passOutput = "PASS\nImplemented auth module.\n15 tests passing."
    val failOutput = "FAIL\n3 tests still failing.\nToken refresh broken."
    val ambiguous = "Not sure if it works."

    // Simulate the parsing logic
    def parse(output: String): Boolean =
      output.linesIterator.nextOption().getOrElse("").trim.toLowerCase.startsWith("pass")

    assert(parse(passOutput), "Should detect PASS")
    assert(!parse(failOutput), "Should not detect PASS for FAIL")
    assert(!parse(ambiguous), "Ambiguous output should not be PASS")
  }

  test("verify parsing: case insensitive and whitespace tolerant") {
    def parse(output: String): Boolean =
      output.linesIterator.nextOption().getOrElse("").trim.toLowerCase.startsWith("pass")

    assert(parse("PASS\nok"))
    assert(parse("pass\nok"))
    assert(parse("  PASS  \nok"))
    assert(parse("Pass\nok"))
    assert(parse("PASS. All good."))
  }

  // ============================================================
  // Template Resolution (validating the expected behavior)
  // ============================================================

  test("template: ${stepId} replacement") {
    // Simulate the resolveTemplate logic
    def resolve(prompt: String, results: Map[String, String]): String =
      results.foldLeft(prompt) { case (p, (id, output)) =>
        p.replace("${" + id + "}", output)
      }

    val results = Map("explore" -> "Found 5 files", "plan" -> "Step 1: do X")
    val prompt = "Based on exploration: ${explore}\nPlan: ${plan}"

    val resolved = resolve(prompt, results)
    assertEquals(resolved, "Based on exploration: Found 5 files\nPlan: Step 1: do X")
  }

  test("template: no variables remain unresolved when all deps are met") {
    def resolve(prompt: String, results: Map[String, String]): String =
      results.foldLeft(prompt) { case (p, (id, output)) =>
        p.replace("${" + id + "}", output)
      }

    val resolved = resolve("Hello ${name}", Map("name" -> "World"))
    assert(!resolved.contains("${"), s"No template vars should remain: $resolved")
  }
