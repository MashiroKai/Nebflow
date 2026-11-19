/* Phase 3 去重(行为保持重构,2026-09-25)。 */
package nebflow.core.daemon

import cats.effect.std.Dispatcher
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import scala.concurrent.duration.*

/**
 * DaemonService.backoffDelay 纯函数钉(Phase 3 去重迁移的活跃覆盖)。
 *
 * 背景:DaemonSpec 的生命周期测试(start/stop、crash-loop、health check)全部
 * .ignore 且依赖真进程(`sh -c` / `sleep`,平台相关),退避路径此前零活跃覆盖;
 * 本 spec 是纯函数确定性测试,不 spawn 任何进程。
 *
 * 语义对照(迁移前原式,行为逐值保持):
 *   delay = min(max(1, baseSec) × 2^min(attempt-1, 5), 60s) 秒
 * 即:初值 = max(1, restartBackoffSec) 秒;倍率 ×2;指数封顶 32× base
 * (min(attempt-1, 5));延迟封顶 MaxBackoffDelay = 60s;无抖动。
 */
class DaemonBackoffDelaySpec extends FunSuite:

  private def withDispatcher[A](f: Dispatcher[IO] => A): A =
    val (disp, release) = Dispatcher.parallel[IO].allocated.unsafeRunSync()
    try f(disp)
    finally release.unsafeRunSync()

  private def backoff(attempt: Int, baseSec: Int): FiniteDuration =
    withDispatcher(disp => new DaemonService(disp).backoffDelay(attempt, baseSec))

  test("指数序列 base=1s: 1,2,4,8,16,32s,此后钉在 32s(指数封顶 32×base,不是 60s)") {
    assertEquals(backoff(1, 1), 1.second)
    assertEquals(backoff(2, 1), 2.seconds)
    assertEquals(backoff(3, 1), 4.seconds)
    assertEquals(backoff(4, 1), 8.seconds)
    assertEquals(backoff(5, 1), 16.seconds)
    assertEquals(backoff(6, 1), 32.seconds)
    assertEquals(backoff(7, 1), 32.seconds, "2^6=64s 被指数封顶钳回 32×1s")
    assertEquals(backoff(50, 1), 32.seconds)
  }

  test("延迟封顶 60s: base=30s → 30s,60s,60s;base=2s → 2,4,8,16,32,60(指数先到 32×2=64 再合 60)") {
    assertEquals(backoff(1, 30), 30.seconds)
    assertEquals(backoff(2, 30), 60.seconds)
    assertEquals(backoff(3, 30), 60.seconds)
    assertEquals(backoff(1, 2), 2.seconds)
    assertEquals(backoff(5, 2), 32.seconds)
    assertEquals(backoff(6, 2), 60.seconds, "min(2×32=64s, 60s) = 60s")
    assertEquals(backoff(7, 2), 60.seconds)
  }

  test("base 下钳: baseSec ≤ 0 → 按 1s 起步") {
    assertEquals(backoff(1, 0), 1.second)
    assertEquals(backoff(1, -5), 1.second)
    assertEquals(backoff(2, 0), 2.seconds)
  }

end DaemonBackoffDelaySpec
