package nebflow.dropbox

import cats.effect.IO
import munit.CatsEffectSuite

import java.security.MessageDigest

import scala.concurrent.duration.*

/**
 * xferb 批（线B Phase B 第一段 · 纯 Scala 面）自验 —— 卡 P0-1…P0-4 的**可判定**半边。
 *
 * 口径申报（**禁读成端到端**）：本 spec 是**进程内自环** + 纯函数断言 —— 真实文件 IO、
 * 真实分块、真实 sha256、真实探针/续传/中断路径都跑，**只有 socket 被替换**为持有
 * `ChunkReceiver` 的进程内对象（与 `ChunkedRoundTripSpec` 同口径）。真实两进程（隔离实例
 * ⇄ 旧形态对端）的读数另在报告里落盘；本 spec 证明的是「规则本身对」。
 *
 * 覆盖面：
 *   A. 自适应块参（P0-1）：反解式的不变量 —— 块大小随速率单调、被夹取；单块死线随块大小
 *      派生且落在该腿窗口内；**25–30 KB/s 容量下 relay 腿必须够**（卡 §0 的失败根因）。
 *   B. 续传对齐（P0-2）：**非整块尾**（上一次 append 被中断）的续传 —— 前缀不重来、
 *      尾巴不落两遍、最终 sha256 与源一致、全程零 `OFFSET_OUT_OF_RANGE`。
 *   C. 已落满的续传（P0-2）：一块都不发时**不得**凭发送端自算摘要宣布成功 ——
 *      必须用接收端**重算**的前缀摘要比对。
 *   D. 进度节拍（P0-4）：在飞字节按节拍上报，且**另立字段**（不与已确认字节混轴）。
 *   E. 中继腿失败体保结构（P0-3）：接收端回传的结构化错误体不被摊平成「不可达」。
 */
