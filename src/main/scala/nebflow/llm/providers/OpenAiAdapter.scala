package nebflow.llm.providers

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import fs2.Stream
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.NebflowLogger
import nebflow.llm.{AdapterResponse, ProviderAdapter, ProviderSearchKind, SendMessageParams}
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*

import scala.collection.mutable
import scala.concurrent.duration.*

/** Per-index accumulator for one streaming tool call (OpenAI adapter).
  * The StringBuilder and the empty-id/name counters are deliberately mutable
  * and updated in place inside Ref.modify — SSE lines are evaluated
  * sequentially per request (evalMap), the Ref only threads state across
  * chunks, so there is no concurrent access.
  *
  * The empty-id/name bookkeeping aggregates qwen's argument-stream
  * continuation frames (literal "" id/name) so the adapter can emit ONE
  * summary WARN per tool call at the finish flush instead of one WARN per
  * frame (~26 frames/call flooded logs at peak 1038 lines/min, 2026-08-21).
  */
private[providers] final class ToolCallEntry(val id: String, val name: String, initialArgs: String = ""):
  val sb = new StringBuilder(initialArgs)
  /** Continuation frames with literal empty id/name merged into this call. */
  var emptyIdNameFrames = 0
  /** Total argument chars contributed by those frames. */
  var emptyIdNameChars = 0
  /** Raw frame samples for the aggregated summary (first few only). */
  val emptyIdNameSamples = mutable.ListBuffer.empty[String]
end ToolCallEntry

