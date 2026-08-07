//! Context compaction — FastMicroCompact and FullCompact.
//! Mirrors Scala core/compact/ module.

use nebflow_core::types::{ContentBlock, Message, MessageContent, MessageRole};

/// Placeholder text for compacted tool results.
pub const PLACEHOLDER: &str = "[Output removed to free context space]";

/// Tools whose results can be compacted.
const COMPACTABLE_TOOLS: &[&str] = &[
    "Read",
    "Bash",
    "Grep",
    "Glob",
    "WebSearch",
    "WebFetch",
    "Curl",
    "Edit",
    "Write",
];

/// Configuration for compaction.
#[derive(Debug, Clone)]
pub struct CompactConfig {
    pub buffer_tokens: usize,
    pub buffer_ratio: f64,
    pub circuit_breaker_max: u32,
    pub compaction_retry_delay_ms: u64,
    pub emergency_keep_messages: usize,
    pub micro_cache_ttl_minutes: u64,
    pub micro_keep_recent: usize,
}

impl Default for CompactConfig {
    fn default() -> Self {
        Self {
            buffer_tokens: 13_000,
            buffer_ratio: 0.10,
            circuit_breaker_max: 3,
            compaction_retry_delay_ms: 30_000,
            emergency_keep_messages: 20,
            micro_cache_ttl_minutes: 120,
            micro_keep_recent: 5,
        }
    }
}

impl CompactConfig {
    /// Buffer that scales with context window.
    pub fn buffer_for_window(&self, context_window: usize) -> usize {
        ((self.buffer_tokens as f64) * 1.0).max(context_window as f64 * self.buffer_ratio) as usize
    }

    /// Compaction trigger ratio.
    pub fn compaction_trigger_ratio(&self, context_window: usize) -> f64 {
        let threshold = context_window.saturating_sub(self.buffer_for_window(context_window));
        threshold as f64 / context_window as f64
    }

    /// Exponential backoff delay.
    pub fn backoff_ms(&self, failures: u32) -> u64 {
        if failures == 0 {
            0
        } else {
            self.compaction_retry_delay_ms * (1u64 << (failures - 1).min(10))
        }
    }
}

/// Fast micro compaction — rule-based, no LLM call.
/// Replaces old compactable tool_result content with a placeholder,
/// preserving the most recent N results.
pub fn fast_micro_compact(messages: &[Message], keep_recent: usize) -> Option<Vec<Message>> {
    if messages.is_empty() {
        return None;
    }

    // Collect compactable tool_use IDs in order of appearance
    let all_tool_use_ids: Vec<String> = messages
        .iter()
        .flat_map(|m| match &m.content {
            MessageContent::Blocks(blocks) if m.role == MessageRole::Assistant => blocks
                .iter()
                .filter_map(|b| match b {
                    ContentBlock::ToolUse { id, name, .. }
                        if COMPACTABLE_TOOLS.contains(&name.as_str()) =>
                    {
                        Some(id.clone())
                    }
                    _ => None,
                })
                .collect::<Vec<_>>(),
            _ => vec![],
        })
        .collect();

    if all_tool_use_ids.len() <= keep_recent {
        return None;
    }

    let keep_set: std::collections::HashSet<&String> =
        all_tool_use_ids.iter().rev().take(keep_recent).collect();
    let clear_set: std::collections::HashSet<String> = all_tool_use_ids
        .iter()
        .filter(|id| !keep_set.contains(*id))
        .cloned()
        .collect();

    if clear_set.is_empty() {
        return None;
    }

    let result: Vec<Message> = messages
        .iter()
        .map(|msg| match &msg.content {
            MessageContent::Blocks(blocks) => {
                let new_blocks: Vec<ContentBlock> = blocks
                    .iter()
                    .map(|b| match b {
                        ContentBlock::ToolResult {
                            tool_use_id,
                            content,
                            is_error,
                        } if clear_set.contains(tool_use_id) && content != PLACEHOLDER => {
                            ContentBlock::ToolResult {
                                tool_use_id: tool_use_id.clone(),
                                content: PLACEHOLDER.into(),
                                is_error: *is_error,
                            }
                        }
                        other => other.clone(),
                    })
                    .collect();
                Message {
                    role: msg.role,
                    content: MessageContent::Blocks(new_blocks),
                    timestamp: msg.timestamp,
                }
            }
            _ => msg.clone(),
        })
        .collect();

    Some(result)
}

