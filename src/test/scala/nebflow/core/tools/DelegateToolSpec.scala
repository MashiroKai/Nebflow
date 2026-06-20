package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import io.circe.parser.parse
import nebflow.core.tools.DelegateTool.MaxDepth
import nebflow.llm.ModelConfig
import nebflow.shared.{Message, MessageRole}
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
    val props = DelegateTool
      .inputSchema("properties")
      .flatMap(_.asObject)
      .getOrElse(fail("properties should be an object"))
    assert(props.contains("prompt"), "schema should have prompt")
    assert(props.contains("description"), "schema should have description")
    assert(props.contains("agentName"), "schema should have agentName")
    assert(props.contains("model"), "schema should have model")
    assert(props.contains("fork"), "schema should have fork")

    val required = DelegateTool
      .inputSchema("required")
      .flatMap(_.asArray.map(_.map(_.asString.getOrElse(""))))
      .getOrElse(Nil)
    assert(required.contains("prompt"), "prompt should be required")
    assert(required.contains("description"), "description should be required")
    // model and fork should NOT be required
    assert(!required.contains("model"), "model should be optional")
    assert(!required.contains("fork"), "fork should be optional")
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

  test("summarize shows model when specified") {
    val input = JsonObject(
      "description" -> "test task".asJson,
      "prompt" -> "...".asJson,
      "model" -> "zhipu/GLM-5.2".asJson
    )
    assertEquals(DelegateTool.summarize(input), "Delegate(Nebula: test task [model=zhipu/GLM-5.2])")
  }

  test("summarize shows fork when true") {
    val input = JsonObject(
      "description" -> "test task".asJson,
      "prompt" -> "...".asJson,
      "fork" -> true.asJson
    )
    assertEquals(DelegateTool.summarize(input), "Delegate(Nebula: test task [fork])")
  }

  test("summarize shows both model and fork") {
    val input = JsonObject(
      "description" -> "test task".asJson,
      "prompt" -> "...".asJson,
      "model" -> "USTC/glm-5.2".asJson,
      "fork" -> true.asJson
    )
    assertEquals(DelegateTool.summarize(input), "Delegate(Nebula: test task [model=USTC/glm-5.2, fork])")
  }

  test("summarize does not show fork when false or absent") {
    val input1 = JsonObject(
      "description" -> "task".asJson,
      "prompt" -> "...".asJson,
      "fork" -> false.asJson
    )
    assertEquals(DelegateTool.summarize(input1), "Delegate(Nebula: task)")

    val input2 = JsonObject(
      "description" -> "task".asJson,
      "prompt" -> "...".asJson
    )
    assertEquals(DelegateTool.summarize(input2), "Delegate(Nebula: task)")
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

  test("call with model param still fails gracefully without resources") {
    val input = JsonObject(
      "prompt" -> "do something".asJson,
      "description" -> "test".asJson,
      "model" -> "zhipu/GLM-5.2".asJson
    )
    val ctx = ToolContext(projectRoot = "/tmp", depth = 0)
    val result = DelegateTool.call(input, ctx).unsafeRunSync()
    assert(result.isLeft, "should fail without ActorSystem/resources even with model param")
  }

  test("call with fork param still fails gracefully without resources") {
    val input = JsonObject(
      "prompt" -> "do something".asJson,
      "description" -> "test".asJson,
      "fork" -> true.asJson
    )
    val ctx = ToolContext(projectRoot = "/tmp", depth = 0)
    val result = DelegateTool.call(input, ctx).unsafeRunSync()
    assert(result.isLeft, "should fail without ActorSystem/resources even with fork=true")
  }

  test("call with both model and fork params fails gracefully without resources") {
    val input = JsonObject(
      "prompt" -> "do something".asJson,
      "description" -> "test".asJson,
      "model" -> "zhipu/GLM-5.2".asJson,
      "fork" -> true.asJson
    )
    val ctx = ToolContext(projectRoot = "/tmp", depth = 0)
    val result = DelegateTool.call(input, ctx).unsafeRunSync()
    assert(result.isLeft)
  }

  // --- ModelConfig description ---

  test("ModelConfig decodes with description field") {
    val jsonStr =
      """{"id":"GLM-5.2","maxTokens":32000,"contextWindow":200000,"description":"Main model for complex tasks"}"""
    val result = parse(jsonStr).flatMap(_.as[ModelConfig])
    assert(result.isRight, s"should decode: $result")
    val mc = result.toOption.get
    assertEquals(mc.id, "GLM-5.2")
    assertEquals(mc.description, Some("Main model for complex tasks"))
  }

  test("ModelConfig decodes without description field (defaults to None)") {
    val jsonStr = """{"id":"GLM-5.2","maxTokens":32000,"contextWindow":200000}"""
    val result = parse(jsonStr).flatMap(_.as[ModelConfig])
    assert(result.isRight, s"should decode: $result")
    val mc = result.toOption.get
    assertEquals(mc.id, "GLM-5.2")
    assertEquals(mc.description, None)
  }

  test("ModelConfig decodes with null description (defaults to None)") {
    val jsonStr = """{"id":"test","maxTokens":32000,"contextWindow":200000,"description":null}"""
    val result = parse(jsonStr).flatMap(_.as[ModelConfig])
    assert(result.isRight, s"should decode: $result")
    val mc = result.toOption.get
    assertEquals(mc.description, None)
  }

  // --- ToolContext messages field ---

  test("ToolContext defaults messages to empty list") {
    val ctx = ToolContext(projectRoot = "/tmp")
    assertEquals(ctx.messages, Nil)
  }

  test("ToolContext accepts messages for fork support") {
    val msgs = List(
      Message(MessageRole.User, Left("hello")),
      Message(MessageRole.Assistant, Left("hi there"))
    )
    val ctx = ToolContext(projectRoot = "/tmp", messages = msgs)
    assertEquals(ctx.messages.length, 2)
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
