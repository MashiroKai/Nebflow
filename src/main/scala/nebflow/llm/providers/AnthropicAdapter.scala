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
          }
          Json.obj("role" -> Json.fromString(role), "content" -> Json.fromValues(content))
      end match
    }

  private def toAnthropicTools(tools: List[ToolDefinition]): Json =
    Json.fromValues(tools.map { t =>
      Json.obj(
        "name" -> t.name.asJson,
        "description" -> t.description.asJson,
        "input_schema" -> Json.fromJsonObject(t.inputSchema)
      )
    })

  def sendMessage(params: SendMessageParams): IO[AdapterResponse] =
    val systemMsg = params.messages.find(_.role == MessageRole.System)
    val body = Json.obj(
      "model" -> params.model.asJson,
      "messages" -> Json.fromValues(toAnthropicMessages(params.messages)),
      "max_tokens" -> (params.maxTokens.getOrElse(16384)).asJson
    )
    val bodyWithSystem = systemMsg match
      case Some(m) => body.deepMerge(Json.obj("system" -> m.textContent.asJson))
      case None => body
    val bodyWithTools = params.tools.filter(_.nonEmpty) match
      case Some(tools) => bodyWithSystem.deepMerge(Json.obj("tools" -> toAnthropicTools(tools)))
      case None => bodyWithSystem
    val bodyWithThinking = params.thinking match
      case Some(t) => bodyWithTools.deepMerge(Json.obj("thinking" -> t))
      case None => bodyWithTools

    val request = basicRequest
      .post(uri"$base/v1/messages")
      .header("x-api-key", apiKey)
      .header("anthropic-version", "2023-06-01")
      .header("content-type", "application/json")
      .body(bodyWithThinking.noSpaces)

    backend.send(request).flatMap { response =>
      response.body match
        case Left(error) =>
          IO.raiseError(new RuntimeException(s"Anthropic API error: $error"))
        case Right(bodyStr) =>
          IO.pure(parseNonStreamingResponse(bodyStr))
    }

  end sendMessage

  private def parseNonStreamingResponse(bodyStr: String): AdapterResponse =
    parse(bodyStr) match
      case Left(err) => throw new RuntimeException(s"Failed to parse response: ${err.message}")
      case Right(json) =>
        val content = json.hcursor.downField("content").as[List[Json]].getOrElse(Nil)
        val textBlocks = content.filter(_.hcursor.downField("type").as[String].toOption.contains("text"))
        val toolUseBlocks = content.filter(_.hcursor.downField("type").as[String].toOption.contains("tool_use"))

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
    val systemMsg = params.messages.find(_.role == MessageRole.System)
    val body = Json.obj(
      "model" -> params.model.asJson,
      "messages" -> Json.fromValues(toAnthropicMessages(params.messages)),
      "max_tokens" -> (params.maxTokens.getOrElse(16384)).asJson,
      "stream" -> true.asJson
    )
    val bodyWithSystem = systemMsg match
      case Some(m) => body.deepMerge(Json.obj("system" -> m.textContent.asJson))
      case None => body
    val bodyWithTools = params.tools.filter(_.nonEmpty) match
      case Some(tools) => bodyWithSystem.deepMerge(Json.obj("tools" -> toAnthropicTools(tools)))
      case None => bodyWithSystem
    val bodyWithThinking = params.thinking match
      case Some(t) => bodyWithTools.deepMerge(Json.obj("thinking" -> t))
      case None => bodyWithTools

    Stream.eval(IO.ref(Map.empty[Int, (String, String, StringBuilder)])).flatMap { toolCallState =>
      val request = basicRequest
        .post(uri"$base/v1/messages")
        .header("x-api-key", apiKey)
        .header("anthropic-version", "2023-06-01")
        .header("content-type", "application/json")
        .body(bodyWithThinking.noSpaces)
        .response(asStreamUnsafe(Fs2Streams[IO]))
        .readTimeout(180.seconds)

      Stream.eval(backend.send(request)).flatMap { response =>
        response.body match
          case Left(error) =>
            Stream.eval(IO.raiseError(new RuntimeException(s"Anthropic API error: $error")))
          case Right(byteStream) =>
            parseSseIncrementally(byteStream, toolCallState)
      }
    }

  end sendMessageStream

  private def parseSseIncrementally(
    byteStream: Stream[IO, Byte],
    toolCallState: Ref[IO, Map[Int, (String, String, StringBuilder)]]
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
              case Some(et) => processAnthropicEvent(et, data, toolCallState)
              case None => processAnthropicEvent("", data, toolCallState)
            }
          else IO.pure(Nil)
        }
        .flatMap(cs => if cs.nonEmpty then Stream.emits(cs) else Stream.empty)
    }

  private def processAnthropicEvent(
    eventType: String,
    data: String,
    toolCallState: Ref[IO, Map[Int, (String, String, StringBuilder)]]
  ): IO[List[StreamChunk]] =
    if data == "[DONE]" then IO.pure(Nil)
    else
      parse(data) match
        case Left(_) => IO.pure(Nil)
        case Right(json) =>
          eventType match
            case "content_block_delta" =>
              json.hcursor.downField("delta").downField("type").as[String].toOption match
                case Some("text_delta") =>
                  val text = json.hcursor.downField("delta").downField("text").as[String].getOrElse("")
                  IO.pure(if text.nonEmpty then List(StreamChunk.TextDelta(text)) else Nil)
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
                  toolCallState.update(_ + (idx -> (id, name, new StringBuilder))).as(Nil)
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
              val usage = json.hcursor.downField("usage").as[Json].toOption.map { u =>
                TokenUsage(
                  inputTokens = u.hcursor.downField("input_tokens").as[Int].getOrElse(0),
                  outputTokens = u.hcursor.downField("output_tokens").as[Int].getOrElse(0)
                )
              }
              IO.pure(List(StreamChunk.Done(stopReason, usage)))
            case _ => IO.pure(Nil)
end AnthropicAdapter
