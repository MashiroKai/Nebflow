//! SaveWorkspaceItem tool — saves an item to the session workspace.
//!
//! The Scala original persists items via a knowledgeStore actor. The Rust
//! backend has no knowledge store yet, so this validates the item structure
//! (name, content, kind) and returns a confirmation without persisting.

use async_trait::async_trait;
use serde_json::{Map, Value};

use crate::types::{ToolContext, ToolError};
use crate::Tool;

/// Valid workspace item kinds.
const VALID_KINDS: &[&str] = &["note", "file", "snippet", "plan", "reference"];

const MAX_CONTENT_BYTES: usize = 256 * 1024; // 256KB

pub struct SaveWorkspaceItemTool {
    schema: Map<String, Value>,
}

impl SaveWorkspaceItemTool {
    pub fn new() -> Self {
        let schema = serde_json::json!({
            "type": "object",
            "properties": {
                "name": {
                    "type": "string",
                    "description": "Item name (used as the workspace key)"
                },
                "content": {
                    "type": "string",
                    "description": "Item content (text)"
                },
                "kind": {
                    "type": "string",
                    "description": "Item kind: note, file, snippet, plan, reference (default: note)"
                }
            },
            "required": ["name", "content"]
        });
        Self {
            schema: schema.as_object().unwrap().clone(),
        }
    }
}

impl Default for SaveWorkspaceItemTool {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Tool for SaveWorkspaceItemTool {
    fn name(&self) -> &str {
        "SaveWorkspaceItem"
    }

    fn description(&self) -> &str {
        "Validates and records a workspace item (name + content + kind). \
         Persistent workspace storage is not available in the Rust backend yet."
    }

    fn input_schema(&self) -> &Map<String, Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &Map<String, Value>,
        ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let name = input
            .get("name")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim();

        if name.is_empty() {
            return Err(ToolError::InvalidInput("name is required".into()));
        }
        if name.contains('/') || name.contains('\\') || name.contains("..") {
            return Err(ToolError::InvalidInput(format!(
                "name must not contain path separators or '..': {}",
                name
            )));
        }

        let content = input.get("content").and_then(|v| v.as_str()).unwrap_or("");

        if content.len() > MAX_CONTENT_BYTES {
            return Err(ToolError::InvalidInput(format!(
                "content too large: {} bytes (limit {}KB)",
                content.len(),
                MAX_CONTENT_BYTES / 1024
            )));
        }

        let kind = input.get("kind").and_then(|v| v.as_str()).unwrap_or("note");

        if !VALID_KINDS.contains(&kind) {
            return Err(ToolError::InvalidInput(format!(
                "invalid kind \"{}\". Valid: {}",
                kind,
                VALID_KINDS.join(", ")
            )));
        }

        Ok(format!(
            "Workspace item \"{}\" ({}, {} bytes) validated for session {}. \
             Note: persistent workspace storage is not available in the Rust backend yet; \
             the item was not saved to disk.",
            name,
            kind,
            content.len(),
            ctx.session_id
        ))
    }

    fn summarize(&self, input: &Map<String, Value>) -> String {
        let name = input.get("name").and_then(|v| v.as_str()).unwrap_or("");
        format!("SaveWorkspaceItem({})", name)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx() -> ToolContext {
        ToolContext {
            session_id: "s1".into(),
            agent_id: "a1".into(),
            working_directory: "/tmp".into(),
            depth: 0,
            ..Default::default()
        }
    }

    fn base_input() -> Map<String, Value> {
        let mut m = Map::new();
        m.insert("name".into(), Value::String("my-note".into()));
        m.insert("content".into(), Value::String("hello workspace".into()));
        m
    }

    #[tokio::test]
    async fn valid_item_accepted() {
        let tool = SaveWorkspaceItemTool::new();
        let result = tool.call(&base_input(), &ctx()).await.unwrap();
        assert!(result.contains("my-note"));
        assert!(result.contains("note"));
    }

    #[tokio::test]
    async fn path_traversal_name_rejected() {
        let tool = SaveWorkspaceItemTool::new();
        let mut input = base_input();
        input.insert("name".into(), Value::String("../escape".into()));
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }

    #[tokio::test]
    async fn invalid_kind_rejected() {
        let tool = SaveWorkspaceItemTool::new();
        let mut input = base_input();
        input.insert("kind".into(), Value::String("bogus".into()));
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }

    #[tokio::test]
    async fn empty_name_rejected() {
        let tool = SaveWorkspaceItemTool::new();
        let mut input = base_input();
        input.insert("name".into(), Value::String("".into()));
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }
}
