package nebflow.core.flow

import io.circe.{Json, JsonObject}
import io.circe.syntax.*
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
        "prompt" -> "Run tests. Call FlowVerify with result.".asJson
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

  test("parser: loop fix missing agent returns error instead of silent fallback") {
    val input = JsonObject(
      "name" -> "x".asJson,
      "description" -> "d".asJson,
      "steps" -> Json.arr(
        Json.obj("id" -> "s1".asJson, "agent" -> "Explorer".asJson, "prompt" -> "Do X".asJson)
      ),
      "verify" -> Json.obj("prompt" -> "v".asJson),
      "loop" -> Json.obj(
        "fix" -> Json.obj(
          "id" -> "fix".asJson,
          // missing "agent" — should error, not silently default to "Nebula"
          "prompt" -> "Fix it".asJson
        )
      )
    )
    FlowDefParser.parse(input) match
      case Right(_) => fail("Should fail on missing agent in loop.fix")
      case Left(err) => assert(err.contains("agent"), s"Error should mention agent: $err")
  }

  test("parser: loop fix missing prompt returns error") {
    val input = JsonObject(
      "name" -> "x".asJson,
      "description" -> "d".asJson,
      "steps" -> Json.arr(
        Json.obj("id" -> "s1".asJson, "agent" -> "Explorer".asJson, "prompt" -> "Do X".asJson)
      ),
      "verify" -> Json.obj("prompt" -> "v".asJson),
      "loop" -> Json.obj(
        "fix" -> Json.obj(
          "id" -> "fix".asJson,
          "agent" -> "Nebula".asJson
          // missing "prompt" — should error
        )
      )
    )
    FlowDefParser.parse(input) match
      case Right(_) => fail("Should fail on missing prompt in loop.fix")
      case Left(err) => assert(err.contains("prompt"), s"Error should mention prompt: $err")
  }

  test("parser: loop fix with non-object fix returns error") {
    val input = JsonObject(
      "name" -> "x".asJson,
      "description" -> "d".asJson,
      "steps" -> Json.arr(
        Json.obj("id" -> "s1".asJson, "agent" -> "Explorer".asJson, "prompt" -> "Do X".asJson)
      ),
      "verify" -> Json.obj("prompt" -> "v".asJson),
      "loop" -> Json.obj(
        "fix" -> "not-an-object".asJson
      )
    )
    FlowDefParser.parse(input) match
      case Right(_) => fail("Should fail when loop.fix is not an object")
      case Left(err) => assert(err.contains("fix"), s"Error should mention fix: $err")
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
      loop = Some(
        LoopDef(
          fix = FlowStep("fix", "Nebula", "the actual fix"),
          maxIterations = 2
        )
      )
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
    FlowValidator.validate(flow) match
      case Right(()) => fail("Should detect blank ID")
      case Left(err) => ()
  }

  // ============================================================
  // resolveTemplate — real code path
  // ============================================================

  test("resolveTemplate: ${stepId} replacement via real function") {
    val results = Map("explore" -> "Found 5 files", "plan" -> "Step 1: do X")
    val prompt = "Based on exploration: ${explore}\nPlan: ${plan}"

    val resolved = FlowActor.resolveTemplate(prompt, results)
    assertEquals(resolved, "Based on exploration: Found 5 files\nPlan: Step 1: do X")
  }

  test("resolveTemplate: no variables remain unresolved when all deps are met") {
    val resolved = FlowActor.resolveTemplate("Hello ${name}", Map("name" -> "World"))
    assert(!resolved.contains("${"), s"No template vars should remain: $resolved")
  }

  test("resolveTemplate: ${verify} variable resolves correctly") {
    // Regression test for Bug 1: handleVerifyFail used stale state.verifyResult
    // instead of the current verify summary. The template mechanism itself works —
    // the bug was in what was passed to it. Verify that resolveTemplate correctly
    // interpolates the "verify" key.
    val results = Map("explore" -> "Found 3 issues", "verify" -> "FAIL: tests broken at line 42")
    val prompt = "Previous verification found:\n${verify}\nFix these issues."

    val resolved = FlowActor.resolveTemplate(prompt, results)
    assert(resolved.contains("FAIL: tests broken at line 42"), s"Verify output should be interpolated: $resolved")
    assert(!resolved.contains("${verify}"), s"${'$'}{verify} should be resolved: $resolved")
  }

  test("resolveTemplate: large output is truncated") {
    // Regression test for inter-step truncation
    val bigOutput = "X" * 20000
    val resolved = FlowActor.resolveTemplate("Result: ${step1}", Map("step1" -> bigOutput))
    assert(resolved.length < 20000, s"Output should be truncated, got ${resolved.length} chars")
    assert(resolved.contains("truncated"), s"Should contain truncation notice: $resolved")
  }

  test("resolveTemplate: small output is not truncated") {
    val resolved = FlowActor.resolveTemplate("Result: ${step1}", Map("step1" -> "small output"))
    assertEquals(resolved, "Result: small output")
  }

  // ============================================================
  // buildVerifyContext — real code path
  // ============================================================

  test("buildVerifyContext: shows completed step output") {
    val results = Map("step1" -> "All tests passed")
    val failedReasons = Map.empty[String, String]
    val stepStatus = Map("step1" -> StepStatus.Done)

    val context = FlowActor.buildVerifyContext(results, failedReasons, stepStatus)
    assert(context.contains("[step1]"), s"Should show step1: $context")
    assert(context.contains("All tests passed"), s"Should show step output: $context")
    assert(!context.contains("[FAILED]"), s"Should not show FAILED for completed step: $context")
  }

  test("buildVerifyContext: shows failed step with error reason") {
    // Regression test for Bug 2: failed steps were invisible to verify agent
    val results = Map("step1" -> "All tests passed")
    val failedReasons = Map("step2" -> "Compilation error: missing semicolon")
    val stepStatus = Map("step1" -> StepStatus.Done, "step2" -> StepStatus.Failed)

    val context = FlowActor.buildVerifyContext(results, failedReasons, stepStatus)
    assert(context.contains("[step2] [FAILED]"), s"Should show failed step2: $context")
    assert(context.contains("Compilation error"), s"Should show error reason: $context")
    assert(context.contains("[step1]"), s"Should also show successful step: $context")
  }

  test("buildVerifyContext: truncates long step output") {
    val longOutput = "A" * 5000
    val results = Map("step1" -> longOutput)
    val failedReasons = Map.empty[String, String]
    val stepStatus = Map("step1" -> StepStatus.Done)

    val context = FlowActor.buildVerifyContext(results, failedReasons, stepStatus)
    assert(context.contains("truncated"), s"Should show truncation notice: $context")
    assert(context.length < longOutput.length, s"Output should be truncated in context")
  }

  // ============================================================
  // FlowResult
  // ============================================================

  test("FlowResult: Pass/Fail/Cancelled type detection") {
    assertEquals(FlowResult.resultType(FlowResult.Pass("ok")), "pass")
    assertEquals(FlowResult.resultType(FlowResult.Fail("bad")), "fail")
    assertEquals(FlowResult.resultType(FlowResult.Cancelled("stopped")), "cancelled")

    assertEquals(FlowResult.summary(FlowResult.Pass("all good")), "all good")
    assertEquals(FlowResult.summary(FlowResult.Fail("issues found")), "issues found")
  }

end FlowEngineSpec
