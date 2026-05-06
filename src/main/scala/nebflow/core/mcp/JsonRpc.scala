package nebflow.core.mcp

import io.circe.generic.auto.*
import io.circe.{Json, JsonObject}

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

/** JSON-RPC 2.0 notification — no id, no response expected. */
case class JsonRpcNotification(
  jsonrpc: String = "2.0",
  method: String,
  params: Option[JsonObject] = None
)

case class McpTool(
  name: String,
  description: Option[String] = None,
  inputSchema: JsonObject = JsonObject.empty
)
