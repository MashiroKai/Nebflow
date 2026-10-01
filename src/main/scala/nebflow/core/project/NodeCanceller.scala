/* 从 NodeEngine 迁出(行为保持重构,2026-09-25)。 */
package nebflow.core.project

import cats.effect.*
import cats.syntax.all.*
import nebflow.core.tools.BgTaskRegistry

private[project] trait NodeCanceller:
  self: NodeEngine =>

  private[project] def markCascadeCancelled(ids: Iterable[String]): IO[Unit] =
    cascadeCancelledIds.update { cur =>
      val merged = (cur ++ ids).distinct
      if merged.size > NodeEngine.CascadeSuppressCap then merged.takeRight(NodeEngine.CascadeSuppressCap) else merged
    }

  /** 节点是否正在运行（ProjectActor 状态查询用）。 */
  def isRunning(nodeId: String): IO[Boolean] = running.get.map(_.contains(nodeId))

  /** 取消运行中节点（NodeCancel/ProjectActor 转发）：触发 cancel 信号 → 停 agent。 */
  def cancelNodeById(nodeId: String): IO[Unit] =
    running.get.map(_.get(nodeId)).flatMap {
      case Some(sig) => sig.complete(()).void
      case None => IO.unit
    }

  /**
   * 死会话 running 节点收殓（清场 c-③，20260903 03:04 清场误杀事故复盘）：
   * status=running 但无在飞执行 fiber（running 表无此节点——会话死于传输中断 /
   * 实例重启后内存态清空）→ 直接终态化 cancelled（cancelNode 全链；2026-09-07
   * 裁定：无 TTL 强制清，死亡现场保留主图待上层裁决）+ 审计事件，修复
   * NodeCancel「cancel signal sent」但状态不落终态的假成功。
   * 误杀防护（硬约束）：有在飞 fiber = 活会话（取消信号可达）→ Left 拒绝，
   * 本方法绝不触碰活会话节点。幂等：非 running → Left（不重复终态化）。
   */
  def reapStaleRunning(
    nodeId: String,
    reason: String = "dead-session reap: status=running but no live execution fiber (dead session / instance restart)",
    source: CancelSource = CancelSource.Engine,
    emitNotify: Boolean = true,
    suppressTargets: Set[String] = Set.empty
  ): IO[Either[String, String]] =
    store.getNode(nodeId).flatMap {
      case None => IO.pure(Left(s"Node '$nodeId' not found"))
      case Some(n) if n.status != NodeLifecycle.Running =>
        IO.pure(Left(s"Node '${n.name}' is not running (status=${n.status}) — nothing to reap"))
      case Some(n) =>
        isRunning(nodeId).flatMap {
          case true =>
            IO.pure(Left(s"Node '${n.name}' has a live execution fiber — use the normal cancel signal, not reap"))
          case false =>
            logger.warn(
              s"Node '${n.name}' ($nodeId) reaped: status=running but no live execution fiber (dead session / instance restart)"
            )
            FlowMapEventLog.append(
              workspace,
              projectName,
              nodeId,
              "reaped",
              "dead running session finalized as cancelled (no live execution fiber; NodeCancel reap)"
            ) *>
              // R2/R2-R7：reap 是**引擎发起**的收殓（无人主动取消）→ source=Engine，
              // reason 明确写出死会话判据（此前 cancelNode 无 reason 形参，此处文本
              // 只存在于 reaped 事件里，节点自身零原因）。
              // chaincancel 批：`reason` / `source` / `emitNotify` / `suppressTargets`
              // 三个新形参**全部有默认值 = 本方法改动前的逐字行为**（NodeCancel 工具
              // 的单参调用零变化，Z4）；链级腿借此复用同一「stale running 收殓」
              // 二分支，同时把链级来源文本/来源码/聚合通知/抑制面传下来。
              cancelNode(nodeId, reason, source, emitNotify = emitNotify, suppressTargets = suppressTargets)
                .as(
                  Right(
                    s"Node '${n.name}' reaped — dead running session finalized as cancelled (retained on map, no TTL)"
                  )
                )
        }
    }

  // ── R2/R3 链级取消族（chaincancel 批 2026-09-17，作者三答 + 设计报告 §2/§3）──
  //
  // 语义（方案 A「级联取代」，作者三答 1）：链级取消 = 该分量内**全部非终态成员**
  // 翻 `cancelled`；上游取消 ⇒ 下游**一律连带** `cancelled`，**不再**留「待承接」
  // 便签（取代面 = 本族的 `pendingSuccession` 写点，见 [[detachCancelledUpstream]]）。
  // 非取消族的写点（`reversePruneReferences` / `detachAbandonedNode`）**零回归**。

  /**
   * **链级取消（R2 公开原语）**：`chainId` → 成员（[[FlowMapStore.chainMembersOf]]
   * 单点现读派生，本节点**不**二次派生）→ 取消集 → 执行 → 聚合通知一次。
   *
   * 返回 `Left(可行动错误码)`：查无链 ⇒ `CHAIN_NOT_FOUND`；单成员链 / 孤立节点 ⇒
   * `CHAIN_SINGLE_MEMBER`（🔴 **禁**静默退化为单节点取消——语义混淆，M12 判据）。
   *
   * @param cascade 引擎腿默认 false 的保守口径**不适用**于本原语：链级取消是用户
   *                显式不可逆意图，默认 **true**（设计 §3.4 O3 表；V1 变异点）。
   */
  def cancelChain(
    chainId: String,
    source: CancelSource,
    reason: String,
    cascade: Boolean = true
  ): IO[Either[String, ChainCancelReport]] =
    store.chainMembersOf(chainId).flatMap {
      case None => IO.pure(Left(ChainCancelErrors.notFound(chainId)))
      case Some(cm) if cm.info.memberIds.size < 2 => IO.pure(Left(ChainCancelErrors.singleMember(chainId)))
      case Some(cm) =>
        // eng-deferred-cancel batch (2026-10-02), K-1 判据③: record the cancel
        // INTENT on the persisted ledger before any node is signalled — the
        // chain reads as "cancellation in progress" for as long as the deferred
        // legs take to finish in-flight tools. Placed here (after member
        // resolution, before execution) and NOT in `cancelNodes`: that overload
        // is also the node-level entry, and a node cancel must never fabricate
        // a chain-level intent. Failures to record are not fatal to the cancel
        // (the cancel itself is the point).
        store.chainLedgerStore.setChainCancelIntent(chainId, System.currentTimeMillis()).attempt.void *>
          cancelNodes(cm.info.memberIds, chainId, cm.title, source, reason, cascade).map(Right(_))
    }

  // ── 链控三原语（chainview 批 2026-10-01：暂停 / 继续 / 取消 + 台账状态面）────
  //
  // 分工（🔴 判据单点，禁第二处派生）：
  //   · **解析**恒走 `FlowMapStore.chainMembersOf`（链号 → 成员；本 trait **零**二次派生）；
  //   · **状态**恒走 `ChainLedgerStore.chainControlOf`（别名逐跳解析 ⇒ 改号后旧号报对态）；
  //   · **闸**恒走 [[chainGateOf]]（唯一的「本节点是否被链控挡住」判据，三个派发收口共用）；
  //   · **取消**恒走既有 [[cancelChain]]（级联闭包 / 通知 / 审计全套复用，本批零重写
  //     —— 重写会打红 `ChainCascadeSpec` 的 Z5/V8 源码级断言：取消族四条腿**零** archive
  //     写面，且网关腿不得二次派生链成员）。

  /**
   * **链控状态读数**（`paused` / `cancelled` / `active`；判据单点
   * [[ChainLedger.statusOf]]）。已挂载项目的 REST/WS 腿都经本方法取态，禁各自读台账。
   */
  def chainControlOf(chainId: String): IO[ChainLedger.ChainControl] =
    store.chainLedgerStore.chainControlOf(chainId)

  /**
   * **派发闸（判据单点）**：`Some(chainId)` ⇒ 本节点归属的链当前处于 `paused` /
   * `cancelled` ⇒ 启动收口应当**静默短路**（零日志、零事件、零状态写）。
   *
   * 数据源与 `startNode` 的链快照同源（`combinedNodes` → `topologicalChains` →
   * `chainIdIn`，与 [[chainContextOf]] 同一判据面：声明恒带、未声明者分量 ≥2 成员）；
   * **不同源的部分**是「归属判定」与「链控状态」两步分开 —— 归属恒来自图派生，状态恒来自
   * 台账热态。无链节点（`None`）恒放行。
   *
   * 🔴 静默是**有意为之**：暂停 / 取消是用户显式意图，其可见性由 REST 响应 + WS 三态帧
   * （`chainState`）承担；把停等写进引擎事件流（`logMutexHold` 形态）会让暂停链的装配
   * 文本混入停等告警，且让「paused 链零派发」的红验断言无法机械判定。
   */
  private[project] def chainGateOf(nodeId: String): IO[Option[String]] =
    store.combinedNodes.flatMap { combined =>
      val chains = FlowMapStore.topologicalChains(combined.values)
      FlowMapStore.chainIdIn(combined, chains, nodeId) match
        case None => IO.pure(None)
        case Some(cid) =>
          store.chainLedgerStore.snapshot.map(st => if ChainLedger.blocksDispatch(st, cid) then Some(cid) else None)
    }

  /**
   * **链控闸 · 批量形态**（判据与 [[chainGateOf]] 逐字同源，只把「逐节点一次全量分量派生」
   * 收敛成「整张图一次派生」）：返回当前被链控挡住的**全部节点 id**。
   *
   * 🔴 存在的理由 = 性能纪律（`chainContextOf` 头注 §9.2：`topologicalChains` 是 O(N+E)
   * 全量重算，**禁止进热路径**）：`settleRunnableSweep` 每 30s 对**全部** pending/wiring
   * 节点做资格判定，逐节点调 [[chainGateOf]] 会把一拍放大成 O(N·E)。本方法供该唯一热路径
   * 消费；`settleTo` / `startNode` 两处是单点判定，直调 [[chainGateOf]] 即可。
   *
   * 判据面与 [[chainGateOf]] 同源：`combinedNodes` → `topologicalChains` → [[FlowMapStore.chainVisible]]
   * 门槛（声明恒带 / 派生分量 ≥2 成员）+ [[ChainLedger.blocksDispatch]]。
   */
  private[project] def chainGateNodeIds(combined: Map[String, NodeDef]): IO[Set[String]] =
    store.chainLedgerStore.snapshot.map { st =>
      FlowMapStore
        .topologicalChains(combined.values)
        .filter(c => FlowMapStore.chainVisible(combined, c) && ChainLedger.blocksDispatch(st, c.id))
        .flatMap(_.memberIds)
        .toSet
    }

  /**
   * **暂停链**（原语 1）：链台账记 [[ChainLedger.StatusPaused]] ⇒ [[chainGateOf]] 对所有
   * 成员返回 `Some(chainId)` ⇒ **settle sweep / barrier 结算 / 启动收口三处零后续派发**。
   *
   * 🔴 **运行中节点跑完自然停**（作者默认语义：暂停不是 cancel，禁发明「暂停即杀运行中
   * 节点」——杀运行中节点是 [[cancelChain]] 的语义，两原语**不得互相借鉴**）。因此本方法
   * 不触碰任何节点状态、不发任何信号、零 side effect（除台账一行）。
   *
   * 幂等：对已暂停链重复调用 ⇒ 台账零写（[[ChainLedgerStore.setChainControl]] 的无变化分支），
   * 返回态照旧 `paused`。可行动错误：链不存在 ⇒ `CHAIN_NOT_FOUND`；单成员/孤立
   * ⇒ `CHAIN_SINGLE_MEMBER`（与 [[cancelChain]] 同判据：链级原语不静默降级为节点级）。
   */
  def pauseChain(chainId: String, now: Long = System.currentTimeMillis()): IO[Either[String, ChainLedger.ChainControl]] =
    store.chainMembersOf(chainId).flatMap {
      case None => IO.pure(Left(ChainCancelErrors.notFound(chainId)))
      case Some(cm) if cm.info.memberIds.size < 2 => IO.pure(Left(ChainCancelErrors.singleMember(chainId)))
      case Some(_) =>
        store.chainLedgerStore.setChainControl(chainId, ChainLedger.StatusPaused, now).map(Right(_))
    }

  /**
   * **继续链**（原语 2）：清 `paused`（**只清暂停**；`cancelled` 不动）。
   *
   * 🔴 对已取消链 ⇒ `Left(CHAIN_CANCELLED)`：取消是**不可逆**的（级联闭包已把成员翻终态，
   * 「继续」既不可能也不该有语义）。**禁**静默返回 active —— 那会让调用方以为链又能派发了。
   *
   * 幂等：对活跃链 resume ⇒ 台账零写，返回态 `active`。恢复后 settle sweep 当轮即恢复
   * 正常结算（闸是**现读**台账、非一次性闩 ⇒ 无需任何清账动作）。
   */
  def resumeChain(chainId: String, now: Long = System.currentTimeMillis()): IO[Either[String, ChainLedger.ChainControl]] =
    store.chainLedgerStore.chainControlOf(chainId).flatMap { cur =>
      if cur.status == ChainLedger.StatusCancelled then
        IO.pure(Left(ChainCancelErrors.alreadyCancelled(chainId)))
      else
        store.chainMembersOf(chainId).flatMap {
          case None => IO.pure(Left(ChainCancelErrors.notFound(chainId)))
          case Some(cm) if cm.info.memberIds.size < 2 => IO.pure(Left(ChainCancelErrors.singleMember(chainId)))
          case Some(_) =>
            store.chainLedgerStore.setChainControl(chainId, ChainLedger.StatusActive, now).map(Right(_))
        }
    }

  /**
   * **取消链 + 台账留痕**（原语 3；链控三原语的 REST/WS 入口专用）：复用既有 [[cancelChain]]
   * 全套语义（成员解析 → 级联闭包 → 逐节点终态化 → 聚合通知 → `chain-cancelled` 审计），
   * **仅在全成功后**追加一行台账状态（`cancelledChains`）。
   *
   * 顺序是语义：先取消后记账 —— 若取消腿失败（`Left`），台账**不写**（不谎报「已取消」）。
   * 取消腿返回 `Right` 时其成员集必已全终态，故此处的台账写是**事后留痕**而非状态推手
   * （`chainGateOf` 对 cancelled 链同样返回 `Some`，但此刻已无 pending 成员可挡）。
   */
  def cancelChainAndRecord(
    chainId: String,
    source: CancelSource,
    reason: String,
    cascade: Boolean = true,
    now: Long = System.currentTimeMillis()
  ): IO[Either[String, (ChainCancelReport, ChainLedger.ChainControl)]] =
    cancelChain(chainId, source, reason, cascade).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(rep) =>
        store.chainLedgerStore.setChainControl(chainId, ChainLedger.StatusCancelled, now).map(c => Right((rep, c)))
    }

  /**
   * **节点级取消 + 级联（R3 公开原语）**：`ids` 显式种子集（节点级 = 单节点；
   * 链级腿经 [[cancelChain]] 复用本方法，种子 = 链成员集）。
   *
   * 本方法即 R3 的落点：种子 ∪ 级联闭包（[[referencesOf]] BFS，**遇终态即停**、
   * 环路由 visited 集天然收敛、`:loop` 回边不作传导边）→ 逐节点执行 → 聚合通知。
   */
  def cancelNodes(ids: List[String], source: CancelSource, reason: String, cascade: Boolean): IO[ChainCancelReport] =
    cancelNodes(ids, "", "", source, reason, cascade)

  private[project] def cancelNodes(
    ids: List[String],
    chainId: String,
    chainTitle: String,
    source: CancelSource,
    reason: String,
    cascade: Boolean
  ): IO[ChainCancelReport] =
    // 链级来源文本（作者工程面自决：**不扩** `CancelSource` 值域，链级信息走 ① 节点
    // result 文本 ② `chain-cancelled` 审计事件 ③ 通知文本头）。形如
    // `chain-cancel chain=chain-n-x cascade=true: <reason>` ⇒ 节点 result =
    // `cancelled[source=user]: reason=chain-cancel chain=chain-n-x cascade=true: …`
    // （沿 `cancelNode` 的 `rendered` 形态，机械可判：result 含 `chain-cancel chain=<id>`）。
    val origin = if chainId.nonEmpty then s"chain-cancel chain=$chainId cascade=$cascade: $reason" else reason
    store.snapshot.flatMap { snap =>
      val seeds = ids.distinct
      // 取消集 = **显式枚举**的非终态集（🔴 不是 `Terminal` 取反——`Terminal` 含 blocked，
      // 见 `NodeLifecycle.ChainCancelScope` 头注；作者三答 2）+ 级联闭包（R3）。
      val seedSet =
        seeds.filter(id => snap.nodes.get(id).exists(n => NodeLifecycle.ChainCancelScope.contains(n.status))).toSet
      val cascadeSet = if cascade then cascadeClosure(snap, seedSet) else Set.empty[String]
      val cancelIds: Set[String] = seedSet ++ cascadeSet
      // 分区（C7）：`cancelled ⊎ preserved ⊎ skipped` == `seeds` 全集（链级入口下
      // seeds = memberIds ⇒ C7 的机械判据即链级取消的成员覆盖不变量）。
      // `cancelled` = **本次实际翻 cancelled 的全部节点**（含级联新增的非种子节点
      // ——R3 的可观测面，设计 §2.1「实际翻 cancelled 的节点」）；`preserved` /
      // `skipped` 只覆盖声明成员集里未被取消的那些。
      val doomed = cancelIds.toList
      val preserved = seeds.filterNot(cancelIds.contains).flatMap { id =>
        snap.nodes.get(id).map { n =>
          (id, n, if NodeLifecycle.Terminal.contains(n.status) then "terminal" else "terminal-boundary")
        }
      }
      val skipped = seeds
        .filterNot(cancelIds.contains)
        .filterNot(snap.nodes.contains)
        .map(id => ChainCancelEntry(id, "", "", "not-in-active-region"))
      if doomed.isEmpty then
        // 幂等出口（C6：第二调用 == 0 帧 == 0 注入 == 0 审计）：零写、零信号、零通知。
        IO.pure(
          ChainCancelReport(
            chainId = chainId,
            chainTitle = chainTitle,
            preserved =
              preserved.map { case (id, n, why) => ChainCancelEntry(id, n.name, n.status, why) }.sortBy(_.nodeId),
            skipped = skipped.sortBy(_.nodeId)
          )
        )
      else
        for
          // ① 先写全成员 `notifySentAt`（设计 §2.3-1 / D1 单账本）：**必须在**任何取消
          //    动作之前——有在飞 fiber 的成员只收到取消信号，其 `cancelled` 终态由既有
          //    桥/收殓腿异步落盘，届时逐节点 `notifyTerminal` 的 `markerEmpty` 恒 false
          //    ⇒ 结构性不发（C2：注入计数与 N 无关、与信号/结果竞态无关）。
          _ <- dispatchNotify.markNotified((cancelIds ++ seeds).toList.sorted)
          _ <- markCascadeCancelled(cancelIds)
          signalledPairs <- doomed.traverse(id => isRunning(id).map(id -> _))
          signalledSet = signalledPairs.filter(_._2).map(_._1).toSet
          // ② 执行：按 `createdAt` **逆序**（sink 先，设计 §2.1-4）。顺序无关性由
          //    `suppressTargets`（传取消全集）+ `cascadeCancelledIds` 双保险承担（M4）。
          _ <- doomed
            .sortBy(id => (-snap.nodes(id).createdAt, id))
            .traverse_(id => executeCancel(id, signalledSet.contains(id), origin, source, cancelIds))
          waiters <- chainWaiters(cancelIds)
          after <- store.snapshot
          cancelledEntries = doomed
            .map { id =>
              val n = snap.nodes(id)
              ChainCancelEntry(id, n.name, n.status, "cancelled", signalledSet.contains(id))
            }
            .sortBy(_.nodeId)
          preservedEntries = preserved
            .map { case (id, n, why) =>
              ChainCancelEntry(id, n.name, n.status, why)
            }
            .sortBy(_.nodeId)
          // ③ 聚合通知腿（唯一注入点）：一次 `trigger` + 一条 `chain-cancelled` 审计 +
          //    1 个 `Cancelled` 预算单位；**不进** `cancelledAttempt`（窗口/cooldown 零触碰）。
          injected <- dispatchNotify.notifyChainCancelled(
            chainId = chainId,
            chainTitle = chainTitle,
            source = source,
            reason = reason,
            memberIds = (cancelIds ++ seeds).toList.sorted,
            cancelled = cancelledEntries,
            preserved = preservedEntries,
            skipped = skipped.sortBy(_.nodeId),
            waiters = waiters
          )
        yield ChainCancelReport(
          chainId = chainId,
          chainTitle = chainTitle,
          cancelled = cancelledEntries,
          preserved = preservedEntries,
          skipped = skipped.sortBy(_.nodeId),
          prunedReferrers = NodeEngine.prunedReferrersBetween(snap, after, cancelIds),
          injected = injected,
          notified = injected > 0
        )
      end if
    }

  end cancelNodes

  /**
   * 单节点执行（两分支与 `NodeCancel` 工具逐字同款）：
   *   - 有在飞 fiber ⇒ 只发取消信号（终态由既有桥/收殓腿落盘；`signalled = true`）；
   *   - 无在飞 fiber 的 stale running ⇒ [[reapStaleRunning]]（同步收殓，链级来源文本）；
   *   - 其余非终态（pending/wiring/blocked/interrupted）⇒ **新增写路径**：直接
   *     [[cancelNode]]（今日 `NodeCancel` 工具对非 running 是 no-op，链级腿必须补）。
   * 三者一律 `emitNotify = false`（逐节点通知由聚合腿单次收口）+ `suppressTargets = 取消全集`。
   */
  private[project] def executeCancel(
    nodeId: String,
    signalled: Boolean,
    reason: String,
    source: CancelSource,
    suppress: Set[String]
  ): IO[Unit] =
    if signalled then cancelNodeById(nodeId)
    else
      store.getNode(nodeId).flatMap {
        case Some(n) if n.status == NodeLifecycle.Running =>
          reapStaleRunning(nodeId, reason, source, emitNotify = false, suppressTargets = suppress).void
        case Some(_) =>
          cancelNode(nodeId, reason, source, emitNotify = false, suppressTargets = suppress)
        case None => IO.unit
      }

  /**
   * 级联腿的等待者清单（现读）：状态 ∈ {pending, wiring} 且仍以 in/deps/pendingSuccession
   * 引用取消集的节点——通知文本「受影响下游等待者」栏的数据源。
   *
   * ③（chainmodel 批一）：`chain:<id>` 引用展开为目标链成员集后判定（目标链里有被取消
   * 成员 ⇒ 该下游的依赖永久不可满足 ⇒ 它就是受影响等待者）；解析按**活动区**表做
   * （与通知面同区）。零链引用时逐字等于改造前行为。
   */
  private[project] def chainWaiters(cancelIds: Set[String]): IO[List[String]] =
    store.snapshot.map { s =>
      val chainsById =
        if !s.nodes.valuesIterator.exists(_.deps.exists(FlowMapStore.isChainRef)) then Map.empty[String, ChainInfo]
        else FlowMapStore.topologicalChains(s.nodes.values).map(c => c.id -> c).toMap
      def depsRefers(n: NodeDef): Boolean =
        n.deps.exists { dep =>
          if FlowMapStore.isChainRef(dep) then
            chainsById.get(FlowMapStore.chainRefTarget(dep)).exists(_.memberIds.exists(cancelIds.contains))
          else cancelIds.contains(dep)
        }
      s.nodes.values
        .filter(n =>
          (n.status == NodeLifecycle.Pending || n.status == NodeLifecycle.Wiring) &&
            (n.in.exists(cancelIds.contains) || depsRefers(n) ||
              n.pendingSuccession.exists(cancelIds.contains))
        )
        .map(_.id)
        .toList
        .sorted
    }

  /**
   * **级联闭包（R3 传递规则，单点）**：种子上做 [[referencesOf]] BFS 到收敛，**只把
   * 非终态节点纳入传递**（`NodeLifecycle.ChainCancelScope`）——遇到 `completed` /
   * `failed` / `cancelled` 即**停**（防过杀：completed 的结果已投递、其下游输入已到位，
   * 与本次取消无因果依赖；failed/cancelled 的下游已由既有 D5 零结算/停等纪律持有）。
   *
   * 环路/自引用天然收敛：`visited`（含种子）+ `referencesOf` 过滤 `id != self`；弱连通
   * 分量本身用无向 BFS（`FlowMapStore.topologicalChains`）⇒ 回边（含 `:loop`）不会死循环。
   *
   * **跨分量禁行（M6）**：并集只沿图引用（`in`/`out`/`deps`/`pendingSuccession`）走，
   * 而四类引用都是**图的边**（`topologicalChains` 对任何边两侧都建邻接，`pendingSuccession`
   * 亦只由既有摘除腿在这些边上写入）⇒ 「存在引用关系 ⇒ 必同分量」；故闭包**恒不跨链**。
   * 例外声明（设计 §3.2）：日后若新增**非图引用类**（如 retry 可跨子图回跳）必须显式
   * 加入 [[referencesOf]] 并重开本判定。
   *
   * 🔴 **例外已发生（chainmodel 批一 2026-09-19 登记）**：`deps` 自本批起**不再是分量边**
   * （`FlowMapStore.topologicalChains` 的成员边收窄为 in ∪ out），且新增了 `chain:<id>`
   * **非节点引用**（跨链依赖原语）⇒ 上述「四类引用都是图的边 ⇒ 必同分量」的前提**已失效**：
   * 闭包**可以跨分量**（经 `n.deps` / `chain:<id>` 引用）。处置 = [[referencesOf]] 显式承接
   * （字面 deps 与链引用同判据入闭包）+ 本判定按「引用面（与分量解耦）」重开：
   * **取消面只认引用，不认归属**——链归属重构不改取消语义（取消集与改造前逐字同构：
   * 改造前 deps 引用者必同分量、本来就在闭包里；改造后仍经 [[referencesOf]] 入闭包）。
   */
  private[project] def cascadeClosure(snapshot: FlowMapState, seed: Set[String]): Set[String] =
    def go(frontier: Set[String], visited: Set[String]): Set[String] =
      if frontier.isEmpty then visited
      else
        val next = frontier
          .flatMap(id => referencesOf(snapshot, id))
          .filterNot(visited.contains)
          .filter(id => snapshot.nodes.get(id).exists(n => NodeLifecycle.ChainCancelScope.contains(n.status)))
        go(next, visited ++ next)
    go(seed, seed)

  /**
   * **带级联权限守卫的终态写入口（判据 M8 的机械承担点，chaincancel 批 2026-09-17）**：
   * 判据 = **「请求级联」∧「L3 硬恢复中间态」** ⇒ 抛（fail-closed）。
   *
   * 用途 = L3 硬恢复腿（作者三答 4 钉死的硬连接）：该腿以
   * `val cascadeRequested = NodeEngine.l3CascadeAllowed(deferDetach)` 派生出声明的级联
   * 旗标（L3 中间态 ⇒ false），使「deferDetach ⇒ 不级联」从注释里的隐式约定变成
   * **会失败的守卫**——日后若有人把 L3 腿改接上链级/级联写路径（或把该绑定换成字面量
   * true），本守卫立即抛出，而不是静默毁掉 resume 复活的拓扑。
   *
   * 🔴 **两条腿共用本调用点**（易错点，2026-09-17 实测踩到过一次）：桥的 Cancelled 出口
   * 同时服务 L3 硬恢复（`deferDetach=true`）与 L3 之外的取消（`deferDetach=false`：watcher
   * giveUp / AgentControl / 面板 NodeCancel / 父会话级联）⇒ 守卫**只能**在 `l3Intermediate`
   * 为真时才有资格抛；把判据写成「cascadeAllowed 为真即抛」会让**全部非 L3 取消路径**
   * 静默失败（节点滞留 running，零终态）。
   * 其余语义与 [[cancelNode]] 逐字相同（本批不给 L3 腿接任何级联能力）。
   */
  private[project] def cancelNodeGuarded(
    nodeId: String,
    reason: String,
    source: CancelSource,
    detach: Boolean,
    notify: Boolean,
    cascadeRequested: Boolean,
    l3Intermediate: Boolean
  ): IO[Unit] =
    if cascadeRequested && l3Intermediate then
      IO.raiseError(
        new IllegalStateException(
          "cascade is forbidden on this leg (L3 hard-recovery intermediate state, chaincancel §6-M8)"
        )
      )
    else cancelNode(nodeId, reason, source, detach = detach, notify = notify)

  /**
   * bg-wait 标注写点（僵尸收敛批 2026-09-06，作者「首要缺口 = 补显示」）：节点完成
   * 闸（bgtask-completion-gate 批）在持留等待后台任务时把 node `bgWait` 置为在途
   * 等待型任务快照、全部清空/终态化时清 None——NodePayload 条件字段随之带/不带，
   * 前端据此标「等待后台任务」徽标（与真僵尸区分，避免把设计内等待误判成
   * dead-session running）。仅对 running 节点写（fresh 守卫）；状态已变 → 拒写
   * （R2 竞态纪律），flow-map.json 不残留过期 bgWait。值未变 → 不写不 event
   * （幂等：终态化清 None 时若原本就 None，不重复发 nodeUpdated）。
   */
  private[project] def setNodeBgWait(nodeId: String, waiting: List[BgTaskRegistry.ActiveTask]): IO[Unit] =
    val desc =
      if waiting.isEmpty then None
      else Some(s"${waiting.size} background task(s): " + waiting.map(t => s"'${t.description}'").mkString(", "))
    store
      .mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) if fresh.status == NodeLifecycle.Running && fresh.bgWait != desc =>
            st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(bgWait = desc)))
          case _ => st
      }
      .flatMap { s =>
        s.nodes.get(nodeId).filter(_.bgWait == desc).traverse_(emitUpdated)
      }

  /**
   * 未申报计时起表（noderpt 批 A 段 2026-09-11）：**只置不重**——首次未申报在手时置
   * `reportPendingSince`（+ 计数归零）；已置则原样保留，后续 `Completed`（含提醒轮自身
   * 产生的 Completed）与 `NodeMessage` 重入都不改起点（代裁 4：否则计时可被无限拖延）。
   * 会话身份守卫：只在「本 fiber 的会话仍是该节点的当前会话」时置（`sessionRef` 同值），
   * 避免 resume/reactivate 换会话后旧 fiber 的判定落到新会话上。值真的变化才 emitUpdated
   * （载荷条件字段随之带；未变化不刷帧）。
   */
  private[project] def markReportPendingIfAbsent(nodeId: String, sessionId: String): IO[Unit] =
    IO(System.currentTimeMillis()).flatMap { now =>
      store
        .mutateWithResult { st =>
          st.nodes.get(nodeId) match
            case Some(fresh)
                if fresh.status == NodeLifecycle.Running
                  && fresh.sessionRef.forall(_ == sessionId)
                  && fresh.reportPendingSince.isEmpty =>
              (
                st.copy(nodes =
                  st.nodes.updated(nodeId, fresh.copy(reportPendingSince = Some(now), reportReminderCount = 0))
                ),
                true
              )
            case _ => (st, false)
        }
        .flatMap {
          case (s, true) => s.nodes.get(nodeId).traverse_(emitUpdated)
          case (_, false) => IO.unit
        }
    }

  /**
   * 未申报计时清表（唯一清表条件 = 该会话任一 `node_report` 申报；终态/挂起出口与新一轮
   * 翻转同点清零，防跨轮累计）。`sessionId = Some` 时只在「本 fiber 的会话仍是该节点当前
   * 会话」时清——resume 换会话后老 fiber 的收尾不得误清新会话的计时。
   */
  private[project] def clearReportPending(nodeId: String, sessionId: Option[String]): IO[Unit] =
    store
      .mutateWithResult { st =>
        st.nodes.get(nodeId) match
          case Some(fresh)
              if fresh.reportPendingSince.isDefined
                && sessionId.forall(sid => fresh.sessionRef.forall(_ == sid)) =>
            (
              st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(reportPendingSince = None, reportReminderCount = 0))),
              true
            )
          case _ => (st, false)
      }
      .flatMap {
        case (s, true) => s.nodes.get(nodeId).traverse_(emitUpdated)
        case (_, false) => IO.unit
      }

  // ── 终态延迟销毁窗口（noderpt 批 B 段 2026-09-11 作者裁定：一律存活 30 分钟再销毁）──
  //
  // 语义（作者裁定形态 (b)，逐条）：
  //   ① 终态时刻**只登记**，不杀进程：`destroyAt = T + destroyWindowMs` 落节点持久字段；
  //   ② 窗口内：进程/任务照跑、输出照写、**允许读取取证**，仅**禁止新 spawn**（新后台
  //      任务 / 新会话——拦截表 = `BgTaskRegistry.finalizedSessions`，落点见该对象头注）；
  //   ③ 到点由 `sweepDestroyWindows`（`ProjectActor.TtlTick` 30s）执行 `reclaimSession`
  //      （杀进程树 + 注销 registry + 逐条 `finalizeTask` + 释放 `ShellSession.sessions`
  //      条目 + WS `backgroundTaskUpdate(status="cancelled")` 帧）+ 清 `destroyAt`；
  //   ④ 挂起腿不登记（中途换会话，进程即时收割不变）；
  //   ⑤ `settleStaleRunningNodes` 的「bg 等待不算死」判据保留不动（代裁 6）；
  //   ⑥ 每条终态出口都留可事后对齐的痕迹：`destroyAt` 字段 + `node-destroy-scheduled`
  //      / `node-destroyed` 事件（failed/cancelled 与 completed/blocked 口径一致）。
  //
  // 与既有「即时收割」的差别只在**时点**：收殓动作集合逐字相同（同一个
  // `BgTaskRegistry.reclaimSession`），从「终态瞬间 fork」移到「窗口到期扫描」。

  /**
   * 节点名下的会话清单（销毁窗口的收殓对象）：普通节点 = `sessionRef`；Loop 节点 =
   * worker（`sessionRef`）+ verify（`sessionRefVerify`）双会话（裁定 B 双会话贯穿）。
   */
  private[project] def destroyTargetSessions(n: NodeDef): List[String] =
    (n.sessionRef.toList ++ n.sessionRefVerify.toList).map(_.trim).filter(_.nonEmpty).distinct

  /**
   * 终态销毁窗口登记（终态写点之后调用——**只登记不杀进程**）。
   *
   * 幂等：`destroyAt` 已置 ⇒ 不改字段、不重复发事件（同节点二次终态化/重复调用零副作用）。
   * 安全：节点**非终态**（未真正终态化 / 已被重激活回 Running）⇒ 拒登记——窗口只属于
   * 终态，绝不把 Running 节点的进程放进销毁计划。
   * 副作用：① 落 `destroyAt` 持久字段（跨宿主重启存活 ⇒ 到点仍由扫描腿兜底）；
   * ② 会话登记进 `BgTaskRegistry` 禁 spawn 表；③ `node-destroy-scheduled` 事件 + 日志。
   */
  private[project] def scheduleDestroy(nodeId: String, sessions: List[String], cause: String): IO[Unit] =
    IO(System.currentTimeMillis()).flatMap { now =>
      val at = now + destroyWindowDurationMs
      store
        .mutateWithResult { st =>
          st.nodes.get(nodeId) match
            case Some(fresh) if NodeLifecycle.Terminal.contains(fresh.status) && fresh.destroyAt.isEmpty =>
              (st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(destroyAt = Some(at)))), true)
            case _ => (st, false)
        }
        .flatMap { case (s, registered) =>
          if !registered then IO.unit
          else
            s.nodes.get(nodeId).traverse_ { n =>
              val sids = sessions.map(_.trim).filter(_.nonEmpty).distinct
              sids.traverse_(sid => BgTaskRegistry.markSessionFinalized(sid, at)) *>
                emitUpdated(n) *>
                FlowMapEventLog.append(
                  workspace,
                  projectName,
                  nodeId,
                  NodeEngine.DestroyScheduledEventType,
                  s"terminal destroy scheduled: session(s) ${sids.mkString(",")} kept alive for " +
                    s"${destroyWindowDurationMs / 1000}s (read-only evidence window; new spawns rejected) — " +
                    s"destroyAt=$at; cause: ${cause.take(160)}"
                ) *>
                logger.info(
                  s"Node '${n.name}' ($nodeId) terminal (${n.status}) — destroy window opened: sessions " +
                    s"${sids.mkString(",")} stay alive until $at (${destroyWindowDurationMs / 1000}s)"
                )
            }
        }
    }

  /**
   * `destroyAt` 字段清除（**双区单点**，批 F1' 修复第 2 轮 2026-09-12）：活动区优先、
   * 归档区兜底——归档成员**不得复活进活动区**（既有归档写纪律，`NodeTools.setOut`
   * 归档分支先例），故只改归档副本的字段。
   *
   * CAS：只在字段仍 `== expected`（即本次扫描读到的登记值）时清除 ⇒ 并发同拍只留一个
   * 赢家，输家得 `None`（不重复 `reclaimSession` 已完成、也不重复发事件）。
   * 返回 `Some((清点后的节点, 是否活动区))`——调用方据此决定要不要发 `nodeUpdated`
   * （归档区清点不发，见 [[destroyNodeSessions]] 注）。
   */
  private[project] def clearDestroyAt(nodeId: String, expected: Option[Long]): IO[Option[(NodeDef, Boolean)]] =
    if expected.isEmpty then IO.pure(None)
    else
      store
        .mutateWithResult { st =>
          st.nodes.get(nodeId) match
            case Some(fresh) if fresh.destroyAt == expected =>
              (st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(destroyAt = None))), true)
            case _ => (st, false)
        }
        .flatMap {
          case (s, true) => IO.pure(s.nodes.get(nodeId).map(_ -> true))
          case _ =>
            store
              .mutateArchiveWithResult { ar =>
                ar.nodes.get(nodeId) match
                  case Some(fresh) if fresh.destroyAt == expected =>
                    (ar.copy(nodes = ar.nodes.updated(nodeId, fresh.copy(destroyAt = None))), true)
                  case _ => (ar, false)
              }
              .map { case (ar, cleared) => if cleared then ar.nodes.get(nodeId).map(_ -> false) else None }
        }

  /**
   * 窗口撤销（异常残留清点）：节点带着 `destroyAt` 却已非终态（窗口内被重激活/重跑）
   * ⇒ 清字段 + 解禁 spawn + `node-destroy-withdrawn` 事件。正常路径在翻转点已清零，
   * 本方法只作扫描腿的防御兜底。
   *
   * 双区感知（F1' 第 2 轮）：字段清除经 [[clearDestroyAt]]——活动区优先、归档区兜底；
   * 归档成员不发 `nodeUpdated`（同 [[destroyNodeSessions]]：nodeRemoved 墓碑已把该节点
   * 移出主图，发 payload 会把它拉回派生图）。
   */
  private[project] def withdrawDestroyWindow(n: NodeDef, reason: String): IO[Unit] =
    val sids = destroyTargetSessions(n)
    clearDestroyAt(n.id, n.destroyAt).flatMap {
      case None => IO.unit
      case Some((fresh, inActive)) =>
        sids.traverse_(BgTaskRegistry.reopenSession) *>
          IO.whenA(inActive)(emitUpdated(fresh)) *>
          FlowMapEventLog.append(
            workspace,
            projectName,
            n.id,
            NodeEngine.DestroyWithdrawnEventType,
            s"destroy window withdrawn (node no longer terminal): $reason"
          ) *>
          logger.warn(s"Node '${n.name}' (${n.id}) destroy window withdrawn: $reason")
    }

  end withdrawDestroyWindow

  /**
   * 终态销毁窗口扫描腿（`ProjectActor.TtlTick` 30s 驱动，与 `settleStaleRunningNodes` /
   * `remindUnreportedNodes` 同族——复用既有心跳点零新调度器）。
   *
   * 两件事：① **表项自愈**——带 `destroyAt` 的节点逐个重建禁 spawn 表（宿主重启后
   * 内存表为空，本腿一拍内恢复，禁 spawn 面不因重启长期失守）；② **到点销毁**——
   * `destroyAt <= now` 且节点仍为终态 ⇒ `destroyNodeSessions`；节点已非终态 ⇒
   * `withdrawDestroyWindow`（窗口随重激活撤销）。
   *
   * **读面 = 活动区 ∪ 归档区**（批 F1' 修复第 2 轮 2026-09-12，复核 D1 根治）：链级
   * 归档（TtlTick 同拍，链全终态即整链出库）会把**窗口未收殓**的成员搬进归档区；本腿
   * 若只读活动区 snapshot，则 ① 到点永不收殓（进程 / persistent·bg 任务永久泄漏）、
   * ② 宿主重启后的禁 spawn 表自愈也读不到它——两处读面都漏。故本腿在两个读面上都
   * 覆盖归档区：字段清除落在**归档副本**上（不复活进活动区，见 [[clearDestroyAt]]），
   * `node-destroyed` 事件照写（归档侧可见性载体 = 事件 + 日志）。
   * 归因对照：与归档资格解耦——`chainArchivable` **不看** `destroyAt`（撤销 F1 的前置
   * 拒收）⇒ 归档语义与销毁窗口正交，二者都不牺牲。
   *
   * 幂等：字段已清 ⇒ 不再入选（二次调用零副作用）；`reclaimSession` 本体亦幂等
   * （会话无进程/无任务时 no-op、已注销二次无副作用）。best-effort：单节点失败只 WARN，
   * 不影响其它节点与后续 TTL sweep。
   */
  def sweepDestroyWindows(): IO[Unit] =
    (for
      s <- store.snapshot
      a <- store.archiveSnapshot
      // 双区并集（id 冲突 = 崩溃窗口「活动+归档双在」⇒ 取活动区副本，权威副本口径与
      // FlowMapStore.combinedNodes 同源）
      registered = (s.nodes.values ++ a.nodes.values.filterNot(n => s.nodes.contains(n.id))).toList
        .filter(_.destroyAt.isDefined)
      // ① 表项自愈（重启后重建；幂等覆盖）
      _ <- registered.traverse_ { n =>
        val at = n.destroyAt.getOrElse(0L)
        destroyTargetSessions(n).traverse_(sid => BgTaskRegistry.markSessionFinalized(sid, at))
      }
      // ② 到点处置（逐个复核**最新**状态，避免用陈旧快照做终态判断；`findNode` =
      //    活动区优先 + 归档区兜底，双区同一判据单点）
      _ <- IO(System.currentTimeMillis()).flatMap { now =>
        registered.filter(_.destroyAt.exists(_ <= now)).traverse_ { snap =>
          store.findNode(snap.id).flatMap { latest =>
            latest match
              case None => IO.unit // 双区皆无（并发移除）→ no-op
              case Some(fresh) if fresh.destroyAt.isEmpty => IO.unit // 已被其它路径处置
              case Some(fresh) if !NodeLifecycle.Terminal.contains(fresh.status) =>
                withdrawDestroyWindow(
                  fresh,
                  s"node status is '${fresh.status}' at window expiry (reactivated/re-run inside the window) — spawn ban lifted"
                )
              case Some(fresh) => destroyNodeSessions(fresh)
          }
        }
      }
    yield ()).handleErrorWith(e => logger.warn(s"destroy-window sweep failed: ${e.getMessage}"))

  /**
   * 到点对称收殓（③ 的执行体）：对节点名下全部会话执行 `reclaimSession`
   * （= 杀进程树 + 注销 BgTaskRegistry + 逐条 `BgTaskOutputStore.finalizeTask("cancelled")`
   * + 释放 `ShellSession.sessions` 条目 + WS `backgroundTaskUpdate(status="cancelled")` 帧，
   * 见 `shell.scala#killSessionProcesses` 与 `BgTaskRegistry#reclaimSession`），
   * 再清 `destroyAt` + 写 `node-destroyed` 事件。
   *
   * 双区（F1' 第 2 轮）：字段清除经 [[clearDestroyAt]]——活动区命中即清活动区（并发
   * `nodeUpdated`，前端窗口字段即刻消失）；**归档区命中则只清归档副本**，且**不发
   * `nodeUpdated`**：`nodeRemoved` 墓碑已把归档成员移出主图，发 payload 会把它拉回
   * 派生图（归档成员禁复活纪律）；归档侧可见性由 `node-destroyed` 事件 + 日志承载。
   *
   * 幂等双保险：字段清点用「destroyAt 与本次登记值相同」的 CAS（并发同拍只留一个赢家）；
   * 输家 `reclaimSession` 亦为 no-op。清点后**禁 spawn 表项保留**（死会话不可复活——
   * 合法复活唯一入口 = 节点翻转 Running 时的 `BgTaskRegistry.reopenSession`）。
   */
  private[project] def destroyNodeSessions(n: NodeDef): IO[Unit] =
    val at = n.destroyAt.getOrElse(0L)
    val sids = destroyTargetSessions(n)
    for
      _ <- sids.traverse_(sid => BgTaskRegistry.markSessionFinalized(sid, at))
      _ <- sids.traverse_ { sid =>
        BgTaskRegistry
          .reclaimSession(Some(sid), wsSendFn, rootSessionId)
          .handleErrorWith(e =>
            logger.warn(s"Node '${n.name}' (${n.id}) destroy-window reclaim failed for session '$sid': ${e.getMessage}")
          )
      }
      cleared <- clearDestroyAt(n.id, n.destroyAt)
      _ <- cleared.traverse_ { case (fresh, inActive) =>
        IO.whenA(inActive)(emitUpdated(fresh)) *>
          FlowMapEventLog.append(
            workspace,
            projectName,
            n.id,
            NodeEngine.DestroyedEventType,
            s"terminal destroy window expired: session(s) ${sids.mkString(",")} reclaimed " +
              s"(processes killed + bg tasks finalized + shell sessions released), destroyAt=$at" +
              (if inActive then ""
               else " [archived member: field cleared on the archived copy, node not revived into the active map]")
          ) *>
          logger.info(
            s"Node '${n.name}' (${n.id}) destroy window expired — reclaimed session(s) ${sids.mkString(",")}" +
              (if inActive then "" else " (archived member)")
          )
      }
    yield ()

    end for

  end destroyNodeSessions

  /**
   * 终态写点的计时清表纯函数（noderpt 批 A 段 2026-09-11）：**终态无计时语义** ⇒
   * 状态写点与计时字段同事务清零，持久层不残留「终态节点带待申报计时」的误导态。
   * 为什么不能只靠 [[clearReportPending]]（run fiber 收尾的 `cleanupRunTables`）：
   * 存在**不经 run fiber** 的终态写点——boot 期 `reapStaleRunning`（死会话收殓）、
   * `autoFailDeadRunning`、`mergeBlockedByUpstreamFailure`、NodeCancel 收殓等，
   * 它们的节点从没有 fiber 可跑 finalizer ⇒ 计时会随节点进归档（隔离实例实跑读
   * 数：reap 后归档的 cancelled 节点仍带 `reportPendingSince`/`reportReminderCount`）。
   * 值已清 ⇒ 原样返回（零漂移，不发生无谓写）。
   *
   * **public**（noderpt 批 F3，2026-09-11 复核 D3 修复）：第 7 个写点在另一模块
   * （`NodeTools` 的 `NodeEdit abandon`，`:1181`）⇒ 提为公共单点，跨模块复用同一判据，
   * 防第 8 个写点再漏。纯函数（无 IO、不读 store）——调用方在自己的 mutate 事务内联用。
   */
  def withoutReportPending(n: NodeDef): NodeDef =
    if n.reportPendingSince.isEmpty && n.reportReminderCount == 0 then n
    else n.copy(reportPendingSince = None, reportReminderCount = 0)

  /**
   * 在飞登记三表的对称清理（僵尸收敛批 2026-09-06 清理硬化，根因报告漏洞①）：
   * running / nodeSessions / agentRegistry 三表在 runWithAgent 内登记后，只能由该
   * fiber 自己在 race 落定后清理——fiber 崩溃/被外部 cancel/悬死在 race 时三表泄漏
   * （制造「假活会话」canary → isRunning 恒 true → 误杀防护被击穿，
   * reapStaleRunning/abandon/NodeCancel 全部拒绝，不可回收僵尸）。本方法作为
   * runWithAgent 整体 `.guarantee` finalizer：任意退出路径（正常/崩溃/异常/cancel）
   * 都对称移除。身份感知（防误删竞发赢家）：只在 running 表条目是**本 fiber 的
   * cancelSig** 时移除（LostRace/Aborted 败方自己的 sig 已被分支移除，此处 no-op；
   * 赢家条目保留）；nodeSessions 只在映射到本 sessionId 时移除（nodeId 是共享键，
   * 盲删会误删赢家条目）；agentRegistry 只移除本 sessionId。与既有清理段
   * （:947-953）同点幂等（先到先清，后到 no-op）。
   */
  private[project] def cleanupRunTables(nodeId: String, sessionId: String, cancelSig: Deferred[IO, Unit]): IO[Unit] =
    running.modify { m =>
      if m.get(nodeId).exists(_.eq(cancelSig)) then (m - nodeId, ())
      else (m, ())
    } *>
      nodeSessions.update { m =>
        if m.get(nodeId).contains(sessionId) then (m - nodeId) else m
      } *>
      resources.agentRegistry.update(_ - sessionId) *>
      // blocked 结构化信号批（20260909 spec §5.2 #5④；同日泛化 NodeReportRegistry）：
      // 登记表对称清理——cancelled/failed/异常退出路径未消费的申报在此兜底移除
      // （completed 路径已 drain，此处幂等 no-op）。与 running/nodeSessions 同点清理纪律。
      NodeReportRegistry.remove(sessionId) *>
      // 未申报计时对称清理（noderpt 批 A 段 2026-09-11）：终态/挂起出口清表不累计
      // （挂起恢复后按新会话重新起表；sessionId 守卫防误清 resume 后新会话的计时）。
      clearReportPending(nodeId, Some(sessionId))

  /**
   * 死会话 running 节点的降档处置（killruling 批 2026-09-23 作者裁定 #19）。
   *
   * 🔴 **已降档**：本方法**不再写任何终态**。历史口径（僵尸收敛批 2026-09-06）为
   * 「收敛目标取 failed 而非 cancelled：failed 沿 `deliverFailed` 触发分发器通知
   * （附停等等待者清单，wf1cde E-③）且可 reactivate 修复重跑；cancelled 不投递不通知
   * 且不可重激活」——🔴 **降档后该语义消失**（不再有 failed ⇒ 无分发器 failed 通知、
   * 无「reactivate 修复重跑」的失败驱动）。这正是本批要显式登记的**代价**：
   * 「死会话」这一形态改由 [[remindDeadSession]] 的提醒腿 + barrier 告警腿承载，
   * 处置交人工（或分发器面）。
   *
   * barrier 面（同批处置）：`deliverFailed` 被删 ⇒ 下游不再收 failed ⇒ **barrier 永挂**；
   * 出口 = [[checkBarriersNow]] 的**告警腿**（只写 `barrier-blocked` 事件 + WARN，
   * **不**终态化下游，与 #27 mount-stalled 同族）。
   *
   * fresh 守卫（R2）保留：只在 `status==Running` 时提醒——节点已终态/状态已变 ⇒ 拒写
   * （并发完成/取消不被本路径覆盖）。draining 守卫（中断恢复语义批）逐字保留。
   * 审计事件独立（`dead-session-reaped` **改文案**：不再说 converged-to-failed）。 */
  private[project] def autoFailDeadRunning(nodeId: String, err: String): IO[Unit] =
    // draining 守卫（中断恢复语义批 2026-09-13，spec §2.3-3，与 failNode 头部同款）：
    // 优雅关机窗口内 watchdog 若把「内存态已蒸发」误读成死会话，会把节点的中断现场
    // 误报成失败链（方案 B 的噪音链复发形态）。置位时拒绝。
    if ShutdownState.draining then
      FlowMapEventLog.append(
        workspace,
        projectName,
        nodeId,
        NodeEngine.InterruptedEventType,
        s"dead-session reminder suppressed while draining (graceful shutdown): ${err.take(200)}"
      ) *>
        logger.warn(s"Node $nodeId dead-session reminder suppressed while draining (graceful shutdown): ${err.take(200)}")
    else
      for
        now <- IO(System.currentTimeMillis())
        s <- store.snapshot
        _ <- s.nodes.get(nodeId) match
          case Some(n) if n.status == NodeLifecycle.Running =>
            // 会话 id：内存映射优先、持久 sessionRef 兜底（candidates 解析序同
            // `remindUnreportedNode`）。
            nodeSessions.get.map(_.get(nodeId).orElse(n.sessionRef)).flatMap { sid =>
              emitWithChain("nodeUpdated", nodeId, NodePayload.buildNodeJson(n, now)) *>
                FlowMapEventLog.append(
                  workspace,
                  projectName,
                  nodeId,
                  "dead-session-reaped",
                  s"dead-session node kept Running (no auto-fail, no delivery — killruling ruling #19 downgrade): ${err.take(220)}"
                ) *>
                remindDeadSession(n, sid.getOrElse("<session-gone>"), err) *>
                // R3 保留：下游 barrier **告警腿**（只告警、不终态化——`deliverFailed`
                // 已删，下游不再有 failed 结算，此处即其唯一出口）。
                checkBarriersNow(n.id, cause = "dead-session")
            }
          case _ => IO.unit // 已终态/消失/状态已变 → 拒写（R2 竞态纪律）
      yield ()
end NodeCanceller
