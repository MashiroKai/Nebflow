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
import nebflow.core.presets.{ModelPreset, PresetFile, PresetStore}
import nebflow.core.project.{NodeEngine, NodePayload, ProjectRuntimeRegistry, ProjectStore}
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

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // Health check (P2-6 layered, 2026-08-25): `providers` = per-model health
    // (up / down:<reason>), `search` = Tier 2a standalone search API health
    // (unconfigured/up/down) — INDEPENDENT of model health, so "模型配额 DOWN
    // 但搜索 API 正常" is visible at a glance. `status` stays "ok" while the
    // gateway serves (the watchdog keys on HTTP 200).
    case GET -> Root / "health" =>
      for
        modelStates <- sharedResources.healthMonitor.getStates
        searchHealth <- sharedResources.healthMonitor.getSearchHealth
        providers = modelStates.map { case (k, st) =>
          k -> (st match
            case HealthState.Up              => "up"
            case HealthState.Down(reason, _) => s"down: $reason")
        }
        search = searchHealth match
          case SearchApiHealth.Unconfigured => Json.obj("status" -> "unconfigured".asJson)
          case SearchApiHealth.Up           => Json.obj("status" -> "up".asJson)
          case SearchApiHealth.Down(reason, since) =>
            Json.obj("status" -> "down".asJson, "reason" -> reason.asJson, "since" -> since.asJson)
        resp <- Ok(
          Json.obj(
            "product" -> "nebflow".asJson, // single-instance guard identification
            "status" -> "ok".asJson,
            "version" -> nebflow.Version.string.asJson,
            "providers" -> providers.asJson,
            "search" -> search
          )
        )
      yield resp

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

    // TTS 语音合成（无需 auth，内部调用）
    case req @ POST -> Root / "tts" =>
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
    case req @ POST -> Root / "projects" / name / "archive" =>
      withAuth(req) {
        ProjectStore.archive(name).flatMap {
          case Left(err) => NotFound(Json.obj("error" -> err.asJson))
          case Right(at) => Ok(Json.obj("archived" -> true.asJson, "archivedAt" -> at.asJson))
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

    // Return local device info for NebLink discovery probes
    case GET -> Root / "neblink" / "discover" =>
      neblinkService match
        case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
        case Some(ms) =>
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
          cred <- DeviceCredential.load
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
    // GET /neblink/auth/end-session below; the web logout button drives
    // that one. This endpoint stays for API compatibility and scripted use.
    case req @ POST -> Root / "neblink" / "logout" =>
      withNeblink(req) { ms =>
        performLocalLogout(ms) *> Ok(Json.obj("ok" -> true.asJson))
      }

    // RP-initiated logout (OIDC Session Management, RP-logout fix
    // 2026-09-06) — browser navigation endpoint: local teardown FIRST
    // (same steps as POST /neblink/logout above, credential read before
    // it is cleared), then 302 to the provider's end_session_endpoint with
    // the persisted id_token as `id_token_hint` (valid hint = no
    // confirmation page) and the local `/auth/logged-out` landing page as
    // `post_logout_redirect_uri` (ignored by the provider until the uri is
    // allow-listed on the Logto app — probed 2026-09-06, always safe).
    // The browser-side Logto session cookie dies here, so the NEXT login
    // shows the account page instead of silently re-entering the old
    // account.
    //
    // No gateway checkAuth BY DESIGN: this is a browser navigation hop (no
    // Authorization header available) in the standard OIDC RP-logout shape
    // — the credential it acts on is the provider's own session cookie.
    // Worst case abuse = triggering a logout redirect (low risk).
    case GET -> Root / "neblink" / "auth" / "end-session" =>
      neblinkService match
        case None => NotFound(Json.obj("error" -> "NebLink service not initialized".asJson))
        case Some(ms) =>
          for
            logto <- ms.neblinkConfig.map(_.effectiveLogto)
            // Read the hint BEFORE performLocalLogout deletes the file.
            cred <- DeviceCredential.load
            idToken = cred.flatMap(_.logto).flatMap(_.idToken)
            resp <- logto.flatMap(lc => lc.pkceClientId.map(_ => lc)) match
              case Some(lc) =>
                val target = LogtoAuthCode.endSessionUrl(
                  lc.endpoint,
                  idToken,
                  Some(s"http://127.0.0.1:$gatewayPort/auth/logged-out")
                )
                for
                  _ <- performLocalLogout(ms)
                  _ <- logger.info("RP-initiated logout: local teardown done, redirecting to provider end_session")
                  r <- Found(Location(Uri.unsafeFromString(target)))
                yield r
              // Same surface as auth/start: unconfigured provider (or AC app
              // id missing) — the caller falls back to the local-only logout.
              case _ => NotFound(Json.obj("error" -> "logto-not-configured".asJson))
          yield resp

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
                    // The neblink-server URL: read from existing config, or use the
                    // public default. For device flow the server must be reachable
                    // from both the browser (for OAuth) and the device (for polling).
                    serverUrl <- neblinkServerUrl(None)
                    body = Json
                      .obj(
                        "deviceId" -> identity.deviceId.asJson,
                        "deviceName" -> identity.deviceName.asJson,
                        "platform" -> identity.platform.asJson
                      )
                      .noSpaces
                    result <- proxyPost(serverUrl, nebflow.neblink.Protocol.DeviceApi.code, body)
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
                  resolvedUrl <- neblinkServerUrl(serverUrl)
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
              logto <- ms.neblinkConfig.map(_.effectiveLogto)
              resp <- logto match
                case Some(lc) if lc.pkceClientId.isDefined =>
                  val pkceClientId = lc.pkceClientId.getOrElse("")
                  for
                    verifier <- LogtoAuthCode.generateVerifier
                    challenge = LogtoAuthCode.challengeS256(verifier)
                    state <- LogtoAuthCode.generateState
                    _ <- pkceLogin.start(verifier, state)
                    redirectUri = loopbackCallbackUri
                    authorizeUrl = LogtoAuthCode.authorizeUrl(
                      lc.endpoint,
                      pkceClientId,
                      redirectUri,
                      challenge,
                      state,
                      prompt = if forceLogin then "login consent" else "consent",
                      uiLocales = uiLocales
                    )
                    r <- Ok(Json.obj("authorizeUrl" -> authorizeUrl.asJson))
                  yield r
                // Logto unconfigured, or configured without the AC app id —
                // the PKCE login surface treats both as "not configured".
                // (Defensive: effectiveLogto always resolves via the embedded
                // default, so this arm only fires if that invariant changes.)
                case _ => NotFound(Json.obj("error" -> "logto-not-configured".asJson))
            yield resp

    // Login-state poll for the frontend (pending while the hosted page is
    // open; success/error sticky until the next start).
    case req @ GET -> Root / "neblink" / "auth" / "state" =>
      if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
      else pkceLogin.statusJson.flatMap(Ok(_))

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
              case Left(err) => Ok(Json.obj("ok" -> false.asJson, "error" -> err.asJson))
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
              doRemoteUpdate(ns, targetDevice, beta).flatMap {
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

    /** 发消息给好友（用户身份——UI 输入框直发，无 agent 权限档位）。body: {body} */
    case req @ POST -> Root / "friends" / friendUserId / "messages" =>
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            req.as[Json].flatMap { body =>
              val text = body.hcursor.downField("body").as[String].getOrElse("")
              if text.isEmpty then BadRequest(Json.obj("error" -> "Missing body".asJson))
              else fs.sendAsUser(friendUserId, text).flatMap(friendResult)
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

    /** POST /api/groups/{groupId}/messages —— **冻结群发契约**（`groups.rs:364-524`）。
      * 校验序服务端冻结（auth → 群存在且未解散 → 成员 → 长度 → 限速 → origin →
      * 附件），本层**不复制**任何一条判定（禁双实现）。上游 201 SendMessageResponse
      * 同形 / 404 `group_not_found` / 403 `group_disbanded` / 403 `not_member` /
      * 422 `invalid_length` / 422 `invalid_origin` / 429 `rate_limited`。 */
    case req @ POST -> Root / "groups" / groupId / "messages" =>
      groupProxy(req, "POST", s"/api/groups/${encSeg(groupId)}/messages")

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
    *  - 其余上游错误 ⇒ **502**（不变）。
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

  /** Shared remote-update logic: P2P HTTP first, relay fallback. Used by REST + WS handlers. */
  private def doRemoteUpdate(
    ns: nebflow.neblink.NeblinkService,
    targetDevice: String,
    beta: Boolean
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
                val body = Json.obj("beta" -> beta.asJson).noSpaces
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
   * The peer's device info arrives as query params on the WS upgrade request.
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
          val peerDeviceId = req.params.getOrElse("deviceId", "")
          // Trust: IP list (primary) or device-ID network membership (fallback).
          // peerDeviceId is already a query param on the WS upgrade, so the
          // fallback needs no extra header here.
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

    // POST /api/flow/event — Source scripts inject external events (no auth, local only)
    case req @ POST -> Root / "flow" / "event" =>
      req.as[Json].flatMap { body =>
        val sessionId = body.hcursor.downField("sessionId").as[String].getOrElse("")
        val eventType = body.hcursor.downField("type").as[String].getOrElse("")
        val data = body.hcursor.downField("data").as[JsonObject].getOrElse(JsonObject.empty)

        if sessionId.isEmpty || eventType.isEmpty then
          BadRequest(Json.obj("error" -> "Missing 'sessionId' or 'type'".asJson))
        else
          FlowTreeRegistry.get(sessionId).flatMap {
            case Some(_) =>
              // EventFired removed — pipelines don't subscribe to external events
              Ok(Json.obj("status" -> "ok".asJson, "event" -> eventType.asJson))
            case None =>
              NotFound(Json.obj("error" -> s"No FlowTree for session '$sessionId'".asJson))
          }
      }

    // GET /teams/mounted — return all mounted teams with agent status
    // NOTE: Router mounts this under /api prefix, so full path is /api/teams/mounted
    case GET -> Root / "teams" / "mounted" =>
      for
        _ <- nebflow.core.flow.FlowTreeRegistry.awaitRestore(3000L)
        teamsJson <- buildMountedTeamsJson()
        result <- Ok(Json.obj("teams" -> teamsJson))
      yield result

    // GET /running-flows — list currently running DAG flow instances
    case GET -> Root / "running-flows" =>
      nebflow.core.flow.RunningFlowRegistry.listJson.flatMap(json => Ok(json))

    // GET /flow/dag/:name — return flow DAG structure (nodes, edges, routing)
    case GET -> Root / "flow" / "dag" / flowName =>
      if !isValidFlowName(flowName) then BadRequest(Json.obj("error" -> "Invalid flow name".asJson))
      else
        nebflow.core.entity.EntityLoader.loadFlow(flowName).flatMap {
          case Some(dag) =>
            val nodesJson = dag.nodes.map { (nodeId, node) =>
              val route: Json = node.onComplete match
                case nebflow.core.entity.NodeRoute.Goto(t) => Json.fromString(t)
                case nebflow.core.entity.NodeRoute.Return => Json.fromString("$return")
                case p: nebflow.core.entity.NodeRoute.Parallel =>
                  Json.obj(
                    "parallel" -> p.fan.asJson,
                    "onFail" -> (p.onFail match
                      case nebflow.core.entity.NodeRoute.OnFailMode.Collect => Json.fromString("collect")
                      case _ => Json.fromString("abort")
                    )
                  )
                case p: nebflow.core.entity.NodeRoute.ParallelDynamic =>
                  Json.obj(
                    "parallel" -> Json.obj(
                      "slots" -> Json.fromString(p.slotField),
                      "template" -> Json.fromString(p.template)
                    ),
                    "onFail" -> (p.onFail match
                      case nebflow.core.entity.NodeRoute.OnFailMode.Collect => Json.fromString("collect")
                      case _ => Json.fromString("abort")
                    )
                  )
                case nebflow.core.entity.NodeRoute.Switch(expr, cases, _, _) =>
                  val casesObj = io.circe.JsonObject.fromIterable(cases.map { (k, v) =>
                    val target: String = v match
                      case nebflow.core.entity.NodeRoute.Goto(t) => t
                      case nebflow.core.entity.NodeRoute.Return => "$return"
                      case _ => "?"
                    k -> Json.fromString(target)
                  })
                  Json.obj("switch" -> Json.fromString(expr), "cases" -> casesObj.asJson)
              nodeId -> Json.obj(
                "agent" -> node.agent.asJson,
                "input" -> node.input.asJson,
                "onComplete" -> route,
                "onError" -> node.onError.map(_.toString.toLowerCase).asJson,
                "maxRetries" -> node.maxRetries.asJson
              )
            }
            Ok(
              Json.obj(
                "name" -> dag.name.asJson,
                "description" -> dag.description.asJson,
                "entry" -> dag.entry.asJson,
                "maxLoop" -> dag.maxLoop.asJson,
                "nodes" -> nodesJson.asJson
              )
            )
          case None =>
            NotFound(Json.obj("error" -> s"Flow '$flowName' not found".asJson))
        }

    // GET /teams — list all defined teams (from team.json files)
    case GET -> Root / "teams" =>
      for
        teams <- nebflow.core.entity.EntityLoader.listTeams()
        teamsJson = teams.values.toList.sortBy(_.name).map { t =>
          io.circe.Json.obj(
            "name" -> t.name.asJson,
            "description" -> t.description.asJson,
            "lead" -> t.lead.asJson,
            "members" -> t.members.asJson
          )
        }
        result <- Ok(io.circe.Json.obj("teams" -> teamsJson.asJson))
      yield result

    // GET /teams/status/:sessionId — return mounted teams and agent status for frontend
    // NOTE: Router mounts this under /api prefix, so full path is /api/teams/status/:sessionId
    case GET -> Root / "teams" / "status" / sessionId =>
      // Validate sessionId: alphanumerics, dot, underscore, hyphen (covers UUID
      // and flow-node ids like dag-git-merge-scanner-405090), no path traversal
      if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
        BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
      else
        for
          teamsJson <- buildMountedTeamsJson()
          result <- Ok(Json.obj("sessionId" -> sessionId.asJson, "teams" -> teamsJson))
        yield result

    // GET /teams/mailbox/:sessionId/:teamName — mail history for a team
    case GET -> Root / "teams" / "mailbox" / sessionId / flowName =>
      if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
        BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
      else
        for
          records <- nebflow.core.flow.FlowMailStore.load(sessionId, flowName)
          result <- Ok(Json.obj("records" -> records.asJson))
        yield result

    // DELETE /teams/mailbox/:sessionId/:teamName — clear mail history
    case DELETE -> Root / "teams" / "mailbox" / sessionId / flowName =>
      if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
        BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
      else
        for
          _ <- nebflow.core.flow.FlowMailStore.clear(sessionId, flowName)
          result <- Ok(Json.obj("cleared" -> true.asJson))
        yield result

    // GET /teams/mail-queue/:sessionId — pending queue mails
    case GET -> Root / "teams" / "mail-queue" / sessionId =>
      if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
        BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
      else
        for
          items <- nebflow.core.flow.MailQueueStore.load(sessionId)
          result <- Ok(Json.obj("items" -> items.asJson))
        yield result

    // DELETE /teams/mail-queue/:sessionId/:itemId — cancel a pending queue mail
    case DELETE -> Root / "teams" / "mail-queue" / sessionId / itemId =>
      if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
        BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
      else
        for
          remaining <- nebflow.core.flow.MailQueueStore.removeById(sessionId, itemId)
          result <- Ok(Json.obj("items" -> remaining.asJson))
        yield result

    // ===== Flow Editor APIs =====

    // GET /teams/def/:name — return team definition for the editor
    case GET -> Root / "teams" / "def" / flowName =>
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

    // GET /agents/list — list all global agents (for extends dropdown)
    // GET /agents/list — list all agents (all three layers)
    case GET -> Root / "agents" / "list" =>
      for
        globalAgents <- EntityLoader.listAgents()
        teams <- EntityLoader.listTeams()
        teamAgentEntries <- teams.toList.traverse { (teamName, _) =>
          IO.blocking {
            val dir = PathUtil.dataRoot / "teams" / teamName / "agents"
            if os.exists(dir) then
              os.list(dir)
                .filter(os.isDir)
                .flatMap(d => EntityLoader.loadAgentFromDir(d))
                .toList
            else Nil
          }
        }
        flows <- EntityLoader.listFlows()
        flowAgentEntries <- flows.toList.traverse { (flowName, _) =>
          IO.blocking {
            val dir = PathUtil.dataRoot / "flows" / flowName / "agents"
            if os.exists(dir) then
              os.list(dir)
                .filter(os.isDir)
                .flatMap(d => EntityLoader.loadAgentFromDir(d))
                .toList
            else Nil
          }
        }
        globalList = globalAgents.values.map { a =>
          Json.obj(
            "name" -> a.name.asJson,
            "description" -> a.description.asJson,
            "tools" -> a.tools.asJson,
            "displayName" -> a.name.asJson,
            "systemPrompt" -> a.systemPrompt.asJson,
            "category" -> a.category.asJson
          )
        }
        teamList = teamAgentEntries.flatten.map { a =>
          Json.obj(
            "name" -> a.name.asJson,
            "description" -> a.description.asJson,
            "tools" -> a.tools.asJson,
            "displayName" -> a.name.asJson,
            "systemPrompt" -> a.systemPrompt.asJson,
            "category" -> "team".asJson
          )
        }
        flowList = flowAgentEntries.flatten.map { a =>
          Json.obj(
            "name" -> a.name.asJson,
            "description" -> a.description.asJson,
            "tools" -> a.tools.asJson,
            "displayName" -> a.name.asJson,
            "systemPrompt" -> a.systemPrompt.asJson,
            "category" -> "flow".asJson
          )
        }
        all = globalList ++ teamList ++ flowList
        result <- Ok(Json.obj("agents" -> all.asJson))
      yield result

    // GET /agents/:name — get agent detail (system.md + tools) — searches all three layers
    case GET -> Root / "agents" / agentName =>
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

    // GET /agents/:name/model — get agent's model configuration — searches all three layers
    case GET -> Root / "agents" / agentName / "model" =>
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

    // PUT /agents/:name/model — update agent's model configuration
    case req @ PUT -> Root / "agents" / agentName / "model" =>
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

    // PUT /agents/:name/preset — set or remove the agent's preset reference.
    // Body: {"preset": "vision"} or {"preset": null} (removes the field, falls
    // back to default preset). Uses EntityLoader.findAgentDir to locate the
    // agent.json across all three layers.
    case req @ PUT -> Root / "agents" / agentName / "preset" =>
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

    // ===== Entity API (Team/Flow/Agent management) =====

    // PUT /agents/:name — RETIRED 2026-09-06 (tool-face batch): the skills/
    // flows write-back is closed. Loud 410 so any stale client sees the
    // retirement instead of assuming the edit landed. agent.json skills/flows
    // declarations are still PARSED (decision A① — legacy grants stay live
    // until stage 3); only this write path is retired.
    case PUT -> Root / "agents" / agentName =>
      if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
      else
        logger.warn(s"Rejected PUT /agents/$agentName — skills/flows write-back retired 2026-09-06 (stage 2d tool-face batch)")
        Gone(Json.obj("error" -> "agent skills/flows write-back retired 2026-09-06: per-agent capability config is definition/plugin-managed; agent.json is no longer written from the panel".asJson))

    // GET /skills — list all available skills (name + description) for the Agent panel
    case GET -> Root / "skills" =>
      for
        skills <- SkillService.listSkills()
        entries = skills.sortBy(_.name).map { s =>
          Json.obj(
            "name" -> s.name.asJson,
            "description" -> s.description.asJson
          )
        }
        result <- Ok(Json.obj("skills" -> entries.asJson))
      yield result

    // ── Plugins（阶段 2b §B.3：面板审批清单 + CLI 对等）─────────────

    // GET /plugins — 注册表全量（含已封禁项 / 拒载原因 + 清单数据；无审批批 2026-09-13
    // 后条目面无「待审」形态，受信与否 = 在位 ∧ 未被封禁）。
    // 审批清单区块（§B.3）：元信息 / skills 摘要（前 20 行）/ mcp（env 只出键名，
    // 值打码）/ org.nebflow/tools 申请 / 信任状态与 digest。
    case GET -> Root / "plugins" =>
      for
        (plugins, rejected) <- nebflow.core.plugin.PluginRegistry.listWithRejected()
        entries = plugins.sortBy(_.name).map(nebflow.core.plugin.PluginRegistry.approvalManifest)
        rejectedEntries = rejected.sortBy(_._1).map { case (n, r) => Json.obj("name" -> n.asJson, "reason" -> r.asJson) }
        result <- Ok(Json.obj("plugins" -> entries.asJson, "rejected" -> rejectedEntries.asJson))
      yield result

    // GET /plugins/catalog — 分发器目录段同源（trusted only；前端调试/预览用）
    case GET -> Root / "plugins" / "catalog" =>
      nebflow.core.plugin.PluginRegistry.renderCatalog().flatMap { catalog =>
        Ok(Json.obj("catalog" -> catalog.asJson))
      }

    // POST /plugins/:name/approve — **兼容保留**（无审批批 2026-09-13 起 UI 主线不再调用）：
    // 写一条审批审计记录（digest + 逐文件快照）到 `plugins.trust`。⚠️ 该记录**不再决定
    // 装载**（在位即信任），但它是 seed 覆盖的**仲裁基准**（`trustRecordDigest`）⇒ 手动
    // approve 一个用户改过的默认集包会让下次 boot 的种子镜像覆盖视为「干净」。
    // 名字段白名单校验（拒绝路径穿越形态）。
    case POST -> Root / "plugins" / name / "approve" =>
      if !isValidAgentName(name) then BadRequest(Json.obj("error" -> "Invalid plugin name".asJson))
      else
        nebflow.core.plugin.PluginRegistry.approve(name).flatMap {
          case Right(msg) => Ok(Json.obj("ok" -> true.asJson, "message" -> msg.asJson))
          case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
        }

    // POST /plugins/:name/revoke — **封禁**（deny-list；2026-09-13 无审批批语义变更）：
    // 写 `plugins.revoked.<name> = {at, by, reason}`（**独立命名空间**，不被任何 approve
    // 抹掉）⇒ ① 不进分发器目录 ② 闸 A/B/C/E 拒（resolve Left）③ 在飞 MCP 下个 30s
    // 重验 tick 停掉。路径名保留（零迁移），语义从「撤回审批」翻转为「点名封禁」。
    // body（可选）：{"reason": "…"}。**解封**走下方 /unblock。
    case req @ POST -> Root / "plugins" / name / "revoke" =>
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

    // POST /plugins/:name/unblock — **解封**（2026-09-13 新增）：删 `plugins.revoked.<name>`
    // ⇒ 回落「在位即信任」（目录/派发/装载恢复；已停的在飞 MCP 需重新派发才回来）。
    case POST -> Root / "plugins" / name / "unblock" =>
      if !isValidAgentName(name) then BadRequest(Json.obj("error" -> "Invalid plugin name".asJson))
      else
        nebflow.core.plugin.PluginBlockPolicy.unblock(name, "panel/rest").flatMap {
          case Right(_) =>
            Ok(Json.obj("ok" -> true.asJson,
              "message" -> (s"Plugin '$name' unblocked — back to presence trust: it re-enters the catalog and is " +
                "available for new dispatches on the next scan.").asJson))
          case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
        }

    // POST /plugins/:name/disable | /enable — **令 1 派发开关**（2026-09-12）：
    // 只写 `plugins.dispatch.<name>.authorEnabled`（durable 作者意图层）⇒ **只影响
    // 未来派发**：新节点拿不到该插件（闸 A 拒），已在飞/已派发节点**零影响**
    // （闸 B/C/E/D 只判内容面），已注入的 `<injected-plugins>` 提示词全文与审计
    // 留存不变。不放宽内容面（未受信的包此路依然无效）。
    case POST -> Root / "plugins" / name / "disable" =>
      dispatchSwitch(name, enable = false)
    case POST -> Root / "plugins" / name / "enable" =>
      dispatchSwitch(name, enable = true)

    // POST /plugins/:name/dispatch/grant — **过渡期临时派发授权**（设计 R8：
    // 过渡只能**放宽**、带 TTL 自动失效、不污染作者意图）。body（可选）：
    // {"ttlSecs": 1800, "refs": ["n-…"], "reason": "…"}。缺省 ttlSecs=1800。
    case req @ POST -> Root / "plugins" / name / "dispatch" / "grant" =>
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

    // POST /plugins/:name/dispatch/clear — 显式结束过渡（幂等；有效值回落作者意图）。
    case POST -> Root / "plugins" / name / "dispatch" / "clear" =>
      if !isValidAgentName(name) then BadRequest(Json.obj("error" -> "Invalid plugin name".asJson))
      else
        nebflow.core.plugin.PluginDispatchPolicy.clearTransition(name, "rest").flatMap {
          case Right(_) =>
            Ok(Json.obj("ok" -> true.asJson,
              "message" -> s"Plugin '$name' transition cleared — effective dispatch permission falls back to the author's intent".asJson))
          case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
        }

    // GET /flows/list — list all flow definitions (name, description, node count, maxLoop)
    case GET -> Root / "flows" / "list" =>
      for
        flows <- EntityLoader.listFlows()
        entries = flows.values.toList.sortBy(_.name).map { f =>
          val edges = f.nodes.toList.sortBy(_._1).flatMap { (nodeId, node) =>
            node.onComplete match
              case NodeRoute.Goto(target) => List((nodeId, target, None))
              case NodeRoute.Return => List((nodeId, "$return", None))
              case p: NodeRoute.Parallel => p.fan.map(t => (nodeId, t, None))
              case p: NodeRoute.ParallelDynamic => List((nodeId, p.template, None))
              case NodeRoute.Switch(_, cases, _, _) =>
                cases.toList.map { (cond, route) =>
                  route match
                    case NodeRoute.Goto(t)     => List((nodeId, t, Some(cond)))
                    case NodeRoute.Return      => List((nodeId, "$return", Some(cond)))
                    case p: NodeRoute.Parallel => p.fan.map(t => (nodeId, t, Some(cond)))
                    case p: NodeRoute.ParallelDynamic => List((nodeId, p.template, Some(cond)))
                    case _                     => List((nodeId, "?", Some(cond)))
                }.flatten
          }
          Json.obj(
            "name" -> f.name.asJson,
            "description" -> f.description.asJson,
            "entry" -> f.entry.asJson,
            "maxLoop" -> f.maxLoop.asJson,
            "nodeCount" -> f.nodes.size.asJson,
            "nodes" -> f.nodes.toList
              .sortBy(_._1)
              .map { (nodeId, node) =>
                Json.obj(
                  "nodeId" -> nodeId.asJson,
                  "agent" -> node.agent.asJson
                )
              }
              .asJson,
            "edges" -> edges.map { (from, to, cond) =>
              Json.obj("from" -> from.asJson, "to" -> to.asJson, "condition" -> cond.asJson)
            }.asJson
          )
        }
        result <- Ok(Json.obj("flows" -> entries.asJson))
      yield result

    // GET /teams/:name — team detail
    case GET -> Root / "teams" / teamName =>
      if !isValidAgentName(teamName) then BadRequest(Json.obj("error" -> "Invalid team name".asJson))
      else
        for
          teamOpt <- EntityLoader.loadTeam(teamName)
          result <- teamOpt match
            case None => NotFound(Json.obj("error" -> s"Team '$teamName' not found".asJson))
            case Some(team) =>
              Ok(
                Json.obj(
                  "name" -> team.name.asJson,
                  "description" -> team.description.asJson,
                  "lead" -> team.lead.asJson,
                  "members" -> team.members.asJson
                )
              )
        yield result

    // GET /team/rules/:name — read team rules.md
    case GET -> Root / "team" / "rules" / teamName =>
      if !isValidAgentName(teamName) then BadRequest(Json.obj("error" -> "Invalid team name".asJson))
      else
        for
          rules <- EntityLoader.loadTeamRules(teamName)
          result <- Ok(Json.obj("content" -> rules.asJson))
        yield result

    // POST /team/rules/:name — save team rules.md + trigger reload
    case req @ POST -> Root / "team" / "rules" / teamName =>
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

    // GET /entity-agents — list all agents (entity format, with useWhen)
    case GET -> Root / "entity-agents" =>
      for
        agents <- EntityLoader.listAgents()
        entries = agents.values.toList.sortBy(_.name).map { a =>
          Json.obj(
            "name" -> a.name.asJson,
            "description" -> a.description.asJson,
            "useWhen" -> a.useWhen.asJson,
            "tools" -> a.tools.asJson,
            "voice" -> a.voice.asJson,
            "category" -> a.category.asJson
          )
        }
        result <- Ok(Json.obj("agents" -> entries.asJson))
      yield result

    // ===== Model Presets =====

    // GET /presets — list all presets + default name + agent references
    case GET -> Root / "presets" =>
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

    // POST /presets — create a new preset (409 on duplicate name)
    case req @ POST -> Root / "presets" =>
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

    // PUT /presets/default — set the default preset (must exist)
    case req @ PUT -> Root / "presets" / "default" =>
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

    // PUT /presets/:name — update an existing preset (name immutable)
    case req @ PUT -> Root / "presets" / presetName =>
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

    // DELETE /presets/:name — delete a preset (409 if default; scrub agent refs)
    case DELETE -> Root / "presets" / presetName =>
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

    // POST /presets/migrate-legacy — migrate per-agent model configs to presets
    // Body: {"agentNames": ["Coder", "qa-frontend", ...]}
    case req @ POST -> Root / "presets" / "migrate-legacy" =>
      req.as[Json].flatMap { body =>
        val agentNames = body.hcursor.downField("agentNames").as[List[String]].getOrElse(Nil)
        if agentNames.isEmpty then BadRequest(Json.obj("error" -> "Missing or empty agentNames".asJson))
        else migrateLegacyModels(agentNames)
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
   * deviceId we discovered via the NebLink Server (a member of our networkId)
   * AND the request originates from a private/LAN address.
   */
  private def isKnownNetworkDevice(
    ms: NeblinkService,
    claimedDeviceId: String,
    remoteIp: String
  ): IO[Boolean] =
    if claimedDeviceId.isEmpty || !isPrivateLanIp(remoteIp) then IO.pure(false)
    else ms.peers.map(_.exists(_.deviceId == claimedDeviceId))

  /** RFC1918 private ranges + loopback + link-local. P2P-direct is LAN-only. */
  private def isPrivateLanIp(rawIp: String): Boolean =
    val ip = rawIp.stripPrefix("::ffff:")
    ip == "127.0.0.1" || ip == "::1" ||
    ip.startsWith("10.") ||
    ip.startsWith("192.168.") ||
    ip.startsWith("169.254.") ||
    ip.startsWith("fe80:") ||
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

  def authCallbackRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "callback" =>
      handleAuthCallback(req.uri.query.params)
    // RP-logout return target (2026-09-06): the provider lands here when
    // the post_logout_redirect_uri is accepted (allow-listed on the Logto
    // app). Until then the provider shows its own default logged-out page —
    // same UX, different host.
    case GET -> Root / "logged-out" =>
      htmlResponse(loggedOutPage, Status.Ok)
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
    LogtoAuthCode.parseCallbackError(query) match
      case Some(cb) =>
        val msg = s"${cb.error}${cb.description.fold("")(d => s": $d")}"
        pkceLogin.fail(msg) *> htmlResponse(callbackPage(ok = false, msg), Status.BadRequest)
      case None =>
        val code = query.getOrElse("code", "")
        val state = query.getOrElse("state", "")
        pkceLogin.take(state).flatMap {
          // None = mismatch / expired / stray hit (take already set the
          // error status for pending attempts).
          case None =>
            htmlResponse(
              callbackPage(ok = false, "登录回调校验失败（state 不匹配或已过期）"),
              Status.BadRequest
            )
          case Some(verifier) =>
            neblinkService match
              case None =>
                pkceLogin.fail("NebLink service not initialized") *>
                  htmlResponse(callbackPage(ok = false, "nebflow 服务未初始化"), Status.InternalServerError)
              case Some(ms) =>
                for
                  // Same resolution as /api/neblink/auth/start: explicit logto
                  // block wins, otherwise the embedded production default.
                  // The raw `_.logto` read here previously broke the PKCE
                  // chain for fresh installs (start succeeded via the
                  // embedded default, then the callback died with a
                  // misleading "Logto 登录未配置" — 2026-08-30).
                  logto <- ms.neblinkConfig.map(_.effectiveLogto)
                  serverUrl <- neblinkServerUrl(None)
                  resp <- logto.flatMap(lc => lc.pkceClientId.map(pkce => (lc, pkce))) match
                    case Some((lc, pkceClientId)) =>
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
                                        case Right(Left(err)) =>
                                          val detail = enrollFailureDetail(serverUrl, err)
                                          pkceLogin.fail(detail) *>
                                            htmlResponse(callbackPage(ok = false, detail), Status.BadGateway)
                                        case Left(e) =>
                                          pkceLogin.fail(Option(e.getMessage).getOrElse("enrollment error")) *>
                                            htmlResponse(
                                              callbackPage(ok = false, Option(e.getMessage).getOrElse("登录处理失败")),
                                              Status.InternalServerError
                                            )
                                      }
                                  case Left(err) =>
                                    pkceLogin.fail(s"register: $err") *>
                                      htmlResponse(callbackPage(ok = false, s"设备注册失败：$err"), Status.BadGateway)
                                }
                            }
                          case Left(err) =>
                            pkceLogin.fail(s"token: $err") *>
                              htmlResponse(callbackPage(ok = false, s"登录令牌交换失败：$err"), Status.BadRequest)
                        }
                    case _ =>
                      // With effectiveLogto this only fires when an explicit
                      // logto block exists but lacks pkceClientId (the
                      // embedded default carries one; a missing block falls
                      // back to it). Name the actual misconfiguration.
                      pkceLogin.fail("logto-pkce-client-not-configured") *>
                        htmlResponse(
                          callbackPage(ok = false, "Logto PKCE 未配置：logto 段缺少 pkceClientId"),
                          Status.NotFound
                        )
                yield resp
        }

  /** 案 C ①(a)（2026-09-14）：回调页 / 面板的失败文案 = **失败原文照实透出**，不再压成
    * 泛化的「设备注册未完成」（修前 `:3576` 把 Left 丢掉、`/auth/state` 只外泄固定串
    * `"Enrollment failed"`，用户无法判读真因）。
    *
    * 当且仅当该原文**就是**当前隔离护栏的拒绝文本时前置一句说明——判据是逐字符相等
    * （护栏 reason 是 `(serverUrl, 开关)` 的纯函数，同输入同输出），因此非护栏失败
    * （网络 / 服务端 / 缺 deviceToken）绝不会被贴上护栏标签。 */
  private def enrollFailureDetail(serverUrl: String, err: String): String =
    nebflow.neblink.EnrollGuard.enrollRefusal(serverUrl) match
      case Some(reason) if reason == err =>
        s"本机为隔离数据根，入网被本地隔离护栏拒绝（非网络故障）：$err"
      case _ => err

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
    * Same visual skeleton as callbackPage — a static result card. */
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
   * 3. The public default URL.
   */
  private def neblinkServerUrl(explicit: Option[String] = None): IO[String] =
    explicit match
      case Some(url) => IO.pure(url)
      case None =>
        neblinkService match
          case Some(ms) =>
            ms.neblinkConfig.map(_.neblinkServer.map(_.url)).flatMap {
              case Some(url) => IO.pure(url)
              case None => IO.pure(Branding.serverUrl)
            }
          case None => IO.pure(Branding.serverUrl)

end RestApiRoutes
