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
  savedPath: String = "", // where the file was saved (receiver side, after completion)
  // ===== 单条消息多附件（附件腿批，2026-09-12）=====
  // 一条消息最多 9 件（AttachContract.MaxAttachmentsPerMessage）；N 件共用同一 batchId，
  // 各自一个 attachmentIndex。默认值保证旧 JSON 兼容（旧消息 = 单件）。
  batchId: String = "",
  attachmentIndex: Int = 0,
  attachmentCount: Int = 1
)

object DropboxMessage:
  given Encoder[DropboxMessage] = deriveEncoder
  given Decoder[DropboxMessage] = deriveDecoder

// ===== File Transfer State (in-memory + throttled persistence) =====

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
  // P0 wtmove: `Option`, never a blank sentinel — an empty string used to
  // resolve to `os.pwd` downstream, i.e. a transfer with no located temp file
  // silently renamed the *process working directory* into ~/Downloads.
  // `None` = "no temp file recorded for this transfer" (relay direct delivery,
  // or a restart rebuild that found no leftover), which is an explicit state.
  tempPath: Option[String] = None,
  receiverHash: String = "", // SHA-256 computed by receiver
  // ===== 分块通道字段（附件腿批，2026-09-12）=====
  // 全部带默认值 ⇒ 旧 JSON（无这些键）照旧解码，向后兼容。
  totalBytes: Long = 0L, // 整件字节数（与 fileSize 同源，分块模式下为权威值）
  chunkSize: Int = 0, // 会话内恒定；0 = 未协商（legacy 整件模式）
  wholeSha256: String = "", // 发送端单遍算出的整件摘要
  bytesReceived: Long = 0L, // 接收端权威 offset（断点续传）
  proto: Int = 0, // 0 = legacy 整件，1 = 分块（AttachContract.ProtoChunked）
  lastProgressAt: Long = 0L // 最后一次字节进展（看门狗按它计时，非绝对时间）
)

object FileTransfer:
  // 会话持久层（transfers.json）用的编解码。字段全部带默认值 ⇒ 旧 JSON 可解码。
  given Encoder[FileTransfer] = deriveEncoder
  given Decoder[FileTransfer] = deriveDecoder

object DropboxModels:
  /** Generate a short unique ID for messages and transfers. */
  def newId: String = UUID.randomUUID().toString.take(12)

  /** Current epoch milliseconds. */
  def now: Long = System.currentTimeMillis()
end DropboxModels
