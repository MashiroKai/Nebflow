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
  /**
   * 该消息的**作者面**：`"user"`（人发的，默认）/ `"agent"`（本机 agent 经
   * `SendMessage to=device:` 代发，`FriendMessageTool.sendDevice`）。
   *
   * 默认值保证**旧 JSON 兼容**（`messages.json` 里无该键的历史消息照旧解码 = user）。
   * 客户端 `messages.js` 的 `isAgentSent` 直读此字段渲染「Agent 代发」徽章。
   *
   * 🔴 收端纪律（设计卡 §9 P3）：该字段由**发送端网关**权威写入；收端**不得**把 wire
   * 传来的 `origin` 直读为「agent 发」的定论（可作**提示级**渲染）。服务端强制属 MVP-2。
   */
  origin: String = "user",
  // File-specific fields
  transferId: String = "",
  fileName: String = "",
  fileSize: Long = 0,
  mimeType: String = "",
  status: String = "", // "pending" | "accepted" | "rejected" | "transferring" | "completed" | "failed"
  savedPath: String = "", // where the file was saved (receiver side, after completion)
  // ===== 发送端本机真实路径（selfattach 批 · 作者 D-1 = A + B′ 混合裁定，2026-09-17）=====
  /**
   * **发送端**本机真实绝对路径（B′ 腿）。与 [[savedPath]] **不同轴**，禁互相借用：
   *
   *   - `savedPath` 逐字限**接收端落点**（作者 2026-09-17 裁定原文：`receiver side,
   *     after completion`）——语义在**收**侧；
   *   - 本键 = **发**侧自己的本地现实（「每侧记录自己的本地现实」的发送半边）。
   *
   *  唯一写者 = **工具 / agent 附件腿**（`DropboxService.sendLocalFiles` 的
   *  `sendLocalOne(…, p, …)`：字节从本机磁盘直读，`p` 已过绝对 / 存在 / 非目录三道校验）。
   *  浏览器 user 腿**没有**该值（`<input type=file>` 的 `File` 不暴露绝对路径；服务端
   *  staging temp 在完成前已删）⇒ 那侧恒 `""`（缺省 = 无值，**不猜、不拼接**）。
   *
   *  🔴 隐私（作者约束「记录发送端本地真实路径、**零复制**、**禁随 WS 帧广播对端**」）：
   *  本键**不上对端帧** —— 网关发往对端的载荷全部是手写 `Json.obj`
   *  （`offerOne` 的 `file-offer` / `file-response` / `completeTransfer` 的 `file-complete`），
   *  不含本键；它只随**本机**台账（`messages.json`）与**本机**前端帧
   *  （`dropbox-message` / `dropbox-get-history`）流动。
   *
   *  🔴 零字节复制 / 零留存：只记字符串本身，不产生任何副本、暂存件或缓存件。
   *
   *  默认值 ⇒ 旧 JSON（无该键）照旧解码 = 无值。
   */
  deviceOutPath: String = "",
  // ===== 单条消息多附件（附件腿批，2026-09-12）=====
  // 一条消息最多 9 件（AttachContract.MaxAttachmentsPerMessage）；N 件共用同一 batchId，
  // 各自一个 attachmentIndex。默认值保证旧 JSON 兼容（旧消息 = 单件）。
  batchId: String = "",
  attachmentIndex: Int = 0,
  attachmentCount: Int = 1,
  // ===== 失败原因（xferb 批 · P0-3，2026-09-20）=====
  /**
   * 结构化失败原因**码**（`AttachContract.Codes.*`，如 `PEER_UNREACHABLE`）。
   *
   * WHY 落台账：失败原因此前只随 `dropbox-file-complete` 一次性事件流动 ⇒ 刷新/重开窗后
   * 界面上只剩一个「失败」，作者原话「失败没有原因」正是这个形状（服务端知道原因，记录里没有）。
   * 默认值 ⇒ 旧 JSON（无该键）照旧解码 = 无码（**不伪造**）。
   */
  errorCode: String = "",
  /**
   * 结构化失败原因**全文**（`AttachError.toJson.noSpaces`，含 phase / chunkIndex /
   * p2pReason / relayReason 等可选字段）。与 [[errorCode]] 同轴：码供判路、全文供回显。
   * 只在本机台账 + 本机前端帧里流动（**不上对端帧** —— 对端原因以 `file-complete` 帧为准）。
   */
  errorDetail: String = ""
)

