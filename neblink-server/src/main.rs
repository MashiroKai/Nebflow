mod auth;
mod model;
mod routes;
mod store;

use axum::{routing::{delete, get, post}, Router};
use std::time::Duration;
use tower_http::catch_panic::CatchPanicLayer;
use tower_http::trace::TraceLayer;

const VERSION: &str = env!("CARGO_PKG_VERSION");

#[tokio::main]
async fn main() {
    // Init logging — respects RUST_LOG env var, defaults to "info"
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    let port: u16 = std::env::var("NEBLINK_SERVER_PORT")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(9090);

    let store = std::sync::Arc::new(store::Store::new());

    // Background cleanup: remove stale devices every 30s (90s timeout)
    {
        let store = store.clone();
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_secs(30));
            loop {
                interval.tick().await;
                let removed = store.purge_stale(Duration::from_secs(90));
                if !removed.is_empty() {
                    tracing::info!("Purged stale devices: {}", removed.join(", "));
                }
            }
        });
    }

    let app = Router::new()
        // ===== User auth =====
        .route("/api/user/me", get(routes::user_me))
        // ===== Network management (requires user JWT) =====
        .route("/api/network/create", post(routes::create_network))
        .route("/api/network/list", get(routes::list_networks))
        .route("/api/network/{network_id}", delete(routes::delete_network))
        .route("/api/network/{network_id}/rotate", post(routes::rotate_secret))
        .route("/api/network/{network_id}/devices", get(routes::list_devices))
        .route(
            "/api/network/{network_id}/devices/{device_id}",
            delete(routes::revoke_device),
        )
        // ===== Device session endpoints =====
        .route("/api/device/login", post(routes::login))
        .route("/api/device/heartbeat", post(routes::heartbeat))
        .route("/api/device/peers", get(routes::get_peers))
        .route("/api/device/endpoints", post(routes::update_endpoints))
        .route("/api/device/logout", delete(routes::logout))
        // ===== Health =====
        .route("/api/health", get(routes::health))
        .route("/api/info", get(info))
        // ===== Web UI =====
        .route("/", get(web_ui))
        // Catch panics in handlers — return 500 instead of crashing the connection
        .layer(CatchPanicLayer::new())
        // Request tracing — logs each request with method, path, status, latency
        .layer(TraceLayer::new_for_http())
        .with_state(store);

    let addr = format!("0.0.0.0:{port}");
    let listener = match tokio::net::TcpListener::bind(&addr).await {
        Ok(l) => l,
        Err(e) => {
            tracing::error!("Failed to bind {addr}: {e}");
            std::process::exit(1);
        }
    };

    tracing::info!("NebLink Server v{VERSION} started on :{port}");

    // Graceful shutdown: handle Ctrl+C / SIGTERM / service stop
    // NSSM sends Ctrl+Break on Windows; tokio::signal::ctrl_c handles Ctrl+C
    let shutdown = async {
        #[cfg(unix)]
        {
            use tokio::signal::unix::{signal, SignalKind};
            let mut term = signal(SignalKind::terminate()).expect("install TERM handler");
            tokio::select! {
                _ = tokio::signal::ctrl_c() => {}
                _ = term.recv() => {}
            }
        }
        #[cfg(not(unix))]
        {
            let _ = tokio::signal::ctrl_c().await;
        }
        tracing::info!("Shutdown signal received, draining connections...");
    };

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown)
        .await
        .unwrap_or_else(|e| {
            tracing::error!("Server error: {e}");
        });

    tracing::info!("Server stopped.");
}

/// Returns version and configuration info.
async fn info() -> axum::Json<serde_json::Value> {
    axum::Json(serde_json::json!({
        "name": "neblink-server",
        "version": VERSION,
        "port": std::env::var("NEBLINK_SERVER_PORT").unwrap_or_else(|_| "9090".to_string()),
    }))
}

/// Serve the web UI (embedded in binary at compile time).
async fn web_ui() -> axum::response::Html<&'static str> {
    axum::response::Html(include_str!("../static/index.html"))
}
