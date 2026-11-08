package nebflow.core.delegate

/**
 * DelegateCompletionMatrix —— 会话状态 × 子任务/链 **complete** 的单一通知决策点。
 *
 * 作者 2026-11-03 18:14 裁定逐字：「我们有四个状态 idle、stand by、merge、finish。
 * 只有在 idle 状态下，进入等待（complete），才会有 system reminder 通知。它都已经
 * 报了 stand by 了，还通知什么？所以这里的四个状态 idle complete 通知，stand by/
 * merge complete 不通知 finish complete 回报给主 agent。」
 *
 * {{{
 * | 会话状态  | complete 时                    | 决策            |
 * |-----------|--------------------------------|-----------------|
 * | idle      | 回合收尾未申报（矩阵 complete×idle） | InjectReminder  |
 * | stand by  | 已显式申报 standby，已停靠           | QueueSilently   |
 * | finish    | 终态                             | ReportToParent  |
 * }}}
 *
 * （原第四态 merge —— 等合并停靠 —— 随合并队列机制移除：delegate 不再进入合并
 * 等待态，集成是项目 merger 的授权请求；矩阵收敛为三态。）
 *
 * 语义分工：
 *   - [[NotifyDecision.InjectReminder]] —— 注入一条 system reminder（唯一执行体 =
 *     [[DelegateStandbyCare.IdleNudge]] 退避提醒；缺省 10s→30s→60s 后转 10min 档，
 *     且答复过之后间隔不低于 [[nebflow.shared.Defaults.DelegateStandbyNudgeAnswerFloorMs]]）；
 *   - [[NotifyDecision.QueueSilently]] —— **零注入零唤醒**：完成事件静默入队，会话
 *     下次自然醒来（Mail / 新指令 / 作者续聊）才可见；停靠动作、WS 帧、任务行、
 *     registry Waiting 快照等**非提醒**副作用照旧；
 *   - [[NotifyDecision.ReportToParent]] —— 既有终态投递主路径（结果回投主 agent），
 *     **不额外发任何 reminder**。
 *
 * 接线点（全部真接线，不得只是装饰）：
 *   - `nebflow.agent.BackoffSupervisor` 的 `Outcome.Standby` 腿 ⇒ [[SessionState.Standby]]
 *     （不再武装任何 caretaker）；
 *   - 同处 `Outcome.NudgeIdle` 腿 ⇒ [[SessionState.Idle]]（保留 IdleNudge 提醒）；
 *   - 同处 `Outcome.Release` 终态腿 ⇒ [[SessionState.Finish]]（既有父投递路径）。
 */
object DelegateCompletionMatrix:

  /** 会话状态（作者口述状态的代码实体；映射见各 case 注释与类注接线点）。 */
  sealed trait SessionState extends Product with Serializable

  object SessionState:
    /** 回合收尾**未申报**（引擎矩阵 complete×idle）：唯一会收到 system reminder 的状态。 */
    case object Idle extends SessionState

    /** 已显式申报 standby（`delegate_report(category="standby")`），已停靠等事件。 */
    case object Standby extends SessionState

    /** 终态（finish 申报 / 判词链放行的完成）。 */
    case object Finish extends SessionState

  /** 完成事件的通知决策。 */
  sealed trait NotifyDecision extends Product with Serializable

  object NotifyDecision:
    /** 注入 system reminder（仅 idle：催补报 standby 或 finish）。 */
    case object InjectReminder extends NotifyDecision

    /** 静默入队（standby）：零注入、零唤醒。 */
    case object QueueSilently extends NotifyDecision

    /** 结果回报主 agent（finish 终态投递主路径），无额外 reminder。 */
    case object ReportToParent extends NotifyDecision

  /** 矩阵本体（纯函数，单一真源；spec 逐行判红锚）。 */
  def decide(state: SessionState): NotifyDecision = state match
    case SessionState.Idle => NotifyDecision.InjectReminder
    case SessionState.Standby => NotifyDecision.QueueSilently
    case SessionState.Finish => NotifyDecision.ReportToParent

  /** 审计/日志用短标签（一行说清「状态 × complete ⇒ 决策」）。 */
  def describe(state: SessionState): String = state match
    case SessionState.Idle => "idle×complete ⇒ InjectReminder (system reminder, backoff)"
    case SessionState.Standby => "standby×complete ⇒ QueueSilently (zero nudge, zero wake)"
    case SessionState.Finish => "finish×complete ⇒ ReportToParent (terminal delivery, no reminder)"

end DelegateCompletionMatrix
