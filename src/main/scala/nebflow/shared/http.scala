package nebflow.shared

import sttp.client4.SyncBackend
import sttp.client4.httpclient.HttpClientSyncBackend

import java.net.http.HttpClient
import java.time.Duration as JDuration

/**
 * 全局共享的同步 HTTP Backend，所有工具复用同一个实例以利用连接池。
 *
 *  配置要点：
 *  - 连接超时 10s（避免无响应连接挂死）
 *  - Java 层启用 NORMAL 重定向（GET/HEAD 的 301/302/303/307/308）
 *  - sttp 层额外启用 followRedirects 以处理所有情况
 */
object SharedBackend:

  private val httpClient: HttpClient = HttpClient
    .newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(JDuration.ofSeconds(10))
    .build()

  val instance: SyncBackend = HttpClientSyncBackend.usingClient(httpClient)

  /** 统一的 User-Agent，所有 Nebflow 网络请求使用此标识。 */
  val UserAgent = s"Mozilla/5.0 (compatible; Nebflow/${nebflow.Version.string})"

  /** 模拟 Chrome 导航请求的关键 headers，降低被反爬虫拦截的概率。 */
  val BrowserHeaders: Map[String, String] = Map(
    "User-Agent" -> UserAgent,
    "Accept" -> "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language" -> "en-US,en;q=0.9",
    "Referer" -> "https://www.google.com/",
    "Sec-Fetch-Dest" -> "document",
    "Sec-Fetch-Mode" -> "navigate",
    "Sec-Fetch-Site" -> "cross-site",
    "Sec-Fetch-User" -> "?1",
    "Upgrade-Insecure-Requests" -> "1"
  )

end SharedBackend

/** HTTP 工具函数 */
object HttpUtils:

  private val BinaryTypes = Set(
    "application/octet-stream",
    "application/pdf",
    "application/zip",
    "application/gzip",
    "image/png",
    "image/jpeg",
    "image/gif",
    "image/webp",
    "image/svg+xml",
    "video/mp4",
    "video/webm",
    "audio/mpeg",
    "audio/ogg"
  )

  def isBinaryContentType(contentType: String): Boolean =
    BinaryTypes.contains(contentType.split(";").head.trim.toLowerCase)

  /** 从 HTML 中提取纯文本，移除 script/style/nav 等标签 */
  def stripHtmlTags(html: String): String =
    html
      .replaceAll("""(?i)<script[^>]*>[\\s\\S]*?</script>""", "")
      .replaceAll("""(?i)<style[^>]*>[\\s\\S]*?</style>""", "")
      .replaceAll("""(?i)<nav[\\s\\S]*?</nav>""", "")
      .replaceAll("""(?i)<footer[\\s\\S]*?</footer>""", "")
      .replaceAll("""(?i)<header[\\s\\S]*?</header>""", "")
      .replaceAll("""(?i)<aside[\\s\\S]*?</aside>""", "")
      .replaceAll("""(?i)<!--[\\s\\S]*?-->""", "")
      .replaceAll("""<[^>]+>""", " ")
      .replace("&amp;", "&")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&#39;", "'")
      .replace("&nbsp;", " ")
      .replaceAll("\\s+", " ")
      .trim
end HttpUtils
