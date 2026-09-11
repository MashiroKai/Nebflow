package nebflow.core.tools

import cats.effect.std.Dispatcher
import cats.effect.{Deferred, IO, Ref}
import io.circe.JsonObject
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.agent.AgentCommand
import nebflow.core.NebflowLogger
import nebflow.neblink.{NeblinkClient, NeblinkService, PeerInfo}
import sttp.client4.*

import scala.concurrent.duration.*

/**
 * Pure decision surface for the P2P-vs-relay choice (A) and path memory (C4),
 * 2026-09-11 P2P 直连修复批.
 *
 * WHY pure: the two accepted criteria of the plan are behavioural
 * ("`directOnline=false` 且无负证据 ⇒ 尝试 P2P"; "地址变化 ⇒ 记忆失效"; "TTL 过期 ⇒
 * 重探"), and they were previously buried in IO/`@volatile` state where they could
 * only be observed by driving a real network. Extracting them makes each rule a
 * unit-testable function (`P2pPathDecisionSpec`) instead of a claim in a report.
 */
private[nebflow] object P2pPathDecision:

  /**
   * Path-memory TTL (C4). Lowered from the pre-fix 5 min to 60 s.
   *
   * WHY 60s: 5 min was long enough that a single relay success pinned the device
   * to relay for the rest of the window, and every further relay success renewed
   * it (`RemoteExecutor:514/:529`) — so a device whose P2P came back never
   * returned to P2P. 60s bounds the worst case to "one extra P2P probe per minute
   * per device" while keeping the memory long enough to spare the common
   * back-to-back write burst.
   */
  val PathMemoryTtlMs: Long = 60_000L

  /** Negative P2P evidence TTL (A): how long one real failure keeps P2P bypassed. */
  val NegativeCacheTtlMs: Long = 60_000L

  /** Short connect budget for a probe (A): unreachable addresses must fail fast. */
  val ProbeConnectTimeout: FiniteDuration = 1500.millis

  /**
   * Probe budget. `directOnline` (is there an active presence WS?) is **demoted
   * from decision basis to budget hint** — presence-not-connected means "the WS
   * dial did not succeed", not "the peer is unreachable" (different transport).
   */
  final case class ProbeBudget(maxRetries: Int, connectTimeout: FiniteDuration, shortConnect: Boolean)

  /**
   * `directOnline=true` ⇒ trust the peer, retry like before (3 retries, 3s
   * connect — the existing P0-1 posture). `false` ⇒ probe once with a 1.5s
   * connect budget.
   *
   * Worst-case cost before falling back to relay, which is the number the plan's
   * cost caveat is about:
   *   - `false`: ≤ 1.5s connect + one request (no retry, no backoff).
   *   - pre-fix behaviour this replaces: 3s connect × 4 attempts + 1+2+3s
   *     backoff ≈ 12-15s, i.e. the reason `skipP2p` existed at all.
   */
  def probeBudget(directOnline: Boolean): ProbeBudget =
    if directOnline then ProbeBudget(3, 3.seconds, false)
    else ProbeBudget(0, ProbeConnectTimeout, true)

  /**
   * A (负证据化): bypass P2P **only** when there is real recent P2P failure
   * evidence — a relay-route path memory, or a fresh dial/P2P failure
   * ([[NegativeCacheTtlMs]]). Previously `skipP2p = memory=="relay" || !directOnline`
   * turned "no presence WS" into "P2P impossible", which is how 9/9 dispatches
   * went to relay (方案 §1.2 (F)).
   *
   * `relayAvailable` is part of the conjunction: without a relay there is nothing
   * to bypass *to*, so the caller must still attempt P2P.
   */
  def shouldSkipP2pForRelay(
    memoryMethod: Option[String],
    recentP2pFailure: Boolean,
    relayAvailable: Boolean
  ): Boolean =
    relayAvailable && (memoryMethod.contains("relay") || recentP2pFailure)

  /**
   * Address-sensitivity key (C4): the whole endpoint set, sorted, so that **any**
   * change of address or endpoint set invalidates the memory (方案 §3.3 「如何更新」).
   * Sorted (not preference-ordered) deliberately — ordering churn alone must not
   * drop a valid memory, only an actual change of the reachable set may.
   */
  def addressKey(peer: PeerInfo): String =
    val list = if peer.endpoints.nonEmpty then peer.endpoints else List(peer.address)
    list.filter(_.nonEmpty).sorted.distinct.mkString("|")

  /** Path-memory entry: which transport last succeeded, when, and for which address set. */
  final case class PathMemory(method: String, atMs: Long, addressKey: String)

  def isFresh(mem: PathMemory, nowMs: Long): Boolean = nowMs - mem.atMs < PathMemoryTtlMs

  /** Memory usable for this dispatch: TTL fresh **and** same address set. */
  def reusable(mem: Option[PathMemory], peer: PeerInfo, nowMs: Long): Option[PathMemory] =
    mem.filter(m => m.addressKey == addressKey(peer) && isFresh(m, nowMs))

  /**
   * Write a memory entry. C4: **relay successes do not slide the window** — if a
   * fresh relay entry already exists for the same address, its timestamp is kept,
   * so the window always expires and P2P is re-probed at least once per TTL. P2P
   * successes *do* renew (staying on P2P is the desired state).
   */
  def record(existing: Option[PathMemory], method: String, peer: PeerInfo, nowMs: Long): PathMemory =
    val key = addressKey(peer)
    existing match
      case Some(m) if method == "relay" && m.method == "relay" && m.addressKey == key && isFresh(m, nowMs) =>
        m // no renewal — see scaladoc
      case _ => PathMemory(method, nowMs, key)

