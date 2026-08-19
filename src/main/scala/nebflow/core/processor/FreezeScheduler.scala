package nebflow.core.processor

import cats.effect.IO
import cats.syntax.all.*
import nebflow.agent.{AgentCommand, AgentStatus, SharedResources}
import nebflow.core.NebflowLogger

import scala.concurrent.duration.FiniteDuration

/**
 * 冻结恢复扫描器（freeze-schedule spec v1.1 ⑤，TaskStuckWatcher 的姊妹模式）。
 *
 * 每 interval（默认 30s，Defaults.FreezeCheckIntervalSec）扫 agentRegistry，
 * status==Frozen 的 agent 发 CheckFreezeGate——agent 在 frozen behavior 中自行
 * 重评估时间表（支持运行中改配置/时钟漂移）：仍冻结 → 留任（更新内部 resumeAt）；
 * 已开放 → Resumed 事件 + 恢复挂起的 dispatch。消息幂等：到达非 frozen behavior
 * 时 no-op，无需 epoch 去重（D4）。
 *
 * 恢复延迟上限 = interval（30s 可接受，D4）；setWorkSchedule 配置热更时另走
 * 即时 scan（不等轮询）。
 *
 * 工程约束（同 TaskStuckWatcher）：
 *   - 错误自愈：scan 失败 handleErrorWith 吞掉防 fiber 崩溃——扫描器必须自愈；
 *   - `>>`（by-name）递归防栈溢出——`*>` 严格求值会在 IO 构建期无限递归。
 */
object FreezeScheduler:

  private val logger = NebflowLogger.forName("nebflow.core.processor.freeze")

  /** 周期扫描循环：scan → sleep(interval) → 递归。由 GatewayMain 以 fiber 启动。 */
  def run(resources: SharedResources, interval: FiniteDuration): IO[Unit] =
    def loop: IO[Unit] =
      scan(resources).handleErrorWith(e =>
        logger.warn(s"FreezeScheduler scan failed (will retry next cycle): ${e.getMessage}")
      ) *> IO.sleep(interval) >> loop
    loop

  /** 单轮扫描：向所有 Frozen 态 agent 发 CheckFreezeGate。独立成函数便于复用与测试。 */
  def scan(resources: SharedResources): IO[Unit] =
    resources.agentRegistry.get.flatMap { registry =>
      registry.values.toList
        .filter(rec => rec.status == AgentStatus.Frozen)
        .traverse_ { rec =>
          (rec.ref ! AgentCommand.CheckFreezeGate)
            .handleErrorWith(e =>
              logger.warn(s"FreezeScheduler: CheckFreezeGate to ${rec.sessionId} failed: ${e.getMessage}")
            )
        }
    }

end FreezeScheduler
