package nebflow.llm.providers

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.agent.StreamBatching
import nebflow.llm.SendMessageParams
import nebflow.shared.StreamChunk

import java.nio.file.{Files, Path}

/** Synthetic provider deltas exercise the real adapter and WS batching boundary. */
class MermaidStreamPreservationSpec extends CatsEffectSuite:
  private val adapter = new OpenAiAdapter("https://synthetic.invalid/v1", "synthetic-key", null)
  private val params = SendMessageParams(Nil, "synthetic-model")
  private val pieces = List("```mermaid", "\n", "flowchart TD\n  A[Start] --> B[End]", "\n```")
  private val markdown = pieces.mkString
  private val sessionId = "synthetic-mermaid"

  private def decode(parts: List[String], array: Boolean = false, field: String = "content"): IO[List[StreamChunk]] =
    Ref.of[IO, Map[Int, ToolCallEntry]](Map.empty).flatMap { state =>
      parts.flatTraverse { text =>
        val value =
          if array then Json.arr(Json.obj("type" -> "text".asJson, "text" -> text.asJson))
          else text.asJson
        val data = Json.obj("choices" -> Json.arr(Json.obj("delta" -> Json.obj(field -> value))))
        adapter.processOpenAiData(data.noSpaces, state, params)
      }
    }

  private def frames(chunks: List[StreamChunk]): IO[Vector[Json]] =
    Ref.of[IO, Vector[Json]](Vector.empty).flatMap { out =>
      Stream
        .emits(chunks :+ StreamChunk.Done(None, None, None, None))
        .covary[IO]
        .through(StreamBatching.pipe(j => out.update(_ :+ j), false, Some(sessionId), false, false, "synthetic"))
        .compile
        .drain *> out.get
    }

  private def text(chunks: List[StreamChunk]): String =
    chunks.collect { case StreamChunk.TextDelta(delta) => delta }.mkString

  test("standalone newline deltas preserve Mermaid through adapter and WS JSON") {
    for
      single <- decode(List(markdown))
      split <- decode(pieces)
      singleFrames <- frames(single)
      splitFrames <- frames(split)
      // Optional bridge fixture contains actual Scala-produced WS frames, never user data.
      _ <- IO(sys.env.get("MERMAID_STREAM_FIXTURE").foreach { file =>
        val done = Json.obj("type" -> "done".asJson, "sessionId" -> sessionId.asJson)
        val fixture = Json.obj(
          "expected" -> markdown.asJson,
          "single" -> (singleFrames :+ done).asJson,
          "split" -> (splitFrames :+ done).asJson,
          "historyText" -> text(split).asJson
        )
        val path = Path.of(file)
        Files.createDirectories(path.getParent)
        Files.writeString(path, fixture.spaces2)
      })
    yield
      assertEquals(text(split), markdown)
      assertEquals(text(single), text(split))
      val wireText = splitFrames.map(_.hcursor.downField("delta").as[String].getOrElse("")).mkString
      assertEquals(wireText, markdown)
  }

  test("every two-part split and character deltas preserve exact Markdown") {
    (List(markdown.map(_.toString).toList) ++ (0 to markdown.length).map(markdown.splitAt).map { case (a, b) =>
      List(a, b)
    }).traverse_ { parts =>
      decode(parts).map(chunks => assertEquals(text(chunks), markdown))
    }
  }

  test("array content preserves whitespace-only text blocks and mixed blocks") {
    for
      chunks <- decode(pieces, array = true)
      state <- Ref.of[IO, Map[Int, ToolCallEntry]](Map.empty)
      data = Json.obj(
        "choices" -> Json.arr(
          Json.obj(
            "delta" -> Json.obj(
              "content" -> Json.arr(
                Json.obj("type" -> "text".asJson, "text" -> "```mermaid".asJson),
                Json.obj("type" -> "text".asJson, "text" -> "\n".asJson),
                Json.obj("type" -> "text".asJson, "text" -> "flowchart TD".asJson)
              )
            )
          )
        )
      )
      mixed <- adapter.processOpenAiData(data.noSpaces, state, params)
    yield
      assertEquals(text(chunks), markdown)
      assertEquals(text(mixed), "```mermaid\nflowchart TD")
  }

  test("reasoning aliases preserve whitespace while empty deltas remain absent") {
    List("reasoning_content", "thinking", "reasoning").traverse_ { field =>
      for
        chunks <- decode(List("first", "\n", "  ", "last", ""), field = field)
        empty <- decode(List(""), field = field)
        emptyText <- decode(List(""))
      yield
        assertEquals(chunks.collect { case StreamChunk.ThinkingDelta(delta) => delta }.mkString, "first\n  last")
        assertEquals(empty, Nil)
        assertEquals(emptyText, Nil)
    }
  }
end MermaidStreamPreservationSpec
