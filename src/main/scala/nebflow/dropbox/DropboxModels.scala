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
  // ===== 设备会话统一批 MVP-1（2026-09-15）：消息来源标记 =====
  /** 该消息的**作者面**：`"user"`（人发的，默认）/ `"agent"`（本机 agent 经
    * `SendMessage to=device:` 代发，`FriendMessageTool.sendDevice`）。
    *
    * 默认值保证**旧 JSON 兼容**（`messages.json` 里无该键的历史消息照旧解码 = user）。
    * 客户端 `messages.js` 的 `isAgentSent` 直读此字段渲染「Agent 代发」徽章。
    *
    * 🔴 收端纪律（设计卡 §9 P3）：该字段由**发送端网关**权威写入；收端**不得**把 wire
    * 传来的 `origin` 直读为「agent 发」的定论（可作**提示级**渲染）。服务端强制属 MVP-2。 */
  origin: String = "user",
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
  /** `origin` 的两个合法取值（单一来源；调用方不得写字面量）。 */
  val OriginUser  = "user"
  val OriginAgent = "agent"

  /** 读 wire / 旧 JSON 的 `origin`：**未知值一律回落 `OriginUser`**（fail-safe 方向 =
    * 「不声称是 agent 发的」——徽章是加强断言，缺证据不得自证）。 */
  def normalizeOrigin(raw: String): String =
    if raw == OriginAgent then OriginAgent else OriginUser

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
  proto: Int = 0, // 0 = legacy 整件，1 = 分块，2 = 分块 + 接收端指定目录（AttachContract.ProtoAssignDir）
  // ===== 设备腿 targetDir（契约升版批，2026-09-14）=====
  // 全部带默认值 ⇒ 旧 transfers.json（无这些键）照旧解码，向后兼容。
  /** 落点目录。
    *   - 接收端（direction = "in"）：§③ 判定链**通过后**的 canonical 落点 —— 判定结果
    *     在 `file-offer` 阶段固化一次，收块/commit 阶段**不得**重新解释字符串；
    *   - 发送端（direction = "out"）：本次请求的 `targetDir`（NFC 形态，回显用）。
    * `None` = 缺省语义（落 `DropboxUtil.downloadsDir`），与今天逐字节一致。 */
  targetDir: Option[String] = None,
  /** 接收端裁定：非空 = 该请求被拒（`AttachContract.Codes.TargetDir*`），落点不生效。 */
  targetDirCode: Option[String] = None,
  /** 发送端：对端自报的 proto 等级（来源 `file-response.proto`）。`None` = 未知/旧端
    * ⇒ 按 §4.2 候选 1「未确认等级 ⇒ 不发 `targetDir`」。 */
  peerProto: Option[Int] = None,
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
