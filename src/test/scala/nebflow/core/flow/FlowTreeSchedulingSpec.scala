package nebflow.core.flow

import munit.FunSuite

/**
 * Tests for FlowTreeActor's pure scheduling logic — no IO, no actors, no LLM.
 * These test the core algorithms that drive the state machine.
 */
class FlowTreeSchedulingSpec extends FunSuite:

  import FlowTreeActor.{resolveTemplate, buildVerifyContext, buildAddressTable, BranchRuntime, TreeConfig}
  import nebflow.actor.{ActorPath, ActorRef}
  import nebflow.agent.SharedResources
  import nebflow.core.flow.BranchPhase.*
  import nebflow.core.flow.StepStatus.*

  // ============================================================
  // resolveTemplate — ${stepId} variable substitution
  // ============================================================

  test("resolveTemplate replaces single variable"):
    val results = Map("explore" -> "Found 3 directories")
    val prompt = "Based on: ${explore}"
    assertEquals(resolveTemplate(prompt, results), "Based on: Found 3 directories")

  test("resolveTemplate replaces multiple variables"):
    val results = Map("a" -> "result-a", "b" -> "result-b")
    val prompt = "A=${a}, B=${b}"
    assertEquals(resolveTemplate(prompt, results), "A=result-a, B=result-b")

  test("resolveTemplate leaves unknown variables as-is"):
    val results = Map("a" -> "result-a")
    val prompt = "A=${a}, C=${c}"
    assertEquals(resolveTemplate(prompt, results), "A=result-a, C=${c}")

  test("resolveTemplate truncates long output"):
    val longOutput = "x" * 10000
    val results = Map("step1" -> longOutput)
    val result = resolveTemplate("Output: ${step1}", results)
    assert(result.contains("truncated"), s"Should contain truncation notice")
    assert(result.length < longOutput.length + 100, "Should be significantly shorter")

  test("resolveTemplate handles empty results"):
    assertEquals(resolveTemplate("No vars", Map.empty), "No vars")

  test("resolveTemplate handles special characters in output"):
    val results = Map("step" -> "line1\nline2\ttab")
    val result = resolveTemplate("${step}", results)
    assertEquals(result, "line1\nline2\ttab")

  // ============================================================
  // buildVerifyContext — context block for verify agent
  // ============================================================

  test("buildVerifyContext includes completed step results"):
    val results = Map("explore" -> "Found src/ and test/")
    val failedReasons = Map.empty[String, String]
    val stepStatus = Map("explore" -> Done.toString)
    val ctx = buildVerifyContext(results, failedReasons, stepStatus)
    assert(ctx.contains("[explore]"), "Should include step id")
    assert(ctx.contains("Found src/ and test/"), "Should include step output")
    assert(ctx.contains("=== Step Results ==="), "Should have header")

  test("buildVerifyContext includes failed step reasons"):
    val results = Map.empty[String, String]
    val failedReasons = Map("build" -> "Compilation error")
    val stepStatus = Map("build" -> Failed.toString)
    val ctx = buildVerifyContext(results, failedReasons, stepStatus)
    assert(ctx.contains("[build] [FAILED]"), "Should mark as failed")
    assert(ctx.contains("Compilation error"), "Should include failure reason")

  test("buildVerifyContext handles mixed success and failure"):
    val results = Map("explore" -> "OK")
    val failedReasons = Map("build" -> "Error")
    val stepStatus = Map("explore" -> Done.toString, "build" -> Failed.toString)
    val ctx = buildVerifyContext(results, failedReasons, stepStatus)
    assert(ctx.contains("[explore]"))
    assert(ctx.contains("[build] [FAILED]"))

  test("buildVerifyContext truncates long step output"):
    val longOutput = "y" * 5000
    val results = Map("step1" -> longOutput)
    val ctx = buildVerifyContext(results, Map.empty, Map("step1" -> Done.toString))
    assert(ctx.contains("truncated"), "Should truncate long output in verify context")

  test("buildVerifyContext handles empty results"):
    val ctx = buildVerifyContext(Map.empty, Map.empty, Map.empty)
    assert(ctx.contains("=== Step Results ==="))
    assert(ctx.contains("=== End Results ==="))

  // ============================================================
  // buildAddressTable — address table for main agent injection
  // ============================================================

  test("buildAddressTable shows empty message when no branches"):
    val table = buildAddressTable(Map.empty)
    assert(table.contains("无"), "Should show empty message in Chinese")

  test("buildAddressTable includes branch info"):
    val state = BranchState(
      name = "test-pipeline",
      address = "nebflow://local/branch-test-pipeline-abc123",
      branchType = BranchType.Pipeline(
        steps = Nil,
        verify = VerifyStep(prompt = "test")
      ),
      phase = BranchPhase.Running
    )
    val runtime = BranchRuntime(state = state, flowDef = FlowDef("test", state.branchType))
    val table = buildAddressTable(Map("test-pipeline" -> runtime))
    assert(table.contains("test-pipeline"))
    assert(table.contains("pipeline"))
    assert(table.contains("Running"))

  test("buildAddressTable shows multiple branches"):
    val daemonState = BranchState(
      name = "code-reviewer",
      address = "nebflow://local/daemon-code-reviewer-xyz",
      branchType = BranchType.Daemon("Explorer", "review"),
      phase = BranchPhase.Running
    )
    val pipelineState = BranchState(
      name = "deploy-flow",
      address = "nebflow://local/pipeline-deploy-flow-def",
      branchType = BranchType.Pipeline(Nil, VerifyStep(prompt = "check")),
      phase = BranchPhase.Completed
    )
    val daemonRt = BranchRuntime(state = daemonState, flowDef = FlowDef("code-reviewer", daemonState.branchType))
    val pipelineRt = BranchRuntime(state = pipelineState, flowDef = FlowDef("deploy-flow", pipelineState.branchType))
    val table = buildAddressTable(
      Map(
        "code-reviewer" -> daemonRt,
        "deploy-flow" -> pipelineRt
      )
    )
    assert(table.contains("code-reviewer"))
    assert(table.contains("deploy-flow"))
    assert(table.contains("daemon"))
    assert(table.contains("Completed"))

  // ============================================================
  // DAG readiness logic — which steps are ready to run
  // ============================================================

  test("step with no dependencies is ready when Pending"):
    val step = PipelineStep(id = "a", agent = Some("Explorer"), prompt = Some("do a"))
    // Simulate the filter condition from scheduleReadySteps
    val stepStatus = Map("a" -> StepStatus.Pending.toString)
    val runningAgents = Map.empty[String, Any]
    val isReady = stepStatus.get(step.id).contains(StepStatus.Pending.toString) &&
      !runningAgents.contains(step.id) &&
      step.dependsOn.forall(dep => stepStatus.get(dep).contains(StepStatus.Done.toString))
    assert(isReady)

  test("step with unmet dependency is not ready"):
    val step = PipelineStep(id = "b", agent = Some("Explorer"), prompt = Some("do b"), dependsOn = Set("a"))
    val stepStatus = Map("a" -> StepStatus.Pending.toString, "b" -> StepStatus.Pending.toString)
    val runningAgents = Map.empty[String, Any]
    val isReady = stepStatus.get(step.id).contains(StepStatus.Pending.toString) &&
      !runningAgents.contains(step.id) &&
      step.dependsOn.forall(dep => stepStatus.get(dep).contains(StepStatus.Done.toString))
    assert(!isReady)

  test("step with met dependency is ready"):
    val step = PipelineStep(id = "b", agent = Some("Explorer"), prompt = Some("do b"), dependsOn = Set("a"))
    val stepStatus = Map("a" -> StepStatus.Done.toString, "b" -> StepStatus.Pending.toString)
    val runningAgents = Map.empty[String, Any]
    val isReady = stepStatus.get(step.id).contains(StepStatus.Pending.toString) &&
      !runningAgents.contains(step.id) &&
      step.dependsOn.forall(dep => stepStatus.get(dep).contains(StepStatus.Done.toString))
    assert(isReady)

  test("step already running is not ready"):
    val step = PipelineStep(id = "a", agent = Some("Explorer"), prompt = Some("do a"))
    val stepStatus = Map("a" -> StepStatus.Running.toString)
    val runningAgents = Map("a" -> "fake-ref")
    val isReady = stepStatus.get(step.id).contains(StepStatus.Pending.toString) &&
      !runningAgents.contains(step.id) &&
      step.dependsOn.forall(dep => stepStatus.get(dep).contains(StepStatus.Done.toString))
    assert(!isReady)

  test("parallel steps with no deps are both ready"):
    val stepA = PipelineStep(id = "a", agent = Some("Explorer"), prompt = Some("do a"))
    val stepB = PipelineStep(id = "b", agent = Some("Explorer"), prompt = Some("do b"))
    val stepStatus = Map("a" -> StepStatus.Pending.toString, "b" -> StepStatus.Pending.toString)
    val runningAgents = Map.empty[String, Any]
    val readyA = stepStatus.get(stepA.id).contains(StepStatus.Pending.toString) &&
      !runningAgents.contains(stepA.id) && stepA.dependsOn.forall(dep =>
        stepStatus.get(dep).contains(StepStatus.Done.toString)
      )
    val readyB = stepStatus.get(stepB.id).contains(StepStatus.Pending.toString) &&
      !runningAgents.contains(stepB.id) && stepB.dependsOn.forall(dep =>
        stepStatus.get(dep).contains(StepStatus.Done.toString)
      )
    assert(readyA && readyB)

  test("diamond dependency: d depends on b and c, both depend on a"):
    val stepA = PipelineStep(id = "a", agent = Some("X"), prompt = Some(""))
    val stepB = PipelineStep(id = "b", agent = Some("X"), prompt = Some(""), dependsOn = Set("a"))
    val stepC = PipelineStep(id = "c", agent = Some("X"), prompt = Some(""), dependsOn = Set("a"))
    val stepD = PipelineStep(id = "d", agent = Some("X"), prompt = Some(""), dependsOn = Set("b", "c"))
    val stepStatus = Map(
      "a" -> StepStatus.Done.toString,
      "b" -> StepStatus.Done.toString,
      "c" -> StepStatus.Pending.toString,
      "d" -> StepStatus.Pending.toString
    )
    val runningAgents = Map.empty[String, Any]
    val isDReady = stepStatus.get(stepD.id).contains(StepStatus.Pending.toString) &&
      !runningAgents.contains(stepD.id) &&
      stepD.dependsOn.forall(dep => stepStatus.get(dep).contains(StepStatus.Done.toString))
    // c is not done yet, so d is not ready
    assert(!isDReady)

  // ============================================================
  // Flow nesting — PipelineStep with flow reference
  // ============================================================

  test("nested step has isNested=true"):
    val step = PipelineStep(id = "test", flow = Some("sub-flow"))
    assert(step.isNested)

  test("atomic step has isNested=false"):
    val step = PipelineStep(id = "test", agent = Some("Explorer"), prompt = Some("do"))
    assert(!step.isNested)

  test("step cannot have both agent and flow"):
    intercept[IllegalArgumentException]:
      PipelineStep(id = "bad", agent = Some("X"), prompt = Some("y"), flow = Some("z"))

  test("step cannot have neither agent nor flow"):
    intercept[IllegalArgumentException]:
      PipelineStep(id = "bad")

end FlowTreeSchedulingSpec
