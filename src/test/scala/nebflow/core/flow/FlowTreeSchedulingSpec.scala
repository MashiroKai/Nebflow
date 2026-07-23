package nebflow.core.flow

import munit.FunSuite

/**
 * Tests for pipeline pure scheduling logic — no IO, no actors, no LLM.
 * These test the core algorithms that drive the pipeline state machine.
 */
class FlowTreeSchedulingSpec extends FunSuite:

  import PipelineActor.{resolveTemplate, buildNodeResultsContext}
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
  // buildNodeResultsContext — context block for manager/verify
  // ============================================================

  test("buildNodeResultsContext includes completed step results"):
    val results = Map("explore" -> "Found src/ and test/")
    val failedReasons = Map.empty[String, String]
    val stepStatus = Map("explore" -> Done.toString)
    val ctx = buildNodeResultsContext(results, failedReasons, stepStatus)
    assert(ctx.contains("[explore]"), "Should include step id")
    assert(ctx.contains("Found src/ and test/"), "Should include step output")
    assert(ctx.contains("=== Step Results ==="), "Should have header")

  test("buildNodeResultsContext includes failed step reasons"):
    val results = Map.empty[String, String]
    val failedReasons = Map("build" -> "Compilation error")
    val stepStatus = Map("build" -> Failed.toString)
    val ctx = buildNodeResultsContext(results, failedReasons, stepStatus)
    assert(ctx.contains("[build] [FAILED]"), "Should mark as failed")
    assert(ctx.contains("Compilation error"), "Should include failure reason")

  test("buildNodeResultsContext handles mixed success and failure"):
    val results = Map("explore" -> "OK")
    val failedReasons = Map("build" -> "Error")
    val stepStatus = Map("explore" -> Done.toString, "build" -> Failed.toString)
    val ctx = buildNodeResultsContext(results, failedReasons, stepStatus)
    assert(ctx.contains("[explore]"))
    assert(ctx.contains("[build] [FAILED]"))

  test("buildNodeResultsContext truncates long step output"):
    val longOutput = "y" * 5000
    val results = Map("step1" -> longOutput)
    val ctx = buildNodeResultsContext(results, Map.empty, Map("step1" -> Done.toString))
    assert(ctx.contains("truncated"), "Should truncate long output in context")

  test("buildNodeResultsContext handles empty results"):
    val ctx = buildNodeResultsContext(Map.empty, Map.empty, Map.empty)
    assert(ctx.contains("=== Step Results ==="))
    assert(ctx.contains("=== End Results ==="))

  // ============================================================
  // DAG readiness logic — which nodes are ready to run
  // ============================================================

  test("node with no dependencies is ready when Pending"):
    val node = FlowNode(id = "a", agent = Some("Explorer"), prompt = Some("do a"))
    // Simulate the filter condition from scheduleReadySteps
    val stepStatus = Map("a" -> StepStatus.Pending.toString)
    val isReady = stepStatus.get(node.id).contains(StepStatus.Pending.toString) &&
      node.dependsOn.forall(dep => stepStatus.get(dep).contains(StepStatus.Done.toString))
    assert(isReady)

  test("node with unmet dependency is not ready"):
    val node = FlowNode(id = "b", agent = Some("Explorer"), prompt = Some("do b"), dependsOn = Set("a"))
    val stepStatus = Map("a" -> StepStatus.Pending.toString, "b" -> StepStatus.Pending.toString)
    val isReady = stepStatus.get(node.id).contains(StepStatus.Pending.toString) &&
      node.dependsOn.forall(dep => stepStatus.get(dep).contains(StepStatus.Done.toString))
    assert(!isReady)

  test("node with met dependency is ready"):
    val node = FlowNode(id = "b", agent = Some("Explorer"), prompt = Some("do b"), dependsOn = Set("a"))
    val stepStatus = Map("a" -> StepStatus.Done.toString, "b" -> StepStatus.Pending.toString)
    val isReady = stepStatus.get(node.id).contains(StepStatus.Pending.toString) &&
      node.dependsOn.forall(dep => stepStatus.get(dep).contains(StepStatus.Done.toString))
    assert(isReady)

  test("node already running is not ready"):
    val node = FlowNode(id = "a", agent = Some("Explorer"), prompt = Some("do a"))
    val stepStatus = Map("a" -> StepStatus.Running.toString)
    val isReady = stepStatus.get(node.id).contains(StepStatus.Pending.toString) &&
      node.dependsOn.forall(dep => stepStatus.get(dep).contains(StepStatus.Done.toString))
    assert(!isReady)

  test("parallel nodes with no deps are both ready"):
    val nodeA = FlowNode(id = "a", agent = Some("Explorer"), prompt = Some("do a"))
    val nodeB = FlowNode(id = "b", agent = Some("Explorer"), prompt = Some("do b"))
    val stepStatus = Map("a" -> StepStatus.Pending.toString, "b" -> StepStatus.Pending.toString)
    val readyA = stepStatus.get(nodeA.id).contains(StepStatus.Pending.toString) &&
      nodeA.dependsOn.forall(dep => stepStatus.get(dep).contains(StepStatus.Done.toString))
    val readyB = stepStatus.get(nodeB.id).contains(StepStatus.Pending.toString) &&
      nodeB.dependsOn.forall(dep => stepStatus.get(dep).contains(StepStatus.Done.toString))
    assert(readyA && readyB)

  test("diamond dependency: d depends on b and c, both depend on a"):
    val nodeA = FlowNode(id = "a", agent = Some("X"), prompt = Some(""))
    val nodeB = FlowNode(id = "b", agent = Some("X"), prompt = Some(""), dependsOn = Set("a"))
    val nodeC = FlowNode(id = "c", agent = Some("X"), prompt = Some(""), dependsOn = Set("a"))
    val nodeD = FlowNode(id = "d", agent = Some("X"), prompt = Some(""), dependsOn = Set("b", "c"))
    val stepStatus = Map(
      "a" -> StepStatus.Done.toString,
      "b" -> StepStatus.Done.toString,
      "c" -> StepStatus.Pending.toString,
      "d" -> StepStatus.Pending.toString
    )
    val isDReady = stepStatus.get(nodeD.id).contains(StepStatus.Pending.toString) &&
      nodeD.dependsOn.forall(dep => stepStatus.get(dep).contains(StepStatus.Done.toString))
    // c is not done yet, so d is not ready
    assert(!isDReady)

  // ============================================================
  // FlowNode validation
  // ============================================================

  test("nested node has isNested=true"):
    val node = FlowNode(id = "test", flow = Some("sub-flow"))
    assert(node.isNested)

  test("atomic node has isNested=false"):
    val node = FlowNode(id = "test", agent = Some("Explorer"), prompt = Some("do"))
    assert(!node.isNested)

  // Structural validity is now via isValid (root cause 4 removed the throwing require).
  test("node cannot have both agent and flow"):
    assert(!FlowNode(id = "bad", agent = Some("X"), prompt = Some("y"), flow = Some("z")).isValid)

  test("node cannot have neither agent nor flow"):
    assert(!FlowNode(id = "bad").isValid)

end FlowTreeSchedulingSpec
