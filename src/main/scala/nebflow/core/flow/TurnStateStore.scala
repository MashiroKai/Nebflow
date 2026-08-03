package nebflow.core.flow

import cats.effect.IO
import io.circe.*
import io.circe.syntax.*
import io.circe.parser.decode
import nebflow.core.{NebflowLogger, PathUtil}

/**
 * Durable turn-state marker for crash recovery.
 *
 * When a flow agent's turn is in progress, this file records the turn
 * metadata so that on restart (Ctrl+C / power loss) the turn can be
 * resumed by re-issuing the LLM call with the persisted message history.
 *
 * The message history itself (persisted by SessionStore at each ToolsComplete)
 * IS the checkpoint. This file only records "a turn is in progress" plus the
 * metadata needed to resume it correctly (turnStartMessageCount for the
 * expectsMail check, turnIdx for the tool-round counter).
 *
 * Storage: ~/.nebflow/sessions/<sessionId>/turn-state.json
 * Write: atomic (tmp + move), same pattern as FlowMailStore / MountedFlowStore.
 */
object TurnStateStore:
  private val logger = NebflowLogger.forName("nebflow.flow.turnstate")

  case class TurnState(
    inProgress: Boolean,
    turnStartMessageCount: Int,
    turnIdx: Int,
    startedAt: Long
  )

  given Encoder[TurnState] = Encoder.instance { ts =>
    Json.obj(
      "inProgress" -> ts.inProgress.asJson,
      "turnStartMessageCount" -> ts.turnStartMessageCount.asJson,
      "turnIdx" -> ts.turnIdx.asJson,
      "startedAt" -> ts.startedAt.asJson
    )
  }

  given Decoder[TurnState] = Decoder.instance { c =>
    for
      inProgress <- c.downField("inProgress").as[Boolean]
      turnStartMessageCount <- c.downField("turnStartMessageCount").as[Int]
      turnIdx <- c.downField("turnIdx").as[Int]
      startedAt <- c.downField("startedAt").as[Option[Long]]
    yield TurnState(inProgress, turnStartMessageCount, turnIdx, startedAt.getOrElse(0L))
  }

  private def sessionDir(sessionId: String): os.Path =
    PathUtil.dataRoot / "sessions" / sessionId

  private def turnStateFile(sessionId: String): os.Path =
    sessionDir(sessionId) / "turn-state.json"

  /** Save turn state atomically. No-op for empty sessionId. */
  def save(sessionId: String, state: TurnState): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else
      val file = turnStateFile(sessionId)
      val dir = file / os.up
      IO.blocking {
        if !os.exists(dir) then os.makeDir.all(dir)
        val tmp = dir / s"turn-state.json.tmp.${java.util.UUID.randomUUID()}"
        os.write.over(tmp, state.asJson.noSpaces)
        os.move.over(tmp, file, replaceExisting = true)
      }.void.handleErrorWith(e =>
        logger.warn(s"TurnStateStore.save failed for $sessionId: ${e.getMessage}").void
      )

  /** Load turn state. Returns None if not found or corrupt. */
  def load(sessionId: String): IO[Option[TurnState]] =
    if sessionId.isEmpty then IO.pure(None)
    else
      val file = turnStateFile(sessionId)
      IO.blocking {
        if !os.exists(file) then None
        else
          decode[TurnState](os.read(file)) match
            case Right(ts) => Some(ts)
            case Left(e) =>
              logger.warn(s"Failed to decode turn-state for $sessionId: ${e.getMessage}")
              None
      }

  /** Delete the turn-state file (turn completed or agent failed). */
  def clear(sessionId: String): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else
      val file = turnStateFile(sessionId)
      IO.blocking {
        if os.exists(file) then os.remove(file)
      }.void.handleErrorWith(e =>
        logger.warn(s"TurnStateStore.clear failed for $sessionId: ${e.getMessage}").void
      )

end TurnStateStore
