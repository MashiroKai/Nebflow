package nebflow.social

import com.lark.oapi.Client as LarkClient
import com.lark.oapi.core.enums.BaseUrlEnum
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler
import com.lark.oapi.event.cardcallback.model.*
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

  /**
   * The wire value of the interactive-card message type. Kept as our own
   * constant so the send method does not depend on the SDK's enum surface for
   * a string that appears verbatim in the API.
   */
  val InteractiveMessageType = "interactive"

  /** Outcome of one send attempt. `messageId` is Feishu's, safe to record. */
  final case class SendResult(
    ok: Boolean,
    messageId: Option[String],
    code: Int,
    msg: String,
    sentAt: String,
    receiveIdType: String
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

  end SendResult

  /** Outcome of the long-connection handshake. */
  final case class ConnectResult(ok: Boolean, connectedAt: Option[String], detail: String):

    def toJson: io.circe.Json =
      io.circe.Json.obj(
        "ok" -> io.circe.Json.fromBoolean(ok),
        "connectedAt" -> connectedAt.map(io.circe.Json.fromString).getOrElse(io.circe.Json.Null),
        "detail" -> io.circe.Json.fromString(detail)
      )

  /**
   * A received event, tagged with the local receive timestamp (the "true
   * receive" reading this batch must produce).
   */
  final case class Received(inbound: FeishuMessage.Inbound, receivedAt: String):

    def toJson: io.circe.Json =
      io.circe.Json.obj("receivedAt" -> io.circe.Json.fromString(receivedAt), "event" -> inbound.toJson)

  // ───────────── askuser-remote P1: the card.action.trigger receive face ─────────────

  /**
   * One card click, reduced to what the answering side may read: the
   * clicker's open_id (feeds the member allowlist gate — the SAME judge the
   * text intake applies), the chat the card was clicked in (the
   * chat-mismatch judge's input), the message the card lives in, and the
   * button's own `value` payload (nf/rid/qi/oi — [[FeishuAskCard]]'s design:
   * 🔴 it never carries a session id; the answering side resolves the
   * conversation from its own records). Identity/context only — never a
   * credential.
   */
  final case class CardAction(
    openId: Option[String],
    chatId: Option[String],
    messageId: Option[String],
    value: io.circe.Json
  )

  /**
   * What the callback answers with, inside the 3-second window (A4 ruling):
   * a toast and/or the UPDATED card body. `none` keeps the card untouched —
   * the refusal path answers with a toast only.
   */
  final case class CardActionResponse(toast: Option[String], card: Option[String]):
    def isEmpty: Boolean = toast.isEmpty && card.isEmpty

  object CardActionResponse:
    val none: CardActionResponse = CardActionResponse(None, None)

  /**
   * circe → Java (the SDK response objects are plain-Java faces and the SDK
   * serialises them with its own mapper — a circe AST handed to `setData`
   * would not survive). Total over the JSON model.
   */
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

  /**
   * The SDK callback `value` map → circe (Jackson already produced plain
   * String/Number/Boolean/Map/List leaves). Values that are neither JSON
   * scalars, maps nor lists degrade to their `toString` — a button payload
   * never carries anything else, and an unreadable leaf must not kill the
   * dispatch.
   */
  private[social] def javaToJson(v: Object): io.circe.Json =
    import scala.jdk.CollectionConverters.*
    v match
      case null => io.circe.Json.Null
      case s: String => io.circe.Json.fromString(s)
      case b: java.lang.Boolean => io.circe.Json.fromBoolean(b.booleanValue)
      case n: java.lang.Number => io.circe.Json.fromLong(n.longValue)
      case m: java.util.Map[?, ?] =>
        io.circe.Json.obj(m.asScala.toList.map { case (k, v) =>
          (String.valueOf(k), javaToJson(v.asInstanceOf[Object]))
        }*)
      case l: java.util.List[?] => io.circe.Json.arr(l.asScala.map(e => javaToJson(e.asInstanceOf[Object])).toList*)
      case other => io.circe.Json.fromString(String.valueOf(other))

  /**
   * Map the `region` config value onto the SDK's base-URL enum. Unknown values
   *  fall back to Feishu rather than throwing: a bad region must not make the
   *  channel unstartable, and the chosen face is reported in the readings.
   */
  private[social] def baseUrlFor(region: String): BaseUrlEnum =
    if region.equalsIgnoreCase("lark") then BaseUrlEnum.LarkSuite else BaseUrlEnum.FeiShu

  /**
   * The https ORIGIN of the region — the raw-HTTP legs ([[getImageVia]]) build
   * URLs from this string. Same region judgement as [[baseUrlFor]].
   */
  private[social] def baseOriginOf(region: String): String =
    if region.equalsIgnoreCase("lark") then "https://open.larksuite.com" else "https://open.feishu.cn"

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
    /**
     * askuser-remote P1 (zero-secret discipline): the card-callback
     * credential pair, read by the caller through [[FeishuCredentials]] and
     * handed ONLY to the SDK builder — never logged, never rendered. Both
     * default to the empty string = the long-connection shape this listener
     * always ran with (the values are optional for websocket callback
     * delivery; absent credentials change nothing).
     */
    verificationToken: String = "",
    encryptKey: String = "",
    /**
     * The card.action.trigger consumer (askuser-remote P1). Runs on the
     * SDK's dispatch thread with the SAME containment contract as `handler`:
     * a throwing consumer is contained here (the response stays empty) and
     * can never kill the socket. Returning [[CardActionResponse.none]] keeps
     * the card untouched.
     */
    cardHandler: FeishuChannel.CardAction => FeishuChannel.CardActionResponse = _ =>
      FeishuChannel.CardActionResponse.none
  ):
    private val queue = new LinkedBlockingQueue[FeishuMessage.Inbound]()
    private var ws: Option[WsClient] = None
    @volatile private var connectedAt: Option[String] = None
    @volatile private var lastError: Option[String] = None

    /**
     * The dispatching handler. `P2MessageReceiveV1` is the SDK's typed view of
     *  `im.message.receive_v1`; getters return `null` for absent fields, which
     *  [[FeishuMessage.inboundFrom]] normalises in one place. A payload that
     *  cannot be reduced to a message id + chat id is recorded as an error
     *  rather than synthesised into a fake message.
     */
    private def dispatcher: EventDispatcher =
      EventDispatcher
        .newBuilder(verificationToken, encryptKey) // long-connection mode: the callback pair (optional, may stay empty)
        .onP2MessageReceiveV1(
          new ImService.P2MessageReceiveV1Handler:
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
                .map(_.trim)
                .filter(_.nonEmpty)
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
                    id = uid
                      .flatMap(u => Option(u.getUserId))
                      .orElse(uid.flatMap(u => Option(u.getOpenId)))
                      .orElse(uid.flatMap(u => Option(u.getUnionId)))
                      .map(_.trim)
                      .filter(_.nonEmpty)
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
                case Left(_) => lastError = Some("inbound event rejected")
            end handle
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
        .onP2CardActionTrigger(
          new P2CardActionTriggerHandler:
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
                  .map(_.trim)
                  .filter(_.nonEmpty),
                chatId = data
                  .flatMap(d => Option(d.getContext))
                  .flatMap(c => Option(c.getOpenChatId))
                  .map(_.trim)
                  .filter(_.nonEmpty),
                messageId = data
                  .flatMap(d => Option(d.getContext))
                  .flatMap(c => Option(c.getOpenMessageId))
                  .map(_.trim)
                  .filter(_.nonEmpty),
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
                  case Left(_) => c.setData(body) // a non-JSON body would be a caller bug; hand it through unchanged
                out.setCard(c)
              }
              out
            end handle
        )
        .build()

    /**
     * Start the socket and block until the handshake completes (or the budget
     *  expires). Returns a reading rather than throwing so the caller can
     *  record a failed handshake as evidence — an unreachable server is a
     *  finding, not an exception to swallow.
     */
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
          lastError = Some(s"${e.getClass.getSimpleName}")
          // A failed handshake still owns a socket attempt — close it so no
          // reconnect loop outlives this call.
          ws.foreach { c =>
            try c.close()
            catch case _: Exception => ()
          }
          ws = None
          ConnectResult(ok = false, None, s"handshake failed within ${readyMs}ms: ${lastError.getOrElse("unknown")}")

    /**
     * Wait up to `ms` for one inbound message. `None` means the budget expired
     *  with nothing delivered — reported as such, never as a received message.
     */
    def awaitOne(ms: Long): Option[Received] =
      Option(queue.poll(ms, TimeUnit.MILLISECONDS)).map(in => Received(in, stamp()))

    def lastErr: Option[String] = lastError
    def connected: Boolean = ws.isDefined

    /**
     * Idempotent teardown: closes the socket and marks the listener unusable.
     *  Safe to call twice (the `ws` slot is cleared, so the second call is a
     *  no-op) — the caller may therefore put it in a `finally`/shutdown hook.
     */
    def stop(): Unit =
      ws.foreach { c =>
        try c.close()
        catch case _: Exception => ()
      }
      ws = None

    /**
     * askuser-remote P1 — live-probe MACHINE leg only: feed ONE raw wire
     * event through THIS listener's dispatcher, i.e. the same dispatcher
     * object the SDK's websocket transport hands decoded frames to. The
     * probe therefore exercises the REAL `card.action.trigger` registration
     * and the real response assembly, not a copy. Returns the raw response
     * body the SDK would hand back to the platform.
     */
    private[social] def feedRawEvent(body: Array[Byte]): String =
      dispatcher.doWithoutValidation(body).toString

  end Listener

  /**
   * Send one text message. `receiveIdType` is `chat_id` or `open_id` (the two
   *  the batch's acceptance criteria name). Returns a reading for both the
   *  success and the failure branch — a rejected send is evidence too.
   */
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
      val client = LarkClient
        .newBuilder(appId, appSecret)
        .openBaseUrl(baseUrlFor(region))
        .build()
      val body = CreateMessageReqBody
        .newBuilder()
        .receiveId(receiveId)
        .msgType("text")
        .content(io.circe.Json.obj("text" -> io.circe.Json.fromString(text)).noSpaces)
        .build()
      val req = CreateMessageReq
        .newBuilder()
        .receiveIdType(receiveIdType)
        .createMessageReqBody(body)
        .build()
      val resp = client.im().v1().message().create(req)
      if resp != null && resp.success() then
        SendResult(
          ok = true,
          Option(resp.getData).flatMap(d => Option(d.getMessageId)),
          resp.getCode,
          "remote response",
          at,
          receiveIdType
        )
      else if resp != null then SendResult(ok = false, None, resp.getCode, "remote response", at, receiveIdType)
      else SendResult(ok = false, None, -1, "SDK returned a null response", at, receiveIdType)
    catch
      case e: Exception =>
        SendResult(
          ok = false,
          None,
          -1,
          s"${e.getClass.getSimpleName}",
          at,
          receiveIdType
        )

    end try

  end sendText

  /**
   * The read result of one `im/v1/images/:image_key` download
   * (feishu-inbound-image batch, 2026-10-11) — the inbound twin of
   * `RichFeishuAdapter`'s upload leg. `bytes` is the image body exactly as the
   * channel returned it: no re-encode, no synthesis, never a credential.
   * 入站图片必达批（2026-10-24）: the face is deliberately byte[]-shaped — a
   * stream object must NEVER ride this DTO (see [[getImage]]).
   */
  final case class ImageResult(ok: Boolean, bytes: Option[Array[Byte]], code: Int, msg: String)

  /**
   * 入站图片必达批（2026-10-24）· the raw-HTTP transport seam of
   * [[getImageVia]]: (url, headers, body) → status + bytes. `body` present ⇒
   * POST, absent ⇒ GET. `Left` = the transport itself failed (dns/timeout/…).
   * The offline spec injects a stub here; production =
   * [[rawHttpLive]]. Introduced because the SDK's download leg is BROKEN on
   * JDK 23 (see [[getImage]]): the engine must own this wire.
   */
  type RawHttp = (String, Map[String, String], Option[Array[Byte]]) => Either[String, (Int, Array[Byte])]

  /**
   * Production [[RawHttp]] — `java.net.http.HttpClient` (the same JDK client
   * family the neblink legs already use; zero new dependencies).
   */
  def rawHttpLive(timeoutMs: Int = 15_000): RawHttp =
    (url, headers, body) =>
      try
        val client = java.net.http.HttpClient
          .newBuilder()
          .connectTimeout(java.time.Duration.ofMillis(timeoutMs))
          .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
          .build()
        val builder = java.net.http.HttpRequest
          .newBuilder()
          .uri(java.net.URI.create(url))
          .timeout(java.time.Duration.ofMillis(timeoutMs))
        headers.foreach { case (k, v) => builder.header(k, v) }
        val request = body match
          case Some(bytes) => builder.POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
          case None => builder.GET().build()
        val resp = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray())
        Right((resp.statusCode(), resp.body()))
      catch
        case e: Exception =>
          Left(s"${e.getClass.getSimpleName}")

  /** One cached tenant token (value never leaves this map). */
  private final case class CachedToken(token: String, fetchedAt: Long)

  /** The in-memory tenant-token cache — key = appId + region base (never the secret). */
  private val tokenCache = scala.collection.mutable.Map.empty[String, CachedToken]

  /** tenant_access_token lives 2h server-side; refresh after 90min locally. */
  private val TokenTtlMs = 90L * 60L * 1000L

  /**
   * The tenant-token leg of the raw download: POST
   * `/open-apis/auth/v3/tenant_access_token/internal`, cache per
   * (appId, region) for [[TokenTtlMs]]. Every refusal is an explicit reading —
   * code/msg only, the request body (which carries the secret) never rides a
   * log line.
   */
  private[social] def tenantToken(
    appId: String,
    appSecret: String,
    base: String,
    http: RawHttp
  ): Either[String, String] =
    val cacheKey = s"$appId@$base"
    tokenCache.synchronized {
      tokenCache.get(cacheKey).filter(t => System.currentTimeMillis() - t.fetchedAt < TokenTtlMs) match
        case Some(cached) => Right(cached.token)
        case None =>
          val body = io.circe.Json
            .obj("app_id" -> io.circe.Json.fromString(appId), "app_secret" -> io.circe.Json.fromString(appSecret))
            .noSpaces
            .getBytes("UTF-8")
          http(
            s"$base/open-apis/auth/v3/tenant_access_token/internal",
            Map("Content-Type" -> "application/json"),
            Some(body)
          ) match
            case Left(_) => Left("token request failed")
            case Right((status, bytes)) =>
              io.circe.parser
                .parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                .toOption
                .flatMap { j =>
                  val code = j.hcursor.downField("code").as[Long].toOption.map(_.toInt).getOrElse(-1)
                  if code == 0 then
                    j.hcursor.downField("tenant_access_token").as[String].toOption.filter(_.nonEmpty) match
                      case Some(token) =>
                        tokenCache.update(cacheKey, CachedToken(token, System.currentTimeMillis()))
                        Some(Right(token))
                      case None => Some(Left(s"token response carried no tenant_access_token (http=$status)"))
                  else Some(Left(s"token refused code=$code (http=$status)"))
                }
                .getOrElse(Left(s"token response was not JSON (http=$status, ${bytes.length}B)"))
          end match
    }
  end tenantToken

  // ───── 入站消息资源下载（feishu-image-endpoint 批，2026-11-07）: 两条腿与诊断 ─────
  //
  // 官方文档（2026-11-07 核对，飞书开放平台纯 markdown 版）钉死两条腿不是一回事：
  //   · 「下载图片」GET /open-apis/im/v1/images/:image_key
  //     https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/im-v1/image/get
  //     使用限制原文：「只能下载由当前机器人上传的图片，且上传时图片类型为
  //     **用于发送消息**」——并明确指路：「如果你需要下载用户发送消息内的资源
  //     文件，可使用[获取消息中的资源文件]接口」。错误码 234008 =「当前应用不是
  //     资源的所有者」，234001 = Invalid request param。
  //   · 「获取消息中的资源文件」GET /open-apis/im/v1/messages/:message_id/resources/:file_key
  //     https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/im-v1/message-resource/get
  //     查询参数 `type` 必填（image | file），路径参数 message_id 与 file_key
  //     「需要匹配」（否则 234003 File not in message）。
  //
  // 入站事件（im.message.receive_v1）里的 image_key/file_key 属于**后一条**腿。
  // 2026-11-07 作者报障 —— code=234001 Invalid request param
  // (endpoint=im/v1/images/:image_key, message_id=om_x100b61de7010653cb09af6a3e618fef,
  // image_key=img_v3_02177_7c134581-...-8b29f82f1b5g) —— 就是拿错腿的必然结果:
  // 那个 key 不是本机器人上传的，第一条腿无从匹配参数。本批把默认腿改成
  // messages/resources（见 [[getInboundResourceVia]]），旧腿降级为无 message_id
  // 时的受控回退。

  /** 旧腿（机器人自传图）在诊断里的接口标签。 */
  val ImageEndpoint: String = "im/v1/images/:image_key"

  /** 正确腿（消息内资源）在诊断里的接口标签。 */
  val MessageResourceEndpoint: String = "im/v1/messages/:message_id/resources/:file_key"

  /**
   * 「获取消息中的资源文件」接受的资源类型——官方查询参数 `type` 的仅有两个
   * 合法值。按消息类型映射：图片消息 → image；文件/语音/视频消息 → file
   * （视频按官方口径归入 file 一类）。未知类型 ⇒ None（不猜）。
   */
  object ResourceKind:
    val Image = "image"
    val File = "file"
    val Allowed: Seq[String] = Seq(Image, File)

    def ofMessageType(messageType: String): Option[String] =
      messageType match
        case "image" => Some(Image)
        case "file" | "audio" | "media" => Some(File)
        case _ => None

  /**
   * 诊断里使用的 key 截断：key 不是凭据，但它又长又吵，报障只需要头尾特征。
   * ≤16 字符原样保留（短 key 的可读性），更长的截成 `头8…尾4`。
   */
  def maskKey(key: String): String =
    val k = Option(key).getOrElse("").trim
    if k.length <= 16 then k else s"${k.take(8)}…${k.takeRight(4)}"

  /** feishu 的 msg 若回显完整 key，替换成截断形（完整 key 永不出现在读数里）。 */
  private def scrubKey(msg: String, key: String): String =
    val k = Option(key).getOrElse("").trim
    if k.nonEmpty && msg.contains(k) then msg.replace(k, maskKey(k)) else msg

  /**
   * The readable refusal out of a non-2xx resource body — feishu's own
   * `code`/`msg` when the body is the error JSON, an honest shape note when it
   * is not. Pure, so the offline spec pins the exact reading (the SDK used to
   * mask ALL of these behind a JsonIOException — the 2026-10-24 incident).
   *
   * feishu-image-endpoint 批: `api` / `endpoint` are the LEG's own labels, so a
   * reading always says WHICH interface answered — the 2026-11-07 incident was
   * a leg mix-up, so an endpoint-less reading is worthless for next time.
   */
  private def resourceErrorReading(status: Int, body: Array[Byte], api: String, endpoint: String): (Int, String) =
    io.circe.parser
      .parse(new String(body, java.nio.charset.StandardCharsets.UTF_8))
      .toOption
      .flatMap { j =>
        val cursor = j.hcursor
        val rawCode = cursor.downField("code").focus
        val rawMsg = cursor.downField("msg").focus
        val code = cursor
          .downField("code")
          .as[Long]
          .toOption
          .map(_.toInt)
          .orElse(cursor.downField("code").as[String].toOption.flatMap(s => scala.util.Try(s.toInt).toOption))
        code.map { c =>
          (
            c,
            s"Feishu $api refused (http=$status endpoint=$endpoint fields=code:${rawCode.isDefined},msg:${rawMsg.isDefined}; code=$c)"
          )
        }
      }
      .getOrElse(
        (
          status,
          s"Feishu $api refused (http=$status endpoint=$endpoint fields=code:false,msg:false; non-JSON body ${body.length}B)"
        )
      )

  /** 旧腿（im/v1/images）的读数——cd68b495e 加的诊断，字段与措辞逐字保留。 */
  def imageErrorReading(status: Int, body: Array[Byte]): (Int, String) =
    resourceErrorReading(status, body, "image API", ImageEndpoint)

  /** 正确腿（im/v1/messages/.../resources）的读数——同一套 http/code/msg/endpoint 字段。 */
  def messageResourceErrorReading(status: Int, body: Array[Byte]): (Int, String) =
    resourceErrorReading(status, body, "message-resource API", MessageResourceEndpoint)

  /** Encode arbitrary input as one RFC 3986 path segment, never as URL syntax. */
  private def encodePathSegment(value: String): String =
    val hex = "0123456789ABCDEF"
    value
      .getBytes(java.nio.charset.StandardCharsets.UTF_8)
      .iterator
      .map { b =>
        val c = b & 0xff
        if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "-._~".contains(c.toChar) then
          c.toChar.toString
        else s"%${hex.charAt(c >>> 4)}${hex.charAt(c & 0x0f)}"
      }
      .mkString

  /**
   * `im/v1/images/:image_key` — download one image the BOT ITSELF uploaded
   * (feishu-inbound-image batch). 🔴 feishu-image-endpoint 批（2026-11-07）: this
   * is no longer the inbound leg — official docs limit it to「只能下载由当前机器
   * 人上传的图片」, so inbound user-sent images ride
   * [[getMessageResourceVia]] instead. It survives as (a) the public bot-upload
   * download face and (b) the controlled fallback of [[getInboundResourceVia]]
   * for an event that carries no message_id.
   * The bot credential pair rides the SAME face
   * as [[sendText]] (the region base URL); a null response, a channel refusal
   * and a thrown exception all become explicit readings — never a raise, never
   * a silent empty.
   *
   * 入站图片必达批（2026-10-24）· THE SDK IS GONE FROM THIS LEG. Root cause of
   * the 2026-10-24 incident (every inbound image failing, zero ever landed):
   * the SDK models the download as `GetImageResp extends
   * BaseResponse<java.io.ByteArrayOutputStream>` — a STREAM OBJECT inside the
   * Gson-serialized DTO. On any non-binary (error-JSON) response the SDK runs
   * Gson over that DTO; under JDK 23 strong encapsulation `setAccessible` on
   * `java.io.ByteArrayOutputStream#buf` throws JsonIOException BEFORE feishu's
   * real code/msg can be read — the refusal is unreadable, the failure
   * deterministic, and the download leg never worked in production. The fix
   * owns the wire: tenant token + raw GET over [[RawHttp]], bytes in/out, the
   * error JSON parsed with circe. SAME public signature and [[ImageResult]]
   * face — FeishuBridgePlugin is untouched.
   */
  def getImage(appId: String, appSecret: String, region: String, imageKey: String): ImageResult =
    getImageVia(rawHttpLive())(appId, appSecret, region, imageKey)

  /** The [[RawHttp]]-seamed core — the offline spec injects the transport here. */
  private[social] def getImageVia(
    http: RawHttp
  )(appId: String, appSecret: String, region: String, imageKey: String): ImageResult =
    val base = baseOriginOf(region)
    tenantToken(appId, appSecret, base, http) match
      case Left(reason) => ImageResult(ok = false, None, -1, reason)
      case Right(token) =>
        http(
          s"$base/open-apis/im/v1/images/${encodePathSegment(imageKey)}",
          Map("Authorization" -> s"Bearer $token"),
          None
        ) match
          case Left(_) =>
            ImageResult(ok = false, None, -1, "image GET transport failed (endpoint=im/v1/images/:image_key)")
          case Right((status, bytes)) =>
            if status >= 200 && status < 300 then
              if bytes.nonEmpty then ImageResult(ok = true, Some(bytes), status, "ok")
              else ImageResult(ok = false, None, status, "download succeeded but returned no bytes")
            else
              val (code, msg) = imageErrorReading(status, bytes)
              // feishu-image-endpoint 批: 完整 key 不进读数——回显时替换为截断形
              // （cd68b495e 的「不泄露完整 key」要求保留，形态收窄为 maskKey）。
              ImageResult(ok = false, None, code, scrubKey(msg, imageKey))
    end match

  end getImageVia

  /**
   * `GET /open-apis/im/v1/messages/:message_id/resources/:file_key?type=<image|file>`
   * — 下载**某条消息里**的资源（音频/视频/图片/文件）。这是入站事件资源的正确
   * 腿：事件里的 `image_key`/`file_key` 只在「该 message_id + 该 file_key」这一
   * 组合下成立（官方原文：「路径参数 file_key 和 message_id 需要匹配」）。
   *
   * 官方文档：https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/im-v1/message-resource/get
   * 鉴权沿用本类既有的 tenant_access_token 腿（[[tenantToken]]，2h token 缓存
   * per (appId, region)），传输走同一 [[RawHttp]] 缝——不另造一套。
   *
   * 三个前置检查（message_id 空 / file_key 空 / type 不在 image|file）在**发起
   * 请求之前**就返回显式读数：不浪费一次网络往返，也不把 feishu 的 234001 浪费
   * 在本地就能判断的错误上。
   */
  def getMessageResource(
    appId: String,
    appSecret: String,
    region: String,
    messageId: String,
    fileKey: String,
    resourceType: String
  ): ImageResult =
    getMessageResourceVia(rawHttpLive())(appId, appSecret, region, messageId, fileKey, resourceType)

  /** The [[RawHttp]]-seamed core — the offline spec injects the transport here. */
  private[social] def getMessageResourceVia(
    http: RawHttp
  )(
    appId: String,
    appSecret: String,
    region: String,
    messageId: String,
    fileKey: String,
    resourceType: String
  ): ImageResult =
    val base = baseOriginOf(region)
    val mid = Option(messageId).map(_.trim).getOrElse("")
    val key = Option(fileKey).map(_.trim).getOrElse("")
    val kind = Option(resourceType).map(_.trim).getOrElse("")
    if mid.isEmpty then ImageResult(ok = false, None, -1, s"message_id missing (endpoint=$MessageResourceEndpoint)")
    else if key.isEmpty then ImageResult(ok = false, None, -1, s"file_key missing (endpoint=$MessageResourceEndpoint)")
    else if !ResourceKind.Allowed.contains(kind) then
      ImageResult(
        ok = false,
        None,
        -1,
        s"unsupported resource type (endpoint=$MessageResourceEndpoint; expected ${ResourceKind.Allowed.mkString("|")})"
      )
    else
      tenantToken(appId, appSecret, base, http) match
        case Left(reason) => ImageResult(ok = false, None, -1, reason)
        case Right(token) =>
          // message_id / file_key 各是**一个**路径段（RFC 3986 单段编码，绝不
          // 让 key 里的 / ? 之类的字符撕开路径）；type 取自白名单枚举，直接拼接。
          val url =
            s"$base/open-apis/im/v1/messages/${encodePathSegment(mid)}/resources/${encodePathSegment(key)}?type=$kind"
          http(url, Map("Authorization" -> s"Bearer $token"), None) match
            case Left(_) =>
              ImageResult(ok = false, None, -1, s"resource GET transport failed (endpoint=$MessageResourceEndpoint)")
            case Right((status, bytes)) =>
              if status >= 200 && status < 300 then
                if bytes.nonEmpty then ImageResult(ok = true, Some(bytes), status, "ok")
                else ImageResult(ok = false, None, status, "download succeeded but returned no bytes")
              else
                val (code, msg) = messageResourceErrorReading(status, bytes)
                ImageResult(ok = false, None, code, scrubKey(msg, key))
    end if

  end getMessageResourceVia

  /**
   * 入站消息资源的下载腿策略（feishu-image-endpoint 批，2026-11-07）——
   * **默认走 messages/{message_id}/resources/{file_key}?type=…**；`im/v1/images/
   * :image_key` 只作为「事件没带 message_id」时的受控回退保留（那条腿只认机器人
   * 自己上传的图，用它取入站消息的图必然 234001）。
   *
   * 读数里的 `leg=` 标记是硬要求：故障报告必须能说明这次走的是哪条腿，否则下一
   * 次报障还是分不清「拿错腿」还是「真失败」。`ImageResult.msg` 在成功时也带
   * 标记，失败时标记置于最前。
   */
  def getInboundResource(
    appId: String,
    appSecret: String,
    region: String,
    messageId: String,
    fileKey: String,
    resourceType: String
  ): ImageResult =
    getInboundResourceVia(rawHttpLive())(appId, appSecret, region, messageId, fileKey, resourceType)

  /** The [[RawHttp]]-seamed core — the offline spec pins both legs here. */
  private[social] def getInboundResourceVia(
    http: RawHttp
  )(
    appId: String,
    appSecret: String,
    region: String,
    messageId: String,
    fileKey: String,
    resourceType: String
  ): ImageResult =
    if Option(messageId).map(_.trim).exists(_.nonEmpty) then
      val r = getMessageResourceVia(http)(appId, appSecret, region, messageId, fileKey, resourceType)
      if r.ok then r.copy(msg = "ok (leg=message-resources)") else r.copy(msg = s"leg=message-resources ${r.msg}")
    else
      val r = getImageVia(http)(appId, appSecret, region, fileKey)
      if r.ok then r.copy(msg = "ok (leg=legacy-images; no message_id on the event)")
      else r.copy(msg = s"leg=legacy-images (no message_id on the event) ${r.msg}")

  end getInboundResourceVia

  /**
   * Send one interactive card (askuser batch, 2026-10-01).
   *
   * Same shape as [[sendText]] — same client construction, same request chain,
   * same `SendResult` reading — with two differences: the message type is the
   * card type, and the content is the card JSON itself (still STRING-encoded,
   * the same rule `content` always follows on this API).
   *
   * 🔴 Wired since askuser-remote P1 (2026-10-07): production caller
   * [[FeishuBridgePlugin.defaultSendCard]] (the ask card leg) and the card
   * callback handler rides the SAME long-connection dispatcher
   * (`onP2CardActionTrigger`). (The pre-P1 "A9 UNWIRED" wording is retired —
   * the A9 ruling lives on as this seam's name and the mechanical fallback
   * trigger: `SendResult.ok = false` on every card still degrades the set to
   * the numbered text card.)
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
      val client = LarkClient
        .newBuilder(appId, appSecret)
        .openBaseUrl(baseUrlFor(region))
        .build()
      val body = CreateMessageReqBody
        .newBuilder()
        .receiveId(receiveId)
        .msgType(InteractiveMessageType)
        .content(cardJson)
        .build()
      val req = CreateMessageReq
        .newBuilder()
        .receiveIdType(receiveIdType)
        .createMessageReqBody(body)
        .build()
      val resp = client.im().v1().message().create(req)
      if resp != null && resp.success() then
        SendResult(
          ok = true,
          Option(resp.getData).flatMap(d => Option(d.getMessageId)),
          resp.getCode,
          "remote response",
          at,
          receiveIdType
        )
      else if resp != null then SendResult(ok = false, None, resp.getCode, "remote response", at, receiveIdType)
      else SendResult(ok = false, None, -1, "SDK returned a null response", at, receiveIdType)
    catch
      case e: Exception =>
        SendResult(
          ok = false,
          None,
          -1,
          s"${e.getClass.getSimpleName}",
          at,
          receiveIdType
        )
    end try
  end sendInteractive

end FeishuChannel
