package nebflow.shared

import munit.FunSuite

/**
 * protocol-compat 批（2026-11-01，作者令 17:49 第 3 条）——**失败文案分族**钉。
 *
 * 旧文案只有一句 `All providers failed:` + `provider/model: Reason`：配额耗尽
 * （429 计划额度，等得来）与形状失败（400 Format，我们的请求 vs 契约）混在
 * 同一个词里，读的人分不出"等一会儿会好"还是"代码错了"。
 *
 * 现文案每行前置失败族标签，并在末尾给一句族判词。
 */
class FallbackExhaustedMessageSpec extends FunSuite:

  private def attempt(p: String, m: String, r: FailoverReason, msg: Option[String] = None) =
    FallbackAttempt(p, m, Some(r), Some(ErrorPermanence.Permanent), 10L, 0, "t", msg)

  test("F1: a quota-only chain says the request shape is NOT at fault") {
    val e = FallbackExhaustedError(
      List(
        attempt("zhipu", "glm-5.3-flash", FailoverReason.RateLimit, Some("""[1308] 已达到 5 小时的使用上限""")),
        attempt("kimi", "k3.1-pro-highspeed", FailoverReason.RateLimit, Some("weekly quota reached"))
      )
    )
    val msg = e.getMessage
    assert(msg.contains("[quota]"), msg)
    assert(msg.contains("families: quota"), msg)
    assert(msg.contains("waits are meaningful"), msg)
    assert(!msg.contains("format-class"), msg)
    // 上游原话必须带出来（人能自己判断）
    assert(msg.contains("5 小时的使用上限"), msg)
  }

  test("F2: a format failure is labelled and points at the [wire] diagnostic") {
    val e = FallbackExhaustedError(
      List(
        attempt(
          "deepseek",
          "deepseek-flash",
          FailoverReason.Format,
          Some("The `content[].thinking` ... must be passed back")
        )
      )
    )
    val msg = e.getMessage
    assert(msg.contains("[format]"), msg)
    assert(msg.contains("format-class failure"), msg)
    assert(msg.contains("[wire]"), "the diagnostic pointer must be visible")
    assert(!msg.contains("waits are meaningful"), msg)
  }

  test("F3: a mixed chain names every family and does not claim a single cause") {
    val e = FallbackExhaustedError(
      List(
        attempt("zhipu", "glm-5.3-flash", FailoverReason.RateLimit),
        attempt("cmdcode", "deepseek/deepseek-v4.1-flash", FailoverReason.Format),
        attempt("kimi", "kimi-for-coding", FailoverReason.Auth)
      )
    )
    val msg = e.getMessage
    assert(msg.contains("families: quota,format,auth"), msg)
    assert(msg.contains("3 attempt(s)"), msg)
  }

  test("F4: attempt count and unknown reasons never crash the formatter") {
    val e = FallbackExhaustedError(List(FallbackAttempt("p", "m", None, None, 0L, 0, "t")))
    assert(e.getMessage.contains("[unknown] p/m: unknown"))
  }
end FallbackExhaustedMessageSpec
