package nebflow.dropbox

import cats.effect.IO

import java.security.MessageDigest

/**
 * 分块/流式传输引擎（应用层协议，传输无关）。
 *
 * 契约要点（设计件 §3.1–§3.4，逐条可判红）：
 *   - 每块：`{transferId, chunkIndex, offset, totalBytes, chunkSize, bytes, chunkSha256, wholeSha256}`；
 *     `offset` **恒由** `chunkIndex × chunkSize` 推导，禁发送端自由填；
 *   - 末块 `bytes = totalBytes − offset`；恰整除时**不补零长末块**；
 *   - 每块校验：摘要覆盖**原始字节**（未 base64、不含信封）；接收端**必须自己算**（禁自证），
 *     且回执必须**回传接收端自算**的块摘要（R4）—— 回显请求头会让发送端的比对恒真；
 *   - 整件 sha256：发送端分块读盘**同一遍**增量更新；接收端写盘**同一遍**增量更新；
 *     唯一比对点 = 末块应用后、commit 之前；
 *   - 幂等键 = `(transferId, chunkIndex)`；重复块 = no-op；空洞块 = 拒绝（gap 检测）；
 *   - 恢复权威 = **temp 文件实际长度 + 重算前缀 sha256**（不信任何持久化摘要字段）。
 */
