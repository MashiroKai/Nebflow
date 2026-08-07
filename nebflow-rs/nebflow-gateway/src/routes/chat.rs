//! Chat REST API — mirrors Scala ChatRoutes.scala.
//!
//! POST /api/v1/chat — non-streaming LLM call
//! POST /api/v1/chat/stream — streaming LLM call (SSE)
//!
//! Both require Bearer token authentication.

use axum::extract::State;
use axum::http::HeaderMap;
use axum::response::sse::{Event, KeepAlive, Sse};
use axum::response::{IntoResponse, Json};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::convert::Infallible;

use nebflow_agent::AgentEvent;

use crate::routes::AppState;
use crate::ws::agent_event_to_ws_json;

/// Convert an AgentEvent to JSON for SSE output (reuses the WS format).
fn agent_event_to_json(event: &AgentEvent, session_id: &str) -> Value {
    agent_event_to_ws_json(event, session_id)
}

/// Request body for /api/v1/chat.
#[derive(Debug, Deserialize)]
pub struct ChatRequest {
    pub message: String,
    #[serde(default)]
    pub session_id: Option<String>,
}

/// Response body for /api/v1/chat.
#[derive(Debug, Serialize)]
pub struct ChatResponse {
    pub reply: String,
    pub session_id: String,
}

/// Extract Bearer token from Authorization header.
fn extract_bearer_token(headers: &HeaderMap) -> Option<String> {
    headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .filter(|s| s.starts_with("Bearer "))
        .map(|s| s[7..].to_string())
}

/// POST /api/v1/chat — non-streaming chat.
pub async fn chat_handler(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<ChatRequest>,
) -> Result<Json<ChatResponse>, (axum::http::StatusCode, String)> {
    // Auth
    let token = extract_bearer_token(&headers).unwrap_or_default();
    if !state.auth.validate(&token) {
        return Err((
            axum::http::StatusCode::UNAUTHORIZED,
            "Invalid token".to_string(),
        ));
    }

    // Rate limit
    if !state.rate_limiter.check("api").await {
        return Err((
            axum::http::StatusCode::TOO_MANY_REQUESTS,
            "Rate limit exceeded".to_string(),
        ));
    }

    let session_id = req
        .session_id
        .unwrap_or_else(|| format!("rest-{}", uuid::Uuid::new_v4()));

    // Get agent manager
    let mgr = match &state.agent_manager {
        Some(m) => m.clone(),
        None => {
            return Err((
                axum::http::StatusCode::SERVICE_UNAVAILABLE,
                "Agent runtime unavailable".to_string(),
            ));
        }
    };

    // Get or create the agent handle
    let handle = mgr.get_or_create(&session_id).await;

    // Send user input
    let send_result = {
        let h = handle.lock().await;
        h.send_message(nebflow_agent::AgentMessage::UserInput {
            text: req.message.clone(),
            client_message_id: None,
            blocks: None,
            chat_width: 0,
        })
        .await
    };

    if send_result.is_err() {
        mgr.remove(&session_id).await;
        return Err((
            axum::http::StatusCode::INTERNAL_SERVER_ERROR,
            "Agent is not running".to_string(),
        ));
    }

    // Collect events until Done/Interrupted, accumulating text
    let mut reply_parts: Vec<String> = Vec::new();
    loop {
        let event = {
            let mut guard = handle.lock().await;
            guard.recv().await
        };
        match event {
            Some(AgentEvent::TextDelta { text }) => {
                reply_parts.push(text);
            }
            Some(AgentEvent::Done { .. }) | Some(AgentEvent::Interrupted) => break,
            Some(_) => { /* other events ignored in REST */ }
            None => break, // channel closed
        }
    }

    Ok(Json(ChatResponse {
        reply: reply_parts.join(""),
        session_id,
    }))
}

