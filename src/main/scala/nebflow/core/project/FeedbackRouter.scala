package nebflow.core.project

import cats.effect.IO
import nebflow.shared.NebflowLogger

/**
 * Retry-cap escalation router (P2 retry budget, spec §2.3 gate G12): when a failed
 * node has exhausted its automatic bounce-back retries, a single escalation notice
 * goes to the root channel and no further auto-retries run.
 *
 * The blocked-feedback decision chain that used to share this file (reentry
 * rounds, mode gate, rolling-window cooldown) is removed — blocked is no longer a
 * producible terminal state; nodes report uncertainty through AskHost instead.
 *
 * One instance per project (held by NodeEngine); the escalation channel is injected
 * by the constructor (= engine.deliverToRoot with the "failed" status label).
 */
final class FeedbackRouter(
  projectName: String,
  workspace: String,
  /** failed-state escalation channel (text, nodeName) => IO[Unit]. */
  escalateFailed: (String, String) => IO[Unit]
):
  private val logger = NebflowLogger.forName("nebflow.feedback.router")

  /**
   * P2 retry cap exhausted (spec §2.3, gate G12): a failed node that bounced back
   * retry.max times and is still failed escalates straight to the root channel —
   * no window/cooldown gate (Nebula needs every one of these visible; never
   * suppressed, never merged). Audit: FlowMapEventLog "escalated" entry + WARN.
   */
  def routeRetryCap(node: NodeDef, lastErr: String): IO[Unit] =
    val text = retryCapText(node, lastErr)
    for
      _ <- FlowMapEventLog.append(
        workspace,
        projectName,
        node.id,
        "escalated",
        s"reason=RetryCap gen=${node.gen}/${node.retry.map(_.max).getOrElse(0)}: ${lastErr.take(120)}"
      )
      _ <- escalateFailed(text, node.name)
      _ <- logger.warn(
        s"Project '$projectName' node '${node.name}' retry cap exhausted (gen=${node.gen}) — escalated to Nebula, no more auto-retries"
      )
    yield ()

  end routeRetryCap

  /** Retry-cap escalation message text (deliverToRoot caller convention: carries the [Node '<name>' failed] head). */
  private def retryCapText(node: NodeDef, lastErr: String): String =
    val max = node.retry.map(_.max).getOrElse(0)
    val upstream = node.retry.map(_.upstream).getOrElse("-")
    s"[Node '${node.name}' failed]\n项目「$projectName」节点「${node.name}」(${node.id}) 回跳重试 ${max} 次" +
      s"（upstream=$upstream，gen=${node.gen}）后仍 failed——已停止自动回跳，等待处置。\n最后失败：${lastErr.take(600)}"

end FeedbackRouter

object FeedbackRouter:

  /** 滚动窗口：10min（§7.3）。 */
  val WindowMs: Long = 10 * 60 * 1000L

  /** cooldown：30min（§7.3），结束自动恢复 auto。 */
  val CooldownMs: Long = 30 * 60 * 1000L

  /** 窗口阈值：≥5 次通知触发合并（§7.3）。 */
  val WindowThreshold: Int = 5
