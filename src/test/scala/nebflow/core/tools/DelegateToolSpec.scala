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
    os.write(dir / "agent.json", Json.obj(
      "name" -> name.asJson,
      "description" -> s"$name agent".asJson,
      "useWhen" -> "".asJson,
      "tools" -> Json.arr("*".asJson),
      "category" -> category.asJson
    ).noSpaces)
    os.write(dir / "system.md", s"You are $name.")

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
      assert(result.swap.toOption.get.message.contains("not found"), s"error should mention 'not found': ${result.swap.toOption.get.message}")

  test("agent parameter with non-standalone agent returns error"):
    for
      _ <- reset()
      _ <- IO(writeAgent("TeamWorker", category = "team"))
      input = JsonObject("prompt" -> "do work".asJson, "description" -> "task".asJson, "agent" -> "TeamWorker".asJson)
      result <- DelegateTool.call(input, ctxWith())
    yield
      assert(result.isLeft, "should return Left")
      assert(result.swap.toOption.get.message.contains("not a standalone"), s"error should mention 'not a standalone': ${result.swap.toOption.get.message}")

  test("agent parameter with standalone agent resolves (spawns target, not self)"):
    for
      _ <- reset()
      _ <- IO(writeAgent("Coder"))
      input = JsonObject("prompt" -> "write code".asJson, "description" -> "code task".asJson, "agent" -> "Coder".asJson)
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

  test("no agent parameter falls back to self-clone (no agentLibrary lookup)"):
    for
      _ <- reset()
      input = JsonObject("prompt" -> "do work".asJson, "description" -> "task".asJson)
      // No agentLibrary at all — self-clone path doesn't need it
      result <- DelegateTool.call(input, ctxWith(lib = None))
    yield
      assert(result.isLeft, "should return Left (no ActorSystem)")
      assert(
        result.swap.toOption.get.message.contains("ActorSystem"),
        s"should be the ActorSystem error (self-clone path): ${result.swap.toOption.get.message}"
      )

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

  test("inputSchema includes agent parameter"):
    val schema = DelegateTool.inputSchema
    val props = schema("properties").flatMap(_.asObject)
    assert(props.isDefined, "properties should be an object")
    assert(props.get.contains("agent"), "schema should include 'agent' property")
end DelegateToolSpec
