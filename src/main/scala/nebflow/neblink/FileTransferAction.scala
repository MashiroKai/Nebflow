package nebflow.neblink

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.PathUtil
import nebflow.dropbox.{AttachContract, ChunkedTransfer}

/**
 * Handler for the "FileTransfer" action — direct file IO that bypasses ToolRegistry.
 *
 * Used by both the relay tunnel (NeblinkRelayTunnel.handleRelayRequest) and the
 * P2P remote-exec endpoint (RestApiRoutes) so cross-network file transfers work
 * via relay when the direct P2P HTTP endpoints are unreachable.
 *
 * Params（**legacy 形态，逐字节不变**）:
 *   - direction: "get" (read file, return base64) or "put" (write base64 to file)
 *   - path: file path (relative to project root, or absolute after tilde expansion)
 *   - content: base64-encoded content (required for "put")
 *   - overwrite: whether to overwrite existing file (default false)
 *
 * 附件腿批（2026-09-12）新增**分块形态**（同 action，靠 params 是否带分块键区分）:
 *   - `chunkIndex` / `totalBytes` / `chunkSize` / `chunkSha256` / `wholeSha256` ⇒ 分块 put；
 *   - `direction = "probe"` ⇒ 断点续传探针（返回权威 offset + 重算前缀摘要）。
 *
 * **向后兼容**：不带分块键的 `put` 走的就是原来的整件路径（含 `overwrite` 语义与
 * `Missing content for put` 文案）——旧发送端零回归（兼容矩阵 §3.9）。
 *
 * 语义**收紧**（不变量 I2/I3/I4）：分块 put 必须**接收端自算**块摘要与整件摘要，
 * 禁「内容相同所以摘要相等」的自证；一切失败路径返回结构化错误（不静默）。
 *
 * 通道能力**保留**：本 action、`NeblinkClient.relayTransferGet|Put`、
 * `/api/neblink/transfer`、`NeblinkService.receiveFile|sendFile` 一个不删。
 */
