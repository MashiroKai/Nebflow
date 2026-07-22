use crate::model::*;
use crate::store::Store;
use axum::{
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
    Json,
};
use std::sync::Arc;

pub type AppState = Arc<Store>;

// ===== Auth helpers =====

fn extract_token(headers: &HeaderMap) -> Option<String> {
    headers
        .get("authorization")?
        .to_str()
        .ok()?
        .strip_prefix("Bearer ")
        .map(|s| s.to_string())
}

/// Extract user JWT from Authorization header, verify, return (user_id, email).
/// Returns (StatusCode, Json<ErrorResponse>) error tuple on failure.
type AuthResult = Result<(String, String), (StatusCode, Json<ErrorResponse>)>;

fn require_user(headers: &HeaderMap, store: &Store) -> AuthResult {
    if std::env::var("NEBFLOW_JWT_SECRET").is_err() {
        return Err(forbidden("NEBFLOW_JWT_SECRET not configured"));
    }
    let token = extract_token(headers).ok_or_else(|| forbidden("Missing token"))?;
    store
        .verify_user_token(&token)
        .ok_or_else(|| forbidden("Invalid or expired token"))
}

type ApiResult<T> = Result<Json<T>, (StatusCode, Json<ErrorResponse>)>;

fn forbidden(msg: &str) -> (StatusCode, Json<ErrorResponse>) {
    (StatusCode::FORBIDDEN, Json(ErrorResponse::new(msg)))
}

fn bad_request(msg: &str) -> (StatusCode, Json<ErrorResponse>) {
    (StatusCode::BAD_REQUEST, Json(ErrorResponse::new(msg)))
}

// ===== User endpoints =====

pub async fn user_me(
    State(store): State<AppState>,
    headers: HeaderMap,
) -> ApiResult<UserInfo> {
    let (user_id, email) = require_user(&headers, &store)?;
    Ok(Json(UserInfo { user_id, email }))
}

// ===== Network management (requires user JWT) =====

pub async fn create_network(
    State(store): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<CreateNetworkRequest>,
) -> ApiResult<CreateNetworkResponse> {
    let (user_id, _) = require_user(&headers, &store)?;
    Ok(Json(store.create_network(&req.name, &user_id)))
}

pub async fn list_networks(
    State(store): State<AppState>,
    headers: HeaderMap,
) -> ApiResult<Vec<NetworkInfo>> {
    let (user_id, _) = require_user(&headers, &store)?;
    Ok(Json(store.list_networks(&user_id)))
}

pub async fn delete_network(
    State(store): State<AppState>,
    headers: HeaderMap,
    Path(network_id): Path<String>,
) -> ApiResult<serde_json::Value> {
    let (user_id, _) = require_user(&headers, &store)?;
    match store.delete_network(&network_id, &user_id) {
        Ok(_) => Ok(Json(serde_json::json!({"ok": true}))),
        Err(err) => Err(bad_request(&err)),
    }
}

pub async fn rotate_secret(
    State(store): State<AppState>,
    headers: HeaderMap,
    Path(network_id): Path<String>,
) -> ApiResult<serde_json::Value> {
    let (user_id, _) = require_user(&headers, &store)?;
    match store.rotate_secret(&network_id, &user_id) {
        Ok(secret) => Ok(Json(serde_json::json!({"secret": secret}))),
        Err(err) => Err(bad_request(&err)),
    }
}

/// Get network secret (for device pairing). Requires JWT + ownership.
pub async fn get_network_secret(
    State(store): State<AppState>,
    headers: HeaderMap,
    Path(network_id): Path<String>,
) -> ApiResult<serde_json::Value> {
    let (user_id, _) = require_user(&headers, &store)?;
    match store.get_network_secret(&network_id, &user_id) {
        Some(secret) => Ok(Json(serde_json::json!({"secret": secret}))),
        None => Err(bad_request("Network not found or secret not available")),
    }
}

pub async fn list_devices(
    State(store): State<AppState>,
    headers: HeaderMap,
    Path(network_id): Path<String>,
) -> ApiResult<Vec<DeviceInfo>> {
    let (user_id, _) = require_user(&headers, &store)?;
    if !store.check_network_owner(&network_id, &user_id) {
        return Err(forbidden("Network not found or not owned by you"));
    }
    Ok(Json(store.list_devices(&network_id)))
}

pub async fn revoke_device(
    State(store): State<AppState>,
    headers: HeaderMap,
    Path((network_id, device_id)): Path<(String, String)>,
) -> ApiResult<serde_json::Value> {
    let (user_id, _) = require_user(&headers, &store)?;
    if !store.check_network_owner(&network_id, &user_id) {
        return Err(forbidden("Network not found or not owned by you"));
    }
    match store.revoke_device(&network_id, &device_id) {
        Ok(_) => Ok(Json(serde_json::json!({"ok": true}))),
        Err(err) => Err(bad_request(&err)),
    }
}

// ===== Device session endpoints (unchanged — uses network secret) =====

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

// ===== Health =====

pub async fn health() -> Json<serde_json::Value> {
    Json(serde_json::json!({"status": "ok"}))
}
