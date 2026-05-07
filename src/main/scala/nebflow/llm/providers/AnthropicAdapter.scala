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

class AnthropicAdapter(baseUrl: String, apiKey: String, backend: StreamBackend[IO, Fs2Streams[IO]])
    extends ProviderAdapter[IO]:
  private val base = baseUrl.replaceAll("/+$", "")

  private def toAnthropicMessages(messages: List[Message]): List[Json] =
    messages.filterNot(_.role == MessageRole.System).map { msg =>
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
              val base = Json.obj(
                "type" -> "thinking".asJson,
                "thinking" -> thinking.asJson
              )
              signature.fold(base)(s => base.deepMerge(Json.obj("signature" -> s.asJson)))
          }
          Json.obj("role" -> Json.fromString(role), "content" -> Json.fromValues(content))
      end match
    }

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
  end buildSystemBlocks

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
    val bodyWithThinking = params.thinking match
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
      .post(uri"$base/v1/messages")
      .header("x-api-key", apiKey)
      .header("anthropic-version", "2023-06-01")
      .header("content-type", "application/json")
      .body(bodyWithMetadata.noSpaces)

    backend.send(request).flatMap { response =>
      response.body match
        case Left(error) =>
          IO.raiseError(new RuntimeException(s"Anthropic API error: $error"))
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
      val input = b.hcursor.downField("input").as[JsonObject].getOrElse(JsonObject.empty)
      ToolCall(id, name, input)
    }

    val usage = json.hcursor.downField("usage").as[Json].toOption.map { u =>
      TokenUsage(
        inputTokens = u.hcursor.downField("input_tokens").as[Int].getOrElse(0),
        outputTokens = u.hcursor.downField("output_tokens").as[Int].getOrElse(0),
        cacheReadTokens = u.hcursor.downField("cache_read_input_tokens").as[Int].toOption,
        cacheWriteTokens = u.hcursor.downField("cache_creation_input_tokens").as[Int].toOption
      )
    }

    AdapterResponse(reply, toolCalls, usage)

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
    val bodyWithThinking = params.thinking match
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
      Stream.eval(IO.ref(0)).flatMap { inputTokensRef =>
        val request = basicRequest
          .post(uri"$base/v1/messages")
          .header("x-api-key", apiKey)
          .header("anthropic-version", "2023-06-01")
          .header("content-type", "application/json")
          .body(bodyWithMetadata.noSpaces)
          .response(asStreamUnsafe(Fs2Streams[IO]))
          .readTimeout(Defaults.LlmReadTimeoutSec.seconds)

        Stream.eval(backend.send(request)).flatMap { response =>
          response.body match
            case Left(error) =>
              Stream.eval(IO.raiseError(new RuntimeException(s"Anthropic API error: $error")))
            case Right(byteStream) =>
              parseSseIncrementally(byteStream, toolCallState, inputTokensRef, params)
        }
      }
    }

  end sendMessageStream

  private def parseSseIncrementally(
    byteStream: Stream[IO, Byte],
    toolCallState: Ref[IO, Map[Int, (String, String, StringBuilder)]],
    inputTokensRef: Ref[IO, Int],
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
              case Some(et) => processAnthropicEvent(et, data, toolCallState, inputTokensRef, params)
              case None => processAnthropicEvent("", data, toolCallState, inputTokensRef, params)
            }
          else IO.pure(Nil)
        }
        .flatMap(cs => if cs.nonEmpty then Stream.emits(cs) else Stream.empty)
    }

  private def processAnthropicEvent(
    eventType: String,
    data: String,
    toolCallState: Ref[IO, Map[Int, (String, String, StringBuilder)]],
    inputTokensRef: Ref[IO, Int],
    params: SendMessageParams
  ): IO[List[StreamChunk]] =
    if data == "[DONE]" then IO.pure(Nil)
    else
      parse(data) match
        case Left(_) => IO.pure(Nil)
        case Right(json) =>
          eventType match
            case "message_start" =>
              // Capture input_tokens from message_start (message_delta only has output_tokens)
              val inputTokens = json.hcursor
                .downField("message")
                .downField("usage")
                .downField("input_tokens")
                .as[Int]
                .getOrElse(0)
              inputTokensRef.set(inputTokens).as(Nil)
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
                  toolCallState
                    .modify { m =>
                      val updated = m.get(idx) match
                        case Some((id, name, sb)) => m.updated(idx, (id, name, sb.append(partial)))
                        case None => m
                      (updated, ())
                    }
                    .as(Nil)
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
                case _ => IO.pure(Nil)
            case "content_block_stop" =>
              val idx = json.hcursor.downField("index").as[Int].getOrElse(0)
              toolCallState.modify { m =>
                m.get(idx) match
                  case Some((id, name, sb)) =>
                    val input = parse(sb.toString).flatMap(_.as[JsonObject]).getOrElse(JsonObject.empty)
                    (m - idx, List(StreamChunk.ToolCallChunk(ToolCall(id, name, input))))
                  case None => (m, Nil)
              }
            case "message_delta" =>
              val stopReason = json.hcursor.downField("delta").downField("stop_reason").as[String].toOption
              val outputTokens = json.hcursor.downField("usage").downField("output_tokens").as[Int].getOrElse(0)
              inputTokensRef.get.map { inputTokens =>
                val usage = Some(TokenUsage(inputTokens = inputTokens, outputTokens = outputTokens))
                val meta = LlmMeta(
                  sessionId = params.sessionId.getOrElse(""),
                  agentId = params.agentId.getOrElse(""),
                  providerId = "anthropic",
                  model = params.model,
                  durationMs = 0
                )
                List(StreamChunk.Done(stopReason, usage, Some(meta)))
              }
            case _ => IO.pure(Nil)
end AnthropicAdapter
