package nebflow.core.project

import cats.effect.{IO, Ref}

/**
 * blocked 结构化信号登记表（20260909 blocked-signal 设计 spec 方案 A，改造点 #1）。
 *
 * 节点会话内调用 report_blocked 工具 → register(sessionId, feedback)；
 * 会话完成时点 NodeEngine drain（take-and-remove）→ 命中即 blockedNode 终态化，
 * 未命中走既有 BlockedReader 文本锚定降级面（行为零变化）。
 *
 * 形态照抄 BgTaskRegistry（nebflow.core.tools.BgTaskRegistry）：进程内 Ref 单例、
 * session 键控、consume-on-read。语义要点（spec §6 误判面）：
 *  - last-write-wins 单槽：同会话多次申报，最终意图优先；
 *  - drain 即移除：跨 turn 不残留，重复消费不可能；
 *  - 内存态不持久：申报后进程崩溃 → 续跑按最终输出判定（未持久化的申报不复活，
 *    与 BgTaskRegistry 崩溃边界同款；文本降级面在续跑输出仍含锚定时兜底）；
 *  - cancelled/failed 路径不消费：NodeEngine cleanupRunTables 统一 remove 对称清理。
 *
 * BlockedFeedback 同构复用（ProjectTypes.scala）——blockedNode 落库载荷零转换。
 */
object BlockedSignalRegistry:

  private val signals: Ref[IO, Map[String, BlockedFeedback]] = Ref.unsafe(Map.empty)

  /** 登记申报（last-write-wins：同会话重复调用覆盖，最终意图优先）。 */
  def register(sessionId: String, feedback: BlockedFeedback): IO[Unit] =
    signals.update(_.updated(sessionId, feedback))

  /** 完成时点消费（take-and-remove）：命中返回申报并清槽；未命中 None。 */
  def drain(sessionId: String): IO[Option[BlockedFeedback]] =
    signals.modify { m =>
      (m - sessionId, m.get(sessionId))
    }

  /** 清理钩子（不消费）：NodeEngine cleanupRunTables 对称清理点调用——
   * cancelled/failed/异常退出路径的残留申报在此兜底移除（幂等 no-op 安全）。 */
  def remove(sessionId: String): IO[Unit] =
    signals.update(_ - sessionId)
end BlockedSignalRegistry
