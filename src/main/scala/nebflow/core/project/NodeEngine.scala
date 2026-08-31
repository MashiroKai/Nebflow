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
 *    ttlExpireAt=+5min → 沿 out 投递
 * 3. 投递（§2.7）：out=节点 → 下游 deliveredTo 记录 + barrier 归零启动下游（输入 =
 *    task 上下文 + 各上游 result 带 === Node <name> === 头）；out=Nebula →
 *    ImmediateInput("[Node '<name>' completed]\n<result>", source="node") 投根会话；
 *    out=null → 结果保留（接线后从活动/归档自动投递）；failed →
 *    ImmediateInput("[Node '<name>' failed]\n<err>")
 * 4. NodeCancel：运行中节点 → cancel 信号 → 停 agent + status=cancelled（不投递）
 *
 * 硬约束：不设运行超时（TaskStuckWatcher 10min 零活动兜底）；节点无 Mail 身份。
 */
class NodeEngine(
  val store: FlowMapStore,
  system: ActorSystem,
  resources: SharedResources,
  val wsSendFn: Json => IO[Unit],
  workspace: String,
  rootSessionId: String,
  /** WS 事件推送（type → payload 载体）。 */
  emitEvent: (String, String, Json) => IO[Unit]
):
  private val logger = NebflowLogger.forName("nebflow.node.engine")

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

  /** WS nodeRemoved（TTL 移除/归档后通知前端移除卡片）。 */
  def emitRemoved(nodeId: String): IO[Unit] =
    emitEvent("nodeRemoved", nodeId, Json.obj())

  /** WS nodeCreated（NodeEdit 创建后）。 */
  def emitCreated(node: NodeDef): IO[Unit] =
    emitEvent("nodeCreated", node.id, node.asJson)

  /** WS nodeUpdated（NodeEdit 编辑/状态变更后）。 */
  def emitUpdated(node: NodeDef): IO[Unit] =
    emitEvent("nodeUpdated", node.id, node.asJson)

  /** 显式投递（改接投递 §2.3：已完成节点结果 → 指定目标）。 */
  def deliverOutTo(node: NodeDef, target: String, resultText: String): IO[Unit] =
    target match
      case "Nebula" => deliverToNebula(s"[Node '${node.name}' completed]\n$resultText")
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
          case NodeLifecycle.Running | NodeLifecycle.Completed | NodeLifecycle.Failed | NodeLifecycle.Cancelled =>
            IO.unit // 已终态/运行中，幂等跳过
          case _ =>
            // 防御：wiring 空节点（无 task 无 in）不空跑（防浪费 token）
            if node.status == NodeLifecycle.Wiring && node.task.isEmpty && node.in.isEmpty then IO.unit
            else
              buildInput(node).flatMap { input =>
                spawnAndRun(node, input)
              }
    }

  /** 构造节点输入：自身 task 上下文 + 各上游 result（=== Node <name> === 头，§2.7）。 */
  private def buildInput(node: NodeDef): IO[String] =
    val ownTask = node.task.getOrElse("")
    node.in.traverse { upId =>
      store.findNode(upId).map {
        case Some(up) =>
          up.result.map(res => s"=== Node ${up.name} ===\n$res")
        case None => None
      }
    }.map(_.flatten).map { upstream =>
      (List(ownTask).filter(_.nonEmpty) ++ upstream).mkString("\n\n")
    }

  private def spawnAndRun(node: NodeDef, inputText: String): IO[Unit] =
    val nodeId = node.id
    for
      agentOpt <- EntityLoader.loadAgent(node.agent)
      _ <- agentOpt match
        case None =>
          failNode(node, s"agent '${node.agent}' not found in global library")
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
      // 状态 → running + startedAt（WS nodeUpdated）
      _ <- store.mutate(s => s.copy(nodes = s.nodes.updated(nodeId, node.copy(status = NodeLifecycle.Running, startedAt = Some(System.currentTimeMillis())))))
      _ <- emitEvent("nodeUpdated", nodeId, node.copy(status = NodeLifecycle.Running).asJson)
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
          wsSend = wsSendFn,
          projectRoot = Some(projectRoot),
          safetyMode = "confirm-edits",
          rootSessionId = rootSessionId,
          isFlowNode = true // leaf 剥离（与 flow 节点一致：无 Node 工具/展示类）
        )
      )
      _ <- resources.agentRegistry.update(_ + (sessionId -> AgentRecord(sessionId, ref, AgentKind.Flow, rootSessionId)))
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
      // 终态处理：写 result/status/ttl + 投递（§2.7）
      _ <- eventResult match
        case Right(messages) =>
          val text = extractLastAssistantText(messages)
          completeNode(node, text)
        case Left(fo) =>
          if fo.message.contains("cancelled") then cancelNode(node)
          else failNode(node, fo.message)
    yield ()

  private def completeNode(node: NodeDef, resultText: String): IO[Unit] =
    val now = System.currentTimeMillis()
    val completed = node.copy(
      status = NodeLifecycle.Completed,
      result = Some(resultText),
      completedAt = Some(now),
      ttlExpireAt = Some(now + NodeEngine.TtlDisplayMs)
    )
    for
      _ <- store.mutate(s => s.copy(nodes = s.nodes.updated(node.id, completed)))
      _ <- emitEvent("nodeCompleted", node.id, completed.asJson)
      _ <- logger.info(s"Node '${node.name}' completed (result ${resultText.length} chars)")
      _ <- deliverOut(completed, resultText)
    yield ()

  private def failNode(node: NodeDef, err: String): IO[Unit] =
    val now = System.currentTimeMillis()
    val failed = node.copy(
      status = NodeLifecycle.Failed,
      result = Some(err),
      completedAt = Some(now),
      ttlExpireAt = Some(now + NodeEngine.TtlDisplayMs)
    )
    for
      _ <- store.mutate(s => s.copy(nodes = s.nodes.updated(node.id, failed)))
      _ <- emitEvent("nodeUpdated", node.id, failed.asJson)
      _ <- logger.warn(s"Node '${node.name}' failed: ${err.take(200)}")
      // 失败投递（§2.7）：out=Nebula → failed 消息；out=节点 → collect 结算（占位符）。
      _ <- deliverFailed(failed, err)
    yield ()

  private def cancelNode(node: NodeDef): IO[Unit] =
    val now = System.currentTimeMillis()
    val cancelled = node.copy(
      status = NodeLifecycle.Cancelled,
      completedAt = Some(now),
      ttlExpireAt = Some(now + NodeEngine.TtlDisplayMs)
    )
    store.mutate(s => s.copy(nodes = s.nodes.updated(node.id, cancelled))) *>
      emitEvent("nodeUpdated", node.id, cancelled.asJson) *>
      logger.info(s"Node '${node.name}' cancelled")

  // ── 投递（§2.7）─────────────────────────────────────────

  /** out=节点：barrier 归零 → 启动下游。dedup 用 deliveredTo（防改接重投）。 */
  private def deliverOut(node: NodeDef, resultText: String): IO[Unit] =
    node.out match
      case None => IO.unit // 悬空：结果保留在 result（持久化），接线后自动投递
      case Some("Nebula") =>
        deliverToNebula(s"[Node '${node.name}' completed]\n$resultText")
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
        deliverToNebula(s"[Node '${node.name}' failed]\n$err")
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

  /** out=Nebula：ImmediateInput 投 Nebula 根会话（source="node"，复用 flow 气泡语义）。 */
  private def deliverToNebula(text: String): IO[Unit] =
    resources.agentRegistry.get.map(_.get(rootSessionId).map(_.ref)).flatMap {
      case Some(ref) =>
        (ref ! AgentCommand.ImmediateInput(
          text,
          source = Some("node"),
          eventType = Some("completed"),
          sender = Some("node")
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
  /** 终态节点显示 TTL（5min；测试档可缩短——ProjectActor 注入）。 */
  val TtlDisplayMs: Long = 5 * 60 * 1000L

  /** 节点 id 前缀（sessionId = "node-<uuid>"）。 */
  val SessionPrefix = "node-"
