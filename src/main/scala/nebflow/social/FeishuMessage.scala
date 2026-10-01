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
 *   · The wider readable body (rich-text degradation, `@`-mention replacement,
 *     unrecognised-type placeholder) lives in [[InboundParse]] and lands in
 *     `Inbound.parsed`; `text` is deliberately NOT widened — its gate is pinned
 *     by six assertions in `FeishuChannelSpec`.
 *   · Field names on the model are camelCase getters over snake_case wire keys;
 *     this layer deliberately speaks the WIRE names (what a downstream consumer
 *     or an evidence file will carry), so the mapping is testable without
 *     constructing SDK objects.
 */
object FeishuMessage:

  /** Event-face mention, flattened to plain strings. Alias of the parse layer's
    * shape so the live half ([[FeishuChannel]]) can name it without importing
    * the parser, and so the SDK's nested `UserId` is unwrapped in exactly one
    * place (the caller), never inside the pure layer. */
  type InboundMention = InboundParse.Mention
  val InboundMention = InboundParse.Mention

  /** One inbound message, reduced to the fields the channel actually needs.
    *
    * `senderId` (feishubridge batch, 2026-09-25) = the sender's `open_id` when
    * the event carried one. It exists so the bridge layer can run a member
    * allowlist gate; it is appended LAST with a default so every pre-existing
    * positional/named construction stays source-compatible.
    *
    * `parentId` / `rootId` / `mentions` / `parsed` (inbound-parse batch,
    * 2026-10-01) carry the three inbound structures the channel used to discard:
    * the reply pointer (`parent_id` / `root_id` — Feishu keeps them on the envelope,
    * NOT inside `content`), the event's `mentions[]`, and the readable body
    * produced by [[InboundParse]]. All four are appended LAST with defaults, the
    * same compatibility rule `senderId` followed. `parsed` is `None` only when
    * the message genuinely carries nothing to show; a non-text type yields the
    * explicit [[InboundParse.unrecognized]] placeholder, never a silent drop.
    *
    * 🔴 `text` keeps its original meaning verbatim — "the body when the shape
    * really is text" — and [[textOf]] is unchanged. `parsed` is the wider slot:
    * text (mention-replaced) / post (degraded) / placeholder. */
  final case class Inbound(
    eventId: Option[String],
    messageId: String,
    chatId: String,
    messageType: String,
    contentRaw: String,
    text: Option[String],
    createTime: Option[String],
    senderId: Option[String] = None,
    parentId: Option[String] = None,
    rootId: Option[String] = None,
    mentions: List[InboundParse.Mention] = Nil,
    parsed: Option[String] = None
  ):
    /** The quoted-message id this inbound points at, if any. `parent_id` is the
      *  immediate parent; `root_id` is the thread root — the parent is the more
      *  specific answer, so it wins when both are present. */
    def quotedMessageId: Option[String] =
      parentId.map(_.trim).filter(_.nonEmpty).orElse(rootId.map(_.trim).filter(_.nonEmpty))

    /** The wire-shaped JSON a consumer reads. Carries NO credential material:
      *  the message body is the sender's own content, not ours. An open_id is
      *  the tenant-scoped sender identity, not a credential. */
    def toJson: Json =
      Json.obj(
        "eventId" -> (eventId match { case Some(v) => Json.fromString(v); case None => Json.Null }),
        "messageId" -> Json.fromString(messageId),
        "chatId" -> Json.fromString(chatId),
        "messageType" -> Json.fromString(messageType),
        "content" -> Json.fromString(contentRaw),
        "text" -> (text match { case Some(v) => Json.fromString(v); case None => Json.Null }),
        "createTime" -> (createTime match { case Some(v) => Json.fromString(v); case None => Json.Null }),
        "senderId" -> (senderId match { case Some(v) => Json.fromString(v); case None => Json.Null }),
        "parentId" -> (parentId match { case Some(v) => Json.fromString(v); case None => Json.Null }),
        "rootId" -> (rootId match { case Some(v) => Json.fromString(v); case None => Json.Null }),
        "mentions" -> Json.arr(mentions.map(m =>
          Json.obj(
            "key" -> Json.fromString(m.key),
            "name" -> (m.name match { case Some(v) => Json.fromString(v); case None => Json.Null }),
            "id" -> (m.id match { case Some(v) => Json.fromString(v); case None => Json.Null })
          ))*),
        "parsed" -> (parsed match { case Some(v) => Json.fromString(v); case None => Json.Null })
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
    *  are normalised in exactly one place.
    *
    *  The four inbound-parse fields are resolved here in the SAME edit as the
    *  field table (design card §9.2 #4: the field table, this call site and the
    *  `intake` triple are one and the same seam — they must never be split
    *  across commits and left to a three-way merge). */
  def inboundFrom(
    eventId: Option[String],
    messageId: Option[String],
    chatId: Option[String],
    messageType: Option[String],
    contentRaw: Option[String],
    createTime: Option[String],
    senderId: Option[String] = None,
    parentId: Option[String] = None,
    rootId: Option[String] = None,
    mentions: List[InboundParse.Mention] = Nil
  ): Either[String, Inbound] =
    val mt = messageType.getOrElse("")
    (messageId.filter(_.nonEmpty), chatId.filter(_.nonEmpty)) match
      case (Some(mid), Some(cid)) =>
        val raw = contentRaw.getOrElse("")
        val cleanMentions = mentions.filter(_.key.nonEmpty)
        Right(Inbound(
          eventId.filter(_.nonEmpty), mid, cid, mt, raw, textOf(mt, raw),
          createTime.filter(_.nonEmpty), senderId.filter(_.nonEmpty),
          parentId.map(_.trim).filter(_.nonEmpty),
          rootId.map(_.trim).filter(_.nonEmpty),
          cleanMentions,
          InboundParse.bodyOf(mt, mid, raw, cleanMentions)))
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
