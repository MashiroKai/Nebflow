use crate::auth::{self, generate_refresh_token, REFRESH_TOKEN_TTL_SECS};
use crate::model::*;
use crate::oauth::{self, OauthContext};
use crate::store::{DeviceCodePollResult, Store};
use axum::{
    extract::{FromRef, Path, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Redirect, Response},
    Json,
};
use axum_extra::extract::cookie::{Cookie, Key, SameSite, SignedCookieJar};
use serde::Deserialize;
use std::sync::Arc;

/// Shared state: the persistent Store plus the OAuth session driver.
#[derive(Clone)]
pub struct AppState {
    pub store: Arc<Store>,
    pub oauth: OauthContext,
    /// Cookie signing key (HMAC). Derived from NEBFLOW_JWT_SECRET so we don't
    /// need yet another secret to manage.
    pub cookie_key: Key,
}

/// Required by `SignedCookieJar` to obtain the signing key from app state.
impl FromRef<AppState> for Key {
    fn from_ref(state: &AppState) -> Self {
        state.cookie_key.clone()
    }
}

const ACCESS_COOKIE: &str = "neblink_session";
const REFRESH_COOKIE: &str = "neblink_refresh";
// 30 days, in seconds, for the browser-side cookie max-age.
const REFRESH_COOKIE_MAX_AGE_SECS: i64 = 30 * 24 * 3600;

fn cookie_key_from_env() -> Key {
    // Derive a cookie-signing key from the JWT secret. cookie::Key requires a
    // master key >= 64 bytes (COMBINED_KEY_LENGTH = signing + encryption), so
    // we produce a deterministic 64-byte key by concatenating two SHA-256
    // digests of the secret with distinct domain-separation prefixes. This
    // keeps the cookie key derived from (but distinct from) the JWT secret,
    // and works for any non-empty secret value.
    if let Ok(secret) = std::env::var("NEBFLOW_JWT_SECRET") {
        if !secret.is_empty() {
            use sha2::{Digest, Sha256};
            let mut h1 = Sha256::new();
            h1.update(b"neblink-cookie-key-v1|");
            h1.update(secret.as_bytes());
            let d1 = h1.finalize();
            let mut h2 = Sha256::new();
            h2.update(b"neblink-cookie-key-v2|");
            h2.update(secret.as_bytes());
            let d2 = h2.finalize();
            let mut combined = [0u8; 64];
            combined[..32].copy_from_slice(&d1);
            combined[32..].copy_from_slice(&d2);
            return Key::from(&combined);
        }
    }
    tracing::warn!("NEBFLOW_JWT_SECRET not set — auth cookies will use an ephemeral key");
    // Ephemeral key; sessions won't survive restart but the server boots.
    Key::generate()
}

impl AppState {
    pub fn new(store: Arc<Store>) -> Self {
        let cookie_key = cookie_key_from_env();
        Self {
            store,
            oauth: OauthContext {
                states: Arc::new(oauth::StateStore::new()),
                http: oauth::http_client(),
            },
            cookie_key,
        }
    }
}

// ===== Auth helpers =====

fn extract_token(headers: &HeaderMap) -> Option<String> {
    headers
        .get("authorization")?
        .to_str()
        .ok()?
        .strip_prefix("Bearer ")
        .map(|s| s.to_string())
}

/// Read the access token: prefer the signed cookie, fall back to the
/// `Authorization: Bearer` header so existing API clients (and the Scala
/// desktop client) keep working.
fn extract_access_token(jar: &SignedCookieJar, headers: &HeaderMap) -> Option<String> {
    if let Some(c) = jar.get(ACCESS_COOKIE) {
        return Some(c.value().to_string());
    }
    extract_token(headers)
}

/// Extract user JWT, verify, return (user_id, email).
/// Returns (StatusCode, Json<ErrorResponse>) error tuple on failure.
type AuthResult = Result<(String, String), (StatusCode, Json<ErrorResponse>)>;

fn require_user(jar: &SignedCookieJar, headers: &HeaderMap, store: &Store) -> AuthResult {
    if std::env::var("NEBFLOW_JWT_SECRET").is_err() {
        return Err(forbidden("NEBFLOW_JWT_SECRET not configured"));
    }
    let token =
        extract_access_token(jar, headers).ok_or_else(|| forbidden("Missing token"))?;
    store
        .verify_user_token(&token)
        .ok_or_else(|| forbidden("Invalid or expired token"))
}

