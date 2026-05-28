package nebflow.gateway

import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.shared.*

/**
 * Records streaming events as UiMessages for session history,
 * and forwards all events to an underlying wsSend (typically wsHub.broadcast).
 *
 * Each instance is scoped to one session.
 */
class SessionRecorder private (
  sessionId: String,
  sessionStore: SessionStore,
  underlying: Json => IO[Unit],
  textBuf: Ref[IO, String],
  thinkingBuf: Ref[IO, String],
  turnStart: Ref[IO, Option[Long]]
):

  def apply(json: Json): IO[Unit] =
    val hc = json.hcursor
    val eventType = hc.downField("type").as[String].getOrElse("")
    val record = eventType match

      case "thinkingDelta" =>
        val delta = hc.downField("delta").as[String].getOrElse("")
        if delta.nonEmpty then
          turnStart.update(_.orElse(Some(System.currentTimeMillis()))) *>
            thinkingBuf.update(_ + delta)
        else IO.unit

      case "thinking" =>
        turnStart.update(_.orElse(Some(System.currentTimeMillis())))

      case "textDelta" =>
        val delta = hc.downField("delta").as[String].getOrElse("")
        if delta.nonEmpty then
          turnStart.update(_.orElse(Some(System.currentTimeMillis()))) *>
            textBuf.update(_ + delta)
        else IO.unit

      case "toolStart" | "roundComplete" =>
        flushText()

      case "done" =>
        val model = hc.downField("model").as[Option[String]].getOrElse(None)
        flushTextWithMeta(model)

      case "toolEnd" =>
        val label = hc.downField("label").as[String].getOrElse("")
        val summary = hc.downField("summary").as[String].getOrElse("")
        val content = hc.downField("content").as[String].getOrElse("")
        val isError = hc.downField("isError").as[Boolean].getOrElse(false)
        val input = hc.downField("input").as[Json].getOrElse(Json.Null).noSpaces
        sessionStore.appendUiMessages(
          sessionId,
          List(UiMessage.Tool(label, summary, content, isError, input))
        )

      case "system" =>
        val content = hc.downField("content").as[String].getOrElse("")
        if content.nonEmpty then sessionStore.appendUiMessages(sessionId, List(UiMessage.System(content)))
        else IO.unit

      case "user" =>
        val text = hc.downField("text").as[String].getOrElse("")
        val injected = hc.downField("injected").as[Boolean].getOrElse(false)
        if text.nonEmpty then sessionStore.appendUiMessages(sessionId, List(UiMessage.User(text, injected = injected)))
        else IO.unit

      case _ => IO.unit

    record.handleErrorWith(e =>
      IO(SessionRecorder.logger.warn(s"Failed to record UI message for session $sessionId: ${e.getMessage}"))
    ) *> underlying(json).handleErrorWith(e =>
      IO(SessionRecorder.logger.warn(s"Failed to broadcast for session $sessionId: ${e.getMessage}"))
    )
  end apply

  // -- helpers --

  private def flushText(): IO[Unit] =
    textBuf.getAndSet("").flatMap { text =>
      if text.nonEmpty then
        thinkingBuf.getAndSet("").flatMap { thinking =>
          sessionStore.appendUiMessages(
            sessionId,
            List(UiMessage.Ai(text, None, None, Option.when(thinking.nonEmpty)(thinking)))
          )
        }
      else thinkingBuf.set("") *> IO.unit
    }

  private def flushTextWithMeta(model: Option[String]): IO[Unit] =
    for
      start <- turnStart.getAndSet(None)
      text <- textBuf.getAndSet("")
      thinking <- thinkingBuf.getAndSet("")
      durationMs = start.map(s => System.currentTimeMillis() - s)
      thinkingOpt = Option.when(thinking.nonEmpty)(thinking)
      _ <-
        if text.nonEmpty || thinkingOpt.isDefined then
          sessionStore.appendUiMessages(sessionId, List(UiMessage.Ai(text, durationMs, model, thinkingOpt)))
        else IO.unit
    yield ()

end SessionRecorder

object SessionRecorder:
  private val logger = NebflowLogger.forName("nebflow.session-recorder")

  def apply(
    sessionId: String,
    sessionStore: SessionStore,
    underlying: Json => IO[Unit]
  ): SessionRecorder =
    new SessionRecorder(
      sessionId,
      sessionStore,
      underlying,
      textBuf = Ref.unsafe[IO, String](""),
      thinkingBuf = Ref.unsafe[IO, String](""),
      turnStart = Ref.unsafe[IO, Option[Long]](None)
    )
end SessionRecorder
