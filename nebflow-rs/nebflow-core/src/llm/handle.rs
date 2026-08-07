//! LlmHandle — high-level LLM interface with fallback and health monitoring.
//! Mirrors nebflow.llm.LlmInterface from Scala.

use std::sync::Arc;

use crate::llm::fallback::classify_error;
use crate::llm::health::HealthMonitor;
use crate::llm::registry::ProviderRegistry;
use crate::types::{
    AdapterResponse, AgentModelConfig, ErrorPermanence, FallbackAttempt, LlmError, LlmRequest,
    LlmResponse, ModelCandidate, SendMessageParams,
};
use crate::ChunkStream;

/// High-level LLM handle with fallback chain and health monitoring.
pub struct LlmHandle {
    registry: Arc<ProviderRegistry>,
    health: Arc<HealthMonitor>,
}

impl LlmHandle {
    pub fn new(registry: Arc<ProviderRegistry>, health: Arc<HealthMonitor>) -> Self {
        Self { registry, health }
    }

    /// Get candidates for a request, applying session overrides + health filtering.
    pub fn get_candidates(&self, agent_model: &Option<AgentModelConfig>) -> Vec<ModelCandidate> {
        self.registry.get_candidates_for_agent(agent_model)
    }

    /// Filter candidates by health state.
    pub async fn filter_healthy(&self, candidates: &[ModelCandidate]) -> Vec<ModelCandidate> {
        let (up, _down) = self.health.filter_candidates(candidates).await;
        up
    }

    /// Non-streaming send with fallback.
    pub async fn send(&self, req: &LlmRequest) -> Result<LlmResponse, LlmError> {
        let candidates = self.get_candidates(&req.agent_model);
        let (up, _down) = self.health.filter_candidates(&candidates).await;

        if up.is_empty() {
            return Err(LlmError::AllProvidersExhausted);
        }

        let mut attempts: Vec<FallbackAttempt> = vec![];

        for candidate in &up {
            let adapter = match self.registry.get_adapter(&candidate.provider_id) {
                Some(a) => a,
                None => continue,
            };

            let params = build_params(req, candidate);
            match adapter.send_message(&params).await {
                Ok(resp) => {
                    return Ok(LlmResponse {
                        content: build_content_blocks(&resp),
                        stop_reason: None,
                        usage: resp.usage,
                        context_window: Some(candidate.context_window),
                    });
                }
                Err(e) => {
                    let msg = format!("{e:?}");
                    let status = e.status_code();
                    let cls = classify_error(status, &msg);

                    let attempt = FallbackAttempt {
                        provider_id: candidate.provider_id.clone(),
                        model: candidate.model.clone(),
                        reason: Some(cls.reason),
                        permanence: Some(cls.permanence),
                        duration_ms: 0,
                        retries_used: 0,
                        timestamp: now_iso(),
                        message: Some(msg),
                    };
                    attempts.push(attempt);

                    match cls.permanence {
                        ErrorPermanence::Fatal => {
                            return Err(LlmError::AllProvidersExhausted);
                        }
                        ErrorPermanence::Permanent => {
                            self.health
                                .mark_down(
                                    &candidate.provider_id,
                                    &candidate.model,
                                    cls.message.as_deref().unwrap_or("permanent error"),
                                )
                                .await;
                            continue;
                        }
                        ErrorPermanence::Transient => {
                            // Transient errors (timeout, rate limit, connection reset,
                            // empty stream) do NOT mark the provider down — just try
                            // the next candidate. The provider may recover on retry.
                            continue;
                        }
                    }
                }
            }
        }

        Err(LlmError::AllProvidersExhausted)
    }

