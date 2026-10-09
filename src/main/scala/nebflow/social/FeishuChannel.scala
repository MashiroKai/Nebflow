package nebflow.social

import com.lark.oapi.Client as LarkClient
import com.lark.oapi.core.enums.BaseUrlEnum
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler
import com.lark.oapi.event.cardcallback.model.{
  CallBackCard,
  CallBackToast,
  P2CardActionTrigger,
  P2CardActionTriggerResponse
}
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.{CreateMessageReq, CreateMessageReqBody, P2MessageReceiveV1}
import com.lark.oapi.ws.Client as WsClient

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

/**
 * Feishu channel — the LIVE half (feishu-chan, 2026-09-23).
 *
 * Route (upstream plan card `20260923_093043_feishu-prep`, §3.1 ⑤): the official
 * Java SDK's long-connection mode, i.e. `com.lark.oapi.ws.Client` speaking
 * WebSocket to Feishu. No public callback URL is required — the app already has
 * `callback_info.callback_type = "websocket"` configured on the platform side
 * (prep node reading), so this is the mode the application was built for.
 *
 * 🔴 Zero-secret discipline: `appId` / `appSecret` are constructor state and are
 * never logged, never rendered, never written to an evidence file. Every string
 * this object produces for the outside world goes through [[FeishuCredentials]]
 * masking or carries only what Feishu returned (message ids, event ids).
 *
 * Start/stop is symmetrical and self-managed (`close()` always tears the
 * WebSocket down) — the batch constraint forbids leaving a resident process
 * behind, so [[FeishuChannel.stop]] is the single exit path and is idempotent.
 */
