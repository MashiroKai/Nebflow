package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.flow.{FlowMembership, FlowVerifyRegistry, VerifyResult}
import nebflow.shared.{Message, MessageRole}

/**
 * Unified agent communication tool.
 *
 * Two modes:
 *   - type=message (default): Send a message to another agent by address.
 *   - type=verify: Report flow verification result. Auto-routes to the main agent.
 *     Replaces the old FlowVerify tool — one tool for all communication.
 */
object MailTool extends Tool:
  val name: String = "Mail"

  val description: String =
    """Unified communication tool for agent-to-agent and agent-to-main messaging.

Modes:
  type=message (default): Send a message to another agent by address.
    Required: address, message
    Optional: mode ("queue" or "immediate")

  type=verify: Report verification result from a flow verify step.
    Required: passed (boolean), summary (string, keep under 200 chars — key findings only)
    No address needed — auto-routes to the main agent.
    Only use this if you are a flow verify agent."""

  val inputSchema: JsonObject = JsonObject(
    "type" -> JsonObject(
      "type" -> "string".asJson,
      "enum" -> List("message", "verify").asJson,
      "description" -> "message: send to another agent. verify: report flow verification result.".asJson
    ).asJson,
    "address" -> JsonObject(
      "type" -> "string".asJson,
      "description" -> "(type=message) Recipient address, e.g. nebflow://local/delegate-Nebula-abc12345".asJson
    ).asJson,
    "message" -> JsonObject(
      "type" -> "string".asJson,
      "description" -> "(type=message) The message or instruction to send".asJson
    ).asJson,
    "passed" -> JsonObject(
      "type" -> "boolean".asJson,
      "description" -> "(type=verify) true if verification passed, false if issues found".asJson
    ).asJson,
    "summary" -> JsonObject(
      "type" -> "string".asJson,
      "description" -> "(type=verify) Concise result summary. Key findings only, no process details. Max 200 chars.".asJson
    ).asJson,
    "mode" -> JsonObject(
      "type" -> "string".asJson,
      "enum" -> List("queue", "immediate").asJson,
      "description" -> "(type=message) queue: message waits in mailbox (default). immediate: interrupts current work first.".asJson
    ).asJson
  )

  def summarize(input: JsonObject): String =
    input("type").flatMap(_.asString) match
      case Some("verify") =>
        val passed = input("passed").flatMap(_.asBoolean).getOrElse(false)
        s"Mail(verify: ${if passed then "PASS" else "FAIL"})"
      case _ =>
        val addr = input("address").flatMap(_.asString).getOrElse("?")
        val mode = input("mode").flatMap(_.asString).getOrElse("queue")
        s"Mail(→${addr.takeRight(20)}, $mode)"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val msgType = input("type").flatMap(_.asString).getOrElse("message")
    msgType match
      case "verify" => handleVerify(input, ctx)
      case _        => handleMessage(input, ctx)

  // ── type=verify: report flow verification result ──────────

  private def handleVerify(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val passedOpt = input("passed").flatMap(_.asBoolean)
    val summary = input("summary").flatMap(_.asString).getOrElse("")
    val agentPath = ctx.agentActorRef.map(_.path.toString).getOrElse("")

    passedOpt match
      case None =>
        IO.pure(Left(ToolError("Missing required parameter: passed (boolean)")))
      case Some(passed) =>
        if agentPath.isBlank then
          IO.pure(Left(ToolError("Cannot determine agent identity for verify report.")))
        else
          val concise = summary.take(200)
          for
            // 1. Complete Deferred — signals PipelineActor for scheduling (fix loop / complete)
            deferredCompleted <- FlowVerifyRegistry.complete(agentPath, VerifyResult(passed, concise))
            // 2. Notify Main Agent via ExternalEvent — replaces old ExternalEvent from PipelineActor
            _ <- ctx.parentRef match
              case Some(parent) =>
                    val flowName = ctx.sessionName.getOrElse("flow")
                    val status = if passed then "PASS" else "FAIL"
                    parent ! AgentCommand.ExternalEvent(
                      source = "flow",
                      eventType = if passed then "completed" else "verify-failed",
                      payload = s"[Flow: $flowName] $status\n$concise"
                    )
              case None => IO.unit
          yield
            if deferredCompleted then
              Right(if passed then "Verification PASSED." else s"Verification FAILED: $concise")
            else
              Left(ToolError("No pending flow verification for this agent. This type=verify is only for flow verify steps."))

  // ── type=message: standard agent-to-agent messaging ───────

  private def handleMessage(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
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
                system.resolve[AgentCommand](address).attempt.flatMap {
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
                    IO.pure(Left(ToolError(s"Failed to resolve address '$address': ${err.getMessage}")))
                }
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
