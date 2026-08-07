//! EditTool — exact string replacements in files.
//! Mirrors `nebflow.core.tools.EditTool` from Scala.

use crate::types::{ToolContext, ToolError};
use crate::Tool;
use async_trait::async_trait;
use std::fs;
use std::path::Path;

use super::super::string_matcher;

const MAX_EDIT_FILE_SIZE: u64 = 1024 * 1024 * 1024; // 1 GiB

pub struct EditTool {
    schema: serde_json::Map<String, serde_json::Value>,
}

impl EditTool {
    pub fn new() -> Self {
        let mut schema = serde_json::Map::new();
        schema.insert("type".into(), "object".into());
        let mut props = serde_json::Map::new();
        props.insert(
            "file_path".into(),
            serde_json::json!({"type": "string", "description": "The absolute path to the file to modify"}),
        );
        props.insert(
            "old_string".into(),
            serde_json::json!({"type": "string", "description": "The text to replace"}),
        );
        props.insert(
            "new_string".into(),
            serde_json::json!({"type": "string", "description": "The replacement text"}),
        );
        props.insert(
            "replace_all".into(),
            serde_json::json!({"type": "boolean", "description": "Replace all occurences of old_string (default false)"}),
        );
        schema.insert("properties".into(), serde_json::Value::Object(props));
        schema.insert(
            "required".into(),
            serde_json::json!(["file_path", "old_string", "new_string"]),
        );
        Self { schema }
    }
}

impl Default for EditTool {
    fn default() -> Self {
        Self::new()
    }
}

fn format_size(bytes: u64) -> String {
    if bytes >= 1024 * 1024 * 1024 {
        format!("{:.1} GiB", bytes as f64 / (1024.0 * 1024.0 * 1024.0))
    } else if bytes >= 1024 * 1024 {
        format!("{:.1} MiB", bytes as f64 / (1024.0 * 1024.0))
    } else if bytes >= 1024 {
        format!("{:.1} KiB", bytes as f64 / 1024.0)
    } else {
        format!("{bytes} B")
    }
}

/// Count non-overlapping occurrences of needle in haystack.
fn count_matches(haystack: &str, needle: &str) -> usize {
    if needle.is_empty() {
        return 0;
    }
    let mut count = 0;
    let mut idx = 0;
    while let Some(found) = haystack[idx..].find(needle) {
        count += 1;
        idx += found + needle.len();
    }
    count
}

#[async_trait]
impl Tool for EditTool {
    fn name(&self) -> &str {
        "Edit"
    }

    fn description(&self) -> &str {
        "Performs exact string replacements in files.\n\nUsage:\n- Recommended: read the file with the Read tool first so the edit is informed by current state and exact indentation.\n- When editing text from Read tool output, ensure you preserve the exact indentation (tabs/spaces) as appears AFTER the line number prefix.\n- ALWAYS prefer editing existing files in the codebase. NEVER write new files unless explicitly required.\n- Use replace_all for replacing and renaming strings across the file.\n- Do not use Bash (sed, awk) to edit files — use this tool instead.\n\nEdit patterns:\n- Rename a variable: Use replace_all to change every occurrence. Do not do it one at a time.\n- Modify a specific function: Include enough context (function signature, surrounding lines) to make the old_string unique.\n- Multi-location edits: If the same change needs to happen in multiple places, use multiple Edit calls in parallel rather than trying to write a complex regex.\n- Large refactors: If a change affects more than 3-4 files, consider whether the scope matches what the user asked for."
    }

    fn input_schema(&self) -> &serde_json::Map<String, serde_json::Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &serde_json::Map<String, serde_json::Value>,
        _ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let file_path = super::get_str(input, "file_path").unwrap_or("");
        let old_string = super::get_str(input, "old_string").unwrap_or("");
        let new_string = super::get_str(input, "new_string").unwrap_or("");
        let replace_all = super::get_bool(input, "replace_all").unwrap_or(false);

        if !Path::new(file_path).is_absolute() {
            return Err(ToolError::InvalidInput(format!(
                "Path must be absolute, got: {file_path}"
            )));
        }

