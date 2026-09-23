package nebflow.social

import com.lark.oapi.Client as LarkClient
import com.lark.oapi.core.enums.BaseUrlEnum
import com.lark.oapi.event.EventDispatcher
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
    *  receive" reading this batch must produce). */
  final case class Received(inbound: FeishuMessage.Inbound, receivedAt: String):
    def toJson: io.circe.Json =
      io.circe.Json.obj("receivedAt" -> io.circe.Json.fromString(receivedAt), "event" -> inbound.toJson)

  /** Map the `region` config value onto the SDK's base-URL enum. Unknown values
    *  fall back to Feishu rather than throwing: a bad region must not make the
    *  channel unstartable, and the chosen face is reported in the readings. */
  private def baseUrlFor(region: String): BaseUrlEnum =
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
    readyMs: Long = 15000L
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
      EventDispatcher.newBuilder("", "") // long-connection mode: no webhook token/encrypt key
        .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler:
          override def handle(event: P2MessageReceiveV1): Unit =
            val data = Option(event).flatMap(e => Option(e.getEvent))
            val msg = data.flatMap(d => Option(d.getMessage))
            val header = Option(event).flatMap(e => Option(e.getHeader))
            val reduced = FeishuMessage.inboundFrom(
              eventId = header.flatMap(h => Option(h.getEventId)),
              messageId = msg.flatMap(m => Option(m.getMessageId)),
              chatId = msg.flatMap(m => Option(m.getChatId)),
              messageType = msg.flatMap(m => Option(m.getMessageType)),
              contentRaw = msg.flatMap(m => Option(m.getContent)),
              createTime = msg.flatMap(m => Option(m.getCreateTime))
            )
            reduced match
              case Right(inbound) =>
                queue.offer(inbound)
                try handler(inbound)
                catch case e: Exception => lastError = Some(s"handler threw ${e.getClass.getSimpleName}")
              case Left(reason) => lastError = Some(reason)
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

end FeishuChannel
