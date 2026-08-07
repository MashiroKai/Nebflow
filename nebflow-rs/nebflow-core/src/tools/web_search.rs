//! WebSearchTool — search the web using multiple search engines.
//! Mirrors `nebflow.core.tools.WebSearchTool` from Scala.
//!
//! Uses `reqwest` to send real HTTP requests. Supports 7 search engines
//! with HTML result parsing for general engines and structured API parsing
//! for academic engines (arXiv, Crossref).

use crate::Tool;
use crate::types::{ToolContext, ToolError};
use async_trait::async_trait;
use regex::Regex;
use std::sync::LazyLock;

const DEFAULT_MAX_CHARS: usize = 15_000;
const FETCH_TIMEOUT_SECS: u64 = 10;

#[derive(Debug, Clone)]
struct SearchEngine {
    name: &'static str,
    url: &'static str,
    region: &'static str,
}

const ENGINES: &[SearchEngine] = &[
    SearchEngine { name: "Sogou", url: "https://sogou.com/web?query={keyword}", region: "cn" },
    SearchEngine { name: "360", url: "https://www.so.com/s?q={keyword}", region: "cn" },
    SearchEngine { name: "DuckDuckGo", url: "https://duckduckgo.com/html/?q={keyword}", region: "global" },
    SearchEngine { name: "Baidu", url: "https://www.baidu.com/s?wd={keyword}", region: "cn" },
    SearchEngine { name: "WeChat", url: "https://wx.sogou.com/weixin?type=2&query={keyword}", region: "cn" },
    SearchEngine { name: "arXiv", url: "http://export.arxiv.org/api/query", region: "academic" },
    SearchEngine { name: "Crossref", url: "https://api.crossref.org/works", region: "academic" },
];

const ACADEMIC_NAMES: &[&str] = &["arXiv", "Crossref"];

static LINK_REGEX: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>"#).unwrap());
static TAG_STRIP_REGEX: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"<[^>]+>").unwrap());
static DDG_BLOCK_REGEX: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)<div class="result[^"]*"[^>]*>(.*?)(?=<div class="result|\z)"#).unwrap()
});
static DDG_LINK_REGEX: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>"#).unwrap()
});
static DDG_SNIPPET_REGEX: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)<a[^>]*class="result__snippet"[^>]*>(.*?)</a>"#).unwrap()
});
static BAIDU_BLOCK_REGEX: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)<div[^>]*class="[^"]*result[^"]*c-container[^"]*"[^>]*>(.*?)(?=</div>\s*</div>|$)"#)
        .unwrap()
});
static ARXIV_ENTRY_REGEX: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?s)<entry>(.*?)</entry>").unwrap());
static ARXIV_TITLE_REGEX: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?s)<title[^>]*>(.*?)</title>").unwrap());
static ARXIV_SUMMARY_REGEX: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?s)<summary>(.*?)</summary>").unwrap());
static ARXIV_LINK_REGEX: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"<link[^>]*href="([^"]+)"[^>]*rel="alternate""#).unwrap()
});
static ARXIV_PUBLISHED_REGEX: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"<published>([^<]+)</published>").unwrap());
static ARXIV_NAME_REGEX: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"<name>([^<]+)</name>").unwrap());

/// A parsed search result item.
#[derive(Debug, Clone)]
struct SearchResult {
    title: String,
    url: String,
    snippet: String,
}

pub struct WebSearchTool {
    schema: serde_json::Map<String, serde_json::Value>,
}

impl WebSearchTool {
    pub fn new() -> Self {
        let mut schema = serde_json::Map::new();
        schema.insert("type".into(), "object".into());
        let mut props = serde_json::Map::new();
        props.insert(
            "query".into(),
            serde_json::json!({"type": "string", "minLength": 2, "description": "The search query"}),
        );
        let engine_names: Vec<serde_json::Value> =
            ENGINES.iter().map(|e| serde_json::json!(e.name)).collect();
        props.insert(
            "engine".into(),
            serde_json::json!({"type": "string", "enum": engine_names, "description": "Search engine to use. Defaults to auto-racing all general engines."}),
        );
        props.insert(
            "max_results".into(),
            serde_json::json!({"type": "number", "minimum": 100, "description": "Max result characters to return (default 15000)"}),
        );
        props.insert(
            "allowed_domains".into(),
            serde_json::json!({"type": "array", "items": {"type": "string"}, "description": "Only include results from these domains"}),
        );
        props.insert(
            "blocked_domains".into(),
            serde_json::json!({"type": "array", "items": {"type": "string"}, "description": "Exclude results from these domains"}),
        );
        schema.insert("properties".into(), serde_json::Value::Object(props));
        schema.insert("required".into(), serde_json::json!(["query"]));
        Self { schema }
    }

