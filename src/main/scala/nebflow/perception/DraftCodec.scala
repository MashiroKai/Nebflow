package nebflow.perception

/**
 * 发送草稿编解码（r2 §7.2，落盘形态；UI 永不露出——画布面走结构化编辑器）。
 *
 * 文件 = front-matter + 正文。front-matter 字段：
 *   draft / to / cc / bcc / subject / attach（可多行）
 *
 * 正文形态：本批落盘为 HTML 片段（画布富文本编辑器的 innerHTML 序列化），
 * 发送侧原样作为 text/html part；同时派生纯文本（标签剥除）作 text/plain part。
 * 引用上下文（回复时带的原文引用块）= 正文尾部的 `<blockquote>` 片段，随 HTML
 * 一起保存与发送——无需独立字段。
 */
object DraftCodec:

  val DraftMarker = "perception-mail-send"

  case class DraftDoc(
    draftId: String,
    to: List[String],
    cc: List[String],
    bcc: List[String],
    subject: String,
    bodyHtml: String,
    attachments: List[String],
    /** true = 这是对某封来信的回复（画布头部显示引用上下文行）。 */
    replyToMessageId: Option[String] = None,
    /** 引用上下文（来信摘要块，画布只读展示；随正文进发送 HTML）。 */
    quoteHtml: Option[String] = None
  )

  /** Parse a draft file body. Tolerant: missing fields → empty; bad lines skipped. */
  def parse(draftId: String, raw: String): Either[String, DraftDoc] =
    val lines = raw.linesIterator.toList
    val trimmed = lines.dropWhile(_.isBlank)
    if !trimmed.headOption.exists(_.trim == "---") then Left("draft front-matter missing (expected leading '---')")
    else
      val rest = trimmed.tail
      val closeIdx = rest.indexWhere(_.trim == "---")
      if closeIdx < 0 then Left("draft front-matter not closed (missing '---')")
      else
        val header = rest.take(closeIdx)
        val body = rest.drop(closeIdx + 1).dropWhile(_.isBlank).mkString("\n")
        var to = List.empty[String]
        var cc = List.empty[String]
        var bcc = List.empty[String]
        var subject = ""
        var attach = List.empty[String]
        var replyTo = Option.empty[String]
        header.foreach { line =>
          val ci = line.indexOf(':')
          if ci > 0 then
            val key = line.take(ci).trim.toLowerCase
            val value = line.drop(ci + 1).trim
            key match
              case "to" => to = parseAddresses(value)
              case "cc" => cc = parseAddresses(value)
              case "bcc" => bcc = parseAddresses(value)
              case "subject" => subject = value
              case "attach" => if value.nonEmpty then attach = attach :+ value
              case "replyto" | "reply-to" | "inreplyto" => if value.nonEmpty then replyTo = Some(value)
              case "draft" => () // marker line, informational
              case _ => () // unknown key tolerated (forward-compat)
        }
        val (quote, pureBody) = splitQuote(body)
        Right(DraftDoc(draftId, to, cc, bcc, subject, pureBody, attach, replyTo, quote))

      end if

    end if

  end parse

  /** Serialize a doc back to the canonical file form. */
  def serialize(doc: DraftDoc): String =
    val sb = new StringBuilder
    sb.append("---\n")
    sb.append(s"draft: $DraftMarker\n")
    sb.append(s"to: ${doc.to.mkString(", ")}\n")
    if doc.cc.nonEmpty then sb.append(s"cc: ${doc.cc.mkString(", ")}\n")
    if doc.bcc.nonEmpty then sb.append(s"bcc: ${doc.bcc.mkString(", ")}\n")
    sb.append(s"subject: ${doc.subject}\n")
    doc.attachments.foreach(a => sb.append(s"attach: $a\n"))
    doc.replyToMessageId.foreach(r => sb.append(s"replyTo: $r\n"))
    sb.append("---\n\n")
    // 正文在前、引用在后（= splitQuote 的「引用块剥自尾部」契约；写反会让
    // 往返解析把正文吞进引用块）。
    sb.append(doc.bodyHtml).append("\n\n")
    doc.quoteHtml.foreach(q => sb.append(q.trim).append("\n"))
    sb.toString

  end serialize

  /** Comma/space separated addresses → list. */
  def parseAddresses(value: String): List[String] =
    value.split("[,;，；\\s]+").map(_.trim).filter(_.nonEmpty).filter(_.contains("@")).toList

  /** Strip tags → plain text (the text/plain part; blockquote included). */
  def htmlToText(html: String): String =
    html
      .replaceAll("(?i)<br\\s*/?>", "\n")
      .replaceAll("(?i)</(p|div|li|blockquote|h[1-6])>", "\n")
      .replaceAll("<[^>]+>", "")
      .replace("&nbsp;", " ")
      .replace("&amp;", "&")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replaceAll("\n{3,}", "\n\n")
      .trim

  /**
   * 正文尾部的引用块（`<perception-quote>` 或裸 `<blockquote>` 开头到文末）
   * 剥出来作为 quoteHtml——画布将其渲染为只读引用上下文。
   */
  private val QuoteOpen = """(?i)<perception-quote[^>]*>|<blockquote[^>]*>""".r

  def splitQuote(body: String): (Option[String], String) =
    QuoteOpen.findFirstMatchIn(body) match
      case None => (None, body)
      case Some(m) =>
        val quote = body.substring(m.start)
        val rest = body.substring(0, m.start)
        // close an unterminated tag so the HTML stays well-formed enough for mail clients
        val closed =
          if quote.toLowerCase.contains("</blockquote>") || quote.toLowerCase.contains("</perception-quote>")
          then quote
          else quote + "</blockquote>"
        (Some(closed), rest.trim)

  /** Address header value (SMTP): `"Name" <addr>` or bare addr; we send bare addrs. */
  def addrHeader(addrs: List[String]): String = addrs.mkString(", ")

  /** 邮箱地址形态粗校验（配置卡「连接」测试前的一道本地尺，非最终判据）。 */
  def looksLikeAddress(s: String): Boolean =
    val at = s.indexOf('@')
    at > 0 && at < s.length - 1 && !s.contains(' ') && s.drop(at + 1).contains('.')
end DraftCodec
