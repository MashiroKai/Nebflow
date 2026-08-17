package nebflow.llm.providers

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.FunSuite
import nebflow.llm.SendMessageParams
import nebflow.shared.StreamChunk

/**
 * Issue #18 integration (Anthropic protocol — zhipu/GLM-5.3 path): tool
 * arguments streamed as input_json_delta fragments whose concatenation is not
 * valid JSON (unquoted ISO-8601 triggerAt). The flush at content_block_stop
 * must repair it; unrepairable input must surface a marker, never a silent {}.
 */
class AnthropicToolArgsSpec extends FunSuite:

  private val adapter = AnthropicAdapter("http://localhost:0", "k", null)

  private def runEvents(events: List[(String, String)]): List[StreamChunk] =
    val toolState = Ref.unsafe[IO, Map[Int, (String, String, StringBuilder)]](Map.empty)
    val tokenRef = Ref.unsafe[IO, adapter.Tokens](adapter.Tokens(0, None, None))
    events
      .map((et, data) => adapter.processAnthropicEvent(et, data, toolState, tokenRef, SendMessageParams(Nil, "GLM-5.3")))
      .sequence
      .unsafeRunSync()
      .flatten

  private def blockStart(idx: Int, id: String, name: String): (String, String) =
    ("content_block_start", s"""{"type":"content_block_start","index":$idx,"content_block":{"type":"tool_use","id":"$id","name":"$name","input":{}}}""")

  private def argDelta(idx: Int, partial: String): (String, String) =
    ("content_block_delta",
      s"""{"type":"content_block_delta","index":$idx,"delta":{"type":"input_json_delta","partial_json":${encodeJsonString(partial)}}}""")

  private def blockStop(idx: Int): (String, String) =
    ("content_block_stop", s"""{"type":"content_block_stop","index":$idx}""")

  /** Minimal JSON string encoder (no circe dependency on hand-rolled frames). */
  private def encodeJsonString(s: String): String =
    val out = new java.lang.StringBuilder("\"")
    s.foreach {
      case '"'  => out.append("\\\"")
      case '\\' => out.append("\\\\")
      case '\n' => out.append("\\n")
      case c    => out.append(c)
    }
    out.append("\"").toString

  test("unquoted ISO triggerAt fragments are repaired at content_block_stop (issue #18)") {
    // Fragments modeled on the captured GLM-5.3 stream: single characters and
    // short shards, concatenating to invalid JSON with an unquoted datetime.
    val full = """{"content":"明早汇总","triggerAt":2026-08-18T00:00:00+08:00}"""
    val fragments = full.grouped(7).toList // arbitrary shard boundaries
    val chunks = runEvents(
      blockStart(2, "call_x", "Schedule") :: fragments.map(argDelta(2, _)) ::: List(blockStop(2))
    )
    val toolChunks = chunks.collect { case StreamChunk.ToolCallChunk(tc) => tc }
    assertEquals(toolChunks.size, 1)
    val input = toolChunks.head.input
    assertEquals(input("triggerAt").flatMap(_.asString), Some("2026-08-18T00:00:00+08:00"))
    assertEquals(input("content").flatMap(_.asString), Some("明早汇总"))
    assert(input(ToolInputJson.RawArgsKey).isEmpty)
  }

  test("healthy quoted fragments pass through unchanged") {
    val chunks = runEvents(
      List(
        blockStart(0, "call_y", "Read"),
        argDelta(0, """{"file_path":"/t"""),
        argDelta(0, """.txt"}"""),
        blockStop(0)
      )
    )
    val tc = chunks.collect { case StreamChunk.ToolCallChunk(tc) => tc }.head
    assertEquals(tc.input("file_path").flatMap(_.asString), Some("/t.txt"))
  }

  test("unrepairable arguments surface the marker instead of {}") {
    val chunks = runEvents(
      List(
        blockStart(0, "call_z", "Schedule"),
        argDelta(0, """{"content":"x","trig"""),
        argDelta(0, """gerAt":never-gonna-parse}"""),
        blockStop(0)
      )
    )
    val tc = chunks.collect { case StreamChunk.ToolCallChunk(tc) => tc }.head
    assert(tc.input(ToolInputJson.RawArgsKey).isDefined, s"expected marker, got ${tc.input}")
    assert(ToolInputJson.malformedDetails(tc.input, "Schedule").isDefined)
  }

  test("empty fragment stream (provider sent nothing) stays a zero-arg call") {
    val chunks = runEvents(List(blockStart(0, "call_w", "ListThings"), blockStop(0)))
    val tc = chunks.collect { case StreamChunk.ToolCallChunk(tc) => tc }.head
    assert(tc.input.isEmpty)
    assert(tc.input(ToolInputJson.RawArgsKey).isEmpty)
  }

end AnthropicToolArgsSpec
