package nebflow.core.project

import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import nebflow.core.{AtomicJson, NebflowLogger, PathUtil}

/**
 * FlowMapStore —— 每项目 Flow Map 存储（#28 阶段 0，方案 §2.6）。
 *
 * - 磁盘 write-through：活动区 `<workspace>/.nebflow/flow-map.json` +
 *   归档区 `<workspace>/.nebflow/flow-map-archive.json`（原子写，重启恢复）。
 *   result/task 全文不进 JSON——result 拆 `results/<id>.md`（活动+归档），
 *   task 拆 `tasks/<id>.md`（仅活动区；归档 task 剥除，2026-09-06 存储瘦身批），
 *   JSON 只留 ≤500 字符摘要 + 指针（task 归档侧无指针）；加载水合回内存全文
 * - 内存 Ref 持当前状态；所有变更走 `mutate`（Ref.update 原子性 + 落盘）
 * - 环检测：NodeEdit 建边（from→to）前 DFS（to 的传递下游沿 out 边可达 from → 拒）
 * - TTL：completed 节点 ttlExpireAt 到期 → 移入归档区（结果全文保留）；
 *   failed/cancelled/blocked 无 TTL 不过期（2026-09-07 作者裁定：死亡现场保留
 *   主图待上层裁决，永不自动归档）+ 活动区删除
 * - barrier：节点启动条件 = 全部 in 上游 deliveredTo 含本节点（NodeEngine 裁决）
 *
 * 并发纪律：边/计数的变更必须在单个 mutate 内完成（§2.3「单事务更新」）。
 */
