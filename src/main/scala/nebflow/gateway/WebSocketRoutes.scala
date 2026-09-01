package nebflow.gateway

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.ActorSystem as NebulaActorSystem
import nebflow.agent.*
import nebflow.core.entity.EntityLoader
import nebflow.core.flow.{FlowTreeActor, FlowTreeRegistry, TeamSessionRegistry}
import nebflow.core.mcp.McpManager
import nebflow.core.schedule.FreezeSchedule.given
import nebflow.core.skill.SkillService
import nebflow.core.tools.{ToolContext, ToolRegistry}
import nebflow.core.{PathUtil, *}
import nebflow.llm.*
import nebflow.service.*
import nebflow.shared.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.{Charset, HttpRoutes, MediaType, Response, Status, StaticFile}

import scala.concurrent.duration.*
import scala.io.Source

class WebSocketRoutes(
  wsb: WebSocketBuilder2[IO],
  sessionService: SessionService,
  agentService: AgentService,
  configService: ConfigService.type,
  configRef: Ref[IO, NebflowServiceConfig],
  rateLimiter: RateLimiter,
  token: String,
  fileChangeTracker: FileChangeTracker,
  sessionStore: SessionStore,
  wsHub: WsHub,
  contextWindow: Int = Defaults.ContextWindow,
  sharedResources: SharedResources,
  mcpManager: McpManager,
  sttService: Option[SttService] = None
):
  private val logger = NebflowLogger.forName("nebflow.ws")

  /** #295 热更：setSttConfig 写配置后重建服务并替换此 Ref——transcribe 立即
    * 走新配置，无需重启。构造参数保留为初始值（GatewayMain 启动时装载）。 */
  private val sttServiceRef: Ref[IO, Option[SttService]] = Ref.unsafe(sttService)
  private val nebulaSystem = sharedResources.actorSystem

  /** Map of sessionId -> root AgentActor ref. Concurrent-safe via Ref. */
  private val rootAgents: Ref[IO, Map[String, nebflow.actor.ActorRef[AgentCommand]]] =
    Ref.unsafe(Map.empty)

  /** In-flight root-agent spawns (sessionId -> completion). WS connect now
    * eagerly spawns the root agent (⑦ team restore), so concurrent first-use
    * paths (connect + user message) must not double-spawn — the self-cloned
    * actor registry would end up with two live fibers for one session. */
  private val rootSpawnInFlight: Ref[IO, Map[String, Deferred[IO, Unit]]] =
    Ref.unsafe(Map.empty)

  /** Resolve agent definition: global agent → flow agent from disk → Nebula fallback. */
  private def resolveAgentDef(
    sessionId: String,
    metaOpt: Option[nebflow.shared.SessionMeta],
    sharedResources: SharedResources
  ): IO[AgentDef] =
    metaOpt.flatMap(_.agentName) match
      case Some(agentName) =>
        sharedResources.agentLibrary.get(agentName).flatMap {
          case Some(defn) => IO.pure(defn)
          case None =>
            metaOpt.flatMap(_.flowName) match
              case Some(fn) =>
                nebflow.core.entity.EntityLoader.loadTeamAgent(fn, agentName).flatMap {
                  case Some(entry) => IO.pure(entry.toAgentDef)
                  case None        => nebulaFallback(agentName)
                }
              case None => nebulaFallback(agentName)
        }
      case None =>
        sharedResources.agentLibrary.get("Nebula").flatMap {
          case Some(d) => IO.pure(d)
          case None => IO.raiseError(new RuntimeException("No default agent available"))
        }

  private def nebulaFallback(agentName: String): IO[AgentDef] =
    sharedResources.agentLibrary.get("Nebula").flatMap {
      case Some(d) => IO.pure(d)
      case None => IO.raiseError(new RuntimeException(s"Agent not found: $agentName, and no default agent"))
    }

  /** Get or create a root AgentActor for the given session. Idempotent. */
  private def ensureRootAgent(sessionId: String): IO[nebflow.actor.ActorRef[AgentCommand]] =
    rootAgents.get.flatMap { agents =>
      agents.get(sessionId) match
        case Some(ref) => IO.pure(ref)
        case None =>
          // Single-flight: WS connect + user message can race on first use.
          // The claimer spawns and completes the Deferred; joiners wait and
          // re-read the registry.
          rootSpawnInFlight.modify { inFlight =>
            inFlight.get(sessionId) match
              case Some(done) => (inFlight, Right(done))
              case None =>
                val done = Deferred.unsafe[IO, Unit]
                (inFlight + (sessionId -> done), Left(done))
          }.flatMap {
            case Right(done) =>
              done.get *> rootAgents.get.map(_.get(sessionId)).flatMap {
                case Some(ref) => IO.pure(ref)
                case None =>
                  // Claimer failed before registering — retry once.
                  rootSpawnInFlight.update(_ - sessionId) *> ensureRootAgent(sessionId)
              }
            case Left(done) =>
              doSpawnRootAgent(sessionId)
                .guaranteeCase {
                  case cats.effect.Outcome.Succeeded(_) =>
                    rootSpawnInFlight.update(_ - sessionId) *>
                      done.complete(()).void.handleErrorWith(_ => IO.unit)
                  case _ =>
                    rootSpawnInFlight.update(_ - sessionId) *>
                      done.complete(()).void.handleErrorWith(_ => IO.unit)
                }
          }
    }

  /** The actual spawn half of ensureRootAgent (single-flight). */
  private def doSpawnRootAgent(sessionId: String): IO[nebflow.actor.ActorRef[AgentCommand]] =
    val broadcastWsSend = (json: io.circe.Json) => wsHub.broadcast(json)
    val recordingWsSend = makeRecordingWsSend(sessionId, broadcastWsSend)
    val agentIo = for
      history <- sharedResources.sessionStore.loadMessagesForSession(sessionId)
      metaOpt <- sharedResources.sessionStore.getSessionMeta(sessionId)
      agentDef <- resolveAgentDef(sessionId, metaOpt, sharedResources)
      readTracker <- nebflow.core.tools.ReadTracker.create
      fileHistory <- nebflow.core.tools.FileHistory.create()
      modelOverrides <- sharedResources.sessionModelOverrides.get
      contextWindow = modelOverrides.get(sessionId).map(_.contextWindow).getOrElse(sharedResources.contextWindow)
      // Resolve folder-level projectRoot and inherited rules
      folderId = metaOpt.flatMap(_.folderId)
      resolvedProjectRoot <- sharedResources.sessionStore.resolveProjectRoot(folderId)
      agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
      // Compute effective projectRoot: folder setting → unified ~/.nebflow/projects
      effectiveProjectRoot <- resolvedProjectRoot match
        case Some(pr) => IO.pure(Some(pr))
        case None =>
          val projectsDir = PathUtil.dataRoot / "projects"
          IO.blocking {
            if !os.exists(projectsDir) then os.makeDir.all(projectsDir)
          }.as(Some(projectsDir.toString))
      // F1 (#433): global safety mode from nebflow.json `safety.defaultMode`
      // is the default when session meta carries no explicit value — the
      // user's global auto-all must reach every seeding site, not just the
      // session where it was set (hot-read, same source as permissionDecision
      // bucket-miss fallback).
      globalMode <- nebflow.core.GlobalSafety.defaultMode
      // Resolve inherited rules from folder chain
      resolvedRules = folderId.map { fid =>
        nebflow.service.RulesStore.resolveInheritedRules(
          fid,
          id => sharedResources.sessionStore.getFolderParentId(id)
        )
      }.flatten
      ref <- nebulaSystem.spawn(
        AgentActor(
          agentDef,
          sharedResources,
          recordingWsSend,
          depth = 0,
          parentRef = None,
          sessionId = Some(sessionId),
          sessionName = metaOpt.map(_.name),
          initialMessages = history,
          readTracker = Some(readTracker),
          fileHistory = Some(fileHistory),
          contextWindow = contextWindow,
          projectRoot = effectiveProjectRoot,
          rulesMd = resolvedRules,
          folderId = folderId,
          safetyMode = metaOpt.map(_.safetyMode)
            .getOrElse(nebflow.core.SafetyMode.toString(globalMode)),
          gitBranch = metaOpt.flatMap(_.gitBranch),
          rootSessionId = sessionId
        ),
        s"agent-$sessionId"
      )
      pr = effectiveProjectRoot.getOrElse("")
      safetyMode = metaOpt.map(_.safetyMode).getOrElse(nebflow.core.SafetyMode.toString(globalMode))
    yield (ref, pr, safetyMode)
    agentIo.flatMap { case (ref, pr, safetyMode) =>
      val hookCtx = nebflow.core.hooks.HookContext(
        sessionId = Some(sessionId),
        projectRoot = pr,
        cwd = pr
      )
      sharedResources.hookEngine
        .onSessionStart(hookCtx)
        .handleErrorWith { e =>
          nebflow.core.NebflowLogger
            .forName("nebflow.hooks")
            .warn(s"SessionStart hook failed: ${e.getMessage}")
            .as(nebflow.core.hooks.HookResult.allow)
        }
        .void *>
        rootAgents.update(_ + (sessionId -> ref)) *>
        sharedResources.agentRegistry.update(
          _ + (
            sessionId -> AgentRecord(
              sessionId = sessionId,
              ref = ref,
              kind = AgentKind.Root,
              rootSessionId = sessionId,
              parentRef = None
            )
          )
        ) *>
        // P2: register this root session as the InteractionHub render
        // target (cards/questions appear in the Nebula window) and seed
        // its permission-policy bucket from persisted session meta
        // (backward compatible with per-session safetyMode).
        registerRootInteraction(sessionId, recordingWsSend) *>
        seedPermissionPolicy(sessionId, safetyMode) *>
        initFlowTree(sessionId, ref, pr, safetyMode)
          .handleErrorWith(e => logger.error(s"initFlowTree failed for session $sessionId: ${e.getMessage}"))
          .start
          .as(ref)
    }

  /**
   * P2: register a root session's recording wsSend with the InteractionHub so
   * permission/AskUser cards render in the Nebula window and persist to the
   * root session's ui.json.
   */
  private def registerRootInteraction(sessionId: String, wsSend: io.circe.Json => IO[Unit]): IO[Unit] =
    sharedResources.interactionHubRef.get.flatMap {
      case Some(hub) => (hub ! nebflow.agent.InteractionHubCommand.RegisterRoot(sessionId, wsSend)).void
      case None => IO.unit
    }

  private def unregisterRootInteraction(sessionId: String): IO[Unit] =
    sharedResources.interactionHubRef.get.flatMap {
      case Some(hub) => (hub ! nebflow.agent.InteractionHubCommand.UnregisterRoot(sessionId)).void
      case None => IO.unit
    }

  /** P2: seed the root session's permission-policy bucket from persisted meta. */
  private def seedPermissionPolicy(sessionId: String, safetyMode: String): IO[Unit] =
    val mode = nebflow.core.SafetyMode.fromString(safetyMode)
    sharedResources.permissionPolicies.update(_ + (sessionId -> nebflow.agent.PermissionPolicy(safetyMode = mode)))

  /** 递进式放行链 (2026-08-30): persist + hot-apply an escalated safety mode.
    *
    * Scope is aligned with the existing `setSafetyMode` WS command: session
    * meta (persisted, restored on reconnect via seedPermissionPolicy) + the
    * in-memory policy bucket + the agent's own copy. Order matters — the
    * bucket (the decision source) is updated BEFORE the caller forwards the
    * answer, so the next tool decision already sees the new mode.
    */
  private def applyPermissionUpgrade(sessionId: String, mode: nebflow.core.SafetyMode): IO[Unit] =
    val modeStr = nebflow.core.SafetyMode.toString(mode)
    for
      _ <- sessionStore.setSafetyMode(sessionId, modeStr)
      rootSid <- resolveRootSessionId(sessionId)
      _ <- sharedResources.permissionPolicies.update(_ + (rootSid -> nebflow.agent.PermissionPolicy(safetyMode = mode)))
      _ <- ensureAgent(sessionId)(ref => ref ! nebflow.agent.AgentCommand.SetSafetyMode(mode))
      _ <- logger.info(s"Permission upgrade: session $sessionId → $modeStr (persisted + bucket + agent)")
    yield ()

  /** Create and register a FlowTreeActor for a session. Fire-and-forget via .start. */
  private def initFlowTree(
    sessionId: String,
    agentRef: nebflow.actor.ActorRef[AgentCommand],
    projectRoot: String,
    safetyMode: String = "confirm-edits"
  ): IO[Unit] =
    val config = FlowTreeActor.TreeConfig(
      parentAgentRef = agentRef,
      wsSend = Some(makeRecordingWsSend(sessionId, (json: Json) => wsHub.broadcast(json))),
      sessionId = Some(sessionId),
      resources = sharedResources,
      projectRoot = projectRoot,
      safetyMode = safetyMode
    )
    for
      // ⑦ (2026-08-24): mark restore in flight BEFORE spawn so a concurrent
      // GET /api/teams/mounted (awaitRestore) waits for restoreTeams to
      // finish instead of fast-pathing past it and returning an empty list.
      // The completion signal always fires (signalRestoreComplete in the
      // actor setup, even for a zero-team home), so the wait is bounded to
      // the restore itself — no fixed stall.
      _ <- FlowTreeRegistry.markRestoreStarted
      treeRef <- nebulaSystem.spawn(FlowTreeActor(config), s"flow-tree-$sessionId")
      _ <- FlowTreeRegistry.register(sessionId, treeRef)
      _ = logger.info(s"FlowTreeActor created for session $sessionId (pipelines auto-restored on startup)")
    yield ()

  end initFlowTree

  /** Stop and remove the root AgentActor for a session. */
  private def removeRootAgent(sessionId: String): IO[Unit] =
    FlowTreeRegistry.unregister(sessionId) *>
      sharedResources.agentRegistry.update(_ - sessionId) *>
      unregisterRootInteraction(sessionId) *>
      rootAgents.modify { agents =>
        agents.get(sessionId) match
          case Some(ref) =>
            (
              agents - sessionId,
              ref ! AgentCommand.Stop(s"session $sessionId deleted")
            )
          case None => (agents, IO.unit)
      }.flatten

  /** #38 Layer C (2026-09-01): team 成员会话删除闭环。
    *
    * 此前 deleteSession 只停 root agent（removeRootAgent）+ 删磁盘文件；
    * team 成员 actor（qa-backend 等，注册于 TeamSessionRegistry.actorMap）
    * 完全存活——state.messages 持 ~10MB，下一次活动（Mail/flow dispatch）
    * 触发 persistIfSession 2s 防抖 → <id>.json 以 10MB 重建 → 删除复活
    * （观测：deleteSession 后 ~12s 历史灌回）。修复：删除前停成员 actor +
    * 清 TeamSessionRegistry 三处映射（actorMap / AgentRegistry / sessionMap）。
    */
  private def stopTeamSessionActors(sessionId: String): IO[Unit] =
    for
      actorOpt <- TeamSessionRegistry.getRunningActor(sessionId)
      _ <- actorOpt match
        case Some(ref) =>
          logger.info(s"deleteSession: stopping team member actor for $sessionId")
          IO(ref ! AgentCommand.Stop(s"session $sessionId deleted"))
        case None => IO.unit
      _ <- TeamSessionRegistry.unregisterActor(sessionId, sharedResources)
      pairOpt <- TeamSessionRegistry.instanceAndAgentOfSession(sessionId)
      _ <- pairOpt match
        case Some((inst, agent)) => TeamSessionRegistry.unregisterAgent(inst, agent, sessionId)
        case None                => IO.unit
    yield ()

  /**
   * P2: translate a frontend interaction answer (permissionAnswer/askUserAnswer)
   * into an InteractionAnswered for the hub. New frontends attach requestId →
   * the hub matches exactly (multi-slot). Old frontends omit requestId → the
   * hub falls back to the oldest pending request for the resolved root session
   * (incremental compatibility; the card is always rendered at sessionId =
   * rootSessionId). Answers never touch routeToAgent, so no ghost agent can
   * ever be created by an answer (D2).
   */
  private def forwardInteractionAnswer(requestId: String, sessionId: String, payload: io.circe.Json): IO[Unit] =
    if sessionId.nonEmpty then
      sharedResources.interactionHubRef.get.flatMap {
        case Some(hub) =>
          for
            rootSid <- resolveRootSessionId(sessionId)
            _ <- (hub ! nebflow.agent.InteractionHubCommand.Answered(
              nebflow.agent.InteractionAnswered(requestId, rootSid, payload)
            )).void
          yield ()
        case None =>
          logger.warn("Interaction answer dropped: InteractionHub not spawned") *> IO.unit
      }
    else logger.warn("Dropping interaction answer: no sessionId provided") *> IO.unit

  /** Resolve the permission-policy bucket (root session) of a sessionId. */
  private def resolveRootSessionId(sessionId: String): IO[String] =
    sharedResources.agentRegistry.get
      .map(_.get(sessionId).map(_.rootSessionId).filter(_.nonEmpty).getOrElse(sessionId))

  /**
   * Route a message to a registered agent — **lookup only, never spawns**.
   * Interaction answers (permissionAnswer / askUserAnswer) must use this so an
   * unregistered sessionId cannot create a ghost agent (D2). If the session is
   * not in the unified AgentRegistry the message is logged and dropped.
   */
  private def routeToAgent(sessionId: String)(f: nebflow.actor.ActorRef[AgentCommand] => IO[Unit]): IO[Unit] =
    if sessionId.nonEmpty then
      sharedResources.agentRegistry.get.flatMap { registry =>
        registry.get(sessionId) match
          case Some(record) =>
            f(record.ref).handleErrorWith { e =>
              logger.warn(s"Failed to route message to agent $sessionId: ${e.getMessage}")
            }
          case None =>
            logger.warn(s"Dropping message to unregistered agent session $sessionId (no ghost spawn)") *> IO.unit
      }
    else logger.warn("Dropping message: no sessionId provided") *> IO.unit

  /**
   * Route a message to an agent, activating the root agent when the session is a
   * real persisted root session (user messages / plan / interrupt). Never spawns
   * a ghost for unknown sessionIds — such messages are logged and dropped.
   */
  private def ensureAgent(sessionId: String)(f: nebflow.actor.ActorRef[AgentCommand] => IO[Unit]): IO[Unit] =
    if sessionId.nonEmpty then
      sharedResources.agentRegistry.get.flatMap { registry =>
        registry.get(sessionId) match
          case Some(record) =>
            f(record.ref).handleErrorWith { e =>
              logger.warn(s"Failed to route message to agent $sessionId: ${e.getMessage}")
            }
          case None =>
            sessionStore.getSessionMeta(sessionId).flatMap {
              case Some(_) =>
                ensureRootAgent(sessionId).flatMap(f).handleErrorWith { e =>
                  logger.warn(s"Failed to route message to agent for session $sessionId: ${e.getMessage}")
                }
              case None =>
                logger.warn(s"Dropping message to unknown session $sessionId (not a real session)") *> IO.unit
            }
      }
    else logger.warn("Dropping message: no sessionId provided") *> IO.unit

  /**
   * Public API for external sources (e.g. bridge plugins) to inject a user message
   * into a session's agent. Reuses the same logic as WebSocket user messages:
   * rate limiting, UiMessage recording, agent routing.
   */
  def handleBridgeMessage(sessionId: String, content: String, senderId: Option[String] = None): IO[Unit] =
    if content.isEmpty || sessionId.isEmpty then IO.unit
    else
      rateLimiter.check("bridge").flatMap { allowed =>
        if !allowed then logger.warn("Bridge rate limit exceeded")
        else
          val source = senderId.map(id => s"[via bridge:$id]").getOrElse("[via bridge]")
          logger.info(s"Bridge message for session $sessionId: ${content.take(60)}... $source") *>
            // Record as UiMessage
            sharedResources.sessionStore
              .appendUiMessages(sessionId, List(UiMessage.User(content, Nil, timestamp = System.currentTimeMillis())))
              .handleErrorWith(e => logger.warn(s"Failed to record bridge UiMessage: ${e.getMessage}")) *>
            // Push to frontend in real-time so it shows without switching sessions
            wsHub.broadcast(
              io.circe.Json.obj(
                "type" -> "bridgeUser".asJson,
                "sessionId" -> sessionId.asJson,
                "text" -> content.asJson
              )
            ) *>
            // Use ExternalEvent instead of UserInput so the message is queued in
            // pendingEvents and can be injected at the next ToolsComplete gap —
            // avoids being stashed until the entire turn finishes.
            ensureAgent(sessionId)(ref =>
              ref ! AgentCommand.ExternalEvent(
                source = "bridge",
                eventType = "user-message",
                payload = content,
                metadata = io.circe.JsonObject("senderId" -> senderId.asJson),
                correlationId = None
              )
            )
      }

  /**
   * Public API for bridge card-action callbacks to send specific AgentCommands
   * (e.g. Interrupt, PlanApproved) to a session's agent.
   */
  def handleBridgeAgentCommand(sessionId: String, command: AgentCommand): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else ensureAgent(sessionId)(ref => ref ! command)

  def routes: HttpRoutes[IO] =
    WebSocketRoutes.uploadsRoutes(token) <+> WebSocketRoutes.jsRoutes <+> WebSocketRoutes.assetsRoutes <+> HttpRoutes.of[IO] {
    case req @ GET -> Root / "ws" =>
      // Cookie takes priority to avoid token leakage in browser history/logs/Referer.
      // Query param kept as fallback for cross-origin or first-load scenarios.
      val cookieToken = req.cookies.find(_.name == "nebflow_token").map(_.content).getOrElse("")
      val paramToken = req.params.get("token").getOrElse("")
      // A stale cookie (e.g. written by an older build with Secure/domain
      // attributes, so a fresh document.cookie write cannot replace it) must
      // not lock the client out: if the cookie does not validate but a valid
      // ?token= is presented, accept it.
      if Auth.validateToken(cookieToken, token) || Auth.validateToken(paramToken, token) then
        for
          outbound <- Queue.unbounded[IO, WebSocketFrame]

          perConnWsSend = (json: io.circe.Json) => outbound.offer(WebSocketFrame.Text(json.noSpaces))
          hubConnId <- wsHub.register(perConnWsSend)

          receivePipe: Pipe[IO, WebSocketFrame, Unit] = _.evalMap {
            case WebSocketFrame.Text(text, _) =>
              handleMessage(text, perConnWsSend).handleErrorWith { e =>
                logger.error(s"WebSocket message handler error: ${e.getMessage}", e)
                IO.unit
              }
            case _ => IO.unit
          }.onFinalize(
            wsHub.unregister(hubConnId)
          )

          // sendStream: read from outbound queue, send via WebSocket.
          // Client disconnects are expected (browser tab close, network change) and
          // produce IOException during write. Catch and swallow gracefully.
          sendStream = Stream
            .fromQueueUnterminated(outbound)
            .handleErrorWith { e =>
              Stream.eval(logger.debug(s"WebSocket send stream closed: ${e.getMessage}")).drain
            }
          _ <- logger.info("WebSocket client connected")
          thinkingCfg <- sharedResources.thinkingConfigRef.get
          workScheduleCfg <- sharedResources.freezeScheduleRef.get
          skipUntil <- sharedResources.freezeSkipUntilRef.get
          sttSvc <- sttServiceRef.get
          toolsList = ToolRegistry.ALL_TOOLS.map(t =>
            io.circe.Json.obj("name" -> t.name.asJson, "description" -> t.description.asJson)
          )
          mcpServers <- mcpManager.listServers.map(_.map { case (id, enabled) =>
            io.circe.Json.obj("id" -> id.asJson, "enabled" -> enabled.asJson)
          })
          _ <- outbound.offer(
            WebSocketFrame.Text(
              io.circe.Json
                .obj(
                  "type" -> "serverConfig".asJson,
                  "streamTimeoutMs" -> (Defaults.StreamTimeoutSec.toLong * 1000).asJson,
                  "version" -> nebflow.Version.string.asJson,
                  "thinking" -> thinkingCfg.asJson,
                  "workSchedule" -> workScheduleCfg.asJson,
                  // 现象 2 契约（2026-08-30）：连接初始态即携带全局冻结状态——
                  // 页面加载时若已在冻结窗口（且无活跃 agent 产生 frozen 事件），
                  // 前端输入栏禁用状态机必须能直接读到当前冻结态。
                  "freezeState" -> nebflow.core.schedule.FreezeSchedule.freezeStateNode(
                    workScheduleCfg,
                    skipUntil,
                    System.currentTimeMillis()
                  ).asJson,
                  "stt" -> SttService.serverConfigNode(sttSvc),
                  "tools" -> toolsList.asJson,
                  "mcpServers" -> mcpServers.asJson
                )
                .noSpaces
            )
          )
          activeMeta <- sessionStore.getActiveMeta
          agentName = activeMeta.flatMap(_.agentName).getOrElse("Nebula")
          // ⑦ (2026-08-24): restore mounted teams right on WS connect — not
          // only on first user message. Team mount recovery lives in
          // FlowTreeActor startup (restoreTeams → TeamSessionRegistry), and
          // the actor is only created alongside the root agent. After a
          // restart, a client that connects but sends no message would leave
          // /api/teams/mounted empty → "No active teams" panel. ensureRootAgent
          // is idempotent (existing ref is returned) and chains initFlowTree
          // on first spawn, so a plain call covers both the cold start (new
          // agent → tree → restore) and the warm path (existing agent/tree →
          // no-op).
          _ <- activeMeta.traverse_(meta =>
            ensureRootAgent(meta.id).void.handleErrorWith(e =>
              logger.warn(s"WS-connect team restore failed for session ${meta.id}: ${e.getMessage}")
            )
          )
          _ <- sessionService.sendSessionList(perConnWsSend, agentName)
          ws <- wsb.build(sendStream, receivePipe)
        yield ws
      else
        logger.warn(
          s"WebSocket auth failed from ${req.remoteAddr.getOrElse("unknown")}"
        ) *>
          Forbidden("Invalid token")
      end if

    // --- Callback endpoint (外→内) ---
    // POST /api/callbacks/inject — agent-centric, authenticated via gateway token
    case req @ POST -> Root / "api" / "callbacks" / "inject" =>
      val provided = extractToken(req)
      if !Auth.validateToken(provided, token) then Forbidden("Invalid token")
      else handleInject(req)

    // --- Local file serving for card iframes ---
    // GET /api/nf-file?path=xxx&token=xxx — serves whitelisted media files from disk.
    // Used by card iframes to display local images/videos/audio without base64 embedding.
    // Supports Range requests for video seeking.
    case req @ GET -> Root / "api" / "nf-file" =>
      val provided = extractToken(req)
      if !Auth.validateToken(provided, token) then Forbidden("Invalid token")
      else
        val rawPath = req.params.get("path").getOrElse("")
        if rawPath.isEmpty then BadRequest("Missing 'path' parameter")
        else
          // Resolve and validate path
          val expanded = if rawPath.startsWith("~") then sys.props("user.home") + rawPath.substring(1) else rawPath
          val path = java.nio.file.Paths.get(expanded).normalize()
          val ext = path.toString.lastIndexOf('.') match
            case -1 => ""
            case i => path.toString.substring(i + 1).toLowerCase
          // Only allow whitelisted media extensions
          val allowedExt = Set(
            "png",
            "jpg",
            "jpeg",
            "gif",
            "svg",
            "webp",
            "ico",
            "bmp",
            "avif",
            "tiff",
            "tif",
            "mp4",
            "webm",
            "ogg",
            "ogv",
            "mov",
            "mp3",
            "wav",
            "oga",
            "flac",
            "aac",
            "m4a",
            "woff",
            "woff2",
            "ttf",
            "otf",
            "pdf",
            "docx",
            "xlsx",
            "xlsm",
            "pptx",
            "epub"
          )
          if !allowedExt.contains(ext) then BadRequest("File type not allowed")
          else if !java.nio.file.Files.exists(path) || !java.nio.file.Files.isRegularFile(path) then NotFound()
          else StaticFile.fromPath(fs2.io.file.Path(path.toString), Some(req)).getOrElseF(NotFound())
        end if
      end if

    case GET -> Root =>
      // P1 single switch point: when the esbuild dist is packed on the
      // classpath (sbt -Dnebflow.webdist=1 assembly), "/" serves the bundled
      // dist entry; otherwise the dev source tree entry. Everything else the
      // dist index references is either under /assets (assetsRoutes) or a
      // byte-identical passthrough (vendor, fonts, icons) served by the
      // existing web/ routes — no fallback logic, the entry and its hashed
      // deps come from one atomic build-web.mjs run.
      //
      // L1 rebrand: both entries go through indexWithBrand, which injects
      // the window.__BRAND__ contract before the closing head tag. The
      // served body therefore differs from the classpath bytes, which is
      // why this route no longer uses StaticFile's conditional-request
      // handling (a jar-entry Last-Modified must not vouch for content we
      // mutated) — the response stays no-cache with no validators.
      val indexResource =
        if WebSocketRoutes.hasBundledDist then "web-dist/index.html" else "web/index.html"
      WebSocketRoutes.indexWithBrand(indexResource)

    case HEAD -> Root =>
      // Headers-only parity with the pre-rebrand StaticFile route (curl -I,
      // health/link checkers). The GET case above does not match HEAD
      // requests, and the body-stripped variant must go through the same
      // content generation so validators never diverge between the verbs.
      val headIndexResource =
        if WebSocketRoutes.hasBundledDist then "web-dist/index.html" else "web/index.html"
      WebSocketRoutes.indexWithBrand(headIndexResource).map(_.withBodyStream(Stream.empty))

    case req @ GET -> Root / "css" / file =>
      StaticFile
        .fromResource(s"web/css/$file", Some(req))
        .map(_.putHeaders("Cache-Control" -> "no-cache"))
        .getOrElseF(NotFound())

    // /js/** (any depth) is served by WebSocketRoutes.jsRoutes in the
    // companion — see its scaladoc for why the DSL single-segment routes
    // (and the per-directory cases they grew over time) were replaced.

    case req @ GET -> _ if req.uri.path.renderString.startsWith("/vendor/monaco/") =>
      // Serve monaco editor files from bundled resources (supports nested paths).
      // Uses manual path parsing because http4s DSL only matches single path segments.
      val segs = req.uri.path.segments.map(_.encoded).toList
      if segs.sizeIs < 3 then NotFound()
      else
        val relParts = segs.drop(2) // drop "vendor" and "monaco"
        // Block path traversal
        if relParts.exists(s => s == ".." || s.contains("\\")) then NotFound()
        else
          val path = relParts.mkString("/")
          StaticFile.fromResource(s"web/vendor/monaco/$path", Some(req)).getOrElseF(NotFound())
      end if

    case req @ GET -> _ if req.uri.path.renderString.startsWith("/vendor/pdfjs/") =>
      // Serve pdfjs viewer files (pdf.min.js / pdf.worker.min.js) from bundled
      // resources. The single-segment vendor case below cannot match this
      // two-level path (/vendor/pdfjs/pdf.min.js), and the fonts/monaco cases
      // are directory-specific — without this case every pdfjs file 404s and
      // the PDF viewer fails to boot (pdfjsLib undefined). Same guard pattern
      // as the monaco case above (manual path parsing, traversal blocked).
      val segs = req.uri.path.segments.map(_.encoded).toList
      if segs.sizeIs < 3 then NotFound()
      else
        val relParts = segs.drop(2) // drop "vendor" and "pdfjs"
        // Block path traversal
        if relParts.exists(s => s == ".." || s.contains("\\")) then NotFound()
        else
          val path = relParts.mkString("/")
          StaticFile.fromResource(s"web/vendor/pdfjs/$path", Some(req)).getOrElseF(NotFound())
      end if

    case req @ GET -> Root / "vendor" / file =>
      StaticFile.fromResource(s"web/vendor/$file", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / "vendor" / "fonts" / file =>
      StaticFile.fromResource(s"web/vendor/fonts/$file", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / fileName =>
      val allowed =
        Set("style.css", "app.js", "favicon.svg", "logo.svg", "favicon-32.png", "favicon-16.png", "favicon.ico")
      if allowed.contains(fileName) then
        StaticFile
          .fromResource(s"web/$fileName", Some(req))
          .map(_.putHeaders("Cache-Control" -> "no-cache"))
          .getOrElseF(NotFound())
      else NotFound()

    case req @ GET -> Root / "agents" / "manifest.json" =>
      sharedResources.agentLibrary.loadAll().flatMap { agents =>
        val json = io.circe.Json.obj(
          "agents" -> agents.values.toList.map { a =>
            io.circe.Json.obj(
              "name" -> a.name.asJson,
              "description" -> a.description.asJson,
              "displayName" -> a.displayName.getOrElse(a.name).asJson,
              "avatar" -> a.avatar.asJson
            )
          }.asJson
        )
        Ok(json.noSpaces, org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json))
      }

    case req @ GET -> _ if req.uri.path.renderString.startsWith("/agents/") =>
      // Serve static files under agent directory (supports nested paths)
      // Uses manual path parsing because http4s DSL only matches single path segments
      val segs = req.uri.path.segments.map(_.encoded).toList
      if segs.sizeIs < 3 then NotFound()
      else
        val agentName = segs(1)
        // Block path traversal: agentName must be a simple name (no .., /, \)
        if agentName.contains("..") || agentName.contains("/") || agentName.contains("\\") then NotFound()
        else
          val agentDir = AgentLibrary.defaultDir / agentName
          val relParts = segs.drop(2)
          // Block path traversal in relative parts
          val safeRel = relParts.filter(s => s != ".." && !s.contains("\\"))
          if safeRel.length != relParts.length then NotFound()
          else
            val filePath = agentDir / os.RelPath(safeRel.mkString("/"))
            // Final defense: resolve and verify the path stays under agentDir
            if filePath.startsWith(agentDir) && os.exists(filePath) && os.isFile(filePath) then
              StaticFile.fromPath(fs2.io.file.Path(filePath.toString), Some(req)).getOrElseF(NotFound())
            else NotFound()
      end if

    case req @ GET -> _ if req.uri.path.renderString.startsWith("/voice-models/") =>
      // Serve pre-downloaded voice model files from ~/.nebflow/voice-models/
      // Allows offline Whisper inference without CDN dependency.
      val segs = req.uri.path.segments.map(_.encoded).toList
      if segs.sizeIs < 2 then NotFound()
      else
        val relParts = segs.drop(1) // drop "voice-models"
        // Block path traversal
        if relParts.exists(s => s == ".." || s.contains("\\")) then NotFound()
        else
          val modelBase = PathUtil.dataRoot / "voice-models"
          val filePath = modelBase / os.RelPath(relParts.mkString("/"))
          if filePath.startsWith(modelBase) && os.exists(filePath) && os.isFile(filePath) then
            StaticFile.fromPath(fs2.io.file.Path(filePath.toString), Some(req)).getOrElseF(NotFound())
          else NotFound()
  }

  private val inputHistoryPath = PathUtil.dataRoot / "input_history.jsonl"

  private def logInputHistory(
    content: String,
    attachments: List[io.circe.Json],
    sessionId: String,
    sessionName: String,
    agentName: String
  ): IO[Unit] =
    val filtered = content.trim.toLowerCase
    if (filtered == "quit" || filtered == "exit") && attachments.isEmpty then IO.unit
    else if content.trim.isEmpty && attachments.isEmpty then IO.unit
    else
      IO.blocking {
        val entry = InputHistory.buildEntry(
          content,
          attachments,
          sessionId,
          sessionName,
          agentName,
          java.time.LocalDateTime.now()
        )
        os.write.append(inputHistoryPath, entry.noSpaces + "\n", createFolders = true)
      }

    end if

  end logInputHistory

  private def broadcastServerConfig: IO[Unit] =
    val toolsList =
      ToolRegistry.ALL_TOOLS.map(t => io.circe.Json.obj("name" -> t.name.asJson, "description" -> t.description.asJson))
    for
      thinkingCfg <- sharedResources.thinkingConfigRef.get
      workScheduleCfg <- sharedResources.freezeScheduleRef.get
      skipUntil <- sharedResources.freezeSkipUntilRef.get
      sttSvc <- sttServiceRef.get
      mcpServers <- mcpManager.listServers.map(_.map { case (id, enabled) =>
        io.circe.Json.obj("id" -> id.asJson, "enabled" -> enabled.asJson)
      })
      _ <- wsHub.broadcast(
        io.circe.Json.obj(
          "type" -> "serverConfig".asJson,
          "streamTimeoutMs" -> (Defaults.StreamTimeoutSec.toLong * 1000).asJson,
          "version" -> nebflow.Version.string.asJson,
          "thinking" -> thinkingCfg.asJson,
          "workSchedule" -> workScheduleCfg.asJson,
          // 现象 2 契约（2026-08-30）：全局冻结态节点（enabled/frozen/skipped/
          // nextChangeAt）——WS 连接初始态、配置热更、skipFreeze 后均随广播刷新，
          // 前端输入栏禁用状态机以此为准（含 skip 语义，避免按 workSchedule 自行
          // 推算时误判被跳过的窗口仍冻结）。
          "freezeState" -> nebflow.core.schedule.FreezeSchedule.freezeStateNode(
            workScheduleCfg,
            skipUntil,
            System.currentTimeMillis()
          ).asJson,
          "stt" -> SttService.serverConfigNode(sttSvc),
          "tools" -> toolsList.asJson,
          "mcpServers" -> mcpServers.asJson
        )
      )
    yield ()

    end for

  end broadcastServerConfig

  /** Persist thinking config to nebflow.json — targeted field update.
    * 冻结修复（2026-08-27）：不再吞写盘错误——失败必须上浮给调用方回执
    * configUpdateFailed，否则用户看到「已保存」而重启后配置回滚（off 假成功）。
    * 调用方负责 writeLocked 串行化。 */
  private def persistThinkingConfig(tc: ThinkingConfig): IO[Unit] =
    IO.blocking {
      // Read through the dual-read path (legacy fallback), write the brand
      // name — the first write completes the config-file rename migration.
      val existing = if os.exists(nebflow.llm.Config.DefaultConfigPath) then os.read(nebflow.llm.Config.DefaultConfigPath) else "{}"
      val path = PathUtil.configJsonWritePath(PathUtil.dataRoot)
      parse(existing).foreach { json =>
        val updated = json.mapObject { obj =>
          obj.add("thinkingConfig", tc.asJson)
        }
        os.write.over(path, updated.spaces2, createFolders = true)
      }
    }

  /** Persist freeze schedule to nebflow.json — targeted top-level write (照抄
    * persistThinkingConfig 的 read-merge-write 语义，merge 纯函数在
    * FreezeSchedule.mergeIntoConfig 便于 B9 测试）。JSON 键名保留 "workSchedule"（前端契约）。
    * 冻结修复（2026-08-27）：错误上浮不吞——调用方持锁调用并负责失败回执。 */
  private def persistWorkSchedule(cfg: nebflow.core.schedule.FreezeScheduleConfig): IO[Unit] =
    IO.blocking {
      val existing = if os.exists(nebflow.llm.Config.DefaultConfigPath) then os.read(nebflow.llm.Config.DefaultConfigPath) else "{}"
      val path = PathUtil.configJsonWritePath(PathUtil.dataRoot)
      parse(existing).foreach { json =>
        val updated = nebflow.core.schedule.FreezeSchedule.mergeIntoConfig(json, cfg)
        os.write.over(path, updated.spaces2, createFolders = true)
      }
    }

  /** Persist MCP server enabled state to nebflow.json — targeted field update.
    * 冻结修复（2026-08-27）：错误上浮不吞（同族 fail-loud）。调用方持锁。 */
  private def persistMcpServerEnabled(serverId: String, enabled: Boolean): IO[Unit] =
    IO.blocking {
      // Read through the dual-read path (legacy fallback), write the brand
      // name — the first write completes the config-file rename migration.
      val existing = if os.exists(nebflow.llm.Config.DefaultConfigPath) then os.read(nebflow.llm.Config.DefaultConfigPath) else "{}"
      val path = PathUtil.configJsonWritePath(PathUtil.dataRoot)
      parse(existing).foreach { json =>
        val updated = json.mapObject { obj =>
          val mcpObj = obj("mcpServers").flatMap(_.asObject).getOrElse(JsonObject.empty)
          val serverObj = mcpObj(serverId).flatMap(_.asObject).getOrElse(JsonObject.empty)
          val updatedServer = Json.fromJsonObject(serverObj.add("enabled", enabled.asJson))
          val updatedMcp = Json.fromJsonObject(mcpObj.add(serverId, updatedServer))
          obj.add("mcpServers", updatedMcp)
        }
        os.write.over(path, updated.spaces2, createFolders = true)
      }
    }

  /** Broadcast current MCP server list to all connected clients. */
  private def broadcastMcpServersUpdate: IO[Unit] =
    mcpManager.listServers.flatMap { servers =>
      val mcpJson = servers.map { case (id, enabled) =>
        io.circe.Json.obj("id" -> id.asJson, "enabled" -> enabled.asJson)
      }
      wsHub.broadcast(
        io.circe.Json.obj(
          "type" -> "mcpServersUpdate".asJson,
          "mcpServers" -> mcpJson.asJson
        )
      )
    }

  /** Send unified session list by looking up the session's agent name (for agentName field only). */
  private def sendAgentSessionList(wsSend: io.circe.Json => IO[Unit], sessionId: String): IO[Unit] =
    sessionStore.getSessionMeta(sessionId).flatMap { metaOpt =>
      val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
      sendAgentSessionListByName(wsSend, agentName)
    }

  /** Send unified session list (all agents) — keeps sessions isolated from filtering issues. */
  private def sendAgentSessionListByName(wsSend: io.circe.Json => IO[Unit], agentName: String): IO[Unit] =
    (sessionStore.listSessions, sessionStore.listAllFolders).flatMapN { (sessions, folders) =>
      val rulesFolderIds = folders.filter(f => nebflow.service.RulesStore.exists(f.id)).map(_.id)
      wsSend(
        io.circe.Json.obj(
          "type" -> "agentSessionList".asJson,
          "agentName" -> agentName.asJson,
          "sessions" -> sessions.asJson,
          "folders" -> folders.asJson,
          "foldersWithRules" -> rulesFolderIds.asJson
        )
      )
    }

  /** Push memory status for the current session to the frontend. */
  private def sendMemoryStatus(wsSend: io.circe.Json => IO[Unit], sessionId: String): IO[Unit] =
    sessionStore.getSessionMeta(sessionId).flatMap { metaOpt =>
      val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
      wsSend(
        io.circe.Json.obj(
          "type" -> "memoryStatus".asJson,
          "user" -> io.circe.Json.obj(
            "exists" -> MemoryStore.userExists.asJson,
            "preview" -> MemoryStore.userPreview.asJson
          ),
          "agent" -> io.circe.Json.obj(
            "exists" -> MemoryStore.agentExists(agentName).asJson,
            "preview" -> MemoryStore.agentPreview(agentName).asJson
          )
        )
      )
    }

  private val MaxMessageSize = 10 * 1024 * 1024 // 10MB (base64 images can be large)

  /** Public facade for REST API to call into the same message handler. */
  def handleMessagePublic(text: String, wsSend: io.circe.Json => IO[Unit]): IO[Unit] =
    handleMessage(text, wsSend)

  private def handleMessage(
    text: String,
    wsSend: io.circe.Json => IO[Unit]
  ): IO[Unit] =
    if text.length > MaxMessageSize then logger.warn(s"Dropping oversized WebSocket message (${text.length} bytes)")
    else
      val parsed = parse(text).toOption.getOrElse(io.circe.Json.Null)
      val sessionIdForTracking = parsed.hcursor.downField("sessionId").as[String].toOption.getOrElse("")
      val msgType = parsed.hcursor.downField("type").as[String].getOrElse("")
      for
        _ <- sharedResources.lastWsActivity.set(System.currentTimeMillis())
        _ <- nebflow.core.UsageTracker.record("ws_message", sessionIdForTracking)
        _ <- msgType match
          case "askUserAnswer" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val hc = json.hcursor
            (hc.downField("answers").as[List[String]], hc.downField("sessionId").as[String]) match
              case (Right(answers), Right(askSessionId)) =>
                val answerText = answers.mkString("\n")
                val requestId = hc.downField("requestId").as[String].toOption.getOrElse("")
                sessionStore.appendUiMessages(
                  askSessionId,
                  List(UiMessage.User(answerText, timestamp = System.currentTimeMillis()))
                ) *>
                  forwardInteractionAnswer(requestId, askSessionId, io.circe.Json.obj("answers" -> answers.asJson))
              case _ => IO.unit

          case "permissionAnswer" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val permSessionId = json.hcursor.downField("sessionId").as[String].toOption.getOrElse("")
            val requestId = json.hcursor.downField("requestId").as[String].toOption.getOrElse("")
            val upgradeRaw = json.hcursor.downField("upgradeMode").as[String].toOption
            // #12: NEVER synthesize a deny from a malformed reply — the old
            // getOrElse(false) turned any missing/non-boolean `approved` into
            // a user-attributed denial the user never clicked.
            json.hcursor.downField("approved").as[Boolean] match
              case Right(approved) =>
                // 递进式放行链 (2026-08-30): an invalid upgrade request never
                // transforms the answer (rule #12 spirit) — log and proceed as
                // the plain allow/deny the user actually clicked.
                nebflow.core.PermissionUpgrade.parse(approved, upgradeRaw) match
                  case Left(why) =>
                    logger.warn(
                      s"Permission answer upgradeMode IGNORED ($why) — proceeding as plain ${if approved then "allow" else "deny"} (requestId=$requestId)"
                    ) *>
                      forwardInteractionAnswer(requestId, permSessionId, io.circe.Json.obj("approved" -> approved.asJson))
                  case Right(upgrade) =>
                    val answerPayload = io.circe.Json.fromJsonObject(
                      io.circe.JsonObject.fromIterable(
                        Seq("approved" -> approved.asJson) ++
                          upgrade.map(m => "upgradeMode" -> io.circe.Json.fromString(nebflow.core.SafetyMode.toString(m)))
                      )
                    )
                    val upgradeIo = upgrade.fold(IO.unit)(m =>
                      applyPermissionUpgrade(permSessionId, m).handleErrorWith { e =>
                        logger.warn(s"Permission upgrade FAILED (proceeding as plain allow): ${e.getMessage}")
                      }
                    )
                    logger.info(
                      s"Permission answer: ${if approved then "approved" else "denied"}${upgrade.map(m => s" + upgrade→${nebflow.core.SafetyMode.toString(m)}").getOrElse("")}"
                    ) *> upgradeIo *> forwardInteractionAnswer(requestId, permSessionId, answerPayload)
              case Left(_) =>
                logger.warn(
                  s"Permission answer DROPPED: 'approved' missing or not a boolean (requestId=$requestId, session=$permSessionId) — a malformed reply must not become a deny (#12)"
                )

          case "planApprove" =>
            val planSessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).toOption.getOrElse("")
            logger.info(s"Plan approved for session $planSessionId") *>
              ensureAgent(planSessionId)(ref => ref ! AgentCommand.PlanApproved)

          case "planFeedback" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val fbSessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
            val fbText = json.hcursor.downField("text").as[String].getOrElse("")
            if fbSessionId.nonEmpty && fbText.nonEmpty then
              logger.info(s"Plan feedback for session $fbSessionId: ${fbText.take(60)}") *>
                ensureAgent(fbSessionId)(ref => ref ! AgentCommand.PlanFeedback(fbText))
            else IO.unit

          case "planCancel" =>
            val cancelSessionId =
              parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).toOption.getOrElse("")
            logger.info(s"Plan cancelled for session $cancelSessionId") *>
              ensureAgent(cancelSessionId)(ref => ref ! AgentCommand.PlanCancelled)

          case "interrupt" =>
            val intSessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).toOption.getOrElse("")
            logger.info("User interrupted") *> ensureAgent(intSessionId)(ref => ref ! AgentCommand.Interrupt())

          case "restartAgent" =>
            val rJson = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val rSessionId = rJson.hcursor.downField("sessionId").as[String].toOption.getOrElse("")
            val rLevel = rJson.hcursor.downField("level").as[String].toOption.getOrElse("soft") match
              case "rollback" => nebflow.agent.RestartLevel.Rollback
              case "prune" => nebflow.agent.RestartLevel.Prune
              case "full" => nebflow.agent.RestartLevel.Full
              case _ => nebflow.agent.RestartLevel.Soft
            if rSessionId.nonEmpty then
              logger.info(s"Restart agent (level=$rLevel) for session $rSessionId") *>
                ensureAgent(rSessionId)(ref => ref ! nebflow.agent.AgentCommand.RestartAgent(rLevel))
            else IO.unit

          case "cancelAgent" =>
            // 子 agent 管理面板（2026-08-22 缺口 1）：终止任务终态——复用
            // AgentControlTool.doCancel 同链路（supervisor Cancelled → barrier
            // 释放 + taskStore cancelled + Stop；无 supervisor 走降级兜底）。
            // 区别于 interrupt（只停当前 turn，任务可继续）。用户面板路径无
            // caller 概念（工具层的自杀/同桶守卫不适用），仅保留 kind 白名单。
            val cJson = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val cSessionId = cJson.hcursor.downField("sessionId").as[String].toOption.getOrElse("")
            val cReason = cJson.hcursor.downField("reason").as[String].toOption.getOrElse("")
            def cancelReply(ok: Boolean, extra: (String, Json)*): IO[Unit] =
              wsSend(
                io.circe.Json.obj(
                  Seq(
                    ("type", "cancelAgentResult".asJson),
                    ("ok", ok.asJson),
                    ("sessionId", cSessionId.asJson)
                  ) ++ extra*
                )
              )
            if cSessionId.isEmpty then
              cancelReply(ok = false, "error" -> "cancelAgent requires sessionId".asJson)
            else
              logger.info(s"cancelAgent (panel) for session $cSessionId") *>
                sharedResources.agentRegistry.get.flatMap { registry =>
                  registry.get(cSessionId) match
                    case None =>
                      cancelReply(
                        ok = false,
                        "error" ->
                          s"No live agent with sessionId='$cSessionId' (registry is in-memory; stale ids vanish after restart)".asJson
                      )
                    case Some(rec) if !nebflow.core.tools.AgentControlTool.CancelableKinds.contains(rec.kind) =>
                      cancelReply(
                        ok = false,
                        "error" -> s"Kind ${rec.kind} is read-only (cancelable: Delegate / SubTask / Ephemeral)".asJson
                      )
                    case Some(rec) =>
                      val reason = if cReason.nonEmpty then cReason else "cancelled from panel"
                      nebflow.core.tools.AgentControlTool
                        .doCancel(sharedResources, rec, reason)
                        .flatMap {
                          case Right(msg) => cancelReply(ok = true, "message" -> msg.asJson)
                          case Left(err)  => cancelReply(ok = false, "error" -> err.message.asJson)
                        }
                }

          case "parentRestart" =>
            // v2 冻结式错误恢复升级链（§5.3.3）：用户/父干预卡片——重启冻结中的
            // 子 agent（断点续跑）。幂等守卫：sessionId 存在 + 处于错误族冻结
            // （status==Frozen && frozenReason != schedule——时间表冻结是正常调度，
            // 不可父重启）。按 kind 路由：有 supervisor（Delegate/SubTask）→ Stop →
            // BackoffSupervisor respawn；Team 成员 → Stop + Mail 激活（history 重建）；
            // Root → restartAgent soft（现有重启语义）。
            val prJson = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val prSessionId = prJson.hcursor.downField("sessionId").as[String].toOption.getOrElse("")
            def prReply(ok: Boolean, extra: (String, Json)*): IO[Unit] =
              wsSend(
                io.circe.Json.obj(
                  Seq(
                    ("type", "parentRestartResult".asJson),
                    ("ok", ok.asJson),
                    ("sessionId", prSessionId.asJson)
                  ) ++ extra*
                )
              )
            if prSessionId.isEmpty then
              prReply(ok = false, "error" -> "parentRestart requires sessionId".asJson)
            else
              logger.info(s"parentRestart (error-recovery) for session $prSessionId") *>
                sharedResources.agentRegistry.get.flatMap { registry =>
                  registry.get(prSessionId) match
                    case None =>
                      prReply(
                        ok = false,
                        "error" ->
                          s"No live agent with sessionId='$prSessionId' (registry is in-memory; stale ids vanish after restart)".asJson
                      )
                    case Some(rec)
                        if rec.status != nebflow.agent.AgentStatus.Frozen ||
                          !rec.frozenReason.exists(_ != "schedule") =>
                      val notFrozenErr =
                        s"Session '$prSessionId' is not in error-frozen state (status=${rec.status}, " +
                          s"frozenReason=${rec.frozenReason.getOrElse("none")}) — parentRestart only applies to frozen-error agents"
                      prReply(ok = false, "error" -> notFrozenErr.asJson)
                    case Some(rec) if rec.kind == nebflow.agent.AgentKind.Root =>
                      // Root agent：现有 restartAgent soft 语义（冻结中 RestartAgent
                      // 镜像 processing——cancelCurrentTurn + restartStateFor + 续跑）
                      (rec.ref ! nebflow.agent.AgentCommand.RestartAgent(nebflow.agent.RestartLevel.Soft)) *>
                        prReply(ok = true, "message" -> "Root restart (soft) sent; frozen turn will resume from checkpoint".asJson)
                    case Some(rec) if rec.supervisorRef.isDefined =>
                      // Delegate/SubTask：Stop → BackoffSupervisor respawn（断点续跑）
                      (rec.ref ! nebflow.agent.AgentCommand.Stop(s"parent-restart")) *>
                        prReply(
                          ok = true,
                          "message" ->
                            "Restart sent: the supervisor will respawn from the last persisted checkpoint after a short backoff".asJson
                        )
                    case Some(rec) =>
                      // Team 成员：Stop + Mail 激活（history 重建）——复用 AgentControl 工具链路
                      val toolCtx = ToolContext(
                        projectRoot = "",
                        sessionId = None,
                        sharedResources = Some(sharedResources),
                        actorSystem = Some(sharedResources.actorSystem)
                      )
                      nebflow.core.tools.AgentControlTool
                        .doRestart(sharedResources, toolCtx, rec, "parent-restart (WS)")
                        .flatMap {
                          case Right(msg) => prReply(ok = true, "message" -> msg.asJson)
                          case Left(err)  => prReply(ok = false, "error" -> err.message.asJson)
                        }
                }

          case "immediateInput" =>
            val immJson = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val immSessionId = immJson.hcursor.downField("sessionId").as[String].getOrElse("")
            val immContent = immJson.hcursor.downField("content").as[String].getOrElse("")
            handleUserText(immSessionId, immContent, source = "immediateInput")

          // Protocol alias for immediateInput, sent by the CLI (ChatSend).
          // Before this case existed the payload fell through to the silent
          // `case _ => IO.unit` below — the CLI received "status: ok" while
          // nothing was dispatched (the headless one-shot chain was broken).
          // NOTE: ScheduledTaskActor also emits a "userMessage" broadcast, but
          // that is a server-to-client display event (task routing goes
          // through routeToAgent directly) — it never enters handleMessage
          // and is unaffected by this case.
          case "userMessage" =>
            val umJson = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val umSessionId = umJson.hcursor.downField("sessionId").as[String].getOrElse("")
            val umContent = umJson.hcursor.downField("content").as[String].getOrElse("")
            handleUserText(umSessionId, umContent, source = "userMessage")

          // 冻结「跳过本次」按钮（2026-08-25 前端联调）：无文本跳过命令——
          // 不注入假消息气泡污染上下文，直调 skipCurrentFreezeWindow（置
          // freezeSkipUntilRef = 当前窗口结束时刻 + FreezeScheduler.scan 全局
          // 解冻，语义与用户消息触发完全一致；窗口结束后 skip 自然过期，下一
          // 冻结段照常冻结）。无 payload、无回执（fire-and-forget）。
          case "skipFreeze" =>
            skipCurrentFreezeWindow

          case "command" =>
            val command = parse(text).flatMap(_.hcursor.downField("command").as[String]).getOrElse("")
            command match
              case "clear" =>
                val clearSessionId =
                  parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).toOption.getOrElse("")
                logger.info("Session cleared") *>
                  sessionStore.saveMessagesForSession(clearSessionId, Nil) *>
                  sessionStore.appendUiMessages(
                    clearSessionId,
                    List(UiMessage.System("Context cleared. LLM memory reset.", Some("slash.clearDone")))
                  ) *>
                  sharedResources.taskStore.deleteAll(clearSessionId) *>
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "taskListUpdate".asJson,
                      "tasks" -> io.circe.Json.arr(),
                      "sessionId" -> clearSessionId.asJson
                    )
                  ) *>
                  ensureAgent(clearSessionId)(ref => ref ! AgentCommand.ResetSession)
              case "compact" =>
                val compactSessionId =
                  parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).toOption.getOrElse("")
                val instruction =
                  parse(text).flatMap(_.hcursor.downField("instruction").as[String]).toOption.filter(_.nonEmpty)
                logger.info("Manual compaction triggered") *>
                  ensureAgent(compactSessionId)(ref =>
                    ref ! AgentCommand.TriggerCompaction("full", postCompactInstruction = instruction)
                  )
              case "plan" =>
                val planSessionId =
                  parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).toOption.getOrElse("")
                val planTask = parse(text).flatMap(_.hcursor.downField("task").as[String]).toOption.getOrElse("")
                if planSessionId.nonEmpty && planTask.nonEmpty then
                  logger.info(s"Plan mode started: ${planTask.take(60)}") *>
                    ensureAgent(planSessionId)(ref => ref ! AgentCommand.StartPlan(planTask))
                else IO.unit
              case "fork" =>
                val forkSessionId =
                  parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).toOption.getOrElse("")
                for
                  sourceMetaOpt <- sessionStore.getSessionMeta(forkSessionId)
                  sourceName = sourceMetaOpt.map(_.name).getOrElse("Session")
                  _ <- logger.info(s"Forking session $forkSessionId ($sourceName)")
                  newMeta <- sessionStore.forkSession(forkSessionId, s"Fork of $sourceName")
                  _ <- (sessionStore.listSessions, sessionStore.listAllFolders).flatMapN { (sessions, folders) =>
                    val rulesFolderIds = folders.filter(f => nebflow.service.RulesStore.exists(f.id)).map(_.id)
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "sessionList".asJson,
                        "sessions" -> sessions.asJson,
                        "folders" -> folders.asJson,
                        "activeId" -> forkSessionId.asJson,
                        "foldersWithRules" -> rulesFolderIds.asJson
                      )
                    )
                  }
                  _ <- wsSend(
                    io.circe.Json.obj(
                      "type" -> "forkComplete".asJson,
                      "sessionId" -> newMeta.id.asJson,
                      "name" -> newMeta.name.asJson
                    )
                  )
                yield ()
                end for
              case _ => IO.unit
            end match

          case "recallMessage" =>
            val recallSessionId =
              parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).toOption.getOrElse("")
            if recallSessionId.nonEmpty then
              for
                deleted <- sessionStore.deleteLastUserMessage(recallSessionId)
                _ <- wsSend(
                  io.circe.Json.obj(
                    "type" -> "messageRecalled".asJson,
                    "sessionId" -> recallSessionId.asJson,
                    "success" -> deleted.asJson
                  )
                )
              yield ()
            else IO.unit

          case "setThinking" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val hc = json.hcursor
            val thinkingOpt = hc.downField("thinking").as[Option[io.circe.Json]].toOption.flatten
            // null/absent means toggled off; {enabled: false} also means off; otherwise default true
            val enabled = thinkingOpt match
              case None | Some(io.circe.Json.Null) => false
              case Some(v) => v.hcursor.downField("enabled").as[Boolean].getOrElse(true)
            val budgetTokens = thinkingOpt match
              case None | Some(io.circe.Json.Null) => 32000
              case Some(v) => v.hcursor.downField("budgetTokens").as[Int].getOrElse(32000)
            val tc = ThinkingConfig(enabled, budgetTokens)
            logger.info(s"Thinking mode set to: enabled=$enabled budgetTokens=$budgetTokens") *>
              // 冻结修复（2026-08-27）：同族排序——persist 成功才热更，失败回执。
              nebflow.service.ConfigService.writeLocked(persistThinkingConfig(tc)).attempt.flatMap {
                case Left(e) =>
                  logger.warn(s"Failed to persist thinking config: ${e.getMessage}") *>
                    wsSend(io.circe.Json.obj("type" -> "configUpdateFailed".asJson, "message" -> s"思考模式保存失败: ${e.getMessage}".asJson))
                case Right(_) =>
                  sharedResources.thinkingConfigRef.set(tc) *> broadcastServerConfig
              }

          case "setWorkSchedule" =>
            // 冻结调度（freeze-schedule spec ⑦ + 2026-08-25 裁定「设置关闭保留
            // 配置」）：payload {type, workSchedule:{enabled?, segments?}}——
            // 缺键从现有配置继承（toggle off 只置 disabled、segments 不清空不
            // 重置；显式 segments:[] 仍可清空）。校验失败拒绝保存（配置不变）
            // 并回 configUpdateFailed；成功 → ref 热更 + targeted write + 广播 +
            // 立即让所有 Frozen agent 重评估（不用等 30s 轮询——改配置关功能即恢复）。
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val payload = json.hcursor
              .downField("workSchedule")
              .as[Option[io.circe.Json]]
              .toOption
              .flatten
              .getOrElse(json)
            for
              current <- sharedResources.freezeScheduleRef.get
              merged <- IO.pure(nebflow.core.schedule.FreezeSchedule.mergeValidate(current, payload))
              _ <- merged match
                case Right(cfg) =>
                  // 冻结修复（2026-08-27）排序：写盘成功才热更 ref——persist 失败时
                  // 内存与盘保持一致并回执 failed（此前 persist 静默吞错 + ref 先行，
                  // 会造成「UI 已关、重启后又冻」的假保存）。写盘段持 config 写锁。
                  logger.info(s"Freeze schedule set: enabled=${cfg.enabled} segments=${cfg.segments.size}") *>
                    nebflow.service.ConfigService.writeLocked(persistWorkSchedule(cfg)).attempt.flatMap {
                      case Left(e) =>
                        logger.warn(s"Failed to persist work schedule: ${e.getMessage}") *>
                          wsSend(io.circe.Json.obj("type" -> "configUpdateFailed".asJson, "message" -> s"冻结配置保存失败: ${e.getMessage}".asJson))
                      case Right(_) =>
                        sharedResources.freezeScheduleRef.set(cfg) *>
                          broadcastServerConfig *>
                          // 立即让所有 Frozen agent 重评估（不用等 30s 轮询——改配置
                          // 关功能即恢复）。
                          nebflow.core.processor.FreezeScheduler.scan(sharedResources)
                    }
                case Left(err) =>
                  logger.warn(s"Invalid workSchedule payload rejected: $err") *>
                    wsSend(io.circe.Json.obj("type" -> "configUpdateFailed".asJson, "message" -> err.asJson))
            yield ()

          case "setSttConfig" =>
            // #295 STT 可配置（用户 2026-08-18 拍板）→ A2 部分更新语义
            // （2026-08-20）：payload {type, sttConfig: {endpoint?, apiKey?,
            // model?}}。字段省略=保留旧值（前端空 key 输入框省略字段——旧
            // 整文件替换语义会把已存 apiKey 覆盖丢失，此为 #295 A2 根因）；
            // 字段显式空串=清除该字段；合并后无任何字段=删配置文件（回退
            // 免费浏览器 Web Speech）。endpoint 设值须 http(s)://，model
            // 非空，apiKey 原样存（key 不落地前端——serverConfig 广播只含
            // sttConfigured/endpoint/model，永不回传 apiKey）。写盘走
            // AtomicJson 原子写；写后 SttService.create() 重建热更
            // （transcribe 立即走新配置）。
            val payload = parse(text).toOption
              .flatMap(_.hcursor.downField("sttConfig").as[Option[Json]].toOption.flatten)
              .getOrElse(Json.obj())
            SttService.parsePatch(payload) match
              case Left(err) =>
                logger.warn(s"Invalid sttConfig payload rejected: $err") *>
                  wsSend(io.circe.Json.obj("type" -> "configUpdateFailed".asJson, "message" -> err.asJson))
              case Right(patch) =>
                val oldCfgOpt = IO.blocking {
                  if os.exists(SttService.configPath) then
                    parse(os.read(SttService.configPath)).toOption
                  else None
                }
                oldCfgOpt.flatMap { oldCfg =>
                  SttService.mergeConfig(oldCfg, patch) match
                    case Some(cfgJson) =>
                      AtomicJson.write(SttService.configPath, cfgJson.noSpaces) *>
                        SttService.create().flatMap { svc =>
                          sttServiceRef.set(svc) *> logger.info(s"STT config set: endpoint=${svc.map(_.endpoint).getOrElse("(defaults)")}")
                        } *>
                        broadcastServerConfig *>
                        wsSend(io.circe.Json.obj("type" -> "configUpdated".asJson, "success" -> true.asJson))
                    case None =>
                      (if os.exists(SttService.configPath) then IO.blocking(os.remove(SttService.configPath)) else IO.unit) *>
                        sttServiceRef.set(None) *>
                        logger.info("STT config cleared (fallback to browser Web Speech)") *>
                        broadcastServerConfig *>
                        wsSend(io.circe.Json.obj("type" -> "configUpdated".asJson, "success" -> true.asJson))
                }

          case "getToolResultTtl" =>
            // #341 WS 尾巴：TTL 设置面板读当前生效配置（Ref 是权威——含未
            // 重启的热更值）。无敏感字段，全量回显。
            sharedResources.toolResultTtlRef.get.flatMap { cfg =>
              wsSend(io.circe.Json.obj(
                "type" -> "toolResultTtl".asJson,
                "config" -> cfg.asJson
              ))
            }

          case "setToolResultTtl" =>
            // #341 WS 尾巴：payload {type, config:{enabled,ttlMinutes,
            // keepRecent}}（全量替换，三字段必填；minChars 已移除，旧负载
            // 携带该字段静默忽略）。STRICT 校验（负数/非整数/超界/缺字段 →
            // 数/非整数/超界/缺字段 → 拒绝并 warn，回 configUpdateFailed——与
            // boot 时 fail-safe load 不同：交互面必须把错误亮给用户）。
            // 成功 → nebflow.json toolResultTtl 节 read-merge + AtomicJson
            // 原子写 + Ref 热更（下个 LLM 请求生效，镜像 freezeScheduleRef）
            // → 回 toolResultTtlSaved。
            val payload = parse(text).toOption
              .flatMap(_.hcursor.downField("config").as[Option[Json]].toOption.flatten)
              .getOrElse(Json.Null)
            nebflow.core.compact.ToolResultTtlConfig.parseStrict(payload) match
              case Left(err) =>
                logger.warn(s"Invalid toolResultTtl payload rejected: $err") *>
                  wsSend(io.circe.Json.obj("type" -> "configUpdateFailed".asJson, "message" -> err.asJson))
              case Right(cfg) =>
                val persist = IO.blocking {
                  val existing =
                    if os.exists(nebflow.llm.Config.DefaultConfigPath) then
                      os.read(nebflow.llm.Config.DefaultConfigPath)
                    else "{}"
                  val path = PathUtil.configJsonWritePath(PathUtil.dataRoot)
                  parse(existing).foreach { json =>
                    val updated = json.mapObject(_.add("toolResultTtl", cfg.asJson))
                    // writeSync (not write): we are already inside IO.blocking —
                    // the IO-returning variant would be built, not run.
                    AtomicJson.writeSync(path, updated.noSpaces)
                  }
                }
                // 冻结修复（2026-08-27）：同族排序——persist 成功才热更 ref，
                // 失败回执 failed（写盘段持 config 写锁）。
                nebflow.service.ConfigService.writeLocked(persist).attempt.flatMap {
                  case Left(e) =>
                    logger.warn(s"Failed to persist toolResultTtl: ${e.getMessage}") *>
                      wsSend(io.circe.Json.obj("type" -> "configUpdateFailed".asJson, "message" -> s"TTL 配置保存失败: ${e.getMessage}".asJson))
                  case Right(_) =>
                    sharedResources.toolResultTtlRef.set(cfg) *>
                      logger.info(
                        s"Tool result TTL set: enabled=${cfg.enabled} ttlMinutes=${cfg.ttlMinutes} " +
                          s"keepRecent=${cfg.keepRecent}"
                      ) *>
                      wsSend(io.circe.Json.obj(
                        "type" -> "toolResultTtlSaved".asJson,
                        "config" -> cfg.asJson
                      ))
                }

          case "setVoiceMuted" =>
            val muted = parse(text).toOption
              .flatMap(_.hcursor.downField("muted").as[Boolean].toOption)
              .getOrElse(false)
            sharedResources.voiceMutedRef.set(muted)

          case "setLlmLog" =>
            val enabled = parse(text).toOption
              .flatMap(_.hcursor.downField("enabled").as[Boolean].toOption)
              .getOrElse(true)
            LlmLogWriter.setEnabled(enabled)
            logger.info(s"LLM log set to: $enabled") *>
              wsSend(io.circe.Json.obj("type" -> "llmLogState".asJson, "enabled" -> enabled.asJson))

          case "getLlmLog" =>
            wsSend(io.circe.Json.obj("type" -> "llmLogState".asJson, "enabled" -> LlmLogWriter.isEnabled.asJson))

          case "getModelOptions" =>
            val sessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
            sharedResources.providerRegistry.getAllModelsDetailed().flatMap { models =>
              sharedResources.sessionModelOverrides.get.flatMap { overrides =>
                val currentOpt = overrides.get(sessionId).map(c => s"${c.providerId}/${c.model}")
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "modelOptions".asJson,
                    "sessionId" -> sessionId.asJson,
                    "models" -> models.map { case (ref, label, desc) =>
                      io.circe.Json.obj(
                        "ref" -> ref.asJson,
                        "label" -> label.asJson,
                        "description" -> desc.asJson
                      )
                    }.asJson,
                    "current" -> currentOpt.asJson
                  )
                )
              }
            }

          case "setSessionModel" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val sessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
            val modelRef = json.hcursor.downField("modelRef").as[Option[String]].getOrElse(None)
            if sessionId.nonEmpty then
              (modelRef match
                case Some(ref) =>
                  sharedResources.providerRegistry.getCandidateForRef(ref).flatMap {
                    case Some(candidate) =>
                      sharedResources.sessionModelOverrides.update(_ + (sessionId -> candidate)) *>
                        sessionStore
                          .updateSessionModel(sessionId, Some(ref))
                          .as(Right(s"${candidate.providerId}/${candidate.model}"))
                    case None =>
                      IO.pure(Left(s"Unknown model: $ref"))
                  }
                case None =>
                  sharedResources.sessionModelOverrides.update(_ - sessionId) *>
                    sessionStore.updateSessionModel(sessionId, None).as(Right("default"))
              ).flatMap {
                case Right(ref) =>
                  // Notify agent actor of context window change for compaction threshold
                  val notifyAgent = sharedResources.sessionModelOverrides.get.flatMap { overrides =>
                    overrides.get(sessionId) match
                      case Some(candidate) =>
                        ensureAgent(sessionId)(ref => ref ! AgentCommand.UpdateContextWindow(candidate.contextWindow))
                      case None => IO.unit
                  }
                  notifyAgent *> wsSend(io.circe.Json.obj("type" -> "sessionModelSet".asJson, "modelRef" -> ref.asJson))
                case Left(err) =>
                  wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> err.asJson))
              }
            else IO.unit
            end if

          case "switchSession" =>
            val sessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
            if sessionId.nonEmpty then
              sessionService
                .switchSession(sessionId)
                .flatMap { _ =>
                  sendAgentSessionList(wsSend, sessionId) *>
                    sendMemoryStatus(wsSend, sessionId)
                }
                  .handleErrorWith { e =>
                    wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
                  }
            else IO.unit
            end if

          case "createSession" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val name = json.hcursor.downField("name").as[String].getOrElse("New Session")
            val agentName = json.hcursor.downField("agentName").as[Option[String]].getOrElse(None)
            val folderId = json.hcursor.downField("folderId").as[Option[String]].getOrElse(None)
            sessionService
              .createSession(name, agentName = agentName, folderId = folderId)
              .flatMap { meta =>
                // Send unified session list (all agents)
                val sendList = agentName match
                  case Some(an) =>
                    sendAgentSessionListByName(wsSend, an)
                  case None =>
                    sessionService.sendSessionList(wsSend, "Nebula")
                sendList
              }
              .handleErrorWith { e =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
              }

          case "deleteSession" =>
            val sessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
            if sessionId.nonEmpty then
              // Get agent name before deleting so we can send filtered list
              sessionStore
                .getSessionMeta(sessionId)
                .flatMap { metaOpt =>
                  val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
                  // Clean up text buffers for deleted session to prevent memory leak
                  sessionTextBuffers.update(_ - sessionId) *>
                    sessionThinkingBuffers.update(_ - sessionId) *>
                    sessionTurnStarts.update(_ - sessionId) *>
                    stopTeamSessionActors(sessionId) *> removeRootAgent(sessionId) *> sessionService
                      .deleteSession(sessionId)
                      .flatMap { _ =>
                        sendAgentSessionListByName(wsSend, agentName)
                      }
                }
                .handleErrorWith { e =>
                  wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
                }
            else IO.unit
            end if

          case "batchDeleteSessions" =>
            val sessionIds = parse(text).flatMap(_.hcursor.downField("sessionIds").as[List[String]]).getOrElse(Nil)
            if sessionIds.nonEmpty then
              sessionStore
                .getSessionMeta(sessionIds.head)
                .flatMap { metaOpt =>
                  val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
                  sessionIds
                    .traverse_ { sid =>
                      sessionTextBuffers.update(_ - sid) *>
                        sessionThinkingBuffers.update(_ - sid) *>
                        sessionTurnStarts.update(_ - sid) *>
                        stopTeamSessionActors(sid) *>
                        removeRootAgent(sid) *>
                        sessionService.deleteSession(sid)
                    }
                    .flatMap { _ =>
                      sendAgentSessionListByName(wsSend, agentName)
                    }
                }
                .handleErrorWith { e =>
                  wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
                }
            else IO.unit
            end if

          case "renameSession" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val sessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
            val newName = json.hcursor.downField("name").as[String].getOrElse("")
            if sessionId.nonEmpty && newName.nonEmpty then
              sessionService
                .renameSession(sessionId, newName)
                .flatMap { _ =>
                  sendAgentSessionList(wsSend, sessionId)
                }
                .handleErrorWith { e =>
                  wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
                }
            else IO.unit

          case "setSafetyMode" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val sid = json.hcursor.downField("sessionId").as[String].getOrElse("")
            val mode = json.hcursor.downField("safetyMode").as[String].getOrElse("confirm-edits")
            if sid.nonEmpty then
              sessionStore
                .setSafetyMode(sid, mode)
                .flatMap { _ =>
                  ensureAgent(sid)(ref => ref ! AgentCommand.SetSafetyMode(nebflow.core.SafetyMode.fromString(mode))) *>
                    sendAgentSessionList(wsSend, sid)
                }
                .handleErrorWith { e =>
                  wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
                }
            else IO.unit

          case "setBypass" =>
            // Backward compat: old clients send { bypass: Boolean }
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val sid = json.hcursor.downField("sessionId").as[String].getOrElse("")
            val bypass = json.hcursor.downField("bypass").as[Boolean].getOrElse(false)
            val mode = if bypass then "auto-all" else "confirm-edits"
            if sid.nonEmpty then
              sessionStore
                .setSafetyMode(sid, mode)
                .flatMap { _ =>
                  ensureAgent(sid)(ref => ref ! AgentCommand.SetSafetyMode(nebflow.core.SafetyMode.fromString(mode))) *>
                    sendAgentSessionList(wsSend, sid)
                }
                .handleErrorWith { e =>
                  wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
                }
            else IO.unit

          case "ask" =>
            val askJson = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val question = askJson.hcursor.downField("question").as[String].getOrElse("")
            val askSessionId = askJson.hcursor.downField("sessionId").as[String].getOrElse("")
            if question.nonEmpty && askSessionId.nonEmpty then
              executeAsk(askSessionId, question, wsSend).handleErrorWith { e =>
                logger.warn(s"Ask failed for session $askSessionId: ${e.getMessage}")
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "askError".asJson,
                    "sessionId" -> askSessionId.asJson,
                    "message" -> s"Ask failed: ${e.getMessage.take(200)}".asJson
                  )
                )
              }
            else IO.unit

          case "getSkills" =>
            for
              skills <- SkillService.listSkills()
              _ <- wsSend(
                io.circe.Json.obj(
                  "type" -> "skillList".asJson,
                  "skills" -> skills.asJson
                )
              )
            yield ()

          case "getTeams" =>
            for
              teams <- EntityLoader.listTeams()
              flows <- EntityLoader.listFlows()
              teamEntries = teams.values.toList
                .sortBy(_.name)
                .map(t =>
                  io.circe.Json
                    .obj("name" -> t.name.asJson, "description" -> t.description.asJson, "type" -> "team".asJson)
                )
              flowEntries = flows.values.toList
                .sortBy(_.name)
                .map(f =>
                  io.circe.Json
                    .obj("name" -> f.name.asJson, "description" -> f.description.asJson, "type" -> "flow".asJson)
                )
              _ <- wsSend(
                io.circe.Json.obj(
                  "type" -> "teamList".asJson,
                  "teams" -> teamEntries.asJson,
                  "flows" -> flowEntries.asJson
                )
              )
            yield ()

          case "skill" =>
            val skillJson = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val skillName = skillJson.hcursor.downField("skillName").as[String].getOrElse("")
            val skillInput = skillJson.hcursor.downField("input").as[String].getOrElse("")
            val skillSessionId = skillJson.hcursor.downField("sessionId").as[String].getOrElse("")
            if skillName.nonEmpty && skillSessionId.nonEmpty then
              // Persist user message and skill activation system bubble to session history,
              // so they survive session switching (frontend rebuilds DOM from backend history).
              sharedResources.sessionStore.appendUiMessages(
                skillSessionId,
                List(
                  UiMessage.User(skillInput, timestamp = System.currentTimeMillis()),
                  UiMessage.System(
                    s"Using skill: $skillName",
                    Some("slash.skillActivated"),
                    Some(io.circe.Json.obj("skillName" -> skillName.asJson))
                  )
                )
              ) *>
                executeSkill(skillName, skillInput, skillSessionId, wsSend).handleErrorWith { e =>
                  logger.warn(s"Skill '$skillName' failed for session $skillSessionId: ${e.getMessage}")
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "skillError".asJson,
                      "sessionId" -> skillSessionId.asJson,
                      "message" -> s"Skill failed: ${e.getMessage.take(200)}".asJson
                    )
                  )
                }
            else IO.unit
            end if

          case "deleteSkill" =>
            val delJson = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val delName = delJson.hcursor.downField("name").as[String].getOrElse("")
            if delName.nonEmpty then
              SkillService.deleteSkill(delName).flatMap { success =>
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "skillDeleted".asJson,
                    "name" -> delName.asJson,
                    "success" -> success.asJson
                  )
                ) *>
                  SkillService.listSkills().flatMap { skills =>
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "skillList".asJson,
                        "skills" -> skills.asJson
                      )
                    )
                  }
              }
            else IO.unit
            end if

          // ===== Scheduled Task Management =====

          case "createScheduledTask" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val hc = json.hcursor
            val crSessionId = hc.downField("sessionId").as[String].getOrElse("")
            val crContent = hc.downField("content").as[String].getOrElse("")
            val crTriggerAt = hc.downField("triggerAt").as[Long].getOrElse(0L)
            val crRefPath = hc.downField("referencePath").as[Option[String]].getOrElse(None)
            val crRepeat = hc.downField("repeat").as[Option[String]].getOrElse(None)
            if crSessionId.nonEmpty && crContent.nonEmpty && crTriggerAt > System.currentTimeMillis() then
              val task =
                nebflow.core.scheduler.ScheduledTask.create(crSessionId, crContent, crTriggerAt, crRefPath, crRepeat)
              sharedResources.scheduledTaskStore.addTask(task).flatMap { _ =>
                // Creation audit trail: without this line a lost WS create (fire-and-forget
                // client, no ack retry) is indistinguishable from a persistence failure —
                // see the 2026-08-17 P0 where the file was never written and no server-side
                // trace existed to separate "message never arrived" from "arrived and broke".
                // NOTE: infoSync/warnSync — the IO-returning info/warn would be discarded
                // as bare statements (effect never runs).
                logger.infoSync(
                  s"Scheduled task created: ${task.id} session=${task.sessionId} " +
                    s"triggerAt=${task.triggerAt} content=${task.content.take(40)}"
                )
                sharedResources.scheduledTaskService.foreach(_.notifyTaskChange())
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "scheduledTaskCreated".asJson,
                    "task" -> io.circe.Json.obj(
                      "id" -> task.id.asJson,
                      "content" -> task.content.asJson,
                      "triggerAt" -> task.triggerAt.asJson,
                      "createdAt" -> task.createdAt.asJson,
                      "referencePath" -> task.referencePath.asJson,
                      "repeat" -> task.repeat.asJson
                    )
                  )
                )
              }
            else
              val reason =
                if crSessionId.isEmpty then "missing sessionId"
                else if crContent.isEmpty then "missing content"
                else if crTriggerAt <= System.currentTimeMillis() then "triggerAt must be in the future"
                else "unknown"
              // Rejections must be loud: the client treats creates as fire-and-forget with
              // an optimistic row, so a silent reject reads as "task set" until it never
              // fires. Log it, and tag the error with the originating msgType so the
              // frontend can route it back to the scheduled-task panel.
              logger.warnSync(
                s"Rejected createScheduledTask: $reason (session=$crSessionId " +
                  s"triggerAt=$crTriggerAt content=${crContent.take(40)})"
              )
              wsSend(
                io.circe.Json.obj(
                  "type" -> "error".asJson,
                  "msgType" -> "createScheduledTask".asJson,
                  "message" -> s"Invalid scheduled task: $reason".asJson
                )
              )
            end if

          case "listScheduledTasks" =>
            val lrSessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
            if lrSessionId.nonEmpty then
              sharedResources.scheduledTaskStore.loadTasks(lrSessionId).flatMap { allTasks =>
                // Only return pending (untriggered) tasks — triggered tasks are
                // deleted from storage on firing, but filter as a safety net.
                val tasks = allTasks.filterNot(_.triggered)
                val taskJsons = tasks.map { t =>
                  io.circe.Json.obj(
                    "id" -> t.id.asJson,
                    "content" -> t.content.asJson,
                    "triggerAt" -> t.triggerAt.asJson,
                    "createdAt" -> t.createdAt.asJson,
                    "triggered" -> t.triggered.asJson,
                    "triggeredAt" -> t.triggeredAt.asJson,
                    "referencePath" -> t.referencePath.asJson,
                    "repeat" -> t.repeat.asJson,
                    "enabled" -> t.enabled.asJson
                  )
                }
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "scheduledTaskList".asJson,
                    "tasks" -> taskJsons.asJson,
                    "sessionId" -> lrSessionId.asJson
                  )
                )
              }
            else IO.unit
            end if

          case "deleteScheduledTask" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val drSessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
            val drId = json.hcursor.downField("id").as[String].getOrElse("")
            if drSessionId.nonEmpty && drId.nonEmpty then
              sharedResources.scheduledTaskStore.deleteTask(drSessionId, drId).flatMap { _ =>
                sharedResources.scheduledTaskService.foreach(_.notifyTaskChange())
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "scheduledTaskDeleted".asJson,
                    "id" -> drId.asJson,
                    "sessionId" -> drSessionId.asJson
                  )
                )
              }
            else IO.unit

          case "toggleScheduledTask" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val tgSessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
            val tgId = json.hcursor.downField("id").as[String].getOrElse("")
            if tgSessionId.nonEmpty && tgId.nonEmpty then
              sharedResources.scheduledTaskStore.toggleTask(tgSessionId, tgId).flatMap { newEnabled =>
                sharedResources.scheduledTaskService.foreach(_.notifyTaskChange())
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "scheduledTaskToggled".asJson,
                    "id" -> tgId.asJson,
                    "sessionId" -> tgSessionId.asJson,
                    "enabled" -> newEnabled.asJson
                  )
                )
              }
            else IO.unit

          // ===== Task List (fetch on session switch / reconnect) =====

          case "getTaskList" =>
            val tlsSessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
            if tlsSessionId.nonEmpty then
              sharedResources.taskStore.listVisible(tlsSessionId).flatMap { tasks =>
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "taskListUpdate".asJson,
                    "sessionId" -> tlsSessionId.asJson,
                    "tasks" -> tasks.asJson
                  )
                )
              }
            else IO.unit

          // ===== Complete Task (user clicks the todos-panel circle, todo-panel §7.1) =====

          case "completeTask" =>
            val ctSessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
            val ctTaskId = parse(text).flatMap(_.hcursor.downField("taskId").as[String]).getOrElse("")
            if ctSessionId.nonEmpty && ctTaskId.nonEmpty then
              sharedResources.taskStore.complete(ctSessionId, ctTaskId, by = "user").attempt.flatMap {
                case Right(Some(_)) =>
                  // Return updated task list — completed items vanish from the
                  // panel ([U3] complete = disappear), so the authoritative
                  // refresh is what converges every client.
                  sharedResources.taskStore.listVisible(ctSessionId).flatMap { tasks =>
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "taskListUpdate".asJson,
                        "sessionId" -> ctSessionId.asJson,
                        "tasks" -> tasks.asJson
                      )
                    )
                  }
                case Right(None) =>
                  wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> s"Task not found: $ctTaskId".asJson))
                case Left(err) =>
                  // IllegalStateException — terminal task cannot be re-completed
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "taskError".asJson,
                      "error" -> s"Cannot complete: ${err.getMessage}".asJson,
                      "taskId" -> ctTaskId.asJson
                    )
                  )
              }
            else IO.unit
            end if

          // ===== Workspace Knowledge =====

          case "listWorkspaceItems" =>
            val wsSessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
            if wsSessionId.nonEmpty then
              sharedResources.knowledgeStore.loadItems(wsSessionId).flatMap { items =>
                val itemJsons = items.map { it =>
                  io.circe.Json.obj(
                    "id" -> it.id.asJson,
                    "sessionId" -> it.sessionId.asJson,
                    "title" -> it.title.asJson,
                    "itemType" -> it.itemType.asJson,
                    "content" -> it.content.asJson,
                    "createdAt" -> it.createdAt.asJson
                  )
                }
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "workspaceItemList".asJson,
                    "items" -> itemJsons.asJson,
                    "sessionId" -> wsSessionId.asJson
                  )
                )
              }
            else IO.unit
            end if

          case "deleteWorkspaceItem" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val delSessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
            val delId = json.hcursor.downField("id").as[String].getOrElse("")
            if delSessionId.nonEmpty && delId.nonEmpty then
              sharedResources.knowledgeStore.deleteItem(delSessionId, delId).flatMap { _ =>
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "workspaceItemDeleted".asJson,
                    "id" -> delId.asJson,
                    "sessionId" -> delSessionId.asJson
                  )
                )
              }
            else IO.unit

          // ===== Explorer (File Tree) =====

          case "listDir" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val hc = json.hcursor
            val exSessionId = hc.downField("sessionId").as[String].getOrElse("")
            val subPath = hc.downField("path").as[String].getOrElse("")
            if exSessionId.nonEmpty then
              val overrideRoot = hc.downField("rootPath").as[Option[String]].toOption.flatten
              (for
                pr <- overrideRoot match
                  case Some(root) => IO.pure(root)
                  case None =>
                    for
                      metaOpt <- sessionStore.getSessionMeta(exSessionId)
                      folderId = metaOpt.flatMap(_.folderId)
                      prOpt <- sessionStore.resolveProjectRoot(folderId)
                    yield prOpt.getOrElse((PathUtil.dataRoot / "projects").toString)
                basePath = if subPath.isEmpty then os.Path(pr) else PathUtil.resolvePath(subPath, os.Path(pr))
                canonicalBase = basePath.toIO.getCanonicalPath
                canonicalRoot = os.Path(pr).toIO.getCanonicalPath
                _ <- IO.raiseUnless(canonicalBase.startsWith(canonicalRoot))(
                  new RuntimeException("path outside project root")
                )
                entries <- IO.blocking {
                  if os.exists(basePath) && os.isDir(basePath) then
                    os.list(basePath)
                      .sortBy { p =>
                        (if os.isDir(p) then 0 else 1, p.last.toLowerCase)
                      }
                      .map { p =>
                        val isDir = os.isDir(p)
                        io.circe.Json.obj(
                          "name" -> p.last.asJson,
                          "type" -> (if isDir then "dir" else "file").asJson,
                          "size" -> (if !isDir then os.size(p) else 0L).asJson
                        )
                      }
                  else Nil
                }
              yield (basePath.toString, entries))
                .flatMap { case (resolvedPath, entries) =>
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "dirListing".asJson,
                      "path" -> subPath.asJson,
                      "resolvedPath" -> resolvedPath.asJson,
                      "entries" -> entries.asJson
                    )
                  )
                }
                .handleErrorWith { e =>
                  logger.warn(s"listDir failed: ${e.getMessage}")
                    *> wsSend(io.circe.Json.obj("type" -> "dirListing".asJson, "error" -> e.getMessage.asJson))
                }
            else IO.unit
            end if

          case "readFile" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val hc = json.hcursor
            val rdSessionId = hc.downField("sessionId").as[String].getOrElse("")
            val filePath = hc.downField("path").as[String].getOrElse("")
            if rdSessionId.nonEmpty && filePath.nonEmpty then
              val overrideRoot = hc.downField("rootPath").as[Option[String]].toOption.flatten.filter(_.nonEmpty)
              (for
                pr <- overrideRoot match
                  case Some(root) => IO.pure(root)
                  case None =>
                    for
                      metaOpt <- sessionStore.getSessionMeta(rdSessionId)
                      folderId = metaOpt.flatMap(_.folderId)
                      prOpt <- sessionStore.resolveProjectRoot(folderId)
                    yield prOpt.getOrElse((PathUtil.dataRoot / "projects").toString)
                basePath <-
                  if pr.nonEmpty && os.Path(pr, os.pwd).segments.nonEmpty then
                    IO.blocking { PathUtil.resolvePath(filePath, os.Path(pr, os.pwd)) }
                  else IO.blocking { PathUtil.resolvePath(filePath, PathUtil.dataRoot / "projects") }
                canonicalBase = basePath.toIO.getCanonicalPath
                canonicalRoot = os.Path(pr, os.pwd).toIO.getCanonicalPath
                _ <- IO.raiseUnless(canonicalBase.startsWith(canonicalRoot))(
                  new RuntimeException("path outside project root")
                )
                content <- IO.blocking {
                  val size = os.size(basePath)
                  if size > 2 * 1024 * 1024 then
                    os.read(basePath, offset = 0, count = 2 * 1024 * 1024) + "\n\n[... file truncated at 2MB]"
                  else os.read(basePath)
                }
                fileSize = os.size(basePath)
              yield (content, basePath.toString, fileSize))
                .flatMap { case (content, absPath, fileSize) =>
                  val ext = filePath.split('.').lastOption.getOrElse("").toLowerCase
                  val entry = nebflow.core.workspace.FileTypeRegistry.detect(ext)
                  val itemType = entry.itemType
                  val isBinary = entry.binary
                  if isBinary then
                    // Binary files: don't send content via WS — frontend fetches via /api/nf-file
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "fileContent".asJson,
                        "path" -> filePath.asJson,
                        "absPath" -> absPath.asJson,
                        "itemType" -> itemType.asJson,
                        "fileName" -> filePath.split('/').last.asJson,
                        "size" -> fileSize.asJson
                      )
                    )
                  else
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "fileContent".asJson,
                        "path" -> filePath.asJson,
                        "absPath" -> absPath.asJson,
                        "content" -> content.asJson,
                        "itemType" -> itemType.asJson,
                        "fileName" -> filePath.split('/').last.asJson,
                        "size" -> fileSize.asJson
                      )
                    )
                  end if
                }
                .handleErrorWith { e =>
                  logger.warn(s"readFile failed: ${e.getMessage}")
                    *> wsSend(
                      io.circe.Json
                        .obj("type" -> "fileContent".asJson, "error" -> e.getMessage.asJson, "path" -> filePath.asJson)
                    )
                }
            else IO.unit
            end if

          case "pop.readFile" =>
            // Pop re-open: reads any absolute path without project root restriction.
            // Agent-created files (e.g. /tmp/output.svg) should always be readable.
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val hc = json.hcursor
            val popFilePathRaw = hc.downField("path").as[String].getOrElse("")
            // Expand a leading `~` (home shorthand) to the absolute home dir —
            // historical Pop records may store `~/...`; only the server (which
            // knows user.home) can resolve it to an absolute path.
            val userHome = System.getProperty("user.home", "")
            val popFilePath =
              if popFilePathRaw == "~" then userHome
              else if popFilePathRaw.startsWith("~/") then userHome + popFilePathRaw.drop(1)
              else popFilePathRaw
            if popFilePath.nonEmpty then
              (for
                _ <- IO.raiseUnless(popFilePath.startsWith("/"))(
                  new RuntimeException("path must be absolute")
                )
                basePath = os.Path(popFilePath)
                _ <- IO.raiseUnless(os.exists(basePath))(
                  new RuntimeException(s"file not found: $popFilePath")
                )
                _ <- IO.raiseUnless(os.isFile(basePath))(
                  new RuntimeException("path is not a regular file")
                )
                fileSize = os.size(basePath)
                _ <- IO.raiseWhen(fileSize > 10L * 1024 * 1024)(
                  new RuntimeException("file exceeds 10MB limit")
                )
                content <- IO.blocking { os.read(basePath) }
              yield (content, basePath.toString, fileSize, popFilePath))
                .flatMap { case (content, absPath, fileSize, origPath) =>
                  val ext = popFilePath.split('.').lastOption.getOrElse("").toLowerCase
                  val entry = nebflow.core.workspace.FileTypeRegistry.detect(ext)
                  val itemType = entry.itemType
                  val isBinary = entry.binary
                  if isBinary then
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "fileContent".asJson,
                        "path" -> origPath.asJson,
                        "absPath" -> absPath.asJson,
                        "itemType" -> itemType.asJson,
                        "fileName" -> origPath.split('/').last.asJson,
                        "size" -> fileSize.asJson
                      )
                    )
                  else
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "fileContent".asJson,
                        "path" -> origPath.asJson,
                        "absPath" -> absPath.asJson,
                        "content" -> content.asJson,
                        "itemType" -> itemType.asJson,
                        "fileName" -> origPath.split('/').last.asJson,
                        "size" -> fileSize.asJson
                      )
                    )
                  end if
                }
                .handleErrorWith { e =>
                  logger.warn(s"pop.readFile failed: ${e.getMessage}")
                    *> wsSend(
                      io.circe.Json
                        .obj(
                          "type" -> "fileContent".asJson,
                          "error" -> e.getMessage.asJson,
                          "path" -> popFilePath.asJson
                        )
                    )
                }
            else IO.unit
            end if

          case "transcribe" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val hc = json.hcursor
            val audioB64 = hc.downField("audio").as[String].getOrElse("")
            val language = hc.downField("language").as[String].toOption.filter(_.nonEmpty)
            if audioB64.nonEmpty then
              sttServiceRef.get.flatMap { sttService =>
              sttService match
                case None =>
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "transcription".asJson,
                      "error" -> "STT not configured — set it up in Settings".asJson
                    )
                  )
                case Some(svc) =>
                  IO.blocking {
                    java.util.Base64.getDecoder.decode(audioB64)
                  }.flatMap { wavBytes =>
                    svc.transcribe(wavBytes, language).flatMap {
                      case Right(text) =>
                        wsSend(
                          io.circe.Json.obj(
                            "type" -> "transcription".asJson,
                            "text" -> text.asJson
                          )
                        )
                      case Left(err) =>
                        wsSend(
                          io.circe.Json.obj(
                            "type" -> "transcription".asJson,
                            "error" -> err.asJson
                          )
                        )
                    }
                  }.handleErrorWith { e =>
                    logger.warn(s"Transcribe failed: ${e.getMessage}")
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "transcription".asJson,
                        "error" -> e.getMessage.asJson
                      )
                    )
                  }
              }
            else IO.unit
            end if

          case "ping" => wsSend(io.circe.Json.obj("type" -> "pong".asJson))

          case "createFile" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val hc = json.hcursor
            val cfSessionId = hc.downField("sessionId").as[String].getOrElse("")
            val cfPath = hc.downField("path").as[String].getOrElse("")
            if cfSessionId.nonEmpty && cfPath.nonEmpty then
              val overrideRoot = hc.downField("rootPath").as[Option[String]].toOption.flatten
              (for
                pr <- overrideRoot match
                  case Some(root) => IO.pure(root)
                  case None =>
                    for
                      metaOpt <- sessionStore.getSessionMeta(cfSessionId)
                      folderId = metaOpt.flatMap(_.folderId)
                      prOpt <- sessionStore.resolveProjectRoot(folderId)
                    yield prOpt.getOrElse((PathUtil.dataRoot / "projects").toString)
                basePath = PathUtil.resolvePath(cfPath, os.Path(pr))
                canonicalBase = basePath.toIO.getCanonicalPath
                canonicalRoot = os.Path(pr).toIO.getCanonicalPath
                _ <- IO.raiseUnless(canonicalBase.startsWith(canonicalRoot))(
                  new RuntimeException("path outside project root")
                )
                _ <- IO.blocking {
                  val parent = basePath / os.up
                  if !os.exists(parent) then os.makeDir.all(parent)
                  if !os.exists(basePath) then os.write.over(basePath, "")
                }
              yield cfPath)
                .flatMap { p =>
                  wsSend(io.circe.Json.obj("type" -> "fileCreated".asJson, "path" -> p.asJson))
                }
                .handleErrorWith { e =>
                  logger.warn(s"createFile failed: ${e.getMessage}")
                  wsSend(io.circe.Json.obj("type" -> "fileOpError".asJson, "error" -> e.getMessage.asJson))
                }
            else IO.unit
            end if

          case "createDir" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val hc = json.hcursor
            val cdSessionId = hc.downField("sessionId").as[String].getOrElse("")
            val cdPath = hc.downField("path").as[String].getOrElse("")
            if cdSessionId.nonEmpty && cdPath.nonEmpty then
              val overrideRoot = hc.downField("rootPath").as[Option[String]].toOption.flatten
              (for
                pr <- overrideRoot match
                  case Some(root) => IO.pure(root)
                  case None =>
                    for
                      metaOpt <- sessionStore.getSessionMeta(cdSessionId)
                      folderId = metaOpt.flatMap(_.folderId)
                      prOpt <- sessionStore.resolveProjectRoot(folderId)
                    yield prOpt.getOrElse((PathUtil.dataRoot / "projects").toString)
                basePath = PathUtil.resolvePath(cdPath, os.Path(pr))
                canonicalBase = basePath.toIO.getCanonicalPath
                canonicalRoot = os.Path(pr).toIO.getCanonicalPath
                _ <- IO.raiseUnless(canonicalBase.startsWith(canonicalRoot))(
                  new RuntimeException("path outside project root")
                )
                _ <- IO.blocking { os.makeDir.all(basePath) }
              yield cdPath)
                .flatMap { p =>
                  wsSend(io.circe.Json.obj("type" -> "dirCreated".asJson, "path" -> p.asJson))
                }
                .handleErrorWith { e =>
                  logger.warn(s"createDir failed: ${e.getMessage}")
                  wsSend(io.circe.Json.obj("type" -> "fileOpError".asJson, "error" -> e.getMessage.asJson))
                }
            else IO.unit
            end if

          case "deletePath" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val hc = json.hcursor
            val dpSessionId = hc.downField("sessionId").as[String].getOrElse("")
            val dpPath = hc.downField("path").as[String].getOrElse("")
            if dpSessionId.nonEmpty && dpPath.nonEmpty then
              val overrideRoot = hc.downField("rootPath").as[Option[String]].toOption.flatten
              (for
                pr <- overrideRoot match
                  case Some(root) => IO.pure(root)
                  case None =>
                    for
                      metaOpt <- sessionStore.getSessionMeta(dpSessionId)
                      folderId = metaOpt.flatMap(_.folderId)
                      prOpt <- sessionStore.resolveProjectRoot(folderId)
                    yield prOpt.getOrElse((PathUtil.dataRoot / "projects").toString)
                basePath = PathUtil.resolvePath(dpPath, os.Path(pr))
                canonicalBase = basePath.toIO.getCanonicalPath
                canonicalRoot = os.Path(pr).toIO.getCanonicalPath
                _ <- IO.raiseUnless(canonicalBase.startsWith(canonicalRoot))(
                  new RuntimeException("path outside project root")
                )
                // Prevent deleting the project root itself
                _ <- IO.raiseWhen(canonicalBase == canonicalRoot)(new RuntimeException("cannot delete project root"))
                _ <- IO.blocking { if os.exists(basePath) then os.remove(basePath) }
              yield dpPath)
                .flatMap { p =>
                  wsSend(io.circe.Json.obj("type" -> "pathDeleted".asJson, "path" -> p.asJson))
                }
                .handleErrorWith { e =>
                  logger.warn(s"deletePath failed: ${e.getMessage}")
                  wsSend(io.circe.Json.obj("type" -> "fileOpError".asJson, "error" -> e.getMessage.asJson))
                }
            else IO.unit
            end if

          // F1 file-explorer single-item move: relocate a file/dir into a
          // target directory within the same project root (rename semantics).
          case "movePath" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val hc = json.hcursor
            val mpSessionId = hc.downField("sessionId").as[String].getOrElse("")
            val mpPath = hc.downField("path").as[String].getOrElse("")
            val mpTargetDir = hc.downField("targetDir").as[String].getOrElse("")
            // targetDir may be empty: dropping onto the tree's blank root area
            // moves the item to the project root (frontend contract).
            if mpSessionId.nonEmpty && mpPath.nonEmpty then
              val overrideRoot = hc.downField("rootPath").as[Option[String]].toOption.flatten
              (for
                pr <- overrideRoot match
                  case Some(root) => IO.pure(root)
                  case None =>
                    for
                      metaOpt <- sessionStore.getSessionMeta(mpSessionId)
                      folderId = metaOpt.flatMap(_.folderId)
                      prOpt <- sessionStore.resolveProjectRoot(folderId)
                    yield prOpt.getOrElse((PathUtil.dataRoot / "projects").toString)
                rootPath = os.Path(pr)
                newPath <- IO
                  .blocking(WebSocketRoutes.movePathSafely(mpPath, mpTargetDir, rootPath))
                  .flatMap {
                    case Right(np) => IO.pure(np)
                    case Left(err) => IO.raiseError(new RuntimeException(err))
                  }
              yield (mpPath, newPath))
                .flatMap { case (oldPath, newPath) =>
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "pathMoved".asJson,
                      "oldPath" -> oldPath.asJson,
                      "newPath" -> newPath.asJson
                    )
                  )
                }
                .handleErrorWith { e =>
                  logger.warn(s"movePath failed: ${e.getMessage}")
                  wsSend(io.circe.Json.obj("type" -> "fileOpError".asJson, "error" -> e.getMessage.asJson))
                }
            else IO.unit
            end if

          // F1 file-explorer multi-select: batch delete in ONE round trip.
          // Per-path guards identical to deletePath; failures are aggregated
          // per item so a partial failure never blocks the rest.
          case "deletePaths" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val hc = json.hcursor
            val dpsSessionId = hc.downField("sessionId").as[String].getOrElse("")
            val dpsPaths = hc.downField("paths").as[List[String]].getOrElse(Nil)
            if dpsSessionId.nonEmpty && dpsPaths.nonEmpty then
              val overrideRoot = hc.downField("rootPath").as[Option[String]].toOption.flatten
              (for
                pr <- overrideRoot match
                  case Some(root) => IO.pure(root)
                  case None =>
                    for
                      metaOpt <- sessionStore.getSessionMeta(dpsSessionId)
                      folderId = metaOpt.flatMap(_.folderId)
                      prOpt <- sessionStore.resolveProjectRoot(folderId)
                    yield prOpt.getOrElse((PathUtil.dataRoot / "projects").toString)
                (deleted, failed) <- WebSocketRoutes.deletePathsSafely(dpsPaths, os.Path(pr))
              yield (deleted, failed))
                .flatMap { case (deleted, failed) =>
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "pathsDeleted".asJson,
                      "deleted" -> deleted.asJson,
                      "failed" -> failed.map { case (p, err) =>
                        io.circe.Json.obj("path" -> p.asJson, "error" -> err.asJson)
                      }.asJson
                    )
                  )
                }
                .handleErrorWith { e =>
                  logger.warn(s"deletePaths failed: ${e.getMessage}")
                  wsSend(io.circe.Json.obj("type" -> "fileOpError".asJson, "error" -> e.getMessage.asJson))
                }
            else IO.unit
            end if

          case "writeFile" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val hc = json.hcursor
            val wrSessionId = hc.downField("sessionId").as[String].getOrElse("")
            val wrFilePath = hc.downField("path").as[String].getOrElse("")
            val wrContent = hc.downField("content").as[String].getOrElse("")
            if wrSessionId.nonEmpty && wrFilePath.nonEmpty then
              val overrideRoot = hc.downField("rootPath").as[Option[String]].toOption.flatten
              (for
                pr <- overrideRoot match
                  case Some(root) => IO.pure(root)
                  case None =>
                    for
                      metaOpt <- sessionStore.getSessionMeta(wrSessionId)
                      folderId = metaOpt.flatMap(_.folderId)
                      prOpt <- sessionStore.resolveProjectRoot(folderId)
                    yield prOpt.getOrElse((PathUtil.dataRoot / "projects").toString)
                basePath = PathUtil.resolvePath(wrFilePath, os.Path(pr))
                canonicalBase = basePath.toIO.getCanonicalPath
                canonicalRoot = os.Path(pr).toIO.getCanonicalPath
                _ <- IO.raiseUnless(canonicalBase.startsWith(canonicalRoot))(
                  new RuntimeException("path outside project root")
                )
                _ <- IO.blocking {
                  os.write.over(basePath, wrContent)
                }
              yield basePath.toString)
                .flatMap { absPath =>
                  logger.info(s"File saved: $absPath (${wrContent.length} chars)")
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "fileSaved".asJson,
                      "path" -> wrFilePath.asJson
                    )
                  )
                }
                .handleErrorWith { e =>
                  logger.warn(s"writeFile failed: ${e.getMessage}")
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "fileSaveError".asJson,
                      "path" -> wrFilePath.asJson,
                      "error" -> e.getMessage.asJson
                    )
                  )
                }
            else IO.unit
            end if

          case "getActiveBgTasks" =>
            nebflow.core.tools.BgTaskRegistry.activeTasksJson.flatMap { tasksJson =>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "activeBgTasks".asJson,
                  "tasks" -> tasksJson
                )
              )
            }

          case "getActiveAgents" =>
            // Snapshot restore for the bg-agent indicator: the frontend builds
            // sessionBgAgents incrementally from realtime agentStart/agentDone
            // events, which are not replayed after a browser refresh. Read the
            // unified AgentRegistry (sessionId -> AgentRecord) and filter to
            // active sub-agents (root agents excluded). Team agents are
            // included only while BUSY — they are long-lived in the registry
            // (see filterActiveAgents), so registry presence alone would
            // report idle team agents as running ghosts.
            // agentId == sessionId (nodeSessionId) so restored entries match
            // subsequent realtime events (agentToolStart/agentDone key on it)
            // — pinned by ActiveAgentsEntrySpec, see activeAgentEntryJson.
            sharedResources.agentRegistry.get.flatMap { registry =>
              WebSocketRoutes.filterActiveAgents(registry).flatMap { active =>
                active
                  .traverse { rec =>
                    // retryCount 来自 taskStore（2026-08-22 缺口 2：快照自带三
                    // 字段之一；Ephemeral/无任务记录 → 0）
                    sharedResources.subAgentTaskStore.findByTaskId(rec.sessionId).flatMap { taskOpt =>
                      sessionStore.getSessionMeta(rec.sessionId).map { meta =>
                        WebSocketRoutes.activeAgentEntryJson(rec, meta, taskOpt.map(_.retryCount))
                      }
                    }
                  }
                  .flatMap { agents =>
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "activeAgents".asJson,
                        "agents" -> agents.asJson
                      )
                    )
                  }
              }
            }

          case "cancelBackgroundJob" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val cancelSessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
            val jobId = json.hcursor.downField("jobId").as[String].getOrElse("")
            if cancelSessionId.nonEmpty && jobId.nonEmpty then
              nebflow.core.tools.ShellSession
                .forSession(cancelSessionId)
                .flatMap { shell =>
                  shell.cancelBackgroundJob(jobId).flatMap { cancelled =>
                    val logMsg =
                      if cancelled then s"Cancelled background job $jobId"
                      else s"Background job $jobId not found or already completed"
                    logger.info(logMsg, "sessionId" -> cancelSessionId, "jobId" -> jobId) *>
                      // Notify agent so it can process cancellation
                      ensureAgent(cancelSessionId) { ref =>
                        ref ! AgentCommand.ExternalEvent(
                          source = "background-task",
                          eventType = "cancelled",
                          payload = s"[Background task cancelled] Job ID: $jobId",
                          metadata = io.circe.JsonObject(
                            "jobId" -> jobId.asJson
                          ),
                          correlationId = Some(jobId)
                        )
                      } *>
                      // Send completion update to frontend so the task is removed from the dropdown
                      wsSend(
                        io.circe.Json.obj(
                          "type" -> "backgroundTaskUpdate".asJson,
                          "sessionId" -> cancelSessionId.asJson,
                          "taskId" -> jobId.asJson,
                          "description" -> "".asJson,
                          "status" -> "completed".asJson
                        )
                      )
                  }
                }
                .handleErrorWith { e =>
                  logger.warn(s"cancelBackgroundJob failed for session=$cancelSessionId job=$jobId: ${e.getMessage}")
                  // Send a completion update even on error, so the frontend removes the task
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "backgroundTaskUpdate".asJson,
                      "sessionId" -> cancelSessionId.asJson,
                      "taskId" -> jobId.asJson,
                      "description" -> "".asJson,
                      "status" -> "failed".asJson
                    )
                  ).handleErrorWith(_ => IO.unit)
                }
            else IO.unit
            end if

          case "cancelFlow" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val flowName = json.hcursor.downField("name").as[String].getOrElse("")
            val cfSessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
            val instanceId = json.hcursor.downField("instanceId").as[String].getOrElse("")
            if instanceId.nonEmpty then
              // Cancel a DAG flow instance directly
              nebflow.core.flow.RunningFlowRegistry.cancel(instanceId) *>
                logger.info(s"Cancel DAG flow '$instanceId' requested by user via WS")
            else if flowName.nonEmpty && cfSessionId.nonEmpty then
              // Cancel a team pipeline flow via FlowTreeActor
              nebflow.core.flow.FlowTreeRegistry.get(cfSessionId).flatMap {
                case Some(treeRef) =>
                  treeRef ! nebflow.core.flow.TreeCommand.CancelPipeline(flowName)
                  logger.info(s"Cancel flow '$flowName' requested by user via WS")
                case None =>
                  logger.warn(s"Cannot cancel flow '$flowName': no FlowTreeActor for session")
              }
            else IO.unit

          case "getHistory" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val sessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
            val limit = json.hcursor.downField("limit").as[Int].getOrElse(50)
            val beforeIndex = json.hcursor.downField("beforeIndex").as[Option[Int]].getOrElse(None)
            if sessionId.nonEmpty then
              (sharedResources.sessionStore
                .getHistoryPage(sessionId, limit, beforeIndex)
                .attempt
                .flatMap {
                  case Right((msgs, total, offset, hasMore)) =>
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "historyPage".asJson,
                        "sessionId" -> sessionId.asJson,
                        "messages" -> msgs.asJson,
                        "total" -> total.asJson,
                        "offset" -> offset.asJson,
                        "hasMore" -> hasMore.asJson
                      )
                    )
                  case Left(_) =>
                    // Send empty historyPage so the frontend doesn't get stuck
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "historyPage".asJson,
                        "sessionId" -> sessionId.asJson,
                        "messages" -> List.empty[io.circe.Json].asJson,
                        "total" -> 0.asJson,
                        "offset" -> 0.asJson,
                        "hasMore" -> false.asJson
                      )
                    )
                })
                .handleErrorWith { e =>
                  wsSend(
                    io.circe.Json
                      .obj("type" -> "error".asJson, "message" -> s"getHistory failed: ${e.getMessage}".asJson)
                  )
                }
            else IO.unit
            end if

          case "listAgents" =>
            agentService.listAgents.flatMap { agents =>
              val agentsJson = agents.map { a =>
                io.circe.Json.obj(
                  "name" -> a.name.asJson,
                  "description" -> a.description.asJson,
                  "displayName" -> a.displayName.getOrElse(a.name).asJson,
                  "avatar" -> a.avatar.asJson,
                  "tools" -> a.tools.asJson
                )
              }
              wsSend(
                io.circe.Json.obj(
                  "type" -> "agentList".asJson,
                  "agents" -> agentsJson.asJson
                )
              )
            }

          case "listAgentSessions" =>
            val agentName = parse(text).flatMap(_.hcursor.downField("name").as[String]).getOrElse("")
            if agentName.nonEmpty then sendAgentSessionListByName(wsSend, agentName)
            else IO.unit
            end if

          case "listSessions" =>
            // Manual trigger for re-fetching the unified session list on WS
            // reconnect (sessions may have been created/removed while the
            // frontend was disconnected). Same payload as the initial push.
            sessionService.sendSessionList(wsSend, "Nebula")

          // ===== Folder Management =====

          case "createFolder" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val name = json.hcursor.downField("name").as[String].getOrElse("New Folder")
            val parentId = json.hcursor.downField("parentId").as[Option[String]].getOrElse(None)
            val agentNameFromMsg = json.hcursor.downField("agentName").as[String].getOrElse("")
            if name.nonEmpty then
              val agentNameIO =
                if agentNameFromMsg.nonEmpty then IO.pure(agentNameFromMsg)
                else sessionStore.getActiveMeta.map(_.flatMap(_.agentName).getOrElse("Nebula"))
              agentNameIO
                .flatMap { agentName =>
                  sessionService.createFolder(name, parentId, agentName).flatMap { _ =>
                    sendAgentSessionListByName(wsSend, agentName)
                  }
                }
                .handleErrorWith { e =>
                  wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
                }
            else IO.unit

          case "renameFolder" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
            val newName = json.hcursor.downField("name").as[String].getOrElse("")
            if folderId.nonEmpty && newName.nonEmpty then
              sessionService
                .renameFolder(folderId, newName)
                .flatMap { _ =>
                  sessionStore.getActiveMeta.flatMap { metaOpt =>
                    val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
                    sendAgentSessionListByName(wsSend, agentName)
                  }
                }
                .handleErrorWith { e =>
                  wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
                }
            else IO.unit

          case "deleteFolder" =>
            val folderId = parse(text).flatMap(_.hcursor.downField("folderId").as[String]).getOrElse("")
            if folderId.nonEmpty then
              sessionService
                .deleteFolder(folderId)
                .flatMap { _ =>
                  sessionStore.getActiveMeta.flatMap { metaOpt =>
                    val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
                    sendAgentSessionListByName(wsSend, agentName)
                  }
                }
                .handleErrorWith { e =>
                  wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
                }
            else IO.unit

          case "moveSessionToFolder" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val sessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
            val folderId = json.hcursor.downField("folderId").as[Option[String]].getOrElse(None)
            if sessionId.nonEmpty then
              sessionService
                .moveSessionToFolder(sessionId, folderId)
                .flatMap { _ =>
                  sendAgentSessionList(wsSend, sessionId)
                }
                .handleErrorWith { e =>
                  wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
                }
            else IO.unit

          case "moveFolder" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
            val parentId = json.hcursor.downField("parentId").as[Option[String]].getOrElse(None)
            if folderId.nonEmpty then
              sessionService
                .moveFolder(folderId, parentId)
                .flatMap { _ =>
                  sessionStore.getActiveMeta.flatMap { metaOpt =>
                    val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
                    sendAgentSessionListByName(wsSend, agentName)
                  }
                }
                .handleErrorWith { e =>
                  wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
                }
            else IO.unit

          case "setFolderProjectRoot" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
            val projectRoot = json.hcursor.downField("projectRoot").as[Option[String]].getOrElse(None)
            if folderId.nonEmpty then
              sessionService
                .setFolderProjectRoot(folderId, projectRoot)
                .flatMap {
                  case Right(_) =>
                    // Use the folder's own agent name, not the active session's,
                    // to ensure the frontend receives the update regardless of which agent tab is active.
                    sessionStore.getFolderAgentName(folderId).flatMap { agentOpt =>
                      val agentName = agentOpt.getOrElse("Nebula")
                      sendAgentSessionListByName(wsSend, agentName)
                    }
                  case Left(err) =>
                    wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> err.asJson))
                }
                .handleErrorWith { e =>
                  wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
                }
            else IO.unit
            end if

          // Directory browser for project root selection
          case "browsePath" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val path = json.hcursor.downField("path").as[String].getOrElse("~")
            val expanded = if path.startsWith("~") then System.getProperty("user.home") + path.drop(1) else path
            IO.blocking {
              val dir = os.Path(expanded, os.pwd)
              if os.isDir(dir) then
                val entries = os.list(dir).filter(os.isDir).sortBy(_.last)
                val result = entries.take(200).map { p =>
                  io.circe.Json.obj("name" -> p.last.asJson, "path" -> p.toString.asJson)
                }
                io.circe.Json.obj(
                  "type" -> "browseResult".asJson,
                  "path" -> dir.toString.asJson,
                  "entries" -> result.asJson
                )
              else
                io.circe.Json.obj(
                  "type" -> "browseResult".asJson,
                  "path" -> path.asJson,
                  "entries" -> io.circe.Json.arr()
                )
              end if
            }.flatMap(wsSend)
              .handleErrorWith { e =>
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "browseResult".asJson,
                    "path" -> path.asJson,
                    "entries" -> io.circe.Json.arr(),
                    "error" -> e.getMessage.asJson
                  )
                )
              }

          // ===== Folder Rules Management =====

          case "getRules" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
            if folderId.nonEmpty then
              val content = RulesStore.loadFolderRules(folderId).getOrElse("")
              wsSend(
                io.circe.Json.obj(
                  "type" -> "rulesData".asJson,
                  "folderId" -> folderId.asJson,
                  "content" -> content.asJson
                )
              )
            else IO.unit

          case "saveRules" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
            val content = json.hcursor.downField("content").as[String].getOrElse("")
            if folderId.nonEmpty then
              RulesStore.saveFolderRules(folderId, content) *>
                wsSend(io.circe.Json.obj("type" -> "rulesSaved".asJson, "folderId" -> folderId.asJson))
            else IO.unit

          case "deleteRules" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
            if folderId.nonEmpty then
              RulesStore.deleteFolderRules(folderId) *>
                wsSend(io.circe.Json.obj("type" -> "rulesDeleted".asJson, "folderId" -> folderId.asJson))
            else IO.unit

          case "rulesStatus" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val folderId = json.hcursor.downField("folderId").as[String].getOrElse("")
            if folderId.nonEmpty then
              wsSend(
                io.circe.Json.obj(
                  "type" -> "rulesStatus".asJson,
                  "folderId" -> folderId.asJson,
                  "exists" -> RulesStore.exists(folderId).asJson,
                  "preview" -> RulesStore.preview(folderId).asJson
                )
              )
            else IO.unit

          case "getAgentSystemPrompt" =>
            val agentName = parse(text).flatMap(_.hcursor.downField("name").as[String]).getOrElse("")
            if agentName.nonEmpty then
              agentService.getSystemPrompt(agentName).flatMap { mdOpt =>
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "agentSystemPrompt".asJson,
                    "name" -> agentName.asJson,
                    "systemMd" -> mdOpt.getOrElse("").asJson
                  )
                )
              }
            else IO.unit

          case "updateAgentSystemPrompt" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val agentName = json.hcursor.downField("name").as[String].getOrElse("")
            val systemMd = json.hcursor.downField("systemMd").as[String].getOrElse("")
            if agentName.nonEmpty then
              agentService.updateSystemPrompt(agentName, systemMd) *>
                wsSend(io.circe.Json.obj("type" -> "agentSystemPromptSaved".asJson, "name" -> agentName.asJson))
            else IO.unit

          case "updateAgentTools" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val agentName = json.hcursor.downField("name").as[String].getOrElse("")
            val tools = json.hcursor.downField("tools").as[List[String]].getOrElse(List("*"))
            if agentName.nonEmpty then
              agentService.updateTools(agentName, tools) *>
                wsSend(io.circe.Json.obj("type" -> "agentSystemPromptSaved".asJson, "name" -> agentName.asJson))
            else IO.unit

          case "createAgentSession" =>
            val agentName = parse(text).flatMap(_.hcursor.downField("name").as[String]).getOrElse("")
            if agentName.nonEmpty then
              (for
                defnOpt <- sharedResources.agentLibrary.get(agentName)
                defn <- IO.fromOption(defnOpt)(new RuntimeException(s"Agent not found: $agentName"))
                meta <- sessionService.createSession(
                  s"Agent: ${defn.displayName.getOrElse(defn.name)}",
                  agentName = Some(agentName)
                )
                _ <- sessionService.switchSession(meta.id)
                _ <- sessionService.sendSessionList(wsSend, agentName)
              yield ()).handleErrorWith { e =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
              }
            else IO.unit

          case "getMemory" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val scope = json.hcursor.downField("scope").as[String].getOrElse("session")
            val sessionIdParam = json.hcursor.downField("sessionId").as[String].toOption.filter(_.nonEmpty)
            val metaIO = sessionIdParam match
              case Some(sid) => sessionStore.getSessionMeta(sid)
              case None => sessionStore.getActiveMeta
            (metaIO
              .flatMap { metaOpt =>
                val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
                val teamName = metaOpt.flatMap(_.flowName)
                val content = scope match
                  case "user" => MemoryStore.loadUserMemory.getOrElse("")
                  case "agent" =>
                    teamName match
                      case Some(tn) => MemoryStore.loadTeamAgentMemory(tn, agentName).getOrElse("")
                      case None => MemoryStore.loadAgentMemory(agentName).getOrElse("")
                  case _ => ""
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "memoryData".asJson,
                    "scope" -> scope.asJson,
                    "content" -> content.asJson
                  )
                )
              })
              .handleErrorWith { e =>
                logger.warn(s"getMemory error: ${e.getMessage}")
                wsSend(
                  io.circe.Json.obj("type" -> "error".asJson, "message" -> s"getMemory failed: ${e.getMessage}".asJson)
                )
              }

          case "saveMemory" =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val scope = json.hcursor.downField("scope").as[String].getOrElse("session")
            val content = json.hcursor.downField("content").as[String].getOrElse("")
            val sessionIdParam = json.hcursor.downField("sessionId").as[String].toOption.filter(_.nonEmpty)
            val metaIO = sessionIdParam match
              case Some(sid) => sessionStore.getSessionMeta(sid)
              case None => sessionStore.getActiveMeta
            (metaIO
              .flatMap { metaOpt =>
                val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
                val teamName = metaOpt.flatMap(_.flowName)
                val save = scope match
                  case "user" => MemoryStore.saveUserMemory(content)
                  case "agent" =>
                    teamName match
                      // 2026-08-31 裁定①: team agents have no memory — refuse to
                      // resurrect deleted team memory.md files via the modal.
                      case Some(_) => IO.unit
                      case None => MemoryStore.saveAgentMemory(agentName, content)
                  case _ => IO.unit
                save *> wsSend(io.circe.Json.obj("type" -> "memorySaved".asJson, "scope" -> scope.asJson))
              })
              .handleErrorWith { e =>
                logger.warn(s"saveMemory error: ${e.getMessage}")
                wsSend(
                  io.circe.Json.obj("type" -> "error".asJson, "message" -> s"saveMemory failed: ${e.getMessage}".asJson)
                )
              }

          case "memoryStatus" =>
            sessionStore.getActiveMeta.flatMap { metaOpt =>
              val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
              val teamName = metaOpt.flatMap(_.flowName)
              val (agentExists, agentPreview) = teamName match
                case Some(tn) =>
                  (MemoryStore.teamAgentExists(tn, agentName), MemoryStore.teamAgentPreview(tn, agentName))
                case None => (MemoryStore.agentExists(agentName), MemoryStore.agentPreview(agentName))
              wsSend(
                io.circe.Json.obj(
                  "type" -> "memoryStatus".asJson,
                  "user" -> io.circe.Json.obj(
                    "exists" -> MemoryStore.userExists.asJson,
                    "preview" -> MemoryStore.userPreview.asJson
                  ),
                  "agent" -> io.circe.Json.obj(
                    "exists" -> agentExists.asJson,
                    "preview" -> agentPreview.asJson
                  )
                )
              )
            }

          case "checkUpdate" =>
            val currentVer = nebflow.Version.string
            val result = IO
              .blocking {
                try
                  // #29: 仓库 private 后 GH API 未认证不可用——版本检查读 COS
                  // latest-version.txt（stable 通道，与旧 releases/latest 同语义）。
                  val url = "https://nebflow-releases-1411212853.cos.ap-nanjing.myqcloud.com/latest-version.txt"
                  val conn = java.net.URI.create(url).toURL.openConnection()
                  conn.setConnectTimeout(5000)
                  conn.setReadTimeout(5000)
                  val raw = new String(conn.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim
                  if raw.nonEmpty then Some((raw, raw)) // (tag, releaseName)——COS 无 release 名，版本号兜底
                  else None
                catch case _: Exception => None
              }
              .flatMap {
                case Some((tag, releaseName)) =>
                  val latestVer = tag.stripPrefix("v")
                  val hasUpdate = latestVer != currentVer
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "updateCheckResult".asJson,
                      "currentVersion" -> currentVer.asJson,
                      "latestVersion" -> latestVer.asJson,
                      "hasUpdate" -> hasUpdate.asJson,
                      "releaseName" -> releaseName.asJson
                    )
                  )
                case None =>
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "updateCheckResult".asJson,
                      "currentVersion" -> currentVer.asJson,
                      "error" -> "Failed to check for updates".asJson
                    )
                  )
              }
            result

          case "doUpdate" =>
            val beta = parse(text).toOption.flatMap(_.hcursor.downField("beta").as[Boolean].toOption).getOrElse(false)
            wsSend(io.circe.Json.obj("type" -> "updateStarted".asJson)) *>
              IO.blocking {
                import sys.process.*
                val isWindows = System.getProperty("os.name").toLowerCase.contains("win")
                // String concat (not s"") — the powershell snippets contain
                // $env: which an interpolator would try to resolve.
                val script =
                  if beta then
                    if isWindows then
                      """powershell -Command "$env:CHANNEL='beta'; iwr """ + Branding.installPs1Url + """ | iex" """
                    else "curl -fsSL " + Branding.installUrl + " | sh -s -- --beta"
                  else if isWindows then """powershell -Command "& { iwr """ + Branding.installPs1Url + """ | iex }" """
                  else "curl -fsSL " + Branding.installUrl + " | sh"
                val exitCode = script.!
                if exitCode == 0 then
                  wsSend(io.circe.Json.obj("type" -> "updateCompleted".asJson, "success" -> true.asJson))
                else
                  wsSend(
                    io.circe.Json
                      .obj(
                        "type" -> "updateCompleted".asJson,
                        "success" -> false.asJson,
                        "error" -> s"Exit code: $exitCode".asJson
                      )
                  )
              }.flatten
                .handleErrorWith { e =>
                  wsSend(
                    io.circe.Json
                      .obj(
                        "type" -> "updateCompleted".asJson,
                        "success" -> false.asJson,
                        "error" -> e.getMessage.asJson
                      )
                  )
                }

          case "remoteUpdate" =>
            def tryRelayUpdate(
              ns: nebflow.neblink.NeblinkService,
              peer: nebflow.neblink.PeerInfo,
              beta: Boolean,
              p2pError: String
            ): IO[Unit] =
              ns.relayClientOpt match
                case Some(client) =>
                  logger.info(s"P2P update failed ($p2pError), trying relay to ${peer.deviceName}") *>
                    client.relayUpdate(peer.deviceId, beta).flatMap {
                      case Right(msg) =>
                        wsSend(io.circe.Json.obj(
                          "type" -> "remoteUpdateResult".asJson,
                          "success" -> true.asJson,
                          "device" -> peer.deviceName.asJson,
                          "message" -> msg.asJson
                        ))
                      case Left(err) =>
                        wsSend(io.circe.Json.obj(
                          "type" -> "remoteUpdateResult".asJson,
                          "success" -> false.asJson,
                          "error" -> s"P2P: $p2pError; Relay: $err".asJson
                        ))
                    }
                case None =>
                  wsSend(io.circe.Json.obj(
                    "type" -> "remoteUpdateResult".asJson,
                    "success" -> false.asJson,
                    "error" -> p2pError.asJson
                  ))

            val hc = parse(text).toOption.map(_.hcursor).getOrElse(io.circe.Json.Null.hcursor)
            val targetDevice = hc.downField("device").as[String].getOrElse("")
            val beta = hc.downField("beta").as[Boolean].getOrElse(false)
            if targetDevice.isEmpty then
              wsSend(
                io.circe.Json.obj(
                  "type" -> "remoteUpdateResult".asJson,
                  "success" -> false.asJson,
                  "error" -> "Missing device name".asJson
                )
              )
            else
              sharedResources.neblinkService match
                case None =>
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "remoteUpdateResult".asJson,
                      "success" -> false.asJson,
                      "error" -> "NebLink not enabled".asJson
                    )
                  )
                case Some(neblinkService) =>
                  neblinkService.peers.flatMap { peers =>
                    peers.find(p =>
                      p.deviceName.equalsIgnoreCase(targetDevice) ||
                        p.deviceName.toLowerCase.contains(targetDevice.toLowerCase)
                    ) match
                      case None =>
                        wsSend(
                          io.circe.Json.obj(
                            "type" -> "remoteUpdateResult".asJson,
                            "success" -> false.asJson,
                            "error" -> s"Device '$targetDevice' not found".asJson
                          )
                        )
                      case Some(peer) =>
                        if peer.address.isEmpty then
                          wsSend(
                            io.circe.Json.obj(
                              "type" -> "remoteUpdateResult".asJson,
                              "success" -> false.asJson,
                              "error" -> s"Device '$targetDevice' has no address".asJson
                            )
                          )
                        else
                          logger.info(
                            s"Remote update: sending update request to ${peer.deviceName} at ${peer.address} (beta=$beta)"
                          ) *>
                            IO.blocking {
                              import sttp.client4.*
                              val body = io.circe.Json.obj("beta" -> beta.asJson).noSpaces
                              val resp = basicRequest
                                .post(sttp.model.Uri.unsafeParse(s"${peer.address}/api/neblink/update"))
                                .contentType("application/json")
                                .body(body)
                                .readTimeout(180.seconds)
                                .response(asStringAlways)
                                .send(neblinkService.httpBackend)
                              resp
                            }.flatMap { resp =>
                              if resp.code.isSuccess then
                                wsSend(
                                  io.circe.Json.obj(
                                    "type" -> "remoteUpdateResult".asJson,
                                    "success" -> true.asJson,
                                    "device" -> peer.deviceName.asJson,
                                    "message" -> "Update installed, device is restarting...".asJson
                                  )
                                )
                              else
                                // P2P returned an HTTP error — try relay before failing
                                tryRelayUpdate(neblinkService, peer, beta, s"Remote returned HTTP ${resp.code}")
                            }.handleErrorWith { e =>
                              tryRelayUpdate(neblinkService, peer, beta, s"Cannot reach ${peer.deviceName}: ${e.getMessage}")
                            }
                    end match
                  }
            end if

          case "getConfig" =>
            configService.isConfigured.flatMap { configured =>
              configService.getConfig.flatMap { cfg =>
                nebflow.core.OnboardingService.readState().flatMap { onboarding =>
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "configData".asJson,
                      "config" -> cfg.asJson,
                      "configured" -> configured.asJson,
                      // null = no marker yet (fresh install); frontend shows the wizard
                      "onboarding" -> onboarding.map(_.name).asJson
                    )
                  )
                }
              }
            }

          case "setOnboardingState" =>
            // F3 onboarding state machine: pending | done | skipped.
            // HARD GATE (server-side, user ruling 2026-08-15): done is
            // rejected unless a successful probeLlm is on record — the WS
            // surface can no longer bypass the gate the frontend enforces.
            val stJson = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val stStr = stJson.hcursor.downField("state").as[String].getOrElse("")
            nebflow.core.OnboardingService.OnboardingState.fromString(stStr) match
              case Some(st) =>
                nebflow.core.OnboardingService.setState(st).flatMap {
                  case Right(applied) =>
                    wsSend(io.circe.Json.obj("type" -> "onboardingStateSet".asJson, "state" -> applied.name.asJson))
                  case Left(reason) =>
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "error".asJson,
                        "code" -> "probe_required".asJson,
                        "message" -> reason.asJson
                      )
                    )
                }
              case None =>
                wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> s"invalid onboarding state: $stStr".asJson))

          case "probeLlm" =>
            // Onboarding HARD GATE (user ruling 2026-08-15): one real LLM call
            // through the global chain. The welcome message may only be sent
            // after this returns ok=true.
            nebflow.core.OnboardingService.probeLlm(sharedResources.llm).flatMap { pr =>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "probeResult".asJson,
                  "ok" -> pr.ok.asJson,
                  "provider" -> pr.provider.asJson,
                  "error" -> pr.error.asJson
                )
              )
            }

          case "autostartStatus" =>
            // Settings panel "start on login" toggle (F2) — shared logic with
            // the `nebflow autostart` CLI via AutoStartService.
            nebflow.core.AutoStartService.status().flatMap { st =>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "autostartStatusResult".asJson,
                  "enabled" -> st.enabled.asJson,
                  "supported" -> st.supported.asJson,
                  "reason" -> st.reason.asJson
                )
              )
            }

          case "autostartSet" =>
            val asJson = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val enable = asJson.hcursor.downField("enabled").as[Boolean].getOrElse(false)
            val op = if enable then nebflow.core.AutoStartService.enable() else nebflow.core.AutoStartService.disable()
            op.flatMap { res =>
              // Always answer with the authoritative post-op status; attach
              // the op message on failure so the UI can toast + revert the toggle.
              nebflow.core.AutoStartService.status().flatMap { st =>
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "autostartStatusResult".asJson,
                    "enabled" -> st.enabled.asJson,
                    "supported" -> st.supported.asJson,
                    "reason" -> st.reason.asJson,
                    "error" -> (if res.ok then None else Some(res.message)).asJson
                  )
                )
              }
            }

          case "updateConfig" =>
            val cfg = parse(text).flatMap(_.hcursor.downField("config").as[String]).getOrElse("")
            if cfg.nonEmpty then
              // 冻结修复（2026-08-27）：runtime 热更键以内存 ref 为权威——调用方的
              // config 快照可能陈旧（页面加载时刻底稿），不覆写则任意 provider 类
              // 保存会把 workSchedule/thinkingConfig/toolResultTtl 回滚到快照值。
              (sharedResources.freezeScheduleRef.get,
               sharedResources.thinkingConfigRef.get,
               sharedResources.toolResultTtlRef.get).mapN { (wsCfg, thCfg, ttlCfg) =>
                Map[String, io.circe.Json](
                  "workSchedule" -> wsCfg.asJson,
                  "thinkingConfig" -> thCfg.asJson,
                  "toolResultTtl" -> ttlCfg.asJson
                )
              }.flatMap { runtimeOverrides =>
              configService.updateConfig(cfg, runtimeOverrides).flatMap {
                case Left(err) =>
                  wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> err.asJson))
                case Right(_) =>
                  // #311: the first provider/model save seeds/repairs the default
                  // preset (from the just-saved llm.model chain) so agent model
                  // resolution never silently falls to a hidden global chain.
                  IO
                    .delay(nebflow.core.presets.PresetStore().ensureDefaultPreset())
                    .attempt
                    .flatMap {
                      case Right(_) => IO.unit
                      case Left(e)  => logger.warn(s"Default preset seeding failed: ${e.getMessage}")
                    } *>
                  // Hot-reload: update in-memory config and clear adapter cache
                  sharedResources.providerRegistry
                    .reloadConfig(Some(sharedResources.sessionModelOverrides))
                    .attempt
                    .flatMap {
                      case Right(staleIds) =>
                        // #33: keep the persisted session meta in sync — dropped
                        // overrides also clear their modelRef on disk so the UI
                        // state matches the in-memory session overrides.
                        staleIds.traverse_(id => sessionStore.updateSessionModel(id, None)) *>
                          logger.info("Config hot-reloaded successfully")
                      case Left(e) =>
                        logger.warn(s"Config hot-reload failed: ${e.getMessage}")
                    } *> wsSend(io.circe.Json.obj("type" -> "configUpdated".asJson, "success" -> true.asJson))
              }
              }
            else IO.unit

          case "toggleMcpServer" =>
            val hc = parse(text).toOption.map(_.hcursor).getOrElse(io.circe.Json.Null.hcursor)
            val serverId = hc.downField("serverId").as[String].getOrElse("")
            val enabled = hc.downField("enabled").as[Boolean].getOrElse(false)
            if serverId.nonEmpty then
              configRef.get.flatMap { cfg =>
                cfg.mcpServers.getOrElse(Map.empty).get(serverId) match
                  case Some(mcpCfg) =>
                    val action =
                      if enabled then mcpManager.enableServer(serverId, mcpCfg) else mcpManager.disableServer(serverId)
                    // 冻结修复（2026-08-27）：persist 持锁 + 失败回执（不再静默）。
                    action *>
                      nebflow.service.ConfigService.writeLocked(persistMcpServerEnabled(serverId, enabled)).attempt.flatMap {
                        case Left(e) =>
                          logger.warn(s"Failed to persist MCP server state: ${e.getMessage}") *>
                            wsSend(io.circe.Json.obj("type" -> "configUpdateFailed".asJson, "message" -> s"MCP 状态保存失败: ${e.getMessage}".asJson)) *>
                            broadcastMcpServersUpdate.handleErrorWith(_ => IO.unit)
                        case Right(_) => broadcastMcpServersUpdate.handleErrorWith(_ => IO.unit)
                      }
                  case None =>
                    logger.warn(s"toggleMcpServer: server '$serverId' not found in config") *> IO.unit
              }
            else IO.unit

          // ===== Dropbox: cross-device messaging & file transfer =====

          case "dropbox-send-text" =>
            val hc = parse(text).toOption.map(_.hcursor).getOrElse(io.circe.Json.Null.hcursor)
            val deviceId = hc.downField("deviceId").as[String].getOrElse("")
            val msgText = hc.downField("text").as[String].getOrElse("")
            if deviceId.nonEmpty && msgText.nonEmpty then
              sharedResources.dropboxService match
                case None =>
                  wsSend(io.circe.Json.obj("type" -> "dropboxError".asJson, "error" -> "Dropbox not enabled".asJson))
                case Some(svc) =>
                  svc
                    .sendText(deviceId, msgText)
                    .handleErrorWith(e =>
                      wsSend(io.circe.Json.obj("type" -> "dropboxError".asJson, "error" -> e.getMessage.asJson))
                    )
            else IO.unit

          case "dropbox-file-offer" =>
            val hc = parse(text).toOption.map(_.hcursor).getOrElse(io.circe.Json.Null.hcursor)
            val deviceId = hc.downField("deviceId").as[String].getOrElse("")
            val fileName = hc.downField("fileName").as[String].getOrElse("")
            val fileSize = hc.downField("fileSize").as[Long].getOrElse(0L)
            val mimeType = hc.downField("mimeType").as[String].getOrElse("")
            if deviceId.nonEmpty && fileName.nonEmpty then
              sharedResources.dropboxService match
                case None =>
                  wsSend(io.circe.Json.obj("type" -> "dropboxError".asJson, "error" -> "Dropbox not enabled".asJson))
                case Some(svc) =>
                  svc
                    .offerFile(deviceId, fileName, fileSize, mimeType)
                    .handleErrorWith(e =>
                      wsSend(io.circe.Json.obj("type" -> "dropboxError".asJson, "error" -> e.getMessage.asJson))
                    )
            else IO.unit

          case "dropbox-file-respond" =>
            val hc = parse(text).toOption.map(_.hcursor).getOrElse(io.circe.Json.Null.hcursor)
            val deviceId = hc.downField("deviceId").as[String].getOrElse("")
            val transferId = hc.downField("transferId").as[String].getOrElse("")
            val accepted = hc.downField("accepted").as[Boolean].getOrElse(false)
            if deviceId.nonEmpty && transferId.nonEmpty then
              sharedResources.dropboxService match
                case None => IO.unit
                case Some(svc) => svc.respondToOffer(deviceId, transferId, accepted).handleErrorWith(_ => IO.unit)
            else IO.unit

          case "dropbox-get-history" =>
            val hc = parse(text).toOption.map(_.hcursor).getOrElse(io.circe.Json.Null.hcursor)
            val deviceId = hc.downField("deviceId").as[String].getOrElse("")
            if deviceId.nonEmpty then
              sharedResources.dropboxService match
                case None => IO.unit
                case Some(svc) =>
                  svc.getHistory(deviceId).flatMap { msgs =>
                    wsSend(
                      io.circe.Json
                        .obj(
                          "type" -> "dropbox-history".asJson,
                          "deviceId" -> deviceId.asJson,
                          "messages" -> msgs.asJson
                        )
                    )
                  }
            else IO.unit
            end if

          case _ =>
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val content = json.hcursor.downField("content").as[String].getOrElse("")
            val attachments = json.hcursor.downField("attachments").as[List[io.circe.Json]].getOrElse(Nil)
            val clientMessageId = json.hcursor.downField("clientMessageId").as[Option[String]].getOrElse(None)
            val msgSessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
            val chatWidth = json.hcursor.downField("chatWidth").as[Int].getOrElse(0)

            if content.nonEmpty || attachments.nonEmpty then
              rateLimiter.check("ws").flatMap { allowed =>
                if !allowed then
                  logger.warn("Rate limit exceeded") *>
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "error".asJson,
                        "message" -> NebflowError.toUserMessage(NebflowError.RateLimited("websocket")).asJson
                      )
                    )
                else
                  // P1 2026-08-27: user-message handling must survive a client
                  // disconnect. The receive pipe evalMap cancels in-flight
                  // handlers when the WebSocket closes (user refresh after
                  // seeing no response). Attachment resolution (fs walk +
                  // Spotlight) can take 5-30s, during which a refresh cancels
                  // the chain *after* the upload file is saved but *before*
                  // history/dispatch run — the message is then silently lost
                  // (observed: uploads/<sid>/pasted-text-*.txt written, no
                  // input_history entry, no LLM turn). Uncancelable guarantees
                  // the persist+dispatch tail completes; clientMessageId dedup
                  // in AgentActor makes a client resend idempotent.
                  IO.uncancelable(_ =>
                    // Resolve projectRoot for local file search before processing attachments
                    (for
                      metaOpt <- sessionStore.getSessionMeta(msgSessionId)
                      folderId = metaOpt.flatMap(_.folderId)
                      projectRoot <- sessionStore.resolveProjectRoot(folderId)
                    yield (metaOpt, projectRoot)).flatMap { (metaOpt, projectRoot) =>
                    val blocks = scala.collection.mutable.ListBuffer.empty[ContentBlock]
                    if content.nonEmpty then blocks += ContentBlock.Text(content)

                    // Map attachment index → saved/local path (only non-image files get entries)
                    val savedPaths = scala.collection.mutable.Map.empty[Int, String]
                    attachments.zipWithIndex.foreach { case (att, attIdx) =>
                      val mimeType = att.hcursor.downField("mimeType").as[String].getOrElse("")
                      val data = att.hcursor.downField("data").as[String].getOrElse("")
                      val name = att.hcursor.downField("name").as[String].getOrElse("")
                      val hash = att.hcursor.downField("hash").as[String].getOrElse("")
                      val fileSize = att.hcursor.downField("size").as[Long].getOrElse(0L)
                      if mimeType.startsWith("image/") && data.nonEmpty then
                        // Save image to uploads dir so it has a local path (like non-image files)
                        val uploadDir = Config.NebflowHome / "uploads" / msgSessionId
                        try
                          os.makeDir.all(uploadDir)
                          val ext =
                            if mimeType.contains("png") then "png"
                            else if mimeType.contains("webp") then "webp"
                            else "jpg"
                          // Use the MIME-derived extension, not the original
                          // filename's: the frontend re-encodes uploads to JPEG
                          // (compressImage), so "shot.png" would otherwise be
                          // saved with JPEG bytes under a .png name — and
                          // ReadTool maps MIME by extension.
                          val stem = name.replaceAll("\\.[a-zA-Z0-9]+$", "")
                          val fileName = s"${System.nanoTime()}_$stem.$ext"
                          val safeName = fileName.replaceAll("[/\\\\]", "_").replace("..", "_")
                          val filePath = uploadDir / safeName
                          val decoded = java.util.Base64.getDecoder.decode(data)
                          os.write.over(filePath, decoded)
                          val absPath = filePath.toString
                          if absPath.startsWith(uploadDir.toString) then
                            savedPaths(attIdx) = absPath
                            // Give LLM both the image (visual) and the path (forwardable via Mail)
                            blocks += ContentBlock.Image(data, mimeType)
                            blocks += ContentBlock.Text(s"[用户附加图片: $absPath]")
                            logger.info(s"Saved image '$name' to $absPath (${decoded.length} bytes)")
                          else
                            blocks += ContentBlock.Image(data, mimeType)
                            logger.warn(s"Image '$name' path resolved outside upload dir, only sending visual")
                        catch
                          case e: Exception =>
                            logger.warn(s"Failed to save image '$name': ${e.getMessage}")
                            // Fallback: still send the image visually even if save failed
                            blocks += ContentBlock.Image(data, mimeType)
                        end try
                      else if mimeType.startsWith("image/") then
                        // Image without data — cannot process
                        blocks += ContentBlock.Text(s"[image: $name (无数据)]")
                      else
                        // Non-image: try to find the file locally by name + size + hash
                        // Search priority: project root → common user dirs → full home → Spotlight
                        // P1 2026-08-27: attachments generated in-memory by the frontend
                        // (large paste → pasted-text-*.txt, input.js paste handler) never
                        // exist on disk — the fs walk + Spotlight search below is a
                        // guaranteed miss costing 5-30s of silent processing. Skip
                        // straight to the data-save branch when base64 data is in hand.
                        val isFrontendBlob = name.startsWith("pasted-text-") && data.nonEmpty
                        val home = os.home.toString
                        val commonDirs = List("Downloads", "Desktop", "Documents")
                          .map(d => s"$home/$d")
                          .filter(d => java.nio.file.Files.isDirectory(java.nio.file.Path.of(d)))
                        val searchPaths = projectRoot.toList ::: commonDirs ::: List(home)
                        val localPath =
                          if !isFrontendBlob && hash.nonEmpty && fileSize > 0 then findLocalFile(name, hash, fileSize, searchPaths)
                          else None
                        // Fallback: macOS Spotlight (finds files in Library, Containers, etc.)
                        val spotlightPath = localPath match
                          case Some(_) => localPath
                          case None if !isFrontendBlob && hash.nonEmpty && fileSize > 0 => spotlightSearch(name, hash, fileSize)
                          case None => None
                        spotlightPath match
                          case Some(path) =>
                            savedPaths(attIdx) = path
                            blocks += ContentBlock.Text(s"[用户附加文件: $path]")
                            logger.info(s"Attachment '$name' resolved to local file: $path")
                          case None if data.nonEmpty =>
                            // Fallback: save uploaded content to disk, send path reference to LLM
                            val uploadDir = Config.NebflowHome / "uploads" / msgSessionId
                            try
                              os.makeDir.all(uploadDir)
                              val safeName = name.replaceAll("[/\\\\]", "_").replace("..", "_")
                              val fileName = s"${System.nanoTime()}_$safeName"
                              val filePath = uploadDir / fileName
                              val decoded = java.util.Base64.getDecoder.decode(data)
                              os.write.over(filePath, decoded)
                              val absPath = filePath.toString
                              if absPath.startsWith(uploadDir.toString) then
                                savedPaths(attIdx) = absPath
                                blocks += ContentBlock.Text(s"[用户附加文件: $absPath]")
                                logger.info(s"Saved attachment '$name' to $absPath (${decoded.length} bytes)")
                              else
                                logger.warn(s"Attachment '$name' resolved outside upload dir, skipping")
                                blocks += ContentBlock.Text(s"[file: $name (path unsafe)]")
                            catch
                              case e: Exception =>
                                logger.warn(s"Failed to save attachment '$name': ${e.getMessage}")
                                blocks += ContentBlock.Text(s"[file: $name (保存失败)]")
                            end try
                          case None =>
                            // No data and not found locally — tell the LLM the file name so it can
                            // use Read/Grep tools to locate it.
                            logger.warn(s"Attachment '$name' not found locally (hash=$hash, size=$fileSize)")
                            blocks += ContentBlock.Text(s"[用户附加文件: $name (未找到本地路径，请用工具搜索)]")
                        end match
                      end if
                    }

                    val sessionName = metaOpt.map(_.name).getOrElse("-")
                    val agentName = metaOpt.flatMap(_.agentName).getOrElse("")
                    logger.info(s"${logger.hl(sessionName)} User message: ${content
                        .take(60)}${if content.length > 60 then "..." else ""}") *>
                      logInputHistory(content, attachments, msgSessionId, sessionName, agentName) *>
                      // Record user message as UiMessage for history
                      (if msgSessionId.nonEmpty then
                         val attJson = attachments.zipWithIndex.map { case (att, idx) =>
                           val name = att.hcursor.downField("name").as[String].getOrElse("")
                           val mimeType = att.hcursor.downField("mimeType").as[String].getOrElse("")
                           val savedPath = savedPaths.getOrElse(idx, "")
                           io.circe.Json.obj(
                             "name" -> name.asJson,
                             "type" -> (if mimeType.startsWith("image/") then "image" else "file").asJson,
                             "path" -> (if savedPath.nonEmpty then savedPath.asJson else Json.Null)
                           )
                         }
                         val injected = json.hcursor.downField("injected").as[Boolean].getOrElse(false)
                         sharedResources.sessionStore
                           .appendUiMessages(
                             msgSessionId,
                             List(UiMessage.User(content, attJson, injected, timestamp = System.currentTimeMillis()))
                           )
                           .handleErrorWith(e => logger.warn(s"Failed to record user UiMessage: ${e.getMessage}"))
                       else IO.unit) *> {
                              // 任务工具重做（2026-08-30）：打回语义退役——
                              // taskRefs/refs(refType=task) 的 processTaskReturns
                              // 路径整体移除。#303 D1: resolve non-task refs
                              // (file/document/html-element) into [引用: …]
                              // injection blocks; refType=task fail-open 跳过。
                              processRefs(json, blocks).flatMap { _ =>
                                val blocksList = blocks.toList
                                ensureAgent(msgSessionId)(ref =>
                                  ref ! AgentCommand
                                    .UserInput(
                                      content,
                                      None,
                                      clientMessageId,
                                      Some(blocksList).filter(_.nonEmpty),
                                      chatWidth
                                    )
                                )
                              }
                      }
                  }
                  ) // end IO.uncancelable
              }
            else IO.unit
            end if
      yield ()
      end for
    end if
  end handleMessage

  /**
    * Shared body for the user-text input cases ("immediateInput" from the
    * frontend, "userMessage" from the CLI). Persists the user message as a
    * UiMessage bubble, then dispatches ImmediateInput to the session's agent.
    * The headless turn endpoint (POST /api/sessions/:id/turn) mirrors this
    * same sequence via [dispatchUserText] — keep the two in sync.
    *
    * 2026-08-25 22:28 裁定（覆盖同日 14:40 的「用户消息=全局跳过」）：发送
    * 文字消息**不再**触发全局解冻——冻结态下解冻的唯一入口 = skipFreeze 命令
    * （前端「跳过本次」按钮）或冻结段自然结束。消息到达 Frozen agent 后由
    * dispatch gate 拦截排队（B5 系统输入排队语义），不唤醒不解冻。前端在
    * 冻结态禁用输入栏，此处不再调 skipCurrentFreezeWindow 保持语义干净。
    */
  private def handleUserText(sessionId: String, content: String, source: String): IO[Unit] =
    if sessionId.nonEmpty && content.nonEmpty then
      // 第六件 QC (2026-08-30): the passthrough probe is WS-input-box ONLY —
      // headless REST turns ("rest-turn") go straight to dispatch so a P0
      // benchmark POST can never silently answer a pending card.
      if !WebSocketRoutes.probesPassthrough(source) then dispatchUserText(sessionId, content, source)
      else
        // 第六件 (2026-08-30): if THIS session has a pending AskUser card, the
        // input-box text is the answer — deliver it straight through as the
        // tool result (free-text). The agent's injection queue is untouched:
        // queued Mails/external events stay queued with the SAME length (场景②),
        // and the user's text itself never enqueues (no ImmediateInput on this
        // path). Miss (no pending AskUser) → normal dispatch, byte-identical
        // behavior (场景③).
        sharedResources.interactionHubRef.get.flatMap {
          case Some(hub) =>
            resolveRootSessionId(sessionId).flatMap { rootSid =>
              Deferred[IO, Boolean].flatMap { answered =>
                (hub ! nebflow.agent.InteractionHubCommand.AnswerViaChatInput(rootSid, content, answered)) *>
                  // QC: bounded wait — a hub crash / swallowed forkTurn must not
                  // park this gateway fiber forever; on timeout fall back to
                  // the normal dispatch path (message still reaches the agent).
                  answered.get.timeout(1.second).handleError(_ => false)
              }.flatMap {
                case true =>
                  logger.info(
                    s"User text ($source) → AskUser passthrough for session $sessionId (${content.length} chars)"
                  ) *>
                    // user bubble still lands (same shape as the card "Other" path)
                    sessionStore.appendUiMessages(
                      sessionId,
                      List(UiMessage.User(content, Nil, timestamp = System.currentTimeMillis()))
                    )
                case false => dispatchUserText(sessionId, content, source)
              }
            }
          case None => dispatchUserText(sessionId, content, source)
        }
    else
      // P1 2026-08-27 (frontend c1d57710): the queue "send-now" branch emitted an
      // empty-content frame (attachments dropped), which reached this path and was
      // silently dropped here — the defining feature of the incident was ZERO logs /
      // zero user feedback. An empty payload is not a dispatchable turn, but it must
      // leave a trace so a lost user message is diagnosable instead of invisible.
      // (Note: the default no-type frame path tolerates content-empty + attachments
      // payloads; this text-only path has no attachments to fall back on.)
      logger.warn(
        s"handleUserText($source): dropped EMPTY content for session '$sessionId' — no turn dispatched (frame was sent but carried no text)"
      ) *> IO.unit
  end handleUserText

  /** Normal message dispatch: user bubble + immediate injection into the
    * agent's turn pipeline. The ONLY path that enqueues — the AskUser
    * passthrough deliberately bypasses this (第六件, 2026-08-30).
    */
  private def dispatchUserText(sessionId: String, content: String, source: String): IO[Unit] =
    logger.info(s"User text ($source) for session $sessionId (${content.length} chars)") *>
      sessionStore.appendUiMessages(
        sessionId,
        List(UiMessage.User(content, Nil, timestamp = System.currentTimeMillis()))
      ) *>
      ensureAgent(sessionId)(ref => ref ! AgentCommand.ImmediateInput(content))


  /**
    * 冻结「跳过本次」唯一入口（2026-08-25 22:28 裁定）：前端冻结按钮发
    * skipFreeze 命令 → 置 freezeSkipUntilRef = 窗口结束时刻（eval 的
    * nextChangeAt；全天冻结兜底下一午夜）并立即 FreezeScheduler.scan——所有
    * Frozen agent 收到 CheckFreezeGate 重评估 → evalWithSkip 视为段外 →
    * 恢复工作（drain 冻结期间排队的消息）。窗口结束后 skip 自然过期，下一
    * 冻结段照常冻结（跳过非永久）。用户文字消息**不再**触发本函数（22:28
    * 裁定，见 handleUserText）。
    */
  private def skipCurrentFreezeWindow: IO[Unit] =
    for
      cfg <- sharedResources.freezeScheduleRef.get
      existingSkip <- sharedResources.freezeSkipUntilRef.get
      now = System.currentTimeMillis()
      window = nebflow.core.schedule.FreezeSchedule.evalWithSkip(cfg, existingSkip, now)
      _ <-
        if window.frozen then
          val until = nebflow.core.schedule.FreezeSchedule.skipUntilFor(window, now)
          logger.info(
            s"Freeze window skipped (skipFreeze; skipUntil=${until.map(u => new java.util.Date(u).toString).getOrElse("none")})"
          ) *>
            sharedResources.freezeSkipUntilRef.set(until) *>
              // P0（2026-08-30）：skip 持久化——重启/刷新后重连仍可见 skipped=true
              // （此前仅内存 Ref，后端进程重启即丢 → 输入框重新冻结）。best-effort：
              // 写盘失败不阻塞解冻（scan/broadcast 照常），warn 留痕。
              nebflow.core.schedule.FreezeSchedule
                .persistSkip(nebflow.core.PathUtil.dataRoot, until)
                .handleErrorWith(e =>
                  logger.warn(s"Failed to persist freeze skip: ${e.getMessage}")
                ) *>
              nebflow.core.processor.FreezeScheduler.scan(sharedResources) *>
            // 现象 2 契约（2026-08-30）：跳过 → 立即广播全局冻结态——前端输入栏
            // 状态机从 serverConfig.freezeState 读 frozen=false，不等任何 agent
            // 的 resumed 事件（无活跃 agent 时也要即时解除禁用）。
            broadcastServerConfig
        else IO.unit
    yield ()
  end skipCurrentFreezeWindow

  /**
    * Headless turn entry (P0 benchmark): dispatch a user text into a session
    * exactly like the WS "immediateInput"/"userMessage" cases do. Exposed for
    * RestApiRoutes' synchronous turn endpoint. Named distinctly from the WS
    * dispatch (QC nit, 2026-08-30): no overload shadowing, and the name says
    * REST-deterministic — this path never probes the AskUser passthrough.
    */
  def dispatchHeadlessTurn(sessionId: String, content: String): IO[Unit] =
    handleUserText(sessionId, content, source = "rest-turn")

  // ============================================================
  // Local file search for smart attachment resolution
  // ============================================================

  /** Directories to skip during filesystem search — build artifacts, caches, system dirs. */
  private val skipDirs = Set(
    "node_modules",
    ".git",
    "build",
    "target",
    "dist",
    ".cache",
    "__pycache__",
    ".gradle",
    ".idea",
    ".vscode",
    ".nebflow",
    // L3 rebrand compat: the brand data dir name must be ignored too
    // (identical to ".nebflow" today; duplicate entries are harmless here)
    Branding.homeDirName,
    "Library",
    "Applications",
    "Trash",
    ".Trash",
    ".npm",
    ".yarn",
    ".pnpm-store",
    ".cargo",
    ".rustup",
    ".conda",
    ".venv",
    "venv",
    "DerivedData",
    ".m2",
    ".ivy2",
    ".sbt",
    ".coursier",
    "Pods",
    "vendor",
    "bower_components",
    "Movies"
  )

  /** #303 D1/D5: resolve unified refs (refType ≠ task) into [引用: …]
    * injection blocks appended to the user message (spec §2.3 ③). Pointer
    * semantics — source + anchor only, never content (D5, ≤ ~100 token).
    * refType=task refs are gone (任务工具重做 2026-08-30, 打回退役) — they
    * fall through as unknown and are skipped fail-open (never block the
    * message or the other refs). */
  private def processRefs(
    frame: io.circe.Json,
    blocks: scala.collection.mutable.ListBuffer[ContentBlock]
  ): IO[Unit] =
    val refs = frame.hcursor.downField("refs").as[List[io.circe.Json]].getOrElse(Nil)
    refs.foreach { ref =>
      val refType = ref.hcursor.downField("refType").as[String].getOrElse("")
      if refType != "task" then
        RefResolver.resolve(ref).foreach(block => blocks += ContentBlock.Text(block))
    }
    IO.unit

  /**
   * Search for a file by name and verify its SHA-256 hash matches.
   * Searches directories in priority order. Does NOT follow symlinks.
   * First matches by filename + size (cheap), then verifies by SHA-256 (expensive).
   *
   * @param name filename to search for
   * @param expectedHash SHA-256 hex hash of the uploaded file
   * @param expectedSize file size in bytes (used for fast pre-filtering)
   * @param searchPaths directories to search in priority order
   * @return absolute path of a matching local file, or None
   */
  private def findLocalFile(
    name: String,
    expectedHash: String,
    expectedSize: Long,
    searchPaths: List[String]
  ): Option[String] =
    import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}
    import java.security.MessageDigest

    val targetName = name.replaceAll("[/\\\\]", "_").replace("..", "_")
    if targetName.isEmpty then None
    else
      val candidates = scala.collection.mutable.ListBuffer.empty[(Path, Long, Long)] // (path, mtime, size)

      for searchRoot <- searchPaths if candidates.isEmpty do
        val rootPath = Path.of(searchRoot)
        if Files.isDirectory(rootPath) then
          try
            // No FOLLOW_LINKS — avoids symlink loops on macOS bundles (.fcpcache etc.)
            Files.walkFileTree(
              rootPath,
              java.util.EnumSet.noneOf(classOf[java.nio.file.FileVisitOption]),
              Integer.MAX_VALUE,
              new SimpleFileVisitor[Path]:
                override def preVisitDirectory(
                  dir: Path,
                  attrs: java.nio.file.attribute.BasicFileAttributes
                ): FileVisitResult =
                  if skipDirs.contains(dir.getFileName.toString) then FileVisitResult.SKIP_SUBTREE
                  else FileVisitResult.CONTINUE

                override def visitFile(
                  file: Path,
                  attrs: java.nio.file.attribute.BasicFileAttributes
                ): FileVisitResult =
                  // Fast filter: match filename + size before expensive hash computation
                  if file.getFileName.toString == targetName && attrs.isRegularFile && attrs.size == expectedSize then
                    candidates += ((file, attrs.lastModifiedTime.toMillis, attrs.size))
                  FileVisitResult.CONTINUE

                override def visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult =
                  FileVisitResult.CONTINUE
            )
          catch
            case _: Exception => // skip inaccessible directories
        end if
      end for
      if candidates.isEmpty then None
      else
        // Verify SHA-256 for each candidate (already pre-filtered by name + size)
        val hashMatches = candidates.toList.filter { (path, _, _) =>
          try
            val bytes = Files.readAllBytes(path)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = digest.map(b => String.format("%02x", b)).mkString
            hex == expectedHash
          catch case _: Exception => false
        }

        hashMatches match
          case Nil => None
          case (p, _, _) :: Nil => Some(p.toString)
          case multiple =>
            // Multiple identical copies: pick the most recently modified with shortest path
            val sorted = multiple.sortBy { (path, mtime, _) =>
              (-mtime, path.toString.length)
            }
            val chosen = sorted.head._1.toString
            logger.info(s"Attachment '$name': ${multiple.size} local copies with same hash, chose: $chosen")
            Some(chosen)
      end if
    end if
  end findLocalFile

  /**
   * macOS Spotlight fallback — finds files that the filesystem walk misses
   *  (e.g. files inside ~/Library/Containers for WeChat, Telegram, etc.).
   */
  private def spotlightSearch(
    name: String,
    expectedHash: String,
    expectedSize: Long
  ): Option[String] =
    import java.security.MessageDigest
    val safeName = name.replaceAll("[/\\\\]", "_").replace("..", "_")
    try
      val cmd = Seq("mdfind", "-name", safeName)
      val output = scala.sys.process.Process(cmd).!!
      val lines = output.trim.split("\n").iterator.filter(_.nonEmpty)
      // Filter by size (cheap), then verify hash (expensive)
      lines.find { line =>
        val file = java.nio.file.Path.of(line)
        java.nio.file.Files.exists(file) &&
        java.nio.file.Files.isRegularFile(file) &&
        java.nio.file.Files.size(file) == expectedSize && {
          try
            val bytes = java.nio.file.Files.readAllBytes(file)
            val hex = MessageDigest
              .getInstance("SHA-256")
              .digest(bytes)
              .map(b => String.format("%02x", b))
              .mkString
            hex == expectedHash
          catch case _: Exception => false
        }
      }
    catch case _: Exception => None
    end try
  end spotlightSearch

  // ============================================================
  // UI Message recording — wraps wsSend to persist frontend-renderable history
  // ============================================================

  /** Per-session accumulator for text deltas (emitted one-by-one, saved on textDone). */
  private val sessionTextBuffers: Ref[IO, Map[String, String]] =
    Ref.unsafe[IO, Map[String, String]](Map.empty)

  /** Per-session accumulator for thinking deltas. */
  private val sessionThinkingBuffers: Ref[IO, Map[String, String]] =
    Ref.unsafe[IO, Map[String, String]](Map.empty)

  /** Per-session turn start time — set on first streaming event, consumed on done. */
  private val sessionTurnStarts: Ref[IO, Map[String, Long]] =
    Ref.unsafe[IO, Map[String, Long]](Map.empty)

  private def makeRecordingWsSend(
    sessionId: String,
    underlying: io.circe.Json => IO[Unit]
  ): io.circe.Json => IO[Unit] = json =>
    val hc = json.hcursor
    val eventType = hc.downField("type").as[String].getOrElse("")
    // "team-" is a frontend routing marker injected by MailTool.activateAgent,
    // not a real session id. Strip it so Mail-activated team agent streams
    // persist to the team session's own ui.json instead of a phantom
    // "team-<id>" session.
    def normalizeNodeSessionId(nsid: String): String =
      if nsid.startsWith("team-") then nsid.drop(5) else nsid
    val record = eventType match
      case "thinkingDelta" =>
        val delta = hc.downField("delta").as[String].getOrElse("")
        if delta.nonEmpty then
          sessionTurnStarts
            .update(m => if m.contains(sessionId) then m else m.updated(sessionId, System.currentTimeMillis())) *>
            sessionThinkingBuffers.update(m => m.updatedWith(sessionId)(_.map(_ + delta).orElse(Some(delta))))
        else IO.unit

      case "thinking" =>
        sessionTurnStarts
          .update(m => if m.contains(sessionId) then m else m.updated(sessionId, System.currentTimeMillis()))

      case "textDelta" =>
        // Accumulate text for this session
        val delta = hc.downField("delta").as[String].getOrElse("")
        if delta.nonEmpty then
          sessionTurnStarts
            .update(m => if m.contains(sessionId) then m else m.updated(sessionId, System.currentTimeMillis())) *>
            sessionTextBuffers.update(m => m.updatedWith(sessionId)(_.map(_ + delta).orElse(Some(delta))))
        else IO.unit

      case "toolStart" =>
        sessionTurnStarts
          .update(m => if m.contains(sessionId) then m else m.updated(sessionId, System.currentTimeMillis()))
        // Flush accumulated text + thinking before tool execution, matching frontend finishAi() behavior.
        // Without this, text output before a tool call stays in the in-memory buffer and is lost
        // when the user switches sessions before the final "done" event.
          *> sessionTextBuffers
            .modify { m =>
              val text = m.getOrElse(sessionId, "")
              (m - sessionId, text)
            }
            .flatMap { text =>
              if text.nonEmpty then
                sessionThinkingBuffers
                  .modify { m =>
                    val thinking = m.getOrElse(sessionId, "")
                    (m - sessionId, thinking)
                  }
                  .flatMap { thinking =>
                    sharedResources.sessionStore.appendUiMessages(
                      sessionId,
                      List(
                        UiMessage
                          .Ai(text, None, None, Option.when(thinking.nonEmpty)(thinking), System.currentTimeMillis())
                      )
                    )
                  }
              else
                sessionThinkingBuffers.update(_ - sessionId)
                IO.unit
            }

      case "roundComplete" =>
        // Flush accumulated text + thinking for the current round (same as toolStart).
        // The backend is about to start a new LLM round via pipeLlmCall.
        sessionTextBuffers
          .modify { m =>
            val text = m.getOrElse(sessionId, "")
            (m - sessionId, text)
          }
          .flatMap { text =>
            if text.nonEmpty then
              sessionThinkingBuffers
                .modify { m =>
                  val thinking = m.getOrElse(sessionId, "")
                  (m - sessionId, thinking)
                }
                .flatMap { thinking =>
                  sharedResources.sessionStore.appendUiMessages(
                    sessionId,
                    List(
                      UiMessage
                        .Ai(text, None, None, Option.when(thinking.nonEmpty)(thinking), System.currentTimeMillis())
                    )
                  )
                }
            else
              sessionThinkingBuffers.update(_ - sessionId)
              IO.unit
          }

      case "done" =>
        val model = hc.downField("model").as[Option[String]].getOrElse(None)
        sessionTurnStarts
          .modify { m =>
            val start = m.getOrElse(sessionId, 0L)
            (m - sessionId, start)
          }
          .flatMap { startTime =>
            val durationMs = if startTime > 0 then Some(System.currentTimeMillis() - startTime) else None
            sessionTextBuffers
                .modify { m =>
                  val text = m.getOrElse(sessionId, "")
                  (m - sessionId, text)
                }
                .flatMap { text =>
                  sessionThinkingBuffers
                    .modify { m =>
                      val thinking = m.getOrElse(sessionId, "")
                      (m - sessionId, thinking)
                    }
                    .flatMap { thinking =>
                      val thinkingOpt = Option.when(thinking.nonEmpty)(thinking)
                      if text.nonEmpty || thinkingOpt.isDefined then
                        sharedResources.sessionStore.appendUiMessages(
                          sessionId,
                          List(UiMessage.Ai(text, durationMs, model, thinkingOpt, System.currentTimeMillis()))
                        )
                      else if durationMs.isDefined then
                        // No text to flush (already flushed at roundComplete/toolStart),
                        // but we have a duration — backfill onto the last saved Ai message.
                        sharedResources.sessionStore.updateLastAiMeta(
                          sessionId,
                          durationMs,
                          model,
                          System.currentTimeMillis()
                        )
                      else IO.unit
                      end if
                    }
                }
          }

      case "toolEnd" =>
        val label = hc.downField("label").as[String].getOrElse("")
        val summary = hc.downField("summary").as[String].getOrElse("")
        val content = hc.downField("content").as[String].getOrElse("")
        val isError = hc.downField("isError").as[Boolean].getOrElse(false)
        val input = hc.downField("input").as[io.circe.Json].getOrElse(io.circe.Json.Null).noSpaces
        sharedResources.sessionStore.appendUiMessages(
          sessionId,
          List(UiMessage.Tool(label, summary, content, isError, input))
        )

      case "agentEnd" =>
        val agentId = hc.downField("agentId").as[String].getOrElse("")
        // Sub-agent text is streamed as agentTextDelta — we need to capture it.
        // For now, agentEnd without accumulated text is a no-op.
        // Agent text is typically short and embedded in the main AI bubble on the frontend.
        // We'll record a minimal agent entry for history if needed.
        IO.unit

      // ── Flow agent session persistence ───────────────────────────────
      // Flow agent events carry an injected nodeSessionId.
      // Accumulate/flush them into that session's .ui.json
      // so the agent popup shows full history on reopen.
      case "agentTextDelta" =>
        val nodeSessionId =
          hc.downField("nodeSessionId").as[String].toOption.filter(_.nonEmpty).map(normalizeNodeSessionId)
        val delta = hc.downField("delta").as[String].getOrElse("")
        nodeSessionId match
          case Some(nsid) if delta.nonEmpty =>
            // Record turn start for this flow agent so agentDone can compute a
            // duration for the ✻ duration badge on its final AI message.
            sessionTurnStarts
              .update(m => if m.contains(nsid) then m else m.updated(nsid, System.currentTimeMillis())) *>
              sessionTextBuffers.update(m => m.updatedWith(nsid)(_.map(_ + delta).orElse(Some(delta))))
          case _ => IO.unit

      case "agentThinking" =>
        // Mark turn start on the first thinking token too (some turns emit
        // thinking before any text). Also accumulate the delta into the
        // sub-agent's own sessionThinkingBuffers so agentToolEnd/agentDone
        // can persist thinking into ui.json.
        hc.downField("nodeSessionId").as[String].toOption.filter(_.nonEmpty).map(normalizeNodeSessionId) match
          case Some(nsid) =>
            val delta = hc.downField("delta").as[String].getOrElse("")
            val recordThinking =
              if delta.nonEmpty then
                sessionThinkingBuffers.update(m => m.updatedWith(nsid)(_.map(_ + delta).orElse(Some(delta))))
              else IO.unit
            recordThinking *> sessionTurnStarts
              .update(m => if m.contains(nsid) then m else m.updated(nsid, System.currentTimeMillis()))
          case None => IO.unit

      case "agentToolEnd" =>
        val nodeSessionId =
          hc.downField("nodeSessionId").as[String].toOption.filter(_.nonEmpty).map(normalizeNodeSessionId)
        val label = hc.downField("label").as[String].getOrElse("")
        nodeSessionId match
          case Some(nsid) if label.nonEmpty =>
            val summary = hc.downField("summary").as[String].getOrElse("")
            val content = hc.downField("content").as[String].getOrElse("")
            val isError = hc.downField("isError").as[Boolean].getOrElse(false)
            val input = hc.downField("input").as[io.circe.Json].getOrElse(io.circe.Json.Null).noSpaces
            // Flush any accumulated text + thinking before recording the tool, so the AI
            // message bubble (with thinking) appears above the tool card in history.
            sessionTextBuffers
              .modify(m => (m - nsid, m.getOrElse(nsid, "")))
              .flatMap { text =>
                sessionThinkingBuffers
                  .modify(m => (m - nsid, m.getOrElse(nsid, "")))
                  .flatMap { thinking =>
                    val flushMsg =
                      if text.nonEmpty then
                        sharedResources.sessionStore.appendUiMessages(
                          nsid,
                          List(UiMessage.Ai(text, None, None, Option.when(thinking.nonEmpty)(thinking), System.currentTimeMillis()))
                        )
                      else IO.unit
                    flushMsg *> sharedResources.sessionStore.appendUiMessages(
                      nsid,
                      List(UiMessage.Tool(label, summary, content, isError, input))
                    )
                  }
              }
          case _ => IO.unit
        end match

      case "agentDone" =>
        val nodeSessionId =
          hc.downField("nodeSessionId").as[String].toOption.filter(_.nonEmpty).map(normalizeNodeSessionId)
        nodeSessionId match
          case Some(nsid) =>
            // Flush any remaining accumulated text + thinking as a final AI bubble, with
            // duration (from the turn start) and model so the popup renders the
            // ✻ duration badge on the agent's last reply.
            val model = hc.downField("model").as[Option[String]].getOrElse(None)
            sessionTurnStarts
              .modify(m => (m - nsid, m.getOrElse(nsid, 0L)))
              .flatMap { startTime =>
                val durationMs = if startTime > 0 then Some(System.currentTimeMillis() - startTime) else None
                sessionTextBuffers
                  .modify(m => (m - nsid, m.getOrElse(nsid, "")))
                  .flatMap { text =>
                    sessionThinkingBuffers
                      .modify(m => (m - nsid, m.getOrElse(nsid, "")))
                      .flatMap { thinking =>
                        val thinkingOpt = Option.when(thinking.nonEmpty)(thinking)
                        if text.nonEmpty || thinkingOpt.isDefined then
                          sharedResources.sessionStore.appendUiMessages(
                            nsid,
                            List(UiMessage.Ai(text, durationMs, model, thinkingOpt, System.currentTimeMillis()))
                          )
                        else
                          // No text (flushed earlier by agentToolEnd) — backfill
                          // model + timestamp onto the last saved AI message. We no
                          // longer gate on durationMs.isDefined: when sessionTurnStarts
                          // was never set (startTime=0 → durationMs=None), team agent
                          // AI messages still need model and timestamp so the popup
                          // renders the badge (model name + time). durationMs stays
                          // None when unknown; the frontend falls back to a simpler
                          // badge (model + timestamp, no thinking phrase).
                          sharedResources.sessionStore.updateLastAiMeta(
                            nsid,
                            durationMs,
                            model,
                            System.currentTimeMillis()
                          )
                      }
                  }
              }
          case _ => IO.unit
        end match

      case "askUser" =>
        val items = hc.downField("items").as[List[io.circe.Json]].getOrElse(Nil)
        sharedResources.sessionStore.appendUiMessages(sessionId, List(UiMessage.AskUser(items)))

      case "askPermission" =>
        val toolName = hc.downField("toolName").as[String].getOrElse("")
        val summary = hc.downField("summary").as[String].getOrElse("")
        val permInput = hc.downField("input").as[io.circe.Json].getOrElse(io.circe.Json.Null).noSpaces
        if toolName.nonEmpty then
          sharedResources.sessionStore.appendUiMessages(
            sessionId,
            List(UiMessage.AskPermission(toolName, summary, permInput))
          )
        else IO.unit

      case "user" =>
        // Injected user events (from emitInjectedUserEvent) — persist so the
        // blue injection bubble survives page refresh. Regular user messages
        // are persisted separately in the WS message handler (not here).
        //
        // CRITICAL: the record target must be the event's own nodeSessionId
        // (normalized), NOT the root WS sessionId. Sub-agent injected events
        // (Mail delivery, Delegate/SubTask prompts) flow through the root
        // wsSend — recording them to sessionId would accumulate every
        // cross-agent injection into Nebula's ui.json. Matching the
        // agentTextDelta / agentToolEnd cases above.
        val text = hc.downField("text").as[String].getOrElse("")
        val injected = hc.downField("injected").as[Boolean].getOrElse(false)
        val source = hc.downField("source").as[Option[String]].getOrElse(None)
        val evtType = hc.downField("eventType").as[Option[String]].getOrElse(None)
        val sender = hc.downField("sender").as[Option[String]].getOrElse(None)
        val senderTeam = hc.downField("senderTeam").as[Option[String]].getOrElse(None)
        val delivery = hc.downField("delivery").as[Option[String]].getOrElse(None)
        val targetSession = hc
          .downField("nodeSessionId")
          .as[String]
          .toOption
          .filter(_.nonEmpty)
          .map(normalizeNodeSessionId)
          .getOrElse(sessionId)
        if text.nonEmpty && injected then
          sharedResources.sessionStore.appendUiMessages(
            targetSession,
            List(
              UiMessage.User(
                text,
                Nil,
                injected = true,
                timestamp = System.currentTimeMillis(),
                source = source,
                eventType = evtType,
                sender = sender,
                senderTeam = senderTeam,
                delivery = delivery
              )
            )
          )
        else IO.unit
        end if

      case "system" =>
        val content = hc.downField("content").as[String].getOrElse("")
        if content.nonEmpty then
          sharedResources.sessionStore.appendUiMessages(sessionId, List(UiMessage.System(content)))
        else IO.unit

      case "compactStart" =>
        val mode = hc.downField("mode").as[String].getOrElse("full")
        sharedResources.sessionStore.appendUiMessages(
          sessionId,
          List(UiMessage.System(s"Compacting context ($mode)...", Some("chat.compacting")))
        )

      case "compactComplete" =>
        val before = hc.downField("before").as[Int].getOrElse(0)
        val after = hc.downField("after").as[Int].getOrElse(0)
        val reportPath = hc.downField("reportPath").as[String].toOption
        val detail = reportPath.map(p => s" (report: ${p.split('/').last})").getOrElse("")
        sharedResources.sessionStore.appendUiMessages(
          sessionId,
          List(
            UiMessage.System(
              s"Context compacted: $before → $after messages$detail",
              Some("chat.compacted"),
              Some(io.circe.Json.obj("before" -> before.asJson, "after" -> after.asJson, "detail" -> detail.asJson))
            )
          )
        )

      case "compactFailed" =>
        val attempt = hc.downField("attempt").as[Int].getOrElse(0)
        val maxAttempts = hc.downField("maxAttempts").as[Int].getOrElse(0)
        sharedResources.sessionStore.appendUiMessages(
          sessionId,
          List(
            UiMessage.System(
              s"Context compaction failed (attempt $attempt/$maxAttempts)",
              Some("chat.compactFailed"),
              Some(io.circe.Json.obj("attempt" -> attempt.asJson, "maxAttempts" -> maxAttempts.asJson))
            )
          )
        )

      case _ => IO.unit

    record.handleErrorWith(e =>
      logger.warn(s"Failed to record UI message for session $sessionId: ${e.getMessage}")
    ) *> underlying(json).handleErrorWith(e =>
      logger.warn(s"Failed to broadcast message for session $sessionId: ${e.getMessage}")
    )

  // ============================================================
  // /ask — isolated LLM Q&A (does not affect agent context)
  // ============================================================

  private def executeAsk(
    sessionId: String,
    question: String,
    wsSend: io.circe.Json => IO[Unit]
  ): IO[Unit] =
    // Route /ask to the agent actor — it handles inline via pipeLlmCall with askMode
    ensureAgent(sessionId)(ref => ref ! AgentCommand.AskQuestion(question, sessionId))

  private def executeSkill(
    skillName: String,
    input: String,
    sessionId: String,
    wsSend: io.circe.Json => IO[Unit]
  ): IO[Unit] =
    // Check if it's a flow first — if so, instruct the agent to trigger it
    // via FlowTrigger (agent-mediated: the agent can refine the prompt).
    EntityLoader.loadFlow(skillName).flatMap {
      case Some(_) =>
        val safeInput = input.replace("\"", "\\\"").replace("\n", " ")
        ensureAgent(sessionId) { ref =>
          ref ! AgentCommand.SkillActivate(
            skillName,
            input,
            sessionId,
            s"""Trigger the "$skillName" flow:
               |FlowTrigger(flow="$skillName", prompt="$safeInput")
               |Wait for the flow's result and report it when it arrives.""".stripMargin,
            ""
          )
        }
      case None =>
        // Not a flow — try skill file
        SkillService.listSkills().flatMap { skills =>
          skills.find(_.name == skillName) match
            case Some(skillInfo) =>
              SkillService.loadSkill(skillInfo.filePath).flatMap {
                case Some(content) =>
                  ensureAgent(sessionId) { ref =>
                    ref ! AgentCommand.SkillActivate(
                      skillName,
                      input,
                      sessionId,
                      content.content,
                      content.baseDir
                    )
                  }
                case None =>
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "skillError".asJson,
                      "sessionId" -> sessionId.asJson,
                      "message" -> s"Skill '$skillName' content not found".asJson
                    )
                  )
              }
            case None =>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "skillError".asJson,
                  "sessionId" -> sessionId.asJson,
                  "message" -> s"Skill '$skillName' not found".asJson
                )
              )
        }
    }

  // ============================================================
  // Callback helpers
  // ============================================================

  /** Extract token from query param (localStorage), Authorization header, or cookie. */
  private def extractToken(req: org.http4s.Request[IO]): String =
    val fromParam = req.params.get("token").getOrElse("")
    if fromParam.nonEmpty then fromParam
    else
      val fromHeader = req.headers
        .get[org.http4s.headers.Authorization]
        .collectFirst { case org.http4s.headers.Authorization(org.http4s.Credentials.Token(_, t)) =>
          t
        }
        .getOrElse("")
      if fromHeader.nonEmpty then fromHeader
      else req.cookies.find(_.name == "nebflow_token").map(_.content).getOrElse("")

  /**
   * POST /api/callbacks/inject
   *
   * Body: { "agent": "Nebula", "session?": "abc123", "message": "text" }
   *
   * - agent is required
   * - session is optional; if missing, create a new session for the agent
   * - message is required
   */
  private def handleInject(req: org.http4s.Request[IO]): IO[org.http4s.Response[IO]] =
    req.bodyText.compile.string
      .flatMap { text =>
        parse(text).toOption match
          case None => BadRequest("Invalid JSON body")
          case Some(json) =>
            val cursor = json.hcursor
            val agentName = cursor.downField("agent").as[String].getOrElse("")
            val sessionIdOpt = cursor.downField("session").as[Option[String]].getOrElse(None)
            val message = cursor.downField("message").as[String].getOrElse("")
            val source = cursor.downField("source").as[String].getOrElse("callback")
            val metadata = cursor.downField("metadata").as[JsonObject].getOrElse(JsonObject.empty)
            val correlationId = cursor.downField("correlationId").as[Option[String]].getOrElse(None)

            if agentName.isEmpty then BadRequest(Json.obj("error" -> "Missing 'agent' field".asJson))
            else if message.isEmpty then BadRequest(Json.obj("error" -> "Missing 'message' field".asJson))
            else
              resolveSession(agentName, sessionIdOpt).flatMap { sessionId =>
                val event = AgentCommand.ExternalEvent(source, "inject", message, metadata, correlationId)
                handleBridgeAgentCommand(sessionId, event) *>
                  Ok(
                    Json.obj(
                      "status" -> "ok".asJson,
                      "sessionId" -> sessionId.asJson,
                      "agent" -> agentName.asJson
                    )
                  )
              }
      }
      .handleErrorWith {
        case e: RuntimeException => NotFound(Json.obj("error" -> e.getMessage.asJson))
        case e => InternalServerError(Json.obj("error" -> e.getMessage.asJson))
      }

  /** Resolve a session: use provided ID, or create a new one for the agent. */
  private def resolveSession(agentName: String, sessionIdOpt: Option[String]): IO[String] =
    sessionIdOpt match
      case Some(sid) =>
        sessionStore.getSessionMeta(sid).flatMap {
          case Some(_) => IO.pure(sid)
          case None => IO.raiseError(new RuntimeException(s"Session not found: $sid"))
        }
      case None =>
        sessionService
          .createSession(
            name = s"$agentName-callback",
            agentName = Some(agentName)
          )
          .map(_.id)

