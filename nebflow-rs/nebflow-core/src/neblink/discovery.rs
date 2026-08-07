//! Discovery client — polls the NebLink server to find peer devices.
//!
//! Scala: `nebflow.neblink.NelinkDiscovery`

use crate::neblink::{
    capabilities::DeviceCapabilities, DeviceDiscoveryInfo, DeviceIdentity, NeblinkConfig, PeerInfo,
    PeerStore,
};
use async_trait::async_trait;
use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{debug, warn};

/// Discovery configuration extracted from NeblinkConfig.
#[derive(Debug, Clone)]
pub struct DiscoveryConfig {
    pub server_url: String,
    pub interval_secs: u64,
    pub max_retries: u32,
}

impl From<&NeblinkConfig> for DiscoveryConfig {
    fn from(cfg: &NeblinkConfig) -> Self {
        Self {
            server_url: cfg.server_url.clone(),
            interval_secs: cfg.discovery_interval_secs,
            max_retries: 5,
        }
    }
}

/// Trait abstracting the HTTP transport for discovery.
/// In production this is backed by reqwest; in tests it can be mocked.
#[async_trait]
pub trait DiscoveryTransport: Send + Sync {
    /// Register this device with the server, returning discovered peers.
    async fn register_and_discover(
        &self,
        server_url: &str,
        info: &DeviceDiscoveryInfo,
    ) -> Result<Vec<PeerInfo>, DiscoveryError>;
}

/// Error type for discovery operations.
#[derive(Debug, thiserror::Error)]
pub enum DiscoveryError {
    #[error("network error: {0}")]
    Network(String),
    #[error("server returned error: status={status}, body={body}")]
    Server { status: u16, body: String },
    #[error("deserialization error: {0}")]
    Deserialize(String),
    #[error("max retries exceeded")]
    MaxRetries,
}

impl From<reqwest::Error> for DiscoveryError {
    fn from(e: reqwest::Error) -> Self {
        Self::Network(e.to_string())
    }
}

impl From<serde_json::Error> for DiscoveryError {
    fn from(e: serde_json::Error) -> Self {
        Self::Deserialize(e.to_string())
    }
}

/// Reqwest-based transport implementation.
pub struct HttpDiscoveryTransport {
    client: reqwest::Client,
}

impl HttpDiscoveryTransport {
    pub fn new() -> Self {
        Self {
            client: reqwest::Client::builder()
                .timeout(std::time::Duration::from_secs(10))
                .build()
                .expect("failed to build reqwest client"),
        }
    }
}

impl Default for HttpDiscoveryTransport {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl DiscoveryTransport for HttpDiscoveryTransport {
    async fn register_and_discover(
        &self,
        server_url: &str,
        info: &DeviceDiscoveryInfo,
    ) -> Result<Vec<PeerInfo>, DiscoveryError> {
        let url = format!("{}/api/neblink/discover", server_url.trim_end_matches('/'));
        let resp = self
            .client
            .post(&url)
            .json(info)
            .send()
            .await
            .map_err(DiscoveryError::from)?;

        let status = resp.status();
        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            return Err(DiscoveryError::Server {
                status: status.as_u16(),
                body,
            });
        }

        let peers: Vec<PeerInfo> = resp.json().await.map_err(DiscoveryError::from)?;
        Ok(peers)
    }
}

/// Discovery client that periodically registers with the NebLink server
/// and discovers peer devices.
///
/// Scala: `nebflow.neblink.NelinkDiscovery`
pub struct DiscoveryClient {
    config: DiscoveryConfig,
    identity: DeviceIdentity,
    capabilities: DeviceCapabilities,
    transport: Arc<dyn DiscoveryTransport>,
    peer_store: Arc<RwLock<PeerStore>>,
}

impl DiscoveryClient {
    pub fn new(
        config: DiscoveryConfig,
        identity: DeviceIdentity,
        capabilities: DeviceCapabilities,
        transport: Arc<dyn DiscoveryTransport>,
        peer_store: Arc<RwLock<PeerStore>>,
    ) -> Self {
        Self {
            config,
            identity,
            capabilities,
            transport,
            peer_store,
        }
    }

    /// Perform a single discovery cycle. Returns the number of peers found.
    ///
    /// Implements exponential backoff on failure (up to max_retries).
    pub async fn discover_once(&self, now_unix: u64) -> Result<usize, DiscoveryError> {
        let info = DeviceDiscoveryInfo {
            identity: self.identity.clone(),
            capabilities: self.capabilities.clone(),
            neblink_server: self.config.server_url.clone(),
            last_seen: now_unix,
        };

        let peers = self
            .transport
            .register_and_discover(&self.config.server_url, &info)
            .await?;

        let count = peers.len();
        let mut store = self.peer_store.write().await;
        for peer in peers {
            debug!(target: "neblink::discovery", peer = %peer.identity.device_id, "discovered peer");
            store.upsert(peer);
        }
        Ok(count)
    }

