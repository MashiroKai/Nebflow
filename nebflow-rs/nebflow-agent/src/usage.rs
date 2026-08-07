//! Token usage tracking — mirrors Scala UsageTracker.

use nebflow_core::types::TokenUsage;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

/// Tracks token usage per session.
#[derive(Default)]
pub struct UsageTracker {
    sessions: Arc<RwLock<HashMap<String, SessionUsage>>>,
}

#[derive(Debug, Clone, Default)]
pub struct SessionUsage {
    pub total_input_tokens: u64,
    pub total_output_tokens: u64,
    pub total_cache_read_tokens: u64,
    pub total_cache_write_tokens: u64,
    pub last_usage: Option<TokenUsage>,
}

impl UsageTracker {
    pub fn new() -> Self {
        Self::default()
    }

    /// Record usage for a session.
    pub async fn record(&self, session_id: &str, usage: TokenUsage) {
        let mut sessions = self.sessions.write().await;
        let entry = sessions.entry(session_id.into()).or_default();
        entry.total_input_tokens += usage.input_tokens;
        entry.total_output_tokens += usage.output_tokens;
        entry.total_cache_read_tokens += usage.cache_read_tokens.unwrap_or(0);
        entry.total_cache_write_tokens += usage.cache_write_tokens.unwrap_or(0);
        entry.last_usage = Some(usage);
    }

    /// Get usage stats for a session.
    pub async fn get(&self, session_id: &str) -> Option<SessionUsage> {
        self.sessions.read().await.get(session_id).cloned()
    }

    /// Reset usage for a session (e.g. after compaction).
    pub async fn reset(&self, session_id: &str) {
        self.sessions.write().await.remove(session_id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn record_and_get_usage() {
        let tracker = UsageTracker::new();
        tracker
            .record(
                "s1",
                TokenUsage {
                    input_tokens: 100,
                    output_tokens: 50,
                    cache_read_tokens: Some(20),
                    cache_write_tokens: None,
                },
            )
            .await;
        let usage = tracker.get("s1").await.unwrap();
        assert_eq!(usage.total_input_tokens, 100);
        assert_eq!(usage.total_output_tokens, 50);
        assert_eq!(usage.total_cache_read_tokens, 20);
    }

    #[tokio::test]
    async fn accumulate_usage() {
        let tracker = UsageTracker::new();
        tracker
            .record(
                "s1",
                TokenUsage {
                    input_tokens: 100,
                    output_tokens: 50,
                    cache_read_tokens: None,
                    cache_write_tokens: None,
                },
            )
            .await;
        tracker
            .record(
                "s1",
                TokenUsage {
                    input_tokens: 200,
                    output_tokens: 100,
                    cache_read_tokens: Some(10),
                    cache_write_tokens: None,
                },
            )
            .await;
        let usage = tracker.get("s1").await.unwrap();
        assert_eq!(usage.total_input_tokens, 300);
        assert_eq!(usage.total_output_tokens, 150);
        assert_eq!(usage.total_cache_read_tokens, 10);
    }

    #[tokio::test]
    async fn reset_usage() {
        let tracker = UsageTracker::new();
        tracker
            .record(
                "s1",
                TokenUsage {
                    input_tokens: 100,
                    output_tokens: 50,
                    cache_read_tokens: None,
                    cache_write_tokens: None,
                },
            )
            .await;
        tracker.reset("s1").await;
        assert!(tracker.get("s1").await.is_none());
    }

    #[tokio::test]
    async fn get_nonexistent_session() {
        let tracker = UsageTracker::new();
        assert!(tracker.get("nonexistent").await.is_none());
    }
}
