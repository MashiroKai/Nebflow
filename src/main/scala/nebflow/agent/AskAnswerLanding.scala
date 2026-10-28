package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.AgentCommand
import nebflow.bridge.AskAnswerReceipt
import nebflow.core.project.{DelegateFlowHosts, FlowMapEventLog, ProjectRuntimeRegistry}
import nebflow.core.tools.ToolContext
import nebflow.shared.{AskItem, NebflowLogger}

/**
 * AskUser 社交腿答案的**着陆面**（answer-landing 实施批 2026-10-21；设计批
 * `reports/20261020_123548_askuser-social-answer-inject/` §3–§5 原案落地）。
 *
 * == 缺口（本面对治的根因）==
 *
 * 上行链路 U1–U6（sidecar 收信 → 渠道闸 → 映射解析 → hub `Answered` → `complete`）
 * 全部健康；唯一断点在 U7：发起会话已消亡（后台批节点以 blocked 终态收场后
 * 会话被销毁）时，答案在 `AskUserAnswerBridge.deliver` 的 `sessionAlive=false`
 * 分支被 WARN + 丢弃——「hub 收得到、判得对、但送达面单点断裂」。本面对该分支
 * 接**三臂着陆**（原分支的 WARN+丢 = 本面失败出口的前身，行为被取代）：
 *
 *   1. **会话亡＋归因在＋项目宿主在（节点在项目图内）** ⇒ 引擎重入：
 *      `ProjectActor.AskAnswerReentry`（ReenterDispatcher 同族管线；分发器把答案
 *      折进节点任务后 NodeEdit 重激活——开放问题 #5 采纳推荐默认 5b：答案 = 对
 *      该问题的直接回答，随 task 重读进入节点）。审计 `answer-retry`（任务令
 *      措辞；先例 `reentry-triggered` 同基座）。
 *   2. **项目图无此节点、委托作用域宿主在且委托会话活** ⇒ 注入**委托会话**：
 *      委托作用域无分发器（DelegateFlowRuntime 零 actor 指令面），委托会话自己
 *      就是该图的编排者——答案作为用户输入注入（fromUser=true），由它 NodeEdit
 *      重激活节点。
 *   3. **归因缺席 / 宿主不在（项目卸载 / 链归档 / 委托宿主已收割 / 委托会话已亡）**
 *      ⇒ **失败出口**：WARN + 审计行（`answer-retry-failed`，有锚点时）+ 桌面
 *      toast（`interactionAnswerRejected` 既有帧族，前端零改动）+ **渠道失败回执**
 *      （「问题已作废 + 原因」经 [[AskAnswerReceipt]] 反向通道——任务令缺陷①的
 *      never-silent 收口：僵尸问题卡就此显式作废）。任何出口不静默。
 *
 * == 归因快照（设计 §5）==
 *
 * `ToolContext` 的 `(projectName, flowNodeId, flowChainId)` 由 `AgentCore` 在
 * 会话 spawn 时从引擎侧 SessionContext 透传（不信客户端参数）；桥引用构造时把
 * **ctx 闭包捕获**即完成快照——ToolContext 不可变，hub 槽位 complete 前后的任何
 * 运行期状态漂移都不影响本面读到的值。归因缺席（root 自问 / Nebula / 分发器）
 * = 合法状态，直接映射失败出口。
 *
 * == 不做的（设计 §4「不做的」逐条）==
 *
 *   · 不复活已删除的 root/delegate 直会话（超范围）；
 *   · 不重开已归档链（对齐 restoreChain 需显式授权的精神）；
 *   · 不引入等待/超时（零超时纪律；本面只处置「答案后到」，不处置「问题久等」）。
 */
