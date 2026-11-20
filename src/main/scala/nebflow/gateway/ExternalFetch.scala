package nebflow.gateway

import cats.effect.IO
import nebflow.gateway.ExternalOpenPolicy as P
import nebflow.gateway.ExternalOpenPolicy.Refusal

import java.io.{BufferedInputStream, FileOutputStream, InputStream}
import java.net.{InetAddress, URI}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.{SNIHostName, SSLSocket, SSLSocketFactory}

import scala.util.control.NoStackTrace

/**
 * 外部 URL 拉取的**动作面**（external-open 批，2026-11-19）：判据全在
 * [[ExternalOpenPolicy]]，本对象只负责「按判据把字节取回来、落到指定临时文件」。
 *
 * ## 为什么不用现成的 `HttpClient`
 *
 * SSRF 硬闸要的是「**连到我们判过的那一个地址**」。`java.net.http.HttpClient`
 * 在 `send` 里自己解析域名，判据与连接之间留着一个 DNS 重绑定窗口；它的自动
 * 重定向（`Redirect.NORMAL`，见 `shared/http.scala`）也会把「跳去私网」的一跳
 * 直接吃掉。所以这里用 **socket 级**的最小 HTTP/1.1 客户端：
 *
 * - 判据先解析、先判地址，然后 `socket.connect(<判过的那一个 InetAddress>)` ——
 *   连接目标就是判据看到的目标，不存在「判 A 连 B」；
 * - TLS 用 `createSocket(plain, host, port, true)` + `endpointIdentificationAlgorithm
 *   = "HTTPS"`：SNI 与证书校验仍按**域名**（不是按 IP），所以固定地址不会削弱
 *   TLS 校验，也不会把 `https://` 降级成明文；
 * - 重定向**不自动跟随**：每一跳回到同一发闸（scheme → host 名 → 解析 → 地址），
 *   跳去私网的那一跳就是一次独立的 `url-private-address` 拒绝（spec 的负控 ②）。
 *
 * ## 预算（全部来自 [[ExternalOpenPolicy]]，此处零第二份数）
 *
 * 连接/读/总时限、字节上限、重定向跳数；**边下边判**大小（`append` 在每次写盘前
 * 查上限 ⇒ 超限不会先收完再拒）。
 *
 * ## 出向形状
 *
 * 只发一条 `GET`，`Accept-Encoding: identity`（不引入解压面：解压炸弹是另一类
 * 资源攻击，本面服务的是「看一份文档」），`Connection: close`。
 */
