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

      // ===== NebLink P2P Discovery (no gateway auth — used by other Nebflow instances) =====
      // 注（2026-09-20 加固批）：本段的**对端判据**见下方 /neblink/discover 与 /neblink/announce
      // 两条（前者本批补入 verifyPeerAccess，后者原已内联 isTrustedPeer/isKnownNetworkDevice）。

      // Return local device info for NebLink discovery probes
      // 2026-09-20 加固批（chain-apiguard）：本路由原为裸奔（无门亦无内联判据）。它面向
      // **对端设备**而非 UI 客户端（对端无 Bearer 令牌）⇒ 落点是本族既有的对端判据
      // helper（同 /neblink/remote-exec、/neblink/transfer 等），**不**套 withAuth。
      // NebLink 未启用时 verifyPeerAccess 回 404，体与改前的 None 分支逐字相同。
      case req @ GET -> Root / "neblink" / "discover" =>
        verifyPeerAccess(req).flatMap {
          case Left(resp) => IO.pure(resp)
          case Right(ms) =>
            ms.identity.flatMap { id =>
              Ok(
                Json.obj(
                  "deviceId" -> id.deviceId.asJson,
                  "deviceName" -> id.deviceName.asJson,
                  "platform" -> id.platform.asJson,
                  "capabilities" -> id.capabilities.asJson
                )
              )
            }
        }

      // Receive a peer's announcement ("I'm online, here's my info")
      case req @ POST -> Root / "neblink" / "announce" =>
        neblinkService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(ms) =>
            val remoteIp = req.remoteAddr.fold("")(a => a.toString)
            // Parse first so we can read the claimed deviceId for the device-ID
            // trust fallback. Malformed bodies from untrusted callers are still
            // rejected as "not a trusted peer" rather than leaking a 400.
            req.as[Json].flatMap { body =>
              io.circe.parser.decode[nebflow.neblink.DeviceDiscoveryInfo](body.noSpaces) match
                case Right(info) =>
                  val port = body.hcursor.downField("port").as[Int].getOrElse(8080)
                  isKnownNetworkDevice(ms, info.deviceId, remoteIp).flatMap { known =>
                    if ms.isTrustedPeer(remoteIp) || known then
                      ms.handleAnnounce(info, remoteIp, port) *>
                        Ok(Json.obj("ok" -> true.asJson))
                    else Forbidden(Json.obj("error" -> "Not a trusted peer".asJson))
                  }
                case Left(_) =>
                  if ms.isTrustedPeer(remoteIp) then BadRequest(Json.obj("error" -> "Invalid device info".asJson))
                  else Forbidden(Json.obj("error" -> "Not a trusted peer".asJson))
            }

      // ===== NebLink API (gateway auth required — for frontend) =====

      // NebLink scan — trigger NebLink discovery immediately, return updated peers
      case req @ POST -> Root / "neblink" / "scan" =>
        withNeblink(req) { ms =>
          ms.scanNow.flatMap { peersList =>
            Ok(
              Json.obj(
                "peers" -> peersList
                  .map(p =>
                    Json.obj(
                      "deviceId" -> p.deviceId.asJson,
                      "deviceName" -> p.deviceName.asJson,
                      "platform" -> p.platform.asJson,
                      "capabilities" -> p.capabilities.asJson,
                      "userDescription" -> p.userDescription.asJson
                    )
                  )
                  .asJson,
                "peerCount" -> peersList.length.asJson
              )
            )
          }
        }

      // NebLink status — identity, peers, login state
      case req @ GET -> Root / "neblink" / "status" =>
        withNeblink(req) { ms =>
          for
            id <- ms.identity
            peersList <- ms.peers
            // 🔴 缺陷 A（上游 §8.2 第 4 项 / 判据 G4②）：本读点**永不抛** —— 坏凭据
            // （读不开/解码坏）由 `DeviceCredentialStore.loadDiagnosed` 自愈（改名留档 +
            // 当作无凭据），本端点因此稳定回答 200 + `loggedIn=false`，而不是 500
            // （修前 500 被前端 `neblink.js:159` 静默吞掉 ⇒ 假「已登录」中间态，上游 S4）。
            // 失败**不静默**：分类经下方**加法字段** `credentialIssue` 透出（老消费方忽略未知键）。
            credentialRead <- DeviceCredential.loadDiagnosed
            cred = credentialRead.getOrElse(None)
            credentialIssue = credentialRead.left.toOption
            cfg <- ms.neblinkConfig
            // Logged in = device credential exists AND NebLink is enabled.
            loggedIn = cred.isDefined && cfg.enabled
            // P1-2: accurate per-peer connectivity. serverOnline (heartbeat) alone
            // doesn't mean the peer is reachable — cross-network peers need relay.
            directOnline = (deviceId: String) => ms.presenceServiceOpt.exists(_.isConnected(deviceId))
            // C3 (2026-09-11 P2P 直连修复批): WHY the P2P leg is where it is.
            // `directOnline=false` alone was unattributable — a failed presence
            // dial only wrote a logger.debug line that root level=INFO filtered
            // out (方案 §1.1 环③ / U-1), so "unreachable address" and "never
            // dialed" looked identical. These are ADDITIVE fields; no existing
            // field's meaning changes.
            dialStatusOf = (deviceId: String) => ms.presenceServiceOpt.flatMap(_.dialStatus(deviceId))
            relayAvailable = ms.relayTunnelOpt.exists(_.isAlive)
            // F7 (2026-09-10 隧道鉴权自愈批): relayAvailable alone hides WHY the
            // tunnel is down. authRejected distinguishes "our session was
            // rejected (401/403) — self-heal territory" from a server-side 5xx,
            // which is the report §6 cross-project discriminator.
            // 2026-09-14（踢旧批案 B）：再补本地已判定的「已在别处登录」态
            // （`disconnect` 帧 → 停摆）——**加法字段，本地网关↔浏览器侧**，服务端
            // wire 零变化（见 NeblinkRelayTunnel.statusJson 注释）。
            relayStatus = nebflow.neblink.NeblinkRelayTunnel.statusJson(
              relayAvailable,
              ms.relayTunnelOpt.flatMap(_.authStatus),
              ms.relayTunnelOpt.map(_.signedOutElsewhereAt).getOrElse(0L)
            )
            // C3 (ghost-peer fix): `online` is a real freshness judgement — the
            // peer must have appeared in a server heartbeat/discovery response
            // within the online window (NeblinkService.onlineFreshnessMs), not
            // merely exist in the local list.
            nowMs = System.currentTimeMillis()
            peerOnline = (p: nebflow.neblink.PeerInfo) => NeblinkService.isPeerOnline(p, nowMs, cfg.syncIntervalSec)
            // Account identity hints (switch-account, 2026-09-10): decoded
            // READ-ONLY from the ALREADY-persisted id_token (no extra I/O —
            // `cred` is loaded right below anyway). Same trust rationale as
            // the C2 picture claim: TLS-sourced token, claim read only, the
            // token never leaves the store. The web client's account memory
            // persists ONLY these two display strings — never any credential.
            acctClaims = cred.flatMap(_.logto.flatMap(_.idToken)) match
              case Some(tok) => LogtoAuthCode.decodeIdTokenClaims(tok, Seq("email", "name"))
              case None => Map.empty[String, String]
            // 批 C（§3.7）：好友消息面的**对账计数暴露**。批 A 只产不曝（`FriendService`
            // 的 `recordPull`/`recordPullLine` 是唯一计数点，注释已声明暴露归批 C），
            // 本行即那条指令的落点——**读的就是批 A 那份累加值**，不另建计数器
            // （两份计数 = 两个读数会各说各话 = 判据④不可机械判）。
            // 未装配 friendService（未配置 NebLink Server）⇒ 显式 `null`，不是空对象：
            // 「没有这个面」与「有这个面且计数全 0」必须可区分（同既有 `relay` 字段口径）。
            friendPull <- sharedResources.friendService match
              case Some(fs) => fs.pullCountersJson
              case None => IO.pure(Json.Null)
            r <- Ok(
              Json.obj(
                "loggedIn" -> loggedIn.asJson,
                "relay" -> relayStatus,
                "friendPull" -> friendPull,
                // 缺陷 A（加法字段，老消费方忽略未知键）：本机凭据面的可判读读数。
                // `null` = 读干净（有/无凭据都是正常态）；非 null = 出过事，带
                // `code`/`reason`/`action` 三段（**零路径、零异常类名**，判据 G2/G3）。
                "credentialIssue" -> credentialIssue.fold(Json.Null)(_.toJson),
                "device" -> Json.obj(
                  "id" -> id.deviceId.asJson,
                  "name" -> id.deviceName.asJson,
                  "platform" -> id.platform.asJson,
                  "capabilities" -> id.capabilities.asJson,
                  "userDescription" -> id.userDescription.asJson,
                  "avatarUrl" -> id.avatarUrl.asJson,
                  "githubLogin" -> id.githubLogin.asJson,
                  "email" -> acctClaims.getOrElse("email", "").asJson,
                  "displayName" -> acctClaims.getOrElse("name", "").asJson
                ),
                "peers" -> peersList
                  .map(p =>
                    Json.obj(
                      "deviceId" -> p.deviceId.asJson,
                      "deviceName" -> p.deviceName.asJson,
                      "platform" -> p.platform.asJson,
                      "address" -> p.address.asJson,
                      "capabilities" -> p.capabilities.asJson,
                      "userDescription" -> p.userDescription.asJson,
                      "lastSeen" -> p.lastSeen.asJson,
                      "online" -> peerOnline(p).asJson, // freshness: seen by server within the online window
                      "directOnline" -> directOnline(p.deviceId).asJson, // P2P WS reachable
                      "lastDialError" -> dialStatusOf(p.deviceId)
                        .flatMap(_.error)
                        .asJson, // C3: why not (None = last dial succeeded)
                      "lastDialAt" -> dialStatusOf(p.deviceId)
                        .map(_.atMs)
                        .asJson, // C3: when that dial happened (null = never dialed)
                      "dialEndpoint" -> dialStatusOf(p.deviceId)
                        .map(_.endpoint)
                        .asJson, // C1: candidate actually dialed / won
                      "relayAvailable" -> relayAvailable.asJson // our relay tunnel is up
                    )
                  )
                  .asJson
              )
            )
          yield r
        }

      // Own-account avatar proxy (2026-09-07 设置页头像加载态修复): the avatar
      // origin (neblink-server static host) sends no CORS headers, so the web
      // local-first cache (avatarCache.js) could never fetch it cross-origin and
      // never populated — every settings open fell back to a slow remote <img>.
      // Same-origin proxy fixes the cache build; fetches ONLY the current
      // identity avatarUrl (client supplies no URL — no open-proxy surface).
      case req @ GET -> Root / "neblink" / "avatar" =>
        withNeblink(req) { ms =>
          ms.identity.flatMap { id =>
            id.avatarUrl.filter(_.nonEmpty) match
              case None => NotFound(Json.obj("error" -> "no avatar".asJson))
              case Some(url) =>
                AvatarProxy.fetch(AvatarProxy.jdkFetch)(url).flatMap {
                  case Right((contentType, bytes)) =>
                    // Explicit byte-stream entity: bare Ok(Array[Byte]) resolves to
                    // the circe generic encoder in this scope (circe encodes byte
                    // arrays as JSON number arrays — the bytes would be mangled).
                    val ct = org.http4s.headers.`Content-Type`
                      .parse(contentType)
                      .getOrElse(org.http4s.headers.`Content-Type`(MediaType.application.`octet-stream`))
                    IO.pure(
                      Response[IO](Status.Ok)
                        .withEntity(fs2.Stream.emits(bytes).covary[IO])
                        .withHeaders(ct)
                    )
                  case Left(err) =>
                    BadGateway(Json.obj("error" -> err.asJson))
                }
          }
        }

      // 对端头像同源只读路由（sessperf Phase B 前置项 · 作者 2026-09-20 18:55 令 B）。
      //
      // 为什么需要：网页本地层要给**对端**头像建「内容指纹双层缓存」（方案卡 §4③），
      // 而跨源头像源站**不发 CORS 头** ⇒ 浏览器直 fetch 恒失败（`AvatarProxy.scala`
      // 头注 2026-09-07 取证）；`GET /api/neblink/avatar` 只服务当前身份**自己**。
      // 本路由是「按 userId 取对端头像字节」的唯一取数面。
      //
      // 🔴 **出生即包鉴权闸**（作者令：新路由禁裸奔过夜；apiguard 批 38 路由同族口径）：
      //    判据与全部 `/friends*` / `/conversations*` 代理腿**逐字同款**——
      //    ① `withAuth`（网关 Bearer 令牌）在先：无令牌 ⇒ **403**，零上游往返；
      //    ② `friendService` 缺席 ⇒ **404 `NebLink not enabled`**（fail-closed）；
      //    ③ 上游 `Not logged in` ⇒ **401 `code=neblink_not_logged_in`**（`friendErr` 单点，
      //       与网关自身 403 的**有意分化**见 `friendErr` 注释）。
      //
      // 🔴 **不是开放代理 / SSRF 面为零**：客户端**只能给 `userId`**；具体 URL 由网关从
      //    **本账号好友档案**（`FriendSummary.avatar`）解出 —— 请求方无法让网关去取任意
      //    地址。够不着的好友（非好友 / 未知 id / 该好友无头像）⇒ **404 `no avatar`**。
      //
      // 响应面（方案卡 §3 前置项逐条）：字节 + `Content-Type` 原样透传，
      //   带 `X-Avatar-Sha256` + `ETag`（强 ETag = 内容 sha）+ `Cache-Control: private`；
      //    `If-None-Match` 命中 ⇒ **304**（零重传 —— 客户端「hash 未变 ⇒ 复用旧 blob」的
      //    判据面）。上游非 200 ⇒ 502（客户端按失败退避，不污染既有缓存）。
      case req @ GET -> Root / "avatars" / userId =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              val wanted = userId.trim
              if wanted.isEmpty then BadRequest(Json.obj("error" -> "userId required".asJson))
              else
                fs.listFriends.flatMap {
                  case Left(err) => friendErr(err)
                  case Right(resp) =>
                    resp.friends.find(_.userId == wanted).flatMap(_.avatar).filter(_.nonEmpty) match
                      case None => NotFound(Json.obj("error" -> "no avatar".asJson))
                      case Some(url) =>
                        AvatarProxy.fetch(AvatarProxy.jdkFetch)(url).flatMap {
                          case Right((contentType, bytes)) =>
                            val sha = AvatarProxy.sha256Hex(bytes)
                            val etag = "\"" + sha + "\""
                            val inm = req.headers.get(CIString("If-None-Match")).map(_.head.value.trim).getOrElse("")
                            if inm.nonEmpty && inm.contains(sha) then
                              IO.pure(
                                Response[IO](Status.NotModified)
                                  .withHeaders(Headers(Header.Raw(CIString("ETag"), etag)))
                              )
                            else
                              // 显式字节流实体（同 /neblink/avatar 与附件下载先例：裸
                              // `Ok(Array[Byte])` 会命中 circe 的 byte 数组编码器，把字节
                              // 变成 JSON 数字数组 ⇒ 图片损坏）。
                              val ct = org.http4s.headers.`Content-Type`
                                .parse(contentType)
                                .getOrElse(org.http4s.headers.`Content-Type`(MediaType.application.`octet-stream`))
                              IO.pure(
                                Response[IO](Status.Ok)
                                  .withEntity(fs2.Stream.emits(bytes).covary[IO])
                                  .withHeaders(
                                    Headers(
                                      ct,
                                      Header.Raw(CIString("ETag"), etag),
                                      Header.Raw(CIString("X-Avatar-Sha256"), sha),
                                      Header.Raw(CIString("Cache-Control"), "private, max-age=3600")
                                    )
                                  )
                              )
                            end if
                          case Left(err) =>
                            BadGateway(Json.obj("error" -> err.asJson))
                        }
                }
              end if
        }

      // Update neblink config (e.g. syncIntervalSec)
      case req @ PATCH -> Root / "neblink" / "config" =>
        withNeblink(req) { ms =>
          req.as[Json].flatMap { body =>
            val syncInterval = body.hcursor.downField("syncIntervalSec").as[Option[Int]].toOption.flatten
            ms.updateConfig { cfg =>
              cfg.copy(
                syncIntervalSec = syncInterval.getOrElse(cfg.syncIntervalSec)
              )
            } *> Ok(Json.obj("ok" -> true.asJson))
          }
        }

      // Logout — notify the server, stop tunnels, clear device credential,
      // disable NebLink (keep server address), stop the client, clear user info
      // and peers. Reverses the device-flow login.
      //
      // NOTE (RP-logout fix, 2026-09-06): this endpoint is LOCAL-only teardown
      // — it never touches the provider's browser SSO session, so a login
      // right after it silently redirects back into the original account.
      // The full logout (local teardown + Logto end-session handoff) is
      // POST /neblink/auth/end-session below (POST + token since the 2026-09-20
      // closeout batch; the old GET arm is a gated 405); the web logout button
      // drives that one. This endpoint stays for API compatibility and scripted use.
      case req @ POST -> Root / "neblink" / "logout" =>
        withNeblink(req) { ms =>
          AuthRoutes.performLocalLogout(ms) *> Ok(Json.obj("ok" -> true.asJson))
        }

      // RP-initiated logout (OIDC Session Management, RP-logout fix
      // 2026-09-06; POST + gate since the 2026-09-20 closeout batch) — local
      // teardown FIRST (same steps as POST /neblink/logout above, credential read
      // before it is cleared), then the provider's end_session_endpoint URL is
      // returned as DATA (`{"endSessionUrl": …}`) instead of a 302: the caller is
      // now a `fetch` (a cross-origin 302 cannot be followed by fetch — it is
      // CORS-blocked and the body is opaque, so the target URL would be
      // unreachable), and it navigates a popup it reserved in the same gesture
      // tick (top-level navigation = no CORS). The URL carries the persisted
      // id_token as `id_token_hint` (valid hint = no confirmation page) and the
      // local `/auth/logged-out` landing page as `post_logout_redirect_uri`
      // (ignored by the provider until the uri is allow-listed on the Logto app —
      // probed 2026-09-06, always safe). The browser-side Logto session cookie
      // dies at that navigation, so the NEXT login shows the account page instead
      // of silently re-entering the old account.
      //
      // 🔴 门控（2026-09-20 收尾批 = 作者裁定 + 分发器定形）：旧注记
      // 「No gateway checkAuth BY DESIGN」（理由 = 浏览器导航跳拿不到
      // Authorization）**已作废** —— 同一形态也是「GET 带副作用」的反模式（凭据删除
      // 可由一次导航 / 链接预取 / 扫描器触发）。现在 **POST + 令牌** 才可达：客户端
      // `web/js/neblink.js` `openEndSessionHandoff` 在手势内先预约空白窗、再 fetch
      // （带 Authorization）、拿到 URL 后导航该窗。旧 GET 面留下**门内 405**（下一条 arm）。
      case req @ POST -> Root / "neblink" / "auth" / "end-session" =>
        withAuth(req) {
          // Body（两字段皆可选；缺失 / 空 / 非 JSON body = 纯登出，容错与
          // POST /neblink/auth/start 同款）：
          //   {"scenario":"switch","uiLocales":"zh"|"en"}
          //
          // Scenario marker (one-window switch, 2026-09-16): `scenario=switch` is
          // sent ONLY by the switch-account entry; it arms the single-use handoff
          // so the landing page this hop ends on continues into the login in the
          // SAME window. A plain logout explicitly DISARMS, so a leftover marker
          // can never drag the plain-logout landing page into an auto-login.
          // `uiLocales` is whitelisted exactly like /neblink/auth/start (the
          // landing hop is a bare navigation — it cannot read the app's locale).
          // 两者都从 query 迁到 POST body（本批：GET 面退场）。arm/disarm 调用点语义不变。
          req.attemptAs[Json].value.map(_.toOption).flatMap { bodyJson =>
            val switchScenario = bodyJson
              .flatMap(_.hcursor.downField("scenario").as[String].toOption)
              .contains("switch")
            val uiLocales = bodyJson
              .flatMap(_.hcursor.downField("uiLocales").as[String].toOption)
              .filter(v => v == "zh" || v == "en")
              .getOrElse("")
            val armOrDisarm =
              if switchScenario then AuthRoutes.switchHandoff.arm(uiLocales) else AuthRoutes.switchHandoff.disarm
            neblinkService match
              case None =>
                armOrDisarm *> NotFound(Json.obj("error" -> "NebLink service not initialized".asJson))
              case Some(ms) =>
                val run =
                  for
                    _ <- armOrDisarm
                    logto <- ms.neblinkConfig.map(_.effectiveLogto)
                    // Read the hint BEFORE performLocalLogout deletes the file.
                    //
                    // 🔴 缺陷 A（上游 §8.2 第 4 项 / 判据 G5）：读失败**不得**跳过本地拆除。
                    // 修前这一读异常裸冒泡 ⇒ 整条路由 500、拆除一步没跑（凭据没删、config
                    // 没关、client 没置空），用户因此**无法通过「退出账号」自救**（上游 S3）。
                    // 现在：读失败 ⇒ 只跳过 `id_token_hint`（登录态可能不完整），拆除照跑；
                    // 分类读数由存储层的 WARN 留档（带分类码），无需在这里再判一次。
                    credentialRead <- DeviceCredential.loadDiagnosed
                    idToken = credentialRead.toOption.flatten.flatMap(_.logto).flatMap(_.idToken)
                    resp <- logto.flatMap(lc => lc.pkceClientId.map(_ => lc)) match
                      case Some(lc) =>
                        val target = LogtoAuthCode.endSessionUrl(
                          lc.endpoint,
                          idToken,
                          Some(s"http://127.0.0.1:$gatewayPort/auth/logged-out")
                        )
                        for
                          _ <- AuthRoutes.performLocalLogout(ms)
                          _ <- logger.info(
                            "RP-initiated logout: local teardown done, returning the provider end_session URL"
                          )
                          // 出口 = 200 + JSON（不再是 302）：见上方注释（fetch 无法消费跨域 302）。
                          r <- Ok(Json.obj("endSessionUrl" -> target.asJson))
                        yield r
                      // Same surface as auth/start: unconfigured provider (or AC app
                      // id missing) — the caller falls back to the local-only logout,
                      // and no landing hop will ever come back to consume the marker.
                      case _ =>
                        AuthRoutes.switchHandoff.disarm *> NotFound(Json.obj("error" -> "logto-not-configured".asJson))
                  yield resp
                // 意外失败（拆除腿异常等）⇒ **可判读的失败页**：三段式文案，绝不再把裸异常
                // 变成无解释的 500（`getMessage` 直出 = 上游 §4 第 3 处丢失点）。
                run.handleErrorWith { e =>
                  val diagnostic = nebflow.neblink.CredentialDiagnostics.classifyFailure(
                    e,
                    nebflow.neblink.CredentialFailure.Unclassified
                  )
                  logger.warn(diagnostic.logLine("end-session failed"), "code" -> diagnostic.code) *>
                    AuthRoutes.htmlResponse(
                      AuthRoutes.callbackPage(ok = false, diagnostic.message),
                      Status.InternalServerError
                    )
                }
            end match
          }
        }

      // 旧 GET 面（本批退场，分发器 2026-09-20 定形 ⒜(ii)：**门内 405**）—— 旧调用方
      // （书签 / 脚本 / 旧页缓存）拿到自解释的「方法不对」而不是模糊 404；且因为它**在门内**
      // （先过 checkAuth），加门后的未门控集仍 = 恰 4 条（4 条设计面豁免），不新增普查条目。
      // 🔴 副作用不可达：本 arm 只回状态码，不 arm/disarm 标记、不碰凭据。
      case req @ GET -> Root / "neblink" / "auth" / "end-session" =>
        withAuth(req) {
          // 显式构造（DSL 的 `MethodNotAllowed` 只接受 `Allow` 头、不带体）：体自解释
          // （`error` + 迁移目标），调用方拿到 405 而非模糊 404。
          IO.pure(
            Response[IO](status = Status.MethodNotAllowed).withEntity(
              Json.obj(
                "error" -> "method-not-allowed".asJson,
                "allow" -> "POST".asJson
              )
            )
          )
        }

      // Enroll device via pairing code — calls the NebLink Server's
      // /api/device/enroll, receives a long-lived device credential, persists it,
      // and writes the neblink config so the device joins on next start.
      case req @ POST -> Root / "neblink" / "enroll" =>
        if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
        else
          req.as[Json].flatMap { body =>
            val serverOpt = body.hcursor
              .downField("server")
              .as[Option[String]]
              .toOption
              .flatten
              .map(_.stripSuffix("/"))
            val pairCodeOpt = body.hcursor.downField("pairCode").as[Option[String]].toOption.flatten
            (serverOpt, pairCodeOpt) match
              case (Some(server), Some(pairCode)) =>
                neblinkService match
                  case None => BadRequest(Json.obj("error" -> "NebLink service not initialized".asJson))
                  case Some(ms) =>
                    for
                      identity <- ms.identity
                      enrollBody = Json
                        .obj(
                          "pairCode" -> pairCode.asJson,
                          "deviceId" -> identity.deviceId.asJson,
                          "deviceName" -> identity.deviceName.asJson,
                          "platform" -> identity.platform.asJson
                        )
                        .noSpaces
                      result <- enrollWithServer(server, enrollBody)
                      resp <- result match
                        case Right(credJson) =>
                          val deviceToken = credJson.hcursor.downField("deviceToken").as[String].toOption
                          val networkId = credJson.hcursor.downField("networkId").as[String].toOption.getOrElse("")
                          val deviceId =
                            credJson.hcursor.downField("deviceId").as[String].toOption.getOrElse(identity.deviceId)
                          deviceToken match
                            case Some(token) =>
                              val credential = DeviceCredential(server, networkId, deviceId, token)
                              val newConfig = NeblinkServerConfig(
                                url = server,
                                networkId = networkId,
                                secret = "",
                                deviceToken = Some(token)
                              )
                              for
                                _ <- DeviceCredential.save(credential)
                                current <- NeblinkConfig.load
                                updated = current.copy(enabled = true, neblinkServer = Some(newConfig))
                                _ <- NeblinkConfig.save(updated)
                                r <- Ok(
                                  Json.obj(
                                    "ok" -> true.asJson,
                                    "message" -> "Enrolled. Please restart Nebflow to connect.".asJson,
                                    "networkId" -> networkId.asJson
                                  )
                                )
                              yield r
                            case None =>
                              BadRequest(Json.obj("error" -> "Server did not return a device token".asJson))
                          end match
                        case Left(err) =>
                          BadRequest(Json.obj("error" -> s"Enrollment failed: $err".asJson))
                    yield resp
                end match
              case _ =>
                BadRequest(Json.obj("error" -> "Missing server or pairCode".asJson))
            end match
          }

      // ===== Device authorization flow (Tailscale-style) =====

      // Start device flow. Dual-mode (Logto stage 1): with a provider
      // configured, start its RFC 8628 /oidc/device/auth; otherwise proxy to
      // neblink-server's /api/device/code. Both map onto the same frontend
      // contract {deviceCode, userCode, verificationUri, interval, expiresIn}.
      case req @ POST -> Root / "neblink" / "device-flow" / "start" =>
        if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
        else
          neblinkService match
            case None => BadRequest(Json.obj("error" -> "NebLink service not initialized".asJson))
            case Some(ms) =>
              for
                logto <- ms.neblinkConfig.map(_.logto)
                result <- logto match
                  case Some(lc) =>
                    LogtoDeviceFlow
                      .start(LogtoDeviceFlow.jdkSend)(lc.endpoint, lc.clientId)
                  case None =>
                    for
                      identity <- ms.identity
                      // The neblink-server URL: explicit request field > config > the
                      // public default — the last one is gated by 案 b① (isolated data
                      // root without the explicit switch ⇒ **no target**, see
                      // `neblinkServerUrl`). For device flow the server must be reachable
                      // from both the browser (for OAuth) and the device (for polling).
                      serverTarget <- neblinkServerUrl(None)
                      result <- serverTarget match
                        case Some(serverUrl) =>
                          val body = Json
                            .obj(
                              "deviceId" -> identity.deviceId.asJson,
                              "deviceName" -> identity.deviceName.asJson,
                              "platform" -> identity.platform.asJson
                            )
                            .noSpaces
                          proxyPost(serverUrl, nebflow.neblink.Protocol.DeviceApi.code, body)
                        case None =>
                          // 案 b①：无目标 ⇒ 零出站（不发起任何请求，更不注册）。
                          IO.pure(Left(EnrollGuard.prodFallbackRefusalReason))
                    yield result
                resp <- result match
                  case Right(json) => Ok(json)
                  case Left(err) => BadRequest(Json.obj("error" -> s"Failed to start device flow: $err".asJson))
              yield resp

      // Poll device flow: proxy to neblink-server's /api/device/token.
      // On success, persist the device credential + update config + hot-swap client.
      // Poll device flow. Dual-mode (Logto stage 1): with a provider
      // configured, poll its /oidc/token (RFC 8628 grant) and on success
      // exchange the access token via /api/device/register; otherwise proxy to
      // neblink-server's /api/device/token. Both success paths converge on the
      // same persist-credential + hot-swap completion.
      case req @ POST -> Root / "neblink" / "device-flow" / "poll" =>
        if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
        else
          req.as[Json].flatMap { body =>
            val deviceCode = body.hcursor.downField("deviceCode").as[String].getOrElse("")
            val serverUrl = body.hcursor
              .downField("serverUrl")
              .as[Option[String]]
              .toOption
              .flatten
              .map(_.stripSuffix("/"))
            if deviceCode.isEmpty then BadRequest(Json.obj("error" -> "Missing deviceCode".asJson))
            else
              neblinkService match
                case None => BadRequest(Json.obj("error" -> "NebLink service not initialized".asJson))
                case Some(ms) =>
                  for
                    serverTarget <- neblinkServerUrl(serverUrl)
                    resp <- serverTarget match
                      case None =>
                        // 案 b①：无目标 ⇒ 不轮询、不注册；文案与其余入口同源。
                        BadRequest(Json.obj("error" -> EnrollGuard.prodFallbackRefusalReason.asJson))
                      case Some(resolvedUrl) =>
                        for
                          logto <- ms.neblinkConfig.map(_.logto)
                          result <- logto match
                            case Some(lc) =>
                              LogtoDeviceFlow
                                .pollOnce(LogtoDeviceFlow.jdkSend)(lc.endpoint, lc.clientId, deviceCode)
                                .flatMap {
                                  case LogtoDeviceFlow.PollOutcome.Success(accessToken) =>
                                    ms.identity.flatMap { identity =>
                                      LogtoDeviceFlow
                                        .register(LogtoDeviceFlow.jdkSend)(
                                          resolvedUrl,
                                          accessToken,
                                          identity.deviceId,
                                          identity.deviceName,
                                          identity.platform
                                        )
                                        .map {
                                          case Right(json) => Right(json)
                                          case Left(err) => Left(err)
                                        }
                                    }
                                  // Pending (incl. slow_down, normalized) and terminal
                                  // failures surface as error strings exactly like the
                                  // legacy proxy path — the frontend's
                                  // authorization_pending polling contract is unchanged.
                                  case LogtoDeviceFlow.PollOutcome.Pending(err) =>
                                    IO.pure(Left(err))
                                  case LogtoDeviceFlow.PollOutcome.Failed(err) =>
                                    IO.pure(Left(err))
                                }
                            case None =>
                              val pollBody = Json.obj("deviceCode" -> deviceCode.asJson).noSpaces
                              proxyPost(resolvedUrl, nebflow.neblink.Protocol.DeviceApi.token, pollBody)
                          resp <- result match
                            case Right(json) =>
                              // Success — persist credential + update config + hot-swap.
                              completeDeviceEnrollment(ms, resolvedUrl, json)
                            case Left(err) =>
                              // authorization_pending is expected during polling — pass through.
                              BadRequest(Json.obj("error" -> err.asJson))
                        yield resp
                  yield resp
              end match
            end if
          }

      // ===== Logto AC+PKCE login (stage 2, 2026-08-28) =====
      // Contract (Manager-frozen): start 200 = {authorizeUrl}; logto not
      // configured = 404 {error:"logto-not-configured"}; PKCE state machine
      // exposed via /auth/state {status: idle|pending|success|error, error?}.

      // Start a PKCE login: build verifier/challenge + state (in-memory,
      // single-flight), return the hosted authorize URL for window.open.
      // Optional body {"forceLogin":true} → authorize prompt "login consent"
      // (RP-logout fix, 2026-09-06): the switch-account entry — `login`
      // forces the hosted account page even with a live Logto SSO session;
      // `consent` re-asks for consent on that same hosted page (UX: the account
      // chooser must be reached even when the provider SSO session is alive).
      // [O5, 2026-09-11] The offline_access/refresh-token invariant this comment
      // used to cite NO LONGER EXISTS — authorize requests `openid email
      // profile`, no refresh token is issued for a new login, and the persisted
      // identity comes from the id_token (see LogtoAuthCode.authorizeUrl /
      // DeviceCredentialStore.LogtoRefresh). Body is optional:
      // absent/empty/unparsable → plain login.
      // Optional body {"uiLocales":"zh"|"en"} (BYUI handoff ①, 2026-09-09):
      // forwarded as the OIDC ui_locales hint so the hosted page matches the
      // client UI language. WHITELISTED to "zh"/"en" — any other value (or an
      // absent field) resolves to "" and the param is omitted, which is also
      // the byte-identical legacy behavior for old callers.
      case req @ POST -> Root / "neblink" / "auth" / "start" =>
        if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
        else
          neblinkService match
            case None => BadRequest(Json.obj("error" -> "NebLink service not initialized".asJson))
            case Some(ms) =>
              for
                // One body read feeds both optional fields (http4s streams a
                // request body once).
                bodyJson <- req
                  .attemptAs[Json]
                  .value
                  .map(_.toOption) // empty / non-JSON body = plain login
                forceLogin = bodyJson
                  .flatMap(_.hcursor.downField("forceLogin").as[Boolean].toOption)
                  .getOrElse(false)
                uiLocales = bodyJson
                  .flatMap(_.hcursor.downField("uiLocales").as[String].toOption)
                  .flatMap(v => if v == "zh" || v == "en" then Some(v) else None)
                  .getOrElse("")
                // Embedded-default fallback: missing logto block resolves to the
                // product's hosted auth service (fresh installs get PKCE login).
                // The authorize URL itself has ONE builder — [[beginPkceLogin]] —
                // shared with the switch-account landing continuation below.
                // An explicit login start supersedes any pending switch handoff
                // (the marker is single-use and must not survive a new attempt).
                _ <- AuthRoutes.switchHandoff.disarm
                authorizeUrl <- AuthRoutes.beginPkceLogin(ms, forceLogin, uiLocales)
                resp <- authorizeUrl match
                  case Some(url) => Ok(Json.obj("authorizeUrl" -> url.asJson))
                  // Logto unconfigured, or configured without the AC app id —
                  // the PKCE login surface treats both as "not configured".
                  // (Defensive: effectiveLogto always resolves via the embedded
                  // default, so this arm only fires if that invariant changes.)
                  case None => NotFound(Json.obj("error" -> "logto-not-configured".asJson))
              yield resp

      // Login-state poll for the frontend (pending while the hosted page is
      // open; success/error sticky until the next start).
      case req @ GET -> Root / "neblink" / "auth" / "state" =>
        if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
        else AuthRoutes.pkceLogin.statusJson.flatMap(Ok(_))

      // Switch-account handoff readout (one-window switch, 2026-09-16). The app
      // window watches this after a switch gesture to tell the two cases apart:
      //   · "consumed" — the logout window's landing page took the continuation
      //     over (one window total, nothing to do);
      //   · anything else past the client deadline — the continuation never
      //     arrived (e.g. the gateway port is not on the provider's
      //     post_logout_redirect_uri allow-list, which the provider answers with
      //     400), so the app shows the login panel as the visible failure face
      //     instead of retrying a window.
      // READ-ONLY by construction: it must never consume/arm the marker
      // (only GET /auth/logged-out consumes; only end-session arms — the POST
      // arm since the 2026-09-20 closeout batch; the retired GET arm is a gated
      // 405 and touches no marker).
      case req @ GET -> Root / "neblink" / "auth" / "handoff" =>
        if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
        else AuthRoutes.switchHandoff.stateName.flatMap(s => Ok(Json.obj("state" -> s.asJson)))

      // Cloud session sync toggle — removed (session sync deleted)

      // Update device capabilities / user description
      case req @ PUT -> Root / "neblink" / "device-info" =>
        withNeblink(req) { ms =>
          req.as[Json].flatMap { body =>
            val userDesc = body.hcursor.downField("userDescription").as[Option[String]].toOption.flatten
            val caps = body.hcursor.downField("capabilities").as[Option[Map[String, String]]].toOption.flatten
            ms.updateDeviceInfo(userDescription = userDesc, capabilities = caps) *>
              Ok(Json.obj("ok" -> true.asJson))
          }
        }

      // Update a peer device's description (local override)
      case req @ PUT -> Root / "neblink" / "peer-description" =>
        withNeblink(req) { ms =>
          req.as[Json].flatMap { body =>
            val deviceId = body.hcursor.downField("deviceId").as[String].toOption
            val desc = body.hcursor.downField("userDescription").as[String].toOption.getOrElse("")
            deviceId match
              case Some(did) => ms.updatePeerDescription(did, desc) *> Ok(Json.obj("ok" -> true.asJson))
              case None => BadRequest(Json.obj("error" -> "missing deviceId".asJson))
          }
        }

      // File sync endpoints (fingerprints, file GET/PUT) — removed

      // Peer notification — lightweight ping to trigger immediate sync
      case req @ POST -> Root / "neblink" / "remote-exec" =>
        verifyPeerAccess(req).flatMap {
          case Left(resp) => IO.pure(resp)
          case Right(ms) =>
            req.as[Json].flatMap { body =>
              val hc = body.hcursor
              val action = hc.downField("action").as[String].getOrElse("")
              // Expand ~ to this device's user.home — same rationale as the relay
              // path: the path must resolve on the local (receiving) filesystem.
              val params = PathUtil.expandPathParams(
                hc.downField("params").as[io.circe.JsonObject].getOrElse(io.circe.JsonObject.empty)
              )
              val projectRoot = hc.downField("projectRoot").as[String].getOrElse(System.getProperty("user.dir", "."))

              // Extended actions (FileTransfer, Notify, RemoteUpdate) share handlers
              // with the relay tunnel so P2P and relay paths behave identically.
              action match
                case "FileTransfer" =>
                  nebflow.neblink.FileTransferAction.handle(params).flatMap {
                    case Right(json) => Ok(Json.obj("output" -> json.noSpaces.asJson))
                    case Left(err) => Ok(Json.obj("error" -> err.asJson, "output" -> "".asJson))
                  }
                case _ =>
                  val toolOpt = nebflow.core.tools.ToolRegistry.TOOL_MAP.get(action)
                  toolOpt match
                    case Some(tool) =>
                      val ctx = nebflow.core.tools.ToolContext(
                        projectRoot = projectRoot,
                        isRemoteExec = true
                      )
                      tool.call(params, ctx).attempt.flatMap {
                        case Right(Right(result)) => Ok(Json.obj("output" -> result.asJson))
                        case Right(Left(err)) => Ok(Json.obj("error" -> err.message.asJson, "output" -> "".asJson))
                        case Left(e) =>
                          Ok(
                            Json.obj("error" -> s"Tool execution failed: ${e.getMessage}".asJson, "output" -> "".asJson)
                          )
                      }
                    case None =>
                      BadRequest(Json.obj("error" -> s"Unknown tool: $action".asJson))
                  end match
              end match
            }
        }

      // ===== Remote Update (P2P — triggered by another Nebflow instance) =====
      // Downloads and installs the latest JAR, then restarts Nebflow.
      // The caller must be a trusted peer (verified by IP).
      case req @ POST -> Root / "neblink" / "update" =>
        verifyPeerAccess(req).flatMap {
          case Left(resp) => IO.pure(resp)
          case Right(_) =>
            req.as[Json].flatMap { body =>
              val beta = body.hcursor.downField("beta").as[Boolean].getOrElse(false)
              logger.info(s"[neblink] Remote update requested (beta=$beta), running install script...") *>
                nebflow.neblink.RemoteUpdateAction.runInstallScript(beta).flatMap {
                  case Right(msg) =>
                    logger.info("[neblink] Install succeeded, spawning restart helper and shutting down...") *>
                      IO.blocking(nebflow.core.RestartHelper.spawnRestart()) *>
                      IO.delay {
                        sharedResources.dispatcher.unsafeRunAndForget(
                          IO.sleep(1.second) *> IO(System.exit(0))
                        )
                      } *>
                      Ok(Json.obj("ok" -> true.asJson, "message" -> msg.asJson))
                  case Left(err) =>
                    Ok(Json.obj("ok" -> false.asJson, "error" -> err.asJson))
                }
            }
        }

      // ===== NebLink File Transfer (P2P — peer IP auth) =====

      // Push a file to this device
      case req @ POST -> Root / "neblink" / "transfer" =>
        verifyPeerAccess(req).flatMap {
          case Left(resp) => IO.pure(resp)
          case Right(ms) =>
            req.as[Json].flatMap { body =>
              val path = body.hcursor.downField("path").as[String].getOrElse("")
              val contentB64 = body.hcursor.downField("content").as[String].getOrElse("")
              val overwrite = body.hcursor.downField("overwrite").as[Boolean].getOrElse(false)
              if path.isEmpty then BadRequest(Json.obj("error" -> "Missing path".asJson))
              else if contentB64.isEmpty then BadRequest(Json.obj("error" -> "Missing content".asJson))
              else
                val content = java.util.Base64.getDecoder.decode(contentB64)
                ms.receiveFile(path, content, overwrite)
                  .flatMap(size => Ok(Json.obj("ok" -> true.asJson, "path" -> path.asJson, "size" -> size.asJson)))
                  .handleErrorWith(e => Ok(Json.obj("ok" -> false.asJson, "error" -> e.getMessage.asJson)))
            }
        }

      // Pull a file from this device
      case req @ GET -> Root / "neblink" / "transfer" =>
        verifyPeerAccess(req).flatMap {
          case Left(resp) => IO.pure(resp)
          case Right(ms) =>
            val path = req.params.getOrElse("path", "")
            if path.isEmpty then BadRequest(Json.obj("error" -> "Missing path parameter".asJson))
            else
              ms.sendFile(path).flatMap {
                case Some(content) =>
                  val b64 = java.util.Base64.getEncoder.encodeToString(content)
                  Ok(
                    Json.obj(
                      "path" -> path.asJson,
                      "content" -> b64.asJson,
                      "size" -> content.length.asJson
                    )
                  )
                case None => NotFound(Json.obj("error" -> s"File not found: $path".asJson))
              }
        }

      // ===== Dropbox: cross-device file transfer =====

      // Frontend uploads a file to send to a peer (gateway token auth)
      case req @ POST -> Root / "neblink" / "dropbox" / "upload" / transferId =>
        withAuth(req) {
          sharedResources.dropboxService match
            case None => NotFound(Json.obj("error" -> "Dropbox not enabled".asJson))
            case Some(svc) =>
              svc.uploadAndRelay(transferId, req.body).flatMap {
                case Right(_) => Ok(Json.obj("ok" -> true.asJson))
                case Left(err) =>
                  // xferb 批（P0-3）：失败响应带**结构化原因**（code + errorDetail 全文），
                  // 与 `dropboxError` / `dropbox-file-complete` 事件同形态 —— 前端据此回显
                  // 可判读原因，而不是一句人读文本（第二段上屏消费本字段）。
                  Ok(
                    Json.obj(
                      "ok" -> false.asJson,
                      "error" -> err.render.asJson,
                      "errorCode" -> err.code.asJson,
                      "errorDetail" -> err.toJson
                    )
                  )
              }
        }

      // Peer pushes a file (or one chunk) via HTTP (peer IP auth).
      //
      // 附件腿批（2026-09-12）：同一端点承载两种形态，靠**分块头**区分 ——
      //   - 带 `X-Dropbox-Proto: 1` + index/total/chunk-size/chunk-sha/whole-sha ⇒ 分块模式
      //     （按 offset 追加、幂等重放、gap 拒绝、末块整件摘要**接收端自算**）；
      //   - 无该头 ⇒ **legacy 整件模式**，行为与今天逐字节一致（旧发送端零回归）。
      // 端点路径与鉴权不变（不放松）。
      case req @ POST -> Root / "neblink" / "dropbox" / "transfer" / transferId =>
        verifyPeerAccess(req).flatMap {
          case Left(resp) => IO.pure(resp)
          case Right(_) =>
            sharedResources.dropboxService match
              case None => NotFound(Json.obj("error" -> "Dropbox not enabled".asJson))
              case Some(svc) =>
                val chunkHeaders = parseDropboxChunkHeaders(req)
                val receive: IO[Response[IO]] = chunkHeaders match
                  case Some(h) =>
                    // 分块模式：回执 = **接收端自算**的块摘要 + 权威 offset（R4：不是请求头回显
                    // —— 回显会让发送端 `ack.chunkSha256 == frame.chunkSha256` 的比对恒真）。
                    // 末块的 `wholeSha256` 同样只由接收端自算后写入（`ChunkAck.wholeSha256`）。
                    svc.receiveChunkFromPeer(transferId, req.body, h).map {
                      case Right(ack) =>
                        Response[IO](Status.Ok).withEntity(
                          Json.obj(
                            "ok" -> true.asJson,
                            "chunkSha256" -> ack.chunkSha256.asJson,
                            "bytesReceived" -> ack.bytesReceived.asJson,
                            "wholeSha256" -> ack.wholeSha256.map(_.asJson).getOrElse(Json.Null)
                          )
                        )
                      case Left(err) => chunkErrorResponse(err)
                    }
                  case None =>
                    svc.receiveFromPeer(transferId, req.body, None).map {
                      case Right(hash) => Response[IO](Status.Ok).withEntity(Json.obj("sha256" -> hash.asJson))
                      case Left(err) => chunkErrorResponse(err)
                    }
                receive.handleErrorWith(e =>
                  IO.pure(
                    Response[IO](Status.InternalServerError)
                      .withEntity(Json.obj("ok" -> false.asJson, "error" -> e.getMessage.asJson))
                  )
                )
        }

      // 断点续传探针（peer IP auth）：返回接收端权威 offset 与其**重算**的前缀摘要。
      case req @ GET -> Root / "neblink" / "dropbox" / "probe" / transferId =>
        verifyPeerAccess(req).flatMap {
          case Left(resp) => IO.pure(resp)
          case Right(_) =>
            sharedResources.dropboxService match
              case None => NotFound(Json.obj("error" -> "Dropbox not enabled".asJson))
              case Some(svc) =>
                svc.probeTransfer(transferId).map { probe =>
                  probe match
                    case Right(state) =>
                      Response[IO](Status.Ok).withEntity(
                        Json.obj(
                          "bytesReceived" -> state.bytesReceived.asJson,
                          "totalBytes" -> state.totalBytes.asJson,
                          "prefixSha256" -> state.prefixSha256.asJson
                        )
                      )
                    case Left(err) => Response[IO](Status.NotFound).withEntity(err.toJson)
                }
        }
      // Gateway-mediated remote update: CLI sends this, gateway does P2P first then relay
      case req @ POST -> Root / "neblink" / "remote-update" =>
        withAuth(req) {
          neblinkService match
            case None => Ok(Json.obj("success" -> false.asJson, "error" -> "NebLink not enabled".asJson))
            case Some(ns) =>
              req.as[Json].flatMap { body =>
                val targetDevice = body.hcursor.downField("device").as[String].getOrElse("")
                val beta = body.hcursor.downField("beta").as[Boolean].getOrElse(false)
                // 幂等键（hotupdate 批 3 · G8）：**可选**读取（缺席 = 现行为逐字节不变）；
                // 空串归一成缺席（与「本次未带键」同语义，见契约 §B.2「缺席 = 本次未带键」）。
                val clientRequestId = body.hcursor
                  .downField("clientRequestId")
                  .as[String]
                  .toOption
                  .map(_.trim)
                  .filter(_.nonEmpty)
                doRemoteUpdate(ns, targetDevice, beta, clientRequestId).flatMap {
                  case Right(msg) => Ok(Json.obj("success" -> true.asJson, "message" -> msg.asJson))
                  case Left(err) => Ok(Json.obj("success" -> false.asJson, "error" -> err.asJson))
                }
              }
        }

      /**
       * MVP-2 设备会话域统一（2026-09-15）：**设备会话发送面**
       * （`POST /api/devices/{device_id}/messages` → neblink-server
       * `src/friends.rs:1300 device_send_message`）。
       *
       * 段名是 **`devices`（复数）**：与服务端路由表逐字对齐（`src/friends.rs:1788`），
       * 且与既有的单数 `/api/device/` 设备认证面（`device/code`、`device/token` 等）
       * **命名空间不相交** —— 两者共享前缀会让「设备授权流」与「设备消息」两条语义
       * 完全不同的面在路由分派上互相遮挡。
       *
       * 🔴 **`origin` 闸**（复用 [[GroupSendOriginVerdict]] 的**同一**判据，不新写一套）：
       * 本路由同样是**用户身份直发**面（web 前端是它的唯一调用者，前端不得自报
       * `origin:"agent"`——那会把非 agent 通道的消息落库成 agent 代发，而
       * 「收端禁采信 wire `origin`」正是本批 P3 红线）。缺席 / 逐字 `"user"` ⇒ 原文
       * 转发（= 服务端缺省语义，字节零变化）；其余值 ⇒ `400` 显式拒绝，**零上游往返**。
       * 显式拒绝而非静默改写：静默剔键会让一次越界自报**静默消失**（本仓禁止的
       * 「静默不达」缺陷族）。
       *
       * 🔴 **请求体按原文转发**（[[rawBody]]）：不解析、不重编码 —— 解析后再编码会
       * 重排键并丢掉未知键（`attachments` 等加性键），等于替冻结契约改了形态。
       * `{device_id}` 路径段先 [[encSeg]] 编码（段内 `/`、`?` 注入面）。
       *
       * 🔴 幂等语义原样交给服务端（契约 §8.6：同 `clientMsgId` 重复 ⇒ **仍是 201**，
       * `existing:true` 仅表示回放原行）。本层**不**把 `existing` 折成别的状态码。
       */
      case req @ POST -> Root / "devices" / deviceId / "messages" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              rawBody(req).flatMap { body =>
                GroupSendOriginVerdict.check(body) match
                  case Left(reason) => BadRequest(Json.obj("error" -> reason.asJson))
                  case Right(()) =>
                    // 转发复用 [[groupProxy]] 的**同一实现**（同一 `(status, body)` 保留语义、
                    // 同一 live-client 缝、同一「身份只由 device session token 承载」纪律）。
                    // 该入口是「按原文转发任意上游路径」的通用代理口，群面只是它的第一个
                    // 调用方 ⇒ 设备面直接复用，**禁**为它复制第二份转发实现。
                    fs.groupProxy("POST", s"/api/devices/${encSeg(deviceId)}/messages", body)
                      .flatMap(groupProxyResult)
              }
        }

    } <+> SocialRoutes.routes(ctx)

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

  /**
   * Shared remote-update logic: P2P HTTP first, relay fallback. Used by REST + WS handlers.
   *
   * `clientRequestId`（hotupdate 批 3 · G8）= **可选**幂等键：仅在 P2P 载荷里作为
   * 加法字段随行（老端对端 read 只见 `beta`，未知键静默忽略）；缺席 ⇒ 载荷与改前
   * 逐字节相同。🔴 中继腿（`relayUpdateFallback`）的隧道参数面保持 `{beta}` 不变
   * ——隧道动作 `RemoteUpdate` 的参数集由跨仓契约钉死（契约 §B.1.3），本批零越仓。
   */
  private def doRemoteUpdate(
    ns: nebflow.neblink.NeblinkService,
    targetDevice: String,
    beta: Boolean,
    clientRequestId: Option[String] = None
  ): IO[Either[String, String]] =
    ns.peers.flatMap { peers =>
      peers.find(p =>
        p.deviceName.equalsIgnoreCase(targetDevice) ||
          p.deviceName.toLowerCase.contains(targetDevice.toLowerCase)
      ) match
        case None => IO.pure(Left(s"Device '$targetDevice' not found"))
        case Some(peer) =>
          if peer.address.isEmpty then IO.pure(Left(s"Device '$targetDevice' has no address"))
          else
            logger.info(s"Remote update via P2P: ${peer.deviceName} at ${peer.address} (beta=$beta)") *>
              IO.blocking {
                import sttp.client4.*
                val fields = List("beta" -> beta.asJson)
                  ++ clientRequestId.map(id => "clientRequestId" -> id.asJson)
                val body = Json.obj(fields*).noSpaces
                val resp = basicRequest
                  .post(sttp.model.Uri.unsafeParse(s"${peer.address}/api/neblink/update"))
                  .contentType("application/json")
                  .body(body)
                  .readTimeout(180.seconds)
                  .response(asStringAlways)
                  .send(ns.httpBackend)
                resp
              }.flatMap { resp =>
                if resp.code.isSuccess then IO.pure(Right("Update installed, device is restarting..."))
                else relayUpdateFallback(ns, peer, beta, s"P2P HTTP ${resp.code}")
              }.handleErrorWith { e =>
                relayUpdateFallback(ns, peer, beta, s"P2P unreachable: ${e.getMessage}")
              }
    }

  private def relayUpdateFallback(
    ns: nebflow.neblink.NeblinkService,
    peer: nebflow.neblink.PeerInfo,
    beta: Boolean,
    p2pError: String
  ): IO[Either[String, String]] =
    ns.relayClientOpt match
      case Some(client) =>
        logger.info(s"P2P update failed ($p2pError), trying relay to ${peer.deviceName}") *>
          client.relayUpdate(peer.deviceId, beta)
      case None => IO.pure(Left(p2pError))

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

  /** Run block only if NeblinkService is available and request is authenticated. */
  private def withNeblink(req: Request[IO])(f: NeblinkService => IO[Response[IO]]): IO[Response[IO]] =
    if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
    else
      neblinkService match
        case Some(ms) => f(ms)
        case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))

  /**
   * Verify peer-to-peer access.
   *
   * Primary check: is the caller IP in the trusted-peer list (populated from
   * NebLink Server discovery)? The NebLink Server is the trust boundary — only
   * devices on the same network can reach each other.
   *
   * Fallback: trust by device-ID network membership. The trusted IP list can be
   * stale or wrong (a peer's advertised endpoints didn't match its actual
   * source IP — NIC filtering, DHCP rotation, multi-homed host). When a caller
   * presents a deviceId we discovered via the NebLink Server (i.e. a confirmed
   * member of the same networkId), we accept it regardless of the IP list. The
   * relay path authenticates the same way with a server-issued token, so this
   * keeps the P2P-direct path on par with relay.
   *
   * The fallback is gated on a private/LAN source IP: a claimed deviceId alone
   * never grants access to internet-sourced requests (P2P-direct is LAN-only).
   */
  /**
   * 解析 Dropbox 分块头。**只有** `X-Dropbox-Proto` 明确为 1（且其余必需头齐备）时才
   * 返回 `Some` —— 任何缺失 ⇒ `None` ⇒ 走 legacy 整件路径（向后兼容的判定依据，
   * 契约 §3.9「未知/缺失字段不得静默到看似成功」：这里「缺失」有明确定义的降级行为）。
   *
   * 🔴 **判据**（等值，非 `>=`）**与头值（恒 1）都不得改动** —— 本批契约升版（设备腿
   * `targetDir`，`AttachContract.ProtoAssignDir = 2`）**只升 JSON 面数值轴**。把发送端
   * 头值升成 `2` 会让旧接收端 guard 为假 ⇒ 走 legacy 整件 ⇒ 静默数据损坏；把 `==` 改成
   * `>=` 则让新头被本次实现接受、却对旧端仍无救（掩盖破坏面）。实现体已逐字抽到
   * [[nebflow.dropbox.DropboxChunkHeaderParser]]（可判定性抽出，语义零改动），
   * 常绿钉见 `AttachProtoHeaderPinSpec`。
   */
  private def parseDropboxChunkHeaders(req: Request[IO]): Option[nebflow.dropbox.DropboxService.ChunkHeaders] =
    nebflow.dropbox.DropboxChunkHeaderParser.parse(req.headers)

  /** 分块接收失败的结构化回执（会话不存在 ⇒ 404，其余 ⇒ 500）。 */
  private def chunkErrorResponse(err: nebflow.dropbox.AttachContract.AttachError): Response[IO] =
    val status =
      if err.code == nebflow.dropbox.AttachContract.Codes.SessionNotFound then Status.NotFound
      else Status.InternalServerError
    Response[IO](status).withEntity(err.toJson)

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

  private def verifyPeerAccess(req: Request[IO]): IO[Either[Response[IO], NeblinkService]] = neblinkService match
    case None =>
      IO.pure(Left(Response[IO](Status.NotFound).withEntity(Json.obj("error" -> "NebLink not enabled".asJson))))
    case Some(ms) =>
      val remoteIp = req.remoteAddr.fold("")(a => a.toString)
      if ms.isTrustedPeer(remoteIp) then IO.pure(Right(ms))
      else
        val callerDeviceId =
          req.headers.get(CIString("x-neblink-device")).map(_.head.value).getOrElse("")
        isKnownNetworkDevice(ms, callerDeviceId, remoteIp).flatMap {
          case true =>
            logger.info(
              s"Peer $callerDeviceId trusted by device-ID membership (IP $remoteIp not in trusted list)"
            ) *> IO.pure(Right(ms))
          case false =>
            IO.pure(
              Left(
                Response[IO](Status.Forbidden)
                  .withEntity(Json.obj("error" -> s"Not a trusted peer (from $remoteIp)".asJson))
              )
            )
        }

      end if

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
   * POST the enrollment body to the NebLink Server and parse the JSON reply.
   * Uses java.net.http directly (mirrors NeblinkClient) to avoid pulling an
   * http4s client dependency into this routes class. Bypasses the system proxy
   * so direct LAN access works.
   *
   * Isolation guard (2026-09-11): single choke point for the pairing-code
   * enroll path — an instance on a redirected data root does not auto-register
   * with the production network (see `EnrollGuard`). Refusal is returned on the
   * error channel (caller renders "Enrollment failed: …") AND logged, so it is
   * never silent; `NEBFLOW_ALLOW_PROD_ENROLL=1` restores the old behaviour.
   */
  private def enrollWithServer(serverUrl: String, body: String): IO[Either[String, Json]] =
    nebflow.neblink.EnrollGuard.enrollRefusal(serverUrl) match
      case Some(reason) =>
        logger.warn(s"enroll refused by the isolation guard: $reason").as(Left(reason))
      case None =>
        proxyPost(serverUrl, nebflow.neblink.Protocol.DeviceApi.enroll, body)

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
   * Generic POST proxy to the NebLink Server. Returns the parsed JSON on
   * success (2xx) or an error message on failure. Bypasses the system proxy.
   */
  /**
   * Shared completion for BOTH device-flow paths (self-hosted token poll
   * and Logto register) and the AC+PKCE callback: read the EnrollResponse
   * fields, persist the credential, switch the config, hot-swap the client,
   * and record the user's profile info. `logtoRefresh` carries the provider
   * refresh token (AC+PKCE / silent re-login) into the persisted credential.
   *
   * `explicitUserAction` (2026-09-14 案 C ①(b)) is forwarded to the isolation
   * gate; only the PKCE callback — downstream of a matched, single-use login
   * state — passes `true`. Default `false` = the device-flow poll path, which
   * has no server-side marker proving who started it.
   */
  private def completeDeviceEnrollment(
    ms: NeblinkService,
    resolvedUrl: String,
    json: Json,
    logtoRefresh: Option[String] = None,
    logtoIdToken: Option[String] = None,
    explicitUserAction: Boolean = false
  ): IO[org.http4s.Response[IO]] =
    completeDeviceEnrollmentDetailed(ms, resolvedUrl, json, logtoRefresh, logtoIdToken, explicitUserAction)
      .flatMap {
        case Right(networkId) =>
          Ok(
            Json.obj(
              "ok" -> true.asJson,
              "networkId" -> networkId.asJson
            )
          )
        // 案 C ①(a)：不再把失败压成泛化串——`err` 原文（隔离护栏拒绝时就是
        // `EnrollGuard` 的 reason）走调用方的渲染面。
        case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
      }

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

  private def proxyPost(serverUrl: String, path: String, body: String): IO[Either[String, Json]] =
    IO.blocking {
      // The shared OutboundHttpClients.Policy.Direct15s client: HTTP/1.1, system
      // proxy bypassed, 15 s connect. Previously built per call (device-flow
      // code/token/enroll) — now one memoized instance (D5, 2026-09-13).
      //
      // D1 — why HTTP/1.1 here, and what would flip it:
      //   · Evidence: this peer is the neblink-server, behind the SAME Caddy as
      //     NeblinkClient. The 2026-09-12 probe of that topology served 14/14
      //     requests 200 with ALPN=h2, 0 GOAWAY, 0 TLS alert, 5/5 TLS 1.3
      //     resumptions accepted ⇒ the old "HTTP/2 reuse + TLS 1.3 resumption
      //     clashes with Caddy" sentence has no support on this link; it was
      //     copied from the 2026-08-11 upstream (nginx/one-api) incident (42fd15b6
      //     self-describes it as "same TLS fix as 3773699b"). 未证 either way:
      //     the original symptom was intermittent and left no logs, so one
      //     green local window does not prove the trap absent.
      //   · Judge-red: a GOAWAY / closed-reset bucket on this non-idempotent
      //     POST in the outbound-failure counters, or a reproduced TLS alert,
      //     or same-window h2 p95 > h1 p95 × 1.2 (n ≥ 100/arm) — then re-review
      //     the pin (not before; no version decision moves on this evidence).
      val client = OutboundHttpClients.client(OutboundHttpClients.Policy.Direct15s)
      val request = java.net.http.HttpRequest
        .newBuilder()
        .uri(java.net.URI.create(s"${serverUrl.stripSuffix("/")}$path"))
        .timeout(java.time.Duration.ofSeconds(15))
        .header("Content-Type", "application/json")
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
        .build()
      try
        val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        val respBody = response.body()
        if status >= 200 && status < 300 then
          parser.parse(respBody) match
            case Right(json) => Right(json)
            case Left(err) => Left(s"invalid JSON from server: ${err.message}")
        else
          // Surface the server's error message if it's JSON (e.g. authorization_pending).
          parser.parse(respBody) match
            case Right(json) =>
              json.hcursor.downField("error").as[String].toOption match
                case Some(errMsg) => Left(errMsg)
                case None => Left(s"HTTP $status")
            case Left(_) => Left(s"HTTP $status")
      catch case e: Exception => Left(e.getMessage)
      end try
    }.handleErrorWith(e => IO.pure(Left(e.getMessage)))

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
