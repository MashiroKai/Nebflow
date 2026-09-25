/* Phase 3 去重(行为保持重构,2026-09-25)。 */
package nebflow.shared

import Retry.*
import cats.effect.IO
import cats.effect.testkit.TestControl
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
  * Retry.retryWithBackoff 组合子的行为钉(RetryWithBackoffSpec 与 retry.scala
  * 同步新增,零调用点改动)。时间全部走 TestControl 虚拟时钟,零真实等待
  * (手法同 McpCallTimeoutSpec)。
  *
  * 覆盖:① 成功直通 ② 耗尽后抛最后错误 ③ 分类谓词短路(异常/值两模型)
  * ④ 退避序列(含上限与注入抖动) ⑤ 中断语义(取消令牌 + fiber 取消)
  * ⑥ 值轮询模型(isSuccess 谓词,补钉 ChunkTransport/TurnEndpoint 的
  * Either/值失败模型轴)。
  */
class RetryWithBackoffSpec extends CatsEffectSuite:

  private final case class Boom(n: Int)
      extends RuntimeException(s"boom-$n") with scala.util.control.NoStackTrace

  private final case class NonRetryable(msg: String)
      extends RuntimeException(msg) with scala.util.control.NoStackTrace

  // ============================================================
  // ① 成功直通:首试即成 → 原值返回,恰好一次尝试、零延迟
  // ============================================================

  test("① 成功直通: 首试即过成功谓词 → 原值返回,恰好一次尝试、零退避延迟") {
    for
      attempts <- IO.ref(0)
      timed <- TestControl.executeEmbed(
        Retry.retryWithBackoff(attempts.update(_ + 1).as("ok"))(
          Policy(maxAttempts = 3, initialDelay = 1.second)
        ).timed
      )
      (elapsed, result) = timed
      n <- attempts.get
    yield
      assertEquals(result, "ok")
      assertEquals(n, 1, "首试即成不得触发重试")
      assertEquals(elapsed, Duration.Zero, "首试即成不得产生任何退避延迟")
  }

  // ============================================================
  // ② 耗尽后抛最后错误:全败 → 抛第 3 次的原异常(非包装),退避全额支付
  // ============================================================

  test("② 耗尽后抛最后错误: 3 次全败 → 抛第 3 次的原 Boom(身份保持),全额支付退避") {
    for
      attempts <- IO.ref(0)
      action = attempts.updateAndGet(_ + 1).flatMap(n => IO.raiseError[String](Boom(n)))
      timed <- TestControl.executeEmbed(
        Retry.retryWithBackoff(action)(
          Policy(maxAttempts = 3, initialDelay = 1.second, multiplier = 2.0)
        ).attempt.timed
      )
      (elapsed, outcome) = timed
      n <- attempts.get
    yield
      assertEquals(n, 3, "maxAttempts=3 含首次,总尝试恰好 3 次")
      assertEquals(outcome, Left(Boom(3)): Either[Throwable, String], "必须抛第 3 次的原异常,不是包装错误")
      assertEquals(elapsed, 3.seconds, "两次退避 1s+2s 必须全额支付(虚拟时钟)")
  }

  // ============================================================
  // ③ 分类谓词短路:不可重试 → 首次即终止,零退避零重试(异常模型)
  // ============================================================

  test("③ 分类谓词短路: 不可重试异常 → 首次即抛原异常,零退避零重试") {
    val classifier: AttemptFailure[Unit] => Boolean = {
      case AttemptFailure.Errored(_: NonRetryable) => false
      case _                                       => true
    }
    for
      attempts <- IO.ref(0)
      action = attempts.update(_ + 1).flatMap(_ => IO.raiseError[Unit](NonRetryable("fatal")))
      timed <- TestControl.executeEmbed(
        Retry.retryWithBackoff(action)(
          Policy(maxAttempts = 5, initialDelay = 1.second, isRetryable = classifier)
        ).attempt.timed
      )
      (elapsed, outcome) = timed
      n <- attempts.get
    yield
      assertEquals(n, 1, "不可重试分类不得发起第二次尝试")
      assertEquals(outcome, Left(NonRetryable("fatal")): Either[Throwable, Unit], "直接抛原异常,非包装")
      assertEquals(elapsed, Duration.Zero, "短路不得支付任何退避")
  }

  test("③b 值模型分类短路: 不可重试失败值 → 立即返回原值不耗尝试(ChunkTransport 口径)") {
    val classifier: AttemptFailure[Either[String, Int]] => Boolean = {
      case AttemptFailure.Unsuccessful(Left("fatal")) => false
      case _                                          => true
    }
    for
      attempts <- IO.ref(0)
      action = attempts.update(_ + 1).as(Left("fatal"): Either[String, Int])
      result <- TestControl.executeEmbed(
        Retry.retryWithBackoff(action)(
          Policy(
            maxAttempts = 5,
            initialDelay = 1.second,
            isSuccess = (_.isRight),
            isRetryable = classifier
          )
        )
      )
      n <- attempts.get
    yield
      assertEquals(result, Left("fatal"), "不可重试失败值原样返回(对齐 sendWithRetry 的立即 Left)")
      assertEquals(n, 1)
  }

  // ============================================================
  // ④ 退避序列:onAttempt 时间戳钉死相邻尝试间隔(虚拟时钟,抖动关闭)
  // ============================================================

  test("④ 退避序列: initial=1s ×2 封顶 4s → 尝试间隔 1s,2s,4s,4s") {
    for
      stamps <- IO.ref(Vector.empty[FiniteDuration])
      p: Policy[Unit] = Policy(
        maxAttempts = 5,
        initialDelay = 1.second,
        multiplier = 2.0,
        maxDelay = 4.seconds,
        onAttempt = (_, _) => IO.monotonic.flatMap(t => stamps.update(_ :+ t))
      )
      _ <- TestControl.executeEmbed(
        Retry.retryWithBackoff(IO.raiseError[Unit](Boom(0)))(p).attempt.void
      )
      v <- stamps.get
      deltas = v.sliding(2).map(w => w(1) - w(0)).toVector
    yield
      assertEquals(v.size, 5, "5 次尝试各留一个时间戳")
      assertEquals(deltas, Vector(1.second, 2.seconds, 4.seconds, 4.seconds), "指数 ×2、上限 4s 封顶")
  }

  test("④b 加性抖动: 注入 nextDouble=0.5、bound=1s → 间隔 = 基础 + 0.5s(封顶前)") {
    for
      stamps <- IO.ref(Vector.empty[FiniteDuration])
      p: Policy[Unit] = Policy(
        maxAttempts = 5,
        initialDelay = 1.second,
        multiplier = 2.0,
        maxDelay = 10.seconds,
        jitter = Jitter.Additive(bound = 1.second, nextDouble = () => 0.5d),
        onAttempt = (_, _) => IO.monotonic.flatMap(t => stamps.update(_ :+ t))
      )
      _ <- TestControl.executeEmbed(
        Retry.retryWithBackoff(IO.raiseError[Unit](Boom(0)))(p).attempt.void
      )
      v <- stamps.get
      deltas = v.sliding(2).map(w => w(1) - w(0)).toVector
    yield
      assertEquals(
        deltas,
        Vector(1.5.seconds, 2.5.seconds, 4.5.seconds, 8.5.seconds),
        "加性抖动 = min(base + 0.5×bound, maxDelay),先加再封顶(对齐 retryDelayMs 形状)"
      )
  }

  // ============================================================
  // ⑤ 中断语义:取消令牌(合作式) + fiber 取消(退避睡眠中即时生效)
  // ============================================================

  test("⑤a 取消令牌: 第 1 次失败后令牌置位 → 第 2 次尝试前抛 RetryCancelled,不再尝试") {
    for
      attempts <- IO.ref(0)
      token <- IO.ref(false)
      action = attempts.updateAndGet(_ + 1).flatMap { n =>
        if n == 1 then token.set(true).flatMap(_ => IO.raiseError[Unit](Boom(1)))
        else IO.raiseError[Unit](Boom(n))
      }
      timed <- TestControl.executeEmbed(
        Retry.retryWithBackoff(action)(
          Policy(maxAttempts = 5, initialDelay = 1.second, shouldCancel = token.get)
        ).attempt.timed
      )
      (elapsed, outcome) = timed
      n <- attempts.get
    yield
      assertEquals(n, 1, "令牌置位后不得发起第 2 次尝试")
      assertEquals(outcome, Left(RetryCancelled(2)): Either[Throwable, Unit])
      assertEquals(elapsed, 1.second, "已支付第 1 次失败后的退避,然后在第 2 次尝试前被令牌终止")
  }

  test("⑤b fiber 取消: 退避睡眠中被外部 cancel → 即时终止,无后续尝试") {
    val p: Policy[Unit] = Policy(maxAttempts = 10, initialDelay = 10.seconds)
    val program = for
      attempts <- IO.ref(0)
      fib <- Retry
        .retryWithBackoff(attempts.update(_ + 1).flatMap(_ => IO.raiseError[Unit](Boom(99))))(p)
        .start
      _ <- IO.sleep(1.second) // t=0 首试失败(虚拟瞬时)进入 10s 退避;t=1s 主线醒来取消
      _ <- fib.cancel
      _ <- fib.join
      n <- attempts.get
    yield n
    TestControl.executeEmbed(program).map(n => assertEquals(n, 1, "退避睡眠被取消后不得再尝试"))
  }

  // ============================================================
  // ⑥ 值轮询模型:isSuccess 谓词(Either/值失败模型轴,补钉站点形状)
  // ============================================================

  test("⑥ 值轮询: Left,Left,Right → 第 3 次过谓词直通,退避照常支付") {
    for
      attempts <- IO.ref(0)
      action = attempts.updateAndGet(_ + 1).flatMap { n =>
        IO.pure((if n >= 3 then Right(42) else Left(s"e$n")): Either[String, Int])
      }
      timed <- TestControl.executeEmbed(
        Retry.retryWithBackoff(action)(
          Policy(
            maxAttempts = 5,
            initialDelay = 1.second,
            multiplier = 2.0,
            isSuccess = (_.isRight)
          )
        ).timed
      )
      (elapsed, result) = timed
      n <- attempts.get
    yield
      assertEquals(result, Right(42))
      assertEquals(n, 3)
      assertEquals(elapsed, 3.seconds, "1s + 2s 两次退避后第 3 次成功")
  }

  test("⑥b 值模型耗尽: 恒 Left → 返回最后一个失败值不抛(TurnEndpoint 耗尽降级口径)") {
    for
      attempts <- IO.ref(0)
      action = attempts.updateAndGet(_ + 1).flatMap(n => IO.pure(Left(s"e$n"): Either[String, Int]))
      result <- TestControl.executeEmbed(
        Retry.retryWithBackoff(action)(
          Policy(maxAttempts = 3, initialDelay = 1.second, isSuccess = (_.isRight))
        )
      )
      n <- attempts.get
    yield
      assertEquals(result, Left("e3"), "耗尽返回最后一个失败值,不升级为异常")
      assertEquals(n, 3)
  }

end RetryWithBackoffSpec