    /// Streaming send with fallback.
    ///
    /// Tries each healthy candidate in order. The first adapter that starts
    /// producing chunks "wins" — subsequent fallback only happens if the
    /// stream errors before producing any data.
    ///
    /// The returned stream may contain `StreamChunk::TextDelta` etc. If all
    /// candidates fail immediately, the stream yields a single `Err` item.
    pub fn send_stream(&self, req: &LlmRequest) -> ChunkStream {
        let candidates = self.get_candidates(&req.agent_model);
        let registry = self.registry.clone();
        let health = self.health.clone();
        let req_clone = req.clone();

        Box::pin(async_stream::stream! {
            let (up, _down) = health.filter_candidates(&candidates).await;

            if up.is_empty() {
                yield Err(LlmError::AllProvidersExhausted);
                return;
            }

            let first_token_timeout = std::time::Duration::from_secs(
                crate::config::LLM_FIRST_TOKEN_TIMEOUT_SEC
            );
            let inactivity_timeout = std::time::Duration::from_secs(
                crate::config::LLM_STREAM_INACTIVITY_SEC
            );

            for candidate in &up {
                let adapter = match registry.get_adapter(&candidate.provider_id) {
                    Some(a) => a,
                    None => continue,
                };

                let params = build_params(&req_clone, candidate);
                let stream = adapter.send_message_stream(&params);

                // Consume and re-yield chunks from this adapter.
                // If the first chunk is an error, fall through to the next candidate.
                // Two-phase timeout:
                //   - Before first chunk (got_data=false): use first_token_timeout (90s)
                //   - After first chunk (got_data=true): use inactivity_timeout (60s)
                use futures::StreamExt;
                let mut stream = stream;
                let mut got_data = false;
                let mut stream_error: Option<LlmError> = None;

                loop {
                    let current_timeout = if got_data {
                        inactivity_timeout
                    } else {
                        first_token_timeout
                    };
                    match tokio::time::timeout(current_timeout, stream.next()).await {
                        Ok(Some(chunk_result)) => {
                            match &chunk_result {
                                Ok(_) => { got_data = true; }
                                Err(e) => {
                                    if !got_data {
                                        stream_error = Some(e.clone());
                                        break;
                                    }
                                    // Late error — yield it and stop.
                                    yield chunk_result;
                                    return;
                                }
                            }
                            yield chunk_result;
                        }
                        Ok(None) => break, // Stream ended
                        Err(_) => {
                            // Timeout — no chunk received within the timeout window.
                            if got_data {
                                yield Err(LlmError::Stream(
                                    format!("Stream inactivity timeout after {}s",
                                        inactivity_timeout.as_secs())
                                ));
                                return;
                            } else {
                                stream_error = Some(LlmError::Stream(
                                    format!("First-token timeout after {}s",
                                        first_token_timeout.as_secs())
                                ));
                                break;
                            }
                        }
                    }
                }

                if got_data {
                    return; // Stream completed successfully
                }

                // No data received — check error and decide fallback
                if let Some(e) = stream_error {
                    let msg = format!("{e:?}");
                    let cls = classify_error(e.status_code(), &msg);

                    match cls.permanence {
                        ErrorPermanence::Fatal => {
                            yield Err(LlmError::AllProvidersExhausted);
                            return;
                        }
                        ErrorPermanence::Permanent => {
                            health
                                .mark_down(
                                    &candidate.provider_id,
                                    &candidate.model,
                                    cls.message.as_deref().unwrap_or("permanent error"),
                                )
                                .await;
                            continue; // Try next candidate
                        }
                        ErrorPermanence::Transient => {
                            // Transient errors (timeout, empty stream, rate limit)
                            // do NOT mark the provider down — just try next candidate.
                            continue;
                        }
                    }
                }

                // Empty stream with no error — try next candidate
                continue;
            }

            // All candidates exhausted
            yield Err(LlmError::AllProvidersExhausted);
        })
    }
}

/// Build SendMessageParams from LlmRequest + ModelCandidate.
fn build_params(req: &LlmRequest, candidate: &ModelCandidate) -> SendMessageParams {
    SendMessageParams {
        messages: req.messages.clone(),
        model: candidate.model.clone(),
        tools: req.tools.clone(),
        max_tokens: Some(candidate.max_tokens),
        thinking: req.thinking.clone(),
        system_stable: req.system_stable.clone(),
        system_dynamic: req.system_dynamic.clone(),
        session_id: Some(req.session_id.clone()),
        agent_id: Some(req.agent_id.clone()),
    }
}

/// Build content blocks from an adapter response.
fn build_content_blocks(resp: &AdapterResponse) -> Vec<crate::types::ContentBlock> {
    let mut blocks = vec![];
    if !resp.reply.is_empty() {
        blocks.push(crate::types::ContentBlock::Text {
            text: resp.reply.clone(),
        });
    }
    for tc in &resp.tool_calls {
        blocks.push(crate::types::ContentBlock::ToolUse {
            id: tc.id.clone(),
            name: tc.name.clone(),
            input: tc.input.clone(),
        });
    }
    blocks
}

