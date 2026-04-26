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
import fs2.Stream

class OpenAiAdapter(baseUrl: String, apiKey: String) extends ProviderAdapter[IO]:
  private val backend = HttpClientSyncBackend()
  private val base = baseUrl.replaceAll("/+$", "")

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
          val toolResultParts = blocks.collect { case ContentBlock.ToolResult(toolUseId, content, _) => (toolUseId, content) }

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
            val first = toolResultParts.head
            Json.obj(
              "role" -> "tool".asJson,
              "content" -> first._2.asJson,
              "tool_call_id" -> first._1.asJson
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
            else
              Json.obj("role" -> Json.fromString(role), "content" -> textParts.mkString("\n").asJson)
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
    response.hcursor.downField("choices").downN(0).downField("message").downField("tool_calls").as[List[Json]].getOrElse(Nil).map { tc =>
      val id = tc.hcursor.downField("id").as[String].getOrElse("")
      val name = tc.hcursor.downField("function").downField("name").as[String].getOrElse("")
      val args = tc.hcursor.downField("function").downField("arguments").as[String].getOrElse("{}")
      val input = parse(args).flatMap(_.as[JsonObject]).getOrElse(JsonObject.empty)
      ToolCall(id, name, input)
    }

  def sendMessage(params: SendMessageParams): IO[AdapterResponse] = IO.blocking {
    val body = Json.obj(
      "model" -> params.model.asJson,
      "messages" -> Json.fromValues(toOpenAiMessages(params.messages)),
      "max_tokens" -> (params.maxTokens.getOrElse(4096)).asJson
    )
    val bodyWithTools = params.tools.filter(_.nonEmpty) match
      case Some(tools) => body.deepMerge(Json.obj("tools" -> toOpenAiTools(tools)))
      case None => body

    val request = basicRequest
      .post(uri"$base/chat/completions")
      .header("Authorization", s"Bearer $apiKey")
      .header("content-type", "application/json")
      .body(bodyWithTools.noSpaces)

    val response = request.send(backend)
    response.body match
      case Left(error) =>
        throw new RuntimeException(s"OpenAI API error: $error")
      case Right(bodyStr) =>
        parse(bodyStr) match
          case Left(err) => throw new RuntimeException(s"Failed to parse response: ${err.message}")
          case Right(json) =>
            val reply = json.hcursor.downField("choices").downN(0).downField("message").downField("content").as[String].getOrElse("")
            val toolCalls = extractToolCalls(json)
            val usage = json.hcursor.downField("usage").as[Json].toOption.map { u =>
              TokenUsage(
                inputTokens = u.hcursor.downField("prompt_tokens").as[Int].getOrElse(0),
                outputTokens = u.hcursor.downField("completion_tokens").as[Int].getOrElse(0)
              )
            }
            AdapterResponse(reply, toolCalls, usage)
  }

  def sendMessageStream(params: SendMessageParams): Stream[IO, StreamChunk] =
    val body = Json.obj(
      "model" -> params.model.asJson,
      "messages" -> Json.fromValues(toOpenAiMessages(params.messages)),
      "max_tokens" -> (params.maxTokens.getOrElse(4096)).asJson,
      "stream" -> true.asJson
    )
    val bodyWithTools = params.tools.filter(_.nonEmpty) match
      case Some(tools) => body.deepMerge(Json.obj("tools" -> toOpenAiTools(tools)))
      case None => body

    Stream.eval(IO.blocking {
      val request = basicRequest
        .post(uri"$base/chat/completions")
        .header("Authorization", s"Bearer $apiKey")
        .header("content-type", "application/json")
        .body(bodyWithTools.noSpaces)

      request.send(backend)
    }).flatMap { response =>
      response.body match
        case Left(error) =>
          Stream.eval(IO.raiseError(new RuntimeException(s"OpenAI API error: $error")))
        case Right(bodyStr) =>
          parseOpenAiSseStream(bodyStr)
    }

  private def parseOpenAiSseStream(text: String): Stream[IO, StreamChunk] =
    val lines = text.split("\n").toList
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamChunk]
    var toolCalls = Map.empty[Int, (String, String, StringBuilder)] // index -> (id, name, argsBuilder)
    var stopReason: Option[String] = None

    var i = 0
    while i < lines.length do
      val line = lines(i).trim
      if line.startsWith("data:") then
        val data = line.drop(5).trim
        if data != "[DONE]" then
          parse(data) match
            case Left(_) => // skip
            case Right(json) =>
              val delta = json.hcursor.downField("choices").downN(0).downField("delta").as[Json].toOption
              val finishReason = json.hcursor.downField("choices").downN(0).downField("finish_reason").as[String].toOption

              delta.foreach { d =>
                d.hcursor.downField("content").as[String].toOption.foreach { text =>
                  chunks += StreamChunk.TextDelta(text)
                }
                d.hcursor.downField("tool_calls").as[List[Json]].toOption.foreach { tcs =>
                  tcs.foreach { tc =>
                    val index = tc.hcursor.downField("index").as[Int].getOrElse(0)
                    val id = tc.hcursor.downField("id").as[String].toOption
                    val name = tc.hcursor.downField("function").downField("name").as[String].toOption
                    val args = tc.hcursor.downField("function").downField("arguments").as[String].toOption

                    (id, name) match
                      case (Some(toolId), Some(toolName)) =>
                        toolCalls = toolCalls + (index -> (toolId, toolName, new StringBuilder(args.getOrElse(""))))
                      case _ =>
                        toolCalls.get(index).foreach { case (_, _, sb) =>
                          args.foreach(sb.append)
                        }
                  }
                }
              }

              if finishReason.contains("tool_calls") || finishReason.contains("function_call") then
                toolCalls.foreach { case (_, (id, name, sb)) =>
                  val input = parse(sb.toString).flatMap(_.as[JsonObject]).getOrElse(JsonObject.empty)
                  chunks += StreamChunk.ToolCallChunk(ToolCall(id, name, input))
                }
                toolCalls = Map.empty

              if finishReason.isDefined then
                stopReason = finishReason
      i += 1

    if stopReason.isDefined || chunks.nonEmpty then
      chunks += StreamChunk.Done(stopReason, None)

    Stream.emits(chunks.toList)
