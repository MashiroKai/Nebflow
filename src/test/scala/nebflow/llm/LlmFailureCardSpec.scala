package nebflow.llm

import munit.FunSuite

/**
 * LlmFailureCard 非阻塞运行时回归（卡片批 2026-11-21）。
 * 钉死：失败登记出 llmFailure 帧；同 agent 成功 ⇒ recovered 帧并移除；无失败态 ⇒ 零帧。
 */
class LlmFailureCardSpec extends FunSuite:

  private def field(j: io.circe.Json, k: String): Option[String] =
    j.hcursor.downField(k).as[String].toOption

  test("失败登记：llmFailure 帧携带 sessionId / agentName / summary / failureClass") {
    val f = LlmFailureCard.recordFailure("s-1", "agent-a", "HTTP 503 overloaded", "llm-overloaded")
    assertEquals(field(f, "type"), Some("llmFailure"))
    assertEquals(field(f, "sessionId"), Some("s-1"))
    assertEquals(field(f, "agentName"), Some("agent-a"))
    assertEquals(field(f, "summary"), Some("HTTP 503 overloaded"))
    assertEquals(field(f, "failureClass"), Some("llm-overloaded"))
    assert(LlmFailureCard.activeAgents.contains("agent-a"))
  }

  test("恢复：同 agent 成功 ⇒ llmFailureRecovered 帧，表项移除") {
    LlmFailureCard.recordFailure("s-2", "agent-b", "timeout", "llm-timeout")
    val r = LlmFailureCard.recordSuccess("s-2", "agent-b")
    assert(r.isDefined)
    assertEquals(field(r.get, "type"), Some("llmFailureRecovered"))
    assertEquals(field(r.get, "agentName"), Some("agent-b"))
    assert(!LlmFailureCard.activeAgents.contains("agent-b"))
  }

  test("无失败态的成功调用不刷帧（recordSuccess = None）") {
    assertEquals(LlmFailureCard.recordSuccess("s-3", "agent-clean"), None)
  }

  test("同 agent 二次失败覆盖为最新摘要（同一时刻至多一张卡）") {
    LlmFailureCard.recordFailure("s-4", "agent-c", "first", "llm-timeout")
    val second = LlmFailureCard.recordFailure("s-4", "agent-c", "second", "llm-timeout")
    assertEquals(field(second, "summary"), Some("second"))
    assertEquals(LlmFailureCard.activeAgents.count(_ == "agent-c"), 1)
    LlmFailureCard.recordSuccess("s-4", "agent-c")
  }

  test("卡片帧声明可等待 / 可换模型两个动作") {
    val f = LlmFailureCard.failureFrame("s-5", "agent-d", "x", "llm-auth")
    assertEquals(f.hcursor.downField("waitable").as[Boolean].toOption, Some(true))
    assertEquals(f.hcursor.downField("switchable").as[Boolean].toOption, Some(true))
  }

end LlmFailureCardSpec
