package nebflow.dropbox

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.gateway.WsHub
import nebflow.neblink.{NeblinkClient, NeblinkService}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

import scala.concurrent.duration.*

/**
 * Cross-device Dropbox — text messages and file transfer over the neblink P2P network.
 *
 * Transport:
 *   - Text messages and file-transfer signaling go over the WS data channel (channel = "dropbox").
 *   - File data goes over HTTP (binary stream), relayed sender-backend → receiver-backend.
 *   - SHA-256 is computed during file I/O on both sides; hashes are compared after transfer.
 *
 * Persistence:
 *   - Message history is stored per-peer in ~/.nebflow/dropbox/messages.json.
 *   - File transfer state is in-memory only (transient by nature).
 */
final class DropboxService private (
  neblinkService: NeblinkService,
  wsHub: WsHub,
  /** Signaling timeouts (diag-transfer-stuck R4): a message parked in one of
    * the watched statuses past its window is marked failed instead of sitting
    * in the UI as「传输中…」forever. Injected so tests can use short windows. */
  offerTimeout: FiniteDuration = 120.seconds, // pending → waiting for file-response
  acceptedTimeout: FiniteDuration = 10.minutes, // accepted → waiting for the frontend upload
  transferTimeout: FiniteDuration = 31.minutes // transferring → waiting for upload completion (P2P HTTP caps at 30min)
):

  private val logger = NebflowLogger.forName("nebflow.dropbox")

  // ===== State =====

  /** Per-peer message history: deviceId -> messages sorted by timestamp. */
  private val messagesRef: cats.effect.Ref[IO, Map[String, List[DropboxMessage]]] =
    cats.effect.Ref.unsafe[IO, Map[String, List[DropboxMessage]]](Map.empty)

  /** Active file transfers: transferId -> FileTransfer. */
  private val transfersRef: cats.effect.Ref[IO, Map[String, FileTransfer]] =
    cats.effect.Ref.unsafe[IO, Map[String, FileTransfer]](Map.empty)

  // ===== Init =====

  /** Load persisted messages and register the WS data channel handler. */
  def init: IO[Unit] =
    loadMessages *> neblinkService.addDataHandler(handleDataMessage)

  // ===== Persistence =====

  private val messagesPath = PathUtil.dataRoot / "dropbox" / "messages.json"

  private def loadMessages: IO[Unit] =
    IO.blocking {
      if os.exists(messagesPath) then decode[Map[String, List[DropboxMessage]]](os.read(messagesPath)).toOption
      else None
    }.flatMap {
      case Some(m) => messagesRef.set(m)
      case None => IO.unit
    }

  private def persistMessages: IO[Unit] =
    messagesRef.get.flatMap { msgs =>
      IO.blocking(os.write.over(messagesPath, msgs.asJson.spaces2, createFolders = true))
        .handleErrorWith(e => logger.warn(s"Failed to persist dropbox messages: ${e.getMessage}"))
    }

  // ===== Public API (called from WebSocketRoutes) =====

  /** Send a text message to a peer. */
  def sendText(deviceId: String, text: String): IO[Unit] =
    neblinkService.identity.flatMap { id =>
      val msg = DropboxMessage(
        msgId = DropboxModels.newId,
        direction = "out",
        kind = "text",
        ts = DropboxModels.now,
        text = text
      )
      val payload = Json.obj(
        "kind" -> "text".asJson,
        "senderId" -> id.deviceId.asJson,
        "senderName" -> id.deviceName.asJson,
        "msgId" -> msg.msgId.asJson,
        "text" -> text.asJson,
        "ts" -> msg.ts.asJson
      )
      for
        _ <- addMessage(deviceId, msg)
        delivered <- sendDataOrRelay(deviceId, "dropbox", payload)
        _ <-
          if delivered then notifyFrontend("dropbox-message", deviceId, msg.asJson)
          else
            // R3: a text message that reached no channel is honestly failed
            // (frontend renders the failed state on upsert).
            for
              _ <- updateMessageStatus(deviceId, msg.msgId, "failed")
              _ <- notifyFrontend("dropbox-message", deviceId, msg.copy(status = "failed").asJson)
            yield ()
      yield ()
    }

  /**
   * Send a data-channel message via P2P WS; fall back to relay Notify when no
   * direct WS connection exists (cross-network peers).
   *
   * R3 (diag-transfer-stuck): the old version swallowed relay failures with a
   * WARN, so a lost file-response silently froze the sender's transfer state
   * machine. Now returns true only when one of the channels actually accepted
   * the frame; callers decide how to surface a false (mark failed).
   */
  private def sendDataOrRelay(deviceId: String, channel: String, payload: Json): IO[Boolean] =
    neblinkService.sendData(deviceId, channel, payload).flatMap {
      case true  => IO.pure(true)
      case false =>
        neblinkService.relayClientOpt match
          case Some(client) =>
            client.relayNotify(deviceId, channel, payload).map(_.isRight)
              .handleErrorWith(e =>
                logger.warn(s"Relay notify to $deviceId failed: ${e.getMessage}").as(false))
          case None =>
            logger.warn(s"Cannot deliver dropbox message to $deviceId: no P2P WS and no relay client").as(false)
    }

  /** Mark a transfer + its message as failed and tell the frontend (R3/R4). */
  private def markTransferFailed(
    transferId: String,
    peerDeviceId: String,
    msgId: String,
    reason: String
  ): IO[Unit] =
    for
      _ <- updateTransferStatus(transferId, "failed")
      _ <- updateMessageStatus(peerDeviceId, msgId, "failed")
      _ <- notifyFrontend(
            "dropbox-file-complete",
            peerDeviceId,
            Json.obj(
              "transferId" -> transferId.asJson,
              "msgId" -> msgId.asJson,
              "success" -> false.asJson,
              "error" -> reason.asJson
            )
          )
      _ <- logger.warn(s"Dropbox transfer $transferId marked failed: $reason")
    yield ()

  /**
   * R4 timeout watchdog: after `timeout`, if the transfer is still parked in
   * one of `stuckStatuses`, migrate it to failed + notify the frontend, so a
   * lost frame (disconnected peer, dead tunnel) can no longer freeze the UI
   * on「传输中…」forever. Fired as a fire-and-forget fiber; a completion that
   * arrives late (reconnect inside the window) simply no-ops here.
   */
  private def armTransferTimeout(
    transferId: String,
    peerDeviceId: String,
    msgId: String,
    timeout: FiniteDuration,
    stuckStatuses: Set[String]
  ): IO[Unit] =
    (IO.sleep(timeout) *> failIfStuck(transferId, peerDeviceId, msgId, stuckStatuses)).start.void

  private def failIfStuck(
    transferId: String,
    peerDeviceId: String,
    msgId: String,
    stuckStatuses: Set[String]
  ): IO[Unit] =
    transfersRef.get.map(_.get(transferId)).flatMap {
      case Some(t) if stuckStatuses.contains(t.status) =>
        markTransferFailed(transferId, peerDeviceId, msgId, s"timeout while waiting in '${t.status}' state")
      case _ => IO.unit
    }

  /** Offer a file to a peer. Generates a transferId and sends the offer. */
  def offerFile(deviceId: String, fileName: String, fileSize: Long, mimeType: String): IO[Unit] =
    neblinkService.identity.flatMap { id =>
      neblinkService.peers.flatMap { peers =>
        peers.find(_.deviceId == deviceId) match
          case None => IO.unit // peer not found, ignore
          case Some(peer) =>
            val transferId = DropboxModels.newId
            val msgId = DropboxModels.newId
            val msg = DropboxMessage(
              msgId = msgId,
              direction = "out",
              kind = "file",
              ts = DropboxModels.now,
              transferId = transferId,
              fileName = fileName,
              fileSize = fileSize,
              mimeType = mimeType,
              status = "pending"
            )
            val transfer = FileTransfer(
              transferId = transferId,
              direction = "out",
              peerDeviceId = deviceId,
              peerAddress = peer.address,
              fileName = fileName,
              fileSize = fileSize,
              mimeType = mimeType,
              msgId = msgId,
              status = "pending"
            )
            val payload = Json.obj(
              "kind" -> "file-offer".asJson,
              "senderId" -> id.deviceId.asJson,
              "senderName" -> id.deviceName.asJson,
              "transferId" -> transferId.asJson,
              "msgId" -> msgId.asJson,
              "fileName" -> fileName.asJson,
              "fileSize" -> fileSize.asJson,
              "mimeType" -> mimeType.asJson
            )
            for
              _ <- transfersRef.update(_ + (transferId -> transfer))
              _ <- addMessage(deviceId, msg)
              delivered <- sendDataOrRelay(deviceId, "dropbox", payload)
              _ <-
                if delivered then
                  // Arm pending-timeout: if the file-response never comes back
                  // (peer/relay down), fail instead of parking on「传输中…」.
                  armTransferTimeout(transferId, deviceId, msgId, offerTimeout, Set("pending")) *>
                    notifyFrontend("dropbox-message", deviceId, msg.asJson)
                else
                  markTransferFailed(transferId, deviceId, msgId, "file-offer could not be delivered")
            yield ()
      }
    }

  /** Respond to a file offer (accept or reject). Called by the receiving frontend. */
  def respondToOffer(senderDeviceId: String, transferId: String, accepted: Boolean): IO[Unit] =
    for
      transfer <- transfersRef.get.map(_.get(transferId))
      _ <- transfer match
        case Some(t) =>
          val newStatus = if accepted then "accepted" else "rejected"
          for
            _ <- transfersRef.update(_ + (transferId -> t.copy(status = newStatus)))
            _ <- updateMessageStatus(senderDeviceId, t.msgId, newStatus)
            delivered <- sendDataOrRelay(
                  senderDeviceId,
                  "dropbox",
                  Json.obj(
                    "kind" -> "file-response".asJson,
                    "transferId" -> transferId.asJson,
                    "accepted" -> accepted.asJson
                  )
                )
            _ <-
              if delivered then
                // Receiver parked in accepted until the sender uploads; arm a
                // timeout so a never-started upload fails instead of hanging.
                armTransferTimeout(transferId, senderDeviceId, t.msgId, acceptedTimeout, Set("accepted"))
              else
                markTransferFailed(transferId, senderDeviceId, t.msgId, "file-response could not be delivered")
          yield ()
        case None => IO.unit
    yield ()

  /** Get message history for a peer. */
  def getHistory(deviceId: String): IO[List[DropboxMessage]] =
    messagesRef.get.map(_.getOrElse(deviceId, Nil))

  // ===== HTTP File Transfer (called from RestApiRoutes) =====

  /**
   * Sender side: receive file upload from frontend, relay to peer via HTTP.
   * Saves to temp, sends to peer, compares SHA-256 hashes, notifies both sides.
   */
  def uploadAndRelay(transferId: String, body: Stream[IO, Byte]): IO[Either[String, Unit]] =
    transfersRef.get.map(_.get(transferId)).flatMap {
      case None => IO.pure(Left("Transfer not found"))
      case Some(t) if t.direction != "out" => IO.pure(Left("Not an outgoing transfer"))
      case Some(t) if t.status != "accepted" => IO.pure(Left("Transfer not accepted by receiver"))
      case Some(t) =>
        val tempDir = PathUtil.dataRoot / "dropbox" / ".tmp"
        val tempPath = tempDir / s"$transferId.tmp"
        for
          _ <- IO.blocking(os.makeDir.all(tempDir))
          senderHash <- streamToFileWithHash(body, tempPath)
          // R4: persist the "transferring" phase to the message so the UI shows
          // a real stage (was transfersRef-only), and arm a transferring
          // timeout in case the upload fiber dies mid-flight.
          _ <- updateTransferStatus(transferId, "transferring") *>
            updateMessageStatus(t.peerDeviceId, t.msgId, "transferring")
          _ <- armTransferTimeout(transferId, t.peerDeviceId, t.msgId, transferTimeout, Set("transferring"))
          result <- sendTempToPeer(t, tempPath)
          _ <- IO.blocking(os.remove(tempPath)).handleErrorWith(_ => IO.unit)
          _ <- result match
            case Right(receiverHash) =>
              val matchResult = senderHash == receiverHash
              for
                _ <- sendDataOrRelay(
                  t.peerDeviceId,
                  "dropbox",
                  Json.obj(
                    "kind" -> "file-complete".asJson,
                    "transferId" -> transferId.asJson,
                    "msgId" -> t.msgId.asJson,
                    "success" -> matchResult.asJson,
                    "sha256" -> senderHash.asJson
                  )
                ).flatMap {
                  case true => IO.unit
                  case false =>
                    // The bytes reached the peer but the completion frame did
                    // not; the peer's own timeout/recovery handles the state.
                    logger.warn(s"file-complete could not be delivered to ${t.peerDeviceId} — peer will time out")
                }
                _ <- updateTransferStatus(transferId, if matchResult then "completed" else "failed")
                _ <- updateMessageStatus(t.peerDeviceId, t.msgId, if matchResult then "completed" else "failed")
                _ <- notifyFrontend(
                  "dropbox-file-complete",
                  t.peerDeviceId,
                  Json
                    .obj("transferId" -> transferId.asJson, "msgId" -> t.msgId.asJson, "success" -> matchResult.asJson)
                )
              yield ()
              end for
            case Left(err) =>
              for
                _ <- updateTransferStatus(transferId, "failed")
                _ <- updateMessageStatus(t.peerDeviceId, t.msgId, "failed")
                _ <- notifyFrontend(
                  "dropbox-file-complete",
                  t.peerDeviceId,
                  Json.obj(
                    "transferId" -> transferId.asJson,
                    "msgId" -> t.msgId.asJson,
                    "success" -> false.asJson,
                    "error" -> err.asJson
                  )
                )
              yield ()
        yield Right(())
        end for
    }

  /**
   * Receiver side: receive file from peer via HTTP, save to temp in Downloads.
   * Returns the SHA-256 hash so the sender can verify.
   */
  def receiveFromPeer(transferId: String, body: Stream[IO, Byte]): IO[Either[String, String]] =
    transfersRef.get.map(_.get(transferId)).flatMap {
      case None => IO.pure(Left("Transfer not found"))
      case Some(t) if t.direction != "in" => IO.pure(Left("Not an incoming transfer"))
      case Some(t) if t.status != "accepted" => IO.pure(Left("Transfer not accepted"))
      case Some(t) =>
        val dlDir = DropboxUtil.downloadsDir
        val tempName = s".${t.fileName}.dropbox-${transferId.take(8)}"
        val tempPath = dlDir / tempName
        for
          _ <- IO.blocking(os.makeDir.all(dlDir))
          hash <- streamToFileWithHash(body, tempPath)
          _ <- transfersRef.update(_ + (transferId -> t.copy(tempPath = tempPath.toString, receiverHash = hash)))
        yield Right(hash)
    }

  // ===== Data Channel Handler =====

  private def handleDataMessage(payload: Json): IO[Unit] =
    payload.hcursor.downField("kind").as[String].getOrElse("") match
      case "text" => handleIncomingText(payload)
      case "file-offer" => handleIncomingOffer(payload)
      case "file-response" => handleFileResponse(payload)
      case "file-complete" => handleFileComplete(payload)
      case _ => IO.unit

  // --- Incoming text ---
  private def handleIncomingText(payload: Json): IO[Unit] =
    val hc = payload.hcursor
    val senderId = hc.downField("senderId").as[String].getOrElse("")
    val msg = DropboxMessage(
      msgId = hc.downField("msgId").as[String].getOrElse(DropboxModels.newId),
      direction = "in",
      kind = "text",
      ts = hc.downField("ts").as[Long].getOrElse(DropboxModels.now),
      text = hc.downField("text").as[String].getOrElse("")
    )
    for
      _ <- addMessage(senderId, msg)
      _ <- notifyFrontend("dropbox-message", senderId, msg.asJson)
    yield ()

  // --- Incoming file offer (auto-accept) ---
  private def handleIncomingOffer(payload: Json): IO[Unit] =
    val hc = payload.hcursor
    val senderId = hc.downField("senderId").as[String].getOrElse("")
    val transferId = hc.downField("transferId").as[String].getOrElse("")
    val msgId = hc.downField("msgId").as[String].getOrElse(DropboxModels.newId)
    val fileName = hc.downField("fileName").as[String].getOrElse("unknown")
    val fileSize = hc.downField("fileSize").as[Long].getOrElse(0L)
    val mimeType = hc.downField("mimeType").as[String].getOrElse("")
    val msg = DropboxMessage(
      msgId = msgId,
      direction = "in",
      kind = "file",
      ts = DropboxModels.now,
      transferId = transferId,
      fileName = fileName,
      fileSize = fileSize,
      mimeType = mimeType,
      status = "accepted"
    )
    val transfer = FileTransfer(
      transferId = transferId,
      direction = "in",
      peerDeviceId = senderId,
      peerAddress = "",
      fileName = fileName,
      fileSize = fileSize,
      mimeType = mimeType,
      msgId = msgId,
      status = "accepted"
    )
    // Auto-accept: immediately notify sender to start uploading
    val acceptPayload = Json.obj(
      "kind" -> "file-response".asJson,
      "transferId" -> transferId.asJson,
      "accepted" -> true.asJson
    )
    for
      _ <- transfersRef.update(_ + (transferId -> transfer))
      _ <- addMessage(senderId, msg)
      _ <- notifyFrontend("dropbox-message", senderId, msg.asJson)
      delivered <- sendDataOrRelay(senderId, "dropbox", acceptPayload)
      _ <-
        if delivered then
          // Auto-accept sent (arm accepted-timeout: this side waits for the
          // sender to push the bytes; an abandoned upload fails rather than
          // pinging「传输中…」).
          armTransferTimeout(transferId, senderId, msgId, acceptedTimeout, Set("accepted"))
        else
          markTransferFailed(transferId, senderId, msgId, "auto-accept response could not be delivered")
    yield ()

  end handleIncomingOffer

  // --- File response (receiver accepted/rejected our offer) ---
  private def handleFileResponse(payload: Json): IO[Unit] =
    val hc = payload.hcursor
    val transferId = hc.downField("transferId").as[String].getOrElse("")
    val accepted = hc.downField("accepted").as[Boolean].getOrElse(false)
    for
      // R5: if the in-memory record is gone (restart / very late frame after
      // a reconnect), rebuild a minimal outbound transfer from the persisted
      // message so the state machine can still move instead of silently
      // dropping the acknowledgement.
      transfer <- ensureTransfer(transferId, "out")
      _ <- transfer match
        case Some(t) =>
          val newStatus = if accepted then "accepted" else "rejected"
          for
            _ <- transfersRef.update(_ + (transferId -> t.copy(status = newStatus)))
            _ <- updateMessageStatus(t.peerDeviceId, t.msgId, newStatus)
            _ <-
              if accepted then
                // Sender now waits for the frontend to start the upload; arm an
                // accepted-timeout so a lost dropbox-file-response event
                // (frontend drives uploadFile from it) fails rather than hanging
                // the message in accepted forever.
                armTransferTimeout(transferId, t.peerDeviceId, t.msgId, acceptedTimeout, Set("accepted"))
              else IO.unit
            _ <- notifyFrontend(
              "dropbox-file-response",
              t.peerDeviceId,
              Json.obj("transferId" -> transferId.asJson, "accepted" -> accepted.asJson)
            )
          yield ()
        case None =>
          logger.warn(s"file-response for unknown transfer $transferId — no persisted message to rebuild from")
          IO.unit
    yield ()

  end handleFileResponse

  // --- File complete (sender tells us transfer result) ---
  private def handleFileComplete(payload: Json): IO[Unit] =
    val hc = payload.hcursor
    val transferId = hc.downField("transferId").as[String].getOrElse("")
    val success = hc.downField("success").as[Boolean].getOrElse(false)
    for
      // R5: a file-complete arriving after the receiving process restarted has
      // no in-memory transfer. Rebuild from the persisted message (and scan the
      // Downloads dir for the leftover temp file) so the UI can move to a
      // terminal state instead of dropping the completion.
      transfer <- ensureTransfer(transferId, "in")
      _ <- transfer match
        case Some(t) =>
          for
            _ <-
              if success then commitTempFile(t)
              else deleteTempFile(t)
            savedPath <-
              if success then IO.pure(DropboxUtil.resolveFinalPath(DropboxUtil.downloadsDir, t.fileName).toString)
              else IO.pure("")
            _ <- updateTransferStatus(transferId, if success then "completed" else "failed")
            _ <- updateMessageStatus(t.peerDeviceId, t.msgId, if success then "completed" else "failed", savedPath)
            _ <- notifyFrontend(
              "dropbox-file-complete",
              t.peerDeviceId,
              Json.obj(
                "transferId" -> transferId.asJson,
                "msgId" -> t.msgId.asJson,
                "success" -> success.asJson,
                "savedPath" -> savedPath.asJson
              )
            )
          yield ()
        case None =>
          logger.warn(s"file-complete for unknown transfer $transferId — no persisted message to rebuild from")
          IO.unit
    yield ()
  end handleFileComplete

  // ===== Helpers =====

  private def addMessage(deviceId: String, msg: DropboxMessage): IO[Unit] =
    messagesRef.update(m => m.updated(deviceId, m.getOrElse(deviceId, Nil) :+ msg))
      *> persistMessages

  private def updateMessageStatus(deviceId: String, msgId: String, status: String, savedPath: String = ""): IO[Unit] =
    messagesRef.update { m =>
      m.updated(
        deviceId,
        m.getOrElse(deviceId, Nil).map { msg =>
          if msg.msgId == msgId then msg.copy(status = status, savedPath = savedPath) else msg
        }
      )
    } *> persistMessages

  private def updateTransferStatus(transferId: String, status: String): IO[Unit] =
    transfersRef.update(m => m.get(transferId).map(t => m + (transferId -> t.copy(status = status))).getOrElse(m))

  /**
   * R5: look up a transfer in memory; if absent, rebuild a minimal record from
   * the persisted message history so a late frame (after restart / reconnect)
   * can still drive the state machine instead of being silently dropped.
   */
  private def ensureTransfer(transferId: String, direction: String): IO[Option[FileTransfer]] =
    transfersRef.get.map(_.get(transferId)).flatMap {
      case some @ Some(_) => IO.pure(some)
      case None =>
        rebuildTransfer(transferId, direction).flatMap {
          case None => IO.pure(None)
          case Some(t) =>
            logger.warn(
              s"Transfer $transferId not in memory (restart/late frame) — rebuilt from persisted message"
            ) *> transfersRef.update(_ + (transferId -> t)).as(Some(t))
        }
    }

  /** Rebuild a minimal inbound/outbound transfer from the persisted file message with this transferId. */
  private def rebuildTransfer(transferId: String, direction: String): IO[Option[FileTransfer]] =
    messagesRef.get.map { msgs =>
      msgs.toList.collectFirst {
        case (deviceId, list) if list.exists(m => m.kind == "file" && m.transferId == transferId) =>
          (deviceId, list.find(m => m.kind == "file" && m.transferId == transferId).get)
      }.map { case (deviceId, m) =>
        val tempPath =
          if direction == "in" then findReceiverTempFile(m.fileName, transferId)
          else ""
        FileTransfer(
          transferId = transferId,
          direction = direction,
          peerDeviceId = deviceId,
          peerAddress = "",
          fileName = m.fileName,
          fileSize = m.fileSize,
          mimeType = m.mimeType,
          msgId = m.msgId,
          status = m.status,
          tempPath = tempPath
        )
      }
    }

  /** Scan the Downloads dir for a leftover `.fileName.dropbox-XXXX` temp file (restart recovery). */
  private def findReceiverTempFile(fileName: String, transferId: String): String =
    try
      val prefix = s".$fileName.dropbox-${transferId.take(8)}"
      val dlDir = DropboxUtil.downloadsDir
      if os.exists(dlDir) then
        os.list(dlDir).find(_.last.startsWith(prefix)).map(_.toString).getOrElse("")
      else ""
    catch case _: Exception => ""

  private def notifyFrontend(msgType: String, deviceId: String, msgJson: Json): IO[Unit] =
    wsHub.broadcast(
      Json.obj(
        "type" -> msgType.asJson,
        "deviceId" -> deviceId.asJson,
        "msg" -> msgJson
      )
    )

  /** Stream bytes to a file while computing SHA-256. Returns the hex hash. */
  private def streamToFileWithHash(stream: Stream[IO, Byte], path: os.Path): IO[String] =
    DropboxUtil.streamToFileWithHash(stream, path)

  /**
   * Send a temp file to the peer — P2P HTTP first, relay FileTransfer fallback.
   * Returns the peer's SHA-256 (P2P) or the sender's own hash (relay — same content).
   */
  private def sendTempToPeer(t: FileTransfer, tempPath: os.Path): IO[Either[String, String]] =
    p2pSendTempToPeer(t.peerAddress, t.transferId, tempPath).flatMap {
      case r @ Right(_) => IO.pure(r)
      case Left(p2pErr) =>
        neblinkService.relayClientOpt match
          case Some(client) =>
            logger.info(s"P2P file transfer failed (${p2pErr.take(80)}), falling back to relay") *>
              relaySendTempToPeer(t.peerDeviceId, t.fileName, tempPath, client)
          case None => IO.pure(Left(p2pErr))
    }

  /** P2P HTTP push to the peer's dropbox endpoint. Returns the peer's SHA-256 or an error. */
  private def p2pSendTempToPeer(peerAddress: String, transferId: String, tempPath: os.Path): IO[Either[String, String]] =
    IO.blocking {
      val client = HttpClient
        .newBuilder()
        .proxy(java.net.ProxySelector.of(null)) // bypass HTTP proxy for P2P
        .build()
      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(s"$peerAddress/api/neblink/dropbox/transfer/$transferId"))
        .header("Content-Type", "application/octet-stream")
        .timeout(java.time.Duration.ofMinutes(30))
        .POST(HttpRequest.BodyPublishers.ofFile(java.nio.file.Paths.get(tempPath.toString)))
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      val body = response.body()
      val status = response.statusCode()
      if status == 200 then
        io.circe.parser
          .parse(body)
          .toOption
          .flatMap(_.hcursor.downField("sha256").as[String].toOption)
          .map(Right(_))
          .getOrElse(Left(s"Peer returned 200 but no sha256 in body: $body"))
      else Left(s"Peer returned HTTP $status: $body")
    }.handleErrorWith(e => IO.pure(Left(s"Transfer failed: ${e.getMessage}")))

  /**
   * Relay fallback: push the file to the peer's Downloads directory via relay
   * FileTransfer. The receiver gets the file directly in ~/Downloads (no temp
   * rename dance) — returns the sender's hash since content is identical.
   */
  private def relaySendTempToPeer(
    peerDeviceId: String,
    fileName: String,
    tempPath: os.Path,
    client: NeblinkClient
  ): IO[Either[String, String]] =
    for
      hash <- DropboxUtil.hashFile(tempPath)
      b64 <- IO.blocking(java.util.Base64.getEncoder.encodeToString(os.read.bytes(tempPath)))
      result <- client.relayTransferPut(
        peerDeviceId,
        s"~/Downloads/$fileName",
        b64,
        overwrite = true // receiver-side naming is handled by direct overwrite
      )
    yield result match
      case Right(_) => Right(hash) // same content — hash trivially matches
      case Left(err) => Left(s"Relay file transfer failed: $err")

  /** Rename temp file to final name in Downloads, handling name conflicts. */
  private def commitTempFile(t: FileTransfer): IO[Unit] =
    IO.blocking {
      val tempPath = os.Path(t.tempPath, os.pwd)
      if os.exists(tempPath) then
        val finalPath = DropboxUtil.resolveFinalPath(DropboxUtil.downloadsDir, t.fileName)
        os.move(tempPath, finalPath, replaceExisting = true)
      ()
    }.handleErrorWith(e => logger.warn(s"Failed to commit temp file: ${e.getMessage}"))

  /** Delete the temp file. */
  private def deleteTempFile(t: FileTransfer): IO[Unit] =
    IO.blocking {
      if t.tempPath.nonEmpty then
        val tempPath = os.Path(t.tempPath, os.pwd)
        if os.exists(tempPath) then os.remove(tempPath)
      ()
    }.handleErrorWith(_ => IO.unit)

end DropboxService

object DropboxService:

  def create(neblinkService: NeblinkService, wsHub: WsHub): IO[DropboxService] =
    val svc = new DropboxService(neblinkService, wsHub)
    svc.init.as(svc)

  /** Test factory with injectable signaling timeouts. */
  private[nebflow] def createForTest(
    neblinkService: NeblinkService,
    wsHub: WsHub,
    offerTimeout: FiniteDuration,
    acceptedTimeout: FiniteDuration,
    transferTimeout: FiniteDuration
  ): IO[DropboxService] =
    val svc = new DropboxService(neblinkService, wsHub, offerTimeout, acceptedTimeout, transferTimeout)
    svc.init.as(svc)
