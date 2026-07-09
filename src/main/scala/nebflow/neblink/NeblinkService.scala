package nebflow.neblink

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import sttp.client4.*

import scala.concurrent.duration.*

// ============================================================
// Sync command protocol — public so callers can send commands
// ============================================================

sealed trait SyncCommand

object SyncCommand:
  /** Start periodic sync. */
  case object StartSync extends SyncCommand

  /** Stop periodic sync. */
  case object StopSync extends SyncCommand

  /** Periodic sync timer fired. */
  private[neblink] case object SyncTick extends SyncCommand

  /** A peer was discovered — wake up the sync loop for an immediate cycle. */
  case object PeerDiscovered extends SyncCommand
end SyncCommand

/**
 * Core neblink service — Tailscale P2P device discovery and cross-device tool execution.
 *
 * Event-driven design:
 *   - IO-based sync loop replaces the old Pekko actor.
 *   - Tailscale is the trust boundary — no account or cloud relay needed.
 *   - Sync runs continuously; discovery hook is set by GatewayMain at startup.
 */
class NeblinkService private (
  identityRef: Ref[IO, DeviceIdentity],
  configRef: Ref[IO, NeblinkConfig],
  peersRef: Ref[IO, Map[String, PeerInfo]],
  serverPort: Int,
  private val syncQueue: Queue[IO, SyncCommand],
  private val dispatcher: Dispatcher[IO]
):
  private val logger = NebflowLogger.forName("nebflow.neblink")

  /** Callbacks fired when a peer goes online/offline. Wired to WsHub.broadcast by GatewayMain. */
  private val peerChangeCallbacks: Ref[IO, List[IO[Unit]]] =
    Ref.unsafe[IO, List[IO[Unit]]](Nil)

  /** Register a callback to fire when peers come or go. */
  def addPeerChangeCallback(cb: IO[Unit]): IO[Unit] =
    peerChangeCallbacks.update(_ :+ cb)

  private def notifyPeersChanged: IO[Unit] =
    peerChangeCallbacks.get.flatMap(_.traverse_(_.handleErrorWith(_ => IO.unit)))

  /** Discovery hook — set to TailscaleDiscovery.discoverCycle at startup. */
  private val discoveryHookRef: Ref[IO, IO[Unit]] = Ref.unsafe[IO, IO[Unit]](IO.unit)

  /** Set the discovery hook (called periodically by the sync loop). */
  def setDiscoveryHook(hook: IO[Unit]): IO[Unit] = discoveryHookRef.set(hook)

  /** Set diagnostic function for the scan endpoint. */
  private val diagnosticRef: Ref[IO, IO[io.circe.Json]] =
    Ref.unsafe[IO, IO[io.circe.Json]](IO.pure(io.circe.Json.obj("error" -> "not configured".asJson)))
  def setDiagnostic(fn: IO[io.circe.Json]): IO[Unit] = diagnosticRef.set(fn)
  def diagnosticScan: IO[io.circe.Json] = diagnosticRef.get.flatten

  // ===== Identity =====

  def identity: IO[DeviceIdentity] = identityRef.get

  /** Update device capabilities and/or user description. Persists to disk. */
  def updateDeviceInfo(
    capabilities: Option[Map[String, String]] = None,
    userDescription: Option[String] = None
  ): IO[Unit] =
    for
      updated <- identityRef.modify { id =>
        val newId = id.copy(
          capabilities = capabilities.getOrElse(id.capabilities),
          userDescription = userDescription.getOrElse(id.userDescription)
        )
        (newId, newId)
      }
      _ <- DeviceIdentity.save(updated)
    yield ()

  /** Update a peer's description locally. Not persisted — cleared on restart. */
  def updatePeerDescription(deviceId: String, description: String): IO[Unit] =
    peersRef.update { peers =>
      peers.get(deviceId) match
        case Some(p) => peers + (deviceId -> p.copy(userDescription = description))
        case None => peers
    }

  /** Run capability self-check and update device identity. Called on startup. */
  def selfCheckCapabilities: IO[Unit] =
    for
      caps <- DeviceIdentity.detectCapabilities
      id <- identityRef.get
      _ <- if id.capabilities != caps then updateDeviceInfo(capabilities = Some(caps)) else IO.unit
    yield ()

  /** Send a command to the sync loop. */
  def sendSync(cmd: SyncCommand): IO[Unit] = syncQueue.offer(cmd)

  /** Launch the background sync loop. Call once at startup. */
  def startSyncLoop: IO[Unit] = syncLoop(true)

  // ===== Config =====

  def neblinkConfig: IO[NeblinkConfig] = configRef.get

  def updateConfig(fn: NeblinkConfig => NeblinkConfig): IO[Unit] =
    configRef
      .modify { cfg =>
        val u = fn(cfg); (u, u)
      }
      .flatMap(NeblinkConfig.save)

  // ===== Peers =====

  def peers: IO[List[PeerInfo]] = peersRef.get.map(_.values.toList)

  /** Add or update a single peer. Fires callback only when peer is newly discovered. */
  def upsertPeer(peer: PeerInfo): IO[Unit] =
    for
      isNew <- peersRef.get.map(!_.contains(peer.deviceId))
      _ <- peersRef.update(_ + (peer.deviceId -> peer))
      _ <- if isNew then notifyPeersChanged else IO.unit
    yield ()

  /** Remove a peer when its WS presence connection drops. Fires callback. */
  def removePeer(deviceId: String): IO[Unit] =
    peersRef.get.flatMap { peers =>
      if peers.contains(deviceId) then peersRef.update(_ - deviceId) *> notifyPeersChanged
      else IO.unit
    }

  /** Add or update a single peer from an announce push.
   *
   *  Device descriptions are never exchanged between devices — they are purely local
   *  annotations. We only preserve any description the local user may have set.
   */
  def handleAnnounce(info: DeviceDiscoveryInfo, remoteIp: String, port: Int): IO[Unit] =
    identityRef.get.flatMap { id =>
      if info.deviceId == id.deviceId then IO.unit // ignore self-announce
      else
        val peer = PeerInfo(
          deviceId = info.deviceId,
          deviceName = info.deviceName,
          platform = info.platform,
          address = s"http://$remoteIp:$port",
          capabilities = info.capabilities
        )
        peersRef.update { peers =>
          val existingDesc = peers.get(info.deviceId).flatMap(p => Option(p.userDescription).filter(_.nonEmpty))
          val finalPeer = existingDesc match
            case Some(d) => peer.copy(userDescription = d)
            case None => peer
          peers + (info.deviceId -> finalPeer)
        } *>
          logger.debug(s"Peer announced: ${info.deviceName} at ${peer.address}")
    }

  /** Check if an IP belongs to the Tailscale CGNAT range (100.64.0.0/10). */
  def isTailscalePeer(remoteAddr: String): Boolean =
    try
      val parts = remoteAddr.split("\\.")
      if parts.length == 4 then
        val first = parts(0).toInt
        val second = parts(1).toInt
        first == 100 && second >= 64 && second <= 127
      else false
    catch case _: Exception => false

  /** Handle an incoming handshake from a peer. Called by the REST endpoint. */
  def handleHandshake(
    deviceId: String,
    deviceName: String,
    platform: String,
    callerIp: String,
    port: Int,
    callerSecret: String = ""
  ): IO[Unit] =
    val address = s"http://$callerIp:$port"
    val peer = PeerInfo(deviceId, deviceName, platform, address, callerSecret)
    peersRef.update(_ + (deviceId -> peer)) *>
      logger.info(s"Peer joined: $deviceName at $address")

  // ===== Sync =====

  /** Run one sync cycle: Tailscale discovery. */
  def runSyncCycle: IO[Unit] = discoveryHookRef.get.flatten

  /** Trigger discovery immediately and return current peers. */
  def scanNow: IO[List[PeerInfo]] =
    discoveryHookRef.get.flatten *> peers

  // ===== Shared HTTP backend (used by RemoteExecutor for P2P tool calls) =====

  private[nebflow] lazy val httpBackend = DefaultSyncBackend()

  // ===== Data Channel =====

  /** Handlers for incoming WS data messages from peers. */
  private val dataHandlers: Ref[IO, List[Json => IO[Unit]]] =
    Ref.unsafe[IO, List[Json => IO[Unit]]](Nil)

  /** Register a handler for incoming data messages from peers. */
  def addDataHandler(handler: Json => IO[Unit]): IO[Unit] =
    dataHandlers.update(_ :+ handler)

  /** Forward an incoming WS data message to all registered handlers. */
  private[nebflow] def handleDataMessage(payload: Json): IO[Unit] =
    dataHandlers.get.flatMap(
      _.traverse_(_.apply(payload).handleErrorWith(e => logger.debug(s"Data handler error: ${e.getMessage}")))
    )

  /**
   * Send function for outgoing WS data messages. Set by GatewayMain after
   * NeblinkPresenceService is created. Default is a no-op so callers are safe
   * before wiring is complete.
   */
  private val sendDataFnRef: Ref[IO, (String, String, Json) => IO[Unit]] =
    Ref.unsafe[IO, (String, String, Json) => IO[Unit]]((_, _, _) => IO.unit)

  /** Wire the send function (called once at startup by GatewayMain). */
  def setSendDataFn(fn: (String, String, Json) => IO[Unit]): IO[Unit] =
    sendDataFnRef.set(fn)

  /** Send a data message to a peer over the WS presence connection. */
  def sendData(deviceId: String, channel: String, payload: Json): IO[Unit] =
    sendDataFnRef.get.flatMap(_(deviceId, channel, payload))

  // ===== File Transfer (P2P, Tailscale IP auth) =====

  /**
   * Validate that a relative path is safe — no traversal, no absolute paths.
   * Returns the normalized os.RelPath, or an error message.
   */
  private def validateTransferPath(relPath: String): Either[String, os.RelPath] =
    val normalized = java.nio.file.Paths.get(relPath).normalize
    if normalized.startsWith("..") || normalized.isAbsolute then
      Left(s"Invalid path: $relPath (must be relative, no .. traversal)")
    else
      try Right(os.RelPath(normalized.toString))
      catch case _: Exception => Left(s"Invalid path: $relPath")

  /** Receive a file pushed by a peer. Writes under project root. Returns bytes written. */
  def receiveFile(relPath: String, content: Array[Byte], overwrite: Boolean): IO[Long] =
    validateTransferPath(relPath) match
      case Left(err) => IO.raiseError(new IllegalArgumentException(err))
      case Right(rel) =>
        val dest = os.pwd / rel
        if !overwrite && os.exists(dest) then
          IO.raiseError(new IllegalArgumentException(s"File already exists: $relPath (use overwrite=true to replace)"))
        else
          IO.blocking {
            os.write(dest, content, createFolders = true)
            content.length.toLong
          }

  /** Read a file for a peer that requested it. Returns None if file doesn't exist or path invalid. */
  def sendFile(relPath: String): IO[Option[Array[Byte]]] =
    validateTransferPath(relPath) match
      case Left(_) => IO.pure(None)
      case Right(rel) =>
        IO.blocking {
          val file = os.pwd / rel
          if os.exists(file) && os.isFile(file) then Some(os.read.bytes(file))
          else None
        }

  // ============================================================
  // Sync loop — event-driven, no polling when idle
  // ============================================================

  private def syncLoop(running: Boolean): IO[Unit] =
    for
      _ <-
        if running then runSyncCycle.handleErrorWith(e => logger.warn(s"Sync cycle failed: ${e.getMessage}").void)
        else IO.unit
      interval <- configRef.get.map(_.syncIntervalSec.max(10).seconds)
      sleepIO: IO[Unit] = if running then IO.sleep(interval) else IO.never
      result <- IO.race(sleepIO, syncQueue.take)
      nextRunning = result match
        case Left(_) => running
        case Right(cmd) => processSyncCommand(cmd, running)
      _ <- syncLoop(nextRunning)
    yield ()

  private def processSyncCommand(cmd: SyncCommand, currentlyRunning: Boolean): Boolean =
    cmd match
      case SyncCommand.StartSync => true
      case SyncCommand.StopSync => false
      case SyncCommand.PeerDiscovered => currentlyRunning // just wake up the loop
      case SyncCommand.SyncTick => currentlyRunning

end NeblinkService

object NeblinkService:
  private val logger = NebflowLogger.forName("nebflow.neblink")

  def create(
    serverPort: Int = 8080,
    dispatcher: Dispatcher[IO]
  ): IO[NeblinkService] =
    for
      identity <- DeviceIdentity.loadOrCreate
      config <- NeblinkConfig.load
      idRef <- Ref.of[IO, DeviceIdentity](identity)
      cfgRef <- Ref.of[IO, NeblinkConfig](config)
      peersRef <- Ref.of[IO, Map[String, PeerInfo]](Map.empty)
      syncQueue <- Queue.unbounded[IO, SyncCommand]
      service = new NeblinkService(idRef, cfgRef, peersRef, serverPort, syncQueue, dispatcher)
      // Start sync loop — Tailscale is the trust boundary, no login needed.
      _ = dispatcher.unsafeRunAndForget(service.startSyncLoop)
      _ = dispatcher.unsafeRunAndForget(
        service.selfCheckCapabilities.handleErrorWith(e => logger.debug(s"Capability detection: ${e.getMessage}"))
      )
    yield service

end NeblinkService
