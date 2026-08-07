//! Read tool — reads files from the local filesystem.
//!
//! Mirrors the Scala ReadTool. Supports text files (cat -n style output),
//! image files (returns metadata description), filtering, offset/limit.

use async_trait::async_trait;
use serde_json::{Map, Value};
use std::path::Path;

use crate::types::{ToolContext, ToolError};
use crate::Tool;

const MAX_LINE_COUNT: usize = 2000;
const MAX_FILE_BYTES: u64 = 512 * 1024; // 512KB
const MAX_IMAGE_BYTES: u64 = 10 * 1024 * 1024; // 10MB

/// Supported image extensions → MIME type.
const IMAGE_EXTENSIONS: &[(&str, &str)] = &[
    (".png", "image/png"),
    (".jpg", "image/jpeg"),
    (".jpeg", "image/jpeg"),
    (".gif", "image/gif"),
    (".webp", "image/webp"),
    (".bmp", "image/bmp"),
];

fn image_mime_type(file_name: &str) -> Option<&'static str> {
    let lower = file_name.to_lowercase();
    IMAGE_EXTENSIONS
        .iter()
        .find(|(ext, _)| lower.ends_with(ext))
        .map(|(_, mime)| *mime)
}

pub struct ReadTool {
    schema: Map<String, Value>,
}

impl ReadTool {
    pub fn new() -> Self {
        let schema = serde_json::json!({
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "The absolute path to the file to read"
                },
                "offset": {
                    "type": "number",
                    "description": "The line number to start reading from (1-based)"
                },
                "limit": {
                    "type": "number",
                    "description": "The number of lines to read"
                },
                "filter": {
                    "type": "string",
                    "description": "Regex pattern to filter lines. Only matching lines are returned (with original line numbers)."
                }
            },
            "required": ["file_path"]
        });
        Self {
            schema: schema.as_object().unwrap().clone(),
        }
    }
}

impl Default for ReadTool {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Tool for ReadTool {
    fn name(&self) -> &str {
        "Read"
    }

    fn description(&self) -> &str {
        "Reads a file from the local filesystem. Reading a non-existent file returns an error, which is fine.\n\nParameters:\n- file_path (required): Absolute path to the file.\n- offset: Line number to start reading from (1-based). Defaults to 1.\n- limit: Number of lines to read. Defaults to 2000.\n- filter: Regex pattern to extract matching lines from large files."
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

        if file_path_str.is_empty() {
            return Err(ToolError::InvalidInput("file_path is required".into()));
        }

        let file_path = Path::new(file_path_str);
        let file_name = file_path
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| file_path_str.to_string());

        // Check if this is an image file
        if let Some(media_type) = image_mime_type(&file_name) {
            if !file_path.exists() {
                return Err(ToolError::NotFound(format!(
                    "File does not exist: {}",
                    file_path_str
                )));
            }
            if file_path.is_dir() {
                return Err(ToolError::InvalidInput(format!(
                    "Path is a directory, not a file: {}",
                    file_path_str
                )));
            }
            let size = std::fs::metadata(file_path).map(|m| m.len()).unwrap_or(0);
            if size > MAX_IMAGE_BYTES {
                let size_mb = size as f64 / 1024.0 / 1024.0;
                return Err(ToolError::InvalidInput(format!(
                    "Image too large: {} ({:.1}MB, limit {}MB)",
                    file_name,
                    size_mb,
                    MAX_IMAGE_BYTES / 1024 / 1024
                )));
            }
            let size_kb = size as f64 / 1024.0;
            return Ok(format!(
                "[image: {} | {} | {:.0}KB]",
                file_name, media_type, size_kb
            ));
        }

        // Text file (including SVG)
        read_text_file(input, file_path, file_path_str)
    }

    fn summarize(&self, input: &Map<String, Value>) -> String {
        let path = input
            .get("file_path")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        let short = path.rsplit('/').next().unwrap_or(path);
        let offset = input.get("offset").and_then(|v| v.as_u64());
        let limit = input.get("limit").and_then(|v| v.as_u64());
        let filter = input.get("filter").and_then(|v| v.as_str());
        let mut params = Vec::new();
        if let Some(o) = offset {
            params.push(format!("offset={}", o));
        }
        if let Some(l) = limit {
            params.push(format!("limit={}", l));
        }
        if let Some(f) = filter {
            params.push(format!("filter={}", f));
        }
        let param_str = if params.is_empty() {
            String::new()
        } else {
            format!(", {}", params.join(", "))
        };
        format!("Read({}{})\n  (\"{}\")", short, param_str, path)
    }

