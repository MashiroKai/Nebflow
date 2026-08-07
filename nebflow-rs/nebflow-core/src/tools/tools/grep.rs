//! GrepTool — regex search using ripgrep.
//! Mirrors `nebflow.core.tools.GrepTool` from Scala.

use crate::types::{ToolContext, ToolError};
use crate::Tool;
use async_trait::async_trait;
use tokio::process::Command;

const DEFAULT_HEAD_LIMIT: usize = 250;
const VCS_DIRS: &[&str] = &[".git", ".svn", ".hg", ".bzr", ".jj"];

pub struct GrepTool {
    schema: serde_json::Map<String, serde_json::Value>,
}

impl GrepTool {
    pub fn new() -> Self {
        let mut schema = serde_json::Map::new();
        schema.insert("type".into(), "object".into());
        let mut props = serde_json::Map::new();
        props.insert(
            "pattern".into(),
            serde_json::json!({"type": "string", "description": "The regular expression pattern to search for in file contents"}),
        );
        props.insert(
            "path".into(),
            serde_json::json!({"type": "string", "description": "File or directory to search in. Defaults to current working directory."}),
        );
        props.insert(
            "glob".into(),
            serde_json::json!({"type": "string", "description": "Glob pattern to filter files (e.g. \"*.js\", \"*.{ts,tsx}\")"}),
        );
        props.insert(
            "type".into(),
            serde_json::json!({"type": "string", "description": "File type to search (rg --type). Common types: js, py, rust, go, java, etc."}),
        );
        props.insert(
            "output_mode".into(),
            serde_json::json!({"type": "string", "enum": ["content", "files_with_matches", "count"], "description": "Output mode. Defaults to \"files_with_matches\"."}),
        );
        props.insert(
            "-i".into(),
            serde_json::json!({"type": "boolean", "description": "Case insensitive search"}),
        );
        props.insert(
            "-A".into(),
            serde_json::json!({"type": "number", "description": "Lines after match"}),
        );
        props.insert(
            "-B".into(),
            serde_json::json!({"type": "number", "description": "Lines before match"}),
        );
        props.insert(
            "-C".into(),
            serde_json::json!({"type": "number", "description": "Lines around match"}),
        );
        props.insert("head_limit".into(), serde_json::json!({"type": "number", "description": "Limit output to first N results. Defaults to 250. Pass 0 for unlimited."}));
        props.insert("offset".into(), serde_json::json!({"type": "number", "description": "Skip first N results before applying head_limit."}));
        props.insert("multiline".into(), serde_json::json!({"type": "boolean", "description": "Enable multiline mode where . matches newlines."}));
        schema.insert("properties".into(), serde_json::Value::Object(props));
        schema.insert("required".into(), serde_json::json!(["pattern"]));
        Self { schema }
    }
}

impl Default for GrepTool {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Tool for GrepTool {
    fn name(&self) -> &str {
        "Grep"
    }

    fn description(&self) -> &str {
        "A powerful search tool built on ripgrep.\n\nUsage:\n- ALWAYS use Grep for search tasks. Do not use Bash with grep/rg.\n- Supports full regex syntax (e.g. \"log.*Error\", \"function\\s+\\w+\")\n- Filter files with glob parameter (e.g. \"*.js\", \"*.{ts,tsx}\") or type parameter (e.g. \"js\", \"py\", \"rust\")\n- Output modes: \"content\" shows matching lines (supports -A/-B/-C context), \"files_with_matches\" shows only file paths, \"count\" shows match counts\n- Use -i for case insensitive search"
    }

    fn input_schema(&self) -> &serde_json::Map<String, serde_json::Value> {
        &self.schema
    }

    fn max_result_size(&self) -> usize {
        20_000
    }