type ApiResult<T> = Result<Json<T>, (StatusCode, Json<ErrorResponse>)>;

fn forbidden(msg: &str) -> (StatusCode, Json<ErrorResponse>) {
    (StatusCode::FORBIDDEN, Json(ErrorResponse::new(msg)))
}

fn bad_request(msg: impl Into<String>) -> (StatusCode, Json<ErrorResponse>) {
    (StatusCode::BAD_REQUEST, Json(ErrorResponse::new(msg)))
}

fn unauthorized(msg: &str) -> (StatusCode, Json<ErrorResponse>) {
    (StatusCode::UNAUTHORIZED, Json(ErrorResponse::new(msg)))
}

/// Whether the request arrived over HTTPS (directly or via the Caddy reverse
/// proxy). Drives the `Secure` flag on cookies.
fn is_secure(headers: &HeaderMap) -> bool {
    headers
        .get("x-forwarded-proto")
        .and_then(|v| v.to_str().ok())
        .map(|s| s.eq_ignore_ascii_case("https"))
        .unwrap_or(false)
}

fn build_access_cookie(value: &str, secure: bool) -> Cookie<'static> {
    let mut c = Cookie::build((ACCESS_COOKIE, value.to_string()))
        .path("/")
        .http_only(true)
        .same_site(SameSite::Lax)
        .max_age(time::Duration::seconds(auth::ACCESS_TOKEN_TTL_SECS as i64));
    if secure {
        c = c.secure(true);
    }
    c.build()
}

fn build_refresh_cookie(value: &str, secure: bool) -> Cookie<'static> {
    let mut c = Cookie::build((REFRESH_COOKIE, value.to_string()))
        .path("/")
        .http_only(true)
        .same_site(SameSite::Lax)
        .max_age(time::Duration::seconds(REFRESH_COOKIE_MAX_AGE_SECS));
    if secure {
        c = c.secure(true);
    }
    c.build()
}

fn clear_cookie(name: &str) -> Cookie<'static> {
    Cookie::build((name.to_string(), String::new()))
        .path("/")
        .max_age(time::Duration::seconds(0))
        .build()
}

// ===== OAuth endpoints =====

/// GET /api/auth/github/login?user_code=XXXX-XXXX
/// Begin the OAuth dance: redirect the browser to GitHub. When `user_code` is
/// present (device-authorization flow), it's bound to the OAuth `state` so the
/// callback can approve the pending device after GitHub redirects back.
pub async fn github_login(
    State(state): State<AppState>,
    axum::extract::Query(params): axum::extract::Query<GithubLoginParams>,
) -> Response {
    // Fail gracefully if OAuth isn't configured (rather than panicking inside
    // oauth::authorize_url). This returns a clear JSON error the SPA can show.
    if std::env::var("GITHUB_CLIENT_ID").is_err() {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(ErrorResponse::new(
                "GitHub OAuth not configured (GITHUB_CLIENT_ID missing)",
            )),
        )
            .into_response();
    }
    let st = state
        .oauth
        .states
        .issue_with(params.user_code.filter(|c| !c.is_empty()));
    Redirect::to(&oauth::authorize_url(&st)).into_response()
}

#[derive(Deserialize)]
pub struct GithubLoginParams {
    #[serde(default)]
    pub user_code: Option<String>,
}

#[derive(Deserialize)]
pub struct GithubCallbackParams {
    pub code: String,
    pub state: String,
}