/// Emergency truncation — keep only the last N messages.
pub fn emergency_clean(messages: &[Message], keep: usize) -> (Vec<Message>, String) {
    if messages.len() <= keep {
        return (messages.to_vec(), "no change".into());
    }
    let kept = messages.iter().rev().take(keep).rev().cloned().collect();
    let desc = format!(
        "Emergency truncation: kept last {} of {} messages",
        keep,
        messages.len()
    );
    (kept, desc)
}

/// Estimate token count for a message (rough: 1 token ≈ 4 chars).
pub fn estimate_tokens(messages: &[Message]) -> usize {
    messages
        .iter()
        .map(|m| match &m.content {
            MessageContent::Text(t) => t.len() / 4,
            MessageContent::Blocks(blocks) => blocks
                .iter()
                .map(|b| match b {
                    ContentBlock::Text { text } => text.len() / 4,
                    ContentBlock::ToolResult { content, .. } => content.len() / 4,
                    ContentBlock::ToolUse { input, .. } => serde_json::to_string(input)
                        .map(|s| s.len() / 4)
                        .unwrap_or(0),
                    ContentBlock::Thinking { thinking, .. } => thinking.len() / 4,
                    _ => 100,
                })
                .sum(),
        })
        .sum()
}

/// Check if compaction should be triggered based on token count.
pub fn should_compact(messages: &[Message], context_window: usize, config: &CompactConfig) -> bool {
    let tokens = estimate_tokens(messages);
    let threshold =
        (context_window as f64 * config.compaction_trigger_ratio(context_window)) as usize;
    tokens > threshold
}

#[cfg(test)]
mod tests {
    use super::*;

    fn msg(role: MessageRole, text: &str) -> Message {
        Message {
            role,
            content: MessageContent::Text(text.into()),
            timestamp: 0,
        }
    }

    fn tool_msg(role: MessageRole, blocks: Vec<ContentBlock>) -> Message {
        Message {
            role,
            content: MessageContent::Blocks(blocks),
            timestamp: 0,
        }
    }

    #[test]
    fn fast_micro_compact_replaces_old_results() {
        let messages = vec![
            msg(MessageRole::User, "do task"),
            tool_msg(
                MessageRole::Assistant,
                vec![ContentBlock::ToolUse {
                    id: "t1".into(),
                    name: "Read".into(),
                    input: Default::default(),
                }],
            ),
            tool_msg(
                MessageRole::User,
                vec![ContentBlock::ToolResult {
                    tool_use_id: "t1".into(),
                    content: "file content line 1\nline 2".into(),
                    is_error: None,
                }],
            ),
            msg(MessageRole::Assistant, "result 1"),
            tool_msg(
                MessageRole::Assistant,
                vec![ContentBlock::ToolUse {
                    id: "t2".into(),
                    name: "Read".into(),
                    input: Default::default(),
                }],
            ),
            tool_msg(
                MessageRole::User,
                vec![ContentBlock::ToolResult {
                    tool_use_id: "t2".into(),
                    content: "file content 2".into(),
                    is_error: None,
                }],
            ),
        ];

        let result = fast_micro_compact(&messages, 1);
        assert!(result.is_some());
        let compacted = result.unwrap();
        // t1 should be placeholdered, t2 should be kept
        match &compacted[2].content {
            MessageContent::Blocks(blocks) => {
                if let ContentBlock::ToolResult { content, .. } = &blocks[0] {
                    assert_eq!(content, PLACEHOLDER);
                }
            }
            _ => panic!("expected blocks"),
        }
        match &compacted[4].content {
            MessageContent::Blocks(blocks) => {
                if let ContentBlock::ToolResult { content, .. } = &blocks[0] {
                    assert_eq!(content, "file content 2");
                }
            }
            _ => panic!("expected blocks"),
        }
    }

