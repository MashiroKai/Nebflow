package nebflow.llm

import io.circe.Json
import munit.FunSuite
import nebflow.shared.ModelScheduleRestrictedError

/**
 * LlmFailureCard 非阻塞运行时回归（卡片批 2026-11-21；聚合键「层级+模型」第 3 轮）。
 * 钉死：聚合键 = (layer, modelRef)；同键多代理只出一张组快照；逐个恢复收窄；清空收卡；
 * 无失败态 ⇒ 零帧。
 */
class LlmFailureCardSpec extends FunSuite:

  private def str(j: Json, k: String): Option[String] = j.hcursor.downField(k).as[String].toOption
  private def agents(j: Json): List[String] = j.hcursor.downField("agents").as[List[String]].toOption.getOrElse(Nil)

  test("失败登记：llmFailure 帧携带 layer / modelRef / agents / summary / failureClass") {
    val f = LlmFailureCard.recordFailure("s-1", "agent-a", "HTTP 503 overloaded", "llm-overloaded", "root", "p/m-1")
    assertEquals(str(f, "type"), Some("llmFailure"))
    assertEquals(str(f, "sessionId"), Some("s-1"))
    assertEquals(str(f, "layer"), Some("root"))
    assertEquals(str(f, "modelRef"), Some("p/m-1"))
    assertEquals(agents(f), List("agent-a"))
    assertEquals(str(f, "summary"), Some("HTTP 503 overloaded"))
    assertEquals(str(f, "failureClass"), Some("llm-overloaded"))
    assert(LlmFailureCard.activeAgents.contains("agent-a"))
    LlmFailureCard.recordSuccess("s-1", "agent-a")
  }

  test("同层级 + 同模型的多个代理失败：只出一张卡（组快照列出全部代理）") {
    LlmFailureCard.recordFailure("s-2", "agent-b1", "t", "llm-timeout", "team", "p/shared")
    val second = LlmFailureCard.recordFailure("s-2", "agent-b2", "t2", "llm-timeout", "team", "p/shared")
    assertEquals(agents(second), List("agent-b1", "agent-b2"))
    assertEquals(str(second, "summary"), Some("t2"))
    LlmFailureCard.recordSuccess("s-2", "agent-b1")
    LlmFailureCard.recordSuccess("s-2", "agent-b2")
  }

  test("同模型不同层级 ⇒ 分开两张卡（键不同）") {
    LlmFailureCard.recordFailure("s-3", "agent-c1", "x", "llm-timeout", "root", "p/m-x")
    val sub = LlmFailureCard.recordFailure("s-3", "agent-c2", "x", "llm-timeout", "subtask", "p/m-x")
    assertEquals(agents(sub), List("agent-c2"))
    assertEquals(str(sub, "layer"), Some("subtask"))
    LlmFailureCard.recordSuccess("s-3", "agent-c1")
    LlmFailureCard.recordSuccess("s-3", "agent-c2")
  }

  test("组内逐个恢复：仍有成员 ⇒ 收窄的 llmFailure 快照；清空 ⇒ llmFailureRecovered") {
    LlmFailureCard.recordFailure("s-4", "agent-d1", "x", "llm-auth", "team", "p/m-d")
    LlmFailureCard.recordFailure("s-4", "agent-d2", "x", "llm-auth", "team", "p/m-d")
    val narrowed = LlmFailureCard.recordSuccess("s-4", "agent-d1")
    assert(narrowed.isDefined)
    assertEquals(str(narrowed.get, "type"), Some("llmFailure"))
    assertEquals(agents(narrowed.get), List("agent-d2"))
    val done = LlmFailureCard.recordSuccess("s-4", "agent-d2")
    assertEquals(str(done.get, "type"), Some("llmFailureRecovered"))
    assertEquals(str(done.get, "layer"), Some("team"))
    assertEquals(str(done.get, "modelRef"), Some("p/m-d"))
    assert(!LlmFailureCard.activeAgents.contains("agent-d2"))
  }

  test("无失败态的成功调用不刷帧（recordSuccess = None）") {
    assertEquals(LlmFailureCard.recordSuccess("s-5", "agent-clean"), None)
  }

  test("同代理二次失败覆盖为最新摘要（组内同一代理至多一条）") {
    LlmFailureCard.recordFailure("s-6", "agent-e", "first", "llm-timeout", "root", "p/m-e")
    val second = LlmFailureCard.recordFailure("s-6", "agent-e", "second", "llm-timeout", "root", "p/m-e")
    assertEquals(str(second, "summary"), Some("second"))
    assertEquals(agents(second), List("agent-e"))
    assertEquals(LlmFailureCard.activeAgents.count(_ == "agent-e"), 1)
    LlmFailureCard.recordSuccess("s-6", "agent-e")
  }

  test("层级判定：深度 0 = root；无 replyTo 的有父 = team；其余 = subtask") {
    assertEquals(LlmFailureCard.layerOf(0, teamMember = false), "root")
    assertEquals(LlmFailureCard.layerOf(1, teamMember = true), "team")
    assertEquals(LlmFailureCard.layerOf(1, teamMember = false), "subtask")
  }

  test("卡片帧声明可等待 / 可换模型两个动作") {
    val f = LlmFailureCard.failureFrame("s-7", "agent-f", "x", "llm-auth")
    assertEquals(f.hcursor.downField("waitable").as[Boolean].toOption, Some(true))
    assertEquals(f.hcursor.downField("switchable").as[Boolean].toOption, Some(true))
  }

  // ── 窗口受限型（provider-notice-card-unify 批 2026-11-23）─────────────────

  private def scheduleError =
    new ModelScheduleRestrictedError(List("UTSC/glm-5.3-flash", "zhipu/glm-4.7"), "06:00-01:00")

  test("时段窗全受限：failureClassOf = llm-schedule-window；windowDescOf 带窗口描述") {
    assertEquals(LlmFailureCard.failureClassOf(scheduleError, "Unknown"), "llm-schedule-window")
    assertEquals(LlmFailureCard.windowDescOf(scheduleError), Some("06:00-01:00"))
    // 非时段窗错误：分类回退调用方值、窗口 None（零行为变化）。
    assertEquals(LlmFailureCard.failureClassOf(new RuntimeException("boom"), "ServerError"), "ServerError")
    assertEquals(LlmFailureCard.windowDescOf(new RuntimeException("boom")), None)
  }

  test("时段窗全受限：modelRefOf 取受限清单首个（provider id 不再丢成 unknown）") {
    assertEquals(LlmFailureCard.modelRefOf(scheduleError, None), "UTSC/glm-5.3-flash")
    assertEquals(LlmFailureCard.modelRefOf(scheduleError, Some("p/preferred")), "UTSC/glm-5.3-flash")
  }

  test("窗口受限卡：waitable / switchable 双 false；帧携带 windowDesc 与分类线值") {
    val f = LlmFailureCard.recordFailure(
      "s-8", "agent-w", "当前时段（06:00-01:00）全部候选模型受限", "llm-schedule-window", "root", "UTSC/glm-5.3-flash", "06:00-01:00"
    )
    assertEquals(f.hcursor.downField("waitable").as[Boolean].toOption, Some(false))
    assertEquals(f.hcursor.downField("switchable").as[Boolean].toOption, Some(false))
    assertEquals(f.hcursor.downField("failureClass").as[String].toOption, Some("llm-schedule-window"))
    assertEquals(f.hcursor.downField("windowDesc").as[String].toOption, Some("06:00-01:00"))
    LlmFailureCard.recordSuccess("s-8", "agent-w")
  }

  test("普通失败卡：windowDesc 空串缺省，waitable / switchable 仍双 true") {
    val f = LlmFailureCard.failureFrame("s-9", "agent-n", "x", "llm-auth")
    assertEquals(f.hcursor.downField("windowDesc").as[String].toOption, Some(""))
    assertEquals(f.hcursor.downField("waitable").as[Boolean].toOption, Some(true))
    assertEquals(f.hcursor.downField("switchable").as[Boolean].toOption, Some(true))
  }

end LlmFailureCardSpec
