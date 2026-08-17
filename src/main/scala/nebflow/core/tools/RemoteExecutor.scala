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

  /** Foreground remote calls that exceed this are automatically moved to background. */
  private val AutoBgThreshold = 30.seconds

  /** Timeout for synchronous remote calls without ToolContext (fallback path). */
  private val SyncTimeout = 120.seconds

  /** Timeout for background remote calls — the HTTP call waits up to this long. */
  private val BgTimeout = 3600.seconds

  // ---- P0-3: Path memory — remember last successful transport per device ----

  /** Map: deviceId → (method, timestamp). TTL-locked to avoid stale relay bias. */
  @volatile private var lastSuccessPath: Map[String, (String, Long)] = Map.empty
  private val PathMemoryTtlMs = 5.minutes.toMillis

  /** Read cached transport method if fresh, else None (and clean up expired entry). */
  private def getPathMemory(deviceId: String): Option[String] =
    lastSuccessPath.get(deviceId) match
      case Some((method, ts)) if System.currentTimeMillis() - ts < PathMemoryTtlMs => Some(method)
      case Some(_) =>
        lastSuccessPath = lastSuccessPath.removed(deviceId)
        None
      case None => None

  private def recordPathMemory(deviceId: String, method: String): IO[Unit] = IO {
    lastSuccessPath = lastSuccessPath.updated(deviceId, (method, System.currentTimeMillis()))
  }

  private def clearPathMemory(deviceId: String): IO[Unit] = IO {
    lastSuccessPath = lastSuccessPath.removed(deviceId)
  }

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

    def runOnPeer(peer: PeerInfo): IO[Either[ToolError, String]] =
      if peer.address.isEmpty then IO.pure(Left(ToolError(s"Device '${peer.deviceName}' has no address.")))
      else if isBackground && ctxOpt.isDefined then executeRemoteBackground(peer, toolName, params, ctxOpt.get)
      else if ctxOpt.isDefined then executeForegroundWithAutoBackground(peer, toolName, params, ctxOpt.get)
      else executeViaBestPath(peer, toolName, params, SyncTimeout)

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
   * 1. Strip `run_in_background` so KAI executes synchronously
   * 2. Emit "running" indicator to frontend
   * 3. Start a detached fiber that does the synchronous HTTP call to KAI
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

    for
      jobId <- IO.randomUUID.map(_.toString.take(8))
      _ <- logger.info(s"Remote background task $jobId started on ${peer.deviceName}: $firstLine")
      // 1. Emit "running" to frontend + register in global registry
      _ <- emitBgTaskStarted(ctx, jobId, description)
      _ <- BgTaskRegistry.register(jobId, ctx.sessionId.getOrElse(""), description, "remote")
      // 2. Start heartbeat so frontend shows progress (remote tasks have no process-level health)
      doneRef <- IO.ref(false)
      _ <- startRemoteHeartbeat(ctx, jobId, description, doneRef)
      // 3. Start detached fiber — synchronous HTTP to KAI, then notify on completion
      _ <- IO(startRemoteBgFiber(peer, toolName, remoteParams, ctx, jobId, description, doneRef))
    yield Right(
      s"[Background job started] Job ID: $jobId\nThe command is running in the background on ${peer.deviceName}. You will be automatically notified when it finishes — continue with other work or finish your turn."
    )

  end executeRemoteBackground

  /**
   * Execute a remote command in the foreground with an auto-background threshold.
   * Mirrors BashTool's executeForegroundWithAutoBackground: if the HTTP call
   * completes within the threshold, return the result directly; otherwise,
   * the HTTP call continues running and the agent is notified on completion.
   */
  private def executeForegroundWithAutoBackground(
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
      resultRef <- IO.ref[Option[Either[ToolError, String]]](None)
      signal <- Deferred[IO, Unit]
      thresholdWon <- IO.ref(false)
      jobId <- IO.randomUUID.map(_.toString.take(8))

      // HTTP call — never cancelled, runs to completion on the remote device
      commandFiber <- (for
        r <- executeViaBestPath(peer, toolName, remoteParams, BgTimeout)
        _ <- resultRef.set(Some(r))
        _ <- signal.complete(()).void
      yield ()).start

      // Threshold timer
      thresholdFiber <- (for
        _ <- IO.sleep(AutoBgThreshold)
        _ <- thresholdWon.set(true)
        _ <- signal.complete(()).void
      yield ()).start

      // Wait for whichever finishes first
      _ <- signal.get
      didWin <- thresholdWon.get
      resultOpt <- resultRef.get

      _ <-
        if didWin then
          // Command still running — convert to background
          logger.info(
            s"Remote command on ${peer.deviceName} exceeded ${AutoBgThreshold.toSeconds}s, moving to background (job $jobId)"
          ) *>
            emitBgTaskStarted(ctx, jobId, description) *>
            BgTaskRegistry.register(jobId, ctx.sessionId.getOrElse(""), description, "remote") *>
            (for
              doneRef <- IO.ref(false)
              _ <- startRemoteHeartbeat(ctx, jobId, description, doneRef)
              _ <- commandFiber.joinWithNever
              r <- resultRef.get
              _ <- r match
                case Some(Right(output)) =>
                  notifyRemoteBgResult(ctx, jobId, description, Right(output))
                case Some(Left(err)) =>
                  notifyRemoteBgResult(ctx, jobId, description, Left(err.message))
                case None => IO.unit
              _ <- doneRef.set(true)
            yield ()).start.void
        else thresholdFiber.cancel
    yield
      if !didWin then resultOpt.getOrElse(Left(ToolError("[Unexpected: no result from remote command]")))
      else
        Right(
          s"[Remote command moved to background] Job ID: $jobId\n" +
            s"The command has been running on ${peer.deviceName} for over ${AutoBgThreshold.toSeconds}s " +
            "and will continue in the background. You will be automatically notified when it finishes — " +
            "continue with other work or finish your turn."
        )
    end for
  end executeForegroundWithAutoBackground

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
      executeViaBestPath(peer, toolName, params, BgTimeout)
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
    ctx.wsSend.fold(
      logger.debug(s"Cannot notify frontend for remote background job $jobId: no wsSend")
    )(send =>
      send(
        io.circe.Json.obj(
          "type" -> "backgroundTaskUpdate".asJson,
          "sessionId" -> ctx.sessionId.asJson,
          "taskId" -> jobId.asJson,
          "description" -> description.asJson,
          "status" -> "running".asJson,
          "startedAt" -> System.currentTimeMillis().asJson
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

  private def p2pExecute(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    timeout: FiniteDuration
  ): IO[Either[ToolError, String]] =
    for
      // Send our own deviceId so the peer can authenticate us by network
      // membership (same networkId) when its IP-based trust list is stale —
      // e.g. after a NIC filter change or DHCP address rotation.
      selfDeviceId <- neblinkService.identity.map(_.deviceId)
      result <- IO.blocking {
        val body = io.circe.Json.obj(
          "action" -> toolName.asJson,
          "params" -> params.asJson
        )
        val resp = basicRequest
          .post(sttp.model.Uri.unsafeParse(s"${peer.address}/api/neblink/remote-exec"))
          .contentType("application/json")
          .header(nebflow.neblink.Protocol.DeviceHeader, selfDeviceId)
          .body(body.noSpaces)
          .readTimeout(timeout)
          .response(asStringAlways)
          .send(neblinkService.httpBackend)

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
        else IO.pure(Left(ToolError(s"Cannot reach ${peer.deviceName} at ${peer.address}: $msg")))
      }
    yield result
  end p2pExecute

  /**
   * Wraps p2pExecute with retry logic for transient network failures.
   * Retries up to 3 times with 1s, 2s delays on connection errors only.
   * Does NOT retry on HTTP errors or remote tool execution errors — those
   * indicate the remote device is running but the request itself failed.
   */
  private def p2pExecuteWithRetry(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    timeout: FiniteDuration,
    maxRetries: Int = 3
  ): IO[Either[ToolError, String]] =
    def attempt(n: Int): IO[Either[ToolError, String]] =
      p2pExecute(peer, toolName, params, timeout).flatMap {
        case Right(result) => IO.pure(Right(result))
        case Left(err) if n < maxRetries && isTransientError(err) =>
          val delay = (n + 1).seconds
          logger.info(s"Retrying ${peer.deviceName} in ${delay.toSeconds}s (attempt ${n + 1}/$maxRetries)") *>
            IO.sleep(delay) *> attempt(n + 1)
        case Left(err) => IO.pure(Left(err))
      }
    attempt(0)

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
   * 1. **P0-3 Path memory**: if relay was the last successful transport for this
   *    device (within 5 min TTL), skip P2P entirely.
   * 2. **Presence check**: if there is no active presence WS connection to the
   *    peer (cross-network), P2P is impossible — skip to relay (fast-fail with
   *    a clear error if the relay tunnel is also down).
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
    timeout: FiniteDuration
  ): IO[Either[ToolError, String]] =
    relayClient match
      case None =>
        // No relay available — P2P only
        p2pExecuteWithRetry(peer, toolName, params, timeout).flatMap {
          case r @ Right(_) => recordPathMemory(peer.deviceId, "p2p").as(r)
          case l @ Left(_)  => IO.pure(l)
        }

      case Some(client) =>
        // P0-3: check path memory — skip P2P if relay was last success
        val memoryOpt = getPathMemory(peer.deviceId)
        // Presence-based P2P check: skip P2P if no active presence WS to peer
        val directOnline = neblinkService.presenceServiceOpt.exists(_.isConnected(peer.deviceId))
        val relayAvailable = neblinkService.relayTunnelOpt.exists(_.isAlive)
        val skipP2p = memoryOpt.contains("relay") || !directOnline

        for
          result <-
            if skipP2p then
              if !relayAvailable then
                IO.pure(
                  Left(
                    ToolError(
                      s"Device ${peer.deviceName} is unreachable: no direct connection and relay tunnel is down"
                    )
                  )
                )
              else
                // Relay only — with cold-start retry for tunnel reconnection latency
                relayWithColdStartRetry(client, peer, toolName, params).flatMap {
                  case Right(output) => recordPathMemory(peer.deviceId, "relay").as(Right(output))
                  case Left(err)     => clearPathMemory(peer.deviceId).as(Left(ToolError(s"Relay failed: $err")))
                }
            else if ReadOnlyTools.contains(toolName) then
              // P1: parallel race for read-only tools (safe cancellation)
              raceP2PAndRelay(peer, toolName, params, timeout, client)
            else
              // Write tools: serial P2P → relay fallback
              p2pExecuteWithRetry(peer, toolName, params, timeout).flatMap {
                case Right(output) => recordPathMemory(peer.deviceId, "p2p").as(Right(output))
                case Left(err) if shouldRelayFallback(err) =>
                  logger.info(
                    s"P2P unavailable for ${peer.deviceName} (${err.message.take(80)}), falling back to relay"
                  ) *>
                    relayWithColdStartRetry(client, peer, toolName, params).flatMap {
                      case Right(output) => recordPathMemory(peer.deviceId, "relay").as(Right(output))
                      case Left(relayErr) =>
                        IO.pure(Left(ToolError(s"P2P failed: ${err.message}; Relay also failed: $relayErr")))
                    }
                case Left(err) => IO.pure(Left(err))
              }
        yield result
  end executeViaBestPath

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
    params: JsonObject
  ): IO[Either[String, String]] =
    client.relayExec(peer.deviceId, toolName, params).flatMap {
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
    client: NeblinkClient
  ): IO[Either[ToolError, String]] =
    val p2pIO: IO[Either[ToolError, String]] = p2pExecuteWithRetry(peer, toolName, params, timeout)
    val relayIO: IO[Either[ToolError, String]] =
      client.relayExec(peer.deviceId, toolName, params).map {
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
          relayFiber.cancel *> recordPathMemory(peer.deviceId, "p2p").as(r)
        case Left(Left(_)) =>
          // P2P failed first — wait for relay
          relayFiber.joinWithNever.flatMap(r =>
            recordPathMemory(peer.deviceId, if r.isRight then "relay" else "p2p").as(r)
          )
        case Right(r @ Right(_)) =>
          // Relay won with success — cancel P2P fiber, record memory
          p2pFiber.cancel *> recordPathMemory(peer.deviceId, "relay").as(r)
        case Right(Left(_)) =>
          // Relay failed first — wait for P2P
          p2pFiber.joinWithNever.flatMap(r =>
            recordPathMemory(peer.deviceId, if r.isRight then "p2p" else "relay").as(r)
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
