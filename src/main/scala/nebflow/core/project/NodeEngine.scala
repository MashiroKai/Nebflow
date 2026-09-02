package nebflow.core.project

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.entity.EntityLoader
import nebflow.core.node.NodeRunner
import nebflow.shared.Message

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
  emitEvent: (String, String, Json) => IO[Unit]
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

  /** 运行中节点 → cancel 信号（NodeCancel 用）。 */
  private val running: Ref[IO, Map[String, Deferred[IO, Unit]]] = Ref.unsafe[IO, Map[String, Deferred[IO, Unit]]](Map.empty)

  /** 节点是否正在运行（ProjectActor 状态查询用）。 */
  def isRunning(nodeId: String): IO[Boolean] = running.get.map(_.contains(nodeId))

  /** 取消运行中节点（NodeCancel/ProjectActor 转发）：触发 cancel 信号 → 停 agent。 */
  def cancelNodeById(nodeId: String): IO[Unit] =
    running.get.map(_.get(nodeId)).flatMap {
      case Some(sig) => sig.complete(()).void
      case None => IO.unit
    }

  /** WS nodeRemoved（TTL 移除/归档后通知前端移除卡片）。payload = 节点最终态
    * （NodeList 同构——归档区兜底查得，ttlLeftSec=0），不再发空对象。 */
  def emitRemoved(nodeId: String): IO[Unit] =
    store.findNode(nodeId).flatMap {
      case Some(n) => emitEvent("nodeRemoved", nodeId, NodePayload.buildNodeJson(n, System.currentTimeMillis()))
      case None    => emitEvent("nodeRemoved", nodeId, Json.obj("id" -> nodeId.asJson))
    }

  /** WS nodeCreated（NodeEdit 创建后）。payload 与 NodeList 同构。 */
  def emitCreated(node: NodeDef): IO[Unit] =
    emitEvent("nodeCreated", node.id, NodePayload.buildNodeJson(node, System.currentTimeMillis()))

  /** WS nodeUpdated（NodeEdit 编辑/wiring 变更后）。payload 与 NodeList 同构，
    * 调用方须传 store 最终态（wiring 变更走 NodeTools.emitWiringUpdates）。 */
  def emitUpdated(node: NodeDef): IO[Unit] =
    emitEvent("nodeUpdated", node.id, NodePayload.buildNodeJson(node, System.currentTimeMillis()))

  /** 显式投递（改接投递 §2.3：已完成节点结果 → 指定目标）。 */
  def deliverOutTo(node: NodeDef, target: String, resultText: String): IO[Unit] =
    target match
      case "Nebula" => deliverToNebula(s"[Node '${node.name}' completed]\n$resultText", node.name, "completed")
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
                if allArrived then startNode(t) else IO.unit
              }
        yield ()

  /** 启动节点（§2.1 创建即运行：入口节点由 NodeEdit 调；下游由投递 barrier 归零调）。 */
  def startNode(nodeId: String): IO[Unit] =
    store.getNode(nodeId).flatMap {
      case None => IO.unit
      case Some(node) =>
        node.status match
          // blocked 同样幂等跳过（§1.1：重跑同样 blocked；唯一出口 = NodeEdit 重激活）
          case NodeLifecycle.Running | NodeLifecycle.Completed | NodeLifecycle.Failed | NodeLifecycle.Cancelled | NodeLifecycle.Blocked =>
            IO.unit // 已终态/运行中，幂等跳过
          case _ =>
            // 防御：wiring 空节点（无 task 无 in）不空跑（防浪费 token）
            if node.status == NodeLifecycle.Wiring && node.task.isEmpty && node.in.isEmpty then IO.unit
            else
              buildInput(node).flatMap { input =>
                spawnAndRun(node, input)
              }
    }

  /** 构造节点输入：自身 task 上下文 + 各上游 result（=== Node <name> === 头，§2.7）+
    * blocked 声明协议脚注（设计 §1.5 原文，单点注入覆盖所有节点——节点 agent 是通用
    * 全局 agent，system prompt 不含约定，必须随输入注入）。 */
  private def buildInput(node: NodeDef): IO[String] =
    val ownTask = node.task.getOrElse("")
    node.in.traverse { upId =>
      store.findNode(upId).map {
        case Some(up) =>
          up.result.map(res => s"=== Node ${up.name} ===\n$res")
        case None => None
      }
    }.map(_.flatten).map { upstream =>
      (List(ownTask).filter(_.nonEmpty) ++ upstream).mkString("\n\n") + "\n\n" + NodeEngine.ProtocolFootnote
    }

  private def spawnAndRun(node: NodeDef, inputText: String): IO[Unit] =
    val nodeId = node.id
    for
      agentOpt <- EntityLoader.loadAgent(node.agent)
      _ <- agentOpt match
        case None =>
          failNode(nodeId, s"agent '${node.agent}' not found in global library")
        case Some(entry) =>
          runWithAgent(node, entry, inputText)
    yield ()

  private def runWithAgent(node: NodeDef, entry: nebflow.core.entity.AgentEntry, inputText: String): IO[Unit] =
    val nodeId = node.id
    val sessionId = s"node-${java.util.UUID.randomUUID().toString.take(8)}"
    val projectRoot = node.worktree match
      case Some(wt) => (os.Path(workspace) / ".nebflow" / wt).toString
      case None => workspace
    val nodeName = node.name
    for
      // 状态 → running + startedAt（WS nodeUpdated，NodeList 同构 payload）。
      // 竞态修复（与终态化同族）：running 迁移落在事务内现读的 fresh 节点上——
      // getNode 快照经 EntityLoader 加载 agent 期间可能已落后，陈旧 copy 写回会
      // 覆盖该窗口内的接线变更；None = 节点已消失 → 中止 spawn（拒写复活垃圾行）。
      _ <- store.mutate { s =>
        s.nodes.get(nodeId) match
          case Some(fresh) =>
            s.copy(nodes = s.nodes.updated(nodeId, fresh.copy(
              status = NodeLifecycle.Running,
              startedAt = Some(System.currentTimeMillis()))))
          case None => s
      }.flatMap { s =>
        s.nodes.get(nodeId) match
          case Some(runningDef) =>
            emitEvent("nodeUpdated", nodeId, NodePayload.buildNodeJson(runningDef, System.currentTimeMillis()))
          case None =>
            IO.raiseError(new RuntimeException(s"Node '$nodeName' ($nodeId) vanished before start — spawn aborted"))
      }
      cancelSig <- Deferred[IO, Unit]
      _ <- running.update(_ + (nodeId -> cancelSig))
      resultDeferred <- Deferred[IO, Either[FailOutcome, List[Message]]]
      // Bridge actor：捕获 Completed/Failed/Cancelled → Deferred（同步 complete，
      // 防 Behaviors.stopped 竞态——同 FlowDagExecutor 教训）。
      bridgeRef <- system.spawn(
        Behaviors.receive[AgentEvent] { (_, event) =>
          event match
            case AgentEvent.Completed(_, messages) =>
              resultDeferred.complete(Right(messages)).void.as(Behaviors.stopped)
            case AgentEvent.Failed(_, err) =>
              resultDeferred
                .complete(Left(FailOutcome(Option(err.message).getOrElse("unknown error"))))
                .void
                .as(Behaviors.stopped)
            case AgentEvent.Cancelled(_, reason) =>
              resultDeferred.complete(Left(FailOutcome(s"cancelled: $reason"))).void.as(Behaviors.stopped)
        },
        s"nodebridge-${sessionId.take(8)}"
      )
      agentDef = entry.toAgentDef
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
          isFlowNode = true // leaf 剥离（与 flow 节点一致：无 Node 工具/展示类）
        )
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
      // 终态处理：写 result/status/ttl + 投递（§2.7）——传 nodeId，终态化内部
      // 事务内现读 fresh 节点（不沿本 fiber 启动时捕获的陈旧快照）
      _ <- eventResult match
        case Right(messages) =>
          val text = extractLastAssistantText(messages)
          completeNode(nodeId, text)
        case Left(fo) =>
          if fo.message.contains("cancelled") then cancelNode(nodeId)
          else failNode(nodeId, fo.message)
    yield ()

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
    // 非 BLOCKED 开头 → completed 原路径无损降级（误报率≈0）。
    BlockedReader.parse(resultText) match
      case Some(feedback) => blockedNode(nodeId, feedback)
      case None =>
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
                deliverOut(completed, resultText)
            case None =>
              logger.warn(s"Node '$nodeId' vanished before completion — result not persisted")
        yield ()

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
        deliverToNebula(s"[Node '${node.name}' completed]\n$resultText", node.name, "completed")
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
                if allArrived && tn.exists(_.status != NodeLifecycle.Running) then startNode(targetId)
                else IO.unit
              }
        yield ()

  private def deliverFailed(node: NodeDef, err: String): IO[Unit] =
    node.out match
      case None => IO.unit
      case Some("Nebula") =>
        deliverToNebula(s"[Node '${node.name}' failed]\n$err", node.name, "failed")
      case Some(targetId) =>
        // collect 结算：占位符标记 + 计数归零继续（§2.4 默认 collect）
        for
          targetOpt <- store.findNode(targetId)
          _ <- targetOpt match
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
                if allArrived && tn.exists(_.status != NodeLifecycle.Running) then startNode(targetId)
                else IO.unit
              }
            case None => IO.unit
        yield ()

  /** out=Nebula：ImmediateInput 投 Nebula 根会话（source="node"，复用 flow 气泡语义）。
    * 气泡 header 契约（前端 chat.js injectedSourceLabel node 分支）：
    *   source = "node" → 显示段 NODE
    *   eventType = status（"completed" | "failed"）→ 显示段 COMPLETED / FAILED
    *   sender = "<projectName>/<nodeName>" → 显示段 <项目名> · <节点名>
    * 整条 header = NODE · <项目名> · <节点名> · <状态>。 */
  private def deliverToNebula(text: String, nodeName: String, status: String): IO[Unit] =
    resources.agentRegistry.get.map(_.get(rootSessionId).map(_.ref)).flatMap {
      case Some(ref) =>
        (ref ! AgentCommand.ImmediateInput(
          text,
          source = Some("node"),
          eventType = Some(status),
          sender = Some(s"$projectName/$nodeName")
        )).void
      case None =>
        logger.warn(s"Root session '$rootSessionId' not found — node result not delivered")
        IO.unit
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
