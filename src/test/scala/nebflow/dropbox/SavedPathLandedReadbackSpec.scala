package nebflow.dropbox

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import nebflow.gateway.WsHub
import nebflow.neblink.NeblinkService

import scala.concurrent.duration.*

/**
 * 收端「通报路径 ⇄ 磁盘实际名」**同源钉**（nfpath-impl 批）。
 *
 * ## 缺陷
 *
 * `DropboxService.handleFileComplete` 的 `savedPath` 由 `DropboxUtil.resolveFinalPath`
 * **在落地之后重算**（`DropboxService.scala:1174`），而落地名是 `commitTempFile` 在
 * **落地前**解析的（同文件 `:1426`）。两次调用**不同源**，两种走法都会给出盘上不存在的名：
 *
 *   ① **无冲突**：commit 把 temp 搬到 `dir/<name>` 之后，报告侧再调 `resolveFinalPath`，
 *      它的 `os.exists(dir/<name>)` 判定已被**自己刚做完的 move** 改变 ⇒ 走冲突分支，
 *      回一个带 `_<yyyyMMdd_HHmmss>` 后缀、盘上**不存在**的名字（同一秒也照错，见下）。
 *   ② **同名冲突**：两次调用各自取 `ZonedDateTime.now()`，跨秒即与 commit 时的名字不同源。
 *
 * 用户表现 = 按通报路径读取必 file-not-found（「文件不存在或被清理」）。
 *
 * ## 实测对（KAI 2026-09-17 报备 · 本 spec 的外部基准）
 *
 * | 面 | 值 | 现读 |
 * |---|---|---|
 * | 通报 | `~/Downloads/call_00_E8yKDsczBtBctmSpVvuk1995_20260916_182050.txt` | ABSENT |
 * | 磁盘 | `~/Downloads/call_00_E8yKDsczBtBctmSpVvuk1995.txt` | 120079 B, mtime 2026-09-16 18:20:50 |
 *
 * 后缀时间戳 `_20260916_182050` 与磁盘 mtime **同一秒** ⇒ 走法 ① 的确定形态（与"冲突"无关）。
 * KAI 的第二对（`截屏2026-09-17 09.04.12.png`：磁盘 1785831 B / mtime 12:07:07，通报后缀
 * `_20260917_122640` 落后 19m33s）⇒ 走法 ②。
 *
 * ## 口径（禁读成端到端）
 *
 * 本 spec 走**真实入向链、无桩**：`handleIncomingOffer`（经 `NeblinkService.handleDataMessage`，
 * 与 WS data channel 同一入口）→ `receiveChunkFromPeer`（`POST /api/neblink/dropbox/transfer/<tid>`
 * 的调用面）→ `handleFileComplete`。落点用**接收端本地允许根配置**
 * （`nebflow.json` 的 `dropbox.allowTargetDirRoots`，发送端无法影响该清单）指到隔离临时树
 * ⇒ 全程不触用户 `~/Downloads`（用户数据只读）。
 *
 * **被替换的只有 socket/HTTP 传输层**（真流由 `Stream.emits` 直接喂给收端 handler）；
 * 命名 / 落盘 / commit / 通报 / ledger 全走生产代码。
 */
class SavedPathLandedReadbackSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  /** KAI 实测第二对的文件名形态（空格 + CJK）与**逐字字节数**。 */
  private val KaiName = "截屏2026-09-17 09.04.12.png"
  private val KaiSize = 1785831L

  // ===== 隔离接收端（真实入向链；allowRoot 之外的落点一律被 TargetDirGuard 拒） =====

  private def withReceiver[A](
    allowRoot: os.Path
  )(use: (NeblinkService, DropboxService, Ref[IO, List[Json]]) => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        prev <- IO(PathUtil.dataRoot)
        tmp  <- IO.blocking(os.temp.dir(prefix = "nb-nfpath-"))
        _    <- IO(PathUtil.setDataRoot(tmp))
        _ <- IO.blocking(
               os.write.over(tmp / "nebflow.json", s"""{"dropbox":{"allowTargetDirRoots":["$allowRoot"]}}""")
             )
        ms   <- NeblinkService.create(0, dispatcher)
        seen <- Ref.of[IO, List[Json]](Nil)
        // 通报面 = WsHub 广播（`notifyFrontend`）；必须注册收集器，否则「通报路径」不可观测
        hub  <- IO(new WsHub)
        reg  <- hub.register(j => seen.update(_ :+ j))
        // 送达腿判真（否则 offer 会被 markTransferFailed 打成 failed）
        _   <- ms.setSendDataFn((_, _, p) => seen.update(_ :+ p).as(true))
        // 信令窗口放大到 30s：本 spec 只判命名/通报，禁让超时看门狗在序列中途插手
        svc <- DropboxService.createForTest(ms, hub, 30.seconds, 30.seconds, 30.seconds)
        out <- use(ms, svc, seen)
        _   <- hub.unregister(reg)
        _   <- IO(PathUtil.setDataRoot(prev))
        _   <- IO(os.remove.all(tmp))
      yield out
    }

  private def offerFrame(transferId: String, fileName: String, size: Long, targetDir: os.Path): Json =
    Json.obj(
      "kind"       -> "file-offer".asJson,
      "senderId"   -> "kai-peer".asJson,
      "transferId" -> transferId.asJson,
      "msgId"      -> s"$transferId-m".asJson,
      "fileName"   -> fileName.asJson,
      "fileSize"   -> size.asJson,
      "mimeType"   -> "application/octet-stream".asJson,
      "proto"      -> AttachContract.ProtoAssignDir.asJson,
      "targetDir"  -> targetDir.toString.asJson
    )

  private def completeFrame(transferId: String): Json =
    Json.obj(
      "kind"       -> "file-complete".asJson,
      "transferId" -> transferId.asJson,
      "msgId"      -> s"$transferId-m".asJson,
      "success"    -> true.asJson
    )

  /** 一帧整件（单块）走真实收块面：`receiveChunkFromPeer` 的入参即 HTTP PUT 的解析结果。 */
  private def pushWholeFile(svc: DropboxService, transferId: String, bytes: Array[Byte]): IO[Unit] =
    val sum = ChunkedTransfer.sha256Hex(bytes)
    svc
      .receiveChunkFromPeer(
        transferId,
        Stream.emits(bytes).covary[IO],
        DropboxService.ChunkHeaders(0, bytes.length.toLong, bytes.length, sum, sum)
      )
      .flatMap {
        case Left(err) => IO.raiseError(new AssertionError(s"chunk rejected: ${err.render}"))
        case Right(_)  => IO.unit
      }

  /** 通报面（WS 帧）里的 `savedPath` —— 即引用面/用户看到的那个串。 */
  private def reportedPaths(seen: List[Json]): List[String] =
    seen.filter(_.hcursor.downField("type").as[String].toOption.contains("dropbox-file-complete"))
      .flatMap(_.hcursor.downField("msg").downField("savedPath").as[String].toOption)
      .filter(_.nonEmpty)

  /** 落点目录里的**非隐藏**条目（`.*.dropbox-*` temp 不算落盘件）。 */
  private def landed(dir: os.Path): List[String] =
    if !os.exists(dir) then Nil
    else os.list(dir).map(_.last).filterNot(_.startsWith(".")).sorted.toList

  /**
   * 预期侧取 **realpath** 形态：通报面给出的落点是 `TargetDirGuard` canonicalize 之后的形态，
   * 而 `os.temp.dir()` 返回 `/var/...`（macOS 上 `/private/var` 的符号链）⇒ 两侧必须同待遇，
   * 否则 `/var` vs `/private/var` 的**前缀**差异会伪装成断言失败，把「名字是否同源」这个
   * 唯一判据淹掉。
   */
  private def canon(p: os.Path): String = p.toNIO.toRealPath().toString

  /** 确定性伪随机载荷（禁全零：全零会掩盖截断/错位）。 */
  private def payload(size: Long, seed: Long = 7L): Array[Byte] =
    val a = new Array[Byte](size.toInt)
    new java.util.Random(seed).nextBytes(a)
    a

  // ===== ① 无冲突场景：通报路径 == 磁盘实际路径（逐字相等） =====

  test("① 无冲突落地：通报 savedPath 必须**逐字等于**磁盘实际路径（禁预计算名）"):
    val parent = os.temp.dir(prefix = "nb-nfpath-a-")
    val dir    = parent / "landing"
    os.makeDir.all(dir)
    val tid  = "t-nfpath-noconflict"
    val name = "report.txt"
    val data = payload(4096)
    withReceiver(dir) { (ms, svc, seen) =>
      for
        _ <- ms.handleDataMessage(offerFrame(tid, name, data.length.toLong, dir))
        _ <- pushWholeFile(svc, tid, data)
        _ <- ms.handleDataMessage(completeFrame(tid))
        frames <- seen.get
        hist   <- svc.getHistory("kai-peer")
        onDisk <- IO(landed(dir))
        reported = reportedPaths(frames)
        ledger   = hist.find(_.transferId == tid).map(_.savedPath).getOrElse("<no message>")
      yield
        // 通报名与磁盘实际名同源：先钉「落点条目集」再钉「串逐字相等」
        assertEquals(onDisk, List(name), s"落点目录应有且仅有裸名一件，实际 $onDisk")
        assertEquals(reported.size, 1, s"必须有一条带 savedPath 的完成通报：$frames")
        assertEquals(reported.head, canon(dir / name), "通报路径必须逐字等于磁盘实际路径")
        assertEquals(ledger, reported.head, "ledger（引用面）与通报必须同源")
        // 按通报路径读取必须成功（KAI 表现的反面）
        assert(os.exists(os.Path(reported.head, os.pwd)), s"按通报路径读取失败：${reported.head}")
        assertEquals(os.read.bytes(os.Path(reported.head, os.pwd)).toSeq, data.toSeq, "通报路径必须指向真落盘字节")
        assertEquals(os.read.bytes(os.Path(reported.head, os.pwd)).length.toLong, data.length.toLong)
    }.flatMap(_ => IO(os.remove.all(parent)))

  // ===== ② 同名冲突场景：通报含 `_<yyyyMMdd_HHmmss>` 且 == 磁盘实际名 =====

  test("② 同名冲突落地：通报路径必须等于**冲突后实际落盘名**（带后缀且盘上存在）"):
    val parent = os.temp.dir(prefix = "nb-nfpath-b-")
    val dir    = parent / "landing"
    os.makeDir.all(dir)
    val tid   = "t-nfpath-conflict"
    val name  = "report.txt"
    val prior = "PRE-EXISTING-CONTENT".getBytes("UTF-8")
    val data  = payload(2048, seed = 11L)
    IO.blocking(os.write(dir / name, prior)).flatMap(_ =>
      withReceiver(dir) { (ms, svc, seen) =>
        for
          _ <- ms.handleDataMessage(offerFrame(tid, name, data.length.toLong, dir))
          _ <- pushWholeFile(svc, tid, data)
          _ <- ms.handleDataMessage(completeFrame(tid))
          frames <- seen.get
          hist   <- svc.getHistory("kai-peer")
          onDisk <- IO(landed(dir))
          reported = reportedPaths(frames)
          ledger   = hist.find(_.transferId == tid).map(_.savedPath).getOrElse("<no message>")
        yield
          assertEquals(onDisk.size, 2, s"冲突落点应有两件（既有 + 新落），实际 $onDisk")
          val fresh = onDisk.filterNot(_ == name)
          assertEquals(fresh.size, 1, s"除既有件外应恰有一件新落盘：$onDisk")
          // 后缀形态：<stem>_<yyyyMMdd_HHmmss><ext>
          assert(
            fresh.head.matches("""report_\d{8}_\d{6}\.txt"""),
            s"冲突落盘名应为 report_<yyyyMMdd_HHmmss>.txt，实际 ${fresh.head}"
          )
          assertEquals(reported.size, 1, s"必须有一条带 savedPath 的完成通报：$frames")
          assertEquals(reported.head, canon(dir / fresh.head), "通报路径必须等于冲突后的实际落盘名")
          assertEquals(ledger, reported.head, "ledger（引用面）与通报必须同源")
          assert(os.exists(os.Path(reported.head, os.pwd)), s"按通报路径读取失败：${reported.head}")
          assertEquals(os.read.bytes(os.Path(reported.head, os.pwd)).toSeq, data.toSeq, "新落件必须是本次字节")
          // 既有件不得被覆盖（禁改名磁盘文件迎合通报）
          assertEquals(os.read.bytes(dir / name).toSeq, prior.toSeq, "既有同名件必须逐字不变")
    }).flatMap(_ => IO(os.remove.all(parent)))

  // ===== ③ KAI 场景回归对照：同名形态 + 同字节数，按通报路径读取必须成功 =====

  test(s"③ KAI 对照（$KaiName / $KaiSize B）：按通报路径读取必须成功"):
    val parent = os.temp.dir(prefix = "nb-nfpath-c-")
    val dir    = parent / "landing"
    os.makeDir.all(dir)
    val tid  = "t-nfpath-kai"
    val data = payload(KaiSize, seed = 20260917L)
    withReceiver(dir) { (ms, svc, seen) =>
      for
        _ <- ms.handleDataMessage(offerFrame(tid, KaiName, KaiSize, dir))
        _ <- pushWholeFile(svc, tid, data)
        _ <- ms.handleDataMessage(completeFrame(tid))
        frames <- seen.get
        onDisk <- IO(landed(dir))
        reported = reportedPaths(frames)
        // 「按通报路径读取」= 前端 previewLocalPath 的实际动作（os.Path(通报串) 后读字节）
        readBack <- IO {
                      reported.headOption.flatMap { p =>
                        val f = os.Path(p, os.pwd)
                        if os.exists(f) then Some(os.read.bytes(f).length.toLong) else None
                      }
                    }
      yield
        assertEquals(onDisk, List(KaiName), s"落点目录应有且仅有裸名一件，实际 $onDisk")
        assertEquals(reported.size, 1, s"必须有一条带 savedPath 的完成通报：$frames")
        assertEquals(reported.head, canon(dir / KaiName), "通报路径必须逐字等于磁盘实际名")
        assertEquals(readBack, Some(KaiSize), s"按通报路径读取必须成功且字节数 == KAI 读数 $KaiSize：${reported.headOption}")
    }.flatMap(_ => IO(os.remove.all(parent)))

  // ===== ③b 迟到/重放的 file-complete（KAI 形态）：通报与 ledger 都不得被重算值覆盖 =====

  test("③b 迟到/重放的完成帧（temp 已不在）：通报仍须等于磁盘实际名，且不得覆盖已记录值"):
    val parent = os.temp.dir(prefix = "nb-nfpath-e-")
    val dir    = parent / "landing"
    os.makeDir.all(dir)
    val tid  = "t-nfpath-late"
    val name = "late.txt"
    val data = payload(1024, seed = 33L)
    withReceiver(dir) { (ms, svc, seen) =>
      for
        _ <- ms.handleDataMessage(offerFrame(tid, name, data.length.toLong, dir))
        _ <- pushWholeFile(svc, tid, data)
        _ <- ms.handleDataMessage(completeFrame(tid))
        first <- seen.get
        hist1 <- svc.getHistory("kai-peer")
        // 迟到/重放的完成帧：temp 已被首次 commit 搬走 ⇒ 本次没有任何 move
        _ <- ms.handleDataMessage(completeFrame(tid))
        second <- seen.get
        hist2  <- svc.getHistory("kai-peer")
        onDisk <- IO(landed(dir))
        r1 = reportedPaths(first)
        r2 = reportedPaths(second)
        saved1 = hist1.find(_.transferId == tid).map(_.savedPath).getOrElse("<no message>")
        saved2 = hist2.find(_.transferId == tid).map(_.savedPath).getOrElse("<no message>")
        readBack <- IO {
                      r2.lastOption.map { p =>
                        val f = os.Path(p, os.pwd)
                        if os.exists(f) then Some(os.read.bytes(f).length) else None
                      }.getOrElse(None)
                    }
      yield
        assertEquals(onDisk, List(name), s"落点目录应仍只有裸名一件，实际 $onDisk")
        assertEquals(r1.size, 1, s"首次完成帧必须带 savedPath：$first")
        assertEquals(r2.size, 2, s"重放帧必须再带一条 savedPath（禁静默丢弃）：$second")
        assertEquals(r1.head, canon(dir / name), "首次通报必须逐字等于磁盘实际名")
        assertEquals(r2.last, canon(dir / name), "重放通报同样必须逐字等于磁盘实际名（禁重算）")
        assertEquals(saved1, canon(dir / name), "ledger 首值")
        assertEquals(saved2, canon(dir / name), "ledger 不得被重放帧的重算值覆盖")
        assertEquals(readBack, Some(data.length), s"按通报路径读取必须成功：${r2.lastOption}")
    }.flatMap(_ => IO(os.remove.all(parent)))

  // ===== ④ temp 残留与既有零回归 =====

  test("④ 落地后不得残留 `.*.dropbox-*` temp（commit 已搬走）"):
    val parent = os.temp.dir(prefix = "nb-nfpath-d-")
    val dir    = parent / "landing"
    os.makeDir.all(dir)
    val tid  = "t-nfpath-temp"
    val name = "clean.bin"
    val data = payload(512)
    withReceiver(dir) { (ms, svc, seen) =>
      for
        _ <- ms.handleDataMessage(offerFrame(tid, name, data.length.toLong, dir))
        _ <- pushWholeFile(svc, tid, data)
        _ <- ms.handleDataMessage(completeFrame(tid))
        frames <- seen.get
        onDisk <- IO(landed(dir))
        all    <- IO(if os.exists(dir) then os.list(dir).map(_.last).toList else Nil)
        hist   <- svc.getHistory("kai-peer")
      yield
        assertEquals(onDisk, List(name))
        assertEquals(all.filter(n => n.startsWith(".") && n.contains(".dropbox-")), Nil, s"temp 残留：$all")
        // 既有字段面零回归：状态与 kind 语义不变
        assertEquals(hist.find(_.transferId == tid).map(_.status), Some("completed"))
        assertEquals(hist.find(_.transferId == tid).map(_.kind), Some("file"))
        assertEquals(
          frames.filter(_.hcursor.downField("type").as[String].toOption.contains("dropbox-file-complete"))
            .flatMap(_.hcursor.downField("msg").downField("success").as[Boolean].toOption),
          List(true)
        )
    }.flatMap(_ => IO(os.remove.all(parent)))

end SavedPathLandedReadbackSpec
