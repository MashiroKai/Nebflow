package nebflow.mesh

import cats.effect.{IO, Ref, Temporal}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.core.NebflowLogger
import sttp.client4.*

import scala.concurrent.duration.*

/**
 * Core mesh service — token-based pairing, UDP discovery, direct peer connections.
 *
 * All device-to-device communication goes over direct HTTP with Bearer token auth.
 * No cloud relay or cloud sync — the cloud is only used for discovery (Phase 3).
 */
class MeshService private (
  identityRef: Ref[IO, DeviceIdentity],
  configRef: Ref[IO, MeshConfig],
  peersRef: Ref[IO, Map[String, PeerInfo]],
  syncStore: MeshSyncStore,
  udpDiscovery: UdpDiscovery,
  serverPort: Int
):
  private val logger = NebflowLogger.forName("nebflow.mesh")

  // ===== Identity =====

  def identity: IO[DeviceIdentity] = identityRef.get

  def isPaired: IO[Boolean] =
    identityRef.get.map(_.groupId.isDefined)

  /** Shared token (= groupId) for this device. */
  def groupId: IO[Option[String]] =
    identityRef.get.map(_.groupId)

  // ===== Pairing =====

  /** Pair with a shared token. Starts UDP broadcast discovery. */
  def pair(token: String): IO[Unit] =
    for
      _ <- IO.raiseWhen(token.length < 6)(new RuntimeException("Token must be at least 6 characters"))
      id <- identityRef.get
      updated = id.copy(groupId = Some(token))
      _ <- DeviceIdentity.save(updated)
      _ <- identityRef.set(updated)
      hash = UdpDiscovery.hashToken(token)
      _ <- udpDiscovery.setTokenHash(Some(hash))
      _ <- configRef.update(_.copy(enabled = true))
      _ <- MeshConfig.save(MeshConfig(enabled = true))
      _ <- logger.info(s"Paired with token, hash=${hash.take(12)}...")
    yield ()

  /** Leave — clear token, stop discovery, clear peers. */
  def leaveGroup: IO[Unit] =
    for
      id <- identityRef.get
      _ <- DeviceIdentity.save(id.copy(groupId = None))
      _ <- identityRef.set(id.copy(groupId = None))
      _ <- udpDiscovery.setTokenHash(None)
      _ <- peersRef.set(Map.empty)
      _ <- configRef.update(_.copy(enabled = false))
      _ <- MeshConfig.save(MeshConfig(enabled = false))
      _ <- logger.info("Left mesh group")
    yield ()

  // ===== Config =====

  def meshConfig: IO[MeshConfig] = configRef.get

  def updateConfig(fn: MeshConfig => MeshConfig): IO[Unit] =
    configRef
      .modify { cfg =>
        val updated = fn(cfg)
        (updated, updated)
      }
      .flatMap(updated => MeshConfig.save(updated))

  // ===== Peers =====

  def peers: IO[List[PeerInfo]] = peersRef.get.map(_.values.toList)

  /** Handle an incoming handshake from a peer. Called by the REST endpoint. */
  def handleHandshake(
    callerToken: String,
    deviceId: String,
    deviceName: String,
    platform: String,
    callerIp: String,
    port: Int
  ): IO[Unit] =
    for
      id <- identityRef.get
      gid <- id.groupId match
        case Some(g) => IO.pure(g)
        case None => IO.raiseError(new RuntimeException("Not paired"))
      _ <- IO.raiseWhen(callerToken != gid)(new RuntimeException("Token mismatch"))
      address = s"http://$callerIp:$port"
      peer = PeerInfo(deviceId, deviceName, platform, address)
      _ <- peersRef.update(_ + (deviceId -> peer))
      _ <- logger.info(s"Peer joined: $deviceName at $address")
    yield ()

  /** Called by UdpDiscovery when a matching broadcast is heard. Initiates handshake. */
  private def onPeerDiscovered(address: String, port: Int): IO[Unit] =
    for
      id <- identityRef.get
      gidOpt = id.groupId
      _ <- gidOpt match
        case Some(gid) => sendHandshake(address, gid, id)
        case None => IO.unit
    yield ()

  private def sendHandshake(address: String, token: String, id: DeviceIdentity): IO[Unit] =
    IO.blocking {
      val body = Json.obj(
        "deviceId" -> id.deviceId.asJson,
        "deviceName" -> id.deviceName.asJson,
        "platform" -> id.platform.asJson,
        "port" -> serverPort.asJson
      )
      val resp = basicRequest
        .post(sttp.model.Uri.unsafeParse(s"$address/api/mesh/handshake"))
        .auth.bearer(token)
        .contentType("application/json")
        .body(body.noSpaces)
        .readTimeout(10.seconds)
        .response(asStringAlways)
        .send(httpBackend)

      if resp.code.isSuccess then ()
      else ()
    }.handleErrorWith(e => logger.debug(s"Handshake to $address failed: ${e.getMessage}"))

  // ===== Sync (Phase 2) =====

  def computeLocalFingerprints: IO[Map[String, FileFingerprint]] =
    IO.blocking {
      val base = os.home / ".nebflow"
      val builder = Map.newBuilder[String, FileFingerprint]

      for
        path <- List(base / "NEBFLOW.md", base / "memory.md")
        fp <- FileFingerprint.compute(path)
      do builder += path.relativeTo(base).toString -> fp

      val agentsDir = base / "agents"
      if os.exists(agentsDir) then
        for agentDir <- os.list(agentsDir).filter(os.isDir) do
          val memFile = agentDir / "memory.md"
          FileFingerprint.compute(memFile).foreach { fp =>
            builder += memFile.relativeTo(base).toString -> fp
          }

      val foldersDir = base / "folders"
      if os.exists(foldersDir) then
        for f <- os.list(foldersDir).filter(_.last.endsWith(".memory.md")) do
          FileFingerprint.compute(f).foreach { fp =>
            builder += f.relativeTo(base).toString -> fp
          }

      val memoryDir = base / "memory"
      if os.exists(memoryDir) then
        for f <- os.list(memoryDir).filter(_.last.endsWith(".md")) do
          FileFingerprint.compute(f).foreach { fp =>
            builder += f.relativeTo(base).toString -> fp
          }

      val skillsDir = base / "skills"
      if os.exists(skillsDir) then
        for skillDir <- os.list(skillsDir).filter(os.isDir) do
          val skillFile = skillDir / "skill.md"
          FileFingerprint.compute(skillFile).foreach { fp =>
            builder += skillFile.relativeTo(base).toString -> fp
          }

      builder.result()
    }

  def computeSyncDiff(local: Map[String, FileFingerprint], remote: Map[String, FileFingerprint]): SyncDiff =
    val allPaths = local.keySet ++ remote.keySet
    val upload = List.newBuilder[String]
    val download = List.newBuilder[String]
    val unchanged = List.newBuilder[String]

    allPaths.foreach { path =>
      (local.get(path), remote.get(path)) match
        case (Some(_), None) => upload += path
        case (None, Some(_)) => download += path
        case (Some(l), Some(r)) =>
          if l.hash == r.hash then unchanged += path
          else if l.mtime > r.mtime then upload += path
          else download += path
        case (None, None) =>
    }
    SyncDiff(upload.result(), download.result(), unchanged.result())

  // ===== Background Loops =====

  def startSyncLoop: IO[Nothing] =
    configRef.get.flatMap { cfg =>
      Temporal[IO].sleep(cfg.syncIntervalSec.seconds) *>
        startSyncLoop
    }

  /** Expose UDP discovery for starting background loops. */
  private[nebflow] def discovery: UdpDiscovery = udpDiscovery

  // ===== HTTP Helpers =====

  private[nebflow] lazy val httpBackend = DefaultSyncBackend()

  def detectLocalAddress: IO[String] =
    IO.blocking {
      try
        val socket = new java.net.DatagramSocket()
        try
          socket.connect(java.net.InetAddress.getByName("8.8.8.8"), 53)
          val ip = socket.getLocalAddress.getHostAddress
          s"http://$ip:$serverPort"
        finally socket.close()
      catch case _: Exception => s"http://localhost:$serverPort"
    }

