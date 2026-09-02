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
  cooldownMs: Long = FeedbackRouter.CooldownMs
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

object FeedbackRouter:
  val ModeAuto = "auto"
  val ModeEscalateOnly = "escalate-only"

  /** 滚动窗口：10min（§7.3）。 */
  val WindowMs: Long = 10 * 60 * 1000L
  /** cooldown：30min（§7.3），结束自动恢复 auto。 */
  val CooldownMs: Long = 30 * 60 * 1000L
  /** 窗口阈值：≥5 次 blocked 触发 cooldown（§7.3）。 */
  val WindowThreshold: Int = 5
