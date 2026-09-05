package nebflow.agent

import cats.effect.IO

import scala.concurrent.duration.*

/**
 * 工具执行期心跳（审计 20260903 子项①）：在 `io` 运行期间每 `interval`
 * 触发一次 `emit`——AgentCore 用它发 toolHeartbeat WS 事件喂活前端 busy
 * timer，前台长工具执行（toolStart→toolEnd 之间零事件）不再触发前端
 * 630s 纯静默超时误杀仍在干活的 turn。RemoteExecutor 活动心跳
 * （touch lastActivityMs，防 TaskStuckWatcher 误判）先例的 WS 面补充，
 * 后端停摆口径统一（零输出+零 CPU 双条件 10min）。
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
