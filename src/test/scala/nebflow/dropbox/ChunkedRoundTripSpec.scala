package nebflow.dropbox

import cats.effect.IO
import munit.CatsEffectSuite

import java.security.MessageDigest

/**
 * 分块通道**自环**往返 —— 硬判据 ②③④。
 *
 * 口径申报（**禁读成端到端**）：本 spec 是**进程内自环**（in-process loopback harness）——
 * 真实文件 IO、真实分块、真实 sha256、真实断点续传与真实失败路径都跑，**只有 socket 被替换**
 * 为持有 `ChunkReceiver` 的进程内对象。设备面 peers 名册为空 ⇒ **端到端不可判定**，
 * 结论一律标「未端到端验证」。
 */
class ChunkedRoundTripSpec extends CatsEffectSuite:

  private val Chunk = 64 * 1024 // 小分块便于造多块 + 中断点（协议上分块大小是会话参数）

  private def sha256File(p: os.Path): String =
    val d = MessageDigest.getInstance("SHA-256")
    val in = os.read.inputStream(p)
    try
      val buf = new Array[Byte](64 * 1024)
      var n = in.read(buf)
      while n > 0 do
        d.update(buf, 0, n)
        n = in.read(buf)
    finally in.close()
    d.digest().map(b => f"$b%02x").mkString

  /** 确定性伪随机源文件（避免全零文件掩盖分块错位）。 */
  private def makeSource(dir: os.Path, name: String, size: Long): IO[os.Path] =
    IO.blocking {
      val p = dir / name
      val rnd = new java.util.Random(42L)
      val buf = new Array[Byte](64 * 1024)
      val out = os.write.outputStream(p, createFolders = true)
      try
        var remaining = size
        while remaining > 0 do
          val n = math.min(buf.length.toLong, remaining).toInt
          rnd.nextBytes(buf)
          out.write(buf, 0, n)
          remaining -= n
      finally out.close()
      p
    }

  private def target(peerId: String, transferId: String, fileName: String): FileTransfer =
    FileTransfer(
      transferId = transferId,
      direction = "out",
      peerDeviceId = peerId,
      peerAddress = "http://harness",
      fileName = fileName,
      fileSize = 0L,
      mimeType = "application/octet-stream",
      msgId = "m",
      status = "accepted",
      chunkSize = Chunk,
      proto = AttachContract.ProtoChunked
    )

  /**
   * 自环传输腿：把块喂给一个真实的 `ChunkReceiver`（temp 落在真实磁盘）。
   * `failAfterChunk` = Some(k) ⇒ 在应用完 k 号块之后的下一次 put 抛「对端不可达」
   * （模拟中途断连，用于断点续传）。
   */
  private final class LoopbackTransport(
    receiver: ChunkReceiver,
    failAfterChunk: Option[Int] = None,
    var puts: Int = 0
  ) extends ChunkTransport:
    def leg: String = "harness"

    def put(
      t: FileTransfer,
      frame: ChunkedTransfer.ChunkFrame,
      payload: Array[Byte]
    ): IO[Either[AttachContract.AttachError, ChunkedTransfer.ChunkAck]] =
      if failAfterChunk.contains(frame.chunkIndex) then
        IO.pure(
          Left(
            AttachContract.AttachError(
              AttachContract.Codes.PeerUnreachable,
              s"harness: simulated disconnect before chunk ${frame.chunkIndex}",
              phase = "transfer",
              path = Some("harness")
            )
          )
        )
      else
        puts += 1
        receiver.applyChunk(frame, payload)

    def probe(t: FileTransfer): IO[Either[AttachContract.AttachError, ChunkedTransfer.ReceiveState]] =
      receiver.prime().map(Right(_))

  end LoopbackTransport

  private def withDirs[A](f: (os.Path, os.Path) => IO[A]): IO[A] =
    IO.blocking(os.temp.dir(prefix = "nb-chunk-spec-")).flatMap { dir =>
      val src = dir / "src"
      val dst = dir / "dst"
      IO.blocking { os.makeDir.all(src); os.makeDir.all(dst) } *>
        f(src, dst).guarantee(IO.blocking(os.remove.all(dir)).handleErrorWith(_ => IO.unit))
    }

  // ===== ② 分块/流式 + 每块校验 + 整件 sha256：双侧一致 =====

  test("判据②：100 万字节（17 块）自环往返 —— 双侧整件 sha256 一致，逐块校验全通过") {
    withDirs { (src, dst) =>
      val size = 1_000_000L
      for
        source <- makeSource(src, "big.bin", size)
        tempPath = dst / ".recv.bin"
        receiver = new ChunkReceiver("tid-a", size, Chunk, tempPath)
        _ <- receiver.prime()
        transport = new LoopbackTransport(receiver)
        outcome <- ChunkedSendLoop.run(
          transport,
          target("p1", "tid-a", "big.bin"),
          source,
          chunkSize = Chunk,
          retriesPerLeg = 0
        )
      yield outcome match
        case Right(o) =>
          val senderSha = sha256File(source)
          val receiverSha = sha256File(tempPath)
          assertEquals(o.bytesSent, size, "发送端字节数")
          assertEquals(receiver.bytesReceived, size, "接收端字节数")
          assertEquals(o.receiverComputedSha256, senderSha, "接收端自算整件 sha256 必须等于源文件 sha256")
          assertEquals(receiverSha, senderSha, "落盘文件 sha256 必须等于源文件 sha256")
          assertEquals(o.chunksSent, 16, "1,000,000 B / 65,536 B = 16 块（末块非满）")
          assert(transport.puts == 16, s"每块恰好一次 put，实测 ${transport.puts}")
          assert(senderSha.length == 64)
          println(
            s"[READING C2] source=$size B chunks=${o.chunksSent} puts=${transport.puts} leg=${o.leg}\n" +
              s"[READING C2] sha256(sender)  =$senderSha\n" +
              s"[READING C2] sha256(receiver)=$receiverSha (receiver self-computed: ${o.receiverComputedSha256})\n" +
              s"[READING C2] bytes: source=$size sender=${o.bytesSent} receiver=${receiver.bytesReceived} onDisk=${os.size(tempPath)}"
          )
        case Left(err) => fail(s"自环往返必须成功，实得 ${err.render}")
      end for
    }
  }

  test("判据②负控：块字节被翻转 1 bit ⇒ 接收端 CHUNK_DIGEST_MISMATCH，且**不落盘、不推进 offset**") {
    withDirs { (src, dst) =>
      val size = 200_000L
      for
        source <- makeSource(src, "tamper.bin", size)
        tempPath = dst / ".tamper.bin"
        receiver = new ChunkReceiver("tid-b", size, Chunk, tempPath)
        _ <- receiver.prime()
        sender <- ChunkSender.open(source, "tid-b", 0L, Chunk)
        first <- sender.readNext().map(_.get)
        (frame, payload) = first
        tampered = payload.clone()
        _ = tampered(0) = (tampered(0) ^ 0x01).toByte
        res <- receiver.applyChunk(frame, tampered)
      yield
        res match
          case Left(err) =>
            assertEquals(err.code, AttachContract.Codes.ChunkDigestMismatch)
            assertEquals(err.chunkIndex, Some(0))
            assertEquals(err.expected, Some(frame.chunkSha256))
            assert(err.actualHash.exists(_ != frame.chunkSha256), "错误体必须带接收端自算的实际摘要")
          case Right(_) => fail("篡改后的块必须被拒")
        assertEquals(receiver.bytesReceived, 0L, "拒绝后不得推进 offset")
        assert(!os.exists(tempPath) || os.size(tempPath) == 0L, "拒绝后不得落盘")
      end for
    }
  }

  test("判据②（I2 禁自证）：末块整件摘要声明值与接收端自算不符 ⇒ WHOLE_DIGEST_MISMATCH 且删 temp") {
    withDirs { (src, dst) =>
      val size = 50_000L // < Chunk ⇒ 单块（末块即首块，整件比对点必被走到）
      for
        source <- makeSource(src, "whole.bin", size)
        tempPath = dst / ".whole.bin"
        receiver = new ChunkReceiver("tid-c", size, Chunk, tempPath)
        _ <- receiver.prime()
        sender <- ChunkSender.open(source, "tid-c", 0L, Chunk)
        first <- sender.readNext().map(_.get)
        _ = assert(first._1.bytes >= size, "must be the last (only) chunk")
        (frame, payload) = first
        // 只改整件摘要声明（块摘要保持正确）⇒ 块级通过、整件级必须拦住。
        badFrame = frame.copy(wholeSha256 = "0" * 64)
        res <- receiver.applyChunk(badFrame, payload)
      yield
        res match
          case Left(err) =>
            assertEquals(err.code, AttachContract.Codes.WholeDigestMismatch)
            assertEquals(err.expected, Some("0" * 64))
            assert(err.actualHash.exists(_.length == 64))
          case Right(_) => fail("整件摘要不符必须被拒（禁自证）")
        assert(!os.exists(tempPath), "整件不符必须删 temp（绝不 commit）")
      end for
    }
  }

  test("判据②（I2 禁自证）：接收端未回传自算整件摘要 ⇒ 发送端显式失败，不置 completed") {
    withDirs { (src, dst) =>
      val size = 50_000L // 单块：末块回执才承载整件摘要
      for
        source <- makeSource(src, "noack.bin", size)
        tempPath = dst / ".noack.bin"
        receiver = new ChunkReceiver("tid-d", size, Chunk, tempPath)
        _ <- receiver.prime()
        silent = new ChunkTransport:
          def leg = "silent"
          def put(t: FileTransfer, f: ChunkedTransfer.ChunkFrame, p: Array[Byte]) =
            // 回执吞掉 wholeSha256（模拟「自证式」实现：只说成功，不给接收端读数）
            receiver.applyChunk(f, p).map(_.map(_.copy(wholeSha256 = None)))
          def probe(t: FileTransfer) = receiver.prime().map(Right(_))
        outcome <- ChunkedSendLoop.run(silent, target("p4", "tid-d", "noack.bin"), source, retriesPerLeg = 0)
      yield outcome match
        case Left(err) =>
          assertEquals(err.code, AttachContract.Codes.WholeDigestMismatch)
          assert(err.message.contains("without returning its self-computed whole-file digest"))
        case Right(_) => fail("缺接收端自算摘要必须失败（否则等于回到自证）")
      end for
    }
  }

  // ===== ②b R4：每块回执 = 接收端**自算**（禁回显请求头）=====

  test("R4：非末块回执的 chunkSha256 = 接收端自算（改前回显请求头 ⇒ 重放错块放行）") {
    withDirs { (src, dst) =>
      val size = 200_000L
      for
        source <- makeSource(src, "r4.bin", size)
        tempPath = dst / ".r4.bin"
        receiver = new ChunkReceiver("tid-r4", size, Chunk, tempPath)
        _ <- receiver.prime()
        sender <- ChunkSender.open(source, "tid-r4", 0L, Chunk)
        c0 <- sender.readNext().map(_.get)
        (frame0, payload0) = c0
        a0 <- receiver.applyChunk(frame0, payload0)
        // 重放同 index，但字节被改；声明的块摘要仍是**正确**那一份 ⇒
        // 改前回执回显 frame.chunkSha256 ⇒ 发送端 `ack == frame` 恒真、错块被静默放行。
        forged = payload0.clone()
        _ = forged(0) = (forged(0) ^ 0x01).toByte
        a1 <- receiver.applyChunk(frame0, forged)
        last <- receiver.applyChunk(frame0, payload0)
      yield
        assertEquals(
          a0.map(_.chunkSha256),
          Right(ChunkedTransfer.sha256Hex(payload0)),
          "非末块回执必须带接收端自算摘要（改前 = frame.chunkSha256 回显）"
        )
        a1 match
          case Right(ack) =>
            assertEquals(ack.chunkSha256, ChunkedTransfer.sha256Hex(forged), "回执摘要必须是收到字节的自算值")
            assertNotEquals(
              ack.chunkSha256,
              frame0.chunkSha256,
              "R4 负控：回执不得回显请求头 —— 否则发送端 `ack.chunkSha256 == frame.chunkSha256` 恒真"
            )
          case Left(err) => fail(s"幂等重放应回执成功（no-op），实得 ${err.render}")
        // 非末块的 ack 必须仍然只带自算块摘要（整件摘要留到末块）
        assertEquals(last.map(_.wholeSha256), Right(None))
        assertEquals(receiver.bytesReceived, Chunk.toLong, "重放不得推进 offset")
      end for
    }
  }

  // ===== ③ 断点续传 =====

  test("判据③：中断后接续 —— 中断前 offset > 0，接续后最终双侧 sha256 一致（给前后读数）") {
    withDirs { (src, dst) =>
      val size = 300_000L // 5 块（4 × 65,536 + 末块 37,856）
      for
        source <- makeSource(src, "resume.bin", size)
        tempPath = dst / ".resume.bin"
        receiver = new ChunkReceiver("tid-e", size, Chunk, tempPath)
        _ <- receiver.prime()
        // 第一次：第 2 块之前断连（块 0/1 已落盘）
        t1 = new LoopbackTransport(receiver, failAfterChunk = Some(2))
        r1 <- ChunkedSendLoop.run(t1, target("p5", "tid-e", "resume.bin"), source, chunkSize = Chunk, retriesPerLeg = 0)
        offsetAfterBreak <- IO.pure(receiver.bytesReceived)
        prefix <- IO.blocking(ChunkedTransfer.hashFileStreaming(tempPath, offsetAfterBreak))
        // 第二次：新的发送游标 —— probe 得到权威 offset，从该 offset 续（不重传已落盘前缀）
        t2 = new LoopbackTransport(receiver)
        r2 <- ChunkedSendLoop.run(t2, target("p5", "tid-e", "resume.bin"), source, chunkSize = Chunk, retriesPerLeg = 0)
      yield
        r1 match
          case Left(err) => assertEquals(err.code, AttachContract.Codes.PeerUnreachable)
          case Right(_) => fail("第一次必须因模拟断连失败")
        assertEquals(offsetAfterBreak, 2L * Chunk, "中断后权威 offset = 已应用块数 × 块大小")
        assert(offsetAfterBreak > 0, "断点续传前提：中断前已有进展")
        r2 match
          case Right(o) =>
            val srcSha = sha256File(source)
            assertEquals(o.bytesSent, size)
            assertEquals(receiver.bytesReceived, size)
            assertEquals(o.receiverComputedSha256, srcSha, "接续后接收端自算整件 sha256 == 源文件 sha256")
            assertEquals(sha256File(tempPath), srcSha, "接续后落盘 sha256 == 源文件 sha256")
            assertEquals(t2.puts, 3, "接续只发剩余 3 块（0/1 不重传）")
            // 前后读数：中断时前缀摘要 vs 最终整件摘要
            assert(prefix != srcSha, s"中断时的前缀摘要($prefix)不应等于整件摘要($srcSha)")
            println(
              s"[READING C3] break: bytesReceived=$offsetAfterBreak/${size} (chunk 2 of 5 refused)\n" +
                s"[READING C3] break: sha256(prefix on disk)=$prefix  (prefix != whole ⇒ NOT the whole-file hash)\n" +
                s"[READING C3] after resume: bytesSent=${o.bytesSent} bytesReceived=${receiver.bytesReceived} resumedPuts=${t2.puts}\n" +
                s"[READING C3] after resume: sha256(source)=$srcSha\n" +
                s"[READING C3] after resume: sha256(receiver self-computed)=${o.receiverComputedSha256}\n" +
                s"[READING C3] after resume: sha256(onDisk)=${sha256File(tempPath)}"
            )
          case Left(err) => fail(s"接续必须成功，实得 ${err.render}")
        end match
      end for
    }
  }

  test("判据③负控 A：接收端 temp 前缀被篡改 1 字节 ⇒ 续传**不得静默拼接**，必须显式失败") {
    withDirs { (src, dst) =>
      val size = 200_000L
      for
        source <- makeSource(src, "corrupt.bin", size)
        tempPath = dst / ".corrupt.bin"
        receiver = new ChunkReceiver("tid-f", size, Chunk, tempPath)
        _ <- receiver.prime()
        sender <- ChunkSender.open(source, "tid-f", 0L, Chunk)
        first <- sender.readNext().map(_.get)
        _ <- receiver.applyChunk(first._1, first._2)
        // 篡改已落盘的前缀
        _ <- IO.blocking {
          val bytes = os.read.bytes(tempPath)
          bytes(0) = (bytes(0) ^ 0x01).toByte
          os.write.over(tempPath, bytes)
        }
        t = new LoopbackTransport(receiver)
        r <- ChunkedSendLoop.run(t, target("p6", "tid-f", "corrupt.bin"), source, chunkSize = Chunk, retriesPerLeg = 0)
      yield r match
        case Left(err) => assertEquals(err.code, AttachContract.Codes.WholeDigestMismatch)
        case Right(_) => fail("被篡改的前缀必须导致整件摘要不符 —— 静默拼接 = 交付损坏文件")
      end for
    }
  }

  test("判据③负控 B：重放同一 index ⇒ no-op，字节数不增长（幂等键 = (transferId, chunkIndex)）") {
    withDirs { (src, dst) =>
      val size = 200_000L
      for
        source <- makeSource(src, "idem.bin", size)
        tempPath = dst / ".idem.bin"
        receiver = new ChunkReceiver("tid-g", size, Chunk, tempPath)
        _ <- receiver.prime()
        sender <- ChunkSender.open(source, "tid-g", 0L, Chunk)
        first <- sender.readNext().map(_.get)
        (frame, payload) = first
        a1 <- receiver.applyChunk(frame, payload)
        after1 <- IO.pure(receiver.bytesReceived)
        a2 <- receiver.applyChunk(frame, payload)
        after2 <- IO.pure(receiver.bytesReceived)
      yield
        assert(a1.isRight && a2.isRight, "重放必须被回执为成功（no-op），不是错误")
        assertEquals(after1, Chunk.toLong)
        assertEquals(after2, after1, "重放后字节数不得增长")
        assertEquals(os.size(tempPath), after1, "磁盘长度也必须不变")
      end for
    }
  }

  test("判据③负控 C：跳号 index ⇒ OFFSET_OUT_OF_RANGE + expectedIndex（禁静默缓冲乱序块）") {
    withDirs { (src, dst) =>
      val size = 300_000L
      for
        source <- makeSource(src, "gap.bin", size)
        tempPath = dst / ".gap.bin"
        receiver = new ChunkReceiver("tid-h", size, Chunk, tempPath)
        _ <- receiver.prime()
        sender <- ChunkSender.open(source, "tid-h", 0L, Chunk)
        c0 <- sender.readNext().map(_.get)
        _ <- sender.readNext() // 丢弃块 1
        c2 <- sender.readNext().map(_.get)
        _ <- receiver.applyChunk(c0._1, c0._2)
        res <- receiver.applyChunk(c2._1, c2._2)
      yield
        res match
          case Left(err) =>
            assertEquals(err.code, AttachContract.Codes.OffsetOutOfRange)
            assertEquals(err.expectedIndex, Some(1))
            assertEquals(err.actualIndex, Some(2))
          case Right(_) => fail("跳号块必须被拒")
        assertEquals(receiver.bytesReceived, Chunk.toLong, "gap 拒绝后 offset 不得推进")
      end for
    }
  }

  // ===== ④ 负控：对端不可达 ⇒ fail-fast，不静默本地执行 =====

  test("判据④：两腿均不可达 ⇒ PEER_UNREACHABLE，错误体**同时**带 p2pReason 与 relayReason") {
    withDirs { (src, dst) =>
      val size = 100_000L
      for
        source <- makeSource(src, "unreach.bin", size)
        failing = (legName: String) =>
          new ChunkTransport:
            def leg = legName
            def put(t: FileTransfer, f: ChunkedTransfer.ChunkFrame, p: Array[Byte]) =
              IO.pure(
                Left(
                  AttachContract.AttachError(
                    AttachContract.Codes.PeerUnreachable,
                    s"$legName down",
                    phase = "transfer",
                    path = Some(legName)
                  )
                )
              )
            def probe(t: FileTransfer) =
              IO.pure(
                Left(
                  AttachContract.AttachError(
                    AttachContract.Codes.PeerUnreachable,
                    s"$legName probe down",
                    phase = "transfer",
                    path = Some(legName)
                  )
                )
              )
        transport = ChunkTransport.failover(failing("p2p"), failing("relay"))
        before = os.list(dst).size
        r <- ChunkedSendLoop.run(transport, target("p7", "tid-i", "unreach.bin"), source, retriesPerLeg = 0)
        after = os.list(dst).size
      yield
        r match
          case Left(err) =>
            assertEquals(err.code, AttachContract.Codes.PeerUnreachable)
            assert(err.p2pReason.isDefined, "错误体必须带 p2pReason（今天的实现丢弃它）")
            assert(err.relayReason.isDefined, "错误体必须带 relayReason")
            assert(err.p2pReason.get.contains("p2p"), s"p2pReason=${err.p2pReason}")
            assert(err.relayReason.get.contains("relay"), s"relayReason=${err.relayReason}")
            println(
              s"[READING C4] code=${err.code}\n" +
                s"[READING C4] p2pReason  =${err.p2pReason.get}\n" +
                s"[READING C4] relayReason=${err.relayReason.get}\n" +
                s"[READING C4] dstFilesBefore=$before dstFilesAfter=$after (no silent local write)"
            )
          case Right(_) => fail("对端不可达必须失败")
        assertEquals(after, before, "对端不可达时**不得**在本地落任何文件（禁静默本地执行）")
      end for
    }
  }

  test("判据④：单块重试后仍不可达 —— 重试不重置 offset（幂等重放语义）") {
    withDirs { (src, dst) =>
      val size = 200_000L
      for
        source <- makeSource(src, "retry.bin", size)
        tempPath = dst / ".retry.bin"
        receiver = new ChunkReceiver("tid-j", size, Chunk, tempPath)
        _ <- receiver.prime()
        attempts = new java.util.concurrent.atomic.AtomicInteger(0)
        flaky = new ChunkTransport:
          def leg = "flaky"
          def put(t: FileTransfer, f: ChunkedTransfer.ChunkFrame, p: Array[Byte]) =
            if f.chunkIndex == 1 && attempts.getAndIncrement() < 2 then
              IO.pure(
                Left(AttachContract.AttachError(AttachContract.Codes.PeerUnreachable, "flaky blip", phase = "transfer"))
              )
            else receiver.applyChunk(f, p)
          def probe(t: FileTransfer) = receiver.prime().map(Right(_))
        r <- ChunkedSendLoop.run(
          flaky,
          target("p8", "tid-j", "retry.bin"),
          source,
          chunkSize = Chunk,
          retriesPerLeg = 3,
          backoff = _ => scala.concurrent.duration.Duration.Zero
        )
      yield r match
        case Right(o) =>
          assertEquals(o.bytesSent, size)
          assertEquals(o.receiverComputedSha256, sha256File(source))
          assertEquals(attempts.get(), 3, "第 1 块先失败 2 次、第 3 次成功（重试不重置 offset）")
        case Left(err) => fail(s"重试应当最终成功，实得 ${err.render}")
      end for
    }
  }

  test("判据④：不可重试错误（ATTACH_TOO_LARGE）立即失败 —— 不消耗重试额度") {
    withDirs { (src, dst) =>
      for
        source <- makeSource(src, "nr.bin", 10_000L)
        calls = new java.util.concurrent.atomic.AtomicInteger(0)
        rejecting = new ChunkTransport:
          def leg = "reject"
          def put(t: FileTransfer, f: ChunkedTransfer.ChunkFrame, p: Array[Byte]) =
            calls.incrementAndGet()
            IO.pure(Left(AttachContract.AttachError(AttachContract.Codes.AttachTooLarge, "nope", phase = "transfer")))
          def probe(t: FileTransfer) =
            IO.pure(Right(ChunkedTransfer.ReceiveState("x", 0L, 10_000L, "")))
        r <- ChunkedSendLoop.run(
          rejecting,
          target("p9", "tid-k", "nr.bin"),
          source,
          chunkSize = Chunk,
          retriesPerLeg = 3,
          backoff = _ => scala.concurrent.duration.Duration.Zero
        )
      yield
        assert(r.isLeft)
        assertEquals(r.left.toOption.get.code, AttachContract.Codes.AttachTooLarge)
        assertEquals(calls.get(), 1, "不可重试错误只能尝试 1 次")
    }
  }

end ChunkedRoundTripSpec
