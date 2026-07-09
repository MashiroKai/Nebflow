package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.agent.*
import nebflow.shared.{Message, MessageRole}

/**
 * General-purpose actor messaging tool.
 *
 * Sends a message to any agent by its actor address (e.g. nebflow://local/delegate-Nebula-abc12345).
 * Uses ActorSystem.resolve to look up the actor by path string, then sends UserInput.
 *
 * For persistent sub-agents, spawns a temporary reply adapter that forwards the
 * sub-agent's completion event back to the parent agent via ExternalEvent.
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
              // Spawn a temporary reply adapter so the sub-agent's completion
              // event is forwarded back to the parent agent via ExternalEvent.
              // Without this, replyTo=None means the result goes nowhere.
              val adapterName = s"mail-reply-${java.util.UUID.randomUUID().toString.take(8)}"
              for
                adapterRef <- system.spawn(
                  replyAdapter(address, ctx.agentActorRef),
                  adapterName
                )
                _ <-
                  if mode == "immediate" then
                    (ref ! AgentCommand.Interrupt()) *> (ref ! AgentCommand.UserInput(message, Some(adapterRef)))
                  else
                    ref ! AgentCommand.UserInput(message, Some(adapterRef))
              yield Right(s"Message sent to $address ($mode mode). The agent will process it in its mailbox.")
            case Left(err) =>
              IO.pure(Left(ToolError(s"Failed to resolve address '$address': ${err.getMessage}")))
          }
    end if
  end call

  /** Temporary adapter: forwards the sub-agent's completion event to the parent, then stops. */
  private def replyAdapter(
    address: String,
    parentRef: Option[ActorRef[AgentCommand]]
  ): Behavior[AgentEvent] =
    Behaviors.receiveMessage { (event: AgentEvent) =>
      val (eventType, payload) = event match
        case AgentEvent.Completed(_, messages) =>
          val text = extractLastAssistantText(messages)
          if text.nonEmpty then ("completed", s"[Session update] \"$address\":\n$text")
          else ("completed", s"[Session update] \"$address\" (task complete, awaiting instructions)")
        case AgentEvent.Failed(_, error) =>
          ("failed", s"[Session error] \"$address\": ${error.message}")

      val actions = parentRef match
        case Some(ref) =>
          (ref ! AgentCommand.ExternalEvent(
            source = address,
            eventType = eventType,
            payload = payload
          )) *> (ref ! AgentCommand.SessionUpdate(
            address,
            if eventType == "completed" then "idle (awaiting instructions)" else "failed"
          ))
        case None => IO.unit

      (actions *> IO.pure(Behaviors.stopped))
        .handleErrorWith(_ => IO.pure(Behaviors.stopped))
    }

  private def extractLastAssistantText(messages: List[Message]): String =
    messages.reverse
      .collectFirst {
        case msg if msg.role == MessageRole.Assistant => msg.textContent
      }
      .filter(_.nonEmpty)
      .getOrElse("")
end MailTool