        // Pre-validation
        if file_path.ends_with(".ipynb") {
            return Err(ToolError::InvalidInput(
                "File is a Jupyter Notebook. Use the NotebookEdit tool to edit this file.".into(),
            ));
        }
        if old_string.is_empty() && replace_all {
            return Err(ToolError::InvalidInput(
                "replace_all cannot be used with empty old_string.".into(),
            ));
        }
        if old_string.is_empty() && new_string.is_empty() {
            return Err(ToolError::InvalidInput(
                "Cannot create file with empty content.".into(),
            ));
        }
        if old_string == new_string {
            return Err(ToolError::InvalidInput(
                "old_string and new_string are identical — no change needed.".into(),
            ));
        }

        let path = Path::new(file_path);

        // New file creation branch
        if old_string.is_empty() {
            if path.exists() {
                let content = fs::read_to_string(path).unwrap_or_default();
                if !content.trim().is_empty() {
                    return Err(ToolError::InvalidInput(
                        "Cannot create new file — file already exists and has content. Use the Write tool to overwrite.".into(),
                    ));
                }
            }
            // Create parent dirs
            if let Some(parent) = path.parent() {
                if !parent.as_os_str().is_empty() {
                    let _ = fs::create_dir_all(parent);
                }
            }
            fs::write(path, new_string)
                .map_err(|e| ToolError::Execution(format!("Error writing file: {e}")))?;
            let lines: Vec<&str> = new_string.split('\n').collect();
            let short = path
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default();
            return Ok(format!(
                "OK:CREATED {short}, {} line{}",
                lines.len(),
                if lines.len() == 1 { "" } else { "s" }
            ));
        }

        // Existing file edit branch
        if !path.exists() {
            return Err(ToolError::NotFound(format!(
                "File does not exist: {file_path}"
            )));
        }

        let size = path.metadata().map(|m| m.len()).unwrap_or(0);
        if size > MAX_EDIT_FILE_SIZE {
            return Err(ToolError::InvalidInput(format!(
                "File too large to edit ({}). Maximum is {}.",
                format_size(size),
                format_size(MAX_EDIT_FILE_SIZE)
            )));
        }

        let raw_content = fs::read_to_string(path)
            .map_err(|e| ToolError::Execution(format!("Error reading file: {e}")))?;

        // Normalize line endings
        let content = raw_content.replace("\r\n", "\n");
        let search_str = old_string.replace("\r\n", "\n");

        // Fuzzy matching
        let actual_old = match string_matcher::find_actual_string(&content, &search_str) {
            Some(s) => s,
            None => {
                return Err(ToolError::NotFound(
                    "old_string not found in file. Ensure the string matches exactly, including whitespace and indentation.".into(),
                ));
            }
        };

        // Uniqueness check
        let match_count = count_matches(&content, &actual_old);
        if match_count > 1 && !replace_all {
            return Err(ToolError::InvalidInput(format!(
                "Found {match_count} matches of old_string. Either provide more context to make it unique, or set replace_all to true."
            )));
        }

        // Apply replacement
        let effective_new = if actual_old != search_str {
            string_matcher::preserve_quote_style(&search_str, &actual_old, new_string)
        } else {
            new_string.to_string()
        };

        let updated = if replace_all {
            content.replace(&actual_old, &effective_new)
        } else {
            // Replace only first occurrence
            content.replacen(&actual_old, &effective_new, 1)
        };

        fs::write(path, &updated)
            .map_err(|e| ToolError::Execution(format!("Error writing file: {e}")))?;

        // Compute diff stats using similar crate
        let diff = similar::TextDiff::from_lines(&content, &updated);
        let mut added = 0usize;
        let mut removed = 0usize;
        for change in diff.iter_all_changes() {
            match change.tag() {
                similar::ChangeTag::Insert => added += 1,
                similar::ChangeTag::Delete => removed += 1,
                similar::ChangeTag::Equal => {}
            }
        }

