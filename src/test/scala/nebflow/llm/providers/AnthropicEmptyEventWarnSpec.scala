package nebflow.llm.providers

import cats.effect.IO
import cats.effect.Ref
import munit.CatsEffectSuite
import nebflow.llm.SendMessageParams
import nebflow.shared.StreamChunk
import scala.jdk.CollectionConverters.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.{GenericRequest, Response, StreamBackend}

/**
 * #256 (author ruling 2026-09-13): the silent-drop path in AnthropicAdapter — a
 * `data:` line arriving without a preceding `event:` line is dispatched with an
 * empty event type and lands on the terminal `case _ => Nil`, so the frame's
 * content is discarded with zero trace. The fix adds exactly one WARN there and
 * nowhere else (log surface only: no parsing / dispatch / retry / state change).
 *
 * Positive control: an event-line-less frame must WARN *and* still contribute
 * zero chunks (behavior unchanged). Negative control: frames that carry their
 * `event:` line — including legitimately unknown event names and `ping` — must
 * not WARN. Anti-payload control: the WARN must not carry raw wire text
 * (author: R1b — wire-level raw-frame capture is deliberately out of scope).
 *
 * CatsEffectSuite (not FunSuite): the bodies are IO, and a plain FunSuite would
 * build-but-never-run them (vacuous green — caught by the mutation check below).
 */