object ChunkedTransfer:

  /** 一个分块帧。`bytes` = 本块原始字节数（非 base64 长度）。 */
  final case class ChunkFrame(
    transferId: String,
    chunkIndex: Int,
    offset: Long,
    totalBytes: Long,
    chunkSize: Int,
    bytes: Int,
    chunkSha256: String,
    wholeSha256: String,
    proto: Int = AttachContract.ProtoChunked
  )

  /**
   * 接收端对一块的回执。
   *   - `chunkSha256` = **接收端自算**的块摘要（R4：不得回显请求头，否则发送端的
   *     `ack.chunkSha256 == frame.chunkSha256` 比对恒真）；
   *   - 末块必带接收端**自算**的整件摘要（I2 禁自证）。
   */
  final case class ChunkAck(
    transferId: String,
    chunkIndex: Int,
    bytesReceived: Long,
    chunkSha256: String,
    wholeSha256: Option[String] = None
  )

  /** 接收侧权威状态。 */
  final case class ReceiveState(
    transferId: String,
    bytesReceived: Long,
    totalBytes: Long,
    prefixSha256: String
  )

  enum ChunkDecision:
    case AlreadyApplied
    case Proceed

  // ===== 摘要工具 =====

  def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"$b%02x").mkString

  def sha256Hex(text: String): String = sha256Hex(text.getBytes("UTF-8"))

  def newDigest: MessageDigest = MessageDigest.getInstance("SHA-256")

  def digestHex(d: MessageDigest): String = d.digest().map(b => f"$b%02x").mkString

  /** 流式算一个文件（或文件前 n 字节）的 sha256 —— 有界内存，禁整件读入。 */
  def hashFileStreaming(path: os.Path, limit: Long = -1L): String =
    val digest = newDigest
    if os.exists(path) then
      val in = os.read.inputStream(path)
      try
        val buf = new Array[Byte](64 * 1024)
        var remaining = if limit < 0 then Long.MaxValue else limit
        var n = if remaining > 0 then in.read(buf) else -1
        while n > 0 do
          val take = math.min(n.toLong, remaining).toInt
          digest.update(buf, 0, take)
          remaining -= take
          n = if remaining > 0 then in.read(buf) else -1
      finally in.close()
    digestHex(digest)
  end hashFileStreaming

  // ===== 校验（纯函数，可单测）=====

  /**
   * 块摘要校验：摘要不符 ⇒ `CHUNK_DIGEST_MISMATCH` + 期望/实际 + index。
   * **成功时返回接收端自算的摘要** —— 回执必须回传这个值（R4：回执回显请求头
   * 会让发送端的 `ack.chunkSha256 == frame.chunkSha256` 恒真，校验退化成自证）。
   */
  def verifyChunkDigest(frame: ChunkFrame, payload: Array[Byte]): Either[AttachContract.AttachError, String] =
    val computed = sha256Hex(payload)
    if computed == frame.chunkSha256 then Right(computed)
    else
      Left(
        AttachContract.AttachError(
          AttachContract.Codes.ChunkDigestMismatch,
          s"Chunk ${frame.chunkIndex} digest mismatch: declared ${frame.chunkSha256}, computed $computed",
          phase = "transfer",
          chunkIndex = Some(frame.chunkIndex),
          offset = Some(frame.offset),
          expected = Some(frame.chunkSha256),
          actualHash = Some(computed)
        )
      )

  end verifyChunkDigest

  /** 帧自洽性：offset 必须由 index 推导；bytes 必须与块计划一致。 */
  def verifyFrameShape(frame: ChunkFrame): Either[AttachContract.AttachError, Unit] =
    val derived = AttachContract.offsetForIndex(frame.chunkIndex, frame.chunkSize)
    if derived != frame.offset then
      Left(
        AttachContract.AttachError(
          AttachContract.Codes.OffsetOutOfRange,
          s"Chunk ${frame.chunkIndex} declares offset ${frame.offset} but chunkIndex × chunkSize = $derived",
          phase = "transfer",
          chunkIndex = Some(frame.chunkIndex),
          offset = Some(frame.offset),
          expected = Some(derived.toString)
        )
      )
    else
      val expectedBytes = math.min(frame.chunkSize.toLong, frame.totalBytes - derived)
      if expectedBytes < 0 then
        Left(
          AttachContract.AttachError(
            AttachContract.Codes.OffsetOutOfRange,
            s"Chunk ${frame.chunkIndex} starts at $derived which is past totalBytes ${frame.totalBytes}",
            phase = "transfer",
            chunkIndex = Some(frame.chunkIndex),
            offset = Some(derived),
            expectedIndex = Some(AttachContract.indexForOffset(frame.totalBytes, frame.chunkSize))
          )
        )
      else if expectedBytes != frame.bytes.toLong then
        Left(
          AttachContract.AttachError(
            AttachContract.Codes.OffsetOutOfRange,
            s"Chunk ${frame.chunkIndex} declares ${frame.bytes} bytes but the plan says $expectedBytes",
            phase = "transfer",
            chunkIndex = Some(frame.chunkIndex),
            offset = Some(derived),
            expected = Some(expectedBytes.toString),
            actual = Some(frame.bytes.toLong)
          )
        )
      else Right(())

      end if

    end if

  end verifyFrameShape

  /**
   * 幂等 / gap 判定（契约 §3.4）：
   *   - index == 期望 ⇒ Proceed；
   *   - index <  期望 ⇒ AlreadyApplied（幂等 no-op，不重放不报错）；
   *   - index >  期望 ⇒ `OFFSET_OUT_OF_RANGE` + `expectedIndex`（**禁静默缓冲乱序块**）。
   */
  def decideChunk(
    frame: ChunkFrame,
    bytesReceived: Long
  ): Either[AttachContract.AttachError, ChunkDecision] =
    val expectedIndex = AttachContract.indexForOffset(bytesReceived, frame.chunkSize)
    if frame.chunkIndex == expectedIndex then Right(ChunkDecision.Proceed)
    else if frame.chunkIndex < expectedIndex then Right(ChunkDecision.AlreadyApplied)
    else
      Left(
        AttachContract.AttachError(
          AttachContract.Codes.OffsetOutOfRange,
          s"Chunk ${frame.chunkIndex} arrives out of order: expected index $expectedIndex (bytesReceived=$bytesReceived)",
          phase = "transfer",
          chunkIndex = Some(frame.chunkIndex),
          expectedIndex = Some(expectedIndex),
          actualIndex = Some(frame.chunkIndex),
          bytesReceived = Some(bytesReceived)
        )
      )

  end decideChunk

  /** 整件摘要比对（唯一比对点 = 末块应用后、commit 前）。 */
  def verifyWholeDigest(
    declared: String,
    computed: String,
    bytesReceived: Long
  ): Either[AttachContract.AttachError, Unit] =
    if declared == computed then Right(())
    else
      Left(
        AttachContract.AttachError(
          AttachContract.Codes.WholeDigestMismatch,
          s"Whole-file digest mismatch: declared $declared, receiver computed $computed",
          phase = "commit",
          expected = Some(declared),
          actualHash = Some(computed),
          bytesReceived = Some(bytesReceived)
        )
      )

