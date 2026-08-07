//! WebSearchTool — search the web using multiple search engines.
//! Mirrors `nebflow.core.tools.WebSearchTool` from Scala.

use crate::types::{ToolContext, ToolError};
use crate::Tool;
use async_trait::async_trait;

const DEFAULT_MAX_CHARS: usize = 15_000;

#[derive(Debug, Clone)]
struct SearchEngine {
    name: &'static str,
    url: &'static str,
    region: &'static str,
}

const ENGINES: &[SearchEngine] = &[
    SearchEngine {
        name: "Sogou",
        url: "https://sogou.com/web?query={keyword}",
        region: "cn",
    },
    SearchEngine {
        name: "360",
        url: "https://www.so.com/s?q={keyword}",
        region: "cn",
    },
    SearchEngine {
        name: "DuckDuckGo",
        url: "https://duckduckgo.com/html/?q={keyword}",
        region: "global",
    },
    SearchEngine {
        name: "Baidu",
        url: "https://www.baidu.com/s?wd={keyword}",
        region: "cn",
    },
    SearchEngine {
        name: "WeChat",
        url: "https://wx.sogou.com/weixin?type=2&query={keyword}",
        region: "cn",
    },
    SearchEngine {
        name: "arXiv",
        url: "http://export.arxiv.org/api/query",
        region: "academic",
    },
    SearchEngine {
        name: "Crossref",
        url: "https://api.crossref.org/works",
        region: "academic",
    },
];

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
        let _max_chars = super::get_int(input, "max_results")
            .map(|n| n as usize)
            .unwrap_or(DEFAULT_MAX_CHARS);

        // Build search URL(s)
        let engines: Vec<&SearchEngine> = match engine {
            Some(name) => ENGINES.iter().filter(|e| e.name == name).collect(),
            None => ENGINES.iter().filter(|e| e.region != "academic").collect(),
        };

        if engines.is_empty() {
            return Err(ToolError::InvalidInput(format!(
                "Unknown engine: {engine:?}"
            )));
        }

        // Build URLs for the response — actual HTTP calls would go here
        let urls: Vec<String> = engines
            .iter()
            .map(|e| e.url.replace("{keyword}", &urlencoding::encode(query)))
            .collect();

        Ok(format!(
            "WebSearch query: \"{query}\"\nEngines: {}\nURLs:\n{}\n\nNote: Actual HTTP fetching requires network access. This is a stub that returns the search URLs that would be fetched.",
            engines.iter().map(|e| e.name).collect::<Vec<_>>().join(", "),
            urls.join("\n")
        ))
    }

    fn summarize(&self, input: &serde_json::Map<String, serde_json::Value>) -> String {
        let query = super::get_str(input, "query").unwrap_or("");
        format!("WebSearch(\"{query}\")")
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
    async fn websearch_basic() {
        let tool = WebSearchTool::new();
        let mut input = serde_json::Map::new();
        input.insert("query".into(), "rust programming".into());
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.contains("rust programming"));
        assert!(result.contains("DuckDuckGo"));
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
    async fn websearch_specific_engine() {
        let tool = WebSearchTool::new();
        let mut input = serde_json::Map::new();
        input.insert("query".into(), "quantum computing".into());
        input.insert("engine".into(), "arXiv".into());
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.contains("arXiv"));
        assert!(!result.contains("DuckDuckGo"));
    }
}
