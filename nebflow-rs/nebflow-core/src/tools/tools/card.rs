//! CardTool — renders HTML in a sandboxed iframe in the chat stream.
//! Mirrors `nebflow.core.tools.CardTool` from Scala.

use crate::types::{ToolContext, ToolError};
use crate::Tool;
use async_trait::async_trait;

pub struct CardTool {
    schema: serde_json::Map<String, serde_json::Value>,
}

impl CardTool {
    pub fn new() -> Self {
        let mut schema = serde_json::Map::new();
        schema.insert("type".into(), "object".into());
        let mut props = serde_json::Map::new();
        props.insert("html".into(), serde_json::json!({"type": "string", "description": "HTML content to render in the card"}));
        schema.insert("properties".into(), serde_json::Value::Object(props));
        schema.insert("required".into(), serde_json::json!(["html"]));
        Self { schema }
    }
}

impl Default for CardTool {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Tool for CardTool {
    fn name(&self) -> &str {
        "Card"
    }
    fn description(&self) -> &str {
        "Renders arbitrary HTML in a sandboxed iframe in the chat stream.\n\nAgents emit HTML directly. The frontend renders it in a sandboxed <iframe> with theme variables injected, so dark mode works automatically.\n\nLocal file references (src=\"...\") pointing to image/video/audio/font/PDF files on disk are converted to /api/nf-file?path=... URLs."
    }
    fn input_schema(&self) -> &serde_json::Map<String, serde_json::Value> {
        &self.schema
    }
    fn max_result_size(&self) -> usize {
        usize::MAX
    }

    async fn call(
        &self,
        input: &serde_json::Map<String, serde_json::Value>,
        _ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let html = super::get_str(input, "html").unwrap_or("");
        if html.is_empty() {
            return Err(ToolError::InvalidInput("html is required".into()));
        }
        Ok(format!("[Card] Rendered {} bytes of HTML.", html.len()))
    }

    fn summarize(&self, _input: &serde_json::Map<String, serde_json::Value>) -> String {
        "Card()".to_string()
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
    async fn card_basic() {
        let tool = CardTool::new();
        let mut input = serde_json::Map::new();
        input.insert("html".into(), "<div>hello</div>".into());
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.contains("Rendered"));
    }

    #[tokio::test]
    async fn card_empty_html() {
        let tool = CardTool::new();
        let input = serde_json::Map::new();
        let result = tool.call(&input, &ctx()).await;
        assert!(result.is_err());
    }
}
