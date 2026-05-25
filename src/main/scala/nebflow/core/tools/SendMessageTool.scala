package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.agent.{AgentCommand, AgentMailbox}

/**
 * SendMessage tool — enables inter-agent communication via PostOffice.
 *
 * An agent can send a message to another agent by specifying the target's
 * mailbox address. The message is delivered as an ExternalEvent to the
 * target session.
 */
object SendMessageTool extends Tool:
  val name: String = "SendMessage"

  val description: String =
    """Send a message to another agent session via its mailbox address.
      |
      |The target address format is: "agentName/sessionId/sessionName"
      |You can use ListMailboxes to discover available agents and their addresses.
      |
      |The message will be delivered as a system event to the target agent.
      |If the target agent is busy, the message is queued and processed after the current turn.""".stripMargin

  val inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "target" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Target mailbox address (format: agentName/sessionId/sessionName)".asJson
      ),
      "message" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "The message content to send".asJson
      )
    ),
    "required" -> List("target", "message").asJson
  )

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val targetOpt = input("target").flatMap(_.asString)
    val messageOpt = input("message").flatMap(_.asString)

    (targetOpt, messageOpt) match
      case (Some(target), Some(message)) =>
        AgentMailbox.parse(target) match
          case Some(mailbox) =>
            val po = ctx.postOffice
            po.findBySessionId(mailbox.sessionId).flatMap {
              case Some((targetMailbox, _)) =>
                // Build sender identity
                val senderAgent = ctx.agentDef.map(_.name).getOrElse("unknown")
                val senderSessionId = ctx.sessionId.getOrElse("")
                val senderSessionName = ctx.sessionName.getOrElse("")
                val senderMailbox = AgentMailbox(senderAgent, senderSessionId, senderSessionName)

                po.deliver(
                  targetMailbox.sessionId,
                  AgentCommand.AgentMessageReceived(senderMailbox, message)
                ).map {
                  case Right(_) =>
                    Right(s"Message delivered to ${targetMailbox.display}")
                  case Left(err) =>
                    Left(ToolError(err))
                }
              case None =>
                IO.pure(Left(ToolError(s"Target agent not found: ${mailbox.display}. Use ListMailboxes to discover available agents.")))
            }
          case None =>
            IO.pure(Left(ToolError(s"Invalid mailbox address format: $target. Expected format: agentName/sessionId/sessionName")))
      case _ =>
        IO.pure(Left(ToolError("Missing required parameters: target and message")))

  def summarize(input: JsonObject): String =
    val target = input("target").flatMap(_.asString).getOrElse("?")
    val msg = input("message").flatMap(_.asString).getOrElse("")
    val preview = if msg.length > 60 then s"${msg.take(60)}..." else msg
    s"SendMessage to $target: $preview"

  def summarizeResult(input: JsonObject, result: String): String = result
end SendMessageTool

/**
 * ListMailboxes tool — discover available agent sessions.
 */
object ListMailboxesTool extends Tool:
  val name: String = "ListMailboxes"

  val description: String =
    """List all registered agent mailboxes.
      |
      |Returns a list of available agents you can send messages to via SendMessage.
      |Each entry shows the agent name, session name, and mailbox address.""".stripMargin

  val inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "filter" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Optional filter by agent name or session name (case-insensitive substring match)".asJson
      )
    )
  )

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val filterOpt = input("filter").flatMap(_.asString).map(_.toLowerCase)
    val po = ctx.postOffice
    po.listAll.map { mailboxes =>
      val filtered = filterOpt match
        case Some(f) =>
          mailboxes.filter(m =>
            m.agentName.toLowerCase.contains(f) ||
            m.sessionName.toLowerCase.contains(f)
          )
        case None => mailboxes

      if filtered.isEmpty then
        Right("No agents found." + filterOpt.map(f => s" (filter: '$f')").getOrElse(""))
      else
        val lines = filtered.map { m =>
          s"- ${m.agentName} / ${m.sessionName} → ${m.address}"
        }
        Right(s"Available agents (${filtered.size}):\n${lines.mkString("\n")}")
    }

  def summarize(input: JsonObject): String =
    val filter = input("filter").flatMap(_.asString).map(f => s" (filter: $f)").getOrElse("")
    s"ListMailboxes$filter"

  def summarizeResult(input: JsonObject, result: String): String = result
end ListMailboxesTool
