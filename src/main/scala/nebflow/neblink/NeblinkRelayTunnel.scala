package nebflow.neblink

import cats.effect.{Deferred, IO}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.core.tools.{ToolContext, ToolRegistry}

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.util.concurrent.*
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}

import scala.concurrent.duration.*

/**
 * WebSocket relay tunnel to the NebLink Server.
 *
 * Device B connects this tunnel to the server so that cross-network relay-exec
 * requests (from device A) can be routed to B via the server. Without this
 * tunnel, devices on different networks cannot reach each other's P2P endpoints.
 *
 * Protocol (server <-> device B):
 *   S->B: {"type":"relay_request","requestId":"uuid","action":"Bash","params":{...},"projectRoot":"..."}
 *   B->S: {"type":"relay_response","requestId":"uuid","output":"...","error":""}
 *   Heartbeat: {"type":"ping"} / {"type":"pong"}
 *
 * Auto-reconnects with exponential backoff (0s -> 1s -> 2s ... -> 30s cap).
 * Token is read live from NeblinkClient so re-login after token expiry works.
 * Always-on by design — started in GatewayMain when NebLink Server is configured.
 */
final class NeblinkRelayTunnel(
  neblinkService: NeblinkService,
  serverUrl: String,
  /** Live session-token source, evaluated on every (re)connect. F1 (2026-09-10
    * friend-search batch): GatewayMain wires this to the discovery-held
    * authoritative client (IO-based, resolved per attempt) so enrollment
    * hot-swaps are picked up — a constructor-time client closure kept reading
    * a session the server had kicked. */
  tokenGetter: () => IO[Option[String]],
  /** A2A 一期（spec §5.1）：friend_event 推送回调（事件去重/未读/补拉在 FriendService）。 */
  private[neblink] val friendService: Option[FriendService] = None
)(dispatcher: Dispatcher[IO]):
  import NeblinkRelayTunnel.{TunnelAuthStatus, shouldHealAuthFailure}

  private val logger = NebflowLogger.forName("nebflow.neblink.relay")

  @volatile private var wsRef: Option[WebSocket] = None
  private val running = new AtomicBoolean(true)
  private val alive = new AtomicBoolean(false)
  private val lastPong = new AtomicLong(System.currentTimeMillis())
  private var heartbeat: Option[ScheduledExecutorService] = None

  /** F7 (2026-09-10 隧道鉴权自愈批): last relay-ws upgrade auth rejection —
    * the state `relayAvailable = isAlive` cannot express (see authStatus). */
  @volatile private var lastAuthRejection: Option[TunnelAuthStatus] = None

  /** Anti-loop counter: consecutive upgrade failures since the last successful
    * connect. Drives shouldHealAuthFailure. */
  private val authFailStreak = new AtomicInteger(0)

  /** When the last self-heal re-login was attempted (0 = never). */
  @volatile private var lastHealAtMs = 0L

  /** Check if the relay tunnel is currently connected (for status reporting). */
  def isAlive: Boolean = alive.get()

  /** F7: last upgrade auth rejection (status code + self-heal outcome).
    * Surfaces on /neblink/status as an INDEPENDENT "auth rejected" state —
    * "tunnel dead" alone hides whether we are waiting on a 403 (our session was
    * kicked → self-healable) or on a 5xx (server side). */
  def authStatus: Option[TunnelAuthStatus] = lastAuthRejection

  /** Test seam: is the reconnect loop still running (false after stop())? */
  private[neblink] def isRunning: Boolean = running.get()

  /** Start the relay tunnel connection loop. Runs in background until stop(). */
  def connect(): IO[Unit] =
    logger.info("Starting relay tunnel to NebLink Server") *>
      connectLoop(0)

  /** Gracefully stop the tunnel. */
  def stop(): IO[Unit] =
    IO.blocking {
      running.set(false)
      alive.set(false)
      heartbeat.foreach { hb => try hb.shutdownNow() catch case _: Exception => () }
      wsRef.foreach { w => try w.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown") catch case _: Exception => () }
    }

  // ===== Connection loop =====

  private def connectLoop(attempt: Int): IO[Unit] =
    if !running.get() then IO.unit
    else
      val delay = if attempt == 0 then 0.seconds else math.min(30, 1 << (attempt - 1)).seconds
      IO.sleep(delay).flatMap { _ =>
        if !running.get() then IO.unit
        else
          tokenGetter().flatMap {
            case None =>
              val wait = math.min(30, 1 << math.min(attempt, 4)).seconds
              // R2 visibility: this branch used to log at DEBUG only — a login
              // that never completes left the tunnel dark for hours with zero
              // trace. INFO keeps the retry loop observable (≤2 lines/min).
              logger.info(s"Relay tunnel: no session token yet, retrying in ${wait.toSeconds}s...").flatMap { _ =>
                IO.sleep(wait).flatMap { _ => connectLoop(attempt + 1) }
              }
            case Some(token) =>
              neblinkService.identity
                .flatMap(id => connectOnce(id, token))
                .flatMap { _ =>
                  if running.get() then
                    logger.info("Relay tunnel disconnected, reconnecting...").flatMap { _ =>
                      connectLoop(0) // reset for immediate retry
                    }
                  else IO.unit
                }
                .handleErrorWith { e =>
                  // F3 (report §3): never log e.getMessage here — it is null for
                  // WebSocketHandshakeException and carries only the class NAME
                  // when wrapped in ExecutionException. describe() extracts the
                  // HTTP status (+ a redacted body snippet) instead.
                  val failure = RelayTunnelDiagnostics.describe(e)
                  if failure.authRejected then handleAuthRejection(failure, attempt)
                  else
                    logger.warn(s"Relay tunnel error: ${failure.summary}").flatMap { _ =>
                      if running.get() then connectLoop(attempt + 1) else IO.unit
                    }
                }
          }
      }

  /**
   * Auth-rejection path of the reconnect loop (report §4 F2/F3, U1).
   *
   * A 401/403 on the upgrade means OUR session token was rejected — under the
   * server's one-live-session-per-device policy that is what every fresh login
   * on this device (UI re-login, account switch, enrollment hot-swap) does to
   * the tunnel's session. The reconnect loop used to retry the SAME dead token
   * forever, so the device stayed unreachable until a gateway restart.
   *
   * NARROW gate: only 401/403 come here. 5xx / gateway / transport failures are
   * server-side or environmental — a re-login is meaningless for them and would
   * only add a login storm, so they take the plain reporting path above.
   *
   * Anti-loop: at most one re-login per failure streak (a further one only
   * after HealCooldownMs); the streak resets on the next successful connect.
   */
  private def handleAuthRejection(failure: RelayTunnelDiagnostics.UpgradeFailure, attempt: Int): IO[Unit] =
    val streak = authFailStreak.incrementAndGet()
    val nowMs = System.currentTimeMillis()
    val head = s"Relay tunnel upgrade rejected (auth): ${failure.summary}"
    if !shouldHealAuthFailure(streak, nowMs, lastHealAtMs) then
      IO { lastAuthRejection = Some(TunnelAuthStatus(failure.statusCode.getOrElse(0), nowMs, healAttempted = false, healSucceeded = false, active = true)) } *>
        logger.warn(s"$head — self-heal NOT retried (failure streak #$streak, anti-loop bound); backing off") *>
        (if running.get() then connectLoop(attempt + 1) else IO.unit)
    else
      for
        _ <- logger.warn(s"$head — attempting session self-heal (re-login)")
        _ <- IO { lastHealAtMs = nowMs }
        healed <- healSession()
        _ <- logger.warn(
          if healed then "Relay tunnel session self-heal: re-login OK — retrying with the refreshed token"
          else "Relay tunnel session self-heal: re-login FAILED — continuing with backoff"
        )
        _ <- IO { lastAuthRejection = Some(TunnelAuthStatus(failure.statusCode.getOrElse(0), nowMs, healAttempted = true, healSucceeded = healed, active = true)) }
        // The refreshed token is picked up by tokenGetter on the next attempt —
        // it reads the live client, never a cached/snapshotted token.
        _ <- if healed then connectLoop(0) else if running.get() then connectLoop(attempt + 1) else IO.unit
      yield ()

  /**
   * Run the shared single-flight re-login on the client the relay path actually
   * uses. `NeblinkService.relayClientOpt` is the hot-swap pointer: GatewayMain
   * registers the startup client, `NeblinkEnrollment.persist` re-points it on
   * every re-enrollment and logout clears it — so this is resolved per heal and
   * never captured at construction.
   *
   * It goes through `NeblinkClient.ensureFreshSession`, the SAME gate the
   * API-level heal (friend batch F2) uses: two independent gates would race two
   * logins, and since every login kicks our own previous session server-side,
   * they would kick each other in a loop. One gate per client instance means the
   * concurrent API 403s and this relay 403 collapse into one login.
   */
  private def healSession(): IO[Boolean] =
    IO(neblinkService.relayClientOpt).flatMap {
      case None =>
        logger.warn("Relay tunnel session self-heal: no live NebLink client — reporting only").as(false)
      case Some(client) =>
        client
          .ensureFreshSession("relay-upgrade-auth-reject")
          .handleErrorWith(e => logger.warn(s"Relay tunnel session self-heal errored (${e.getClass.getSimpleName})").as(false))
    }

  /** Establish a single WS connection; returns when the connection ends. */
  private def connectOnce(id: DeviceIdentity, token: String): IO[Unit] =
    Deferred[IO, Unit].flatMap { closed =>
      IO.blocking {
        val wsUri = buildRelayWsUri(serverUrl, id)
        val listener = new RelayWsListener(this, closed, dispatcher)
        val client = HttpClient
          .newBuilder()
          .version(HttpClient.Version.HTTP_1_1) // avoid HTTP/2 TLS issues with Caddy
          .proxy(java.net.ProxySelector.of(null)) // bypass system proxy
          .connectTimeout(java.time.Duration.ofSeconds(15))
          .build()
        val w = client
          .newWebSocketBuilder()
          .header("Authorization", s"Bearer $token")
          .buildAsync(URI.create(wsUri), listener)
          .get(10, TimeUnit.SECONDS)
        wsRef = Some(w)
        alive.set(true)
        lastPong.set(System.currentTimeMillis())
        startHeartbeat(w)
        // Anti-loop reset + F7: a successful upgrade means the credential
        // problem is over — the next rejection gets a fresh heal budget and the
        // status stops claiming "auth rejected" (code/time stay as history).
        authFailStreak.set(0)
        lastAuthRejection = lastAuthRejection.map(_.copy(active = false))
        logger.infoSync(s"Relay tunnel connected: $wsUri")
        ()
      } *> closed.get // block until WS closes
        <* IO.blocking {
          alive.set(false)
          stopHeartbeat()
          wsRef = None
        }
    }

  // ===== Relay request handling (called from WS listener thread) =====

  /** Execute a relay_request locally and send the response back over WS. */
  private[neblink] def handleRelayRequest(msg: Json, ws: WebSocket): IO[Unit] =
    val hc = msg.hcursor
    val requestId = hc.downField("requestId").as[String].getOrElse("")
    val action = hc.downField("action").as[String].getOrElse("")
    // Expand ~ to *this* device's user.home — must happen on the receiver so
    // the path resolves to the local filesystem (e.g. C:\Users\name on Windows),
    // not the sender's home directory.
    val params = PathUtil.expandPathParams(
      hc.downField("params").as[JsonObject].getOrElse(JsonObject.empty)
    )
    val projectRoot =
      hc.downField("projectRoot").as[String].getOrElse(System.getProperty("user.dir", "."))

    val toolOpt = ToolRegistry.TOOL_MAP.get(action)
    for
      (output, error) <- action match
        case "FileTransfer" =>
          FileTransferAction.handle(params).map {
            case Right(json) => (json.noSpaces, "")
            case Left(err)   => ("", err)
          }
        case "Notify" =>
          val payload = params("payload").getOrElse(Json.Null)
          neblinkService.handleDataMessage(payload).as(("notified", ""))
        case "RemoteUpdate" =>
          val beta = params("beta").flatMap(_.asBoolean).getOrElse(false)
          RemoteUpdateAction.runInstallScript(beta).flatMap {
            case Right(msg) =>
              // Schedule restart — same logic as RestApiRoutes POST /neblink/update
              IO.blocking(nebflow.core.RestartHelper.spawnRestart()) *>
                IO.delay(dispatcher.unsafeRunAndForget(IO.sleep(1.second) *> IO(System.exit(0)))) *>
                IO.pure((msg, ""))
            case Left(err) => IO.pure(("", err))
          }
        case _ =>
          toolOpt match
            case Some(tool) =>
              val ctx = ToolContext(projectRoot = projectRoot, isRemoteExec = true)
              tool.call(params, ctx).attempt.map {
                case Right(Right(result)) => (result, "")
                case Right(Left(err))     => ("", err.message)
                case Left(e)              => ("", s"Tool execution failed: ${e.getMessage}")
              }
            case None =>
              IO.pure(("", s"Unknown tool: $action"))
      resp = Json.obj(
        "type" -> "relay_response".asJson,
        "requestId" -> requestId.asJson,
        "output" -> output.asJson,
        "error" -> error.asJson
      )
      _ <- IO.blocking {
        try ws.sendText(resp.noSpaces, true)
        catch case _: Exception => ()
        ()
      }
    yield ()

  /** Called by WS listener when pong arrives — refreshes heartbeat liveness. */
  private[neblink] def updateLastPong(): Unit =
    lastPong.set(System.currentTimeMillis())

  /**
   * presence v2 (C6): handle a server-pushed DeviceStatusUpdate frame.
   *
   * Wire schema (dual-field tolerant — fixes the interop mismatch where the
   * server actually emits `{"deviceId":"...","status":"offline"}` while this
   * consumer only read the `online` boolean and defaulted to false, so a
   * future `status:"online"` frame would have been silently misread as
   * OFFLINE):
   *   - `online: true|false`     — boolean spelling (spec-proposed), takes priority
   *   - `status: "online"|"offline"` — string spelling (current server wire,
   *     pinned by the server's wire-shape test), case-insensitive fallback
   *   - neither present          — defaults to false (legacy behavior)
   * The snake_case `device_id` spelling is tolerated as well (camelCase
   * matches the relay protocol's requestId/eventId convention).
   *
   * Drives the same freshness path as heartbeats — the status endpoint and UI
   * badge flip within one push, no polling wait.
   */
  private[neblink] def handleDeviceStatusUpdate(msg: Json): IO[Unit] =
    val hc = msg.hcursor
    val deviceId = hc.downField("deviceId").as[String]
      .orElse(hc.downField("device_id").as[String])
      .getOrElse("")
    val online = hc.downField("online").as[Boolean].toOption
      .orElse(hc.downField("status").as[String].toOption.map(_.equalsIgnoreCase("online")))
      .getOrElse(false)
    if deviceId.isEmpty then logger.debug("DeviceStatusUpdate frame without deviceId — ignored")
    else neblinkService.applyServerPeerStatus(deviceId, online)

  // ===== Heartbeat =====

  private def startHeartbeat(ws: WebSocket): Unit =
    val hb = Executors.newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r, "relay-tunnel-hb")
      t.setDaemon(true)
      t
    }
    heartbeat = Some(hb)
    hb.scheduleAtFixedRate(
      { () =>
        try
          if alive.get() then
            if System.currentTimeMillis() - lastPong.get() > 30_000L then
              // Zombie-connection fix (diag-transfer-stuck R2): a half-open TCP
              // connection never delivers sendClose to the peer and the JDK
              // listener's onError/onClose never fire, so `closed` (the
              // connectOnce latch) stayed incomplete and the reconnect loop
              // stalled silently for hours. abort() tears the connection down
              // LOCALLY — the listener fires immediately, connectOnce returns
              // and connectLoop reconnects.
              logger.infoSync("Relay tunnel heartbeat timeout — aborting zombie connection for reconnect")
              ws.abort()
            else
              try ws.sendText("""{"type":"ping"}""", true)
              catch case _: Exception =>
                // send failure on a live-flagged connection means the socket is
                // already broken — abort now instead of waiting out the pong
                // window with a dead connection (alive must track reality).
                logger.debugSync("Relay tunnel ping send failed — aborting broken connection")
                ws.abort()
        catch case _: Exception => ()
      },
      10,
      10,
      TimeUnit.SECONDS
    )

  private def stopHeartbeat(): Unit =
    heartbeat.foreach { hb => try hb.shutdownNow() catch case _: Exception => () }
    heartbeat = None

  // ===== Helpers =====

  /** Convert serverUrl (https://...) to ws/wss and build the relay-ws endpoint. */
  private def buildRelayWsUri(serverUrl: String, id: DeviceIdentity): String =
    val wsBase = serverUrl
      .replaceFirst("https://", "wss://")
      .replaceFirst("http://", "ws://")
    val encodedId = try java.net.URLEncoder.encode(id.deviceId, "UTF-8")
    catch case _: Exception => id.deviceId
    s"$wsBase/api/device/relay-ws?deviceId=$encodedId"

