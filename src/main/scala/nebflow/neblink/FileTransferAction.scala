package nebflow.neblink

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.PathUtil
import nebflow.dropbox.{AttachContract, ChunkedTransfer, DropboxUtil}

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
                      else legacyPutPreservingExisting(path, content)
                  case other => Left(s"Unknown direction: $other")
              }.handleErrorWith(e => IO.pure(Left(msgOf(e))))

      end match

    end if

  end handle

  /**
   * legacy 整件 put 的**撞名保护**（dropnam 批 · 作者 2026-09-19 裁定② 明确要求本分支也在保护面内）：
   *   - 目标不存在（或不是普通文件）⇒ 与今天**逐字节一致**（原样写入 / 原样报错）；
   *   - 目标**是已存在的普通文件** ⇒ **改名保留新件、原件零损** —— 既有的 `os.write` 会
   *     truncate 覆盖它（分块腿的 `os.remove.all` 与它同族），本函数改为写入一个
   *     **冲突无关的新名**（唯一算名点 `DropboxUtil.occupyConflictFreeName`，名字生成仍只在
   *     `DropboxUtil.finalNameCandidate` 一处）。
   *
   * ⚠️ 已知面（登记，非静默）：改写后的落点**不是**调用方请求的那个名字（这正是「原件零损」的
   * 代价）；收端台账/通报面因该腿不经 `commitTempFile` 而无从观测此新名 ⇒ 该兼容格的
   * `savedPath` 为空串（前端保持不可点）。数据零损优先。
   */
  private def legacyPutPreservingExisting(path: os.Path, content: Array[Byte]): Either[String, Json] =
    if !os.exists(path) || !os.isFile(path) then
      os.write(path, content, createFolders = true)
      Right(Json.obj("size" -> content.length.asJson))
    else
      DropboxUtil.occupyConflictFreeName(path / os.up, path.last, java.time.ZonedDateTime.now()) match
        case Left(reason) => Left(reason)
        case Right(fresh) =>
          try
            os.write.over(fresh, content)
            Right(Json.obj("size" -> content.length.asJson))
          catch
            case e: Exception =>
              // 刚占据的 0 字节新名 ⇒ 清掉（既有件自始至终未被触碰）。
              try os.remove(fresh)
              catch case _: Exception => ()
              Left(msgOf(e))

  private def msgOf(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

  private def hasChunkParams(params: JsonObject): Boolean =
    params("chunkIndex").flatMap(_.asNumber).isDefined && params("chunkSize").flatMap(_.asNumber).isDefined

  // ===== 归属判据（dropnam 批 · 收端全保护）=====
  //
  // 问题：分块 put 是「按 offset 追加」语义，而它的目标路径**由发送端指定**。无 token 时，
  // 「目标上已有的字节」既可能是本次续传的前缀，也可能是**别人的件**：
  //   - 追加到别人的件上 = 污染它；
  //   - 末块整件摘要不符 ⇒ `os.remove.all(path)` 删掉的是**别人的件**（生产块长 4 MiB 下
  //     「既有件 = k × 4 MiB 且 < 来件 total」即触发，见方案件 D3/D4）。
  // 收端**永不**覆盖/删除既有件（作者 2026-09-19 裁定②）⇒ 只有**归属明确**的路径才可写/可清。

  /**
   * 进程内 claim 表：记录「本进程在某路径上**从零开始**写入过的那条流」。
   * 跨重启失效（重启后旧流本就断了 ⇒ 保守方向 = 对已存在目标 fail-closed 拒绝）。
   * 键 = 路径字符串；值 = 流标识（`totalBytes/chunkSize`）。完成/失败清理时移除。
   */
  private val streamClaims = new java.util.concurrent.ConcurrentHashMap[String, String]()

  private def streamKey(totalBytes: Long, chunkSize: Int): String = s"$totalBytes/$chunkSize"

  /** 请求自带的归属 token（可选键）。旧接收端忽略未知键 ⇒ 向后兼容。 */
  private def transferIdToken(params: JsonObject): Option[String] =
    params("transferId").flatMap(_.asString).map(_.trim).filter(_.nonEmpty)

  /**
   * 归属判据：① 请求自带 `transferId`，且**路径里内嵌的 `<tid8>` 与之相符**
   * （路径自证 —— 落名本身由发送端从 transferId 生成，声明与路径同源）；
   * ② 或本进程曾在该路径上从零写入过同一条流（无 token 的合法多块续传）。
   */
  private def pathOwnedBy(params: JsonObject, path: os.Path, totalBytes: Long, chunkSize: Int): Boolean =
    val tokenOwns = transferIdToken(params).exists(tid => path.last.endsWith(s".dropbox-${tid.take(8)}"))
    tokenOwns || Option(streamClaims.get(path.toString)).contains(streamKey(totalBytes, chunkSize))

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
      val owned = pathOwnedBy(params, path, totalBytes, chunkSize)
      // 本流**从零创建**该文件 ⇒ 本流拥有它（与 `owned` 合起来 = 「这个文件是我们的」）。
      val ownsNow = owned || existing == 0L
      // 接收端**自算**的块摘要（禁自证，R4）：回执一律回传这个值，不回显 `chunkSha`
      // 请求参数 —— 回显会让发送端的 `ack.chunkSha256 == frame.chunkSha256` 比对恒真。
      val computedChunk = ChunkedTransfer.sha256Hex(java.util.Base64.getDecoder.decode(contentB64))
      if existing > 0L && !owned then
        // 🔴 收端全保护（裁定②）：目标上已有非空文件，而本次请求**声明不了归属**
        // ⇒ 那不是「本次的续传」，是**别人的件** ⇒ 显式拒绝：零写、零删、结构化原因。
        // （改前：D1 形态回**成功 ack**（进度面 100%、零字节写入）；D3/D4 形态 append 污染后
        //   `os.remove.all` **删掉对端原件** —— 两者都是本批要消灭的静默失败。）
        Left(
          AttachContract
            .AttachError(
              AttachContract.Codes.FileExistsRefusingAppend,
              s"Refusing to append to existing $path ($existing bytes): this request declares no ownership of it " +
                "(no matching transferId token, and this process did not create that file) — the existing file is left untouched (zero write, zero delete)",
              phase = "transfer",
              chunkIndex = Some(chunkIndex),
              bytesReceived = Some(existing),
              expected = Some(totalBytes.toString),
              path = Some(path.toString)
            )
            .toJson
            .noSpaces
        )
      else if zeroChunks then Right(chunkAckJson(0L, computedChunk, None, totalBytes))
      else if alreadyAtOrPastTotal then
        // 幂等：整件已落盘 ⇒ 回当前 offset + 自算整件摘要（重放安全）。
        Right(chunkAckJson(existing, computedChunk, Some(ChunkedTransfer.hashFileStreaming(path)), totalBytes))
      else if !overwrite && !os.exists(path) then
        // R5：此前的 `chunkAckJson(0L, …)` **不回写却回执 0 字节** ⇒ 首块静默停滞
        // （发送端以为已应用 0 字节，次块因 gap 报 OFFSET_OUT_OF_RANGE）。
        // 分块 put 是「按 offset 追加」语义，本就不该在 overwrite=false 下走 —— 显式拒绝。
        Left(
          AttachContract
            .AttachError(
              AttachContract.Codes.InvalidArgument,
              s"Chunked put requires overwrite=true (path $path does not exist yet and overwrite=false would silently apply nothing)",
              phase = "transfer",
              chunkIndex = Some(chunkIndex),
              bytesReceived = Some(existing)
            )
            .toJson
            .noSpaces
        )
      else
        val expectedIndex = AttachContract.indexForOffset(existing, chunkSize)
        if chunkIndex < expectedIndex then
          // 幂等 no-op：不重放、不报错、不重复写（摘要同样是自算的）。
          Right(chunkAckJson(existing, computedChunk, None, totalBytes))
        else if chunkIndex > expectedIndex then
          Left(
            AttachContract
              .AttachError(
                AttachContract.Codes.OffsetOutOfRange,
                s"Chunk $chunkIndex arrives out of order: expected index $expectedIndex (bytesReceived=$existing)",
                phase = "transfer",
                chunkIndex = Some(chunkIndex),
                expectedIndex = Some(expectedIndex),
                actualIndex = Some(chunkIndex),
                bytesReceived = Some(existing)
              )
              .toJson
              .noSpaces
          )
        else
          val payload = java.util.Base64.getDecoder.decode(contentB64)
          // 帧自洽（offset 由 index 推导；bytes 符合计划）
          val derivedOffset = AttachContract.offsetForIndex(chunkIndex, chunkSize)
          val expectedBytes = math.min(chunkSize.toLong, totalBytes - derivedOffset)
          if derivedOffset > existing then
            Left(
              AttachContract
                .AttachError(
                  AttachContract.Codes.OffsetOutOfRange,
                  s"Chunk $chunkIndex declares offset $derivedOffset but the target already holds $existing bytes",
                  phase = "transfer",
                  chunkIndex = Some(chunkIndex),
                  expected = Some(existing.toString),
                  actual = Some(derivedOffset)
                )
                .toJson
                .noSpaces
            )
          else if expectedBytes != payload.length.toLong then
            Left(
              AttachContract
                .AttachError(
                  AttachContract.Codes.OffsetOutOfRange,
                  s"Chunk $chunkIndex carries ${payload.length} bytes but the plan says $expectedBytes",
                  phase = "transfer",
                  chunkIndex = Some(chunkIndex),
                  expected = Some(expectedBytes.toString),
                  actual = Some(payload.length.toLong)
                )
                .toJson
                .noSpaces
            )
          else
            // **接收端自算**块摘要（禁自证）
            val computedChunk = ChunkedTransfer.sha256Hex(payload)
            if computedChunk != chunkSha then
              Left(
                AttachContract
                  .AttachError(
                    AttachContract.Codes.ChunkDigestMismatch,
                    s"Chunk $chunkIndex digest mismatch: declared $chunkSha, computed $computedChunk",
                    phase = "transfer",
                    chunkIndex = Some(chunkIndex),
                    expected = Some(chunkSha),
                    actualHash = Some(computedChunk)
                  )
                  .toJson
                  .noSpaces
              )
            else
              // ===== 续传对齐（xferb 批 · P0-2）=====
              // `derivedOffset < existing` 且 index 等于期望 index ⇒ 目标上存的是**非整块尾**
              // （上一次 append 被中断，或上一次会话用了另一个块大小）。此时**必须**先截到
              // `derivedOffset` 再 append —— 否则同一段字节落两遍，末块整件摘要必然不符
              // （改前 = 显式 `OFFSET_OUT_OF_RANGE` 拒绝 ⇒ 该腿的断点续传物理不可行）。
              // 🔴 只在**本次流拥有该文件**时截（上方 `existing > 0 && !owned` 已 fail-closed 拒绝
              // 别人的件）——「收端永不覆盖/删除既有件」的口径不放松。
              val baseBytes =
                if derivedOffset < existing then
                  val raf = new java.io.RandomAccessFile(path.toNIO.toFile, "rw")
                  try raf.setLength(derivedOffset)
                  finally raf.close()
                  derivedOffset
                else existing
              os.write.append(path, payload)
              // 本流从零创建了该文件 ⇒ 登记归属（后续块 / 末块清理才敢动它）。
              if baseBytes == 0L then streamClaims.put(path.toString, streamKey(totalBytes, chunkSize))
              val nowBytes = baseBytes + payload.length
              if nowBytes >= totalBytes then
                // 唯一比对点：自算整件摘要（流式），不符 ⇒ 删文件（绝不 commit）。
                val computedWhole = ChunkedTransfer.hashFileStreaming(path)
                if computedWhole != wholeSha then
                  if ownsNow then
                    os.remove.all(path)
                    streamClaims.remove(path.toString)
                    Left(
                      AttachContract
                        .AttachError(
                          AttachContract.Codes.WholeDigestMismatch,
                          s"Whole-file digest mismatch: declared $wholeSha, receiver computed $computedWhole",
                          phase = "commit",
                          expected = Some(wholeSha),
                          actualHash = Some(computedWhole),
                          bytesReceived = Some(nowBytes)
                        )
                        .toJson
                        .noSpaces
                    )
                  else
                    // 防御性分支（正常不可达：非归属目标已在入口被拒）—— 禁删别人的件。
                    Left(
                      AttachContract
                        .AttachError(
                          AttachContract.Codes.WholeDigestMismatch,
                          s"Whole-file digest mismatch: declared $wholeSha, receiver computed $computedWhole; " +
                            "the pre-existing file was left untouched (not created by this transfer stream)",
                          phase = "commit",
                          expected = Some(wholeSha),
                          actualHash = Some(computedWhole),
                          bytesReceived = Some(nowBytes)
                        )
                        .toJson
                        .noSpaces
                    )
                else
                  streamClaims.remove(path.toString)
                  Right(chunkAckJson(nowBytes, computedChunk, Some(computedWhole), totalBytes))
                end if
              else Right(chunkAckJson(nowBytes, computedChunk, None, totalBytes))

              end if

            end if

          end if

        end if

      end if

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
