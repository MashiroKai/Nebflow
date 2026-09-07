package nebflow.core.project

import cats.effect.{Deferred, Fiber, IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.PathUtil
import nebflow.core.entity.EntityLoader
import nebflow.core.node.NodeRunner
import nebflow.core.plugin.{PluginMcpManager, PluginRegistry, PluginsConfig}
import nebflow.core.presets.PresetStore
import nebflow.core.skill.SkillService
import nebflow.core.tools.{BgTaskRegistry, PresetResolver}
import nebflow.shared.Message

import scala.concurrent.duration.*

/**
 * NodeEngine —— 节点执行内核（#28 阶段 0，方案 §2.7 + §3.1）。
 *
 * 职责：
 * 1. spawn 节点 agent（NodeRunner 共享内核；sessionId = "node-<uuid>"，干净上下文、
 *    leaf、无记忆无持久会话；projectRoot = worktree 或工作区）
 * 2. 结果捕获（bridge actor + Deferred）：节点 agent 完成 → 其最终输出文本即结果
 *    （无 FlowReport/verdict/slots）→ 写节点 result（持久化）+ status=completed +
 *    ttlExpireAt=+24h → 沿 out 投递
 * 3. 投递（§2.7）：out=节点 → 下游 deliveredTo 记录 + barrier 归零启动下游（输入 =
 *    task 上下文 + 各上游 result 带 === Node <name> === 头）；out=Nebula →
 *    ImmediateInput("[Node '<name>' completed]\n<result>", source="node") 投根会话；
 *    out=null → 结果保留（接线后从活动/归档自动投递）；failed →
 *    ImmediateInput("[Node '<name>' failed]\n<err>")
 * 4. NodeCancel：运行中节点 → cancel 信号 → 停 agent + status=cancelled（不投递）
 * 5. blocked 终态（20260902 反馈重入设计 §1/§2）：completeNode 入口经 BlockedReader
 *    解析——最终输出以 BLOCKED 锚定 + JSON 体 → status=blocked（不结算下游）+
 *    FeedbackRouter 重入/升级路由；非 BLOCKED 开头 → completed 原路径无损降级
 *
 * 硬约束：不设运行超时（TaskStuckWatcher 10min 零活动兜底）；节点无 Mail 身份。
 */
class NodeEngine(
  val store: FlowMapStore,
  system: ActorSystem,
  resources: SharedResources,
  val wsSendFn: Json => IO[Unit],
  workspace: String,
  val rootSessionId: String,
  /** 项目名：Node 完成通知蓝气泡 header 第二段（NODE · <项目名> · <节点名> · <状态>）。 */
  val projectName: String,
  /** blocked 反馈档位（设计 §7.1）：auto（默认）| escalate-only。挂载时读 project.json。 */
  val feedbackMode: String = FeedbackRouter.ModeAuto,
  /** WS 事件推送（type → payload 载体）。 */
  emitEvent: (String, String, Json) => IO[Unit],
  /** 产物完整性闸门的 git 执行器（audit 20260905）：默认真实 git 只读命令；
    * spec 注 stub。闸门本体与 kill-switch 见 CompletionGate。 */
  gateRunner: CompletionGate.GitRunner = CompletionGate.defaultRunner,
  /** 节点完成闸等待总上限兜底（bgtask-completion-gate 批）：单个 bg 等待期超过
    * 此值 → 节点 failed+注明（防通知链断裂永久悬挂）。默认 Defaults.BgGateWaitTimeoutMs
    * （system prop nebflow.bgtask.gate.timeoutMs 可调）；spec 注小值验红。 */
  bgWaitCapMs: Long = nebflow.shared.Defaults.BgGateWaitTimeoutMs
):
  private val logger = NebflowLogger.forName("nebflow.node.engine")

  /** blocked 反馈路由器（§2.2/§2.3）：档位决策 + 项目级频率保护 + 重入/升级执行。
    * escalate 通道 = 本引擎的 deliverToNebula（eventType="blocked"，前端 label 自动 BLOCKED）。 */
  private[project] val feedbackRouter: FeedbackRouter = new FeedbackRouter(
    projectName = projectName,
    workspace = workspace,
    feedbackMode = feedbackMode,
    escalate = (text, nodeName) => deliverToNebula(text, nodeName, NodeLifecycle.Blocked)
  )

  /** dispatch-notify 通道（2026-09-05 批）：节点终态结果回流分发器的单一通知入口
    * （completion 接线 / failed-blocked 预留；防循环+预算+持久去重见 DispatchNotify）。
    * 挂接点仅两处：completedNode 尾部直触发 + ProjectActor.TtlTick 周期补投。 */
  private[project] val dispatchNotify: DispatchNotify = DispatchNotify.forEngine(
    store, workspace, projectName, rootSessionId,
    // notice 语义（非 blocked）：预算耗尽时节点保持 completed，前端不可标 BLOCKED。
    escalate = (text, nodeName) => deliverToNebula(text, nodeName, DispatchNotify.NoticeEventType),
    emitUpdated = emitUpdated
  )

  /** 运行中节点 → cancel 信号（NodeCancel 用）。 */
  private val running: Ref[IO, Map[String, Deferred[IO, Unit]]] = Ref.unsafe[IO, Map[String, Deferred[IO, Unit]]](Map.empty)

  /** NodeMessage 注入链（20260905 机制批，作者裁定②）：running 节点 nodeId →
    * sessionId 活映射。runWithAgent 在飞登记时与 running 表同步置位、清理段
    * 同步移除——sendNodeMessage 据此定位节点会话 actor 发 ImmediateInput
    * （turn 边界 drain 既有基建：pendingImmediateInputs + TurnBoundaryDrains
    * .drainHead + CompactionQueueStore 压缩窗口保全，全部复用零重造）。
    * 无映射 = 会话已终结（裁定②竞态兜底入口：回退记录追加「注入未达」）。 */
  private[project] val nodeSessions: Ref[IO, Map[String, String]] =
    Ref.unsafe[IO, Map[String, String]](Map.empty)

  /** boot 恢复排队集（crash-recovery 批 2026-09-07）：快段已认领（翻 Pending）但慢段
    * 尚未 startNode(resume) 的节点 id——settleRunnableSweep 对其跳过（资格回扫的
    * 新鲜启动会与续跑竞速，CAS 裁决虽不双 spawn 但恢复降级为无 transcript 的全新重
    * 跑）。进程内 boot 生命周期状态：慢段逐节点认领前移除；慢段异常（节点级
    * handleErrorWith）后该节点回归正常 pending 池，settle 回扫以新鲜路径兜底启动
    * （R4 降级语义——恢复优先、收殓兜底）。 */
  private[project] val bootRecoveryQueue: Ref[IO, Set[String]] =
    Ref.unsafe[IO, Set[String]](Set.empty)

  /** 缺口4（2026-09-04 重复投递去重）：同 (identity, status) 60s 窗口内重复
    * Nebula 通知抑制——进程内时间窗 map（固定窗，顺路淘汰过期条目）。与 V8
    * nebulaDeliveredAt 账本**正交**：账本管跨重启 at-least-once（持久化，管
    * 「结果是否到达过 Nebula」），本表管秒级抖动（内存，管「同一通知短窗内
    * 重复轰炸」——09-03 ProjectCreate 合并回报三连投实证）。窗口内重复被抑制
    * 时仍 markNebulaDelivered，账本一致性不破坏。identity = nodeId（无 nodeId
    * 的 fire-and-forget 通道用 nodeName——escalate 各节点独立窗口）。 */
  private[project] val recentNebulaDeliveries: Ref[IO, Map[(String, String), Long]] =
    Ref.unsafe[IO, Map[(String, String), Long]](Map.empty)

  /** 节点是否正在运行（ProjectActor 状态查询用）。 */
  def isRunning(nodeId: String): IO[Boolean] = running.get.map(_.contains(nodeId))

  /** 取消运行中节点（NodeCancel/ProjectActor 转发）：触发 cancel 信号 → 停 agent。 */
  def cancelNodeById(nodeId: String): IO[Unit] =
    running.get.map(_.get(nodeId)).flatMap {
      case Some(sig) => sig.complete(()).void
      case None => IO.unit
    }

  /** 死会话 running 节点收殓（清场 c-③，20260903 03:04 清场误杀事故复盘）：
    * status=running 但无在飞执行 fiber（running 表无此节点——会话死于传输中断 /
    * 实例重启后内存态清空）→ 直接终态化 cancelled + 显示 TTL（cancelNode 全链）
    * + 审计事件，修复 NodeCancel「cancel signal sent」但状态不落终态的假成功。
    * 误杀防护（硬约束）：有在飞 fiber = 活会话（取消信号可达）→ Left 拒绝，
    * 本方法绝不触碰活会话节点。幂等：非 running → Left（不重复终态化）。 */
  def reapStaleRunning(nodeId: String): IO[Either[String, String]] =
    store.getNode(nodeId).flatMap {
      case None => IO.pure(Left(s"Node '$nodeId' not found"))
      case Some(n) if n.status != NodeLifecycle.Running =>
        IO.pure(Left(s"Node '${n.name}' is not running (status=${n.status}) — nothing to reap"))
      case Some(n) =>
        isRunning(nodeId).flatMap {
          case true =>
            IO.pure(Left(s"Node '${n.name}' has a live execution fiber — use the normal cancel signal, not reap"))
          case false =>
            logger.warn(s"Node '${n.name}' ($nodeId) reaped: status=running but no live execution fiber (dead session / instance restart)")
            FlowMapEventLog.append(workspace, projectName, nodeId, "reaped",
              "dead running session finalized as cancelled (no live execution fiber; NodeCancel reap)") *>
              cancelNode(nodeId).as(Right(s"Node '${n.name}' reaped — dead running session finalized as cancelled (display TTL)"))
        }
    }

  /** bg-wait 标注写点（僵尸收敛批 2026-09-06，作者「首要缺口 = 补显示」）：节点完成
    * 闸（bgtask-completion-gate 批）在持留等待后台任务时把 node `bgWait` 置为在途
    * 等待型任务快照、全部清空/终态化时清 None——NodePayload 条件字段随之带/不带，
    * 前端据此标「等待后台任务」徽标（与真僵尸区分，避免把设计内等待误判成
    * dead-session running）。仅对 running 节点写（fresh 守卫）；状态已变 → 拒写
    * （R2 竞态纪律），flow-map.json 不残留过期 bgWait。值未变 → 不写不 event
    * （幂等：终态化清 None 时若原本就 None，不重复发 nodeUpdated）。 */
  private def setNodeBgWait(nodeId: String, waiting: List[BgTaskRegistry.ActiveTask]): IO[Unit] =
    val desc =
      if waiting.isEmpty then None
      else Some(s"${waiting.size} background task(s): " + waiting.map(t => s"'${t.description}'").mkString(", "))
    store.mutate { st =>
      st.nodes.get(nodeId) match
        case Some(fresh) if fresh.status == NodeLifecycle.Running && fresh.bgWait != desc =>
          st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(bgWait = desc)))
        case _ => st
    }.flatMap { s =>
      s.nodes.get(nodeId).filter(_.bgWait == desc).traverse_(emitUpdated)
    }

  /** 在飞登记三表的对称清理（僵尸收敛批 2026-09-06 清理硬化，根因报告漏洞①）：
    * running / nodeSessions / agentRegistry 三表在 runWithAgent 内登记后，只能由该
    * fiber 自己在 race 落定后清理——fiber 崩溃/被外部 cancel/悬死在 race 时三表泄漏
    * （制造「假活会话」canary → isRunning 恒 true → 误杀防护被击穿，
    * reapStaleRunning/abandon/NodeCancel 全部拒绝，不可回收僵尸）。本方法作为
    * runWithAgent 整体 `.guarantee` finalizer：任意退出路径（正常/崩溃/异常/cancel）
    * 都对称移除。身份感知（防误删竞发赢家）：只在 running 表条目是**本 fiber 的
    * cancelSig** 时移除（LostRace/Aborted 败方自己的 sig 已被分支移除，此处 no-op；
    * 赢家条目保留）；nodeSessions 只在映射到本 sessionId 时移除（nodeId 是共享键，
    * 盲删会误删赢家条目）；agentRegistry 只移除本 sessionId。与既有清理段
    * （:947-953）同点幂等（先到先清，后到 no-op）。 */
  private def cleanupRunTables(nodeId: String, sessionId: String, cancelSig: Deferred[IO, Unit]): IO[Unit] =
    running.modify { m =>
      if m.get(nodeId).exists(_.eq(cancelSig)) then (m - nodeId, ())
      else (m, ())
    } *>
      nodeSessions.update { m =>
        if m.get(nodeId).contains(sessionId) then (m - nodeId) else m
      } *>
      resources.agentRegistry.update(_ - sessionId)

  /** 死会话 running 节点的自动收敛（僵尸收敛批 2026-09-06；与 NodeCancel-stale /
    * abandon 的人力收殓区分——本方法走**自动** watchdog 路径）。收敛目标取 **failed**
    * 而非 reapStaleRunning 的 cancelled：cancelled 不投递下游（cancelNode 不调
    * deliverFailed/settleDeps），下游 barrier/deps 永不释放——正是「下游永久阻塞」
    * 这门核心危害；failed 沿 deliverFailed 给下游一个 collect 占位结算（merge 型转
    * blocked），barrier 归零，下游不再悬挂。fresh 守卫（R2）：只在 `status==Running`
    * 时收敛——节点已终态/状态已变 → 拒写（并发完成/取消不被本路径覆盖成 failed）。
    * 审计事件独立（dead-session-reaped），与既有 reaped/abandoned 区分。 */
  private def autoFailDeadRunning(nodeId: String, err: String): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(
              status = NodeLifecycle.Failed,
              result = Some(err),
              completedAt = Some(now),
              ttlExpireAt = Some(now + NodeEngine.TtlDisplayMs))))
          case _ => st // 已终态/消失/状态已变 → 拒写（R2 竞态纪律）
      }
      _ <- s.nodes.get(nodeId) match
        case Some(failed) if failed.status == NodeLifecycle.Failed =>
          emitEvent("nodeUpdated", nodeId, NodePayload.buildNodeJson(failed, now)) *>
            logger.warn(s"Node '${failed.name}' auto-finalized failed (dead session): ${err.take(200)}") *>
            FlowMapEventLog.append(workspace, projectName, nodeId, "dead-session-reaped",
              s"dead-session node auto-converged to failed: ${err.take(220)}") *>
            deliverFailed(failed, err)
        case _ => IO.unit
    yield ()

  /** 死会话 running 周期对账（僵尸收敛批 2026-09-06；ProjectActor.TtlTick 30s 驱动
    * ——复用既有心跳点零新调度器，与 settleRunnableSweep 同族）。对每个 status=Running
    * 节点做死会话判定，死者自动收敛 failed（autoFailDeadRunning）。判定口径（作者
    * 2026-09-06 裁定：收敛必须以「可证明无活会话 且 无在途后台任务」触发）：
    *   - 活会话 = nodeSessions(sessionId) 在 agentRegistry 有记录（agent 已 spawn 且
    *     未清理）——有记录即视为活（保守，宁可漏一期不可误杀）；
    *   - nodeSessions 无映射 = 会话未登记/登记已清（重启内存清空 / 翻转即崩溃於登记
    *     前）→ 用 running 表再判：无在飞 fiber（running 表无此节点）才收敛（有 fiber
    *     却无 nodeSessions 是异常带，保守不收敛）；
    *   - 记录缺失但过了 spawn 窗口（StaleSpawnGraceMs）→ 判死（fiber 已死未登记
    *     agent，而非刚启动的 spawn 窗口内）；
    *   - 「无在途后台任务」= BgTaskRegistry.waitingFor(sessionId) 为空——非空 = 设计
    *     内等待后台任务（节点按设计保持 running，**不得收敛/提前结算**——作者红线，
    *     禁把「等待后台任务」当 bug 提前结算）。
    * 不误杀活会话：registry 有活记录 / waitingFor 非空 → 一律跳过。 */
  def settleStaleRunningNodes(): IO[Unit] =
    store.snapshot.flatMap { s =>
      s.nodes.values.filter(_.status == NodeLifecycle.Running).toList
        .filterA { n =>
          val deadSession: IO[Boolean] =
            nodeSessions.get.map(_.get(n.id)).flatMap {
              case None =>
                running.get.map(m => !m.contains(n.id))
              case Some(sid) =>
                resources.agentRegistry.get.map { reg =>
                  val pastSpawnWindow =
                    n.startedAt.exists(st => System.currentTimeMillis() - st > NodeEngine.StaleSpawnGraceMs)
                  !reg.contains(sid) && pastSpawnWindow
                }
            }
          deadSession.flatMap {
            case false => IO.pure(false)
            case true =>
              nodeSessions.get.map(_.get(n.id)).flatMap {
                case Some(sid) => BgTaskRegistry.waitingFor(sid).map(_.isEmpty)
                case None => IO.pure(true)
              }
          }
        }
        .flatMap { zombies =>
          zombies.traverse_ { z =>
            autoFailDeadRunning(z.id,
              "node session dead (no live session, no in-flight background task) — auto-converged to failed by dead-session watchdog")
          }
        }
    }

  // ── boot-time 崩溃恢复（crash-recovery 批 2026-09-07，设计 §3.2 快段/慢段）──
  //
  // 快段（同步秒级）：对崩溃残留 status=Running 节点按持久层完整性三分类（§3.3）：
  //   (a) checkpoint 完整 / (b) mid-turn —— 磁盘不可区分，统一同路径：认领 = 翻回
  //       Pending + 清 bgWait + boot-recovery 事件 + 入 bootRecoveryQueue，续跑交慢段；
  //   (c) sessionRef 无值 / transcript 缺失/损坏/空 —— failNode("crash recovery:
  //       session transcript lost/corrupt") 走既有 deliverFailed 链（姊妹批挂接后自动
  //       获分发器 failed 通知）。
  // 认领动作改变节点状态使其即刻脱离 watchdog（settleStaleRunningNodes 只看 Running）
  // 与资格回扫（bootRecoveryQueue 排除）的处置口径——sweep 与 watchdog 的竞速由
  // 挂载顺序结构性关闭（快段先于 projectTtlScanner 启动完成）。
  // 判定材料 = sessionRef（D1，与 startedAt 同事务落库）→ SessionStore transcript
  // 文件。分类读取用 loadMessagesForSession（decode 失败侧自动备份损坏文件——
  // SessionStore 既有语义，非静默）。

  /** 快段单节点：分类 + 认领。返回：
    * - Some(Right(ctx))：已认领（翻 Pending + 清 bgWait + 事件 + 入队），待慢段
    *   startNode(ctx) 续跑；
    * - Some(Left(reason))：(c) 类已 failNode（reason 含 transcript 指针），下游走
    *   deliverFailed 既有链；
    * - None：非候选（非 Running）或认领事务败给并发状态变更（fresh 守卫拒写）。
    * 节点级异常不在此吞——调用方（sweep）逐节点 handleErrorWith，残余 Running 由
    * watchdog 兜底（R4）。 */
  def bootRecoveryClaim(n: NodeDef): IO[Option[Either[String, NodeEngine.ResumeContext]]] =
    if n.status != NodeLifecycle.Running then IO.pure(None)
    else
      def failClaim(reason: String): IO[Option[Either[String, NodeEngine.ResumeContext]]] =
        // (c) 类：仍 Running 才处置（fresh 守卫——与并发终态化互斥）；failNode 自带
        // deliverFailed + WS；boot-recovery 事件留痕（禁止静默自愈）。
        store.getNode(n.id).flatMap {
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            failNode(n.id, reason) *>
              FlowMapEventLog.append(workspace, projectName, n.id, NodeEngine.BootRecoveryEventType,
                s"failed (class c): $reason") *>
              logger.warn(s"[boot-recovery] node '${n.name}' (${n.id}) failed: $reason").as(Some(Left(reason)))
          case _ => IO.pure(None)
        }
      def resumeClaim(ctx: NodeEngine.ResumeContext, claimNote: String): IO[Option[Either[String, NodeEngine.ResumeContext]]] =
        // (a)/(b) 类认领：Running → Pending 单事务翻转（CAS：并发终态化/已处置 → 拒写
        // 返回 None）+ 清 bgWait（等待集随进程蒸发 G4，resume prompt 已附死亡告知）。
        store.mutateWithResult { s =>
          s.nodes.get(n.id) match
            case Some(f) if f.status == NodeLifecycle.Running =>
              (s.copy(nodes = s.nodes.updated(n.id, f.copy(
                status = NodeLifecycle.Pending,
                bgWait = None))), true)
            case _ => (s, false)
        }.flatMap { (s, claimed) =>
          if !claimed then IO.pure(None)
          else
            s.nodes.get(n.id).traverse_(emitUpdated) *>
              bootRecoveryQueue.update(_ + n.id) *>
              FlowMapEventLog.append(workspace, projectName, n.id, NodeEngine.BootRecoveryEventType,
                s"claimed (resume): $claimNote") *>
              logger.info(s"[boot-recovery] node '${n.name}' (${n.id}) claimed for rehydrate: $claimNote")
                .as(Some(Right(ctx)))
        }
      // ── 分类（§3.3 判定表；loop 节点裁定③双会话续接）──
      if n.loop.exists(_.enabled) then
        n.sessionRef match
          case None =>
            failClaim(s"crash recovery: session transcript lost (loop node has no sessionRef persisted — predates crash recovery; " +
              s"loopRound=${n.loopRound}, loopPhase=${n.loopPhase.getOrElse("-")} persisted for audit)")
          case Some(workerSid) =>
            resources.sessionStore.loadMessagesForSession(workerSid).attempt.flatMap {
              case Right(workerMsgs) if workerMsgs.nonEmpty =>
                // verify transcript 可缺（每轮输入自足）：缺 → 空 transcript 水合（等价新会话）。
                val verifySid = n.sessionRefVerify
                verifySid.traverse(sid => resources.sessionStore.loadMessagesForSession(sid).attempt.map {
                  case Right(msgs) => msgs
                  case Left(_)     => Nil // 既有 BackoffSupervisor 同款容错：坏档不阻断恢复
                }).map(_.getOrElse(Nil)).flatMap { verifyMsgs =>
                  val round = math.max(n.loopRound, 1)
                  val phase = n.loopPhase.getOrElse(NodeEngine.LoopPhaseWorker)
                  resumeClaim(
                    NodeEngine.ResumeContext(
                      sessionId = workerSid,
                      recoveredMessages = workerMsgs,
                      resumePrompt = NodeEngine.loopWorkerResumePrompt(n, round, phase, workerMsgs.size),
                      verifySessionId = verifySid,
                      verifyMessages = verifyMsgs,
                      loopResumeRound = round,
                      loopResumePhase = Some(phase)),
                    s"loop dual-session resume (worker=$workerSid msgs=${workerMsgs.size}, verify=${verifySid.getOrElse("-")} msgs=${verifyMsgs.size}, round=$round, phase=$phase)")
                }
              case Right(_) =>
                failClaim(s"crash recovery: session transcript lost/corrupt (loop worker sessionRef=$workerSid empty or missing; " +
                  s"loopRound=${n.loopRound}, loopPhase=${n.loopPhase.getOrElse("-")} persisted for audit)")
              case Left(e) =>
                failClaim(s"crash recovery: session transcript unreadable (loop worker sessionRef=$workerSid: ${Option(e.getMessage).getOrElse(e.toString)})")
            }
      else
        n.sessionRef match
          case None =>
            failClaim("crash recovery: session transcript lost (no sessionRef persisted — node predates crash recovery)")
          case Some(sid) =>
            resources.sessionStore.loadMessagesForSession(sid).attempt.flatMap {
              case Right(msgs) if msgs.nonEmpty =>
                resumeClaim(
                  NodeEngine.ResumeContext(
                    sessionId = sid,
                    recoveredMessages = msgs,
                    resumePrompt = NodeEngine.nodeResumePrompt(projectName, n, msgs.size, n.bgWait)),
                  s"resume (sessionRef=$sid msgs=${msgs.size}${n.bgWait.fold("")(w => s", bgWait cleared: ${w.take(120)}")})")
              case Right(_) =>
                failClaim(s"crash recovery: session transcript lost/corrupt (sessionRef=$sid)")
              case Left(e) =>
                failClaim(s"crash recovery: session transcript unreadable (sessionRef=$sid: ${Option(e.getMessage).getOrElse(e.toString)})")
            }

  /** 慢段单节点：出队 + startNode(resume)。出队即归还 settle 回扫资格——本 fiber
    * 紧接 startNode（CAS 翻转与任何并发新鲜启动互斥裁决，微秒窗口败者安静）；失败
    * （agent 缺失等）则节点留在正常 pending 池由资格回扫新鲜兜底（R4 降级）。 */
  def bootRecoveryStart(nodeId: String, ctx: NodeEngine.ResumeContext): IO[Unit] =
    bootRecoveryQueue.update(_ - nodeId) *>
      logger.info(s"[boot-recovery] node $nodeId rehydrating (session=${ctx.sessionId})") *>
      startNode(nodeId, Some(ctx))

  /** WS nodeRemoved（TTL 移除/归档后通知前端移除卡片）。payload = 节点最终态
    * （NodeList 同构——归档区兜底查得，ttlLeftSec=0），不再发空对象。 */
  def emitRemoved(nodeId: String): IO[Unit] =
    store.findNode(nodeId).flatMap {
      case Some(n) => emitEvent("nodeRemoved", nodeId, NodePayload.buildNodeJson(n, System.currentTimeMillis()))
      case None    => emitEvent("nodeRemoved", nodeId, Json.obj("id" -> nodeId.asJson))
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
  def sendNodeMessage(nodeId: String, message: String): IO[Either[String, String]] =
    val text = message.trim
    if text.isEmpty then
      IO.pure(Left(s"'message' must be non-empty (trim) — nothing to inject (NODE_MESSAGE_EMPTY)"))
    else
      store.findNode(nodeId).flatMap {
        case None =>
          IO.pure(Left(s"Node '$nodeId' not found in project '$projectName' (active or archived) — " +
            s"check NodeList for the current topology (NODE_NOT_FOUND)"))
        case Some(n) if NodeLifecycle.Terminal.contains(n.status) =>
          IO.pure(Left(s"Node '${n.name}' is terminal (status=${n.status}) — messages are refused: " +
            "a finished node is never retro-edited; create a new node instead (NODE_TERMINAL_NO_MESSAGE)"))
        case Some(n) if n.status == NodeLifecycle.Running =>
          injectRunning(n, text)
        case Some(n) =>
          appendToTaskRoute(n, text, retried = false)
      }

  /** 裁定② running 路由：活会话 → ImmediateInput（source="system"，text 带
    * [NODE-MESSAGE] 前缀头）；查无会话（nodeSessions/registry 无映射 = 会话
    * 在检查与入队间已终结）→ 裁定②竞态兜底：任务记录追加 + 「注入未达」标注。
    * 注入为 fire-and-forget tell（与 Mail/revalidate 先例同语义）——投递本身
    * 不可回执，事件日志恒记录注入尝试（留痕不丢）。 */
  private def injectRunning(node: NodeDef, text: String): IO[Either[String, String]] =
    nodeSessions.get.map(_.get(node.id)).flatMap {
      case Some(sid) =>
        resources.agentRegistry.get.flatMap { reg =>
          reg.get(sid) match
            case Some(record) =>
              val head = NodeEngine.nodeMessageHeader(node.name)
              (record.ref ! AgentCommand.ImmediateInput(
                text = s"$head\n\n$text",
                source = Some("system")
              )).void *>
                FlowMapEventLog.append(workspace, projectName, node.id, NodeEngine.NodeMessageEventType,
                  s"injected -> live session $sid: $text") *>
                  logger.info(s"NodeMessage -> node '${node.name}' (${node.id}) injected at turn boundary (session $sid, ${text.length} chars)").as(
                    Right(s"Message delivered to running node '${node.name}' — it will be injected at the next turn boundary ([NODE-MESSAGE] header). " +
                      "Trace: flow-map-events.jsonl (node-message/injected)."))
            case None =>
              // registry 已清 = 会话正在拆除（runWithAgent 清理段）→ 未达兜底
              appendUndelivered(node, text, sid)
        }
      case None => appendUndelivered(node, text, "-")
    }

  /** 裁定②竞态兜底：注入不可达 → 记录追加「注入未达」（纯留痕写——只对节点
    * 存在性守卫，不挑剔状态；会话已终结时状态可能已翻终态，留痕必须不丢）。 */
  private def appendUndelivered(node: NodeDef, text: String, sid: String): IO[Either[String, String]] =
    store.mutate { st =>
      st.nodes.get(node.id) match
        case Some(fresh) =>
          st.copy(nodes = st.nodes.updated(node.id, fresh.copy(task = Some(
            fresh.task.getOrElse("") + s"\n\n${NodeEngine.nodeMessageSection(undelivered = true)}\n$text"))))
        case None => st
    }.flatMap { s =>
      s.nodes.get(node.id).traverse_(emitUpdated) *>
        FlowMapEventLog.append(workspace, projectName, node.id, NodeEngine.NodeMessageEventType,
          s"not-delivered (session $sid already gone) -> recorded on task: $text") *>
        logger.warn(s"NodeMessage -> node '${node.name}' (${node.id}) session '$sid' gone before delivery — recorded on task as undelivered").as(
          Right(s"Node '${node.name}' session ended before the message could be injected — the message was recorded on the node's task " +
            "as「注入未达」(trace preserved, nothing lost). If the node reruns (NodeEdit reactivation) it will read it; " +
            "otherwise create a new node to carry the instruction."))
    }

  /** 裁定③ wiring/pending 路由：持久追加 task（节点启动时随 buildInput
    * 读到）。单事务 fresh 状态守卫：仍 ∈ {wiring, pending} → 追加写成；
    * 并发启动（running）→ 单次重试走 running 路由；并发终态化 → 裁定④拒绝。 */
  private def appendToTaskRoute(node: NodeDef, text: String, retried: Boolean): IO[Either[String, String]] =
    store.mutate { st =>
      st.nodes.get(node.id) match
        case Some(fresh) if fresh.status == NodeLifecycle.Wiring || fresh.status == NodeLifecycle.Pending =>
          st.copy(nodes = st.nodes.updated(node.id, fresh.copy(task = Some(
            fresh.task.getOrElse("") + s"\n\n${NodeEngine.nodeMessageSection(undelivered = false)}\n$text"))))
        case _ => st // 状态已变 → 本事务不写（下方复核分流）
    }.flatMap { s =>
      s.nodes.get(node.id) match
        case Some(fresh) if fresh.status == NodeLifecycle.Wiring || fresh.status == NodeLifecycle.Pending =>
          // mutate 原子性：返回快照即本事务后状态——状态仍 ∈ 追加集 ⟺ 本事务写成
          emitUpdated(fresh) *>
            FlowMapEventLog.append(workspace, projectName, node.id, NodeEngine.NodeMessageEventType,
              s"appended to task (status=${fresh.status}): $text").as(
              Right(s"Message appended to node '${fresh.name}' task (status=${fresh.status}) as「分发器补充（NodeMessage）」— " +
                "the node will read it as part of its task when it starts. Trace: flow-map-events.jsonl (node-message/appended)."))
        case Some(fresh) if fresh.status == NodeLifecycle.Running && !retried =>
          // 并发启动窗口：pending 在检查与追加间被投递启动 → 消息应走注入面
          logger.info(s"NodeMessage -> node '${node.name}' started concurrently during append — rerouting to live injection")
          injectRunning(fresh, text)
        case Some(fresh) if fresh.status == NodeLifecycle.Running =>
          appendUndelivered(fresh, text, "rerun-race")
        case Some(fresh) =>
          IO.pure(Left(s"Node '${fresh.name}' became terminal (status=${fresh.status}) while the message was being appended — " +
            "refused: create a new node instead (NODE_TERMINAL_NO_MESSAGE)"))
        case None =>
          IO.pure(Left(s"Node '${node.id}' vanished before the message was appended (NODE_NOT_FOUND)"))
    }

  /** WS nodeCreated（NodeEdit 创建后）。payload 与 NodeList 同构。 */
  def emitCreated(node: NodeDef): IO[Unit] =
    emitEvent("nodeCreated", node.id, NodePayload.buildNodeJson(node, System.currentTimeMillis()))

  /** WS nodeUpdated（NodeEdit 编辑/wiring 变更后）。payload 与 NodeList 同构，
    * 调用方须传 store 最终态（wiring 变更走 NodeTools.emitWiringUpdates）。 */
  def emitUpdated(node: NodeDef): IO[Unit] =
    emitEvent("nodeUpdated", node.id, NodePayload.buildNodeJson(node, System.currentTimeMillis()))

  /** 显式投递（改接投递 §2.3：已完成节点结果 → 指定目标）。V8: Nebula 分支
    * 同样记账——人工改接重投后刷新账本，避免周期扫描对同一结果再补投。
    * 缺口3（2026-09-04）：人工改接重投通道同样过夹具信封排除——名字 ∧ 载荷
    * 双确认 → WARN + 不投（结果滞留节点不删，真实工作不受影响）。 */
  def deliverOutTo(node: NodeDef, target: String, resultText: String): IO[Unit] =
    target match
      case "Nebula" =>
        if NodeEngine.isFixtureEnvelope(node) then
          logger.warn(
            s"[fixture-guard] excluded fixture envelope from manual redelivery: node '${node.name}' (${node.id}) status=${node.status} — name matches fixture family and task carries fixture marker")
        else deliverToNebula(s"[Node '${node.name}' completed]\n$resultText", node.name, "completed", Some(node.id))
      case t =>
        for
          targetOpt <- store.findNode(t)
          _ <- targetOpt match
            case None => IO.unit
            case Some(_) =>
              store.mutate { s =>
                val tn = s.nodes.get(t)
                tn match
                  case Some(x) if !x.deliveredTo.contains(node.id) =>
                    s.copy(nodes = s.nodes.updated(t, x.copy(deliveredTo = x.deliveredTo :+ node.id)))
                  case _ => s
              }.flatMap { s =>
                val allArrived = s.nodes.get(t).exists(tn2 => tn2.in.forall(upId => tn2.deliveredTo.contains(upId)))
                if allArrived then forkStart(s"deliver-out-to -> $t")(startNode(t)) else IO.unit
              }
        yield ()

  /** deps 满足判定（deps 设计 §1.3）：声明式状态查询（幂等、零记账），非 deliveredTo
    * 式事件累计。findNode 归档兜底——TTL 归档不影响「完成」事实（归档上游可触发，
    * 与 D1「归档 completed 上游是唯一投递入口」先例一致）。deps 为空 → 恒满足。 */
  def depsSatisfied(node: NodeDef): IO[Boolean] =
    node.deps.traverse(store.findNode)
      .map(_.forall(_.exists(_.status == NodeLifecycle.Completed)))

  /** 启动节点（§2.1 创建即运行：入口节点由 NodeEdit 调；下游由投递 barrier 归零调）。
    * resume（crash-recovery 批 2026-09-07 D3）：boot sweep 认领的崩溃残留节点续跑——
    * Some 时跳过 buildInput 直接用 resume prompt（水合消息经 spawn 链 initialMessages
    * 注入），CAS 翻转/deps 闸门/barrier 复核/三表登记全套照走——恢复不绕过任何启动
    * 纪律（resume 节点崩溃前已过同一闸门，deliveredTo/deps 随 flow-map 持久，重走恒真）。 */
  def startNode(nodeId: String, resume: Option[NodeEngine.ResumeContext] = None): IO[Unit] =
    store.getNode(nodeId).flatMap {
      case None => IO.unit
      case Some(node) =>
        node.status match
          // blocked 同样幂等跳过（§1.1：重跑同样 blocked；唯一出口 = NodeEdit 重激活）
          case NodeLifecycle.Running | NodeLifecycle.Completed | NodeLifecycle.Failed | NodeLifecycle.Cancelled | NodeLifecycle.Blocked =>
            IO.unit // 已终态/运行中，幂等跳过
          case _ =>
            // deps 闸门（deps 设计 §1.3，单权威）：全部 deps 上游 completed 才放行。
            // 单闸门自动保护全部启动入口（deliverOut/deliverOutTo/D1 补投递/重激活链/
            // 入口启动/settleDeps）——任何绕过 deps 的启动都被拦在最后一关。
            // failed/cancelled/blocked ∉ completed → 不触发，下游保持 pending/wiring 可见。
            depsSatisfied(node).flatMap {
              case false => IO.unit
              case true =>
                // in barrier 归零检查（deps 设计 §1.3「in 未归零都会静默返回」）：新调用方
                // settleDeps 直调 startNode 不再经 deliverOut 的 barrier 归零前置——闸门内
                // 补上这层，混合同持 in+deps 的下游只在两种等待都满足时才启动。既有全部
                // 调用方（deliverOut/deliverOutTo/deliverFailed/D1 链/入口启动）本就在
                // barrier 归零后才调（入口 in=Nil 平凡归零），检查对它们恒真、零行为变化。
                // 本检查沿快照；并发接线窗口的事务性复核在 spawnAndRun 的翻转 mutate 内
                //（fix a 启动判定完整性，20260903）。
                if node.in.exists(up => !node.deliveredTo.contains(up)) then IO.unit
                else
                  // 防御：wiring 空节点（无 task 无 in 无 deps）不空跑（防浪费 token）。
                  // 裁定①后降级为纵深防御：零连接主责在 NodeEdit 校验五/六，此处只兜
                  // 历史遗留数据（校验生效前落盘的零连接旧节点）与校验层外瞬态。
                  if node.status == NodeLifecycle.Wiring && node.task.isEmpty && node.in.isEmpty && node.deps.isEmpty then IO.unit
                  else {
                    // resume：Some → 首轮输入 = resume prompt（任务全文经 NodeList(detail)
                    // 指引自读，BackoffSupervisor continue-prompt 直系演化）；None → buildInput。
                    val inputIO = resume.fold(buildInput(node))(ctx => IO.pure(ctx.resumePrompt))
                    inputIO.flatMap { input =>
                      // 翻转竞发败方终结（trigger-chain-fix CAS 守卫）：spawnAndRun 内
                      // 翻转 LostRace 败方抛 StartRaceLost——在此单点吞掉，对全部调用
                      // 方（forkStart/runDetached/直接调用）呈安静败方语义；其他异常
                      // 原样上抛（forkStart/runDetached 留痕）。
                      // LoopNode 路由（LoopNode 批 2026-09-06）：node.loop 启用 → 走
                      // spawnAndRunLoop（worker/verify 双会话迭代）；否则标准路径。
                      val runIO =
                        if node.loop.exists(_.enabled) then spawnAndRunLoop(node, input, resume)
                        else spawnAndRun(node, input, resume)
                      runIO.handleErrorWith {
                        case _: NodeEngine.StartRaceLost => IO.unit
                      }
                    }
                  }
            }
        }

  /** 触发链 fork 化（根因报告 §6.1，trigger-chain-fix 批）：startNode 同步等到被
    * 启动节点整个会话终态（runWithAgent 内 IO.race(resultDeferred.get, …)），触发
    * 方在自身 fiber 上被下游会话质押——settleDeps 的 traverse_ 遍历因此把「同上游
    * N 依赖者」压成深度优先串行链（案例 A：队尾依赖者 pending 数小时，外观 pending
    * 永动），deliverOut/deliverFailed/deliverOutTo 则把上游完成 fiber 质押给下游
    * 全链（链深嵌套 SOE 风险面同源）。fork 后上游完成即同时唤醒全部依赖者；启动
    * 判定、幂等闸门、CAS 翻转守卫全在 startNode/spawnAndRun 内原样把关（同节点
    * 竞发由翻转守卫裁决，败方安静退出）。错误观测沿用 NodeTools.runDetached 形态
    * （handleErrorWith 留痕，含触发点上下文）。 */
  private def forkStart(what: String)(io: IO[Unit]): IO[Unit] =
    io
      .handleErrorWith(e =>
        logger.error(s"[$projectName] detached trigger '$what' failed: ${Option(e.getMessage).getOrElse(e.toString)}")
      )
      .start
      .void

  /** 构造节点输入：自身 task 上下文 + 各上游 result（=== Node <name> === 头，§2.7）+
    * blocked 声明协议脚注（设计 §1.5 原文，单点注入覆盖所有节点——节点 agent 是通用
    * 全局 agent，system prompt 不含约定，必须随输入注入）。
    * project-memory 批（2026-09-05 §3）：首部注入【本项目】记忆块（分发器所建
    * 节点自动携带项目状态/口径上下文）——渲染三态单点 ProjectMemory.injectionBlock
    * （预算内全文 / 软警全文+WARN / 超限头部+统计；缺失/空 → 不注）。全局记忆
    * 注入面（ContextRefresher）不含项目记忆——瘦身边界不变。 */
  private[project] def buildInput(node: NodeDef): IO[String] =
    val ownTask = node.task.getOrElse("")
    node.in.traverse { upId =>
      store.findNode(upId).map {
        case Some(up) =>
          up.result.map(res => s"=== Node ${up.name} ===\n$res")
        case None => None
      }
    }.flatMap { upstream =>
      ProjectMemory.injectionBlock(workspace, projectName).map { memBlock =>
        val base = (List(ownTask).filter(_.nonEmpty) ++ upstream).mkString("\n\n") + "\n\n" + NodeEngine.ProtocolFootnote
        if memBlock.isEmpty then base else memBlock + "\n\n" + base
      }
    }

  private def spawnAndRun(node: NodeDef, inputText: String, resume: Option[NodeEngine.ResumeContext] = None): IO[Unit] =
    val nodeId = node.id
    for
      agentOpt <- EntityLoader.loadAgent(node.agent)
      _ <- agentOpt match
        case None =>
          failNode(nodeId, s"agent '${node.agent}' not found in global library")
        case Some(entry) =>
          // 阶段 2b Plugins（§B.4 第 4 步，spawn 前执行）：① 解析 node.plugins
          // （untrusted/不存在/装载非法 → failNode，错误消息列明原因——分配失败是
          // 节点级失败，不静默降级）；② skill 全文读出 + ${SKILL_DIR} 替换
          // （SkillService.loadSkill 单点复用）组装 <injected-plugins> 块；
          // ③ MCP server 启动 + 引用记账（PluginMcpManager，启动失败 → failNode）。
          // 三步全部发生在状态翻转（status=Running）之前——失败路径零 running 残留。
          // §E.3 preset 消费（协议符合度批接通，2b 遗留）：同样翻转前 failNode。
          // resume（crash-recovery 批 D2/D3）：插件/preset/装配链逐行复用，仅两处
          // 差异——sessionId 复用 sessionRef 旧 id（transcript 单文件续写 + F2 队列
          // 重放白捡，BackoffSupervisor respawn 同款先例）+ initialMessages 水合。
          nodePresetDef(node, entry).flatMap {
            case Left(err) => failNode(nodeId, err)
            case Right(baseDef) =>
              prepareNodePlugins(node).flatMap {
                case Left(err) => failNode(nodeId, err)
                case Right(prepared) =>
                  val sessionId = resume.fold(s"node-${java.util.UUID.randomUUID().toString.take(8)}")(_.sessionId)
                  val inputWithPlugins =
                    if prepared.injectedBlock.isEmpty then inputText
                    else inputText + "\n\n" + prepared.injectedBlock
                  resources.pluginMcp.acquire(sessionId, prepared.mcpPlugins).flatMap {
                    case Left(err) => failNode(nodeId, err)
                    case Right(grant) =>
                      // 回收兜底（§B.4 第 5 步）：completed/failed/cancelled/blocked 全
                      // 终态汇合点=runWithAgent 完成；异常中止路径由 guarantee 补位。
                      // release 幂等（PluginMcpManager 内 no-op 语义），双保险不重复卸载。
                      runWithAgent(node, baseDef, inputWithPlugins, sessionId, prepared, grant, resume)
                        .guarantee(resources.pluginMcp.release(sessionId))
                  }
              }
          }
    yield ()

  /** LoopNode spawn（LoopNode 批 2026-09-06）：与 spawnAndRun 同骨——加载 worker/
    * verify 两 agent、准备插件、acquire 各会话 MCP grant、翻转 Running、spawn 双会话、
    * 驱动 loop。终态（PASS/failed/cancelled/blocked/达 K）由 runLoopNode 落终态化，
    * 会话/MCP/running 清理在 guarantee 内（裁定 B 双销毁不残留）。 */
  private def spawnAndRunLoop(node: NodeDef, inputText: String, resume: Option[NodeEngine.ResumeContext] = None): IO[Unit] =
    val nodeId = node.id
    val loopCfg = node.loop.get
    // crash-recovery 批裁定③：resume=Some 时双会话复用 sessionRef（worker）/
    // sessionRefVerify（verify）旧 id + 各自 transcript 水合，runLoopNode 从崩溃时
    // loopRound/loopPhase 断点续跑（worker→verify 迭代跨 kill -9 续接）。
    val workerSessionId = resume.fold(s"node-${java.util.UUID.randomUUID().toString.take(8)}")(_.sessionId)
    // verify 会话：恢复优先复用旧 id（D2）；无旧 ref（崩溃于 flip 前/历史数据）→ 新 id。
    val verifySessionId = resume.flatMap(_.verifySessionId).getOrElse(s"node-${java.util.UUID.randomUUID().toString.take(8)}")
    // projectRoot 解析（与 runWithAgent 同源单点：PathUtil.resolveNodeProjectRoot）
    val projectRoot =
      node.worktree match
        case Some(wt) if PathUtil.normalizeWorktree(wt).isLeft =>
          val pr = PathUtil.resolveNodeProjectRoot(workspace, node.worktree)
          logger.warnSync(
            s"Node '${node.name}' (${node.id}) has corrupt worktree value '$wt' — projectRoot fell back to workspace ($pr)")
          pr
        case _ => PathUtil.resolveNodeProjectRoot(workspace, node.worktree)
    for
      workerAgentOpt <- EntityLoader.loadAgent(node.agent)
      verifyAgentOpt <- EntityLoader.loadAgent(loopCfg.verify)
      _ <- (workerAgentOpt, verifyAgentOpt) match
        case (None, _) => failNode(nodeId, s"worker agent '${node.agent}' not found in global library (loop node)")
        case (_, None) => failNode(nodeId, s"verify agent '${loopCfg.verify}' not found in global library (loop node)")
        case (Some(wEntry), Some(vEntry)) =>
          // worker 侧 preset 消费（§E.3 同款）；verify 侧无单独 preset（LoopConfig 精简，
          // 与 node.preset 归 worker 的 §2.1 口径一致）。
          nodePresetDef(node, wEntry).flatMap {
            case Left(err) => failNode(nodeId, err)
            case Right(workerBase) =>
              val verifyBase = vEntry.toAgentDef
              // worker/verify 均注入 node.plugins（§2.1 verify=通用 agent+plugins，验证域
              // 分配共享同域能力包）；各 session 独立 acquire MCP grant（引用记账分离）。
              prepareNodePlugins(node).flatMap {
                case Left(err) => failNode(nodeId, err)
                case Right(prepared) =>
                  for
                    wGrantE <- resources.pluginMcp.acquire(workerSessionId, prepared.mcpPlugins)
                    vGrantE <- resources.pluginMcp.acquire(verifySessionId, prepared.mcpPlugins)
                    _ <- (wGrantE, vGrantE) match
                      case (Left(err), _) => failNode(nodeId, err)
                      case (_, Left(err)) => failNode(nodeId, err)
                      case (Right(wGrant), Right(vGrant)) =>
                        for
                          cancelSig <- Deferred[IO, Unit]
                          _ <- flipToRunning(node, cancelSig, workerSessionId, nodeId, node.name, Some(verifySessionId))
                          worker <- spawnLoopSession(workerBase, prepared, wGrant, workerSessionId, node.name, projectRoot,
                            initialMessages = resume.fold(List.empty[Message])(_.recoveredMessages))
                          verify <- spawnLoopSession(verifyBase, prepared, vGrant, verifySessionId, s"${node.name}-verify", projectRoot,
                            initialMessages = resume.fold(List.empty[Message])(_.verifyMessages))
                          _ <- runLoopNode(node, worker, verify, inputText, cancelSig, resume)
                            .guarantee(
                              destroyLoopSessions(worker, verify) *>
                                resources.pluginMcp.release(workerSessionId) *>
                                resources.pluginMcp.release(verifySessionId) *>
                                running.update(_ - nodeId) *>
                                nodeSessions.update(_ - nodeId)
                            )
                        yield ()
                  yield ()
              }
          }
    yield ()

  /** §E.3 preset 消费接通（协议符合度批补齐，2b 遗留）：node.preset →
    * PresetResolver 单点解析（Delegate/SubTask #291 先例同款）——预设链写入
    * AgentDef.model/preset/modelOverride，modelOverride 经 ContextRefresher
    * 每 turn 热重载保活。解析失败（不存在/空链，错误含可用预设清单）= 节点级
    * 失败，状态翻转前 failNode（与插件准备同纪律：失败路径零 running 残留）。
    * node.preset 缺省 → 原 AgentDef 原样透传（旧行为零变化）。 */
  private def nodePresetDef(node: NodeDef, entry: nebflow.core.entity.AgentEntry): IO[Either[String, nebflow.agent.AgentDef]] =
    IO.blocking {
      PresetResolver.applyPreset(PresetStore(), entry.toAgentDef, node.preset).left.map { err =>
        s"Node '${node.name}' preset '${node.preset.getOrElse("")}' unresolved: $err " +
          "(§E.3 node.preset consumption — presets live in model-presets.json)"
      }
    }

  /** node.plugins → 可分配能力（§B.4 第 4 步 ①②，feature flag §G.2 开关）：
    * flag off / 无分配 → 空 preparation（旧行为零变化）；解析失败 → Left
    * （failNode，错误含审批指引）。 */
  private def prepareNodePlugins(node: NodeDef): IO[Either[String, NodeEngine.PluginPreparation]] =
    PluginsConfig.enabled.flatMap {
      case false => IO.pure(Right(NodeEngine.PluginPreparation.empty))
      case true =>
        if node.plugins.isEmpty then IO.pure(Right(NodeEngine.PluginPreparation.empty))
        else
          node.plugins.traverse(PluginRegistry.resolve).flatMap { resolved =>
            val errors = resolved.collect { case Left(e) => e }
            if errors.nonEmpty then IO.pure(Left(errors.mkString(" | ")))
            else
              val defs = resolved.collect { case Right(d) => d }
              defs.traverse(injectedPluginBlock).map { blocks =>
                val inner = blocks.collect { case Right(b) if b.nonEmpty => b }
                Right(NodeEngine.PluginPreparation(
                  injectedBlock =
                    if inner.isEmpty then ""
                    else s"<injected-plugins>\n${inner.mkString("\n\n")}\n</injected-plugins>",
                  mcpPlugins = defs.filter(_.mcpServers.nonEmpty),
                  builtinTools = defs.flatMap(_.toolsExtension).distinct
                ))
              }
          }
    }

  /** 逐 plugin 逐 skill 读 SKILL.md 全文（frontmatter 去除 + ${SKILL_DIR} 替换，
    * SkillService.loadSkill 单点复用——修复「模型自读拿不到替换」缺口，§B.4 ②）。
    * 文件不可读 → Left（节点级失败，不静默降级）。 */
  private def injectedPluginBlock(defn: PluginRegistry.PluginDef): IO[Either[String, String]] =
    if defn.skills.isEmpty then IO.pure(Right(""))
    else
      defn.skills.traverse { sk =>
        SkillService.loadSkill(sk.path).flatMap {
          case Some(content) =>
            IO.pure[Either[String, String]](Right(
              s"""<plugin name="${defn.name}" skill="${sk.name}">
                 |${content.content}
                 |</plugin>""".stripMargin))
          case None =>
            IO.pure[Either[String, String]](Left(
              s"Plugin '${defn.name}' skill '${sk.id}' file unreadable: ${sk.path} — allocation refused (no silent degradation)"))
        }
      }.map { blocks =>
        blocks.find(_.isLeft) match
          case Some(Left(err)) => Left(err)
          case _ => Right(blocks.collect { case Right(b) => b }.mkString("\n\n"))
      }

  /** 信任门运行时重验（§B.5 信任联动，ProjectActor.TtlTick 30s 驱动）：停用
    * digest 失效的运行中 plugin MCP + 对持有会话发系统提醒。flag off → no-op。 */
  def revalidatePluginTrust(): IO[Unit] =
    PluginsConfig.enabled.flatMap {
      case false => IO.unit
      case true =>
        resources.pluginMcp.revalidate(
          PluginRegistry.scan(),
          (sessionId, text) =>
            resources.agentRegistry.get.flatMap { reg =>
              reg.get(sessionId).map(_.ref) match
                case Some(ref) =>
                  (ref ! AgentCommand.ImmediateInput(text, source = Some("system"))).void
                case None => IO.unit // 会话已终结——提醒无投递面，server 已停即足够
            }
        ).void
    }

  private def runWithAgent(
    node: NodeDef,
    baseDef: nebflow.agent.AgentDef, // §E.3 preset 消费：已过 PresetResolver 的 AgentDef（协议符合度批）
    inputText: String,
    sessionId: String,
    prepared: NodeEngine.PluginPreparation,
    grant: PluginMcpManager.Grant,
    /** crash-recovery 批 D3：Some = 崩溃恢复续跑（initialMessages=水合 transcript，
      * inputText=resume prompt）；None = 新鲜启动（行为与本批前逐字节一致）。 */
    resume: Option[NodeEngine.ResumeContext] = None
  ): IO[Unit] =
    val nodeId = node.id
    // sessionId 由 spawnAndRun 生成传入（阶段 2b：plugin MCP acquire 引用记账
    // 需在 spawn 前拿到同一 id——终态回收 release(sessionId) 靠它对账）。
    // projectRoot 解析（20260903 worktree 参数修复）：归一化 + worktrees/ 权威
    // 位置优先、顶层存量 fallback 的双查单点在 PathUtil.resolveNodeProjectRoot
    // （与 NodeEdit 校验同源，参照系唯一）。顶层命中与旧公式
    // `(os.Path(workspace) / ".nebflow" / wt).toString` 逐字节一致（回归红线）；
    // 两处均不存在 → 旧公式路径；损坏存储值 → workspace 兜底（不再 InvalidSegment
    // 炸 spawn）并 warnSync 留痕（QC P2：fail-safe 必须可排查）。
    val projectRoot =
      node.worktree match
        case Some(wt) if PathUtil.normalizeWorktree(wt).isLeft =>
          val pr = PathUtil.resolveNodeProjectRoot(workspace, node.worktree)
          logger.warnSync(
            s"Node '${node.name}' (${node.id}) has corrupt worktree value '$wt' — projectRoot fell back to workspace ($pr)")
          pr
        case _ => PathUtil.resolveNodeProjectRoot(workspace, node.worktree)
    val nodeName = node.name
    // 清理硬化（僵尸收敛批 2026-09-06，根因报告漏洞①）：runSig 桥接 guarantee 与
    // for 内才创建的 cancelSig——finalizer 在任意退出路径（正常/崩溃/异常/cancel）
    // 读到 cancelSig 执行 cleanupRunTables 三表对称移除（防泄漏「假活 canary」）。
    // cancelSig 未创建（for 前崩溃）→ None → 无登记可清，no-op。
    val runSig = Ref.unsafe[IO, Option[Deferred[IO, Unit]]](None)
    (for
      // 在飞登记先行（清场 c-① liveness 误杀防护 20260903）：running 表先于
      // status=Running 翻转——NodeList liveness / abandon / NodeCancel 收殓判定
      // 以「running 表含此节点」为活会话信号，若先翻转后登记，spawn 窗口内的
      // 节点会被误判死会话（false-dead → 可被收殓 = 误杀）。
      // 条件注册（trigger-chain-fix fork 化硬化）：触发链并行化后，同节点多触发
      // 方（deliverOut/settleDeps/settleSweep）并发到达本点——盲目覆写会顶掉先到
      // 者的 cancelSig（NodeCancel 信号永久丢失）。只在空位时注册；翻转赢家在
      // 翻转成功后重装自己的 sig（FlipDone 分支），最终条目必属活会话 fiber。
      // liveness 判定只看键存在，不受值选择影响。
      cancelSig <- Deferred[IO, Unit]
      _ <- runSig.set(Some(cancelSig))
      _ <- running.modify { m =>
        if m.contains(nodeId) then (m, ())
        else (m + (nodeId -> cancelSig), ())
      }
      // NodeMessage 注入链登记（20260905 机制批）：与 running 表同点置位——
      // sendNodeMessage 经本映射定位会话 actor；清理见下方对称移除。
      _ <- nodeSessions.update(_ + (nodeId -> sessionId))
      // 状态 → running + startedAt（WS nodeUpdated，NodeList 同构 payload）。
      // 竞态修复（与终态化同族）：running 迁移落在事务内现读的 fresh 节点上——
      // getNode 快照经 EntityLoader 加载 agent 期间可能已落后，陈旧 copy 写回会
      // 覆盖该窗口内的接线变更；None = 节点已消失 → 中止 spawn（拒写复活垃圾行）。
      // barrier 事务性复核（fix a 启动判定完整性 20260903）：startNode 入口的
      // in ⊆ deliveredTo 检查沿 getNode 快照，快照与翻转之间并发接线（edit 追加
      // in）可增长 in——沿陈旧快照放行 = 以不完整 barrier 提前启动（未等齐）。
      // 复核与翻转并入同一事务：fresh barrier 未齐 → 拒翻转不 spawn（status 保持
      // 原态，等真正等齐时的投递/结算方重试 startNode）。
      // CAS 翻转守卫（trigger-chain-fix §6.1 前置硬化）：仅 Pending/Wiring 可翻
      // 转。fork 化后多触发方并发通过①②③闸门（都沿陈旧 Pending 快照），无守卫
      // 的第二个 mutate 会无视已是 Running 的事实照常覆写 → 双 spawn（第二个覆写
      // running 表与 startedAt，双会话双计费、cancel 信号错投）。守卫 +
      // mutateWithResult 事务内三态判定：Done=本 fiber 唯一翻转（继续 spawn）；
      // LostRace=败方（他者已翻转到 Running——fork 并行化的正常形态，安静回滚，
      // 不事件不抛错：上游每次完成都产生同节点竞发，事件化=刷屏）；Aborted=真异
      // 常（消失/barrier 未齐/终态竞合）→ start-aborted 事件落账（§6.3 异常信号
      // 源，不再只靠异常上抛）+ 错误上抛由调用方留痕。
      now <- IO(System.currentTimeMillis())
      (flipped, flipOutcome) <- store.mutateWithResult { s =>
        s.nodes.get(nodeId) match
          case Some(fresh)
              if (fresh.status == NodeLifecycle.Pending || fresh.status == NodeLifecycle.Wiring)
                && !fresh.in.exists(up => !fresh.deliveredTo.contains(up)) =>
            // sessionRef 与 startedAt 同事务落库（crash-recovery 批 D1）：崩溃后 boot
            // sweep 据此定位磁盘 transcript；终态不清除（审计价值）。
            (s.copy(nodes = s.nodes.updated(nodeId, fresh.copy(
              status = NodeLifecycle.Running,
              startedAt = Some(now),
              sessionRef = Some(sessionId)))), NodeEngine.FlipOutcome.Done)
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            (s, NodeEngine.FlipOutcome.LostRace)
          case Some(fresh) if fresh.in.exists(up => !fresh.deliveredTo.contains(up)) =>
            (s, NodeEngine.FlipOutcome.Aborted("in-barrier not settled (concurrent rewiring)"))
          case Some(fresh) =>
            (s, NodeEngine.FlipOutcome.Aborted(s"state changed to '${fresh.status}' before flip (concurrent finalize)"))
          case None =>
            (s, NodeEngine.FlipOutcome.Aborted("node vanished"))
      }
      _ <- flipOutcome match
        case NodeEngine.FlipOutcome.Done =>
          // 赢家重装自己的 cancelSig：条件注册期间条目可能仍是竞发者的占位——
          // 最终条目必须属于本活会话 fiber（NodeCancel 信号才能到达本会话）。
          running.update(m => m + (nodeId -> cancelSig)) *>
            flipped.nodes.get(nodeId).traverse_(runningDef =>
              emitEvent("nodeUpdated", nodeId, NodePayload.buildNodeJson(runningDef, System.currentTimeMillis())))
        case NodeEngine.FlipOutcome.LostRace =>
          // CAS 败方：按 sig 身份只回滚自己的登记（不得误删赢家的条目），抛
          // StartRaceLost 终止本 fiber 的 for 推导（否则败方继续 spawn = 双会话，
          // M1 demo 实证 6 路并发 6 会话）。异常由 startNode 的 handleErrorWith
          // 精准吞掉——对全部调用方呈安静败方语义。nodeSessions 对称移除
          //（NodeMessage 注入链，防泄漏僵尸映射）。
          running.modify {
            case m if m.get(nodeId).exists(_.eq(cancelSig)) => (m - nodeId, ())
            case m => (m, ())
          } *> nodeSessions.update(_ - nodeId) *>
            IO.raiseError(NodeEngine.StartRaceLost(nodeId))
        case NodeEngine.FlipOutcome.Aborted(reason) =>
          // 真异常中止：按身份回滚 + start-aborted 事件（原语义「回滚在飞登记并
          // 中止」保留，异常信号从「仅错误上抛」扩展为「事件 + 上抛」双通道）。
          // nodeSessions 对称移除（NodeMessage 注入链，防泄漏僵尸映射）。
          running.modify {
            case m if m.get(nodeId).exists(_.eq(cancelSig)) => (m - nodeId, ())
            case m => (m, ())
          } *> nodeSessions.update(_ - nodeId) *>
            FlowMapEventLog.append(workspace, projectName, nodeId, "start-aborted",
              s"start aborted: $reason") *>
            IO.raiseError(new RuntimeException(
              s"Node '$nodeName' ($nodeId) start aborted — $reason"))
      resultDeferred <- Deferred[IO, Either[FailOutcome, List[Message]]]
      // 阶段 2b Plugins（§B.4 第 4 步 ③）：plugin MCP 前缀来源 + 内建工具授予
      // 进该会话 allowedSet（buildAllowedToolSet 扩展消费；仅运行时 AgentDef，
      // 不落 agent.json）。bridge actor 已由投递可靠性批次迁移至 spawnAgentActor
      // 之后（升级 ctx.watch 会话死亡兜底）——旧内联位置删除，本行仅保留 2b 的
      // agentDef 扩展语义。
      agentDef = baseDef.copy(
        pluginMcpServers = grant.serverIds,
        pluginTools = prepared.builtinTools
      )
      ref <- NodeRunner.spawnAgentActor(
        system,
        NodeRunner.SpawnParams(
          agentDef = agentDef,
          resources = resources,
          sessionId = sessionId,
          sessionName = nodeName,
          depth = 1,
          parentRef = None, // 节点无 Mail 身份（§硬约束）
          // #28 可观测接线：节点事件必须经路由包装注入 rootSessionId（前端
          // sessionBgAgents 归桶键）+ nodeSessionId（popup/历史路由）——否则
          // 子 agent 事件无法在 subagent 面板归到根会话（与 Delegate/SubTask
          // 同一可观测性标准）。
          wsSend = NodeRunner.routeSubagentWsSend(wsSendFn, rootSessionId, sessionId),
          projectRoot = Some(projectRoot),
          safetyMode = "confirm-edits",
          rootSessionId = rootSessionId,
          isFlowNode = true, // leaf 剥离（与 flow 节点一致：无 Node 工具/展示类）
          // 阶段 2a 沙箱（§A.6）：dev/修复节点 root=<workspace>/.nebflow/<wt>、
          // merge 节点 root=workspace——物理隔离，最小权限。
          sandboxEnabled = true,
          // [2026-09-05 21:05 作者裁定——worktree 节点继承项目沙箱]：沙箱根=
          // 项目工作区根（不收窄到 worktree 自身）——主仓 .git/worktrees/<name>/
          // 元数据在工作区内，git commit / worktree remove 直写不再 EPERM。
          // 独立信号传入（sessionRoot 显式 sandboxRoot 优先于 projectRoot），
          // 不按路径形态硬猜 workspace 布局；cwd/projectRoot 工具语义不动。
          sandboxRoot = Some(workspace),
          // crash-recovery 批 D3：恢复续跑时水合磁盘 transcript（NodeRunner.
          // initialMessages 通道，BackoffSupervisor respawn 同款）；spawn 时
          // RecoverPersistedQueues 自动重放该会话崩溃前排队中的 F2 注入。
          initialMessages = resume.fold(List.empty[Message])(_.recoveredMessages)
        )
      )
      // Bridge actor：捕获 Completed/Failed/Cancelled → Deferred（同步 complete，
      // 防 Behaviors.stopped 竞态——同 FlowDagExecutor 教训）。
      // 缺口1（2026-09-04 会话死亡假尸修复，EphemeralAgentRunner 先例同款）：
      // bridge ctx.watch(agentRef)——会话无终态事件死亡（processing.onError 静默
      // 回 idle 后被 Stop、裸崩溃退出 loop、外部 fiber cancel）时 Terminated 在此
      // 兜底完成 deferred，engine fiber 不再永久挂在 race 上、节点不再滞留 running
      // 成假尸。Terminated-without-event 只可能是异常死亡：全部合法取消路径
      // （NodeCancel→cancelSig、AgentControl/TaskStuckWatcher giveUp→
      // AgentEvent.Cancelled）都先于死亡到达 bridge——故按 **failed** 语义终态化
      // （failNode 既有链：WARN + deliverFailed；下游 barrier/deps 按既有 failed
      // 语义零改动，不伪造结果投递）。WARN 含 sessionId+nodeId+失败原因。
      //
      // ═══ 节点完成闸（bgtask-completion-gate 批，作者 2026-09-05 18:29 裁定）═══
      // 问题：节点把编译/CI 等长命令跑后台后结束轮次，engine 按「turn 结束=完成」
      // 终态化节点并把结果传下游——后台任务实际没干完，完成通知注入死会话丢失。
      // 方案（桥单咽喉 hold）：Completed 分支先查 BgTaskRegistry.waitingFor
      // (sessionId)（该节点名下等待型后台任务，即「完成时会向本会话注入通知」的
      // 任务；persistent 服务型已被 registry 过滤）：
      //   非空 → hold：不 complete、桥保持存活、FlowMapEventLog 留痕（bg-wait，
      //   先例 held/blocked 语义族但不复用——held=人工 release 放行，bg 等待是
      //   自动续行）+ 武装兜底计时器（每等待期重臂）。后台任务完成回调经
      //   AgentCommand.ExternalEvent(source="background-task") 注入仍活着的 agent
      //   → 新轮次（AgentActor idle 唤醒轮对本桥 supervisorRef 发 Completed——
      //   粘性完成目标）→ 轮次终 → 又一次 Completed → 此处复检。
      //   清空 → drainFailures 检查终局记账（超时/停滞杀的 failed 注明，拒绝静默
      //   completed）→ complete resultDeferred（result=最后一轮文本，agent 已消化
      //   全部后台通知）。
      // 三态：无后台任务 → 立即放行（现状零变化）；等待型 → 拦截至全完；
      // persistent 服务型 → 不等待。兜底面（后台任务不能卡死节点）：
      //   ①等待期超 bgWaitCapMs → failed 注明（TaskStuckWatcher 对等待期 Idle
      //     节点不判卡死——Idle 是合法状态，故需自身兜底防通知链断裂悬挂）；
      //   ②bg 任务被超时/停滞看护杀 → registry 记账 → failed+注明。
      // bg 等待先于 hold/blocked 分流发生（本桥在 completeNode 之前）——hold 节点
      // 也是先等后台再 held，语义正确。
      bridgeRef <- system.spawn(
        Behaviors.setup[AgentEvent] { bctx =>
          bctx.watch(ref) *>
            IO {
              // hold 期状态：留痕单发标记 + 兜底计时器句柄（每等待期重臂）。
              val holdEmitted = Ref.unsafe[IO, Boolean](false)
              val waitCapFiber = Ref.unsafe[IO, Option[Fiber[IO, Throwable, Unit]]](None)
              def disarmCap: IO[Unit] =
                waitCapFiber.get.flatMap(_.traverse_(_.cancel).void)
              def armCap(waiting: List[BgTaskRegistry.ActiveTask]): IO[Unit] =
                disarmCap *>
                  {
                    // 兜底计时器：等待期内无任何后台完成复检达 bgWaitCapMs →
                    // failed 注明。措辞注意：completeNode 以 message.contains
                    // ("cancelled") 分流 cancelNode，本文案不得含该词。
                    val capBody: IO[Unit] =
                      IO.sleep(bgWaitCapMs.millis) *>
                        FlowMapEventLog.append(
                          workspace, projectName, nodeId, "bg-wait-timeout",
                          s"background wait cap (${bgWaitCapMs / 1000}s) hit with ${waiting.size} task(s) pending — finalizing failed") *>
                        waitCapFiber.set(None) *>
                        resultDeferred
                          .complete(Left(FailOutcome(
                            s"background task wait cap exceeded (${bgWaitCapMs / 1000}s): still waiting for " +
                              waiting.map(t => s"'${t.description}' (${t.jobId})").mkString(", ") +
                              " — node finalized as failed by the background-completion gate; " +
                              "the background job(s) keep running and their completion notification may arrive at a finalized session"
                          ))).attempt.void
                    capBody.start.flatMap(f => waitCapFiber.set(Some(f)))
                  }
              // 终局记账 → failed 注明文案（含 agent 消化失败通知后的最终输出，
              // 截断防串膨胀；杀因原文净化同上）。
              def bgFailureMessage(
                failures: List[BgTaskRegistry.FailedBgTask],
                msgs: List[Message]
              ): String =
                val causes = failures
                  .map { f =>
                    s"'${f.description}' (${f.jobId}): ${f.cause.replace("cancelled", "auto-stopped")}"
                  }
                  .mkString("\n  - ")
                val tail = extractLastAssistantText(msgs)
                val tailNote =
                  if tail.trim.nonEmpty then
                    s"\n\nNode agent final output (after consuming the failure notification):\n${tail.take(2000)}"
                  else ""
                s"background task(s) in the node wait set were killed by the background guard (idle/stall watchdog):\n  - $causes$tailNote"
              lazy val bridge: Behavior[AgentEvent] = new Behavior[AgentEvent]:
                def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
                  event match
                    case AgentEvent.Completed(_, messages) =>
                      BgTaskRegistry.waitingFor(sessionId).flatMap { waiting =>
                        if waiting.nonEmpty then
                          holdEmitted.get.flatMap { already =>
                            val emitOnce =
                              IO.whenA(!already)(
                                FlowMapEventLog.append(
                                  workspace, projectName, nodeId, "bg-wait",
                                  s"completion held: ${waiting.size} background task(s) pending: " +
                                    waiting.map(t => s"'${t.description}'").mkString(", ")) *>
                                  logger.info(
                                    s"Node '$nodeName' ($nodeId) turn completed with ${waiting.size} background " +
                                      "task(s) still running — holding completion until they finish") *>
                                  // bg-wait 标注写点（僵尸收敛批）：首个 hold 期置位
                                  // bgWait——NodePayload 条件字段随之带，前端可标
                                  // 「等待后台任务」（与真僵尸区分，作者首要缺口）。
                                  setNodeBgWait(nodeId, waiting)) *>
                                holdEmitted.set(true)
                            emitOnce *> armCap(waiting).as(bridge)
                          }
                        else
                          BgTaskRegistry.drainFailures(sessionId).flatMap { failures =>
                            if failures.isEmpty then
                              holdEmitted.get.flatMap { held =>
                                disarmCap *> holdEmitted.set(false) *>
                                  IO.whenA(held)(
                                    FlowMapEventLog.append(
                                      workspace, projectName, nodeId, "bg-released",
                                      "all background task(s) finished — completion released") *>
                                      setNodeBgWait(nodeId, Nil)) *>
                                  resultDeferred.complete(Right(messages)).attempt.void.as(Behaviors.stopped)
                              }
                            else
                              disarmCap *> holdEmitted.set(false) *>
                                setNodeBgWait(nodeId, Nil) *>
                                resultDeferred
                                  .complete(Left(FailOutcome(bgFailureMessage(failures, messages))))
                                  .attempt.void.as(Behaviors.stopped)
                          }
                      }
                    case AgentEvent.Failed(_, err) =>
                      setNodeBgWait(nodeId, Nil) *>
                        resultDeferred
                          .complete(Left(FailOutcome(Option(err.message).getOrElse("unknown error"))))
                          .void
                          .as(Behaviors.stopped)
                    case AgentEvent.Cancelled(_, reason) =>
                      setNodeBgWait(nodeId, Nil) *>
                        resultDeferred.complete(Left(FailOutcome(s"cancelled: $reason"))).void.as(Behaviors.stopped)

                override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
                  signal match
                    case SystemSignal.Terminated(_) =>
                      logger.warn(
                        s"Node '$nodeName' ($nodeId) session '$sessionId' terminated WITHOUT a terminal event " +
                          "(crashed or stopped unexpectedly) — finalizing node as failed")
                      setNodeBgWait(nodeId, Nil) *>
                        resultDeferred
                          .complete(Left(FailOutcome(
                            s"node session '$sessionId' terminated without a terminal event (session crashed or was stopped unexpectedly)")))
                          .void
                          .handleErrorWith(_ => IO.unit) // 与正常终态事件竞争时败方静默
                        .as(Behaviors.stopped)
              bridge
            }
        },
        s"nodebridge-${sessionId.take(8)}"
      )
      // 卡死处置接线（与 ProjectActor 分发器注册同款）：supervisorRef=bridgeRef
      // ——bridge 的 Cancelled 分支（resultDeferred.complete(Left(cancelled))）
      // 成为节点的取消通道，AgentControl cancel / 面板 cancelAgent /
      // TaskStuckWatcher giveUp 共用。cancelled 走 cancelNode（status=cancelled
      // + TTL + 停 agent + running 清理）= NodeCancel 同语义，不会像 raw Stop
      // 那样把 engine fiber 永远挂在 resultDeferred 上。
      // startedAt/lastActivityMs=now：list 的 up/idle 列 + watcher 从出生可见。
      _ <- resources.agentRegistry.update(
        _ + (
          sessionId -> AgentRecord(
            sessionId,
            ref,
            AgentKind.Flow,
            rootSessionId,
            startedAt = System.currentTimeMillis(),
            lastActivityMs = System.currentTimeMillis(),
            supervisorRef = Some(bridgeRef)
          )
        )
      )
      _ <- (ref ! AgentCommand.UserInput(text = inputText, replyTo = Some(bridgeRef))).void
      // 等待完成——不设超时（硬约束）；唯一竞争事件 = NodeCancel 信号。
      outcome <- IO.race(resultDeferred.get, cancelSig.get)
      _ <- outcome match
        case Right(_) =>
          logger.info(s"Node '$nodeName' cancelled — stopping agent")
          (ref ! AgentCommand.Stop("Node cancelled")).void
        case Left(_) => IO.unit
      eventResult = outcome match
        case Left(r) => r
        case Right(_) => Left(FailOutcome("cancelled by NodeCancel"))
      _ <- resources.agentRegistry.update(_ - sessionId)
      _ <- system.stop(ref).handleErrorWith(_ => IO.unit)
      _ <- system.stop(bridgeRef).handleErrorWith(_ => IO.unit)
      _ <- running.update(_ - nodeId)
      // NodeMessage 注入链对称移除（与 running 表同点清理——此后 sendNodeMessage
      // 查无映射即走「注入未达」兜底，不再向死会话投递）。
      _ <- nodeSessions.update(_ - nodeId)
      // 终态处理：写 result/status/ttl + 投递（§2.7）——传 nodeId，终态化内部
      // 事务内现读 fresh 节点（不沿本 fiber 启动时捕获的陈旧快照）
      _ <- eventResult match
        case Right(messages) =>
          val text = extractLastAssistantText(messages)
          completeNode(nodeId, text)
        case Left(fo) =>
          if fo.message.contains("cancelled") then cancelNode(nodeId)
          else failNode(nodeId, fo.message)
    yield ()).guarantee {
      // 任意退出路径（正常/崩溃/异常/cancel）三表对称移除——与既有清理段
      // （agentRegistry/running/nodeSessions :947-953）同点幂等（先到先清）。
      runSig.get.flatMap {
        case Some(cancelSig) => cleanupRunTables(nodeId, sessionId, cancelSig)
        case None => IO.unit // cancelSig 未创建 = 未登记任何表 → 无可清
      }
    }

  // ── LoopNode 执行（LoopNode 批 2026-09-06，主设计 §2.2 状态机 + §2.4 异常路径）──
  //
  // LoopNode 语义（作者 13 条红线，主设计 §2）：
  //  - worker/verify 双会话**贯穿节点存续期**（裁定 B 2026-09-03 00:59）：首轮 spawn、
  //    跨轮同会话注入续跑、终态（PASS 投递 /failed/cancelled/blocked）双会话一并销毁。
  //  - worker 会话生产 → verify 会话校验：PASS → completeNode（投递 worker 最终产出
  //    原文，§2.2 裁定建议）。FAIL → 打回 worker（输入恒定，同会话注入返工模板二）
  //    重跑；达 maxRounds(K) 仍未 PASS → 终态（failNode，§2.5 轮级兜底）。
  //  - 打回输入恒定：返工输入只含【LoopNode 返工 · 第 N 轮】+ 新意见 + 协议脚注，
  //    不重复注入产出全文/历史（持久会话模型收益，§2.2 模板二——「重跑≠改需求」）。
  //  - LoopGuard（turn 级，会话内既有防线）与 maxRounds（轮级）两轴独立：worker/verify
  //    会话内部 LoopGuard R-text 精确重复 → 会话 L1 终止 → 以执行层 failed 形态上浮 →
  //    整 Loop failed；轮级由 runLoopNode 的 maxRounds 兜底（本方法）。
  //  - blocked 分流：worker/verify 产出命中 BlockedReader 锚定 → Loop 级 blockedNode
  //    （§2.7：FAIL=产出质量问题内部迭代消化；BLOCKED=任务/拓扑问题传播停止+重入）。
  //
  // 关键：本方法只编排**已 spawn 的** worker/verify 双会话（spawnAndRunLoop 负责
  // 翻转+spawn+插件/MCP）；终态会话销毁在 spawnAndRunLoop 的 guarantee 内。

  /** Loop 会话（worker/verify 各一）：持久 agent 会话 + 常驻桥。跨轮同 ActorRef 复用
    * （裁定 B——上下文贯穿），每轮经 round Ref 等待本轮 Completed。桥常驻存活
    * （每轮 Completes 本轮 Deferred 后回 idle 等下一轮），终态由 spawnAndRunLoop 停毁。 */
  private final case class LoopSession(
    sessionId: String,
    agentRef: ActorRef[AgentCommand],
    bridgeRef: ActorRef[AgentEvent],
    round: Ref[IO, Deferred[IO, Either[String, List[Message]]]]
  )

  /** Loop 会话常驻桥行为（与 runWithAgent bridge 同构，但**每轮完成不停止**——只
    * complete 本轮 Deferred、保持存活等下一轮注入）。失败/取消/会话死亡 → complete
    * Left（本轮即终，spawnAndRunLoop guarantee 统一销毁）。不持会话内状态（状态全在
    * 共享 round Ref），故每轮简单递归构造新 Behavior 即可（无 lazy val 循环引用）。 */
  private def loopBridge(
    round: Ref[IO, Deferred[IO, Either[String, List[Message]]]],
    sessionId: String,
    sessionName: String
  ): Behavior[AgentEvent] =
    new Behavior[AgentEvent]:
      def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
        event match
          case AgentEvent.Completed(_, messages) =>
            round.get.flatMap(_.complete(Right(messages)).attempt.void).as(loopBridge(round, sessionId, sessionName))
          case AgentEvent.Failed(_, err) =>
            round.get.flatMap(_.complete(Left(Option(err.message).getOrElse("unknown error"))).attempt.void).as(loopBridge(round, sessionId, sessionName))
          case AgentEvent.Cancelled(_, reason) =>
            round.get.flatMap(_.complete(Left(s"cancelled: $reason")).attempt.void).as(loopBridge(round, sessionId, sessionName))
      override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
        signal match
          case SystemSignal.Terminated(_) =>
            logger.warn(s"Loop session '$sessionId' ($sessionName) terminated WITHOUT a terminal event — finalize round as failed")
            round.get.flatMap(_.complete(Left(s"loop session '${sessionId}' terminated without a terminal event")).attempt.void).as(loopBridge(round, sessionId, sessionName))

  /** spawn 单个 Loop 会话（agent + 常驻桥 + registry 注册）。MCP grant 由调用方
    * （spawnAndRunLoop）分别对 worker/verify acquire 后传入（各自独立引用记账）。
    * baseDef=已过 PresetResolver 的 AgentDef；prepared=已解析的插件分配。 */
  private def spawnLoopSession(
    baseDef: AgentDef,
    prepared: NodeEngine.PluginPreparation,
    grant: PluginMcpManager.Grant,
    sessionId: String,
    sessionName: String,
    projectRoot: String,
    initialMessages: List[Message] = Nil
  ): IO[LoopSession] =
    for
      initD <- Deferred[IO, Either[String, List[Message]]]
      round = Ref.unsafe[IO, Deferred[IO, Either[String, List[Message]]]](initD)
      agentDef = baseDef.copy(pluginMcpServers = grant.serverIds, pluginTools = prepared.builtinTools)
      ref <- NodeRunner.spawnAgentActor(
        system,
        NodeRunner.SpawnParams(
          agentDef = agentDef,
          resources = resources,
          sessionId = sessionId,
          sessionName = sessionName,
          depth = 1,
          parentRef = None,
          wsSend = NodeRunner.routeSubagentWsSend(wsSendFn, rootSessionId, sessionId),
          projectRoot = Some(projectRoot),
          safetyMode = "confirm-edits",
          rootSessionId = rootSessionId,
          isFlowNode = true,
          sandboxEnabled = true,
          sandboxRoot = Some(workspace),
          initialMessages = initialMessages
        )
      )
      bridgeRef <- system.spawn(
        Behaviors.setup[AgentEvent] { bctx =>
          bctx.watch(ref) *> IO(loopBridge(round, sessionId, sessionName))
        },
        s"loopbridge-${sessionId.take(8)}"
      )
      _ <- resources.agentRegistry.update(
        _ + (sessionId -> AgentRecord(
          sessionId, ref, AgentKind.Flow, rootSessionId,
          startedAt = System.currentTimeMillis(),
          lastActivityMs = System.currentTimeMillis(),
          supervisorRef = Some(bridgeRef)
        ))
      )
    yield LoopSession(sessionId, ref, bridgeRef, round)

  /** 翻转 + 在飞登记（LoopNode 批复用；与 runWithAgent 内联翻转同语义——CAS 守卫
    * Done/LostRace/Aborted 三态、running/nodeSessions 条件登记、start-aborted 事件。
    * 独立实现避免改动既有 runWithAgent（并发分支保护，同文件不同 hunk 收敛）。
    * crash-recovery 批 D1/裁定③：翻转事务落 sessionRef（worker 主会话）+
    * sessionRefVerify（verify 会话，仅 loop 有值）——loop 崩溃残留据此双会话续接。 */
  private def flipToRunning(
    node: NodeDef,
    cancelSig: Deferred[IO, Unit],
    sessionId: String,
    nodeId: String,
    nodeName: String,
    verifySessionId: Option[String] = None
  ): IO[Unit] =
    for
      _ <- running.modify { m => if m.contains(nodeId) then (m, ()) else (m + (nodeId -> cancelSig), ()) }
      _ <- nodeSessions.update(_ + (nodeId -> sessionId))
      now <- IO(System.currentTimeMillis())
      (flipped, flipOutcome) <- store.mutateWithResult { s =>
        s.nodes.get(nodeId) match
          case Some(fresh)
              if (fresh.status == NodeLifecycle.Pending || fresh.status == NodeLifecycle.Wiring)
                && !fresh.in.exists(up => !fresh.deliveredTo.contains(up)) =>
            (s.copy(nodes = s.nodes.updated(nodeId, fresh.copy(
              status = NodeLifecycle.Running,
              startedAt = Some(now),
              sessionRef = Some(sessionId),
              sessionRefVerify = verifySessionId))), NodeEngine.FlipOutcome.Done)
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            (s, NodeEngine.FlipOutcome.LostRace)
          case Some(fresh) if fresh.in.exists(up => !fresh.deliveredTo.contains(up)) =>
            (s, NodeEngine.FlipOutcome.Aborted("in-barrier not settled (concurrent rewiring)"))
          case Some(fresh) =>
            (s, NodeEngine.FlipOutcome.Aborted(s"state changed to '${fresh.status}' before flip (concurrent finalize)"))
          case None =>
            (s, NodeEngine.FlipOutcome.Aborted("node vanished"))
      }
      _ <- flipOutcome match
        case NodeEngine.FlipOutcome.Done =>
          running.update(m => m + (nodeId -> cancelSig)) *>
            flipped.nodes.get(nodeId).traverse_(n =>
              emitEvent("nodeUpdated", nodeId, NodePayload.buildNodeJson(n, System.currentTimeMillis())))
        case NodeEngine.FlipOutcome.LostRace =>
          running.modify { case m if m.get(nodeId).exists(_.eq(cancelSig)) => (m - nodeId, ()); case m => (m, ()) } *>
            nodeSessions.update(_ - nodeId) *> IO.raiseError(NodeEngine.StartRaceLost(nodeId))
        case NodeEngine.FlipOutcome.Aborted(reason) =>
          running.modify { case m if m.get(nodeId).exists(_.eq(cancelSig)) => (m - nodeId, ()); case m => (m, ()) } *>
            nodeSessions.update(_ - nodeId) *>
            FlowMapEventLog.append(workspace, projectName, nodeId, "start-aborted",
              s"start aborted: $reason") *>
            IO.raiseError(new RuntimeException(s"Node '$nodeName' ($nodeId) start aborted — $reason"))
    yield ()

  /** 终态双会话销毁（裁定 B）：停 agent + 停桥 + 注销 registry。MCP release 与
    * running/nodeSessions 清理在 spawnAndRunLoop 的 guarantee 内一并做（此处只管会话）。 */
  private def destroyLoopSessions(worker: LoopSession, verify: LoopSession): IO[Unit] =
    resources.agentRegistry.update(_ - worker.sessionId - verify.sessionId) *>
      system.stop(worker.agentRef).handleErrorWith(_ => IO.unit) *>
      system.stop(worker.bridgeRef).handleErrorWith(_ => IO.unit) *>
      system.stop(verify.agentRef).handleErrorWith(_ => IO.unit) *>
      system.stop(verify.bridgeRef).handleErrorWith(_ => IO.unit).void

  /** runLoopNode 主编排（§2.2 状态机）：worker/verify 双会话迭代。spawnAndRunLoop
    * 已翻转+spawn+装好两会话后调用；终态（PASS/failed/cancelled/blocked/达 K）落
    * 终态化后即返回，会话清理由调用方 guarantee 兜底。
    * crash-recovery 批裁定③：resume=Some 时从崩溃时持久断点（loopRound/loopPhase）
    * 续跑——phase=worker → 本轮 worker 注入换 resume prompt（transcript 已水合，
    * 从最后持久轮边界续作），完成后照常进 verify；phase=verify → 以 worker transcript
    * 末条 assistant 文本重建本轮 verify 输入（标注崩溃续接）注入续验。loopRound 语义
    * 保持连续（续跑轮号 = 崩溃时轮号，PASS 记同一轮，FAIL 打回 +1）。 */
  private def runLoopNode(
    node: NodeDef,
    worker: LoopSession,
    verify: LoopSession,
    workerFirstInput: String,
    cancelSig: Deferred[IO, Unit],
    resume: Option[NodeEngine.ResumeContext] = None
  ): IO[Unit] =
    val nodeId = node.id
    val loopCfg = node.loop.get
    val maxRounds = loopCfg.maxRounds

    /** 单会话单轮注入：置新 Deferred → 发 UserInput → race(本轮产出, node cancelSig)。
      * cancelSig 赢 → Left(cancelled)（整 Loop cancelled），否则返回本轮产物。 */
    def step(ses: LoopSession, input: String): IO[Either[String, List[Message]]] =
      for
        d <- Deferred[IO, Either[String, List[Message]]]
        _ <- ses.round.set(d)
        _ <- (ses.agentRef ! AgentCommand.UserInput(text = input, replyTo = Some(ses.bridgeRef))).void
        r <- IO.race(d.get, cancelSig.get).map {
          case Left(res) => res
          case Right(_)  => Left("cancelled by NodeCancel")
        }
      yield r

    /** 轮次/阶段状态落库 + WS nodeUpdated（NodePayload 同构载荷，NodeList/前端可见）。 */
    def goto(nodeId: String, phase: String, round: Int): IO[Unit] =
      store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(f) if f.loop.isDefined =>
            st.copy(nodes = st.nodes.updated(nodeId, f.copy(loopPhase = Some(phase), loopRound = round)))
          case _ => st
      }.flatMap(s => s.nodes.get(nodeId).traverse_(emitUpdated))

    /** 最近一次 FAIL verdict 摘要落库 + WS（loopLastVerdict，前端打回原因可见）。 */
    def setVerdict(nodeId: String, summary: String): IO[Unit] =
      store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(f) if f.loop.isDefined =>
            st.copy(nodes = st.nodes.updated(nodeId, f.copy(loopLastVerdict = Some(summary))))
          case _ => st
      }.flatMap(s => s.nodes.get(nodeId).traverse_(emitUpdated))

    /** 执行层失败/取消分流（§2.4）：cancelled → cancelNode（整 Loop cancelled）；
      * 其余（LLM 错误/agent 消失/LoopGuard L1 终止该 turn）→ 整 Loop failed（failNode）。 */
    def failOrCancel(nodeId: String, err: String): IO[Unit] =
      if err.contains("cancelled") then cancelNode(nodeId) else failNode(nodeId, err)

    /** verify 首轮输入构建（模板三全文：原始任务 + 上游段 + 待验证产出 + 验证清单 +
      * 验证协议脚注）；轮 N≥2 用模板三短段（持久上下文已持有），见 loopVerifyInput。 */
    def verifyRound1Input(workerText: String): IO[String] =
      node.in.traverse { upId =>
        store.findNode(upId).map {
          case Some(up) => up.result.map(res => s"=== Node ${up.name} ===\n$res")
          case None     => None
        }
      }.map { upstream =>
        NodeEngine.loopVerifyInput(1, node.task.getOrElse(""), upstream.flatten.mkString("\n"), workerText, loopCfg.verifyTask)
      }

    /** verify 产出裁决（PASS 投递 worker 产出 / FAIL 打回 +1 / BLOCKED → Loop 级
      * blocked）——loopRound 正常轮与 verify 相位崩溃续跑（resumeVerifyRound）共用。 */
    def handleVerify(roundNum: Int, wText: String, vMsgs: List[Message]): IO[Unit] =
      val vText = extractLastAssistantText(vMsgs)
      BlockedReader.parse(vText) match
        case Some(fb) => blockedNode(nodeId, fb) // verify 申告任务无法验证 → Loop 级 blocked
        case None =>
          VerdictReader.parse(vText) match
            case VerdictReader.Verdict.Pass =>
              // PASS：投递 worker 最终产出原文（§2.2 裁定建议 a）
              completeNode(nodeId, wText)
            case f: VerdictReader.Verdict.Fail =>
              // FAIL：打回 worker（同会话注入意见），轮 +1，至 K
              setVerdict(nodeId, VerdictReader.renderFailSummary(f)) *>
                loopRound(roundNum + 1, Some(f))

    /** roundNum 轮的 verify 输入构建（首轮模板三全文，N≥2 短段）。 */
    def verifyInputFor(roundNum: Int, wText: String): IO[String] =
      if roundNum <= 1 then verifyRound1Input(wText)
      else IO.pure(NodeEngine.loopVerifyInput(roundNum, "", "", wText, ""))

    def loopRound(roundNum: Int, lastFail: Option[VerdictReader.Verdict.Fail], overrideWorkerInput: Option[String] = None): IO[Unit] =
      if roundNum > maxRounds then
        // 达 K 轮仍未 PASS → 轮级兜底终态（§2.5 划界：maxRounds 是轮帽，非内容检测）
        val suffix = lastFail.map(f => s" — last verdict: ${VerdictReader.renderFailSummary(f)}").getOrElse("")
        failNode(nodeId, s"loop reached maxRounds=${maxRounds} without passing verification$suffix")
      else
        // overrideWorkerInput（crash-recovery 批）：worker 相位崩溃续跑时本轮 worker
        // 输入 = resume prompt（上下文已在水合 transcript 内）；递归轮恒 None。
        val workerInput = overrideWorkerInput.getOrElse {
          if roundNum == 1 then workerFirstInput
          else
            val f = lastFail.getOrElse(VerdictReader.Verdict.Fail(Nil, ""))
            NodeEngine.loopReworkInput(roundNum, f.issues, f.requirements)
        }
        for
          _ <- goto(nodeId, NodeEngine.LoopPhaseWorker, roundNum)
          wOut <- step(worker, workerInput)
          _ <- wOut match
            case Left(err) => failOrCancel(nodeId, s"worker round $roundNum: $err")
            case Right(wMsgs) =>
              val wText = extractLastAssistantText(wMsgs)
              BlockedReader.parse(wText) match
                case Some(fb) => blockedNode(nodeId, fb) // worker 申告无法继续 → Loop 级 blocked
                case None =>
                  for
                    vIn <- verifyInputFor(roundNum, wText)
                    _ <- goto(nodeId, NodeEngine.LoopPhaseVerify, roundNum)
                    vOut <- step(verify, vIn)
                    _ <- vOut match
                      case Left(err) => failOrCancel(nodeId, s"verify round $roundNum: $err")
                      case Right(vMsgs) => handleVerify(roundNum, wText, vMsgs)
                  yield ()
        yield ()

    /** verify 相位崩溃续跑（裁定③）：以 worker transcript 末条 assistant 文本重建本轮
      * verify 输入（附崩溃续接标注——旧输入可能未持久/已消费，重注入是操作侧消息），
      * 注入水合后的 verify 会话续验；verdict 走 handleVerify 共用裁决（loopRound 语义
      * 连续：PASS 记崩溃轮，FAIL 打回 +1）。 */
    def resumeVerifyRound(r: NodeEngine.ResumeContext): IO[Unit] =
      val roundNum = math.max(r.loopResumeRound, 1)
      val wText = extractLastAssistantText(r.recoveredMessages)
      for
        vBase <- verifyInputFor(roundNum, wText)
        _ <- goto(nodeId, NodeEngine.LoopPhaseVerify, roundNum)
        vOut <- step(verify, NodeEngine.loopVerifyResumeAnnotation(roundNum) + "\n\n" + vBase)
        _ <- vOut match
          case Left(err) => failOrCancel(nodeId, s"verify round $roundNum (resumed): $err")
          case Right(vMsgs) => handleVerify(roundNum, wText, vMsgs)
      yield ()

    resume match
      case None => loopRound(1, None)
      case Some(r) =>
        val roundNum = math.max(r.loopResumeRound, 1)
        r.loopResumePhase match
          case Some(NodeEngine.LoopPhaseVerify) => resumeVerifyRound(r)
          case _ =>
            // worker 相位（或轮前崩溃 loopRound=0/phase=None）：本轮 worker 输入 =
            // resume prompt，完成后照常进 verify 轮。
            loopRound(roundNum, None, Some(r.resumePrompt))


  // ── 终态化（§2.7）─────────────────────────────────────────
  //
  // 竞态根因修复（barrier 投递 bug 主因）：终态字段（status/result/completedAt/
  // ttlExpireAt）一律落在 mutate 事务内现读的 fresh 节点上，绝不在启动时捕获的
  // 陈旧快照上 copy 写回。运行期间 NodeTools.setOut 的接线改写（out/in 反向一致）
  // 与 deliverOut 的 deliveredTo 增量都在 fresh 上原样保留；投递沿 fresh.out。
  // 实证：n-95271231 两上游运行中被改接线 → 完成时陈旧副本回写 out=null →
  // deliverOut 沿空 out 悬空 → 下游永久 wiring；n-ab4a884f 同理回写覆盖成 Nebula。
  // 节点已不在活动区（被移除）→ 拒写拒投（陈旧写回会把它复活成垃圾行）。

  private def completeNode(nodeId: String, resultText: String): IO[Unit] =
    // blocked 分流（设计 §1.3/§2.1）：最终输出以 BLOCKED 锚定 → blockedNode；
    // 非 BLOCKED 开头 → completeNode 原路径（产物完整性闸门 + completedNode）。
    BlockedReader.parse(resultText) match
      case Some(feedback) => blockedNode(nodeId, feedback)
      case None =>
        // 产物完整性闸门（audit 20260905 机制建议）：completed 出口三合法态
        // 校验（a 已提交+b commit-ready 申报+c 零改动；脏且未申报 → Reject）。
        // Reject → blockedNode 转 blocked（复用 BLOCKED 反馈协议：不走 out 投递、
        // 结果不丢弃，重入协议处置）——堵「节点自报 completed 但产物滞留」静默丢失。
        // fail-open 见 CompletionGate.check。
        store.getNode(nodeId).flatMap {
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            CompletionGate.check(workspace, fresh.worktree, resultText, gateRunner).flatMap {
              case CompletionGate.Pass(reason) =>
                logger.debug(s"Node '${fresh.name}' completion gate pass: $reason")
                completedNode(nodeId, resultText)
              case CompletionGate.Reject(reason, diag) =>
                logger.warn(s"Node '${fresh.name}' completion gate reject: $reason")
                blockedNode(nodeId, CompletionGate.feedback(diag))
            }
          case _ => completedNode(nodeId, resultText)
        }

  /** completed 原路径：落库 completed + TTL → emitEvent nodeCompleted → deliverOut → settleDeps。 */
  private def completedNode(nodeId: String, resultText: String): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) =>
            st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(
              status = NodeLifecycle.Completed,
              result = Some(resultText),
              completedAt = Some(now),
              ttlExpireAt = Some(now + NodeEngine.TtlDisplayMs))))
          case None => st
      }
      _ <- s.nodes.get(nodeId) match
        case Some(completed) =>
          emitEvent("nodeCompleted", nodeId, NodePayload.buildNodeJson(completed, now)) *>
            logger.info(s"Node '${completed.name}' completed (result ${resultText.length} chars)") *>
            deliverOut(completed, resultText) *>
            // deps 反向结算（deps 设计 §1.3）：与 deliverOut 同一完成 fiber 顺序推进
            //（现状 deliverOut 同款语义）。blocked 分流在 completeNode 入口已与
            // completed 分叉，deps 结算只挂 completed 分支尾部——blocked ∉ completed
            // 不触发；failed（failNode）/cancelled（cancelNode）不挂 settleDeps，
            // 下游保持 pending 可见（裁定③差异语义，NodeDepsSpec T5 锁定）。
            settleDeps(completed) *>
            // dispatch-notify（2026-09-05 批）：终态落库+投递+结算完成后，回流通知
            // 分发器（仅 notifyDispatcher 显式开启的节点；内部 best-effort 不上抛）。
            dispatchNotify.notifyTerminal(completed, NotifyReason.Completion)
        case None =>
          logger.warn(s"Node '$nodeId' vanished before completion — result not persisted")
    yield ()

  /** deps 完成信号 → 触发依赖者（deps 设计 §1.3）：反向扫描活动区，对每个
    * deps 含本次完成节点、且自身非 running/终态的依赖者调 startNode——闸门在
    * startNode 内（deps 未全满足 / in 未归零都会静默返回）。满足判定是声明式
    * 状态查询（幂等、零记账）：同一上游既 in 又 deps 时，in 路径（deliverOut
    * barrier 归零）与 deps 路径（settleDeps）汇合同一个 startNode 入口，第二次
    * 调用被幂等跳过，冗余无害不重复 spawn。归档上游可触发——TTL 归档不影响
    * 「完成」事实（本函数由 completeNode 完成 fiber 调用时上游必在活动区；
    * 归档变体由 NodeEdit D1-deps 补触发路径覆盖，findNode 兜底）。 */
  private def settleDeps(completed: NodeDef): IO[Unit] =
    store.snapshot.flatMap { s =>
      s.nodes.values
        .filter(d => d.deps.contains(completed.id)
          && d.status != NodeLifecycle.Running
          && !NodeLifecycle.Terminal.contains(d.status))
        .toList
        // fork 化（§6.1）：traverse_ 遍历体的 startNode 各自 fork——同上游 N 依赖
        // 者同时获得会话（案例 A 串行链根除），遍历 fiber 不被任何一个下游会话质押。
        .traverse_(d => forkStart(s"settle-deps -> ${d.name}(${d.id})")(startNode(d.id)))
    }

  /** 依赖者触发饥饿记账（§6.3）：nodeId → 连续「资格满足却未获会话」的回扫轮数。
    * 节点启动/终态/消失即移除（下一轮重新起算）。 */
  private val starveRounds: Ref[IO, Map[String, Int]] =
    Ref.unsafe[IO, Map[String, Int]](Map.empty)

  /** mount-stalled 单发记账（mount-enforce 批）：当前停滞期已发过事件的节点 id 集。
    * 每轮 sweep 全量替换为当轮停滞集——节点恢复（被补触发/合法等待）即自动出集，
    * 再次停滞 = 新停滞期再发一次。 */
  private val stallNotified: Ref[IO, Set[String]] =
    Ref.unsafe[IO, Set[String]](Set.empty)

  /** 资格回扫（根因报告 §6.2，TtlTick 30s 驱动，ProjectActor 挂点）：对活动区
    * pending/wiring 节点做声明式启动资格重估，一次性关死「有资格但没人叫」的悬
    * 挂族（孤儿 barrier、D1 缺口、投递丢失、触发消费错位——案例 B 收口C 96min
    * 滞留的根因通道）。两步：
    *   1. 孤儿 barrier 自愈：in 中「completed+有 result+deliveredTo 未记」的上游
    *      逐个 deliverOutTo（自带 deliveredTo 去重幂等；语义 = NodeTools fix-b
    *      补投从「仅 edit 时」提升为周期性；barrier 随之归零者由其内部启动）；
    *   2. barrier/deps 均满足者 fork startNode（幂等；fork 化后不阻塞 tick）。
    * 资格口径与 startNode 闸门同源（非终态 + deps 全 completed + in 全归零 + 非
    * 零接线防御）——合格即应启动；同一节点连续 ≥StarvedRounds 轮合格却仍
    * pending/wiring（= fork 启动未生效，健康系统不应发生）→ trigger-starved 事
    * 件单发（附 nodeId+资格明细，防每 tick 刷屏）。
    * 留痕纪律：本轮实际补投/启动了哪些节点——INFO 一行 + FlowMapEventLog 每动作
    * 节点一条 settle-sweep 事件，禁止静默自愈。 */
  def settleRunnableSweep(): IO[Unit] =
    for
      // crash-recovery 批：已认领待 rehydrate 的节点排除（bootRecoveryQueue 见字段注
      // 释）——资格回扫会以无 resume 的新鲜路径 fork startNode，与慢段续跑竞速会
      // 顶掉 transcript 续接语义（CAS 败者安静但恢复降级为全新重跑）。
      recovering <- bootRecoveryQueue.get
      s0 <- store.snapshot
      candidates = s0.nodes.values.filter(n =>
        (n.status == NodeLifecycle.Pending || n.status == NodeLifecycle.Wiring) && !recovering.contains(n.id)).toList
      // 第 1 步：孤儿 barrier 自愈（deliverOutTo 自带 deliveredTo 去重，重复扫描幂等）
      healed <- candidates.traverse { n =>
        n.in.traverse { upId =>
          if n.deliveredTo.contains(upId) then IO.pure(None)
          else
            store.findNode(upId).flatMap {
              case Some(up) if up.status == NodeLifecycle.Completed && up.result.exists(_.trim.nonEmpty) =>
                deliverOutTo(up, n.id, up.result.get)
                  .as(Some(n.id -> s"orphan barrier healed: redelivered completed upstream '${up.name}' (${up.id})"))
              case _ => IO.pure(None)
            }
        }.map(_.flatten)
      }.map(_.flatten)
      _ <- healed.traverse_((id, desc) => FlowMapEventLog.append(workspace, projectName, id, "settle-sweep", desc))
      // 第 2 步：重读后资格判定（补投可能已归零部分 barrier）+ fork 启动
      s1 <- store.snapshot
      actives = s1.nodes.values.filter(n =>
        n.status == NodeLifecycle.Pending || n.status == NodeLifecycle.Wiring).toList
      qualified <- actives.filterA { n =>
        val emptyWiring = n.status == NodeLifecycle.Wiring && n.task.isEmpty && n.in.isEmpty && n.deps.isEmpty
        val barrierOk = !n.in.exists(up => !n.deliveredTo.contains(up))
        if emptyWiring || !barrierOk then IO.pure(false)
        else depsSatisfied(n)
      }
      _ <- qualified.traverse_(n => forkStart(s"settle-sweep -> ${n.name}(${n.id})")(startNode(n.id)))
      _ <- qualified.traverse_(n => FlowMapEventLog.append(workspace, projectName, n.id, "settle-sweep",
        s"qualified (deps+barrier settled, status=${n.status}) — startNode forked by settle sweep"))
      // 饥饿记账：合格 → 计数 +1；已启动/终态/消失（不在本轮合格集）→ 移除（重新
      // 起算）；计数恰达阈值 → trigger-starved 单发（继续增长不再重复发）。
      starved <- starveRounds.modify { m =>
        val next = qualified.map(n => n.id -> (m.getOrElse(n.id, 0) + 1)).toMap
        (next, next.collect { case (id, c) if c == NodeEngine.StarvedRounds => id }.toSet)
      }
      _ <- qualified.filter(n => starved.contains(n.id)).traverse_ { n =>
        FlowMapEventLog.append(workspace, projectName, n.id, "trigger-starved",
          s"qualified but no session after ${NodeEngine.StarvedRounds} consecutive sweep rounds " +
            s"(status=${n.status}, deps=[${n.deps.mkString(",")}] all completed, " +
            s"in=[${n.in.mkString(",")}] all delivered, non-terminal) — startNode attempts not taking effect; " +
            "check detached trigger logs")
      }
      // 第 3 步：挂载停滞兜底闸（mount-enforce 批 20260905，作者裁定「节点一旦挂载
      // 必须一定生效——不存在挂着但永不启动的合法形态」）：可触发点（入口=创建时 /
      // barrier=全部上游终态时）后 60s 仍 pending/wiring → mount-stalled 事件留痕
      // （含节点 id+等待原因）。补触发不另起机制——第 1/2 步既有骨架即接管（孤儿
      // barrier 自愈投递 + 资格回扫 fork 启动）；上游 failed/cancelled 卡 barrier 的
      // 形态补触发不可达，事件即其可见性载体（分发器巡检处置）。合法等待不误伤：
      // running/wiring 上游 = 可触发点未到，不判停滞（mountStallReason 单点口径）。
      // 判定与资格回扫同源快照（actives，fork 启动前）——本 tick 被补触发的节点同样
      // 先落停滞事件：事件记录的是回扫起点的停滞事实，repair 与留痕同 tick 完成，
      // 互不吞没（若用 fork 后快照，fork 翻转会赢得竞速吞掉事件）。
      nowMs <- IO(System.currentTimeMillis())
      stallChecks <- actives.traverse(n => mountStallReason(n, nowMs).map(reason => n.id -> reason))
      stalledNow = stallChecks.collect { case (id, Some(reason)) => id -> reason }
      prevStall <- stallNotified.get
      stallToEmit = stalledNow.filter { case (id, _) => !prevStall.contains(id) }
      _ <- stallNotified.set(stalledNow.map(_._1).toSet)
      _ <- stallToEmit.traverse_ { case (id, reason) =>
        FlowMapEventLog.append(workspace, projectName, id, "mount-stalled", reason) *>
          logger.warn(s"[$projectName] node $id mount-stalled: $reason")
      }
      actions = healed.map(_._2) ++ qualified.map(n => s"start ${n.name}(${n.id})")
      _ <- if actions.nonEmpty then
        logger.info(s"[$projectName] settle sweep: ${actions.mkString("; ")}")
      else IO.unit
    yield ()

  /** 挂载停滞判定（mount-enforce 批 §3）：Some(reason)=已过可触发点 60s 仍未触发。
    * 可触发点口径：入口节点（无 in 无 deps）自 createdAt 起；barrier 节点自全部上游
    * （in ∪ deps）到达终态起（四终态 completed/blocked/failed/cancelled 写点均落
    * completedAt——已核实）。barrier 感知（合法等待不判停滞）：
    *   - 任一上游 running/wiring = 被真实上游正确闸住（作者明示合法形态）；
    *   - 上游引用悬空（校验生效前遗留数据）= 保守不判。
    * 全部上游终态后仍 pending/wiring 超 60s = 停滞；reason 携带各上游终态明细 +
    * barrier 残缺清单，供事件流直接定位等待原因。 */
  private def mountStallReason(n: NodeDef, now: Long): IO[Option[String]] =
    (n.in ++ n.deps).distinct.traverse(upId => store.findNode(upId)).flatMap { ups =>
      if ups.exists(_.isEmpty) then IO.pure(None)
      else
        val us = ups.flatten
        val allTerminal = us.forall(u => NodeLifecycle.Terminal.contains(u.status))
        if !allTerminal then IO.pure(None)
        else
          // 入口节点（无上游）可触发点 = createdAt；barrier 节点 = 最晚上游终态时刻
          val t0 = if us.isEmpty then n.createdAt
                   else us.flatMap(_.completedAt).maxOption.getOrElse(n.createdAt)
          val stalledSec = (now - t0) / 1000L
          if stalledSec <= NodeEngine.MountStalledMs / 1000L then IO.pure(None)
          else
            val upDesc = if us.isEmpty then "entry node (no upstreams; triggerable since creation)"
                         else us.map(u => s"'${u.name}'(${u.id}):${u.status}").mkString(", ")
            val barrierDesc = n.in.filterNot(n.deliveredTo.contains) match
              case Nil => "in-barrier cleared"
              case missing => s"in-barrier undelivered=[${missing.mkString(",")}]"
            IO.pure(Some(
              s"mount stalled: ${stalledSec}s past triggerable point, still status=${n.status}, " +
                s"$barrierDesc, no running/wiring upstream (upstreams: $upDesc) — settle sweep " +
                "takeover attempted; if still stuck a terminal (failed/cancelled) upstream is blocking " +
                "the barrier — dispatcher intervention required"))
    }

  /** blocked 终态化（设计 §2.1 四条动作序列，与 completeNode 同构）：
    * ① 事务内现读 fresh（R2 纪律）→ status=Blocked / result=渲染串 / blockedFeedback /
    *    blockCount+1 / completedAt=now / ttlExpireAt=None（永不过期=待办语义 §1.4）；
    *    节点已消失/状态已变（NodeEdit 重激活竞态）→ 拒写。
    * ② emitEvent nodeUpdated（复用现有 WS 类型 + NodePayload 同构载荷，不加新事件类型）。
    * ③ 不结算下游（out=节点不做 barrier 占位投递——传播停止是特性；下游 pending
    *    由重入分发器 NodeList 可见并处置）。
    * ④ 调 FeedbackRouter（重入 / 升级 / 频率保护决策）。 */
  private def blockedNode(nodeId: String, feedback: BlockedFeedback): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) if fresh.status == NodeLifecycle.Running =>
            st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(
              status = NodeLifecycle.Blocked,
              result = Some(BlockedReader.render(feedback)),
              blockedFeedback = Some(feedback),
              blockCount = fresh.blockCount + 1,
              completedAt = Some(now),
              ttlExpireAt = None)))
          case _ => st // 节点已消失 / 状态已变 → 拒写（R2 竞态纪律）
      }
      _ <- s.nodes.get(nodeId) match
        case Some(bn) if bn.status == NodeLifecycle.Blocked =>
          val summary = s"round ${bn.blockCount}: [${feedback.category}] ${feedback.detail.take(160)}"
          emitEvent("nodeUpdated", nodeId, NodePayload.buildNodeJson(bn, now)) *>
            logger.warn(s"Node '${bn.name}' blocked — $summary") *>
            FlowMapEventLog.append(workspace, projectName, nodeId, "blocked", summary) *>
            feedbackRouter.route(bn, feedback)
        case Some(other) =>
          logger.info(s"Node '$nodeId' state changed to '${other.status}' before blocked finalize — refused (fresh-read discipline)")
        case None =>
          logger.warn(s"Node '$nodeId' vanished before blocked finalize — feedback not persisted")
    yield ()

  private def failNode(nodeId: String, err: String): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) =>
            st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(
              status = NodeLifecycle.Failed,
              result = Some(err),
              completedAt = Some(now),
              ttlExpireAt = Some(now + NodeEngine.TtlDisplayMs))))
          case None => st
      }
      _ <- s.nodes.get(nodeId) match
        case Some(failed) =>
          emitEvent("nodeUpdated", nodeId, NodePayload.buildNodeJson(failed, now)) *>
            logger.warn(s"Node '${failed.name}' failed: ${err.take(200)}") *>
            // 失败投递（§2.7）：out=Nebula → failed 消息；out=节点 → collect 结算（占位符）。
            deliverFailed(failed, err)
        case None =>
          logger.warn(s"Node '$nodeId' vanished before failure finalize — error not persisted")
    yield ()

  private def cancelNode(nodeId: String): IO[Unit] =
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(nodeId) match
          case Some(fresh) =>
            st.copy(nodes = st.nodes.updated(nodeId, fresh.copy(
              status = NodeLifecycle.Cancelled,
              completedAt = Some(now),
              ttlExpireAt = Some(now + NodeEngine.TtlDisplayMs))))
          case None => st
      }
      _ <- s.nodes.get(nodeId) match
        case Some(cancelled) =>
          emitEvent("nodeUpdated", nodeId, NodePayload.buildNodeJson(cancelled, now)) *>
            logger.info(s"Node '${cancelled.name}' cancelled")
        case None =>
          logger.warn(s"Node '$nodeId' vanished before cancel finalize — skipped")
    yield ()

  // ── 投递（§2.7）─────────────────────────────────────────

  /** out=节点：barrier 归零 → 启动下游。dedup 用 deliveredTo（防改接重投）。 */
  private def deliverOut(node: NodeDef, resultText: String): IO[Unit] =
    node.out match
      case None => IO.unit // 悬空：结果保留在 result（持久化），接线后自动投递
      case Some("Nebula") =>
        deliverToNebula(s"[Node '${node.name}' completed]\n$resultText", node.name, "completed", Some(node.id))
      case Some(targetId) =>
        for
          targetOpt <- store.findNode(targetId)
          _ <- targetOpt match
            case None =>
              logger.warn(s"Node '${node.name}' out target '$targetId' not found — result retained")
            case Some(target) =>
              // 原子：target.deliveredTo += node.id（dedup）+ 更新
              store.mutate { s =>
                val t = s.nodes.get(targetId)
                t match
                  case Some(tn) if !tn.deliveredTo.contains(node.id) =>
                    s.copy(nodes = s.nodes.updated(targetId, tn.copy(deliveredTo = tn.deliveredTo :+ node.id)))
                  case _ => s
              }.flatMap { s =>
                val tn = s.nodes.get(targetId)
                val allArrived = tn.exists(tn2 => tn2.in.forall(upId => tn2.deliveredTo.contains(upId)))
                if allArrived && tn.exists(_.status != NodeLifecycle.Running) then
                  forkStart(s"deliver-out -> $targetId")(startNode(targetId))
                else IO.unit
              }
        yield ()

  private def deliverFailed(node: NodeDef, err: String): IO[Unit] =
    node.out match
      case None => IO.unit
      case Some("Nebula") =>
        deliverToNebula(s"[Node '${node.name}' failed]\n$err", node.name, "failed", Some(node.id))
      case Some(targetId) =>
        // collect 结算：占位符标记 + 计数归零继续（§2.4 默认 collect）。
        // 合并节点例外（merge-node 批 20260905 §触发语义，MergeNodePolicy 单点）：
        // 占位结算会让合并在不完整输入上启动 → 改转 blocked 可见终态不悬挂。
        for
          targetOpt <- store.findNode(targetId)
          _ <- targetOpt match
            case Some(target) if MergeNodePolicy.haltsOnFailure(target) =>
              mergeBlockedByUpstreamFailure(target, node, err)
            case Some(target) =>
              store.mutate { s =>
                val t = s.nodes.get(targetId)
                t match
                  case Some(tn) if !tn.deliveredTo.contains(node.id) =>
                    s.copy(nodes = s.nodes.updated(targetId, tn.copy(deliveredTo = tn.deliveredTo :+ node.id)))
                  case _ => s
              }.flatMap { s =>
                val tn = s.nodes.get(targetId)
                val allArrived = tn.exists(tn2 => tn2.in.forall(upId => tn2.deliveredTo.contains(upId)))
                if allArrived && tn.exists(_.status != NodeLifecycle.Running) then
                  forkStart(s"deliver-failed -> $targetId")(startNode(targetId))
                else IO.unit
              }
            case None => IO.unit
        yield ()

  /** 合并节点因上游失败转 blocked（merge-node 批 §触发语义②；与 blockedNode 同构
    * 但**不经 FeedbackRouter**）：① 事务内现读 fresh（R2 纪律，haltsOnFailure 只认
    * wiring/pending）→ status=Blocked / result=渲染串 / blockedFeedback=合成反馈 /
    * completedAt=now / ttlExpireAt=None（待办语义）。blockCount 不增（非节点自报
    * 轮次，重入主责在失败上游侧，MergeNodePolicy.upstreamFailureFeedback）。
    * ② emitEvent nodeUpdated + FlowMapEventLog "merge-blocked" + deliverToNebula
    * 通报（无 nodeId=fire-and-forget，对齐 escalate 通道：状态通报不是结果投递）。
    * 语义收益：不触发合并（占位结算被旁路）+ 不永久 pending 悬挂（blocked 可见
    * 终态，分发器 NodeList 可巡检）+ 已完成上游的结算保留（修复上游 → 重激活合并
    * 节点 → 既有重激活重投链自动补齐 barrier）。
    * public（mount-enforce 批）：第二挂接点 = NodeTools 创建期 D1 保留投递——
    * 合并节点创建时 in 引用已 failed 的上游（创建顺序翻转后合法形态），failed
    * 错误文本不作输入投递，同语义转 blocked 不悬挂。 */
  def mergeBlockedByUpstreamFailure(target: NodeDef, failed: NodeDef, err: String): IO[Unit] =
    val feedback = MergeNodePolicy.upstreamFailureFeedback(failed, err)
    for
      now <- IO(System.currentTimeMillis())
      s <- store.mutate { st =>
        st.nodes.get(target.id) match
          case Some(fresh) if MergeNodePolicy.haltsOnFailure(fresh) =>
            st.copy(nodes = st.nodes.updated(target.id, fresh.copy(
              status = NodeLifecycle.Blocked,
              result = Some(MergeNodePolicy.renderBlocked(feedback)),
              blockedFeedback = Some(feedback),
              completedAt = Some(now),
              ttlExpireAt = None)))
          case _ => st // 状态已变（并发终态化）→ 拒写（R2 竞态纪律）
      }
      _ <- s.nodes.get(target.id) match
        case Some(bn) if bn.status == NodeLifecycle.Blocked =>
          val summary = s"merge node blocked: upstream '${failed.name}' (${failed.id}) failed — ${err.take(140)}"
          emitEvent("nodeUpdated", target.id, NodePayload.buildNodeJson(bn, now)) *>
            logger.warn(s"Node '${bn.name}' $summary") *>
            FlowMapEventLog.append(workspace, projectName, target.id, "merge-blocked", summary) *>
            deliverToNebula(
              s"[Node '${bn.name}' blocked — 上游 '${failed.name}' failed，合并未执行]\n${err.take(800)}",
              bn.name, NodeLifecycle.Blocked)
        case _ => IO.unit
    yield ()

  /** out=Nebula：ImmediateInput 投 Nebula 根会话（source="node"，复用 flow 气泡语义）。
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
    * 不记账 → 周期重投扫描在根会话可用后补投。 */
  private[project] def deliverToNebula(text: String, nodeName: String, status: String, nodeId: Option[String] = None): IO[Unit] =
    resources.agentRegistry.get.map(_.get(rootSessionId).map(_.ref)).flatMap {
      case Some(ref) =>
        // 缺口4：同 (identity, status) 60s 窗口去重——抑制重复 offer（首投已入
        // 根会话队列），仍刷新账本（账本与通知解耦：本表只压秒级抖动，V8
        // at-least-once 语义不变）。不同 status 天然独立窗口（running→completed
        // 互不挡）。
        dedupeNebulaDelivery(nodeId.getOrElse(nodeName), status).flatMap {
          case true =>
            logger.warn(
              s"[dedup] suppressed duplicate Nebula delivery (identity=${nodeId.getOrElse(nodeName)}, status=$status, window=${NodeEngine.NebulaDedupWindowMs}ms, rootSession=$rootSessionId)") *>
              nodeId.traverse_(id => markNebulaDelivered(id)).void
          case false =>
            (ref ! AgentCommand.ImmediateInput(
              text,
              source = Some("node"),
              eventType = Some(status),
              sender = Some(s"$projectName/$nodeName")
            )) *> nodeId.traverse_(id => markNebulaDelivered(id)).void
        }
      case None =>
        logger.warn(s"Root session '$rootSessionId' not found — node result parked for redelivery scan (nodeName=$nodeName)")
        IO.unit
    }

  /** 缺口4：去重判定+登记（固定窗：首投时间戳起算 60s，不滑动；过期条目顺路
    * 淘汰=时间窗淘汰）。true = 窗口内重复（应抑制 offer）。 */
  private def dedupeNebulaDelivery(identity: String, status: String): IO[Boolean] =
    IO(System.currentTimeMillis()).flatMap { now =>
      recentNebulaDeliveries.modify { m =>
        val live = m.view.filter { case (_, ts) => now - ts < NodeEngine.NebulaDedupWindowMs }.toMap
        live.get((identity, status)) match
          case Some(_) => (live, true)
          case None    => (live.updated((identity, status), now), false)
      }
    }

  /** V8: 写 nebulaDeliveredAt 记账（活动区优先，归档区兜底——TTL 归档的
    * 未投递节点同样要记账，否则扫描每次重启都重投）。 */
  private def markNebulaDelivered(nodeId: String): IO[Unit] =
    store.getNode(nodeId).flatMap {
      case Some(_) =>
        store.mutate { s =>
          s.nodes.get(nodeId) match
            case Some(n) => s.copy(nodes = s.nodes.updated(nodeId, n.copy(nebulaDeliveredAt = Some(System.currentTimeMillis()))))
            case None    => s
        }.void
      case None =>
        store.mutateArchive { a =>
          a.nodes.get(nodeId) match
            case Some(n) => a.copy(nodes = a.nodes.updated(nodeId, n.copy(nebulaDeliveredAt = Some(System.currentTimeMillis()))))
            case None    => a
        }.void
    }

  /**
   * V8 (2026-09-03): 重启重投扫描——扫活动区+归档区全部「终态（completed/failed）
   * + out=Nebula + 有 result + 未记账」的节点，补投并记账。挂载时立即跑一次
   * （ProjectRuntimeRegistry.mount），此后挂载在 TtlTick 周期（GatewayMain 启动的
   * projectTtlScanner，30s）——启动期根会话 actor 通常尚未 spawn（懒加载），周期
   * 扫描保证根会话可用后的 30s 内补投。根 ref 缺失时静默跳过（结果滞留 map，
   * 不消费不记账，下个 tick 再试）。返回本次补投的节点数（>0 时 actor 记 info）。
   */
  def redeliverUnconsumedNebulaResults(): IO[Int] =
    resources.agentRegistry.get.flatMap { registry =>
      if !registry.contains(rootSessionId) then IO.pure(0)
      else
        for
          active <- store.snapshot
          arch <- store.archiveSnapshot
          now <- IO(System.currentTimeMillis())
          unpaid = (active.nodes.values ++ arch.nodes.values)
            .filter(n =>
              (n.status == NodeLifecycle.Completed || n.status == NodeLifecycle.Failed) &&
                n.out.contains("Nebula") &&
                n.result.exists(_.trim.nonEmpty) &&
                n.nebulaDeliveredAt.isEmpty
            )
          // 缺口3（2026-09-04 夹具信封排除）：名字命中夹具家族 ∧ 任务载荷带夹具
          // 自证标记 → 排除投递 + WARN（09-03 实证 cancel-test-* 夹具家族轰炸根
          // 会话）。排除后照常记账（nebulaDeliveredAt 置位=通知通道对该信封关闭）
          // ——否则 30s 周期扫描每轮重复 WARN。结果不删不改，滞留节点可查。
          fixtures = unpaid.filter(NodeEngine.isFixtureEnvelope).toList
          _ <- fixtures.traverse_(n =>
            logger.warn(
              s"[fixture-guard] excluded fixture envelope from redelivery scan: node '${n.name}' (${n.id}) status=${n.status} — name matches fixture family and task carries fixture marker")
              *> markNebulaDelivered(n.id))
          pending = unpaid.filterNot(NodeEngine.isFixtureEnvelope).toList
          // 缺口2（2026-09-04 补投新鲜度门控）：completedAt 距今 ≤24h（与节点显示
          // TTL 同口径）= 新鲜欠账 → 逐条投（既有形态零改动）；>24h = 历史欠账 →
          // 同批合并单条汇总通知（结果照投不丢：每节点 id+状态+摘要入汇总并逐节点
          // 记账）。completedAt 缺失 = 无法判旧 → 按新鲜逐条（宁投勿丢）。首次实时
          // 投递（completeNode/deliverOut）不走此路径，零改动。
          // MUTATION-2 已恢复：新鲜度门控（缺口2）——completedAt 距今 ≤24h 逐条、>24h 合并
          (fresh, stale) = pending.partition(n => n.completedAt.forall(c => now - c <= NodeEngine.StaleRedeliveryMs))
          _ <- fresh.traverse_(n =>
            deliverToNebula(s"[Node '${n.name}' ${n.status}]\n${n.result.get}", n.name, n.status, Some(n.id)))
          _ <- if stale.nonEmpty then deliverStaleSummary(stale) else IO.unit
          _ <- if pending.nonEmpty then
            logger.info(s"Node redelivery scan: re-delivered ${pending.size} unconsumed out=Nebula result(s) to root '$rootSessionId' (fresh=${fresh.size} stale-merged=${stale.size} fixture-excluded=${fixtures.size})")
          else IO.unit
        yield pending.size
    }

  /** 缺口2：历史欠账（completedAt >24h）同批合并单条汇总通知（形态 (a) 取舍：
    * 纯后端立即生效，不依赖前端改动——形态 (b) 载荷打标记需后续前端批才可见）。
    * 一条文本含 N 条 nodeId+状态+结果摘要（160 字/条）。eventType：混含 failed →
    * "failed"（强提醒），全 completed → "completed"。**绕过缺口4 去重直投**——
    * 汇总文本每批唯一，若走 deliverToNebula 会与 60s 前一批汇总同 key 相撞被
    * 误抑制（节点已被记账=通知丢失）。offer 后逐节点记账（at-least-once：offer
    * 与记账间崩溃 → 下轮扫描重汇总，宁重复不丢失）。 */
  private def deliverStaleSummary(stale: List[NodeDef]): IO[Unit] =
    val eventType = if stale.exists(_.status == NodeLifecycle.Failed) then NodeLifecycle.Failed else NodeLifecycle.Completed
    val lines = stale.zipWithIndex
      .map((n, i) => s"${i + 1}. [${n.status}] ${n.name} (${n.id}): ${n.result.get.trim.take(NodeEngine.StaleSummaryPerNodeChars)}")
      .mkString("\n")
    val text = s"[Node 历史欠账汇总补投 ×${stale.size}（>${NodeEngine.StaleRedeliveryMs / 3600000}h 未消费，项目 $projectName）]\n$lines"
    resources.agentRegistry.get.map(_.get(rootSessionId).map(_.ref)).flatMap {
      case Some(ref) =>
        (ref ! AgentCommand.ImmediateInput(
          text,
          source = Some("node"),
          eventType = Some(eventType),
          sender = Some(s"$projectName/stale-redelivery-summary")
        )) *> stale.traverse_(n => markNebulaDelivered(n.id))
      case None => IO.unit // 根不可达 → 不记账不丢账，下轮扫描重汇总
    }

  /** 分发器会话最终输出投递（2026-09-05 作者裁定「任务分发器的结果没有任何人看见，
    * 把这个接线给 nebula」）：分发器 turn 终态时由 ProjectActor 观察桥调用——取本
    * turn 最终 assistant 文本（extractLastAssistantText），注入 Nebula 根会话。
    * 空文本（无 assistant 文本或纯空白）不投（防御性判空，debug 级留痕）。
    *
    * 格式（对照节点投递家族）：text 头部标注行 [Dispatcher '<项目名>' · task:
    * <触发任务摘要>] + 换行 + 最终输出全文，对照 "[Node '<name>' completed]"
    * 头部行家族；蓝气泡 source="dispatcher"（DispatcherSourceMarker）走前端
    * injectedSourceLabel 通用分支 → "Dispatcher · <项目名> · Completed"，
    * 与 NODE/MAIL 同族蓝气泡标注体系，零前端改动。
    *
    * 与节点投递（deliverToNebula）的关系——独立新投递种类，刻意绕过节点投递的
    * 全部账本机制（绕过去重直投有 deliverStaleSummary 先例）：
    *  - 不进 V8 nebulaDeliveredAt 账本/重投扫描：分发器输出非节点结果、无持久化
    *    载体可补投，单次会话单次投递，at-least-once 无对象；
    *  - 不进 60s 去重窗口：同项目短窗内多次触发任务是各自独立的合法投递（每次
    *    触发一个新 turn），按 (identity,status) 去重会吞掉合法的第二次；
    *  - 不走夹具信封排除：分发器输出不是节点结果，夹具家族语义不适用。
    * 忙时排队自动继承：ImmediateInput 在根会话忙 turn 时入 pendingImmediateInputs
    * （AgentActor processing 态排队、turn 边界串行 drain），不打断不丢。
    * 根会话 ref 缺失 → WARN 丢弃（无账本无重投，Flow Map 拓扑仍是事实来源）；
    * 占位/跳过类极简输出照常投递（无特殊抑制——分发器一条短行也是有效反馈）。 */
  def deliverDispatcherOutputToNebula(messages: List[Message], taskSummary: Option[String]): IO[Unit] =
    extractLastAssistantText(messages) match
      case "" =>
        logger.debug(s"Dispatcher turn produced no text output (project=$projectName) — nothing to deliver")
      case finalText =>
        resources.agentRegistry.get.map(_.get(rootSessionId).map(_.ref)).flatMap {
          case Some(ref) =>
            val header = taskSummary.map(_.trim).filter(_.nonEmpty) match
              case Some(s) => s"[Dispatcher '$projectName' · task: $s]"
              case None    => s"[Dispatcher '$projectName']"
            (ref ! AgentCommand.ImmediateInput(
              s"$header\n$finalText",
              source = Some(NodeEngine.DispatcherSourceMarker),
              eventType = Some(NodeLifecycle.Completed),
              sender = Some(projectName)
            )).void
          case None =>
            logger.warn(
              s"Root session '$rootSessionId' not found — dispatcher final output not deliverable (project=$projectName, ${finalText.length} chars dropped; no ledger/no redelivery by design)")
        }

  private def extractLastAssistantText(messages: List[Message]): String =
    messages.reverse
      .collectFirst {
        case m if m.role == nebflow.shared.MessageRole.Assistant => m.textContent
      }
      .filter(_.trim.nonEmpty)
      .getOrElse("")

  private case class FailOutcome(message: String)