object FileTransferAction:

  def handle(params: JsonObject): IO[Either[String, Json]] =
    val direction = params("direction").flatMap(_.asString).getOrElse("get")
    val pathStr = params("path").flatMap(_.asString).getOrElse("")
    val contentB64 = params("content").flatMap(_.asString).getOrElse("")
    val overwrite = params("overwrite").flatMap(_.asBoolean).getOrElse(false)

    if pathStr.isEmpty then IO.pure(Left("Missing path"))
    else
      val expanded = PathUtil.expandTilde(pathStr)
      val pathEither =
        if PathUtil.isAbsolute(expanded) then
          try Right(PathUtil.resolvePath(expanded))
          catch case e: Exception => Left(s"Invalid path: $expanded")
        else
          try Right(os.pwd / os.RelPath(expanded))
          catch case e: Exception => Left(s"Invalid path: $expanded")

      pathEither match
        case Left(err) => IO.pure(Left(err))
        case Right(path) =>
          direction match
            case "probe" =>
              // 续传探针：权威 offset = 落盘实际长度；前缀摘要**重算**（不信任何持久化字段）。
              IO.blocking {
                val size = if os.exists(path) && os.isFile(path) then os.size(path) else 0L
                Right(
                  Json.obj(
                    "bytesReceived" -> size.asJson,
                    "totalBytes" -> params("totalBytes").flatMap(_.asNumber).flatMap(_.toLong).getOrElse(size).asJson,
                    "prefixSha256" -> ChunkedTransfer.hashFileStreaming(path, size).asJson
                  )
                )
              }.handleErrorWith(e => IO.pure(Left(msgOf(e))))
            case "put" if hasChunkParams(params) =>
              IO.blocking(chunkedPut(params, path, contentB64, overwrite)).handleErrorWith(e => IO.pure(Left(msgOf(e))))
            case _ =>
              // ===== legacy 整件路径（**与今天逐字节一致**）=====
              IO.blocking {
                direction match
                  case "get" =>
                    if !os.exists(path) || !os.isFile(path) then Left(s"File not found: $pathStr")
                    else
                      val content = os.read.bytes(path)
                      val b64 = java.util.Base64.getEncoder.encodeToString(content)
                      Right(Json.obj("content" -> b64.asJson, "size" -> content.length.asJson))
                  case "put" =>
                    if contentB64.isEmpty then Left("Missing content for put")
                    else
                      val content = java.util.Base64.getDecoder.decode(contentB64)
                      if !overwrite && os.exists(path) then Left(s"File exists: $pathStr (use overwrite)")
                      else
                        os.write(path, content, createFolders = true)
                        Right(Json.obj("size" -> content.length.asJson))
                  case other => Left(s"Unknown direction: $other")
              }.handleErrorWith(e => IO.pure(Left(msgOf(e))))

  private def msgOf(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

  private def hasChunkParams(params: JsonObject): Boolean =
    params("chunkIndex").flatMap(_.asNumber).isDefined && params("chunkSize").flatMap(_.asNumber).isDefined

  /**
   * 分块 put：**先校验、再落盘、后推进 offset**。
   *   1. 权威 offset = 落盘实际长度（不信持久化）；
   *   2. 幂等（index < 期望）⇒ no-op 回当前 offset，不重放、不报错；
   *   3. gap（index > 期望）⇒ `OFFSET_OUT_OF_RANGE` + `expectedIndex`；
   *   4. 块摘要不符 ⇒ **不写**、`CHUNK_DIGEST_MISMATCH`；
   *   5. 末块 ⇒ 接收端**自算**整件摘要比对，不符 ⇒ **删文件** + `WHOLE_DIGEST_MISMATCH`。
   */
  private def chunkedPut(
    params: JsonObject,
    path: os.Path,
    contentB64: String,
    overwrite: Boolean
  ): Either[String, Json] =
    if contentB64.isEmpty then Left("Missing content for put")
    else
      val chunkIndex = params("chunkIndex").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(0)
      val totalBytes = params("totalBytes").flatMap(_.asNumber).flatMap(_.toLong).getOrElse(0L)
      val chunkSize = params("chunkSize").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(AttachContract.ChunkSize)
      val chunkSha = params("chunkSha256").flatMap(_.asString).getOrElse("")
      val wholeSha = params("wholeSha256").flatMap(_.asString).getOrElse("")

      val zeroChunks = totalBytes == 0L
      val existing = if os.exists(path) && os.isFile(path) then os.size(path) else 0L
      val alreadyAtOrPastTotal = totalBytes > 0L && existing >= totalBytes
      // 接收端**自算**的块摘要（禁自证，R4）：回执一律回传这个值，不回显 `chunkSha`
      // 请求参数 —— 回显会让发送端的 `ack.chunkSha256 == frame.chunkSha256` 比对恒真。
      val computedChunk = ChunkedTransfer.sha256Hex(java.util.Base64.getDecoder.decode(contentB64))
      if zeroChunks then Right(chunkAckJson(0L, computedChunk, None, totalBytes))
      else if alreadyAtOrPastTotal then
        // 幂等：整件已落盘 ⇒ 回当前 offset + 自算整件摘要（重放安全）。
        Right(chunkAckJson(existing, computedChunk, Some(ChunkedTransfer.hashFileStreaming(path)), totalBytes))
      else if !overwrite && !os.exists(path) then
        // R5：此前的 `chunkAckJson(0L, …)` **不回写却回执 0 字节** ⇒ 首块静默停滞
        // （发送端以为已应用 0 字节，次块因 gap 报 OFFSET_OUT_OF_RANGE）。
        // 分块 put 是「按 offset 追加」语义，本就不该在 overwrite=false 下走 —— 显式拒绝。
        Left(
          AttachContract.AttachError(
            AttachContract.Codes.InvalidArgument,
            s"Chunked put requires overwrite=true (path $path does not exist yet and overwrite=false would silently apply nothing)",
            phase = "transfer",
            chunkIndex = Some(chunkIndex),
            bytesReceived = Some(existing)
          ).toJson.noSpaces
        )
      else
        val expectedIndex = AttachContract.indexForOffset(existing, chunkSize)
        if chunkIndex < expectedIndex then
          // 幂等 no-op：不重放、不报错、不重复写（摘要同样是自算的）。
          Right(chunkAckJson(existing, computedChunk, None, totalBytes))
        else if chunkIndex > expectedIndex then
          Left(
            AttachContract.AttachError(
              AttachContract.Codes.OffsetOutOfRange,
              s"Chunk $chunkIndex arrives out of order: expected index $expectedIndex (bytesReceived=$existing)",
              phase = "transfer",
              chunkIndex = Some(chunkIndex),
              expectedIndex = Some(expectedIndex),
              actualIndex = Some(chunkIndex),
              bytesReceived = Some(existing)
            ).toJson.noSpaces
          )
        else
          val payload = java.util.Base64.getDecoder.decode(contentB64)
          // 帧自洽（offset 由 index 推导；bytes 符合计划）
          val derivedOffset = AttachContract.offsetForIndex(chunkIndex, chunkSize)
          val expectedBytes = math.min(chunkSize.toLong, totalBytes - derivedOffset)
          if derivedOffset != existing then
            Left(
              AttachContract.AttachError(
                AttachContract.Codes.OffsetOutOfRange,
                s"Chunk $chunkIndex declares offset $derivedOffset but the target already holds $existing bytes",
                phase = "transfer",
                chunkIndex = Some(chunkIndex),
                expected = Some(existing.toString),
                actual = Some(derivedOffset)
              ).toJson.noSpaces
            )
          else if expectedBytes != payload.length.toLong then
            Left(
              AttachContract.AttachError(
                AttachContract.Codes.OffsetOutOfRange,
                s"Chunk $chunkIndex carries ${payload.length} bytes but the plan says $expectedBytes",
                phase = "transfer",
                chunkIndex = Some(chunkIndex),
                expected = Some(expectedBytes.toString),
                actual = Some(payload.length.toLong)
              ).toJson.noSpaces
            )
          else
            // **接收端自算**块摘要（禁自证）
            val computedChunk = ChunkedTransfer.sha256Hex(payload)
            if computedChunk != chunkSha then
              Left(
                AttachContract.AttachError(
                  AttachContract.Codes.ChunkDigestMismatch,
                  s"Chunk $chunkIndex digest mismatch: declared $chunkSha, computed $computedChunk",
                  phase = "transfer",
                  chunkIndex = Some(chunkIndex),
                  expected = Some(chunkSha),
                  actualHash = Some(computedChunk)
                ).toJson.noSpaces
              )
            else
              os.write.append(path, payload)
              val nowBytes = existing + payload.length
              if nowBytes >= totalBytes then
                // 唯一比对点：自算整件摘要（流式），不符 ⇒ 删文件（绝不 commit）。
                val computedWhole = ChunkedTransfer.hashFileStreaming(path)
                if computedWhole != wholeSha then
                  os.remove.all(path)
                  Left(
                    AttachContract.AttachError(
                      AttachContract.Codes.WholeDigestMismatch,
                      s"Whole-file digest mismatch: declared $wholeSha, receiver computed $computedWhole",
                      phase = "commit",
                      expected = Some(wholeSha),
                      actualHash = Some(computedWhole),
                      bytesReceived = Some(nowBytes)
                    ).toJson.noSpaces
                  )
                else Right(chunkAckJson(nowBytes, computedChunk, Some(computedWhole), totalBytes))
              else Right(chunkAckJson(nowBytes, computedChunk, None, totalBytes))

  private def chunkAckJson(
    bytesReceived: Long,
    chunkSha256: String,
    wholeSha256: Option[String],
    totalBytes: Long
  ): Json =
    Json.obj(
      "size" -> bytesReceived.asJson,
      "bytesReceived" -> bytesReceived.asJson,
      "totalBytes" -> totalBytes.asJson,
      "chunkSha256" -> chunkSha256.asJson,
      "wholeSha256" -> wholeSha256.map(_.asJson).getOrElse(Json.Null)
    )

end FileTransferAction