/// GET /api/auth/github/callback
/// GitHub sends the user back here with ?code=...&state=... . We verify state,
/// exchange the code, fetch the profile + email, upsert the user, and set the
/// access + refresh cookies before bouncing to the SPA root.
pub async fn github_callback(
    State(state): State<AppState>,
    headers: HeaderMap,
    jar: SignedCookieJar,
    axum::extract::Query(params): axum::extract::Query<GithubCallbackParams>,
) -> Response {
    let app_url =
        std::env::var("APP_PUBLIC_URL").unwrap_or_else(|_| "/".to_string());
    let failure = |msg: &str| -> Response {
        tracing::warn!("github oauth callback failed: {msg}");
        Redirect::to(&format!("{app_url}/#auth-error={}", urlenc_short(msg)))
            .into_response()
    };

    let (valid, user_code) = state.oauth.states.consume(&params.state);
    if !valid {
        return failure("invalid or expired state");
    }
    let user_code = user_code; // rebinding for clarity (device-flow payload, if any)

    let gh_token = match oauth::exchange_code(&params.code, &state.oauth.http).await {
        Ok(t) => t,
        Err(e) => return failure(&e),
    };

    let user = match oauth::fetch_user(&gh_token, &state.oauth.http).await {
        Ok(u) => u,
        Err(e) => return failure(&e),
    };
    let email = oauth::fetch_primary_email(&gh_token, &state.oauth.http)
        .await
        .ok()
        .flatten();

    let (user_id, final_email) = state.store.upsert_github_user(
        user.id,
        email.as_deref(),
        user.name.as_deref().or(Some(user.login.as_str())),
        user.avatar_url.as_deref(),
    );

    let email_for_jwt = final_email.clone().unwrap_or_default();
    let secret = match std::env::var("NEBFLOW_JWT_SECRET") {
        Ok(s) => s,
        Err(_) => return failure("server missing NEBFLOW_JWT_SECRET"),
    };

    let access_jwt = match auth::create_access_jwt(&user_id, &email_for_jwt, &secret) {
        Ok(t) => t,
        Err(e) => return failure(&e),
    };
    let refresh = generate_refresh_token();
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    state
        .store
        .save_refresh_token(&refresh, &user_id, now + REFRESH_TOKEN_TTL_SECS);

    let secure = is_secure(&headers);
    let updated = jar
        .add(build_access_cookie(&access_jwt, secure))
        .add(build_refresh_cookie(&refresh, secure));

    // If this OAuth flow was initiated by a device-authorization request,
    // approve the pending device code so the polling device can proceed.
    if let Some(uc) = &user_code {
        state.store.approve_device_code(uc, &user_id);
        // Redirect to the device authorization success page (shows "you can
        // close this tab now" to the user).
        return (updated, Redirect::to(&format!("{app_url}/device/{uc}?status=approved")))
            .into_response();
    }

    // Bounce to the SPA; cookies ride along.
    (updated, Redirect::to(&format!("{app_url}/"))).into_response()
}

/// POST /api/auth/refresh
/// Exchange a valid refresh-token cookie for a new access-token cookie.
/// The refresh token is single-use (rotated on consumption).
pub async fn auth_refresh(
    State(state): State<AppState>,
    jar: SignedCookieJar,
) -> Result<Response, (StatusCode, Json<ErrorResponse>)> {
    let refresh = jar
        .get(REFRESH_COOKIE)
        .map(|c| c.value().to_string())
        .ok_or_else(|| unauthorized("Missing refresh token"))?;

    let user_id = state
        .store
        .consume_refresh_token(&refresh)
        .ok_or_else(|| unauthorized("Invalid or expired refresh token"))?;

    // Look up the email for the new access token.
    let email = state
        .store
        .user_email(&user_id)
        .unwrap_or_default();

    let secret = std::env::var("NEBFLOW_JWT_SECRET")
        .map_err(|_| forbidden("NEBFLOW_JWT_SECRET not configured"))?;
    let access_jwt = auth::create_access_jwt(&user_id, &email, &secret)
        .map_err(|e| bad_request(&e))?;

    // Mint a rotated refresh token.
    let new_refresh = generate_refresh_token();
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    state
        .store
        .save_refresh_token(&new_refresh, &user_id, now + REFRESH_TOKEN_TTL_SECS);

    // We can't read request headers here easily for the Secure flag without
    // adding them as an extractor; default to Secure-on for safety in prod.
    // The browser will use the cookie over https only.
    let updated = jar
        .add(build_access_cookie(&access_jwt, true))
        .add(build_refresh_cookie(&new_refresh, true));

    Ok((updated, Json(serde_json::json!({"ok": true}))).into_response())
}

