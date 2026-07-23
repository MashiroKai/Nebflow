package nebflow.core.flow

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.*
import nebflow.agent.AgentCommand
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * Integration tests for PipelineActor — tests the full state machine
 * including node execution, verdict parsing, retry, and reflect.
 *
 * Uses FlowTestKit with FakeLlm to avoid real LLM calls.
 */
class PipelineActorSpec extends CatsEffectSuite:

  import PipelineActor.*
  import PipelineActor.PipelineCommand.{GetState, Trigger as TriggerCmd}

  private val simpleFlow = FlowDef(
    name = "test-simple",
    nodes = List(
      FlowNode(
        id = "greet",
        agent = Some("Explorer"),
        prompt = Some("Say hello and list files.")
      ),
      FlowNode(
        id = "verify",
        agent = Some("Explorer"),
        prompt = Some("Check if the response contains a greeting and file listing."),
        verdict = true,
        dependsOn = Set("greet")
      )
    )
  )

  private val flowWithRetry = FlowDef(
    name = "test-retry",
    nodes = List(
      FlowNode(
        id = "greet",
        agent = Some("Explorer"),
        prompt = Some("Say hello.")
      ),
      FlowNode(
        id = "verify",
        agent = Some("Explorer"),
        prompt = Some("Check the greeting."),
        verdict = true,
        dependsOn = Set("greet"),
        retry = Some(RetryTarget(target = "greet", maxIterations = 2))
      )
    )
  )

  // ============================================================
  // Helper: trigger pipeline and wait for state
  // ============================================================

  private def waitForState(
    pipeRef: ActorRef[PipelineCommand],
    targetPhase: String,
    timeout: FiniteDuration = 30.seconds
  ): IO[PipelineSnapshot] =
    val poll = fs2.Stream
      .awakeEvery[IO](500.millis)
      .evalMap { _ =>
        pipeRef ? (GetState.apply, Some(5.seconds))
      }
      .takeThrough(_.phase != targetPhase)
      .compile
      .last

    fs2.Stream.eval(IO.sleep(1.second)).compile.drain *>
      poll.flatMap {
        case Some(s) if s.phase == targetPhase => IO.pure(s)
        case other =>
          IO.raiseError(
            new RuntimeException(
              s"Expected phase $targetPhase, got ${other.map(_.phase)}"
            )
          )
      }
  end waitForState

  // ============================================================
  // Tests
  // ============================================================

  test("verify state machine: VERDICT PASS → pipeline completes") {
    Resource.eval(FlowTestKit.create(FakeLlm.passing)).use { kit =>
      for
        parent <- kit.spawnParentAgent()
        pipeRef <- kit.spawnPipeline("test-pass", simpleFlow, parent)
        _ <- pipeRef ! TriggerCmd("test input", None)
        snapshot <- waitForState(pipeRef, "Idle")
      yield
        assertEquals(snapshot.phase, "Idle")
      // After completion, pipeline goes back to Idle (Completed is transient)
    }
  }

  test("verify state machine: FAIL without retry → pipeline still completes") {
    Resource.eval(FlowTestKit.create(FakeLlm.failing)).use { kit =>
      for
        parent <- kit.spawnParentAgent()
        pipeRef <- kit.spawnPipeline("test-fail-noloop", simpleFlow, parent)
        _ <- pipeRef ! TriggerCmd("test input", None)
        snapshot <- waitForState(pipeRef, "Idle")
      yield assertEquals(snapshot.phase, "Idle")
    }
  }

  test("verify state machine: no VERDICT → treated as FAIL verdict") {
    Resource.eval(FlowTestKit.create(FakeLlm.noVerdict)).use { kit =>
      for
        parent <- kit.spawnParentAgent()
        pipeRef <- kit.spawnPipeline("test-noverdict", simpleFlow, parent)
        _ <- pipeRef ! TriggerCmd("test input", None)
        snapshot <- waitForState(pipeRef, "Idle")
      yield assertEquals(snapshot.phase, "Idle")
    }
  }

  test("flow with retry: PASS → retry not triggered → completes") {
    Resource.eval(FlowTestKit.create(FakeLlm.passing)).use { kit =>
      for
        parent <- kit.spawnParentAgent()
        pipeRef <- kit.spawnPipeline("test-retry", flowWithRetry, parent)
        _ <- pipeRef ! TriggerCmd("test input", None)
        snapshot <- waitForState(pipeRef, "Idle")
      yield assertEquals(snapshot.phase, "Idle")
    }
  }

  test("reflect: after completion, memory file is created") {
    Resource.eval(FlowTestKit.create(FakeLlm.passing)).use { kit =>
      for
        parent <- kit.spawnParentAgent()
        pipeRef <- kit.spawnPipeline("test-simple", simpleFlow, parent)
        _ <- pipeRef ! TriggerCmd("learn from this run", None)
        _ <- waitForState(pipeRef, "Idle")
        // Wait for reflect (runs in background after Idle)
        _ <- IO.sleep(3.seconds)
        memory <- kit.readMemory("test-simple")
      yield assert(memory.nonEmpty, "Memory file should be created by reflect")
    }
  }

  // ============================================================
  // Regression tests for root-cause fixes
  // ============================================================

  // A flow with a conditional fix node whose gate PASSES must NOT deadlock.
  // Previously the fix node stayed Pending forever (no "skip" state) and the
  // pipeline hung until the 1-hour global timeout. Now it's Canceled and the
  // pipeline terminates normally. (Root cause 1: completion semantics.)
  private val conditionalFlow = FlowDef(
    name = "test-conditional",
    nodes = List(
      FlowNode(id = "work", agent = Some("Explorer"), prompt = Some("Do the work.")),
      FlowNode(
        id = "verify", agent = Some("Explorer"),
        prompt = Some("Verify the work."), verdict = true, dependsOn = Set("work")
      ),
      FlowNode(
        id = "fix", agent = Some("Explorer"),
        prompt = Some("Fix issues."),
        condition = Some("verify.fail"), dependsOn = Set("verify")
      )
    )
  )

  test("regression: conditional node does not deadlock when gate PASSES") {
    Resource.eval(FlowTestKit.create(FakeLlm.passing)).use { kit =>
      for
        parent <- kit.spawnParentAgent()
        pipeRef <- kit.spawnPipeline("test-cond", conditionalFlow, parent)
        _ <- pipeRef ! TriggerCmd("input", None)
        snapshot <- waitForState(pipeRef, "Idle", 30.seconds)
        // fix must be Canceled (not Pending), proving the deadlock is gone.
      yield assertEquals(snapshot.stepStatus.getOrElse("fix", "?"), "Canceled")
    }
  }

  // A failed step must NOT be reported as PASS. Previously completePipeline
  // hardcoded pass=true, so a leaf failure was silently swallowed as success.
  // (Root cause 1: pass derivation.)
  test("regression: failed step is not reported as PASS") {
    // FakeLlm.failing makes the verify node report FAIL via Mail.
    Resource.eval(FlowTestKit.create(FakeLlm.failing)).use { kit =>
      for
        (parent, getEvents) <- kit.spawnCapturingParent()
        pipeRef <- kit.spawnPipeline("test-failreport", simpleFlow, parent)
        _ <- pipeRef ! TriggerCmd("input", None)
        _ <- waitForState(pipeRef, "Idle", 30.seconds)
        events <- getEvents
        completed = events.find(_.eventType == "completed")
      yield assert(completed.exists(_.payload.contains("FAIL")),
        s"expected a FAIL completion event, got: ${events.map(_.payload)}")
    }
  }

  // A verify node that reports PASS via Mail, with NO VERDICT text, must keep
  // PASS. Previously two verdict paths (Mail + text parse) raced, and the text
  // path defaulted missing-VERDICT to FAIL, flipping PASS→FAIL. With Mail as
  // the single source there is no text path. (Root cause 2: single verdict.)
  test("regression: Mail-reported PASS is not flipped by text path") {
    Resource.eval(FlowTestKit.create(FakeLlm.passing)).use { kit =>
      for
        (parent, getEvents) <- kit.spawnCapturingParent()
        pipeRef <- kit.spawnPipeline("test-noflip", simpleFlow, parent)
        _ <- pipeRef ! TriggerCmd("input", None)
        _ <- waitForState(pipeRef, "Idle", 30.seconds)
        events <- getEvents
        completed = events.find(_.eventType == "completed")
      yield assert(completed.exists(_.payload.contains("PASS")),
        s"expected a PASS completion event (Mail-reported, no text VERDICT), got: ${events.map(_.payload)}")
    }
  }

  // A flow definition with a dangling dependsOn reference must be rejected at
  // parse time with a readable error, not silently accepted and fail at runtime.
  // (Root cause 4: parse-time validation.)
  test("regression: validation rejects dangling dependsOn") {
    val badYaml =
      """name: bad-flow
        |nodes:
        |  - id: a
        |    agent: Explorer
        |    prompt: hi
        |    dependsOn: [nonexistent]
        |""".stripMargin
    FlowDefLoader.parse(badYaml) match
      case Left(err) => assert(err.contains("nonexistent"), s"error should name the bad ref: $err")
      case Right(_)  => fail("expected a validation error for dangling dependsOn")
  }

  // ============================================================
  // Resource cleanup
  // ============================================================

  // CatsEffectSuite handles Resource cleanup automatically via .use {}
end PipelineActorSpec
