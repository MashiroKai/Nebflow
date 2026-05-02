package nebflow.agent

import cats.effect.std.Semaphore
import cats.effect.{Deferred, IO, Ref}
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import nebflow.core.{AskItem, ReplUi}

/** ReplUi implementation for SessionActor — streams events via wsSend,
  * uses Deferred for askUser/askPermission round-trips.
  * Tags all events with the sessionId captured at REPL start time (immutable).
  */
class SessionReplUi(
  wsSend: Json => IO[Unit],
  sessionId: String,
  askSemaphore: Semaphore[IO]
) extends ReplUi:

  private val pendingAsk = Ref.unsafe[IO, Option[Deferred[IO, List[String]]]](None)
  private val pendingPerm = Ref.unsafe[IO, Option[Deferred[IO, Boolean]]](None)

  private def send(json: Json): IO[Unit] =
    val tagged = json.mapObject(_.add("sessionId", sessionId.asJson))
    wsSend(tagged)

  // ---------- emit methods ----------

  def emitThinking(): IO[Unit] =
    send(Json.obj("type" -> "thinking".asJson))

  def emitInterrupted(): IO[Unit] =
    send(Json.obj("type" -> "interrupted".asJson))

  def emitTextDelta(text: String): IO[Unit] =
    send(Json.obj("type" -> "textDelta".asJson, "delta" -> text.asJson))

  def emitTextDone(): IO[Unit] =
    send(Json.obj("type" -> "textDone".asJson))

  def emitToolStart(label: String): IO[Unit] =
    send(Json.obj("type" -> "toolStart".asJson, "label" -> label.asJson))

  def emitToolEnd(
    label: String,
    summary: String,
    content: String,
    isError: Boolean,
    inputJson: Option[String] = None
  ): IO[Unit] =
    val fields = List(
      "type" -> "toolEnd".asJson,
      "label" -> label.asJson,
      "summary" -> summary.asJson,
      "content" -> content.asJson,
      "isError" -> isError.asJson
    ) ++ inputJson.filter(_.nonEmpty).flatMap(j => parse(j).toOption.map(v => "input" -> v))
    send(Json.obj(fields*))

  def emitMaxTokens(): IO[Unit] =
    send(Json.obj("type" -> "maxTokens".asJson))

  def emitTimeout(): IO[Unit] =
    send(Json.obj("type" -> "timeout".asJson))

  def emitDone(): IO[Unit] =
    send(Json.obj("type" -> "done".asJson))

  // ---------- interrupt (no-op, handled by actor) ----------

  def onEscInterrupt(action: IO[Unit]): IO[Unit] = IO.unit
  def removeEscListener(): IO[Unit] = IO.unit

  // ---------- askUser / askPermission ----------

  def askUser(items: List[AskItem]): IO[List[String]] =
    askSemaphore.permit.use { _ =>
      val itemsJson = items.map { item =>
        val opts = item.options.map { o =>
          val fields = List("label" -> o.label.asJson) ++
            o.description.map(d => "description" -> d.asJson)
          Json.obj(fields*)
        }
        Json.obj(
          "question" -> item.question.asJson,
          "options" -> opts.asJson,
          "allowOther" -> item.allowOther.asJson
        )
      }
      Deferred[IO, List[String]].flatMap { d =>
        pendingAsk.set(Some(d)) *>
          send(Json.obj("type" -> "askUser".asJson, "items" -> itemsJson.asJson)) *>
          d.get
      }
    }

  def answerAskUser(answers: List[String]): IO[Unit] =
    pendingAsk.getAndSet(None).flatMap {
      case Some(d) => d.complete(answers).void
      case None => IO.unit
    }

  def askPermission(toolName: String, summary: String, inputJson: String): IO[Boolean] =
    askSemaphore.permit.use { _ =>
      Deferred[IO, Boolean].flatMap { d =>
        pendingPerm.set(Some(d)) *>
          send(Json.obj(
            "type" -> "askPermission".asJson,
            "toolName" -> toolName.asJson,
            "summary" -> summary.asJson,
            "input" -> parse(inputJson).getOrElse(Json.Null)
          )) *>
          d.get
      }
    }

  def answerPermission(approved: Boolean): IO[Unit] =
    pendingPerm.getAndSet(None).flatMap {
      case Some(d) => d.complete(approved).void
      case None => IO.unit
    }

  // ---------- error ----------

  def sendError(message: String): IO[Unit] =
    send(Json.obj("type" -> "error".asJson, "message" -> message.asJson))

end SessionReplUi