end ChunkedTransfer

/**
 * 发送侧游标：**单遍 IO** —— 逐块读盘，边算块摘要边增量更新整件摘要。
 * 有界缓冲（≤1 块），内存不随文件大小线性增长（不变量 I4）。
 *
 * `startOffset > 0`（续传）时先重读 `[0, startOffset)` 重建整件摘要前缀
 * —— `MessageDigest` 状态不可序列化，续传只能重算前缀（有意的、有界的代价）。
 */
final class ChunkSender(
  val source: os.Path,
  val transferId: String,
  val totalBytes: Long,
  val chunkSize: Int,
  private val wholeDigest: MessageDigest,
  var bytesSent: Long
):

  /**
   * 全文件 sha256（单遍增量算出）。末块之前是「已读前缀」的摘要，末块之后即整件。
   *
   * ⚠️ **必须判空**：`MessageDigest.digest()` 会**重置**摘要状态 —— 直接调它会让
   * 下一次 `update` 从头累积，整件摘要静默变成「只剩最后一块」。这里在**快照克隆**
   * 上取摘要，`wholeSha256` 因此可重复读取且无副作用。
   */
  def wholeSha256: String =
    ChunkedTransfer.digestHex(wholeDigest.clone().asInstanceOf[MessageDigest])

  /** 下一个待发块的 index（由 bytesSent 推导）。 */
  def nextIndex: Int = AttachContract.indexForOffset(bytesSent, chunkSize)

  /** 剩余块。 */
  def remaining: List[AttachContract.ChunkPlan] =
    AttachContract.plan(totalBytes, chunkSize).filter(_.offset >= bytesSent)

  /** 本块是否为末块。 */
  def isLastChunk(index: Int): Boolean =
    AttachContract.offsetForIndex(index, chunkSize) + math.min(
      chunkSize.toLong,
      totalBytes - AttachContract.offsetForIndex(index, chunkSize)
    ) >= totalBytes

  /**
   * 读下一块：**单遍 IO** —— 一次 read 拿原始字节，算块摘要 + 增量更新整件摘要。
   * `None` = 已发完。
   */
  def readNext(): IO[Option[(ChunkedTransfer.ChunkFrame, Array[Byte])]] =
    IO.blocking {
      if bytesSent >= totalBytes then None
      else
        val index = nextIndex
        val offset = AttachContract.offsetForIndex(index, chunkSize)
        val want = math.min(chunkSize.toLong, totalBytes - offset).toInt
        val buf = new Array[Byte](want)
        val in = os.read.inputStream(source)
        try
          var skipped = 0L
          while skipped < offset do
            val s = in.skip(offset - skipped)
            if s <= 0 then skipped = offset else skipped += s
          var read = 0
          while read < want do
            val n = in.read(buf, read, want - read)
            if n <= 0 then read = want else read += n
        finally in.close()
        wholeDigest.update(buf)
        val frame = ChunkedTransfer.ChunkFrame(
          transferId = transferId,
          chunkIndex = index,
          offset = offset,
          totalBytes = totalBytes,
          chunkSize = chunkSize,
          bytes = want,
          chunkSha256 = ChunkedTransfer.sha256Hex(buf),
          wholeSha256 = wholeSha256
        )
        bytesSent = offset + want
        Some((frame, buf))
    }

end ChunkSender