class OpenAiAdapter(baseUrl: String, apiKey: String, backend: StreamBackend[IO, Fs2Streams[IO]])
    extends ProviderAdapter[IO]:
  private val base = baseUrl.replaceAll("/+$", "")
  private val logger = NebflowLogger.forName("nebflow.llm.openai")

  /** Full endpoint URL: use base as-is if it already points to /chat/completions, otherwise append. */
  private val endpoint =
    if base.endsWith("/chat/completions") then base else s"$base/chat/completions"

  /**
   * Map Anthropic-style budget_tokens to OpenAI reasoning_effort.
   *
   * OpenAI doesn't have a quantitative reasoning budget, only a qualitative
   * effort scale.  This mapping gives the user a natural continuum:
   *   ≤ 2048   → low
   *   2049…8192   → medium
   *   8193…32768  → high
   *   > 32768     → xhigh
   */
  private def budgetToEffort(budget: Int): String =
    if budget <= 2048 then "low"
    else if budget <= 8192 then "medium"
    else if budget <= 32768 then "high"
    else "xhigh"

  /**
   * Build thinking-related request body based on the model.
   *  - GLM models: send `thinking: { type: "enabled" }` (GLM's native format)
   *  - OpenAI o-series: send `reasoning_effort` (OpenAI's format)
   *  - Others: send nothing (API will ignore unknown params)
   */
  private def thinkingBody(model: String, thinking: Option[io.circe.Json]): Json =
    thinking match
      case Some(t) if t.hcursor.get[String]("type").toOption.contains("enabled") =>
        val m = model.toLowerCase
        if m.contains("glm") then
          // GLM uses its own `thinking` parameter, not OpenAI's `reasoning_effort`
          Json.obj("thinking" -> Json.obj("type" -> "enabled".asJson))
        else
          val budget = t.hcursor.get[Int]("budget_tokens").getOrElse(32000)
          Json.obj("reasoning_effort" -> budgetToEffort(budget).asJson)
      case _ => Json.obj()

  /** Build system message from stable + dynamic parts for OpenAI's messages format. */
  private[providers] def buildSystemMessage(params: SendMessageParams): Option[Json] =
    val stable = params.systemStable.filter(_.nonEmpty)
    val dynamic = params.systemDynamic.filter(_.nonEmpty)
    val fallback = params.messages.find(_.role == MessageRole.System).map(_.textContent).filter(_.nonEmpty)
    val text = stable.orElse(fallback) match
      case Some(s) => if dynamic.nonEmpty then s"$s\n\n${dynamic.get}" else s
      case None => dynamic.orNull
    if text != null && text.nonEmpty then Some(Json.obj("role" -> "system".asJson, "content" -> text.asJson))
    else None

  private[providers] def toOpenAiMessages(messages: List[Message]): List[Json] =
    messages.filterNot(_.role == MessageRole.System).flatMap { msg =>
      val role = msg.role match
        case MessageRole.User => "user"
        case MessageRole.Assistant => "assistant"
        case _ => "user"

      msg.content match
        case Left(text) =>
          List(Json.obj("role" -> Json.fromString(role), "content" -> Json.fromString(text)))
        case Right(blocks) =>
          val textParts = blocks.collect { case ContentBlock.Text(t) => t }
          val imageParts = blocks.collect { case ContentBlock.Image(data, mediaType) => (data, mediaType) }
          val toolUseParts = blocks.collect { case ContentBlock.ToolUse(id, name, input) => (id, name, input) }
          val toolResultParts = blocks.collect { case ContentBlock.ToolResult(toolUseId, content, _) =>
            (toolUseId, content)
          }

          if toolUseParts.nonEmpty then
            List(
              Json.obj(
                "role" -> "assistant".asJson,
                "content" -> (if textParts.nonEmpty then textParts.mkString("\n").asJson else Json.Null),
                "tool_calls" -> Json.fromValues(toolUseParts.map { case (id, name, input) =>
                  Json.obj(
                    "id" -> id.asJson,
                    "type" -> "function".asJson,
                    "function" -> Json.obj(
                      "name" -> name.asJson,
                      "arguments" -> Json.fromJsonObject(input).noSpaces.asJson
                    )
                  )
                })
              )
            )
          else if toolResultParts.nonEmpty then
            // OpenAI requires each tool result to be a separate message with its own tool_call_id
            toolResultParts.map { case (id, content) =>
              Json.obj(
                "role" -> "tool".asJson,
                "content" -> content.asJson,
                "tool_call_id" -> id.asJson
              )
            }
          else
            val contentParts = scala.collection.mutable.ListBuffer.empty[Json]
            textParts.foreach(t => contentParts += Json.obj("type" -> "text".asJson, "text" -> t.asJson))
            imageParts.foreach { case (data, mediaType) =>
              contentParts += Json.obj(
                "type" -> "image_url".asJson,
                "image_url" -> Json.obj("url" -> s"data:$mediaType;base64,$data".asJson)
              )
            }
            if contentParts.nonEmpty then
              List(Json.obj("role" -> Json.fromString(role), "content" -> Json.fromValues(contentParts.toList)))
            else List(Json.obj("role" -> Json.fromString(role), "content" -> textParts.mkString("\n").asJson))
          end if
      end match
    }

  private def toolDefJson(t: ToolDefinition): Json =
    Json.obj(
      "type" -> "function".asJson,
      "function" -> Json.obj(
        "name" -> t.name.asJson,
        "description" -> t.description.asJson,
        "parameters" -> Json.fromJsonObject(t.inputSchema)
      )
    )

  private def toOpenAiTools(tools: List[ToolDefinition]): Json =
    Json.fromValues(tools.map(toolDefJson))

  // ── WebSearch P0: provider-native search injection ─────────────────
  // Injection shapes per provider (SearchProviderResolver.capabilityFor):
  //   zhipu  → {"type":"web_search","web_search":{"search_result":true}}
  //            (search_result:true makes the response carry the structured
  //            `web_search` field — the evidence for the Tier 2 executor)
  //   kimi   → {"type":"builtin_function","function":{"name":"$web_search"}}
  //            (round-trip tool: the model emits $web_search, AgentCore
  //            echoes the arguments back verbatim; thinking must be off)
  //   qwen   → request-level enable_search:true (no tools entry)

  private[providers] def searchToolEntries(kind: ProviderSearchKind): List[Json] =
    kind match
      case ProviderSearchKind.ZhipuWebSearchTool =>
        // enable:true is REQUIRED (omitting it → zhipu 1210 "API 调用参数有误",
        // verified against the real endpoint 2026-08-23). search_result:true
        // makes the response carry the structured `web_search` field — the
        // evidence for the Tier 2 executor.
        List(
          Json.obj(
            "type" -> "web_search".asJson,
            "web_search" -> Json.obj(
              "enable" -> true.asJson,
              "search_result" -> true.asJson
            )
          )
        )
      case ProviderSearchKind.KimiBuiltinWebSearch =>
        List(
          Json.obj(
            "type" -> "builtin_function".asJson,
            "function" -> Json.obj("name" -> nebflow.llm.SearchProviderResolver.KimiWebSearchToolName.asJson)
          )
        )
      case ProviderSearchKind.QwenEnableSearch => Nil

  /** Merge tools: agent function tools first, provider search tool APPENDED —
    * deepMerge would REPLACE the array, clobbering the agent's tool
    * definitions (the P0 spec's explicit deepMerge-order regression point). */
  private[providers] def bodyWithSearchTools(
      body: Json,
      tools: Option[List[ToolDefinition]],
      search: Option[ProviderSearchKind]
  ): Json =
    val agentTools = tools.getOrElse(Nil)
    val extra = search.toList.flatMap(searchToolEntries)
    if agentTools.isEmpty && extra.isEmpty then body
    else body.deepMerge(Json.obj("tools" -> Json.fromValues(agentTools.map(toolDefJson) ++ extra)))

  /** Thinking with kimi-search interplay: $web_search is mutually exclusive
    * with thinking — when kimi search is armed, skip the model's thinking
    * body entirely (incl. reasoning_effort) and force thinking disabled. */
  private[providers] def bodyWithSearchThinking(
      body: Json,
      model: String,
      thinking: Option[io.circe.Json],
      search: Option[ProviderSearchKind]
  ): Json =
    if search.contains(ProviderSearchKind.KimiBuiltinWebSearch) then
      body.deepMerge(Json.obj("thinking" -> Json.obj("type" -> "disabled".asJson)))
    else body.deepMerge(thinkingBody(model, thinking))

  /** Non-tools request-level extras (qwen enable_search). */
  private[providers] def searchRequestExtras(
      search: Option[ProviderSearchKind]
  ): Json =
    search match
      case Some(ProviderSearchKind.QwenEnableSearch) =>
        Json.obj("enable_search" -> true.asJson)
      case _ => Json.obj()

  /** Observability for provider-native search (smoke diagnostics): which
    * injection was armed for this request — one INFO line per request, only
    * when injection is active (no log spam in normal traffic). */
  private def logSearchInjection(params: SendMessageParams): Unit =
    params.providerSearch.foreach { kind =>
      logger.infoSync(
        s"provider search armed: $kind (session ${params.sessionId.getOrElse("-")} " +
          s"agent ${params.agentId.getOrElse("-")})"
      )
    }

  /** Structured search results from the response (zhipu `web_search` array /
    * qwen `search_info` object) — the Tier 2 evidence source. Both top-level
    * and message-level placements are checked (zhipu returns top-level
    * `web_search`; DashScope nests `search_info` under choices[0].message).
    * Streaming responses don't capture this in P0 (only the non-streaming
    * executor path consumes it). */
  private[providers] def extractSearchInfo(response: Json): Option[Json] =
    val message = response.hcursor.downField("choices").downN(0).downField("message")
    def both(h: io.circe.ACursor): Option[Json] =
      h.downField("web_search").as[Json].toOption
        .orElse(h.downField("search_info").as[Json].toOption)
    both(response.hcursor)
      .orElse(both(message))
      .filter(j => j.isArray || j.isObject)

  private[providers] def extractToolCalls(response: Json): List[ToolCall] =
    response.hcursor
      .downField("choices")
      .downN(0)
      .downField("message")
      .downField("tool_calls")
      .as[List[Json]]
      .getOrElse(Nil)
      .flatMap { tc =>
        val id = tc.hcursor.downField("id").as[String].getOrElse("")
        val name = tc.hcursor.downField("function").downField("name").as[String].getOrElse("")
        val args = tc.hcursor.downField("function").downField("arguments").as[String].getOrElse("{}")
        // qwen-degenerate guard (2026-08-20 incident): a tool call with an
        // empty name (and/or empty id) cannot pass the agent's allowed-tool
        // whitelist — it would surface as "Tool not available: " and send the
        // model into a retry storm. Drop it here instead of fabricating a
        // call that can never execute.
        if name.trim.isEmpty then
          logger.warnSync(
            s"dropped tool call with empty name (degenerate provider frame): ${tc.noSpaces.take(160)}"
          )
          None
        else
          val input = ToolInputJson.parseToolInput(name, args)
          // rawArguments kept byte-faithful for provider round-trip semantics
          // (kimi $web_search echo — see SearchProviderResolver.kimiEchoContent).
          Some(ToolCall(id, name, input, Some(args)))
      }

  /**
   * True if the response carries reasoning output (reasoning_content / thinking)
   * even when `content` is empty. Thinking models (GLM-5.2, DeepSeek reasoning)
   * can spend the whole token budget on reasoning — such a response is NOT empty.
   */
  private[providers] def hasReasoningContent(response: Json): Boolean =
    val message = response.hcursor.downField("choices").downN(0).downField("message")
    message
      .downField("reasoning_content")
      .as[String]
      .toOption
      .orElse(message.downField("thinking").as[String].toOption)
      .exists(_.trim.nonEmpty)

  def sendMessage(params: SendMessageParams): IO[AdapterResponse] =
    val systemMsg = buildSystemMessage(params)
    val baseMessages = toOpenAiMessages(params.messages)
    val allMessages = systemMsg.toList ++ baseMessages
    val body = Json.obj(
      "model" -> params.model.asJson,
      "messages" -> Json.fromValues(allMessages),
      "max_tokens" -> (params.maxTokens.getOrElse(Defaults.MaxTokensCompact)).asJson
    )
    // WebSearch P0: search tool appended AFTER agent tools (never replaces);
    // kimi search forces thinking disabled; qwen gets enable_search.
    val bodyWithTools = bodyWithSearchTools(body, params.tools, params.providerSearch)
    // Thinking parameters are model-specific: GLM uses `thinking`, OpenAI uses `reasoning_effort`
    val bodyWithThinking = bodyWithSearchThinking(bodyWithTools, params.model, params.thinking, params.providerSearch)
    val bodyWithSearchExtras = bodyWithThinking.deepMerge(searchRequestExtras(params.providerSearch))
    val bodyWithMetadata = (params.sessionId, params.agentId) match
      case (Some(sid), Some(aid)) =>
        bodyWithSearchExtras.deepMerge(
          Json.obj(
            "metadata" -> Json.obj(
              "session_id" -> sid.asJson,
              "agent_id" -> aid.asJson
            )
          )
        )
      case (Some(sid), _) =>
        bodyWithSearchExtras.deepMerge(Json.obj("metadata" -> Json.obj("session_id" -> sid.asJson)))
      case (_, Some(aid)) =>
        bodyWithSearchExtras.deepMerge(Json.obj("metadata" -> Json.obj("agent_id" -> aid.asJson)))
      case _ => bodyWithSearchExtras

    val request = basicRequest
      .post(uri"$endpoint")
      .header("Authorization", s"Bearer $apiKey")
      .header("content-type", "application/json")
      .body(bodyWithMetadata.noSpaces)
    IO.delay(logSearchInjection(params)) *>
    backend.send(request).flatMap { response =>
      response.body match
        case Left(error) =>
          // 审计 20260903 子项②：结构化 HttpError（与 AnthropicAdapter 同款）——
          // classifyError 据状态码区分 400 Format（不驱逐）与 Auth/配额（驱逐）。
          IO.raiseError(sttp.client4.HttpError(error, response.code))
        case Right(bodyStr) =>
          IO.defer {
            parse(bodyStr) match
              case Left(err) => IO.raiseError(new RuntimeException(s"Failed to parse response: ${err.message}"))
              case Right(json) =>
                IO.defer {
                  val reply = json.hcursor
                    .downField("choices")
                    .downN(0)
                    .downField("message")
                    .downField("content")
                    .as[String]
                    .getOrElse("")
                  val toolCalls = extractToolCalls(json)
                  val usage = json.hcursor.downField("usage").as[Json].toOption.map { u =>
                    TokenUsage(
                      inputTokens = u.hcursor.downField("prompt_tokens").as[Int].getOrElse(0),
                      outputTokens = u.hcursor.downField("completion_tokens").as[Int].getOrElse(0)
                    )
                  }
                  // Thinking models (GLM-5.2, DeepSeek reasoning) may return a
                  // response whose `content` is empty because all tokens went to
                  // reasoning (reasoning_content / thinking fields). That is NOT
                  // an empty response — treat it as a successful reply so probes
                  // and real calls don't falsely fail or fall back.
                  val reasoning = hasReasoningContent(json)

                  // Empty response with no tool calls — treat as error to trigger fallback
                  if reply.isEmpty && toolCalls.isEmpty && !reasoning then
                    val finishReason = json.hcursor
                      .downField("choices")
                      .downN(0)
                      .downField("finish_reason")
                      .as[String]
                      .toOption
                      .getOrElse("")
                    val detail = if finishReason.nonEmpty then s" (finish_reason: $finishReason)" else ""
                    IO.raiseError(
                      new RuntimeException(
                        s"LLM returned empty response$detail"
                      )
                    )
                  else
                    val searchInfo = extractSearchInfo(json)
                    IO.pure(AdapterResponse(reply, toolCalls, usage, searchInfo))
                  end if
                }
          }
    }

  end sendMessage

  def sendMessageStream(params: SendMessageParams): Stream[IO, StreamChunk] =
    val systemMsg = buildSystemMessage(params)
    val baseMessages = toOpenAiMessages(params.messages)
    val allMessages = systemMsg.toList ++ baseMessages
    val body = Json.obj(
      "model" -> params.model.asJson,
      "messages" -> Json.fromValues(allMessages),
      "max_tokens" -> (params.maxTokens.getOrElse(Defaults.MaxTokensCompact)).asJson,
      "stream" -> true.asJson,
      "stream_options" -> Json.obj("include_usage" -> true.asJson)
    )    // WebSearch P0: same injection chain as the non-streaming path (see
    // sendMessage) — the main conversation is streaming, so provider-native
    // search must be armed here too.
    val bodyWithTools = bodyWithSearchTools(body, params.tools, params.providerSearch)
    // Thinking parameters are model-specific: GLM uses `thinking`, OpenAI uses `reasoning_effort`
    val bodyWithThinking = bodyWithSearchThinking(bodyWithTools, params.model, params.thinking, params.providerSearch)
    val bodyWithSearchExtras = bodyWithThinking.deepMerge(searchRequestExtras(params.providerSearch))
    val bodyWithMetadata = (params.sessionId, params.agentId) match
      case (Some(sid), Some(aid)) =>
        bodyWithSearchExtras.deepMerge(
          Json.obj(
            "metadata" -> Json.obj(
              "session_id" -> sid.asJson,
              "agent_id" -> aid.asJson
            )
          )
        )
      case (Some(sid), _) =>
        bodyWithSearchExtras.deepMerge(Json.obj("metadata" -> Json.obj("session_id" -> sid.asJson)))
      case (_, Some(aid)) =>
        bodyWithSearchExtras.deepMerge(Json.obj("metadata" -> Json.obj("agent_id" -> aid.asJson)))
      case _ => bodyWithSearchExtras

    Stream.eval(IO.delay(logSearchInjection(params))).drain ++
      Stream.eval(IO.ref(Map.empty[Int, ToolCallEntry])).flatMap { toolCallState =>
      val request = basicRequest
        .post(uri"$endpoint")
        .header("Authorization", s"Bearer $apiKey")
        .header("content-type", "application/json")
        .body(bodyWithMetadata.noSpaces)
        .response(asStreamUnsafe(Fs2Streams[IO]))
        .readTimeout(Defaults.LlmReadTimeoutSec.seconds)

      Stream.eval(backend.send(request)).flatMap { response =>
        response.body match
          case Left(error) =>
            // 同 sendMessage：结构化 HttpError（子项②）。
            Stream.eval(IO.raiseError(sttp.client4.HttpError(error, response.code)))
          case Right(byteStream) =>
            // If the stream dies mid-tool-call (transport error, or downstream
            // cancellation from the no-progress watchdog / a fallback switch),
            // the finish flush never runs — this finalizer emits whatever
            // empty-id/name summaries were already counted so aggregation
            // never silently loses content (日志完整性优先). After a normal
            // finish flush the state map is empty and this is a no-op;
            // getAndSet makes it idempotent under any termination path.
            parseOpenAiSseIncrementally(byteStream, toolCallState, params)
              .onFinalize(flushEmptyIdNameSummaries(toolCallState, params))
      }
    }

  end sendMessageStream

  private def parseOpenAiSseIncrementally(
    byteStream: Stream[IO, Byte],
    toolCallState: Ref[IO, Map[Int, ToolCallEntry]],
    params: SendMessageParams
  ): Stream[IO, StreamChunk] =
    byteStream
      .through(fs2.text.utf8.decode)
      .through(fs2.text.lines)
      .filter(_.nonEmpty)
      .evalMap { line =>
        if line.startsWith("data:") then
          val data = line.drop(5).trim
          if data == "[DONE]" then IO.pure(Nil)
          else processOpenAiData(data, toolCallState, params)
        else IO.pure(Nil)
      }
      .flatMap(cs => if cs.nonEmpty then Stream.emits(cs) else Stream.empty)

  private[providers] def processOpenAiData(
    data: String,
    toolCallState: Ref[IO, Map[Int, ToolCallEntry]],
    params: SendMessageParams
  ): IO[List[StreamChunk]] =
    def makeMeta: LlmMeta = LlmMeta(
      sessionId = params.sessionId.getOrElse(""),
      agentId = params.agentId.getOrElse(""),
      providerId = "openai",
      model = params.model,
      durationMs = 0
    )
    parse(data) match
      case Left(err) =>
        // Observability (issue #18 follow-up): dropped SSE data used to be
        // invisible; log so malformed provider frames are diagnosable.
        NebflowLogger
          .forName("nebflow.llm.openai")
          .warn(s"dropped unparseable SSE data (${err.message}): ${data.take(120)}")
          .as(Nil)
      case Right(json) =>
        // Check for usage-only chunk (stream_options.include_usage sends a final chunk with empty choices)
        val usageOpt = json.hcursor.downField("usage").as[Json].toOption.map { u =>
          val promptTokens = u.hcursor.downField("prompt_tokens").as[Int].getOrElse(0)
          val cachedTokens =
            u.hcursor.downField("prompt_tokens_details").downField("cached_tokens").as[Option[Int]].toOption.flatten
          TokenUsage(
            inputTokens = promptTokens,
            outputTokens = u.hcursor.downField("completion_tokens").as[Int].getOrElse(0),
            cacheReadTokens = cachedTokens,
            cacheWriteTokens = None
          )
        }
        val choicesEmpty = json.hcursor.downField("choices").as[List[Json]].toOption.exists(_.isEmpty)
        if usageOpt.isDefined && choicesEmpty then IO.pure(List(StreamChunk.Done(None, usageOpt, Some(makeMeta), None)))
        else
          val delta = json.hcursor.downField("choices").downN(0).downField("delta").as[Json].toOption
          val finishReason = json.hcursor.downField("choices").downN(0).downField("finish_reason").as[String].toOption

          delta match
            case None => IO.pure(Nil)
            case Some(d) =>
              val textChunks = d.hcursor.downField("content").as[String].toOption.filter(_.trim.nonEmpty).toList
              val textDeltas = textChunks.map(StreamChunk.TextDelta.apply)
              // Some OpenAI-compatible providers (GLM, DeepSeek, etc.) return reasoning/thinking
              // content in separate fields during thinking mode. Emit them as ThinkingDelta so they
              // are recognized as content and prevent "Stream completed with no content" errors.
              val thinkText = d.hcursor
                .downField("reasoning_content")
                .as[String]
                .toOption
                .orElse(d.hcursor.downField("thinking").as[String].toOption)
                .filter(_.trim.nonEmpty)
              val thinkingDeltas = thinkText.map(t => List(StreamChunk.ThinkingDelta(t))).getOrElse(Nil)
              val allTextDeltas = thinkingDeltas ++ textDeltas

              d.hcursor.downField("tool_calls").as[List[Json]].toOption match
                case Some(tcs) =>
                  // Fragment classification. A fragment STARTS a tool call only
                  // when both id and name are present AND non-empty. DashScope
                  // compatible-mode (qwen) streams its argument continuation
                  // frames with literal EMPTY strings for id/name ("" on the
                  // wire, not absent — their standard continuation shape,
                  // ~26 frames per tool call; 2026-08-20 incident: treating
                  // those as starts produced ToolCall(name=""), which the
                  // agent's allowed-tool whitelist dropped as "Tool not
                  // available: <empty>" for EVERY tool, a two-day retry
                  // storm. A late degenerate fragment under the old code also
                  // clobbered a valid entry). Empty-id/name frames now route
                  // to the continuation branch — never starting, never
                  // clobbering — and are counted per call; ONE aggregated
                  // summary WARN is emitted at the finish flush (the
                  // per-frame WARN flooded logs at peak 1038 lines/min,
                  // 2026-08-21; content preserved, only granularity changed).
                  def continueFragment(
                    acc: List[StreamChunk],
                    index: Int,
                    args: Option[String],
                    emptyIdNameRaw: Option[String] = None
                  ): IO[List[StreamChunk]] =
                    toolCallState
                      .modify { m =>
                        m.get(index) match
                          case Some(entry) =>
                            args.foreach(entry.sb.append)
                            emptyIdNameRaw.foreach { raw =>
                              entry.emptyIdNameFrames += 1
                              entry.emptyIdNameChars += args.map(_.length).getOrElse(0)
                              if entry.emptyIdNameSamples.size < MaxEmptyIdNameSamples then
                                entry.emptyIdNameSamples += raw
                            }
                            val chunks =
                              args.filter(_.nonEmpty).map(a => StreamChunk.ToolArgDelta(entry.name, a)).toList
                            (m, (chunks, false))
                          case None => (m, (Nil, emptyIdNameRaw.isDefined))
                      }
                      .flatMap { case (chunks, orphan) =>
                        // Orphan empty-id/name frame: no valid start seen for
                        // this index, its args are dropped, and no flush can
                        // ever cover a call that never started — log it now
                        // (0 occurrences in production 2026-08-20/21; this
                        // stays silent in normal operation and is the only
                        // remaining per-frame WARN path).
                        if orphan then
                          logger
                            .warn(
                              s"orphan empty-id/name continuation frame dropped (no valid start for index $index): " +
                                emptyIdNameRaw.getOrElse("")
                            )
                            .as(acc ++ chunks)
                        else IO.pure(acc ++ chunks)
                      }

                  tcs
                    .foldM(Nil: List[StreamChunk]) { (acc, tc) =>
                      val index = tc.hcursor.downField("index").as[Int].getOrElse(0)
                      val id = tc.hcursor.downField("id").as[String].toOption
                      val name = tc.hcursor.downField("function").downField("name").as[String].toOption
                      val args = tc.hcursor.downField("function").downField("arguments").as[String].toOption

                      (id, name) match
                        case (Some(toolId), Some(toolName))
                            if toolId.nonEmpty && toolName.nonEmpty =>
                          toolCallState
                            .modify { m =>
                              // Some OpenAI-compatible providers repeat id+name
                              // in every fragment chunk. Resetting the builder
                              // per chunk kept only the LAST fragment, so the
                              // accumulated JSON no longer parsed and arguments
                              // were silently lost (same class as issue #18).
                              // Accumulate instead when the entry is the same call.
                              m.get(index) match
                                case Some(entry) if entry.id == toolId && entry.name == toolName =>
                                  args.foreach(entry.sb.append)
                                  (m.updated(index, entry), ())
                                case _ =>
                                  (m.updated(index, ToolCallEntry(toolId, toolName, args.getOrElse(""))), ())
                            }
                            .as {
                              val start = StreamChunk.ToolCallStart(toolName)
                              val argChunks =
                                args.filter(_.nonEmpty).map(a => StreamChunk.ToolArgDelta(toolName, a)).toList
                              acc ++ (start :: argChunks)
                            }
                        case (Some(_), Some(_)) =>
                          // qwen's standard argument-stream continuation frame
                          // (empty id/name) — merge as continuation, count for
                          // the aggregated flush summary. logger.warn returns
                          // IO[Unit]; never wrap it in IO.delay (builds-but-
                          // never-runs the inner IO — qa 2026-08-20 catch).
                          continueFragment(acc, index, args, Some(tc.noSpaces.take(160)))
                        case _ =>
                          continueFragment(acc, index, args)
                      end match
                    }
                    .flatMap { acc =>
                      if finishReason.contains("tool_calls") || finishReason.contains("function_call") then
                        toolCallState.getAndSet(Map.empty).flatMap { m =>
                          logEmptyIdNameSummaries(m, params).as {
                            val toolChunks = m.values.toList.map { entry =>
                              val input = ToolInputJson.parseToolInput(entry.name, entry.sb.toString)
                              StreamChunk.ToolCallChunk(
                                ToolCall(entry.id, entry.name, input, Some(entry.sb.toString))
                              )
                            }
                            // Include any text/thinking deltas from this chunk AND a Done chunk,
                            // consistent with the case None branch below.
                            allTextDeltas ++ acc ++ toolChunks :+ StreamChunk.Done(
                              finishReason,
                              usageOpt,
                              Some(makeMeta),
                              None
                            )
                          }
                        }
                      else if finishReason.isDefined then
                        IO.pure(allTextDeltas ++ acc :+ StreamChunk.Done(finishReason, usageOpt, Some(makeMeta), None))
                      else IO.pure(allTextDeltas ++ acc)
                    }
                case None =>
                  // finish_reason may arrive in a chunk with empty delta (no tool_calls field).
                  // Flush accumulated tool call state when finish_reason indicates tool use.
                  if finishReason.exists(fr => fr.contains("tool_calls") || fr.contains("function_call")) then
                    toolCallState.getAndSet(Map.empty).flatMap { m =>
                      logEmptyIdNameSummaries(m, params).as {
                        val toolChunks = m.values.toList.map { entry =>
                          val input = ToolInputJson.parseToolInput(entry.name, entry.sb.toString)
                          StreamChunk.ToolCallChunk(
                            ToolCall(entry.id, entry.name, input, Some(entry.sb.toString))
                          )
                        }
                        allTextDeltas ++ toolChunks :+ StreamChunk.Done(finishReason, usageOpt, Some(makeMeta), None)
                      }
                    }
                  else if finishReason.isDefined then
                    IO.pure(allTextDeltas :+ StreamChunk.Done(finishReason, usageOpt, Some(makeMeta), None))
                  else IO.pure(allTextDeltas)
              end match
          end match
        end if
    end match
  end processOpenAiData
  /** Raw frame samples kept per tool call for the aggregated summary. */
  private val MaxEmptyIdNameSamples = 3

  /** Emit ONE aggregated WARN per flushed tool call that received qwen-style
    * empty-id/name continuation frames — replaces the per-frame WARN that
    * flooded logs at ~26 lines per tool call (peak 1038 lines/min,
    * 2026-08-21). Content preserved: frame count, argument char volume,
    * merge health of the accumulated args, session/agent correlation (the
    * old per-frame WARN had none — multi-stream attribution was guesswork),
    * and the first raw frames (≤160 chars each, same truncation as before).
    */
  private def logEmptyIdNameSummaries(m: Map[Int, ToolCallEntry], params: SendMessageParams): IO[Unit] =
    m.toList.traverse_ { case (index, entry) =>
      IO.whenA(entry.emptyIdNameFrames > 0) {
        val mergeHealth =
          if entry.sb.isEmpty then "no args"
          else if parse(entry.sb.toString).isRight then "parsed OK"
          else "not strict JSON (rescue path)"
        logger.warn(
          s"empty-id/name continuation frames merged into tool ${entry.name} (index $index): " +
            s"${entry.emptyIdNameFrames} frames, ${entry.emptyIdNameChars} arg chars, " +
            s"merged args ${entry.sb.length} chars ($mergeHealth), " +
            s"session ${params.sessionId.getOrElse("-")} agent ${params.agentId.getOrElse("-")}; " +
            s"first frames: ${entry.emptyIdNameSamples.mkString(" | ")}"
        )
      }
    }

  /** Flush aggregated empty-id/name summaries when the stream terminates
    * abnormally (error or cancellation) so the finish-flush aggregation
    * never silently loses content it already counted.
    */
  private def flushEmptyIdNameSummaries(
    toolCallState: Ref[IO, Map[Int, ToolCallEntry]],
    params: SendMessageParams
  ): IO[Unit] =
    toolCallState.getAndSet(Map.empty).flatMap(logEmptyIdNameSummaries(_, params))

end OpenAiAdapter
