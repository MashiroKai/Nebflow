//! WebFetch tool — fetches URLs and converts content to text or markdown.
//!
//! Mirrors the Scala WebFetchTool. Uses reqwest for HTTP requests.
//! No browser fallback (Obscura/Playwright) in the Rust implementation.

use async_trait::async_trait;
use serde_json::{Map, Value};
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use crate::types::{ToolContext, ToolError};
use crate::Tool;

const FETCH_TIMEOUT_SECS: u64 = 30;
const DEFAULT_MAX_CHARS: usize = 20_000;
const DEFAULT_MAX_BYTES: usize = 750_000;
const CACHE_TTL: Duration = Duration::from_secs(300); // 5 minutes
const MAX_CACHE_SIZE: usize = 100;

/// Simple in-memory LRU cache for fetched pages.
static CACHE: Mutex<Option<HashMap<String, (String, Instant)>>> = Mutex::new(None);

fn get_cache() -> std::sync::MutexGuard<'static, Option<HashMap<String, (String, Instant)>>> {
    let mut guard = CACHE.lock().unwrap();
    if guard.is_none() {
        *guard = Some(HashMap::new());
    }
    guard
}

fn read_cache(url: &str) -> Option<String> {
    let mut guard = get_cache();
    let cache = guard.as_mut().unwrap();

    if let Some((value, ts)) = cache.get(url) {
        if ts.elapsed() < CACHE_TTL {
            return Some(value.clone());
        }
        cache.remove(url);
    }
    None
}

fn write_cache(url: &str, value: &str) {
    let mut guard = get_cache();
    let cache = guard.as_mut().unwrap();
    if cache.len() >= MAX_CACHE_SIZE {
        // Evict oldest entries (simple approach: clear when full)
        let oldest: Vec<String> = cache
            .iter()
            .min_by_key(|(_, (_, ts))| ts)
            .map(|(k, _)| k.clone())
            .into_iter()
            .collect();
        for k in oldest {
            cache.remove(&k);
        }
    }
    cache.insert(url.to_string(), (value.to_string(), Instant::now()));
}

/// Check if a content-type indicates binary content.
fn is_binary_content_type(content_type: &str) -> bool {
    let ct = content_type.to_lowercase();
    ct.contains("image/")
        || ct.contains("video/")
        || ct.contains("audio/")
        || ct.contains("application/octet-stream")
        || ct.contains("application/zip")
        || ct.contains("application/pdf")
        || ct.contains("font/")
}

