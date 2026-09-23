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
      for res <- FileTransferAction.handle(
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
      for acks <- plans.foldLeftM(List.empty[Json]) { (acc, cp) =>
          val payload = src.slice(cp.offset.toInt, cp.offset.toInt + cp.bytes)
          FileTransferAction.handle(putParams(p.toString, payload, cp.index, total, whole)).map {
            case Right(json) => acc :+ json
            case Left(err) => fail(s"chunk ${cp.index} rejected: $err")
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
      for res <- FileTransferAction.handle(params)
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
      for res <- FileTransferAction.handle(putParams(p.toString, src, 0, total, "0" * 64, chunkSize = 32))
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
      end for
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

  // ===== 收端全保护（dropnam 批 · 判据⑤b/⑤d；作者 2026-09-19 裁定②「取接收侧全保护」）=====
  //
  // 改前形态（真漏 B 的破坏面，方案件 D3/D4）：
  //   - 目标已有 `k × chunkSize` 字节且 < 来件 total ⇒ 发送端从该 offset 续传
  //     ⇒ 接收端 `os.write.append` **污染别人的件** ⇒ 末块自算整件摘要不符 ⇒
  //     `os.remove.all(path)` **删掉对端原件**（生产块长 4 MiB 下同样触发）；
  //   - 目标字节数 ≥ 来件 total ⇒ 回**成功 ack**（零字节写入却报 100%，回执不诚实）。
  // 本组钉「显式拒绝 + 零写零删」。

  test("⑤d tokenless 撞已有件 ⇒ FILE_EXISTS_REFUSING_APPEND（显式 Left、零写、零删）"):
    withTmp { dir =>
      val p = dir / "pre.bin"
      // 既有件 = 1 × Chunk（= D3 形态的触发前提：整数倍块长且 < 来件 total）
      val prior = Array.tabulate(Chunk)(i => (i * 3 + 1).toByte)
      val incoming = Array.tabulate(Chunk * 2)(i => (i * 7 + 5).toByte)
      val whole = ChunkedTransfer.sha256Hex(incoming)
      for
        _ <- IO.blocking(os.write(p, prior))
        before <- IO.blocking(ChunkedTransfer.sha256Hex(os.read.bytes(p)))
        sizeBefore <- IO.blocking(os.size(p))
        // 首块（index 0）撞已有件：改前 = 幂等 no-op 回**成功 ack**（进度面假 100%）
        r0 <- FileTransferAction.handle(putParams(p.toString, incoming.slice(0, Chunk), 0, Chunk * 2L, whole))
        // 续传起点（index 1 == expectedIndex）：改前 = 追加后整件不符 ⇒ os.remove.all **删掉对端原件**
        r1 <- FileTransferAction.handle(
          putParams(p.toString, incoming.slice(Chunk, Chunk * 2), 1, Chunk * 2L, whole)
        )
        // 既有件状态读数（禁「件已消失 ⇒ os.size 抛异常」把读数吃掉）：消失时给出哨兵，
        // 并在下面的失败告警里**一并回带** ⇒ 变异验红时 ⑤b 的读数（件消失 / 字节被改）可读。
        exists <- IO.blocking(os.exists(p))
        after <- IO.blocking(if os.exists(p) then ChunkedTransfer.sha256Hex(os.read.bytes(p)) else "<file-gone>")
        sizeAfter <- IO.blocking(if os.exists(p) then os.size(p) else -1L)
      yield
        val state = s"既有件状态：exists=$exists size=$sizeAfter sha=${if after == before then "unchanged" else after}"
        List(r0, r1).foreach { r =>
          assert(r.isLeft, s"tokenless 撞已有件必须显式失败（禁静默 append / 禁删件），实得 $r；$state")
          assert(r.left.toOption.get.contains("FILE_EXISTS_REFUSING_APPEND"), r.left.toOption.get)
        }
        assert(exists, "⑤b 既有件必须仍在（禁 os.remove.all）")
        assertEquals(after, before, "⑤b 既有件字节必须逐字不变")
        assertEquals(sizeAfter, sizeBefore, "拒绝路径不得写入任何字节")
      end for
    }

  test("⑤d 归属明确（transferId token 与路径内嵌 <tid8> 相符）⇒ 同一路径可正常分块续传"):
    withTmp { dir =>
      val tid = "tid-abc-1234567890"
      val p = dir / s".chunked.bin.dropbox-${tid.take(8)}"
      val total = Chunk * 2L
      val src = Array.tabulate(total.toInt)(i => (i * 9 + 2).toByte)
      val whole = ChunkedTransfer.sha256Hex(src)
      def withToken(index: Int, payload: Array[Byte]): JsonObject =
        putParams(p.toString, payload, index, total, whole).add("transferId", tid.asJson)
      for
        a0 <- FileTransferAction.handle(withToken(0, src.slice(0, Chunk)))
        a1 <- FileTransferAction.handle(withToken(1, src.slice(Chunk, Chunk * 2)))
        size <- IO.blocking(os.size(p))
      yield
        assert(a0.isRight, a0.toString)
        assert(a1.isRight, a1.toString)
        assertEquals(a1.toOption.get.hcursor.downField("wholeSha256").as[String].toOption, Some(whole))
        assertEquals(size, total, "归属明确 ⇒ 追加路径与今天一致")
    }

  test("裁定② legacy 整件（`overwrite=true`）撞已有件 ⇒ 改名保留新件、原件零损"):
    withTmp { dir =>
      val p = dir / "legacy-exists.txt"
      val prior = "ORIGINAL-CONTENT-KEEP-ME".getBytes("UTF-8")
      val fresh = "NEW-CONTENT-FROM-PEER".getBytes("UTF-8")
      for
        _ <- IO.blocking(os.write(p, prior))
        res <- FileTransferAction.handle(
          JsonObject(
            "direction" -> "put".asJson,
            "path" -> p.toString.asJson,
            "content" -> b64(fresh).asJson,
            "overwrite" -> true.asJson
          )
        )
        names <- IO.blocking(os.list(dir).map(_.last).sorted.toList)
        originalNow <- IO.blocking(new String(os.read.bytes(p), "UTF-8"))
        freshName = names.find(_ != "legacy-exists.txt")
        freshNow <- IO.blocking(freshName.map(n => new String(os.read.bytes(dir / n), "UTF-8")))
      yield
        assert(res.isRight, res.toString)
        assertEquals(originalNow, "ORIGINAL-CONTENT-KEEP-ME", "既有件必须逐字不变（禁 truncate 覆盖）")
        assertEquals(freshNow, Some("NEW-CONTENT-FROM-PEER"), s"新件必须以新名落盘并保留内容：$names")
        assertEquals(names.size, 2, s"应恰有既有件 + 新件两名：$names")
      end for
    }

end FileTransferChunkSpec
