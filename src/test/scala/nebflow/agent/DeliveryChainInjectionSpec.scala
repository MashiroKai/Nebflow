package nebflow.agent

import io.circe.JsonObject
import munit.FunSuite
import nebflow.actor.AgentCommand

/**
 * 送达链路修复批（2026-11-23）判红锚：
 *   P0① 续轮身份续代（resolveTerminalGeneration 纯函数）；
 *   P1⑤ 终报独立注入（injectionMessages：终报逐件独立 user 消息，非终态保持合批形）。
 */
class DeliveryChainInjectionSpec extends FunSuite:

  private def terminal(payload: String, correlationId: String): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent(
      source = "delegate",
      eventType = "completed",
      payload = payload,
      metadata = JsonObject.empty,
      correlationId = Some(correlationId)
    )

  test("P0①: debt generation wins; missing debt falls back to capturedRun, never to empty") {
    assertEquals(BackoffSupervisor.resolveTerminalGeneration(Some("gen-7"), Some("run-2")), "gen-7")
    assertEquals(BackoffSupervisor.resolveTerminalGeneration(None, Some("run-2")), "run-2")
    assertEquals(BackoffSupervisor.resolveTerminalGeneration(Some("  "), Some("run-2")), "run-2")
    assertEquals(BackoffSupervisor.resolveTerminalGeneration(None, None), "")
  }

  test("P1⑤: each delegate terminal becomes its own user message; non-terminal keeps batch form") {
    val t1 = terminal("REPORT-A", "t-a")
    val t2 = terminal("REPORT-B", "t-b")
    val other = AgentCommand.ExternalEvent("merge-queue", "released", "NOTE", JsonObject.empty, None)
    val msgs = MailboxDrain.injectionMessages(List(t1, other, t2))
    // 两条终报各自独立 + 一条非终态合批 = 3 条消息
    assertEquals(msgs.size, 3)
    val texts = msgs.map(_.content match
      case Left(t) => t
      case Right(_) => "<blocks>")
    assert(texts.head.contains("REPORT-A") && !texts.head.contains("<system-reminder>1."), texts.head)
    assert(texts(1).contains("REPORT-B"), texts(1))
    assert(texts.last.contains("NOTE"), texts.last)
  }

  test("P1⑤: no terminal event keeps the original single-batch shape (zero regression)") {
    val a = AgentCommand.ExternalEvent("merge-queue", "released", "A", JsonObject.empty, None)
    val b = AgentCommand.ExternalEvent("merge-queue", "released", "B", JsonObject.empty, None)
    val msgs = MailboxDrain.injectionMessages(List(a, b))
    val expected = MailboxDrain.mailboxMessage(List(a, b))
    assertEquals(msgs.size, 1)
    assertEquals(msgs.head.role, expected.role)
    assertEquals(msgs.head.content, expected.content)
  }

end DeliveryChainInjectionSpec