/// Extract text from HTML — strips tags, scripts, styles, nav, etc.
fn extract_html_text(html: &str) -> (Option<String>, String, Option<String>) {
    // Extract meta description
    let meta_desc = {
        let re = regex::Regex::new(r#"<meta[^>]+name="description"[^>]+content="([^"]+)""#).ok();
        re.and_then(|r| r.captures(html).map(|c| c[1].trim().to_string()))
    };

    // Extract title
    let title = {
        let re = regex::Regex::new(r"<title[^>]*>([^<]*)</title>").ok();
        re.and_then(|r| r.captures(html).map(|c| c[1].trim().to_string()))
    };

    // Remove scripts, styles, nav, header, footer, aside, comments
    let mut clean = html.to_string();
    let patterns = [
        r"(?i)<script[^>]*>[\s\S]*?</script>",
        r"(?i)<style[^>]*>[\s\S]*?</style>",
        r"(?i)<nav[\s\S]*?</nav>",
        r"(?i)<header[\s\S]*?</header>",
        r"(?i)<footer[\s\S]*?</footer>",
        r"(?i)<aside[\s\S]*?</aside>",
        r"(?i)<!--[\s\S]*?-->",
    ];
    for pat in &patterns {
        if let Ok(re) = regex::Regex::new(pat) {
            clean = re.replace_all(&clean, "").to_string();
        }
    }

    // Try to extract main content
    let source = {
        let main_re = regex::Regex::new(r"(?i)<main[^>]*>([\s\S]*)</main>").ok();
        let article_re = regex::Regex::new(r"(?i)<article[^>]*>([\s\S]*)</article>").ok();
        let body_re = regex::Regex::new(r"(?i)<body[^>]*>([\s\S]*)</body>").ok();

        if let Some(re) = main_re {
            if let Some(cap) = re.captures(&clean) {
                cap[1].to_string()
            } else if let Some(re) = article_re {
                if let Some(cap) = re.captures(&clean) {
                    cap[1].to_string()
                } else if let Some(re) = body_re {
                    re.captures(&clean)
                        .map(|c| c[1].to_string())
                        .unwrap_or(clean)
                } else {
                    clean
                }
            } else {
                clean
            }
        } else {
            clean
        }
    };

    // Convert block-level closing tags to newlines, strip remaining tags
    let mut text = source;
    let newline_tags = [r"(?i)</(p|div|section|article|main|h[1-6]|li|tr|br|pre|blockquote)>"];
    for pat in &newline_tags {
        if let Ok(re) = regex::Regex::new(pat) {
            text = re.replace_all(&text, "\n").to_string();
        }
    }
    if let Ok(re) = regex::Regex::new(r"<[^>]+>") {
        text = re.replace_all(&text, "").to_string();
    }

    // Decode HTML entities
    text = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&nbsp;", " ")
        .replace("&#39;", "'");

    // Collapse multiple newlines
    if let Ok(re) = regex::Regex::new(r"\n{3,}") {
        text = re.replace_all(&text, "\n\n").to_string();
    }
    let text = text.trim().to_string();

    (title, text, meta_desc)
}

/// Build the final output string from title, text, meta description, and format.
fn build_output(title: Option<&str>, text: &str, meta_desc: Option<&str>, format: &str) -> String {
    let desc_block = meta_desc
        .filter(|d| !d.is_empty())
        .map(|d| format!("> {}\n\n", d))
        .unwrap_or_default();

    match (title, format) {
        (Some(t), "markdown") => format!("# {}\n\n{}{}", t, desc_block, text),
        (Some(t), _) => format!("{}\n\n{}{}", t, desc_block, text),
        (_, "markdown") => format!("{}{}", desc_block, text),
        _ => format!("{}{}", desc_block, text),
    }
}

/// Truncate text to max_chars.
fn truncate(text: &str, max_chars: usize) -> String {
    if text.len() <= max_chars {
        text.to_string()
    } else {
        format!("{}\n\n[Content truncated]", &text[..max_chars])
    }
}

pub struct WebFetchTool {
    schema: Map<String, Value>,
}

impl WebFetchTool {
    pub fn new() -> Self {
        let schema = serde_json::json!({
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "The URL to fetch"
                },
                "format": {
                    "type": "string",
                    "enum": ["markdown", "text"],
                    "description": "Output format. Defaults to \"markdown\"."
                },
                "maxChars": {
                    "type": "number",
                    "description": "Maximum characters to return (default 20000)"
                }
            },
            "required": ["url"]
        });
        Self {
            schema: schema.as_object().unwrap().clone(),
        }
    }
}

impl Default for WebFetchTool {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Tool for WebFetchTool {
    fn name(&self) -> &str {
        "WebFetch"
    }

    fn description(&self) -> &str {
        "Fetch and read a URL, converting the content to text or markdown.\n\n- Supports HTML pages, plain text, JSON, and other text-based content\n- Automatically skips binary content (images, videos, archives)\n- Returns the content in markdown (default) or plain text format"
    }

    fn input_schema(&self) -> &Map<String, Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &Map<String, Value>,
        _ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let url = input.get("url").and_then(|v| v.as_str()).unwrap_or("");
        let max_chars = input
            .get("maxChars")
            .and_then(|v| v.as_u64())
            .map(|n| std::cmp::max(100, n as usize))
            .unwrap_or(DEFAULT_MAX_CHARS);
        let format = input
            .get("format")
            .and_then(|v| v.as_str())
            .unwrap_or("markdown");

        if url.is_empty() {
            return Err(ToolError::InvalidInput("url is required".into()));
        }

        // Check cache
        if let Some(cached) = read_cache(url) {
            let truncated = truncate(&cached, max_chars);
            return Ok(format!("[Cached] {}", truncated));
        }

        // HTTP fetch
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(FETCH_TIMEOUT_SECS))
            .redirect(reqwest::redirect::Policy::limited(5))
            .user_agent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
            .map_err(|e| ToolError::Execution(format!("HTTP client error: {}", e)))?;

        let response = client.get(url).send().await.map_err(|e| {
            if e.is_timeout() {
                ToolError::Execution(format!("Request timed out after {}s", FETCH_TIMEOUT_SECS))
            } else if e.is_connect() {
                ToolError::Execution(format!("Connection failed: {}", e))
            } else {
                ToolError::Execution(format!("Fetch failed: {}", e))
            }
        })?;