    /// Build an HTTP client with browser-like headers.
    fn make_client() -> reqwest::Client {
        reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(FETCH_TIMEOUT_SECS))
            .default_headers({
                let mut headers = reqwest::header::HeaderMap::new();
                headers.insert(
                    "Accept-Language",
                    "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7"
                        .parse()
                        .unwrap(),
                );
                headers.insert(
                    "User-Agent",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) \
                     AppleWebKit/537.36 (KHTML, like Gecko) \
                     Chrome/120.0.0.0 Safari/537.36"
                        .parse()
                        .unwrap(),
                );
                headers
            })
            .build()
            .expect("failed to build reqwest client")
    }

    /// Strip HTML tags and collapse whitespace.
    fn strip_tags(html: &str) -> String {
        let stripped = TAG_STRIP_REGEX.replace_all(html, "");
        stripped.split_whitespace().collect::<Vec<_>>().join(" ")
    }

    // ── HTML result extraction ─────────────────────────────────

    /// Extract search results from DuckDuckGo HTML.
    fn extract_duckduckgo(html: &str) -> Vec<SearchResult> {
        let mut results = Vec::new();
        for block in DDG_BLOCK_REGEX.captures_iter(html).take(8) {
            let block_html = block.get(1).map(|m| m.as_str()).unwrap_or("");
            if let Some(link) = DDG_LINK_REGEX.captures(block_html) {
                let url = link.get(1).map(|m| m.as_str()).unwrap_or("").to_string();
                let title = Self::strip_tags(link.get(2).map(|m| m.as_str()).unwrap_or(""));
                let snippet = DDG_SNIPPET_REGEX
                    .captures(block_html)
                    .and_then(|s| s.get(1))
                    .map(|m| Self::strip_tags(m.as_str()))
                    .unwrap_or_default();
                if !title.is_empty() && url.starts_with("http") {
                    results.push(SearchResult { title, url, snippet });
                }
            }
        }
        results
    }

    /// Extract search results from Baidu HTML.
    fn extract_baidu(html: &str) -> Vec<SearchResult> {
        let mut results = Vec::new();
        for block in BAIDU_BLOCK_REGEX.captures_iter(html).take(5) {
            let block_html = block.get(1).map(|m| m.as_str()).unwrap_or("");
            if let Some(link) = LINK_REGEX.captures(block_html) {
                let url = link.get(1).map(|m| m.as_str()).unwrap_or("").to_string();
                let title = Self::strip_tags(link.get(2).map(|m| m.as_str()).unwrap_or(""));
                if !title.is_empty() && url.starts_with("http") {
                    results.push(SearchResult {
                        title,
                        url,
                        snippet: String::new(),
                    });
                }
            }
        }
        results
    }

    /// Generic fallback: extract meaningful <a> links.
    fn extract_generic(html: &str) -> Vec<SearchResult> {
        const SKIP_DOMAINS: &[&str] = &[
            "bing.com",
            "baidu.com",
            "google.com",
            "duckduckgo.com",
            "sogou.com",
            "yahoo.com",
        ];

        let mut results = Vec::new();
        for cap in LINK_REGEX.captures_iter(html) {
            if results.len() >= 8 {
                break;
            }
            let url = cap.get(1).map(|m| m.as_str()).unwrap_or("").to_string();
            let text = Self::strip_tags(cap.get(2).map(|m| m.as_str()).unwrap_or(""));
            if text.len() > 12
                && url.starts_with("http")
                && !SKIP_DOMAINS.iter().any(|d| url.contains(d))
            {
                results.push(SearchResult {
                    title: text,
                    url,
                    snippet: String::new(),
                });
            }
        }
        results
    }

    /// Dispatch to the right HTML parser based on engine name.
    fn extract_html_results(html: &str, engine_name: &str) -> Vec<SearchResult> {
        if engine_name.contains("DuckDuckGo") {
            let r = Self::extract_duckduckgo(html);
            if !r.is_empty() {
                return r;
            }
        } else if engine_name.contains("Baidu") {
            let r = Self::extract_baidu(html);
            if !r.is_empty() {
                return r;
            }
        }
        Self::extract_generic(html)
    }

    // ── Academic API parsing ───────────────────────────────────

    /// Parse arXiv Atom XML feed.
    fn extract_arxiv(xml: &str) -> Vec<SearchResult> {
        let mut results = Vec::new();
        for entry in ARXIV_ENTRY_REGEX.captures_iter(xml).take(8) {
            let e = entry.get(1).map(|m| m.as_str()).unwrap_or("");
            let title = ARXIV_TITLE_REGEX
                .captures(e)
                .and_then(|c| c.get(1))
                .map(|m| Self::strip_tags(m.as_str()))
                .unwrap_or_default();
            let summary = ARXIV_SUMMARY_REGEX
                .captures(e)
                .and_then(|c| c.get(1))
                .map(|m| Self::strip_tags(m.as_str()))
                .unwrap_or_default();
            let link = ARXIV_LINK_REGEX
                .captures(e)
                .and_then(|c| c.get(1))
                .map(|m| m.as_str().to_string())
                .unwrap_or_default();
            let year = ARXIV_PUBLISHED_REGEX
                .captures(e)
                .and_then(|c| c.get(1))
                .map(|m| m.as_str().get(..4).unwrap_or("").to_string())
                .unwrap_or_default();
            let authors: Vec<String> = ARXIV_NAME_REGEX
                .captures_iter(e)
                .map(|c| c.get(1).map(|m| m.as_str().to_string()).unwrap_or_default())
                .take(5)
                .collect();

            if !title.is_empty() {
                let snippet = if !summary.is_empty() {
                    let truncated = if summary.len() > 400 {
                        format!("{}...", &summary[..400])
                    } else {
                        summary
                    };
                    format!(
                        "Authors: {} ({})\n{}",
                        authors.join(", "),
                        year,
                        truncated
                    )
                } else {
                    format!("Authors: {} ({})", authors.join(", "), year)
                };
                results.push(SearchResult {
                    title,
                    url: link,
                    snippet,
                });
            }
        }
        results
    }

    /// Parse Crossref JSON response.
    fn extract_crossref(json_str: &str) -> Vec<SearchResult> {
        let Ok(json) = serde_json::from_str::<serde_json::Value>(json_str) else {
            return Vec::new();
        };
        let items = json
            .get("message")
            .and_then(|m| m.get("items"))
            .and_then(|i| i.as_array());

        let Some(items) = items else {
            return Vec::new();
        };

        items
            .iter()
            .take(8)
            .filter_map(|item| {
                let title = item
                    .get("title")
                    .and_then(|t| t.as_array())
                    .and_then(|arr| arr.first())
                    .and_then(|t| t.as_str())
                    .unwrap_or("");
                if title.is_empty() {
                    return None;
                }
                let doi = item.get("DOI").and_then(|d| d.as_str()).unwrap_or("");
                let year = item
                    .get("published-print")
                    .and_then(|p| p.get("date-parts"))
                    .and_then(|dp| dp.as_array())
                    .and_then(|dp| dp.first())
                    .and_then(|dp| dp.as_array())
                    .and_then(|dp| dp.first())
                    .and_then(|dp| dp.as_i64())
                    .map(|y| y.to_string())
                    .unwrap_or_default();
                let journal = item
                    .get("container-title")
                    .and_then(|ct| ct.as_array())
                    .and_then(|arr| arr.first())
                    .and_then(|ct| ct.as_str())
                    .unwrap_or("");
                let authors: Vec<String> = item
                    .get("author")
                    .and_then(|a| a.as_array())
                    .map(|authors| {
                        authors
                            .iter()
                            .filter_map(|a| a.get("family").and_then(|f| f.as_str()).map(|s| s.to_string()))
                            .take(5)
                            .collect()
                    })
                    .unwrap_or_default();

                let url = if doi.is_empty() {
                    String::new()
                } else {
                    format!("https://doi.org/{doi}")
                };
                let snippet = format!(
                    "Authors: {} ({})\nJournal: {}",
                    authors.join(", "),
                    year,
                    journal
                );
                Some(SearchResult {
                    title: title.to_string(),
                    url,
                    snippet,
                })
            })
            .collect()
    }

    // ── Single-engine fetch ────────────────────────────────────

    /// Search a single general engine. Returns parsed results or error.
    async fn search_one(
        client: &reqwest::Client,
        query: &str,
        engine: &SearchEngine,
    ) -> Result<Vec<SearchResult>, String> {
        let url = engine.url.replace("{keyword}", &urlencoding::encode(query));
        let resp = client
            .get(&url)
            .send()
            .await
            .map_err(|e| format!("{}: {}", engine.name, &e.to_string()[..120.min(e.to_string().len())]))?;

        if !resp.status().is_success() {
            return Err(format!("{}: HTTP {}", engine.name, resp.status()));
        }

        let html = resp
            .text()
            .await
            .map_err(|e| format!("{}: {}", engine.name, e))?;

        let results = Self::extract_html_results(&html, engine.name);
        if results.is_empty() {
            return Err(format!("{}: No meaningful results", engine.name));
        }
        Ok(results)
    }

    /// Search an academic API.
    async fn search_academic(
        client: &reqwest::Client,
        query: &str,
        engine: &SearchEngine,
    ) -> Result<Vec<SearchResult>, String> {
        match engine.name {
            "arXiv" => {
                let terms: Vec<&str> = query.split_whitespace().take(5).collect();
                let arxiv_q = terms
                    .iter()
                    .map(|w| format!("all:{}", w))
                    .collect::<Vec<_>>()
                    .join("+AND+");
                let url = format!(
                    "{}?search_query={}&start=0&max_results=8&sortBy=relevance",
                    engine.url,
                    arxiv_q
                );
                let resp = client.get(&url).send().await.map_err(|e| {
                    format!("arXiv: {}", &e.to_string()[..120.min(e.to_string().len())])
                })?;
                if !resp.status().is_success() {
                    return Err(format!("arXiv: HTTP {}", resp.status()));
                }
                let xml = resp.text().await.map_err(|e| format!("arXiv: {e}"))?;
                let results = Self::extract_arxiv(&xml);
                if results.is_empty() {
                    return Err("arXiv: No results".into());
                }
                Ok(results)
            }
            "Crossref" => {
                let encoded = urlencoding::encode(query);
                let url = format!(
                    "{}/?query={}&rows=8&select=DOI,title,author,published-print,container-title",
                    engine.url, encoded
                );
                let resp = client
                    .get(&url)
                    .header("User-Agent", "Nebflow/academic-search (mailto:research@nebflow.space)")
                    .send()
                    .await
                    .map_err(|e| {
                        format!("Crossref: {}", &e.to_string()[..120.min(e.to_string().len())])
                    })?;
                if !resp.status().is_success() {
                    return Err(format!("Crossref: HTTP {}", resp.status()));
                }
                let json = resp.text().await.map_err(|e| format!("Crossref: {e}"))?;
                let results = Self::extract_crossref(&json);
                if results.is_empty() {
                    return Err("Crossref: No results".into());
                }
                Ok(results)
            }
            _ => Err(format!("Unknown academic engine: {}", engine.name)),
        }
    }

    // ── Formatting and filtering ───────────────────────────────

    fn format_results(engine_name: &str, results: &[SearchResult], max_chars: usize) -> String {
        let mut text = format!("Search engine: {engine_name}\n\n");
        for r in results {
            let entry = if r.snippet.is_empty() {
                format!("**{}**\n{}\n\n", r.title, r.url)
            } else {
                format!("**{}**\n{}\n{}\n\n", r.title, r.url, r.snippet)
            };
            text.push_str(&entry);
            if text.len() > max_chars {
                text.truncate(max_chars);
                text.push_str("\n\n[Result truncated]");
                break;
            }
        }
        text
    }

    fn filter_domains(
        text: &str,
        allowed: &[String],
        blocked: &[String],
    ) -> String {
        let mut lines: Vec<&str> = text.lines().collect();
        if !blocked.is_empty() {
            lines.retain(|l| !blocked.iter().any(|d| l.contains(d.as_str())));
        }
        if !allowed.is_empty() {
            lines.retain(|l| {
                // Keep header lines (not URLs)
                l.starts_with("Search engine:")
                    || l.starts_with("**")
                    || l.starts_with("---")
                    || l.starts_with("[Result")
                    || allowed.iter().any(|d| l.contains(d.as_str()))
            });
        }
        lines.join("\n")
    }
}

