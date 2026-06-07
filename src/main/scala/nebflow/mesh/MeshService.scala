package nebflow.mesh

import cats.effect.{IO, Ref, Temporal}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.parser.decode
import io.circe.{Decoder, Json, parser}
import nebflow.core.NebflowLogger
import sttp.client4.*
import sttp.model.Uri as SttpUri

import scala.concurrent.duration.*

/**
 * Core mesh service — handles device discovery, data sync, and relay communication.
 *
 * Uses sttp SyncBackend (same as GatewayClient) for HTTP calls to CloudBase.
 * JSON responses decoded manually via circe (project convention).
 */
class MeshService private (
  identityRef: Ref[IO, DeviceIdentity],
  configRef: Ref[IO, MeshConfig],
  peersRef: Ref[IO, List[PeerInfo]],
  syncStore: MeshSyncStore
):
  private val logger = NebflowLogger.forName("nebflow.mesh")

  // ===== Identity =====

  def identity: IO[DeviceIdentity] = identityRef.get

  def isLoggedIn: IO[Boolean] =
    identityRef.get.map(d => d.jwt.isDefined && d.nebflowUserId.isDefined)

  def isJwtExpired: IO[Boolean] =
    identityRef.get.map { d =>
      d.jwtExpiresAt.exists(_ < System.currentTimeMillis())
    }

  /** Update auth after successful login. */
  def onLogin(userId: String, jwt: String, expiresAt: Long): IO[Unit] =
    identityRef.get.flatMap { current =>
      val updated = current.copy(
        nebflowUserId = Some(userId),
        jwt = Some(jwt),
        jwtExpiresAt = Some(expiresAt)
      )
      DeviceIdentity.save(updated) *> identityRef.set(updated) *> logger.info(
        s"Logged in as $userId on device ${current.deviceName}"
      )
    }

  // ===== Config =====

  def meshConfig: IO[MeshConfig] = configRef.get

  def updateConfig(fn: MeshConfig => MeshConfig): IO[Unit] =
    configRef.modify { cfg =>
      val updated = fn(cfg)
      (updated, updated)
    }.flatMap(updated => MeshConfig.save(updated))

  // ===== Peers =====

  def peers: IO[List[PeerInfo]] = peersRef.get

  /** Fetch peer list from cloud and update local cache. */
  def refreshPeers: IO[List[PeerInfo]] =
    for
      id <- identityRef.get
      jwt <- IO.fromOption(id.jwt)(new RuntimeException("Not logged in"))
      cloudUrl <- getCloudUrl
      uri = cloudUrl.withPath("device" :: "list" :: Nil)
      peers <- cloudGet[List[PeerInfo]](uri, jwt)
      _ <- peersRef.set(peers)
      _ <- logger.debug(s"Refreshed peers: ${peers.size} devices")
    yield peers

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
      do
        builder += path.relativeTo(base).toString -> fp

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

  /** Upload a single file to cloud storage. */
  def uploadFile(relPath: String): IO[Unit] =
    for
      id <- identityRef.get
      jwt <- IO.fromOption(id.jwt)(new RuntimeException("Not logged in"))
      cloudUrl <- getCloudUrl
      absPath = os.home / ".nebflow" / relPath
      content <- IO.blocking(os.read.bytes(absPath))
      uri = cloudUrl.withPath("sync" :: "upload" :: Nil)
      _ <- cloudPost(uri, jwt, Json.obj("path" -> relPath.asJson, "content" -> java.util.Base64.getEncoder.encodeToString(content).asJson))
      fp <- IO.blocking(FileFingerprint.compute(absPath))
      _ <- fp.traverse(fp => syncStore.updateSnapshot(relPath, fp))
      _ <- logger.debug(s"Uploaded: $relPath")
    yield ()

  /** Download a single file from cloud storage. */
  def downloadFile(relPath: String): IO[Unit] =
    for
      id <- identityRef.get
      jwt <- IO.fromOption(id.jwt)(new RuntimeException("Not logged in"))
      cloudUrl <- getCloudUrl
      absPath = os.home / ".nebflow" / relPath
      uri = cloudUrl.withPath("sync" :: "download" :: Nil).withParam("path", relPath)
      response <- cloudGet[Json](uri, jwt)
      contentStr <- IO.fromEither(
        response.hcursor.downField("content").as[String].leftMap(_ => new RuntimeException("Invalid download response"))
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
      loggedIn <- isLoggedIn
      _ <- if !loggedIn then logger.debug("Skipping sync: not logged in")
      else
        for
          local <- computeLocalFingerprints
          remote <- fetchRemoteFingerprints
          diff = computeSyncDiff(local, remote)
          _ <- logger.info(s"Sync: upload ${diff.needUpload.size}, download ${diff.needDownload.size}, unchanged ${diff.unchanged.size}")
          _ <- diff.needUpload.traverse_(uploadFile)
          _ <- diff.needDownload.traverse_(downloadFile)
        yield ()
    yield ()

  // ===== Relay =====

  /** Poll for pending relay messages from the cloud. */
  def pollRelay: IO[List[RelayMessage]] =
    for
      id <- identityRef.get
      jwt <- IO.fromOption(id.jwt)(new RuntimeException("Not logged in"))
      cloudUrl <- getCloudUrl
      uri = cloudUrl.withPath("relay" :: "poll" :: Nil).withParam("deviceId", id.deviceId)
      messages <- cloudGet[List[RelayMessage]](uri, jwt)
    yield messages

  /** Send a relay message to a remote device. */
  def sendRelay(targetDeviceId: String, payload: RelayPayload): IO[Unit] =
    for
      id <- identityRef.get
      jwt <- IO.fromOption(id.jwt)(new RuntimeException("Not logged in"))
      cloudUrl <- getCloudUrl
      uri = cloudUrl.withPath("relay" :: "send" :: Nil)
      msg = RelayMessage(
        id = java.util.UUID.randomUUID().toString,
        fromDeviceId = id.deviceId,
        toDeviceId = targetDeviceId,
        payload = payload,
        createdAt = System.currentTimeMillis()
      )
      _ <- cloudPost(uri, jwt, msg.asJson)
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
        pollRelay.handleErrorWith(e => logger.warn(s"Relay poll failed: ${e.getMessage}").as(Nil))
          .flatMap(_.traverse_(handler)) *>
        startRelayLoop(handler)
    }

  // ===== HTTP Helpers =====

  private def getCloudUrl: IO[SttpUri] =
    configRef.get.flatMap { cfg =>
      IO.fromOption(
        cfg.cloudBaseUrl.flatMap(url => SttpUri.parse(url).toOption)
      )(new RuntimeException("CloudBase URL not configured"))
    }

  private def fetchRemoteFingerprints: IO[Map[String, FileFingerprint]] =
    for
      id <- identityRef.get
      jwt <- IO.fromOption(id.jwt)(new RuntimeException("Not logged in"))
      cloudUrl <- getCloudUrl
      uri = cloudUrl.withPath("sync" :: "status" :: Nil)
      result <- cloudGet[Map[String, FileFingerprint]](uri, jwt)
    yield result

  /** GET a cloud endpoint, decode JSON response as [A]. */
  private def cloudGet[A: Decoder](uri: SttpUri, jwt: String): IO[A] =
    IO.blocking {
      val resp = basicRequest
        .get(uri)
        .auth.bearer(jwt)
        .response(asStringAlways)
        .send(backend)
      decodeResponse[A](resp.body, uri.toString)
    }

  /** POST JSON to a cloud endpoint, fire-and-forget (checks status only). */
  private def cloudPost(uri: SttpUri, jwt: String, body: Json): IO[Unit] =
    IO.blocking {
      val resp = basicRequest
        .post(uri)
        .auth.bearer(jwt)
        .contentType("application/json")
        .body(body.noSpaces)
        .response(asStringAlways)
        .send(backend)
      if !resp.code.isSuccess then
        throw new RuntimeException(s"Cloud API ${resp.code}: ${resp.body.take(200)}")
    }

  private def decodeResponse[A: Decoder](body: String, context: String): A =
    if body.startsWith("{") || body.startsWith("[") then
      decode[A](body) match
        case Right(value) => value
        case Left(err) => throw new RuntimeException(s"JSON decode error at $context: ${err.getMessage}")
    else
      throw new RuntimeException(s"Cloud API non-JSON response at $context: ${body.take(200)}")

  private lazy val backend = DefaultSyncBackend()

end MeshService

object MeshService:
  /** Create and initialize the MeshService. */
  def create(syncStore: MeshSyncStore): IO[MeshService] =
    for
      identity <- DeviceIdentity.loadOrCreate
      config <- MeshConfig.load
      idRef <- Ref.of[IO, DeviceIdentity](identity)
      cfgRef <- Ref.of[IO, MeshConfig](config)
      peersRef <- Ref.of[IO, List[PeerInfo]](Nil)
    yield new MeshService(idRef, cfgRef, peersRef, syncStore)
end MeshService
