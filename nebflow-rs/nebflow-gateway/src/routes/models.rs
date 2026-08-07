//! Models REST API.
//! GET /api/models — list available LLM models from the configured providers.
//!
//! Mirrors Scala RestApiRoutes `GET /models` → `{models: [{ref, label}]}` where
//! `ref = "<providerId>/<modelId>"` and `label = <modelId>` (ProviderRegistry.getAllModels).

use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::response::Json;
use serde::Serialize;
use serde_json::{json, Value};

use crate::routes::AppState;

fn check_auth(state: &AppState, headers: &HeaderMap) -> bool {
    let token = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .filter(|s| s.starts_with("Bearer "))
        .map(|s| s[7..].to_string())
        .unwrap_or_default();
    state.auth.validate(&token)
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct ModelEntry {
    #[serde(rename = "ref")]
    pub ref_: String,
    pub label: String,
}

/// GET /api/models — list all configured models as `{models: [{ref, label}]}`.
pub async fn list_models(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Value>, (StatusCode, String)> {
    if !check_auth(&state, &headers) {
        return Err((StatusCode::UNAUTHORIZED, "Invalid token".into()));
    }
    let path = nebflow_core::config::default_config_path();
    let raw = tokio::task::spawn_blocking(move || nebflow_core::config::read_raw_config(&path))
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    let config: Value = serde_json::from_str(&raw).unwrap_or_else(|_| json!({}));
    let models = models_from_config(&config);
    Ok(Json(json!({ "models": models })))
}

/// Build the model list from a raw config JSON value.
/// Reads `llm.providers.<id>.models[].id`; providers with no models are skipped.
/// Pure function — takes the config value directly for testability.
pub fn models_from_config(config: &Value) -> Vec<ModelEntry> {
    let mut out = Vec::new();
    let Some(providers) = config.pointer("/llm/providers").and_then(Value::as_object) else {
        return out;
    };
    // Sort provider ids for deterministic ordering (JSON objects are unordered).
    let mut provider_ids: Vec<&String> = providers.keys().collect();
    provider_ids.sort();
    for provider_id in provider_ids {
        let Some(models) = providers[provider_id]
            .get("models")
            .and_then(Value::as_array)
        else {
            continue;
        };
        for model in models {
            let Some(id) = model.get("id").and_then(Value::as_str) else {
                continue;
            };
            out.push(ModelEntry {
                ref_: format!("{provider_id}/{id}"),
                label: id.to_string(),
            });
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::Auth;
    use crate::ratelimit::RateLimiter;
    use crate::session::SessionStore;
    use crate::ws_hub::WsHub;
    use std::sync::Arc;
    use tempfile::tempdir;

    fn make_state(dir: &std::path::Path) -> AppState {
        AppState::new(
            Arc::new(Auth::with_token("t".into())),
            Arc::new(RateLimiter::new()),
            Arc::new(SessionStore::new(dir.to_path_buf())),
            Arc::new(WsHub::new()),
            "test",
            None,
            None,
            None,
        )
    }

    #[tokio::test]
    async fn list_models_no_auth() {
        let dir = tempdir().unwrap();
        let result = list_models(State(make_state(dir.path())), HeaderMap::new()).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn list_models_with_auth_returns_models_object() {
        let dir = tempdir().unwrap();
        let mut headers = HeaderMap::new();
        headers.insert("authorization", "Bearer t".parse().unwrap());
        let Json(body) = list_models(State(make_state(dir.path())), headers)
            .await
            .unwrap();
        // Response shape must be an object with a "models" array (Scala-compatible).
        assert!(body.get("models").and_then(Value::as_array).is_some());
    }

    #[test]
    fn models_from_config_builds_refs() {
        let config = json!({
            "llm": {
                "providers": {
                    "ustc": {
                        "baseUrl": "https://example.com",
                        "models": [
                            {"id": "deepseek-v4-pro"},
                            {"id": "qwen3-32b"}
                        ]
                    },
                    "openai": {
                        "baseUrl": "https://api.openai.com",
                        "models": [{"id": "gpt-4o"}]
                    },
                    "empty": {"baseUrl": "https://x.com", "models": []}
                }
            }
        });
        let models = models_from_config(&config);
        assert_eq!(
            models,
            vec![
                ModelEntry {
                    ref_: "openai/gpt-4o".into(),
                    label: "gpt-4o".into()
                },
                ModelEntry {
                    ref_: "ustc/deepseek-v4-pro".into(),
                    label: "deepseek-v4-pro".into()
                },
                ModelEntry {
                    ref_: "ustc/qwen3-32b".into(),
                    label: "qwen3-32b".into()
                },
            ]
        );
    }

    #[test]
    fn models_from_config_handles_missing_sections() {
        assert!(models_from_config(&json!({})).is_empty());
        assert!(models_from_config(&json!({"llm": {}})).is_empty());
        assert!(models_from_config(&json!({"llm": {"providers": null}})).is_empty());
        // Model entry without an id is skipped.
        let config = json!({"llm": {"providers": {"p": {"models": [{"name": "x"}]}}}});
        assert!(models_from_config(&config).is_empty());
    }

    #[test]
    fn model_entry_serializes_ref_and_label() {
        let entry = ModelEntry {
            ref_: "p/m".into(),
            label: "m".into(),
        };
        let json = serde_json::to_value(&entry).unwrap();
        assert_eq!(json["ref"], "p/m");
        assert_eq!(json["label"], "m");
    }
}
