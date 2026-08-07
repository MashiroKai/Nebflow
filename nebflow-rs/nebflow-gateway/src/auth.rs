//! Authentication — mirrors Scala auth.scala.
//!
//! Generates and validates gateway tokens. Tokens are persisted to
//! `~/.nebflow/auth.json` with restrictive file permissions.

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use std::path::PathBuf;

/// Auth token file path: `~/.nebflow/auth.json`.
fn token_path() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    PathBuf::from(home).join(".nebflow").join("auth.json")
}

/// Generate a random 32-byte URL-safe base64 token.
pub fn generate_token() -> String {
    use std::io::Read;
    // Use /dev/urandom on Unix, fall back to a simple random on other platforms.
    // For production, this should use a proper crypto RNG (e.g. ring or rand crate),
    // but for now we use the OS entropy source.
    let mut bytes = [0u8; 32];
    #[cfg(unix)]
    {
        let mut f = std::fs::File::open("/dev/urandom").expect("failed to open /dev/urandom");
        f.read_exact(&mut bytes)
            .expect("failed to read /dev/urandom");
    }
    #[cfg(not(unix))]
    {
        // Fallback: use system time + process ID as entropy (not cryptographically strong)
        let seed = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos() as u64;
        for (i, b) in bytes.iter_mut().enumerate() {
            *b = ((seed.wrapping_mul((i as u64 + 1) * 2654435761)) >> ((i % 8) * 8)) as u8;
        }
    }
    URL_SAFE_NO_PAD.encode(bytes)
}

/// Load an existing token from disk, or create a new one and persist it.
pub fn load_or_create_token() -> Result<String, std::io::Error> {
    let path = token_path();
    if path.exists() {
        let content = std::fs::read_to_string(&path)?;
        // The file stores the token as a JSON string: "token_here"
        let token: serde_json::Value =
            serde_json::from_str(&content).unwrap_or(serde_json::Value::Null);
        if let Some(t) = token.as_str() {
            if !t.is_empty() {
                return Ok(t.to_string());
            }
        }
    }
    // Create new token
    let token = generate_token();
    save_token(&path, &token)?;
    Ok(token)
}

/// Save token to disk with restrictive permissions.
fn save_token(path: &PathBuf, token: &str) -> Result<(), std::io::Error> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let json = serde_json::Value::String(token.to_string());
    let content = serde_json::to_string(&json)?;
    std::fs::write(path, content)?;
    // Set file permissions to owner-only on Unix
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let perms = std::fs::Permissions::from_mode(0o600);
        std::fs::set_permissions(path, perms)?;
    }
    Ok(())
}

/// Validate a provided token against the expected token using constant-time
/// comparison to prevent timing attacks.
pub fn validate_token(provided: &str, expected: &str) -> bool {
    constant_time_eq(provided.as_bytes(), expected.as_bytes())
}

/// Constant-time byte comparison.
fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut result = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        result |= x ^ y;
    }
    result == 0
}

/// Auth struct — holds the expected token for the gateway's lifetime.
#[derive(Clone)]
pub struct Auth {
    token: String,
}

impl Auth {
    /// Create a new Auth instance, loading or creating the token from disk.
    pub fn new() -> Result<Self, std::io::Error> {
        let token = load_or_create_token()?;
        Ok(Self { token })
    }

    /// Create an Auth instance with a pre-known token (for testing).
    pub fn with_token(token: String) -> Self {
        Self { token }
    }

    /// Get the token string.
    pub fn token(&self) -> &str {
        &self.token
    }

    /// Validate a provided token.
    pub fn validate(&self, provided: &str) -> bool {
        validate_token(provided, &self.token)
    }
}

impl Default for Auth {
    fn default() -> Self {
        Self::new().unwrap_or_else(|_| Self::with_token(generate_token()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generate_token_is_url_safe_base64() {
        let token = generate_token();
        // 32 bytes → 43 chars in URL-safe base64 without padding
        assert_eq!(token.len(), 43);
        assert!(!token.contains('='));
        assert!(!token.contains('+'));
        assert!(!token.contains('/'));
    }

    #[test]
    fn generate_token_is_unique() {
        let t1 = generate_token();
        let t2 = generate_token();
        assert_ne!(t1, t2);
    }

    #[test]
    fn validate_token_correct() {
        let auth = Auth::with_token("test-token-123".into());
        assert!(auth.validate("test-token-123"));
    }

    #[test]
    fn validate_token_wrong() {
        let auth = Auth::with_token("test-token-123".into());
        assert!(!auth.validate("wrong-token"));
    }

    #[test]
    fn validate_token_empty() {
        let auth = Auth::with_token("test-token-123".into());
        assert!(!auth.validate(""));
    }

    #[test]
    fn validate_token_different_length() {
        let auth = Auth::with_token("short".into());
        assert!(!auth.validate("short-but-longer"));
    }

    #[test]
    fn constant_time_eq_same() {
        assert!(constant_time_eq(b"hello", b"hello"));
    }

    #[test]
    fn constant_time_eq_diff() {
        assert!(!constant_time_eq(b"hello", b"world"));
    }

    #[test]
    fn constant_time_eq_diff_len() {
        assert!(!constant_time_eq(b"hello", b"hell"));
    }

    #[test]
    fn constant_time_eq_empty() {
        assert!(constant_time_eq(b"", b""));
    }

    #[test]
    fn auth_with_token_returns_same_token() {
        let auth = Auth::with_token("abc123".into());
        assert_eq!(auth.token(), "abc123");
    }
}