    fn summarize_result(&self, _input: &Map<String, Value>, result: &str) -> String {
        if result.starts_with("File does not exist") || result.starts_with("Error") {
            result.to_string()
        } else if result.starts_with("[image:") {
            result.lines().next().unwrap_or(result).to_string()
        } else if result.contains("showing") {
            // Extract "showing X of Y lines"
            if let Some(start) = result.find("showing ") {
                let rest = &result[start + 8..];
                if let Some(end) = rest.find(" lines") {
                    return format!("{} lines", &rest[..end]);
                }
            }
            format!("{} lines", result.lines().count())
        } else {
            format!("{} lines", result.lines().count())
        }
    }

    fn max_result_size(&self) -> usize {
        usize::MAX // Read controls its own output via limit parameter
    }
}

fn read_text_file(
    input: &Map<String, Value>,
    file_path: &Path,
    file_path_str: &str,
) -> Result<String, ToolError> {
    if !file_path.exists() {
        return Err(ToolError::NotFound(format!(
            "File does not exist: {}",
            file_path_str
        )));
    }
    if file_path.is_dir() {
        return Err(ToolError::InvalidInput(format!(
            "Path is a directory, not a file: {}. Use Glob to list directory contents.",
            file_path_str
        )));
    }
    let size = std::fs::metadata(file_path).map(|m| m.len()).unwrap_or(0);
    if size > MAX_FILE_BYTES {
        let size_mb = size as f64 / 1024.0 / 1024.0;
        let short_name = file_path
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default();
        return Err(ToolError::InvalidInput(format!(
            "File too large to read safely: {} ({:.1}MB, limit {}MB). \
             Use offset/limit to read specific sections, or Bash with head/tail.",
            short_name,
            size_mb,
            MAX_FILE_BYTES / 1024 / 1024
        )));
    }

    let content = std::fs::read_to_string(file_path)
        .map_err(|e| ToolError::Execution(format!("Error reading file: {}", e)))?;

    let all_lines: Vec<&str> = content.split('\n').collect();
    let filter_opt = input
        .get("filter")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty());

    // Apply filter if provided
    let (working_lines, working_indices, filter_info) = match filter_opt {
        Some(pattern) => {
            let re = regex_lite(pattern);
            let matched: Vec<(usize, &&str)> = all_lines
                .iter()
                .enumerate()
                .filter(|(_, line)| re.is_match(line))
                .collect();
            let lines: Vec<&str> = matched.iter().map(|(_, l)| **l).collect();
            let indices: Vec<usize> = matched.iter().map(|(i, _)| *i).collect();
            (
                lines,
                indices,
                format!(
                    ", filter: \"{}\" — {} match(es) in {} lines",
                    pattern,
                    matched.len(),
                    all_lines.len()
                ),
            )
        }
        None => {
            let indices: Vec<usize> = (0..all_lines.len()).collect();
            (all_lines.clone(), indices, String::new())
        }
    };

    let start = input
        .get("offset")
        .and_then(|v| v.as_u64())
        .map(|o| (o as usize).saturating_sub(1))
        .unwrap_or(0);
    let end = input
        .get("limit")
        .and_then(|v| v.as_u64())
        .map(|l| start + l as usize)
        .unwrap_or_else(|| std::cmp::min(working_lines.len(), start + MAX_LINE_COUNT));

    let selected: Vec<&str> = working_lines[start..end.min(working_lines.len())].to_vec();
    let selected_indices: Vec<usize> =
        working_indices[start..end.min(working_indices.len())].to_vec();

    let result: String = selected
        .iter()
        .zip(selected_indices.iter())
        .map(|(line, original_idx)| format!("{}\t{}", original_idx + 1, line))
        .collect::<Vec<String>>()
        .join("\n");

    let total_lines = working_lines.len();
    let showed_lines = selected.len();
    let suffix = if filter_opt.is_some() && showed_lines < working_lines.len() {
        format!(
            "\n\n(showing {} of {} matched lines{})",
            showed_lines, total_lines, filter_info
        )
    } else if filter_opt.is_some() {
        format!("\n\n({} matched lines{})", total_lines, filter_info)
    } else if showed_lines < all_lines.len() {
        format!(
            "\n\n(showing {} of {} lines)",
            showed_lines,
            all_lines.len()
        )
    } else {
        String::new()
    };

    Ok(format!("{}{}", result, suffix))
}

