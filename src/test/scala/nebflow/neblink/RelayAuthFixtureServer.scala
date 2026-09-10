package nebflow.neblink

import io.circe.parser.parse

import java.io.{BufferedInputStream, ByteArrayOutputStream, InputStream, OutputStream}
import java.net.{InetAddress, ServerSocket, Socket, SocketException}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, CopyOnWriteArrayList}
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local wire-accurate fixture for the NebLink Server's device + relay-ws
 * authentication path (2026-09-10 隧道鉴权自愈批 harness).
 *
 * WHY a raw-socket server: the JDK's `HttpServer` cannot complete a WebSocket
 * upgrade (no 101 support), and the repo has no WS server library on the test
 * classpath. The tunnel only needs two observable outcomes from the fixture:
 * a full 101 handshake (green path) or an HTTP status rejection (red path) —
 * both are plain HTTP/1.1 responses on a socket, so ~150 lines of raw
 * protocol are enough and give byte-level control over the status code.
 *
 * Semantics reproduced (from the 2026-09-10 device-channel attribution report
 * §1/§2 + the real neblink-server):
 *   - `POST /api/device/login` | `/api/device/session` issues a session token
 *     and KICKS any previous session of the same (deviceId, networkId)
 *     — one-live-session-per-device (`store.rs kick_sessions_where`). This is
 *     the exact mechanism that stranded the relay tunnel on 2026-09-10.
 *   - `GET /api/relay-ws?deviceId=…` answers 101 only when the Bearer token is
 *     the live session; otherwise it answers an HTTP rejection with the real
 *     server's body shape `{"error":"Missing or invalid token"}`
 *     (`friends.rs require_user_or_device`). The mode knob decides whether the
 *     rejection is 403 (auth), 401 (auth) or 500 (gateway/server) — the
 *     cross-project discriminator of report §6.
 *   - `/api/users/search` mirrors the auth-protected business endpoints, so the
 *     API-level self-heal (friend batch F2) can be driven concurrently with the
 *     tunnel path (the shared-single-flight-gate scenario).
 *
 * Binds 127.0.0.1 on an ephemeral port only — never a fixed port, never 8080.
 */
