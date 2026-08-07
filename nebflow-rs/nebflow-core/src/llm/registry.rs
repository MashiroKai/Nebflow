//! Provider registry — manages HTTP client, adapter creation, candidate chain building.
//! Mirrors nebflow.llm.ProviderRegistry from Scala.

use std::sync::Arc;

use crate::config::{LlmProtocol, NebflowServiceConfig, ProviderConfig};
use crate::llm::adapter::ProviderAdapter;
use crate::llm::anthropic::AnthropicAdapter;
use crate::llm::openai::OpenAiAdapter;
use crate::types::{AgentModelConfig, ModelCandidate};

/// Manages adapter creation and candidate chain resolution.
pub struct ProviderRegistry {
    config: NebflowServiceConfig,
    http_client: reqwest::Client,
}

impl ProviderRegistry {
    pub fn new(config: NebflowServiceConfig) -> Self {
        let http_client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(600))
            .build()
            .expect("failed to build HTTP client");
        Self {
            config,
            http_client,
        }
    }

    /// Get the shared HTTP client.
    pub fn http_client(&self) -> &reqwest::Client {
        &self.http_client
    }

    /// Create an adapter for a given provider ID.
    pub fn get_adapter(&self, provider_id: &str) -> Option<Arc<dyn ProviderAdapter>> {
        let provider = self.config.llm.providers.get(provider_id)?;
        Some(self.create_adapter(provider))
    }

    fn create_adapter(&self, provider: &ProviderConfig) -> Arc<dyn ProviderAdapter> {
        match provider.protocol {
            LlmProtocol::Anthropic => Arc::new(AnthropicAdapter::new(
                &provider.base_url,
                &provider.api_key,
                self.http_client.clone(),
            )),
            LlmProtocol::OpenAi => Arc::new(OpenAiAdapter::new(
                &provider.base_url,
                &provider.api_key,
                self.http_client.clone(),
            )),
        }
    }

    /// Parse a model ref string "providerId/modelId" into its components.
    pub fn parse_model_ref(ref_str: &str) -> Option<(&str, &str)> {
        let idx = ref_str.find('/')?;
        Some((&ref_str[..idx], &ref_str[idx + 1..]))
    }

    /// Build the global candidate chain from config.
    pub fn get_candidates(&self) -> Vec<ModelCandidate> {
        let chain: Vec<&str> = std::iter::once(self.config.llm.model.default.as_str())
            .chain(self.config.llm.model.fallbacks.iter().map(|s| s.as_str()))
            .collect();

        let from_chain: Vec<ModelCandidate> = chain
            .iter()
            .filter_map(|&ref_str| {
                let (provider_id, model_id) = Self::parse_model_ref(ref_str)?;
                let provider = self.config.llm.providers.get(provider_id)?;
                let model_config = provider.models.iter().find(|m| m.id == model_id);
                let max_tokens = model_config
                    .map(|m| m.max_tokens)
                    .unwrap_or(crate::config::MAX_TOKENS);
                let context_window = model_config
                    .map(|m| m.context_window)
                    .unwrap_or(crate::config::CONTEXT_WINDOW);
                let vision = model_config.and_then(|m| m.vision).unwrap_or(false);
                let capabilities = model_config
                    .and_then(|m| m.capabilities.clone())
                    .unwrap_or_default();
                Some(ModelCandidate {
                    provider_id: provider_id.to_string(),
                    model: model_id.to_string(),
                    max_tokens,
                    context_window,
                    vision,
                    capabilities,
                })
            })
            .collect();

        if !from_chain.is_empty() {
            from_chain
        } else {
            // Fallback: use first available model across all providers
            self.config
                .llm
                .providers
                .iter()
                .flat_map(|(provider_id, provider)| {
                    provider.models.iter().map(move |mc| ModelCandidate {
                        provider_id: provider_id.clone(),
                        model: mc.id.clone(),
                        max_tokens: mc.max_tokens,
                        context_window: mc.context_window,
                        vision: mc.vision.unwrap_or(false),
                        capabilities: mc.capabilities.clone().unwrap_or_default(),
                    })
                })
                .next()
                .into_iter()
                .collect()
        }
    }

    /// Build a candidate chain for an agent's model configuration.
    /// Falls back to global chain if agent config is empty or unresolvable.
    pub fn get_candidates_for_agent(
        &self,
        agent_model: &Option<AgentModelConfig>,
    ) -> Vec<ModelCandidate> {
        match agent_model {
            None => self.get_candidates(),
            Some(cfg) if cfg.preferred.is_none() && cfg.fallbacks.is_empty() => {
                self.get_candidates()
            }
            Some(cfg) => {
                let agent_chain: Vec<String> = cfg
                    .preferred
                    .iter()
                    .cloned()
                    .chain(cfg.fallbacks.iter().cloned())
                    .collect();

                let resolved: Vec<ModelCandidate> = agent_chain
                    .iter()
                    .filter_map(|ref_str| self.get_candidate_for_ref(ref_str))
                    .collect();

                if !resolved.is_empty() {
                    resolved
                } else {
                    self.get_candidates()
                }
            }
        }
    }

    /// Resolve a single model ref to a ModelCandidate.
    pub fn get_candidate_for_ref(&self, ref_str: &str) -> Option<ModelCandidate> {
        let (provider_id, model_id) = Self::parse_model_ref(ref_str)?;
        let provider = self.config.llm.providers.get(provider_id)?;
        let model_config = provider.models.iter().find(|m| m.id == model_id);
        Some(ModelCandidate {
            provider_id: provider_id.to_string(),
            model: model_id.to_string(),
            max_tokens: model_config
                .map(|m| m.max_tokens)
                .unwrap_or(crate::config::MAX_TOKENS),
            context_window: model_config
                .map(|m| m.context_window)
                .unwrap_or(crate::config::CONTEXT_WINDOW),
            vision: model_config.and_then(|m| m.vision).unwrap_or(false),
            capabilities: model_config
                .and_then(|m| m.capabilities.clone())
                .unwrap_or_default(),
        })
    }

    /// List all available models across all providers. Returns (ref, displayName) pairs.
    pub fn get_all_models(&self) -> Vec<(String, String)> {
        self.config
            .llm
            .providers
            .iter()
            .flat_map(|(provider_id, provider)| {
                provider
                    .models
                    .iter()
                    .map(move |mc| (format!("{provider_id}/{}", mc.id), mc.id.clone()))
            })
            .collect()
    }

    /// Get the raw config.
    pub fn config(&self) -> &NebflowServiceConfig {
        &self.config
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_config() -> NebflowServiceConfig {
        let json = r#"{
            "llm": {
                "providers": {
                    "anthropic": {
                        "baseUrl": "https://api.anthropic.com",
                        "apiKey": "sk-test",
                        "protocol": "anthropic",
                        "models": [
                            {"id": "claude-sonnet-4-6", "maxTokens": 16384, "contextWindow": 200000}
                        ]
                    },
                    "openai": {
                        "baseUrl": "https://api.openai.com/v1",
                        "apiKey": "sk-test2",
                        "protocol": "openai",
                        "models": [
                            {"id": "gpt-4o", "maxTokens": 16384, "contextWindow": 128000}
                        ]
                    }
                },
                "model": {
                    "default": "anthropic/claude-sonnet-4-6",
                    "fallbacks": ["openai/gpt-4o"]
                }
            }
        }"#;
        serde_json::from_str(json).unwrap()
    }

    #[test]
    fn get_candidates_from_chain() {
        let registry = ProviderRegistry::new(test_config());
        let candidates = registry.get_candidates();
        assert_eq!(candidates.len(), 2);
        assert_eq!(candidates[0].provider_id, "anthropic");
        assert_eq!(candidates[0].model, "claude-sonnet-4-6");
        assert_eq!(candidates[1].provider_id, "openai");
    }

    #[test]
    fn get_candidate_for_ref() {
        let registry = ProviderRegistry::new(test_config());
        let candidate = registry.get_candidate_for_ref("openai/gpt-4o").unwrap();
        assert_eq!(candidate.provider_id, "openai");
        assert_eq!(candidate.model, "gpt-4o");
        assert_eq!(candidate.context_window, 128000);
    }

    #[test]
    fn get_candidate_for_invalid_ref_returns_none() {
        let registry = ProviderRegistry::new(test_config());
        assert!(registry.get_candidate_for_ref("unknown/model").is_none());
        assert!(registry.get_candidate_for_ref("invalid").is_none());
    }

    #[test]
    fn candidates_for_agent_with_config() {
        let registry = ProviderRegistry::new(test_config());
        let agent_model = Some(AgentModelConfig {
            preferred: Some("openai/gpt-4o".into()),
            fallbacks: vec!["anthropic/claude-sonnet-4-6".into()],
        });
        let candidates = registry.get_candidates_for_agent(&agent_model);
        assert_eq!(candidates.len(), 2);
        // Preferred comes first
        assert_eq!(candidates[0].model, "gpt-4o");
    }

    #[test]
    fn candidates_for_agent_without_config_uses_global() {
        let registry = ProviderRegistry::new(test_config());
        let candidates = registry.get_candidates_for_agent(&None);
        assert_eq!(candidates.len(), 2);
        assert_eq!(candidates[0].model, "claude-sonnet-4-6");
    }

    #[test]
    fn candidates_for_agent_with_empty_config_uses_global() {
        let registry = ProviderRegistry::new(test_config());
        let agent_model = Some(AgentModelConfig {
            preferred: None,
            fallbacks: vec![],
        });
        let candidates = registry.get_candidates_for_agent(&agent_model);
        assert_eq!(candidates.len(), 2);
    }

    #[test]
    fn get_all_models() {
        let registry = ProviderRegistry::new(test_config());
        let models = registry.get_all_models();
        assert_eq!(models.len(), 2);
        assert!(models
            .iter()
            .any(|(r, _)| r == "anthropic/claude-sonnet-4-6"));
    }

    #[test]
    fn parse_model_ref() {
        let (provider, model) = ProviderRegistry::parse_model_ref("anthropic/claude").unwrap();
        assert_eq!(provider, "anthropic");
        assert_eq!(model, "claude");
    }

    #[test]
    fn parse_invalid_model_ref() {
        assert!(ProviderRegistry::parse_model_ref("invalid").is_none());
    }
}
