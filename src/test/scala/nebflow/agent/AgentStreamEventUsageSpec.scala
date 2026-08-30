package nebflow.agent

import io.circe.syntax.*
import munit.FunSuite

/**
 * Wire-format contract for AgentStreamEvent usage/model fields (2026-08-18):
 * the WS events the frontend consumes must carry outputTokens so the UI can
 * show output-token usage. Regression guard for the fix that added
 * outputTokens to Done/UsageUpdate (previously dropped at the event layer).
 */
class AgentStreamEventUsageSpec extends FunSuite:

  private def json(ev: AgentStreamEvent): io.circe.Json = ev.toJson("test-agent", false, Some("sid-1"))

  test("done event carries outputTokens when known") {
    val j = json(
      AgentStreamEvent.Done(
        model = Some("provider/model"),
        contextWindow = Some(1000000),
        inputTokens = Some(250000),
        compactThreshold = Some(0.8),
        outputTokens = Some(1440)
      )
    )
    assertEquals(j.hcursor.get[String]("type").toOption, Some("done"))
    assertEquals(j.hcursor.get[Int]("outputTokens").toOption, Some(1440))
    assertEquals(j.hcursor.get[Int]("inputTokens").toOption, Some(250000))
    assertEquals(j.hcursor.get[String]("model").toOption, Some("provider/model"))
  }

  test("done event omits outputTokens when unknown (backward compatible)") {
    val j = json(AgentStreamEvent.Done(None))
    assertEquals(j.hcursor.get[Int]("outputTokens").toOption, None)
    assertEquals(j.hcursor.get[Int]("inputTokens").toOption, None)
  }

  test("usageUpdate event carries outputTokens when known") {
    val j = json(
      AgentStreamEvent.UsageUpdate(
        inputTokens = 250000,
        contextWindow = 1000000,
        compactThreshold = 0.8,
        outputTokens = Some(1440)
      )
    )
    assertEquals(j.hcursor.get[String]("type").toOption, Some("usageUpdate"))
    assertEquals(j.hcursor.get[Int]("outputTokens").toOption, Some(1440))
    assertEquals(j.hcursor.get[Int]("inputTokens").toOption, Some(250000))
    assertEquals(j.hcursor.get[String]("sessionId").toOption, Some("sid-1"))
  }

  test("usageUpdate event omits outputTokens when unknown (backward compatible)") {
    val j = json(AgentStreamEvent.UsageUpdate(100, 1000000, 0.8))
    assertEquals(j.hcursor.get[Int]("outputTokens").toOption, None)
    assertEquals(j.hcursor.get[Int]("inputTokens").toOption, Some(100))
  }

  test("subagent done event uses agentDone type with agentId") {
    val j = AgentStreamEvent
      .Done(model = Some("m"), outputTokens = Some(5))
      .toJson("agent-x", true, Some("node-s"))
    assertEquals(j.hcursor.get[String]("type").toOption, Some("agentDone"))
    assertEquals(j.hcursor.get[String]("agentId").toOption, Some("agent-x"))
    assertEquals(j.hcursor.get[Int]("outputTokens").toOption, Some(5))
  }

  // --- 冻结状态事件契约（现象 2 补全 2026-08-30）---
  // 前端输入栏禁用状态机直接读事件携带的显式 frozen 布尔（而非推断事件类型）——
  // 事件语义与状态字段解耦，统一处理冻结中/解冻两条路径。

  test("frozen event carries explicit frozen boolean + resumeAt + reason") {
    val j = json(AgentStreamEvent.Frozen(resumeAtMillis = Some(1234567890L)))
    assertEquals(j.hcursor.get[String]("type").toOption, Some("frozen"))
    assertEquals(j.hcursor.get[Boolean]("frozen").toOption, Some(true))
    assertEquals(j.hcursor.get[Long]("resumeAt").toOption, Some(1234567890L))
    // reason 缺省='schedule'（默认参数），旧前端/旧后端双向兼容
    assertEquals(j.hcursor.get[String]("reason").toOption, Some("schedule"))
  }

  test("frozen event keeps error-family reason when set") {
    val j = json(AgentStreamEvent.Frozen(resumeAtMillis = None, reason = FreezeReason.ProviderDown, retryCount = 2))
    assertEquals(j.hcursor.get[Boolean]("frozen").toOption, Some(true))
    assertEquals(j.hcursor.get[String]("reason").toOption, Some("provider-down"))
    assertEquals(j.hcursor.get[Int]("retryCount").toOption, Some(2))
  }

  test("resumed event carries frozen:false and nextChangeAt when known") {
    val j = json(AgentStreamEvent.Resumed(nextChangeAt = Some(9876543210L)))
    assertEquals(j.hcursor.get[String]("type").toOption, Some("resumed"))
    assertEquals(j.hcursor.get[Boolean]("frozen").toOption, Some(false))
    assertEquals(j.hcursor.get[Long]("nextChangeAt").toOption, Some(9876543210L))
  }

  test("resumed event omits nextChangeAt when unknown (backward compatible)") {
    val j = json(AgentStreamEvent.Resumed())
    assertEquals(j.hcursor.get[String]("type").toOption, Some("resumed"))
    assertEquals(j.hcursor.get[Boolean]("frozen").toOption, Some(false))
    assertEquals(j.hcursor.get[Long]("nextChangeAt").toOption, None)
  }
end AgentStreamEventUsageSpec
