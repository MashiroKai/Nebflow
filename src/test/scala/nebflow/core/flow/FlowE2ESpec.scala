package nebflow.core.flow

import cats.effect.IO
import munit.CatsEffectSuite

/**
 * End-to-end flow tests.
 *
 * Reads .test.yaml specs from ~/.nebflow/flows/ and executes each test case
 * through the full stack: YAML load → FlowTreeActor mount → PipelineActor →
 * AgentActor → FakeLlm → verify → reflect → memory.
 *
 * To add a test for a new flow, create a <flow-name>.test.yaml alongside
 * the flow definition. No Scala code needed.
 */
class FlowE2ESpec extends CatsEffectSuite:

  test("all .test.yaml specs pass end-to-end") {
    // Reset to real ~/.nebflow so we find .test.yaml flow definitions
    val flowsDir = os.home / ".nebflow" / "flows"
    FlowTestRunner.runAll(flowsDir).flatMap { results =>
      val failed = results.filter(!_.passed)
      val report = results
        .map { r =>
          val status = if r.passed then "PASS" else "FAIL"
          s"  [$status] ${r.testName}: ${r.detail}"
        }
        .mkString("\n")

      IO {
        assertEquals(failed.size, 0, s"\n${results.size} tests run, ${failed.size} failed:\n$report\n")
      }
    }
  }
end FlowE2ESpec
