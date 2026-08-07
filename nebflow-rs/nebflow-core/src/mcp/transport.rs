//! MCP transports — stdio and HTTP.
//!
//! Mirrors Scala `transports.scala`.
//!
//! - `StdioTransport`: communicates with MCP server via subprocess stdin/stdout
//! - `HttpTransport`: Streamable HTTP (MCP 2025-06-18), one POST per JSON-RPC message

use std::collections::HashMap;
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::Arc;

use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, Command};
use tokio::sync::{oneshot, Mutex};
use tokio::time::Duration;

use crate::mcp::jsonrpc::{JsonRpcError, JsonRpcNotification, JsonRpcRequest, JsonRpcResponse};

/// MCP Transport interface.
#[async_trait::async_trait]
pub trait McpTransport: Send + Sync {
    async fn send(&self, request: JsonRpcRequest) -> Result<JsonRpcResponse, String>;
    async fn send_notification(&self, notification: JsonRpcNotification) -> Result<(), String>;
    async fn close(&self);
}

/// Stdio transport — communicates with MCP server via subprocess stdin/stdout.
pub struct StdioTransport {
    stdin: Arc<Mutex<tokio::process::ChildStdin>>,
    child: Arc<Mutex<Child>>,
    counter: AtomicI32,
    pending: Arc<Mutex<HashMap<String, oneshot::Sender<JsonRpcResponse>>>>,
    running: Arc<AtomicBool>,
}

impl StdioTransport {
    /// Create a new StdioTransport by spawning the MCP server process.
    pub async fn spawn(
        command: &str,
        args: &[String],
        env: &HashMap<String, String>,
    ) -> Result<Self, String> {
        let mut cmd = Command::new(command);
        cmd.args(args);
        for (k, v) in env {
            cmd.env(k, v);
        }
        cmd.stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());

        let mut child = cmd
            .spawn()
            .map_err(|e| format!("Failed to spawn MCP server: {}", e))?;

        let stdin = child.stdin.take().ok_or("Failed to capture stdin")?;
        let stdout = child.stdout.take().ok_or("Failed to capture stdout")?;
        let stderr = child.stderr.take();

        let pending: Arc<Mutex<HashMap<String, oneshot::Sender<JsonRpcResponse>>>> =
            Arc::new(Mutex::new(HashMap::new()));
        let running = Arc::new(AtomicBool::new(true));

        // Spawn stdout reader thread.
        {
            let pending = pending.clone();
            let running = running.clone();
            tokio::spawn(async move {
                let reader = BufReader::new(stdout);
                let mut lines = reader.lines();
                while running.load(Ordering::Relaxed) {
                    match lines.next_line().await {
                        Ok(Some(line)) => {
                            if let Ok(json) = serde_json::from_str::<serde_json::Value>(&line) {
                                if json.get("id").is_some() {
                                    // Response — dispatch to waiter.
                                    let id = json["id"].to_string();
                                    let response = parse_response(&json);
                                    let mut pending_map = pending.lock().await;
                                    if let Some(sender) = pending_map.remove(&id) {
                                        let _ = sender.send(response);
                                    }
                                }
                                // Notifications (no id) are not handled here —
                                // could be added with a notification handler map.
                            }
                        }
                        Ok(None) => break,
                        Err(_) => break,
                    }
                }
            });
        }

        // Spawn stderr reader thread (logs to tracing).
        if let Some(stderr) = stderr {
            tokio::spawn(async move {
                let reader = BufReader::new(stderr);
                let mut lines = reader.lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    tracing::info!(target: "mcp.stdio", "[stderr] {}", line);
                }
            });
        }

        Ok(Self {
            stdin: Arc::new(Mutex::new(stdin)),
            child: Arc::new(Mutex::new(child)),
            counter: AtomicI32::new(0),
            pending,
            running,
        })
    }
}

#[async_trait::async_trait]
impl McpTransport for StdioTransport {
    async fn send(&self, request: JsonRpcRequest) -> Result<JsonRpcResponse, String> {
        let id = self.counter.fetch_add(1, Ordering::SeqCst) + 1;
        let request = JsonRpcRequest {
            id: serde_json::json!(id),
            ..request
        };
        let id_str = serde_json::to_string(&request.id).unwrap_or_default();

        let (tx, rx) = oneshot::channel();
        {
            let mut pending = self.pending.lock().await;
            pending.insert(id_str.clone(), tx);
        }

        let json_str = serde_json::to_string(&request)
            .map_err(|e| format!("Failed to serialize request: {}", e))?;

        {
            let mut stdin = self.stdin.lock().await;
            stdin
                .write_all(json_str.as_bytes())
                .await
                .map_err(|e| format!("Failed to write to stdin: {}", e))?;
            stdin
                .write_all(b"\n")
                .await
                .map_err(|e| format!("Failed to write newline: {}", e))?;
            stdin
                .flush()
                .await
                .map_err(|e| format!("Failed to flush stdin: {}", e))?;
        }

        match tokio::time::timeout(Duration::from_secs(120), rx).await {
            Ok(Ok(response)) => Ok(response),
            Ok(Err(_)) => {
                self.pending.lock().await.remove(&id_str);
                Err("MCP request cancelled".to_string())
            }
            Err(_) => {
                self.pending.lock().await.remove(&id_str);
                Err("MCP request timed out".to_string())
            }
        }
    }

