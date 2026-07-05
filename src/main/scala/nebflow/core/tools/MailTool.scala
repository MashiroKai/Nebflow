package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.JsonObject
import nebflow.actor.*
import nebflow.agent.AgentCommand
import nebflow.core.tools.{Tool, ToolContext, ToolError}

/**
 * General-purpose actor messaging tool.
 *
 * Sends a message to any agent by its actor address (e.g. nebflow://local/delegate-Nebula-abc12345).
 * Uses ActorSystem.resolve to look up the actor by path string, then sends UserInput.
 *
 * Modes:
 *   - "queue" (default): message enters the recipient's mailbox, processed after current work
 *   - "immediate": sends Interrupt first, then UserInput — recipient handles it right away
 */
object MailTool extends Tool:
  val name: String = "Mail"

  val description: String =
    "Send a message to an agent by its actor address. The address format is nebflow://device/name (e.g. nebflow://local/delegate-Nebula-abc12345). Use this to send follow-up instructions to persistent sub-agents listed in your Active Sessions. The message will be delivered to the agent's mailbox."

  val inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> JsonObject(
      "address" -> JsonObject(
        "type" -> "string".asJson,
        "description" -> "The actor address (e.g. nebflow://local/delegate-Nebula-abc12345)".asJson
      ).asJson,
      "message" -> JsonObject(
        "type" -> "string".asJson,
        "description" -> "The message or instruction to send".asJson
      ).asJson,
      "mode" -> JsonObject(
        "type" -> "string".asJson,
        "enum" -> List("queue", "immediate").asJson,
        "description" -> "queue: message waits in mailbox (default). immediate: interrupts current work first.".asJson
      ).asJson
    ).asJson,
    "required" -> List("address", "message").asJson
  )

  def summarize(input: JsonObject): String =
    val addr = input("address").flatMap(_.asString).getOrElse("?")
    val mode = input("mode").flatMap(_.asString).getOrElse("queue")
    s"Mail(→${addr.takeRight(20)}, $mode)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val address = input("address").flatMap(_.asString).getOrElse("")
    val message = input("message").flatMap(_.asString).getOrElse("")
    val mode = input("mode").flatMap(_.asString).getOrElse("queue")

    if address.isEmpty then IO.pure(Left(ToolError("Missing required parameter: address")))
    else if message.isEmpty then IO.pure(Left(ToolError("Missing required parameter: message")))
    else
      ctx.actorSystem match
        case None =>
          IO.pure(Left(ToolError("No actor system available")))
        case Some(system) =>
          system.resolve[AgentCommand](address).attempt.flatMap {
            case Right(ref) =>
              val sendIO =
                if mode == "immediate" then (ref ! AgentCommand.Interrupt()) *> (ref ! AgentCommand.UserInput(message))
                else ref ! AgentCommand.UserInput(message)
              sendIO.as(Right(s"Message sent to $address ($mode mode). The agent will process it in its mailbox."))
            case Left(err) =>
              IO.pure(Left(ToolError(s"Failed to resolve address '$address': ${err.getMessage}")))
          }
    end if
  end call
end MailTool
