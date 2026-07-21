mod model;
mod routes;
mod store;

use axum::{routing::{delete, get, post}, Router};
use std::time::Duration;

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

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
        .route("/api/network/create", post(routes::create_network))
        .route("/api/device/login", post(routes::login))
        .route("/api/device/heartbeat", post(routes::heartbeat))
        .route("/api/device/peers", get(routes::get_peers))
        .route("/api/device/endpoints", post(routes::update_endpoints))
        .route("/api/device/logout", delete(routes::logout))
        .route("/api/health", get(routes::health))
        .with_state(store);

    let listener = tokio::net::TcpListener::bind("0.0.0.0:9090").await.unwrap();
    tracing::info!("Nebflow Coordinator started on :9090");
    axum::serve(listener, app).await.unwrap();
}
