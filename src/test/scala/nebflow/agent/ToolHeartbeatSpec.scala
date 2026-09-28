package nebflow.agent

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * 工具执行期心跳（审计 20260903 子项①，验收：参数化缩窗模拟——50ms 间隔，
 * 严禁真实等待 30s/10min）：
 *  - T1 心跳在 io 运行期间按间隔持续发射（120ms / 50ms ≥ 2 次命中）；
 *  - T2 io 结果原样穿透；
 *  - T3 io 失败时 fiber 取消、心跳停发（无泄漏），异常原样上抛；
 *  - T4 首跳在 interval 之后（<interval 内零发射——toolStart 已重置过 timer）。
 * 变异验红：span 改为纯 io（去掉心跳发射）→ T1 红。
 */
class ToolHeartbeatSpec extends CatsEffectSuite:

  override val munitIOTimeout = 20.seconds

  test("T1: heartbeats fire continuously while io runs (50ms interval, 120ms body → ≥2 beats)") {
    for
      beats <- Ref.of[IO, Int](0)
      emit = beats.update(_ + 1)
      result <- ToolHeartbeat.span(emit, 50.millis)(IO.sleep(120.millis).as("done"))
      count <- beats.get
    yield
      assertEquals(result, "done")
      assert(count >= 2, s"expected ≥2 heartbeats in 120ms at 50ms interval, got $count")
  }

  test("T2: io value passes through unchanged (fast io, zero beats needed)") {
    for result <- ToolHeartbeat.span(IO.unit, 1.hour)(IO.pure(42))
    yield assertEquals(result, 42)
  }

  test("T3: io failure propagates and the heartbeat fiber is cancelled (no leaked beats after)") {
    for
      beats <- Ref.of[IO, Int](0)
      emit = beats.update(_ + 1)
      err <- ToolHeartbeat.span(emit, 30.millis)(IO.sleep(40.millis) *> IO.raiseError[Unit](new RuntimeException("boom")))
        .attempt
      countAtFail <- beats.get
      _ <- IO.sleep(120.millis) // heartbeats MUST NOT continue after io settled
      countAfter <- beats.get
    yield
      assertEquals(err.isLeft, true)
      assertEquals(err.swap.toOption.map(_.getMessage), Some("boom"))
      assert(countAtFail >= 1, s"expected ≥1 beat before failure, got $countAtFail")
      assertEquals(countAfter, countAtFail, "heartbeat fiber must be cancelled when io fails")
  }

  test("T4: no heartbeat before the first interval elapses (toolStart already reset the timer)") {
    for
      beats <- Ref.of[IO, Int](0)
      emit = beats.update(_ + 1)
      _ <- ToolHeartbeat.span(emit, 500.millis)(IO.unit)
      count <- beats.get
    yield assertEquals(count, 0, "io completing before the first interval must see zero beats")
  }
end ToolHeartbeatSpec
