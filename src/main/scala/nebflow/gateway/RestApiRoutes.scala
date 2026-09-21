package nebflow.gateway

import cats.effect.IO
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.syntax.*
import io.circe.{Json, JsonObject, parser}
import nebflow.agent.AgentCore
import nebflow.agent.SharedResources
import nebflow.core.AtomicJson
import nebflow.core.Branding
import nebflow.core.CanvasTabs
import nebflow.core.CanvasTabStore
import nebflow.core.PathUtil
import nebflow.core.daemon.{DaemonConfig, DaemonService, DaemonStore}
import nebflow.core.entity.{EntityLoader, NodeRoute}
import nebflow.core.flow.{FlowTreeRegistry, TreeCommand}
import nebflow.core.hotrestart.HealthPayload
import nebflow.core.presets.{ModelPreset, PresetFile, PresetStore}
import nebflow.core.project.{NodeEngine, NodePayload, ProjectActor, ProjectRuntimeRegistry, ProjectStore}
import nebflow.core.skill.SkillService
import nebflow.core.tools.NodeTools
// FreezeScheduleConfig encoder givens (workSchedule runtime-authoritative PATCH)
import nebflow.core.schedule.FreezeSchedule.given
import nebflow.core.task.{FileTaskStore, TaskStore}
import cats.effect.unsafe.implicits.global
import nebflow.llm.{HealthState, LlmProtocol, NebflowServiceConfig, SearchApiHealth}
import nebflow.llm.providers.ModelListFaces
import nebflow.neblink.*
import nebflow.neblink.FriendCodecs.given
import nebflow.service.ConfigService
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.{Authorization, Location, `Content-Type`}
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
  wsHub: WsHub = new WsHub
):
  private val logger = nebflow.core.NebflowLogger.forName("nebflow.rest-api")

  /** Logto AC+PKCE login single-flight state (stage 2, 2026-08-28). */
  private val pkceLogin = PkceLoginSession.unsafe

  /** Switch-account single-window handoff marker (2026-09-16, one-window
    * switch batch): the ONE place that knows whether the current logout hop is
    * a switch-account flow, so the `/auth/logged-out` landing page it ends on
    * can continue the login in the same window. Lifecycle (arm / consume /
    * disarm, single-use, TTL) lives in [[NeblinkSwitchHandoff]]. */
  private val switchHandoff = NeblinkSwitchHandoff.unsafe

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
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
                    wsHub, sessionStore, wsRoutes.dispatchHeadlessTurn, sessionId, content, timeoutSec)
                }
            }
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
          rawValue <- IO.blocking {
            if !os.exists(configPath) then None
            else
              io.circe.parser
                .parse(os.read(configPath))
                .toOption
                .flatMap(_.hcursor.downField("safety").downField("defaultMode").as[String].toOption)
          }.handleErrorWith(_ => IO.pure(None))
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
        ProjectStore.list().map { projects =>
          Json.obj(
            "projects" -> projects.map(p =>
              Json.obj(
                "name" -> p.name.asJson,
                "workspace" -> p.workspace.asJson,
                "agentFile" -> p.agentFile.asJson,
                "description" -> p.description.asJson,
                "createdAt" -> p.createdAt.asJson
              )
            ).asJson
          )
        }.flatMap(Ok(_))
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
              inWindow = bt.values.toList.flatMap { meta =>
                val members = meta.nodeIds.flatMap(id => arch.nodes.get(id)).toList.sortBy(_.createdAt)
                val completedAt = members.flatMap(_.completedAt).foldLeft(0L)(math.max)
                if members.isEmpty || (completedAt > 0L && now - completedAt > NodeEngine.TtlDisplayMs) then None
                else Some(Json.obj(
                  "id" -> meta.id.asJson,
                  "archivedAt" -> meta.archivedAt.asJson,
                  "completedAt" -> completedAt.asJson,
                  "members" -> members.map(n => NodePayload.buildNodeJson(n, now)).asJson
                ))
              }.sortBy(j => -(j.hcursor.get[Long]("completedAt").toOption.getOrElse(0L)))
              resp <- Ok(Json.obj(
                "batches" -> inWindow.asJson,
                "ttlMs" -> NodeEngine.TtlDisplayMs.asJson,
                "count" -> inWindow.size.asJson
              ))
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
                Ok(Json.obj(
                  "id" -> n.id.asJson,
                  "name" -> n.name.asJson,
                  "status" -> n.status.asJson,
                  "result" -> n.result.asJson
                ).deepMerge(Json.obj((histFeedback ++ descLong)*)))
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
          (sharedResources.freezeScheduleRef.get,
           sharedResources.thinkingConfigRef.get,
           sharedResources.toolResultTtlRef.get).mapN { (wsCfg, thCfg, ttlCfg) =>
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
              Json.obj("key" -> "vision".asJson, "label" -> "图片理解".asJson, "description" -> "支持 image_url 图片输入".asJson)
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
              if visionOpt.contains(true) then nebflow.llm.EmptyCompletionTracker.shared.clearOverride(providerId, modelId)
              else IO.unit
            clearRuntime *> Ok(Json.obj("status" -> "ok".asJson))
          else BadRequest(Json.obj("error" -> "providerId and modelId required".asJson))
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
          peerOnline = (p: nebflow.neblink.PeerInfo) =>
            NeblinkService.isPeerOnline(p, nowMs, cfg.syncIntervalSec)
          // Account identity hints (switch-account, 2026-09-10): decoded
          // READ-ONLY from the ALREADY-persisted id_token (no extra I/O —
          // `cred` is loaded right below anyway). Same trust rationale as
          // the C2 picture claim: TLS-sourced token, claim read only, the
          // token never leaves the store. The web client's account memory
          // persists ONLY these two display strings — never any credential.
          acctClaims = cred.flatMap(_.logto.flatMap(_.idToken)) match
            case Some(tok) => LogtoAuthCode.decodeIdTokenClaims(tok, Seq("email", "name"))
            case None      => Map.empty[String, String]
          // 批 C（§3.7）：好友消息面的**对账计数暴露**。批 A 只产不曝（`FriendService`
          // 的 `recordPull`/`recordPullLine` 是唯一计数点，注释已声明暴露归批 C），
          // 本行即那条指令的落点——**读的就是批 A 那份累加值**，不另建计数器
          // （两份计数 = 两个读数会各说各话 = 判据④不可机械判）。
          // 未装配 friendService（未配置 NebLink Server）⇒ 显式 `null`，不是空对象：
          // 「没有这个面」与「有这个面且计数全 0」必须可区分（同既有 `relay` 字段口径）。
          friendPull <- sharedResources.friendService match
            case Some(fs) => fs.pullCountersJson
            case None     => IO.pure(Json.Null)
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
                    "lastDialError" -> dialStatusOf(p.deviceId).flatMap(_.error).asJson, // C3: why not (None = last dial succeeded)
                    "lastDialAt" -> dialStatusOf(p.deviceId).map(_.atMs).asJson, // C3: when that dial happened (null = never dialed)
                    "dialEndpoint" -> dialStatusOf(p.deviceId).map(_.endpoint).asJson, // C1: candidate actually dialed / won
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
                  val ct = org.http4s.headers.`Content-Type`.parse(contentType)
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
                            val ct = org.http4s.headers.`Content-Type`.parse(contentType)
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
                        case Left(err) =>
                          BadGateway(Json.obj("error" -> err.asJson))
                      }
              }
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
        performLocalLogout(ms) *> Ok(Json.obj("ok" -> true.asJson))
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
            if switchScenario then switchHandoff.arm(uiLocales) else switchHandoff.disarm
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
                        _ <- performLocalLogout(ms)
                        _ <- logger.info("RP-initiated logout: local teardown done, returning the provider end_session URL")
                        // 出口 = 200 + JSON（不再是 302）：见上方注释（fetch 无法消费跨域 302）。
                        r <- Ok(Json.obj("endSessionUrl" -> target.asJson))
                      yield r
                    // Same surface as auth/start: unconfigured provider (or AC app
                    // id missing) — the caller falls back to the local-only logout,
                    // and no landing hop will ever come back to consume the marker.
                    case _ =>
                      switchHandoff.disarm *> NotFound(Json.obj("error" -> "logto-not-configured".asJson))
                yield resp
              // 意外失败（拆除腿异常等）⇒ **可判读的失败页**：三段式文案，绝不再把裸异常
              // 变成无解释的 500（`getMessage` 直出 = 上游 §4 第 3 处丢失点）。
              run.handleErrorWith { e =>
                val diagnostic = nebflow.neblink.CredentialDiagnostics.classifyFailure(
                  e,
                  nebflow.neblink.CredentialFailure.Unclassified
                )
                logger.warn(diagnostic.logLine("end-session failed"), "code" -> diagnostic.code) *>
                  htmlResponse(callbackPage(ok = false, diagnostic.message), Status.InternalServerError)
              }
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
                                        case Left(err)   => Left(err)
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
              _ <- switchHandoff.disarm
              authorizeUrl <- beginPkceLogin(ms, forceLogin, uiLocales)
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
      else pkceLogin.statusJson.flatMap(Ok(_))

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
      else switchHandoff.stateName.flatMap(s => Ok(Json.obj("state" -> s.asJson)))

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
                  case Left(err)   => Ok(Json.obj("error" -> err.asJson, "output" -> "".asJson))
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
                      case Right(Left(err))     => Ok(Json.obj("error" -> err.message.asJson, "output" -> "".asJson))
                      case Left(e) =>
                        Ok(Json.obj("error" -> s"Tool execution failed: ${e.getMessage}".asJson, "output" -> "".asJson))
                    }
                  case None =>
                    BadRequest(Json.obj("error" -> s"Unknown tool: $action".asJson))
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
              val clientRequestId = body.hcursor.downField("clientRequestId").as[String].toOption
                .map(_.trim).filter(_.nonEmpty)
              doRemoteUpdate(ns, targetDevice, beta, clientRequestId).flatMap {
                case Right(msg) => Ok(Json.obj("success" -> true.asJson, "message" -> msg.asJson))
                case Left(err)  => Ok(Json.obj("success" -> false.asJson, "error" -> err.asJson))
              }
            }
      }

    // ===== A2A 好友与消息端点（spec §6.1 客户端 UI 代理层） =====
    // 全部经 FriendService → NeblinkClient 代理到 neblink-server（Bearer device
    // session token）。friendService 仅在 NebLink Server 配置时存在。

    /** 好友列表 + 双向 pending 请求。 */
    case req @ GET -> Root / "friends" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            // F4 (2026-09-10 friend-search batch): list loads go through the
            // DIRECT path — upstream Left folds to 502 so the frontend can
            // tell "no friends yet" apart from "load failed". The folded
            // empty-list variant (FriendService.refreshFriends) stays for the
            // background refresh chain only.
            fs.listFriends.flatMap {
              case Right(resp) => Ok(resp.asJson)
              // 2026-09-11：上游 Left 走单一判据（未登录 → 401/404，其余 → 502）。
              case Left(err)   => friendErr(err)
            }
      }

    /** 待处理请求分组（incoming / outgoing）。
      *
      * 2026-09-11 明写「不改（无消费者）」：本端点走折叠版
      * `FriendService.refreshFriends`（上游 Left 在服务内折成空表 ⇒ 恒 200），
      * 前端 `friendsApi.js` 对该路径只有 POST，incoming/outgoing 全部来自
      * `GET /api/friends`（含本面板）⇒ 不接 friendErr、不扩大改动面。 */
    case req @ GET -> Root / "friends" / "requests" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            fs.refreshFriends().flatMap { resp =>
              Ok(Json.obj(
                "incoming" -> resp.incoming.asJson,
                "outgoing" -> resp.outgoing.asJson
              ))
            }
      }

    /** 发好友请求（按 NebLink 号寻址）。body: {query, note?} */
    case req @ POST -> Root / "friends" / "requests" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            req.as[Json].flatMap { body =>
              val query = body.hcursor.downField("query").as[String].getOrElse("")
              val note = body.hcursor.downField("note").as[Option[String]].toOption.flatten
              if query.isEmpty then BadRequest(Json.obj("error" -> "Missing query".asJson))
              else
                fs.sendFriendRequest(query, note).flatMap(friendResult)
            }
      }

    case req @ POST -> Root / "friends" / "requests" / requestId / "accept" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) => fs.acceptFriendRequest(requestId).flatMap(friendResult)
      }

    case req @ POST -> Root / "friends" / "requests" / requestId / "decline" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) => fs.declineFriendRequest(requestId).flatMap(friendResultRaw)
      }

    /** 发消息给好友（用户身份——UI 输入框直发，无 agent 权限档位）。
      *
      * body: `{body, attachments?, clientMsgId?}`（attachcl / P2-b 两批加性扩面）。
      *
      * `attachments` = **已上传**的附件 id 列表（顺序 = 展示顺序），由本路由**逐字**
      * 转给 `FriendService.sendAsUser` → `NeblinkClient.sendFriendMessage`。🔴 本层
      * 只搬运 id、**不**判权限（关系闸在服务端 E1/E2/E3）、**不**做上传（字节面 =
      * `POST /api/attachments`，同一分块驱动）。
      *
      * `clientMsgId`（P2-b）= 客户端**发送动作**侧的幂等键（同动作重试复用同键）。
      * 本路由**只做**「读出 + 透传」两件事：不判重、不去重、不缓存、不改状态码
      * —— 幂等判定**全在服务端**（§8.6：同键重复仍是 201、`existing:true` 仅表示
      * 回放原行）。🔴 加性判据：键缺席 / 空串 / 非字符串 ⇒ `None` ⇒ 转发形态与今天
      * **逐字节同形**（旧客户端零变化；`NeblinkClient` 只在 `Some` 时发该键）。
      * 🔴 本路由**是**在转发前读请求体的既有腿（`{body, attachments}` 早已如此），
      * 但**只读**这几个键、**原样**取值——不重建请求体、不重排、不丢未知键。
      *
      * 正文闸的加性放开：`body` 为空**仅当** `attachments` 非空时允许（服务端 §B.4
      * 有附件时生成占位正文）——这是**拓宽**而不是收紧：无附件时空正文仍逐字 400
      * （旧行为不变，与群路由的服务端校验序同源）。
      *
      * `replyToMessageId`（quotejump 批加性扩面 · 作者裁 (c) 双写双读）= 被引消息的
      * `messages.id`（**整数**）。本路由**只做**「读出 + 透传」两件事，与上面三键同款：
      * 不校验坐标、不解析会话归属、不改状态码 —— 🔴 坐标合法性与会话归属**全在服务端**
      * 写事务内判定（异会话 / 无此行 ⇒ **400 `REPLY_TARGET_INVALID`**，零副作用），
      * 本层自行「校验」即造出第二套真相。🔴 加性判据：键缺席 / `null` / 非正整数 ⇒
      * `None` ⇒ 转发形态与今天**逐字节同形**（旧客户端零变化；`NeblinkClient` 只在
      * `Some` 时发该键）。🔴 **本路由只读已列出的键、逐字取值，不重建请求体** ——
      * 下游 `NeblinkClient` 的 `Json.fromFields(fields)` 才是事实上的出站白名单
      * （新键若不在这三处显式出现就会在网关**静默消失**，表现 = 跨会话跳转不可达而
      * 同会话仍可跳，缺陷隐蔽）。🔴 群腿不受影响：群发送是**原文转发**（见本文件
      * `groupSendProxy`），外仓群/设备体刻意不收该键（D-6），本批禁为群腿硬塞字段。 */
    case req @ POST -> Root / "friends" / friendUserId / "messages" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            req.as[Json].flatMap { body =>
              val text = body.hcursor.downField("body").as[String].getOrElse("")
              val attachmentIds = body.hcursor
                .downField("attachments")
                .as[List[String]]
                .getOrElse(Nil)
                .filter(_.nonEmpty)
              val clientMsgId = body.hcursor
                .downField("clientMsgId")
                .as[String]
                .toOption
                .filter(_.nonEmpty)
              val replyToMessageId = body.hcursor
                .downField("replyToMessageId")
                .as[Long]
                .toOption
                .filter(_ > 0)
              if text.isEmpty && attachmentIds.isEmpty then BadRequest(Json.obj("error" -> "Missing body".asJson))
              else
                // rcptcode 批（好友腿终态码 502 折叠修复 · 折叠点 ②）：本路由改走
                // **保留状态码**通道 —— `sendAsUserWithStatus` + 既有唯一映射器
                // `groupProxyResult`（上游状态码逐字 + 体优先 JSON；`Left` 仍走
                // `friendErr` ⇒ 传输失败 502 / 未登录三态**逐字不变**）。
                // 🔴 为什么不新写第二套映射：群腿 `groupSendProxy` 与附件腿已各自透传，
                // `(status, body) ⇒ Response` 只有 `groupProxyResult` 这一个事实源。
                // 🔴 本路由是好友域**唯一**采用该通道的腿（其余 20+ 好友路由继续折叠 ——
                // 是否推广是另一刀；`GET /friends` 的 502 是 F4 有意为之，勿顺手改）。
                fs.sendAsUserWithStatus(friendUserId, text, attachmentIds, clientMsgId, replyToMessageId)
                  .flatMap(groupProxyResult)
            }
      }

    /** 删除好友。 */
    case req @ DELETE -> Root / "friends" / friendUserId =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) => fs.removeFriend(friendUserId).flatMap(friendResultRaw)
      }

    /** 拉黑好友（#290 §1.2 WeChat 式黑名单）。 */
    case req @ POST -> Root / "friends" / friendUserId / "block" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) => fs.blockFriend(friendUserId).flatMap(friendResultRaw)
      }

    /** 移出黑名单（仅拉黑方；上游非拉黑方 403 not_blocker → BadGateway 透传错误）。 */
    case req @ POST -> Root / "friends" / friendUserId / "unblock" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) => fs.unblockFriend(friendUserId).flatMap(friendResultRaw)
      }

    /** 设置 / 清除好友备注（⑦，2026-09-12）。body `{remark}` → 200 `{ok:true}`。
      *
      * 逐行镜像 `PUT /neblink/peer-description` 的形态（withAuth + 缺参 400），
      * 差别只有一处：**备注是 home 本地态、不触上游** ⇒ 本端点**没有** 502 /
      * `friendErr` 分态（唯一失败面 = 400 缺参；未认证 = withAuth 403）。
      * 语义：`trim` 后空串 = 清除（删键）；`remark` 键缺席 / null / 非字符串 =
      * 缺参 400（冻结契约形 `{"remark":"<string>"}`——清备注用 `""`，不用 null）。
      * 回显：响应恒 `{ok:true}`，不回带 remark（前端本地已有值，无二次真相源）。
      */
    case req @ PUT -> Root / "friends" / friendUserId / "remark" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            req.as[Json].flatMap { body =>
              body.hcursor.downField("remark").as[String].toOption match
                case Some(remark) =>
                  fs.setRemark(friendUserId, remark) *> Ok(Json.obj("ok" -> true.asJson))
                case None => BadRequest(Json.obj("error" -> "Missing remark".asJson))
            }
      }

    /** 会话列表（按 last_message_id 倒序，含 unreadCount）。 */
    case req @ GET -> Root / "conversations" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            fs.refreshConversations().flatMap(convs => Ok(convs.asJson))
      }

    /** keyset 分页拉消息。?after=N&limit=N */
    case req @ GET -> Root / "conversations" / conversationId / "messages" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            val after = req.params.get("after").flatMap(_.toLongOption).getOrElse(0L)
            val limit = req.params.get("limit").flatMap(_.toIntOption).getOrElse(50)
            fs.listMessages(conversationId, after, limit).flatMap(r => friendResult(r.map(_.asJson)))
      }

    /** 附件下载（4b 腿 A-3，裁定②：「下载面走**应用内鉴权路由**」）。
      *
      * **唯一取字节入口**：前端（`friendsApi.js#downloadFriendAttachment`）只能经本路由
      * 取字节，拿不到服务端地址/凭证；服务端附件目录**不挂 Caddy**（§D.1、§A.2 N3）
      * ⇒ 全链路不存在任何静态/公开 URL 面（🔴 红线：禁直出静态 URL 绕过鉴权）。
      *
      * 鉴权 = 本网关的既有 `withAuth`（同其余 `/friends*`、`/conversations*` 面）；
      * 关系闸（`friendship_accepted`，含拉黑）在服务端 E3 上，本层**不复制**第二套
      * 权限判定（禁双实现）。
      *
      * 状态码**逐字透传**上游，不折叠：`410` = 附件已过期（**终态**，§B.7 ③ 要求客户端
      * 能把「已过期」与「下载失败」分开）；`404` = 不存在/对调用方不可见；`403` = 非好友。
      * 其余（含 5xx）⇒ 502 + 逐字原因（可重试态）。
      */
    case req @ GET -> Root / "friends" / "attachments" / attachmentId =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            fs.downloadAttachment(attachmentId).flatMap {
              case Left(err) => friendErr(err)
              case Right(fetch) =>
                fetch.status match
                  case 200 =>
                    // 显式字节流实体（同 /neblink/avatar 先例：裸 Ok(Array[Byte]) 会命中
                    // circe 的 byte 数组编码器，把字节变成 JSON 数字数组 ⇒ 文件损坏）。
                    val attHeaders = Headers(
                      List(
                        Some(Header.Raw(CIString("Content-Type"), "application/octet-stream")),
                        fetch.contentDisposition.map(d => Header.Raw(CIString("Content-Disposition"), d)),
                        fetch.sha256Header.map(h => Header.Raw(CIString("X-Attachment-Sha256"), h))
                      ).flatten
                    )
                    IO.pure(
                      Response[IO](Status.Ok)
                        .withEntity(fs2.Stream.emits(fetch.bytes).covary[IO])
                        .withHeaders(attHeaders)
                    )
                  case 410 => Gone(Json.obj("error" -> "attachment_expired".asJson))
                  case 404 => NotFound(Json.obj("error" -> "attachment_not_found".asJson))
                  case 403 => Forbidden(Json.obj("error" -> "not_friends".asJson))
                  case other =>
                    BadGateway(Json.obj("error" -> s"attachment download failed upstream: HTTP $other".asJson))
            }
      }

    /** **附件上传**（attachcl 批，2026-09-16）——网页腿的**唯一**字节入口。
      *
      * 上传形态（A1 = ②）：**网页整件一次请求 → 网关 → 复用桌面分块驱动**。
      * 浏览器把整件放进请求体（`fetch(..., {body: file})`，Chromium 自带流式发送），
      * 本路由把请求体**流式**落临时件（`streamToFileWithHashBounded`，上限 1 GiB 在
      * **读的过程中**生效），然后把临时件交给 [[nebflow.neblink.AttachUpload.pushFile]]
      * —— **与桌面腿同一份** E1+E2×n 链（块大小仍是 `AttachContract.plan` 的 4 MiB，
      * 单块峰值内存与文件大小无关）。
      *
      * 🔴 **硬钉①（禁整件缓冲）机械判据**：本方法体里**不出现** `req.as[Array[Byte]]` /
      * `bodyText.compile.string` / `req.as[String]` / `req.as[Json]` —— 请求体只以
      * `req.body`（`Stream[IO, Byte]`）形态被消费一次；单块字节的 `Array[Byte]` 只出现在
      * [[nebflow.neblink.AttachUpload.pushChunks]] 的 4 MiB `readRange` 里。
      *
      * 🔴 **硬钉②（禁假进度 / 失败可见）**：进度只由 [[nebflow.neblink.AttachUpload.Hooks.onChunk]]
      * 在**服务端确认一块之后**广播（WS 帧 `attach-upload-progress`），因此不存在
      * 「到点 100%」；成败**一律**落在本次响应的 `ok` 上（失败 ⇒ 非 2xx + 可判读
      * `code`/`error`，取消 ⇒ `409` + `code:"cancelled"`）——绝不把拒绝塞进 2xx。
      *
      * 🔴 **E1 早拒（1 GiB，不得晚于传输前）**：`X-Attach-Size`（缺省用 `Content-Length`）
      * 超限 ⇒ 在**读请求体之前** `413`；声明缺失/撒谎 ⇒ 流内上限兜底（同样早于任何
      * 上游字节：落盘阶段就断）。
      *
      * 会话寻址 `conversationId` = 好友 userId / 群会话 id（服务端 E1 对这段段做
      * `friendship_accepted` ∨ `is_member_gated`）⇒ **好友与群同一路由、同一驱动、同一渲染**
      * （A4：两面同批）。
      *
      * 临时件在所有出口删除；取消见下一条路由。
      */
    case req @ POST -> Root / "attachments" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            val conversationId = req.params.getOrElse("conversationId", "")
            val name           = req.params.getOrElse("name", "")
            val rawUploadId    = req.params.getOrElse("uploadId", "")
            // 🔴 **形态闸（attachid 批）**：`uploadId` 是**客户端可控**的查询参数，且会被当作
            // **单个路径段**消费（`FriendService.uploadStream` 拼临时件路径）⇒ 非空时先过
            // **唯一**判定点 [[nebflow.neblink.AttachUploadId]]。判定在**任何路径拼接 / 登记 /
            // 读请求体之前** ⇒ 非法 id 零落盘 / 零上游调用 / 零临时件（复核位 A1 的原始缺陷：
            // 含 `/` 的 id ⇒ 500 + 空体）。
            // 🔴 **缺席 / 空白仍是合法语义**（本次上传不可取消、服务端自造 `anon-…`）⇒ 只在
            // 非空时判，该面逐字不变（既有合法路径零回归）。
            val uploadIdForm: Either[String, Option[String]] =
              val trimmed = rawUploadId.trim
              if trimmed.isEmpty then Right(None) else AttachUploadId.validate(trimmed).map(Some(_))
            val declared       = req.headers.get(CIString("X-Attach-Size")).map(_.head.value.trim.toLongOption).getOrElse(req.contentLength)
            if conversationId.trim.isEmpty then
              BadRequest(Json.obj("ok" -> false.asJson, "code" -> "invalid_argument".asJson, "error" -> "Missing conversationId".asJson))
            else if name.trim.isEmpty then
              BadRequest(Json.obj("ok" -> false.asJson, "code" -> "invalid_argument".asJson, "error" -> "Missing name".asJson))
            else if uploadIdForm.isLeft then
              // 与相邻两条 400 **逐字同形**（`{ok:false, code:"invalid_argument", error}`）：
              // 网关既有 4xx 信封先例就在本路由，**不新造第二套错误形状**；`error` 文案由闸
              // 单点给出（自描述 + 实际值回显）。
              BadRequest(
                Json.obj(
                  "ok" -> false.asJson,
                  "code" -> AttachUploadId.ErrorCode.asJson,
                  "error" -> uploadIdForm.left.toOption.getOrElse("").asJson
                )
              )
            else
              // 早拒三段（全部**先于**读请求体）：声明超限 / 声明非正 / 无声明但 Content-Length 超限。
              declared match
                case Some(size) if size > nebflow.dropbox.AttachContract.MaxFileBytes =>
                  IO.pure(
                    Response[IO](Status.PayloadTooLarge).withEntity(
                      Json.obj(
                        "ok" -> false.asJson,
                        "code" -> "attach_too_large".asJson,
                        "actual" -> size.asJson,
                        "limit" -> nebflow.dropbox.AttachContract.MaxFileBytes.asJson,
                        "error" -> s"Attachment too large: $size bytes exceeds the ${nebflow.dropbox.AttachContract.MaxFileBytesLabel} limit. Nothing was uploaded.".asJson
                      )
                    )
                  )
                case Some(size) if size <= 0L =>
                  IO.pure(
                    Response[IO](Status.UnprocessableEntity).withEntity(
                      Json.obj(
                        "ok" -> false.asJson,
                        "code" -> "empty_file".asJson,
                        "error" -> "Attachment gate rejected: empty file (0 bytes) — nothing was uploaded.".asJson
                      )
                    )
                  )
                case _ =>
                  // uploadId = 取消键（客户端生成）。缺席 ⇒ 本次上传不可取消（仍可用），
                  // 服务端生成一枚仅供进度帧关联，**不**登记取消位（禁伪造可取消面）。
                  // 🔴 非空时**必须**用闸后的值（`uploadIdForm` 的右侧），禁用原始入参：
                  // 登记键 / 临时件名 / 进度帧字段 / 响应字段必须是同一枚**已判合法**的串。
                  val checked     = uploadIdForm.getOrElse(None)
                  val uploadId    = checked.getOrElse(s"anon-${java.util.UUID.randomUUID().toString}")
                  val cancellable = checked.isDefined
                  val registry = sharedResources.attachUploads
                  val hooks = nebflow.neblink.AttachUpload.Hooks(
                    onChunk = (pr: nebflow.neblink.AttachUpload.Progress) =>
                      wsHub.broadcast(
                        // 🔴 帧形状与同族 `dropbox-file-progress` 逐字同构（`type` + 会话键 +
                        // **嵌套 `msg`**）：载荷在 `msg` 下，前端读点 = `msg.uploadId` 等。
                        // （本批实测踩到：扁平帧前端读不到 ⇒ 进度永不推进的「看起来对的错」。）
                        Json.obj(
                          "type" -> "attach-upload-progress".asJson,
                          "conversationId" -> conversationId.asJson,
                          "msg" -> Json.obj(
                            "uploadId" -> uploadId.asJson,
                            "conversationId" -> conversationId.asJson,
                            "name" -> name.asJson,
                            "chunkIndex" -> pr.chunkIndex.asJson,
                            "bytesSent" -> pr.bytesSent.asJson,
                            "totalBytes" -> pr.totalBytes.asJson
                          )
                        )
                      ),
                    cancelled = if cancellable then registry.isCancelled(uploadId) else IO.pure(false)
                  )
                  val run =
                    if cancellable then registry.register(uploadId) else IO.unit
                  (run *> fs.uploadStream(conversationId, name, uploadId, req.body, hooks))
                    .guarantee(if cancellable then registry.release(uploadId) else IO.unit)
                    .flatMap {
                      case Right(up) =>
                        wsHub.broadcast(
                          Json.obj(
                            "type" -> "attach-upload-done".asJson,
                            "conversationId" -> conversationId.asJson,
                            "msg" -> Json.obj(
                              "uploadId" -> uploadId.asJson,
                              "conversationId" -> conversationId.asJson,
                              "ok" -> true.asJson,
                              "attachmentId" -> up.attachmentId.asJson
                            )
                          )
                        ) *>
                          IO.pure(
                            Response[IO](Status.Created).withEntity(
                              Json.obj(
                                "ok" -> true.asJson,
                                "attachmentId" -> up.attachmentId.asJson,
                                "name" -> up.name.asJson,
                                "size" -> up.size.asJson,
                                "sha256" -> up.sha256.asJson
                              )
                            )
                          )
                      case Left((code, message)) =>
                        val status =
                          if code == "cancelled" then Status.Conflict
                          // 形态闸拒因（attachid 批）：`FriendService.uploadStream` 的兜底闸把
                          // 非法 id 收成结构化 Left ⇒ 这里必须映射成**可判读 4xx**（而不是落进
                          // 末尾的 `Status.BadGateway` 兜底 —— 那会把「入参错」报成「上游错」）。
                          else if code == AttachUploadId.ErrorCode then Status.BadRequest
                          else if code == "attach_too_large" then Status.PayloadTooLarge
                          else if code == "empty_file" || code == "invalid_attachment" then Status.UnprocessableEntity
                          else if code == "forbidden" then Status.Forbidden
                          else if code == "not_logged_in" then Status.Forbidden
                          else if code == "attachment_unsupported" then Status.NotImplemented
                          else if code == "rate_limited" || code == "quota_exceeded" then Status.TooManyRequests
                          else if code == "upstream_error" then Status.BadGateway
                          else Status.BadGateway
                        wsHub.broadcast(
                          Json.obj(
                            "type" -> "attach-upload-done".asJson,
                            "conversationId" -> conversationId.asJson,
                            "msg" -> Json.obj(
                              "uploadId" -> uploadId.asJson,
                              "conversationId" -> conversationId.asJson,
                              "ok" -> false.asJson,
                              "code" -> code.asJson,
                              "error" -> message.asJson
                            )
                          )
                        ) *> IO.pure(
                          Response[IO](status).withEntity(
                            Json.obj("ok" -> false.asJson, "code" -> code.asJson, "error" -> message.asJson)
                          )
                        )
                    }
      }

    /** **取消在飞上传**（attachcl 批）：把取消位翻起来 ⇒
      * [[nebflow.neblink.AttachUpload.pushChunks]] 在**下一块发出前**读到它、停止后续
      * 分块，并以 [[nebflow.neblink.AttachUpload.Failure.Cancelled]] 收尾（**不报完成**）。
      *
      * 未登记过的 `uploadId` ⇒ `200 {cancelled:false}`（不新造位、不谎报成功：客户端
      * 据此如实显示「已结束/无法取消」而不是假的「已取消」）。已登记 ⇒ `{cancelled:true}`
      * （终态由上传请求自身的响应给出，本路由只负责翻转信号）。
      */
    case req @ POST -> Root / "attachments" / uploadId / "cancel" =>
      withAuth(req) {
        // 🔴 **同一道形态闸**（attachid 批）：取消键与上传键是**同一名字空间**，非法形态在任何
        // 消费点都必须被**同一个**判定拒掉 ⇒ 这里复用 [[nebflow.neblink.AttachUploadId]]，
        // **不**各写一份（禁多点漂移）。判定不触盘、不触上游：非法 id 的读数恒为
        // 4xx + 非空体、零落盘、零上游调用、零临时件。
        AttachUploadId.validate(uploadId) match
          case Left(reason) =>
            BadRequest(
              Json.obj(
                "ok" -> false.asJson,
                "code" -> AttachUploadId.ErrorCode.asJson,
                "error" -> reason.asJson
              )
            )
          case Right(id) =>
            // 形态合法 ⇒ 既有语义**逐字不变**：未登记过 ⇒ `200 {cancelled:false}`
            // （不新造位、不谎报成功）；已登记 ⇒ `{cancelled:true}`。
            sharedResources.attachUploads.cancel(id).flatMap { flipped =>
              Ok(Json.obj("ok" -> true.asJson, "cancelled" -> flipped.asJson, "uploadId" -> id.asJson))
            }
      }

    /** 附件接收完毕回执（补件批 4b1 · §B.1 E4 / §F.1b）。
      *
      * 与上一条 E3 下载路由**同族**：前端拿不到服务端地址/凭证 ⇒ 回执也**只能**走本网关的
      * 应用内鉴权路由（`withAuth`，同其余 `/friends*` 面；服务端侧对应 `POST /api/attachments/{id}/received`）。
      * 关系闸（§F.1b 规则 5：`received` 只对「能读该件的人」开放）在**服务端** E4 上，本层**不复制**
      * 第二套权限判定（禁双实现）。
      *
      * 🔴 **判定不在此层**：本路由只把请求体整理成 [[AttachmentAck.Evidence]] 交给
      * `FriendService.ackAttachmentReceived`；fail-closed 判定只有一处实现（[[AttachmentAck.decide]]），
      * 前端只上报证据（`friendsApi.js#ackAttachmentReceived`）。缺证据 / sha 不符 / 未申报落盘
      * ⇒ 上游**不发** E4 ⇒ 服务端 blob 不动（24 h TTL 兜底）。
      *
      * 🔴 **永不改变用户面**：无论结局是 acknowledged / skipped / failed，一律 `200` + 结局体
      * `{"ack": …, "reason": …}`；调用方（前端）**不 await、不看**该结果 ⇒ 回执失败不影响
      * 下载/保存/UI（失败静默容忍，§F.1b 规则 4）。「E4 失败 ⇒ 用户面与成功路径逐字相同」
      * 因此是**结构保证**：两条路径的唯一差异位就在本体的 `ack`/`reason` 字段。
      */
    case req @ POST -> Root / "friends" / "attachments" / attachmentId / "received" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            req.as[Json].attempt.flatMap {
              case Left(_) =>
                // 证据体不可解析 ⇒ 按「无法验证」处理（fail-closed：零 E4），且不改用户面。
                Ok(Json.obj("ack" -> "skipped".asJson, "reason" -> "malformed-evidence".asJson))
              case Right(body) =>
                val h = body.hcursor
                val evidence = AttachmentAck.Evidence(
                  localSha256 = h.get[String]("wholeSha256").toOption,
                  declaredSha256 = h.get[String]("declaredSha256").toOption.getOrElse(""),
                  receivedBytes = h.get[Long]("receivedBytes").toOption,
                  expectedBytes = h.get[Long]("expectedBytes").toOption,
                  landedFinal = h.get[Boolean]("landedFinal").toOption.getOrElse(false)
                )
                fs.ackAttachmentReceived(attachmentId, evidence).flatMap {
                  case AttachmentAck.Result.Acknowledged => Ok(Json.obj("ack" -> "acknowledged".asJson))
                  case AttachmentAck.Result.Skipped(reason) =>
                    Ok(Json.obj("ack" -> "skipped".asJson, "reason" -> reason.asJson))
                  case AttachmentAck.Result.Failed(reason) =>
                    Ok(Json.obj("ack" -> "failed".asJson, "reason" -> reason.asJson))
                }
            }
      }

    /** 标记已读。body: {lastReadMessageId} */
    case req @ POST -> Root / "conversations" / conversationId / "read" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            req.as[Json].flatMap { body =>
              val lastRead = body.hcursor.downField("lastReadMessageId").as[Long].getOrElse(-1L)
              if lastRead < 0 then BadRequest(Json.obj("error" -> "Missing lastReadMessageId".asJson))
              else fs.markRead(conversationId, lastRead).flatMap(friendResultRaw)
            }
      }

    /** MVP-2 设备会话域统一（2026-09-15）：**会话级回执读面**
      * （`GET /api/conversations/{id}/receipts` → neblink-server
      * `src/friends.rs:1712 conversation_receipts`）。
      *
      * 路径与既有 `conversations` 面**同族**（同段数、同 auth、同 id 编码）；设备会话
      * 与 legacy 直聊会话**共用本路由**——服务端按 `conversations.kind` 自行分派到
      * `device_message_receipts` / `message_receipts`，且**响应形状逐字同源**
      * （契约 §8.7；服务端逐字「so one client parser reads both faces」）。本层
      * **不判 kind**、不复制第二套分派，禁双实现。
      *
      * 🔴 **状态码逐字透传**（走 [[groupProxyResult]] = 本文件唯一的 `(status, body)`
      * 映射器）：`403 device_identity_required` 是契约 §8.7 的**可判读终态**
      * （「本次凭证没有设备身份」），折叠成 502 后客户端只能解析字符串分态。
      *
      * 🔴 身份面既有纪律不变：身份**只**由 `Bearer device session token` 承载，
      * 本层**不发** `sender`/`uid` 类自定义头（客户端不得自报身份）。 */
    case req @ GET -> Root / "conversations" / conversationId / "receipts" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            fs.conversationReceipts(conversationId).flatMap(groupProxyResult)
      }

    /** MVP-2 设备会话域统一（2026-09-15）：**设备会话发送面**
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
      * `existing:true` 仅表示回放原行）。本层**不**把 `existing` 折成别的状态码。 */
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

    /** 查号（精确匹配 neblink_id，大小写不敏感）。?q=... */
    case req @ GET -> Root / "users" / "lookup" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            req.params.get("q") match
              case None | Some("") => BadRequest(Json.obj("error" -> "Missing q".asJson))
              case Some(q)         => fs.lookupUser(q).flatMap(friendResult)
      }

    /** 搜索（friend-search-contract §4.1 唯一入口）：username OR email 双键 NOCASE
      * 精确。?q=... → 命中 {found:true,user:{username,display_name,avatar},
      * relation_status} / 未命中 {found:false}；透传上游不变形（返回结构与前端
      * friendsApi.normalizeSearch 归一语义严格一致——纯代理，不字段映射）。 */
    case req @ GET -> Root / "users" / "search" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            req.params.get("q") match
              case None | Some("") => BadRequest(Json.obj("error" -> "Missing q".asJson))
              case Some(q)         => fs.searchUser(q).flatMap(friendResult)
      }

    /** [U3] 自定义 NebLink 号。body: {neblinkId} → 200 {neblinkId}；上游 409
      * taken / 422 invalid 由 NeblinkClient 折叠为 Left → 网关 502 + error 透传
      * （web 端以 available 预检 + 本地正则兜底，409/422 仅竞态兜底面）。 */
    case req @ PUT -> Root / "users" / "me" / "neblink-id" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            req.as[Json].flatMap { body =>
              val id = body.hcursor.downField("neblinkId").as[String].getOrElse("").trim
              if id.isEmpty then BadRequest(Json.obj("error" -> "Missing neblinkId".asJson))
              else fs.setNeblinkId(id).flatMap(friendResult)
            }
      }

    /** [U3] 号可用性实时检测（供 NL 号自定义 UI 即时反馈）。?q=... → {available, reason?} */
    case req @ GET -> Root / "users" / "me" / "neblink-id" / "available" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            req.params.get("q") match
              case None | Some("") => BadRequest(Json.obj("error" -> "Missing q".asJson))
              case Some(q)         => fs.neblinkIdAvailable(q).flatMap(friendResult)
      }

    // ===== 群组一期代理面（gwroutes 批，2026-09-15）=====
    //
    // 契约真源 = 跨仓 neblink-server `main`@`9e811ffc77349ae26af3d34ca790cee12ef216b2`
    // 的 `src/groups.rs:616-639`：**11 条 `.route()` / 12 个 method+path 对**（第 1 条
    // `.route("/api/groups", post(group_create).get(group_list))` 一条注册两个方法）。
    // 逐条对表（server → 本层）见报告 §2；本层 = **纯代理**：路径 / 方法 / 请求体
    // 逐字转发，响应 status + body 逐字回传 —— **不**做字段映射、**不**拆信封、
    // **不**裁剪字段（任何 reshape 都会给冻结契约造出第二个真相源）。
    //
    // 🔴 为什么不能复用 `friendResult` / `friendErr`（既有好友面的折叠判据）：那条路
    // 把上游非 2xx 统一折成 502，而群域的 `404 group_not_found` / `403 group_disbanded`
    // / `403 not_member` 是**群终态**（客户端 `web/js/friendGroups.js#groupErrToast`
    // 按语义码分态，`messages.groupNotFound` / `groupDisbanded` / `groupKicked` 三文案）。
    // 折叠 ⇒ 客户端再也分不出「群不存在 / 群已解散 / 我被踢了」⇒ 语义净丢失。
    // 同族先例 = 本文件 E3 附件下载路由（`410`/`404`/`403` 逐字透传不折叠）。
    //
    // 静态段优先：`/groups/invites` 必须排在 `/groups/{group_id}` 之前（与
    // `groups.rs:611-615` 的 axum 静态段优先级**同构**；本文件 `/friends/requests`
    // 先于 `/friends/{friendUserId}` 是同一既有先例）。
    //
    // 本层对每个 method+path 对**只登记一条**：多出的形态（如 `GET /groups/{id}`）
    // 服务端没有 ⇒ 不注册（对表判据「网关多出 server 无」在报告 §2 逐条给读数）。

    /** GET/POST /api/groups —— 我的群列表 / 建群。
      * 列表 = **裸数组** `[GroupSummary]`（`groups.rs:170-176`，同 GET
      * /api/conversations 约定；`model.rs:740-757` `rename_all="camelCase"`）；
      * 建群 201 `{groupId,title,createdAt}` / 422 `invalid_title` / 429 `rate_limited`。 */
    case req @ GET -> Root / "groups" =>
      groupProxy(req, "GET", "/api/groups")

    case req @ POST -> Root / "groups" =>
      groupProxy(req, "POST", "/api/groups")

    /** GET /api/groups/invites —— **邀请发现面**（加性端点，`groups.rs:178-200`）。
      * 出参 `{"incoming":[GroupInviteEntry]}`（`model.rs:831-836`）。@静态段先于
      * `/groups/{groupId}` 的 GET 形态——服务端无 `GET /groups/{id}`，本层亦不注册。 */
    case req @ GET -> Root / "groups" / "invites" =>
      groupProxy(req, "GET", "/api/groups/invites")

    /** POST /api/groups/{groupId}/invites —— owner 邀请一人（A-4：被邀请人 accept 后
      * 才入群）。上游 201 `{inviteId,groupId,inviteeUserId,status,createdAt}` /
      * 404 `user_not_found` / 409 `already_member` / 409 `group_full` /
      * 409 `invite_pending` / 400 `self_invite` / 422 `invalid_request`。 */
    case req @ POST -> Root / "groups" / groupId / "invites" =>
      groupProxy(req, "POST", s"/api/groups/${encSeg(groupId)}/invites")

    /** POST .../invites/{inviteId}/accept —— 仅被邀请人。上游 200
      * `{ok:true,groupId,title}` / 404 `not_found` / 403 `not_invitee` /
      * 409 `not_pending` / 403 `group_disbanded` / 409 `group_full`。 */
    case req @ POST -> Root / "groups" / groupId / "invites" / inviteId / "accept" =>
      groupProxy(req, "POST", s"/api/groups/${encSeg(groupId)}/invites/${encSeg(inviteId)}/accept")

    case req @ POST -> Root / "groups" / groupId / "invites" / inviteId / "decline" =>
      groupProxy(req, "POST", s"/api/groups/${encSeg(groupId)}/invites/${encSeg(inviteId)}/decline")

    /** GET /api/groups/{groupId}/members —— 成员闸（非成员 403 `not_member`）。
      * 出参 `{"members":[{...FriendPublic,role,joinedAt}]}`（`model.rs:762-776`；
      * 档案字段沿用 FriendPublic 的 snake_case 钉法，**本层不动**）。 */
    case req @ GET -> Root / "groups" / groupId / "members" =>
      groupProxy(req, "GET", s"/api/groups/${encSeg(groupId)}/members")

    /** POST .../members/{userId}/kick —— owner only。上游 403 `not_owner` /
      * 403 `not_member` / 403 `owner_cannot_leave`（自踢）/ 404 `member_not_found`。 */
    case req @ POST -> Root / "groups" / groupId / "members" / userId / "kick" =>
      groupProxy(req, "POST", s"/api/groups/${encSeg(groupId)}/members/${encSeg(userId)}/kick")

    /** POST /api/groups/{groupId}/messages —— **冻结群发契约**（`groups.rs:403-524`）。
      * 校验序服务端冻结（auth → 群存在且未解散 → 成员 → 长度 → 限速 → origin →
      * 附件），本层**不复制**任何一条判定（禁双实现）。上游 201 SendMessageResponse
      * 同形 / 404 `group_not_found` / 403 `group_disbanded` / 403 `not_member` /
      * 422 `invalid_length` / 422 `invalid_origin` / 429 `rate_limited`。
      *
      * 🔴 **本路由与其余 11 条群路由的唯一差别**：转发前多过一道 `origin` 闸
      * （见 [[groupSendProxy]]）——它是「UI 身份直发」面，而 agent 代发走的是
      * 进程内腿（`FriendService.sendGroupAsAgent`，不经本路由）⇒ 两腿**共用同一上游
      * 端点**，但只有进程内腿能写 `origin="agent"`。 */
    case req @ POST -> Root / "groups" / groupId / "messages" =>
      groupSendProxy(req, groupId)

    /** POST /api/groups/{groupId}/leave —— 成员退群；owner 禁退群（上游
      * 403 `owner_cannot_leave`，O⑨）。 */
    case req @ POST -> Root / "groups" / groupId / "leave" =>
      groupProxy(req, "POST", s"/api/groups/${encSeg(groupId)}/leave")

    /** PUT /api/groups/{groupId}/title —— owner 改名。上游 200 `{ok:true,title}` /
      * 422 `invalid_title`（trim 后非空且 ≤64 字符）。 */
    case req @ PUT -> Root / "groups" / groupId / "title" =>
      groupProxy(req, "PUT", s"/api/groups/${encSeg(groupId)}/title")

    /** DELETE /api/groups/{groupId} —— owner 解散（**软标记** `group_disbanded`，
      * 消息行永不删；`groups.rs:592-607`）。 */
    case req @ DELETE -> Root / "groups" / groupId =>
      groupProxy(req, "DELETE", s"/api/groups/${encSeg(groupId)}")

  }

  // ===== 好友域上游错误的单一判据（2026-09-11 boot 快照修复，R3(a)） =====

  /** 本网关是否配置了 NebLink（server 址存在）？**live 读 config ref**
    * （同既有先例 `neblinkServerUrl` / `ms.relayTunnelOpt`，不得引入新的 boot
    * 快照）。未配置 ⇒ 好友域维持 `404 NebLink not enabled`，前端
    * `errKind='neblinkOff'` 保持可表达（其 retry 只对「已配置但暂时失败」有意义）。
    */
  private def neblinkConfigured: IO[Boolean] =
    neblinkService match
      case Some(ms) => ms.neblinkConfig.map(_.neblinkServer.isDefined)
      case None     => IO.pure(false)

  /** 好友域上游失败的**单一**应答判据。三个渲染上游 `Left` 的落点共用它：
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

  /** Uniform A2A endpoint result mapping: upstream Left → 401/404/502 by the
    * single judgement above (was: always 502). */
  private def friendResult(result: Either[String, io.circe.Json]): IO[Response[IO]] =
    result match
      case Right(json) => Ok(json)
      case Left(err)   => friendErr(err)

  /** Raw-string upstream results (decline/remove/read): parse the body as JSON
    * when possible, else wrap as {ok, message}. */
  private def friendResultRaw(result: Either[String, String]): IO[Response[IO]] =
    result match
      case Right(body) =>
        parser.parse(body) match
          case Right(json) => Ok(json)
          case Left(_)     => Ok(Json.obj("ok" -> true.asJson, "message" -> body.asJson))
      case Left(err) => friendErr(err)

  // ===== 群代理腿的单一实现（gwroutes 批，2026-09-15）=====
  //
  // 12 条群路由**共用**本实现（禁各写一套 —— 与 `friendErr` 是「好友域上游错误的
  // 单一判据」同构：群域的状态码判据也只有这一处）。

  /** 群请求转发（唯一入口）。**语义分三层**：
    *
    *  ① **鉴权在先**：`withAuth` 先于任何上游往返（无 token ⇒ 403，零外发）；
    *  ② **未配置即 404**：`friendService` 缺席 ⇒ `404 NebLink not enabled`。这是
    *     **fail-closed 的承重墙**：客户端 `friendsApi.errKind` 把 404 读作
    *     `neblinkOff` ⇒ `friendGroups.markAvailability(false)` ⇒ 群入口隐藏
    *     （主卡 G-2）。改成 502/空成功都会把「群不可用」伪装成「群是空的」；
    *  ③ **身份透传**：与全部既有 friends / conversations 代理**逐字一致** ——
    *     身份**只**由 `NeblinkServerUrl + Bearer device session token` 承载，
    *     本层**不发** `sender` / `uid` 类自定义头（客户端不得自报身份；服务端
    *     `require_user` 从 token 解身份，`groups.rs:50-54`）。
    *
    * 请求体**按原文转发**（见 [[rawBody]]）：不解析、不重编码 —— 解析后再编码会
    * 重排键并丢掉未知键，等于替冻结契约改了形态。
    */
  private def groupProxy(req: Request[IO], method: String, upstreamPath: String): IO[Response[IO]] =
    withAuth(req) {
      sharedResources.friendService match
        case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
        case Some(fs) =>
          rawBody(req).flatMap(body => fs.groupProxy(method, upstreamPath, body).flatMap(groupProxyResult))
    }

  /** 请求体**逐字**取原文（代理腿专用；空体 ⇒ `""`）。
    *
    * 用 `bodyText.compile.string` 而**不**用 `req.as[Json]`：后者把 body 解析成 AST
    * 再序列化回去会重排键 / 丢未知键 / 改数字字面量 ⇒ 上游收到的字节与客户端发的不
    * 同形。代理腿的职责是搬运字节，不是理解它。 */
  private def rawBody(req: Request[IO]): IO[String] =
    req.bodyText.compile.string

  /** **UI 身份直发的群消息路由**（`POST /api/groups/{groupId}/messages`）——
    * 代理 + 一道 `origin` 闸。
    *
    * 🔴 为什么需要闸（补充卡 §6.5 + §8.1 判红面 ①「标识伪造面」）：本路由是
    * **用户身份**直发面（前端唯一可达的群发送入口）。代理腿的默认形态是逐字转发
    * 请求体（不解析、不重编码），但那样 web 前端就能塞一个 `origin:"agent"`
    * 一路到服务端并**落库为 agent 代发** —— 而 §8.1(a) 的判红信号正是「非 agent
    * 通道的消息被存成 `origin='agent'`」。⇒ 本路由是**唯一**在转发前读请求体的群
    * 路由，且**只判 `origin` 一个键**：缺席 / 逐字 `"user"` ⇒ 原文转发（= 服务端
    * 缺省语义，字节零变化）；**其他任何值** ⇒ `400` 显式拒绝，**零上游往返**。
    *
    * 处置形态的选择（两条都登记在其后的「为什么不」里）：
    *  - **显式拒绝，不静默改写**：剔键 / 改写为 `"user"` 会让一次越界自报**静默消失**
    *    （调用方以为生效了、实际没有）——本仓明令禁止的缺陷族（静默不达）。
    *  - **不按补充卡 §6.5 的字面机制「只读 `body` 一个字段重建请求体」**：服务端已把
    *   附件纳入一期群发（作者指令），而 UI 腿正在飞 ⇒ 重建会把 UI 后续携带的
    *   加性键（`attachments` 等）**静默丢弃**。本批取「保住判据目标（UI 面不可能产出
    *   `origin='agent'`）+ 不静默丢键」，字面机制差异作为**待作者裁**项单列上报
    *   （实施报告「待作者拍板」节，非本节点自裁）。
    *
    * 身份面既有纪律不变：本层**不发** `sender`/`uid` 类自定义头（身份**只**由
    * `NebLinkServerUrl + Bearer device session token` 承载）。 */
  private def groupSendProxy(req: Request[IO], groupId: String): IO[Response[IO]] =
    withAuth(req) {
      sharedResources.friendService match
        case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
        case Some(fs) =>
          rawBody(req).flatMap { body =>
            GroupSendOriginVerdict.check(body) match
              case Left(reason) => BadRequest(Json.obj("error" -> reason.asJson))
              case Right(()) =>
                fs.groupProxy("POST", s"/api/groups/${encSeg(groupId)}/messages", body)
                  .flatMap(groupProxyResult)
          }
    }

  /** 上游 `(status, body)` ⇒ 本网关响应：**状态码逐字**，体优先 JSON 解析。
    *
    * 🔴 禁吞：既不把上游 4xx 折成 500 / 502，也不把错误折成「空成功」——群域三码
    * （`group_not_found` / `group_disbanded` / `not_member`）必须原样到达客户端。
    * 非 JSON 体（网关/代理层注入的 HTML 错误页等）包成 `{"error":<原文>}`：既保住
    * 可判读性，又不让一次体解析失败把响应升级成 500。空体保持空体（不透传伪实体）。 */
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

  /** 路径段编码（代理腿转发用）：避免上游路径被段内容改写（段内 `/`、`?` 注入）。
    * 与 `NeblinkClient.enc` 同法，只把 `+` 归一成 `%20`（`URLEncoder` 的
    * `application/x-www-form-urlencoded` 口径在路径段里会变成字面 `+`）。 */
  private def encSeg(s: String): String =
    java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

  /** Shared remote-update logic: P2P HTTP first, relay fallback. Used by REST + WS handlers.
    *
    * `clientRequestId`（hotupdate 批 3 · G8）= **可选**幂等键：仅在 P2P 载荷里作为
    * 加法字段随行（老端对端 read 只见 `beta`，未知键静默忽略）；缺席 ⇒ 载荷与改前
    * 逐字节相同。🔴 中继腿（`relayUpdateFallback`）的隧道参数面保持 `{beta}` 不变
    * ——隧道动作 `RemoteUpdate` 的参数集由跨仓契约钉死（契约 §B.1.3），本批零越仓。 */
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
  def presenceWsRoutes(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "neblink" / "presence" =>
      neblinkService match
        case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
        case Some(ms) =>
          val remoteIp = req.remoteAddr.fold("")(a => a.toString)
          val peerDeviceId = presencePeerDeviceId(req)
          // Trust: IP list (primary) or device-ID network membership (fallback).
          // A1 (2026-09-20): the claiming id arrives in the handshake header
          // (see presencePeerDeviceId) — the legacy ?deviceId= query param is
          // still accepted for peers built before the change.
          (if ms.isTrustedPeer(remoteIp) then IO.pure(true)
           else isKnownNetworkDevice(ms, peerDeviceId, remoteIp)).flatMap {
            case false => Forbidden(Json.obj("error" -> "Not a trusted peer".asJson))
            case true =>
              if peerDeviceId.isEmpty then BadRequest(Json.obj("error" -> "Missing deviceId".asJson))
              else
                val peerDeviceName = req.params.getOrElse("deviceName", "Unknown")
                val peerPlatform = req.params.getOrElse("platform", "")
                val peerPort = req.params.getOrElse("port", "8080").toIntOption.getOrElse(8080)
                val capsStr = req.params.getOrElse("capabilities", "{}")
                val capabilities = parser.decode[Map[String, String]](capsStr).getOrElse(Map.empty)
                val info = nebflow.neblink.DeviceDiscoveryInfo(
                  peerDeviceId,
                  peerDeviceName,
                  peerPlatform,
                  capabilities
                )
                // Silent upsert — the HTTP /neblink/announce endpoint already handles logging.
                // Calling handleAnnounce here too produces duplicate "Peer announced" logs.
                val peer = nebflow.neblink.PeerInfo(
                  peerDeviceId,
                  peerDeviceName,
                  peerPlatform,
                  s"http://$remoteIp:$peerPort",
                  capabilities = capabilities
                )
                ms.upsertPeer(peer).flatMap { _ =>
                  Queue.unbounded[IO, WebSocketFrame].flatMap { sendQueue =>
                    val heartbeat = Stream
                      .awakeEvery[IO](10.seconds)
                      .map(_ => WebSocketFrame.Text("""{"type":"ping"}"""))
                    val queued = Stream.fromQueueUnterminated(sendQueue)
                    val send = queued.merge(heartbeat)
                    val receive: Pipe[IO, WebSocketFrame, Unit] =
                      _.evalMap {
                        case WebSocketFrame.Text(text, _) =>
                          parser.parse(text).toOption match
                            case Some(json) =>
                              json.hcursor.downField("type").as[String].getOrElse("") match
                                case "ping" =>
                                  sendQueue.offer(WebSocketFrame.Text("""{"type":"pong"}"""))
                                case "data" =>
                                  val payload = json.hcursor.downField("payload").focus.getOrElse(Json.Null)
                                  ms.handleDataMessage(payload)
                                case _ => IO.unit
                            case None => IO.unit
                        case _ => IO.unit
                      }.onFinalize(
                        ms.removePeer(peerDeviceId).handleErrorWith(_ => IO.unit)
                      )
                    wsb.build(send, receive)
                  }
                }
          }

    // (2026-09-20 device-face hardening batch) POST /api/flow/event was RETIRED
    // here — the classify pass recorded it as 仓内零调用点 (no FE/CLI/BE caller)
    // and the 11-route takedown was authorised by the author.

    // GET /teams/mounted — return all mounted teams with agent status
    // NOTE: Router mounts this under /api prefix, so full path is /api/teams/mounted
    case req @ GET -> Root / "teams" / "mounted" =>
      withAuth(req) {
        for
          _ <- nebflow.core.flow.FlowTreeRegistry.awaitRestore(3000L)
          teamsJson <- buildMountedTeamsJson()
          result <- Ok(Json.obj("teams" -> teamsJson))
        yield result
      }

    // (2026-09-20 device-face hardening batch) 11-route takedown, authorised by
    // the author: GET /running-flows, GET /flow/dag/:name, GET /teams and
    // GET /teams/status/:sessionId were RETIRED here — each was 仓内零调用点 in
    // the classify pass (no FE/CLI/BE caller). The live siblings
    // (/teams/mounted, /teams/mailbox/*, /teams/def/*, /teams/mail-queue/*,
    // /team/rules/*) are untouched.


    // GET /teams/mailbox/:sessionId/:teamName — mail history for a team
    case req @ GET -> Root / "teams" / "mailbox" / sessionId / flowName =>
      withAuth(req) {
        if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
          BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
        else
          for
            records <- nebflow.core.flow.FlowMailStore.load(sessionId, flowName)
            result <- Ok(Json.obj("records" -> records.asJson))
          yield result
      }

    // DELETE /teams/mailbox/:sessionId/:teamName — clear mail history
    case req @ DELETE -> Root / "teams" / "mailbox" / sessionId / flowName =>
      withAuth(req) {
        if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
          BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
        else
          for
            _ <- nebflow.core.flow.FlowMailStore.clear(sessionId, flowName)
            result <- Ok(Json.obj("cleared" -> true.asJson))
          yield result
      }

    // GET /teams/mail-queue/:sessionId — pending queue mails
    case req @ GET -> Root / "teams" / "mail-queue" / sessionId =>
      withAuth(req) {
        if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
          BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
        else
          for
            items <- nebflow.core.flow.MailQueueStore.load(sessionId)
            result <- Ok(Json.obj("items" -> items.asJson))
          yield result
      }

    // DELETE /teams/mail-queue/:sessionId/:itemId — cancel a pending queue mail
    case req @ DELETE -> Root / "teams" / "mail-queue" / sessionId / itemId =>
      withAuth(req) {
        if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
          BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
        else
          for
            remaining <- nebflow.core.flow.MailQueueStore.removeById(sessionId, itemId)
            result <- Ok(Json.obj("items" -> remaining.asJson))
          yield result
      }

    // ===== Flow Editor APIs =====

    // GET /teams/def/:name — return team definition for the editor
    case req @ GET -> Root / "teams" / "def" / flowName =>
      withAuth(req) {
        if !isValidFlowName(flowName) then BadRequest(Json.obj("error" -> "Invalid name".asJson))
        else
          for
            teamOpt <- EntityLoader.loadTeam(flowName)
            agents <- EntityLoader.listAgents()
            result <- teamOpt match
              case None => NotFound(Json.obj("error" -> s"Team '$flowName' not found".asJson))
              case Some(team) =>
                val leadEntry = agents.get(team.lead)
                val memberEntries = team.members.flatMap(agents.get)
                val allEntries = (leadEntry.toList ++ memberEntries)
                val agentsJson = allEntries.map { entry =>
                  Json.obj(
                    "name" -> entry.name.asJson,
                    "description" -> entry.description.asJson,
                    "tools" -> entry.tools.asJson,
                    "systemPrompt" -> entry.systemPrompt.asJson
                  )
                }
                Ok(
                  Json.obj(
                    "name" -> team.name.asJson,
                    "manager" -> team.lead.asJson,
                    "description" -> team.description.asJson,
                    "agents" -> agentsJson.asJson,
                    "type" -> "team".asJson
                  )
                )
          yield result
      }

    // GET /bg-tasks/:jobId/output?offset=N — background task output view
    // (2026-09-09 author request: the bg-tasks panel rows open a detail card).
    // REST GET polling over WS push: stateless byte-offset cursor (line
    // granularity — returns every line whose start offset >= offset), auth via
    // the same withAuth middleware as /api/agents/:name/model, and no 256KB
    // payloads broadcast through WsHub to every connected client. Response:
    // status / totalBytes / totalLines / truncated (tail window dropped the
    // head) / output / nextOffset (+ finishedAtMs / exitCode / errorHint when
    // terminal). 404 = unknown jobId (remote tasks and evicted retention).
    case req @ GET -> Root / "bg-tasks" / jobId / "output" =>
      withAuth(req) {
        val offset = req.uri.params.get("offset").flatMap(_.toLongOption).getOrElse(0L)
        nebflow.core.tools.BgTaskOutputStore.read(jobId, offset).flatMap {
          case None =>
            NotFound(Json.obj("error" -> s"Background task '$jobId' not found or output unavailable".asJson))
          case Some(o) =>
            Ok(
              Json.obj(
                "taskId" -> o.taskId.asJson,
                "status" -> o.status.asJson,
                "totalBytes" -> o.totalBytes.asJson,
                "totalLines" -> o.totalLines.asJson,
                "truncated" -> o.truncated.asJson,
                "output" -> o.output.asJson,
                "nextOffset" -> o.nextOffset.asJson,
                "finishedAtMs" -> o.finishedAtMs.asJson,
                "exitCode" -> o.exitCode.asJson,
                "errorHint" -> o.errorHint.asJson
              )
            )
        }
      }

    // (2026-09-20 device-face hardening batch) 11-route takedown: GET /agents/list
    // was RETIRED here — 仓内零调用点 in the classify pass (its only in-repo
    // reference was the smoke-spec probe, updated in the same commit).
    // GET /agents/:name and /agents/:name/model below are the live faces.


    // GET /agents/:name — get agent detail (system.md + tools) — searches all three layers
    case req @ GET -> Root / "agents" / agentName =>
      withAuth(req) {
        if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
        else
          for
            agentOpt <- EntityLoader.findAgentByName(agentName)
            result <- agentOpt match
              case None => NotFound(Json.obj("error" -> s"Agent '$agentName' not found".asJson))
              case Some(defn) =>
                Ok(
                  Json.obj(
                    "name" -> defn.name.asJson,
                    "description" -> defn.description.asJson,
                    "tools" -> defn.tools.asJson,
                    "category" -> defn.category.asJson,
                    "fixedTools" -> AgentCore.fixedToolsFor(defn).toList.asJson,
                    "systemPrompt" -> defn.systemPrompt.asJson,
                    "displayName" -> defn.displayName.getOrElse(defn.name).asJson,
                    "model" -> defn.model.asJson,
                    "skills" -> defn.skills.asJson,
                    "flows" -> defn.flows.asJson
                  )
                )
          yield result
      }

    // GET /agents/:name/model — get agent's model configuration — searches all three layers
    case req @ GET -> Root / "agents" / agentName / "model" =>
      withAuth(req) {
        if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
        else
          for
            agentOpt <- EntityLoader.findAgentByName(agentName)
            result <- agentOpt match
              case None => NotFound(Json.obj("error" -> s"Agent '$agentName' not found".asJson))
              case Some(defn) =>
                val modelConfig = defn.model.getOrElse(nebflow.shared.AgentModelConfig.empty)
                // Determine resolvedFrom: check the raw AgentEntry for preset/legacy.
                // AgentDef.preset tells us the explicit preset (if any). If absent,
                // check whether the original agent.json had a non-empty legacy model.
                val resolvedFrom = computeResolvedFrom(defn.preset, agentName)
                // Resolve the model this agent would actually use: candidates =
                // [preferred, ...fallbacks] (or the global chain), filtered by
                // provider health. Previously this read runtimeModels.values.headOption —
                // an arbitrary session from a GLOBAL session→model map — which
                // showed the wrong model for every agent except the first LLM caller.
                for
                  candidates <- sharedResources.providerRegistry.getCandidatesForAgent(Some(modelConfig))
                  (healthy, _) <- sharedResources.healthMonitor.filterCandidates(candidates)
                  current = healthy.headOption.map(c => s"${c.providerId}/${c.model}")
                  result <- Ok(
                    Json.obj(
                      "model" -> modelConfig.asJson,
                      "current" -> current.asJson,
                      "preferred" -> modelConfig.preferred.asJson,
                      "fallbacks" -> modelConfig.fallbacks.asJson,
                      "default" -> modelConfig.preferred.asJson,
                      "preset" -> defn.preset.asJson,
                      "resolvedFrom" -> resolvedFrom.asJson
                    )
                  )
                yield result
                end for
          yield result
      }

    // PUT /agents/:name/model — update agent's model configuration
    case req @ PUT -> Root / "agents" / agentName / "model" =>
      withAuth(req) {
        if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
        else
          req.as[Json].flatMap { body =>
            // Parse the model config from request body
            io.circe.parser.decode[nebflow.shared.AgentModelConfig](body.noSpaces) match
              case Right(modelConfig) =>
                for
                  dirOpt <- EntityLoader.findAgentDir(agentName)
                  result <- dirOpt match
                    case Some(dir) =>
                      IO.blocking {
                        val jsonPath = dir / "agent.json"
                        val json = os.read(jsonPath)
                        io.circe.parser.parse(json) match
                          case Right(parsed) =>
                            val updated = parsed.deepMerge(Json.obj("model" -> modelConfig.asJson))
                            AtomicJson.writeSync(jsonPath, updated.noSpaces)
                            true
                          case Left(_) => false
                      }.flatMap {
                        case true =>
                          Ok(Json.obj("updated" -> true.asJson, "model" -> modelConfig.asJson))
                        case false =>
                          InternalServerError(Json.obj("error" -> "Failed to write agent.json".asJson))
                      }
                    case None =>
                      NotFound(Json.obj("error" -> s"Agent '$agentName' not found".asJson))
                yield result
              case Left(err) =>
                BadRequest(Json.obj("error" -> s"Invalid model config: ${err.getMessage}".asJson))
          }
      }

    // PUT /agents/:name/preset — set or remove the agent's preset reference.
    // Body: {"preset": "vision"} or {"preset": null} (removes the field, falls
    // back to default preset). Uses EntityLoader.findAgentDir to locate the
    // agent.json across all three layers.
    case req @ PUT -> Root / "agents" / agentName / "preset" =>
      withAuth(req) {
        if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
        else
          req.as[Json].flatMap { body =>
            val presetOpt = body.hcursor.downField("preset").as[Option[String]].toOption.flatten
            for
              dirOpt <- EntityLoader.findAgentDir(agentName)
              result <- dirOpt match
                case Some(dir) =>
                  IO.blocking {
                    val jsonPath = dir / "agent.json"
                    val json = os.read(jsonPath)
                    parser.parse(json) match
                      case Right(parsed) =>
                        val updated = presetOpt match
                          case Some(name) =>
                            parsed.deepMerge(Json.obj("preset" -> name.asJson))
                          case None =>
                            // Remove the preset field entirely
                            parsed.asObject
                              .map(obj => Json.fromFields(obj.toMap.removed("preset")))
                              .getOrElse(parsed)
                        AtomicJson.writeSync(jsonPath, updated.noSpaces)
                        true
                      case Left(_) => false
                  }.flatMap {
                    case true =>
                      Ok(Json.obj("updated" -> true.asJson, "preset" -> presetOpt.asJson))
                    case false =>
                      InternalServerError(Json.obj("error" -> "Failed to write agent.json".asJson))
                  }
                case None =>
                  NotFound(Json.obj("error" -> s"Agent '$agentName' not found".asJson))
            yield result
            end for
          }
      }

    // ===== Entity API (Team/Flow/Agent management) =====

    // PUT /agents/:name — RETIRED 2026-09-06 (tool-face batch): the skills/
    // flows write-back is closed. Loud 410 so any stale client sees the
    // retirement instead of assuming the edit landed. agent.json skills/flows
    // declarations are still PARSED (decision A① — legacy grants stay live
    // until stage 3); only this write path is retired.
    case req @ PUT -> Root / "agents" / agentName =>
      withAuth(req) {
        if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
        else
          logger.warn(s"Rejected PUT /agents/$agentName — skills/flows write-back retired 2026-09-06 (stage 2d tool-face batch)")
          Gone(Json.obj("error" -> "agent skills/flows write-back retired 2026-09-06: per-agent capability config is definition/plugin-managed; agent.json is no longer written from the panel".asJson))
      }

    // (2026-09-20 device-face hardening batch) 11-route takedown: GET /skills was
    // RETIRED here — 仓内零调用点 (the plugins page has asserted since the unified
    // plugin system that it must NOT call it: tests/sidebar-plugins.spec.mjs:396).


    // ── Plugins（阶段 2b §B.3：面板审批清单 + CLI 对等）─────────────

    // GET /plugins — 注册表全量（含已封禁项 / 拒载原因 + 清单数据；无审批批 2026-09-13
    // 后条目面无「待审」形态，受信与否 = 在位 ∧ 未被封禁）。
    // 审批清单区块（§B.3）：元信息 / skills 摘要（前 20 行）/ mcp（env 只出键名，
    // 值打码）/ org.nebflow/tools 申请 / 信任状态与 digest。
    case req @ GET -> Root / "plugins" =>
      withAuth(req) {
        for
          (plugins, rejected) <- nebflow.core.plugin.PluginRegistry.listWithRejected()
          entries = plugins.sortBy(_.name).map(nebflow.core.plugin.PluginRegistry.approvalManifest)
          rejectedEntries = rejected.sortBy(_._1).map { case (n, r) => Json.obj("name" -> n.asJson, "reason" -> r.asJson) }
          result <- Ok(Json.obj("plugins" -> entries.asJson, "rejected" -> rejectedEntries.asJson))
        yield result
      }

    // GET /plugins/catalog — 分发器目录段同源（trusted only；前端调试/预览用）
    case req @ GET -> Root / "plugins" / "catalog" =>
      withAuth(req) {
        nebflow.core.plugin.PluginRegistry.renderCatalog().flatMap { catalog =>
          Ok(Json.obj("catalog" -> catalog.asJson))
        }
      }

    // POST /plugins/:name/approve — **兼容保留**（无审批批 2026-09-13 起 UI 主线不再调用）：
    // 写一条审批审计记录（digest + 逐文件快照）到 `plugins.trust`。⚠️ 该记录**不再决定
    // 装载**（在位即信任），但它是 seed 覆盖的**仲裁基准**（`trustRecordDigest`）⇒ 手动
    // approve 一个用户改过的默认集包会让下次 boot 的种子镜像覆盖视为「干净」。
    // 名字段白名单校验（拒绝路径穿越形态）。
    case req @ POST -> Root / "plugins" / name / "approve" =>
      withAuth(req) {
        if !isValidAgentName(name) then BadRequest(Json.obj("error" -> "Invalid plugin name".asJson))
        else
          nebflow.core.plugin.PluginRegistry.approve(name).flatMap {
            case Right(msg) => Ok(Json.obj("ok" -> true.asJson, "message" -> msg.asJson))
            case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
          }
      }

    // POST /plugins/:name/revoke — **封禁**（deny-list；2026-09-13 无审批批语义变更）：
    // 写 `plugins.revoked.<name> = {at, by, reason}`（**独立命名空间**，不被任何 approve
    // 抹掉）⇒ ① 不进分发器目录 ② 闸 A/B/C/E 拒（resolve Left）③ 在飞 MCP 下个 30s
    // 重验 tick 停掉。路径名保留（零迁移），语义从「撤回审批」翻转为「点名封禁」。
    // body（可选）：{"reason": "…"}。**解封**走下方 /unblock。
    case req @ POST -> Root / "plugins" / name / "revoke" =>
      withAuth(req) {
        if !isValidAgentName(name) then BadRequest(Json.obj("error" -> "Invalid plugin name".asJson))
        else
          req.as[Json].attempt.map(_.getOrElse(Json.obj())).flatMap { body =>
            val reason = body.hcursor.downField("reason").as[String].toOption.getOrElse("")
            nebflow.core.plugin.PluginBlockPolicy.block(name, reason, "panel/rest").flatMap {
              case Right(_) =>
                Ok(Json.obj("ok" -> true.asJson,
                  "message" -> (s"Plugin '$name' is now BLOCKED (deny-list) — it leaves the catalog, is refused on " +
                    "new dispatches and at node start, and its in-flight MCP servers are stopped within 30s. " +
                    s"Unblock with POST /api/plugins/$name/unblock or CLI 'nebflow plugin unblock $name'.").asJson))
              case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
            }
          }
      }

    // POST /plugins/:name/unblock — **解封**（2026-09-13 新增）：删 `plugins.revoked.<name>`
    // ⇒ 回落「在位即信任」（目录/派发/装载恢复；已停的在飞 MCP 需重新派发才回来）。
    case req @ POST -> Root / "plugins" / name / "unblock" =>
      withAuth(req) {
        if !isValidAgentName(name) then BadRequest(Json.obj("error" -> "Invalid plugin name".asJson))
        else
          nebflow.core.plugin.PluginBlockPolicy.unblock(name, "panel/rest").flatMap {
            case Right(_) =>
              Ok(Json.obj("ok" -> true.asJson,
                "message" -> (s"Plugin '$name' unblocked — back to presence trust: it re-enters the catalog and is " +
                  "available for new dispatches on the next scan.").asJson))
            case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
          }
      }

    // POST /plugins/:name/disable | /enable — **令 1 派发开关**（2026-09-12）：
    // 只写 `plugins.dispatch.<name>.authorEnabled`（durable 作者意图层）⇒ **只影响
    // 未来派发**：新节点拿不到该插件（闸 A 拒），已在飞/已派发节点**零影响**
    // （闸 B/C/E/D 只判内容面），已注入的 `<injected-plugins>` 提示词全文与审计
    // 留存不变。不放宽内容面（未受信的包此路依然无效）。
    case req @ POST -> Root / "plugins" / name / "disable" =>
      withAuth(req) {
        dispatchSwitch(name, enable = false)
      }
    case req @ POST -> Root / "plugins" / name / "enable" =>
      withAuth(req) {
        dispatchSwitch(name, enable = true)
      }

    // POST /plugins/:name/dispatch/grant — **过渡期临时派发授权**（设计 R8：
    // 过渡只能**放宽**、带 TTL 自动失效、不污染作者意图）。body（可选）：
    // {"ttlSecs": 1800, "refs": ["n-…"], "reason": "…"}。缺省 ttlSecs=1800。
    case req @ POST -> Root / "plugins" / name / "dispatch" / "grant" =>
      withAuth(req) {
        if !isValidAgentName(name) then BadRequest(Json.obj("error" -> "Invalid plugin name".asJson))
        else
          req.as[Json].attempt.map(_.getOrElse(Json.obj())).flatMap { body =>
            val c = body.hcursor
            val ttl = c.downField("ttlSecs").as[Long].toOption.getOrElse(1800L)
            val refs = c.downField("refs").as[List[String]].toOption.getOrElse(Nil)
            val reason = c.downField("reason").as[String].toOption.getOrElse("temporary dispatch grant via REST")
            nebflow.core.plugin.PluginDispatchPolicy.grantTransition(name, ttl, refs, reason, "rest").flatMap {
              case Right(_) =>
                Ok(Json.obj("ok" -> true.asJson,
                  "message" -> s"Plugin '$name' temporary dispatch grant recorded (ttlSecs=$ttl, refs=${refs.mkString(",")}) — new dispatches may use it until it expires; the author's intent is untouched".asJson))
              case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
            }
          }
      }

    // (2026-09-20 device-face hardening batch) 11-route takedown, authorised by
    // the author: POST /plugins/:name/dispatch/clear and GET /flows/list were
    // RETIRED here — both 仓内零调用点 in the classify pass. The sibling
    // POST /plugins/:name/dispatch/grant above stays (it has a BE caller).

    // ── Social interface channels (socpanel batch, 2026-09-19) ────────────
    // Design = `socremote-design` (n-5c95367d) arch §7.2/§7.3. Config lives in
    // the top-level `socialChannels` key of nebflow.json; a pasted credential
    // goes through the EXISTING core/CredentialFileAcl narrowing and the config
    // keeps only its path. 🔴 No response ever carries secret content — the
    // read side answers the mechanical triple `{exists, modeOk, readable}`.
    // All three endpoints sit behind the shared auth gate (withAuth).
    case req @ GET -> Root / "social" / "channels" =>
      withAuth(req) {
        IO.blocking(nebflow.social.SocialChannels.channelsJson(PathUtil.dataRoot)).flatMap(json => Ok(json))
      }

    case req @ POST -> Root / "social" / "channels" / channelId =>
      withAuth(req) {
        req.as[Json].attempt.flatMap {
          case Left(_) =>
            BadRequest(Json.obj(
              "error" -> "invalid_field".asJson,
              "reason" -> "request body must be a JSON object".asJson
            ))
          case Right(body) =>
            IO.blocking(nebflow.social.SocialChannels.save(PathUtil.dataRoot, channelId, body)).flatMap {
              case Right(json) => Ok(json)
              case Left(err)   => socialErrorResponse(err)
            }
        }
      }

    case req @ GET -> Root / "social" / "probe" =>
      withAuth(req) {
        val channelId = req.params.getOrElse("channel", "")
        IO.blocking(nebflow.social.SocialChannels.probeJson(PathUtil.dataRoot, channelId)).flatMap {
          case Right(json) => Ok(json)
          case Left(err)   => socialErrorResponse(err)
        }
      }

    // (r3 merge 2026-09-21) GET /flows/list stays RETIRED per the main-side
    // device-face hardening takedown above — the branch-side copy carried into
    // this conflict from the pre-takedown base was dropped, not resurrected.

    // (2026-09-20 device-face hardening batch) 11-route takedown: GET /teams/:name
    // (team detail) was RETIRED here — 仓内零调用点 in the classify pass. The
    // /team/rules/:name pair below is the live face (FE caller).


    // GET /team/rules/:name — read team rules.md
    case req @ GET -> Root / "team" / "rules" / teamName =>
      withAuth(req) {
        if !isValidAgentName(teamName) then BadRequest(Json.obj("error" -> "Invalid team name".asJson))
        else
          for
            rules <- EntityLoader.loadTeamRules(teamName)
            result <- Ok(Json.obj("content" -> rules.asJson))
          yield result
      }

    // POST /team/rules/:name — save team rules.md + trigger reload
    case req @ POST -> Root / "team" / "rules" / teamName =>
      withAuth(req) {
        if !isValidAgentName(teamName) then BadRequest(Json.obj("error" -> "Invalid team name".asJson))
        else
          for
            body <- req.as[Json]
            content = body.hcursor.downField("content").as[String].getOrElse("")
            rulesDir = PathUtil.dataRoot / "teams" / teamName
            _ <- IO.blocking {
              os.makeDir.all(rulesDir)
              val tmp = rulesDir / ".rules.md.tmp"
              os.write(tmp, content)
              os.move.over(tmp, rulesDir / "rules.md")
            }
            // Trigger reload so changes take effect on next activation
            _ <- FlowTreeRegistry.treesRef.get.flatMap { treeMap =>
              treeMap.values.toList.traverse_ { treeRef =>
                treeRef ! TreeCommand.ReloadDefinition(teamName)
              }
            }
            result <- Ok(Json.obj("saved" -> true.asJson))
          yield result
      }

    // (2026-09-20 device-face hardening batch) 11-route takedown: GET /entity-agents
    // was RETIRED here — 仓内零调用点 in the classify pass. Live agent faces are
    // /agents/:name, /agents/:name/model and /agents/:name/preset.


    // ===== Model Presets =====

    // GET /presets — list all presets + default name + agent references
    case req @ GET -> Root / "presets" =>
      withAuth(req) {
        val store = new PresetStore()
        for
          file <- IO.blocking(store.load())
          // Build agent → preset mapping by scanning all agent.json files
          agentPresets <- IO.blocking(scanAgentPresets())
          result <- Ok(
            Json.obj(
              "defaultPreset" -> file.defaultPreset.asJson,
              "presets" -> file.presets.values.toList.asJson,
              "agents" -> agentPresets.asJson
            )
          )
        yield result
      }

    // POST /presets — create a new preset (409 on duplicate name)
    case req @ POST -> Root / "presets" =>
      withAuth(req) {
        req.as[Json].flatMap { body =>
          val name = body.hcursor.downField("name").as[String].getOrElse("")
          if name.isEmpty then BadRequest(Json.obj("error" -> "Missing required field: name".asJson))
          else
            val store = new PresetStore()
            IO.blocking(store.load()).flatMap { file =>
              if file.presets.contains(name) then Conflict(Json.obj("error" -> s"Preset '$name' already exists".asJson))
              else
                val description = body.hcursor.downField("description").as[String].getOrElse("")
                val preferred = body.hcursor.downField("preferred").as[Option[String]].toOption.flatten
                val fallbacks = body.hcursor.downField("fallbacks").as[List[String]].getOrElse(Nil)
                val preset = ModelPreset(name, description, preferred, fallbacks)
                val updated = file.copy(presets = file.presets + (name -> preset))
                IO.blocking(store.save(updated)) *>
                  Created(preset.asJson)
            }
        }
      }

    // PUT /presets/default — set the default preset (must exist)
    case req @ PUT -> Root / "presets" / "default" =>
      withAuth(req) {
        req.as[Json].flatMap { body =>
          val name = body.hcursor.downField("name").as[String].getOrElse("")
          if name.isEmpty then BadRequest(Json.obj("error" -> "Missing required field: name".asJson))
          else
            val store = new PresetStore()
            IO.blocking(store.load()).flatMap { file =>
              if !file.presets.contains(name) then NotFound(Json.obj("error" -> s"Preset '$name' not found".asJson))
              else
                val updated = file.copy(defaultPreset = name)
                IO.blocking(store.save(updated)) *>
                  Ok(Json.obj("defaultPreset" -> name.asJson))
            }
        }
      }

    // PUT /presets/:name — update an existing preset (name immutable)
    case req @ PUT -> Root / "presets" / presetName =>
      withAuth(req) {
        req.as[Json].flatMap { body =>
          val store = new PresetStore()
          IO.blocking(store.load()).flatMap { file =>
            file.presets.get(presetName) match
              case None =>
                NotFound(Json.obj("error" -> s"Preset '$presetName' not found".asJson))
              case Some(existing) =>
                val description = body.hcursor.downField("description").as[String].getOrElse(existing.description)
                val preferred = body.hcursor.downField("preferred").as[Option[String]].toOption.flatten
                val fallbacks = body.hcursor.downField("fallbacks").as[List[String]].getOrElse(existing.fallbacks)
                val updated = existing.copy(description = description, preferred = preferred, fallbacks = fallbacks)
                val newFile = file.copy(presets = file.presets + (presetName -> updated))
                IO.blocking(store.save(newFile)) *>
                  Ok(updated.asJson)
          }
        }
      }

    // DELETE /presets/:name — delete a preset (409 if default; scrub agent refs)
    case req @ DELETE -> Root / "presets" / presetName =>
      withAuth(req) {
        val store = new PresetStore()
        IO.blocking(store.load()).flatMap { file =>
          if file.defaultPreset == presetName then
            Conflict(Json.obj("error" -> "Cannot delete the default preset; set another as default first".asJson))
          else if !file.presets.contains(presetName) then
            NotFound(Json.obj("error" -> s"Preset '$presetName' not found".asJson))
          else
            // 1. Delete preset from file
            val newFile = file.copy(presets = file.presets - presetName)
            // 2. Scrub all agent.json files that reference this preset
            for
              _ <- IO.blocking(store.save(newFile))
              _ <- scrubPresetRefs(presetName)
              result <- Ok(Json.obj("deleted" -> true.asJson))
            yield result
        }
      }

    // POST /presets/migrate-legacy — migrate per-agent model configs to presets
    // Body: {"agentNames": ["Coder", "qa-frontend", ...]}
    case req @ POST -> Root / "presets" / "migrate-legacy" =>
      withAuth(req) {
        req.as[Json].flatMap { body =>
          val agentNames = body.hcursor.downField("agentNames").as[List[String]].getOrElse(Nil)
          if agentNames.isEmpty then BadRequest(Json.obj("error" -> "Missing or empty agentNames".asJson))
          else migrateLegacyModels(agentNames)
        }
      }

    // ===== Provider model discovery =====

    // POST /provider/models — fetch a provider's model list (GET the endpoint
    // its protocol face declares, see `ModelListFaces`) so the settings dialog
    // can auto-populate model ids instead of hand-typing them. Body: {baseUrl,
    // apiKey?, protocol?, name?}. When apiKey is absent or the masked "***"
    // (edit dialog), the stored key of the matched provider is used. The face
    // comes from the body protocol when one is sent, else from the stored
    // provider matched by name or by baseUrl (the dialog posts {baseUrl,
    // apiKey} only), else anthropic — the dialog's own default.
    // Best-effort: any failure returns 502 + message; the frontend falls back to
    // manual entry (失败降级).
    case req @ POST -> Root / "provider" / "models" =>
      withAuth(req) {
        req.as[Json].flatMap { body =>
          val baseUrl = body.hcursor.downField("baseUrl").as[String].getOrElse("").trim
          val protocol = body.hcursor.downField("protocol").as[String].getOrElse("").trim
          val name = body.hcursor.downField("name").as[String].toOption.filter(_.nonEmpty)
          if baseUrl.isEmpty then BadRequest(Json.obj("error" -> "Missing required field: baseUrl".asJson))
          else
            checkHttpBaseUrl(baseUrl) match
              case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
              case Right(base) =>
                configRef.get.flatMap { cfg =>
                  val stored = name
                    .flatMap(n => cfg.llm.providers.get(n))
                    .orElse(cfg.llm.providers.values.find(_.baseUrl.replaceAll("/+$", "") == base))
                  val face =
                    if protocol == "openai" then LlmProtocol.OpenAI
                    else if protocol == "anthropic" then LlmProtocol.Anthropic
                    else stored.map(_.protocol).getOrElse(LlmProtocol.Anthropic)
                  val rawKey = body.hcursor.downField("apiKey").as[String].toOption.map(_.trim).getOrElse("")
                  val apiKey =
                    // Masked key from the edit dialog — fall back to the stored one.
                    if rawKey.nonEmpty && rawKey != "***" then rawKey
                    else stored.map(_.apiKey).getOrElse("")
                  ModelListFaces.models(face, base) match
                    case Nil => BadGateway(Json.obj("error" -> ModelListFaces.noEndpointMessage(face).asJson))
                    case urls =>
                      fetchProviderModels(urls, apiKey, face.name).flatMap {
                        case Right(models) => Ok(Json.obj("models" -> models.asJson))
                        case Left(err)    => BadGateway(Json.obj("error" -> err.asJson))
                      }
                }
        }
      }

    // ===== Daemon Management =====

    // GET /daemons — list all daemons with runtime state
    case req @ GET -> Root / "daemons" =>
      withAuth(req) {
        sharedResources.daemonService match
          case None => Ok(Json.obj("daemons" -> List.empty[String].asJson))
          case Some(svc) =>
            val store = new DaemonStore()
            store.load().flatMap { configs =>
              // Reconcile first: a daemons.json hot-edit (config removed /
              // changed under a running entry) must stop the stale process NOW,
              // not leave a ghost holding the port until the panel's stop can
              // no longer reach it (id changed/removed → 404).
              svc.reconcile(configs) *> svc.getStates(configs).flatMap { states =>
                val cfgById = configs.map(c => c.id -> c).toMap
                val statesJson = states.map { st =>
                  st.asJson.deepMerge(
                    Json.obj(
                      "autoStart" -> cfgById.get(st.id).exists(_.autoStart).asJson,
                      "restartOnExit" -> cfgById.get(st.id).exists(_.restartOnExit).asJson
                    )
                  )
                }
                Ok(Json.obj("daemons" -> statesJson.asJson))
              }
            }
      }

    // PUT /daemons/:id — update daemon config (autoStart / restartOnExit)
    case req @ PUT -> Root / "daemons" / daemonId =>
      withAuth(req) {
        sharedResources.daemonService match
          case None => NotFound(Json.obj("error" -> "Daemon service not available".asJson))
          case Some(_) =>
            req.as[Json].flatMap { body =>
              val autoStartOpt = body.hcursor.downField("autoStart").as[Option[Boolean]].toOption.flatten
              val restartOnExitOpt = body.hcursor.downField("restartOnExit").as[Option[Boolean]].toOption.flatten
              if autoStartOpt.isEmpty && restartOnExitOpt.isEmpty then
                BadRequest(
                  Json.obj("error" -> "No updatable fields provided (expected autoStart or restartOnExit)".asJson)
                )
              else
                val store = new DaemonStore()
                store
                  .update(
                    daemonId,
                    cfg =>
                      cfg.copy(
                        autoStart = autoStartOpt.getOrElse(cfg.autoStart),
                        restartOnExit = restartOnExitOpt.getOrElse(cfg.restartOnExit)
                      )
                  )
                  .flatMap {
                    case None => NotFound(Json.obj("error" -> s"Daemon '$daemonId' not found".asJson))
                    case Some(updated) => Ok(updated.asJson)
                  }
              end if
            }
      }

    // POST /daemons — create a new daemon
    case req @ POST -> Root / "daemons" =>
      withAuth(req) {
        sharedResources.daemonService match
          case None => NotFound(Json.obj("error" -> "Daemon service not available".asJson))
          case Some(svc) =>
            req.as[Json].flatMap { body =>
              val id = body.hcursor.downField("id").as[String].getOrElse("")
              val name = body.hcursor.downField("name").as[String].getOrElse("")
              val command = body.hcursor.downField("command").as[List[String]].getOrElse(List.empty)
              val cwd = body.hcursor.downField("cwd").as[Option[String]].toOption.flatten
              val env = body.hcursor.downField("env").as[Map[String, String]].getOrElse(Map.empty)
              val autoStart = body.hcursor.downField("autoStart").as[Boolean].getOrElse(false)
              val restartOnExit = body.hcursor.downField("restartOnExit").as[Boolean].getOrElse(false)
              val port = body.hcursor.downField("port").as[Option[Int]].toOption.flatten

              if id.isEmpty || name.isEmpty || command.isEmpty then
                BadRequest(Json.obj("error" -> "Missing required fields: id, name, command".asJson))
              else
                val config = DaemonConfig(
                  id = id,
                  name = name,
                  command = command,
                  cwd = cwd,
                  env = env,
                  autoStart = autoStart,
                  restartOnExit = restartOnExit,
                  port = port
                )
                val store = new DaemonStore()
                store.add(config).flatMap { updated =>
                  // Reconcile: adding with an existing id replaces the config —
                  // a running process of the OLD config must be stopped so the
                  // port is free for the new one.
                  svc.reconcile(updated) *> {
                    // Auto-start if requested
                    val startIO = if autoStart then svc.start(config).void.handleErrorWith(_ => IO.unit) else IO.unit
                    startIO *> svc.getState(id).flatMap {
                      case Some(state) => Ok(state.asJson)
                      case None => Ok(config.asJson)
                    }
                  }
                }
              end if
            }
      }

    // DELETE /daemons/:id — remove a daemon (stops if running)
    case req @ DELETE -> Root / "daemons" / daemonId =>
      withAuth(req) {
        sharedResources.daemonService match
          case None => NotFound(Json.obj("error" -> "Daemon service not available".asJson))
          case Some(svc) =>
            // Decouple stop from remove: a failure OR hang while stopping the
            // process must NOT block removal of the config entry. doStop can
            // raise (fiber-cancel / kill edge case) or hang (readFiber blocked
            // on a stdout pipe held open by an orphaned grandchild process), so
            // we bound it with a timeout and recover any error. The user asked
            // to delete the daemon, so daemons.json is updated regardless —
            // otherwise the entry reappears on the next GET /daemons.
            val store = new DaemonStore()
            // svc.remove (not stop): also drops the entry so the takeover
            // fiber exits — stop alone would leave it probing and resurrect
            // the deleted daemon once the port freed.
            svc.remove(daemonId).timeout(15.seconds).handleErrorWith { e =>
              logger.warn(
                s"Stop failed/timed out for daemon '$daemonId' during delete; removing config anyway: ${e.getMessage}"
              )
            } *> store.remove(daemonId) *> Ok(Json.obj("deleted" -> true.asJson))
      }

    // POST /daemons/:id/start — start a daemon
    case req @ POST -> Root / "daemons" / daemonId / "start" =>
      withAuth(req) {
        sharedResources.daemonService match
          case None => NotFound(Json.obj("error" -> "Daemon service not available".asJson))
          case Some(svc) =>
            val store = new DaemonStore()
            svc.startById(store, daemonId).flatMap {
              case Some(state) => Ok(state.asJson)
              case None => NotFound(Json.obj("error" -> s"Daemon '$daemonId' not found".asJson))
            }
      }

    // POST /daemons/:id/stop — stop a daemon
    case req @ POST -> Root / "daemons" / daemonId / "stop" =>
      withAuth(req) {
        sharedResources.daemonService match
          case None => NotFound(Json.obj("error" -> "Daemon service not available".asJson))
          case Some(svc) =>
            svc.stop(daemonId).flatMap {
              case Some(state) => Ok(state.asJson)
              case None => NotFound(Json.obj("error" -> s"Daemon '$daemonId' not found".asJson))
            }
      }

    // POST /daemons/:id/restart — restart a daemon
    case req @ POST -> Root / "daemons" / daemonId / "restart" =>
      withAuth(req) {
        sharedResources.daemonService match
          case None => NotFound(Json.obj("error" -> "Daemon service not available".asJson))
          case Some(svc) =>
            val store = new DaemonStore()
            svc.restart(store, daemonId).flatMap {
              case Some(state) => Ok(state.asJson)
              case None => NotFound(Json.obj("error" -> s"Daemon '$daemonId' not found".asJson))
            }
      }
  }

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
   */
  private def scanAgentPresets(): Map[String, Option[String]] =
    allAgentJsonFiles().flatMap { path =>
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

  /**
   * Determine the resolvedFrom value for an agent by reading the raw agent.json.
   * Returns "preset" | "legacy-model" | "default-preset" | "global".
   */
  private def computeResolvedFrom(preset: Option[String], agentName: String): String =
    val store = new PresetStore()
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
            else if file.presets.get(file.defaultPreset).exists(p => p.preferred.isDefined || p.fallbacks.nonEmpty) then
              "default-preset"
            else "global"
          case None => "global"
    else
      // No preset — check legacy model
      EntityLoader.findAgentDir(agentName).unsafeRunSync() match
        case Some(dir) =>
          val json = parser.parse(os.read(dir / "agent.json")).toOption.getOrElse(Json.obj())
          val model = json.hcursor.downField("model").as[Option[nebflow.shared.AgentModelConfig]].toOption.flatten
          if model.exists(m => m.preferred.isDefined || m.fallbacks.nonEmpty) then "legacy-model"
          else
            val file = store.load()
            if file.presets.get(file.defaultPreset).exists(p => p.preferred.isDefined || p.fallbacks.nonEmpty) then
              "default-preset"
            else "global"
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
      }
    yield teamsList.asJson

  // ── Flow editor helpers ──────────────────────────────────

  private def isValidFlowName(name: String): Boolean =
    name.nonEmpty && name.matches("^[a-zA-Z0-9][a-zA-Z0-9._-]*$") && !name.contains("..")

  private def isValidAgentName(name: String): Boolean =
    name.nonEmpty && name.matches("^[a-zA-Z0-9][a-zA-Z0-9._-]*$") && !name.contains("..")

  /** 令 1 派发开关的 REST 实现单点（`/plugins/:name/enable|disable`）。写 `plugins
    * .dispatch.<name>.authorEnabled`（作者意图层，durable）+ 一条 append-only 审计；
    * **不影响内容信任面** ⇒ 在飞节点零影响。 */
  private def dispatchSwitch(name: String, enable: Boolean): IO[Response[IO]] =
    if !isValidAgentName(name) then BadRequest(Json.obj("error" -> "Invalid plugin name".asJson))
    else
      nebflow.core.plugin.PluginDispatchPolicy.setAuthorEnabled(name, enable, "panel/rest").flatMap {
        case Right(_) =>
          Ok(Json.obj(
            "ok" -> true.asJson,
            "message" -> (s"Plugin '$name' dispatch ${if enable then "enabled" else "disabled"} — " +
              "affects FUTURE dispatches only; nodes already dispatched keep their plugin grant " +
              "(content trust is untouched; use /revoke to withdraw content trust).").asJson
          ))
        case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
      }

  private def withAuth(req: Request[IO])(f: => IO[Response[IO]]): IO[Response[IO]] =
    if checkAuth(req) then f
    else Forbidden(Json.obj("error" -> "Unauthorized".asJson))

  /** Error-code mapping for the social-channel endpoints (arch §7.3):
    * `400 invalid_field` / `400 unknown_channel` / `403 secret_mode` / `500 io`.
    * A credential-storage failure is a 403 with the field named — it is never
    * folded into a generic 500, and never reported as a success.
    *
    * Returns the response in effect (`IO`), not a bare value: the http4s dsl
    * constructors already produce `F[Response[F]]`, so wrapping them here and
    * de-wrapping at the call site would be a pointless round trip. */
  private def socialErrorResponse(err: nebflow.social.SocialChannels.Failure): IO[Response[IO]] =
    import nebflow.social.SocialChannels.Failure
    err match
      case Failure.UnknownChannel(id) =>
        BadRequest(Json.obj("error" -> "unknown_channel".asJson, "channel" -> id.asJson))
      case Failure.InvalidField(field, reason) =>
        BadRequest(Json.obj("error" -> "invalid_field".asJson, "field" -> field.asJson,
          "reason" -> reason.asJson))
      case Failure.SecretMode(field, reason) =>
        Forbidden(Json.obj("error" -> "secret_mode".asJson, "field" -> field.asJson,
          "reason" -> reason.asJson))
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

  /** Identity claimed by the peer opening a presence WS upgrade.
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

  private def verifyPeerAccess(req: Request[IO]): IO[Either[Response[IO], NeblinkService]] =    neblinkService match
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

  /** Pure form of the membership + freshness leg, so the criterion is testable
    * without a live [[NeblinkService]] (see `PeerCriterionFreshnessSpec`). */
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
      case Some(t) => Auth.validateToken(t, token)
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
   * One model-list probe outcome. `NoEndpoint` is the only verdict that lets
   * the caller move on to the next declared candidate; `Failed` already carries
   * a user-facing message.
   */
  private enum ModelsProbe:
    case Found(models: List[Json])
    case NoEndpoint(detail: String)
    case Failed(detail: String)

  /**
   * GET every model-list endpoint declared for one provider face, in probe
   * order (`ModelListFaces`), and extract the model entries (both
   * OpenAI-compatible and Anthropic reply `{"data":[{"id":..}]}`, normalized
   * with empty ids removed and duplicates collapsed). Each entry is `{id}` plus
   * `contextLength` when the provider reports one (OpenRouter
   * `context_length`, others `context_window`) — absent/unparsable means the
   * field is simply omitted.
   *
   * Only a "no such endpoint" verdict advances to the next declared candidate
   * (`ModelsProbe.NoEndpoint`: HTTP 404, or a 2xx body carrying the provider's
   * own 404 envelope — measured on zhipu, whose gateway answers `HTTP 200` with
   * `{"code":500,"msg":"404 NOT_FOUND"}`). Every other failure is final, so a
   * later candidate can never mask a real error. Uses the same JDK
   * HttpClient posture as the LLM adapters (`OutboundHttpClients.Policy.SystemProxy10s`:
   * HTTP/1.1 forced, system proxy honored) — a probe must see the same network
   * path real completions take.
   *
   * D1 — why HTTP/1.1 here, and what would flip it (this peer is NOT the
   * neblink Caddy face, so the two must not share a conclusion):
   *   · Evidence: **this call faces a different reverse proxy** — the provider
   *     API gateway, the same family as the nginx/one-api endpoint that
   *     produced the original 2026-08-11 `bad_record_mac` incident, so its
   *     trap evidence is *closer* to the original finding than neblink's. What
   *     we actually have measured here is nothing: the incident's alert text
   *     and frequency were never retained, and no reading on this path since.
   *     The 2026-09-12 probe covered neblink/Caddy only ⇒ the neblink
   *     14/14-green result says nothing about this peer.
   *   · Judge-red: a reproduced `bad_record_mac` / TLS alert on this path, or a
   *     GOAWAY / closed-reset bucket here in the outbound-failure counters, or
   *     an h2-vs-h1 same-window comparison (n ≥ 100/arm) showing h2 p95 >
   *     h1 p95 × 1.2. Do NOT relax this pin as part of a neblink-Caddy review.
   */
  private def fetchProviderModels(
    modelsUrls: List[String],
    apiKey: String,
    protocol: String
  ): IO[Either[String, List[Json]]] =
    IO.blocking {
      val client = OutboundHttpClients.client(OutboundHttpClients.Policy.SystemProxy10s)

      // Declared candidates, probed in order; only a missing endpoint advances.
      def probe(urls: List[String], missing: Option[String]): Either[String, List[Json]] =
        urls match
          case Nil =>
            val tried = missing.map(d => s" — tried $d").getOrElse("")
            Left(s"No model-list endpoint found for this provider$tried: enter model ids manually")
          case url :: rest =>
            probeModelList(client, url, apiKey, protocol) match
              case ModelsProbe.Found(models)      => Right(models)
              case ModelsProbe.NoEndpoint(detail) => probe(rest, Some(s"$url -> $detail"))
              case ModelsProbe.Failed(detail)     => Left(detail)

      probe(modelsUrls, None)
    }.handleErrorWith(e => IO.pure(Left(unreachable(e))))

  /**
   * GET one declared endpoint with protocol-specific auth headers and classify
   * the reply. A 2xx body that still carries the provider's own error envelope
   * counts as a failure: the zhipu gateway answers `HTTP 200` with
   * `{"code":500,"msg":"404 NOT_FOUND",...}` on an endpoint it does not serve,
   * and reporting that as "no models" hid the real 404 from the dialog.
   */
  private def probeModelList(
    client: java.net.http.HttpClient,
    url: String,
    apiKey: String,
    protocol: String
  ): ModelsProbe =
    try
      val reqBuilder = java.net.http.HttpRequest
        .newBuilder()
        .uri(java.net.URI.create(url))
        .timeout(java.time.Duration.ofSeconds(15))
        .GET()
      if protocol == "openai" then
        if apiKey.nonEmpty then reqBuilder.header("Authorization", s"Bearer $apiKey")
      else
        // anthropic
        reqBuilder.header("x-api-key", apiKey)
        reqBuilder.header("anthropic-version", "2023-06-01")
      val response = client.send(reqBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
      val status = response.statusCode()
      val body = response.body()
      val parsed = parser.parse(body)
      val detail = parsed.toOption.flatMap(errorDetail).getOrElse(body.take(200))
      if status == 404 || parsed.toOption.exists(saysNoEndpoint) then ModelsProbe.NoEndpoint(detail)
      else if status >= 200 && status < 300 then
        parsed match
          case Left(err) => ModelsProbe.Failed(s"Invalid JSON from provider: ${err.message}")
          case Right(json) =>
            val models = extractModels(json)
            if models.nonEmpty then ModelsProbe.Found(models)
            else if isErrorEnvelope(json) then
              ModelsProbe.Failed(s"Provider returned HTTP $status with an error body: $detail")
            else ModelsProbe.Failed("Provider returned no models")
      else ModelsProbe.Failed(s"Provider returned HTTP $status: $detail")
    catch case e: Exception => ModelsProbe.Failed(unreachable(e))
    end try

  /**
   * Extract `data[].id` from a provider reply (both OpenAI-compatible and
   * Anthropic replies) with empty ids dropped and duplicates collapsed (first
   * occurrence wins). Each entry is `{id}` plus `contextLength` when the
   * provider reports one (OpenRouter `context_length`, others
   * `context_window`).
   */
  private def extractModels(json: Json): List[Json] =
    val entries = json.hcursor
      .downField("data")
      .as[List[Json]]
      .getOrElse(Nil)
      .flatMap(j => j.hcursor.downField("id").as[String].toOption.map(_.trim).filter(_.nonEmpty).map(id => (id, j)))
    // distinct by id, first occurrence wins
    val seen = scala.collection.mutable.LinkedHashSet.empty[String]
    entries.collect { case (id, raw) if seen.add(id) =>
      val ctx = List("context_length", "context_window")
        .flatMap(k => raw.hcursor.downField(k).as[Long].toOption)
        .headOption
      ctx match
        case Some(n) => Json.obj("id" -> id.asJson, "contextLength" -> n.asJson)
        case None    => Json.obj("id" -> id.asJson)
    }

  /**
   * Provider-side error text, when the reply carries one (`error.message`,
   * `error` as a string, `msg`, or `message`).
   */
  private def errorDetail(json: Json): Option[String] =
    val c = json.hcursor
    List(
      c.downField("error").downField("message").as[String].toOption,
      c.downField("error").as[String].toOption,
      c.downField("msg").as[String].toOption,
      c.downField("message").as[String].toOption
    ).flatten.map(_.trim).find(_.nonEmpty)

  /**
   * A reply that is an error even under a 2xx status: `{"error":…}`,
   * `{"success":false}`, or the `{"code":…,"msg":…}` envelope a gateway uses to
   * carry an HTTP-level error code. Such a body must never be reported as
   * "no models".
   */
  private def isErrorEnvelope(json: Json): Boolean =
    val c = json.hcursor
    c.downField("error").focus.isDefined ||
      c.downField("success").as[Boolean].toOption.contains(false) ||
      (c.downField("code").focus.isDefined && c.downField("msg").focus.isDefined)

  /**
   * The reply says the endpoint does not exist (a 2xx carrier of
   * `404 NOT_FOUND`, or a `resource_not_found_error`) — the only verdict that
   * lets `fetchProviderModels` try the face's next declared candidate.
   */
  private def saysNoEndpoint(json: Json): Boolean =
    val c = json.hcursor
    val code = c.downField("code").as[Int].toOption
      .orElse(c.downField("code").as[String].toOption.flatMap(_.trim.toIntOption))
    val text = List(
      c.downField("msg").as[String].toOption,
      c.downField("message").as[String].toOption,
      c.downField("error").downField("message").as[String].toOption,
      c.downField("error").downField("type").as[String].toOption
    ).flatten.mkString(" ").toLowerCase
    code.contains(404) || text.contains("not_found") || text.contains("not found")

  /**
   * Transport-level failure text: a provider whose declared endpoints answer
   * nothing at all (proxy, DNS, TLS, timeout) is reported through this branch,
   * never as a silent empty list.
   */
  private def unreachable(e: Throwable): String =
    s"Provider unreachable: ${Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)}"

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

  /** `completeDeviceEnrollment` 的结果通道版本：`Right(networkId)` = 已落盘并热换，
    * `Left(err)` = **真实失败原因原文**（护栏拒绝 ⇒ `EnrollGuard` 的 reason）。
    * 回调页需要它来透真因（案 C ①(a)）；HTTP 形态由调用方决定。 */
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

  /** The enrollment half of completeDeviceEnrollment — delegates to
    * NeblinkEnrollment (shared with the startup client's silent re-login
    * hook, which has no HTTP context). Returns the persisted device token. */
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

  // ── Loopback callback (RFC 8252): Logto redirects the browser here after
  // the hosted login. No gateway token — the browser carries only the
  // provider redirect; the PKCE state parameter is the anti-CSRF check.

  /** The registered loopback redirect (RFC 8252: the provider accepts ANY
    * local port against the port-less registered URI). */
  private def loopbackCallbackUri: String = s"http://127.0.0.1:$gatewayPort/auth/callback"

  /** Start a Logto AC+PKCE login: fresh verifier/state registered in
    * [[PkceLoginSession]] + the authorize URL for the SAME parameters.
    * Returns None when the provider is unconfigured (or the AC app id is
    * missing) — callers answer `logto-not-configured`, exactly as before.
    *
    * SINGLE SOURCE of the login start: `POST /api/neblink/auth/start` and the
    * switch-account landing continuation (`/auth/logged-out`) both call this,
    * so a switch continuation can never drift from a normal login (same
    * redirect URI, same PKCE parameters, same prompt mapping — `forceLogin`
    * ⇒ `prompt="login consent"`, the forced-fresh-login semantic the
    * switch-account path requires). */
  private def beginPkceLogin(
    ms: NeblinkService,
    forceLogin: Boolean,
    uiLocales: String
  ): IO[Option[String]] =
    ms.neblinkConfig.map(_.effectiveLogto).flatMap {
      case Some(lc) if lc.pkceClientId.isDefined =>
        val pkceClientId = lc.pkceClientId.getOrElse("")
        for
          verifier <- LogtoAuthCode.generateVerifier
          challenge = LogtoAuthCode.challengeS256(verifier)
          state <- LogtoAuthCode.generateState
          _ <- pkceLogin.start(verifier, state)
          authorizeUrl = LogtoAuthCode.authorizeUrl(
            lc.endpoint,
            pkceClientId,
            loopbackCallbackUri,
            challenge,
            state,
            prompt = if forceLogin then "login consent" else "consent",
            uiLocales = uiLocales
          )
        yield Some(authorizeUrl)
      case _ => IO.pure(None)
    }

  def authCallbackRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "callback" =>
      handleAuthCallback(req.uri.query.params)
    // RP-logout return target. Reachability (2026-09-15 oidcfix reading): the
    // provider REJECTS an unregistered `post_logout_redirect_uri` with 400
    // `post_logout_redirect_uri not registered` (the older "ignored, always
    // safe" note is obsolete), and it does NOT apply loopback port leniency
    // here — so this page is reached only on a port whose exact URI is
    // allow-listed on the Logto app (today: 8080 and 8097).
    //
    // Behaviour is decided in ONE place — the single-use switch handoff marker:
    // armed+fresh ⇒ consume and continue the switch-account login IN THIS
    // WINDOW (302 to the same authorize URL /auth/start would return, forced
    // fresh login); otherwise a static card (plain logout = the unchanged
    // "已退出登录" card; switch cases get an explicit spent/expired/failed card).
    case GET -> Root / "logged-out" =>
      renderLoggedOutLanding
  }

  /** The `/auth/logged-out` landing page (see the route comment above for the
    * reachability constraint). This is the ONLY consumer of the switch handoff
    * marker, and it consumes it exactly once — a replay/reload therefore gets
    * [[NeblinkSwitchHandoff.Outcome.Replay]] and a visible card instead of a
    * second auto-login. */
  private def renderLoggedOutLanding: IO[org.http4s.Response[IO]] =
    switchHandoff.consume.flatMap {
      case NeblinkSwitchHandoff.Outcome.Plain =>
        htmlResponse(loggedOutPage, Status.Ok)
      case NeblinkSwitchHandoff.Outcome.Replay =>
        htmlResponse(
          switchNoticePage(
            symbol = "!",
            symbolColor = "#d1242f",
            headline = "续登链接已使用",
            detail = "切号续登入口一次性有效，重放本页不会再次自动登录。如需切换账号，请在 nebflow 设置页重新点「切换账号」。"
          ),
          Status.Ok
        )
      case NeblinkSwitchHandoff.Outcome.Expired =>
        htmlResponse(
          switchNoticePage(
            symbol = "!",
            symbolColor = "#d1242f",
            headline = "续登已超时",
            detail = "本次切号续登标记已过期，未自动登录。请在 nebflow 设置页重新点「切换账号」。"
          ),
          Status.Ok
        )
      case NeblinkSwitchHandoff.Outcome.Continue(uiLocales) =>
        neblinkService match
          case None =>
            htmlResponse(
              switchNoticePage("!", "#d1242f", "续登未启动", "nebflow 服务未初始化，请在本窗口手动登录。"),
              Status.Ok
            )
          case Some(ms) =>
            beginPkceLogin(ms, forceLogin = true, uiLocales = uiLocales)
              .flatMap {
                case Some(url) =>
                  // Same 302 shape as the end-session hop (dsl `Found` returns a
                  // ResponseGenerator, i.e. IO[Response] — build it explicitly so
                  // this branch and the `None` branch share one IO type).
                  IO.pure(
                    org.http4s.Response[IO](Status.Found)
                      .withHeaders(Headers(Location(Uri.unsafeFromString(url))))
                  )
                case None =>
                  htmlResponse(
                    switchNoticePage("!", "#d1242f", "续登未启动", "登录服务未配置，请在本窗口手动登录。"),
                    Status.Ok
                  )
              }
              // 🔴 缺陷 A 返工（round 1 / 判词 D1）：本页是**用户可见**的换号续登失败页。
              // `e.getMessage` 直出会把整条 authorize URL、PKCE `state` 值与文件系统路径
              // 一起送到用户眼前（判词探针 P7 原文：`发起登录失败：Invalid URI:
              // C:\Users\…\bad endpoint/oidc/auth?…&state=…` ⇒ 判据正则命中 2）。
              // 改走同文件既有的**分类通道**（与 `:981-990`（end-session 意外失败）/
              // `:4423-4431`（回调 `Left(e)` 支）**同形**，零新轮子）：三段式
              // （原因 + 动作 + 稳定诊断码）进页面，原始细节（异常类名 / 路径 / URL）
              // **只**进 WARN。页面的其它语义（headline / 状态码 / 单窗口续登契约）零改动。
              .handleErrorWith { e =>
                val diagnostic = nebflow.neblink.CredentialDiagnostics.classifyFailure(
                  e,
                  nebflow.neblink.CredentialFailure.Unclassified
                )
                logger.warn(diagnostic.logLine("switch continue login failed"), "code" -> diagnostic.code) *>
                  htmlResponse(
                    switchNoticePage("!", "#d1242f", "续登失败", diagnostic.message),
                    Status.Ok
                  )
              }
    }

  /** The 8-step local teardown shared by POST /neblink/logout and the
    * RP-initiated end-session endpoint. Always completes locally — every
    * remote/best-effort step swallows failures. */
  private def performLocalLogout(ms: NeblinkService): IO[Unit] =
    for
      // 1. Notify the NebLink Server: DELETE /api/device/logout lets the
      //    server clear the device session + relay tunnel immediately
      //    instead of waiting for the 90s TTL purge. Best-effort — logout
      //    must ALWAYS succeed locally, so any failure (network
      //    unreachable, server down) is swallowed. Uses the discovery's
      //    live client: enrollment hot-swap replaces it without updating
      //    ms.relayClientOpt, which may hold a stale instance.
      _ <- neblinkDiscovery.fold(IO.unit)(d =>
        d.currentClient.flatMap {
          case Some(client) =>
            client.logout.handleErrorWith(e =>
              logger.debug(s"NebLink server logout notify failed (continuing local logout): ${e.getMessage}")
            )
          case None => IO.unit
        }
      )
      // 2. Stop the relay tunnel — drops the local WS so no reconnect
      //    loop keeps the device visible server-side after logout.
      _ <- ms.relayTunnelOpt.fold(IO.unit)(t => t.stop().handleErrorWith(_ => IO.unit))
      // 3. Drop all P2P presence connections and cancel auto-reconnects —
      //    otherwise a reconnecting peer could silently re-enter the local
      //    list after logout (reconnectLoop re-upserts on success).
      _ <- ms.presenceServiceOpt.fold(IO.unit)(p => p.disconnectAll().handleErrorWith(_ => IO.unit))
      // 4. Delete the device credential file (~/.nebflow/neblink/device.json)
      _ <- DeviceCredential.clear
      // 5. Disable NebLink in config (keep neblinkServer address for next login).
      //    updateConfig refreshes the in-memory ref AND persists to disk, so
      //    /status reflects the change immediately without a restart.
      _ <- ms.updateConfig(_.copy(enabled = false))
      // 6. Stop the NebLink client (hot-swap to None). Both client pointers are
      //    cleared: the discovery one (authoritative live client) AND the
      //    NeblinkService relay pointer, which every relay consumer reads
      //    (RemoteExecutor / DropboxService / status) —
      //    leaving it behind kept a logged-out client reachable through the
      //    relay path (2026-09-10 隧道鉴权自愈批 convergence sweep).
      _ <- neblinkDiscovery.fold(IO.unit)(d => d.setClient(None))
      _ <- IO(ms.setRelayClient(None))
      // 7. Clear user info (avatar, github login) from the device identity.
      _ <- ms.updateDeviceInfo(avatarUrl = Some(""), githubLogin = Some(""))
      // 8. Clear all discovered peers.
      _ <- ms.clearPeers
    yield ()

  private def handleAuthCallback(query: Map[String, String]): IO[org.http4s.Response[IO]] =
    // Provider error redirect (?error=...&error_description=...) — user
    // denied / hosted-page failure.
    //
    // 🔴 缺陷 A：失败面一律走**分类三段式**（上游 §8.2 第 4 项：禁 `getMessage`/原文直出）。
    // 原始串（服务方 error 码 + description，可能是任何文本）只进 WARN（带分类码 + 归因）。
    LogtoAuthCode.parseCallbackError(query) match
      case Some(cb) =>
        val raw = s"${cb.error}${cb.description.fold("")(d => s": $d")}"
        val diagnostic = nebflow.neblink.CredentialDiagnostics.diagnosticOf(
          nebflow.neblink.CredentialFailure.ProviderError,
          raw
        )
        logger.warn(diagnostic.logLine("provider error redirect"), "code" -> diagnostic.code) *>
          pkceLogin.failDiagnosed(diagnostic) *>
          htmlResponse(callbackPage(ok = false, diagnostic.message), Status.BadRequest)
      case None =>
        val code = query.getOrElse("code", "")
        val state = query.getOrElse("state", "")
        pkceLogin.take(state).flatMap {
          // None = mismatch / expired / stray hit (take already set the
          // error status for pending attempts).
          case None =>
            htmlResponse(
              callbackPage(
                ok = false,
                nebflow.neblink.CredentialDiagnostics
                  .diagnosticOf(nebflow.neblink.CredentialFailure.CallbackStateInvalid)
                  .message
              ),
              Status.BadRequest
            )
          case Some(verifier) =>
            neblinkService match
              case None =>
                val diagnostic = nebflow.neblink.CredentialDiagnostics.diagnosticOf(
                  nebflow.neblink.CredentialFailure.ServiceUnavailable,
                  "NebLink service not initialized"
                )
                pkceLogin.failDiagnosed(diagnostic) *>
                  htmlResponse(callbackPage(ok = false, diagnostic.message), Status.InternalServerError)
              case Some(ms) =>
                for
                  // Same resolution as /api/neblink/auth/start: explicit logto
                  // block wins, otherwise the embedded production default.
                  // The raw `_.logto` read here previously broke the PKCE
                  // chain for fresh installs (start succeeded via the
                  // embedded default, then the callback died with a
                  // misleading "Logto 登录未配置" — 2026-08-30).
                  logto <- ms.neblinkConfig.map(_.effectiveLogto)
                  target <- neblinkServerUrl(None)
                  // 案 b①（2026-09-20）：目标解析先于换码 —— `None`（隔离数据根 + 无显式开关
                  // + 无配置 URL）⇒ 整条 PKCE 腿在**换码之前**失败，环 5 的 `register`
                  // 从此没有起点。两个缺席原因（无目标 / 无 AC 应用）在此合流为 `None`，
                  // 由下面的 `case None =>` 按 `target.isEmpty` 各归其类。
                  resp <- (
                    for
                      serverUrl <- target
                      client <- logto.flatMap(lc => lc.pkceClientId.map(pkce => (lc, pkce)))
                    yield (serverUrl, client)
                  ) match
                    case Some((serverUrl, (lc, pkceClientId))) =>
                      LogtoAuthCode
                        .tokenCall(LogtoDeviceFlow.jdkSend)(
                          LogtoAuthCode.tokenRequest(
                            lc.endpoint,
                            pkceClientId,
                            loopbackCallbackUri,
                            code,
                            verifier
                          )
                        )
                        .flatMap {
                          case Right(tokens) =>
                            ms.identity.flatMap { identity =>
                              // C2 (2026-09-01 login-chain fix): id_token picture
                              // → device identity avatarUrl immediately (Logto
                              // users get an avatar without waiting for the
                              // enroll response; neblink-server avatar sync is
                              // the C1 half, this is the client-side half).
                              val pictureWrite = tokens.picture match
                                case Some(pic) if pic.nonEmpty =>
                                  ms.updateDeviceInfo(avatarUrl = Some(pic))
                                case _ => IO.unit
                              pictureWrite *>
                              LogtoDeviceFlow
                                .register(LogtoDeviceFlow.jdkSend)(
                                  serverUrl,
                                  tokens.accessToken,
                                  identity.deviceId,
                                  identity.deviceName,
                                  identity.platform
                                )
                                .flatMap {
                                  case Right(json) =>
                                    // 案 C（2026-09-14 作者裁定 C+B·客户端一刀）：
                                    // 显式登录放行 + 失败透真因。
                                    // ①(b) 机械判据：`explicitUserAction = true` 只在
                                    // 这里给出，而这里的唯一入口是上面
                                    // `pkceLogin.take(state)` 命中 —— 即「回调携带的
                                    // state 命中了一次性、进程内、由 POST
                                    // /api/neblink/auth/start（登录按钮的端点）建立的
                                    // 待决登录尝试」。自动路径不可能携带它：boot 客户端
                                    // 与 silent re-login 从不调 /auth/start，也从不经过
                                    // /auth/callback；该标记 take 一次即消费、15min 过期
                                    // （PkceLoginSession.ExpiryMs），且 pkceLogin 是进程内
                                    // 单飞槽（`start` 的唯一调用点 = auth/start 路由）。
                                    // ①(a) 真因：Left 原文（护栏拒绝 ⇒ EnrollGuard 的
                                    // reason 文本）上页面与 /auth/state 面板，不再吞成
                                    // 「设备注册未完成」。
                                    completeDeviceEnrollmentDetailed(
                                      ms,
                                      serverUrl,
                                      json,
                                      tokens.refreshToken,
                                      tokens.idToken,
                                      explicitUserAction = true
                                    ).attempt
                                      .flatMap {
                                        case Right(Right(_)) =>
                                          pkceLogin.succeed *> htmlResponse(callbackPage(ok = true, ""), Status.Ok)
                                        // 🔴 缺陷 A：`Right(Left(err))` 与 `Left(e)` 两支都改走
                                        // **分类映射**（上游 §8.2 第 4 项），`getMessage` / 自由串
                                        // 一律不进用户可见面。
                                        //  · `err` 是左通道自由串 —— 逐字符反查分类（`persist` 出来的
                                        //    凭据失败/服务端缺凭据都已是三段式文案），护栏拒绝则按
                                        //    既有的逐字符相等判据给 `enroll-refused-isolated-home`
                                        //    （案 C 语义：真因照实透出，护栏文本本身干净）。
                                        case Right(Left(err)) =>
                                          val diagnostic = classifyEnrollFailure(serverUrl, err)
                                          pkceLogin.failDiagnosed(diagnostic) *>
                                            htmlResponse(callbackPage(ok = false, diagnostic.message), Status.BadGateway)
                                        case Left(e) =>
                                          val diagnostic = nebflow.neblink.CredentialDiagnostics.classifyFailure(
                                            e,
                                            nebflow.neblink.CredentialFailure.Unclassified
                                          )
                                          logger.warn(
                                            diagnostic.logLine("auth callback enrollment failed"),
                                            "code" -> diagnostic.code
                                          ) *>
                                            pkceLogin.failDiagnosed(diagnostic) *>
                                            htmlResponse(callbackPage(ok = false, diagnostic.message),
                                              Status.InternalServerError)
                                      }
                                  case Left(err) =>
                                    val diagnostic = nebflow.neblink.CredentialDiagnostics.diagnosticOf(
                                      nebflow.neblink.CredentialFailure.DeviceRegisterFailed,
                                      err
                                    )
                                    logger.warn(diagnostic.logLine("device register failed"),
                                      "code" -> diagnostic.code) *>
                                      pkceLogin.failDiagnosed(diagnostic) *>
                                      htmlResponse(callbackPage(ok = false, diagnostic.message), Status.BadGateway)
                                }
                            }
                          case Left(err) =>
                            val diagnostic = nebflow.neblink.CredentialDiagnostics.diagnosticOf(
                              nebflow.neblink.CredentialFailure.TokenExchangeFailed,
                              err
                            )
                            logger.warn(diagnostic.logLine("token exchange failed"), "code" -> diagnostic.code) *>
                              pkceLogin.failDiagnosed(diagnostic) *>
                              htmlResponse(callbackPage(ok = false, diagnostic.message), Status.BadRequest)
                        }
                    case None =>
                      if target.isEmpty then
                        // 案 b①：无目标 —— 隔离实例不得把生产默认当入网目标。失败文案 =
                        // 护栏原文（`EnrollRefusedIsolatedHome` 的例外分支把**干净**原文
                        // 逐字送上回调页与 /auth/state 面板；见 CredentialDiagnostics）。
                        val diagnostic = nebflow.neblink.CredentialDiagnostics.diagnosticOf(
                          CredentialFailure.EnrollRefusedIsolatedHome,
                          EnrollGuard.prodFallbackRefusalReason
                        )
                        logger.warn(
                          diagnostic.logLine("no enrollment target (案 b①)"),
                          "code" -> diagnostic.code
                        ) *>
                          pkceLogin.failDiagnosed(diagnostic) *>
                          htmlResponse(callbackPage(ok = false, diagnostic.message), Status.BadRequest)
                      else
                        // With effectiveLogto this only fires when an explicit
                        // logto block exists but lacks pkceClientId (the
                        // embedded default carries one; a missing block falls
                        // back to it). Name the actual misconfiguration.
                        val diagnostic = nebflow.neblink.CredentialDiagnostics.diagnosticOf(
                          nebflow.neblink.CredentialFailure.LogtoNotConfigured,
                          "logto-pkce-client-not-configured"
                        )
                        pkceLogin.failDiagnosed(diagnostic) *>
                          htmlResponse(callbackPage(ok = false, diagnostic.message), Status.NotFound)
                yield resp
        }

  /** 案 C ①(a)（2026-09-14）语义的**分类化**承接（缺陷 A / 上游 §8.2 第 4 项）：
    * 回调页与 `/auth/state` 的失败文案不再直出自由串，而是走
    * `CredentialDiagnostics` 的分类映射 —— 三段式（原因 + 动作 + 稳定诊断码）。
    *
    * 两条判据逐字保留自修前实现：
    *  - **隔离护栏拒绝**：`EnrollGuard.enrollRefusal(serverUrl)` 的 reason 是
    *    `(serverUrl, 开关)` 的纯函数（同输入同输出）⇒ 与 `err` **逐字符相等**即判定为护栏
    *    拒绝，翻译成 `enroll-refused-isolated-home`；护栏原文**照实透出**（案 C：失败透真因，
    *    且该文本本身不含路径/异常类名）。
    *  - **其余**：先按可见三段式**反查**（`persist` 出来的凭据失败/服务端缺凭据都已是分类
    *    文案）⇒ 同一个稳定 code 回到结构化通道；反查不中 ⇒ 兜底分类 + 原文进**日志**。 */
  private def classifyEnrollFailure(
    serverUrl: String,
    err: String
  ): nebflow.neblink.CredentialDiagnostics.Diagnostic =
    import nebflow.neblink.{CredentialDiagnostics as CD, CredentialFailure as CF}
    nebflow.neblink.EnrollGuard.enrollRefusal(serverUrl) match
      case Some(reason) if reason == err => CD.diagnosticOf(CF.EnrollRefusedIsolatedHome, err)
      case _ =>
        CD.byVisibleMessage(err).map(f => CD.diagnosticOf(f)).getOrElse(CD.diagnosticOf(CF.Unclassified, err))

  /** Static loopback login result page (success + error variants). The
    * frontend learns the outcome by polling /api/neblink/auth/state. */
  private def callbackPage(ok: Boolean, message: String): String =
    val headline = if ok then "登录成功，可关闭本页" else "登录失败"
    val detail = if ok then "nebflow 账号已连接，本窗口可以关闭" else message
    val icon = if ok then "✓" else "✕"
    val iconColor = if ok then "#07c160" else "#d1242f"
    s"""<!doctype html>
       |<html lang="zh-CN"><head><meta charset="utf-8">
       |<meta name="viewport" content="width=device-width,initial-scale=1">
       |<title>nebflow 登录</title>
       |<style>body{font-family:-apple-system,'Segoe UI','PingFang SC',sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#f6f7f9;color:#1f2328}
       |.card{text-align:center;padding:44px 52px;border-radius:14px;background:#fff;box-shadow:0 2px 14px rgba(0,0,0,.08)}
       |.icon{color:$iconColor;font-size:42px;line-height:1;margin-bottom:10px}h1{font-size:18px;font-weight:600;margin:0 0 8px}
       |p{color:#6a737d;font-size:13px;margin:0;max-width:320px;word-break:break-all}</style></head>
       |<body><div class="card"><div class="icon">$icon</div><h1>$headline</h1><p>$detail</p></div></body></html>""".stripMargin

  /** RP-logout landing page (post_logout_redirect_uri target, 2026-09-06).
    * Same visual skeleton as callbackPage — a static result card.
    * 🔴 UNCHANGED by the one-window switch batch (2026-09-16): the plain-logout
    * landing must stay byte-identical, so the switch-continuation cards are a
    * separate builder ([[switchNoticePage]]) rather than a parameterisation of
    * this one. */
  private def loggedOutPage: String =
    s"""<!doctype html>
       |<html lang="zh-CN"><head><meta charset="utf-8">
       |<meta name="viewport" content="width=device-width,initial-scale=1">
       |<title>nebflow 已退出登录</title>
       |<style>body{font-family:-apple-system,'Segoe UI','PingFang SC',sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#f6f7f9;color:#1f2328}
       |.card{text-align:center;padding:44px 52px;border-radius:14px;background:#fff;box-shadow:0 2px 14px rgba(0,0,0,.08)}
       |.icon{color:#07c160;font-size:42px;line-height:1;margin-bottom:10px}h1{font-size:18px;font-weight:600;margin:0 0 8px}
       |p{color:#6a737d;font-size:13px;margin:0;max-width:320px;word-break:break-all}</style></head>
       |<body><div class="card"><div class="icon">✓</div><h1>已退出登录</h1><p>已在浏览器中退出 nebflow 账号，本页可以关闭</p></div></body></html>""".stripMargin

  /** Switch-account continuation notice card (one-window switch, 2026-09-16) —
    * the visible face of a handoff that did NOT continue into a login
    * (replayed / expired / failed to start). Same skeleton, class names and
    * literal values as [[loggedOutPage]] (no new colour literals: `#07c160`
    * and `#d1242f` are the two icon colours already used in this file). */
  private def switchNoticePage(symbol: String, symbolColor: String, headline: String, detail: String): String =
    s"""<!doctype html>
       |<html lang="zh-CN"><head><meta charset="utf-8">
       |<meta name="viewport" content="width=device-width,initial-scale=1">
       |<title>nebflow 切换账号</title>
       |<style>body{font-family:-apple-system,'Segoe UI','PingFang SC',sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#f6f7f9;color:#1f2328}
       |.card{text-align:center;padding:44px 52px;border-radius:14px;background:#fff;box-shadow:0 2px 14px rgba(0,0,0,.08)}
       |.icon{color:$symbolColor;font-size:42px;line-height:1;margin-bottom:10px}h1{font-size:18px;font-weight:600;margin:0 0 8px}
       |p{color:#6a737d;font-size:13px;margin:0;max-width:320px;word-break:break-all}</style></head>
       |<body><div class="card"><div class="icon">$symbol</div><h1>$headline</h1><p>$detail</p></div></body></html>""".stripMargin

  /** HTML response without circe's String-entity hijack (explicit bytes +
    * content type + length). */
  private def htmlResponse(markup: String, status: Status): IO[org.http4s.Response[IO]] =
    val bytes = markup.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    IO.pure(
      org.http4s.Response(status = status)
        .withHeaders(Headers(
          `Content-Type`(MediaType.text.html, Charset.`UTF-8`),
          org.http4s.headers.`Content-Length`.unsafeFromLong(bytes.length.toLong)
        ))
        .withBodyStream(Stream.emits(bytes))
    )

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
              case None      => prodDefaultTargetIO
            }
          case None => prodDefaultTargetIO

  /** Last-resort enrollment target (`Branding.serverUrl`, gated by [[EnrollGuard]]).
    * The refusal is loud, never silent — the log line is the 「零注册出网」的日志面判据。 */
  private def prodDefaultTargetIO: IO[Option[String]] =
    EnrollGuard.prodDefaultTarget match
      case some @ Some(_) => IO.pure(some)
      case None =>
        logger.warn(
          "isolated data root: the production server default is NOT used as an enrollment target " +
            s"(案 b①) — ${EnrollGuard.prodFallbackRefusalReason}",
          "code" -> CredentialFailure.EnrollRefusedIsolatedHome.code
        ).as(None)

