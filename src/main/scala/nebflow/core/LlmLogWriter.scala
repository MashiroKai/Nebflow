package nebflow.core

import cats.effect.IO
import io.circe.*
import io.circe.syntax.*
import nebflow.shared.*

import java.nio.file.*
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters.*

/**
 * Writes LLM request/response logs in Router-compatible JSONL format.
 *
 * Output directory: ~/.nebflow/logs/router/
 *   - {date}_summary.jsonl  — lightweight entries for list view
 *   - {date}_full.jsonl     — entries with object refs for detail view
 *   - {date}_sse.jsonl      — SSE-style events for streaming reconstruction
 *   - objects/{hash}.json   — content-addressed store (system, tools, messages)
 *
 * This is a one-way data producer. The Router viewer (now a pure reader) watches
 * this directory and displays the logs in its dashboard.
 *
 * Retention: hard 3-day limit. Old JSONL files and orphaned objects are pruned
 * automatically (at most once per day, checked during logResponse()).
 */
object LlmLogWriter:

  private val logger = NebflowLogger.forName("nebflow.llm.logger")

  /** Runtime toggle. When false, log() is a no-op. */
  private val enabled = java.util.concurrent.atomic.AtomicBoolean(true)

  def setEnabled(v: Boolean): Unit = enabled.set(v)
  def isEnabled: Boolean = enabled.get()

  private val logDirOverride = AtomicReference[Option[Path]](None)

  /** Test-only redirect of the router log directory (spec harnesses /
    * isolated instances assert tools↔router requestId alignment without
    * touching the real ~/.nebflow/logs/router). */
  private[nebflow] def setLogDirForTest(p: Path): Unit = logDirOverride.set(Some(p))
  private[nebflow] def resetLogDirForTest(): Unit = logDirOverride.set(None)

  /** Artificial write delay (ms) — async non-blocking proof in specs
    * (ToolsLogWriter 同款注入面). */
  private val writeDelayMsForTest = new java.util.concurrent.atomic.AtomicLong(0)
  private[nebflow] def setWriteDelayMsForTest(ms: Long): Unit = writeDelayMsForTest.set(ms)

  private def logDir: Path =
    logDirOverride.get().getOrElse(Paths.get(System.getProperty("user.home"), ".nebflow", "logs", "router"))
  private def objectsDir: Path = logDir.resolve("objects")

  /** Retention days — shared with ToolsLogWriter (方案 B: tools 日志保留对齐
    * router 现行默认). */
  private[core] val retentionDays = 3

  // Double-checked locking for daily prune
  private val lastPruneDate = java.util.concurrent.atomic.AtomicReference("")

  // ── Public API ──────────────────────────────────────────────────────

  /**
   * 审计 20260903 子项⑤——旧 `log()` 批量落盘（流收集完成后一次性写
   * request/response/SSE）致时间戳失真：request→response 全部 <1.2s 而
   * output_tokens 高达 2 万，首 token 延迟/chunk 间隙无法实测。现拆为三段：
   *   - [[logRequest]]  流派发时落 request 行（summary+full+objects）；
   *   - [[logStreamEvent]] 每 chunk 到达即实时落 sse 行（时间戳=到达时刻，
   *     有界队列+后台 fiber 异步写——吞吐零回归，参照 ToolsLogWriter 模式）；
   *   - [[logResponse]] 流结束落 response 行（附 request_id 与 request 行对齐）。
   * `requestId`（方案 B 20260903）：调用方提供的关联 id，与 tools JSONL 对齐。
   * Never throws — errors are logged and swallowed.
   */

  /** 流派发时落 request 行（在 sendStream 派发前调用）。 */
  def logRequest(
    request: LlmRequest,
    requestId: String,
    isSubagent: Boolean,
    isCompaction: Boolean
  ): IO[Unit] =
    if !enabled.get() then IO.unit
    else
      IO.blocking {
        val agent = request.agentId
        val messages = request.messages
        val tools = request.tools.getOrElse(Nil)
        val systemText = request.systemStable.getOrElse("") +
          request.systemDynamic.map(d => s"\n\n$d").getOrElse("")

        // ── Build and store objects ──

        val systemRef: String =
          if systemText.nonEmpty then storeObject(Json.fromString(systemText)) else null

        val toolsRef: String =
          if tools.nonEmpty then storeObject(toolsToJson(tools)) else null

        val messageRefs: List[String] =
          messages.map(m => storeObject(messageToJson(m)))

        // ── Request summary ──

        val ts = Instant.now().toString

        val toolCallsCount = messages.reverse
          .find(_.role == MessageRole.Assistant)
          .map(_.content.toOption.toList.flatMap(_.collect { case t: ContentBlock.ToolUse => t }).size)
          .getOrElse(0)

        val toolResultsCount = messages.reverse
          .find(_.role == MessageRole.User)
          .map(_.content.toOption.toList.flatMap(_.collect { case t: ContentBlock.ToolResult => t }).size)
          .getOrElse(0)

        val summary = Json.obj(
          "timestamp" -> ts.asJson,
          "type" -> "request".asJson,
          "request_id" -> requestId.asJson,
          "url" -> "".asJson,
          "api_type" -> "anthropic_messages".asJson,
          "model" -> "unknown".asJson,
          "agent" -> agent.asJson,
          "channel" -> "web".asJson,
          "session_id" -> request.sessionId.asJson,
          "metadata_agent_id" -> agent.asJson,
          "messages_count" -> messages.size.asJson,
          "system_length" -> systemText.length.asJson,
          "tools_count" -> tools.size.asJson,
          "tool_calls_count" -> toolCallsCount.asJson,
          "tool_results_count" -> toolResultsCount.asJson,
          "max_tokens" -> request.maxTokens.asJson,
          "stream" -> true.asJson,
          "thinking_enabled" -> request.thinking.isDefined.asJson,
          "is_compaction" -> isCompaction.asJson,
          "is_subagent" -> isSubagent.asJson
        )

        val fullEntry = summary.deepMerge(
          Json.obj(
            "system_ref" -> Option(systemRef).asJson,
            "tools_ref" -> Option(toolsRef).asJson,
            "message_refs" -> messageRefs.asJson,
            "max_tokens" -> request.maxTokens.asJson,
            "stream" -> true.asJson,
            "thinking" -> request.thinking.asJson
          )
        )

        appendJsonl("summary", summary)
        appendJsonl("full", fullEntry)
      }.handleErrorWith(e => logger.warn(s"LlmLogWriter.logRequest: ${e.getMessage}"))

  /** 流结束时落 response 行（附 request_id；request/response 行序不变，
    * viewer 按相邻序关联的既有契约保持）。 */
  def logResponse(
    requestId: String,
    resultText: String,
    resultToolCalls: List[ToolCall],
    resultThinking: Option[String],
    resultStopReason: Option[String],
    resultUsage: Option[TokenUsage],
    resultModel: Option[String]
  ): IO[Unit] =
    if !enabled.get() then IO.unit
    else
      IO.blocking {
        val model = resultModel.getOrElse("unknown")

        val usageJson = resultUsage.map { u =>
          val base = Json.obj(
            "input_tokens" -> u.inputTokens.asJson,
            "output_tokens" -> u.outputTokens.asJson
          )
          val withCr = u.cacheReadTokens
            .map(cr => base.deepMerge(Json.obj("cache_read_input_tokens" -> cr.asJson)))
            .getOrElse(base)
          u.cacheWriteTokens
            .map(cw => withCr.deepMerge(Json.obj("cache_creation_input_tokens" -> cw.asJson)))
            .getOrElse(withCr)
        }

        val responseSummary = Json
          .obj(
            "timestamp" -> Instant.now().toString.asJson,
            "type" -> "response".asJson,
            "request_id" -> requestId.asJson,
            "request_model" -> model.asJson,
            "resolved_model" -> model.asJson,
            "response_length" -> resultText.length.asJson,
            "is_streaming" -> true.asJson,
            "status_code" -> 200.asJson,
            "content_type" -> "text/event-stream".asJson
          )
          .deepMerge(usageJson.map(u => Json.obj("usage" -> u)).getOrElse(Json.obj()))

        val responseFull = responseSummary.deepMerge(
          Json.obj(
            "full" -> buildResponseJson(
              model,
              resultText,
              resultThinking,
              resultToolCalls,
              resultStopReason,
              usageJson
            ).noSpaces.asJson
          )
        )

        appendJsonl("summary", responseSummary)
        appendJsonl("full", responseFull)

        // ── Daily prune ──
        maybePrune()
      }.handleErrorWith(e => logger.warn(s"LlmLogWriter.logResponse: ${e.getMessage}"))

  /**
   * 单请求的流式 SSE 事件编码器（逐事件落盘的可变 block-index 状态持有者；
   * 每个 LLM 流一个实例，fs2 顺序消费保证线程安全）。`encode` 纯转换：
   * 时间戳在 [[logStreamEvent]] 的到达时刻捕获。
   */
  final class StreamEventEncoder(requestId: String, agent: String):
    private var nextBlockIdx = 0
    private var textIdx = -1
    private var thinkingIdx = -1

    def encode(chunk: StreamChunk, model: Option[String]): List[Json] =
      def baseSse(eventType: String): Json =
        Json.obj(
          "timestamp" -> Instant.now().toString.asJson,
          "type" -> "sse_event".asJson,
          "request_id" -> requestId.asJson,
          "request_model" -> model.asJson,
          "resolved_model" -> model.asJson,
          "agent" -> agent.asJson,
          "sse_event_type" -> eventType.asJson
        )

      chunk match
        case StreamChunk.Done(stopReason, usage, _, _) =>
          val entry = baseSse("message_delta")
            .deepMerge(
              Json.obj(
                "stop_reason" -> stopReason.getOrElse("end_turn").asJson
              )
            )
          val withUsage = usage
            .map { u =>
              val baseU = Json.obj(
                "input_tokens" -> u.inputTokens.asJson,
                "output_tokens" -> u.outputTokens.asJson
              )
              val withCr = u.cacheReadTokens
                .map(cr => baseU.deepMerge(Json.obj("cache_read_input_tokens" -> cr.asJson)))
                .getOrElse(baseU)
              val withCw = u.cacheWriteTokens
                .map(cw => withCr.deepMerge(Json.obj("cache_creation_input_tokens" -> cw.asJson)))
                .getOrElse(withCr)
              entry.deepMerge(Json.obj("usage" -> withCw))
            }
            .getOrElse(entry)
          List(withUsage)

        case StreamChunk.ThinkingDelta(delta) =>
          if thinkingIdx < 0 then
            thinkingIdx = nextBlockIdx
            nextBlockIdx += 1
          List(
            baseSse("content_block_delta").deepMerge(
              Json.obj(
                "sse_content_block_index" -> thinkingIdx.asJson,
                "delta_reasoning" -> delta.asJson,
                "delta_reasoning_length" -> delta.length.asJson
              )
            )
          )

        case StreamChunk.TextDelta(delta) =>
          if textIdx < 0 then
            textIdx = nextBlockIdx
            nextBlockIdx += 1
          List(
            baseSse("content_block_delta").deepMerge(
              Json.obj(
                "sse_content_block_index" -> textIdx.asJson,
                "delta_content" -> delta.asJson,
                "delta_content_length" -> delta.length.asJson
              )
            )
          )

        case StreamChunk.ToolCallChunk(tc) =>
          val idx = nextBlockIdx
          nextBlockIdx += 1
          // content_block_start: tool metadata
          val start = baseSse("content_block_start").deepMerge(
            Json.obj(
              "sse_content_block_index" -> idx.asJson,
              "sse_content_block" -> Json.obj(
                "type" -> "tool_use".asJson,
                "id" -> tc.id.asJson,
                "name" -> tc.name.asJson
              )
            )
          )
          // content_block_delta: complete tool input JSON in one shot
          val delta = baseSse("content_block_delta").deepMerge(
            Json.obj(
              "sse_content_block_index" -> idx.asJson,
              "delta_tool_json" -> Json.fromJsonObject(tc.input).noSpaces.asJson
            )
          )
          List(start, delta)

        case _ => Nil // ToolCallStart, ToolArgDelta, ThinkingSignature: covered by ToolCallChunk / Done
  end StreamEventEncoder

  /** 每 chunk 到达即入队（ts 在 encode 时捕获=到达时刻）；后台 fiber 异步写盘，
    * 吞吐零回归。model 仅 Done 帧可携带（meta.model），其余帧为 null。 */
  def logStreamEvent(encoder: StreamEventEncoder, chunk: StreamChunk): IO[Unit] =
    if !enabled.get() then IO.unit
    else
      IO.delay {
        val model = chunk match
          case StreamChunk.Done(_, _, meta, _) => meta.map(_.model)
          case _ => None
        encoder.encode(chunk, model)
      }.flatMap(lines =>
        if lines.isEmpty then IO.unit
        else
          ensureWorker *> lines.foldLeft(IO.unit) { (acc, json) =>
            // 计数先于入队：counter 任意时刻 == 排队中 + 已take未落盘 行数，
            // 无「offer 成功但尚未 +1」的瞬时空窗（负漂移曾取消在飞行等待）。
            acc *> IO.delay(pendingWrites.incrementAndGet()).void *> sseQueue
              .tryOffer(json)
              .flatMap {
                case true => IO.unit
                case false =>
                  // Overflow: drop + WARN — telemetry loss never blocks the stream path.
                  IO.delay {
                    pendingWrites.decrementAndGet()
                    logger.warnSync(
                      s"LlmLogWriter: sse queue full ($QueueCapacity) — dropping streaming event line"
                    )
                  }
              }
          }
      )

  // ── Async sse pipeline: bounded queue + background fiber (ToolsLogWriter 模式) ──

  /** Bounded queue capacity — overflow drops + WARN, never blocks the stream. */
  private val QueueCapacity = 8192

  private val sseQueue: cats.effect.std.Queue[IO, Json] =
    cats.effect.std.Queue.bounded[IO, Json](QueueCapacity).unsafeRunSync()(using
      cats.effect.unsafe.implicits.global
    )

  private val sseWorkerStarted = new java.util.concurrent.atomic.AtomicBoolean(false)

  /** Flush barrier: while true the worker does not take from the queue. */
  private val sseFlushing = new java.util.concurrent.atomic.AtomicBoolean(false)

  /** Offered-but-not-yet-written lines — lets flushSync wait out in-flight writes.
    * EXACT accounting (never negative): only the queue path touches it
    * (increment before offer / decrement after the consuming append). The
    * sync direct-write paths (logRequest/logResponse/logIntake) must NOT
    * decrement it — pre-fix they did, and the accumulated negative base
    * cancelled flushSync's in-flight wait entirely ("taken but not yet
    * appended" window ran bare → T2 flake on loaded CI runners). */
  private val pendingWrites = new java.util.concurrent.atomic.AtomicLong(0)

  /** Test-only probe — specs assert the in-flight counter never drifts. */
  private[nebflow] def ssePendingWritesForTest: Long = pendingWrites.get()

  private def sseWorkerLoop: IO[Unit] =
    (IO.blocking {
      while sseFlushing.get() do Thread.sleep(5)
    } *> sseQueue.take.flatMap(json =>
      IO.blocking {
        try appendJsonl("sse", json)
        finally pendingWrites.decrementAndGet()
      }
    )).foreverM

  private def ensureWorker: IO[Unit] =
    IO(sseWorkerStarted.compareAndSet(false, true)).ifM(sseWorkerLoop.start.void, IO.unit)

  /** Synchronous drain — specs / shutdown hook make the async write observable:
    * every line offered BEFORE flushSync started is on disk when it returns.
    * (Drain covers queued items; writeLock barrier covers in-append items;
    * pendingWrites wait covers taken-but-not-yet-appended items.) */
  private[nebflow] def flushSync(): Unit =
    sseFlushing.set(true)
    try
      var more = true
      while more do
        sseQueue.tryTake.unsafeRunSync()(using cats.effect.unsafe.implicits.global) match
          case Some(json) =>
            try appendJsonl("sse", json)
            finally pendingWrites.decrementAndGet()
          case None => more = false
      // Wait out any in-flight worker write (same lock appendJsonl holds).
      writeLock.synchronized(())
      var waits = 0
      while pendingWrites.get() > 0 && waits < 2000 do
        Thread.sleep(5)
        waits += 1
    finally sseFlushing.set(false)

  // JVM shutdown: best-effort flush of whatever is still queued.
  locally {
    Runtime.getRuntime.addShutdownHook(new Thread(
      () => { try flushSync() catch case _: Throwable => () },
      "llm-sse-log-flush"
    ))
  }

  // ── Content-Addressed Object Store ──────────────────────────────────

  private def hashContent(json: Json): String =
    val bytes = MessageDigest
      .getInstance("SHA-256")
      .digest(json.noSpaces.getBytes("UTF-8"))
    bytes.map("%02x".format(_)).mkString.take(16)

  private def storeObject(json: Json): String =
    val hash = hashContent(json)
    val target = objectsDir.resolve(s"$hash.json")
    if !Files.exists(target) then
      Files.createDirectories(objectsDir)
      val tmp = objectsDir.resolve(s"$hash.tmp")
      Files.write(tmp, json.noSpaces.getBytes("UTF-8"))
      try Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
      catch
        case _: java.nio.file.FileAlreadyExistsException =>
          Files.deleteIfExists(tmp)
    hash

  // ── JSONL Append (synchronized for multi-agent safety) ──────────────

  private val writeLock = new Object

  /**
   * gate-wedge P2 (2026-08-20): pre-gate request INTAKE event — one JSONL line
   * appended to the sse file the moment a streaming request is accepted by the
   * interface, BEFORE provider selection / gate acquire. The incident showed
   * requests that died while queued at the gate leave ZERO traces in the stream
   * logs (SSE logging only starts once bytes flow). With intake lines, a
   * request_id that has an intake but no response is instantly identifiable as
   * "never fired" (gate wedge / pre-gate death) instead of requiring 8h of
   * forensic reconstruction. One line per request; no objects stored.
   */
  def logIntake(requestId: String, sessionId: String, agentId: String): IO[Unit] =
    if !enabled.get() then IO.unit
    else
      IO.blocking {
        appendJsonl(
          "sse",
          Json.obj(
            "timestamp" -> Instant.now().toString.asJson,
            "type" -> "intake".asJson,
            "request_id" -> requestId.asJson,
            "session" -> sessionId.asJson,
            "agent" -> agentId.asJson,
            "stage" -> "pre-gate".asJson
          )
        )
      }.handleErrorWith(e => logger.warn(s"LlmLogWriter.logIntake: ${e.getMessage}"))

  private def appendJsonl(suffix: String, json: Json): Unit = writeLock.synchronized {
    try
      if writeDelayMsForTest.get() > 0 then Thread.sleep(writeDelayMsForTest.get())
      val date = Instant.now().toString.take(10) // yyyy-MM-dd
      val path = logDir.resolve(s"${date}_$suffix.jsonl")
      Files.createDirectories(logDir)
      Files.write(
        path,
        (json.noSpaces + "\n").getBytes("UTF-8"),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
    catch case e: Exception => logger.warnSync(s"LlmLogWriter.appendJsonl: ${e.getMessage}")
  }

  // ── Conversion: Nebflow types → Anthropic-style JSON ────────────────

  private def messageToJson(msg: Message): Json =
    val content = msg.content match
      case Left(text) => Json.fromString(text)
      case Right(blocks) => Json.fromValues(blocks.map(blockToJson))
    Json.obj("role" -> msg.role.name.asJson, "content" -> content)

  private def blockToJson(b: ContentBlock): Json = b match
    case ContentBlock.Text(text) =>
      Json.obj("type" -> "text".asJson, "text" -> text.asJson)
    case ContentBlock.ToolUse(id, name, input) =>
      Json.obj(
        "type" -> "tool_use".asJson,
        "id" -> id.asJson,
        "name" -> name.asJson,
        "input" -> Json.fromJsonObject(input)
      )
    case ContentBlock.ToolResult(toolUseId, content, isError) =>
      val base = Json.obj(
        "type" -> "tool_result".asJson,
        "tool_use_id" -> toolUseId.asJson,
        "content" -> content.asJson
      )
      isError.fold(base)(e => base.deepMerge(Json.obj("is_error" -> e.asJson)))
    case ContentBlock.Thinking(text, signature) =>
      val base = Json.obj("type" -> "thinking".asJson, "thinking" -> text.asJson)
      signature.fold(base)(s => base.deepMerge(Json.obj("signature" -> s.asJson)))
    case ContentBlock.Image(data, mediaType) =>
      // Log-only serialization (API requests are built in AnthropicAdapter
      // and keep the full payload) — the base64 data is replaced by a
      // placeholder so request logs don't balloon with megabytes of image
      // data per message.
      Json.obj(
        "type" -> "image".asJson,
        "source" -> Json.obj(
          "type" -> "base64".asJson,
          "media_type" -> mediaType.asJson,
          "data" -> s"<base64 omitted, ${data.length} bytes>".asJson
        )
      )

  private def toolsToJson(tools: List[ToolDefinition]): Json =
    Json.fromValues(tools.map { t =>
      Json.obj(
        "name" -> t.name.asJson,
        "description" -> t.description.asJson,
        "input_schema" -> Json.fromJsonObject(t.inputSchema)
      )
    })

  private def buildResponseJson(
    model: String,
    text: String,
    thinking: Option[String],
    toolCalls: List[ToolCall],
    stopReason: Option[String],
    usage: Option[Json]
  ): Json =
    val parts = List.newBuilder[Json]
    thinking.foreach(t => parts += Json.obj("type" -> "thinking".asJson, "thinking" -> t.asJson))
    if text.nonEmpty then parts += Json.obj("type" -> "text".asJson, "text" -> text.asJson)
    toolCalls.foreach { tc =>
      parts += Json.obj(
        "type" -> "tool_use".asJson,
        "id" -> tc.id.asJson,
        "name" -> tc.name.asJson,
        "input" -> Json.fromJsonObject(tc.input)
      )
    }
    Json.obj(
      "type" -> "message".asJson,
      "role" -> "assistant".asJson,
      "model" -> model.asJson,
      "content" -> Json.fromValues(parts.result()).asJson,
      "stop_reason" -> stopReason.getOrElse("end_turn").asJson,
      "usage" -> usage.getOrElse(Json.obj())
    )
  end buildResponseJson

  // ── Retention: hard 3-day limit ─────────────────────────────────────

  private def maybePrune(): Unit =
    val today = Instant.now().toString.take(10)
    if lastPruneDate.get() != today then
      writeLock.synchronized {
        if lastPruneDate.get() != today then
          pruneOldLogs()
          lastPruneDate.set(today)
      }

  private def pruneOldLogs(): Unit =
    try
      val cutoff = Instant
        .now()
        .minusSeconds(retentionDays * 86400L)
        .toString
        .take(10)

      // 1. Delete old JSONL files and collect remaining hashes.
      //    Only `_full.jsonl` entries carry object refs (system_ref /
      //    tools_ref / message_refs) — summary and sse files are pure
      //    stats/events and are NEVER scanned (#26: sse files reach
      //    hundreds of MB; readAllLines on them OOMs the JVM).
      val usedHashes = scala.collection.mutable.Set.empty[String]
      // Oversized full files are skipped (their refs unknown) — when that
      // happens the orphan sweep is suppressed so live objects are never
      // mistaken for orphans and deleted.
      val scanIncomplete =
        if Files.exists(logDir) then
          val cutoffDelete = cutoff
          scanFullLogsForRefs(logDir, cutoffDelete, usedHashes, MaxPruneScanBytes)
        else false

      // 2. Delete orphaned objects — only when every full file was fully
      //    scanned; a skipped file's refs are unknown, so deleting "orphans"
      //    could remove objects still in use.
      if !scanIncomplete && Files.exists(objectsDir) then
        for
          file <- Files.list(objectsDir).iterator().asScala.toList
          if file.getFileName.toString.endsWith(".json")
        do
          val hash = file.getFileName.toString.dropRight(5)
          if !usedHashes.contains(hash) then Files.deleteIfExists(file)

      logger.infoSync(s"Log retention: pruned files older than $cutoff")
    catch case e: Exception => logger.warnSync(s"Log retention error: ${e.getMessage}")

  /** Max size of a single full.jsonl file we are willing to scan for refs. */
  private val MaxPruneScanBytes: Long = 128L * 1024 * 1024

  /** Scan jsonl files in `dir` for retention: delete files older than
    * `cutoff` (date-prefix compare), and collect object refs from remaining
    * `_full.jsonl` files into `usedHashes`.
    *
    * Returns true when any remaining full file was SKIPPED because it
    * exceeded `maxBytes` — the caller must then suppress the orphan sweep
    * (a skipped file's refs are unknown, so deleting "orphans" could remove
    * objects still in use).
    *
    * Only `_full.jsonl` is scanned: summary/sse entries carry no object
    * refs. Package-visible for tests (#26). */
  private[core] def scanFullLogsForRefs(
      dir: Path,
      cutoff: String,
      usedHashes: scala.collection.mutable.Set[String],
      maxBytes: Long
  ): Boolean =
    var incomplete = false
    for
      file <- Files.list(dir).iterator().asScala.toList
      if file.getFileName.toString.endsWith(".jsonl")
    do
      val fname = file.getFileName.toString
      val dateStr = fname.take(10)
      if dateStr < cutoff then Files.deleteIfExists(file)
      else if fname.endsWith("_full.jsonl") then
        // Stream line-by-line (bounded memory); skip gigantic files rather
        // than risking OOM — pruning is best-effort cleanup.
        if Files.size(file) <= maxBytes then collectReferencedHashes(file, usedHashes)
        else incomplete = true
    incomplete

  /** Stream one full.jsonl line-by-line, collecting object refs. Memory is
    * bounded to a single line (readAllLines would load the whole file —
    * #26: multi-hundred-MB files OOM under the default 1g heap). */
  private def collectReferencedHashes(file: Path, usedHashes: scala.collection.mutable.Set[String]): Unit =
    val reader = Files.newBufferedReader(file)
    try
      var line = reader.readLine()
      while line != null do
        if line.nonEmpty then
          io.circe.parser.parse(line).toOption.flatMap(_.asObject) match
            case Some(obj) =>
              obj("system_ref").flatMap(_.asString).foreach(usedHashes += _)
              obj("tools_ref").flatMap(_.asString).foreach(usedHashes += _)
              obj("message_refs")
                .flatMap(_.asArray)
                .foreach:
                  _.foreach(_.asString.foreach(usedHashes += _))
            case None => ()
        line = reader.readLine()
    finally reader.close()

end LlmLogWriter
