package nebflow.core.delegate

import cats.effect.IO
import nebflow.shared.NebflowLogger

/**
 * MergeQueueCare —— 合并队列（交通引导员制）的**扫描拍驱动面**（台账 #896
 * 2026-10-26 作者裁定落地批；消费点 = `TaskStuckWatcher.scan` 尾部，搭既有扫描拍
 * 零新增定时器）。
 *
 * 一拍两件事（事件判据，零时间判据；本体在 [[MergeQueue.careTick]]）：
 *   - **持窗尸体判死**：turn-granted 成员的属主 actor 已退出 ⇒ owner-gone 关账
 *     （窗绝不许尸体持死——整队陪站的死亡形态在本拍收口）；
 *   - **补一拍放行**：每个有开放成员的 project 补一次放行尝试——净空转洁、阻碍
 *     消除、前位关账后的自然推进都经此兑现（队列自己不跑定时器，放行节奏 =
 * 事件 + 本扫描拍）。
 *
 * 幂等：动作过的成员已关账（终态），重复扫描零重复动作。本件**零重试零复活**
 * ——重试合并轮与复活轮已随交通引导员制整体退役（owner-gone / failed 关账后
 * 处置归 dispatcher，面板显式呈现，绝不静默 park）。
 */
object MergeQueueCare:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.merge-queue-care")

  /** 一拍看护（fail-soft：任何故障只 WARN，绝不拖垮扫描拍）。 */
  def tick(): IO[Unit] =
    MergeQueue.careTickIO.handleErrorWith(e =>
      logger.warn(s"[merge-queue-care] tick failed: ${Option(e.getMessage).getOrElse(e.toString).take(200)}").void
    )
end MergeQueueCare
