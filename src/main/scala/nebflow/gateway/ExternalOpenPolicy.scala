package nebflow.gateway

import java.io.ByteArrayInputStream
import java.net.{Inet4Address, Inet6Address, InetAddress, URI}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

import scala.jdk.CollectionConverters.*

/**
 * 「把外部文件拉进 canvas 打开」的**纯判据面**（external-open 批，2026-11-19）。
 *
 * 本对象只做没有副作用的判定：URL 闸（scheme / host / **解析后的地址**）、字节嗅探
 * （按内容不按扩展名）、落盘命名与路径、TTL 清账清单。网络与磁盘动作全部在
 * [[ExternalFetch]] / [[ExternalOpenRoutes]]，因此本表可在 spec 里逐格断言。
 *
 * ## 现状定性（本批前置勘察，代码行号见报告）
 *
 * - 本机路径：**早就能开**。`pop.readFile`（WsFileOpsHandlers.scala:633）打开腿
 *   零域门（绝对路径 + 存在 + 普通文件 + 大小闸），前端 `openWorkspaceItem`
 *   （canvas.js:1722）对任意 absPath 有效。缺的只是**入口**：控制台没有「粘贴一个
 *   项目外绝对路径就打开」的 UI。
 * - 外部 URL：**全无通路**（仓内唯一相近的 `PresenceRoutes.checkHttpBaseUrl`
 *   :2960 只判 scheme/host 形态，不判地址，不落盘）。
 * - 凭据红线：nf-file 的判据阶梯（`NfFilePolicy.nfVerdictForRealLayer` :621）是
 *   身份两层 + R2 inode 的**单一权威**，本入口直接复用同一函数，零第二把尺。
 *
 * ## 红线（本批不可放宽，逐条）
 *
 * 1. **SSRF**：只允许 `http`/`https`；host 名在 [[BlockedHostNames]] / `.localhost`
 *    / `.local` / `.internal` 之下直接拒；**解析后**任一地址落在环回 / 私网 /
 *    CGNAT / 链路本地（含 `169.254.169.254` 元数据）/ 组播 / 保留段 ⇒ 整条拒
 *    （fail-closed：解析出多个地址时**有一个坏的就拒**，防轮询绕过）。3xx 不自动
 *    跟随，每一跳重判同一闸（[[ExternalFetch]] 的手工重定向循环）。
 * 2. **大小 / 时间**：[[MaxUrlBytes]] 与 [[UrlTotalDeadlineMs]]，理由见常量注释。
 * 3. **类型按内容嗅探**（[[sniffExtension]]），扩展名只作文本族的兜底提示。
 * 4. **落盘**：`<dataRoot>/external-fetch/<sha256 前 16>/<安全名>` + [[FetchTtlMillis]]
 *    清账（[[expiredDirs]]）。
 * 5. **凭据**：本文件不碰凭据判据——由路由复用 `NfFilePolicy` 的身份 + inode 两层；
 *    判据拒的路径连 `stat` 之外的字节都不读，也绝不进日志（路由里脱敏）。
 */
