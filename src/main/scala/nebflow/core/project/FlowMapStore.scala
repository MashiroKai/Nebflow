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
 *   归档区 `<workspace>/.nebflow/flow-map-archive/<batchId>.json`（裁定④「TTL 分开」批
 *   2026-09-07：按派发批次分文件，一批一文件，原子写，重启恢复；存量单文件
 *   `flow-map-archive.json` open 时零丢失迁移进分文件）。
 *   result/task 全文不进 JSON——result 拆 `results/<id>.md`（活动+归档），
 *   task 拆 `tasks/<id>.md`（仅活动区；归档 task 剥除，2026-09-06 存储瘦身批），
 *   JSON 只留 ≤500 字符摘要 + 指针（task 归档侧无指针）；加载水合回内存全文
 * - 内存 Ref 持当前状态；所有变更走 `mutate`（Ref.update 原子性 + 落盘）
 * - 环检测：NodeEdit 建边（from→to）前 DFS（to 的传递下游沿 out 边可达 from → 拒）
 * - 链级即时归档（裁定④「TTL 分开」，取代旧 ttlExpireAt 24h 计时滞留）：链级抽象
 *   P0（C4）起链 = 拓扑分量（topologicalChains，活动∪归档合并集弱连通分量，取代旧
 *   ≤120s 时间批聚簇），**分量归档资格 = chainArchivable**（适用单位=分量，判据本体
 *   不变）→ 整链立即移归档（结果全文保留）+ 活动区删除。归档资格（2026-09-07
 *   20:38「送达即移」+ 2026-09-08 P1「cancelled 判据放行」作者裁定）：链内无活跃
 *   （running/pending/wiring/blocked）节点 ∧ failed 成员已上报（notifySentAt.
 *   isDefined）∧ cancelled 放行（通知系统无 Cancelled reason，旧判据对其永假 →
 *   死链；终态即移）。completed 天然满足；blocked 永不自动归档（必留主图）；未上报
 *   的 failed 保留主图待上报。
 * - barrier：节点启动条件 = 全部 in 上游 deliveredTo 含本节点（NodeEngine 裁决）
 *
 * 并发纪律：边/计数的变更必须在单个 mutate 内完成（§2.3「单事务更新」）。
 */
