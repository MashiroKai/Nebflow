package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.AgentDef

/**
 * FlowTriggerTool — R1 split from DelegateTool. The whitelist gate is the
 * first check after parameter validation, so these tests run without an
 * actor system / shared resources: passing the gate surfaces the
 * "missing resources" error instead of the whitelist error.
 */
class FlowTriggerToolSpec extends CatsEffectSuite:

  private def ctxWith(flows: List[String], name: String = "Caller"): ToolContext =
    ToolContext(
      sessionId = Some("test"),
      sessionStore = None,
      agentDef = Some(
        AgentDef(name = name, description = "", tools = List("*"), systemPrompt = "", flows = flows)
      ),
      agentLibrary = None,
      agentActorRef = None,
      actorSystem = None,
      sharedResources = None,
      depth = 0,
      messages = Nil,
      wsSend = None,
      projectRoot = ""
    )

  private def flowInput(flow: String, prompt: String = "do the thing"): JsonObject =
    JsonObject("flow" -> flow.asJson, "prompt" -> prompt.asJson)

  test("missing flow parameter returns error"):
    FlowTriggerTool
      .call(JsonObject("prompt" -> "x".asJson), ctxWith(List("code-review")))
      .map { result =>
        assert(result.isLeft)
        assert(result.swap.toOption.get.message.contains("Missing required parameter: flow"))
      }

  test("missing prompt parameter returns error"):
    FlowTriggerTool
      .call(JsonObject("flow" -> "code-review".asJson), ctxWith(List("code-review")))
      .map { result =>
        assert(result.isLeft)
        assert(result.swap.toOption.get.message.contains("Missing required parameter: prompt"))
      }

  test("agent without declared flows is rejected with empty allowed list"):
    FlowTriggerTool.call(flowInput("code-review"), ctxWith(Nil)).map { result =>
      assert(result.isLeft, "must be rejected")
      val msg = result.swap.toOption.get.message
      assert(msg.contains("not allowed"), s"should cite the whitelist rejection: $msg")
      assert(msg.contains("(none — no flows declared)"), s"should list the empty whitelist: $msg")
    }

  test("undeclared flow is rejected listing the declared flows"):
    FlowTriggerTool
      .call(flowInput("unknown-flow"), ctxWith(List("code-review", "release-beta")))
      .map { result =>
        assert(result.isLeft)
        val msg = result.swap.toOption.get.message
        assert(msg.contains("not allowed"), s"$msg")
        assert(
          msg.contains("Allowed: code-review, release-beta"),
          s"should list declared flows: $msg"
        )
      }

  test("declared flow passes the whitelist gate (fails later on missing resources)"):
    FlowTriggerTool
      .call(flowInput("code-review"), ctxWith(List("code-review")))
      .map { result =>
        assert(result.isLeft, "no actor system in test context — must be the resources error")
        val msg = result.swap.toOption.get.message
        assert(msg.contains("missing resources"), s"whitelist passed, failure must be resources: $msg")
        assert(!msg.contains("not allowed"), s"must not be a whitelist rejection: $msg")
      }

  test("wildcard flows=['*'] passes the gate for any named flow"):
    FlowTriggerTool
      .call(flowInput("nebflow-review-merge"), ctxWith(List("*")))
      .map { result =>
        // Gate must pass; failure (if any) must be the missing-resources one,
        // never a whitelist rejection. '*' used to be matched literally,
        // blocking every named flow for agents configured with the wildcard.
        val msgOpt = result.swap.toOption.map(_.message)
        msgOpt.foreach { msg =>
          assert(!msg.contains("not allowed"), s"'*' must match any named flow: $msg")
        }
      }

  test("specific flows list still rejects non-declared flow even with wildcard elsewhere in ecosystem"):
    FlowTriggerTool
      .call(flowInput("some-other-flow"), ctxWith(List("code-review")))
      .map { result =>
        assert(result.isLeft, "must be rejected")
        val msg = result.swap.toOption.get.message
        assert(msg.contains("not allowed"), s"$msg")
      }

  test("summarize shows the flow target"):
    val summary = FlowTriggerTool.summarize(flowInput("code-review"))
    assert(summary.contains("flow:code-review"), s"summary: $summary")

  test("inputSchema has only flow and prompt parameters"):
    val props = FlowTriggerTool.inputSchema("properties").flatMap(_.asObject)
    assert(props.isDefined)
    assert(props.get.contains("flow"))
    assert(props.get.contains("prompt"))
    assertEquals(props.get.size, 2, s"FlowTrigger schema must be minimal: ${props.get.keys.toList}")
end FlowTriggerToolSpec
