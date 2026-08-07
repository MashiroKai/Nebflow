//! MCP client — communicates with an MCP server.
//!
//! Mirrors Scala `McpClient.scala`. Provides:
//! - `initialize`: handshake with the server
//! - `list_tools`: fetch the server's tool list
//! - `call_tool`: invoke a tool on the server

use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::Arc;
use std::time::Duration;

use tokio::time::timeout;

use crate::mcp::jsonrpc::{JsonRpcNotification, JsonRpcRequest, McpTool};
use crate::mcp::transport::McpTransport;
use crate::mcp::MCP_PROTOCOL_VERSION;
use crate::types::{ToolContext, ToolError};
use crate::Tool;

/// MCP client — communicates with a single MCP server.
pub struct McpClient {
    server_id: String,
    transport: Arc<dyn McpTransport>,
    counter: AtomicI32,
}

impl McpClient {
    pub fn new(server_id: String, transport: Arc<dyn McpTransport>) -> Self {
        Self {
            server_id,
            transport,
            counter: AtomicI32::new(0),
        }
    }

    fn next_id(&self) -> serde_json::Value {
        serde_json::json!(self.counter.fetch_add(1, Ordering::SeqCst) + 1)
    }

    /// Initialize the MCP session — handshake with the server.
    pub async fn initialize(&self) -> Result<(), String> {
        let request = JsonRpcRequest::new(
            self.next_id(),
            "initialize",
            Some(
                serde_json::json!({
                    "protocolVersion": MCP_PROTOCOL_VERSION,
                    "capabilities": {},
                    "clientInfo": {
                        "name": "nebflow",
                        "version": "1.0.0"
                    }
                })
                .as_object()
                .unwrap()
                .clone(),
            ),
        );

        let response = self.transport.send(request).await?;

        if let Some(err) = response.error {
            return Err(format!("MCP initialize failed: {}", err.message));
        }

        // Complete handshake: send initialized notification.
        let notif = JsonRpcNotification::new("notifications/initialized");
        self.transport.send_notification(notif).await?;

        Ok(())
    }

    /// List tools available on the server.
    pub async fn list_tools(&self) -> Result<Vec<McpTool>, String> {
        let request = JsonRpcRequest::new(self.next_id(), "tools/list", None);

        let response = timeout(Duration::from_secs(30), self.transport.send(request))
            .await
            .map_err(|_| "tools/list timed out".to_string())??;

        match response.result {
            Some(result) => {
                let tools = result
                    .get("tools")
                    .and_then(|t| t.as_array())
                    .cloned()
                    .unwrap_or_default();

                let mut mcp_tools = Vec::new();
                for tool_json in tools {
                    let name = tool_json.get("name").and_then(|n| n.as_str()).unwrap_or("");
                    if name.is_empty() {
                        tracing::warn!(
                            target: "mcp.client",
                            "Skipping malformed tool from server '{}': missing 'name' field",
                            self.server_id
                        );
                        continue;
                    }
                    let description = tool_json
                        .get("description")
                        .and_then(|d| d.as_str())
                        .map(|s| s.to_string());
                    let input_schema = tool_json
                        .get("inputSchema")
                        .and_then(|s| s.as_object())
                        .cloned()
                        .unwrap_or_default();
                    mcp_tools.push(McpTool {
                        name: name.to_string(),
                        description,
                        input_schema,
                    });
                }
                Ok(mcp_tools)
            }
            None => Ok(Vec::new()),
        }
    }