    async fn call(
        &self,
        input: &serde_json::Map<String, serde_json::Value>,
        ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let pattern = super::get_str(input, "pattern").unwrap_or("");
        let path_opt = super::get_str(input, "path");
        let mode = super::get_str(input, "output_mode").unwrap_or("files_with_matches");
        let limit = super::get_int(input, "head_limit")
            .map(|l| if l == 0 { usize::MAX } else { l as usize })
            .unwrap_or(DEFAULT_HEAD_LIMIT);
        let offset = super::get_int(input, "offset")
            .map(|o| o as usize)
            .unwrap_or(0);

        let search_root = match path_opt {
            Some(p) if p.starts_with('/') => p.to_string(),
            Some(p) => format!("{}/{}", ctx.working_directory, p),
            None => ctx.working_directory.clone(),
        };

        let mut args: Vec<String> = vec![
            "--color=never".into(),
            "--hidden".into(),
            "--max-columns".into(),
            "500".into(),
        ];

        // Exclude VCS directories
        for dir in VCS_DIRS {
            args.push("--glob".into());
            args.push(format!("!{dir}"));
        }

        // Case insensitive
        if super::get_bool(input, "-i").unwrap_or(false) {
            args.push("--ignore-case".into());
        }

        // Multiline
        if super::get_bool(input, "multiline").unwrap_or(false) {
            args.push("-U".into());
            args.push("--multiline-dotall".into());
        }

        // Output mode
        match mode {
            "files_with_matches" => args.push("--files-with-matches".into()),
            "count" => args.push("--count".into()),
            "content" => args.push("--line-number".into()),
            _ => args.push("--files-with-matches".into()),
        }

        // Context lines for content mode
        if mode == "content" {
            if let Some(c) = super::get_int(input, "-C") {
                args.push("-C".into());
                args.push(c.to_string());
            } else {
                if let Some(b) = super::get_int(input, "-B") {
                    args.push("-B".into());
                    args.push(b.to_string());
                }
                if let Some(a) = super::get_int(input, "-A") {
                    args.push("-A".into());
                    args.push(a.to_string());
                }
            }
        }

        // Glob filter
        if let Some(g) = super::get_str(input, "glob") {
            args.push("--glob".into());
            args.push(g.into());
        }

        // Type filter
        if let Some(t) = super::get_str(input, "type") {
            args.push("--type".into());
            args.push(t.into());
        }

        // Pattern (escape if starts with -)
        if pattern.starts_with('-') {
            args.push("-e".into());
            args.push(pattern.into());
        } else {
            args.push(pattern.into());
        }

        // Search root
        args.push(search_root);

        let output = Command::new("rg")
            .args(&args)
            .output()
            .await
            .map_err(|e| ToolError::Execution(format!("Failed to run rg: {e}")))?;

        let stdout = String::from_utf8_lossy(&output.stdout);
        let stderr = String::from_utf8_lossy(&output.stderr);
        let exit_code = output.status.code().unwrap_or(-1);

        if exit_code == 2 {
            return Err(ToolError::Execution(format!(
                "Error: {}",
                if !stderr.trim().is_empty() {
                    stderr.trim().to_string()
                } else {
                    format!("rg exited with code {exit_code}")
                }
            )));
        }

        if stdout.trim().is_empty() {
            return Ok("No matches found.".to_string());
        }

        let all_lines: Vec<&str> = stdout.trim().split('\n').collect();
        let sliced: Vec<&str> = all_lines.iter().skip(offset).take(limit).cloned().collect();

        let result = sliced.join("\n");

        // Pagination info
        let truncated = all_lines.len() > offset + limit;
        let pagination = if !truncated && offset == 0 {
            String::new()
        } else {
            let mut parts = Vec::new();
            if truncated {
                parts.push(format!("limit: {limit}"));
            }
            if offset > 0 {
                parts.push(format!("offset: {offset}"));
            }
            format!(
                "\n\n[Showing results with pagination = {}]",
                parts.join(", ")
            )
        };

        // Count mode summary
        let summary = if mode == "count" {
            let mut total = 0;
            let mut file_count = 0;
            for line in &sliced {
                if let Some(colon_idx) = line.rfind(':') {
                    if let Ok(c) = line[colon_idx + 1..].trim().parse::<usize>() {
                        total += c;
                        file_count += 1;
                    }
                }
            }
            format!(
                "\n\nFound {total} total {} across {file_count} {}.",
                if total == 1 {
                    "occurrence"
                } else {
                    "occurrences"
                },
                if file_count == 1 { "file" } else { "files" }
            )
        } else {
            String::new()
        };

        Ok(format!("{result}{summary}{pagination}"))
    }