object ChunkSender:

  /**
   * 打开发送游标。`startOffset > 0` 时**先对齐到块边界**（xferb 批 · P0-2），再重读
   * `[0, aligned)` 重建整件摘要前缀。
   *
   * 🔴 为什么必须对齐（改前缺陷）：探测到的权威 offset 是 temp 的**实际长度**，它可以不是
   * `chunkSize` 的整数倍（上一次 append 被中断）。`readNext` 只会从 `index × chunkSize`
   * 起读 ⇒ 实际重发的是**含该 offset 的那一整块**。若仍按未对齐的 offset 给摘要打前缀
   * （改前行为），整件 sha256 的前缀就比实际发送的字节多出那截尾巴 ⇒ **末块整件摘要必然
   * 不符**（续传永远失败，且原因只显示为「内容不一致」）。对齐后：前缀 = `[0, aligned)`
   * = 真正会跳过的字节，重发块与接收端截断后的落盘位置严格同源。
   *
   * 🔴 对齐的**例外**（xferb 批 · 已落满）：`startOffset ≥ totalBytes` 时**不对齐**。
   * 此时对端 temp 已含整件，对齐回上一块边界会把**末块**当成「未发」重发一遍（数据上安全，
   * 但违背 P0-2 的「已传字节不重来」）；不对齐则 `readNext` 直接给 None，由调用方走
   * **零块收口**（用接收端重算的摘要比对，见 `ChunkedSendLoop.run` 的空读分支）。
   */
  def open(
    source: os.Path,
    transferId: String,
    startOffset: Long = 0L,
    chunkSize: Int = AttachContract.ChunkSize
  ): IO[ChunkSender] =
    IO.blocking {
      val totalBytes = os.size(source)
      val digest = ChunkedTransfer.newDigest
      val aligned =
        if chunkSize <= 0 then math.max(0L, startOffset)
        else if startOffset >= totalBytes then math.max(0L, startOffset)
        else math.max(0L, (startOffset / chunkSize.toLong) * chunkSize.toLong)
      var primed = 0L
      if aligned > 0 then
        val in = os.read.inputStream(source)
        try
          val buf = new Array[Byte](64 * 1024)
          var remaining = aligned
          while remaining > 0 do
            val want = math.min(buf.length.toLong, remaining).toInt
            val n = in.read(buf, 0, want)
            if n <= 0 then remaining = 0
            else
              digest.update(buf, 0, n)
              primed += n
              remaining -= n
        finally in.close()
      new ChunkSender(source, transferId, totalBytes, chunkSize, digest, math.min(primed, totalBytes))
    }

end ChunkSender

/**
 * 接收侧会话。恢复权威 = **temp 文件实际长度 + 重算前缀 sha256**
 * （不信任何持久化摘要字段 —— 防篡改、防未 flush 的脏写；设计件 R2）。
 */
