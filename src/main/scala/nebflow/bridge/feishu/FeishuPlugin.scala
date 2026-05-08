package nebflow.bridge.feishu

import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import nebflow.bridge.*
import nebflow.core.NebflowLogger
import nebflow.gateway.SessionMeta

import java.net.URI
import java.net.http.{HttpClient, WebSocket as JWebSocket}
import java.nio.ByteBuffer
import java.util.concurrent.{CompletionStage, LinkedBlockingQueue}

import scala.jdk.CollectionConverters.*

/**
 * Feishu bridge plugin using WebSocket long-connection.
 *
 * No public HTTP endpoint needed — the plugin connects outbound to Feishu's
 * WebSocket gateway, so it works behind NAT / without a public IP.
 *
 * Connection lifecycle:
 *   1. POST /callback/ws/endpoint → get WS URL
 *   2. Connect to WS URL
 *   3. Receive protobuf frames (ping/pong, events)
 *   4. Send ACK for data frames, reply ping with pong
 *   5. Auto-reconnect on disconnect
 */
class FeishuPlugin(globalConfig: FeishuGlobalConfig) extends BridgePlugin:
  val name: String = "feishu"

  private val logger = NebflowLogger.forName("nebflow.bridge.feishu")

  private var ctx: BridgeContext = scala.compiletime.uninitialized

  // Routing table: feishu chatId → sessionId
  private val chatSessionMap: Ref[IO, Map[String, String]] =
    Ref.unsafe[IO, Map[String, String]](Map.empty)

  // HTTP client for sending messages and endpoint API
  private val feishuClient = new FeishuClient(globalConfig)
  private val httpClient = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .build()

  // WebSocket state
  private val wsRef: Ref[IO, Option[JWebSocket]] = Ref.unsafe(None)
  private val runningRef: Ref[IO, Boolean] = Ref.unsafe(false)

  // Per-session text accumulator for streaming responses
  private val textBuffers: Ref[IO, Map[String, String]] = Ref.unsafe(Map.empty)
  private val lastMsgIds: Ref[IO, Map[String, String]] = Ref.unsafe(Map.empty)

  // ===== BridgePlugin interface =====

  def start(ctx: BridgeContext): IO[Unit] =
    this.ctx = ctx
    for
      _ <- logger.info(s"Feishu plugin starting (appId=${globalConfig.appId})")
      _ <- rebuildRoutingTable
      _ <- runningRef.set(true)
      _ <- connectLoop // blocking reconnect loop
    yield ()

  def stop: IO[Unit] =
    for
      _ <- runningRef.set(false)
      _ <- wsRef.get.flatMap {
        case Some(ws) => IO.blocking(ws.sendClose(JWebSocket.NORMAL_CLOSURE, "shutdown"))
        case None => IO.unit
      }
      _ <- logger.info("Feishu plugin stopped")
    yield ()

  def onAgentEvent(sessionId: String, event: Json): IO[Unit] =
    chatSessionMap.get.flatMap { map =>
      // Find chatId bound to this session
      map.find(_._2 == sessionId).map(_._1) match
        case Some(chatId) => handleEvent(sessionId, chatId, event)
        case None => IO.unit
    }

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
        logger.info(s"Feishu routing table: ${mapping.size} binding(s)")
    }

  private def sessionBinding(sessionId: String): IO[Option[(String, Json)]] =
    ctx.sessionMeta(sessionId).map {
      case Some(meta) => meta.bridges.get("feishu").map(chatId => {
        val id = chatId.hcursor.downField("chatId").as[String].getOrElse("")
        id -> chatId
      })
      case None => None
    }

  // ===== WebSocket Connection =====

  private def connectLoop: IO[Unit] =
    runningRef.get.flatMap { running =>
      if !running then IO.unit
      else
        (for
          wsUrl <- fetchEndpoint
          _ <- logger.info(s"Feishu WS connecting...")
          _ <- connectWs(wsUrl)
        yield ()).handleErrorWith { e =>
          logger.warn(s"Feishu WS connection error: ${e.getMessage}")
        } *> IO.sleep(scala.concurrent.duration.Duration(5, scala.concurrent.duration.SECONDS)) *> connectLoop
    }

  /** Fetch WebSocket endpoint URL from Feishu. */
  private def fetchEndpoint: IO[String] =
    IO.blocking {
      val body = Json.obj(
        "app_id" -> globalConfig.appId.asJson,
        "app_secret" -> globalConfig.appSecret.asJson
      ).noSpaces
      val request = java.net.http.HttpRequest.newBuilder()
        .uri(URI.create("https://open.feishu.cn/callback/ws/endpoint"))
        .header("Content-Type", "application/json")
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
        .build()
      val response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
      if response.statusCode() != 200 then
        throw new RuntimeException(s"Feishu endpoint API HTTP ${response.statusCode()}: ${response.body().take(200)}")
      val json = parse(response.body()).getOrElse(Json.obj())
      val code = json.hcursor.downField("code").as[Int].getOrElse(-1)
      if code != 0 then
        throw new RuntimeException(s"Feishu endpoint API error (code=$code): ${response.body().take(300)}")
      json.hcursor.downField("data").downField("url").as[String].getOrElse(
        throw new RuntimeException(s"Feishu endpoint response missing url: ${response.body().take(200)}")
      )
    }

  /** Connect to Feishu WebSocket and process messages until disconnected. */
  private def connectWs(url: String): IO[Unit] =
    feishuClient.getToken.flatMap { token =>
      IO.blocking {
        val incoming = new LinkedBlockingQueue[Array[Byte]]()
        val listener: JWebSocket.Listener = new JWebSocket.Listener {
          override def onBinary(ws: JWebSocket, data: ByteBuffer, last: Boolean): CompletionStage[JWebSocket.Listener] =
            val bytes = new Array[Byte](data.remaining())
            data.get(bytes)
            incoming.put(bytes)
            null

          override def onText(ws: JWebSocket, data: CharSequence, last: Boolean): CompletionStage[JWebSocket.Listener] = null
          override def onOpen(ws: JWebSocket): Unit = ()
          override def onClose(ws: JWebSocket, statusCode: Int, reason: String): CompletionStage[JWebSocket.Listener] = null
          override def onError(ws: JWebSocket, error: Throwable): Unit = ()
          override def onPing(ws: JWebSocket, data: ByteBuffer): CompletionStage[JWebSocket.Listener] =
            ws.sendPong(data)
            null
          override def onPong(ws: JWebSocket, data: ByteBuffer): CompletionStage[JWebSocket.Listener] = null
        }

        val ws = httpClient.newWebSocketBuilder()
          .header("Authorization", s"Bearer $token")
          .buildAsync(URI.create(url), listener)
          .get() // blocking

        (ws, incoming)
      }.flatMap { case (ws, incoming) =>
        wsRef.set(Some(ws)) *> logger.info("Feishu WS connected") *>
          IO.blocking {
            // Message processing loop — blocks until WS closes
            try
              while runningRef.get.unsafeRunSync() do
                val data = incoming.poll(30, java.util.concurrent.TimeUnit.SECONDS)
                if data != null then
                  val frame = FeishuWire.decodeFrame(data)
                  frame.method match
                    case FeishuWire.METHOD_CONTROL =>
                      if frame.payloadType == FeishuWire.PAYLOAD_PING then
                        val pong = FeishuWire.encodeFrame(frame.copy(payloadType = FeishuWire.PAYLOAD_PONG))
                        ws.sendBinary(ByteBuffer.wrap(pong), true)
                    case FeishuWire.METHOD_DATA =>
                      // Send ACK
                      val ack = FeishuWire.ackFrame(frame)
                      ws.sendBinary(ByteBuffer.wrap(ack), true)
                      // Process event
                      if frame.payloadType == FeishuWire.PAYLOAD_EVENT && frame.payload.nonEmpty then
                        val eventJson = new String(frame.payload, "UTF-8")
                        processEvent(eventJson).unsafeRunSync()
            catch
              case _: InterruptedException => // shutdown
              case e: Exception =>
                logger.warn(s"Feishu WS message loop error: ${e.getMessage}").unsafeRunSync()
          }.handleErrorWith { e =>
            logger.warn(s"Feishu WS connection lost: ${e.getMessage}")
          } <* wsRef.set(None)
      }
    }

  // ===== Event Processing =====

  private def processEvent(eventStr: String): IO[Unit] =
    for
      json <- IO.fromEither(parse(eventStr))
      eventType = json.hcursor.downField("header").downField("event_type").as[String].getOrElse("")
      _ <- eventType match
        case "im.message.receive_v1" => handleImMessage(json)
        case _ => IO.unit
    yield ()

  private def handleImMessage(eventJson: Json): IO[Unit] =
    val ec = eventJson.hcursor.downField("event")
    val senderOpenId = ec.downField("sender").downField("sender_id").downField("open_id").as[String].getOrElse("")
    val msgCursor = ec.downField("message")
    val chatId = msgCursor.downField("chat_id").as[String].getOrElse("")
    val chatType = msgCursor.downField("chat_type").as[String].getOrElse("p2p")
    val isGroup = chatType == "group"
    val messageType = msgCursor.downField("message_type").as[String].getOrElse("")
    val contentStr = msgCursor.downField("content").as[String].getOrElse("{}")

    messageType match
      case "text" =>
        val text = parse(contentStr).toOption
          .flatMap(_.hcursor.downField("text").as[String].toOption)
          .getOrElse("")
        if text.nonEmpty then handleUserMessage(chatId, senderOpenId, text, isGroup)
        else IO.unit
      case _ =>
        logger.debug(s"Ignoring non-text Feishu message (type=$messageType)").void

  private def handleUserMessage(chatId: String, senderOpenId: String, text: String, isGroup: Boolean): IO[Unit] =
    // Group chats: all users allowed. P2P: check allowlist.
    val authCheck = if isGroup then IO.pure(true) else IO.pure(globalConfig.allowsUser(senderOpenId))
    authCheck.flatMap { allowed =>
      if !allowed then
        logger.warn(s"Feishu message from unauthorized user: $senderOpenId").void
      else
        chatSessionMap.get.map(_.get(chatId)).flatMap {
          case Some(sessionId) =>
            logger.info(s"Feishu → session $sessionId: ${text.take(60)}") *>
              ctx.injectMessage(sessionId, text, Some(senderOpenId))
          case None =>
            logger.debug(s"No session bound to Feishu chat $chatId").void
        }
    }

  // ===== Agent Event → Feishu =====

  private def handleEvent(sessionId: String, chatId: String, event: Json): IO[Unit] =
    val hc = event.hcursor
    val eventType = hc.downField("type").as[String].getOrElse("")

    sessionBinding(sessionId).flatMap {
      case Some((_, cfgJson)) =>
        val enabled = cfgJson.hcursor.downField("enabled").as[Option[Boolean]].toOption.flatten.getOrElse(true)
        val notifyEvents = cfgJson.hcursor.downField("notifyEvents").as[Option[List[String]]].toOption.flatten
          .getOrElse(List("aiResponse", "askUser", "permissionRequest"))
        val chatType = cfgJson.hcursor.downField("chatType").as[Option[String]].toOption.flatten.getOrElse("p2p")
        val chatIdType = chatType match
          case "group" => "chat_id"
          case _ => "open_id"

        if !enabled then IO.unit
        else eventType match
          case "textDelta" =>
            if notifyEvents.contains("aiResponse") then handleTextDelta(sessionId, chatId, chatIdType, hc)
            else IO.unit
          case "done" =>
            if notifyEvents.contains("aiResponse") then handleDone(sessionId, chatId, chatIdType, hc)
            else IO.unit
          case "askUser" =>
            if notifyEvents.contains("askUser") then handleAskUser(sessionId, chatId, chatIdType, hc)
            else IO.unit
          case "permissionRequest" =>
            if notifyEvents.contains("permissionRequest") then handlePermissionRequest(sessionId, chatId, chatIdType, hc)
            else IO.unit
          case _ => IO.unit
      case None => IO.unit
    }

  private def handleTextDelta(sessionId: String, chatId: String, chatIdType: String, hc: HCursor): IO[Unit] =
    val delta = hc.downField("delta").as[String].getOrElse("")
    if delta.isEmpty then IO.unit
    else
      textBuffers.update(_.updatedWith(sessionId)(_.map(_ + delta).orElse(Some(delta))))

  private def handleDone(sessionId: String, chatId: String, chatIdType: String, hc: HCursor): IO[Unit] =
    textBuffers.get.flatMap(_.get(sessionId) match
      case Some(text) if text.nonEmpty =>
        for
          _ <- textBuffers.update(_ - sessionId)
          _ <- flushText(sessionId, chatId, chatIdType, text)
        yield ()
      case _ => IO.unit
    )

  private def flushText(sessionId: String, chatId: String, chatIdType: String, text: String): IO[Unit] =
    val displayText = if text.length > 4000 then text.take(3900) + "\n...(truncated)" else text
    feishuClient.sendTextMessage(chatId, chatIdType, displayText).flatMap { msgId =>
      lastMsgIds.update(_.updated(sessionId, msgId))
    }.handleErrorWith { e =>
      logger.warn(s"Failed to send Feishu message: ${e.getMessage}").void
    }

  private def handleAskUser(sessionId: String, chatId: String, chatIdType: String, hc: HCursor): IO[Unit] =
    // Flush any pending text first
    textBuffers.get.flatMap(_.get(sessionId) match
      case Some(text) if text.nonEmpty =>
        textBuffers.update(_ - sessionId) *> flushText(sessionId, chatId, chatIdType, text)
      case _ => IO.unit
    ) *> {
      val question = hc.downField("question").as[String].getOrElse("")
      if question.nonEmpty then
        feishuClient.sendTextMessage(chatId, chatIdType, s"❓ $question").void
          .handleErrorWith(e => logger.warn(s"Failed to send askUser: ${e.getMessage}"))
      else IO.unit
    }

  private def handlePermissionRequest(sessionId: String, chatId: String, chatIdType: String, hc: HCursor): IO[Unit] =
    val tool = hc.downField("tool").as[String].getOrElse("")
    val args = hc.downField("args").as[String].getOrElse("")
    val card = Json.obj(
      "config" -> Json.obj("wide_screen_mode" -> true.asJson),
      "header" -> Json.obj(
        "title" -> Json.obj("tag" -> "plain_text".asJson, "content" -> "⚠ 权限请求".asJson),
        "template" -> "orange".asJson
      ),
      "elements" -> List(
        Json.obj("tag" -> "div".asJson, "text" -> Json.obj("tag" -> "plain_text".asJson, "content" -> s"工具: $tool\n参数: ${args.take(200)}".asJson)),
        Json.obj("tag" -> "action".asJson, "actions" -> Json.arr(
          Json.obj(
            "tag" -> "button".asJson, "text" -> Json.obj("tag" -> "plain_text".asJson, "content" -> "✅ 允许".asJson),
            "type" -> "primary".asJson, "value" -> Json.obj("action" -> "permissionApprove".asJson, "sessionId" -> sessionId.asJson)
          ),
          Json.obj(
            "tag" -> "button".asJson, "text" -> Json.obj("tag" -> "plain_text".asJson, "content" -> "❌ 拒绝".asJson),
            "type" -> "danger".asJson, "value" -> Json.obj("action" -> "permissionDeny".asJson, "sessionId" -> sessionId.asJson)
          )
        ))
      ).asJson
    )
    feishuClient.sendCardMessage(chatId, chatIdType, card).void
      .handleErrorWith(e => logger.warn(s"Failed to send permission card: ${e.getMessage}"))

end FeishuPlugin
