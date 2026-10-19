package nebflow.core.project

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.delegate.{ChainStore, DelegateRegistry, MergeQueue}
import nebflow.shared.PathUtil

/**
 * ChainsReadFace —— **per-task 只读读面**（N1 刀3 · 设计 v3 §3 A8 / §9；#1228 stub
 * §四.3「per-task 读面」的唯一硬依赖落位）。两条 REST 臂（挂载见 PresenceRoutes
 * `/api/chains*`，本件只做投影、零 HTTP 语义）：
 *
 *   - [[rowsJson]]（`GET /api/chains`）：任务行投影 = [[DelegateRegistry]] 持久记录
 *     全量 × agentRegistry **活性提示**（stub §四.4「live.status 提示位 = agentRegistry
 *     快照投影，A8 同面携带」）× 最新 `terminal-verdict` 判词行（v3 §9 逐字：
 *     「ready-to-merge 由 terminal-verdict 判词行供给——#1224 既有，不新增映射权威」；
 *     其余状态仍由前端既有适配层 rowFromRegistry 的 reportStatus/live 推导，本面不
 *     抢权威）。行 JSON = Record 原样编码 + 可选 `live` / `verdict` 附加键——**接口
 *     形状（read port / setSource）零改动**，字段差异由前端适配层吸收（v3 §9）。
 *   - [[chainJson]]（`GET /api/chains/:key`）：键 = ChainStore.keyFor 口径（台账号
 *     优先，delegateId 回落）。响应 = `record`（[[ChainStore]] 三行族原样 JSON——前端
 *     既有 chainFromStoreRecord 适配层的直接输入）+ `chain`（delegate 作用域
 *     **FlowMap 衍生投影**：已挂载宿主现读；未挂载 = 盘面探针式 open 只读——崩溃恢复
 * 「探针式 open 幂等收敛」同款；作用域目录不存在（外部执行器/从未建图）= null，
 *     视图降级为三行族投影）。节点态映射 = v3 §9 逐字表（[[rowStatusOf]] 单点）：
 *     pending/wiring→pending、running/interrupted→running、completed/blocked/failed/
 *     cancelled 直映；ChainView 形（taskId/delegateId/nodes/worktree/verdict/
 *     mergeQueue/frames）不变。
 *
 * == 纪律 ==
 *  - **只读**：本件零写盘（探针 open 的目录已存在性先行检查，防无目录副作用建目录；
 *    openScope 对已存在目录的 ledger 载入幂等——ProjectCrashRecovery 探针先例）。
 *  - **零第二权威**：节点投影只取活动区快照（归档成员的终态故事由三行族 verdict/
 *    spawn 投影承载）；不合成 chain-registered/chain-node-state 帧行（候定集透传
 *    纪律不破——前端禁造第二权威，后端同守）。
 *  - **fail-soft**：单行/单键坏形只降级该行（WARN），绝不让整面 500。
 */

