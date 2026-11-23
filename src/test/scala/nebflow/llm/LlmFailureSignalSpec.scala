package nebflow.llm

import munit.FunSuite
import nebflow.actor.{LoopDetectedError, ToolPipelineError}
import nebflow.shared.*

/**
 * LlmFailureSignal 判据回归（LLM 失败卡片批，2026-11-21）。
 *
 * 钉死：LLM 类（provider 侧）⇒ true；工具/代码/循环/停机/卡死 ⇒ false。
 * 这是所有「不向上游 LLM 注入」停点的单一真源，改判据必须先改这里的红绿锚。
 */
class LlmFailureSignalSpec extends FunSuite:

  private def exhausted(reason: FailoverReason): FallbackExhaustedError =
    new FallbackExhaustedError(
      List(
        FallbackAttempt(
          providerId = "deepseek",
          model = "deepseek-flash",
          reason = Some(reason),
          permanence = Some(ErrorPermanence.Transient),
          durationMs = 1L,
          retriesUsed = 0,
          timestamp = "2026-11-21T00:00:00Z"
        )
      )
    )

  test("链穷尽 = LLM 类（provider 侧失败，不论上游原话）") {
    assert(LlmFailureSignal.isLlmClass(exhausted(FailoverReason.ServerError)))
    assert(LlmFailureSignal.isLlmClass(exhausted(FailoverReason.RateLimit)))
    assert(LlmFailureSignal.isLlmClass(exhausted(FailoverReason.Auth)))
    assert(LlmFailureSignal.isLlmClass(exhausted(FailoverReason.NetworkUnreachable)))
  }

  test("流式传输层超时 = LLM 类（上游抖动）") {
    assert(LlmFailureSignal.isLlmClass(StreamInactivityTimeout(lastChunkAgeMs = 600000L, message = "stall")))
  }

  test("工具流水线错误 = 非 LLM 类（原路径不动）") {
    assert(!LlmFailureSignal.isLlmClass(ToolPipelineError("bash exited 1")))
  }

  test("循环检测 = 非 LLM 类") {
    assert(!LlmFailureSignal.isLlmClass(LoopDetectedError("loop")))
  }

  test("代码异常 = 非 LLM 类（确定性缺陷必须照常上报）") {
    assert(!LlmFailureSignal.isLlmClass(new NullPointerException("npe")))
    assert(!LlmFailureSignal.isLlmClass(new AssertionError("red test")))
    assert(!LlmFailureSignal.isLlmClass(new IllegalStateException("bad state")))
  }

  test("停机 / 卡死看门狗中止 = 非 LLM 类（本进程行为，不是 provider 故障）") {
    assert(!LlmFailureSignal.isLlmClass(new ShutdownAbort))
    assert(!LlmFailureSignal.isLlmClass(new StuckAbort("s-1")))
  }

  // ── 窗口受限型（provider-notice-card-unify 批 2026-11-23）─────────────────

  test("时段窗全受限 = LLM 类（provider 侧条件性不可用，窗口结束自愈 ⇒ 卡家族收编）") {
    assert(LlmFailureSignal.isLlmClass(ModelScheduleRestrictedError(List("UTSC/glm-5.3-flash"), "06:00-01:00")))
  }

  test("null = 非 LLM 类（防御）") {
    assert(!LlmFailureSignal.isLlmClass(null))
  }

  // ── 上级零注入断言：LLM 类失败的 provider 原文不得出现在上级邮箱正文 ──

  private val rawProviderText = "HTTP 503 upstream deepseek-flash overloaded req=7f3a"

  test("上级零注入：LLM 类 ⇒ 正文不含 provider 原文，只含中性句") {
    val text = LlmFailureSignal.parentPayload(llmClass = true, "调研子任务")(s"boom: $rawProviderText")
    assert(!text.contains("503"), text)
    assert(!text.contains("deepseek-flash"), text)
    assert(!text.contains("overloaded"), text)
    assert(text.contains(LlmFailureSignal.ParentNeutralNotice), text)
    assert(text.contains("调研子任务"), text)
  }

  test("上级零注入：LLM 类 ⇒ verbatim 不被求值（原文路径未泄漏进正文）") {
    var evaluated = false
    val text = LlmFailureSignal.parentPayload(llmClass = true, "d") { evaluated = true; rawProviderText }
    assert(!evaluated)
    assert(!text.contains(rawProviderText))
  }

  test("非 LLM 类 ⇒ 原路径逐字不变（工具/代码错误照常上报原文）") {
    val verbatim = "ToolPipelineError: bash exited 1"
    assertEquals(LlmFailureSignal.parentPayload(llmClass = false, "d")(verbatim), verbatim)
  }

end LlmFailureSignalSpec
