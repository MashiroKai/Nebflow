package nebflow.gateway

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.{ActorSystem as RootActorSystem, *}
import nebflow.agent.*
import nebflow.core.*
import nebflow.core.entity.EntityLoader
import nebflow.core.flow.{FlowTreeActor, FlowTreeRegistry, TeamSessionRegistry}
import nebflow.core.mcp.McpManager
import nebflow.core.project.{CancelSource as ChainCancelSource, *}
import nebflow.core.schedule.FreezeSchedule.given
import nebflow.core.skill.SkillService
import nebflow.core.tools.*
import nebflow.gateway.NfFilePolicy.*
import nebflow.gateway.WsDispatch.parsedJson
import nebflow.llm.*
import nebflow.service.*
import nebflow.shared.{NebflowLogger, *}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame

import scala.concurrent.duration.*
import scala.io.Source

class WebSocketRoutes(
  wsb: WebSocketBuilder2[IO],
  sessionService: SessionService,
  agentService: AgentService,
  configService: ConfigService.type,
  configRef: Ref[IO, NebflowServiceConfig],
  rateLimiter: RateLimiter,
  /**
   * Phase 3b: the LIVE file token (Ref, not a boot snapshot) — a rotation
   * swaps it and the old value dies on this running gateway immediately
   * (criterion 7), while live sessions keep authenticating.
   */
  tokenRef: Ref[IO, String],
  /** Phase 3b: the console session store (session rail on the WS-face routes). */
  sessions: GatewaySessionStore,
  fileChangeTracker: FileChangeTracker,
  sessionStore: SessionStore,
  wsHub: WsHub,
  contextWindow: Int = Defaults.ContextWindow,
  sharedResources: SharedResources,
  mcpManager: McpManager,
  sttService: Option[SttService] = None,
  /**
   * C2-6: the `/api/nf-file` ticket store, created (TTL from
   * `nebflow.json`) and injected by GatewayMain. The default keeps every
   * existing construction site (specs, tooling) compiling.
   */
  nfTicketStore: NfTicketStore = NfTicketStore.unsafeDefault(),
  /**
   * C1-5 injection seam: the credential-namespace policy the read and the
   * signing endpoints share. Memoized so the R2 inode scan is a one-off.
   */
  nfPathPolicy: NfPathPolicy = NfPathPolicy.memoized(),
  /**
   * R-1b conn-guard：per-IP WS 看护（升级面准入 + 计数）。缺省 = 全放行
   * 实例（既有测试构造点零改动）；生产由 GatewayMain 注入实配。
   */
  connGuard: ConnGuard = ConnGuard.disabled
):
  private val logger = NebflowLogger.forName("nebflow.ws")

  /**
   * #295 热更：setSttConfig 写配置后重建服务并替换此 Ref——transcribe 立即
   * 走新配置，无需重启。构造参数保留为初始值（GatewayMain 启动时装载）。
   */
  private val sttServiceRef: Ref[IO, Option[SttService]] = Ref.unsafe(sttService)
  private val rootSystem = sharedResources.actorSystem

  /** Map of sessionId -> root AgentActor ref. Concurrent-safe via Ref. */
  private val rootAgents: Ref[IO, Map[String, nebflow.actor.ActorRef[AgentCommand]]] =
    Ref.unsafe(Map.empty)

  /**
   * In-flight root-agent spawns (sessionId -> completion). WS connect now
   * eagerly spawns the root agent (⑦ team restore), so concurrent first-use
   * paths (connect + user message) must not double-spawn — the self-cloned
   * actor registry would end up with two live fibers for one session.
   */
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
                  case None => rootFallback(agentName)
                }
              case None => rootFallback(agentName)
        }
      case None =>
        sharedResources.agentLibrary.get(RootAgentIdentity.Name).flatMap {
          case Some(d) => IO.pure(d)
          case None => IO.raiseError(new RuntimeException("No default agent available"))
        }

  private def rootFallback(agentName: String): IO[AgentDef] =
    sharedResources.agentLibrary.get(RootAgentIdentity.Name).flatMap {
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
          rootSpawnInFlight
            .modify { inFlight =>
              inFlight.get(sessionId) match
                case Some(done) => (inFlight, Right(done))
                case None =>
                  val done = Deferred.unsafe[IO, Unit]
                  (inFlight + (sessionId -> done), Left(done))
            }
            .flatMap {
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
      readTracker <- nebflow.shared.ReadTracker.create
      fileHistory <- nebflow.shared.FileHistory.create()
      modelOverrides <- sharedResources.sessionModelOverrides.get
      contextWindow = modelOverrides.get(sessionId).map(_.contextWindow).getOrElse(sharedResources.contextWindow)
      // ── ctxthresh 批（2026-09-15 方案 A，作者卡答「按方案A实施」）─────────────
      // 🔴 **本行是全仓唯一的阈值覆盖注入点**（口径③「仅 Nebula 窗口」的**结构性**
      // 保证，不是口号）：depth=0 的 WS 根会话 spawn 是全仓唯一的 root spawn 点，
      // 非 root spawn（NodeRunner / EphemeralAgentRunner / MailTool /
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
      agentName = metaOpt.flatMap(_.agentName).getOrElse(RootAgentIdentity.Name)
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
        nebflow.shared.RulesStore.resolveInheritedRules(
          fid,
          id => sharedResources.sessionStore.getFolderParentId(id)
        )
      }.flatten
      ref <- rootSystem.spawn(
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
          sandboxEnabled = agentDef.name == RootAgentIdentity.Name,
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
          nebflow.shared.NebflowLogger
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

  end doSpawnRootAgent

  // ── D1 后台通知·目标会话激活面（steward 批 2026-10-23；作者裁定 09:46
  //    「目标会话死了就激活发送，不用发给root」）────────────────────────────

  /**
   * 激活（复活）已死的目标会话并把完成通知投给它。复活机制与引擎重启后的会话
   * 恢复**同源**：[[doSpawnRootAgent]] 持久化恢复 spawn（SessionStore 水合
   * transcript + spawn 即 `RecoverPersistedQueues` 注入队重放）——WS connect 恢复
   * 同款，通知经重放落进目标会话新回合。
   *
   * 家族分派：
   *   - registry 行已活（判活→激活竞态合拢）⇒ 直接投该会话（新鲜 ref）；
   *   - 会话已删（SessionStore 无行）⇒ `UnknownSession`（不落盘、不激活、审计收口）；
   *   - Delegate/SubTask/Ephemeral/Plan ⇒ `Deferred`（复活归监督者 respawn /
   *     boot sweep 既有恢复腿所有，不越权重挂——持久标记等自然复活重放补投）；
   *   - Root/Team/Flow ⇒ 先把标记写进**目标会话**注入队（correlationId 幂等，
   *     BgInboxBackstop 会话存在守卫双保险），再走恢复 spawn ⇒ 重放交付。
   */
  def activateSession(
    sessionId: String,
    pendingEvent: AgentCommand.ExternalEvent
  ): IO[BgSessionActivator.ActivationResult] =
    sessionStore.getSessionMeta(sessionId).flatMap {
      case None => IO.pure(BgSessionActivator.UnknownSession) // 已删：无处激活、无处落盘
      case Some(_) =>
        sharedResources.agentRegistry.get.flatMap { reg =>
          reg.get(sessionId) match
            case Some(rec) =>
              rootSystem.isAlive(rec.ref.path).flatMap {
                case true =>
                  // 激活竞态：判活到激活之间已被别人复活 ⇒ 直投新鲜 ref（零重复落盘）。
                  (rec.ref ! pendingEvent)
                    .as(BgSessionActivator.Activated: BgSessionActivator.ActivationResult)
                case false => activateDeadSession(sessionId, rec.kind, pendingEvent)
              }
            case None => activateDeadSession(sessionId, AgentKind.Root, pendingEvent)
        }
    }

  private def activateDeadSession(
    sessionId: String,
    kind: AgentKind,
    pendingEvent: AgentCommand.ExternalEvent
  ): IO[BgSessionActivator.ActivationResult] =
    kind match
      case AgentKind.Delegate | AgentKind.SubTask | AgentKind.Ephemeral | AgentKind.Plan =>
        IO.pure(
          BgSessionActivator.Deferred(
            s"session family $kind revival is owned by its supervisor respawn / boot-sweep legs"
          )
        )
      case _ =>
        // Root | Team | Flow：标记先行（spawn 重放载体），恢复 spawn 同源激活。
        BgInboxPort.append(sessionId, pendingEvent) *>
          ensureRootAgent(sessionId)
            .as(BgSessionActivator.Activated: BgSessionActivator.ActivationResult)
            .handleErrorWith(e => IO.pure(BgSessionActivator.Deferred(e.getMessage)))

  // 装配（构造一次；install 幂等覆盖）。bg 完成通知的目标会话死亡 ⇒ 激活目标本身。
  BgSessionActivator.install((sid, ev) => activateSession(sid, ev))

  /**
   * P2: register a root session's recording wsSend with the InteractionHub so
   * permission/AskUser cards render in the Nebula window and persist to the
   * root session's ui.json.
   */
  private def registerRootInteraction(sessionId: String, wsSend: io.circe.Json => IO[Unit]): IO[Unit] =
    sharedResources.interactionHubRef.get.flatMap {
      case Some(hub) => (hub ! nebflow.actor.InteractionHubCommand.RegisterRoot(sessionId, wsSend)).void
      case None => IO.unit
    }

  private def unregisterRootInteraction(sessionId: String): IO[Unit] =
    sharedResources.interactionHubRef.get.flatMap {
      case Some(hub) => (hub ! nebflow.actor.InteractionHubCommand.UnregisterRoot(sessionId)).void
      case None => IO.unit
    }

  /**
   * 刷新存活 (2026-09-03): re-send a root session's still-pending AskUser
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
            .?[List[io.circe.Json]](reply => nebflow.actor.InteractionHubCommand.ListPendingAsks(rootSid, reply))
            .flatMap(_.traverse_(frame => wsSend(frame)))
            .handleErrorWith { e =>
              logger.warn(s"Pending-ask replay failed for session $sessionId: ${e.getMessage}")
            }
        }
    }

  /**
   * 多 AskUser 并发批（#250 第③项，2026-09-13 作者裁定「6 项全补」）：
   * 全库 pending-AskUser 快照 → 本连接（`pendingAsksSnapshot{asks:[…]}`，单帧，
   * 与 `ListPendingAsks` 的重放帧逐字节同构，含 `replayed: true`）。
   *
   * 与 `replayPendingAsks` 的分工：那条**渲染卡片进聊天流**（按会话订阅触发，职责
   * 不变），本条只**重建待办条/badge 的本地镜像**——所以它是「一次性全局」，与
   * 当前活动会话无关。前端消费点 = `askPending.applyPendingAskSnapshot`。
   *
   * hub 未装配（早期 boot / 测试）⇒ 回空快照：这是确定性结论（无 hub ⇒ 无槽位），
   * 比「不回帧、前端镜像悬空」少一条静默路径。查询失败 ⇒ 回失败帧（不带 asks），
   * 前端据此保留本地镜像并给可见提示——把「同步失败」与「确实没有 pending」区分开。
   */
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
          .?[List[io.circe.Json]](reply => nebflow.actor.InteractionHubCommand.ListAllPendingAsks(reply))
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

  /**
   * 递进式放行链 (2026-08-30)：把确认卡上选定的升级档**落为全局持久档位**。
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

  /**
   * 全局档位的**单一持久写入口**（permshield S1）：WS 盾牌 `setSafetyMode` /
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
      treeRef <- rootSystem.spawn(FlowTreeActor(config), s"flow-tree-$sessionId")
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

  /**
   * #38 Layer C (2026-09-01): team 成员会话删除闭环。
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
        case None => IO.unit
    yield ()

  /**
   * V1 (2026-09-03): Delegate/SubTask/Ephemeral 子代理级联停止——实现抽在
   * SessionChildCascade（deleteSession/batchDelete 两条路径与单测共用同一实现），
   * 取舍理由见该对象 doc。
   */
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
            _ <- (hub ! nebflow.actor.InteractionHubCommand.Answered(
              nebflow.actor.InteractionAnswered(requestId, rootSid, payload)
            )).void
          yield ()
        case None =>
          logger.warn("Interaction answer dropped: InteractionHub not spawned") *> IO.unit
      }
    else logger.warn("Dropping interaction answer: no sessionId provided") *> IO.unit

  /**
   * askuser-remote P1 (2026-10-07): the BRIDGE answer leg — social bridges
   * (a feishu card click, a weixin numbered reply) enter the SAME Answered
   * convergence through this DEDICATED public method instead of the private
   * frontend one, so bridge-sourced answers stay attributable (their own log
   * line) and can never be confused with a frontend WS answer. Same
   * primitives, same ghost-agent D2 guarantee: the hub is told, never
   * routeToAgent.
   */
  def forwardBridgeInteractionAnswer(requestId: String, sessionId: String, payload: io.circe.Json): IO[Unit] =
    if sessionId.nonEmpty then
      sharedResources.interactionHubRef.get.flatMap {
        case Some(hub) =>
          for
            rootSid <- resolveRootSessionId(sessionId)
            _ <- logger.info(
              s"bridge interaction answer: requestId=$requestId root=$rootSid keys=${payload.asObject.map(_.keys.toList.mkString(",")).getOrElse("-")}"
            )
            // askuser-remote P1 反射补口（2026-10-09，feishu-answer-card-flip）：
            // 桥面作答此前只进 hub + 会话注入，桌面端两面都收不到（.ui.json 无作答行
            // ⇒ 刷新后历史卡永远「未答」；无关卡帧 ⇒ 活卡不翻面——2026-10-09 12:36
            // 用户报单根因）。web 腿（handleAskUserAnswer）两面都做，此处对齐：
            // 落库行（同款单一构造点）+ askUserAnsweredRemote 翻面帧。两面 best-effort
            // （与取消落盘同款降级：失败只 WARN，作答照常进 hub）；askUser 形闸由
            // BridgeAnswerReflection 单点把持（许可类载荷 ⇒ 双跳过，改前行为不变）。
            // 秘密槽判据走 hub 只读查询（SecretSlotsOf，与 web 腿 secretSlotsFor 同一
            // 权威同一形态；查询失败 ⇒ 空集，hub 侧守卫兜底拒收明文回流）。
            secretIdxs <- hub
              .?[Option[List[nebflow.actor.SecretSlotSpec]]](reply =>
                nebflow.actor.InteractionHubCommand.SecretSlotsOf(requestId, reply)
              )
              .map(_.getOrElse(Nil).map(_.idx).toSet)
              .handleErrorWith(_ => IO.pure(Set.empty[Int]))
            _ <- BridgeAnswerReflection.rowFor(requestId, payload, secretIdxs).traverse_ { row =>
              sharedResources.sessionStore
                .appendUiMessages(rootSid, List(row))
                .handleErrorWith(e =>
                  logger.warn(
                    s"bridge answer ui-row append failed requestId=$requestId root=$rootSid: ${e.getMessage}"
                  )
                )
            }
            _ <- BridgeAnswerReflection.frame(rootSid, requestId, payload, secretIdxs).traverse_ { f =>
              wsHub
                .broadcast(f)
                .handleErrorWith(e =>
                  logger
                    .warn(s"bridge answer flip broadcast failed requestId=$requestId root=$rootSid: ${e.getMessage}")
                )
            }
            _ <- (hub ! nebflow.actor.InteractionHubCommand.Answered(
              nebflow.actor.InteractionAnswered(requestId, rootSid, payload)
            )).void
          yield ()
        case None =>
          logger.warn("Bridge interaction answer dropped: InteractionHub not spawned") *> IO.unit
      }
    else logger.warn("Dropping bridge interaction answer: no sessionId provided") *> IO.unit

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
  /**
   * ctxthresh 批：会话的**有效上下文窗口**（只读回显用）——会话级模型覆盖优先，
   * 否则预设派生的全局值。与 `doSpawnRootAgent` 内同一表达式同源。
   */
  private def effectiveContextWindowOf(sessionId: String): IO[Int] =
    sharedResources.sessionModelOverrides.get.map(
      _.get(sessionId).map(_.contextWindow).getOrElse(sharedResources.contextWindow)
    )

  /**
   * ctxthresh 批：会话的阈值覆盖现值——内存 Ref（本进程内已热更的权威值）→
   * 盘上 `SessionMeta.compactThresholdRatio`（跨重启保留值）→ None（无覆盖）。
   */
  private def compactThresholdOverrideOf(sessionId: String): IO[Option[Double]] =
    sharedResources.sessionCompactThreshold.get.flatMap { m =>
      m.get(sessionId) match
        case some @ Some(_) => IO.pure(some)
        case None => sessionStore.getSessionMeta(sessionId).map(_.flatMap(_.compactThresholdRatio))
    }

  /**
   * ctxthresh 批：**身份面判据**（作用域闸的第 ① 条，独立成函数便于判读）——
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
   * 对库里不存在的名字会 `rootFallback` 回落成 Nebula（本文件 :98-102）⇒ 用解析结果
   * 判会让 `agentName="general"` 这类会话**误判成 Nebula**（复核位探针的第 1 例正是它）。
   * spawn 期身份另有 `agentDef.name == "Nebula"`（:216 `sandboxEnabled`），二者不冲突。
   *
   * 无 `SessionMeta` 的 id（`node-*` / `dag-*` / 已删会话等幽灵 id）⇒ **身份不可立
   * ⇒ 拒**（fail-closed；它们既不在会话索引里，也不该有阈值覆盖可写）。
   */
  private def isRootIdentitySession(sessionId: String): IO[Boolean] =
    sessionStore.getSessionMeta(sessionId).map {
      case Some(meta) => meta.agentName.getOrElse(RootAgentIdentity.Name) == RootAgentIdentity.Name
      case None => false
    }

  /**
   * ctxthresh 批：**作用域闸**（口径①/③；静态泄漏判据见
   * `.nebflow/tools/20260915_ctxthresh_leak-check.sh`）——本功能**仅对 Nebula 的
   * root 会话开放**。判据 = 下列合取（全部为真才放行）：
   *
   *   ① **身份面**：`isRootIdentitySession`（上）——排除主窗口切过去的 standalone
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
   * `.nebflow/tools/20260915_ctxthresh_scope-recheck.mjs`）。
   */
  private def isRootScopeSession(sessionId: String): IO[Boolean] =
    if sessionId.isEmpty then IO.pure(false)
    else
      isRootIdentitySession(sessionId).flatMap { isRoot =>
        if !isRoot then IO.pure(false)
        else
          sharedResources.agentRegistry.get.flatMap { registry =>
            registry.get(sessionId) match
              case Some(rec) => IO.pure(rec.kind == AgentKind.Root)
              case None => sessionStore.getActiveMeta.map(_.exists(_.id == sessionId))
          }
      }

  /**
   * ctxthresh 批：阈值面板的**权威回显载荷**（面板打开 / 设值成功 / 恢复默认后
   * 共用同一数据源——前端不回算、不自造值，「生效绝对 token 回显」由此保证与
   * 引擎判定面同源）。
   */
  private def compactThresholdInfo(sessionId: String): IO[Json] =
    for
      ratioOpt <- compactThresholdOverrideOf(sessionId)
      window <- effectiveContextWindowOf(sessionId)
    yield
      val effective = CompactThresholdOverride.effectiveThreshold(window, ratioOpt)
      val default = nebflow.shared.CompactThreshold.threshold(window)
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
  private def isKickCandidate(rec: nebflow.actor.AgentRecord, now: Long): Boolean =
    val kickIdleMs = nebflow.shared.Defaults.SessionKickIdleSec * 1000L
    val agentIdleMs = if rec.lastActivityMs > 0 then now - rec.lastActivityMs else 0L
    val toolPhaseMs = if rec.currentToolStartedAt > 0 then now - rec.currentToolStartedAt else 0L
    val toolInFlight = toolPhaseMs > 0 && toolPhaseMs <= nebflow.shared.Defaults.ToolPhaseStuckMs
    rec.status == nebflow.actor.AgentStatus.Processing &&
    rec.lastActivityMs > 0 &&
    agentIdleMs > kickIdleMs &&
    !toolInFlight

  /**
   * Public API for external sources (e.g. bridge plugins) to inject a user message
   * into a session's agent. Reuses the same logic as WebSocket user messages:
   * rate limiting, UiMessage recording, agent routing.
   *
   * 社交通道入站媒体统一适配批（2026-10-20）：`attachments`（(path,name,mime)
   * 指针）在场 ⇒ 三同一登记/渲染/构块，判据 = **控制台 uploads 管线同款**
   * （`nebflow.bridge.UnifiedMedia` 单点）：
   *   - 登记同一：attJson=[{name,type,path}]（mime `image/` 前缀 ⇒ image，否则
   *     file——console :1770 同式）随 `UiMessage.User` 落盘；指针登记，字节留 media 根。
   *   - 显示同一：`bridgeUser` 帧增加性 `attachments` 键，前端既有媒体气泡面接管
   *     （文本面 = `content` 随文，纯媒体 = ""，与控制台纯图发送同形）。
   *   - 读取同一：image ⇒ `ImageInject.prepareImage` 管线 ⇒ `ContentBlock.Image`
   *     随 ExternalEvent.blocks 直入 LLM；file ⇒ 标签线文本（ReadTool 同面）。
   * 模型面文本 = faces(origin, senderId, 标签线 + 随文)，marker 块照旧只进模型面；
   * 构块失败（超帽/读失败）⇒ 块缺省、文本线仍送达（fail-closed 降级）。
   * 缺省形参 = 全部既有调用点（纯文本注入）逐字现状。
   */
  def handleBridgeMessage(
    sessionId: String,
    content: String,
    senderId: Option[String] = None,
    origin: Option[nebflow.bridge.BridgeOrigin] = None,
    attachments: List[nebflow.bridge.BridgeAttachment] = Nil
  ): IO[Unit] =
    if (content.isEmpty && attachments.isEmpty) || sessionId.isEmpty then IO.unit
    else
      rateLimiter.check("bridge").flatMap { allowed =>
        if !allowed then logger.warn("Bridge rate limit exceeded")
        else
          // Source marker relocation (source-marker batch, 2026-10-01): this is
          // the ONE bridge→session injection point, so it is also the one place
          // that splits an inbound message into the faces it is allowed to appear
          // on. 🔴 THE MARKER IS MODEL-FACE ONLY: it reaches the agent's context
          // (`ExternalEvent.payload`) and is structurally absent from the session
          // display face (the recorded `.ui.json` row and the `bridgeUser`
          // broadcast both take `faces.sessionDisplay`, which is the bare content
          // by construction). The template still lives channel-agnostically on
          // [[nebflow.bridge.BridgeOrigin]]; `origin = None` (the default, and
          // every pre-existing caller) leaves every face byte-identical to the
          // pre-batch behaviour.
          val media = attachments.filter(_.path.trim.nonEmpty)
          // 媒体统一适配批：标签线（控制台逐字同款）+ 随文 = 模型面的 content；
          // 纯文本注入（media 空）⇒ content 原样（逐字现状）；显示面 = 随文
          // （纯媒体 = ""，媒体卡接管聊天流——控制台纯图发送同形）。
          val modelContent =
            if media.isEmpty then content
            else
              val labels = nebflow.bridge.UnifiedMedia.labelLinesOf(media)
              if content.trim.isEmpty then labels else labels + "\n" + content
          val faces = nebflow.bridge.BridgeOrigin.faces(origin, senderId, modelContent)
          val source = senderId.map(id => s"[via bridge:$id]").getOrElse("[via bridge]")
          // The log line is diagnostic, not a display face: it may quote the
          // labelled model payload. The log file is never rendered as a chat
          // stream and never read back as a `UiMessage`.
          logger.info(s"Bridge message for session $sessionId: ${faces.modelPayload.take(60)}... $source") *>
            // 媒体统一适配批：vision 块先构（控制台 images 同管线；失败 ⇒ Nil，
            // 文本线仍送达）。逐件构块按序拼合（微信腿恒单件）。
            media.traverse(a => nebflow.bridge.UnifiedMedia.imageBlocksOf(a)).flatMap { perItem =>
              val imageBlocks = perItem.flatten
              // 媒体指针的 attJson（控制台同形 [{name,type,path}]）；纯文本注入 = Nil 逐字现状。
              val attJson = nebflow.bridge.UnifiedMedia.attJsonOf(media)
              val displayText = if media.isEmpty then faces.sessionDisplay else content
              // Record as UiMessage — DISPLAY face: bare content, no marker.
              // 媒体统一适配批：attJson 随行落盘（控制台同位），历史/缓存恢复走
              // 既有媒体行渲染面。
              sharedResources.sessionStore
                .appendUiMessages(
                  sessionId,
                  List(UiMessage.User(displayText, attJson, timestamp = System.currentTimeMillis()))
                )
                .handleErrorWith(e => logger.warn(s"Failed to record bridge UiMessage: ${e.getMessage}")) *>
                // Push to frontend in real-time so it shows without switching sessions
                // — DISPLAY face: bare content, no marker.
                // 媒体统一适配批：`attachments` 键增加性并帧（旧前端忽略未知键 =
                // 纯文本回落，协议零破坏）。
                wsHub.broadcast(
                  {
                    val base = io.circe.Json.obj(
                      "type" -> "bridgeUser".asJson,
                      "sessionId" -> sessionId.asJson,
                      "text" -> displayText.asJson
                    )
                    if attJson.isEmpty then base
                    else base.deepMerge(io.circe.Json.obj("attachments" -> attJson.asJson))
                  }
                ) *>
                // Use ExternalEvent instead of UserInput so the message is queued in
                // pendingEvents and can be injected at the next ToolsComplete gap —
                // avoids being stashed until the entire turn finishes.
                // MODEL face: the payload KEEPS the marker block (the model must stay
                // able to tell a bridged message from a local one); the structured
                // origin rides along in `metadata` for later consumers.
                // 媒体统一适配批：vision 块随 `blocks` 读取同管线直入 LLM 上下文。
                ensureAgent(sessionId)(ref =>
                  ref ! AgentCommand.ExternalEvent(
                    source = "bridge",
                    eventType = "user-message",
                    payload = faces.modelPayload,
                    metadata = faces.metadata,
                    correlationId = None,
                    blocks = Option(imageBlocks).filter(_.nonEmpty)
                  )
                )
            }
      }

  /**
   * Public API for bridge card-action callbacks to send specific AgentCommands
   * (e.g. Interrupt) to a session's agent.
   */
  def handleBridgeAgentCommand(sessionId: String, command: AgentCommand): IO[Unit] =
    if sessionId.isEmpty then IO.unit
    else ensureAgent(sessionId)(ref => ref ! command)

  def routes: HttpRoutes[IO] =
    StaticRoutes.uploadsRoutes(
      tokenRef,
      sessions
    ) <+> StaticRoutes.jsRoutes <+> StaticRoutes.assetsRoutes <+> NfFileRoutes.nfFileRoutes(
      nfTicketStore,
      nfPathPolicy
    ) <+> NfFileRoutes.nfTicketRoutes(tokenRef, nfTicketStore, nfPathPolicy, sessions) <+> NfFileRoutes
      .nfAuthcheckRoutes(
        tokenRef,
        sessions
      ) <+> NfOfficeRoutes.officeRoutes(nfTicketStore, nfPathPolicy) <+> NfFileRoutes.nfReachRoutes <+> HttpRoutes.of[IO] {
      case req @ GET -> Root / "ws" =>
        // Phase 3b: the unified GatewayAuth criterion under WsAuth — session
        // cookie first, then Bearer (file token; a WsAuth registration note:
        // browsers cannot send Authorization on a WS handshake, so this leg
        // only ever fires for non-browser clients such as CLI/script callers).
        // Stage D retired the two file-token-in-request-target rails (the
        // legacy `nebflow_token` cookie and the token query param) — the
        // handshake is session-cookie driven; a presented-but-invalid high
        // rail still falls through instead of rejecting (see GatewayAuth).
        tokenRef.get
          .flatMap(t => GatewayAuth.authenticate(req, t, sessions, GatewayAuth.Rails.WsAuth))
          .flatMap {
            case None =>
              logger.warn(
                s"WebSocket auth failed from ${req.remoteAddr.getOrElse("unknown")}"
              ) *>
                Forbidden("Invalid token")
            case Some(_) =>
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
                    // ── A2 (perf-481): bounded outbound queue ────────────────────
                    // Was `Queue.unbounded`: a slow/stalled subscriber never
                    // applied backpressure, so its queue grew without bound while
                    // the event loop kept paying "serialize + offer" for it.
                    //
                    // Overflow policy (plan OD-1, per-frame class):
                    //  · streaming increment (textDelta/thinkingDelta/…): drop the
                    //    OLDEST frame and enqueue the new one. A stream increment is
                    //    superseded by later ones, so losing the middle of a stream
                    //    costs at most a few rendered characters — never final state.
                    //  · control/status frame: do NOT drop. A dropped control frame
                    //    is a silent lie (e.g. a lost `configUpdated` leaves the UI
                    //    showing "saved"); a throttled one delays it. So the
                    //    connection is torn down instead: the client reconnects and
                    //    re-syncs from scratch (ws.js owns the reconnect + onReconnect
                    //    resync leg), which is a visible, recoverable failure rather
                    //    than a silent one.
                    // The class is computed once per event inside WsHub (see there).
                    outbound <- Queue.bounded[IO, WebSocketFrame](OutboundQueueCapacity)
                    outboundOverflowClosed <- Deferred[IO, Unit]

                    perConnWsSend = (text: String, isStream: Boolean) =>
                      outbound
                        .tryOffer(WebSocketFrame.Text(text))
                        .flatMap {
                          case true => IO.unit
                          case false if isStream =>
                            // Full queue + droppable frame ⇒ evict oldest, then take
                            // the slot this frame just vacated. `tryTake` returning
                            // None (a reader drained it concurrently) is fine — the
                            // retry below either succeeds or is counted, never loops.
                            outbound.tryTake *> outbound.tryOffer(WebSocketFrame.Text(text)).void *>
                              IO(wsHub.noteOverflowDrop())
                          case false =>
                            // Full queue + control frame ⇒ tear this connection down.
                            // The teardown rides the queue's own stream (see
                            // `sendStream`), so there is no process-wide state and no
                            // cross-connection leak.
                            IO(wsHub.noteOverflowClose()) *>
                              logger.warn(
                                "WebSocket outbound queue full on a non-stream frame — closing " +
                                  s"this connection (capacity $OutboundQueueCapacity)"
                              ) *>
                              outboundOverflowClosed.complete(()).void
                        }
                    hubConnId <- wsHub.register(perConnWsSend)

                    // A1: the hub now hands connections pre-serialized text. The
                    // handlers below (message dispatch, session list, the explorer
                    // watch table) still reason in Json, so they get an adapter that
                    // serializes on the way in — the point of A1 is that the *fan-out*
                    // no longer re-serializes per connection, not that Json is banned.
                    perConnJsonSend = (json: io.circe.Json) => perConnWsSend(json.noSpaces, WsHub.isStreamFrame(json))

                    // explorer-rt (chain-n-1981ce87): one watch-subscription table per
                    // connection. Chained into the finalizer below — a dropped
                    // connection releases every WatchService it registered.
                    watchSession = new ExplorerWatchSession(perConnJsonSend, logger)

                    receivePipe: Pipe[IO, WebSocketFrame, Unit] = _.evalMap {
                      case WebSocketFrame.Text(text, _) =>
                        handleMessage(text, perConnJsonSend, watchSession).handleErrorWith { e =>
                          logger.error(s"WebSocket message handler error: ${e.getMessage}", e)
                          IO.unit
                        }
                      case _ => IO.unit
                    }.onFinalize(
                      wsHub.unregister(hubConnId) *> watchSession.close()
                    )

                    // sendStream: read from outbound queue, send via WebSocket.
                    // Client disconnects are expected (browser tab close, network change) and
                    // produce IOException during write. Catch and swallow gracefully.
                    //
                    // A2: the stream races the queue against the overflow signal, so a
                    // control frame hitting a full queue actually ENDS the stream
                    // (the socket closes and the client reconnects + resyncs) instead
                    // of the frame being silently discarded. `interruptWhen` cancels
                    // the queue side on completion, and the server's own WebSocket
                    // close handling runs the receive pipe's finalizer.
                    sendStream = Stream
                      .fromQueueUnterminated(outbound)
                      .interruptWhen(outboundOverflowClosed.get.attempt)
                      .handleErrorWith { e =>
                        Stream.eval(logger.debug(s"WebSocket send stream closed: ${e.getMessage}")).drain
                      }
                    _ <- logger.info("WebSocket client connected")
                    thinkingCfg <- sharedResources.thinkingConfigRef.get
                    workScheduleCfg <- sharedResources.freezeScheduleRef.get
                    skipUntil <- sharedResources.freezeSkipUntilRef.get
                    sttSvc <- sttServiceRef.get
                    // 统一更新收敛闭环（M3 ③④）：连接初始态即携带更新状态快照
                    // （自报版本/构建/通道 + 最近挂账与决策）——面板更新卡纯订阅 +
                    // 快照，零轮询。未装配 ⇒ 空对象，帧形零变化。
                    updateSnapshot <- sharedResources.updateConvergence
                      .traverse(_.stateJson)
                      .map(_.flatten)
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
                            "freezeState" -> nebflow.core.schedule.FreezeSchedule
                              .freezeStateNode(
                                workScheduleCfg,
                                skipUntil,
                                System.currentTimeMillis()
                              )
                              .asJson,
                            "stt" -> SttService.serverConfigNode(sttSvc),
                            // A4: the tool list is served on demand (`getToolsList`),
                            // not on every connect + config broadcast — see the note
                            // above `toolsListJson`.
                            "mcpServers" -> mcpServers.asJson
                          )
                          // M3 ④：update 快照 additive 挂载（未装配 = 空对象 = 不出现键）
                          .deepMerge(
                            updateSnapshot.map(u => io.circe.Json.obj("update" -> u)).getOrElse(io.circe.Json.obj())
                          )
                          .noSpaces
                      )
                    )
                    activeMeta <- sessionStore.getActiveMeta
                    agentName = activeMeta.flatMap(_.agentName).getOrElse(RootAgentIdentity.Name)
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
                    _ <- sessionService.sendSessionList(perConnJsonSend, agentName)
                    // R-1b：入账在 build 前（此处之后仅剩 build 本身，失败即自然不
                    // build ⇒ 无幽灵计数）；回减挂流 finalizer（连接关闭必走）。
                    guardHandle <- connGuard.acquireWs(wsIp)
                    guardedPipe = receivePipe.andThen(_.onFinalize(connGuard.releaseWs(guardHandle)))
                    ws <- wsb.build(sendStream, guardedPipe)
                  yield ws
                )
              }
          }

      // --- Callback endpoint (外→内) ---
      // POST /api/callbacks/inject — agent-centric, authenticated through the
      // unified GatewayAuth criterion (WsAuth: session cookie > Bearer).
      case req @ POST -> Root / "api" / "callbacks" / "inject" =>
        tokenRef.get
          .flatMap(t => GatewayAuth.authenticate(req, t, sessions, GatewayAuth.Rails.WsAuth))
          .flatMap {
            case None => Forbidden("Invalid token")
            case Some(_) => handleInject(req)
          }

      // GET /api/nf-file — local file serving for card iframes moved to the
      // companion as nfFileRoutes(token) (standalone, unit-testable, same
      // pattern as uploadsRoutes/jsRoutes); mounted ahead of this match above.
      // 2026-09-03 Canvas interactive-HTML fix: the whitelist (NfFileAllowedExt)
      // now also covers the text asset types (js/css/json) a multi-file HTML
      // deliverable references from the Canvas HTML viewer.
      case req @ GET -> Root =>
        // W1 single switch point (mobile-quickwins 2026-10-20; supersedes the
        // P1 classpath-only probe): the entry dispatches on
        // StaticRoutes.staticSource — bundled classpath dist (jar), FRESH
        // disk dist (build/web-dist under the engine working dir; the
        // freshness fence is what keeps `sbt run` hot-edit serving the dev
        // tree the moment a web/ source is newer than the dist), or the dev
        // source tree. Everything else the dist index references is either
        // under /assets (assetsRoutes) or a byte-identical passthrough
        // (vendor, fonts, icons) served by the existing web/ routes — no
        // fallback logic, the entry and its hashed deps come from one
        // atomic build-web.mjs run.
        //
        // L1 rebrand: every branch goes through the brand injection before
        // the closing head tag. The served body therefore differs from the
        // source bytes, which is why this route does not use StaticFile's
        // conditional-request handling (a jar-entry Last-Modified must not
        // vouch for content we mutated) — the response stays no-cache with
        // no validators.
        // console-fastpath 批 2026-10-23·腿②：隧道脸（TunnelOriginMark）分发到
        // 首开内联打包面（[[StaticRoutes.indexResponseFor]]），直连脸逐字原应答。
        StaticRoutes.indexResponseFor(req)

      case req @ HEAD -> Root =>
        // Headers-only parity with the pre-rebrand StaticFile route (curl -I,
        // health/link checkers). The GET case above does not match HEAD
        // requests, and the body-stripped variant must go through the same
        // content generation so validators never diverge between the verbs.
        StaticRoutes.indexResponseFor(req).map(_.withBodyStream(Stream.empty))

      // 卡窗白屏修复批（2026-10-22）：/index.html 入口别名 —— 与 `/` 同一
      // brand 注入应答。独立卡窗（UI 批⑧）曾以 `/index.html?card-window=1`
      // 开窗：本路由只派 `/`，该 URL 落根文件白名单（sw.js/favicon 族）之外
      // ⇒ 404 空 Content-Length 体 ⇒ 独立窗整页空白（作者实测根因）。别名让
      // 一切历史/深链入口落在真应用而非空白体；现役打开链已改 `/`
      // （canvas.js popoutCardWindow），别名是兜底面。query 原样保留
      // （http4s 只按 path 匹配），card-window 参数不丢。
      case req @ GET -> Root / "index.html" =>
        StaticRoutes.indexResponseFor(req)

      case req @ HEAD -> Root / "index.html" =>
        StaticRoutes.indexResponseFor(req).map(_.withBodyStream(Stream.empty))

      case req @ GET -> Root / "css" / file =>
        StaticFile
          .fromResource(s"web/css/$file", Some(req))
          .map(_.putHeaders("Cache-Control" -> "no-cache"))
          .getOrElseF(NotFound())

      // /js/** (any depth) is served by StaticRoutes.jsRoutes — see its
      // scaladoc for why the DSL single-segment routes (and the
      // per-directory cases they grew over time) were replaced.

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

      case req @ GET -> _ if req.uri.path.renderString.startsWith("/vendor/") =>
        // Vendor SUBTREE passthrough (canvas-office-render-fix batch,
        // 2026-10-06): the depth ≥ 2 shapes the monaco/pdfjs/fonts and
        // single-segment cases above cannot match — the pptx-preview MVP's
        // vendored /vendor/pptx-renderer/* answered 404 here and the renderer
        // never loaded (real-instance reading). Third occurrence of the
        // per-directory outage class on /vendor (monaco → pdfjs →
        // pptx-renderer) — the jsRoutes lesson applied to the vendor tree:
        // served by the standalone gap-filler, unit-pinned by
        // VendorSubtreeRoutesSpec (see StaticRoutes.vendorSubtree scaladoc).
        // This case sits AFTER the directory-specific cases, so their
        // behavior is byte-identical; this only fills the 404ing gap.
        StaticRoutes.vendorSubtree(req)

      case req @ GET -> _ if req.uri.path.renderString.startsWith("/brand/") =>
        // Brand asset subtree (stand-logo-slotting batch, 2026-10-15): serves
        // web/brand/** at any depth so the optional __BRAND__ logoUrl /
        // logoDarkUrl reference slots can point at the slotted stand kit
        // (/brand/stand/…) on switch day without moving the incumbent
        // /css/logo-* files. Same gap-filler shape as the /vendor/ subtree
        // case above — /brand/* was previously unrouted (404) and nothing
        // referenced it, so default behavior is byte-identical; guards are
        // the vendorSubtree precedent, pinned by BrandSubtreeRoutesSpec.
        StaticRoutes.brandSubtree(req)

      case req @ GET -> Root / fileName =>
        val allowed =
          Set(
            "style.css",
            "app.js",
            "sw.js", // console-load-perf 批 2026-10-21: 温访问秒开 SW（根 scope 注册要求根路径脚本；no-cache 保证 SW 更新即时可见）
            "favicon-32.png",
            "favicon-16.png",
            "favicon.svg",
            "favicon.ico",
            "favicon-180.png",
            "favicon-192.png",
            "favicon-512.png"
          )
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

  /**
   * A4 (perf-481): the full tool-description list, exposed as its OWN message
   * instead of riding every `serverConfig` frame.
   *
   * Why: `serverConfig` carries five small config nodes, but the tool list alone
   * is ~93.5 KB of tool descriptions. Embedding it made EVERY config broadcast —
   * and every WS connect — a ~93.5 KB frame to every connection, for a payload
   * the frontend never reads: `state.availableTools` is only ever WRITTEN
   * (`main.js:2701`) and has ZERO read sites across `web/` (measured by grep
   * over `src/main/resources/web/`, and re-confirmed by the DOM/e2e probe leg
   * before this change).
   *
   * On-demand (plan OD-2 default) rather than deleted: the field is the only
   * provider of `state.availableTools`, so dropping it outright would silently
   * break any future consumer. `getToolsList` serves it on request, and the
   * frontend asks once per connection — so the cost moves from "every config
   * broadcast × every connection" to "once per connect".
   */
  private def toolsListJson: io.circe.Json =
    ToolRegistry.ALL_TOOLS
      .map(t => io.circe.Json.obj("name" -> t.name.asJson, "description" -> t.description.asJson))
      .asJson

  /** A4: the on-demand face of the tool list (`getToolsList` → this frame). */
  private def sendToolsList(wsSend: io.circe.Json => IO[Unit]): IO[Unit] =
    wsSend(io.circe.Json.obj("type" -> "toolsList".asJson, "tools" -> toolsListJson))

  private def broadcastServerConfig: IO[Unit] =
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
          "freezeState" -> nebflow.core.schedule.FreezeSchedule
            .freezeStateNode(
              workScheduleCfg,
              skipUntil,
              System.currentTimeMillis()
            )
            .asJson,
          "stt" -> SttService.serverConfigNode(sttSvc),
          // A4: `tools` intentionally NOT here — see the note above the helper
          // (it was ~93.5 KB on every broadcast; now served by `getToolsList`).
          "mcpServers" -> mcpServers.asJson
        )
      )
    yield ()

    end for

  end broadcastServerConfig

  /**
   * Persist thinking config to nebflow.json — targeted field update.
   * 冻结修复（2026-08-27）：不再吞写盘错误——失败必须上浮给调用方回执
   * configUpdateFailed，否则用户看到「已保存」而重启后配置回滚（off 假成功）。
   * 调用方负责 writeLocked 串行化。
   */
  private def persistThinkingConfig(tc: ThinkingConfig): IO[Unit] =
    IO.blocking {
      // Read through the dual-read path (legacy fallback), write the brand
      // name — the first write completes the config-file rename migration.
      val existing =
        if os.exists(nebflow.shared.Config.DefaultConfigPath) then os.read(nebflow.shared.Config.DefaultConfigPath)
        else "{}"
      val path = PathUtil.configJsonWritePath(PathUtil.dataRoot)
      parse(existing).foreach { json =>
        val updated = json.mapObject { obj =>
          obj.add("thinkingConfig", tc.asJson)
        }
        // Restricted form (keyaudit-quickfix ①): nebflow.json carries provider
        // apiKeys — the file lands 0600 instead of the umask default 0644.
        AtomicJson.writeSyncRestricted(path, updated.spaces2)
      }
    }

  /**
   * Persist freeze schedule to nebflow.json — targeted top-level write (照抄
   * persistThinkingConfig 的 read-merge-write 语义，merge 纯函数在
   * FreezeSchedule.mergeIntoConfig 便于 B9 测试）。JSON 键名保留 "workSchedule"（前端契约）。
   * 冻结修复（2026-08-27）：错误上浮不吞——调用方持锁调用并负责失败回执。
   */
  private def persistWorkSchedule(cfg: nebflow.core.schedule.FreezeScheduleConfig): IO[Unit] =
    IO.blocking {
      val existing =
        if os.exists(nebflow.shared.Config.DefaultConfigPath) then os.read(nebflow.shared.Config.DefaultConfigPath)
        else "{}"
      val path = PathUtil.configJsonWritePath(PathUtil.dataRoot)
      parse(existing).foreach { json =>
        val updated = nebflow.core.schedule.FreezeSchedule.mergeIntoConfig(json, cfg)
        // Restricted form (keyaudit-quickfix ①) — same rationale as persistThinkingConfig.
        AtomicJson.writeSyncRestricted(path, updated.spaces2)
      }
    }

  /**
   * Persist MCP server enabled state to nebflow.json — targeted field update.
   * 冻结修复（2026-08-27）：错误上浮不吞（同族 fail-loud）。调用方持锁。
   */
  private def persistMcpServerEnabled(serverId: String, enabled: Boolean): IO[Unit] =
    IO.blocking {
      // Read through the dual-read path (legacy fallback), write the brand
      // name — the first write completes the config-file rename migration.
      val existing =
        if os.exists(nebflow.shared.Config.DefaultConfigPath) then os.read(nebflow.shared.Config.DefaultConfigPath)
        else "{}"
      val path = PathUtil.configJsonWritePath(PathUtil.dataRoot)
      parse(existing).foreach { json =>
        val updated = json.mapObject { obj =>
          val mcpObj = obj("mcpServers").flatMap(_.asObject).getOrElse(JsonObject.empty)
          val serverObj = mcpObj(serverId).flatMap(_.asObject).getOrElse(JsonObject.empty)
          val updatedServer = Json.fromJsonObject(serverObj.add("enabled", enabled.asJson))
          val updatedMcp = Json.fromJsonObject(mcpObj.add(serverId, updatedServer))
          obj.add("mcpServers", updatedMcp)
        }
        // Restricted form (keyaudit-quickfix ①) — same rationale as persistThinkingConfig.
        AtomicJson.writeSyncRestricted(path, updated.spaces2)
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
      val agentName = metaOpt.flatMap(_.agentName).getOrElse(RootAgentIdentity.Name)
      sendAgentSessionListByName(wsSend, agentName)
    }

  /** Send unified session list (all agents) — keeps sessions isolated from filtering issues. */
  private def sendAgentSessionListByName(wsSend: io.circe.Json => IO[Unit], agentName: String): IO[Unit] =
    (sessionStore.listSessions, sessionStore.listAllFolders).flatMapN { (sessions, folders) =>
      val rulesFolderIds = folders.filter(f => nebflow.shared.RulesStore.exists(f.id)).map(_.id)
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
      val agentName = metaOpt.flatMap(_.agentName).getOrElse(RootAgentIdentity.Name)
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

  /**
   * '~' 前缀展开（仅前缀语义；非 ~ 开头原样返回）——wsBrowse 路径入参用。
   * 委托 PathUtil.expandTilde：统一支持 `~` / `~/` / `~\`（Windows 分隔符形态），
   * 并在 Windows 上做分隔符归一（os-lib 拒绝混合分隔符段）。
   */
  private def expandTilde(path: String): String = PathUtil.expandTilde(path)

  /**
   * 应用内工作区浏览器（Route C 兜底）的目录列表响应帧。
   * home 供前端把 home 前缀折叠为「主目录」面包屑；err 非 null → 前端在弹窗
   * 内联展示错误（路径非法/不可读）。
   */
  private def wsBrowseEvent(
    evType: String,
    path: String,
    parent: Option[String],
    entries: List[String],
    err: Option[String]
  ): Json =
    val fields = scala.collection.mutable.ListBuffer(
      "type" -> Json.fromString(evType),
      "path" -> Json.fromString(path),
      "home" -> Json.fromString(sys.props("user.home")),
      "entries" -> Json.arr(entries.map(Json.fromString)*)
    )
    parent.foreach(p => fields += "parent" -> Json.fromString(p))
    err.foreach(e => fields += "error" -> Json.fromString(e))
    Json.obj(fields.toList*)
  end wsBrowseEvent

  /**
   * picker-trunc (2026-09-22 author ruling, A+B+C in ONE batch) — the
   * `browsePath` frame builders. Body lives in the companion object (pure core,
   * spec-callable — `deletePathsSafely` precedent), these are the seam the
   * handler uses.
   */
  private[gateway] def browseResultFrame(dir: os.Path, displayPath: String, query: String, cap: Int): Json =
    WebSocketRoutes.browseFrame(dir, displayPath, query, cap)

  private val MaxMessageSize = 10 * 1024 * 1024 // 10MB (base64 images can be large)

  /**
   * A2 (perf-481): per-connection outbound queue capacity, in frames.
   *
   * Sized as a bounded spill buffer for a transiently slow reader, NOT as a
   * stream buffer: the largest frame the server emits is the ~21 KB
   * `serverConfig`/`configData` family (A4 shrinks the tool-list one further),
   * so 1024 frames bounds one connection's pending bytes to the low tens of MB
   * even in the worst case, while a healthy reader drains far faster than the
   * server enqueues. A reader that stays behind for this many frames is stalled
   * by definition and gets the OD-1 policy applied (drop-oldest for stream
   * increments, connection teardown for control frames).
   */
  private[gateway] val OutboundQueueCapacity = 1024

  /**
   * Text-stream engine behind the `textWindow` / `textIndex` / `textSearch` /
   * `textCancel` legs — state + methods live in [[TextStreamSearch]] (one
   * instance per routes, identical lifecycle to the inline Refs it replaced).
   * The open-path size gate it shares with `pop.readFile` is
   * `TextStreamSearch.MaxPopReadFileBytes`.
   */
  private val textSearch = new TextStreamSearch

  /**
   * explorer-rt: shared explorer root resolution (listDir + watchSubscribe
   * same judgment by construction). `overrideRoot` wins verbatim; otherwise
   * session project root, falling back to the default projects dir — byte
   * -for-byte the resolution listDir has always used; the canonical-path
   * escape guard stays at the call sites (it guards a resolved subpath, not
   * the root itself).
   */
  private def resolveExplorerBaseRoot(sessionId: String, overrideRoot: Option[String]): IO[String] =
    overrideRoot match
      case Some(root) => IO.pure(root)
      case None =>
        for
          metaOpt <- sessionStore.getSessionMeta(sessionId)
          folderId = metaOpt.flatMap(_.folderId)
          prOpt <- sessionStore.resolveProjectRoot(folderId)
        yield prOpt.getOrElse((PathUtil.dataRoot / "projects").toString)

  /** Public facade for REST API to call into the same message handler. */
  def handleMessagePublic(text: String, wsSend: io.circe.Json => IO[Unit]): IO[Unit] =
    // Non-live watch session: explorer-rt subscriptions need a real WS
    // connection (lifecycle-tied); a REST-invoked handler answers fileOpError
    // for watch frames instead of silently leaking a WatchService.
    handleMessage(text, wsSend, new ExplorerWatchSession(wsSend, logger, live = false))

  private def handleMessage(
    text: String,
    wsSend: io.circe.Json => IO[Unit],
    watchSession: ExplorerWatchSession
  ): IO[Unit] =
    if text.length > MaxMessageSize then logger.warn(s"Dropping oversized WebSocket message (${text.length} bytes)")
    else
      val parsed = parsedJson(text)
      // 未走信封助手:parsed 绑定同时供 type 等多字段使用(全文件「先绑定 json/hcursor
      // 再取多字段」的 sessionId 站点均按此边界保持原样,2026-09-24)
      val sessionIdForTracking = parsed.hcursor.downField("sessionId").as[String].toOption.getOrElse("")
      val msgType = parsed.hcursor.downField("type").as[String].getOrElse("")
      for
        _ <- sharedResources.lastWsActivity.set(System.currentTimeMillis())
        _ <- nebflow.core.UsageTracker.record("ws_message", sessionIdForTracking)
        _ <- WsDispatch.handlers.get(msgType) match
          case Some(handle) =>
            handle(wsDispatchContext, text, wsSend, watchSession)

          case None =>
            val json = parsedJson(text)
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
                    // device-provenance 批：帧 `device` 键解析（缺席 ⇒ None ⇒ 下方
                    // 构块/派发逐字节不变；解析面 fail-open，绝不阻塞消息本身）。
                    deviceProvenanceOf(json).flatMap { deviceResolved =>
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
                            if !isFrontendBlob && hash.nonEmpty && fileSize > 0 then
                              findLocalFile(name, hash, fileSize, searchPaths)
                            else None
                          // Fallback: macOS Spotlight (finds files in Library, Containers, etc.)
                          val spotlightPath = localPath match
                            case Some(_) => localPath
                            case None if !isFrontendBlob && hash.nonEmpty && fileSize > 0 =>
                              spotlightSearch(name, hash, fileSize)
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
                           // quote-message 一期（2026-10-09，spec §3.3）：帧 refs 里
                           // refType==='chat-message' 的**首条**转成落盘 quote 快照
                           // （至多 1 条；与 attJson 组装同段）。缺席 ⇒ None ⇒ 编码器
                           // 不落 quote 键 ⇒ 无引用行字节形态与旧 .ui.json 逐字相同。
                           val quoteJson = quoteFromRefs(json)
                           val injected = json.hcursor.downField("injected").as[Boolean].getOrElse(false)
                           sharedResources.sessionStore
                             .appendUiMessages(
                               msgSessionId,
                               List(
                                 UiMessage.User(
                                   content,
                                   attJson,
                                   injected,
                                   timestamp = System.currentTimeMillis(),
                                   quote = quoteJson
                                 )
                               )
                             )
                             .handleErrorWith(e => logger.warn(s"Failed to record user UiMessage: ${e.getMessage}"))
                           // attachmentsSaved（用户附件点击统一批 2026-10-08）：live 渲染腿
                           // 前端只有 data/preview、没有落盘 path —— Pop 面的点击开 Canvas
                           // 依赖 item.path（popArtifacts.openMediaItemClick / buildFileCard
                           // 的 `if (item.path)` 闸）。把与 ui.json 同形的 [{name,type,path}]
                           // 回传请求方连接，前端据此原位重渲用户媒体行。纯文本帧（无附件）
                           // 不发；旧前端未知帧类型 = handlers 无登记 = no-op，协议零破坏。
                             *> (if attJson.nonEmpty then
                                   wsSend(
                                     io.circe.Json.obj(
                                       "type" -> "attachmentsSaved".asJson,
                                       "sessionId" -> msgSessionId.asJson,
                                       "attachments" -> attJson.asJson
                                     )
                                   )
                                 else IO.unit)
                         else IO.unit) *> {
                          // 任务工具重做（2026-08-30）：打回语义退役——
                          // taskRefs/refs(refType=task) 的 processTaskReturns
                          // 路径整体移除。#303 D1: resolve non-task refs
                          // (file/document/html-element) into [引用: …]
                          // injection blocks; refType=task fail-open 跳过。
                          processRefs(json, blocks).flatMap { _ =>
                            // device-provenance 批：标记块前插为首块（正文块逐字不动；
                            // 会话显示面已在上方 appendUiMessages 落裸正文）。None =
                            // 未声明设备 ⇒ 逐字节现状。
                            val blocksList = deviceResolved.fold(blocks.toList)(
                              nebflow.bridge.DeviceProvenance.prependMarker(_, blocks.toList)
                            )
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
                      } // end deviceResolved flatMap (device-provenance 批)
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

  /**
   * F 步分发上下文:按帧把迁出臂所需的本类私有依赖打包给各域 handler
   * (引用与 eta 展开,构建零副作用)。
   */
  private def wsDispatchContext: WsDispatchCtx =
    WsDispatchCtx(
      logger = logger,
      sessionService = sessionService,
      agentService = agentService,
      configService = configService,
      configRef = configRef,
      sessionStore = sessionStore,
      sharedResources = sharedResources,
      mcpManager = mcpManager,
      sttServiceRef = sttServiceRef,
      textSearch = textSearch,
      sessionTextBuffers = sessionTextBuffers,
      sessionThinkingBuffers = sessionThinkingBuffers,
      sessionTurnStarts = sessionTurnStarts,
      replayPendingAsksImpl = replayPendingAsks,
      listAllPendingAsksImpl = listAllPendingAsks,
      applyPermissionUpgradeImpl = applyPermissionUpgrade,
      persistGlobalSafetyModeImpl = persistGlobalSafetyMode,
      removeRootAgentImpl = removeRootAgent,
      stopTeamSessionActorsImpl = stopTeamSessionActors,
      stopChildDelegateActorsImpl = stopChildDelegateActors,
      forwardInteractionAnswerImpl = forwardInteractionAnswer,
      isRootScopeSessionImpl = isRootScopeSession,
      compactThresholdInfoImpl = compactThresholdInfo,
      ensureAgentImpl = ensureAgent,
      maybeSessionKickImpl = maybeSessionKick,
      broadcastServerConfigImpl = () => broadcastServerConfig,
      persistThinkingConfigImpl = persistThinkingConfig,
      persistWorkScheduleImpl = persistWorkSchedule,
      persistMcpServerEnabledImpl = persistMcpServerEnabled,
      broadcastMcpServersUpdateImpl = () => broadcastMcpServersUpdate,
      sendAgentSessionListImpl = sendAgentSessionList,
      sendAgentSessionListByNameImpl = sendAgentSessionListByName,
      sendMemoryStatusImpl = sendMemoryStatus,
      sendToolsListImpl = sendToolsList,
      expandTildeImpl = expandTilde,
      wsBrowseEventImpl = wsBrowseEvent,
      browseResultFrameImpl = WebSocketRoutes.browseFrame,
      browseErrorFrameImpl = WebSocketRoutes.browseErrorFrame,
      browseEntryCapImpl = WebSocketRoutes.BrowseEntryCap,
      resolveExplorerBaseRootImpl = resolveExplorerBaseRoot,
      admitWorkOrRefuseImpl = admitWorkOrRefuse,
      handleUserTextImpl = handleUserText,
      deviceProvenanceImpl = deviceProvenanceOf,
      skipCurrentFreezeWindowImpl = () => skipCurrentFreezeWindow,
      executeAskImpl = executeAsk,
      executeSkillImpl = executeSkill,
      resolveRootSessionIdImpl = resolveRootSessionId,
      projectCreateBroadcastImpl = (json: io.circe.Json) => wsHub.broadcast(json)
    )
  end wsDispatchContext

  // ─────────── 来源设备标记（device-provenance 批，2026-10-23 作者令）───────────
  //
  // 作者令：消息来源标记从渠道级（微信/飞书）细化到设备级——「让你能知道是哪台
  // 设备来的，是否为我们设备列表里的设备」。本类是控制台腿的**唯一注入点**：
  //
  //   - 无 type 帧（输入框/队列/插件卡直投）与 `immediateInput` 帧（队列「立即
  //     发送」）两条腿在派发前经 [[deviceProvenanceOf]] 解析帧 `device` 键；
  //   - 标记只进模型面（`UserInput` blocks 首块 / `ImmediateInput` text 前缀），
  //     会话显示面（`.ui.json` 行、气泡）保持裸正文——source-marker relocation
  //     批（2026-10-01）的两 face 分离原样适用；
  //   - 帧不带 `device`（CLI `userMessage` / REST headless / 旧前端）⇒ None ⇒
  //     全部 face 逐字节不变（不声明不标注，不猜）。
  //
  // 纯函数面（解码/解析/渲染）在 [[nebflow.bridge.DeviceProvenance]]；本类只做
  // 花名册取数（neblink self + peers，Ref 读内存价）与接线。

  /** 花名册读数：self 身份 + peers 列表，映射为纯结构行。取数失败降级空册（未登记兜底，绝不冒认已登记）。 */
  private def deviceRosterEntries: IO[(Option[nebflow.bridge.DeviceRosterEntry], List[nebflow.bridge.DeviceRosterEntry])] =
    sharedResources.neblinkService match
      case None => IO.pure((None, Nil))
      case Some(svc) =>
        (
          svc.identity.map(i => Some(nebflow.bridge.DeviceRosterEntry(i.deviceId, i.deviceName))).attempt,
          svc.peers
            .map(ps => ps.map(p => nebflow.bridge.DeviceRosterEntry(p.deviceId, p.deviceName)))
            .attempt
        ).mapN((self, peers) =>
          (
            self.toOption.flatten,
            peers.getOrElse(Nil)
          )
        )

  /**
   * 帧的来源设备解析：`device` 键缺席/无 clientId ⇒ None（不标注）；在场 ⇒ 对照
   * 花名册出 [[ResolvedDevice]]。任何花名册异常都 fail-open 为空册（未登记），
   * 绝不影响消息本身的派发。
   */
  private def deviceProvenanceOf(json: io.circe.Json): IO[Option[nebflow.bridge.ResolvedDevice]] =
    nebflow.bridge.ClientDevice.fromFrame(json) match
      case None => IO.pure(None)
      case Some(client) =>
        deviceRosterEntries.map { (self, peers) =>
          val resolved = nebflow.bridge.DeviceProvenance.resolve(client, self, peers)
          logger.info(
            s"Device provenance: '${resolved.display}' (enrolled=${resolved.enrolled}) clientId=${client.clientId.take(8)}…"
          )
          Some(resolved)
        }

  /**
   * 热重启 draining 工作准入闸（hot-restart 批设计 §3.3，WS 侧 choke 点）：包裹
   * 工作型消息入口（userMessage / immediateInput——REST 的 handleMessagePublic
   * 同走本 handleMessage，故 REST 工作型路径一并覆盖）。draining 置位期间拒绝
   * 并回 workRefused 帧（503 + retryAfter 语义）；遗漏准入点的兜底 = 崩溃恢复
   * sweep（设计 R2）。非 draining 期间零开销旁路（行为与既有完全一致）。
   */
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
  /**
   * @param fromUser
   *   ② (2026-09-11, queue-direct-pass diagnosis §2): is this text a real
   *   human message? Required (no default) so every entry point states its
   *   origin. 真人口径（Nebula 代裁，可被作者推翻）= WS 直投（浏览器
   *   `immediateInput` / 无 type 帧）+ CLI `userMessage`；REST headless
   *   (`rest-turn`) 算程序 ⇒ false。The flag is threaded into
   *   [[dispatchUserText]] → `AgentCommand.ImmediateInput(fromUser = …)` so the
   *   idle judgement (`clientMessageId.isDefined || fromUser`) keeps the
   *   human turn free of an injection source.
   * @param device
   *   来源设备解析读数（device-provenance 批，2026-10-23）：帧 `device` 键对照
   *   花名册的读数；`None`（默认）= 客户端未声明 ⇒ 全部 face 逐字节不变。在场时
   *   标记只进模型面（ImmediateInput text 前缀），UiMessage 行保持裸正文。
   */
  private def handleUserText(
    sessionId: String,
    content: String,
    source: String,
    fromUser: Boolean,
    device: Option[nebflow.bridge.ResolvedDevice] = None
  ): IO[Unit] =
    if sessionId.nonEmpty && content.nonEmpty then
      // 输入框直通退役（2026-09-14 作者令）：输入框文本一律按普通消息投递 ——
      // 不再探 hub 的 pending AskUser 槽位，文本也不再成为卡片答案
      // （[[dispatchUserText]] = 唯一入队路径）。pending 卡保持 pending，
      // 只能在卡片上作答；headless REST (`rest-turn`) 与输入框同腿，形态不变。
      dispatchUserText(sessionId, content, source, fromUser, device)
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

  /**
   * Normal message dispatch: user bubble + immediate injection into the
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
  private def dispatchUserText(
    sessionId: String,
    content: String,
    source: String,
    fromUser: Boolean,
    device: Option[nebflow.bridge.ResolvedDevice] = None
  ): IO[Unit] =
    logger.info(s"User text ($source) for session $sessionId (${content.length} chars)") *>
      sessionStore.appendUiMessages(
        sessionId,
        List(UiMessage.User(content, Nil, timestamp = System.currentTimeMillis()))
      ) *>
      // Hard-recovery P4: if the target turn is wedged (Processing + idle >
      // SessionKickIdleSec), break it BEFORE queueing — the queued message
      // injects at the turn boundary the kick creates (user intent first).
      maybeSessionKick(sessionId, s"userMessage:$source") *>
      // device-provenance 批：标记只进模型面（text 前缀，BridgeOrigin.compose 同序
      // ——标记块、空行、正文）；上方 UiMessage 行保持裸正文（两 face 分离原律）。
      ensureAgent(sessionId)(ref =>
        ref ! AgentCommand.ImmediateInput(
          device.fold(content)(nebflow.bridge.DeviceProvenance.compose(_, content)),
          fromUser = fromUser
        )
      )

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
              .persistSkip(nebflow.shared.PathUtil.dataRoot, until)
              .handleErrorWith(e => logger.warn(s"Failed to persist freeze skip: ${e.getMessage}")) *>
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

  /**
   * #303 D1/D5: resolve unified refs (refType ≠ task) into [引用: …]
   * injection blocks appended to the user message (spec §2.3 ③). Pointer
   * semantics — source + anchor only, never content (D5, ≤ ~100 token).
   * refType=task refs are gone (任务工具重做 2026-08-30, 打回退役) — they
   * fall through as unknown and are skipped fail-open (never block the
   * message or the other refs).
   */
  private def processRefs(
    frame: io.circe.Json,
    blocks: scala.collection.mutable.ListBuffer[ContentBlock]
  ): IO[Unit] =
    val refs = frame.hcursor.downField("refs").as[List[io.circe.Json]].getOrElse(Nil)
    refs.foreach { ref =>
      val refType = ref.hcursor.downField("refType").as[String].getOrElse("")
      if refType != "task" then RefResolver.resolve(ref).foreach(block => blocks += ContentBlock.Text(block))
    }
    IO.unit

  /**
   * quote-message 一期（2026-10-09，spec §3.3）：帧 refs 里 refType==='chat-message'
   * 的首条 → 落盘 quote 快照 Json（UiMessage.User.quote）。形状（引擎不解释内部
   * 字段，原样落盘原样下发）：
   *   { refMessageId, excerpt ≤80, senderRole, senderName?, ts? }
   * 无 chat-message ref / 形态不合 ⇒ None（编码器缺席不落键，旧行字节形态不变）。
   * excerpt 一律过 RefResolver.sanitize 再截 80（引用块快照的防伪同源）。
   */
  private def quoteFromRefs(frame: io.circe.Json): Option[io.circe.Json] =
    val refs = frame.hcursor.downField("refs").as[List[io.circe.Json]].getOrElse(Nil)
    refs.find(r => r.hcursor.downField("refType").as[String].getOrElse("") == "chat-message").flatMap { ref =>
      val c = ref.hcursor
      val src = c.downField("source")
      val mid = RefResolver.sanitize(src.downField("messageId").as[String].getOrElse(""))
      if mid.isEmpty then None
      else
        val excerpt = RefResolver
          .sanitize(c.downField("content").downField("excerpt").as[String].getOrElse(""))
          .take(80)
        val role = RefResolver.sanitize(src.downField("senderRole").as[String].getOrElse("user"))
        val base = scala.collection.mutable.LinkedHashMap[String, io.circe.Json](
          "refMessageId" -> mid.asJson,
          "excerpt" -> excerpt.asJson,
          "senderRole" -> role.asJson
        )
        src
          .downField("senderName")
          .as[String]
          .toOption
          .map(RefResolver.sanitize)
          .filter(_.nonEmpty)
          .foreach(n => base("senderName") = n.asJson)
        c.downField("meta")
          .downField("ts")
          .as[Long]
          .toOption
          .filter(_ > 0)
          .foreach(ts => base("ts") = ts.asJson)
        Some(io.circe.Json.fromJsonObject(io.circe.JsonObject.fromIterable(base)))
      end if
    }

  end quoteFromRefs

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
        if sys.props.getOrElse("os.name", "").toLowerCase.contains("mac") then scala.sys.process.Process(cmd).!!
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
                          List(
                            UiMessage.Ai(
                              text,
                              None,
                              None,
                              Option.when(thinking.nonEmpty)(thinking),
                              System.currentTimeMillis()
                            )
                          )
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
                        end if
                      }
                  }
              }
          case _ => IO.unit
        end match

      case "askUser" =>
        // 案 B（双开缺陷批 2026-09-21，chain-askuserdup）：随行落盘 requestId ⇒ 历史
        // 恢复出的卡可 id 寻址（重放腿按 id 替换、askUserClosed 关卡可达）。旧行缺席
        // ⇒ None ⇒ 前端回落形态兜底去重腿（`chat.js sameAskCards` ②）。
        // 附件（askUser 历史恢复批 2026-10-02，chain-askuser-histattach）：帧
        // `attachments` 随行落盘 ⇒ 历史恢复腿读回后在卡上还原附件行（与 live 腿
        // `renderAskUser` 同构、单点位于 `.option-box`）。帧 → 落盘行的取键形态
        // = 伴生对象纯函数 `askUserRowFromFrame`（缺席 / 形态不合 / 空白 / 空列表
        // 一律回落 None ⇒ 旧载荷逐字节不变）。
        sharedResources.sessionStore.appendUiMessages(
          sessionId,
          List(WebSocketRoutes.askUserRowFromFrame(json))
        )

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
        // 未走信封助手:REST 注入体的 Option 匹配(parse 失败显式回 BadRequest),非 Null 回退同形,保持原样(2026-09-24)
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
   * Which AgentRegistry entries getActiveAgents should report as running.
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
      .traverseFilter(rec => TeamSessionRegistry.isBusy(rec.sessionId).map(busy => if busy then Some(rec) else None))
      .map(_ ++ inFlight)

  /**
   * 显示名契约批（2026-10-22 · 子智能体面板行显示名修复）：activeAgents 快照面
   * 的 delegate/SubTask 行名解析链——**DelegateRegistry.Record.title（主源，spawn
   * 落盘、跨重启存活）→ SubAgentTask.description（任务记录回填源）→ None**。
   *
   * 背景（作者实测批）：面板行「先长名后收缩成 session id」的根因 = 恢复脸
   * （`activeAgentEntryJson`）对 delegate 会话既不查 meta（delegate 从不过
   * createSession，index 恒无条目）也不查 displayName（注册点不设）⇒ 直落
   * sessionId 兜底；而 live 脸 agentId == sessionId，同 key 合并「快照为准」
   * 每次都把 live 长名行覆盖成短 id。两处持久面（DelegateRegistry / SubAgentTaskStore）
   * 本就持有批次标题，此处对齐为单一解析缝。
   *
   * - `AgentKind.Delegate`：registryTitle 优先（跨重启权威），storeDescription 回填。
   * - `AgentKind.SubTask`：DelegateRegistry 只装 delegate——只走 storeDescription。
   * - 其余 kind：None（既有三档链逐字不动）。
   *
   * 纯函数、零类依赖（`handleGetActiveAgents` 薄调用；IO 包装在调用方）。
   * Standalone（zero class deps）so the chain is directly unit-testable.
   */
  def delegateDisplayTitle(
    kind: AgentKind,
    registryTitle: Option[String],
    storeDescription: Option[String]
  ): Option[String] =
    val store = storeDescription.filter(_.trim.nonEmpty)
    if kind == AgentKind.Delegate then registryTitle.filter(_.trim.nonEmpty).orElse(store)
    else if kind == AgentKind.SubTask then store
    else None

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
   * `delegateTitle`（显示名契约批 2026-10-22）：delegate/SubTask 恢复行的
   * 批次标题（由调用方经 [[delegateDisplayTitle]] 解析）；注入 agentName 链的
   * sessionId 兜底**之前**——delegate 会话的 meta/displayName 恒缺席，无此档则
   * 面板恢复行退化成 sessionId（作者「收缩」症状）。缺省 None = 行为与旧版逐字节
   * 一致（向后兼容；node/team 域不受影响——其 meta/displayName 优先于本档）。
   *
   * Standalone (zero class deps) so the contract is directly unit-testable
   * (ActiveAgentsEntrySpec).
   */
  def activeAgentEntryJson(
    rec: AgentRecord,
    meta: Option[SessionMeta],
    retryCount: Option[Int] = None,
    taskTitle: Option[String] = None, // taskbadge batch (main-side): title resolved by the caller via the ledger
    delegateTitle: Option[String] = None, // 显示名契约批 2026-10-22: delegate/SubTask 行批次标题（delegateDisplayTitle 解析）
    bgWaiting: List[(String, String, Long)] =
      Nil // #752: (jobId, description, startedAtMs) — the session's own waiting-type bg tasks
  ): Json =
    Json.obj(
      "sessionId" -> rec.sessionId.asJson,
      "agentId" -> rec.sessionId.asJson,
      // agentName 四档链（显示名契约批 2026-10-22 扩为四档）：indexed meta（team/主会话既有
      // 归属）→ AgentRecord.displayName（Project 域注册点写入的 Flow Map 节点名 /
      // "dispatcher/<project>"——node-/dispatcher- 会话从不过 SessionStore.createSession，
      // index 恒无条目，缺此档刷新后 subagent 面板行回退 sessionId「node-xx 默认名」）
      // → delegateTitle（delegate/SubTask 恢复行的批次标题——DelegateRegistry.Record.title
      // 主源 / SubAgentTask.description 回填；修前此处直落 sessionId，面板行「收缩」根因）
      // → sessionId 兜底（既有行为）。
      "agentName" -> meta
        .flatMap(_.agentName)
        .orElse(rec.displayName)
        .orElse(delegateTitle)
        .getOrElse(rec.sessionId)
        .asJson,
      "rootSessionId" -> rec.rootSessionId.asJson,
      "kind" -> rec.kind.toString.asJson,
      "task" -> meta.map(_.name).getOrElse("").asJson,
      // 2026-08-22 缺口 2：快照自带可刷新恢复三字段（向后兼容——旧字段不动，
      // 旧前端无感）。status=AgentStatus toString（Idle/Processing/.../Error）；
      // startedAt epoch ms（0=unknown）；retryCount 来自 taskStore（缺省 0——
      // Ephemeral 不落 taskStore）。不加 agentStatus 变更广播（改动面大，裁定
      // 不做——快照轮询由前端按需刷新）。
      "status" -> rec.status.toString.asJson,
      "startedAt" -> rec.startedAt.asJson,
      "retryCount" -> retryCount.getOrElse(0).asJson,
      // #752 空闲细分（快照恢复路径的数据源；live 行由实时帧写 currentTool，快照
      // 字段合并时不降级实时值——前端 merge 规则）。currentTool = 在飞/最后工具名
      // （最近动作摘要）、lastActivityMs = agent 侧最近活动戳（长空闲徽标判据）。
      "currentTool" -> rec.currentToolName.getOrElse("").asJson,
      "lastActivityMs" -> rec.lastActivityMs.asJson,
      // Project attribution (2026-09-06 author ruling): Flow rows carry the
      // owning project's name next to the kind chip — muted plain text,
      // visually subordinate to the chip, never competing with the node name.
      // Absent (Root/direct delegates) → no badge at all.
      "project" -> rec.project.getOrElse("").asJson,
      // taskbadge batch 2026-09-27: sub-agents panel task attribution on the
      // snapshot face; taskId = the registration-time snapshot (dispatcher slot /
      // NodeDef), taskTitle = resolved by the caller via the unified ledger.
      // Empty string = no badge rendered (falsy contract, same as `project`).
      "taskId" -> rec.taskId.getOrElse("").asJson,
      "taskTitle" -> taskTitle.getOrElse("").asJson,
      // held-receipt 投递可靠性批（2026-10-21 · fix 3 可见化快照面）：pendingEventCount
      // = 被 HOLD 的已产出回执数（actor 权威快照）、unreadReceiptCount = 催注穷尽后
      // 的未读终态数（HeldDeliveryWatchdog 独占写）。空 = 0 → 前端 falsy 不渲染
      // （同 project/taskId 徽标契约）。
      "heldReceipts" -> rec.pendingEventCount.asJson,
      "unreadReceipts" -> rec.unreadReceiptCount.asJson,
      // #752 空闲细分：「等子代理」判据的另一半——barrier 上的 spawn 计数
      // （issue #31 Fix D 幻影屏障可见化的既有快照字段，随批补进快照出帧）。
      "outstandingSubagents" -> rec.outstandingSubagents.asJson,
      // #752 空闲细分：「等后台任务」——会话自有等待型任务（BgTaskRegistry
      // waitingBySession，调用方一次全表扫描传入）。[{taskId, description,
      // startedAt}]；空数组 = 无（前端 falsy 判 length）。启动戳毫秒，前端算时长。
      "bgWaiting" -> bgWaiting.map { case (jobId, description, startedAtMs) =>
        Json.obj(
          "taskId" -> jobId.asJson,
          "description" -> description.asJson,
          "startedAt" -> startedAtMs.asJson
        )
      }.asJson
    )

  // ============================================================
  // picker-trunc batch (main-side 2026-09-22): the browsePath frame
  // builders — pure core, owned by this object so `PickerBrowseFrameSpec`
  // can call them directly. The WS `browsePath` command wiring (the call
  // site that emits these frames) lives in WsFileOpsHandlers.handleBrowsePath,
  // which reaches them through the class-level seams (browseResultFrame /
  // browseErrorFrame / BrowseEntryCap).
  // ============================================================

  /**
   * How many entries a `browsePath` frame carries before `truncated` turns on —
   * the ONE ruler for that leg (was a literal `take(200)`).
   */
  private[gateway] val BrowseEntryCap: Int = 2000

  /**
   * picker-trunc (2026-09-22 author ruling, A+B+C in ONE batch) — the
   * `browsePath` success frame. Pure core: the spec calls it directly.
   *
   * A. **No silent truncation**: `total` is the full FILTERED entry count and
   *    `truncated` says whether the cap cut it.
   * B. `query` filters SERVER-side (case-insensitive substring on the entry
   *    name) BEFORE the cap; `query` is echoed back so the frontend can
   *    discard a frame that answers a stale filter string.
   */
  private[gateway] def browseFrame(dir: os.Path, displayPath: String, query: String, cap: Int): Json =
    val q = query.trim.toLowerCase
    // ONE listing pass: lexical order (pre-existing), then filter, then cap.
    val all = os
      .list(dir)
      .filter(os.isDir)
      .map(_.last)
      .toList
      .sorted
    val matched = if q.isEmpty then all else all.filter(_.toLowerCase.contains(q))
    val shown = matched.take(cap)
    Json.obj(
      "type" -> "browseResult".asJson,
      "path" -> displayPath.asJson,
      "entries" -> shown
        .map(n => Json.obj("name" -> n.asJson, "path" -> (dir / n).toString.asJson))
        .asJson,
      "total" -> matched.size.asJson,
      "truncated" -> (matched.size > shown.size).asJson,
      "query" -> query.asJson
    )

  end browseFrame

  /**
   * The `browsePath` failure frame (C): same shape, empty `entries`, plus a
   * typed `errorKind` (`invalid-path` / `not-a-directory` / `unreadable`).
   */
  private[gateway] def browseErrorFrame(displayPath: String, kind: String, msg: String, query: String): Json =
    Json.obj(
      "type" -> "browseResult".asJson,
      "path" -> displayPath.asJson,
      "entries" -> Json.arr(),
      "total" -> 0.asJson,
      "truncated" -> false.asJson,
      "query" -> query.asJson,
      "errorKind" -> kind.asJson,
      "error" -> msg.asJson
    )

  // ============================================================
  // askUser 历史恢复批 (2026-10-02, chain-askuser-histattach): the
  // `askUser` frame → persisted-row builder — pure core, owned by this
  // object so the paired spec can feed raw frame JSON. The WS
  // recording leg (`WebSocketRoutes#makeRecordingWsSend`, `case "askUser"`)
  // calls it exactly once, so the frame→row shape has ONE construction
  // point (the persistence leg) and stays disjoint from the restoral leg
  // (`WsSessionChatHandlers.handleGetHistory` → `SessionStore.getHistoryPage`
  // → `given Encoder[UiMessage]`).
  // ============================================================

  /**
   * `askUser` frame → the row written to `.ui.json`.
   *
   * Key-reading shape is the SAME loose fallback as the decoder
   * (`UiMessage`'s `case "askUser"`): absent / malformed ⇒ `None`;
   * blank `requestId` ⇒ `None`; empty `attachments` ⇒ `None` (dual of the
   * encoder's "emit only in presence, empty ⇒ no key" rule — an absent or
   * blank frame must produce byte-identical legacy rows).
   */
  private[gateway] def askUserRowFromFrame(frame: Json): UiMessage.AskUser =
    val hc = frame.hcursor
    val items = hc.downField("items").as[List[Json]].getOrElse(Nil)
    val askRid = hc.downField("requestId").as[String].toOption.filter(_.nonEmpty)
    val attachments = hc
      .downField("attachments")
      .as[Option[List[String]]]
      .toOption
      .flatten
      .filter(_.nonEmpty)
    UiMessage.AskUser(items, askRid, attachments)
end WebSocketRoutes
