package nebflow.llm.providers

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import fs2.Stream
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.llm.{AdapterResponse, ProviderAdapter, SendMessageParams}
import nebflow.shared.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*

import scala.concurrent.duration.*

class AnthropicAdapter(
  baseUrl: String,
  apiKey: String,
  backend: StreamBackend[IO, Fs2Streams[IO]],
  // When true, unsigned thinking blocks from assistant history are replayed
  // (without a signature field) instead of dropped. DeepSeek's Anthropic-
  // compatible endpoint requires thinking blocks to be passed back in thinking
  // mode; real Anthropic rejects them without a signature. Set per provider
  // in ProviderRegistry.createAdapter.
  requireThinkingPassback: Boolean = false
) extends ProviderAdapter[IO]:
  private val base = baseUrl.replaceAll("/+$", "")

  /** Full endpoint URL: use base as-is if it already points to /v1/messages, otherwise append. */
  private val endpoint =
    if base.endsWith("/v1/messages") then base else s"$base/v1/messages"

  // Holds (inputTokens, cacheReadTokens, cacheCreationTokens) from message_start
  // private[providers] so specs can drive processAnthropicEvent.
  private[providers] case class Tokens(input: Int, cacheRead: Option[Int], cacheWrite: Option[Int])

  // private[providers] for spec access (pure JSON mapping, no backend needed)
  private[providers] def toAnthropicMessages(messages: List[Message]): List[Json] =
    mergeConsecutive(pairToolResults(messages.filterNot(_.role == MessageRole.System))).map { msg =>
      val role = msg.role match
        case MessageRole.User => "user"
        case MessageRole.Assistant => "assistant"
        case _ => "user"

      msg.content match
        case Left(text) =>
          Json.obj("role" -> Json.fromString(role), "content" -> Json.fromString(text))
        case Right(blocks) =>
          val content = blocks.map {
            case ContentBlock.Text(text) =>
              Json.obj("type" -> "text".asJson, "text" -> text.asJson)
            case ContentBlock.Image(data, mediaType) =>
              Json.obj(
                "type" -> "image".asJson,
                "source" -> Json.obj(
                  "type" -> "base64".asJson,
                  "media_type" -> mediaType.asJson,
                  "data" -> data.asJson
                )
              )
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
              if isError.contains(true) then base.deepMerge(Json.obj("is_error" -> true.asJson)) else base
            case ContentBlock.Thinking(thinking, signature) =>
              // Anthropic requires signature when thinking mode is enabled.
              // Providers that don't send signature (e.g. DeepSeek) will have None here.
              // Including a thinking block without signature causes API rejection.
              signature.filter(_.nonEmpty) match
                case Some(sig) =>
                  Json.obj(
                    "type" -> "thinking".asJson,
                    "thinking" -> thinking.asJson,
                    "signature" -> sig.asJson
                  )
                case None =>
                  // Unsigned thinking: replay it only for providers that demand
                  // passback (DeepSeek). Dropping it was the root cause of the
                  // recurring deepseek DOWN cycles on 2026-08-15 — its thinking
                  // mode rejects history that omits thinking blocks.
                  if requireThinkingPassback then
                    Json.obj(
                      "type" -> "thinking".asJson,
                      "thinking" -> thinking.asJson
                    )
                  else Json.Null
          }
          val filtered = content.filterNot(_ == Json.Null)
          if filtered.isEmpty then Json.obj("role" -> Json.fromString(role), "content" -> Json.fromString(" "))
          else Json.obj("role" -> Json.fromString(role), "content" -> Json.fromValues(filtered))
      end match
    }

  /**
   * Protocol-level pairing defense for Anthropic tool calls.
   *
   * Root cause (2026-08-23 deepseek 422 loop): a user(tool_result) message can
   * survive context cleanup while its preceding assistant(tool_use) message is
   * truncated away — the orphaned tool_result then heads the message list and
   * the provider rejects it (`Each tool_result block must have a corresponding
   * tool_use block in the previous message`). Observed live: Manager session
   * started with an orphan `[Output removed to free context space]` result
   * referencing a yesterday tool_use id → deepseek DOWN/recovered loop.
   *
   * Fix: scan the message list, collect every tool_use id seen in assistant
   * messages (in order), and drop tool_result blocks whose id was never seen.
   * A user message whose tool_use was consumed by cleanup (all remaining
   * blocks are the now-dangling tool_result's siblings) keeps its text —
   * dropping ONLY the orphaned result block, never whole messages.
   */
  private def pairToolResults(messages: List[Message]): List[Message] =
    val seen = scala.collection.mutable.HashSet.empty[String]
    messages.map {
      case msg @ Message(MessageRole.Assistant, Right(blocks), _, _) =>
        blocks.foreach {
          case ContentBlock.ToolUse(id, _, _) => seen += id
          case _                              => ()
        }
        msg
      case msg @ Message(MessageRole.User, Right(blocks), _, _) =>
        val paired = blocks.filter {
          case ContentBlock.ToolResult(toolUseId, _, _) => seen.contains(toolUseId)
          case _                                        => true
        }
        if paired.size == blocks.size then msg
        else
          val newBlocks =
            if paired.isEmpty then List(ContentBlock.Text("[dropped orphaned tool_result: referenced tool_use no longer in history]"))
            else paired
          msg.copy(content = Right(newBlocks))
      case other => other
    }

  /** Merge consecutive messages of the same role to prevent tool_use/tool_result pairing issues. */
  private def mergeConsecutive(messages: List[Message]): List[Message] =
    if messages.isEmpty then Nil
    else
      messages.tail.foldLeft(List(messages.head)) { (acc, msg) =>
        val last = acc.last
        if last.role == msg.role then
          val merged = mergeMessages(last, msg)
          acc.init :+ merged
        else acc :+ msg
      }

  private def mergeMessages(a: Message, b: Message): Message =
    val mergedContent = (a.content, b.content) match
      case (Left(aText), Left(bText)) =>
        Left(aText + "\n" + bText)
      case (Right(aBlocks), Right(bBlocks)) =>
        Right(aBlocks ++ bBlocks)
      case (Left(text), Right(blocks)) =>
        Right(ContentBlock.Text(text) +: blocks)
      case (Right(blocks), Left(text)) =>
        Right(blocks :+ ContentBlock.Text(text))
    Message(a.role, mergedContent, math.max(a.timestamp, b.timestamp))

  private def toAnthropicTools(tools: List[ToolDefinition]): Json =
    val toolJsons = tools.map { t =>
      Json.obj(
        "name" -> t.name.asJson,
        "description" -> t.description.asJson,
        "input_schema" -> Json.fromJsonObject(t.inputSchema)
      )
    }
    // Add cache_control to the last tool definition to extend the cache prefix through tools
    if toolJsons.isEmpty then Json.fromValues(Nil)
    else
      val (init, last) = (toolJsons.init, toolJsons.last)
      val lastWithCache = last.deepMerge(Json.obj("cache_control" -> Json.obj("type" -> "ephemeral".asJson)))
      Json.fromValues(init :+ lastWithCache)

  /** Build the system field as content blocks with cache_control on the stable prefix. */
  private def buildSystemBlocks(params: SendMessageParams): Json =
    val stableOpt = params.systemStable.filter(_.nonEmpty)
    val dynamicOpt = params.systemDynamic.filter(_.nonEmpty)
    // Fallback: if systemParts not provided, extract from messages (backward compat)
    val fallbackSystem = params.messages.find(_.role == MessageRole.System).map(_.textContent).filter(_.nonEmpty)

    val stable = stableOpt.orElse(fallbackSystem)
    if stable.isEmpty && dynamicOpt.isEmpty then Json.Null
    else
      val blocks = scala.collection.mutable.ListBuffer.empty[Json]
      stable.foreach { text =>
        // Stable part gets cache_control — this is the cache breakpoint shared across sessions
        blocks += Json.obj(
          "type" -> "text".asJson,
          "text" -> text.asJson,
          "cache_control" -> Json.obj("type" -> "ephemeral".asJson)
        )
      }
      dynamicOpt.foreach { text =>
        // Dynamic part (env info, reminders) — no cache_control, changes every turn
        blocks += Json.obj(
          "type" -> "text".asJson,
          "text" -> text.asJson
        )
      }
      Json.fromValues(blocks.toList)
    end if
  end buildSystemBlocks

  /**
   * 跨 provider thinking 重放适配（审计 20260903 子项④，flap 风暴底层缺陷）。
   *
   * 会话历史由「不产出 thinking 块的 provider」生成时（所有 OpenAI 协议
   * adapter 的 history 序列化都丢弃 thinking；GLM/zhipu 会话即此形态），
   * fallback 到 requireThinkingPassback 的 Anthropic 兼容端点（deepseek）
   * 会 400 "The content[].thinking in the thinking mode must be passed back
   * to the API"——thinking 模式下 assistant 历史缺 thinking 块。实测 25 次
   * DOWN 全部源于此（09-03 flap 风暴，p50 0.9s 探测秒回 UP 后再 400）。
   *
   * 取舍（转换优先、不可转换则剥离）：
   *  - 历史「有」thinking 块 → 原样回传（2026-08-15 既有契约，本签名 spec
   *    锁定；含签名块的回传行为不变）。
   *  - 历史「缺」thinking 块 → 转换不可能（无法伪造模型没产生的推理内容）
   *    → 剥离 = 本请求不进 thinking 模式（去掉 thinking 参数）。deepseek 对
   *    纯文本历史在非 thinking 模式下正常接受；后续轮次由 deepseek 自己产生
   *    thinking 块后自然恢复 thinking 模式。
   */
  private[providers] def effectiveThinking(params: SendMessageParams): Option[Json] =
    params.thinking match
      case Some(t) if requireThinkingPassback && hasAssistantWithoutThinking(params.messages) =>
        nebflow.core.NebflowLogger
          .forName("nebflow.llm.anthropic")
          .warn(
            s"thinking mode dropped for this request: ${params.model} requires thinking passback but " +
              "session history contains assistant messages without thinking blocks (cross-provider replay) — " +
              "replaying would 400 (audit 20260903 #4)"
          )
        None
      case other => other

  private def hasAssistantWithoutThinking(messages: List[Message]): Boolean =
    messages.exists {
      case Message(MessageRole.Assistant, Right(blocks), _, _) =>
        !blocks.exists(_.isInstanceOf[ContentBlock.Thinking])
      case Message(MessageRole.Assistant, Left(_), _, _) => true
      case _ => false
    }

  def sendMessage(params: SendMessageParams): IO[AdapterResponse] =
    val systemBlocks = buildSystemBlocks(params)
    val body = Json.obj(
      "model" -> params.model.asJson,
      "messages" -> Json.fromValues(toAnthropicMessages(params.messages)),
      "max_tokens" -> (params.maxTokens.getOrElse(Defaults.MaxTokens)).asJson
    )
    val bodyWithSystem =
      if systemBlocks != Json.Null then body.deepMerge(Json.obj("system" -> systemBlocks))
      else body
    val bodyWithTools = params.tools.filter(_.nonEmpty) match
      case Some(tools) => bodyWithSystem.deepMerge(Json.obj("tools" -> toAnthropicTools(tools)))
      case None => bodyWithSystem
    val bodyWithThinking = effectiveThinking(params) match
      case Some(t) => bodyWithTools.deepMerge(Json.obj("thinking" -> t))
      case None => bodyWithTools
    val bodyWithMetadata = (params.sessionId, params.agentId) match
      case (Some(sid), Some(aid)) =>
        bodyWithThinking.deepMerge(
          Json.obj(
            "metadata" -> Json.obj(
              "session_id" -> sid.asJson,
              "agent_id" -> aid.asJson
            )
          )
        )
      case (Some(sid), _) =>
        bodyWithThinking.deepMerge(Json.obj("metadata" -> Json.obj("session_id" -> sid.asJson)))
      case (_, Some(aid)) =>
        bodyWithThinking.deepMerge(Json.obj("metadata" -> Json.obj("agent_id" -> aid.asJson)))
      case _ => bodyWithThinking

    val request = basicRequest
      .post(uri"$endpoint")
      .header("x-api-key", apiKey)
      .header("anthropic-version", "2023-06-01")
      .header("content-type", "application/json")
      .body(bodyWithMetadata.noSpaces)

    backend.send(request).flatMap { response =>
      response.body match
        case Left(error) =>
          // 审计 20260903 子项②：非 2xx 升格为结构化 HttpError（带状态码）——
          // classifyError 的结构化分支据此区分 400 Format（不驱逐）与 Auth/404
          // 等确证死亡（驱逐）。旧 RuntimeException 文案无状态码，400 只能落
          // Unknown/Transient → 重试耗尽后照样 markDown（flap 主因）。
          IO.raiseError(sttp.client4.HttpError(error, response.code))
        case Right(bodyStr) =>
          IO.defer {
            parse(bodyStr) match
              case Left(err) => IO.raiseError(new RuntimeException(s"Failed to parse response: ${err.message}"))
              case Right(json) => IO.pure(parseNonStreamingResponse(json))
          }
    }

  end sendMessage

  private def parseNonStreamingResponse(json: Json): AdapterResponse =
    val content = json.hcursor.downField("content").as[List[Json]].getOrElse(Nil)
    val textBlocks = content.filter(_.hcursor.downField("type").as[String].toOption.contains("text"))
    val toolUseBlocks = content.filter(_.hcursor.downField("type").as[String].toOption.contains("tool_use"))
    val thinkingBlocks = content.filter(_.hcursor.downField("type").as[String].toOption.contains("thinking"))

    val reply = textBlocks.flatMap(_.hcursor.downField("text").as[String].toOption).mkString("")
    val toolCalls = toolUseBlocks.map { b =>
      val id = b.hcursor.downField("id").as[String].getOrElse("")
      val name = b.hcursor.downField("name").as[String].getOrElse("")
      // Non-streaming responses carry `input` as a JSON object; a non-object
      // input goes through the shared marker path instead of silently {}.
      val input = b.hcursor.downField("input").as[JsonObject] match
        case Right(obj) => obj
        case Left(_)    => ToolInputJson.malformedInput(b.hcursor.downField("input").as[io.circe.Json].getOrElse(io.circe.Json.Null).noSpaces)
      ToolCall(id, name, input)
    }

    val usage = json.hcursor.downField("usage").as[Json].toOption.map { u =>
      val inputTokens = u.hcursor.downField("input_tokens").as[Int].getOrElse(0)
      val cacheRead = u.hcursor.downField("cache_read_input_tokens").as[Option[Int]].toOption.flatten
      val cacheWrite = u.hcursor.downField("cache_creation_input_tokens").as[Option[Int]].toOption.flatten
      val totalInput = inputTokens + cacheRead.getOrElse(0) + cacheWrite.getOrElse(0)
      TokenUsage(
        inputTokens = totalInput,
        outputTokens = u.hcursor.downField("output_tokens").as[Int].getOrElse(0),
        cacheReadTokens = cacheRead,
        cacheWriteTokens = cacheWrite
      )
    }

    AdapterResponse(reply, toolCalls, usage)

  end parseNonStreamingResponse

  def sendMessageStream(params: SendMessageParams): Stream[IO, StreamChunk] =
    val systemBlocks = buildSystemBlocks(params)
    val body = Json.obj(
      "model" -> params.model.asJson,
      "messages" -> Json.fromValues(toAnthropicMessages(params.messages)),
      "max_tokens" -> (params.maxTokens.getOrElse(Defaults.MaxTokens)).asJson,
      "stream" -> true.asJson
    )
    val bodyWithSystem =
      if systemBlocks != Json.Null then body.deepMerge(Json.obj("system" -> systemBlocks))
      else body
    val bodyWithTools = params.tools.filter(_.nonEmpty) match
      case Some(tools) => bodyWithSystem.deepMerge(Json.obj("tools" -> toAnthropicTools(tools)))
      case None => bodyWithSystem
    val bodyWithThinking = effectiveThinking(params) match
      case Some(t) => bodyWithTools.deepMerge(Json.obj("thinking" -> t))
      case None => bodyWithTools
    val bodyWithMetadata = (params.sessionId, params.agentId) match
      case (Some(sid), Some(aid)) =>
        bodyWithThinking.deepMerge(
          Json.obj(
            "metadata" -> Json.obj(
              "session_id" -> sid.asJson,
              "agent_id" -> aid.asJson
            )
          )
        )
      case (Some(sid), _) =>
        bodyWithThinking.deepMerge(Json.obj("metadata" -> Json.obj("session_id" -> sid.asJson)))
      case (_, Some(aid)) =>
        bodyWithThinking.deepMerge(Json.obj("metadata" -> Json.obj("agent_id" -> aid.asJson)))
      case _ => bodyWithThinking

    Stream.eval(IO.ref(Map.empty[Int, (String, String, StringBuilder)])).flatMap { toolCallState =>
      Stream.eval(IO.ref(Tokens(0, None, None))).flatMap { tokenRef =>
        val request = basicRequest
          .post(uri"$endpoint")
          .header("x-api-key", apiKey)
          .header("anthropic-version", "2023-06-01")
          .header("content-type", "application/json")
          .body(bodyWithMetadata.noSpaces)
          .response(asStreamUnsafe(Fs2Streams[IO]))
          .readTimeout(Defaults.LlmReadTimeoutSec.seconds)

        // Hard-recovery P1: per-attempt backend when provided (per-request
        // HttpClient whose shutdownNow aborts exactly this request).
        Stream.eval(params.attemptBackend.getOrElse(backend).send(request)).flatMap { response =>
          response.body match
            case Left(error) =>
              // 同 sendMessage：结构化 HttpError 携带状态码（子项②）。
              Stream.eval(IO.raiseError(sttp.client4.HttpError(error, response.code)))
            case Right(byteStream) =>
              parseSseIncrementally(byteStream, toolCallState, tokenRef, params)
        }
      }
    }

  end sendMessageStream

  private def parseSseIncrementally(
    byteStream: Stream[IO, Byte],
    toolCallState: Ref[IO, Map[Int, (String, String, StringBuilder)]],
    tokenRef: Ref[IO, Tokens],
    params: SendMessageParams
  ): Stream[IO, StreamChunk] =
    Stream.eval(IO.ref(Option.empty[String])).flatMap { eventTypeRef =>
      byteStream
        .through(fs2.text.utf8.decode)
        .through(fs2.text.lines)
        .filter(_.nonEmpty)
        .evalMap { line =>
          if line.startsWith("event:") then eventTypeRef.set(Some(line.drop(6).trim)).as(List.empty[StreamChunk])
          else if line.startsWith("data:") then
            val data = line.drop(5).trim
            eventTypeRef.getAndSet(None).flatMap {
              case Some(et) => processAnthropicEvent(et, data, toolCallState, tokenRef, params)
              case None => processAnthropicEvent("", data, toolCallState, tokenRef, params)
            }
          else IO.pure(Nil)
        }
        .flatMap(cs => if cs.nonEmpty then Stream.emits(cs) else Stream.empty)
    }

  private[providers] def processAnthropicEvent(
    eventType: String,
    data: String,
    toolCallState: Ref[IO, Map[Int, (String, String, StringBuilder)]],
    tokenRef: Ref[IO, Tokens],
    params: SendMessageParams
  ): IO[List[StreamChunk]] =
    if data == "[DONE]" then IO.pure(Nil)
    else
      parse(data) match
        case Left(err) =>
          // Observability (issue #18 follow-up): dropped SSE data used to be
          // invisible; log so malformed provider frames are diagnosable.
          nebflow.core.NebflowLogger
            .forName("nebflow.llm.anthropic")
            .warn(s"dropped unparseable SSE data (${err.message}): ${data.take(120)}")
            .as(Nil)
        case Right(json) =>
          eventType match
            case "message_start" =>
              // Capture usage from message_start (message_delta only has output_tokens)
              val usageObj = json.hcursor.downField("message").downField("usage")
              val inputTokens = usageObj.downField("input_tokens").as[Int].getOrElse(0)
              val cacheRead = usageObj.downField("cache_read_input_tokens").as[Option[Int]].toOption.flatten
              val cacheWrite = usageObj.downField("cache_creation_input_tokens").as[Option[Int]].toOption.flatten
              nebflow.core.NebflowLogger
                .forName("nebflow.llm.anthropic")
                .infoSync(
                  s"message_start: model=${params.model} usage_json=${usageObj.as[Json].getOrElse(Json.Null).noSpaces} inputTokens=$inputTokens cacheRead=$cacheRead cacheWrite=$cacheWrite"
                )
              tokenRef.set(Tokens(inputTokens, cacheRead, cacheWrite)).as(Nil)
            case "content_block_delta" =>
              json.hcursor.downField("delta").downField("type").as[String].toOption match
                case Some("text_delta") =>
                  val text = json.hcursor.downField("delta").downField("text").as[String].getOrElse("")
                  IO.pure(if text.nonEmpty then List(StreamChunk.TextDelta(text)) else Nil)
                case Some("thinking_delta") =>
                  val thinking = json.hcursor.downField("delta").downField("thinking").as[String].getOrElse("")
                  IO.pure(if thinking.nonEmpty then List(StreamChunk.ThinkingDelta(thinking)) else Nil)
                case Some("input_json_delta") =>
                  val idx = json.hcursor.downField("index").as[Int].getOrElse(0)
                  val partial = json.hcursor.downField("delta").downField("partial_json").as[String].getOrElse("")
                  toolCallState.modify { m =>
                    m.get(idx) match
                      case Some((id, name, sb)) =>
                        val updated = m.updated(idx, (id, name, sb.append(partial)))
                        val chunks = if partial.nonEmpty then List(StreamChunk.ToolArgDelta(name, partial)) else Nil
                        (updated, chunks)
                      case None => (m, Nil)
                  }
                case Some("signature_delta") =>
                  val sig = json.hcursor.downField("delta").downField("signature").as[String].getOrElse("")
                  IO.pure(if sig.nonEmpty then List(StreamChunk.ThinkingSignature(sig)) else Nil)
                case _ => IO.pure(Nil)
            case "content_block_start" =>
              json.hcursor.downField("content_block").downField("type").as[String].toOption match
                case Some("tool_use") =>
                  val id = json.hcursor.downField("content_block").downField("id").as[String].getOrElse("")
                  val name = json.hcursor.downField("content_block").downField("name").as[String].getOrElse("")
                  val idx = json.hcursor.downField("index").as[Int].getOrElse(0)
                  toolCallState
                    .update(_ + (idx -> (id, name, new StringBuilder)))
                    .as(List(StreamChunk.ToolCallStart(name)))
                case Some("thinking") =>
                  // Some providers (DeepSeek) send empty signature in content_block_start
                  // and the real signature later via signature_delta. Filter out empty to avoid
                  // collectFirst in aggregateChunks picking the useless empty string.
                  val sig = json.hcursor.downField("content_block").downField("signature").as[String].toOption
                  IO.pure(sig.filter(_.nonEmpty).map(s => List(StreamChunk.ThinkingSignature(s))).getOrElse(Nil))
                case _ => IO.pure(Nil)
            case "content_block_stop" =>
              val idx = json.hcursor.downField("index").as[Int].getOrElse(0)
              toolCallState.modify { m =>
                m.get(idx) match
                  case Some((id, name, sb)) =>
                    // Issue #18: an unparseable argument stream (e.g. GLM's
                    // unquoted ISO-8601 triggerAt) used to be coerced to {}
                    // here, silently. ToolInputJson repairs what it can and
                    // marks the rest so executeTool reports it to the LLM.
                    (m - idx, List(StreamChunk.ToolCallChunk(ToolCall(id, name, ToolInputJson.parseToolInput(name, sb.toString)))))
                  case None => (m, Nil)
              }
            case "message_delta" =>
              val stopReason = json.hcursor.downField("delta").downField("stop_reason").as[String].toOption
              val deltaUsage = json.hcursor.downField("usage")
              val outputTokens = deltaUsage.downField("output_tokens").as[Int].getOrElse(0)
              // Some Anthropic-compatible providers (Zhipu, DeepSeek) report input_tokens in
              // message_delta instead of (or in addition to) message_start.
              // Prefer message_delta's input_tokens when available and non-zero.
              val deltaInput = deltaUsage.downField("input_tokens").as[Int].toOption
              val deltaCacheRead = deltaUsage.downField("cache_read_input_tokens").as[Option[Int]].toOption.flatten
              val deltaCacheWrite = deltaUsage.downField("cache_creation_input_tokens").as[Option[Int]].toOption.flatten
              tokenRef.get.map { t =>
                val inputTokens = deltaInput.filter(_ > 0).getOrElse(t.input)
                val cacheRead = deltaCacheRead.orElse(t.cacheRead)
                val cacheWrite = deltaCacheWrite.orElse(t.cacheWrite)
                val totalInput = inputTokens + cacheRead.getOrElse(0) + cacheWrite.getOrElse(0)
                nebflow.core.NebflowLogger
                  .forName("nebflow.llm.anthropic")
                  .infoSync(
                    s"message_delta: model=${params.model} deltaInput=$deltaInput stored_input=${t.input} final_input=$inputTokens cacheRead=$cacheRead cacheWrite=$cacheWrite totalInput=$totalInput outputTokens=$outputTokens stopReason=$stopReason"
                  )
                val usage = Some(
                  TokenUsage(
                    inputTokens = totalInput,
                    outputTokens = outputTokens,
                    cacheReadTokens = cacheRead,
                    cacheWriteTokens = cacheWrite
                  )
                )
                val meta = LlmMeta(
                  sessionId = params.sessionId.getOrElse(""),
                  agentId = params.agentId.getOrElse(""),
                  providerId = "anthropic",
                  model = params.model,
                  durationMs = 0
                )
                List(StreamChunk.Done(stopReason, usage, Some(meta), None))
              }
            case _ => IO.pure(Nil)
end AnthropicAdapter
