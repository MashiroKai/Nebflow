//! Pop tool — opens a file in the Canvas panel as a new tab.
//!
//! In the Rust implementation there is no WebSocket/frontend connection,
//! so this validates the file exists and returns a confirmation string.
//! The Scala original pushes an event to the frontend via wsSend.

use async_trait::async_trait;
use serde_json::{Map, Value};
use std::path::Path;

use crate::types::{ToolContext, ToolError};
use crate::Tool;

pub struct PopTool {
    schema: Map<String, Value>,
}

impl PopTool {
    pub fn new() -> Self {
        let schema = serde_json::json!({
            "type": "object",
            "properties": {
                "filePath": {
                    "type": "string",
                    "description": "Absolute path to the file to display (supports ~ expansion)"
                },
                "title": {
                    "type": "string",
                    "description": "Custom tab title (defaults to filename)"
                }
            },
            "required": ["filePath"]
        });
        Self {
            schema: schema.as_object().unwrap().clone(),
        }
    }
}

impl Default for PopTool {
    fn default() -> Self {
        Self::new()
    }
}

fn expand_tilde(path: &str) -> String {
    if let Some(rest) = path.strip_prefix("~/") {
        if let Some(home) = std::env::var_os("HOME") {
            return format!("{}/{}", home.to_string_lossy(), rest);
        }
    }
    path.to_string()
}

#[async_trait]
impl Tool for PopTool {
    fn name(&self) -> &str {
        "Pop"
    }

    fn description(&self) -> &str {
        "Opens a file in the Canvas panel as a new tab. The file is displayed using the appropriate viewer (Monaco editor for code, markdown renderer, image viewer, PDF viewer, etc.)."
    }

    fn input_schema(&self) -> &Map<String, Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &Map<String, Value>,
        _ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let file_path_raw = input.get("filePath").and_then(|v| v.as_str()).unwrap_or("");

        if file_path_raw.is_empty() {
            return Err(ToolError::InvalidInput("filePath is required".into()));
        }

        let file_path = expand_tilde(file_path_raw);
        let path = Path::new(&file_path);

        if !path.exists() {
            return Err(ToolError::NotFound(format!(
                "File does not exist: {}",
                file_path
            )));
        }
        if path.is_dir() {
            return Err(ToolError::InvalidInput(format!(
                "Path is a directory, not a file: {}",
                file_path
            )));
        }

        let title = input
            .get("title")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
            .unwrap_or_else(|| {
                path.file_name()
                    .map(|n| n.to_string_lossy().to_string())
                    .unwrap_or_else(|| file_path.clone())
            });

        Ok(format!(
            "Opened \"{}\" in Canvas tab (title: \"{}\"). \
             Note: Rust backend has no frontend WebSocket connection; \
             the tab event is emitted as a protocol message by the caller.",
            file_path, title
        ))
    }

    fn summarize(&self, input: &Map<String, Value>) -> String {
        let path = input.get("filePath").and_then(|v| v.as_str()).unwrap_or("");
        let short = path.rsplit('/').next().unwrap_or(path);
        format!("Pop({})", short)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

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
    async fn pop_existing_file() {
        let mut f = tempfile::NamedTempFile::new().unwrap();
        f.write_all(b"hello").unwrap();
        let path = f.path().to_string_lossy().to_string();

        let tool = PopTool::new();
        let mut input = Map::new();
        input.insert("filePath".into(), Value::String(path.clone()));
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.contains("Canvas"));
    }

    #[tokio::test]
    async fn pop_nonexistent_file() {
        let tool = PopTool::new();
        let mut input = Map::new();
        input.insert(
            "filePath".into(),
            Value::String("/nonexistent/file.svg".into()),
        );
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::NotFound(_))));
    }

    #[tokio::test]
    async fn pop_missing_param() {
        let tool = PopTool::new();
        let input = Map::new();
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }

    #[tokio::test]
    async fn tilde_expansion() {
        let expanded = expand_tilde("~/foo/bar.txt");
        assert!(!expanded.starts_with('~'));
        assert!(expanded.ends_with("/foo/bar.txt"));
        let plain = expand_tilde("/abs/path.txt");
        assert_eq!(plain, "/abs/path.txt");
    }
}