    #[test]
    fn fast_micro_compact_skips_when_few_results() {
        let messages = vec![
            msg(MessageRole::User, "do task"),
            tool_msg(
                MessageRole::Assistant,
                vec![ContentBlock::ToolUse {
                    id: "t1".into(),
                    name: "Read".into(),
                    input: Default::default(),
                }],
            ),
            tool_msg(
                MessageRole::User,
                vec![ContentBlock::ToolResult {
                    tool_use_id: "t1".into(),
                    content: "content".into(),
                    is_error: None,
                }],
            ),
        ];
        let result = fast_micro_compact(&messages, 5);
        assert!(result.is_none());
    }

    #[test]
    fn fast_micro_compact_empty_messages() {
        assert!(fast_micro_compact(&[], 5).is_none());
    }

    #[test]
    fn fast_micro_compact_already_compacted() {
        let messages = vec![tool_msg(
            MessageRole::User,
            vec![ContentBlock::ToolResult {
                tool_use_id: "t1".into(),
                content: PLACEHOLDER.into(),
                is_error: None,
            }],
        )];
        // Already placeholdered — should not double-compact
        let result = fast_micro_compact(&messages, 0);
        // t1 is in clear_set but content already == PLACEHOLDER, so clear_set filtering
        // should yield no changes → None
        assert!(result.is_none());
    }

    #[test]
    fn emergency_clean_truncates() {
        let messages: Vec<Message> = (0..30)
            .map(|i| msg(MessageRole::User, &format!("msg {i}")))
            .collect();
        let (kept, desc) = emergency_clean(&messages, 10);
        assert_eq!(kept.len(), 10);
        assert!(desc.contains("Emergency"));
        // Should keep last 10
        match &kept[0].content {
            MessageContent::Text(t) => assert_eq!(t, "msg 20"),
            _ => panic!("expected text"),
        }
    }

    #[test]
    fn emergency_clean_no_truncation_needed() {
        let messages = vec![msg(MessageRole::User, "hi")];
        let (kept, _) = emergency_clean(&messages, 10);
        assert_eq!(kept.len(), 1);
    }

    #[test]
    fn estimate_tokens_basic() {
        let messages = vec![msg(MessageRole::User, "hello world")]; // 11 chars ≈ 2 tokens
        let tokens = estimate_tokens(&messages);
        assert!(tokens > 0);
    }

    #[test]
    fn compact_config_buffer_for_window() {
        let config = CompactConfig::default();
        // For 200k window: 10% = 20000 > 13000
        assert_eq!(config.buffer_for_window(200_000), 20000);
        // For 50k window: 10% = 5000 < 13000, use 13000
        assert_eq!(config.buffer_for_window(50_000), 13000);
    }

    #[test]
    fn compact_config_trigger_ratio() {
        let config = CompactConfig::default();
        let ratio = config.compaction_trigger_ratio(200_000);
        // (200000 - 20000) / 200000 = 0.9
        assert!((ratio - 0.9).abs() < 0.01);
    }

    #[test]
    fn compact_config_backoff() {
        let config = CompactConfig::default();
        assert_eq!(config.backoff_ms(0), 0);
        assert_eq!(config.backoff_ms(1), 30000);
        assert_eq!(config.backoff_ms(2), 60000);
        assert_eq!(config.backoff_ms(3), 120000);
    }

    #[test]
    fn should_compact_below_threshold() {
        let config = CompactConfig::default();
        let messages = vec![msg(MessageRole::User, "short")];
        assert!(!should_compact(&messages, 200_000, &config));
    }
}
