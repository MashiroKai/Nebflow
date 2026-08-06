use base64::Engine;
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};

/// Issuer / audience claim used to bind tokens to this service and prevent
/// cross-service token confusion.
pub const ISSUER: &str = "neblink";

/// Access-token lifetime: short to limit blast radius of a leaked cookie.
pub const ACCESS_TOKEN_TTL_SECS: usize = 15 * 60;
/// Refresh-token lifetime (kept in DB so it can be revoked).
pub const REFRESH_TOKEN_TTL_SECS: i64 = 30 * 24 * 3600;

/// JWT claims — `sub` is the internal user id, `email` is the user email.
#[derive(Serialize, Deserialize)]
pub struct Claims {
    pub sub: String,
    pub email: String,
    pub exp: usize,
    /// Issued-at. Helps with clock-skew debugging and rotation.
    pub iat: usize,
    /// Issuer (always [`ISSUER`]).
    pub iss: String,
}

/// Create a short-lived access JWT for a user.
pub fn create_access_jwt(user_id: &str, email: &str, secret: &str) -> Result<String, String> {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as usize)
        .unwrap_or(0);
    let claims = Claims {
        sub: user_id.to_string(),
        email: email.to_string(),
        iat: now,
        exp: now + ACCESS_TOKEN_TTL_SECS,
        iss: ISSUER.to_string(),
    };
    let header = Header::default();
    // HS256 (jsonwebtoken default) — explicit for clarity.
    encode(
        &header,
        &claims,
        &EncodingKey::from_secret(secret.as_bytes()),
    )
    .map_err(|e| format!("JWT encode error: {e}"))
}

/// Verify an access JWT and return the claims. Returns None if invalid/expired
/// or if the issuer does not match (prevents tokens minted by other services
/// that happen to share the secret from being accepted).
pub fn verify_jwt(token: &str, secret: &str) -> Option<Claims> {
    let mut validation = Validation::default();
    validation.set_issuer(&[ISSUER]);
    decode::<Claims>(
        token,
        &DecodingKey::from_secret(secret.as_bytes()),
        &validation,
    )
    .ok()
    .map(|data| data.claims)
}

/// Generate a random refresh token (32 bytes, base64url). Only its SHA-256 hash
/// is persisted, so a DB leak is not replayable.
pub fn generate_refresh_token() -> String {
    use rand::RngCore;
    let mut bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut bytes);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}
