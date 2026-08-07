//! Route handlers — HTTP and WebSocket routes for the gateway.
//!
//! Backend handles: chat, health, WS, static files, sessions, config,
//! agents, skills, models.

pub mod chat;
pub mod health;

// Stub modules — Frontend agent will implement these.
pub mod agents;
pub mod config;
pub mod models;
pub mod sessions;
pub mod skills;
pub mod speech;
pub mod static_files;

use std::sync::Arc;

use axum::routing::{get, post};
use axum::Json;
use axum::Router;

use crate::auth::Auth;
use crate::ratelimit::RateLimiter;
use crate::session::SessionStore;
use crate::ws_hub::WsHub;

/// Shared application state — available to all route handlers.
#[derive(Clone)]
pub struct AppState {
    pub auth: Arc<Auth>,
    pub rate_limiter: Arc<RateLimiter>,
    pub session_store: Arc<SessionStore>,
    pub ws_hub: Arc<WsHub>,
    pub version: String,
    /// Live agent registry — None in tests/minimal deployments without an
    /// LLM backend.
    pub agent_manager: Option<Arc<crate::agent_manager::AgentManager>>,
    /// STT service — None when `~/.nebflow/stt-config.json` is absent.
    pub stt_service: Option<Arc<crate::stt_service::SttService>>,
    /// TTS service — None when `~/.nebflow/tts-config.json` is absent.
    pub tts_service: Option<Arc<crate::tts_service::TtsService>>,
}

impl AppState {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        auth: Arc<Auth>,
        rate_limiter: Arc<RateLimiter>,
        session_store: Arc<SessionStore>,
        ws_hub: Arc<WsHub>,
        version: &str,
        agent_manager: Option<Arc<crate::agent_manager::AgentManager>>,
        stt_service: Option<Arc<crate::stt_service::SttService>>,
        tts_service: Option<Arc<crate::tts_service::TtsService>>,
    ) -> Self {
        Self {
            auth,
            rate_limiter,
            session_store,
            ws_hub,
            version: version.to_string(),
            agent_manager,
            stt_service,
            tts_service,
        }
    }
}

/// Build the full application router.
pub fn build_router(state: AppState) -> Router {
    let ws_state = crate::ws::WsState {
        auth: state.auth.clone(),
        rate_limiter: state.rate_limiter.clone(),
        session_store: state.session_store.clone(),
        ws_hub: state.ws_hub.clone(),
        agent_manager: state.agent_manager.clone(),
    };

    Router::new()
        // WebSocket endpoint
        .route("/ws", get(crate::ws::ws_handler))
        // REST API
        .route("/api/health", get(health::health_handler))
        .route("/api/v1/chat", post(chat::chat_handler))
        .route("/api/v1/chat/stream", post(chat::chat_stream_handler))
        // Sessions REST
        .route("/api/sessions", get(sessions::list_sessions))
        .route("/api/sessions", post(sessions::create_session))
        .route(
            "/api/sessions/{id}",
            axum::routing::delete(sessions::delete_session),
        )
        .route("/api/sessions/{id}/history", get(sessions::get_history))
        // Config REST
        .route("/api/config", get(config::get_config))
        .route(
            "/api/config",
            axum::routing::patch(config::update_config).put(config::update_config),
        )
        // Agents REST
        .route("/api/agents", get(agents::list_agents))
        .route("/api/agents/manifest.json", get(agents::manifest))
        .route("/api/agents/{name}", get(agents::get_agent))
        // Skills REST
        .route("/api/skills", get(skills::list_skills))
        // Models REST
        .route("/api/models", get(models::list_models))
        .route(
            "/api/models/capabilities",
            get(|| async { Json(serde_json::json!({})) }),
        )
        // NebLink status (no P2P transport yet — always disconnected)
        .route(
            "/api/neblink/status",
            get(|| async { Json(serde_json::json!({ "connected": false, "peers": [] })) }),
        )
        // Teams + flows REST
        .route(
            "/api/teams/mounted",
            get(|| async {
                let data_root = nebflow_core::config::data_root();
                let teams_dir = data_root.join("teams");
                let mut teams: Vec<serde_json::Value> = vec![];
                if let Ok(entries) = std::fs::read_dir(&teams_dir) {
                    for entry in entries.flatten() {
                        let team_json = entry.path().join("team.json");
                        if let Ok(content) = std::fs::read_to_string(&team_json) {
                            if let Ok(t) = serde_json::from_str::<serde_json::Value>(&content) {
                                teams.push(t);
                            }
                        }
                    }
                }
                Json(serde_json::json!({ "teams": teams }))
            }),
        )
        .route(
            "/api/running-flows",
            get(|| async { Json(serde_json::json!({ "flows": [] })) }),
        )
        // Speech REST (no auth — internal calls, mirrors Scala)
        .route("/api/tts", post(speech::tts_handler))
        .route("/api/stt", post(speech::stt_handler))
        // Static files (served by static_files module)
        .fallback(static_files::static_handler)
        .with_state(state)
        .layer(axum::Extension(ws_state))
        .layer(tower_http::cors::CorsLayer::permissive())
}
