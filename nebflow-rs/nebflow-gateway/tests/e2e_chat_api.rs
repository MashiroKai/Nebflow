//! E2E: REST /api/v1/chat endpoint — HTTP-level integration tests.
//!
//! Tests the full HTTP request → router → chat_handler → AgentManager
//! pipeline using axum's tower::ServiceExt::oneshot.

use std::path::PathBuf;
use std::sync::Arc;

use axum::body::Body;
use axum::http::{Request, StatusCode};
use tower::ServiceExt;

use nebflow_agent::library::AgentLibrary;
use nebflow_agent::resources::SharedResources;
use nebflow_core::config::{NebflowServiceConfig, ThinkingConfig};
use nebflow_gateway::agent_manager::AgentManager;
use nebflow_gateway::routes::{build_router, AppState};
use nebflow_gateway::{Auth, RateLimiter, SessionStore, WsHub};

fn test_config() -> NebflowServiceConfig {
    let json = r#"{
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
    serde_json::from_str(json).unwrap()
}

fn make_state_no_manager() -> AppState {
    let dir = tempfile::tempdir().unwrap();
    AppState::new(
        Arc::new(Auth::with_token("test-token".into())),
        Arc::new(RateLimiter::new()),
        Arc::new(SessionStore::new(dir.path().to_path_buf())),
        Arc::new(WsHub::new()),
        "test",
        None, // no agent_manager
        None,
        None,
    )
}

fn make_state_with_manager() -> AppState {
    let sessions_dir = tempfile::tempdir().unwrap();
    let agents_dir = tempfile::tempdir().unwrap();

    let resources = Arc::new(SharedResources::new(
        test_config(),
        PathBuf::from("/tmp"),
        ThinkingConfig {
            enabled: false,
            budget_tokens: 0,
        },
    ));
    let library = Arc::new(AgentLibrary::new(agents_dir.path()));
    let store = Arc::new(SessionStore::new(sessions_dir.path().to_path_buf()));

    let manager = Arc::new(AgentManager::new(resources, library, store));

    AppState::new(
        Arc::new(Auth::with_token("test-token".into())),
        Arc::new(RateLimiter::new()),
        Arc::new(SessionStore::new(sessions_dir.path().to_path_buf())),
        Arc::new(WsHub::new()),
        "test",
        Some(manager),
        None,
        None,
    )
}

async fn send_chat_request(
    state: AppState,
    token: Option<&str>,
    body: &str,
) -> (StatusCode, String) {
    let app = build_router(state);
    let mut req = Request::builder()
        .method("POST")
        .uri("/api/v1/chat")
        .header("content-type", "application/json");

    if let Some(t) = token {
        req = req.header("authorization", format!("Bearer {t}"));
    }

    let response = app
        .oneshot(req.body(Body::from(body.to_string())).unwrap())
        .await
        .unwrap();

    let status = response.status();
    let body_bytes = axum::body::to_bytes(response.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let body_str = String::from_utf8_lossy(&body_bytes).to_string();
    (status, body_str)
}

#[tokio::test]
async fn chat_no_auth_returns_401() {
    let state = make_state_no_manager();
    let (status, _) = send_chat_request(state, None, r#"{"message":"hello"}"#).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn chat_invalid_token_returns_401() {
    let state = make_state_no_manager();
    let (status, _) = send_chat_request(state, Some("wrong-token"), r#"{"message":"hello"}"#).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn chat_no_agent_manager_returns_503() {
    let state = make_state_no_manager();
    let (status, body) =
        send_chat_request(state, Some("test-token"), r#"{"message":"hello"}"#).await;
    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
    assert!(
        body.contains("agent") || body.contains("503") || body.contains("unavailable"),
        "body should mention agent unavailability: {body}"
    );
}

#[tokio::test]
async fn chat_with_manager_returns_200() {
    let state = make_state_with_manager();
    let (status, body) =
        send_chat_request(state, Some("test-token"), r#"{"message":"hello"}"#).await;

    // With a manager, the handler should return 200.
    // The agent will fail (localhost:1 unreachable), but the handler
    // collects the error text as the reply.
    assert_eq!(status, StatusCode::OK, "body: {body}");

    // Parse response JSON to verify structure.
    let resp: serde_json::Value = serde_json::from_str(&body).unwrap();
    assert!(
        resp.get("reply").is_some(),
        "response should have 'reply' field"
    );
    assert!(
        resp.get("session_id").is_some(),
        "response should have 'session_id' field"
    );
}

#[tokio::test]
async fn chat_with_custom_session_id() {
    let state = make_state_with_manager();
    let body = r#"{"message":"hello","session_id":"custom-session-123"}"#;
    let (status, response_body) = send_chat_request(state, Some("test-token"), body).await;
    assert_eq!(status, StatusCode::OK, "body: {response_body}");

    let resp: serde_json::Value = serde_json::from_str(&response_body).unwrap();
    let session_id = resp["session_id"].as_str().unwrap();
    assert!(
        session_id.contains("custom-session-123"),
        "session_id should contain the custom ID: {session_id}"
    );
}

#[tokio::test]
async fn chat_health_endpoint_works() {
    // Verify the router serves other endpoints too.
    let state = make_state_no_manager();
    let app = build_router(state);
    let response = app
        .oneshot(
            Request::builder()
                .method("GET")
                .uri("/api/health")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
}