final class ChunkReceiver(
  val transferId: String,
  val totalBytes: Long,
  val chunkSize: Int,
  val tempPath: os.Path
):
  private var bytes: Long = 0L
  private var committedWhole: String = ""

  /** 已收到的字节数（权威 offset）。 */
  def bytesReceived: Long = bytes

  /** 末块通过整件摘要比对后，接收端自算的整件 sha256。 */
  def computedWholeSha256: String = committedWhole

  /** 从头开始（新建会话）：清 temp，offset = 0。 */
  def reset(): IO[Unit] =
    IO.blocking {
      if os.exists(tempPath) then os.remove(tempPath)
      bytes = 0L
      committedWhole = ""
    }

  /** 恢复权威：temp 实际长度 + 重算前缀摘要，返回可回传发送端的 probe 结果。 */
  def prime(): IO[ChunkedTransfer.ReceiveState] =
    IO.blocking {
      val onDisk = if os.exists(tempPath) then os.size(tempPath) else 0L
      val clamped = math.min(onDisk, totalBytes)
      bytes = clamped
      committedWhole = ""
      ChunkedTransfer.ReceiveState(
        transferId,
        clamped,
        totalBytes,
        ChunkedTransfer.hashFileStreaming(tempPath, clamped)
      )
    }

  /**
   * 应用一块（**先校验、再落盘、后推进 offset**）：
   *   1. 帧自洽（offset 由 index 推导 / bytes 符合计划）；
   *   2. 幂等 / gap 判定；
   *   3. **先算块摘要再写**（不符 ⇒ 不写、不推进、`CHUNK_DIGEST_MISMATCH`）；
   *   4. 追加写 temp；
   *   5. 末块 ⇒ 自算整件摘要与声明比对（不符 ⇒ 删 temp + `WHOLE_DIGEST_MISMATCH`）。
   */
  def applyChunk(
    frame: ChunkedTransfer.ChunkFrame,
    payload: Array[Byte]
  ): IO[Either[AttachContract.AttachError, ChunkedTransfer.ChunkAck]] =
    ChunkedTransfer.verifyFrameShape(frame) match
      case Left(err) => IO.pure(Left(err))
      case Right(_) =>
        ChunkedTransfer.decideChunk(frame, bytes) match
          case Left(err) => IO.pure(Left(err))
          case Right(ChunkedTransfer.ChunkDecision.AlreadyApplied) =>
            // 幂等 no-op：返回当前 offset，不重放、不报错、不重复写。
            // R4：回执摘要同样是**接收端自算**（对重放的块也把收到的字节算一遍），
            // 不回显请求头 —— 否则发送端的比对恒真。
            IO.pure(
              Right(
                ChunkedTransfer.ChunkAck(
                  transferId,
                  frame.chunkIndex,
                  bytes,
                  ChunkedTransfer.sha256Hex(payload)
                )
              )
            )
          case Right(ChunkedTransfer.ChunkDecision.Proceed) =>
            ChunkedTransfer.verifyChunkDigest(frame, payload) match
              case Left(err) => IO.pure(Left(err))
              case Right(computedChunk) =>
                // ⚠️ 分支判定必须在 IO **运行期**做（`flatMap` 里），不能在构造期。
                // `ioA *> { if bytes < totalBytes then … }` 的 `{…}` 是**急求值**的：
                // 它在 `applyChunk` 被调用的那一刻就求值，而此刻本块还没落盘
                // ⇒ 整件摘要比对点永远是死代码、末块回执永远不带接收端自算摘要
                // ⇒ 校验退化成「发送端自证」（不变量 I2 的反面）。自环测试实测命中。
                IO.blocking {
                  // ===== 续传对齐（xferb 批 · P0-2）=====
                  // 本块的 offset 之前若已存在**非整块尾**（上一次 append 被中断，或上一次
                  // 会话用了另一个块大小），必须先截到 `frame.offset` 再 append —— 否则同一段
                  // 字节落两遍：落盘长度超出计划、末块整件摘要必然不符（改前 = 静默污染，
                  // 只表现为 `WHOLE_DIGEST_MISMATCH` 的假「内容不一致」）。
                  // 只对**本会话自己的 temp** 动手（路径来自会话记录/确定性派生名），
                  // 不触任何别人的件（收端全保护口径不变）。
                  val frameStart = frame.offset
                  if os.exists(tempPath) && os.size(tempPath) > frameStart then
                    val raf = new java.io.RandomAccessFile(tempPath.toNIO.toFile, "rw")
                    try raf.setLength(frameStart)
                    finally raf.close()
                  os.write.append(tempPath, payload)
                  bytes = frameStart + payload.length
                  bytes
                }.flatMap { nowBytes =>
                  if nowBytes < totalBytes then
                    // R4：回执带**接收端自算**的块摘要（发送端据此真比对，非恒真）。
                    IO.pure(Right(ChunkedTransfer.ChunkAck(transferId, frame.chunkIndex, nowBytes, computedChunk)))
                  else
                    // 唯一比对点：接收端自算整件摘要（流式，禁整件驻留）。
                    IO.blocking(ChunkedTransfer.hashFileStreaming(tempPath)).flatMap { computed =>
                      ChunkedTransfer.verifyWholeDigest(frame.wholeSha256, computed, nowBytes) match
                        case Left(err) =>
                          // 不符 ⇒ 立即删 temp（绝不 commit）。
                          IO.blocking(if os.exists(tempPath) then os.remove(tempPath) else ())
                            .handleErrorWith(_ => IO.unit)
                            .as(Left(err))
                        case Right(_) =>
                          IO {
                            committedWhole = computed
                            Right(
                              ChunkedTransfer.ChunkAck(
                                transferId,
                                frame.chunkIndex,
                                nowBytes,
                                computedChunk,
                                wholeSha256 = Some(computed)
                              )
                            )
                          }
                    }
                }

end ChunkReceiver
