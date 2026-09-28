package nebflow.dropbox

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import nebflow.gateway.WsHub
import nebflow.neblink.{NeblinkService, PeerInfo}

import scala.concurrent.duration.*

/**
 * selfattach 批 · 片 1（B′ 腿）—— **发送端本机真实路径**入台账的运行期读数。
 *
 * 口径申报（**禁读成端到端**）：本 spec 走**真实发送链、真文件 IO、真分块、真 sha256、
 * 真台账落盘**；被替换的只有两处传输面 ——
 *   ① 对端 WS 数据通道（`NeblinkService.setSendDataFn` 捕获**发往对端**的载荷，不真过网；
 *      载荷**逐字**是网关手写的那份 JSON ⇒ 隐私面可判）；
 *   ② 分块传输腿（`transportOverride` = 进程内自环，喂真 `ChunkReceiver`，与
 *      `ChunkedRoundTripSpec` 同范式）。
 * 对端「已接受」帧按**真 wire 形态**注入（`NeblinkService.handleDataMessage`，与 WS data
 * channel 同一入口）。设备面 peers 名册为空 ⇒ **端到端不可判定**，结论一律标「未端到端」。
 *
 * 钉住四条（逐条对应任务书 §1 / §3）：
 *   ① **台账确有该字段**：真 `messages.json` 读出向行的 `deviceOutPath` = 发件前的真绝对路径；
 *   ② **隐私 · 对端帧零该键**：捕获的全部发往对端载荷内（递归）无 `deviceOutPath` 键 ——
 *      同一次捕获里**台账 JSON 必须被检出**（检测器鉴别力对照，防「扫不出 = 假绿」）；
 *   ③ **零字节复制 / 零留存**：数据根下无任何与源件同字节的文件，发送端 temp 目录不留件；
 *   ④ **本机首帧即可用**：`dropbox-message`（本机 WsHub 广播）帧内即带该路径；
 *      且浏览器腿（`offerFiles`，`outPath = None`）恒空串 ⇒ 该键**不可能**由前端注入。
 */
class SenderOutPathLedgerSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  // ===== 自环传输腿（与 ChunkedRoundTripSpec 同范式；接收端在拿到 transferId 后装配）=====

  private final class DeferredLoopback(holder: Ref[IO, Option[ChunkReceiver]]) extends ChunkTransport:
    def leg: String = "harness"

    private def receiver: IO[ChunkReceiver] =
      holder.get.map(_.getOrElse(throw new IllegalStateException("harness: receiver not installed")))

    def put(
      target: FileTransfer,
      frame: ChunkedTransfer.ChunkFrame,
      payload: Array[Byte]
    ): IO[Either[AttachContract.AttachError, ChunkedTransfer.ChunkAck]] =
      receiver.flatMap(_.applyChunk(frame, payload))

    def probe(target: FileTransfer): IO[Either[AttachContract.AttachError, ChunkedTransfer.ReceiveState]] =
      receiver.flatMap(_.prime().map(Right(_)))

  private def withSender[A](
    use: (NeblinkService, DropboxService, Ref[IO, List[Json]], Ref[IO, List[Json]], os.Path) => IO[A]
  ): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        prev       <- IO(PathUtil.dataRoot)
        root       <- IO.blocking(os.temp.dir(prefix = "nb-selfattach-spec-"))
        _          <- IO(PathUtil.setDataRoot(root))
        ms         <- NeblinkService.create(0, dispatcher)
        peerFrames <- Ref.of[IO, List[Json]](Nil)
        // 对端方向载荷捕获（**逐字** = 网关手写的那份 JSON；返回 true ⇒ 视为已投递）
        _          <- ms.setSendDataFn((_, _, p) => peerFrames.update(_ :+ p).as(true))
        hub        <- IO(new WsHub)
        localSeen  <- Ref.of[IO, List[Json]](Nil)
        reg        <- hub.register(j => localSeen.update(_ :+ j))
        _          <- ms.upsertPeer(PeerInfo("dev-2", "Dev2", "macos", "http://127.0.0.1:9"))
        svc        <- DropboxService.createForTest(ms, hub, 30.seconds, 30.seconds, 30.seconds)
        out        <- use(ms, svc, peerFrames, localSeen, root)
        _          <- hub.unregister(reg)
        _          <- IO(PathUtil.setDataRoot(prev))
        _          <- IO(os.remove.all(root))
      yield out
    }

  /** 轮询等「发往对端的载荷里出现某 kind 的帧」，取其中的 transferId。 */
  private def awaitFrameId(ref: Ref[IO, List[Json]], kind: String, timeout: FiniteDuration): IO[String] =
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def pick(frames: List[Json]): String =
      frames.reverse
        .find(f => f.hcursor.downField("kind").as[String].toOption.contains(kind))
        .flatMap(_.hcursor.downField("transferId").as[String].toOption)
        .getOrElse("")
    def poll: IO[String] =
      ref.get.map(pick).flatMap { id =>
        if id.nonEmpty then IO.pure(id)
        else if System.currentTimeMillis() > deadline then
          IO.raiseError(new AssertionError(s"harness: no '$kind' payload captured within ${timeout.toSeconds}s"))
        else IO.sleep(50.millis) *> poll
      }
    poll

  /** 递归键扫描（对端帧隐私面的唯一判据：任一深度出现该键即命中）。 */
  private def hasKeyDeep(j: Json, key: String): Boolean =
    j.asObject match
      case Some(o) => o.keys.exists(_ == key) || o.values.exists(v => hasKeyDeep(v, key))
      case None    => j.asArray.exists(_.exists(v => hasKeyDeep(v, key)))

  /** 数据根下的全部普通文件（零留存判据的读数面）。 */
  private def filesUnder(root: os.Path): List[os.Path] =
    if !os.exists(root) then Nil
    else os.walk(root).filter(os.isFile).toList

  /** 读整个文件（零字节复制判据的比较面）。 */
  private def dirBytes(p: os.Path): Array[Byte] = os.read.bytes(p)

  // ===== ① 主测：agent 腿真路径入台账 + ②③④ 三条读数 =====

  test("B′ 运行期：agent 腿出向行落 deviceOutPath（真台账）+ 对端帧零该键 + 零字节复制 + 首帧即带"):
    withSender { (ms, svc, peerFrames, localSeen, root) =>
      for
        srcDir <- IO.blocking(os.temp.dir(prefix = "nb-selfattach-src-"))
        src    = srcDir / "payload.png"
        bytes  = Array.tabulate[Byte](70_000)(i => ((i * 31 + 7) % 251).toByte) // 2 块（> 64 KiB）
        _      <- IO.blocking(os.write(src, bytes))
        recvDir <- IO.blocking(os.temp.dir(prefix = "nb-selfattach-recv-"))
        holder  <- Ref.of[IO, Option[ChunkReceiver]](None)
        loop     = new DeferredLoopback(holder)
        fiber <- svc
                   .sendLocalFiles(
                     "dev-2",
                     List(src),
                     acceptWait = 30.seconds,
                     uploadWait = 30.seconds,
                     transportOverride = Some(loop),
                     // 与两个工具调用点同值（`MailTool.scala:1057` / `FriendMessageTool.scala:372`）
                     origin = DropboxMessage.OriginAgent
                   )
                   .start
        tid <- awaitFrameId(peerFrames, "file-offer", 20.seconds)
        _ <- holder.set(
               Some(new ChunkReceiver(tid, bytes.length.toLong, AttachContract.ChunkSize, recvDir / "recv.bin"))
             )
        _ <- ms.handleDataMessage(
               Json.obj(
                 "kind"       -> "file-response".asJson,
                 "transferId" -> tid.asJson,
                 "accepted"   -> true.asJson
               )
             )
        outcome <- fiber.joinWithNever
        _ = assert(outcome.isRight, s"harness: agent-leg send must complete, got $outcome")
        delivered = outcome.toOption.toList.flatten.headOption.exists(_.delivered)
        _ = assert(delivered, s"harness: outcome must report delivered, got $outcome")

        // ── ① 真台账读数（磁盘 ground truth，非内存 Ref）──────────────────────
        ledgerPath = root / "dropbox" / "messages.json"
        ledgerRaw <- IO.blocking(os.read(ledgerPath))
        ledgerJson = io.circe.parser.parse(ledgerRaw).toOption.getOrElse(fail("ledger must be valid JSON"))
        decoded    = DropboxLedger.decode(ledgerRaw).toOption.getOrElse(fail("ledger must decode"))
        row = decoded.messages
          .getOrElse("dev-2", Nil)
          .find(_.transferId == tid)
          .getOrElse(fail(s"out row for transfer $tid must exist in the ledger"))
        // 内存面（getHistory —— `dropbox-get-history` 帧的同源数据面）
        history <- svc.getHistory("dev-2")
        histRow = history.find(_.transferId == tid).getOrElse(fail("history row must exist"))

        // ── ② 隐私：对端方向载荷逐帧递归扫描 + 检测器鉴别力对照 ────────────────
        peerNow  <- peerFrames.get
        localNow <- localSeen.get
        peerKinds  = peerNow.map(_.hcursor.downField("kind").as[String].getOrElse("?"))
        peerHits   = peerNow.filter(f => hasKeyDeep(f, "deviceOutPath"))
        ledgerHit  = hasKeyDeep(ledgerJson, "deviceOutPath") // 检测器对照（台账**必须**命中）

        // ── ④ 本机首帧 ─────────────────────────────────────────────────────
        msgFrames = localNow.filter(_.hcursor.downField("type").as[String].toOption.contains("dropbox-message"))
        outMsgFrame = msgFrames.find(f =>
          f.hcursor.downField("msg").downField("transferId").as[String].toOption.contains(tid)
        )
        framePath = outMsgFrame
          .flatMap(_.hcursor.downField("msg").downField("deviceOutPath").as[String].toOption)
          .getOrElse("")

        // ── ③ 零字节复制 / 零留存 ───────────────────────────────────────────
        rootFiles = filesUnder(root)
        copies    = rootFiles.filter(p => dirBytes(p).sameElements(bytes))
        tmpDir    = root / "dropbox" / ".tmp"
        tmpFiles  = if os.exists(tmpDir) then os.list(tmpDir).toList else Nil

        readings = Json.obj(
          "src"                     -> src.toString.asJson,
          "srcBytes"                -> bytes.length.asJson,
          "transferId"              -> tid.asJson,
          "ledgerRowDeviceOutPath"  -> row.deviceOutPath.asJson,
          "ledgerRowStatus"         -> row.status.asJson,
          "ledgerRowOrigin"         -> row.origin.asJson,
          "ledgerRowSavedPath"      -> row.savedPath.asJson,
          "historyRowDeviceOutPath" -> histRow.deviceOutPath.asJson,
          "peerFrameKinds"          -> peerKinds.asJson,
          "peerFramesWithKey"       -> peerHits.size.asJson,
          "ledgerDetectorHit"       -> ledgerHit.asJson,
          "localMsgFramePath"       -> framePath.asJson,
          "rootFiles"               -> rootFiles.map(_.toString).asJson,
          "byteCopiesOfSource"      -> copies.size.asJson,
          "senderTempFiles"         -> tmpFiles.map(_.toString).asJson
        )
        _ <- IO(println(s"[READING SELFATTACH-B1] $readings"))
        // 清理本 spec 自己的临时件（不跨用例残留）
        _ <- IO.blocking { os.remove.all(srcDir); os.remove.all(recvDir) }
      yield
        // ① 台账确有该字段（真绝对路径，逐字）
        assertEquals(row.deviceOutPath, src.toString, "台账出向行必须带发送端真绝对路径")
        assertEquals(histRow.deviceOutPath, src.toString, "getHistory（dropbox-get-history 同源）必须同值")
        assertEquals(row.status, "completed")
        assertEquals(row.origin, DropboxMessage.OriginAgent)
        assertEquals(row.savedPath, "", "发送侧不得写 savedPath（其语义逐字限接收端）")
        // ② 对端帧零该键（并先证明检测器有效：同一扫描器在台账上必命中）
        assertEquals(ledgerHit, true, "检测器鉴别力对照失败：同一扫描器必须能在台账里扫出该键")
        assert(peerKinds.nonEmpty, "防空跑：本次必须捕获到发往对端的载荷")
        assert(peerKinds.contains("file-offer"), s"必须捕获 file-offer，实得 $peerKinds")
        assert(peerKinds.contains("file-complete"), s"必须捕获 file-complete，实得 $peerKinds")
        assertEquals(peerHits.size, 0, "发往对端的载荷内**不得**出现 deviceOutPath（隐私硬约束）")
        // ③ 零字节复制 / 零留存
        assertEquals(copies.size, 0, "数据根内不得出现源件的字节副本")
        assertEquals(tmpFiles.size, 0, "发送端 temp 目录必须空（一切退出路径都清 temp）")
        // ④ 本机首帧即带
        assertEquals(framePath, src.toString, "本机 dropbox-message 帧必须首帧即带该路径")
    }

  // ===== ⑤ 负控：浏览器腿（无本机路径）恒空串 —— 该键不得由前端注入 =====

  test("B′ 负控：浏览器腿（offerFiles，无 outPath）⇒ 台账 deviceOutPath 恒空串"):
    withSender { (_, svc, peerFrames, _, root) =>
      for
        _      <- svc.offerFiles("dev-2", List(DropboxService.FileSpec("browser.png", 10L, "image/png")))
        tid    <- awaitFrameId(peerFrames, "file-offer", 10.seconds)
        raw    <- IO.blocking(os.read(root / "dropbox" / "messages.json"))
        decoded = DropboxLedger.decode(raw).toOption.getOrElse(fail("ledger must decode"))
        row = decoded.messages
          .getOrElse("dev-2", Nil)
          .find(_.transferId == tid)
          .getOrElse(fail("browser-leg out row must exist"))
        _ <- IO(println(s"[READING SELFATTACH-B2] browserLegDeviceOutPath='${row.deviceOutPath}' keys=${row.productElementNames.size}"))
      yield
        assertEquals(row.deviceOutPath, "", "浏览器腿无本机真实路径 ⇒ 恒空串（禁拼接/禁预测）")
        assertEquals(row.productElementNames.size, 16, "台账行键数 = 16（新键在编码面必在场）")
    }

end SenderOutPathLedgerSpec