/// Minimal regex-like matcher using simple substring/pattern matching.
/// For the Rust implementation we use a simplified approach:
/// - If the pattern contains no regex metacharacters, treat it as a literal substring.
/// - Otherwise fall back to a basic glob-to-regex conversion.
fn regex_lite(pattern: &str) -> SimpleRegex {
    SimpleRegex {
        pattern: pattern.to_string(),
    }
}

struct SimpleRegex {
    pattern: String,
}

impl SimpleRegex {
    fn is_match(&self, text: &str) -> bool {
        // Simple approach: try to use the regex crate if available,
        // otherwise fall back to substring matching
        if self.pattern.contains(".*") || self.pattern.contains('[') || self.pattern.contains('(') {
            // Likely a real regex — try to compile it
            if let Ok(re) = regex::Regex::new(&self.pattern) {
                return re.is_match(text);
            }
        }
        // Fall back to substring match for simple patterns
        text.contains(&self.pattern)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn temp_file(content: &str) -> (tempfile::NamedTempFile, String) {
        let mut f = tempfile::NamedTempFile::new().unwrap();
        f.write_all(content.as_bytes()).unwrap();
        let path = f.path().to_string_lossy().to_string();
        (f, path)
    }

    fn make_input(file_path: &str) -> Map<String, Value> {
        let mut m = Map::new();
        m.insert("file_path".into(), Value::String(file_path.into()));
        m
    }

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
    async fn read_text_file_basic() {
        let (_f, path) = temp_file("hello\nworld\nfoo\n");
        let tool = ReadTool::new();
        let result = tool.call(&make_input(&path), &ctx()).await.unwrap();
        assert!(result.contains("1\thello"));
        assert!(result.contains("2\tworld"));
        assert!(result.contains("3\tfoo"));
    }

    #[tokio::test]
    async fn read_nonexistent_file() {
        let tool = ReadTool::new();
        let result = tool
            .call(&make_input("/nonexistent/file.txt"), &ctx())
            .await;
        assert!(result.is_err());
        let err = result.unwrap_err();
        assert!(matches!(err, ToolError::NotFound(_)));
    }

    #[tokio::test]
    async fn read_with_offset_and_limit() {
        let (_f, path) = temp_file("line1\nline2\nline3\nline4\nline5\n");
        let tool = ReadTool::new();
        let mut input = make_input(&path);
        input.insert("offset".into(), Value::Number(2.into()));
        input.insert("limit".into(), Value::Number(2.into()));
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.contains("2\tline2"));
        assert!(result.contains("3\tline3"));
        assert!(!result.contains("line1"));
        assert!(!result.contains("line4"));
    }

    #[tokio::test]
    async fn read_with_filter() {
        let (_f, path) = temp_file("apple\nbanana\ncherry\napricot\n");
        let tool = ReadTool::new();
        let mut input = make_input(&path);
        input.insert("filter".into(), Value::String("ap".into()));
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.contains("apple"));
        assert!(result.contains("apricot"));
        assert!(!result.contains("banana"));
        assert!(result.contains("matched lines"));
    }

    #[tokio::test]
    async fn read_directory_error() {
        let tool = ReadTool::new();
        let result = tool.call(&make_input("/tmp"), &ctx()).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn image_mime_type_detection() {
        assert_eq!(image_mime_type("photo.png"), Some("image/png"));
        assert_eq!(image_mime_type("photo.JPG"), Some("image/jpeg"));
        assert_eq!(image_mime_type("photo.txt"), None);
    }

    #[tokio::test]
    async fn summarize_formats() {
        let tool = ReadTool::new();
        let mut input = Map::new();
        input.insert("file_path".into(), Value::String("/tmp/test.rs".into()));
        let s = tool.summarize(&input);
        assert!(s.contains("Read(test.rs)"));
    }
}
