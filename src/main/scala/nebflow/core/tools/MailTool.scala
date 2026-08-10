package nebflow.core.tools

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.entity.EntityLoader
import nebflow.core.flow.{FlowMailStore, TeamSessionRegistry}
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
    """Send a message to an agent within your team.

Required: address, message

The address can be:
- A team name (e.g. "nebflow-project") — forwards the message to the team's lead agent.
- A team lead or member name (e.g. "nebflow-manager", "backend") — sends a message to that agent.

For triggering flows or spawning standalone agents, use the Delegate tool instead.

Default mode (fork omitted or false):
  Async send. If the recipient is idle, delivered immediately. If busy, queued
  and injected at the next turn boundary. You don't wait for a response.

Fork mode (fork: true):
  Forks the target agent's context — loads their conversation history into a
  temporary instance, asks the question, and returns the answer immediately.
  The agent's main task is NOT interrupted. Use for progress queries and
  quick questions when you need an immediate answer without disrupting workflow.

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
          "description" -> "Recipient: a team name (e.g. \"nebflow-project\") or agent name (e.g. \"backend\").".asJson
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
          targetSidOpt <- TeamSessionRegistry.resolveSessionId(senderSid, address, resources.sessionStore)
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
              TeamSessionRegistry.instanceAndAgentOfSession(targetSid).flatMap {
                case Some((instance, agentName)) =>
                  EntityLoader.loadTeamAgent(instance, agentName).flatMap {
                    case Some(entry) =>
                      val agentDef = AgentDef(
                        name = entry.name, description = entry.description, tools = entry.tools,
                        systemPrompt = entry.systemPrompt, category = entry.category,
                        mcpServers = entry.mcpServers, model = entry.model
                      )
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
      history <- agentSessionId match
        case Some(sid) => resources.sessionStore.loadMessagesForSession(sid)
        case None => IO.pure(List.empty[Message])

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
      forkWs = (_: Json) => IO.unit

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

  private def deliverToShortName(
    address: String,
    message: String,
    mailType: String,
    ctx: ToolContext,
    system: ActorSystem
  ): IO[Either[ToolError, String]] =
    val senderSessionId = ctx.sessionId.getOrElse("")
    val senderName = ctx.agentDef.map(_.name).getOrElse("")

    TeamSessionRegistry.teamOfSession(senderSessionId).flatMap {
      case None =>
        if address == "Nebula" && senderName != "Nebula" then
          TeamSessionRegistry.isManager(senderSessionId).flatMap { isMgr =>
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
      case Some(teamName) =>
        checkTeamScope(address, teamName, senderSessionId).flatMap {
          case Some(error) => IO.pure(Left(ToolError(error)))
          case None =>
            deliverShortNameUnscoped(address, message, mailType, ctx, system, senderSessionId)
        }
    }
  end deliverToShortName

  private def deliverShortNameUnscoped(
    address: String,
    message: String,
    mailType: String,
    ctx: ToolContext,
    system: ActorSystem,
    senderSessionId: String
  ): IO[Either[ToolError, String]] =
    for
      teamOpt <- EntityLoader.loadTeam(address)
      result <- teamOpt match
        case Some(team) =>
          for
            leadSidOpt <- TeamSessionRegistry.findTeamAgent(address, team.lead)
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
          for
            sidOpt <- ctx.sharedResources match
              case Some(res) => TeamSessionRegistry.resolveSessionId(senderSessionId, address, res.sessionStore)
              case None => IO.pure(None)
            sr <- sidOpt match
              case Some(targetSid) =>
                for
                  res <- deliverToSession(targetSid, address, message, mailType, ctx, system)
                  _ <- res match
                    case Right(_) => onMailDelivered(senderSessionId, targetSid, address, message, ctx)
                    case Left(_) => IO.unit
                yield res
              case None =>
                if address == "Nebula" then
                  for
                    parentActorOpt <- TeamSessionRegistry.getParentActor(senderSessionId)
                    res <- parentActorOpt match
                      case Some(ref) => sendMail(ref, "Nebula", message, mailType, ctx, system)
                      case None => mailNotFound(address)
                  yield res
                else mailNotFound(address)
          yield sr
    yield result

  private def checkTeamScope(address: String, teamName: String, senderSessionId: String): IO[Option[String]] =
    for
      isMgr <- TeamSessionRegistry.isManager(senderSessionId)
      teamOpt <- EntityLoader.loadTeam(address)
    yield teamOpt match
      case Some(_) if address == teamName =>
        None
      case Some(_) =>
        Some(s"Cannot mail outside your team. You are in team '$teamName'. Use your Manager to escalate to Nebula.")
      case None if address == "Nebula" && isMgr =>
        None
      case None if address == "Nebula" =>
        Some("Cannot mail Nebula directly. Use Mail(\"manager\", ...) to report to your Team Lead.")
      case None =>
        None

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
          // Check if actor already running
          existingOpt <- TeamSessionRegistry.getRunningActor(sessionId)
          refOpt <- existingOpt match
            case Some(ref) => IO.pure(Some(ref))
            case None =>
              // Activate agent from session
              activateAgent(sessionId, resources, actorSystem, ctx)
          result <- refOpt match
            case Some(ref) => sendMail(ref, shortName, message, mailType, ctx, system)
            case None =>
              TeamSessionRegistry.getParentActor(sessionId).flatMap {
                case Some(ref) => sendMail(ref, shortName, message, mailType, ctx, system)
                case None => mailNotFound(shortName)
              }
        yield result
      case _ =>
        IO.pure(Left(ToolError(s"Cannot activate session for '$shortName': missing resources")))

  /** Activate a team agent session by spawning an AgentActor (replaces FlowAgentActivator). */
  private def activateAgent(
    sessionId: String,
    resources: SharedResources,
    actorSystem: ActorSystem,
    ctx: ToolContext
  ): IO[Option[ActorRef[AgentCommand]]] =
    for
      sessionOpt <- resources.sessionStore.getSessionMeta(sessionId)
      refOpt <- sessionOpt.traverse_ { session =>
        val agentName = session.agentName.getOrElse("")
        for
          // Use loadTeamAgent when session belongs to a team (checks team dir first, then global).
          // Plain loadAgent only checks global agents/ which misses team-scoped agents like Manager.
          entryOpt <- session.flowName match
            case Some(teamName) => EntityLoader.loadTeamAgent(teamName, agentName)
            case None => EntityLoader.loadAgent(agentName)
          _ <- entryOpt.traverse_ { entry =>
            val agentDef = AgentDef(
              name = entry.name, description = entry.description, tools = entry.tools,
              systemPrompt = entry.systemPrompt, category = entry.category,
              mcpServers = entry.mcpServers, model = entry.model
            )
            for
              ref <- actorSystem.spawn(
                AgentActor(
                  agentDef = agentDef,
                  resources = resources,
                  wsSend = ctx.wsSend.getOrElse(_ => IO.unit),
                  depth = 1,
                  parentRef = ctx.agentActorRef,
                  sessionId = Some(session.id),
                  sessionName = Some(session.name),
                  projectRoot = Some(ctx.projectRoot),
                  safetyMode = "confirm-edits"
                ),
                s"mail-${session.id.take(8)}"
              )
              _ <- TeamSessionRegistry.registerActor(session.id, ref)
            yield ref
          }
        yield ()
      }
      ref <- TeamSessionRegistry.getRunningActor(sessionId)
    yield ref

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
      else s"Cannot deliver to '$address'. Use a team name or agent short name."
    IO.pure(Left(ToolError(msg)))

  private def onMailDelivered(
    fromSid: String,
    toSid: String,
    toName: String,
    message: String,
    ctx: ToolContext
  ): IO[Unit] =
    val preview = message.take(200)
    for
      fromOpt <- TeamSessionRegistry.agentOfSession(fromSid)
      toInfo <- TeamSessionRegistry.instanceAndAgentOfSession(toSid)
      senderName = fromOpt.orElse(ctx.agentDef.map(_.name)).getOrElse("Nebula")
      (teamName, fromName) = toInfo match
        case Some((inst, _)) => (inst, senderName)
        case None => ("", fromOpt.getOrElse(fromSid.take(8)))
      parentSid <- TeamSessionRegistry.parentSessionOf(teamName)
      mailboxSid = parentSid.getOrElse(ctx.sessionId.getOrElse(""))
      _ <-
        if teamName.nonEmpty then
          FlowMailStore.append(mailboxSid, teamName, FlowMailStore.MailRecord(fromName, toName, message))
        else IO.unit
      _ <- ctx.wsSend match
        case Some(send) =>
          val event = Json.obj(
            "type" -> "flowMail".asJson,
            "from" -> fromName.asJson,
            "to" -> toName.asJson,
            "flowName" -> teamName.asJson,
            "preview" -> preview.asJson
          )
          send(event).handleErrorWith(_ => IO.unit)
        case None => IO.unit
    yield ()
    end for
  end onMailDelivered
end MailTool
