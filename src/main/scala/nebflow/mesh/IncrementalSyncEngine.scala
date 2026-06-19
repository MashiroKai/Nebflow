package nebflow.mesh

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Json}
import nebflow.core.NebflowLogger
import nebflow.gateway.{Folder, SessionStore}
import nebflow.shared.{*, given}
import io.circe.generic.semiauto.*

/** Remote session state from cloud (metadata only). */
case class RemoteSessionState(
  sessionId: String,
  name: String,
  agentName: String,
  folderId: Option[String],
  updatedAt: Long
):
  def createdAt: Long = updatedAt

object RemoteSessionState:

  given Decoder[RemoteSessionState] = Decoder.instance { c =>
    for
      sessionId <- c.downField("sessionId").as[String]
      name <- c.downField("name").as[String]
      agentName <- c.downField("agentName").as[Option[String]].map(_.getOrElse("Nebula"))
      folderId <- c.downField("folderId").as[Option[String]]
      updatedAt <- c.downField("updatedAt").as[Option[Long]].map(_.getOrElse(0L))
    yield RemoteSessionState(sessionId, name, agentName, folderId, updatedAt)
  }

/**
 * Incremental sync engine — replaces CloudSessionSync's push/pull with blob-based sync.
 *
 * Architecture (git-style):
 *   1. State sync (every 5s): tiny JSON — session metadata, busy state, file refs
 *   2. Content sync (on demand): only transfer blobs the other side doesn't have
 *
 * Push flow (when messages change):
 *   compute hash of each message → batch-check which blobs are new → upload new blobs → append hashes to session log
 *
 * Pull flow (when detecting remote changes):
 *   get session log (hash list) → compare with local → download only missing blobs → reconstruct messages
 */
