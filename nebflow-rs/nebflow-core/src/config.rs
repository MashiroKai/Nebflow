//! Default configuration values — mirrors nebflow.shared.Defaults from Scala.
//!
//! Centralized constants to avoid magic numbers scattered across the codebase.

/// Default LLM context window size.
pub const CONTEXT_WINDOW: usize = 128_000;

/// Default max tokens for LLM generation.
pub const MAX_TOKENS: usize = 16_384;

/// Max tokens for compaction requests.
pub const MAX_TOKENS_COMPACT: usize = 4_096;

/// Stream inactivity timeout — resets on every stream event (text/tool/compaction).
/// Also used as frontend spinner timeout (streamTimeoutMs + 30s buffer).
pub const STREAM_TIMEOUT_SEC: u64 = 600;

/// First-token timeout — if no chunk arrives from the LLM provider within this
/// time after the request is sent, the stream is considered hung.
pub const LLM_FIRST_TOKEN_TIMEOUT_SEC: u64 = 90;

/// Backend LLM stream inactivity timeout — if no chunk is received from the
/// LLM provider within this time, the stream is considered hung and cancelled.
pub const LLM_STREAM_INACTIVITY_SEC: u64 = 60;

/// Per-provider LLM request timeout (covers streaming generation), in ms.
pub const LLM_TIMEOUT_MS: u64 = 600_000;

/// HTTP read timeout for LLM provider connections (must be >= LLM_TIMEOUT_MS), in seconds.
pub const LLM_READ_TIMEOUT_SEC: u64 = 600;

/// Bash tool max timeout in ms.
pub const BASH_MAX_TIMEOUT_MS: u64 = 3_600_000;

/// Curl tool max timeout in seconds.
pub const CURL_MAX_TIMEOUT_SEC: u64 = 120;

/// WebFetch tool timeout in ms.
pub const WEB_FETCH_TIMEOUT_MS: u64 = 120_000;

/// Background job heartbeat interval in seconds.
pub const BG_HEARTBEAT_INTERVAL_SEC: u64 = 30;

/// Background job health check interval in seconds.
pub const BG_HEALTH_CHECK_INTERVAL_SEC: u64 = 30;

/// Background job idle threshold (no output) before flagging as stuck, in seconds.
pub const BG_STUCK_THRESHOLD_SEC: u64 = 600;

/// Global cap on tool result size (chars).
pub const DEFAULT_MAX_RESULT_SIZE_CHARS: usize = 50_000;

/// Maximum aggregate size (chars) for tool_result blocks within a single turn's batch.
pub const MAX_TOOL_RESULTS_PER_MESSAGE_CHARS: usize = 200_000;

/// Preview size in characters for persisted tool results.
pub const TOOL_RESULT_PREVIEW_SIZE: usize = 2_048;

// ============================================================
// LLM config types — from nebflow.llm.config.scala
// ============================================================

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// LLM protocol type.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum LlmProtocol {
    #[serde(rename = "anthropic")]
    Anthropic,
    #[serde(rename = "openai")]
    OpenAi,
}

/// Model configuration entry within a provider.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelConfig {
    pub id: String,
    #[serde(default = "default_max_tokens")]
    pub max_tokens: usize,
    #[serde(default = "default_context_window")]
    pub context_window: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub vision: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub capabilities: Option<Vec<String>>,
}

fn default_max_tokens() -> usize {
    MAX_TOKENS
}
fn default_context_window() -> usize {
    CONTEXT_WINDOW
}

/// Provider configuration (API endpoint + key + models).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProviderConfig {
    pub base_url: String,
    pub api_key: String,
    pub protocol: LlmProtocol,
    #[serde(default)]
    pub models: Vec<ModelConfig>,
}

/// Model chain configuration (default + fallbacks).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelChainConfig {
    pub default: String,
    #[serde(default)]
    pub fallbacks: Vec<String>,
}

/// Thinking configuration for extended thinking models.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ThinkingConfig {
    #[serde(default = "default_thinking_enabled")]
    pub enabled: bool,
    #[serde(default = "default_budget_tokens")]
    pub budget_tokens: u32,
}

fn default_thinking_enabled() -> bool {
    true
}
fn default_budget_tokens() -> u32 {
    32_000
}

impl ThinkingConfig {
    /// Convert to the raw JSON shape expected by LLM adapters.
    pub fn to_llm_json(&self) -> serde_json::Value {
        if self.enabled {
            serde_json::json!({
                "type": "enabled",
                "budget_tokens": self.budget_tokens
            })
        } else {
            serde_json::Value::Null
        }
    }
}

