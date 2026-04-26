package nebscala.llm.providers

import nebscala.llm.{ProviderAdapter, SendMessageParams, AdapterResponse}
import nebscala.shared.{Message, MessageRole, ContentBlock, ToolCall, ToolDefinition, StreamChunk, TokenUsage}
import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import io.circe.parser.parse
import sttp.client4.*
import sttp.client4.httpclient.HttpClientSyncBackend
import fs2.{Stream, Chunk, Pull}
import java.io.InputStream
import scala.io.Source

class AnthropicAdapter(baseUrl: String, apiKey: String) extends ProviderAdapter[IO]:
  private val backend = HttpClientSyncBackend()
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
    }

  private def toAnthropicTools(tools: List[ToolDefinition]): Json =
    Json.fromValues(tools.map { t =>
      Json.obj(
        "name" -> t.name.asJson,
        "description" -> t.description.asJson,
        "input_schema" -> Json.fromJsonObject(t.inputSchema)
      )
    })

  def sendMessage(params: SendMessageParams): IO[AdapterResponse] = IO.blocking {
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

    val request = basicRequest
      .post(uri"$base/v1/messages")
      .header("x-api-key", apiKey)
      .header("anthropic-version", "2023-06-01")
      .header("content-type", "application/json")
      .body(bodyWithTools.noSpaces)

    val response = request.send(backend)
    response.body match
      case Left(error) =>
        throw new RuntimeException(s"Anthropic API error: $error")
      case Right(bodyStr) =>
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
  }

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

    Stream.eval(IO.blocking {
      val request = basicRequest
        .post(uri"$base/v1/messages")
        .header("x-api-key", apiKey)
        .header("anthropic-version", "2023-06-01")
        .header("content-type", "application/json")
        .body(bodyWithTools.noSpaces)

      request.send(backend)
    }).flatMap { response =>
      response.body match
        case Left(error) =>
          Stream.eval(IO.raiseError(new RuntimeException(s"Anthropic API error: $error")))
        case Right(bodyStr) =>
          parseSseStream(bodyStr)
    }

  private def parseSseStream(text: String): Stream[IO, StreamChunk] =
    val lines = text.split("\n").toList
    var toolCallInputs = Map.empty[Int, (String, String, StringBuilder)] // index -> (id, name, inputBuilder)
    var currentIndex = 0

    val chunks = scala.collection.mutable.ListBuffer.empty[StreamChunk]
    var stopReason: Option[String] = None
    var usage: Option[TokenUsage] = None

    var i = 0
    while i < lines.length do
      val line = lines(i).trim
      if line.startsWith("event:") then
        val eventType = line.drop(6).trim
        // Read next line for data
        i += 1
        if i < lines.length && lines(i).trim.startsWith("data:") then
          val data = lines(i).trim.drop(5).trim
          if data == "[DONE]" then
            () // Stream done
          else
            parse(data) match
              case Left(_) => // skip
              case Right(json) =>
                eventType match
                  case "content_block_start" =>
                    json.hcursor.downField("content_block").downField("type").as[String].toOption match
                      case Some("tool_use") =>
                        val id = json.hcursor.downField("content_block").downField("id").as[String].getOrElse("")
                        val name = json.hcursor.downField("content_block").downField("name").as[String].getOrElse("")
                        val idx = json.hcursor.downField("index").as[Int].getOrElse(0)
                        toolCallInputs = toolCallInputs + (idx -> (id, name, new StringBuilder))
                      case _ =>
                  case "content_block_delta" =>
                    val idx = json.hcursor.downField("index").as[Int].getOrElse(0)
                    json.hcursor.downField("delta").downField("type").as[String].toOption match
                      case Some("text_delta") =>
                        json.hcursor.downField("delta").downField("text").as[String].toOption.foreach { text =>
                          chunks += StreamChunk.TextDelta(text)
                        }
                      case Some("input_json_delta") =>
                        json.hcursor.downField("delta").downField("partial_json").as[String].toOption.foreach { partial =>
                          toolCallInputs.get(idx).foreach { case (_, _, sb) =>
                            sb.append(partial)
                          }
                        }
                      case _ =>
                  case "content_block_stop" =>
                    val idx = json.hcursor.downField("index").as[Int].getOrElse(0)
                    toolCallInputs.get(idx).foreach { case (id, name, sb) =>
                      val inputStr = sb.toString
                      val input = parse(inputStr).flatMap(_.as[JsonObject]).getOrElse(JsonObject.empty)
                      chunks += StreamChunk.ToolCallChunk(ToolCall(id, name, input))
                      toolCallInputs = toolCallInputs - idx
                    }
                  case "message_stop" =>
                    stopReason = json.hcursor.downField("message").downField("stop_reason").as[String].toOption
                    usage = json.hcursor.downField("message").downField("usage").as[Json].toOption.map { u =>
                      TokenUsage(
                        inputTokens = u.hcursor.downField("input_tokens").as[Int].getOrElse(0),
                        outputTokens = u.hcursor.downField("output_tokens").as[Int].getOrElse(0)
                      )
                    }
                  case _ =>
      i += 1

    // Handle incomplete tool calls
    toolCallInputs.foreach { case (idx, (id, name, sb)) =>
      val inputStr = sb.toString
      val input = parse(inputStr).flatMap(_.as[JsonObject]).getOrElse(JsonObject.empty)
      chunks += StreamChunk.ToolCallChunk(ToolCall(id, name, input))
    }

    if stopReason.isDefined || chunks.nonEmpty then
      chunks += StreamChunk.Done(stopReason, usage)

    Stream.emits(chunks.toList)
