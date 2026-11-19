package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.{AgentState, recentMessageIds, withRecentMessageIds}

/**
 * Session management helpers extracted from AgentActor.
 * Handles dedup, sessionBusy signaling.
 */
private[agent] trait AgentSession:

  /**
   * Check for duplicate clientMessageId and update recentMessageIds.
   *  Returns (isDuplicate, updatedState).
   */
  private[agent] def checkDuplicate(
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

  /**
   * Duplicate-drop receipt (resend-dedup 批 2026-10-20, 修复批 #569): a single
   * construction point for the frame the gateway sends back when a user input
   * is dropped because its clientMessageId was already accepted. The client
   * contract (main.js onMessage('messageDuplicate')): the message HAS been
   * received before ⇒ clear the phantom busy/watchdog the same-id resend
   * armed; no second turn will run. Old frontends have no handler registered
   * for the type ⇒ dispatched to an empty handler list = no-op (protocol
   * additive, zero break). Emitted best-effort: a send failure must never
   * block the drop itself.
   */
  private[agent] def emitDuplicateReceipt(
    wsSend: Json => IO[Unit],
    sessionId: Option[String],
    clientMessageId: Option[String]
  ): IO[Unit] =
    sessionId.traverse_(sid =>
      wsSend(
        Json.obj(
          "type" -> "messageDuplicate".asJson,
          "sessionId" -> sid.asJson,
          "clientMessageId" -> clientMessageId.asJson
        )
      )
    )

  /** Emit a sessionBusy event to the frontend. */
  private[agent] def emitSessionBusy(
    wsSend: Json => IO[Unit],
    sessionId: String,
    busy: Boolean
  ): IO[Unit] =
    wsSend(
      Json.obj(
        "type" -> "sessionBusy".asJson,
        "sessionId" -> sessionId.asJson,
        "busy" -> busy.asJson
      )
    )

end AgentSession
