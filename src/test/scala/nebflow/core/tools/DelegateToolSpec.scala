package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.{AgentDef, AgentLibrary}
import nebflow.core.PathUtil

class DelegateToolSpec extends CatsEffectSuite:
  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-agent"
  PathUtil.setDataRoot(tempRoot)
  private val agentsDir = tempRoot / "agents"
  private val lib = AgentLibrary(agentsDir)

  private def reset(): IO[Unit] =
    IO.delay { if os.exists(tempRoot) then os.remove.all(tempRoot) } *>
      IO.delay { os.makeDir.all(agentsDir) }

  private def writeAgent(name: String, category: String = "standalone"): Unit =
    val dir = agentsDir / name
    os.makeDir.all(dir)
    os.write(
      dir / "agent.json",
      Json
        .obj(
          "name" -> name.asJson,
          "description" -> s"$name agent".asJson,
          "useWhen" -> "".asJson,
          "tools" -> Json.arr("*".asJson),
          "category" -> category.asJson
        )
        .noSpaces
    )
    os.write(dir / "system.md", s"You are $name.")

  end writeAgent

  // Build a minimal ToolContext with just agentLibrary set (no ActorSystem etc.)
  private def ctxWith(lib: Option[AgentLibrary] = Some(lib)): ToolContext =
    ToolContext(
      sessionId = Some("test"),
      sessionStore = None,
      agentDef = Some(AgentDef(name = "Caller", description = "", tools = List("*"), systemPrompt = "")),
      agentLibrary = lib,
      agentActorRef = None,
      actorSystem = None,
      sharedResources = None,
      depth = 0,
      messages = Nil,
      wsSend = None,
      projectRoot = ""
    )

  test("agent parameter with non-existent agent returns error"):
    for
      _ <- reset()
      input = JsonObject("prompt" -> "do work".asJson, "description" -> "task".asJson, "agent" -> "Ghost".asJson)
      result <- DelegateTool.call(input, ctxWith())
    yield
      assert(result.isLeft, "should return Left")
      assert(
        result.swap.toOption.get.message.contains("not found"),
        s"error should mention 'not found': ${result.swap.toOption.get.message}"
      )

  test("agent parameter with non-standalone agent returns error"):
    for
      _ <- reset()
      _ <- IO(writeAgent("TeamWorker", category = "team"))
      input = JsonObject("prompt" -> "do work".asJson, "description" -> "task".asJson, "agent" -> "TeamWorker".asJson)
      result <- DelegateTool.call(input, ctxWith())
    yield
      assert(result.isLeft, "should return Left")
      assert(
        result.swap.toOption.get.message.contains("not a standalone"),
        s"error should mention 'not a standalone': ${result.swap.toOption.get.message}"
      )

  test("agent parameter with standalone agent resolves (spawns target, not self)"):
    for
      _ <- reset()
      _ <- IO(writeAgent("Coder"))
      input = JsonObject(
        "prompt" -> "write code".asJson,
        "description" -> "code task".asJson,
        "agent" -> "Coder".asJson
      )
      result <- DelegateTool.call(input, ctxWith())
    yield
      // Without ActorSystem the tool returns Left "Delegate requires ActorSystem..."
      // — but only AFTER agent resolution succeeds. If agent resolution failed,
      // we'd get a "not found" / "not standalone" error instead.
      assert(result.isLeft, "should return Left (no ActorSystem)")
      assert(
        !result.swap.toOption.get.message.contains("not found"),
        "should not be a 'not found' error — agent resolved OK"
      )
      assert(
        !result.swap.toOption.get.message.contains("not a standalone"),
        "should not be a 'not standalone' error — agent resolved OK"
      )
      assert(
        result.swap.toOption.get.message.contains("ActorSystem"),
        s"should be the ActorSystem error (agent resolved, spawn failed): ${result.swap.toOption.get.message}"
      )

  test("no agent parameter is rejected — self-clone banned (#28)"):
    for
      _ <- reset()
      _ <- IO(writeAgent("Coder"))
      input = JsonObject("prompt" -> "do work".asJson, "description" -> "task".asJson)
      result <- DelegateTool.call(input, ctxWith())
    yield
      assert(result.isLeft, "must be rejected — nothing may spawn")
      val msg = result.swap.toOption.get.message
      assert(msg.contains("Missing required parameter: agent"), s"must name the missing agent param: $msg")
      assert(msg.contains("#28"), s"must cite issue #28: $msg")
      assert(msg.contains("Coder"), s"must list targetable standalone agents: $msg")

  test("no agent parameter rejected even without a library (#28)"):
    for
      _ <- reset()
      input = JsonObject("prompt" -> "do work".asJson, "description" -> "task".asJson)
      result <- DelegateTool.call(input, ctxWith(lib = None))
    yield
      assert(result.isLeft, "must be rejected before any library/spawn concern")
      assert(
        result.swap.toOption.get.message.contains("Missing required parameter: agent"),
        s"got: ${result.swap.toOption.get.message}"
      )

  test("agent='Nebula' is rejected — root orchestrator must never be spawned (#28)"):
    for
      _ <- reset()
      // category defaults to "standalone" exactly like the runtime Nebula def —
      // the category check alone must NOT let it through
      _ <- IO(writeAgent("Nebula"))
      _ <- IO(writeAgent("Coder"))
      input = JsonObject("prompt" -> "do work".asJson, "description" -> "task".asJson, "agent" -> "Nebula".asJson)
      result <- DelegateTool.call(input, ctxWith())
    yield
      assert(result.isLeft, "must be rejected — nothing may spawn")
      val msg = result.swap.toOption.get.message
      assert(msg.contains("must never be spawned"), s"must explain the #28 ban: $msg")
      assert(!msg.contains("ActorSystem"), s"rejection happens at resolve time, before spawn: $msg")

  test("agent naming the caller itself is rejected — self-clone via the back door (#28)"):
    for
      _ <- reset()
      _ <- IO(writeAgent("Caller")) // ctx.agentDef is named "Caller"
      input = JsonObject("prompt" -> "do work".asJson, "description" -> "task".asJson, "agent" -> "Caller".asJson)
      result <- DelegateTool.call(input, ctxWith())
    yield
      assert(result.isLeft, "must be rejected — nothing may spawn")
      val msg = result.swap.toOption.get.message
      assert(msg.contains("calling agent itself"), s"must identify the self-target: $msg")
      assert(!msg.contains("ActorSystem"), s"rejection happens at resolve time, before spawn: $msg")

  test("agent parameter but no agentLibrary returns 'No agent library' error"):
    for
      _ <- reset()
      input = JsonObject("prompt" -> "do work".asJson, "description" -> "task".asJson, "agent" -> "Coder".asJson)
      result <- DelegateTool.call(input, ctxWith(lib = None))
    yield
      assert(result.isLeft)
      assert(result.swap.toOption.get.message.contains("No agent library"), s"got: ${result.swap.toOption.get.message}")

  test("empty prompt returns error before agent resolution"):
    for
      _ <- reset()
      _ <- IO(writeAgent("Coder"))
      input = JsonObject("prompt" -> "".asJson, "description" -> "task".asJson, "agent" -> "Coder".asJson)
      result <- DelegateTool.call(input, ctxWith())
    yield
      assert(result.isLeft)
      assert(result.swap.toOption.get.message.contains("Missing required parameter: prompt"))

  test("summarize includes target agent when specified"):
    val input = JsonObject("description" -> "task".asJson, "agent" -> "Coder".asJson)
    val summary = DelegateTool.summarize(input)
    assert(summary.contains("Coder"), s"summarize should include target agent: $summary")

  test("summarize without agent does not include arrow"):
    val input = JsonObject("description" -> "task".asJson)
    val summary = DelegateTool.summarize(input)
    assert(!summary.contains("→"), s"summarize should not include arrow without agent: $summary")

  test("stray flow parameter is ignored — Delegate spawns sub-agents only (2026-09-06 retirement)"):
    // 2026-09-06 工具面裁撤批：FlowTrigger 已退役，R1 split 时代的「flow=
    // 引导拒绝」分支随之删除。传入 flow= 不再产生专用错误——它不是合法参数，
    // 静默忽略后按缺 agent 正常拒绝（自含目录清单）。
    for
      _ <- reset()
      input = JsonObject(
        "prompt" -> "do work".asJson,
        "description" -> "task".asJson,
        "flow" -> "code-review".asJson
      )
      result <- DelegateTool.call(input, ctxWith())
    yield
      assert(result.isLeft, "missing agent must still reject the call")
      val msg = result.swap.toOption.get.message
      assert(msg.contains("Missing required parameter: agent"), s"normal agent-missing path: $msg")
      assert(!msg.contains("FlowTrigger"), s"FlowTrigger guidance must be gone (retired): $msg")

  test("inputSchema no longer includes the flow parameter"):
    val props = DelegateTool.inputSchema("properties").flatMap(_.asObject)
    assert(props.isDefined, "properties should be an object")
    assert(!props.get.contains("flow"), "flow parameter must be gone from the schema")

  test("inputSchema includes agent parameter"):
    val schema = DelegateTool.inputSchema
    val props = schema("properties").flatMap(_.asObject)
    assert(props.isDefined, "properties should be an object")
    assert(props.get.contains("agent"), "schema should include 'agent' property")

  test("inputSchema requires agent (#28)"):
    val schema = DelegateTool.inputSchema
    val required = schema("required").flatMap(_.asArray).toList.flatten.flatMap(_.asString)
    assert(required.contains("agent"), s"agent must be a required parameter: $required")

  test("inputSchema no longer includes the fork parameter (#28)"):
    val props = DelegateTool.inputSchema("properties").flatMap(_.asObject)
    assert(props.isDefined, "properties should be an object")
    assert(!props.get.contains("fork"), "fork must be gone — it only applied to the banned self-clone path")

  test("inputSchema includes preset parameter (#291)"):
    val schema = DelegateTool.inputSchema
    val props = schema("properties").flatMap(_.asObject)
    assert(props.isDefined, "properties should be an object")
    assert(props.get.contains("preset"), "schema should include 'preset' property")

  test("preset parameter with unknown name returns self-describing error (#291)"):
    for
      _ <- reset()
      _ <- IO(writeAgent("Coder"))
      input = JsonObject(
        "prompt" -> "do work".asJson,
        "description" -> "task".asJson,
        "agent" -> "Coder".asJson,
        "preset" -> "Bogus".asJson
      )
      result <- DelegateTool.call(input, ctxWith())
    yield
      assert(result.isLeft, "unknown preset must fail")
      val msg = result.swap.toOption.get.message
      assert(msg.contains("'Bogus' not found"), s"error should mention the preset name: $msg")
      assert(msg.contains("Available presets:"), s"error should list available presets: $msg")

  test("preset parameter with existing name passes preset resolution (fails later on missing ActorSystem)"):
    // A valid preset resolves the def; with no ActorSystem the failure must be
    // about the missing actor system, NOT about the preset.
    for
      _ <- reset()
      _ <- IO(writeAgent("Coder"))
      _ <- IO.delay {
        os.write.over(
          tempRoot / "model-presets.json",
          """{"defaultPreset":"general","presets":{"general":{"name":"general","description":"","preferred":"mock/mock-model","fallbacks":[]}}}"""
        )
      }
      input = JsonObject(
        "prompt" -> "do work".asJson,
        "description" -> "task".asJson,
        "agent" -> "Coder".asJson,
        "preset" -> "general".asJson
      )
      result <- DelegateTool.call(input, ctxWith())
    yield
      assert(result.isLeft, "no ActorSystem → Left")
      val msg = result.swap.toOption.get.message
      assert(msg.contains("ActorSystem"), s"valid preset should pass; failure is about ActorSystem: $msg")
      assert(!msg.contains("not found"), s"valid preset must not be reported missing: $msg")
end DelegateToolSpec