object ChainsReadFace:

  /**
   * 任务行投影（rows 臂）。`registry` = agentRegistry 快照（活性提示单源）。
   * `taskStatuses`（#566 修复批 · 行终态权威）：delegateId → 引擎任务行终态
   * （SubAgentTaskStore 值域：running/completed/failed/cancelled/restarting/
   * ready-to-merge）——delegate 终态后 reportStatus/live 双缺席时前端回落 running
   * 渲染的陈旧行根因修补（行必须在 UI 反映终态）；由调用方批量供给
   * （findAllTasks 单遍扫），缺席 = 旧投影（字段缺键，前端回落既有推导）。
   * `archived`（delegate-ui 批 2026-10-08 · G6 历史窗）：`false`（缺省）= 在册窗
   * （`archivedAt` 缺席的记录）；`true` = 历史窗（`?archived=1`，只出已归档）——
   * 两窗互补零重叠，归档旗零删除（记录本体原样）。
   *
   * 🔴 现役窗**世界座位闸**（面板退役台账泄漏微批 2026-10-18，#506）：在册窗只投影
   * 座位（[[seatOf]]）落在本实例世界（[[activeWorldSeats]]：dataRoot 子树 ∪ 各未归档/
   * 未卸载项目定义的 workspace 子树）的记录——registry 是实例级共享地址簿，隔离实例
   * （coldstart 腿，座位 `/tmp/nebflow-coldstart/general`）的遗留记录也会落进来；它们
   * 的 taskNo/title 是**退役台账**派发时刻的快照（同号不同物实证：#33-#86 vs 现役 v2
   * 台账），reportStatus 缺席 ⇒ 前端按 running 渲染 ⇒「阻塞/进行中」旧任务混进现役
   * 面板。历史窗（archived=true）**零过滤**——归档抽屉是记录的全量查看窗，零删除
   * 纪律不破。世界解析失败 = fail-open（不过滤，宁见旧勿丢现役——显示面不因读盘抖动
   * 清空）。
   */
  def rowsJson(
    registry: Map[String, nebflow.actor.AgentRecord],
    archived: Boolean = false,
    taskStatuses: Map[String, String] = Map.empty
  ): IO[Json] =
    IO.blocking(DelegateRegistry.list()).flatMap { records =>
      val scoped = records.filter(rec => rec.archivedAt.isDefined == archived)
      val windowed: IO[List[DelegateRegistry.Record]] =
        if archived then IO.pure(scoped) // 历史窗：全量（零删除展示纪律）
        else activeWorldSeatFilter(scoped)
      windowed.flatMap(_.traverse(rowJson(registry, taskStatuses))).map(rows => Json.obj("rows" -> rows.asJson))
    }

  /** 记录的「座位」：项目 workspace 优先，回落派发 cwd（scopeProjection 同款取序）。 */
  private[project] def seatOf(rec: DelegateRegistry.Record): String =
    rec.projectWorkspace.getOrElse(rec.cwd)

  /** 现役窗世界闸（fail-open：世界解析失败 = 不过滤）。 */
  private def activeWorldSeatFilter(records: List[DelegateRegistry.Record]): IO[List[DelegateRegistry.Record]] =
    activeWorldSeats.map {
      case None => records
      case Some(world) => records.filter(rec => inActiveWorld(world, seatOf(rec)))
    }

  /**
   * 本实例「现役世界」座位集：dataRoot 本身（缺省 general 座位与其余引擎侧工作区）∪
   * 各未归档/未卸载项目定义的 workspace（挂载单源 = [[ProjectStore.list]]，与面板项目
   * 列表/启动挂载同源）。读盘失败 = `None`（调用方 fail-open）。
   */
  private def activeWorldSeats: IO[Option[Set[String]]] =
    ProjectStore.list().attempt.map {
      case Right(defs) => Some((PathUtil.dataRoot.toString +: defs.map(_.workspace)).toSet)
      case Left(e) =>
        nebflow.shared.NebflowLogger
          .forName("nebflow.core.project.chains-read-face")
          .warnSync(s"chains rows: active-world resolution failed — showing unscoped rows: ${e.getMessage}")
        None
    }

  /**
   * 座位 ∈ 世界：canonical 化后的相等/子树判定（symlink 稳——`Nebflow → Stand` 改名
   * 后旧记录 cwd 仍走旧名实证）。canonical 化 = [[canonicalPath]]（确定性解析：现存
   * 最深祖先 `toRealPath`（symlink 全解）+ 不存在尾巴词面回接——JDK `getCanonicalPath`
   * 对「新建 symlink + 不存在尾巴」的解析有未定义态，弃用）。空座位恒 false（保守
   * 剔除，cwd 陷阱不进判定）。
   */
  private[project] def inActiveWorld(world: Set[String], seat: String): Boolean =
    if seat.trim.isEmpty then false
    else
      val seatCanon = canonicalPath(seat)
      world.exists { root =>
        root.trim.nonEmpty && {
          val rootCanon = canonicalPath(root)
          seatCanon == rootCanon || seatCanon.startsWith(rootCanon + java.io.File.separator)
        }
      }

  /** 确定性 canonical：现存最深祖先 `toRealPath`（symlink 全解）+ 不存在尾巴词面回接。 */
  private def canonicalPath(path: String): String =
    try
      val p = java.nio.file.Paths.get(path)
      var anchor = p
      while !java.nio.file.Files.exists(anchor) && anchor.getParent != null do anchor = anchor.getParent
      if !java.nio.file.Files.exists(anchor) then p.toString
      else
        val realAnchor = anchor.toRealPath()
        val tail = anchor.relativize(p)
        if tail.toString.isEmpty then realAnchor.toString else realAnchor.resolve(tail).toString
    catch case _: Throwable => path

  /**
   * 单行投影：Record 原样 + live 提示 + 最新 terminal-verdict 判词（ready-to-merge 供给源）
   * + G1 mode（Record 字段随 asJson 原样透出，None = null ⇒ 前端过渡启发兜底）+
   * G5 行内排队条件键（`mergeQueue`/`mergeQueuePos`，判据单点 = [[MergeQueue.queueViewOf]]，
   * 未排队缺键）+ #566 `taskStatus`（引擎任务行终态——行状态权威，前端终态判定的
   * 直接读数；缺席 = 旧投影）。
   */
  private def rowJson(
    registry: Map[String, nebflow.actor.AgentRecord],
    taskStatuses: Map[String, String]
  )(rec: DelegateRegistry.Record): IO[Json] =
    IO.blocking {
      val key = ChainStore.keyFor(rec.taskId, rec.id)
      val live = registry.get(rec.id).map(_ => Json.obj("status" -> "running".asJson))
      val verdict = latestVerdict(key)
      val queue = MergeQueue.queueViewOf(rec.id)
      rec.asJson.asObject match
        case Some(obj) =>
          val withLive = live.fold(obj)(v => obj.add("live", v))
          val withVerdict = verdict.fold(withLive)(v => withLive.add("verdict", v))
          val withTask = taskStatuses.get(rec.id).fold(withVerdict) { s =>
            withVerdict.add("taskStatus", s.asJson)
          }
          val withQueue = queue.fold(withTask) { q =>
            withTask
              .add(
                "mergeQueue",
                Json.obj(
                  "projectKey" -> q.projectKey.asJson,
                  "ahead" -> q.ahead.asJson,
                  "holder" -> q.holder.fold(Json.Null)(_.asJson),
                  "reminderSent" -> q.reminderSent.asJson
                )
              )
              .add(
                "mergeQueuePos",
                Json.obj(
                  "position" -> q.position.asJson,
                  "total" -> q.total.asJson,
                  "readyAt" -> q.readyAt.asJson,
                  "branch" -> q.branch.asJson,
                  "baseBranch" -> q.baseBranch.asJson
                )
              )
          }
          Json.fromJsonObject(withQueue)
        case None => rec.asJson
      end match
    }.handleErrorWith(e =>
      nebflow.shared.NebflowLogger
        .forName("nebflow.core.project.chains-read-face")
        .warn(s"chains rows: row projection failed for '${rec.id}': ${e.getMessage}")
        .as(rec.asJson)
    )

  /** 某键的最新 `terminal-verdict` 行投影（{verdict,reason,source,at}；无行 = None）。 */
  private def latestVerdict(key: String): Option[Json] =
    ChainStore.readSync(key).flatMap { rec =>
      rec.entries
        .filter(_.kind == VerdictRowKind)
        .sortBy(_.at)
        .lastOption
        .map(e =>
          Json.obj(
            "verdict" -> payloadString(e, "verdict").asJson,
            "reason" -> payloadString(e, "reason").asJson,
            "source" -> payloadString(e, "source").asJson,
            "at" -> e.at.asJson
          )
        )
    }

  private val VerdictRowKind = "terminal-verdict"

  private def payloadString(e: ChainStore.Entry, field: String): String =
    e.payload.hcursor.get[String](field).getOrElse("")

  /**
   * per-task 链视图投影（chains/:key 臂）。`None` = 查无 delegate 记录（HTTP 404 面）；
   * `Some` 的 `chain` 字段可为 null（作用域图不存在 = 三行族降级投影）。
   */
  def chainJson(key: String, registry: Map[String, nebflow.actor.AgentRecord]): IO[Option[Json]] =
    if !ChainStore.validKey(key) then IO.pure(None)
    else
      IO.blocking(findRecord(key)).flatMap {
        case None => IO.pure(None)
        case Some(rec) =>
          val storeRecord = IO.blocking(ChainStore.readSync(key)).map {
            case Some(r) => r.asJson
            case None => Json.obj("taskId" -> key.asJson, "entries" -> List.empty[Json].asJson)
          }
          scopeProjection(rec).flatMap { chain =>
            storeRecord.map(record =>
              Some(
                Json.obj(
                  "key" -> key.asJson,
                  "record" -> record,
                  // chain 缺席 = JSON null（作用域图不存在——视图降级为三行族投影）。
                  "chain" -> chain.getOrElse(Json.Null)
                )
              )
            )
          }
      }

  /**
   * delegate 聚合单查投影（delegate-ui 批 2026-10-08 · G4，`GET /api/delegates/:id`
   * 读面——视图④详情首绘单缝）：`record` 全量 + `live` 活性提示（rows 臂同源同形）+
   * `verdict` 最新判词 + `task` 台账全文（taskId → TaskLedger；无号/查无 = null）+
   * `childrenCount`（星型子代理计数，由路由侧经 [[nebflow.agent.SubAgentTaskStore]]
   * .findByParent 供给——本面不持 store，零第二读取路径）。`None` = 查无记录/非法 id
   * （HTTP 404/400 面）。`output` 头（外部执行器增量读首窗）由路由侧并挂（与
   * `/delegates/:id/output` 同源单点，本面零触碰 executor 域）。**零新存储**：全部
   * 现读既有面。
   */
  def delegateJson(id: String, registry: Map[String, nebflow.actor.AgentRecord], childrenCount: Int): IO[Option[Json]] =
    if !DelegateRegistry.validId(id) then IO.pure(None)
    else
      IO.blocking(DelegateRegistry.find(id)).flatMap {
        case None => IO.pure(None)
        case Some(rec) =>
          IO.blocking {
            val verdict = latestVerdict(ChainStore.keyFor(rec.taskId, rec.id))
            val task = rec.taskId.flatMap(tid => TaskLedgerStore.open().findSync(tid))
            (verdict, task)
          }.map { case (verdict, task) =>
            Some(
              Json.obj(
                "record" -> rec.asJson,
                "live" -> registry.get(id).map(_ => Json.obj("status" -> "running".asJson)).getOrElse(Json.Null),
                "verdict" -> verdict.getOrElse(Json.Null),
                "task" -> task.map(_.asJson).getOrElse(Json.Null),
                "childrenCount" -> childrenCount.asJson
              )
            )
          }
      }

  /** 键 → 记录：台账号（taskId）优先命中，delegateId 等价命中（keyFor 同口径双向）。 */
  private def findRecord(key: String): Option[DelegateRegistry.Record] =
    DelegateRegistry.list().find(r => r.taskId.contains(key) || r.id == key)

  /**
   * delegate 作用域的 FlowMap 衍生投影：已挂载宿主现读 → 未挂载盘面探针（作用域目录
   * 在才开——防读面建目录副作用）→ 目录也无 = null。
   */
  private def scopeProjection(rec: DelegateRegistry.Record): IO[Option[Json]] =
    DelegateFlowHosts.get(rec.id).flatMap {
      case Some(h) => h.store.snapshot.map(s => Some(chainNodesJson(rec.id, mounted = true, s)))
      case None =>
        val baseWs = rec.projectWorkspace.getOrElse(rec.cwd)
        IO.blocking {
          val dir =
            os.Path(baseWs, nebflow.shared.PathUtil.dataRoot) / ".nebflow" / FlowMapStore.FlowMapsDirName / rec.id
          if os.exists(dir) && os.isDir(dir) then Some(dir) else None
        }.flatMap {
          case None => IO.pure(None)
          case Some(_) =>
            val projectName = rec.project.getOrElse(os.Path(baseWs).last)
            FlowMapStore
              .openScope(rec.id, projectName, baseWs)
              .flatMap(_.snapshot.map(s => Some(chainNodesJson(rec.id, mounted = false, s))))
              .handleErrorWith { e =>
                nebflow.shared.NebflowLogger
                  .forName("nebflow.core.project.chains-read-face")
                  .warn(s"chains/:key probe open failed for scope '${rec.id}': ${e.getMessage}")
                  .as(None)
              }
        }
    }

  /**
   * 活动区节点 → ChainNode 形 JSON（label=任务首行，description=短描述，状态经
   * [[rowStatusOf]] v3 §9 映射表单点；执行器 = ''（继承链——ChainNode 契约））。
   */
  private def chainNodesJson(scope: String, mounted: Boolean, s: FlowMapState): Json =
    Json.obj(
      "scope" -> scope.asJson,
      "mounted" -> mounted.asJson,
      "nodes" ->
        s.nodes.values.toList
          .sortBy(_.createdAt)
          .map { n =>
            val label = n.task
              .map(_.linesIterator.nextOption().getOrElse("").trim.take(NodePayload.TaskPreviewMaxChars))
              .filter(_.nonEmpty)
              .getOrElse(n.name)
            Json.obj(
              "id" -> n.id.asJson,
              "kind" -> "flow-step".asJson,
              "label" -> label.asJson,
              "description" -> n.description.getOrElse("").asJson,
              "status" -> rowStatusOf(n.status).asJson,
              "deps" -> n.deps.asJson,
              "executor" -> "".asJson
            )
          }
          .asJson
    )

  /**
   * 底座节点态 → ROW_STATUS 映射（v3 §9 逐字表；interrupted = 运行族休眠态归
   * running——sendNodeMessage 同判据先例；未知态归 pending 非终态保守侧）。
   */
  private[project] def rowStatusOf(nodeStatus: String): String = nodeStatus match
    case "pending" | "wiring" => "pending"
    case "running" | "interrupted" => "running"
    case "completed" => "completed"
    case "blocked" => "blocked"
    case "failed" => "failed"
    case "cancelled" => "cancelled"
    case _ => "pending"

end ChainsReadFace
