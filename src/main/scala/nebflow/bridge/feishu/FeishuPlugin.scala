package nebflow.bridge.feishu

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.lark.oapi.Client as SdkClient
import com.lark.oapi.core.token.AccessTokenType
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler
import com.lark.oapi.event.cardcallback.model.{CallBackToast, P2CardActionTrigger, P2CardActionTriggerResponse}
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import com.lark.oapi.ws.Client as WsClient
import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import nebflow.bridge.*
import scala.concurrent.duration.*
import nebflow.core.NebflowLogger

import scala.jdk.CollectionConverters.*

/**
 * Feishu bridge plugin using the official Java SDK's WebSocket long-connection client.
 *
 * Design (inspired by OpenClaw):
 * - Per-turn activation: a reply dispatcher is created only when a Feishu message
 *   triggers a conversation turn, and destroyed on completion. This ensures only
 *   Feishu-triggered conversations get pushed to Feishu — web UI conversations
 *   are never forwarded.
 * - Streaming: text deltas are accumulated and the card is patched with throttle
 *   (300ms), giving near-real-time feedback in the Feishu chat.
 */
class FeishuPlugin(globalConfig: FeishuGlobalConfig) extends BridgePlugin:
  val name: String = "feishu"

  private val logger = NebflowLogger.forName("nebflow.bridge.feishu")

  private var ctx: BridgeContext = scala.compiletime.uninitialized

  // Static routing: feishu chatId → sessionId
  private val chatSessionMap: Ref[IO, Map[String, String]] =
    Ref.unsafe[IO, Map[String, String]](Map.empty)

  // Per-turn reply dispatchers: sessionId → FeishuReplyDispatcher
  // Only present while a Feishu-triggered turn is active.
  private val activeDispatchers: Ref[IO, Map[String, FeishuReplyDispatcher]] =
    Ref.unsafe[IO, Map[String, FeishuReplyDispatcher]](Map.empty)

  // Per-session clients keyed by appId (for multi-app setups)
  private val sessionClientsRef: Ref[IO, Map[String, FeishuClient]] =
    Ref.unsafe[IO, Map[String, FeishuClient]](Map.empty)

  // Default client using global config
  private val feishuClient = new FeishuClient(globalConfig)

  // Raw SDK client for direct API calls
  private val rawSdkClient: SdkClient = SdkClient.newBuilder(globalConfig.appId, globalConfig.appSecret).build()

  // Bot's own open_id, auto-detected on startup
  private val botOpenId: Ref[IO, Option[String]] = Ref.unsafe(None)

  // Dedup: track processed message IDs
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
        _ <- fetchBotOpenId
        _ <- rebuildRoutingTable
        _ <- startWsClient
      yield ()

  def stop: IO[Unit] =
    logger.info("[feishu] plugin stopped")

  /**
   * Only forward events to Feishu if there's an active dispatcher for this session,
   * meaning the current turn was triggered by a Feishu message.
   */
  def onAgentEvent(sessionId: String, event: Json): IO[Unit] =
    activeDispatchers.get.flatMap(_.get(sessionId) match
      case Some(dispatcher) => dispatchToFeishu(sessionId, dispatcher, event)
      case None             => IO.unit // Not a Feishu-triggered turn — skip
    )

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
        (if mapping.isEmpty then
           logger.info("[feishu] routing table: no bindings")
         else
           mapping.toList.traverse_ { case (chatId, sid) =>
             logger.info(s"[feishu] routing: chat ${chatId.take(12)}... → session ${sid.take(8)}...")
           })
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

  private def getSendClient(bridgesCfg: Option[Json]): IO[FeishuClient] =
    bridgesCfg.flatMap { cfg =>
      val appId = cfg.hcursor.downField("appId").as[String].toOption.filter(_.nonEmpty)
      val appSecret = cfg.hcursor.downField("appSecret").as[String].toOption.filter(_.nonEmpty)
      (appId, appSecret) match
        case (Some(id), Some(secret)) => Some((id, secret))
        case _                        => None
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

  // ===== Bot Identity =====

  private def fetchBotOpenId: IO[Unit] =
    IO.blocking {
      rawSdkClient.get("/open-apis/bot/v3/info/", null, AccessTokenType.Tenant)
    }.flatMap { resp =>
      val body = resp.getBody
      if body == null then
        logger.warn("[feishu] getBotInfo: response body is null")
        IO.unit
      else
        val bodyStr = new String(body, "UTF-8")
        IO.fromEither(io.circe.parser.parse(bodyStr)).flatMap { json =>
          val code = json.hcursor.downField("code").as[Int].getOrElse(-1)
          if code != 0 then
            val msg = json.hcursor.downField("msg").as[String].getOrElse("unknown")
            logger.warn(s"[feishu] getBotInfo API error (code=$code): $msg")
          else
            val openId = json.hcursor.downField("bot").downField("open_id").as[String].toOption
            openId match
              case Some(id) =>
                botOpenId.set(Some(id)) *> logger.info(s"[feishu] bot open_id detected: $id")
              case None =>
                logger.warn("[feishu] could not detect bot open_id; group mention filtering will use fallback")
        }
    }.handleErrorWith { e =>
      logger.warn(s"[feishu] fetchBotOpenId error: ${e.getClass.getSimpleName}: ${e.getMessage}")
    }

  // ===== SDK WS Client =====

  private def startWsClient: IO[Unit] =
    wsMonitorLoop.void

  /** Build a fresh EventDispatcher with all handlers. */
  private def buildDispatcher: EventDispatcher =
    EventDispatcher
      .newBuilder("", "")
      .onP2MessageReceiveV1(
        new ImService.P2MessageReceiveV1Handler:
          def handle(event: P2MessageReceiveV1): Unit =
            try handleWsEvent(event)
            catch
              case e: Throwable =>
                logger.warn(s"[feishu] WS event handler error: ${e.getClass.getSimpleName}: ${e.getMessage}")
                  .unsafeRunSync()
      )
      // Register no-op handlers for reaction events to avoid HandlerNotFoundException
      .onP2MessageReactionCreatedV1(new ImService.P2MessageReactionCreatedV1Handler:
        def handle(event: com.lark.oapi.service.im.v1.model.P2MessageReactionCreatedV1): Unit = ()
      )
      .onP2MessageReactionDeletedV1(new ImService.P2MessageReactionDeletedV1Handler:
        def handle(event: com.lark.oapi.service.im.v1.model.P2MessageReactionDeletedV1): Unit = ()
      )
      .onP2CardActionTrigger(new P2CardActionTriggerHandler:
        override def handle(event: P2CardActionTrigger): P2CardActionTriggerResponse =
          try handleCardAction(event)
          catch
            case e: Throwable =>
              logger.warn(s"[feishu] card action handler error: ${e.getClass.getSimpleName}: ${e.getMessage}")
                .unsafeRunSync()
              new P2CardActionTriggerResponse()
      )
      .build()

  /**
   * Start WS client with retry. Uses SDK's built-in autoReconnect for normal operation.
   * Only retries on initial connection failure.
   */
  private def wsMonitorLoop: IO[Unit] =
    def connectWithBackoff(attempt: Int): IO[Unit] =
      val delay = math.min(math.pow(2, attempt - 1).toInt, 30)
      (if attempt > 1 then
        logger.info(s"[feishu] WS connect attempt $attempt in ${delay}s...") *> IO.sleep(delay.seconds)
      else IO.unit) *>
      IO.blocking {
        val dispatcher = buildDispatcher
        wsClient = new WsClient.Builder(globalConfig.appId, globalConfig.appSecret)
          .eventHandler(dispatcher)
          .autoReconnect(true)
          .build()
        wsClient.start()
      }.attempt.flatMap {
        case Right(_) =>
          logger.info("[feishu] WS client started")
        case Left(e) =>
          logger.warn(s"[feishu] WS start failed: ${e.getClass.getSimpleName}: ${e.getMessage}") *>
            connectWithBackoff(attempt + 1)
      }

    connectWithBackoff(1)

  /** Handle interactive card action (button click) from Feishu. */
  private def handleCardAction(event: P2CardActionTrigger): P2CardActionTriggerResponse =
    val data = event.getEvent
    if data == null then return new P2CardActionTriggerResponse()

    val action = data.getAction
    if action == null then return new P2CardActionTriggerResponse()

    val value = action.getValue
    if value == null || value.getOrDefault("action", "") != "interrupt" then
      return new P2CardActionTriggerResponse()

    val context = data.getContext
    val chatId = if context != null then Option(context.getOpenChatId).getOrElse("") else ""

    if chatId.nonEmpty then
      val sessionId = chatSessionMap.get.map(_.get(chatId)).unsafeRunSync()
      sessionId match
        case Some(sid) =>
          logger.info(s"[feishu] card interrupt: chat=${chatId.take(12)}... → session=${sid.take(8)}...")
            .unsafeRunSync()
          ctx.interruptAgent(sid).unsafeRunSync()
        case None =>
          logger.warn(s"[feishu] card interrupt: no session for chat ${chatId.take(12)}...")
            .unsafeRunSync()

    // Return a toast to acknowledge
    val toast = new CallBackToast()
    toast.setType("success")
    toast.setContent("已停止")
    val resp = new P2CardActionTriggerResponse()
    resp.setToast(toast)
    resp

  /** Handle an incoming WS event from Feishu SDK. Runs on SDK's internal thread. */
  private def handleWsEvent(event: P2MessageReceiveV1): Unit =
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

    // Parse mentions
    val mentionsArr =
      msgOpt.flatMap(m => Option(m.getMentions)).map(_.toSeq).getOrElse(Seq.empty)
    val mentionedOpenIds = mentionsArr.flatMap(m => Option(m.getId).flatMap(id => Option(id.getOpenId)))

    if chatId.nonEmpty && msgType != null then
      // Skip bot's own messages to prevent echo loops
      if senderType == "app" then
        return // skip silently — bot echo
      end if

      logger.info(s"[feishu] ws-event: chat=$chatId type=$msgType sender=${senderId.take(10)} msgId=${messageId.take(10)}").unsafeRunSync()

      // Dedup
      val isDup = processedMsgIds.get.map(_.contains(messageId)).unsafeRunSync()
      if !isDup then
        processedMsgIds
          .update { ids =>
            val updated = ids + messageId
            if updated.size > MaxProcessedIds then updated.drop(updated.size - MaxProcessedIds)
            else updated
          }
          .unsafeRunSync()

        // Group mention filter
        val isMentioned =
          if isGroup then
            botOpenId.get.map {
              case Some(botId) => mentionedOpenIds.contains(botId)
              case None        => mentionedOpenIds.nonEmpty
            }.unsafeRunSync()
          else true

        if isMentioned then
          msgType match
            case "text" =>
              val rawText = parse(content).toOption
                .flatMap(_.hcursor.downField("text").as[String].toOption)
                .getOrElse("")
              val mentionKeys = mentionsArr.flatMap(m => Option(m.getKey)).toSet
              val cleanText = mentionKeys.foldLeft(rawText)((t, key) => t.replace(key, "")).trim
              if cleanText.nonEmpty then
                logger.info(s"[feishu] inbound: chat=$chatId sender=${senderId.take(10)} msg=${messageId.take(10)} text=${cleanText.take(40)}").unsafeRunSync()
                handleUserMessage(chatId, senderId, messageId, cleanText, isGroup).unsafeRunSync()
            case _ =>
              // Non-text messages (image, file, etc.) — log for now
              logger.debug(s"[feishu] non-text message type: $msgType").unsafeRunSync()
        else
          logger.debug(s"[feishu] skipped (not mentioned)").unsafeRunSync()
      else
        logger.debug(s"[feishu] skipped (duplicate)").unsafeRunSync()
      end if
    end if
  end handleWsEvent

  // ===== Inbound: Feishu → Agent =====

  private val InterruptKeywords = Set("停止", "中断", "stop", "cancel", "abort", "interrupt")

  private def handleUserMessage(
    chatId: String,
    senderOpenId: String,
    userMessageId: String,
    text: String,
    isGroup: Boolean
  ): IO[Unit] =
    val authCheck =
      if isGroup then IO.pure(true)
      else IO.pure(globalConfig.allowsUser(senderOpenId))

    authCheck.flatMap { allowed =>
      if !allowed then logger.warn(s"[feishu] unauthorized user: $senderOpenId").void
      else
        chatSessionMap.get.flatMap { routing =>
          routing.get(chatId) match
            case Some(sessionId) =>
              val normalizedText = text.trim.toLowerCase
              if InterruptKeywords.contains(normalizedText) then
                // Interrupt current agent turn
                logger.info(s"[feishu] interrupt: chat=${chatId.take(12)}... → session=${sessionId.take(8)}...") *>
                  ctx.interruptAgent(sessionId) *>
                  activeDispatchers.get.flatMap(_.get(sessionId).map(_.flushAndClose()).getOrElse(IO.unit)) *>
                  feishuClient.sendTextMessage(chatId, "chat_id", "已中断").void
              else
                logger.info(s"[feishu] route: chat=${chatId.take(12)}... → session=${sessionId.take(8)}...") *>
                  activeDispatchers.get.flatMap(_.get(sessionId).map(_.flushAndClose()).getOrElse(IO.unit)) *>
                  createDispatcher(sessionId, chatId, userMessageId) *>
                  ctx.injectMessage(sessionId, s"[飞书消息] $text", Some(senderOpenId))
            case None =>
              logger.warn(s"[feishu] no session bound to chat ${chatId.take(12)}...").void
        }
    }

  /** Create a per-turn reply dispatcher for this session. */
  private def createDispatcher(sessionId: String, chatId: String, userMessageId: String): IO[Unit] =
    sessionBinding(sessionId).flatMap {
      case Some((_, cfgJson)) =>
        // Both group and p2p chats use oc_ prefixed chat_id
        val chatIdType = "chat_id"
        getSendClient(Some(cfgJson)).flatMap { client =>
          val dispatcher = new FeishuReplyDispatcher(client, chatId, chatIdType, sessionId, userMessageId)
          activeDispatchers.update(_.updated(sessionId, dispatcher)) *>
            // Show typing indicator (non-blocking, errors logged)
            dispatcher.showTyping()
        }
      case None => IO.unit
    }

  // ===== Outbound: Agent → Feishu (per-turn dispatch) =====

  private def dispatchToFeishu(
    sessionId: String,
    dispatcher: FeishuReplyDispatcher,
    event: Json
  ): IO[Unit] =
    val eventType = event.hcursor.downField("type").as[String].getOrElse("")
    eventType match
      case "textDelta" =>
        val delta = event.hcursor.downField("delta").as[String].getOrElse("")
        dispatcher.onTextDelta(delta)
      case "done" =>
        dispatcher.onDone().attempt.void *>
          // Remove dispatcher — turn complete
          activeDispatchers.update(_ - sessionId)
      case _ => IO.unit

end FeishuPlugin
