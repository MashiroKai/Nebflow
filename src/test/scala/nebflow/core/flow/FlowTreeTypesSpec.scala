package nebflow.core.flow

import io.circe.syntax.*
import io.circe.parser.decode
import io.circe.{Decoder, Encoder, Json}
import munit.FunSuite

class FlowTreeTypesSpec extends FunSuite:

  // ============================================================
  // Helper: encode then decode, assert equality
  // ============================================================

  private def roundTrip[A: Encoder: Decoder](value: A): A =
    val json = value.asJson.noSpaces
    decode[A](json) match
      case Right(v) => v
      case Left(e) => fail(s"Round-trip decode failed: $e\nJSON: $json")

  private def assertFailsLeft[A: Encoder: Decoder](jsonStr: String): Unit =
    decode[A](jsonStr) match
      case Right(_) => fail(s"Should have failed to decode: $jsonStr")
      case Left(_) => ()

  // ============================================================
  // RetryTarget
  // ============================================================

  test("RetryTarget: default maxIterations round-trip") {
    val rt = RetryTarget(target = "fix-step")
    val decoded = roundTrip(rt)
    assertEquals(decoded.target, "fix-step")
    assertEquals(decoded.maxIterations, 3)
  }

  test("RetryTarget: custom maxIterations round-trip") {
    val rt = RetryTarget(target = "retry-node", maxIterations = 10)
    val decoded = roundTrip(rt)
    assertEquals(decoded.target, "retry-node")
    assertEquals(decoded.maxIterations, 10)
  }

  test("RetryTarget: decodes without maxIterations (defaults to 3)") {
    val json = """{"target":"t"}"""
    decode[RetryTarget](json) match
      case Right(rt) =>
        assertEquals(rt.target, "t")
        assertEquals(rt.maxIterations, 3)
      case Left(e) => fail(s"Decode failed: $e")
  }

  // ============================================================
  // FlowNode
  // ============================================================

  test("FlowNode: atomic agent node round-trip") {
    val node = FlowNode(
      id = "explore",
      agent = Some("Explorer"),
      prompt = Some("Analyze the codebase")
    )
    val decoded = roundTrip(node)
    assertEquals(decoded.id, "explore")
    assertEquals(decoded.agent, Some("Explorer"))
    assertEquals(decoded.prompt, Some("Analyze the codebase"))
    assertEquals(decoded.flow, None)
    assertEquals(decoded.dependsOn, Set.empty)
    assertEquals(decoded.verdict, false)
    assertEquals(decoded.retry, None)
    assertEquals(decoded.condition, None)
    assertEquals(decoded.timeoutSeconds, 1800)
    assert(!decoded.isNested)
  }

  test("FlowNode: atomic node with dependsOn and custom timeout round-trip") {
    val node = FlowNode(
      id = "build",
      agent = Some("Coder"),
      prompt = Some("Compile and test"),
      dependsOn = Set("explore", "plan"),
      timeoutSeconds = 600
    )
    val decoded = roundTrip(node)
    assertEquals(decoded.id, "build")
    assertEquals(decoded.agent, Some("Coder"))
    assertEquals(decoded.dependsOn, Set("explore", "plan"))
    assertEquals(decoded.timeoutSeconds, 600)
    assert(!decoded.isNested)
  }

  test("FlowNode: nested flow node round-trip") {
    val node = FlowNode(
      id = "sub-flow",
      flow = Some("deploy-flow")
    )
    val decoded = roundTrip(node)
    assertEquals(decoded.id, "sub-flow")
    assertEquals(decoded.flow, Some("deploy-flow"))
    assertEquals(decoded.agent, None)
    assertEquals(decoded.prompt, None)
    assert(decoded.isNested)
  }

  test("FlowNode: nested flow node with flowInput, dependsOn, timeout round-trip") {
    val node = FlowNode(
      id = "deploy",
      flow = Some("deploy-flow"),
      flowInput = Some("${buildResult}"),
      dependsOn = Set("build"),
      timeoutSeconds = 300
    )
    val decoded = roundTrip(node)
    assertEquals(decoded.flow, Some("deploy-flow"))
    assertEquals(decoded.flowInput, Some("${buildResult}"))
    assertEquals(decoded.dependsOn, Set("build"))
    assertEquals(decoded.timeoutSeconds, 300)
  }

  test("FlowNode: verdict node round-trip") {
    val node = FlowNode(
      id = "verify",
      agent = Some("Explorer"),
      prompt = Some("Check correctness"),
      verdict = true
    )
    val decoded = roundTrip(node)
    assertEquals(decoded.id, "verify")
    assertEquals(decoded.verdict, true)
    assert(decoded.isVerdict)
  }

  test("FlowNode: node with retry round-trip") {
    val node = FlowNode(
      id = "verify",
      agent = Some("Explorer"),
      prompt = Some("Check results"),
      verdict = true,
      retry = Some(RetryTarget(target = "fix", maxIterations = 5))
    )
    val decoded = roundTrip(node)
    assertEquals(decoded.retry, Some(RetryTarget("fix", 5)))
  }

  test("FlowNode: node with condition round-trip") {
    val node = FlowNode(
      id = "fix",
      agent = Some("Coder"),
      prompt = Some("Fix issues"),
      condition = Some("verify.fail")
    )
    val decoded = roundTrip(node)
    assertEquals(decoded.condition, Some("verify.fail"))
  }

  test("FlowNode: atomic JSON omits flow field") {
    val node = FlowNode(id = "s", agent = Some("Explorer"), prompt = Some("p"))
    val json = node.asJson
    assert(json.asObject.forall(!_.contains("flow")), "Atomic node JSON should not contain 'flow' field")
  }

  test("FlowNode: nested JSON omits agent and prompt fields") {
    val node = FlowNode(id = "s", flow = Some("f"))
    val json = node.asJson
    val obj = json.asObject.getOrElse(fail("Should be a JSON object"))
    assert(!obj.contains("agent"), "Nested node JSON should not contain 'agent' field")
    assert(!obj.contains("prompt"), "Nested node JSON should not contain 'prompt' field")
  }

  test("FlowNode: non-verdict JSON omits verdict field") {
    val node = FlowNode(id = "s", agent = Some("Explorer"), prompt = Some("p"))
    val json = node.asJson
    assert(json.asObject.forall(!_.contains("verdict")), "Non-verdict node JSON should not contain 'verdict' field")
  }

  test("FlowNode: rejects both agent and flow") {
    intercept[IllegalArgumentException] {
      FlowNode(id = "bad", agent = Some("X"), prompt = Some("y"), flow = Some("z"))
    }
  }

  test("FlowNode: rejects neither agent nor flow") {
    intercept[IllegalArgumentException] {
      FlowNode(id = "bad")
    }
  }

  test("FlowNode: agent without prompt is rejected") {
    intercept[IllegalArgumentException] {
      FlowNode(id = "bad", agent = Some("X"))
    }
  }

  // ============================================================
  // BranchPhase
  // ============================================================

  test("BranchPhase: all five phases round-trip") {
    BranchPhase.values.foreach { phase =>
      val decoded = roundTrip(phase)
      assertEquals(decoded, phase)
    }
  }

  test("BranchPhase: rejects unknown phase name") {
    assertFailsLeft[BranchPhase](""""Teleported"""")
  }

  // ============================================================
  // BranchState
  // ============================================================

  test("BranchState: minimal state round-trip") {
    val state = BranchState(
      name = "watcher",
      address = "nebflow://local/watcher",
      flowName = "watch-flow",
      phase = BranchPhase.Running
    )
    val decoded = roundTrip(state)
    assertEquals(decoded.name, "watcher")
    assertEquals(decoded.address, "nebflow://local/watcher")
    assertEquals(decoded.flowName, "watch-flow")
    assertEquals(decoded.phase, BranchPhase.Running)
    assertEquals(decoded.stepStatus, Map.empty)
    assertEquals(decoded.results, Map.empty)
    assertEquals(decoded.failedReasons, Map.empty)
    assertEquals(decoded.retryLeft, Map.empty)
    assertEquals(decoded.verdicts, Map.empty)
    assertEquals(decoded.iteration, 0)
    assertEquals(decoded.children, Map.empty)
    assertEquals(decoded.parentBranch, None)
  }

  test("BranchState: pipeline state with step results round-trip") {
    val state = BranchState(
      name = "build-flow",
      address = "nebflow://local/build",
      flowName = "ci-pipeline",
      phase = BranchPhase.Running,
      stepStatus = Map("s1" -> "Done"),
      results = Map("s1" -> "Build successful"),
      retryLeft = Map("s1" -> 1),
      verdicts = Map("verify" -> true),
      iteration = 1
    )
    val decoded = roundTrip(state)
    assertEquals(decoded.flowName, "ci-pipeline")
    assertEquals(decoded.stepStatus, Map("s1" -> "Done"))
    assertEquals(decoded.results, Map("s1" -> "Build successful"))
    assertEquals(decoded.retryLeft, Map("s1" -> 1))
    assertEquals(decoded.verdicts, Map("verify" -> true))
    assertEquals(decoded.iteration, 1)
  }

  test("BranchState: crashed branch with failed reasons round-trip") {
    val state = BranchState(
      name = "crashed-branch",
      address = "nebflow://local/crashed",
      flowName = "dangerous-flow",
      phase = BranchPhase.Crashed,
      parentBranch = Some("parent-flow"),
      failedReasons = Map("proc" -> "exit code 1")
    )
    val decoded = roundTrip(state)
    assertEquals(decoded.phase, BranchPhase.Crashed)
    assertEquals(decoded.parentBranch, Some("parent-flow"))
    assertEquals(decoded.failedReasons, Map("proc" -> "exit code 1"))
  }

  test("BranchState: state with children map round-trip") {
    val state = BranchState(
      name = "parent",
      address = "nebflow://local/parent",
      flowName = "parent-flow",
      phase = BranchPhase.Completed,
      children = Map("child-a" -> "branch-a", "child-b" -> "branch-b")
    )
    val decoded = roundTrip(state)
    assertEquals(decoded.children, Map("child-a" -> "branch-a", "child-b" -> "branch-b"))
    assertEquals(decoded.phase, BranchPhase.Completed)
  }

  // ============================================================
  // FlowTreeSnapshot
  // ============================================================

  test("FlowTreeSnapshot: round-trip with multiple branches") {
    val snapshot = FlowTreeSnapshot(
      sessionId = "session-001",
      branches = Map(
        "branch-1" -> BranchState(
          name = "branch-1",
          address = "nebflow://local/branch-1",
          flowName = "simple-flow",
          phase = BranchPhase.Running
        ),
        "branch-2" -> BranchState(
          name = "branch-2",
          address = "nebflow://local/branch-2",
          flowName = "ci-pipeline",
          phase = BranchPhase.Running,
          stepStatus = Map("explore" -> "Done", "impl" -> "Running"),
          results = Map("explore" -> "Found 3 files"),
          verdicts = Map("verify" -> false),
          children = Map("sub-1" -> "sub-branch-1")
        )
      )
    )
    val decoded = roundTrip(snapshot)
    assertEquals(decoded.sessionId, "session-001")
    assertEquals(decoded.branches.size, 2)
    assertEquals(decoded.branches("branch-1").phase, BranchPhase.Running)
    assertEquals(decoded.branches("branch-2").stepStatus, Map("explore" -> "Done", "impl" -> "Running"))
    assertEquals(decoded.branches("branch-2").results, Map("explore" -> "Found 3 files"))
    assertEquals(decoded.branches("branch-2").verdicts, Map("verify" -> false))
    assertEquals(decoded.branches("branch-2").children, Map("sub-1" -> "sub-branch-1"))
  }

  test("FlowTreeSnapshot: empty branches map round-trip") {
    val snapshot = FlowTreeSnapshot(sessionId = "empty-session", branches = Map.empty)
    val decoded = roundTrip(snapshot)
    assertEquals(decoded.sessionId, "empty-session")
    assert(decoded.branches.isEmpty)
  }

  // ============================================================
  // FlowDefLoader — YAML parsing
  // ============================================================

  test("FlowDefLoader: parse simple flow YAML") {
    val yaml =
      """name: test-flow
        |nodes:
        |  - id: step1
        |    agent: Explorer
        |    prompt: Do something
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(defn) =>
        assertEquals(defn.name, "test-flow")
        assertEquals(defn.manager, None)
        assertEquals(defn.maxDepth, 5)
        assertEquals(defn.nodes.length, 1)
        assertEquals(defn.nodes(0).id, "step1")
        assertEquals(defn.nodes(0).agent, Some("Explorer"))
        assertEquals(defn.nodes(0).prompt, Some("Do something"))
      case Left(err) => fail(s"Parse should succeed: $err")
  }

  test("FlowDefLoader: parse flow YAML with manager and multiple nodes") {
    val yaml =
      """name: ci-flow
        |manager: Nebula
        |maxDepth: 10
        |nodes:
        |  - id: lint
        |    agent: Explorer
        |    prompt: Run linters
        |  - id: test
        |    agent: Coder
        |    prompt: Run tests
        |    dependsOn:
        |      - lint
        |    timeoutSeconds: 600
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(defn) =>
        assertEquals(defn.name, "ci-flow")
        assertEquals(defn.manager, Some("Nebula"))
        assertEquals(defn.maxDepth, 10)
        assertEquals(defn.nodes.length, 2)
        assertEquals(defn.nodes(0).id, "lint")
        assertEquals(defn.nodes(1).id, "test")
        assertEquals(defn.nodes(1).dependsOn, Set("lint"))
        assertEquals(defn.nodes(1).timeoutSeconds, 600)
      case Left(err) => fail(s"Parse should succeed: $err")
  }

  test("FlowDefLoader: parse flow YAML with verdict and retry nodes") {
    val yaml =
      """name: verify-flow
        |nodes:
        |  - id: build
        |    agent: Coder
        |    prompt: Build the project
        |  - id: verify
        |    agent: Explorer
        |    prompt: Check correctness
        |    verdict: true
        |    dependsOn:
        |      - build
        |    retry:
        |      target: build
        |      maxIterations: 3
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(defn) =>
        assertEquals(defn.nodes.length, 2)
        val verify = defn.nodes(1)
        assertEquals(verify.id, "verify")
        assertEquals(verify.verdict, true)
        assertEquals(verify.retry, Some(RetryTarget("build", 3)))
      case Left(err) => fail(s"Parse should succeed: $err")
  }

  test("FlowDefLoader: parse flow with nested flow reference node") {
    val yaml =
      """name: parent-flow
        |nodes:
        |  - id: prepare
        |    agent: Explorer
        |    prompt: Prepare
        |  - id: deploy
        |    flow: deploy-flow
        |    dependsOn:
        |      - prepare
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(defn) =>
        assertEquals(defn.nodes.length, 2)
        assert(defn.nodes(0).agent.isDefined, "First node should be atomic")
        assert(defn.nodes(1).isNested, "Second node should be nested flow reference")
        assertEquals(defn.nodes(1).flow, Some("deploy-flow"))
        assertEquals(defn.nodes(1).dependsOn, Set("prepare"))
      case Left(err) => fail(s"Parse should succeed: $err")
  }

  test("FlowDefLoader: invalid YAML returns Left") {
    val yaml = "name: [unclosed bracket"
    FlowDefLoader.parse(yaml) match
      case Right(_) => fail("Should fail on invalid YAML syntax")
      case Left(err) => assert(err.contains("YAML parse error"), s"Error should mention YAML parse: $err")
  }

  test("FlowDefLoader: missing name returns Left") {
    val yaml =
      """nodes:
        |  - id: s
        |    agent: Explorer
        |    prompt: test
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(_) => fail("Should fail when name is missing")
      case Left(err) => assert(err.contains("name"), s"Error should mention name: $err")
  }

  test("FlowDefLoader: flow with no nodes defaults to empty list") {
    val yaml = "name: empty-flow\n"
    FlowDefLoader.parse(yaml) match
      case Right(defn) =>
        assertEquals(defn.name, "empty-flow")
        assert(defn.nodes.isEmpty, "Nodes should default to empty")
      case Left(err) => fail(s"Parse should succeed: $err")
  }

  test("FlowDefLoader: manager defaults to None when absent") {
    val yaml =
      """name: no-manager
        |nodes:
        |  - id: s
        |    agent: Explorer
        |    prompt: test
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(defn) =>
        assertEquals(defn.manager, None)
      case Left(err) => fail(s"Parse should succeed: $err")
  }

end FlowTreeTypesSpec
