package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.flow.{FlowMailTracker, FlowMembership}
import nebflow.shared.{Message, MessageRole}

/**
 * Agent-to-agent communication tool.
 *
 * Sends a message to another agent by address. In flow pipelines, the pattern
 * of Mail sends implicitly determines control flow:
 *   - Verify agent sends Mail to fix agent → triggers retry
 *   - Verify agent completes without Mail to fix agent → pass
 *   - Mail to the manager → summary notification
 */
object MailTool extends Tool:
  val name: String = "Mail"

  val description: String =
    """Send a message to another agent by address.

Required: address, message
Optional: mode ("queue" or "immediate")

In flow pipelines, Mail patterns implicitly control execution:
  - Verify agent sends Mail to fix agent with feedback → triggers retry
  - Verify agent completes without Mail to fix agent → pass"""

  // NOTE: input_schema must be a JSON-Schema *object* (top-level "type": "object" +
  // "properties"). A flat layout puts a property named "type" at the top level,
  // which Anthropic/DeepSeek reject with "not valid under anyOf".
  val inputSchema: JsonObject = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "address" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Recipient address, e.g. nebflow://local/delegate-Nebula-abc12345".asJson
        ),
        "message" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "The message or instruction to send".asJson
        ),
        "mode" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "enum" -> List("queue", "immediate").asJson,
          "description" -> "queue: message waits in mailbox (default). immediate: interrupts current work first.".asJson
        )
      ),
      "required" -> io.circe.Json.arr("address".asJson, "message".asJson)
    )
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
          val senderPath = ctx.agentActorRef.map(_.path.toString).getOrElse("")
          for
            allowed <- FlowMembership.canCommunicate(senderPath, address)
            result <-
              if !allowed then
                IO.pure(
                  Left(
                    ToolError(
                      s"Cannot send mail to $address: recipient is in a different flow. " +
                        "Mail is only allowed between agents in the same flow."
                    )
                  )
                )
              else
                for
                  // Record the send BEFORE attempting delivery — PipelineActor uses
                  // this to determine verify pass/fail even if delivery fails.
                  _ <- FlowMailTracker.record(senderPath, address)
                  r <- system.resolve[AgentCommand](address).attempt.flatMap {
                    case Right(ref) =>
                      val adapterName = s"mail-reply-${java.util.UUID.randomUUID().toString.take(8)}"
                      for
                        adapterRef <- system.spawn(
                          replyAdapter(address, ctx.agentActorRef),
                          adapterName
                        )
                        _ <-
                          if mode == "immediate" then
                            (ref ! AgentCommand.Interrupt()) *> (ref ! AgentCommand.UserInput(message, Some(adapterRef)))
                          else ref ! AgentCommand.UserInput(message, Some(adapterRef))
                      yield Right(s"Message sent to $address ($mode mode). The agent will process it in its mailbox.")
                    case Left(err) =>
                      // Delivery failed, but tracking was recorded. For flow agents,
                      // the pipeline will still detect the send and trigger retry.
                      // Return a soft success for flow senders, error for standalone.
                      FlowMembership.flowsOf(senderPath).flatMap { flows =>
                        if flows.nonEmpty then
                          IO.pure(Right(s"Message recorded for $address."))
                        else
                          IO.pure(Left(ToolError(s"Failed to resolve address '$address': ${err.getMessage}")))
                      }
                  }
                yield r
          yield result
          end for
    end if

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
