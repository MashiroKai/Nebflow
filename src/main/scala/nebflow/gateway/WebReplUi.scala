package nebflow.gateway

import cats.effect.std.Queue
import cats.effect.{IO, Ref}
import io.circe.syntax.*
import nebflow.core.ReplUi
import org.http4s.websocket.WebSocketFrame

class WebReplUi(queue: Queue[IO, WebSocketFrame]) extends ReplUi:
  private val escAction = Ref.unsafe[IO, Option[IO[Unit]]](None)
  private val askUserDeferred = Ref.unsafe[IO, Option[cats.effect.Deferred[IO, List[String]]]](None)
  private val permissionDeferred = Ref.unsafe[IO, Option[cats.effect.Deferred[IO, Boolean]]](None)

  private def sendJson(json: String): IO[Unit] =
    queue.offer(WebSocketFrame.Text(json))

  def emitThinking(): IO[Unit] =
    sendJson("""{"type":"thinking"}""")

  def emitInterrupted(): IO[Unit] =
    sendJson("""{"type":"interrupted"}""")

  def emitTextDelta(text: String): IO[Unit] =
    sendJson(s"""{"type":"textDelta","delta":${text.asJson.noSpaces}}""")

  def emitTextDone(): IO[Unit] =
    sendJson("""{"type":"textDone"}""")

  def emitToolStart(label: String): IO[Unit] =
    sendJson(s"""{"type":"toolStart","label":${label.asJson.noSpaces}}""")

  def emitToolEnd(
    label: String,
    summary: String,
    content: String,
    isError: Boolean,
    inputJson: Option[String] = None
  ): IO[Unit] =
    val inputField = inputJson.filter(_.nonEmpty).map(j => s""","input":$j""").getOrElse("")
    sendJson(
      s"""{"type":"toolEnd","label":${label.asJson.noSpaces},"summary":${summary.asJson.noSpaces},"content":${content.asJson.noSpaces},"isError":$isError$inputField}"""
    )

  def emitMaxTokens(): IO[Unit] =
    sendJson("""{"type":"maxTokens"}""")

  def emitTimeout(): IO[Unit] =
    sendJson("""{"type":"timeout"}""")

  def emitDone(): IO[Unit] =
    sendJson("""{"type":"done"}""")

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
    sendJson(s"""{"type":"error","message":${message.asJson.noSpaces}}""")

  def askUser(items: List[nebflow.core.AskItem]): IO[List[String]] =
    val itemsJson = items
      .map { item =>
        val opts = item.options
          .map(o =>
            s"""{"label":${o.label.asJson.noSpaces}${o.description
                .map(d => s",\"description\":${d.asJson.noSpaces}")
                .getOrElse("")}}"""
          )
          .mkString(",")
        s"""{"question":${item.question.asJson.noSpaces},"options":[$opts]}"""
      }
      .mkString(",")
    cats.effect.Deferred[IO, List[String]].flatMap { d =>
      askUserDeferred.set(Some(d)) *>
        sendJson(s"""{"type":"askUser","items":[$itemsJson]}""") *>
        d.get
    }

  def answerAskUser(answers: List[String]): IO[Unit] =
    askUserDeferred.get.flatMap {
      case Some(d) => askUserDeferred.set(None) *> d.complete(answers).void
      case None => IO.unit
    }

  def askPermission(toolName: String, summary: String, inputJson: String): IO[Boolean] =
    cats.effect.Deferred[IO, Boolean].flatMap { d =>
      permissionDeferred.set(Some(d)) *>
        sendJson(
          s"""{"type":"askPermission","toolName":${toolName.asJson.noSpaces},"summary":${summary.asJson.noSpaces},"input":$inputJson}"""
        ) *>
        d.get
    }

  def answerPermission(approved: Boolean): IO[Unit] =
    permissionDeferred.get.flatMap {
      case Some(d) => permissionDeferred.set(None) *> d.complete(approved).void
      case None => IO.unit
    }
end WebReplUi
