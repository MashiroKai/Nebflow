package nebflow.dropbox

import io.circe.Json
import io.circe.syntax.*

/**
 * 附件通道冻结契约 —— **单一数值权威面**。
 *
 * 作者 2026-09-14 09:14 给定数（逐字：「把文件传输的上限增加到一个g。1024MB」，
 * 覆盖 2026-09-12 的 100 MB 口径）：
 *   - 单文件上限 = **1024 MB = 1 GiB = 1,073,741,824 B**（= 1024 MiB；作者「一个 g」
 *     与「1024MB」同指一处，取 1 GiB = 2³⁰ B）；
 *   - 单条消息附件 ≤ **9 件**（不变）；
 *   - 传输 = 分块/流式 + 每块校验 + 整件 sha256 + 断点续传（不变）。
 *   - 旧口径「单件 ≤100 MB 十进制（100,000,000 B）」**已作废**。
 *
 * ⚠️ 量纲变更留痕（审计用，禁倒推）：旧口径取**十进制** MB（100 × 10⁶），新口径取
 * **二进制** GiB（1024 × 2²⁰）。新值除以 10⁶ = 1073.741824 ⇒ 不可与十进制 MB 互推；
 * 标签一律写「1024 MB = 1 GiB (… bytes)」，避免再次 MB/MiB 混用。
 *
 * WHY 单点：量纲写错（MB/MiB 混用）是这类闸位最典型的静默缺陷 —— 把常量收在一处，
 * 闸位、回显、前端、测试全部引用同一符号，杜绝两处各写一个数。
 *
 * 工程数值（分块大小 / 超时 / 并发 / 重试 / TTL）在本文件一律标 **建议值**，
 * 依据与备选见设计件；**不得**当作作者给定数引用。
 */
