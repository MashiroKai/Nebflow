use crate::model::*;
use crate::store::Store;
use axum::{
    extract::State,
    http::{HeaderMap, StatusCode},
    Json,
};
use std::sync::Arc;

pub type AppState = Arc<Store>;

fn extract_token(headers: &HeaderMap) -> Option<String> {
    headers
        .get("authorization")?
        .to_str()
        .ok()?
        .strip_prefix("Bearer ")
        .map(|s| s.to_string())
}

type ApiResult<T> = Result<Json<T>, (StatusCode, Json<ErrorResponse>)>;

fn forbidden(msg: &str) -> (StatusCode, Json<ErrorResponse>) {
    (StatusCode::FORBIDDEN, Json(ErrorResponse::new(msg)))
}

fn bad_request(msg: &str) -> (StatusCode, Json<ErrorResponse>) {
    (StatusCode::BAD_REQUEST, Json(ErrorResponse::new(msg)))
}

pub async fn create_network(State(store): State<AppState>, Json(req): Json<CreateNetworkRequest>) -> Json<CreateNetworkResponse> {
    let resp = store.create_network(&req.name);
    Json(resp)
}

pub async fn login(State(store): State<AppState>, Json(req): Json<LoginRequest>) -> ApiResult<LoginResponse> {
    match store.login(req) {
        Ok(resp) => Ok(Json(resp)),
        Err(err) => Err(forbidden(&err)),
    }
}

pub async fn heartbeat(State(store): State<AppState>, headers: HeaderMap) -> ApiResult<HeartbeatResponse> {
    let token = extract_token(&headers).ok_or_else(|| forbidden("Missing token"))?;
    match store.heartbeat(&token) {
        Ok(resp) => Ok(Json(resp)),
        Err(err) => Err(forbidden(&err)),
    }
}

pub async fn get_peers(State(store): State<AppState>, headers: HeaderMap) -> ApiResult<Vec<PeerInfo>> {
    let token = extract_token(&headers).ok_or_else(|| forbidden("Missing token"))?;
    match store.get_peer_list(&token) {
        Ok(peers) => Ok(Json(peers)),
        Err(err) => Err(forbidden(&err)),
    }
}

pub async fn update_endpoints(
    State(store): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<UpdateEndpointsRequest>,
) -> ApiResult<serde_json::Value> {
    let token = extract_token(&headers).ok_or_else(|| forbidden("Missing token"))?;
    match store.update_endpoints(&token, req.endpoints) {
        Ok(_) => Ok(Json(serde_json::json!({"ok": true}))),
        Err(err) => Err(forbidden(&err)),
    }
}

pub async fn logout(State(store): State<AppState>, headers: HeaderMap) -> Json<serde_json::Value> {
    if let Some(token) = extract_token(&headers) {
        store.logout(&token);
    }
    Json(serde_json::json!({"ok": true}))
}

pub async fn health() -> Json<serde_json::Value> {
    Json(serde_json::json!({"status": "ok"}))
}