    /// Call a tool on the server.
    pub async fn call_tool(
        &self,
        name: &str,
        arguments: &serde_json::Map<String, serde_json::Value>,
    ) -> Result<String, String> {
        let mut params = serde_json::Map::new();
        params.insert("name".to_string(), serde_json::json!(name));
        params.insert(
            "arguments".to_string(),
            serde_json::Value::Object(arguments.clone()),
        );

        let request = JsonRpcRequest::new(self.next_id(), "tools/call", Some(params));

        let response = timeout(Duration::from_secs(120), self.transport.send(request))
            .await
            .map_err(|_| "tools/call timed out".to_string())??;

        match response.result {
            Some(result) => {
                let content = result
                    .get("content")
                    .and_then(|c| c.as_array())
                    .cloned()
                    .unwrap_or_default();

                let text_parts: Vec<String> = content
                    .iter()
                    .filter_map(|c| {
                        c.get("text")
                            .and_then(|t| t.as_str())
                            .map(|s| s.to_string())
                            .or_else(|| {
                                c.get("data")
                                    .and_then(|d| d.as_str())
                                    .map(|s| s.to_string())
                            })
                    })
                    .collect();

                Ok(text_parts.join("\n"))
            }
            None => {
                if let Some(err) = response.error {
                    Ok(format!("[MCP Error {}] {}", err.code, err.message))
                } else {
                    Ok(String::new())
                }
            }
        }
    }

    /// Close the connection.
    pub async fn close(&self) {
        self.transport.close().await;
    }
}

/// Create a Tool wrapper around an MCP tool.
///
/// The tool name is prefixed with `mcp__{serverId}__{toolName}` to avoid collisions.
pub fn create_mcp_tool_wrapper(
    server_id: &str,
    tool: &McpTool,
    client: Arc<McpClient>,
) -> Box<dyn Tool> {
    let tool_name = format!("mcp__{}__{}", server_id, tool.name);
    let description = tool
        .description
        .clone()
        .unwrap_or_else(|| format!("MCP tool: {}", tool.name));
    let input_schema = tool.input_schema.clone();

    Box::new(McpToolWrapper {
        name: tool_name,
        description,
        input_schema,
        client,
        original_name: tool.name.clone(),
    })
}

/// Internal Tool implementation wrapping an MCP tool.
struct McpToolWrapper {
    name: String,
    description: String,
    input_schema: serde_json::Map<String, serde_json::Value>,
    client: Arc<McpClient>,
    original_name: String,
}

#[async_trait::async_trait]
impl Tool for McpToolWrapper {
    fn name(&self) -> &str {
        &self.name
    }

    fn description(&self) -> &str {
        &self.description
    }

    fn input_schema(&self) -> &serde_json::Map<String, serde_json::Value> {
        &self.input_schema
    }

    async fn call(
        &self,
        input: &serde_json::Map<String, serde_json::Value>,
        _ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        self.client
            .call_tool(&self.original_name, input)
            .await
            .map_err(|e| ToolError::Execution(format!("Error: {}", e)))
    }

    fn summarize(&self, _input: &serde_json::Map<String, serde_json::Value>) -> String {
        format!("[MCP] {}", self.original_name)
    }

