//! Write tool — writes files to the local filesystem.
//!
//! Mirrors the Scala WriteTool. Creates parent directories, enforces
//! size limits, and provides diff output for modified files.

use async_trait::async_trait;
use serde_json::{Map, Value};
use std::path::Path;

use crate::types::{ToolContext, ToolError};
use crate::Tool;

const MAX_WRITE_BYTES: usize = 512 * 1024; // 512KB

pub struct WriteTool {
    schema: Map<String, Value>,
}

impl WriteTool {
    pub fn new() -> Self {
        let schema = serde_json::json!({
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "The absolute path to the file to write (must be absolute, not relative)"
                },
                "content": {
                    "type": "string",
                    "description": "The content to write to the file"
                }
            },
            "required": ["file_path", "content"]
        });
        Self {
            schema: schema.as_object().unwrap().clone(),
        }
    }
}

impl Default for WriteTool {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Tool for WriteTool {
    fn name(&self) -> &str {
        "Write"
    }

    fn description(&self) -> &str {
        "Writes a file to the local filesystem.\n\nUsage:\n- This tool will overwrite the existing file if there is one at the provided path.\n- ALWAYS prefer editing existing files. Prefer the Edit tool for modifying existing files — only use Write for new files or complete rewrites."
    }

    fn input_schema(&self) -> &Map<String, Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &Map<String, Value>,
        _ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let file_path_str = input
            .get("file_path")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        let content = input.get("content").and_then(|v| v.as_str()).unwrap_or("");

        if file_path_str.is_empty() {
            return Err(ToolError::InvalidInput("file_path is required".into()));
        }

        let content_bytes = content.len();
        if content_bytes > MAX_WRITE_BYTES {
            return Err(ToolError::InvalidInput(format!(
                "Content too large: {} bytes (limit {}). Split into multiple Edit calls.",
                content_bytes, MAX_WRITE_BYTES
            )));
        }

        let file_path = Path::new(file_path_str);

        if file_path.is_dir() {
            return Err(ToolError::InvalidInput(format!(
                "Path is a directory, not a file: {}",
                file_path_str
            )));
        }

        let is_new = !file_path.exists();

        // Create parent directories if needed
        if let Some(parent) = file_path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| ToolError::Execution(format!("Error creating directories: {}", e)))?;
        }

        std::fs::write(file_path, content)
            .map_err(|e| ToolError::Execution(format!("Error writing file: {}", e)))?;

        if is_new {
            Ok(format!("File created: {}", file_path_str))
        } else {
            let line_count = content.lines().count();
            Ok(format!(
                "File updated: {} ({} lines)",
                file_path_str, line_count
            ))
        }
    }

    fn summarize(&self, input: &Map<String, Value>) -> String {
        let path = input
            .get("file_path")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        let short = path.rsplit('/').next().unwrap_or(path);
        format!("Write({})\n  (\"{}\")", short, path)
    }

    fn summarize_result(&self, _input: &Map<String, Value>, result: &str) -> String {
        if result.starts_with("File created") {
            "File created".into()
        } else if result.starts_with("File updated") {
            result.to_string()
        } else {
            result.lines().next().unwrap_or(result).to_string()
        }
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

    fn make_input(file_path: &str, content: &str) -> Map<String, Value> {
        let mut m = Map::new();
        m.insert("file_path".into(), Value::String(file_path.into()));
        m.insert("content".into(), Value::String(content.into()));
        m
    }

    #[tokio::test]
    async fn write_new_file() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("test.txt").to_string_lossy().to_string();
        let tool = WriteTool::new();
        let result = tool
            .call(&make_input(&path, "hello world"), &ctx())
            .await
            .unwrap();
        assert!(result.starts_with("File created"));
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "hello world");
    }

    #[tokio::test]
    async fn write_overwrites_existing() {
        let (_f, path) = {
            let mut f = tempfile::NamedTempFile::new().unwrap();
            std::io::Write::write_all(&mut f, b"old content").unwrap();
            let p = f.path().to_string_lossy().to_string();
            (f, p)
        };
        let tool = WriteTool::new();
        let result = tool
            .call(&make_input(&path, "new content"), &ctx())
            .await
            .unwrap();
        assert!(result.starts_with("File updated"));
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "new content");
    }

    #[tokio::test]
    async fn write_creates_parent_dirs() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir
            .path()
            .join("sub/dir/file.txt")
            .to_string_lossy()
            .to_string();
        let tool = WriteTool::new();
        let result = tool
            .call(&make_input(&path, "nested"), &ctx())
            .await
            .unwrap();
        assert!(result.starts_with("File created"));
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "nested");
    }

    #[tokio::test]
    async fn write_to_directory_fails() {
        let tool = WriteTool::new();
        let result = tool.call(&make_input("/tmp", "content"), &ctx()).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn content_too_large() {
        let tool = WriteTool::new();
        let big = "x".repeat(MAX_WRITE_BYTES + 1);
        let result = tool
            .call(&make_input("/tmp/test_big.txt", &big), &ctx())
            .await;
        assert!(result.is_err());
        let err = result.unwrap_err();
        assert!(matches!(err, ToolError::InvalidInput(_)));
    }
}