/// POST /api/auth/logout
/// Revoke the refresh token and clear both cookies.
pub async fn auth_logout(
    State(state): State<AppState>,
    jar: SignedCookieJar,
) -> Response {
    if let Some(c) = jar.get(REFRESH_COOKIE) {
        state.store.revoke_refresh_token(c.value());
    }
    let cleared = jar
        .remove(clear_cookie(ACCESS_COOKIE))
        .remove(clear_cookie(REFRESH_COOKIE));
    (cleared, Json(serde_json::json!({"ok": true}))).into_response()
}

// ===== Device authorization flow (Tailscale-style) =====

/// POST /api/device/code
/// Device initiates the authorization flow. Returns a device_code (for polling)
/// and a user_code (for the user to enter in the browser). No auth required —
/// the device isn't enrolled yet.
pub async fn device_code(
    State(state): State<AppState>,
    Json(req): Json<DeviceCodeRequest>,
) -> ApiResult<DeviceCodeResponse> {
    let app_url =
        std::env::var("APP_PUBLIC_URL").unwrap_or_else(|_| "/".to_string());
    let (device_code, user_code) = state
        .store
        .create_device_code(&req.device_id, &req.device_name, &req.platform);
    Ok(Json(DeviceCodeResponse {
        device_code,
        user_code: user_code.clone(),
        verification_uri: format!("{app_url}/device/{user_code}"),
        expires_in: 900,
        interval: 3,
    }))
}

/// POST /api/device/token
/// Device polls for the result of its authorization request.
/// - Pending → 400 `{"error":"authorization_pending"}`
/// - Approved → 200 `{deviceToken, networkId, deviceId}` (EnrollResponse)
/// - Expired/Denied → 400 with error message
pub async fn device_token(
    State(state): State<AppState>,
    Json(req): Json<DeviceTokenRequest>,
) -> Response {
    match state.store.poll_device_code(&req.device_code) {
        Ok(DeviceCodePollResult::Pending) => (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"error":"authorization_pending"})),
        )
            .into_response(),
        Ok(DeviceCodePollResult::Approved {
            user_id,
            device_id,
            device_name,
            platform: _,
        }) => {
            // Get-or-create the user's default network (account = network).
            let network_id = match state.store.get_or_create_default_network(&user_id) {
                Ok(id) => id,
                Err(e) => {
                    return (
                        StatusCode::INTERNAL_SERVER_ERROR,
                        Json(ErrorResponse::new(e)),
                    )
                        .into_response();
                }
            };
            // Mint a long-lived device credential.
            let device_token = match state.store.enroll_device(&network_id, &device_id) {
                Ok(t) => t,
                Err(e) => {
                    return (
                        StatusCode::INTERNAL_SERVER_ERROR,
                        Json(ErrorResponse::new(e)),
                    )
                        .into_response();
                }
            };
            // Persist the device row for management listings.
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs() as i64)
                .unwrap_or(0);
            state
                .store
                .upsert_device_row(&device_id, &network_id, &device_name, "", now);
            tracing::info!("Device enrolled via flow: {} (user={})", device_id, user_id);
            Json(EnrollResponse {
                device_token,
                network_id,
                device_id,
            })
            .into_response()
        }
        Err(e) => (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"error": e})),
        )
            .into_response(),
    }
}

/// GET /device/{user_code}
/// Browser page where the user authorizes a device. Shows the user_code and
/// a "Login with GitHub" button that starts the OAuth flow with the user_code
/// bound to the state.
pub async fn device_authorize_page(
    State(_state): State<AppState>,
    Path(user_code): Path<String>,
) -> axum::response::Html<&'static str> {
    // The page is a static template; the user_code is in the URL path.
    // The JS reads window.location.pathname to extract it.
    axum::response::Html(include_str!("../static/device.html"))
}