end WebSocketRoutes

object WebSocketRoutes:

  /**
    * 第六件 QC (2026-08-30): which input sources may probe the AskUser
    * chat-input passthrough. ONLY the WS input-box family does — the headless
    * REST turn entry ("rest-turn", P0 benchmark) must stay deterministic and
    * must NEVER silently answer a pending card. Pure + testable on purpose:
    * the gate is the whole behavioral delta of the REST fix.
    */
  def probesPassthrough(source: String): Boolean = source != "rest-turn"

  /**
    * F1 batch delete — pure, testable core for the `deletePaths` WS case.
    * Per-path guards are IDENTICAL to the single `deletePath` case:
    * resolve under root, canonical-path containment check, root itself
    * protected. Each path is attempted independently; failures (guard
    * rejection OR io error) land in `failed` without aborting the batch.
    * Returns (deletedPaths, failedPairs).
    */
  def deletePathsSafely(
      paths: List[String],
      root: os.Path
  ): IO[(List[String], List[(String, String)])] =
    paths.foldLeftM((List.empty[String], List.empty[(String, String)])) { (acc, p) =>
      resolveGuardedForDelete(p, root) match
        case Left(err) => IO.pure((acc._1, acc._2 :+ (p -> err)))
        case Right(basePath) =>
          // remove.all: batch delete from the file explorer explicitly covers
          // non-empty directories (F1 acceptance), unlike the single-file
          // deletePath case.
          IO.blocking { if os.exists(basePath) then os.remove.all(basePath) }
            .attempt
            .map {
              case Right(_)   => (acc._1 :+ p, acc._2)
              case Left(e)    => (acc._1, acc._2 :+ (p -> Option(e.getMessage).getOrElse(e.toString)))
            }
    }

  /** Resolve + guard one delete candidate (mirror of deletePath's checks). */
  private[gateway] def resolveGuardedForDelete(path: String, root: os.Path): Either[String, os.Path] =
    try
      val basePath = PathUtil.resolvePath(path, root)
      val canonicalBase = basePath.toIO.getCanonicalPath
      val canonicalRoot = root.toIO.getCanonicalPath
      if !canonicalBase.startsWith(canonicalRoot) then Left("path outside project root")
      else if canonicalBase == canonicalRoot then Left("cannot delete project root")
      else Right(basePath)
    catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.toString))

  /**
   * Resolve + guard + perform one move (mirror of movePath's checks).
   * Returns Right(relative new path) on success; Left(error message) on any
   * guard failure or move failure. Never overwrites an existing destination.
   */
  private[gateway] def movePathSafely(path: String, targetDir: String, root: os.Path): Either[String, String] =
    try
      val basePath = PathUtil.resolvePath(path, root)
      // Empty targetDir = move to the project root itself (resolvePath("")
      // semantics are os-lib-version-sensitive — be explicit).
      val targetBase = if targetDir.isEmpty then root else PathUtil.resolvePath(targetDir, root)
      val canonicalBase = basePath.toIO.getCanonicalPath
      val canonicalTarget = targetBase.toIO.getCanonicalPath
      val canonicalRoot = root.toIO.getCanonicalPath
      // Source and target must both stay inside the project root.
      if !canonicalBase.startsWith(canonicalRoot) then Left("path outside project root")
      else if !canonicalTarget.startsWith(canonicalRoot) then Left("target directory outside project root")
      // Cannot move the project root itself.
      else if canonicalBase == canonicalRoot then Left("cannot move project root")
      // Source must exist; target must be an existing directory.
      else if !os.exists(basePath) then Left("source path not found")
      else if !(os.exists(targetBase) && os.isDir(targetBase)) then Left("target directory not found")
      // Prevent cycles: target must not be the source itself or inside it.
      else if canonicalTarget == canonicalBase || canonicalTarget.startsWith(canonicalBase + java.io.File.separator)
      then Left("cannot move path into itself")
      else
        val newBase = targetBase / basePath.last
        // Never overwrite an existing destination (os.move has no overwrite).
        if os.exists(newBase) then Left("destination already exists")
        else
          os.move(basePath, newBase)
          Right(newBase.relativeTo(root).toString.replace('\\', '/'))
    catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.toString))

  /**
    * P1: true when the esbuild production bundle is packed on the classpath
    * (sbt -Dnebflow.webdist=1 assembly mounts build/ as a resource dir).
    * Lazy — the classpath is fixed for the JVM's lifetime, so this resolves
    * once. Single switch point for the static tree: only the "/" entry
    * chooses between dist and dev sources; all other static routes keep
    * serving the web/ source tree (vendor passthrough is byte-identical in
    * both trees, and the C1 source-mode contract requires web/ paths to stay
    * green on prod instances too).
    */
  lazy val hasBundledDist: Boolean =
    getClass.getClassLoader.getResource("web-dist/index.html") != null

  /**
    * L1 rebrand: the frontend brand contract. window.__BRAND__ is the only
    * brand source web/ may read; fields are append-only across rebrand
    * batches (initial contract: productName, lowerName, domain; L3 batch 3
    * appended homeDirName for the frontend's own legacy-path messaging;
    * 2026-09-01 login-chain fix appended profileUrl — the frontend's ONLY
    * URL input, consumed by activityBar.js to build the profile link).
    * `domain` carries the debug value (neblink.space) or the publish value
    * (nebflow.space via env override) — display-only, never consumed to
    * build a URL.
    *
    * circe handles JSON string escaping; the serialized blob additionally
    * escapes the forward slash of "</" because it is inlined inside a
    * script element (script-tag breakout hardening; JSON permits `\/`).
    */
  private[gateway] def brandScriptTag: String =
    s"""<script>window.__BRAND__=${brandScriptJson(
      Branding.productName,
      Branding.lowerName,
      Branding.domain,
      Branding.homeDirName,
      Branding.profileUrl,
    )};</script>"""
  end brandScriptTag

  /** Serialize the contract JSON (values injected for testability of the
    * escaping hardening). */
  private[gateway] def brandScriptJson(
    productName: String,
    lowerName: String,
    domain: String,
    homeDirName: String,
    profileUrl: String
  ): String =
    Json
      .obj(
        "productName" -> Json.fromString(productName),
        "lowerName"   -> Json.fromString(lowerName),
        "domain"      -> Json.fromString(domain),
        "homeDirName" -> Json.fromString(homeDirName),
        "profileUrl"  -> Json.fromString(profileUrl),
      )
      .noSpaces
      .replace("</", "<\\/")

  /**
    * Insert `snippet` directly before the LAST closing head tag — the one
    * structural anchor every index variant (dev source, esbuild dist) is
    * guaranteed to carry, emitted lowercase. None when the tag is absent:
    * callers serve the original bytes rather than guessing a fallback
    * position (a broken template should be visible, not papered over).
    */
  private[gateway] def injectBeforeHeadClose(html: String, snippet: String): Option[String] =
    val idx = html.lastIndexOf("</head>")
    if idx < 0 then None
    else Some(html.substring(0, idx) + snippet + html.substring(idx))
  end injectBeforeHeadClose

  /**
    * L1 rebrand: serve the index entry with the brand script injected.
    * Blocking classpath read of a tiny resource (a few KB) per request —
    * the same order of cost as the StaticFile.fromResource lookup it
    * replaces. Response contract: no-cache, no validators (the body is
    * content-generated, not a static file), text/html in UTF-8. The gzip
    * middleware wraps this route from the Router "/" mount, so it sees the
    * final injected bytes — compression order is correct by construction.
    *
    * The entity is written as a raw byte stream with an explicit
    * Content-Length — deliberately NOT Ok(String)/withEntity(String):
    * this file imports org.http4s.circe.CirceEntityCodec.* for the JSON
    * endpoints, and that import's String entity encoder (lexical scope)
    * wins over http4s' built-in one (implicit scope), silently encoding
    * the whole HTML as a JSON string literal (body starts with '"',
    * quotes escaped throughout — the page never boots). Raw bytes bypass
    * entity-encoder resolution entirely; pinned at the HTTP response
    * layer by IndexWithBrandServeSpec.
    */
  def indexWithBrand(indexResource: String): IO[Response[IO]] =
    IO.blocking(readClasspathResourceUtf8(indexResource)).flatMap {
      case None => NotFound()
      case Some(html) =>
        val served = injectBeforeHeadClose(html, brandScriptTag).getOrElse(html)
        val bytes = served.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        IO.pure(
          Response[IO](Status.Ok)
            .withBodyStream(Stream.emits(bytes))
            .withContentType(`Content-Type`(MediaType.text.html, Charset.`UTF-8`))
            .putHeaders(
              "Content-Length" -> bytes.length.toString,
              "Cache-Control"  -> "no-cache",
            )
        )
    }
  end indexWithBrand

  private def readClasspathResourceUtf8(name: String): Option[String] =
    Option(getClass.getClassLoader.getResourceAsStream(name)).map { in =>
      val source = Source.fromInputStream(in, "UTF-8")
      try source.mkString
      finally in.close()
    }

  /**
    * P1: serve the bundled asset tree (any depth under the /assets prefix —
    * hashed entry and lazy chunks produced by build-web.mjs). Guard pattern
    * copied from jsRoutes: trailing-slash (directory request) is rejected,
    * path traversal (`..` and backslash) is rejected, and the lookup is a
    * plain classpath resource join. Serves ONLY the web-dist tree — the
    * hashed names are a dist-build artifact and never exist in dev sources.
    * In dev every /assets request 404s; harmless, the dev index never
    * references the /assets prefix.
    *
    * Standalone (zero class deps) so it is directly unit-testable; composed
    * ahead of the instance routes like jsRoutes.
    */
  def assetsRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> _ if req.uri.path.renderString.startsWith("/assets/") =>
      if req.uri.path.endsWithSlash then NotFound()
      else
        val relParts = req.uri.path.segments.map(_.encoded).toList.drop(1) // drop "assets"
        if relParts.isEmpty || relParts.last.isEmpty then NotFound()
        else if relParts.exists(s => s == ".." || s.contains("\\")) then NotFound()
        else
          val path = relParts.mkString("/")
          StaticFile
            .fromResource(s"web-dist/assets/$path", Some(req))
            .map(_.putHeaders("Cache-Control" -> "no-cache"))
            .getOrElseF(NotFound())
        end if
      end if
  }

  /**
    * G1: serve user-uploaded attachments from
    * `~/.nebflow/uploads/<sid>/<file>`. Restored session history (ui.json)
    * records attachments as {name,type,path}; without a route serving the
    * uploads dir those images are unrenderable after a restart.
    *
    * Authenticated — unlike the voice-models route, uploads are user
    * screenshots (sensitive). Cookie `nebflow_token` first + `?token=` query
    * fallback (same dual-channel pattern as the /ws route): a malicious page
    * cross-site <img>-probing the localhost gateway gets no SameSite cookie
    * sent, while the same-origin frontend attaches it automatically. The
    * query fallback covers cookie-less contexts (and a stale cookie, same
    * rationale as /ws).
    *
    * Lives in the companion (composed ahead of the instance routes) because
    * it touches none of the class dependencies — testable with just a
    * gateway token.
    */
  def uploadsRoutes(token: String): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> _ if req.uri.path.renderString.startsWith("/uploads/") =>
      val cookieToken = req.cookies.find(_.name == "nebflow_token").map(_.content).getOrElse("")
      val paramToken = req.params.get("token").getOrElse("")
      if !(Auth.validateToken(cookieToken, token) || Auth.validateToken(paramToken, token)) then
        Forbidden("Invalid token")
      else
        val segs = req.uri.path.segments.map(_.encoded).toList
        // Path shape is exactly <sid>/<filename> — two segments after "uploads".
        if segs.sizeIs != 3 then NotFound()
        else
          val relParts = segs.drop(1) // drop "uploads"
          // Block path traversal (same guard style as voice-models)
          if relParts.exists(s => s == ".." || s.contains("\\")) then NotFound()
          else
            val uploadsBase = PathUtil.dataRoot / "uploads"
            val filePath = uploadsBase / os.RelPath(relParts.mkString("/"))
            // Final defense: the resolved path must stay under the base
            if filePath.startsWith(uploadsBase) && os.exists(filePath) && os.isFile(filePath) then
              StaticFile.fromPath(fs2.io.file.Path(filePath.toString), Some(req)).getOrElseF(NotFound())
            else NotFound()
        end if
      end if
  }

  /** Serves everything under web/js at ANY depth. The frontend's ES modules
    * live at /js/<file>.js, /js/locales/<lang>.js and /js/viewers/<name>.js,
    * and any future subdirectory — this closes the "add a js subdirectory →
    * every dynamic import 404s" outage class for good. The per-path routes
    * it replaces (in-class single-segment /js, the /js/locales case, and the
    * viewers-only route from 4281f720) each fixed one symptom after the
    * fact: http4s DSL matches single path segments, so every new directory
    * needed its own hand-written case.
    *
    * Manual segment parsing (same pattern and guard style as the monaco
    * route): ".." and backslash segments are rejected; everything else is
    * joined and looked up as a classpath resource. no-cache at EVERY depth —
    * rebuilt classpath resources must be picked up by the browser during
    * development (this also fixes locales, which used to ship without it).
    *
    * Standalone (zero class deps) so it is directly unit-testable
    * (JsStaticRoutesSpec); composed ahead of the instance routes like
    * uploadsRoutes.
    */
  def jsRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> _ if req.uri.path.renderString.startsWith("/js/") =>
      // Trailing slash = directory request (e.g. /js/viewers/) — this route
      // serves files only; without the guard StaticFile happily serves the
      // classpath directory entry itself (200 with junk).
      if req.uri.path.endsWithSlash then NotFound()
      else
        val relParts = req.uri.path.segments.map(_.encoded).toList.drop(1) // drop "js"
        if relParts.isEmpty || relParts.last.isEmpty then NotFound()
        else if relParts.exists(s => s == ".." || s.contains("\\")) then NotFound()
        else
          val path = relParts.mkString("/")
          StaticFile
            .fromResource(s"web/js/$path", Some(req))
            .map(_.putHeaders("Cache-Control" -> "no-cache"))
            .getOrElseF(NotFound())
        end if
      end if
  }

  /** Which AgentRegistry entries getActiveAgents should report as running.
    *
    * Task-lifecycle kinds (Delegate/Ephemeral/Flow/SubTask) are reported
    * as-is: their presence in the registry means the task is in flight
    * (they unregister on completion).
    *
    * Team agents are LONG-LIVED: activateAgent registers them on the first
    * Mail and they stay in the registry while idle, waiting for the next
    * Mail. "In the registry" says nothing about running — gate them on the
    * same busy signal /api/teams/mounted uses (markBusy/markIdle around each
    * team turn), otherwise every browser refresh reports idle team agents
    * back as running ghosts in the bg-agent dropdown.
    *
    * Standalone (zero class deps) so it is directly unit-testable
    * (ActiveAgentsFilterSpec).
    */
  def filterActiveAgents(registry: Map[String, AgentRecord]): IO[List[AgentRecord]] =
    val taskKinds = Set(AgentKind.Delegate, AgentKind.Ephemeral, AgentKind.Flow, AgentKind.SubTask)
    val inFlight = registry.values.toList.filter(r => taskKinds.contains(r.kind))
    val teamAgents = registry.values.toList.filter(_.kind == AgentKind.Team)
    teamAgents
      .traverseFilter(rec =>
        TeamSessionRegistry.isBusy(rec.sessionId).map(busy => if busy then Some(rec) else None)
      )
      .map(_ ++ inFlight)

  /**
    * One entry of the getActiveAgents ("activeAgents") restore reply.
    *
    * Contract: agentId == sessionId. The frontend keys its bg-agent map by
    * the agentId of BOTH this restore reply and live agentStart events —
    * a mismatch files two running rows for one session (the Teams panel
    * double-entry ghost). Live events carry ctx.self.path.name, so every
    * subagent spawn path must name its actor by the session id (Mail /
    * Delegate / SubTask / DAG alike). `task` mirrors the live agentStart's
    * taskDescription (= the session display name, e.g. "team/agent" for
    * mounted team sessions) so restored rows render with the same team
    * attribution as live ones.
    *
    * Standalone (zero class deps) so the contract is directly unit-testable
    * (ActiveAgentsEntrySpec).
    */
  def activeAgentEntryJson(rec: AgentRecord, meta: Option[SessionMeta], retryCount: Option[Int] = None): Json =
    Json.obj(
      "sessionId"      -> rec.sessionId.asJson,
      "agentId"        -> rec.sessionId.asJson,
      "agentName"      -> meta.flatMap(_.agentName).getOrElse(rec.sessionId).asJson,
      "rootSessionId"  -> rec.rootSessionId.asJson,
      "kind"           -> rec.kind.toString.asJson,
      "task"           -> meta.map(_.name).getOrElse("").asJson,
      // 2026-08-22 缺口 2：快照自带可刷新恢复三字段（向后兼容——旧字段不动，
      // 旧前端无感）。status=AgentStatus toString（Idle/Processing/.../Error）；
      // startedAt epoch ms（0=unknown）；retryCount 来自 taskStore（缺省 0——
      // Ephemeral 不落 taskStore）。不加 agentStatus 变更广播（改动面大，裁定
      // 不做——快照轮询由前端按需刷新）。
      "status"         -> rec.status.toString.asJson,
      "startedAt"      -> rec.startedAt.asJson,
      "retryCount"     -> retryCount.getOrElse(0).asJson
    )
