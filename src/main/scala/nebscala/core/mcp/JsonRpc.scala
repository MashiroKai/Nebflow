package nebscala.core.mcp

import io.circe.{Json, JsonObject}
import io.circe.generic.auto.*

/** JSON-RPC 2.0 types */
case class JsonRpcRequest(
  jsonrpc: String = "2.0",
  id: Json,
  method: String,
  params: Option[JsonObject] = None
)

case class JsonRpcResponse(
  jsonrpc: String = "2.0",
  id: Json,
  result: Option[Json] = None,
  error: Option[JsonRpcError] = None
)

case class JsonRpcError(
  code: Int,
  message: String,
  data: Option[Json] = None
)

case class McpTool(
  name: String,
  description: Option[String] = None,
  inputSchema: JsonObject = JsonObject.empty
)
