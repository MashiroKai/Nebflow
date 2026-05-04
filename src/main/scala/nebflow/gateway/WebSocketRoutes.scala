package nebflow.gateway

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.parser.parse
import io.circe.syntax.*
import nebflow.agent.{SessionCommand, *}
import nebflow.core.*
import nebflow.service.{AgentService, ConfigService, SessionService}
import nebflow.shared.*
import nebflow.skill.SkillDiscovery
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.http4s.dsl.io.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.{HttpRoutes, StaticFile}

import scala.concurrent.duration.*

class WebSocketRoutes(
  wsb: WebSocketBuilder2[IO],
  sessionService: SessionService,
  agentService: AgentService,
  configService: ConfigService.type,
  thinkingModeRef: Ref[IO, Option[io.circe.Json]],
  permState: PermissionState,
  rateLimiter: RateLimiter,
  token: String,
  fileChangeTracker: FileChangeTracker,
  reminderStateRef: Ref[IO, ReminderState],
  sessionStore: SessionStore,
  wsHub: WsHub,
  sessionActorRef: ActorRef[SessionCommand],
  contextWindow: Int = Defaults.ContextWindow,
  skillDiscovery: Option[SkillDiscovery] = None,
  sharedResources: SharedResources = null
):
  private val logger = NebflowLogger.forName("nebflow.ws")

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "ws" =>
      val cookieToken = req.cookies.find(_.name == "nebflow_token").map(_.content).getOrElse("")
      val provided = if cookieToken.nonEmpty then cookieToken else req.params.get("token").getOrElse("")
      if Auth.validateToken(provided, token) then
        for
          outbound <- Queue.unbounded[IO, WebSocketFrame]

          perConnWsSend = (json: io.circe.Json) => outbound.offer(WebSocketFrame.Text(json.noSpaces))
          hubConnId <- wsHub.register(perConnWsSend)

          receivePipe: Pipe[IO, WebSocketFrame, Unit] = _.evalMap {
            case WebSocketFrame.Text(text, _) =>
              handleMessage(text, perConnWsSend, Some(sessionActorRef))
            case _ => IO.unit
          }.onFinalize(
            wsHub.unregister(hubConnId)
          )

          sendStream = Stream.fromQueueUnterminated(outbound)
          _ <- logger.info("WebSocket client connected")
          policy <- permState.policy
          thinking <- thinkingModeRef.get
          _ <- outbound.offer(
            WebSocketFrame.Text(
              io.circe.Json
                .obj(
                  "type" -> "serverConfig".asJson,
                  "streamTimeoutMs" -> (Defaults.StreamTimeoutSec.toLong * 1000).asJson,
                  "version" -> nebflow.Version.string.asJson,
                  "policy" -> PermissionPolicy.toName(policy).asJson,
                  "thinking" -> thinking.getOrElse(io.circe.Json.Null)
                )
                .noSpaces
            )
          )
          _ <- sessionService.sendSessionList(perConnWsSend)
          ws <- wsb.build(sendStream, receivePipe)
        yield ws
      else
        logger.debug("WebSocket auth failed: invalid token") *>
          Forbidden("Invalid token")
      end if

    case req @ GET -> Root =>
      StaticFile.fromResource("web/index.html", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / "css" / file =>
      StaticFile.fromResource(s"web/css/$file", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / "js" / file =>
      StaticFile.fromResource(s"web/js/$file", Some(req)).getOrElseF(NotFound())

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
    wsSend: io.circe.Json => IO[Unit],
    sessionRefOpt: Option[ActorRef[SessionCommand]]
  ): IO[Unit] =
    parse(text).toOption.getOrElse(io.circe.Json.Null).hcursor.downField("type").as[String].getOrElse("") match
      case "askUserAnswer" =>
        parse(text).flatMap(_.hcursor.downField("answers").as[List[String]]).toOption match
          case Some(answers) =>
            sessionRefOpt match
              case Some(ref) => IO(ref ! SessionCommand.AskUserResponse("", answers))
              case None => IO.unit
          case None => IO.unit

      case "permissionAnswer" =>
        val approved = parse(text).flatMap(_.hcursor.downField("approved").as[Boolean]).getOrElse(false)
        logger.info(s"Permission answer: ${if approved then "approved" else "denied"}") *> (
          sessionRefOpt match
            case Some(ref) => IO(ref ! SessionCommand.PermissionResponse("", approved))
            case None => IO.unit
        )

      case "setPolicy" =>
        val policyStr = parse(text).flatMap(_.hcursor.downField("policy").as[String]).getOrElse("ask")
        val policy = PermissionPolicy.fromString(policyStr)
        logger.info(s"Permission policy changed to: $policyStr") *>
          permState.setPolicy(policy) *>
          reminderStateRef.update(_.copy(policyReminderPending = true))

      case "interrupt" =>
        logger.info("User interrupted") *> (sessionRefOpt match
          case Some(ref) => sessionStore.getActiveId.flatMap(id => IO(ref ! SessionCommand.Interrupt(id)))
          case None => IO.unit)

      case "command" =>
        val command = parse(text).flatMap(_.hcursor.downField("command").as[String]).getOrElse("")
        command match
          case "clear" =>
            logger.info("Session cleared") *> sessionStore.setActiveMessages(Nil) *>
              reminderStateRef.update(_.copy(sessionStarted = false, highestPressureLevel = 0))
          case _ => IO.unit

      case "setThinking" =>
        val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
        json.hcursor.downField("thinking").focus match
          case Some(t) => thinkingModeRef.set(Some(t))
          case None => thinkingModeRef.set(None)

      case "switchSession" =>
        val sessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
        if sessionId.nonEmpty then
          sessionService.switchSession(sessionId).flatMap { _ =>
            sessionService.sendSessionList(wsSend)
          }.handleErrorWith { e =>
            wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
          }
        else IO.unit

      case "createSession" =>
        val name = parse(text).flatMap(_.hcursor.downField("name").as[String]).getOrElse("New Session")
        sessionService.createSession(name).flatMap { _ =>
          sessionService.sendSessionList(wsSend)
        }.handleErrorWith { e =>
          wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
        }

      case "deleteSession" =>
        val sessionId = parse(text).flatMap(_.hcursor.downField("sessionId").as[String]).getOrElse("")
        if sessionId.nonEmpty then
          sessionService.deleteSession(sessionId).flatMap { _ =>
            sessionService.sendSessionList(wsSend)
          }.handleErrorWith { e =>
            wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
          }
        else IO.unit

      case "renameSession" =>
        val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
        val sessionId = json.hcursor.downField("sessionId").as[String].getOrElse("")
        val newName = json.hcursor.downField("name").as[String].getOrElse("")
        if sessionId.nonEmpty && newName.nonEmpty then
          sessionService.renameSession(sessionId, newName).flatMap { _ =>
            sessionService.sendSessionList(wsSend)
          }.handleErrorWith { e =>
            wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
          }
        else IO.unit

      case "ping" => IO.unit

      case "listAgents" =>
        agentService.listAgents.flatMap { agents =>
          val agentsJson = agents.map { a =>
            io.circe.Json.obj(
              "name" -> a.name.asJson,
              "description" -> a.description.asJson,
              "tools" -> a.tools.asJson,
              "subagents" -> a.subagents.asJson
            )
          }
          wsSend(
            io.circe.Json.obj(
              "type" -> "agentList".asJson,
              "agents" -> agentsJson.asJson
            )
          )
        }

      case "getAgentConfig" =>
        val agentName = parse(text).flatMap(_.hcursor.downField("name").as[String]).getOrElse("")
        if agentName.nonEmpty then
          agentService.getAgentConfig(agentName).flatMap {
            case Some(cfg) =>
              wsSend(
                io.circe.Json.obj(
                  "type" -> "agentConfig".asJson,
                  "name" -> cfg.name.asJson,
                  "configJson" -> cfg.configJson.asJson,
                  "systemMd" -> cfg.systemMd.asJson
                )
              )
            case None => IO.unit
          }
        else IO.unit

      case "createAgent" =>
        val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
        val agentName = json.hcursor.downField("name").as[String].getOrElse("")
        val configJson = json.hcursor.downField("configJson").as[String].getOrElse("")
        val systemMd = json.hcursor.downField("systemMd").as[String].getOrElse("")
        if agentName.nonEmpty then
          agentService.createAgent(agentName, configJson, systemMd).flatMap {
            case Left(err) =>
              wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> err.asJson))
            case Right(_) =>
              wsSend(io.circe.Json.obj("type" -> "agentCreated".asJson, "name" -> agentName.asJson))
          }
        else IO.unit

      case "updateAgent" =>
        val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
        val agentName = json.hcursor.downField("name").as[String].getOrElse("")
        val configJson = json.hcursor.downField("configJson").as[String].getOrElse("")
        val systemMd = json.hcursor.downField("systemMd").as[String].getOrElse("")
        if agentName.nonEmpty then
          agentService.updateAgent(agentName, configJson, systemMd).flatMap {
            case Left(err) =>
              wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> err.asJson))
            case Right(_) =>
              wsSend(io.circe.Json.obj("type" -> "agentUpdated".asJson, "name" -> agentName.asJson))
          }
        else IO.unit

      case "createAgentSession" =>
        val agentName = parse(text).flatMap(_.hcursor.downField("name").as[String]).getOrElse("")
        if agentName.nonEmpty then
          (for
            defnOpt <- sharedResources.agentLibrary.get(agentName)
            defn <- IO.fromOption(defnOpt)(new RuntimeException(s"Agent not found: $agentName"))
            meta <- sessionService.createSession(s"Agent: ${defn.name}", agentName = Some(agentName))
            _ <- sessionService.switchSession(meta.id)
            _ <- sessionService.sendSessionList(wsSend)
          yield ()).handleErrorWith { e =>
            wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> e.getMessage.asJson))
          }
        else IO.unit

      case "getConfig" =>
        configService.getConfig.flatMap { cfg =>
          wsSend(
            io.circe.Json.obj(
              "type" -> "configData".asJson,
              "config" -> cfg.asJson
            )
          )
        }

      case "updateConfig" =>
        val cfg = parse(text).flatMap(_.hcursor.downField("config").as[String]).getOrElse("")
        if cfg.nonEmpty then
          configService.updateConfig(cfg).flatMap {
            case Left(err) =>
              wsSend(io.circe.Json.obj("type" -> "error".asJson, "message" -> err.asJson))
            case Right(_) =>
              wsSend(io.circe.Json.obj("type" -> "configUpdated".asJson, "success" -> true.asJson))
          }
        else IO.unit

      case _ =>
        val json = parse(text).toOption.getOrElse(io.circe.Json.Null)
        val content = json.hcursor.downField("content").as[String].getOrElse("")
        val attachments = json.hcursor.downField("attachments").as[List[io.circe.Json]].getOrElse(Nil)
        val clientMessageId = json.hcursor.downField("clientMessageId").as[Option[String]].getOrElse(None)

        if content.trim == "__interrupt__" then
          sessionRefOpt match
            case Some(ref) => sessionStore.getActiveId.flatMap(id => IO(ref ! SessionCommand.Interrupt(id)))
            case None => IO.unit
        else if content.nonEmpty || attachments.nonEmpty then
          rateLimiter.check("ws").flatMap { allowed =>
            if !allowed then
              logger.warn("Rate limit exceeded") *>
                wsSend(
                  io.circe.Json.obj(
                    "type" -> "error".asJson,
                    "message" -> NebflowError.toUserMessage(NebflowError.RateLimited("websocket")).asJson
                  )
                )
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

              logger.info(s"User message: ${content.take(60)}${if content.length > 60 then "..." else ""}") *>
                logInputHistory(content, attachments) *> (sessionRefOpt match
                  case Some(ref) =>
                    IO(ref ! SessionCommand.UserMessage(content, blocks.toList, clientMessageId))
                  case None => IO.unit)
          }
        else IO.unit
        end if
  end handleMessage

end WebSocketRoutes
