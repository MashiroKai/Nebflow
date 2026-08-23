package nebflow.llm.providers

import cats.effect.IO
import io.circe.Json
import munit.FunSuite
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend

/**
 * Anthropic adapter tool_result/tool_use pairing defense (2026-08-23 deepseek
 * 422 loop). An orphaned user(tool_result) — whose assistant(tool_use) was
 * truncated away by context cleanup — must be dropped/replaced at the protocol
 * boundary, or the provider rejects the whole request ("Each tool_result block
 * must have a corresponding tool_use block in the previous message") and the
 * agent loops retry→DOWN→recovered forever.
 */
class AnthropicPairingDefenseSpec extends FunSuite:

  private def adapter: AnthropicAdapter =
    AnthropicAdapter(
      "http://localhost:0",
      "k",
      null.asInstanceOf[StreamBackend[IO, Fs2Streams[IO]]],
      requireThinkingPassback = true
    )

  private def messages(msgs: List[Message]): List[Json] =
    adapter.toAnthropicMessages(msgs)

  private def allText(jsons: List[Json]): String =
    jsons.flatMap(_.hcursor.downField("content").as[List[Json]].getOrElse(Nil))
      .flatMap(_.hcursor.downField("text").as[String].toOption)
      .mkString(" ")

  private def toolResultIds(jsons: List[Json]): List[String] =
    jsons.flatMap(_.hcursor.downField("content").as[List[Json]].getOrElse(Nil))
      .flatMap { block =>
        block.hcursor.downField("type").as[String].toOption match
          case Some("tool_result") => block.hcursor.downField("tool_use_id").as[String].toOption
          case _                   => None
      }

  test("orphaned head tool_result (no preceding tool_use) is replaced, not sent") {
    // The exact Manager-session shape: history STARTS with a user(tool_result)
    // whose tool_use id is NOT present anywhere earlier (cleanup truncated the
    // assistant message away) — deepseek rejected this with 422.
    val history = List(
      Message(
        MessageRole.User,
        Right(List(ContentBlock.ToolResult("call_orphan_1", "[Output removed to free context space]", Some(false))))
      ),
      Message(
        MessageRole.Assistant,
        Right(
          List(
            ContentBlock.Thinking("think", None),
            ContentBlock.ToolUse("call_valid_1", "Edit", io.circe.JsonObject.empty)
          )
        )
      ),
      Message(
        MessageRole.User,
        Right(List(ContentBlock.ToolResult("call_valid_1", "edit ok", Some(false))))
      )
    )
    val out = messages(history)
    val ids = toolResultIds(out)
    assertEquals(
      clue(ids), List("call_valid_1"),
      s"only paired tool_result must survive; orphan call_orphan_1 leaked: $out"
    )
    // The orphan is replaced by an explanatory text, not silently deleted —
    // the model still sees why the result is gone.
    assert(
      clue(allText(out)).contains("orphaned tool_result"),
      "orphan replacement text must be present"
    )
  }

  test("paired tool_result immediately after its tool_use is untouched") {
    val history = List(
      Message(MessageRole.User, Left("do the edit")),
      Message(
        MessageRole.Assistant,
        Right(List(ContentBlock.ToolUse("call_a", "Edit", io.circe.JsonObject.empty)))
      ),
      Message(
        MessageRole.User,
        Right(List(ContentBlock.ToolResult("call_a", "edited ok", Some(false))))
      ),
      Message(
        MessageRole.Assistant,
        Right(List(ContentBlock.ToolUse("call_b", "Write", io.circe.JsonObject.empty)))
      ),
      Message(
        MessageRole.User,
        Right(List(ContentBlock.ToolResult("call_b", "wrote ok", Some(false))))
      )
    )
    val out = messages(history)
    assertEquals(clue(toolResultIds(out)), List("call_a", "call_b"), s"both results must survive: $out")
  }

  test("multiple tool_results in one message keep only the paired ones") {
    val history = List(
      Message(MessageRole.User, Left("run tools")),
      Message(
        MessageRole.Assistant,
        Right(
          List(
            ContentBlock.ToolUse("call_x", "Read", io.circe.JsonObject.empty),
            ContentBlock.ToolUse("call_y", "Grep", io.circe.JsonObject.empty)
          )
        )
      ),
      // call_x is paired; call_ghost's tool_use was truncated away
      Message(
        MessageRole.User,
        Right(
          List(
            ContentBlock.ToolResult("call_x", "read output", Some(false)),
            ContentBlock.ToolResult("call_ghost", "dangling", Some(false))
          )
        )
      )
    )
    val out = messages(history)
    assertEquals(clue(toolResultIds(out)), List("call_x"), s"ghost result must be dropped: $out")
  }

  test("text-only history and assistant-head history pass through unchanged") {
    val history = List(
      Message(MessageRole.User, Left("hello")),
      Message(MessageRole.Assistant, Left("hi there"))
    )
    val out = messages(history)
    val flat = out.mkString
    assert(clue(flat).contains("hello") && clue(flat).contains("hi there"), s"both messages survive: $out")
  }

end AnthropicPairingDefenseSpec
