package nebflow.mesh

import cats.effect.{IO, Ref, Temporal}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import sttp.client4.*

import scala.concurrent.duration.*

/**
 * Core mesh service — account-based login, UDP discovery, direct peer connections.
 *
 * Users register/login with a Nebflow account (username + password).
 * The userId groups devices together. Device-to-device auth uses userId as Bearer token.
 * Cloud function handles account + discovery. All data transfer is P2P.
 */
class MeshService private (
  identityRef: Ref[IO, DeviceIdentity],
  accountRef: Ref[IO, Option[AccountInfo]],
  configRef: Ref[IO, MeshConfig],
  peersRef: Ref[IO, Map[String, PeerInfo]],
  syncStore: MeshSyncStore,
  udpDiscovery: UdpDiscovery,
  serverPort: Int
):
  private val logger = NebflowLogger.forName("nebflow.mesh")

  // ===== Identity =====

  def identity: IO[DeviceIdentity] = identityRef.get

  def isLoggedIn: IO[Boolean] = accountRef.get.map(_.isDefined)

  def account: IO[Option[AccountInfo]] = accountRef.get

  def userId: IO[Option[String]] = accountRef.get.map(_.map(_.userId))

  // ===== Account =====

  /** Check if a username is available via cloud function. */
  def checkUsernameAvailable(username: String): IO[Boolean] =
    if username.length < 3 then IO.pure(true)
    else
      getCloudUrl.flatMap { cloudUrl =>
        callCloud(cloudUrl, Json.obj(
          "action" -> "auth/check-username".asJson,
          "username" -> username.asJson
        )).map { json =>
          json.hcursor.downField("available").as[Boolean].getOrElse(true)
        }.handleError(_ => true) // on error, assume available (don't block registration)
      }

  /** Register a new Nebflow account via cloud function. */
  def register(username: String, password: String): IO[AccountInfo] =
    for
      _ <- validateUsername(username)
      _ <- validatePassword(password)
      cloudUrl <- getCloudUrl
      resp <- callCloud(cloudUrl, Json.obj(
        "action" -> "auth/register".asJson,
        "username" -> username.asJson,
        "password" -> password.asJson
      ))
      acc <- parseAccountResponse(resp, username)
      _ <- AccountInfo.save(acc)
      _ <- accountRef.set(Some(acc))
      _ <- startDiscovery(acc.userId)
      _ <- logger.info(s"Registered and logged in as $username")
    yield acc

  /** Login to an existing Nebflow account via cloud function. */
  def login(username: String, password: String): IO[AccountInfo] =
    for
      cloudUrl <- getCloudUrl
      resp <- callCloud(cloudUrl, Json.obj(
        "action" -> "auth/login".asJson,
        "username" -> username.asJson,
        "password" -> password.asJson
      ))
      acc <- parseAccountResponse(resp, username)
      _ <- AccountInfo.save(acc)
      _ <- accountRef.set(Some(acc))
      _ <- startDiscovery(acc.userId)
      _ <- logger.info(s"Logged in as $username")
    yield acc

  /** Logout — clear account, stop discovery, clear peers. */
  def logout: IO[Unit] =
    for
      _ <- AccountInfo.clear
      _ <- accountRef.set(None)
      _ <- udpDiscovery.setTokenHash(None)
      _ <- peersRef.set(Map.empty)
      _ <- configRef.update(_.copy(enabled = false))
      _ <- MeshConfig.save(MeshConfig(enabled = false))
      _ <- logger.info("Logged out of Mesh")
    yield ()

  // ===== Config =====

  def meshConfig: IO[MeshConfig] = configRef.get

  def updateConfig(fn: MeshConfig => MeshConfig): IO[Unit] =
    configRef.modify { cfg => val u = fn(cfg); (u, u) }.flatMap(MeshConfig.save)

  // ===== Peers =====

  def peers: IO[List[PeerInfo]] = peersRef.get.map(_.values.toList)

  /** Handle an incoming handshake from a peer. Called by the REST endpoint. */
  def handleHandshake(
    callerUserId: String,
    deviceId: String,
    deviceName: String,
    platform: String,
    callerIp: String,
    port: Int
  ): IO[Unit] =
    for
      myUserId <- userId
      _ <- myUserId match
        case Some(uid) => IO.raiseWhen(callerUserId != uid)(new RuntimeException("User mismatch"))
        case None      => IO.raiseError(new RuntimeException("Not logged in"))
      address = s"http://$callerIp:$port"
      peer = PeerInfo(deviceId, deviceName, platform, address)
      _ <- peersRef.update(_ + (deviceId -> peer))
      _ <- logger.info(s"Peer joined: $deviceName at $address")
    yield ()

  private def onPeerDiscovered(address: String, port: Int): IO[Unit] =
    for
      uidOpt <- userId
      id <- identityRef.get
      _ <- uidOpt match
        case Some(uid) => sendHandshake(address, uid, id)
        case None      => IO.unit
    yield ()

  private def sendHandshake(address: String, uid: String, id: DeviceIdentity): IO[Unit] =
    IO.blocking {
      val body = Json.obj(
        "deviceId" -> id.deviceId.asJson,
        "deviceName" -> id.deviceName.asJson,
        "platform" -> id.platform.asJson,
        "port" -> serverPort.asJson
      )
      val resp = basicRequest
        .post(sttp.model.Uri.unsafeParse(s"$address/api/mesh/handshake"))
        .auth.bearer(uid)
        .contentType("application/json")
        .body(body.noSpaces)
        .readTimeout(10.seconds)
        .response(asStringAlways)
        .send(httpBackend)
      if resp.code.isSuccess then resp.body else ""
    }.flatMap { responseBody =>
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

  // ===== Discovery =====

  private def startDiscovery(uid: String): IO[Unit] =
    val hash = UdpDiscovery.hashToken(uid)
    for
      _ <- udpDiscovery.setTokenHash(Some(hash))
      _ <- configRef.update(_.copy(enabled = true))
      _ <- MeshConfig.save(MeshConfig(enabled = true))
      _ <- logger.info(s"Started discovery for userId=${uid.take(8)}...")
    yield ()

  // ===== Sync =====

  def computeLocalFingerprints: IO[Map[String, FileFingerprint]] =
    IO.blocking {
      val base = os.home / ".nebflow"
      val builder = Map.newBuilder[String, FileFingerprint]
      for path <- List(base / "NEBFLOW.md", base / "memory.md"); fp <- FileFingerprint.compute(path)
      do builder += path.relativeTo(base).toString -> fp
      val agentsDir = base / "agents"
      if os.exists(agentsDir) then
        for agentDir <- os.list(agentsDir).filter(os.isDir) do
          val memFile = agentDir / "memory.md"
          FileFingerprint.compute(memFile).foreach(fp => builder += memFile.relativeTo(base).toString -> fp)
      val foldersDir = base / "folders"
      if os.exists(foldersDir) then
        for f <- os.list(foldersDir).filter(_.last.endsWith(".memory.md")) do
          FileFingerprint.compute(f).foreach(fp => builder += f.relativeTo(base).toString -> fp)
      val memoryDir = base / "memory"
      if os.exists(memoryDir) then
        for f <- os.list(memoryDir).filter(_.last.endsWith(".md")) do
          FileFingerprint.compute(f).foreach(fp => builder += f.relativeTo(base).toString -> fp)
      val skillsDir = base / "skills"
      if os.exists(skillsDir) then
        for skillDir <- os.list(skillsDir).filter(os.isDir) do
          FileFingerprint.compute(skillDir / "skill.md").foreach(fp =>
            builder += (skillDir / "skill.md").relativeTo(base).toString -> fp
          )
      builder.result()
    }

  def computeSyncDiff(local: Map[String, FileFingerprint], remote: Map[String, FileFingerprint]): SyncDiff =
    val allPaths = local.keySet ++ remote.keySet
    val up = List.newBuilder[String]; val dn = List.newBuilder[String]; val un = List.newBuilder[String]
    allPaths.foreach { path =>
      (local.get(path), remote.get(path)) match
        case (Some(_), None)                                            => up += path
        case (None, Some(_))                                            => dn += path
        case (Some(l), Some(r)) if l.hash == r.hash                    => un += path
        case (Some(l), Some(r)) if l.mtime > r.mtime                   => up += path
        case (Some(_), Some(_))                                         => dn += path
        case (None, None)                                               =>
    }
    SyncDiff(up.result(), dn.result(), un.result())

  private def validateRelPath(relPath: String): Option[String] =
    val n = java.nio.file.Paths.get(relPath).normalize
    if n.startsWith("..") || n.isAbsolute then None else Some(n.toString)

  def readLocalFile(relPath: String): IO[Option[(Array[Byte], FileFingerprint)]] =
    validateRelPath(relPath) match
      case None        => IO.pure(None)
      case Some(safe)  =>
        IO.blocking {
          val abs = os.home / ".nebflow" / safe
          FileFingerprint.compute(abs).map(fp => (os.read.bytes(abs), fp))
        }

  def writeLocalFile(relPath: String, content: Array[Byte]): IO[Unit] =
    validateRelPath(relPath) match
      case None        => IO.raiseError(new RuntimeException(s"Invalid path: $relPath"))
      case Some(safe)  =>
        val abs = os.home / ".nebflow" / safe
        IO.blocking {
          if os.exists(abs) then
            val histDir = os.home / ".nebflow" / "mesh" / "history"
            os.write.over(histDir / s"${abs.last}.${System.currentTimeMillis()}.bak", os.read.bytes(abs), createFolders = true)
          val tmp = abs / os.up / s"${abs.last}.tmp"
          os.write.over(tmp, content, createFolders = true)
          os.move.over(tmp, abs)
        }.flatMap(_ =>
          IO.blocking(FileFingerprint.compute(abs)).flatMap {
            case Some(fp) => syncStore.updateSnapshot(relPath, fp)
            case None     => IO.unit
          }
        )

  private def fetchPeerFingerprints(peer: PeerInfo, uid: String): IO[Map[String, FileFingerprint]] =
    IO.blocking {
      val resp = basicRequest
        .get(sttp.model.Uri.unsafeParse(s"${peer.address}/api/mesh/fingerprints"))
        .auth.bearer(uid).readTimeout(15.seconds).response(asStringAlways).send(httpBackend)
      if resp.code.isSuccess then resp.body else throw new RuntimeException(s"Peer returned ${resp.code}")
    }.flatMap(body => IO.fromEither(io.circe.parser.decode[Map[String, FileFingerprint]](body)))

  private def downloadFromPeer(peer: PeerInfo, uid: String, relPath: String): IO[Array[Byte]] =
    IO.blocking {
      val enc = java.net.URLEncoder.encode(relPath, "UTF-8")
      val resp = basicRequest
        .get(sttp.model.Uri.unsafeParse(s"${peer.address}/api/mesh/file?path=$enc"))
        .auth.bearer(uid).readTimeout(30.seconds).response(asStringAlways).send(httpBackend)
      if resp.code.isSuccess then resp.body else throw new RuntimeException(s"Download $relPath failed: ${resp.code}")
    }.flatMap(body =>
      IO.fromEither(io.circe.parser.decode[Json](body)).flatMap(json =>
        IO.fromEither(json.hcursor.downField("content").as[String])
          .map(java.util.Base64.getDecoder.decode)
      )
    )

  private def uploadToPeer(peer: PeerInfo, uid: String, relPath: String, content: Array[Byte]): IO[Unit] =
    IO.blocking {
      val body = Json.obj("path" -> relPath.asJson, "content" -> java.util.Base64.getEncoder.encodeToString(content).asJson)
      val resp = basicRequest
        .put(sttp.model.Uri.unsafeParse(s"${peer.address}/api/mesh/file"))
        .auth.bearer(uid).contentType("application/json").body(body.noSpaces)
        .readTimeout(30.seconds).response(asStringAlways).send(httpBackend)
      if !resp.code.isSuccess then throw new RuntimeException(s"Upload $relPath failed: ${resp.code}")
    }

  def syncWithPeer(peer: PeerInfo): IO[Unit] =
    for
      uidOpt <- userId
      uid <- IO.fromOption(uidOpt)(new RuntimeException("Not logged in"))
      localFps <- computeLocalFingerprints
      remoteFps <- fetchPeerFingerprints(peer, uid)
      diff = computeSyncDiff(localFps, remoteFps)
      _ <- logger.info(s"Sync with ${peer.deviceName}: up ${diff.needUpload.size}, dn ${diff.needDownload.size}")
      _ <- diff.needUpload.traverse_ { p =>
        readLocalFile(p).flatMap {
          case Some((c, _)) => uploadToPeer(peer, uid, p, c).handleErrorWith(e => logger.warn(s"Upload $p failed: ${e.getMessage}"))
          case None          => IO.unit
        }
      }
      _ <- diff.needDownload.traverse_ { p =>
        downloadFromPeer(peer, uid, p).flatMap(c => writeLocalFile(p, c))
          .handleErrorWith(e => logger.warn(s"Download $p failed: ${e.getMessage}"))
      }
    yield ()

  def syncAll: IO[Unit] =
    for
      loggedIn <- isLoggedIn
      _ <- if !loggedIn then IO.unit
      else peersRef.get.flatMap(_.values.toList.traverse_(p =>
        syncWithPeer(p).handleErrorWith(e => logger.warn(s"Sync with ${p.deviceName} failed: ${e.getMessage}"))
      ))
    yield ()

  // ===== Background Loops =====

  def startSyncLoop: IO[Nothing] =
    configRef.get.flatMap { cfg =>
      Temporal[IO].sleep(cfg.syncIntervalSec.seconds) *>
        (syncAll *> cloudDiscover).handleErrorWith(e => logger.warn(s"Sync cycle failed: ${e.getMessage}"))
    }.flatMap(_ => startSyncLoop)

  // ===== Cloud Discovery =====

  def cloudDiscover: IO[Unit] =
    for
      accOpt <- accountRef.get
      _ <- accOpt match
        case None => IO.unit
        case Some(acc) =>
          configRef.get.flatMap { cfg =>
            cfg.cloudUrl match
              case None     => IO.unit
              case Some(url) =>
                for
                  addr <- detectLocalAddress
                  id <- identityRef.get
                  _ <- cloudRegister(url, acc, id, addr)
                  _ <- cloudLookup(url, acc, id)
                yield ()
          }
    yield ()

  private def cloudRegister(cloudUrl: String, acc: AccountInfo, id: DeviceIdentity, address: String): IO[Unit] =
    IO.blocking {
      val body = Json.obj(
        "action" -> "discover/register".asJson,
        "userId" -> acc.userId.asJson,
        "sessionToken" -> acc.sessionToken.asJson,
        "deviceId" -> id.deviceId.asJson,
        "deviceName" -> id.deviceName.asJson,
        "platform" -> id.platform.asJson,
        "address" -> address.asJson
      )
      val resp = basicRequest.post(sttp.model.Uri.unsafeParse(cloudUrl))
        .contentType("application/json").body(body.noSpaces)
        .readTimeout(10.seconds).response(asStringAlways).send(httpBackend)
      if !resp.code.isSuccess then throw new RuntimeException(s"Cloud register failed: ${resp.code}")
    }.handleErrorWith(e => logger.debug(s"Cloud register: ${e.getMessage}"))

  private def cloudLookup(cloudUrl: String, acc: AccountInfo, id: DeviceIdentity): IO[Unit] =
    IO.blocking {
      val body = Json.obj(
        "action" -> "discover/lookup".asJson,
        "userId" -> acc.userId.asJson,
        "sessionToken" -> acc.sessionToken.asJson,
        "deviceId" -> id.deviceId.asJson
      )
      val resp = basicRequest.post(sttp.model.Uri.unsafeParse(cloudUrl))
        .contentType("application/json").body(body.noSpaces)
        .readTimeout(10.seconds).response(asStringAlways).send(httpBackend)
      if resp.code.isSuccess then resp.body else ""
    }.flatMap { body =>
      if body.isEmpty then IO.unit
      else
        io.circe.parser.decode[Json](body) match
          case Right(json) =>
            val data = json.hcursor.downField("data")
            data.downField("peers").as[List[PeerInfo]] match
              case Right(cloudPeers) =>
                peersRef.update(_ ++ cloudPeers.map(p => p.deviceId -> p))
              case Left(_) => IO.unit
          case Left(_) => IO.unit
    }.handleErrorWith(e => logger.debug(s"Cloud lookup: ${e.getMessage}"))

  // ===== Helpers =====

  private[nebflow] def discovery: UdpDiscovery = udpDiscovery

  private[nebflow] lazy val httpBackend = DefaultSyncBackend()

  def detectLocalAddress: IO[String] =
    IO.blocking {
      try
        val socket = new java.net.DatagramSocket()
        try
          socket.connect(java.net.InetAddress.getByName("8.8.8.8"), 53)
          s"http://${socket.getLocalAddress.getHostAddress}:$serverPort"
        finally socket.close()
      catch case _: Exception => s"http://localhost:$serverPort"
    }

  private def getCloudUrl: IO[String] =
    configRef.get.flatMap(_.cloudUrl match
      case Some(url) => IO.pure(url)
      case None => IO.raiseError(new RuntimeException("Cloud URL not configured"))
    )

  private def callCloud(cloudUrl: String, body: Json): IO[Json] =
    IO.blocking {
      val resp = basicRequest.post(sttp.model.Uri.unsafeParse(cloudUrl))
        .contentType("application/json").body(body.noSpaces)
        .readTimeout(15.seconds).response(asStringAlways).send(httpBackend)
      if !resp.code.isSuccess then throw new RuntimeException(s"Cloud API ${resp.code}: ${resp.body.take(200)}")
      io.circe.parser.decode[Json](resp.body) match
        case Right(json) =>
          json.hcursor.downField("code").as[Int] match
            case Right(200) => json.hcursor.downField("data").as[Json].getOrElse(json)
            case Right(code) =>
              val msg = json.hcursor.downField("message").as[String].getOrElse("Unknown")
              throw new RuntimeException(s"Cloud error $code: $msg")
            case Left(_) => json
        case Left(err) => throw new RuntimeException(s"JSON decode error: ${err.getMessage}")
    }

  private def parseAccountResponse(json: Json, username: String): IO[AccountInfo] =
    val data = json.hcursor
    for
      userId <- IO.fromEither(data.downField("userId").as[String].leftMap(_ => new RuntimeException("Missing userId")))
      token  <- IO.fromEither(data.downField("sessionToken").as[String].leftMap(_ => new RuntimeException("Missing sessionToken")))
    yield AccountInfo(userId, username, token)

  private def validateUsername(username: String): IO[Unit] =
    IO.raiseWhen(username.length < 3)(new RuntimeException("Username must be at least 3 characters")) *>
      IO.raiseWhen(!username.matches("^[a-zA-Z0-9_-]+$"))(new RuntimeException("Username can only contain letters, numbers, _ and -"))

  private def validatePassword(password: String): IO[Unit] =
    IO.raiseWhen(password.length < 6)(new RuntimeException("Password must be at least 6 characters"))

end MeshService

object MeshService:
  private val logger = NebflowLogger.forName("nebflow.mesh")

  def create(syncStore: MeshSyncStore, serverPort: Int = 8080): IO[MeshService] =
    for
      identity <- DeviceIdentity.loadOrCreate
      config <- MeshConfig.load
      accountOpt <- AccountInfo.load
      idRef <- Ref.of[IO, DeviceIdentity](identity)
      accRef <- Ref.of[IO, Option[AccountInfo]](accountOpt)
      cfgRef <- Ref.of[IO, MeshConfig](config)
      peersRef <- Ref.of[IO, Map[String, PeerInfo]](Map.empty)
      callbackRef <- Ref.of[IO, (String, Int) => IO[Unit]]((_, _) => IO.unit)
      udp <- UdpDiscovery.create(serverPort, (addr, port) => callbackRef.get.flatMap(cb => cb(addr, port)))
      service = new MeshService(idRef, accRef, cfgRef, peersRef, syncStore, udp, serverPort)
      _ <- callbackRef.set((addr: String, port: Int) => service.onPeerDiscovered(addr, port))
      // Restore discovery if already logged in
      _ <- accountOpt match
        case Some(acc) =>
          val hash = UdpDiscovery.hashToken(acc.userId)
          udp.setTokenHash(Some(hash)) *> logger.info(s"Restored mesh login: ${acc.username}")
        case None => IO.unit
    yield service

end MeshService