object DropboxMessage:
  /** `origin` 的两个合法取值（单一来源；调用方不得写字面量）。 */
  val OriginUser = "user"
  val OriginAgent = "agent"

  /**
   * 读 wire / 旧 JSON 的 `origin`：**未知值一律回落 `OriginUser`**（fail-safe 方向 =
   * 「不声称是 agent 发的」——徽章是加强断言，缺证据不得自证）。
   */
  def normalizeOrigin(raw: String): String =
    if raw == OriginAgent then OriginAgent else OriginUser

  // ===== 台账键缺席语义（单一来源 · 显式表）=====
  //
  // 作者 2026-09-17 #785 裁定（解码健壮化）：逐键容错、**禁整表失败**、「键缺失语义」显式化。
  // 原形态 `deriveDecoder`（半自动派生）**不套用** case class 默认值 ⇒ 台账里任一条目
  // 缺一个可缺键 ⇒ 该 `Map` 整体解码失败 ⇒ `loadMessages` 静默留空表 ⇒
  // `dropbox-get-history` 回 `[]` ⇒ 客户端（以帧为真相源）开窗抹掉本地已知消息。
  //
  // 两个键集**互相排斥且并集 = 本 case class 的全部 18 键**（`DropboxLedgerDecodeSpec` 断言）：
  //
  //   ① 必给键（缺席 / 类型不符 ⇒ **条目级**失败，由 `DropboxLedger.decode` 跳过该条并计数登记）：
  //      `msgId` / `direction` / `kind` / `ts`
  //      —— 判据：这四键承载**身份 / 排序 / 渲染分路**语义，缺了只能凭空构造
  //         （造 msgId 会让客户端 `mergeDeviceMessages` 的键身份失真；猜 direction 会把入向读成出向），
  //         故宁可**弃该条并留痕**，也不伪造。
  //   ② 可缺键（缺席 / 值为 `null` ⇒ 以**下表缺省值**补齐，**保留该条目**）：
  //
  //        | 键                | 缺省值   |
  //        |-------------------|----------|
  //        | `text`            | `""`     |
  //        | `origin`          | `"user"` |
  //        | `transferId`      | `""`     |
  //        | `fileName`        | `""`     |
  //        | `fileSize`        | `0L`     |
  //        | `mimeType`        | `""`     |
  //        | `status`          | `""`     |
  //        | `savedPath`       | `""`     |
  //        | `deviceOutPath`   | `""`     |
  //        | `batchId`         | `""`     |
  //        | `attachmentIndex` | `0`      |
  //        | `attachmentCount` | `1`      |
  //        | `errorCode`       | `""`     |
  //        | `errorDetail`     | `""`     |
  //
  // 🔴 缺省值与本 case class 的默认值**逐字相同**（表 = 默认值的镜像），但语义**不依赖**
  //    Scala 默认值机制——逐键语义显式落在下面的 `given Decoder` 里。
  // 🔴 「键在场」的值一律**逐字照读**（不夹带归一化）：既有全键台账的解码结果与改前**逐格相同**。

  /** 必给键（缺席即**条目级**失败；顺序即报告里的列举顺序）。 */
  val RequiredKeys: List[String] = List("msgId", "direction", "kind", "ts")

  /** 可缺键（缺席即按上表缺省值补齐；与 [[RequiredKeys]] 互补且并集 = 全部 18 键）。 */
  val OptionalKeys: List[String] = List(
    "text",
    "origin",
    "transferId",
    "fileName",
    "fileSize",
    "mimeType",
    "status",
    "savedPath",
    "deviceOutPath",
    "batchId",
    "attachmentIndex",
    "attachmentCount",
    "errorCode",
    "errorDetail"
  )

  given Encoder[DropboxMessage] = deriveEncoder

  /**
   * 显式 decoder（取代裸 `deriveDecoder`）——**逐键容错**，逐键语义如下：
   *
   *   - 必给键：`c.get[...]` ⇒ 缺席 / 类型不符 = `Left`（**该条目**失败；绝不放大成整表失败，
   *     整表聚合由 [[DropboxLedger.decode]] 负责跳过 + 计数）；
   *   - 可缺键：`c.get[Option[...]](key).map(_.getOrElse(default))` ⇒ 缺席 **或** `null` =
   *     缺省值补齐并**保留条目**；键在场但类型不符（如 `text` 是数字）= `Left`（条目级失败，
   *     不静默伪造）。这一区分正是「键缺失语义显式化」：**缺席 ≠ 类型错**。
   */
  given Decoder[DropboxMessage] = Decoder.instance { c =>
    for
      msgId <- c.get[String]("msgId")
      direction <- c.get[String]("direction")
      kind <- c.get[String]("kind")
      ts <- c.get[Long]("ts")
      text <- c.get[Option[String]]("text").map(_.getOrElse(""))
      origin <- c.get[Option[String]]("origin").map(_.getOrElse(OriginUser))
      transferId <- c.get[Option[String]]("transferId").map(_.getOrElse(""))
      fileName <- c.get[Option[String]]("fileName").map(_.getOrElse(""))
      fileSize <- c.get[Option[Long]]("fileSize").map(_.getOrElse(0L))
      mimeType <- c.get[Option[String]]("mimeType").map(_.getOrElse(""))
      status <- c.get[Option[String]]("status").map(_.getOrElse(""))
      savedPath <- c.get[Option[String]]("savedPath").map(_.getOrElse(""))
      deviceOutPath <- c.get[Option[String]]("deviceOutPath").map(_.getOrElse(""))
      batchId <- c.get[Option[String]]("batchId").map(_.getOrElse(""))
      attachmentIndex <- c.get[Option[Int]]("attachmentIndex").map(_.getOrElse(0))
      attachmentCount <- c.get[Option[Int]]("attachmentCount").map(_.getOrElse(1))
      errorCode <- c.get[Option[String]]("errorCode").map(_.getOrElse(""))
      errorDetail <- c.get[Option[String]]("errorDetail").map(_.getOrElse(""))
    yield DropboxMessage(
      msgId = msgId,
      direction = direction,
      kind = kind,
      ts = ts,
      text = text,
      origin = origin,
      transferId = transferId,
      fileName = fileName,
      fileSize = fileSize,
      mimeType = mimeType,
      status = status,
      savedPath = savedPath,
      deviceOutPath = deviceOutPath,
      batchId = batchId,
      attachmentIndex = attachmentIndex,
      attachmentCount = attachmentCount,
      errorCode = errorCode,
      errorDetail = errorDetail
    )
  }
