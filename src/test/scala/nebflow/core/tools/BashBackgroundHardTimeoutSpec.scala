package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * #391 机制 B（2026-08-25 用户裁定「输出零增长且 CPU 零消耗才杀」）：后台兜底
 * 硬超时 + 停滞检测——运行 > BashBackgroundHardTimeoutMs（默认 30min）后进入
 * 停滞观察：输出零增长且 CPU 增量 < 10ms/采样窗口 连续 ≥ BashStuckWindowSec
 * （默认 120s）→ killProcessTree + TimeoutException。idle timeout 加 CPU 豁免
 * （CPU 忙不算 idle 不杀）。
 *
 * 验收（设计文档 §3.6）：
 * - B-1 后台 sleep 3600（无输出无 CPU）→ 注入硬超时 → 杀 + TimeoutException
 * - B-2 CPU 忙任务（python busy loop）→ idle 豁免 + 硬超时后停滞观察不杀（双条件）
 * - B-3 先输出后停滞（python 吐 1000 字符后 sleep）→ 硬超时后停滞窗口到点 → 杀
 * - B-4 阈值可配注入（hardTimeoutMs / stuckWindowSec / healthCheckIntervalSec）
 */
class BashBackgroundHardTimeoutSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 90.seconds

  /** 轮询 background_job_id 直到完成（消费即移除）。 */
  private def pollCompleted(shell: ShellSession, jobId: String, deadlineMs: Long): IO[Either[Throwable, ProcessResult]] =
    def loop: IO[Either[Throwable, ProcessResult]] =
      shell.getBackgroundResult(jobId).flatMap {
        case Some(res) => IO.pure(res)
        case None => IO.sleep(300.millis) *> loop
      }
    loop.timeout(deadlineMs.millis)

  private def assertTimeout(res: Either[Throwable, ProcessResult]): Unit =
    res match
      case Left(e: scala.concurrent.TimeoutException) => ()
      case Left(e) => fail(s"expected TimeoutException, got: ${e.getClass.getSimpleName}: ${e.getMessage}")
      case Right(r) => fail(s"expected timeout failure, got success: ${r.stdout.take(100)}")

  test("B-1+B-4: background sleep 3600 killed by injected hard timeout + stuck window") {
    for
      shell <- ShellSession.forSession("bg-hard-1")
      _ <- shell.executeBackground(
        "sleep 3600",
        jobIdOverride = Some("b1"),
        hardTimeoutMs = 4000,
        stuckWindowSec = 2,
        healthCheckIntervalSec = 1
      )
      res <- pollCompleted(shell, "b1", 20000)
      _ <- IO(assertTimeout(res))
      _ <- ShellSession.destroySession("bg-hard-1")
    yield ()
  }

  test("B-2: CPU-busy background task survives idle exemption AND hard-timeout stall guard") {
    for
      shell <- ShellSession.forSession("bg-hard-2")
      _ <- shell.executeBackground(
        "python3 -c 'while True: pass'",
        jobIdOverride = Some("b2"),
        hardTimeoutMs = 3000,
        stuckWindowSec = 2,
        healthCheckIntervalSec = 1
      )
      // 等 > hardTimeout(3s) + stuckWindow(2s)，CPU 忙 → 不杀
      _ <- IO.sleep(6500.millis)
      health <- shell.getBackgroundJobHealth("b2")
      _ <- IO(assert(health.exists(_.isAlive), s"CPU-busy task must not be killed: $health"))
      _ <- shell.cancelBackgroundJob("b2")
      _ <- ShellSession.destroySession("bg-hard-2")
    yield ()
  }

  test("B-3: output-then-stall background task killed after hard timeout + stuck window") {
    for
      shell <- ShellSession.forSession("bg-hard-3")
      _ <- shell.executeBackground(
        "python3 -c 'import sys; sys.stdout.write(\"x\"*1000); import time; time.sleep(3600)'",
        jobIdOverride = Some("b3"),
        hardTimeoutMs = 4000,
        stuckWindowSec = 2,
        healthCheckIntervalSec = 1
      )
      // 先等进程吐出输出（health 记录 outputLineCount ≥ 1）
      _ <- IO.sleep(2000.millis)
      res <- pollCompleted(shell, "b3", 20000)
      _ <- IO(assertTimeout(res))
      _ <- ShellSession.destroySession("bg-hard-3")
    yield ()
  }

end BashBackgroundHardTimeoutSpec
