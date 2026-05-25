package nebflow.agent

import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}

/**
 * Unique mailbox address for a session.
 *
 * Format: "{agentName}/{sessionId}/{sessionName}"
 * Example: "Nebula/a296f1da/my-project"
 *
 * The sessionId is the primary key for routing.
 * agentName and sessionName are for human readability and identity display.
 */
case class AgentMailbox(
  agentName: String,
  sessionId: String,
  sessionName: String
):

  /** Canonical string representation — used as address by other agents. */
  def address: String = s"$agentName/$sessionId/$sessionName"

  /** Short display form for logs and UI. */
  def display: String = s"$agentName/${sessionName}/${sessionId.take(8)}"

object AgentMailbox:

  /** Parse from canonical address string. Returns None if format is invalid. */
  def parse(addr: String): Option[AgentMailbox] =
    val parts = addr.split("/", 3)
    if parts.length == 3 && parts(0).nonEmpty && parts(1).nonEmpty && parts(2).nonEmpty then
      Some(AgentMailbox(parts(0), parts(1), parts(2)))
    else None

  given Encoder[AgentMailbox] = Encoder.instance { m =>
    Json.obj(
      "agentName" -> m.agentName.asJson,
      "sessionId" -> m.sessionId.asJson,
      "sessionName" -> m.sessionName.asJson,
      "address" -> m.address.asJson
    )
  }

  given Decoder[AgentMailbox] = Decoder.instance { c =>
    for
      agentName <- c.downField("agentName").as[String]
      sessionId <- c.downField("sessionId").as[String]
      sessionName <- c.downField("sessionName").as[String]
    yield AgentMailbox(agentName, sessionId, sessionName)
  }

end AgentMailbox
