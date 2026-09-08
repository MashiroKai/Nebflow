package nebflow.core.project

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import nebflow.core.NebflowLogger

/** FeedbackRouter 的路由裁决（设计 §2.2/§2.3/§3）。 */
sealed trait FeedbackDecision
object FeedbackDecision:
  /** 自动重入：ProjectActor.ReenterDispatcher → spawn 重入分发器会话（§2.2）。 */
  case object Reenter extends FeedbackDecision
  /** 升级 Nebula（deliverToNebula 通道，eventType="blocked"）：escalate-only 档 /
    * 节点循环超限（§3.1）/ 项目 cooldown 触发（§3.2，合并单条）。 */
  final case class Escalate(reason: EscalateReason) extends FeedbackDecision
  /** cooldown 期内：不重入、不新升级（单条合并升级已在 cooldown-on 时投出）。 */
  case object Suppress extends FeedbackDecision

  sealed trait EscalateReason
  object EscalateReason:
    /** feedbackMode=escalate-only（§7.1 档位 B）。 */
    case object Mode extends EscalateReason
    /** 节点循环超限：blockCount > MaxBlockRoundsPerNode（§3.1）。 */
    case object LoopCap extends EscalateReason
    /** 项目级频率保护触发：10min 窗口 ≥5 次 blocked → 30min cooldown（§3.2）。 */
    case object CooldownOn extends EscalateReason
    /** P2 retry 预算耗尽（20260908 spec §2.3，gate G12）：failed 节点自动回跳
      * retry.max 次后仍 failed → 升级 Nebula 等待处置（重入协议族内聚——复用
      * blocked 升级通道形态，不经 blocked 裁决链：decide 的窗口/cooldown 状态机
      * 是 blocked 反馈语义，retry cap 是独立预算轴，不走 suppress/合并逻辑）。 */
    case object RetryCap extends EscalateReason

/**
 * blocked 反馈路由器（20260902 设计 §2.2–§2.4 + §3）——档位决策链 + 项目级频率保护。
 *
 * 决策链（依次）：escalate-only 档 → 升级；blockCount > MaxBlockRoundsPerNode →
 * 升级（附全轮次反馈历史）；cooldown 期内 → Suppress；窗口 ≥5 次 → cooldown-on
 * （单条合并升级）；否则 → ReenterDispatcher 自动重入。
 *
 * 守卫状态选型（§3.2 二选一）：路由器自持 Ref[IO, GuardState]，不挂 ProjectActor。
 * 理由：① 决策链（档位/上限/窗口）单点收口，blockedNode 在 NodeEngine 事务外直接
 * 调 route，不需要绕道 actor 消息才能拿到裁决；② ProjectActor 保持薄命令接收器
 * （只收 ReenterDispatcher），路由器可独立实例化单测（tiny 窗口/cooldown 时长注入）；
 * ③ 重启丢窗口计数可接受——限流器是成本保护不是安全机制（设计 §3.2 原文）。
 *
 * 每项目一个实例（NodeEngine 持有）；escalate 通道由构造方注入（= engine.deliverToNebula）。
 */
