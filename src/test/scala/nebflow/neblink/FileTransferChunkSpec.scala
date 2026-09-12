package nebflow.neblink

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.dropbox.{AttachContract, ChunkedTransfer}

/**
 * relay 兜底腿的接收端（`FileTransferAction`）—— 硬判据 ②③ 的 relay 面 + **零回归**面。
 *
 * 两件事必须同时成立：
 *   ① **分块形态**：按 offset 追加、幂等重放、gap 拒绝、块/整件摘要**接收端自算**
 *      （今天 relay 腿是整件 base64 + `Right(hash) // hash trivially matches` 自证）；
 *   ② **legacy 整件形态逐字节不变**：不带分块键的 put/get 走原路径（含 `Missing content
 *      for put` / `File exists … use overwrite` 文案）—— 旧发送端零回归。
 */
class FileTransferChunkSpec extends CatsEffectSuite:

  private val Chunk = 16 // 小分块，便于造多块

  private def withTmp[A](f: os.Path => IO[A]): IO[A] =
    IO.blocking(os.temp.dir(prefix = "nb-ftaction-spec-")).flatMap { dir =>
      f(dir).guarantee(IO.blocking(os.remove.all(dir)).handleErrorWith(_ => IO.unit))
    }

  private def b64(bytes: Array[Byte]): String = java.util.Base64.getEncoder.encodeToString(bytes)

  private def putParams(
    path: String,
    payload: Array[Byte],
    index: Int,
    totalBytes: Long,
    wholeSha: String,
    chunkSize: Int = Chunk
  ): JsonObject =
    JsonObject(
      "direction" -> "put".asJson,
      "path" -> path.asJson,
      "content" -> b64(payload).asJson,
      "overwrite" -> true.asJson,
      "chunkIndex" -> index.asJson,
      "totalBytes" -> totalBytes.asJson,
      "chunkSize" -> chunkSize.asJson,
      "chunkSha256" -> ChunkedTransfer.sha256Hex(payload).asJson,
      "wholeSha256" -> wholeSha.asJson
    )

  private def plan(totalBytes: Long): List[AttachContract.ChunkPlan] = AttachContract.plan(totalBytes, Chunk)

  // ===== legacy 整件路径零回归（先跑这一组，证明「保留全量默认语义」）=====

  test("零回归：legacy put（无分块键）行为逐字节不变 —— 写入 + 返回 size") {
    withTmp { dir =>
      val p = dir / "legacy.txt"
      val payload = "hello relay".getBytes("UTF-8")
      for
        res <- FileTransferAction.handle(
          JsonObject(
            "direction" -> "put".asJson,
            "path" -> p.toString.asJson,
            "content" -> b64(payload).asJson,
            "overwrite" -> true.asJson
          )
        )
      yield
        assert(res.isRight, res.toString)
        assertEquals(os.read.bytes(p).toList, payload.toList)
        assertEquals(res.toOption.get.hcursor.downField("size").as[Long].toOption, Some(payload.length.toLong))
    }
  }

  test("零回归：legacy put 无 content ⇒ 原文案 \"Missing content for put\" 不变") {
    withTmp { dir =>
      FileTransferAction
        .handle(JsonObject("direction" -> "put".asJson, "path" -> (dir / "x").toString.asJson))
        .map(res => assertEquals(res, Left("Missing content for put")))
    }
  }

  test("零回归：legacy put 已存在且 overwrite=false ⇒ 原文案 \"File exists … (use overwrite)\"") {
    withTmp { dir =>
      val p = dir / "exists.txt"
      for
        _ <- IO.blocking(os.write(p, "old".getBytes("UTF-8")))
        res <- FileTransferAction.handle(
          JsonObject(
            "direction" -> "put".asJson,
            "path" -> p.toString.asJson,
            "content" -> b64("new".getBytes("UTF-8")).asJson,
            "overwrite" -> false.asJson
          )
        )
      yield
        assert(res.isLeft)
        assert(res.left.toOption.get.contains("use overwrite"), res.left.toOption.get)
        assertEquals(new String(os.read.bytes(p), "UTF-8"), "old", "拒绝后不得覆盖")
    }
  }

  test("零回归：legacy get 返回 base64 + size") {
    withTmp { dir =>
      val p = dir / "getme.txt"
      val payload = "content-for-get".getBytes("UTF-8")
      for
        _ <- IO.blocking(os.write(p, payload))
        res <- FileTransferAction.handle(JsonObject("direction" -> "get".asJson, "path" -> p.toString.asJson))
      yield
        assert(res.isRight, res.toString)
        val hc = res.toOption.get.hcursor
        assertEquals(hc.downField("size").as[Long].toOption, Some(payload.length.toLong))
        assertEquals(
          java.util.Base64.getDecoder.decode(hc.downField("content").as[String].toOption.get).toList,
          payload.toList
        )
    }
  }

  // ===== 分块形态（relay 腿接收端）=====

  test("relay 分块：逐块 put ⇒ 双侧整件摘要一致，块回执带接收端**自算**摘要") {
    withTmp { dir =>
      val p = dir / "chunked.bin"
      val total = 50L
      val src = Array.tabulate(total.toInt)(i => (i * 7 % 251).toByte)
      val whole = ChunkedTransfer.sha256Hex(src)
      val plans = plan(total)
      for
        acks <- plans.foldLeftM(List.empty[Json]) { (acc, cp) =>
          val payload = src.slice(cp.offset.toInt, cp.offset.toInt + cp.bytes)
          FileTransferAction.handle(putParams(p.toString, payload, cp.index, total, whole)).map {
            case Right(json) => acc :+ json
            case Left(err)   => fail(s"chunk ${cp.index} rejected: $err")
          }
        }
      yield
        assertEquals(plans.size, 4, "50 / 16 → 4 块（末块 2 字节）")
        assertEquals(acks.last.hcursor.downField("bytesReceived").as[Long].toOption, Some(total))
        val lastWhole = acks.last.hcursor.downField("wholeSha256").as[String].toOption
        assertEquals(lastWhole, Some(whole), "末块回执必须带接收端自算的整件摘要（禁自证）")
        assertEquals(os.size(p), total)
        assertEquals(ChunkedTransfer.sha256Hex(os.read.bytes(p)), whole)
    }
  }

  test("relay 分块：块字节被篡改 ⇒ CHUNK_DIGEST_MISMATCH，且**不落盘、不推进 offset**") {
    withTmp { dir =>
      val p = dir / "tamper.bin"
      val total = 32L
      val src = Array.tabulate(total.toInt)(i => (i * 3).toByte)
      val whole = ChunkedTransfer.sha256Hex(src)
      val first = src.slice(0, Chunk)
      val tampered = first.clone()
      tampered(0) = (tampered(0) ^ 0x01).toByte
      val params = JsonObject(
        "direction" -> "put".asJson,
        "path" -> p.toString.asJson,
        "content" -> b64(tampered).asJson,
        "overwrite" -> true.asJson,
        "chunkIndex" -> 0.asJson,
        "totalBytes" -> total.asJson,
        "chunkSize" -> Chunk.asJson,
        // 声明的是**正确**的块摘要 ⇒ 只有接收端自算才能发现篡改
        "chunkSha256" -> ChunkedTransfer.sha256Hex(first).asJson,
        "wholeSha256" -> whole.asJson
      )
      for
        res <- FileTransferAction.handle(params)
      yield
        assert(res.isLeft)
        assert(res.left.toOption.get.contains("CHUNK_DIGEST_MISMATCH"), res.left.toOption.get)
        assert(!os.exists(p), "校验失败不得落盘")
    }
  }

  test("relay 分块：整件摘要不符 ⇒ WHOLE_DIGEST_MISMATCH 且**删文件**（绝不 commit）") {
    withTmp { dir =>
      val p = dir / "whole.bin"
      val total = 8L
      val src = Array.tabulate(total.toInt)(i => (i + 1).toByte)
      for
        res <- FileTransferAction.handle(putParams(p.toString, src, 0, total, "0" * 64, chunkSize = 32))
      yield
        assert(res.isLeft)
        assert(res.left.toOption.get.contains("WHOLE_DIGEST_MISMATCH"), res.left.toOption.get)
        assert(!os.exists(p), "整件不符必须删文件")
    }
  }

  test("relay 分块：重复 index ⇒ 幂等 no-op（字节数不增长）") {
    withTmp { dir =>
      val p = dir / "idem.bin"
      val total = 32L
      val src = Array.tabulate(total.toInt)(i => (i * 5).toByte)
      val whole = ChunkedTransfer.sha256Hex(src)
      val first = src.slice(0, Chunk)
      for
        a1 <- FileTransferAction.handle(putParams(p.toString, first, 0, total, whole))
        size1 <- IO.blocking(os.size(p))
        a2 <- FileTransferAction.handle(putParams(p.toString, first, 0, total, whole))
        size2 <- IO.blocking(os.size(p))
      yield
        assert(a1.isRight && a2.isRight, s"重放必须成功：$a1 $a2")
        assertEquals(size1, Chunk.toLong)
        assertEquals(size2, size1, "重放不得增长字节数")
    }
  }

  test("relay 分块：跳号 index ⇒ OFFSET_OUT_OF_RANGE（带 expectedIndex），禁静默缓冲乱序块") {
    withTmp { dir =>
      val p = dir / "gap.bin"
      val total = 48L
      val src = Array.tabulate(total.toInt)(i => (i * 11).toByte)
      val whole = ChunkedTransfer.sha256Hex(src)
      val plans = plan(total)
      val p0 = src.slice(0, Chunk)
      val p2 = src.slice(plans(2).offset.toInt, plans(2).offset.toInt + plans(2).bytes)
      for
        _ <- FileTransferAction.handle(putParams(p.toString, p0, 0, total, whole))
        res <- FileTransferAction.handle(putParams(p.toString, p2, 2, total, whole))
      yield
        assert(res.isLeft)
        assert(res.left.toOption.get.contains("OFFSET_OUT_OF_RANGE"), res.left.toOption.get)
        assert(res.left.toOption.get.contains("expectedIndex"), res.left.toOption.get)
        assertEquals(os.size(p), Chunk.toLong, "gap 拒绝后 offset 不得推进")
    }
  }

  test("relay 探针：direction=probe ⇒ 权威 offset = 落盘实际长度 + 重算前缀摘要") {
    withTmp { dir =>
      val p = dir / "probe.bin"
      val total = 32L
      val src = Array.tabulate(total.toInt)(i => (i * 13).toByte)
      val whole = ChunkedTransfer.sha256Hex(src)
      val first = src.slice(0, Chunk)
      for
        _ <- FileTransferAction.handle(putParams(p.toString, first, 0, total, whole))
        res <- FileTransferAction.handle(
          JsonObject("direction" -> "probe".asJson, "path" -> p.toString.asJson, "totalBytes" -> total.asJson)
        )
      yield
        assert(res.isRight, res.toString)
        val hc = res.toOption.get.hcursor
        assertEquals(hc.downField("bytesReceived").as[Long].toOption, Some(Chunk.toLong))
        assertEquals(
          hc.downField("prefixSha256").as[String].toOption,
          Some(ChunkedTransfer.sha256Hex(first)),
          "前缀摘要必须由盘上内容重算（不信任何持久化字段）"
        )
    }
  }

  test("relay 分块：会话 TTL/孤立文件 —— probe 对不存在的文件报 offset=0（新会话），不报错") {
    withTmp { dir =>
      FileTransferAction
        .handle(
          JsonObject(
            "direction" -> "probe".asJson,
            "path" -> (dir / "never-written.bin").toString.asJson,
            "totalBytes" -> 100.asJson
          )
        )
        .map { res =>
          assert(res.isRight, res.toString)
          assertEquals(res.toOption.get.hcursor.downField("bytesReceived").as[Long].toOption, Some(0L))
        }
    }
  }

  // ===== R4：回执 = 接收端**自算**块摘要（禁回显请求参数）=====

  test("R4：幂等重放回执的 chunkSha256 = 接收端自算（改前回显请求参数 ⇒ 错块静默放行）") {
    withTmp { dir =>
      val p = dir / "r4-replay.bin"
      val total = 32L
      val src = Array.tabulate(total.toInt)(i => (i * 5).toByte)
      val whole = ChunkedTransfer.sha256Hex(src)
      val first = src.slice(0, Chunk)
      // 重放 index 0，但字节被改；声明摘要仍是**正确**那一份（改前回显 ⇒ 发送端 `ack == frame` 恒真）。
      val forged = first.clone()
      forged(0) = (forged(0) ^ 0x01).toByte
      for
        a1 <- FileTransferAction.handle(putParams(p.toString, first, 0, total, whole))
        a2 <- FileTransferAction.handle(putParams(p.toString, forged, 0, total, whole))
        size <- IO.blocking(os.size(p))
      yield
        assert(a1.isRight && a2.isRight, s"重放必须被回执为成功（no-op）：$a1 $a2")
        val hc = a2.toOption.get.hcursor
        assertEquals(
          hc.downField("chunkSha256").as[String].toOption,
          Some(ChunkedTransfer.sha256Hex(forged)),
          "R4：回执摘要必须是收到字节的自算值（改前 = 请求参数 chunkSha256 回显）"
        )
        assertNotEquals(
          hc.downField("chunkSha256").as[String].toOption,
          Some(ChunkedTransfer.sha256Hex(first)),
          "R4 负控：不得回显请求参数（回显会让发送端的比对恒真）"
        )
        assertEquals(size, Chunk.toLong, "重放不得增长字节数")
    }
  }

  // ===== R5：分块 put 在 overwrite=false 下不得「不回写却回执 0 字节」=====

  test("R5：overwrite=false + 目标不存在 ⇒ 显式 INVALID_ARGUMENT（改前：回执 0 字节、首块静默停滞）") {
    withTmp { dir =>
      val p = dir / "r5.bin"
      val total = 32L
      val src = Array.tabulate(total.toInt)(i => (i * 3).toByte)
      val whole = ChunkedTransfer.sha256Hex(src)
      val first = src.slice(0, Chunk)
      def params(index: Int, payload: Array[Byte]) =
        JsonObject(
          "direction" -> "put".asJson,
          "path" -> p.toString.asJson,
          "content" -> b64(payload).asJson,
          "overwrite" -> false.asJson,
          "chunkIndex" -> index.asJson,
          "totalBytes" -> total.asJson,
          "chunkSize" -> Chunk.asJson,
          "chunkSha256" -> ChunkedTransfer.sha256Hex(payload).asJson,
          "wholeSha256" -> whole.asJson
        )
      for
        a0 <- FileTransferAction.handle(params(0, first))
        exists0 <- IO.blocking(os.exists(p))
        a1 <- FileTransferAction.handle(params(1, src.slice(Chunk, Chunk * 2)))
        exists1 <- IO.blocking(os.exists(p))
      yield
        List((0, a0), (1, a1)).foreach { case (i, res) =>
          assert(res.isLeft, s"chunk $i 在 overwrite=false 下必须显式失败，实得 $res")
          assert(res.left.toOption.get.contains("INVALID_ARGUMENT"), res.left.toOption.get)
          assert(res.left.toOption.get.contains("requires overwrite=true"), res.left.toOption.get)
        }
        assert(!exists0 && !exists1, "拒绝路径不得落盘（改前是「不回写却回执 0 字节」）")
    }
  }

end FileTransferChunkSpec
