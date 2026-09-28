package nebflow.core.tools

import cats.effect.IO
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.AgentDef

/**
 * SubTaskTool — #28 structural defense: the root orchestrator (Nebula) must
 * never self-clone, even if a custom agent.json lists SubTask in its tools.
 * (Nebula's normal toolset has no SubTask; this guards the config edge case.)
 */
class SubTaskToolSpec extends CatsEffectSuite:

  private def ctxWith(caller: String): ToolContext =
    ToolContext(
      sessionId = Some("test"),
      sessionStore = None,
      agentDef = Some(AgentDef(name = caller, description = "", tools = List("*"), systemPrompt = "")),
      agentLibrary = None,
      agentActorRef = None,
      actorSystem = None,
      sharedResources = None,
      depth = 0,
      messages = Nil,
      wsSend = None,
      projectRoot = ""
    )

  private val input = JsonObject(
    "prompt" -> "do work".asJson,
    "description" -> "task".asJson
  )

  test("caller=Nebula is rejected — root must never be self-cloned (#28)"):
    val result = SubTaskTool.call(input, ctxWith("Nebula")).unsafeRunSync()
    assert(result.isLeft, "must be rejected — nothing may spawn")
    val msg = result.swap.toOption.get.message
    assert(msg.contains("#28"), s"must cite issue #28: $msg")
    assert(msg.contains("Delegate"), s"must point to Delegate as the legal path: $msg")
    assert(!msg.contains("ActorSystem"), s"rejection happens before spawn concerns: $msg")

  test("regular team-member caller proceeds past the Nebula guard (#28)"):
    // A non-Nebula caller must reach the normal failure points (missing
    // ActorSystem), proving the guard does not over-reject.
    val result = SubTaskTool.call(input, ctxWith("backend")).unsafeRunSync()
    assert(result.isLeft, "no ActorSystem → Left")
    assert(
      result.swap.toOption.get.message.contains("ActorSystem"),
      s"should be the ActorSystem error, not the #28 guard: ${result.swap.toOption.get.message}"
    )

end SubTaskToolSpec