// Minimal URL-component encoder for the error fragment (no new dep).
fn urlenc_short(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' => out.push(b as char),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

// ===== User endpoints =====

pub async fn user_me(
    State(state): State<AppState>,
    jar: SignedCookieJar,
    headers: HeaderMap,
) -> ApiResult<UserInfo> {
    let (user_id, email) = require_user(&jar, &headers, &state.store)?;
    Ok(Json(UserInfo { user_id, email }))
}

// ===== Network management (requires user JWT) =====

pub async fn create_network(
    State(state): State<AppState>,
    jar: SignedCookieJar,
    headers: HeaderMap,
    Json(req): Json<CreateNetworkRequest>,
) -> ApiResult<CreateNetworkResponse> {
    let (user_id, _) = require_user(&jar, &headers, &state.store)?;
    Ok(Json(state.store.create_network(&req.name, &user_id)))
}

pub async fn list_networks(
    State(state): State<AppState>,
    jar: SignedCookieJar,
    headers: HeaderMap,
) -> ApiResult<Vec<NetworkInfo>> {
    let (user_id, _) = require_user(&jar, &headers, &state.store)?;
    Ok(Json(state.store.list_networks(&user_id)))
}

pub async fn delete_network(
    State(state): State<AppState>,
    jar: SignedCookieJar,
    headers: HeaderMap,
    Path(network_id): Path<String>,
) -> ApiResult<serde_json::Value> {
    let (user_id, _) = require_user(&jar, &headers, &state.store)?;
    match state.store.delete_network(&network_id, &user_id) {
        Ok(_) => Ok(Json(serde_json::json!({"ok": true}))),
        Err(err) => Err(bad_request(&err)),
    }
}

pub async fn rotate_secret(
    State(state): State<AppState>,
    jar: SignedCookieJar,
    headers: HeaderMap,
    Path(network_id): Path<String>,
) -> ApiResult<serde_json::Value> {
    let (user_id, _) = require_user(&jar, &headers, &state.store)?;
    match state.store.rotate_secret(&network_id, &user_id) {
        Ok(secret) => Ok(Json(serde_json::json!({"secret": secret}))),
        Err(err) => Err(bad_request(&err)),
    }
}

/// Get network secret (for device pairing). Requires JWT + ownership.
/// Deprecated: pair-code enrollment replaces this; kept for legacy clients.
pub async fn get_network_secret(
    State(state): State<AppState>,
    jar: SignedCookieJar,
    headers: HeaderMap,
    Path(network_id): Path<String>,
) -> ApiResult<serde_json::Value> {
    let (user_id, _) = require_user(&jar, &headers, &state.store)?;
    match state.store.get_network_secret(&network_id, &user_id) {
        Some(secret) => Ok(Json(serde_json::json!({"secret": secret}))),
        None => Err(bad_request("Network not found or secret not available")),
    }
}

pub async fn list_devices(
    State(state): State<AppState>,
    jar: SignedCookieJar,
    headers: HeaderMap,
    Path(network_id): Path<String>,
) -> ApiResult<Vec<DeviceInfo>> {
    let (user_id, _) = require_user(&jar, &headers, &state.store)?;
    if !state.store.check_network_owner(&network_id, &user_id) {
        return Err(forbidden("Network not found or not owned by you"));
    }
    Ok(Json(state.store.list_devices(&network_id)))
}

pub async fn revoke_device(
    State(state): State<AppState>,
    jar: SignedCookieJar,
    headers: HeaderMap,
    Path((network_id, device_id)): Path<(String, String)>,
) -> ApiResult<serde_json::Value> {
    let (user_id, _) = require_user(&jar, &headers, &state.store)?;
    if !state.store.check_network_owner(&network_id, &user_id) {
        return Err(forbidden("Network not found or not owned by you"));
    }
    match state.store.revoke_device(&network_id, &device_id) {
        Ok(_) => Ok(Json(serde_json::json!({"ok": true}))),
        Err(err) => Err(bad_request(&err)),
    }
}

// ===== Device session endpoints =====

pub async fn login(State(state): State<AppState>, Json(req): Json<LoginRequest>) -> ApiResult<LoginResponse> {
    match state.store.login(req) {
        Ok(resp) => Ok(Json(resp)),
        Err(err) => Err(forbidden(&err)),
    }
}

/// POST /api/network/{id}/pair-code  (user JWT)
/// Mint a 6-digit pairing code a new device can type in once.
pub async fn create_pair_code(
    State(state): State<AppState>,
    jar: SignedCookieJar,
    headers: HeaderMap,
    Path(network_id): Path<String>,
) -> ApiResult<PairCodeResponse> {
    let (user_id, _) = require_user(&jar, &headers, &state.store)?;
    if !state.store.check_network_owner(&network_id, &user_id) {
        return Err(forbidden("Network not found or not owned by you"));
    }
    let code = state.store.issue_pair_code(&network_id, &user_id);
    Ok(Json(PairCodeResponse {
        pair_code: code,
        expires_in_secs: 600,
    }))
}

/// POST /api/device/enroll  (pairing code)
/// Device submits the one-time code; we hand back a long-lived credential.
pub async fn enroll_device(
    State(state): State<AppState>,
    jar: SignedCookieJar,
    headers: HeaderMap,
    Json(req): Json<EnrollRequest>,
) -> ApiResult<EnrollResponse> {
    // The pairing code itself proves authorization, so we don't strictly need
    // the user JWT here. But if a session cookie is present we bind the code to
    // that user (defense in depth against code leakage across accounts).
    let owner_hint = require_user(&jar, &headers, &state.store)
        .ok()
        .map(|(uid, _)| uid);

    let network_id = match owner_hint {
        Some(uid) => state
            .store
            .consume_pair_code(&req.pair_code, &uid)
            .map_err(bad_request)?,
        None => {
            // No session: consume by matching any code's owner. Look it up by
            // scanning (codes are in-memory and short-lived, so cheap).
            state
                .store
                .consume_pair_code_any(&req.pair_code)
                .map_err(bad_request)?
        }
    };

    let device_token = state
        .store
        .enroll_device(&network_id, &req.device_id)
        .map_err(bad_request)?;

    // Record the device row so it shows up in management listings.
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    state.store.upsert_device_row(
        &req.device_id,
        &network_id,
        &req.device_name,
        &req.platform,
        now,
    );

    Ok(Json(EnrollResponse {
        device_token,
        network_id,
        device_id: req.device_id,
    }))
}

/// POST /api/device/session  (per-device credential)
/// Exchange a long-lived device credential for an ephemeral session token.
pub async fn device_session(
    State(state): State<AppState>,
    Json(req): Json<DeviceSessionRequest>,
) -> ApiResult<LoginResponse> {
    if !state.store.verify_device_credential(&req.network_id, &req.device_id, &req.device_token) {
        return Err(forbidden("Invalid device credential"));
    }
    let login_req = LoginRequest {
        network_id: req.network_id.clone(),
        // The device is already authenticated by the credential; reuse the
        // shared-secret login path with an empty secret (it's bypassed via the
        // credential check above). To keep store.login's contract simple we
        // instead build the session directly here.
        secret: String::new(),
        device_id: req.device_id.clone(),
        device_name: String::new(),
        platform: String::new(),
        endpoints: req.endpoints,
    };
    // store.login still validates the network secret; for credential-based
    // devices we use a dedicated path that skips that check.
    match state.store.session_for_credential(login_req) {
        Ok(resp) => Ok(Json(resp)),
        Err(err) => Err(forbidden(&err)),
    }
}

pub async fn heartbeat(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<HeartbeatResponse> {
    let token = extract_token(&headers).ok_or_else(|| forbidden("Missing token"))?;
    match state.store.heartbeat(&token) {
        Ok(resp) => Ok(Json(resp)),
        Err(err) => Err(forbidden(&err)),
    }
}

pub async fn get_peers(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<Vec<PeerInfo>> {
    let token = extract_token(&headers).ok_or_else(|| forbidden("Missing token"))?;
    match state.store.get_peer_list(&token) {
        Ok(peers) => Ok(Json(peers)),
        Err(err) => Err(forbidden(&err)),
    }
}

pub async fn update_endpoints(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<UpdateEndpointsRequest>,
) -> ApiResult<serde_json::Value> {
    let token = extract_token(&headers).ok_or_else(|| forbidden("Missing token"))?;
    match state.store.update_endpoints(&token, req.endpoints) {
        Ok(_) => Ok(Json(serde_json::json!({"ok": true}))),
        Err(err) => Err(forbidden(&err)),
    }
}

pub async fn logout(State(state): State<AppState>, headers: HeaderMap) -> Json<serde_json::Value> {
    if let Some(token) = extract_token(&headers) {
        state.store.logout(&token);
    }
    Json(serde_json::json!({"ok": true}))
}

// ===== Health =====

pub async fn health() -> Json<serde_json::Value> {
    Json(serde_json::json!({"status": "ok"}))
}
