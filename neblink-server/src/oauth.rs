//! GitHub OAuth helpers.
//!
//! Flow:
//!   1. Browser hits  GET /api/auth/github/login
//!      -> we mint an opaque `state`, remember it for 5 minutes, and redirect
//!         the user to GitHub's authorize URL.
//!   2. GitHub redirects back to  GET /api/auth/github/callback?code=..&state=..
//!      -> we validate `state`, swap `code` for an access token, fetch the
//!         GitHub user profile + primary email, upsert into the `users` table,
//!         and (handled in routes.rs) set auth cookies + redirect to the SPA.
//!
//! Secrets come from environment variables (see `.env.example`):
//!   GITHUB_CLIENT_ID, GITHUB_CLIENT_SECRET, OAUTH_REDIRECT_URL, APP_PUBLIC_URL

use base64::Engine;
use dashmap::DashMap;
use serde::Deserialize;
use std::sync::Arc;
use std::time::{Duration, Instant};

/// PKCE-ish CSRF protection: state -> (expiry, redirect_hint). One-shot.
/// Held in memory; a server restart just forces the in-flight login to retry.
pub struct StateStore {
    inner: DashMap<String, Instant>,
}

impl StateStore {
    pub fn new() -> Self {
        Self {
            inner: DashMap::new(),
        }
    }

    /// Mint a fresh `state`, valid for [`STATE_TTL`].
    pub fn issue(&self) -> String {
        use rand::RngCore;
        let mut bytes = [0u8; 16];
        rand::thread_rng().fill_bytes(&mut bytes);
        let state = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes);
        self.inner.insert(state.clone(), Instant::now() + STATE_TTL);
        state
    }

    /// Consume a `state`. Returns true iff it exists and had not expired.
    /// Single-use: removed whether or not it was valid.
    pub fn consume(&self, state: &str) -> bool {
        let had = self.inner.remove(state).is_some();
        // Opportunistic GC of expired entries.
        if self.inner.len() > 256 {
            let now = Instant::now();
            self.inner.retain(|_, exp| *exp > now);
        }
        had
    }
}

const STATE_TTL: Duration = Duration::from_secs(5 * 60);

/// GitHub user profile (subset of fields we care about).
#[derive(Debug, Deserialize)]
pub struct GithubUser {
    pub id: i64,
    pub login: String,
    pub name: Option<String>,
    pub avatar_url: Option<String>,
}

/// Entry from GET /user/emails.
#[derive(Debug, Deserialize)]
pub struct GithubEmail {
    pub email: String,
    pub primary: bool,
    pub verified: bool,
}

/// Read the GitHub OAuth client id from env (panics with a clear message if
/// missing — better than a silent 403 later).
pub fn client_id() -> String {
    std::env::var("GITHUB_CLIENT_ID").expect("GITHUB_CLIENT_ID must be set")
}

pub fn client_secret() -> String {
    std::env::var("GITHUB_CLIENT_SECRET").expect("GITHUB_CLIENT_SECRET must be set")
}

pub fn redirect_url() -> String {
    std::env::var("OAUTH_REDIRECT_URL")
        .expect("OAUTH_REDIRECT_URL must be set (e.g. https://neblink.nebflow.space/api/auth/github/callback)")
}

/// Build the authorize URL the browser is redirected to.
pub fn authorize_url(state: &str) -> String {
    format!(
        "https://github.com/login/oauth/authorize?client_id={cid}&redirect_uri={ru}&scope={scope}&state={state}",
        cid = urlencoding::encode(&client_id()),
        ru = urlencoding::encode(&redirect_url()),
        // read:user gives us the profile; user:email lets us read /user/emails.
        scope = urlencoding::encode("read:user user:email"),
        state = urlencoding::encode(state),
    )
}

/// Swap the authorization `code` for a GitHub access token. Returns the token
/// string on success.
pub async fn exchange_code(code: &str, http: &reqwest::Client) -> Result<String, String> {
    #[derive(Deserialize)]
    struct TokenResp {
        access_token: String,
        // GitHub may return error/error_description on failure; we ignore them
        // and rely on access_token being absent to signal failure.
    }

    let resp = http
        .post("https://github.com/login/oauth/access_token")
        .header("Accept", "application/json")
        .json(&serde_json::json!({
            "client_id": client_id(),
            "client_secret": client_secret(),
            "code": code,
            "redirect_uri": redirect_url(),
        }))
        .send()
        .await
        .map_err(|e| format!("token exchange request failed: {e}"))?;

    if !resp.status().is_success() {
        return Err(format!("token exchange HTTP {}", resp.status()));
    }

    let parsed: TokenResp = resp
        .json()
        .await
        .map_err(|e| format!("token exchange decode failed: {e}"))?;
    Ok(parsed.access_token)
}

/// Fetch the GitHub user profile.
pub async fn fetch_user(
    token: &str,
    http: &reqwest::Client,
) -> Result<GithubUser, String> {
    let resp = http
        .get("https://api.github.com/user")
        .header("Authorization", format!("Bearer {token}"))
        .header("User-Agent", "neblink-server")
        .header("Accept", "application/vnd.github+json")
        .send()
        .await
        .map_err(|e| format!("fetch user failed: {e}"))?;
    if !resp.status().is_success() {
        return Err(format!("fetch user HTTP {}", resp.status()));
    }
    resp.json()
        .await
        .map_err(|e| format!("fetch user decode failed: {e}"))
}

/// Fetch the user's primary verified email.
pub async fn fetch_primary_email(
    token: &str,
    http: &reqwest::Client,
) -> Result<Option<String>, String> {
    let resp = http
        .get("https://api.github.com/user/emails")
        .header("Authorization", format!("Bearer {token}"))
        .header("User-Agent", "neblink-server")
        .header("Accept", "application/vnd.github+json")
        .send()
        .await
        .map_err(|e| format!("fetch emails failed: {e}"))?;
    if !resp.status().is_success() {
        return Err(format!("fetch emails HTTP {}", resp.status()));
    }
    let emails: Vec<GithubEmail> = resp
        .json()
        .await
        .map_err(|e| format!("fetch emails decode failed: {e}"))?;
    Ok(emails
        .into_iter()
        .find(|e| e.primary && e.verified)
        .map(|e| e.email))
}

/// A minimal URL-encoder (avoids pulling in the `urlencoding` crate).
mod urlencoding {
    pub fn encode(s: &str) -> String {
        let mut out = String::with_capacity(s.len());
        for &b in s.as_bytes() {
            match b {
                b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                    out.push(b as char)
                }
                _ => out.push_str(&format!("%{:02X}", b)),
            }
        }
        out
    }
}

/// Shared reqwest client reused across the OAuth lifecycle.
pub fn http_client() -> reqwest::Client {
    reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(15))
        .build()
        .expect("failed to build reqwest client")
}

/// Bundle of everything routes.rs needs to drive the OAuth flow.
#[derive(Clone)]
pub struct OauthContext {
    pub states: Arc<StateStore>,
    pub http: reqwest::Client,
}
