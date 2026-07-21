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
 * Core neblink service — NebLink P2P device discovery and cross-device tool execution.
 *
 * Event-driven design:
 *   - IO-based sync loop replaces the old Pekko actor.
 *   - NebLink Server is the trust boundary — no account or cloud relay needed.
 *   - Sync runs continuously; discovery hook is set by GatewayMain at startup.
 */
class NeblinkService private (
  identityRef: Ref[IO, DeviceIdentity],
  configRef: Ref[IO, NeblinkConfig],
  peersRef: Ref[IO, Map[String, PeerInfo]],
  peerDescRef: Ref[IO, Map[String, String]],
  serverPort: Int,
  private val syncQueue: Queue[IO, SyncCommand],
  private val dispatcher: Dispatcher[IO],
  private val peerRemovalGracePeriod: FiniteDuration = 15.seconds
):
  private val logger = NebflowLogger.forName("nebflow.neblink")

  /** IPs of peers discovered via NebLink Server. Trusted for incoming connections. */
  @volatile private var trustedPeerIps: Set[String] = Set.empty

  /** Update trusted peer IPs (from NebLink Server discovery). */
  def updateTrustedIps(ips: Set[String]): IO[Unit] = IO { trustedPeerIps = ips }

  /** Devices scheduled for removal after grace period. Prevents UI flicker from brief WS disconnects. */
  private val pendingRemovals: Ref[IO, Set[String]] =
    Ref.unsafe[IO, Set[String]](Set.empty)

  /** Callbacks fired when a peer goes online/offline. Wired to WsHub.broadcast by GatewayMain. */
  private val peerChangeCallbacks: Ref[IO, List[IO[Unit]]] =
    Ref.unsafe[IO, List[IO[Unit]]](Nil)

  /** Register a callback to fire when peers come or go. */
  def addPeerChangeCallback(cb: IO[Unit]): IO[Unit] =
    peerChangeCallbacks.update(_ :+ cb)

  private def notifyPeersChanged: IO[Unit] =
    peerChangeCallbacks.get.flatMap(_.traverse_(_.handleErrorWith(_ => IO.unit)))

  /** Discovery hook — set to NeblinkDiscovery.discoverCycle at startup. */
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

  /** Update a peer's description locally and persist to disk. */
  def updatePeerDescription(deviceId: String, description: String): IO[Unit] =
    for
      _ <- peersRef.update { peers =>
        peers.get(deviceId) match
          case Some(p) => peers + (deviceId -> p.copy(userDescription = description))
          case None => peers
      }
      updated <- peerDescRef.modify { descs =>
        val next = descs + (deviceId -> description)
        (next, next)
      }
      _ <- PeerDescriptionStore.save(updated)
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

  /** Apply any persisted description override to a peer. */
  private def applyDescOverride(peer: PeerInfo): IO[PeerInfo] =
    peerDescRef.get.map { descs =>
      descs.get(peer.deviceId).filter(_.nonEmpty) match
        case Some(d) => peer.copy(userDescription = d)
        case None => peer
    }

  /** Add or update a single peer. Fires callback only when peer is newly discovered. */
  def upsertPeer(peer: PeerInfo): IO[Unit] =
    for
      _ <- pendingRemovals.update(_ - peer.deviceId)
      isNew <- peersRef.get.map(!_.contains(peer.deviceId))
      finalPeer <- applyDescOverride(peer)
      _ <- peersRef.update(_ + (peer.deviceId -> finalPeer))
      _ <- if isNew then notifyPeersChanged else IO.unit
    yield ()

  /** Schedule peer removal after grace period. If peer reconnects before timeout, removal is cancelled. */
  def removePeer(deviceId: String): IO[Unit] =
    for
      alreadyPending <- pendingRemovals.get.map(_.contains(deviceId))
      hasPeer <- peersRef.get.map(_.contains(deviceId))
      _ <-
        if !alreadyPending && hasPeer then
          for
            _ <- pendingRemovals.update(_ + deviceId)
            _ <- (IO.sleep(peerRemovalGracePeriod) *>
              pendingRemovals
                .modify { pending =>
                  if pending.contains(deviceId) then (pending - deviceId, true)
                  else (pending, false)
                }
                .flatMap { shouldRemove =>
                  if shouldRemove then
                    peersRef
                      .modify { peers =>
                        (peers - deviceId, peers.contains(deviceId))
                      }
                      .flatMap { wasPresent =>
                        if wasPresent then notifyPeersChanged else IO.unit
                      }
                  else IO.unit
                }).start.void
          yield ()
        else IO.unit
    yield ()

  /**
   * Add or update a single peer from an announce push.
   *
   *  Device descriptions are never exchanged between devices — they are purely local
   *  annotations stored in peerDescRef. We apply any saved description here.
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
        for
          _ <- pendingRemovals.update(_ - info.deviceId)
          finalPeer <- applyDescOverride(peer)
          _ <- peersRef.update(_ + (info.deviceId -> finalPeer))
          _ <- logger.debug(s"Peer announced: ${info.deviceName} at ${peer.address}")
        yield ()
    }

  /** Check if an IP is trusted — peer IPs discovered via NebLink Server. */
  def isTrustedPeer(remoteAddr: String): Boolean =
    trustedPeerIps.contains(remoteAddr)

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
    for
      _ <- pendingRemovals.update(_ - deviceId)
      finalPeer <- applyDescOverride(peer)
      _ <- peersRef.update(_ + (deviceId -> finalPeer))
      _ <- logger.info(s"Peer joined: $deviceName at $address")
    yield ()
  end handleHandshake

  // ===== Sync =====

  /** Run one sync cycle: NebLink discovery. */
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

  // ===== File Transfer (P2P, peer IP auth) =====

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
    createInternal(serverPort, dispatcher, 15.seconds)

  /** Test-only factory with custom grace period. */
  private[neblink] def createForTest(
    serverPort: Int,
    dispatcher: Dispatcher[IO],
    gracePeriod: FiniteDuration
  ): IO[NeblinkService] =
    createInternal(serverPort, dispatcher, gracePeriod)

  private def createInternal(
    serverPort: Int,
    dispatcher: Dispatcher[IO],
    gracePeriod: FiniteDuration
  ): IO[NeblinkService] =
    for
      identity <- DeviceIdentity.loadOrCreate
      config <- NeblinkConfig.load
      peerDescs <- PeerDescriptionStore.load
      idRef <- Ref.of[IO, DeviceIdentity](identity)
      cfgRef <- Ref.of[IO, NeblinkConfig](config)
      peersRef <- Ref.of[IO, Map[String, PeerInfo]](Map.empty)
      descRef <- Ref.of[IO, Map[String, String]](peerDescs)
      syncQueue <- Queue.unbounded[IO, SyncCommand]
      service = new NeblinkService(idRef, cfgRef, peersRef, descRef, serverPort, syncQueue, dispatcher, gracePeriod)
      // Start sync loop — NebLink Server is the trust boundary, no login needed.
      _ = dispatcher.unsafeRunAndForget(service.startSyncLoop)
    yield service

end NeblinkService
