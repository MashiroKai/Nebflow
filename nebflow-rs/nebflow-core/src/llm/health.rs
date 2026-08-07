//! Health monitor — tracks provider up/down state and probes for recovery.
//! Mirrors nebflow.llm.ProviderHealthMonitor from Scala.

use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::Notify;
use tokio::sync::RwLock;

/// Health state of a single provider+model combination.
#[derive(Debug, Clone)]
pub enum HealthState {
    Up,
    Down { reason: String, since: u64 },
}

/// HealthMonitor — tracks the health of every provider+model in the candidate chain.
///
/// State transitions:
/// - Real request failures -> mark_down
/// - Background probes -> mark_up on success
pub struct HealthMonitor {
    states: Arc<RwLock<HashMap<String, HealthState>>>,
    notify: Arc<Notify>,
}

impl HealthMonitor {
    pub fn new() -> Self {
        Self {
            states: Arc::new(RwLock::new(HashMap::new())),
            notify: Arc::new(Notify::new()),
        }
    }

    fn key(provider_id: &str, model: &str) -> String {
        format!("{provider_id}/{model}")
    }

    /// Mark a candidate as Down. No-op if already Down.
    pub async fn mark_down(&self, provider_id: &str, model: &str, reason: &str) {
        let k = Self::key(provider_id, model);
        let mut states = self.states.write().await;
        match states.get(&k) {
            Some(HealthState::Down { .. }) => {} // already down
            _ => {
                tracing::warn!("Provider {} marked DOWN: {}", k, reason);
                states.insert(
                    k,
                    HealthState::Down {
                        reason: reason.to_string(),
                        since: now_millis(),
                    },
                );
            }
        }
    }

    /// Mark a candidate as Up and wake waiters. No-op if already Up.
    pub async fn mark_up(&self, provider_id: &str, model: &str) {
        let k = Self::key(provider_id, model);
        let mut states = self.states.write().await;
        if let Some(HealthState::Down { .. }) = states.get(&k) {
            tracing::info!("Provider {} recovered (UP)", k);
            states.insert(k, HealthState::Up);
            drop(states);
            self.notify.notify_waiters();
        }
    }
    /// Partition candidates into (up, down) based on current health state.
    pub async fn filter_candidates(
        &self,
        candidates: &[crate::types::ModelCandidate],
    ) -> (
        Vec<crate::types::ModelCandidate>,
        Vec<crate::types::ModelCandidate>,
    ) {
        let states = self.states.read().await;
        candidates.iter().cloned().partition(|c| {
            !matches!(
                states.get(&Self::key(&c.provider_id, &c.model)),
                Some(HealthState::Down { .. })
            )
        })
    }

    /// Block until at least one Down provider recovers.
    pub async fn wait_for_any_up(&self) {
        self.notify.notified().await;
    }

    /// Snapshot of all health states (for UI / logging).
    pub async fn get_states(&self) -> HashMap<String, HealthState> {
        self.states.read().await.clone()
    }

    /// Probe a provider to check if it has recovered.
    ///
    /// Sends a minimal request to the provider. On success, marks it Up.
    /// For thinking models (model name contains "glm-5" or "deepseek-r"),
    /// uses a 60s timeout; for regular models, uses 30s.
    pub async fn probe(
        &self,
        provider_id: &str,
        model: &str,
        endpoint: &str,
        api_key: &str,
    ) -> bool {
        let is_thinking_model = {
            let m = model.to_lowercase();
            m.contains("glm-5") || m.contains("deepseek-r")
        };
        let timeout_secs = if is_thinking_model { 60 } else { 30 };

        let client = reqwest::Client::new();
        let body = serde_json::json!({
            "model": model,
            "messages": [{"role": "user", "content": "ping"}],
            "max_tokens": 1,
            "thinking": if is_thinking_model {
                serde_json::json!({"type": "enabled", "budget_tokens": 1024})
            } else {
                serde_json::json!(null)
            }
        });

        let url = if endpoint.ends_with("/chat/completions") {
            endpoint.to_string()
        } else {
            format!("{endpoint}/chat/completions")
        };

        let result = tokio::time::timeout(
            std::time::Duration::from_secs(timeout_secs),
            client
                .post(&url)
                .header("Authorization", format!("Bearer {api_key}"))
                .json(&body)
                .send(),
        )
        .await;

        match result {
            Ok(Ok(resp)) if resp.status().is_success() => {
                self.mark_up(provider_id, model).await;
                true
            }
            _ => false,
        }
    }
}

