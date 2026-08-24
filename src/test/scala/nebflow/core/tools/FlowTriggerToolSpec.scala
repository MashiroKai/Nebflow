package nebflow.core.tools

import cats.effect.IO
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.AgentDef
import nebflow.core.entity.{FlowDagDef, FlowParamSpec, FlowNode, NodeRoute}

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

  test("inputSchema has only flow, prompt and params parameters"):
    val props = FlowTriggerTool.inputSchema("properties").flatMap(_.asObject)
    assert(props.isDefined)
    assert(props.get.contains("flow"))
    assert(props.get.contains("prompt"))
    assert(props.get.contains("params"))
    assertEquals(props.get.size, 3, s"FlowTrigger schema must be minimal: ${props.get.keys.toList}")

  // ---- params validation (validateFlowParams is the gate before RunFlow) ----

  private val declaredParams: Map[String, FlowParamSpec] = Map(
    "fanout" -> FlowParamSpec(`type` = "int", default = Some(Json.fromInt(2)), min = Some(1), max = Some(4)),
    "topic" -> FlowParamSpec(`type` = "string"),
    "deep" -> FlowParamSpec(`type` = "bool", default = Some(Json.fromBoolean(false)))
  )

  test("params: unknown key is rejected (typo guard)"):
    val result = FlowTriggerTool.validateFlowParams(declaredParams, JsonObject("fnaout" -> Json.fromInt(3).asJson))
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("unknown parameter 'fnaout'"), s"${result.swap.toOption.get}")
    assert(result.swap.toOption.get.contains("declared"), s"lists declared keys: ${result.swap.toOption.get}")

  test("params: wrong type is rejected (fanout:\"abc\")"):
    val result = FlowTriggerTool.validateFlowParams(declaredParams, JsonObject("fanout" -> Json.fromString("abc").asJson))
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("must be int"), s"${result.swap.toOption.get}")

  test("params: out of range is rejected (fanout:99 > max 4)"):
    val result = FlowTriggerTool.validateFlowParams(declaredParams, JsonObject("fanout" -> Json.fromInt(99).asJson))
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("out of range [1, 4]"), s"${result.swap.toOption.get}")

  test("params: min-only and max-only bounds enforced"):
    val minOnly = FlowTriggerTool.validateFlowParams(
      Map("n" -> FlowParamSpec(`type` = "int", min = Some(5))),
      JsonObject("n" -> Json.fromInt(3).asJson)
    )
    assert(minOnly.isLeft && minOnly.swap.toOption.get.contains(">= 5"), s"$minOnly")
    val maxOnly = FlowTriggerTool.validateFlowParams(
      Map("n" -> FlowParamSpec(`type` = "int", max = Some(5))),
      JsonObject("n" -> Json.fromInt(9).asJson)
    )
    assert(maxOnly.isLeft && maxOnly.swap.toOption.get.contains("<= 5"), s"$maxOnly")

  test("params: defaults fill missing keys, provided values win"):
    val result = FlowTriggerTool.validateFlowParams(
      declaredParams,
      JsonObject("fanout" -> Json.fromInt(3).asJson)
    )
    assert(result.isRight, s"$result")
    val m = result.toOption.get
    assertEquals(m("fanout"), Json.fromInt(3), "provided wins over default")
    assertEquals(m("deep"), Json.fromBoolean(false), "default applied for missing key")
    assert(!m.contains("topic"), "param without default and not provided stays absent")

  test("params: valid full set passes through"):
    val result = FlowTriggerTool.validateFlowParams(
      declaredParams,
      JsonObject(
        "fanout" -> Json.fromInt(2).asJson,
        "topic" -> Json.fromString("CZT noise").asJson,
        "deep" -> Json.fromBoolean(true).asJson
      )
    )
    assert(result.isRight, s"$result")
    assertEquals(result.toOption.get.get("topic"), Some(Json.fromString("CZT noise")))

  test("params: flow declaring no params rejects any provided key"):
    val result = FlowTriggerTool.validateFlowParams(Map.empty, JsonObject("fanout" -> Json.fromInt(2).asJson))
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("declares no params"), s"${result.swap.toOption.get}")

  test("FlowDagDef params decode round-trips and rejects unknown types"):
    import io.circe.parser.decode
    val json =
      """{"name":"t","description":"d","entry":"e","nodes":{"e":{"agent":"a","input":"$params.fanout","onComplete":"$return"}},
         |"params":{"fanout":{"type":"int","default":2,"min":1,"max":4,"description":"并行路数"}}}""".stripMargin
    val decoded = decode[FlowDagDef](json)
    assert(decoded.isRight, s"$decoded")
    val fanout = decoded.toOption.get.params("fanout")
    assertEquals(fanout.`type`, "int")
    assertEquals(fanout.default, Some(Json.fromInt(2)))
    assertEquals(fanout.min, Some(1))
    assertEquals(fanout.max, Some(4))
    // round-trip
    val redecoded = decode[FlowDagDef](decoded.toOption.get.asJson.noSpaces)
    assertEquals(redecoded, decoded)
    // unknown type rejected at parse
    val badType = decode[FlowDagDef](
      """{"name":"t","description":"d","entry":"e","nodes":{"e":{"agent":"a","input":"x","onComplete":"$return"}},
         |"params":{"fanout":{"type":"float"}}}""".stripMargin
    )
    assert(badType.isLeft, s"unknown param type must fail parse: $badType")
    assert(badType.swap.toOption.get.getMessage.contains("param type must be one of"), s"${badType.swap.toOption.get}")
    // min/max on non-int rejected
    val badMin = decode[FlowDagDef](
      """{"name":"t","description":"d","entry":"e","nodes":{"e":{"agent":"a","input":"x","onComplete":"$return"}},
         |"params":{"topic":{"type":"string","min":1}}}""".stripMargin
    )
    assert(badMin.isLeft, s"min on string param must fail parse: $badMin")
end FlowTriggerToolSpec