impl Default for WebSearchTool {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Tool for WebSearchTool {
    fn name(&self) -> &str {
        "WebSearch"
    }

    fn description(&self) -> &str {
        "Search the web using multiple search engines (no API key required).\n\nAvailable engines (default: auto-select via batch racing):\n- General: Sogou, 360, DuckDuckGo, Baidu, WeChat Articles\n- Academic: arXiv, Crossref\n\nUsage:\n- For academic paper search, prefer engine=\"arXiv\" or engine=\"Crossref\" over general web search.\n- Only use WebSearch and WebFetch for accessing information beyond your training data.\n- Web search results may contain outdated information — verify critical facts before relying on them.\n- IMPORTANT: You MUST include a \"Sources:\" section listing all relevant URLs"
    }

    fn input_schema(&self) -> &serde_json::Map<String, serde_json::Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &serde_json::Map<String, serde_json::Value>,
        _ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let query = super::get_str(input, "query").unwrap_or("");
        if query.len() < 2 {
            return Err(ToolError::InvalidInput(
                "Query must be at least 2 characters".into(),
            ));
        }

        let engine = super::get_str(input, "engine");
        let max_chars = super::get_int(input, "max_results")
            .map(|n| (n as usize).max(100))
            .unwrap_or(DEFAULT_MAX_CHARS);

