//! Glob tool — fast file pattern matching.
//!
//! Mirrors the Scala GlobTool. Uses the `glob` crate for pattern matching
//! and returns results sorted by modification time.

use async_trait::async_trait;
use serde_json::{Map, Value};
use std::path::PathBuf;

use crate::types::{ToolContext, ToolError};
use crate::Tool;

const MAX_RESULTS: usize = 100;

pub struct GlobTool {
    schema: Map<String, Value>,
}

impl GlobTool {
    pub fn new() -> Self {
        let schema = serde_json::json!({
            "type": "object",
            "properties": {
                "pattern": {
                    "type": "string",
                    "description": "The glob pattern to match files against (e.g. \"**/*.js\", \"src/**/*.ts\")"
                },
                "path": {
                    "type": "string",
                    "description": "The directory to search in. Defaults to current working directory."
                }
            },
            "required": ["pattern"]
        });
        Self {
            schema: schema.as_object().unwrap().clone(),
        }
    }
}

impl Default for GlobTool {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Tool for GlobTool {
    fn name(&self) -> &str {
        "Glob"
    }

    fn description(&self) -> &str {
        "Fast file pattern matching tool that works with any codebase size.\n\n- Supports glob patterns like \"**/*.js\" or \"src/**/*.ts\"\n- Returns matching file paths sorted by modification time\n- Use this tool when you need to quickly find files by name patterns"
    }

    fn input_schema(&self) -> &Map<String, Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &Map<String, Value>,
        ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let pattern = input.get("pattern").and_then(|v| v.as_str()).unwrap_or("");
        let path_opt = input.get("path").and_then(|v| v.as_str());

        if pattern.is_empty() {
            return Err(ToolError::InvalidInput("pattern is required".into()));
        }

        let search_root = match path_opt {
            Some(p) if !p.is_empty() => PathBuf::from(p),
            _ => PathBuf::from(&ctx.working_directory),
        };

        // Build the full glob pattern
        let full_pattern = format!("{}/{}", search_root.display(), pattern);

        let mut entries: Vec<(PathBuf, std::time::SystemTime)> = Vec::new();

        let glob_iter = glob::glob(&full_pattern)
            .map_err(|e| ToolError::Execution(format!("Invalid glob pattern: {}", e)))?;

        for entry in glob_iter {
            match entry {
                Ok(path) => {
                    // Skip VCS directories — the dir itself and anything under it
                    let is_vcs = path.components().any(|c| {
                        matches!(
                            c.as_os_str().to_str(),
                            Some(".git") | Some(".svn") | Some(".hg") | Some(".bzr") | Some(".jj")
                        )
                    });
                    if is_vcs {
                        continue;
                    }

                    let mtime = std::fs::metadata(&path)
                        .and_then(|m| m.modified())
                        .unwrap_or(std::time::SystemTime::UNIX_EPOCH);
                    entries.push((path, mtime));
                }
                Err(e) => {
                    tracing::warn!("Glob entry error: {}", e);
                }
            }
        }

        // Sort by modification time, newest first
        entries.sort_by_key(|b| std::cmp::Reverse(b.1));

        if entries.is_empty() {
            return Ok("No files found matching the pattern.".into());
        }

        let results: Vec<String> = entries
            .iter()
            .take(MAX_RESULTS)
            .map(|(p, _)| {
                // Convert to relative path from search root if possible
                if let Ok(rel) = p.strip_prefix(&search_root) {
                    rel.to_string_lossy().to_string()
                } else {
                    p.to_string_lossy().to_string()
                }
            })
            .collect();

        let mut output = results.join("\n");
        if results.len() >= MAX_RESULTS {
            output.push_str(
                "\n\n(Results are truncated. Consider using a more specific path or pattern.)",
            );
        }

        Ok(output)
    }

    fn summarize(&self, input: &Map<String, Value>) -> String {
        let pattern = input.get("pattern").and_then(|v| v.as_str()).unwrap_or("");
        let path = input.get("path").and_then(|v| v.as_str());
        match path {
            Some(p) if !p.is_empty() => format!("Glob(\"{}\")\n  (path=\"{}\")", pattern, p),
            _ => format!("Glob(\"{}\")", pattern),
        }
    }

    fn summarize_result(&self, _input: &Map<String, Value>, result: &str) -> String {
        if result == "No files found matching the pattern." {
            "No files found".into()
        } else {
            format!("{} files found", result.lines().count())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    // Note: the `ctx()` helper below is unused because each test builds its own
    // ToolContext with a unique tempdir as working_directory.
    #[allow(dead_code)]
    fn ctx() -> ToolContext {
        let dir = tempfile::tempdir().unwrap();
        ToolContext {
            session_id: "test".into(),
            agent_id: "test".into(),
            working_directory: dir.path().to_string_lossy().to_string(),
            depth: 0,
            ..Default::default()
        }
    }

    fn make_input(pattern: &str) -> Map<String, Value> {
        let mut m = Map::new();
        m.insert("pattern".into(), Value::String(pattern.into()));
        m
    }

    #[tokio::test]
    async fn glob_finds_files() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        fs::write(root.join("a.rs"), "").unwrap();
        fs::write(root.join("b.rs"), "").unwrap();
        fs::write(root.join("c.txt"), "").unwrap();

        let ctx = ToolContext {
            session_id: "test".into(),
            agent_id: "test".into(),
            working_directory: root.to_string_lossy().to_string(),
            depth: 0,
            ..Default::default()
        };

        let tool = GlobTool::new();
        let result = tool.call(&make_input("*.rs"), &ctx).await.unwrap();
        assert!(result.contains("a.rs"));
        assert!(result.contains("b.rs"));
        assert!(!result.contains("c.txt"));
    }

    #[tokio::test]
    async fn glob_no_matches() {
        let dir = tempfile::tempdir().unwrap();
        let ctx = ToolContext {
            session_id: "test".into(),
            agent_id: "test".into(),
            working_directory: dir.path().to_string_lossy().to_string(),
            depth: 0,
            ..Default::default()
        };

        let tool = GlobTool::new();
        let result = tool.call(&make_input("*.nonexistent"), &ctx).await.unwrap();
        assert_eq!(result, "No files found matching the pattern.");
    }

    #[tokio::test]
    async fn glob_recursive() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        fs::create_dir_all(root.join("src/deep")).unwrap();
        fs::write(root.join("src/deep/mod.rs"), "").unwrap();

        let ctx = ToolContext {
            session_id: "test".into(),
            agent_id: "test".into(),
            working_directory: root.to_string_lossy().to_string(),
            depth: 0,
            ..Default::default()
        };

        let tool = GlobTool::new();
        let result = tool.call(&make_input("**/*.rs"), &ctx).await.unwrap();
        assert!(result.contains("mod.rs"));
    }

    #[tokio::test]
    async fn glob_skips_vcs_dirs() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        fs::create_dir_all(root.join(".git")).unwrap();
        fs::write(root.join(".git/HEAD"), "").unwrap();
        fs::write(root.join("file.txt"), "").unwrap();

        let ctx = ToolContext {
            session_id: "test".into(),
            agent_id: "test".into(),
            working_directory: root.to_string_lossy().to_string(),
            depth: 0,
            ..Default::default()
        };

        let tool = GlobTool::new();
        let result = tool.call(&make_input("**/*"), &ctx).await.unwrap();
        assert!(result.contains("file.txt"));
        assert!(!result.contains(".git"));
    }

    #[tokio::test]
    async fn summarize_formats() {
        let tool = GlobTool::new();
        let input = make_input("**/*.rs");
        assert!(tool.summarize(&input).contains("**/*.rs"));
    }
}
