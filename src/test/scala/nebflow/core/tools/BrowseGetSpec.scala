package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.dropbox.ChunkedTransfer

import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * M2 浏览取件分块拉取腿（设计 v1 §7 M2-1/2）——收端游标 + 拉取循环 + 取消登记表。
 *
 * 质量闸映射（本批四条 spec 域之二）：
 *   - **分块校验**：块摘要接收端自算比对（不符 = 显式错误 + 零写盘）；
 *   - **续传**：落盘长度为权威 offset、非整块尾截断对齐、重试从续传点继续；
 *   - **取消**：旗标在块间生效（`BrowseFetchRegistry`）+ 终态哨兵区别于传输失败；
 *   - **整件收口**：接收端流式自算 vs 对端声明，不符 ⇒ 删落盘件。
 */
class BrowseGetSpec extends CatsEffectSuite:

  private val Chunk = 16 // 小分块（字节级可手算）

  private def b64(bytes: Array[Byte]): String = Base64.getEncoder.encodeToString(bytes)

  private def payload(n: Int, seed: Byte): Array[Byte] =
    Array.tabulate(n)(i => ((i + seed) % 251).toByte)

  /** 服务端同款切片响应（与 FileTransferAction.chunkedGet 同形，spec 内自给替身）。 */
  private def chunkReply(total: Array[Byte], index: Int, chunkSize: Int, wholeSha: Option[String]): BrowseGetCursor.ChunkReply =
    val offset = index.toLong * chunkSize
    val slice = total.drop(offset.toInt).take(chunkSize)
    BrowseGetCursor.ChunkReply(
      chunked = true,
      chunkIndex = index,
      chunkBytes = slice.length,
      chunkSha256 = ChunkedTransfer.sha256Hex(slice),
      totalBytes = total.length.toLong,
      totalChunks = ((total.length + chunkSize - 1) / chunkSize).toLong,
      wholeSha256 = wholeSha,
      contentB64 = b64(slice)
    )

  /** 测试体包装：临时目录生命周期（guarantee 直接挂被测 IO——禁 IO.blocking(f) 的
    * 嵌套 IO 形态：那会让清理先于内层 IO 执行，目录先删后测）。 */
  private def withTmp[A](f: os.Path => IO[A]): IO[A] =
    IO.blocking(os.temp.dir(prefix = "nb-browseget-spec-")).flatMap { dir =>
      f(dir).guarantee(IO.blocking(os.remove.all(dir)).handleError(_ => ()))
    }

  // ===== 游标本体：分块校验 / 续传对齐 / 整件收口 =====

  test("applyChunk：逐块校验（本侧自算）——摘要不符不写盘、显式错误") {
    withTmp { dir =>
      IO {
      val target = dir / "pickups" / "a.bin"
      val cursor = new BrowseGetCursor(target, totalBytes = 32, chunkSize = Chunk)
      cursor.restore()
      val good = payload(Chunk, 1)
      val bad = payload(Chunk, 2)
      // 声明摘要用 good 的、字节用 bad 的 ⇒ 本侧自算必判不符。
      val err = cursor.applyChunk(0, bad, ChunkedTransfer.sha256Hex(good))
      assert(err.isLeft, "digest mismatch must be rejected")
      assert(!os.exists(target), "no bytes may land on digest mismatch")
      // 正确块照常落盘。
      val ok = cursor.applyChunk(0, good, ChunkedTransfer.sha256Hex(good))
      assertEquals(ok, Right(Chunk.toLong))
      assertEquals(os.size(target), Chunk.toLong)
      }
    }
  }

  test("applyChunk：计划形状——字节数与计划不符拒绝（末块短字节/超发同判）") {
    withTmp { dir =>
      IO {
      val target = dir / "pickups" / "b.bin"
      val cursor = new BrowseGetCursor(target, totalBytes = 40, chunkSize = Chunk)
      cursor.restore()
      val short = payload(8, 3) // 首块应为 16 字节，只给了 8
      val r = cursor.applyChunk(0, short, ChunkedTransfer.sha256Hex(short))
      assert(clue(r).isLeft, "plan-shape mismatch must be rejected")
      }
    }
  }

  test("续传：非整块尾截断对齐——从续传点继续，同段字节不落两遍") {
    withTmp { dir =>
      IO {
      val target = dir / "pickups" / "c.bin"
      val total = payload(40, 5)
      // 模拟上次中断：前 16 字节（整块）+ 5 字节尾巴已落盘。
      os.makeDir.all(target / os.up)
      os.write.over(target, total.take(Chunk + 5))
      val cursor = new BrowseGetCursor(target, totalBytes = total.length.toLong, chunkSize = Chunk)
      cursor.restore()
      // 权威 offset = 落盘长度夹到整件内；期望块 index = 21/16 = 1。
      assertEquals(cursor.expectedIndex, 1)
      // 应用块 1（字节 16..32）：先截断到 16 再追加 ⇒ 尾巴不会重复。
      val slice1 = total.slice(Chunk, 2 * Chunk)
      assertEquals(cursor.applyChunk(1, slice1, ChunkedTransfer.sha256Hex(slice1)), Right(32L))
      assertEquals(os.size(target), 32L)
      assertEquals(os.read.bytes(target).toSeq, total.take(32).toSeq, "bytes must match source prefix exactly")
      // 幂等重放（index < 期望）：no-op 不报错。
      assertEquals(cursor.applyChunk(0, total.take(Chunk), ChunkedTransfer.sha256Hex(total.take(Chunk))), Right(32L))
      }
    }
  }

  test("verifyWhole：整件摘要不符 ⇒ 删落盘件 + 显式错误；相符 ⇒ 收口") {
    withTmp { dir =>
      IO {
      val target = dir / "pickups" / "d.bin"
      val total = payload(32, 7)
      os.makeDir.all(target / os.up)
      os.write.over(target, total)
      val cursor = new BrowseGetCursor(target, totalBytes = 32, chunkSize = Chunk)
      cursor.restore()
      cursor.noteWholeSha("deadbeef" * 8)
      val bad = cursor.verifyWhole()
      assert(bad.isLeft, "declared-vs-computed mismatch must fail")
      assert(bad.left.toOption.get.contains("WHOLE_DIGEST_MISMATCH"), "error must be the whole-mismatch shape")
      assert(!os.exists(target), "mismatched pickup must be deleted (never commit)")
      // 相符路径：重建后以真摘要收口。
      os.write.over(target, total)
      cursor.noteWholeSha(ChunkedTransfer.hashFileStreaming(target))
      assertEquals(cursor.verifyWhole(), Right(ChunkedTransfer.hashFileStreaming(target)))
      }
    }
  }

  // ===== 拉取循环：多块 / 续传 / 取消 / legacy / 超限 =====

  test("BrowseGetLoop：多块拉取全链——逐块进度 + 整件收口 + 字节精确") {
    withTmp { dir =>
      val target = dir / "pickups" / "e.bin"
      val total = payload(50, 9)
      val wholeSha = ChunkedTransfer.sha256Hex(total)
      /** @type Array[Int] */ val requested = new scala.collection.mutable.ArrayBuffer[Int]()
      val fetch: (Int, Int, Boolean) => IO[Either[String, BrowseGetCursor.ChunkReply]] = (idx, cs, wantWhole) =>
        IO {
          requested += idx
          if wantWhole then Right(chunkReply(total, idx, cs, Some(wholeSha)))
          else Right(chunkReply(total, idx, cs, None))
        }
      /** @type Array[(Long, Long)] */ val progress = new scala.collection.mutable.ArrayBuffer[(Long, Long)]()
      BrowseGetLoop
        .run(target, fetch, (b, t, _) => IO { progress += ((b, t)) }, new AtomicBoolean(false), startChunkSize = Chunk)
        .map {
          case Right((path, size, sha)) =>
            assertEquals(size, 50L)
            assertEquals(sha, wholeSha)
            assertEquals(os.read.bytes(path).toSeq, total.toSeq, "assembled bytes must equal source")
            assert(progress.nonEmpty && progress.last == ((50L, 50L)), "final progress must report completion")
            assert(requested.distinct.size == requested.size, "no chunk may be fetched twice on the happy path")
          case other => fail(s"expected a completed fetch, got: $other")
        }
    }
  }

  test("BrowseGetLoop：断点续传——既有部分件只补尾块，重试从续传点继续") {
    withTmp { dir =>
      val target = dir / "pickups" / "f.bin"
      val total = payload(50, 11)
      val wholeSha = ChunkedTransfer.sha256Hex(total)
      // 上会话残留：整块 0（16B）+ 5 字节**非整块尾**已落盘。
      os.makeDir.all(target / os.up)
      os.write.over(target, total.take(Chunk + 5))
      /** @type Array[Int] */ val requested = new scala.collection.mutable.ArrayBuffer[Int]()
      val fetch: (Int, Int, Boolean) => IO[Either[String, BrowseGetCursor.ChunkReply]] = (idx, cs, wantWhole) =>
        IO {
          requested += idx
          Right(chunkReply(total, idx, cs, Option.when(wantWhole)(wholeSha)))
        }
      BrowseGetLoop
        .run(target, fetch, (_, _, _) => IO.unit, new AtomicBoolean(false), startChunkSize = Chunk)
        .map {
          case Right((path, size, _)) =>
            assertEquals(size, 50L)
            assertEquals(os.read.bytes(path).toSeq, total.toSeq)
            assert(
              !requested.contains(0),
              s"chunk 0 already on disk must NOT be re-fetched (resume, not restart): requested=$requested"
            )
            assertEquals(requested.head, 1, "first request must be the resume chunk (floor(bytesOnDisk / chunk))")
          case other => fail(s"expected a completed fetch, got: $other")
        }
    }
  }

  test("BrowseGetLoop：取消旗标块间生效——Cancelled 哨兵（区别于传输失败）+ 已落字节保留") {
    withTmp { dir =>
      val target = dir / "pickups" / "g.bin"
      val total = payload(50, 13)
      val wholeSha = ChunkedTransfer.sha256Hex(total)
      val flag = new AtomicBoolean(false)
      val fetch: (Int, Int, Boolean) => IO[Either[String, BrowseGetCursor.ChunkReply]] = (idx, cs, wantWhole) =>
        IO {
          // 拉取块 1 的途中翻旗标 ⇒ 块 1 落盘后循环必须停（粒度 = 块间）。
          if idx >= 1 then flag.set(true)
          Right(chunkReply(total, idx, cs, Option.when(wantWhole)(wholeSha)))
        }
      BrowseGetLoop
        .run(target, fetch, (_, _, _) => IO.unit, flag, startChunkSize = Chunk)
        .map {
          case Left(err) =>
            assertEquals(err, BrowseGetCursor.Cancelled, "cancel must surface the Cancelled sentinel, not a generic error")
            assertEquals(os.size(target), (Chunk * 2).toLong, "bytes already landed must be preserved for resume")
          case other => fail(s"expected the Cancelled sentinel, got: $other")
        }
    }
  }

  test("BrowseGetLoop：块摘要坏块重试后成功——同 index 重拉，续传语义收口") {
    withTmp { dir =>
      val target = dir / "pickups" / "h.bin"
      val total = payload(40, 15)
      val wholeSha = ChunkedTransfer.sha256Hex(total)
      /** @type Array[Int] */ val idxHits = new scala.collection.mutable.ArrayBuffer[Int]()
      val fetch: (Int, Int, Boolean) => IO[Either[String, BrowseGetCursor.ChunkReply]] = (idx, cs, wantWhole) =>
        IO {
          idxHits += idx
          val reply = chunkReply(total, idx, cs, Option.when(wantWhole)(wholeSha))
          if idx == 1 && idxHits.count(_ == idx) == 1 then
            // 第一次给坏摘要（模拟传输损伤）——接收端自算必拒，重试同块。
            Right(reply.copy(chunkSha256 = "0" * 64))
          else Right(reply)
        }
      BrowseGetLoop
        .run(target, fetch, (_, _, _) => IO.unit, new AtomicBoolean(false), startChunkSize = Chunk)
        .map {
          case Right((path, size, _)) =>
            assertEquals(size, 40L)
            assertEquals(os.read.bytes(path).toSeq, total.toSeq, "retry-from-resume-point must land exact bytes")
            assertEquals(idxHits.count(_ == 1), 2, "bad chunk must be re-fetched at the same index")
          case other => fail(s"expected a completed fetch, got: $other")
        }
    }
  }

  test("BrowseGetLoop：legacy 整件回应（旧对端）= 单发消费 + 本侧自算整件摘要") {
    withTmp { dir =>
      val target = dir / "pickups" / "i.bin"
      val total = payload(24, 17)
      val legacy = BrowseGetCursor.ChunkReply(
        chunked = false, chunkIndex = 0, chunkBytes = 0, chunkSha256 = "",
        totalBytes = total.length.toLong, totalChunks = 1, wholeSha256 = None,
        contentB64 = b64(total)
      )
      BrowseGetLoop
        .run(target, (_, _, _) => IO.pure(Right(legacy)), (_, _, _) => IO.unit, new AtomicBoolean(false), startChunkSize = Chunk)
        .map {
          case Right((path, size, sha)) =>
            assertEquals(size, 24L)
            assertEquals(sha, ChunkedTransfer.sha256Hex(total), "whole sha must be computed receiver-side even on the legacy leg")
            assertEquals(os.read.bytes(path).toSeq, total.toSeq)
          case other => fail(s"expected a completed legacy fetch, got: $other")
        }
    }
  }

  test("BrowseGetLoop：整件读数超 50MiB 闸 ⇒ 显式报错引导 dropbox（分块/legacy 同判）") {
    withTmp { dir =>
      val target = dir / "pickups" / "j.bin"
      val overCap = RemoteExecutor.MaxScopedFetchBytes + 1
      val reply = BrowseGetCursor.ChunkReply(
        chunked = true, chunkIndex = 0, chunkBytes = 0, chunkSha256 = "x",
        totalBytes = overCap, totalChunks = 999, wholeSha256 = None, contentB64 = ""
      )
      BrowseGetLoop
        .run(target, (_, _, _) => IO.pure(Right(reply)), (_, _, _) => IO.unit, new AtomicBoolean(false), startChunkSize = Chunk)
        .map {
          case Left(err) =>
            assert(err.contains("use explicit batch transfer"), "cap error must steer to the dropbox channel")
          case other => fail(s"expected the size-cap error, got: $other")
        }
    }
  }

  // ===== 取消登记表（WS fileTransferCancel 的后端账）=====

  test("BrowseFetchRegistry：register/cancel/isActive 会话周期账——旗标翻转让循环停") {
    withTmp { dir =>
      IO {
      val (f1, un1) = BrowseFetchRegistry.register("dev-1", s"$dir/x.png")
      assert(BrowseFetchRegistry.isActive("dev-1", s"$dir/x.png"))
      assert(!f1.get())
      assertEquals(BrowseFetchRegistry.cancel("dev-1", s"$dir/x.png"), true)
      assert(f1.get(), "cancel must flip the registered flag")
      assertEquals(BrowseFetchRegistry.cancel("dev-1", s"$dir/x.png"), true, "cancel on an active fetch stays true (flag already set)")
      un1.unsafeRunSync()
      assertEquals(BrowseFetchRegistry.isActive("dev-1", s"$dir/x.png"), false, "unregister frees the ledger slot")
      // 无在途：cancel 幂等 false（不误报）。
      assertEquals(BrowseFetchRegistry.cancel("dev-1", s"$dir/x.png"), false)
      }
    }
  }

end BrowseGetSpec