final class FeedbackRouter(
  projectName: String,
  workspace: String,
  /** auto（默认，自动重入）| escalate-only（blocked 直接升级 Nebula）。 */
  val feedbackMode: String,
  /** 升级投递通道 (text, nodeName) => IO[Unit]——NodeEngine 注入 deliverToNebula(_, _, "blocked")。 */
  escalate: (String, String) => IO[Unit],
  /** 滚动窗口/冷却时长（默认 10min/30min；测试注入 tiny 值验证状态机）。 */
  windowMs: Long = FeedbackRouter.WindowMs,
  cooldownMs: Long = FeedbackRouter.CooldownMs,
  /** failed 态升级通道（P2 RetryCap 用，spec §2.3 G12）：NodeEngine 注入
    * deliverToNebula(_, _, "failed")（前端 label 按真实终态显示 FAILED 而非
    * BLOCKED）。None = 回退 blocked 通道（既有测试/构造零改动——事件文本自辨）。
    * 置于参数列末位带默认值——既有位置构造（FeedbackRouterSpec）零破坏。 */
  escalateFailed: Option[(String, String) => IO[Unit]] = None,
):
  private val logger = NebflowLogger.forName("nebflow.feedback.router")

  private case class GuardState(
    blockedAt: List[Long] = Nil, // 滚动窗口内的 blocked 时间戳
    cooldownUntil: Long = 0L
  )

  private val guard: Ref[IO, GuardState] = Ref.unsafe[IO, GuardState](GuardState())

  /** 每节点 blocked 反馈历史（本进程内）——升级消息附全轮次反馈（§3.1）。 */
  private val history: Ref[IO, Map[String, List[BlockedFeedback]]] =
    Ref.unsafe[IO, Map[String, List[BlockedFeedback]]](Map.empty)

  /** 裁决（公开便于单测）：原子更新窗口/cooldown 状态，返回 (裁决, 是否本次触发 cooldown-on)。 */
  def decide(node: NodeDef): IO[(FeedbackDecision, Boolean)] =
    guard.modify { g =>
      val now = System.currentTimeMillis()
      val recent = g.blockedAt.filter(t => now - t <= windowMs) :+ now
      val inCooldown = now < g.cooldownUntil
      if inCooldown then
        (g.copy(blockedAt = recent), (FeedbackDecision.Suppress: FeedbackDecision, false))
      else if feedbackMode == FeedbackRouter.ModeEscalateOnly then
        (g.copy(blockedAt = recent), (FeedbackDecision.Escalate(FeedbackDecision.EscalateReason.Mode): FeedbackDecision, false))
      else if node.blockCount > NodeEngine.MaxBlockRoundsPerNode then
        (g.copy(blockedAt = recent), (FeedbackDecision.Escalate(FeedbackDecision.EscalateReason.LoopCap): FeedbackDecision, false))
      else if recent.size >= FeedbackRouter.WindowThreshold then
        val ng = g.copy(blockedAt = recent, cooldownUntil = now + cooldownMs)
        (ng, (FeedbackDecision.Escalate(FeedbackDecision.EscalateReason.CooldownOn): FeedbackDecision, true))
      else
        (g.copy(blockedAt = recent), (FeedbackDecision.Reenter: FeedbackDecision, false))
    }

  /** blockedNode 终态化后的路由入口（§2.1 动作④）：裁决 → 执行 + 审计。 */
  def route(node: NodeDef, feedback: BlockedFeedback): IO[Unit] =
    for
      _ <- history.update(h => h.updated(node.id, h.getOrElse(node.id, Nil) :+ feedback))
      verdict <- decide(node)
      _ <- verdict match
        case (FeedbackDecision.Reenter, _) => reenter(node, feedback)
        case (FeedbackDecision.Escalate(reason), cooldownJustOn) => doEscalate(node, feedback, reason, cooldownJustOn)
        case (FeedbackDecision.Suppress, _) =>
          logger.warn(s"Project '$projectName' node '${node.name}' blocked during cooldown — suppressed (merged into single escalation)")
    yield ()

  /** P2 retry cap 耗尽升级（spec §2.3，gate G12）：failed 节点回跳 retry.max 次
    * 仍 failed → 直走升级通道（不经 decide 裁决链——窗口/cooldown 状态机是 blocked
    * 反馈语义；retry cap 是独立预算轴，Nebula 需要每条都可见，不 Suppress 不合并）。
    * 审计同款：FlowMapEventLog "escalated" 留痕 + WARN。 */
  def routeRetryCap(node: NodeDef, lastErr: String): IO[Unit] =
    val text = retryCapText(node, lastErr)
    for
      _ <- FlowMapEventLog.append(workspace, projectName, node.id, "escalated",
        s"reason=RetryCap gen=${node.gen}/${node.retry.map(_.max).getOrElse(0)}: ${lastErr.take(120)}")
      _ <- escalateFailed.getOrElse(escalate)(text, node.name)
      _ <- logger.warn(
        s"Project '$projectName' node '${node.name}' retry cap exhausted (gen=${node.gen}) — escalated to Nebula, no more auto-retries")
    yield ()

  /** RetryCap 升级消息文本（deliverToNebula 调用方习惯：自带 [Node '<name>' failed] 头）。 */
  private def retryCapText(node: NodeDef, lastErr: String): String =
    val max = node.retry.map(_.max).getOrElse(0)
    val upstream = node.retry.map(_.upstream).getOrElse("-")
    s"[Node '${node.name}' failed]\n项目「$projectName」节点「${node.name}」(${node.id}) 回跳重试 ${max} 次" +
      s"（upstream=$upstream，gen=${node.gen}）后仍 failed——已停止自动回跳，等待处置。\n最后失败：${lastErr.take(600)}"

  /** 档位 A：自动重入 → ProjectActor.ReenterDispatcher（§2.2）。 */
  private def reenter(node: NodeDef, feedback: BlockedFeedback): IO[Unit] =
    ProjectRuntimeRegistry.get(projectName).flatMap {
      case Some(rt) =>
        rt.actorRef match
          case Some(ref) =>
            FlowMapEventLog.append(workspace, projectName, node.id, "reentry-triggered",
              s"round ${node.blockCount}: dispatcher reentry for [${feedback.category}] ${feedback.detail.take(120)}") *>
              (ref ! ProjectActor.ProjectCommand.ReenterDispatcher(node.id, feedback, node.blockCount)).void
          case None =>
            logger.warn(s"Project '$projectName' has no actorRef — reentry for node '${node.name}' skipped")
      case None =>
        logger.warn(s"Project '$projectName' not mounted — reentry for node '${node.name}' skipped")
    }

  /** 档位 B：升级 Nebula（§2.3）。仅 cooldown-on 时合并为单条（§7.4：其余即时投）。 */
  private def doEscalate(node: NodeDef, feedback: BlockedFeedback, reason: FeedbackDecision.EscalateReason, cooldownJustOn: Boolean): IO[Unit] =
    for
      hist <- history.get.map(_.getOrElse(node.id, Nil))
      text = escalateText(node, feedback, reason, hist)
      _ <- FlowMapEventLog.append(workspace, projectName, node.id, "escalated",
        s"reason=$reason round ${node.blockCount}: [${feedback.category}] ${feedback.detail.take(120)}")
      _ <- if cooldownJustOn then
        FlowMapEventLog.append(workspace, projectName, node.id, "cooldown-on",
          s"${FeedbackRouter.WindowThreshold} blocked in window — cooldown ${cooldownMs / 60000}min, reentry paused")
      else IO.unit
      _ <- escalate(text, node.name)
      _ <- logger.warn(s"Project '$projectName' node '${node.name}' blocked escalated to Nebula (reason=$reason, round ${node.blockCount})")
    yield ()

  /** 升级消息文本（deliverToNebula 调用方习惯：自带 [Node '<name>' blocked] 头）。 */
  private def escalateText(node: NodeDef, feedback: BlockedFeedback, reason: FeedbackDecision.EscalateReason, hist: List[BlockedFeedback]): String =
    val head = s"[Node '${node.name}' blocked]"
    val histSection =
      if hist.length <= 1 then ""
      else
        "\n历史轮次反馈（本进程内记录 " + hist.length + " 轮）：\n" +
          hist.zipWithIndex.map((f, i) => s"  ${i + 1}. [${f.category}] ${f.detail} — 建议: ${f.suggestion}").mkString("\n")
    reason match
      case FeedbackDecision.EscalateReason.LoopCap =>
        s"$head\n项目「$projectName」节点「${node.name}」(${node.id}) 第 ${node.blockCount} 次 blocked，超过自动重入上限 ${NodeEngine.MaxBlockRoundsPerNode}——已停止重入，等待处置。\n本轮反馈：[${feedback.category}] ${feedback.detail} — 建议: ${feedback.suggestion}$histSection"
      case FeedbackDecision.EscalateReason.CooldownOn =>
        s"$head\n项目「$projectName」10 分钟内 blocked 达 ${FeedbackRouter.WindowThreshold} 次——进入 ${cooldownMs / 60000} 分钟冷却：期间不再自动重入，后续 blocked 合并为本条升级不再逐条投递，冷却结束自动恢复。\n最新节点「${node.name}」(${node.id})：[${feedback.category}] ${feedback.detail} — 建议: ${feedback.suggestion}"
      case FeedbackDecision.EscalateReason.Mode =>
        s"$head\n项目「$projectName」节点「${node.name}」(${node.id}) blocked（feedbackMode=escalate-only，不自动重入）：\n[${feedback.category}] ${feedback.detail} — 建议: ${feedback.suggestion}"
      case FeedbackDecision.EscalateReason.RetryCap =>
        // RetryCap 走 routeRetryCap/retryCapText（需 lastErr 上下文，本函数签名无）——
        // 此分支仅为穷尽性（防御未来误用），文案指向专用通道。
        s"$head\n项目「$projectName」节点「${node.name}」(${node.id}) retry 预算耗尽（gen=${node.gen}/${node.retry.map(_.max).getOrElse(0)}）——等待处置（详见 retryCap 专用升级文本）。"

object FeedbackRouter:
  val ModeAuto = "auto"
  val ModeEscalateOnly = "escalate-only"

  /** 滚动窗口：10min（§7.3）。 */
  val WindowMs: Long = 10 * 60 * 1000L
  /** cooldown：30min（§7.3），结束自动恢复 auto。 */
  val CooldownMs: Long = 30 * 60 * 1000L
  /** 窗口阈值：≥5 次 blocked 触发 cooldown（§7.3）。 */
  val WindowThreshold: Int = 5
