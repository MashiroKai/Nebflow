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
        logger
          .warn(
            s"[$projectName] node '${node.name}' (${node.id}) deps=[${node.deps.mkString(",")}] carries " +
              s"unresolvable chain reference(s) [${targets.unknownChainRefs.mkString(",")}] — the dependency can never " +
              "satisfy (fail-closed); fix the deps ref (chains come from the Flow Map `chains[]` payload)"
          )
          .as(false)
      else
        // 判据 D（死链；案 b 运行期可见面）：链号**可解析**但成员含 `cancelled` ⇒ 闸**构造性
        // 不可满足**。**判据零变化**（下方 `forall(completed)` 早已返回 false）；本分支只补
        // **点名面**：既有 `unknownChainRefs` 分支不覆盖该形态 ⇒ 否则该形态在启动闸上**静默**。
        // 🔴 **带单发去重**（复用 [[verdictGapLogged]] 式 `Ref[IO, Map]` 记账，见 [[deadChainWarned]]）
        // ——既有 `unknownChainRefs` 分支本就无去重（逐次调用逐次 WARN），是既有隐患；本分支
        // **不继承**该隐患（`depsSatisfied` 在资格回扫/启动腿被高频调用 ⇒ 无去重会刷屏）。
        // RISKY 档（`failed` 成员）同源并列：**放行**（下方判定照旧，可重激活）+ 一条警告，
        // 🔴 **禁**在此硬拒（那会封死自愈路）。
        val deadNote = deadChainRefNote(node, targets)
        val warnDead: IO[Unit] =
          if deadNote.isEmpty then IO.unit
          else
            deadChainWarned
              .modify(m =>
                if m.get(node.id).contains(deadNote) then (m, false) else (m.updated(node.id, deadNote), true)
              )
              .flatMap(first =>
                if first then logger.warn(s"[$projectName] node '${node.name}' (${node.id}) $deadNote") else IO.unit
              )
        warnDead *>
          targets.ids
            .traverse(store.findNode)
            .map(_.forall(_.exists(_.status == NodeLifecycle.Completed)))
      end if
    }

  /**
   * 死链 / RISKY 链引用的**单点文案**（运行面 WARN 与 [[NodeTools.chainRefDeadError]] 同口径）。
   * 空串 = 无该形态。判据源 = [[FlowMapStore.resolveDepTargets]] 的第三/第四桶（唯一解析点）。
   */
  private[project] def deadChainRefNote(node: NodeDef, targets: FlowMapStore.DepTargets): String =
    val parts = scala.collection.mutable.ListBuffer.empty[String]
    if targets.deadChainRefs.nonEmpty then
      val named = targets.deadChainRefs.toList
        .sortBy(_._1)
        .map((cid, ids) =>
          s"chain '$cid' via member(s) ${ids.toList.sorted.map(id => s"'$id':${NodeLifecycle.Cancelled}").mkString(", ")}"
        )
        .mkString("; ")
      parts +=
        (s"deps chain ref(s) are constructively unsatisfiable: $named — `cancelled` is a non-reactivatable terminal " +
          "(the gate waits for EVERY member to be completed), so this dependency can NEVER be satisfied; rewire the deps " +
          "ref to a completion FACT ANCHOR (literal member ids that already carry the landing fact) or drop the ref")
    if targets.riskyChainRefs.nonEmpty then
      val named = targets.riskyChainRefs.toList
        .sortBy(_._1)
        .map((cid, ids) =>
          s"chain '$cid' via member(s) ${ids.toList.sorted.map(id => s"'$id':${NodeLifecycle.Failed}").mkString(", ")}"
        )
        .mkString("; ")
      parts +=
        (s"deps chain ref(s) are RISKY: $named — `failed` members block the gate until they are re-activated (failed IS " +
          "reactivatable, so this is NOT a dead ref); re-activate them or rewire to a completion fact anchor")
    if parts.isEmpty then "" else s"deps=[${node.deps.mkString(",")}] ${parts.mkString("; ")}"
  end deadChainRefNote

  // ── verdict 闸（merge-verdict-gate 批 2026-09-12 作者裁定；**engine-defects #238 泛化
  //    2026-09-15**：闸面从「仅 merge 节点」扩到**全部收口位**）────────────────────────
  //
  // 判据：**节点的 `in ∪ deps` 上游中含 verifier 时，该 verifier 的当下 `lastVerdict`
  // 必须为 `pass`**；否则本节点不可启动、原地保持 pending。把「先验（判词）后交付」从
  // 节点自觉变成机制保证（来源实证：`perm-global-merge`(n-9e9c385d)，其 in 含 verifier
  // `n-6f86d028`，该 verifier 已报 lastVerdict=fail，merge 仍被 `settleRunnableSweep`
  // 拉起并启动）。
  //
  // **#238 泛化的理由（缝 = O-1）**：
  // 判词是控制信号，**「不通过」结论不得作为正向交付补投下游**。旧闸曾前置「合并节点」
  // 形态判定 ⇒ 非合并收口位（09-14 官网链 `bpm-report`）遇 fail 判词的 verifier 上游
  // 照旧被拉起（补投腿 = `settleRunnableSweep` 第 1 步孤儿
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
  //   · role 经 NodeRoles.normalize（缺省/空 = task）⇒ 存量数据零回溯；
  //   · 函数名保留 `mergeVerdictHolders*`（**历史名**，语义已泛化）：调用点全部在本文件
  //     与既有共享落点，改名会把无关行卷进共享写面 diff。

  /**
   * 挡住本节点的 verifier 清单（IO 版；空 = 放行）。
   *
   * **O-2（mergefifo-engine 批 2026-09-13，作者 A-4 裁决并入本批）**：上游集 =
   * **`in ∪ deps`**——设计件 §5.2 **G-1** 的 1 行级扩展（`deps` 参与 verdict 闸）。
   * 本仓**零存量影响**：现场扫描 16 个 merge 节点全部用 `in`、无一使用 `deps`。
   * deps 上游解析仍走 `store.findNode`（归档兜底语义逐字保留）。
   * **#238（2026-09-15）**：删去「仅合并节点」前置 —— 闸对**全部节点**生效
   * （收口位 = 全部 start 入口，见上方头注）。
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
   * 清单 —— **#238 泛化后 = [[staleVerdictUps]] 的直通单点**（「仅合并节点」前置已随
   * 合并节点机制移除，判据对节点形态零分叉）。
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

  /**
   * **Pre-W1 name for [[mergeVerdictHoldersOf]]** (taskunify / W2b' backfill). The PR-side
   * tree carries `verdictGateHoldersOf`; the body was byte-identical to
   * [[mergeVerdictHoldersOf]]'s (`in ++ deps` expansion over the combined two-zone node map,
   * `.distinct`, resolved through `store.findNode`, then the shared [[mergeVerdictHolders]]
   * predicate) -- `git log -SverdictGateHoldersOf` lands only on the synthetic PR head, so the
   * divergence is W1 rename fallout, not an organised main-side rename. This alias binds to
   * the single implementation rather than re-deriving the upstream set: a second derivation
   * would be a second source of truth for the gate, which the batch forbids.
   */
  private def verdictGateHoldersOf(n: NodeDef): IO[List[NodeDef]] =
    mergeVerdictHoldersOf(n)

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
                    logger.warn(s"[$projectName] node ${n.id} barrier-blocked (cause=$cause): $reason") *>
                    chainWedgedReceipt(snap.nodes, n, terminalId, cause, reason)
              }
        }
    }

  /**
   * **链级终态回执**（P0 dispatch-notify 批 2026-11-02，RCA §7-P1）：由
   * [[checkBarriersNow]] 在**已有** `barrier-blocked` 留痕之后调用（追加腿，不改动
   * 后者的文本与发射条件）。零归属（`n.chainId` 空）⇒ `IO.unit`，行为与改前逐字
   * 一致。
   *
   * 两条产物（同一分支、同一 chainId 单发）：
   *   ① **事件面** [[NodeEngine.ChainWedgedEventType]]：三要素
   *      `chain=<chainId> node=<被闸死的下游 id> wedgedBy=<terminalId>`（机械可
   *      grep），附 `cause` 与 reason 摘要；顶层 `chainId` 键一并带上（与链族事件
   *      同源口径，消费者免反解析）。
   *   ② **上层回执**：走**既有** root 通知通道 [[enqueueRootNotify]]——本仓既有分流
   *      语义直接复用，**不为 delegate 另写投递**：`rootSessionId` 在项目面 = 项目根
   *      会话（→ 项目 dispatcher/root 可见），在 delegate 作用域面 = delegate 自己
   *      （`notifyTargetKind = CreatorDelegate`，[[DelegateFlowHosts]] 挂载面固定）
   *      ⇒ 落到**创建者 delegate**。文本逐字含 `chain-wedged` / chainId / 被闸死
   *      节点名+id / 处置方向三段（承接 hand over · 改接 rewire · abandon）。
   *
   * `status` = `"blocked"`：这是**要求上层处置的终态回执**，前端据此打 BLOCKED 标签
   * （与既有 `feedbackRouter.escalate` 同档）；`blocked` 不在 [[isRootNotifyInterrupt]]
   * 档内 ⇒ 进 root 打包窗，密集扇出不轰炸。`nodeId` 刻意传 `None`（理由见调用点注释：
   * 不让 pending 节点被盖上「结果已投根」的持久账本）；重复抑制由
   * [[NodeEngine.chainWedgedAlerted]] 的 chainId 单发承担，**不依赖** root 通道的
   * 60s 同键窗。
   *
   * 🔴 **不套 uplink 归因闸**（`uplinkAllowed`）：该闸的语义是「有任务归属的任务面
   * 上报」，而本回执是**引擎监督面**的链级终态告知（收件人 = 链属主 / root，不是
   * 任务台账）；对本形态（下游无 `taskId`）套闸只会让「链断了」这一条最该被看见的
   * 信号静默消失。**没有新建第三套闸**：直接复用 `enqueueRootNotify` 的既有形态
   * （与 `NodeDelivery.rootDelivery` 的 Nebula 腿同款调用）。
   *
   * 去重：[[NodeEngine.chainWedgedAlerted]]（key = chainId，与 `barrierAlerted` 的
   * 节点 id 集正交）；由 `settleRunnableSweep` 按「该链此刻是否仍被闸住」剪枝。
   */
  private[project] def chainWedgedReceipt(
    nodes: Map[String, NodeDef],
    n: NodeDef,
    terminalId: String,
    cause: String,
    reason: String
  ): IO[Unit] =
    n.chainId.map(_.trim).filter(_.nonEmpty) match
      case None => IO.unit // 无链归属 ⇒ 零行为变化（只留既有 barrier-blocked）
      case Some(cid) =>
        chainWedgedAlerted.modify(s => if s.contains(cid) then (s, false) else (s + cid, true)).flatMap {
          case false => IO.unit // 本链已回过执（单发）
          case true =>
            val wedgedBy = nodes.get(terminalId).map(_.name).getOrElse(terminalId)
            val action =
              "Action for the chain owner: hand over (承接) the wedged node, rewire (改接) it to a live upstream, " +
                "or abandon the chain — without one of these the chain stays permanently wedged (no settlement " +
                "for failed/cancelled upstreams; this receipt is sent once per chain)"
            FlowMapEventLog
              .append(
                workspace,
                projectName,
                n.id,
                NodeEngine.ChainWedgedEventType,
                s"chain-wedged chain=$cid node=${n.id} (${n.name}) wedgedBy=$terminalId ($wedgedBy) " +
                  s"cause=$cause — $reason",
                chainId = Some(cid)
              ) *>
              logger.warn(
                s"[$projectName] CHAIN WEDGED chain=$cid — node '${n.name}'(${n.id}) permanently blocked by " +
                  s"terminal upstream '$wedgedBy'($terminalId) (cause=$cause)"
              ) *>
              enqueueRootNotify(
                s"chain-wedged chain=$cid — node '${n.name}' (${n.id}, status=${n.status}) is permanently wedged " +
                  s"by terminal upstream '$wedgedBy' (${terminalId}, cause=$cause): the in-barrier can never be " +
                  s"settled and nothing downstream of this node will ever start on its own.\nReason: $reason\n$action",
                n.name,
                NodeLifecycle.Blocked,
                // 🔴 `nodeId = None`（刻意）：本回执是**监督面**告知，被闸死的下游自己
                // **尚未产出任何结果**。`deliverToRoot` 拿到 nodeId 会顺手
                // `markRootDelivered` 写 `nebulaDeliveredAt`（`NodeDelivery.scala:780,791`）
                // ——那是「本节点结果已投根」的持久账本，写在一个 pending 节点上是伪事实，
                // 并且会让 `deliverOutTo` 的 Nebula 腿（`NodeDelivery.scala:534` 持久去重锚）
                // 在承接/改接后**拒投**该节点真正的结果。与既有 `feedbackRouter.escalate`
                // 的调用形态（`enqueueRootNotify(text, nodeName, blocked)`，不传 nodeId）同款。
                None
              )
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
