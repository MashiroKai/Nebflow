package nebflow.agent

import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.social.outbound.OutputBlock

/**
 * Nonterminal child progress; final answers continue exclusively through the result channel.
 *
 * ==Identity discipline (write-ahead, 2026-11-20)==
 *
 * A progress event's correlation id is the identity **reserved in the durable
 * journal** ([[ChildReportJournal]]), never the turn id: a redispatch of the same
 * logical slot reuses the persisted identity, so the parent's existing
 * `DeliveredReceiptLedger` dedup folds the duplicate instead of the user seeing the
 * same progress twice. This object is the single construction point for BOTH legs —
 * live ([[AgentFinishTurn.routeChildProgress]]) and supervisor crash recovery
 * ([[BackoffSupervisor]]) — so there is no second shape in the repo.
 */
private[agent] object ChildReport:

  /**
   * The text → body step: `continues=false` (a final message) never produces a
   * progress event — the final text is delivered only by the result channel. A
   * message without a report block, or with an unterminated/empty one, produces
   * nothing either (progress carries user-visible bodies only).
   */
  def body(text: String, continues: Boolean): Option[String] =
    if !continues then None
    else
      OutputBlock.extract(text) match
        case OutputBlock.Extracted(inner) if inner.nonEmpty => Some(inner)
        case _ => None

  /**
   * The one event construction point. `identity` is the durable reservation
   * (`ChildReportJournal.prepareReport` for the live leg, `pendingRecovery` for the
   * recovery leg) — a redispatched report therefore carries the ORIGINAL identity
   * and folds as a duplicate at the parent.
   */
  def event(identity: String, body: String, childName: String): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent(
      "child-report",
      "progress",
      body,
      JsonObject("agentName" -> childName.asJson),
      correlationId = Some(identity)
    )
end ChildReport