private[agent] object AskAnswerLanding:

  private val logger = NebflowLogger.forName("nebflow.agent.askuser")

  /** 重入审计事件类型（FlowMapEventLog 基座；先例 `reentry-triggered`）。 */
  val AuditEventType: String = "answer-retry"

  /** 失败出口审计事件类型（有项目/节点锚点时落）。 */
  val AuditFailedType: String = "answer-retry-failed"

  /** 渠道失败回执行（任务令缺陷①的「问题已作废+原因」显式回执）。 */
  def failureReceiptLine(reason: String): String =
    s"问题已作废：答案未送达——$reason。该问题不会再等待作答（如需继续请在桌面面板重新发起）。"

  /** 渠道成功回执行（重入臂）：作者在社交腿得到「答案已被接住」的显式回音。 */
  def reentryReceiptLine(target: String): String =
    s"已收到。发起会话已结束，答案已转交引擎重入（「$target」将以该答复继续）。"

  /**
   * 着陆判定（纯函数，单点分派；spec 固化三臂判据）。
   *
   * @param hasAttribution     归因二元组 (project, flowNodeId) 是否齐备
   * @param nodeInProjectGraph 节点在归因项目图内（非委托作用域节点）
   * @param projectActorPresent 项目宿主带 ProjectActor（重入指令面）
   * @param delegateHostAlive  委托作用域宿主在册且委托会话存活（臂2 前提）
   */
  sealed trait Landing
  object Landing:
    /** 臂1：项目链引擎重入（ProjectActor.AskAnswerReentry）。 */
    case object ProjectReentry extends Landing
    /** 臂2：委托链注入委托会话（作用域根的 ImmediateInput）。 */
    case object DelegateInjection extends Landing
    /** 臂3：失败出口（显式回执 + 审计 + toast，绝不静默）。 */
    final case class Fail(reason: String) extends Landing

  def decide(
    hasAttribution: Boolean,
    nodeInProjectGraph: Boolean,
    projectActorPresent: Boolean,
    delegateHostAlive: Boolean
  ): Landing =
    if !hasAttribution then Landing.Fail("归因缺席：发起会话非项目/委托节点会话（root/直会话已结束），答案无法重入")
    else if nodeInProjectGraph then
      if projectActorPresent then Landing.ProjectReentry
      else Landing.Fail("宿主不在：项目已挂载但无引擎指令面（ProjectActor 缺席），答案无法重入")
    else if delegateHostAlive then Landing.DelegateInjection
    else Landing.Fail("宿主不在：归因节点已不在任何在册引擎图中（项目图无此节点、委托宿主未在册或已收割），答案无法重入")

  /**
   * 着陆执行面（bridge `sessionAlive=false` 分支的唯一入口）。
   *
   * @param requestId  提问 id（审计 + 回执通道键）
   * @param items      问题集（判据面保留；注入文本已格式化）
   * @param answerText 已格式化的答复文本（= 活会话臂注入的同一把 `formatAnswer` 尺
   *                   + 附件清单段；设计 §3「答案语义不变」）
   * @param ctx        桥构造时的 ToolContext（归因快照 + 注册表 + wsSend）
   */
  def land(requestId: String, items: List[AskItem], answerText: String, ctx: ToolContext): IO[Unit] =
    val attribution = for
      project <- ctx.projectName.map(_.trim).filter(_.nonEmpty)
      nodeId <- ctx.flowNodeId.map(_.trim).filter(_.nonEmpty)
    yield (project, nodeId)

    def failWith(reason: String, ws: Option[String], p: Option[String], n: Option[String]): IO[Unit] =
      fail(requestId, reason, ws, p, n, ctx)

    attribution match
      case None =>
        failWith(
          decide(hasAttribution = false, nodeInProjectGraph = false, projectActorPresent = false, delegateHostAlive = false) match
            case Landing.Fail(r) => r
            case _ => "归因缺席"
          ,
          None,
          None,
          None
        )
      case Some((project, nodeId)) =>
        ProjectRuntimeRegistry.get(project).flatMap { hostOpt =>
          hostOpt match
            case Some(rt) =>
              rt.store.getNode(nodeId).flatMap {
                case Some(node) =>
                  decide(hasAttribution = true, nodeInProjectGraph = true, projectActorPresent = rt.actorRef.isDefined, delegateHostAlive = false) match
                    case Landing.ProjectReentry => reenterProject(requestId, node, answerText, rt, ctx)
                    case Landing.Fail(reason)   => failWith(reason, Some(rt.project.workspace), Some(project), Some(nodeId))
                    case Landing.DelegateInjection =>
                      failWith("宿主不在：项目图无重入指令面", Some(rt.project.workspace), Some(project), Some(nodeId))
                case None =>
                  // 项目宿主在但节点不在其图内 = 节点属委托作用域（归因 project =
                  // 挂载项目名 / general 座位名）——扫委托宿主定臂。
                  delegateHost(ctx).flatMap {
                    case Some((host, ref)) =>
                      decide(hasAttribution = true, nodeInProjectGraph = false, projectActorPresent = false, delegateHostAlive = ref.isDefined) match
                        case Landing.DelegateInjection => injectDelegate(requestId, host, ref.get, answerText, ctx)
                        case Landing.Fail(reason) =>
                          failWith(reason, Some(host.baseWorkspace), Some(project), Some(nodeId))
                        case Landing.ProjectReentry =>
                          failWith("宿主不在：委托图无分发器指令面", Some(host.baseWorkspace), Some(project), Some(nodeId))
                    case None =>
                      failWith(
                        "宿主不在：归因节点已不在任何在册引擎图中（项目图无此节点、委托宿主未在册或已收割）",
                        Some(rt.project.workspace),
                        Some(project),
                        Some(nodeId)
                      )
                  }
              }
            case None =>
              // 归因项目未挂载：仍可能属委托作用域（general 座位名等）——同序扫描。
              delegateHost(ctx).flatMap {
                case Some((host, ref)) =>
                  decide(hasAttribution = true, nodeInProjectGraph = false, projectActorPresent = false, delegateHostAlive = ref.isDefined) match
                    case Landing.DelegateInjection => injectDelegate(requestId, host, ref.get, answerText, ctx)
                    case Landing.Fail(reason)      => failWith(reason, Some(host.baseWorkspace), Some(project), Some(nodeId))
                    case Landing.ProjectReentry =>
                      failWith("宿主不在：委托图无分发器指令面", Some(host.baseWorkspace), Some(project), Some(nodeId))
                case None =>
                  failWith(
                    "宿主不在：归因项目已卸载（或链已归档），答案无法重入",
                    None,
                    Some(project),
                    Some(nodeId)
                  )
              }
        }

  /** 臂1：项目链引擎重入——审计 + AskAnswerReentry 指令 + 渠道回执。 */
  private def reenterProject(
    requestId: String,
    node: nebflow.core.project.NodeDef,
    answerText: String,
    rt: nebflow.core.project.ProjectRuntime,
    ctx: ToolContext
  ): IO[Unit] =
    val workspace = rt.project.workspace
    val project = rt.project.name
    FlowMapEventLog
      .append(
        workspace,
        project,
        node.id,
        AuditEventType,
        s"social answer re-entry: requestId=$requestId answer delivered to dispatcher for task-folding (node=${node.name})"
      )
      .handleErrorWith(e => logger.warn(s"ask-answer landing: audit append failed ($project/${node.id}): ${e.getMessage}")) *>
      rt.actorRef.traverse_(ref =>
        ref ! nebflow.core.project.ProjectActor.ProjectCommand.AskAnswerReentry(
          nodeId = node.id,
          requestId = requestId,
          answerText = answerText
        )
      ) *>
      logger.info(
        s"askUser answer bridge: requestId=$requestId — session gone, answer RE-ENTERED via project actor " +
          s"(project=$project node=${node.name}/${node.id})"
      ) *>
      AskAnswerReceipt.take(requestId).flatMap {
        case Some(e) =>
          e.send(reentryReceiptLine(node.name)).handleErrorWith(err =>
            logger.warn(s"ask-answer receipt: reentry confirmation send failed (${err.getMessage})")
          )
        case None => IO.unit
      }

  /**
   * 委托作用域宿主探测：归因根会话（= 作用域键 = 引擎根）即委托会话 id——
   * 按 `DelegateFlowHosts.get(scope)` 精确取宿主，活性查统一注册表（与 bridge
   * `sessionAlive` 同一权威）。
   */
  private def delegateHost(
    ctx: ToolContext
  ): IO[Option[(nebflow.core.project.DelegateFlowRuntime, Option[nebflow.actor.ActorRef[AgentCommand]])]] =
    ctx.rootSessionId.map(_.trim).filter(_.nonEmpty) match
      case None => IO.pure(None)
      case Some(scope) =>
        DelegateFlowHosts.get(scope).flatMap {
          case None => IO.pure(None)
          case Some(host) =>
            ctx.sharedResources match
              case Some(res) =>
                res.agentRegistry.get
                  .map(_.get(scope).map(_.ref))
                  .handleError(_ => None)
                  .map(ref => Some((host, ref)))
              case None => IO.pure(Some((host, None)))
        }

  /** 臂2：委托链——答案注入委托会话（作用域根），由它折进节点任务重激活。 */
  private def injectDelegate(
    requestId: String,
    host: nebflow.core.project.DelegateFlowRuntime,
    delegateRef: nebflow.actor.ActorRef[AgentCommand],
    answerText: String,
    ctx: ToolContext
  ): IO[Unit] =
    val workspace = host.baseWorkspace
    val project = host.engine.projectName
    FlowMapEventLog
      .append(
        workspace,
        project,
        ctx.flowNodeId.getOrElse("-"),
        AuditEventType,
        s"social answer re-entry: requestId=$requestId answer injected into delegate session ${host.delegateId}"
      )
      .handleErrorWith(e => logger.warn(s"ask-answer landing: audit append failed ($project): ${e.getMessage}")) *>
      (delegateRef ! AgentCommand.ImmediateInput(
        text = delegateInjectionText(answerText, ctx.flowNodeId),
        source = Some(nebflow.actor.AskUserAnswerSource.AnswerSource),
        eventType = Some(nebflow.actor.AskUserAnswerSource.AnswerSource),
        fromUser = true
      )) *>
      logger.info(
        s"askUser answer bridge: requestId=$requestId — node session gone, answer injected into delegate " +
          s"session ${host.delegateId}"
      ) *>
      AskAnswerReceipt.take(requestId).flatMap {
        case Some(e) =>
          e.send(reentryReceiptLine(project)).handleErrorWith(err =>
            logger.warn(s"ask-answer receipt: delegate confirmation send failed (${err.getMessage})")
          )
        case None => IO.unit
      }

  /** 委托注入文本：答案 = 对该问题的直接回答 + 折进节点任务的指令（5b 语义）。 */
  private def delegateInjectionText(answerText: String, flowNodeId: Option[String]): String =
    val nodeNote = flowNodeId.map(id => s"（节点 $id）").getOrElse("")
    s"""── AskUser answer arrived (the asking node session has ended; social-channel answer) ──
       |
       |A node you dispatched$nodeNote asked the user a question; the node's session has since ended.
       |The user's answer arrived via a social channel:
       |
       |$answerText
       |
       |Read the current state (NodeList), then fold this answer into that node's task with NodeEdit
       |(reactivation re-reads the task from the top) so the node resumes with the answer in hand.""".stripMargin

  /** 臂3：失败出口——WARN + 审计（有锚点时）+ 桌面 toast + 渠道回执。绝不静默。 */
  private def fail(
    requestId: String,
    reason: String,
    workspace: Option[String],
    project: Option[String],
    nodeId: Option[String],
    ctx: ToolContext
  ): IO[Unit] =
    val warn = logger.warn(
      s"askUser answer bridge: requestId=$requestId — answer NOT delivered, landing FAILED: $reason " +
        s"(project=${project.getOrElse("-")} node=${nodeId.getOrElse("-")})"
    )
    val audit = (workspace, project, nodeId) match
      case (Some(ws), Some(p), Some(n)) =>
        FlowMapEventLog
          .append(ws, p, n, AuditFailedType, s"social answer landing failed: requestId=$requestId — $reason")
          .handleErrorWith(e => logger.warn(s"ask-answer landing: audit append failed ($p/$n): ${e.getMessage}"))
      case _ => IO.unit // 无锚点（归因缺席/项目卸载）：日志 + toast + 回执已是全部显式面
    val toast = desktopToast(requestId, reason, ctx)
    val receipt = AskAnswerReceipt.sendLine(requestId, failureReceiptLine(reason))
    warn *> audit *> toast *> receipt.void

  /**
   * 桌面 toast（`interactionAnswerRejected` 既有帧族——前端 `main.js` 既有消费点
   * 零改动，`reason` 无专属文案键时落 `rejectGeneric` 兜底）。best-effort：发送
   * 失败只 WARN，绝不影响审计与渠道回执。
   */
  private def desktopToast(requestId: String, reason: String, ctx: ToolContext): IO[Unit] =
    ctx.wsSend match
      case None => IO.unit
      case Some(ws) =>
        ws(
          Json.obj(
            "type" -> "interactionAnswerRejected".asJson,
            "requestId" -> requestId.asJson,
            "reason" -> "answer-undeliverable".asJson,
            "detail" -> reason.asJson
          )
        ).handleErrorWith(e => logger.warn(s"ask-answer landing: toast send failed: ${e.getMessage}"))

end AskAnswerLanding
