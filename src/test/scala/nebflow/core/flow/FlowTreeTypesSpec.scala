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
  // PipelineStep
  // ============================================================

  test("PipelineStep: atomic step (agent + prompt) round-trip") {
    val step = PipelineStep(
      id = "explore",
      agent = Some("Explorer"),
      prompt = Some("Analyze the codebase")
    )
    val decoded = roundTrip(step)
    assertEquals(decoded.id, "explore")
    assertEquals(decoded.agent, Some("Explorer"))
    assertEquals(decoded.prompt, Some("Analyze the codebase"))
    assertEquals(decoded.flow, None)
    assertEquals(decoded.dependsOn, Set.empty)
    assertEquals(decoded.retry, 2)
    assertEquals(decoded.timeoutSeconds, 1800)
    assert(!decoded.isNested)
  }

  test("PipelineStep: atomic step with custom dependsOn, retry, timeout round-trip") {
    val step = PipelineStep(
      id = "build",
      agent = Some("Nebula"),
      prompt = Some("Compile and test"),
      dependsOn = Set("explore", "plan"),
      retry = 5,
      timeoutSeconds = 600
    )
    val decoded = roundTrip(step)
    assertEquals(decoded.id, "build")
    assertEquals(decoded.agent, Some("Nebula"))
    assertEquals(decoded.dependsOn, Set("explore", "plan"))
    assertEquals(decoded.retry, 5)
    assertEquals(decoded.timeoutSeconds, 600)
    assert(!decoded.isNested)
  }

  test("PipelineStep: nested step (flow reference) round-trip") {
    val step = PipelineStep(
      id = "sub-flow",
      flow = Some("deploy-flow")
    )
    val decoded = roundTrip(step)
    assertEquals(decoded.id, "sub-flow")
    assertEquals(decoded.flow, Some("deploy-flow"))
    assertEquals(decoded.agent, None)
    assertEquals(decoded.prompt, None)
    assert(decoded.isNested)
  }

  test("PipelineStep: nested step with dependsOn and custom retry round-trip") {
    val step = PipelineStep(
      id = "nested-after",
      flow = Some("child-flow"),
      dependsOn = Set("parent-step"),
      retry = 1,
      timeoutSeconds = 300
    )
    val decoded = roundTrip(step)
    assertEquals(decoded.flow, Some("child-flow"))
    assertEquals(decoded.dependsOn, Set("parent-step"))
    assertEquals(decoded.retry, 1)
    assertEquals(decoded.timeoutSeconds, 300)
    assert(decoded.isNested)
  }

  test("PipelineStep: atomic JSON omits flow field") {
    val step = PipelineStep(id = "s", agent = Some("Explorer"), prompt = Some("p"))
    val json = step.asJson
    assert(json.asObject.forall(!_.contains("flow")), "Atomic step JSON should not contain 'flow' field")
  }

  test("PipelineStep: nested JSON omits agent and prompt fields") {
    val step = PipelineStep(id = "s", flow = Some("f"))
    val json = step.asJson
    val obj = json.asObject.getOrElse(fail("Should be a JSON object"))
    assert(!obj.contains("agent"), "Nested step JSON should not contain 'agent' field")
    assert(!obj.contains("prompt"), "Nested step JSON should not contain 'prompt' field")
  }

  // ============================================================
  // RestartPolicy
  // ============================================================

  test("RestartPolicy: all three types round-trip") {
    val permanent: RestartPolicy = RestartPolicy.Permanent
    val transient: RestartPolicy = RestartPolicy.Transient
    val temporary: RestartPolicy = RestartPolicy.Temporary
    assertEquals(roundTrip(permanent), RestartPolicy.Permanent)
    assertEquals(roundTrip(transient), RestartPolicy.Transient)
    assertEquals(roundTrip(temporary), RestartPolicy.Temporary)
  }

  test("RestartPolicy: encodes as lowercase string") {
    assertEquals(RestartPolicy.Permanent.asJson.asString, Some("permanent"))
    assertEquals(RestartPolicy.Transient.asJson.asString, Some("transient"))
    assertEquals(RestartPolicy.Temporary.asJson.asString, Some("temporary"))
  }

  test("RestartPolicy: rejects unknown value") {
    assertFailsLeft[RestartPolicy](""""unknown"""")
  }

  // ============================================================
  // VerifyStep
  // ============================================================

  test("VerifyStep: default agent and timeout round-trip") {
    val v = VerifyStep(prompt = "Check correctness")
    val decoded = roundTrip(v)
    assertEquals(decoded.agent, "Explorer")
    assertEquals(decoded.prompt, "Check correctness")
    assertEquals(decoded.timeoutSeconds, 1800)
  }

  test("VerifyStep: custom agent and timeout round-trip") {
    val v = VerifyStep(agent = "Nebula", prompt = "Run tests", timeoutSeconds = 900)
    val decoded = roundTrip(v)
    assertEquals(decoded.agent, "Nebula")
    assertEquals(decoded.prompt, "Run tests")
    assertEquals(decoded.timeoutSeconds, 900)
  }

  // ============================================================
  // LoopConfig
  // ============================================================

  test("LoopConfig: default maxIterations round-trip") {
    val fix = PipelineStep(id = "fix", agent = Some("Nebula"), prompt = Some("Fix it"))
    val lc = LoopConfig(fix = fix)
    val decoded = roundTrip(lc)
    assertEquals(decoded.fix.id, "fix")
    assertEquals(decoded.fix.agent, Some("Nebula"))
    assertEquals(decoded.maxIterations, 3)
  }

  test("LoopConfig: custom maxIterations round-trip") {
    val fix = PipelineStep(id = "fixer", agent = Some("Explorer"), prompt = Some("Investigate"), retry = 0)
    val lc = LoopConfig(fix = fix, maxIterations = 10)
    val decoded = roundTrip(lc)
    assertEquals(decoded.maxIterations, 10)
    assertEquals(decoded.fix.retry, 0)
  }

  test("LoopConfig: fix step can be nested flow reference") {
    val fix = PipelineStep(id = "auto-fix", flow = Some("auto-fixer-flow"))
    val lc = LoopConfig(fix = fix, maxIterations = 7)
    val decoded = roundTrip(lc)
    assert(decoded.fix.isNested)
    assertEquals(decoded.fix.flow, Some("auto-fixer-flow"))
    assertEquals(decoded.maxIterations, 7)
  }

  // ============================================================
  // ReactorAction
  // ============================================================

  test("ReactorAction: RunAgent round-trip") {
    val action: ReactorAction = ReactorAction.RunAgent(agent = "Nebula", prompt = "Handle event")
    val decoded = roundTrip[ReactorAction](action)
    assertEquals(decoded, action)
  }

  test("ReactorAction: MountFlow round-trip") {
    val action: ReactorAction = ReactorAction.MountFlow(flowName = "responder-flow")
    val decoded = roundTrip[ReactorAction](action)
    assertEquals(decoded, action)
  }

  test("ReactorAction: type discriminator in JSON") {
    val runAgentJson = (ReactorAction.RunAgent("Nebula", "Do X"): ReactorAction).asJson
    assertEquals(runAgentJson.hcursor.downField("type").as[String], Right("runAgent"))

    val mountFlowJson = (ReactorAction.MountFlow("test-flow"): ReactorAction).asJson
    assertEquals(mountFlowJson.hcursor.downField("type").as[String], Right("mountFlow"))
  }

  test("ReactorAction: rejects unknown type discriminator") {
    val bad = Json
      .obj(
        "type" -> "teleport".asJson,
        "agent" -> "X".asJson,
        "prompt" -> "Y".asJson
      )
      .noSpaces
    assertFailsLeft[ReactorAction](bad)
  }

  // ============================================================
  // BranchType — all four variants
  // ============================================================

  test("BranchType: Daemon round-trip with persistent=true") {
    val d: BranchType = BranchType.Daemon(agent = "Explorer", prompt = "Watch for events", persistent = true)
    val decoded = roundTrip[BranchType](d)
    assertEquals(decoded, d)
  }

  test("BranchType: Daemon default persistent is false") {
    val d: BranchType = BranchType.Daemon(agent = "Explorer", prompt = "Watch")
    val decoded = roundTrip[BranchType](d)
    assertEquals(decoded, d)
    // Verify persistent defaulted to false in the original
    d match
      case BranchType.Daemon(_, _, persistent) => assertEquals(persistent, false)
      case _ => fail("Expected Daemon")
  }

  test("BranchType: Pipeline with steps, verify, and loop round-trip") {
    val p: BranchType = BranchType.Pipeline(
      steps = List(
        PipelineStep(id = "s1", agent = Some("Explorer"), prompt = Some("Explore")),
        PipelineStep(id = "s2", agent = Some("Nebula"), prompt = Some("Implement"), dependsOn = Set("s1"))
      ),
      verify = VerifyStep(prompt = "Check all"),
      loop = Some(
        LoopConfig(
          fix = PipelineStep(id = "fix", agent = Some("Nebula"), prompt = Some("Fix")),
          maxIterations = 5
        )
      ),
      maxConcurrency = 3
    )
    val decoded = roundTrip[BranchType](p)
    assertEquals(decoded, p)
  }

  test("BranchType: Pipeline without loop uses default maxConcurrency") {
    val p: BranchType = BranchType.Pipeline(
      steps = List(PipelineStep(id = "only", agent = Some("Explorer"), prompt = Some("Do"))),
      verify = VerifyStep(prompt = "Check")
    )
    val decoded = roundTrip[BranchType](p)
    assertEquals(decoded, p)
    decoded match
      case BranchType.Pipeline(_, _, loop, maxConcurrency) =>
        assertEquals(loop, None)
        assertEquals(maxConcurrency, 5)
      case _ => fail("Expected Pipeline")
  }

  test("BranchType: Reactor with RunAgent action round-trip") {
    val r: BranchType = BranchType.Reactor(
      subscribe = Set("github.push", "ci.failed"),
      filter = Map("repo" -> "nebflow"),
      action = ReactorAction.RunAgent("Nebula", "Respond")
    )
    val decoded = roundTrip[BranchType](r)
    assertEquals(decoded, r)
  }

  test("BranchType: Reactor with MountFlow action round-trip") {
    val r: BranchType = BranchType.Reactor(
      subscribe = Set("alert"),
      action = ReactorAction.MountFlow("incident-handler")
    )
    val decoded = roundTrip[BranchType](r)
    assertEquals(decoded, r)
  }

  test("BranchType: Source with custom restart round-trip") {
    val s: BranchType = BranchType.Source(command = "python script.py", restart = RestartPolicy.Temporary)
    val decoded = roundTrip[BranchType](s)
    assertEquals(decoded, s)
  }

  test("BranchType: Source default restart is Permanent") {
    val s: BranchType = BranchType.Source(command = "echo hello")
    val decoded = roundTrip[BranchType](s)
    assertEquals(decoded, s)
    decoded match
      case BranchType.Source(_, restart) => assertEquals(restart, RestartPolicy.Permanent)
      case _ => fail("Expected Source")
  }

  test("BranchType: type discriminator field for all variants") {
    assertEquals(
      (BranchType.Daemon("A", "B"): BranchType).asJson.hcursor.downField("type").as[String],
      Right("daemon")
    )
    assertEquals(
      (BranchType.Pipeline(List.empty, VerifyStep(prompt = "x")): BranchType).asJson.hcursor
        .downField("type")
        .as[String],
      Right("pipeline")
    )
    assertEquals(
      (BranchType.Reactor(Set.empty, action = ReactorAction.MountFlow("x")): BranchType).asJson.hcursor
        .downField("type")
        .as[String],
      Right("reactor")
    )
    assertEquals(
      (BranchType.Source("cmd"): BranchType).asJson.hcursor.downField("type").as[String],
      Right("source")
    )
  }

  test("BranchType: rejects unknown branch type discriminator") {
    val bad = Json.obj("type" -> "mystery".asJson).noSpaces
    assertFailsLeft[BranchType](bad)
  }

  // ============================================================
  // BranchPhase
  // ============================================================

  test("BranchPhase: all five phases round-trip") {
    BranchPhase.values.foreach { phase =>
      val decoded = roundTrip[BranchPhase](phase)
      assertEquals(decoded, phase)
    }
  }

  test("BranchPhase: rejects unknown phase name") {
    assertFailsLeft[BranchPhase](""""Teleported"""")
  }

  // ============================================================
  // BranchState
  // ============================================================

  test("BranchState: minimal daemon state round-trip") {
    val state = BranchState(
      name = "watcher",
      address = "nebflow://local/watcher",
      branchType = BranchType.Daemon("Explorer", "Watch"),
      phase = BranchPhase.Running
    )
    val decoded = roundTrip(state)
    assertEquals(decoded.name, "watcher")
    assertEquals(decoded.address, "nebflow://local/watcher")
    assertEquals(decoded.phase, BranchPhase.Running)
    assertEquals(decoded.stepStatus, Map.empty)
    assertEquals(decoded.results, Map.empty)
    assertEquals(decoded.failedReasons, Map.empty)
    assertEquals(decoded.retryLeft, Map.empty)
    assertEquals(decoded.verifyResult, None)
    assertEquals(decoded.iteration, 0)
    assertEquals(decoded.children, Map.empty)
    assertEquals(decoded.parentBranch, None)
  }

  test("BranchState: pipeline state with step results round-trip") {
    val state = BranchState(
      name = "build-flow",
      address = "nebflow://local/build",
      branchType = BranchType.Pipeline(
        steps = List(PipelineStep(id = "s1", agent = Some("Nebula"), prompt = Some("Build"))),
        verify = VerifyStep(prompt = "Check build")
      ),
      phase = BranchPhase.Running,
      stepStatus = Map("s1" -> "Done"),
      results = Map("s1" -> "Build successful"),
      retryLeft = Map("s1" -> 1),
      verifyResult = Some("PASS: all tests green"),
      iteration = 1
    )
    val decoded = roundTrip(state)
    assertEquals(decoded.stepStatus, Map("s1" -> "Done"))
    assertEquals(decoded.results, Map("s1" -> "Build successful"))
    assertEquals(decoded.retryLeft, Map("s1" -> 1))
    assertEquals(decoded.verifyResult, Some("PASS: all tests green"))
    assertEquals(decoded.iteration, 1)
  }

  test("BranchState: crashed source branch with failed reasons round-trip") {
    val state = BranchState(
      name = "crashed-branch",
      address = "nebflow://local/crashed",
      branchType = BranchType.Source("bad-command"),
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
      branchType = BranchType.Pipeline(
        steps = List(PipelineStep(id = "s", agent = Some("Nebula"), prompt = Some("p"))),
        verify = VerifyStep(prompt = "v")
      ),
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
        "daemon-1" -> BranchState(
          name = "daemon-1",
          address = "nebflow://local/daemon-1",
          branchType = BranchType.Daemon("Explorer", "Poll"),
          phase = BranchPhase.Running
        ),
        "pipeline-1" -> BranchState(
          name = "pipeline-1",
          address = "nebflow://local/pipeline-1",
          branchType = BranchType.Pipeline(
            steps = List(
              PipelineStep(id = "explore", agent = Some("Explorer"), prompt = Some("Go")),
              PipelineStep(id = "impl", agent = Some("Nebula"), prompt = Some("Code"), dependsOn = Set("explore"))
            ),
            verify = VerifyStep(prompt = "Test all")
          ),
          phase = BranchPhase.Running,
          stepStatus = Map("explore" -> "Done", "impl" -> "Running"),
          results = Map("explore" -> "Found 3 files"),
          children = Map("sub-1" -> "sub-branch-1")
        )
      )
    )
    val decoded = roundTrip(snapshot)
    assertEquals(decoded.sessionId, "session-001")
    assertEquals(decoded.branches.size, 2)
    assertEquals(decoded.branches("daemon-1").phase, BranchPhase.Running)
    assertEquals(decoded.branches("pipeline-1").stepStatus, Map("explore" -> "Done", "impl" -> "Running"))
    assertEquals(decoded.branches("pipeline-1").results, Map("explore" -> "Found 3 files"))
    assertEquals(decoded.branches("pipeline-1").children, Map("sub-1" -> "sub-branch-1"))
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

  test("FlowDefLoader: parse daemon YAML") {
    val yaml =
      """name: test-daemon
        |type: daemon
        |agent: Explorer
        |prompt: You are a helper
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(defn) =>
        assertEquals(defn.name, "test-daemon")
        assertEquals(defn.maxDepth, 5)
        defn.branchType match
          case BranchType.Daemon(agent, prompt, persistent) =>
            assertEquals(agent, "Explorer")
            assertEquals(prompt, "You are a helper")
            assertEquals(persistent, false)
          case other => fail(s"Expected Daemon, got $other")
      case Left(err) => fail(s"Parse should succeed: $err")
  }

  test("FlowDefLoader: parse daemon YAML with persistent flag and maxDepth") {
    val yaml =
      """name: persistent-daemon
        |type: daemon
        |agent: Nebula
        |prompt: Keep running
        |persistent: true
        |maxDepth: 10
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(defn) =>
        assertEquals(defn.name, "persistent-daemon")
        assertEquals(defn.maxDepth, 10)
        defn.branchType match
          case BranchType.Daemon(agent, prompt, persistent) =>
            assertEquals(agent, "Nebula")
            assertEquals(prompt, "Keep running")
            assertEquals(persistent, true)
          case other => fail(s"Expected Daemon, got $other")
      case Left(err) => fail(s"Parse should succeed: $err")
  }

  test("FlowDefLoader: parse pipeline YAML with steps, verify, and loop") {
    val yaml =
      """name: ci-pipeline
        |type: pipeline
        |steps:
        |  - id: lint
        |    agent: Explorer
        |    prompt: Run linters
        |  - id: test
        |    agent: Nebula
        |    prompt: Run tests
        |    dependsOn:
        |      - lint
        |    retry: 3
        |    timeoutSeconds: 600
        |verify:
        |  agent: Explorer
        |  prompt: Verify everything passes
        |loop:
        |  fix:
        |    id: fix
        |    agent: Nebula
        |    prompt: Fix failures
        |  maxIterations: 4
        |maxConcurrency: 2
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(defn) =>
        assertEquals(defn.name, "ci-pipeline")
        defn.branchType match
          case BranchType.Pipeline(steps, verify, loop, maxConcurrency) =>
            assertEquals(steps.length, 2)
            assertEquals(steps(0).id, "lint")
            assertEquals(steps(0).agent, Some("Explorer"))
            assertEquals(steps(0).prompt, Some("Run linters"))
            assertEquals(steps(1).id, "test")
            assertEquals(steps(1).dependsOn, Set("lint"))
            assertEquals(steps(1).retry, 3)
            assertEquals(steps(1).timeoutSeconds, 600)
            assertEquals(verify.agent, "Explorer")
            assertEquals(verify.prompt, "Verify everything passes")
            assertEquals(loop.map(_.maxIterations), Some(4))
            assertEquals(loop.map(_.fix.id), Some("fix"))
            assertEquals(maxConcurrency, 2)
          case other => fail(s"Expected Pipeline, got $other")
        end match
      case Left(err) => fail(s"Parse should succeed: $err")
    end match
  }

  test("FlowDefLoader: parse pipeline YAML without loop (defaults applied)") {
    val yaml =
      """name: simple-pipeline
        |type: pipeline
        |steps:
        |  - id: only
        |    agent: Explorer
        |    prompt: Do it
        |verify:
        |  prompt: Check
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(defn) =>
        defn.branchType match
          case BranchType.Pipeline(_, verify, loop, maxConcurrency) =>
            assertEquals(loop, None)
            assertEquals(maxConcurrency, 5)
            assertEquals(verify.agent, "Explorer")
          case other => fail(s"Expected Pipeline, got $other")
      case Left(err) => fail(s"Parse should succeed: $err")
  }

  test("FlowDefLoader: parse source YAML with restart policy") {
    val yaml =
      """name: webhook-listener
        |type: source
        |command: python -m webhook.server
        |restart: temporary
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(defn) =>
        assertEquals(defn.name, "webhook-listener")
        defn.branchType match
          case BranchType.Source(command, restart) =>
            assertEquals(command, "python -m webhook.server")
            assertEquals(restart, RestartPolicy.Temporary)
          case other => fail(s"Expected Source, got $other")
      case Left(err) => fail(s"Parse should succeed: $err")
  }

  test("FlowDefLoader: parse source YAML with default restart") {
    val yaml =
      """name: simple-source
        |type: source
        |command: ./run.sh
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(defn) =>
        defn.branchType match
          case BranchType.Source(command, restart) =>
            assertEquals(command, "./run.sh")
            assertEquals(restart, RestartPolicy.Permanent)
          case other => fail(s"Expected Source, got $other")
      case Left(err) => fail(s"Parse should succeed: $err")
  }

  test("FlowDefLoader: parse reactor YAML with RunAgent action") {
    val yaml =
      """name: alert-responder
        |type: reactor
        |subscribe:
        |  - alerts
        |  - incidents
        |filter:
        |  severity: critical
        |action:
        |  type: runAgent
        |  agent: Nebula
        |  prompt: Respond to the alert
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(defn) =>
        assertEquals(defn.name, "alert-responder")
        defn.branchType match
          case BranchType.Reactor(subscribe, filter, action) =>
            assertEquals(subscribe, Set("alerts", "incidents"))
            assertEquals(filter, Map("severity" -> "critical"))
            assertEquals(action, ReactorAction.RunAgent("Nebula", "Respond to the alert"))
          case other => fail(s"Expected Reactor, got $other")
      case Left(err) => fail(s"Parse should succeed: $err")
  }

  test("FlowDefLoader: parse reactor YAML with MountFlow action") {
    val yaml =
      """name: auto-deployer
        |type: reactor
        |subscribe:
        |  - push
        |action:
        |  type: mountFlow
        |  flowName: deploy-pipeline
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(defn) =>
        defn.branchType match
          case BranchType.Reactor(subscribe, filter, action) =>
            assertEquals(subscribe, Set("push"))
            assert(filter.isEmpty)
            assertEquals(action, ReactorAction.MountFlow("deploy-pipeline"))
          case other => fail(s"Expected Reactor, got $other")
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
      """type: daemon
        |agent: Explorer
        |prompt: test
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(_) => fail("Should fail when name is missing")
      case Left(err) => assert(err.contains("name"), s"Error should mention name: $err")
  }

  test("FlowDefLoader: missing type discriminator returns Left") {
    val yaml =
      """name: no-type
        |agent: Explorer
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(_) => fail("Should fail when type discriminator is missing")
      case Left(err) => () // expected
  }

  test("FlowDefLoader: unknown branch type returns Left") {
    val yaml =
      """name: bad-type
        |type: flux-capacitor
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(_) => fail("Should fail on unknown branch type")
      case Left(_) => () // expected
  }

  test("FlowDefLoader: pipeline missing steps returns Left") {
    val yaml =
      """name: no-steps
        |type: pipeline
        |verify:
        |  prompt: check
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(_) => fail("Should fail when steps is missing")
      case Left(_) => () // expected
  }

  test("FlowDefLoader: pipeline missing verify returns Left") {
    val yaml =
      """name: no-verify
        |type: pipeline
        |steps:
        |  - id: s1
        |    agent: Explorer
        |    prompt: do something
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(_) => fail("Should fail when verify is missing")
      case Left(_) => () // expected
  }

  test("FlowDefLoader: daemon missing agent returns Left") {
    val yaml =
      """name: no-agent
        |type: daemon
        |prompt: test
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(_) => fail("Should fail when agent is missing for daemon")
      case Left(_) => () // expected
  }

  test("FlowDefLoader: source missing command returns Left") {
    val yaml =
      """name: no-command
        |type: source
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(_) => fail("Should fail when command is missing for source")
      case Left(_) => () // expected
  }

  test("FlowDefLoader: reactor missing action returns Left") {
    val yaml =
      """name: no-action
        |type: reactor
        |subscribe:
        |  - events
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(_) => fail("Should fail when action is missing for reactor")
      case Left(_) => () // expected
  }

  test("FlowDefLoader: nested flow step in pipeline YAML parses correctly") {
    val yaml =
      """name: nested-pipeline
        |type: pipeline
        |steps:
        |  - id: explore
        |    agent: Explorer
        |    prompt: Explore
        |  - id: deploy
        |    flow: deploy-flow
        |    dependsOn:
        |      - explore
        |verify:
        |  prompt: Check deployment
        |""".stripMargin
    FlowDefLoader.parse(yaml) match
      case Right(defn) =>
        defn.branchType match
          case BranchType.Pipeline(steps, _, _, _) =>
            assertEquals(steps.length, 2)
            assert(steps(0).agent.isDefined, "First step should be atomic")
            assert(steps(1).isNested, "Second step should be nested flow reference")
            assertEquals(steps(1).flow, Some("deploy-flow"))
            assertEquals(steps(1).dependsOn, Set("explore"))
          case other => fail(s"Expected Pipeline, got $other")
      case Left(err) => fail(s"Parse should succeed: $err")
  }

end FlowTreeTypesSpec
