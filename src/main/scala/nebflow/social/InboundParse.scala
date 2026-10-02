package nebflow.social

import io.circe.Json

/**
 * Channel-agnostic inbound STRUCTURE parser (inbound-parse batch, 2026-10-01).
 *
 * Everything here is a TOTAL FUNCTION over plain values — no network, no SDK
 * object, no IO. It exists because the inbound entry point used to drop every
 * message whose `message_type` was not `text` ([[FeishuMessage.textOf]] answers
 * `None` for those, and `FeishuBridgePlugin.intake` only logged a line). The
 * four shapes the channel actually receives — quoted reply, rich-text `post`,
 * `@`-mention placeholders, and anything else — are reduced here to a readable
 * body, and nothing is ever dropped silently: every branch has an explicit,
 * visible degradation.
 *
 * 🔴 Copy discipline (§16): every user-visible string in this file is a verbatim
 * template from the inbound-parse design card §0.3 / §5.3 / §6.2 / §10.3, which
 * the author reviewed word for word. Do not reword them here.
 *
 * Design card references:
 *   · §0.2 four classes, two artefacts each (parse criterion + degradation form)
 *   · §3.4 quoted-reply three branches
 *   · §4.2 recursive `post` degradation
 *   · §5.3 mention replacement rules
 *   · §6.2 unrecognised-type placeholder
 *   · §10.4 composition order: (1) source marker → (2) quoted block → (3) body
 */
