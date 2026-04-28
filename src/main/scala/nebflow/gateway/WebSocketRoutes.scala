package nebflow.gateway

import cats.effect.std.Queue
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.parser.parse
import nebflow.core.{NebflowError, NebflowLogger, PermissionPolicy, PermissionState, Repl}
import nebflow.shared.*
import org.http4s.dsl.io.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.{HttpRoutes, StaticFile}

class WebSocketRoutes(
  wsb: WebSocketBuilder2[IO],
  llm: LlmHandle[IO],
  messagesRef: Ref[IO, List[Message]],
  saveSession: List[Message] => IO[Unit],
  thinkingModeRef: Ref[IO, Option[io.circe.Json]],
  permState: PermissionState,
  rateLimiter: RateLimiter,
  token: String
):
  private val logger = NebflowLogger.forName("nebflow.ws")

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "ws" =>
      val provided = req.params.get("token").getOrElse("")
      if Auth.validateToken(provided, token) then
        for
          outbound <- Queue.unbounded[IO, WebSocketFrame]
          replUi = new WebReplUi(outbound)

          receivePipe: Pipe[IO, WebSocketFrame, Unit] = _.evalMap {
            case WebSocketFrame.Text(text, _) => handleMessage(text, replUi)
            case _ => IO.unit
          }

          sendStream = Stream.fromQueueUnterminated(outbound)
          _ <- logger.info("WebSocket client connected")
          ws <- wsb.build(sendStream, receivePipe)
        yield ws
      else
        logger.warn("WebSocket auth failed: invalid token") *>
          Forbidden("Invalid token")

    case req @ GET -> Root =>
      StaticFile.fromResource("web/index.html", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / fileName =>
      val allowed = Set("style.css", "app.js")
      if allowed.contains(fileName) then StaticFile.fromResource(s"web/$fileName", Some(req)).getOrElseF(NotFound())
      else NotFound()
  }

  private def handleMessage(
    text: String,
    replUi: WebReplUi
  ): IO[Unit] =
    parse(text).toOption.getOrElse(io.circe.Json.Null).hcursor.downField("type").as[String].getOrElse("") match
      case "askUserAnswer" =>
        parse(text).flatMap(_.hcursor.downField("answers").as[List[String]]).toOption match
          case Some(answers) => replUi.answerAskUser(answers)
          case None => IO.unit

      case "permissionAnswer" =>
        val approved = parse(text).flatMap(_.hcursor.downField("approved").as[Boolean]).getOrElse(false)
        logger.info(s"Permission answer: ${if approved then "approved" else "denied"}") *>
          replUi.answerPermission(approved)

      case "setPolicy" =>
        val policyStr = parse(text).flatMap(_.hcursor.downField("policy").as[String]).getOrElse("ask")
        val policy = PermissionPolicy.fromString(policyStr)
        logger.info(s"Permission policy changed to: $policyStr") *>
          permState.setPolicy(policy)

      case "interrupt" =>
        logger.info("User interrupted") *> replUi.triggerEsc()

      case "command" =>
        val command = parse(text).flatMap(_.hcursor.downField("command").as[String]).getOrElse("")
        command match
          case "clear" =>
            logger.info("Session cleared") *> messagesRef.set(Nil) *> saveSession(Nil) *> replUi.emitDone()
          case _ => IO.unit

      case "setThinking" =>
        val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
        json.hcursor.downField("thinking").focus match
          case Some(t) => thinkingModeRef.set(Some(t))
          case None => thinkingModeRef.set(None)

      case _ =>
        val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
        val content = json.hcursor.downField("content").as[String].getOrElse("")
        val attachments = json.hcursor.downField("attachments").as[List[io.circe.Json]].getOrElse(Nil)

        if content.trim == "__interrupt__" then replUi.triggerEsc()
        else if content.nonEmpty || attachments.nonEmpty then
          rateLimiter.check("ws").flatMap { allowed =>
            if !allowed then
              logger.warn("Rate limit exceeded") *>
                replUi.sendError(NebflowError.toUserMessage(NebflowError.RateLimited("websocket")))
            else
              val blocks = scala.collection.mutable.ListBuffer.empty[ContentBlock]
              if content.nonEmpty then blocks += ContentBlock.Text(content)

              attachments.foreach { att =>
                val mimeType = att.hcursor.downField("mimeType").as[String].getOrElse("")
                val data = att.hcursor.downField("data").as[String].getOrElse("")
                val name = att.hcursor.downField("name").as[String].getOrElse("")
                if mimeType.startsWith("image/") && data.nonEmpty then blocks += ContentBlock.Image(data, mimeType)
                else if data.nonEmpty then blocks += ContentBlock.Text(s"[file: $name]\n$data")
              }

              val userMessage =
                if blocks.length == 1 && content.nonEmpty then Message(MessageRole.User, Left(content))
                else Message(MessageRole.User, Right(blocks.toList))

              logger.info(s"User message: ${content.take(60)}${if content.length > 60 then "..." else ""}") *>
                messagesRef.get
                  .flatMap { history =>
                    thinkingModeRef.get
                      .flatMap { thinking =>
                        Repl.runRepl(
                          userMessage = userMessage,
                          llm = llm,
                          projectRoot = System.getProperty("user.dir"),
                          initialMessages = history,
                          store = replUi,
                          onToolRound = Some { (msgs: List[Message]) => messagesRef.set(msgs) *> saveSession(msgs) },
                          silent = true,
                          thinkingMode = thinking,
                          permState = Some(permState)
                        )
                      }
                      .flatMap { updated =>
                        messagesRef.set(updated) *> saveSession(updated)
                      }
                      .handleErrorWith { e =>
                        val userMsg = e match
                          case fe: nebflow.llm.FallbackExhaustedError =>
                            NebflowError.toUserMessage(NebflowError.LlmFailed(fe.getMessage, Nil))
                          case _ =>
                            NebflowError.toUserMessage(
                              NebflowError.Internal(
                                Option(e.getMessage).getOrElse("Unknown error")
                              )
                            )
                        logger.error(s"REPL error: ${e.getMessage}", e) *> replUi.sendError(userMsg)
                      }
                  }
                  .guarantee(replUi.emitDone())
                  .start
                  .void
          }
        else IO.unit
        end if
  end handleMessage
end WebSocketRoutes
