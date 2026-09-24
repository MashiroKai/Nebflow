/* 从 RestApiRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.*
import nebflow.core.project.*
import nebflow.core.tools.NodeTools
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

/**
 * 项目域(projects,F 步 2026-09-24):原 RestApiRoutes.routes 巨型 match 的
 * /projects 全族 case(列表/归档/flow-map 快照/归档批次/节点结果全文/
 * agent.md 读写)逐字迁入,行为保持;经 RestApiRoutes.routes 级联挂载,
 * 各臂首段路径字面量与其余域互不重叠。
 */
private[gateway] object ProjectsRoutes:

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    HttpRoutes.of[IO] {
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

    }
  end routes

end ProjectsRoutes