object AttachContract:

  // ===== 作者给定数（冻结，禁自选）=====

  /** 单文件上限 = 1024 MB = 1 GiB = 1,073,741,824 B（作者 2026-09-14 09:14 数）。 */
  val MaxFileBytes: Long = 1_073_741_824L

  /** 单条消息附件上限 = 9 件。 */
  val MaxAttachmentsPerMessage: Int = 9

  /** 便于回显的量纲标签（十进制字节数 + 二进制量纲，禁裸写 "MB"）。 */
  val MaxFileBytesLabel: String = "1024 MB = 1 GiB (1,073,741,824 bytes)"

  // ===== 工程数值（建议值 + 备选，非冻结口径）=====

  /**
   * 分块大小（建议值 4 MiB = 4,194,304 B）。
   *
   * 依据：① relay 单请求体 = ceil(4 MiB / 3) × 4 ≈ 5.33 MiB，对服务端 32 MiB
   * body 限余量 6×；② 1 GiB → 256 块（1,073,741,824 / 4,194,304 = 256 整），
   * 块数与请求数平衡；
   * 备选：1 MiB（1024 块）/ 8 MiB（128 块）。
   */
  val ChunkSize: Int = 4 * 1024 * 1024

  /**
   * targetDir 上限 = **1024 字节**（UTF-8 编码后的字节数，**非字符数** —— 一个汉字
   * 占 3 B，量纲写错会静默放宽/收紧可达面）。
   *
   * 依据：本机 `getconf PATH_MAX /` = 1024（macOS 的路径长度上限）——取**最小公共界**
   * 以免「接收端可表达、发送端已拒」的反向陷阱（Linux PATH_MAX = 4096 更宽，取 min
   * 不损失可达面）。归**工程数值**区（非作者给定数区）。
   */
  val MaxTargetDirBytes: Int = 1024

  /** 协议版本：0 = 整件 legacy（proto 字段缺失的旧端），1 = 分块。 */
  val ProtoLegacy: Int = 0
  val ProtoChunked: Int = 1

  /**
   * 等级 2 = 分块 + **接收端指定目录**（`targetDir`，接收端裁定）。
   *
   * 阶梯语义：2 ⊃ 1 ⊃ 0（每级含其下各级全部能力）——`negotiate = min` 只在单调阶梯上
   * 等价于能力交集，故一个号只承载一次升版，`2` 不得在不同发布里代表不同能力集。
   *
   * 🔴 **头面红线（本批最容易被踩坏的一条）**：P2P 腿的 HTTP 头 `X-Dropbox-Proto`
   * 的值**恒为 1**，**不得**复用本数值轴。理由（现读锚）：接收端
   * `RestApiRoutes.parseDropboxChunkHeaders` 的判据是**等值**（`proto == ProtoChunked`），
   * 一旦新发送端把头升成 `2`，**旧接收端**该 guard 为假 ⇒ 返回 `None` ⇒ 走 legacy
   * 整件路径（把第一块当整件落盘）= 静默数据损坏，且旧端无从自纠。
   * ⇒ 本常量**只用于 JSON 面**（`file-offer` / `file-response`）与本地持久化；
   * 新增能力一律走新增独立头 / 新增 JSON 键，缺省即旧行为。
   */
  val ProtoAssignDir: Int = 2

  /** 协商：双方取 min；min == 0 ⇒ 走整件 legacy 路径 + 大小闸。
    *
    * ⚠️ 本判据只在**单调阶梯**（每级含其下各级能力）上等价于能力交集；把两个能力塞进
    * 同一个号会让「号 → 能力集」从函数退化成关系 ⇒ 协商语义崩塌。调用点：接收端在
    * `file-offer` 阶段用它判「是否可兑现 `targetDir`」；发送端在 `file-response` 收到
    * 对端自报等级时用它判「是否可发 `targetDir`」。 */
  def negotiate(local: Int, peer: Int): Int = math.min(local, peer)

  // ===== 错误码（本批最小集）=====

  object Codes:
    val AttachTooLarge: String = "ATTACH_TOO_LARGE"
    val AttachTooMany: String = "ATTACH_TOO_MANY"
    val ChunkDigestMismatch: String = "CHUNK_DIGEST_MISMATCH"
    val WholeDigestMismatch: String = "WHOLE_DIGEST_MISMATCH"
    val OffsetOutOfRange: String = "OFFSET_OUT_OF_RANGE"
    val SessionNotFound: String = "SESSION_NOT_FOUND"
    val PeerUnreachable: String = "PEER_UNREACHABLE"
    val QueueFull: String = "QUEUE_FULL"
    val InsufficientDisk: String = "INSUFFICIENT_DISK"
    val UnsupportedProtocol: String = "UNSUPPORTED_PROTOCOL"
    val InvalidArgument: String = "INVALID_ARGUMENT"

    // ===== targetDir 裁定码（契约升版批，2026-09-14）=====
    //
    // 这四码是 `INVALID_ARGUMENT` 在 targetDir 面上的**细化**（后者仍是未分类入参错的
    // 兜底）；**不得**把下列情形写成 `INVALID_ARGUMENT` 后静默降级 —— 与上方 `AttachError`
    // 文档的四条禁吞口径同源。被拒目标走既有 `AttachError.path`，允许根清单走 `expected`
    // （不新增字段）。

    /** 形态拒（词法，未触盘）：空串 / 非绝对 POSIX 路径 / 含 `..` 或 `.` 段 / 含 `\u0000` /
      * 超 1024 字节 / 以 `~` 开头 / Windows 盘符或 UNC 形态。`path` = 原始串（截断回显）。 */
    val TargetDirInvalid: String = "TARGET_DIR_INVALID"

    /** canonicalize 后不落在接收端允许根内（含符号链接逃逸后的结果）。
      * `path` = canonical 后目标；`expected` = 允许根清单。 */
    val TargetDirNotAllowed: String = "TARGET_DIR_NOT_ALLOWED"

    /** 目标目录不存在**且**其最深存在祖先不是一个目录。`path` = canonical 目标。 */
    val TargetDirNotFound: String = "TARGET_DIR_NOT_FOUND"

    /** 目标存在但是文件 / 无写权限 / 只读卷。`path` = canonical 目标。 */
    val TargetDirNotWritable: String = "TARGET_DIR_NOT_WRITABLE"

  /**
   * 失败必须自描述（不变量 I3）：错误体一律带机器可解析字段 —— 调用方/模型据此
   * 判定，前端据此回显**实际值**。禁止 `IO.unit` / `case Left(_) => ()` /
   * `case _ => ()` / 只 `logger.warn` 四种吞法。
   */
  final case class AttachError(
    code: String,
    message: String,
    phase: String = "offer",
    actual: Option[Long] = None,
    limit: Option[Long] = None,
    chunkIndex: Option[Int] = None,
    offset: Option[Long] = None,
    expectedIndex: Option[Int] = None,
    actualIndex: Option[Int] = None,
    expected: Option[String] = None,
    actualHash: Option[String] = None,
    bytesReceived: Option[Long] = None,
    path: Option[String] = None,
    p2pReason: Option[String] = None,
    relayReason: Option[String] = None
  ):
    /** 折叠进 `Either[String, _]` 的既有调用面时用（人类可读 + 实际值）。 */
    def render: String = s"[$code] $message"

    def toJson: Json =
      Json
        .obj(
          "code" -> code.asJson,
          "message" -> message.asJson,
          "phase" -> phase.asJson
        )
        .deepMerge(
          Json.fromFields(
            List(
              actual.map(v => "actual" -> v.asJson),
              limit.map(v => "limit" -> v.asJson),
              chunkIndex.map(v => "chunkIndex" -> v.asJson),
              offset.map(v => "offset" -> v.asJson),
              expectedIndex.map(v => "expectedIndex" -> v.asJson),
              actualIndex.map(v => "actualIndex" -> v.asJson),
              expected.map(v => "expected" -> v.asJson),
              actualHash.map(v => "actualHash" -> v.asJson),
              bytesReceived.map(v => "bytesReceived" -> v.asJson),
              path.map(v => "path" -> v.asJson),
              p2pReason.map(v => "p2pReason" -> v.asJson),
              relayReason.map(v => "relayReason" -> v.asJson)
            ).flatten
          )
        )

  // ===== 闸位（超限 fail-fast + 回显实际值）=====

  /** 单文件大小闸。超限 ⇒ `ATTACH_TOO_LARGE` + `actual`（实际字节）+ `limit`。 */
  def checkFileSize(bytes: Long): Either[AttachError, Unit] =
    if bytes < 0 then
      Left(
        AttachError(
          Codes.InvalidArgument,
          s"Attachment size must be non-negative, got $bytes bytes",
          actual = Some(bytes)
        )
      )
    else if bytes > MaxFileBytes then
      Left(
        AttachError(
          Codes.AttachTooLarge,
          s"Attachment too large: $bytes bytes exceeds the $MaxFileBytesLabel limit",
          actual = Some(bytes),
          limit = Some(MaxFileBytes)
        )
      )
    else Right(())

  /** 单条消息件数闸。超限 ⇒ `ATTACH_TOO_MANY` + `actual`（实际件数）+ `limit`。 */
  def checkAttachmentCount(count: Int): Either[AttachError, Unit] =
    if count < 0 then
      Left(AttachError(Codes.InvalidArgument, s"Attachment count must be non-negative, got $count", actual = Some(count.toLong)))
    else if count > MaxAttachmentsPerMessage then
      Left(
        AttachError(
          Codes.AttachTooMany,
          s"Too many attachments: $count exceeds the limit of $MaxAttachmentsPerMessage per message",
          actual = Some(count.toLong),
          limit = Some(MaxAttachmentsPerMessage.toLong)
        )
      )
    else Right(())

  /**
   * 组合闸：件数 + 逐件大小的**一次性**判定（任一超限即拒，先报件数）。
   * 超限时错误体带 `actual`（实际值）+ `limit` —— 调用方/前端据此回显。
   */
  def checkMessage(sizes: List[Long]): Either[AttachError, Unit] =
    checkAttachmentCount(sizes.size).flatMap { _ =>
      sizes.foldLeft[Either[AttachError, Unit]](Right(())) { (acc, size) =>
        acc.flatMap(_ => checkFileSize(size))
      }
    }

  // ===== 分块计划（末块不补零长；totalBytes 恰整除时不产生空末块）=====

  final case class ChunkPlan(index: Int, offset: Long, bytes: Int)

  /**
   * 把 totalBytes 切成块计划。`totalBytes <= 0` ⇒ 空计划（零字节文件不产生块，
   * 也不补零长末块 —— 契约 §3.1）。
   */
  def plan(totalBytes: Long, chunkSize: Int = ChunkSize): List[ChunkPlan] =
    require(chunkSize > 0, "chunkSize must be positive")
    if totalBytes <= 0 then Nil
    else
      val count = ((totalBytes + chunkSize - 1) / chunkSize).toInt
      (0 until count).toList.map { i =>
        val offset = i.toLong * chunkSize
        ChunkPlan(i, offset, math.min(chunkSize.toLong, totalBytes - offset).toInt)
      }

  /** 由 offset 推 index（契约：offset 恒由 chunkIndex × chunkSize 推导，禁自由填）。 */
  def indexForOffset(offset: Long, chunkSize: Int = ChunkSize): Int =
    if chunkSize <= 0 then 0 else (offset / chunkSize).toInt

  /** 由 index 推 offset。 */
  def offsetForIndex(index: Int, chunkSize: Int = ChunkSize): Long =
    index.toLong * chunkSize

end AttachContract
