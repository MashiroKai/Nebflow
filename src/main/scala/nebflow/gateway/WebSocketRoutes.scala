package nebflow.gateway

import cats.effect.std.{Queue, Semaphore}
import cats.effect.{Fiber, IO, Ref}
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.parser.parse
import io.circe.syntax.*
import nebflow.core.*
import nebflow.shared.*
import nebflow.skill.SkillDiscovery
import org.http4s.dsl.io.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.{HttpRoutes, StaticFile}

import scala.concurrent.duration.*

class WebSocketRoutes(
  wsb: WebSocketBuilder2[IO],
  llm: LlmHandle[IO],
  sessionStore: SessionStore,
  thinkingModeRef: Ref[IO, Option[io.circe.Json]],
  permState: PermissionState,
  rateLimiter: RateLimiter,
  token: String,
  fileChangeTracker: FileChangeTracker,
  reminderStateRef: Ref[IO, ReminderState],
  contextWindow: Int = 128000,
  skillDiscovery: Option[SkillDiscovery] = None
):
  private val logger = NebflowLogger.forName("nebflow.ws")

  private def sendSessionList(replUi: WebReplUi): IO[Unit] =
    for
      sessions <- sessionStore.listSessions
      activeId <- sessionStore.getActiveId
      _ <- replUi.sendRaw(
        io.circe.Json.obj(
          "type" -> "sessionList".asJson,
          "sessions" -> sessions.asJson,
          "activeId" -> activeId.asJson
        )
      )
    yield ()

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "ws" =>
      val provided = req.params.get("token").getOrElse("")
      if Auth.validateToken(provided, token) then
        for
          fiberRef <- Ref.of[IO, Option[Fiber[IO, Throwable, Unit]]](None)
          outbound <- Queue.unbounded[IO, WebSocketFrame]
          askSem <- Semaphore[IO](1)
          permSem <- Semaphore[IO](1)
          replUi = new WebReplUi(outbound, askSem, permSem)

          receivePipe: Pipe[IO, WebSocketFrame, Unit] = _.evalMap {
            case WebSocketFrame.Text(text, _) => handleMessage(text, replUi, fiberRef)
            case _ => IO.unit
          }.onFinalize(
            fiberRef.get.flatMap {
              case Some(f) =>
                logger.info("WebSocket disconnected, cancelling REPL fiber") *>
                  f.cancel *> fiberRef.set(None)
              case None => IO.unit
            }
          )

          sendStream = Stream.fromQueueUnterminated(outbound)
          // Send initial session list on connect
          _ <- sendSessionList(replUi)
          _ <- logger.info("WebSocket client connected")
          ws <- wsb.build(sendStream, receivePipe)
        yield ws
      else
        logger.debug("WebSocket auth failed: invalid token") *>
          Forbidden("Invalid token")
      end if

    case req @ GET -> Root =>
      StaticFile.fromResource("web/index.html", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / fileName =>
      val allowed = Set("style.css", "app.js")
      if allowed.contains(fileName) then StaticFile.fromResource(s"web/$fileName", Some(req)).getOrElseF(NotFound())
      else NotFound()
  }

  private val inputHistoryPath = os.home / ".nebflow" / "input_history.jsonl"

  private def logInputHistory(content: String, attachments: List[io.circe.Json]): IO[Unit] =
    val filtered = content.trim.toLowerCase
    if (filtered == "quit" || filtered == "exit") && attachments.isEmpty then IO.unit
    else if content.trim.isEmpty && attachments.isEmpty then IO.unit
    else
      IO.blocking {
        val inputType =
          if attachments.nonEmpty then "file"
          else if content.length > 200 then "paste"
          else "input"
        val files = attachments.flatMap(_.hcursor.downField("name").as[String].toOption)
        val entry = io.circe.Json.obj(
          "text" -> io.circe.Json.fromString(content.take(2000)),
          "ts" -> io.circe.Json.fromString(
            java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
          ),
          "type" -> io.circe.Json.fromString(inputType)
        )
        val withFiles =
          if files.nonEmpty then
            entry.mapObject(_.add("files", io.circe.Json.fromValues(files.map(io.circe.Json.fromString))))
          else entry
        os.write.append(inputHistoryPath, withFiles.noSpaces + "\n", createFolders = true)
      }

  private def handleMessage(
    text: String,
    replUi: WebReplUi,
    fiberRef: Ref[IO, Option[Fiber[IO, Throwable, Unit]]]
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
          permState.setPolicy(policy) *>
          reminderStateRef.update(_.copy(policyReminderPending = true))

      case "interrupt" =>
        logger.info("User interrupted") *> replUi.triggerEsc()

      case "command" =>
        val command = parse(text).flatMap(_.hcursor.downField("command").as[String]).getOrElse("")
        command match
          case "clear" =>
            logger.info("Session cleared") *> sessionStore.setActiveMessages(Nil) *>
              reminderStateRef.update(_.copy(sessionStarted = false, highestPressureLevel = 0)) *>
              replUi.emitDone()
          case _ => IO.unit

      case "setThinking" =>
        val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
        json.hcursor.downField("thinking").focus match
          case Some(t) => thinkingModeRef.set(Some(t))
          case None => thinkingModeRef.set(None)

      case "switchSession" =>
        val sessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
        if sessionId.nonEmpty then
          (sessionStore.switchSession(sessionId) *> sendSessionList(replUi)).handleErrorWith { e =>
            logger.warn(s"Switch session failed: ${e.getMessage}") *> IO.unit
          }
        else IO.unit

      case "createSession" =>
        val name = parse(text).flatMap(_.hcursor.downField("name").as[String]).getOrElse("New Session")
        (sessionStore
          .createSession(name)
          .flatMap { meta =>
            sessionStore.switchSession(meta.id) *> sendSessionList(replUi)
          })
          .handleErrorWith { e =>
            logger.warn(s"Create session failed: ${e.getMessage}") *> IO.unit
          }

      case "deleteSession" =>
        val sessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
        if sessionId.nonEmpty then
          sessionStore.deleteSession(sessionId).attempt.flatMap {
            case Right(_) => sendSessionList(replUi)
            case Left(e) =>
              logger.warn(s"Delete session failed: ${e.getMessage}") *>
                replUi.sendRaw(
                  io.circe.Json.obj("type" -> "error".asJson, "message" -> s"Delete failed: ${e.getMessage}".asJson)
                )
          }
        else IO.unit

      case "ping" => IO.unit

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
                else if data.nonEmpty then
                  val maxChars = 30000
                  if data.length <= maxChars then blocks += ContentBlock.Text(s"[file: $name]\n$data")
                  else blocks += ContentBlock.Text(s"[file: $name] (文件过大，已截断前 ${maxChars} 字符)\n${data.take(maxChars)}")
              }

              val userMessage =
                if blocks.length == 1 && content.nonEmpty then Message(MessageRole.User, Left(content))
                else Message(MessageRole.User, Right(blocks.toList))

              logger.info(s"User message: ${content.take(60)}${if content.length > 60 then "..." else ""}") *>
                logInputHistory(content, attachments) *>
                fiberRef.get.flatMap {
                  case Some(f) =>
                    // Wait for previous REPL to finish so its response is saved,
                    // with a timeout to avoid blocking indefinitely.
                    // On timeout, flush whatever was saved via onToolRound before cancelling.
                    logger.info("Waiting for previous REPL fiber to complete") *>
                      f.join.timeout(5.seconds).void handleErrorWith { _ =>
                        sessionStore.flushIndex *> f.cancel
                      }
                  case None => IO.unit
                } *>
                // Capture session ID first to prevent race with concurrent session switch
                sessionStore.getActiveId
                  .flatMap { replSessionId =>
                    sessionStore.getActiveMessages
                      .flatMap { history =>
                        thinkingModeRef.get
                          .flatMap { thinking =>
                            Repl.runRepl(
                              userMessage = userMessage,
                              llm = llm,
                              projectRoot = System.getProperty("user.dir"),
                              initialMessages = history,
                              store = replUi,
                              onToolRound = Some { (msgs: List[Message]) =>
                                sessionStore.saveMessagesForSession(replSessionId, msgs)
                              },
                              silent = true,
                              thinkingMode = thinking,
                              permState = Some(permState),
                              contextWindow = contextWindow,
                              reminderStateRef = Some(reminderStateRef),
                              fileChangeTracker = Some(fileChangeTracker),
                              skillDiscovery = skillDiscovery,
                              userText = Some(content),
                              sessionStore = Some(sessionStore)
                            )
                          }
                          .flatMap { updated =>
                            sessionStore.saveMessagesForSession(
                              replSessionId,
                              updated
                            ) *> sessionStore.flushIndex *> replUi.emitDone() *>
                              sendSessionList(replUi)
                          }
                          .handleErrorWith { e =>
                            val userMsg = e match
                              case fe: nebflow.llm.FallbackExhaustedError =>
                                val attemptSummaries = fe.attempts.map(a =>
                                  s"${a.providerId}/${a.model}: ${a.reason.map(_.toString).getOrElse("unknown")}"
                                )
                                NebflowError.toUserMessage(NebflowError.LlmFailed(fe.getMessage, attemptSummaries))
                              case _ =>
                                NebflowError.toUserMessage(
                                  NebflowError.Internal(
                                    Option(e.getMessage).getOrElse("Unknown error")
                                  )
                                )
                            logger.error(s"REPL error: ${e.getMessage}", e) *> replUi.sendError(userMsg) *> replUi
                              .emitDone()
                          }
                          .guarantee(fiberRef.set(None))
                      }
                  }
                  .start
                  .flatMap(f => fiberRef.set(Some(f)))
                  .void
          }
        else IO.unit
        end if
  end handleMessage
end WebSocketRoutes