class XferbChunkParamsSpec extends CatsEffectSuite:

  private val Chunk = 64 * 1024 // 小分块便于造多块 + 中途断点（协议上分块大小是会话参数）

  // ===== 夹具 =====

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

  private def target(peerId: String, transferId: String, fileName: String, total: Long): FileTransfer =
    FileTransfer(
      transferId = transferId,
      direction = "out",
      peerDeviceId = peerId,
      peerAddress = "http://harness",
      fileName = fileName,
      fileSize = total,
      mimeType = "application/octet-stream",
      msgId = "m",
      status = "accepted",
      totalBytes = total,
      chunkSize = Chunk,
      proto = AttachContract.ProtoChunked
    )

  private def withDirs[A](f: (os.Path, os.Path) => IO[A]): IO[A] =
    IO.blocking(os.temp.dir(prefix = "nb-xferb-spec-")).flatMap { dir =>
      val src = dir / "src"
      val dst = dir / "dst"
      IO.blocking { os.makeDir.all(src); os.makeDir.all(dst) } *>
        f(src, dst).guarantee(IO.blocking(os.remove.all(dir)).handleErrorWith(_ => IO.unit))
    }

  /**
   * 自环腿：块喂给真实 `ChunkReceiver`（temp 落真实磁盘）。
   *
   *   - `failAfterChunk` = Some(k)：应用到 k 号块时**不落地**，直接回「对端不可达」；
   *   - `partialAtChunk` = Some((k, n))：k 号块**只落前 n 字节**再回「对端不可达」
   *     —— 忠实复刻「append 中途被杀」留下的**非整块尾**（P0-2 的关键夹具）。
   */
  private class LoopbackTransport(
    receiver: ChunkReceiver,
    failAfterChunk: Option[Int] = None,
    partialAtChunk: Option[(Int, Int)] = None,
    var puts: Int = 0,
    putDelay: FiniteDuration = Duration.Zero,
    // 帧时序（诊断 P0-4 节拍与块在途的**重叠关系**；生产腿不需要，测试腿要能读）
    val putLog: scala.collection.mutable.ArrayBuffer[(Int, Long)] = scala.collection.mutable.ArrayBuffer.empty
  ) extends ChunkTransport:
    def leg: String = "harness"

    def put(t: FileTransfer, frame: ChunkedTransfer.ChunkFrame, payload: Array[Byte]) =
      putLog += ((frame.chunkIndex, System.nanoTime()))
      val maybeFail =
        partialAtChunk match
          case Some((idx, take)) if frame.chunkIndex == idx =>
            IO.blocking(os.write.append(receiver.tempPath, payload.take(take))) *>
              IO.pure(
                Left(
                  AttachContract.AttachError(
                    AttachContract.Codes.PeerUnreachable,
                    s"harness: append interrupted after $take B of chunk ${frame.chunkIndex}",
                    phase = "transfer",
                    path = Some("harness")
                  )
                )
              )
          case _ =>
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
      val paused = if putDelay > Duration.Zero then IO.sleep(putDelay) else IO.unit
      paused *> maybeFail
    end put
    def probe(t: FileTransfer) = receiver.prime().map(Right(_))
  end LoopbackTransport

  // ===== A. 自适应块参（P0-1）=====

  test("A1：块大小随速率单调不减，且恒被夹在 [MinChunkSize, MaxChunkSize] 内") {
    // 🔴 单调梯子只对**实测速率**成立；`0` 不是「极慢」而是「没有读数」⇒ 走 A4 的
    //    保守假设（≥ 慢端），与慢端不同轴，故不放进这条梯子（见下方单独断言）。
    val rates = List(1L, 4 * 1024L, 17 * 1024L, 25 * 1024L, 200 * 1024L, 2 * 1024 * 1024L, 50L * 1024 * 1024)
    val sizes = rates.map(r => AttachContract.adaptiveChunkSize(r))
    sizes.foreach(s =>
      assert(s >= AttachContract.MinChunkSize, s"块大小 $s 低于下限（会退化成 RTT 拖死）")
      assert(s <= AttachContract.MaxChunkSize, s"块大小 $s 超过上限（不放大既有线上块长）")
      assertEquals(s % AttachContract.ChunkSizeQuantum, 0, s"块大小 $s 未量化到 64 KiB 整数倍")
    )
    assertEquals(sizes, sizes.sorted, s"块大小必须随速率单调不减：rates=$rates sizes=$sizes")
    assertEquals(sizes.last, AttachContract.MaxChunkSize, "极高速率必须顶到上限（快链路零回归）")
    // 无读数 = 按**保守假设**取值（不是按下界取值）：与「极慢」同值会让本端在未知链路上
    // 过度缩小块；与假设值同值才是契约（A4 钉了假设值本身 ≤ 实测底座下沿）。
    assertEquals(
      AttachContract.adaptiveChunkSize(0L),
      AttachContract.adaptiveChunkSize(AttachContract.AssumedMinRateBytesPerSec),
      "无速率读数必须等价于「按保守假设速率反解」"
    )
    assertEquals(
      AttachContract.adaptiveChunkSize(-1L),
      AttachContract.adaptiveChunkSize(AttachContract.AssumedMinRateBytesPerSec),
      "非法速率读数同无读数"
    )
  }

  test("A2：25–30 KB/s 容量下 relay 腿必须能成（= 卡 §0 的失败根因被判红）") {
    // 卡 §0 实测：底座 24.6–30.3 KB/s；旧口径要求 4 MiB 在 30 s 内（≥139.8 KB/s）⇒ 2.3–5.6 倍超配。
    val capacityBytesPerSec = 25 * 1024L
    val chunk = AttachContract.adaptiveChunkSize(capacityBytesPerSec)
    val deadline = AttachContract.relayDeadline(chunk.toLong, capacityBytesPerSec)
    val needed = chunk.toDouble / capacityBytesPerSec.toDouble
    assert(
      needed < deadline.toMillis / 1000.0,
      s"25 KB/s 下 $chunk B 需 ${needed}s > relay 死线 ${deadline.toMillis / 1000.0}s ⇒ 确定性失败未被消除"
    )
    assert(
      deadline <= AttachContract.RelayDeadlineCeiling,
      s"relay 死线 $deadline 超过窗口上限 ${AttachContract.RelayDeadlineCeiling}（不得无限放宽）"
    )
    // 反例对照：旧口径 4 MiB 在同一容量下的需求 —— 必须显著超出任何窗口。
    val legacyNeeded = 4L * 1024 * 1024 / capacityBytesPerSec.toDouble
    assert(legacyNeeded > AttachContract.RelayDeadlineCeiling.toMillis / 1000.0, "旧口径 4 MiB 必然超窗")
    println(
      s"[READING A2] capacity=$capacityBytesPerSec B/s -> chunkSize=$chunk B, relayDeadline=${deadline.toMillis}ms, " +
        s"needed=${needed}s | legacy 4 MiB needed=${legacyNeeded}s (> ceiling ${AttachContract.RelayDeadlineCeiling.toMillis}ms)"
    )
  }

  test("A3：单块死线恒落在该腿窗口内，且随块大小单调不减") {
    val rates = List(4 * 1024L, 24 * 1024L, 100 * 1024L, 1024 * 1024L)
    rates.foreach { r =>
      val small = AttachContract.p2pDeadline(AttachContract.MinChunkSize.toLong, r)
      val big = AttachContract.p2pDeadline(AttachContract.MaxChunkSize.toLong, r)
      assert(small >= AttachContract.P2PDeadlineFloor && small <= AttachContract.P2PDeadlineCeiling, s"p2p $small")
      assert(big >= AttachContract.P2PDeadlineFloor && big <= AttachContract.P2PDeadlineCeiling, s"p2p $big")
      assert(big >= small, "死线必须随块大小单调不减")
      val relay = AttachContract.relayDeadline(AttachContract.MinChunkSize.toLong, r)
      assert(
        relay >= AttachContract.RelayDeadlineFloor && relay <= AttachContract.RelayDeadlineCeiling,
        s"relay $relay 越窗"
      )
    }
  }

  test("A4：速率读数缺失/非法 ⇒ 回落保守假设（方向 = 不因假设过快而失败）") {
    assertEquals(AttachContract.normalizeRate(0L), AttachContract.AssumedMinRateBytesPerSec)
    assertEquals(AttachContract.normalizeRate(-5L), AttachContract.AssumedMinRateBytesPerSec)
    assertEquals(AttachContract.normalizeRate(1L), AttachContract.MinRateBytesPerSec, "低于下界的读数被夹取")
    // 假设值本身必须**慢于**实测底座下沿（否则首传仍按过快的假设推进）
    assert(
      AttachContract.AssumedMinRateBytesPerSec <= 25 * 1024L,
      s"假设速率 ${AttachContract.AssumedMinRateBytesPerSec} 不得高于实测底座下沿 25 KB/s"
    )
  }

  // ===== B. 续传对齐（P0-2）=====

  test("B1：非整块尾中断 ⇒ 续传不重来已确认块、不落两遍尾巴、整件 sha256 一致") {
    withDirs { (src, dst) =>
      val size = 300_000L // 5 块：4 × 65,536 + 末块 37,856
      for
        source <- makeSource(src, "partial.bin", size)
        tempPath = dst / ".partial.bin"
        receiver = new ChunkReceiver("tid-p", size, Chunk, tempPath)
        _ <- receiver.prime()
        // 第一次：块 0/1 正常落地，块 2 **只落半截**（append 被中断）后断连
        // ⇒ 磁盘上是**非整块尾**（这正是续传对齐要处理的形状）。
        t1 = new LoopbackTransport(receiver, partialAtChunk = Some((2, Chunk / 2)))
        r1 <- ChunkedSendLoop.run(
          t1,
          target("p", "tid-p", "partial.bin", size),
          source,
          chunkSize = Chunk,
          retriesPerLeg = 0
        )
        onDiskAfterBreak <- IO.blocking(os.size(tempPath))
        probeState <- receiver.prime()
        // 第二次：**同号**续传 —— 探针给的是未对齐的实际长度。
        t2 = new LoopbackTransport(receiver)
        r2 <- ChunkedSendLoop.run(
          t2,
          target("p", "tid-p", "partial.bin", size),
          source,
          chunkSize = Chunk,
          retriesPerLeg = 0
        )
        finalSize <- IO.blocking(os.size(tempPath))
        finalHash <- IO.blocking(sha256File(tempPath))
        srcHash = sha256File(source)
      yield
        r1 match
          case Left(err) => assertEquals(err.code, AttachContract.Codes.PeerUnreachable)
          case Right(_) => fail("第一次必须因模拟断连失败")
        assertEquals(onDiskAfterBreak, 2L * Chunk + Chunk / 2, s"夹具必须造出非整块尾，实测 $onDiskAfterBreak B")
        assert(onDiskAfterBreak % Chunk.toLong != 0L, "尾巴必须不是整块（否则本用例不承重）")
        assertEquals(probeState.bytesReceived, onDiskAfterBreak, "探针权威值 = temp 实际长度（不夹取到块边界）")
        r2 match
          case Right(o) =>
            assertEquals(
              finalSize,
              size,
              s"续传后落盘长度必须精确等于 totalBytes（重影尾巴会多出 ${onDiskAfterBreak % Chunk} B）"
            )
            assertEquals(finalHash, srcHash, "续传后落盘 sha256 必须等于源文件")
            assertEquals(o.receiverComputedSha256, srcHash, "接收端自算整件 sha256 必须等于源文件")
            // 已确认的块 0/1 不得重传；只发剩余 3 块（含被截断后重发的块 2）。
            assertEquals(t2.puts, 3, s"续传只应发 3 块，实发 ${t2.puts}")
            println(
              s"[READING B1] break: onDisk=$onDiskAfterBreak/${size} (unaligned tail=${onDiskAfterBreak % Chunk} B), probeOffset=${probeState.bytesReceived}, resumedPuts=${t2.puts}, finalSize=$finalSize"
            )
          case Left(err) => fail(s"续传必须成功，实得 ${err.render}")
        end match
      end for
    }
  }

  test("B2：同号重试的探针语义 —— offset 由 temp 实际长度决定（不是内存计数、不是持久化字段）") {
    withDirs { (src, dst) =>
      val size = 200_000L
      for
        source <- makeSource(src, "authority.bin", size)
        tempPath = dst / ".authority.bin"
        receiver = new ChunkReceiver("tid-a2", size, Chunk, tempPath)
        _ <- receiver.prime()
        t1 = new LoopbackTransport(receiver, failAfterChunk = Some(1))
        _ <- ChunkedSendLoop.run(
          t1,
          target("p", "tid-a2", "authority.bin", size),
          source,
          chunkSize = Chunk,
          retriesPerLeg = 0
        )
        // 伪造「内存计数」：全新 receiver 实例（等价进程重启）—— offset 仍须来自磁盘。
        fresh = new ChunkReceiver("tid-a2", size, Chunk, tempPath)
        state <- fresh.prime()
        onDisk <- IO.blocking(os.size(tempPath))
      yield
        assertEquals(state.bytesReceived, onDisk, "恢复权威 = temp 实际长度")
        assertEquals(state.bytesReceived, Chunk.toLong, "块 0 已落盘 ⇒ offset 一块")
        assertEquals(state.prefixSha256, ChunkedTransfer.hashFileStreaming(tempPath, onDisk), "前缀摘要必须重算")
      end for
    }
  }

  // ===== C. 已落满的续传（P0-2）=====

  test("C1：一块都不发（对端已含整件）⇒ 用接收端重算的摘要收口，不凭自证成功") {
    withDirs { (src, dst) =>
      val size = 150_000L
      for
        source <- makeSource(src, "already.bin", size)
        tempPath = dst / ".already.bin"
        receiver = new ChunkReceiver("tid-c", size, Chunk, tempPath)
        _ <- receiver.prime()
        t1 = new LoopbackTransport(receiver)
        r1 <- ChunkedSendLoop.run(
          t1,
          target("p", "tid-c", "already.bin", size),
          source,
          chunkSize = Chunk,
          retriesPerLeg = 0
        )
        // 第二次：对端已落满 ⇒ 无块可发 + 探针重算摘要 ⇒ 成功收口，且 puts 不增。
        t2 = new LoopbackTransport(receiver)
        r2 <- ChunkedSendLoop.run(
          t2,
          target("p", "tid-c", "already.bin", size),
          source,
          chunkSize = Chunk,
          retriesPerLeg = 0
        )
      yield
        assertEquals(r1.map(_.chunksSent), Right(3))
        r2 match
          case Right(o) =>
            assertEquals(o.chunksSent, 0, "对端已落满 ⇒ 不得重发任何块")
            assertEquals(o.receiverComputedSha256, sha256File(source), "收口摘要必须来自接收端重算")
            assertEquals(t2.puts, 0, "零重传")
          case Left(err) => fail(s"已落满的续传必须成功，实得 ${err.render}")
      end for
    }
  }

  test("C2：已落满但接收端内容被篡改 ⇒ 显式失败（不得凭发送端自算摘要宣布成功）") {
    withDirs { (src, dst) =>
      val size = 150_000L
      for
        source <- makeSource(src, "tamper.bin", size)
        tempPath = dst / ".tamper.bin"
        receiver = new ChunkReceiver("tid-c2", size, Chunk, tempPath)
        _ <- receiver.prime()
        t1 = new LoopbackTransport(receiver)
        _ <- ChunkedSendLoop.run(
          t1,
          target("p", "tid-c2", "tamper.bin", size),
          source,
          chunkSize = Chunk,
          retriesPerLeg = 0
        )
        _ <- IO.blocking {
          val bytes = os.read.bytes(tempPath)
          bytes(0) = (bytes(0) ^ 0x01).toByte
          os.write.over(tempPath, bytes)
        }
        t2 = new LoopbackTransport(receiver)
        r2 <- ChunkedSendLoop.run(
          t2,
          target("p", "tid-c2", "tamper.bin", size),
          source,
          chunkSize = Chunk,
          retriesPerLeg = 0
        )
      yield r2 match
        case Left(err) => assertEquals(err.code, AttachContract.Codes.WholeDigestMismatch)
        case Right(o) =>
          fail(s"篡改后的「已落满」不得被宣布成功（得 bytesSent=${o.bytesSent}）")
      end for
    }
  }

  // ===== D. 进度节拍（P0-4）=====

  test("D1：在飞字节按节拍上报，且与「已确认」分字段（禁混轴）") {
    withDirs { (src, dst) =>
      val size = 300_000L
      for
        source <- makeSource(src, "tick.bin", size)
        tempPath = dst / ".tick.bin"
        receiver = new ChunkReceiver("tid-d", size, Chunk, tempPath)
        _ <- receiver.prime()
        ticks = scala.collection.mutable.ArrayBuffer.empty[(Long, Long)]
        tickNs = scala.collection.mutable.ArrayBuffer.empty[Long]
        acks = scala.collection.mutable.ArrayBuffer.empty[Long]
        rates = scala.collection.mutable.ArrayBuffer.empty[Long]
        t = new LoopbackTransport(receiver, putDelay = 120.millis)
        r <- ChunkedSendLoop.run(
          t,
          target("p", "tid-d", "tick.bin", size),
          source,
          chunkSize = Chunk,
          retriesPerLeg = 0,
          onProgress = (_, received) => IO { acks += received; () },
          // 🔴 节拍的**每次**执行都要重新记一行（本字段曾因 `*>` 按值求值被冻成常量 ——
          //    见 `ChunkedSendLoop.run` 的 `IO.defer` 注）：`tickNs` 让「节拍是否真在跑」可判。
          onInFlight = (acked, inFlight) => IO { ticks += ((acked, inFlight)); tickNs += System.nanoTime(); () },
          progressCadence = 100.millis,
          onRate = rate => IO { rates += rate; () }
        )
      yield
        assert(r.isRight, s"自环必须成功，实得 $r")
        assert(ticks.nonEmpty, s"节拍必须触发（每 ${100}ms 一次），实得 0 次")
        // 时序读数：节拍与块在途的**重叠关系**（诊断用；在飞恒 0 时一眼看出是「节拍与块不重叠」
        // 还是「在飞水位没被推进」）。
        val t0 = tickNs.headOption.getOrElse(0L)
        println(
          s"[READING D1-timing] ticks(ms,inFlight)=${tickNs.zip(ticks).map { case (ns, v) => ((ns - t0) / 1000000L, v._2) }.toList} " +
            s"putStarts(ms)=${t.putLog.map { case (i, ns) => (i, (ns - t0) / 1000000L) }.toList}"
        )
        assert(ticks.exists(_._2 > 0L), s"必须有「在飞 > 0」的拍（否则等于没报在飞）：${ticks.toList}")
        // 分轴：节拍的 acked 只能是**已确认水位**（首拍可等于会话起始 offset = 0 —— 那时
        // 还没有任何块 ack，是合法起点，不是「把在飞算进已确认」）。
        ticks.foreach { case (acked, _) =>
          assert(
            acked == 0L || acks.contains(acked),
            s"节拍 acked=$acked 既不是会话起始 offset 也不是任何已确认水位 $acks"
          )
        }
        // 在飞是**另立字段**：任一拍都不得把在飞并进已确认；两轴之和不得超过整件。
        ticks.foreach { case (acked, inFlight) =>
          assert(acked + inFlight <= size, s"acked($acked)+inFlight($inFlight) 超过整件 $size")
        }
        // 每块 ack 后节拍水位单调不减（同一 transfer 内不可能回退）。
        assertEquals(ticks.map(_._1).toList, ticks.map(_._1).toList.sorted)
        assertEquals(acks.last, size, "末块 ack = totalBytes")
        assert(rates.nonEmpty && rates.forall(_ > 0L), s"每块必须有实测速率读数：${rates.toList}")
        println(s"[READING D1] cadence=100ms ticks=${ticks.size} puts=${t.puts} rates=$rates")
      end for
    }
  }

  // ===== E. 中继腿失败体保结构（P0-3）=====

  test("E1：接收端结构化错误体（FileTransferAction 形态）不被摊平 —— 码/字段逐格保留") {
    withDirs { (src, dst) =>
      val size = 200_000L
      for
        source <- makeSource(src, "relay.bin", size)
        path = dst / "t.bin.dropbox-tid-e"
        // 先落块 0，再请求块 2（空洞）⇒ 接收端回结构化 OFFSET_OUT_OF_RANGE。
        sender <- ChunkSender.open(source, "tid-e", 0L, Chunk)
        c0 <- sender.readNext().map(_.get)
        _ <- IO.blocking(os.write.append(path, c0._2))
        params = io.circe.JsonObject(
          "direction" -> io.circe.Json.fromString("put"),
          "path" -> io.circe.Json.fromString(path.toString),
          "content" -> io.circe.Json.fromString(java.util.Base64.getEncoder.encodeToString(c0._2.clone())),
          "overwrite" -> io.circe.Json.fromBoolean(true),
          "chunkIndex" -> io.circe.Json.fromInt(2),
          "totalBytes" -> io.circe.Json.fromLong(size),
          "chunkSize" -> io.circe.Json.fromInt(Chunk),
          "chunkSha256" -> io.circe.Json.fromString(c0._1.chunkSha256),
          "wholeSha256" -> io.circe.Json.fromString("deadbeef"),
          "transferId" -> io.circe.Json.fromString("tid-e")
        )
        res <- nebflow.neblink.FileTransferAction.handle(params)
      yield res match
        case Left(raw) =>
          val json = io.circe.parser.parse(raw).getOrElse(fail(s"结构化错误体必须是可解析 JSON：$raw"))
          val hc = json.hcursor
          assertEquals(hc.downField("code").as[String].toOption, Some("OFFSET_OUT_OF_RANGE"))
          assertEquals(hc.downField("phase").as[String].toOption, Some("transfer"))
          assertEquals(hc.downField("expectedIndex").as[Int].toOption, Some(1))
          assertEquals(hc.downField("actualIndex").as[Int].toOption, Some(2))
          assert(hc.downField("bytesReceived").as[Long].toOption.contains(Chunk.toLong))
        case Right(ok) => fail(s"空洞块必须被拒，实得成功回执 $ok")
      end for
    }
  }

  test("E2：续传对齐在中继腿同样成立（非整块尾 ⇒ 截尾后 append，而不是 OFFSET_OUT_OF_RANGE）") {
    withDirs { (src, dst) =>
      val size = 200_000L
      for
        source <- makeSource(src, "relay-align.bin", size)
        path = dst / "t2.bin.dropbox-tid-e2"
        sender <- ChunkSender.open(source, "tid-e2", 0L, Chunk)
        c0 <- sender.readNext().map(_.get)
        c1 <- sender.readNext().map(_.get)
        // 块 0 整块 + 块 1 半截（模拟 append 被中断）
        _ <- IO.blocking {
          os.write.append(path, c0._2)
          os.write.append(path, c1._2.take(Chunk / 2))
        }
        params = io.circe.JsonObject(
          "direction" -> io.circe.Json.fromString("put"),
          "path" -> io.circe.Json.fromString(path.toString),
          "content" -> io.circe.Json.fromString(java.util.Base64.getEncoder.encodeToString(c1._2.clone())),
          "overwrite" -> io.circe.Json.fromBoolean(true),
          "chunkIndex" -> io.circe.Json.fromInt(1),
          "totalBytes" -> io.circe.Json.fromLong(size),
          "chunkSize" -> io.circe.Json.fromInt(Chunk),
          "chunkSha256" -> io.circe.Json.fromString(c1._1.chunkSha256),
          "wholeSha256" -> io.circe.Json.fromString("deadbeef"),
          "transferId" -> io.circe.Json.fromString("tid-e2")
        )
        res <- nebflow.neblink.FileTransferAction.handle(params)
        onDisk <- IO.blocking(os.size(path))
      yield res match
        case Right(json) =>
          val bytes = json.hcursor.downField("bytesReceived").as[Long].getOrElse(0L)
          assertEquals(bytes, 2L * Chunk, "截尾后 append ⇒ 落盘恰为 2 块（改前 = 重影 ⇒ 2.5 块）")
          assertEquals(onDisk, 2L * Chunk)
        case Left(err) => fail(s"非整块尾的续传必须成功（截尾修复），实得 $err")
      end for
    }
  }
end XferbChunkParamsSpec
