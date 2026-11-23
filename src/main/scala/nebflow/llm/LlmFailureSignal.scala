package nebflow.llm

import nebflow.actor.{LoopDetectedError, ToolPipelineError}
import nebflow.shared.{ErrorPermanence, FailoverReason, FallbackExhaustedError, ModelScheduleRestrictedError, StreamInactivityTimeout}

/**
 * LlmFailureSignal —— 「LLM 类失败」的**唯一判据**（LLM 失败卡片批，2026-11-21）。
 *
 * 作者令（要点）：LLM 类失败（超时/限流/认证/余额/网络/provider 5xx）**不再向上
 * 注入上级 LLM 上下文**，改由主会话卡片交给用户处理；非 LLM 错误（工具失败、代码异常、
 * 测试红）走原路径不动。本对象只回答「这个 Throwable 是不是 LLM 类」，所有注入停点
 * 都经此判据，不各自重算。
 *
 * 判据纪律：
 *   - 链穷尽（[[FallbackExhaustedError]]）= provider 侧失败，恒为 LLM 类；
 *   - 流式传输层（[[StreamInactivityTimeout]]）= 上游抖动，LLM 类；
 *   - 其余按 [[Fallback.classifyError]] 的 reason 白名单判定，且 permanence 不得为
 *     Fatal（Fatal = 上下文超长 / 取消 / 看门狗杀停，不算 provider 故障）；
 *   - [[FailoverReason.Format]]（400 请求形状）**不算** LLM 类：多半是引擎自己发出的
 *     坏请求，属于确定性缺陷，必须照常上报；
 *   - 工具流水线、循环检测、停机/卡死中止一律非 LLM 类。
 */
object LlmFailureSignal:

  /** provider 侧故障的 reason 白名单（与卡片「失败分类」的 provider 族同口径）。 */
  val LlmClassReasons: Set[FailoverReason] = Set(
    FailoverReason.Auth,
    FailoverReason.RateLimit,
    FailoverReason.Overloaded,
    FailoverReason.ServerError,
    FailoverReason.ModelNotFound,
    FailoverReason.ProviderError,
    FailoverReason.ConnectionReset,
    FailoverReason.Timeout,
    FailoverReason.EmptyStream,
    FailoverReason.NetworkUnreachable
  )

  /** 上级可见的中性句（不含 provider 原文）。 */
  val ParentNeutralNotice: String =
    "因模型不可用未完成，原始错误已转入卡片，不进入上级上下文"

  /**
   * 上级邮箱正文的单点选择：LLM 类 ⇒ 中性句（原文零注入）；非 LLM 类 ⇒ `verbatim`
   * 原路径逐字不变。`verbatim` 为 by-name，LLM 类时根本不求值。
   */
  def parentPayload(llmClass: Boolean, description: String)(verbatim: => String): String =
    if llmClass then s""""$description": $ParentNeutralNotice""" else verbatim

  /**
   * 上行失败消息的单点选择（致命路径 `AgentEvent.Failed` 的 message）：LLM 类 ⇒ 中性句，
   * 原文不进上级/节点结果；非 LLM 类 ⇒ `verbatim` 原路径逐字不变。原文由卡片直呈用户，
   * 不经本函数。
   */
  def upstreamMessage(llmClass: Boolean)(verbatim: => String): String =
    if llmClass then ParentNeutralNotice else verbatim

  /** 是否为 LLM 类失败（true = 不得向上游 LLM 上下文注入其原文，改走卡片）。 */
  def isLlmClass(error: Throwable): Boolean = error match
    case null => false
    case _: ToolPipelineError => false
    case _: LoopDetectedError => false
    case _: FallbackExhaustedError => true
    case _: StreamInactivityTimeout => true
    // 时段窗全受限（provider-notice-card-unify 批 2026-11-23）：时间闸失败 = provider
    // 侧条件性不可用（窗口结束后自愈），不是引擎缺陷——收进 LLM 卡家族（窗口受限型），
    // 不再落裸 error 帧。此前 classifyBase 把它兜底成 Unknown ⇒ 不在白名单 ⇒ 卡闸
    // 关闭，作者只看到一条无法处置的横幅。
    case _: ModelScheduleRestrictedError => true
    case _ =>
      val cls = Fallback.classifyError(error)
      LlmClassReasons.contains(cls.reason) && cls.permanence != ErrorPermanence.Fatal

end LlmFailureSignal
