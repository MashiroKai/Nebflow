//! Health check endpoint — mirrors Scala RestApiRoutes /health.

use axum::extract::State;
use axum::response::Json;
use serde_json::json;

use crate::routes::AppState;

/// GET /api/health — returns server status.
pub async fn health_handler(State(state): State<AppState>) -> Json<serde_json::Value> {
    let conn_count = state.ws_hub.connection_count().await;
    Json(json!({
        "status": "ok",
        "version": state.version,
        "connections": conn_count,
    }))
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

    #[tokio::test]
    async fn health_returns_ok() {
        let dir = tempdir().unwrap();
        let state = AppState::new(
            Arc::new(Auth::with_token("test".into())),
            Arc::new(RateLimiter::new()),
            Arc::new(SessionStore::new(dir.path().to_path_buf())),
            Arc::new(WsHub::new()),
            "1.0.0-test",
            None,
            None,
            None,
        );
        let result = health_handler(State(state)).await;
        let json = result.0;
        assert_eq!(json["status"], "ok");
        assert_eq!(json["version"], "1.0.0-test");
    }
}
