//! ToolResultGuard — guards against oversized tool results.
//! Mirrors `nebflow.core.tools.ToolResultGuard` from Scala.
//!
//! Two layers:
//!   1. Per-tool: if a single result exceeds the tool's maxResultSizeChars,
//!      persist to disk and replace LLM-visible content with a preview + file path.
//!   2. Per-message aggregate: if all results in one turn together exceed
//!      the budget, persist the largest ones.

use std::fs;
use std::path::Path;

const DEFAULT_MAX_RESULT_SIZE: usize = 50_000;
const MAX_TOOL_RESULTS_PER_MESSAGE: usize = 200_000;
const TOOL_RESULT_PREVIEW_SIZE: usize = 2_000;
const PERSISTED_TAG: &str = "<persisted-output>";
const PERSISTED_CLOSING_TAG: &str = "</persisted-output>";

/// Result of a tool execution.
#[derive(Debug, Clone)]
pub struct ToolExecResult {
    pub content: String,
    pub is_error: bool,
    pub frontend_content: Option<String>,
}

impl ToolExecResult {
    pub fn new(content: String, is_error: bool) -> Self {
        Self {
            content,
            is_error,
            frontend_content: None,
        }
    }
}

/// Guard a single tool result. If content exceeds the threshold,
/// persist to disk and replace LLM-visible content with a preview.
pub fn guard_result(
    tool_name: &str,
    tool_max_size: usize,
    tool_use_id: &str,
    result: ToolExecResult,
    session_id: &str,
    data_root: &Path,
) -> ToolExecResult {
    if result.is_error || result.content.is_empty() {
        return result;
    }

    // Tools with max = usize::MAX are exempt
    if tool_max_size == usize::MAX {
        return result;
    }

    let threshold = tool_max_size.min(DEFAULT_MAX_RESULT_SIZE);
    if result.content.len() <= threshold {
        return result;
    }

    persist_and_replace(tool_name, tool_use_id, result, session_id, data_root)
}

/// Guard a batch of tool results. If their aggregate content exceeds
/// the per-message budget, persist the largest ones until under budget.
pub fn guard_batch(
    results: Vec<(String, ToolExecResult)>, // (tool_name, result)
    session_id: &str,
    data_root: &Path,
) -> Vec<(String, ToolExecResult)> {
    if results.len() <= 1 {
        return results;
    }

    let total_size: usize = results.iter().map(|(_, r)| r.content.len()).sum();
    if total_size <= MAX_TOOL_RESULTS_PER_MESSAGE {
        return results;
    }

    let preview_overhead = TOOL_RESULT_PREVIEW_SIZE + 200;

    // Build (index, size) pairs, sorted largest-first, skip already-persisted
    let mut candidates: Vec<(usize, usize)> = results
        .iter()
        .enumerate()
        .filter(|(_, (_, r))| !r.content.starts_with(PERSISTED_TAG))
        .map(|(i, (_, r))| (i, r.content.len()))
        .collect();
    candidates.sort_by_key(|b| std::cmp::Reverse(b.1));

    // Select indices to persist: largest first until under budget
    let mut remaining = total_size;
    let mut selected: Vec<usize> = Vec::new();
    for (idx, size) in &candidates {
        if remaining > MAX_TOOL_RESULTS_PER_MESSAGE {
            remaining = remaining.saturating_sub(*size - preview_overhead);
            selected.push(*idx);
        }
    }

    if selected.is_empty() {
        return results;
    }

    results
        .into_iter()
        .enumerate()
        .map(|(i, (name, result))| {
            if selected.contains(&i) {
                let tool_use_id = format!("batch_{i}");
                let name_clone = name.clone();
                (
                    name,
                    persist_and_replace(&name_clone, &tool_use_id, result, session_id, data_root),
                )
            } else {
                (name, result)
            }
        })
        .collect()
}