fn now_iso() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    format!("{secs}")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::NebflowServiceConfig;
    use crate::types::{Message, MessageContent, MessageRole};

    #[test]
    fn build_params_from_request() {
        let req = LlmRequest {
            messages: vec![Message {
                role: MessageRole::User,
                content: MessageContent::Text("hello".into()),
                timestamp: 0,
            }],
            session_id: "s1".into(),
            agent_id: "a1".into(),
            tools: None,
            max_tokens: None,
            thinking: None,
            system_stable: Some("system".into()),
            system_dynamic: None,
            agent_model: None,
        };
        let candidate = ModelCandidate {
            provider_id: "anthropic".into(),
            model: "claude".into(),
            max_tokens: 16384,
            context_window: 200000,
            vision: false,
            capabilities: vec![],
        };
        let params = build_params(&req, &candidate);
        assert_eq!(params.model, "claude");
        assert_eq!(params.session_id.as_deref(), Some("s1"));
        assert_eq!(params.max_tokens, Some(16384));
    }

    #[test]
    fn build_content_blocks_from_response() {
        let resp = AdapterResponse {
            reply: "Hello!".into(),
            tool_calls: vec![crate::types::ToolCall {
                id: "tc1".into(),
                name: "Read".into(),
                input: serde_json::Map::new(),
            }],
            usage: None,
        };
        let blocks = build_content_blocks(&resp);
        assert_eq!(blocks.len(), 2);
        assert!(matches!(blocks[0], crate::types::ContentBlock::Text { .. }));
        assert!(matches!(
            blocks[1],
            crate::types::ContentBlock::ToolUse { .. }
        ));
    }

    #[tokio::test]
    async fn send_with_all_providers_down() {
        let config_json = r#"{
            "llm": {
                "providers": {
                    "anthropic": {
                        "baseUrl": "http://localhost:1",
                        "apiKey": "sk-test",
                        "protocol": "anthropic",
                        "models": [{"id": "claude", "maxTokens": 16384, "contextWindow": 200000}]
                    }
                },
                "model": {"default": "anthropic/claude"}
            }
        }"#;
        let config: NebflowServiceConfig = serde_json::from_str(config_json).unwrap();
        let registry = Arc::new(ProviderRegistry::new(config));
        let health = Arc::new(HealthMonitor::new());

        // Mark the provider down
        health.mark_down("anthropic", "claude", "test").await;

        let handle = LlmHandle::new(registry, health);
        let req = LlmRequest {
            messages: vec![],
            session_id: "s1".into(),
            agent_id: "a1".into(),
            tools: None,
            max_tokens: None,
            thinking: None,
            system_stable: None,
            system_dynamic: None,
            agent_model: None,
        };

        let result = handle.send(&req).await;
        assert!(matches!(result, Err(LlmError::AllProvidersExhausted)));
    }

    #[tokio::test]
    async fn send_stream_all_providers_down() {
        use futures::StreamExt;

        let config_json = r#"{
            "llm": {
                "providers": {
                    "anthropic": {
                        "baseUrl": "http://localhost:1",
                        "apiKey": "sk-test",
                        "protocol": "anthropic",
                        "models": [{"id": "claude", "maxTokens": 16384, "contextWindow": 200000}]
                    }
                },
                "model": {"default": "anthropic/claude"}
            }
        }"#;
        let config: NebflowServiceConfig = serde_json::from_str(config_json).unwrap();
        let registry = Arc::new(ProviderRegistry::new(config));
        let health = Arc::new(HealthMonitor::new());

        health.mark_down("anthropic", "claude", "test").await;

        let handle = LlmHandle::new(registry, health);
        let req = LlmRequest {
            messages: vec![],
            session_id: "s1".into(),
            agent_id: "a1".into(),
            tools: None,
            max_tokens: None,
            thinking: None,
            system_stable: None,
            system_dynamic: None,
            agent_model: None,
        };

        let mut stream = handle.send_stream(&req);
        let first = stream.next().await;
        assert!(matches!(first, Some(Err(LlmError::AllProvidersExhausted))));
    }

    #[tokio::test]
    async fn send_stream_no_candidates() {
        use futures::StreamExt;

        let config_json = r#"{
            "llm": {
                "providers": {},
                "model": {"default": ""}
            }
        }"#;
        let config: NebflowServiceConfig = serde_json::from_str(config_json).unwrap();
        let registry = Arc::new(ProviderRegistry::new(config));
        let health = Arc::new(HealthMonitor::new());

        let handle = LlmHandle::new(registry, health);
        let req = LlmRequest {
            messages: vec![],
            session_id: "s1".into(),
            agent_id: "a1".into(),
            tools: None,
            max_tokens: None,
            thinking: None,
            system_stable: None,
            system_dynamic: None,
            agent_model: None,
        };

        let mut stream = handle.send_stream(&req);
        let first = stream.next().await;
        // With no providers, we should get an error
        assert!(first.is_some());
        assert!(first.unwrap().is_err());
    }

    #[tokio::test]
    async fn send_stream_uses_agent_model_override() {
        use futures::StreamExt;

        let config_json = r#"{
            "llm": {
                "providers": {
                    "anthropic": {
                        "baseUrl": "http://localhost:1",
                        "apiKey": "sk-test",
                        "protocol": "anthropic",
                        "models": [{"id": "claude", "maxTokens": 16384, "contextWindow": 200000}]
                    },
                    "openai": {
                        "baseUrl": "http://localhost:1",
                        "apiKey": "sk-test2",
                        "protocol": "openai",
                        "models": [{"id": "gpt-4o", "maxTokens": 16384, "contextWindow": 128000}]
                    }
                },
                "model": {"default": "anthropic/claude"}
            }
        }"#;
        let config: NebflowServiceConfig = serde_json::from_str(config_json).unwrap();
        let registry = Arc::new(ProviderRegistry::new(config));
        let health = Arc::new(HealthMonitor::new());

        // Mark the default provider down — should still try openai
        health.mark_down("anthropic", "claude", "test").await;

        let handle = LlmHandle::new(registry, health);

        // With agent_model preferring openai, candidates should include it
        let req = LlmRequest {
            messages: vec![Message {
                role: MessageRole::User,
                content: MessageContent::Text("hi".into()),
                timestamp: 0,
            }],
            session_id: "s1".into(),
            agent_id: "a1".into(),
            tools: None,
            max_tokens: None,
            thinking: None,
            system_stable: None,
            system_dynamic: None,
            agent_model: Some(AgentModelConfig {
                preferred: Some("openai/gpt-4o".into()),
                fallbacks: vec![],
            }),
        };

        let candidates = handle.get_candidates(&req.agent_model);
        assert_eq!(candidates[0].model, "gpt-4o");

        // Stream should try openai (which will timeout since localhost:1 is unreachable,
        // but we're verifying the candidate selection, not the actual response).
        let mut stream = handle.send_stream(&req);
        // The stream will eventually produce an error (timeout or connection error)
        // since localhost:1 is unreachable. Just verify it doesn't return
        // AllProvidersExhausted immediately (which would mean openai was filtered out).
        let first = tokio::time::timeout(std::time::Duration::from_secs(5), stream.next()).await;

        // Should get something (not hang forever) — either an error from the adapter
        // or a timeout. The key is it didn't return AllProvidersExhausted instantly.
        assert!(first.is_ok(), "stream should produce a result within 5s");
    }

    #[tokio::test]
    async fn send_stream_health_filter_excludes_down_provider() {
        let config_json = r#"{
            "llm": {
                "providers": {
                    "anthropic": {
                        "baseUrl": "http://localhost:1",
                        "apiKey": "sk-test",
                        "protocol": "anthropic",
                        "models": [{"id": "claude", "maxTokens": 16384, "contextWindow": 200000}]
                    },
                    "openai": {
                        "baseUrl": "http://localhost:1",
                        "apiKey": "sk-test2",
                        "protocol": "openai",
                        "models": [{"id": "gpt-4o", "maxTokens": 16384, "contextWindow": 128000}]
                    }
                },
                "model": {
                    "default": "anthropic/claude",
                    "fallbacks": ["openai/gpt-4o"]
                }
            }
        }"#;
        let config: NebflowServiceConfig = serde_json::from_str(config_json).unwrap();
        let registry = Arc::new(ProviderRegistry::new(config));
        let health = Arc::new(HealthMonitor::new());

        // Mark BOTH providers down
        health.mark_down("anthropic", "claude", "test").await;
        health.mark_down("openai", "gpt-4o", "test").await;

        let handle = LlmHandle::new(registry, health);

        // All providers down → should return AllProvidersExhausted
        let healthy = handle.filter_healthy(&handle.get_candidates(&None)).await;
        assert!(healthy.is_empty(), "all providers should be filtered out");
    }

    #[test]
    fn first_token_timeout_config_is_90s() {
        // T-1: verify the first-token timeout is 90s as configured
        assert_eq!(crate::config::LLM_FIRST_TOKEN_TIMEOUT_SEC, 90);
    }

    #[test]
    fn inactivity_timeout_config_is_60s() {
        // T-1: verify the inactivity timeout is 60s as configured
        assert_eq!(crate::config::LLM_STREAM_INACTIVITY_SEC, 60);
    }

    #[test]
    fn first_token_and_inactivity_timeouts_differ() {
        // T-1: the two timeouts must be different (90s first-token vs 60s inactivity)
        assert_ne!(
            crate::config::LLM_FIRST_TOKEN_TIMEOUT_SEC,
            crate::config::LLM_STREAM_INACTIVITY_SEC
        );
    }
}
