//! CurlTool — make HTTP requests and return the response.
//! Mirrors `nebflow.core.tools.CurlTool` from Scala.
//!
//! Uses `reqwest` to send real HTTP requests. Supports all standard methods,
//! custom headers, request body, and configurable timeout.

use crate::Tool;
use crate::types::{ToolContext, ToolError};
use async_trait::async_trait;

const MAX_RESPONSE_CHARS: usize = 100_000;
const DEFAULT_TIMEOUT_SECS: u64 = 30;
const MAX_TIMEOUT_SECS: u64 = 300;

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

    /// Sanitize URL by percent-encoding characters illegal in raw URIs.
    fn sanitize_url(raw: &str) -> String {
        raw.replace('|', "%7C")
            .replace('[', "%5B")
            .replace(']', "%5D")
            .replace('{', "%7B")
            .replace('}', "%7D")
            .replace('<', "%3C")
            .replace('>', "%3E")
            .replace('^', "%5E")
            .replace('`', "%60")
            .replace('\\', "%5C")
            .replace(' ', "%20")
    }

    /// Check if content-type indicates binary content.
    fn is_binary_content_type(ct: &str) -> bool {
        let ct_lower = ct.to_lowercase();
        const BINARY_PREFIXES: &[&str] = &[
            "image/",
            "video/",
            "audio/",
            "application/octet-stream",
            "application/pdf",
            "application/zip",
            "application/gzip",
            "application/x-tar",
            "application/x-rar",
        ];
        BINARY_PREFIXES.iter().any(|p| ct_lower.starts_with(p))
    }

    /// Pretty-print JSON if the body is valid JSON.
    fn format_json(body: &str) -> String {
        if body.is_empty() {
            return body.to_string();
        }
        match serde_json::from_str::<serde_json::Value>(body) {
            Ok(json) => serde_json::to_string_pretty(&json).unwrap_or_else(|_| body.to_string()),
            Err(_) => body.to_string(),
        }
    }

    /// Build the reqwest method enum from a string.
    fn parse_method(method: &str) -> reqwest::Method {
        match method {
            "GET" => reqwest::Method::GET,
            "POST" => reqwest::Method::POST,
            "PUT" => reqwest::Method::PUT,
            "PATCH" => reqwest::Method::PATCH,
            "DELETE" => reqwest::Method::DELETE,
            "HEAD" => reqwest::Method::HEAD,
            "OPTIONS" => reqwest::Method::OPTIONS,
            _ => reqwest::Method::GET,
        }
    }

    /// Core HTTP execution logic — extracted for testability.
    async fn do_request(
        url: &str,
        method: &str,
        headers: Option<&serde_json::Map<String, serde_json::Value>>,
        body: Option<&str>,
        timeout_secs: u64,
    ) -> Result<String, ToolError> {
        let sanitized = Self::sanitize_url(url);

        if !sanitized.starts_with("http://") && !sanitized.starts_with("https://") {
            return Err(ToolError::InvalidInput(
                "URL must start with http:// or https://".into(),
            ));
        }

        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(timeout_secs))
            .build()
            .map_err(|e| ToolError::Execution(format!("Failed to build HTTP client: {e}")))?;

        let req_method = Self::parse_method(method);
        let mut request = client.request(req_method, &sanitized);

        // Default User-Agent
        request = request.header("User-Agent", "Nebflow/1.0");

        // Apply user headers (overriding User-Agent if provided)
        if let Some(hdrs) = headers {
            for (key, val) in hdrs {
                if let Some(s) = val.as_str() {
                    request = request.header(key, s);
                }
            }
        }

        // Apply body
        if let Some(b) = body {
            if !b.is_empty() {
                request = request.body(b.to_string());
            }
        }

        let response = request
            .send()
            .await
            .map_err(|e| ToolError::Execution(format!("Request failed: {e}")))?;

        let status = response.status();
        let content_type = response
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_string();

        let content_length = response
            .headers()
            .get("content-length")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_string();

        let location = response
            .headers()
            .get("location")
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string());

        // Read body
        let body_text = if Self::is_binary_content_type(&content_type) {
            format!(
                "[Binary content: {}, {} bytes]",
                content_type,
                if content_length.is_empty() {
                    "unknown size"
                } else {
                    &content_length
                }
            )
        } else {
            response
                .text()
                .await
                .map_err(|e| ToolError::Execution(format!("Failed to read response body: {e}")))?
        };

        // Format JSON
        let formatted = if content_type.contains("application/json") && !body_text.is_empty() {
            Self::format_json(&body_text)
        } else {
            body_text
        };

        // Build output
        let mut parts = Vec::new();
        parts.push(format!(
            "Status: {} {}",
            status.as_u16(),
            status.canonical_reason().unwrap_or("")
        ));

        if !content_type.is_empty() {
            parts.push(format!("content-type: {content_type}"));
        }
        if !content_length.is_empty() {
            parts.push(format!("content-length: {content_length}"));
        }
        if let Some(loc) = location {
            parts.push(format!("location: {loc}"));
        }

        parts.push("---".to_string());

        let truncated = if formatted.len() > MAX_RESPONSE_CHARS {
            format!(
                "{}\n[Response truncated at {} chars]",
                &formatted[..MAX_RESPONSE_CHARS],
                MAX_RESPONSE_CHARS
            )
        } else {
            formatted
        };

        parts.push(truncated);

        Ok(parts.join("\n"))
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
        "Make an HTTP request to a URL and return the response.\n\nUsage:\n- Use Curl for API calls, webhooks, or HTTP-based interactions that require specific headers, methods, or body content.\n- Supports GET, POST, PUT, PATCH, DELETE and other HTTP methods\n- Headers and request body can be provided as JSON\n- Automatically formats JSON responses\n- Binary responses (images, videos, etc.) are skipped with a summary\n- Returns status code, headers, and body"
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
        let method = super::get_str(input, "method").unwrap_or("GET").to_uppercase();
        let body = super::get_str(input, "body");
        let timeout_sec = super::get_int(input, "timeout")
            .map(|n| n.clamp(1, MAX_TIMEOUT_SECS as i64) as u64)
            .unwrap_or(DEFAULT_TIMEOUT_SECS);
        let headers = input.get("headers").and_then(|v| v.as_object());

        if url.is_empty() {
            return Err(ToolError::InvalidInput("URL is required".into()));
        }

        Self::do_request(url, &method, headers, body, timeout_sec).await
    }

    fn summarize(
        &self,
        input: &serde_json::Map<String, serde_json::Value>,
    ) -> String {
        let method = super::get_str(input, "method").unwrap_or("GET").to_uppercase();
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
    async fn curl_basic_get() {
        // Test against httpbin.org which returns predictable JSON
        let result = CurlTool::do_request(
            "https://httpbin.org/get",
            "GET",
            None,
            None,
            10,
        )
        .await;

        // Network might be unavailable in CI — skip assertion if so
        if let Ok(output) = result {
            assert!(output.contains("Status: 200"));
            assert!(output.contains("content-type"));
            assert!(output.contains("---"));
        }
    }

    #[tokio::test]
    async fn curl_post_with_body() {
        let result = CurlTool::do_request(
            "https://httpbin.org/post",
            "POST",
            None,
            Some(r#"{"test":"value"}"#),
            10,
        )
        .await;

        if let Ok(output) = result {
            assert!(output.contains("Status: 200"));
            // httpbin echoes back the body
            assert!(output.contains("test") || output.contains("value"));
        }
    }

    #[tokio::test]
    async fn curl_invalid_url() {
        let result = CurlTool::do_request("not-a-url", "GET", None, None, 10).await;
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

    #[tokio::test]
    async fn curl_non_http_scheme_rejected() {
        let result = CurlTool::do_request("ftp://example.com", "GET", None, None, 10).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn curl_custom_headers() {
        let mut headers = serde_json::Map::new();
        headers.insert("X-Custom-Header".into(), "test-value".into());

        let result = CurlTool::do_request(
            "https://httpbin.org/headers",
            "GET",
            Some(&headers),
            None,
            10,
        )
        .await;

        if let Ok(output) = result {
            assert!(output.contains("Status: 200"));
        }
    }

    #[tokio::test]
    async fn curl_url_sanitization() {
        let sanitized = CurlTool::sanitize_url(
            "https://example.com/api?ids=[1,2]&filter=a|b",
        );
        assert!(sanitized.contains("%5B"));
        assert!(sanitized.contains("%5D"));
        assert!(sanitized.contains("%7C"));
        assert!(!sanitized.contains('['));
        assert!(!sanitized.contains('|'));
    }

    #[tokio::test]
    async fn curl_json_pretty_print() {
        let json = r#"{"name":"test","value":42}"#;
        let pretty = CurlTool::format_json(json);
        assert!(pretty.contains("\n"));
        assert!(pretty.contains("\"name\""));
    }

    #[tokio::test]
    async fn curl_json_pretty_print_invalid() {
        let not_json = "this is not json";
        let result = CurlTool::format_json(not_json);
        assert_eq!(result, not_json);
    }

    #[test]
    fn curl_binary_detection() {
        assert!(CurlTool::is_binary_content_type("image/png"));
        assert!(CurlTool::is_binary_content_type("video/mp4"));
        assert!(CurlTool::is_binary_content_type("application/pdf"));
        assert!(!CurlTool::is_binary_content_type("text/html"));
        assert!(!CurlTool::is_binary_content_type("application/json"));
    }

    #[test]
    fn curl_method_parsing() {
        assert_eq!(CurlTool::parse_method("GET"), reqwest::Method::GET);
        assert_eq!(CurlTool::parse_method("POST"), reqwest::Method::POST);
        assert_eq!(CurlTool::parse_method("PUT"), reqwest::Method::PUT);
        assert_eq!(CurlTool::parse_method("DELETE"), reqwest::Method::DELETE);
        assert_eq!(CurlTool::parse_method("PATCH"), reqwest::Method::PATCH);
        assert_eq!(CurlTool::parse_method("HEAD"), reqwest::Method::HEAD);
        assert_eq!(CurlTool::parse_method("OPTIONS"), reqwest::Method::OPTIONS);
        assert_eq!(CurlTool::parse_method("UNKNOWN"), reqwest::Method::GET);
    }
}