final class RelayAuthFixtureServer extends AutoCloseable:

  import RelayAuthFixtureServer.*

  private val server = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
  private val closed = new java.util.concurrent.atomic.AtomicBoolean(false)

  def port: Int = server.getLocalPort
  /** Base URL the NeblinkClient / tunnel is pointed at (http:// — the tunnel
    * rewrites it to ws:// itself). */
  def url: String = s"http://127.0.0.1:$port"

  // ---- knobs ----
  /** Relay-ws upgrade behaviour (see `RelayMode`). */
  @volatile var relayMode: RelayMode = RelayMode.Auth403
  /** Artificial login latency — holds the client-side single-flight gate open
    * long enough for concurrent heal callers to join it (determinism knob for
    * the single-flight test, not a product behaviour). */
  @volatile var loginDelayMs: Long = 0L
  /** Answer login with 500 (server-side failure) instead of issuing a token. */
  @volatile var failLogins: Boolean = false

  // ---- observations ----
  /** Every issued session token, in order (includes impersonator logins). */
  val logins = new AtomicInteger(0)
  /** deviceId of every login/session call served. */
  val loginCalls = new ConcurrentLinkedQueue[String]()
  /** (statusCode, accepted) of every relay-ws upgrade attempt. */
  val relayAttempts = new ConcurrentLinkedQueue[(Int, Boolean)]()
  /** Bearer tokens seen on relay-ws upgrade attempts (masked to a prefix). */
  val relayTokens = new ConcurrentLinkedQueue[String]()
  /** (bearer token, was it a live session) of every /api/relay/&lt;id&gt;/exec call. */
  val relayExecCalls = new ConcurrentLinkedQueue[(String, Boolean)]()
  /** How many times a live session was kicked by a newer login. */
  val kickedSessions = new AtomicInteger(0)

  private val liveTokens = new ConcurrentHashMap[String, String]() // key → token
  private val tokenOwner = new ConcurrentHashMap[String, String]() // token → key
  private val openRelaySockets = new CopyOnWriteArrayList[Socket]()
  private val threads = new CopyOnWriteArrayList[Thread]()

  private def sessionKey(deviceId: String, networkId: String): String = s"$deviceId|$networkId"

  /**
   * Issue a session for (deviceId, networkId), kicking the previous one —
   * exactly the server's one-live-session semantics. Public so tests can drive
   * an "impersonator" login (UI re-login / second device) without HTTP.
   */
  def loginAs(deviceId: String, networkId: String): String =
    val key = sessionKey(deviceId, networkId)
    val prev = liveTokens.get(key)
    if prev != null then
      kickedSessions.incrementAndGet()
      liveTokens.remove(key)
      tokenOwner.remove(prev)
    val n = logins.incrementAndGet()
    val tok = s"tok-$n"
    liveTokens.put(key, tok)
    tokenOwner.put(tok, key)
    tok

  /** Kick the live session of (deviceId, networkId) by logging in again —
    * the incident's trigger (a fresh login on the same device). */
  def kickSessionOf(deviceId: String, networkId: String): String =
    loginAs(deviceId, networkId)

  /** Is this token the current live session? */
  def isLive(token: String): Boolean = token.nonEmpty && tokenOwner.containsKey(token)

  /** Current live token for a device (None when kicked/logged out). */
  def liveTokenOf(deviceId: String, networkId: String): Option[String] =
    Option(liveTokens.get(sessionKey(deviceId, networkId)))

  /** Forcibly drop every accepted relay-ws connection — makes the tunnel
    * observe "disconnected" and run its reconnect path. */
  def closeAllRelaySockets(): Unit =
    openRelaySockets.forEach { s => try s.close() catch case _: Exception => () }
    openRelaySockets.clear()

  def attemptCount(status: Int): Int = relayAttempts.stream().filter(_._1 == status).count().toInt

  // ---- lifecycle ----

  start()

  private def start(): Unit =
    val t = new Thread(() => acceptLoop(), "relay-auth-fixture-accept")
    t.setDaemon(true)
    t.start()
    threads.add(t)

  private def acceptLoop(): Unit =
    while !closed.get() do
      try
        val sock = server.accept()
        spawn(serve(sock))
      catch
        case _: SocketException => () // server socket closed
        case _: Throwable       => ()

  private def spawn(body: => Unit): Unit =
    val t = new Thread(() => body, "relay-auth-fixture-conn")
    t.setDaemon(true)
    t.start()
    threads.add(t)

  override def close(): Unit =
    closed.set(true)
    closeAllRelaySockets()
    try server.close() catch case _: Exception => ()

  // ---- protocol ----

  private def serve(sock: Socket): Unit =
    try
      sock.setSoTimeout(10_000)
      val in = new BufferedInputStream(sock.getInputStream)
      val out = sock.getOutputStream
      readRequest(in) match
        case None       => closeQuietly(sock)
        case Some(req)  => route(req, sock, out)
    catch case _: Throwable => closeQuietly(sock)

  private def route(req: Request, sock: Socket, out: OutputStream): Unit =
    val path = req.path
    if path.startsWith("/api/health") then
      respond(out, 200, """{"status":"ok"}""")
      closeQuietly(sock)
    else if path.startsWith("/api/device/login") || path.startsWith("/api/device/session") then
      val body = parse(req.body).getOrElse(io.circe.Json.Null)
      val hc = body.hcursor
      val deviceId = hc.downField("deviceId").as[String].getOrElse("unknown-device")
      val networkId = hc.downField("networkId").as[String].getOrElse("qa-net")
      loginCalls.add(deviceId)
      if loginDelayMs > 0 then Thread.sleep(loginDelayMs)
      if failLogins then
        respond(out, 500, """{"error":"session exchange failed"}""")
      else
        val tok = loginAs(deviceId, networkId)
        respond(out, 200, s"""{"token":"$tok","networkId":"$networkId","deviceId":"$deviceId","peers":[]}""")
      closeQuietly(sock)
    else if path.startsWith("/api/device/heartbeat") then
      if isLive(bearer(req)) then respond(out, 200, """{"peers":[]}""")
      else respond(out, 403, AuthRejectBody)
      closeQuietly(sock)
    else if path.startsWith("/api/device/logout") then
      val tok = bearer(req)
      if tok.nonEmpty then
        Option(tokenOwner.remove(tok)).foreach(k => liveTokens.remove(k))
      respond(out, 200, """{"ok":true}""")
      closeQuietly(sock)
    else if path.startsWith("/api/device/relay-ws") then
      handleRelayUpgrade(req, sock, out)
    else if path.startsWith("/api/relay/") && path.endsWith("/exec") then
      // Cross-device dispatch (RemoteExecutor → NeblinkClient.relayExec):
      // which client's session token carries the call is the observable that
      // proves the hot-swap convergence.
      val tok = bearer(req)
      relayExecCalls.add((tok, isLive(tok)))
      if isLive(tok) then respond(out, 200, """{"output":"remote-ok","error":""}""")
      else respond(out, 403, AuthRejectBody)
      closeQuietly(sock)
    else if path.startsWith("/api/users/search") || path.startsWith("/api/friends") ||
      path.startsWith("/api/conversations")
    then
      if isLive(bearer(req)) then
        respond(out, 200, if path.startsWith("/api/users/search") then """{"found":false}""" else """{"friends":[],"incoming":[],"outgoing":[]}""")
      else respond(out, 403, AuthRejectBody)
      closeQuietly(sock)
    else
      respond(out, 404, """{"error":"not found"}""")
      closeQuietly(sock)

  private def handleRelayUpgrade(req: Request, sock: Socket, out: OutputStream): Unit =
    val tok = bearer(req)
    relayTokens.add(if tok.length > 8 then tok.take(8) + "…" else tok)
    val live = isLive(tok)
    relayMode match
      case RelayMode.Auth403 =>
        if live then completeUpgrade(req, sock, out)
        else
          relayAttempts.add((403, false))
          respond(out, 403, AuthRejectBody)
          closeQuietly(sock)
      case RelayMode.Auth401 =>
        if live then completeUpgrade(req, sock, out)
        else
          relayAttempts.add((401, false))
          respond(out, 401, AuthRejectBody)
          closeQuietly(sock)
      case RelayMode.Server500 =>
        relayAttempts.add((500, false))
        respond(out, 500, """{"error":"relay upstream unavailable"}""")
        closeQuietly(sock)
      case RelayMode.AcceptIfLive =>
        if live then completeUpgrade(req, sock, out)
        else
          relayAttempts.add((403, false))
          respond(out, 403, AuthRejectBody)
          closeQuietly(sock)

  /** Finish a real RFC 6455 upgrade: the JDK client validates Upgrade /
    * Connection / Sec-WebSocket-Accept byte-for-byte (OpeningHandshake),
    * so the accept header is computed from the client nonce. */
  private def completeUpgrade(req: Request, sock: Socket, out: OutputStream): Unit =
    relayAttempts.add((101, true))
    val nonce = req.headers.getOrElse("sec-websocket-key", "")
    val accept = Base64.getEncoder.encodeToString(
      MessageDigest
        .getInstance("SHA-1")
        .digest((nonce + WsGuid).getBytes(StandardCharsets.ISO_8859_1))
    )
    val resp =
      "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
        s"Sec-WebSocket-Accept: $accept\r\n\r\n"
    out.write(resp.getBytes(StandardCharsets.ISO_8859_1))
    out.flush()
    openRelaySockets.add(sock)
    // Drain frames (ping/pong/close) so the client's writes never block. The
    // tunnel's own heartbeat would otherwise stall against a full socket buffer.
    spawn {
      try
        val buf = new Array[Byte](4096)
        val is = sock.getInputStream
        var n = is.read(buf)
        while n >= 0 do n = is.read(buf)
      catch case _: Throwable => ()
      finally
        openRelaySockets.remove(sock)
        closeQuietly(sock)
    }

  private def bearer(req: Request): String =
    req.headers
      .get("authorization")
      .map(_.stripPrefix("Bearer").stripPrefix("bearer").trim)
      .getOrElse("")

  private def respond(out: OutputStream, status: Int, body: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    val head =
      s"HTTP/1.1 $status ${statusText(status)}\r\n" +
        "Content-Type: application/json\r\n" +
        s"Content-Length: ${bytes.length}\r\n" +
        "Connection: close\r\n\r\n"
    try
      out.write(head.getBytes(StandardCharsets.ISO_8859_1))
      out.write(bytes)
      out.flush()
    catch case _: Exception => ()

  private def statusText(code: Int): String = code match
    case 101 => "Switching Protocols"
    case 200 => "OK"
    case 401 => "Unauthorized"
    case 403 => "Forbidden"
    case 404 => "Not Found"
    case 500 => "Internal Server Error"
    case _   => "Status"

  private def readRequest(in: InputStream): Option[Request] =
    val headBytes = new ByteArrayOutputStream()
    var done = false
    var b = in.read()
    while !done && b >= 0 do
      headBytes.write(b)
      val a = headBytes.toByteArray
      if a.length >= 4 && a(a.length - 4) == 13 && a(a.length - 3) == 10 && a(a.length - 2) == 13 && a(a.length - 1) == 10
      then done = true
      else b = in.read()
    if headBytes.size() == 0 then None
    else
      val head = new String(headBytes.toByteArray, StandardCharsets.ISO_8859_1)
      val lines = head.split("\r\n").toList
      val parts = lines.head.split(" ")
      val headers = lines.tail.flatMap { l =>
        val i = l.indexOf(':')
        if i > 0 then Some(l.substring(0, i).trim.toLowerCase -> l.substring(i + 1).trim) else None
      }.toMap
      val len = headers.get("content-length").flatMap(_.toIntOption).getOrElse(0)
      val body = if len > 0 then new String(in.readNBytes(len), StandardCharsets.UTF_8) else ""
      Some(Request(parts.lift(0).getOrElse("GET"), parts.lift(1).getOrElse("/"), headers, body))

  private def closeQuietly(sock: Socket): Unit =
    try sock.close() catch case _: Exception => ()

object RelayAuthFixtureServer:
  private val WsGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
  private[neblink] val AuthRejectBody = """{"error":"Missing or invalid token"}"""

  /** Relay-ws upgrade behaviour of the fixture. */
  enum RelayMode:
    /** 101 only while the Bearer token is the live session, else 403. */
    case AcceptIfLive

    /** Always 403 (auth rejection) — the incident's shape. */
    case Auth403

    /** Always 401 (auth rejection, other spelling). */
    case Auth401

    /** Always 500 (server/gateway failure) — must NEVER trigger a re-login. */
    case Server500

  private final case class Request(
    method: String,
    path: String,
    headers: Map[String, String],
    body: String
  )
end RelayAuthFixtureServer
