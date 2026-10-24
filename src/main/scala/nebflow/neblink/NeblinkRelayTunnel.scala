package nebflow.neblink

import cats.effect.std.Dispatcher
import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.RelayTunnelPort
import nebflow.core.hotupdate.RemoteUpdateAction
import nebflow.core.tools.{ToolContext, ToolRegistry}
import nebflow.shared.{DeviceMail, NebflowLogger, PathUtil}

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.util.concurrent.*
import java.util.concurrent.atomic.*

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
  /**
   * Live session-token source, evaluated on every (re)connect. F1 (2026-09-10
   * friend-search batch): GatewayMain wires this to the discovery-held
   * authoritative client (IO-based, resolved per attempt) so enrollment
   * hot-swaps are picked up — a constructor-time client closure kept reading
   * a session the server had kicked.
   */
  tokenGetter: () => IO[Option[String]],
  /** A2A 一期（spec §5.1）：friend_event 推送回调（事件去重/未读/补拉在 FriendService）。 */
  private[neblink] val friendService: Option[FriendService] = None,
  /**
   * Phase 3a portal tunnel faces (design §3): late-bound host faces (lifted
   * HTTP slice + conn-guard tunnel port + gateway port). None = portal frame
   * consumption is not wired (tests / pre-assembly shapes) — downlink portal
   * frames then drop with an explicit WARN, never silently.
   */
  portalHost: Option[PortalHostFaces] = None,
  /**
   * Phase 3b (plan §3.1/§5): the gateway-side revocation ports, injected by
   * GatewayMain from the SAME stores the gateway face holds (never a second
   * instance). Both are bound onto the faces INSIDE this constructor — the
   * constructor completes before `connect()` fires, so there is no window in
   * which a binding-dead signal observes an unbound face.
   *  - `sessionRevocation` — revoke every relay-mint gateway session
   *    (`GatewaySessionStore.revokeRelayMinted`);
   *  - `nfTicketRevocation` — revoke every nf path-read ticket
   *    (`NfTicketStore.revokeAll`; a session-revocation face that left those
   *    tickets alive would keep a ≤TTL read window open past the revocation).
   * Defaults keep every existing construction site (specs) compiling; the
   * no-op default makes the absent port a counted zero, never a failure.
   */
  sessionRevocation: Option[IO[Int]] = None,
  nfTicketRevocation: Option[IO[Int]] = None
)(dispatcher: Dispatcher[IO])
    extends RelayTunnelPort: // 严格DAG第⑥步第二批裁定(2026-09-27,R3):原地混入 core 窄视图(isAlive,RemoteExecutor 选路读数)
  import NeblinkRelayTunnel.{AckOutcome, TunnelAuthStatus, shouldHealAuthFailure}

  private val logger = NebflowLogger.forName("nebflow.neblink.relay")

  @volatile private var wsRef: Option[WebSocket] = None
  private val running = new AtomicBoolean(true)
  private val alive = new AtomicBoolean(false)
  private val lastPong = new AtomicLong(System.currentTimeMillis())

  /**
   * hbzombie-src（2026-10-14 客户端腿同案确认批）：**ping 的最后成功入线时刻**。
   *
   * 与 [[lastPong]] 成对，构成僵尸判据「起算点」的另一半：`LivenessTimeoutMs` 的
   * 本义是「我们本应收到那个 pong 而没收到」的等待，**不是**「距离上次上行已经
   * 多久」。修前只有 `lastPong` 一个读数 ⇒ 它同时兼任了「pong 等待窗」与「任意
   * 帧间隔」两义：只要本端**自己没有成功发出**一次 ping（大流量下 `sendText` 被
   * 对端 TCP 背压阻塞、或与本 socket 上在飞的 bulk 发送撞 JDK 单发契约），pong
   * 自然永不到来，9s 到点即判僵尸——但这不是对端沉默，是本端自己写不出去。
   *
   * 取值点 = `sendText` 入队成功（future 返回），即「这一拍真的上了线」；心跳
   * 僵尸判据与 `isAlive` 共用它，不新增第二个阈值轴。pump-selfabort-fix 起放宽为
   * `private[neblink]`：饱和跳拍分支会回滚本戳，回归钉须直读它验证「跳拍不得
   * 伪造 D3 的『问出去过』前提」（与 `sendHeartbeatPing` 同款测试可见性）。
   */
  private[neblink] val lastPingSentAt = new AtomicLong(System.currentTimeMillis())
  private var heartbeat: Option[ScheduledExecutorService] = None

  /**
   * F7 (2026-09-10 隧道鉴权自愈批): last relay-ws upgrade auth rejection —
   * the state `relayAvailable = isAlive` cannot express (see authStatus).
   */
  @volatile private var lastAuthRejection: Option[TunnelAuthStatus] = None

  /**
   * Phase 3a portal dependencies (process-global, owned by the tunnel):
   * late-bound faces + console ticket store + binding state machine +
   * live identity + the gateway-side nf-ticket revocation port. The binding
   * state persists across restarts (unlike the kick park), so a revoked host
   * stays terminal after a process restart.
   *
   * Phase 3b (plan §3.1/§5): BOTH new faces are bound right here, in the
   * constructor — `connect()` is fired by the assembler immediately after
   * this constructor returns, so binding any later (e.g. inside the
   * withHttpWebSocketApp late-binding segment) would let the binding-dead
   * signals observe `None` and silently skip half the revocation.
   */
  private[neblink] val portalDeps: Option[PortalTunnelDeps] = portalHost.map { faces =>
    // Stage C: arm the tunnel-origin mark BEFORE any bridge can serve — this
    // assembly segment runs at construction (before connect() and before any
    // PortalTunnelBridge exists), so the bridge always injects a live value
    // and GatewayAuth's origin gate never sees the unarmed state in prod.
    nebflow.shared.TunnelOriginMark.arm()
    val deps = new PortalTunnelDeps(
      faces = faces,
      consoleTickets = ConsoleTicketStore.unsafeCreate(),
      bindingState = PortalBindingState.unsafeCreate(),
      identity = neblinkService.identity,
      revokeNfTickets = nfTicketRevocation.getOrElse(IO.pure(0))
    )
    faces.bindConsoleTicketFace(
      PortalHostFaces.ConsoleTicketFace(
        isUnbound = deps.bindingState.isUnbound,
        verifyAndConsume = token => deps.consoleTickets.verifyAndConsume(token),
        // Break-glass mint: origin=local-ticket, binding gate EXEMPT (the
        // escape face works in the revoked state), shared budget enforced.
        mintLocalTicket = deps.mintConsoleTicket(ConsoleTicketStore.LocalAccountSub, ConsoleTicketStore.OriginLocalTicket, enforceBindingGate = false)
      )
    )
    faces.bindSessionRevocation(sessionRevocation.getOrElse(IO.pure(0)))
    deps
  }

  /** Judgment-table rows 1/2: the host binding is in the terminal (revoked) state. */
  private def bindingTerminal: IO[Boolean] =
    portalDeps.fold(IO.pure(false))(_.bindingState.isUnbound)

  /**
   * Terminal probe cadence (test seam over [[NeblinkRelayTunnel.TerminalProbeNap]]):
   * production reads the constant; the terminal-loop spec shortens it so the
   * dial-while-terminal behavior is observable without a 10-minute wait.
   */
  @volatile private[neblink] var terminalProbeNap: scala.concurrent.duration.FiniteDuration =
    NeblinkRelayTunnel.TerminalProbeNap

  /**
   * Anti-loop counter: consecutive upgrade failures since the last successful
   * connect. Drives shouldHealAuthFailure.
   */
  private val authFailStreak = new AtomicInteger(0)

  /**
   * clientconn item 1: consecutive SHORT-LIVED connections (each dropped
   * before `StableConnectionMs`). Drives the stability gate of the backoff
   * reset — the first short-lived drop retries immediately (pre-fix speed for
   * a transient blip); a repeated streak means a flap loop and escalates.
   */
  private val shortLivedStreak = new AtomicInteger(0)

  /** When the last self-heal re-login was attempted (0 = never). */
  @volatile private var lastHealAtMs = 0L

  /**
   * 踢下线停摆（案 B 客户端腿，2026-09-14 作者裁定 17:07；零 wire 新增）。
   *
   * 服务端在「同 (deviceId, network) 有新会话」时主动断开本隧道并推 `disconnect`
   * 帧（`relay.rs disconnect_device`）。修前这里只打一行 debug，重连行为「pending
   * product decision」（见下方 listener 注释）⇒ 两端各自无屏障地自动重连/重登录，
   * 同账号双实例会互踢成乒乓（`NeblinkClient.reloginGate` / `HealCooldownMs` 只
   * 覆盖**单实例内**）。
   *
   * 现语义：收到 `disconnect` ⇒ 记下时刻 + **停摆**（不再自动重连、不再自动重注册），
   * 直到**用户显式再登录**（`resumeAfterUserLogin`，唯一调用点 =
   * `NeblinkEnrollment.persist(explicitUserAction = true)`）。
   *
   * ★ r2 订正（2026-09-14，复核位判词 fail 的唯一失败点）：停摆位**不在本类里**了 ——
   * 它搬到了 `NeblinkService`（`markKickParked` / `kickParked` / `clearKickPark`）。
   * 理由：r1 把停摆位关在隧道内，而**自动重注册腿根本不经过隧道**（心跳 401 →
   * `NeblinkClient.doLogin` → `LogtoSilentRelogin` → `register`，且 register 在
   * persist **之前**）⇒ 隧道腿堵住、重注册腿照样起（r1 实测踢后 ~24s 仍有
   * `POST /api/device/register` 200）。现在本类只做**停摆位的读/写方之一**：
   * 写 = `noteServerDisconnect`，读 = `connectLoop`；`NeblinkClient` /
   * `LogtoSilentRelogin` 读同一真值（register 之前的那道门在后者里）。
   *
   * 范围（诚实边界）：停摆是**进程内**状态——重启进程（boot client 用存储的
   * deviceToken 走 session 交换）会重新入网（该 token 若已被服务端作废则 401，
   * **不会**重注册：register 只由 `LogtoSilentRelogin` 发，而它被同一停摆位挡住）。
   * 跨实例语义 = **每个被踢实例各自停摆**，互踢链因此断掉（被踢方不再自动反踢）；
   * 没有任何中央协调、也没有 wire 协商。0 = 未被踢。
   */

  /**
   * Test/status seam: 本隧道是否处于「被服务端踢下线后的停摆态」。真值源 =
   * `NeblinkService`（跨腿可读面）。
   */
  def signedOutElsewhereAt: Long = neblinkService.kickParkedAtMs
  def parkedAfterKick: Boolean = neblinkService.kickParked

  /**
   * Server-forced teardown（`disconnect` 帧）落地：记录 + 被动提示 + 停摆。
   *
   * 零 wire 新增：帧本身不加字段、不改语义（listener 仍只按既有 `type` 分派），
   * 提示文案由客户端本地下定。
   */
  private[neblink] def noteServerDisconnect(): IO[Unit] =
    IO(neblinkService.markKickParked()) *> logger.warn(
      "Relay tunnel: server sent Disconnect — this device was signed in elsewhere; " +
        "auto-reconnect/re-register is parked until an explicit user login"
    )

  /** 用户显式再登录（唯一合法解除停摆的动作）。幂等；带上一行可见日志。 */
  private[neblink] def resumeAfterUserLogin(): IO[Unit] =
    IO(neblinkService.clearKickPark()).flatMap { wasParked =>
      if wasParked then
        logger.info("Relay tunnel: explicit user login — kick park lifted, reconnecting") *> signalWake()
      else IO.unit
    }

  /**
   * **凭据证据式**解除停摆后的隧道腿（kaiauth 修法批 ①配套，2026-09-16）。
   *
   * 与 [[resumeAfterUserLogin]] 的差别**只在到达路径**：本腿由「新凭据已铸成且经一次
   * 成功交换证明有效」触发（调用方 = `NeblinkService.liftKickParkAfterProvenCredential`，
   * 其唯一调用面 = `NeblinkEnrollment.persistImpl` 的证明步骤），**不是**用户动作 ⇒
   * 日志字样不得混用（归因必须可判别：现场读日志要能一眼分出「人登的」还是
   * 「自动证明解除的」）。停摆位的清除由 `NeblinkService` 统一执行；本方法只负责
   * 掐断当前这一睡（`signalWake`），让 connectLoop 下一轮立即复核停摆位。
   */
  private[neblink] def wakeAfterCredentialProven(): IO[Unit] =
    logger.info(
      "Relay tunnel: kick park lifted by a proven fresh credential — reconnecting"
    ) *> signalWake()

  /**
   * Check if the relay tunnel is currently connected (for status reporting).
   *
   * ①-2 语义诚实化（2026-09-12 波3，方案 §2.1 ①opt-A1 / §6.2 ①-2）：修前
   * `alive` 单点——它只在 `closed.get` 返回之后才被清假（:313-317），而闩在
   * 「abort 不回调 Listener」的僵尸态里永不返回（E3 探针复现）⇒ 通道死了 4 小时
   * 而 `/api/neblink/status` 仍报 `relay.available:true`（E4 假阳性）。
   * 现语义 = **连线在册 且 最近一次入向帧（hbzombie-src 起 = 任意帧，修前仅 pong）
   * 在存活窗（`LivenessTimeoutMs`）内**——判据与心跳的僵尸判据同源（同一个
   * `LivenessTimeoutMs`，不新增第二个阈值）。消费面：
   * status 端点的 `relay.available`（含 per-peer reachable 提示）与
   * `RemoteExecutor` 的 P2P/relay 选路（僵尸隧道不再被当作可用 relay）。
   */
  def isAlive: Boolean =
    alive.get() && (System.currentTimeMillis() - lastPong.get()) < NeblinkRelayTunnel.LivenessTimeoutMs

  /**
   * F7: last upgrade auth rejection (status code + self-heal outcome).
   * Surfaces on /neblink/status as an INDEPENDENT "auth rejected" state —
   * "tunnel dead" alone hides whether we are waiting on a 403 (our session was
   * kicked → self-healable) or on a 5xx (server side).
   */
  def authStatus: Option[TunnelAuthStatus] = lastAuthRejection

  /** Test seam: is the reconnect loop still running (false after stop())? */
  private[neblink] def isRunning: Boolean = running.get()

  /**
   * Test seam: how many connection-loop chains have been spawned. Two
   * `ensure()` calls must spawn ONE chain (no double tunnel) — asserting the
   * spawn counter is deterministic, unlike inferring it from reconnect
   * attempt counters.
   */
  private val loopSpawns = new AtomicInteger(0)
  private[neblink] def loopSpawnCount: Int = loopSpawns.get()

  /**
   * N3 log-voice latch for the "configured but no session token" state, which
   * is a STEADY state since logout keeps the server URL: first occurrence
   * INFO (visibility), the rest DEBUG (no permanent INFO spam). Reset on
   * every successful connect, so a later outage gets a visible first line.
   */
  private val noTokenInfoLogged = new AtomicBoolean(false)

  /**
   * Wake latch (clientconn item 1): `start()` / `ensure()` completes it, which
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
   * the wake is redundant rather than lost.
   */
  private val wakeLatch = new AtomicReference[Deferred[IO, Unit]](null)

  /**
   * A wake that has not been consumed yet. `nap` consumes it at entry: the
   * first wait after a wake does NOT sleep (the wake's intent is "re-evaluate
   * now — config / token / connection state just changed"). This is what makes
   * `ensure()` effective on a two-sleep iteration (retry delay + idle wait):
   * cutting the in-flight nap alone would still leave the second sleep ahead.
   */
  private val wakePending = new AtomicBoolean(false)

  /**
   * Interruptible sleep: returns when `d` elapses OR `signalWake()` fires.
   * A pending (unconsumed) wake short-circuits the wait entirely.
   */
  private[neblink] def nap(d: FiniteDuration): IO[Unit] =
    if wakePending.getAndSet(false) then IO.unit
    else if d.toMillis <= 0L then IO.unit
    else
      Deferred[IO, Unit].flatMap { latch =>
        IO(wakeLatch.set(latch)) *>
          IO.race(latch.get, IO.sleep(d)).void <*
          IO(wakeLatch.compareAndSet(latch, null)).void
      }

  /**
   * Cut the current nap short (and arm the "do not sleep next time" flag).
   * Idempotent: a second call while nothing is sleeping only sets the flag.
   */
  private[neblink] def signalWake(): IO[Unit] =
    IO(wakePending.set(true)) *> IO(wakeLatch.get()).flatMap {
      case null => IO.unit
      case latch => latch.complete(()).void
    }

  /** Test seam: is a nap currently armed (i.e. the loop is sleeping)? */
  private[neblink] def napArmed: Boolean = wakeLatch.get() != null

  /**
   * Spawn one connection loop chain (fire-and-forget; the loop ends on
   * running == false).
   */
  private def spawnLoop(): IO[Unit] =
    IO(loopSpawns.incrementAndGet()).void *> IO {
      dispatcher.unsafeRunAndForget(
        connectLoop(0).handleErrorWith(e =>
          logger.warn(s"Relay tunnel loop terminated unexpectedly (${e.getClass.getSimpleName})")
        )
      )
    }

  /**
   * Start the relay tunnel connection loop (boot entry). Returns immediately —
   * the loop itself runs in the background until stop().
   */
  def connect(): IO[Unit] =
    logger.info("Starting relay tunnel to NebLink Server") *>
      IO(running.set(true)) *> spawnLoop()

  /**
   * Idempotent (re)start — revives a tunnel that `stop()` (logout) shut down.
   *
   * Single-flight via CAS: concurrent callers (enrollment hot-swap + boot)
   * cannot spawn a second loop, and there is no bare check-then-act on a
   * shared flag. `running` starts true (the tunnel is constructed running),
   * so `ensure()` before any stop() is a no-op.
   */
  def start(): IO[Unit] =
    IO(running.compareAndSet(false, true)).flatMap {
      case false => signalWake() // already running — the only useful semantics left
      case true => logger.info("Relay tunnel (re)starting after stop()") *> spawnLoop()
    }

  /**
   * Alias of `start()` — the "make sure the tunnel runs" signal used by the
   * enrollment hot-swap path (`NeblinkService.ensureRelayTunnel`).
   */
  def ensure(): IO[Unit] = start()

  /**
   * Gracefully stop the tunnel.
   *
   * 🔴 relay-honest 批（2026-10-20，link-server 诊断「登出→重登录后 relay 隧道
   * 无重启触发器」②的一半）：修前本方法**零日志** —— 登出第 2 步走到这里，引擎日志
   * 里没有任何 disconnect 行，状态翻转完全不可见（诊断原话：「引擎日志零 disconnect
   * 行」）。现在断开必须有可见行：与既有断开族同措辞（`Relay tunnel disconnected …`，
   * INFO 级 = 与 `connectConfigured` 的掉线行同档），机器可 grep（`tunnel stop`）。
   * 复活行（`(re)starting after stop()`）由 `start()` 负责，配对可查。
   */
  def stop(): IO[Unit] =
    logger.info("Relay tunnel disconnected: stop() — tunnel torn down (logout local teardown); " +
      "status no longer reports this tunnel connected") *>
      signalWake() *> IO.blocking {
        running.set(false)
        alive.set(false)
        heartbeat.foreach { hb =>
          try hb.shutdownNow()
          catch case _: Exception => ()
        }
        wsRef.foreach { w =>
          try w.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown")
          catch case _: Exception => ()
        }
      }

  // ===== Connection loop =====

  /**
   * Live NebLink Server URL, resolved on EVERY attempt from the config ref
   * (precedent: `RestApiRoutes.neblinkServerUrl` / `relayTunnelOpt`). Never a
   * constructor-time snapshot: the tunnel object is installed once at boot and
   * may be re-pointed by enrollment without being rebuilt.
   */
  private def currentServerUrl: IO[Option[String]] =
    neblinkService.neblinkConfig.map(_.neblinkServer.map(_.url))

  private def connectLoop(attempt: Int): IO[Unit] =
    if !running.get() then IO.unit
    else if parkedAfterKick then
      // 踢下线停摆（案 B 客户端腿）：不重连、不重注册。零连接尝试 ⇒ 服务端看不到
      // 任何升级/登录流量（判据见 KickParkNap 注释：停摆期的重连尝试计数不增长）。
      // 每次睡醒都重新判定（`resumeAfterUserLogin` 会连睡一起掐断 ⇒ 用户再登录即时恢复）。
      logger
        .debug(
          s"Relay tunnel: parked after a server-forced sign-out (${(System.currentTimeMillis() - signedOutElsewhereAt) / 1000}s) — " +
            "waiting for an explicit user login"
        )
        .flatMap(_ => nap(NeblinkRelayTunnel.KickParkNap) *> connectLoop(attempt))
    else
      // 🔴 腿 A 活动面闸（2026-09-22 作者二择裁定 A 腿）——**第四条**自动外发腿。
      //
      // 为什么它必须入闸：装配点 `GatewayMain.scala:990` 的 `relayTunnel.connect()`
      // 是**无条件**的（与登录态无关），而登出**刻意保留** `neblinkServer`（含
      // `deviceToken`）以便「下次登录」（`RestApiRoutes.performLocalLogout` 第 5 步
      // 注释：keep neblinkServer address for next login）⇒ 登出后重启，boot 客户端
      // 仍带着有效 deviceToken，隧道一旦拿到会话就重拨、把本机重新挂回服务端。
      // 「enabled=false 不得跨重启」这条判词若不覆盖本腿，就会从这条缝里漏掉。
      //
      // 逐拍**现读**（不是启动期快照）⇒ 重新登录（`NeblinkEnrollment.scala:201`
      // 写 `enabled = true`）后本闸立刻张开；`ensureRelayTunnel` 的 `signalWake()`
      // 还会掐断这一段空转 ⇒ 无需重启。空转形态 = **不拨号**（零升级尝试、零注册
      // 流量），与既有「未配置 server URL」分支同族的安静梯子。
      neblinkService.activityEnabled.flatMap {
        case false => idleWhileDisabled(attempt)
        case true =>
          bindingTerminal.flatMap {
            case true  => probeWhileTerminal(attempt)
            case false => connectConfigured(attempt)
          }
      }

  /**
   * 「未启用」空转腿（腿 A 闸的 false 分支）。与 `currentServerUrl = None` 分支
   * 同族的安静节拍：DEBUG（登出后的稳态，禁 INFO 噪声）+ 可被 `ensure()`/`stop()`
   * 掐断的 `nap`（用户再登录即时恢复）。
   */
  private def idleWhileDisabled(attempt: Int): IO[Unit] =
    val wait = math.min(30L, 1L << math.min(attempt, 4)).seconds
    logger
      .debug(
        s"Relay tunnel: NebLink is disabled (enabled=false) — no dial, retrying the gate in ${wait.toSeconds}s..."
      )
      .flatMap(_ => nap(wait) *> connectLoop(attempt + 1))

  /**
   * Terminal-binding probe leg (judgment table rows 1/2, design §3.5): while
   * the host binding is revoked the tunnel keeps DIALING — the terminal state
   * only replaces the fast reconnect ladder with a low fixed cadence. Each
   * probe is a real upgrade attempt so that (a) the server-side binding
   * gate's 401 terminal body keeps confirming the terminal state (row 2),
   * and (b) a genuine re-bind (binding row back to bound for this host) is
   * detected by the successful upgrade, which clears the terminal state
   * (row 4, in connectOnce) and puts the loop back on the normal ladder.
   * Never a sleep-only loop: without a dial, row 4 would be unreachable and
   * a revoked host would stay dark forever. Interruptible by
   * `ensure()`/`stop()` like every other nap.
   */
  private def probeWhileTerminal(attempt: Int): IO[Unit] =
    logger
      .debug(
        s"Relay tunnel: host binding is terminal (revoked) — dialing the relay at a low " +
          s"cadence (every ${terminalProbeNap.toSeconds}s) until a successful upgrade clears it"
      )
      .flatMap(_ => nap(terminalProbeNap) *> connectConfigured(attempt))

  private def connectConfigured(attempt: Int): IO[Unit] =
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
          else
            NeblinkRelayTunnel.jittered(
              NeblinkRelayTunnel.backoffSeconds(attempt).seconds,
              scala.util.Random.nextDouble()
            )
        nap(pre) *> (if !running.get() then IO.unit
                     else
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
                                       .info(
                                         s"Relay tunnel disconnected after ${heldMs / 1000}s, reconnecting (step $next, short-lived streak $streakBefore)..."
                                       )
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
    if isTerminalBindingRejection(failure) then
      // Judgment-table row 2 (design §3.5, authoritative signal): 401 with the
      // binding-gate terminal body — the BINDING is dead, not the session, so
      // a re-login is meaningless (and would be a login storm against a state
      // only the portal can restore). Park in the terminal state (persisted)
      // and let the low-frequency probe leg carry the reconnects.
      //
      // Phase 3b (plan §5 row 2): the binding-dead signal now revokes the
      // SAME three faces the bridge's BindingControl(revoked) row revokes —
      // console tickets + relay-mint sessions + nf path-read tickets. Two
      // entries, ONE semantics (v3 :184 "no residual window" — the ceiling
      // it referenced then was 12h, today 30d; immediacy is the invariant).
      for
        _ <- portalDeps.fold(IO.unit)(_.bindingState.markUnbound().void)
        dropped <- portalDeps.fold(IO.pure((0, 0, 0))) { deps =>
          (deps.consoleTickets.revokeAll(), deps.faces.sessionRevocation.getOrElse(IO.pure(0)), deps.revokeNfTickets)
            .mapN((tickets, sessions, nfTickets) => (tickets, sessions, nfTickets))
        }
        (ticketsDropped, sessionsDropped, nfTicketsDropped) = dropped
        _ <- IO {
          lastAuthRejection = Some(
            TunnelAuthStatus(
              failure.statusCode.getOrElse(401),
              nowMs,
              healAttempted = false,
              healSucceeded = false,
              active = true
            )
          )
        }
        _ <- logger.warn(
          s"$head — TERMINAL binding rejection (binding_revoked): no session self-heal, " +
            s"probing every ${NeblinkRelayTunnel.TerminalProbeNap.toSeconds}s until the binding is restored; " +
            s"revoked consoleTickets=$ticketsDropped sessions=$sessionsDropped nfTickets=$nfTicketsDropped"
        )
        _ <- if running.get() then connectLoop(attempt + 1) else IO.unit
      yield ()
    else if !shouldHealAuthFailure(streak, nowMs, lastHealAtMs) then
      IO {
        lastAuthRejection = Some(
          TunnelAuthStatus(
            failure.statusCode.getOrElse(0),
            nowMs,
            healAttempted = false,
            healSucceeded = false,
            active = true
          )
        )
      } *>
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
        _ <- IO {
          lastAuthRejection = Some(
            TunnelAuthStatus(
              failure.statusCode.getOrElse(0),
              nowMs,
              healAttempted = true,
              healSucceeded = healed,
              active = true
            )
          )
        }
        // The refreshed token is picked up by tokenGetter on the next attempt —
        // it reads the live client, never a cached/snapshotted token.
        _ <- if healed then connectLoop(0) else if running.get() then connectLoop(attempt + 1) else IO.unit
      yield ()

    end if

  end handleAuthRejection

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
    // Judgment-table row 1 short-circuit (design §3.5): while the host binding
    // is terminal (revoked), a re-login cannot fix anything — the session is
    // not what the server is rejecting. Never re-login in the terminal state.
    bindingTerminal.flatMap {
      case true =>
        logger
          .warn(
            "Relay tunnel session self-heal SKIPPED: host binding is terminal (revoked) — " +
              "re-login is pointless until the portal restores the binding"
          )
          .as(false)
      case false =>
        healSessionLive
    }

  private def healSessionLive: IO[Boolean] =
    IO(neblinkService.relayClientOpt).flatMap {
      case None =>
        logger.warn("Relay tunnel session self-heal: no live NebLink client — reporting only").as(false)
      case Some(client) =>
        client
          .ensureFreshSession("relay-upgrade-auth-reject")
          .handleErrorWith(e =>
            logger.warn(s"Relay tunnel session self-heal errored (${e.getClass.getSimpleName})").as(false)
          )
    }

  /** Establish a single WS connection; returns when the connection ends. */
  private def connectOnce(id: DeviceIdentity, url: String, token: String): IO[Unit] =
    Deferred[IO, Unit].flatMap { closed =>
      // One portal face per connection — created BEFORE the socket so the
      // listener can carry it from the very first frame it delivers.
      val portalConn = new PortalConnection(dispatcher)
      IO.blocking {
        val wsUri = buildRelayWsUri(url, id)
        val listener = new RelayWsListener(this, closed, dispatcher, portalConn)
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
        // 🔴 relay-honest 批（2026-10-20）：**stop-during-upgrade 诚实闸**。
        //
        // 修前这条竞态的真实形态：`stop()`（登出第 2 步）落在 101 升级在飞的窗口里
        // ⇒ 它的 `sendClose` 打在**旧 wsRef**（此刻还是 None/旧 socket）上，而下面的
        // 装配块照跑 —— `alive=true` + 新心跳挂上 stop() 永远够不着的**新** socket
        // ⇒ 登出之后：状态面继续报「连着」（isAlive 靠这条 socket 的 pong 自证），
        // socket 在服务端继续挂着，且日志里**再无任何一行**（诊断的「状态面还显示
        // 连着=假象」的机制根源）。
        //
        // 现语义：装配前现读 `running` —— stop() 已落 ⇒ 立即关闭这只新 socket、
        // 补完闩（幂等补法同 `armZombieLatchWatchdog`）、**零装配**（不写 wsRef /
        // alive / 心跳 / portal 桥），循环经既有 `running==false` 分支自然退场，
        // 断开行由 `stop()` 自己的 disconnect 行承接。残余的纳秒级窗口由
        // `isAlive` 的 9s 存活窗兜底（stop() 已停心跳 ⇒ 无新 pong ⇒ 最多 9s 自愈）。
        if !running.get() then
          try w.sendClose(WebSocket.NORMAL_CLOSURE, "stopped during upgrade")
          catch case _: Exception => ()
          dispatcher.unsafeRunAndForget(
            closed.complete(()).void.handleErrorWith(e =>
              logger.debug(s"stop-during-upgrade latch complete errored (${e.getClass.getSimpleName})")
            )
          )
          logger.infoSync(
            "Relay tunnel: stop() landed during the upgrade — closing the fresh socket, nothing installed"
          )
          ()
        else
          // Phase 3a: one portal bridge per relay connection — the per-requestId
          // body channels and local WS cycles it holds die with this socket.
          // (Tiny window before this bind: frames arriving between the upgrade
          // completing and this line drop with an explicit "bridge not wired"
          // WARN; the server never sends portal frames in that window — they
          // require a mint/binding cycle first.)
          portalDeps.foreach(deps => portalConn.bind(new PortalTunnelBridge(deps, new RelayFrameSender(w))))
          wsRef = Some(w)
          alive.set(true)
          lastPong.set(System.currentTimeMillis())
          lastPingSentAt.set(System.currentTimeMillis())
          startHeartbeat(w, closed)
          // Anti-loop reset + F7: a successful upgrade means the credential
          // problem is over — the next rejection gets a fresh heal budget and the
          // status stops claiming "auth rejected" (code/time stay as history).
          authFailStreak.set(0)
          lastAuthRejection = lastAuthRejection.map(_.copy(active = false))
          // N3: re-arm the first-line INFO voice — a later outage (e.g. after
          // logout) gets one visible line instead of silence.
          noTokenInfoLogged.set(false)
          // Judgment-table row 4 (design §3.5): a successful upgrade (101, no
          // terminal code) clears the terminal binding state and re-enters the
          // network. Pre-gate caveat: until the server binding gate (design
          // §2.6) is deployed, an unbound device's upgrade still succeeds, so
          // the first reconnect clears — deployment-order hard constraint puts
          // the gate before/with this row.
          portalDeps.foreach { deps =>
            dispatcher.unsafeRunAndForget(
              deps.bindingState.clearTerminal().flatMap { cleared =>
                IO.whenA(cleared)(
                  logger.info(
                    "Relay tunnel: successful upgrade cleared the terminal binding state — re-entering the network"
                  )
                )
              }
            )
          }
          logger.infoSync(s"Relay tunnel connected: $wsUri")
          // Self-driven portal lifecycle (staging fix): the mailbox consumer
          // starts and the teardown arms HERE, through the dispatcher — not off
          // the connectOnce chain tail, which stalls across reconnect rounds
          // when the chain runs as a dispatcher task (repro: consumer seconds
          // late on the first connection, never on the second — every answer
          // then left through a stale, output-closed sender).
          portalConn.armLifecycle(closed)
          ()
      } *> closed.get <* IO.blocking {
          alive.set(false)
          stopHeartbeat()
          wsRef = None
        }
    }

  // ===== Relay request handling (called from WS listener thread) =====

  /** Execute a relay_request locally and send the response back over WS. */
  private[neblink] def handleRelayRequest(msg: Json, ws: WebSocket): IO[Unit] =
    // Design §3.5 (revoked processing): while the host binding is terminal the
    // relay TOOL face stops serving. The wire shape is unchanged (the frame
    // still gets exactly one relay_response echoing its requestId) — only the
    // outcome differs: error, zero local work. Zero-regression: this branch is
    // reachable only AFTER a terminal state was entered through the new
    // BindingControl / gate-body paths; a bound host takes the live path
    // byte-for-byte as before.
    bindingTerminal.flatMap {
      case true =>
        val requestId = msg.hcursor.downField("requestId").as[String].getOrElse("")
        logger.warn(
          "Relay request refused: host binding is terminal (revoked) — the relay tool face is retired"
        ) *> IO.blocking {
          val resp = Json.obj(
            "type" -> "relay_response".asJson,
            "requestId" -> requestId.asJson,
            "output" -> "".asJson,
            "error" -> "Host binding revoked: the relay tool face is retired on this host".asJson
          )
          try
            ws.sendText(resp.noSpaces, true)
            ()
          catch case _: Exception => ()
        }
      case false => handleRelayRequestLive(msg, ws)
    }

  private def handleRelayRequestLive(msg: Json, ws: WebSocket): IO[Unit] =
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
            case Left(err) => ("", err)
          }
        case "Notify" =>
          val payload = params("payload").getOrElse(Json.Null)
          // P6 诊断探针（delegate 断点追查，腿3 定案后）②分发层：relay_request 的
          // Notify 载荷若为 delegate-spawn，本行是「隧道收帧 → DeviceDelegate.handleSpawn」
          // 链上隧道侧的唯一分发口（下一跳 = neblinkService.handleDataMessage，再下跳即
          // spawn handler 真正入口，其既有 INFO「delegate-spawn RECEIVED」与本行对照）。
          // 选址在 DeviceDelegate.scala 之外（作者 WIP 红线）的等价调用点。DEBUG 级、
          // 只读载荷摘要（spawnId/from/task 截断 80 字符防刷屏），分发语义零改动。
          // P6 步2 修复（2026-10-14）：原 `logger.debug` 返回 IO[Unit]，在语句位置被
          // 丢弃 ⇒ 探针从未输出（步2 复现实证）。改用 debugSync 真打。
          if payload.hcursor.downField("kind").as[String].getOrElse("") ==
              nebflow.core.delegate.DeviceDelegate.KindSpawn
          then
            logger.debugSync(
              s"P6 probe: spawn dispatched " +
                s"handler=DeviceDelegate.handleSpawn via=NeblinkService.handleDataMessage " +
                s"request_id=$requestId " +
                s"spawnId=${payload.hcursor.downField("spawnId").as[String].getOrElse("<none>")} " +
                s"from=${payload.hcursor.downField("fromDeviceId").as[String].getOrElse("<none>")} " +
                s"taskHead=${payload.hcursor.downField("task").as[String].getOrElse("").take(80)}"
            )
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
              // 跨设备批（2026-10-25）：Bash 下发的 `cwd`（设备盘 workspace，发送侧
              // 会话默认设备上下文注入）→ 对端会话初始 cwd——「派发任务的命令 cwd
              // 指向设备 workspace」。目录在对端不存在 ⇒ BashTool resolveCwdOrFail
              // fail-closed（与本地座椅同语义）。P2P 直连腿同款（NeblinkRoutes）。
              val cwdOpt = params("cwd").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)
              val ctx = ToolContext(projectRoot = projectRoot, isRemoteExec = true, sessionCwd = cwdOpt)
              tool.call(params, ctx).attempt.map {
                case Right(Right(result)) => (result, "")
                case Right(Left(err)) => ("", err.message)
                case Left(e) => ("", s"Tool execution failed: ${e.getMessage}")
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

    end for

  end handleRelayRequestLive

  /**
   * Called by the WS listener on ANY inbound delivery (hbzombie-src 起不再限于
   * pong：任意入向帧都是「对端活着」的直接证据，与服务端同口径）——refreshes
   * heartbeat liveness. 字段名 `lastPong` 保留（读数点不动），语义 = 最近入向帧时刻。
   */
  private[neblink] def updateLastPong(): Unit =
    lastPong.set(System.currentTimeMillis())

  /**
   * D-B ack 生产者（2026-09-13 好友推送修复批）——**帧形状冻结**，唯一编码点：
   *   `{"type":"ack","eventId":"<eventId>"}`
   *
   * 与 neblink-server 的对应关系（现场读服务端源码，非猜）：
   *   · `ClientToServer::Ack { #[serde(rename="eventId")] event_id }`，枚举
   *     `#[serde(tag="type", rename_all="snake_case")]` ⇒ 线上就是 `"ack"` + `eventId`
   *     （`src/relay.rs:77-94`）；
   *   · 收帧分支 `Ok(ClientToServer::Ack { event_id })`（`src/relay.rs:630-651`）：
   *     `friend-evt-<rowId>` ⇒ 推进该设备的 durable 重放游标；
   *     `message-<id>` ⇒ 写 `sent` 回执（`message_receipt_message_id`，`:141-145`）
   *     —— 后者正是本批的靶（消息推送的送达确证）。
   *   · 不可解析的 eventId 一律被服务端忽略（幂等；伪 id 也跳不了真帧）⇒ 本侧
   *     发送失败/多发的代价是「零」，故整条腿 **best-effort，绝不抛出**：
   *     它挂在 friend_event 处理链的尾部，任何异常都不得影响消费/广播（那个方向
   *     才是真正会丢消息的方向）。
   *
   * 语义边界（🔴 本侧只做客户端半边）：ack = 「本设备已**持久处理**该事件」，
   * 不是「本设备已收帧」。因此**重复帧也要 ack**（见 `FriendService.onFriendEvent`）：
   * 首帧的 ack 若在链路上丢了，服务端会在下次隧道注册时重放，客户端按 eventId
   * 去重后若不再 ack，重放将**永远**退不掉（at-least-once 的活锁）。服务端侧
   * 的账本/重放（S2 的其余半边）由 neblink-server 另案交付，本仓不发单、不改其码。
   *
   * 可见性 = **public**（与 `connect` / `ensure` / `statusJson` 同档）：唯一调用方是
   * `GatewayMain` 的 ack 装配缝（包 `nebflow.gateway`），`private[neblink]` 够不着；
   * 帧编码仍只此一处（不加第二实现）。
   *
   * 🔴 **F3（2026-09-18 回执诚实性批，作者裁示「回执诚实性修、单列小批」）**：返回值
   * `IO[Unit]` → `IO[AckOutcome]`。修前「无 live socket」只落一行 DEBUG 后**返回成功**
   * （既无异常、也无判别值），装配缝又把「隧道对象不在册」也吞成 `IO.unit` ⇒ 调用方
   * （`DeviceMailInbox.sendReceipt`）照打 `ack sent to the server` + 审计 `ack-sent`
   * ——**线上零帧却报已回执**（缺陷 B 的放大因，`mailreplay-recon` §1-④/§2-⑦ 逐字）。
   * 现在三态可判别（[[AckOutcome]]），「没发出」**不可能**被读成「已发出」。
   */
  def sendAck(eventId: String): IO[AckOutcome] =
    val frame = NeblinkRelayTunnel.ackFrame(eventId)
    wsRef match
      case None =>
        logger
          .debug(s"ack-not-sent reason=no_live_socket: no registered relay socket for $eventId")
          .as(AckOutcome.NoLiveSocket)
      case Some(w) if w.isOutputClosed() =>
        // 同族第二种形态（同一判据的收窄）：socket 对象在册但**出向已关**（半关 /
        // 正在关闭）⇒ 帧同样上不了线。必须自己判：JDK 的 `WebSocket.sendText` 把
        // 写失败放进**返回的 future** 而不抛（修前那个 try/catch 因此是死代码，
        // 连真写失败都吞成成功）。
        logger
          .debug(s"ack-not-sent reason=no_live_socket: relay socket output closed for $eventId")
          .as(AckOutcome.NoLiveSocket)
      case Some(w) =>
        IO.blocking {
          try
            val fut = w.sendText(frame.noSpaces, true)
            // 异步失败面（写进 socket 缓冲后 TCP 中途断）：只能留痕，不改已返回的
            // 结局——结局的语义边界 = 「帧交给了在册 socket 且其出向未关」（线上
            // 有帧的实证见 `DeviceMailAckHonestySpec` 的真 RFC 6455 夹具直读）。
            fut.whenComplete { (_, err) =>
              if err != null then
                logger.debugSync(
                  s"ack frame write reported async failure for $eventId: ${NeblinkRelayTunnel.describeErr(err)}"
                )
            }
            AckOutcome.Sent
          catch
            case e: Exception =>
              val reason = NeblinkRelayTunnel.describeErr(e)
              logger.debugSync(s"ack send failed for $eventId: $reason")
              AckOutcome.SendFailed(reason)
        }.handleErrorWith(e =>
          logger
            .debug(s"ack send error for $eventId: ${e.getMessage}")
            .as(AckOutcome.SendFailed(NeblinkRelayTunnel.describeErr(e)))
        )

    end match

  end sendAck

  /**
   * D6 binding-transfer confirmation report (phase 3c plan §8 component ①):
   * push the freshly minted 6-digit confirm code up the relay control plane
   * so the server hangs it as the pending confirmation (plan R-8 recommended
   * flow — the portal compares against the server's copy). Frame shape has
   * ONE definition point: [[NeblinkRelayTunnel.bindConfirmFrame]].
   *
   * Outcome semantics are the SAME three states as [[sendAck]] (read its F3
   * note): `Sent` = the frame was handed to the registered socket with its
   * output direction open — NOT "the server accepted it", and NOT "the code
   * became pending" (no ack exists on this face). ANY server without the
   * confirmation-staging face drops the unknown `bind_confirm` type with a
   * warn log and keeps the tunnel (server `relay_legacy_text` returns true
   * on the parse-error branch): a pre-D6 server, and equally the
   * intermediate state where the D6 GUARD is live but the staging face is
   * still deferred (portal wire contract v1.2 §3.3 → phase-5 freeze). The
   * uplink is wire-safe in every such state, but in them the printed code
   * can never match — the caller must treat `NoLiveSocket` / `SendFailed`
   * as "the code never became pending" (never display it), and the CLI copy
   * names the staging-face dependency instead of promising portal
   * acceptance.
   */
  def sendBindConfirm(code: String, ttlSec: Long): IO[AckOutcome] =
    val frame = NeblinkRelayTunnel.bindConfirmFrame(code, ttlSec)
    wsRef match
      case None =>
        logger
          .debug("bind-confirm-not-sent reason=no_live_socket: no registered relay socket")
          .as(AckOutcome.NoLiveSocket)
      case Some(w) if w.isOutputClosed() =>
        logger
          .debug("bind-confirm-not-sent reason=no_live_socket: relay socket output closed")
          .as(AckOutcome.NoLiveSocket)
      case Some(w) =>
        IO.blocking {
          try
            val fut = w.sendText(frame.noSpaces, true)
            fut.whenComplete { (_, err) =>
              if err != null then
                logger.debugSync(
                  s"bind_confirm frame write reported async failure: ${NeblinkRelayTunnel.describeErr(err)}"
                )
            }
            AckOutcome.Sent
          catch
            case e: Exception =>
              val reason = NeblinkRelayTunnel.describeErr(e)
              logger.debugSync(s"bind_confirm send failed: $reason")
              AckOutcome.SendFailed(reason)
        }.handleErrorWith(e =>
          logger
            .debug(s"bind_confirm send error: ${e.getMessage}")
            .as(AckOutcome.SendFailed(NeblinkRelayTunnel.describeErr(e)))
        )

    end match

  end sendBindConfirm

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
    val deviceId = hc
      .downField("deviceId")
      .as[String]
      .orElse(hc.downField("device_id").as[String])
      .getOrElse("")
    val online = hc
      .downField("online")
      .as[Boolean]
      .toOption
      .orElse(hc.downField("status").as[String].toOption.map(_.equalsIgnoreCase("online")))
      .getOrElse(false)
    if deviceId.isEmpty then logger.debug("DeviceStatusUpdate frame without deviceId — ignored")
    else neblinkService.applyServerPeerStatus(deviceId, online)
  end handleDeviceStatusUpdate

  /**
   * Phase 3a (design §3.1): dispatch one decoded downlink portal frame into
   * THE CONNECTION'S OWN holder — the listener carries its connection's
   * [[PortalConnection]], so dispatch can never cross connections (reconnect
   * rebinding is by construction, not by field discipline). Called from the
   * WS listener thread — never blocks.
   */
  private[neblink] def dispatchPortalFrame(frame: PortalWireCodec.PortalFrame, conn: PortalConnection): Unit =
    frame match
      case _: PortalWireCodec.MigrationChunk =>
        // Explicit not-implemented case (design §3.1): the migration relay face
        // belongs to phase 5. Dropped HERE (dispatch level), independent of
        // bridge wiring, so it never falls into the unknown-type branch —
        // that would misreport contract drift.
        logger.warnSync("MigrationChunk frame dropped: migration relay face is not implemented in this phase")
      case _ =>
        if conn.hasBridge then conn.dispatch(frame)
        else
          logger.warnSync(
            "portal frame dropped: no portal bridge wired for this connection " +
              s"branch=P2 type=${frame.getClass.getSimpleName}"
          )

  /**
   * Judgment-table row 2 discriminator (design §3.5 / §2.6): a 401 upgrade
   * rejection whose body carries the binding gate's terminal marker
   * (`binding_revoked` + `terminal:true`). The body arrives redacted through
   * [[RelayTunnelDiagnostics]] (the marker strings survive redaction — it only
   * masks credential-shaped values). Any other 401/403 keeps the
   * self-healable row-3 behavior.
   */
  private def isTerminalBindingRejection(failure: RelayTunnelDiagnostics.UpgradeFailure): Boolean =
    failure.statusCode.contains(401) && failure.bodySnippet.exists { body =>
      val b = body.toLowerCase(java.util.Locale.ROOT)
      b.contains("binding_revoked") && b.matches("(?s).*\"terminal\"\\s*:\\s*true.*")
    }

  // ===== Heartbeat =====

  /**
   * ①opt-A1 通道自愈（2026-09-12 波3，方案 §2.1）：`WebSocket.abort()` 只撕
   * socket（服务端看到 FIN），**不回调 Listener 的 onError/onClose**——JDK
   * 实机探针复现（the friendmsg batch fm-realtime-recon probe AbortProbe.java，
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
            val now = System.currentTimeMillis()
            val lastInbound = lastPong.get()
            if now - lastInbound > NeblinkRelayTunnel.LivenessTimeoutMs then
              // Zombie-connection fix (diag-transfer-stuck R2): a half-open TCP
              // connection never delivers sendClose to the peer and the JDK
              // listener's onError/onClose never fire, so `closed` (the
              // connectOnce latch) stayed incomplete and the reconnect loop
              // stalled silently for hours. abort() tears the connection down
              // LOCALLY — the listener fires immediately, connectOnce returns
              // and connectLoop reconnects.
              // 波3（①opt-A1）：该注释里的「listener fires immediately」经探针证伪
              // ——abort 不回调，故此处补看门狗把闩补完（幂等）。
              //
              // hbzombie-src（2026-10-14）：判死前多问一句「我们**问出去过**吗」。
              // 修前只看 `lastPong` 年龄 ⇒ 大流量下 ping 持续撞 JDK 单发契约
              // （bulk 发送恒占在飞位 ⇒ 每拍都被跳过）时，「没等到 pong」根本不是
              // 对端沉默的证据，却照样 abort 一条正在传输的活连接（A4 崩塌的
              // 客户端侧放大器）。现在：只有「上次入向帧之后确有 ping 成功入线、
              // 且已等满至少一拍（复用心跳间隔，不新增阈值轴）仍无任何入向帧」
              // 才判僵尸；否则先补发一问，下一拍再判。
              val askedAt = lastPingSentAt.get()
              val pingOutstanding =
                askedAt > lastInbound && now - askedAt >= NeblinkRelayTunnel.HeartbeatIntervalSec * 1000
              if pingOutstanding then
                logger.infoSync("Relay tunnel heartbeat timeout — aborting zombie connection for reconnect")
                ws.abort()
                armZombieLatchWatchdog(closed)
              else
                sendHeartbeatPing(ws, closed)
            else
              sendHeartbeatPing(ws, closed)
        catch case _: Exception => ()
      },
      NeblinkRelayTunnel.HeartbeatIntervalSec,
      NeblinkRelayTunnel.HeartbeatIntervalSec,
      TimeUnit.SECONDS
    )

  end startHeartbeat

  /**
   * 心跳 ping 的单拍发送（hbzombie-src，2026-10-14 客户端腿同案确认批 D1 修；
   * pump-selfabort-fix，2026-10-15 残余批把**异步失败面**也按真实含义分流）。
   *
   * 修前缺陷（hbzombie-src 之前）：本拍直接 `ws.sendText` 并把**任何**同步异常读成
   * 「socket 已死 ⇒ abort」。但 JDK `WebSocket.sendText` 对真写失败是把异常放进
   * **返回的 future** 而不抛（`sendAck` 的 F3 注）⇒ 修前的 try/catch 对真失败是
   * 死代码、对假阳性百发百中。
   *
   * hbzombie-src D1 的口径按「失败在哪一面浮现」分流：同步异常 + 出向关 ⇒ 真死
   * abort；同步异常 + 出向开 ⇒ 跳拍；入队成功 ⇒ 记 `lastPingSentAt` 并挂 future，
   * **异步失败一律当真死 abort**。
   *
   * pump-selfabort-fix（本批）钉死的事实：D1 的假设在这个 JDK 上不成立——
   * Temurin 23 `WebSocketImpl.sendText` 对「前一发未完成」**从不同步抛**
   * IllegalStateException，而是返回一个**已完成的失败 future**
   * （`failedFuture(new IllegalStateException("Send pending"))`，源码
   * `pendingTextOrBinary.compareAndSet` 失败分支；真 RFC 6455 定向复现
   * （NeblinkRelayTunnelHeartbeatSendSpec 真 socket 契约钉）实证：bulk 在飞时
   * 心跳 sendText **零同步异常**、future 携 `IllegalStateException: Send pending`
   * 失败、**isOutputClosed()==false**（出向完好、连接未死）。于是 bulk 洪峰下
   * 每一拍心跳都落进 D1 的异步分支 ⇒ 误 abort 一条出向完好的活连接 ⇒
   * portal bridge 在飞发送面随撕（`portal http cycle failed: Output closed`
   * 伴生）⇒ 2–5s 自愈重连（A4-R2 残余族，与本批同 jar 指纹双批复现）。
   *
   * 修后口径（**按失败的真实含义分流，不按浮现面**）：
   *   - **入队成功** ⇒ 记 `lastPingSentAt`（僵尸判据的「问出去过」证据）；
   *   - **异步失败 + `IllegalStateException` + 出向开** = 「另一发送在飞」的
   *     饱和/背压面（该 future 上 JDK 只可能放这一种 ISE——sendText/sendBinary
   *     的 Send pending CAS 失败分支；真写失败走 IOException 家族）⇒ **跳拍不撕**：
   *     回滚 `lastPingSentAt`（这一拍**没有**上线，不得伪造 D3 的「问出去过」
   *     前提），不 abort、不闩看门狗。服务端 miss 计数被任意入向帧重置（bulk
   *     帧本身即在重置它），本端僵尸判据以真实入线的 ping 为前提，不会因此误杀；
   *   - **异步失败（其余一切：IOException「Output closed」/ broken pipe /
   *     ISE 但出向已关等）** = 真死面 ⇒ 维持 D1 语义立即 abort + 闩看门狗；
   *   - **同步异常**（其它 JDK 实现（按 javadoc 契约同步抛 ISE）的防御面，
   *     本 JDK 上不可达）：+ `isOutputClosed` ⇒ 真死 abort；出向开 ⇒ 跳拍不撕。
   */
  private[neblink] def sendHeartbeatPing(ws: WebSocket, closed: Deferred[IO, Unit]): Unit =
    try
      val prevAskedAt = lastPingSentAt.get()
      val fut = ws.sendText("""{"type":"ping"}""", true)
      val bumpedAt = System.currentTimeMillis()
      lastPingSentAt.set(bumpedAt)
      fut.whenComplete { (_, err) =>
        if err != null then
          err match
            case _: IllegalStateException if !ws.isOutputClosed() =>
              // 饱和/背压面：bulk 在飞占住 JDK 单发位，「Send pending」拒绝本拍 ——
              // 出向开着 = 连接没死的直接见证。跳拍不撕；回滚 lastPingSentAt
              // （future 已失败 ⇒ ping 没上线 ⇒ 「问出去过」的戳必须退回）。
              lastPingSentAt.compareAndSet(bumpedAt, prevAskedAt)
              logger.debugSync(
                s"Relay tunnel ping beat skipped: another send is in flight (${NeblinkRelayTunnel.describeErr(err)}) — output open, not a failure"
              )
            case _ =>
              logger.debugSync(s"Relay tunnel ping write failed async — aborting broken connection: ${NeblinkRelayTunnel.describeErr(err)}")
              try ws.abort()
              catch case _: Exception => ()
              armZombieLatchWatchdog(closed)
      }
      ()
    catch
      case e: Exception =>
        if ws.isOutputClosed() then
          // send failure on a live-flagged connection with its output CLOSED means
          // the socket is already broken — abort now instead of waiting out the
          // pong window with a dead connection (alive must track reality).
          logger.debugSync("Relay tunnel ping send failed (output closed) — aborting broken connection")
          ws.abort()
          armZombieLatchWatchdog(closed)
        else
          logger.debugSync(
            s"Relay tunnel ping beat skipped: another send is in flight (${NeblinkRelayTunnel.describeErr(e)}) — output open, not a failure"
          )

  private def stopHeartbeat(): Unit =
    heartbeat.foreach { hb =>
      try hb.shutdownNow()
      catch case _: Exception => ()
    }
    heartbeat = None

  // ===== Helpers =====

  /** Convert serverUrl (https://...) to ws/wss and build the relay-ws endpoint. */
  private def buildRelayWsUri(serverUrl: String, id: DeviceIdentity): String =
    val wsBase = serverUrl
      .replaceFirst("https://", "wss://")
      .replaceFirst("http://", "ws://")
    val encodedId =
      try java.net.URLEncoder.encode(id.deviceId, "UTF-8")
      catch case _: Exception => id.deviceId
    s"$wsBase/api/device/relay-ws?deviceId=$encodedId"

end NeblinkRelayTunnel

object NeblinkRelayTunnel:

  /**
   * D-B（2026-09-13）ack 帧 —— **帧形状的唯一定义点**（可独立单测，无需真 socket）。
   *
   * 冻结形状：`{"type":"ack","eventId":"<eventId>"}`，与 neblink-server
   * `ClientToServer::Ack`（`#[serde(tag="type", rename_all="snake_case")]` +
   * `#[serde(rename="eventId")]`，`src/relay.rs:77-94`）逐字对齐；消息面 eventId
   * 形如 `message-<id>`（服务端 `message_event_id`，`src/relay.rs:133-134`），
   * 由服务端 `message_receipt_message_id`（`:141-145`）反解。键名/取值改动 = 断链。
   */
  private[neblink] def ackFrame(eventId: String): Json =
    Json.obj("type" -> "ack".asJson, "eventId" -> eventId.asJson)

  /**
   * D6 confirm-code report frame — **the single shape definition point**
   * (same discipline as [[ackFrame]]): `{"type":"bind_confirm","code":"<6
   * digits>","ttlSec":900}`.
   *
   * Alignment target for the neblink-server implementer (no server-side
   * definition exists yet — this frame rides the RELAY control plane, NOT
   * the frozen 13-frame portal wire contract, so no contract/vector change
   * is involved): a new `ClientToServer` enum variant in `src/relay.rs`,
   * following that enum's exact conventions (serde `tag = "type"`,
   * `rename_all = "snake_case"`, explicit camelCase field renames):
   *
   * ```rust
   * BindConfirm {
   *     #[serde(rename = "code")]
   *     code: String, // exactly 6 ASCII digits — a STRING, never a number (leading zeros are real codes)
   *     #[serde(rename = "ttlSec")]
   *     ttl_sec: u32, // the host-side pending TTL in seconds (BindConfirmStore.DefaultTtlSec = 900)
   * },
   * ```
   *
   * The tunnel registration already identifies the device/account server
   * side, so no identity field rides the frame. Until the server lands the
   * variant it logs + drops the unknown type (`relay_legacy_text` warn
   * branch) — the wire is otherwise untouched.
   */
  private[neblink] def bindConfirmFrame(code: String, ttlSec: Long): Json =
    Json.obj(
      "type" -> "bind_confirm".asJson,
      "code" -> code.asJson,
      "ttlSec" -> ttlSec.asJson
    )

  private val logger = NebflowLogger.forName("nebflow.neblink.relay")

  /**
   * 异常的可读原因（`getMessage` 可为 null：如 `WebSocketHandshakeException`——
   * 同族口径见本仓 `RelayTunnelDiagnostics`）。禁把 null 写进日志/审计。
   */
  private[neblink] def describeErr(t: Throwable): String =
    Option(t.getMessage).filter(_.nonEmpty).getOrElse(t.getClass.getSimpleName)

  /**
   * ack 发送**结局**（F3，2026-09-18 回执诚实性批，作者裁示「回执诚实性修、单列小批」）
   * ——调用方据此**如实记账**：🔴 `NoLiveSocket` / `SendFailed` **不得**被读成
   * 「已回执」（那正是本批修掉的假陈述：`DeviceMailInbox.sendReceipt` 的
   * `ack sent to the server` + 审计 `ack-sent`）。
   *
   * 语义边界（须知）：`Sent` = 「帧已交给**在册的** relay socket、其出向未关、
   * `sendText` 未同步抛」——**不等于**「服务端已收到/已脱账」（客户端不可观测，
   * 属 neblink-server 面，见 `mailreplay-recon` §6 U-1）。线上「真有帧」的实证 =
   * `DeviceMailAckHonestySpec` 的真 RFC 6455 夹具直读（非 mock）。
   */
  enum AckOutcome:
    /** 帧已写进在册 socket（线上有帧——spec 级直读）。 */
    case Sent

    /** 无 live socket：隧道对象不在册 / socket 未注册 / 出向已关 ⇒ **零帧上线**。 */
    case NoLiveSocket

    /** socket 在册但写失败（同步抛出）⇒ **零帧上线**（带可读原因）。 */
    case SendFailed(reason: String)

  /**
   * **唯一** ack 发送实现点（F4 单一实现点，2026-09-18 回执诚实性批）。
   *
   * 两个装配缝（`GatewayMain` 好友消息腿 `:900-905` / 设备邮件腿 `:1010-1015`）都
   * **只**调本方法：「隧道对象 live 读 + 无对象时如实报 `NoLiveSocket`」这套判断
   * 只此一份。修前两处各写一份 `case None => IO.unit`，把「压根没发出」吞成成功
   * ——同族判断各写一份正是该缺陷能在两条腿上同时存活的原因。
   */
  def sendAckLive(tunnelOpt: Option[NeblinkRelayTunnel], eventId: String): IO[AckOutcome] =
    tunnelOpt match
      case Some(t) => t.sendAck(eventId)
      case None =>
        logger
          .debug(s"ack-not-sent reason=no_live_socket: relay tunnel not registered for $eventId")
          .as(AckOutcome.NoLiveSocket)

  /**
   * The single bind-confirm send implementation point (same shape discipline
   * as [[sendAckLive]]): the gateway route reads the tunnel LIVE at request
   * time and an absent tunnel is an honest `NoLiveSocket`, never a swallowed
   * success.
   */
  def sendBindConfirmLive(tunnelOpt: Option[NeblinkRelayTunnel], code: String, ttlSec: Long): IO[AckOutcome] =
    tunnelOpt match
      case Some(t) => t.sendBindConfirm(code, ttlSec)
      case None =>
        logger
          .debug("bind-confirm-not-sent reason=no_live_socket: relay tunnel not registered")
          .as(AckOutcome.NoLiveSocket)

  /**
   * Client heartbeat interval in seconds — the relay-link liveness `ping`
   * cadence (single point; the heartbeat scheduler reads this constant).
   *
   * Phase 3c liveness retune (plan §7.3 three-face batch; contract v1.1
   * section 4 "Heartbeat interval: 3 s"): 10s -> 3s, retuned TOGETHER with the
   * server-side eviction face (relay.rs `TUNNEL_PING_INTERVAL` 3s x
   * `TUNNEL_MAX_MISSED_PONGS` 2 = a 6s miss window). The miss window must stay
   * strictly above the client heartbeat — a 2x margin, so ONE delayed
   * heartbeat cannot flip a live link offline (any inbound frame resets the
   * server's missed-pong counter) — and the silent-partition detection line
   * (2x ping interval + phase 0..1x interval + close propagation = 6..9s)
   * stays within the 10s close-propagation budget.
   */
  private[neblink] val HeartbeatIntervalSec: Long = 3L

  /**
   * Liveness window (①-2 / ①opt-A1, wave 3 2026-09-12): a last-INBOUND-frame
   * stamp older than this window means the connection is a zombie (hbzombie-src
   * 2026-10-14：刷新面由「仅 pong」放宽到「任意入向帧」，与服务端
   * 「any inbound frame resets the missed-pong counter」同口径；且心跳判死另加
   * 「确有 ping 入线且等满一拍」前提，见 startHeartbeat）。The SAME value feeds
   * both the heartbeat's zombie judgment and `isAlive` (never a second threshold —
   * drifting the two would make status and self-heal disagree).
   *
   * Phase 3c retune arithmetic (with the 6s server miss window, plan §7.3):
   * 30s -> 9s = 3 beats of the 3s heartbeat, keeping the previous
   * three-beats ratio (30s was 3 beats of the 10s heartbeat). The window must
   * exceed 2x the heartbeat interval so one lost or delayed beat (up to a full
   * interval late) still passes, and it converges with the server's eviction
   * line (miss window 6s, detection line 6..9s) so both ends reach the same
   * "link dead" verdict at the same scale instead of the client reporting a
   * live link tens of seconds after the server dropped it.
   */
  private[neblink] val LivenessTimeoutMs = HeartbeatIntervalSec * 3 * 1000

  /**
   * Backoff ceiling of the reconnect ladder (unchanged value — the pre-fix
   * expression also capped at 30s; what changed is that the cap now holds for
   * every attempt, see [[backoffSeconds]]).
   */
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
   * 被服务端踢下线后的停摆轮询间隔（案 B 客户端腿，2026-09-14）。
   *
   * 停摆期**不发起任何连接**，只是按这个间隔醒来重新判定一次「用户是否已显式再登录」。
   * 30s 与 ReconnectCap（30s 上限）同量级 ⇒ 用户再登录到隧道回来的时延与一次普通
   * 重连同阶；且 `resumeAfterUserLogin` 会掐断当前这一睡（`signalWake`）⇒ 实际恢复
   * 时延 ≈ 0。取值不引入第二个阈值轴：它只决定「多久复查一次停摆条件」。
   */
  private[neblink] val KickParkNap: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(30).seconds

  /**
   * Terminal-binding probe cadence (judgment-table rows 1/2, design §3.5):
   * while the host binding is revoked, the tunnel dials at this low fixed
   * frequency instead of churning the reconnect ladder — enough for the
   * server binding gate's 401 terminal body to keep confirming the state and
   * for a genuine re-bind to be picked up by the next successful upgrade
   * (row 4). 10 minutes per the design's "10min 低频探测".
   */
  private[neblink] val TerminalProbeNap: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(10).minutes

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
  def statusJson(available: Boolean, status: Option[TunnelAuthStatus], kickedAtMs: Long = 0L): io.circe.Json =
    io.circe.Json.obj(
      "available" -> available.asJson,
      "authRejected" -> status.exists(_.active).asJson,
      "lastRejectedStatusCode" -> status.map(_.statusCode).asJson,
      "lastRejectedAt" -> status.map(_.atMs).asJson,
      "selfHeal" -> status
        .map(s => if !s.healAttempted then "not-attempted" else if s.healSucceeded then "ok" else "failed")
        .asJson,
      // 案 B 客户端腿（2026-09-14）——**本机网关 ↔ 浏览器**这一侧的加法字段，不是
      // neblink-server 的 wire：服务端 `disconnect` 帧零字段变化（listener 仍只按
      // `type` 分派），提示文案由客户端本地下定，这里只把本地已判定的状态透给 UI。
      "signedOutElsewhere" -> (kickedAtMs > 0L).asJson,
      "signedOutElsewhereAt" -> (if kickedAtMs > 0L then kickedAtMs.asJson else io.circe.Json.Null),
      "autoReconnectParked" -> (kickedAtMs > 0L).asJson
    )

end NeblinkRelayTunnel

/**
 * JDK WebSocket.Listener for the relay tunnel.
 * Forwards events to NeblinkRelayTunnel — stateless on its own.
 */
private final class RelayWsListener(
  tunnel: NeblinkRelayTunnel,
  closed: cats.effect.Deferred[IO, Unit],
  dispatcher: Dispatcher[IO],
  portalConn: PortalConnection
) extends WebSocket.Listener:
  private val logger = NebflowLogger.forName("nebflow.neblink.relay")

  /**
   * 帧内会话 id 取值（W9/W10 的 `conversationId=` 字段用）。
   *
   * 与 `FriendService.conversationIdOf` **同口径**（`payload` 下钻优先，顶层为旧形状
   * 容错）——此处是**日志字段**用途、不参与路由，故不跨类依赖该 private 方法；
   * 两边口径若漂移，症状只是留痕字段缺失，不影响投递正确性。
   */
  private def conversationIdOfFrame(json: Json): Option[String] =
    json.hcursor
      .downField("payload")
      .get[String]("conversationId")
      .toOption
      .orElse(json.hcursor.get[String]("conversationId").toOption)

  override def onOpen(ws: WebSocket): Unit =
    ws.request(1)

  /**
   * 本连接的分片缓冲（fragreasm-impl，2026-09-19）：**一连接一实例** ⇒ 缓冲按连接
   * 持有；有界性/生命周期口径见 [[RelayWsListener.FrameReassembler]]。
   */
  private val reassembler = new RelayWsListener.FrameReassembler()

  /**
   * WS 文本帧入口（**分片感知**）。
   *
   * 修前（本批缺陷）：`last` 收到但**从不使用** ⇒ 每次调用都把当前片段当整帧
   * `decode[Json]`（首片残片解析失败 + 后继片各自成帧 ⇒ 大载荷必丢）。修后：分片交给
   * [[RelayWsListener.FrameReassembler]]，**只有 `last == true` 的完整载荷**才进
   * [[handleFrame]]（既有分支逻辑**逐字未改**）；单帧消息（缓冲为空 + `last == true`）
   * 走直通分支 ⇒ 与修前逐字等价。
   */
  override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
    // hbzombie-src（2026-10-14 客户端腿同案确认批 D2 修）：**任何入向交付**（含
    // 分片中间片、契约帧、bulk 数据帧）都刷新存活戳——与服务端「任意入向帧重置
    // missed-pong 计数」同口径（见 LivenessTimeoutMs 注）。修前只有
    // {"type":"pong"} 刷新 ⇒ relay 心跳腿被 bulk 饿死、但数据帧仍在流动时，本端
    // 把一条活连接误判僵尸并 abort（A4 崩塌的客户端侧放大器）。半开 TCP 的判死
    // 能力不受影响：半开时根本不会有任何入向交付。
    tunnel.updateLastPong()
    reassembler.accept(data, last) match
      case RelayWsListener.FrameStep.Complete(payload) =>
        handleFrame(ws, payload)
      case RelayWsListener.FrameStep.Partial | RelayWsListener.FrameStep.Discarding =>
        ()
      case RelayWsListener.FrameStep.Dropped(reason, chars) =>
        // W13（fragreasm-impl，2026-09-19）：分片消息被判废 = **一条整消息**丢失的信号。
        // 修前这条消息只会以逐片 `undecodable_frame` 的形态出现 ⇒「一条大载荷没了」与
        // 「一条小帧畸形了」不可分（归因不可跳过）。字段沿用既有判据口径
        // （branch + reason + 规模 + 门槛）。
        logger.warnSync(
          "relay frame dropped: fragmented message abandoned " +
            f"branch=W13 conversationId=<none> messageId=<none> reason=$reason " +
            s"chars=$chars maxChars=${RelayWsListener.MaxAssembledFrameChars} " +
            s"deadlineMs=${RelayWsListener.AssemblyDeadlineMs}"
        )
    end match
    ws.request(1)
    null

  end onText

  /**
   * 完整帧上送路径 —— 修前 `onText` 的既有分支逻辑（`type` 分派 + W9..W12 留痕）
   * **逐字搬运**，唯一变化是载荷来源（`data.toString` → 完整消息字符串；单帧场景下二者
   * 逐字相同）。fragreasm 批的「禁止在本方法内新增/改写分支语义」约束随该批收口；
   * phase 3a（设计 §3.1）在此新增：proto 前置校验（P0）+ 契约帧族分支（P1），
   * 既有分支（relay_request/ping/pong/friend_event/device_status_update/
   * disconnect/ack + W10/W11）语义零改动。
   */
  private def handleFrame(ws: WebSocket, text: String): Unit =
    try
      decode[Json](text) match
        case Right(json) if RelayWsListener.protoTooNew(json) =>
          // P0（设计 §3.1 proto 校验统一前置）：proto 超过支持版本 ⇒ 对**所有**
          // 帧族拒收（含 relay_request——host 侧单一入口、无回落路径，故服务端
          // §2.2 那种「拒后回落被接受」的缝隙在这里构造上不存在）⇒ 丢帧，绝不
          // 落入下方分派。缺失 ⇒ 宽容（既有族继续走既有解析；新帧族由 P1 的
          // codec 全量校验补上 missing_required_field）。
          logger.warnSync(
            "relay frame dropped: proto unsupported " +
              f"branch=P0 conversationId=<none> messageId=<none> reason=proto_unsupported " +
                s"head=${RelayTunnelDiagnostics.redact(text.take(200))}"
          )
        case Right(json) =>
          json.hcursor.downField("type").as[String].getOrElse("") match
            case "relay_request" =>
              // P6 诊断探针（delegate 断点追查，腿3 定案后）①隧道层收帧入口：
              // 服务端已证帧投递到 B 且 531ms 内回包 200（request_id=e64239ec），
              // 本行与分发层探针②「spawn dispatched」对照，把断点钉到
              // 「隧道收帧 → spawn 分发」之间的具体段。DEBUG 级、只读帧摘要
              // （requestId/action/帧长），分发语义零改动。
              logger.debugSync(
                s"P6 probe: relay_request received " +
                  s"request_id=${json.hcursor.downField("requestId").as[String].getOrElse("<none>")} " +
                  s"action=${json.hcursor.downField("action").as[String].getOrElse("<none>")} " +
                  s"frameChars=${text.length}"
              )
              dispatcher.unsafeRunAndForget(tunnel.handleRelayRequest(json, ws))
            case "ping" =>
              // pong-reliability（2026-10-22「大传输触发重连」诊断修复批）：
              // 修前此处裸 `ws.sendText(pong)` 且无视返回 future —— JDK 单发位被
              // bulk 帧占住时 sendText 不同步抛、只回携 ISE("Send pending") 失败
              // future（NeblinkRelayTunnelHeartbeatSendSpec P1 真 socket 契约钉）
              // ⇒ bulk 洪峰下 pong 静默丢失 ⇒ relay 侧 2-miss liveness 判死撕隧道
              // ⇒ 骑隧道的浏览器 WS 全体重连（作者症状；stand.log 形态 = 无
              // zombie 前兆的「Relay tunnel disconnected after Ns」+ 同秒
              // 「portal http cycle failed: Output closed」伴生簇）。修后 pong 走
              // RelayFrameSender（与业务 bulk 同锁：Send pending 重试 + 预算，
              // 持锁等在飞帧写完即上、不会被洪峰饿死）；失手留痕不撕连接。
              portalConn.sendControlText("""{"type":"pong"}""", ws)
            case "pong" =>
              tunnel.updateLastPong()
            case "friend_event" =>
              // A2A 一期：好友/消息推送（spec §5.1 复用 relay 隧道）。尽力而为
              // 优化——REST 补拉兜底，事件丢失不影响正确性。
              //
              // W9（§3.3）：修前是 `foreach` ⇒ `friendService` 为 None 时**整帧静默
              // 丢弃**（零日志）。这是「事件到了网关却什么都不发生」的最短路径，
              // 归因不可跳过 ⇒ 显式 WARN。
              //
              // 跨设备 Nebula 邮件（device-mail 批，2026-09-15；契约 **v2.1**，12:57
              // root 裁定）：设备邮件的收件**唯一入口** = 本事件流信封内
              // `event.type == "agent_mail"` 的帧（实证形态：
              // `{"type":"friend_event","eventId":"message-<id>","event":{"payload":{…},"type":"agent_mail"}}`）。
              // 契约 v2 时期的隧道顶层 `case "agent_mail"` 分支**已删**（禁双入口）。
              //
              // mailmodel batch (2026-09-25, ruling (e-1)): this intake leg is retired
              // on BOTH ends in the same batch — the send side (the Mail tool's
              // `device` parameter, refused by the MAIL_DEVICE_RETIRED tombstone) and
              // this injection side go offline together. This branch stays as a
              // retirement tombstone: an `agent_mail` frame => log WARN and ignore
              // (it must NOT fall into `FriendService` — that would parse/credit it
              // as a friend message; double consumption is worse than dropping);
              // the payload is no longer parsed and no session is injected.
              // `nebflow.neblink.DeviceMail*` objects remain only as tombstones (zero
              // production callers).
              if DeviceMail.isAgentMailEnvelope(json) then
                logger.warnSync(
                  "agent_mail frame ignored: cross-device agent-mail intake is not a tunnel face — " +
                    "the sender side refuses with MAIL_DEVICE_UNSUPPORTED; " +
                    f"frame dropped branch=agent_mail_retired conversationId=${conversationIdOfFrame(json).getOrElse("<none>")}"
                )
              else
                tunnel.friendService match
                  case Some(fs) =>
                    dispatcher.unsafeRunAndForget(fs.onFriendEvent(json))
                  case None =>
                    logger.warnSync(
                      "friend_event frame dropped: friendService not wired " +
                        f"branch=W9 conversationId=${conversationIdOfFrame(json).getOrElse("<none>")} " +
                        "messageId=<none> reason=friend_service_not_wired"
                    )
            case "device_status_update" =>
              // presence v2 (C6)：设备上下线推送——隧道关闭/探活判死时服务端广播。
              // 帧驱动为主（<2s 翻转），心跳顺带拉取降级为帧丢失兜底。
              dispatcher.unsafeRunAndForget(tunnel.handleDeviceStatusUpdate(json))
            case "disconnect" =>
              // presence fix A1 (server-side): forced tunnel teardown on logout.
              // 2026-09-14（踢旧批案 B，作者 17:07 裁定 C+B·客户端一刀）：本帧是
              // 「本设备已在别处登录」的唯一信号（服务端 kick 同 (deviceId, network)
              // 的旧会话）。从「只 debug」提升为：被动提示（角标/状态行级，无横幅
              // 无声音——一期口径见设计件）+ 停摆（禁自动
              // 重连/重注册，防同账号双实例互踢乒乓）。
              // 🔴 零 wire 新增：本分支**不解析任何新字段**（仍只按既有 `type`
              // 分派），帧形态与修前逐字节同形；提示文案由客户端本地下定。
              dispatcher.unsafeRunAndForget(tunnel.noteServerDisconnect())
            case "ack" =>
              // 定向投递回执（契约 v2 ④ / v2.1 ②；形状冻结 `{"type":"ack","eventId":"message-<id>"}`）：
              // **既有** ack 机制复用 ⇒ 发送侧在此做 eventId 关联（命中 = INFO + 审计；
              // `message-` 族未命中 = WARN + 审计；其余族 = DEBUG）。本分支**只读**帧，
              // 不改帧形状、不改本端 `sendAck` 的既有发送语义。
              dispatcher.unsafeRunAndForget(DeviceMailAck.handle(json))
            case "http_request" | "body_frame" | "ws_open" | "ws_frame" | "ws_close" |
                "ConsoleTicketMint" | "BindingControl" | "MigrationChunk" =>
              // P1（设计 §3.1）：契约帧族——先过生产 codec（[[PortalWireCodec]]）全量
              // 校验：帧字段与冻结契约逐字一致；拒码 = conformance vocabulary，
              // 丢帧 + 结构化诊断（复用既有脱敏面），绝不部分处理、绝不回落 W10
              // （MigrationChunk 在 codec 侧成功解码后由桥接层按 not-implemented 丢弃，
              // 不落 unknown_type——那是契约漂移信号，会误报）。
              handlePortalFrame(text, json)
            case other @ ("http_response" | "ws_open_result" | "ConsoleTicketResult") =>
              // 上行契约帧被回授：语义上无需动作（与 ack/relay_response 同族的降噪
              // 口——只 debug，防 W10 噪声化）。
              logger.debugSync(s"Relay frame ignored (benign): uplink echo type=$other")
            case other if RelayWsListener.BenignUnknownFrameTypes.contains(other) =>
              // W10（§3.3）的**降噪口**：本端自己会发/回的帧类型（`ack` /
              // `relay_response`）若被回授，语义上无需动作 ⇒ 只留 debug，
              // 免得 W10 退化成噪声源（噪声化 = 真信号被淹 = 另一种静默）。
              logger.debugSync(s"Relay frame ignored (benign): type=$other")
            case other =>
              // W10（§3.3）：修前 `case _ => ()` 零日志。**未知类型 = 契约漂移信号**：
              // 服务端新增帧类型而本端未接 ⇒ 该族事件整类静默，只有 WARN 能让它与
              // 「上游压根没发」分开。带 `type` 与帧长度（不含正文，避免噪声）。
              logger.warnSync(
                "relay frame dropped: unknown type " +
                  f"branch=W10 conversationId=${conversationIdOfFrame(json).getOrElse("<none>")} " +
                  s"messageId=<none> reason=unknown_frame_type type=$other len=${text.length}"
              )
        case Left(err) =>
          // W11（§3.3）：修前 `case Left(_) => ()` 零日志。不可解析的帧 = 帧形状
          // 契约破裂（版本错配、半包、编码漂移）——静默丢弃会让「隧道在收帧但 UI
          // 不动」无法与「隧道根本没收帧」区分。带前 200 字符（**必过脱敏**：
          // 复用 `RelayTunnelDiagnostics.redact` 的既有凭据口径，禁自写一套）。
          logger.warnSync(
            "relay frame dropped: undecodable json " +
              f"branch=W11 conversationId=<none> messageId=<none> reason=undecodable_frame " +
              s"err=${err.getClass.getSimpleName}: ${err.getMessage} " +
              s"head=${RelayTunnelDiagnostics.redact(text.take(200))}"
          )
    catch
      case e: Exception =>
        // W12（§3.3）：修前整段 `catch case _: Exception => ()` 零日志 ⇒ 任何监听器
        // 内部异常（NPE/越界/编码错）都被吞成「什么都没发生」。**禁吞异常类**：
        // 类名 + 消息是唯一能把这类故障从「帧没到」里分出来的读数。
        logger.warnSync(
          "relay frame handler threw " +
            f"branch=W12 conversationId=<none> messageId=<none> reason=frame_handler_exception " +
            s"err=${e.getClass.getSimpleName}: ${e.getMessage} head=${RelayTunnelDiagnostics.redact(text.take(200))}"
        )
  end handleFrame

  /**
   * P1 dispatch（设计 §3.1）：契约帧族的唯一入口——生产 codec 全量校验后按
   * 解码帧分派到隧道（隧道再交给当前连接的 portal bridge）。拒码 ⇒ 丢帧 +
   * 结构化诊断（branch=P1 + 拒码 + 触发字段 + 脱敏帧头），绝不部分处理。
   */
  private def handlePortalFrame(text: String, json: Json): Unit =
    PortalWireCodec.decodeJson(json) match
      case Right(frame) =>
        tunnel.dispatchPortalFrame(frame, portalConn)
      case Left(rej) =>
        logger.warnSync(
          "relay frame dropped: portal contract rejection " +
            f"branch=P1 conversationId=<none> messageId=<none> reason=${rej.code} " +
              s"field=${rej.field.getOrElse("<none>")} fields=${rej.fields.getOrElse(Nil).mkString(",")} " +
              s"expected=${rej.expected.getOrElse("-")} allowed=${rej.allowed.getOrElse(Nil).mkString("/")} " +
              s"head=${RelayTunnelDiagnostics.redact(text.take(200))}"
        )

  override def onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage[?] =
    // 有界性腿③（连接终结）：缓冲随连接消亡，不跨连接存活（半成品即弃并计数）。
    reassembler.reset()
    dispatcher.unsafeRunAndForget(closed.complete(()).void)
    null

  override def onError(ws: WebSocket, error: Throwable): Unit =
    logger.debugSync(s"Relay WS error: ${error.getMessage}")
    // 异常同义：残片即弃（否则断连抖动会把上一连接的残片带进下一连接）。
    reassembler.reset()
    dispatcher.unsafeRunAndForget(closed.complete(()).void)
end RelayWsListener

object RelayWsListener:
  /**
   * W10 的**降噪白名单**：本端自己会发/回、语义上不需要动作的帧类型
   * （`ack` 是 `NeblinkRelayTunnel.sendAck` 的出向形态；`relay_response` 是
   * `handleRelayRequest` 的出向形态）。若被回授，只留 debug——否则 W10 会退化成
   * 噪声源，而**噪声化 = 真信号被淹 = 另一种静默**（与修 W10 的初衷相悖）。
   * 名单之外的未知类型一律 WARN（= 契约漂移信号）。
   */
  private[neblink] val BenignUnknownFrameTypes: Set[String] = Set("ack", "relay_response")

  /**
   * P0 front check (design §3.1): `proto` present AND above the supported
   * version. Applies to EVERY frame family. A missing `proto` stays lenient
   * here (pre-v1 legacy frames); the new contract frames get the strict
   * `missing_required_field` from the codec at P1. A non-integer `proto`
   * also stays lenient here — P1's codec rejects it for contract frames.
   */
  private[neblink] def protoTooNew(json: Json): Boolean =
    json.hcursor.downField("proto").as[Int].toOption.exists(_ > PortalWireCodec.SupportedProto)

  // ===== 分片重组（fragreasm-impl，2026-09-19）===============================
  //
  // 缺陷（修前 `onText`，`NeblinkRelayTunnel.scala:982-984`）：`last` 参数**收到但
  // 从不使用** —— 每次 WS 调用都直接把 `data.toString` 当整帧 `decode[Json]` ⇒
  // **任何被 WS 分帧的大载荷必丢**：首片是残片（解析失败），后继片各自按整帧走同一
  // 路径 ⇒ 零重组、零派发（2026-09-19 01:23 实测：3 片 4129 字符 ⇒ 0 派发 + 3 条
  // `undecodable_frame`）。RFC 6455 §5.4 把分片重组定为**接收方**的责任，本对象即
  // 该责任的最小实现（零 wire 变化、零服务端依赖）。

  /**
   * 重组缓冲的**字符**上限（🔴 显式设计选择，非推导量；有界性腿①）。
   *
   * 理由与边界（申报口径，可机械核）：
   *   - `CharSequence` 的单位是 UTF-16 code unit（本实现按 `length` 计），最坏内存
   *     ≈ 上限 × 2 B = **16 MiB**；本隧道同时至多一条在连 socket（`connectOnce`
   *     单飞 + `wsRef` 单槽）⇒ 全进程最坏 ≈ 16 MiB，**有界**；
   *   - 8 MiB 的量级取自本仓同一用途的既有常量（`NeblinkFiles.scala:23` 的 8 MiB
   *     读缓冲），远高于任何实测 relay 帧（设备邮件/relay_request 均在 KB 级；大载荷
   *     走 `FileTransferAction` 的 `chunkSize` 分块）；
   *   - 🔴 上限**只约束「分片重组」**：单帧消息（`last == true` 且缓冲为空）走直通
   *     分支、不经缓冲、不受本上限影响 ⇒ 修前能收的单帧大载荷修后照收（零行为漂移）；
   *     被丢弃的只可能是「分片总长超过上限」的消息。
   */
  private[neblink] val MaxAssembledFrameChars: Int = 8 * 1024 * 1024

  /**
   * Survival cap for a partially-assembled frame buffer (explicit design
   * choice; boundedness leg 2, lazily evaluated).
   *
   * The historical anchor was this tunnel's own liveness window
   * (`NeblinkRelayTunnel.LivenessTimeoutMs`, then 30s). The phase 3c liveness
   * retune lowered that window to 9s, and THIS constant deliberately keeps its
   * own 30s value — reassembly patience and link-liveness detection are two
   * different scales and do not retune together: a message the peer cannot
   * finish within a liveness-window-scale span stops occupying buffer here.
   */
  private[neblink] val AssemblyDeadlineMs: Long = 30_000L

  /** 一次 WS 调用的处置结果（`onText` 的唯一分支依据）。 */
  private[neblink] enum FrameStep:
    /** 完整消息（`last == true`）。载荷与修前同源：单帧直通时**逐字等于** `data.toString`。 */
    case Complete(payload: String)

    /** 已缓冲，等待后续分片（`last == false`）——**零派发**。 */
    case Partial

    /** 本条消息判废（超限 / 过期）⇒ **零派发** + 显式留痕（`reason` ∈ `frag_oversize` / `frag_stale`）。 */
    case Dropped(reason: String, chars: Int)

    /** 判废消息的剩余分片（消息终点未知）⇒ 静默丢弃到 `last == true`。 */
    case Discarding

  /**
   * WS 文本帧**分片重组器**（RFC 6455 §5.4 接收方责任）。
   *
   * **生命周期（显式）**：每个 WS 连接一个实例（`RelayWsListener` 每连接新建）⇒ 缓冲
   * 按连接持有；完成即清（`Complete` / `Dropped` 之后缓冲为空）；连接关闭/异常由
   * `RelayWsListener.onClose` / `onError` 调 [[reset]] 清空 ⇒ **缓冲不跨连接存活**。
   *
   * **有界性（显式申报的两条腿）**：① 上限 [[maxChars]]；② 过期 [[deadlineMs]]（惰性）。
   * 惰性 = 不引入定时线程（本类零 spawn、零额外生命周期），内存最坏保持到「下一帧到达」
   * 或「连接关闭」，两者都有上界（心跳 10 s 一帧；隧道存活窗 30 s 判僵尸 ⇒ abort）。
   *
   * 🔴 **绝不派发非完整载荷**：判废只可能发生在「消息尚未结束」的中间态（`last == false`），
   * 而按 WS 协议此时**下一条调用必然仍是同一条消息的续片**（消息终点 = `last == true`）
   * ⇒ 判废后进入 `Discarding` 把剩余续片丢到消息终点为止，绝不把残尾当新消息上送
   * （否则残尾若恰好可解析 = 派发一条被截断的语义帧）。
   */
  private[neblink] final class FrameReassembler(
    val maxChars: Int = MaxAssembledFrameChars,
    val deadlineMs: Long = AssemblyDeadlineMs,
    nowMs: () => Long = () => System.currentTimeMillis()
  ):
    private var buf = new StringBuilder
    private var chars = 0
    private var startedAtMs = 0L
    private var discarding = false
    private var dropped = 0

    /** 当前缓冲的字符数（判定面读数）。 */
    def pendingChars: Int = chars

    /** 是否处于「判废消息的续片静默丢弃」态。 */
    def isDiscarding: Boolean = discarding

    /** 累计判废（丢弃）的**消息**数（超限 + 过期 + 连接关闭时的半成品）。 */
    def droppedMessages: Int = dropped

    /** 收一次 WS 调用（`data` = 本片载荷，`last` = 是否消息终点）。 */
    def accept(data: CharSequence, last: Boolean): FrameStep =
      if discarding then
        // 判废消息的续片：丢到消息终点为止（终点即清，下一条消息从零开始）。
        if last then clear(discardingNext = false)
        FrameStep.Discarding
      else if chars > 0 && nowMs() - startedAtMs >= deadlineMs then
        // 有界性腿②：半成品过期 ⇒ 判废（消息终点未知 ⇒ 续片继续丢）。
        dropped += 1
        val n = chars
        clear(discardingNext = !last)
        FrameStep.Dropped("frag_stale", n)
      else if last && chars == 0 then
        // 🔴 单帧直通（零行为漂移）：与修前逐字同源，不经缓冲、不受上限约束。
        FrameStep.Complete(data.toString)
      else
        val part = data.toString
        if chars == 0 then startedAtMs = nowMs()
        buf.append(part)
        chars += part.length
        if chars > maxChars then
          // 有界性腿①：超限 ⇒ 判废（消息终点未知 ⇒ 续片继续丢）。
          dropped += 1
          val n = chars
          clear(discardingNext = !last)
          FrameStep.Dropped("frag_oversize", n)
        else if last then
          val complete = buf.toString
          clear(discardingNext = false)
          FrameStep.Complete(complete)
        else FrameStep.Partial

    /** 连接关闭/异常：清空（未完成的半成品消息判定为丢弃并计数）。 */
    def reset(): Unit =
      if chars > 0 then dropped += 1
      clear(discardingNext = false)

    private def clear(discardingNext: Boolean): Unit =
      buf = new StringBuilder
      chars = 0
      startedAtMs = 0L
      discarding = discardingNext
  end FrameReassembler
end RelayWsListener
