package nebflow.mesh

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.{NebflowLogger, PathUtil}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import sttp.client4.*

import scala.compiletime.uninitialized
import scala.concurrent.duration.*

// ============================================================
// Sync command protocol — public so callers can send commands
// ============================================================

sealed trait SyncCommand

object SyncCommand:
  /** Start periodic sync (sent on login). */
  case object StartSync extends SyncCommand

  /** Stop periodic sync (sent on logout). */
  case object StopSync extends SyncCommand

  /** Periodic sync timer fired. */
  private[mesh] case object SyncTick extends SyncCommand

  /** A peer was discovered — trigger immediate sync. */
  case object PeerDiscovered extends SyncCommand
end SyncCommand

/**
 * Core mesh service — account-based login, cloud discovery, peer-to-peer sync.
 *
 * Event-driven design:
 *   - SyncActor replaces the old recursive IO.sleep loop.
 *   - No UDP broadcast/listen — cloud discovery is sufficient for peer detection.
 *   - Sync only runs when logged in. Logged out = zero CPU, zero network.
 */
class MeshService private (
  identityRef: Ref[IO, DeviceIdentity],
  accountRef: Ref[IO, Option[AccountInfo]],
  configRef: Ref[IO, MeshConfig],
  peersRef: Ref[IO, Map[String, PeerInfo]],
  syncStore: MeshSyncStore,
  serverPort: Int,
  private val _syncActorRef: ActorRef[SyncCommand]
):
  private val logger = NebflowLogger.forName("nebflow.mesh")

  /** Optional hook invoked after each sync cycle (cloud discover + P2P sync). Used by CloudSessionSync. */
  @volatile private var _postSyncHook: IO[Unit] = IO.unit

  /** Set a hook to run after each sync cycle (e.g. cloud session sync, relay poll). */
  def setPostSyncHook(hook: IO[Unit]): Unit = _postSyncHook = hook

  // ===== Identity =====

  def identity: IO[DeviceIdentity] = identityRef.get

  /** Update device capabilities and/or user description. Persists to disk and cloud. */
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
      _ <- callCloudFunction(
        "device/capabilities",
        "deviceId" -> updated.deviceId.asJson,
        "capabilities" -> updated.capabilities.asJson,
        "userDescription" -> updated.userDescription.asJson
      ).handleErrorWith(e => logger.debug(s"Cloud capabilities update: ${e.getMessage}"))
    yield ()

  /** Run capability self-check and update device identity + cloud. Called on startup. */
  def selfCheckCapabilities: IO[Unit] =
    for
      caps <- DeviceIdentity.detectCapabilities
      id <- identityRef.get
      // Only update if capabilities changed
      _ <- if id.capabilities != caps then updateDeviceInfo(capabilities = Some(caps)) else IO.unit
    yield ()

  def isLoggedIn: IO[Boolean] = accountRef.get.map(_.isDefined)

  def account: IO[Option[AccountInfo]] = accountRef.get

  def userId: IO[Option[String]] = accountRef.get.map(_.map(_.userId))

  /** ActorRef of the sync actor — callers can send commands directly. */
  def syncActor: ActorRef[SyncCommand] = _syncActorRef

  /** P2P auth token: userId:deviceSecret. Used as Bearer in peer-to-peer requests. */
  def peerAuthToken: IO[String] =
    for
      uidOpt <- userId
      id <- identityRef.get
    yield uidOpt match
      case Some(uid) => s"$uid:${id.deviceSecret}"
      case None => ""

  /**
   * Verify an incoming P2P request. Bearer format: userId:deviceSecret.
   * Checks: userId matches local account AND deviceSecret is from a known peer.
   */
  def verifyPeerToken(bearer: String): IO[Boolean] =
    val parts = bearer.split(":", 2)
    if parts.length != 2 then IO.pure(false)
    else
      val (callerUserId, callerSecret) = (parts(0), parts(1))
      for
        myUserId <- userId
        peersMap <- peersRef.get
        myId <- identityRef.get
      yield myUserId match
        case Some(uid) if uid == callerUserId =>
          peersMap.values.exists(_.deviceSecret == callerSecret) ||
          myId.deviceSecret == callerSecret
        case _ => false

  // ===== Account =====

  /** Check if a username is available via cloud function. */
  def checkUsernameAvailable(username: String): IO[Boolean] =
    if username.length < 3 then IO.pure(true)
    else
      getCloudUrl.flatMap { cloudUrl =>
        callCloud(
          cloudUrl,
          Json.obj(
            "action" -> "auth/check-username".asJson,
            "username" -> username.asJson
          )
        ).map { json =>
          json.hcursor.downField("available").as[Boolean].getOrElse(false)
        }.handleError(_ => false)
      }

  /** Register a new Nebflow account via cloud function. */
  def register(username: String, password: String): IO[AccountInfo] =
    for
      _ <- validateUsername(username)
      _ <- validatePassword(password)
      cloudUrl <- getCloudUrl
      resp <- callCloud(
        cloudUrl,
        Json.obj(
          "action" -> "auth/register".asJson,
          "username" -> username.asJson,
          "password" -> password.asJson
        )
      )
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
      resp <- callCloud(
        cloudUrl,
        Json.obj(
          "action" -> "auth/login".asJson,
          "username" -> username.asJson,
          "password" -> password.asJson
        )
      )
      acc <- parseAccountResponse(resp, username)
      _ <- AccountInfo.save(acc)
      _ <- accountRef.set(Some(acc))
      _ <- startDiscovery(acc.userId)
      _ <- logger.info(s"Logged in as $username")
    yield acc

  /** Logout — clear account, stop sync, clear peers. */
  def logout: IO[Unit] =
    for
      _ <- AccountInfo.clear
      _ <- accountRef.set(None)
      _ <- peersRef.set(Map.empty)
      _ <- configRef.update(_.copy(enabled = false))
      _ <- MeshConfig.save(MeshConfig(enabled = false))
      // Stop sync actor timers
      _ = _syncActorRef ! SyncCommand.StopSync
      _ <- logger.info("Logged out of Mesh")
    yield ()

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

  /** Handle an incoming handshake from a peer. Called by the REST endpoint. */
  def handleHandshake(
    callerUserId: String,
    deviceId: String,
    deviceName: String,
    platform: String,
    callerIp: String,
    port: Int,
    callerSecret: String = ""
  ): IO[Unit] =
    for
      myUserId <- userId
      _ <- myUserId match
        case Some(uid) => IO.raiseWhen(callerUserId != uid)(new RuntimeException("User mismatch"))
        case None => IO.raiseError(new RuntimeException("Not logged in"))
      address = s"http://$callerIp:$port"
      peer = PeerInfo(deviceId, deviceName, platform, address, callerSecret)
      _ <- peersRef.update(_ + (deviceId -> peer))
      _ <- logger.info(s"Peer joined: $deviceName at $address")
    yield ()

  // ===== Discovery =====

  private def startDiscovery(uid: String): IO[Unit] =
    for
      _ <- configRef.update(_.copy(enabled = true))
      _ <- MeshConfig.save(MeshConfig(enabled = true))
      // Kick the sync actor — it will start periodic sync + cloud discovery
      _ = _syncActorRef ! SyncCommand.StartSync
      _ <- logger.info(s"Started discovery for userId=${uid.take(8)}...")
    yield ()

  // ===== Sync =====

  def computeLocalFingerprints: IO[Map[String, FileFingerprint]] =
    IO.blocking {
      val base = PathUtil.dataRoot
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
          FileFingerprint
            .compute(skillDir / "skill.md")
            .foreach(fp => builder += (skillDir / "skill.md").relativeTo(base).toString -> fp)
      builder.result()
    }

  def computeSyncDiff(local: Map[String, FileFingerprint], remote: Map[String, FileFingerprint]): SyncDiff =
    val allPaths = local.keySet ++ remote.keySet
    val up = List.newBuilder[String]; val dn = List.newBuilder[String]; val un = List.newBuilder[String]
    allPaths.foreach { path =>
      (local.get(path), remote.get(path)) match
        case (Some(_), None) => up += path
        case (None, Some(_)) => dn += path
        case (Some(l), Some(r)) if l.hash == r.hash => un += path
        case (Some(l), Some(r)) if l.mtime > r.mtime => up += path
        case (Some(_), Some(_)) => dn += path
        case (None, None) =>
    }
    SyncDiff(up.result(), dn.result(), un.result())

  private def validateRelPath(relPath: String): Option[String] =
    val n = java.nio.file.Paths.get(relPath).normalize
    if n.startsWith("..") || n.isAbsolute then None else Some(n.toString)

  def readLocalFile(relPath: String): IO[Option[(Array[Byte], FileFingerprint)]] =
    validateRelPath(relPath) match
      case None => IO.pure(None)
      case Some(safe) =>
        IO.blocking {
          val abs = PathUtil.dataRoot / os.RelPath(safe)
          FileFingerprint.compute(abs).map(fp => (os.read.bytes(abs), fp))
        }

  private val MaxFileSyncBytes = 10 * 1024 * 1024 // 10MB

  def writeLocalFile(relPath: String, content: Array[Byte]): IO[Unit] =
    if content.length > MaxFileSyncBytes then
      IO.raiseError(new RuntimeException(s"File too large for sync: ${content.length} bytes (max $MaxFileSyncBytes)"))
    else
      validateRelPath(relPath) match
        case None => IO.raiseError(new RuntimeException(s"Invalid path: $relPath"))
        case Some(safe) =>
          val abs = PathUtil.dataRoot / os.RelPath(safe)
          IO.blocking {
            if os.exists(abs) then
              val histDir = PathUtil.dataRoot / "mesh" / "history"
              os.write.over(
                histDir / s"${abs.last}.${System.currentTimeMillis()}.bak",
                os.read.bytes(abs),
                createFolders = true
              )
            val tmp = abs / os.up / s"${abs.last}.tmp"
            os.write.over(tmp, content, createFolders = true)
            os.move.over(tmp, abs)
          }.flatMap(_ =>
            IO.blocking(FileFingerprint.compute(abs)).flatMap {
              case Some(fp) => syncStore.updateSnapshot(relPath, fp)
              case None => IO.unit
            }
          )

  private def fetchPeerFingerprints(peer: PeerInfo, token: String): IO[Map[String, FileFingerprint]] =
    IO.blocking {
      val resp = basicRequest
        .get(sttp.model.Uri.unsafeParse(s"${peer.address}/api/mesh/fingerprints"))
        .auth
        .bearer(token)
        .readTimeout(15.seconds)
        .response(asStringAlways)
        .send(httpBackend)
      if resp.code.isSuccess then resp.body else throw new RuntimeException(s"Peer returned ${resp.code}")
    }.flatMap(body => IO.fromEither(io.circe.parser.decode[Map[String, FileFingerprint]](body)))

  private def downloadFromPeer(peer: PeerInfo, token: String, relPath: String): IO[Array[Byte]] =
    IO.blocking {
      val enc = java.net.URLEncoder.encode(relPath, "UTF-8")
      val resp = basicRequest
        .get(sttp.model.Uri.unsafeParse(s"${peer.address}/api/mesh/file?path=$enc"))
        .auth
        .bearer(token)
        .readTimeout(30.seconds)
        .response(asStringAlways)
        .send(httpBackend)
      if resp.code.isSuccess then resp.body else throw new RuntimeException(s"Download $relPath failed: ${resp.code}")
    }.flatMap(body =>
      IO.fromEither(io.circe.parser.decode[Json](body))
        .flatMap(json =>
          IO.fromEither(json.hcursor.downField("content").as[String])
            .map(java.util.Base64.getDecoder.decode)
        )
    )

  private def uploadToPeer(peer: PeerInfo, token: String, relPath: String, content: Array[Byte]): IO[Unit] =
    IO.blocking {
      val body =
        Json.obj("path" -> relPath.asJson, "content" -> java.util.Base64.getEncoder.encodeToString(content).asJson)
      val resp = basicRequest
        .put(sttp.model.Uri.unsafeParse(s"${peer.address}/api/mesh/file"))
        .auth
        .bearer(token)
        .contentType("application/json")
        .body(body.noSpaces)
        .readTimeout(30.seconds)
        .response(asStringAlways)
        .send(httpBackend)
      if !resp.code.isSuccess then throw new RuntimeException(s"Upload $relPath failed: ${resp.code}")
    }

  def syncWithPeer(peer: PeerInfo): IO[Unit] =
    for
      uidOpt <- userId
      _ <- IO.fromOption(uidOpt)(new RuntimeException("Not logged in"))
      token <- peerAuthToken
      localFps <- computeLocalFingerprints
      remoteFps <- fetchPeerFingerprints(peer, token)
      diff = computeSyncDiff(localFps, remoteFps)
      _ <- logger.info(s"Sync with ${peer.deviceName}: up ${diff.needUpload.size}, dn ${diff.needDownload.size}")
      _ <- diff.needUpload.traverse_ { p =>
        readLocalFile(p).flatMap {
          case Some((c, _)) =>
            uploadToPeer(peer, token, p, c).handleErrorWith(e => logger.warn(s"Upload $p failed: ${e.getMessage}"))
          case None => IO.unit
        }
      }
      _ <- diff.needDownload.traverse_ { p =>
        downloadFromPeer(peer, token, p)
          .flatMap(c => writeLocalFile(p, c))
          .handleErrorWith(e => logger.warn(s"Download $p failed: ${e.getMessage}"))
      }
    yield ()

  /** Cloud-based file sync — replaces P2P syncWithPeer. */
  def syncFilesWithCloud: IO[Unit] =
    for
      loggedIn <- isLoggedIn
      _ <-
        if !loggedIn then IO.unit
        else
          for
            localFps <- computeLocalFingerprints
            // Phase 1: fingerprint exchange
            resp <- callCloudFunction("file/sync", "fingerprints" -> localFps.asJson)
            // Phase 2: download cloud-newer files
            downloads <- IO.fromEither(
              resp.hcursor
                .downField("download")
                .as[List[CloudFileDownload]]
                .leftMap(e => new RuntimeException(s"Decode download: ${e.getMessage}"))
            )
            _ <- downloads.traverse_ { f =>
              val bytes = java.util.Base64.getDecoder.decode(f.content)
              writeLocalFile(f.path, bytes) *> syncStore.updateSnapshot(f.path, f.fingerprint)
            }
            // Phase 3: upload local-newer files
            uploadNeeded = resp.hcursor.downField("uploadNeeded").as[List[String]].getOrElse(Nil)
            _ <- uploadNeeded.traverse_ { p =>
              readLocalFile(p).flatMap {
                case Some((content, fp)) =>
                  val b64 = java.util.Base64.getEncoder.encodeToString(content)
                  callCloudFunction(
                    "file/upload",
                    "path" -> p.asJson,
                    "content" -> b64.asJson,
                    "fingerprint" -> fp.asJson
                  ).void.handleErrorWith(e => logger.warn(s"Upload $p failed: ${e.getMessage}"))
                case None => IO.unit
              }
            }
            _ <- logger.info(s"Cloud file sync: ${downloads.size} downloaded, ${uploadNeeded.size} uploaded")
          yield ()
    yield ()

  /** File sync moved to CloudSessionSync.fastSyncCycle (5s interval). This is a no-op kept for compatibility. */
  def syncAll: IO[Unit] = IO.unit

  /** Run one sync cycle: cloud discover + sync all peers + post-sync hook (cloud session sync, relay poll). */
  def runSyncCycle: IO[Unit] = cloudDiscover *> syncAll *> _postSyncHook

  // ===== Cloud Discovery =====

  def cloudDiscover: IO[Unit] =
    for
      accOpt <- accountRef.get
      _ <- accOpt match
        case None => IO.unit
        case Some(acc) =>
          configRef.get.flatMap { cfg =>
            cfg.cloudUrl match
              case None => IO.unit
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
        "address" -> address.asJson,
        "capabilities" -> id.capabilities.asJson,
        "userDescription" -> id.userDescription.asJson,
        "deviceSecret" -> id.deviceSecret.asJson
      )
      val resp = basicRequest
        .post(sttp.model.Uri.unsafeParse(cloudUrl))
        .contentType("application/json")
        .body(body.noSpaces)
        .readTimeout(10.seconds)
        .response(asStringAlways)
        .send(httpBackend)
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
      val resp = basicRequest
        .post(sttp.model.Uri.unsafeParse(cloudUrl))
        .contentType("application/json")
        .body(body.noSpaces)
        .readTimeout(10.seconds)
        .response(asStringAlways)
        .send(httpBackend)
      if resp.code.isSuccess then resp.body else ""
    }.flatMap { body =>
      if body.isEmpty then IO.unit
      else
        io.circe.parser.decode[Json](body) match
          case Right(json) =>
            // Cloud function may return peers directly or nested under "data"
            val dataCursor = json.hcursor.downField("data")
            val peersCursor =
              if dataCursor.succeeded then dataCursor.downField("peers")
              else json.hcursor.downField("peers")
            peersCursor.as[List[PeerInfo]] match
              case Right(cloudPeers) =>
                peersRef.update(_ ++ cloudPeers.map(p => p.deviceId -> p))
              case Left(e) =>
                logger.debug(s"Cloud lookup parse error: ${e.getMessage}")
            IO.unit
          case Left(e) =>
            logger.debug(s"Cloud lookup JSON error: ${e.getMessage}")
      end if
    }.handleErrorWith(e => logger.debug(s"Cloud lookup: ${e.getMessage}"))

  // ===== Cloud Call (public for CloudSessionSync) =====

  /**
   * Call a cloud function action with auth context injected automatically.
   * Requires the user to be logged in.
   * Returns the response JSON (unwrapped from the cloud function envelope).
   */
  def callCloudFunction(action: String, extraFields: (String, Json)*): IO[Json] =
    for
      accOpt <- accountRef.get
      acc <- IO.fromOption(accOpt)(new RuntimeException("Not logged in"))
      cloudUrl <- getCloudUrl
      body = Json
        .obj(
          "action" -> action.asJson,
          "userId" -> acc.userId.asJson,
          "sessionToken" -> acc.sessionToken.asJson
        )
        .deepMerge(Json.obj(extraFields*))
      resp <- callCloud(cloudUrl, body)
    yield resp

  /** Current cloud URL, or empty string if not configured. */
  def cloudUrl: IO[String] = configRef.get.map(_.cloudUrl.getOrElse(""))

  // ===== Helpers =====

  private[nebflow] lazy val httpBackend = DefaultSyncBackend()

  def detectLocalAddress: IO[String] =
    IO.blocking {
      val fallback = s"http://localhost:$serverPort"

      // Method 1: UDP connect to public DNS
      def tryUdp: String =
        try
          val socket = new java.net.DatagramSocket()
          try
            socket.connect(java.net.InetAddress.getByName("8.8.8.8"), 53)
            val addr = socket.getLocalAddress.getHostAddress
            if addr != "0.0.0.0" && addr != "127.0.0.1" then s"http://$addr:$serverPort"
            else ""
          finally socket.close()
        catch case _: Exception => ""

      // Method 2: Enumerate network interfaces
      def tryInterfaces: String =
        try
          var result = ""
          val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
          val it = java.util.Collections.list(interfaces).iterator
          while it.hasNext && result.isEmpty do
            val ni = it.next()
            if ni.isUp && !ni.isLoopback && !ni.isVirtual then
              val addrs = java.util.Collections.list(ni.getInetAddresses).iterator
              while addrs.hasNext && result.isEmpty do
                val addr = addrs.next()
                if addr.isInstanceOf[java.net.Inet4Address] && !addr.isLoopbackAddress then
                  result = s"http://${addr.getHostAddress}:$serverPort"
          result
        catch case _: Exception => ""

      tryUdp match
        case s if s.nonEmpty => s
        case _ =>
          tryInterfaces match
            case s if s.nonEmpty => s
            case _ => fallback
    }

  private def getCloudUrl: IO[String] =
    configRef.get.flatMap(_.cloudUrl match
      case Some(url) => IO.pure(url)
      case None => IO.raiseError(new RuntimeException("Cloud URL not configured")))

  private def callCloud(cloudUrl: String, body: Json): IO[Json] =
    IO.blocking {
      val resp = basicRequest
        .post(sttp.model.Uri.unsafeParse(cloudUrl))
        .contentType("application/json")
        .body(body.noSpaces)
        .readTimeout(15.seconds)
        .response(asStringAlways)
        .send(httpBackend)
      if !resp.code.isSuccess then throw new RuntimeException(s"Cloud API ${resp.code}: ${resp.body.take(200)}")
      io.circe.parser.decode[Json](resp.body) match
        case Right(json) =>
          json.hcursor.downField("code").as[Int] match
            case Right(code) if code != 200 =>
              val msg = json.hcursor.downField("message").as[String].getOrElse("Unknown")
              throw new RuntimeException(s"Cloud error $code: $msg")
            case _ => json // no code field or code==200 — return full json
        case Left(err) => throw new RuntimeException(s"JSON decode error: ${err.getMessage}")
    }

  private def parseAccountResponse(json: Json, username: String): IO[AccountInfo] =
    // Account data may be at root or nested under "data"
    val dataCursor = json.hcursor.downField("data")
    val data = if dataCursor.succeeded then dataCursor else json.hcursor
    for
      userId <- IO.fromEither(data.downField("userId").as[String].leftMap(_ => new RuntimeException("Missing userId")))
      token <- IO.fromEither(
        data.downField("sessionToken").as[String].leftMap(_ => new RuntimeException("Missing sessionToken"))
      )
    yield AccountInfo(userId, username, token)

  private def validateUsername(username: String): IO[Unit] =
    IO.raiseWhen(username.length < 3)(new RuntimeException("Username must be at least 3 characters")) *>
      IO.raiseWhen(!username.matches("^[a-zA-Z0-9_-]+$"))(
        new RuntimeException("Username can only contain letters, numbers, _ and -")
      )

  private def validatePassword(password: String): IO[Unit] =
    IO.raiseWhen(password.length < 6)(new RuntimeException("Password must be at least 6 characters"))

