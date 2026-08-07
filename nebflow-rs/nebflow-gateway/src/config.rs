//! Gateway configuration — mirrors Scala gatewayConfig.scala.
//!
//! Reads host/port from environment variables with sensible defaults.

use std::net::SocketAddr;

/// Default bind host.
pub const DEFAULT_HOST: &str = "0.0.0.0";
/// Default bind port.
pub const DEFAULT_PORT: u16 = 8080;

const HOST_ENV: &str = "NEBFLOW_GATEWAY_HOST";
const PORT_ENV: &str = "NEBFLOW_GATEWAY_PORT";

/// Gateway configuration (host + port).
#[derive(Debug, Clone)]
pub struct GatewayConfig {
    pub host: String,
    pub port: u16,
}

impl Default for GatewayConfig {
    fn default() -> Self {
        Self {
            host: DEFAULT_HOST.to_string(),
            port: DEFAULT_PORT,
        }
    }
}

impl GatewayConfig {
    /// Load config from environment variables.
    pub fn from_env() -> Self {
        let host = std::env::var(HOST_ENV).unwrap_or_else(|_| DEFAULT_HOST.to_string());
        let port = std::env::var(PORT_ENV)
            .ok()
            .and_then(|s| s.parse::<u16>().ok())
            .unwrap_or(DEFAULT_PORT);
        Self { host, port }
    }

    /// Allow CLI override of port (analogous to Scala's setPort).
    pub fn with_port(mut self, port: u16) -> Self {
        self.port = port;
        self
    }

    /// Build the socket address for binding.
    pub fn socket_addr(&self) -> SocketAddr {
        format!("{}:{}", self.host, self.port)
            .parse()
            .unwrap_or_else(|_| {
                format!("{}:{}", DEFAULT_HOST, DEFAULT_PORT)
                    .parse()
                    .expect("default address is valid")
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_config() {
        let cfg = GatewayConfig::default();
        assert_eq!(cfg.host, "0.0.0.0");
        assert_eq!(cfg.port, 8080);
    }

    #[test]
    fn with_port_override() {
        let cfg = GatewayConfig::default().with_port(3000);
        assert_eq!(cfg.port, 3000);
    }

    #[test]
    fn socket_addr_parses() {
        let cfg = GatewayConfig::default();
        let addr = cfg.socket_addr();
        assert_eq!(addr.port(), 8080);
    }

    use std::sync::{Mutex, OnceLock};

    // Mutex to serialize env-var tests (they share global state).
    fn env_mutex() -> &'static Mutex<()> {
        static M: OnceLock<Mutex<()>> = OnceLock::new();
        M.get_or_init(|| Mutex::new(()))
    }

    #[test]
    fn from_env_uses_defaults_when_unset() {
        let _guard = env_mutex().lock().unwrap();
        std::env::remove_var(HOST_ENV);
        std::env::remove_var(PORT_ENV);
        let cfg = GatewayConfig::from_env();
        assert_eq!(cfg.host, DEFAULT_HOST);
        assert_eq!(cfg.port, DEFAULT_PORT);
    }

    #[test]
    fn from_env_reads_port() {
        let _guard = env_mutex().lock().unwrap();
        std::env::set_var(PORT_ENV, "9090");
        let cfg = GatewayConfig::from_env();
        assert_eq!(cfg.port, 9090);
        std::env::remove_var(PORT_ENV);
    }

    #[test]
    fn from_env_reads_host() {
        let _guard = env_mutex().lock().unwrap();
        std::env::set_var(HOST_ENV, "127.0.0.1");
        let cfg = GatewayConfig::from_env();
        assert_eq!(cfg.host, "127.0.0.1");
        std::env::remove_var(HOST_ENV);
    }

    #[test]
    fn from_env_invalid_port_falls_back() {
        let _guard = env_mutex().lock().unwrap();
        std::env::set_var(PORT_ENV, "not-a-number");
        let cfg = GatewayConfig::from_env();
        assert_eq!(cfg.port, DEFAULT_PORT);
        std::env::remove_var(PORT_ENV);
    }
}
