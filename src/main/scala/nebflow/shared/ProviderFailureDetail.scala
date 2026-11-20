package nebflow.shared

/**
 * Keep actionable upstream diagnostics while removing common credential forms.
 * Redact before truncating so a long secret cannot leave a visible prefix.
 */
object ProviderFailureDetail:

  private val credentials =
    "(?i)([\\\"']?(?:authorization|proxy-authorization|api[-_]?key|access[-_]?token|refresh[-_]?token|password|secret)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|(?:Bearer\\s+)?[^\\s,;&}]+)".r
  private val bearer = "(?i)\\bBearer\\s+[^\\s,;\\\"'}]+".r
  private val apiKey = "\\bsk-[A-Za-z0-9_-]+".r
  private val urlCredentials = "(https?://)[^/@\\s]+:[^/@\\s]+@".r

  def sanitize(message: String): String =
    val fields =
      credentials.replaceAllIn(message, m => java.util.regex.Matcher.quoteReplacement(m.group(1) + "[redacted]"))
    val tokens = apiKey.replaceAllIn(bearer.replaceAllIn(fields, "Bearer [redacted]"), "[redacted]")
    urlCredentials
      .replaceAllIn(tokens, "$1[redacted]@")
      .replaceAll("\\s+", " ")
      .take(300)
end ProviderFailureDetail
