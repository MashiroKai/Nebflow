package nebflow.agent

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.tools.{FlowExecuteTool, ToolContext, ToolError}
import cats.effect.unsafe.implicits.global

/**
 * #406 FlowExecuteTool validation unit tests.
 *
 * Covers the pure validation surface: required params, depth limit, DAG
 * structure validation (FlowStructure.validate), and agent-existence
 * validation (EntityLoader.validateFlow). The spawn path (real resources /
 * actor system) is exercised by the isolated-instance E2E smoke instead —
 * the tool falls through to the "missing resources" branch here, which
 * proves validation passed before reaching the spawn gate.
 */
class FlowExecuteToolSpec extends FunSuite:

  private val noCtx = ToolContext(projectRoot = "/tmp")

  private def call(input: Json, ctx: ToolContext = noCtx): Either[ToolError, String] =
    FlowExecuteTool
      .call(input.asObject.getOrElse(io.circe.JsonObject.empty), ctx)
      .unsafeRunSync()

  /** Minimal valid DAG: planner → worker → $return. Node agents reference a
    * real global agent ("Nebula" — the unit-test environment resolves the
    * live agent library; a nonexistent name is covered by the unknown-agent
    * test below). */
  private def validDag: Json = Json.obj(
    "prompt" -> "summarize two topics".asJson,
    "name" -> "t2".asJson,
    "description" -> "two-topic smoke".asJson,
    "entry" -> "planner".asJson,
    "nodes" -> Json.obj(
      "planner" -> Json.obj(
        "agent" -> "Nebula".asJson,
        "input" -> "$task".asJson,
        "onComplete" -> "worker".asJson
      ),
      "worker" -> Json.obj(
        "agent" -> "Nebula".asJson,
        "input" -> "$planner.output".asJson,
        "onComplete" -> "$return".asJson
      )
    )
  )

  test("missing prompt is rejected"):
    val input = Json.fromJsonObject(validDag.asObject.get.remove("prompt"))
    val res = call(input)
    assert(res.isLeft)
    assertEquals(res.swap.toOption.get.message, "Missing required parameter: prompt")

  test("missing entry is rejected"):
    val input = Json.fromJsonObject(validDag.asObject.get.remove("entry"))
    val res = call(input)
    assert(res.isLeft)
    assertEquals(res.swap.toOption.get.message, "Missing required parameter: entry")

  test("depth limit rejects nested flow execution"):
    val ctx = noCtx.copy(depth = FlowExecuteTool.MaxDepth)
    val res = call(validDag, ctx)
    assert(res.isLeft)
    assert(res.swap.toOption.get.message.contains("Maximum flow depth"))

  test("invalid DAG JSON (unparseable node) is rejected with decode error"):
    val bad = Json.obj(
      "prompt" -> "x".asJson,
      "name" -> "bad".asJson,
      "description" -> "d".asJson,
      "entry" -> "a".asJson,
      "nodes" -> Json.obj(
        "a" -> Json.obj("agent" -> 42.asJson, "input" -> "$task".asJson, "onComplete" -> "$return".asJson)
      )
    )
    val res = call(bad)
    assert(res.isLeft)
    assert(res.swap.toOption.get.message.startsWith("Invalid flow definition"))

  test("structural validation rejects single-node flow (needs ≥2 nodes)"):
    val single = Json.obj(
      "prompt" -> "x".asJson,
      "name" -> "one".asJson,
      "description" -> "d".asJson,
      "entry" -> "a".asJson,
      "nodes" -> Json.obj(
        "a" -> Json.obj("agent" -> "Nebula".asJson, "input" -> "$task".asJson, "onComplete" -> "$return".asJson)
      )
    )
    val res = call(single)
    assert(res.isLeft)
    assert(res.swap.toOption.get.message.contains("at least 2 nodes"), res.swap.toOption.get.message)

  test("structural validation rejects flow with no termination path"):
    val noReturn = Json.obj(
      "prompt" -> "x".asJson,
      "name" -> "cycle".asJson,
      "description" -> "d".asJson,
      "entry" -> "a".asJson,
      "nodes" -> Json.obj(
        "a" -> Json.obj("agent" -> "Nebula".asJson, "input" -> "$task".asJson, "onComplete" -> "b".asJson),
        "b" -> Json.obj("agent" -> "Nebula".asJson, "input" -> "$a.output".asJson, "onComplete" -> "a".asJson)
      )
    )
    val res = call(noReturn)
    assert(res.isLeft)
    assert(res.swap.toOption.get.message.contains("no termination path"), res.swap.toOption.get.message)

  test("unknown node agent is rejected with the agent name in the error"):
    val unknownAgent = Json.obj(
      "prompt" -> "x".asJson,
      "name" -> "ghost".asJson,
      "description" -> "d".asJson,
      "entry" -> "a".asJson,
      "nodes" -> Json.obj(
        "a" -> Json.obj("agent" -> "definitely-not-an-agent-xyz".asJson, "input" -> "$task".asJson, "onComplete" -> "b".asJson),
        "b" -> Json.obj("agent" -> "definitely-not-an-agent-xyz".asJson, "input" -> "$a.output".asJson, "onComplete" -> "$return".asJson)
      )
    )
    val res = call(unknownAgent)
    assert(res.isLeft)
    assert(res.swap.toOption.get.message.contains("'definitely-not-an-agent-xyz' not found"), res.swap.toOption.get.message)

  test("valid DAG passes validation and reaches the spawn gate (missing resources here)"):
    val res = call(validDag)
    assert(res.isLeft, "no resources in unit context — expected spawn-gate rejection")
    assertEquals(res.swap.toOption.get.message, "Cannot start flow: missing resources")

  // ── #424 compile-time rejection (0 spawn / 0 token) ────────────────────
  // A rejected DAG returns the [E-<code>] error synchronously from the tool
  // call — it never reaches the spawn gate ("Cannot start flow") and no
  // runner/node is created (the E2E smoke asserts 0 spawn / 0 flowStarted).

  test("E-101: static node with {{item}} is rejected at submit time (v4 join accident)"):
    val bad = Json.obj(
      "prompt" -> "x".asJson,
      "name" -> "bad101".asJson,
      "description" -> "d".asJson,
      "entry" -> "a".asJson,
      "nodes" -> Json.obj(
        "a" -> Json.obj("agent" -> "Nebula".asJson, "input" -> "{{item}}".asJson, "onComplete" -> "$return".asJson)
      )
    )
    val res = call(bad)
    assert(res.isLeft)
    assert(res.swap.toOption.get.message.contains("[E-101]"), res.swap.toOption.get.message)
    assert(!res.swap.toOption.get.message.contains("Cannot start flow"), "must not reach the spawn gate")

  test("E-201: undeclared slots reference is rejected at submit time (redo accident)"):
    val bad = Json.obj(
      "prompt" -> "x".asJson,
      "name" -> "bad201".asJson,
      "description" -> "d".asJson,
      "entry" -> "qa".asJson,
      "nodes" -> Json.obj(
        "qa" -> Json.obj("agent" -> "Nebula".asJson, "input" -> "$task".asJson, "onComplete" -> "judge".asJson),
        "judge" -> Json.obj("agent" -> "Nebula".asJson, "input" -> "$task".asJson, "onComplete" -> "redo".asJson),
        "redo" -> Json.obj("agent" -> "Nebula".asJson, "input" -> "$judge.slots.failBlocks".asJson, "onComplete" -> "$return".asJson)
      )
    )
    val res = call(bad)
    assert(res.isLeft)
    assert(res.swap.toOption.get.message.contains("[E-201]"), res.swap.toOption.get.message)
    assert(!res.swap.toOption.get.message.contains("Cannot start flow"), "must not reach the spawn gate")

  test("E-017: missing name is rejected at submit time (D4 — no silent 'dynamic-flow')"):
    val noName = Json.fromJsonObject(validDag.asObject.get.remove("name"))
    val res = call(noName)
    assert(res.isLeft)
    assert(res.swap.toOption.get.message.contains("[E-017]"), res.swap.toOption.get.message)
    assert(!res.swap.toOption.get.message.contains("Cannot start flow"), "must not reach the spawn gate")

end FlowExecuteToolSpec
