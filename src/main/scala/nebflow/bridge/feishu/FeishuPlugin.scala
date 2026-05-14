package nebflow.bridge.feishu

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import com.lark.oapi.ws.Client as WsClient
import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import nebflow.bridge.*
import nebflow.core.NebflowLogger

import java.net.http.HttpClient

import scala.jdk.CollectionConverters.*

/**
 * Feishu bridge plugin using the official Java SDK's WebSocket long-connection client.
 *
 * Uses com.larksuite.oapi:oapi-sdk for:
 * - WebSocket connection with OkHttp (supports permessage-deflate compression)
 * - Protobuf frame encoding/decoding
 * - Ping/pong keepalive
 * - Event dispatching
 */
class FeishuPlugin(globalConfig: FeishuGlobalConfig) extends BridgePlugin:
  val name: String = "feishu"

  private val logger = NebflowLogger.forName("nebflow.bridge.feishu")

  private var ctx: BridgeContext = scala.compiletime.uninitialized

  // Routing table: feishu chatId → sessionId
  private val chatSessionMap: Ref[IO, Map[String, String]] =
    Ref.unsafe[IO, Map[String, String]](Map.empty)

  // HTTP client for sending messages
  private val feishuClient = new FeishuClient(globalConfig)

  private val httpClient = HttpClient
    .newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .build()

  // Per-session clients keyed by appId
  private val sessionClientsRef: Ref[IO, Map[String, FeishuClient]] =
    Ref.unsafe[IO, Map[String, FeishuClient]](Map.empty)

  private def getSendClient(bridgesCfg: Option[Json]): IO[FeishuClient] =
    bridgesCfg.flatMap { cfg =>
      val appId = cfg.hcursor.downField("appId").as[String].toOption.filter(_.nonEmpty)
      val appSecret = cfg.hcursor.downField("appSecret").as[String].toOption.filter(_.nonEmpty)
      (appId, appSecret) match
        case (Some(id), Some(secret)) => Some((id, secret))
        case _ => None
    } match
      case Some((appId, appSecret)) =>
        sessionClientsRef.get.map(_.get(appId)).flatMap {
          case Some(client) => IO.pure(client)
          case None =>
            val cfg = FeishuGlobalConfig(appId, appSecret)
            val client = new FeishuClient(cfg)
            sessionClientsRef.update(_ + (appId -> client)).as(client)
        }
      case None => IO.pure(feishuClient)

  // Per-session text accumulator for streaming responses
  private val textBuffers: Ref[IO, Map[String, String]] = Ref.unsafe(Map.empty)
  private val lastMsgIds: Ref[IO, Map[String, String]] = Ref.unsafe(Map.empty)

  // Dedup: track processed message IDs (keep last N to bound memory)
  private val processedMsgIds: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
  private val MaxProcessedIds = 500

  // SDK WS client reference
  private var wsClient: WsClient = scala.compiletime.uninitialized

  // ===== BridgePlugin interface =====

  def start(ctx: BridgeContext): IO[Unit] =
    this.ctx = ctx
    if globalConfig.appId.contains("xxx") || globalConfig.appId.isEmpty then
      logger.info(s"[feishu] plugin skipped (appId placeholder or empty)")
      IO.unit
    else
      for
        _ <- logger.info(s"[feishu] plugin starting (appId=${globalConfig.appId})")
        _ <- rebuildRoutingTable
        _ <- startWsClient
      yield ()

  def stop: IO[Unit] =
    // SDK WS client has no public disconnect; just let the daemon thread die
    logger.info("[feishu] plugin stopped")

  def onAgentEvent(sessionId: String, event: Json): IO[Unit] =
    chatSessionMap.get.flatMap { map =>
      map.find(_._2 == sessionId).map(_._1) match
        case Some(chatId) => handleEvent(sessionId, chatId, event)
        case None => IO.unit
    }

  override def refreshRoutes: IO[Unit] = rebuildRoutingTable

  // ===== Routing =====

  private def rebuildRoutingTable: IO[Unit] =
    ctx.listSessions.flatMap { sessions =>
      val mapping = sessions.flatMap { s =>
        s.bridges.get("feishu").flatMap { cfgJson =>
          val enabled = cfgJson.hcursor.downField("enabled").as[Option[Boolean]].toOption.flatten.getOrElse(true)
          val chatId = cfgJson.hcursor.downField("chatId").as[Option[String]].toOption.flatten.getOrElse("")
          if enabled && chatId.nonEmpty then Some(chatId -> s.id) else None
        }
      }.toMap
      chatSessionMap.set(mapping) *>
        logger.info(s"[feishu] routing table: ${mapping.size} binding(s)")
    }

  private def sessionBinding(sessionId: String): IO[Option[(String, Json)]] =
    ctx.sessionMeta(sessionId).map {
      case Some(meta) =>
        meta.bridges
          .get("feishu")
          .map(cfg =>
            val id = cfg.hcursor.downField("chatId").as[String].getOrElse("")
            id -> cfg
          )
      case None => None
    }

  // ===== SDK WS Client =====

  private def startWsClient: IO[Unit] =
    IO.blocking {
      val dispatcher = EventDispatcher
        .newBuilder("", "")
        .onP2MessageReceiveV1(
          new ImService.P2MessageReceiveV1Handler:
            def handle(event: P2MessageReceiveV1): Unit =
              // Null-safe access: SDK may dispatch events with missing fields for
              // unrecognized subtypes or during reconnect replays.
              val eventBody = Option(event).flatMap(e => Option(e.getEvent))
              val senderOpt = eventBody.flatMap(e => Option(e.getSender))
              val senderId = senderOpt.flatMap(s => Option(s.getSenderId)).map(_.getOpenId).getOrElse("")
              val senderType = senderOpt.map(_.getSenderType).orNull
              val msgOpt = eventBody.flatMap(e => Option(e.getMessage))
              val chatId = msgOpt.map(_.getChatId).getOrElse("")
              val chatType = msgOpt.map(_.getChatType).orNull
              val msgType = msgOpt.map(_.getMessageType).orNull
              val content = msgOpt.map(_.getContent).getOrElse("")
              val messageId = msgOpt.map(_.getMessageId).getOrElse("")
              val isGroup = "group" == chatType
              if chatId.nonEmpty && msgType != null then
                // Skip messages sent by the bot itself to prevent echo loops
                if senderType != "app" then
                  // Dedup: skip already-processed messages (e.g. history replay on reconnect)
                  val isDup = processedMsgIds.get.map(_.contains(messageId)).unsafeRunSync()
                  if !isDup then
                    processedMsgIds
                      .update { ids =>
                        val updated = ids + messageId
                        if updated.size > MaxProcessedIds then updated.drop(updated.size - MaxProcessedIds)
                        else updated
                      }
                      .unsafeRunSync()
                    logger
                      .info(
                        s"[feishu] message received: chatId=$chatId chatType=$chatType msgType=$msgType sender=$senderId"
                      )
                      .unsafeRunSync()
                    if msgType == "text" then
                      val text = parse(content).toOption
                        .flatMap(_.hcursor.downField("text").as[String].toOption)
                        .getOrElse("")
                      if text.nonEmpty then handleUserMessage(chatId, senderId, text, isGroup).unsafeRunSync()
                  else logger.debug(s"[feishu] skipping duplicate message: $messageId").unsafeRunSync()
                else logger.debug(s"[feishu] skipping bot message: chatId=$chatId").unsafeRunSync()
              else
                logger
                  .debug(s"[feishu] skipping event with missing fields: chatId=$chatId msgType=$msgType")
                  .unsafeRunSync()
              end if
            end handle
        )
        .build()

      wsClient = new WsClient.Builder(globalConfig.appId, globalConfig.appSecret)
        .eventHandler(dispatcher)
        .autoReconnect(true)
        .build()

      // Start in a background thread — SDK manages its own threads
      val thread = new Thread(
        () =>
          try wsClient.start()
          catch
            case e: Exception =>
              logger.warn(s"[feishu] WS client error: ${e.getMessage}").unsafeRunSync()
        ,
        "feishu-ws"
      )
      thread.setDaemon(true)
      thread.start()
      logger.info("[feishu] WS client started via SDK").unsafeRunSync()
    }

  /** Process an im.message.receive_v1 event payload from the SDK dispatcher. */
  private def processImMessage(payload: String): IO[Unit] =
    for
      json <- IO.fromEither(parse(payload))
      _ <- logger.info(s"[feishu] im.message.receive_v1: ${payload.take(300)}")
      ec = json.hcursor.downField("event")
      senderCursor = ec.downField("sender")
      senderOpenId = senderCursor.downField("sender_id").downField("open_id").as[String].getOrElse("")
      msgCursor = ec.downField("message")
      chatId = msgCursor.downField("chat_id").as[String].getOrElse("")
      chatType = msgCursor.downField("chat_type").as[String].getOrElse("p2p")
      isGroup = chatType == "group"
      messageType = msgCursor.downField("message_type").as[String].getOrElse("")
      contentStr = msgCursor.downField("content").as[String].getOrElse("{}")
      _ <- messageType match
        case "text" =>
          val text = parse(contentStr).toOption
            .flatMap(_.hcursor.downField("text").as[String].toOption)
            .getOrElse("")
          if text.nonEmpty then handleUserMessage(chatId, senderOpenId, text, isGroup)
          else IO.unit
        case _ =>
          logger.debug("[feishu] ignoring non-text message (type=$messageType)").void
    yield ()

  private def handleUserMessage(chatId: String, senderOpenId: String, text: String, isGroup: Boolean): IO[Unit] =
    val authCheck = if isGroup then IO.pure(true) else IO.pure(globalConfig.allowsUser(senderOpenId))
    authCheck.flatMap { allowed =>
      if !allowed then logger.warn(s"[feishu] message from unauthorized user: $senderOpenId").void
      else
        chatSessionMap.get.map(_.get(chatId)).flatMap {
          case Some(sessionId) =>
            logger.info(s"[feishu] → session $sessionId: ${text.take(60)}") *>
              ctx.injectMessage(sessionId, s"[飞书消息] $text", Some(senderOpenId))
          case None =>
            logger.debug("[feishu] no session bound to chat $chatId").void
        }
    }

  // ===== Agent Event → Feishu =====

  private def handleEvent(sessionId: String, chatId: String, event: Json): IO[Unit] =
    val hc = event.hcursor
    val eventType = hc.downField("type").as[String].getOrElse("")

    sessionBinding(sessionId).flatMap {
      case Some((_, cfgJson)) =>
        val enabled = cfgJson.hcursor.downField("enabled").as[Option[Boolean]].toOption.flatten.getOrElse(true)
        val notifyEvents = cfgJson.hcursor
          .downField("notifyEvents")
          .as[Option[List[String]]]
          .toOption
          .flatten
          .getOrElse(List("aiResponse", "askUser", "permissionRequest"))
        val chatType = cfgJson.hcursor.downField("chatType").as[Option[String]].toOption.flatten.getOrElse("p2p")
        val chatIdType = chatType match
          case "group" => "chat_id"
          case _ => "open_id"

        if !enabled then IO.unit
        else
          getSendClient(Some(cfgJson)).flatMap { client =>
            eventType match
              case "textDelta" =>
                if notifyEvents.contains("aiResponse") then handleTextDelta(sessionId, chatId, chatIdType, hc, client)
                else IO.unit
              case "done" =>
                if notifyEvents.contains("aiResponse") then handleDone(sessionId, chatId, chatIdType, hc, client)
                else IO.unit
              case "askUser" =>
                if notifyEvents.contains("askUser") then handleAskUser(sessionId, chatId, chatIdType, hc, client)
                else IO.unit
              case "permissionRequest" =>
                if notifyEvents.contains("permissionRequest") then
                  handlePermissionRequest(sessionId, chatId, chatIdType, hc, client)
                else IO.unit
              case _ => IO.unit
          }
      case None => IO.unit
    }

  end handleEvent

  private def handleTextDelta(
    sessionId: String,
    chatId: String,
    chatIdType: String,
    hc: HCursor,
    client: FeishuClient
  ): IO[Unit] =
    val delta = hc.downField("delta").as[String].getOrElse("")
    if delta.isEmpty then IO.unit
    else textBuffers.update(_.updatedWith(sessionId)(_.map(_ + delta).orElse(Some(delta))))

  private def handleDone(
    sessionId: String,
    chatId: String,
    chatIdType: String,
    hc: HCursor,
    client: FeishuClient
  ): IO[Unit] =
    textBuffers.get.flatMap(_.get(sessionId) match
      case Some(text) if text.nonEmpty =>
        for
          _ <- textBuffers.update(_ - sessionId)
          _ <- flushText(sessionId, chatId, chatIdType, text, client)
        yield ()
      case _ => IO.unit)

  private def flushText(
    sessionId: String,
    chatId: String,
    chatIdType: String,
    text: String,
    client: FeishuClient
  ): IO[Unit] =
    val displayText = if text.length > 4000 then text.take(3900) + "\n...(truncated)" else text
    client
      .sendTextMessage(chatId, chatIdType, displayText)
      .flatMap { msgId =>
        lastMsgIds.update(_.updated(sessionId, msgId))
      }
      .handleErrorWith { e =>
        logger.warn(s"[feishu] failed to send message: ${e.getMessage}").void
      }

  private def handleAskUser(
    sessionId: String,
    chatId: String,
    chatIdType: String,
    hc: HCursor,
    client: FeishuClient
  ): IO[Unit] =
    textBuffers.get.flatMap(_.get(sessionId) match
      case Some(text) if text.nonEmpty =>
        textBuffers.update(_ - sessionId) *> flushText(sessionId, chatId, chatIdType, text, client)
      case _ => IO.unit) *> {
      val question = hc.downField("question").as[String].getOrElse("")
      if question.nonEmpty then
        client
          .sendTextMessage(chatId, chatIdType, s"❓ $question")
          .void
          .handleErrorWith(e => logger.warn(s"[feishu] failed to send askUser: ${e.getMessage}"))
      else IO.unit
    }

  private def handlePermissionRequest(
    sessionId: String,
    chatId: String,
    chatIdType: String,
    hc: HCursor,
    client: FeishuClient
  ): IO[Unit] =
    val tool = hc.downField("tool").as[String].getOrElse("")
    val args = hc.downField("args").as[String].getOrElse("")
    val card = Json.obj(
      "config" -> Json.obj("wide_screen_mode" -> true.asJson),
      "header" -> Json.obj(
        "title" -> Json.obj("tag" -> "plain_text".asJson, "content" -> "⚠ 权限请求".asJson),
        "template" -> "orange".asJson
      ),
      "elements" -> List(
        Json.obj(
          "tag" -> "div".asJson,
          "text" -> Json.obj("tag" -> "plain_text".asJson, "content" -> s"工具: $tool\n参数: ${args.take(200)}".asJson)
        ),
        Json.obj(
          "tag" -> "action".asJson,
          "actions" -> Json.arr(
            Json.obj(
              "tag" -> "button".asJson,
              "text" -> Json.obj("tag" -> "plain_text".asJson, "content" -> "✅ 允许".asJson),
              "type" -> "primary".asJson,
              "value" -> Json.obj("action" -> "permissionApprove".asJson, "sessionId" -> sessionId.asJson)
            ),
            Json.obj(
              "tag" -> "button".asJson,
              "text" -> Json.obj("tag" -> "plain_text".asJson, "content" -> "❌ 拒绝".asJson),
              "type" -> "danger".asJson,
              "value" -> Json.obj("action" -> "permissionDeny".asJson, "sessionId" -> sessionId.asJson)
            )
          )
        )
      ).asJson
    )
    client
      .sendCardMessage(chatId, chatIdType, card)
      .void
      .handleErrorWith(e => logger.warn(s"[feishu] failed to send permission card: ${e.getMessage}"))
  end handlePermissionRequest

end FeishuPlugin
