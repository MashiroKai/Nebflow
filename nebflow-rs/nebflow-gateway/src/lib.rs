//! nebflow-gateway — HTTP/WebSocket gateway layer (Phase 5).
//!
//! Implements the axum-based HTTP server, WebSocket routes, session management,
//! authentication, and rate limiting. This is the entry point for all client
//! connections (browser, CLI, bridges).

pub mod agent_manager;
pub mod auth;
pub mod config;
pub mod ratelimit;
pub mod routes;
pub mod server;
pub mod session;
pub mod stt_service;
pub mod team_loader;
pub mod tts_service;
pub mod ws;
pub mod ws_hub;

pub use agent_manager::AgentManager;
pub use auth::Auth;
pub use config::GatewayConfig;
pub use ratelimit::RateLimiter;
pub use routes::AppState;
pub use server::run_server;
pub use session::SessionStore;
pub use stt_service::SttService;
pub use team_loader::{MountedTeam, TeamLoader};
pub use tts_service::TtsService;
pub use ws_hub::WsHub;
