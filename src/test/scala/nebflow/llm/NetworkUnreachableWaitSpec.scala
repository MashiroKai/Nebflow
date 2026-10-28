package nebflow.llm

import munit.FunSuite
import nebflow.shared.{ErrorPermanence, FailoverReason, FallbackAttempt, FallbackExhaustedError}

import java.net.{ConnectException, NoRouteToHostException, SocketException, SocketTimeoutException, UnknownHostException}

/**
 * #692（网络不可达退避等待修批，2026-10-21 作者令）判据 spec：网络级失败 =
 * 可等待（不终态），供应商明确拒绝 = 真失败（failed），混合链不等（等不来的
 * 是配置不是网络）。
 *
 * 现场锚（10-21 断网实测）：链 [zhipu Timeout(Permanent), kimi Unknown(Transient)]
 * 在旧语义下 `llmFailureRetryable=false` → fatal 判死，两批阵亡；本 spec 钉住
 * 该形状必须判「可等待」。
 */
class NetworkUnreachableWaitSpec extends FunSuite:

  private def attempt(reason: FailoverReason, permanence: ErrorPermanence): FallbackAttempt =
    FallbackAttempt(
      providerId = "p",
      model = "m",
      reason = Some(reason),
      permanence = Some(permanence),
      durationMs = 10,
      retriesUsed = 0,
      timestamp = "t"
    )

  // ── ① 链穷尽形状判据（agent 层等待分支的输入）─────────────────────────

  test("10-21 incident shape [Timeout(Permanent), Unknown(Transient)] is waitable") {
    val err = new FallbackExhaustedError(
      List(
        attempt(FailoverReason.Timeout, ErrorPermanence.Permanent),
        attempt(FailoverReason.Unknown, ErrorPermanence.Transient)
      )
    )
    assert(Fallback.isNetworkUnreachableExhaustion(err), "zhipu timeout + kimi unknown = network outage shape, must wait")
  }

  test("pure connect-refused chain is waitable") {
    val err = new FallbackExhaustedError(
      List(attempt(FailoverReason.NetworkUnreachable, ErrorPermanence.Transient))
    )
    assert(Fallback.isNetworkUnreachableExhaustion(err))
  }

  test("connection-reset chain is waitable") {
    val err = new FallbackExhaustedError(
      List(attempt(FailoverReason.ConnectionReset, ErrorPermanence.Transient))
    )
    assert(Fallback.isNetworkUnreachableExhaustion(err))
  }

  test("401/403 auth in the chain is NOT waitable — explicit rejection waits for no one") {
    val err = new FallbackExhaustedError(
      List(
        attempt(FailoverReason.Timeout, ErrorPermanence.Permanent),
        attempt(FailoverReason.Auth, ErrorPermanence.Permanent)
      )
    )
    assert(!Fallback.isNetworkUnreachableExhaustion(err), "mixed [timeout, auth]: the 401 will never heal — real failure")
  }

  test("pure auth chain is NOT waitable") {
    val err = new FallbackExhaustedError(List(attempt(FailoverReason.Auth, ErrorPermanence.Permanent)))
    assert(!Fallback.isNetworkUnreachableExhaustion(err))
  }

  test("format(400) in the chain is NOT waitable") {
    val err = new FallbackExhaustedError(
      List(
        attempt(FailoverReason.Format, ErrorPermanence.Permanent),
        attempt(FailoverReason.Unknown, ErrorPermanence.Transient)
      )
    )
    assert(!Fallback.isNetworkUnreachableExhaustion(err))
  }

  test("model-not-found(404) in the chain is NOT waitable") {
    val err = new FallbackExhaustedError(
      List(attempt(FailoverReason.ModelNotFound, ErrorPermanence.Permanent))
    )
    assert(!Fallback.isNetworkUnreachableExhaustion(err))
  }

  test("pure overload chain is NOT network-waitable (existing LlmTransient freeze owns it)") {
    val err = new FallbackExhaustedError(
      List(attempt(FailoverReason.Overloaded, ErrorPermanence.Transient))
    )
    assert(!Fallback.isNetworkUnreachableExhaustion(err))
  }

  test("empty attempt list is not waitable (nothing known — conservative)") {
    val err = new FallbackExhaustedError(Nil)
    assert(!Fallback.isNetworkUnreachableExhaustion(err))
  }

  // ── ② 类型化网络异常直判（分类层）────────────────────────────────────

  test("typed JDK network exceptions are detected through the cause chain") {
    assert(Fallback.isNetworkUnreachableThrowable(new ConnectException("Connection refused")))
    assert(Fallback.isNetworkUnreachableThrowable(new UnknownHostException("api.x.com")))
    assert(Fallback.isNetworkUnreachableThrowable(new NoRouteToHostException("no route")))
    assert(Fallback.isNetworkUnreachableThrowable(new SocketTimeoutException("connect timed out")))
    // wrapped (sttp-style): wrapper RuntimeException carrying the JDK root cause
    val wrapped = new RuntimeException("request failed", new ConnectException("refused"))
    assert(Fallback.isNetworkUnreachableThrowable(wrapped))
  }

  test("plain SocketException stays OUT of the typed set (mid-stream reset keeps the short-freeze path)") {
    // SocketException 双面性：message 带不可达措辞 = 网络断（等待）；裸
    // "Connection reset" = 流中断 flap（既有 10-30s 短冻结，语义不变）。
    assert(Fallback.isNetworkUnreachableThrowable(new SocketException("Network is unreachable")))
    assert(Fallback.isNetworkUnreachableThrowable(new SocketException("Network is down")))
    assert(!Fallback.isNetworkUnreachableThrowable(new SocketException("Connection reset")))
  }

  test("non-network errors are not typed network-unreachable") {
    assert(!Fallback.isNetworkUnreachableThrowable(new RuntimeException("boom")))
    assert(!Fallback.isNetworkUnreachableThrowable(new java.util.concurrent.TimeoutException("timeout")))
  }

  test("classifyError maps typed network failures to NetworkUnreachable/Transient") {
    val cls = Fallback.classifyError(new ConnectException("Connection refused"))
    assertEquals(cls.reason, FailoverReason.NetworkUnreachable)
    assertEquals(cls.permanence, ErrorPermanence.Transient)
    assert(cls.evict, "network-down evicts so the probe loop can markUp on recovery")
  }

  // ── ③ 等待退避阶梯（5s/15s/45s/…封顶 5min）——private[agent] 常量，钉在
  // nebflow.agent 包内的 RestartingWatchdogSpec（同批）。

end NetworkUnreachableWaitSpec
