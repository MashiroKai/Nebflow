package nebflow.agent

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*

/**
 * Session management helpers extracted from AgentActor.
 * Handles dedup, sessionBusy signaling.
 */
private[agent] trait AgentSession:

  /** Check for duplicate clientMessageId and update recentMessageIds.
   *  Returns (isDuplicate, updatedState).
   */
  protected def checkDuplicate(
    clientMessageId: Option[String],
    state: AgentState
  ): (Boolean, AgentState) =
    clientMessageId match
      case Some(id) if state.recentMessageIds.contains(id) =>
        (true, state)
      case _ =>
        val newIds = clientMessageId match
          case Some(id) => (state.recentMessageIds :+ id).takeRight(100)
          case None => state.recentMessageIds
        (false, state.withRecentMessageIds(newIds))

  /** Emit a sessionBusy event to the frontend. */
  protected def emitSessionBusy(
    wsSend: Json => IO[Unit],
    sessionId: String,
    busy: Boolean
  ): IO[Unit] =
    wsSend(Json.obj(
      "type" -> "sessionBusy".asJson,
      "sessionId" -> sessionId.asJson,
      "busy" -> busy.asJson
    ))

end AgentSession
