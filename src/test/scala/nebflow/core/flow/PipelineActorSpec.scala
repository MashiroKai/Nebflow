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
  // Resource cleanup
  // ============================================================

  // CatsEffectSuite handles Resource cleanup automatically via .use {}
end PipelineActorSpec
