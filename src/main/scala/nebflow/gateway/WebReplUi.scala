package nebflow.gateway

import cats.effect.std.{Queue, Semaphore}
import cats.effect.{IO, Ref}
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.ReplUi
import org.http4s.websocket.WebSocketFrame

class WebReplUi(
  queue: Queue[IO, WebSocketFrame],
  askSemaphore: Semaphore[IO],
  permSemaphore: Semaphore[IO]
) extends ReplUi:
  private val escAction = Ref.unsafe[IO, Option[IO[Unit]]](None)
  private val askUserDeferred = Ref.unsafe[IO, Option[cats.effect.Deferred[IO, List[String]]]](None)
  private val permissionDeferred = Ref.unsafe[IO, Option[cats.effect.Deferred[IO, Boolean]]](None)

  private def sendJson(json: Json): IO[Unit] =
    queue.offer(WebSocketFrame.Text(json.noSpaces))

  def sendRaw(json: Json): IO[Unit] = sendJson(json)

  def emitThinking(): IO[Unit] =
    sendJson(Json.obj("type" -> "thinking".asJson))

  def emitInterrupted(): IO[Unit] =
    sendJson(Json.obj("type" -> "interrupted".asJson))

  def emitTextDelta(text: String): IO[Unit] =
    sendJson(Json.obj("type" -> "textDelta".asJson, "delta" -> text.asJson))

  def emitTextDone(): IO[Unit] =
    sendJson(Json.obj("type" -> "textDone".asJson))

  def emitToolStart(label: String): IO[Unit] =
    sendJson(Json.obj("type" -> "toolStart".asJson, "label" -> label.asJson))

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
    sendJson(Json.obj(fields*))

  def emitMaxTokens(): IO[Unit] =
    sendJson(Json.obj("type" -> "maxTokens".asJson))

  def emitTimeout(): IO[Unit] =
    sendJson(Json.obj("type" -> "timeout".asJson))

  def emitDone(): IO[Unit] =
    sendJson(Json.obj("type" -> "done".asJson))

  def onEscInterrupt(action: IO[Unit]): IO[Unit] =
    escAction.set(Some(action))

  def removeEscListener(): IO[Unit] =
    escAction.set(None)

  def triggerEsc(): IO[Unit] =
    escAction.get.flatMap {
      case Some(action) => action
      case None => IO.unit
    }

  def sendError(message: String): IO[Unit] =
    sendJson(Json.obj("type" -> "error".asJson, "message" -> message.asJson))

  def askUser(items: List[nebflow.core.AskItem]): IO[List[String]] =
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
      cats.effect.Deferred[IO, List[String]].flatMap { d =>
        askUserDeferred.set(Some(d)) *>
          sendJson(Json.obj("type" -> "askUser".asJson, "items" -> itemsJson.asJson)) *>
          d.get
      }
    }

  def answerAskUser(answers: List[String]): IO[Unit] =
    askUserDeferred.get.flatMap {
      case Some(d) => askUserDeferred.set(None) *> d.complete(answers).void
      case None => IO.unit
    }

  def askPermission(toolName: String, summary: String, inputJson: String): IO[Boolean] =
    permSemaphore.permit.use { _ =>
      cats.effect.Deferred[IO, Boolean].flatMap { d =>
        permissionDeferred.set(Some(d)) *>
          sendJson(
            Json.obj(
              "type" -> "askPermission".asJson,
              "toolName" -> toolName.asJson,
              "summary" -> summary.asJson,
              "input" -> parse(inputJson).getOrElse(Json.Null)
            )
          ) *>
          d.get
      }
    }

  def answerPermission(approved: Boolean): IO[Unit] =
    permissionDeferred.get.flatMap {
      case Some(d) => permissionDeferred.set(None) *> d.complete(approved).void
      case None => IO.unit
    }
end WebReplUi
