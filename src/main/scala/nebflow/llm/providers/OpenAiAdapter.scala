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

class OpenAiAdapter(baseUrl: String, apiKey: String, backend: StreamBackend[IO, Fs2Streams[IO]])
    extends ProviderAdapter[IO]:
  private val base = baseUrl.replaceAll("/+$", "")

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

  /** Extract reasoning_effort from the thinking JSON config. */
  private def effortFromThinking(thinking: Option[io.circe.Json]): Option[String] =
    thinking.flatMap { t =>
      t.hcursor.get[String]("type").toOption match
        case Some("enabled") =>
          Some(budgetToEffort(t.hcursor.get[Int]("budget_tokens").getOrElse(32000)))
        case _ => None
    }

  /** Build system message from stable + dynamic parts for OpenAI's messages format. */
  private def buildSystemMessage(params: SendMessageParams): Option[Json] =
    val stable = params.systemStable.filter(_.nonEmpty)
    val dynamic = params.systemDynamic.filter(_.nonEmpty)
    val fallback = params.messages.find(_.role == MessageRole.System).map(_.textContent).filter(_.nonEmpty)
    val text = stable.orElse(fallback) match
      case Some(s) => if dynamic.nonEmpty then s"$s\n\n${dynamic.get}" else s
      case None => dynamic.orNull
    if text != null && text.nonEmpty then Some(Json.obj("role" -> "system".asJson, "content" -> text.asJson))
    else None

  private def toOpenAiMessages(messages: List[Message]): List[Json] =
    messages.filterNot(_.role == MessageRole.System).map { msg =>
      val role = msg.role match
        case MessageRole.User => "user"
        case MessageRole.Assistant => "assistant"
        case _ => "user"

      msg.content match
        case Left(text) =>
          Json.obj("role" -> Json.fromString(role), "content" -> Json.fromString(text))
        case Right(blocks) =>
          val textParts = blocks.collect { case ContentBlock.Text(t) => t }
          val imageParts = blocks.collect { case ContentBlock.Image(data, mediaType) => (data, mediaType) }
          val toolUseParts = blocks.collect { case ContentBlock.ToolUse(id, name, input) => (id, name, input) }
          val toolResultParts = blocks.collect { case ContentBlock.ToolResult(toolUseId, content, _) =>
            (toolUseId, content)
          }

          if toolUseParts.nonEmpty then
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
          else if toolResultParts.nonEmpty then
            if toolResultParts.size == 1 then
              val (id, content) = toolResultParts.head
              Json.obj(
                "role" -> "tool".asJson,
                "content" -> content.asJson,
                "tool_call_id" -> id.asJson
              )
            else
              Json.obj(
                "role" -> "tool".asJson,
                "content" -> toolResultParts.map(_._2).mkString("\n").asJson,
                "tool_call_id" -> toolResultParts.head._1.asJson
              )
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
              Json.obj("role" -> Json.fromString(role), "content" -> Json.fromValues(contentParts.toList))
            else Json.obj("role" -> Json.fromString(role), "content" -> textParts.mkString("\n").asJson)
          end if
      end match
    }

  private def toOpenAiTools(tools: List[ToolDefinition]): Json =
    Json.fromValues(tools.map { t =>
      Json.obj(
        "type" -> "function".asJson,
        "function" -> Json.obj(
          "name" -> t.name.asJson,
          "description" -> t.description.asJson,
          "parameters" -> Json.fromJsonObject(t.inputSchema)
        )
      )
    })

  private def extractToolCalls(response: Json): List[ToolCall] =
    response.hcursor
      .downField("choices")
      .downN(0)
      .downField("message")
      .downField("tool_calls")
      .as[List[Json]]
      .getOrElse(Nil)
      .map { tc =>
        val id = tc.hcursor.downField("id").as[String].getOrElse("")
        val name = tc.hcursor.downField("function").downField("name").as[String].getOrElse("")
        val args = tc.hcursor.downField("function").downField("arguments").as[String].getOrElse("{}")
        val input = parse(args).flatMap(_.as[JsonObject]).getOrElse(JsonObject.empty)
        ToolCall(id, name, input)
      }

  def sendMessage(params: SendMessageParams): IO[AdapterResponse] =
    val systemMsg = buildSystemMessage(params)
    val baseMessages = toOpenAiMessages(params.messages)
    val allMessages = systemMsg.toList ++ baseMessages
    val body = Json.obj(
      "model" -> params.model.asJson,
      "messages" -> Json.fromValues(allMessages),
      "max_tokens" -> (params.maxTokens.getOrElse(Defaults.MaxTokensCompact)).asJson
    )
    val bodyWithTools = params.tools.filter(_.nonEmpty) match
      case Some(tools) => body.deepMerge(Json.obj("tools" -> toOpenAiTools(tools)))
      case None => body
    // OpenAI o-series models support reasoning_effort; silently ignore for others
    val effort = effortFromThinking(params.thinking)
    val bodyWithThinking = effort match
      case Some(e) => bodyWithTools.deepMerge(Json.obj("reasoning_effort" -> e.asJson))
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
      .post(uri"$base/chat/completions")
      .header("Authorization", s"Bearer $apiKey")
      .header("content-type", "application/json")
      .body(bodyWithMetadata.noSpaces)

    backend.send(request).flatMap { response =>
      response.body match
        case Left(error) =>
          IO.raiseError(new RuntimeException(s"OpenAI API error: $error"))
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
                  // Empty response with no tool calls — treat as error to trigger fallback
                  if reply.isEmpty && toolCalls.isEmpty then
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
                  else IO.pure(AdapterResponse(reply, toolCalls, usage))
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
    )
    val bodyWithTools = params.tools.filter(_.nonEmpty) match
      case Some(tools) => body.deepMerge(Json.obj("tools" -> toOpenAiTools(tools)))
      case None => body
    // OpenAI o-series models support reasoning_effort; silently ignore for others
    val effort = effortFromThinking(params.thinking)
    val bodyWithThinking = effort match
      case Some(e) => bodyWithTools.deepMerge(Json.obj("reasoning_effort" -> e.asJson))
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
      val request = basicRequest
        .post(uri"$base/chat/completions")
        .header("Authorization", s"Bearer $apiKey")
        .header("content-type", "application/json")
        .body(bodyWithMetadata.noSpaces)
        .response(asStreamUnsafe(Fs2Streams[IO]))
        .readTimeout(Defaults.LlmReadTimeoutSec.seconds)

      Stream.eval(backend.send(request)).flatMap { response =>
        response.body match
          case Left(error) =>
            Stream.eval(IO.raiseError(new RuntimeException(s"OpenAI API error: $error")))
          case Right(byteStream) =>
            parseOpenAiSseIncrementally(byteStream, toolCallState, params)
      }
    }

  end sendMessageStream

  private def parseOpenAiSseIncrementally(
    byteStream: Stream[IO, Byte],
    toolCallState: Ref[IO, Map[Int, (String, String, StringBuilder)]],
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

  private def processOpenAiData(
    data: String,
    toolCallState: Ref[IO, Map[Int, (String, String, StringBuilder)]],
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
      case Left(_) => IO.pure(Nil)
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
              val textChunks = d.hcursor.downField("content").as[String].toOption.filter(_.nonEmpty).toList
              val textDeltas = textChunks.map(StreamChunk.TextDelta.apply)
              // Some OpenAI-compatible providers (GLM, DeepSeek, etc.) return reasoning/thinking
              // content in separate fields during thinking mode. Emit them as ThinkingDelta so they
              // are recognized as content and prevent "Stream completed with no content" errors.
              val thinkText = d.hcursor
                .downField("reasoning_content")
                .as[String]
                .toOption
                .orElse(d.hcursor.downField("thinking").as[String].toOption)
                .filter(_.nonEmpty)
              val thinkingDeltas = thinkText.map(t => List(StreamChunk.ThinkingDelta(t))).getOrElse(Nil)
              val allTextDeltas = thinkingDeltas ++ textDeltas

              d.hcursor.downField("tool_calls").as[List[Json]].toOption match
                case Some(tcs) =>
                  tcs
                    .foldM(Nil: List[StreamChunk]) { (acc, tc) =>
                      val index = tc.hcursor.downField("index").as[Int].getOrElse(0)
                      val id = tc.hcursor.downField("id").as[String].toOption
                      val name = tc.hcursor.downField("function").downField("name").as[String].toOption
                      val args = tc.hcursor.downField("function").downField("arguments").as[String].toOption

                      (id, name) match
                        case (Some(toolId), Some(toolName)) =>
                          toolCallState
                            .update(_ + (index -> (toolId, toolName, new StringBuilder(args.getOrElse("")))))
                            .as(acc :+ StreamChunk.ToolCallStart(toolName))
                        case _ =>
                          toolCallState
                            .modify { m =>
                              m.get(index) match
                                case Some((tid, tname, sb)) =>
                                  args.foreach(sb.append)
                                  (m.updated(index, (tid, tname, sb)), ())
                                case None => (m, ())
                            }
                            .as(acc)
                    }
                    .flatMap { acc =>
                      if finishReason.contains("tool_calls") || finishReason.contains("function_call") then
                        toolCallState.getAndSet(Map.empty).map { m =>
                          acc ++ m.values.toList.map { case (id, name, sb) =>
                            val input = parse(sb.toString).flatMap(_.as[JsonObject]).getOrElse(JsonObject.empty)
                            StreamChunk.ToolCallChunk(ToolCall(id, name, input))
                          }
                        }
                      else if finishReason.isDefined then
                        IO.pure(acc :+ StreamChunk.Done(finishReason, usageOpt, Some(makeMeta), None))
                      else IO.pure(allTextDeltas ++ acc)
                    }
                case None =>
                  if finishReason.isDefined then
                    IO.pure(allTextDeltas :+ StreamChunk.Done(finishReason, usageOpt, Some(makeMeta), None))
                  else IO.pure(allTextDeltas)
              end match
          end match
        end if
    end match
  end processOpenAiData
end OpenAiAdapter
