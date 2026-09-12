package nebflow.dropbox

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.PathUtil
import nebflow.gateway.WsHub
import nebflow.neblink.{NeblinkService, PeerInfo}

import scala.concurrent.duration.*

/**
 * 闸位与负控在**服务面**的落点 —— 硬判据 ①（端到端闸）与 ④（对端不可达 fail-fast）。
 *
 * 判据 ① 在纯函数层的逐条判定见 `AttachLimitsSpec`；本 spec 证明闸位**真的接在
 * 服务入口上**（`DropboxService.offerFiles` = 唯一闸位，单件入口不绕过）。
 * 判据 ④ 的关键回归锚：今天的 `offerFile` 在对端不存在时是 `IO.unit`（静默吞），
 * 新契约要求显式 `PEER_UNREACHABLE`。
 */
class AttachGateServiceSpec extends CatsEffectSuite:

  private def peer(id: String): PeerInfo =
    PeerInfo(deviceId = id, deviceName = s"Device-$id", platform = "macos", address = "http://127.0.0.1:9")

  private def withStack[A](use: (NeblinkService, DropboxService) => IO[A]): IO[A] =
    Dispatcher.parallel[IO].use { dispatcher =>
      for
        prevRoot <- IO(PathUtil.dataRoot)
        tempDir <- IO.blocking(os.temp.dir(prefix = "nb-attachgate-spec-"))
        _ <- IO(PathUtil.setDataRoot(tempDir))
        ms <- NeblinkService.create(0, dispatcher)
        hub = new WsHub
        seen <- Ref.of[IO, List[Json]](Nil)
        regId <- hub.register(j => seen.update(_ :+ j))
        svc <- DropboxService.createForTest(ms, hub, 300.millis, 400.millis, 500.millis)
        out <- use(ms, svc)
        _ <- hub.unregister(regId)
        _ <- IO {
          PathUtil.setDataRoot(prevRoot)
          os.remove.all(tempDir)
        }
      yield out
    }

  private def spec(name: String, size: Long): DropboxService.FileSpec =
    DropboxService.FileSpec(name, size, "application/octet-stream")

  // ===== 判据①（服务面）：闸位真的接在入口上 =====

  test("服务面闸位：10 件 ⇒ ATTACH_TOO_MANY + actual=10 + limit=9，且**零消息落库、零 transfer 建立**") {
    withStack { (ms, svc) =>
      for
        _ <- ms.upsertPeer(peer("peer1"))
        res <- svc.offerFiles("peer1", List.tabulate(10)(i => spec(s"f$i.bin", 1_000L)))
        history <- svc.getHistory("peer1")
      yield
        res match
          case Left(err) =>
            assertEquals(err.code, AttachContract.Codes.AttachTooMany)
            assertEquals(err.actual, Some(10L))
            assertEquals(err.limit, Some(9L))
          case Right(ids) => fail(s"10 attachments must be rejected, got ${ids.size} transfer ids")
        assertEquals(history, Nil, "超限必须在建 transfer / 写消息**之前**就 fail-fast")
        println(
          s"[READING C1] service gate: 10 files -> " +
            s"code=${res.left.toOption.map(_.code).getOrElse("-")} " +
            s"actual=${res.left.toOption.flatMap(_.actual).getOrElse(-1L)} " +
            s"limit=${res.left.toOption.flatMap(_.limit).getOrElse(-1L)} historySize=${history.size}"
        )
    }
  }

  test("服务面闸位：100,000,001 B ⇒ ATTACH_TOO_LARGE + actual=100000001 + limit=100000000；100,000,000 B 通过") {
    withStack { (ms, svc) =>
      for
        _ <- ms.upsertPeer(peer("peer1"))
        over <- svc.offerFiles("peer1", List(spec("big.bin", 100_000_001L)))
        atLimit <- svc.offerFiles("peer1", List(spec("exact.bin", 100_000_000L)))
      yield
        over match
          case Left(err) =>
            assertEquals(err.code, AttachContract.Codes.AttachTooLarge)
            assertEquals(err.actual, Some(100_000_001L))
            assertEquals(err.limit, Some(100_000_000L))
          case Right(ids) => fail(s"100,000,001 B must be rejected, got $ids")
        atLimit match
          case Right(ids) => assertEquals(ids.size, 1, "边界正控：100,000,000 B 必须被接受")
          case Left(err)  => fail(s"100,000,000 B must be accepted (正控防线量纲写错), got ${err.render}")
        println(
          s"[READING C1] service gate: 100,000,001 B -> code=${over.left.toOption.map(_.code).getOrElse("-")} " +
            s"actual=${over.left.toOption.flatMap(_.actual).getOrElse(-1L)} limit=${over.left.toOption.flatMap(_.limit).getOrElse(-1L)}\n" +
            s"[READING C1] service gate: 100,000,000 B -> ${if atLimit.isRight then "ACCEPTED" else "REJECTED (WRONG)"} " +
            s"transfers=${atLimit.toOption.map(_.size).getOrElse(-1)}"
        )
    }
  }

  test("服务面闸位：9 件边界正控 ⇒ 通过并各分配一个 transferId（单条消息 ≤9 件）") {
    withStack { (ms, svc) =>
      for
        _ <- ms.upsertPeer(peer("peer1"))
        res <- svc.offerFiles("peer1", List.tabulate(9)(i => spec(s"f$i.bin", 1_000L)))
        history <- svc.getHistory("peer1")
      yield
        res match
          case Right(ids) =>
            assertEquals(ids.size, 9)
            assertEquals(ids.distinct.size, 9, "每件一个独立 transferId")
            println(s"[READING C1] service gate: 9 files -> ACCEPTED transfers=${ids.size} batchIdCount=1")
          case Left(err) => fail(s"9 attachments must be accepted, got ${err.render}")
        val fileMsgs = history.filter(_.kind == "file")
        assertEquals(fileMsgs.size, 9)
        assertEquals(fileMsgs.map(_.batchId).distinct.size, 1, "9 件共用同一 batchId = 同一条消息")
        assertEquals(fileMsgs.map(_.attachmentCount).distinct, List(9))
        assertEquals(fileMsgs.map(_.attachmentIndex).sorted, (0 until 9).toList)
    }
  }

  test("服务面闸位：单件入口不得绕过闸位（offerFile 超限同样被拒）") {
    withStack { (ms, svc) =>
      for
        _ <- ms.upsertPeer(peer("peer1"))
        res <- svc.offerFile("peer1", "huge.bin", 100_000_001L, "application/octet-stream")
        history <- svc.getHistory("peer1")
      yield
        res match
          case Left(err) => assertEquals(err.code, AttachContract.Codes.AttachTooLarge)
          case Right(_)  => fail("单件入口必须走同一闸位")
        assertEquals(history, Nil)
    }
  }

  // ===== 判据④（服务面）：对端不可达 fail-fast，不静默 =====

  test("判据④：对端不在名册 ⇒ PEER_UNREACHABLE（今天的 `IO.unit // peer not found, ignore` 是负控）") {
    withStack { (ms, svc) =>
      for
        _ <- ms.upsertPeer(peer("known"))
        res <- svc.offerFiles("ghost-device", List(spec("a.bin", 1_000L)))
      yield
        res match
          case Left(err) =>
            assertEquals(err.code, AttachContract.Codes.PeerUnreachable)
            assert(err.message.contains("nothing was offered or written locally"), err.message)
          case Right(ids) => fail(s"unknown peer must fail explicitly, got $ids")
    }
  }

  // ===== 判据③：探针在会话缺失时显式报错（不静默回 0 —— 那会让发送端从头重传） =====

  test("判据③（探针）：会话不存在 ⇒ SESSION_NOT_FOUND，不静默返回 offset=0") {
    withStack { (_, svc) =>
      svc.probeTransfer("no-such-transfer").map {
        case Left(err) => assertEquals(err.code, AttachContract.Codes.SessionNotFound)
        case Right(state) =>
          fail(s"missing session must not be reported as offset=0 (silent restart of the whole transfer): $state")
      }
    }
  }

  // ===== R4（服务面）：分块回执 = 接收端自算摘要（改前 RestApiRoutes 回显请求头） =====

  test("R4 服务面：receiveChunkFromPeer 回执带接收端自算块摘要 —— 重放错块不再恒真放行") {
    withStack { (ms, svc) =>
      val transferId = "tid-r4-svc"
      val total = 64L
      val chunkSize = 32
      val src = Array.tabulate(total.toInt)(i => (i * 7 % 251).toByte)
      val whole = ChunkedTransfer.sha256Hex(src)
      val first = src.slice(0, chunkSize)
      // 重放同一 index 但字节被改：声明的块摘要仍是**正确**那一份 ⇒
      // 只有「回执 = 接收端自算」才能让发送端发现（改前 route 回显请求头 ⇒ 恒真）。
      val forged = first.clone()
      forged(0) = (forged(0) ^ 0x03).toByte
      val tempFile = DropboxUtil.downloadsDir / s".r4-svc.bin.dropbox-${transferId.take(8)}"
      val body = for
        _ <- ms.handleDataMessage(
          Json.obj(
            "kind" -> "file-offer".asJson,
            "senderId" -> "peer-r4".asJson,
            "transferId" -> transferId.asJson,
            "msgId" -> "m-r4".asJson,
            "fileName" -> "r4-svc.bin".asJson,
            "fileSize" -> total.asJson,
            "mimeType" -> "application/octet-stream".asJson
          )
        )
        h0 = DropboxService.ChunkHeaders(0, total, chunkSize, ChunkedTransfer.sha256Hex(first), whole)
        a0 <- svc.receiveChunkFromPeer(transferId, fs2.Stream.emits(first).covary[IO], h0)
        a1 <- svc.receiveChunkFromPeer(transferId, fs2.Stream.emits(forged).covary[IO], h0)
      yield
        assertEquals(
          a0.map(_.chunkSha256),
          Right(ChunkedTransfer.sha256Hex(first)),
          "非末块回执必须带接收端自算摘要"
        )
        a1 match
          case Right(ack) =>
            assertEquals(ack.chunkSha256, ChunkedTransfer.sha256Hex(forged), "回执摘要必须是收到字节的自算值")
            assertNotEquals(ack.chunkSha256, h0.chunkSha256, "R4 负控：不得回显请求头（否则发送端比对恒真）")
            assertEquals(ack.bytesReceived, chunkSize.toLong, "重放不得推进 offset")
          case Left(err) => fail(s"幂等重放应回执成功（no-op）：${err.render}")
      // 接收端 temp 落在真实 ~/Downloads（下载目录与 dataRoot 无关）⇒ 测试后自清。
      body.guarantee(
        IO.blocking {
          if os.exists(tempFile) then os.remove(tempFile)
          ()
        }.handleErrorWith(_ => IO.unit)
      )
    }
  }

end AttachGateServiceSpec