object NodeEngine:
  /** 终态节点显示 TTL（24h——2026-09-02 作者裁定；测试档可缩短——ProjectActor 注入）。 */
  val TtlDisplayMs: Long = 24 * 60 * 60 * 1000L

  /** startNode 翻转事务的结局（trigger-chain-fix §6.1 CAS 守卫）：Done=本 fiber
    * 完成翻转（唯一赢家，继续 spawn）；LostRace=败方（他者已翻转到 Running——
    * fork 并行化的正常形态，安静回滚不事件）；Aborted=真异常（节点消失/barrier
    * 未齐/终态竞合）→ start-aborted 事件 + 错误上抛。判定在 mutateWithResult
    * 事务内产出（翻转后状态恒 Running，事后补查无法区分赢家与败方）。 */
  enum FlipOutcome:
    case Done, LostRace
    case Aborted(reason: String)

  /** trigger-starved 触发阈值（§6.3）：同一节点连续 ≥N 轮资格回扫均满足资格却仍
    * 非终态无会话（fork 启动未生效）才落事件——每饥饿期单发（计数恰等于阈值时），
    * 避免每 tick 刷屏。 */
  val StarvedRounds: Int = 2

  /** mount-stalled 停滞阈值（mount-enforce 批 20260905，作者裁定「节点一旦挂载必须
    * 一定生效」）：可触发点后 60s 仍未触发（仍 pending/wiring 且无 running/wiring
    * 上游）→ mount-stalled 事件留痕（含节点 id+等待原因）+ settleSweep 既有资格回扫
    * 接管补触发。双保险的时间维度信号（轮次维度由 trigger-starved 承担）。 */
  val MountStalledMs: Long = 60_000L

  /** 死会话自动收敛的 spawn 窗口宽限（僵尸收敛批 2026-09-06）：节点 status 翻
    * Running 后，agent registry 登记发生在 spawn 之后（runWithAgent :816）——Flipped
    * 瞬到 registry 登记之间存在微小窗口。自动对账只在「已过该宽限仍未登记 agent」
    * 时才判定死会话，避免把刚启动（fiber 活着、agent 即将登记）的节点误判收殓。
    * 宽限取 60s（agent spawn + plugin MCP acquire 量级远小于此）。 */
  val StaleSpawnGraceMs: Long = 60_000L

  /** startNode 翻转竞发的败方信号（trigger-chain-fix）：startNode 单点吞掉——
    * 败方安静退出，赢家持有节点生命周期（会话、cancelSig、终态分发）。 */
  final case class StartRaceLost(nodeId: String)
      extends RuntimeException(s"start race lost ($nodeId) — winner owns the session")

  // ── 投递可靠性批次常量（2026-09-04 四缺口）──────────────────

  /** 缺口2：补投/重投新鲜度阈值（24h，取 TtlDisplayMs 同口径——超过一个显示
    * 周期未被消费的欠账即「历史欠账」，合并汇总降噪；结果照投不丢）。 */
  val StaleRedeliveryMs: Long = TtlDisplayMs

  /** 缺口2：汇总通知每节点结果摘要截断长度。 */
  val StaleSummaryPerNodeChars: Int = 160

  /** 缺口3：测试夹具信封家族（宁窄勿宽——09-03 实证宿主真实数据唯一可确证
    * 夹具家族：cancel-test 取消语义验证节点，见 phd-notebook 归档 cancel-test、
    * cancel-test-3..11；精确锚定全名，真实节点名巧合含 test 不命中）。 */
  val FixtureFamilyRegex = "^(cancel-test|cancel-test-\\d+)$".r

  /** 缺口3：夹具载荷自证标记（09-03 实证夹具任务正文均带此前缀）。双条件
    * 缺一不可：名字 ∧ 载荷双确认才排除——真实节点名巧合命中家族但载荷是
    * 真实工作 → 照常投递。 */
  val FixtureTaskMarker = "取消验证节点"

  /** 缺口3：夹具信封判定（纯函数便于单测）。 */
  def isFixtureEnvelope(node: NodeDef): Boolean =
    FixtureFamilyRegex.matches(node.name) && node.task.exists(_.contains(FixtureTaskMarker))

  /** 缺口4：同 (identity, status) 重复通知去重窗口（60s——秒级抖动口径：挂载
    * 扫描与周期扫描竞态、ProjectCreate 合并回报三连投实证 09-03）。进程内
    * 内存窗，与 V8 nebulaDeliveredAt 持久账本正交（账本管跨重启 at-least-once）。 */
  val NebulaDedupWindowMs: Long = 60_000L

  /** 分发器最终输出投递的 source 标记（2026-09-05 接线）：ImmediateInput source
    * 值。前端 injectedSourceLabel 通用分支对未知 source 首字母大写 → 蓝气泡
    * "Dispatcher · <项目名> · Completed"（与 NODE/MAIL 同族，零前端改动）。 */
  val DispatcherSourceMarker = "dispatcher"

  /** 分发器投递任务摘要截断长度（触发任务首行、单行空白折叠，超出截断加省略号）。 */
  val DispatcherTaskSummaryChars: Int = 100

  /** 阶段 2b Plugins：spawn 前解析完成的分配物（§B.4 第 4 步）。
    * empty = flag 关 / 节点无分配 / 无可注入内容——旧行为零变化。 */
  final case class PluginPreparation(
    /** <injected-plugins> 全文块（逐 plugin 逐 skill，frontmatter 已去除 +
      * ${SKILL_DIR} 已替换）；"" = 无注入。 */
    injectedBlock: String,
    /** 含 MCP server 的 plugin（acquire 输入；serverId = plugin_<p>_<s>）。 */
    mcpPlugins: List[PluginRegistry.PluginDef],
    /** org.nebflow/tools 授予的 builtin 工具名（白名单已在装载层校验）。 */
    builtinTools: List[String]
  )
  object PluginPreparation:
    val empty: PluginPreparation = PluginPreparation("", Nil, Nil)

  // ── NodeMessage（20260905 机制批，作者裁定六条语义）──────────────────

  /** 裁定②：running 注入文本的可识别前缀——与用户任务文本、节点结果投递明确
    * 区分（分发器 NodeMessage + 节点名 + 时间戳来源标注）。 */
  val NodeMessagePrefix: String = "[NODE-MESSAGE]"

  /** 裁定⑤：消息留痕事件类型（FlowMapEventLog append-only JSONL）。 */
  val NodeMessageEventType: String = "node-message"

  /** running 注入文本头（前缀 + 来源标注单点）。 */
  def nodeMessageHeader(nodeName: String): String = {
    val ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    s"$NodeMessagePrefix 来源：任务分发器 NodeMessage · 节点 $nodeName · $ts"
  }

  /** 裁定③：任务追加分节头（NodeEdit release note 先例同款形态；裁定原文
    * 「== 分发器补充（NodeMessage <时间戳>） ==」）。undelivered=true 时追加
    * 「注入未达」标注（裁定②竞态兜底——留痕不丢）。 */
  def nodeMessageSection(undelivered: Boolean): String = {
    val ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    val base = s"== 分发器补充（NodeMessage $ts） =="
    if undelivered then base + "\n（注入未达：会话在消息投递前已终结，仅记录不注入——留痕不丢）" else base
  }

  /** 节点 id 前缀（sessionId = "node-<uuid>"）。 */
  val SessionPrefix = "node-"

  /** 节点级 blocked 重入上限（设计 §3.1/§7.2）：blockCount 1/2 → 重入调整；
    * count=3（> 2）→ 升级 Nebula 不再重入。 */
  val MaxBlockRoundsPerNode: Int = 2

  /** blocked 声明协议脚注（设计 §1.5 文案原样，buildInput 末尾单点注入）。 */
  val ProtocolFootnote: String =
    """── 节点协议 ──
      |若你判定任务无法完成（上游依赖未就绪/任务定义不完整/能力不匹配/缺外部条件），
      |不要硬造结果：把最终输出的第一行写为 BLOCKED，随后给出 JSON：
      |{"category":"…","detail":"…","suggestion":"…"}
      |category ∈ upstream-incomplete | task-underspecified | agent-mismatch |
      |external-dependency | needs-split | other。可完成时正常输出结果，勿以 BLOCKED 开头。""".stripMargin

  // ── LoopNode（LoopNode 批 2026-09-06，主设计 §2.2/§2.3）──────────────────

  /** loop 运行态阶段值（NodeDef.loopPhase；loopRound 配套）。 */
  val LoopPhaseWorker: String = "worker"
  val LoopPhaseVerify: String = "verify"

  // ── boot-time 崩溃恢复（crash-recovery 批 2026-09-07）──────────────────

  /** 恢复认领事件类型（FlowMapEventLog append-only JSONL，「禁止静默自愈」纪律——
    * 每个认领/处置动作一条，summary 含三分类与 transcript 指针）。 */
  val BootRecoveryEventType: String = "boot-recovery"

  /** 崩溃恢复续跑上下文（D3）：spawnAndRun/runWithAgent/spawnAndRunLoop 全链的
    * resume 增量——Some 时跳过 buildInput、复用旧会话 id（D2：transcript 单文件
    * 续写 + F2 队列重放白捡）+ initialMessages 水合，其余装配逐行复用（R8：禁手写
    * 独立 spawn 链）。普通节点仅主会话三字段；loop 节点（裁定③）另带 verify 双会话
    * 与崩溃时 loopRound/loopPhase 断点。 */
  final case class ResumeContext(
    /** 复用的旧会话 id（普通节点唯一会话；loop = worker 主会话）。 */
    sessionId: String,
    /** 主会话水合消息（磁盘 transcript，最后持久 tool round 边界——§1.1 诚实语义：
      * 工具执行中/LLM 调用中/2s debounce 窗内崩溃的轮次回退重放）。 */
    recoveredMessages: List[Message],
    /** 首轮输入 = resume prompt（BackoffSupervisor continue-prompt 直系演化）。 */
    resumePrompt: String,
    /** loop verify 会话 id（仅 loop 节点；None = 普通/无旧 ref 新起）。 */
    verifySessionId: Option[String] = None,
    /** verify 会话水合消息（loop 节点）。 */
    verifyMessages: List[Message] = Nil,
    /** loop 崩溃时持久轮次断点（≥1；0/无值按 1 起）。 */
    loopResumeRound: Int = 0,
    /** loop 崩溃时相位（worker/verify；无值按 worker）。 */
    loopResumePhase: Option[String] = None
  )

  /** 普通节点 resume prompt（§3.3 模板；磁盘上 (a)/(b) 不可区分——副作用核验告知
    * 恒带（裁定②诚实语义：mid-turn 重放=从上一持久轮重跑，已完成的工作无需重复，
    * 未确认的副作用先核验）。bgWaitNote = 认领时清空的等待集快照（G4 死亡告知）。 */
  def nodeResumePrompt(projectName: String, node: NodeDef, recoveredCount: Int, bgWaitNote: Option[String]): String =
    val bgSection = bgWaitNote match
      case Some(w) => s"\n崩溃前等待中的后台任务已死亡（$w）——等待集已随进程消失，其结果文件若在盘上请自行核验，按需重启该后台工作。\n"
      case None => ""
    s"""[system] 进程在你上一轮工作期间崩溃并已重启。你的会话历史已从磁盘恢复
       |（共恢复 ${recoveredCount} 条消息，断点 = 最后一个已持久化的工具轮边界）。请继续完成节点任务「${node.name}」。
       |注意：上一轮工具调用可能未完成即中断——请先核验关键副作用（git 状态、关键文件）再继续，已完成的工作无需重复。
       |$bgSection
       |任务全文（原始要求）：NodeList(detail="${node.id}", project="$projectName") 可读；本 prompt 只负责续跑告知。""".stripMargin

  /** loop worker 相位 resume prompt（裁定③：worker→verify 迭代跨崩溃续接——本轮
    * 产出从最后持久轮边界续作，verify 照常裁决本轮）。 */
  def loopWorkerResumePrompt(node: NodeDef, round: Int, phase: String, recoveredCount: Int): String =
    s"""[system] 进程在 LoopNode 第 $round 轮（$phase 阶段）执行期间崩溃并已重启。你的会话历史已从磁盘恢复
       |（共恢复 ${recoveredCount} 条消息，断点 = 最后一个已持久化的工具轮边界）。请继续完成本轮产出——
       |从断点继续工作，上一轮工具调用可能未完成即中断，请先核验关键副作用（git 状态、关键文件）再继续，已完成的工作无需重复；
       |完成后给出本轮最终产出（后续由验证会话裁决）。
       |原始任务全文：NodeList(detail="${node.id}") 可读；本 prompt 只负责续跑告知。""".stripMargin

  /** loop verify 相位续跑标注（verify 输入由 worker transcript 末条 assistant 文本
    * 重建——旧输入可能未持久/已消费，重注入是操作侧消息）。 */
  def loopVerifyResumeAnnotation(round: Int): String =
    s"[system] 进程在 LoopNode 第 $round 轮（verify 阶段）执行期间崩溃并已重启，verify 会话历史已恢复；" +
      s"下方为重建的本轮验证输入（worker 本轮产出），请按验证协议对本轮给出 verdict。"

  // ── LoopNode 模板（续）────────────────────────────────────────

  /** verify 文法脚注（主设计 §2.3 原文单点注入，模板三末尾）——与 ProtocolFootnote
    * 同机制，覆盖所有 verify 会话，防「verify 意图 FAIL 但忘写锚定行」被降级放行。 */
  val VerifyVerdictFootnote: String =
    """── 验证协议 ──
      |你的最终输出第一行必须是且只能是：VERDICT: PASS 或 VERDICT: FAIL（大写，冒号后半角）。
      |判 FAIL 时随后给出 JSON：{"issues":["问题1","问题2"],"requirements":"通过标准"}
      |issues 必须具体到修改点；可 PASS 时第一行写 VERDICT: PASS，不要附加其他内容。""".stripMargin

  /** verify 清单默认模板（loopSpec.verifyTask 为空时兜底注入）。 */
  val VerifyDefaultTask: String =
    "按原始任务与验收基准逐条核对 worker 产出，判断是否达到通过标准；" +
      "指出必须修改的具体问题（issues 逐条列出），达到标准则判 PASS。"

  /** worker 返工模板二（第 N≥2 轮同会话注入；产出全文/历史不重复注入——持久会话
    * 上下文已持有，主设计 §2.2 模板二）。 */
  def loopReworkInput(round: Int, issues: List[String], requirements: String): String =
    val reqLine =
      if requirements.trim.nonEmpty then s"== 通过标准 ==\n$requirements" else ""
    val iss = if issues.nonEmpty then issues.map(i => s"- $i").mkString("\n") else "- （verify 未给出具体意见）"
    s"""【LoopNode 返工 · 第 $round 轮】
       |== 验证意见 ==
       |$iss
       |$reqLine
       |
       |${ProtocolFootnote}""".stripMargin

  /** verify 输入模板三（第 N 轮；首轮全文、第 N≥2 轮新段——持久会话已持有原始任务/
    * 上游段/验证清单/历轮产出，主设计 §2.2 模板三 + 裁定 B 注记）。 */
  def loopVerifyInput(
    round: Int,
    nodeTask: String,
    upstreamSection: String,
    workerOutput: String,
    verifyTask: String
  ): String =
    if round <= 1 then
      val up = if upstreamSection.nonEmpty then s"\n$upstreamSection" else ""
      s"""【LoopNode 验证 · 第 $round 轮】
         |== 原始任务（验收基准） ==
         |$nodeTask$up
         |== 待验证产出（worker 第 $round 轮） ==
         |$workerOutput
         |== 验证清单 ==
         |${if verifyTask.trim.nonEmpty then verifyTask else VerifyDefaultTask}
         |
         |${VerifyVerdictFootnote}""".stripMargin
    else
      s"""【LoopNode 验证 · 第 $round 轮】
         |== 待验证产出（worker 第 $round 轮） ==
         |$workerOutput""".stripMargin

