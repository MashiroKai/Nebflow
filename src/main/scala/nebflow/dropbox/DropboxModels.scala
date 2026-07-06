package nebflow.dropbox

import io.circe.generic.semiauto.*
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}

import java.util.UUID

// ===== Dropbox Message =====

/** A single message in the dropbox conversation with a peer. */
case class DropboxMessage(
  msgId: String,
  direction: String, // "out" | "in"
  kind: String, // "text" | "file"
  ts: Long,
  text: String = "",
  // File-specific fields
  transferId: String = "",
  fileName: String = "",
  fileSize: Long = 0,
  mimeType: String = "",
  status: String = "", // "pending" | "accepted" | "rejected" | "transferring" | "completed" | "failed"
  savedPath: String = "" // where the file was saved (receiver side, after completion)
)

object DropboxMessage:
  given Encoder[DropboxMessage] = deriveEncoder
  given Decoder[DropboxMessage] = deriveDecoder

// ===== File Transfer State (in-memory, not persisted) =====

/** Tracks the lifecycle of a file transfer. */
case class FileTransfer(
  transferId: String,
  direction: String, // "out" | "in"
  peerDeviceId: String,
  peerAddress: String, // peer's HTTP base URL (for outgoing)
  fileName: String,
  fileSize: Long,
  mimeType: String,
  msgId: String,
  status: String, // "pending" | "accepted" | "rejected" | "transferring" | "completed" | "failed"
  tempPath: String = "", // temp file path on receiver
  receiverHash: String = "" // SHA-256 computed by receiver
)

object DropboxModels:
  /** Generate a short unique ID for messages and transfers. */
  def newId: String = UUID.randomUUID().toString.take(12)

  /** Current epoch milliseconds. */
  def now: Long = System.currentTimeMillis()
end DropboxModels
