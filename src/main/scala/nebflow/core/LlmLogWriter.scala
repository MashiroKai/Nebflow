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
 * Output directory: <dataRoot>/logs/router/ — dataRoot = PathUtil.dataRoot
 * (CLI --home flag / NEBFLOW_HOME env / default ~/.nebflow). Isolated
 * instances (--home /tmp/qa-*) keep their LLM request logs inside their own
 * home; the production default collapses to ~/.nebflow/logs/router/.
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

  /** **默认态 = 关**（无落盘值时的兜底；2026-09-13 只改默认值批）。
    * 全仓**单点**定义——禁第二份「默认 true」（boot 读侧 [[loadEnabled]] 返回
    * None 时即落到本值）。声明序**必须**在 [[enabled]] 之前：object 初始化按
    * 声明序执行，常量后置会被读到默认 false（前向引用陷阱）。
    * 钉版：`LlmLogDefaultSpec`（本常量的回归门）。 */
  private[nebflow] val DefaultEnabled = false

  /** Runtime toggle. When false, log() is a no-op.
    *
    * **默认关（2026-09-13 作者裁定「只改默认值」批）**：能力全保留——
    * [[setEnabled]] 显式打开后四道门（`:logRequest` / `:logResponse` /
    * `:logStreamEvent` / `:logIntake`）全开、四个写入面（summary / full /
    * sse / objects）照写，**开启时仍完整落盘、不降采样**（既有「完整性优先」
    * 裁定管的是「开启时不得降采样」，与默认态是两件事，未被本批取代）。
    * 默认关 ≠ 降采样：关态是门控短路，一行不写。
    *
    * 本初值只是**无落盘值时的兜底**；落盘值（nebflow.json 顶层 `llmLog` 节，
    * 见 [[configSection]] / [[loadEnabled]]）在 boot 时覆写（GatewayMain），
    * WS `setLlmLog` 落盘后热更——故用户显式改动过的值**跨重启保持**
    * （D-A：旧缺陷「宿主重启回落 true」已修）。 */
  private val enabled = java.util.concurrent.atomic.AtomicBoolean(DefaultEnabled)

  def setEnabled(v: Boolean): Unit = enabled.set(v)
  def isEnabled: Boolean = enabled.get()

  /** 持久化落点 = nebflow.json **顶层节点名**（读 / 写两侧单源：读 =
    * [[loadEnabled]]（boot），写 = `nebflow.service.ConfigService.setLlmLogEnabled`
    * （WS `setLlmLog` 经 ConfigService 的既有配置写锁 + 原子写））。
    * 复用既有配置存储（同一 nebflow.json，与 `toolResultTtl` / `safety` /
    * `workSchedule` 同族形态）；**不新增配置文件格式**。 */
  val configSection = "llmLog"

  /** 解析落盘值 → 显式开关态。**fail-safe**：`llmLog` 节缺失 / 非对象 /
    * `enabled` 非布尔 ⇒ `None`（= 「无落盘值」⇒ 调用方保持默认关）。
    * 纯函数（无副作用、不读盘），boot 与 spec 共用。 */
  def loadEnabled(config: Option[Json]): Option[Boolean] =
    config.flatMap(_.hcursor.downField("enabled").as[Boolean].toOption)

  /** 落盘载荷（[[loadEnabled]] 的逆）。 */
  def toConfigJson(v: Boolean): Json = Json.obj("enabled" -> v.asJson)

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

  /** Log root follows the instance data root (PathUtil.dataRoot: CLI --home
    * flag / NEBFLOW_HOME env / default ~/.nebflow) — NEVER a raw user.home
    * hardcode. P1 defect 20260910 (实测报告 20260910_loopnode新形式实测报告.md):
    * the previous `Paths.get(user.home, ".nebflow", ...)` hardcode leaked
    * every isolated instance's LLM request bodies (objects/ 含 messages 全量)
    * into the production ~/.nebflow/logs/router. logDirOverride stays the
    * test-only injection point (takes precedence). */
  private def logDir: Path =
    logDirOverride.get().getOrElse((PathUtil.dataRoot / "logs" / "router").toNIO)

  /** Package-visible probe — specs pin the dataRoot-following contract
    * (isolated home + production-default path) without writing to either. */
  private[core] def logDirForTest: Path = logDir
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
            "stream" -> true.asJson,
            "thinking" -> request.thinking.asJson
          )
        )

        appendJsonl("summary", summary)
        appendJsonl("full", fullEntry)
      }.handleErrorWith(e => logger.warn(s"LlmLogWriter.logRequest: ${e.getMessage}"))

  /** 流结束时落 response 行（附 request_id；request/response 行序不变，
    * viewer 按相邻序关联的既有契约保持）。
    *
    * **回收腿与写入开关解耦（本批）**：本方法是 `maybePrune` 的既有唯一触发点，
    * 现将回收 tick 提到 `enabled` 门**之外**——关掉写入 ≠ 关掉占用（旧缺陷：
    * 门在 `:217` 短路 ⇒ 关态下保留窗执行整条腿停摆、声明 3 天实际 0 天淘汰）。
    * 写入面（[[writeResponse]]）的门控**原样保留**：关态仍零新增。 */
  def logResponse(
    requestId: String,
    resultText: String,
    resultToolCalls: List[ToolCall],
    resultThinking: Option[String],
    resultStopReason: Option[String],
    resultUsage: Option[TokenUsage],
    resultModel: Option[String]
  ): IO[Unit] =
    pruneTick *> writeResponse(
      requestId,
      resultText,
      resultToolCalls,
      resultThinking,
      resultStopReason,
      resultUsage,
      resultModel
    )

  /** 写入面（原 `logResponse` 主体，逐字节未改）：`enabled` 门控的唯一归属地。 */
  private def writeResponse(
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
  //
  // 保留窗执行与写入开关**解耦**（2026-09-13 回收解耦批）：trigger/execute 都不再挂
  // 在 `enabled` 上——[[pruneTick]] 由 [[logResponse]] 无条件先跑，写入面门控留在
  // [[writeResponse]] 内。**关掉写入 ≠ 关掉占用**。
  //
  // 扫描拆成两条**互不影响**的路径（D-B 抑制修复）：
  //   pass 1 删除作业：出窗 jsonl 一律删——不读内容、**不受任何扫描预算影响**；
  //   pass 2 引用扫描：窗内 `*_full.jsonl` 流式读，**每轮字节预算**封顶，超预算
  //          **停在行边界**并把（文件 + 偏移）存进 [[ScanState]]，下一轮**续读**
  //          ——多轮完成、**不清空进度**。
  //
  // 修的是什么（旧语义）：任一窗内 `_full.jsonl` > 128 MiB ⇒ `scanIncomplete=true`
  // ⇒ 孤儿清扫**整段跳过**（不是「扫到一半停」）。实测三件窗内 full 为 275/208/148 MB
  // **全部超限** ⇒ 孤儿**从不**被清扫（84,989 件 > 3 天仍在盘）。预算常量**值不变**
  // （128 MiB）——变的是「超限之后怎么办」，不是「把上限调大」。

  /** 回收腿**武装位**（默认**关**）：只有「本实例」在启动时显式武装（[[armRetention]]，
    * 由 GatewayMain boot 调用）之后，[[pruneTick]] 才会真正执行回收。
    *
    * 为什么必须有这道闸（与 `enabled` **正交**，不是把写入门挪回来）：
    * [[logResponse]] 是唯一触发点，但它在**真实 dataRoot** 上也被大量非实例调用者执行过
    * ——仓内 74 个 spec 引用 AgentCore/AgentActor，其中未隔离 dataRoot 者会走完整轮次
    * （`AgentCore.scala` 的 `logResponse` 调用点），外加 e2e / 临时脚本。修前这些调用被
    * 写入开关的默认关**顺带挡住**；本批把回收从写入开关解耦后，这条顺带保护消失 ⇒
    * 实测后果：一次 `sbt testOnly` 在 2026-09-13 09:54:42 删掉了生产
    * `logs/router/2026-09-09_{full,sse,summary}.jsonl`（1.7468 GiB，不可恢复；
    * 详见 llmlogprune 批事故取证 INCIDENT-objects-restore（内部留档）
    * 与同目录 incident #2 记录）。武装位把「破坏性回收」绑定到**实例生命周期**这一正确
    * 轴（谁是实例谁回收），而不是绑定回写入开关。 */
  private val retentionArmed = java.util.concurrent.atomic.AtomicBoolean(false)

  /** 武装本实例的回收腿（GatewayMain boot 调用；spec / 演武可显式调用）。
    * 幂等；返回武装前的状态（false = 本次调用完成武装）。 */
  private[nebflow] def armRetention(): Boolean = !retentionArmed.getAndSet(true)

  /** Spec 探针 / 复位（默认**关**是刻意设计，见 [[retentionArmed]] 的来历）。 */
  private[core] def isRetentionArmedForTest: Boolean = retentionArmed.get()
  private[core] def disarmRetentionForTest(): Unit = retentionArmed.set(false)

  /** 单轮读取预算（字节）——值与原 `MaxPruneScanBytes` 相同（128 MiB），语义已从
    * 「单文件大小上限（超限即整体放弃）」改为「单轮预算（超限停在行边界、下一轮续读）」。
    * 时间上界：每轮读取量 ≤ 预算 + 单行（[[MaxPruneLineBytes]]），与文件大小、窗内
    * 总字节无关；未完成轮之间有 [[pruneRetryBackoffMs]] 节流。 */
  private val MaxPruneRoundBytes: Long = 128L * 1024 * 1024

  /** 单行内存上界 = 流式扫描**唯一**的驻留缓冲（读一行、解析一行、随即释放；
    * 内存 O(1 行)，与文件大小无关）。超限行读不出引用 ⇒ 整窗标记 poisoned（该窗口
    * **不清扫孤儿**：引用集不完整时宁可不删）。 */
  private val MaxPruneLineBytes: Int = 8 * 1024 * 1024

  /** 未完成轮（预算耗尽）之间的最小间隔——封住重试频率上界；首轮不受限。 */
  private val pruneRetryBackoffMs = new java.util.concurrent.atomic.AtomicLong(60_000L)
  private[core] def setPruneRetryBackoffMsForTest(ms: Long): Unit = pruneRetryBackoffMs.set(ms)
  private[core] def resetPruneRetryBackoffMsForTest(): Unit = pruneRetryBackoffMs.set(60_000L)
  private val lastIncompleteRoundMs = new java.util.concurrent.atomic.AtomicLong(0L)

  /** 一次保留窗执行（pass）的**跨轮进度**：已扫完的窗内 full 文件集 + 累积引用集
    * （immutable 快照，容量 ~窗内去重对象数）+ 当前文件的字节游标。`cutoff` 绑定窗口：
    * 窗口滚动（UTC 日切）即重置——绝不用「比当前窗更小」的引用集去清扫孤儿。 */
  private[core] final case class ScanState(
      cutoff: String,
      done: Set[String],
      used: Set[String],
      cursor: Option[(String, Long)],
      passStartedAtMs: Long,
      poisoned: Boolean
  )
  private[core] object ScanState:
    val empty: ScanState = ScanState("", Set.empty, Set.empty, None, 0L, false)

  private val scanState = new java.util.concurrent.atomic.AtomicReference[ScanState](ScanState.empty)

  /** Spec 探针：当前 pass 的进度快照。 */
  private[core] def pruneStateForTest: ScanState = scanState.get()

  /** Spec / 演武驱动用：重置回收簿记（日锁 + 跨轮进度 + 重试节流）。 */
  private[core] def resetPruneStateForTest(): Unit =
    lastPruneDate.set("")
    lastIncompleteRoundMs.set(0L)
    scanState.set(ScanState.empty)

  /** 回收 tick —— **无 `enabled` 门**（本批解耦点），但**要求本实例已武装**
    * （[[retentionArmed]]，boot 时由 GatewayMain 武装）：回收与写入开关正交，
    * 与「谁是实例」绑定。廉价 + 幂等（日锁内一次原子比较即返回）；绝不抛出
    * （失败降级为 WARN，不污染 LLM 主路径）。 */
  private def pruneTick: IO[Unit] =
    if !retentionArmed.get() then IO.unit
    else
      IO.blocking(maybePrune()).handleErrorWith(e =>
        logger.warn(s"LlmLogWriter.pruneTick: ${e.getMessage}")
      )

  private def maybePrune(): Unit =
    val today = Instant.now().toString.take(10)
    // 一天一次——但「一天一次」指的是**完整跑完一次 pass**：预算耗尽的轮不算完成，
    // 由后续 tick 在节流下续跑（进度在 [[scanState]] 里，不清空）。
    if lastPruneDate.get() != today then
      writeLock.synchronized {
        if lastPruneDate.get() != today then
          val nowMs = System.currentTimeMillis()
          val lastIncomplete = lastIncompleteRoundMs.get()
          if lastIncomplete == 0L || nowMs - lastIncomplete >= pruneRetryBackoffMs.get() then
            val cutoff = Instant
              .now()
              .minusSeconds(retentionDays * 86400L)
              .toString
              .take(10)
            val (next, finished) = retentionRound(logDir, cutoff, scanState.get(), MaxPruneRoundBytes)
            scanState.set(next)
            if finished then lastPruneDate.set(today)
            else lastIncompleteRoundMs.set(nowMs)
      }

  /** 删除腿（pass 1）——独立路径：删 `dir` 下日期前缀 < `cutoff` 的 jsonl。
    * **不读内容、不受扫描预算影响**（旧实现把删除腿与扫描腿耦在同一循环里：扫描
    * 预算一耗尽，两条腿一起停）。包内可见：ToolsLogWriter 复用（tools 文件不携带
    * 对象引用 ⇒ 只有这一条腿）。 */
  private[core] def deleteOutOfWindowJsonl(dir: Path, cutoff: String): Unit =
    if Files.exists(dir) then
      for
        f <- Files.list(dir).iterator().asScala.toList
        if f.getFileName.toString.endsWith(".jsonl")
        if f.getFileName.toString.take(10) < cutoff
      do Files.deleteIfExists(f)

  /** 一次保留窗轮次。返回 `(新进度, 本轮是否**跑完整个 pass**)`——`false` 只表示
    * 「预算耗尽、下轮续读」，**不表示放弃**。
    *
    * @param budgetBytes 本轮读取字节预算（[[MaxPruneRoundBytes]]；spec 用极小值模拟多轮）
    * @param maxLineBytes 单行内存上界（[[MaxPruneLineBytes]]；spec 用极小值模拟超限行） */
  private[core] def retentionRound(
      dir: Path,
      cutoff: String,
      prev: ScanState,
      budgetBytes: Long,
      maxLineBytes: Int = MaxPruneLineBytes
  ): (ScanState, Boolean) =
    try
      val st =
        if prev.cutoff == cutoff then prev
        else ScanState(cutoff, Set.empty, Set.empty, None, System.currentTimeMillis(), false)

      // ── pass 1：删除作业（与底下的引用扫描完全解耦）─────────────────
      deleteOutOfWindowJsonl(dir, cutoff)

      val inWindowFull =
        if Files.exists(dir) then
          Files.list(dir).iterator().asScala.toList.filter { f =>
            val name = f.getFileName.toString
            name.endsWith("_full.jsonl") && name.take(10) >= cutoff
          }
        else Nil

      if st.poisoned then
        logger.infoSync(
          s"Log retention: pruned files older than $cutoff (orphan sweep OFF: unreadable line in window)"
        )
        (st, true)
      else
        val done = scala.collection.mutable.Set.from(st.done)
        val used = scala.collection.mutable.Set.from(st.used)
        val todo = inWindowFull
          .filterNot(f => done.contains(f.getFileName.toString))
          .sortBy(_.getFileName.toString)
        var remaining = budgetBytes
        var cursor = st.cursor
        var poisoned = false
        var budgetStopped = false
        var i = 0
        // 游标所指文件若已出窗被删（窗口滚动 / 手动清理），游标作废——其引用行随文件消失
        if cursor.exists { case (n, _) => !todo.exists(_.getFileName.toString == n) } then cursor = None
        while i < todo.size && !poisoned && !budgetStopped && remaining > 0 do
          val f = todo(i)
          val name = f.getFileName.toString
          val from = cursor match
            case Some((n, off)) if n == name => off
            case _                           => 0L
          val (read, trunc, hitEof) =
            collectReferencedHashes(f, from, used, remaining, maxLineBytes)
          remaining -= math.max(read, 1L) // 空文件也必须推进（否则轮内死循环）
          if trunc then poisoned = true
          else if hitEof then
            done += name
            cursor = None
            i += 1
          else
            // 预算耗尽：停在行边界，游标交给下一轮续读（**不是**「整体放弃」）
            cursor = Some((name, from + read))
            budgetStopped = true
        val complete = !poisoned && !budgetStopped && i >= todo.size
        val next = ScanState(cutoff, done.toSet, used.toSet, cursor, st.passStartedAtMs, poisoned)
        if complete then
          val swept = deleteOrphanObjects(dir.resolve("objects"), used, st.passStartedAtMs)
          logger.infoSync(
            s"Log retention: pruned files older than $cutoff (pass complete: refs=${used.size}, swept objects=$swept)"
          )
          (next, true)
        else if poisoned then
          logger.infoSync(
            s"Log retention: pruned files older than $cutoff (orphan sweep OFF: unreadable line in window)"
          )
          (next, true) // 毒窗口当日不再重扫（重扫只会再毒一次）
        else
          logger.infoSync(
            s"Log retention: pruned files older than $cutoff (pass in progress: refs=${used.size}, " +
              s"cursor=$cursor → next round resumes)"
          )
          (next, false)
    catch
      case e: Exception =>
        logger.warnSync(s"Log retention error: ${e.getMessage}")
        (prev, true) // 出错即当日不再重试（与修前口径一致：不把异常变成重试风暴）

  /** 孤儿清扫——**只在整窗引用集完整时**调用（不清扫不完整引用集，宁可少删）。
    *
    * `objectsDir` **必须**是调用方传入的那个（与 `retentionRound(dir, …)` 同一目录）：
    * 一旦写成模块级 `objectsDir`，任何用临时目录驱动本函数的 spec 都会去删**真实
    * 生产** `~/.nebflow/logs/router/objects`（2026-09-13 09:46:49 实测事故——已完整
    * 恢复，见 llmlogprune 批事故取证 INCIDENT-objects-restore（内部留档））。
    *
    * `notAfterMs` = 本次 pass 的起始时刻，是**增量扫描的正确性要件**：跨轮扫描期间
    * 新写入的对象（mtime 晚于 pass 起点）一律留到下一轮/次日——append-only 文件的
    * 新追加行可能落在「本轮已读区间」之后（读到 EOF 后追加的行本轮看不到），此时它们
    * 的引用尚未被采集；把这类对象排除在清扫之外，即把「引用晚于扫描」的竞态窗口压回
    * 与修前同为「分钟级一轮」的量级（修前单发扫描同样存在该竞态）。 */
  private def deleteOrphanObjects(objectsDir: Path, used: scala.collection.Set[String], notAfterMs: Long): Int =
    var deleted = 0
    if Files.exists(objectsDir) then
      for
        file <- Files.list(objectsDir).iterator().asScala.toList
        if file.getFileName.toString.endsWith(".json")
      do
        val hash = file.getFileName.toString.dropRight(5)
        if !used.contains(hash) && Files.getLastModifiedTime(file).toMillis <= notAfterMs then
          if Files.deleteIfExists(file) then deleted += 1
    deleted

  /** 有界内存的按行读取器（字节级切行——UTF-8 多字节序列内不会出现 0x0A，安全）。
    * 每行返回 `(文本, 行字节数含换行符, 是否因超 maxLineBytes 被截断)`。 */
  private final class CappedLineReader(src: java.io.InputStream, maxLineBytes: Int):
    private val buf = new Array[Byte](64 * 1024)
    private var len = 0
    private var pos = 0

    /** 缓冲里还有未消费字节？否则再读一块；EOF（且缓冲已空）返回 false。 */
    private def ensure(): Boolean =
      if pos < len then true
      else
        len = src.read(buf)
        pos = 0
        len > 0

    def close(): Unit = src.close()

    def readLine(): Option[(String, Int, Boolean)] =
      if !ensure() then None
      else
        val out = new java.io.ByteArrayOutputStream(256)
        var bytes = 0
        var truncated = false
        var doneLine = false
        while !doneLine do
          if !ensure() then doneLine = true
          else
            var i = pos
            while i < len && buf(i) != '\n'.toByte do i += 1
            val segLen = i - pos
            if segLen > 0 then
              bytes += segLen
              val room = maxLineBytes - out.size()
              if room > 0 then out.write(buf, pos, math.min(segLen, room))
              if segLen > room then truncated = true
            if i < len then
              bytes += 1
              pos = i + 1
              doneLine = true
            else pos = len
        Some((out.toString("UTF-8"), bytes, truncated))

  /** 流式读一个 `_full.jsonl`（可从 `from` 字节偏移**续读**）并收集对象引用。
    * 内存上界 = 单行（[[CappedLineReader]]）；读取量上界 = `budgetBytes` + 单行。
    *
    * @return `(本次读入字节数, 是否遇到超限行, 是否读到文件尾)`——`false,false` 组合
    *         即「预算耗尽、停在行边界」，游标 = `from + 读入字节数`。 */
  private def collectReferencedHashes(
      file: Path,
      from: Long,
      usedHashes: scala.collection.mutable.Set[String],
      budgetBytes: Long,
      maxLineBytes: Int
  ): (Long, Boolean, Boolean) =
    var read = 0L
    var truncated = false
    var hitEof = false
    val ch = Files.newByteChannel(file, StandardOpenOption.READ)
    try
      if from > 0 then ch.position(from)
      val reader = new CappedLineReader(java.nio.channels.Channels.newInputStream(ch), maxLineBytes)
      try
        var line = reader.readLine()
        var stop = false
        while line.isDefined && !stop do
          val (text, n, trunc) = line.get
          read += n
          if trunc then
            truncated = true
            stop = true
          else
            if text.nonEmpty then
              io.circe.parser.parse(text).toOption.flatMap(_.asObject) match
                case Some(obj) =>
                  obj("system_ref").flatMap(_.asString).foreach(usedHashes += _)
                  obj("tools_ref").flatMap(_.asString).foreach(usedHashes += _)
                  obj("message_refs")
                    .flatMap(_.asArray)
                    .foreach:
                      _.foreach(_.asString.foreach(usedHashes += _))
                case None => ()
            // 只在行边界处检查预算：停点永远是行边界（游标可安全续读）
            if read >= budgetBytes then stop = true
            else line = reader.readLine()
        hitEof = line.isEmpty
      finally reader.close()
    finally ch.close()
    (read, truncated, hitEof)

end LlmLogWriter
