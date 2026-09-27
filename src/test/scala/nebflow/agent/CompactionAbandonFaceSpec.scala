package nebflow.agent

import munit.FunSuite

import nebflow.actor.{AgentState, AgentStreamEvent, CompactionJob, compactionFailures, messages}
import nebflow.core.compact.{CompactConfig, CompactService}
import nebflow.shared.{Message, MessageRole}

/**
 * compactui 批（2026-09-15 作者实证事故）**回归闸**：压缩作业被放弃（用户中断 /
 * turn 在 compact 阶段前结束）时的两面决策。
 *
 * 断言对象 = 生产决策对象 [[CompactionAbandon]]（`private` 包装器
 * `AgentActor.emitAbandonedCompaction` / `dropCompactionScratch` 只是 IO 外壳），
 * 故本规格无需拉起 actor / 无 LLM 调用 / 零网络 —— 确定性红绿闸。
 *
 * 背景与现场读数见 [[CompactionAbandon]] 的类文档；此处只钉**判据**：
 *   1. 有 pending 作业 ⇒ 必须给出 `CompactFailed` 终局帧（否则客户端 pill 永挂）；
 *   2. 终局帧**不得**递增熔断计数（中断是用户动作）；
 *   3. 无 pending 作业 ⇒ 两面都必须 no-op（不得误摘、不得误发帧）；
 *   4. 摘除判据对**所有 profile** 的 reminder 都成立（共用签名，不是逐 profile 硬编码）。
 */
class CompactionAbandonFaceSpec extends FunSuite:

  private def userMsg(text: String): Message = Message(MessageRole.User, Left(text))

  private def stateWith(messages: List[Message], pending: Boolean, failures: Int = 2): AgentState =
    AgentState(
      messages = messages,
      sessionId = Some("compactui-spec"),
      pendingCompaction = if pending then Some(CompactionJob("compact-spec", "full", None, None)) else None,
      compactionFailures = failures
    )

  private val realReminder: Message = CompactService.buildCompactReminder(0, false, Some("compactui-spec"))

  // ---------- 1/2/3：终局帧决策 ----------

  test("no pending job => no terminal frame (no-op, 不误发)") {
    assertEquals(CompactionAbandon.event(stateWith(List(userMsg("hi")), pending = false)), None)
  }

  test("pending job => CompactFailed terminal frame (pill 清除的唯一驱动)") {
    val ev = CompactionAbandon.event(stateWith(List(realReminder), pending = true))
    ev match
      case Some(AgentStreamEvent.CompactFailed(reason, attempt, maxAttempts)) =>
        assert(reason.contains("abandoned"), s"reason=$reason")
        assertEquals(attempt, 2, "中断不得计入熔断 ⇒ attempt 必须等于现有 compactionFailures(2)")
        assertEquals(maxAttempts, CompactConfig().circuitBreakerMax)
      case other => fail(s"expected CompactFailed, got $other")
  }

  test("terminal frame on abandon never bumps the circuit breaker") {
    val st = stateWith(List(realReminder), pending = true, failures = 0)
    val ev = CompactionAbandon.event(st)
    assertEquals(ev.collect { case AgentStreamEvent.CompactFailed(_, attempt, _) => attempt }, Some(0))
    // 决策自身不得改状态（状态转换由调用点负责）
    assertEquals(st.compactionFailures, 0)
  }

  // ---------- 4：摘除压缩轮临时输入 ----------

  test("pending job => compact reminder is stripped, other messages preserved in order") {
    val msgs = List(userMsg("u1"), realReminder, userMsg("u2"))
    val out = CompactionAbandon.dropScratch(stateWith(msgs, pending = true))
    assertEquals(out.messages.map(_.content), List(msgs.head.content, msgs.last.content))
    assertEquals(out.messages.size, 2)
    assert(!out.messages.exists(CompactService.isCompactReminder), "reminder 必须被摘净")
  }

  test("no pending job => message list untouched (even if a reminder is present)") {
    val msgs = List(userMsg("u1"), realReminder)
    val out = CompactionAbandon.dropScratch(stateWith(msgs, pending = false))
    assertEquals(out.messages, msgs)
  }

  test("only the reminder is removed — plain user text is never dropped") {
    val msgs = List(userMsg("<system-reminder>x"), userMsg("plain"), realReminder)
    val out = CompactionAbandon.dropScratch(stateWith(msgs, pending = true))
    assertEquals(out.messages.map(_.content), List(msgs(0).content, msgs(1).content))
  }

  // ---------- 5：profile 覆盖（共用签名，非逐 profile 判据）----------

  test("strip predicate covers every compaction profile's reminder") {
    val profiles =
      List(
        CompactService.buildCompactReminder(0, false, Some("s")), // Root/Nebula
        CompactService.buildCompactReminder(1, true, Some("s")), // Manager (lead)
        CompactService.buildCompactReminder(1, false, Some("s")), // Worker
        CompactService.buildCompactReminder(3, false, Some("s")) // Sub-project profile
      )
    profiles.foreach(m => assert(CompactService.isCompactReminder(m), s"not recognized: ${m.content}"))
    val out = CompactionAbandon.dropScratch(stateWith(userMsg("keep") :: profiles, pending = true))
    assertEquals(out.messages.map(_.content), List(userMsg("keep").content))
  }
end CompactionAbandonFaceSpec
