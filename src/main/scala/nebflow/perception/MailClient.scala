package nebflow.perception

import PerceptionModel.IncomingMail

import java.io.{BufferedReader, BufferedInputStream, InputStreamReader, OutputStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.{SSLSocket, SSLSocketFactory, SSLContext, TrustManagerFactory}
import scala.util.matching.Regex

/**
 * 极简 IMAP/SMTP 客户端（零第三方邮件依赖；标准库 TLS socket 直连）。
 *
 * 范围（骨架批，深度优先核心链路）：
 *   - IMAP：LOGIN / SELECT / UID SEARCH / UID FETCH（RFC822 整信）/ LOGOUT，
 *     字面量 `{n}` 读取，993 口隐式 TLS。IDLE 留作增强（本批自适应轮询：
 *     OTP 窗开 15s / 常态 60s，见 PerceptionService）。
 *   - SMTP：EHLO / AUTH PLAIN / MAIL / RCPT / DATA / QUIT；465 隐式 TLS 与
 *     587 STARTTLS 双轨；MIME multipart/mixed + alternative + 附件 base64。
 *
 * 纯函数面（`Rfc822` / `HeaderCodec` / `SmtpCodec`）全部可 spec；
 * socket 腿薄封装，生产连接由 PerceptionService 持有。
 */
object MailClient:

  // ════════════════════════ IMAP ════════════════════════

  // socket 形参放宽为 Socket（生产腿仍由 connectImap 建隐式 TLS 的 SSLSocket；
  // 放宽只为 spec 的本地明文 mock 服务器可直构会话）。
  final class ImapSession(socket: Socket) extends AutoCloseable:
    @volatile private var in: BufferedInputStream = new BufferedInputStream(socket.getInputStream, 64 * 1024)
    private var counter = 0

    private def nextTag: String =
      counter += 1
      s"a$counter"

    /**
     * Byte-level 行读取（单流所有者）。旧版 BufferedReader（字符流）与字面量
     * 直读（`in.read`）混流：readLine 会把同一 TCP 分段里 tag 行之后的字节
     * （含 `{n}` 字面量本体与后续行）预取进字符缓冲，字面量再按精确长度直读
     * 时字节已不在流里 ⇒ FETCH 挂死（spec 实测撞 5s SoTimeout）或内容错位。
     * 修后行按字节读到 `\n`、剥尾 `\r`、UTF-8 解码；字面量仍按长度直读——
     * 同一条流顺序消费，零混流。
     *
     * @return null = 干净 EOF（对齐旧 BufferedReader.readLine 语义）
     */
    private def readLine(): String =
      val first = in.read()
      if first < 0 then null
      else
        val buf = new scala.collection.mutable.ArrayBuffer[Byte](160)
        var b = first
        var atEnd = b == '\n'
        while !atEnd do
          buf += b.toByte
          b = in.read()
          atEnd = b < 0 || b == '\n'
        if buf.nonEmpty && buf.last == '\r' then buf.remove(buf.length - 1)
        new String(buf.toArray, StandardCharsets.UTF_8)

    /** Read a full response for `tag`, inlining `{n}` literals as raw bytes. */
    def readUntilTag(tag: String): String =
      val sb = new StringBuilder
      var done = false
      while !done do
        val line = readLine()
        if line == null then throw new java.io.IOException("IMAP connection closed by peer")
        sb.append(line).append("\r\n")
        literalLen(line) match
          case Some(n) =>
            val bytes = new Array[Byte](n)
            var off = 0
            while off < n do
              val r = in.read(bytes, off, n - off)
              if r < 0 then throw new java.io.IOException("EOF inside IMAP literal")
              off += r
            sb.append(new String(bytes, StandardCharsets.UTF_8)).append("\r\n")
          case None => ()
        done = line.startsWith(tag + " ")
      end while
      sb.toString

    end readUntilTag

    private def literalLen(line: String): Option[Int] =
      LiteralPattern.findAllMatchIn(line).toList.lastOption.map(_.group(1).toInt)

    /**
     * Send a command and consume its full response.
     *
     * 判定锚点 = tagged 完成行（RFC 3501 §7）：SELECT/SEARCH/FETCH 的响应
     * 以 untagged 数据行（`* …`）开头、tagged 完成行（`a2 OK/NO/BAD …`）
     * 收尾。旧版锚在 `resp.startsWith(tag OK)`（首行）——SELECT 的 untagged
     * 序列永不满足 ⇒ 成功响应整段被当错误倾倒（报障 2026-10-28：QQ 连接
     * 「* 4497 EXISTS …」被判错显示并 take(160) 半截截断；LOGIN 单行响应
     * 侥幸通过，故表现为「认证已通却报错」）。
     *
     * Right = 完整响应（untagged + tagged，供 EXISTS/SEARCH/FETCH 解析）；
     * Left = 人类可读短句（raw 线上数据不进错误串，UI 不再倾倒）。
     */
    def command(cmd: String): Either[String, String] =
      val tag = nextTag
      val read =
        try
          val out = socket.getOutputStream
          out.write(s"$tag $cmd\r\n".getBytes(StandardCharsets.UTF_8))
          out.flush()
          Right(readUntilTag(tag))
        catch
          // SocketTimeoutException ⊂ IOException，须先于 IOException 匹配。
          case _: java.net.SocketTimeoutException =>
            Left("IMAP 响应超时 · 服务器长时间无响应，请检查网络后重试")
          case e: java.io.IOException =>
            Left(s"IMAP 连接中断：${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")
          case e: Throwable =>
            Left(s"IMAP 会话异常：${e.getClass.getSimpleName}")
      read.flatMap(resp => MailClient.imapVerdict(tag, resp))

    end command

    /** Consume the greeting (`* OK` / `* PREAUTH`). */
    def readGreeting(): Either[String, Unit] =
      val line = readLine()
      if line == null then Left("IMAP: no greeting (connection closed)")
      else if line.startsWith("* OK") || line.startsWith("* PREAUTH") then Right(())
      else Left(s"IMAP greeting not OK: ${line.take(120)}")

    def login(user: String, authCode: String): Either[String, Unit] =
      command(s"""LOGIN "${escape(user)}" "${escape(authCode)}"""").map(_ => ())

    private def escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** SELECT INBOX → 统计（EXISTS/UNSEEN/UIDVALIDITY，untagged 行解析）。 */
    def selectInbox(): Either[String, InboxStats] =
      command("SELECT INBOX").map(resp => InboxStats.fromSelectResponse(resp))

    /** UIDs strictly greater than `afterUid` (0 = all → caller takes max for first sync). */
    def searchUids(afterUid: Long): Either[String, List[Long]] =
      val q = if afterUid <= 0 then "UID SEARCH ALL" else s"UID SEARCH UID ${afterUid + 1}:*"
      command(q).map { resp =>
        SearchLine.findFirstMatchIn(resp) match
          case None => Nil
          case Some(m) =>
            m.group(1).split("\\s+").flatMap(_.toLongOption).filter(_ > afterUid).toList.sorted
      }

    /** FETCH one message by UID as the exact RFC822 blob (literal length cut). */
    def fetchMessage(uid: Long): Either[String, String] =
      command(s"UID FETCH $uid (BODY.PEEK[])").map { resp =>
        val firstEnd = resp.indexOf("\r\n")
        val firstLine = if firstEnd >= 0 then resp.substring(0, firstEnd) else resp
        literalLen(firstLine) match
          case Some(len) =>
            val start = firstEnd + 2
            resp.substring(start, math.min(resp.length, start + len))
          case None => ""
      }

    def logout(): Unit =
      try command("LOGOUT")
      catch case _: Throwable => ()

    def close(): Unit =
      try socket.close()
      catch case _: Throwable => ()
  end ImapSession

  private val LiteralPattern: Regex = """\{(\d+)\}\s*$""".r
  private val ExistsPattern: Regex = """\* (\d+) EXISTS""".r.unanchored
  private val SearchLine: Regex = """(?s)\* SEARCH(.*)""".r

  /**
   * SELECT 统计面（连接成功态的数据源；报障 2026-10-28 要求的「已连接：
   * 收件箱 N 封、M 封未读」）。uidValidity 解析留存（UIDVALIDITY 变更时
   * lastSeenUid 需重置的同步正确性位，本批只解析不上屏）。
   */
  final case class InboxStats(exists: Int, unseen: Option[Int], uidValidity: Option[Long])

  object InboxStats:
    private val UnseenPattern: Regex = """\* OK \[UNSEEN (\d+)\]""".r.unanchored
    private val UidValidityPattern: Regex = """\* OK \[UIDVALIDITY (\d+)\]""".r.unanchored

    /** 纯函数（spec 直测截图同形状）：untagged 序列 → 统计。缺项容忍。 */
    def fromSelectResponse(resp: String): InboxStats =
      InboxStats(
        exists = ExistsPattern.findFirstMatchIn(resp).map(_.group(1).toInt).getOrElse(0),
        unseen = UnseenPattern.findFirstMatchIn(resp).map(_.group(1).toInt),
        uidValidity = UidValidityPattern.findFirstMatchIn(resp).map(_.group(1).toLong)
      )

  /**
   * 判定 + 错误面（纯函数，spec 直测）。完成行 = 响应最后一条非空行
   * （readUntilTag 恰在 tagged 行停笔）：`tag OK` ⇒ Right(完整响应)；
   * `tag NO/BAD` ⇒ Left(完成行本身——服务器的人类可读结论，如
   * 「NO [AUTHENTICATIONFAILED] …」，上限 200 字符防病态服务器)；
   * 无完成行 ⇒ 流被截断，Left 防御句（不倾倒 raw）。
   */
  def imapVerdict(tag: String, resp: String): Either[String, String] =
    val completion = resp.linesIterator.map(_.trim).filter(_.nonEmpty).toList.lastOption.getOrElse("")
    def startsWithWord(w: String): Boolean = completion.startsWith(s"$tag $w")
    if startsWithWord("OK") then Right(resp)
    else if startsWithWord("NO") then Left(s"IMAP NO ${completion.drop(tag.length + 1).trim.take(200)}")
    else if startsWithWord("BAD") then Left(s"IMAP BAD ${completion.drop(tag.length + 1).trim.take(200)}")
    else Left("IMAP 响应不完整：未收到完成行 · 请重试")

  def connectImap(host: String, port: Int, timeoutMs: Int = 15000): Either[String, ImapSession] =
    try
      val socket = tlsFactory.createSocket().asInstanceOf[SSLSocket]
      socket.setSoTimeout(timeoutMs)
      socket.connect(new InetSocketAddress(host, port), timeoutMs)
      val s = new ImapSession(socket)
      s.readGreeting().map(_ => s) match
        case Right(sess) => Right(sess)
        case Left(err) =>
          s.close()
          Left(err)
    catch case e: Throwable => Left(s"IMAP connect ${e.getClass.getSimpleName}: ${e.getMessage}")

  // ════════════════════ RFC822 / MIME 解析（纯函数）════════════════════

  /** Shallow RFC822 parse: top-level headers + raw body. */
  object Rfc822:

    def parse(raw: String): (Map[String, String], String) =
      val sep = raw.indexOf("\r\n\r\n")
      val alt = raw.indexOf("\n\n")
      val cut =
        if sep >= 0 && (alt < 0 || sep <= alt) then (sep, 4)
        else if alt >= 0 then (alt, 2)
        else (-1, 0)
      val (headStr, body) =
        if cut._1 < 0 then (raw, "")
        else (raw.substring(0, cut._1), raw.substring(cut._1 + cut._2))
      val headers = scala.collection.mutable.Map.empty[String, String]
      var lastKey = ""
      headStr.linesIterator.foreach { line =>
        if line.startsWith(" ") || line.startsWith("\t") then
          if lastKey.nonEmpty then headers(lastKey) = headers(lastKey) + " " + line.trim
        else
          val ci = line.indexOf(':')
          if ci > 0 then
            lastKey = line.take(ci).trim.toLowerCase
            headers(lastKey) = line.drop(ci + 1).trim
      }
      (headers.toMap, body)

    end parse

    /** Extract readable text from a (possibly multipart) body. */
    def bodyText(headers: Map[String, String], body: String): String =
      val cte = headers.getOrElse("content-transfer-encoding", "").toLowerCase
      val decoded =
        if cte.contains("base64") then b64DecodeText(body)
        else if cte.contains("quoted-printable") then qpDecode(body)
        else body
      val ctypeRaw = headers.getOrElse("content-type", "")
      val ctype = ctypeRaw.toLowerCase
      if ctype.contains("boundary=") then
        // boundary 值大小写敏感（RFC 2046）——必须从原始头取，禁用小写化后的串。
        BoundaryPattern.findFirstMatchIn(ctypeRaw).map(_.group(1)) match
          case Some(b) =>
            val parts = splitMultipart(body, b)
            val isPlain = (h: Map[String, String]) => h.getOrElse("content-type", "").toLowerCase.contains("text/plain")
            val isHtml = (h: Map[String, String]) => h.getOrElse("content-type", "").toLowerCase.contains("text/html")
            val isNested = (h: Map[String, String]) =>
              h.getOrElse("content-type", "").toLowerCase.contains("multipart/")
            val plain = parts.find(p => isPlain(p._1))
            val html = parts.find(p => isHtml(p._1))
            val nested = parts.find(p => isNested(p._1))
            plain
              .map(p => bodyText(p._1, p._2))
              .orElse(html.map(p => DraftCodec.htmlToText(bodyText(p._1, p._2))))
              .orElse(nested.map(p => bodyText(p._1, p._2))) // mixed→alternative 深取
              .getOrElse(decoded)
          case None => decoded
      else if ctype.contains("text/html") then DraftCodec.htmlToText(decoded)
      else decoded

      end if

    end bodyText

    private def splitMultipart(body: String, boundary: String): List[(Map[String, String], String)] =
      val delim = "--" + boundary
      body.split(java.util.regex.Pattern.quote(delim)).toList.drop(1).takeWhile(c => !c.startsWith("--")).map { c =>
        parse(c.stripPrefix("\r\n").stripPrefix("\n"))
      }

    def firstAddress(value: String): String =
      AngleAddr.findFirstMatchIn(value).map(_.group(1)).getOrElse(value.trim)
  end Rfc822

  private val BoundaryPattern: Regex = """(?i)boundary="?([^";]+)"?""".r
  private val AngleAddr: Regex = """<([^>]+)>""".r

  def b64DecodeText(s: String): String =
    try
      val clean = s.replaceAll("\\s+", "")
      val padded = clean + ("=" * ((4 - clean.length % 4) % 4))
      new String(Base64.getMimeDecoder.decode(padded), StandardCharsets.UTF_8)
    catch case _: Throwable => s

  def qpDecode(s: String): String =
    try
      val noSoft = s.replaceAll("=\r?\n", "")
      val out = new java.io.ByteArrayOutputStream
      val bytes = noSoft.getBytes(StandardCharsets.ISO_8859_1)
      var i = 0
      while i < bytes.length do
        if bytes(i) == '=' && i + 2 < bytes.length then
          val hex = new String(bytes, i + 1, 2, StandardCharsets.ISO_8859_1)
          out.write(java.lang.Integer.parseInt(hex, 16) & 0xff)
          i += 3
        else
          out.write(bytes(i) & 0xff); i += 1
      new String(out.toByteArray, StandardCharsets.UTF_8)
    catch case _: Throwable => s

  /** RFC2047 header decoding (B/Q encodings). */
  object HeaderCodec:
    private val EncodedWord: Regex = """=\?([^?]+)\?([bBqQ])\?([^?]*)\?=""".r

    def decode(value: String): String =
      EncodedWord.replaceAllIn(
        value,
        m =>
          val charset = m.group(1)
          val enc = m.group(2).toLowerCase
          val payload = m.group(3)
          val text = if enc == "b" then b64DecodeText(payload) else qpDecode(payload.replace('_', ' '))
          reencode(text, charset)
      )

    private def reencode(text: String, charset: String): String =
      try
        if charset.toLowerCase.contains("utf-8") then text
        else new String(text.getBytes("ISO-8859-1"), java.nio.charset.Charset.forName(charset))
      catch case _: Throwable => text
  end HeaderCodec

  // ═════════════════ IncomingMail 组装 ═════════════════

  def toIncomingMail(uid: Long, raw: String): IncomingMail =
    val (headers, body) = Rfc822.parse(raw)
    // 地址统一小写（域归一/信任发件人匹配的单一形态）。
    val from = Rfc822.firstAddress(HeaderCodec.decode(headers.getOrElse("from", ""))).toLowerCase
    IncomingMail(
      uid = uid,
      from = from,
      fromDomain = MailboxPresets.domainOf(from),
      to = HeaderCodec.decode(headers.getOrElse("to", "")).split("[,;]").map(_.trim).filter(_.contains("@")).toList,
      subject = HeaderCodec.decode(headers.getOrElse("subject", "")),
      date = headers.getOrElse("date", ""),
      messageId = headers.getOrElse("message-id", "").trim,
      bodyText = Rfc822.bodyText(headers, body)
    )

  // ════════════════════════ SMTP ════════════════════════

  case class OutgoingMail(
    from: String,
    fromName: String,
    to: List[String],
    cc: List[String],
    bcc: List[String],
    subject: String,
    bodyHtml: String,
    attachments: List[(String, Array[Byte])] = Nil, // (filename, bytes)
    inReplyTo: Option[String] = None
  )

  object SmtpCodec:

    /** Build the full DATA payload (headers + MIME, dot-stuffing applied). */
    def buildData(mail: OutgoingMail): String =
      val boundary = s"=_perception_${java.lang.Long.toString(System.currentTimeMillis(), 36)}_${rand6()}"
      val altBoundary = s"=_alt_${rand6()}"
      val sb = new StringBuilder
      val fromAddr = addrOnly(mail.from)
      if mail.fromName.nonEmpty then sb.append(s"From: =?UTF-8?B?${b64(mail.fromName)}?= <$fromAddr>\r\n")
      else sb.append(s"From: <$fromAddr>\r\n")
      sb.append(s"To: ${mail.to.map(addrOnly).mkString(", ")}\r\n")
      if mail.cc.nonEmpty then sb.append(s"Cc: ${mail.cc.map(addrOnly).mkString(", ")}\r\n")
      if mail.bcc.nonEmpty then sb.append(s"Bcc: ${mail.bcc.map(addrOnly).mkString(", ")}\r\n")
      mail.inReplyTo.foreach(r => sb.append(s"In-Reply-To: $r\r\n"))
      sb.append(s"Subject: =?UTF-8?B?${b64(mail.subject)}?=\r\n")
      sb.append(
        s"Date: ${java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.ZonedDateTime.now())}\r\n"
      )
      sb.append(s"Message-ID: <${System.currentTimeMillis()}.$boundary@perception.local>\r\n")
      sb.append("MIME-Version: 1.0\r\n")
      sb.append(s"Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n")
      sb.append(s"--$boundary\r\n")
      sb.append(s"Content-Type: multipart/alternative; boundary=\"$altBoundary\"\r\n\r\n")
      val textPlain = DraftCodec.htmlToText(mail.bodyHtml)
      sb.append("--")
        .append(altBoundary)
        .append("\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
      sb.append(wrap76(Base64.getEncoder.encodeToString(textPlain.getBytes(StandardCharsets.UTF_8)))).append("\r\n")
      sb.append("--")
        .append(altBoundary)
        .append("\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
      sb.append(wrap76(Base64.getEncoder.encodeToString(mail.bodyHtml.getBytes(StandardCharsets.UTF_8)))).append("\r\n")
      sb.append(s"--$altBoundary--\r\n")
      mail.attachments.foreach { (name, bytes) =>
        sb.append(s"--$boundary\r\n")
        sb.append(s"Content-Type: application/octet-stream; name=\"${sanitizeHeader(name)}\"\r\n")
        sb.append(s"Content-Disposition: attachment; filename=\"${sanitizeHeader(name)}\"\r\n")
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n")
        sb.append(wrap76(Base64.getEncoder.encodeToString(bytes))).append("\r\n")
      }
      sb.append(s"--$boundary--\r\n")
      dotStuff(sb.toString)

    end buildData

    private def addrOnly(s: String): String =
      AngleAddr.findFirstMatchIn(s).map(_.group(1)).getOrElse(s.trim)

    private def sanitizeHeader(s: String): String = s.replaceAll("[\r\"]", "_")

    private def wrap76(s: String): String = s.grouped(76).mkString("\r\n")

    private def b64(s: String): String = Base64.getEncoder.encodeToString(s.getBytes(StandardCharsets.UTF_8))

    /** DATA 点填充：行首 `.` → `..`。 */
    def dotStuff(payload: String): String =
      payload.split("\r\n", -1).map(l => if l.startsWith(".") then "." + l else l).mkString("\r\n")

    /** Pull our own Message-ID back out of the built payload (receipt face). */
    def messageIdOf(data: String): String =
      val (headers, _) = Rfc822.parse(data)
      headers.getOrElse("message-id", "")
  end SmtpCodec

  def rand6(): String = f"${java.util.concurrent.ThreadLocalRandom.current().nextLong(0xffffffL)}%06x"

  /** Send via SMTP. Right(messageId) or Left(readable failure). */
  def send(
    smtpHost: String,
    smtpPort: Int,
    implicitTls: Boolean,
    user: String,
    authCode: String,
    mail: OutgoingMail,
    timeoutMs: Int = 20000
  ): Either[PerceptionModel.SendFailure, String] =
    var socket: Socket = null
    try
      socket = if implicitTls then
        val s = tlsFactory.createSocket()
        s.setSoTimeout(timeoutMs)
        s.connect(new InetSocketAddress(smtpHost, smtpPort), timeoutMs)
        s
      else
        val s = new Socket()
        s.setSoTimeout(timeoutMs)
        s.connect(new InetSocketAddress(smtpHost, smtpPort), timeoutMs)
        s
      var in = new BufferedReader(new InputStreamReader(socket.getInputStream, StandardCharsets.ISO_8859_1))
      var out: OutputStream = socket.getOutputStream

      def readReply(): Either[PerceptionModel.SendFailure, String] =
        val sb = new StringBuilder
        var line = in.readLine()
        var keep = Option(line)
        while keep.isDefined do
          val l = keep.get
          sb.append(l).append("\r\n")
          if l.length > 3 && l.charAt(3) == '-' then keep = Option(in.readLine())
          else keep = None
        val resp = sb.toString
        if resp.startsWith("2") || resp.startsWith("3") then Right(resp)
        else if resp.startsWith("535") || resp.startsWith("534") || resp.startsWith("530")
        then Left(PerceptionModel.SendFailure.AuthFailed)
        else Left(PerceptionModel.SendFailure.Rejected(resp.trim.take(200)))

      def cmd(c: String): Either[PerceptionModel.SendFailure, String] =
        out.write(c.getBytes(StandardCharsets.UTF_8))
        out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1))
        out.flush()
        readReply()

      val fromAddr = AngleAddr.findFirstMatchIn(mail.from).map(_.group(1)).getOrElse(mail.from.trim)
      val recipients =
        (mail.to ++ mail.cc ++ mail.bcc).map(r => AngleAddr.findFirstMatchIn(r).map(_.group(1)).getOrElse(r.trim))

      val result = for
        _ <- readReply() // 220 greeting
        _ <- cmd("EHLO perception.local")
        _ <-
          if implicitTls then Right("tls-already")
          else
            cmd("STARTTLS").flatMap { _ =>
              val ssl = tlsFactory.createSocket(socket, smtpHost, smtpPort, true).asInstanceOf[SSLSocket]
              ssl.setSoTimeout(timeoutMs)
              socket = ssl
              in = new BufferedReader(new InputStreamReader(ssl.getInputStream, StandardCharsets.ISO_8859_1))
              out = ssl.getOutputStream
              cmd("EHLO perception.local")
            }
        _ <- cmd(
          "AUTH PLAIN " + Base64.getEncoder.encodeToString(
            s"\u0000$user\u0000$authCode".getBytes(StandardCharsets.UTF_8)
          )
        )
        _ <- cmd(s"MAIL FROM:<$fromAddr>")
        _ <- recipients.foldLeft[Either[PerceptionModel.SendFailure, Unit]](Right(())) { (acc, r) =>
          acc.flatMap(_ => cmd(s"RCPT TO:<$r>").map(_ => ()))
        }
        _ <- cmd("DATA")
        data = SmtpCodec.buildData(mail)
        _ =
          out.write(data.getBytes(StandardCharsets.UTF_8))
          out.write(".\r\n".getBytes(StandardCharsets.ISO_8859_1))
          out.flush()
          val _reply = readReply()
          ()
        _ =
          val _q = cmd("QUIT"); ()
      yield SmtpCodec.messageIdOf(data)
      result
    catch
      case e: Throwable =>
        Left(PerceptionModel.SendFailure.Network(s"${e.getClass.getSimpleName}: ${e.getMessage}"))
    finally
      if socket != null then
        try socket.close()
        catch case _: Throwable => ()
    end try
  end send

  // ════════════════════════ TLS ════════════════════════

  private lazy val tlsFactory: SSLSocketFactory =
    val ctx = SSLContext.getInstance("TLS")
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(null.asInstanceOf[java.security.KeyStore])
    ctx.init(null, tmf.getTrustManagers, new SecureRandom())
    ctx.getSocketFactory

end MailClient