    fn summarize(&self, input: &serde_json::Map<String, serde_json::Value>) -> String {
        let pattern = super::get_str(input, "pattern").unwrap_or("");
        match super::get_str(input, "path") {
            Some(p) => format!("Grep(\"{pattern}\")\n  (path=\"{p}\")"),
            None => format!("Grep(\"{pattern}\")"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn ctx(work_dir: &str) -> ToolContext {
        ToolContext {
            session_id: "test".into(),
            agent_id: "test".into(),
            working_directory: work_dir.into(),
            depth: 0,
            ..Default::default()
        }
    }

    #[tokio::test]
    async fn grep_find_matches() {
        let tmp = tempfile::tempdir().unwrap();
        fs::write(tmp.path().join("test.rs"), "fn hello() {}\nfn world() {}").unwrap();

        let tool = GrepTool::new();
        let mut input = serde_json::Map::new();
        input.insert("pattern".into(), "fn hello".into());
        input.insert("path".into(), tmp.path().to_string_lossy().into());
        let result = tool.call(&input, &ctx("/tmp")).await.unwrap();
        assert!(result.contains("test.rs"));
    }

    #[tokio::test]
    async fn grep_content_mode() {
        let tmp = tempfile::tempdir().unwrap();
        fs::write(tmp.path().join("test.rs"), "fn hello() {}\nfn world() {}").unwrap();

        let tool = GrepTool::new();
        let mut input = serde_json::Map::new();
        input.insert("pattern".into(), "fn hello".into());
        input.insert("path".into(), tmp.path().to_string_lossy().into());
        input.insert("output_mode".into(), "content".into());
        let result = tool.call(&input, &ctx("/tmp")).await.unwrap();
        assert!(result.contains("fn hello() {}"));
    }

    #[tokio::test]
    async fn grep_no_matches() {
        let tmp = tempfile::tempdir().unwrap();
        fs::write(tmp.path().join("test.rs"), "nothing here").unwrap();

        let tool = GrepTool::new();
        let mut input = serde_json::Map::new();
        input.insert("pattern".into(), "nonexistent_pattern_xyz".into());
        input.insert("path".into(), tmp.path().to_string_lossy().into());
        let result = tool.call(&input, &ctx("/tmp")).await.unwrap();
        assert_eq!(result, "No matches found.");
    }

    #[tokio::test]
    async fn grep_case_insensitive() {
        let tmp = tempfile::tempdir().unwrap();
        fs::write(tmp.path().join("test.rs"), "Hello World").unwrap();

        let tool = GrepTool::new();
        let mut input = serde_json::Map::new();
        input.insert("pattern".into(), "hello".into());
        input.insert("path".into(), tmp.path().to_string_lossy().into());
        input.insert("-i".into(), true.into());
        let result = tool.call(&input, &ctx("/tmp")).await.unwrap();
        assert!(result.contains("test.rs"));
    }

    #[tokio::test]
    async fn grep_count_mode() {
        let tmp = tempfile::tempdir().unwrap();
        fs::write(tmp.path().join("test.rs"), "foo\nfoo\nbar").unwrap();

        let tool = GrepTool::new();
        let mut input = serde_json::Map::new();
        input.insert("pattern".into(), "foo".into());
        input.insert("path".into(), tmp.path().to_string_lossy().into());
        input.insert("output_mode".into(), "count".into());
        let result = tool.call(&input, &ctx("/tmp")).await.unwrap();
        assert!(result.contains("Found 2"));
    }
}
