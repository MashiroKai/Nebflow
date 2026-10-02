package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.{Json, parser}
import munit.CatsEffectSuite
import nebflow.core.LlmLogWriter
import nebflow.shared.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * visionfix (甲) — §6 visible marker (author ruling: LOG-SIDE ONLY, zero new
 * user-facing text).
 *
 * Asserts the structured marker `type="vision_intent"` really lands in the
 * router sse JSONL for a request that carried an image, with `hadImage=true`,
 * and pins it to the SAME payload the adapter received: the marker's `hadImage`
 * and the captured wire body's image-block count must agree. That agreement is
 * what makes a reintroduced strip step observable — the marker would flip to
 * `false` while the wire would show zero images (mutation-2 of the batch red
 * check).
 *
 * Isolation: the router log dir is redirected via `setLogDirForTest` onto a
 * JUnit temp dir; nothing under the real HOME is read or written.
 *
 * Ports 18573/18574 are free (checked at run time in the batch evidence).
 */
class VisionIntentMarkerSpec extends CatsEffectSuite:

  override val munitIOTimeout = 45.seconds

  private val PortMarker = 18573
  private val PortMarkerText = 18574

  private val SrcImageB64: String =
    "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAa0lEQVR42hWNQREAQQzCKqVSIgUplYIUpCDlbnkSJswMOzBouMFDhg4zyy4sWm7xkqX7g1e+CA4Mgf6KEatXS5ywiKh+cOy9sY47fOTo/cCsn0LmjE1M/YOweWKFCw4JzQ/K9t2pXHFJafkAliFIATL1UtgAAAAASUVORK5CYII="

  private val messageStart =
    "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_mock\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n"
  private def textDelta(t: String) =
    s"event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"$t\"}}\n\n"
  private val okTail =
    Seq(
      "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
      "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n",
      "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
      "data: [DONE]\n\n"
    ).mkString

  private def startMock(port: Int, sink: AtomicReference[String]): IO[HttpServer] =
    IO.blocking {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
      server.createContext(
        "/v1/messages",
        (exchange: HttpExchange) =>
          sink.set(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
          val payload = (messageStart + textDelta("OK") + okTail).getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
          exchange.sendResponseHeaders(200, 0)
          val os = exchange.getResponseBody
          os.write(payload)
          os.flush()
          os.close()
          exchange.close()
      )
      server.start()
      server
    }

  private def mkConfig(port: Int): NebflowServiceConfig =
    NebflowServiceConfig(
      llm = ServiceLlmConfig(
        providers = Map(
          "v" -> ProviderConfig(
            baseUrl = s"http://127.0.0.1:$port",
            apiKey = "test",
            protocol = LlmProtocol.Anthropic,
            models = List(ModelConfig("m1", contextWindow = 128000))
          )
        )
      )
    )

  private def imageRequest(sid: String): LlmRequest =
    LlmRequest(
      messages = List(
        Message(
          MessageRole.User,
          Right(List(ContentBlock.Text("describe this"), ContentBlock.Image(SrcImageB64, "image/png")))
        )
      ),
      sessionId = sid,
      agentId = "vision-marker-spec-agent",
      agentModel = Some(AgentModelConfig(preferred = Some("v/m1"), fallbacks = Nil))
    )

  private def countImageBlocks(body: String): Int =
    val json = parser.parse(body).getOrElse(Json.Null)
    json.hcursor
      .downField("messages")
      .as[List[Json]]
      .getOrElse(Nil)
      .flatMap(_.hcursor.downField("content").as[List[Json]].getOrElse(Nil))
      .count(_.hcursor.downField("type").as[String].toOption.contains("image"))

  /** All lines of type=vision_intent found in the redirected sse JSONL. */
  private def visionIntentLines(dir: Path): List[Json] =
    val files =
      if Files.isDirectory(dir) then
        val stream = Files.list(dir)
        try stream.iterator().asScala.filter(_.getFileName.toString.endsWith("_sse.jsonl")).toList
        finally stream.close()
      else Nil
    files
      .flatMap(p => Files.readAllLines(p).asScala.toList)
      .flatMap(l => parser.parse(l).toOption)
      .filter(_.hcursor.downField("type").as[String].toOption.contains("vision_intent"))

  private def withMarkerDir[A](f: Path => IO[A]): IO[A] =
    for
      dir <- IO.blocking(Files.createTempDirectory("vf2-marker-"))
      _ <- IO(LlmLogWriter.setLogDirForTest(dir))
      _ <- IO(LlmLogWriter.setEnabled(true))
      out <- f(dir).guarantee(
        IO {
          LlmLogWriter.setEnabled(false)
          LlmLogWriter.resetLogDirForTest()
        }
      )
    yield out

  test("marker: a request carrying an image logs vision_intent with hadImage=true") {
    val sink = new AtomicReference[String]("")
    var server: HttpServer = null
    val program = withMarkerDir { dir =>
      for
        s <- startMock(PortMarker, sink)
        _ = server = s
        configRef <- Ref.of[IO, NebflowServiceConfig](mkConfig(PortMarker))
        overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        res <- LlmInterface
          .createLlm(overrides, None, Some(configRef))
          .flatMap { case (handle, _, _, release) =>
            handle.sendStream(imageRequest("vision-marker-spec")).compile.toList.attempt.guarantee(release)
          }
        _ <- IO.blocking(server.stop(0)).attempt.void
        lines <- IO.blocking(visionIntentLines(dir))
      yield (res, lines)
    }
    val (res, lines) = try program.unsafeRunSync()
    finally if server != null then try server.stop(0) catch case _: Throwable => ()

    assert(res.isRight, s"turn should complete against the mock: $res")
    assertEquals(lines.size, 1, s"exactly one vision_intent marker expected, got ${lines.size}")
    val line = lines.head
    assertEquals(
      line.hcursor.downField("hadImage").as[Boolean].toOption,
      Some(true),
      s"marker must report hadImage=true for an image request: $line"
    )
    assertEquals(line.hcursor.downField("model").as[String].toOption, Some("m1"))
    assertEquals(
      line.hcursor.downField("provider").as[String].toOption,
      Some("v"),
      s"marker must name the provider actually attempted: $line"
    )
    // Cross-pin: the marker and the OUTBOUND WIRE must agree. The marker is fed
    // the same value the adapter sends (interface.scala send point), so a
    // strip step reintroduced after the marker would break this equality.
    val wire = countImageBlocks(sink.get())
    assert(
      wire >= 1,
      s"marker said an image went out (hadImage=true) but the wire has $wire image blocks; body=${sink.get()}"
    )
  }

  test("marker: a text-only request logs hadImage=false") {
    val sink = new AtomicReference[String]("")
    val hits = new AtomicInteger(0)
    var server: HttpServer = null
    val program = withMarkerDir { dir =>
      for
        s <- startMock(PortMarkerText, sink)
        _ = server = s
        configRef <- Ref.of[IO, NebflowServiceConfig](mkConfig(PortMarkerText))
        overrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        req = LlmRequest(
          messages = List(Message(MessageRole.User, Left("plain text only"))),
          sessionId = "vision-marker-spec",
          agentId = "vision-marker-spec-agent",
          agentModel = Some(AgentModelConfig(preferred = Some("v/m1"), fallbacks = Nil))
        )
        res <- LlmInterface
          .createLlm(overrides, None, Some(configRef))
          .flatMap { case (handle, _, _, release) =>
            handle.sendStream(req).compile.toList.attempt.guarantee(release)
          }
        _ <- IO.blocking(server.stop(0)).attempt.void
        lines <- IO.blocking(visionIntentLines(dir))
      yield (res, lines, hits.get())
    }
    val (res, lines, _) = try program.unsafeRunSync()
    finally if server != null then try server.stop(0) catch case _: Throwable => ()

    assert(res.isRight, s"turn should complete against the mock: $res")
    assertEquals(lines.size, 1, s"exactly one marker expected, got ${lines.size}")
    assertEquals(
      lines.head.hcursor.downField("hadImage").as[Boolean].toOption,
      Some(false),
      "a text-only request must report hadImage=false"
    )
    assertEquals(countImageBlocks(sink.get()), 0, "the wire must carry no image blocks")
  }
end VisionIntentMarkerSpec
