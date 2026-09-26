/* 从 NodeEngine 迁出(行为保持重构,2026-09-25)。 */
package nebflow.core.project

import cats.effect.*
import cats.syntax.all.*

private[project] trait NodeGating:
  self: NodeEngine =>

  /**
   * deps 满足判定（deps 设计 §1.3）：声明式状态查询（幂等、零记账），非 deliveredTo
   * 式事件累计。findNode 归档兜底——TTL 归档不影响「完成」事实（归档上游可触发，
   * 与 D1「归档 completed 上游是唯一投递入口」先例一致）。deps 为空 → 恒满足。
   *
   * ③（chainmodel 批一 2026-09-19）：`deps` 里的 `chain:<id>` 跨链引用展开为目标链
   * **成员集**（判据单点 `FlowMapStore.resolveDepTargets`，数据源 = 合并集 ⇒ 与
   * `findNode` 双区口径同源）——目标链**全体成员 completed** 才满足（成员未全终态 ⇒
   * false ⇒ 本节点不启动，判据③）。🔴 **不可解析的链引用（链号不存在）= 恒不满足**
   * （fail-closed，禁静默当「零成员即满足」通过）；该形态在 `mountStallReason` 里
   * 会被点名（可行动），写路径另有 fail-closed 校验。零链引用时逐字等于改造前行为。
   */
  def depsSatisfied(node: NodeDef): IO[Boolean] =
    store.combinedNodes.flatMap { combined =>
      val targets = FlowMapStore.resolveDepTargets(node.deps, combined)
      if targets.unknownChainRefs.nonEmpty then
        logger.warn(
          s"[$projectName] node '${node.name}' (${node.id}) deps=[${node.deps.mkString(",")}] carries " +
            s"unresolvable chain reference(s) [${targets.unknownChainRefs.mkString(",")}] — the dependency can never " +
            "satisfy (fail-closed); fix the deps ref (chains come from the Flow Map `chains[]` payload)"
        )
        IO.pure(false)
      else
        targets.ids
          .traverse(store.findNode)
          .map(_.forall(_.exists(_.status == NodeLifecycle.Completed)))
    }

  // ── verdict 闸（merge-verdict-gate 批 2026-09-12 作者裁定；**engine-defects #238 泛化
  //    2026-09-15**：闸面从「仅 merge 节点」扩到**全部收口位**）────────────────────────
  //
  // 判据：**节点的 `in ∪ deps` 上游中含 verifier 时，该 verifier 的当下 `lastVerdict`
  // 必须为 `pass`**；否则本节点不可启动、原地保持 pending。把「先验（判词）后交付」从
  // 节点自觉变成机制保证（来源实证：`perm-global-merge`(n-9e9c385d)，其 in 含 verifier
  // `n-6f86d028`，该 verifier 已报 lastVerdict=fail，merge 仍被 `settleRunnableSweep`
  // 拉起并启动）。
  //
  // **#238 泛化的理由（缝 = O-1；只读侦察 `.nebflow/reports/20260914_verdict-routing-recon.md`）**：
  // 判词是控制信号，**「不通过」结论不得作为正向交付补投下游**。旧闸前置
  // `MergeNodePolicy.isMerge(n)` ⇒ 非 merge 收口位（09-14 官网链 `bpm-report`，merge=false）
  // 遇 fail 判词的 verifier 上游**照旧被拉起**（补投腿 = `settleRunnableSweep` 第 1 步孤儿
  // barrier 自愈 → `startNode`），下游只能靠任务书里手写的人肉口径「注意上游判词」补。
  // 泛化后判据不再按节点形态分叉（唯一谓词 = [[staleVerdictUps]]），覆盖**全部收口位**：
  // merge sink / 非 merge sink / 链尾 / 一切 `startNode` 入口（barrier 结算、资格回扫、
  // 重激活补投、D1、crash-recovery 续跑、直接调用）。
  //
  // 语义与边界（逐字口径）：
  //   · 「上游含 verifier」= `(n.in ++ n.deps).distinct` 中 role=verifier 的节点（`deps`
  //     自 O-2/G-1 起参与）；
  //   · 该 verifier 当下 lastVerdict ≠ "pass"（含 `fail` / 未申报 None / 空串）⇒ 挡住，**读
  //     当下值**（每次判定现读 store，非一次性历史闩）——verifier 重跑出 `pass` 后本闸自动
  //     放行，无需任何清账/重置动作；
  //   · **纯闸门零副作用**：不改上游 status/lastVerdict/result、不写 blockedFeedback、不改
  //     deliveredTo（barrier 记账照旧——`deliverOutTo` 公共门按侦察 §4 红线**不动**）、不改
  //     in/out/deps/merge——节点保持 pending/wiring 可见（停等可见性由既有 mount-stalled
  //     事件承载，见 mountStallReason 的 verdict 闸文案）；
  //   · **合法等待 ≠ 启动失败**：被挡者不进 `trigger-starved` 记账（见落点②注释）；
  //   · **返工腿不受闸（结构性、非豁免分支）**：`(fail)<target>:loop` 是控制边，**不进
  //     in/barrier**（见 [[deliverOutTo]] 注释）⇒ 回边目标的 `in` 恒不含该 verifier ⇒
  //     `reloopTo` 的 `startNode(loopRework=…)` 天生不被本闸拦；
  //   · 无 verifier 上游的节点恒不挡（既有行为逐字不变）；
  //   · verifier 终态 `failed`（loop 预算耗尽）在本闸下同样挡住下游（其判词恒非 pass）；
  //     merge 的可见终态仍走既有「上游失败 ⇒ blocked(upstream-incomplete)」
  //     （MergeNodePolicy.haltsOnFailure）路径，零新语义；
  //   · role 经 NodeRoles.normalize（缺省/空 = task）⇒ 存量数据零回溯；
  //   · 函数名保留 `mergeVerdictHolders*`（**历史名**，语义已泛化）：本批只改语义不改名——
  //     调用点全部在本文件，且改名会把无关行卷进与 #239①/② 共享的写面 diff。

  /**
   * 挡住本节点的 verifier 清单（IO 版；空 = 放行）。
   *
   * **O-2（mergefifo-engine 批 2026-09-13，作者 A-4 裁决并入本批）**：上游集 =
   * **`in ∪ deps`**——设计件 §5.2 **G-1** 的 1 行级扩展（`deps` 参与 verdict 闸，
   * 口径见设计件 §5.2「`mergeVerdictHoldersOf` 上游集改 `(n.in ++ n.deps).distinct`」）。
   * 本仓**零存量影响**：现场扫描 16 个 merge 节点全部用 `in`、无一使用 `deps`
   * （设计件 §5.2 实测）；G-2（verifier 接进 `in`）仍为派发纪律、不入代码；G-3（判词
   * sha 守卫）本批不做。deps 上游解析仍走 `store.findNode`（归档兜底语义逐字保留）。
   * **#238（2026-09-15）**：删去前置 `MergeNodePolicy.isMerge(n)` —— 闸对**全部节点**
   * 生效（收口位 = 全部 start 入口，见上方头注）。
   */
  private[project] def mergeVerdictHoldersOf(n: NodeDef): IO[List[NodeDef]] =
    // ③（chainmodel 批一）：`chain:<id>` 跨链引用展开为目标链成员集（判据单点，合并集口径
    // ——与下方 findNode 双区兜底同源）；目标链里的 verifier 因此同样能挡住本节点。
    store.combinedNodes.flatMap { combined =>
      val ups = (n.in ++ FlowMapStore.resolveDepTargets(n.deps, combined).ids).distinct
      if ups.isEmpty then IO.pure(Nil)
      else ups.traverse(store.findNode).map(l => mergeVerdictHolders(l.flatten))
    }

  /**
   * 纯判据（IO 版与 mount-stalled 可见性文案共用单点）：上游中「让本节点卡住的 verifier」
   * 清单 —— **#238 泛化后 = [[staleVerdictUps]] 的直通单点**（旧前置
   * `MergeNodePolicy.isMerge(n)` 已删，判据对节点形态零分叉）。
   */
  private[project] def mergeVerdictHolders(ups: List[NodeDef]): List[NodeDef] =
    staleVerdictUps(ups)

  /**
   * 「当下判词非 pass」的上游 verifier（**纯判据单点**，闸与回退告警共用）。
   * `fail` / 未申报 `None` / 空串同判（保守口径，见 [[mergeVerdictHolders]] 头注）。
   */
  private def staleVerdictUps(ups: List[NodeDef]): List[NodeDef] =
    ups.filter(u =>
      NodeRoles.normalize(u.role) == NodeRoles.Verifier &&
        !u.lastVerdict.exists(_.trim.equalsIgnoreCase(VerdictPass))
    )

  /**
   * 判词闸**回退告警**（O-1 缝的回退检测器；#238 泛化前 = 「常态可见化」，泛化后语义反转；
   * 写点 = [[startNode]] 闸收口）。
   *
   * 前身（engine-defects 批 #238 第一笔 `8a3ac535e`）：闸是 merge-only ⇒ 非 merge 收口位
   * （09-14 官网链 `bpm-verify`(fail) → `bpm-report`(merge=False)）带非 pass 判词上游被
   * 拉起属**常态**，本函数把它从「人肉口径」变成事件流里可 grep 的一行。
   * 本笔（#238 **泛化**）：闸覆盖全部收口位 ⇒ 该形态**结构性不可能再发生**（本告警与闸共用
   * [[staleVerdictUps]] 单点；闸持有时根本走不到本调用点）⇒ 本行语义 = **不变式告警**：
   * 一旦出现即表示闸被绕过 / 被改弱（或新增了绕开 [[startNode]] 收口的启动腿）。
   * 判据面证据：变异臂「把 [[mergeVerdictHoldersOf]] 置空」⇒ 本行出现，且
   * `MergeVerdictGateSpec.V6` 的「held + 不得发本事件」断言同时转红。
   */
  private[project] def logVerdictGateBreach(where: String, n: NodeDef): IO[Unit] =
    // ③（chainmodel 批一）：上游集与闸共用同一解析单点（链引用 → 成员集），否则回退告警
    // 会与闸的口径分叉（链引用上游被漏点名 = 假阴性）。
    store.combinedNodes
      .flatMap { combined =>
        (n.in ++ FlowMapStore.resolveDepTargets(n.deps, combined).ids).distinct.traverse(store.findNode)
      }
      .flatMap { ups =>
        val held = staleVerdictUps(ups.flatten)
        if held.isEmpty then IO.unit
        else
          val key = held.map(u => s"${u.id}:${u.lastVerdict.getOrElse("none")}").sorted
          verdictGapLogged
            .modify { m =>
              if m.get(n.id).contains(key) then (m, false) else (m.updated(n.id, key), true)
            }
            .flatMap { first =>
              if !first then IO.unit
              else
                val desc =
                  held.map(u => s"'${u.name}'(${u.id}):lastVerdict=${u.lastVerdict.getOrElse("none")}").mkString(", ")
                val summary =
                  s"verdict-gate gap (REGRESSION): node started at $where while the in/deps upstream verifier(s) [$desc] " +
                    "carry no pass verdict — the #238 gate holds EVERY landing position, so this start means the gate was " +
                    "bypassed or weakened (the gate predicate and this alarm share staleVerdictUps; a non-pass verdict must " +
                    "never be handed on as a positive result — the fail route is the '(fail)<target>:loop' control edge)"
                logger.warn(s"[$projectName] node '${n.name}' (${n.id}) $summary") *>
                  FlowMapEventLog.append(workspace, projectName, n.id, FlowMapEventLog.VerdictGateGapType, summary)
            }
        end if
      }

  /**
   * 闸挡启动时的留痕（三处落点共用单点文案）：INFO 一行带 verifier id + 当下 verdict，
   * 供事后从日志直接定位「收口位为何没动」。**节点形态中立**（#238 泛化后闸对全部节点
   * 生效——旧文案写死 "merge" 会误指非 merge 收口位）。
   */
  private[project] def logVerdictGateHold(where: String, n: NodeDef, holders: List[NodeDef]): IO[Unit] =
    logger.info(
      s"[$projectName] node '${n.name}' (${n.id}) start held by verdict gate at $where — " +
        holders.map(u => s"'${u.name}'(${u.id}) lastVerdict=${u.lastVerdict.getOrElse("none")}").mkString(", ") +
        "; node stays pending (gate re-reads lastVerdict on every judgement — a verifier re-run to 'pass' unblocks " +
        "it; a non-pass verdict never opens a downstream — the fail route is the '(fail)<target>:loop' control edge)"
    )

  // ── 合并窗 FIFO 互斥闸（mergefifo-engine 批 2026-09-13，作者 A-4 裁决收窄落地）────
  //
  // 作者原话（逐字）：「每个项目 git 目录下，只能同时有一个合并节点在工作。」
  //
  // 判据：**同键（本项目 git 目录）内的 merge 节点中，若存在更高优先者（`running` 者
  // 恒优先；开态且 rank 严格更小者按 FIFO 优先），则本 merge 不启动、原地保持
  // pending/wiring**。持有者 = 状态派生（`merge=true ∧ status=running`），终态写点
  // （completed/failed/cancelled/blocked）自动释放——**无锁文件、无 TTL、无孤儿、
  // 无迁移**，与既有 verdict 闸同构（同三落点、同「零副作用」纪律）。
  //
  // 语义与边界（逐字口径）：
  //   · 键 = `realpath(git rev-parse --git-common-dir)`（[[MergeMutexPolicy.keyOf]]）；
  //     **worktree 与主仓同键**（设计件 §3.1 实测）；异键不互斥 ⇒ 跨项目并行零变化；
  //   · FIFO 次序 = **到达序**（rank = `(readyAt, createdAt, id)` 升序）；到达 = 「in ∪ deps
  //     全终态」；**不得从「谁先完成」反推到达序**（#438 写前固化纪律）；
  //   · 闸只影响**启动**：barrier 结算/deliveredTo 记账照旧（与 verdict 闸同款
  //     「零副作用——只挡 fork startNode 这一动作」）；不改任何上游/其他节点字段；
  //   · 释放：持有者进终态即自动释放，下一个由 `settleRunnableSweep`（TtlTick 30 s）
  //     当轮拉起 ⇒ **有界释放**（生产上界 = 一个 tick 周期 + 亚秒级启动）；
  //   · 🔴 **不削弱既有 verdict 闸**：两闸是**合取**（先 verdict 后互斥，与设计件
  //     §7.2 状态机同序）；verdict 闸的 marker/日志面（`start held by verdict gate`）
  //     逐字未动（现网判据）；
  //   · 非 merge 节点恒空（闸是 merge-only）、无竞争时**逐字零行为变化**；
  //   · O-1 已知缺口（两项目共用同一 git 目录 ⇒ 引擎侧漏互斥）**不实现 claim/抢占**
  //     （作者令：与「每个项目 git 目录」的字面范围外），只做**发生即告警**。

  /**
   * 挡住本 merge 启动的同键更高优先者（IO 版；空 = 放行）。非 merge 节点零开销
   * 短路（不进 store、不碰注册表、零告警）。
   *
   * 两段：① [[MergeMutexPolicy.holders]] 出「同键更高优先者」（running / 开态 rank 更小）；
   * ② **准入过滤**——候选中自身被 verdict 闸挡住的**不算持有者**（否则一个被 verdict
   * 判 fail 的队头会永久堵死整条队列；设计与本批状态机都把 verdict 闸排在互斥闸之前，
   * 见 [[MergeMutexPolicy]] 头注「显式收窄」）。判据复用既有 `mergeVerdictHolders`
   * **单点**（O-2 后上游集同为 `in ∪ deps` ⇒ 两闸零口径差）。
   * 两段合体已抽为 [[mergeQueueHolders]]（排队位次可见性批：闸与显示面共用同一判据）。
   *
   * 副作用（本函数是闸判定的单一入口，三落点 + 停等文案共用）：附带执行 **O-1 告警**
   * [[alarmSameGitDirProjects]]（同键多项目 = 引擎侧漏互斥，发生即告警，单发）。
   */
  private[project] def mergeMutexHoldersOf(n: NodeDef): IO[List[NodeDef]] =
    if !MergeNodePolicy.isMerge(n) then IO.pure(Nil)
    else
      for
        s <- store.snapshot
        _ <- alarmSameGitDirProjects()
      yield mergeQueueHolders(n, s.nodes)

  /**
   * 阻塞清单纯判据（**闸与显示面的共同单点**）：[[MergeMutexPolicy.holders]] 出「同键
   * 更高优先者」，再滤掉自身被 verdict 闸挡住的候选（该过滤理由见 [[MergeMutexPolicy]]
   * 头注「显式收窄」）。空 = 未被挡（放行）。
   *
   * 抽出的动因（**排队位次可见性批** 2026-09-14，作者 16:39 双裁 = 案 A）：显示面的
   * 「前面还有 N 个 / 被 XX 挡着」必须与闸**同一判据**——闸 [[mergeMutexHoldersOf]]
   * 与载荷注入 [[NodeTools.buildNodeListPayload]] 都调本函数，**禁第二判据**。
   */
  def mergeQueueHolders(n: NodeDef, all: Map[String, NodeDef]): List[NodeDef] =
    MergeMutexPolicy
      .holders(n, all)
      .filterNot(o => mergeVerdictHolders(MergeMutexPolicy.upsOf(o, all)).nonEmpty)

  /**
   * 排队位次派生批次（**显示面单点**；纯函数、零副作用、零持久字段）：nodeId → 当下
   * 挡住它的持有者清单，**只收非空项**（未排队的 merge 节点与全部非 merge 节点不在表内
   * ⇒ 缺键 = 未排队，消费方据此读）。
   *
   * 🔴 口径纪律（逐字）：本函数是 [[mergeQueueHolders]] 的纯映射，**不得**另读文件票层
   * （`.nebflow/locks/main-merge.queue`）、**不得**从事件流回放、**不得**在前端/分发器
   * 复刻——事件流是审计面、文件票层是过渡期并存的旧层，两者都不是本判据的真源。
   */
  def mergeQueueHoldersBatch(all: Map[String, NodeDef]): Map[String, List[NodeDef]] =
    all.valuesIterator
      .filter(MergeNodePolicy.isMerge)
      .flatMap { n =>
        val hs = mergeQueueHolders(n, all)
        if hs.isEmpty then None else Some(n.id -> hs)
      }
      .toMap

  /**
   * 排队位次**显示槽**批次（engine-defects 批 #2/#227，2026-09-15）：与
   * [[mergeQueueHoldersBatch]] **同一判据**（`mergeQueueHolders` → 闸单点），只是把持有者
   * 逐项富化成 [[MergeMutexPolicy.QueueSlot]]（rank 依据 `readyAt/createdAt` + 是否真在
   * 临界区 + 未点火原因）。**零行为面**：不改闸、不改 FIFO、不写任何持久字段。
   *
   * 与准入过滤的关系：被 verdict 闸挡住的候选本就不进 holders（既有收窄），故槽里的
   * `notStartedReason` 只可能落在 `in-critical-section / awaiting-handover /
   * barrier-incomplete / queued` 四态。
   */
  def mergeQueueSlotsBatch(all: Map[String, NodeDef]): Map[String, List[MergeMutexPolicy.QueueSlot]] =
    all.valuesIterator
      .filter(MergeNodePolicy.isMerge)
      .flatMap { n =>
        val hs = mergeQueueHolders(n, all)
        if hs.isEmpty then None else Some(n.id -> hs.map(o => MergeMutexPolicy.slotOf(o, all)))
      }
      .toMap

  /**
   * 排队**位次**批次（queuepos 批 2026-09-15；显示面单点；纯函数、零副作用、零持久字段）：
   * nodeId → 位次槽 [[MergeMutexPolicy.QueuePos]]。**只收非空项** ⇒ 缺键 = 不在队列
   * （非 merge / 在临界区 / 终态 / 竞争者不足）。
   *
   * 与 [[mergeQueueSlotsBatch]] 的分工（🔴 两个量互不替代，禁混用）：
   *   · [[mergeQueueSlotsBatch]] = **闸**判据（阻塞集合 `holders`，载荷键 `mergeQueue`）
   *     ——「谁挡着我」（含 verdict 准入过滤）；
   *   · 本函数 = **队列序**（SEM-2 rank 位次，载荷键 `mergeQueuePos`）——「我排第几」
   *     （**不**过 verdict 准入过滤：位次必须对全队列可读，否则未过 verdict 的节点又
   *     回到「无信息」——正是作者现场报的 6/6 零显示态）。
   * 两键同源真源（同一份 `all` + 同一 [[MergeMutexPolicy.rankOf]]），判据不同。
   *
   * 🔴 口径纪律（与 [[mergeQueueHoldersBatch]] 逐字同源）：**不得**另读文件票层
   * （`.nebflow/locks/main-merge.queue`）、**不得**从事件流回放、**不得**在前端/分发器
   * 复刻——事件流是审计面、文件票层是过渡期并存的旧层，两者都不是本判据的真源。
   */
  def mergeQueuePositionsBatch(all: Map[String, NodeDef]): Map[String, MergeMutexPolicy.QueuePos] =
    all.valuesIterator
      .filter(MergeNodePolicy.isMerge)
      .flatMap(n => MergeMutexPolicy.queuePosOf(n, all).map(p => n.id -> p))
      .toMap

  /**
   * 闸挡启动时的留痕（三处落点共用单点文案）：`merge-queue` 事件（持有者集合变化时
   * 单发）+ INFO 一行带持有者 id/status——供事后从事件流直接读出**FIFO 次序**（谁在
   * 等谁、等了多久由节点 createdAt/startedAt 与事件 ts 共同给出）。
   */
  private[project] def logMutexHold(where: String, n: NodeDef, holders: List[NodeDef]): IO[Unit] =
    val ids = holders.map(_.id).sorted
    mutexHoldLogged.modify(m => (m.updated(n.id, ids), m.get(n.id).contains(ids))).flatMap {
      case true => IO.unit // 同一持有者集合已留痕（禁每轮刷屏）
      case false =>
        FlowMapEventLog.append(
          workspace,
          projectName,
          n.id,
          FlowMapEventLog.MergeQueueType,
          FlowMapEventLog.mergeQueueHoldSummary(where, ids)
        ) *>
          logger.info(
            s"[$projectName] merge '${n.name}' (${n.id}) start held by merge queue at $where — " +
              holders.map(h => s"'${h.name}'(${h.id}) status=${h.status}").mkString(", ") +
              "; node stays pending (one merge node per project git dir — FIFO by arrival: readyAt,createdAt,id; " +
              "the holder's terminal write releases it and the next TtlTick starts the next by rank)"
          )
    }

  end logMutexHold

  /**
   * 🔴 **O-1 已知缺口：同键多项目 ⇒ 发生即告警**（作者 A-4「只登记缺口 + 加检测判据」）。
   *
   * 缺口（设计件 §3.2 登记、不隐瞒）：引擎侧持有者派生自**本项目 store** ⇒ 「两个项目
   * 定义指向同一 git 目录」时**漏互斥**（节点侧文件键不漏）。本批**不实现 claim/抢占**
   * （作者令：与「每个项目 git 目录」的字面范围外，要做须单列批）。
   *
   * 检测 = 对本项目 workspace 求键，遍历 [[ProjectRuntimeRegistry]] 全部在册（未归档）
   * 项目逐个求键（缓存），同键且非本项目 ⇒ 单发一条 `merge-queue` 事件
   * （`kind=same-git-dir-multi-project`，nodeId 字段承载**项目名**）+ WARN 一行（含键
   * 原文与他项目 running merge 计数）。零新事件机制（复用既有事件通道 + WARN）。
   * 键求值失败/非 git 目录回落工作区本体 ⇒ 只会「自等」，不制造假互斥、不误告警。
   */
  private def alarmSameGitDirProjects(): IO[Unit] =
    sameKeyForeignRuntimes.flatMap { case (mine, same) =>
      if same.isEmpty then IO.unit else emitSameKeyAlarm(mine, same)
    }

  /**
   * 同键他项目读数的**共同单点**（告警 [[alarmSameGitDirProjects]] 与显示面
   * [[sameKeyForeignProjectsNow]] 共用）：返回 (本项目键, [(他项目名, 键, runtime)])。
   * 键求值经 [[MergeMutexPolicy.keyOf]] 的进程内缓存 ⇒ 稳态下零 git 调用。
   */
  private def sameKeyForeignRuntimes: IO[(String, List[(String, String, ProjectRuntime)])] =
    for
      mine <- MergeMutexPolicy.keyOf(workspace)
      rts <- ProjectRuntimeRegistry.all
      others = rts.filter(rt => rt.project.name != projectName && !rt.project.archived.contains(true))
      keyed <- others.traverse(rt => MergeMutexPolicy.keyOf(rt.project.workspace).map(k => (rt.project.name, k, rt)))
      same = keyed.filter((_, k, _) => k == mine)
    yield (mine, same)

  /**
   * 同键多项目（O-1）**当下**读数：与本项目同键的他项目名（升序去重；空 = 无）。
   *
   * 用途（排队位次可见性批）：引擎侧持有者派生自**本项目 store** ⇒ 同键他项目的 merge
   * 节点对位次计数**结构性不可见**（[[MergeMutexPolicy.sameKeyForeignProjects]] 头注：
   * 作者 09-13 令「每个项目 git 目录下只能同时有一个合并节点」在本仓 = 只允许一个合并
   * 节点在工作；跨项目同键 = 告警面）。故显示面据此**降级**——非空 ⇒ 不渲染位次数字
   * （🔴 降级红线：禁编造数字；「读不到」≠「不在排队」）。
   * 与 [[MergeMutexPolicy.sameKeyForeignProjects]] 同源（**禁二次口径**）。
   */
  def sameKeyForeignProjectsNow: IO[List[String]] =
    sameKeyForeignRuntimes.map { case (mine, same) =>
      MergeMutexPolicy.sameKeyForeignProjects(mine, same.map((name, k, _) => (name, k)))
    }

  private def emitSameKeyAlarm(
    mine: String,
    same: List[(String, String, ProjectRuntime)]
  ): IO[Unit] =
    val mark = same.map((name, _, _) => name).distinct.sorted.mkString(",")
    sameKeyWarned.modify(m => (m + mark, m.contains(mark))).flatMap {
      case true => IO.unit // 同一他项目集合已告警（禁 30 s 节拍刷屏）
      case false =>
        for
          foreignRunning <- same
            .traverse((_, _, rt) =>
              rt.store.snapshot
                .map(_.nodes.values.count(o => MergeNodePolicy.isMerge(o) && o.status == NodeLifecycle.Running))
            )
            .map(_.sum)
          s <- store.snapshot
          mineRunning = s.nodes.values.count(o => MergeNodePolicy.isMerge(o) && o.status == NodeLifecycle.Running)
          names = same.map((name, _, _) => name).distinct.sorted
          _ <- FlowMapEventLog.append(
            workspace,
            projectName,
            projectName,
            FlowMapEventLog.MergeQueueType,
            FlowMapEventLog.mergeQueueSameGitDirSummary(mine, names, foreignRunning, mineRunning)
          )
          _ <- logger.warn(
            s"[$projectName] merge mutex KNOWN GAP (O-1): other registered project(s) [${names.mkString(", ")}] " +
              s"resolve to the SAME git dir '$mine' — merge holders are derived from THIS project's store only, " +
              "so mutual exclusion is NOT enforced across projects sharing one git dir " +
              "(no claim/preemption implemented in this batch, per author ruling). " +
              s"merge-running now: mine=$mineRunning foreign=$foreignRunning. " +
              "Mitigation: never point two projects at one git dir."
          )
        yield ()
    }

  end emitSameKeyAlarm

  /**
   * R3 终态写点**即时** barrier 检查（取消静默死锁修复批，作者裁定 R3 方案 3）：
   * 终态写点已经知道「谁终态了 + 谁是它的 barrier」，信息完整——把「周期发现」变成
   * 「同步可知」。对每个 in/deps/pendingSuccession 引用 `terminalId` 的 pending/wiring
   * 下游，若该 barrier 已被终态上游永久闸死（[[barrierHeldReason]]）→ 立即写
   * `barrier-blocked` 事件 + WARN（**无 60s 阈值**，理论延迟 ≈ 0）。
   *
   * 与周期回扫（`settleRunnableSweep` → `mountStalledMs` 60s 档 → `mount-stalled`）
   * 的关系：**兜底而非重复**。单发记账由 [[barrierAlerted]] 承担——周期回扫把
   * barrierAlerted 成员排除出发射集，且每轮按「此刻是否仍被闸住」剪枝（恢复即出集）。
   *
   * 反例护栏（对齐 `mountStallReason` 的合法等待豁免，见 [[barrierHeldReason]]）：
   * 仍有 running/wiring 上游 → 不告警；上游引用悬空 → 不告警。
   */
  private[project] def checkBarriersNow(terminalId: String, cause: String): IO[Unit] =
    store.snapshot.flatMap { snap =>
      // ③（chainmodel 批一）：`chain:<id>` 引用展开为目标链成员集后判定（目标链里有终态
      // 成员 ⇒ 该下游进入检查集；其依赖永不满足的事实由 barrierHeldReason 判断与点名）。
      // 零链引用时逐字等于改造前行为。
      val chainsById =
        if !snap.nodes.valuesIterator.exists(_.deps.exists(FlowMapStore.isChainRef)) then Map.empty[String, ChainInfo]
        else FlowMapStore.topologicalChains(snap.nodes.values).map(c => c.id -> c).toMap
      def depsRefers(n: NodeDef): Boolean =
        n.deps.exists { dep =>
          if FlowMapStore.isChainRef(dep) then
            chainsById.get(FlowMapStore.chainRefTarget(dep)).exists(_.memberIds.contains(terminalId))
          else dep == terminalId
        }
      snap.nodes.values.toList
        .filter(n =>
          (n.status == NodeLifecycle.Pending || n.status == NodeLifecycle.Wiring) &&
            (n.in.contains(terminalId) || depsRefers(n) || n.pendingSuccession.contains(terminalId))
        )
        .traverse_ { n =>
          barrierHeldReason(snap.nodes, n) match
            case None => IO.unit
            case Some(reason) =>
              barrierAlerted.modify(s => if s.contains(n.id) then (s, false) else (s + n.id, true)).flatMap {
                case false => IO.unit // 本停滞期已告警（即时或回扫）→ 单发
                case true =>
                  val summary = s"barrier blocked by terminal upstream (cause=$cause): $reason"
                  FlowMapEventLog.append(workspace, projectName, n.id, "barrier-blocked", summary) *>
                    logger.warn(s"[$projectName] node ${n.id} barrier-blocked (cause=$cause): $reason")
              }
        }
    }

  /**
   * barrier 被终态上游**永久闸死**的判定单点（R3 即时告警与周期回扫共享口径，
   * **不含时间阈值**——即时路径要求 0 延迟，60s 阈值只属于周期兜底）。
   * 返回 Some(reason) = 已闸死（barrier 的闸门不会再自行打开）。
   *
   * 判定：
   *   - blocker = ① in 引用且**未投递**且上游已是「非 completed 的终态」
   *     （failed/cancelled/blocked——永不投递）；② deps 引用且上游非 completed；
   *     ③ `pendingSuccession` 成员（R4 摘除后登记的「待承接」槽位）。
   *   - 其余上游必须全部到位（in 已投递 / deps 已 completed）——**仍有 running/
   *     wiring 上游 = 合法等待，不判**（mountStallReason 同款豁免）。
   *   - 任一 in/deps 引用在图上查不到（悬空遗留数据）→ 保守不判。
   *   - `completed 但未投递`的上游**不算 blocker**（那是投递丢失，settle 回扫的自愈
   *     对象，不是终态闸死）——避免重复告警噪音。
   */
  private[project] def barrierHeldReason(nodes: Map[String, NodeDef], n: NodeDef): Option[String] =
    // ③（chainmodel 批一）：`chain:<id>` 引用展开为目标链成员集后再逐条解析——目标链里
    // 出现「非 completed 的终态成员」时，本闸**永久不可满足**（链引用要求全体成员
    // completed），旧口径会因引用解析不到而走「悬空 ⇒ 保守不判」= 静默放过真死锁。
    // 解析按本判据既有的节点表口径（活动区）做；零链引用时逐字等于改造前行为。
    val depsResolved = FlowMapStore.resolveDepTargets(n.deps, nodes).ids
    val refs = (n.in ++ depsResolved).distinct
    val resolved = refs.flatMap(id => nodes.get(id).map(id -> _))
    if resolved.size != refs.size then None
    else
      def upstreamOf(id: String): Option[NodeDef] = nodes.get(id)
      def permanentlyTerminal(id: String): Boolean =
        upstreamOf(id).exists(u => u.status != NodeLifecycle.Completed && NodeLifecycle.Terminal.contains(u.status))
      val inBlockers = refs.filter(id => n.in.contains(id) && !n.deliveredTo.contains(id) && permanentlyTerminal(id))
      val depsBlockers =
        refs.filter(id => depsResolved.contains(id) && upstreamOf(id).exists(_.status != NodeLifecycle.Completed))
      val blockers = (inBlockers ++ depsBlockers ++ n.pendingSuccession).distinct
      if blockers.isEmpty then None
      else
        val othersOk = refs.forall { id =>
          blockers.contains(id) ||
          ((!n.in.contains(id) || n.deliveredTo.contains(id)) &&
            (!depsResolved.contains(id) || upstreamOf(id).exists(_.status == NodeLifecycle.Completed)))
        }
        if !othersOk then None
        else
          val blockerDesc = blockers
            .map { id =>
              upstreamOf(id).map(u => s"'${u.name}'(${id}):${u.status}").getOrElse(s"$id:gone")
            }
            .mkString(", ")
          if n.pendingSuccession.nonEmpty then
            Some(
              s"in-barrier awaiting handover — pendingSuccession=[${n.pendingSuccession.mkString(",")}] " +
                s"(cancelled upstream detached by R4; the slot is explicitly NOT settled and the barrier will not " +
                s"auto-trigger until the dispatcher hands over) — upstreams: $blockerDesc"
            )
          else
            Some(
              s"in-barrier permanently wedged by terminal upstream (no settlement for failed/cancelled) — " +
                s"upstreams: $blockerDesc — dispatcher must hand over (承接) / rewire (改接) / abandon"
            )

        end if

      end if

    end if

  end barrierHeldReason
end NodeGating
