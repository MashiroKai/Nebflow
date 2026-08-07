//! Config REST API — mirrors Scala ConfigService + RestApiRoutes config endpoints.
//!
//! GET   /api/config — returns raw config JSON + `configured` flag
//! PATCH /api/config — validate + deep-merge update, writes ~/.nebflow/nebflow.json
//! PUT   /api/config — alias of PATCH (some frontends prefer PUT)

use axum::extract::State;
use axum::http::HeaderMap;
use axum::response::Json;
use serde_json::Value;

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

/// Sensitive keys whose empty/blank incoming values must not overwrite the
/// existing secret (mirrors Scala `sensitiveKeyPattern`).
fn is_sensitive_key(key: &str) -> bool {
    let k = key.to_ascii_lowercase().replace('-', "_");
    k.contains("api_key")
        || k.contains("apikey")
        || k.contains("secret")
        || k.contains("token")
        || k.contains("password")
}

/// Whether the config file has at least one LLM provider configured
/// (mirrors Scala `ConfigService.isConfigured`).
pub fn is_configured(config: &Value) -> bool {
    config
        .get("llm")
        .and_then(|llm| llm.get("providers"))
        .and_then(|p| p.as_object())
        .is_some_and(|providers| !providers.is_empty())
}

/// Validate a config JSON value — returns a list of human-readable errors.
/// Empty list means valid. Mirrors Scala `ConfigService.validateConfig`.
pub fn validate_config(json: &Value) -> Vec<String> {
    let mut errors = Vec::new();
    let llm = json.get("llm");
    let providers = llm
        .and_then(|l| l.get("providers"))
        .and_then(|p| p.as_object());

    // Check providers
    if let Some(providers) = providers {
        for (name, p_json) in providers {
            let base_url = p_json.get("baseUrl").and_then(Value::as_str).unwrap_or("");
            let protocol = p_json.get("protocol").and_then(Value::as_str).unwrap_or("");
            let models = p_json
                .get("models")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default();
            if base_url.trim().is_empty() {
                errors.push(format!("Provider '{name}': Base URL is required"));
            }
            if protocol.is_empty() {
                errors.push(format!("Provider '{name}': Protocol is required"));
            } else if protocol != "anthropic" && protocol != "openai" {
                errors.push(format!(
                    "Provider '{name}': Unknown protocol '{protocol}', must be 'anthropic' or 'openai'"
                ));
            }
            if models.is_empty() {
                errors.push(format!("Provider '{name}': At least one model is required"));
            } else {
                for (idx, m) in models.iter().enumerate() {
                    let mid = m.get("id").and_then(Value::as_str).unwrap_or("");
                    if mid.trim().is_empty() {
                        errors.push(format!(
                            "Provider '{name}': Model #{} has empty id",
                            idx + 1
                        ));
                    }
                }
            }
        }
    }

    // Check model chain
    if let Some(model_json) = llm.and_then(|l| l.get("model")) {
        let provider_known = |pid: &str| providers.is_some_and(|ps| ps.contains_key(pid));
        if let Some(default) = model_json.get("default").and_then(Value::as_str) {
            if !default.is_empty() {
                match default.split_once('/') {
                    None => errors.push(format!(
                        "Default model '{default}' is invalid — expected 'providerId/modelId' format"
                    )),
                    Some((pid, _)) if !provider_known(pid) => errors.push(format!(
                        "Default model '{default}' points to unknown provider '{pid}'"
                    )),
                    _ => {}
                }
            }
        }
        if let Some(fallbacks) = model_json.get("fallbacks").and_then(Value::as_array) {
            for fb in fallbacks {
                if let Some(fb_ref) = fb.as_str() {
                    match fb_ref.split_once('/') {
                        None => errors.push(format!(
                            "Fallback model '{fb_ref}' is invalid — expected 'providerId/modelId' format"
                        )),
                        Some((pid, _)) if !provider_known(pid) => errors.push(format!(
                            "Fallback model '{fb_ref}' points to unknown provider '{pid}'"
                        )),
                        _ => {}
                    }
                }
            }
        }
    }

    errors
}

