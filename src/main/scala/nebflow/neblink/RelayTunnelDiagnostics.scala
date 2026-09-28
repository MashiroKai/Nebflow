package nebflow.neblink

import java.net.http.{HttpResponse, WebSocketHandshakeException}

/**
 * Failure classification for the relay-ws upgrade (2026-09-10 隧道鉴权自愈批,
 * report `20260910_134229_device-channel-attribution.md` §3 U1 + §4 F3).
 *
 * WHY this exists: the tunnel's failure path used to log `e.getMessage`, which
 * carries NO information for the failure shape that actually happens —
 *
 *   - `java.net.http.WebSocketHandshakeException` (the JDK throws it whenever
 *     the upgrade response is not 101) does NOT override `getMessage`, so it
 *     returns `null`;
 *   - `.get(10, SECONDS)` wraps it in an `ExecutionException` whose message is
 *     just `cause.toString()` = the class NAME;
 *   - a `.get` timeout surfaces as a bare `TimeoutException` (message `null`).
 *
 * Result on 2026-09-10: 702 `Relay tunnel error: java.net.http.WebSocketHandshakeException`
 * + 22 `Relay tunnel error: null` lines and no way to tell 401/403 (our token
 * was rejected → client-side, self-healable) from 502/503 (relay/gateway →
 * server-side, cross-project). That distinction is the entry criterion of the
 * report §6 cross-project routing table, so the status code is now extracted
 * from `WebSocketHandshakeException.getResponse()`.
 *
 * The response body is included only as a truncated, credential-redacted
 * snippet — the real server answers `{"error":"Missing or invalid token"}`
 * (friends.rs require_user_or_device), which is diagnostic, while anything
 * token-shaped a proxy might echo back must never reach the log.
 */
private[neblink] object RelayTunnelDiagnostics:

  /** Classified upgrade failure. `summary` is always non-empty (never `null`),
    * safe to log verbatim. */
  final case class UpgradeFailure(statusCode: Option[Int], bodySnippet: Option[String], summary: String):
    /** 401/403 = OUR credential was rejected (self-healable, narrow gate). */
    def authRejected: Boolean = statusCode.exists(c => c == 401 || c == 403)

  /** Max characters of the response body kept in the log line. */
  val MaxBodyChars = 200

  /** Classify any throwable raised by the upgrade attempt. */
  def describe(t: Throwable): UpgradeFailure =
    handshakeOf(t) match
      case Some(hs) =>
        val resp = hs.getResponse
        val code = Option(resp).map(_.statusCode()).filter(_ > 0)
        val body = Option(resp).flatMap(bodyFragment)
        UpgradeFailure(code, body, render(code, body))
      case None =>
        val deepest = rootCause(t)
        val name = deepest.getClass.getName
        val msg = Option(deepest.getMessage).filter(_.nonEmpty)
        UpgradeFailure(None, None, s"no HTTP response ($name${msg.fold("")(m => s": $m")})")

  private def render(code: Option[Int], body: Option[String]): String =
    val head = code.fold("no HTTP status")(c => s"HTTP $c")
    body.fold(head)(b => s"$head — body: $b")

  /** First `WebSocketHandshakeException` in the cause chain (the JDK wraps it
    * in `ExecutionException` / `CompletionException`). Depth-capped and
    * cycle-safe. */
  private def handshakeOf(t: Throwable): Option[WebSocketHandshakeException] =
    var cur: Throwable = t
    var depth = 0
    while cur != null && depth < 10 do
      cur match
        case hs: WebSocketHandshakeException => return Some(hs)
        case other                           => cur = other.getCause
      depth += 1
    None

  /** Deepest cause — the most specific description of what went wrong. */
  private def rootCause(t: Throwable): Throwable =
    var cur: Throwable = t
    var depth = 0
    while cur.getCause != null && cur.getCause != cur && depth < 10 do
      cur = cur.getCause
      depth += 1
    cur

  /** Response body of the rejected upgrade. The JDK's WebSocket opening
    * handshake uses `BodyHandlers.ofString()` (`OpeningHandshake.send`), so the
    * body is already fully read and this cannot block. */
  private def bodyFragment(resp: HttpResponse[?]): Option[String] =
    val raw =
      try
        Option(resp.body()) match
          case None            => None
          case Some(s: String) => Some(s)
          case Some(other)     => Some(other.toString)
      catch case _: Exception => None
    raw.map(_.trim).filter(_.nonEmpty).map(redact).map(truncate)

  private def truncate(s: String): String =
    if s.length <= MaxBodyChars then s else s.take(MaxBodyChars) + "…(truncated)"

  /**
   * Strip credential-shaped substrings before the body reaches the log.
   * Deliberately narrow: the server's auth-rejection body
   * (`{"error":"Missing or invalid token"}`) must survive verbatim — it is the
   * evidence that identifies the rejection as auth-typed — while JWTs, bearer
   * values and token-typed JSON fields (which a proxy or a future server
   * revision could echo) are masked.
   */
  private[neblink] def redact(s: String): String =
    s
      .replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+/-]+=*", "Bearer <redacted>")
      .replaceAll("eyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}(\\.[A-Za-z0-9_-]+)?", "<redacted-jwt>")
      .replaceAll("(?i)\"(token|sessiontoken|devicetoken|refresh_token|access_token)\"\\s*:\\s*\"[^\"]*\"", "\"$1\":\"<redacted>\"")

end RelayTunnelDiagnostics