class FlowMapStore private (
  val project: String,
  private val statePath: os.Path,
  private val archivePath: os.Path,
  private val state: Ref[IO, FlowMapState],
  private val archive: Ref[IO, FlowMapArchive],
  private val batches: Ref[IO, Map[String, ArchiveBatchMeta]]
):
  private val logger = NebflowLogger.forName("nebflow.flowmap")

  /** 当前活动区快照。 */
  def snapshot: IO[FlowMapState] = state.get

  /** 当前归档区快照。 */
  def archiveSnapshot: IO[FlowMapArchive] = archive.get

  /** 归档批次索引快照（裁定④：REST 按批组装 / 分文件写粒度判定的数据源）。 */
  def archiveBatches: IO[Map[String, ArchiveBatchMeta]] = batches.get

  def getNode(id: String): IO[Option[NodeDef]] = state.get.map(_.nodes.get(id))

  /** 查节点：活动区优先，归档区兜底（§2.1 归档结果仍可接线投递）。 */
  def findNode(id: String): IO[Option[NodeDef]] =
    state.get.map(_.nodes.get(id)).flatMap {
      case some @ Some(_) => IO.pure(some)
      case None => archive.get.map(_.nodes.get(id))
    }

  /** 双区合并节点集（链级抽象 P0 · D8 跨区遍历数据源）：活动区 ++ 归档区。归档
    * NodeDef 的 in/out 完整保留 → 跨区连通（续做引用归档上游）在合并集上自动并链。
    * topologicalChains / chainIdOf 的数据源；id 冲突（崩溃窗口双在）活动区优先。 */
  def combinedNodes: IO[Map[String, NodeDef]] =
    for
      s <- state.get
      a <- archive.get
    yield s.nodes ++ a.nodes

  /** 节点链归属判据单点（链级抽象 P0 + U1 多链归属批）：一次分量派生同时产出两个
    * 值——`_1` = 主链 id（载荷 `chainId` 条件键；合并集分量成员数 ≥2 才 Some，孤立
    * 单节点链不带 = payload 零膨胀）、`_2` = 多链归属集（载荷 `chainIds` 条件键 =
    * **主链 id 首项 + 全量成员链**，[[mergeChainIds]]；**仅 merge 节点**且可达成员链数
    * ≥2 才 Some——普通节点恒 None，单值 `chainId` 语义不变）。节点已不在双区
    * （已被移除且查无归档）→ (None, None)。两值同出一次分量派生 ⇒ `chainIds.head`
    * 恒与 `_1` 逐字同值（主链恒首项）。
    * WS 事件经 NodeEngine.emitWithChain、快照/详情经 NodeTools.buildNodeListPayload
    * 消费本判据，三处口径恒同源；分量只派生一次（旧 `chainIdOf` 单独派生会让
    * 「chainId + chainIds」两值各算一次全量分量 —— 本方法即为此而合并）。 */
  def chainAttrsOf(nodeId: String): IO[(Option[String], Option[List[String]])] =
    combinedNodes.map { combined =>
      val chains = FlowMapStore.topologicalChains(combined.values)
      val cid = chains.find(_.memberIds.contains(nodeId)).filter(_.memberIds.size >= 2).map(_.id)
      (cid, FlowMapStore.mergeChainIds(combined, chains, nodeId))
    }

  /** 节点所属主链 id（载荷 chainId 条件键，链级抽象 P0 判据单点）：见
    * [[chainAttrsOf]]（本方法为其单值投影，语义与判据逐字同源）。 */
  def chainIdOf(nodeId: String): IO[Option[String]] =
    chainAttrsOf(nodeId).map(_._1)

  /** merge 节点多链归属集（载荷 chainIds 条件键，U1 判据单点）：见 [[chainAttrsOf]]。 */
  def chainIdsOf(nodeId: String): IO[Option[List[String]]] =
    chainAttrsOf(nodeId).map(_._2)

  /** 本派生链**全集**（R2「一个 Mail 统一」批 2026-09-12，B2-x）：`Mail` 的
    * `chainId` 参数只校验不落库（B2-x）——校验源即本方法。判据与
    * [[chainAttrsOf]] / [[chainIdsOf]] **同一单点**（`topologicalChains` 分量
    * 派生），不新增第二套链推导逻辑（R3-a：不新增链级账本）。 */
  def allChainIds: IO[Set[String]] =
    combinedNodes.map(combined => FlowMapStore.topologicalChains(combined.values).map(_.id).toSet)

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

  /** 归档区事务（单点更新用：out 改线 / notifySentAt / nebulaDeliveredAt 记账）。
    * diff 落盘（裁定④）：old/new 对比出变更节点 → 批次索引映射 → 只重写受影响批
    * 文件；变更节点无批次归属（防呆，正常路径不发生）→ 整档重写兜底。 */
  def mutateArchive(f: FlowMapArchive => FlowMapArchive): IO[FlowMapArchive] =
    for
      pair <- archive.modify { a =>
        val na = f(a).copy(project = project)
        (na, (a, na))
      }
      (oldA, newA) = pair
      _ <- persistArchiveDiff(oldA, newA)
    yield newA

  /** 归档区事务（带结果版，批 F1' 修复第 2 轮 2026-09-12）：语义与 [[mutateWithResult]]
    * 同源，作用于归档区——调用方据此区分「本事务做了转移」与「条件不满足未转移」。
    * 首个消费方 = `NodeEngine` 的销毁扫描腿：归档区成员的 `destroyAt` 清除要按
    * 「字段仍等于登记值」做 CAS（并发同拍只留一个赢家 ⇒ 不重复发 `node-destroyed`），
    * 与活动区侧清点同判据。f 在 Ref CAS 自旋下可能重入多次 ⇒ f 必须纯（结果值只依赖
    * 输入状态，无副作用）。 */
  def mutateArchiveWithResult[A](f: FlowMapArchive => (FlowMapArchive, A)): IO[(FlowMapArchive, A)] =
    for
      pair <- archive.modify { a =>
        val (na0, res) = f(a)
        val na = na0.copy(project = project)
        (na, (a, na, res))
      }
      (oldA, newA, res) = pair
      _ <- persistArchiveDiff(oldA, newA)
    yield (newA, res)

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
          // P1 多边：全部 out 边目标（跳过 Nebula）；环检测目标仍是单 id（to 参数）。
          // **控制边豁免（nrloop 一期 2026-09-12，设计 §3.3 #14 / 红线①）**：`mode==loop`
          // 的 out 边是「回边」——图上**不连**（不进邻接表、不进环检、不进谱系邻接）。
          // 若不豁免，verifier 的 `(fail)worker:loop` 会被判成 DAG 环而拒建（作者裁定
          // R5(a)：`reloopTo` 是唯一执行入口，回边纯控制信号）。
          val viaOut = s.nodes.get(id)
            .map(_.out.filterNot(OutEdge.isLoopEdge).map(_.to).filterNot(_ == "Nebula"))
            .getOrElse(Nil)
          viaOut ++ depsReverse.getOrElse(id, Nil)
        def reachable(start: String, visited: Set[String]): Boolean =
          if start == from then true
          else if visited.contains(start) then false
          else
            val nexts = successors(start)
            nexts.nonEmpty && nexts.exists(nxt => reachable(nxt, visited + start))
        reachable(to, Set.empty)
      }

  /** 链级即时归档 sweep（裁定④「TTL 分开」批 2026-09-07，取代旧 ttlExpireAt 24h 计时
    * 滞留 sweep；链级抽象 P0 · C4：聚簇口径由时间批换拓扑分量——spec §4.3-3「先合并
    * 算分量、再筛活动成员」直白实现）：分量在活动∪归档合并集上判定
    * （FlowMapStore.topologicalChains · D8），归档资格 = chainArchivable（判据本体
    * 不变，2026-09-07 20:38「送达即移」+ 2026-09-08 P1「cancelled 判据放行」：链内
    * 无活跃节点 ∧ failed 成员已上报（notifySentAt.isDefined）∧ cancelled 放行——
    * 通知系统无 Cancelled reason、cancel/abandon 均不写 notifySentAt，旧判据对其
    * 永假 → 含 cancelled 成员的链整链死锁，71/83 占位死链根因；作者拍板判据放行 =
    * 终态即移）→ 整分量立即移归档 + 活动区删除。归档区成员恒终态（已在归档）→
    * 资格实际由分量的**活动区成员**决定；跨区续做分量（活跃下游接归档上游）的归档
    * 区成员不重复出库，仅活动成员入册——批 id 取分量链 id，与既有归档批 id 重合时
    * nodeIds 并集合并（防旧批成员孤儿化，批文件收敛为全链）。
    * completed 天然满足；blocked 永不自动归档（必留主图）；未上报的 failed → 保留
    * 主图待上报（终态成员留主图，前端规格 §3.1 同源）。
    * TtlTick 30s 驱动（ProjectActor）——视觉「同帧淡出」由前端派生管线当帧承担，本
    * sweep 承担出库与归档落盘（≤30s 滞后不可见：nodeRemoved 到达时前端墓碑幂等）。
    * 顺序 = 归档先行（批次注册 → 归档区写入 → 分文件落盘）再删活动区：崩溃窗口残留
    * 「活动+归档双在」下个 tick 自愈（幂等重归档零写入 + 补删），反向（先删活动）
    * 崩溃则双失。返回被移除的节点 id 列表（ProjectActor 据此发 WS nodeRemoved）。
    *
    * P3 归档联动批（2026-09-10）：链级明细版 [[sweepCompletedChainsDetailed]] 为唯一
    * 实现，本方法退化为「只取被移除节点 id」的兼容外壳（既有调用方/单测零改动）；
    * 归档审计事件（chain-archived）由调用方在出库后追加——事件载荷需 chainId /
    * 分量最早节点 / 成员数，这些**只能来自 sweep 内部派生的链分量**（本类是链派生
    * 单点，调用方二次派生 = 禁止的双端派生，spec §6.2 注）。 */
  def sweepCompletedChains(now: Long): IO[List[String]] =
    sweepCompletedChainsDetailed(now).map(_.flatMap(_.nodeIds))

  /** sweep 出库明细（P3 归档联动批）：语义与归档判定与 [[sweepCompletedChains]] 完全
    * 同源（同一实现），返回每链的链级事实——chainId（分量链 id）、nodeId（分量内
    * createdAt 最早节点，= chainId 派生源节点，FlowMapStore.scala:643 同源）、
    * nodeIds（本次移出活动区的成员）、members（分量成员总数，含已在归档区的成员——
    * 与归档批文件 nodeIds 并集口径同源）。 */
  def sweepCompletedChainsDetailed(now: Long): IO[List[FlowMapStore.SweptChain]] =
    for
      s <- state.get
      a <- archive.get
      chains = FlowMapStore.topologicalChains(s.nodes.values ++ a.nodes.values)
      doneChains = chains.flatMap { c =>
        val activeMembers = c.memberIds.flatMap(id => s.nodes.get(id))
        if activeMembers.isEmpty || !FlowMapStore.chainArchivable(activeMembers) then None
        else Some((c, activeMembers))
      }
      _ <- if doneChains.isEmpty then IO.unit
      else
        val moved = doneChains.flatMap(_._2)
        val ids = moved.map(_.id).toSet
        for
          bt0 <- batches.get
          // 批 id 合并（跨区续做分量）：拓扑链 id 可能与既有归档批 id 重合（归档上游
          // 是分量内 createdAt 最早节点）——memberIds 并集，旧批成员保持批次归属。
          metas: Map[String, ArchiveBatchMeta] = doneChains.map { case (c, members) =>
            val merged = bt0.get(c.id).map(_.nodeIds).getOrElse(Set.empty)
            c.id -> ArchiveBatchMeta(c.id, now, members.map(_.id).toSet ++ merged)
          }.toMap
          _ <- batches.update(_ ++ metas)
          newArc <- archive.updateAndGet(a2 => a2.copy(nodes = a2.nodes ++ moved.map(n => n.id -> n).toMap))
          _ <- persistBatchFiles(metas.keySet, newArc, metas)
          _ <- mutate(st => st.copy(nodes = st.nodes -- ids))
          _ <- IO(logger.infoSync(s"FlowMap[$project] chain sweep: ${doneChains.map(_._1.id).mkString(", ")} (${ids.size} node(s)) → archive"))
        yield ()
    yield doneChains.map { case (c, members) =>
      FlowMapStore.SweptChain(
        chainId = c.id,
        nodeId = c.memberIds.headOption.getOrElse(""),
        nodeIds = members.map(_.id),
        members = c.memberIds.size,
        archivedAt = now
      )
    }

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
    *     taskFile 指针（对工具/人工可读）；归档分文件（flow-map-archive/<batchId>.json）
    *     → result 同款摘要+指针，task 直接剥除（无 taskFile——归档无重入价值）。
    * 这治掉了「节点 result/task 全文灌进 flow-map.json」的载荷污染（宿主实测
    * 630KB 活动区 + 1.35MB 归档区；09-06 复测 task 仍占 nebflow-website 活动区
    * 214KB 中的 ~101KB）。 */
  private def persistState(s: FlowMapState): IO[Unit] =
    IO.blocking {
      writeResultFiles(s.nodes)
      writeTaskFiles(s.nodes)
      AtomicJson.writeSync(statePath, slimNodeResults(slimNodeTasks(s.asJson)).noSpaces)
    }

  /** 归档分文件落盘（裁定④）：`<batchId>.json` 一批一文件——result 摘要+指针、task
    * 剥除（手术与旧单文件同款）。只写 batchIds 命中的批（增量写粒度；替代旧「每次
    * 变更全量重写 flow-map-archive.json」的写放大）。批成员全被移除 → 删批文件。 */
  private def persistBatchFiles(batchIds: Set[String], a: FlowMapArchive, bt: Map[String, ArchiveBatchMeta]): IO[Unit] =
    IO.blocking {
      if batchIds.nonEmpty then
        os.makeDir.all(archiveDir)
        batchIds.foreach { bid =>
          bt.get(bid).foreach { meta =>
            val nodes = meta.nodeIds.flatMap(id => a.nodes.get(id).map(id -> _)).toMap
            if nodes.isEmpty && meta.nodeIds.nonEmpty then
              os.remove.all(batchPath(bid)) // 批成员全移除 → 批文件删除（当前无调用方，防呆）
            else if nodes.nonEmpty then
              writeResultFiles(nodes)
              // 归档不写 task 文件（writeTaskFiles 仅活动区）——存量活动期 task 文件
              // 留存为孤儿（不删不引用），JSON 侧 task 键剥除。
              val file = FlowMapArchiveBatch(project, bid, meta.archivedAt, nodes)
              AtomicJson.writeSync(batchPath(bid), stripNodeTasks(slimNodeResults(file.asJson)).noSpaces)
          }
        }
    }

  /** mutateArchive 的 diff 落盘：只重写变更节点所属批文件。变更/移除节点无批次归属
    * （直种归档等防呆路径）→ 现场聚簇注册批次再落盘——归档写永不丢节点。 */
  private def persistArchiveDiff(oldA: FlowMapArchive, newA: FlowMapArchive): IO[Unit] =
    val changed = (newA.nodes.filterNot { case (id, n) => oldA.nodes.get(id).contains(n) }.keySet
      ++ (oldA.nodes.keySet -- newA.nodes.keySet))
    if changed.isEmpty then IO.unit
    else
        for
          bt <- batches.get
          affected = bt.collect { case (bid, meta) if meta.nodeIds.exists(changed.contains) => bid }.toSet
          orphaned = changed.filterNot(id => bt.values.exists(_.nodeIds.contains(id)))
          _ <-
            if orphaned.isEmpty then persistBatchFiles(affected, newA, bt)
            else
              val nowMs = System.currentTimeMillis()
              for
                s <- state.get
                // 孤儿重聚簇（链级抽象 P0 · C4 拓扑口径取代时间批）：孤儿在合并集
                // 分量上定域（分量链 id 作批 id——跨区续做分量与活动侧同链同 id），
                // 只入册孤儿成员（批内既有成员不重复入册）；分量 id 与既有批重合 →
                // nodeIds 并集合并（防旧批成员孤儿化，批文件收敛为全链）。
                newMetas = FlowMapStore
                  .topologicalChains(s.nodes.values ++ newA.nodes.values)
                  .flatMap { c =>
                    val orphans = c.memberIds.filter(orphaned.contains)
                    if orphans.isEmpty then None
                    else
                      val orphanNodes = orphans.flatMap(id => newA.nodes.get(id))
                      val base = ArchiveBatchMeta(c.id,
                        orphanNodes.flatMap(_.completedAt).foldLeft(nowMs)(math.max),
                        orphans.toSet)
                      Some(c.id -> bt.get(c.id)
                        .map(p => base.copy(nodeIds = base.nodeIds ++ p.nodeIds, archivedAt = math.max(base.archivedAt, p.archivedAt)))
                        .getOrElse(base))
                  }.toMap
                _ <- batches.update(_ ++ newMetas)
                bt2 <- batches.get
                _ <- persistBatchFiles(affected ++ newMetas.keySet, newA, bt2)
              yield ()
        yield ()

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

  /** 归档分文件目录（裁定④：`<workspace>/.nebflow/flow-map-archive/`）。 */
  private def archiveDir: os.Path = statePath / os.up / FlowMapStore.ArchiveDirName

  private def batchPath(batchId: String): os.Path = archiveDir / s"$batchId.json"

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

  /** 加载净化（P1 双读后的边形态）：历史数据存在 out 被写成**字符串** "null" 的行
    * （parseOut 归一化修复之前 LLM 以字符串 "null" 断开接线被当字面 target id 存盘；
    * 实证归档 n-8a481bd0/n-c90d1140）。codec 双读已把字符串 "null" 解码为 Nil——本
    * 净化保留为手改数据防御：滤除 to 为空/字面 "null" 的边。语义应为悬空 Nil。 */
  private def normalizeOut(n: NodeDef): NodeDef =
    n.copy(out = n.out.filterNot(e => e.to.trim.isEmpty || e.to.equalsIgnoreCase("null")))

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

  /** 归档加载（裁定④分批布局）+ 存量单文件零丢失迁移。返回 (归档区, 批次索引,
    * 待收敛批 id 集——open 收尾时对它们重写一次分文件)。
    *   1. 分文件目录在 → 逐批读入（单批损坏只跳该批，不拖垮全档）+ result 水合；
    *   2. 存量单文件 flow-map-archive.json 在 → 解码 + result 水合迁移 → 剔除已被
    *      分文件覆盖的节点（崩溃重入去重，分文件优先）→ 聚簇分批 → 逐批落分文件
    *      （已存在不覆盖，幂等）→ 单文件改名 .split-bak（回滚锚点，不覆盖既有备份）。
    * 崩溃中段安全：分文件原子写；改名只在全部批文件落盘后——中途崩溃下次 open
    * 重走迁移，已写批文件经去重跳过。 */
  private def loadArchive(): IO[(FlowMapArchive, Map[String, ArchiveBatchMeta], Set[String])] =
    IO.blocking {
      val fromFiles: List[(ArchiveBatchMeta, Map[String, NodeDef], Boolean)] =
        if os.exists(archiveDir) then
          os.list(archiveDir).filter(_.last.endsWith(".json")).toList.flatMap { f =>
            jsonParse(os.read(f)).flatMap(_.as[FlowMapArchiveBatch]) match
              case Right(b) =>
                val bid = if b.batch.nonEmpty then b.batch else f.last.stripSuffix(".json")
                val normalized = b.nodes.transform((_, n) => normalizeOut(n))
                val (hydrated, migrated) = hydrateAndMigrate(normalized, f, migrateTasks = false)
                Some((ArchiveBatchMeta(bid, b.archivedAt, hydrated.keySet), hydrated, migrated))
              case Left(e) =>
                logger.warnSync(s"flow-map '$project': archive batch file corrupt: ${f.last}: $e — skipped")
                None
          }
        else Nil
      val fileNodeIds = fromFiles.flatMap(_._2.keySet).toSet
      val fileMetas = fromFiles.map(_._1).map(m => m.id -> m).toMap
      val converge = fromFiles.collect { case (m, _, true) => m.id }.toSet

      val fromMonolith: List[(ArchiveBatchMeta, Map[String, NodeDef])] =
        if os.exists(archivePath) then
          jsonParse(os.read(archivePath)).flatMap(_.as[FlowMapArchive]) match
            case Right(a0) =>
              val normalized = a0.copy(nodes = a0.nodes.transform((_, n) => normalizeOut(n)))
              val (hydrated, _) = hydrateAndMigrate(normalized.nodes, archivePath, migrateTasks = false)
              val toMigrate = hydrated.filterNot { case (id, _) => fileNodeIds.contains(id) }
              val nowMs = System.currentTimeMillis()
              // 迁移聚簇（链级抽象 P0 · C4 拓扑口径）：待迁移节点按拓扑分量分批——
              // 只在 toMigrate 上派生（已入分文件的节点保持原批零迁移，C5；若并入
              // 派生会让分量 id 锚到既有批 → 命中「文件已在」幂等跳过 → 节点丢失）。
              val migratedBatches = FlowMapStore.topologicalChains(toMigrate.values).flatMap { c =>
                val members = c.memberIds.flatMap(id => toMigrate.get(id))
                val bid = c.id
                val target = batchPath(bid)
                if os.exists(target) then None // 幂等：批文件已在（崩溃重入）→ 不覆盖
                else
                  val meta = ArchiveBatchMeta(bid,
                    members.flatMap(_.completedAt).foldLeft(nowMs)(math.max),
                    members.map(_.id).toSet)
                  val nodes = members.map(n => n.id -> n).toMap
                  writeResultFiles(nodes)
                  val file = FlowMapArchiveBatch(project, bid, meta.archivedAt, nodes)
                  AtomicJson.writeSync(target, stripNodeTasks(slimNodeResults(file.asJson)).noSpaces)
                  Some(meta -> nodes)
              }
              val splitBak = os.Path(archivePath.toString + ".split-bak")
              val dest = if os.exists(splitBak) then os.Path(s"${archivePath}.split-bak.$nowMs") else splitBak
              os.move(archivePath, dest)
              if hydrated.nonEmpty then
                logger.warnSync(s"flow-map '$project': legacy single-file archive migrated to per-batch files (${migratedBatches.size} batch file(s), ${toMigrate.size} node(s)) — original renamed to ${dest.last}")
              migratedBatches
            case Left(e) =>
              logger.warnSync(s"flow-map-archive.json corrupt: $e — archive starts empty")
              Nil
        else Nil

      val allMetas = fileMetas ++ fromMonolith.map(_._1).map(m => m.id -> m).toMap
      val allNodes = fromFiles.foldLeft(Map.empty[String, NodeDef])(_ ++ _._2) ++
        fromMonolith.foldLeft(Map.empty[String, NodeDef])(_ ++ _._2)
      (FlowMapArchive(project = project, nodes = allNodes), allMetas, converge)
    }

