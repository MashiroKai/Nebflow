package nebflow.agent

import nebflow.actor.AgentCommand
import nebflow.social.outbound.OutputBlock
import io.circe.JsonObject
import io.circe.syntax.*

/** Nonterminal child progress; final answers continue exclusively through the result channel. */
private[agent] object ChildReport:
  def event(text: String, continues: Boolean, childName: String): Option[AgentCommand.ExternalEvent] =
    if !continues then None
    else OutputBlock.extract(text) match
      case OutputBlock.Extracted(body) if body.nonEmpty =>
        Some(AgentCommand.ExternalEvent("child-report", "progress", body,
          JsonObject("agentName" -> childName.asJson)))
      case _ => None
