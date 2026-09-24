package nebflow.gateway

import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.syntax.*
import io.circe.{Json, JsonObject, parser}
import nebflow.agent.{AgentCore, SharedResources}
import nebflow.core.*
import nebflow.core.daemon.{DaemonConfig, DaemonService, DaemonStore}
import nebflow.core.entity.{EntityLoader, NodeRoute}
import nebflow.core.flow.{FlowTreeRegistry, TreeCommand}
import nebflow.core.hotrestart.HealthPayload
import nebflow.core.presets.{ModelPreset, PresetFile, PresetStore}
import nebflow.core.project.*
import nebflow.core.schedule.FreezeSchedule.given
import nebflow.core.skill.SkillService
import nebflow.core.task.{FileTaskStore, TaskStore}
import nebflow.core.tools.NodeTools
import nebflow.llm.*
import nebflow.llm.providers.ModelListFaces
import nebflow.neblink.*
import nebflow.service.ConfigService
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.{Authorization, `Content-Type`}
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.typelevel.ci.{CIString, CIStringSyntax}

import scala.concurrent.duration.*

/**
 * REST API routes for CLI consumption.
 * These endpoints mirror the WebSocket message handlers but over HTTP.
 */
class RestApiRoutes(
  token: String,
  configRef: cats.effect.Ref[IO, NebflowServiceConfig],
  sharedResources: SharedResources,
  sessionStore: SessionStore,
  wsRoutes: WebSocketRoutes,
  neblinkService: Option[NeblinkService] = None,
  ttsService: Option[TtsService] = None,
  neblinkDiscovery: Option[nebflow.neblink.NeblinkDiscovery] = None,
  gatewayPort: Int = 8080,
  wsHub: WsHub = new WsHub,
  /**
   * R-1b conn-guard：presence WS 受理面 per-IP 看护 + /health/conn 读数源。
   * 缺省 = 全放行实例（既有测试构造点零改动）；生产由 GatewayMain 注入实配。
   */
  connGuard: ConnGuard = ConnGuard.disabled
):
  private val logger = nebflow.core.NebflowLogger.forName("nebflow.rest-api")

  /**
   * REST 域分发上下文(B 步起,仿 WebSocketRoutes.wsDispatchContext 先例):把
   * 迁出域 routes 所需的本类私有依赖打包给各域(引用与 eta 展开,构建零副
   * 作用);类私有方法本体不动,仍以单一实现留守本类。后续域按需增量补成员。
   */
  private val ctx: RestApiCtx =
    RestApiCtx(
      token = token,
      configRef = configRef,
      sharedResources = sharedResources,
      sessionStore = sessionStore,
      wsRoutes = wsRoutes,
      neblinkService = neblinkService,
      ttsService = ttsService,
      neblinkDiscovery = neblinkDiscovery,
      gatewayPort = gatewayPort,
      wsHub = wsHub,
      connGuard = connGuard,
      logger = logger,
      scanAgentPresetsImpl = () => scanAgentPresets(),
      computeResolvedFromImpl = computeResolvedFrom,
      scrubPresetRefsImpl = scrubPresetRefs,
      migrateLegacyModelsImpl = migrateLegacyModels,
      buildMountedTeamsJsonImpl = () => buildMountedTeamsJson(),
      isValidFlowNameImpl = isValidFlowName,
      isValidAgentNameImpl = isValidAgentName,
      dispatchSwitchImpl = dispatchSwitch,
      withAuthImpl = req => f => withAuth(req)(f),
      checkAuthImpl = checkAuth,
      socialErrorResponseImpl = socialErrorResponse,
      presencePeerDeviceIdImpl = presencePeerDeviceId,
      isKnownNetworkDeviceImpl = isKnownNetworkDevice,
      checkHttpBaseUrlImpl = checkHttpBaseUrl,
      friendErrImpl = friendErr,
      groupProxyResultImpl = groupProxyResult,
      rawBodyImpl = rawBody,
      encSegImpl = encSeg,
      neblinkServerUrlImpl = explicit => neblinkServerUrl(explicit),
      completeDeviceEnrollmentDetailedImpl = (ms, resolvedUrl, json, logtoRefresh, logtoIdToken, explicitUserAction) =>
        completeDeviceEnrollmentDetailed(ms, resolvedUrl, json, logtoRefresh, logtoIdToken, explicitUserAction)
    )

  /** 登录回调域(AuthRoutes)成员 (using ctx: RestApiCtx) 的解析锚点(恒等于上面的 ctx)。 */
  private given RestApiCtx = ctx

  def routes: HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      // Health check (P2-6 layered, 2026-08-25): `providers` = per-model health
      // (up / down:<reason>), `search` = Tier 2a standalone search API health
      // (unconfigured/up/down) — INDEPENDENT of model health, so "模型配额 DOWN
      // 但搜索 API 正常" is visible at a glance. `status` stays "ok" while the
      // gateway serves (the watchdog keys on HTTP 200).
      case GET -> Root / "health" =>
        // Payload construction is SINGLE-SOURCED in
        // `nebflow.core.hotrestart.HealthPayload.build` (hotupdate batch 2, G3): the
        // hot-restart door self-check (tier 3 "port-serving" / tier 4 "version-match")
        // reads the very same payload over HTTP, so the version a successor
        // advertises can never drift from what this endpoint serves. Fields,
        // field order and value semantics are unchanged (byte-compatible body).
        HealthPayload.build(sharedResources.healthMonitor).flatMap(Ok(_))

      // 连接面只读读数（R-1b conn-guard 批，设计件 §C④-4）：零凭据面（仅
      // 对端 IP + 计数 + 上限/超时口径 + fd 代理）。与 /health 同级无令牌门
      // （watchdog/作者探视用）；不含任何 header/body/token 内容。
      case GET -> Root / "health" / "conn" =>
        connGuard.snapshot.flatMap(s => Ok(ConnGuard.healthJson(s, ConnGuard.fdCount())))

      // Token consumption dashboard aggregate (2026-08-18): structured LLM usage
      // telemetry with dimension slicing.
      //   dim=provider|model|agent|hour|day (absent = totals only)
      //   from/to = epoch millis, inclusive lower / exclusive upper (both optional)
      //   provider/model/agent = exact-match filters, applied before grouping
      //     (dashboard D1: orthogonal to dim — all absent = unfiltered, and an
      //     empty value is treated as absent for backward compatibility)
      case req @ GET -> Root / "usage" / "aggregate" =>
        withAuth(req) {
          val params = req.uri.multiParams
          val dim = params.get("dim").flatMap(_.headOption)
          val from = params.get("from").flatMap(_.headOption).flatMap(_.toLongOption)
          val to = params.get("to").flatMap(_.headOption).flatMap(_.toLongOption)
          val provider = params.get("provider").flatMap(_.headOption).filter(_.nonEmpty)
          val model = params.get("model").flatMap(_.headOption).filter(_.nonEmpty)
          val agent = params.get("agent").flatMap(_.headOption).filter(_.nonEmpty)
          sharedResources.usageRecordStore
            .aggregate(dim, from, to, provider, model, agent)
            .flatMap(agg => Ok(agg.asJson))
        }

      // TTS 语音合成。门控（2026-09-20 收尾批）：原注记「无需 auth，内部调用」**已作废** ——
      // 唯一调用方是登录态 webui（`web/js/chat.js` `VoicePlayer._fetchTts`，本批已补带
      // `Authorization`）。与其余 REST 面同门：无令牌 403、带令牌照走业务体（`withAuth`
      // 纯套壳，业务体逐行保留）。门的判据在业务判据（`ttsService` 是否配置）**之前**。
      case req @ POST -> Root / "tts" =>
        withAuth(req) {
          ttsService match
            case None => NotFound(Json.obj("error" -> "TTS not configured".asJson))
            case Some(svc) =>
              req.as[Json].flatMap { body =>
                val text = body.hcursor.downField("text").as[String].getOrElse("")
                svc.synthesize(text).flatMap {
                  case Some(bytes) =>
                    IO.pure(
                      Response[IO](
                        status = Status.Ok,
                        headers = Headers(`Content-Type`(MediaType.audio.wav)),
                        body = Stream.emits(bytes).covary[IO]
                      )
                    )
                  case None => NotFound(Json.obj("error" -> "TTS synthesis failed".asJson))
                }
              }
        }

      // Generic command endpoint — mirrors WS messages
      case req @ POST -> Root / "command" =>
        withAuth(req) {
          req.as[Json].flatMap { payload =>
            val responseRef = cats.effect.Ref.unsafe[IO, Option[Json]](None)
            val wsSend = (json: Json) => responseRef.set(Some(json))
            wsRoutes.handleMessagePublic(payload.noSpaces, wsSend).flatMap { _ =>
              responseRef.get.flatMap {
                case Some(resp) => Ok(resp)
                case None => Ok(Json.obj("status" -> "ok".asJson))
              }
            }
          }
        }

      // Headless synchronous turn (P0 benchmark): send user text into a session
      // and block until the turn completes, returning the final assistant
      // message and tool trace. Logic lives in TurnEndpoint (testable);
      // this route adds auth + session validation.
      case req @ POST -> Root / "sessions" / sessionId / "turn" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val content = body.hcursor.downField("content").as[String].getOrElse("")
            val timeoutSec =
              body.hcursor.downField("timeoutSec").as[Int].getOrElse(1800).min(7200).max(1)
            if content.isEmpty then BadRequest(Json.obj("error" -> "content is required".asJson))
            else
              sessionStore.getSessionMeta(sessionId).flatMap {
                case None => NotFound(Json.obj("error" -> s"session not found: $sessionId".asJson))
                case Some(_) =>
                  TurnEndpoint.gated(sessionId) {
                    TurnEndpoint.runTurn(
                      wsHub,
                      sessionStore,
                      wsRoutes.dispatchHeadlessTurn,
                      sessionId,
                      content,
                      timeoutSec
                    )
                  }
              }
            end if
          }
        }

      // Session list. `includeUnindexed=1` (search scope) unions in on-disk-only
      // .ui.json sessions (delegate/subtask/dag sub-agents) that never enter the
      // index. The sidebar consumes this endpoint WITHOUT the param and must stay
      // index-only — that isolation is the reason this is a query param.
      case req @ GET -> Root / "sessions" =>
        withAuth(req) {
          val list =
            if req.params.get("includeUnindexed").contains("1") then sessionStore.listSessionsIncludeUnindexed
            else sessionStore.listSessions
          list.flatMap { sessions =>
            sessionStore.getActiveId.flatMap { activeId =>
              // 出口 overlay：逐会话 `safetyMode` 输出**有效档位** = 应用级全局持久值
              // （permshield S1 后已无会话覆盖面）。与 WS 出口共用同一个 helper——
              // CLI/QA 据此读到的是实际生效的档位，而不是 `_index.json` 的遗留值。
              sharedResources.overlaySessionList(sessions).flatMap { sessionsJson =>
                Ok(Json.obj("sessions" -> sessionsJson, "activeId" -> activeId.asJson))
              }
            }
          }
        }

      // Session history
      case req @ GET -> Root / "sessions" / sessionId / "history" =>
        withAuth(req) {
          sessionStore.getUiMessages(sessionId, 0, 0).flatMap { case (messages, total) =>
            Ok(Json.obj("messages" -> messages.asJson, "total" -> total.asJson, "sessionId" -> sessionId.asJson))
          }
        }

      // Create session
      case req @ POST -> Root / "sessions" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val name = body.hcursor.downField("name").as[String].getOrElse("New Session")
            val agentName = body.hcursor.downField("agentName").as[Option[String]].getOrElse(None)
            val folderId = body.hcursor.downField("folderId").as[Option[String]].getOrElse(None)
            // 2026-09-12 权限全局单一权威源（设计 §10 #16 / §13 #13）：**不再把全局档位
            // 写进会话 meta**。新会话的**有效档位** = 全局值，由 resolver（覆盖 ?? 全局）
            // 保证，与盘上键无关 —— 此前把全局值写进 meta 正是"会话各自持有权威档位"的
            // 承载面（索引损坏恢复路径把顶档写回落盘 ⇒ R1/T-2）。
            sessionStore
              .createSession(name, agentName = agentName, folderId = folderId)
              .flatMap { meta =>
                Ok(meta.asJson)
              }
          }
        }

      // Delete session
      case req @ DELETE -> Root / "sessions" / sessionId =>
        withAuth(req) {
          // 2026-09-13（permshield S1）：此处原为"清该会话的内存权限覆盖条目"。覆盖层
          // 删除后档位是应用级的 ⇒ 删除会话**不得**（也无从）改动档位，本清理点移除。
          sessionStore.deleteSession(sessionId) *>
            Ok(Json.obj("deleted" -> true.asJson))
        }

      // Config
      case req @ GET -> Root / "config" =>
        withAuth(req) {
          ConfigService.getConfig.flatMap { cfg =>
            ConfigService.isConfigured.flatMap { configured =>
              // P0（2026-08-30）：freezeState 进 REST 通道——前端刷新/重连若走
              // REST 拉配置（而非仅依赖 WS serverConfig 推送），也能读到当前
              // 冻结态（含 skip 语义：skipped=true/frozen=false）。
              for
                wsCfg <- sharedResources.freezeScheduleRef.get
                skipUntil <- sharedResources.freezeSkipUntilRef.get
                resp <- Ok(
                  Json.obj(
                    "config" -> cfg.asJson,
                    "configured" -> configured.asJson,
                    "freezeState" -> nebflow.core.schedule.FreezeSchedule
                      .freezeStateNode(wsCfg, skipUntil, System.currentTimeMillis())
                      .asJson
                  )
                )
              yield resp
            }
          }
        }

      // ── 权限模式（应用级全局单一来源；permshield S1 / 2026-09-13 作者重裁）──────
      // GET /api/safety —— 全局权限模式的**权威观测面**（QA/CLI 可 curl 判定）：
      //   defaultMode = 当前生效值（读不到有效值 ⇒ 启动默认顶档，见 GlobalSafety）；
      //   configured  = 配置文件里是否写了**可识别**的显式值（三档之一）。键缺失 /
      //                 类型不符 / 值不可识别 / 文件不可解析 一律 false。
      case req @ GET -> Root / "safety" =>
        withAuth(req) {
          val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
          (for
            mode <- nebflow.core.GlobalSafety.defaultMode
            rawValue <- IO
              .blocking {
                if !os.exists(configPath) then None
                else
                  io.circe.parser
                    .parse(os.read(configPath))
                    .toOption
                    .flatMap(_.hcursor.downField("safety").downField("defaultMode").as[String].toOption)
              }
              .handleErrorWith(_ => IO.pure(None))
          yield Json.obj(
            "defaultMode" -> nebflow.core.SafetyMode.toString(mode).asJson,
            "configured" -> rawValue.exists(v => nebflow.core.SafetyMode.fromWire(v).isDefined).asJson,
            "source" -> configPath.toString.asJson
          )).flatMap(Ok(_))
        }

      // PUT /api/safety/mode —— 全局权限模式的 REST 写入口（定向写，不走
      // `PATCH /api/config` 的全量快照语义，避免陈旧底稿回滚无关键）。
      // permshield S1 起这是**两个写入口之一**：另一个是 WS `setSafetyMode`（盾牌），
      // 二者共用同一个 `ConfigService.setSafetyDefaultMode` ⇒ 同一条持久路径。
      // 非三档显式值 ⇒ 400 且**不落盘**（不静默兜底）。
      case req @ PUT -> Root / "safety" / "mode" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val rawMode = body.hcursor.downField("mode").as[String].toOption.getOrElse("")
            nebflow.core.SafetyMode.fromWire(rawMode) match
              case None =>
                BadRequest(
                  Json.obj(
                    "error" -> s"unknown mode '$rawMode' — valid: confirm-edits, auto-edits, auto-all".asJson
                  )
                )
              case Some(mode) =>
                val modeStr = nebflow.core.SafetyMode.toString(mode)
                ConfigService.setSafetyDefaultMode(modeStr) *>
                  wsHub.broadcast(Json.obj("type" -> "configUpdated".asJson, "success" -> true.asJson)) *>
                  Ok(Json.obj("updated" -> true.asJson, "defaultMode" -> modeStr.asJson))
          }
        }

      // ── Canvas tabs 服务端持久化（F1 根治，2026-08-30）────────────────────
      // 作者报告「JVM 重启后标签页丢失」——浏览器 localStorage 不可靠（Safari
      // 无痕/多窗口 removeItem 竞态/清理）。服务端存档 ~/.nebflow/canvas_tabs.json
      // 与 sessionStore 同生命周期，JVM 重启保留；前端 restoreTabs 优先拉这里。
      // 契约（与 Frontend 同步）：GET → 200 {v:2,tabs:[...]} 或 404（无存档）；
      // PUT body {v:2,tabs:[...]} → 200 {ok:true}；非法输入 400 / 超限 413 不落盘。
      case req @ GET -> Root / "canvas-tabs" =>
        withAuth(req) {
          new CanvasTabStore(PathUtil.dataRoot / "canvas_tabs.json").load().flatMap {
            case Some(json) => Ok(json)
            case None => NotFound(Json.obj("error" -> "no canvas tabs archive".asJson))
          }
        }

      case req @ PUT -> Root / "canvas-tabs" =>
        withAuth(req) {
          // 读 body 有界：take(max+1) 后超限即 413，防大 payload 拉爆内存。
          val maxBytes = CanvasTabs.MaxBodyBytes
          req.body.take(maxBytes.toLong + 1).compile.toVector.flatMap { bytes =>
            CanvasTabs.parseBody(bytes.toArray, maxBytes) match
              case Left((status, msg)) =>
                val st = org.http4s.Status.fromInt(status).getOrElse(Status.BadRequest)
                IO.pure(Response[IO](status = st).withEntity(Json.obj("error" -> msg.asJson)))
              case Right(json) =>
                new CanvasTabStore(PathUtil.dataRoot / "canvas_tabs.json")
                  .save(json) *> Ok(Json.obj("ok" -> true.asJson))
                  .handleErrorWith { e =>
                    logger.error(s"canvas tabs save failed: ${e.getMessage}") *>
                      IO.pure(
                        Response[IO](status = Status.InternalServerError)
                          .withEntity(Json.obj("error" -> s"save failed: ${e.getMessage}".asJson))
                      )
                  }
          }
        }

      // ── Project + Flow Map REST（#28 阶段 0，前端 Project 面板 + Flow Map 视图数据源）──
      // 契约（同步 Frontend，与 NodeList 工具同 shape）：
      //   GET /projects → 200 {projects:[{name, workspace, agentFile, description, createdAt}]}
      //                   （归档项目不在列——ProjectStore.list 源头过滤，迁移方案 v2 §6.1）
      //   GET /projects/<name>/flow-map → 200 {nodes:[...], worktrees:[...], meta:{...}}
      //                   （2026-09-05 载荷收敛：节点条目=元数据 only——无 result 全文/摘要，
      //                   hasResult 标记 + description/taskPreview；结果全文按需取 ↓）
      //   GET /projects/<name>/flow-map/archive → 200 {batches:[...], ttlMs, count}
      //                   （裁定④「TTL 分开」批：归档面板数据源；显示窗 24h 内批次聚合）
      //   GET/PUT /projects/<name>/agent.md → 200 {content} / {saved:true}
      //   POST /projects/<name>/archive → 200 {archived:true, archivedAt}（§6.1 显式人工
      //                   归档：仅 project.json 打标记，零删除零移动；幂等；单程无取消）
      // 未挂载/不存在 → 404 {error}; 需 auth（withAuth）。
      // 注意：routes 挂载在 Router("/api" -> ...) 下，路径必须写相对段
      // （Root / "projects"），写 "api" 会双前缀 /api/api（QA P1②）。
      case req @ GET -> Root / "projects" =>
        withAuth(req) {
          ProjectStore
            .list()
            .map { projects =>
              Json.obj(
                "projects" -> projects
                  .map(p =>
                    Json.obj(
                      "name" -> p.name.asJson,
                      "workspace" -> p.workspace.asJson,
                      "agentFile" -> p.agentFile.asJson,
                      "description" -> p.description.asJson,
                      "createdAt" -> p.createdAt.asJson
                    )
                  )
                  .asJson
              )
            }
            .flatMap(Ok(_))
        }

      // POST /projects/<name>/archive — 归档（迁移方案 v2 §6.1）：显式人工动作唯一入口
      // （面板按钮/明确指令）。语义：仅 project.json 打归档标记（零删除零移动，workspace
      // 原样）；列表出口过滤（list 源头）→ 面板即时消失；startupMount 同源跳过 → 重启
      // 不自动挂载；运行中 ProjectActor/会话不强制拆除（registry 与 Mail 路由不受影响）。
      // 幂等：重复归档不重写。单程：本批无取消归档 API（手工删两键可恢复）。
      //
      // 项目级实时事件（tabrealtime 批 2026-09-17 · 作者裁定 (b) 方案 B / (e) 两身份事件）：
      // 成功分支经**既有** `wsHub.broadcast` 全连接广播一帧 `projectArchived`
      // （载荷逐字 §D-2：type / project / archivedAt；帧形由 ProjectActor.projectArchivedFrame
      // 单点生产）——与既有广播先例同族（:306 configUpdated / :1767 / :1792 / :1830），
      // 🔴 不自建第二套推送面、不改帧外壳语义。
      // 幂等重归档（已归档 → 不重写文件）同样返回 Right(at) ⇒ 同样发一帧，语义为
      // 「该项目的归档态此刻为真」；前端按名定点删除对「卡已不在」是 no-op（无重复渲染）。
      case req @ POST -> Root / "projects" / name / "archive" =>
        withAuth(req) {
          ProjectStore.archive(name).flatMap {
            case Left(err) => NotFound(Json.obj("error" -> err.asJson))
            case Right(at) =>
              // `*>`：先广播（IO[Unit]）再回响应（Ok(...) 本体已是 IO[Response]）
              wsHub.broadcast(ProjectActor.projectArchivedFrame(name, at)) *>
                Ok(Json.obj("archived" -> true.asJson, "archivedAt" -> at.asJson))
          }
        }

      case req @ GET -> Root / "projects" / name / "flow-map" =>
        withAuth(req) {
          ProjectRuntimeRegistry.get(name).flatMap {
            case None => NotFound(Json.obj("error" -> s"project '$name' not mounted".asJson))
            case Some(rt) => NodeTools.buildNodeListPayload(rt).flatMap(Ok(_))
          }
        }

      // GET /projects/<name>/flow-map/archive — Flow Archive 分批内容（裁定④「TTL 分开」批
      // 2026-09-07）：归档面板（右上角「已归档任务链」入口）的后端数据源。聚合返回显示窗
      // （24h = NodeEngine.TtlDisplayMs，与前端 ARCHIVE_TTL_MS 同值，服务端单点把关）内
      // 全部批次；成员 = NodePayload 元数据（结果全文走 nodes/<id>/result 按需通道，归档
      // 节点由 findNode 兜底命中）。选聚合不选按批拉取：面板需全量列表，24h 窗口规模小，
      // 与 flow-map 快照一次性拉取模式一致。批次按 completedAt 倒序。未挂载 → 404 {error}。
      case req @ GET -> Root / "projects" / name / "flow-map" / "archive" =>
        withAuth(req) {
          ProjectRuntimeRegistry.get(name).flatMap {
            case None => NotFound(Json.obj("error" -> s"project '$name' not mounted".asJson))
            case Some(rt) =>
              for
                arch <- rt.store.archiveSnapshot
                bt <- rt.store.archiveBatches
                now <- IO(System.currentTimeMillis())
                inWindow = bt.values.toList
                  .flatMap { meta =>
                    val members = meta.nodeIds.flatMap(id => arch.nodes.get(id)).toList.sortBy(_.createdAt)
                    val completedAt = members.flatMap(_.completedAt).foldLeft(0L)(math.max)
                    if members.isEmpty || (completedAt > 0L && now - completedAt > NodeEngine.TtlDisplayMs) then None
                    else
                      Some(
                        Json.obj(
                          "id" -> meta.id.asJson,
                          "archivedAt" -> meta.archivedAt.asJson,
                          "completedAt" -> completedAt.asJson,
                          "members" -> members.map(n => NodePayload.buildNodeJson(n, now)).asJson
                        )
                      )
                  }
                  .sortBy(j => -(j.hcursor.get[Long]("completedAt").toOption.getOrElse(0L)))
                resp <- Ok(
                  Json.obj(
                    "batches" -> inWindow.asJson,
                    "ttlMs" -> NodeEngine.TtlDisplayMs.asJson,
                    "count" -> inWindow.size.asJson
                  )
                )
              yield resp
          }
        }

      // GET /projects/<name>/flow-map/nodes/<nodeId>/result — 节点结果全文按需单点取
      // （2026-09-04 作者反馈「归档详情窗节点结果要能完整显示」；2026-09-05 载荷收敛后
      // 这是结果全文的两条按需通道之一，另一条 = NodeList(detail=<nodeId>) 工具参数，
      // 同源：FlowMapStore 内存 result = 加载时从 per-node 文件 results/<nodeId>.md
      // 水合的全文）。快照/WS 事件统一走 NodePayload.buildNodeJson 的元数据 only 载荷
      // （无 result 键），hasResult 标记驱动前端按需拉取。FlowMapStore.findNode 活动
      // 区优先、归档区兜底。未挂载/节点不存在 → 404 {error}（前端静默回退）。
      case req @ GET -> Root / "projects" / name / "flow-map" / "nodes" / nodeId / "result" =>
        withAuth(req) {
          ProjectRuntimeRegistry.get(name).flatMap {
            case None => NotFound(Json.obj("error" -> s"project '$name' not mounted".asJson))
            case Some(rt) =>
              rt.store.findNode(nodeId).flatMap {
                case None => NotFound(Json.obj("error" -> s"node '$nodeId' not found".asJson))
                case Some(n) =>
                  // 裁定②历史参照 REST 通道（20260907 上下文经济学批）：默认载荷仅
                  // blocked 态携带 blockedFeedback——非 blocked 终态的历史残留经本
                  // 端点按需取（条件键：无历史反馈的节点不带，键集零漂移）。
                  val histFeedback = n.blockedFeedback.toList.map(bf => "blockedFeedback" -> bf.asJson)
                  // 裁定⑤c 双层化：长描述仅 detail 通道（同 NodeList detail=，条件键）
                  val descLong = n.descriptionLong.toList.map(d => "descriptionLong" -> d.asJson)
                  Ok(
                    Json
                      .obj(
                        "id" -> n.id.asJson,
                        "name" -> n.name.asJson,
                        "status" -> n.status.asJson,
                        "result" -> n.result.asJson
                      )
                      .deepMerge(Json.obj((histFeedback ++ descLong)*))
                  )
              }
          }
        }

      // GET /projects/<name>/agent.md — 项目 agent 指令读取（#27「点击查看」；仿 team rules.md）。
      // E.3 双轨移除（裁定 13）：只读工作区根 AGENTS.md（canonical）——旧位
      // `.nebflow/Agent.md` 优先逻辑删除，迁移由 ProjectStore.load 统一执行
      // （本路由先 load 再读，旧位真文件/symlink 在 load 时已落根）。响应形状
      // {content} 不变（前端零改动）。URL 不变。
      case req @ GET -> Root / "projects" / name / "agent.md" =>
        withAuth(req) {
          ProjectStore.load(name).flatMap {
            case None => NotFound(Json.obj("error" -> s"project '$name' not found".asJson))
            case Some(pd) =>
              val p = os.Path(pd.workspace, PathUtil.dataRoot) / "AGENTS.md"
              if os.exists(p) then Ok(Json.obj("content" -> os.read(p).asJson))
              else NotFound(Json.obj("error" -> s"project '$name' has no AGENTS.md".asJson))
          }
        }

      // PUT /projects/<name>/agent.md — 保存。E.3 双轨移除（裁定 13）：只落工作区根
      // AGENTS.md（canonical）；旧位优先/穿透写逻辑删除（旧位由 ProjectStore.load
      // 迁移——本路由先 load，同请求内迁移先行完成）。响应形状 {saved:true} 不变。
      case req @ PUT -> Root / "projects" / name / "agent.md" =>
        withAuth(req) {
          ProjectStore.load(name).flatMap {
            case None => NotFound(Json.obj("error" -> s"project '$name' not found".asJson))
            case Some(pd) =>
              req.as[Json].flatMap { body =>
                val content = body.hcursor.downField("content").as[String].getOrElse("")
                IO.blocking {
                  val p = os.Path(pd.workspace, PathUtil.dataRoot) / "AGENTS.md"
                  os.makeDir.all(p / os.up)
                  AtomicJson.writeSync(p, content)
                } *> Ok(Json.obj("saved" -> true.asJson))
              }
          }
        }

      // Update config
      case req @ PATCH -> Root / "config" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val cfgStr = body.hcursor.downField("config").as[String].getOrElse(body.noSpaces)
            // 冻结修复（2026-08-27）：runtime 热更键以内存 ref 为权威（镜像 WS
            // updateConfig 分支）——PATCH 快照陈旧时不得回滚这些键。
            (
              sharedResources.freezeScheduleRef.get,
              sharedResources.thinkingConfigRef.get,
              sharedResources.toolResultTtlRef.get
            ).mapN { (wsCfg, thCfg, ttlCfg) =>
              Map[String, io.circe.Json](
                "workSchedule" -> wsCfg.asJson,
                "thinkingConfig" -> thCfg.asJson,
                "toolResultTtl" -> ttlCfg.asJson
              )
            }.flatMap { runtimeOverrides =>
              ConfigService.updateConfig(cfgStr, runtimeOverrides).flatMap {
                case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
                case Right(_) =>
                  sharedResources.providerRegistry
                    .reloadConfig(Some(sharedResources.sessionModelOverrides))
                    .attempt
                    .flatMap {
                      case Right(staleIds) =>
                        // #33: keep the persisted session meta in sync (see
                        // WebSocketRoutes updateConfig — same cleanup).
                        staleIds.traverse_(id => sessionStore.updateSessionModel(id, None)) *>
                          logger.info("Config hot-reloaded via REST")
                      case Left(e) => logger.warn(s"Config hot-reload failed: ${e.getMessage}")
                    } *> Ok(Json.obj("updated" -> true.asJson))
              }
            }
          }
        }

      // Models
      case req @ GET -> Root / "models" =>
        withAuth(req) {
          sharedResources.providerRegistry.getAllModels().flatMap { models =>
            Ok(Json.obj("models" -> models.map { case (ref, label) =>
              Json.obj("ref" -> ref.asJson, "label" -> label.asJson)
            }.asJson))
          }
        }

      // GET /models/capability-tags — list predefined capability tags
      case req @ GET -> Root / "models" / "capability-tags" =>
        withAuth(req) {
          Ok(
            Json.obj(
              "tags" -> List(
                Json.obj(
                  "key" -> "vision".asJson,
                  "label" -> "图片理解".asJson,
                  "description" -> "支持 image_url 图片输入".asJson
                )
              ).asJson
            )
          )
        }

      // GET /models/capabilities — list all models with their capability tags
      case req @ GET -> Root / "models" / "capabilities" =>
        withAuth(req) {
          Ok(nebflow.llm.ModelRegistry.loadForApi.asJson)
        }

      // PUT /models/capabilities — update a single model's capability tags
      case req @ PUT -> Root / "models" / "capabilities" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val providerId = body.hcursor.downField("providerId").as[String].getOrElse("")
            val modelId = body.hcursor.downField("modelId").as[String].getOrElse("")
            // B3 Phase 2: vision is tri-state — absent in the request body keeps
            // the existing annotation instead of collapsing to false.
            val visionOpt = body.hcursor.downField("vision").as[Option[Boolean]].toOption.flatten
            val capabilities = body.hcursor.downField("capabilities").as[List[String]].getOrElse(Nil)
            if providerId.nonEmpty && modelId.nonEmpty then
              val key = s"$providerId/$modelId"
              val current = nebflow.llm.ModelRegistry.loadForApi
              val updatedEntry = current.models.get(key) match
                case Some(existing) =>
                  existing.copy(
                    vision = visionOpt.orElse(existing.vision),
                    capabilities = capabilities
                  )
                case None =>
                  nebflow.llm.ModelRegistry.ModelEntry(
                    vision = visionOpt,
                    capabilities = capabilities
                  )
              val updatedModels = current.models + (key -> updatedEntry)
              nebflow.llm.ModelRegistry.save(updatedModels)
              // B3 restore semantics: an explicit vision=true annotation is user
              // intent and outranks runtime auto-demotion — clear the in-memory
              // override (and counters) too, otherwise effectiveVision stays
              // false until restart (stripImages blocks any image-bearing
              // success, so resetOnSuccess can never lift it).
              val clearRuntime =
                if visionOpt.contains(true) then
                  nebflow.llm.EmptyCompletionTracker.shared.clearOverride(providerId, modelId)
                else IO.unit
              clearRuntime *> Ok(Json.obj("status" -> "ok".asJson))
            else BadRequest(Json.obj("error" -> "providerId and modelId required".asJson))
            end if
          }
        }

      // Agents — global layer only（2026-09-05 08:40 作者裁定：面板数据源收敛）。
      // 旧三层聚合（global+team+flow）的 team/flow 两层是面板污染源——team/flow
      // 入口已随 sidebar flag 封存，域 agent 不应出现在全局面板。global 层以
      // agent.json 存在为准（EntityLoader.loadAgentFromDir 无 agent.json 即 None
      // 丢弃），天然只回 keeper 定义；.archived / 惰性残留目录不出现（面板收敛
      // spec 钉死）。layer 字段保留恒 "global"（agentManager.js 的 layer 过滤
      // 兼容；scope 字段仅旧 team/flow 条目携带，随两层删除自然消失）。
      case req @ GET -> Root / "agents" =>
        withAuth(req) {
          for
            globalAgents <- EntityLoader.listAgents()
            globalList = globalAgents.values.toList.map { a =>
              Json.obj(
                "name" -> a.name.asJson,
                "description" -> a.description.asJson,
                "displayName" -> a.name.asJson,
                "category" -> a.category.asJson,
                "layer" -> "global".asJson
              )
            }
            result <- Ok(Json.obj("agents" -> globalList.asJson))
          yield result
        }

      // Folders
      case req @ GET -> Root / "folders" =>
        withAuth(req) {
          val agentName = req.params.get("agent").getOrElse("Nebula")
          sessionStore.listFolders(agentName).flatMap { folders =>
            Ok(Json.obj("folders" -> folders.asJson))
          }
        }

      // MCP servers
      case req @ GET -> Root / "mcp" =>
        withAuth(req) {
          IO.blocking {
            val configPath = nebflow.llm.Config.DefaultConfigPath
            if os.exists(configPath) then
              parser
                .parse(os.read(configPath))
                .toOption
                .flatMap(_.hcursor.downField("mcpServers").as[Map[String, Json]].toOption)
            else None
          }.flatMap {
            case Some(servers) => Ok(Json.obj("mcpServers" -> io.circe.Json.fromFields(servers)))
            case None => Ok(Json.obj("mcpServers" -> Json.obj()))
          }
        }

      // Memory
      case req @ GET -> Root / "memory" =>
        withAuth(req) {
          val scope = req.params.get("scope").getOrElse("agent")
          sessionStore.getActiveMeta.flatMap { metaOpt =>
            val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
            val folderId = metaOpt.flatMap(_.folderId).getOrElse("")
            val content = scope match
              case "user" => nebflow.service.MemoryStore.loadUserMemory.getOrElse("")
              case "agent" => nebflow.service.MemoryStore.loadAgentMemory(agentName).getOrElse("")
              case _ => ""
            Ok(Json.obj("scope" -> scope.asJson, "content" -> content.asJson))
          }
        }

    } <+> NeblinkRoutes.routes(ctx) <+> SocialRoutes.routes(ctx)

  // ===== 好友域上游错误的单一判据（2026-09-11 boot 快照修复，R3(a)） =====

  /**
   * 本网关是否配置了 NebLink（server 址存在）？**live 读 config ref**
   * （同既有先例 `neblinkServerUrl` / `ms.relayTunnelOpt`，不得引入新的 boot
   * 快照）。未配置 ⇒ 好友域维持 `404 NebLink not enabled`，前端
   * `errKind='neblinkOff'` 保持可表达（其 retry 只对「已配置但暂时失败」有意义）。
   */
  private def neblinkConfigured: IO[Boolean] =
    neblinkService match
      case Some(ms) => ms.neblinkConfig.map(_.neblinkServer.isDefined)
      case None => IO.pure(false)

  /**
   * 好友域上游失败的**单一**应答判据。三个渲染上游 `Left` 的落点共用它：
   * `friendResult` / `friendResultRaw` / `GET /friends` 内联（其余好友路由全部
   * 经前两个 helper 汇聚，禁逐处复制粘贴分叉）。
   *
   *  - `"Not logged in"`（`FriendService.withClient`：登出 / 从未 enroll）
   *    **且已配置** ⇒ **401** + `code=neblink_not_logged_in`。
   *    **有意与 withAuth 的 403 分化**（2026-09-11 复核 N4）：网关自身鉴权缺失
   *    是 403 `Unauthorized`，NebLink 会话缺失是 401；同一端点族的两种
   *    「未认证」靠 `code` 字段消歧，**不要「统一」掉**（前端 401/403 都映射
   *    到 auth → 重登引导，语义一致）。
   *  - `"Not logged in"` **且未配置** ⇒ **404** `NebLink not enabled`
   *    （与修前 wire 契约逐字一致——缺了这条就是净回归：前端把 502 认成
   *    `retryable`、重试恒无效）。
   *  - 其余上游错误 ⇒ **502**（不变）。🟡 **例外（rcptcode 批，2026-09-20）**：好友
   *    **发送**路由（`POST /friends/{id}/messages`）已改走「保留状态码」通道
   *    （`sendAsUserWithStatus` + `groupProxyResult`）⇒ 该腿的上游 4xx/5xx **逐字**
   *    到达客户端、**不经本判据**；其余好友路由（列表/请求/备注/拉黑/已读/搜索）
   *    **继续**走本判据（是否推广是另一刀）。
   */
  private def friendErr(err: String): IO[Response[IO]] =
    if err != "Not logged in" then BadGateway(Json.obj("error" -> err.asJson))
    else
      neblinkConfigured.flatMap {
        case true =>
          // 显式构造（http4s 的 `Unauthorized(...)` 有 WWW-Authenticate 重载，
          // 直接传 Json 会命中它）——不带 WWW-Authenticate：这不是 HTTP 层面的
          // 401 challenge，是 NebLink 会话缺失的应用层状态。
          IO.pure(
            Response[IO](Status.Unauthorized)
              .withEntity(Json.obj("error" -> "Not logged in".asJson, "code" -> "neblink_not_logged_in".asJson))
          )
        case false =>
          NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
      }

  /**
   * 请求体**逐字**取原文（代理腿专用；空体 ⇒ `""`）。
   *
   * 用 `bodyText.compile.string` 而**不**用 `req.as[Json]`：后者把 body 解析成 AST
   * 再序列化回去会重排键 / 丢未知键 / 改数字字面量 ⇒ 上游收到的字节与客户端发的不
   * 同形。代理腿的职责是搬运字节，不是理解它。
   */
  private def rawBody(req: Request[IO]): IO[String] =
    req.bodyText.compile.string

  /**
   * 上游 `(status, body)` ⇒ 本网关响应：**状态码逐字**，体优先 JSON 解析。
   *
   * 🔴 禁吞：既不把上游 4xx 折成 500 / 502，也不把错误折成「空成功」——群域三码
   * （`group_not_found` / `group_disbanded` / `not_member`）必须原样到达客户端。
   * 非 JSON 体（网关/代理层注入的 HTML 错误页等）包成 `{"error":<原文>}`：既保住
   * 可判读性，又不让一次体解析失败把响应升级成 500。空体保持空体（不透传伪实体）。
   */
  private def groupProxyResult(result: Either[String, (Int, String)]): IO[Response[IO]] =
    result match
      case Left(err) => friendErr(err)
      case Right((code, body)) =>
        val status = Status.fromInt(code).getOrElse(Status.BadGateway)
        IO.pure(
          if body.isBlank then Response[IO](status)
          else
            Response[IO](status).withEntity(
              parser.parse(body).getOrElse(Json.obj("error" -> body.asJson))
            )
        )

  /**
   * 路径段编码（代理腿转发用）：避免上游路径被段内容改写（段内 `/`、`?` 注入）。
   * 与 `NeblinkClient.enc` 同法，只把 `+` 归一成 `%20`（`URLEncoder` 的
   * `application/x-www-form-urlencoded` 口径在路径段里会变成字面 `+`）。
   */
  private def encSeg(s: String): String =
    java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

  // ===== WebSocket Presence Server Endpoint =====

  /**
   * Accepts incoming WS presence connections from NebLink peers.
   *
   * The peer's device info arrives as handshake headers and/or query params on
   * the WS upgrade request (A1, 2026-09-20: deviceId moved to the header; see
   * [[presencePeerDeviceId]]).
   * Once the WS is established:
   *   - Server sends heartbeat pings every 10s.
   *   - Server responds to client pings with pongs.
   *   - On disconnect, the peer is removed from the peer list.
   *
   * This is a separate HttpRoutes value because it needs WebSocketBuilder2,
   * which is only available inside `withHttpWebSocketApp` in GatewayMain.
   */
  def presenceWsRoutes(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] = PresenceRoutes.routes(wsb, ctx)

  // ── Preset helpers ──────────────────────────────────────

  /** All agent.json paths across the three layers. */
  private def allAgentJsonFiles(): List[os.Path] =
    val root = PathUtil.dataRoot
    def agentJsons(parent: os.Path): List[os.Path] =
      if !os.exists(parent) then Nil
      else os.list(parent).filter(os.isDir).map(_ / "agent.json").filter(os.exists).toList
    val standalone = agentJsons(root / "agents")
    val teamAgents =
      if !os.exists(root / "teams") then Nil
      else os.list(root / "teams").filter(os.isDir).flatMap(t => agentJsons(t / "agents")).toList
    val flowAgents =
      if !os.exists(root / "flows") then Nil
      else os.list(root / "flows").filter(os.isDir).flatMap(f => agentJsons(f / "agents")).toList
    standalone ++ teamAgents ++ flowAgents

  /**
   * Scan all agent.json files and build a map of agentName → presetName (or null
   * if no preset field). Used by GET /presets to show which agents reference
   * which presets.
   *
   * panelscheme 批（2026-09-21）：映射的是**有效**引用（SchemePolicy 名称策略）——
   * 可设两类（Nebula/任务分发器）= 自有原始引用；kernel/general = 继承根
   * （Nebula/project-dispatcher）的当前引用；其余 agent 引擎已忽略其存储引用 →
   * null（usedBy 计数不再把「死数据」算进引用者）。
   */
  private def scanAgentPresets(): Map[String, Option[String]] =
    val raw: Map[String, Option[String]] = allAgentJsonFiles().flatMap { path =>
      parser.parse(os.read(path)).toOption.flatMap { json =>
        val name = json.hcursor
          .downField("name")
          .as[String]
          .toOption
          .getOrElse((path / os.up).last) // fall back to directory name
        val preset = json.hcursor.downField("preset").as[Option[String]].toOption.flatten
        Some(name -> preset)
      }
    }.toMap
    raw.map { (name, own) =>
      val (effPreset, _) = nebflow.core.presets.SchemePolicy.effectiveRefs(name, own, None)
      name -> effPreset
    }

  end scanAgentPresets

  /**
   * Determine the resolvedFrom value for an agent by reading the raw agent.json.
   * Returns "preset" | "legacy-model" | "default-preset" | "global".
   *
   * panelscheme 批（2026-09-21）名称策略感知：非可设两类（kernel/general/其余）
   * 的存储 preset/model 引用引擎已忽略——kernel/general 的 AgentDef.preset 携带
   * 继承根（Nebula/project-dispatcher）的引用名，按引用是否存在如实报告；其余
   * 不再做 legacy-model 探测（那会把「已忽略的死数据」误报为生效来源，误触发
   * 前端迁移横幅）。可设两类走既有逻辑逐字不变（回归红线）。
   */
  private def computeResolvedFrom(preset: Option[String], agentName: String): String =
    val store = new PresetStore()
    def defaultOrGlobal: String =
      val file = store.load()
      if file.presets.get(file.defaultPreset).exists(p => p.preferred.isDefined || p.fallbacks.nonEmpty) then
        "default-preset"
      else "global"
    if !nebflow.core.presets.SchemePolicy.SettableAgents.contains(agentName) then
      preset match
        case Some(p) =>
          if store.load().presets.contains(p) then "preset" else defaultOrGlobal
        case None => defaultOrGlobal
    else
      // If AgentDef has a preset, it was resolved from preset (or dangling → fallback)
      if preset.isDefined then
        val file = store.load()
        if file.presets.contains(preset.get) then "preset"
        else
          // Dangling preset — check if there's a legacy model
          EntityLoader.findAgentDir(agentName).unsafeRunSync() match
            case Some(dir) =>
              val json = parser.parse(os.read(dir / "agent.json")).toOption.getOrElse(Json.obj())
              val model = json.hcursor.downField("model").as[Option[nebflow.shared.AgentModelConfig]].toOption.flatten
              if model.exists(m => m.preferred.isDefined || m.fallbacks.nonEmpty) then "legacy-model"
              else defaultOrGlobal
            case None => "global"
      else
        // No preset — check legacy model
        EntityLoader.findAgentDir(agentName).unsafeRunSync() match
          case Some(dir) =>
            val json = parser.parse(os.read(dir / "agent.json")).toOption.getOrElse(Json.obj())
            val model = json.hcursor.downField("model").as[Option[nebflow.shared.AgentModelConfig]].toOption.flatten
            if model.exists(m => m.preferred.isDefined || m.fallbacks.nonEmpty) then "legacy-model"
            else defaultOrGlobal
          case None => "global"

    end if

  end computeResolvedFrom

  /**
   * Remove the `preset` field from all agent.json files that reference the given
   * preset name. Called when a preset is deleted so agents fall back to the
   * default preset instead of holding a dangling reference.
   */
  private def scrubPresetRefs(presetName: String): IO[Unit] =
    IO.blocking {
      allAgentJsonFiles().foreach { path =>
        val content = os.read(path)
        parser.parse(content) match
          case Right(json) =>
            json.hcursor.downField("preset").as[Option[String]].toOption.flatten match
              case Some(p) if p == presetName =>
                val updated = json.asObject
                  .map(obj => Json.fromFields(obj.toMap.removed("preset")))
                  .getOrElse(json)
                if updated != json then AtomicJson.writeSync(path, updated.noSpaces)
              case _ => ()
          case Left(_) => () // skip unparseable file
      }
    }

  /**
   * Migrate per-agent legacy model configs to named presets.
   * Groups agents by model-config fingerprint, creates a preset per group
   * (mig-<n>), writes the preset reference, and removes the legacy model field.
   * Agents with empty model configs ({preferred: null, fallbacks: []}) are
   * skipped (treated as "no config" — they already use the default preset).
   */
  private def migrateLegacyModels(agentNames: List[String]): IO[Response[IO]] =
    IO.blocking {
      val store = new PresetStore()
      val file = store.load()
      // Load each agent's raw model config
      val agentsWithConfig = agentNames.flatMap { name =>
        EntityLoader.findAgentDir(name).unsafeRunSync() match
          case None => None
          case Some(dir) =>
            parser.parse(os.read(dir / "agent.json")).toOption.flatMap { json =>
              val model = json.hcursor.downField("model").as[Option[nebflow.shared.AgentModelConfig]].toOption.flatten
              // Only migrate non-empty configs
              if model.exists(m => m.preferred.isDefined || m.fallbacks.nonEmpty) then Some((name, dir, model.get))
              else None
            }
      }
      // Group by fingerprint (preferred + fallbacks)
      def fingerprint(m: nebflow.shared.AgentModelConfig): String =
        s"${m.preferred.getOrElse("")}|${m.fallbacks.mkString(",")}"
      val groups = agentsWithConfig.groupBy { case (_, _, m) => fingerprint(m) }
      // Generate preset names (mig-<n>, avoiding collisions with existing)
      var migN = 1
      val existingNames = file.presets.keySet
      val newPresets = scala.collection.mutable.Map.empty[String, ModelPreset]
      val agentToPreset = scala.collection.mutable.Map.empty[String, String]
      groups.toList.sortBy(_._1).foreach { (fp, agents) =>
        val model = agents.head._3
        // Skip if this fingerprint already matches an existing preset
        val existingMatch =
          file.presets.values.find(p => p.preferred == model.preferred && p.fallbacks == model.fallbacks)
        val presetName = existingMatch match
          case Some(p) => p.name
          case None =>
            var name = s"mig-$migN"
            while existingNames.contains(name) || newPresets.contains(name) do
              migN += 1
              name = s"mig-$migN"
            migN += 1
            val agentList = agents.map(_._1).mkString(", ")
            val preset = ModelPreset(
              name = name,
              description = s"Auto-migrated from: $agentList",
              preferred = model.preferred,
              fallbacks = model.fallbacks
            )
            newPresets += (name -> preset)
            name
        agents.foreach { (name, _, _) => agentToPreset += (name -> presetName) }
      }
      // Write: update presets file + update each agent.json
      val updatedFile = file.copy(presets = file.presets ++ newPresets)
      store.save(updatedFile)
      agentToPreset.toList.foreach { (name, presetName) =>
        EntityLoader.findAgentDir(name).unsafeRunSync() match
          case Some(dir) =>
            val jsonPath = dir / "agent.json"
            parser.parse(os.read(jsonPath)) match
              case Right(json) =>
                // Remove model, add preset
                val withoutModel = json.asObject
                  .map(obj => Json.fromFields(obj.toMap.removed("model")))
                  .getOrElse(json)
                val updated = withoutModel.deepMerge(Json.obj("preset" -> presetName.asJson))
                AtomicJson.writeSync(jsonPath, updated.noSpaces)
              case Left(_) => ()
          case None => ()
      }
      // Build response data (plain values, not IO)
      (agentToPreset.toList, newPresets.values.toList)
    }.flatMap { (migrated, createdPresets) =>
      val migratedAgents = migrated.map { (name, preset) =>
        Json.obj("agent" -> name.asJson, "preset" -> preset.asJson)
      }
      Ok(
        Json.obj(
          "migratedAgents" -> migratedAgents.asJson,
          "createdPresets" -> createdPresets.map(_.asJson).asJson
        )
      )
    }.handleErrorWith(e => InternalServerError(Json.obj("error" -> s"Migration failed: ${e.getMessage}".asJson)))

  /**
   * Build mounted teams JSON for the frontend (GET /api/teams/mounted).
   *  Reads from live TeamSessionRegistry runtime state. Each team is a card with
   *  agent tiles showing status.
   */
  private def buildMountedTeamsJson(): IO[Json] =
    for
      teamsMap <- nebflow.core.flow.TeamSessionRegistry.listMountedTeams
      teams <- EntityLoader.listTeams()
      teamsList <- teamsMap.toList.sortBy(_._1).traverse { (instanceName, agents) =>
        val teamDefOpt = teams.get(instanceName)
        // Only render agents declared in team.json (lead + members). Delegate /
        // SubTask sub-agents are no longer registered in TeamSessionRegistry
        // (no Mail identity), so no ghost tiles can appear here; the filter
        // remains as defense-in-depth. Fall back to all agents when the team
        // definition is missing (legacy behavior).
        val memberNames = teamDefOpt.map(td => (td.lead :: td.members).toSet)
        for
          agentsJson <- agents
            .filter((name, _) => memberNames.forall(_.contains(name)))
            .traverse { (agentName, sid) =>
              nebflow.core.flow.TeamSessionRegistry.isBusy(sid).map { busy =>
                Json.obj(
                  "name" -> agentName.asJson,
                  "sessionId" -> sid.asJson,
                  "status" -> (if busy then "running" else "idle").asJson,
                  "manager" -> teamDefOpt.exists(_.lead == agentName).asJson
                )
              }
            }
          // Team Manager task tool (#D 2026-08-25): the team panel renders a
          // Tasks section from this array (flowTeams.js buildTasksSection,
          // frontend phase-2 contract A1: id/subject/status/blockedBy/blocks).
          // Read straight from the team task store directory (scope key
          // "team:<name>") — empty array for teams with no tasks yet.
          tasks <- FileTaskStore.list(TaskStore.teamScopeKey(instanceName))
        yield Json.obj(
          "name" -> instanceName.asJson,
          "type" -> "team".asJson,
          "agents" -> agentsJson.asJson,
          "tasks" -> tasks.asJson
        )
        end for
      }
    yield teamsList.asJson

  // ── Flow editor helpers ──────────────────────────────────

  private def isValidFlowName(name: String): Boolean =
    name.nonEmpty && name.matches("^[a-zA-Z0-9][a-zA-Z0-9._-]*$") && !name.contains("..")

  private def isValidAgentName(name: String): Boolean =
    name.nonEmpty && name.matches("^[a-zA-Z0-9][a-zA-Z0-9._-]*$") && !name.contains("..")

  /**
   * 令 1 派发开关的 REST 实现单点（`/plugins/:name/enable|disable`）。写 `plugins
   * .dispatch.<name>.authorEnabled`（作者意图层，durable）+ 一条 append-only 审计；
   * **不影响内容信任面** ⇒ 在飞节点零影响。
   */
  private def dispatchSwitch(name: String, enable: Boolean): IO[Response[IO]] =
    if !isValidAgentName(name) then BadRequest(Json.obj("error" -> "Invalid plugin name".asJson))
    else
      nebflow.core.plugin.PluginDispatchPolicy.setAuthorEnabled(name, enable, "panel/rest").flatMap {
        case Right(_) =>
          Ok(
            Json.obj(
              "ok" -> true.asJson,
              "message" -> (s"Plugin '$name' dispatch ${if enable then "enabled" else "disabled"} — " +
                "affects FUTURE dispatches only; nodes already dispatched keep their plugin grant " +
                "(content trust is untouched; use /revoke to withdraw content trust).").asJson
            )
          )
        case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
      }

  private def withAuth(req: Request[IO])(f: => IO[Response[IO]]): IO[Response[IO]] =
    if checkAuth(req) then f
    else Forbidden(Json.obj("error" -> "Unauthorized".asJson))

  /**
   * Error-code mapping for the social-channel endpoints (arch §7.3):
   * `400 invalid_field` / `400 unknown_channel` / `403 secret_mode` / `500 io`.
   * A credential-storage failure is a 403 with the field named — it is never
   * folded into a generic 500, and never reported as a success.
   *
   * Returns the response in effect (`IO`), not a bare value: the http4s dsl
   * constructors already produce `F[Response[F]]`, so wrapping them here and
   * de-wrapping at the call site would be a pointless round trip.
   */
  private def socialErrorResponse(err: nebflow.social.SocialChannels.Failure): IO[Response[IO]] =
    import nebflow.social.SocialChannels.Failure
    err match
      case Failure.UnknownChannel(id) =>
        BadRequest(Json.obj("error" -> "unknown_channel".asJson, "channel" -> id.asJson))
      case Failure.InvalidField(field, reason) =>
        BadRequest(Json.obj("error" -> "invalid_field".asJson, "field" -> field.asJson, "reason" -> reason.asJson))
      case Failure.SecretMode(field, reason) =>
        Forbidden(Json.obj("error" -> "secret_mode".asJson, "field" -> field.asJson, "reason" -> reason.asJson))
      case Failure.Io(reason) =>
        InternalServerError(Json.obj("error" -> "io".asJson, "reason" -> reason.asJson))

  /**
   * Identity claimed by the peer opening a presence WS upgrade.
   *
   * A1 (2026-09-20 device-face hardening batch): the handshake HEADER
   * ([[nebflow.neblink.Protocol.DeviceHeader]] — the same channel
   * [[verifyPeerAccess]] already reads on the REST peer face) is the primary
   * carrier, so deviceId stops travelling in the URL. The legacy `?deviceId=`
   * query param stays as the FALLBACK: dialers built before the change send
   * only that, and dropping it would refuse every existing peer.
   */
  private[gateway] def presencePeerDeviceId(req: Request[IO]): String =
    // Fully qualified on purpose: `org.http4s._` (imported after
    // `nebflow.neblink._`) also defines a `Protocol`, so the bare name is
    // ambiguous at this call site.
    req.headers
      .get(CIString(nebflow.neblink.Protocol.DeviceHeader))
      .map(_.head.value)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(req.params.getOrElse("deviceId", ""))

  /**
   * Network-membership check by device ID. True when the caller claims a
   * deviceId we discovered via the NebLink Server (a member of our networkId),
   * the request originates from a private/LAN address, AND that peer is still
   * FRESH (within the online window, see [[NeblinkService.isPeerOnline]]).
   *
   * A2 (2026-09-20 device-face hardening batch): the freshness leg is the new
   * half. A peer id is not a secret (it is published, see the A1 half of the
   * same batch) and the peer map deliberately KEEPS rows for peers the server
   * has flagged offline (`lastSeen = 0`, `NeblinkService.applyServerPeerStatus`
   * :374), so "is a known deviceId" alone stayed true forever after a peer went
   * away — including after DHCP handed its old address to a different host (the
   * address-reuse row of the threat table). The freshness predicate is REUSED,
   * not re-derived: [[NeblinkService.isPeerOnline]] with the configured
   * `syncIntervalSec`, exactly as documented there (floor 90s = the server's
   * own online TTL, 2x the sync interval).
   */
  private def isKnownNetworkDevice(
    ms: NeblinkService,
    claimedDeviceId: String,
    remoteIp: String
  ): IO[Boolean] =
    if claimedDeviceId.isEmpty || !isPrivateLanIp(remoteIp) then IO.pure(false)
    else
      for
        peers <- ms.peers
        cfg <- ms.neblinkConfig
      yield isFreshKnownPeer(peers, claimedDeviceId, System.currentTimeMillis(), cfg.syncIntervalSec)

  /**
   * Pure form of the membership + freshness leg, so the criterion is testable
   * without a live [[NeblinkService]] (see `PeerCriterionFreshnessSpec`).
   */
  private[gateway] def isFreshKnownPeer(
    peers: List[PeerInfo],
    claimedDeviceId: String,
    nowMs: Long,
    syncIntervalSec: Int
  ): Boolean =
    peers.exists(p => p.deviceId == claimedDeviceId && NeblinkService.isPeerOnline(p, nowMs, syncIntervalSec))

  /**
   * Private addresses a LAN-direct P2P peer can legitimately come from:
   * loopback, RFC1918 (10/8, 172.16/12, 192.168/16) and IPv6 `::1`.
   *
   * A2 (2026-09-20 device-face hardening batch): IPv4 link-local
   * `169.254.0.0/16` and IPv6 link-local `fe80::/10` are REMOVED here. They are
   * not "private LAN" ranges (RFC1918/loopback) at all, and any host on the wire
   * may self-assign them — so they let a caller satisfy "came from a private
   * address" without ever being part of the trusted network. The legitimate
   * private ranges below are unchanged (zero narrowing beyond those two).
   */
  private[gateway] def isPrivateLanIp(rawIp: String): Boolean =
    val ip = rawIp.stripPrefix("::ffff:")
    ip == "127.0.0.1" || ip == "::1" ||
    ip.startsWith("10.") ||
    ip.startsWith("192.168.") ||
    ip.startsWith("172.") && {
      val octet = ip.split('.').lift(1).flatMap(_.toIntOption).getOrElse(-1)
      octet >= 16 && octet <= 31
    }

  private def checkAuth(req: Request[IO]): Boolean =
    req.headers.get[Authorization].collectFirst { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) =>
      t
    } match
      // 接受面双轨（patbackend 批，2026-09-20）：轨 1 = 本机文件令牌（Auth，逐字不变）；
      // 轨 2 = Logto 签发的 PAT（自包含 JWT，用 JWKS 公钥**离线**验签；逻辑全在 PatAuth，
      // 本行只做薄委调）。两轨皆否 ⇒ 同一个 Forbidden，对外不区分原因（防枚举）。
      case Some(t) => Auth.validateToken(t, token) || PatAuth.accepts(t)
      case None =>
        req.params.get("token").exists(t => Auth.validateToken(t, token))

  /**
   * SSRF guard for provider model discovery: only absolute http(s) URLs with a
   * non-empty host are fetchable. Blocks other schemes (file:, jar:, ftp:...)
   * and URLs the JDK client would resolve to something unexpected. Returns the
   * validated base URL with trailing slashes trimmed.
   *
   * The model-list PATH is deliberately NOT built here: `baseUrl` is a chat
   * prefix and the two protocol faces place the version segment differently, so
   * each face declares its own list endpoint (`ModelListFaces`, whose
   * declarations carry the measurements that pin the paths).
   */
  private def checkHttpBaseUrl(baseUrl: String): Either[String, String] =
    try
      val normalized = baseUrl.trim.replaceAll("/+$", "")
      val uri = java.net.URI.create(normalized)
      val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("")
      val host = Option(uri.getHost).map(_.trim).getOrElse("")
      if (scheme == "http" || scheme == "https") && host.nonEmpty then Right(normalized)
      else Left("baseUrl must be an absolute http(s) URL with a host")
    catch case _: Exception => Left("Invalid baseUrl")

  /**
   * `completeDeviceEnrollment` 的结果通道版本：`Right(networkId)` = 已落盘并热换，
   * `Left(err)` = **真实失败原因原文**（护栏拒绝 ⇒ `EnrollGuard` 的 reason）。
   * 回调页需要它来透真因（案 C ①(a)）；HTTP 形态由调用方决定。
   */
  private def completeDeviceEnrollmentDetailed(
    ms: NeblinkService,
    resolvedUrl: String,
    json: Json,
    logtoRefresh: Option[String] = None,
    logtoIdToken: Option[String] = None,
    explicitUserAction: Boolean = false
  ): IO[Either[String, String]] =
    persistEnrollment(ms, resolvedUrl, json, logtoRefresh, logtoIdToken, explicitUserAction).map(
      _.map(_ => json.hcursor.downField("networkId").as[String].toOption.getOrElse(""))
    )

  /**
   * The enrollment half of completeDeviceEnrollment — delegates to
   * NeblinkEnrollment (shared with the startup client's silent re-login
   * hook, which has no HTTP context). Returns the persisted device token.
   */
  private def persistEnrollment(
    ms: NeblinkService,
    resolvedUrl: String,
    json: Json,
    logtoRefresh: Option[String],
    logtoIdToken: Option[String] = None,
    explicitUserAction: Boolean = false
  ): IO[Either[String, String]] =
    NeblinkEnrollment.persist(
      ms,
      resolvedUrl,
      json,
      logtoRefresh,
      neblinkDiscovery,
      gatewayPort,
      reloginHook = Some(LogtoSilentRelogin.make(ms, IO.pure(neblinkDiscovery), gatewayPort, neblinkServerUrl(None))),
      logtoIdToken = logtoIdToken,
      explicitUserAction = explicitUserAction
    )
  end persistEnrollment

  // 登录回调域已整体迁至 gateway/AuthRoutes.scala(行为保持重构,2026-09-24):
  // PKCE/切换标记状态与 GET /callback、GET /logged-out 的实现都在那边;本类保留
  // 同名挂载委托,GatewayMain 的 /auth 挂载面零改动。
  def authCallbackRoutes: HttpRoutes[IO] = AuthRoutes.callbackRoutes(ctx)

  /**
   * Resolve the NebLink Server URL for device-flow requests. Priority:
   * 1. Explicitly provided URL (from the request body).
   * 2. URL from the current neblink config.
   * 3. The public default URL — 🔴 **suppressed on a redirected data root**
   *    (案 b①，2026-09-20 作者令 · 测试卫生).
   *
   * `None` = 「**没有目标**」：隔离实例既无显式 URL 也无配置 URL，就**不得**悄悄继承
   * 生产默认当入网目标（今晚事故链：隔离 home 自铸身份 → 本回落 → 案 C 显式登录 →
   * `POST /api/device/register` 打到 `neblink.nebflow.space`）。调用点把 `None` 变成
   * **可见失败**（[[EnrollGuard.prodFallbackRefusalReason]]），放行通道 =
   * `NEBFLOW_ALLOW_PROD_ENROLL=1`，置上后回落与改前**逐字相同**。
   *
   * 默认数据根 / 显式 URL / 配置 URL 三条路径零行为变化（判据不在本函数，而在
   * `EnrollGuard.prodDefaultTarget` —— 单点）。
   */
  private def neblinkServerUrl(explicit: Option[String] = None): IO[Option[String]] =
    explicit match
      case Some(url) => IO.pure(Some(url))
      case None =>
        neblinkService match
          case Some(ms) =>
            ms.neblinkConfig.map(_.neblinkServer.map(_.url)).flatMap {
              case Some(url) => IO.pure(Some(url))
              case None => prodDefaultTargetIO
            }
          case None => prodDefaultTargetIO

  /**
   * Last-resort enrollment target (`Branding.serverUrl`, gated by [[EnrollGuard]]).
   * The refusal is loud, never silent — the log line is the 「零注册出网」的日志面判据。
   */
  private def prodDefaultTargetIO: IO[Option[String]] =
    EnrollGuard.prodDefaultTarget match
      case some @ Some(_) => IO.pure(some)
      case None =>
        logger
          .warn(
            "isolated data root: the production server default is NOT used as an enrollment target " +
              s"(案 b①) — ${EnrollGuard.prodFallbackRefusalReason}",
            "code" -> CredentialFailure.EnrollRefusedIsolatedHome.code
          )
          .as(None)

end RestApiRoutes
