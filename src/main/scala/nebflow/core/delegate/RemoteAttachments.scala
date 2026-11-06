package nebflow.core.delegate

import cats.effect.IO
import io.circe.Json
import nebflow.core.tools.{RemoteExecutor, ToolError}
import nebflow.shared.{AttachContract, NebflowLogger, PeerInfo}

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.Base64

/**
 * RemoteAttachments —— **跨设备附件送达腿**（跨设备附件批 2026-11-05，作者令逐字：
 * 「这个也要支持」）。上一批（[[DelegateAttachments]]）把「带附件的设备腿」显式拒成
 * `DELEGATE_ATTACHMENTS_REMOTE`；本件把那句拒绝换成**可验证的真送达**。
 *
 * ## 通道（复用已验证者的**同一份**，禁自造第三套传输）
 *
 * 逐块 put 走的是设备面既有的 `FileTransfer` 通道（`direction=put` 的**分块形态**）：
 *   - 发送腿 = [[nebflow.core.tools.RemoteExecutor]] 的 P2P 优先 → relay 回落；
 *   - 接收腿 = 对端 `FileTransferAction.chunkedPut`：**接收端自算**块摘要与整件摘要
 *     （不变量 I2：禁「内容相同所以摘要相等」的自证）、按 offset 追加、末块比对整件。
 *   - 块大小 / 死线 / 件数体积上限**全部引用** [[AttachContract]]（单点，禁第二份数字）。
 *
 * 目录建位走同一条 remote-exec 通道上的 `Write`（空内容 ⇒ 幂等建父目录 + 目标空件）：
 * 接收端 `os.write.append` **不建父目录**，不建位则首块必失败。零新 action。
 *
 * ## 落点（稳定且对端可读）
 *
 * `<设备上的那份 workspace>/.nebflow/attachments/<dispatchId>/NN-<原名>` —— 与本地腿的
 * 座位副本 **同构同段**（[[DelegateAttachments.SeatDirSegments]]），只是根换成了**设备盘**
 * 的 workspace（host 侧本来就持有该路径：项目 origin 的 `path`）。清单里给的是**对端绝对
 * 路径**（delegate 在那台机器上 `Read` 的就是它），本机路径只作溯源。
 *
 * ## 可验证性（hard：发了不等于到了）
 *
 * 逐件三段证据，任一不成立 ⇒ **整批失败并点名**（nothing is spawned，与本地腿同语义）：
 *   ① 末块回执必须带回**接收端自算**的 `wholeSha256`（缺失 = 对端引擎太旧，见
 *      [[CodeTooOld]]）；
 *   ② 该值必须等于本机原件 sha256（逐块另有块摘要比对）；
 *   ③ 收口探针（`direction=probe`）必须读到 `bytesReceived == 字节数` **且**
 *      `prefixSha256 == 原件 sha256` —— 摘要由对端从**它盘上的字节**重算。
 *
 * ## 断点续传的诚实边界
 *
 * 本腿**不做跨次续传**：每次派发的落点目录按 dispatchId 唯一，建位阶段把目标截成 0 字节，
 * 探针随后断言「盘上 0 字节」，因此每次都是从 0 起的有序块流；单块在传输层失败重试一次
 * （同 index 幂等，接收端 `index < expected` 是 no-op）。已落盘的中间文件留在对端
 * （接收端没有删除动作，本腿**不会**为了整洁去删对端字节），失败文案如实申报「N 件已落盘」。
 */