/// MCP server configuration.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct McpServerConfig {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub command: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub args: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub env: Option<HashMap<String, String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub headers: Option<HashMap<String, String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub enabled: Option<bool>,
}

impl McpServerConfig {
    pub fn is_enabled(&self) -> bool {
        self.enabled.unwrap_or(true)
    }
}

/// Search configuration.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchConfig {
    pub provider: String,
    pub api_key: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub engine: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
}

/// Service-level LLM config (providers map + model chain).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServiceLlmConfig {
    pub providers: HashMap<String, ProviderConfig>,
    pub model: ModelChainConfig,
}

/// Top-level nebflow service config.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NebflowServiceConfig {
    pub llm: ServiceLlmConfig,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mcp_servers: Option<HashMap<String, McpServerConfig>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub search: Option<SearchConfig>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub thinking_config: Option<ThinkingConfig>,
}

// ============================================================
// Config file loading — mirrors Scala Config.loadServiceConfig
// ============================================================

use std::path::{Path, PathBuf};

/// Default config file path: `~/.nebflow/nebflow.json`.
pub fn default_config_path() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    PathBuf::from(home).join(".nebflow").join("nebflow.json")
}

/// Get the data root directory: `~/.nebflow/`.
pub fn data_root() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    PathBuf::from(home).join(".nebflow")
}

/// Default NebflowServiceConfig with no providers configured.
pub fn default_config() -> NebflowServiceConfig {
    NebflowServiceConfig {
        llm: ServiceLlmConfig {
            providers: HashMap::new(),
            model: ModelChainConfig {
                default: "anthropic/claude-sonnet-4-6".into(),
                fallbacks: Vec::new(),
            },
        },
        mcp_servers: None,
        search: None,
        thinking_config: None,
    }
}

/// Load service config from `~/.nebflow/nebflow.json`.
///
/// Returns default config if the file doesn't exist.
/// Returns default config on parse error (with warning logged via eprintln).
pub fn load_service_config() -> NebflowServiceConfig {
    load_service_config_from(&default_config_path())
}

/// Load service config from a specific path.
pub fn load_service_config_from(path: &Path) -> NebflowServiceConfig {
    match std::fs::read_to_string(path) {
        Ok(content) => {
            if content.trim().is_empty() || content.trim() == "{}" {
                return default_config();
            }
            match serde_json::from_str::<NebflowServiceConfig>(&content) {
                Ok(cfg) => cfg,
                Err(e) => {
                    eprintln!("Warning: Failed to parse config at {}: {e}", path.display());
                    default_config()
                }
            }
        }
        Err(_) => default_config(),
    }
}

/// Save service config to a path (pretty-printed JSON).
pub fn save_service_config_to(
    config: &NebflowServiceConfig,
    path: &Path,
) -> Result<(), std::io::Error> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let json = serde_json::to_string_pretty(config)?;
    std::fs::write(path, json)
}

/// Save service config to the default path (`~/.nebflow/nebflow.json`).
pub fn save_service_config(config: &NebflowServiceConfig) -> Result<(), std::io::Error> {
    save_service_config_to(config, &default_config_path())
}

/// Read raw config JSON from a path (for `config show` — returns the file
/// content as-is, or `"{}"` if the file doesn't exist).
pub fn read_raw_config(path: &Path) -> String {
    std::fs::read_to_string(path).unwrap_or_else(|_| "{}".to_string())
}

/// Set a value at a dot-separated path in a JSON config object.
///
/// Example: `set_config_value(&mut json, "llm.model.default", "openai/gpt-4o")`
/// navigates to `json["llm"]["model"]["default"]` and sets it to the string.
/// Numeric and boolean values are parsed if possible.
pub fn set_config_value(json: &mut serde_json::Value, path: &str, value: &str) {
    let segments: Vec<&str> = path.split('.').collect();
    let parsed_value = parse_config_value(value);

    let mut current = json;
    for (i, seg) in segments.iter().enumerate() {
        if i == segments.len() - 1 {
            // Last segment — set the value
            if let Some(obj) = current.as_object_mut() {
                obj.insert(seg.to_string(), parsed_value.clone());
            }
        } else {
            // Navigate/create intermediate objects
            if let Some(obj) = current.as_object_mut() {
                if !obj.contains_key(*seg) {
                    obj.insert(seg.to_string(), serde_json::json!({}));
                }
                current = obj.get_mut(*seg).unwrap();
            } else {
                // Can't navigate into a non-object
                return;
            }
        }
    }
}

