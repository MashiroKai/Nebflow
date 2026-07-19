package nebflow.core.flow

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json, parser}
import nebflow.core.{NebflowLogger, PathUtil}

/**
 * Persists per-pipeline runtime state for frontend status queries.
 *
 * Each pipeline writes a snapshot on every state change.
 * The REST API reads these files to return current state.
 *
 * Storage: ~/.nebflow/sessions/<sessionId>/pipeline-state/<pipelineName>.json
 */
object PipelineStateStore:
  private val logger = NebflowLogger(getClass)

  case class StepInfo(
    id: String,
    agent: String,
    status: String,
    dependsOn: List[String] = Nil
  )

  case class PipelineState(
    name: String,
    flowName: String,
    phase: String,
    steps: List[StepInfo] = Nil,
    results: Map[String, String] = Map.empty,
    iteration: Int = 0,
    verifyResult: Option[String] = None,
    updatedAt: Long = System.currentTimeMillis()
  )

  given Encoder[StepInfo] = Encoder.instance { s =>
    Json.obj(
      "id" -> s.id.asJson,
      "agent" -> s.agent.asJson,
      "status" -> s.status.asJson,
      "dependsOn" -> s.dependsOn.asJson
    )
  }

  given Decoder[StepInfo] = Decoder.instance { c =>
    for
      id <- c.downField("id").as[String]
      agent <- c.downField("agent").as[String]
      status <- c.downField("status").as[String]
      deps <- c.downField("dependsOn").as[Option[List[String]]]
    yield StepInfo(id, agent, status, deps.getOrElse(Nil))
  }

  given Encoder[PipelineState] = Encoder.instance { s =>
    Json.obj(
      "name" -> s.name.asJson,
      "flowName" -> s.flowName.asJson,
      "phase" -> s.phase.asJson,
      "steps" -> s.steps.asJson,
      "results" -> s.results.asJson,
      "iteration" -> s.iteration.asJson,
      "verifyResult" -> s.verifyResult.asJson,
      "updatedAt" -> s.updatedAt.asJson
    )
  }

  given Decoder[PipelineState] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      flowName <- c.downField("flowName").as[String]
      phase <- c.downField("phase").as[String]
      steps <- c.downField("steps").as[Option[List[StepInfo]]]
      results <- c.downField("results").as[Option[Map[String, String]]]
      iteration <- c.downField("iteration").as[Option[Int]]
      verifyResult <- c.downField("verifyResult").as[Option[String]]
      updatedAt <- c.downField("updatedAt").as[Option[Long]]
    yield PipelineState(
      name, flowName, phase,
      steps.getOrElse(Nil),
      results.getOrElse(Map.empty),
      iteration.getOrElse(0),
      verifyResult,
      updatedAt.getOrElse(0L)
    )
  }

  private def stateDir(sessionId: String): os.Path =
    PathUtil.dataRoot / "sessions" / sessionId / "pipeline-state"

  private def stateFile(sessionId: String, pipelineName: String): os.Path =
    stateDir(sessionId) / s"$pipelineName.json"

  /** Save a pipeline state snapshot (atomic write). */
  def save(sessionId: String, state: PipelineState): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else
      val dir = stateDir(sessionId)
      val file = stateFile(sessionId, state.name)
      val tmp = dir / s"${state.name}.json.tmp"
      IO.blocking {
        if !os.exists(dir) then os.makeDir.all(dir)
        os.write.over(tmp, state.asJson.noSpaces)
        os.move.over(tmp, file, replaceExisting = true)
      }.void

  /** Delete a pipeline's state file (on unmount). */
  def delete(sessionId: String, pipelineName: String): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else
      val file = stateFile(sessionId, pipelineName)
      IO.blocking {
        if os.exists(file) then os.remove(file)
      }.void

  /** Load all pipeline states for a session. */
  def loadAll(sessionId: String): IO[List[PipelineState]] =
    val dir = stateDir(sessionId)
    IO.blocking {
      if !os.exists(dir) then Nil
      else
        os.list(dir).toArray.toList
          .filter(_.last.endsWith(".json"))
          .flatMap { file =>
            val content = os.read(file)
            parser.decode[PipelineState](content) match
              case Right(state) => Some(state)
              case Left(e) =>
                logger.warn(s"Failed to decode pipeline state ${file.last}: ${e.getMessage}")
                None
          }
    }

  /** Delete all state files for a session. */
  def deleteAll(sessionId: String): IO[Unit] =
    val dir = stateDir(sessionId)
    IO.blocking {
      if os.exists(dir) then os.remove.all(dir)
    }.void

end PipelineStateStore
