//! E2E: REST /api/v1/chat handler — tests the full HTTP → AgentManager → Agent → response chain.
//!
//! Since there's no real LLM in tests, the agent will emit an error event
//! (no LLM resources or connection refused). We verify the error path
//! completes without hanging and returns a valid ChatResponse.

use nebflow_agent::library::AgentLibrary;
use nebflow_agent::resources::SharedResources;
use nebflow_core::config::{NebflowServiceConfig, ThinkingConfig};
use nebflow_gateway::agent_manager::AgentManager;
use nebflow_gateway::auth::Auth;
use nebflow_gateway::ratelimit::RateLimiter;
use nebflow_gateway::routes::chat::{chat_handler, ChatRequest, ChatResponse};
use nebflow_gateway::routes::AppState;
use nebflow_gateway::session::SessionStore;
use nebflow_gateway::ws_hub::WsHub;
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

fn make_state() -> AppState {
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

fn make_state_no_manager() -> AppState {
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

#[tokio::test]
async fn chat_handler_no_auth_401() {
    let state = make_state();
    let req = ChatRequest {
        message: "hello".into(),
        session_id: None,
    };
    let result = chat_handler(
        axum::extract::State(state),
        axum::http::HeaderMap::new(),
        axum::Json(req),
    )
    .await;
    assert!(result.is_err());
    let (status, _) = result.unwrap_err();
    assert_eq!(status, axum::http::StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn chat_handler_no_manager_503() {
    let state = make_state_no_manager();
    let mut headers = axum::http::HeaderMap::new();
    headers.insert("authorization", "Bearer test-token".parse().unwrap());
    let req = ChatRequest {
        message: "hello".into(),
        session_id: None,
    };
    let result = chat_handler(axum::extract::State(state), headers, axum::Json(req)).await;
    assert!(result.is_err());
    let (status, _) = result.unwrap_err();
    assert_eq!(status, axum::http::StatusCode::SERVICE_UNAVAILABLE);
}

#[tokio::test]
async fn chat_handler_with_manager_returns_response() {
    let state = make_state();
    let mut headers = axum::http::HeaderMap::new();
    headers.insert("authorization", "Bearer test-token".parse().unwrap());
    let req = ChatRequest {
        message: "hello".into(),
        session_id: Some("e2e-chat-1".into()),
    };

    // The agent will fail (localhost:1 unreachable) but should emit Done
    // and the handler should return a valid ChatResponse.
    let result = tokio::time::timeout(
        std::time::Duration::from_secs(10),
        chat_handler(axum::extract::State(state), headers, axum::Json(req)),
    )
    .await;

    assert!(result.is_ok(), "chat_handler should not hang");
    let inner = result.unwrap();
    assert!(inner.is_ok(), "should return Ok response");
    let resp: ChatResponse = inner.unwrap().0;
    assert_eq!(resp.session_id, "e2e-chat-1");
}

#[tokio::test]
async fn chat_handler_same_session_reuses_agent() {
    let state = make_state();
    let mut headers = axum::http::HeaderMap::new();
    headers.insert("authorization", "Bearer test-token".parse().unwrap());

    // First request
    let req1 = ChatRequest {
        message: "hello".into(),
        session_id: Some("e2e-reuse".into()),
    };
    let result1 = chat_handler(
        axum::extract::State(state.clone()),
        headers.clone(),
        axum::Json(req1),
    )
    .await;
    assert!(result1.is_ok());

    // Second request with same session — should reuse the agent
    let req2 = ChatRequest {
        message: "world".into(),
        session_id: Some("e2e-reuse".into()),
    };
    let result2 = chat_handler(axum::extract::State(state), headers, axum::Json(req2)).await;
    assert!(result2.is_ok());
    let resp2: ChatResponse = result2.unwrap().0;
    assert_eq!(resp2.session_id, "e2e-reuse");
}
