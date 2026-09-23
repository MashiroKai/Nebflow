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
import java.time.ZonedDateTime

import scala.concurrent.duration.*

/**
 * P0 wtmove — the resolved state of a transfer's temp path, produced by the single
 * guard `DropboxService.guardedTempPath` and consumed by `commitTempFile` /
 * `deleteTempFile` / `handleFileComplete`.
 *
 * Replaces the old `""` sentinel: "no temp file recorded for this transfer" is now
 * a visible state instead of an empty string that downstream resolved to the
 * process working directory.
 */
private[dropbox] enum TempPathDecision:
  /** The only case in which a caller may touch the filesystem. */
  case Usable(path: os.Path)

  /**
   * No temp file recorded — relay direct delivery, or a rebuild that found no
   * leftover. Nothing to do; not an error.
   */
  case Absent

  /**
   * The cwd guard fired (source or destination side): acting would have touched
   * the process working directory. No filesystem change.
   */
  case Refused(reason: String)

/**
 * 提交/删除 temp 的结果 —— **判据**与**落点名**分开两格。
 *
 *   - `decision`：谁能碰文件系统的唯一判据（P0 wtmove，语义逐字不变）；
 *   - `landedPath`：**本次调用真的搬动过**时的实际目标（落地后回读；`None` = 没有任何 move）。
 *
 * WHY 分格（nfpath 批）：修复前「报道路径」由 `DropboxUtil.resolveFinalPath` 在**落地之后
 * 重算**，与这里 move 到的实际目标**不同源** —— 重算时 `os.exists` 已被本次 move 改变
 * （无冲突场景返回一个盘上不存在的 `_<ts>` 名），且时间戳取的是**报告时刻**而非落地时刻。
 */
