package nebflow.agent

import nebflow.core.compact.{CompactConfig, CompactService}

/**
 * 压缩作业被**放弃**（用户中断 / turn 在 compact 阶段前结束）时的两面决策。
 *
 * 纯决策对象（无 IO、无 actor 依赖）——沿用 `TurnBoundaryDrains` 的「纯决策 + 调用点
 * 执行」先例（见 [[AgentActor]] 顶部注释），便于单测直接断言。
 *
 * ==事故来源（compactui 批 · 2026-09-15 作者实证）==
 *
 * 作者 16:45 起在同一会话观测到两症状：
 *   ① 「正在压缩上下文」pill 出现后**不消失**（16:47/16:48 后续消息正常到达）；
 *   ② 压缩摘要全文（`<analysis>/<summary>/<files>`）**进入对话窗消息列表**，
 *      渲染在该 pill 正下方。
 *
 * 现场取证（日志 + 会话落盘，双源逐字对齐）：
 * {{{
 * 16:45:50.428  lifecycle event=compaction-window-start detail=phase=Compact
 * 16:47:32      user 「只清提示词文本（推荐）/不清（不做）」   ← AskUser 卡答
 * 16:48:57      user 「然后对好友/设备对话框里的附件…」
 * 16:48:57.034  lifecycle event=interrupt detail=reason=user
 * 16:51:19.853  lifecycle event=turn-complete msgs=226 textLen=7363 textStreamed=true thinking=7614
 * 16:51:19.854  落盘 ai 条目 text=7363 字符（首行 `<analysis>`，含 `<summary>` 6106 字符）
 * }}}
 * `turn-complete` 的 `textLen=7363` 与落盘 `ai` 条目的 `text` 长度**逐字相等** ⇒ 压缩轮的
 * LLM 回复走了**普通助手消息**路径入窗。
 *
 * ==根因（两症状同根：放弃路径缺终局帧 + 缺现场回滚）==
 *
 * 1. **pill 永挂**：客户端 pill 生命周期只由终局帧驱动 —— `main.js` 的 `setCompacting`
 *    仅有 `compactStart(true)` / `compactComplete(false)` / `compactFailed(false)` 三处调用
 *    （`web/js/main.js:2422,2435,2470`），`interrupted` 处理器不触碰压缩状态
 *    （`web/js/main.js:1255-1278`）。而中断面原先只清 `pendingCompaction`、**不发任何
 *    压缩帧** ⇒ ① 客户端 `state.compactingSessionIds` 残留（并连带使
 *    `main.js:2759`「非压缩中才 drain 队列」长期失效）；② 服务端落盘的
 *    `chat.compacting` UiMessage 无后继 terminal 条目 ⇒ 每次历史重放被
 *    `persistence.js:161-163` 重建为**活动 pill**（事故会话现取：6 条
 *    `chat.compacting` 中 **5 条为孤儿**）。
 * 2. **摘要在窗**：`startDirectCompaction` 把压紧后的历史 + 摘要指令 reminder 装进
 *    `state.messages`（`AgentCore.scala:470-472`），而 `resetForInterrupt`
 *    （`protocol.scala:1847-1860`）**原样保留** `execution.messages` ⇒ 中断后 reminder
 *    存活到**下一个正常轮**，模型照指令把 `<analysis>/<summary>/<files>` 全文当普通
 *    助手回复产出 ⇒ 入窗 + 落盘为 `ai` 条目（事故读数：`thinking` 末尾即 reminder 尾句
 *    `"…Keep it dense but complete."`）。
 *
 * ==修复语义==
 *
 * - [[event]]：放弃时补发 `CompactFailed` 终局帧 ⇒ 客户端清 pill **且**服务端落一条
 *   `chat.compactFailed` terminal 条目 ⇒ 重放侧孤儿被 `persistence.js:160` 抑制
 *   （一处修两症）。**不递增 circuit breaker** —— 中断/放弃是用户动作，不得计入
 *   `compactionFailures` 触发熔断（本对象只出帧，状态转换由调用点负责）。
 * - [[dropScratch]]：放弃时摘掉压缩轮临时输入（摘要指令），使下一个正常轮的 prompt
 *   不再携带「写摘要」指令。
 */
object CompactionAbandon:

  /** 放弃压缩作业时应补发的**终局帧**；无 pending 作业 ⇒ `None`（no-op）。
    *
    * 只发帧、不改状态：`compactionFailures` 原样带入帧（不 +1），因为中断是用户动作。
    */
  def event(state: AgentState): Option[AgentStreamEvent] =
    state.pendingCompaction.map(_ =>
      AgentStreamEvent.CompactFailed(
        "Compaction abandoned: the turn ended before compaction finished",
        state.compactionFailures,
        CompactConfig().circuitBreakerMax
      )
    )

  /** 放弃压缩作业时摘掉压缩轮的**临时输入**（`CompactService.isCompactReminder` 命中的
    * 摘要指令消息）。
    *
    * 只在**仍有 pending 作业**时摘除：作业正常终局时 `handleCompactResponse` 用自己的
    * 产物（summary + 尾部保真轮）整体替换消息列，本方法不得介入。
    * 依据 [[CompactService.isCompactReminder]] 单点判据（`<system-reminder>` +
    * `Context compaction required` 前缀），不新造判据。
    */
  def dropScratch(state: AgentState): AgentState =
    if state.pendingCompaction.isEmpty then state
    else state.withMessages(state.messages.filterNot(CompactService.isCompactReminder))
