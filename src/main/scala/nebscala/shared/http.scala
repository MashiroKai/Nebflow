package nebscala.shared

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
