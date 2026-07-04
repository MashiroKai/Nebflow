package nebflow.mesh

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
  private[mesh] case object SyncTick extends SyncCommand

  /** A peer was discovered — wake up the sync loop for an immediate cycle. */
  case object PeerDiscovered extends SyncCommand
end SyncCommand

/**
 * Core mesh service — Tailscale P2P device discovery and cross-device tool execution.
 *
 * Event-driven design:
 *   - IO-based sync loop replaces the old Pekko actor.
 *   - Tailscale is the trust boundary — no account or cloud relay needed.
 *   - Sync runs continuously; discovery hook is set by GatewayMain at startup.
 */
class MeshService private (
  identityRef: Ref[IO, DeviceIdentity],
  configRef: Ref[IO, MeshConfig],
  peersRef: Ref[IO, Map[String, PeerInfo]],
  serverPort: Int,
  private val syncQueue: Queue[IO, SyncCommand],
  private val dispatcher: Dispatcher[IO]
):
  private val logger = NebflowLogger.forName("nebflow.mesh")

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

  def meshConfig: IO[MeshConfig] = configRef.get

  def updateConfig(fn: MeshConfig => MeshConfig): IO[Unit] =
    configRef
      .modify { cfg =>
        val u = fn(cfg); (u, u)
      }
      .flatMap(MeshConfig.save)

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
      if peers.contains(deviceId) then
        peersRef.update(_ - deviceId) *> notifyPeersChanged
      else IO.unit
    }

  /** Add or update a single peer from an announce push. */
  def handleAnnounce(info: DeviceDiscoveryInfo, remoteIp: String, port: Int): IO[Unit] =
    identityRef.get.flatMap { id =>
      if info.deviceId == id.deviceId then IO.unit // ignore self-announce
      else
        val peer = PeerInfo(
          deviceId = info.deviceId,
          deviceName = info.deviceName,
          platform = info.platform,
          address = s"http://$remoteIp:$port",
          capabilities = info.capabilities,
          userDescription = info.userDescription
        )
        peersRef.update(_ + (info.deviceId -> peer)) *>
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

end MeshService

object MeshService:
  private val logger = NebflowLogger.forName("nebflow.mesh")

  def create(
    serverPort: Int = 8080,
    dispatcher: Dispatcher[IO]
  ): IO[MeshService] =
    for
      identity <- DeviceIdentity.loadOrCreate
      config <- MeshConfig.load
      idRef <- Ref.of[IO, DeviceIdentity](identity)
      cfgRef <- Ref.of[IO, MeshConfig](config)
      peersRef <- Ref.of[IO, Map[String, PeerInfo]](Map.empty)
      syncQueue <- Queue.unbounded[IO, SyncCommand]
      service = new MeshService(idRef, cfgRef, peersRef, serverPort, syncQueue, dispatcher)
      // Start sync loop — Tailscale is the trust boundary, no login needed.
      _ = dispatcher.unsafeRunAndForget(service.startSyncLoop)
      _ = dispatcher.unsafeRunAndForget(
        service.selfCheckCapabilities.handleErrorWith(e => logger.debug(s"Capability detection: ${e.getMessage}"))
      )
    yield service

end MeshService
