package nebflow.social

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.social.outbound.{OutboundRetry, RetryPolicy}

import scala.concurrent.duration.*

/**
 * 出站有界退避的判定表 spec（社交出站 P0 批 2026-11-21，作者令③「排队出站按
 * 有界退避重试排空，不许一次失败就放弃」）。
 *
 * 钉住三件事，其余一概不管：
 *
 *   ① 退避算术 —— [[RetryPolicy.delayBefore]] 的序列（首试零等待、指数、封顶）
 *      与 [[RetryPolicy.Single]] 的历史契约（恰好一次尝试）。总等待因此有界：
 *      `maxAttempts × maxDelay`。
 *   ② 执行面语义 —— 成功即停、只吃 `retryable` 的名额、返回**最后一次**读数
 *      （调用侧原有失败语义不吞不改写）。
 *   ③ 两条腿的**确定性未送达**分类（[[FeishuBridgePlugin.retryableFailure]]）：
 *      5xx/限流可重试；鉴权/参数/目标不存在一类的确定性拒绝立刻停手。微信腿
 *      的分类表在 [[WeixinOutboundRetrySpec]]（需要 HTTP stub，单独一个文件）。
 *
 * 纯 IO 组合、无网络、无时钟（`IO.sleep` 的时长都取毫秒级，判定不看真实时间）。
 * 离线纪律与 `WeixinOutboundLogSpec` 同源：一个测试都不碰外网。
 */
class OutboundRetrySpec extends CatsEffectSuite:

  // ───────────────────────── ① 退避算术 ─────────────────────────

  test("OR-1 delayBefore: first attempt waits zero, then base, 2×base, … capped at maxDelay") {
    val p = RetryPolicy(5, 100.millis, 350.millis)
    assertEquals(p.delayBefore(1), Duration.Zero, "首试零等待（立刻开跑）")
    assertEquals(p.delayBefore(2), 100.millis, "第 2 次 = base")
    assertEquals(p.delayBefore(3), 200.millis, "第 3 次 = 2×base")
    assertEquals(p.delayBefore(4), 350.millis, "第 4 次被 maxDelay 封顶（400ms > 350ms）")
    assertEquals(p.delayBefore(9), 350.millis, "更后面恒等于 maxDelay —— 单次间隔有界")
  }

  test("OR-2 the two shipped policies: Single = exactly one attempt (历史契约), Bounded = 3 次封顶") {
    assertEquals(RetryPolicy.Single.maxAttempts, 1)
    assertEquals(RetryPolicy.Single.delayBefore(1), Duration.Zero)
    assertEquals(RetryPolicy.Bounded.maxAttempts, 3)
    assertEquals(RetryPolicy.Bounded.delayBefore(2), 500.millis)
    assertEquals(RetryPolicy.Bounded.delayBefore(3), 1.second)
    // 总等待有界：次数有界 ∧ 单次间隔有界。
    val total = (2 to RetryPolicy.Bounded.maxAttempts).map(RetryPolicy.Bounded.delayBefore).fold(Duration.Zero)(_ + _)
    assert(total <= RetryPolicy.Bounded.maxDelay * RetryPolicy.Bounded.maxAttempts, s"总等待必须有界，实际 $total")
  }

  test("OR-3 maxAttempts < 1 is refused at construction (a policy never attempts zero times)") {
    intercept[IllegalArgumentException](RetryPolicy(0, Duration.Zero, Duration.Zero))
  }

  // ───────────────────────── ② 执行面语义 ─────────────────────────

  test("OR-4 Single calls the attempt exactly once, even when the failure looks retryable") {
    for
      ref <- IO.ref(List.empty[Int])
      res <- OutboundRetry
        .run(RetryPolicy.Single)((n: Int) => ref.update(_ :+ n).as("nope"))(
          ok = (_: String) => false,
          retryable = (_: String) => true
        )
      calls <- ref.get
    yield
      assertEquals(res, "nope")
      assertEquals(calls, List(1), "Single = 恰好一次尝试（既有钉子的口径）")
  }

  test("OR-5 a retryable failure is retried up to maxAttempts and the LAST reading is returned") {
    for
      ref <- IO.ref(List.empty[Int])
      res <- OutboundRetry
        .run(RetryPolicy(3, 1.milli, 2.millis))((n: Int) => ref.update(_ :+ n).as(s"fail-$n"))(
          ok = (_: String) => false,
          retryable = (_: String) => true
        )
      calls <- ref.get
    yield
      assertEquals(calls, List(1, 2, 3), "3 次尝试、序号连续")
      assertEquals(res, "fail-3", "返回最后一次读数 —— 不吞、不改写")
  }

  test("OR-6 success stops the loop immediately (no further attempt, no extra wait)") {
    for
      ref <- IO.ref(List.empty[Int])
      res <- OutboundRetry
        .run(RetryPolicy(3, 1.milli, 2.millis))((n: Int) => ref.update(_ :+ n).as(if n >= 2 then "ok" else "fail"))(
          ok = _ == "ok",
          retryable = (_: String) => true
        )
      calls <- ref.get
    yield
      assertEquals(calls, List(1, 2), "第 2 次成功即停")
      assertEquals(res, "ok")
  }

  test("OR-7 a NON-retryable failure stops immediately (确定性拒绝不值得再试一次)") {
    for
      ref <- IO.ref(List.empty[Int])
      res <- OutboundRetry
        .run(RetryPolicy(3, 1.milli, 2.millis))((n: Int) => ref.update(_ :+ n).as("denied"))(
          ok = (_: String) => false,
          retryable = (_: String) => false
        )
      calls <- ref.get
    yield
      assertEquals(calls, List(1), "鉴权/参数类拒绝一次即止")
      assertEquals(res, "denied")
  }

  // ───────────────────── ③ 飞书腿的失败分类 ─────────────────────

  test("OR-8 feishu retryableFailure: 5xx / 429 / 99991400 可重试，确定性拒绝不可") {
    val retryable = List(500, 502, 504, 429, 99991400)
    val terminal = List(0, 200, 400, 401, 403, 404, -1, 230002)
    retryable.foreach(c => assert(FeishuBridgePlugin.retryableFailure(c), s"code=$c 应可重试"))
    terminal.foreach(c => assert(!FeishuBridgePlugin.retryableFailure(c), s"code=$c 是确定性拒绝，不该重试"))
  }

end OutboundRetrySpec