object RemoteAttachments:

  private val logger = NebflowLogger(getClass)

  // ============================================================
  // reason codes（每个失败面一个，全部「整批失败」）
  // ============================================================

  /** 设备项目没有设备盘 workspace（origin path 缺席）⇒ 无处安放，显式失败。 */
  val CodeNoDeviceDir = "DELEGATE_ATTACHMENT_REMOTE_NO_DEVICE_DIR"

  /** 对端不可达（名册无此设备 / P2P 与 relay 双双失败）。 */
  val CodeOffline = "DELEGATE_ATTACHMENT_REMOTE_OFFLINE"

  /** 对端显式拒绝（接收端回了结构化 AttachError 码：落点不许 / 撞名不许追加 / 参数非法…）。 */
  val CodeRefused = "DELEGATE_ATTACHMENT_REMOTE_REFUSED"

  /** 传输中断（块没到 / 连接断 / 盘上长度不符）。 */
  val CodeInterrupted = "DELEGATE_ATTACHMENT_REMOTE_INTERRUPTED"

  /** 摘要不符（块摘要或整件摘要，两侧读数都摆出来）。 */
  val CodeDigest = "DELEGATE_ATTACHMENT_REMOTE_DIGEST_MISMATCH"

  /** 对端磁盘不足（接收端 ENOSPC / INSUFFICIENT_DISK）。 */
  val CodeDiskFull = "DELEGATE_ATTACHMENT_REMOTE_DISK_FULL"

  /** 对端引擎版本过旧（不认分块 put / 不回带接收端自算摘要）——沿用既有设备面词表。 */
  val CodeTooOld = "DEVICE_ENGINE_TOO_OLD"

  /** 落点上已有本次不认领的非空字节（唯一落点目录下不该出现；出现即显式失败、绝不覆盖）。 */
  val CodePartial = "DELEGATE_ATTACHMENT_REMOTE_PARTIAL_CONFLICT"

  /** 对端附件目录的相对段——与本地腿座位副本同构（单点，禁第二份段表）。 */
  val DirSegments: List[String] = DelegateAttachments.SeatDirSegments

  // ============================================================
  // 目标设备
  // ============================================================

  /**
   * 送达目标：对端设备 + **设备盘** workspace（host 侧已知的项目 origin 路径）。
   *
   * `deviceWorkspace` 必须是**对端绝对路径**（`C:\…` / `/…`）。它是清单里对端路径的根，
   * 也是 delegate 在那台机器上干活的地方。
   */
  final case class Target(peer: PeerInfo, deviceWorkspace: String):
    def deviceName: String = peer.deviceName
    def deviceId: String = peer.deviceId

    /** 本次派发的落点目录（对端绝对路径）。 */
    def dirFor(dispatchId: String): String =
      joinPeerPath(deviceWorkspace.trim, DirSegments :+ safeSegment(dispatchId))

    /** 单件的对端绝对路径（文件名沿用本机稳定副本名 `NN-<原名>`，扩展名不变 ⇒ 视觉通道可判）。 */
    def pathFor(dir: String, fileName: String): String = joinPeerPath(dir, List(fileName))
  end Target

  // ============================================================
  // 传输缝（唯一入口；生产 = RemoteExecutor，测试 = 桩）
  // ============================================================

  /**
   * 两个动作的**唯一**传输缝：
   *   - [[probe]]：续传/收口探针（接收端回 `bytesReceived` + **自算**的 `prefixSha256`）；
   *   - [[putChunk]]：推一块（接收端自算块摘要，末块另回整件摘要；`totalBytes = 0` 的
   *     零块帧 = 建出空文件）。
   *
   * 目录建位**不在这里**：接收端 `FileTransferAction.chunkedPut` 自己建父目录（跨设备
   * 附件批对接收腿的对齐改动）——发送侧因此不依赖对端的 `Write` 工具腿，也不猜对端
   * home/临时目录（实测：对端 `Write` 在 Windows 用户目录下会失败，禁作传输前件）。
   *
   * 生产实现 = [[Transport.viaRemoteExecutor]]（P2P 优先 → relay 回落，与 capture 腿同族
   * 判定）。测试实现 = 自环桩（见 `RemoteAttachmentsSpec`：把块原样喂给**真实的**
   * `FileTransferAction` 接收端实现，摘要由接收端自己算——不是手写假语义）。
   */
  trait Transport:
    def probe(target: Target, path: String): IO[Either[String, Json]]
    def putChunk(
      target: Target,
      path: String,
      contentB64: String,
      chunkIndex: Int,
      totalBytes: Long,
      chunkSize: Int,
      chunkSha256: String,
      wholeSha256: String,
      overwrite: Boolean
    ): IO[Either[String, Json]]

  object Transport:

    /** 生产腿：经 [[RemoteExecutor]] 的设备面（P2P 优先 → relay 回落）。 */
    val viaRemoteExecutor: Transport = new Transport:
      private def withExecutor[A](f: RemoteExecutor => IO[Either[String, A]]): IO[Either[String, A]] =
        RemoteExecutor.current match
          case Some(exec) => f(exec)
          case None =>
            IO.pure(
              Left(
                "the remote executor is not initialized on this host (NebLink off) — no device channel to deliver through"
              )
            )

      def probe(target: Target, path: String): IO[Either[String, Json]] =
        withExecutor(_.transferProbe(target.peer, path))

      def putChunk(
        target: Target,
        path: String,
        contentB64: String,
        chunkIndex: Int,
        totalBytes: Long,
        chunkSize: Int,
        chunkSha256: String,
        wholeSha256: String,
        overwrite: Boolean
      ): IO[Either[String, Json]] =
        withExecutor(
          _.transferPutChunk(
            target.peer,
            path,
            contentB64,
            chunkIndex,
            totalBytes,
            chunkSize,
            chunkSha256,
            wholeSha256,
            overwrite
          )
        )

  // ============================================================
  // 送达（唯一入口）
  // ============================================================

  /**
   * 把**已落地并校验过**的稳定副本逐件送到目标设备，返回带对端路径的计划。
   *
   * 空计划 ⇒ 原样返回（零改动）。任一失败 ⇒ `Left`，且文案点名「哪一件、哪个对端路径、
   * 哪类失败、已落盘几件」——调用方据此**拒绝派发**（nothing is spawned）。
   */
  def deliver(
    plan: DelegateAttachments.Plan,
    target: Target,
    transport: Transport = Transport.viaRemoteExecutor,
    /**
     * 块长（**测试缝**）：生产恒为 [[AttachContract.ChunkSize]]（4 MiB，单点）；spec 用
     * 小值跑多块路径。上限仍由 [[AttachContract.MaxChunkSize]] 夹取（禁第二份数字）。
     */
    chunkSize: Int = AttachContract.ChunkSize
  ): IO[Either[ToolError, DelegateAttachments.Plan]] =
    if plan.isEmpty then IO.pure(Right(plan))
    else
      val effectiveChunk = math.max(1, math.min(AttachContract.MaxChunkSize, chunkSize))
      val dispatchId = DelegateAttachments.fileNameOf(plan.copyDir)
      val dir = target.dirFor(dispatchId)
      def loop(
        rest: List[DelegateAttachments.Entry],
        done: List[DelegateAttachments.Entry]
      ): IO[Either[ToolError, DelegateAttachments.Plan]] =
        rest match
          case Nil =>
            IO.pure(Right(plan.copy(entries = done, device = Some(target.deviceName))))
          case entry :: tail =>
            deliverOne(entry, target, transport, dir, effectiveChunk).flatMap {
              case Left(f) => IO.pure(Left(f.render(plan.size, done.size)))
              case Right(peerPath) =>
                loop(tail, done :+ entry.copy(remoteCopy = Some(peerPath)))
            }
      loop(plan.entries, Nil)

  /** 单件失败读数（渲染成 `ToolError` 前带着全部上下文）。 */
  private final case class Failure(
    code: String,
    entry: DelegateAttachments.Entry,
    peerPath: String,
    deviceName: String,
    detail: String
  ):
    def render(total: Int, done: Int): ToolError =
      val landed =
        if done == 0 then "No file had landed on the device."
        else s"$done of $total file(s) had ALREADY landed on the device before this one failed (they stay there)."
      ToolError(
        s"Delegate: attachment delivery to device '$deviceName' FAILED — '${entry.original}' → '$peerPath' " +
          s"(${entry.sizeBytes} B, sha256 ${entry.sha256}): $detail. $landed Nothing was dispatched — the delegate " +
          s"was NOT started (device-leg attachments are all-or-nothing, exactly like the local leg). ($code)"
      )

  // ============================================================
  // 单件：建位 → 探针 → 逐块 → 收口探针
  // ============================================================

  private def deliverOne(
    entry: DelegateAttachments.Entry,
    target: Target,
    transport: Transport,
    dir: String,
    chunkSize: Int
  ): IO[Either[Failure, String]] =
    val peerPath = target.pathFor(dir, DelegateAttachments.fileNameOf(entry.stableCopy))

    def failure(code: String, detail: String): Failure =
      Failure(code, entry, peerPath, target.deviceName, detail)

    for
      // ① 探针（同时是**引擎能力探针**：不认 probe 方向 = 对端太旧；也读回落点上
      //    已有的字节数——不认领的字节一律不覆盖）
      out <- transport.probe(target, peerPath).flatMap {
        case Left(err) =>
          val (code, detail) = classify(err)
          IO.pure(Left(failure(code, detail)))
        case Right(json) =>
          readProbe(json) match
            case None =>
              IO.pure(
                Left(
                  failure(
                    CodeTooOld,
                    "the device's receiver answered the resume probe without the receiver-computed fields " +
                      s"(bytesReceived / prefixSha256): ${json.noSpaces.take(200)} — that engine build predates chunked " +
                      "FileTransfer put; upgrade the engine on that device and re-dispatch"
                  )
                )
              )
            case Some((bytesOnPeer, prefixSha)) =>
              if bytesOnPeer > entry.sizeBytes then
                IO.pure(
                  Left(
                    failure(
                      CodePartial,
                      s"the device already holds $bytesOnPeer bytes at this path, more than the ${entry.sizeBytes} B " +
                        "being delivered — refusing to write over bytes this dispatch does not own"
                    )
                  )
                )
              else if bytesOnPeer > 0 then
                IO.pure(
                  Left(
                    failure(
                      CodePartial,
                      s"the device holds $bytesOnPeer bytes at this path that this dispatch did not put there " +
                        s"(prefix sha256 $prefixSha) — refusing to append to bytes this dispatch does not own"
                    )
                  )
                )
              else pushChunks(entry, target, transport, peerPath, chunkSize, failure)
          end match
      }
    yield out.map(_ => peerPath)

  /** 逐块推 + 末块整件摘要 + 收口探针（接收端从**它盘上的字节**重算）。 */
  private def pushChunks(
    entry: DelegateAttachments.Entry,
    target: Target,
    transport: Transport,
    peerPath: String,
    chunkSize: Int,
    mkFailure: (String, String) => Failure
  ): IO[Either[Failure, Unit]] =
    val planned = AttachContract.plan(entry.sizeBytes, chunkSize)
    // 零字节附件：`plan(0)` 是空列表，但空件也必须在设备上**存在**⇒ 发一个零块帧
    // （`totalBytes = 0`）：接收端的零块支幂等建出空件并回传空文件的整件摘要。
    val frames =
      if planned.isEmpty then List(AttachContract.ChunkPlan(0, 0L, 0)) else planned
    val total = frames.size

    def sendChunk(chunk: AttachContract.ChunkPlan, attempt: Int): IO[Either[Failure, Unit]] =
      IO.blocking {
        val bytes = readRange(entry.stableCopy, chunk.offset, chunk.bytes)
        bytes -> sha256Hex(bytes)
      }.attempt.flatMap {
        case Left(e) =>
          IO.pure(
            Left(
              mkFailure(
                CodeInterrupted,
                s"cannot read the stable copy on this machine (${e.getClass.getSimpleName}: ${e.getMessage})"
              )
            )
          )
        case Right((bytes, chunkSha)) =>
          val b64 = Base64.getEncoder.encodeToString(bytes)
          transport
            .putChunk(
              target,
              peerPath,
              b64,
              chunk.index,
              entry.sizeBytes,
              chunkSize,
              chunkSha,
              entry.sha256,
              overwrite = true
            )
            .flatMap {
              case Right(ack) =>
                verifyAck(entry, chunk, chunkSha, isLast = chunk.index == total - 1, ack, mkFailure)
              case Left(err) =>
                // 单次重试**只给传输层失败**（无结构化错误体：断流 / 超时 / 网线）。接收端的
                // 结构化 `AttachError` 是**确定性判定**，重试只会拿到次生错误并盖掉真原因
                // （例：整件摘要不符 ⇒ 接收端已删件 ⇒ 重试变成 OFFSET_OUT_OF_RANGE）。
                if attempt == 0 && receiverError(err).isEmpty then
                  logger.warn(
                    s"RemoteAttachments: chunk ${chunk.index} of ${entry.original} failed on the device " +
                      s"(${err.take(160)}) — retrying once"
                  ) *> sendChunk(chunk, 1)
                else
                  val (code, detail) = classify(err)
                  IO.pure(Left(mkFailure(code, s"chunk ${chunk.index} (offset ${chunk.offset}) failed: $detail")))
            }
      }

    def loop(rest: List[AttachContract.ChunkPlan]): IO[Either[Failure, Unit]] =
      rest match
        case Nil => IO.pure(Right(()))
        case chunk :: tail => sendChunk(chunk, 0).flatMap {
            case Left(f) => IO.pure(Left(f))
            case Right(_) => loop(tail)
          }

    loop(frames).flatMap {
      case Left(f) => IO.pure(Left(f))
      case Right(_) =>
        // 收口：对端盘上的字节数 + 对端自算的整件摘要
        transport.probe(target, peerPath).map {
          case Left(err) =>
            val (code, detail) = classify(err)
            Left(mkFailure(code, s"the closing verification probe failed: $detail"))
          case Right(json) =>
            readProbe(json) match
              case None =>
                Left(
                  mkFailure(
                    CodeTooOld,
                    "the device's receiver answered the closing verification probe without bytesReceived/prefixSha256"
                  )
                )
              case Some((bytesOnPeer, prefixSha)) =>
                if bytesOnPeer != entry.sizeBytes then
                  Left(
                    mkFailure(
                      CodeInterrupted,
                      s"the device holds $bytesOnPeer bytes but ${entry.sizeBytes} B were sent " +
                        s"(receiver-computed prefix sha256 $prefixSha)"
                    )
                  )
                else if prefixSha != entry.sha256 then
                  Left(
                    mkFailure(
                      CodeDigest,
                      s"the device re-computed sha256 $prefixSha over its own bytes, which differs from the original's " +
                        s"${entry.sha256} — the bytes that landed are NOT the bytes that were attached"
                    )
                  )
                else Right(())
            end match
        }
    }
  end pushChunks

  /** 块回执校验（接收端**自算**的读数 vs 本机读数）。 */
  private def verifyAck(
    entry: DelegateAttachments.Entry,
    chunk: AttachContract.ChunkPlan,
    localChunkSha: String,
    isLast: Boolean,
    ack: Json,
    mkFailure: (String, String) => Failure
  ): IO[Either[Failure, Unit]] =
    val hc = ack.hcursor
    val ackChunkSha = hc.downField("chunkSha256").as[String].toOption.getOrElse("")
    val bytesReceived = hc.downField("bytesReceived").as[Long].toOption.getOrElse(-1L)
    val whole = hc.downField("wholeSha256").as[String].toOption.getOrElse("")
    val expectedMin = chunk.offset + chunk.bytes
    val result: Either[Failure, Unit] =
      if ackChunkSha.isEmpty then
        Left(
          mkFailure(
            CodeTooOld,
            "the device's receiver acknowledged a chunk WITHOUT its self-computed chunkSha256 — that engine build " +
              "predates the verified chunked FileTransfer leg; upgrade the engine on that device and re-dispatch"
          )
        )
      else if ackChunkSha != localChunkSha then
        Left(
          mkFailure(
            CodeDigest,
            s"chunk ${chunk.index} digest mismatch: this machine computed $localChunkSha, the device computed $ackChunkSha"
          )
        )
      else if bytesReceived < expectedMin then
        Left(
          mkFailure(
            CodeInterrupted,
            s"chunk ${chunk.index} was acknowledged but the device reports only $bytesReceived of $expectedMin bytes"
          )
        )
      else if isLast && whole.isEmpty then
        Left(
          mkFailure(
            CodeTooOld,
            "the device's receiver did not return the whole-file sha256 on the final chunk — that engine build predates " +
              "the verified chunked FileTransfer leg; upgrade the engine on that device and re-dispatch"
          )
        )
      else if isLast && whole != entry.sha256 then
        Left(
          mkFailure(
            CodeDigest,
            s"whole-file digest mismatch: this machine's original is ${entry.sha256}, the device computed $whole over the " +
              "bytes it assembled"
          )
        )
      else Right(())
    IO.pure(result)

  // ============================================================
  // 读数 / 分类（纯函数，spec 直测）
  // ============================================================

  /** 探针回执 → (盘上字节数, 前缀摘要)；两个键缺一即 `None`（= 对端太旧/回执不完整）。 */
  def readProbe(json: Json): Option[(Long, String)] =
    val hc = json.hcursor
    for
      bytes <- hc.downField("bytesReceived").as[Long].toOption
      sha <- hc.downField("prefixSha256").as[String].toOption
      if sha.nonEmpty
    yield (bytes, sha)

  /** 设备面失败文本 → (reason code, 人可读原因)。**纯**，逐面明示，禁摊平成「失败了」。 */
  def classify(raw: String): (String, String) =
    val msg = Option(raw).getOrElse("")
    val lower = msg.toLowerCase
    receiverError(msg) match
      case Some((code, text)) =>
        val detail = if text.isEmpty then "" else s" $text"
        if code == AttachContract.Codes.InsufficientDisk then
          (CodeDiskFull, s"the device reported insufficient disk space [$code]$detail")
        else if code == AttachContract.Codes.ChunkDigestMismatch || code == AttachContract.Codes.WholeDigestMismatch then
          (CodeDigest, s"the device recomputed the bytes it received and they do not match [$code]$detail")
        else if code == AttachContract.Codes.UnsupportedProtocol then
          (CodeTooOld, s"the device's engine does not speak this channel [$code]$detail")
        else (CodeRefused, s"the device's receiver refused this chunk [$code]$detail")
      case None =>
        if isDiskFull(lower) then (CodeDiskFull, s"the device reported insufficient disk space: ${trim(msg)}")
        else if isTooOld(lower) then
          (
            CodeTooOld,
            s"the device's engine does not implement the receiver side of this channel (${trim(msg)}) — upgrade the " +
              "engine on that device and re-dispatch"
          )
        else if isOffline(lower) then
          (CodeOffline, s"the device could not be reached on either leg (${trim(msg)})")
        else (CodeInterrupted, s"the transfer broke (${trim(msg)})")

  /** 接收端结构化错误体（`AttachContract.AttachError.toJson`）→ (code, message)。 */
  def receiverError(raw: String): Option[(String, String)] =
    val from = raw.indexOf('{')
    if from < 0 then None
    else
      io.circe.parser.decode[Json](raw.substring(from)).toOption.flatMap { json =>
        val hc = json.hcursor
        hc.downField("code").as[String].toOption.map(code => code -> hc.downField("message").as[String].getOrElse(""))
      }

  private def isDiskFull(lower: String): Boolean =
    lower.contains("insufficient_disk") || lower.contains("no space left") || lower.contains("not enough space") ||
      lower.contains("disk full") || lower.contains("enospc")

  private def isTooOld(lower: String): Boolean =
    lower.contains("unknown direction: probe") || lower.contains("unknown direction: put") ||
      lower.contains("missing content for put")

  private def isOffline(lower: String): Boolean =
    lower.contains("cannot reach") || lower.contains("not found among") || lower.contains("no peer") ||
      lower.contains("relay failed") || lower.contains("timed out") || lower.contains("timeout") ||
      lower.contains("connection refused") || lower.contains("econnrefused") || lower.contains("not initialized")

  private def trim(s: String): String =
    val one = s.replace('\n', ' ').trim
    if one.length <= 300 then one else one.take(300) + "…"

  // ============================================================
  // 小工具
  // ============================================================

  /**
   * 对端路径拼接（**对端**分隔符，不是本机）：根含 `\` 且不含 `/` ⇒ Windows 形态。
   * 接收端 `PathUtil.expandPathParams` 对非 `~` 串会按**它自己**的 OS 归一化分隔符，
   * 故这里只为让清单里的路径与对端一致（人/模型看到的就是那台机器上的真路径）。
   */
  def joinPeerPath(base: String, segments: List[String]): String =
    val sep = if base.contains('\\') && !base.contains('/') then '\\' else '/'
    val trimmed = base.replaceAll("""[/\\]+$""", "")
    (trimmed :: segments.map(_.replaceAll("""^[/\\]+""", ""))).mkString(sep.toString)

  /** 目录名安全化（dispatchId 可能含分隔符/控制字符）。 */
  private def safeSegment(id: String): String =
    val cleaned = id.map(c => if c.isLetterOrDigit || c == '-' || c == '_' || c == '.' then c else '-').mkString
    cleaned.trim match
      case "" => "dispatch"
      case s => if s.length > 96 then s.take(96) else s

  /** 读稳定副本的一个字节窗（≤ 块长驻留，绝不整件读入）。 */
  private def readRange(path: String, offset: Long, len: Int): Array[Byte] =
    val buf = new Array[Byte](len)
    val raf = new java.io.RandomAccessFile(path, "r")
    try
      raf.seek(offset)
      var read = 0
      while read < len do
        val n = raf.read(buf, read, len - read)
        if n <= 0 then throw new java.io.EOFException(s"short read at offset $offset (+$read of $len)")
        read += n
      buf
    finally raf.close()

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

end RemoteAttachments