/// Deep-merge incoming config into existing (mirrors Scala `mergeConfig`):
/// - keys only in existing → preserved
/// - incoming `null` → key deleted
/// - both objects → recursive merge
/// - leaf `"***"` → keep existing (secret redaction)
/// - empty string on a sensitive key → keep existing
fn merge_config(existing: &Value, incoming: &Value, key_chain: &mut Vec<String>) -> Value {
    match (existing.as_object(), incoming.as_object()) {
        (Some(e_obj), Some(i_obj)) => {
            let mut merged = e_obj.clone();
            for (key, i_val) in i_obj {
                if i_val.is_null() {
                    merged.remove(key);
                } else {
                    let e_val = merged.get(key).cloned().unwrap_or(Value::Null);
                    key_chain.push(key.clone());
                    let merged_val = merge_config(&e_val, i_val, key_chain);
                    key_chain.pop();
                    merged.insert(key.clone(), merged_val);
                }
            }
            Value::Object(merged)
        }
        _ => {
            let current_key = key_chain.last().cloned().unwrap_or_default();
            match incoming.as_str() {
                Some("***") => existing.clone(),
                Some(s) if s.is_empty() && is_sensitive_key(&current_key) => existing.clone(),
                _ => incoming.clone(),
            }
        }
    }
}

