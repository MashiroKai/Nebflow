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
end AgentStreamEventUsageSpec