class AnthropicEmptyEventWarnSpec extends CatsEffectSuite:

  private val LogName = "nebflow.llm.anthropic"

  /** Minimal in-memory StreamBackend serving a canned SSE body. */
  private class CannedSseBackend(body: fs2.Stream[IO, Byte]) extends StreamBackend[IO, Fs2Streams[IO]]:

    def send[T](
      request: GenericRequest[T, Fs2Streams[IO] & sttp.capabilities.Effect[IO]]
    ): IO[Response[T]] =
      IO.pure(
        Response(
          Right(body).asInstanceOf[T],
          sttp.model.StatusCode.Ok,
          "",
          Nil
        )
      )

    def monad: sttp.monad.MonadError[IO] =
      new sttp.client4.impl.cats.CatsMonadError[IO](using IO.asyncForIO)
    def close(): IO[Unit] = IO.unit

  end CannedSseBackend

  /** Wire shape of the canned body: each frame followed by the SSE blank line. */
  private def sse(frames: String*): fs2.Stream[IO, Byte] =
    fs2.Stream
      .emits(frames.flatMap(f => List(f, "")))
      .map(_ + "\n")
      .through(fs2.text.utf8.encode)

  private def withWarnAppender[A](f: => IO[A]): IO[(A, List[String])] =
    val lbLogger = org.slf4j.LoggerFactory.getLogger(LogName).asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    IO.delay {
      appender.start()
      lbLogger.addAppender(appender)
    }.bracket { _ =>
      f.map(a =>
        a -> appender.list.asScala.toList
          .filter(_.getLevel == ch.qos.logback.classic.Level.WARN)
          .map(_.getFormattedMessage)
      )
    } { _ => IO.delay(lbLogger.detachAppender(appender)) }

  private def adapterFor(body: fs2.Stream[IO, Byte]): AnthropicAdapter =
    new AnthropicAdapter("https://api.example.com", "k", new CannedSseBackend(body))

  private val params =
    SendMessageParams(Nil, "GLM-5.3", sessionId = Some("sess-256"), agentId = Some("ag-256"))

  /** Marker proving the payload text never reaches the log line. */
  private val marker = "SECRET_WIRE_MARKER_256"

  private def eventFrame(et: String, data: String): String = s"event: $et\ndata: $data"

  private def deltaPayload(text: String): String =
    s"""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}"""

  /** The anomalous frame: a complete content_block_delta payload with NO `event:` line. */
  private val orphanFrame = s"data: ${deltaPayload(marker)}"

  private val startFrame = eventFrame(
    "message_start",
    """{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":0}}}"""
  )
  private def textFrame(text: String): String = eventFrame("content_block_delta", deltaPayload(text))

  private val stopFrame = eventFrame(
    "message_delta",
    """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}"""
  )

  /** WARN lines produced by the #256 drop path (other WARNs on the same logger ignored). */
  private def dropWarns(warns: List[String]): List[String] = warns.filter(_.contains("empty event type"))

  test("data frame without an `event:` line: WARN fires, frame still contributes zero chunks") {
    val body = sse(startFrame, textFrame("hello"), orphanFrame, textFrame(" world"), stopFrame)
    val adapter = adapterFor(body)
    for (chunks, warns) <- withWarnAppender(adapter.sendMessageStream(params).compile.toList)
    yield
      // Behavior unchanged: the anomalous frame yields nothing; the rest is intact.
      assertEquals(chunks.collect { case StreamChunk.TextDelta(t) => t }, List("hello", " world"))
      assert(chunks.exists(_.isInstanceOf[StreamChunk.Done]), s"stream must still reach Done, got $chunks")
      // Exactly one WARN for the one anomalous frame, with locatable metadata.
      val drops = dropWarns(warns)
      assertEquals(drops.size, 1, s"expected exactly one drop WARN, got $drops (all warns: $warns)")
      val w = drops.head
      assert(w.contains("frame_type=content_block_delta"), w)
      assert(w.contains("payloadChars="), w)
      assert(w.contains("model=GLM-5.3"), w)
      assert(w.contains("sessionId=sess-256"), w)
      assert(w.contains("agentId=ag-256"), w)
      // Anti-payload: no raw wire text in the log line.
      assert(!w.contains(marker), s"raw payload leaked into WARN: $w")
      assert(!w.contains("{"), s"raw JSON leaked into WARN: $w")
    end for
  }

  test("negative control: frames carrying their `event:` line (known, unknown, ping) never warn") {
    val body = sse(
      startFrame,
      eventFrame("ping", """{"type":"ping"}"""),
      textFrame("hi"),
      eventFrame("some_future_event_type", """{"type":"some_future_event_type"}"""),
      stopFrame
    )
    val adapter = adapterFor(body)
    for (chunks, warns) <- withWarnAppender(adapter.sendMessageStream(params).compile.toList)
    yield
      assertEquals(chunks.collect { case StreamChunk.TextDelta(t) => t }, List("hi"))
      assertEquals(dropWarns(warns), List.empty[String], s"compliant frames must not warn, got $warns")
  }

  test("drop site: empty event type warns and still returns Nil; named-but-unknown event types stay silent") {
    val adapter = adapterFor(fs2.Stream.empty[IO])
    val state = Ref.unsafe[IO, Map[Int, (String, String, StringBuilder)]](Map.empty)
    val tokens = Ref.unsafe[IO, adapter.Tokens](adapter.Tokens(0, None, None))
    for
      (empty, warnsEmpty) <- withWarnAppender(
        adapter.processAnthropicEvent("", deltaPayload(marker), state, tokens, params)
      )
      (named, warnsNamed) <- withWarnAppender(
        adapter.processAnthropicEvent("ping", """{"type":"ping"}""", state, tokens, params)
      )
    yield
      assertEquals(empty, List.empty[StreamChunk], "empty event type must keep returning Nil")
      assertEquals(named, List.empty[StreamChunk], "unknown-but-named event type must keep returning Nil")
      val drops = dropWarns(warnsEmpty)
      assertEquals(drops.size, 1, s"expected one drop WARN, got $drops")
      assert(!drops.head.contains(marker), s"raw payload leaked into WARN: ${drops.head}")
      assertEquals(
        dropWarns(warnsNamed),
        List.empty[String],
        s"named-but-unknown events must stay silent, got $warnsNamed"
      )
    end for
  }

end AnthropicEmptyEventWarnSpec
