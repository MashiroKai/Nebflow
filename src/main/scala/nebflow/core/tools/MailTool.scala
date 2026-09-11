package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.entity.EntityLoader
import nebflow.core.flow.{FlowMailStore, MailQueueStore, TeamSessionRegistry}
import nebflow.core.project.{ProjectActor, ProjectRuntimeRegistry}
import nebflow.shared.{ContentBlock, Message, MessageRole}


/**
 * Agent-to-agent communication tool.
 *
 * Two delivery modes (via `delivery` parameter):
 * - immediate (default): Async send. Injected at next turn boundary. If recipient
 *   is idle, starts a new turn. If busy, merged into the current turn. The standard
 *   communication method.
 * - queue: Serialized FIFO. Persisted to disk (survives restart). Each queued mail
 *   triggers a full turn — processed one at a time, only after the current task
 *   completes. Use for serial task chains: "do this, then that, then that."
 *
 * The former `ask` mode (synchronous context fork) was removed entirely
 * (2026-08-27 user ruling) — see git history if that mechanism is ever needed.
 */
object MailTool extends Tool:
  private val logger = NebflowLogger(getClass)

  /** Routing rule: senders without a team context mail TEAM names only. */
  private val TeamOnlyRoutingError =
    "Agents outside a team mail TEAM names only (e.g. \"nebflow-project\") — the team Manager dispatches to members. \"team/agent\" explicit addresses and bare short names are not routable from outside a team."

  val name: String = "Mail"

  val description: String =
    """Send a message to an agent within your team.

Required: address, message

The address depends on your team context:
- OUTSIDE any team (Nebula root / standalone agents): use a TEAM name
  (e.g. "nebflow-project") — the message goes to the team's lead agent
  (Manager), who dispatches to members. Bare member short names and
  "team/agent" addresses are NOT routable from outside a team.
- INSIDE a team: use a member short name (e.g. "backend") — resolved within
  your team first (same-team priority).
- "team/agent" (e.g. "nebflow-project/Backend") — explicit scoped route to a
  specific member, usable from inside a team context.

For a one-shot executor outside any project (no team/mail identity), use the Delegate tool — it starts the built-in minimal kernel sub-agent.

Images (optional `images` parameter): up to 5 absolute local image paths
(PNG/JPG/JPEG/GIF/WEBP/BMP) sent as attachments — the recipient sees the images
directly (vision models) plus their paths as text. For any other file, reference
its path in the message text and ask the recipient to Read it.

Delivery modes (via `delivery` parameter):
  immediate (default): async send — injected at the next turn boundary (idle
  recipient starts a new turn; busy recipient gets it merged into the current
  turn). You don't wait for a response.
  queue: serialized FIFO persisted to disk (survives restart) — processed one
  at a time, only after the current task completes. Use for serial task
  chains ("do this, then that").

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
          "description" -> "Outside a team: a team name (e.g. \"nebflow-project\") routed to its Manager. Inside a team: a member short name (e.g. \"backend\") or an explicit \"team/agent\" route (e.g. \"nebflow-project/Backend\").".asJson
        ),
        "message" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "The message or question to send".asJson
        ),
        "type" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("INFO".asJson, "FOLLOW_UP".asJson, "PARALLEL".asJson, "INTERRUPT".asJson, "RESULT".asJson),
          "description" -> """Message type tag. "INFO" = supplementary context (default); "FOLLOW_UP" = new task after current finishes; "PARALLEL" = delegate independently; "INTERRUPT" = urgent, handle now; "RESULT" = work results from another agent.""".asJson,
          "default" -> "INFO".asJson
        ),
        "delivery" -> Json.obj(
          "type" -> "string".asJson,
          "enum" -> Json.arr("queue".asJson, "immediate".asJson),
          "description" -> "Delivery mode: 'queue' = serialized FIFO (survives restart, processed one at a time after current task completes); 'immediate' = inject like user input (merged into current turn at next boundary). Default: 'immediate'. [INTERRUPT] type must use 'immediate' — queue would delay it past the current task, breaking the interrupt semantics.".asJson,
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
    val delivery = input("delivery").flatMap(_.asString).getOrElse("immediate")
    val mailType = input("type").flatMap(_.asString).getOrElse("INFO")
    val typeStr = if mailType != "INFO" then s" [$mailType]" else ""
    delivery match
      case "queue"   => s"Mail(→$addr, queue)$typeStr"
      case _         => s"Mail(→$addr)$typeStr"

  def summarizeResult(input: JsonObject, result: String): String = result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val address = input("address").flatMap(_.asString).getOrElse("")
    val message = input("message").flatMap(_.asString).getOrElse("")
    val mailType = input("type").flatMap(_.asString).getOrElse("INFO")

    val delivery = input("delivery").flatMap(_.asString).getOrElse("immediate")

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
                  // Observability (qa #8 note): unknown delivery values (e.g. an
                  // old caller still sending "ask") silently converge to the
                  // immediate path — warn so stale callers surface in logs.
                  delivery match
                    case "queue" =>
                      if address.contains("://") then
                        IO.pure(Left(ToolError("Queue mode is only for team agents (short names), not URLs.")))
                      else deliverQueue(address, message, mailType, imagePaths, ctx, system)
                    case "immediate" =>
                      if address.contains("://") then deliverToAddress(address, message, blocks, mailType, ctx, system)
                      else deliverToShortName(address, message, blocks, mailType, ctx, system)
                    case other =>
                      IO(logger.warnSync(
                        s"[mail] unknown delivery mode '$other' from ${ctx.sessionId.getOrElse("?").take(8)} — falling back to immediate"
                      )) *>
                        (if address.contains("://") then deliverToAddress(address, message, blocks, mailType, ctx, system)
                         else deliverToShortName(address, message, blocks, mailType, ctx, system))
          }
  end call

  // ============================================================
  // Queue mode: persisted FIFO, drained one-per-turn
  // ============================================================

  /** #28 阶段 0 §3.2：Mail(→project) 触发分发器（试点期新旧并存）。
    * queue/immediate 两个入口共用——address 是已挂载 project → 返回
    * Some(结果)（已处理：触发 ProjectActor.TriggerDispatcher 或挂载错误）；
    * 非 project 名 → None（调用方继续旧路由）。Mail 仅触发、无回报——
    * 节点结果沿 out 边投递（§2.7），不经 Mail 回传。 */
  private def routeToProject(
      address: String,
      message: String,
      ctx: ToolContext
    ): IO[Option[Either[ToolError, String]]] =
    ProjectRuntimeRegistry.get(address).flatMap {
      case None => IO.pure(None)
      case Some(rt) =>
        rt.actorRef match
          case None =>
            IO.pure(Some(Left(ToolError(s"Project '$address' has no mounted ProjectActor — re-mount it"))))
          case Some(ref) =>
            val rootSid = ctx.rootSessionId.orElse(ctx.sessionId).getOrElse("")
            (ref ! ProjectActor.ProjectCommand.TriggerDispatcher(message, rootSid)).void
              .as(Some(Right(s"Project '$address' dispatcher triggered")))
    }

  private[tools] def deliverQueue(
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
            // Mail(→project) 路由（#28 阶段 0，§3.2 试点期新旧并存）：
            // project 名 → ProjectActor.TriggerDispatcher（触发分发器会话）。
            // 团队名路由优先（旧体系照常）；project 名兜底（新体系试点）。
            // Mail 仅做触发、无回报——节点结果沿 out 边投递（§2.7），不靠 Mail。
            pr <- routeToProject(address, message, ctx).flatMap {
              case Some(r) => IO.pure(r)
              case None =>
                for
                  senderTeamOpt <- TeamSessionRegistry.teamOfSession(senderSessionId)
                  sr <- senderTeamOpt match
                    case None if address != "Nebula" =>
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
            }
          yield pr
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

  /** Persist to MailQueueStore, activate target, send MailQueued command.
    * private[tools] for ColdQueueActivationSpec (issue #22). */
  private[tools] def queueToSession(
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
          // 3. Get or activate the target actor (#22: with liveness probe —
          // a stale dead ref would swallow MailQueued into an unconsumed queue)
          refOpt <- MailTool.this.liveActorOrActivate(sessionId, resources, actorSystem, ctx)
          // 4. Send MailQueued command (triggers drain on the live actor)
          _ <- refOpt.traverse_(_ ! AgentCommand.MailQueued(item, senderSessionId))
          _ <- IO {
            if refOpt.isDefined then
              logger.info(
                s"[mail] queue mail delivered to $shortName (session=${sessionId.take(8)}, from=$senderName) — agent active, drain triggered"
              )
          }
          // 4+5. Emit WS event regardless of activation outcome (the item IS
          // persisted either way — the frontend should see the queue grow)
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
        yield refOpt match
          case Some(_) =>
            Right(s"Message queued to $shortName. Will be processed after current work completes (position #$pendingCount in queue).")
          case None =>
            // #22 honesty: activation failed (session meta or agent def not
            // loadable). The item IS on disk and will drain on the agent's
            // next successful activation — but claiming "will be processed"
            // unconditionally was a silent-loss lie.
            Left(ToolError(
              s"Message persisted to $shortName's queue (position #$pendingCount), but cold activation FAILED — session metadata or agent definition not found for session ${sessionId.take(8)}. " +
                s"The item will drain once the agent is loadable (check the agent exists in its team). See logs: \"[mail] activation failed\"."
            ))
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

  private[tools] def deliverShortNameUnscoped(
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
          // Mail(→project) 路由（§3.2，immediate 路径对称）——先于旧 short-name 解析。
          for
            pr <- routeToProject(address, message, ctx).flatMap {
              case Some(r) => IO.pure(r)
              case None =>
                // Not a team name — short agent name. Routable only for senders
                // with a team context (same-team priority); outside a team, only
                // team names and "Nebula" are valid (user ruling 08-24 — the
                // team/agent escape hatch is closed for root senders).
                for
                  senderTeamOpt <- TeamSessionRegistry.teamOfSession(senderSessionId)
                  sr <- senderTeamOpt match
                    case None if address != "Nebula" =>
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
            }
          yield pr
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
          // Check if actor already running (#22: probe liveness — dead cached
          // ref would swallow ImmediateInput into an unconsumed queue)
          refOpt <- MailTool.this.liveActorOrActivate(sessionId, resources, actorSystem, ctx)
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

  /** getRunningActor + liveness probe (#22): a cached ref whose actor loop
    * has exited (crash / TTL / stop whose deathwatch cleanup missed) still
    * accepts offers into an unconsumed queue — a queue-mode Mail delivered to
    * it persists, sends MailQueued into the void, and NEVER activates the
    * agent: no error, no retry, silent loss (issue #22's mechanism). Probe
    * the actor system registry; dead ref → reactivate. */
  private def liveActorOrActivate(
      sessionId: String,
      resources: SharedResources,
      system: ActorSystem,
      ctx: ToolContext
  ): IO[Option[ActorRef[AgentCommand]]] =
    TeamSessionRegistry.getRunningActor(sessionId).flatMap {
      case Some(ref) =>
        system.isAlive(ref.path).flatMap {
          case true => IO.pure(Some(ref))
          case false =>
            logger.warn(
              s"[mail] stale actor ref for session=${sessionId.take(8)} — actor dead but actorMap kept it; reactivating (issue #22)"
            ) *> activateAgent(sessionId, resources, system, ctx)
        }
      case None =>
        // #407 fix（E2E 实证）：用户消息触发的 team 成员 spawn（ensureRootAgent）
        // 只写统一 agentRegistry、不写 TeamSessionRegistry.actorMap——只查 actorMap
        // 会误判「无 live actor」→ activateAgent 重复 spawn（双活：旧 actor 在途
        // turn 与恢复 actor 并存，queue Mail 的 gate 投递错位、turn 完成不 drain）。
        // 回退统一注册表找 live record（ref 非 null 且 actor 存活），命中则复用。
        resources.agentRegistry.get.flatMap { reg =>
          reg.get(sessionId).filter(_.ref != null) match
            case Some(rec) =>
              system.isAlive(rec.ref.path).flatMap {
                case true  => IO.pure(Some(rec.ref))
                case false => activateAgent(sessionId, resources, system, ctx)
              }
            case None => activateAgent(sessionId, resources, system, ctx)
        }
    }

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
      _ <- IO {
        if sessionOpt.isEmpty then
          logger.warn(s"[mail] activation failed: session ${sessionId.take(8)} not found in session store (issue #22 observability)")
      }
      refOpt <- sessionOpt.traverse_ { session =>
        val agentName = session.agentName.getOrElse("")
        for
          // P2: resolve the Nebula root session (permission-policy anchor) first
          // so the spawned agent inherits the root's policy bucket and its
          // InteractionRequests render in the Nebula window.
          parentSidOpt <- TeamSessionRegistry.parentSessionOf(session.flowName.getOrElse(""))
          rootSid = parentSidOpt.getOrElse(session.id)
          // Block 0 registration chain: member → its Manager; Manager → mounting
          // root; unknown → None (registration falls back to the caller sid).
          parentSid <- TeamSessionRegistry.parentForRecord(session.id)
          policyOpt <- resources.permissionPolicies.get.map(_.get(rootSid))
          safetyMode =
            policyOpt.map(p => nebflow.core.SafetyMode.toString(p.safetyMode)).getOrElse(session.safetyMode)
          entryOpt <- session.flowName match
            case Some(teamName) => EntityLoader.loadTeamAgent(teamName, agentName)
            case None => EntityLoader.loadAgent(agentName)
          _ <- IO {
            if entryOpt.isEmpty then
              logger.warn(
                s"[mail] activation failed: agent def '$agentName' not loadable (team=${session.flowName.getOrElse("-")}) for session ${sessionId.take(8)} (issue #22 observability)"
              )
          }
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
            // reset, prior context lost). Same pattern as the root-agent
            // restore (WebSocketRoutes.ensureRootAgent).
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
                    parentRef = ctx.agentActorRef,
                    // Block 0 registration chain (supervision trio §B2): a
                    // member's parent is its team Manager; the Manager's parent
                    // is the mounting root. Fallback = the activating caller.
                    parentSessionId = parentSid.getOrElse(ctx.sessionId.getOrElse(""))
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
                  logger.info(
                    s"team agent actor stopped: agent=$agentName session=$sessionId — registry unregistered, busy cleared"
                  ).as(Behaviors.stopped[SystemSignal])
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
        delivery = Some("immediate"),
        // ② 服务端注入（agent→agent 邮件），不是真人输入 —— 显式表态。
        fromUser = false
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
