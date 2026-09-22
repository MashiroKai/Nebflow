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
import nebflow.core.project.{CancelSource as ChainCancelSource, ChainCancelEntry, ChainCancelReport, ProjectRuntime, ProjectRuntimeRegistry}
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
  sttService: Option[SttService] = None,
  /** C2-6: the `/api/nf-file` ticket store, created (TTL from
    * `nebflow.json`) and injected by GatewayMain. The default keeps every
    * existing construction site (specs, tooling) compiling. */
  nfTicketStore: NfTicketStore = NfTicketStore.unsafeDefault(),
  /** C1-5 injection seam: the credential-namespace policy the read and the
    * signing endpoints share. Memoized so the R2 inode scan is a one-off. */
  nfPathPolicy: WebSocketRoutes.NfPathPolicy = WebSocketRoutes.NfPathPolicy.memoized(),
  /** R-1b conn-guard：per-IP WS 看护（升级面准入 + 计数）。缺省 = 全放行
    * 实例（既有测试构造点零改动）；生产由 GatewayMain 注入实配。 */
  connGuard: ConnGuard = ConnGuard.disabled
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
      // ── ctxthresh 批（2026-09-15 方案 A，作者卡答「按方案A实施」）─────────────
      // 🔴 **本行是全仓唯一的阈值覆盖注入点**（口径③「仅 Nebula 窗口」的**结构性**
      // 保证，不是口号）：depth=0 的 WS 根会话 spawn 是全仓唯一的 root spawn 点，
      // 非 root spawn（NodeRunner / EphemeralAgentRunner / MemoryTrack / MailTool /
      // FlowTreeActor）一律不传该实参 ⇒ SessionContext.compactThresholdRatio = None
      // ⇒ 走 CompactThreshold 现值函数（口径②）。
      //
      // 优先级链：内存 Ref（本进程内已热更的权威值）＞ 盘上 SessionMeta（跨重启
      // 保留值，设计 §9-O4(a)）＞ 无覆盖。Ref 在启动时为空 ⇒ 重启后由 meta 恢复。
      thresholdOverrides <- sharedResources.sessionCompactThreshold.get
      compactThresholdRatio =
        thresholdOverrides.get(sessionId).orElse(metaOpt.flatMap(_.compactThresholdRatio))
      // ─────────────────────────────────────────────────────────────────────
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
      // 2026-09-13（permshield S1）权限档位 = **应用级持久值**：agent 参数取唯一有效
      // 档位（`SharedResources.effectiveSafetyMode` ⇒ `nebflow.json` 的
      // `safety.defaultMode`）。这里既不消费 `SessionMeta.safetyMode`（盘上遗留值，
      // 非权威），也没有"本会话覆盖"可保留——档位对**所有**会话一致（作者 09-13
      // 「落全局持久，重启后仍生效」）。
      effectiveMode <- sharedResources.effectiveSafetyMode
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
          safetyMode = nebflow.core.SafetyMode.toString(effectiveMode),
          gitBranch = metaOpt.flatMap(_.gitBranch),
          rootSessionId = sessionId,
          // Nebula 会话沙箱启用（2026-09-05 作者裁定 13:09）：写根=~/.nebflow
          // 数据根（root 特判在 AgentCore，按 SandboxPolicy.isNebulaRootSession
          // 取 PathUtil.dataRoot）。判定基准=WS 根会话 ∧ agent==Nebula——此处
          // 是 depth=0 全仓唯一 spawn 点，agentDef.name 是 agent 身份权威
          // （metaOpt.agentName 是可缺省的会话元数据）。其余 WS 根会话
          // （standalone 非 Nebula 聊天 / team Manager / flow 入口）保持
          // sandboxEnabled=false 现状零变化；沙箱 root 推导仍归 AgentCore。
          sandboxEnabled = agentDef.name == "Nebula",
          // ctxthresh 批：会话级压缩阈值比例覆盖（仅本 root spawn 注入，见上方注释）。
          compactThresholdRatio = compactThresholdRatio
        ),
        s"agent-$sessionId"
      )
      pr = effectiveProjectRoot.getOrElse("")
      safetyMode = nebflow.core.SafetyMode.toString(effectiveMode)
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
        // target (cards/questions appear in the Nebula window).
        //
        // 2026-09-13（permshield S1）：**没有任何按会话的权限桶可播种**——档位只有
        // 一个来源（应用级 `safety.defaultMode`，见 SharedResources）。此前每建立
        // 一次连接就用盘上 meta 值写桶的路径（2026-09-12 前）以及"内存覆盖"路径
        // （2026-09-12–09-13）都已随覆盖层删除。
        registerRootInteraction(sessionId, recordingWsSend) *>
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

  /** 刷新存活 (2026-09-03): re-send a root session's still-pending AskUser
    * cards to THIS connection. Called after the initial historyPage of a
    * session (re)subscribe — see the getHistory hook. Resolves the root
    * session id first (team/flow node sessions have no hub slots of their
    * own; their asks live under the root), then snapshots via the hub and
    * re-renders each card byte-identical to the first send plus
    * `replayed: true` (the frontend dedup key). Failure-tolerant: a hub
    * hiccup degrades to "no replay", the history restore chain still applies.
    */
  private def replayPendingAsks(sessionId: String, wsSend: io.circe.Json => IO[Unit]): IO[Unit] =
    sharedResources.interactionHubRef.get.flatMap {
      case None => IO.unit
      case Some(hub) =>
        resolveRootSessionId(sessionId).flatMap { rootSid =>
          hub
            .?[List[io.circe.Json]](reply =>
              nebflow.agent.InteractionHubCommand.ListPendingAsks(rootSid, reply)
            )
            .flatMap(_.traverse_(frame => wsSend(frame)))
            .handleErrorWith { e =>
              logger.warn(s"Pending-ask replay failed for session $sessionId: ${e.getMessage}")
            }
        }
    }

  /** 多 AskUser 并发批（#250 第③项，2026-09-13 作者裁定「6 项全补」）：
    * 全库 pending-AskUser 快照 → 本连接（`pendingAsksSnapshot{asks:[…]}`，单帧，
    * 与 `ListPendingAsks` 的重放帧逐字节同构，含 `replayed: true`）。
    *
    * 与 `replayPendingAsks` 的分工：那条**渲染卡片进聊天流**（按会话订阅触发，职责
    * 不变），本条只**重建待办条/badge 的本地镜像**——所以它是「一次性全局」，与
    * 当前活动会话无关。前端消费点 = `askPending.applyPendingAskSnapshot`。
    *
    * hub 未装配（早期 boot / 测试）⇒ 回空快照：这是确定性结论（无 hub ⇒ 无槽位），
    * 比「不回帧、前端镜像悬空」少一条静默路径。查询失败 ⇒ 回失败帧（不带 asks），
    * 前端据此保留本地镜像并给可见提示——把「同步失败」与「确实没有 pending」区分开。 */
  private def listAllPendingAsks(wsSend: io.circe.Json => IO[Unit]): IO[Unit] =
    sharedResources.interactionHubRef.get.flatMap {
      case None =>
        wsSend(
          io.circe.Json.obj(
            "type" -> "pendingAsksSnapshot".asJson,
            "asks" -> List.empty[io.circe.Json].asJson
          )
        )
      case Some(hub) =>
        hub
          .?[List[io.circe.Json]](reply => nebflow.agent.InteractionHubCommand.ListAllPendingAsks(reply))
          .flatMap(asks =>
            wsSend(
              io.circe.Json.obj(
                "type" -> "pendingAsksSnapshot".asJson,
                "asks" -> asks.asJson
              )
            )
          )
          .handleErrorWith { e =>
            logger.warn(s"Pending-ask global snapshot failed: ${e.getMessage}") *>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "pendingAsksSnapshot".asJson,
                  "failed" -> true.asJson,
                  "error" -> s"pending-ask snapshot failed: ${e.getMessage}".asJson
                )
              ).handleErrorWith(_ => IO.unit)
          }
    }

  /** 递进式放行链 (2026-08-30)：把确认卡上选定的升级档**落为全局持久档位**。
    *
    * permshield S1（2026-09-13，作者重裁「保留递进链路…落全局持久，跟盾牌走同一条
    * 路，重启后仍生效」）：递进链**保留**，但写入目标由"本会话内存覆盖"改为
    * `nebflow.json` 的 `safety.defaultMode` —— 与 WS 盾牌 / REST
    * `PUT /api/safety/mode` 共用同一个持久函数（`ConfigService.setSafetyDefaultMode`
    * 内 `writeLocked` 串行），因此升级立即对所有会话生效且**重启后仍生效**。
    *
    * 顺序契约：落盘（权威源）→ 广播 `configUpdated`。判定侧每次热读该键，故下一次
    * 工具判定即看到新档位；无需（也没有）向 agent 发状态通知。
    */
  private def applyPermissionUpgrade(sessionId: String, mode: nebflow.core.SafetyMode): IO[Unit] =
    val modeStr = nebflow.core.SafetyMode.toString(mode)
    for
      _ <- ConfigService.setSafetyDefaultMode(modeStr)
      _ <- wsHub.broadcast(io.circe.Json.obj("type" -> "configUpdated".asJson, "success" -> true.asJson))
      _ <- logger.info(
        s"Permission upgrade (session $sessionId): global safety mode → $modeStr (persisted to safety.defaultMode; applies to every session)"
      )
    yield ()

  /** 全局档位的**单一持久写入口**（permshield S1）：WS 盾牌 `setSafetyMode` /
   * 老客户端 `setBypass` / 确认卡递进升级三处共用，与 REST `PUT /api/safety/mode`
   * 落在同一个函数（`ConfigService.setSafetyDefaultMode`，`writeLocked` 串行）
   * ⇒ 一条持久路径，零第二介质。
   *
   * 顺序契约：**先落盘**（权威源），**再广播** `configUpdated`（各客户端同步）。
   * 落盘失败**上浮** —— 调用方回 error 帧，绝不出现"界面已切、盘上没写"（重启即
   * 回弹，用户可见的坏形态）。
   */
  private def persistGlobalSafetyMode(mode: String): IO[Unit] =
    ConfigService.setSafetyDefaultMode(mode) *>
      wsHub.broadcast(io.circe.Json.obj("type" -> "configUpdated".asJson, "success" -> true.asJson))

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
      // 2026-09-13（permshield S1）：此处原为"清该会话的内存覆盖条目"。覆盖层删除后
      // 档位是应用级的，删除会话**不得**（也不能）改动它——故本清理点整体移除。
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
          // 注意：`ref ! msg` 本身返回 IO[Unit]（offer 的描述）——直接使用，
          // 不能包 IO(...)（嵌套 IO[IO[Unit]]，内层 offer 永不执行——与 V1
          // 级联修复同类 bug，#38 停成员 stop 曾因此静默无效）。
          ref ! AgentCommand.Stop(s"session $sessionId deleted")
        case None => IO.unit
      _ <- TeamSessionRegistry.unregisterActor(sessionId, sharedResources)
      pairOpt <- TeamSessionRegistry.instanceAndAgentOfSession(sessionId)
      _ <- pairOpt match
        case Some((inst, agent)) => TeamSessionRegistry.unregisterAgent(inst, agent, sessionId)
        case None                => IO.unit
    yield ()

  /** V1 (2026-09-03): Delegate/SubTask/Ephemeral 子代理级联停止——实现抽在
    * SessionChildCascade（deleteSession/batchDelete 两条路径与单测共用同一实现），
    * 取舍理由见该对象 doc。 */
  private def stopChildDelegateActors(sessionId: String): IO[Unit] =
    SessionChildCascade.stopChildDelegateActors(sharedResources, sessionId).void

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
  /** ctxthresh 批：会话的**有效上下文窗口**（只读回显用）——会话级模型覆盖优先，
    * 否则预设派生的全局值。与 `doSpawnRootAgent` 内同一表达式同源。 */
  private def effectiveContextWindowOf(sessionId: String): IO[Int] =
    sharedResources.sessionModelOverrides.get.map(
      _.get(sessionId).map(_.contextWindow).getOrElse(sharedResources.contextWindow)
    )

  /** ctxthresh 批：会话的阈值覆盖现值——内存 Ref（本进程内已热更的权威值）→
    * 盘上 `SessionMeta.compactThresholdRatio`（跨重启保留值）→ None（无覆盖）。 */
  private def compactThresholdOverrideOf(sessionId: String): IO[Option[Double]] =
    sharedResources.sessionCompactThreshold.get.flatMap { m =>
      m.get(sessionId) match
        case some @ Some(_) => IO.pure(some)
        case None => sessionStore.getSessionMeta(sessionId).map(_.flatMap(_.compactThresholdRatio))
    }

  /** ctxthresh 批：**身份面判据**（作用域闸的第 ① 条，独立成函数便于判读）——
    * 会话的**有效 agent 名**必须是 `"Nebula"`。
    *
    * 口径来源 = 作者卡答「仅 root 会话」＋「其他的还是使用我们预设中规定的」，与既有
    * 「Nebula 会话集」判据逐字同款（`SessionStore.listSessionsByAgent` :604-613
    * 「Sessions without agentName match "Nebula"」= `agentName.getOrElse("Nebula")`；
    * 客户端同款 `s.agentName || 'Nebula'`，main.js:1553）；先例 = 会话沙箱根的
    * `SandboxPolicy.isNebulaRootSession` 用 `agentName == "Nebula"` 把 standalone 非
    * Nebula 聊天 / team Manager / flow 入口等**其余 root 会话**一并排除（双保险）。
    *
    * 🔴 **判据取「声明的名字」而非 spawn 期解析出的 `AgentDef.name`**：`resolveAgentDef`
    * 对库里不存在的名字会 `nebulaFallback` 回落成 Nebula（本文件 :98-102）⇒ 用解析结果
    * 判会让 `agentName="general"` 这类会话**误判成 Nebula**（复核位探针的第 1 例正是它）。
    * spawn 期身份另有 `agentDef.name == "Nebula"`（:216 `sandboxEnabled`），二者不冲突。
    *
    * 无 `SessionMeta` 的 id（`node-*` / `dag-*` / 已删会话等幽灵 id）⇒ **身份不可立
    * ⇒ 拒**（fail-closed；它们既不在会话索引里，也不该有阈值覆盖可写）。 */
  private def isNebulaIdentitySession(sessionId: String): IO[Boolean] =
    sessionStore.getSessionMeta(sessionId).map {
      case Some(meta) => meta.agentName.getOrElse("Nebula") == "Nebula"
      case None       => false
    }

  /** ctxthresh 批：**作用域闸**（口径①/③；静态泄漏判据见
    * `.nebflow/tools/20260915_ctxthresh_leak-check.sh`）——本功能**仅对 Nebula 的
    * root 会话开放**。判据 = 下列合取（全部为真才放行）：
    *
    *   ① **身份面**：`isNebulaIdentitySession`（上）——排除主窗口切过去的 standalone
    *      非 Nebula 会话 / 节点形态 id。
    *   ② **注册面**：已注册活体 agent 的会话，`AgentRecord.kind` 必须 == `Root`
    *      （`kind = Root` 的唯一置位点 = `doSpawnRootAgent`，本文件 :246）。节点 /
    *      链路 / 委托会话 kind ≠ Root ⇒ 一律拒。
    *   ③ **未注册 ⇒ fail-closed**：唯一例外 = **本次 WS 的根会话**（= store 的活跃会话，
    *      取法与 WS 连接面 :827 同源）。留这一格的实证理由：`switchSession`
    *      （`SessionStore.scala:657-683`）**只动 index / 消息、不 spawn root agent**，
    *      用户切到另一个 Nebula 会话后、发首条消息前它不在 registry 里——一律拒会
    *      误伤这条合法面（切会话后调阈值）。除活跃会话外的未注册 id 一律拒。
    *
    * 🔴 **2026-09-15 返工（复核位判词 fail）**：原实现未注册 ⇒ `case None => true`
    * **fail-open** ⇒ 未注册的 `agentName="general"` 会话 / 节点形态 id 全部被放行，
    * 真机读数（复核位独立实例 8098，`ev_scope_probe.txt`）= 四例全 ACCEPT 且
    * `_index.json` 落盘 `compactThresholdRatio=0.5`；且原实现只有 kind 闸，主窗口
    * 切到 general agent 会话时点环即写进该会话并持久化（违反作者逐字口径③）。
    * 现改为 fail-closed + Nebula 身份门（返工探针见
    * `.nebflow/tools/20260915_ctxthresh_scope-recheck.mjs`）。 */
  private def isRootScopeSession(sessionId: String): IO[Boolean] =
    if sessionId.isEmpty then IO.pure(false)
    else
      isNebulaIdentitySession(sessionId).flatMap { isNebula =>
        if !isNebula then IO.pure(false)
        else
          sharedResources.agentRegistry.get.flatMap { registry =>
            registry.get(sessionId) match
              case Some(rec) => IO.pure(rec.kind == AgentKind.Root)
              case None      => sessionStore.getActiveMeta.map(_.exists(_.id == sessionId))
          }
      }

  /** ctxthresh 批：阈值面板的**权威回显载荷**（面板打开 / 设值成功 / 恢复默认后
    * 共用同一数据源——前端不回算、不自造值，「生效绝对 token 回显」由此保证与
    * 引擎判定面同源）。 */
  private def compactThresholdInfo(sessionId: String): IO[Json] =
    for
      ratioOpt <- compactThresholdOverrideOf(sessionId)
      window <- effectiveContextWindowOf(sessionId)
    yield
      val effective = CompactThresholdOverride.effectiveThreshold(window, ratioOpt)
      val default = nebflow.core.compact.CompactThreshold.threshold(window)
      Json.obj(
        "type" -> Json.fromString("compactThresholdInfo"),
        "sessionId" -> Json.fromString(sessionId),
        "ratio" -> ratioOpt.fold(Json.Null)(Json.fromDoubleOrNull),
        "contextWindow" -> Json.fromInt(window),
        "effectiveThreshold" -> Json.fromInt(effective),
        "effectiveRatio" -> Json.fromDoubleOrNull(effective.toDouble / window),
        "defaultThreshold" -> Json.fromInt(default),
        "defaultRatio" -> Json.fromDoubleOrNull(default.toDouble / window),
        "minRatio" -> Json.fromDoubleOrNull(CompactThresholdOverride.minRatioFor(0.0)),
        "maxRatio" -> Json.fromDoubleOrNull(CompactThresholdOverride.MaxRatio)
      )

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
   * Hard-recovery P4 SessionKick (设计 §2.5, 2026-09-07): a user message or
   * interrupt arriving for a session whose turn is WEDGED must be able to
   * break in. Queueing alone never helps — the turn never ends, so queued
   * input is never injected (production: 11:09:39 message queued, still
   * waiting 22 minutes later). When the session is Processing with no
   * activity for > SessionKickIdleSec (default 150s — deliberately above the
   * 120s per-provider inactivity watchdog: only kick when that watchdog has
   * provably failed to surface its error), force-abort the session's in-flight
   * LLM request at the TRANSPORT level (the only primitive that unwedges a
   * parked body read). The turn then fails with RecoverableAbort and the
   * agent's normal machinery takes over: queued input batch-injects (缺陷⑥)
   * or a bounded whole-turn retry (VPN flap). Fire-and-forget — the user's
   * message was already routed/queued by the caller; the kick runs alongside.
   * Verification (设计 P2) is the watcher's next scans (TaskStuckWatcher L2/L3
   * escalate if the kick didn't take) plus a warn log here for diagnosis.
   */
  private def maybeSessionKick(sessionId: String, trigger: String): IO[Unit] =
    if sessionId.isEmpty || !nebflow.shared.Defaults.HardRecoveryEnabled then IO.unit
    else
      sharedResources.agentRegistry.get.flatMap { registry =>
        registry.get(sessionId) match
          case Some(rec) if isKickCandidate(rec, System.currentTimeMillis()) =>
            val idleMs = System.currentTimeMillis() - rec.lastActivityMs
            logger.info(
              s"SessionKick ($trigger): session $sessionId Processing with no agent-side activity for " +
                s"${idleMs / 1000}s — force-aborting in-flight LLM transport"
            ) *>
              nebflow.llm.LlmInterface
                .transportAbortFor(sessionId)
                .flatMap { n =>
                  logger.info(s"SessionKick ($trigger): aborted $n in-flight LLM request(s) of $sessionId")
                }
                .handleErrorWith(e =>
                  logger.warn(s"SessionKick ($trigger) transport abort failed for $sessionId: ${e.getMessage}")
                )
          case _ => IO.unit
      }

  /**
   * **口径如实（wd-fix 批 2026-09-12 实读订正）**：本函数**不调用**
   * `TaskStuckWatcher.assess` / `#classify` —— 它是同一组不等式的**第二份内联副本**，
   * 与 [TaskStuckWatcher] 头注旧文所称的「三处同源」**不符**：
   *   - **阈值不同**：本处 `Defaults.SessionKickIdleSec` = **150s**（设计 D-3），
   *     watcher 的 `Defaults.StuckThresholdMs` = **600s**；
   *   - **多一条护栏**：`toolInFlight`（本处独有，watcher 在 `classify` 里做根因分流）。
   * ⇒ 判据源实为 2 同源（watcher 扫描 + `AgentControlTool`）+ 1 副本（本处）。
   * 副本改走 `assess`/`classify` 属**行为变更**（阈值/护栏/分级语义全变），
   * **不在 wd-fix 批范围内**（建议另立条目）。
   *
   * 2026-09-10 卡死判据换轴：kick 判据与 lastActivityMs 一并收紧为 **agent 侧**
   * 信号，并加一条工具相位护栏（与 TaskStuckWatcher.assess 同轴）：
   *
   *   - lastActivityMs 现在只由 agent 侧事件写（BashTool 活动桥 / RemoteExecutor
   *     心跳改写 processActivityMs）——旧判据「idle > 150s 就 kick」在长前台
   *     命令期间读到的是**进程**活性，kick 语义（「LLM transport 被楔死」）错位。
   *   - 护栏：**有工具正在执行**（0 < 工具已持续时间 ≤ ToolPhaseStuckMs）时不 kick
   *     ——工具执行相位本就没有在飞 LLM 请求（上一轮流已结束），transport abort
   *     是 no-op；且这条护栏避免了「新半径」：长前台命令（>150s）不再因为 agent 侧
   *     戳停摆而被每次用户消息踢一脚。
   *   - 工具相位**自己**超时（> ToolPhaseStuckMs）时护栏解除：让 TaskStuckWatcher
   *     的换轴判据成为唯一接管者（此时 kick 命中 0 在飞请求，无副作用）。
   */
  private def isKickCandidate(rec: nebflow.agent.AgentRecord, now: Long): Boolean =
    val kickIdleMs = nebflow.shared.Defaults.SessionKickIdleSec * 1000L
    val agentIdleMs = if rec.lastActivityMs > 0 then now - rec.lastActivityMs else 0L
    val toolPhaseMs = if rec.currentToolStartedAt > 0 then now - rec.currentToolStartedAt else 0L
    val toolInFlight = toolPhaseMs > 0 && toolPhaseMs <= nebflow.shared.Defaults.ToolPhaseStuckMs
    rec.status == nebflow.agent.AgentStatus.Processing &&
      rec.lastActivityMs > 0 &&
      agentIdleMs > kickIdleMs &&
      !toolInFlight

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
   * (e.g. Interrupt) to a session's agent.
   */
  def handleBridgeAgentCommand(sessionId: String, command: AgentCommand): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else ensureAgent(sessionId)(ref => ref ! command)

  def routes: HttpRoutes[IO] =
    WebSocketRoutes.uploadsRoutes(token) <+> WebSocketRoutes.jsRoutes <+> WebSocketRoutes.assetsRoutes <+> WebSocketRoutes.nfFileRoutes(token, nfTicketStore, nfPathPolicy) <+> WebSocketRoutes.nfTicketRoutes(token, nfTicketStore, nfPathPolicy) <+> WebSocketRoutes.nfAuthcheckRoutes(token) <+> HttpRoutes.of[IO] {
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
        // R-1b conn-guard：升级受理即 per-IP 检查（拒 ⇒ 可见 429，不静默；
        // 环回恒放行）。入账延后到 wsb.build 前（下方 acquireWs——中途失败
        // 不留幽灵计数），回减挂流 finalizer（releaseWs 凭据配对）。
        // 括号包裹 = 保持原 for 缩进不动（大段受理体零改排）。
        val wsIp = ConnGuard.normalizeIp(req.remoteAddr)
        connGuard.checkWs(wsIp).flatMap {
          case Some(reason) =>
            logger.warn(
              s"conn-guard: WS upgrade rejected ip=$wsIp reason=$reason caps=${connGuard.config.wsPerIpCap}/${connGuard.config.wsTotalCap}"
            ) *>
              TooManyRequests(
                s"Connection guard: WebSocket limit reached ($reason); retry later or contact the operator"
              )
          case None => (
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
          // R-1b：入账在 build 前（此处之后仅剩 build 本身，失败即自然不
          // build ⇒ 无幽灵计数）；回减挂流 finalizer（连接关闭必走）。
          guardHandle <- connGuard.acquireWs(wsIp)
          guardedPipe = receivePipe.andThen(_.onFinalize(connGuard.releaseWs(guardHandle)))
          ws <- wsb.build(sendStream, guardedPipe)
        yield ws
          )
        }
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

    // GET /api/nf-file — local file serving for card iframes moved to the
    // companion as nfFileRoutes(token) (standalone, unit-testable, same
    // pattern as uploadsRoutes/jsRoutes); mounted ahead of this match above.
    // 2026-09-03 Canvas interactive-HTML fix: the whitelist (NfFileAllowedExt)
    // now also covers the text asset types (js/css/json) a multi-file HTML
    // deliverable references from the Canvas HTML viewer.
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
        Set("style.css", "app.js", "favicon-32.png", "favicon-16.png", "favicon.ico",
          "favicon-180.png", "favicon-192.png", "favicon-512.png")
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
      // 出口 overlay（设计 §13 #9）：列表里的逐会话 `safetyMode` = **有效档位**
      // （覆盖 ?? 全局），不再输出盘上遗留值——前端 `state.bypassSessions`
      // 由此只含"有效档位 = 全部放行"的会话（A-14）。
      sharedResources.overlaySessionList(sessions).flatMap { sessionsJson =>
        wsSend(
          io.circe.Json.obj(
            "type" -> "agentSessionList".asJson,
            "agentName" -> agentName.asJson,
            "sessions" -> sessionsJson,
            "folders" -> folders.asJson,
            "foldersWithRules" -> rulesFolderIds.asJson
          )
        )
      }
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

  /** '~' 前缀展开（仅前缀语义；非 ~ 开头原样返回）——wsBrowse 路径入参用。
    * 委托 PathUtil.expandTilde：统一支持 `~` / `~/` / `~\`（Windows 分隔符形态），
    * 并在 Windows 上做分隔符归一（os-lib 拒绝混合分隔符段）。 */
  private def expandTilde(path: String): String = PathUtil.expandTilde(path)

  /** 应用内工作区浏览器（Route C 兜底）的目录列表响应帧。
    * home 供前端把 home 前缀折叠为「主目录」面包屑；err 非 null → 前端在弹窗
    * 内联展示错误（路径非法/不可读）。 */
  private def wsBrowseEvent(
      evType: String,
      path: String,
      parent: Option[String],
      entries: List[String],
      err: Option[String]
  ): Json = {
    val fields = scala.collection.mutable.ListBuffer(
      "type" -> Json.fromString(evType),
      "path" -> Json.fromString(path),
      "home" -> Json.fromString(sys.props("user.home")),
      "entries" -> Json.arr(entries.map(Json.fromString)*)
    )
    parent.foreach(p => fields += "parent" -> Json.fromString(p))
    err.foreach(e => fields += "error" -> Json.fromString(e))
    Json.obj(fields.toList*)
  }

  private val MaxMessageSize = 10 * 1024 * 1024 // 10MB (base64 images can be large)

  /** The Canvas / file-viewer OPEN gate — the largest file the WS `pop.readFile`
    * leg will serve (2026-09-20 author order: 10MB → 100MB; the author could not
    * open a PDF over 10MB). It is the ONE ruler for the open path on the server
    * side: the frontend pre-check (`web/js/attachmentPreview.js` `MAX_TEXT_BYTES`)
    * mirrors this value, so the two cannot drift.
    *
    * NOT the WS frame cap (`MaxMessageSize` above, inbound), nor
    * `FileRefs.MaxFileSize` (200MB, the Card/Pop reference probe), nor the
    * inline budgets (5MB image / 40k `data:` URI) — those are other surfaces.
    *
    * Cost note: this gate bounds the WS TEXT-content leg only in size, not in
    * streaming (a WS frame carries one whole string). The BINARY leg does not
    * read the bytes at all — it sends metadata and the viewer streams the file
    * from `GET /api/nf-file` (http4s `StaticFile` ⇒ `fs2.io.file.Files.readRange`),
    * so a 100MB PDF never enters this JVM's heap (see the `pop.readFile` case). */
  private val MaxPopReadFileBytes: Long = 100L * 1024 * 1024

  // ── Text-stream legs (design card §三.4/§三.5, `TextStream.scala`) ─────────
  // The render-path switch: text files ABOVE `TextStream.ThresholdBytes` (8MiB,
  // server single point of truth) open as a read-only virtual-scroll view fed by
  // on-demand windows instead of one whole `content` frame. NOT a second open
  // gate — `MaxPopReadFileBytes` above stays the only size limit on this path.

  /** Per-request cancellation flags. `textCancel` flips the flag; the index
    * builder and the scan loop check it between chunks (cooperative cancel, so a
    * cancelled stream stops at the next 256KiB boundary). */
  private val textStreamCancels: Ref[IO, Map[String, Ref[IO, Boolean]]] = Ref.unsafe(Map.empty)

  /** In-flight scan count, capped at `TextStream.MaxConcurrentScans` — the pull
    * protocol already limits one window per tab, this bounds the server side when
    * several tabs search at once. */
  private val textStreamScans: Ref[IO, Int] = Ref.unsafe(0)

  /** Sparse-index LRU, bounded to `TextStream.MaxCachedIndexes` entries and keyed
    * by (path, size, mtimeMs): `size`/`mtimeMs` changing IS the invalidation. */
  private val textStreamIndexes: Ref[IO, Vector[(TextStream.IndexKey, TextStream.SparseIndex)]] =
    Ref.unsafe(Vector.empty)

  /** In-flight index builds, keyed by the same (path, size, mtimeMs). Without
    * this, the window request that needs `firstLine` and the parallel `textIndex`
    * request of the same open would both scan a 100MiB file. */
  private val textStreamIndexBuilds
    : Ref[IO, Map[TextStream.IndexKey, Deferred[IO, Either[Throwable, TextStream.SparseIndex]]]] =
    Ref.unsafe(Map.empty)

  /** Read [start, start+count) without ever holding the whole file: one bounded
    * array + a positional channel read. Short reads (EOF) are returned as-is. */
  private def readRangeBytes(p: os.Path, start: Long, count: Int): IO[Array[Byte]] =
    IO.blocking {
      val bb = java.nio.ByteBuffer.allocate(math.max(count, 0))
      val ch = java.nio.channels.FileChannel.open(p.toNIO, java.nio.file.StandardOpenOption.READ)
      try
        var pos = start
        var done = false
        while !done && bb.hasRemaining do
          val n = ch.read(bb, pos)
          if n <= 0 then done = true else pos += n
        bb.flip()
        val out = new Array[Byte](bb.remaining())
        bb.get(out)
        out
      finally ch.close()
    }

  /** Count newlines in [from, to) chunk by chunk — the `firstLine` refinement for
    * a window that does not start on an index anchor. Memory stays O(chunk); the
    * span is bounded by the distance from the nearest stride anchor, which for
    * line-aligned requests is one window or less. */
  private def countNewlinesBetween(p: os.Path, from: Long, to: Long): IO[Long] =
    def loop(offset: Long, acc: Long): IO[Long] =
      if offset >= to then IO.pure(acc)
      else
        val n = math.min(TextStream.ScanChunkBytes.toLong, to - offset).toInt
        readRangeBytes(p, offset, n).flatMap { chunk =>
          loop(offset + chunk.length, acc + TextStream.countNewlines(chunk, chunk.length))
        }
    loop(from, 0L)

  /** The text-stream legs' shared admission ruling — deliberately the SAME
    * reachable surface as today's text leg (`pop.readFile`): absolute path,
    * exists, regular file, ≤ the 100MB open gate. No credential-namespace check
    * and no extension whitelist, so streaming opens NO new surface (card §二.3 ②).
    * Binary files are refused: they have their own byte leg. */
  private def resolveStreamPath(rawPath: String): IO[os.Path] =
    for
      _ <- IO.raiseUnless(PathUtil.isAbsolute(rawPath))(new RuntimeException("path must be absolute"))
      p = PathUtil.resolvePath(rawPath)
      _ <- IO.raiseUnless(os.exists(p))(new RuntimeException(s"file not found: $rawPath"))
      _ <- IO.raiseUnless(os.isFile(p))(new RuntimeException("path is not a regular file"))
      size <- IO.blocking(os.size(p))
      _ <- IO.raiseWhen(size > MaxPopReadFileBytes)(
        new RuntimeException(s"file exceeds ${MaxPopReadFileBytes / (1024 * 1024)}MB limit")
      )
      ext = rawPath.split('.').lastOption.getOrElse("").toLowerCase
      _ <- IO.raiseWhen(nebflow.core.workspace.FileTypeRegistry.detect(ext).binary)(
        new RuntimeException("not a text file")
      )
    yield p

  private def textStreamCancelled(reqId: String): IO[Boolean] =
    if reqId.isEmpty then IO.pure(false)
    else
      textStreamCancels.get.flatMap { m =>
        m.get(reqId) match
          case Some(flag) => flag.get
          case None       => IO.pure(false)
      }

  /** Scan a file chunk by chunk into a sparse index. Cancellable between chunks. */
  private def buildTextIndex(p: os.Path, size: Long, reqId: String): IO[TextStream.SparseIndex] =
    val builder = new TextStream.IndexBuilder()
    def loop(offset: Long): IO[TextStream.SparseIndex] =
      textStreamCancelled(reqId).flatMap { stop =>
        if stop || offset >= size then IO.pure(builder.result)
        else
          val n = math.min(TextStream.ScanChunkBytes.toLong, size - offset).toInt
          readRangeBytes(p, offset, n).flatMap { chunk =>
            builder.feed(chunk, chunk.length)
            loop(offset + chunk.length)
          }
      }
    loop(0L)

  /** Cached index for (path, size, mtimeMs), de-duplicating concurrent builds. */
  private def textIndexFor(p: os.Path, size: Long, mtimeMs: Long, reqId: String): IO[TextStream.SparseIndex] =
    val key = TextStream.IndexKey(p.toString, size, mtimeMs)
    textStreamIndexes.get.flatMap { entries =>
      TextStream.lruGet(entries, key)._1 match
        case Some(idx) => textStreamIndexes.update(es => TextStream.lruPut(es, key, idx)).as(idx)
        case None =>
        Deferred[IO, Either[Throwable, TextStream.SparseIndex]].flatMap { gate =>
          type Gate = Deferred[IO, Either[Throwable, TextStream.SparseIndex]]
          textStreamIndexBuilds
            .modify[Either[Gate, Gate]] { m =>
              m.get(key) match
                case Some(existing) => (m, Left(existing))
                case None           => (m + (key -> gate), Right(gate))
            }
            .flatMap {
              case Left(existing) => existing.get.rethrow
              case Right(mine) =>
                buildTextIndex(p, size, reqId)
                  .flatTap(idx => textStreamIndexes.update(es => TextStream.lruPut(es, key, idx)))
                  .attempt
                  .flatTap(res => mine.complete(res))
                  .rethrow
                  .onCancel(mine.complete(Left(new RuntimeException("index build was cancelled"))).void)
                  .guarantee(textStreamIndexBuilds.update(_ - key))
            }
        }
    }

  /** Exact line number of the first line in a window starting at `startByte`:
    * the index anchor gives the line exactly when the request is anchor-aligned
    * (jump case, O(1)); otherwise the residual span is counted. */
  private def textFirstLine(p: os.Path, index: TextStream.SparseIndex, startByte: Long): IO[Long] =
    val (anchorOffset, anchorLine) = index.anchorFor(startByte)
    if anchorOffset >= startByte then IO.pure(anchorLine)
    else countNewlinesBetween(p, anchorOffset, startByte).map(n => TextStream.firstLineFrom(anchorLine, n))

  private def acquireScanSlot: IO[Boolean] =
    textStreamScans.modify(n =>
      if n < TextStream.MaxConcurrentScans then (n + 1, true) else (n, false)
    )

  private def releaseScanSlot: IO[Unit] = textStreamScans.update(n => math.max(0, n - 1))

  private def hitsJson(hits: Vector[TextStream.Hit]): Json =
    Json.arr(
      hits.map(h =>
        Json.obj("line" -> Json.fromLong(h.line), "col" -> Json.fromLong(h.col), "text" -> Json.fromString(h.text))
      )*
    )

  /** Stream the literal scan in `ScanChunkBytes` reads, emitting `textSearchHit`
    * frames of ≤ `TextStream.HitsPerFrame` hits. Stops on cancel, on EOF or on the
    * hit cap — every stop is reported as `truncated` (never a silent partial). */
  private def runTextSearch(
    wsSend: io.circe.Json => IO[Unit],
    reqId: String,
    tabId: String,
    p: os.Path,
    size: Long,
    scanner: TextStream.LiteralScanner,
    flag: Ref[IO, Boolean]
  ): IO[TextStream.SearchOutcome] =
    def sendBatch(batch: Vector[TextStream.Hit]): IO[Unit] =
      wsSend(
        Json.obj(
          "type" -> "textSearchHit".asJson,
          "reqId" -> reqId.asJson,
          "tabId" -> tabId.asJson,
          "hits" -> hitsJson(batch)
        )
      )
    def flush(hits: Vector[TextStream.Hit]): IO[Unit] =
      hits.grouped(TextStream.HitsPerFrame).toVector.foldLeft(IO.unit)((acc, b) => acc *> sendBatch(b))
    def loop(offset: Long): IO[TextStream.SearchOutcome] =
      flag.get.flatMap { stop =>
        if stop || offset >= size || scanner.truncated then
          IO(scanner.finish()) *> flush(scanner.drainHits())
            .as(TextStream.SearchOutcome(scanner.scannedBytes, scanner.totalHits, stop || scanner.truncated))
        else
          val n = math.min(TextStream.ScanChunkBytes.toLong, size - offset).toInt
          readRangeBytes(p, offset, n).flatMap { chunk =>
            scanner.feed(chunk, chunk.length)
            flush(scanner.drainHits()) *> loop(offset + chunk.length)
          }
      }
    loop(0L)

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
                  // 案 B（双开缺陷批 2026-09-21，chain-askuserdup）：作答行经**单一构造
                  // 点**落盘，带显式来源标记 answerOf = 被作答的 requestId ⇒ 历史恢复
                  // 取值由**数据**决定，不再靠「askUser 条目后面第一条 user 行」的邻接
                  // 启发式（该启发式在非阻塞提问/作答后继续打字的形态下会取偏 ⇒ 取样
                  // null ⇒ 历史卡被渲染成「已作答」⇒ 重放去重判据被击穿 ⇒ 同 id 双卡）。
                  List(UiMessage.askUserAnswer(answerText, requestId, System.currentTimeMillis()))
                ) *>
                  forwardInteractionAnswer(requestId, askSessionId, io.circe.Json.obj("answers" -> answers.asJson))
              case _ => IO.unit

          // ── 工作区目录选择链（2026-09-05 作者裁定，workspace-picker 批次）──
          // ProjectCreate「选择工作区」卡片点击 → 后端原生目录对话框（Route A）；
          // headless/异常时 WorkspaceDirPicker 回 fallback 事件 → 前端降级应用内
          // 浏览器（Route C，wsBrowse.list / wsBrowse.mkdir）。
          // 关键：WS 帧串行（evalMap），对话框阻塞必须 `.start` 独立 fiber，
          // 否则冻结整条连接（卡片后续 askUserAnswer 无法处理）。
          case "pickWorkspaceDir" =>
            val hc = parse(text).toOption.getOrElse(io.circe.Json.Null).hcursor
            (hc.downField("sessionId").as[String].toOption, hc.downField("requestId").as[String].toOption) match
              case (Some(sid), Some(rid)) =>
                WorkspaceDirPicker.pick(sid, rid, wsSend).start.void
              case _ =>
                logger.warn(s"pickWorkspaceDir: missing sessionId/requestId — dropped")

          case "wsBrowse.list" =>
            val hc = parse(text).toOption.getOrElse(io.circe.Json.Null).hcursor
            hc.downField("path").as[String].toOption.filter(_.nonEmpty) match
              case None => IO.unit
              case Some(raw) =>
                IO.blocking {
                  scala.util.Try(os.Path(expandTilde(raw), os.Path(sys.props("user.home")))).toOption match
                    case None => (raw, None, List.empty[String], "invalid path")
                    case Some(dir) =>
                      if !os.isDir(dir) then (dir.toString, None, Nil, "not a directory")
                      else
                        // 2026-09-09 作者裁定：默认显示隐藏文件夹（dot 目录不过滤）——
                        // 选目录模式须能进入 ~/.nebflow/projects/（隐藏目录嵌套路径）。
                        val entries = os.list(dir)
                          .filter(os.isDir)
                          .map(_.last)
                          .toList.sorted
                        // 根目录无上级（segmentCount==0）；其余经 os.up 规范化
                        val parent = if dir.segmentCount > 0 then Some((dir / os.up).toString) else None
                        (dir.toString, parent, entries, null: String)
                }.flatMap { case (path, parent, entries, err) =>
                    wsSend(wsBrowseEvent("wsBrowseList", path, parent, entries, Option(err)))
                }.handleErrorWith { e =>
                  wsSend(wsBrowseEvent("wsBrowseList", raw, None, Nil, Some(e.getMessage)))
                }

          case "wsBrowse.mkdir" =>
            val hc = parse(text).toOption.getOrElse(io.circe.Json.Null).hcursor
            (hc.downField("path").as[String].toOption, hc.downField("name").as[String].toOption.map(_.trim)) match
              case (Some(raw), Some(name)) if name.nonEmpty && name != "." && name != ".." && !name.contains('/') && !name.contains('\\') =>
                IO.blocking {
                  val base = os.Path(expandTilde(raw), os.Path(sys.props("user.home")))
                  os.makeDir(base / name)
                  base / name
                }.flatMap { created =>
                  wsSend(Json.obj(
                    "type" -> Json.fromString("wsBrowseMkdir"),
                    "path" -> Json.fromString(created.toString),
                    "ok" -> Json.fromBoolean(true)
                  ))
                }.handleErrorWith { e =>
                  wsSend(Json.obj(
                    "type" -> Json.fromString("wsBrowseMkdir"),
                    "path" -> Json.fromString(raw),
                    "ok" -> Json.fromBoolean(false),
                    "error" -> Json.fromString(Option(e.getMessage).getOrElse("mkdir failed"))
                  ))
                }
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

          case "interrupt" =>
            val intSessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).toOption.getOrElse("")
            // Hard-recovery P4: an interrupt aimed at a wedged turn cannot be
            // consumed (P3 keeps the actor alive, but the parked read survives
            // fiber cancellation) — kick the transport so the turn actually dies.
            maybeSessionKick(intSessionId, "interrupt") *>
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
                    case Some(rec) if !nebflow.core.tools.AgentControlTool.cancelable(rec) =>
                      cancelReply(
                        ok = false,
                        "error" -> s"Kind ${rec.kind} is read-only (cancelable: Delegate / SubTask / Ephemeral / Project node-dispatcher sessions)".asJson
                      )
                    case Some(rec) =>
                      val reason = if cReason.nonEmpty then cReason else "cancelled from panel"
                      // notifyWs = 本连接 wsSend：node-* 会话取消补发 agentDone
                      // 面板帧（Sub-Agents 面板取消实时刷新修复；dispatcher-*
                      // 由观察桥拆除点补发，不在此重发）。
                      nebflow.core.tools.AgentControlTool
                        .doCancel(sharedResources, rec, reason, notifyWs = Some(wsSend))
                        .flatMap {
                          case Right(msg) => cancelReply(ok = true, "message" -> msg.asJson)
                          case Left(err)  => cancelReply(ok = false, "error" -> err.message.asJson)
                        }
                }

          case "chainCancel" =>
            // **链级取消（R2 面板入口）**，chaincancel 批 2026-09-17。
            // 帧 = `{type:'chainCancel', chainId, reason?}`——🔴 **前端只发 chainId**：
            // 成员集合 / 状态判定 / 级联闭包**全在后端**（"后端下发、零派生"硬纪律；
            // 链解析唯一单点 = `FlowMapStore.chainMembersOf`）。项目由注册表反查：
            // chainId 只作**查找键**（同一 chainId 同时在两个项目里 = 节点 id 碰撞，
            // 概率可忽略但**不静默**——报可行动歧义错误）。
            val chJson = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val chChainId = chJson.hcursor.downField("chainId").as[String].toOption.getOrElse("").trim
            val chReason = chJson.hcursor.downField("reason").as[String].toOption.getOrElse("")
            def entryJson(e: ChainCancelEntry): Json =
              Json.obj(
                "id" -> e.nodeId.asJson, "name" -> e.name.asJson, "status" -> e.status.asJson,
                "why" -> e.why.asJson, "signalled" -> e.signalled.asJson)
            def chainCancelReply(ok: Boolean, report: Option[ChainCancelReport], err: Option[String]): IO[Unit] =
              val base = Seq(
                ("type", "chainCancelResult".asJson),
                ("ok", ok.asJson),
                ("chainId", chChainId.asJson),
                ("error", err.map(_.asJson).getOrElse(Json.Null))
              )
              val payload = report match
                case None => base
                case Some(r) => base ++ Seq(
                  ("chainTitle", r.chainTitle.asJson),
                  ("cancelled", r.cancelled.map(entryJson).asJson),
                  ("preserved", r.preserved.map(entryJson).asJson),
                  ("skipped", r.skipped.map(entryJson).asJson),
                  ("prunedReferrers", r.prunedReferrers.asJson),
                  ("injected", r.injected.asJson),
                  ("notified", r.notified.asJson)
                )
              wsSend(Json.obj(payload*))
            if chChainId.isEmpty then
              chainCancelReply(ok = false, None, Some("chainCancel requires chainId"))
            else
              def resolved(rt: ProjectRuntime): IO[Boolean] = rt.store.chainMembersOf(chChainId).map(_.isDefined)
              ProjectRuntimeRegistry.all.flatMap(_.traverse(rt => resolved(rt).map(rt -> _))).flatMap { pairs =>
                val hits = pairs.collect { case (rt, true) => rt }
                hits match
                  case Nil =>
                    chainCancelReply(ok = false, None,
                      Some(s"CHAIN_NOT_FOUND: no mounted project owns chain '$chChainId' " +
                        "(chain ids come from the Flow Map chains[] payload)"))
                  case one :: Nil =>
                    val reason = if chReason.nonEmpty then chReason else "cancelled from Flow Map panel (chain-level)"
                    logger.info(s"chainCancel (panel) for chain $chChainId in project '${one.project.name}'") *>
                      one.engine.cancelChain(chChainId, ChainCancelSource.User, reason).flatMap {
                        case Left(err)  => chainCancelReply(ok = false, None, Some(err))
                        case Right(rep) => chainCancelReply(ok = true, Some(rep), None)
                      }
                  case many =>
                    chainCancelReply(ok = false, None,
                      Some(s"AMBIGUOUS_CHAIN_ID: chain '$chChainId' exists in ${many.size} mounted projects " +
                        s"(${many.map(_.project.name).mkString(", ")}) — refusing to guess which one to cancel"))
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
                    case Some(rec) if rec.kind == nebflow.agent.AgentKind.Flow =>
                      // Project flow 会话（node-/dispatcher-）：单次会话，supervisor
                      // 是观察桥（不 respawn）——restart 语义不成立，且 raw Stop 会
                      // 杀 agent 而不发终态事件（node engine fiber 挂死）。拒绝并指路。
                      prReply(
                        ok = false,
                        "error" ->
                          "Project node/dispatcher sessions are single-shot — restart not supported. Cancel it and re-trigger the work.".asJson
                      )
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
            admitWorkOrRefuse(wsSend)(
              // 真人：用户在客户端点发送（队列条「立即发送」/输入框直投）
              handleUserText(immSessionId, immContent, source = "immediateInput", fromUser = true)
            )

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
            admitWorkOrRefuse(wsSend)(
              // 真人：CLI (ChatSend) 是人在终端里敲的字——实测本帧确实走
              // handleUserText → dispatchUserText（与 immediateInput 同一腿）。
              handleUserText(umSessionId, umContent, source = "userMessage", fromUser = true)
            )

          // ── 热重启触发面（P1，hot-restart 批设计 §3.2）：Web UI 按钮的 WS 命令。
          // confirm=true 必带（前端确认对话框「将等待当前工作完成后重启」的引擎侧
          // 强制位）；waitIdle 默认 true（§7 拍板项 2 建议：UI 默认排队可取消语义
          // 的后端形态——等待期工作照常准入）。进度经 restartStatus 帧 wsHub 广播
          //（quiesce/draining/spawning/handing-over/completed/failed）。编排器与
          // 触发源解耦——桌面菜单/REST API（P2）后续接同一 HotRestart.requestRestart。
          case "restart" =>
            val rc = parse(text).toOption.getOrElse(io.circe.Json.Null).hcursor
            val confirmed = rc.downField("confirm").as[Boolean].getOrElse(false)
            val waitIdle = rc.downField("waitIdle").as[Boolean].getOrElse(true)
            val waitTimeoutMs = rc.downField("waitTimeoutMs").as[Long].getOrElse(600000L)
            def restartReply(ok: Boolean, extra: (String, Json)*): IO[Unit] =
              wsSend(
                io.circe.Json.obj(
                  Seq(("type", "restartResult".asJson), ("ok", ok.asJson)) ++ extra*
                )
              )
            if !confirmed then
              restartReply(
                ok = false,
                "error" -> "restart requires confirm=true — the gateway restarts in place once current work finishes".asJson
              )
            else
              sharedResources.hotRestart match
                case None =>
                  restartReply(ok = false, "error" -> "hot restart is not available in this instance".asJson)
                case Some(hr) =>
                  val mode =
                    if waitIdle then nebflow.core.hotrestart.RestartMode.WaitIdle(waitTimeoutMs)
                    else nebflow.core.hotrestart.RestartMode.RejectIfBusy
                  hr.requestRestart(source = "web-ui", mode = mode).flatMap {
                    case Right(()) =>
                      restartReply(
                        ok = true,
                        "message" -> "hot restart underway — the UI will reconnect automatically when the new instance is up".asJson
                      )
                    case Left(err) => restartReply(ok = false, "error" -> err.asJson)
                  }

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
              case "fork" =>
                val forkSessionId =
                  parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).toOption.getOrElse("")
                for
                  sourceMetaOpt <- sessionStore.getSessionMeta(forkSessionId)
                  sourceName = sourceMetaOpt.map(_.name).getOrElse("Session")
                  _ <- logger.info(s"Forking session $forkSessionId ($sourceName)")
                  newMeta <- sessionStore.forkSession(forkSessionId, s"Fork of $sourceName")
                  // 出口 overlay（设计 §13 #9）：fork 帧同样输出有效档位。
                  _ <- (sessionStore.listSessions, sessionStore.listAllFolders).flatMapN { (sessions, folders) =>
                    val rulesFolderIds = folders.filter(f => nebflow.service.RulesStore.exists(f.id)).map(_.id)
                    sharedResources.overlaySessionList(sessions).flatMap { sessionsJson =>
                      wsSend(
                        io.circe.Json.obj(
                          "type" -> "sessionList".asJson,
                          "sessions" -> sessionsJson,
                          "folders" -> folders.asJson,
                          "activeId" -> forkSessionId.asJson,
                          "foldersWithRules" -> rulesFolderIds.asJson
                        )
                      )
                    }
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
            // 默认关批（2026-09-13）：缺字段不再视为「开」——与新默认态一致
            // （fail-safe：无明确指令不改状态；UI 恒带 enabled，见 sidebar.js）。
            val enabled = parse(text).toOption
              .flatMap(_.hcursor.downField("enabled").as[Boolean].toOption)
              .getOrElse(false)
            // 持久化优先（D-A：显式改动过的值须跨重启保持）：落盘成功才热更
            // 内存态，镜像 setToolResultTtl 的「persist 成功才热更」排序——
            // 不产生「内存已改、盘上未改」的窗口。落盘失败 = fail-loud：
            // WARN + configUpdateFailed，并回**权威现值**让 UI 与实际一致。
            nebflow.service.ConfigService.setLlmLogEnabled(enabled).attempt.flatMap {
              case Right(_) =>
                LlmLogWriter.setEnabled(enabled)
                logger.info(s"LLM log set to: $enabled") *>
                  wsSend(io.circe.Json.obj("type" -> "llmLogState".asJson, "enabled" -> enabled.asJson))
              case Left(e) =>
                logger.warn(s"Failed to persist llmLog.enabled=$enabled: ${e.getMessage}") *>
                  wsSend(io.circe.Json.obj(
                    "type" -> "configUpdateFailed".asJson,
                    "message" -> s"LLM 日志开关保存失败: ${e.getMessage}".asJson
                  )) *>
                  wsSend(io.circe.Json.obj("type" -> "llmLogState".asJson, "enabled" -> LlmLogWriter.isEnabled.asJson))
            }

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

          case "getCompactThreshold" =>
            // ctxthresh 批（2026-09-15 方案 A）：面板打开时读**权威值**——Ref 含未
            // 重启的热更值，盘上 meta 含跨重启保留值（先例 getToolResultTtl）。
            val json = parse(text).toOption.getOrElse(Json.Null)
            val sid = json.hcursor.downField("sessionId").as[String].getOrElse("")
            if sid.nonEmpty then compactThresholdInfo(sid).flatMap(wsSend) else IO.unit

          case "setCompactThreshold" =>
            // ctxthresh 批：payload {sessionId, ratio: number|null}（ratio=null ⇒
            // 恢复默认 = 清覆盖）。STRICT 校验（镜像 setToolResultTtl 的交互面纪律）：
            // 值域逐字 = 作者卡答「上限90%，下限…大于15%」⇒ `15% < r ≤ 90%`
            // （CompactThresholdOverride.isValid）。越界 ⇒ **拒绝并亮错**，不做静默
            // 钳制——「钳回」是 UI 侧滑杆的动态下限职责（js/ctxthresh.js）。
            // 成功路径 = persist-then-hot 三步（盘上 SessionMeta → 内存 Ref → 通知
            // 活体 root agent）+ 回权威回显帧。
            // 🔴 作用域闸（首行）= fail-closed + Nebula 身份门（2026-09-15 返工，见
            // `isRootScopeSession`）——非 Nebula 会话 / 幽灵 id / 未注册且非活跃者
            // 一律走 reject 分支，**绝不落盘**。
            val json = parse(text).toOption.getOrElse(Json.Null)
            val sid = json.hcursor.downField("sessionId").as[String].getOrElse("")
            val rawRatio = json.hcursor.downField("ratio").as[Option[Double]].toOption.flatten
            if sid.isEmpty then IO.unit
            else
              val reject = (msg: String) =>
                logger.warn(s"Rejected setCompactThreshold for $sid: $msg") *>
                  wsSend(
                    Json.obj(
                      "type" -> Json.fromString("compactThresholdError"),
                      "sessionId" -> Json.fromString(sid),
                      "message" -> Json.fromString(msg)
                    )
                  )
              isRootScopeSession(sid).flatMap { isRoot =>
                if !isRoot then reject("scope: only the root session (Nebula window) may override")
                else if rawRatio.exists(r => !CompactThresholdOverride.isValid(r)) then
                  reject(
                    s"ratio must satisfy 15% < r <= 90% (got ${rawRatio.getOrElse(Double.NaN)})"
                  )
                else
                  val persist = sessionStore.updateSessionCompactThreshold(sid, rawRatio) *>
                    sharedResources.sessionCompactThreshold.update { m =>
                      rawRatio.fold(m - sid)(r => m + (sid -> r))
                    } *>
                    ensureAgent(sid)(ref => ref ! AgentCommand.SetCompactThresholdRatio(rawRatio))
                  persist.handleErrorWith { e =>
                    logger.warn(s"setCompactThreshold failed for $sid: ${e.getMessage}") *>
                      wsSend(
                        Json.obj(
                          "type" -> Json.fromString("compactThresholdError"),
                          "sessionId" -> Json.fromString(sid),
                          "message" -> Json.fromString(e.getMessage)
                        )
                      )
                  } *> compactThresholdInfo(sid).flatMap(wsSend)
              }
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
                    stopTeamSessionActors(sessionId) *> stopChildDelegateActors(sessionId) *> removeRootAgent(sessionId) *> sessionService
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
                        stopChildDelegateActors(sid) *>
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
            // ── 通道处置（permshield S1，2026-09-13 作者重裁「候选 B」）────────────
            // **改造为写全局持久**（不退役），理由：
            //   ① 盾牌是作者指定的唯一 UI 调整入口（「就使用 header 的盾牌来调整」），
            //      它发的就是这个帧；退役该帧 ⇒ 盾牌在 F1 落地前完全失效（功能回归）。
            //   ② 作者口径「落全局持久（跟盾牌走同一条路，重启后仍生效）」要的正是
            //      改这条通道的**写入目标**，不是删掉它。
            // 处置后语义：写 `nebflow.json` 的 `safety.defaultMode`（与 REST
            // `PUT /api/safety/mode` 同一个持久函数 ⇒ 同一路），广播 `configUpdated`
            // （所有客户端同步），再推本连接会话列表。
            // 🔴 旧行为「本会话临时覆盖、不落盘」**已消失**：档位不再有会话维度。
            // `sessionId` 仅用于回推列表（档位本身是应用级的，对所有会话一致）。
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val sid = json.hcursor.downField("sessionId").as[String].getOrElse("")
            val rawMode = json.hcursor.downField("safetyMode").as[String].toOption
            // 三档显式白名单：未知值**拒绝**（旧行为是静默 `getOrElse("confirm-edits")`
            // ——把拼错/缺失的值悄悄变成最严档，用户无感且不可诊断）。拒绝 ⇒ 不落盘。
            val modeOpt = rawMode.flatMap(nebflow.core.SafetyMode.fromWire)
            if sid.nonEmpty then
              modeOpt match
                case None =>
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "error".asJson,
                      "message" -> s"unknown safetyMode '${rawMode.getOrElse("")}' — valid: confirm-edits, auto-edits, auto-all".asJson
                    )
                  )
                case Some(mode) =>
                  persistGlobalSafetyMode(nebflow.core.SafetyMode.toString(mode))
                    .flatMap(_ => sendAgentSessionList(wsSend, sid))
                    .handleErrorWith { e =>
                      // 落盘失败 = 档位**没有**改变（fail-loud，不静默回滚）
                      logger.error(s"setSafetyMode persist failed: ${e.getMessage}", e) *>
                        wsSend(
                          io.circe.Json.obj(
                            "type" -> "error".asJson,
                            "message" -> s"failed to persist safety mode '${nebflow.core.SafetyMode.toString(mode)}': ${e.getMessage}".asJson
                          )
                        )
                    }
            else IO.unit

          case "setBypass" =>
            // Backward compat: old clients send { bypass: Boolean }（等价于
            // setSafetyMode 的两档特例）——permshield S1 起同样**写全局持久**；
            // 该帧已无任何前端调用点（`grep -rn setBypass src/main/resources/web` = 0），
            // 保留仅因 wire 向后兼容（老客户端 / 外部脚本）。
            val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
            val sid = json.hcursor.downField("sessionId").as[String].getOrElse("")
            val bypass = json.hcursor.downField("bypass").as[Boolean].getOrElse(false)
            val mode = if bypass then "auto-all" else "confirm-edits"
            if sid.nonEmpty then
              persistGlobalSafetyMode(mode)
                .flatMap(_ => sendAgentSessionList(wsSend, sid))
                .handleErrorWith { e =>
                  logger.error(s"setBypass persist failed: ${e.getMessage}", e) *>
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "error".asJson,
                        "message" -> s"failed to persist safety mode '$mode': ${e.getMessage}".asJson
                      )
                    )
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
                entries <- IO.blocking(WebSocketRoutes.listDirEntries(basePath))
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
                fileSize <- IO.blocking(os.size(basePath))
                readExt = filePath.split('.').lastOption.getOrElse("").toLowerCase
                readIsBinary = nebflow.core.workspace.FileTypeRegistry.detect(readExt).binary
                // 流式态（卡 §三.3，`readFile` 腿同判）：文本件 > 阈值即不读字节。
                // 改前这里对所有件无条件读（二进制读到的 content 在帧里被丢弃），
                // 现在二进制与流式态都跳过读取。
                readIsStream = TextStream.isStream(readIsBinary, fileSize)
                readMtimeMs <- IO.blocking(os.mtime(basePath))
                content <- IO.blocking {
                  if readIsBinary || readIsStream then ""
                  else if fileSize > 2 * 1024 * 1024 then
                    os.read(basePath, offset = 0, count = 2 * 1024 * 1024) + "\n\n[... file truncated at 2MB]"
                  else os.read(basePath)
                }
              yield (content, basePath.toString, fileSize, readMtimeMs, readIsStream))
                .flatMap { case (content, absPath, fileSize, mtimeMs, isStream) =>
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
                  else if isStream then
                    // 流式描述符（同 `pop.readFile` 腿）：无 `content` + `stream` +
                    // `mtimeMs`；`itemType` 非空是硬要求（坑 ①）。
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "fileContent".asJson,
                        "path" -> filePath.asJson,
                        "absPath" -> absPath.asJson,
                        "itemType" -> itemType.asJson,
                        "fileName" -> filePath.split('/').last.asJson,
                        "size" -> fileSize.asJson,
                        "mtimeMs" -> mtimeMs.asJson,
                        "stream" -> io.circe.Json.obj("v" -> io.circe.Json.fromInt(1), "kind" -> "text".asJson)
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
            // Expand a leading `~` (home shorthand: `~`, `~/`, `~\`) to the
            // absolute home dir — historical Pop records may store `~/...`;
            // only the server (which knows user.home) can resolve it to an
            // absolute path. PathUtil also normalizes separators on Windows.
            val popFilePath = PathUtil.expandTilde(popFilePathRaw)
            if popFilePath.nonEmpty then
              (for
                // Cross-platform absolute check: `startsWith("/")` rejected
                // every Windows path (C:\..., C:/..., UNC) with "path must be
                // absolute" — the file-browser Windows-path bug (diag-win-paths).
                // PathUtil.isAbsolute accepts POSIX, drive-letter and UNC forms.
                _ <- IO.raiseUnless(PathUtil.isAbsolute(popFilePath))(
                  new RuntimeException("path must be absolute")
                )
                // resolvePath (not bare os.Path): survives cross-drive paths
                // (pwd on C:, target on D:) via the java.nio fallback.
                basePath = PathUtil.resolvePath(popFilePath)
                _ <- IO.raiseUnless(os.exists(basePath))(
                  new RuntimeException(s"file not found: $popFilePath")
                )
                _ <- IO.raiseUnless(os.isFile(basePath))(
                  new RuntimeException("path is not a regular file")
                )
                fileSize = os.size(basePath)
                _ <- IO.raiseWhen(fileSize > MaxPopReadFileBytes)(
                  new RuntimeException(s"file exceeds ${MaxPopReadFileBytes / (1024 * 1024)}MB limit")
                )
                // 🔴 二进制腿不读字节（2026-09-20 打开闸批）：本 case 只回元数据，
                // 字节由前端经 `/api/nf-file` 流式取回（`StaticFile` ⇒
                // `fs2.io.file.Files.readRange`，支持 Range）。改前这里对所有件
                // 无条件 `os.read` 再丢弃二进制的 content —— 闸提到 100MB 后那等于
                // 为一次 PDF 打开把 100MB 读进堆里再扔。
                popExt = popFilePath.split('.').lastOption.getOrElse("").toLowerCase
                popIsBinary = nebflow.core.workspace.FileTypeRegistry.detect(popExt).binary
                // 流式态（卡 §三.3）：> TextStream.ThresholdBytes 的文本件**不读字节** ——
                // 100MiB 整件读出 = 单帧 106.4M 字符 / 峰值 +1.07GB。改为回元数据 +
                // `stream` 描述符，字节由 `textWindow` 按需拉（一窗一取 = 流控本体）。
                popIsStream = TextStream.isStream(popIsBinary, fileSize)
                popMtimeMs <- IO.blocking(os.mtime(basePath))
                content <- if popIsBinary || popIsStream then IO.pure("") else IO.blocking { os.read(basePath) }
              yield (content, basePath.toString, fileSize, popFilePath, popMtimeMs, popIsStream))
                .flatMap { case (content, absPath, fileSize, origPath, mtimeMs, isStream) =>
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
                  else if isStream then
                    // 流式描述符：无 `content`（与二进制腿同形），+ `stream` + `mtimeMs`。
                    // 🔴 `itemType` 必须非空（卡 §三.3 坑 ①）：空 itemType 会命中
                    // canvas.js 的「空内容再取一次」分支 ⇒ 自激循环。FileTypeRegistry
                    // 对未知扩展名回落 `code`，故非空 —— TextStreamSpec 钉住该不变量。
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "fileContent".asJson,
                        "path" -> origPath.asJson,
                        "absPath" -> absPath.asJson,
                        "itemType" -> itemType.asJson,
                        "fileName" -> origPath.split('/').last.asJson,
                        "size" -> fileSize.asJson,
                        "mtimeMs" -> mtimeMs.asJson,
                        "stream" -> io.circe.Json.obj("v" -> io.circe.Json.fromInt(1), "kind" -> "text".asJson)
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

          // ── Text-stream legs (design card §三.4: frame shapes are the contract) ──
          // Pull protocol: the client asks one window / the index / one scan at a
          // time, so flow control is structural (per tab ≤1 window in flight) and
          // the server never queues a file's worth of bytes (the `Queue.unbounded`
          // outbound trap, §二.3 ②).

          case "textWindow" =>
            val thc = parse(text).toOption.getOrElse(io.circe.Json.Null).hcursor
            val twReqId = thc.downField("reqId").as[String].getOrElse("")
            val twTabId = thc.downField("tabId").as[String].getOrElse("")
            val twPath = PathUtil.expandTilde(thc.downField("path").as[String].getOrElse(""))
            val twMtime = thc.downField("mtimeMs").as[Long].getOrElse(0L)
            val twStart = thc.downField("startByte").as[Long].getOrElse(-1L)
            val twEnd = thc.downField("endByte").as[Long].getOrElse(-1L)
            if twReqId.nonEmpty && twPath.nonEmpty then
              (for
                twFile <- resolveStreamPath(twPath)
                twSize <- IO.blocking(os.size(twFile))
                twMtimeNow <- IO.blocking(os.mtime(twFile))
                _ <- IO.raiseWhen(twMtime > 0 && twMtime != twMtimeNow)(
                  new RuntimeException("file changed since the index was built (mtime drift) — re-index")
                )
                _ <- IO.raiseUnless(twStart >= 0 && twEnd > twStart)(
                  new RuntimeException(s"invalid window range [$twStart, $twEnd)")
                )
                _ <- IO.raiseWhen(twEnd - twStart > TextStream.MaxWindowBytes)(
                  new RuntimeException(s"window request exceeds ${TextStream.MaxWindowBytes} bytes")
                )
                _ <- IO.raiseUnless(twStart < twSize)(new RuntimeException("window starts beyond end of file"))
                twIndex <- textIndexFor(twFile, twSize, twMtimeNow, twReqId)
                twReadEnd = math.min(twEnd, twSize)
                twBytes <- readRangeBytes(twFile, twStart, (twReadEnd - twStart).toInt)
                twFirstLine <- textFirstLine(twFile, twIndex, twStart)
              yield (twBytes, twFirstLine, twSize, twMtimeNow))
                .timeoutTo(
                  TextStream.RequestTimeout,
                  IO.raiseError(new RuntimeException(s"text window timed out after ${TextStream.RequestTimeout}"))
                )
                .flatMap { case (twBytes, twFirstLine, twSize, twMtimeNow) =>
                  val realEnd = twStart + twBytes.length
                  val twText = TextStream.decode(twBytes, twBytes.length)
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "textWindow".asJson,
                      "reqId" -> twReqId.asJson,
                      "tabId" -> twTabId.asJson,
                      "path" -> twPath.asJson,
                      "mtimeMs" -> twMtimeNow.asJson,
                      "startByte" -> twStart.asJson,
                      "endByte" -> realEnd.asJson,
                      "text" -> twText.asJson,
                      "firstLine" -> twFirstLine.asJson,
                      "lineCount" -> TextStream.lineCountOf(twText).asJson,
                      "eof" -> (realEnd >= twSize).asJson
                    )
                  )
                }
                .handleErrorWith { e =>
                  logger.warn(s"textWindow failed: ${e.getMessage}")
                    *> wsSend(
                      io.circe.Json.obj(
                        "type" -> "textWindow".asJson,
                        "reqId" -> twReqId.asJson,
                        "tabId" -> twTabId.asJson,
                        "path" -> twPath.asJson,
                        "error" -> e.getMessage.asJson
                      )
                    )
                }
            else IO.unit
            end if

          case "textIndex" =>
            val thc = parse(text).toOption.getOrElse(io.circe.Json.Null).hcursor
            val tiReqId = thc.downField("reqId").as[String].getOrElse("")
            val tiTabId = thc.downField("tabId").as[String].getOrElse("")
            val tiPath = PathUtil.expandTilde(thc.downField("path").as[String].getOrElse(""))
            val tiMtime = thc.downField("mtimeMs").as[Long].getOrElse(0L)
            if tiReqId.nonEmpty && tiPath.nonEmpty then
              (for
                tiFile <- resolveStreamPath(tiPath)
                tiSize <- IO.blocking(os.size(tiFile))
                tiMtimeNow <- IO.blocking(os.mtime(tiFile))
                _ <- IO.raiseWhen(tiMtime > 0 && tiMtime != tiMtimeNow)(
                  new RuntimeException("file changed since the index was built (mtime drift) — re-index")
                )
                tiIndex <- textIndexFor(tiFile, tiSize, tiMtimeNow, tiReqId)
              yield (tiSize, tiMtimeNow, tiIndex))
                .timeoutTo(
                  TextStream.RequestTimeout,
                  IO.raiseError(new RuntimeException(s"text index timed out after ${TextStream.RequestTimeout}"))
                )
                .flatMap { case (tiSize, tiMtimeNow, tiIndex) =>
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "textIndex".asJson,
                      "reqId" -> tiReqId.asJson,
                      "tabId" -> tiTabId.asJson,
                      "path" -> tiPath.asJson,
                      "size" -> tiSize.asJson,
                      "mtimeMs" -> tiMtimeNow.asJson,
                      "totalLines" -> tiIndex.totalLines.asJson,
                      "stride" -> tiIndex.stride.asJson,
                      "lineStarts" -> io.circe.Json.arr(tiIndex.lineStarts.map(io.circe.Json.fromLong)*)
                    )
                  )
                }
                .handleErrorWith { e =>
                  logger.warn(s"textIndex failed: ${e.getMessage}")
                    *> wsSend(
                      io.circe.Json.obj(
                        "type" -> "textIndex".asJson,
                        "reqId" -> tiReqId.asJson,
                        "tabId" -> tiTabId.asJson,
                        "path" -> tiPath.asJson,
                        "error" -> e.getMessage.asJson
                      )
                    )
                }
            else IO.unit
            end if

          case "textSearch" =>
            val thc = parse(text).toOption.getOrElse(io.circe.Json.Null).hcursor
            val tsReqId = thc.downField("reqId").as[String].getOrElse("")
            val tsTabId = thc.downField("tabId").as[String].getOrElse("")
            val tsPath = PathUtil.expandTilde(thc.downField("path").as[String].getOrElse(""))
            val tsMtime = thc.downField("mtimeMs").as[Long].getOrElse(0L)
            val tsQuery = thc.downField("query").as[String].getOrElse("")
            val tsCase = thc.downField("caseSensitive").as[Boolean].getOrElse(false)
            val tsMaxHitsRaw = thc.downField("maxHits").as[Long].getOrElse(TextStream.DefaultMaxHits.toLong)
            val tsMaxHits =
              if tsMaxHitsRaw <= 0 then TextStream.DefaultMaxHits
              else math.min(tsMaxHitsRaw, TextStream.MaxAllowedMaxHits.toLong).toInt
            if tsReqId.nonEmpty && tsPath.nonEmpty && tsQuery.nonEmpty then
              (for
                tsFile <- resolveStreamPath(tsPath)
                tsSize <- IO.blocking(os.size(tsFile))
                tsMtimeNow <- IO.blocking(os.mtime(tsFile))
                _ <- IO.raiseWhen(tsMtime > 0 && tsMtime != tsMtimeNow)(
                  new RuntimeException("file changed since the index was built (mtime drift) — re-index")
                )
                tsSlot <- acquireScanSlot
                _ <- IO.raiseUnless(tsSlot)(
                  new RuntimeException(s"too many concurrent text searches (limit ${TextStream.MaxConcurrentScans})")
                )
                tsScanner = new TextStream.LiteralScanner(tsQuery, tsCase, tsMaxHits)
                tsFlag <- Ref[IO].of(false)
                _ <- textStreamCancels.update(_ + (tsReqId -> tsFlag))
                tsOutcome <- runTextSearch(wsSend, tsReqId, tsTabId, tsFile, tsSize, tsScanner, tsFlag)
                  .guarantee(releaseScanSlot *> textStreamCancels.update(_ - tsReqId))
              yield tsOutcome)
                .timeoutTo(
                  TextStream.RequestTimeout,
                  IO.raiseError(new RuntimeException(s"text search timed out after ${TextStream.RequestTimeout}"))
                )
                .flatMap { tsOutcome =>
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "textSearchDone".asJson,
                      "reqId" -> tsReqId.asJson,
                      "tabId" -> tsTabId.asJson,
                      "path" -> tsPath.asJson,
                      "scannedBytes" -> tsOutcome.scannedBytes.asJson,
                      "hits" -> tsOutcome.totalHits.asJson,
                      "truncated" -> tsOutcome.truncated.asJson
                    )
                  )
                }
                .handleErrorWith { e =>
                  logger.warn(s"textSearch failed: ${e.getMessage}")
                    *> wsSend(
                      io.circe.Json.obj(
                        "type" -> "textSearch".asJson,
                        "reqId" -> tsReqId.asJson,
                        "tabId" -> tsTabId.asJson,
                        "path" -> tsPath.asJson,
                        "error" -> e.getMessage.asJson
                      )
                    )
                }
            else IO.unit
            end if

          case "textCancel" =>
            // tab 关闭 / 卸载 ⇒ 释放服务端 per-stream 状态并中断在跑扫描（卡 §三.5：
            // 不做跨会话续传，重开 = 重建索引 + 重取可见窗，成本有界）。
            val thc = parse(text).toOption.getOrElse(io.circe.Json.Null).hcursor
            val tcReqId = thc.downField("reqId").as[String].getOrElse("")
            if tcReqId.nonEmpty then
              textStreamCancels.get.flatMap { m =>
                m.get(tcReqId) match
                  case Some(flag) => flag.set(true)
                  case None       => IO.unit
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
                // remove.all: single delete must cover non-empty directories too
                // (VS Code parity — a right-click delete on a folder takes the
                // whole subtree; previously os.remove failed on non-empty dirs
                // and only the batch deletePaths channel could remove them).
                // On a plain file remove.all behaves exactly like remove.
                wasDir <- IO.blocking { os.isDir(basePath) }
                existed <- IO.blocking { os.exists(basePath) }
                _ <- IO.blocking { if existed then os.remove.all(basePath) }
                // ── 补盲区：删除**成功分支**留痕（#159/#176 wtsurv 批，2026-09-14）────
                // 取证件 `20260913_100008_worktree-vanish-forensics.md` §1.4 第 1 条 /
                // §6.1 第 2 条：WS `deletePath` 是**非网关通道**（UI 触发，不是 agent
                // 工具调用），递归删目录用 `os.remove.all` ⇒ **不碰** `.git/worktrees/
                // <name>` 注册 ⇒ 精确制造「目录消失 + 注册残留 = prunable」签名；而成功
                // 路径**零日志**（失败才 `logger.warn`）⇒ 该类路径在取证面不存在。
                // 本行把该通道纳入可审计面：记录**被删路径**（canonical，与包含校验同一
                // 参照系）与**来源会话**。语义边界（硬）：只证「本通道删过 X」，
                // **不指认**任何历史事件的责任人（责任者未证；见取证件 §1.4）。
                // ⚠ 只在路径**实存**时写（`existed`）：对已被删掉的路径，单删语义是
                // 「no-op 成功」，写「removed」会**误报**（审计面第一条纪律：不写没发生的事）。
                _ <-
                  if existed then
                    logger.info(WebSocketRoutes.deleteAuditLine("deletePath", canonicalBase, dpSessionId, wasDir))
                  else IO.unit
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
                (deleted, failed) <- WebSocketRoutes.deletePathsSafely(dpsPaths, os.Path(pr), dpsSessionId)
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
            // Optional `encoding:"base64"` — byte-preserving writes for binary
            // payloads (external file drag-in). Absent = plain text, the
            // editor-save path, unchanged.
            val wrEncoding = hc.downField("encoding").as[String].getOrElse("")
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
                  // Create parent dirs on demand so a drop-imported folder tree
                  // lands recursively (no separate mkdir round trip). No-op when
                  // the parent exists (editor-save path).
                  if !os.exists(basePath / os.up) then os.makeDir.all(basePath / os.up)
                  if wrEncoding == "base64" then
                    os.write.over(basePath, java.util.Base64.getDecoder.decode(wrContent))
                  else
                    os.write.over(basePath, wrContent)
                }
              yield basePath.toString)
                .flatMap { absPath =>
                  val desc =
                    if wrEncoding == "base64" then s"${wrContent.length} b64 chars"
                    else s"${wrContent.length} chars"
                  logger.info(s"File saved: $absPath ($desc)")
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
                      // 输出查看批（2026-09-09）：显式取消也是终态——输出留存区
                      // 翻转为 cancelled，详情卡回看「取消时刻为止」的全部输出。
                      // 幂等：若完成回调已先 finalize（竞态），此处 no-op。
                      (if cancelled then
                         nebflow.core.tools.BgTaskOutputStore
                           .finalizeTask(jobId, "cancelled", None, Some("Cancelled by user"))
                           .handleErrorWith(e => logger.warn(s"bg-output finalize (cancel) failed for job $jobId: ${e.getMessage}"))
                       else IO.unit) *>
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
                          // 权威分键（2026-09-05）：前端按 rootSessionId 分桶，回显
                          // 取消请求携带的会话（即前端桶键），保证移除帧落同一桶。
                          "rootSessionId" -> cancelSessionId.asJson,
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
                      "rootSessionId" -> cancelSessionId.asJson,
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

          case "getPendingAsks" =>
            // 多 AskUser 并发批（#250 第③项，2026-09-13 作者裁定「6 项全补」）：
            // **全局** pending-AskUser 快照 —— 前端重连时把待办条/badge 的本地镜像
            // 与 hub 权威一次性对齐（与「按会话订阅重放」解耦）。
            //
            // 旧口径缺口：前端 onReconnect 做全局清空（resetPendingAsks），后端重建
            // 却只在「某会话 getHistory 首帧」触发（replayPendingAsks，按会话订阅）
            // ⇒ 重连时活动会话 ≠ 承载卡片的 root 会话时，镜像清空后永不重建：
            // 待办条/badge 显示 0 而卡片还挂着 = 待办信号静默丢失。
            //
            // 读侧纪律与 replayPendingAsks 同族：只读快照、不触碰槽位。
            // 无静默路径核证：hub 未装配 ⇒ 回空快照（确定性结论，不是「无响应」）；
            // 查询失败 ⇒ 回 `failed:true` 帧（前端保留本地镜像并给可见提示），
            // 绝不假装「零 pending」把用户已有的待办清零。
            listAllPendingAsks(wsSend)

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
                // 刷新存活 (2026-09-03): after the initial history page of a
                // session (re)subscribe — browser refresh, WS reconnect
                // (frontend onReconnect re-fetches history) or session switch —
                // re-send the hub's still-pending AskUser cards. Ordered AFTER
                // historyPage on the same outbound queue so the replayed frames
                // are never wiped by the initial-load DOM clear. Pagination
                // fetches (beforeIndex set) prepend older messages and must not
                // trigger a replay. The hub is the single authority: an ask
                // answered before this point is not in the snapshot. The frames
                // carry the live requestId — the frontend dedups against the
                // history-restored (requestId-less) card and rebinds the answer
                // channel (#12 precise routing).
                .flatMap { _ =>
                  if beforeIndex.isEmpty then replayPendingAsks(sessionId, wsSend)
                  else IO.unit
                }
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
            // Retired 2026-09-06 (tool-face batch): agent.json tools write-back
            // is closed. Loud rejection (no silent no-op) so any stale client
            // sees the retirement instead of assuming the edit landed.
            val agentName = parse(text).toOption
              .flatMap(_.hcursor.downField("name").as[String].toOption)
              .getOrElse("")
            logger.warn(s"Rejected updateAgentTools for '$agentName' — write-back retired 2026-09-06 (stage 2d tool-face batch)")
            wsSend(
              io.circe.Json.obj(
                "type" -> "error".asJson,
                "message" -> s"updateAgentTools retired: per-agent tools are mechanism/plugin-managed since 2026-09-06; agent.json is no longer written from the panel".asJson
              )
            )

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
                // M4 落盘单点闸（MemoryWriteGate）：预算超硬顶 / 快照失败 = **结构化拒绝**，
                // 与 infra 失败**可判**区分 —— code 同码进日志与错误帧。拒绝不是 no-op，
                // 也不是静默跳过：前端拿到 error 帧（附 code），日志拿到同一码。
                val (code, detail) = e match
                  case r: nebflow.service.MemoryWriteGate.Rejected =>
                    (r.code, r.detail)
                  case other =>
                    ("SAVEMEMORY_FAILED", Option(other.getMessage).getOrElse(other.getClass.getSimpleName))
                // `logger.warn` 返回 IO —— 必须 *> 进链才会真的执行（旧写法把它当语句丢弃 ⇒
                // 该 WARN 从不落日志：一处既有的静默出口，随本批一并接上）。
                logger.warn(s"saveMemory error [$code]: $detail") *>
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "error".asJson,
                      "code" -> code.asJson,
                      "message" -> s"saveMemory failed: $detail".asJson
                    )
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
            // #29: 仓库 private 后 GH API 未认证不可用——版本检查读发布镜像
            // latest-version.txt（stable 通道，与旧 releases/latest 同语义）。
            // COS→OSS 切仓（2026-09-14）：桶名走 Branding.cosBucket 派生
            // （brand.conf 为唯一事实源，禁再硬编码桶名）；端点 = 阿里云 OSS 杭州。
            // hotupdate 批 1：读取与比对本体已抽到 VersionCheck（单一实现）——更新
            // 编排器的「检查」相位与本分支同源，禁第二套比对。帧形状逐字段不变。
            val result = nebflow.core.hotupdate.VersionCheck
              .fetchRaw()
              .flatMap {
                case Some(tag) =>
                  val latestVer = tag.stripPrefix("v")
                  val hasUpdate = nebflow.core.hotupdate.VersionCheck.hasUpdate(currentVer, latestVer)
                  wsSend(
                    io.circe.Json.obj(
                      "type" -> "updateCheckResult".asJson,
                      "currentVersion" -> currentVer.asJson,
                      "latestVersion" -> latestVer.asJson,
                      "hasUpdate" -> hasUpdate.asJson,
                      "releaseName" -> tag.asJson
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

          // ── 一键更新（设置页）：接统一更新编排器（hotupdate 批 1 · D3 · 闭 G1）。
          // 本批前：本段**只装不重启**（内联 curl|sh，整段零重启调用）⇒ 点完仍是旧版在跑。
          // 本批后：发**更新请求**给编排器 ⇒ 检查 → 准备 → 冻结 → 更新（安装动作本体
          // #26）→ 重启（**委托既有热重启编排器**）→ 恢复（后继进程自己走既有开机链）。
          // 向后兼容：既有 updateStarted / updateCompleted 两帧保留不删（安装相位回执经
          // onInstallOutcome 发出，形状与字段语义不变）；相位进度走统一 updateProgress 帧。
          // 确认位：沿用既有 restart 命令的强制位语义——缺 confirm 直接拒绝 + 可行动错误。
          case "doUpdate" =>
            val rc = parse(text).toOption.getOrElse(io.circe.Json.Null).hcursor
            val beta = rc.downField("beta").as[Boolean].getOrElse(false)
            val confirmed = rc.downField("confirm").as[Boolean].getOrElse(false)
            val idemKey = rc.downField("idempotencyKey").as[String].toOption
            sharedResources.updateOrchestrator match
              case None =>
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "updateCompleted".asJson,
                    "success" -> false.asJson,
                    "error" -> "update orchestrator is not available in this instance".asJson
                  )
                )
              case Some(orchestrator) =>
                val updateReq = nebflow.core.hotupdate.UpdateRequest(
                  source = nebflow.core.hotupdate.UpdateSource.Settings,
                  confirm = confirmed,
                  channel =
                    if beta then nebflow.core.hotupdate.UpdateChannel.Beta
                    else nebflow.core.hotupdate.UpdateChannel.Stable,
                  // 裁定 7：更新场景等待上限独立值 300s（界面重启命令那一处的 600s
                  // 默认本批零改动——两值互不影响，见 UpdateDefaults 注释）。
                  mode = nebflow.core.hotrestart.RestartMode.WaitIdle(
                    nebflow.core.hotupdate.UpdateDefaults.awaitIdleMs),
                  idempotencyKey = idemKey
                )
                // 安装相位回执 → 既有完成帧（向后兼容；失败分支的文案来自安装动作本体 #26）
                val onInstallOutcome: Either[String, String] => IO[Unit] =
                  case Right(_) =>
                    wsSend(
                      io.circe.Json.obj("type" -> "updateCompleted".asJson, "success" -> true.asJson))
                  case Left(err) =>
                    wsSend(
                      io.circe.Json.obj(
                        "type" -> "updateCompleted".asJson,
                        "success" -> false.asJson,
                        "error" -> err.asJson
                      ))
                orchestrator.request(updateReq, onInstallOutcome).flatMap { admission =>
                  nebflow.core.hotupdate.UpdateOrchestrator.admissionFrame(admission) match
                    case None =>
                      // 受理 → 既有开始帧（向后兼容）
                      wsSend(io.circe.Json.obj("type" -> "updateStarted".asJson))
                    case Some(frame) =>
                      // 已在途 / 更新中 / 拒绝 —— 立即回报（更新是排他动作、不排队）
                      wsSend(frame)
                }

          case "remoteUpdate" =>
            // hotupdate 批 3 · G8：请求可带**可选**幂等键 `clientRequestId`（界面设备行
            // 的触发点生成，见 resources/web/js/contacts.js）。语义（逐条）：
            //   · 缺席 / 空串 ⇒ 本分支的载荷与回帧与改前**逐字节相同**（老端路径不变）；
            //   · 带键 ⇒ 只在既有帧与既有 P2P 载荷上**加**一个字段（不新造消息类型、
            //     不改既有字段语义、不删既有字段——设计 §9:173 逐字），并在每条
            //     `remoteUpdateResult` 里**回显**该键，使界面能把结果对回它那一次点击。
            //   · 中继腿的隧道参数面保持 `{beta}`：动作 `RemoteUpdate` 的参数集由跨仓
            //     契约钉死（契约 §B.1.3），本批零越仓。
            val hc = parse(text).toOption.map(_.hcursor).getOrElse(io.circe.Json.Null.hcursor)
            val targetDevice = hc.downField("device").as[String].getOrElse("")
            val beta = hc.downField("beta").as[Boolean].getOrElse(false)
            val clientRequestId = hc.downField("clientRequestId").as[String].toOption
              .map(_.trim).filter(_.nonEmpty)

            /** `remoteUpdateResult` 的唯一构造点（本分支内单点）：既有字段原样 +
              * 带键时追加 `clientRequestId`（缺席时不追加 ⇒ 老端回帧形状不变）。 */
            def resultFrame(fields: (String, io.circe.Json)*): io.circe.Json =
              val all: Seq[(String, io.circe.Json)] =
                Seq("type" -> io.circe.Json.fromString("remoteUpdateResult")) ++ fields ++
                  clientRequestId.map(id => "clientRequestId" -> io.circe.Json.fromString(id))
              io.circe.Json.obj(all*)

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
                        wsSend(resultFrame(
                          "success" -> true.asJson,
                          "device" -> peer.deviceName.asJson,
                          "message" -> msg.asJson
                        ))
                      case Left(err) =>
                        wsSend(resultFrame(
                          "success" -> false.asJson,
                          "error" -> s"P2P: $p2pError; Relay: $err".asJson
                        ))
                    }
                case None =>
                  wsSend(resultFrame(
                    "success" -> false.asJson,
                    "error" -> p2pError.asJson
                  ))

            if targetDevice.isEmpty then
              wsSend(
                resultFrame(
                  "success" -> false.asJson,
                  "error" -> "Missing device name".asJson
                )
              )
            else
              sharedResources.neblinkService match
                case None =>
                  wsSend(
                    resultFrame(
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
                          resultFrame(
                            "success" -> false.asJson,
                            "error" -> s"Device '$targetDevice' not found".asJson
                          )
                        )
                      case Some(peer) =>
                        if peer.address.isEmpty then
                          wsSend(
                            resultFrame(
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
                              val fields = List("beta" -> beta.asJson)
                                ++ clientRequestId.map(id => "clientRequestId" -> id.asJson)
                              val body = io.circe.Json.obj(fields*).noSpaces
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
                                  resultFrame(
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
            // 附件腿批（2026-09-12）：`files: [{fileName,fileSize,mimeType}]` ⇒ 单条消息多件
            // （≤9）。旧单件字段（fileName/fileSize/mimeType）继续有效 —— 向后兼容。
            val batch = hc
              .downField("files")
              .as[List[io.circe.Json]]
              .getOrElse(Nil)
              .flatMap { j =>
                val c = j.hcursor
                c.downField("fileName").as[String].toOption.map { n =>
                  nebflow.dropbox.DropboxService.FileSpec(
                    n,
                    c.downField("fileSize").as[Long].getOrElse(0L),
                    c.downField("mimeType").as[String].getOrElse("")
                  )
                }
              }
            val specs =
              if batch.nonEmpty then batch
              else if fileName.nonEmpty then List(nebflow.dropbox.DropboxService.FileSpec(fileName, fileSize, mimeType))
              else Nil
            if deviceId.nonEmpty && specs.nonEmpty then
              sharedResources.dropboxService match
                case None =>
                  wsSend(io.circe.Json.obj("type" -> "dropboxError".asJson, "error" -> "Dropbox not enabled".asJson))
                case Some(svc) =>
                  svc
                    .offerFiles(deviceId, specs)
                    .flatMap {
                      case Right(_) => IO.unit
                      case Left(err) =>
                        // 超限 fail-fast + **回显实际值**：结构化错误体（code/actual/limit）直达前端。
                        wsSend(
                          io.circe.Json.obj(
                            "type" -> "dropboxError".asJson,
                            "deviceId" -> deviceId.asJson,
                            "error" -> err.message.asJson,
                            "errorDetail" -> err.toJson
                          )
                        )
                    }
                    .handleErrorWith(e =>
                      wsSend(io.circe.Json.obj("type" -> "dropboxError".asJson, "error" -> e.getMessage.asJson))
                    )
            else IO.unit

          case "dropbox-file-probe" =>
            val hc = parse(text).toOption.map(_.hcursor).getOrElse(io.circe.Json.Null.hcursor)
            val transferId = hc.downField("transferId").as[String].getOrElse("")
            if transferId.nonEmpty then
              sharedResources.dropboxService match
                case None => IO.unit
                case Some(svc) =>
                  svc.probeTransfer(transferId).flatMap {
                    case Right(state) =>
                      wsSend(
                        io.circe.Json.obj(
                          "type" -> "dropbox-file-probe".asJson,
                          "transferId" -> transferId.asJson,
                          "bytesReceived" -> state.bytesReceived.asJson,
                          "totalBytes" -> state.totalBytes.asJson,
                          "prefixSha256" -> state.prefixSha256.asJson
                        )
                      )
                    case Left(err) =>
                      wsSend(
                        io.circe.Json.obj(
                          "type" -> "dropboxError".asJson,
                          "transferId" -> transferId.asJson,
                          "error" -> err.message.asJson,
                          "errorDetail" -> err.toJson
                        )
                      )
                  }
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
                                // 输入框直通退役（2026-09-14 作者令）：typeless 帧不再探
                                // hub 的 pending AskUser 槽位 —— 文本一律按普通消息投
                                // AgentCommand.UserInput（= 引入直通之前的既有通道）。
                                // 曾以直通覆盖的「刷新后 busy 标志丢失」窗口（#43-domain）
                                // 随之回到功能前口径：pending 卡保持 pending、须点卡作答。
                                // ref/附件携带帧本就只走本通道，形态逐字节不变。
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
            else
              // fwdguard-impl (2026-09-17): 本准入谓词只数 `content`/`attachments`，
              // 而转发腿的载荷只走 `refs`（前端 input.js:672-673 又把 ref 剔出
              // attachments）⇒「转发后不附言直接发送」的帧在此被判为空帧丢弃。
              // 与 handleUserText 的 EMPTY 内容 WARN（:4309-4312）同族：那条只覆盖
              // immediateInput / userMessage 两条腿，本 typeless 腿此前**全静默**
              // （零日志零 turn，而前端已乐观置 busy ⇒ 会话永久转圈，且用户消息在
              // 所有台账里都不留痕）。此处只补**可观测性**：🔴 准入谓词与投递腿一字
              // 不改（作者 2026-09-17 Q1 取向：闸住前端，不放宽网关）。
              val frameHasRefsKey = json.hcursor.downField("refs").focus.isDefined
              logger.warn(
                "handleMessage(typeless): dropped frame with no content and no attachments " +
                  s"for session '$msgSessionId' — nothing dispatched " +
                  s"(frame was sent but carried no text; refs=$frameHasRefsKey, " +
                  s"clientMessageId=${clientMessageId.getOrElse("")})"
              ) *> IO.unit
            end if
      yield ()
      end for
    end if
  end handleMessage

  /** 热重启 draining 工作准入闸（hot-restart 批设计 §3.3，WS 侧 choke 点）：包裹
    * 工作型消息入口（userMessage / immediateInput——REST 的 handleMessagePublic
    * 同走本 handleMessage，故 REST 工作型路径一并覆盖）。draining 置位期间拒绝
    * 并回 workRefused 帧（503 + retryAfter 语义）；遗漏准入点的兜底 = 崩溃恢复
    * sweep（设计 R2）。非 draining 期间零开销旁路（行为与既有完全一致）。 */
  private def admitWorkOrRefuse(wsSend: io.circe.Json => IO[Unit])(cont: IO[Unit]): IO[Unit] =
    nebflow.core.hotrestart.HotRestart.admissionGate.flatMap {
      case Right(()) => cont
      case Left(reason) =>
        logger.warn(s"[hot-restart] work refused during draining: $reason") *>
          wsSend(
            io.circe.Json.obj(
              "type" -> "workRefused".asJson,
              "reason" -> reason.asJson,
              "retryAfterMs" -> 5000.asJson
            )
          )
    }

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
  /** @param fromUser
    *   ② (2026-09-11, queue-direct-pass diagnosis §2): is this text a real
    *   human message? Required (no default) so every entry point states its
    *   origin. 真人口径（Nebula 代裁，可被作者推翻）= WS 直投（浏览器
    *   `immediateInput` / 无 type 帧）+ CLI `userMessage`；REST headless
    *   (`rest-turn`) 算程序 ⇒ false。The flag is threaded into
    *   [[dispatchUserText]] → `AgentCommand.ImmediateInput(fromUser = …)` so the
    *   idle judgement (`clientMessageId.isDefined || fromUser`) keeps the
    *   human turn free of an injection source.
    */
  private def handleUserText(sessionId: String, content: String, source: String, fromUser: Boolean): IO[Unit] =
    if sessionId.nonEmpty && content.nonEmpty then
      // 输入框直通退役（2026-09-14 作者令）：输入框文本一律按普通消息投递 ——
      // 不再探 hub 的 pending AskUser 槽位，文本也不再成为卡片答案
      // （[[dispatchUserText]] = 唯一入队路径）。pending 卡保持 pending，
      // 只能在卡片上作答；headless REST (`rest-turn`) 与输入框同腿，形态不变。
      dispatchUserText(sessionId, content, source, fromUser)
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
    * agent's turn pipeline. The ONLY path that enqueues user text — the
    * input-box AskUser passthrough that used to bypass it was retired
    * 2026-09-14 (作者令「把 AskUserQuestion 通过输入框回答的功能关了」).
    *
    * ② (2026-09-11): `fromUser` rides along onto the ImmediateInput so the
    * agent can tell 真人文本 (WS immediateInput / CLI userMessage) apart from
    * server-side injections — without it the text lands in the
    * `clientMessageId=None ⇒ source="tool"` fallback (idle conversion) and
    * renders as a bogus TOOL card. `fromUser` is a required parameter so no
    * caller can silently inherit the wrong origin.
    */
  private def dispatchUserText(sessionId: String, content: String, source: String, fromUser: Boolean): IO[Unit] =
    logger.info(s"User text ($source) for session $sessionId (${content.length} chars)") *>
      sessionStore.appendUiMessages(
        sessionId,
        List(UiMessage.User(content, Nil, timestamp = System.currentTimeMillis()))
      ) *>
      // Hard-recovery P4: if the target turn is wedged (Processing + idle >
      // SessionKickIdleSec), break it BEFORE queueing — the queued message
      // injects at the turn boundary the kick creates (user intent first).
      maybeSessionKick(sessionId, s"userMessage:$source") *>
      ensureAgent(sessionId)(ref => ref ! AgentCommand.ImmediateInput(content, fromUser = fromUser))


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
    * REST-deterministic — this path and the WS input box share the same leg
    * ([[dispatchUserText]]); the AskUser passthrough probe they used to differ
    * on was retired 2026-09-14.
    */
  def dispatchHeadlessTurn(sessionId: String, content: String): IO[Unit] =
    // ② 口径（Nebula 代裁，可被作者推翻）：REST headless 是「程序」在投文本
    // （P0 benchmark / 外部脚本），不是人 ⇒ 显式 fromUser = false，保持既有
    // 注入来源标注（rest-turn 与输入框同腿：直落 dispatchUserText；输入框直通
    // 已于 2026-09-14 退役，此处不再有来源分叉）。
    handleUserText(sessionId, content, source = "rest-turn", fromUser = false)

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
      // Platform gate (P13): `mdfind` ships on macOS only — off macOS the
      // Spotlight fallback is a bounded no-op instead of a failing spawn.
      val output =
        if sys.props.getOrElse("os.name", "").toLowerCase.contains("mac") then
          scala.sys.process.Process(cmd).!!
        else ""
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
        // 案 B（双开缺陷批 2026-09-21，chain-askuserdup）：随行落盘 requestId ⇒ 历史
        // 恢复出的卡可 id 寻址（重放腿按 id 替换、askUserClosed 关卡可达）。旧行缺席
        // ⇒ None ⇒ 前端回落形态兜底去重腿（`chat.js sameAskCards` ②）。
        val askRid = hc.downField("requestId").as[String].toOption.filter(_.nonEmpty)
        sharedResources.sessionStore.appendUiMessages(sessionId, List(UiMessage.AskUser(items, askRid)))

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
        // bluebubble 批（2026-09-12）：注入行（injected:true）的落盘**已上收到唯一
        // 发射点** `AgentActor#emitInjectedUserEvent` —— 本层不再按帧嗅探重复落盘
        // （同一行会被写两次）。理由：本层只在 wsSend 恰为**录制 send** 时生效，
        // 而启动挂载的项目 engine.wsSendFn = 裸 wsHub.broadcast（GatewayMain.startupMount）
        // ⇒ 分发器/节点会话的注入气泡永不落盘（作者 2026-09-12 19:42「任务分发器看不到
        // 蓝气泡」的根因之一）。普通用户消息本就不经此处（WS message handler 单独落盘）。
        IO.unit

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
        // 2026-09-15 作者令：压缩不再落 report ⇒ 帧内不再带 reportPath，
        // 「(report: xxx.md)」尾巴随生成链一并删除（不留死读盘分支）。
        sharedResources.sessionStore.appendUiMessages(
          sessionId,
          List(
            UiMessage.System(
              s"Context compacted: $before → $after messages",
              Some("chat.compacted"),
              Some(io.circe.Json.obj("before" -> before.asJson, "after" -> after.asJson))
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
    // Flow-name routing removed 2026-09-06 (tool-face batch): flows are no
    // longer triggerable from skill activation (FlowTrigger retired). Names
    // resolve through the skill catalog only.
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

  // ============================================================
  // Callback helpers
  // ============================================================

  /** Extract token from query param (localStorage), Authorization header, or cookie.
    * Logic lives on the companion (pure, shared with nfFileRoutes); this
    * instance alias keeps every existing call site unchanged. */
  private def extractToken(req: org.http4s.Request[IO]): String =
    WebSocketRoutes.extractToken(req)

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

  /** 伴生对象侧 logger（#159/#176 wtsurv 批，2026-09-14）：`deletePathsSafely` 是
    * **纯核**（companion 成员，spec 直接静态调用），其**成功分支的审计留痕**必须
    * 在本层落笔 ⇒ 需要本层自己的 logger（与类侧 `nebflow.ws` 同名，日志面同源）。 */
  private val logger = nebflow.core.NebflowLogger.forName("nebflow.ws")

  /** UI 文件浏览器**删除成功分支**的审计行（#159/#176 ④「补盲区」）。
    *
    * **单一出处**：单删（WS `deletePath`）与批量删（`deletePathsSafely`）两处共用同一
    * 生成函数——措辞与字段集只定义一次，防两处漂移（`deletePath` / `deletePaths` 的
    * 排除理由在取证件 §1.2 机制 D-2 里是同一段代码）。字段 = **被删路径**（canonical，
    * 与包含校验同一参照系）+ **来源会话** + 形态（目录递归 / 单文件）+ 通道名。
    *
    * 语义边界（硬）：只证「本通道删过 X」，**不指认**任何历史事件的责任人（取证件
    * §1.4「直接删除者未证」）。
    *
    * `channel` 取 `"deletePath"` / `"deletePaths"`（与 WS case 名同字，便于按通道 grep）。 */
  private[gateway] def deleteAuditLine(
      channel: String,
      canonicalPath: String,
      sessionId: String,
      isDir: Boolean
  ): String =
    s"$channel: removed '$canonicalPath' (session=$sessionId, " +
      s"kind=${if isDir then "dir-recursive" else "file"}, channel=ui-file-explorer)"

  /** Extract token from query param (localStorage), Authorization header, or
    * cookie. Pure — shared by every authenticated route through the
    * class-side alias. */
  private[gateway] def extractToken(req: org.http4s.Request[IO]): String =
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

  /** Extension whitelist for GET /api/nf-file (local files served to
    * card/canvas iframes).
    *
    * 2026-09-03 Canvas interactive-HTML fix: beyond the media types it now
    * covers the text asset types a multi-file HTML deliverable references —
    * `js` (companion data/script modules), `mjs`, `css`, `json`. Without
    * them the Canvas HTML viewer renders the document shell, but the page's
    * own boot script dies on the first missing module, BEFORE binding any
    * event listeners — the reported "renders but nothing is clickable"
    * failure (prototype.html:455 `const SNAP = window.FM_SNAPSHOT` →
    * :487 seedFromSnapshot() throws on the 400'd script).
    *
    * Security posture unchanged: the route stays token-gated end to end, and
    * anything running inside the srcdoc canvas iframe already executes with
    * app-origin script privileges (viewers/html.js sandbox: allow-scripts +
    * allow-same-origin), so serving js/css/json does not widen the trust
    * boundary — content source remains locally opened, user-initiated
    * deliverables.
    *
    * 2026-09-17 nfext batch (author ruling, superseding the list above — not a
    * silent override): `doc`, `ppt`, `xls` joined. The ruling's standard is
    * "the device face behaves like the friend/group face": the friend/group
    * attachment leg (`GET /api/friends/attachments/{id}`, RestApiRoutes.scala)
    * carries NO extension gate and serves legacy Office files, while the device
    * face routes its attachment bytes through THIS route's ticket leg — so the
    * three legacy binary types were the one place the two faces disagreed.
    * `devattach-verify` open item ① measured the resulting false affordance:
    * a legacy `.doc` card binds its download key (the frontend criterion
    * `canFetchLocalBytes` resolves `.doc`/`.ppt`/`.xls` to the docx/pptx/xlsx
    * viewers, all of which are in `BLOB_ITEM_TYPES`) but the key's nf-file call
    * is refused 400 once — "the key is there and must fail". Closing that gap
    * means exactly these three entries here, on the endpoint that is allowed.
    *
    * Width of the change: the extension table is NOT a path constraint. It is
    * evaluated LAST, after expandTilde → lexical normalize → exists/isRegularFile
    * → toRealPath → the credential-namespace judge → the R2 hard-link inode
    * guard (see `nfFileVerdict`), and it only decides whether an already-fully
    * resolved real file's lowercased last-dot suffix is served. Adding a suffix
    * therefore grants no reach to any path that the six upstream judgements
    * refuse — it widens "which bytes an already-credentialed, already-path-
    * authorized requester may fetch" by three suffixes and nothing else.
    * Serving bytes executes nothing ON THIS ENDPOINT — the route transports bytes
    * and never parses them — and what the EXISTING viewers then do with a legacy
    * container was MEASURED rather than assumed (probe scripts + raw stdout under
    * `.nebflow/evidence/20260917_nfext/`): `.ppt` parses nothing at all (info card
    * + a click-time download link, `viewers/pptx.js`); `.doc` is refused by
    * mammoth with a visible parse error (CFB/OLE2 header → "Can't find end of
    * central directory : is this a zip file ?", caught at `viewers/docx.js:68` →
    * error panel) instead of being rendered; `.xls` goes down the SAME SheetJS leg
    * that already carried `.xlsx`/`.xlsm` before this batch. No preview leg
    * evaluates VBA, so a macro inside a legacy container is inert on this face.
    *
    * ⚠ Open item registered in the batch report (deliberately NOT fixed here —
    * the frontend tree is outside this batch's face): these two tables decide only
    * what may be FETCHED, whereas the `.xls` leg inherits a pre-existing property
    * of the vendored SheetJS 0.18.5 — `sheet_to_html` escapes a cell's TEXT but
    * not its `data-v` attribute, and `viewers/xlsx.js:50` assigns that string to
    * `pane.innerHTML` (a same-origin div, not a sandboxed iframe). It is reachable
    * TODAY with `.xlsx` alone (already on this list before the batch) and through
    * the friend/group attachment leg (which has no extension gate at all), so this
    * batch neither creates nor widens it. `html` stays excluded from the
    * attachment preview face.
    *
    * Pure + testable on purpose (same pattern as uploadsRoutes). */
  val NfFileAllowedExt: Set[String] = Set(
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
    "doc",
    "docx",
    "xls",
    "xlsx",
    "xlsm",
    "ppt",
    "pptx",
    "epub",
    "js",
    "mjs",
    "css",
    "json"
  )

  // ────────────────────────────────────────────────────────────────────────
  // C1 — credential-namespace refusal (R1 = O-A: default-deny + allowlist)
  //
  // Before this batch the read endpoint confined NO root: credential check +
  // extension whitelist only. `?path=~/.nebflow/auth.json` (extension `json`,
  // on the whitelist) served the gateway token itself, and any extension-
  // whitelisted file under `~/.ssh` / `~/.aws` / `~/.docker` was fair game.
  //
  // Policy (R1 = O-A), applied to the *realpath* (C1-1), so symlinks and
  // `..` chains cannot dodge it:
  //   P1 = PathUtil.dataRoot (the data root; `overrideRoot` respected — never
  //        a hardcoded `~/.nebflow`)   → default deny, allowlist below
  //   P2 = home credential entries      → default deny (whole subtree)
  //   P3 = <workspace>/.nebflow         → default deny, allowlist below
  //   outside P1..P3                    → pattern reject only (mode B), so
  //        /tmp/... and project files stay readable (CardTool's documented
  //        "you MUST use absolute paths" contract)
  //   hits → 403 + actionable `reason` (never a 404 disguise)
  //
  // R2 (hard link aliasing) rides on top: (dev,ino) of the known credential
  // files is captured once at startup — realpath cannot see a hard link.
  // ────────────────────────────────────────────────────────────────────────

  /** A1 — the ONLY `PathUtil.dataRoot` subtrees whose contents may be served.
    *
    * Every other entry under the data root is credential-bearing by default:
    * `auth.json` (the gateway token), `nebflow.json`, `secrets/`, `logs/`,
    * `sessions/`, `usage-records/`, … Fail-closed: a new directory added
    * under the data root is refused until it is listed here.
    *
    * 2026-09-16 (img-ticket batch i, #687-A, author ruling — already ruled, not
    * pending): `docs` joins the list. The author's human-deliverable directory
    * (`~/.nebflow/docs/…`) was the one location the delivery convention told
    * people to write to AND this judge refused with `credential-path`; the
    * ruling resolves that contradiction. 🔴 Scope is EXACTLY this one entry:
    * no other subtree, no prefix/glob matching, no change to the `head`
    * exact-match semantics below, and the five shipped entries keep their order
    * and meaning. The ruling explicitly SUPERSEDES the 2026-09-11 "do not widen
    * the credential namespace" ruling — and that supersession is per-item, so
    * it authorizes nothing beyond `docs`. The tool face reads
    * `FileRefs.DataRootServedNamespaces`, which `FileRefsWhitelistSpec` welds to
    * this list item for item. */
  val NfDataRootAllowlist: List[String] =
    List("projects", "uploads", "plots", "workspace-items", "voice-models", "docs")

  /** A2 — the ONLY `<workspace>/.nebflow` subtrees whose contents may be served
    * (evidence capture directories: screenshots/logs a node produced). */
  val NfWorkspaceAllowlistPrefix: String = "evidence"

  /** P2 — home credential entries (dirs and files) that are refused wholesale. */
  val NfExternalCredentialEntries: List[String] =
    List(".ssh", ".aws", ".gnupg", ".config/gh", ".docker", ".kube", ".netrc", ".git-credentials")

  /** B — pattern reject for paths OUTSIDE the protected namespaces.
    *
    * Mode B is deliberately narrow (basename-shaped), because it must not
    * misfire on ordinary project material: a `/tmp/output.svg` or
    * `/Users/x/project/plot.png` stays readable. It catches the two shapes a
    * credential file reliably has — a private-key/env basename, or a
    * credential-holding directory anywhere in the path. */
  val NfCredentialNamePattern: scala.util.matching.Regex =
    """(?i)^(\.?(env|netrc|git-credentials|npmrc|pypirc|pgpass)|id_(rsa|dsa|ecdsa|ed25519)(\.pub)?|auth\.json|credentials(\.json|\.yaml|\.yml|\.txt)?|secrets?(\.json|\.yaml|\.yml|\.txt)?|token|token\.json|.*\.(pem|key|p12|pfx|keystore|jks))$""".r

  /** B — credential-holding directory segments (matched at any depth). */
  val NfCredentialPathSegments: Set[String] =
    Set(".ssh", ".aws", ".gnupg", ".kube", ".docker", ".git-credentials")

  /** The filesystem coordinates the C1 verdict is computed against.
    *
    * `dataRoot` = P1 (`PathUtil.dataRoot`); `workspaceRoot` = P3 = the project's
    * OWN `<workspace>/.nebflow` directory (R1), whose only allowlisted subtree
    * is the `evidence*` subtree; `credentialInodes` = the R2 `(dev,ino)` snapshot.
    *
    * Injectable so the route stays instance-free and unit-testable with a
    * synthesized data root / workspace root / inode set
    * (`NfFileRoutesSpec` / `NfTicketRoutesSpec`), and so the (dev,ino) scan
    * happens exactly once per JVM in production. */
  final case class NfPathPolicy(
    dataRoot: java.nio.file.Path,
    workspaceRoot: java.nio.file.Path,
    credentialInodes: Set[String]
  )

  object NfPathPolicy:

    /** Canonicalize if the directory exists (macOS `/var` → `/private/var`,
      * symlinked data roots), else keep the normalized absolute form. */
    private[gateway] def canonicalOrSelf(p: java.nio.file.Path): java.nio.file.Path =
      try p.toRealPath()
      catch case _: Throwable => p.toAbsolutePath.normalize()

    /** `fileKey().toString()` is the JVM's portable identity for a file —
      * on POSIX it is `(dev=…,ino=…)`, exactly the R2 key. */
    private[gateway] def inodeKey(p: java.nio.file.Path): Option[String] =
      try
        val attrs =
          java.nio.file.Files.readAttributes(p, classOf[java.nio.file.attribute.BasicFileAttributes])
        Option(attrs.fileKey()).map(_.toString)
      catch case _: Throwable => None

    /** Every file under `dir` (depth-capped), best effort. */
    private def filesUnder(dir: java.nio.file.Path, maxDepth: Int): List[java.nio.file.Path] =
      if !java.nio.file.Files.isDirectory(dir) then Nil
      else
        val out = scala.collection.mutable.ListBuffer.empty[java.nio.file.Path]
        try
          val stream = java.nio.file.Files.walk(dir, maxDepth)
          try
            stream.forEach { p =>
              if java.nio.file.Files.isRegularFile(p) then out += p
            }
          finally stream.close()
        catch case _: Throwable => ()
        out.toList

    /** R2: (dev,ino) of the known credential files — data-root config/auth
      * files, everything under `secrets/`, and the whole `~/.ssh` tree. Small
      * by construction (single digits to tens of files). */
    private[gateway] def scanCredentialInodes(dataRoot: java.nio.file.Path): Set[String] =
      val home = java.nio.file.Paths.get(sys.props.getOrElse("user.home", "/"))
      val seeds: List[java.nio.file.Path] =
        List(dataRoot.resolve("auth.json"), dataRoot.resolve("nebflow.json")) ++
          filesUnder(dataRoot.resolve("secrets"), 8) ++
          filesUnder(home.resolve(".ssh"), 8)
      seeds.flatMap(inodeKey).toSet

    /** Production policy: the real data root, the PROCESS WORKSPACE's own
      * `.nebflow` directory (= P3, R1: "项目内 `<workspace>/.nebflow`"), and the
      * startup inode snapshot.
      *
      * The workspace namespace is the project's `.nebflow` dir, NOT the
      * workspace/repo root — rooting it at the root classifies every file of
      * the checkout as "the project .nebflow directory" and then denies it
      * (only the `evidence*` subtree is allowlisted), which 403'd every repo file until
      * `tests/smoke.spec.mjs` caught it end to end (2026-09-11, real backend).
      * Rooted at `.nebflow`, repo files fall through to the pattern rules
      * (allowed unless credential-shaped) exactly as R1's P3 intends. */
    def standard(): NfPathPolicy =
      val root = canonicalOrSelf(java.nio.file.Paths.get(PathUtil.dataRoot.toString))
      val workspace = canonicalOrSelf(java.nio.file.Paths.get(os.pwd.toString).resolve(".nebflow"))
      NfPathPolicy(root, workspace, scanCredentialInodes(root))

    /** Memoized so the inode scan is a startup cost, not a per-request one. */
    private lazy val standardMemo: NfPathPolicy = standard()

    def memoized(): NfPathPolicy = standardMemo

    /** The policy for the roots that are in force **now**, memoized per root pair
      * (imgref rework r2, 2026-09-18 — the verifier's F1 ②).
      *
      * [[memoized]] freezes the policy at the FIRST call in this JVM. That is the
      * right shape for a process that has exactly one data root for its whole
      * lifetime (production: the root is resolved from `--home` / the environment
      * before the routes are built), but it makes the answer to "would the
      * endpoint serve this path?" depend on **process history** rather than on the
      * file and the roots: a JVM that changes its data root (`PathUtil.setDataRoot`
      * — the test/`--home` redirection every other component follows by using a
      * `def` instead of a `val`, e.g. `paths.scala:223`) would keep judging new
      * files against the OLD root. That is exactly the drift that made
      * `CardModelFaceSpec` green in one run order and red in another.
      *
      * Semantics: re-derive when (and only when) `PathUtil.dataRoot` / the process
      * working directory differ from the pair the cached policy was derived from;
      * the `(dev,ino)` scan therefore stays a once-per-root cost, never a
      * per-call one. Two roots seen in one JVM yield two policies — never one
      * policy wearing the other's roots. */
    private val perRootMemo = new java.util.concurrent.ConcurrentHashMap[String, NfPathPolicy]()

    def current(): NfPathPolicy =
      val key = s"${PathUtil.dataRoot.toString}\u0000${os.pwd.toString}"
      perRootMemo.computeIfAbsent(key, _ => standard())

  /** Verdict of the shared path judge (C1-5) — the read endpoint and the
    * signing endpoint ask the SAME question through this one function, so a
    * path that cannot be read can never be ticketed. */
  enum NfVerdict:
    case Allowed(realPath: java.nio.file.Path, ext: String)
    case Denied(status: Status, reason: String, message: String)

  /** Which layer of the C1 ladder refused a path (imgref rework r2, 2026-09-18).
    *
    * The layers answer DIFFERENT questions, and exactly one caller has to tell
    * them apart (the tool-side inline leg — see `FileRefs.inlineMayTakeOver`):
    *
    *   - `Namespace` = "the endpoint only serves some subtrees of the data
    *     directory / of the project `.nebflow`". A statement about the
    *     ENDPOINT's REACH: meaningful only to a caller that asks `/api/nf-file`
    *     for bytes.
    *   - `Credential` = "this path's own IDENTITY is a credential" — a known
    *     credential entry (`NfExternalCredentialEntries`), a credential-holding
    *     directory (`NfCredentialPathSegments`) or a credential-shaped basename
    *     (`NfCredentialNamePattern`). A statement about the FILE: meaningful to
    *     any caller that would copy its bytes somewhere else.
    *   - `CredentialInode` = R2: a hard link to a credential file's inode.
    *   - `FileType` = the extension of the REAL path is not served.
    *
    * 🔴 This enum only NAMES a decision the shipped ladder already made: the
    * tables, the branch order and every message are byte-identical to the
    * pre-rework judge ([[nfCredentialDeny]] is this function's projection). */
  enum NfDenyLayer:
    case Namespace, Credential, CredentialInode, FileType

  /** C1-4: pure credential-namespace judge. `Some(reason)` = refuse with 403.
    *
    * Projection of [[nfCredentialDenyLayer]] (same branches, same order, same
    * messages) — the two can never disagree. */
  def nfCredentialDeny(realPath: java.nio.file.Path, policy: NfPathPolicy): Option[String] =
    nfCredentialDenyLayer(realPath, policy).map(_._2)

  /** [[nfCredentialDeny]] with the refusing layer named (see [[NfDenyLayer]]). */
  def nfCredentialDenyLayer(
      realPath: java.nio.file.Path,
      policy: NfPathPolicy
  ): Option[(NfDenyLayer, String)] =
    val rp = realPath.toAbsolutePath.normalize()
    val p1 = policy.dataRoot
    val p3 = policy.workspaceRoot
    def relUnder(root: java.nio.file.Path): String =
      root
        .relativize(rp)
        .toString
        .replace('\\', '/')
    if rp.startsWith(p1) then
      val rel = relUnder(p1)
      val head = rel.split('/').headOption.getOrElse("")
      if NfDataRootAllowlist.contains(head) then None
      else
        Some(
          NfDenyLayer.Namespace,
          s"the Nebflow data directory is credential-bearing; only " +
            s"${NfDataRootAllowlist.mkString("/**, ", "/**, ", "/**")} may be served"
        )
    else if rp.startsWith(p3) then
      val rel = relUnder(p3)
      val head = rel.split('/').headOption.getOrElse("")
      if head.startsWith(NfWorkspaceAllowlistPrefix) then None
      else
        Some(
          NfDenyLayer.Namespace,
          s"the project .nebflow directory is credential-bearing; only " +
            s"${NfWorkspaceAllowlistPrefix}*/** may be served"
        )
    else
      val home = java.nio.file.Paths.get(sys.props.getOrElse("user.home", "/")).toAbsolutePath.normalize()
      val homeRel =
        if rp.startsWith(home) then Some(home.relativize(rp).toString.replace('\\', '/')) else None
      val externalHit = homeRel.exists { rel =>
        val norm = rel.stripPrefix("./")
        NfExternalCredentialEntries.exists(entry => norm == entry || norm.startsWith(entry + "/"))
      }
      if externalHit then Some((NfDenyLayer.Credential, "this path is a known credential location"))
      else
        val segments = (0 until rp.getNameCount).map(i => rp.getName(i).toString).toArray
        val basename = Option(rp.getFileName).map(_.toString).getOrElse("")
        if segments.exists(NfCredentialPathSegments.contains) then
          Some((NfDenyLayer.Credential, "this path traverses a credential directory"))
        else if NfCredentialNamePattern.matches(basename) then
          Some((NfDenyLayer.Credential, "this filename is a known credential shape"))
        else None

  /** C1-5: the single authority for "may this raw path be served?".
    *
    * Chain (plan §3.3): empty → `~` expansion → lexical normalize →
    * exists/isRegularFile (404) → toRealPath (404) → R2 hard-link inode (403)
    * → `nfCredentialDeny` (403) → extension taken from the REAL path (400).
    * The extension verdict deliberately uses the realpath: a client can no
    * longer pick the served extension by naming a symlink (C1-2). */
  def nfFileVerdict(
    rawPath: String,
    policy: NfPathPolicy
  ): IO[NfVerdict] =
    if rawPath.trim.isEmpty then
      IO.pure(NfVerdict.Denied(Status.BadRequest, "missing-path", "Missing 'path' parameter"))
    else
      IO.blocking {
        val expanded = PathUtil.expandTilde(rawPath)
        val lexical = java.nio.file.Paths.get(expanded).normalize()
        if !java.nio.file.Files.exists(lexical) || !java.nio.file.Files.isRegularFile(lexical) then
          NfVerdict.Denied(
            Status.NotFound,
            "not-found",
            s"no regular file at $lexical"
          )
        else
          val realTry =
            try Right(lexical.toRealPath())
            catch case e: Throwable => Left(e)
          realTry match
            case Left(e) =>
              NfVerdict.Denied(Status.NotFound, "not-found", s"path could not be resolved: ${e.getMessage}")
            case Right(real) =>
              nfVerdictForReal(real, policy) match
                case Some(d) => d
                case None =>
                  val ext = nfRealExtension(real)
                  NfVerdict.Allowed(real, ext)
      }

  /** The extension a real path is served under — taken from the REAL path, never
    * from the name the client wrote (C1-2: a client can no longer pick the
    * served extension by naming a symlink). */
  private def nfRealExtension(real: java.nio.file.Path): String =
    val name = real.toString
    name.lastIndexOf('.') match
      case -1 => ""
      case i  => name.substring(i + 1).toLowerCase

  /** R2 as a question a caller can ask on its own: `true` = this real path IS
    * one of the policy's credential inodes (a credential file, or a hard link
    * that shares its inode). Public (imgref rework r2) because the endpoint's
    * ladder SHORT-CIRCUITS at the credential step — a namespace refusal can hide
    * a credential inode — and a caller that honours only some layers must be able
    * to ask the inode layer directly instead of re-implementing it. Same snapshot,
    * same helper: one judgement, no copy. */
  def nfCredentialInode(real: java.nio.file.Path, policy: NfPathPolicy): Boolean =
    NfPathPolicy.inodeKey(real).exists(policy.credentialInodes.contains)

  /** The post-`toRealPath` half of [[nfFileVerdict]] — every step that decides on
    * the REAL path, in the shipped order: `nfCredentialDeny` (403 credential-path)
    * → R2 hard-link inode (403 credential-hardlink) → extension from the realpath
    * (400 file-type). `None` = the endpoint would SERVE this real path.
    *
    * Extracted verbatim (imgref rework r1, 2026-09-18) so that the tool-side
    * pre-flight gate (`FileRefs.servableByEndpoint`) and the endpoint ask EXACTLY
    * the same question by calling the same function. Before this extraction the
    * gate reused only `nfCredentialDeny` and therefore went green on a hard link
    * to a credential file and on a symlink whose realpath extension is not
    * served — both of which the endpoint refuses (`proxied` green while the
    * browser's fetch answered 401: the author's failure ② shape). One function,
    * no copy, no parallel judge.
    *
    * Order is part of the contract: a credential file named directly (or reached
    * through a symlink) must report `credential-path`, not the alias-specific
    * `credential-hardlink`.
    *
    * 🔴 Short-circuit (read this before relying on the layer): the credential
    * step runs FIRST, so a path that is refused for the namespace reason can also
    * be a hard link to a credential file without the inode step ever being asked
    * — a caller that honours only some layers must ask the inode layer itself
    * (see `FileRefs.credentialInodeClean`). */
  def nfVerdictForReal(real: java.nio.file.Path, policy: NfPathPolicy): Option[NfVerdict.Denied] =
    nfVerdictForRealLayer(real, policy).map(_._2)

  /** [[nfVerdictForReal]] with the refusing layer named — same order, same
    * reasons, same messages (the entry point above is this function's
    * projection). Extracted in the imgref rework r2 so that a caller which must
    * honour only SOME layers (the inline `data:` leg honours the identity layers
    * and not the reach layer — see `FileRefs.inlineMayTakeOver`) reads the layer
    * from the single source instead of re-implementing the ladder. */
  def nfVerdictForRealLayer(
      real: java.nio.file.Path,
      policy: NfPathPolicy
  ): Option[(NfDenyLayer, NfVerdict.Denied)] =
    nfCredentialDenyLayer(real, policy) match
      case Some((layer, reason)) =>
        Some((layer, NfVerdict.Denied(Status.Forbidden, "credential-path", reason)))
      case None =>
        if nfCredentialInode(real, policy) then
          Some(
            (
              NfDenyLayer.CredentialInode,
              NfVerdict.Denied(
                Status.Forbidden,
                "credential-hardlink",
                "this file is a hard link to a Nebflow credential file"
              )
            )
          )
        else if !NfFileAllowedExt.contains(nfRealExtension(real)) then
          Some((NfDenyLayer.FileType, NfVerdict.Denied(Status.BadRequest, "file-type", "File type not allowed")))
        else None

  /** The URL-form-tolerant facade over [[nfFileVerdict]] (imgref batch,
    * 2026-09-18 作者令).
    *
    * 作者实证：路径含空格时 ① `%20`/`+` 编码形态被按**字面**去找 ⇒ `not-found`；
    * ② 工具回包计数绿而前端取回腿红 —— 因为工具发的是 `URLEncoder` 的 form 形态
    * （空格 → `+`），而**取回腿**把 `+` 当成字面加号去找一个不存在的文件。
    *
    * 判据：候选形态按 [[nebflow.core.PathParamCodec.candidates]] 的次序（原样 →
    * `%`-解码 → `+`-折成空格）逐个过**同一个** [[nfFileVerdict]]；只有 `not-found`
    * 才落到下一个形态 —— 一个真的存在的文件（包括文件名里真带 `+` 的）永远在原样
    * 形态就命中，所以既有全绿面逐字不动。
    *
    * 🔴 **权限面零让步**：每个候选形态过的是同一份 `nfFileVerdict`（词法归一 →
    * exists/isRegularFile → toRealPath → R2 inode → credential namespace → realpath
    * 上的扩展名），判据全作用在 **realpath** 上 ⇒ 变形形态与直接写入的形态得到同一个
    * realpath、同一份判据，造不出「原串判不住、变形后判得住」的穿透。策略表
    * （`NfDataRootAllowlist` / `NfWorkspaceAllowlistPrefix` / `NfExternalCredentialEntries`
    * / `NfCredentialPathSegments` / `NfCredentialNamePattern`）**本批零改动**。
    *
    * 票据腿同用此函数（`POST /api/nf-ticket` 与 `GET /api/nf-file` 一条口径），所以
    * 铸票用的 realpath 与取回时解出的 realpath 必然一致。 */
  def nfFileVerdictTolerant(rawPath: String, policy: NfPathPolicy): IO[NfVerdict] =
    def go(rest: List[String], firstNotFound: Option[NfVerdict]): IO[NfVerdict] =
      rest match
        case Nil =>
          IO.pure(
            firstNotFound
              .getOrElse(NfVerdict.Denied(Status.BadRequest, "missing-path", "Missing 'path' parameter"))
          )
        case form :: tail =>
          nfFileVerdict(form, policy).flatMap {
            // Only "the path does not exist" falls through to the next form. A
            // refusal (credential-path / credential-hardlink / file-type) is
            // final — decoding must never shop for a form that gets past a
            // judgement the raw form already failed.
            case d @ NfVerdict.Denied(_, "not-found", _) if tail.nonEmpty => go(tail, firstNotFound.orElse(Some(d)))
            case v                                                        => IO.pure(v)
          }
    go(nebflow.core.PathParamCodec.candidates(rawPath), None)

  /** Plain-text response builder.
    *
    * Deliberately NOT `Response.withEntity(String)` / the dsl generators:
    * this file imports `org.http4s.circe.CirceEntityCodec.*`, whose
    * `circeEntityEncoder[String]` (circe has an `Encoder[String]`) takes
    * precedence over http4s' own text encoder, so `withEntity("prose")` emits
    * a JSON-quoted string (`"prose"`). That is invisible to a status-only
    * assertion but wrong for a diagnostic body — the reason text is meant to
    * be read by a human and grepped by a spec. */
  private def nfText(status: Status, body: String): Response[IO] =
    Response[IO](status)
      .putHeaders(`Content-Type`(MediaType.text.plain, Charset.`UTF-8`))
      .withBodyStream(fs2.Stream.emit(body).through(fs2.text.utf8.encode))

  /** Render a refusal. The `reason` rides in both the body and a header so
    * clients (and specs) can branch without parsing prose. */
  private def nfDenied(resp: NfVerdict.Denied): Response[IO] =
    nfText(resp.status, s"${resp.reason}: ${resp.message}")
      .putHeaders(org.http4s.Header.Raw(org.typelevel.ci.CIString("X-Nf-Reason"), resp.reason))

  /** Serves whitelisted local files from disk for card iframes
    * (GET /api/nf-file?path=xxx&ticket=xxx). Supports Range requests for
    * video seeking. Standalone (zero class deps) so it is directly
    * unit-testable (NfFileRoutesSpec); composed ahead of the instance
    * routes like uploadsRoutes.
    *
    * 2026-09-11 (C batch, R5 = ticket-only): the global gateway token leg is
    * GONE — a permanent, path-agnostic credential is exactly what this batch
    * removes. A request must carry a ticket minted by `POST /api/nf-ticket`
    * for this precise realpath. Missing ticket → 401; unknown/expired/
    * mismatched → 403 with `reason`. The route body has no `handleErrorWith`
    * (it used to be three total functions), so the new resolution step is an
    * explicit `IO.blocking` + in-function try — never a throwing lambda. */
  def nfFileRoutes(
    token: String,
    store: NfTicketStore,
    policy: NfPathPolicy = NfPathPolicy.memoized()
  ): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "nf-file" =>
      val rawPath = req.params.get("path").getOrElse("")
      val ticket = req.params.get("ticket").getOrElse("")
      if rawPath.isEmpty then BadRequest("Missing 'path' parameter")
      else if ticket.isEmpty then
        // Not the DSL `Unauthorized`: that constructor demands a
        // WWW-Authenticate challenge, and this route has no auth scheme to
        // advertise (the ticket is an opaque bearer value, not Basic/Digest).
        IO.pure(nfText(Status.Unauthorized, "Missing 'ticket' parameter"))
      else
        nfFileVerdictTolerant(rawPath, policy).flatMap {
          case denied: NfVerdict.Denied => IO.pure(nfDenied(denied))
          case NfVerdict.Allowed(real, _) =>
            store.verifyAndConsume(ticket, real.toString).flatMap {
              case Left(reason) =>
                IO.pure(nfDenied(NfVerdict.Denied(Status.Forbidden, reason, "ticket rejected")))
              case Right(_) =>
                StaticFile
                  .fromPath(fs2.io.file.Path(real.toString), Some(req))
                  .getOrElseF(NotFound())
            }
        }
  }

  /** C2-2: `POST /api/nf-ticket` — mint short-lived, per-path read tickets.
    *
    * Body `{sessionId, paths[]}`. Every path goes through the SAME verdict as
    * the read endpoint (C1-5), so an unreadable path can never be ticketed;
    * rejections come back in `rejected[]` with their `reason` instead of
    * failing the whole batch (the frontend degrades per path — §4.3 F4).
    *
    * Auth: the ordinary gateway token through `extractToken` (cookie first,
    * Bearer second, `?token=` last). The frontend sends no explicit
    * credential — the cookie rides along on the same-origin fetch.
    */
  def nfTicketRoutes(
    token: String,
    store: NfTicketStore,
    policy: NfPathPolicy = NfPathPolicy.memoized()
  ): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "nf-ticket" =>
      if !Auth.validateToken(extractToken(req), token) then Forbidden("Invalid token")
      else
        req.as[Json].attempt.flatMap {
          case Left(e) =>
            BadRequest(s"malformed ticket request body: ${e.getMessage}")
          case Right(body) =>
            val sessionId = body.hcursor.downField("sessionId").as[String].getOrElse("")
            val paths = body.hcursor
              .downField("paths")
              .as[List[String]]
              .getOrElse(Nil)
              .filter(_.nonEmpty)
              .distinct
            // Re-read `nfFile.ticketTtlSeconds` (throttled + mtime-gated) so a
            // config edit takes effect without a restart (R3).
            store.refreshTtlFromConfig() *>
              paths
                .traverse { p =>
                  nfFileVerdictTolerant(p, policy).flatMap {
                    case NfVerdict.Allowed(real, _) =>
                      store.issue(sessionId, real.toString).map { issued =>
                        Right(
                          p,
                          Json.obj(
                            "t" -> issued.token.asJson,
                            "exp" -> issued.expiresAt.asJson,
                            "uses" -> issued.remaining.asJson
                          )
                        )
                      }
                    case denied: NfVerdict.Denied =>
                      IO.pure(Left(p, Json.obj("path" -> p.asJson, "reason" -> denied.reason.asJson)))
                  }
                }
                .map { results =>
                  val tickets = results.collect { case Right((p, j)) => p -> j }
                  val rejected = results.collect { case Left((_, j)) => j }
                  Json.obj(
                    "tickets" -> Json.obj(tickets*),
                    "rejected" -> Json.arr(rejected*)
                  )
                }
                .flatMap(Ok(_))
        }
  }

  /** R9 = O-A: `GET /api/nf-authcheck` — the cookie-reachability probe target.
    *
    * Replaces the old "bare `/api/nf-file` → 400 = cookie works" signal, which
    * the ticket leg turns into 401 (a status that overlaps the genuine
    * failure case, so the old probe could no longer distinguish anything).
    * This route touches ZERO disk and has no other side effect: 204 when the
    * presented credential validates (cookie / Bearer / `?token=` all through
    * `extractToken`), 403 otherwise. */
  def nfAuthcheckRoutes(token: String): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "nf-authcheck" =>
      if Auth.validateToken(extractToken(req), token) then NoContent()
      else Forbidden("Invalid token")
  }

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
      root: os.Path,
      /** 来源会话（审计用；#159/#176 wtsurv 批）。默认空串 ⇒ 既有调用点（含
        * `BatchDeleteSpec` 四例）零改动。 */
      sessionId: String = ""
  ): IO[(List[String], List[(String, String)])] =
    paths.foldLeftM((List.empty[String], List.empty[(String, String)])) { (acc, p) =>
      resolveGuardedForDelete(p, root) match
        case Left(err) => IO.pure((acc._1, acc._2 :+ (p -> err)))
        case Right(basePath) =>
          // remove.all: batch delete from the file explorer explicitly covers
          // non-empty directories (F1 acceptance), unlike the single-file
          // deletePath case.
          for
            wasDir <- IO.blocking { os.isDir(basePath) }
            existed <- IO.blocking { os.exists(basePath) }
            res <- IO.blocking { if existed then os.remove.all(basePath) }.attempt
            out <- res match
              // ── 补盲区（同 `deletePath`）：批量删除的**成功分支**留痕 ──────────
              // 同一 `os.remove.all` 语义、同一「目录消失 + 注册残留 = prunable」签名
              // （取证件 §1.2 机制 D-2 / §6.1 第 2 条）。失败分支沿用原「进 failed」
              // 语义，**不误报**成功（本行只在 `Right` 分支执行）；**且**只在路径
              // **实存**时写——不存在的路径是「no-op 成功」（既有语义，见
              // `BatchDeleteSpec`），写 removed 会误报。
              case Right(_) =>
                val canonical = try basePath.toIO.getCanonicalPath catch case _: Throwable => basePath.toString
                val audit =
                  if existed then
                    logger.info(WebSocketRoutes.deleteAuditLine("deletePaths", canonical, sessionId, wasDir))
                  else IO.unit
                audit.as((acc._1 :+ p, acc._2))
              case Left(e) => IO.pure((acc._1, acc._2 :+ (p -> Option(e.getMessage).getOrElse(e.toString))))
          yield out
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
   * Explorer listDir — pure, testable listing core with per-entry fault
   * isolation (2026-09-02). The bare os.isDir/os.size calls used to throw out
   * of the whole listing when a directory contained a dangling symlink (e.g.
   * project .nebflow/flowmap-anim → worktrees/flowmap-anim whose target is
   * gone): one bad entry produced dirListing{error} and the frontend rendered
   * the directory as un-openable. Now each entry stats independently — a
   * stat failure degrades THAT entry to type=file, size=0, broken=true
   * (readFile on it already fails gracefully via fileContent{error}; the
   * extra `broken` field is optional and today's frontend simply ignores it).
   * os.isDir keeps its followLinks semantics; dirs sort first, then name —
   * ordering identical to the pre-fix implementation.
   */
  private[gateway] def listDirEntries(basePath: os.Path): Seq[Json] =
    if os.exists(basePath) && os.isDir(basePath) then
      os.list(basePath)
        .map { p =>
          val stat = scala.util.Try {
            val isDir = os.isDir(p)
            if isDir then (isDir, 0L) else (isDir, os.size(p))
          }.toOption
          stat match
            case Some((isDir, size)) => (p.last, isDir, size, false)
            case None                => (p.last, false, 0L, true)
        }
        .sortBy { case (name, isDir, _, _) => (if isDir then 0 else 1, name.toLowerCase) }
        .map { case (name, isDir, size, broken) =>
          io.circe.Json.obj(
            "name"   -> name.asJson,
            "type"   -> (if isDir then "dir" else "file").asJson,
            "size"   -> size.asJson,
            "broken" -> broken.asJson
          )
        }
    else Nil

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
    * `domain` carries the default value (nebflow.space); a `NEBFLOW_BRAND_DOMAIN`
    * env override may replace it (single-domain, 2026-09-07 naming ruling) —
    * display-only, never consumed to build a URL.
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
      // agentName 三档链（20260907 节点名刷新持久化批）：indexed meta（team/主会话既有
      // 归属）→ AgentRecord.displayName（Project 域注册点写入的 Flow Map 节点名 /
      // "dispatcher/<project>"——node-/dispatcher- 会话从不过 SessionStore.createSession，
      // index 恒无条目，缺此档刷新后 subagent 面板行回退 sessionId「node-xx 默认名」）
      // → sessionId 兜底（既有行为）。
      "agentName"      -> meta.flatMap(_.agentName).orElse(rec.displayName).getOrElse(rec.sessionId).asJson,
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
      "retryCount"     -> retryCount.getOrElse(0).asJson,
      // 项目归属（2026-09-06 作者裁定：面板 Flow 徽标旁标注项目名）——恢复路径
      // 数据源。仅 Project 域会话（node- 与 dispatcher- 前缀，注册时写
      // AgentRecord.project）有值；其余空串（前端 falsy → 不渲染徽标）。实时路径
      // 不经此字段（agentStart 帧由 routeSubagentWsSend 转发层注入，见 NodeRunner）。
      "project"        -> rec.project.getOrElse("").asJson
    )
