//! CurlTool — make HTTP requests and return the response.
//! Mirrors `nebflow.core.tools.CurlTool` from Scala.

use crate::types::{ToolContext, ToolError};
use crate::Tool;
use async_trait::async_trait;

const MAX_RESPONSE_CHARS: usize = 100_000;

pub struct CurlTool {
    schema: serde_json::Map<String, serde_json::Value>,
}

impl CurlTool {
    pub fn new() -> Self {
        let mut schema = serde_json::Map::new();
        schema.insert("type".into(), "object".into());
        let mut props = serde_json::Map::new();
        props.insert(
            "url".into(),
            serde_json::json!({"type": "string", "description": "The URL to request"}),
        );
        props.insert(
            "method".into(),
            serde_json::json!({"type": "string", "enum": ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"], "description": "HTTP method. Defaults to \"GET\"."}),
        );
        props.insert(
            "headers".into(),
            serde_json::json!({"type": "object", "description": "Optional request headers as key-value pairs"}),
        );
        props.insert(
            "body".into(),
            serde_json::json!({"type": "string", "description": "Optional request body (raw string)"}),
        );
        props.insert(
            "timeout".into(),
            serde_json::json!({"type": "number", "description": "Timeout in seconds (default 30, max 300)"}),
        );
        schema.insert("properties".into(), serde_json::Value::Object(props));
        schema.insert("required".into(), serde_json::json!(["url"]));
        Self { schema }
    }
}

impl Default for CurlTool {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Tool for CurlTool {
    fn name(&self) -> &str {
        "Curl"
    }

    fn description(&self) -> &str {
        "Make an HTTP request to a URL and return the response.\n\nUsage:\n- Use Curl for API calls, webhooks, or HTTP-based interactions that require specific headers, methods, or body content.\n- Supports GET, POST, PUT, PATCH, DELETE and other HTTP methods\n- Headers and request body can be provided as JSON\n- Automatically formats JSON responses\n- Returns status code, headers, and body"
    }

    fn input_schema(&self) -> &serde_json::Map<String, serde_json::Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &serde_json::Map<String, serde_json::Value>,
        _ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let url = super::get_str(input, "url").unwrap_or("");
        let method = super::get_str(input, "method")
            .unwrap_or("GET")
            .to_uppercase();
        let body = super::get_str(input, "body").unwrap_or("");
        let timeout_sec = super::get_int(input, "timeout").unwrap_or(30).min(300);
        let headers = input.get("headers").and_then(|v| v.as_object());

        if url.is_empty() {
            return Err(ToolError::InvalidInput("URL is required".into()));
        }

        if !url.starts_with("http://") && !url.starts_with("https://") {
            return Err(ToolError::InvalidInput(format!(
                "URL must start with http:// or https://, got: {url}"
            )));
        }

        // Build request description
        let mut desc = format!("Curl: {method} {url}\nTimeout: {timeout_sec}s\n");
        if let Some(h) = headers {
            desc.push_str(&format!(
                "Headers: {}\n",
                serde_json::to_string(h).unwrap_or_default()
            ));
        }
        if !body.is_empty() {
            let truncated = if body.len() > 200 {
                format!("{}...", &body[..200])
            } else {
                body.to_string()
            };
            desc.push_str(&format!("Body: {truncated}\n"));
        }
        desc.push_str(&format!("\nMax response chars: {MAX_RESPONSE_CHARS}\n\nNote: Actual HTTP fetching requires network access and the reqwest dependency. This is a stub."));

        Ok(desc)
    }

    fn summarize(&self, input: &serde_json::Map<String, serde_json::Value>) -> String {
        let method = super::get_str(input, "method")
            .unwrap_or("GET")
            .to_uppercase();
        let url = super::get_str(input, "url").unwrap_or("");
        format!("Curl({method} \"{url}\")")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx() -> ToolContext {
        ToolContext {
            session_id: "test".into(),
            agent_id: "test".into(),
            working_directory: "/tmp".into(),
            depth: 0,
            ..Default::default()
        }
    }

    #[tokio::test]
    async fn curl_basic() {
        let tool = CurlTool::new();
        let mut input = serde_json::Map::new();
        input.insert("url".into(), "https://api.example.com/data".into());
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.contains("GET"));
        assert!(result.contains("https://api.example.com"));
    }

    #[tokio::test]
    async fn curl_post_with_body() {
        let tool = CurlTool::new();
        let mut input = serde_json::Map::new();
        input.insert("url".into(), "https://api.example.com/create".into());
        input.insert("method".into(), "POST".into());
        input.insert("body".into(), "{\"name\":\"test\"}".into());
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.contains("POST"));
        assert!(result.contains("Body"));
    }

    #[tokio::test]
    async fn curl_invalid_url() {
        let tool = CurlTool::new();
        let mut input = serde_json::Map::new();
        input.insert("url".into(), "not-a-url".into());
        let result = tool.call(&input, &ctx()).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn curl_empty_url() {
        let tool = CurlTool::new();
        let mut input = serde_json::Map::new();
        input.insert("url".into(), "".into());
        let result = tool.call(&input, &ctx()).await;
        assert!(result.is_err());
    }
}