        let status = response.status();
        if status.as_u16() == 403 {
            return Err(ToolError::Permission(
                "HTTP 403 Forbidden — anti-bot protection detected. \
                 No browser fallback available in Rust implementation."
                    .to_string(),
            ));
        }
        if !status.is_success() {
            return Err(ToolError::Execution(format!(
                "HTTP {} {}",
                status.as_u16(),
                status.canonical_reason().unwrap_or("Unknown")
            )));
        }

        let content_type = response
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_string();

        if is_binary_content_type(&content_type) {
            return Ok(format!(
                "[Binary content: {}] Skipped binary file.",
                content_type
            ));
        }

        let body = response
            .text()
            .await
            .map_err(|e| ToolError::Execution(format!("Failed to read response body: {}", e)))?;

        if body.len() > DEFAULT_MAX_BYTES {
            return Err(ToolError::Execution(format!(
                "Response too large ({} bytes > {})",
                body.len(),
                DEFAULT_MAX_BYTES
            )));
        }

        // Parse content based on type
        let (title, text, meta_desc) = if content_type.contains("application/json") {
            // Pretty-print JSON
            match serde_json::from_str::<Value>(&body) {
                Ok(json) => {
                    let pretty = serde_json::to_string_pretty(&json).unwrap_or(body.clone());
                    (None, pretty, None)
                }
                Err(_) => (None, body, None),
            }
        } else if content_type.contains("text/html") {
            extract_html_text(&body)
        } else {
            (None, body, None)
        };

        // Detect JS challenge pages
        if let Some(ref t) = title {
            let lower = t.to_lowercase();
            if lower.contains("just a moment")
                || lower.contains("请稍候")
                || lower.contains("attention required")
                || lower.contains("access denied")
            {
                return Err(ToolError::Permission(format!(
                    "Anti-bot challenge page detected: {}. \
                     No browser fallback available in Rust implementation.",
                    t
                )));
            }
        }

        let output = build_output(title.as_deref(), &text, meta_desc.as_deref(), format);
        let truncated = truncate(&output, max_chars);
        write_cache(url, &truncated);
        Ok(truncated)
    }

    fn summarize(&self, input: &Map<String, Value>) -> String {
        let url = input.get("url").and_then(|v| v.as_str()).unwrap_or("");
        format!("WebFetch(\"{}\")", url)
    }

    fn summarize_result(&self, _input: &Map<String, Value>, result: &str) -> String {
        if result.starts_with("Error") || result.starts_with("[Binary") {
            result.lines().next().unwrap_or(result).to_string()
        } else {
            format!("{} lines fetched", result.lines().count())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn is_binary_detection() {
        assert!(is_binary_content_type("image/png"));
        assert!(is_binary_content_type("application/pdf"));
        assert!(is_binary_content_type("video/mp4"));
        assert!(!is_binary_content_type("text/html"));
        assert!(!is_binary_content_type("application/json"));
    }

    #[test]
    fn truncate_text() {
        let short = "hello";
        assert_eq!(truncate(short, 10), "hello");

        let long = "x".repeat(100);
        let truncated = truncate(&long, 10);
        assert!(truncated.starts_with("xxxxxxxxxx"));
        assert!(truncated.contains("[Content truncated]"));
    }

    #[test]
    fn extract_html_basic() {
        let html =
            "<html><head><title>Test Page</title></head><body><p>Hello world</p></body></html>";
        let (title, text, _) = extract_html_text(html);
        assert_eq!(title, Some("Test Page".to_string()));
        assert!(text.contains("Hello world"));
    }

    #[test]
    fn extract_html_strips_scripts() {
        let html = "<html><body><script>alert('x')</script><p>Content</p></body></html>";
        let (_, text, _) = extract_html_text(html);
        assert!(!text.contains("alert"));
        assert!(text.contains("Content"));
    }

    #[test]
    fn build_output_formats() {
        let out = build_output(Some("Title"), "body text", Some("desc"), "markdown");
        assert!(out.starts_with("# Title"));
        assert!(out.contains("> desc"));
        assert!(out.contains("body text"));

        let out = build_output(None, "body text", None, "text");
        assert_eq!(out, "body text");
    }
}