end NeblinkRelayTunnel

object NeblinkRelayTunnel:

  /**
   * Minimum interval between two auth-rejection self-heals inside the SAME
   * failure streak (see `shouldHealAuthFailure`). One re-login per minute is
   * the ceiling for a server that keeps rejecting our token — the same order of
   * magnitude as the normal login cadence, i.e. no login storm.
   */
  private[neblink] val HealCooldownMs = 60_000L

  /**
   * Anti-loop decision for the auth-rejection self-heal (the F2 obligation:
   * "重登失败不得无界循环"), mirroring `NeblinkClient.reloginAllowed`'s
   * single-shot rule.
   *
   * The FIRST rejection of a failure streak heals immediately (that is the
   * incident case: the session was kicked, one re-login fixes it). Later
   * rejections of the same streak only heal again after `HealCooldownMs`, so a
   * server that rejects our token forever costs at most one re-login per minute
   * instead of one per backoff round. The streak resets on every successful
   * connect, giving a legitimately recovered device a fresh budget.
   */
  private[neblink] def shouldHealAuthFailure(streak: Int, nowMs: Long, lastHealAtMs: Long): Boolean =
    streak <= 1 || (lastHealAtMs > 0L && nowMs - lastHealAtMs >= HealCooldownMs)

  /**
   * Last relay-ws upgrade auth rejection (F7, report §4 F7).
   *
   * `active` = the tunnel is currently parked on this rejection (cleared by the
   * next successful connect); `statusCode` / `atMs` stay as history so a
   * recovered device can still be diagnosed after the fact.
   */
  final case class TunnelAuthStatus(
    statusCode: Int,
    atMs: Long,
    healAttempted: Boolean,
    healSucceeded: Boolean,
    active: Boolean
  )

  /**
   * `/neblink/status` payload for the relay tunnel (F7). `relayAvailable` alone
   * (the only relay field the endpoint had) cannot distinguish "tunnel is down"
   * from "tunnel is down BECAUSE our session was rejected" — the latter is what
   * drives the self-heal path and is the client-side half of the report §6
   * cross-project discriminator.
   */
  def statusJson(available: Boolean, status: Option[TunnelAuthStatus]): io.circe.Json =
    io.circe.Json.obj(
      "available" -> available.asJson,
      "authRejected" -> status.exists(_.active).asJson,
      "lastRejectedStatusCode" -> status.map(_.statusCode).asJson,
      "lastRejectedAt" -> status.map(_.atMs).asJson,
      "selfHeal" -> status
        .map(s => if !s.healAttempted then "not-attempted" else if s.healSucceeded then "ok" else "failed")
        .asJson
    )

