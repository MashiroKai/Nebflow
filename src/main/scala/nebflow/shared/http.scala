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

  /** 模拟真实 Chrome 浏览器的 User-Agent，避免被反爬虫系统识别为爬虫。 */
  val UserAgent =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

  /** Chrome 版本号，与 UserAgent 保持一致，用于 Client Hints headers。 */
  private val ChromeVersion = "131"

  /**
   * 模拟真实 Chrome 导航请求的完整 headers，使请求特征与浏览器一致。
   *
   *  关键反检测 headers：
   *  - Sec-Ch-UA: Chrome Client Hints，反爬虫系统会检查是否存在
   *  - Sec-Fetch-*: Fetch Metadata，标识请求来源（导航 vs 资源加载）
   *  - Referer: 模拟从搜索引擎点击进入，增加可信度
   */
  val BrowserHeaders: Map[String, String] = Map(
    "User-Agent" -> UserAgent,
    "Accept" -> "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language" -> "en-US,en;q=0.9",
    "Referer" -> "https://www.google.com/",
    "Sec-Ch-UA" -> s""""Google Chrome";v="$ChromeVersion", "Chromium";v="$ChromeVersion", "Not_A Brand";v="24"""",
    "Sec-Ch-UA-Mobile" -> "?0",
    "Sec-Ch-UA-Platform" -> "\"macOS\"",
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