private[dropbox] final case class CommitOutcome(decision: TempPathDecision, landedPath: Option[os.Path])

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
  /**
   * Signaling timeouts (diag-transfer-stuck R4): a message parked in one of
   * the watched statuses past its window is marked failed instead of sitting
   * in the UI as「传输中…」forever. Injected so tests can use short windows.
   */
  offerTimeout: FiniteDuration = 120.seconds, // pending → waiting for file-response
  acceptedTimeout: FiniteDuration = 10.minutes, // accepted → waiting for the frontend upload
  transferTimeout: FiniteDuration = 31.minutes // transferring → waiting for upload completion (P2P HTTP caps at 30min)
):

  /**
   * 落名用的时钟（dropnam 批 A4）：**可注入**是判据④（同一秒连送 ≥6 份）**确定性**的前提 ——
   * 否则该判据在慢机上是概率判据，变异验红里会假绿。缺省 = 真实时钟。
   *
   * ⚠️ 刻意**不做成主构造器的第 6 个形参**：`WtMoveGuardProbe` 按
   * `getDeclaredConstructor(getParameterCount == 5)` 反射定位主构造器 ⇒ 加形参会把它打断。
   * 注入走下面的 6 参 secondary ctor（同样是生产签名，但不动主构造器元数）。
   */
  private var landingClock: () => ZonedDateTime = () => ZonedDateTime.now()

  /** 可注入落名时钟的构造器（测试面）：`createForTest` 走这条路。 */
  private[nebflow] def this(
    neblinkService: NeblinkService,
    wsHub: WsHub,
    offerTimeout: FiniteDuration,
    acceptedTimeout: FiniteDuration,
    transferTimeout: FiniteDuration,
    clock: () => ZonedDateTime
  ) =
    this(neblinkService, wsHub, offerTimeout, acceptedTimeout, transferTimeout)
    this.landingClock = clock

  private val logger = NebflowLogger.forName("nebflow.dropbox")

  /** 伴生对象里的公开契约类型（Scala 3 不把伴生成员自动纳入类作用域）。 */
  private type FileSpec = DropboxService.FileSpec
  private type ChunkHeaders = DropboxService.ChunkHeaders

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
    loadMessages *> loadTransfers *> sweepOrphanTemps *> neblinkService.addDataHandler(handleDataMessage)

  /**
   * 启动清孤儿：temp 目录里有、但没有任何**活跃**会话引用的文件 ⇒ 删除。
   * （设计件 §3.4 清理点 ⑥；防「失败分支不删 temp」的既有泄漏面长期累积。）
   */
  private def sweepOrphanTemps: IO[Unit] =
    transfersRef.get.flatMap { live =>
      IO.blocking {
        val dir = PathUtil.dataRoot / "dropbox" / ".tmp"
        if os.exists(dir) then
          os.list(dir)
            .filter { p =>
              val id = p.last.stripSuffix(".tmp")
              if !live.contains(id) then
                os.remove(p)
                true
              else false
            }
            .size
        else 0
      }.handleErrorWith(_ => IO.pure(0))
        .flatMap { removed =>
          if removed > 0 then logger.info(s"Dropbox: swept $removed orphan temp file(s)") else IO.unit
        }
    }

  // ===== Persistence =====

  private val messagesPath = PathUtil.dataRoot / "dropbox" / "messages.json"

  private def loadMessages: IO[Unit] =
    IO.blocking {
      if os.exists(messagesPath) then Some(os.read(messagesPath)) else None
    }.flatMap {
      case None => IO.unit
      case Some(raw) =>
        DropboxLedger.decode(raw) match
          case Right(decoded) =>
            // 逐条容错的结果：条目级失败**不**放大成整表失败（作者 2026-09-17 #785 裁定），
            // 但**必须留痕**——跳过几条、为何跳过，逐条登记（禁静默吞）。
            reportLedgerSkips(decoded) *> messagesRef.set(decoded.messages)
          case Left(err) =>
            // 表级不可解（非 JSON / 顶层非对象）：没有「其余条目」可救 ⇒ 按空表继续 +
            // 明示 WARN（禁静默）。🔴 台账文件**只读不写、零迁移**（唯一写者仍是 persistMessages）。
            logger.warn(
              s"Dropbox: message ledger $messagesPath is undecodable at table level ($err); " +
                s"starting with an empty message table — the file is left untouched"
            )
    }

  /** 台账里被跳过的条目 —— 汇总一行 + 明细（上限 10 条，防坏账本刷屏）。 */
  private def reportLedgerSkips(decoded: DropboxLedgerDecode): IO[Unit] =
    if decoded.skipped.isEmpty then IO.unit
    else
      val detail = decoded.skipped.take(10).map(s => s"device=${s.deviceId} index=${s.index} reason=${s.reason}")
      logger.warn(
        s"Dropbox: skipped ${decoded.skipped.size} undecodable message entr(ies) across " +
          s"${decoded.messages.size} device(s); all other entries were loaded as-is. " +
          s"First ${detail.size}: ${detail.mkString(" | ")}"
      )

  private def persistMessages: IO[Unit] =
    messagesRef.get.flatMap { msgs =>
      IO.blocking(os.write.over(messagesPath, msgs.asJson.spaces2, createFolders = true))
        .handleErrorWith(e => logger.warn(s"Failed to persist dropbox messages: ${e.getMessage}"))
    }

  // --- 分块会话持久层（附件腿批）---
  //
  // 今天 transfersRef 是纯内存 Ref（DropboxModels 注释自陈 "in-memory"），进程重启后
  // 断点续传无据可依。新增 ~/.nebflow/dropbox/transfers.json，**节流**落盘。
  // 恢复权威仍是「temp 文件实际长度 + 重算前缀摘要」—— 持久层只是索引，不是真相。

  private val transfersPath = PathUtil.dataRoot / "dropbox" / "transfers.json"

  /** 落盘节流（建议值：5 s；见设计件 §9 P-5）。 */
  private val transfersPersistThrottle = 5.seconds

  // ===== 设备腿速率记忆（xferb 批 · P0-1）=====
  //
  // 块大小与单块死线按**实测速率**派生（`AttachContract.adaptiveChunkSize` / `chunkDeadline`）。
  // 速率的唯一来源 = 真实传输里**成功块**的数据时间（`ChunkedSendLoop.onRate`）；无读数时用
  // `AssumedMinRateBytesPerSec`（保守）。记忆**按对端设备**分格：
  //   - 不跨对端借用（跨网络制式的速率不可移植：一个 LAN 对端的读数会把 Tailscale 对端的块撑大）；
  //   - 内存态（进程重启即回落保守假设）—— 与 `FileTransfer` 一样是**临时态**，
  //     不作为真相源（真相仍是接收端 temp 的实际长度 + 重算摘要）。
  private val peerRateRef: cats.effect.Ref[IO, Map[String, Long]] =
    cats.effect.Ref.unsafe[IO, Map[String, Long]](Map.empty)

  /** 会话速率读数（对该对端设备；`None` = 无实测 ⇒ 由 `AttachContract` 用保守假设）。 */
  private def peerRateHint(deviceId: String): IO[Long] =
    peerRateRef.get.map(_.getOrElse(deviceId, 0L))

  /**
   * 记一次实测速率（**指数滑动平均**，α = 0.5）：单次读数不主导会话块参，
   * 但一两次慢传输足以把块压小（方向 = 保守）。
   */
  private def rememberRate(deviceId: String, measured: Long): IO[Unit] =
    if measured <= 0L then IO.unit
    else
      peerRateRef.update { m =>
        m.get(deviceId) match
          case Some(prev) if prev > 0L =>
            m + (deviceId -> ((prev + measured) / 2L))
          case _ => m + (deviceId -> measured)
      }

  /**
   * 失败后的速率假设下调（**有界减半**，下限 `AttachContract.MinRateBytesPerSec`）：
   * 一块在死线内没传完 ⇒ 该链路的实际速率 < 块/死线 ⇒ 下次会话取更小的块、更宽的死线。
   * 依据 = 失败自己的算术（改前失败恒 ≈367 s，与文件大小无关，正是因为参数没跟着链路走）。
   */
  private def degradeRate(deviceId: String): IO[Unit] =
    peerRateRef.update { m =>
      val prev = m.getOrElse(deviceId, AttachContract.AssumedMinRateBytesPerSec)
      m + (deviceId -> math.max(AttachContract.MinRateBytesPerSec, prev / 2L))
    }

  private val lastTransfersPersistRef: cats.effect.Ref[IO, Long] =
    cats.effect.Ref.unsafe[IO, Long](0L)

  private def loadTransfers: IO[Unit] =
    IO.blocking {
      if os.exists(transfersPath) then decode[Map[String, FileTransfer]](os.read(transfersPath)).toOption
      else None
    }.flatMap {
      case Some(m) =>
        // 只恢复**未终结**的会话；已终结的（completed/failed/rejected）不再参与续传。
        val live = m.filter { case (_, t) =>
          t.status == "pending" || t.status == "accepted" || t.status == "transferring"
        }
        transfersRef.set(live) *> logger.info(s"Dropbox: restored ${live.size} live transfer(s) from disk")
      case None => IO.unit
    }

  /** 节流落盘（每块后调用不会每次都写盘）。 */
  private def persistTransfersThrottled: IO[Unit] =
    for
      now <- IO.realTime.map(_.toMillis)
      last <- lastTransfersPersistRef.get
      _ <-
        if now - last >= transfersPersistThrottle.toMillis then lastTransfersPersistRef.set(now) *> persistTransfers
        else IO.unit
    yield ()

  private def persistTransfers: IO[Unit] =
    transfersRef.get.flatMap { ts =>
      IO.blocking(os.write.over(transfersPath, ts.asJson.spaces2, createFolders = true))
        .handleErrorWith(e => logger.warn(s"Failed to persist dropbox transfers: ${e.getMessage}"))
    }

  /** 目标 temp 路径（接收端）。发送端 temp 在 ~/.nebflow/dropbox/.tmp/<tid>.tmp。 */
  private def senderTempPath(transferId: String): os.Path =
    PathUtil.dataRoot / "dropbox" / ".tmp" / s"$transferId.tmp"

  /**
   * 接收端 temp 的确定性派生名（记录路径不可用时回落；同名可再定位 ⇒ 续传可重建）。
   *
   * 契约升版批（设备腿 `targetDir`）：派生名的**父目录改取会话落点**
   * （[[DropboxService.landingDirFor]]）—— 判定通过时 = 接收端裁定的请求目录，
   * 其余情况 = `downloadsDir`（缺省语义与今天**逐字节一致**）。
   */
  private def derivedReceiverTempPath(t: FileTransfer): os.Path =
    // 名字恒走**唯一生成器**（`DropboxUtil.receiverTempName`）——本文件内**不得**再出现
    // 第二处 `.dropbox-` 拼串（dropnam 批 · 契约①「最终名字只在一处算」，机械可核）。
    DropboxService.landingDirFor(t) / DropboxUtil.receiverTempName(t.fileName, t.transferId)

  /**
   * 接收端 temp 路径 —— **唯一解析入口是 `guardedTempPath`**（P0 wtmove 守卫；
   * 本方法里**没有**任何「把 `t.tempPath` 与 `os.pwd` 直接拼路径」的裸解析）。
   *
   * 分支按 `TempPathDecision` 显式处理：
   *   - `Usable` ⇒ 复用记录路径（断点续传的前提：跨会话同一文件）；
   *   - `Absent` ⇒ 本会话尚无记录（新入向会话的正常态，非异常）⇒ 用确定性派生名；
   *   - `Refused` ⇒ 记录路径落在 cwd 族（旧代码会把整块数据写进工作目录）⇒ 明确
   *     WARN + 回落派生名；**绝不**把裸 cwd 送进文件系统。
   */
  private def receiverTempPath(t: FileTransfer): IO[os.Path] =
    guardedTempPath(t) match
      case TempPathDecision.Usable(p) => IO.pure(p)
      case TempPathDecision.Absent => IO.pure(derivedReceiverTempPath(t))
      case refused @ TempPathDecision.Refused(_) =>
        warnTempPath("receiverTempPath", t, refused).as(derivedReceiverTempPath(t))

  // ===== Public API (called from WebSocketRoutes) =====

  /**
   * Send a text message to a peer.
   *
   * 附件腿批（2026-09-14，SendMessage `device:` 目标）返回值从 `IO[Unit]` 改为
   * `IO[Boolean]`：true = 至少一条通道（P2P WS / relay Notify）实际接受了帧；
   * false = 两腿皆未送达（消息已按既有语义标 failed）。前端 WS 调用点丢弃返回值，
   * 行为零变更；工具腿需要投递真值才能给出诚实的工具结果（禁静默成功）。
   */
  def sendText(deviceId: String, text: String, origin: String = DropboxMessage.OriginUser): IO[Boolean] =
    neblinkService.identity.flatMap { id =>
      val msg = DropboxMessage(
        msgId = DropboxModels.newId,
        direction = "out",
        kind = "text",
        ts = DropboxModels.now,
        text = text,
        origin = origin
      )
      val payload = Json.obj(
        "kind" -> "text".asJson,
        "senderId" -> id.deviceId.asJson,
        "senderName" -> id.deviceName.asJson,
        "msgId" -> msg.msgId.asJson,
        "text" -> text.asJson,
        "ts" -> msg.ts.asJson,
        // 设备会话统一批 MVP-1：来源标记上 wire（收端只作提示级渲染，见 DropboxMessage.origin）。
        "origin" -> origin.asJson
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
      yield delivered
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
      case true => IO.pure(true)
      case false =>
        neblinkService.relayClientOpt match
          case Some(client) =>
            client
              .relayNotify(deviceId, channel, payload)
              .map(_.isRight)
              .handleErrorWith(e => logger.warn(s"Relay notify to $deviceId failed: ${e.getMessage}").as(false))
          case None =>
            logger.warn(s"Cannot deliver dropbox message to $deviceId: no P2P WS and no relay client").as(false)
    }

  /**
   * Mark a transfer + its message as failed and tell the frontend (R3/R4).
   *
   * 字符串原因版（信令投递失败一类）：包成结构化错误体（`code = PEER_UNREACHABLE`、
   * `phase = offer`）后走 [[markTransferFailedWith]] —— 前端/台账因此**恒有**码可判。
   */
  private def markTransferFailed(
    transferId: String,
    peerDeviceId: String,
    msgId: String,
    reason: String
  ): IO[Unit] =
    markTransferFailedWith(
      transferId,
      peerDeviceId,
      msgId,
      AttachContract.AttachError(AttachContract.Codes.PeerUnreachable, reason, phase = "offer"),
      resumable = false
    )

  /**
   * 失败收口（**结构化原因**，xferb 批 · P0-3）。
   *
   * 改前：事件只带一个 `error` 字符串、台账只带 `status="failed"` ⇒ 界面上只剩「失败」，
   * 而服务端其实知道 `code` / `phase` / `chunkIndex` / `p2pReason` / `relayReason`。
   * 本方法把这三样**同时**落到三处（第二段直接上屏，无需再补网关面）：
   *   ① 前端事件 `dropbox-file-complete`：`error`（人读，保留）+ `errorCode` + `errorDetail`（结构化全文）；
   *   ② 台账（`messages.json`）：`errorCode` + `errorDetail`（刷新/重开窗后原因仍在）；
   *   ③ 日志：`[CODE] …`（既有形态，可 grep 归因）。
   *
   * `resumable`（P0-2）：true ⇒ 对端的半成品 temp **必须保留**（同号重试要接着传），
   * 随 `file-complete` 帧上 wire（新增键；旧对端忽略未知键 ⇒ 退化为旧的「失败即清 temp」行为，无回归）。
   */
  private def markTransferFailedWith(
    transferId: String,
    peerDeviceId: String,
    msgId: String,
    err: AttachContract.AttachError,
    resumable: Boolean
  ): IO[Unit] =
    for
      _ <- updateTransferStatus(transferId, "failed")
      _ <- updateMessageStatus(
        peerDeviceId,
        msgId,
        "failed",
        errorCode = err.code,
        errorDetail = err.toJson.noSpaces
      )
      _ <- notifyFrontend(
        "dropbox-file-complete",
        peerDeviceId,
        Json.obj(
          "transferId" -> transferId.asJson,
          "msgId" -> msgId.asJson,
          "success" -> false.asJson,
          "error" -> err.render.asJson,
          "errorCode" -> err.code.asJson,
          "errorDetail" -> err.toJson,
          "resumable" -> resumable.asJson
        )
      )
      _ <- logger.warn(s"Dropbox transfer $transferId marked failed: ${err.render} (resumable=$resumable)")
    yield ()

  /**
   * 该失败是否**可续**（P0-2 判据）：失败在**传输态**且原因不是「接收端对内容判否」
   * （摘要不符 / 空洞 / 越界 ⇒ 半成品无效，保留只会污染下次重试）⇒ 对端 temp 值得保留。
   * 重试时沿用同一 transferId ⇒ 同 temp ⇒ 探针找得到前缀。
   */
  private def isResumable(err: AttachContract.AttachError): Boolean =
    err.phase == "transfer" && !Set(
      AttachContract.Codes.ChunkDigestMismatch,
      AttachContract.Codes.WholeDigestMismatch,
      AttachContract.Codes.OffsetOutOfRange
    ).contains(err.code)

  /**
   * R4 timeout watchdog: after `timeout`, if the transfer is still parked in
   * one of `stuckStatuses`, migrate it to failed + notify the frontend, so a
   * lost frame (disconnected peer, dead tunnel) can no longer freeze the UI
   * on「传输中…」forever. Fired as a fire-and-forget fiber; a completion that
   * arrives late (reconnect inside the window) simply no-ops here.
   *
   * 🆕 xferb 批：`transferring` 态**进展即续窗**（判据 = `lastProgressAt > since`）——
   * 见 [[failIfStuck]] 的 WHY。`since` = 本窗装表时刻（默认现读时钟）。
   */
  private def armTransferTimeout(
    transferId: String,
    peerDeviceId: String,
    msgId: String,
    timeout: FiniteDuration,
    stuckStatuses: Set[String],
    since: Long = DropboxModels.now
  ): IO[Unit] =
    (IO.sleep(timeout) *> failIfStuck(transferId, peerDeviceId, msgId, stuckStatuses, since)).start.void

  private def failIfStuck(
    transferId: String,
    peerDeviceId: String,
    msgId: String,
    stuckStatuses: Set[String],
    since: Long
  ): IO[Unit] =
    transfersRef.get.map(_.get(transferId)).flatMap {
      case Some(t) if stuckStatuses.contains(t.status) =>
        // ===== 进展即续窗（xferb 批 · P0-1 的必要件）=====
        // 改前只判**状态**不判**进展**，于是绝对死线把「慢但正常」的传输一并杀掉：
        // 实测底座 25–30 KB/s 下 50 MB 需 ≈34 min > 本窗口 31 min ⇒ 达标传输必被误判失败，
        // 且失败帧会让对端把 temp 删掉（字节白传）。`lastProgressAt` 的语义本就是
        // 「最近一次字节进展」（字段注释原文如此）⇒ 本处改用「窗口内有进展就再等一窗」。
        // 🔴 只对 `transferring` 生效（有字节预期的态）；`pending`/`accepted` 无字节预期，
        // 照旧绝对收口。
        if t.status == "transferring" && t.lastProgressAt > since then
          logger.info(
            s"Dropbox transfer $transferId still transferring and progressing (${t.bytesReceived} B confirmed, " +
              s"lastProgressAt=${t.lastProgressAt}) — extending the watchdog window instead of failing it"
          ) *> armTransferTimeout(transferId, peerDeviceId, msgId, transferTimeout, Set("transferring"))
        else
          markTransferFailedWith(
            transferId,
            peerDeviceId,
            msgId,
            AttachContract.AttachError(
              AttachContract.Codes.TransferTimeout,
              s"timeout while waiting in '${t.status}' state (${t.bytesReceived} B confirmed, no progress within the window)",
              phase = "transfer"
            ),
            resumable = true
          )
      case _ => IO.unit
    }

  /**
   * Offer **一条**文件给对端（单件入口，兼容既有调用面）。闸位不在本方法里 ——
   * 它统一落在 `offerFiles`，单件路径**不得**绕过闸位。
   */
  def offerFile(
    deviceId: String,
    fileName: String,
    fileSize: Long,
    mimeType: String
  ): IO[Either[AttachContract.AttachError, String]] =
    offerFiles(deviceId, List(DropboxService.FileSpec(fileName, fileSize, mimeType)))
      .map(_.map(_.headOption.getOrElse("")))

  // ===== Agent 附件腿（SendMessage `device:` 目标，2026-09-14）=====

  /**
   * 服务端本地文件 → 对端设备的**工具入口**。与前端通道（`dropbox-file-offer` WS）
   * 走**同一条**链：同一闸位（`offerFiles`：≤9 件 / 单件 ≤1,073,741,824 B = 1024 MB = 1 GiB，
   * 超限 fail-fast 回显实际值）+ 同一分块传输（`uploadAndRelay`：P2P 主腿 + relay
   * 兜底、每块校验、整件双侧 sha256、断点续传）。唯一差别 = **字节源是本机磁盘**
   * （不经浏览器）。
   *
   * 流程：路径校验（绝对 / 存在 / 普通文件，回显实际路径）→ 大小/件数闸 →
   * offer（含对端名册检查，未知设备 ⇒ `PEER_UNREACHABLE`，零静默本地执行）→
   * 等接收端 auto-accept（`acceptWait` 上限）→ 从盘上流式读入 `uploadAndRelay`
   * （有界缓冲，峰值内存与文件大小无关）。
   *
   * 超时语义：`uploadWait` 到点 ⇒ 本调用以显式失败返回（transfer 可能仍在后台
   * 跑，对端面板有实时进度；发送侧看门狗会兜底收口）。`.timeout` 取消会跳过
   * `uploadAndRelay` 的 temp 清理路径 ⇒ 孤儿 temp 由启动清孤儿兜底（设计件 N-5）。
   *
   * `transportOverride` 仅供测试自环注入；生产为 `None` ⇒ 真实两腿。
   */
  def sendLocalFiles(
    deviceId: String,
    files: List[os.Path],
    targetDir: Option[String] = None,
    transportOverride: Option[ChunkTransport] = None,
    acceptWait: FiniteDuration = 20.seconds,
    uploadWait: FiniteDuration = 15.minutes,
    origin: String = DropboxMessage.OriginUser
  ): IO[Either[AttachContract.AttachError, List[DropboxService.LocalFileOutcome]]] =
    val validated: Either[AttachContract.AttachError, List[(os.Path, Long)]] =
      files.foldLeft[Either[AttachContract.AttachError, List[(os.Path, Long)]]](Right(Nil)) { (acc, p) =>
        acc.flatMap { list =>
          val s = p.toString
          if !java.nio.file.Paths.get(s).isAbsolute then
            Left(
              AttachContract.AttachError(
                AttachContract.Codes.InvalidArgument,
                s"Attachment path must be absolute, got: '$s'",
                phase = "validate",
                path = Some(s)
              )
            )
          else if !os.exists(p) then
            Left(
              AttachContract.AttachError(
                AttachContract.Codes.InvalidArgument,
                s"Attachment does not exist: $s",
                phase = "validate",
                path = Some(s)
              )
            )
          else if os.isDir(p) then
            Left(
              AttachContract.AttachError(
                AttachContract.Codes.InvalidArgument,
                s"Attachment is a directory, not a file: $s",
                phase = "validate",
                path = Some(s)
              )
            )
          else Right(list :+ (p -> os.stat(p).size))
          end if
        }
      }
    validated match
      case Left(err) => IO.pure(Left(err))
      case Right(sized) =>
        // 闸位（件数 + 逐件大小，actual/limit 回显）在名册检查**之前** —— 与
        // offerFiles 内部同闸幂等，先拦住明显超限的调用，不发起任何网络。
        AttachContract.checkMessage(sized.map(_._2)) match
          case Left(err) => IO.pure(Left(err))
          case Right(_) =>
            // 设备腿 targetDir（契约升版批）：发送端只发**请求**，落点由接收端裁定。
            // §4.2 候选 1：**未确认对端等级前不发** —— 唯一的自报通道是 `file-response.proto`
            // （旧端不回带 ⇒ 视为等级 1 ⇒ 走缺省语义 + 显式回显，禁静默降级）。
            // 形态合法性**不由发送端预判**（唯一权威 = 接收端 `TargetDirGuard`）：
            // 空串 / 相对串等由接收端回拒码，发送端只做 NFC 归一化。
            val requestedDir = targetDir.map(d => TargetDirGuard.normalize(d))
            knownPeerLevel(deviceId).flatMap { lvl =>
              val peerConfirmed = lvl.exists(l =>
                AttachContract.negotiate(AttachContract.ProtoAssignDir, l) >= AttachContract.ProtoAssignDir
              )
              val wireTargetDir = if peerConfirmed then requestedDir else None
              val deferred = requestedDir.isDefined && !peerConfirmed
              // B′（selfattach 批）：把**本方法已校验过**的本机真实路径原样带入 offer 链
              // ⇒ 出向台账行的 `deviceOutPath`（写点 = `offerOne` 的消息创建处，见那里的注释）。
              // 路径值本身**零复制**：只传字符串，不建任何副本 / 暂存件。
              val specs = sized.map { case (p, size) =>
                DropboxService.FileSpec(p.last, size, guessMime(p.last), Some(p.toString))
              }
              offerFiles(deviceId, specs, wireTargetDir, origin).flatMap {
                case Left(err) => IO.pure(Left(err))
                case Right(transferIds) =>
                  sized
                    .zip(transferIds)
                    .foldLeftM[IO, List[DropboxService.LocalFileOutcome]](Nil) { case (acc, ((p, size), tid)) =>
                      sendLocalOne(tid, p, size, transportOverride, acceptWait, uploadWait, requestedDir, deferred)
                        .map(acc :+ _)
                    }
                    .map(outcomes => Right(outcomes))
              }
            }
    end match
  end sendLocalFiles

  /**
   * 对端已自报的 proto 等级（唯一来源 = `file-response.proto`，记在 out 会话记录上）。
   * `None` = 未确认 / 旧端 ⇒ §1.4 Q1 侧（禁发 `targetDir`）。
   *
   * ⚠️ 等级记忆的载体是会话记录（`transfers.json` 持久化的**未终结**会话）——进程重启后
   * 首次投递会回落 Q1，代价 = spec §4.2 已承认的「一次额外能力自报往返」。
   */
  private def knownPeerLevel(deviceId: String): IO[Option[Int]] =
    transfersRef.get.map(
      _.values
        .filter(t => t.direction == "out" && t.peerDeviceId == deviceId)
        .flatMap(_.peerProto)
        .maxOption
    )

  /** 单件本地发送：等 auto-accept → 从盘流式上传（复用 [[uploadAndRelay]] 全链）。 */
  private def sendLocalOne(
    transferId: String,
    p: os.Path,
    size: Long,
    transportOverride: Option[ChunkTransport],
    acceptWait: FiniteDuration,
    uploadWait: FiniteDuration,
    requestedTargetDir: Option[String],
    targetDirDeferred: Boolean
  ): IO[DropboxService.LocalFileOutcome] =
    val base =
      DropboxService.LocalFileOutcome(
        p.last,
        size,
        transferId,
        delivered = false,
        None,
        targetDir = requestedTargetDir,
        targetDirDeferred = targetDirDeferred
      )
    awaitAccepted(transferId, acceptWait).flatMap {
      case Some(reason) =>
        IO.pure(base.copy(error = Some(reason)))
      case None =>
        uploadAndRelay(transferId, localFileStream(p), transportOverride)
          .timeout(uploadWait)
          .map {
            case Right(_) => base.copy(delivered = true)
            case Left(err) =>
              base.copy(error = Some(err.render))
          }
          .handleErrorWith {
            case _: java.util.concurrent.TimeoutException =>
              IO.pure(
                base.copy(
                  error = Some(
                    s"upload timed out after ${uploadWait.toSeconds}s — the transfer may still be running; the peer's panel shows live progress"
                  )
                )
              )
            case e =>
              IO.pure(base.copy(error = Some(e.getMessage)))
          }
    }

  end sendLocalOne

  /**
   * 轮询等接收端 auto-accept。`None` = accepted 可以上传；`Some` = 终局原因
   * （rejected / failed / 等待超时 —— offer 仍在对端面板可见，发送侧看门狗收口）。
   */
  private def awaitAccepted(transferId: String, wait: FiniteDuration): IO[Option[String]] =
    val deadlineMs = System.currentTimeMillis() + wait.toMillis
    def poll: IO[Option[String]] =
      transfersRef.get.map(_.get(transferId)).flatMap {
        case Some(t) if t.status == "accepted" => IO.pure(None)
        case Some(t) if t.status == "rejected" =>
          IO.pure(Some(s"the receiver rejected the offer" + t.targetDirCode.map(c => s" — $c").getOrElse("")))
        case Some(t) if t.status == "failed" =>
          IO.pure(Some("the offer could not be delivered (peer unreachable on both legs)"))
        case _ =>
          if System.currentTimeMillis() >= deadlineMs then
            IO.pure(
              Some(s"receiver did not accept within ${wait.toSeconds}s (the offer stays visible in the peer's panel)")
            )
          else IO.sleep(200.millis) *> poll
      }
    poll

  end awaitAccepted

  /** 本机文件 → 有界缓冲字节流（fs2-core；峰值内存与文件大小无关）。 */
  private def localFileStream(p: os.Path, bufferSize: Int = 64 * 1024): Stream[IO, Byte] =
    Stream
      .bracket(IO.blocking(java.nio.file.Files.newInputStream(p.toNIO)))(in =>
        IO.blocking(in.close()).handleErrorWith(_ => IO.unit)
      )
      .flatMap { in =>
        Stream
          .repeatEval(IO.blocking {
            val buf = new Array[Byte](bufferSize)
            val n = in.read(buf)
            if n < 0 then fs2.Chunk.empty[Byte] else fs2.Chunk.array(buf, 0, n)
          })
          .takeWhile(_.nonEmpty)
          .flatMap(Stream.chunk)
      }

  /** 扩展名 → MIME（工具腿无浏览器 File.type，自猜小表 + 兜底 octet-stream）。 */
  private def guessMime(fileName: String): String =
    val ext = fileName.lastIndexOf('.') match
      case -1 => ""
      case idx => fileName.substring(idx + 1).toLowerCase
    ext match
      case "png" => "image/png"
      case "jpg" | "jpeg" => "image/jpeg"
      case "gif" => "image/gif"
      case "webp" => "image/webp"
      case "svg" => "image/svg+xml"
      case "pdf" => "application/pdf"
      case "txt" | "md" => "text/plain"
      case "json" => "application/json"
      case "csv" => "text/csv"
      case "html" | "htm" => "text/html"
      case "zip" => "application/zip"
      case "mp4" => "video/mp4"
      case "mp3" => "audio/mpeg"
      case _ => "application/octet-stream"

    end match

  end guessMime

  /**
   * Offer **一条消息的 N 件附件** —— 作者数两条（单件 ≤1,073,741,824 B（1024 MB = 1 GiB）/ 单条消息 ≤9 件）
   * 的**唯一闸位**。
   *
   * 超限 ⇒ **fail-fast**：不建 transfer、不发信令、不写消息，返回结构化错误
   * （`ATTACH_TOO_LARGE` / `ATTACH_TOO_MANY`，带 `actual` 实际值 + `limit`）。
   *
   * 返回每件分配的 transferId（同一 index 顺序）。
   */
  def offerFiles(
    deviceId: String,
    files: List[FileSpec],
    targetDir: Option[String] = None,
    origin: String = DropboxMessage.OriginUser
  ): IO[Either[AttachContract.AttachError, List[String]]] =
    AttachContract.checkMessage(files.map(_.fileSize)) match
      case Left(err) =>
        logger.warn(s"Dropbox offer rejected for $deviceId: ${err.render}")
        IO.pure(Left(err))
      case Right(_) if files.isEmpty =>
        IO.pure(
          Left(
            AttachContract.AttachError(
              AttachContract.Codes.InvalidArgument,
              "No attachments in the offer",
              phase = "offer"
            )
          )
        )
      case Right(_) =>
        neblinkService.identity.flatMap { id =>
          neblinkService.peers.flatMap { peers =>
            peers.find(_.deviceId == deviceId) match
              case None =>
                // I3 / S1：今天的 `IO.unit // peer not found, ignore` 静默吞失败；
                // 新契约禁复现 —— 设备不存在必须显式可见、不静默本地执行。
                IO.pure(
                  Left(
                    AttachContract.AttachError(
                      AttachContract.Codes.PeerUnreachable,
                      s"Device $deviceId not found in the peer roster — nothing was offered or written locally",
                      phase = "offer",
                      actual = Some(files.size.toLong)
                    )
                  )
                )
              case Some(peer) =>
                val batchId = DropboxModels.newId
                val total = files.size
                files.zipWithIndex
                  .foldLeftM[IO, List[String]](Nil) { case (acc, (spec, idx)) =>
                    // 串行 offer（并行度建议值 N = 1；见设计件 §9 P-6）。
                    offerOne(id, peer, deviceId, spec, batchId, idx, total, targetDir, origin).map(acc :+ _)
                  }
                  .map(ids => Right(ids))
          }
        }

  /**
   * Offer 单件（`offerFiles` 已过闸）。
   *
   *  **B′ 写点（selfattach 批 · 唯一）**：出向行的**唯一创建点**就在这里 —— 因此「发送端
   *  本机真实路径」（`spec.outPath`，由 [[sendLocalFiles]] 校验后带入）也只在这一处写：
   *  `deviceOutPath`。三条理由（登记在报告里）：
   *    ① **单一写点**：出向消息只此一处构造 ⇒ 无第二个判据、无第二次赋值；
   *    ② **首帧即可用**：本消息经 `notifyFrontend("dropbox-message", …)`（见下方
   *       `offerOne` 尾段）直达**本机**前端；完成帧（`completeTransfer` 的
   *       `dropbox-file-complete`）按隐私约束**逐字不动** ⇒ 若改在完成时写，发送端 UI
   *       得等下一次 `dropbox-get-history` 才看得到；
   *    ③ **与传输成败正交**：路径记的是「发送动作那一刻的本机现实」，成败由 `status` 承载
   *       （前端按 5 态门控可点面，失败行不挂键）。
   *
   *  🔴 该字段**不上对端帧**：下方 `payloadBase` 是手写 `Json.obj`（无该键），本字段只在
   *  台账 + 本机前端帧里流动（运行期实测见 `SenderOutPathLedgerSpec` 的对端帧捕获断言）。
   *  🔴 浏览器 user 腿：`spec.outPath = None` ⇒ 恒空串（禁 basename 拼接 / 禁预测名）。
   */
  private def offerOne(
    id: nebflow.neblink.DeviceIdentity,
    peer: nebflow.neblink.PeerInfo,
    deviceId: String,
    spec: FileSpec,
    batchId: String,
    index: Int,
    count: Int,
    targetDir: Option[String],
    origin: String = DropboxMessage.OriginUser
  ): IO[String] =
    val transferId = DropboxModels.newId
    val msgId = DropboxModels.newId
    val msg = DropboxMessage(
      msgId = msgId,
      direction = "out",
      kind = "file",
      ts = DropboxModels.now,
      transferId = transferId,
      fileName = spec.fileName,
      fileSize = spec.fileSize,
      mimeType = spec.mimeType,
      status = "pending",
      // B′ 写点：发送端本机真实路径（浏览器腿 = 缺省 ""）。逐字见本方法上方注释。
      deviceOutPath = spec.outPath.getOrElse(""),
      batchId = batchId,
      attachmentIndex = index,
      attachmentCount = count,
      origin = origin
    )
    val payloadBase = Json.obj(
      "kind" -> "file-offer".asJson,
      "senderId" -> id.deviceId.asJson,
      "senderName" -> id.deviceName.asJson,
      "transferId" -> transferId.asJson,
      "msgId" -> msgId.asJson,
      "fileName" -> spec.fileName.asJson,
      "fileSize" -> spec.fileSize.asJson,
      "mimeType" -> spec.mimeType.asJson,
      // 协议协商：缺失 = 0 = 整件 legacy。双方取 min。本键 = **JSON 面**的等级自报，
      // 与 P2P 头 `X-Dropbox-Proto`（**恒 1**，见 AttachContract.ProtoAssignDir 文档）**不同轴**。
      "proto" -> AttachContract.ProtoRelayTemp.asJson,
      "batchId" -> batchId.asJson,
      "attachmentIndex" -> index.asJson,
      "attachmentCount" -> count.asJson,
      // 设备会话统一批 MVP-1：来源标记上 wire（与 sendText 同轴；收端只作提示级渲染）。
      "origin" -> origin.asJson
    )
    // targetDir 只在**对端等级已确认（proto >= 2）**时才上 wire（spec §4.2 候选 1）；
    // 缺省 = 现状（键不出现 ⇒ 旧接收端天然忽略）。
    val payload = targetDir match
      case Some(d) => payloadBase.deepMerge(Json.obj("targetDir" -> d.asJson))
      case None => payloadBase
    // 会话块参（xferb 批 · P0-1）：块大小按**实测速率**反解，缺省回落到保守假设。
    // 会话内冻结（契约 §3.1），随既有 `chunkSize` 会话值/分块头走 —— **零协议改动**。
    peerRateHint(deviceId).flatMap { rateHint =>
      val chunkSize = AttachContract.adaptiveChunkSize(rateHint)
      val transfer = FileTransfer(
        transferId = transferId,
        direction = "out",
        peerDeviceId = deviceId,
        peerAddress = peer.address,
        fileName = spec.fileName,
        fileSize = spec.fileSize,
        mimeType = spec.mimeType,
        msgId = msgId,
        status = "pending",
        totalBytes = spec.fileSize,
        chunkSize = chunkSize,
        proto = AttachContract.ProtoRelayTemp,
        targetDir = targetDir
      )
      logger.info(
        s"Dropbox transfer $transferId session chunk params: chunkSize=$chunkSize B, " +
          s"rateHint=${AttachContract.normalizeRate(rateHint)} B/s, " +
          s"legDeadlines=(p2p=${AttachContract.p2pDeadline(chunkSize.toLong, rateHint).toMillis}ms, " +
          s"relay=${AttachContract.relayDeadline(chunkSize.toLong, rateHint).toMillis}ms)"
      ) *>
        (for
          _ <- transfersRef.update(_ + (transferId -> transfer))
          _ <- addMessage(deviceId, msg)
          delivered <- sendDataOrRelay(deviceId, "dropbox", payload)
          _ <-
            if delivered then
              // Arm pending-timeout: if the file-response never comes back
              // (peer/relay down), fail instead of parking on「传输中…」.
              armTransferTimeout(transferId, deviceId, msgId, offerTimeout, Set("pending")) *>
                notifyFrontend("dropbox-message", deviceId, msg.asJson)
            else markTransferFailed(transferId, deviceId, msgId, "file-offer could not be delivered")
          _ <- persistTransfersThrottled
        yield transferId)
    }
  end offerOne

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
              else markTransferFailed(transferId, senderDeviceId, t.msgId, "file-response could not be delivered")
          yield ()
          end for
        case None => IO.unit
    yield ()

  /** Get message history for a peer. */
  def getHistory(deviceId: String): IO[List[DropboxMessage]] =
    messagesRef.get.map(_.getOrElse(deviceId, Nil))

  // ===== HTTP File Transfer (called from RestApiRoutes) =====

  /**
   * 发送端：前端上传 → 落 temp（**字节流上限兜底** + 单遍整件摘要）→ **分块**送对端
   * （P2P 主腿 + relay 兜底共面）→ 双侧整件摘要比对 → 通知两端。
   *
   * 🆕 **同号续传入口（xferb 批 · P0-2）**：本端点按 `transferId` 索引，重试**沿用同一号**
   * 即命中同一会话 ⇒ 接收端 temp 的既有前缀可续（权威 = 探针返回的实际长度）。
   * 状态门据此放宽为 {`accepted`, `failed`, `transferring`}：
   *   - `accepted` = 首传；
   *   - `failed` = 同号重试（前一次失败的收口态；这是本批新增的**合法**入口 ——
   *     改前 failed 会话一律被拒 ⇒ 「重试」只能在客户端新铸号 ⇒ 已传字节全部作废）；
   *   - `transferring` = 进程重启后从盘上恢复的未终结会话（`loadTransfers` 只恢复未终结态）。
   * 🔴 判据边界（照卡 P0-2 的风险项）：**同号 = 重试**、新动作 = 新号（由 offer 侧铸号），
   * 故不需要额外的时间戳/内容判据 —— 号本身就是入口语义。
   *
   * `transportOverride` 仅供测试自环注入；生产为 `None` ⇒ 真实两腿。
   *
   * 返回面（xferb 批 · P0-3）：从 `Either[String, Unit]` 收紧为
   * `Either[AttachContract.AttachError, Unit]` —— 调用方（REST 端点 / 工具腿）因此拿到
   * **结构化**原因（code/phase/字段），而不是一句人读文本。人读面照旧由 `err.render` 提供。
   */
  def uploadAndRelay(
    transferId: String,
    body: Stream[IO, Byte],
    transportOverride: Option[ChunkTransport] = None
  ): IO[Either[AttachContract.AttachError, Unit]] =
    transfersRef.get.map(_.get(transferId)).flatMap {
      case None =>
        IO.pure[Either[AttachContract.AttachError, Unit]](
          Left(
            AttachContract.AttachError(AttachContract.Codes.SessionNotFound, "Transfer not found", phase = "transfer")
          )
        )
      case Some(t) if t.direction != "out" =>
        IO.pure[Either[AttachContract.AttachError, Unit]](
          Left(
            AttachContract
              .AttachError(AttachContract.Codes.InvalidArgument, "Not an outgoing transfer", phase = "transfer")
          )
        )
      case Some(t) if !retryableOutStates.contains(t.status) =>
        IO.pure[Either[AttachContract.AttachError, Unit]](
          Left(
            AttachContract.AttachError(
              AttachContract.Codes.InvalidArgument,
              s"Transfer not accepted by receiver (status=${t.status})",
              phase = "transfer"
            )
          )
        )
      case Some(t) =>
        val tempPath = senderTempPath(transferId)
        for
          _ <- IO.blocking(os.makeDir.all(tempPath / os.up))
          // ① 落 temp + 上限兜底：声明 size 撒谎（少报）也在这里被拦下 —— fail-fast、
          //    不发送、不建会话。超限时回显**实际值**。
          hashed <- DropboxUtil.streamToFileWithHashBounded(body, tempPath, AttachContract.MaxFileBytes)
          res <- hashed match
            case Left(err) =>
              for
                _ <- IO.blocking(if os.exists(tempPath) then os.remove(tempPath) else ())
                _ <- markTransferFailedWith(transferId, t.peerDeviceId, t.msgId, err, resumable = false)
              yield Left(err)
            case Right(senderWhole) =>
              for
                rateHint <- peerRateHint(t.peerDeviceId)
                _ <- updateTransferStatus(transferId, "transferring") *>
                  updateMessageStatus(t.peerDeviceId, t.msgId, "transferring")
                _ <- armTransferTimeout(transferId, t.peerDeviceId, t.msgId, transferTimeout, Set("transferring"))
                transport = transportOverride.getOrElse(productionTransport(t, rateHint))
                outcome <- ChunkedSendLoop.run(
                  transport,
                  t,
                  tempPath,
                  // 会话冻结的块大小（offer 时写入 transfer）；0 = 未协商 ⇒ 契约默认值
                  // （0 只可能来自旧盘上记录；新会话恒由 `offerOne` 按实测速率反解）。
                  chunkSize = if t.chunkSize > 0 then t.chunkSize else AttachContract.ChunkSize,
                  onProgress = (idx, received) => recordProgress(transferId, t, idx, received),
                  onInFlight = (acked, inFlight) => recordInFlightProgress(transferId, t, acked, inFlight),
                  onRate = rate => rememberRate(t.peerDeviceId, rate)
                )
                // ② 一切退出路径都清 temp（修今天「失败分支不删」的泄漏面）。
                //    🔴 清的是**发送端**自己的暂存件（字节源可重取）；**接收端**的半成品
                //    temp 由 P0-2 保留（`resumable` 随 file-complete 上 wire）——两者不同轴。
                _ <- IO.blocking(if os.exists(tempPath) then os.remove(tempPath) else ())
                out <- outcome match
                  case Right(o) =>
                    // 双侧 sha256 一致（ChunkedSendLoop 已做终局比对；这里落读数 + 通知）。
                    logger.info(
                      s"Dropbox transfer $transferId completed: ${o.chunksSent} chunk(s), ${o.bytesSent} B, " +
                        s"sha256(sender)=$senderWhole sha256(receiver)=${o.receiverComputedSha256} leg=${o.leg}"
                    ) *>
                      // 收口腿（`file-complete` 投递）的既有失败面是**字符串**；本批把返回面
                      // 收紧到结构化 ⇒ 在这里显式转码（相位 `transfer`、支路 `deliver`），
                      // 而不是让它退化成无字段的文本（P0-3 一致性）。语义不变：
                      // `completeTransfer` 内部仍按 I3 显式 failed + 落台账。
                      completeTransfer(transferId, t, o.receiverComputedSha256).map {
                        case Left(msg) =>
                          Left(
                            AttachContract.AttachError(
                              AttachContract.Codes.PeerUnreachable,
                              msg,
                              phase = "transfer",
                              path = Some("deliver")
                            )
                          )
                        case Right(u) => Right(u)
                      }
                  case Left(err) =>
                    // 失败 ⇒ 有界下调该对端的速率假设（下次会话块更小、死线更宽）。
                    degradeRate(t.peerDeviceId) *>
                      markTransferFailedWith(transferId, t.peerDeviceId, t.msgId, err, resumable = isResumable(err))
                        .as(Left(err))
              yield out
        yield res
        end for
    }

  /** 允许进入上传/续传的状态（P0-2 同号重试的三个合法入口态）。 */
  private val retryableOutStates: Set[String] = Set("accepted", "failed", "transferring")

  /** 记录块级进展：推进 `bytesReceived` + `lastProgressAt`（看门狗按它计时），并通知前端。 */
  private def recordProgress(transferId: String, t: FileTransfer, chunkIndex: Int, received: Long): IO[Unit] =
    transfersRef
      .update(m =>
        m.get(transferId) match
          case Some(cur) => m + (transferId -> cur.copy(bytesReceived = received, lastProgressAt = DropboxModels.now))
          case None => m
      ) *>
      persistTransfersThrottled *>
      notifyFrontend(
        "dropbox-file-progress",
        t.peerDeviceId,
        Json.obj(
          "transferId" -> transferId.asJson,
          "msgId" -> t.msgId.asJson,
          "chunkIndex" -> chunkIndex.asJson,
          "bytesReceived" -> received.asJson,
          "totalBytes" -> t.totalBytes.asJson
        )
      )

  /**
   * **在飞**字节进展（xferb 批 · P0-4，节拍 2 s）。
   *
   * 🔴 契约纪律（照方案卡 P0-4）：`bytesReceived` 的既有语义 = **接收端已确认**字节，
   * 在飞值**必须另立字段** `bytesInFlight` —— 混进 `bytesReceived` 会复活「进度 100%
   * 但文件没落地」的假进度。
   *
   * 本回调**只发事件**：不动会话记录的 `bytesReceived`、也不动 `lastProgressAt`
   * （在飞 ≠ 已确认 ⇒ 不得成为看门狗「有进展」的证据）。
   */
  private def recordInFlightProgress(
    transferId: String,
    t: FileTransfer,
    ackedBytes: Long,
    inFlightBytes: Long
  ): IO[Unit] =
    if t.totalBytes <= 0L then IO.unit
    else
      notifyFrontend(
        "dropbox-file-progress",
        t.peerDeviceId,
        Json.obj(
          "transferId" -> transferId.asJson,
          "msgId" -> t.msgId.asJson,
          "bytesReceived" -> ackedBytes.asJson,
          "bytesInFlight" -> inFlightBytes.asJson,
          "totalBytes" -> t.totalBytes.asJson,
          "inFlight" -> true.asJson
        )
      ).handleErrorWith(e => logger.warn(s"in-flight progress for $transferId could not be delivered: ${e.getMessage}"))

  /** 传输成功收口（唯一一处把 outbound 置 completed）。 */
  private def completeTransfer(transferId: String, t: FileTransfer, receiverHash: String): IO[Either[String, Unit]] =
    for
      _ <- transfersRef.update(m =>
        m.get(transferId) match
          case Some(cur) => m + (transferId -> cur.copy(status = "completed", receiverHash = receiverHash))
          case None => m
      )
      _ <- updateMessageStatus(t.peerDeviceId, t.msgId, "completed")
      delivered <- sendDataOrRelay(
        t.peerDeviceId,
        "dropbox",
        Json.obj(
          "kind" -> "file-complete".asJson,
          "transferId" -> transferId.asJson,
          "msgId" -> t.msgId.asJson,
          "success" -> true.asJson,
          "sha256" -> receiverHash.asJson // 接收端**自算**的整件摘要（禁自证）
        )
      )
      _ <-
        if delivered then IO.unit
        else
          // I3：今天的 `logger.warn` 静默（`DropboxService:322`）不再单独成立 ——
          // 带重试的确认仍失败 ⇒ 显式 failed，不再靠 31 min 看门狗迟可见。
          logger.warn(s"file-complete could not be delivered to ${t.peerDeviceId} — marking failed explicitly") *>
            markTransferFailed(transferId, t.peerDeviceId, t.msgId, "file-complete could not be delivered to peer")
      _ <- notifyFrontend(
        "dropbox-file-complete",
        t.peerDeviceId,
        Json.obj("transferId" -> transferId.asJson, "msgId" -> t.msgId.asJson, "success" -> true.asJson)
      )
      _ <- persistTransfers
    yield Right(())

  /**
   * 接收端：收对端推来的字节。
   *
   * **两条路径并存**（兼容矩阵 §3.9）：
   *   - 分块模式（请求带 `X-Dropbox-Proto: 1`）：走 `receiveChunk`（按 offset 追加、
   *     幂等重放、gap 拒绝、末块整件摘要自算比对）；
   *   - **整件 legacy 模式（无该头）**：行为与今天逐字节一致 —— 保留全量默认语义，
   *     旧发送端不受影响。
   */
  def receiveFromPeer(
    transferId: String,
    body: Stream[IO, Byte],
    chunk: Option[ChunkHeaders] = None
  ): IO[Either[AttachContract.AttachError, String]] =
    chunk match
      case Some(headers) =>
        // 回执字符串保留给「只要一个摘要」的调用面；分块回执的完整形态（接收端自算的
        // 块摘要 + 权威 offset）走 `receiveChunkFromPeer`（R4）。
        receiveChunkFromPeer(transferId, body, headers)
          .map(_.map(ack => ack.wholeSha256.getOrElse(ack.chunkSha256)))
      case None => receiveLegacyWholeFile(transferId, body)

  /** 分块头（由 `RestApiRoutes` 从 HTTP 头解析；relay 腿由 `FileTransferAction` 解析）。 */

  /**
   * P2P 分块：请求 body = **裸块字节**（不 base64）。先查会话、再走 `ChunkReceiver`
   * （内部先验块摘要再落盘）。返回**接收端自算**的块回执（R4：不是请求头回显）。
   */
  def receiveChunkFromPeer(
    transferId: String,
    body: Stream[IO, Byte],
    headers: ChunkHeaders
  ): IO[Either[AttachContract.AttachError, ChunkedTransfer.ChunkAck]] =
    val frame = ChunkedTransfer.ChunkFrame(
      transferId = transferId,
      chunkIndex = headers.chunkIndex,
      offset = AttachContract.offsetForIndex(headers.chunkIndex, headers.chunkSize),
      totalBytes = headers.totalBytes,
      chunkSize = headers.chunkSize,
      bytes = math
        .min(
          headers.chunkSize.toLong,
          headers.totalBytes - AttachContract.offsetForIndex(headers.chunkIndex, headers.chunkSize)
        )
        .toInt,
      chunkSha256 = headers.chunkSha256,
      wholeSha256 = headers.wholeSha256
    )
    for
      transferOpt <- ensureTransfer(transferId, "in")
      res <- transferOpt match
        case None =>
          IO.pure(
            Left(
              AttachContract.AttachError(
                AttachContract.Codes.SessionNotFound,
                s"No transfer session $transferId",
                phase = "transfer"
              )
            )
          )
        case Some(t) if t.direction != "in" =>
          IO.pure(
            Left(
              AttachContract.AttachError(
                AttachContract.Codes.SessionNotFound,
                s"Transfer $transferId is not inbound",
                phase = "transfer"
              )
            )
          )
        case Some(t) if t.status == "rejected" =>
          // 契约升版批：被拒会话（`targetDir` 裁定不通过）**不得**再接受任何字节 ——
          // 否则「拒 + 零副作用」（spec §3.1 / §3.4）会被一次事后推块绕过（建目录、写 temp）。
          // 与 legacy 整件路径的 `status != "accepted"` 守卫同口径（`receiveLegacyWholeFile`）。
          IO.pure(
            Left(
              AttachContract.AttachError(
                AttachContract.Codes.SessionNotFound,
                s"Transfer $transferId was refused (${t.targetDirCode.getOrElse("rejected")}) — refusing chunk; no directory created, no file written",
                phase = "transfer"
              )
            )
          )
        case Some(t) =>
          for
            tempPath <- receiverTempPath(t)
            receiver = new ChunkReceiver(transferId, headers.totalBytes, headers.chunkSize, tempPath)
            _ <- IO.blocking(os.makeDir.all(tempPath / os.up))
            // 权威 offset 一律从磁盘重建（不信内存/持久化字段）。
            _ <- receiver.prime()
            payload <- body.compile.to(Array)
            applied <- receiver.applyChunk(frame, payload)
            _ <- applied match
              case Left(err) =>
                if err.code == AttachContract.Codes.WholeDigestMismatch then
                  // 整件不符 ⇒ 立即删 temp（绝不 commit）+ 显式失败。
                  logger.warn(s"Dropbox transfer $transferId: ${err.render} — temp deleted, NOT committed") *>
                    IO.blocking(if os.exists(tempPath) then os.remove(tempPath) else ())
                else IO.unit
              case Right(ack) =>
                transfersRef.update(m =>
                  m.get(transferId) match
                    case Some(cur) =>
                      m + (transferId -> cur.copy(
                        bytesReceived = ack.bytesReceived,
                        tempPath = Some(tempPath.toString),
                        receiverHash = ack.wholeSha256.getOrElse(cur.receiverHash)
                      ))
                    case None => m
                )
            _ <- persistTransfersThrottled
          yield applied
    yield res

    end for

  end receiveChunkFromPeer

  /**
   * 续传探针：返回接收端**权威** offset 与其**重算**的前缀摘要。
   * 会话不存在 ⇒ 显式 `SESSION_NOT_FOUND`（不静默返回 0 —— 那会让发送端从头重传）。
   */
  def probeTransfer(transferId: String): IO[Either[AttachContract.AttachError, ChunkedTransfer.ReceiveState]] =
    ensureTransfer(transferId, "in").flatMap {
      case None =>
        IO.pure(
          Left(
            AttachContract.AttachError(
              AttachContract.Codes.SessionNotFound,
              s"No transfer session $transferId",
              phase = "transfer"
            )
          )
        )
      case Some(t) =>
        val chunkSize = if t.chunkSize > 0 then t.chunkSize else AttachContract.ChunkSize
        val total = if t.totalBytes > 0 then t.totalBytes else t.fileSize
        for
          tempPath <- receiverTempPath(t)
          state <- new ChunkReceiver(transferId, total, chunkSize, tempPath).prime()
          _ <- transfersRef.update(m =>
            m.get(transferId) match
              case Some(cur) =>
                m + (transferId -> cur.copy(bytesReceived = state.bytesReceived, tempPath = Some(tempPath.toString)))
              case None => m
          )
        yield Right(state)
    }

  /** legacy 整件接收（proto 缺失）：与今天逐字节一致，**行为零改动**。 */
  private def receiveLegacyWholeFile(
    transferId: String,
    body: Stream[IO, Byte]
  ): IO[Either[AttachContract.AttachError, String]] =
    transfersRef.get.map(_.get(transferId)).flatMap {
      case None =>
        IO.pure(
          Left(
            AttachContract.AttachError(AttachContract.Codes.SessionNotFound, "Transfer not found", phase = "transfer")
          )
        )
      case Some(t) if t.direction != "in" =>
        IO.pure(
          Left(
            AttachContract
              .AttachError(AttachContract.Codes.SessionNotFound, "Not an incoming transfer", phase = "transfer")
          )
        )
      case Some(t) if t.status != "accepted" =>
        IO.pure(
          Left(
            AttachContract
              .AttachError(AttachContract.Codes.SessionNotFound, "Transfer not accepted", phase = "transfer")
          )
        )
      case Some(t) =>
        val dlDir = DropboxUtil.downloadsDir
        // 同上：temp 名 = 唯一生成器（禁本文件内第二处拼串）。
        val tempName = DropboxUtil.receiverTempName(t.fileName, transferId)
        val tempPath = dlDir / tempName
        for
          _ <- IO.blocking(os.makeDir.all(dlDir))
          hash <- streamToFileWithHash(body, tempPath)
          _ <- transfersRef.update(_ + (transferId -> t.copy(tempPath = Some(tempPath.toString), receiverHash = hash)))
        yield Right(hash)
    }

  /**
   * 生产传输腿：P2P 主腿 + relay 兜底（两腿共面）。无 relay 客户端 ⇒ 仅 P2P。
   *
   * xferb 批（P0-1）：单块死线由**同一速率读数**与本端块大小派生（`rateHint <= 0` ⇒
   * `AttachContract.AssumedMinRateBytesPerSec`）。读数只影响本端超时算术，不上 wire。
   */
  private def productionTransport(t: FileTransfer, rateHintBytesPerSec: Long = 0L): ChunkTransport =
    val p2p = new P2PChunkTransport(t.peerAddress, AttachContract.P2PDeadlineCeiling, rateHintBytesPerSec)
    neblinkService.relayClientOpt match
      case Some(client) =>
        ChunkTransport.failover(
          p2p,
          new RelayChunkTransport(client, AttachContract.RelayDeadlineCeiling, rateHintBytesPerSec)
        )
      case None => p2p

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
      text = hc.downField("text").as[String].getOrElse(""),
      // 设备会话统一批 MVP-1：收端读 wire `origin`（未知/缺席 ⇒ user）。**只落库 + 交客户端
      // 作提示级渲染**，收端不据它下任何结论（设计卡 §9 P3）。
      origin = DropboxMessage.normalizeOrigin(hc.downField("origin").as[String].getOrElse(DropboxMessage.OriginUser))
    )
    for
      _ <- addMessage(senderId, msg)
      _ <- notifyFrontend("dropbox-message", senderId, msg.asJson)
    yield ()

  end handleIncomingText

  // --- Incoming file offer (auto-accept) ---
  private def handleIncomingOffer(payload: Json): IO[Unit] =
    val hc = payload.hcursor
    val senderId = hc.downField("senderId").as[String].getOrElse("")
    val transferId = hc.downField("transferId").as[String].getOrElse("")
    val msgId = hc.downField("msgId").as[String].getOrElse(DropboxModels.newId)
    val fileName = hc.downField("fileName").as[String].getOrElse("unknown")
    val fileSize = hc.downField("fileSize").as[Long].getOrElse(0L)
    val mimeType = hc.downField("mimeType").as[String].getOrElse("")
    // ===== 设备腿 targetDir（契约升版批）=====
    // 对端自报等级（JSON 面；缺失 = 0 = legacy）。头面 `X-Dropbox-Proto` 恒 1，不参与本判定。
    val peerProto = hc.downField("proto").as[Int].toOption.getOrElse(AttachContract.ProtoLegacy)
    val requestedTargetDir = hc.downField("targetDir").as[String].toOption
    // 🔴 判定链**只在此处执行一次**，结果固化进会话记录（`FileTransfer.targetDir`）；
    // 收块/commit 阶段不得重新解释字符串。必须早于任何 `os.makeDir` —— 收块阶段
    // （`receiveChunkFromPeer`）在建 temp 目录前不做任何校验，判定放这里才能保证
    // 「拒绝 ⇒ 零副作用」（spec §3.4）。
    val negotiated = AttachContract.negotiate(AttachContract.ProtoRelayTemp, peerProto)
    val (landingDir, targetDirCode): (Option[String], Option[String]) =
      requestedTargetDir match
        case None => (None, None) // 缺省语义：与今天逐字节一致（不落任何新字段）
        case Some(raw) if negotiated >= AttachContract.ProtoAssignDir =>
          TargetDirGuard.resolveFor(raw) match
            case Right(p) => (Some(p.toString), None)
            case Left(err) => (None, Some(err.code))
        case Some(_) =>
          // 等级 < 2 的发送端不应发该键（§4.2 候选 1）；收到即显式拒（禁把它当缺省静默吞掉）。
          (None, Some(AttachContract.Codes.TargetDirInvalid))
    val refused = targetDirCode.isDefined
    val status = if refused then "rejected" else "accepted"
    val msg = DropboxMessage(
      msgId = msgId,
      direction = "in",
      kind = "file",
      ts = DropboxModels.now,
      transferId = transferId,
      fileName = fileName,
      fileSize = fileSize,
      mimeType = mimeType,
      status = status,
      // 设备会话统一批 MVP-1：文件消息同轴带来源标记（同 handleIncomingText 的收端纪律）。
      origin = DropboxMessage.normalizeOrigin(
        hc.downField("origin").as[String].getOrElse(DropboxMessage.OriginUser)
      )
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
      status = status,
      proto = negotiated,
      peerProto = Some(peerProto),
      targetDir = landingDir,
      targetDirCode = targetDirCode
    )
    // Auto-accept: immediately notify sender to start uploading
    // （拒绝 ⇒ accepted=false + 结构化拒码：发送端据此显式回显，不静默降级）
    val acceptPayload = Json
      .obj(
        "kind" -> "file-response".asJson,
        "transferId" -> transferId.asJson,
        "accepted" -> (!refused).asJson,
        // 接收端等级自报（§1.4「等级自报通道」）：旧发送端忽略未知键，新发送端据此
        // 决定是否可发 targetDir。
        "proto" -> AttachContract.ProtoRelayTemp.asJson,
        "targetDirAccepted" -> (!refused).asJson
      )
      .deepMerge(
        targetDirCode.fold(Json.obj())(c => Json.obj("targetDirCode" -> c.asJson))
      )
      .deepMerge(
        landingDir.fold(Json.obj())(d => Json.obj("targetDir" -> d.asJson))
      )
    for
      _ <- transfersRef.update(_ + (transferId -> transfer))
      _ <- addMessage(senderId, msg)
      _ <- notifyFrontend("dropbox-message", senderId, msg.asJson)
      delivered <- sendDataOrRelay(senderId, "dropbox", acceptPayload)
      _ <-
        if !delivered then
          markTransferFailed(transferId, senderId, msgId, "auto-accept response could not be delivered")
        else if refused then
          // 拒绝：**绝不** arm accepted-timeout（没有任何字节会来）；通知前端使拒绝可见。
          logger.warn(
            s"Dropbox transfer $transferId refused: targetDir rejected with ${targetDirCode.getOrElse("")} — no directory created, no file written"
          ) *> notifyFrontend(
            "dropbox-file-complete",
            senderId,
            Json.obj(
              "transferId" -> transferId.asJson,
              "msgId" -> msgId.asJson,
              "success" -> false.asJson,
              "error" -> targetDirCode.getOrElse("").asJson
            )
          )
        else
          // Auto-accept sent (arm accepted-timeout: this side waits for the
          // sender to push the bytes; an abandoned upload fails rather than
          // pinging「传输中…」).
          armTransferTimeout(transferId, senderId, msgId, acceptedTimeout, Set("accepted"))
    yield ()
    end for

  end handleIncomingOffer

  // --- File response (receiver accepted/rejected our offer) ---
  private def handleFileResponse(payload: Json): IO[Unit] =
    val hc = payload.hcursor
    val transferId = hc.downField("transferId").as[String].getOrElse("")
    val accepted = hc.downField("accepted").as[Boolean].getOrElse(false)
    // 对端等级自报（JSON 面）：旧端不回带 ⇒ None ⇒ 一律按等级 1 处理（§4.2 候选 1）。
    val peerProto = hc.downField("proto").as[Int].toOption
    val targetDirCode = hc.downField("targetDirCode").as[String].toOption
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
            _ <- transfersRef.update(
              _ + (transferId -> t.copy(status = newStatus, peerProto = peerProto, targetDirCode = targetDirCode))
            )
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
          end for
        case None =>
          logger.warn(s"file-response for unknown transfer $transferId — no persisted message to rebuild from")
          IO.unit
    yield ()
    end for

  end handleFileResponse

  // --- File complete (sender tells us transfer result) ---
  private def handleFileComplete(payload: Json): IO[Unit] =
    val hc = payload.hcursor
    val transferId = hc.downField("transferId").as[String].getOrElse("")
    val success = hc.downField("success").as[Boolean].getOrElse(false)
    // ===== 可续失败（xferb 批 · P0-2）=====
    // 发送端随帧声明「本次失败**可续**」（同号重试会接着传）。此时**不得**删本端半成品
    // temp —— 删了就等于把已经付过带宽的字节扔掉（改前 P-11：失败即删 ⇒ 续传物理不可能）。
    // 旧发送端不带该键 ⇒ 缺省 false ⇒ 行为与今天逐字节一致（零回归）。
    val resumable = hc.downField("resumable").as[Boolean].getOrElse(false)
    val errorCode = hc.downField("errorCode").as[String].getOrElse("")
    val errorDetail = hc.downField("errorDetail").as[Json].getOrElse(Json.Null)
    val keepTemp = !success && resumable
    for
      // R5: a file-complete arriving after the receiving process restarted has
      // no in-memory transfer. Rebuild from the persisted message (and scan the
      // Downloads dir for the leftover temp file) so the UI can move to a
      // terminal state instead of dropping the completion.
      transfer <- ensureTransfer(transferId, "in")
      _ <- transfer match
        case Some(t) =>
          for
            // P0 wtmove: explicit branch — a completion whose temp file was never
            // recorded or never found is NOT a failure (relay direct delivery is a
            // normal completion path) and must never fall back to a blank path.
            // `Absent` / `Refused` mean "nothing was moved or deleted"; the
            // transfer still terminates below with its usual status. The WARN
            // naming the exact operation is emitted once, by commit/delete
            // (see `warnTempPath`) — no second copy of the predicate, no
            // duplicate log line for the same event.
            //
            // 🆕 可续失败是**第三格**：既不是「落地」（不 commit）也不是「清理」（不删）——
            // 算作 `Absent`（本次无任何 move/delete 动作）并显式留痕。
            _ <-
              if keepTemp then
                logger.info(
                  s"Dropbox transfer $transferId failed but is resumable ($errorCode) — the partial temp is kept " +
                    s"so a same-id retry can resume from it (no move, no delete)"
                )
              else IO.unit
            outcome <-
              if success then commitTempFile(t)
              else if keepTemp then IO.pure(CommitOutcome(TempPathDecision.Absent, None))
              else deleteTempFile(t)
            savedPath <-
              // 通报/引用面路径 = **已观测到的落地名**（nfpath 批口径：禁预计算名）。
              //
              // 修复前这里在落地之后**重算** `DropboxUtil.resolveFinalPath`，与 `commitTempFile`
              // 里 move 到的实际目标不同源 ⇒ 报出一个盘上不存在的名，按通报路径读取必
              // file-not-found（用户表现「文件不存在或被清理」）。实测对：
              // 通报 `~/Downloads/call_00_…_20260916_182050.txt` vs 磁盘
              // `~/Downloads/call_00_….txt`（120079 B，mtime 2026-09-16 18:20:50 —— 后缀
              // 时间戳与落地 mtime **同一秒**：重算的 `os.exists` 被本次 move 自己改变）。
              if success then landedPathFor(t, outcome) else IO.pure("")
            _ <- updateTransferStatus(transferId, if success then "completed" else "failed")
            _ <- updateMessageStatus(
              t.peerDeviceId,
              t.msgId,
              if success then "completed" else "failed",
              savedPath,
              errorCode = errorCode,
              errorDetail = if errorDetail.isNull then "" else errorDetail.noSpaces
            )
            _ <- notifyFrontend(
              "dropbox-file-complete",
              t.peerDeviceId,
              Json.obj(
                "transferId" -> transferId.asJson,
                "msgId" -> t.msgId.asJson,
                "success" -> success.asJson,
                "savedPath" -> savedPath.asJson,
                "errorCode" -> errorCode.asJson,
                "errorDetail" -> errorDetail,
                "resumable" -> resumable.asJson,
                "tempKept" -> keepTemp.asJson
              )
            )
          yield ()
        case None =>
          logger.warn(s"file-complete for unknown transfer $transferId — no persisted message to rebuild from")
          IO.unit
    yield ()
    end for
  end handleFileComplete

  // ===== Helpers =====

  private def addMessage(deviceId: String, msg: DropboxMessage): IO[Unit] =
    messagesRef.update(m => m.updated(deviceId, m.getOrElse(deviceId, Nil) :+ msg))
      *> persistMessages

  /** 更新一条消息的状态（可选带结构化失败原因，P0-3）。原因为空串 ⇒ 不改写既有原因字段。 */
  private def updateMessageStatus(
    deviceId: String,
    msgId: String,
    status: String,
    savedPath: String = "",
    errorCode: String = "",
    errorDetail: String = ""
  ): IO[Unit] =
    messagesRef.update { m =>
      m.updated(
        deviceId,
        m.getOrElse(deviceId, Nil).map { msg =>
          if msg.msgId == msgId then
            msg.copy(
              status = status,
              savedPath = savedPath,
              errorCode = if errorCode.nonEmpty then errorCode else msg.errorCode,
              errorDetail = if errorDetail.nonEmpty then errorDetail else msg.errorDetail
            )
          else msg
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
      msgs.toList
        .collectFirst {
          case (deviceId, list) if list.exists(m => m.kind == "file" && m.transferId == transferId) =>
            (deviceId, list.find(m => m.kind == "file" && m.transferId == transferId).get)
        }
        .map { case (deviceId, m) =>
          // P0 wtmove: `None` = no leftover temp file — an explicit state.
          // (This used to be `getOrElse("")`, and the blank string then resolved to
          // `os.pwd` in commitTempFile, moving the working directory.)
          val tempPath =
            if direction == "in" then findReceiverTempFile(m.fileName, transferId)
            else None
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

  /**
   * Scan the Downloads dir for a leftover `.fileName.dropbox-XXXX` temp file (restart recovery).
   *
   * P0 wtmove: returns `Option` — `None` means "no leftover temp file", which is a
   * real answer, not a blank path. The old `getOrElse("")` / `else ""` produced an
   * empty string that downstream resolved to `os.pwd`.
   */
  private def findReceiverTempFile(fileName: String, transferId: String): Option[String] =
    try
      // 名字仍走**唯一生成器**（机械判据 G3：本文件内不得再有第三处 `.dropbox-` 拼接）。
      val prefix = DropboxUtil.receiverTempName(fileName, transferId)
      val dlDir = DropboxUtil.downloadsDir
      if os.exists(dlDir) then os.list(dlDir).find(_.last.startsWith(prefix)).map(_.toString)
      else None
    catch case _: Exception => None

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
   * **legacy 整件 P2P 推送**（保留通道能力，兼容矩阵 §3.9「旧发送 × 新接收」格）。
   *
   * 今天这条腿是 `uploadAndRelay` 的唯一发送路径；附件腿批起，生产发送改走
   * `ChunkTransport` 分块两腿（P2P + relay），本方法**降级为兼容面**：
   *   - 它 POST 的是同一个端点 `/api/neblink/dropbox/transfer/<tid>`，只是**不带分块头**
   *     ⇒ 对端走 `receiveLegacyWholeFile`（整件流式），行为与今天逐字节一致；
   *   - 保留它 = 「退的是工具/调用形态，不是通道能力」—— 端点与接收侧整件路径都不删。
   */
  private[nebflow] def p2pSendWholeFileToPeer(
    peerAddress: String,
    transferId: String,
    tempPath: os.Path
  ): IO[Either[String, String]] =
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
   * relay 腿的**整件**发送已删除。
   *
   * WHY 删：它的 `case Right(_) => Right(hash) // same content — hash trivially matches`
   * 使 `matchResult` **恒真** —— 校验自证（不变量 I2 直指的既有缺陷）。
   * 任何「内容相同所以摘要必然相等」的推论都是循环论证：它把「校验」退化成了「断言」。
   *
   * relay 腿的**通道能力没有消失**，而是**收紧语义**后搬到 `RelayChunkTransport`
   * （逐块 + 接收端自算摘要回传 + 按调用传超时）；
   * `NeblinkClient.relayTransferPut` / `relayTransferGet` / `FileTransferAction` /
   * `/api/neblink/transfer` / `NeblinkService.receiveFile|sendFile` **全部保留**。
   */

  // ===== Temp-path guard (P0 wtmove) =====
  //
  // `commitTempFile` used to write `os.Path(t.tempPath, os.pwd)`. With a blank
  // tempPath that expression IS `os.pwd`, so a file-complete whose temp file was
  // never recorded (relay direct delivery) or never found (restart rebuild)
  // unconditionally renamed the JVM's working directory into
  // `~/Downloads/<fileName>` — an entire running worktree relocated out from
  // under a live JVM (21 recorded cases, 2026-09-10..12).
  //
  // One authoritative predicate (`cwdRefusal`) + one authoritative entry point
  // (`guardedTempPath`), shared by commit / delete / complete. Nothing else in
  // this file may resolve a transfer temp path.

  /**
   * The single authoritative "may we touch this path?" predicate.
   *
   * `None` = safe. `Some(reason)` = refused: the path is the process working
   * directory, an ancestor of it, or inside it. Renaming/deleting any of those
   * reaches outside the transfer's own scope — moving the working directory of a
   * *running* JVM is exactly the P0 defect. Paths are compared absolute +
   * normalized so `..` segments cannot slip an equivalent path past the check.
   */
  private def cwdRefusal(p: os.Path): Option[String] =
    val cwd = os.pwd.toNIO.toAbsolutePath.normalize
    val target = p.toNIO.toAbsolutePath.normalize
    if target == cwd then Some(s"path IS the process working directory ($cwd)")
    else if cwd.startsWith(target) then Some(s"path is an ancestor of the process working directory ($target ⊃ $cwd)")
    else if target.startsWith(cwd) then Some(s"path is inside the process working directory ($target ⊂ $cwd)")
    else None

  /**
   * The single authoritative resolution of a transfer's temp path, shared by
   * [[commitTempFile]], [[deleteTempFile]] and [[handleFileComplete]].
   *
   * Replaces `os.Path(t.tempPath, os.pwd)` plus the old blank sentinel. Pure (no
   * I/O, no logging) — the callers log the returned decision, once, with the
   * operation name. Only `Usable` may ever reach the filesystem.
   */
  private def guardedTempPath(t: FileTransfer): TempPathDecision =
    t.tempPath.map(_.trim).filter(_.nonEmpty) match
      case None => TempPathDecision.Absent
      case Some(raw) =>
        try
          val p = os.Path(raw, os.pwd)
          cwdRefusal(p) match
            case Some(reason) => TempPathDecision.Refused(s"source: $reason")
            case None => TempPathDecision.Usable(p)
        catch
          case e: Exception =>
            // Pre-fix this throw happened inside `IO.blocking` and was swallowed by
            // `handleErrorWith`; keep the same "no-op, never throw" contract, but
            // now as an explicit refused decision.
            TempPathDecision.Refused(
              s"source: unresolvable path (${e.getClass.getSimpleName}: ${e.getMessage})"
            )

  /**
   * WARN for a temp path that was *not* usable. Carries `op` / `transferId` /
   * `direction` so a post-mortem can attribute the event, and states explicitly
   * that no filesystem change happened.
   */
  private def warnTempPath(op: String, t: FileTransfer, decision: TempPathDecision): IO[Unit] =
    decision match
      case TempPathDecision.Usable(_) => IO.unit
      case TempPathDecision.Absent =>
        logger.warn(
          s"$op: no temp file recorded for transfer ${t.transferId} (direction=${t.direction} fileName=${t.fileName}) " +
            "— nothing moved/deleted; normal for relay direct delivery, where the sender wrote straight into Downloads"
        )
      case TempPathDecision.Refused(reason) =>
        logger.warn(
          s"$op: REFUSED for transfer ${t.transferId} (direction=${t.direction}) — $reason; no filesystem change"
        )

  /**
   * Rename the temp file to its final name in the landing dir, handling name conflicts.
   *
   * dropnam 批：落名收口到 **唯一一处** —— [[DropboxUtil.reserveAndPlace]]。
   *   - 判定与占名是**同一个内核原子操作**（`link(2)`，EEXIST ⇒ 换下一候选）⇒ 同秒多次同名
   *     投递**在构造上**不可能折叠（改前：`os.exists` 预判 + 秒级时间戳 + 候选不复核 +
   *     `os.move(..., replaceExisting = true)` ⇒ 同秒 6 份只剩 2 份）；
   *   - `replaceExisting` 在任何落盘路径上都**只作用于本次刚占据/刚占位的那个名字**
   *     （硬链接路径上根本不出现）⇒ 既有件零覆盖、零删除；
   *   - 通报名 = 本次**占据**的名字（回读确认），禁第二处名字推断。
   *
   * `Absent`（relay 腿：字节不经 `receiveChunkFromPeer`，故 tempPath 记录恒 `None`）⇒ 回落
   * [[relayTempPathOnReceiver]] 的确定性派生 temp，使 relay 字节**找得到** commit 入口。
   */
  private def commitTempFile(t: FileTransfer): IO[CommitOutcome] =
    val decision = guardedTempPath(t)
    warnTempPath("commitTempFile", t, decision) *> (decision match
      case TempPathDecision.Usable(tempPath) =>
        // `Usable` 保持今天的守卫口径（只判存在）—— 0 字节整件传照旧可 commit。
        placeTemp(t, decision, tempPath, requireNonEmpty = false)
      case refused @ TempPathDecision.Refused(_) => IO.pure(CommitOutcome(refused, None))
      case absent @ TempPathDecision.Absent =>
        relayTempPathOnReceiver(t).flatMap {
          case None => IO.pure(CommitOutcome(absent, None))
          case Some(derived) =>
            // 只有派生 temp **确实存在且非空**时才回落 commit；否则与今天逐字节一致
            // （迟到完成帧、旧发送端裸名直写两条路径都不受影响）。
            placeTemp(t, absent, derived, requireNonEmpty = true)
        })

  end commitTempFile

  /** 「把 temp 落到唯一名字上」—— [[commitTempFile]] 的两个入口共用同一实现（单一落盘点）。 */
  private def placeTemp(
    t: FileTransfer,
    decision: TempPathDecision,
    tempPath: os.Path,
    requireNonEmpty: Boolean
  ): IO[CommitOutcome] =
    IO.blocking(os.exists(tempPath) && (!requireNonEmpty || (os.isFile(tempPath) && os.size(tempPath) > 0))).flatMap {
      case false => IO.pure(CommitOutcome(decision, None))
      case true =>
        val landDir = DropboxService.landingDirFor(t)
        cwdRefusal(landDir) match
          case Some(reason) =>
            val refused = TempPathDecision.Refused(s"destination: $reason")
            warnTempPath("commitTempFile", t, refused).as(CommitOutcome(refused, None))
          case None =>
            DropboxUtil.reserveAndPlace(landDir, t.fileName, tempPath, landingClock()).flatMap {
              case Left(reason) =>
                logger.warn(s"commitTempFile: $reason — nothing moved, nothing overwritten") *>
                  IO.pure(CommitOutcome(TempPathDecision.Refused(reason), None))
              case Right(reserved) =>
                // 落地后**回读**：占据未留住目标（异常/竞态）时 `landedPath` 必须为 None，
                // 禁把「以为占据成功」当成「真落地」。
                IO.blocking(if os.exists(reserved) then Some(reserved) else None)
                  .map(lp => CommitOutcome(decision, lp))
            }
        end match
    }

  /**
   * relay 腿（新协议）字节落点在本端的解释——与发送端 [[DropboxUtil.relayLandingPath]]
   * 是**同一次观测的两侧**（名字只由 `DropboxUtil.receiverTempName` 一处生成）：
   *
   *   - `targetDir` 获接受 ⇒ 与 P2P 腿**同一派生**（[[derivedReceiverTempPath]]）；
   *   - 缺省落点 ⇒ 发送端写的是 `~/Downloads/<temp>`（`RelayDefaultDirTilde` 字面形态），
   *     在此**按本端 home 展开**。⚠️ `DropboxUtil.downloadsDir` 在 Linux `XDG_DOWNLOAD_DIR`
   *     下可能 ≠ `$HOME/Downloads` ⇒ 两个候选都看（否则新×新格在该平台会静默停摆）。
   */
  private def relayTempPathOnReceiver(t: FileTransfer): IO[Option[os.Path]] =
    val name = DropboxUtil.receiverTempName(t.fileName, t.transferId)
    val assigned = derivedReceiverTempPath(t)
    val defaultForm =
      os.Path(PathUtil.expandTilde(s"${DropboxUtil.RelayDefaultDirTilde}/$name"), os.pwd)
    val candidates =
      if t.targetDir.exists(_.trim.nonEmpty) then List(assigned)
      else List(assigned, defaultForm).distinct
    IO.blocking(candidates.find(p => os.exists(p) && os.isFile(p) && os.size(p) > 0))

  /** Delete the temp file. Same guard as [[commitTempFile]] — a refused/blank path deletes nothing. */
  private def deleteTempFile(t: FileTransfer): IO[CommitOutcome] =
    val decision = guardedTempPath(t)
    warnTempPath("deleteTempFile", t, decision) *> (decision match
      case TempPathDecision.Usable(tempPath) =>
        IO.blocking(if os.exists(tempPath) then os.remove(tempPath))
          .handleErrorWith(_ => IO.unit)
          .as(CommitOutcome(decision, None))
      case refused @ TempPathDecision.Refused(_) => IO.pure(CommitOutcome(refused, None))
      case absent @ TempPathDecision.Absent => IO.pure(CommitOutcome(absent, None)))

  /**
   * 通报/引用面路径 —— **已观测到的**落地名，禁预计算名（nfpath 批口径，dropnam 批收口）。
   *
   * 分支（按「本次是否真的落地」分，不按猜测分）：
   *   1. 本次搬动过 temp ⇒ 用搬动的实际目标（存在性以回读为准）；
   *   2. 本次没有任何 move（迟到/重放的完成帧、temp 已不在、目的地被拒）
   *      ⇒ 用**已经记录**的回读值（首次落地时观测到的那个），禁拿重算值去覆盖它；
   *   3. 连记录都没有 ⇒ 如实为空（`""` = 本机没有可读件，前端据此保持不可点）。
   *
   * 🔴 原分支 3（**按落点目录 + 裸名预测**一个「应该存在」的路径）已**删除**（作者 2026-09-19
   * 裁定③，判据 = **分支已删**）：它是**第二处算名点**（预测而非观测），与三契约「最终名字
   * 只在一处算」冲突。该分支存在的唯一理由是 relay 腿直写裸名 —— 那条腿已由 `relayLandingPath`
   * （确定性 temp 名）+ `commitTempFile` 的 `Absent` 回落收口 ⇒ 每次落地都**必经**唯一落盘点
   * ⇒ 预测不再需要。**任何路径**（含旧对端）都不得回退到预测裸名。
   */
  private def landedPathFor(t: FileTransfer, outcome: CommitOutcome): IO[String] =
    outcome.landedPath match
      case Some(p) => IO.blocking(if os.exists(p) then p.toString else "")
      case None =>
        recordedSavedPath(t).map {
          case existing if existing.nonEmpty => existing
          case _ => ""
        }

  /** 该会话消息上**已经记录**的落地路径（空串 = 尚未观测到任何落点）。 */
  private def recordedSavedPath(t: FileTransfer): IO[String] =
    messagesRef.get.map { m =>
      m.getOrElse(t.peerDeviceId, Nil).find(_.msgId == t.msgId).map(_.savedPath).getOrElse("")
    }

end DropboxService

object DropboxService:

  /**
   * 单条消息里的一件附件（名字 / 字节数 / MIME + **发送端本机真实路径**）。
   *
   *  `outPath`（selfattach 批 · B′ 腿）：**唯一**生产者 = [[DropboxService.sendLocalFiles]]
   *  （工具 / agent 腿）——它把**已过绝对 / 存在 / 非目录三道校验**的 `p` 原样带进来；
   *  其余构造点（`WebSocketRoutes` 的浏览器 `dropbox-file-offer`、`offerFile` 单件入口、
   *  测试）一律取缺省 `None` ⇒ 浏览器 user 腿**不可能**经此写入路径
   *  （缺省值 = 「无本机路径可记」，不是「记空串」）。
   */
  final case class FileSpec(fileName: String, fileSize: Long, mimeType: String, outPath: Option[String] = None)

  /**
   * 工具附件腿（`sendLocalFiles`）的单件终局读数：`delivered=false` 时 `error`
   * 必带原因（禁静默）。
   */
  final case class LocalFileOutcome(
    fileName: String,
    fileSize: Long,
    transferId: String,
    delivered: Boolean,
    error: Option[String],
    /** 本次请求的 `targetDir`（NFC 形态）；`None` = 未请求（缺省语义）。 */
    targetDir: Option[String] = None,
    /**
     * 🔴 §4.2 候选 1：请求了 `targetDir` 但**对端等级未确认**（`file-response` 未回带
     * `proto >= 2`）⇒ 该字段**未上 wire**，落点 = 对端缺省目录。调用方（工具面）
     * **必须显式回显**（禁静默降级，spec §4.1）。
     */
    targetDirDeferred: Boolean = false
  )

  /**
   * 分块头（`RestApiRoutes` 从 HTTP 头解析；`FileTransferAction` 从 relay params 解析）。
   * `bytes` 由 `totalBytes − offset` 推导，**不由发送端直传**。
   */
  final case class ChunkHeaders(
    chunkIndex: Int,
    totalBytes: Long,
    chunkSize: Int,
    chunkSha256: String,
    wholeSha256: String
  )

  /**
   * 会话落点目录（契约升版批：设备腿 `targetDir`）—— **接收端落点的唯一解释点**。
   *
   * - `targetDir` 为空 / 不可解释 / 非绝对 ⇒ `DropboxUtil.downloadsDir`
   *   （缺省语义，与今天**逐字节一致**）；
   * - 否则 = `file-offer` 阶段由 [[TargetDirGuard]] 判定通过后固化进会话记录的
   *   **canonical 落点**（收块/commit 阶段**不得**重新解释字符串）。
   *
   * 纯函数、零 IO —— 除 test 外只被接收端落点派生（temp 名 / commit 目标 / 回显
   * `savedPath`）调用，三处共用本函数（单一实现点，禁第二份判据）。
   */
  private[dropbox] def landingDirFor(t: FileTransfer): os.Path =
    t.targetDir.map(_.trim).filter(_.nonEmpty) match
      case None => DropboxUtil.downloadsDir
      case Some(s) =>
        try if s.startsWith("/") then os.Path(java.nio.file.Paths.get(s)) else DropboxUtil.downloadsDir
        catch case _: Exception => DropboxUtil.downloadsDir

  def create(neblinkService: NeblinkService, wsHub: WsHub): IO[DropboxService] =
    val svc = new DropboxService(neblinkService, wsHub)
    svc.init.as(svc)

  /** Test factory with injectable signaling timeouts (+ injectable landing clock, dropnam A4). */
  private[nebflow] def createForTest(
    neblinkService: NeblinkService,
    wsHub: WsHub,
    offerTimeout: FiniteDuration,
    acceptedTimeout: FiniteDuration,
    transferTimeout: FiniteDuration,
    clock: () => ZonedDateTime = () => ZonedDateTime.now()
  ): IO[DropboxService] =
    val svc = new DropboxService(neblinkService, wsHub, offerTimeout, acceptedTimeout, transferTimeout, clock)
    svc.init.as(svc)
end DropboxService
