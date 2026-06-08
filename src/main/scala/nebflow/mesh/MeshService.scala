package nebflow.mesh

import cats.effect.{IO, Ref, Temporal}
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Json}
import nebflow.core.NebflowLogger
import sttp.client4.*
import sttp.model.Uri as SttpUri

import scala.concurrent.duration.*

/**
 * Core mesh service — handles pairing, device discovery, data sync, and relay.
 *
 * All cloud calls go through a single CloudBase cloud function URL.
 * The action field in the JSON body routes to the right handler.
 * groupId (obtained via pairing code) serves as the Bearer token for auth.
 */
class MeshService private (
  identityRef: Ref[IO, DeviceIdentity],
  configRef: Ref[IO, MeshConfig],
  peersRef: Ref[IO, List[PeerInfo]],
  syncStore: MeshSyncStore,
  serverPort: Int
):
  private val logger = NebflowLogger.forName("nebflow.mesh")

  // ===== Identity =====

  def identity: IO[DeviceIdentity] = identityRef.get

  def isLoggedIn: IO[Boolean] =
    identityRef.get.map(_.groupId.isDefined)

  /** Group ID (= cloud userId) for this device. */
  def groupId: IO[Option[String]] =
    identityRef.get.map(_.groupId)

  // ===== Pairing =====

  /** Create a new device group. Returns (groupId, pairingCode, expiresAt). */
  def createGroup: IO[(String, String, Long)] =
    for
      id <- identityRef.get
      cloudUrl <- getCloudUrl
      localAddr <- detectLocalAddress
      resp <- callCloud(
        cloudUrl,
        Json.obj(
          "action" -> "auth/create-group".asJson,
          "deviceId" -> id.deviceId.asJson,
          "deviceName" -> id.deviceName.asJson,
          "platform" -> id.platform.asJson,
          "nebflowUrl" -> localAddr.asJson
        ),
        ""
      )
      groupIdStr <- IO.fromEither(
        resp.hcursor.downField("groupId").as[String].leftMap(_ => new RuntimeException("Missing groupId in response"))
      )
      code <- IO.fromEither(
        resp.hcursor.downField("pairingCode").as[String].leftMap(_ => new RuntimeException("Missing pairingCode"))
      )
      expiresAt <- IO.fromEither(
        resp.hcursor.downField("expiresAt").as[Long].leftMap(_ => new RuntimeException("Missing expiresAt"))
      )
      _ <- DeviceIdentity.setGroup(id, groupIdStr)
      _ <- identityRef.set(id.copy(groupId = Some(groupIdStr)))
      _ <- logger.info(s"Created group $groupIdStr, pairing code: $code")
    yield (groupIdStr, code, expiresAt)

  /** Join an existing group with a pairing code. Returns groupId. */
  def joinGroup(pairingCode: String): IO[String] =
    for
      id <- identityRef.get
      cloudUrl <- getCloudUrl
      localAddr <- detectLocalAddress
      resp <- callCloud(
        cloudUrl,
        Json.obj(
          "action" -> "auth/join-group".asJson,
          "pairingCode" -> pairingCode.asJson,
          "deviceId" -> id.deviceId.asJson,
          "deviceName" -> id.deviceName.asJson,
          "platform" -> id.platform.asJson,
          "nebflowUrl" -> localAddr.asJson
        ),
        ""
      )
      groupIdStr <- IO.fromEither(
        resp.hcursor
          .downField("groupId")
          .as[String]
          .leftMap(_ => new RuntimeException("Invalid pairing code or expired"))
      )
      _ <- DeviceIdentity.setGroup(id, groupIdStr)
      _ <- identityRef.set(id.copy(groupId = Some(groupIdStr)))
      // Store peers from join response
      peersResult = resp.hcursor.downField("peers").as[List[PeerInfo]].getOrElse(List.empty)
      _ <- peersRef.set(peersResult)
      _ <- logger.info(s"Joined group $groupIdStr, ${peersResult.size} peers")
    yield groupIdStr

  /** Leave the current group (clear local groupId). */
  def leaveGroup: IO[Unit] =
    for
      id <- identityRef.get
      _ <- DeviceIdentity.save(id.copy(groupId = None))
      _ <- identityRef.set(id.copy(groupId = None))
      _ <- peersRef.set(Nil)
      _ <- logger.info("Left group")
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

  def peers: IO[List[PeerInfo]] = peersRef.get

  /** Fetch peer list from cloud and update local cache. */
  def refreshPeers: IO[List[PeerInfo]] =
    for
      id <- identityRef.get
      gid <- IO.fromOption(id.groupId)(new RuntimeException("Not paired"))
      cloudUrl <- getCloudUrl
      resp <- callCloudAuth(cloudUrl, gid, Json.obj("action" -> "device/list".asJson))
      peerList = resp.as[List[PeerInfo]].getOrElse(List.empty)
      _ <- peersRef.set(peerList)
      _ <- logger.debug(s"Refreshed peers: ${peerList.size} devices")
    yield peerList

  // ===== Sync =====

  /** Compute fingerprints for all syncable files under ~/.nebflow/. */
  def computeLocalFingerprints: IO[Map[String, FileFingerprint]] =
    IO.blocking {
      val base = os.home / ".nebflow"
      val builder = Map.newBuilder[String, FileFingerprint]

      // Top-level memory files
      for
        path <- List(base / "NEBFLOW.md", base / "memory.md")
        fp <- FileFingerprint.compute(path)
      do builder += path.relativeTo(base).toString -> fp

      // Agent memories: agents/{name}/memory.md
      val agentsDir = base / "agents"
      if os.exists(agentsDir) then
        for agentDir <- os.list(agentsDir).filter(os.isDir) do
          val memFile = agentDir / "memory.md"
          FileFingerprint.compute(memFile).foreach { fp =>
            builder += memFile.relativeTo(base).toString -> fp
          }

      // Folder memories: folders/{id}.memory.md
      val foldersDir = base / "folders"
      if os.exists(foldersDir) then
        for f <- os.list(foldersDir).filter(_.last.endsWith(".memory.md")) do
          FileFingerprint.compute(f).foreach { fp =>
            builder += f.relativeTo(base).toString -> fp
          }

      // Memory details (immutable): memory/{hash}.md
      val memoryDir = base / "memory"
      if os.exists(memoryDir) then
        for f <- os.list(memoryDir).filter(_.last.endsWith(".md")) do
          FileFingerprint.compute(f).foreach { fp =>
            builder += f.relativeTo(base).toString -> fp
          }

      // Skills: skills/{name}/skill.md
      val skillsDir = base / "skills"
      if os.exists(skillsDir) then
        for skillDir <- os.list(skillsDir).filter(os.isDir) do
          val skillFile = skillDir / "skill.md"
          FileFingerprint.compute(skillFile).foreach { fp =>
            builder += skillFile.relativeTo(base).toString -> fp
          }

      builder.result()
    }

  /** Compare local fingerprints with cloud to determine what to sync. */
  def computeSyncDiff(local: Map[String, FileFingerprint], remote: Map[String, FileFingerprint]): SyncDiff =
    val allPaths = local.keySet ++ remote.keySet
    val upload = List.newBuilder[String]
    val download = List.newBuilder[String]
    val unchanged = List.newBuilder[String]

    allPaths.foreach { path =>
      (local.get(path), remote.get(path)) match
        case (Some(_), None) =>
          upload += path
        case (None, Some(_)) =>
          download += path
        case (Some(l), Some(r)) =>
          if l.hash == r.hash then unchanged += path
          else if l.mtime > r.mtime then upload += path
          else download += path
        case (None, None) => // shouldn't happen
    }
    SyncDiff(upload.result(), download.result(), unchanged.result())

  end computeSyncDiff

  /** Upload a single file to cloud storage. */
  def uploadFile(relPath: String): IO[Unit] =
    for
      id <- identityRef.get
      gid <- IO.fromOption(id.groupId)(new RuntimeException("Not paired"))
      cloudUrl <- getCloudUrl
      absPath = os.home / ".nebflow" / relPath
      content <- IO.blocking(os.read.bytes(absPath))
      _ <- callCloudAuth(
        cloudUrl,
        gid,
        Json.obj(
          "action" -> "sync/upload".asJson,
          "path" -> relPath.asJson,
          "content" -> java.util.Base64.getEncoder.encodeToString(content).asJson
        )
      )
      fp <- IO.blocking(FileFingerprint.compute(absPath))
      _ <- fp.traverse(fp => syncStore.updateSnapshot(relPath, fp))
      _ <- logger.debug(s"Uploaded: $relPath")
    yield ()

  /** Download a single file from cloud storage. */
  def downloadFile(relPath: String): IO[Unit] =
    for
      id <- identityRef.get
      gid <- IO.fromOption(id.groupId)(new RuntimeException("Not paired"))
      cloudUrl <- getCloudUrl
      absPath = os.home / ".nebflow" / relPath
      resp <- callCloudAuth(
        cloudUrl,
        gid,
        Json.obj(
          "action" -> "sync/download".asJson,
          "path" -> relPath.asJson
        )
      )
      contentStr <- IO.fromEither(
        resp.hcursor.downField("content").as[String].leftMap(_ => new RuntimeException("Invalid download response"))
      )
      _ <- IO.blocking {
        val tmp = absPath / os.up / s"${absPath.last}.tmp"
        os.write.over(tmp, contentStr.getBytes("UTF-8"), createFolders = true)
        os.move.over(tmp, absPath)
      }
      fp <- IO.blocking(FileFingerprint.compute(absPath))
      _ <- fp.traverse(fp => syncStore.updateSnapshot(relPath, fp))
      _ <- logger.debug(s"Downloaded: $relPath")
    yield ()

  /** Full sync cycle: compare -> upload -> download -> update snapshots. */
  def sync: IO[Unit] =
    for
      paired <- isLoggedIn
      _ <-
        if !paired then logger.debug("Skipping sync: not paired")
        else
          for
            local <- computeLocalFingerprints
            remote <- fetchRemoteFingerprints
            diff = computeSyncDiff(local, remote)
            _ <- logger.info(
              s"Sync: upload ${diff.needUpload.size}, download ${diff.needDownload.size}, unchanged ${diff.unchanged.size}"
            )
            _ <- diff.needUpload.traverse_(uploadFile)
            _ <- diff.needDownload.traverse_(downloadFile)
          yield ()
    yield ()

  // ===== Relay =====

  /** Poll for pending relay messages from the cloud. */
  def pollRelay: IO[List[RelayMessage]] =
    for
      id <- identityRef.get
      gid <- IO.fromOption(id.groupId)(new RuntimeException("Not paired"))
      cloudUrl <- getCloudUrl
      resp <- callCloudAuth(
        cloudUrl,
        gid,
        Json.obj(
          "action" -> "relay/poll".asJson,
          "deviceId" -> id.deviceId.asJson
        )
      )
      messages = resp.as[List[RelayMessage]].getOrElse(List.empty)
    yield messages

  /** Send a relay message to a remote device. */
  def sendRelay(targetDeviceId: String, payload: RelayPayload): IO[Unit] =
    for
      id <- identityRef.get
      gid <- IO.fromOption(id.groupId)(new RuntimeException("Not paired"))
      cloudUrl <- getCloudUrl
      msg = RelayMessage(
        id = java.util.UUID.randomUUID().toString,
        fromDeviceId = id.deviceId,
        toDeviceId = targetDeviceId,
        payload = payload,
        createdAt = System.currentTimeMillis()
      )
      _ <- callCloudAuth(
        cloudUrl,
        gid,
        Json.obj(
          "action" -> "relay/send".asJson,
          "fromDeviceId" -> msg.fromDeviceId.asJson,
          "toDeviceId" -> msg.toDeviceId.asJson,
          "payload" -> msg.payload.asJson,
          "id" -> msg.id.asJson,
          "createdAt" -> msg.createdAt.asJson
        )
      )
    yield ()

  // ===== Background Loops =====

  /** Start the sync loop (runs periodically). Never returns. */
  def startSyncLoop: IO[Nothing] =
    configRef.get.flatMap { cfg =>
      Temporal[IO].sleep(cfg.syncIntervalSec.seconds) *>
        sync.handleErrorWith(e => logger.warn(s"Sync failed: ${e.getMessage}")) *>
        startSyncLoop
    }

  /** Start the relay polling loop. Never returns. */
  def startRelayLoop(handler: RelayMessage => IO[Unit]): IO[Nothing] =
    configRef.get.flatMap { cfg =>
      Temporal[IO].sleep(cfg.relayPollIntervalSec.seconds) *>
        pollRelay
          .handleErrorWith(e => logger.warn(s"Relay poll failed: ${e.getMessage}").as(Nil))
          .flatMap(_.traverse_(handler)) *>
        startRelayLoop(handler)
    }

  // ===== HTTP Helpers =====

  private def getCloudUrl: IO[SttpUri] =
    configRef.get.flatMap { cfg =>
      IO.fromOption(
        cfg.cloudBaseUrl.flatMap(url => SttpUri.parse(url).toOption)
      )(new RuntimeException("Cloud URL not configured — set it in Mesh panel"))
    }

  private def fetchRemoteFingerprints: IO[Map[String, FileFingerprint]] =
    for
      id <- identityRef.get
      gid <- IO.fromOption(id.groupId)(new RuntimeException("Not paired"))
      cloudUrl <- getCloudUrl
      resp <- callCloudAuth(cloudUrl, gid, Json.obj("action" -> "sync/status".asJson))
      result = resp.as[Map[String, FileFingerprint]].getOrElse(Map.empty)
    yield result

  /**
   * Call the cloud function.
   * @param gid groupId for auth header. Empty string = no auth.
   */
  private def callCloud(uri: SttpUri, body: Json, gid: String): IO[Json] =
    IO.blocking {
      val req = basicRequest
        .post(uri)
        .contentType("application/json")
        .body(body.noSpaces)
        .readTimeout(30.seconds)
        .response(asStringAlways)
      val reqWithAuth = if gid.nonEmpty then req.auth.bearer(gid) else req
      val resp = reqWithAuth.send(httpBackend)
      if !resp.code.isSuccess then throw new RuntimeException(s"Cloud API ${resp.code}: ${resp.body.take(300)}")
      decode[Json](resp.body) match
        case Right(json) =>
          // Check for cloud function error response
          json.hcursor.downField("code").as[Int] match
            case Right(200) =>
              json.hcursor.downField("data").as[Json] match
                case Right(data) => data
                case Left(_) => json
            case Right(errCode) =>
              val msg = json.hcursor.downField("message").as[String].getOrElse("Unknown error")
              throw new RuntimeException(s"Cloud error $errCode: $msg")
            case Left(_) => json
        case Left(err) =>
          throw new RuntimeException(s"JSON decode error: ${err.getMessage}")
    }

  /** Convenience: call cloud with auth (requires groupId). */
  private def callCloudAuth(uri: SttpUri, gid: String, body: Json): IO[Json] =
    callCloud(uri, body, gid)

  /** Shared HTTP backend — used by MeshTool for remote calls too. */
  private[nebflow] lazy val httpBackend = DefaultSyncBackend()

  /** Detect this device's Nebflow URL (local IP + port). */
  private def detectLocalAddress: IO[String] =
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

  /** Create and initialize the MeshService. */
  def create(syncStore: MeshSyncStore, serverPort: Int = 8080): IO[MeshService] =
    for
      identity <- DeviceIdentity.loadOrCreate
      config <- MeshConfig.load
      idRef <- Ref.of[IO, DeviceIdentity](identity)
      cfgRef <- Ref.of[IO, MeshConfig](config)
      peersRef <- Ref.of[IO, List[PeerInfo]](Nil)
    yield new MeshService(idRef, cfgRef, peersRef, syncStore, serverPort)
end MeshService
