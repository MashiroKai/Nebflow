//! Sessions REST API — mirrors Scala RestApiRoutes session endpoints.
//!
//! GET    /api/sessions           — list all sessions + activeId
//! POST   /api/sessions           — create a new session
//! DELETE /api/sessions/:id       — delete a session
//! GET    /api/sessions/:id/history — get session history (paginated)

use axum::extract::{Path, Query, State};
use axum::http::HeaderMap;
use axum::response::Json;
use serde::Deserialize;
use serde_json::Value;

use crate::routes::AppState;

/// Extract Bearer token from headers.
fn extract_bearer_token(headers: &HeaderMap) -> Option<String> {
    headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .filter(|s| s.starts_with("Bearer "))
        .map(|s| s[7..].to_string())
}

/// Check auth — returns true if authorized.
fn check_auth(state: &AppState, headers: &HeaderMap) -> bool {
    let token = extract_bearer_token(headers).unwrap_or_default();
    state.auth.validate(&token)
}

/// GET /api/sessions — list all sessions with the active session id.
///
/// Response matches the Scala gateway: `{ sessions: [...], activeId: "..." }`.
pub async fn list_sessions(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Value>, (axum::http::StatusCode, String)> {
    if !check_auth(&state, &headers) {
        return Err((axum::http::StatusCode::UNAUTHORIZED, "Invalid token".into()));
    }
    let sessions = state.session_store.list_sessions().await;
    let active_id = state.session_store.get_active_id().await;
    Ok(Json(serde_json::json!({
        "sessions": sessions,
        "activeId": active_id,
    })))
}

/// Request body for creating a session.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateSessionBody {
    #[serde(default = "default_session_name")]
    pub name: String,
    #[serde(default)]
    pub agent_name: Option<String>,
    #[serde(default)]
    pub folder_id: Option<String>,
}

fn default_session_name() -> String {
    "New Session".into()
}

/// POST /api/sessions — create a new session.
///
/// Accepts `{ name, agentName?, folderId? }` (camelCase, matching the Scala
/// gateway). Returns the created `SessionMeta`.
pub async fn create_session(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<CreateSessionBody>,
) -> Result<Json<Value>, (axum::http::StatusCode, String)> {
    if !check_auth(&state, &headers) {
        return Err((axum::http::StatusCode::UNAUTHORIZED, "Invalid token".into()));
    }
    match state
        .session_store
        .create_session(&body.name, body.agent_name, body.folder_id)
        .await
    {
        Ok(meta) => Ok(Json(serde_json::to_value(meta).unwrap_or(Value::Null))),
        Err(e) => Err((axum::http::StatusCode::INTERNAL_SERVER_ERROR, e.to_string())),
    }
}

/// DELETE /api/sessions/:id — delete a session.
pub async fn delete_session(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<Value>, (axum::http::StatusCode, String)> {
    if !check_auth(&state, &headers) {
        return Err((axum::http::StatusCode::UNAUTHORIZED, "Invalid token".into()));
    }
    match state.session_store.delete_session(&id).await {
        Ok(true) => Ok(Json(serde_json::json!({ "deleted": true }))),
        Ok(false) => Err((
            axum::http::StatusCode::NOT_FOUND,
            "Session not found".into(),
        )),
        Err(e) => Err((axum::http::StatusCode::INTERNAL_SERVER_ERROR, e.to_string())),
    }
}

/// Query params for history pagination.
///
/// Two modes:
/// - Plain (no params): full history — `{ messages, total, sessionId }`
///   (matches the Scala REST gateway).
/// - `limit`/`beforeIndex`: latest page — `{ messages, total, offset,
///   hasMore, sessionId }` (matches the WS `historyPage` payload shape).
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HistoryQuery {
    #[serde(default)]
    pub offset: Option<usize>,
    #[serde(default)]
    pub limit: Option<usize>,
    #[serde(default)]
    pub before_index: Option<usize>,
}

/// GET /api/sessions/:id/history — get session history.
pub async fn get_history(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Query(query): Query<HistoryQuery>,
) -> Result<Json<Value>, (axum::http::StatusCode, String)> {
    if !check_auth(&state, &headers) {
        return Err((axum::http::StatusCode::UNAUTHORIZED, "Invalid token".into()));
    }

    match (query.limit, query.before_index) {
        // Latest-page mode (mirrors the WS getHistory/historyPage flow).
        (Some(limit), before) if limit > 0 => {
            let (msgs, total, offset, has_more) = state
                .session_store
                .get_history_page(&id, limit, before)
                .await;
            Ok(Json(serde_json::json!({
                "messages": msgs,
                "total": total,
                "offset": offset,
                "hasMore": has_more,
                "sessionId": id,
            })))
        }
        // Offset/limit or full-history mode (Scala REST parity).
        _ => {
            let offset = query.offset.unwrap_or(0);
            let limit = query.limit.unwrap_or(0);
            let (msgs, total) = state
                .session_store
                .get_ui_messages(&id, offset, limit)
                .await;
            Ok(Json(serde_json::json!({
                "messages": msgs,
                "total": total,
                "sessionId": id,
            })))
        }
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

    fn auth_headers() -> HeaderMap {
        let mut h = HeaderMap::new();
        h.insert("authorization", "Bearer test-token".parse().unwrap());
        h
    }

    fn empty_query() -> Query<HistoryQuery> {
        Query(HistoryQuery {
            offset: None,
            limit: None,
            before_index: None,
        })
    }

    #[tokio::test]
    async fn list_sessions_no_auth() {
        let state = make_state();
        let result = list_sessions(State(state), HeaderMap::new()).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn list_sessions_with_auth() {
        let state = make_state();
        state.session_store.load().await.unwrap();
        let result = list_sessions(State(state), auth_headers()).await;
        assert!(result.is_ok());
        let body = result.unwrap().0;
        assert_eq!(body["sessions"].as_array().unwrap().len(), 1); // Default session
        assert!(body["activeId"].is_string());
    }

    #[tokio::test]
    async fn create_and_delete_session() {
        let state = make_state();
        state.session_store.load().await.unwrap();
        let body = CreateSessionBody {
            name: "Test".into(),
            agent_name: None,
            folder_id: None,
        };
        let result = create_session(State(state.clone()), auth_headers(), Json(body)).await;
        assert!(result.is_ok());
        let meta = result.unwrap().0;
        let id = meta["id"].as_str().unwrap().to_string();

        let result = delete_session(State(state), auth_headers(), Path(id)).await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn history_full_mode_includes_session_id() {
        let state = make_state();
        state.session_store.load().await.unwrap();
        let sessions = state.session_store.list_sessions().await;
        let id = sessions[0].id.clone();
        let result = get_history(
            State(state),
            auth_headers(),
            Path(id.clone()),
            empty_query(),
        )
        .await;
        assert!(result.is_ok());
        let body = result.unwrap().0;
        assert_eq!(body["sessionId"], id);
        assert!(body["messages"].is_array());
        assert!(body["total"].is_number());
        assert!(body.get("hasMore").is_none());
    }

    #[tokio::test]
    async fn history_page_mode_includes_offset_and_has_more() {
        let state = make_state();
        state.session_store.load().await.unwrap();
        let sessions = state.session_store.list_sessions().await;
        let id = sessions[0].id.clone();
        let query = Query(HistoryQuery {
            offset: None,
            limit: Some(50),
            before_index: None,
        });
        let result = get_history(State(state), auth_headers(), Path(id), query).await;
        assert!(result.is_ok());
        let body = result.unwrap().0;
        assert!(body["offset"].is_number());
        assert_eq!(body["hasMore"], false);
    }
}
