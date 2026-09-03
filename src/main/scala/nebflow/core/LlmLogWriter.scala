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
 * automatically (at most once per day, checked during log()).
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
   * Log one complete LLM call (request + response + SSE events).
   * Called from AgentCore.pipeLlmCall after stream collection completes.
   * `requestId` (方案 B 20260903): correlation id supplied by the caller so
   * tool executions of the SAME turn carry the identical id in
   * tools JSONL — when None, a fresh UUID is generated (legacy behavior).
   * Never throws — errors are logged and swallowed.
   */
  def log(
    request: LlmRequest,
    chunks: List[StreamChunk],
    resultText: String,
    resultToolCalls: List[ToolCall],
    resultThinking: Option[String],
    resultStopReason: Option[String],
    resultUsage: Option[TokenUsage],
    resultModel: Option[String],
    isSubagent: Boolean,
    isCompaction: Boolean,
    requestId: Option[String] = None
  ): IO[Unit] =
    if !enabled.get() then IO.unit
    else
      IO.blocking {
        val reqId = requestId.getOrElse(java.util.UUID.randomUUID().toString)
        val model = resultModel.getOrElse("unknown")
        val agent = request.agentId
        val session = request.sessionId
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
          "request_id" -> reqId.asJson,
          "url" -> "".asJson,
          "api_type" -> "anthropic_messages".asJson,
          "model" -> model.asJson,
          "agent" -> agent.asJson,
          "channel" -> "web".asJson,
          "session_id" -> session.asJson,
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

        // ── SSE events ──

        val sseEvents = chunksToSseEvents(chunks, reqId, agent, model, keepDetail = true)
        sseEvents.foreach(appendJsonl("sse", _))

        // ── Response entry ──

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
            "request_model" -> model.asJson,
            "resolved_model" -> model.asJson,
            "agent" -> agent.asJson,
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
      }.handleErrorWith(e => logger.warn(s"LlmLogWriter: ${e.getMessage}"))

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
    val date = Instant.now().toString.take(10) // yyyy-MM-dd
    val path = logDir.resolve(s"${date}_$suffix.jsonl")
    Files.createDirectories(logDir)
    Files.write(
      path,
      (json.noSpaces + "\n").getBytes("UTF-8"),
      StandardOpenOption.CREATE,
      StandardOpenOption.APPEND
    )
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

  // ── SSE Event Generation ────────────────────────────────────────────

  private def chunksToSseEvents(
    chunks: List[StreamChunk],
    requestId: String,
    agent: String,
    model: String,
    keepDetail: Boolean
  ): List[Json] =

    val entries = List.newBuilder[Json]

    // Track content block indices. Text and thinking don't need precise
    // indices (viewer concatenates all delta_content / delta_reasoning).
    // Tool calls need distinct indices for JSON fragment grouping.
    var nextBlockIdx = 0
    var textIdx = -1
    var thinkingIdx = -1

    def now: String = Instant.now().toString

    def baseSse(eventType: String): Json =
      Json.obj(
        "timestamp" -> now.asJson,
        "type" -> "sse_event".asJson,
        "request_id" -> requestId.asJson,
        "request_model" -> model.asJson,
        "resolved_model" -> model.asJson,
        "agent" -> agent.asJson,
        "sse_event_type" -> eventType.asJson
      )

    for chunk <- chunks do
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
          entries += withUsage

        case StreamChunk.ThinkingDelta(delta) =>
          if thinkingIdx < 0 then
            thinkingIdx = nextBlockIdx
            nextBlockIdx += 1
          entries += baseSse("content_block_delta")
            .deepMerge(
              Json.obj(
                "sse_content_block_index" -> thinkingIdx.asJson,
                "delta_reasoning" -> delta.asJson,
                "delta_reasoning_length" -> delta.length.asJson
              )
            )

        case StreamChunk.TextDelta(delta) =>
          if textIdx < 0 then
            textIdx = nextBlockIdx
            nextBlockIdx += 1
          entries += baseSse("content_block_delta")
            .deepMerge(
              Json.obj(
                "sse_content_block_index" -> textIdx.asJson,
                "delta_content" -> delta.asJson,
                "delta_content_length" -> delta.length.asJson
              )
            )

        case StreamChunk.ToolCallChunk(tc) =>
          val idx = nextBlockIdx
          nextBlockIdx += 1
          // content_block_start: tool metadata
          entries += baseSse("content_block_start")
            .deepMerge(
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
          entries += baseSse("content_block_delta")
            .deepMerge(
              Json.obj(
                "sse_content_block_index" -> idx.asJson,
                "delta_tool_json" -> Json.fromJsonObject(tc.input).noSpaces.asJson
              )
            )

        case _ => () // ToolCallStart, ToolArgDelta, ThinkingSignature: covered by ToolCallChunk / Done
    end for

    entries.result()
  end chunksToSseEvents

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
