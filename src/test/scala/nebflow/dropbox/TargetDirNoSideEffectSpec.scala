package nebflow.dropbox

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import nebflow.gateway.WsHub
import nebflow.neblink.NeblinkService

import scala.concurrent.duration.*

/**
 * 钉 D（spec §⑦ 钉 D，零副作用断言）+ 钉 E（兼容矩阵 Q2 零回归，常绿钉）。
 *
 * 契约来源 = `.nebflow/Spec/20260914_162723_devattach-targetdir-contract-upgrade__chain-n-d623bb5b.md`
 * §3.1 不变量 I-DIR / §3.4 零副作用口径 / §4.3 表 1 第 2 行（等级 1 缺省语义零改动）。
 *
 * 判定入口 = 接收端 `handleIncomingOffer`（经 `NeblinkService.handleDataMessage` 注入 WS
 * data channel 帧，与真实入向路径同一条）；允许根由 `nebflow.json` 的
 * `dropbox.allowTargetDirRoots`（**接收端本地配置**）指到隔离临时树。
 *
 * **修正前（红）**：今天无 targetDir 通路（拒码/会话固化字段不存在）⇒ 本文件编译失败（红）。
 * **修正后（绿）**：拒码正确 + 零副作用（目标目录不存在 / 允许根条目集逐字不变 /
 * 无 `.*.dropbox-*` 残留 / 被拒会话不得再收字节）。
 */
class TargetDirNoSideEffectSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private def withReceiver[A](
    allowRoot: os.Path
  )(use: (NeblinkService, DropboxService, Ref[IO, List[Json]]) => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        prev <- IO(PathUtil.dataRoot)
        tmp  <- IO.blocking(os.temp.dir(prefix = "nb-tdse-"))
        _    <- IO(PathUtil.setDataRoot(tmp))
        // 接收端本地允许根配置（发送端无法影响该清单）
        _ <- IO.blocking(
               os.write.over(tmp / "nebflow.json", s"""{"dropbox":{"allowTargetDirRoots":["$allowRoot"]}}""")
             )
        ms   <- NeblinkService.create(0, dispatcher)
        seen <- Ref.of[IO, List[Json]](Nil)
        _    <- ms.setSendDataFn((_, _, p) => seen.update(_ :+ p).as(true))
        svc  <- DropboxService.createForTest(ms, new WsHub, 400.millis, 400.millis, 500.millis)
        out  <- use(ms, svc, seen)
        _    <- IO(PathUtil.setDataRoot(prev))
        _    <- IO(os.remove.all(tmp))
      yield out
    }

  private def offerFrame(
    transferId: String,
    fileName: String,
    targetDir: Option[String],
    proto: Option[Int]
  ): Json =
    val base = Json.obj(
      "kind"       -> "file-offer".asJson,
      "senderId"   -> "evil-peer".asJson,
      "transferId" -> transferId.asJson,
      "msgId"      -> s"$transferId-m".asJson,
      "fileName"   -> fileName.asJson,
      "fileSize"   -> 5.asJson,
      "mimeType"   -> "application/octet-stream".asJson
    )
    val withProto = proto.fold(base)(p => base.deepMerge(Json.obj("proto" -> p.asJson)))
    targetDir.fold(withProto)(d => withProto.deepMerge(Json.obj("targetDir" -> d.asJson)))

  private def responses(seen: List[Json]): List[Json] =
    seen.filter(_.hcursor.downField("kind").as[String].toOption.contains("file-response"))

  private def errCode(j: Json): Option[String] = j.hcursor.downField("targetDirCode").as[String].toOption

  private def listing(p: os.Path): List[String] =
    if !os.exists(p) then Nil else os.list(p).map(_.last).sorted.toList

  private def tempResidue(p: os.Path): List[String] =
    listing(p).filter(n => n.startsWith(".") && n.contains(".dropbox-"))

  // ===== 钉 D =====

  test("D1 越权目标（允许根外，存在/不存在各一）⇒ 结构化拒 + 目标目录不存在 + 允许根零改动"):
    val parent = os.temp.dir(prefix = "nb-tdse-tree-")
    val allow  = parent / "allowed"
    os.makeDir.all(allow)
    val outside     = parent / "outside"
    val outsideNew  = parent / "outside-not-created"
    IO(os.makeDir.all(outside))
      .flatMap(_ =>
        withReceiver(allow) { (ms, _, seen) =>
          for
            before <- IO(listing(allow))
            _      <- ms.handleDataMessage(offerFrame("t-d1-exist", "a.bin", Some(outside.toString), Some(AttachContract.ProtoAssignDir)))
            _      <- ms.handleDataMessage(offerFrame("t-d1-new", "b.bin", Some(outsideNew.toString), Some(AttachContract.ProtoAssignDir)))
            frames <- seen.get
            after  <- IO(listing(allow))
          yield
            val rs = responses(frames)
            assertEquals(rs.size, 2, s"两次非法 offer 都必须有裁定回执：$frames")
            rs.foreach { r =>
              val c = r.hcursor
              assertEquals(c.downField("accepted").as[Boolean].toOption, Some(false))
              assertEquals(errCode(r), Some(AttachContract.Codes.TargetDirNotAllowed))
              // 接收端等级自报（§1.4 等级自报通道）
              assertEquals(c.downField("proto").as[Int].toOption, Some(AttachContract.ProtoAssignDir))
              // 拒体不得回带任何「已接受落点」
              assertEquals(c.downField("targetDir").as[String].toOption, None)
            }
            // ② 目标目录不存在（请求前不存在 ⇒ 拒后仍必须不存在）
            assert(!os.exists(outsideNew), "被拒目标不得被创建")
            // ③ 允许根内条目集前后逐字相同
            assertEquals(after, before, "允许根条目集必须逐字不变")
            // ④ 无残留 temp
            assertEquals(tempResidue(allow), Nil)
        }
      )
      .flatMap(_ => IO(os.remove.all(parent)))

  test("D2 词法穿越（`..` 段）⇒ TARGET_DIR_INVALID + 允许根外零创建"):
    val parent = os.temp.dir(prefix = "nb-tdse-dotdot-")
    val allow  = parent / "allowed"
    os.makeDir.all(allow)
    val escapee = parent / ".ssh"
    withReceiver(allow) { (ms, _, seen) =>
      for
        before <- IO(listing(parent))
        _      <- ms.handleDataMessage(offerFrame("t-d2", "c.bin", Some(s"$allow/../.ssh"), Some(AttachContract.ProtoAssignDir)))
        frames <- seen.get
        after  <- IO(listing(parent))
      yield
        val rs = responses(frames)
        assertEquals(rs.size, 1)
        assertEquals(errCode(rs.head), Some(AttachContract.Codes.TargetDirInvalid))
        assert(!os.exists(escapee), "`..` 逃逸路径不得被创建")
        assertEquals(after, before, "父目录条目集必须逐字不变")
    }.flatMap(_ => IO(os.remove.all(parent)))

  test("D3 被拒会话**不得**再接受字节（拒 + 零副作用不可被事后推块绕过）"):
    val parent = os.temp.dir(prefix = "nb-tdse-chunk-")
    val allow  = parent / "allowed"
    os.makeDir.all(allow)
    withReceiver(allow) { (ms, svc, seen) =>
      for
        before <- IO(listing(allow))
        _      <- ms.handleDataMessage(offerFrame("t-d3", "d.bin", Some("/etc"), Some(AttachContract.ProtoAssignDir)))
        frames <- seen.get
        res <- svc.receiveChunkFromPeer(
                 "t-d3",
                 Stream.empty[IO],
                 DropboxService.ChunkHeaders(0, 5L, AttachContract.ChunkSize, "a" * 64, "b" * 64)
               )
        after <- IO(listing(allow))
      yield
        assertEquals(errCode(responses(frames).head), Some(AttachContract.Codes.TargetDirNotAllowed))
        res match
          case Left(err) =>
            assertEquals(err.code, AttachContract.Codes.SessionNotFound, err.render)
            assert(err.message.contains("refused"), err.message)
          case Right(_) => fail("被拒会话不得接受任何块")
        assertEquals(after, before, "允许根条目集必须逐字不变（含 temp 派生名）")
        assertEquals(tempResidue(allow), Nil)
    }.flatMap(_ => IO(os.remove.all(parent)))

  test("D4 允许根内不存在的子目录（判定通过）⇒ 允许，且此时**尚未**建任何目录"):
    val parent = os.temp.dir(prefix = "nb-tdse-ok-")
    val allow  = parent / "allowed"
    os.makeDir.all(allow)
    val target = allow / "sub" / "deep"
    withReceiver(allow) { (ms, _, seen) =>
      for
        _      <- ms.handleDataMessage(offerFrame("t-d4", "e.bin", Some(target.toString), Some(AttachContract.ProtoAssignDir)))
        frames <- seen.get
      yield
        val rs = responses(frames)
        assertEquals(rs.size, 1)
        assertEquals(rs.head.hcursor.downField("accepted").as[Boolean].toOption, Some(true))
        assertEquals(errCode(rs.head), None)
        // 判定只做**只读**探查 ⇒ offer 阶段不建目录（建目录只在 commit 前，spec §3.2 步骤 7）
        assert(!os.exists(target), "offer 阶段不得建目录（零副作用前置）")
        assertEquals(tempResidue(allow), Nil)
    }.flatMap(_ => IO(os.remove.all(parent)))

  // ===== 钉 E（兼容矩阵 Q2 零回归）=====

  test("E1 旧端形态 offer（无 targetDir、proto 缺席或 1）⇒ 照旧 accepted=true，且不落任何 targetDir 字段"):
    val parent = os.temp.dir(prefix = "nb-tdse-e1-")
    val allow  = parent / "allowed"
    os.makeDir.all(allow)
    withReceiver(allow) { (ms, _, seen) =>
      for
        _ <- ms.handleDataMessage(offerFrame("t-e1-a", "legacy1.bin", None, None))
        _ <- ms.handleDataMessage(offerFrame("t-e1-b", "legacy2.bin", None, Some(AttachContract.ProtoChunked)))
        frames <- seen.get
      yield
        val rs = responses(frames)
        assertEquals(rs.size, 2)
        rs.foreach { r =>
          assertEquals(r.hcursor.downField("accepted").as[Boolean].toOption, Some(true))
          assertEquals(r.hcursor.downField("targetDirCode").as[String].toOption, None)
          assertEquals(r.hcursor.downField("targetDir").as[String].toOption, None)
        }
    }.flatMap(_ => IO(os.remove.all(parent)))

  test("E2 缺省落点 = downloadsDir（逐字节一致）；有 targetDir 时取会话固化落点"):
    IO.blocking(os.temp.dir(prefix = "nb-tdse-e2-")).map { d =>
      val t = FileTransfer(
        transferId = "t-e2",
        direction = "in",
        peerDeviceId = "p",
        peerAddress = "",
        fileName = "x.bin",
        fileSize = 1L,
        mimeType = "",
        msgId = "m",
        status = "accepted"
      )
      assertEquals(DropboxService.landingDirFor(t), DropboxUtil.downloadsDir)
      assertEquals(DropboxService.landingDirFor(t.copy(targetDir = Some(""))), DropboxUtil.downloadsDir)
      assertEquals(DropboxService.landingDirFor(t.copy(targetDir = Some("relative/x"))), DropboxUtil.downloadsDir)
      val canon = d.toNIO.toRealPath().toString
      assertEquals(DropboxService.landingDirFor(t.copy(targetDir = Some(canon))).toString, canon)
      // 落点名派生链（resolveFinalPath）的缺省父目录不变
      val resolved = DropboxUtil.resolveFinalPath(DropboxUtil.downloadsDir, "nb-tdse-nope-xyz.bin")
      assert(
        resolved.toString == (DropboxUtil.downloadsDir / "nb-tdse-nope-xyz.bin").toString ||
          resolved.toString.startsWith((DropboxUtil.downloadsDir / "nb-tdse-nope-xyz").toString),
        s"缺省落点必须仍在 downloadsDir 内，实际 $resolved"
      )
      os.remove.all(d)
    }.unsafeRunSync()

  test("E3 旧 transfers.json（无 targetDir/targetDirCode/peerProto 键）照旧解码，新字段全默认 None"):
    // 逐字 = 本批**之前**的 `FileTransfer` 编码形态（含当时就无默认值的既有字段 receiverHash）。
    // 判据：新增三键缺席不得让旧 JSON 解码失败（向后兼容 = 「缺省即旧行为」的持久层前提）。
    val legacy =
      """{"t1":{"transferId":"t1","direction":"in","peerDeviceId":"p","peerAddress":"","fileName":"f.bin",""" +
        """"fileSize":1,"mimeType":"","msgId":"m","status":"accepted","receiverHash":"","totalBytes":1,""" +
        """"chunkSize":0,"wholeSha256":"","bytesReceived":0,"proto":1,"lastProgressAt":0}}"""
    val decoded = io.circe.parser.decode[Map[String, FileTransfer]](legacy)
    assert(decoded.isRight, decoded.swap.toOption.map(_.getMessage).getOrElse(""))
    val t = decoded.toOption.get("t1")
    assertEquals(t.targetDir, None)
    assertEquals(t.targetDirCode, None)
    assertEquals(t.peerProto, None)
    assertEquals(DropboxService.landingDirFor(t), DropboxUtil.downloadsDir)

end TargetDirNoSideEffectSpec
