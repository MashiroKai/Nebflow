//! Server startup — mirrors Scala GatewayMain.scala server bootstrap.
//!
//! Starts the axum HTTP server with WebSocket support.

use std::sync::Arc;
use tracing::info;

use nebflow_agent::library::AgentLibrary;
use nebflow_agent::resources::SharedResources;
use nebflow_core::config::{data_root, load_service_config, ThinkingConfig};

use crate::agent_manager::AgentManager;
use crate::auth::Auth;
use crate::config::GatewayConfig;
use crate::ratelimit::RateLimiter;
use crate::routes::{build_router, AppState};
use crate::session::SessionStore;
use crate::ws_hub::WsHub;

/// Run the gateway server.
///
/// Loads config, initializes auth/session-store/rate-limiter/ws-hub,
/// builds the router, and binds the HTTP server. Blocks until shutdown.
pub async fn run_server(config: GatewayConfig) -> Result<(), std::io::Error> {
    let addr = config.socket_addr();
    info!("Starting nebflow gateway on {}", addr);

    // Initialize auth (load or create token)
    let auth =
        Arc::new(Auth::new().map_err(|e| std::io::Error::other(format!("Auth init failed: {e}")))?);
    info!("Gateway token loaded (auth.json)");

    // Initialize session store
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    let sessions_dir = std::path::PathBuf::from(&home)
        .join(".nebflow")
        .join("sessions");
    let session_store = Arc::new(SessionStore::new(sessions_dir));
    session_store
        .load()
        .await
        .map_err(|e| std::io::Error::other(format!("SessionStore load failed: {e}")))?;
    info!("Session store loaded");

    // Initialize rate limiter
    let rate_limiter = Arc::new(RateLimiter::new());

    // Initialize WS hub
    let ws_hub = Arc::new(WsHub::new());

    // Initialize agent runtime (LLM resources + agent library + manager).
    // Config load failures fall back to defaults so the gateway still starts.
    let root = data_root();
    let service_config = load_service_config();
    let thinking = service_config
        .thinking_config
        .clone()
        .unwrap_or(ThinkingConfig {
            enabled: true,
            budget_tokens: 32_000,
        });
    let resources = Arc::new(SharedResources::new(service_config, root.clone(), thinking));
    let library = Arc::new(AgentLibrary::new(root.join("agents")));
    let agent_manager = Some(Arc::new(AgentManager::new(
        resources,
        library,
        session_store.clone(),
    )));
    info!("Agent runtime initialized");

    // Initialize STT/TTS services (None when config files are absent).
    let stt_service = crate::stt_service::SttService::create().map(Arc::new);
    let tts_service = crate::tts_service::TtsService::create().map(Arc::new);
    if stt_service.is_some() {
        info!("STT service loaded");
    }
    if tts_service.is_some() {
        info!("TTS service loaded");
    }

    // Build app state
    let state = AppState::new(
        auth,
        rate_limiter,
        session_store,
        ws_hub,
        "0.1.0-rust",
        agent_manager,
        stt_service,
        tts_service,
    );

    // Build router
    let app = build_router(state);

    // Bind and serve
    let listener = tokio::net::TcpListener::bind(&addr).await?;
    info!("Gateway listening on http://{}", addr);
    info!(
        "Access URL: http://localhost:{} (token in ~/.nebflow/.token)",
        config.port
    );

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await?;

    info!("Gateway shutdown complete");
    Ok(())
}

/// Wait for Ctrl+C or SIGTERM.
async fn shutdown_signal() {
    let ctrl_c = async {
        tokio::signal::ctrl_c()
            .await
            .expect("failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("failed to install signal handler")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gateway_config_socket_addr() {
        let cfg = GatewayConfig::default().with_port(0);
        let addr = cfg.socket_addr();
        assert_eq!(addr.port(), 0);
    }
}