end MeshService

object MeshService:
  private val logger = NebflowLogger.forName("nebflow.mesh")

  // Sync interval — only active when logged in
  private val SyncInterval = 5.minutes

  def create(
    syncStore: MeshSyncStore,
    serverPort: Int = 8080,
    actorSystem: ActorSystem[?],
    dispatcher: Dispatcher[IO]
  ): IO[MeshService] =
    for
      identity <- DeviceIdentity.loadOrCreate
      config <- MeshConfig.load
      accountOpt <- AccountInfo.load
      idRef <- Ref.of[IO, DeviceIdentity](identity)
      accRef <- Ref.of[IO, Option[AccountInfo]](accountOpt)
      cfgRef <- Ref.of[IO, MeshConfig](config)
      peersRef <- Ref.of[IO, Map[String, PeerInfo]](Map.empty)
      // Holder for service ref — set after construction (break circular dependency)
      serviceBox = new AtomicServiceBox
      syncActorRef <- IO {
        actorSystem.systemActorOf(
          syncActor(serviceBox, dispatcher),
          "mesh-sync"
        )
      }
      service = new MeshService(idRef, accRef, cfgRef, peersRef, syncStore, serverPort, syncActorRef)
      // Wire service into actor
      _ = serviceBox.set(service)
      // Restore discovery if already logged in
      _ <- accountOpt match
        case Some(acc) =>
          logger.info(s"Restored mesh login: ${acc.username}") *>
            IO(syncActorRef ! SyncCommand.StartSync)
        case None => IO.unit
      // Background: detect capabilities and update identity (best-effort, non-blocking)
      _ = dispatcher.unsafeRunAndForget(
        service.selfCheckCapabilities.handleErrorWith(e => logger.debug(s"Capability detection: ${e.getMessage}"))
      )
    yield service

  // Simple mutable box to break MeshService ↔ SyncActor circular dependency.
  // Same pattern as MemoryAgentManager._resources.
  private class AtomicServiceBox:
    @volatile private var _service: MeshService = uninitialized
    def set(s: MeshService): Unit = _service = s

    def get: MeshService =
      if _service == null then throw new IllegalStateException("MeshService not initialized")
      _service

  // ============================================================
  // Sync actor — event-driven, no polling when logged out
  // ============================================================

  private def syncActor(
    serviceBox: AtomicServiceBox,
    dispatcher: Dispatcher[IO]
  ): Behavior[SyncCommand] =
    Behaviors.withTimers { timers =>
      // Start idle — no timers, no CPU, no network
      idle(timers, serviceBox, dispatcher)
    }

  private def idle(
    timers: org.apache.pekko.actor.typed.scaladsl.TimerScheduler[SyncCommand],
    serviceBox: AtomicServiceBox,
    dispatcher: Dispatcher[IO]
  ): Behavior[SyncCommand] =
    Behaviors.receiveMessage {
      case SyncCommand.StartSync =>
        // Run first sync immediately, then schedule periodic
        doSync(serviceBox, dispatcher)
        timers.startSingleTimer(SyncCommand.SyncTick, SyncInterval)
        running(timers, serviceBox, dispatcher)
      case SyncCommand.StopSync => Behaviors.same
      case SyncCommand.SyncTick => Behaviors.same
      case SyncCommand.PeerDiscovered => Behaviors.same
    }

  private def running(
    timers: org.apache.pekko.actor.typed.scaladsl.TimerScheduler[SyncCommand],
    serviceBox: AtomicServiceBox,
    dispatcher: Dispatcher[IO]
  ): Behavior[SyncCommand] =
    Behaviors.receiveMessage {
      case SyncCommand.SyncTick =>
        doSync(serviceBox, dispatcher)
        // Reschedule next tick
        timers.startSingleTimer(SyncCommand.SyncTick, SyncInterval)
        Behaviors.same
      case SyncCommand.StopSync =>
        timers.cancelAll()
        idle(timers, serviceBox, dispatcher)
      case SyncCommand.StartSync => Behaviors.same
      case SyncCommand.PeerDiscovered =>
        // Peer just appeared — run sync immediately
        doSync(serviceBox, dispatcher)
        Behaviors.same
    }

  private def doSync(serviceBox: AtomicServiceBox, dispatcher: Dispatcher[IO]): Unit =
    val service = serviceBox.get
    dispatcher.unsafeRunAndForget(
      service.runSyncCycle.handleErrorWith(e => logger.warn(s"Sync cycle failed: ${e.getMessage}"))
    )

end MeshService
