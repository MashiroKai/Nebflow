package nebflow.mesh

import cats.effect.{IO, Ref, Temporal}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
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
        .auth
        .bearer(token)
        .contentType("application/json")
        .body(body.noSpaces)
        .readTimeout(10.seconds)
        .response(asStringAlways)
        .send(httpBackend)

      if resp.code.isSuccess then resp.body else ""
    }.flatMap { responseBody =>
      // Parse peer identity from handshake response and add to local peers
      if responseBody.isEmpty then IO.unit
      else
        io.circe.parser.decode[Json](responseBody) match
          case Right(json) =>
            val peerDeviceId = json.hcursor.downField("deviceId").as[String].getOrElse("")
            val peerName = json.hcursor.downField("deviceName").as[String].getOrElse("")
            val peerPlatform = json.hcursor.downField("platform").as[String].getOrElse("")
            if peerDeviceId.nonEmpty then
              val peer = PeerInfo(peerDeviceId, peerName, peerPlatform, address)
              peersRef.update(_ + (peerDeviceId -> peer)) *>
                logger.info(s"Handshake complete: $peerName at $address")
            else IO.unit
          case Left(_) => IO.unit
    }.handleErrorWith(e => logger.debug(s"Handshake to $address failed: ${e.getMessage}"))

  // ===== Sync =====

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
  end computeSyncDiff

  /** Validate that relPath doesn't escape ~/.nebflow/ (no ../ traversal). */
  private def validateRelPath(relPath: String): Option[String] =
    val normalized = java.nio.file.Paths.get(relPath).normalize
    if normalized.startsWith("..") || normalized.isAbsolute then None
    else Some(normalized.toString)

  /** Read a local file for sync. Returns (content bytes, fingerprint) or None. */
  def readLocalFile(relPath: String): IO[Option[(Array[Byte], FileFingerprint)]] =
    validateRelPath(relPath) match
      case None => IO.pure(None)
      case Some(safePath) =>
        IO.blocking {
          val absPath = os.home / ".nebflow" / safePath
          FileFingerprint.compute(absPath).map { fp =>
            (os.read.bytes(absPath), fp)
          }
        }

  /** Write a file received from a peer. Backs up existing file to history before overwriting. */
  def writeLocalFile(relPath: String, content: Array[Byte]): IO[Unit] =
    validateRelPath(relPath) match
      case None => IO.raiseError(new RuntimeException(s"Invalid path: $relPath"))
      case Some(safePath) =>
        val absPath = os.home / ".nebflow" / safePath
        IO.blocking {
          // Backup existing file to history before overwriting
          if os.exists(absPath) then
            val historyDir = os.home / ".nebflow" / "mesh" / "history"
            val timestamp = System.currentTimeMillis()
            val backupName = s"${absPath.last}.${timestamp}.bak"
            os.write.over(historyDir / backupName, os.read.bytes(absPath), createFolders = true)
          // Write new content atomically
          val tmp = absPath / os.up / s"${absPath.last}.tmp"
          os.write.over(tmp, content, createFolders = true)
          os.move.over(tmp, absPath)
        }.flatMap { _ =>
          IO.blocking(FileFingerprint.compute(absPath)).flatMap {
            case Some(fp) => syncStore.updateSnapshot(relPath, fp)
            case None => IO.unit
          }
        }

  /** Fetch fingerprints from a peer device via direct HTTP. */
  def fetchPeerFingerprints(peer: PeerInfo, token: String): IO[Map[String, FileFingerprint]] =
    IO.blocking {
      val resp = basicRequest
        .get(sttp.model.Uri.unsafeParse(s"${peer.address}/api/mesh/fingerprints"))
        .auth
        .bearer(token)
        .readTimeout(15.seconds)
        .response(asStringAlways)
        .send(httpBackend)
      if resp.code.isSuccess then resp.body
      else throw new RuntimeException(s"Peer ${peer.deviceName} returned ${resp.code}")
    }.flatMap { body =>
      IO.fromEither(io.circe.parser.decode[Map[String, FileFingerprint]](body))
    }

  /** Download a file from a peer device. Returns decoded file bytes. */
  def downloadFromPeer(peer: PeerInfo, token: String, relPath: String): IO[Array[Byte]] =
    IO.blocking {
      val encodedPath = java.net.URLEncoder.encode(relPath, "UTF-8")
      val resp = basicRequest
        .get(sttp.model.Uri.unsafeParse(s"${peer.address}/api/mesh/file?path=$encodedPath"))
        .auth
        .bearer(token)
        .readTimeout(30.seconds)
        .response(asStringAlways)
        .send(httpBackend)
      if resp.code.isSuccess then resp.body
      else throw new RuntimeException(s"Download $relPath failed: ${resp.code}")
    }.flatMap { body =>
      // Response is JSON with base64-encoded content
      IO.fromEither(io.circe.parser.decode[Json](body)).flatMap { json =>
        val contentB64 = json.hcursor.downField("content").as[String]
        IO.fromEither(contentB64).map(java.util.Base64.getDecoder.decode)
      }
    }

  /** Upload a file to a peer device. */
  def uploadToPeer(peer: PeerInfo, token: String, relPath: String, content: Array[Byte]): IO[Unit] =
    IO.blocking {
      val body = Json.obj(
        "path" -> relPath.asJson,
        "content" -> java.util.Base64.getEncoder.encodeToString(content).asJson
      )
      val resp = basicRequest
        .put(sttp.model.Uri.unsafeParse(s"${peer.address}/api/mesh/file"))
        .auth
        .bearer(token)
        .contentType("application/json")
        .body(body.noSpaces)
        .readTimeout(30.seconds)
        .response(asStringAlways)
        .send(httpBackend)
      if resp.code.isSuccess then ()
      else throw new RuntimeException(s"Upload $relPath failed: ${resp.code}")
    }

  /** Full sync with a single peer. */
  def syncWithPeer(peer: PeerInfo): IO[Unit] =
    for
      tokenOpt <- groupId
      token <- IO.fromOption(tokenOpt)(new RuntimeException("Not paired"))
      localFps <- computeLocalFingerprints
      remoteFps <- fetchPeerFingerprints(peer, token)
      diff = computeSyncDiff(localFps, remoteFps)
      _ <- logger.info(
        s"Sync with ${peer.deviceName}: upload ${diff.needUpload.size}, download ${diff.needDownload.size}, unchanged ${diff.unchanged.size}"
      )
      // Upload files we have that the peer doesn't or peer has older
      _ <- diff.needUpload.traverse_ { relPath =>
        readLocalFile(relPath).flatMap {
          case Some((content, _)) =>
            uploadToPeer(peer, token, relPath, content).handleErrorWith { e =>
              logger.warn(s"Upload $relPath to ${peer.deviceName} failed: ${e.getMessage}")
            }
          case None => IO.unit
        }
      }
      // Download files the peer has that we don't or we have older
      _ <- diff.needDownload.traverse_ { relPath =>
        downloadFromPeer(peer, token, relPath)
          .flatMap(content => writeLocalFile(relPath, content))
          .handleErrorWith { e =>
            logger.warn(s"Download $relPath from ${peer.deviceName} failed: ${e.getMessage}")
          }
      }
    yield ()

  /** Sync with all connected peers. */
  def syncAll: IO[Unit] =
    for
      paired <- isPaired
      _ <-
        if !paired then IO.unit
        else
          peersRef.get.flatMap { peersMap =>
            peersMap.values.toList.traverse_ { peer =>
              syncWithPeer(peer).handleErrorWith { e =>
                logger.warn(s"Sync with ${peer.deviceName} failed: ${e.getMessage}")
              }
            }
          }
    yield ()

  // ===== Background Loops =====

  def startSyncLoop: IO[Nothing] =
    configRef.get.flatMap { cfg =>
      Temporal[IO].sleep(cfg.syncIntervalSec.seconds) *>
        syncAll.handleErrorWith(e => logger.warn(s"Sync cycle failed: ${e.getMessage}")) *>
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
      udp <- UdpDiscovery.create(serverPort, (addr, port) => callbackRef.get.flatMap(cb => cb(addr, port)))
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
