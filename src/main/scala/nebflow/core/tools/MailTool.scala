package nebflow.core.tools

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.entity.EntityLoader
import nebflow.core.flow.{FlowMailStore, MailQueueStore, TeamSessionRegistry}
import nebflow.shared.{ContentBlock, Message, MessageRole}

import scala.concurrent.duration.*

/**
 * Agent-to-agent communication tool.
 *
 * Three delivery modes (via `delivery` parameter):
 * - immediate (default): Async send. Injected at next turn boundary. If recipient
 *   is idle, starts a new turn. If busy, merged into the current turn. The standard
 *   communication method. Backward compatible with `ask: false` (omitted).
 * - queue: Serialized FIFO. Persisted to disk (survives restart). Each queued mail
 *   triggers a full turn — processed one at a time, only after the current task
 *   completes. Use for serial task chains: "do this, then that, then that."
 * - ask: Synchronously fork the target agent's context, block for the answer.
 *   Converts to background after 60s. Backward compatible with `ask: true`.
 */
object MailTool extends Tool:
  private val logger = NebflowLogger(getClass)

  /** After this wait, a synchronous ask converts to a background task instead of failing. */
  private val AskBackgroundThreshold: FiniteDuration = 60.seconds

  val name: String = "Mail"

  val description: String =
    """Send a message to an agent within your team.

Required: address, message

The address depends on your team context:
- OUTSIDE any team (Nebula root / standalone agents): use a TEAM name
  (e.g. "nebflow-project") — the message goes to the team's lead agent
  (Manager), who dispatches to members. Bare member short names are not
  routable from outside a team.
- INSIDE a team: use a member short name (e.g. "backend") — resolved within
  your team first (same-team priority).
- "team/agent" (e.g. "nebflow-project/Backend") — explicit scoped route to a
  specific member from anywhere.

For spawning standalone agents, use the Delegate tool. For triggering flows, use FlowTrigger.

Images (optional `images` parameter): up to 5 absolute local image paths
(PNG/JPG/JPEG/GIF/WEBP/BMP) sent as attachments — the recipient sees the images
directly (vision models) plus their paths as text. For any other file, reference
its path in the message text and ask the recipient to Read it.

Default mode (ask omitted or false):
  Async send. If the recipient is idle, delivered immediately. If busy, queued
  and injected at the next turn boundary. You don't wait for a response.

Ask mode (ask: true):
  Synchronous — forks the target agent's context (loads their conversation history
  into a temporary instance), runs the LLM, and blocks until the answer is returned.
  The agent's main task is NOT interrupted. If the answer takes longer than ~60s,
  the ask converts to a background task: you get immediate feedback, the fork keeps
  running, and the answer is delivered later via a completion notification.
  Use for progress queries and quick questions when you need an immediate answer
  without disrupting workflow.

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
          "description" -> "Outside a team: a team name (e.g. \"nebflow-project\") routed to its Manager. Inside a team: a member short name (e.g. \"backend\"). Or explicit \"team/agent\" (e.g. \"nebflow-project/Backend\").".asJson
        ),
        "message" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "The message or question to send".asJson
        ),
        "ask" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "If true, synchronously fork the target's context and block until the answer is returned (without interrupting their main task). If no answer within ~60s, the ask converts to a background task — you get immediate feedback and the answer arrives later via a completion notification. Default: false.".asJson,
          "default" -> false.asJson
        ),
        "type" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("INFO".asJson, "FOLLOW_UP".asJson, "PARALLEL".asJson, "INTERRUPT".asJson, "RESULT".asJson),
          "description" -> """Message type tag. "INFO" = supplementary context (default); "FOLLOW_UP" = new task after current finishes; "PARALLEL" = delegate independently; "INTERRUPT" = urgent, handle now; "RESULT" = work results from another agent.""".asJson,
          "default" -> "INFO".asJson
        ),
        "delivery" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("ask".asJson, "queue".asJson, "immediate".asJson),
          "description" -> "Delivery mode: 'ask' = sync fork (wait for answer); 'queue' = serialized FIFO (survives restart, processed one at a time after current task completes); 'immediate' = inject like user input (merged into current turn at next boundary). Default: 'immediate'. [INTERRUPT] type must use 'immediate' — queue would delay it past the current task, breaking the interrupt semantics.".asJson,
          "default" -> "immediate".asJson
        ),
        "images" -> Json.obj(
          "type" -> "array".asJson,
          "items" -> Json.obj("type" -> "string".asJson).asJson,
          "maxItems" -> 5.asJson,
          "description" -> "Optional absolute local image paths (PNG/JPG/JPEG/GIF/WEBP/BMP, max 5) to attach — the recipient sees the images directly plus their paths as text. For other files, reference the path in the message text.".asJson,
          "default" -> Json.arr()
        )
      ),
      "required" -> Json.arr("address".asJson, "message".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val addr = input("address").flatMap(_.asString).getOrElse("?")
    val delivery = input("delivery").flatMap(_.asString).getOrElse {
      val ask = input("ask").orElse(input("fork")).flatMap(_.asBoolean).getOrElse(false)
      if ask then "ask" else "immediate"
    }
    val mailType = input("type").flatMap(_.asString).getOrElse("INFO")
    val typeStr = if mailType != "INFO" then s" [$mailType]" else ""
    delivery match
      case "ask"     => s"Mail(→$addr, ask)$typeStr"
      case "queue"   => s"Mail(→$addr, queue)$typeStr"
      case _         => s"Mail(→$addr)$typeStr"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val address = input("address").flatMap(_.asString).getOrElse("")
    val message = input("message").flatMap(_.asString).getOrElse("")
    val mailType = input("type").flatMap(_.asString).getOrElse("INFO")

    // Delivery mode: explicit delivery param wins, fall back to ask param for
    // backward compatibility (ask:true -> "ask", else -> "immediate").
    val delivery = input("delivery").flatMap(_.asString)
      .orElse {
        val ask = input("ask").orElse(input("fork")).flatMap(_.asBoolean).getOrElse(false)
        if ask then Some("ask") else Some("immediate")
      }.getOrElse("immediate")

    if address.isEmpty then IO.pure(Left(ToolError("Missing required parameter: address")))
    else if message.isEmpty then IO.pure(Left(ToolError("Missing required parameter: message")))
    else
      ImageInject.parseImagesParam(input) match
        case Left(err) => IO.pure(Left(err))
        case Right(imagePaths) =>
          // G3: resolve attachments BEFORE any delivery side effect (fail fast
          // at the point of action — actor activation / queue persist must not
          // happen for an invalid attachment). Queue mode persists the paths
          // and re-reads at drain time (D6), so only the validation result is
          // used there.
          ImageInject.resolveImages(imagePaths).flatMap {
            case Left(err) => IO.pure(Left(err))
            case Right(attachments) =>
              val blocks = ImageInject.messageBlocks(message, attachments)
              ctx.actorSystem match
                case None =>
                  IO.pure(Left(ToolError("No actor system available")))
                case Some(system) =>
                  delivery match
                    case "ask" => forkAndAsk(address, message, blocks, ctx)
                    case "queue" =>
                      if address.contains("://") then
                        IO.pure(Left(ToolError("Queue mode is only for team agents (short names), not URLs.")))
                      else deliverQueue(address, message, mailType, imagePaths, ctx, system)
                    case _ =>
                      if address.contains("://") then deliverToAddress(address, message, blocks, mailType, ctx, system)
                      else deliverToShortName(address, message, blocks, mailType, ctx, system)
          }
  end call

  // ============================================================
  // Fork mode: load target's history, spawn temp agent, return response
  // ============================================================

  /** Routing rule: senders without a team context mail TEAM names only. */
  private val TeamOnlyRoutingError =
    "Agents outside a team mail TEAM names only (e.g. \"nebflow-project\") — the team Manager dispatches to members. Team members use short names internally. Or use explicit \"team/agent\" (e.g. \"nebflow-project/Backend\")."

  private def forkAndAsk(
      address: String,
      question: String,
      blocks: Option[List[ContentBlock]],
      ctx: ToolContext
    ): IO[Either[ToolError, String]] =
    (ctx.actorSystem, ctx.sharedResources, ctx.sessionId) match
      case (Some(system), Some(resources), Some(senderSid)) =>
        for
          teamOpt <- EntityLoader.loadTeam(address)
          result <- teamOpt match
            case Some(team) =>
              // Team name — fork the team's lead (Manager).
              TeamSessionRegistry.findTeamAgent(address, team.lead).flatMap {
                case Some(leadSid) => forkToSession(system, resources, leadSid, address, question, blocks, ctx)
                case None =>
                  IO.pure(
                    Left(
                      ToolError(s"Team '$address' is not mounted. Use Load(type: \"team\", name: \"$address\") first.")
                    )
                  )
              }
            case None =>
              // Not a team name — short agent name. Routable only for senders
              // with a team context (same-team priority); outside a team, only
              // team names, "Nebula", and explicit team/agent are valid.
              TeamSessionRegistry.teamOfSession(senderSid).flatMap {
                case None if address != "Nebula" && !address.contains("/") =>
                  IO.pure(Left(ToolError(TeamOnlyRoutingError)))
                case _ =>
                  for
                    targetRes <- TeamSessionRegistry.resolveSessionId(senderSid, address, resources.sessionStore)
                    result <- targetRes match
                      case Left(ambErr) =>
                        IO.pure(Left(ToolError(ambErr)))
                      case Right(None) =>
                        IO.pure(
                          Left(
                            ToolError(
                              s"Agent '$address' not found. Use the agent names from your Team context."
                            )
                          )
                        )
                      case Right(Some(targetSid)) =>
                        forkToSession(system, resources, targetSid, address, question, blocks, ctx)
                  yield result
              }
        yield result
      case _ =>
        IO.pure(Left(ToolError("Fork mode requires actor system, shared resources, and session context.")))

  /** Fork an already-resolved session: load its def, spawn a temp agent, ask. */
  private def forkToSession(
      system: ActorSystem,
      resources: SharedResources,
      targetSid: String,
      address: String,
      question: String,
      blocks: Option[List[ContentBlock]],
      ctx: ToolContext
    ): IO[Either[ToolError, String]] =
    TeamSessionRegistry.instanceAndAgentOfSession(targetSid).flatMap {
      case Some((instance, agentName)) =>
        EntityLoader.loadTeamAgent(instance, agentName).flatMap {
          case Some(entry) =>
            val agentDef = entry.toAgentDef
            doFork(system, resources, agentDef, Some(targetSid), question, blocks, address, ctx)
          case None =>
            IO.pure(Left(ToolError(s"Agent '$address' definition not found.")))
        }
      case None =>
        IO.pure(Left(ToolError(s"Agent '$address' has no team association.")))
    }

  private def doFork(
      system: ActorSystem,
      resources: SharedResources,
      agentDef: AgentDef,
      agentSessionId: Option[String],
      question: String,
      blocks: Option[List[ContentBlock]],
      address: String,
      ctx: ToolContext
    ): IO[Either[ToolError, String]] =
    for
      history <- agentSessionId match
        case Some(sid) => resources.sessionStore.loadMessagesForSession(sid)
        case None => IO.pure(List.empty[Message])

      // Inherit caller's safety mode so permission prompts are consistent
      callerSafetyMode <- (ctx.sessionStore, ctx.sessionId) match
        case (Some(store), Some(sid)) => store.getSafetyMode(sid)
        case _ => IO.pure("confirm-edits")

      // P2: resolve the caller's permission-policy bucket so the fork agent
      // inherits the same root-session policy and renders interactions in the
      // same Nebula window as the caller.
      callerRootSessionId <- ctx.sessionId match
        case Some(sid) =>
          resources.agentRegistry.get.map(_.get(sid).map(_.rootSessionId).filter(_.nonEmpty).getOrElse(sid))
        case None => IO.pure("")

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
      // Route permission prompts to the caller's WS so they are visible
      forkWs = ctx.wsSend.getOrElse((_: Json) => IO.unit)

      agentRef <- system.spawn(
        AgentActor(
          agentDef = agentDef,
          resources = resources,
          wsSend = forkWs,
          depth = (ctx.depth + 1),
          parentRef = ctx.agentActorRef,
          sessionId = Some(tempSession.id),
          sessionName = Some(s"fork/${agentDef.name}"),
          initialMessages = history,
          readTracker = Some(readTracker),
          fileHistory = Some(fileHistory),
          contextWindow = resources.contextWindow,
          projectRoot = Some(ctx.projectRoot),
          safetyMode = callerSafetyMode,
          expectsMail = false,
          rootSessionId = callerRootSessionId
        ),
        s"fork-${agentDef.name}-${java.util.UUID.randomUUID().toString.take(8)}"
      )

      // blocks carry the attachments with the message text as the first Text
      // block (UserInput drops `text` when blocks are present).
      _ <- agentRef ! AgentCommand.UserInput(question, Some(adapterRef), blocks = blocks, delivery = Some("ask"))

      result <- waitForForkAnswer(
        system,
        resources,
        agentRef,
        adapterRef,
        tempSession.id,
        responseDeferred,
        address,
        ctx
      )
    yield result

  /**
   * Wait for the fork answer.
   *
   * Races the answer against the background threshold: if the answer arrives
   * first, this is the old synchronous mode. If the threshold wins, the ask
   * converts to a background task — same pattern as BashTool's
   * auto-background: immediate feedback to the caller, the fork agent keeps
   * running, and the answer is later injected into the caller's conversation
   * via ExternalEvent. No hard timeout — the ask never fails on slowness.
   *
   * (Flow agents no longer reach this path at all: buildAllowedToolSet
   * strips Mail from flow-category agents, so they cannot ask.)
   */
  private def waitForForkAnswer(
    system: ActorSystem,
    resources: SharedResources,
    agentRef: ActorRef[AgentCommand],
    adapterRef: ActorRef[AgentEvent],
    tempSessionId: String,
    responseDeferred: Deferred[IO, Either[String, String]],
    address: String,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    // Stop fork agent + adapter, delete temp session.
    def cleanupFork: IO[Unit] =
      system.stop(agentRef).handleErrorWith(_ => IO.unit) *>
        system.stop(adapterRef).handleErrorWith(_ => IO.unit) *>
        resources.sessionStore.deleteSession(tempSessionId).handleErrorWith(_ => IO.unit)

    responseDeferred.get.race(IO.sleep(AskBackgroundThreshold)).flatMap {
      case Left(Right(text)) => cleanupFork *> IO.pure(Right(text))
      case Left(Left(err)) => cleanupFork *> IO.pure(Left(ToolError(err)))
      case Right(_) =>
        val jobId = s"ask-${java.util.UUID.randomUUID().toString.take(8)}"
        val description = s"ask $address"
        for
          _ <- BgTaskRegistry.register(jobId, ctx.sessionId.getOrElse(""), description, "ask")
          _ <- emitAskStarted(ctx, jobId, description)
          // Detached fiber: wait for the fork to finish, then cleanup +
          // notify the caller. Never fails the ask — the fork decides.
          _ <- (responseDeferred.get
            .flatMap {
              case Right(text) =>
                cleanupFork *>
                  BgTaskRegistry.unregister(jobId) *>
                  emitAskFinished(ctx, jobId, description, succeeded = true) *>
                  notifyAskCompleted(ctx, address, jobId, text, succeeded = true)
              case Left(err) =>
                cleanupFork *>
                  BgTaskRegistry.unregister(jobId) *>
                  emitAskFinished(ctx, jobId, description, succeeded = false) *>
                  notifyAskCompleted(ctx, address, jobId, err, succeeded = false)
            })
            .start
            .void
        yield Right(
          s"[Ask moved to background] Mail ask to '$address' has been running for over " +
            s"${AskBackgroundThreshold.toSeconds}s. The fork continues in the background — " +
            "you will be notified when the answer arrives. Continue with other work or finish your turn."
        )
        end for
    }
  end waitForForkAnswer

  /** Emit a WS event so the frontend shows the ask as a background task. */
  private def emitAskStarted(ctx: ToolContext, jobId: String, description: String): IO[Unit] =
    ctx.wsSend.fold(IO.unit) { send =>
      send(
        io.circe.Json.obj(
          "type" -> "backgroundTaskUpdate".asJson,
          "sessionId" -> ctx.sessionId.asJson,
          "taskId" -> jobId.asJson,
          "description" -> description.asJson,
          "status" -> "running".asJson,
          "startedAt" -> System.currentTimeMillis().asJson
        )
      ).handleErrorWith(e => logger.warn(s"WS send failed for ask job $jobId: ${e.getMessage}"))
    }

  /** Emit a WS event so the frontend dismisses the ask background indicator. */
  private def emitAskFinished(ctx: ToolContext, jobId: String, description: String, succeeded: Boolean): IO[Unit] =
    ctx.wsSend.fold(IO.unit) { send =>
      send(
        io.circe.Json.obj(
          "type" -> "backgroundTaskUpdate".asJson,
          "sessionId" -> ctx.sessionId.asJson,
          "taskId" -> jobId.asJson,
          "description" -> description.asJson,
          "status" -> (if succeeded then "completed" else "failed").asJson
        )
      ).handleErrorWith(e => logger.warn(s"WS send failed for ask job $jobId: ${e.getMessage}"))
    }

  /** Inject the ask answer into the caller's conversation via ExternalEvent. */
  private def notifyAskCompleted(
    ctx: ToolContext,
    address: String,
    jobId: String,
    answer: String,
    succeeded: Boolean
  ): IO[Unit] =
    ctx.agentActorRef.fold(IO.unit) { ref =>
      val eventType = if succeeded then "completed" else "failed"
      val payload =
        if succeeded then s"""[Mail ask completed] "$address" answered:\n$answer"""
        else s"""[Mail ask failed] "$address" responded with an error:\n$answer"""
      (ref ! AgentCommand.ExternalEvent(
        source = "mail-ask",
        eventType = eventType,
        payload = payload,
        metadata = io.circe.JsonObject(
          "address" -> address.asJson,
          "jobId" -> jobId.asJson
        )
      )).handleErrorWith(e => logger.warn(s"Failed to notify agent for ask job $jobId: ${e.getMessage}"))
    }

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
        case AgentEvent.Cancelled(_, reason) =>
          Left(s"$agentName cancelled: $reason")
      (responseDeferred
        .complete(result)
        .as(Behaviors.stopped))
        .handleErrorWith(_ => IO.pure(Behaviors.stopped))
    }

  // ============================================================
  // Queue mode: persisted FIFO, drained one-per-turn
  // ============================================================

  private def deliverQueue(
      address: String,
      message: String,
      mailType: String,
      imagePaths: List[String],
      ctx: ToolContext,
      system: ActorSystem
    ): IO[Either[ToolError, String]] =
    val senderSessionId = ctx.sessionId.getOrElse("")
    val senderName = ctx.agentDef.map(_.name).getOrElse("")

    TeamSessionRegistry.teamOfSession(senderSessionId).flatMap {
      case None =>
        if address == "Nebula" && senderName != "Nebula" then
          canMailNebula(senderName, senderSessionId).flatMap { canMail =>
            if canMail then resolveAndQueue(address, message, mailType, imagePaths, ctx, system, senderSessionId)
            else
              IO.pure(
                Left(ToolError("Cannot mail Nebula directly. You are a team worker. Report to your Manager via Mail."))
              )
          }
        else resolveAndQueue(address, message, mailType, imagePaths, ctx, system, senderSessionId)
      case Some(teamName) =>
        checkTeamScope(address, teamName, senderSessionId, senderName).flatMap {
          case Some(error) => IO.pure(Left(ToolError(error)))
          case None        => resolveAndQueue(address, message, mailType, imagePaths, ctx, system, senderSessionId)
        }
    }
  end deliverQueue

  /** Resolve target session (team name → lead, or short name) then queue the mail. */
  private def resolveAndQueue(
      address: String,
      message: String,
      mailType: String,
      imagePaths: List[String],
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
              case Some(targetSid) => queueToSession(targetSid, team.lead, message, mailType, imagePaths, ctx, system, senderSessionId)
              case None =>
                IO.pure(Left(ToolError(s"Team '$address' is not mounted. Use Load(type: \"team\", name: \"$address\") first.")))
          yield r
        case None =>
          for
            senderTeamOpt <- TeamSessionRegistry.teamOfSession(senderSessionId)
            sr <- senderTeamOpt match
              case None if address != "Nebula" && !address.contains("/") =>
                IO.pure(Left(ToolError(TeamOnlyRoutingError)))
              case _ =>
                for
                  targetRes <- ctx.sharedResources match
                    case Some(res) => TeamSessionRegistry.resolveSessionId(senderSessionId, address, res.sessionStore)
                    case None      => IO.pure(Right(None))
                  sr2 <- targetRes match
                    case Left(ambErr) => IO.pure(Left(ToolError(ambErr)))
                    case Right(Some(targetSid)) =>
                      queueToSession(targetSid, address, message, mailType, imagePaths, ctx, system, senderSessionId)
                    case Right(None) if address == "Nebula" =>
                      queueToNebula(message, mailType, imagePaths, ctx, system, senderSessionId, address)
                    case Right(None) => mailNotFound(address)
                yield sr2
          yield sr
    yield result

  /**
   * Queue-mode routing to the Nebula root agent (issue #312).
   *
   * resolveSessionId's contract for "Nebula" is Right(None) = "route to the
   * root agent directly" (see its "team/Nebula" branch) — but the caller must
   * locate the root session: the Nebula root is a Root-kind AgentRecord in the
   * agent registry, NOT a team session in sessionMap. Before this fix a queue
   * Mail to "Nebula" fell into mailNotFound with a misleading "only Team Lead
   * can communicate with Nebula" error even when the sender IS the Team Lead
   * (Manager→Nebula queue rejected; immediate mode was fine because it
   * resolves the actor by name via system.resolve). Permission is already
   * enforced upstream: deliverQueue runs checkTeamScope first, so only
   * canMailNebula senders reach here with address == "Nebula".
   */
  private[tools] def queueToNebula(
      message: String,
      mailType: String,
      imagePaths: List[String],
      ctx: ToolContext,
      system: ActorSystem,
      senderSessionId: String,
      address: String
    ): IO[Either[ToolError, String]] =
    ctx.sharedResources match
      case Some(res) =>
        resolveNebulaRootSession(res).flatMap {
          case Some(nebulaSid) =>
            queueToSession(nebulaSid, address, message, mailType, imagePaths, ctx, system, senderSessionId)
          case None => mailNotFound(address)
        }
      case None => mailNotFound(address)

  /** The Nebula root agent's sessionId from the unified agent registry (Root kind). */
  private[tools] def resolveNebulaRootSession(res: SharedResources): IO[Option[String]] =
    res.agentRegistry.get.map(_.collectFirst { case (_, rec) if rec.kind == AgentKind.Root => rec.sessionId })

  /** Persist to MailQueueStore, activate target, send MailQueued command. */
  private def queueToSession(
      sessionId: String,
      shortName: String,
      message: String,
      mailType: String,
      imagePaths: List[String],
      ctx: ToolContext,
      system: ActorSystem,
      senderSessionId: String
    ): IO[Either[ToolError, String]] =
    val senderName = ctx.agentDef.map(_.name).getOrElse("Nebula")
    val item = MailQueueStore.MailQueueItem(
      id = s"mail-q-${java.util.UUID.randomUUID().toString.take(8)}",
      from = senderName,
      fromSession = senderSessionId,
      message = message,
      `type` = mailType,
      timestamp = System.currentTimeMillis(),
      // D6: persist paths (not base64) — re-read + re-compress at drain time
      imagePaths = imagePaths
    )
    (ctx.sharedResources, ctx.actorSystem) match
      case (Some(resources), Some(actorSystem)) =>
        for
          // 1. Persist to queue (survives restart)
          _ <- MailQueueStore.append(sessionId, item)
          // 2. Record in mailbox history
          _ <- onMailDelivered(senderSessionId, sessionId, shortName, message, ctx)
          // 3. Get or activate the target actor
          existingOpt <- TeamSessionRegistry.getRunningActor(sessionId)
          refOpt <- existingOpt match
            case Some(ref) => IO.pure(Some(ref))
            case None     => activateAgent(sessionId, resources, actorSystem, ctx)
          // 4. Send MailQueued command
          _ <- refOpt.traverse_(_ ! AgentCommand.MailQueued(item, senderSessionId))
          // 5. Emit WS event
          pendingCount <- MailQueueStore.size(sessionId)
          _ <- emitWsEvent(ctx, Json.obj(
            "type" -> "mailQueued".asJson,
            "sessionId" -> sessionId.asJson,
            "from" -> senderName.asJson,
            "to" -> shortName.asJson,
            "preview" -> message.take(200).asJson,
            "pendingCount" -> pendingCount.asJson,
            "timestamp" -> item.timestamp.asJson
          ))
        yield Right(s"Message queued to $shortName. Will be processed after current work completes (position #$pendingCount in queue).")
      case _ =>
        IO.pure(Left(ToolError(s"Cannot deliver queue mail to '$shortName': missing resources")))
  end queueToSession

  private def emitWsEvent(ctx: ToolContext, event: Json): IO[Unit] =
    ctx.wsSend match
      case Some(send) => send(event).handleErrorWith(_ => IO.unit)
      case None       => IO.unit

  // ============================================================
  // Normal mode: async delivery
  // ============================================================

  private def deliverToAddress(
      address: String,
      message: String,
      blocks: Option[List[ContentBlock]],
      mailType: String,
      ctx: ToolContext,
      system: ActorSystem
    ): IO[Either[ToolError, String]] =
    system.resolve[AgentCommand](address).attempt.flatMap {
      case Right(ref) => sendMail(ref, address, message, blocks, mailType, ctx, system)
      case Left(err) => IO.pure(Left(ToolError(s"Failed to resolve address '$address': ${err.getMessage}")))
    }

  private def deliverToShortName(
      address: String,
      message: String,
      blocks: Option[List[ContentBlock]],
      mailType: String,
      ctx: ToolContext,
      system: ActorSystem
    ): IO[Either[ToolError, String]] =
    val senderSessionId = ctx.sessionId.getOrElse("")
    val senderName = ctx.agentDef.map(_.name).getOrElse("")

    TeamSessionRegistry.teamOfSession(senderSessionId).flatMap {
      case None =>
        if address == "Nebula" && senderName != "Nebula" then
          canMailNebula(senderName, senderSessionId).flatMap { canMail =>
            if canMail then deliverShortNameUnscoped(address, message, blocks, mailType, ctx, system, senderSessionId)
            else
              IO.pure(
                Left(
                  ToolError(
                    "Cannot mail Nebula directly. You are a team worker. Report to your Manager via Mail."
                  )
                )
              )
          }
        else deliverShortNameUnscoped(address, message, blocks, mailType, ctx, system, senderSessionId)
      case Some(teamName) =>
        checkTeamScope(address, teamName, senderSessionId, senderName).flatMap {
          case Some(error) => IO.pure(Left(ToolError(error)))
          case None =>
            deliverShortNameUnscoped(address, message, blocks, mailType, ctx, system, senderSessionId)
        }
    }
  end deliverToShortName

  /**
   * Who may mail Nebula directly: a registered team Manager (managerMap) OR
   * any team lead by definition (agent.json lead of a mounted/defined team).
   * The name-based fallback covers fork/temporary sessions — a forked Manager
   * keeps the Manager agentDef (and its lead role) but its sessionId is not
   * registered in managerMap, so sid-only checks would wrongly reject it.
   */
  private def canMailNebula(senderName: String, senderSessionId: String): IO[Boolean] =
    TeamSessionRegistry.isManager(senderSessionId).flatMap { isMgr =>
      if isMgr then IO.pure(true)
      else if senderName.isEmpty then IO.pure(false)
      else EntityLoader.listTeams().map(_.values.exists(_.lead == senderName))
    }

  /** Locate the Nebula root agent's actor in the unified AgentRegistry (kind == Root). */
  private def resolveNebulaRef(resources: SharedResources): IO[Option[ActorRef[AgentCommand]]] =
    resources.agentRegistry.get.map(_.collectFirst { case (_, rec) if rec.kind == AgentKind.Root => rec.ref })

  private def deliverShortNameUnscoped(
    address: String,
    message: String,
    blocks: Option[List[ContentBlock]],
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
                  res <- deliverToSession(targetSid, team.lead, message, blocks, mailType, ctx, system)
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
          // Not a team name — short agent name. Routable only for senders
          // with a team context (same-team priority); outside a team, only
          // team names, "Nebula", and explicit team/agent are valid.
          for
            senderTeamOpt <- TeamSessionRegistry.teamOfSession(senderSessionId)
            sr <- senderTeamOpt match
              case None if address != "Nebula" && !address.contains("/") =>
                IO.pure(Left(ToolError(TeamOnlyRoutingError)))
              case _ =>
                for
                  targetRes <- ctx.sharedResources match
                    case Some(res) => TeamSessionRegistry.resolveSessionId(senderSessionId, address, res.sessionStore)
                    case None => IO.pure(Right(None))
                  sr2 <- targetRes match
                    case Left(ambErr) => IO.pure(Left(ToolError(ambErr)))
                    case Right(Some(targetSid)) =>
                      for
                        res <- deliverToSession(targetSid, address, message, blocks, mailType, ctx, system)
                        _ <- res match
                          case Right(_) => onMailDelivered(senderSessionId, targetSid, address, message, ctx)
                          case Left(_) => IO.unit
                      yield res
                    case Right(None) =>
                      val isNebulaTarget = address == "Nebula" || address.endsWith("/Nebula")
                      if isNebulaTarget then
                        for
                          // Prefer the parent-actor route (team agents have their
                          // parent registered at mount time). Fork/temporary sessions
                          // have no registered parent — fall back to the Nebula root
                          // session's actor in the unified AgentRegistry.
                          parentActorOpt <- TeamSessionRegistry.getParentActor(senderSessionId)
                          res <- parentActorOpt match
                            case Some(ref) => sendMail(ref, "Nebula", message, blocks, mailType, ctx, system)
                            case None =>
                              ctx.sharedResources match
                                case Some(res) =>
                                  resolveNebulaRef(res).flatMap {
                                    case Some(ref) => sendMail(ref, "Nebula", message, blocks, mailType, ctx, system)
                                    case None => mailNotFound(address)
                                  }
                                case None => mailNotFound(address)
                        yield res
                      else mailNotFound(address)
                      end if
                yield sr2
          yield sr
    yield result

  /** Marker a team's rules.md can set to opt in to cross-team explicit Mail. */
  private val CrossTeamMailMarker = "allow-cross-team-mail: true"

  /**
   * Explicit "team/agent" addresses targeting another team are blocked by
   * default for non-lead senders (decision 20) — the escalation path is
   * Manager → Nebula. A team opts in by writing
   * `<!-- allow-cross-team-mail: true -->` in its rules.md. Leads (any team
   * Manager / lead — same judgment as canMailNebula) always pass.
   *
   * Unaffected: same-team explicit routes, short names, and the Nebula root
   * (no team context — it never reaches checkTeamScope).
   */
  private def checkCrossTeamExplicitRoute(
    address: String,
    senderTeam: String,
    isLead: Boolean
  ): IO[Option[String]] =
    if !address.contains("/") then IO.pure(None)
    else
      val targetTeam = address.substring(0, address.indexOf('/'))
      if targetTeam == senderTeam || isLead then IO.pure(None)
      else
        EntityLoader.loadTeamRules(senderTeam).map { rules =>
          if rules.contains(CrossTeamMailMarker) then None
          else
            Some(
              s"Cross-team Mail to '$address' is blocked by default. You are in team '$senderTeam'. " +
                "Ask your Manager to escalate to Nebula, or have the team opt in via its rules.md " +
                "(allow-cross-team-mail: true)."
            )
        }

  /** Visible for tests (package-private). */
  private[tools] def checkTeamScope(
    address: String,
    teamName: String,
    senderSessionId: String,
    senderName: String
  ): IO[Option[String]] =
    for
      teamOpt <- EntityLoader.loadTeam(address)
      canNebula <- canMailNebula(senderName, senderSessionId)
      crossTeam <- checkCrossTeamExplicitRoute(address, teamName, canNebula)
    yield teamOpt match
      case Some(_) if address == teamName =>
        None
      case Some(_) =>
        Some(s"Cannot mail outside your team. You are in team '$teamName'. Use your Manager to escalate to Nebula.")
      case None if address == "Nebula" && canNebula =>
        None
      case None if address == "Nebula" =>
        Some("Cannot mail Nebula directly. Use Mail(\"manager\", ...) to report to your Team Lead.")
      case None =>
        crossTeam

  /** Deliver to a session — ensure actor exists, then send Mail. */
  private def deliverToSession(
    sessionId: String,
    shortName: String,
    message: String,
    blocks: Option[List[ContentBlock]],
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
            case Some(ref) => sendMail(ref, shortName, message, blocks, mailType, ctx, system)
            case None =>
              TeamSessionRegistry.getParentActor(sessionId).flatMap {
                case Some(ref) => sendMail(ref, shortName, message, blocks, mailType, ctx, system)
                case None => mailNotFound(shortName)
              }
        yield result
      case _ =>
        IO.pure(Left(ToolError(s"Cannot activate session for '$shortName': missing resources")))

  /** Activate a team agent session by spawning an AgentActor (replaces FlowAgentActivator).
    * Package-visible for the lifecycle spec (respawn history + death watch). */
  private[tools] def activateAgent(
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
          // P2: resolve the Nebula root session (permission-policy anchor) first
          // so the spawned agent inherits the root's policy bucket and its
          // InteractionRequests render in the Nebula window.
          parentSidOpt <- TeamSessionRegistry.parentSessionOf(session.flowName.getOrElse(""))
          rootSid = parentSidOpt.getOrElse(session.id)
          policyOpt <- resources.permissionPolicies.get.map(_.get(rootSid))
          safetyMode =
            policyOpt.map(p => nebflow.core.SafetyMode.toString(p.safetyMode)).getOrElse(session.safetyMode)
          entryOpt <- session.flowName match
            case Some(teamName) => EntityLoader.loadTeamAgent(teamName, agentName)
            case None => EntityLoader.loadAgent(agentName)
          _ <- entryOpt.traverse_ { entry =>
            val agentDef = entry.toAgentDef
            // Route team agent events with a "team-" prefixed nodeSessionId so
            // the frontend can distinguish Mail-activated team agents from flow
            // agents (whose nodeSessionId is "dag-..."). Without this prefix,
            // the flow interceptor in ws.js would claim these events and render
            // team agent activity into a flow popup.
            //
            // protocol.scala stamps every subagent event with nodeSessionId =
            // sessionId, so the old "if nodeSessionId absent" guard was always
            // true and the "team-" prefix never applied. Overwrite explicitly,
            // except for ids already stamped by an inner wrapper:
            //  - "delegate-..." stamped by DelegateTool.routeWsSend (sub-agents
            //    the team agent spawned via Delegate keep their own prefix so
            //    their events route to the delegate popup, not here)
            //  - "subtask-..." stamped by SubTaskTool.routeWsSend (same for
            //    SubTask workers spawned by team members)
            //  - "team-..." stamped by an INNER MailTool wrapper — in nested
            //    Mail activation (Nebula→Manager→Frontend) the innermost
            //    wrapper stamps the true source; outer wrappers must preserve
            //    it, otherwise the frontend strips the outer team- prefix and
            //    marks the wrong agent (Manager) running while the real
            //    sub-agent (Frontend) stays idle
            val teamWsSend: Json => IO[Unit] = (json: Json) =>
              val underlying = ctx.wsSend.getOrElse((_: Json) => IO.unit)
              val nsidOpt = json.hcursor.downField("nodeSessionId").as[String].toOption
              val stamped = nsidOpt match
                case Some(nsid)
                    if nsid.startsWith("delegate-") || nsid.startsWith("subtask-") || nsid.startsWith("team-") =>
                  json
                case _ =>
                  json.asObject
                    .map(obj => Json.fromJsonObject(obj.add("nodeSessionId", s"team-${session.id}".asJson)))
                    .getOrElse(json)
              underlying(stamped)
            // Session history for the (re)spawn: team agents are LONG-LIVED
            // and their sessions persist across activations — a respawn
            // without the stored messages is an amnesiac agent (turn counts
            // reset, prior context lost). Same pattern as doFork and the
            // root-agent restore (WebSocketRoutes.ensureRootAgent).
            val historyIo: IO[List[Message]] = resources.sessionStore
              .loadMessagesForSession(session.id)
              .handleError { e =>
                logger.warn(s"activateAgent: history load failed for ${session.id}: ${e.getMessage}")
                List.empty[Message]
              }
            for
              history <- historyIo
              // Actor name = session.id pins the event contract
              // "agentId == sessionId" (documented at the getActiveAgents
              // reply: restored bg-agent entries key by sessionId so they
              // merge with subsequent realtime events). Live subagent events
              // key the frontend map by ctx.self.path.name, and the old
              // "mail-<sid8>" name broke the equality for Mail-activated
              // team agents: after a browser refresh the restore reply
              // (agentId=sid) plus the next live agentStart
              // (agentId=mail-<sid8>) filed TWO running rows for ONE
              // session — the Teams panel double-entry ghost. Delegate /
              // SubTask / DAG spawns already name actors by their
              // nodeSessionId; this aligns the Mail path with them.
              ref <- actorSystem.spawn(
                AgentActor(
                  agentDef = agentDef,
                  resources = resources,
                  wsSend = teamWsSend,
                  depth = 1,
                  parentRef = ctx.agentActorRef,
                  sessionId = Some(session.id),
                  sessionName = Some(session.name),
                  initialMessages = history,
                  projectRoot = Some(ctx.projectRoot),
                  safetyMode = safetyMode,
                  rootSessionId = rootSid,
                  expectsMail = entry.name != "Manager"
                ),
                session.id
              )
              // Death watch: Mail-spawned team agents live OUTSIDE
              // FlowTreeActor's watch system, so their death was completely
              // silent — actorMap kept the dead ref (Mails to it vanished),
              // agentRegistry kept the record, busyMap kept the last turn's
              // busy flag, and getActiveAgents reported a running ghost for
              // hours. This watcher fires the cleanup FlowTreeActor runs for
              // its own spawns: unregister + clear busy + leave a log trail
              // (the silent death itself was the observability gap).
              _ <- actorSystem.spawn(
                MailTool.teamAgentDeathWatch(session.id, entry.name, ref, resources),
                s"deathwatch-${session.id.take(8)}"
              )
              _ <- TeamSessionRegistry.registerActor(session.id, ref)
              _ <- resources.agentRegistry.update(
                _ + (
                  session.id -> AgentRecord(
                    sessionId = session.id,
                    ref = ref,
                    kind = AgentKind.Team,
                    rootSessionId = rootSid,
                    parentRef = ctx.agentActorRef
                  )
                )
              )
              // Restart recovery: if mail-queue.json has pending items from a
              // previous session, send MailQueued to trigger drain.
              _ <- MailQueueStore
                .load(session.id)
                .flatMap(items => items.headOption.traverse_(head => ref ! AgentCommand.MailQueued(head, "")))
                .start
                .void
            yield ref
            end for
          }
        yield ()
        end for
      }
      ref <- TeamSessionRegistry.getRunningActor(sessionId)
    yield ref

  /**
    * Death watch for Mail-activated team agents (deep follow-up #1,
    * 2026-08-17): MailTool spawns these actors outside FlowTreeActor's
    * watch system, so their death was completely silent — actorMap kept
    * the dead ref (subsequent Mails to it vanished into a dead queue),
    * agentRegistry kept the record, busyMap kept the last turn's busy
    * flag, and getActiveAgents reported a running ghost for hours
    * (the snapshot source of the Teams panel bare running rows).
    *
    * The watcher mirrors the cleanup FlowTreeActor runs for its own
    * spawns (unregisterActor + resume scheduling) plus the piece that
    * handler lacks — clearing the busy flag — and leaves a log trail:
    * silent death was itself the observability defect.
    *
    * Package-visible for the deathwatch spec.
    */
  private[tools] def teamAgentDeathWatch(
      sessionId: String,
      agentName: String,
      watched: ActorRef[AgentCommand],
      resources: SharedResources
  ): Behavior[SystemSignal] =
    Behaviors.setup { wctx =>
      wctx.watch(watched).map { _ =>
        new Behavior[SystemSignal]:
          def receive(ctx: ActorContext[SystemSignal], msg: SystemSignal): IO[Behavior[SystemSignal]] =
            IO.pure(this)

          override def onSignal(
              ctx: ActorContext[SystemSignal],
              signal: SystemSignal
          ): IO[Behavior[SystemSignal]] =
            signal match
              case SystemSignal.Terminated(_) =>
                TeamSessionRegistry.unregisterActor(sessionId, resources) *>
                  TeamSessionRegistry.markIdle(sessionId) *>
                  IO(logger.info(
                    s"team agent actor stopped: agent=$agentName session=$sessionId — registry unregistered, busy cleared"
                  )).as(Behaviors.stopped[SystemSignal])
      }
    }
  end teamAgentDeathWatch

  private def sendMail(
    ref: ActorRef[AgentCommand],
    label: String,
    message: String,
    blocks: Option[List[ContentBlock]],
    mailType: String,
    ctx: ToolContext,
    system: ActorSystem
  ): IO[Either[ToolError, String]] =
    val senderName = ctx.agentDef.map(_.name).getOrElse("Nebula")
    val senderSid = ctx.sessionId.getOrElse("")
    for
      // Resolve the sender's team so the injected bubble can show "team/agent" attribution.
      teamOpt <- TeamSessionRegistry.teamOfSession(senderSid)
      _ <- ref ! AgentCommand.ImmediateInput(
        message,
        // G3 attachments: blocks already contain the message text as the first
        // Text block (AgentActor drops `text` when blocks are present).
        blocks = blocks,
        source = Some("mail"),
        eventType = Some(mailType.toLowerCase),
        sender = Some(senderName),
        senderTeam = teamOpt,
        delivery = Some("immediate")
      )
      _ <- nebflow.core.UsageTracker.record("mail", senderSid)
    yield Right(s"Message sent to $label. The agent will process it.")

  end sendMail

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