object ExternalFetch:

  /** 一次成功拉取的读数。`head` 只前 [[P.HeadBytes]] 字节，供路由嗅探类型。 */
  final case class Outcome(
    finalUri: URI,
    sha256: String,
    size: Long,
    head: Array[Byte],
    contentType: String
  )

  // ── 内部失败形态（统一收敛成 Refusal，不向路由泄漏异常分类）─────────────
  private final case class TooLarge(limit: Long) extends NoStackTrace:
    override def getMessage: String = s"the response exceeds the ${limit} byte limit"

  private final case class Deadline(limitMs: Long) extends NoStackTrace:
    override def getMessage: String = s"the fetch exceeded the ${limitMs / 1000}s deadline"

  private final case class BadResponse(detail: String) extends NoStackTrace:
    override def getMessage: String = detail

  /** 一跳的结局：要么跳到别处，要么拿全了。 */
  private enum Hop:
    case Redirect(to: URI)
    case Done(outcome: Outcome)

  /**
   * 拉取入口。`tmpFile` 由调用方给（路由给 `<dataRoot>/external-fetch/.tmp-*`），
   * 失败时本函数负责删掉半成品 —— 调用方只处理 `Either`。
   */
  /** 一发闸的类型别名（生产默认 = `ExternalOpenPolicy.gateUrl`）。 */
  type Gate = URI => Either[Refusal, (URI, String, List[InetAddress])]

  /**
   * @param maxBytes 字节上限（spec 用小值验证「超限即拒」，生产用 [[P.MaxUrlBytes]]）
   * @param resolve  DNS 解析器（spec 注入合成表 = 「DNS 重定向到私网」的负控）
   * @param gate     每跳的判据。**默认就是生产闸**；spec 只在「必须允许 loopback
   *                 才能把客户端本身测到」时注入自己的判据（见 ExternalFetchSpec
   *                 的两条注入：正常拉取 / 跳转到私网仍被拒）。没有任何配置/
   *                 环境变量路径能改到这个参数——生产装配点只有 `ExternalOpenRoutes`
   *                 的默认值一处。
   */
  def fetch(
    rawUrl: String,
    tmpFile: Path,
    maxBytes: Long = P.MaxUrlBytes,
    resolve: String => List[InetAddress] = P.resolveHost,
    now: () => Long = () => System.currentTimeMillis(),
    gate: Gate = null
  ): IO[Either[Refusal, Outcome]] =
    // 默认闸 = 生产闸（`ExternalOpenPolicy.gateUrl` 配当前解析器）。写成 `null`
    // 缺省而不是 lambda 缺省，是因为 Scala 3 的默认实参不允许引用**同一参数表**
    // 里的前置参数；装配点仍只有 `ExternalOpenRoutes` 的一处默认调用。
    val gateFn: Gate = if gate != null then gate else uri => P.gateUrl(uri.toString, resolve)
    val deadline = now() + P.UrlTotalDeadlineMs
    def fail(e: Throwable): Refusal = e match
      case t: TooLarge => Refusal(413, "too-large", t.getMessage)
      case d: Deadline => Refusal(504, "timeout", d.getMessage)
      case b: BadResponse => Refusal(502, "fetch-failed", b.getMessage)
      case other =>
        Refusal(
          502,
          "fetch-failed",
          s"${other.getClass.getSimpleName}: ${Option(other.getMessage).getOrElse("no detail")}"
        )
    def hop(uri: URI, hopsLeft: Int): IO[Either[Refusal, Outcome]] =
      gateFn(uri) match
        case Left(refusal) => IO.pure(Left(refusal))
        case Right((judged, host, addrs)) =>
          IO.blocking(transferOnce(judged, host, addrs.head, tmpFile, maxBytes, deadline)).attempt.flatMap {
            case Left(e) => IO.pure(Left(fail(e)))
            case Right(Hop.Done(o)) => IO.pure(Right(o))
            case Right(Hop.Redirect(to)) =>
              if hopsLeft <= 0 then
                IO.pure(Left(Refusal(502, "too-many-redirects", s"more than ${P.MaxRedirects} redirects")))
              else
                val next: Either[Refusal, URI] =
                  try Right(if Option(to.getScheme).exists(_.nonEmpty) then to else uri.resolve(to))
                  catch case _: Throwable => Left(Refusal(502, "fetch-failed", "redirect target is not a valid URL"))
                next match
                  case Left(r) => IO.pure(Left(r))
                  case Right(u) => hop(u, hopsLeft - 1) // 每一跳重新过闸
          }
    val start: Either[Refusal, URI] =
      try Right(new URI(Option(rawUrl).getOrElse("").trim))
      catch case _: Throwable => Left(Refusal(400, "url-malformed", "the value is not a valid URL"))
    start match
      case Left(r) => IO.pure(Left(r))
      case Right(uri) =>
        hop(uri, P.MaxRedirects).flatTap {
          case Left(_) => IO.blocking(Files.deleteIfExists(tmpFile)).attempt.void
          case Right(_) => IO.unit
        }
  end fetch

  // ────────────────────────────────────────────────────────────────────────
  // 一跳
  // ────────────────────────────────────────────────────────────────────────

  private def transferOnce(
    uri: URI,
    host: String,
    addr: InetAddress,
    tmpFile: Path,
    maxBytes: Long,
    deadline: Long
  ): Hop =
    val scheme = Option(uri.getScheme).map(_.toLowerCase(Locale.ROOT)).getOrElse("http")
    val defaultPort = if scheme == "https" then 443 else 80
    val port = if uri.getPort > 0 then uri.getPort else defaultPort
    val socket = openSocket(scheme, host, addr, port)
    try
      val output = socket.getOutputStream
      val input = new BufferedInputStream(socket.getInputStream)
      output.write(requestBytes(uri, host, port, defaultPort))
      output.flush()
      val (status, headers) = readHead(input)
      if status >= 300 && status < 400 then
        headers.get("location").filter(_.nonEmpty) match
          case Some(loc) =>
            val next =
              try new URI(loc)
              catch case _: Throwable => throw BadResponse(s"malformed Location header: $loc")
            Hop.Redirect(next)
          case None => throw BadResponse(s"HTTP $status without a Location header")
      else if status < 200 || status >= 300 then throw BadResponse(s"the origin answered HTTP $status")
      else
        val (size, digest, head) = readBody(headers, input, tmpFile, maxBytes, deadline)
        Hop.Done(Outcome(uri, digest, size, head, headers.getOrElse("content-type", "")))
    finally
      try socket.close()
      catch case _: Throwable => ()
    end try
  end transferOnce

  private def openSocket(scheme: String, host: String, addr: InetAddress, port: Int): java.net.Socket =
    val plain = new java.net.Socket()
    try
      plain.connect(new java.net.InetSocketAddress(addr, port), P.UrlConnectTimeoutMs)
      plain.setSoTimeout(P.UrlReadTimeoutMs)
      if scheme == "https" then
        // `createSocket(plain, host, port, true)`：peerHost = 域名 ⇒ SNI 与证书校验
        // 都按域名走，只有**连接目标**被钉在判过的地址上。
        val factory = SSLSocketFactory.getDefault.asInstanceOf[SSLSocketFactory]
        val ssl = factory.createSocket(plain, host, port, true).asInstanceOf[SSLSocket]
        val params = ssl.getSSLParameters
        params.setEndpointIdentificationAlgorithm("HTTPS")
        try params.setServerNames(java.util.List.of(new SNIHostName(host)))
        catch case _: Throwable => () // 非 ASCII 域名等：SNI 缺席不削弱证书校验
        ssl.setSSLParameters(params)
        ssl.startHandshake()
        ssl
      else plain
    catch
      case e: Throwable =>
        try plain.close()
        catch case _: Throwable => ()
        throw e

    end try

  end openSocket

  private def requestBytes(uri: URI, host: String, port: Int, defaultPort: Int): Array[Byte] =
    val path = Option(uri.getRawPath).filter(_.nonEmpty).getOrElse("/")
    val query = Option(uri.getRawQuery).map(q => if q.isEmpty then "" else "?" + q).getOrElse("")
    val hostLiteral = if host.contains(":") then s"[$host]" else host
    val hostHeader = if port == defaultPort then hostLiteral else s"$hostLiteral:$port"
    val agent = s"${nebflow.shared.Branding.lowerName}-external-open"
    val text =
      s"GET $path$query HTTP/1.1\r\n" +
        s"Host: $hostHeader\r\n" +
        s"User-Agent: $agent\r\n" +
        "Accept: */*\r\n" +
        "Accept-Encoding: identity\r\n" +
        "Connection: close\r\n\r\n"
    text.getBytes(StandardCharsets.ISO_8859_1)

  /**
   * 读响应头：状态行 + 逐行头（空行结束）。行读用同一个 [[readLine]]，
   * 避免「按字节扫 \r\n\r\n」在边界上多吃/少吃一截（吃少 = 头字节混进 body）。
   */
  private def readHead(input: InputStream): (Int, Map[String, String]) =
    val statusLine = readLine(input)
    val parts = statusLine.split(" ").filter(_.nonEmpty)
    val status = if parts.length >= 2 then parts(1).takeWhile(_.isDigit).toIntOption.getOrElse(0) else 0
    if status == 0 then throw BadResponse(s"malformed status line: $statusLine")
    var headers = Map.empty[String, String]
    var budget = statusLine.length
    var line = readLine(input)
    while line.nonEmpty do
      budget += line.length
      if budget > P.MaxHeaderBytes then throw BadResponse("response headers exceed the 64KiB limit")
      val idx = line.indexOf(':')
      if idx > 0 then
        val key = line.substring(0, idx).trim.toLowerCase(Locale.ROOT)
        if !headers.contains(key) then headers += key -> line.substring(idx + 1).trim
      line = readLine(input)
    (status, headers)
  end readHead

  /** 读 body：`chunked` / `content-length` / 读到 EOF 三种形态，**边写边判**上限与总时限。 */
  private def readBody(
    headers: Map[String, String],
    input: InputStream,
    tmpFile: Path,
    maxBytes: Long,
    deadline: Long
  ): (Long, String, Array[Byte]) =
    Files.createDirectories(tmpFile.getParent)
    val out = new FileOutputStream(tmpFile.toFile)
    val digest = MessageDigest.getInstance("SHA-256")
    val headBuf = new java.io.ByteArrayOutputStream()
    var total = 0L
    def append(chunk: Array[Byte], len: Int): Unit =
      if len > 0 then
        if System.currentTimeMillis() > deadline then throw Deadline(P.UrlTotalDeadlineMs)
        if total + len > maxBytes then throw TooLarge(maxBytes)
        out.write(chunk, 0, len)
        digest.update(chunk, 0, len)
        if headBuf.size() < P.HeadBytes then
          val room = P.HeadBytes - headBuf.size()
          headBuf.write(chunk, 0, math.min(room, len))
        total += len
    try
      val chunked = headers.get("transfer-encoding").exists(_.toLowerCase(Locale.ROOT).contains("chunked"))
      val declared = headers.get("content-length").flatMap(_.toLongOption).filter(_ >= 0)
      if chunked then readChunked(input, append)
      else if declared.isDefined then readFixed(input, declared.get, append)
      else readToEof(input, append)
    finally out.close()
    val hex = digest.digest().map(b => f"${b & 0xff}%02x").mkString
    (total, hex, headBuf.toByteArray)
  end readBody

  private def readChunked(input: InputStream, append: (Array[Byte], Int) => Unit): Unit =
    val buf = new Array[Byte](64 * 1024)
    var more = true
    while more do
      val sizeLine = readLine(input)
      val hex = sizeLine.split(';').headOption.getOrElse("").trim
      val size =
        try if hex.isEmpty then -1L else java.lang.Long.parseLong(hex, 16)
        catch case _: Throwable => -1L
      if size < 0 then throw BadResponse("malformed chunked body")
      if size == 0 then
        readLine(input) // trailer
        more = false
      else
        var left = size
        while left > 0 do
          val want = math.min(left, buf.length.toLong).toInt
          val n = input.read(buf, 0, want)
          if n < 0 then throw BadResponse("the origin closed the connection mid-chunk")
          append(buf, n)
          left -= n
        readLine(input) // CRLF after the chunk
    end while
  end readChunked

  private def readFixed(input: InputStream, declared: Long, append: (Array[Byte], Int) => Unit): Unit =
    val buf = new Array[Byte](64 * 1024)
    var left = declared
    while left > 0 do
      val want = math.min(left, buf.length.toLong).toInt
      val n = input.read(buf, 0, want)
      if n < 0 then throw BadResponse("the origin closed the connection before Content-Length bytes arrived")
      append(buf, n)
      left -= n

  private def readToEof(input: InputStream, append: (Array[Byte], Int) => Unit): Unit =
    val buf = new Array[Byte](64 * 1024)
    var n = input.read(buf)
    while n >= 0 do
      append(buf, n)
      n = input.read(buf)

  private def readLine(input: InputStream): String =
    val sb = new StringBuilder
    var b = input.read()
    while b >= 0 && b != 0x0a do
      if b != 0x0d then sb.append(b.toChar)
      b = input.read()
    if b < 0 && sb.isEmpty then throw BadResponse("the origin closed the connection while a chunk header was due")
    sb.toString

end ExternalFetch