end RestApiRoutes

/** 群发路由的 `origin` 闸判据（gmsgsend 批 · 补充卡 §6.5 + §8.1(a)）。
  *
  * 🔴 **纯函数 + 单点**：路由（[[RestApiRoutes.groupSendProxy]]）与 spec 都读这一份
  * 判据，禁两处各写一套（本仓「第二实现」缺陷族）。判据只认**逐字** `"user"`：
  *
  *  - `origin` 缺席 / `null` / `"user"` ⇒ `Right(())`（放行 ⇒ 原文转发，字节零变化：
  *    这三种形态在服务端都是「用户身份」，闸不误伤、不改写）；
  *  - 任何其他值（`"agent"` / 大小写变体 / 非字符串）⇒ `Left(理由)`（路由据此答 400，
  *    **零上游往返**）；
  *  - 体为空 / 非 JSON ⇒ `Right(())`：本层**不复制**服务端的 JSON 校验（禁双实现），
  *    非法体到服务端自然被其校验序拒（400/422）。
  *
  * 大小写变体（`"User"`）**拒绝**而非放行：它不是服务端枚举值（服务端会答 422），
  * 拒绝给出更早、更明确的原因；两条路径都不产生 `origin='agent'` 的落库行。 */
private[gateway] object GroupSendOriginVerdict:

  /** 拒绝理由（对调用方可判读：点名 `origin` + 说明本路由不得设它 + 给出正确做法）。 */
  val RefusalReason: String =
    "This route is the user-identity send path: `origin` is the server's own label and " +
      "cannot be set here — omit it (the server records \"user\" by default)."

  def check(body: String): Either[String, Unit] =
    if body.isBlank then Right(())
    else
      io.circe.parser.parse(body).toOption match
        case None => Right(()) // 非 JSON：交给服务端校验序（本层不复制它）
        case Some(json) =>
          json.hcursor.downField("origin").focus match
            case None                                    => Right(())
            case Some(v) if v.isNull                     => Right(())
            case Some(v) if v.asString.contains("user")  => Right(())
            case Some(_)                                 => Left(RefusalReason)

