package nebflow.core.flow

import cats.effect.{IO, Deferred, Resource}
import cats.syntax.all.*
import io.circe.yaml.parser as yamlParser
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.actor.*
import nebflow.agent.AgentCommand
import nebflow.shared.*
import nebflow.core.{NebflowLogger, PathUtil}

import scala.concurrent.duration.*

/**
 * End-to-end flow test runner.
 *
 * Reads .test.yaml files from the flows directory and executes each test case
 * through the FULL stack: FlowTreeActor → PipelineActor → AgentActor → (FakeLlm).
 *
 * Test spec format (.test.yaml):
 *   flow: test-simple          # flow definition name
 *   tests:
 *     - name: "passing case"
 *       input: "Say hello"
 *       llm: passing            # passing | failing | noVerdict
 *       expect:
 *         outcome: completed    # completed | failed
 *         memoryUpdated: true   # check .memory.md created/updated
 */
object FlowTestRunner:

  private val logger = NebflowLogger(getClass)

  // ============================================================
  // Test spec model
  // ============================================================

  case class FlowTestSpec(flow: String, tests: List[FlowTestCase])

  case class FlowTestCase(
    name: String,
    input: String,
    llm: String, // "passing" | "failing" | "noVerdict"
    expect: FlowTestExpect
  )

  case class FlowTestExpect(
    outcome: String, // "completed" | "failed"
    memoryUpdated: Boolean
  )

  object FlowTestSpec:

    given Decoder[FlowTestSpec] = Decoder.instance { c =>
      for
        flow <- c.downField("flow").as[String]
        tests <- c.downField("tests").as[List[FlowTestCase]]
      yield FlowTestSpec(flow, tests)
    }

  object FlowTestCase:

    given Decoder[FlowTestCase] = Decoder.instance { c =>
      for
        name <- c.downField("name").as[Option[String]]
        input <- c.downField("input").as[Option[String]]
        llm <- c.downField("llm").as[Option[String]]
        expect <- c.downField("expect").as[FlowTestExpect]
      yield FlowTestCase(
        name.getOrElse("unnamed"),
        input.getOrElse(""),
        llm.getOrElse("passing"),
        expect
      )
    }

  object FlowTestExpect:

    given Decoder[FlowTestExpect] = Decoder.instance { c =>
      for
        outcome <- c.downField("outcome").as[Option[String]]
        memUpdated <- c.downField("memoryUpdated").as[Option[Boolean]]
      yield FlowTestExpect(outcome.getOrElse("completed"), memUpdated.getOrElse(false))
    }

  // ============================================================
  // Test result
  // ============================================================

  case class TestResult(specName: String, testName: String, passed: Boolean, detail: String)

  // ============================================================
  // Runner
  // ============================================================

  /** Load all .test.yaml specs from a directory. */
  def loadSpecs(dir: os.Path): IO[List[FlowTestSpec]] =
    IO.blocking {
      if !os.exists(dir) then Nil
      else
        os.list(dir)
          .filter(_.last.endsWith(".test.yaml"))
          .flatMap { file =>
            val yaml = os.read(file)
            yamlParser
              .parse(yaml)
              .toOption
              .flatMap(_.as[FlowTestSpec].toOption)
          }
          .toList
    }

  /** Run a single test spec through the full E2E stack. */
  def runSpec(spec: FlowTestSpec, flowYamls: Map[String, String]): IO[List[TestResult]] =
    val results = spec.tests.map { tc =>
      runTestCase(spec.flow, tc, flowYamls.getOrElse(spec.flow, ""))
    }
    results.sequence

  /** Run one test case end-to-end. */
  private def runTestCase(flowName: String, tc: FlowTestCase, flowYaml: String): IO[TestResult] =
    val fakeLlm = tc.llm match
      case "failing" => FakeLlm.failing
      case "noVerdict" => FakeLlm.noVerdict
      case _ => FakeLlm.passing

    Resource.eval(FlowTestKit.create(fakeLlm)).use { kit =>
      for
        _ <- kit.writeFlowYaml(flowName, flowYaml)
        parent <- kit.spawnParentAgent()
        treeRef <- kit.spawnTreeActor(parent)
        _ <- IO.sleep(500.millis)
        _ <- kit.mountFlow(treeRef, flowName)
        _ <- IO.sleep(500.millis)
        _ <- kit.writeMemory(flowName, "")
        // Create a Deferred that completes on Done/Failed (not Progress)
        doneDeferred <- Deferred[IO, PipelineActor.PipelineEvent]
        collectorDef: Behavior[PipelineActor.PipelineEvent] =
          def waiting: Behavior[PipelineActor.PipelineEvent] =
            Behaviors.receiveMessage {
              case done: PipelineActor.PipelineEvent.Done =>
                doneDeferred.complete(done).as(Behaviors.stopped)
              case failed: PipelineActor.PipelineEvent.Failed =>
                doneDeferred.complete(failed).as(Behaviors.stopped)
              case _ => IO.pure(waiting)
            }
          Behaviors.setup(_ => IO.pure(waiting))
        collector <- kit.actorSystem.spawn(
          collectorDef,
          s"collector-${java.util.UUID.randomUUID().toString.take(8)}"
        )
        // Trigger with collector as replyTo
        _ <- treeRef ! TreeCommand.TriggerPipeline(flowName, tc.input, Some(collector))
        // Wait for Done/Failed (or timeout)
        event <- IO.race(doneDeferred.get, IO.sleep(60.seconds)).flatMap {
          case Left(e) => IO.pure(e)
          case Right(_) => IO.raiseError(new RuntimeException("Pipeline timed out"))
        }
        // Check outcome based on event type
        outcomeOk = tc.expect.outcome match
          case "completed" => event.isInstanceOf[PipelineActor.PipelineEvent.Done]
          case "failed" => event.isInstanceOf[PipelineActor.PipelineEvent.Failed]
          case _ => true
        detail = event match
          case PipelineActor.PipelineEvent.Done(s) => s"completed: ${s.take(100)}"
          case PipelineActor.PipelineEvent.Failed(r) => s"failed: ${r.take(100)}"
          case PipelineActor.PipelineEvent.Progress(id, status, _) => s"progress: $id=$status"
        // Wait for reflect to write memory
        _ <- IO.sleep(3.seconds)
        memResult <-
          if !tc.expect.memoryUpdated then IO.pure(true)
          else kit.readMemory(flowName).map(_.nonEmpty)
      yield TestResult(
        flowName,
        tc.name,
        outcomeOk && memResult,
        s"$detail memory=${if memResult then "updated" else "empty"}"
      )
    }

  end runTestCase

  /** Run all specs from a directory and return combined results. */
  def runAll(flowsDir: os.Path): IO[List[TestResult]] =
    for
      specs <- loadSpecs(flowsDir)
      // Pre-load all flow YAML content before any temp dir setup
      flowYamls <- specs
        .traverse { spec =>
          IO.blocking {
            val file = flowsDir / s"${spec.flow}.yaml"
            spec.flow -> os.read(file)
          }
        }
        .map(_.toMap)
      _ <- logger.info(s"Loaded ${specs.size} test specs, ${flowYamls.size} flow definitions")
      results <- specs.traverse(spec => runSpec(spec, flowYamls))
    yield results.flatten

end FlowTestRunner
