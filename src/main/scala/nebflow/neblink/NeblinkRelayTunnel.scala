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
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference}

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
 * Always-on by design — GatewayMain installs it unconditionally (2026-09-11)
 * and it idles in a DEBUG backoff until a server URL appears.
 *
 * 2026-09-11 (tunnel lifecycle fix) — three defects closed here:
 *  1. `serverUrl` used to be a constructor-time value: the tunnel was installed
 *     once at boot, so `updateConfig` / enrollment could never re-point it.
 *     It is now resolved LIVE per attempt from the config ref (the URL knob is
 *     gone from the constructor entirely).
 *  2. There was no "no URL configured yet" branch: the loop only handled
 *     "no session token". A fresh home (no NebLink config) now idles with a
 *     DEBUG log and a 5-minute backoff ceiling instead of noise.
 *  3. `stop()` (logout) set `running=false` and NOTHING ever set it back —
 *     "logout → log in again" left the relay dead until a process restart.
 *     `start()` / `ensure()` revive it, single-flight via CAS (no bare
 *     check-then-act ⇒ no double tunnel).
 */
final class NeblinkRelayTunnel(
  neblinkService: NeblinkService,
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

  /** clientconn item 1: consecutive SHORT-LIVED connections (each dropped
    * before `StableConnectionMs`). Drives the stability gate of the backoff
    * reset — the first short-lived drop retries immediately (pre-fix speed for
    * a transient blip); a repeated streak means a flap loop and escalates. */
  private val shortLivedStreak = new AtomicInteger(0)

  /** When the last self-heal re-login was attempted (0 = never). */
  @volatile private var lastHealAtMs = 0L

  /** Check if the relay tunnel is currently connected (for status reporting).
    *
    * ①-2 语义诚实化（2026-09-12 波3，方案 §2.1 ①opt-A1 / §6.2 ①-2）：修前
    * `alive` 单点——它只在 `closed.get` 返回之后才被清假（:313-317），而闩在
    * 「abort 不回调 Listener」的僵尸态里永不返回（E3 探针复现）⇒ 通道死了 4 小时
    * 而 `/api/neblink/status` 仍报 `relay.available:true`（E4 假阳性）。
    * 现语义 = **连线在册 且 最近一次 pong 在 30s 窗口内**——判据与心跳的僵尸
    * 判据同源（同一个 `LivenessTimeoutMs`，不新增第二个阈值）。消费面：
    * status 端点的 `relay.available`（含 per-peer reachable 提示）与
    * `RemoteExecutor` 的 P2P/relay 选路（僵尸隧道不再被当作可用 relay）。 */
  def isAlive: Boolean =
    alive.get() && (System.currentTimeMillis() - lastPong.get()) < NeblinkRelayTunnel.LivenessTimeoutMs

  /** F7: last upgrade auth rejection (status code + self-heal outcome).
    * Surfaces on /neblink/status as an INDEPENDENT "auth rejected" state —
    * "tunnel dead" alone hides whether we are waiting on a 403 (our session was
    * kicked → self-healable) or on a 5xx (server side). */
  def authStatus: Option[TunnelAuthStatus] = lastAuthRejection

  /** Test seam: is the reconnect loop still running (false after stop())? */
  private[neblink] def isRunning: Boolean = running.get()

  /** Test seam: how many connection-loop chains have been spawned. Two
    * `ensure()` calls must spawn ONE chain (no double tunnel) — asserting the
    * spawn counter is deterministic, unlike inferring it from reconnect
    * attempt counters. */
  private val loopSpawns = new AtomicInteger(0)
  private[neblink] def loopSpawnCount: Int = loopSpawns.get()

  /** N3 log-voice latch for the "configured but no session token" state, which
    * is a STEADY state since logout keeps the server URL: first occurrence
    * INFO (visibility), the rest DEBUG (no permanent INFO spam). Reset on
    * every successful connect, so a later outage gets a visible first line. */
  private val noTokenInfoLogged = new AtomicBoolean(false)

  /** Wake latch (clientconn item 1): `start()` / `ensure()` completes it, which
    * cuts an in-flight idle nap short.
    *
    * WHY: `ensure()` used to be a pure no-op whenever `running == true` — so an
    * enrollment that lands while the loop is idling (no URL ⇒ nap ceiling 300s,
    * no token ⇒ 30s) could not wake it. The freshly enrolled device therefore
    * spent minutes unreachable on relay before the tunnel noticed the new
    * config (worst case = one full 5-minute nap; measured in the item-1 spec).
    *
    * Lost-wakeup analysis: the latch is RE-ARMED right before each nap. A wake
    * that arrives while the loop is awake (between naps) is dropped on purpose —
    * in that state the loop is about to re-read the config / token anyway, so
    * the wake is redundant rather than lost. */
  private val wakeLatch = new AtomicReference[Deferred[IO, Unit]](null)

  /** A wake that has not been consumed yet. `nap` consumes it at entry: the
    * first wait after a wake does NOT sleep (the wake's intent is "re-evaluate
    * now — config / token / connection state just changed"). This is what makes
    * `ensure()` effective on a two-sleep iteration (retry delay + idle wait):
    * cutting the in-flight nap alone would still leave the second sleep ahead. */
  private val wakePending = new AtomicBoolean(false)

  /** Interruptible sleep: returns when `d` elapses OR `signalWake()` fires.
    * A pending (unconsumed) wake short-circuits the wait entirely. */
  private[neblink] def nap(d: FiniteDuration): IO[Unit] =
    if wakePending.getAndSet(false) then IO.unit
    else if d.toMillis <= 0L then IO.unit
    else
      Deferred[IO, Unit].flatMap { latch =>
        IO(wakeLatch.set(latch)) *>
          IO.race(latch.get, IO.sleep(d)).void <*
          IO(wakeLatch.compareAndSet(latch, null)).void
      }

  /** Cut the current nap short (and arm the "do not sleep next time" flag).
    * Idempotent: a second call while nothing is sleeping only sets the flag. */
  private[neblink] def signalWake(): IO[Unit] =
    IO(wakePending.set(true)) *> IO(wakeLatch.get()).flatMap {
      case null  => IO.unit
      case latch => latch.complete(()).void
    }

  /** Test seam: is a nap currently armed (i.e. the loop is sleeping)? */
  private[neblink] def napArmed: Boolean = wakeLatch.get() != null

  /** Spawn one connection loop chain (fire-and-forget; the loop ends on
    * running == false). */
  private def spawnLoop(): IO[Unit] =
    IO(loopSpawns.incrementAndGet()).void *> IO {
      dispatcher.unsafeRunAndForget(
        connectLoop(0).handleErrorWith(e =>
          logger.warn(s"Relay tunnel loop terminated unexpectedly (${e.getClass.getSimpleName})")
        )
      )
    }

  /** Start the relay tunnel connection loop (boot entry). Returns immediately —
    * the loop itself runs in the background until stop(). */
  def connect(): IO[Unit] =
    logger.info("Starting relay tunnel to NebLink Server") *>
      IO(running.set(true)) *> spawnLoop()

  /** Idempotent (re)start — revives a tunnel that `stop()` (logout) shut down.
    *
    * Single-flight via CAS: concurrent callers (enrollment hot-swap + boot)
    * cannot spawn a second loop, and there is no bare check-then-act on a
    * shared flag. `running` starts true (the tunnel is constructed running),
    * so `ensure()` before any stop() is a no-op.
    */
  def start(): IO[Unit] =
    IO(running.compareAndSet(false, true)).flatMap {
      case false => signalWake() // already running — the only useful semantics left
      case true  => logger.info("Relay tunnel (re)starting after stop()") *> spawnLoop()
    }

  /** Alias of `start()` — the "make sure the tunnel runs" signal used by the
    * enrollment hot-swap path (`NeblinkService.ensureRelayTunnel`). */
  def ensure(): IO[Unit] = start()

  /** Gracefully stop the tunnel. */
  def stop(): IO[Unit] =
    signalWake() *> IO.blocking {
      running.set(false)
      alive.set(false)
      heartbeat.foreach { hb => try hb.shutdownNow() catch case _: Exception => () }
      wsRef.foreach { w => try w.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown") catch case _: Exception => () }
    }

  // ===== Connection loop =====

  /** Live NebLink Server URL, resolved on EVERY attempt from the config ref
    * (precedent: `RestApiRoutes.neblinkServerUrl` / `relayTunnelOpt`). Never a
    * constructor-time snapshot: the tunnel object is installed once at boot and
    * may be re-pointed by enrollment without being rebuilt. */
  private def currentServerUrl: IO[Option[String]] =
    neblinkService.neblinkConfig.map(_.neblinkServer.map(_.url))

  private def connectLoop(attempt: Int): IO[Unit] =
    if !running.get() then IO.unit
    else
      // 每拍**只睡一次**（clientconn item 1）：修前是「拍首 delay + 分支 wait」双睡，
      // 而唤醒只能掐断其中一次 ⇒ 唤醒语义被打折（掐断 300s 空转后还要再睡一拍的
      // 30s 上限）。现在按状态选等待：未配置 / 无 token 走各自的安静梯子（可被
      // `ensure()`/`stop()` 掐断），建连退避走重连梯子（0,1,2,4,8,16,30 上限 + 抖动）。
      currentServerUrl.flatMap {
        case None =>
          // Not configured (fresh home / never enrolled): the tunnel is
          // installed unconditionally, so this state must idle QUIETLY.
          // DEBUG + backoff ceiling raised to 5 minutes (N3): a permanent
          // INFO line here would be pure noise for users who never use
          // NebLink.
          // The idle wait is INTERRUPTIBLE (clientconn item 1): enrollment calls
          // `ensure()` the moment the config lands, and that signal must not have
          // to wait out a 5-minute nap (pre-fix: ensure() was a pure no-op while
          // running ⇒ relay stayed dark for minutes after the user finished
          // enrolling).
          val wait = math.min(300L, 1L << math.min(attempt, 9)).seconds
          logger
            .debug(s"Relay tunnel: no NebLink server configured yet, retrying in ${wait.toSeconds}s...")
            .flatMap { _ => nap(wait) *> connectLoop(attempt + 1) }
        case Some(url) =>
          // 建连退避基值：0,1,2,4,8,16,30,30… (+ 抖动)。`backoffSeconds` 把移位量
          // 钉在上限内并用 Long —— 修前写法 `min(30, 1 << (attempt-1))` 在 attempt=32
          // 处 Int 溢出成负数（`nap(负)` 立即返回 ⇒ 断网中段凭空一次 0 延迟重连），
          // 此后又从 1s 重爬：长断网（>16min）每 32 拍丢一次上限。
          // **睡在 tokenGetter 之前**（修前同一顺序）：tokenGetter 是 live 读取，
          // 睡醒后再读才不会拿着「等待期间已被重新登录替换掉」的旧 token 去升级
          // （那样会凭空多一次 403 自愈——实测在 R3 上复现过）。
          val pre =
            if attempt == 0 then 0.seconds
            else NeblinkRelayTunnel.jittered(NeblinkRelayTunnel.backoffSeconds(attempt).seconds, scala.util.Random.nextDouble())
          nap(pre) *> (if !running.get() then IO.unit else
          tokenGetter().flatMap {
            case None =>
              val wait = math.min(30L, 1L << math.min(attempt, 4)).seconds
              // R2 visibility: this branch used to log at DEBUG only — a login
              // that never completes left the tunnel dark for hours with zero
              // trace. INFO keeps the retry loop observable (≤2 lines/min).
              // N3: this state is STEADY after logout (the server URL is
              // kept), so only the FIRST occurrence is INFO.
              val line = s"Relay tunnel: no session token yet, retrying in ${wait.toSeconds}s..."
              val voice =
                if noTokenInfoLogged.compareAndSet(false, true) then logger.info(line)
                else logger.debug(line)
              voice.flatMap { _ => nap(wait) *> connectLoop(attempt + 1) }
            case Some(token) =>
              val attemptConnect =
                IO(System.currentTimeMillis()).flatMap { startedAtMs =>
                  neblinkService.identity
                    .flatMap(id => connectOnce(id, url, token))
                    .flatMap { _ =>
                      if running.get() then
                        // Backoff RESET is stability-gated (clientconn item 1):
                        // pre-fix every disconnect went back to `connectLoop(0)` —
                        // a 0-delay reconnect even for a connection the server
                        // accepted and dropped immediately (kick-after-upgrade,
                        // half-open flap) ⇒ unbounded upgrade storm at wire speed.
                        // Now the FIRST short-lived drop still retries immediately
                        // (transient blips recover at pre-fix speed), while a
                        // REPEATED short-lived streak escalates the ladder.
                        val heldMs = System.currentTimeMillis() - startedAtMs
                        val streakBefore = shortLivedStreak.get()
                        val next = NeblinkRelayTunnel.nextAttemptAfterDrop(attempt, heldMs, streakBefore)
                        if heldMs >= NeblinkRelayTunnel.StableConnectionMs then shortLivedStreak.set(0)
                        else shortLivedStreak.incrementAndGet()
                        logger
                          .info(s"Relay tunnel disconnected after ${heldMs / 1000}s, reconnecting (step $next, short-lived streak $streakBefore)...")
                          .flatMap { _ => connectLoop(next) }
                      else IO.unit
                    }
                }
              attemptConnect.handleErrorWith { e =>
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
          })
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
  private def connectOnce(id: DeviceIdentity, url: String, token: String): IO[Unit] =
    Deferred[IO, Unit].flatMap { closed =>
      IO.blocking {
        val wsUri = buildRelayWsUri(url, id)
        val listener = new RelayWsListener(this, closed, dispatcher)
        // HTTP/1.1 pinned (D1). Evidence: this peer is neblink-server behind
        // the SAME Caddy as NeblinkClient — see the canonical note there. The
        // old "avoid HTTP/2 TLS issues with Caddy" wording asserted a cause
        // that no reading on this link supports (2026-09-12 probe: 14/14
        // HTTP_2 200, 0 GOAWAY, 0 TLS alert); 未证 either way (intermittent
        // original symptom, no logs kept). Judge-red: a WS upgrade failure
        // whose classified outbound-failure bucket is GOAWAY / closed-reset
        // while h2 is selected. Not converged into OutboundHttpClients: one
        // client per WS connection is the tunnel's own lifecycle, not a
        // per-call construction (D5 scope = 4 sites).
        val client = HttpClient
          .newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
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
        startHeartbeat(w, closed)
        // Anti-loop reset + F7: a successful upgrade means the credential
        // problem is over — the next rejection gets a fresh heal budget and the
        // status stops claiming "auth rejected" (code/time stay as history).
        authFailStreak.set(0)
        lastAuthRejection = lastAuthRejection.map(_.copy(active = false))
        // N3: re-arm the first-line INFO voice — a later outage (e.g. after
        // logout) gets one visible line instead of silence.
        noTokenInfoLogged.set(false)
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

  /**
   * ①opt-A1 通道自愈（2026-09-12 波3，方案 §2.1）：`WebSocket.abort()` 只撕
   * socket（服务端看到 FIN），**不回调 Listener 的 onError/onClose**——JDK
   * 实机探针复现（`.nebflow/evidence/20260912_friendmsg-batch/fm-realtime-recon/AbortProbe.java`，
   * Temurin 23.0.1，同宿主 JDK）。因此 `closed`（connectOnce 唯一等待的闩，
   * :312）永不完成：`alive.set(false)` / `stopHeartbeat()` 那半段收尾
   * （:313-317）不执行，`connectLoop` 停在 `closed.get` 上不再前进，心跳继续
   * 每 10s 一条「heartbeat timeout」刷日志——正是 2026-09-11 20:41 之后约 3.3
   * 小时（1192 条超时 / 0 条重连）的形态。
   *
   * 修法 = 看门狗：abort 之后给闩一个宽限窗（`ZombieLatchGrace`），到点仍未
   * 完成就由我们补 `complete(())`。`Deferred#complete` 完成过的再调是
   * **幂等 no-op**（返回 false），故「Listener 稍后真的回调」与「看门狗先到」
   * 两条路不竞态、不重复收尾（收尾动作在 `connectOnce` 的 `guarantee` 半段，
   * 只会跑一次）。
   */
  private def armZombieLatchWatchdog(closed: Deferred[IO, Unit]): Unit =
    dispatcher.unsafeRunAndForget(
      IO.sleep(NeblinkRelayTunnel.ZombieLatchGrace) *>
        closed.complete(()).void.handleErrorWith { e =>
          logger.debug(s"Relay tunnel latch watchdog errored (${e.getClass.getSimpleName})")
        }
    )

  private def startHeartbeat(ws: WebSocket, closed: Deferred[IO, Unit]): Unit =
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
            if System.currentTimeMillis() - lastPong.get() > NeblinkRelayTunnel.LivenessTimeoutMs then
              // Zombie-connection fix (diag-transfer-stuck R2): a half-open TCP
              // connection never delivers sendClose to the peer and the JDK
              // listener's onError/onClose never fire, so `closed` (the
              // connectOnce latch) stayed incomplete and the reconnect loop
              // stalled silently for hours. abort() tears the connection down
              // LOCALLY — the listener fires immediately, connectOnce returns
              // and connectLoop reconnects.
              // 波3（①opt-A1）：该注释里的「listener fires immediately」经探针证伪
              // ——abort 不回调，故此处补看门狗把闩补完（幂等）。
              logger.infoSync("Relay tunnel heartbeat timeout — aborting zombie connection for reconnect")
              ws.abort()
              armZombieLatchWatchdog(closed)
            else
              try ws.sendText("""{"type":"ping"}""", true)
              catch case _: Exception =>
                // send failure on a live-flagged connection means the socket is
                // already broken — abort now instead of waiting out the pong
                // window with a dead connection (alive must track reality).
                logger.debugSync("Relay tunnel ping send failed — aborting broken connection")
                ws.abort()
                armZombieLatchWatchdog(closed)
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
   * Liveness window (①-2 / ①opt-A1，波3 2026-09-12）：pong 超出此窗口 = 连接是
   * 僵尸。**同一个值**同时供心跳的僵尸判据与 `isAlive` 使用（不新增第二个
   * 阈值——两处判据若漂移，status 会与自愈动作各说各话）。数值 30s 沿既有心跳
   * 阈值原样搬移，未改任何既有默认值（H-1）。
   */
  private[neblink] val LivenessTimeoutMs = 30_000L

  /** Backoff ceiling of the reconnect ladder (unchanged value — the pre-fix
    * expression also capped at 30s; what changed is that the cap now holds for
    * every attempt, see [[backoffSeconds]]). */
  private[neblink] val BackoffCapSeconds = 30L

  /**
   * Reconnect-ladder base (seconds): 0, 1, 2, 4, 8, 16, 30, 30, 30… for
   * `attempt` = 0, 1, 2, 3, 4, 5, 6, 7, …
   *
   * clientconn item 1 (退避上限修正): the pre-fix call site was
   * `math.min(30, 1 << (attempt - 1))` on `Int`. At `attempt = 32` the shift
   * `1 << 31` yields `Int.MinValue`, so `IO.sleep(negative)` returned instantly
   * (a 0-delay reconnect in the middle of an outage) and every later attempt
   * restarted the ladder from 1s — the 30s ceiling silently collapsed once per
   * 32 attempts, i.e. every ~16 minutes of continuous outage. Clamping the shift
   * amount and computing in `Long` makes the ladder monotone up to the cap.
   */
  private[neblink] def backoffSeconds(attempt: Int): Long =
    if attempt <= 0 then 0L
    else math.min(BackoffCapSeconds, 1L << math.min(attempt - 1, 20))

  /**
   * Connection lifetime that marks a connection as STABLE (clientconn item 1,
   * 退避复位判据).
   *
   * A connection that held for at least this long counts as "the tunnel was
   * genuinely working, this drop is new" ⇒ the ladder resets (attempt 0 = the
   * immediate retry the pre-fix code always did). Anything shorter is a flap.
   */
  private[neblink] val StableConnectionMs = 30_000L

  /**
   * Attempt index for the next reconnect after a connection that held for
   * `heldMs`, given how many consecutive short-lived connections already
   * preceded it (`shortLivedStreak`).
   *
   * clientconn item 1: pre-fix EVERY disconnect went back to `connectLoop(0)`
   * — a 0-delay reconnect even for a connection the server accepted and dropped
   * immediately (kick-after-upgrade, half-open NIC flap) ⇒ an unbounded upgrade
   * storm at wire speed. Policy now:
   *   - stable connection (`heldMs >= StableConnectionMs`) ⇒ 0: immediate retry,
   *     the pre-fix behaviour for a real outage;
   *   - FIRST short-lived drop of a streak ⇒ 0 as well: a transient blip still
   *     recovers at pre-fix speed (no needless backoff on the common case);
   *   - a REPEATED short-lived streak ⇒ escalate from the attempt that failed,
   *     i.e. the ladder (1s, 2s, 4s…) finally engages.
   */
  private[neblink] def nextAttemptAfterDrop(attempt: Int, heldMs: Long, shortLivedStreak: Int): Int =
    if heldMs >= StableConnectionMs then 0
    else if shortLivedStreak <= 0 then 0
    else math.max(1, attempt + 1)

  /** Relative jitter applied to every backoff sleep (± this fraction). */
  private[neblink] val JitterFraction = 0.2

  /**
   * Spread reconnect attempts (clientconn item 1, 退避抖动): all devices of an
   * account share the same server and the same outage windows (network blip,
   * server restart), so a fixed ladder makes them retry in lockstep. ±20%
   * jitter breaks the alignment without changing the ladder's order of
   * magnitude. `unitRand` ∈ [0,1) is injected so the ladder stays testable
   * (0.5 = no jitter).
   */
  private[neblink] def jittered(base: FiniteDuration, unitRand: Double): FiniteDuration =
    val f = 1.0 - JitterFraction + unitRand * (2 * JitterFraction)
    math.max(0L, math.round(base.toMillis * f)).millis

  /**
   * `ws.abort()` 之后给闩的宽限窗口（①opt-A1）：到点 `closed` 仍未完成 ⇒ 看门狗
   * 补完成。取值 5s = 「远小于一次心跳间隔的一半」且远小于验收判据的 60s 上限，
   * 使自愈时延可见地落在判据内（心跳超时 → ≤5s 收尾 → 立即重连）。
   */
  private[neblink] val ZombieLatchGrace: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(5).seconds

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
