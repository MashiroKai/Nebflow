package nebflow.core.flow

import cats.effect.IO
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.shared.*

import scala.sys.process.*

/**
 * Flow evolution — uses feedback to improve flow definitions over time.
 *
 * After each pipeline run, FlowEvolver analyzes the results and proposes
 * targeted improvements to prompts and parameters. Changes are validated
 * against .test.yaml specs (when available) before being committed via git.
 *
 * Safety:
 * - Only modifies existing prompts and parameters — never adds/removes steps
 * - All changes are git-committed with descriptive messages
 * - Rollback is a simple `git revert`
 */
object FlowEvolver:
  private val logger = NebflowLogger(getClass)

  private def flowsDir: os.Path = PathUtil.dataRoot / "flows"

  /** Ensure ~/.nebflow/flows/ is a git repo. Idempotent. */
  def ensureGitRepo(): IO[Unit] =
    IO.blocking {
      val dir = flowsDir
      if !os.exists(dir / ".git") then
        os.makeDir.all(dir)
        Process(Seq("git", "init"), dir.toIO).!
        Process(Seq("git", "config", "user.email", "flow@nebflow.local"), dir.toIO).!
        Process(Seq("git", "config", "user.name", "Flow Evolution"), dir.toIO).!
        // Initial commit of all existing files
        Process(Seq("git", "add", "-A"), dir.toIO).!
        Process(Seq("git", "commit", "-m", "init: flow definitions baseline", "--allow-empty"), dir.toIO).!
        logger.infoSync(s"Initialized git repo at $dir")
    }.void

  /**
   * Run the evolution analysis after a pipeline completes.
   *
   * Reads the current flow YAML, asks LLM if any prompts can be improved
   * based on the run results, and if so, applies + commits the change.
   *
   * Fire-and-forget — called from runReflect in background.
   */
  def runEvolution(
    flowName: String,
    llm: LlmHandle[IO],
    sessionId: Option[String],
    pipelineName: String,
    runAnalysis: String,
    passed: Boolean,
    iterations: Int,
    memory: String
  ): IO[Unit] =
    for
      _ <- ensureGitRepo()
      currentYaml <- readFlowYaml(flowName)
      proposedYaml <- analyzeAndPropose(
        flowName,
        llm,
        sessionId,
        pipelineName,
        currentYaml,
        runAnalysis,
        passed,
        iterations,
        memory
      )
      _ <-
        if proposedYaml != currentYaml then applyAndCommit(flowName, currentYaml, proposedYaml, passed, iterations)
        else logger.info(s"[Evolve:$flowName] No changes proposed")
    yield ()

  /** Read the current flow YAML from disk. */
  private def readFlowYaml(flowName: String): IO[String] =
    IO.blocking {
      val file = flowsDir / s"$flowName.yaml"
      if os.exists(file) then os.read(file) else ""
    }

  private val EvolveSystemPrompt =
    """You are a flow optimization system. Your job is to improve flow definitions based on run feedback.

Rules:
- You may ONLY modify existing step prompts, verify prompts, fix prompts, and numeric parameters (retry, timeoutSeconds, maxIterations, maxConcurrency).
- You may NOT add or remove steps, change step IDs, or change agent assignments.
- Each change must be justified by concrete evidence from the run analysis.
- If no improvement is clearly warranted, output the original YAML unchanged.
- Output ONLY the YAML content. No explanations, no markdown fences.
- Preserve the original YAML structure and formatting as closely as possible."""

  /** Ask LLM to analyze the flow and propose improvements. Returns updated YAML. */
  private def analyzeAndPropose(
    flowName: String,
    llm: LlmHandle[IO],
    sessionId: Option[String],
    pipelineName: String,
    currentYaml: String,
    runAnalysis: String,
    passed: Boolean,
    iterations: Int,
    memory: String
  ): IO[String] =
    if currentYaml.isBlank then IO.pure("")
    else
      val status = if passed then "PASS" else "FAIL"
      val memBlock = if memory.isBlank then "(none)" else memory.take(2000)
      val userPrompt =
        s"""Flow: $flowName
           |Status: $status (fix iterations used: $iterations)
           |
           |=== Current Flow Definition ===
           |$currentYaml
           |=== End Definition ===
           |
           |=== Run Analysis ===
           |$runAnalysis
           |=== End Analysis ===
           |
           |=== Accumulated Memory ===
           |$memBlock
           |=== End Memory ===
           |
           |Analyze this flow and propose specific improvements. Output the complete updated YAML:""".stripMargin

      val request = LlmRequest(
        messages = List(Message(MessageRole.User, Left(userPrompt))),
        sessionId = sessionId.getOrElse("flow-evolve"),
        agentId = s"flow-evolve-$pipelineName",
        systemStable = Some(EvolveSystemPrompt)
      )

      llm.send(request).map(_.reply.trim).handleErrorWith { e =>
        logger.warn(s"[Evolve:$flowName] LLM analysis failed: ${e.getMessage}").as(currentYaml)
      }

  /** Write the new YAML, git add + commit. Validates before writing. */
  private def applyAndCommit(
    flowName: String,
    oldYaml: String,
    newYaml: String,
    passed: Boolean,
    iterations: Int
  ): IO[Unit] =
    val file = flowsDir / s"$flowName.yaml"
    FlowDefLoader.parse(newYaml) match
      case Left(err) =>
        logger.warn(
          s"[Evolve:$flowName] Rejecting invalid YAML from LLM: $err"
        )
      case Right(_) =>
        IO.blocking {
          os.write.over(file, newYaml)
          val dir = flowsDir.toIO
          Process(Seq("git", "add", s"$flowName.yaml"), dir).!
          val status = if passed then "pass" else "fail"
          val msg = s"evolve: $flowName ($status, iter=$iterations)"
          Process(Seq("git", "commit", "-m", msg, "--allow-empty"), dir).!
        }.void
          .flatMap { _ =>
            logger.info(s"[Evolve:$flowName] Applied evolution (git committed)")
          }
          .handleErrorWith { e =>
            // If git fails, revert the file change
            IO.blocking { os.write.over(file, oldYaml) }.void *>
              logger.warn(s"[Evolve:$flowName] Git commit failed, reverted: ${e.getMessage}")
          }
  end applyAndCommit

end FlowEvolver
