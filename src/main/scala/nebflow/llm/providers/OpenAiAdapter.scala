package nebflow.llm.providers

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import fs2.Stream
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.llm.*
import nebflow.shared.{NebflowLogger, *}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*

import scala.collection.mutable
import scala.concurrent.duration.*

/**
 * Per-index accumulator for one streaming tool call (OpenAI adapter).
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

class OpenAiAdapter(
  baseUrl: String,
  apiKey: String,
  backend: StreamBackend[IO, Fs2Streams[IO]],
  /**
   * 思考回传族（protocol-compat 批 2026-11-01）：true ⇒ assistant 历史里的
   * Thinking 块按 `reasoning_content` 回传。
   *
   * 与 AnthropicAdapter 的同名开关**语义对称、方向相反**：
   *   - anthropic 面：思考块是 content[] 的一员，缺了要 400；有则回传。
   *   - openai 面：思考内容是 `reasoning_content` 这个旁支字段，旧实现**从不
   *     回传**（`toOpenAiMessages` 只收 Text/Image/ToolUse/ToolResult），于是
   *     `requireThinkingPassback` 这个配置在 openai 面**是个哑开关**——配了也
   *     不生效（registry 只把它交给 AnthropicAdapter）。本批补上：配置说了要
   *     回传，就必须真的回传。
   */
  requireThinkingPassback: Boolean = false,
  /** 本适配器服务的 provider 名（路由自述 + 诊断串；见 [[WireShape]]）。 */
  providerId: String = "unknown",
  /**
   * provider 级额外 HTTP 头（`ProviderConfig.headers`，protocol-compat 批）。
   * 装配唯一一点 = [[HttpHeaders]]：引擎缺省必带 User-Agent，本参数覆盖/追加。
   * cmdcode（OpenAI 面）的 403/1010 事故就是无 UA 被 Cloudflare 边缘挡下。
   */
  providerHeaders: Option[Map[String, String]] = None,
  effortAllowlist: Option[Map[String, Map[String, List[ThinkingEffort]]]] = None
) extends ProviderAdapter[IO]:
  private val base = baseUrl.replaceAll("/+$", "")
  private val logger = NebflowLogger.forName("nebflow.llm.openai")

  /** Full endpoint URL: use base as-is if it already points to /chat/completions, otherwise append. */
  private val endpoint =
    if base.endsWith("/chat/completions") then base else s"$base/chat/completions"

  /**
   * Build thinking-related request body based on the model.
   *  - GLM models: send `thinking: { type: "enabled" }` (GLM's native format)
   *  - OpenAI o-series: send `reasoning_effort` (OpenAI's format)
   *  - Others: send nothing (API will ignore unknown params)
   *
   * Compatible protocol and model names are not evidence of endpoint effort support;
   * unknown endpoints omit reasoning_effort rather than converting a numeric budget.
   */
  private def thinkingBody(model: String, thinking: Option[io.circe.Json], resolved: Option[ResolvedThinking]): Json =
    thinking match
      case Some(t) if t.hcursor.get[String]("type").toOption.contains("enabled") =>
        val m = model.toLowerCase
        if m.contains("glm") then
          // GLM uses its own `thinking` parameter, not OpenAI's `reasoning_effort`
          Json.obj("thinking" -> Json.obj("type" -> "enabled".asJson))
        else
          resolved
            .filter(_.enabled)
            .flatMap(_.effort)
            .filter(e => effortAllowlist.flatMap(_.get(endpoint)).flatMap(_.get(model)).exists(_.contains(e)))
            .fold(Json.obj())(e => Json.obj("reasoning_effort" -> e.wireName.asJson))
      case _ => Json.obj()

  /** Build a stable system prefix containing instructions and route identity. */
  private[providers] def buildSystemMessage(params: SendMessageParams): Option[Json] =
    val stable = params.systemStable.filter(_.nonEmpty)
    // Per-request context must not invalidate the system/history cache prefix.
    val dynamic = Some(WireShape.routeBanner(providerId, params.model)).filter(_.nonEmpty)
    val fallback = params.messages.find(_.role == MessageRole.System).map(_.textContent).filter(_.nonEmpty)
    val text = stable.orElse(fallback) match
      case Some(s) => if dynamic.nonEmpty then s"$s\n\n${dynamic.get}" else s
      case None => dynamic.orNull
    if text != null && text.nonEmpty then Some(Json.obj("role" -> "system".asJson, "content" -> text.asJson))
    else None

  /**
   * Shared by streaming and non-streaming sends; context follows all tool results.
   *
   * cache-warmup 批（2026-11-06）：临时段顺序 = REQUEST-ONLY 提醒段 → systemDynamic，
   * 一律排在持久历史之后（OpenAI 兼容面是隐式前缀缓存：拼在尾部即保前缀逐字节
   * 不变；语义与 Anthropic 面一致，CachePrefixAssemblySpec 判据⑤钉两面）。
   */
  private[providers] def requestMessages(params: SendMessageParams, passback: Boolean): List[Json] =
    val history = buildSystemMessage(params).toList ++ toOpenAiMessages(params.messages, passback)
    val roTail = params.requestOnlyContext.map(_.textContent).filter(_.nonEmpty).map { text =>
      Json.obj("role" -> "user".asJson, "content" -> text.asJson)
    }
    val tail = params.systemDynamic.filter(_.nonEmpty).map { text =>
      Json.obj("role" -> "user".asJson, "content" -> text.asJson)
    }
    val ephemeral = roTail ++ tail.toList
    // 可归因观测（cache-warmup 批）：最终 wire 装配点采指纹（sessionId=None 的
    // 辅助面在 observeWire 内零采集；指纹无正文）。
    CacheAttribution.observeWire("openai", params, providerId, history, ephemeral)
    history ++ ephemeral

  private[providers] def toOpenAiMessages(
    messages: List[Message],
    passback: Boolean = false
  ): List[Json] =
    val replayThinking = passback || requireThinkingPassback
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
          // 思考回传（protocol-compat 批 2026-11-01）：openai 面把推理内容放在
          // 旁支字段 reasoning_content，历史里不回传 ⇒ 要求回传的族（DeepSeek
          // 系）后续轮 400。只有配置显式要求时才带（README 语义：显式配置永远赢）。
          val reasoningText =
            if replayThinking then
              Some(blocks.collect { case ContentBlock.Thinking(t, _) => t }.filter(_.trim.nonEmpty).mkString("\n"))
                .filter(_.nonEmpty)
            else None

          // 一条消息里既有工具结果又有正文时，旧实现走 toolResult 分支
          // **把正文整段丢掉**（用户文本静默消失）。现在正文单独发一条 user
          // 消息，排在 tool 消息之后——顺序上模型先看到结果、再看到追问。
          def toolResultMessages: List[Json] =
            toolResultParts.map { case (id, content) =>
              Json.obj("role" -> "tool".asJson, "content" -> content.asJson, "tool_call_id" -> id.asJson)
            }

          if toolUseParts.nonEmpty then
            List(
              Json
                .obj(
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
                .deepMerge(reasoningText.map(r => Json.obj("reasoning_content" -> r.asJson)).getOrElse(Json.obj()))
            )
          else if toolResultParts.nonEmpty then
            // OpenAI requires each tool result to be a separate message with its own tool_call_id
            toolResultMessages ++
              (if textParts.nonEmpty then
                 List(Json.obj("role" -> "user".asJson, "content" -> textParts.mkString("\n").asJson))
               else Nil)
          else
            val contentParts = scala.collection.mutable.ListBuffer.empty[Json]
            textParts.foreach(t => contentParts += Json.obj("type" -> "text".asJson, "text" -> t.asJson))
            imageParts.foreach { case (data, mediaType) =>
              contentParts += Json.obj(
                "type" -> "image_url".asJson,
                "image_url" -> Json.obj("url" -> s"data:$mediaType;base64,$data".asJson)
              )
            }
            val base =
              if contentParts.nonEmpty then
                List(Json.obj("role" -> Json.fromString(role), "content" -> Json.fromValues(contentParts.toList)))
              else List(Json.obj("role" -> Json.fromString(role), "content" -> textParts.mkString("\n").asJson))
            // 纯思考轮（无正文、无工具）：正文为空但 reasoning_content 要回传，
            // 否则这条 assistant 消息在历史里等于「什么都没说」。
            reasoningText match
              case Some(r) => base.map(_.deepMerge(Json.obj("reasoning_content" -> r.asJson)))
              case None => base
          end if
      end match
    }

  end toOpenAiMessages

  private def toolDefJson(t: ToolDefinition): Json =
    val base = Json.obj(
      "type" -> "function".asJson,
      "function" -> Json.obj(
        "name" -> t.name.asJson,
        "description" -> t.description.asJson,
        "parameters" -> Json.fromJsonObject(t.inputSchema)
      )
    )
    // dynload 批（2026-11-19）：原生 deferred 标记。仅在 DynamicToolLoad 判定为
    // Deferred 档时置位（本腿恒 ExecutionGate ⇒ 该键不出现，wire 逐字节不变）；
    // 支持 tool_search 的部署会按此标记把条目不纳入前缀。
    if t.deferLoading then base.deepMerge(Json.obj("defer_loading" -> Json.True)) else base

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

  /**
   * Merge tools: agent function tools first, provider search tool APPENDED —
   * deepMerge would REPLACE the array, clobbering the agent's tool
   * definitions (the P0 spec's explicit deepMerge-order regression point).
   */
  private[providers] def bodyWithSearchTools(
    body: Json,
    tools: Option[List[ToolDefinition]],
    search: Option[ProviderSearchKind]
  ): Json =
    val agentTools = tools.getOrElse(Nil)
    val extra = search.toList.flatMap(searchToolEntries)
    if agentTools.isEmpty && extra.isEmpty then body
    else body.deepMerge(Json.obj("tools" -> Json.fromValues(agentTools.map(toolDefJson) ++ extra)))

  /**
   * Thinking with kimi-search interplay: $web_search is mutually exclusive
   * with thinking — when kimi search is armed, skip the model's thinking
   * body entirely (incl. reasoning_effort) and force thinking disabled.
   */
  private[providers] def bodyWithSearchThinking(
    body: Json,
    model: String,
    thinking: Option[io.circe.Json],
    search: Option[ProviderSearchKind],
    resolved: Option[ResolvedThinking] = None
  ): Json =
    if search.contains(ProviderSearchKind.KimiBuiltinWebSearch) then
      body.deepMerge(Json.obj("thinking" -> Json.obj("type" -> "disabled".asJson)))
    else body.deepMerge(thinkingBody(model, thinking, resolved))

  /** Non-tools request-level extras (qwen enable_search). */
  private[providers] def searchRequestExtras(
    search: Option[ProviderSearchKind]
  ): Json =
    search match
      case Some(ProviderSearchKind.QwenEnableSearch) =>
        Json.obj("enable_search" -> true.asJson)
      case _ => Json.obj()

  /**
   * Observability for provider-native search (smoke diagnostics): which
   * injection was armed for this request — one INFO line per request, only
   * when injection is active (no log spam in normal traffic).
   */
  private def logSearchInjection(params: SendMessageParams): Unit =
    params.providerSearch.foreach { kind =>
      logger.infoSync(
        s"provider search armed: $kind (session ${params.sessionId.getOrElse("-")} " +
          s"agent ${params.agentId.getOrElse("-")})"
      )
    }

  /**
   * Structured search results from the response (zhipu `web_search` array /
   * qwen `search_info` object) — the Tier 2 evidence source. Both top-level
   * and message-level placements are checked (zhipu returns top-level
   * `web_search`; DashScope nests `search_info` under choices[0].message).
   * Streaming responses don't capture this in P0 (only the non-streaming
   * executor path consumes it).
   */
  private[providers] def extractSearchInfo(response: Json): Option[Json] =
    val message = response.hcursor.downField("choices").downN(0).downField("message")
    def both(h: io.circe.ACursor): Option[Json] =
      h.downField("web_search")
        .as[Json]
        .toOption
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
        // `arguments` 在部分兼容端点是**对象**而不是 JSON 字符串（规范要求字符串）。
        // 旧实现只读字符串 ⇒ 对象形态的参数被当成 "{}"，工具静默拿到空参数。
        val args = tc.hcursor
          .downField("function")
          .downField("arguments")
          .as[String]
          .toOption
          .orElse(
            tc.hcursor
              .downField("function")
              .downField("arguments")
              .focus
              .filter(j => j.isObject || j.isArray)
              .map(_.noSpaces)
          )
          .getOrElse("{}")
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

  /** 头装配唯一一点（[[HttpHeaders]]）：引擎缺省（含必带 UA）+ provider 覆盖。 */
  private def applyHeaders[B](req: sttp.client4.Request[B]): sttp.client4.Request[B] =
    HttpHeaders.applyTo(req.header("Authorization", s"Bearer $apiKey"), providerHeaders)

  /** 形状类失败：带 provider/model + 请求形状 + 响应形状的 HttpError（同 anthropic 面口径）。 */
  private def wireError(status: Int, body: String, params: SendMessageParams): sttp.client4.HttpError[String] =
    new sttp.client4.HttpError(
      s"${WireShape.diagnose(providerId, params.model, params.messages, Some(status), body)} " +
        s"| upstream=${WireShape.upstreamExcerpt(body)}${HttpHeaders.edgeBlockHint(status, body)} " +
        s"| raw=${Option(body).getOrElse("").take(600)}",
      sttp.model.StatusCode(status)
    )

  /**
   * 形状降级重试的参数与开关（protocol-compat 批）：上游说"思考必须回传" ⇒
   * 把历史里的 reasoning_content 打开再发一次。方向与 anthropic 面相反——那边
   * 思考块是正文成员、多则被判 400，这边思考是旁支字段、缺则被判 400。
   */
  private def thinkingPassbackParams(params: SendMessageParams): SendMessageParams = params

  def sendMessage(params: SendMessageParams): IO[AdapterResponse] =
    def attempt(p: SendMessageParams, passback: Boolean): IO[AdapterResponse] =
      val allMessages = requestMessages(p, passback)
      val body = Json.obj(
        "model" -> p.model.asJson,
        "messages" -> Json.fromValues(allMessages)
      )
      // maxTokens dropped from the request body (maxcfg batch 2026-09-16): the
      // output cap is no longer user configurable, and the OpenAI face must not
      // send `max_tokens` at all — it is a deprecated field, and o-series /
      // GPT-5 models reject it outright (400). Omitting it leaves the provider
      // default. WebSearch P0: search tool appended AFTER agent tools (never replaces);
      // kimi search forces thinking disabled; qwen gets enable_search.
      val bodyWithTools = bodyWithSearchTools(body, p.tools, p.providerSearch)
      // Thinking parameters are model-specific: GLM uses `thinking`, OpenAI uses `reasoning_effort`
      val bodyWithThinking = bodyWithSearchThinking(
        bodyWithTools,
        p.model,
        p.thinking,
        p.providerSearch,
        p.thinkingResolved
      )
      val bodyWithSearchExtras = bodyWithThinking.deepMerge(searchRequestExtras(p.providerSearch))
      val bodyWithMetadata = (p.sessionId, p.agentId) match
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

      // 🔴 缺陷①（2026-11-02，本线保留）：非流式请求必须显式带 readTimeout——sttp
      // client4 的 60s 缺省会把插件自动分配/小判官/健康探测腿在 60.0xx s 掐断。
      // 与流式面同口径给 LlmReadTimeoutSec（600s）；既有流式侧的结构（applyHeaders
      // 统一装头 + wireError）逐字保留。
      val request = applyHeaders(
        basicRequest
          .post(uri"$endpoint")
          .contentType("application/json")
          .body(bodyWithMetadata.noSpaces)
      ).readTimeout(Defaults.LlmReadTimeoutSec.seconds)
      IO.delay(logSearchInjection(p)) *>
        backend.send(request).flatMap { response =>
          if response.code.isClientError then
            val raw = response.body.fold(_.toString, identity)
            IO.raiseError(wireError(response.code.code, raw, p))
          else
            response.body match
              case Left(error) =>
                // 审计 20260903 子项②：结构化 HttpError（与 AnthropicAdapter 同款）——
                // classifyError 据状态码区分 400 Format（不驱逐）与 Auth/配额（驱逐）。
                IO.raiseError(sttp.client4.HttpError(error, response.code))
              case Right(bodyStr) =>
                IO.defer {
                  parse(bodyStr) match
                    case Left(err) =>
                      IO.raiseError(
                        new RuntimeException(
                          s"Failed to parse response: ${err.message} | " +
                            WireShape.diagnose(
                              providerId,
                              p.model,
                              p.messages,
                              Some(response.code.code),
                              bodyStr
                            )
                        )
                      )
                    case Right(json) =>
                      IO.defer {
                        val reply = textOfMessage(json)
                        val toolCalls = extractToolCalls(json)
                        val usage = json.hcursor.downField("usage").as[Json].toOption.map { u =>
                          // cache-hit 批（2026-11-06）：非流腿此前丢 cached_tokens（只有流腿解析
                          // prompt_tokens_details）——命中率聚合读数要求两腿同口径。
                          val cachedTokens =
                            u.hcursor
                              .downField("prompt_tokens_details")
                              .downField("cached_tokens")
                              .as[Option[Int]]
                              .toOption
                              .flatten
                          TokenUsage(
                            inputTokens = u.hcursor.downField("prompt_tokens").as[Int].getOrElse(0),
                            outputTokens = u.hcursor.downField("completion_tokens").as[Int].getOrElse(0),
                            cacheReadTokens = cachedTokens,
                            cacheWriteTokens = None
                          )
                        }
                        // cache-hit 批（2026-11-06）：聚合读数的 record 单点在
                        // interface.scala（真实候选腿；adapter 内 record 会与流腿双记）。
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
                              s"LLM returned empty response$detail | " +
                                WireShape.diagnose(
                                  providerId,
                                  p.model,
                                  p.messages,
                                  Some(response.code.code),
                                  bodyStr
                                )
                            )
                          )
                        else
                          val searchInfo = extractSearchInfo(json)
                          IO.pure(AdapterResponse(reply, toolCalls, usage, searchInfo))
                        end if
                      }
                }
        }
    end attempt

    // 单 provider 失败不许打死整批（作者令 17:45 第 3 条）：形状可自愈的一类
    // 失败就地降级重试一次（补回 reasoning_content），再谈向上抛。
    attempt(params, passback = false).handleErrorWith {
      case e: sttp.client4.HttpError[?]
          if WireShape.isThinkingPassbackRejection(
            e.statusCode.code,
            Option(e.body).map(_.toString).getOrElse("")
          ) =>
        nebflow.shared.NebflowLogger
          .forName("nebflow.llm.openai")
          .warn(
            s"thinking-passback rejection — degraded retry with reasoning_content replay: " +
              WireShape.routeLine(providerId, params.model)
          ) *> attempt(thinkingPassbackParams(params), passback = true)
      case other => IO.raiseError(other)
    }

  end sendMessage

  /** 非流式正文归一化：`content` 既可能是字符串，也可能是块数组（兼容端点）。 */
  private[providers] def textOfMessage(json: Json): String =
    val msg = json.hcursor.downField("choices").downN(0).downField("message")
    msg.downField("content").as[String].toOption.getOrElse {
      msg
        .downField("content")
        .as[List[Json]]
        .toOption
        .getOrElse(Nil)
        .flatMap { b =>
          WireShape.classifyBlock(b) match
            case WireShape.Block.Text(t) => Some(t)
            case WireShape.Block.Thinking(_, _) => None
            case WireShape.Block.Opaque(_) => None
        }
        .mkString("")
    }

  end textOfMessage

  def sendMessageStream(params: SendMessageParams): Stream[IO, StreamChunk] =
    def attempt(p: SendMessageParams, passback: Boolean): Stream[IO, StreamChunk] =
      val allMessages = requestMessages(p, passback)
      val body = Json.obj(
        "model" -> p.model.asJson,
        "messages" -> Json.fromValues(allMessages),
        "stream" -> true.asJson,
        "stream_options" -> Json.obj("include_usage" -> true.asJson)
      ) // maxTokens dropped from the request body (maxcfg batch 2026-09-16) — see
      // sendMessage above: o-series / GPT-5 reject `max_tokens` with a 400.
      // WebSearch P0: same injection chain as the non-streaming path (see
      // sendMessage) — the main conversation is streaming, so provider-native
      // search must be armed here too.
      val bodyWithTools = bodyWithSearchTools(body, p.tools, p.providerSearch)
      // Thinking parameters are model-specific: GLM uses `thinking`, OpenAI uses `reasoning_effort`
      val bodyWithThinking = bodyWithSearchThinking(
        bodyWithTools,
        p.model,
        p.thinking,
        p.providerSearch,
        p.thinkingResolved
      )
      val bodyWithSearchExtras = bodyWithThinking.deepMerge(searchRequestExtras(p.providerSearch))
      val bodyWithMetadata = (p.sessionId, p.agentId) match
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

      Stream.eval(IO.delay(logSearchInjection(p))).drain ++
        Stream.eval(IO.ref(Map.empty[Int, ToolCallEntry])).flatMap { toolCallState =>
          // 头装配在 .response/.readTimeout 之前完成（后两者返回 StreamRequest）。
          val request = applyHeaders(
            basicRequest
              .post(uri"$endpoint")
              .contentType("application/json")
              .body(bodyWithMetadata.noSpaces)
          ).response(asStreamUnsafe(Fs2Streams[IO])).readTimeout(Defaults.LlmReadTimeoutSec.seconds)

          // Hard-recovery P1: per-attempt backend when provided (per-request
          // HttpClient whose shutdownNow aborts exactly this request).
          Stream.eval(p.attemptBackend.getOrElse(backend).send(request)).flatMap { response =>
            if response.code.isClientError then
              // 流式请求的 body 是字节流，错误页只在 Left 侧（sttp 对非 2xx 不建流）
              val raw = response.body.fold(_.toString, _ => "(stream body not read: non-2xx)")
              Stream.eval(IO.raiseError(wireError(response.code.code, raw, p)))
            else
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
                  parseOpenAiSseIncrementally(byteStream, toolCallState, p)
                    .onFinalize(flushEmptyIdNameSummaries(toolCallState, p))
          }
        }
    end attempt

    // 降级重试（同 anthropic 面接缝论证：4xx 在响应头即判定，零块已发出 ⇒ 重试
    // 不会拼接两家输出）。
    attempt(params, passback = false).handleErrorWith {
      case e: sttp.client4.HttpError[?]
          if WireShape.isThinkingPassbackRejection(
            e.statusCode.code,
            Option(e.body).map(_.toString).getOrElse("")
          ) =>
        Stream
          .eval(
            nebflow.shared.NebflowLogger
              .forName("nebflow.llm.openai")
              .warn(
                s"thinking-passback rejection — degraded retry with reasoning_content replay: " +
                  WireShape.routeLine(providerId, params.model)
              )
          )
          .drain ++ attempt(thinkingPassbackParams(params), passback = true)
      case other => Stream.raiseError(other)
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
              // 正文归一化（protocol-compat 批）：`content` 规范上是字符串，但
              // 兼容端点会发数组（[{type:text,text:..}]）或 null。旧实现只认
              // 字符串 ⇒ 数组形态的正文整段丢失。统一走 WireShape 降级。
              // Whitespace-only deltas carry Markdown boundaries and must survive streaming.
              val textChunks =
                d.hcursor
                  .downField("content")
                  .as[String]
                  .toOption
                  .orElse {
                    d.hcursor
                      .downField("content")
                      .as[List[Json]]
                      .toOption
                      .map(
                        _.flatMap(b =>
                          WireShape.classifyBlock(b) match
                            case WireShape.Block.Text(t) => Some(t)
                            case WireShape.Block.Thinking(_, _) => None
                            case WireShape.Block.Opaque(_) => None
                        ).mkString("")
                      )
                  }
                  .filter(_.nonEmpty)
                  .toList
              val textDeltas = textChunks.map(StreamChunk.TextDelta.apply)
              // Some OpenAI-compatible providers (GLM, DeepSeek, etc.) return reasoning/thinking
              // content in separate fields during thinking mode. Emit them as ThinkingDelta so they
              // are recognized as content and prevent "Stream completed with no content" errors.
              // `reasoning`（无 _content 后缀）是部分网关的变体名，一并收。
              val thinkText = d.hcursor
                .downField("reasoning_content")
                .as[String]
                .toOption
                .orElse(d.hcursor.downField("thinking").as[String].toOption)
                .orElse(d.hcursor.downField("reasoning").as[String].toOption)
                .filter(_.nonEmpty)
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
                        case (Some(toolId), Some(toolName)) if toolId.nonEmpty && toolName.nonEmpty =>
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

  /**
   * Emit ONE aggregated WARN per flushed tool call that received qwen-style
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

  /**
   * Flush aggregated empty-id/name summaries when the stream terminates
   * abnormally (error or cancellation) so the finish-flush aggregation
   * never silently loses content it already counted.
   */
  private def flushEmptyIdNameSummaries(
    toolCallState: Ref[IO, Map[Int, ToolCallEntry]],
    params: SendMessageParams
  ): IO[Unit] =
    toolCallState.getAndSet(Map.empty).flatMap(logEmptyIdNameSummaries(_, params))

end OpenAiAdapter

/**
 * Model-list endpoints of the OpenAI-compatible face — declared here, not
 * derived by the gateway from a raw `baseUrl` (see `ModelListFaces`).
 *
 *   1. `{base}/models` — for this face `baseUrl` IS the version root (chat goes
 *      to `{base}/chat/completions`), which is also the shape the settings
 *      dialog's placeholder suggests (`https://api.example.com/v1`). Measured
 *      200 with 12 models on the Aliyun compatible-mode root.
 *   2. `{base}/v1/models` — declared for a `baseUrl` that stops above the
 *      version segment (vendor origin or a non-version prefix), where the
 *      implied segment still resolves the list: measured 200 on
 *      `https://api.deepseek.com/v1/models`. Omitted when `baseUrl` already
 *      ends in `/v1`, where it would build `…/v1/v1/models`.
 */
object OpenAiAdapter:

  def modelListUrls(baseUrl: String): List[String] =
    val base = baseUrl.replaceAll("/+$", "")
    if base.endsWith("/v1") then List(s"$base/models")
    else List(s"$base/models", s"$base/v1/models")
end OpenAiAdapter