object ExternalOpenPolicy:

  // ────────────────────────────────────────────────────────────────────────
  // 预算（数值即决定，理由随常量，改动请连同理由一起改）
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 落盘根目录名（`<dataRoot>/external-fetch`）。HTML viewer 的严格 sandbox 也按
   * 这个路径段识别「远程拉来的主动内容」（viewers/html.js），改名要两处同改。
   */
  val StorageDirName: String = "external-fetch"

  /**
   * 单个 URL 拉取的字节上限 = 32 MiB。
   *
   * 理由：①这篇面服务的是「看一份文档 / 一张图」，不是搬运素材——32 MiB 覆盖
   * 典型 PDF（10–20 MiB）、原图（≤30 MiB）与全部文本类；②它同时是**单次拉取的
   * 内存/磁盘上限**：一次拉取最多占 32 MiB 临时盘 + 64 KiB 内存头缓冲，网关
   * （与生产实例共用进程模型）不会被一次远程拉取拖成 OOM；③超过它的正当需求
   * （大视频）走既有「Pop 卡片 + 本机路径」面，不该由远程 URL 承担。
   * 超限 = 413 `too-large`，**边下边判**，不会先收完再拒。
   */
  val MaxUrlBytes: Long = 32L * 1024 * 1024

  /**
   * 连接超时（毫秒）。8s：公网 TLS 首包在慢链路上也够用，又不会让一个黑洞 IP
   * 占住路由线程到用户以为页面卡死。
   */
  val UrlConnectTimeoutMs: Int = 8000

  /** 单次 read 的 socket 超时（毫秒）。20s：允许慢速源，stall 即断。 */
  val UrlReadTimeoutMs: Int = 20000

  /**
   * 整条拉取（含全部跳）的总时限 = 60s。理由：socket 超时只管「单次读不前进」，
   * 一个每秒吐 1 字节的源可以永远不触发它；总时限是这类 slow-loris 形态的上界。
   */
  val UrlTotalDeadlineMs: Long = 60000

  /**
   * 最多跟随的重定向数。3：http→https、规范化尾斜杠、CDN 一跳，够用且让
   * 「跳板到私网」的链条长度受控（每一跳都重判闸）。
   */
  val MaxRedirects: Int = 3

  /**
   * 落盘存活期 = 24h。理由：拉一次是为了「看一眼」，24h 足够跨一次会话/刷新复用，
   * 又不至于让「随手拉过的所有东西」永久堆在数据根里（清账见 [[expiredDirs]]）。
   */
  val FetchTtlMillis: Long = 24L * 60 * 60 * 1000

  /** 嗅探/响应头读取的缓冲上限。 */
  val HeadBytes: Int = 64 * 1024
  val MaxHeaderBytes: Int = 64 * 1024

  /** 落盘文件名长度上限（保留扩展名）。 */
  val MaxFileNameLength: Int = 80

  /** 判据拒的统一出向（route 层映射成 HTTP 状态 + `X-Nf-Reason` 头）。 */
  final case class Refusal(status: Int, reason: String, message: String)

  // ────────────────────────────────────────────────────────────────────────
  // ① URL 闸
  // ────────────────────────────────────────────────────────────────────────

  /** 名字本身就是「本机/元数据」的 host（DNS 前置拦截；解析后再判一次地址）。 */
  val BlockedHostNames: Set[String] = Set(
    "localhost",
    "localhost.localdomain",
    "ip6-localhost",
    "ip6-loopback",
    "metadata.google.internal",
    "metadata.goog"
  )

  /**
   * 保留/特殊用途后缀：`.localhost`（RFC 6761）、`.local`（mDNS 广播域）、
   * `.internal`（私有内部域名惯例）。全族拒绝，防「名字看着公网、解析落在内网」。
   */
  val BlockedHostSuffixes: List[String] = List(".localhost", ".local", ".internal", ".home.arpa")

  def normalizeHost(raw: String): String =
    Option(raw).getOrElse("").trim.toLowerCase(Locale.ROOT).stripSuffix(".")

  def hostNameBlocked(host: String): Boolean =
    val h = normalizeHost(host)
    h.isEmpty || BlockedHostNames.contains(h) || BlockedHostSuffixes.exists(h.endsWith)

  /**
   * 地址判据：环回 / 私网 / CGNAT / 链路本地 / 组播 / 保留段一律 `true`。
   *
   * 覆盖（IPv4）：`0/8`、`10/8`、`100.64/10`、`127/8`、`169.254/16`、`172.16/12`、
   * `192.0.0/24`、`192.0.2/24`、`192.168/16`、`198.18/15`、`198.51.100/24`、
   * `203.0.113/24`、`224/4`（组播）、`240/4`（保留）、`255.255.255.255`。
   * 覆盖（IPv6）：`::`、`::1`、`fc00::/7`（ULA）、`fe80::/10`（链路本地）、
   * `ff00::/8`（组播）、`2002::/16`（6to4，内嵌 v4 不可信）、`64:ff9b::/96`
   * （NAT64，内嵌 v4 判）、以及 `::ffff:a.b.c.d` 映射形态（取内嵌 v4 复判）。
   */
  def addressBlocked(addr: InetAddress): Boolean =
    if addr == null then true
    else if addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
      addr.isMulticastAddress || addr.isSiteLocalAddress
    then true
    else
      addr match
        case v4: Inet4Address =>
          val b = v4.getAddress
          val b0 = b(0) & 0xff
          val b1 = b(1) & 0xff
          val b2 = b(2) & 0xff
          b0 == 0 ||
          b0 == 10 ||
          (b0 == 100 && (b1 & 0xc0) == 64) ||
          b0 == 127 ||
          (b0 == 169 && b1 == 254) ||
          (b0 == 172 && (b1 & 0xf0) == 16) ||
          (b0 == 192 && b1 == 0 && (b2 == 0 || b2 == 2)) ||
          (b0 == 192 && b1 == 88 && b2 == 99) ||
          (b0 == 192 && b1 == 168) ||
          (b0 == 198 && (b1 == 18 || b1 == 19)) ||
          (b0 == 198 && b1 == 51 && b2 == 100) ||
          (b0 == 203 && b1 == 0 && b2 == 113) ||
          (b0 & 0xf0) == 224 ||
          (b0 & 0xf0) == 240 ||
          b0 == 255
        case v6: Inet6Address =>
          val b = v6.getAddress
          val b0 = b(0) & 0xff
          val b1 = b(1) & 0xff
          val embedded =
            if isV4Mapped(b) then Some(b.slice(12, 16))
            else if isNat64(b) then Some(b.slice(12, 16))
            else None
          embedded match
            case Some(v4Bytes) =>
              addressBlocked(InetAddress.getByAddress(v4Bytes))
            case None =>
              (b0 & 0xfe) == 0xfc || // fc00::/7 ULA
              (b0 == 0xfe && (b1 & 0xc0) == 0x80) || // fe80::/10
              b0 == 0xff || // ff00::/8
              (b0 == 0x20 && b1 == 0x02) || // 2002::/16 6to4
              isIpv4Compatible(b) || // ::/96 其余（::1 已由 isLoopbackAddress 拦）
              b0 == 0x20 && b1 == 0x01 && b(2) == 0x0d && (b(3) & 0xff) == 0xb8 // 2001:db8::/32 文档段
        case _ => true

  private def isV4Mapped(b: Array[Byte]): Boolean =
    (0 until 10).forall(i => b(i) == 0) && (b(10) & 0xff) == 0xff && (b(11) & 0xff) == 0xff

  private def isNat64(b: Array[Byte]): Boolean =
    val prefix = Array[Int](0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0)
    (0 until 12).forall(i => (b(i) & 0xff) == prefix(i))

  private def isIpv4Compatible(b: Array[Byte]): Boolean =
    (0 until 12).forall(i => b(i) == 0)

  /** 默认解析器（生产）。测试注入自己的解析表。 */
  def resolveHost(host: String): List[InetAddress] =
    try InetAddress.getAllByName(normalizeHost(host)).toList
    catch case _: Throwable => Nil

  /** 解析后判据：**任一**地址命中即整条拒（fail-closed）。 */
  def judgeAddresses(host: String, addrs: List[InetAddress]): Either[Refusal, List[InetAddress]] =
    if addrs.isEmpty then Left(Refusal(502, "url-unresolved", s"'$host' did not resolve to any address"))
    else
      addrs.find(addressBlocked) match
        case Some(bad) =>
          Left(
            Refusal(
              403,
              "url-private-address",
              s"'$host' resolves to ${bad.getHostAddress} — private, loopback, link-local and " +
                "metadata addresses are refused"
            )
          )
        case None => Right(addrs)

  /** 一发完整闸：scheme → 拒绝的用户信息 → host 名 → 解析 → 地址。 */
  def gateUrl(
    raw: String,
    resolve: String => List[InetAddress] = resolveHost
  ): Either[Refusal, (URI, String, List[InetAddress])] =
    val text = Option(raw).getOrElse("").trim
    val uriTry =
      try Right(new URI(text))
      catch case _: Throwable => Left(Refusal(400, "url-malformed", "the value is not a valid URL"))
    uriTry.flatMap { uri =>
      if text.isEmpty then Left(Refusal(400, "url-malformed", "empty URL"))
      else
        val scheme = Option(uri.getScheme).map(_.toLowerCase(Locale.ROOT)).getOrElse("")
        if scheme != "http" && scheme != "https" then
          Left(
            Refusal(
              400,
              "url-scheme",
              s"only http/https URLs may be fetched (got '${if scheme.isEmpty then "none" else scheme}')"
            )
          )
        else
          val host = normalizeHost(Option(uri.getHost).getOrElse(""))
          if host.isEmpty then Left(Refusal(400, "url-host", "the URL has no host"))
          else if hostNameBlocked(host) then
            Left(Refusal(403, "url-private-address", s"'$host' is a local / metadata host name"))
          else judgeAddresses(host, resolve(host)).map { addrs => (uri, host, addrs) }
    }
  end gateUrl

  // ────────────────────────────────────────────────────────────────────────
  // ② 内容嗅探（按内容，不按扩展名）
  // ────────────────────────────────────────────────────────────────────────

  /**
   * 主动内容（会被浏览器执行的形态）：远程拉来的 HTML 一律不得进 console 同源
   * iframe——viewers/html.js 对 `external-fetch/` 路径下的文档改用无
   * `allow-same-origin` 的 sandbox（脚本可见、事件可用，但拿不到 console 的
   * DOM / localStorage / 同源 fetch 凭据）。
   */
  val ActiveExtensions: Set[String] = Set("html", "htm", "xhtml", "shtml", "js", "mjs", "css", "xml")

  /**
   * 文本族扩展名白名单：只有当 URL 自己的扩展名在此表内、且内容确实是文本时，
   * 才按 URL 扩展名服务；否则一律 `txt`（禁「内容与扩展名不一致照样按扩展名渲染」）。
   */
  val TextExtensions: Set[String] = Set(
    "txt",
    "md",
    "markdown",
    "json",
    "yaml",
    "yml",
    "csv",
    "tsv",
    "log",
    "toml",
    "ini",
    "html",
    "htm",
    "xhtml",
    "shtml",
    "xml",
    "js",
    "mjs",
    "css",
    "scss",
    "less",
    "sh",
    "zsh",
    "bash",
    "py",
    "rb",
    "go",
    "rs",
    "java",
    "kt",
    "c",
    "h",
    "cpp",
    "ts",
    "tsx",
    "jsx",
    "sql",
    "diff",
    "patch"
  )

  private def startsWith(head: Array[Byte], magic: Int*): Boolean =
    head.length >= magic.length && magic.indices.forall(i => (head(i) & 0xff) == magic(i))

  private def asciiAt(head: Array[Byte], offset: Int, text: String): Boolean =
    val bytes = text.getBytes(StandardCharsets.US_ASCII)
    head.length >= offset + bytes.length && bytes.indices.forall(i => head(offset + i) == bytes(i))

  /**
   * ZIP 容器的**内部**判定（OOXML / EPUB）：读头 64 KiB 内的第一/第二个条目名。
   * 读不出结论时回落 `zip`（→ 下载兜底，绝不猜成 docx）。
   */
  private def sniffZip(head: Array[Byte]): String =
    try
      val zin = new ZipInputStream(new ByteArrayInputStream(head))
      var entry = zin.getNextEntry
      var found = ""
      var guard = 0
      while entry != null && found.isEmpty && guard < 8 do
        val name = Option(entry.getName).getOrElse("").toLowerCase(Locale.ROOT)
        if name.startsWith("word/") || name == "[content_types].xml" && headContains(head, "wordprocessingml") then
          found = "docx"
        else if name.startsWith("xl/") then found = "xlsx"
        else if name.startsWith("ppt/") then found = "pptx"
        else if name == "mimetype" then
          // EPUB 的 mimetype 条目是第一个条目且不压缩 —— 名字够了，内容读一点。
          found = "epub"
        entry = zin.getNextEntry
        guard += 1
      if found.isEmpty && headContains(head, "wordprocessingml") then "docx"
      else if found.isEmpty then "zip"
      else found
    catch case _: Throwable => "zip"

  private def headContains(head: Array[Byte], needle: String): Boolean =
    val n = needle.getBytes(StandardCharsets.US_ASCII)
    if n.isEmpty || head.length < n.length then false
    else
      var i = 0
      var hit = false
      while !hit && i <= head.length - n.length do
        var j = 0
        while j < n.length && head(i + j) == n(j) do j += 1
        if j == n.length then hit = true
        i += 1
      hit

  /** 内容嗅探 → 扩展名（`None` = 既非已知二进制魔数、也不像文本）。 */
  def sniffExtension(head: Array[Byte]): Option[String] =
    if head == null || head.isEmpty then None
    else if startsWith(head, 0x89, 0x50, 0x4e, 0x47) then Some("png")
    else if startsWith(head, 0xff, 0xd8, 0xff) then Some("jpg")
    else if startsWith(head, 0x47, 0x49, 0x46, 0x38) then Some("gif")
    else if startsWith(head, 0x52, 0x49, 0x46, 0x46) && asciiAt(head, 8, "WEBP") then Some("webp")
    else if startsWith(head, 0x25, 0x50, 0x44, 0x46) then Some("pdf")
    else if startsWith(head, 0x50, 0x4b, 0x03, 0x04) || startsWith(head, 0x50, 0x4b, 0x05, 0x06) then
      Some(sniffZip(head))
    else if asciiAt(head, 4, "ftyp") then Some("mp4")
    else if startsWith(head, 0x1a, 0x45, 0xdf, 0xa3) then Some("webm")
    else if startsWith(head, 0x4f, 0x67, 0x67, 0x53) then Some("ogg")
    else if startsWith(head, 0x66, 0x4c, 0x61, 0x43) then Some("flac")
    else if startsWith(head, 0x49, 0x44, 0x33) then Some("mp3")
    else if startsWith(head, 0xff, 0xfb) || startsWith(head, 0xff, 0xf3) then Some("mp3")
    else if startsWith(head, 0x52, 0x49, 0x46, 0x46) && asciiAt(head, 8, "WAVE") then Some("wav")
    else if startsWith(head, 0x42, 0x4d) then Some("bmp")
    else if startsWith(head, 0x00, 0x00, 0x01, 0x00) then Some("ico")
    else if startsWith(head, 0x3c, 0x3f, 0x78, 0x6d) then Some("xml") // <?xm
    else if looksLikeText(head) then Some("txt")
    else None

  /** 文本判据：无 NUL 字节 + UTF-8 严格解码不产生替换字符。 */
  def looksLikeText(head: Array[Byte]): Boolean =
    if head == null || head.isEmpty then false
    else if head.exists(b => b == 0) then false
    else !new String(head, StandardCharsets.UTF_8).contains('\uFFFD')

  /** URL 路径里的扩展名（小写、字母数字，长度 ≤ 8；否则空串）。 */
  def urlExtension(urlPath: String): String =
    val name = Option(urlPath).getOrElse("").split('/').lastOption.getOrElse("")
    val dot = name.lastIndexOf('.')
    if dot <= 0 || dot == name.length - 1 then ""
    else
      val ext = name.substring(dot + 1).toLowerCase(Locale.ROOT)
      if ext.matches("[a-z0-9]{1,8}") then ext else ""

  /**
   * 服务扩展名的**最终裁决**：内容优先。
   *   1. 魔数命中 ⇒ 用魔数（扩展名只用来定文件名）；
   *   2. 内容是文本且 URL 扩展名在 [[TextExtensions]] 内 ⇒ 用 URL 扩展名；
   *   3. 其余 ⇒ `txt`（文本）/ `bin`（二进制未知）。
   */
  def servedExtension(head: Array[Byte], urlPath: String): String =
    sniffExtension(head) match
      case Some(ext) if ext != "txt" => ext
      case Some(_) =>
        val fromUrl = urlExtension(urlPath)
        if TextExtensions.contains(fromUrl) then fromUrl else "txt"
      case None => "bin"

  /** 落盘文件名：URL basename 净化 + 强制成服务扩展名 + 长度上限。 */
  def fileNameFor(urlPath: String, ext: String): String =
    val rawName = Option(urlPath).getOrElse("").split('/').lastOption.getOrElse("")
    val decoded =
      try java.net.URLDecoder.decode(rawName, StandardCharsets.UTF_8)
      catch case _: Throwable => rawName
    val base0 =
      val dot = decoded.lastIndexOf('.')
      val stemRaw = if dot > 0 then decoded.substring(0, dot) else decoded
      val cleaned =
        stemRaw.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-{2,}", "-").stripPrefix("-").stripSuffix("-")
      if cleaned.isEmpty then "download" else cleaned
    val suffix = if ext.isEmpty then "" else "." + ext
    val room = math.max(1, MaxFileNameLength - suffix.length)
    val base = if base0.length > room then base0.take(room) else base0
    base + suffix
  end fileNameFor

  // ────────────────────────────────────────────────────────────────────────
  // ③ 落盘路径 + 清账
  // ────────────────────────────────────────────────────────────────────────

  def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  /** sha256 前 16 位 = 内容寻址目录（同内容重复拉取天然幂等，不堆副本）。 */
  def storageDir(dataRoot: Path, sha256Hex: String): Path =
    dataRoot.resolve(StorageDirName).resolve(sha256Hex.take(16))

  def storagePath(dataRoot: Path, sha256Hex: String, fileName: String): Path =
    storageDir(dataRoot, sha256Hex).resolve(fileName)

  /**
   * TTL 清账清单：`external-fetch/` 下一次一级目录里 mtime 早于 `now - ttl` 的。
   * 只列不删——删除是 route 的副作用面（可测之处在清单）。
   */
  def expiredDirs(root: Path, now: Long, ttlMillis: Long = FetchTtlMillis): List[Path] =
    if !Files.isDirectory(root) then Nil
    else
      val stream = Files.list(root)
      try
        stream
          .iterator()
          .asScala
          .filter(p => Files.isDirectory(p))
          .filter { p =>
            val mtime =
              try Files.getLastModifiedTime(p).toMillis
              catch case _: Throwable => now
            mtime < now - ttlMillis
          }
          .toList
      catch case _: Throwable => Nil
      finally
        try stream.close()
        catch case _: Throwable => ()
      end try

end ExternalOpenPolicy