    async fn send_notification(&self, notification: JsonRpcNotification) -> Result<(), String> {
        let json_str = serde_json::to_string(&notification)
            .map_err(|e| format!("Failed to serialize notification: {}", e))?;
        let mut stdin = self.stdin.lock().await;
        stdin
            .write_all(json_str.as_bytes())
            .await
            .map_err(|e| format!("Failed to write notification: {}", e))?;
        stdin.write_all(b"\n").await.map_err(|e| format!("{}", e))?;
        stdin.flush().await.map_err(|e| format!("{}", e))?;
        Ok(())
    }

    async fn close(&self) {
        self.running.store(false, Ordering::Relaxed);
        let _ = self.child.lock().await.kill().await;
    }
}

/// Parse a JSON value into a JsonRpcResponse.
fn parse_response(json: &serde_json::Value) -> JsonRpcResponse {
    let id = json.get("id").cloned().unwrap_or(serde_json::Value::Null);
    let result = json.get("result").cloned();
    let error = json.get("error").map(|err| JsonRpcError {
        code: err.get("code").and_then(|c| c.as_i64()).unwrap_or(-1) as i32,
        message: err
            .get("message")
            .and_then(|m| m.as_str())
            .unwrap_or("Unknown error")
            .to_string(),
        data: err.get("data").cloned(),
    });

    JsonRpcResponse {
        jsonrpc: "2.0".to_string(),
        id,
        result,
        error,
    }
}

/// HTTP transport — Streamable HTTP (MCP 2025-06-18).
///
/// One POST per JSON-RPC message, `Accept: application/json, text/event-stream`.
/// The response is either a single JSON document or an SSE stream.
/// A session id returned by the server is captured and sent on subsequent requests.
pub struct HttpTransport {
    url: String,
    headers: HashMap<String, String>,
    counter: AtomicI32,
    session_id: Mutex<Option<String>>,
    client: reqwest::Client,
}

impl HttpTransport {
    pub fn new(url: String, headers: HashMap<String, String>) -> Self {
        let client = reqwest::Client::builder().build().unwrap_or_default();
        Self {
            url: url.trim_end_matches('/').to_string(),
            headers,
            counter: AtomicI32::new(0),
            session_id: Mutex::new(None),
            client,
        }
    }

    fn build_request(&self, body: &str) -> reqwest::RequestBuilder {
        let mut req = self
            .client
            .post(&self.url)
            .header("content-type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", crate::mcp::MCP_PROTOCOL_VERSION)
            .timeout(Duration::from_secs(30))
            .body(body.to_string());

        // Session id (if captured from a previous response).
        // We can't access the Mutex synchronously here, so this is handled in send().

        for (k, v) in &self.headers {
            req = req.header(k, v);
        }

        req
    }
}

#[async_trait::async_trait]
impl McpTransport for HttpTransport {
    async fn send(&self, request: JsonRpcRequest) -> Result<JsonRpcResponse, String> {
        let id = self.counter.fetch_add(1, Ordering::SeqCst) + 1;
        let request = JsonRpcRequest {
            id: serde_json::json!(id),
            ..request
        };

        let json_str = serde_json::to_string(&request)
            .map_err(|e| format!("Failed to serialize request: {}", e))?;

        let mut req = self.build_request(&json_str);

        // Add session id header if present.
        {
            let sid = self.session_id.lock().await;
            if let Some(ref sid) = *sid {
                req = req.header("Mcp-Session-Id", sid);
            }
        }

        let response = req
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        // Capture session id from response headers.
        if let Some(sid) = response.headers().get("Mcp-Session-Id") {
            if let Ok(sid_str) = sid.to_str() {
                *self.session_id.lock().await = Some(sid_str.to_string());
            }
        }

        let content_type = response
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("application/json")
            .to_string();

        let body = response
            .text()
            .await
            .map_err(|e| format!("Failed to read response body: {}", e))?;

        if content_type.contains("text/event-stream") {
            parse_sse_response(&body, &request.id)
        } else {
            let json: serde_json::Value = serde_json::from_str(&body)
                .map_err(|e| format!("Failed to parse response JSON: {}", e))?;
            Ok(parse_response(&json))
        }
    }

    async fn send_notification(&self, notification: JsonRpcNotification) -> Result<(), String> {
        let json_str = serde_json::to_string(&notification)
            .map_err(|e| format!("Failed to serialize notification: {}", e))?;

        let mut req = self.build_request(&json_str);
        {
            let sid = self.session_id.lock().await;
            if let Some(ref sid) = *sid {
                req = req.header("Mcp-Session-Id", sid);
            }
        }

        req.send()
            .await
            .map_err(|e| format!("HTTP notification failed: {}", e))?;

        Ok(())
    }