fn persist_and_replace(
    _tool_name: &str,
    tool_use_id: &str,
    result: ToolExecResult,
    session_id: &str,
    data_root: &Path,
) -> ToolExecResult {
    let dir = data_root.join("tool-results").join(session_id);
    if fs::create_dir_all(&dir).is_err() {
        return result;
    }

    let path = dir.join(format!("{tool_use_id}.txt"));
    let content = &result.content;

    if !path.exists() {
        let _ = fs::write(&path, content);
    }

    let size_str = format_size(content.len());
    let preview: String = content.chars().take(TOOL_RESULT_PREVIEW_SIZE).collect();
    let has_more = content.len() > TOOL_RESULT_PREVIEW_SIZE;

    let mut preview_content = String::new();
    preview_content.push_str(PERSISTED_TAG);
    preview_content.push('\n');
    preview_content.push_str(&format!(
        "Output too large ({size_str}). Full output saved to: {}\n\n",
        path.display()
    ));
    preview_content.push_str(&format!(
        "Preview (first {TOOL_RESULT_PREVIEW_SIZE} chars):\n{preview}"
    ));
    preview_content.push('\n');
    if has_more {
        preview_content.push_str("...\n");
    }
    preview_content.push_str(PERSISTED_CLOSING_TAG);

    ToolExecResult {
        content: preview_content,
        is_error: result.is_error,
        frontend_content: Some(content.clone()),
    }
}

fn format_size(chars: usize) -> String {
    if chars < 1024 {
        format!("{chars} chars")
    } else if chars < 1024 * 1024 {
        format!("{:.1} KB", chars as f64 / 1024.0)
    } else {
        format!("{:.1} MB", chars as f64 / (1024.0 * 1024.0))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn guard_small_result_passthrough() {
        let result = ToolExecResult::new("hello".to_string(), false);
        let guarded = guard_result(
            "Read",
            50_000,
            "id1",
            result,
            "s1",
            std::path::Path::new("/tmp"),
        );
        assert_eq!(guarded.content, "hello");
    }

    #[test]
    fn guard_error_passthrough() {
        let result = ToolExecResult::new("error message".to_string(), true);
        let guarded = guard_result(
            "Bash",
            50_000,
            "id1",
            result,
            "s1",
            std::path::Path::new("/tmp"),
        );
        assert_eq!(guarded.content, "error message");
    }

    #[test]
    fn guard_large_result_persisted() {
        let tmp = tempfile::tempdir().unwrap();
        let large_content = "x".repeat(60_000);
        let result = ToolExecResult::new(large_content.clone(), false);
        let guarded = guard_result("Bash", 50_000, "id1", result, "s1", tmp.path());
        assert!(guarded.content.starts_with(PERSISTED_TAG));
        assert!(guarded.content.contains("Output too large"));
        assert_eq!(guarded.frontend_content, Some(large_content));
    }

    #[test]
    fn guard_exempt_tool() {
        let result = ToolExecResult::new("x".repeat(60_000), false);
        let guarded = guard_result(
            "Read",
            usize::MAX,
            "id1",
            result,
            "s1",
            std::path::Path::new("/tmp"),
        );
        assert!(!guarded.content.starts_with(PERSISTED_TAG));
    }

    #[test]
    fn guard_batch_small_passthrough() {
        let results = vec![
            (
                "Read".to_string(),
                ToolExecResult::new("small1".into(), false),
            ),
            (
                "Grep".to_string(),
                ToolExecResult::new("small2".into(), false),
            ),
        ];
        let guarded = guard_batch(results, "s1", std::path::Path::new("/tmp"));
        assert_eq!(guarded.len(), 2);
        assert!(!guarded[0].1.content.starts_with(PERSISTED_TAG));
    }

    #[test]
    fn format_size_basic() {
        assert_eq!(format_size(500), "500 chars");
        assert_eq!(format_size(2048), "2.0 KB");
        assert_eq!(format_size(1_048_576), "1.0 MB");
    }
}
