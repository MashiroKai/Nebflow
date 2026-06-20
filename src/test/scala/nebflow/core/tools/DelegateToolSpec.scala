package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.tools.DelegateTool.MaxDepth
import munit.CatsEffectSuite

/**
 * Unit tests for DelegateTool — schema, summarize, depth checks, and
 * extractLastAssistantText logic.
 *
 * Integration with live ActorSystem is tested separately (requires full
 * SharedResources wiring, LLM, etc.).
 */
class DelegateToolSpec extends CatsEffectSuite:

  // --- Schema ---

  test("name is Delegate") {
    assertEquals(DelegateTool.name, "Delegate")
  }

  test("inputSchema has required fields prompt and description") {
    val props = DelegateTool.inputSchema("properties")
      .flatMap(_.asObject)
      .getOrElse(fail("properties should be an object"))
    assert(props.contains("prompt"), "schema should have prompt")
    assert(props.contains("description"), "schema should have description")
    assert(props.contains("agentName"), "schema should have agentName")

    val required = DelegateTool.inputSchema("required")
      .flatMap(_.asArray.map(_.map(_.asString.getOrElse(""))))
      .getOrElse(Nil)
    assert(required.contains("prompt"), "prompt should be required")
    assert(required.contains("description"), "description should be required")
  }

  // --- summarize ---

  test("summarize shows agent and description") {
    val input = JsonObject(
      "agentName" -> "Nebula".asJson,
      "description" -> "Research auth bug".asJson,
      "prompt" -> "...".asJson
    )
    assertEquals(DelegateTool.summarize(input), "Delegate(Nebula: Research auth bug)")
  }

  test("summarize defaults to Nebula when agentName absent") {
    val input = JsonObject(
      "description" -> "test task".asJson,
      "prompt" -> "...".asJson
    )
    assertEquals(DelegateTool.summarize(input), "Delegate(Nebula: test task)")
  }

  test("summarizeResult truncates long results") {
    val long = "x" * 300
    val result = DelegateTool.summarizeResult(JsonObject.empty, long)
    assertEquals(result.length, 200)
    assert(result.endsWith("..."))
  }

  test("summarizeResult keeps short results as-is") {
    val short = "All tests passed."
    assertEquals(DelegateTool.summarizeResult(JsonObject.empty, short), short)
  }

  // --- Depth limit ---

  test("MaxDepth is 5") {
    assertEquals(MaxDepth, 5)
  }

  // --- call: error cases (no ActorSystem needed) ---

  test("call returns error when prompt is empty") {
    val input = JsonObject("prompt" -> "".asJson, "description" -> "test".asJson)
    val ctx = ToolContext(projectRoot = "/tmp")
    val result = DelegateTool.call(input, ctx).unsafeRunSync()
    val err = result.swap.toOption.getOrElse(fail("expected Left"))
    assert(err.message.contains("prompt"))
  }

  test("call returns error when prompt is blank") {
    val input = JsonObject("prompt" -> "   ".asJson, "description" -> "test".asJson)
    val ctx = ToolContext(projectRoot = "/tmp")
    val result = DelegateTool.call(input, ctx).unsafeRunSync()
    val err = result.swap.toOption.getOrElse(fail("expected Left"))
    assert(err.message.contains("prompt"))
  }

  test("call returns error when depth >= MaxDepth") {
    val input = JsonObject("prompt" -> "do something".asJson, "description" -> "test".asJson)
    val ctx = ToolContext(projectRoot = "/tmp", depth = MaxDepth)
    val result = DelegateTool.call(input, ctx).unsafeRunSync()
    val err = result.swap.toOption.getOrElse(fail("expected Left"))
    assert(err.message.contains("depth"))
  }

  test("call returns error when ToolContext lacks actorSystem/resources") {
    val input = JsonObject("prompt" -> "do something".asJson, "description" -> "test".asJson)
    // depth=0 but no actorSystem, sharedResources, etc.
    val ctx = ToolContext(projectRoot = "/tmp", depth = 0)
    val result = DelegateTool.call(input, ctx).unsafeRunSync()
    val err = result.swap.toOption.getOrElse(fail("expected Left"))
    assert(err.message.contains("requires"))
  }

  // --- ToolReversibility integration ---

  test("Delegate is auto-approved (reversible)") {
    assert(DelegateTool.name == "Delegate")
    // Delegate is in the AlwaysReversible set in ToolReversibility
    assert(nebflow.core.ToolReversibility.isReversible("Delegate", JsonObject.empty))
  }

  // --- ToolRegistry integration ---

  test("Delegate is registered in ToolRegistry") {
    assert(ToolRegistry.TOOL_MAP.contains("Delegate"))
  }

end DelegateToolSpec