class FlowMapStore private (
  val project: String,
  private val statePath: os.Path,
  private val archivePath: os.Path,
  private val state: Ref[IO, FlowMapState],
  private val archive: Ref[IO, FlowMapArchive]
):
  private val logger = NebflowLogger.forName("nebflow.flowmap")

  /** 当前活动区快照。 */
  def snapshot: IO[FlowMapState] = state.get

  /** 当前归档区快照。 */
  def archiveSnapshot: IO[FlowMapArchive] = archive.get

  def getNode(id: String): IO[Option[NodeDef]] = state.get.map(_.nodes.get(id))

  /** 查节点：活动区优先，归档区兜底（§2.1 归档结果仍可接线投递）。 */
  def findNode(id: String): IO[Option[NodeDef]] =
    state.get.map(_.nodes.get(id)).flatMap {
      case some @ Some(_) => IO.pure(some)
      case None => archive.get.map(_.nodes.get(id))
    }

  /** 事务变更：f 应用到当前状态 → Ref 更新 → 落盘。返回新活动区。 */
  def mutate(f: FlowMapState => FlowMapState): IO[FlowMapState] =
    for
      newState <- state.updateAndGet { s =>
        val ns = f(s)
        ns.copy(updatedAt = System.currentTimeMillis())
      }
      _ <- persistState(newState)
    yield newState

  /** 事务变更（带结果版，trigger-chain-fix 批）：f 同时产出 (新状态, 结果值)，
    * 结果与状态转移在同一原子操作内生成——调用方据此区分「本事务做了转移」与
    * 「条件不满足未转移」（CAS 翻转守卫：startNode 翻转后状态是 Running，事后
    * 补查无法区分「本 fiber 翻转」与「读到他者翻转」，必须在事务内判定）。
    * f 在 Ref CAS 自旋下可能重入多次——f 必须纯（结果值只依赖输入状态，无副作用）。 */
  def mutateWithResult[A](f: FlowMapState => (FlowMapState, A)): IO[(FlowMapState, A)] =
    for
      r <- state.modify { s =>
        val (ns, a) = f(s)
        val ns2 = ns.copy(updatedAt = System.currentTimeMillis())
        (ns2, (ns2, a))
      }
      _ <- persistState(r._1)
    yield r

  /** 归档区事务（TTL 移除时用）。 */
  def mutateArchive(f: FlowMapArchive => FlowMapArchive): IO[FlowMapArchive] =
    for
      newArc <- archive.updateAndGet { a =>
        val na = f(a)
        na.copy(project = project)
      }
      _ <- persistArchive(newArc)
    yield newArc

  /** 环检测：设 from.out = to（或给 to 加 in=from，或给 to 声明 deps=[from]）是否成环。
    * to 的传递下游可达 from → 成环。后继集合（沿连接的流向）=
    * 「out 目标（跳过 Nebula）」 ∪ 「{ m | m.deps.contains(当前节点) }」——deps 设计
    * §1.2 校验二：deps 下游单侧持有（上游无对应 out 镜像边），混合图环检测必须显式
    * 并入 deps 反向边；create/edit 全部既有调用点经本单点自动获得 in+deps 混合覆盖。 */
  def wouldCreateCycle(from: String, to: String): IO[Boolean] =
    if to == "Nebula" || to.isEmpty then IO.pure(false)
    else
      state.get.map { s =>
        // deps 反向索引：d → 依赖 d 的节点集（d 完成会触发它们 = 流向 d → m）
        val depsReverse: Map[String, List[String]] =
          s.nodes.values.foldLeft(Map.empty[String, List[String]]) { (acc, n) =>
            n.deps.foldLeft(acc)((a, d) => a.updated(d, n.id :: a.getOrElse(d, Nil)))
          }
        def successors(id: String): List[String] =
          val viaOut = s.nodes.get(id).flatMap(_.out).filter(_ != "Nebula").toList
          viaOut ++ depsReverse.getOrElse(id, Nil)
        def reachable(start: String, visited: Set[String]): Boolean =
          if start == from then true
          else if visited.contains(start) then false
          else
            val nexts = successors(start)
            nexts.nonEmpty && nexts.exists(nxt => reachable(nxt, visited + start))
        reachable(to, Set.empty)
      }

  /** TTL sweep：**completed** 节点 ttlExpireAt 到期 → 从活动区移入归档区（结果全文保留）。
    * 2026-09-07 作者裁定收紧：failed/cancelled（与 blocked 同）永不自动归档、无 TTL
    * 强制清——死亡现场保留主图待上层裁决取消/重跑；存量带 ttlExpireAt 的
    * failed/cancelled 节点由此过滤保护（零数据迁移）。
    * 返回被移除的节点 id 列表（ProjectActor 据此发 WS nodeRemoved）。 */
  def sweepExpired(now: Long): IO[List[String]] =
    for
      s <- state.get
      expired = s.nodes.values
        .filter(n => n.status == NodeLifecycle.Completed)
        .filter(n => n.ttlExpireAt.exists(_ <= now))
        .toList
      _ <- if expired.isEmpty then IO.unit
      else
        val ids = expired.map(_.id).toSet
        mutate(st => st.copy(nodes = st.nodes -- ids)) *>
          mutateArchive(a => a.copy(nodes = a.nodes ++ expired.map(n => n.id -> n).toMap))
      _ <- if expired.isEmpty then IO.unit else IO(logger.infoSync(s"FlowMap[$project] TTL sweep: ${expired.map(_.name).mkString(", ")} → archive"))
    yield expired.map(_.id)

  // ── 持久化 ─────────────────────────────────────────────

  /** 持久化层拆分（2026-09-05 Flow Map 精简批；2026-09-06 存储瘦身批扩到 task）：
    * 内存 Ref / 投递链始终持有**全文**（buildInput / deliverOut / 重投扫描零改动），
    * 落盘时拆两半——
    *   - result 全文 → per-node 文件 `<workspace>/.nebflow/results/<nodeId>.md`；
    *   - task 全文 → per-node 文件 `<workspace>/.nebflow/tasks/<nodeId>.md`（与
    *     results/ 同域；仅活动区写——归档节点 task 剥除不落文件，无重入价值）。
    *     先写文件再写 JSON：崩溃窗口内「文件已存在、JSON 尚带全文」→ 下次加载按
    *     「文件存在即回读」水合，不丢不半；
    *   - JSON（flow-map.json）→ result / task 各收敛为 ≤500 字符摘要 + resultFile /
    *     taskFile 指针（对工具/人工可读）；flow-map-archive.json → result 同款摘要
    *     +指针，task 直接剥除（无 taskFile——归档无重入价值）。
    * 这治掉了「节点 result/task 全文灌进 flow-map.json」的载荷污染（宿主实测
    * 630KB 活动区 + 1.35MB 归档区；09-06 复测 task 仍占 nebflow-website 活动区
    * 214KB 中的 ~101KB）。 */
  private def persistState(s: FlowMapState): IO[Unit] =
    IO.blocking {
      writeResultFiles(s.nodes)
      writeTaskFiles(s.nodes)
      AtomicJson.writeSync(statePath, slimNodeResults(slimNodeTasks(s.asJson)).noSpaces)
    }

  private def persistArchive(a: FlowMapArchive): IO[Unit] =
    IO.blocking {
      if a.nodes.nonEmpty then
        writeResultFiles(a.nodes)
        // 归档不写 task 文件（writeTaskFiles 仅活动区）——存量活动期 task 文件留存
        // 为孤儿（不删不引用），JSON 侧 task 键剥除。
        AtomicJson.writeSync(archivePath, stripNodeTasks(slimNodeResults(a.asJson)).noSpaces)
    }

  /** 结果全文落 per-node 文件（幂等：内容相同跳过写）。 */
  private def writeResultFiles(nodes: Map[String, NodeDef]): Unit =
    val withResult = nodes.values.filter(n => n.result.exists(_.trim.nonEmpty))
    if withResult.nonEmpty then
      os.makeDir.all(resultsDir)
      withResult.foreach { n =>
        val full = n.result.get
        val p = resultsDir / s"${n.id}.md"
        if !os.exists(p) || os.read(p) != full then os.write.over(p, full)
      }

  /** task 全文落 per-node 文件（幂等同款；与 writeResultFiles 同构——A/B 硬契约：
    * 路径 `<workspace>/.nebflow/tasks/<nodeId>.md`、字节 = task 原文（无尾部加工），
    * 存量迁移脚本 scripts/migrate-flowmap-task-slim.mjs 须与此严格一致）。 */
  private def writeTaskFiles(nodes: Map[String, NodeDef]): Unit =
    val withTask = nodes.values.filter(n => n.task.exists(_.trim.nonEmpty))
    if withTask.nonEmpty then
      os.makeDir.all(tasksDir)
      withTask.foreach { n =>
        val full = n.task.get
        val p = tasksDir / s"${n.id}.md"
        if !os.exists(p) || os.read(p) != full then os.write.over(p, full)
      }

  private def resultsDir: os.Path = statePath / os.up / FlowMapStore.ResultsDirName

  private def tasksDir: os.Path = statePath / os.up / FlowMapStore.TasksDirName

  /** 落盘 JSON 手术：result 非空节点 → result=摘要 + 注入 resultFile 指针。
    * circe 派生解码忽略未知键 → resultFile 只活在磁盘 JSON，不进 NodeDef 内存模型。 */
  private def slimNodeResults(json: Json): Json =
    json.asObject match
      case Some(obj) =>
        val slimmed = obj("nodes").flatMap(_.asObject) match
          case Some(nodesObj) =>
            io.circe.JsonObject.fromMap(obj.toMap.updated("nodes", nodesObj.mapValues(slimNodeResult).asJson))
          case None => obj
        Json.fromJsonObject(slimmed)
      case None => json

  private def slimNodeResult(n: Json): Json =
    n.asObject match
      case Some(nobj) =>
        nobj("result").flatMap(_.asString) match
          case Some(r) if r.trim.nonEmpty =>
            val id = nobj("id").flatMap(_.asString).getOrElse("")
            Json.fromJsonObject(io.circe.JsonObject.fromMap(
              nobj.toMap.updated("result", FlowMapStore.summarizeResult(r).asJson)
                .updated("resultFile", s"${FlowMapStore.ResultsDirName}/$id.md".asJson)))
          case _ => n
      case None => n

  /** 落盘 JSON 手术（task 版，2026-09-06 存储瘦身批；与 slimNodeResult 同构）：
    * task 非空节点 → task=摘要 + 注入 taskFile 指针。taskFile 只活在磁盘 JSON
    * （circe 派生解码忽略未知键，不进 NodeDef 内存模型）。 */
  private def slimNodeTasks(json: Json): Json =
    json.asObject match
      case Some(obj) =>
        val slimmed = obj("nodes").flatMap(_.asObject) match
          case Some(nodesObj) =>
            io.circe.JsonObject.fromMap(obj.toMap.updated("nodes", nodesObj.mapValues(slimNodeTask).asJson))
          case None => obj
        Json.fromJsonObject(slimmed)
      case None => json

  private def slimNodeTask(n: Json): Json =
    n.asObject match
      case Some(nobj) =>
        nobj("task").flatMap(_.asString) match
          case Some(t) if t.trim.nonEmpty =>
            val id = nobj("id").flatMap(_.asString).getOrElse("")
            Json.fromJsonObject(io.circe.JsonObject.fromMap(
              nobj.toMap.updated("task", FlowMapStore.summarizeTask(t).asJson)
                .updated("taskFile", s"${FlowMapStore.TasksDirName}/$id.md".asJson)))
          case _ => n
      case None => n

  /** 归档 JSON 手术（task 版，2026-09-06 存储瘦身批）：归档节点 task **直接剥**
    * （task 与 taskFile 两键移除，无重入价值——20260902 待办语义下归档节点不再
    * 重入；存量归档由 scripts/migrate-flowmap-task-slim.mjs / 首次归档落盘收敛）。
    * 其余字段（含 result 摘要与 resultFile 指针）不动。 */
  private def stripNodeTasks(json: Json): Json =
    json.asObject match
      case Some(obj) =>
        val stripped = obj("nodes").flatMap(_.asObject) match
          case Some(nodesObj) =>
            io.circe.JsonObject.fromMap(obj.toMap.updated("nodes", nodesObj.mapValues(stripNodeTask).asJson))
          case None => obj
        Json.fromJsonObject(stripped)
      case None => json

  private def stripNodeTask(n: Json): Json =
    n.asObject match
      case Some(nobj) if nobj.contains("task") || nobj.contains("taskFile") =>
        Json.fromJsonObject(io.circe.JsonObject.fromMap(nobj.toMap - "task" - "taskFile"))
      case _ => n

  /** 加载净化：历史数据存在 out 被写成**字符串** "null" 的行（parseOut 归一化修复
    * 之前 LLM 以字符串 "null" 断开接线被当字面 target id 存盘；实证归档
    * n-8a481bd0/n-c90d1140）。语义应为悬空 None——加载时归一，下次落盘即真 null。 */
  private def normalizeOut(n: NodeDef): NodeDef =
    n.copy(out = n.out.map(_.trim).filterNot(_.equalsIgnoreCase("null")).filter(_.nonEmpty))

  /** H-11①（阶段 2b）：存量 flow-map 节点的旧 skill/mcp 字段——加载时告警 +
    * 仅作展示（deprecated，NodeEdit 已拒写，无自动映射）。字段保留不动。 */
  private def warnLegacySkillMcp(s: FlowMapState): Unit =
    val legacy = s.nodes.values.filter(n => n.skill.isDefined || n.mcp.isDefined).toList
    legacy.foreach(n =>
      logger.warnSync(
        s"Node '${n.name}' (${n.id}) carries deprecated skill/mcp fields (skill=${n.skill.getOrElse("-")}, mcp=${n.mcp.getOrElse("-")}) — " +
          "deprecated since phase 2b (ruling H-11①): display only, NodeEdit no longer accepts them; allocate plugins instead"))
    if legacy.nonEmpty then logger.warnSync(s"flow-map '$project': ${legacy.size} node(s) with legacy skill/mcp values (display only)")

  /** 结果/task 水合 + 存量污染自动迁移（2026-09-05 精简批 result；2026-09-06 存储瘦
    * 身批扩 task，幂等可回滚）。对每个节点——
    *   result（活动/归档同款）：
    *     1. `results/<id>.md` 缺失 → 以 JSON 内 result 值落文件（存量迁移写）；
    *     2. 内存 result 回读文件全文（水合——投递链/重投扫描/详情端点同源全文）；
    *     3. JSON result > ResultSummaryCap 且文件是本次迁移写的 → 判定存量污染。
    *   task（仅活动区 migrateTasks=true；与 result 同构）：
    *     1. `tasks/<id>.md` 缺失 → 以 JSON 内 task 值落文件（存量迁移写；短任务
    *        顺手物化，统一「全文单源在文件」）；
    *     2. 内存 task 回读文件全文（水合——buildInput/重入/NodeList detail 同源，
    *        NodeEngine 零改动；per-node 文件内容 = 内存 task，单源等价）；
    *     3. JSON task > TaskSummaryCap 且文件是本次迁移写的 → 判定存量污染。
    *   归档区（migrateTasks=false）task 不迁移不落文件（无重入价值）：JSON 内存量
    *   task 原样进内存（读旧格式行为不变——taskPreview 等消费方不回退），下次归档
    *   落盘由 stripNodeTasks 剥除；非空 task 计入 migrated（驱动 .bak + open 时
    *   归档一次性收敛落盘）。
    * .bak 只在首次迁移写、不覆盖既有备份（回滚锚点恒为迁移前状态）。 */
  private def hydrateAndMigrate(nodes: Map[String, NodeDef], sourcePath: os.Path, migrateTasks: Boolean): (Map[String, NodeDef], Boolean) =
    val migrated = scala.collection.mutable.ListBuffer.empty[String]
    val hydrated = nodes.map { case (id, n) =>
      var cur = n
      cur.result match
        case Some(r) if r.trim.nonEmpty =>
          val p = resultsDir / s"$id.md"
          if !os.exists(p) then
            if r.length > FlowMapStore.ResultSummaryCap then migrated += id
            os.makeDir.all(resultsDir)
            os.write.over(p, r)
          cur = cur.copy(result = Some(os.read(p)))
        case _ => ()
      if migrateTasks then
        cur.task match
          case Some(t) if t.trim.nonEmpty =>
            val p = tasksDir / s"$id.md"
            if !os.exists(p) then
              if t.length > FlowMapStore.TaskSummaryCap then migrated += id
              os.makeDir.all(tasksDir)
              os.write.over(p, t)
            cur = cur.copy(task = Some(os.read(p)))
          case _ => ()
      else
        cur.task match
          case Some(t) if t.trim.nonEmpty => migrated += id // 归档存量 task = 待剥旧格式
          case _ => ()
      id -> cur
    }
    val needsBackup = migrated.nonEmpty && os.exists(sourcePath) && !os.exists(os.Path(sourcePath.toString + ".bak"))
    if needsBackup then
      os.copy(sourcePath, os.Path(sourcePath.toString + ".bak"), replaceExisting = false)
      logger.warnSync(
        s"flow-map '$project': legacy full-text (result/task) pollution migrated (${migrated.size} node(s)) — original JSON backed up to ${sourcePath.last}.bak")
    (hydrated, migrated.nonEmpty)

  private def loadInitial(): IO[FlowMapState] =
    IO.blocking {
      if os.exists(statePath) then
        jsonParse(os.read(statePath)).flatMap(_.as[FlowMapState]) match
          case Right(s) =>
            val normalized = s.copy(nodes = s.nodes.transform((_, n) => normalizeOut(n)))
            warnLegacySkillMcp(normalized)
            val (hydratedNodes, _) = hydrateAndMigrate(normalized.nodes, statePath, migrateTasks = true)
            normalized.copy(nodes = hydratedNodes)
          case Left(e) =>
            logger.warnSync(s"flow-map.json corrupt: $e — starting empty")
            FlowMapState(project = project, updatedAt = System.currentTimeMillis())
      else FlowMapState(project = project, updatedAt = System.currentTimeMillis())
    }

  private def loadArchive(): IO[(FlowMapArchive, Boolean)] =
    IO.blocking {
      if os.exists(archivePath) then
        jsonParse(os.read(archivePath)).flatMap(_.as[FlowMapArchive]) match
          case Right(a) =>
            val normalized = a.copy(nodes = a.nodes.transform((_, n) => normalizeOut(n)))
            val (hydratedNodes, migrated) = hydrateAndMigrate(normalized.nodes, archivePath, migrateTasks = false)
            (normalized.copy(nodes = hydratedNodes), migrated)
          case Left(_) => (FlowMapArchive(project = project), false)
      else (FlowMapArchive(project = project), false)
    }