/// POST /api/v1/chat/stream — streaming chat via SSE.
///
/// Returns a Server-Sent Events stream. Each event is a JSON object
/// matching the WS event format (textDelta, done, etc.).
pub async fn chat_stream_handler(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<ChatRequest>,
) -> Result<axum::response::Response, (axum::http::StatusCode, String)> {
    let token = extract_bearer_token(&headers).unwrap_or_default();
    if !state.auth.validate(&token) {
        return Err((
            axum::http::StatusCode::UNAUTHORIZED,
            "Invalid token".to_string(),
        ));
    }

    // Rate limit
    if !state.rate_limiter.check("api").await {
        return Err((
            axum::http::StatusCode::TOO_MANY_REQUESTS,
            "Rate limit exceeded".to_string(),
        ));
    }

    // Get agent manager
    let mgr = match &state.agent_manager {
        Some(m) => m.clone(),
        None => {
            return Err((
                axum::http::StatusCode::SERVICE_UNAVAILABLE,
                "Agent runtime unavailable".to_string(),
            ));
        }
    };

    let session_id = req
        .session_id
        .unwrap_or_else(|| format!("rest-stream-{}", uuid::Uuid::new_v4()));

    // Get or create the agent handle
    let handle = mgr.get_or_create(&session_id).await;

    // Send user input
    let send_result = {
        let h = handle.lock().await;
        h.send_message(nebflow_agent::AgentMessage::UserInput {
            text: req.message,
            client_message_id: None,
            blocks: None,
            chat_width: 0,
        })
        .await
    };

    if send_result.is_err() {
        mgr.remove(&session_id).await;
        return Err((
            axum::http::StatusCode::INTERNAL_SERVER_ERROR,
            "Agent is not running".to_string(),
        ));
    }

    // Create an SSE stream that drains agent events
    let sid = session_id.clone();
    let stream = async_stream::stream! {
        loop {
            let event = {
                let mut guard = handle.lock().await;
                guard.recv().await
            };
            match event {
                Some(ev) => {
                    let terminal = matches!(ev, AgentEvent::Done { .. } | AgentEvent::Interrupted);
                    let json_val = agent_event_to_json(&ev, &sid);
                    yield Ok::<_, Infallible>(Event::default().data(json_val.to_string()));
                    if terminal {
                        break;
                    }
                }
                None => break,
            }
        }
    };

    Ok(Sse::new(Box::pin(stream))
        .keep_alive(KeepAlive::default())
        .into_response())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agent_manager::AgentManager;
    use crate::auth::Auth;
    use crate::ratelimit::RateLimiter;
    use crate::session::SessionStore;
    use crate::ws_hub::WsHub;
    use nebflow_agent::library::AgentLibrary;
    use nebflow_agent::resources::SharedResources;
    use nebflow_core::config::{NebflowServiceConfig, ThinkingConfig};
    use std::sync::Arc;
    use tempfile::tempdir;

    fn test_config() -> NebflowServiceConfig {
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
        serde_json::from_str(config_json).unwrap()
    }

    fn make_state_with_manager() -> AppState {
        let sessions_dir = tempdir().unwrap();
        let agents_dir = tempdir().unwrap();
        let resources = Arc::new(SharedResources::new(
            test_config(),
            std::path::PathBuf::from("/tmp"),
            ThinkingConfig {
                enabled: false,
                budget_tokens: 0,
            },
        ));
        let library = Arc::new(AgentLibrary::new(agents_dir.path()));
        let store = Arc::new(SessionStore::new(sessions_dir.path().to_path_buf()));
        let manager = Arc::new(AgentManager::new(resources, library, store.clone()));
        AppState::new(
            Arc::new(Auth::with_token("test-token".into())),
            Arc::new(RateLimiter::new()),
            store,
            Arc::new(WsHub::new()),
            "test",
            Some(manager),
            None,
            None,
        )
    }

    fn make_state() -> AppState {
        let dir = tempdir().unwrap();
        AppState::new(
            Arc::new(Auth::with_token("test-token".into())),
            Arc::new(RateLimiter::new()),
            Arc::new(SessionStore::new(dir.path().to_path_buf())),
            Arc::new(WsHub::new()),
            "test",
            None,
            None,
            None,
        )
    }

    #[test]
    fn extract_bearer_token_valid() {
        let mut headers = HeaderMap::new();
        headers.insert("authorization", "Bearer my-token".parse().unwrap());
        let token = extract_bearer_token(&headers);
        assert_eq!(token.as_deref(), Some("my-token"));
    }

    #[test]
    fn extract_bearer_token_missing() {
        let headers = HeaderMap::new();
        let token = extract_bearer_token(&headers);
        assert!(token.is_none());
    }

    #[test]
    fn extract_bearer_token_wrong_scheme() {
        let mut headers = HeaderMap::new();
        headers.insert("authorization", "Basic abc123".parse().unwrap());
        let token = extract_bearer_token(&headers);
        assert!(token.is_none());
    }

    #[tokio::test]
    async fn chat_handler_no_auth_returns_401() {
        let state = make_state();
        let req = ChatRequest {
            message: "hello".into(),
            session_id: None,
        };
        let result = chat_handler(State(state), HeaderMap::new(), Json(req)).await;
        assert!(result.is_err());
        let (status, _) = result.unwrap_err();
        assert_eq!(status, axum::http::StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn chat_handler_no_agent_manager_returns_503() {
        let state = make_state();
        let mut headers = HeaderMap::new();
        headers.insert("authorization", "Bearer test-token".parse().unwrap());
        let req = ChatRequest {
            message: "hello".into(),
            session_id: None,
        };
        let result = chat_handler(State(state), headers, Json(req)).await;
        assert!(result.is_err());
        let (status, _) = result.unwrap_err();
        assert_eq!(status, axum::http::StatusCode::SERVICE_UNAVAILABLE);
    }

    #[tokio::test]
    async fn chat_handler_valid_auth_with_manager() {
        let state = make_state_with_manager();
        let mut headers = HeaderMap::new();
        headers.insert("authorization", "Bearer test-token".parse().unwrap());
        let req = ChatRequest {
            message: "hello".into(),
            session_id: Some("test-chat-1".into()),
        };
        let result = chat_handler(State(state), headers, Json(req)).await;
        // The agent will fail to connect to LLM (localhost:1) but should still
        // emit Done, so the handler returns Ok with an empty or error text reply.
        assert!(result.is_ok());
        let resp = result.unwrap().0;
        assert_eq!(resp.session_id, "test-chat-1");
    }

    #[tokio::test]
    async fn chat_handler_rate_limited() {
        let state = make_state();
        let mut headers = HeaderMap::new();
        headers.insert("authorization", "Bearer test-token".parse().unwrap());

        // Exhaust rate limit (default 60)
        for _ in 0..60 {
            let req = ChatRequest {
                message: "hello".into(),
                session_id: None,
            };
            let _ = chat_handler(State(state.clone()), headers.clone(), Json(req)).await;
        }

        // 61st should fail
        let req = ChatRequest {
            message: "hello".into(),
            session_id: None,
        };
        let result = chat_handler(State(state), headers, Json(req)).await;
        assert!(result.is_err());
        let (status, _) = result.unwrap_err();
        assert_eq!(status, axum::http::StatusCode::TOO_MANY_REQUESTS);
    }

    #[tokio::test]
    async fn chat_stream_handler_no_auth() {
        let state = make_state();
        let req = ChatRequest {
            message: "hello".into(),
            session_id: None,
        };
        let result = chat_stream_handler(State(state), HeaderMap::new(), Json(req)).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn chat_stream_handler_no_manager_returns_503() {
        let state = make_state();
        let mut headers = HeaderMap::new();
        headers.insert("authorization", "Bearer test-token".parse().unwrap());
        let req = ChatRequest {
            message: "hello".into(),
            session_id: None,
        };
        let result = chat_stream_handler(State(state), headers, Json(req)).await;
        assert!(result.is_err());
        let (status, _) = result.unwrap_err();
        assert_eq!(status, axum::http::StatusCode::SERVICE_UNAVAILABLE);
    }

    #[tokio::test]
    async fn chat_stream_handler_with_manager() {
        let state = make_state_with_manager();
        let mut headers = HeaderMap::new();
        headers.insert("authorization", "Bearer test-token".parse().unwrap());
        let req = ChatRequest {
            message: "hello".into(),
            session_id: Some("test-stream-1".into()),
        };
        let result = chat_stream_handler(State(state), headers, Json(req)).await;
        assert!(result.is_ok());
    }
}