/**
 * Executes tool calls on remote devices via direct P2P over NebLink.
 *
 * When a tool call specifies device="desktop-v7eucht", this executor routes
 * the call to that device's gateway via HTTP (POST /api/neblink/remote-exec).
 *
 * NebLink provides the connectivity layer — no relay server needed.
 */
class RemoteExecutor(
  neblinkService: NeblinkService,
  dispatcher: Dispatcher[IO],
  relayClient: Option[NeblinkClient] = None
):

  private val logger = NebflowLogger.forName("nebflow.remote-executor")

  /**
   * The relay client ACTUALLY in use, resolved per dispatch (F1 sweep of the
   * 2026-09-10 隧道鉴权自愈批).
   *
   * `NeblinkService.relayClientOpt` is the hot-swap pointer: GatewayMain
   * registers the startup client and `NeblinkEnrollment.persist` re-points it
   * on every re-enrollment (logout clears it) — reading it live means a UI
   * re-login / account switch is followed instead of a constructor-time
   * snapshot. This class used to capture client₀ and kept dispatching through
   * a client whose session the server had kicked (the relay path is exactly the
   * "control the other computer" business leg of the incident).
   *
   * The constructor argument stays as a fallback for tests / non-gateway
   * wiring, where the service pointer is never set.
   */
  private def currentRelayClient: IO[Option[NeblinkClient]] =
    IO(neblinkService.relayClientOpt).map(_.orElse(relayClient))

  /** Timeout for synchronous remote calls without ToolContext (fallback path). */
  private val SyncTimeout = 120.seconds

  /** Timeout for background remote calls — the HTTP call waits up to this long. */
  private val BgTimeout = 3600.seconds

  // ---- P0-3: Path memory — remember last successful transport per device ----

  /**
   * Map: deviceId → last successful transport.
   *
   * C4 (2026-09-11 P2P 直连修复批): entries now carry the address set they were
   * recorded for, so a changed address/endpoint set invalidates the memory
   * instead of silently pinning the device to a stale transport choice.
   */
  @volatile private var lastSuccessPath: Map[String, P2pPathDecision.PathMemory] = Map.empty

  /**
   * Negative P2P evidence (A): deviceId → timestamp of the last real P2P
   * failure. Only a fresh entry here (see [[P2pPathDecision.NegativeCacheTtlMs]])
   * justifies bypassing P2P — "no presence WS" no longer does.
   */
  @volatile private var p2pFailures: Map[String, Long] = Map.empty

  /**
   * Read the cached transport **only if** it is both TTL-fresh and still refers
   * to the peer's current address set; an expired or address-stale entry is
   * dropped (so the next call re-probes P2P).
   */
  private def getPathMemory(peer: PeerInfo): Option[String] =
    val now = System.currentTimeMillis()
    val usable = P2pPathDecision.reusable(lastSuccessPath.get(peer.deviceId), peer, now)
    if usable.isEmpty && lastSuccessPath.contains(peer.deviceId) then
      lastSuccessPath = lastSuccessPath.removed(peer.deviceId)
    usable.map(_.method)

  private def recordPathMemory(peer: PeerInfo, method: String): IO[Unit] = IO {
    lastSuccessPath = lastSuccessPath.updated(
      peer.deviceId,
      P2pPathDecision.record(lastSuccessPath.get(peer.deviceId), method, peer, System.currentTimeMillis())
    )
  }

  private def clearPathMemory(deviceId: String): IO[Unit] = IO {
    lastSuccessPath = lastSuccessPath.removed(deviceId)
  }

  /** Record real P2P failure evidence (A) — the ONLY thing that bypasses P2P. */
  private def recordP2pFailure(peer: PeerInfo): IO[Unit] = IO {
    p2pFailures = p2pFailures.updated(peer.deviceId, System.currentTimeMillis())
  }

  private def hasRecentP2pFailure(deviceId: String): Boolean =
    p2pFailures.get(deviceId).exists(ts => System.currentTimeMillis() - ts < P2pPathDecision.NegativeCacheTtlMs)

  /**
   * HTTP backend for a probe budget (A): the short-connect backend when the
   * budget is a fast-fail probe, otherwise the shared 3s-connect backend
   * (`NeblinkService.httpBackend`, the existing P0-1 posture).
   */
  private lazy val shortConnectBackend: sttp.client4.SyncBackend =
    sttp.client4.httpclient.HttpClientSyncBackend.usingClient(
      java.net.http.HttpClient
        .newBuilder()
        .connectTimeout(java.time.Duration.ofMillis(P2pPathDecision.ProbeConnectTimeout.toMillis))
        .build()
    )

  private def backendFor(budget: P2pPathDecision.ProbeBudget): sttp.client4.SyncBackend =
    if budget.shortConnect then shortConnectBackend else neblinkService.httpBackend

  /** Read-only tools safe for parallel P2P + Relay racing (no side effects on cancellation). */
  private val ReadOnlyTools = Set("Read", "Glob", "Grep")

  /** Expose NeblinkService for system prompt generation (device list). */
  def neblinkServiceOpt: Option[NeblinkService] = Some(neblinkService)

  def execute(
    deviceName: String,
    toolName: String,
    params: JsonObject,
    ctxOpt: Option[ToolContext] = None
  ): IO[Either[ToolError, String]] =
    val isBackground = params("run_in_background").flatMap(_.asBoolean).getOrElse(false)
    // T4 审计用：projectRoot 随 ctx 传递（无 ctx 的 REST/兜底路径记空串）
    val projectRoot = ctxOpt.map(_.projectRoot).getOrElse("")

    def runOnPeer(peer: PeerInfo): IO[Either[ToolError, String]] =
      if peer.address.isEmpty then IO.pure(Left(ToolError(s"Device '${peer.deviceName}' has no address.")))
      else if isBackground && ctxOpt.isDefined then executeRemoteBackground(peer, toolName, params, ctxOpt.get)
      else if ctxOpt.isDefined then executeForegroundSync(peer, toolName, params, ctxOpt.get)
      else executeViaBestPath(peer, toolName, params, SyncTimeout, projectRoot)

    neblinkService.peers.flatMap { peers =>
      resolvePeer(deviceName, peers) match
        case Right(peer) => runOnPeer(peer)
        case Left(_) =>
          // Device not in peer list — the list might be stale. Trigger one immediate
          // discovery scan before giving up, so transient gaps don't cause false errors.
          logger.info(s"Device '$deviceName' not found in ${peers.size} peer(s), triggering discovery scan") *>
            neblinkService.scanNow.flatMap { refreshedPeers =>
              resolvePeer(deviceName, refreshedPeers) match
                case Right(peer) => runOnPeer(peer)
                case Left(err) => IO.pure(Left(err))
            }
    }
  end execute

  // ---- Remote background task: Mac manages lifecycle locally ----

  /**
   * When the LLM requests a remote background task, Mac handles the lifecycle:
   * 1. Strip `run_in_background` so the remote device executes synchronously
   * 2. Emit "running" indicator to frontend
   * 3. Start a detached fiber that does the synchronous HTTP call to the remote device
   * 4. Return "[Background job started]" to the LLM immediately
   * 5. When the HTTP call returns, notify agent (ExternalEvent) + frontend (WS)
   */
  private def executeRemoteBackground(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    val remoteParams = ensureRemoteTimeout(params.remove("run_in_background"))
    val commandStr = params("command").flatMap(_.asString).getOrElse(toolName)
    val firstLine = commandStr.split('\n').headOption.getOrElse(commandStr).take(80)
    val description = s"[${peer.deviceName}] ${params("description").flatMap(_.asString).getOrElse(firstLine)}"

    // 来源标注（2026-09-07 后台任务面板重设计）：与 BashTool 同推导（注册
    // 会话前缀 + 会话名 → 类别/显示名），注册与 WS 信封同源携带。
    val (bgOrigin, bgOriginLabel) = BgTaskRegistry.originFor(ctx.sessionId.getOrElse(""), ctx.sessionName)
    for
      jobId <- IO.randomUUID.map(_.toString.take(8))
      _ <- logger.info(s"Remote background task $jobId started on ${peer.deviceName}: $firstLine")
      // 1. Emit "running" to frontend + register in global registry
      _ <- emitBgTaskStarted(ctx, jobId, description)
      _ <- BgTaskRegistry.register(
             jobId,
             ctx.sessionId.getOrElse(""),
             description,
             "remote",
             ctx.rootSessionId.orElse(ctx.sessionId).getOrElse(""),
             false,
             bgOrigin,
             bgOriginLabel
           )
      // 2. Start heartbeat so frontend shows progress (remote tasks have no process-level health)
      doneRef <- IO.ref(false)
      _ <- startRemoteHeartbeat(ctx, jobId, description, doneRef)
      // 3. Start detached fiber — synchronous HTTP to the remote device, then notify
      _ <- IO(startRemoteBgFiber(peer, toolName, remoteParams, ctx, jobId, description, doneRef))
    yield Right(
      s"[Background job started] Job ID: $jobId\nThe command is running in the background on ${peer.deviceName}. You will be automatically notified when it finishes — continue with other work or finish your turn."
    )

  end executeRemoteBackground

  /**
   * Execute a remote command in the foreground until it completes.
   *
   * #26（2026-08-30 用户裁定「Bash 工具不再自动转后台」）：删除 30s 自动转后台
   * ——远程前台调用一直等到 HTTP 返回（或 BgTimeout 网络超时兜底），返回真实
   * 输出，不产生「[Remote command moved to background]」占位。
   *
   * 远程执行无本地进程树、无进程级进展探测（HTTP 等待中无法区分「命令在跑」
   * 与「设备无响应」），因此：
   * - 进程侧活动心跳（每 30s touch **processActivityMs**，2026-09-10 换轴前写的是
   *   lastActivityMs）——「对端还在等」的旁证；**不再**参与卡死判据，远程长命令
   *   的看护改由工具相位判据承担（单个工具调用超 ToolPhaseStuckMs 且 turn 未完成
   *   → TaskStuckWatcher 分级恢复）
   * - 真挂起（设备无响应）由 BgTimeout（3600s 网络超时）兜底——远程固有局限：
   *   无法像本地 Bash 一样用输出/CPU 停滞检测，网络层超时是唯一防线
   */
  private def executeForegroundSync(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    val remoteParams = ensureRemoteTimeout(params.remove("run_in_background"))
    val commandStr = params("command").flatMap(_.asString).getOrElse(toolName)
    val firstLine = commandStr.split('\n').headOption.getOrElse(commandStr).take(80)
    val description = s"[${peer.deviceName}] ${params("description").flatMap(_.asString).getOrElse(firstLine)}"

    for
      // 进程侧活动心跳（2026-09-10 换轴）：等待期间每 30s 刷新
      // **processActivityMs**（不再是 lastActivityMs——远程等待中的「进程/网络
      // 还活着」不等于 agent 侧有进展；旧写点与 BashTool 活动桥同属事故根因族）。
      hbFiber <- (IO.sleep(30.seconds) *> touchAgentActivity(ctx)).foreverM.start
      r <- executeViaBestPath(peer, toolName, remoteParams, BgTimeout, ctx.projectRoot)
      _ <- hbFiber.cancel
    yield r
  end executeForegroundSync

  /** 刷新 registry 的 **进程侧** 活动戳（远程等待期间保持「对端还在跑」的旁证）。
    * 2026-09-10 换轴：目标字段 = processActivityMs——本戳不参与卡死判据。 */
  private def touchAgentActivity(ctx: ToolContext): IO[Unit] =
    (ctx.sharedResources, ctx.sessionId) match
      case (Some(res), Some(sid)) =>
        val now = System.currentTimeMillis()
        res.agentRegistry.modify { m =>
          m.get(sid) match
            case Some(rec) => (m.updated(sid, rec.copy(processActivityMs = now)), ())
            case None => (m, ())
        }
      case _ => IO.unit

  /** Launch the HTTP call in a detached fiber; notify agent + frontend when done. */
  private def startRemoteBgFiber(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    ctx: ToolContext,
    jobId: String,
    description: String,
    doneRef: Ref[IO, Boolean]
  ): Unit =
    val completionIO =
      executeViaBestPath(peer, toolName, params, BgTimeout, ctx.projectRoot)
        .flatMap {
          case Right(output) => notifyRemoteBgResult(ctx, jobId, description, Right(output))
          case Left(err) => notifyRemoteBgResult(ctx, jobId, description, Left(err.message))
        }
        .flatMap(_ => doneRef.set(true))

    dispatcher.unsafeRunAndForget(
      completionIO.handleErrorWith(e =>
        logger.warn(s"Remote background fiber $jobId crashed: ${e.getMessage}") *>
          emitBgTaskFinished(ctx, jobId, description, "failed") *>
          doneRef.set(true)
      )
    )
  end startRemoteBgFiber

  // ---- WS helpers (match BashTool's backgroundTaskUpdate format) ----

  /**
   * Periodic heartbeat for remote background tasks. Unlike local Bash tasks
   * which have process-level health (alive, output lines), remote tasks only
   * know the HTTP call is still pending. We send runningMs so the frontend
   * duration timer and status dot work correctly.
   */
  private def startRemoteHeartbeat(
    ctx: ToolContext,
    jobId: String,
    description: String,
    doneRef: Ref[IO, Boolean]
  ): IO[Unit] =
    val baseSec = nebflow.shared.Defaults.BgHeartbeatIntervalSec
    val startedAtMs = System.currentTimeMillis()
    def loop: IO[Unit] =
      doneRef.get.flatMap {
        case true => IO.unit
        case false =>
          val now = System.currentTimeMillis()
          val runningMs = now - startedAtMs
          ctx.wsSend.fold(IO.unit) { send =>
            send(
              io.circe.Json.obj(
                "type" -> "backgroundTaskUpdate".asJson,
                "sessionId" -> ctx.sessionId.asJson,
                "rootSessionId" -> ctx.rootSessionId.orElse(ctx.sessionId).asJson,
                "taskId" -> jobId.asJson,
                "description" -> description.asJson,
                "status" -> "running".asJson,
                "heartbeat" -> io.circe.Json.obj(
                  "alive" -> true.asJson,
                  "outputLines" -> 0.asJson,
                  "idleMs" -> runningMs.asJson,
                  "runningMs" -> runningMs.asJson
                )
              )
            ).handleErrorWith(_ => IO.unit)
          } *> IO.sleep(baseSec.seconds) *> loop
      }
    loop.start.void
  end startRemoteHeartbeat

  private def emitBgTaskStarted(ctx: ToolContext, jobId: String, description: String): IO[Unit] =
    val (origin, originLabel) = BgTaskRegistry.originFor(ctx.sessionId.getOrElse(""), ctx.sessionName)
    ctx.wsSend.fold(
      logger.debug(s"Cannot notify frontend for remote background job $jobId: no wsSend")
    )(send =>
      send(
        io.circe.Json.obj(
          "type" -> "backgroundTaskUpdate".asJson,
          "sessionId" -> ctx.sessionId.asJson,
          "rootSessionId" -> ctx.rootSessionId.orElse(ctx.sessionId).asJson,
          "taskId" -> jobId.asJson,
          "description" -> description.asJson,
          "status" -> "running".asJson,
          "startedAt" -> System.currentTimeMillis().asJson,
          "kind" -> "remote".asJson,
          "origin" -> origin.asJson,
          "originLabel" -> originLabel.asJson
        )
      ).handleErrorWith(e => logger.warn(s"WS send failed for remote job $jobId: ${e.getMessage}"))
    )

  private def emitBgTaskFinished(
    ctx: ToolContext,
    jobId: String,
    description: String,
    status: String
  ): IO[Unit] =
    ctx.wsSend.fold(IO.unit)(send =>
      send(
        io.circe.Json.obj(
          "type" -> "backgroundTaskUpdate".asJson,
          "sessionId" -> ctx.sessionId.asJson,
          "rootSessionId" -> ctx.rootSessionId.orElse(ctx.sessionId).asJson,
          "taskId" -> jobId.asJson,
          "description" -> description.asJson,
          "status" -> status.asJson
        )
      ).handleErrorWith(_ => IO.unit)
    )

  /** Notify agent (ExternalEvent) + frontend (WS) of a background task result. */
  private def notifyRemoteBgResult(
    ctx: ToolContext,
    jobId: String,
    description: String,
    result: Either[String, String]
  ): IO[Unit] =
    BgTaskRegistry.unregister(jobId) *>
      (result match
        case Right(output) =>
          ctx.agentActorRef.fold(IO.unit)(ref =>
            ref ! AgentCommand.ExternalEvent(
              source = "background-task",
              eventType = "completed",
              payload = s"[Background task completed] \"$description\":\n$output",
              metadata = JsonObject("description" -> description.asJson, "output" -> output.asJson)
            )
          ) *>
            emitBgTaskFinished(ctx, jobId, description, "completed") *>
            logger.info(s"Remote background task $jobId completed")
        case Left(errMsg) =>
          ctx.agentActorRef.fold(IO.unit)(ref =>
            ref ! AgentCommand.ExternalEvent(
              source = "background-task",
              eventType = "failed",
              payload = s"[Background task failed] \"$description\":\n$errMsg",
              metadata = JsonObject("description" -> description.asJson)
            )
          ) *>
            emitBgTaskFinished(ctx, jobId, description, "failed") *>
            logger.warn(s"Remote background task $jobId failed: $errMsg"))

  // ---- P2P Direct ----

  /**
   * Candidate endpoints for a P2P dispatch (C1). `peer.endpoints` is already
   * preference-ordered (Tailscale → 同网段 → 其余, `EndpointPreference`); peers
   * built without a server namelist (`endpoints = Nil`) fall back to the single
   * `address`, i.e. pre-C1 behaviour.
   */
  private def p2pCandidates(peer: PeerInfo): List[String] =
    val all = if peer.endpoints.nonEmpty then peer.endpoints else List(peer.address)
    all.filter(_.nonEmpty).distinct

  private def p2pExecute(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    timeout: FiniteDuration,
    budget: P2pPathDecision.ProbeBudget
  ): IO[Either[ToolError, String]] =
    for
      selfDeviceId <- neblinkService.identity.map(_.deviceId)
      // C1: 每个候选短超时串行短路，首个成功者即本次直连端点。
      result <- p2pExecuteCandidates(p2pCandidates(peer), peer, selfDeviceId, toolName, params, timeout, budget)
    yield result

  /**
   * Walk the candidate list, short-circuiting on the first success (C1 目标口径
   * §3.3). Only a *connection-level* failure moves on to the next candidate —
   * an HTTP 403/5xx proves the endpoint is up, so hopping would just burn the
   * budget on a peer that is answering.
   */
  private def p2pExecuteCandidates(
    candidates: List[String],
    peer: PeerInfo,
    selfDeviceId: String,
    toolName: String,
    params: JsonObject,
    timeout: FiniteDuration,
    budget: P2pPathDecision.ProbeBudget
  ): IO[Either[ToolError, String]] =
    candidates match
      case Nil =>
        IO.pure(Left(ToolError(s"Cannot reach ${peer.deviceName}: no usable endpoint address")))
      case endpoint :: rest =>
        p2pExecuteAt(peer, endpoint, selfDeviceId, toolName, params, timeout, budget).flatMap {
          case r @ Right(_) => IO.pure(r)
          case Left(err) if rest.nonEmpty && isTransientError(err) =>
            p2pExecuteCandidates(rest, peer, selfDeviceId, toolName, params, timeout, budget)
          case l => IO.pure(l)
        }

  /** One P2P HTTP dispatch against one concrete endpoint. */
  private def p2pExecuteAt(
    peer: PeerInfo,
    endpoint: String,
    selfDeviceId: String,
    toolName: String,
    params: JsonObject,
    timeout: FiniteDuration,
    budget: P2pPathDecision.ProbeBudget
  ): IO[Either[ToolError, String]] =
    IO.blocking {
      val body = io.circe.Json.obj(
        "action" -> toolName.asJson,
        "params" -> params.asJson
      )
      val resp = basicRequest
        .post(sttp.model.Uri.unsafeParse(s"$endpoint/api/neblink/remote-exec"))
        .contentType("application/json")
        .header(nebflow.neblink.Protocol.DeviceHeader, selfDeviceId)
        .body(body.noSpaces)
        .readTimeout(timeout)
        .response(asStringAlways)
        .send(backendFor(budget))

      if !resp.code.isSuccess then
        Left(ToolError(s"Remote device returned HTTP ${resp.code}: ${resp.body.take(200)}"))
      else
        decode[io.circe.Json](resp.body) match
          case Right(json) =>
            val output = json.hcursor.downField("output").as[String].getOrElse("")
            val error = json.hcursor.downField("error").as[String].getOrElse("")
            if error.nonEmpty then Left(ToolError(s"Remote error: $error"))
            else Right(output)
          case Left(err) =>
            Left(ToolError(s"Invalid response from remote: ${err.getMessage}"))
    }.handleErrorWith { e =>
      val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
      val lower = msg.toLowerCase
      if lower.contains("timeout") || lower.contains("timed out") then
        IO.pure(Left(ToolError(s"Command timed out on ${peer.deviceName} after ${timeout.toSeconds}s: $msg")))
      else IO.pure(Left(ToolError(s"Cannot reach ${peer.deviceName} at $endpoint: $msg")))
    }
  end p2pExecuteAt

  /**
   * Wraps p2pExecute with retry logic for transient network failures.
   * Retries up to `budget.maxRetries` times with 1s, 2s ... delays on connection
   * errors only. Does NOT retry on HTTP errors or remote tool execution errors —
   * those indicate the remote device is running but the request itself failed.
   *
   * A: the budget is the caller's (see [[P2pPathDecision.probeBudget]]) — a
   * `directOnline=false` dispatch probes once with a 1.5s connect timeout
   * instead of paying 4 attempts + backoff before the relay fallback.
   */
  private def p2pExecuteWithRetry(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    timeout: FiniteDuration,
    projectRoot: String,
    budget: P2pPathDecision.ProbeBudget
  ): IO[Either[ToolError, String]] =
    def attempt(n: Int): IO[Either[ToolError, String]] =
      p2pExecute(peer, toolName, params, timeout, budget).flatMap {
        case Right(result) => IO.pure(Right(result))
        case Left(err) if n < budget.maxRetries && isTransientError(err) =>
          val delay = (n + 1).seconds
          logger.info(s"Retrying ${peer.deviceName} in ${delay.toSeconds}s (attempt ${n + 1}/${budget.maxRetries})") *>
            IO.sleep(delay) *> attempt(n + 1)
        case Left(err) => IO.pure(Left(err))
      }
    // T4：一次逻辑下发的审计行（在第一次网络尝试前落——重试是同一行下发，
    // 不重复记行；下发本身失败也记，审计关心「试图驱动了哪台设备」）。
    auditDispatch(peer, toolName, params, projectRoot, "p2p") *> attempt(0)

  end p2pExecuteWithRetry

  /**
   * Connection-level failures worth retrying (connection refused, DNS failure).
   * Does NOT match timeout errors — those mean the command is running but slow.
   * Does NOT match HTTP 403 — that is a trust rejection, not a transient
   * network blip, so retrying wastes time (trust state won't change in 1-2s).
   */
  private def isTransientError(err: ToolError): Boolean =
    val msg = err.message.toLowerCase
    msg.startsWith("cannot reach")

  /**
   * HTTP 403 "Not a trusted peer" rejection. The direct P2P path is blocked by
   * the remote's IP trust list (stale discovery, NIC filtering, or DHCP IP
   * change), but the relay authenticates against the NebLink Server with a
   * token — bypassing IP trust entirely — so this is relay-fallback eligible.
   */
  private def isTrustRejection(err: ToolError): Boolean =
    err.message.toLowerCase.contains("remote device returned http 403")

  /** Errors that justify a relay fallback (connection failure or trust rejection). */
  private def shouldRelayFallback(err: ToolError): Boolean =
    isTransientError(err) || isTrustRejection(err)

  /**
   * Best-path execution with performance optimizations:
   *
   * 1. **A — negative-evidence P2P bypass**: P2P is skipped **only** on real
   *    recent failure evidence (relay path memory, or a fresh P2P/dial failure),
   *    never merely because there is no presence WS. `directOnline` is demoted to
   *    a probe-budget hint ([[P2pPathDecision.probeBudget]]).
   * 2. **C4 — address-sensitive, re-probing path memory**: a memory is reusable
   *    only for the same address set and only within 60s, and relay successes do
   *    not slide the window — so P2P is re-probed at least once per TTL.
   * 3. **P1 Parallel race** (read-only tools only): send P2P and Relay concurrently,
   *    use whichever responds first. Safe because read-only tools have no side
   *    effects on cancellation.
   * 4. **Serial fallback** (write tools): P2P first, then Relay on failure.
   *
   * BUG 4 fix: relay fallback includes a cold-start retry (500ms delay + 2nd attempt)
   * to handle WS tunnel reconnection latency.
   */
  private def executeViaBestPath(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    timeout: FiniteDuration,
    projectRoot: String
  ): IO[Either[ToolError, String]] =
    // F1 sweep (2026-09-10 隧道鉴权自愈批): resolve the client per dispatch —
    // never dispatch through a constructor-time snapshot whose session the
    // server may have kicked.
    currentRelayClient.flatMap {
      case None =>
        // No relay available — P2P only. NOTE (A): the probe budget is
        // deliberately NOT downgraded here — `directOnline=false` does not mean
        // the P2P HTTP path is down (presence WS and remote-exec HTTP are
        // different transports), and with no relay there is nothing to fall
        // back to, so retries remain this path's only recovery.
        p2pExecuteWithRetry(peer, toolName, params, timeout, projectRoot, P2pPathDecision.probeBudget(true)).flatMap {
          case r @ Right(_) => recordPathMemory(peer, "p2p").as(r)
          case l @ Left(_)  => IO.pure(l)
        }

      case Some(client) =>
        val relayAvailable = neblinkService.relayTunnelOpt.exists(_.isAlive)
        // Presence-based signal — no longer a decision basis, only a budget hint.
        val directOnline = neblinkService.presenceServiceOpt.exists(_.isConnected(peer.deviceId))
        // A: 负证据驱动 —— 只有「有近期真实 P2P 失败证据」才旁路，否则尝试 P2P。
        val memoryOpt = getPathMemory(peer)
        val recentP2pFailure = hasRecentP2pFailure(peer.deviceId)
        val skipP2p = P2pPathDecision.shouldSkipP2pForRelay(memoryOpt, recentP2pFailure, relayAvailable)
        val budget = P2pPathDecision.probeBudget(directOnline)

        for
          result <-
            if skipP2p then
              // Relay only — with cold-start retry for tunnel reconnection latency
              relayWithColdStartRetry(client, peer, toolName, params, projectRoot).flatMap {
                case Right(output) => recordPathMemory(peer, "relay").as(Right(output))
                case Left(err)     => clearPathMemory(peer.deviceId).as(Left(ToolError(s"Relay failed: $err")))
              }
            else if ReadOnlyTools.contains(toolName) then
              // P1: parallel race for read-only tools (safe cancellation)
              raceP2PAndRelay(peer, toolName, params, timeout, client, projectRoot, budget)
            else
              // Write tools: serial P2P → relay fallback, negative cache as兜底
              p2pExecuteWithRetry(peer, toolName, params, timeout, projectRoot, budget).flatMap {
                case Right(output) => recordPathMemory(peer, "p2p").as(Right(output))
                case Left(err) if shouldRelayFallback(err) =>
                  // A: this failure IS the evidence that justifies bypassing P2P
                  // (and, because relay renewals do not slide, it can expire).
                  recordP2pFailure(peer) *>
                    logger.info(
                      s"P2P unavailable for ${peer.deviceName} (${err.message.take(80)}), falling back to relay"
                    ) *>
                    relayWithColdStartRetry(client, peer, toolName, params, projectRoot).flatMap {
                      case Right(output) => recordPathMemory(peer, "relay").as(Right(output))
                      case Left(relayErr) =>
                        IO.pure(Left(ToolError(s"P2P failed: ${err.message}; Relay also failed: $relayErr")))
                    }
                case Left(err) => IO.pure(Left(err))
              }
        yield result
    }
  end executeViaBestPath

  // ---- T4 (2026-09-11): relay/P2P 远端执行审计 ----

  /**
   * 落一条远端执行审计行（见 [[RelayExecAudit]]）。**每次逻辑下发一次**，在首次
   * 网络尝试前调用（重试不重复记行）。审计失败绝不影响下发：[[RelayExecAudit.record]]
   * 自身吞异常 + WARN，这里再兜一层（取本机身份失败时也不能挡住下发）。
   *
   * 与 dangerLevel 无关——auto-all 下权限卡不出现，正是这一行承担可见性。
   */
  private def auditDispatch(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    projectRoot: String,
    via: String
  ): IO[Unit] =
    neblinkService.identity
      .map(_.deviceId)
      .flatMap(src =>
        RelayExecAudit.record(
          sourceDeviceId = src,
          targetDeviceId = peer.deviceId,
          via = via,
          action = toolName,
          command = RelayExecAudit.summarizeParams(toolName, params),
          projectRoot = projectRoot,
          cwd = Option(System.getProperty("user.dir")).getOrElse("")
        )
      )
      .handleErrorWith(e => logger.warn(s"relay-exec audit line skipped: ${e.getMessage}"))

  /** relay 下发 + 审计（唯一 relay 出口——避免某条分支漏记）。 */
  private def relayExecAudited(
    client: NeblinkClient,
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    projectRoot: String
  ): IO[Either[String, String]] =
    auditDispatch(peer, toolName, params, projectRoot, "relay") *>
      client.relayExec(peer.deviceId, toolName, params)

  // ---- BUG 4: Relay cold-start retry ----

  /**
   * Relay execution with a single cold-start retry.
   *
   * After a relay tunnel reconnects, the first relay_request may time out because
   * the server's routing table hasn't registered the new WS connection yet.
   * A short 150ms delay + second attempt covers this window.
   */
  private def relayWithColdStartRetry(
    client: NeblinkClient,
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    projectRoot: String
  ): IO[Either[String, String]] =
    relayExecAudited(client, peer, toolName, params, projectRoot).flatMap {
      case Right(output) => IO.pure(Right(output))
      case Left(err)     =>
        // Cold start retry — relay tunnel might have just reconnected
        IO.sleep(150.millis) *> client.relayExec(peer.deviceId, toolName, params).map {
          case Right(output)   => Right(output)
          case Left(err2)      => Left(s"$err; $err2 (2 attempts)")
        }
    }

  // ---- P1: Parallel race for read-only tools ----

  /**
   * Race P2P and Relay concurrently for read-only tools. Whichever responds first
   * with a success wins; the loser fiber is cancelled. If the first to finish
   * fails, we wait for the second.
   *
   * Only safe for read-only tools (Read/Glob/Grep) because cancelling a write
   * tool mid-execution could leave the remote filesystem in an inconsistent state.
   */
  private def raceP2PAndRelay(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    timeout: FiniteDuration,
    client: NeblinkClient,
    projectRoot: String,
    budget: P2pPathDecision.ProbeBudget
  ): IO[Either[ToolError, String]] =
    val p2pIO: IO[Either[ToolError, String]] =
      p2pExecuteWithRetry(peer, toolName, params, timeout, projectRoot, budget)
    val relayIO: IO[Either[ToolError, String]] =
      relayExecAudited(client, peer, toolName, params, projectRoot).map {
        case Right(output) => Right(output)
        case Left(err)     => Left(ToolError(s"Relay failed: $err"))
      }

    for
      p2pFiber   <- p2pIO.start
      relayFiber <- relayIO.start
      // Race to see which fiber finishes first (success or failure).
      // IO.race cancels the losing *join* IO but NOT the underlying fiber.
      raceResult <- IO.race(p2pFiber.joinWithNever, relayFiber.joinWithNever)
      result <- raceResult match
        case Left(r @ Right(_)) =>
          // P2P won with success — cancel relay fiber, record memory
          relayFiber.cancel *> recordPathMemory(peer, "p2p").as(r)
        case Left(Left(_)) =>
          // P2P failed first — wait for relay
          relayFiber.joinWithNever.flatMap(r =>
            recordPathMemory(peer, if r.isRight then "relay" else "p2p").as(r)
          )
        case Right(r @ Right(_)) =>
          // Relay won with success — cancel P2P fiber, record memory
          p2pFiber.cancel *> recordPathMemory(peer, "relay").as(r)
        case Right(Left(_)) =>
          // Relay failed first — wait for P2P
          p2pFiber.joinWithNever.flatMap(r =>
            recordPathMemory(peer, if r.isRight then "p2p" else "relay").as(r)
          )
    yield result
  end raceP2PAndRelay

  // ---- Helpers ----

  /**
   * Ensure remote params have a timeout matching the HTTP timeout.
   * Without this, the remote BashTool uses its default 30s timeout,
   * causing premature timeout for long-running remote tasks.
   */
  private def ensureRemoteTimeout(params: JsonObject): JsonObject =
    if params.contains("timeout") then params
    else params.add("timeout", BgTimeout.toMillis.asJson)

  private def resolvePeer(deviceName: String, peers: List[PeerInfo]): Either[ToolError, PeerInfo] =
    peers.find(p =>
      p.deviceName.equalsIgnoreCase(deviceName) ||
        p.deviceId.startsWith(deviceName) ||
        p.deviceName.toLowerCase.contains(deviceName.toLowerCase)
    ) match
      case Some(p) => Right(p)
      case None =>
        val available = peers.map(_.deviceName)
        Left(
          ToolError(
            if peers.isEmpty then
              s"No peer devices discovered after scan. Check: (1) NebLink Server is configured on both machines, (2) Nebflow is running on '$deviceName', (3) both devices are on the same NebLink network."
            else s"Device '$deviceName' not found among ${peers.size} peer(s). Available: ${available.mkString(", ")}"
          )
        )

end RemoteExecutor

object RemoteExecutor:
  private val logger = NebflowLogger.forName("nebflow.remote-executor")

  @volatile private var instance: Option[RemoteExecutor] = None

  /** Wire the RemoteExecutor with a NeblinkService, Dispatcher, and optional relay client. Called on startup. */
  def initialize(
    neblinkService: NeblinkService,
    dispatcher: Dispatcher[IO],
    relayClient: Option[NeblinkClient] = None
  ): Unit =
    instance = Some(new RemoteExecutor(neblinkService, dispatcher, relayClient))

  /** Get the current instance, or None if neblink is not initialized. */
  def current: Option[RemoteExecutor] = instance

  /**
   * Tools that support remote execution. Only these tools get the `device` parameter
   * in their schema. Other tools (Card, AskUser, Delegate, etc.) always run locally.
   */
  val remoteableTools: Set[String] = Set("Bash", "Read", "Write", "Edit", "Glob", "Grep")

  /**
   * Add `device` parameter to a tool's input schema if it's a remoteable tool.
   * Returns the modified schema, or the original if the tool is not remoteable.
   */
  def augmentSchema(toolName: String, schema: JsonObject): JsonObject =
    if !remoteableTools.contains(toolName) then schema
    else
      val props = schema("properties")
        .flatMap(_.asObject)
        .getOrElse(JsonObject.empty)
      props.add(
        "device",
        io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Target device name. Use the device's name from the available devices list. Defaults to local device if omitted.".asJson
        )
      ) match
        case newProps =>
          schema.add("properties", newProps.asJson)

end RemoteExecutor