object InboundParse:

  /** Context budget for the quoted original and for the raw content echoed in a
    * placeholder (design card §0.3, the unified size gate): beyond this the text is cut and the
    * cut is stated, never silent. */
  val BodyCap = 2000

  /** The `{sender_display}` fallback fixed by the source-marker template. */
  val UnknownSender = "未知发送方"

  /** One entry of an event's `mentions[]` (event face). `id` is the resolved
    * `user_id ?? open_id ?? union_id` — the caller normalises the SDK's nested
    * id object, this layer only carries plain strings. */
  final case class Mention(key: String, name: Option[String], id: Option[String])

  /** The markdown-ish `post` element namespace we render structurally. Unknown
    * tags are kept as a visible `[tag]` trace (design card §4.2: nothing is dropped silently). */
  private val PostTextTag = "text"
  private val PostLinkTag = "a"
  private val PostAtTag = "at"
  private val PostImageTag = "img"
  private val PostMediaTag = "media"

  /** Zero-width space, inserted between two adjacent `at` elements so two
    * mentions never fuse into one unreadable token (§4.2). */
  private val ZeroWidth = "\u200B"

  def truncate(s: String, cap: Int = BodyCap): String =
    if s.length <= cap then s else s.take(cap) + s"…（已截断，原文共 ${s.length} 字）"

  private def strField(j: Json, key: String): Option[String] =
    j.hcursor.downField(key).as[String].toOption.map(_.trim).filter(_.nonEmpty)

  /** A string field whose WHITESPACE IS CONTENT (element prose, link text).
    * Trimming these would weld two words together (`" 请看 "` is part of what
    * the sender wrote), so only emptiness is checked here. */
  private def rawField(j: Json, key: String): Option[String] =
    j.hcursor.downField(key).as[String].toOption

  // ─────────────────────────── @mentions (§5.3) ───────────────────────────

  /** Replace the `@_user_N` placeholders a text body carries with the display
    * name from the event's `mentions[]`.
    *
    *   · `name` wins; else the resolved `id`; else the fixed fallback `某人`.
    *   · A placeholder no `mention` matches is LEFT AS IS — a placeholder is a
    *     trace, deleting it would silently swallow text the sender wrote.
    *   · A `mention` with no placeholder in the body adds NOTHING: we never
    *     invent a mention the sender did not write.
    */
  def renderMentions(text: String, mentions: List[Mention]): String =
    mentions.foldLeft(text) { (acc, m) =>
      if m.key.isEmpty then acc
      else
        val display = m.name.filter(_.nonEmpty)
          .orElse(m.id.filter(_.nonEmpty))
          .getOrElse("某人")
        acc.replace("@" + m.key, "@" + display)
    }

  // ─────────────────────────── post rich text (§4.2) ───────────────────────────

  /** Render one `post` element. Total: an element that cannot be understood
    * still leaves a `[tag]` trace when it carries any non-empty string field. */
  def renderElement(e: Json): String =
    val tag = strField(e, "tag")
    val text = rawField(e, PostTextTag).getOrElse("")
    tag match
      case Some(PostTextTag) => text
      case Some(PostLinkTag) =>
        strField(e, "href") match
          case Some(href) => s"$text($href)"
          case None       => text
      case Some(PostAtTag) =>
        "@" + strField(e, "user_name").orElse(strField(e, "user_id")).getOrElse("某人")
      case Some(PostImageTag) =>
        strField(e, "image_key").map(k => s"[图片 image_key=$k]").getOrElse("[图片]")
      case Some(PostMediaTag) =>
        strField(e, "file_key").map(k => s"[视频 file_key=$k]").getOrElse("[视频]")
      case Some(other) =>
        // Unknown tag: keep its EXISTENCE visible. Only a truly empty element
        // is allowed to vanish (design card §4.2: only an empty element may disappear).
        if carriesAnyString(e) then s"[$other]" else ""
      case None =>
        text

  private def carriesAnyString(j: Json): Boolean =
    j.asObject.exists(_.toMap.exists { case (k, v) =>
      // `tag` is the LABEL, not content: an element carrying only its tag name
      // is the empty element the design card allows to disappear.
      k != "tag" && (v.asString.exists(_.nonEmpty) || v.isArray && v.asArray.exists(_.nonEmpty))
    })

  /** Degrade a `post` body to readable lines. Returns `None` when the payload
    * is not the documented `{"title":…, "content":[[element…]…]}` shape — the
    * caller then falls back to echoing the raw content rather than guessing. */
  def renderPost(contentRaw: String): Option[String] =
    io.circe.parser.parse(contentRaw).toOption.flatMap { json =>
      val content = json.hcursor.downField("content")
      val paragraphs = content.as[List[Json]].toOption.map { ps =>
        ps.map(_.asArray.map(_.toList).getOrElse(Nil)).map(renderParagraph)
      }
      paragraphs.map { lines =>
        val title = strField(json, "title")
        (title.toList ++ lines).mkString("\n")
      }
    }

  /** One paragraph = an element array; elements are concatenated with no
    * separator, except that two adjacent `at` elements get a zero-width space
    * so the two names do not fuse. */
  private def renderParagraph(elements: List[Json]): String =
    val sb = new StringBuilder
    var prevWasAt = false
    elements.foreach { e =>
      val isAt = strField(e, "tag").contains(PostAtTag)
      if prevWasAt && isAt then sb.append(ZeroWidth)
      sb.append(renderElement(e))
      prevWasAt = isAt
    }
    sb.toString

  // ─────────────────────────── unrecognised type (§6.2) ───────────────────────────

  /** The verbatim degraded placeholder. It carries the three mandatory fields —
    * the type name, the message id, and the (bounded) raw content — so a message
    * the channel cannot read is still traceable instead of vanishing. */
  def unrecognized(messageType: String, messageId: String, contentRaw: String): String =
    val bounded = truncate(contentRaw)
    s"""[未识别的消息类型：$messageType]
       |message_id=$messageId
       |原始内容：
       |$bounded
       |[/未识别的消息类型]""".stripMargin

  // ─────────────────────────── body selection (§0.2 four classes) ───────────────────────────

  /** Classify the inbound body into the readable third block of the injection
    * (§0.3 ③). `None` only when the message genuinely carries nothing (an empty
    * text body) — every other case has an explicit degradation. */
  def bodyOf(
      messageType: String,
      messageId: String,
      contentRaw: String,
      mentions: List[Mention] = Nil
  ): Option[String] =
    val rendered = messageType match
      case "post" =>
        renderPost(contentRaw).getOrElse(truncate(contentRaw))
      case "text" =>
        io.circe.parser.parse(contentRaw).toOption
          .flatMap(_.hcursor.downField("text").as[String].toOption)
          .map(t => renderMentions(t, mentions))
          .getOrElse(truncate(contentRaw))
      case _ =>
        unrecognized(messageType, messageId, contentRaw)
    // The size gate is applied uniformly to every class (design card §0.3 —
    // "体积闸（统一）"), so a body of any shape is bounded and its cut is stated.
    Option(truncate(rendered)).filter(_.nonEmpty)

  // ─────────────────────────── quoted reply (§3.4 three branches) ───────────────────────────

  /** The quoted-message block. Three branches, decided mechanically:
    *
    *   · no `parent_id` / `root_id` at all ⇒ `None`: the message is NOT a
    *     reply, so no block is emitted at all (branch C is a defensive slot —
    *     the card's `§3.4` branch C shape is produced only when a channel hands
    *     us an empty-string id, which normalises to `None` here).
    *   · the original text was fetched ⇒ branch A.
    *   · it was not (failure / no permission / timeout / empty parse) ⇒
    *     branch B, which NAMES the id it could not read.
    */
  def quoteSection(
      quotedMessageId: Option[String],
      quotedSender: Option[String],
      quotedText: Option[String]
  ): Option[String] =
    quotedMessageId.map(_.trim).filter(_.nonEmpty).map { id =>
      val sender = quotedSender.map(_.trim).filter(_.nonEmpty).getOrElse(UnknownSender)
      quotedText.map(_.trim).filter(_.nonEmpty) match
        case Some(text) =>
          s"""[引用的消息]
             |发送方：$sender
             |原文：
             |${truncate(text)}
             |[/引用的消息]""".stripMargin
        case None =>
          s"""[引用的消息]
             |发送方：$sender
             |原文：未取得（message_id=$id）
             |[/引用的消息]""".stripMargin
    }

  // ─────────────────────────── composition (§10.4) ───────────────────────────

  /** Compose the bridge-side part of the injection: **quoted block (2) then body
    * (3)**, in that order — the source marker (1) is prepended later, at the
    * unified injection point, by
    * [[nebflow.bridge.BridgeOrigin.compose]]. Two blocks are separated by one
    * blank line; a missing block leaves no stray separator. */
  def compose(quote: Option[String], body: Option[String]): Option[String] =
    val blocks = List(quote, body).flatten.filter(_.nonEmpty)
    if blocks.isEmpty then None else Some(blocks.mkString("\n\n"))

end InboundParse
