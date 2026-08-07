//! JSON-RPC 2.0 types — mirrors Scala `JsonRpc.scala`.

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

/// JSON-RPC 2.0 request.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JsonRpcRequest {
    #[serde(default = "default_version")]
    pub jsonrpc: String,
    pub id: Value,
    pub method: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub params: Option<Map<String, Value>>,
}

impl JsonRpcRequest {
    pub fn new(id: Value, method: &str, params: Option<Map<String, Value>>) -> Self {
        Self {
            jsonrpc: default_version(),
            id,
            method: method.to_string(),
            params,
        }
    }
}

/// JSON-RPC 2.0 response.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JsonRpcResponse {
    #[serde(default = "default_version")]
    pub jsonrpc: String,
    pub id: Value,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<JsonRpcError>,
}

impl JsonRpcResponse {
    pub fn success(id: Value, result: Value) -> Self {
        Self {
            jsonrpc: default_version(),
            id,
            result: Some(result),
            error: None,
        }
    }

    pub fn error(id: Value, code: i32, message: String) -> Self {
        Self {
            jsonrpc: default_version(),
            id,
            result: None,
            error: Some(JsonRpcError {
                code,
                message,
                data: None,
            }),
        }
    }
}

/// JSON-RPC 2.0 error object.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JsonRpcError {
    pub code: i32,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<Value>,
}

/// JSON-RPC 2.0 notification — no id, no response expected.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JsonRpcNotification {
    #[serde(default = "default_version")]
    pub jsonrpc: String,
    pub method: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub params: Option<Map<String, Value>>,
}

impl JsonRpcNotification {
    pub fn new(method: &str) -> Self {
        Self {
            jsonrpc: default_version(),
            method: method.to_string(),
            params: None,
        }
    }
}

/// MCP tool descriptor returned by `tools/list`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct McpTool {
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(default = "default_input_schema")]
    pub input_schema: Map<String, Value>,
}

fn default_version() -> String {
    "2.0".to_string()
}

fn default_input_schema() -> Map<String, Value> {
    Map::new()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn request_serialization() {
        let req = JsonRpcRequest::new(json!(1), "initialize", Some(Map::new()));
        let json_str = serde_json::to_string(&req).unwrap();
        assert!(json_str.contains(r#""jsonrpc":"2.0""#));
        assert!(json_str.contains(r#""method":"initialize""#));
        assert!(json_str.contains(r#""id":1"#));
    }

    #[test]
    fn response_success() {
        let resp = JsonRpcResponse::success(json!(1), json!({"tools": []}));
        let json_str = serde_json::to_string(&resp).unwrap();
        assert!(json_str.contains(r#""result""#));
        assert!(!json_str.contains(r#""error""#));
    }

    #[test]
    fn response_error() {
        let resp = JsonRpcResponse::error(json!(2), -32601, "Method not found".into());
        let json_str = serde_json::to_string(&resp).unwrap();
        assert!(json_str.contains(r#""code":-32601"#));
        assert!(json_str.contains(r#""Method not found""#));
    }

    #[test]
    fn notification_no_id() {
        let notif = JsonRpcNotification::new("notifications/initialized");
        let json_str = serde_json::to_string(&notif).unwrap();
        assert!(!json_str.contains(r#""id""#));
        assert!(json_str.contains(r#""notifications/initialized""#));
    }

    #[test]
    fn mcp_tool_serde() {
        let tool = McpTool {
            name: "search".into(),
            description: Some("Search the web".into()),
            input_schema: Map::new(),
        };
        let json_str = serde_json::to_string(&tool).unwrap();
        let back: McpTool = serde_json::from_str(&json_str).unwrap();
        assert_eq!(back.name, "search");
        assert_eq!(back.description.as_deref(), Some("Search the web"));
    }

    #[test]
    fn round_trip_request() {
        let req = JsonRpcRequest::new(
            json!(42),
            "tools/call",
            Some(serde_json::from_str(r#"{"name":"test"}"#).unwrap()),
        );
        let json_str = serde_json::to_string(&req).unwrap();
        let back: JsonRpcRequest = serde_json::from_str(&json_str).unwrap();
        assert_eq!(back.method, "tools/call");
        assert_eq!(back.id, json!(42));
    }
}