class IncrementalSyncEngine private (
  meshService: MeshService,
  sessionStore: SessionStore,
  blobSync: BlobSyncService
):
  private val logger = NebflowLogger.forName("nebflow.incremental-sync")

  // ===== State Sync (lightweight, every 5 seconds) =====

  /**
   * Push all local session states + folders to cloud and pull remote states.
   * This is the fast cycle — just metadata, no content.
   */
  def stateSync: IO[Unit] =
    for
      loggedIn <- meshService.isLoggedIn
      _ <-
        if !loggedIn then IO.unit
        else
          for
            // Push local session states + pull remote states
            localSessions <- sessionStore.listSessions
            localFolders <- sessionStore.listAllFolders
            statesJson = localSessions.map(s =>
              Json.obj(
                "sessionId" -> s.id.asJson,
                "name" -> s.name.asJson,
                "agentName" -> s.agentName.asJson,
                "folderId" -> s.folderId.asJson,
                "updatedAt" -> s.updatedAt.asJson
              )
            )
            resp <- meshService.callCloudFunction("session/state-sync", "states" -> statesJson.asJson)
            // Merge remote session states into local
            remoteStates <- IO.fromEither(
              resp.hcursor
                .downField("states")
                .as[List[RemoteSessionState]]
                .leftMap(e => new RuntimeException(s"Decode states: ${e.getMessage}"))
            )
            _ <- sessionStore.mergeCloudIndex(
              remoteStates.map(s => SessionMeta(s.sessionId, s.name, s.createdAt, s.updatedAt, hasUnread = false)),
              Nil
            )
            // Folders: push local + pull remote via existing index endpoint
            _ <- meshService.callCloudFunction("session/push-index",
              "sessions" -> localSessions.asJson,
              "folders" -> localFolders.asJson
            ).handleErrorWith(e => logger.debug(s"Push index (folders): ${e.getMessage}"))
            pullResp <- meshService.callCloudFunction("session/pull-index").handleErrorWith(e =>
              logger.debug(s"Pull index: ${e.getMessage}").as(io.circe.Json.Null)
            )
            remoteFolders = pullResp.hcursor.downField("folders").as[List[Folder]].getOrElse(Nil)
            _ <- sessionStore.mergeCloudIndex(Nil, remoteFolders)
          yield ()
    yield ()

  // ===== Content Push (incremental, on message change) =====

  /**
   * Push a session's messages as blobs (incremental — only new blobs uploaded).
   * Called by the session-changed hook.
   */
  def pushSessionIncremental(sessionId: String): IO[Unit] =
    for
      loggedIn <- meshService.isLoggedIn
      _ <-
        if !loggedIn then IO.unit
        else
          for
            messages <- sessionStore.loadMessagesForSession(sessionId)
            (uiMessages, uiCount) <- sessionStore.getUiMessages(sessionId, 0, Int.MaxValue)
            // Compute hashes for all messages
            msgBlobs = messages.map { m =>
              val h = blobSync.hashJson(m)
              h -> m.asJson.noSpaces.getBytes("UTF-8")
            }
            uiBlobs = uiMessages.map { m =>
              val h = blobSync.hashJson(m)
              h -> m.asJson.noSpaces.getBytes("UTF-8")
            }
            // Upload only missing blobs
            allBlobs = (msgBlobs ++ uiBlobs).toMap
            uploaded <- blobSync.uploadMissing(allBlobs)
            // Cache locally
            _ = allBlobs.foreach { case (h, content) => blobSync.cacheLocal(h, content) }
            // Append hashes to session log
            msgHashes = msgBlobs.map(_._1)
            uiHashes = uiBlobs.map(_._1)
            _ <- meshService
              .callCloudFunction(
                "session/log-sync",
                "sessionId" -> sessionId.asJson,
                "messageHashes" -> msgHashes.asJson,
                "uiMessageHashes" -> uiHashes.asJson
              )
              .void
            _ <-
              if uploaded.nonEmpty then logger.debug(s"Session $sessionId: uploaded ${uploaded.size} new blobs")
              else IO.unit
            // Notify peers to pull immediately (push-based sync)
            _ <- notifyPeers("session", sessionId)
          yield ()
    yield ()

  // ===== Content Pull (incremental, on session switch or detected change) =====

  /**
   * Pull a session's messages from cloud (incremental — only download missing blobs).
   * Reconstructs full message list from blob store.
   */
  def pullSessionIncremental(sessionId: String): IO[Unit] =
    for
      loggedIn <- meshService.isLoggedIn
      _ <-
        if !loggedIn then IO.unit
        else pullSessionFromCloud(sessionId)
    yield ()

  private def pullSessionFromCloud(sessionId: String): IO[Unit] =
    for
      resp <- meshService.callCloudFunction(
        "session/log-sync",
        "sessionId" -> sessionId.asJson,
        "messageHashes" -> List.empty[String].asJson,
        "uiMessageHashes" -> List.empty[String].asJson
      )
      cloudMsgHashes = resp.hcursor.downField("messageHashes").as[List[String]].getOrElse(Nil)
      cloudUiHashes = resp.hcursor.downField("uiMessageHashes").as[List[String]].getOrElse(Nil)
      _ <-
        if cloudMsgHashes.isEmpty && cloudUiHashes.isEmpty then IO.unit
        else downloadAndReconstruct(sessionId, cloudMsgHashes, cloudUiHashes)
    yield ()

  private def downloadAndReconstruct(
    sessionId: String,
    cloudMsgHashes: List[String],
    cloudUiHashes: List[String]
  ): IO[Unit] =
    val msgToDownload = cloudMsgHashes.filterNot(blobSync.hasLocally)
    val uiToDownload = cloudUiHashes.filterNot(blobSync.hasLocally)
    for
      _ <- blobSync.download(msgToDownload ++ uiToDownload)
      msgBytes <- blobSync.download(cloudMsgHashes)
      uiBytes <- blobSync.download(cloudUiHashes)
      messages = cloudMsgHashes.flatMap { h =>
        msgBytes.get(h).flatMap(b => decode[Message](new String(b, "UTF-8")).toOption)
      }
      uiMessages = cloudUiHashes.flatMap { h =>
        uiBytes.get(h).flatMap(b => decode[UiMessage](new String(b, "UTF-8")).toOption)
      }
      _ <- sessionStore.setSessionFromCloud(sessionId, messages, uiMessages)
    yield ()
  end downloadAndReconstruct

  // ===== File Sync (incremental, same blob approach) =====

  /**
   * Sync local files (memory.md, skills, etc.) using blob storage.
   * Computes hashes, compares with cloud, transfers only changed content.
   */
  def syncFilesIncremental: IO[Unit] =
    for
      loggedIn <- meshService.isLoggedIn
      _ <-
        if !loggedIn then IO.unit
        else
          for
            localFps <- meshService.computeLocalFingerprints
            localHashes = localFps.map { case (path, fp) => (path, fp.hash) }
            resp <- meshService.callCloudFunction("file/ref-sync-v2", "files" -> localHashes.asJson)
            needUpload = resp.hcursor.downField("needUpload").as[List[String]].getOrElse(Nil)
            needDownload = resp.hcursor.downField("needDownload").as[List[String]].getOrElse(Nil)
            cloudFiles = resp.hcursor.downField("files").as[Map[String, String]].getOrElse(Map.empty)
            // Upload changed files
            uploadBlobs <- needUpload
              .traverse { path =>
                meshService.readLocalFile(path).map {
                  case Some((content, _)) => Some(blobSync.hash(content) -> content)
                  case None => None
                }
              }
              .map(_.flatten.toMap)
            _ <- blobSync.uploadMissing(uploadBlobs)
            // Download changed files
            downloadHashes = needDownload.flatMap(p => cloudFiles.get(p))
            downloaded <- blobSync.download(downloadHashes)
            _ <- needDownload.traverse_ { path =>
              cloudFiles.get(path).flatMap(h => downloaded.get(h)) match
                case Some(content) => meshService.writeLocalFile(path, content)
                case None => IO.unit
            }
            // Notify peers if we uploaded changed files
            _ <- if uploadBlobs.nonEmpty then notifyPeers("file") else IO.unit
          yield ()
    yield ()

  // ===== Peer Notification (push-based, bypasses polling) =====

  import sttp.client4.*
  import scala.concurrent.duration.*

  /** Notify all reachable peers to trigger immediate sync. Fire-and-forget. */
  def notifyPeers(notifType: String, sessionId: String = ""): IO[Unit] =
    for
      peers <- meshService.peers
      _ <- peers.traverse_ { peer =>
        notifyOne(peer, notifType, sessionId).handleErrorWith(_ => IO.unit)
      }
    yield ()

  private def notifyOne(peer: PeerInfo, notifType: String, sessionId: String): IO[Unit] =
    if peer.address.isEmpty then IO.unit
    else
      IO.blocking {
        val body =
          if sessionId.nonEmpty then s"""{"type":"$notifType","sessionId":"$sessionId"}"""
          else s"""{"type":"$notifType"}"""
        try
          basicRequest
            .post(sttp.model.Uri.unsafeParse(s"${peer.address}/api/mesh/notify"))
            .contentType("application/json")
            .body(body)
            .readTimeout(2.seconds)
            .response(asStringAlways)
            .send(meshService.httpBackend)
          ()
        catch case _: Exception => ()
      }

  // ===== Fast Sync Cycle (fallback, 10s) =====

  /** Lightweight cycle: state sync only (metadata). Fallback for when push notification fails. */
  def fastSyncCycle: IO[Unit] = stateSync

  // ===== Full Sync Cycle (5 minutes) =====

  /** Full cycle: state sync + file sync + push all sessions. */
  def fullSyncCycle: IO[Unit] =
    for
      _ <- stateSync.handleErrorWith(e => logger.debug(s"State sync: ${e.getMessage}"))
      _ <- syncFilesIncremental.handleErrorWith(e => logger.debug(s"File sync: ${e.getMessage}"))
      // Push all local sessions (incremental — only new blobs)
      sessions <- sessionStore.listSessions
      _ <- sessions.traverse_(s =>
        pushSessionIncremental(s.id).handleErrorWith(e => logger.debug(s"Push ${s.id.take(8)}: ${e.getMessage}"))
      )
    yield ()

end IncrementalSyncEngine

// ===== Data Types =====

object IncrementalSyncEngine:

  def apply(
    meshService: MeshService,
    sessionStore: SessionStore
  ): IncrementalSyncEngine =
    new IncrementalSyncEngine(meshService, sessionStore, BlobSyncService(meshService))