        let short = path
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default();
        Ok(format!(
            "OK:UPDATED {short}, {added} added, {removed} removed"
        ))
    }

    fn summarize(&self, input: &serde_json::Map<String, serde_json::Value>) -> String {
        let path = super::get_str(input, "file_path").unwrap_or("");
        let short = path.rsplit('/').next().unwrap_or(path);
        let replace_all = super::get_bool(input, "replace_all").unwrap_or(false);
        if replace_all {
            format!("Edit({short}, replace_all)\n  (\"{path}\")")
        } else {
            format!("Edit({short})\n  (\"{path}\")")
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

    #[tokio::test]
    async fn edit_basic_replace() {
        let tmp = tempfile::tempdir().unwrap();
        let file_path = tmp.path().join("test.txt");
        fs::write(&file_path, "hello world\nfoo bar").unwrap();

        let tool = EditTool::new();
        let mut input = serde_json::Map::new();
        input.insert("file_path".into(), file_path.to_string_lossy().into());
        input.insert("old_string".into(), "hello world".into());
        input.insert("new_string".into(), "hello rust".into());
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.starts_with("OK:UPDATED"));
        assert_eq!(
            fs::read_to_string(&file_path).unwrap(),
            "hello rust\nfoo bar"
        );
    }

    #[tokio::test]
    async fn edit_not_found() {
        let tmp = tempfile::tempdir().unwrap();
        let file_path = tmp.path().join("test.txt");
        fs::write(&file_path, "hello world").unwrap();

        let tool = EditTool::new();
        let mut input = serde_json::Map::new();
        input.insert("file_path".into(), file_path.to_string_lossy().into());
        input.insert("old_string".into(), "xyz".into());
        input.insert("new_string".into(), "abc".into());
        let result = tool.call(&input, &ctx()).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn edit_multiple_matches_without_replace_all() {
        let tmp = tempfile::tempdir().unwrap();
        let file_path = tmp.path().join("test.txt");
        fs::write(&file_path, "foo bar\nfoo baz").unwrap();

        let tool = EditTool::new();
        let mut input = serde_json::Map::new();
        input.insert("file_path".into(), file_path.to_string_lossy().into());
        input.insert("old_string".into(), "foo".into());
        input.insert("new_string".into(), "qux".into());
        let result = tool.call(&input, &ctx()).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn edit_replace_all() {
        let tmp = tempfile::tempdir().unwrap();
        let file_path = tmp.path().join("test.txt");
        fs::write(&file_path, "foo bar\nfoo baz").unwrap();

        let tool = EditTool::new();
        let mut input = serde_json::Map::new();
        input.insert("file_path".into(), file_path.to_string_lossy().into());
        input.insert("old_string".into(), "foo".into());
        input.insert("new_string".into(), "qux".into());
        input.insert("replace_all".into(), true.into());
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.starts_with("OK:UPDATED"));
        assert_eq!(fs::read_to_string(&file_path).unwrap(), "qux bar\nqux baz");
    }

    #[tokio::test]
    async fn edit_identical_strings() {
        let tmp = tempfile::tempdir().unwrap();
        let file_path = tmp.path().join("test.txt");
        fs::write(&file_path, "hello").unwrap();

        let tool = EditTool::new();
        let mut input = serde_json::Map::new();
        input.insert("file_path".into(), file_path.to_string_lossy().into());
        input.insert("old_string".into(), "hello".into());
        input.insert("new_string".into(), "hello".into());
        let result = tool.call(&input, &ctx()).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn edit_nonexistent_file() {
        let tool = EditTool::new();
        let mut input = serde_json::Map::new();
        input.insert("file_path".into(), "/nonexistent/file.txt".into());
        input.insert("old_string".into(), "a".into());
        input.insert("new_string".into(), "b".into());
        let result = tool.call(&input, &ctx()).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn edit_create_new_file() {
        let tmp = tempfile::tempdir().unwrap();
        let file_path = tmp.path().join("new.txt");
        let tool = EditTool::new();
        let mut input = serde_json::Map::new();
        input.insert("file_path".into(), file_path.to_string_lossy().into());
        input.insert("old_string".into(), "".into());
        input.insert("new_string".into(), "new content".into());
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.starts_with("OK:CREATED"));
        assert_eq!(fs::read_to_string(&file_path).unwrap(), "new content");
    }

    #[tokio::test]
    async fn edit_relative_path_rejected() {
        let tool = EditTool::new();
        let mut input = serde_json::Map::new();
        input.insert("file_path".into(), "relative.txt".into());
        input.insert("old_string".into(), "a".into());
        input.insert("new_string".into(), "b".into());
        let result = tool.call(&input, &ctx()).await;
        assert!(result.is_err());
    }
}
