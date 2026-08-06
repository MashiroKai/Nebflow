package nebflow.core.tools

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.flow.{FlowAgentActivator, FlowMailStore, FlowMembership}
import nebflow.shared.{Message, MessageRole}

import scala.concurrent.duration.*

/**
 * Agent-to-agent communication tool.
 *
 * Two modes:
 * - Default (fork=false): Send a message to another agent. Async — recipient
 *   processes on next turn, sender doesn't wait. The standard communication method.
 * - fork=true: Fork the target agent's context (load their conversation history),
 *   ask the question, and return the response immediately. The target's main task
 *   is NOT interrupted. Use for progress queries, quick questions, supplemental info.
 */
object MailTool extends Tool:
  private val logger = NebflowLogger(getClass)

  val name: String = "Mail"

  val description: String =
    """Send a message to an agent or trigger a flow pipeline.

Required: address, message

The address can be:
- A team name (e.g. "nebflow-project") — forwards the message to the team's lead agent.
- A team lead or member name (e.g. "nebflow-manager", "backend") — sends a message to that agent.
- A flow name (e.g. "code-review", "entity-creator") — triggers a one-shot pipeline execution with the message as the task.
- A standalone agent name (not in any team/flow) — spawns the agent ephemerally, runs the task, and delivers the result via Mail when complete.

Default mode (fork omitted or false):
  Async send. If the recipient is idle, delivered immediately. If busy, queued
  and injected at the next turn boundary. You don't wait for a response.
  For flows: the pipeline runs in the background, result delivered via Mail when complete.

Fork mode (fork: true):
  Forks the target agent's context — loads their conversation history into a
  temporary instance, asks the question, and returns the answer immediately.
  The agent's main task is NOT interrupted. Use for progress queries and
  quick questions when you need an immediate answer without disrupting workflow.
  Does not apply to flows.

Message type (optional, default "INFO"):
  Every Mail has a TYPE tag. Check the TYPE before acting — it tells you how to handle the Mail:

  1. **[INFO]** — supplementary context for your current task. Keep working. Incorporate silently.
  2. **[FOLLOW_UP]** — additional task to start AFTER your current one finishes. Finish current work first. Then start the new task.
  3. **[PARALLEL]** — independent work that doesn't depend on your current task. Delegate it in parallel. Keep going.
  4. **[INTERRUPT]** — urgent, requires immediate attention. Pause current work and handle this now.
  5. **[RESULT]** — work results or status report from another agent. Acknowledge if needed. Continue your own work unless this changes your task.

  Default: Mail supplements your work, not replaces it. Switch tasks only on [INTERRUPT] or when your current task is complete."""

  val inputSchema: JsonObject = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "address" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Recipient: a team name (e.g. \"nebflow-project\"), agent name (e.g. \"backend\"), or flow name (e.g. \"code-review\").".asJson
        ),
        "message" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "The message or question to send".asJson
        ),
        "fork" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "If true, fork the target's context and get an immediate response without interrupting their main task. Default: false.".asJson,
          "default" -> false.asJson
        ),
        "type" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("INFO".asJson, "FOLLOW_UP".asJson, "PARALLEL".asJson, "INTERRUPT".asJson, "RESULT".asJson),
          "description" -> """Message type tag. "INFO" = supplementary context (default); "FOLLOW_UP" = new task after current finishes; "PARALLEL" = delegate independently; "INTERRUPT" = urgent, handle now; "RESULT" = work results from another agent.""".asJson,
          "default" -> "INFO".asJson
        )
      ),
      "required" -> Json.arr("address".asJson, "message".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val addr = input("address").flatMap(_.asString).getOrElse("?")
    val fork = input("fork").flatMap(_.asBoolean).getOrElse(false)
    val mailType = input("type").flatMap(_.asString).getOrElse("INFO")
    val typeStr = if mailType != "INFO" then s" [$mailType]" else ""
    if fork then s"Mail(→$addr, fork)$typeStr" else s"Mail(→$addr)$typeStr"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val address = input("address").flatMap(_.asString).getOrElse("")
    val message = input("message").flatMap(_.asString).getOrElse("")
    val fork = input("fork").flatMap(_.asBoolean).getOrElse(false)
    val mailType = input("type").flatMap(_.asString).getOrElse("INFO")

    if address.isEmpty then IO.pure(Left(ToolError("Missing required parameter: address")))
    else if message.isEmpty then IO.pure(Left(ToolError("Missing required parameter: message")))
    else if fork then forkAndAsk(address, message, ctx)
    else
      ctx.actorSystem match
        case None =>
          IO.pure(Left(ToolError("No actor system available")))
        case Some(system) =>
          if address.contains("://") then deliverToAddress(address, message, mailType, ctx, system)
          else deliverToShortName(address, message, mailType, ctx, system)
  end call

  // ============================================================
  // Fork mode: load target's history, spawn temp agent, return response
  // ============================================================

  private def forkAndAsk(
    address: String,
    question: String,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    (ctx.actorSystem, ctx.sharedResources, ctx.sessionId) match
      case (Some(system), Some(resources), Some(senderSid)) =>
        for
          targetSidOpt <- FlowMembership.resolveSessionId(senderSid, address)
          result <- targetSidOpt match
            case None =>
              IO.pure(
                Left(
                  ToolError(
                    s"Agent '$address' not found. Use the agent names from your Team context."
                  )
                )
              )
            case Some(targetSid) =>
              FlowMembership.flowOfSession(targetSid).flatMap {
                case Some(flowName) =>
                  FlowAgentActivator.resolveAgentFromDisk(flowName, address, resources).flatMap {
                    case Some(agentDef) =>
                      doFork(system, resources, agentDef, Some(targetSid), question, ctx)
                    case None =>
                      IO.pure(Left(ToolError(s"Agent '$address' definition not found.")))
                  }
                case None =>
                  IO.pure(Left(ToolError(s"Agent '$address' has no team association.")))
              }
        yield result
      case _ =>
        IO.pure(Left(ToolError("Fork mode requires actor system, shared resources, and session context.")))

  private def doFork(
    system: ActorSystem,
    resources: SharedResources,
    agentDef: AgentDef,
    agentSessionId: Option[String],
    question: String,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    for
      // Load the agent's conversation history (fork context)
      history <- agentSessionId match
        case Some(sid) => resources.sessionStore.loadMessagesForSession(sid)
        case None => IO.pure(List.empty[Message])

      // Create a temporary session
      tempSession <- resources.sessionStore
        .createSession(s"fork-${java.util.UUID.randomUUID().toString.take(8)}", agentName = Some(agentDef.name))
        .handleErrorWith(e =>
          logger.warn(s"Mail fork: createSession failed: ${e.getMessage}") *>
            resources.sessionStore.createSession(s"fork-fallback", agentName = Some(agentDef.name))
        )

      responseDeferred <- Deferred[IO, Either[String, String]]

      adapterRef <- system.spawn(
        forkAdapter(agentDef.name, responseDeferred),
        s"fork-adapter-${java.util.UUID.randomUUID().toString.take(8)}"
      )

      readTracker <- ReadTracker.create
      fileHistory <- FileHistory.create()
      forkWs = (_: Json) => IO.unit // suppress WS events for forked agents

      agentRef <- system.spawn(
        AgentActor(
          agentDef = agentDef,
          resources = resources,
          wsSend = forkWs,
          depth = (ctx.depth + 1),
          parentRef = None,
          sessionId = Some(tempSession.id),
          sessionName = Some(s"fork/${agentDef.name}"),
          initialMessages = history,
          readTracker = Some(readTracker),
          fileHistory = Some(fileHistory),
          contextWindow = resources.contextWindow,
          projectRoot = Some(ctx.projectRoot),
          safetyMode = "bypass",
          expectsMail = false
        ),
        s"fork-${agentDef.name}-${java.util.UUID.randomUUID().toString.take(8)}"
      )

      _ <- agentRef ! AgentCommand.UserInput(question, Some(adapterRef))

      result <- responseDeferred.get
        .timeoutTo(120.seconds, IO.pure(Left[String, String]("Fork timed out after 120s")))
        .flatMap {
          case Right(text) =>
            for
              _ <- system.stop(agentRef).handleErrorWith(_ => IO.unit)
              _ <- system.stop(adapterRef).handleErrorWith(_ => IO.unit)
              _ <- resources.sessionStore.deleteSession(tempSession.id).handleErrorWith(_ => IO.unit)
            yield Right(text)
          case Left(err) =>
            for
              _ <- system.stop(agentRef).handleErrorWith(_ => IO.unit)
              _ <- system.stop(adapterRef).handleErrorWith(_ => IO.unit)
              _ <- resources.sessionStore.deleteSession(tempSession.id).handleErrorWith(_ => IO.unit)
            yield Left(ToolError(err))
        }
    yield result

  private def forkAdapter(
    agentName: String,
    responseDeferred: Deferred[IO, Either[String, String]]
  ): Behavior[AgentEvent] =
    Behaviors.receiveMessage { (event: AgentEvent) =>
      val result = event match
        case AgentEvent.Completed(_, messages) =>
          val text = messages.reverse
            .collectFirst {
              case msg if msg.role == MessageRole.Assistant => msg.textContent
            }
            .filter(_.nonEmpty)
            .getOrElse(s"$agentName completed but produced no text output.")
          Right(text)
        case AgentEvent.Failed(_, error) =>
          Left(s"$agentName failed: ${error.message}")
      (responseDeferred
        .complete(result)
        .as(Behaviors.stopped))
        .handleErrorWith(_ => IO.pure(Behaviors.stopped))
    }

  // ============================================================
  // Normal mode: async delivery
  // ============================================================

  /** Deliver to a full address (nebflow://...) — legacy path. */
  private def deliverToAddress(
    address: String,
    message: String,
    mailType: String,
    ctx: ToolContext,
    system: ActorSystem
  ): IO[Either[ToolError, String]] =
    system.resolve[AgentCommand](address).attempt.flatMap {
      case Right(ref) => sendMail(ref, address, message, mailType, ctx, system)
      case Left(err) => IO.pure(Left(ToolError(s"Failed to resolve address '$address': ${err.getMessage}")))
    }

  /**
   * Deliver to a short name — resolves team name, agent name, or flow name.
   *  Applies team scope check before delivery.
   */
  private def deliverToShortName(
    address: String,
    message: String,
    mailType: String,
    ctx: ToolContext,
    system: ActorSystem
  ): IO[Either[ToolError, String]] =
    val senderSessionId = ctx.sessionId.getOrElse("")
    val senderName = ctx.agentDef.map(_.name).getOrElse("")

    FlowMembership.flowOfSession(senderSessionId).flatMap {
      case None =>
        // Not in sessionFlows — could be Nebula, DAG agent, or unregistered team agent.
        // Safety net: block "Nebula" access for any non-Nebula sender that isn't a confirmed manager.
        if address == "Nebula" && senderName != "Nebula" then
          FlowMembership.isManager(senderSessionId).flatMap { isMgr =>
            if isMgr then deliverShortNameUnscoped(address, message, mailType, ctx, system, senderSessionId)
            else
              IO.pure(
                Left(
                  ToolError(
                    "Cannot mail Nebula directly. You are a team worker. Report to your Manager via Mail."
                  )
                )
              )
          }
        else deliverShortNameUnscoped(address, message, mailType, ctx, system, senderSessionId)
      case Some(flowId) =>
        // Confirmed team agent — full scope check
        checkTeamScope(address, flowId, senderSessionId).flatMap {
          case Some(error) => IO.pure(Left(ToolError(error)))
          case None =>
            deliverShortNameUnscoped(address, message, mailType, ctx, system, senderSessionId)
        }
    }

  end deliverToShortName

  /** Actual delivery logic without scope checks. */
  private def deliverShortNameUnscoped(
    address: String,
    message: String,
    mailType: String,
    ctx: ToolContext,
    system: ActorSystem,
    senderSessionId: String
  ): IO[Either[ToolError, String]] =
    for
      // 1. Check if it's a team name → forward to lead
      teamOpt <- nebflow.core.entity.EntityLoader.loadTeam(address)
      result <- teamOpt match
        case Some(team) =>
          for
            leadSidOpt <- FlowMembership.resolveTeamAgent(address, team.lead)
            r <- leadSidOpt match
              case Some(targetSid) =>
                for
                  res <- deliverToSession(targetSid, team.lead, message, mailType, ctx, system)
                  _ <- res match
                    case Right(_) => onMailDelivered(senderSessionId, targetSid, team.lead, message, ctx)
                    case Left(_) => IO.unit
                yield res
              case None =>
                IO.pure(
                  Left(
                    ToolError(
                      s"Team '$address' is not mounted. Use Load(type: \"team\", name: \"$address\") first."
                    )
                  )
                )
          yield r

        case None =>
          // 2. Check if it's a flow name → trigger one-shot DAG pipeline.
          //    This MUST come before resolveSessionId to avoid team flow aliases
          //    (e.g. "code-review" alias in nebflow-project) intercepting the trigger.
          for
            flowOpt <- nebflow.core.entity.EntityLoader.loadFlow(address)
            r <- flowOpt match
              case Some(flowDef) =>
                (ctx.sharedResources, ctx.actorSystem, ctx.agentActorRef) match
                  case (Some(resources), Some(sys), Some(callerRef)) =>
                    for
                      runnerRef <- sys.spawn(
                        nebflow.core.flow.FlowDagRunner(resources, ctx.wsSend),
                        s"dag-runner-${address.take(10)}-${System.currentTimeMillis().toString.takeRight(6)}"
                      )
                      _ <- (runnerRef ! nebflow.core.flow.FlowDagRunner.RunFlow(flowDef, message, callerRef)).void
                    yield Right(s"Flow '$address' started. Result will be delivered via Mail when complete.")
                  case _ =>
                    IO.pure(Left(ToolError(s"Cannot start flow '$address': missing resources")))
              case None =>
                // 3. Check FlowMembership (agent short name)
                for
                  sidOpt <- FlowMembership.resolveSessionId(senderSessionId, address)
                  sr <- sidOpt match
                    case Some(targetSid) =>
                      for
                        res <- deliverToSession(targetSid, address, message, mailType, ctx, system)
                        _ <- res match
                          case Right(_) => onMailDelivered(senderSessionId, targetSid, address, message, ctx)
                          case Left(_) => IO.unit
                      yield res
                    case None =>
                      // 4. Check if it's a standalone agent definition → spawn ephemeral runner
                      for
                        agentDefOpt <- ctx.agentLibrary match
                          case Some(lib) => lib.get(address)
                          case None => IO.pure(None)
                        agentRes <- agentDefOpt match
                          case Some(agentDef) if agentDef.category == "standalone" && address != "Nebula" =>
                            (ctx.sharedResources, ctx.actorSystem, ctx.agentActorRef) match
                              case (Some(resources), Some(sys), Some(callerRef)) =>
                                for
                                  runnerRef <- sys.spawn(
                                    nebflow.core.flow.EphemeralAgentRunner(resources, ctx.wsSend),
                                    s"ephemeral-runner-${address.take(10)}-${System.currentTimeMillis().toString.takeRight(6)}"
                                  )
                                  _ = runnerRef ! nebflow.core.flow.EphemeralAgentRunner.RunAgent(
                                    agentDef,
                                    message,
                                    callerRef,
                                    depth = ctx.depth + 1,
                                    projectRoot = ctx.projectRoot
                                  )
                                yield Right(s"Agent '$address' activated. Result will be delivered when complete.")
                              case _ =>
                                IO.pure(Left(ToolError(s"Cannot activate agent '$address': missing resources")))
                          case Some(_) if address == "Nebula" =>
                            // Nebula is the main orchestrator — deliver to parent session if possible
                            for
                              parentActorOpt <- nebflow.core.flow.FlowMembership.getParentActor(senderSessionId)
                              res <- parentActorOpt match
                                case Some(ref) => sendMail(ref, "Nebula", message, mailType, ctx, system)
                                case None => mailNotFound(address)
                            yield res
                          case Some(agentDef) =>
                            // Agent exists but is team/flow category
                            IO.pure(
                              Left(
                                ToolError(
                                  s"'$address' is a ${agentDef.category} agent — use its team or flow instead."
                                )
                              )
                            )
                          case None =>
                            mailNotFound(address)
                      yield agentRes
                yield sr
          yield r
    yield result

  /**
   * Check if a team agent is allowed to send to the given address.
   * Returns Some(errorMessage) if blocked, None if allowed.
   *
   * Rules:
   *   - Team agent (non-manager): same team only. Cannot reach other teams or Nebula.
   *   - Team manager: same team + Nebula (escalation channel). Cannot reach other teams.
   */
  private def checkTeamScope(address: String, flowId: String, senderSessionId: String): IO[Option[String]] =
    for
      isMgr <- FlowMembership.isManager(senderSessionId)
      teamOpt <- nebflow.core.entity.EntityLoader.loadTeam(address)
    yield teamOpt match
      case Some(_) if address == flowId =>
        None // Own team — allowed
      case Some(_) =>
        Some(s"Cannot mail outside your team. You are in team '$flowId'. Use your Manager to escalate to Nebula.")
      case None if address == "Nebula" && isMgr =>
        None // Manager → Nebula — allowed (escalation channel)
      case None if address == "Nebula" =>
        Some("Cannot mail Nebula directly. Use Mail(\"manager\", ...) to report to your Team Lead.")
      case None =>
        None // Agent name — scoped resolveSessionId will handle cross-flow rejection

  /** Deliver to a session — ensure actor exists, then send Mail. */
  private def deliverToSession(
    sessionId: String,
    shortName: String,
    message: String,
    mailType: String,
    ctx: ToolContext,
    system: ActorSystem
  ): IO[Either[ToolError, String]] =
    (ctx.sharedResources, ctx.actorSystem) match
      case (Some(resources), Some(actorSystem)) =>
        for
          refOpt <- nebflow.core.flow.FlowAgentActivator.ensureSession(
            sessionId,
            ctx.agentActorRef,
            resources,
            actorSystem,
            ctx.wsSend
          )
          result <- refOpt match
            case Some(ref) => sendMail(ref, shortName, message, mailType, ctx, system)
            case None =>
              nebflow.core.flow.FlowMembership.getParentActor(sessionId).flatMap {
                case Some(ref) => sendMail(ref, shortName, message, mailType, ctx, system)
                case None => mailNotFound(shortName)
              }
        yield result
      case _ =>
        IO.pure(Left(ToolError(s"Cannot activate session for '$shortName': missing resources")))

  private def sendMail(
    ref: ActorRef[AgentCommand],
    label: String,
    message: String,
    mailType: String,
    ctx: ToolContext,
    system: ActorSystem
  ): IO[Either[ToolError, String]] =
    val senderName = ctx.agentDef.map(_.name).getOrElse("Nebula")
    val taggedMessage = s"📬 Mail from $senderName [TYPE: $mailType]\n$message"
    for
      _ <- ref ! AgentCommand.ImmediateInput(taggedMessage)
      _ <- nebflow.core.UsageTracker.record("mail", ctx.sessionId.getOrElse(""))
    yield Right(s"Message sent to $label. The agent will process it.")

  private def mailNotFound(address: String): IO[Either[ToolError, String]] =
    val msg =
      if address == "Nebula" then
        s"Cannot deliver to 'Nebula': only Team Lead can communicate with Nebula. Use Mail(\"manager\", ...) to report to your Team Lead."
      else s"Cannot deliver to '$address'. Use a team name, agent short name, or flow name."
    IO.pure(Left(ToolError(msg)))

  /** Emit flowMail WS event + persist to mailbox. */
  private def onMailDelivered(
    fromSid: String,
    toSid: String,
    toName: String,
    message: String,
    ctx: ToolContext
  ): IO[Unit] =
    val preview = message.take(200)
    for
      fromOpt <- FlowMembership.agentOfSession(fromSid)
      toOpt <- FlowMembership.agentOfSession(toSid)
      (flowName, fromName) = toOpt match
        case Some((fid, _)) => (fid, fromOpt.map(_._2).getOrElse("Nebula"))
        case None => ("", fromOpt.map(_._2).getOrElse(fromSid.take(8)))
      parentSid <- FlowMembership.parentSessionOf(flowName)
      mailboxSid = parentSid.getOrElse(ctx.sessionId.getOrElse(""))
      _ <-
        if flowName.nonEmpty then
          FlowMailStore.append(mailboxSid, flowName, FlowMailStore.MailRecord(fromName, toName, message))
        else IO.unit
      _ <- ctx.wsSend match
        case Some(send) =>
          val event = Json.obj(
            "type" -> "flowMail".asJson,
            "from" -> fromName.asJson,
            "to" -> toName.asJson,
            "flowName" -> flowName.asJson,
            "preview" -> preview.asJson
          )
          send(event).handleErrorWith(_ => IO.unit)
        case None => IO.unit
    yield ()
    end for
  end onMailDelivered
end MailTool