/** blocked 声明解析器（设计 §1.3 文法）。独立 object 便于单测。
  *
  * - 锚定：trim 后以 BLOCKED 开头（后跟 `:` 或换行/空白/串尾）——只查开头，正文含词不误判。
  * - JSON 体：首个 `{` 到末个 `}` 之间尝试 circe 解析；category 不在枚举 → other。
  * - JSON 缺失/畸形 → BlockedFeedback("other", 其余全文截断, "")。
  * - 非 BLOCKED 开头 → None（调用方走 completed 原路径无损降级）。 */
object BlockedReader:
  val Categories: Set[String] =
    Set("upstream-incomplete", "task-underspecified", "agent-mismatch", "external-dependency", "needs-split", "other")

  private val Marker = "BLOCKED"
  private val DetailCap = 500

  /** 解析节点最终输出：命中 blocked → Some(BlockedFeedback)；否则 None。 */
  def parse(resultText: String): Option[BlockedFeedback] =
    val trimmed = resultText.trim
    if !anchored(trimmed) then None
    else
      jsonBody(trimmed) match
        case Some(body) =>
          io.circe.parser.parse(body) match
            case Right(json) =>
              val cursor = json.hcursor
              val category = cursor.get[String]("category").toOption.filter(Categories.contains).getOrElse("other")
              val detail = cursor.get[String]("detail").toOption.getOrElse("")
              val suggestion = cursor.get[String]("suggestion").toOption.getOrElse("")
              Some(BlockedFeedback(category, detail, suggestion))
            case Left(_) => Some(fallback(trimmed))
        case None => Some(fallback(trimmed))

  /** 落库渲染串（设计 §1.3：result 字段人类可读形态，NodeList 摘要与详情窗共用）。 */
  def render(f: BlockedFeedback): String =
    s"[blocked:${f.category}] ${f.detail} — 建议: ${f.suggestion}"

  /** 锚定判定：BLOCKED 开头且后跟 `:` / 空白 / 串尾（"BLOCKEDxx" 不算）。 */
  private def anchored(trimmed: String): Boolean =
    trimmed.startsWith(Marker) && {
      val rest = trimmed.substring(Marker.length)
      rest.isEmpty || rest.head == ':' || rest.head.isWhitespace
    }

  /** 首个 `{` 到末个 `}` 之间（可嵌于说明文字之后）。 */
  private def jsonBody(trimmed: String): Option[String] =
    val i = trimmed.indexOf('{')
    val j = trimmed.lastIndexOf('}')
    if i >= 0 && j > i then Some(trimmed.substring(i, j + 1)) else None

  /** JSON 缺失/畸形降级：去掉 BLOCKED 标记行后的其余全文截断为 detail。 */
  private def fallback(trimmed: String): BlockedFeedback =
    val rest = trimmed.replaceFirst("^BLOCKED\\s*:?\\s*", "").trim
    val detail = if rest.length > DetailCap then rest.take(DetailCap) + "…" else rest
    BlockedFeedback("other", detail, "")