    fn summarize_result(
        &self,
        _input: &serde_json::Map<String, serde_json::Value>,
        result: &str,
    ) -> String {
        let first_line = result.lines().next().unwrap_or("");
        if first_line.len() > 80 {
            format!("{}...", &first_line[..80])
        } else {
            first_line.to_string()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mcp::jsonrpc::JsonRpcResponse;
    use crate::mcp::transport::MockTransport;

    #[tokio::test]
    async fn initialize_sends_request_and_notification() {
        let mock = Arc::new(MockTransport::new());
        mock.queue_response(JsonRpcResponse::success(
            serde_json::json!(1),
            serde_json::json!({"protocolVersion": "2025-06-18", "capabilities": {}}),
        ))
        .await;

        let client = McpClient::new("test-server".into(), mock.clone());
        client.initialize().await.unwrap();

        // Should have sent one notification.
        let notifications = mock.get_notifications().await;
        assert_eq!(notifications.len(), 1);
        assert_eq!(notifications[0].method, "notifications/initialized");
    }

    #[tokio::test]
    async fn initialize_fails_on_error() {
        let mock = Arc::new(MockTransport::new());
        mock.queue_response(JsonRpcResponse::error(
            serde_json::json!(1),
            -1,
            "Server unavailable".into(),
        ))
        .await;

        let client = McpClient::new("test-server".into(), mock);
        let result = client.initialize().await;
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("Server unavailable"));
    }

    #[tokio::test]
    async fn list_tools_parses_response() {
        let mock = Arc::new(MockTransport::new());
        mock.queue_response(JsonRpcResponse::success(
            serde_json::json!(1),
            serde_json::json!({
                "tools": [
                    {
                        "name": "search",
                        "description": "Search the web",
                        "inputSchema": {"type": "object"}
                    },
                    {
                        "name": "fetch",
                        "description": "Fetch a URL",
                        "inputSchema": {"type": "object"}
                    }
                ]
            }),
        ))
        .await;

        let client = McpClient::new("test-server".into(), mock);
        let tools = client.list_tools().await.unwrap();

        assert_eq!(tools.len(), 2);
        assert_eq!(tools[0].name, "search");
        assert_eq!(tools[0].description.as_deref(), Some("Search the web"));
        assert_eq!(tools[1].name, "fetch");
    }

    #[tokio::test]
    async fn list_tools_skips_malformed() {
        let mock = Arc::new(MockTransport::new());
        mock.queue_response(JsonRpcResponse::success(
            serde_json::json!(1),
            serde_json::json!({
                "tools": [
                    {"description": "missing name"},
                    {"name": "valid", "description": "valid tool"}
                ]
            }),
        ))
        .await;

        let client = McpClient::new("test-server".into(), mock);
        let tools = client.list_tools().await.unwrap();

        assert_eq!(tools.len(), 1);
        assert_eq!(tools[0].name, "valid");
    }

    #[tokio::test]
    async fn call_tool_extracts_text_content() {
        let mock = Arc::new(MockTransport::new());
        mock.queue_response(JsonRpcResponse::success(
            serde_json::json!(1),
            serde_json::json!({
                "content": [
                    {"type": "text", "text": "result line 1"},
                    {"type": "text", "text": "result line 2"}
                ]
            }),
        ))
        .await;

        let client = McpClient::new("test-server".into(), mock);
        let result = client
            .call_tool("search", &serde_json::Map::new())
            .await
            .unwrap();

        assert_eq!(result, "result line 1\nresult line 2");
    }

    #[tokio::test]
    async fn call_tool_error_response() {
        let mock = Arc::new(MockTransport::new());
        mock.queue_response(JsonRpcResponse::error(
            serde_json::json!(1),
            -32601,
            "Method not found".into(),
        ))
        .await;

        let client = McpClient::new("test-server".into(), mock);
        let result = client
            .call_tool("nonexistent", &serde_json::Map::new())
            .await
            .unwrap();

        assert!(result.contains("[MCP Error -32601]"));
        assert!(result.contains("Method not found"));
    }

    #[tokio::test]
    async fn mcp_tool_wrapper_call() {
        let mock = Arc::new(MockTransport::new());
        mock.queue_response(JsonRpcResponse::success(
            serde_json::json!(1),
            serde_json::json!({
                "content": [{"type": "text", "text": "42"}]
            }),
        ))
        .await;

        let client = Arc::new(McpClient::new("calc".into(), mock));
        let mcp_tool = McpTool {
            name: "add".into(),
            description: Some("Add numbers".into()),
            input_schema: serde_json::Map::new(),
        };

        let tool = create_mcp_tool_wrapper("calc", &mcp_tool, client);
        assert_eq!(tool.name(), "mcp__calc__add");

        let ctx = ToolContext {
            session_id: "test".into(),
            agent_id: "test".into(),
            working_directory: "/tmp".into(),
            depth: 0,
            ..Default::default()
        };
        let result = tool.call(&serde_json::Map::new(), &ctx).await;
        assert!(result.is_ok());
        assert_eq!(result.unwrap(), "42");
    }

    #[tokio::test]
    async fn mcp_tool_wrapper_summarize() {
        let mock = Arc::new(MockTransport::new());
        let client = Arc::new(McpClient::new("srv".into(), mock));
        let mcp_tool = McpTool {
            name: "search".into(),
            description: None,
            input_schema: serde_json::Map::new(),
        };

        let tool = create_mcp_tool_wrapper("srv", &mcp_tool, client);
        assert_eq!(tool.summarize(&serde_json::Map::new()), "[MCP] search");
        assert_eq!(
            tool.summarize_result(&serde_json::Map::new(), "line1\nline2"),
            "line1"
        );
    }
}
