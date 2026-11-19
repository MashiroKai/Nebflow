package nebflow.shared

import munit.FunSuite
import nebflow.core.NebflowError

/**
 * flysin 提供商报错收口批（2026-11-04；作者报障 2026-11-03 22:23/22:36）——
 * **能力不匹配不许只以笼统 provider 失败呈现**。
 *
 * 现场事实（不重跑探针，取自 2026-11-03 的 stand 日志）：
 *   - 直连 `https://maas.flysinfo.com/v1/messages` 实测 HTTP 200 / 1.2s ⇒ 提供商不是坏了；
 *   - 唯一的真报错 = 带图请求被上游拒绝：
 *     `HTTP 400 … upstream=model deepseek-v4-1-flash-261017 does not support image input;
 *      choose a vision-capable model or remove image content`
 *   - 引擎把它归到 `FailoverReason.Format`（400 口径），于是：
 *       · 链耗尽文案：`[format] flysin/deepseek-v4-1-flash-261017: Format …` +
 *         「format-class failure: at least one provider rejected the request/response shape (400)」
 *         —— 作者读不出「图不能发」这件事；
 *       · 作者可见文案：`hadImage && !transient` 才会说「图片没能送出去」，只要链上
 *         混着一条 timeout/rate 的 attempt 就退化成正文明说的
 *         「LLM 服务暂时不可用，请稍后重试」——**永久能力事实被瞬时措辞盖掉**，
 *         而且两条文案都不点出是哪个模型不收图。
 *
 * 验收口径（二值）：一条真实形状的上游 400 进链后 —
 *   V1 链级错误条目必须出现 image-capability 族，且点名拒绝的 provider/model；
 *   V2 作者可见文案必须点名该模型，并给出「换 vision 模型 / 去掉图片」的可行动作；
 *   V3 即使链上同时有一条瞬时失败，也不许退化成「服务暂时不可用」；
 *   V4 判据只认「不支持图片输入」类原话，普通 400 形状失败不得被误标成能力问题。
 */
class ImageCapabilityRejectionSpec extends FunSuite:

  /**
   * 逐字取自 2026-11-03 生产日志（`nebflow.llm` 的 permanent error 行，
   * 含 `[wire]` 诊断串与上游原话；request id 保留原样，日志里本来就有）。
   */
  private val upstreamBody: String =
    """{"error":{"type":"new_api_error","message":"model deepseek-v4-1-flash-261017 does not """ +
      """support image input; choose a vision-capable model or remove image content """ +
      """(request id: 202611031350518583139378268d9d61MITVlzn)"}}"""

  private val wireMessage: String =
    "[wire] HTTP 400 provider=flysin model=deepseek-v4-1-flash-261017 " +
      "| request=u:t,a:ktuu,u:rr,a:kuu,u:ii,+3 | response=error/new_api_error(code=-) " +
      "| upstream=model deepseek-v4-1-flash-261017 does not support image input; " +
      "choose a vision-capable model or remove image content " +
      "| raw=" + upstreamBody

  private def attempt(
    provider: String,
    model: String,
    reason: FailoverReason,
    message: Option[String] = None
  ): FallbackAttempt =
    FallbackAttempt(provider, model, Some(reason), Some(ErrorPermanence.Permanent), 1_200L, 0, "t", message)

  /**
   * 作者可见文案：走**改后的唯一装配点** `NebflowError.llmFailure`（两条调用点同款），
   * 而不是在 spec 里手工拼 `LlmFailed` 字段——否则「调用点忘了传」这类回归测不到。
   */
  private def userMessage(e: FallbackExhaustedError): String =
    NebflowError.toUserMessage(NebflowError.llmFailure(e, hadImage = e.hadImage))

  test("mixed authentication and timeout remains an authentication issue") {
    val e = FallbackExhaustedError(List(
      attempt("p", "m", FailoverReason.Auth),
      attempt("q", "n", FailoverReason.Timeout)
    ))
    val message = userMessage(e)
    assert(message.contains("凭据"))
    assert(message.contains("权限"))
    assert(!message.contains("暂时不可用"))
  }

  test("V1: chain-level error entry names the image-capability family and the rejecting model") {
    val e = FallbackExhaustedError(
      List(attempt("flysin", "deepseek-v4-1-flash-261017", FailoverReason.Format, Some(wireMessage))),
      hadImage = true
    )
    val msg = e.getMessage
    assert(msg.contains("image-capability"), s"家族标签缺失，作者读不出「图不能发」：\n$msg")
    assert(
      msg.contains("deepseek-v4-1-flash-261017"),
      s"必须点名拒绝的模型（provider/model 字段）：\n$msg"
    )
    assert(msg.contains("flysin"), s"必须点名拒绝的 provider：\n$msg")
  }

  test("V2: the author-visible text names the model and says how to fix it") {
    val e = FallbackExhaustedError(
      List(attempt("flysin", "deepseek-v4-1-flash-261017", FailoverReason.Format, Some(wireMessage))),
      hadImage = true
    )
    val msg = userMessage(e)
    assert(msg.contains("deepseek-v4-1-flash-261017"), s"作者可见文案没有点名模型：\n$msg")
    assert(msg.contains("图片"), s"作者可见文案没有说清是图片问题：\n$msg")
    assert(msg.contains("vision") || msg.contains("看图"), s"没有给出「换 vision 模型」的动作：\n$msg")
  }

  test("V3: a mixed chain (image rejection + timeout) never collapses into 'temporarily unavailable'") {
    val e = FallbackExhaustedError(
      List(
        attempt("flysin", "deepseek-v4-1-flash-261017", FailoverReason.Format, Some(wireMessage)),
        attempt("kimi", "kimi-k3", FailoverReason.Timeout, Some("LLM stream: no response within 90s"))
      ),
      hadImage = true
    )
    val msg = userMessage(e)
    assert(
      !msg.contains("暂时不可用"),
      s"永久能力事实被瞬时措辞盖掉了（这正是本批要消除的笼统失败面）：\n$msg"
    )
    assert(msg.contains("deepseek-v4-1-flash-261017"), s"作者可见文案没有点名模型：\n$msg")
  }

  test("V4: an ordinary 400 shape failure is NOT re-labelled as a capability problem") {
    val thinkingBody =
      "The `content[].thinking` in the previous message must be passed back to the API"
    val e = FallbackExhaustedError(
      List(
        attempt(
          "deepseek",
          "deepseek-flash",
          FailoverReason.Format,
          Some(s"[wire] HTTP 400 provider=deepseek model=deepseek-flash | upstream=$thinkingBody")
        )
      ),
      hadImage = true
    )
    val msg = userMessage(e)
    assert(!msg.contains("image-capability"), s"普通 400 被误标成能力问题：\n${e.getMessage}")
    assert(!msg.contains("不支持图片"), s"普通 400 被误标成能力问题：\n$msg")
  }
