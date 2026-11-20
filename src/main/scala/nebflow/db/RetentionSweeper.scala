package nebflow.db

import cats.effect.{Fiber, IO}
import cats.effect.unsafe.implicits.global
import org.slf4j.LoggerFactory

import scala.concurrent.duration.*

/**
 * 遗忘刀最小内嵌调度（D-9 接线期宿主定时器——轮 B 落位说明）：
 *
 * - **谁触发**：[[DbHost]].openBestEffort 成功开根 ⇒ arm（fiber 挂 cats 全局运行时）；
 *   release ⇒ 取消 fiber。装配点（GatewayMain/NodeEngine）零触碰。
 * - **周期**：[[Retention.DefaultSweepIntervalMs]]（24h）——开门后**首扫立即**
 *   （catch-up），随后按周期。
 * - **避让窗（乙件 S16→S17 同语义：遗忘清扫不得在 boot 恢复完成前跑）**：先
 *   `awaitRecoveryComplete`（[[DbHandle]].recoverPendingD8 成功开锁 = §3.11 步 3
 *   对位腿；生产触发点 PendingAskLedger.bootReconcile）。门永不开 ⇒ 永不扫
 *   （fail-safe：未恢复就绝不遗忘）。pending/snapshot/quarantine 整域结构性豁免
 *   （[[Retention.hasDebt]]）⇒「抢救中单据被当过期候选」危险面双重不可达。
 * - **为什么落 db 下层包**（作者 20:06「不在主链造基」）：主仓无统一维护作业调度
 *   基建（调度域 = 用户任务系统 ScheduledTaskActor，neblink 两处私有 executor 自挂
 *   先例）⇒ 本件 = db 库自带的接线期定时器，主链零新增基建、依赖方向主链→db 单向。
 * - 卫生：零 `import nebflow.`（V7 判据保持）。
 */
object RetentionSweeper:

  private val log = LoggerFactory.getLogger("nebflow.db")

  /** 生产默认：年龄刀 365d（设计 §4 提案·事件流域）+ 采样刀恒留（默认只年龄刀生效）。 */
  val ProductionConfig: Retention.Config = Retention.Config(
    maxAgeMs = Some(365L * 24 * 3600 * 1000),
    sampleRatioPer10k = Retention.SampleDenominator
  )

  /**
   * 挂载 sweep fiber（幂等性由调用方持有 fiber 保证：每 handle 恰一 arm）。
   * 返回 fiber 供取消（[[DbHost]].release）。
   */
  def arm(
    handle: DbHandle,
    cfg: Retention.Config = ProductionConfig,
    intervalMs: Long = Retention.DefaultSweepIntervalMs
  ): Fiber[IO, Throwable, Unit] =
    loop(handle, cfg, intervalMs).start.unsafeRunSync()

  /** 避让门 → 首扫 → 周期循环。 */
  private def loop(handle: DbHandle, cfg: Retention.Config, intervalMs: Long): IO[Unit] =
    handle.awaitRecoveryComplete *> cycle(handle, cfg, intervalMs)

  private def cycle(handle: DbHandle, cfg: Retention.Config, intervalMs: Long): IO[Unit] =
    sweepOnce(handle, cfg).flatMap {
      case true => IO.sleep(intervalMs.millis) *> cycle(handle, cfg, intervalMs)
      case false => IO.unit // 终止信号（失世代/关库）：quiet stop
    }

  /** 一轮 sweep；返回 false = 应终止（ReadOnly），true = 续跑。 */
  private def sweepOnce(handle: DbHandle, cfg: Retention.Config): IO[Boolean] =
    handle
      .retentionSweep(cfg, System.currentTimeMillis())
      .flatMap { report =>
        if report.dropped > 0 then
          IO(
            log.info(
              "retention sweep: scanned={} kept={} dropped(age={}, sampling={}) tombstones={} (logical track only)",
              report.scanned,
              report.kept,
              report.dropByAge,
              report.dropBySampling,
              report.tombstonesAppended
            )
          )
        else IO.unit
      }
      .as(true)
      .recoverWith {
        case e: DbError.ReadOnly =>
          IO(log.warn("retention sweeper stopping: {}", e.toString)).as(false)
        case e =>
          IO(log.warn("retention sweep failed (will retry next cycle): {}", e.toString)).as(true)
      }

end RetentionSweeper
