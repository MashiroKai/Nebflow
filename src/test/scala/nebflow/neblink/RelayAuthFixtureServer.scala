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
  /** 波3（①opt-A1 通道自愈）健康路径开关：true ⇒ 解析客户端 WS 文本帧并对
    * `{"type":"ping"}` 回 `{"type":"pong"}`（= 真服务端 relay.rs 的
    * `ClientToServer::Ping` 分支）。**默认 false**：升级完成后只吞帧不回话，
    * 这就是 2026-09-11 事故的僵尸形态（`lastPong` 永不刷新 ⇒ 30s 存活窗到点
    * 判僵尸 ⇒ abort ⇒ 闩永不完成）。两个方向都要能测：
    * false = 僵尸/自愈路径，true = 健康路径不误杀（无 flap）。 */
  @volatile var pongReplies: Boolean = false
  /** How many client ping frames were answered with a pong (health knob readout). */
  val pongsSent = new AtomicInteger(0)

  /** **线级回执读数**（回执诚实性批 F3/F5，2026-09-18）：把客户端→服务端的**文本
    * 帧原文**记下来，供 spec 断言「`ack-sent` 必须对应线上真有帧」——走的是真
    * RFC 6455 帧编解码（JDK 客户端掩码 + 本夹具解掩码），比任何 mock 回调硬。
    *
    * 默认 **false** ⇒ 既有 spec 的排空路径（只 read 不解析）**逐字不动**、读数面
    * 零变化；本批新增 `DeviceMailAckHonestySpec` 才打开它。
    * 打开时排空循环改为按帧解析（与 `pongReplies=true` 同一条解析路），文本帧原文
    * 落进 [[clientTextFrames]]（按到达顺序）。 */
  @volatile var recordClientFrames: Boolean = false

  /** 收到的客户端文本帧原文（顺序 = 到达顺序；见 [[recordClientFrames]]）。 */
  val clientTextFrames = new ConcurrentLinkedQueue[String]()

  /** 已收到的客户端文本帧快照。 */
  def clientFrames: List[String] =
    val out = scala.collection.mutable.ListBuffer.empty[String]
    clientTextFrames.forEach(f => out += f)
    out.toList

  /** 线上 ack 帧数（判别锚 = 帧原文同时含 `"ack"` 与目标 eventId）。 */
  def ackFrameCount(eventId: String): Int =
    var n = 0
    clientTextFrames.forEach(f => if f.contains("\"ack\"") && f.contains(eventId) then n += 1)
    n

  /** 踢旧批 r2（2026-09-14）设备注册腿：`POST /api/device/register` 的服务次数。
    * 判据面 = 「被踢后自动重注册腿是否还在跑」（复核位判 fail 的那条腿）。 */
  val registers = new AtomicInteger(0)
  /** deviceId of every register call served. */
  val registerCalls = new ConcurrentLinkedQueue[String]()
  /** HTTP `POST /api/device/register` count (the判据面 for the re-registration leg). */
  def registerCount: Int = registers.get()
  /** `POST /oidc/token` 次数（silent re-login 的 refresh 腿）。 */
  val tokenCalls = new AtomicInteger(0)
  /** `POST /api/device/session` 因凭据失效被拒的次数（kick 后的常态）。 */
  val sessionRejections = new AtomicInteger(0)
  /** register 响应里回的 networkId（真服务端由 token 的 network 决定；夹具给一个
    * 可设值，让 register 与 login 落在同一个 (deviceId, networkId) 维度上）。 */
  @volatile var enrollNetworkId: String = "qa-net"

  /** `POST /api/device/session` **凭据无效**时的应答（kaiauth 修法批 ①，2026-09-16）。
    *
    * 默认 = 修前既有形态（401 + `{"error":"Missing or invalid token"}`）—— 既有 spec 的
    * 读数面**逐字不变**。置 403 + `{"error":"Invalid device credential"}` 就是真服务端
    * `device_session` 的**凭据族**形态（跨仓只读 `neblink-server/src/routes.rs:1366-1379`：
    * `check_device_credential` 非 Ok ⇒ `forbidden("Invalid device credential")`；
    * `NoRow` 与 `Mismatch` 压成同一个 403 字面）。两个旋钮分离，是因为本批的判据正是
    * 「**哪条腿 + 哪个状态码**」：状态码变了、腿没变 ⇒ 必须进入重登腿（N1）。 */
  @volatile var sessionRejectStatus: Int = 401
  @volatile var sessionRejectBody: String = AuthRejectBody

  /** **凭据腿**（`/api/device/session`）被拒的次数 —— 与 `sessionRejections` 同源
    * （本旋钮不改变计数语义，分离出来只为在断言里读得直白）。 */
  def sessionRejectionCount: Int = sessionRejections.get()

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
  /** **请求体** of every `/api/relay/<id>/exec` call —— 批 2（2026-09-16 · C06）新增的
    * 判别面：`(token, accepted)` 只能证「2 次下发都带活 token」，**分不开**这 2 次里
    * 哪次是只读画像探针、哪次是业务 ⇒ 补请求体级锚（见下两个读数）。 */
  val relayExecBodies = new ConcurrentLinkedQueue[String]()

  /** 只读画像探针（`kind=probe`）的命中数 —— 与 `StubPeerServer.probeHitCount`
    * （`RemoteExecutorEndpointCandidateSpec.scala:73-80`）**同款口径、同款判别锚**：
    * 请求体含字面 `xdev read-only profile probe`（生产侧 `RemoteExecutor.scala:303-308`，
    * 随 `NeblinkClient.relayExec` 的 `{"action":…,"params":…,"projectRoot":…}` 信封下发）。
    * 按**请求体**判定 ⇒ 与下发先后 / 重试 / 候选轮转无关。 */
  def relayExecProbeCount: Int =
    relayExecBodies.stream().filter(_.contains(ProbeMarker)).count().toInt

  /** 业务下发数 = `/exec` **请求体**里不含探针判别字面者（批 3 · 同族治本 A，2026-09-16）。
    *
    * 旧形态 `relayExecCalls.size() - relayExecProbeCount` 是**派生量**：两个读数共享
    * 一个来源 ⇒ 一旦探针计数器本身被改成常量（「恒 1 夹具」形态），派生值会**自动跟着
    * 对**，任何读该计数器的断言都无法识别「仪器在撒谎」（批 2 复核 §J6.3 的存活变异即此）。
    * 本轮把两个读数改成**各自独立按请求体统计**（同一 `relayExecBodies` 队列、两条互不
    * 依赖的过滤谓词）⇒ 「探针 / 业务」分判是两个**独立读数**，可分别被变异验红。
    *
    * ⚠ 可检验性边界（须知）：若把**两个**计数器同时改成「恒 1」，则断言面（`probes == 1
    * ∧ business == 1`）与**本场景真值**（1 探针 + 1 业务）重合 ⇒ 该变异在逻辑上**不可检**
    * ——这是夹具型判据的固有边界（「仪器读数 == 真值」时无从证伪），不是本夹具的判据弱化；
    * 本夹具对**生产行为**的判别力由「判别字面 / 计数谓词」两个方向的变异实证（见 spec 注释）。 */
  def relayExecBusinessCount: Int =
    relayExecBodies.stream().filter(b => !b.contains(ProbeMarker)).count().toInt
  /** How many times a live session was kicked by a newer login. */
  val kickedSessions = new AtomicInteger(0)

  private val liveTokens = new ConcurrentHashMap[String, String]() // key → token
  private val tokenOwner = new ConcurrentHashMap[String, String]() // token → key
  private val openRelaySockets = new CopyOnWriteArrayList[Socket]()
  private val threads = new CopyOnWriteArrayList[Thread]()

  /** 设备凭据表（踢旧批 r2）：key `(deviceId|networkId)` → 当前 deviceToken。
    * 真服务端语义（取证报告 §2.3）：`enroll_device` 是 `INSERT OR REPLACE INTO
    * device_credentials` ⇒ 重新注册**覆盖**旧凭据（旧 deviceToken 立刻失效，后续
    * `/api/device/session` 401）。夹具照此实现，否则「被踢后自动重注册」这条腿
    * 根本走不到 401 ⇒ 钉不具判别力。 */
  private val deviceCredentials = new ConcurrentHashMap[String, String]()

  /** Device-token serial (unrelated to the HTTP counters — see `registerDevice`). */
  private val deviceTokenIds = new AtomicInteger(0)

  private def deviceKey(deviceId: String, networkId: String): String = s"$deviceId|$networkId"

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

  /** Push one server→client text frame to EVERY open relay-ws connection
    * (the real `relay.rs` push path). Returns the number of sockets written.
    *
    * 踢旧批（2026-09-14）新增：钉「服务端主动推 `disconnect` 帧 ⇒ 被踢端被动提示
    * + 停摆」需要用真帧驱动，而不是直接调被测算出的方法。帧形态与生产同形
    * （`{"type":"disconnect"}`，零新字段）。 */
  def sendTextToRelay(text: String): Int =
    var n = 0
    openRelaySockets.forEach { s =>
      try
        writeTextFrame(s.getOutputStream, text)
        n += 1
      catch case _: Exception => ()
    }
    n

  def attemptCount(status: Int): Int = relayAttempts.stream().filter(_._1 == status).count().toInt

  // ---- device-registration leg (踢旧批 r2, 2026-09-14) ----

  /** Serve `POST /api/device/register`: mint a fresh device credential AND revoke
    * the previous one for (deviceId, networkId) — the real server's
    * kick-on-re-enroll (`store.rs enroll_device` + `INSERT OR REPLACE INTO
    * device_credentials`). Returns the new deviceToken.
    *
    * 🔴 本方法**不计数**：计数面（`registers` / `registerCalls`）只由 HTTP 路由递增，
    * 否则测试自己造「另一个实例登录」的那次调用会污染读数（判据必须只数被测端的请求）。 */
  def registerDevice(deviceId: String, networkId: String): String =
    val tok = s"dtok-${deviceTokenIds.incrementAndGet()}"
    deviceCredentials.put(deviceKey(deviceId, networkId), tok)
    // 同一次注册也踢掉该设备的旧**会话**（服务端 enroll_device 先 kick_sessions_where）。
    liveTokens.remove(deviceKey(deviceId, networkId)) match
      case null => ()
      case prev =>
        tokenOwner.remove(prev)
        kickedSessions.incrementAndGet()
    tok

  /** Is this deviceToken the CURRENT credential of (deviceId, networkId)? */
  def deviceTokenValid(deviceId: String, networkId: String, token: String): Boolean =
    token.nonEmpty && token == deviceCredentials.get(deviceKey(deviceId, networkId))

  /** 当前凭据（None = 从未注册 / 已被新注册覆盖）。 */
  def currentDeviceToken(deviceId: String, networkId: String): Option[String] =
    Option(deviceCredentials.get(deviceKey(deviceId, networkId)))

  /** Invalidate the current device credential WITHOUT issuing a new one — the
    * shape the kicked instance sees: its stored deviceToken stops being accepted
    * (`session` → 401) while the session token is dead too (`heartbeat` → 403). */
  def revokeDeviceCredential(deviceId: String, networkId: String): Unit =
    deviceCredentials.remove(deviceKey(deviceId, networkId))
    Option(liveTokens.remove(deviceKey(deviceId, networkId))).foreach(tokenOwner.remove)

  /** `POST /oidc/token`（refresh 腿）的最小可用响应。判据面是「register 到底发没发」，
    * provider 细节不在判据内 ⇒ 恒 200 + access_token（refresh_token 也回一个，
    * 覆盖 `DeviceCredential.updateLogtoRefresh` 的回写腿）。 */
  def respondToken(out: OutputStream): Unit =
    tokenCalls.incrementAndGet()
    respond(out, 200, """{"access_token":"mock-access-token","refresh_token":"mock-refresh-token-2"}""")

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
    else if path.startsWith("/oidc/token") then
      respondToken(out)
      closeQuietly(sock)
    else if path.startsWith("/api/device/register") then
      // 踢旧批 r2：设备注册腿（`LogtoDeviceFlow.register` → POST /api/device/register）。
      // 真服务端语义照搬：注册即覆盖旧凭据（kick-on-re-enroll），响应 = EnrollResponse
      // 的实字段子集（deviceToken 恒在；model.rs 的 EnrollResponse）。
      val body = parse(req.body).getOrElse(io.circe.Json.Null)
      val deviceId = body.hcursor.downField("deviceId").as[String].getOrElse("unknown-device")
      registers.incrementAndGet()
      registerCalls.add(deviceId)
      val networkId = enrollNetworkId
      val tok = registerDevice(deviceId, networkId)
      respond(
        out,
        200,
        s"""{"deviceToken":"$tok","networkId":"$networkId","deviceId":"$deviceId","avatarUrl":null,"githubUsername":null}"""
      )
      closeQuietly(sock)
    else if path.startsWith("/api/device/login") then
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
    else if path.startsWith("/api/device/session") then
      // 踢旧批 r2：凭据换会话。**凭据必须是当前有效者**——被踢（凭据被新注册覆盖）后
      // 这里回 401，与真服务端 `session_for_credential`（verify_device_credential 恒
      // false）一致；这正是「自动重登录 → 401 → silent re-login → register」链的起点。
      val body = parse(req.body).getOrElse(io.circe.Json.Null)
      val hc = body.hcursor
      val deviceId = hc.downField("deviceId").as[String].getOrElse("unknown-device")
      val networkId = hc.downField("networkId").as[String].getOrElse("qa-net")
      val dtok = hc.downField("deviceToken").as[String].getOrElse("")
      loginCalls.add(deviceId)
      if deviceTokenValid(deviceId, networkId, dtok) then
        val tok = loginAs(deviceId, networkId)
        respond(out, 200, s"""{"token":"$tok","networkId":"$networkId","deviceId":"$deviceId","peers":[]}""")
      else
        sessionRejections.incrementAndGet()
        // 状态码/体可配（见 `sessionRejectStatus`）：默认 401 令牌拒收原文。
        respond(out, sessionRejectStatus, sessionRejectBody)
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
      relayExecBodies.add(req.body) // 批 2（C06）：请求体级锚（探针 / 业务分判）
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
    // 升级后的连接必须**保持安静且不自我关闭**：serve() 给 socket 设了 10s
    // SO_TIMEOUT，只读不写的排空循环会在 10s 后抛 SocketTimeoutException ⇒
    // fixture 自己把连接关掉（僵尸场景根本走不到 30s 存活窗）。置 0 = 无限等待，
    // 让「对端静默」成为真正可观测的形态。
    try sock.setSoTimeout(0) catch case _: Exception => ()
    // Drain frames (ping/pong/close) so the client's writes never block. The
    // tunnel's own heartbeat would otherwise stall against a full socket buffer.
    // pongReplies=true 时顺带应答 ping（健康路径开关，见字段注释）。
    spawn {
      try
        val is = sock.getInputStream
        if pongReplies || recordClientFrames then
          var frame = readFrame(is)
          while frame.isDefined do
            val (op, payload) = frame.get
            if op == 0x1 then
              val text = new String(payload, StandardCharsets.UTF_8)
              if recordClientFrames then clientTextFrames.add(text)
              if pongReplies && text.contains("\"ping\"") then
                pongsSent.incrementAndGet()
                writeTextFrame(sock.getOutputStream, """{"type":"pong"}""")
            frame = readFrame(is)
        else
          val buf = new Array[Byte](4096)
          var n = is.read(buf)
          while n >= 0 do n = is.read(buf)
      catch case _: Throwable => ()
      finally
        openRelaySockets.remove(sock)
        closeQuietly(sock)
    }

  /** Read one client→server RFC 6455 frame (masked). None = stream end. */
  private def readFrame(in: InputStream): Option[(Int, Array[Byte])] =
    val h = in.readNBytes(2)
    if h.length < 2 then None
    else
      val op = h(0) & 0x0f
      val masked = (h(1) & 0x80) != 0
      var len = (h(1) & 0x7f).toLong
      if len == 126L then
        val e = in.readNBytes(2)
        if e.length < 2 then return None
        len = ((e(0) & 0xff) << 8 | (e(1) & 0xff)).toLong
      else if len == 127L then
        val e = in.readNBytes(8)
        if e.length < 8 then return None
        len = (0 until 8).foldLeft(0L)((acc, i) => (acc << 8) | (e(i) & 0xff).toLong)
      val mask = if masked then in.readNBytes(4) else Array.emptyByteArray
      val payloadBytes = if len > 0 then in.readNBytes(len.toInt) else Array.emptyByteArray
      if payloadBytes.length < len then None
      else
        if masked && mask.length == 4 then
          var i = 0
          while i < payloadBytes.length do
            payloadBytes(i) = (payloadBytes(i) ^ mask(i % 4)).toByte
            i += 1
        Some((op, payloadBytes))

  /** Server→client text frame (unmasked, single frame, len ≤ 125). */
  /** Close every open relay-ws connection with a proper RFC 6455 **close frame**
    * (opcode 0x8, status 1000) and then the TCP close.
    *
    * 踢旧批（2026-09-14）新增：服务端 `disconnect_device` 之后的收尾是「WS 关闭」。
    * 只做裸 socket close 时，JDK 客户端**不保证**及时回调 Listener（本仓 2026-09-11
    * 「僵尸闩」同类问题：`closed` Deferred 迟迟不完成）⇒ 依赖它的断言会抖动。给一
    * 个真 close 帧让对端走正常关闭握手，读数是确定的。
    * 返回处理的连接数。 */
  def closeRelaySocketsGracefully(): Int =
    var n = 0
    openRelaySockets.forEach { s =>
      try
        val out = s.getOutputStream
        out.write(Array[Byte](0x88.toByte, 0x02.toByte, 0x03.toByte, 0xE8.toByte)) // close, len=2, 1000
        out.flush()
        Thread.sleep(30)
        s.close()
        n += 1
      catch case _: Exception => ()
    }
    openRelaySockets.clear()
    n

  private def writeTextFrame(out: OutputStream, text: String): Unit =
    try
      val bytes = text.getBytes(StandardCharsets.UTF_8)
      out.write(Array[Byte](0x81.toByte, bytes.length.toByte))
      out.write(bytes)
      out.flush()
    catch case _: Exception => ()

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

  /** 只读画像探针的**请求体判别锚** —— 与生产侧 `RemoteExecutor.scala:306` 的字面同源，
    * 也与兄弟夹具 `StubPeerServer.probeHitCount` 的判别字面逐字一致（同根因、同口径）。 */
  private[neblink] val ProbeMarker = "xdev read-only profile probe"

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