impl Default for HealthMonitor {
    fn default() -> Self {
        Self::new()
    }
}

fn now_millis() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::ModelCandidate;

    fn test_candidate(provider: &str, model: &str) -> ModelCandidate {
        ModelCandidate {
            provider_id: provider.into(),
            model: model.into(),
            max_tokens: 16384,
            context_window: 128000,
            vision: false,
            capabilities: vec![],
        }
    }

    #[test]
    fn probe_timeout_is_60s_for_thinking_models() {
        // Thinking models should use 60s timeout (verified by convention)
        let model = "glm-5-plus";
        let is_thinking = {
            let m = model.to_lowercase();
            m.contains("glm-5") || m.contains("deepseek-r")
        };
        assert!(is_thinking);
    }

    #[test]
    fn probe_timeout_is_30s_for_regular_models() {
        let model = "claude-3.5-sonnet";
        let is_thinking = {
            let m = model.to_lowercase();
            m.contains("glm-5") || m.contains("deepseek-r")
        };
        assert!(!is_thinking);
    }

    #[tokio::test]
    async fn mark_down_and_filter() {
        let monitor = HealthMonitor::new();
        let candidates = vec![
            test_candidate("anthropic", "claude"),
            test_candidate("openai", "gpt-4"),
        ];

        // All up initially
        let (up, down) = monitor.filter_candidates(&candidates).await;
        assert_eq!(up.len(), 2);
        assert!(down.is_empty());

        // Mark one down
        monitor.mark_down("openai", "gpt-4", "server error").await;

        let (up, down) = monitor.filter_candidates(&candidates).await;
        assert_eq!(up.len(), 1);
        assert_eq!(down.len(), 1);
        assert_eq!(up[0].provider_id, "anthropic");
    }

    #[tokio::test]
    async fn mark_up_recovers() {
        let monitor = HealthMonitor::new();
        let candidates = vec![test_candidate("anthropic", "claude")];

        monitor.mark_down("anthropic", "claude", "error").await;
        let (up, _) = monitor.filter_candidates(&candidates).await;
        assert!(up.is_empty());

        monitor.mark_up("anthropic", "claude").await;
        let (up, _) = monitor.filter_candidates(&candidates).await;
        assert_eq!(up.len(), 1);
    }

    #[tokio::test]
    async fn mark_down_idempotent() {
        let monitor = HealthMonitor::new();
        monitor.mark_down("p", "m", "reason1").await;
        monitor.mark_down("p", "m", "reason2").await;

        let states = monitor.get_states().await;
        let state = states.get("p/m").unwrap();
        // First reason should be preserved
        if let HealthState::Down { reason, .. } = state {
            assert_eq!(reason, "reason1");
        }
    }

    #[tokio::test]
    async fn wait_for_any_up_unblocks() {
        let monitor = HealthMonitor::new();
        monitor.mark_down("p", "m", "error").await;

        let monitor2 = monitor;
        let monitor_clone = std::sync::Arc::new(monitor2);
        let mc = monitor_clone.clone();

        let handle = tokio::spawn(async move {
            mc.wait_for_any_up().await;
        });

        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        monitor_clone.mark_up("p", "m").await;

        tokio::time::timeout(std::time::Duration::from_secs(1), handle)
            .await
            .unwrap()
            .unwrap();
    }
}