end DropboxMessage

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
  /**
   * 落点目录。
   *   - 接收端（direction = "in"）：§③ 判定链**通过后**的 canonical 落点 —— 判定结果
   *     在 `file-offer` 阶段固化一次，收块/commit 阶段**不得**重新解释字符串；
   *   - 发送端（direction = "out"）：本次请求的 `targetDir`（NFC 形态，回显用）。
   * `None` = 缺省语义（落 `DropboxUtil.downloadsDir`），与今天逐字节一致。
   */
  targetDir: Option[String] = None,
  /** 接收端裁定：非空 = 该请求被拒（`AttachContract.Codes.TargetDir*`），落点不生效。 */
  targetDirCode: Option[String] = None,
  /**
   * 发送端：对端自报的 proto 等级（来源 `file-response.proto`）。`None` = 未知/旧端
   * ⇒ 按 §4.2 候选 1「未确认等级 ⇒ 不发 `targetDir`」。
   */
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

// ===== 台账（messages.json）逐条容错解码（作者 2026-09-17 #785 裁定）=====

/** 被跳过的一条台账条目 —— **有数、有声**（禁静默吞）。 */
final case class DropboxLedgerSkip(
  deviceId: String,
  /** 该条目在设备数组里的下标；`-1` = 整个设备值不是数组（设备级跳过）。 */
  index: Int,
  reason: String
)

/** 台账解码结果：解出的消息表 + 被跳过的条目明细（跳过数为 0 时为空）。 */
final case class DropboxLedgerDecode(
  messages: Map[String, List[DropboxMessage]],
  skipped: List[DropboxLedgerSkip]
)

object DropboxLedger:

  /**
   * 台账**逐条容错**解码（`~/.nebflow/dropbox/messages.json` 的唯一解码入口）。
   *
   *  分级失败语义（🔴 本批核心判据 = **禁整表失败**）：
   *   - **表级 fail-closed**（唯一）：非 JSON / 顶层不是对象 ⇒ `Left`。这一级没有「其余条目」
   *     可救，调用方按空表继续并 WARN；**文件本身零写回**。
   *   - **设备级容错**：某设备的值不是数组 ⇒ 该设备跳过 + 登记，其余设备照常解出。
   *   - **条目级容错**：条目缺可缺键 ⇒ 缺省补齐并保留；缺必给键 / 类型不符 / 元素非对象
   *     ⇒ **跳过该条 + 计数登记**，同表**其余条目必须正常解出**。
   *
   *  顺序与键值一律**原样保留**（不做排序、不做归一化）⇒ 全键台账的解码结果与改前逐格相同。
   */
  def decode(raw: String): Either[String, DropboxLedgerDecode] =
    io.circe.parser.parse(raw).left.map(e => s"invalid JSON: ${e.getMessage}").flatMap { json =>
      json.asObject match
        case None => Left("top-level JSON value is not an object")
        case Some(obj) =>
          val skipped = List.newBuilder[DropboxLedgerSkip]
          val out = scala.collection.mutable.LinkedHashMap.empty[String, List[DropboxMessage]]
          obj.toIterable.foreach { case (deviceId, value) =>
            value.asArray match
              case None =>
                // 设备级跳过：台账里出现过的设备键**仍在表里**（值为空），只是零条目 —— 与
                // 「数组里条目全被跳过」同形，调用方 `getHistory` 两条路径都回 `Nil`。
                skipped += DropboxLedgerSkip(deviceId, -1, "device value is not an array")
                out.update(deviceId, Nil)
              case Some(entries) =>
                val kept = List.newBuilder[DropboxMessage]
                entries.zipWithIndex.foreach { case (entry, idx) =>
                  entry.as[DropboxMessage] match
                    case Right(m) => kept += m
                    case Left(err) =>
                      skipped += DropboxLedgerSkip(deviceId, idx, err.message)
                }
                out.update(deviceId, kept.result())
          }
          Right(DropboxLedgerDecode(out.toMap, skipped.result()))
    }
end DropboxLedger
