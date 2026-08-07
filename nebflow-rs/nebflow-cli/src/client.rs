//! Gateway HTTP client — mirrors Scala GatewayClient.scala.
//!
//! Communicates with the running Gateway server via REST API.
//! Reads the auth token from `~/.nebflow/auth.json`.

use std::path::PathBuf;

/// Gateway HTTP client.
pub struct GatewayClient {
    base_url: String,
    token: String,
    client: reqwest::Client,
}

impl GatewayClient {
    /// Create a new client with the given base URL and token.
    pub fn new(base_url: String, token: String) -> Self {
        Self {
            base_url,
            token,
            client: reqwest::Client::new(),
        }
    }

    /// Read the stored auth token from `~/.nebflow/auth.json`.
    pub fn read_token() -> Option<String> {
        let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
        let path = PathBuf::from(home).join(".nebflow").join("auth.json");
        let content = std::fs::read_to_string(&path).ok()?;
        let value: serde_json::Value = serde_json::from_str(&content).ok()?;
        value.as_str().map(|s| s.to_string())
    }

    /// Read the gateway port from env or default to 8080.
    pub fn read_port() -> u16 {
        std::env::var("NEBFLOW_GATEWAY_PORT")
            .ok()
            .and_then(|s| s.parse::<u16>().ok())
            .unwrap_or(8080)
    }

    /// Create a client if the gateway is running and accessible.
    /// Returns None if the gateway is not reachable or no token is found.
    pub async fn create() -> Option<Self> {
        let token = Self::read_token()?;
        let port = Self::read_port();
        let client = Self::new(format!("http://localhost:{port}"), token);

        if client.health_check().await {
            Some(client)
        } else {
            None
        }
    }

    /// Simple health check — returns true if the server responds.
    pub async fn health_check(&self) -> bool {
        let url = format!("{}/api/health", self.base_url);
        match self
            .client
            .get(&url)
            .header("Authorization", format!("Bearer {}", self.token))
            .timeout(std::time::Duration::from_secs(3))
            .send()
            .await
        {
            Ok(resp) => resp.status().is_success(),
            Err(_) => false,
        }
    }

    /// GET request — returns parsed JSON.
    pub async fn get(&self, path: &str) -> Result<serde_json::Value, String> {
        let url = format!("{}{path}", self.base_url);
        let resp = self
            .client
            .get(&url)
            .header("Authorization", format!("Bearer {}", self.token))
            .send()
            .await
            .map_err(|e| format!("Request failed: {e}"))?;

        let status = resp.status();
        let body = resp
            .text()
            .await
            .map_err(|e| format!("Read body failed: {e}"))?;

        if !status.is_success() {
            return Err(format!("HTTP {}: {body}", status.as_u16()));
        }

        serde_json::from_str(&body).map_err(|e| format!("Parse JSON failed: {e}"))
    }

    /// POST request with JSON body — returns parsed JSON.
    pub async fn post(
        &self,
        path: &str,
        body: &serde_json::Value,
    ) -> Result<serde_json::Value, String> {
        let url = format!("{}{path}", self.base_url);
        let resp = self
            .client
            .post(&url)
            .header("Authorization", format!("Bearer {}", self.token))
            .header("Content-Type", "application/json")
            .json(body)
            .send()
            .await
            .map_err(|e| format!("Request failed: {e}"))?;

        let status = resp.status();
        let body_text = resp
            .text()
            .await
            .map_err(|e| format!("Read body failed: {e}"))?;

        if !status.is_success() {
            return Err(format!("HTTP {}: {body_text}", status.as_u16()));
        }

        serde_json::from_str(&body_text).map_err(|e| format!("Parse JSON failed: {e}"))
    }

    /// DELETE request — returns parsed JSON.
    pub async fn delete(&self, path: &str) -> Result<serde_json::Value, String> {
        let url = format!("{}{path}", self.base_url);
        let resp = self
            .client
            .delete(&url)
            .header("Authorization", format!("Bearer {}", self.token))
            .send()
            .await
            .map_err(|e| format!("Request failed: {e}"))?;

        let status = resp.status();
        let body = resp
            .text()
            .await
            .map_err(|e| format!("Read body failed: {e}"))?;

        if !status.is_success() {
            return Err(format!("HTTP {}: {body}", status.as_u16()));
        }

        serde_json::from_str(&body).map_err(|e| format!("Parse JSON failed: {e}"))
    }

    /// POST /api/command — generic WS-equivalent endpoint.
    #[allow(dead_code)]
    pub async fn command(&self, payload: &serde_json::Value) -> Result<serde_json::Value, String> {
        self.post("/api/command", payload).await
    }

    /// Get the base URL.
    #[allow(dead_code)]
    pub fn base_url(&self) -> &str {
        &self.base_url
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn read_token_returns_none_for_missing_file() {
        let _g = crate::test_util::home_mutex().lock().unwrap();
        let dir = tempfile::tempdir().unwrap();
        std::env::set_var("HOME", dir.path());
        assert!(GatewayClient::read_token().is_none());
    }

    #[test]
    fn read_token_returns_value_from_file() {
        let _g = crate::test_util::home_mutex().lock().unwrap();
        let dir = tempfile::tempdir().unwrap();
        std::env::set_var("HOME", dir.path());

        let nebflow_dir = dir.path().join(".nebflow");
        std::fs::create_dir_all(&nebflow_dir).unwrap();
        std::fs::write(nebflow_dir.join("auth.json"), r#""test-token-123""#).unwrap();

        let token = GatewayClient::read_token();
        assert_eq!(token.as_deref(), Some("test-token-123"));
    }

    #[test]
    fn read_port_defaults_to_8080() {
        let _g = crate::test_util::home_mutex().lock().unwrap();
        std::env::remove_var("NEBFLOW_GATEWAY_PORT");
        assert_eq!(GatewayClient::read_port(), 8080);
    }

    #[test]
    fn read_port_from_env() {
        let _g = crate::test_util::home_mutex().lock().unwrap();
        std::env::set_var("NEBFLOW_GATEWAY_PORT", "9090");
        assert_eq!(GatewayClient::read_port(), 9090);
        std::env::remove_var("NEBFLOW_GATEWAY_PORT");
    }

    #[test]
    fn new_client_stores_base_url_and_token() {
        let client = GatewayClient::new("http://localhost:8080".into(), "my-token".into());
        assert_eq!(client.base_url(), "http://localhost:8080");
    }
}