/// Parse a string value as JSON (number, boolean, null) or fall back to string.
fn parse_config_value(s: &str) -> serde_json::Value {
    // Try parsing as JSON (handles numbers, booleans, null, arrays, objects)
    if let Ok(v) = serde_json::from_str::<serde_json::Value>(s) {
        return v;
    }
    serde_json::Value::String(s.to_string())
}

/// Get a value at a dot-separated path from a JSON config object.
pub fn get_config_value<'a>(
    json: &'a serde_json::Value,
    path: &str,
) -> Option<&'a serde_json::Value> {
    let mut current = json;
    for seg in path.split('.') {
        current = current.get(seg)?;
    }
    Some(current)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_match_scala() {
        assert_eq!(CONTEXT_WINDOW, 128_000);
        assert_eq!(MAX_TOKENS, 16_384);
        assert_eq!(MAX_TOKENS_COMPACT, 4_096);
        assert_eq!(STREAM_TIMEOUT_SEC, 600);
        assert_eq!(LLM_FIRST_TOKEN_TIMEOUT_SEC, 90);
        assert_eq!(LLM_STREAM_INACTIVITY_SEC, 60);
        assert_eq!(LLM_TIMEOUT_MS, 600_000);
        assert_eq!(LLM_READ_TIMEOUT_SEC, 600);
        assert_eq!(BASH_MAX_TIMEOUT_MS, 3_600_000);
        assert_eq!(CURL_MAX_TIMEOUT_SEC, 120);
        assert_eq!(WEB_FETCH_TIMEOUT_MS, 120_000);
        assert_eq!(BG_HEARTBEAT_INTERVAL_SEC, 30);
        assert_eq!(BG_HEALTH_CHECK_INTERVAL_SEC, 30);
        assert_eq!(BG_STUCK_THRESHOLD_SEC, 600);
        assert_eq!(DEFAULT_MAX_RESULT_SIZE_CHARS, 50_000);
        assert_eq!(MAX_TOOL_RESULTS_PER_MESSAGE_CHARS, 200_000);
        assert_eq!(TOOL_RESULT_PREVIEW_SIZE, 2_048);
    }

    #[test]
    fn thinking_config_serde() {
        let tc = ThinkingConfig {
            enabled: true,
            budget_tokens: 32_000,
        };
        let json = serde_json::to_string(&tc).unwrap();
        let back: ThinkingConfig = serde_json::from_str(&json).unwrap();
        assert!(back.enabled);
        assert_eq!(back.budget_tokens, 32_000);
    }

    #[test]
    fn thinking_config_to_llm_json() {
        let tc = ThinkingConfig {
            enabled: true,
            budget_tokens: 16_000,
        };
        let json = tc.to_llm_json();
        assert_eq!(json["type"], "enabled");
        assert_eq!(json["budget_tokens"], 16_000);

        let tc_off = ThinkingConfig {
            enabled: false,
            budget_tokens: 0,
        };
        assert!(tc_off.to_llm_json().is_null());
    }

    #[test]
    fn model_chain_config_serde() {
        let json = r#"{"default":"anthropic/claude-sonnet-4-6","fallbacks":["openai/gpt-4o"]}"#;
        let cfg: ModelChainConfig = serde_json::from_str(json).unwrap();
        assert_eq!(cfg.default, "anthropic/claude-sonnet-4-6");
        assert_eq!(cfg.fallbacks.len(), 1);
    }

    #[test]
    fn provider_config_serde() {
        let json = r#"{"baseUrl":"https://api.anthropic.com","apiKey":"sk-xxx","protocol":"anthropic","models":[{"id":"claude-sonnet-4-6","maxTokens":16384,"contextWindow":200000}]}"#;
        let cfg: ProviderConfig = serde_json::from_str(json).unwrap();
        assert_eq!(cfg.base_url, "https://api.anthropic.com");
        assert_eq!(cfg.protocol, LlmProtocol::Anthropic);
        assert_eq!(cfg.models.len(), 1);
        assert_eq!(cfg.models[0].id, "claude-sonnet-4-6");
    }

    #[test]
    fn mcp_server_config_defaults_enabled() {
        let json = r#"{"command":"npx"}"#;
        let cfg: McpServerConfig = serde_json::from_str(json).unwrap();
        assert!(cfg.is_enabled());
    }

    #[test]
    fn mcp_server_config_explicit_disabled() {
        let json = r#"{"command":"npx","enabled":false}"#;
        let cfg: McpServerConfig = serde_json::from_str(json).unwrap();
        assert!(!cfg.is_enabled());
    }

    #[test]
    fn nebflow_service_config_serde() {
        let json = r#"{"llm":{"providers":{},"model":{"default":"anthropic/claude-sonnet-4-6"}}}"#;
        let cfg: NebflowServiceConfig = serde_json::from_str(json).unwrap();
        assert_eq!(cfg.llm.model.default, "anthropic/claude-sonnet-4-6");
    }

    // ===== Config file loading tests =====

    #[test]
    fn default_config_has_no_providers() {
        let cfg = default_config();
        assert!(cfg.llm.providers.is_empty());
        assert_eq!(cfg.llm.model.default, "anthropic/claude-sonnet-4-6");
    }

    #[test]
    fn load_from_missing_file_returns_default() {
        let cfg = load_service_config_from(Path::new("/nonexistent/path/nebflow.json"));
        assert!(cfg.llm.providers.is_empty());
    }

    #[test]
    fn load_from_empty_file_returns_default() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("nebflow.json");
        std::fs::write(&path, "{}").unwrap();
        let cfg = load_service_config_from(&path);
        assert!(cfg.llm.providers.is_empty());
    }

    #[test]
    fn load_from_valid_config() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("nebflow.json");
        std::fs::write(
            &path,
            r#"{
                "llm": {
                    "providers": {
                        "anthropic": {
                            "baseUrl": "https://api.anthropic.com",
                            "apiKey": "sk-test",
                            "protocol": "anthropic",
                            "models": [{"id": "claude", "maxTokens": 16384, "contextWindow": 200000}]
                        }
                    },
                    "model": {"default": "anthropic/claude", "fallbacks": []}
                }
            }"#,
        ).unwrap();
        let cfg = load_service_config_from(&path);
        assert_eq!(cfg.llm.providers.len(), 1);
        assert!(cfg.llm.providers.contains_key("anthropic"));
        assert_eq!(cfg.llm.model.default, "anthropic/claude");
    }

    #[test]
    fn save_and_load_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("nebflow.json");

        let cfg = NebflowServiceConfig {
            llm: ServiceLlmConfig {
                providers: HashMap::new(),
                model: ModelChainConfig {
                    default: "openai/gpt-4o".into(),
                    fallbacks: vec!["anthropic/claude".into()],
                },
            },
            mcp_servers: None,
            search: None,
            thinking_config: Some(ThinkingConfig {
                enabled: false,
                budget_tokens: 16000,
            }),
        };

        save_service_config_to(&cfg, &path).unwrap();
        let loaded = load_service_config_from(&path);
        assert_eq!(loaded.llm.model.default, "openai/gpt-4o");
        assert_eq!(loaded.llm.model.fallbacks.len(), 1);
        assert!(loaded.thinking_config.is_some());
        assert!(!loaded.thinking_config.unwrap().enabled);
    }

    #[test]
    fn read_raw_config_missing_returns_braces() {
        let content = read_raw_config(Path::new("/nonexistent/config.json"));
        assert_eq!(content, "{}");
    }

    #[test]
    fn set_config_value_simple() {
        let mut json = serde_json::json!({});
        set_config_value(&mut json, "key", "value");
        assert_eq!(json["key"], "value");
    }

    #[test]
    fn set_config_value_nested() {
        let mut json = serde_json::json!({"llm": {"model": {}}});
        set_config_value(&mut json, "llm.model.default", "openai/gpt-4o");
        assert_eq!(json["llm"]["model"]["default"], "openai/gpt-4o");
    }

    #[test]
    fn set_config_value_creates_intermediate_objects() {
        let mut json = serde_json::json!({});
        set_config_value(&mut json, "a.b.c", "deep");
        assert_eq!(json["a"]["b"]["c"], "deep");
    }

    #[test]
    fn set_config_value_numeric() {
        let mut json = serde_json::json!({});
        set_config_value(&mut json, "port", "8080");
        assert_eq!(json["port"], 8080);
    }

    #[test]
    fn set_config_value_boolean() {
        let mut json = serde_json::json!({});
        set_config_value(&mut json, "enabled", "true");
        assert_eq!(json["enabled"], true);
    }

    #[test]
    fn get_config_value_simple() {
        let json = serde_json::json!({"key": "value"});
        let val = get_config_value(&json, "key");
        assert_eq!(val.unwrap(), "value");
    }

    #[test]
    fn get_config_value_nested() {
        let json = serde_json::json!({"a": {"b": {"c": 42}}});
        let val = get_config_value(&json, "a.b.c");
        assert_eq!(val.unwrap(), 42);
    }

    #[test]
    fn get_config_value_missing() {
        let json = serde_json::json!({"a": 1});
        let val = get_config_value(&json, "b");
        assert!(val.is_none());
    }
}