object FlowMapStore:
  /** per-node 结果文件目录名（相对 workspace/.nebflow/）。 */
  val ResultsDirName: String = "results"

  /** per-node task 文件目录名（相对 workspace/.nebflow/；与 results/ 同域，
    * 2026-09-06 存储瘦身批）。A/B 硬契约：存量迁移脚本与 store 读写同路径同字节。 */
  val TasksDirName: String = "tasks"

  /** 落盘 JSON 内 result 摘要截断上限（与原 NodePayload 载荷摘要规则同口径）。 */
  val ResultSummaryCap: Int = 500

  /** 落盘 JSON 内 task 摘要截断上限（与 result 摘要同口径）。 */
  val TaskSummaryCap: Int = 500

  /** 摘要规则单点：>Cap take(Cap)+"…"（原 ≤500 载荷摘要语义平移到持久化层）。 */
  def summarizeResult(r: String): String =
    if r.length > ResultSummaryCap then r.take(ResultSummaryCap) + "…" else r

  /** task 摘要规则单点（与 summarizeResult 同构；迁移脚本须逐字节同规则）。 */
  def summarizeTask(t: String): String =
    if t.length > TaskSummaryCap then t.take(TaskSummaryCap) + "…" else t

  /** 打开（或初始化）项目的 Flow Map store。workspace 为项目工作区绝对路径。 */
  def open(project: String, workspace: String): IO[FlowMapStore] =
    val base = os.Path(workspace, PathUtil.dataRoot) / ".nebflow"
    val statePath = base / "flow-map.json"
    val archivePath = base / "flow-map-archive.json"
    for
      s <- IO.blocking(os.makeDir.all(base))
      store = new FlowMapStore(project, statePath, archivePath, Ref.unsafe[IO, FlowMapState](FlowMapState(project = project, updatedAt = 0L)), Ref.unsafe[IO, FlowMapArchive](FlowMapArchive(project = project)))
      initial <- store.loadInitial()
      (arch, archiveMigrated) <- store.loadArchive()
      _ <- store.state.set(initial)
      _ <- store.archive.set(arch)
      // 首写：确保 flow-map.json 存在（验收①「只有 flow-map.json 被 store 写」）；
      // 同时完成存量 JSON 的摘要收敛（水合后内存全文 → 落盘自动拆分）。
      _ <- store.persistState(initial)
      // 归档区迁移收敛：归档 JSON 平日只在 archive mutation 时重写——存量污染在
      // open 时立即收敛一次（幂等；无迁移时零写入）。
      _ <- if archiveMigrated then store.persistArchive(arch) else IO.unit
    yield store