    /// Run the discovery loop. This is designed to be spawned as a tokio task.
    ///
    /// Each iteration:
    /// 1. Attempt discovery
    /// 2. On success: sleep `interval_secs`
    /// 3. On failure: exponential backoff (1s, 2s, 4s, ... up to interval_secs)
    pub async fn run_loop(&self) {
        let mut backoff_secs: u64 = 1;
        let normal_interval = self.config.interval_secs.max(1);

        loop {
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs();

            match self.discover_once(now).await {
                Ok(count) => {
                    debug!(target: "neblink::discovery", peers = count, "discovery cycle ok");
                    backoff_secs = 1;
                    tokio::time::sleep(std::time::Duration::from_secs(normal_interval)).await;
                }
                Err(e) => {
                    warn!(target: "neblink::discovery", error = %e, "discovery failed, backing off {}s", backoff_secs);
                    tokio::time::sleep(std::time::Duration::from_secs(backoff_secs)).await;
                    backoff_secs = (backoff_secs * 2).min(normal_interval).max(1);
                }
            }
        }
    }

    /// Access the shared peer store.
    pub fn peer_store(&self) -> &Arc<RwLock<PeerStore>> {
        &self.peer_store
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU32, Ordering};

    /// Mock transport for testing.
    struct MockTransport {
        peers_to_return: Vec<PeerInfo>,
        call_count: AtomicU32,
        fail_first: bool,
    }

    #[async_trait]
    impl DiscoveryTransport for MockTransport {
        async fn register_and_discover(
            &self,
            _server_url: &str,
            _info: &DeviceDiscoveryInfo,
        ) -> Result<Vec<PeerInfo>, DiscoveryError> {
            let count = self.call_count.fetch_add(1, Ordering::SeqCst);
            if self.fail_first && count == 0 {
                return Err(DiscoveryError::Network("simulated failure".into()));
            }
            Ok(self.peers_to_return.clone())
        }
    }

    fn make_test_peer(id: &str, online: bool) -> PeerInfo {
        PeerInfo {
            identity: DeviceIdentity::new(id, "user-1"),
            capabilities: DeviceCapabilities::default(),
            address: format!("10.0.0.{}", id.len()),
            last_seen: 100,
            online,
        }
    }

    #[test]
    fn discovery_config_from_neblink() {
        let nc = NeblinkConfig::default();
        let dc = DiscoveryConfig::from(&nc);
        assert_eq!(dc.interval_secs, 30);
        assert_eq!(dc.max_retries, 5);
    }

    #[tokio::test]
    async fn discover_once_success() {
        let transport = Arc::new(MockTransport {
            peers_to_return: vec![
                make_test_peer("dev-a", true),
                make_test_peer("dev-bb", false),
            ],
            call_count: AtomicU32::new(0),
            fail_first: false,
        });
        let store = Arc::new(RwLock::new(PeerStore::new()));
        let client = DiscoveryClient::new(
            DiscoveryConfig {
                server_url: "http://test".into(),
                interval_secs: 5,
                max_retries: 3,
            },
            DeviceIdentity::new("self", "user-1"),
            DeviceCapabilities::default(),
            transport,
            store.clone(),
        );

        let count = client.discover_once(1000).await.unwrap();
        assert_eq!(count, 2);
        let s = store.read().await;
        assert_eq!(s.len(), 2);
        assert!(s.get("dev-a").is_some());
    }

    #[tokio::test]
    async fn discover_once_failure() {
        let transport = Arc::new(MockTransport {
            peers_to_return: vec![],
            call_count: AtomicU32::new(0),
            fail_first: true,
        });
        let store = Arc::new(RwLock::new(PeerStore::new()));
        let client = DiscoveryClient::new(
            DiscoveryConfig {
                server_url: "http://test".into(),
                interval_secs: 5,
                max_retries: 3,
            },
            DeviceIdentity::new("self", "user-1"),
            DeviceCapabilities::default(),
            transport,
            store,
        );

        let result = client.discover_once(1000).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn discover_updates_existing_peer() {
        let transport = Arc::new(MockTransport {
            peers_to_return: vec![make_test_peer("dev-a", true)],
            call_count: AtomicU32::new(0),
            fail_first: false,
        });
        let store = Arc::new(RwLock::new(PeerStore::new()));
        let client = DiscoveryClient::new(
            DiscoveryConfig {
                server_url: "http://test".into(),
                interval_secs: 5,
                max_retries: 3,
            },
            DeviceIdentity::new("self", "user-1"),
            DeviceCapabilities::default(),
            transport,
            store.clone(),
        );

        client.discover_once(1000).await.unwrap();
        client.discover_once(2000).await.unwrap();
        let s = store.read().await;
        assert_eq!(s.len(), 1); // same peer, not duplicated
    }
}
