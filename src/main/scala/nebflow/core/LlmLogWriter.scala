package nebflow.core

import cats.effect.IO
import io.circe.*
import io.circe.syntax.*
import nebflow.shared.*
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.security.MessageDigest
import java.time.Instant
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

  private val logDir: Path =
    Paths.get(System.getProperty("user.home"), ".nebflow", "logs", "router")
  private val objectsDir: Path = logDir.resolve("objects")

  private val retentionDays = 3

  // Double-checked locking for daily prune
  private val lastPruneDate = java.util.concurrent.atomic.AtomicReference("")

  // ── Public API ──────────────────────────────────────────────────────

  /**
   * Log one complete LLM call (request + response + SSE events).
   * Called from AgentCore.pipeLlmCall after stream collection completes.
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
    isCompaction: Boolean
  ): IO[Unit] =
    if !enabled.get() then IO.unit
    else IO.blocking {
      val requestId = java.util.UUID.randomUUID().toString
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
        "request_id" -> requestId.asJson,
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

      val fullEntry = summary.deepMerge(Json.obj(
        "system_ref" -> Option(systemRef).asJson,
        "tools_ref" -> Option(toolsRef).asJson,
        "message_refs" -> messageRefs.asJson,
        "max_tokens" -> request.maxTokens.asJson,
        "stream" -> true.asJson,
        "thinking" -> request.thinking.asJson
      ))

      appendJsonl("summary", summary)
      appendJsonl("full", fullEntry)

      // ── SSE events ──

      val sseEvents = chunksToSseEvents(chunks, requestId, agent, model)
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

      val responseSummary = Json.obj(
        "timestamp" -> Instant.now().toString.asJson,
        "type" -> "response".asJson,
        "request_model" -> model.asJson,
        "resolved_model" -> model.asJson,
        "agent" -> agent.asJson,
        "response_length" -> resultText.length.asJson,
        "is_streaming" -> true.asJson,
        "status_code" -> 200.asJson,
        "content_type" -> "text/event-stream".asJson
      ).deepMerge(usageJson.map(u => Json.obj("usage" -> u)).getOrElse(Json.obj()))

      val responseFull = responseSummary.deepMerge(Json.obj(
        "full" -> buildResponseJson(
          model, resultText, resultThinking, resultToolCalls,
          resultStopReason, usageJson
        ).noSpaces.asJson
      ))

      appendJsonl("summary", responseSummary)
      appendJsonl("full", responseFull)

      // ── Daily prune ──
      maybePrune()
    }.handleErrorWith(e => logger.warn(s"LlmLogWriter: ${e.getMessage}"))

  // ── Content-Addressed Object Store ──────────────────────────────────

  private def hashContent(json: Json): String =
    val bytes = MessageDigest.getInstance("SHA-256")
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

  private def appendJsonl(suffix: String, json: Json): Unit = writeLock.synchronized {
    val date = Instant.now().toString.take(10) // yyyy-MM-dd
    val path = logDir.resolve(s"${date}_$suffix.jsonl")
    Files.createDirectories(logDir)
    Files.write(
      path,
      (json.noSpaces + "\n").getBytes("UTF-8"),
      StandardOpenOption.CREATE, StandardOpenOption.APPEND
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
      Json.obj(
        "type" -> "image".asJson,
        "source" -> Json.obj(
          "type" -> "base64".asJson,
          "media_type" -> mediaType.asJson,
          "data" -> data.asJson
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
    model: String, text: String, thinking: Option[String],
    toolCalls: List[ToolCall], stopReason: Option[String],
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

  // ── SSE Event Generation ────────────────────────────────────────────

  private def chunksToSseEvents(
    chunks: List[StreamChunk],
    requestId: String,
    agent: String,
    model: String
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

    for chunk <- chunks do chunk match
      case StreamChunk.ThinkingDelta(delta) =>
        if thinkingIdx < 0 then
          thinkingIdx = nextBlockIdx
          nextBlockIdx += 1
        entries += baseSse("content_block_delta")
          .deepMerge(Json.obj(
            "sse_content_block_index" -> thinkingIdx.asJson,
            "delta_reasoning" -> delta.asJson,
            "delta_reasoning_length" -> delta.length.asJson
          ))

      case StreamChunk.TextDelta(delta) =>
        if textIdx < 0 then
          textIdx = nextBlockIdx
          nextBlockIdx += 1
        entries += baseSse("content_block_delta")
          .deepMerge(Json.obj(
            "sse_content_block_index" -> textIdx.asJson,
            "delta_content" -> delta.asJson,
            "delta_content_length" -> delta.length.asJson
          ))

      case StreamChunk.ToolCallChunk(tc) =>
        val idx = nextBlockIdx
        nextBlockIdx += 1
        // content_block_start: tool metadata
        entries += baseSse("content_block_start")
          .deepMerge(Json.obj(
            "sse_content_block_index" -> idx.asJson,
            "sse_content_block" -> Json.obj(
              "type" -> "tool_use".asJson,
              "id" -> tc.id.asJson,
              "name" -> tc.name.asJson
            )
          ))
        // content_block_delta: complete tool input JSON in one shot
        entries += baseSse("content_block_delta")
          .deepMerge(Json.obj(
            "sse_content_block_index" -> idx.asJson,
            "delta_tool_json" -> Json.fromJsonObject(tc.input).noSpaces.asJson
          ))

      case StreamChunk.Done(stopReason, usage, _, _) =>
        val entry = baseSse("message_delta")
          .deepMerge(Json.obj(
            "stop_reason" -> stopReason.getOrElse("end_turn").asJson
          ))
        val withUsage = usage.map { u =>
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
        }.getOrElse(entry)
        entries += withUsage

      case _ => () // ToolCallStart, ToolArgDelta, ThinkingSignature: covered by ToolCallChunk / Done

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
      val cutoff = Instant.now()
        .minusSeconds(retentionDays * 86400L)
        .toString.take(10)

      // 1. Delete old JSONL files and collect remaining hashes
      val usedHashes = scala.collection.mutable.Set.empty[String]

      if Files.exists(logDir) then
        for file <- Files.list(logDir).iterator().asScala.toList
            if file.getFileName.toString.endsWith(".jsonl") do
          val fname = file.getFileName.toString
          val dateStr = fname.take(10)
          if dateStr < cutoff then
            Files.deleteIfExists(file)
          else
            // Collect referenced hashes from remaining files
            for line <- Files.readAllLines(file).asScala if line.nonEmpty do
              io.circe.parser.parse(line).toOption.flatMap(_.asObject) match
                case Some(obj) =>
                  obj("system_ref").flatMap(_.asString).foreach(usedHashes += _)
                  obj("tools_ref").flatMap(_.asString).foreach(usedHashes += _)
                  obj("message_refs").flatMap(_.asArray).foreach:
                    _.foreach(_.asString.foreach(usedHashes += _))
                case None => ()

      // 2. Delete orphaned objects
      if Files.exists(objectsDir) then
        for file <- Files.list(objectsDir).iterator().asScala.toList
            if file.getFileName.toString.endsWith(".json") do
          val hash = file.getFileName.toString.dropRight(5)
          if !usedHashes.contains(hash) then
            Files.deleteIfExists(file)

      logger.infoSync(s"Log retention: pruned files older than $cutoff")
    catch
      case e: Exception => logger.warnSync(s"Log retention error: ${e.getMessage}")

end LlmLogWriter