end NeblinkRelayTunnel

/**
 * JDK WebSocket.Listener for the relay tunnel.
 * Forwards events to NeblinkRelayTunnel — stateless on its own.
 */
private final class RelayWsListener(
  tunnel: NeblinkRelayTunnel,
  closed: cats.effect.Deferred[IO, Unit],
  dispatcher: Dispatcher[IO]
) extends WebSocket.Listener:
  private val logger = NebflowLogger.forName("nebflow.neblink.relay")

  override def onOpen(ws: WebSocket): Unit =
    ws.request(1)

  override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
    try
      decode[Json](data.toString) match
        case Right(json) =>
          json.hcursor.downField("type").as[String].getOrElse("") match
            case "relay_request" =>
              dispatcher.unsafeRunAndForget(tunnel.handleRelayRequest(json, ws))
            case "ping" =>
              try ws.sendText("""{"type":"pong"}""", true)
              catch case _: Exception => ()
            case "pong" =>
              tunnel.updateLastPong()
            case "friend_event" =>
              // A2A 一期：好友/消息推送（spec §5.1 复用 relay 隧道）。尽力而为
              // 优化——REST 补拉兜底，事件丢失不影响正确性。
              tunnel.friendService.foreach { fs =>
                dispatcher.unsafeRunAndForget(fs.onFriendEvent(json))
              }
            case "device_status_update" =>
              // presence v2 (C6)：设备上下线推送——隧道关闭/探活判死时服务端广播。
              // 帧驱动为主（<2s 翻转），心跳顺带拉取降级为帧丢失兜底。
              dispatcher.unsafeRunAndForget(tunnel.handleDeviceStatusUpdate(json))
            case "disconnect" =>
              // presence fix A1 (server-side): forced tunnel teardown on logout.
              // The WS close follows; the reconnect loop's behavior after a
              // server-forced disconnect is a pending product decision.
              logger.debugSync("Relay tunnel: server sent Disconnect")
            case _ => ()
        case Left(_) => ()
    catch case _: Exception => ()
    end try
    ws.request(1)
    null

  override def onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage[?] =
    dispatcher.unsafeRunAndForget(closed.complete(()).void)
    null

  override def onError(ws: WebSocket, error: Throwable): Unit =
    logger.debugSync(s"Relay WS error: ${error.getMessage}")
    dispatcher.unsafeRunAndForget(closed.complete(()).void)
end RelayWsListener