        let blocked: Vec<String> = input
            .get("blocked_domains")
            .and_then(|v| v.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|v| v.as_str().map(|s| s.to_string()))
                    .collect()
            })
            .unwrap_or_default();
        let allowed: Vec<String> = input
            .get("allowed_domains")
            .and_then(|v| v.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|v| v.as_str().map(|s| s.to_string()))
                    .collect()
            })
            .unwrap_or_default();

        let client = Self::make_client();

        // Academic engines: direct API call, no racing
        if let Some(name) = engine {
            if ACADEMIC_NAMES.contains(&name) {
                let eng = ENGINES.iter().find(|e| e.name == name).unwrap();
                return match Self::search_academic(&client, query, eng).await {
                    Ok(results) => {
                        let text = Self::format_results(name, &results, max_chars);
                        let filtered = Self::filter_domains(&text, &allowed, &blocked);
                        Ok(filtered)
                    }
                    Err(e) => Err(ToolError::Execution(e)),
                };
            }
        }

        // General engines
        let engines_to_try: Vec<&SearchEngine> = match engine {
            Some(name) => {
                // When a specific engine is specified, try it first, then fall back to others
                let primary = ENGINES.iter().find(|e| e.name == name);
                let rest: Vec<&SearchEngine> = ENGINES
                    .iter()
                    .filter(|e| e.name != name && !ACADEMIC_NAMES.contains(&e.name))
                    .collect();
                let mut combined = Vec::new();
                if let Some(p) = primary {
                    combined.push(p);
                }
                combined.extend(rest);
                combined
            }
            None => ENGINES
                .iter()
                .filter(|e| !ACADEMIC_NAMES.contains(&e.name))
                .collect(),
        };

        let mut errors = Vec::new();
        for eng in &engines_to_try {
            match Self::search_one(&client, query, eng).await {
                Ok(results) => {
                    let text = Self::format_results(eng.name, &results, max_chars);
                    let filtered = Self::filter_domains(&text, &allowed, &blocked);
                    return Ok(filtered);
                }
                Err(e) => {
                    errors.push(e);
                }
            }
        }

        Err(ToolError::Execution(format!(
            "All search engines failed.\n{}",
            errors.into_iter().take(8).collect::<Vec<_>>().join("\n")
        )))
    }

    fn summarize(
        &self,
        input: &serde_json::Map<String, serde_json::Value>,
    ) -> String {
        let query = super::get_str(input, "query").unwrap_or("");
        let engine = super::get_str(input, "engine").unwrap_or("auto");
        format!("WebSearch(\"{query}\", engine={engine})")
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

    #[test]
    fn strip_tags_basic() {
        assert_eq!(WebSearchTool::strip_tags("<b>hello</b>"), "hello");
        assert_eq!(WebSearchTool::strip_tags("<a href='x'>click</a>"), "click");
        assert_eq!(
            WebSearchTool::strip_tags("  multiple   spaces  "),
            "multiple spaces"
        );
    }

    #[test]
    fn extract_duckduckgo_results() {
        let html = r#"
        <div class="result results_links results_links_deep web-result">
            <a class="result__a" href="https://example.com/page1">Example Page</a>
            <a class="result__snippet">This is a snippet about the example page</a>
        </div>
        <div class="result results_links">
            <a class="result__a" href="https://example.com/page2">Second Result</a>
            <a class="result__snippet">Another snippet here</a>
        </div>
        "#;
        let results = WebSearchTool::extract_duckduckgo(html);
        assert_eq!(results.len(), 2);
        assert_eq!(results[0].title, "Example Page");
        assert_eq!(results[0].url, "https://example.com/page1");
        assert_eq!(results[0].snippet, "This is a snippet about the example page");
    }

    #[test]
    fn extract_baidu_results() {
        let html = r#"
        <div class="result c-container">
            <h3><a href="https://example.com/baidu1">First Baidu Result</a></h3>
        </div>
        <div class="result-op c-container">
            <a href="https://example.com/baidu2">Second Result</a>
        </div>
        "#;
        let results = WebSearchTool::extract_baidu(html);
        assert!(!results.is_empty());
        assert_eq!(results[0].title, "First Baidu Result");
    }

    #[test]
    fn extract_generic_links() {
        let html = r#"
        <a href="https://example.com/article">This is a long enough title for the link</a>
        <a href="https://google.com/search">Google Search</a>
        <a href="https://example.com/another">Another Meaningful Article Title</a>
        "#;
        let results = WebSearchTool::extract_generic(html);
        assert_eq!(results.len(), 2); // google.com is filtered out
        assert!(results.iter().all(|r| !r.url.contains("google.com")));
    }

    #[test]
    fn extract_arxiv_results() {
        let xml = r#"<?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
            <entry>
                <title>Quantum Computing Breakthrough</title>
                <summary>A novel approach to quantum error correction.</summary>
                <link href="https://arxiv.org/abs/2024.12345" rel="alternate"/>
                <published>2024-06-15T00:00:00Z</published>
                <author><name>Alice Smith</name></author>
                <author><name>Bob Jones</name></author>
            </entry>
        </feed>"#;
        let results = WebSearchTool::extract_arxiv(xml);
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].title, "Quantum Computing Breakthrough");
        assert_eq!(results[0].url, "https://arxiv.org/abs/2024.12345");
        assert!(results[0].snippet.contains("Alice Smith"));
        assert!(results[0].snippet.contains("2024"));
    }

    #[test]
    fn extract_crossref_results() {
        let json = r#"{
            "message": {
                "items": [
                    {
                        "DOI": "10.1000/test",
                        "title": ["A Test Paper on Rust Programming"],
                        "author": [{"family": "Chen"}, {"family": "Wang"}],
                        "published-print": {"date-parts": [[2023]]},
                        "container-title": ["Journal of Testing"]
                    },
                    {
                        "DOI": "10.2000/second",
                        "title": ["Another Great Paper"],
                        "author": [{"family": "Li"}],
                        "published-print": {"date-parts": [[2022]]},
                        "container-title": ["Nature"]
                    }
                ]
            }
        }"#;
        let results = WebSearchTool::extract_crossref(json);
        assert_eq!(results.len(), 2);
        assert_eq!(results[0].title, "A Test Paper on Rust Programming");
        assert_eq!(results[0].url, "https://doi.org/10.1000/test");
        assert!(results[0].snippet.contains("Chen"));
        assert!(results[0].snippet.contains("Journal of Testing"));
    }

    #[test]
    fn format_results_basic() {
        let results = vec![
            SearchResult {
                title: "Test Title".into(),
                url: "https://example.com".into(),
                snippet: "A snippet".into(),
            },
            SearchResult {
                title: "Second".into(),
                url: "https://second.com".into(),
                snippet: String::new(),
            },
        ];
        let text = WebSearchTool::format_results("DuckDuckGo", &results, 15000);
        assert!(text.contains("Search engine: DuckDuckGo"));
        assert!(text.contains("**Test Title**"));
        assert!(text.contains("https://example.com"));
        assert!(text.contains("A snippet"));
        assert!(text.contains("**Second**"));
        assert!(!text.contains("\n\n\n")); // no double snippet for empty
    }

    #[test]
    fn format_results_truncation() {
        let results: Vec<SearchResult> = (0..20)
            .map(|i| SearchResult {
                title: format!("Title {}", i),
                url: format!("https://example.com/{}", i),
                snippet: format!("Snippet {} with lots of text to fill up space quickly here", i),
            })
            .collect();
        let text = WebSearchTool::format_results("Test", &results, 200);
        assert!(text.len() <= 250); // truncation + marker
        assert!(text.contains("[Result truncated]"));
    }

    #[test]
    fn filter_domains_blocked() {
        let text = "**Result 1**\nhttps://blocked.com/page\n\n**Result 2**\nhttps://good.com/page";
        let blocked = vec!["blocked.com".to_string()];
        let filtered = WebSearchTool::filter_domains(text, &[], &blocked);
        assert!(!filtered.contains("blocked.com"));
        assert!(filtered.contains("good.com"));
    }

    #[test]
    fn filter_domains_allowed() {
        let text = "**Result 1**\nhttps://allowed.com/page\n\n**Result 2**\nhttps://other.com/page";
        let allowed = vec!["allowed.com".to_string()];
        let filtered = WebSearchTool::filter_domains(text, &allowed, &[]);
        assert!(filtered.contains("allowed.com"));
        assert!(!filtered.contains("other.com"));
    }

    #[tokio::test]
    async fn websearch_short_query_rejected() {
        let tool = WebSearchTool::new();
        let mut input = serde_json::Map::new();
        input.insert("query".into(), "a".into());
        let result = tool.call(&input, &ctx()).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn websearch_unknown_engine_error() {
        let tool = WebSearchTool::new();
        let mut input = serde_json::Map::new();
        input.insert("query".into(), "test query".into());
        input.insert("engine".into(), "NonExistent".into());
        // With unknown engine, the first engine in the fallback list won't match
        // (engine is Some("NonExistent")), so it filters to empty and all fail.
        let result = tool.call(&input, &ctx()).await;
        // Should eventually fail with "all engines failed" or similar
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn websearch_arxiv_engine() {
        let tool = WebSearchTool::new();
        let mut input = serde_json::Map::new();
        input.insert("query".into(), "quantum computing".into());
        input.insert("engine".into(), "arXiv".into());

        let result = tool.call(&input, &ctx()).await;
        // Network might be unavailable — verify it either succeeds or fails gracefully
        match result {
            Ok(text) => assert!(text.starts_with("Search engine: arXiv")),
            Err(e) => {
                let msg = format!("{e}");
                assert!(msg.contains("arXiv") || msg.contains("failed") || msg.contains("network"));
            }
        }
    }

    #[test]
    fn websearch_summarize() {
        let tool = WebSearchTool::new();
        let mut input = serde_json::Map::new();
        input.insert("query".into(), "test query".into());
        input.insert("engine".into(), "DuckDuckGo".into());
        let summary = tool.summarize(&input);
        assert!(summary.contains("test query"));
        assert!(summary.contains("DuckDuckGo"));
    }
}
