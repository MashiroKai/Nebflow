package nebflow.core

import nebflow.shared.{FallbackAttempt, FallbackExhaustedError, ImageCapabilityRejection}

enum NebflowError:
  case ToolFailed(toolName: String, message: String)
  case PathViolation(path: String, reason: String)

  /**
   * LLM 链失败。`imageRejectedModels` = 链上**明确拒绝接收图片**的 `provider/model`
   * 列表（flysin 收口批 2026-11-04）：非空 ⇒ 这是能力不匹配，不是抖动，文案必须
   * 点名模型并给出「换 vision 模型 / 去掉图片」两条动作。
   */
  case LlmFailed(
    summary: String,
    attempts: List[String],
    hadImage: Boolean = false,
    imageRejectedModels: List[String] = Nil
  )
  case AuthFailed(reason: String)
  case RateLimited(source: String)
  case Internal(message: String)

end NebflowError

object NebflowError:

  /**
   * 从链级 attempt 列表算出「拒绝收图的腿」（唯一判据来源 =
   * [[ImageCapabilityRejection]]）。
   */
  def imageRejections(attempts: List[FallbackAttempt]): List[String] =
    ImageCapabilityRejection.rejectingModels(attempts)

  /**
   * **链级失败 → 作者可见错误条目的唯一装配点**（flysin 收口批 2026-11-04）。
   *
   * 动因：`LlmFailed` 的字段组合过去由两条调用点各自拼（`AgentProcessing` 的
   * agent 面、`ChatRoutes` 的 REST/OpenAI 面）。能力不匹配的判据一旦散在调用点，
   * 迟早有一处忘了传 ⇒ 作者在那里重新看到「笼统 provider 失败」——正是本批要
   * 消除的失败面。现在判据与文案都收在这一个函数里，调用点只负责「本回合带没带
   * 图」这一件它们才知道的事。
   *
   * @param e        链穷尽错误（`attempts` 里带着每个候选的上游原话）
   * @param hadImage 本回合的请求是否真带了图（调用点判据 = `FallbackExhaustedError
   *                 .hadImage` 与会话消息 / 请求体任一为真）
   */
  def llmFailure(e: FallbackExhaustedError, hadImage: Boolean): NebflowError.LlmFailed =
    LlmFailed(
      e.getMessage,
      e.attempts.map(a => s"${a.providerId}/${a.model}: ${a.reason.map(_.toString).getOrElse("unknown")}"),
      hadImage = hadImage,
      imageRejectedModels = imageRejections(e.attempts)
    )

  def toUserMessage(err: NebflowError): String = err match
    case ToolFailed(tool, msg) => s"Tool '$tool' failed: $msg"
    case PathViolation(path, reason) => s"Path access denied ($path): $reason"
    case LlmFailed(summary, attempts, hadImage, imageRejectedModels) =>
      val lower = attempts.mkString(" ").toLowerCase
      val transient = lower.contains("timeout") || lower.contains("rate") ||
        lower.contains("overload") || lower.contains("server") || lower.contains("reset")
      // flysin 收口批（2026-11-04）：**能力不匹配排在 transient 之前**。
      // 旧序是「hadImage && !transient」——链上只要混一条 timeout/rate，永久能力
      // 事实就被「LLM 服务暂时不可用，请稍后重试」盖掉（作者报障现场就是这样：
      // 同一句话反复出现，界面却说等服务恢复）。现在：已知谁拒绝收图 ⇒ 点名它；
      // 只知道「本回合带了图」（尚无点名证据）⇒ 沿用链级文案。
      if imageRejectedModels.nonEmpty then ImageCapabilityRejection.userText(imageRejectedModels)
      // visionfix (甲): this request carried images and no candidate in the chain
      // could deliver them. `transient` still dominates the LEGACY wording below
      // (an upstream jitter / rate-limit failure must never be re-labelled as a
      // vision-capability failure when we have no name to point at).
      else if lower.contains("auth") then "LLM 认证或权限失败，请检查凭据和模型权限"
      else if hadImage && !transient then "这条消息里的图片没能送出去：当前模型链没有一个支持看图。请改用支持看图的模型，或去掉图片后重发。"
      else if transient then "LLM 服务暂时不可用，请稍后重试"
      else s"AI request failed: $summary"
    case AuthFailed(reason) => s"Authentication failed: $reason"
    case RateLimited(source) => s"Rate limit exceeded ($source). Please wait and try again."
    case Internal(message) => s"Internal error: $message"
end NebflowError