    async fn close(&self) {
        // Per Streamable HTTP spec, DELETE with session id terminates the session.
        let sid = self.session_id.lock().await.clone();
        if let Some(sid) = sid {
            let _ = self
                .client
                .delete(&self.url)
                .header("Mcp-Session-Id", sid)
                .send()
                .await;
        }
    }
}

/// Parse an SSE response body — find the event whose id matches the request id.
fn parse_sse_response(
    body: &str,
    request_id: &serde_json::Value,
) -> Result<JsonRpcResponse, String> {
    let events: Vec<serde_json::Value> = body
        .split("\n\n")
        .flat_map(|block| {
            block
                .lines()
                .filter_map(|line| line.strip_prefix("data:").map(str::trim))
                .filter_map(|line| serde_json::from_str::<serde_json::Value>(line).ok())
        })
        .collect();

    // Find the event whose id matches request_id.
    let matched = events
        .iter()
        .find(|j| j.get("id").map(|id| id == request_id).unwrap_or(false))
        .or_else(|| {
            events
                .iter()
                .find(|j| j.get("id").is_some() && !j["id"].is_null())
        });

    match matched {
        Some(json) => Ok(parse_response(json)),
        None => Err(format!(
            "No JSON-RPC response in SSE stream ({} events)",
            events.len()
        )),
    }
}

/// Mock transport for testing — returns pre-configured responses.
pub struct MockTransport {
    responses: Arc<Mutex<Vec<JsonRpcResponse>>>,
    notifications: Arc<Mutex<Vec<JsonRpcNotification>>>,
}

impl Default for MockTransport {
    fn default() -> Self {
        Self::new()
    }
}

impl MockTransport {
    pub fn new() -> Self {
        Self {
            responses: Arc::new(Mutex::new(Vec::new())),
            notifications: Arc::new(Mutex::new(Vec::new())),
        }
    }

    /// Queue a response to be returned by the next `send` call.
    pub async fn queue_response(&self, response: JsonRpcResponse) {
        self.responses.lock().await.push(response);
    }

    /// Get all notifications that were sent.
    pub async fn get_notifications(&self) -> Vec<JsonRpcNotification> {
        self.notifications.lock().await.clone()
    }
}

#[async_trait::async_trait]
impl McpTransport for MockTransport {
    async fn send(&self, _request: JsonRpcRequest) -> Result<JsonRpcResponse, String> {
        let mut responses = self.responses.lock().await;
        if responses.is_empty() {
            Err("No queued responses".to_string())
        } else {
            Ok(responses.remove(0))
        }
    }

    async fn send_notification(&self, notification: JsonRpcNotification) -> Result<(), String> {
        self.notifications.lock().await.push(notification);
        Ok(())
    }

    async fn close(&self) {}
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn mock_transport_returns_queued_response() {
        let mock = MockTransport::new();
        mock.queue_response(JsonRpcResponse::success(
            serde_json::json!(1),
            serde_json::json!({"tools": []}),
        ))
        .await;

        let request = JsonRpcRequest::new(serde_json::json!(0), "tools/list", None);
        let response = mock.send(request).await.unwrap();
        assert!(response.result.is_some());
    }

    #[tokio::test]
    async fn mock_transport_records_notifications() {
        let mock = MockTransport::new();
        let notif = JsonRpcNotification::new("notifications/initialized");
        mock.send_notification(notif).await.unwrap();

        let notifications = mock.get_notifications().await;
        assert_eq!(notifications.len(), 1);
        assert_eq!(notifications[0].method, "notifications/initialized");
    }

    #[tokio::test]
    async fn http_transport_creates_client() {
        let transport = HttpTransport::new("https://example.com/mcp/".to_string(), HashMap::new());
        // URL should be trimmed of trailing slash.
        assert_eq!(transport.url, "https://example.com/mcp");
    }

    #[test]
    fn parse_sse_response_finds_matching_id() {
        let body = "data:{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n\ndata:{\"jsonrpc\":\"2.0\",\"method\":\"progress\",\"params\":{}}\n\n";
        let result = parse_sse_response(body, &serde_json::json!(1));
        assert!(result.is_ok());
        let response = result.unwrap();
        assert!(response.result.is_some());
    }

    #[test]
    fn parse_sse_response_no_match() {
        let body = "data:{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{}}\n\n";
        let result = parse_sse_response(body, &serde_json::json!(1));
        // Falls back to first event with an id.
        assert!(result.is_ok());
    }

    #[test]
    fn parse_response_from_json() {
        let json = serde_json::json!({
            "jsonrpc": "2.0",
            "id": 42,
            "result": {"tools": [{"name": "search"}]}
        });
        let response = parse_response(&json);
        assert_eq!(response.id, serde_json::json!(42));
        assert!(response.result.is_some());
    }

    #[test]
    fn parse_response_error_from_json() {
        let json = serde_json::json!({
            "jsonrpc": "2.0",
            "id": 42,
            "error": {"code": -32601, "message": "Method not found"}
        });
        let response = parse_response(&json);
        assert!(response.error.is_some());
        assert_eq!(response.error.unwrap().code, -32601);
    }
}