object FeishuChannel:

  /** ISO-8601 (offset) stamp — the evidence convention for this batch. */
  private def stamp(): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))

  /** The wire value of the interactive-card message type. Kept as our own
    * constant so the send method does not depend on the SDK's enum surface for
    * a string that appears verbatim in the API. */
  val InteractiveMessageType = "interactive"

  /** Outcome of one send attempt. `messageId` is Feishu's, safe to record. */
  final case class SendResult(
    ok: Boolean, messageId: Option[String], code: Int, msg: String, sentAt: String, receiveIdType: String
  ):
    def toJson: io.circe.Json =
      io.circe.Json.obj(
        "ok" -> io.circe.Json.fromBoolean(ok),
        "messageId" -> messageId.map(io.circe.Json.fromString).getOrElse(io.circe.Json.Null),
        "code" -> io.circe.Json.fromInt(code),
        "msg" -> io.circe.Json.fromString(msg),
        "sentAt" -> io.circe.Json.fromString(sentAt),
        "receiveIdType" -> io.circe.Json.fromString(receiveIdType)
      )

  /** Outcome of the long-connection handshake. */
  final case class ConnectResult(ok: Boolean, connectedAt: Option[String], detail: String):
    def toJson: io.circe.Json =
      io.circe.Json.obj(
        "ok" -> io.circe.Json.fromBoolean(ok),
        "connectedAt" -> connectedAt.map(io.circe.Json.fromString).getOrElse(io.circe.Json.Null),
        "detail" -> io.circe.Json.fromString(detail)
      )

  /** A received event, tagged with the local receive timestamp (the "true
    * receive" reading this batch must produce). */
  final case class Received(inbound: FeishuMessage.Inbound, receivedAt: String):
    def toJson: io.circe.Json =
      io.circe.Json.obj("receivedAt" -> io.circe.Json.fromString(receivedAt), "event" -> inbound.toJson)

  // ───────────── askuser-remote P1: the card.action.trigger receive face ─────────────

  /** One card click, reduced to what the answering side may read: the
    * clicker's open_id (feeds the member allowlist gate — the SAME judge the
    * text intake applies), the chat the card was clicked in (the
    * chat-mismatch judge's input), the message the card lives in, and the
    * button's own `value` payload (nf/rid/qi/oi — [[FeishuAskCard]]'s design:
    * 🔴 it never carries a session id; the answering side resolves the
    * conversation from its own records). Identity/context only — never a
    * credential. */
  final case class CardAction(
    openId: Option[String],
    chatId: Option[String],
    messageId: Option[String],
    value: io.circe.Json
  )

  /** What the callback answers with, inside the 3-second window (A4 ruling):
    * a toast and/or the UPDATED card body. `none` keeps the card untouched —
    * the refusal path answers with a toast only. */
  final case class CardActionResponse(toast: Option[String], card: Option[String]):
    def isEmpty: Boolean = toast.isEmpty && card.isEmpty
  object CardActionResponse:
    val none: CardActionResponse = CardActionResponse(None, None)

  /** circe → Java (the SDK response objects are plain-Java faces and the SDK
    * serialises them with its own mapper — a circe AST handed to `setData`
    * would not survive). Total over the JSON model. */
  private[social] def jsonToJava(j: io.circe.Json): Object =
    import scala.jdk.CollectionConverters.*
    j.fold(
      null,
      b => java.lang.Boolean.valueOf(b),
      n => java.lang.Long.valueOf(n.toLong.getOrElse(0L)),
      s => s,
      arr => new java.util.ArrayList[Object](arr.map(jsonToJava).asJava),
      obj => new java.util.LinkedHashMap[String, Object](obj.toMap.map { (k, v) => k -> jsonToJava(v) }.asJava)
    )

  /** The SDK callback `value` map → circe (Jackson already produced plain
    * String/Number/Boolean/Map/List leaves). Values that are neither JSON
    * scalars, maps nor lists degrade to their `toString` — a button payload
    * never carries anything else, and an unreadable leaf must not kill the
    * dispatch. */
  private[social] def javaToJson(v: Object): io.circe.Json =
    import scala.jdk.CollectionConverters.*
    v match
      case null                                 => io.circe.Json.Null
      case s: String                            => io.circe.Json.fromString(s)
      case b: java.lang.Boolean                 => io.circe.Json.fromBoolean(b.booleanValue)
      case n: java.lang.Number                  => io.circe.Json.fromLong(n.longValue)
      case m: java.util.Map[?, ?]               =>
        io.circe.Json.obj(m.asScala.toList.map { case (k, v) =>
          (String.valueOf(k), javaToJson(v.asInstanceOf[Object])) }*)
      case l: java.util.List[?]                 => io.circe.Json.arr(l.asScala.map(e => javaToJson(e.asInstanceOf[Object])).toList*)
      case other                                => io.circe.Json.fromString(String.valueOf(other))

  /** Map the `region` config value onto the SDK's base-URL enum. Unknown values
    *  fall back to Feishu rather than throwing: a bad region must not make the
    *  channel unstartable, and the chosen face is reported in the readings. */
  private[social] def baseUrlFor(region: String): BaseUrlEnum =
    if region.equalsIgnoreCase("lark") then BaseUrlEnum.LarkSuite else BaseUrlEnum.FeiShu

  /**
   * Open a long connection and collect inbound events.
   *
   * @param appId      application id (never logged)
   * @param appSecret  application secret (never logged)
   * @param region     `feishu` (default) or `lark`
   * @param handler    invoked on the SDK's receive thread for every parsed
   *                   `im.message.receive_v1`; exceptions are contained so a
   *                   throwing consumer cannot kill the socket
   * @param readyMs    handshake budget; `awaitReady` throws past it
   */
  final class Listener(
    appId: String,
    appSecret: String,
    region: String = "feishu",
    handler: FeishuMessage.Inbound => Unit = _ => (),
    readyMs: Long = 15000L,
    /** askuser-remote P1 (zero-secret discipline): the card-callback
      * credential pair, read by the caller through [[FeishuCredentials]] and
      * handed ONLY to the SDK builder — never logged, never rendered. Both
      * default to the empty string = the long-connection shape this listener
      * always ran with (the values are optional for websocket callback
      * delivery; absent credentials change nothing). */
    verificationToken: String = "",
    encryptKey: String = "",
    /** The card.action.trigger consumer (askuser-remote P1). Runs on the
      * SDK's dispatch thread with the SAME containment contract as `handler`:
      * a throwing consumer is contained here (the response stays empty) and
      * can never kill the socket. Returning [[CardActionResponse.none]] keeps
      * the card untouched. */
    cardHandler: FeishuChannel.CardAction => FeishuChannel.CardActionResponse = _ => FeishuChannel.CardActionResponse.none
  ):
    private val queue = new LinkedBlockingQueue[FeishuMessage.Inbound]()
    private var ws: Option[WsClient] = None
    @volatile private var connectedAt: Option[String] = None
    @volatile private var lastError: Option[String] = None

    /** The dispatching handler. `P2MessageReceiveV1` is the SDK's typed view of
      *  `im.message.receive_v1`; getters return `null` for absent fields, which
      *  [[FeishuMessage.inboundFrom]] normalises in one place. A payload that
      *  cannot be reduced to a message id + chat id is recorded as an error
      *  rather than synthesised into a fake message. */
    private def dispatcher: EventDispatcher =
      EventDispatcher.newBuilder(verificationToken, encryptKey) // long-connection mode: the callback pair (optional, may stay empty)
        .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler:
          override def handle(event: P2MessageReceiveV1): Unit =
            val data = Option(event).flatMap(e => Option(e.getEvent))
            val msg = data.flatMap(d => Option(d.getMessage))
            val header = Option(event).flatMap(e => Option(e.getHeader))
            // The sender's open_id (feishubridge batch): the member-allowlist
            // gate downstream needs a stable per-sender identity. Absent or
            // blank stays None — an identity we do not have is never invented.
            val senderId = data
              .flatMap(d => Option(d.getSender))
              .flatMap(s => Option(s.getSenderId))
              .flatMap(u => Option(u.getOpenId))
              .map(_.trim).filter(_.nonEmpty)
            // The reply pointer (inbound-parse batch, 2026-10-01): Feishu keeps
            // `parent_id` / `root_id` on the ENVELOPE, not inside `content`, so
            // without these two getters a quoted reply loses its referent at the
            // door. Blank stays None — an id we do not have is never invented.
            val parentId = msg.flatMap(m => Option(m.getParentId)).map(_.trim).filter(_.nonEmpty)
            val rootId = msg.flatMap(m => Option(m.getRootId)).map(_.trim).filter(_.nonEmpty)
            // The event-face mention list. 🔴 The SDK has TWO mention shapes: this
            // event face (`MentionEvent`, whose `id` is a nested `UserId`) and the
            // fetch-message face (`Mention`, whose `id` is a plain String). They
            // must never be treated as the same class — here we always take the
            // nested one and flatten it to a plain string for the parse layer.
            val mentions = msg.flatMap(m => Option(m.getMentions)).map(_.toList).getOrElse(Nil).flatMap { me =>
              Option(me).flatMap(m => Option(m.getKey)).map(_.trim).filter(_.nonEmpty).map { key =>
                val uid = Option(me).flatMap(m => Option(m.getId))
                FeishuMessage.InboundMention(
                  key = key,
                  name = Option(me).flatMap(m => Option(m.getName)).map(_.trim).filter(_.nonEmpty),
                  id = uid.flatMap(u => Option(u.getUserId))
                    .orElse(uid.flatMap(u => Option(u.getOpenId)))
                    .orElse(uid.flatMap(u => Option(u.getUnionId)))
                    .map(_.trim).filter(_.nonEmpty)
                )
              }
            }
            val reduced = FeishuMessage.inboundFrom(
              eventId = header.flatMap(h => Option(h.getEventId)),
              messageId = msg.flatMap(m => Option(m.getMessageId)),
              chatId = msg.flatMap(m => Option(m.getChatId)),
              messageType = msg.flatMap(m => Option(m.getMessageType)),
              contentRaw = msg.flatMap(m => Option(m.getContent)),
              createTime = msg.flatMap(m => Option(m.getCreateTime)),
              senderId = senderId,
              parentId = parentId,
              rootId = rootId,
              mentions = mentions
            )
            reduced match
              case Right(inbound) =>
                queue.offer(inbound)
                try handler(inbound)
                catch case e: Exception => lastError = Some(s"handler threw ${e.getClass.getSimpleName}")
              case Left(reason) => lastError = Some(reason)
        )
        // askuser-remote P1 (A-1/A-2 拍板): the SAME long-connection dispatcher
        // also serves the interactive-card callback — only the NEW
        // `card.action.trigger` event is subscribed (never the v1 face, never
        // a second webhook URL, so one click produces exactly one request).
        // The click is reduced to a [[CardAction]] (operator open_id →
        // allowlist gate; context open_chat_id → chat-mismatch judge; action
        // value → the nf/rid/qi/oi payload) and the handler's response —
        // toast and/or the A4 updated card — is returned inside the SDK's
        // callback window.
        .onP2CardActionTrigger(new P2CardActionTriggerHandler:
          override def handle(event: P2CardActionTrigger): P2CardActionTriggerResponse =
            val data = Option(event).flatMap(e => Option(e.getEvent))
            val action = data.flatMap(d => Option(d.getAction))
            val value = action
              .flatMap(a => Option(a.getValue))
              .map(m => javaToJson(m.asInstanceOf[Object]))
              .getOrElse(io.circe.Json.obj())
            val card = FeishuChannel.CardAction(
              openId = data
                .flatMap(d => Option(d.getOperator))
                .flatMap(o => Option(o.getOpenId))
                .map(_.trim).filter(_.nonEmpty),
              chatId = data
                .flatMap(d => Option(d.getContext))
                .flatMap(c => Option(c.getOpenChatId))
                .map(_.trim).filter(_.nonEmpty),
              messageId = data
                .flatMap(d => Option(d.getContext))
                .flatMap(c => Option(c.getOpenMessageId))
                .map(_.trim).filter(_.nonEmpty),
              value = value
            )
            val response =
              try cardHandler(card)
              catch
                case e: Exception =>
                  lastError = Some(s"card handler threw ${e.getClass.getSimpleName}")
                  FeishuChannel.CardActionResponse.none
            val out = new P2CardActionTriggerResponse
            response.toast.foreach { t =>
              val toast = new CallBackToast()
              toast.setType("info")
              toast.setContent(t)
              out.setToast(toast)
            }
            response.card.foreach { body =>
              val c = new CallBackCard()
              c.setType("raw")
              io.circe.parser.parse(body) match
                case Right(j) => c.setData(jsonToJava(j))
                case Left(_)  => c.setData(body) // a non-JSON body would be a caller bug; hand it through unchanged
              out.setCard(c)
            }
            out
        )
        .build()

    /** Start the socket and block until the handshake completes (or the budget
      *  expires). Returns a reading rather than throwing so the caller can
      *  record a failed handshake as evidence — an unreachable server is a
      *  finding, not an exception to swallow. */
    def start(): ConnectResult =
      try
        val client = new WsClient.Builder(appId, appSecret)
          .eventHandler(dispatcher)
          .domain(baseUrlFor(region).getUrl)
          .autoReconnect(true)
          .build()
        client.start()
        client.awaitReady(readyMs)
        ws = Some(client)
        connectedAt = Some(stamp())
        ConnectResult(ok = true, connectedAt, s"websocket ready within ${readyMs}ms (region=$region)")
      catch
        case e: Exception =>
          lastError = Some(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")
          // A failed handshake still owns a socket attempt — close it so no
          // reconnect loop outlives this call.
          ws.foreach { c => try c.close() catch case _: Exception => () }
          ws = None
          ConnectResult(ok = false, None, s"handshake failed within ${readyMs}ms: ${lastError.getOrElse("unknown")}")

    /** Wait up to `ms` for one inbound message. `None` means the budget expired
      *  with nothing delivered — reported as such, never as a received message. */
    def awaitOne(ms: Long): Option[Received] =
      Option(queue.poll(ms, TimeUnit.MILLISECONDS)).map(in => Received(in, stamp()))

    def lastErr: Option[String] = lastError
    def connected: Boolean = ws.isDefined

    /** Idempotent teardown: closes the socket and marks the listener unusable.
      *  Safe to call twice (the `ws` slot is cleared, so the second call is a
      *  no-op) — the caller may therefore put it in a `finally`/shutdown hook. */
    def stop(): Unit =
      ws.foreach { c => try c.close() catch case _: Exception => () }
      ws = None

    /** askuser-remote P1 — live-probe MACHINE leg only: feed ONE raw wire
      * event through THIS listener's dispatcher, i.e. the same dispatcher
      * object the SDK's websocket transport hands decoded frames to. The
      * probe therefore exercises the REAL `card.action.trigger` registration
      * and the real response assembly, not a copy. Returns the raw response
      * body the SDK would hand back to the platform. */
    private[social] def feedRawEvent(body: Array[Byte]): String =
      dispatcher.doWithoutValidation(body).toString

  /** Send one text message. `receiveIdType` is `chat_id` or `open_id` (the two
    *  the batch's acceptance criteria name). Returns a reading for both the
    *  success and the failure branch — a rejected send is evidence too. */
  def sendText(
    appId: String,
    appSecret: String,
    region: String,
    receiveIdType: String,
    receiveId: String,
    text: String
  ): SendResult =
    val at = stamp()
    try
      val client = LarkClient.newBuilder(appId, appSecret)
        .openBaseUrl(baseUrlFor(region))
        .build()
      val body = CreateMessageReqBody.newBuilder()
        .receiveId(receiveId)
        .msgType("text")
        .content(io.circe.Json.obj("text" -> io.circe.Json.fromString(text)).noSpaces)
        .build()
      val req = CreateMessageReq.newBuilder()
        .receiveIdType(receiveIdType)
        .createMessageReqBody(body)
        .build()
      val resp = client.im().v1().message().create(req)
      if resp != null && resp.success() then
        SendResult(ok = true, Option(resp.getData).flatMap(d => Option(d.getMessageId)),
          resp.getCode, Option(resp.getMsg).getOrElse(""), at, receiveIdType)
      else if resp != null then
        SendResult(ok = false, None, resp.getCode, Option(resp.getMsg).getOrElse(""), at, receiveIdType)
      else
        SendResult(ok = false, None, -1, "SDK returned a null response", at, receiveIdType)
    catch
      case e: Exception =>
        SendResult(ok = false, None, -1,
          s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}", at, receiveIdType)

  /** Send one interactive card (askuser batch, 2026-10-01).
    *
    * Same shape as [[sendText]] — same client construction, same request chain,
    * same `SendResult` reading — with two differences: the message type is the
    * card type, and the content is the card JSON itself (still STRING-encoded,
    * the same rule `content` always follows on this API).
    *
    * 🔴 This is the seam only. Ruling A9 keeps the interactive leg UNWIRED for
    * this batch: nothing calls this method in production, no card callback
    * handler is registered, and the `SendResult.ok` reading it returns is the
    * mechanical fallback trigger a later batch will use.
    *
    * `sendText` above is deliberately left byte-identical — this is an
    * ADDITION, so the shipped text path cannot regress.
    */
  def sendInteractive(
    appId: String,
    appSecret: String,
    region: String,
    receiveIdType: String,
    receiveId: String,
    cardJson: String
  ): SendResult =
    val at = stamp()
    try
      val client = LarkClient.newBuilder(appId, appSecret)
        .openBaseUrl(baseUrlFor(region))
        .build()
      val body = CreateMessageReqBody.newBuilder()
        .receiveId(receiveId)
        .msgType(InteractiveMessageType)
        .content(cardJson)
        .build()
      val req = CreateMessageReq.newBuilder()
        .receiveIdType(receiveIdType)
        .createMessageReqBody(body)
        .build()
      val resp = client.im().v1().message().create(req)
      if resp != null && resp.success() then
        SendResult(ok = true, Option(resp.getData).flatMap(d => Option(d.getMessageId)),
          resp.getCode, Option(resp.getMsg).getOrElse(""), at, receiveIdType)
      else if resp != null then
        SendResult(ok = false, None, resp.getCode, Option(resp.getMsg).getOrElse(""), at, receiveIdType)
      else
        SendResult(ok = false, None, -1, "SDK returned a null response", at, receiveIdType)
    catch
      case e: Exception =>
        SendResult(ok = false, None, -1,
          s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}", at, receiveIdType)

end FeishuChannel
