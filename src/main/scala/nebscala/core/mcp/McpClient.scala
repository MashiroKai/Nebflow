package nebscala.core.mcp

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebscala.core.tools.{Tool, ToolContext, ToolRegistry}

class McpClient(transport: McpTransport):
  private val counter = new java.util.concurrent.atomic.AtomicInteger(0)

  private def nextId: Json = Json.fromInt(counter.incrementAndGet())

  def initialize(): IO[Unit] =
    val request = JsonRpcRequest(
      id = nextId,
      method = "initialize",
      params = Some(JsonObject.fromIterable(List(
        "protocolVersion" -> "2025-11-25".asJson,
        "capabilities" -> Json.obj(),
        "clientInfo" -> Json.obj("name" -> "nebscala".asJson, "version" -> "1.0.0".asJson)
      )))
    )
    transport.send(request).flatMap { response =>
      response.error match
        case Some(err) => IO.raiseError(new RuntimeException(s"MCP initialize failed: ${err.message}"))
        case None => IO.unit
    }

  def listTools(): IO[List[McpTool]] =
    val request = JsonRpcRequest(
      id = nextId,
      method = "tools/list",
      params = Some(JsonObject.empty)
    )
    transport.send(request).map { response =>
      response.result match
        case Some(result) =>
          result.hcursor.downField("tools").as[List[Json]].getOrElse(Nil).flatMap { t =>
            val name = t.hcursor.downField("name").as[String].getOrElse("")
            val desc = t.hcursor.downField("description").as[String].toOption
            val schema = t.hcursor.downField("inputSchema").as[JsonObject].getOrElse(JsonObject.empty)
            if name.nonEmpty then Some(McpTool(name, desc, schema)) else None
          }
        case None => Nil
    }

  def callTool(name: String, arguments: JsonObject): IO[String] =
    val request = JsonRpcRequest(
      id = nextId,
      method = "tools/call",
      params = Some(JsonObject.fromIterable(List(
        "name" -> name.asJson,
        "arguments" -> Json.fromJsonObject(arguments)
      )))
    )
    transport.send(request).map { response =>
      response.result match
        case Some(result) =>
          val content = result.hcursor.downField("content").as[List[Json]].getOrElse(Nil)
          content.flatMap { c =>
            c.hcursor.downField("text").as[String].toOption.orElse(
              c.hcursor.downField("data").as[String].toOption
            )
          }.mkString("\n")
        case None =>
          response.error match
            case Some(err) => s"Error: ${err.message}"
            case None => ""
    }

  def close(): IO[Unit] = transport.close()

def createMcpToolWrapper(serverId: String, tool: McpTool, client: McpClient): Tool =
  val toolName = s"mcp__${serverId}__${tool.name}"
  new Tool:
    def name: String = toolName
    def description: String = tool.description.getOrElse(s"MCP tool: ${tool.name}")
    def inputSchema: JsonObject = tool.inputSchema
    def call(input: JsonObject, ctx: ToolContext): IO[Either[nebscala.core.tools.ToolError, String]] =
      client.callTool(tool.name, input).map(Right(_)).handleError { e =>
        Right(s"Error: ${e.getMessage}")
      }
    def summarize(input: JsonObject): String = s"[MCP] ${tool.name}"
    def summarizeResult(input: JsonObject, result: String): String =
      val firstLine = result.split("\n").headOption.getOrElse("")
      if firstLine.length > 80 then firstLine.take(80) + "..." else firstLine