object FlowMapStore:
  /** per-node 结果文件目录名（相对 workspace/.nebflow/）。 */
  val ResultsDirName: String = "results"

  /** sweep 出库明细载体（P3 归档联动批 2026-09-10；[[FlowMapStore.sweepCompletedChainsDetailed]]
    * 的返回元素）：链级事实，供调用方追加 chain-archived 审计事件——链 id、
    * 分量内 createdAt 最早节点（事件 nodeId；= chainId 派生源节点，FlowMapStore.scala:643）、
    * 本次移出成员、分量成员总数（含已在归档区成员；与归档批文件 nodeIds 并集口径同源）、
    * 出库时刻（= 归档批 archivedAt，同一 now 参数）。**派生只发生在本类内部**，
    * 调用方不得二次派生（spec §6.2 双端派生禁令）。 */
  case class SweptChain(
    chainId: String,
    nodeId: String,
    nodeIds: List[String],
    members: Int,
    archivedAt: Long
  )

  /** per-node task 文件目录名（相对 workspace/.nebflow/；与 results/ 同域，
    * 2026-09-06 存储瘦身批）。A/B 硬契约：存量迁移脚本与 store 读写同路径同字节。 */
  val TasksDirName: String = "tasks"

  /** 归档分文件目录名（裁定④「TTL 分开」批；相对 workspace/.nebflow/）。 */
  val ArchiveDirName: String = "flow-map-archive"

  /** 无需上报即满足归档资格的终态集：**completed**（无上报要求，天然满足
    * [[chainArchivable]]）。2026-09-07 12:29 裁定保留（failed/cancelled/blocked 永不
    * 满足本集）；第三次演进（20:38「送达即移」）后归档判据 = [[chainArchivable]]，
    * 本集并入其「completed → true」分支。注意不复用 NodeLifecycle.Terminal
    * （含 blocked，blocked 永不自动归档）。 */
  val ChainTerminalStatuses: Set[String] =
    Set(NodeLifecycle.Completed)

  /** 链级归档资格（2026-09-07 20:38「送达即移」+ 2026-09-08 P1「cancelled 判据放行」
    * 作者裁定，取代 12:29 占图部分）：completed → true（无上报要求）；failed → 上报
    * 已送达（notifySentAt.isDefined——终态化后 deliverFailed 尾部触发通知落
    * notifySentAt）；**cancelled → true**（P1 判据放行：通知系统无 Cancelled reason、
    * cancel/abandon 均不写 notifySentAt，旧判据「cancelled 需已上报」永假 → 含
    * cancelled 成员的链整链永久锁死不出库。cancelled 是终态，语义实质从「无通道永
    * 滞留」变「终态即移」，作者已拍板实施；归档后回看走归档面板，findNode 兜底可达）。
    * blocked 永不自动归档（必留主图）；running/pending/wiring 为活跃态非终态 → 链未齐
    * （活跃链兄弟保留语义不变）。
    * 适用单位（链级抽象 P0 · C4）：时间批 → **拓扑分量**——调用方传入分量的活动区
    * 成员（归档区成员恒终态天然放行，资格实际由活动成员决定，sweep §4.3-3）。
    * 注意：前端 flowMapArchive.js chainEligible 仍是 20:38 旧判据镜像，本批未同步
    * （P1 引擎单侧先行，前端批跟进）——后端归档先行的 ≤30s 窗口内前端派生链仍按旧
    * 判据留主图，nodeRemoved 墓碑幂等兜底出图，无归档泄漏。
    *
    * **销毁窗口与归档资格正交**（noderpt 批 F1' 修复第 2 轮，2026-09-12）：本判据
    * **不看** `destroyAt`——「终态延迟销毁窗口（30min）」与「链级归档（TtlTick 30s）」
    * 是两个独立轴：窗口未收殓**不阻塞**出库，窗口语义改由销毁扫描腿的**双区读面**
    * 兜底（`NodeEngine.sweepDestroyWindows` 同时扫活动区 ∪ 归档区 ⇒ 被搬进归档区的
    * 成员到点照样收殓，见该函数头注）。历史（已撤销）：F1（`314b20c0`）曾在此前置
    * 拒收带 `destroyAt` 的成员，使链尾节点「窗口未收殓」期间整链不出库——被独立复核
    * 判为回归（`NodeDepsSpec.T4` / `NodeEdgeRepairSpec` 6 例全红，仅还原本函数即 16/16
    * 全绿），故撤销：归档资格只由活跃态 / 上报事实决定（口径见上段）。 */
  def chainArchivable(members: Iterable[NodeDef]): Boolean =
    members.forall { n =>
      n.status match
        case s if ChainTerminalStatuses.contains(s) => true
        case NodeLifecycle.Cancelled                => true
        case NodeLifecycle.Failed                   => n.notifySentAt.isDefined
        case _                                      => false
    }

  /** 拓扑链派生单点（链级抽象 P0 · spec §2.1；C4 起取代旧时间批聚簇 clusterBatches
    * ——该算法与 ChainBatchMs 常量已退役，全消费方 sweep/persistArchiveDiff/
    * loadArchive 均走本单点，无双口径漂移面）：链 = 节点集上的**弱连通分量**——
    * 无向边 = in ∪ out ∪ deps（C3/D1 deps 算链边，弱关联语义由谱系边表 via 标注）；
    * out 边逐目标解析——"Nebula" 豁免（非图成员，环检同款）、名字形态过
    * OutEdge.resolveTargetId 命中才连、悬空名跳过不产生链归属（D9，与 settleTo
    * MISS 语义一致）；retry 回跳边不进任何邻接表 → 天然不进链拓扑（D4 既定事实）；
    * merge/loop 节点 = 普通成员（D3/D4）。孤立节点 = 单成员链（D6）。
    * chainId = `chain-<分量内 createdAt 最早节点 id>`（与旧时间批 id 规则同构）：
    * 链尾追加成员 → 最早节点不变 → id 稳定；分量合并 → 归并为最早 createdAt 者
    * 的 id。entries = 分量内 in=Nil ∧ deps=Nil 双空（D2，与创建期入口判据对齐）；
    * ends = 分量内 out 无节点目标的成员（仅 Nebula 边/悬空名/out=Nil 都算，D7）。
    * edges = 谱系边表（同双端不同 via 各自保留；方向恒上游→下游）——**via 按 mode
    * 细化（nrloop 一期 2026-09-12）**：`:loop` 控制边标 `"loop"`、普通 out 边标
    * `"out"`（`"in"`/`"deps"` 不变）；`loop` 边**仍连**（弱连通分量本就是无向的，
    * 回边不破坏链归属，D3/D4 的「loop 节点 = 普通成员」口径沿用到「回边」上）。
    * 确定性：分量列表按 id 排序、成员按 (createdAt, id) 升序、edges 全排序——同输入
    * 恒同输出。纯函数（无 IO、不读 store），数据源由调用方给定（合并集 = 双区）。 */
  def topologicalChains(nodes: Iterable[NodeDef]): List[ChainInfo] =
    val nodeMap = nodes.map(n => n.id -> n).toMap
    val emptyAdj = scala.collection.mutable.LinkedHashSet.empty[String]
    val adj = scala.collection.mutable.HashMap.empty[String, scala.collection.mutable.LinkedHashSet[String]]
    val edges = scala.collection.mutable.LinkedHashSet.empty[ChainEdge]
    def link(upstream: String, downstream: String, via: String): Unit =
      // 双端都必须在节点集内——悬空引用不产生链归属（也不进邻接表，防幽灵成员）
      if nodeMap.contains(upstream) && nodeMap.contains(downstream) then
        adj.getOrElseUpdate(upstream, scala.collection.mutable.LinkedHashSet.empty) += downstream
        adj.getOrElseUpdate(downstream, scala.collection.mutable.LinkedHashSet.empty) += upstream
        edges += ChainEdge(from = upstream, to = downstream, via = via)
    nodeMap.values.foreach { n =>
      n.in.foreach(up => link(up, n.id, "in"))
      n.out.foreach { e =>
        if e.to != OutEdge.NebulaTarget then
          // via 按 mode 细化（nrloop 一期 2026-09-12，设计 §3.3 #16）：`:loop` 控制边
          // 标 "loop"，与普通 out 边区分——谱系/取证侧据此辨「这条边是回边，图上不连、
          // barrier 不认」；链口径不变（弱连通分量本来就是无向的，回边不破坏它）。
          OutEdge.resolveTargetId(nodeMap, e.to).foreach(t =>
            link(n.id, t, if OutEdge.isLoopEdge(e) then "loop" else "out"))
      }
      n.deps.foreach(up => link(up, n.id, "deps"))
    }
    def hasNodeTarget(n: NodeDef): Boolean =
      n.out.exists(e => e.to != OutEdge.NebulaTarget && OutEdge.resolveTargetId(nodeMap, e.to).isDefined)
    // 弱连通分量（BFS；起点按 (createdAt, id) 排序保证确定性）
    val seen = scala.collection.mutable.HashSet.empty[String]
    val components = scala.collection.mutable.ListBuffer.empty[List[NodeDef]]
    nodeMap.values.toList.sortBy(n => (n.createdAt, n.id)).foreach { start =>
      if !seen.contains(start.id) then
        seen += start.id
        val queue = scala.collection.mutable.Queue.empty[String]
        queue.enqueue(start.id)
        val comp = scala.collection.mutable.ListBuffer.empty[NodeDef]
        while queue.nonEmpty do
          val cur = queue.dequeue()
          nodeMap.get(cur).foreach(comp += _)
          adj.getOrElse(cur, emptyAdj).foreach { nxt =>
            if !seen.contains(nxt) then
              seen += nxt
              queue.enqueue(nxt)
          }
        components += comp.toList
    }
    components.map { members =>
      val sorted = members.sortBy(n => (n.createdAt, n.id))
      val memberIds = sorted.map(_.id).toList
      val memberIdSet = memberIds.toSet
      ChainInfo(
        id = s"chain-${sorted.head.id}",
        entries = sorted.filter(n => n.in.isEmpty && n.deps.isEmpty).map(_.id).toList,
        ends = sorted.filterNot(hasNodeTarget).map(_.id).toList,
        memberIds = memberIds,
        edges = edges.toList
          .filter(e => memberIdSet.contains(e.from) && memberIdSet.contains(e.to))
          .sortBy(e => (e.from, e.to, e.via))
      )
    }.sortBy(_.id).toList

  /** merge 节点多链归属派生（U1 批 · 2026-09-11 作者裁定①「多链归属只对合并节点做」）。
    *
    * **判定单位**：链归属的身份层仍是 [[topologicalChains]] 的弱连通分量（分区单值，
    * 每节点恰属一个分量）；「多链」指的**不是**一个节点落在多个分量里（弱连通分量是
    * 节点集划分，恒不可能），而是**合并节点被多条「成员链」共享**——成员链 = 分量内
    * 按**入口可达分解**得到的枝线（每个入口 e，即 `in ∧ deps` 双空节点，对应一条
    * 独立派发的支线；其链 id 与它独占分量时该有的 id 同构 = `chain-<e>`）。合并节点
    * 的多个上游 `in` 边把各支线汇聚进同一分量，于是它同时属于这些支线：
    * `chainIds(M) = 主链（= 分量链 id，[[topologicalChains]] id）:: 全量成员链 id
    * （可达 M 的入口链，分量 entries 序）`，全部列出、**无上限、无降级路径**
    * （作者裁定①；原「上限 4 + 超限降级」方案已废）。**主链恒首项**
    * （`chainIds.head == chainId`）；主链本身同时是可达成员链时只出现一次
    * （`.distinct`，首位保留主链）。两键分工：`chainId` = 分区归属单值
    * （折叠/归档/落点），`chainIds` = 主链 + 多链成员归属 —— 主链值在两键中冗余
    * 出现，属**有意的形态契约**（对应文档元数据头 §0bis.3 `chains: [主链, 支链…]`
    * 首项恒主链；作者裁定①逐字「主链 chainId + 全量成员链」）。
    *
    * 定向可达（§12.2.3-B 最小自洽定义，与「in 代理接线为主」的现状拓扑吻合）：
    * 沿 **in 正向（u → 引用 u 的下游）∪ out 正向（跳过 Nebula/悬空名）∪ deps 正向**
    * 遍历；入口 e 可达 M ⇔ M ∈ chainIds(M) 的成员链之一。分量外无边（弱分量定义），
    * 故遍历在分量内闭包。
    *
    * **门控**：仅 `n.merge == true` 且**可达该 merge 的成员链数 ≥2** 才返回 Some
    * （普通节点保持现有单值 `chainId` 不变，禁改成全员数组，作者裁定①；单链合并节点
    * 仅 1 条成员链可达 ⇒ None = 前端按单链语义处理）。计量口径 = **入口可达分解得到的
    * 成员链条数**（`memberChains` 的长度，「除自身主链外」按该分解计量）——**不是**拼上
    * 前置主链项后的裸长度（`chainIds.size`）：后者会让「主链 + 1 条成员链」形态凑够 2
    * 而被误判多链。门控口径与本次形态恢复**无关**（本批只改组成，门控、成员链派生、
    * 无上限无降级三者不变）。
    * 确定性：成员链按分量 entries 序（createdAt, id 升序），主链恒首项。 */
  def mergeChainIds(
      combined: Map[String, NodeDef],
      chains: List[ChainInfo],
      nodeId: String
  ): Option[List[String]] =
    combined.get(nodeId).filter(_.merge).flatMap { _ =>
      chains.find(_.memberIds.contains(nodeId)).flatMap { comp =>
        val nodeMap = comp.memberIds.flatMap(id => combined.get(id).map(id -> _)).toMap
        // in 反向索引（u → 引用 u 的成员节点）：in 边在存储上只由下游持有，
        // 正向遍历必须自建反向表（与 wouldCreateCycle 的 depsReverse 同法）。
        val inRev = scala.collection.mutable.HashMap.empty[String, List[String]]
        nodeMap.values.foreach(n => n.in.foreach(up => inRev.update(up, n.id :: inRev.getOrElse(up, Nil))))
        def reachFrom(entry: String): Set[String] =
          val seen = scala.collection.mutable.HashSet(entry)
          val stack = scala.collection.mutable.Stack(entry)
          while stack.nonEmpty do
            val cur = stack.pop()
            val viaOut = nodeMap.get(cur).toList.flatMap(_.out)
              .filter(_.to != OutEdge.NebulaTarget)
              .flatMap(e => OutEdge.resolveTargetId(nodeMap, e.to))
            val viaDeps = nodeMap.get(cur).toList.flatMap(_.deps).filter(nodeMap.contains)
            val viaIn = inRev.getOrElse(cur, Nil)
            (viaOut ++ viaDeps ++ viaIn).foreach { x =>
              if !seen.contains(x) then
                seen += x
                stack.push(x)
            }
          seen.toSet
        val memberChains = comp.entries.filter(e => reachFrom(e).contains(nodeId)).map(e => s"chain-$e")
        if memberChains.size < 2 then None
        // 主链恒首项 + 全量成员链（主链同时可达时去重保首位）——形态契约见 doc block。
        else Some((comp.id :: memberChains.filterNot(_ == comp.id)).distinct)
      }
    }

  /** 链名三级推导单点（链级抽象 P0 · spec §6.2，后端下发前端零派生）：
    * ① 链上首个 description（成员 createdAt 升序第一个非空）→ ② 首节点 task 预览
    * （首行 ≤NodePayload.TaskPreviewMaxChars 截断，与载荷 taskPreview 同规则）→
    * ③ chain id 兜底。 */
  def chainTitle(members: Iterable[NodeDef], chainId: String): String =
    val sorted = members.toList.sortBy(n => (n.createdAt, n.id))
    val desc = sorted.flatMap(_.description).find(_.trim.nonEmpty).map(_.trim)
    val taskPreview = sorted.headOption
      .flatMap(_.task.map(_.trim).filter(_.nonEmpty))
      .map { t =>
        val firstLine = t.linesIterator.next().trim
        if firstLine.length > NodePayload.TaskPreviewMaxChars then firstLine.take(NodePayload.TaskPreviewMaxChars) + "…" else firstLine
      }
    desc.orElse(taskPreview).getOrElse(chainId)

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
      store = new FlowMapStore(project, statePath, archivePath, Ref.unsafe[IO, FlowMapState](FlowMapState(project = project, updatedAt = 0L)), Ref.unsafe[IO, FlowMapArchive](FlowMapArchive(project = project)), Ref.unsafe[IO, Map[String, ArchiveBatchMeta]](Map.empty))
      initial <- store.loadInitial()
      (arch, batchMetas, convergeBatches) <- store.loadArchive()
      _ <- store.state.set(initial)
      _ <- store.archive.set(arch)
      _ <- store.batches.set(batchMetas)
      // 首写：确保 flow-map.json 存在（验收①「只有 flow-map.json 被 store 写」）；
      // 同时完成存量 JSON 的摘要收敛（水合后内存全文 → 落盘自动拆分）。
      _ <- store.persistState(initial)
      // 归档区迁移收敛：批文件平日只在 archive mutation 时重写——存量污染/迁移在
      // open 时立即收敛一次（幂等；无迁移时零写入）。单文件存量迁移本身已在
      // loadArchive 内落盘并改名；此处只收敛分文件内的 result 摘要污染。
      _ <- if convergeBatches.nonEmpty then store.persistBatchFiles(convergeBatches, arch, batchMetas) else IO.unit
    yield store