end MeshService

object MeshService:
  private val logger = NebflowLogger.forName("nebflow.mesh")

  /**
   * Create and initialize the MeshService with UDP discovery.
   *
   * Uses a Ref to break the circular dependency:
   * UdpDiscovery needs a callback → callback needs MeshService → MeshService holds UdpDiscovery.
   * We create UdpDiscovery with a Ref-based callback that reads from the ref,
   * then set the ref after MeshService is constructed.
   */
  def create(syncStore: MeshSyncStore, serverPort: Int = 8080): IO[MeshService] =
    for
      identity <- DeviceIdentity.loadOrCreate
      config <- MeshConfig.load
      idRef <- Ref.of[IO, DeviceIdentity](identity)
      cfgRef <- Ref.of[IO, MeshConfig](config)
      peersRef <- Ref.of[IO, Map[String, PeerInfo]](Map.empty)
      // Ref to hold the peer-discovered callback — breaks the circular dependency
      callbackRef <- Ref.of[IO, (String, Int) => IO[Unit]]((_, _) => IO.unit)
      udp <- UdpDiscovery.create(serverPort, (addr, port) =>
        callbackRef.get.flatMap(cb => cb(addr, port))
      )
      service = new MeshService(idRef, cfgRef, peersRef, syncStore, udp, serverPort)
      // Wire the callback to the service's onPeerDiscovered method
      _ <- callbackRef.set((addr: String, port: Int) => service.onPeerDiscovered(addr, port))
      // Restore token hash if already paired
      _ <- identity.groupId match
        case Some(token) =>
          val hash = UdpDiscovery.hashToken(token)
          udp.setTokenHash(Some(hash)) *> logger.info(s"Restored mesh pairing, hash=${hash.take(12)}...")
        case None => IO.unit
    yield service

end MeshService
