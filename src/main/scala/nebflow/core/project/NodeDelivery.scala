/* 从 NodeEngine 迁出(行为保持重构,2026-09-25)。 */
package nebflow.core.project

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.agent.*

import scala.concurrent.duration.*

private[project] trait NodeDelivery:
  self: NodeEngine =>

  /**
   * U3/B 兜底腿（2026-09-11 作者拍板）：L3 resume 失败且**活动区查无**该会话归属节点
   * 时的收口——把「找不到节点归属」从静默死路改成有账的迟到摘除。
   *
   * **哪条路径摘除、哪条不摘除（逐条）**：
   *   - ① **能定位节点**（[[settleFailedHardResume]] 的 `Some(n)` 支；P2① 修好定位键后
   *     L3 场景恒走此路）→ 改判 **failed**（[[failNode]] 全链）⇒ failed 侧 D5 零结算
   *     停等：**不摘除、不打 pendingSuccession**，下游 in 镜像原样保留，等上游
   *     NodeEdit 重激活后自动续跑。这是 R4/R5 裁定的**永久拒绝**项（「failed 也自动
   *     摘」会掐死 reactivate 恢复路径），本批不回退、不越界。
   *   - ② **查无节点但它在归档区**（本方法 `Some(archived)` 支）→ 节点以 **cancelled**
   *     终态离场（不是 failed），而 cancelled 侧的 R4 语义**就是摘除**（[[cancelNode]]
   *     头注 ③）。L3 路径因 `deferDetach = true` 把摘除推迟到 resume 结果；resume
   *     失败且节点已不可定位 ⇒ 没有任何腿会再补这一步 ⇒ **在此补**：反向扫活动区全部
   *     节点，凡 `in` 仍引用该 id 的 → prune `in` + 追加 `pendingSuccession`（「待承接」
   *     标）+ 补发 `nodeUpdated` + 事件流留痕，并把 L3 中间态的回流占位归还（节点已
   *     离场，占位留着只会是永不清除的陈旧标记）。
   *     **为什么反向扫**：节点已不在活动区，前向 `out` 遍历不可达（这正是
   *     [[detachCancelledUpstream]] 的 `targets.isEmpty` 零操作形态）；而「谁还引用我」
   *     在活动区恒可算。摘除方向（引用方 in）与 R4 摘除逐字同源，故语义一致。
   *   - ③ **连归档区也查无**（会话从未绑定到任何节点 / 记录已注销）→ ERROR **+ 事件流
   *     留痕**（`hard-recovery`，nodeId 空串）：不能再静默——这正是 09-11 的事故形态
   *     （35 行日志里 15 例只有 ERROR、事件流零行，事后无法 join 出「哪次 L3 失败了」）。
   *     此支**不摘除**（没有任何 id 可摘），但已可见、可检索、可 join。
   *
   * 幂等：反向 prune 后该 id 不再出现在任何 `in` 中 ⇒ 重复调用零写、零帧；事件流只在
   * 真正到达本方法时追加一行（可重放审计，与 [[FlowMapEventLog]] 的追加-only 纪律一致）。
   */
  private[project] def lateDetachUnlocatableSession(sessionId: String): IO[Unit] =
    store.archiveSnapshot.flatMap { arch =>
      arch.nodes.values.find(n => n.sessionRef.contains(sessionId) || n.sessionRefVerify.contains(sessionId)) match
        case None =>
          logger.error(
            s"[hard-recovery] L3 resume failed and no node owns session $sessionId — it is in neither the active " +
              "region nor this project's archive; no re-judge and no detach is possible (manual intervention required)"
          ) *> FlowMapEventLog.append(
            workspace,
            projectName,
            "",
            "hard-recovery",
            s"L3 resume FAILED (session=$sessionId) — no node owns this session in project '$projectName' " +
              "(active + archive both empty for it): nothing to re-judge, nothing to detach — manual intervention required"
          )
        case Some(archived) =>
          reversePruneReferences(archived.id).flatMap { pruned =>
            dispatchNotify.releaseTerminalNotify(archived.id) *>
              FlowMapEventLog.append(
                workspace,
                projectName,
                archived.id,
                "hard-recovery",
                s"L3 resume FAILED (session=$sessionId) — node '${archived.name}' (${archived.id}) is no longer in the " +
                  "active region (archived), so it cannot be re-judged as failed; cancelled-side R4 detach applied " +
                  "late instead: " +
                  (if pruned.nonEmpty then
                     s"pruned the in-mirror of ${pruned.size} active referrer(s) ${pruned.mkString(",")} + marked " +
                       "pendingSuccession (their barrier is unblocked for a handover node)"
                   else "no active node references it any more (idempotent zero-write)")
              )
          }
    }

  /**
   * 反向引用 prune **单点**（本批新增；服务两处：B 的迟到摘除 [[lateDetachUnlocatableSession]]
   * 与 E 的不一致拓扑 [[detachCancelledUpstream]] `targets.isEmpty` 出口）。
   *
   * 语义 = 活动区内凡 `n.in.contains(nodeId)` 的节点 → 从其 `in` 摘除该 id + 追加
   * `pendingSuccession`（「待承接」）+ 补发 `nodeUpdated`（可见性口径与
   * [[detachCancelledUpstream]] 逐字一致：标记只落盘不推帧，前端要等下一次全量快照才见）。
   *
   * **为什么必须有反向方向**：`detachCancelledUpstream` 旧口径用前向 `out` 遍历目标，
   * 在两种形态下 `targets.isEmpty` ⇒ 静默零操作（U5/E 的问题）：
   *   ① out 已改接 Nebula（只剩 Nebula 边）而下游 `in` 镜像仍引用本节点（不一致拓扑）；
   *   ② 节点已不在活动区（归档/移除），`s.nodes.get(nodeId)` 直接 None。
   * 而「谁还引用我」在活动区恒可算——引用方向是这一族的唯一可靠方向。
   *
   * 幂等：prune 后该 id 不再出现在任何 `in` ⇒ 重复调用零写、零帧（满足 U5 的「不破坏
   * 重入幂等」）。返回被 prune 的下游 id 集（升序，审计/事件文案用）。
   */
  private def reversePruneReferences(nodeId: String): IO[List[String]] =
    store
      .mutateWithResult { s =>
        val referrers = s.nodes.values.filter(_.in.contains(nodeId)).map(_.id).toList.sorted
        if referrers.isEmpty then (s, Nil)
        else
          val pruned = referrers.foldLeft(s.nodes) { (acc, tid) =>
            acc.get(tid) match
              case Some(tn) =>
                acc.updated(
                  tid,
                  tn.copy(
                    in = tn.in.filterNot(_ == nodeId),
                    pendingSuccession = (tn.pendingSuccession :+ nodeId).distinct
                  )
                )
              case None => acc
          }
          (s.copy(nodes = pruned), referrers)
        end if
      }
      .flatMap { case (_, pruned) =>
        pruned
          .foldLeft(IO.unit) { (acc, tid) =>
            acc >> store.getNode(tid).flatMap {
              case Some(n) => emitUpdated(n)
              case None => IO.unit
            }
          }
          .as(pruned)
      }

  /**
   * WS nodeRemoved（TTL 移除/归档后通知前端移除卡片）。payload = 节点最终态
   * （NodeList 同构——归档区兜底查得，ttlLeftSec=0），不再发空对象。
   */
  def emitRemoved(nodeId: String): IO[Unit] =
    store.findNode(nodeId).flatMap {
      case Some(n) => emitWithChain("nodeRemoved", nodeId, NodePayload.buildNodeJson(n, System.currentTimeMillis()))
      case None => emitWithChain("nodeRemoved", nodeId, Json.obj("id" -> nodeId.asJson))
    }

  // ── NodeMessage（20260905 机制批，作者裁定六条语义）──────────────────
  //
  // 向已分发节点注入补充消息，按节点状态三路由：
  //   running（裁定②）     → 复用既有 immediate 注入机制（ImmediateInput →
  //                          pendingImmediateInputs → turn 边界 drainHead /
  //                          idle 即开新 turn；CompactionQueueStore 压缩窗口
  //                          保全）——注入文本带 [NODE-MESSAGE] 可识别前缀
  //                          （来源 = 分发器 NodeMessage + 节点名 + 时间戳），
  //                          与用户任务文本、节点结果投递明确区分。竞态兜底：
  //                          状态检查与入队间会话终结（nodeSessions/registry
  //                          查无映射）→ 回退任务记录追加并标注「注入未达」。
  //   wiring/pending
  //   （裁定③，非终态无
  //    活动会话）          → 补充内容持久追加进节点 task（== 分发器补充
  //                          （NodeMessage <时间戳>） == 分节）——节点启动时 buildInput 沿 task
  //                          读到。事务内 fresh 状态守卫：并发启动窗口（状态已
  //                          变 running）→ 重走一次 running 路由（单次重试）；
  //                          并发终态化 → NODE_TERMINAL_NO_MESSAGE。
  //   终态（裁定④）        → 拒绝 NODE_TERMINAL_NO_MESSAGE（completed/failed/
  //                          cancelled/blocked；blocked 亦拒——应新建节点而非
  //                          倒改）。
  // 留痕（裁定⑤）：每条消息（含注入成功/未达/追加）一律追加 FlowMapEventLog
  // （type=node-message，append-only 持久可追溯）；Flow Map 默认载荷零膨胀
  // （不加标记不加键——载荷精简裁定「能不加就不加」）。
  /**
   * 本项目链号**校验面**（chainmodel 批三 ① 起 = 台账解析）：`MailTool` 的 `chainId`
   * 参数校验（只校验、不落库）经本访问器收口——Mail 对工程存取的触点仍收敛到
   * `ProjectRuntimeRegistry` 一处（与 `node:` 腿复用 [[sendNodeMessage]] 同路径），
   * **不**让 MailTool 直触 `FlowMapStore`。
   *
   * 判据单点 = `FlowMapStore.mailChainIds` / `resolveMailChainId`（**声明链 ∪ 兜底派生链 ∪
   * 链级依赖目标链 ∪ 台账已登记面**，冷档兜底见 `resolveMailChainId`）。
   * 🔴 改造前口径 = `FlowMapStore.allChainIds`（纯派生链集，零台账）——链号改号/合并后
   * 旧号立即失效（设计件 §一 取证 4）。
   */
  def mailChainIds: IO[Set[String]] = store.mailChainIds

  /** 链号深解析（同上）：`Some` = 该号可达（热面 / 台账别名 / 冷档历史行）。 */
  def resolveMailChainId(id: String): IO[Option[String]] = store.resolveMailChainId(id)

  /**
   * **引用面计数钩子（chainmodel 批三+ 轴(b) 外部三面；best-effort）**：把**一次已发生的
   * 引用**计入 `faceId`（面必须先登记在 `ChainLedger.ReferenceFaces`；未登记 ⇒ WARN，不计）。
   *
   * 与 [[mailChainIds]] 同款收口理由：工具面对工程的触点收敛到本类 ⇒ `MailTool` /
   * `TaskBoardTool` / `NodeReportTool` **不直触** `FlowMapStore`（`FlowMapStore.chainLedgerStore`
   * 保持只读消费面语义，写面单点仍在此）。
   *
   * best-effort 口径（与轴(a) 归档绑定的 best-effort 同款，**但不静默**）：计数是退役判据
   * （轴 b）的输入面，其失败**不得**让已完成的投递/写入回滚 ⇒ 只 WARN 可观察；残留风险
   * 「某次引用被漏计 ⇒ 退役可能早触发」由批报告「未决/风险」栏承担，不靠本方法兜。
   * 🔴 调用方**只应在引用已成立之后**调用（投递/写库返回成功之后）：未发生即不计数。
   */
  def noteChainReference(id: String, faceId: String, delta: Int = 1): IO[Unit] =
    store.chainLedgerStore
      .noteReference(id, faceId, delta)
      .flatMap {
        case Right(()) => IO.unit
        case Left(diag) =>
          logger.warn(s"chain reference not noted (face=$faceId id=$id): $diag")
      }
      .handleErrorWith(e => logger.warn(s"chain reference not noted (face=$faceId id=$id): ${e.getMessage}"))

  /**
   * 同上的**正文形态**（`board-usage` / `report-usage` 共用）：一段正文里逐字出现的**已登记**
   * 链号一次性计入；零命中 ⇒ 零写。判据与「禁回填」边界全在
   * `ChainLedgerStore.noteTextReferences`（纯函数判据见 `ChainLedger.referencedIds`）。
   */
  def noteChainReferencesIn(text: String, faceId: String): IO[Unit] =
    store.chainLedgerStore
      .noteTextReferences(text, faceId)
      .flatMap {
        case Right(_) => IO.unit
        case Left(diag) =>
          logger.warn(s"chain text references not noted (face=$faceId): $diag")
      }
      .handleErrorWith(e => logger.warn(s"chain text references not noted (face=$faceId): ${e.getMessage}"))

  def sendNodeMessage(
    nodeId: String,
    message: String,
    attribution: Option[InjectionAttribution] = None
  ): IO[Either[String, String]] =
    val text = message.trim
    if text.isEmpty then IO.pure(Left(s"'message' must be non-empty (trim) — nothing to inject (NODE_MESSAGE_EMPTY)"))
    else
      store.findNode(nodeId).flatMap {
        case None =>
          IO.pure(
            Left(
              s"Node '$nodeId' not found in project '$projectName' (active or archived) — " +
                s"check NodeList for the current topology (NODE_NOT_FOUND)"
            )
          )
        case Some(n) if NodeLifecycle.Terminal.contains(n.status) =>
          IO.pure(
            Left(
              s"Node '${n.name}' is terminal (status=${n.status}) — messages are refused: " +
                "a finished node is never retro-edited; create a new node instead (NODE_TERMINAL_NO_MESSAGE)"
            )
          )
        case Some(n) if n.status == NodeLifecycle.Running =>
          injectRunning(n, text, attribution)
        // interrupted（中断恢复语义批 2026-09-13 spec §2.2 不变量第 4 条）：
        // 节点非终态但「休眠」——**不得按终态拒收**（spec 原口径「现状即兼容」经实读
        // 核验**不成立**：旧路由落到 appendToTaskRoute 的终态分支，会把 interrupted
        // 误报成 terminal 并丢消息）。改走注入面：活会话（钩子窗口）→ ImmediateInput；
        // 会话已死/未登记（重启后未认领 / crashRecovery=false 降级）→ injectRunning
        // 的既有兜底 appendUndelivered（记录在 task 上「注入未达」，续跑/重跑经
        // NodeList(detail) 可读）——与 spec §2.2 期望语义一致且零丢失。
        case Some(n) if n.status == NodeLifecycle.Interrupted =>
          injectRunning(n, text, attribution)
        case Some(n) =>
          appendToTaskRoute(n, text, retried = false, attribution)
      }

    end if

  end sendNodeMessage

  /**
   * 裁定② running 路由：活会话 → ImmediateInput（source="system"，text 带
   * [NODE-MESSAGE] 前缀头）。
   *
   * 增量#5 修复（2026-09-07，slideblocks ×3 投递失败）：会话解析不再单依赖
   * nodeSessions 内存缓存——**实时解析优先**：D1 持久化的 node.sessionRef 与
   * Running 翻转同事务落库（重激活/重挂载后永为新会话的权威记录），缓存与
   * sessionRef 不一致时以 sessionRef 为准并**自愈缓存**；两者逐一试探
   * agentRegistry（任一命中即投递），全 miss 才走「注入未达」兜底。消除
   * 「blocked→NodeEdit 重激活后注入持续解析到已终结句柄」的窗口（旧句柄
   * 只可能存在于缓存侧；sessionRef 每次启动原子刷新）。注入为 fire-and-forget
   * tell（与 Mail/revalidate 先例同语义）——投递本身不可回执，事件日志恒记录
   * 注入尝试（留痕不丢）。
   */
  private def injectRunning(
    node: NodeDef,
    text: String,
    attribution: Option[InjectionAttribution] = None
  ): IO[Either[String, String]] =
    nodeSessions.get.map(_.get(node.id)).flatMap { cached =>
      // 候选序：sessionRef（持久权威，实时解析）→ cached（内存快路径）。去重。
      val candidates = (node.sessionRef.toList ++ cached.toList).distinct
      def tryDeliver(sids: List[String]): IO[Either[String, String]] =
        sids match
          case sid :: rest =>
            resources.agentRegistry.get.flatMap { reg =>
              reg.get(sid) match
                case Some(record) =>
                  val head = NodeEngine.nodeMessageHeader(node.name)
                  // 自愈：命中者非缓存值（重激活/重挂载后缓存滞后）→ 刷新缓存，
                  // 后续投递恢复快路径。
                  val heal =
                    if cached.contains(sid) then IO.unit
                    else
                      nodeSessions.update(_ + (node.id -> sid)) *>
                        logger.warn(
                          s"NodeMessage: node '${node.name}' (${node.id}) session cache self-healed -> $sid " +
                            s"(cached=${cached.getOrElse("-")}, sessionRef=${node.sessionRef.getOrElse("-")})"
                        )
                  heal *>
                    (record.ref ! AgentCommand.ImmediateInput(
                      text = s"$head\n\n$text",
                      source = Some(InjectionAttribution.SourceSystem),
                      // bluebubble 批（2026-09-12）腿②：Mail(address="node:<id>") 的发信方
                      // 随注入落到节点会话蓝气泡顶栏（sender = 分发器 agent 名）。
                      // source 仍取 "system"（R2 D-3 裁定：保持节点侧既有定名，`mail`
                      // 只出现在真实邮件来源处）。
                      sender = attribution.flatMap(_.sender),
                      senderTeam = attribution.flatMap(_.senderTeam),
                      eventType = attribution.flatMap(_.eventType),
                      // 气泡四段式统一批（2026-09-15）：PROJECT 段链首级（发送方所属
                      // 项目）同源透传；`None` ⇒ 发射点走 ②/③ 回落。
                      project = attribution.flatMap(_.project),
                      fromUser = false // ② 服务端注入（Node 消息），不是真人输入
                    )).void *>
                    FlowMapEventLog.append(
                      workspace,
                      projectName,
                      node.id,
                      NodeEngine.NodeMessageEventType,
                      s"injected -> live session $sid: $text"
                    ) *>
                    logger
                      .info(
                        s"NodeMessage -> node '${node.name}' (${node.id}) injected at turn boundary (session $sid, ${text.length} chars)"
                      )
                      .as(
                        Right(
                          s"Message delivered to running node '${node.name}' — it will be injected at the next turn boundary ([NODE-MESSAGE] header). " +
                            "Trace: flow-map-events.jsonl (node-message/injected)."
                        )
                      )
                case None => tryDeliver(rest)
            }
          case Nil =>
            // 全部候选查无会话（会话在检查与入队间已终结）→ 裁定②竞态兜底。
            appendUndelivered(node, text, candidates.mkString("/"))
      tryDeliver(candidates)
    }

  /**
   * 裁定②竞态兜底：注入不可达 → 记录追加「注入未达」（纯留痕写——只对节点
   * 存在性守卫，不挑剔状态；会话已终结时状态可能已翻终态，留痕必须不丢）。
   */
  private def appendUndelivered(node: NodeDef, text: String, sid: String): IO[Either[String, String]] =
    store
      .mutate { st =>
        st.nodes.get(node.id) match
          case Some(fresh) =>
            st.copy(nodes =
              st.nodes.updated(
                node.id,
                fresh.copy(task =
                  Some(fresh.task.getOrElse("") + s"\n\n${NodeEngine.nodeMessageSection(undelivered = true)}\n$text")
                )
              )
            )
          case None => st
      }
      .flatMap { s =>
        s.nodes.get(node.id).traverse_(emitUpdated) *>
          FlowMapEventLog.append(
            workspace,
            projectName,
            node.id,
            NodeEngine.NodeMessageEventType,
            s"not-delivered (session $sid already gone) -> recorded on task: $text"
          ) *>
          logger
            .warn(
              s"NodeMessage -> node '${node.name}' (${node.id}) session '$sid' gone before delivery — recorded on task as undelivered"
            )
            .as(
              Right(
                s"Node '${node.name}' session ended before the message could be injected — the message was recorded on the node's task " +
                  "as「注入未达」(trace preserved, nothing lost). If the node reruns (NodeEdit reactivation) it will read it; " +
                  "otherwise create a new node to carry the instruction."
              )
            )
      }

  /**
   * 裁定③ wiring/pending 路由：持久追加 task（节点启动时随 buildInput
   * 读到）。单事务 fresh 状态守卫：仍 ∈ {wiring, pending} → 追加写成；
   * 并发启动（running）→ 单次重试走 running 路由；并发终态化 → 裁定④拒绝。
   */
  private def appendToTaskRoute(
    node: NodeDef,
    text: String,
    retried: Boolean,
    attribution: Option[InjectionAttribution] = None
  ): IO[Either[String, String]] =
    store
      .mutate { st =>
        st.nodes.get(node.id) match
          case Some(fresh) if fresh.status == NodeLifecycle.Wiring || fresh.status == NodeLifecycle.Pending =>
            st.copy(nodes =
              st.nodes.updated(
                node.id,
                fresh.copy(task =
                  Some(fresh.task.getOrElse("") + s"\n\n${NodeEngine.nodeMessageSection(undelivered = false)}\n$text")
                )
              )
            )
          case _ => st // 状态已变 → 本事务不写（下方复核分流）
      }
      .flatMap { s =>
        s.nodes.get(node.id) match
          case Some(fresh) if fresh.status == NodeLifecycle.Wiring || fresh.status == NodeLifecycle.Pending =>
            // mutate 原子性：返回快照即本事务后状态——状态仍 ∈ 追加集 ⟺ 本事务写成
            emitUpdated(fresh) *>
              FlowMapEventLog
                .append(
                  workspace,
                  projectName,
                  node.id,
                  NodeEngine.NodeMessageEventType,
                  s"appended to task (status=${fresh.status}): $text"
                )
                .as(
                  Right(
                    s"Message appended to node '${fresh.name}' task (status=${fresh.status}) as「分发器补充（NodeMessage）」— " +
                      "the node will read it as part of its task when it starts. Trace: flow-map-events.jsonl (node-message/appended)."
                  )
                )
          case Some(fresh) if fresh.status == NodeLifecycle.Running && !retried =>
            // 并发启动窗口：pending 在检查与追加间被投递启动 → 消息应走注入面
            logger.info(
              s"NodeMessage -> node '${node.name}' started concurrently during append — rerouting to live injection"
            )
            injectRunning(fresh, text, attribution)
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            appendUndelivered(fresh, text, "rerun-race")
          case Some(fresh) =>
            IO.pure(
              Left(
                s"Node '${fresh.name}' became terminal (status=${fresh.status}) while the message was being appended — " +
                  "refused: create a new node instead (NODE_TERMINAL_NO_MESSAGE)"
              )
            )
          case None =>
            IO.pure(Left(s"Node '${node.id}' vanished before the message was appended (NODE_NOT_FOUND)"))
      }

  /**
   * WS 事件链富化单点（链级抽象 P0 + U1 多链归属批 + chainmodel 批三 ②）：全部节点
   * 事件 payload 经此统一补 chainId / chainIds / mergeUpstreamChains 条件键——判据单点
   * FlowMapStore.chainAttrsOf 一次分量派生给出三值（`chainId` = 所属（声明）链；
   * `chainIds` = 多链归属集 = 主链 id 首项 + 全量成员链，**仅 merge 节点**且可达成员链数
   * ≥2 才带；`mergeUpstreamChains` = 本次汇聚的上游链，同门控；普通节点恒不带 =
   * 单值 chainId 语义不变；与快照 buildNodeListPayload 同口径）。查无链
   * （节点已出双区/单节点链）→ payload 原样透传。WS 帧外壳（ProjectActor.emitNodeEvent）
   * 零改动——富化只发生在载荷体。
   */
  private[project] def emitWithChain(eventType: String, nodeId: String, payload: Json): IO[Unit] =
    store.chainAttrsOf(nodeId).flatMap { attrs =>
      val enriched = attrs.chainId.toList.map(c => "chainId" -> c.asJson) ++
        attrs.chainIds.toList.map(ids => "chainIds" -> ids.asJson) ++
        attrs.mergeUpstreamChains.toList.map(ids => "mergeUpstreamChains" -> ids.asJson)
      emitEvent(eventType, nodeId, if enriched.isEmpty then payload else payload.deepMerge(Json.obj(enriched*)))
    }

  /** WS nodeCreated（NodeEdit 创建后）。payload 与 NodeList 同构。 */
  def emitCreated(node: NodeDef): IO[Unit] =
    emitWithChain("nodeCreated", node.id, NodePayload.buildNodeJson(node, System.currentTimeMillis()))

  /**
   * WS nodeUpdated（NodeEdit 编辑/wiring 变更后）。payload 与 NodeList 同构，
   * 调用方须传 store 最终态（wiring 变更走 NodeTools.emitWiringUpdates）。
   */
  def emitUpdated(node: NodeDef): IO[Unit] =
    emitWithChain("nodeUpdated", node.id, NodePayload.buildNodeJson(node, System.currentTimeMillis()))

  /**
   * 显式投递（改接投递 §2.3：已完成节点结果 → 指定目标）。P1（spec §2.2 #4/#6）：
   * 人工改接 / D1 补投 / 重激活补投链统一经本函数。**门控按源 status 选门**（2026-09-12
   * 批，O-B 必做 2 / N2 收敛）：completed → `pass`、failed → `failed`（旧口径只查
   * 「on ∋ pass」，对 failed 源方向相反——`"(failed)B"` 边被静默跳过，而默认 pass 边
   * 反而把错误文本投出去）；cancelled → 不投（只留 info，取消节点的结果不构成输入）；
   * 无边（修复路径的悬空/孤儿 barrier）→ 保留既有「兜底放行」修复语义。
   * Nebula 分支：mode=result 通报 + V8 记账（**直投前查持久去重锚**，见下 M1 段），
   * mode=signal 只记账。缺口3：夹具信封排除——名字 ∧ 载荷双确认 → WARN + 不投
   * （结果滞留节点不删）。
   */
  def deliverOutTo(node: NodeDef, target: String, resultText: String): IO[Unit] =
    val edge = node.out.find(_.to == target)
    // 源 status 选门（完成腿 pass / 失败腿 failed）；cancel 源整体不投。
    val requiredGate = if node.status == NodeLifecycle.Failed then OutEdge.Failed else OutEdge.Pass
    if node.status == NodeLifecycle.Cancelled then
      logger.info(
        s"[gating] redelivery '${node.name}' -> '$target' skipped: source status=cancelled (a cancelled node's result is never a downstream input)"
      )
    // **`:loop` 控制边零投递（nrloop 一期 2026-09-12，红线①/B3 第四条）**：回边是
    // verdict 选通的控制信号，不是投递腿——`settleTo` 会把目标节点的 in-barrier 记成
    // 已消费并启动它（round-1 barrier 死锁 + 语义污染）。本分支把「改接补投递」等
    // 直投入口对控制边整体挡掉（执行腿由引擎显式驱动，见二期 `reloopTo`）。
    else if edge.exists(OutEdge.isLoopEdge) then
      logger.info(
        s"[gating] redelivery '${node.name}' -> '$target' skipped: ':loop' control edge (verdict routing — no delivery, no barrier settlement; the engine drives the re-run leg)"
      )
    else if edge.exists(e => !e.on.contains(requiredGate)) then
      logger.info(
        s"[gating] redelivery '${node.name}' -> '$target' skipped: edge on=[${edge.get.on.mkString(",")}] excludes $requiredGate (source status=${node.status})"
      )
    else
      target match
        // 字面量保留：case 模式匹配形态（改经常量会破坏 match）；身份名单点 = RootAgentIdentity.Name
        case "Nebula" =>
          if NodeEngine.isFixtureEnvelope(node) then
            logger.warn(
              s"[fixture-guard] excluded fixture envelope from manual redelivery: node '${node.name}' (${node.id}) status=${node.status} — name matches fixture family and task carries fixture marker"
            )
          else if edge.exists(_.mode == OutEdge.Signal) then markRootDelivered(node.id)
          // M1（U9-b，2026-09-12 批）：Nebula 腿直投前查**持久**去重锚 `nebulaDeliveredAt`
          // ——本分支旧口径只查 mode、不查账本 ⇒ 已投过的节点被改接/重复声明时会再投一次
          // （deliverToNebula 的 60s 窗是进程内抖动抑制，不承担持久幂等）。signal 出口
          // （上一分支）与**首次接线**（nebulaDeliveredAt 空）照常放行；零新字段。
          else if node.nebulaDeliveredAt.isDefined then
            logger.info(
              s"[dedup] redelivery '${node.name}' -> Nebula suppressed: nebulaDeliveredAt already set (persistent anchor; result already delivered)"
            )
          else enqueueRootNotify(s"[Node '${node.name}' completed]\n$resultText", node.name, "completed", Some(node.id))
        case t => settleTo(node, t)

    end if

  end deliverOutTo

  // ── 投递（§2.7）─────────────────────────────────────────

  /**
   * 节点目标结算（deliverOut / deliverFailed signal 边 / deliverOutTo 共用骨架）：
   * target.deliveredTo += node.id（dedup 原子记账）→ barrier 归零且非 running 则
   * fork 启动下游。载荷注入与否由 buildInput 按上游→下游边 mode 判定——本函数只管
   * 记账与启动（「signal 只记账归零 barrier」与「result 投载荷」在投递侧同形，
   * 差异全在输入装配侧，deps 同款拆分）。
   * 目标解析（20260909 in 丢失事故修复面③）：边 to 串有节点 id / 节点名两形态
   * （LLM 按名接线的自然写法原样落库；存量归档数据同），记账/启动统一落到解析后
   * 的 id——名字形态边不再是死边（原实现 findNode 按原始串查，名字边投递静默丢失）。
   */
  private[project] def settleTo(node: NodeDef, targetId: String): IO[Unit] =
    store.snapshot.map(s => OutEdge.resolveTargetId(s.nodes, targetId)).flatMap {
      case None =>
        logger.warn(s"Node '${node.name}' out target '$targetId' not found — result retained")
      case Some(tid) =>
        store.findNode(tid).flatMap {
          case None =>
            logger.warn(s"Node '${node.name}' out target '$targetId' not found — result retained")
          case Some(_) =>
            // 原子：target.deliveredTo += node.id（dedup）+ 更新
            store
              .mutate { s =>
                val t = s.nodes.get(tid)
                t match
                  case Some(tn) if !tn.deliveredTo.contains(node.id) =>
                    s.copy(nodes = s.nodes.updated(tid, tn.copy(deliveredTo = tn.deliveredTo :+ node.id)))
                  case _ => s
              }
              .flatMap { s =>
                val tn = s.nodes.get(tid)
                // R4：barrier 归零判定并入「待承接」标记（缺轨输入不得自动触发下游）。
                val allArrived = tn
                  .exists(tn2 => tn2.in.forall(upId => tn2.deliveredTo.contains(upId)) && tn2.pendingSuccession.isEmpty)
                if allArrived && tn.exists(_.status != NodeLifecycle.Running) then
                  // verdict 闸（merge-verdict-gate 批 2026-09-12，落点①/case (a)——barrier
                  // 结算后的启动判定；**#238 泛化 2026-09-15** 后对全部节点生效）：上游 verifier
                  // 判词非 pass 时本节点不得启动（保持 pending），直到该 verifier 当下 lastVerdict
                  // 变 pass。零副作用——barrier 记账
                  // （deliveredTo）已在上方 mutate 落库，本闸只挡「fork startNode」这一动作。
                  // mergefifo-engine 批 2026-09-13：**互斥闸**接在同一收口（落点③/纵深）——
                  // 同键已有更高优先 merge 在跑时，本 merge 的 barrier 照旧结算（记账已落库）、
                  // 只是不 fork 启动；释放后由资格回扫（落点②）当轮拉起。
                  tn match
                    case Some(t) =>
                      mergeVerdictHoldersOf(t).flatMap { holders =>
                        if holders.nonEmpty then logVerdictGateHold("settleTo (barrier settle)", t, holders)
                        else
                          mergeMutexHoldersOf(t).flatMap { queued =>
                            if queued.nonEmpty then logMutexHold("settleTo (barrier settle)", t, queued)
                            else forkStart(s"deliver-out -> $tid")(startNode(tid))
                          }
                      }
                    case None => IO.unit
                else IO.unit
                end if
              }
        }
    }

  /**
   * 完成投递（P1 语义门控，spec §2.2 #2；nrloop 一期加 **verdict 感知**）：
   * 沿 on ∋ pass 过滤出边后投递。fan-out：Nebula 通报与多个节点目标并存（旧单值
   * 拓扑 = 单边，行为等价）。mode=result → 投载荷（经 buildInput 注入）+ Nebula
   * 通报；mode=signal → 只记账归零 barrier，Nebula 只记账不通报（重投扫描按同门
   * 公式判定，不会重投）。dedup 用 deliveredTo（防改接重投）；canonical 合并同
   * (to,mode) 多边 on 集——一次终态至多投一次。
   *
   * **verdict 感知（设计 §3.3 #7）**：`role=verifier ∧ lastVerdict=fail` 的节点**不投
   * pass 边**（含 Nebula 通报）——被判定对象不合格时上游污染结果不得前递；选通由
   * `(fail)<target>:loop` **控制边**承载（`verifierFail` 已登记：记 verdict + 轮次 +
   * 计时；执行腿二期）。无 verdict（= 全部执行节点，及 verifier 的 pass/无申报）⇒
   * 照旧投 pass 边，行为逐字不变。
   *
   * 控制边不经本函数（`:loop` 不是 pass 边，且 `settleTo`/barrier/deliveredTo 三面
   * 都与它无关——红线①：控制边不进图、不进 barrier）。
   */
  private[project] def deliverOut(node: NodeDef, resultText: String): IO[Unit] =
    if node.role == NodeRoles.Verifier && node.lastVerdict.contains(VerdictFail) then
      logger.info(
        s"Node '${node.name}' (${node.id}) verdict=fail — pass edges NOT delivered (the judged target was rejected; " +
          "the re-run leg rides the ':loop' control edge, never settleTo/barrier)"
      )
    else
      val passEdges = OutEdge.canonical(node.out).filter(_.on.contains(OutEdge.Pass)).filterNot(OutEdge.isLoopEdge)
      rootDelivery(node, resultText, passEdges.partition(_.to == OutEdge.RootTarget)._1) *>
        passEdges.filterNot(_.to == OutEdge.RootTarget).traverse_(e => settleTo(node, e.to))

  private def rootDelivery(node: NodeDef, resultText: String, rootEdges: List[OutEdge]): IO[Unit] =
    if rootEdges.isEmpty then IO.unit // 悬空/无 pass 边：结果保留在 result（持久化）
    // ── R5（唯一语义变更点，作者裁定；b64 批 2026-09-13）：Nebula 边**保留声明**，
    // 运行时按通知策略裁决。策略 ≠ root ⇒ 完成通报被**抑制**（不投根）但仍
    // `markNebulaDelivered` 记账——否则 30s 补投扫描会把它复活（spec §5 表尾推论 2）。
    // 策略 = root（含存量 legacy：`:result` 的 pass Nebula 边）⇒ 逐字走今天的路径。
    // ⚠ M1（作者裁定「先不定义、沿用现网代码口径」）：本批**不为 `:signal` 边新增
    // 裁决分支**——下一条 `mode == Result` 前置判据原样保留，signal 边恒「只记账不通报」，
    // 策略（含 root）不得使之升根。
    else if !NotifyPolicy.completedRootVisible(node) then
      logger.info(
        s"Node '${node.name}' (${node.id}) has Nebula out-edge(s) but notify=${node.notifyPolicy.getOrElse("<legacy>")} " +
          "— completion root-notify SUPPRESSED (edge kept as declaration, runtime arbitration; R5). Ledger marked to keep the redelivery scan from reviving it."
      ) *>
        markRootDelivered(node.id)
    else if rootEdges.exists(_.mode == OutEdge.Result) then
      // notifybatch 批（2026-09-18，M-2）：改走 root 打包入口（决策①生产者侧合并）；
      // R5 抑制分支（上一支）与 `markNebulaDelivered` 记账口径**一字未动**。
      enqueueRootNotify(s"[Node '${node.name}' completed]\n$resultText", node.name, "completed", Some(node.id))
    else markRootDelivered(node.id)

  /**
   * out=Nebula：ImmediateInput 投 Nebula 根会话（source="node"，复用 flow 气泡语义）。
   * 气泡 header 契约（前端 chat.js injectedSourceLabel node 分支）：
   *   source = "node" → 显示段 NODE
   *   eventType = status（"completed" | "failed"）→ 显示段 COMPLETED / FAILED
   *   sender = "<projectName>/<nodeName>" → 显示段 <项目名> · <节点名>
   * 整条 header = NODE · <项目名> · <节点名> · <状态>。
   *
   * V8 (2026-09-03)：带 nodeId 的调用（deliverOut/deliverFailed/deliverOutTo/重投
   * 扫描）在 offer 后写 nebulaDeliveredAt 记账（at-least-once：offer 与记账之间
   * 崩溃 → 重投扫描会再投一次，宁重复不丢失）。nodeId=None（FeedbackRouter
   * escalate 复用通道）保持 fire-and-forget——blocked 反馈本体已持久化在节点上，
   * 升级消息不进重投扫描（避免对已处置的 blocked 再升级）。根 ref 缺失时不丢弃：
   * 不记账 → 周期重投扫描在根会话可用后补投。
   *
   * **notifybatch 批（2026-09-18）位置声明**：本方法是 root 通道**唯一 offer 单点**
   * （`ref ! ImmediateInput` 一行**逐字未动**）＝打包窗的 **flush 出口**；所有生产调用点
   * 改走其前置入口 [[enqueueRootNotify]]（缓冲入队 ⇒ 窗口结束合并一次 offer）。
   * ⇒ 生产路径上本方法只被 [[flushRootNotify]] 与两条旁路（`windowMs<=0` / P0 INTERRUPT）
   * 调用；「一条 = 一个 turn」的消费侧观感由此在**生产者侧**归零。
   */
  private[project] def deliverToRoot(
    text: String,
    nodeName: String,
    status: String,
    nodeId: Option[String] = None
  ): IO[Unit] =
    offerRootNotify(text, nodeName, status, nodeId).void

  /**
   * **offer 结果（F-1 修复面 · 2026-09-18 作者裁定 (a)：记账必须反映交付事实）**——
   * 三态穷尽 root 通道 offer 的全部出口；调用方据此决定**是否**逐件
   * `markNebulaDelivered`（「先标已发、实际没发」是缺陷本体）。
   *
   *  - `Offered`：`ref ! ImmediateInput` **已发出** ⇒ 交付事实成立（记账依据）；
   *  - `Suppressed`：60s 同 `(identity, status)` 窗抑制 ⇒ **未**发出。逐件腿按 V8 既有
   *    语义**仍**记账（该键即件自身身份 ⇒ 同键前件已落地，账本与通知解耦）；
   *    合并腿**不**据此记账——其键 = 首件 nodeName × 合并状态，**不代表**该批其余件已交付；
   *  - `Parked`：根 ref 缺失 ⇒ **未**发出、**不**记账，件滞留 `nebulaDeliveredAt` 空态
   *    ⇒ 30s 补投扫描重新入队（不丢件）。
   */
  private enum RootNotifyOffer:
    case Offered
    case Suppressed
    case Parked

  /**
   * root 通道 offer **单点实现**（`ref ! ImmediateInput` 一行逐字未动）；[[deliverToRoot]]
   * 与 [[flushRootNotify]] 的合并腿共用它 ⇒ 「发没发出」只有这一个判据源。
   */
  private def offerRootNotify(
    text: String,
    nodeName: String,
    status: String,
    nodeId: Option[String]
  ): IO[RootNotifyOffer] =
    resources.agentRegistry.get.map(_.get(rootSessionId).map(_.ref)).flatMap {
      case Some(ref) =>
        // 缺口4：同 (identity, status) 60s 窗口去重——抑制重复 offer（首投已入
        // 根会话队列），仍刷新账本（账本与通知解耦：本表只压秒级抖动，V8
        // at-least-once 语义不变）。不同 status 天然独立窗口（running→completed
        // 互不挡）。
        dedupeRootDelivery(nodeId.getOrElse(nodeName), status).flatMap {
          case true =>
            logger.warn(
              s"[dedup] suppressed duplicate Nebula delivery (identity=${nodeId.getOrElse(nodeName)}, status=$status, window=${NodeEngine.RootDedupWindowMs}ms, rootSession=$rootSessionId)"
            ) *>
              nodeId.traverse_(id => markRootDelivered(id)) *>
              IO.pure(RootNotifyOffer.Suppressed)
          case false =>
            (ref ! AgentCommand.ImmediateInput(
              text,
              source = Some("node"),
              eventType = Some(status),
              sender = Some(s"$projectName/$nodeName"),
              fromUser = false // ② 服务端注入（节点状态），不是真人输入
            )) *> nodeId.traverse_(id => markRootDelivered(id)) *>
              IO.pure(RootNotifyOffer.Offered)
        }
      case None =>
        logger.warn(
          s"Root session '$rootSessionId' not found — node result parked for redelivery scan (nodeName=$nodeName)"
        ) *>
          IO.pure(RootNotifyOffer.Parked)
    }

  // ── root 通道通知打包窗（notifybatch 批 2026-09-18；作者 2026-09-18 三决策）────────
  //
  // == 问题（诊断批 n-45385003 现读证据）==
  // 同一族节点终态事件在**分发器通道**已有打包窗（`DispatchNotify.NotifyBatch`，首件
  // 起算 5s 滚动窗 ⇒ N 件合并一次注入，现网 27 例 batch≥2），而 **root 通道**逐件
  // offer、零缓冲 ⇒ 密集扇出在 root 侧退化为「一条 = 一个 turn」（`batch=1` 178/178）。
  //
  // == 方案（作者决策①：**生产者侧打包**；消费侧零改）==
  // N 件在 producer 侧合成**一条** `ImmediateInput` ⇒ root 会话队列里恒只有一件 ⇒
  // `AgentActor.TurnBoundaryDrains.drainHead`（2026-09-15 裁定「每条独立成 turn、
  // 保序、禁合并语义」）**一字不改**且天然只得到一个 turn。形态**照抄**既有
  // `DispatchNotify`：滚动窗 + 首件起算（不随新件延长）+ 窗口结束合并注入 +
  // `windowMs<=0` 同步逐条（= 引入本窗之前的逐字行为，测试接缝/回滚面）。
  //
  // == 分层（作者决策②：**只保留一档**）==
  // 仅 **INTERRUPT/P0 不合并**（[[isRootNotifyInterrupt]]，逐条即时、不进缓冲）；
  // `completed` / `failed` / `blocked` / `cancelled` / `notice` **同窗打包、不单列**
  // （异常类一并合并、统一密度——决策②明文）。
  //
  // == 不折叠（作者决策③）==
  // **无超龄阈值、无折叠摘要行、无 `RootNotifyFoldMs`**：窗口内一律投**正文全文**
  // （合并 ≠ 摘要 ≠ 丢弃）；既有 `deliverStaleSummary`（>24h 历史欠账，`redeliver`
  // 扫描腿）**零行为改动**，只是与新窗并存。
  //
  // == 顺序 / 不丢不重 / at-least-once ==
  // 批内 FIFO、批间按窗口先后 ⇒ 拼接读出的 nodeId 序列 == 到达序列（决策①不破
  // 2026-09-15「到达顺序不变」）。上限 = [[rootNotifyBatchMaxValue]]，**溢出留队下一
  // 窗口**（决策③下唯一合法形态：不降格摘要行），故不丢件。窗口是**进程内**状态
  // （照抄 `DispatchNotify`）：崩溃即丢缓冲，但件**未** `markNebulaDelivered` ⇒ 仍在
  // [[redeliverUnconsumedNebulaResults]] 候选集，重启后补投 ⇒ at-least-once 不破。
  // **tell-then-mark 序不变**：flush 的 offer 成功后才逐件记账（`:5784` 语义原样）。
  // 缓冲层按 `(identity, status)` 去重（与 `dedupeNebulaDelivery` 同键）：实时腿入队后、
  // flush 记账前的 30s 补投扫描撞窗时，同一件不得重复注入。

  /** 生效窗长（现读；spec 走构造入参接缝 `rootNotifyQuietMs`）。 */
  private[project] def rootNotifyQuietMsValue: Long =
    rootNotifyQuietMs.getOrElse(nebflow.shared.Defaults.RootNotifyQuietMs)

  /** 生效条数上限（现读；`< 1` 归一到 1，防 0/负值把窗口变成永不排空）。 */
  private[project] def rootNotifyBatchMaxValue: Int =
    math.max(1, rootNotifyBatchMax.getOrElse(nebflow.shared.Defaults.RootNotifyBatchMax))

  /** 只读读数（spec/验收机械核对「已入队未注入件数」，不写状态、不派发）。 */
  private[project] def rootNotifyPendingCount: IO[Int] = rootNotifyBatchState.get.map(_.entries.size)

  /** 只读读数（本窗计时是否在走）。 */
  private[project] def rootNotifyWindowArmed: IO[Boolean] = rootNotifyBatchState.get.map(_.windowArmed)

  /**
   * **P0（不合并）判据单点**（作者决策②：分层只保留这一档）。
   *
   * 机械判据（零新字段、零 schema 变更）：`status ∈ {"interrupt", "immediate"}`
   * （大小写不敏感）。本通道的 `status` 形参即 `ImmediateInput.eventType`
   * （`deliverToRoot` 第 3 参）——`"interrupt"` 是 `NotificationHeader.StateLabels`
   * 既有词表项（`NotificationHeader.scala:98`），`"immediate"` 是 `delivery=immediate`
   * 在本通道的同义机械载体（本方法无 `delivery` 形参，而 `delivery` 只挂在
   * `ImmediateInput`/`UserInput` 上、其生产者为 Mail/deviceMail 腿——**本批零触碰**）。
   * 该档**不进任何缓冲**（不入队、不受窗长约束）⇒ 打事件序在前、单独成条（A3/R7）。
   * 其余全部同窗打包（含 `failed`/`blocked`/`cancelled`——决策②「异常类一并合并」）。
   */
  private def isRootNotifyInterrupt(status: String): Boolean =
    val s = status.trim.toLowerCase(java.util.Locale.ROOT)
    s == "interrupt" || s == "immediate"

  /**
   * **root 通知打包入口（生产者侧合并，决策①）**：节点终态投根的**唯一入口**
   * （`rootDelivery` / `deliverFailed` / `mergeBlockedByUpstreamFailure` /
   * `deliverOutTo` 手动重投腿 / 补投扫描 fresh 腿 / FeedbackRouter·DispatchNotify
   * escalate 腿**六路同入口**）——offer 单点 [[deliverToRoot]] 前插入本层。
   *
   * 三条旁路（不进缓冲、直接 offer = 逐字旧行为）：
   *   ① `windowMs <= 0`（关窗 = 回滚面/测试接缝）；
   *   ② P0 INTERRUPT 档（[[isRootNotifyInterrupt]]，决策②唯一豁免档）；
   *   ③ 该件已在缓冲中（`(identity, status)` 同键，防补投扫描撞窗重复注入）。
   * 其余：入队 + **首件**起算滚动窗；窗口结束时 [[flushRootNotify]] 合并注入一条。
   */
  private[project] def enqueueRootNotify(
    text: String,
    nodeName: String,
    status: String,
    nodeId: Option[String] = None
  ): IO[Unit] =
    val windowMs = rootNotifyQuietMsValue
    if windowMs <= 0 || isRootNotifyInterrupt(status) then deliverToRoot(text, nodeName, status, nodeId)
    else
      val entry = RootNotifyEntry(text, nodeName, status, nodeId)
      rootNotifyBatchState
        .modify { s =>
          if s.entries.exists(e => e.identity == entry.identity && e.status == entry.status) then (s, false)
          else
            val armWindow = !s.windowArmed
            (RootNotifyBatchState(s.entries :+ entry, windowArmed = true), armWindow)
        }
        .flatMap { armWindow =>
          if armWindow then (IO.sleep(windowMs.millis) *> flushRootNotify()).start.void else IO.unit
        }

  end enqueueRootNotify

  /**
   * **窗口结束的唯一出口**（M-3）：按上限取队首 ≤N 件 → **一次** offer（N=1 ⇒ 文本
   * 逐字不变；N≥2 ⇒ 正文分节 + header 保守）→ 逐件记账。
   *
   * **记账序（V8 tell-then-mark；F-1 修复面 · 2026-09-18 作者裁定 (a)：「记账必须反映
   * 交付事实」）**：合并腿**只有 offer 真的落地**（`RootNotifyOffer.Offered`，即
   * `ref ! ImmediateInput` 已发出）才 `traverse_(markNebulaDelivered)`。两条**未落地**
   * 路径一律**不记账** ⇒ 件留在 `nebulaDeliveredAt` 空态、下轮 30s 补投扫描重新入队
   * （不丢件；宁重复不丢失）：
   *   ① 根 ref 缺失（`Parked`——`deliverToNebula` 只 WARN + 不记账，`:5789` 语义原样）；
   *   ② 60s 同键窗抑制（`Suppressed`——**未**发出；合并腿的键 = 首件 nodeName × 合并
   *      状态，不代表该批其余件已交付）。
   * 单件腿（`case one :: Nil`）走 [[deliverToRoot]] 原路径**逐字保留**（抑制/落地两态
   * 均记账，键 = 件自身身份）；`private[project]`：spec 可显式驱动（上限/保序用例无需等
   * 真实窗长）。
   */
  private[project] def flushRootNotify(): IO[Unit] =
    rootNotifyBatchState
      .modify { s =>
        val (drained, rest) = s.entries.splitAt(rootNotifyBatchMaxValue)
        // 本窗排空：溢出件留队 ⇒ 计时随之下一次起算（不延续本窗残时）
        (RootNotifyBatchState(rest, windowArmed = rest.nonEmpty), drained)
      }
      .flatMap { drained =>
        if drained.isEmpty then IO.unit
        else
          val entries = drained.toList
          val offer = entries match
            // 单件：文本 / header / 去重键 / 记账**逐字同今天**（A2 单件零漂移）
            case one :: Nil => deliverToRoot(one.text, one.nodeName, one.status, one.nodeId)
            // 多件：正文分节 + header 保守（T-6(a)：不新增 header 语义 ⇒ `NotificationHeader`
            // 与前端 `chat.js` **零改动**；去重键 = 首件身份 × 合并状态）；**落地才**逐件记账
            case many =>
              offerRootNotify(
                mergedRootNotifyText(many),
                many.head.nodeName,
                mergedRootNotifyStatus(many),
                nodeId = None
              ).flatMap {
                // F-1（作者裁定 (a)）：`Offered` = 交付事实成立 ⇒ 逐件记账；`Parked`/`Suppressed`
                // = 本批**没发出去** ⇒ 一件都不记（否则件被标已发却未发、补投判据
                // `n.nebulaDeliveredAt.isEmpty` 永不命中 ⇒ 整窗永久丢失，宁重复不丢失）。
                case RootNotifyOffer.Offered => many.flatMap(_.nodeId).distinct.traverse_(markRootDelivered)
                case _ => IO.unit
              }
          offer *>
            logger.info(
              "root-notify batch flushed",
              "event" -> "root-notify-batch-flushed",
              "batch" -> entries.size.toString,
              "windowMs" -> rootNotifyQuietMsValue.toString,
              "nodes" -> entries.map(_.identity).mkString(",")
            ) *>
            rearmRootNotifyWindowIfPending
      }

  /**
   * 溢出留队 ⇒ 下一窗口（决策③：不折叠、不降格摘要行 ⇒ 唯一合法形态 = 留队）。
   * 只由 [[flushRootNotify]] 尾调：单点排空 ⇒ 无并发双排空（每次 flush 恒由上一窗
   * 的出口链式驱动，或由 spec 显式调用）。
   */
  private def rearmRootNotifyWindowIfPending: IO[Unit] =
    rootNotifyBatchState.get.flatMap { s =>
      if s.entries.nonEmpty then (IO.sleep(rootNotifyQuietMsValue.millis) *> flushRootNotify()).start.void
      else IO.unit
    }

  /**
   * 合并正文（N≥2 才被调用）：批头一行 + 逐件分节，**每件正文全文**（决策③不折叠 ⇒
   * 无摘要行、无截断）+ 每件带 `status` 与 `nodeId` ⇒ 机械可核（多重集/保序/不丢）。
   * 分节行形如 `── [i/N] [status] <nodeName> (<nodeId>) ──`（nodeId 缺失时回落
   * nodeName，与 [[RootNotifyEntry.identity]] 同口径）。
   */
  private def mergedRootNotifyText(entries: List[RootNotifyEntry]): String =
    val head = s"[Node 本批 ${entries.size} 件终态通知（root 通道打包窗合并，项目 $projectName）]"
    val body = entries.zipWithIndex
      .map((e, i) => s"── [${i + 1}/${entries.size}] [${e.status}] ${e.nodeName} (${e.identity}) ──\n${e.text}")
      .mkString("\n\n")
    s"$head\n$body"

  /**
   * 合并件的 header `eventType`（保守：沿用既有字段与既有词表，不新增批级语义）。
   * 强提醒优先（口径同既有 [[deliverStaleSummary]]：混含 failed ⇒ `failed`）：
   * failed > blocked > cancelled > 首件 status（全 completed ⇒ `completed`）。
   * ⚠ 逐件真实状态在**正文分节行**内（header 只表达本批的主状态）。
   */
  private def mergedRootNotifyStatus(entries: List[RootNotifyEntry]): String =
    if entries.exists(_.status == NodeLifecycle.Failed) then NodeLifecycle.Failed
    else if entries.exists(_.status == NodeLifecycle.Blocked) then NodeLifecycle.Blocked
    else if entries.exists(_.status == NodeLifecycle.Cancelled) then NodeLifecycle.Cancelled
    else entries.head.status

end NodeDelivery