/// GET /api/config — returns `{ config: <raw json>, configured: bool }`
/// (Scala-compatible shape).
pub async fn get_config(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Value>, (axum::http::StatusCode, String)> {
    if !check_auth(&state, &headers) {
        return Err((axum::http::StatusCode::UNAUTHORIZED, "Invalid token".into()));
    }
    let raw = nebflow_core::config::read_raw_config(&nebflow_core::config::default_config_path());
    let config: Value = serde_json::from_str(&raw).unwrap_or_else(|_| serde_json::json!({}));
    Ok(Json(serde_json::json!({
        "config": config,
        "configured": is_configured(&config),
        "version": state.version,
    })))
}

/// PATCH/PUT /api/config — validate and deep-merge a config update.
///
/// Accepts either `{ "config": <object-or-json-string> }` or a bare config
/// object. On success writes ~/.nebflow/nebflow.json and returns
/// `{ updated: true }`; on validation failure returns 400 with
/// `{ error: "..." }`.
pub async fn update_config(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<Value>,
) -> Result<Json<Value>, (axum::http::StatusCode, String)> {
    if !check_auth(&state, &headers) {
        return Err((axum::http::StatusCode::UNAUTHORIZED, "Invalid token".into()));
    }

    // Unwrap: body may be { config: ... } or the config object itself.
    let incoming = match body.get("config") {
        Some(Value::String(s)) => match serde_json::from_str::<Value>(s) {
            Ok(v) => v,
            Err(e) => {
                return Err((
                    axum::http::StatusCode::BAD_REQUEST,
                    format!("Invalid JSON: {e}"),
                ))
            }
        },
        Some(v) => v.clone(),
        None => body,
    };

    let errors = validate_config(&incoming);
    if !errors.is_empty() {
        return Err((axum::http::StatusCode::BAD_REQUEST, errors.join("; ")));
    }

    let path = nebflow_core::config::default_config_path();
    let existing_raw = nebflow_core::config::read_raw_config(&path);
    let existing: Value =
        serde_json::from_str(&existing_raw).unwrap_or_else(|_| serde_json::json!({}));
    let merged = merge_config(&existing, &incoming, &mut Vec::new());

    let write_result = tokio::task::spawn_blocking(move || {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let json = serde_json::to_string_pretty(&merged).map_err(std::io::Error::other)?;
        std::fs::write(&path, json)
    })
    .await
    .map_err(|e| (axum::http::StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    match write_result {
        Ok(()) => Ok(Json(serde_json::json!({ "updated": true }))),
        Err(e) => Err((axum::http::StatusCode::INTERNAL_SERVER_ERROR, e.to_string())),
    }
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

    fn make_state(version: &str) -> AppState {
        let dir = tempdir().unwrap();
        AppState::new(
            Arc::new(Auth::with_token("t".into())),
            Arc::new(RateLimiter::new()),
            Arc::new(SessionStore::new(dir.path().to_path_buf())),
            Arc::new(WsHub::new()),
            version,
            None,
            None,
            None,
        )
    }

    fn auth_headers() -> HeaderMap {
        let mut h = HeaderMap::new();
        h.insert("authorization", "Bearer t".parse().unwrap());
        h
    }

    #[tokio::test]
    async fn get_config_no_auth() {
        let state = make_state("test");
        let result = get_config(State(state), HeaderMap::new()).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn get_config_with_auth() {
        let state = make_state("1.0.0");
        let result = get_config(State(state), auth_headers()).await;
        assert!(result.is_ok());
        let json = result.unwrap().0;
        assert_eq!(json["version"], "1.0.0");
        assert!(json.get("config").is_some());
        assert!(json.get("configured").is_some());
    }

    #[tokio::test]
    async fn update_config_no_auth() {
        let state = make_state("test");
        let result =
            update_config(State(state), HeaderMap::new(), Json(serde_json::json!({}))).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn update_config_rejects_invalid() {
        let state = make_state("test");
        let body = serde_json::json!({
            "llm": { "providers": { "p1": { "baseUrl": "", "protocol": "weird" } } }
        });
        let result = update_config(State(state), auth_headers(), Json(body)).await;
        assert!(result.is_err());
        let (status, msg) = result.unwrap_err();
        assert_eq!(status, axum::http::StatusCode::BAD_REQUEST);
        assert!(msg.contains("p1"));
    }

    #[test]
    fn validate_ok_config() {
        let cfg = serde_json::json!({
            "llm": {
                "providers": {
                    "anthropic": {
                        "baseUrl": "https://api.anthropic.com",
                        "protocol": "anthropic",
                        "models": [{ "id": "claude-sonnet-4-6" }]
                    }
                },
                "model": { "default": "anthropic/claude-sonnet-4-6" }
            }
        });
        assert!(validate_config(&cfg).is_empty());
    }

    #[test]
    fn validate_bad_default_ref() {
        let cfg = serde_json::json!({
            "llm": {
                "providers": {
                    "a": { "baseUrl": "https://x", "protocol": "openai", "models": [{ "id": "m" }] }
                },
                "model": { "default": "nope/m", "fallbacks": ["bad-format"] }
            }
        });
        let errors = validate_config(&cfg);
        assert_eq!(errors.len(), 2);
    }

    #[test]
    fn merge_preserves_and_deletes() {
        let existing = serde_json::json!({
            "llm": { "providers": { "a": { "apiKey": "real-secret", "baseUrl": "u" } }, "model": { "default": "a/m" } },
            "keep": 1
        });
        let incoming = serde_json::json!({
            "llm": { "providers": { "a": { "apiKey": "***" } }, "model": null }
        });
        let merged = merge_config(&existing, &incoming, &mut Vec::new());
        assert_eq!(merged["keep"], 1);
        assert_eq!(merged["llm"]["providers"]["a"]["apiKey"], "real-secret");
        assert!(merged["llm"].get("model").is_none());
    }

    #[test]
    fn merge_empty_sensitive_string_preserves() {
        let existing = serde_json::json!({ "apiKey": "real" });
        let incoming = serde_json::json!({ "apiKey": "" });
        let merged = merge_config(&existing, &incoming, &mut Vec::new());
        assert_eq!(merged["apiKey"], "real");
    }

    #[test]
    fn is_configured_check() {
        assert!(!is_configured(&serde_json::json!({})));
        assert!(!is_configured(
            &serde_json::json!({"llm": {"providers": {}}})
        ));
        assert!(is_configured(
            &serde_json::json!({"llm": {"providers": {"a": {}}}})
        ));
    }
}
