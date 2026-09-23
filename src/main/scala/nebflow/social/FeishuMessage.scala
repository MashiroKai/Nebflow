package nebflow.social

import io.circe.Json

/**
 * Pure mapping layer for the Feishu channel (feishu-chan, 2026-09-23).
 *
 * Everything in this object is a TOTAL FUNCTION over plain values — no network,
 * no SDK object, no IO. That is deliberate: it is the half of the channel that
 * can be unit-tested offline, and the offline spec pins it. The live half
 * ([[FeishuChannel]]) drives the SDK and is exercised by hand only, so the
 * credential never enters CI.
 *
 * Contract of the inbound side (`im.message.receive_v1`):
 *   · `EventMessage.content` is a JSON **string** whose SHAPE depends on
 *     `message_type` (`text` ⇒ `{"text":"..."}`, `post` ⇒ a rich-text tree, …).
 *     A consumer that assumes `text` will silently produce empty bodies for
 *     every other type, so [[inboundFrom]] keeps the raw content and only
 *     extracts a convenience `text` when the shape actually is text.
 *   · Field names on the model are camelCase getters over snake_case wire keys;
 *     this layer deliberately speaks the WIRE names (what a downstream consumer
 *     or an evidence file will carry), so the mapping is testable without
 *     constructing SDK objects.
 */
object FeishuMessage:

  /** One inbound message, reduced to the fields the channel actually needs. */
  final case class Inbound(
    eventId: Option[String],
    messageId: String,
    chatId: String,
    messageType: String,
    contentRaw: String,
    text: Option[String],
    createTime: Option[String]
  ):
    /** The wire-shaped JSON a consumer reads. Carries NO credential material:
      *  the message body is the sender's own content, not ours. */
    def toJson: Json =
      Json.obj(
        "eventId" -> (eventId match { case Some(v) => Json.fromString(v); case None => Json.Null }),
        "messageId" -> Json.fromString(messageId),
        "chatId" -> Json.fromString(chatId),
        "messageType" -> Json.fromString(messageType),
        "content" -> Json.fromString(contentRaw),
        "text" -> (text match { case Some(v) => Json.fromString(v); case None => Json.Null }),
        "createTime" -> (createTime match { case Some(v) => Json.fromString(v); case None => Json.Null })
      )

  /** Extract the human-readable text when (and only when) the content shape is a
    *  plain text body. Any other type answers `None` rather than guessing — a
    *  wrong guess here would fabricate message content.
    *
    * `message_type = "text"` ⇒ `{"text":"hello"}` (the SDK/Feishu convention).
    */
  def textOf(messageType: String, contentRaw: String): Option[String] =
    if messageType != "text" then None
    else
      io.circe.parser.parse(contentRaw).toOption
        .flatMap(_.hcursor.downField("text").as[String].toOption)

  /** Build an [[Inbound]] from already-extracted getter values. Kept separate
    *  from the SDK handler so the spec can feed it without the SDK on the
    *  classpath path being exercised, and so the SDK's null-returning getters
    *  are normalised in exactly one place. */
  def inboundFrom(
    eventId: Option[String],
    messageId: Option[String],
    chatId: Option[String],
    messageType: Option[String],
    contentRaw: Option[String],
    createTime: Option[String]
  ): Either[String, Inbound] =
    val mt = messageType.getOrElse("")
    (messageId.filter(_.nonEmpty), chatId.filter(_.nonEmpty)) match
      case (Some(mid), Some(cid)) =>
        val raw = contentRaw.getOrElse("")
        Right(Inbound(eventId.filter(_.nonEmpty), mid, cid, mt, raw, textOf(mt, raw), createTime.filter(_.nonEmpty)))
      case (mid, cid) =>
        Left(
          "im.message.receive_v1 payload is missing " +
            Seq(
              if mid.isEmpty then Some("message.message_id") else None,
              if cid.isEmpty then Some("message.chat_id") else None
            ).flatten.mkString(" and ") + " — refusing to fabricate an inbound message"
        )

  /** The text-message send body (`im/v1/messages`). `content` is the STRING
    *  form of a JSON object — passing the object itself is the classic mistake
    *  here and Feishu answers with a type error, so this helper owns the
    *  encoding and the caller cannot get it wrong. */
  def textSendBody(text: String): Json =
    Json.obj("msg_type" -> Json.fromString("text"),
      "content" -> Json.fromString(Json.obj("text" -> Json.fromString(text)).noSpaces))

end FeishuMessage
