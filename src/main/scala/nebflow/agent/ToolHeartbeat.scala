package nebflow.agent

import cats.effect.IO

import scala.concurrent.duration.*

/**
 * 工具执行期心跳（审计 20260903 子项①）：在 `io` 运行期间每 `interval`
 * 触发一次 `emit`——AgentCore 用它发 toolHeartbeat WS 事件喂活前端 busy
 * timer，前台长工具执行（toolStart→toolEnd 之间零事件）不再触发前端
 * 630s 纯静默超时误杀仍在干活的 turn。
 *
 * 2026-09-10 卡死判据换轴（取证 20260910_130621）：后端判据改为 agent 侧
 * （10min 零事件 ∪ 单工具超 10min），进程侧活性另存 processActivityMs 且
 * **不参与判据**；本对象是 WS 面（前端 busy timer）的保活源，两者语义已分家
 * ——前端 busy timer 仍是「长工具 = 有活动」的直观，属于换轴后遗留的口径
 * 不一致面（本批不改，见交付报告后续批建议）。
 *
 * 保证：
 *  - 首次心跳在 `interval` 之后（toolStart 事件已重置过 timer，无需 t=0 心跳）；
 *  - `io` 的值/异常原样穿透；
 *  - `io` 结束（成功/失败/被中断）心跳 fiber 必被取消，无泄漏。
 * interval 参数化——spec 缩窗模拟，严禁真实等待。
 */
object ToolHeartbeat:

  def span[A](emit: IO[Unit], interval: FiniteDuration)(io: IO[A]): IO[A] =
    (IO.sleep(interval) *> emit).foreverM.start.flatMap(fiber => io.guarantee(fiber.cancel))
end ToolHeartbeat
