//! NebLink Device Transfer — HTTP-based file transfer between peers.
//!
//! Implements the `DeviceTransfer` trait using `reqwest` to send/receive
//! files from peer devices via the NebLink file transfer API.
//!
//! The peer's address (host:port) is resolved from a `PeerStore`. The
//! HTTP endpoint on the peer is `POST /api/neblink/transfer` (push)
//! and `GET /api/neblink/transfer?path=...` (fetch).

use async_trait::async_trait;
use std::sync::Arc;
use tokio::sync::RwLock;

use crate::neblink::{PeerInfo, PeerStore};
use crate::types::DeviceTransfer;

/// HTTP-based DeviceTransfer implementation backed by NebLink PeerStore.
///
/// Resolves peer device names to addresses via PeerStore, then uses
/// reqwest to transfer file content over HTTP.
pub struct NebLinkTransfer {
    peer_store: Arc<RwLock<PeerStore>>,
    client: reqwest::Client,
}

impl NebLinkTransfer {
    pub fn new(peer_store: Arc<RwLock<PeerStore>>) -> Self {
        Self {
            peer_store,
            client: reqwest::Client::new(),
        }
    }

    /// Create with a custom HTTP client (for testing/proxying).
    pub fn with_client(peer_store: Arc<RwLock<PeerStore>>, client: reqwest::Client) -> Self {
        Self { peer_store, client }
    }

    /// Resolve a device name to its PeerInfo.
    async fn resolve_peer(&self, device: &str) -> Result<PeerInfo, String> {
        let store = self.peer_store.read().await;
        store
            .get(device)
            .filter(|p| p.online)
            .cloned()
            .ok_or_else(|| format!("Device '{device}' not found or offline"))
    }

    /// Build the HTTP URL for a peer's transfer endpoint.
    fn transfer_url(address: &str, path: &str) -> String {
        // Ensure address has scheme
        let base = if address.starts_with("http") {
            address.to_string()
        } else {
            format!("http://{address}")
        };
        format!(
            "{base}/api/neblink/transfer?path={}",
            urlencoding::encode(path)
        )
    }
}

#[async_trait]
impl DeviceTransfer for NebLinkTransfer {
    async fn fetch_remote(&self, device: &str, remote_path: &str) -> Result<Vec<u8>, String> {
        let peer = self.resolve_peer(device).await?;
        let url = Self::transfer_url(&peer.address, remote_path);

        let response = self
            .client
            .get(&url)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {e}"))?;

        if !response.status().is_success() {
            return Err(format!(
                "Peer returned HTTP {} for fetch {}:{}",
                response.status(),
                device,
                remote_path
            ));
        }

        let bytes = response
            .bytes()
            .await
            .map_err(|e| format!("Failed to read response body: {e}"))?;

        Ok(bytes.to_vec())
    }

    async fn push_remote(
        &self,
        device: &str,
        remote_path: &str,
        data: Vec<u8>,
    ) -> Result<(), String> {
        let peer = self.resolve_peer(device).await?;
        let url = Self::transfer_url(&peer.address, remote_path);

        let response = self
            .client
            .post(&url)
            .body(data)
            .send()
            .await
            .map_err(|e| format!("HTTP request failed: {e}"))?;

        if !response.status().is_success() {
            return Err(format!(
                "Peer returned HTTP {} for push {}:{}",
                response.status(),
                device,
                remote_path
            ));
        }

        Ok(())
    }
}

/// Minimal URL-encoding for file paths (encode special characters).
mod urlencoding {
    pub fn encode(s: &str) -> String {
        s.chars()
            .map(|c| {
                if c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.' || c == '~' {
                    c.to_string()
                } else {
                    format!("%{:02X}", c as u8)
                }
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::neblink::{DeviceCapabilities, DeviceIdentity};

    fn make_peer(device_name: &str, address: &str, online: bool) -> PeerInfo {
        PeerInfo {
            identity: DeviceIdentity {
                device_id: device_name.into(),
                user_id: "user-1".into(),
                device_name: device_name.into(),
                platform: "test".into(),
            },
            capabilities: DeviceCapabilities::default(),
            address: address.into(),
            last_seen: 0,
            online,
        }
    }

    #[test]
    fn transfer_url_with_bare_address() {
        let url = NebLinkTransfer::transfer_url("192.168.1.10:8080", "/tmp/file.txt");
        assert!(url.starts_with("http://192.168.1.10:8080/"));
        assert!(url.contains("/api/neblink/transfer"));
    }

    #[test]
    fn transfer_url_with_scheme() {
        let url = NebLinkTransfer::transfer_url("https://peer.local:443", "/path/to/file");
        assert!(url.starts_with("https://peer.local:443/"));
    }

    #[test]
    fn urlencoding_encodes_spaces() {
        assert_eq!(urlencoding::encode("hello world"), "hello%20world");
        assert_eq!(urlencoding::encode("/tmp/file.txt"), "%2Ftmp%2Ffile.txt");
        assert_eq!(urlencoding::encode("/a b/c d"), "%2Fa%20b%2Fc%20d");
    }

    #[tokio::test]
    async fn resolve_peer_not_found() {
        let store = Arc::new(RwLock::new(PeerStore::new()));
        let transfer = NebLinkTransfer::new(store);
        let result = transfer.resolve_peer("unknown").await;
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("not found"));
    }

    #[tokio::test]
    async fn resolve_peer_offline_rejected() {
        let mut store = PeerStore::new();
        store.upsert(make_peer("phone", "phone:8080", false));
        let store = Arc::new(RwLock::new(store));
        let transfer = NebLinkTransfer::new(store);
        let result = transfer.resolve_peer("phone").await;
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("offline"));
    }

    #[tokio::test]
    async fn resolve_peer_online_found() {
        let mut store = PeerStore::new();
        store.upsert(make_peer("tablet", "tablet:8080", true));
        let store = Arc::new(RwLock::new(store));
        let transfer = NebLinkTransfer::new(store);
        let result = transfer.resolve_peer("tablet").await;
        assert!(result.is_ok());
        assert_eq!(result.unwrap().address, "tablet:8080");
    }

    #[tokio::test]
    async fn fetch_remote_device_not_found() {
        let store = Arc::new(RwLock::new(PeerStore::new()));
        let transfer = NebLinkTransfer::new(store);
        let result = transfer.fetch_remote("nonexistent", "/path").await;
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("not found"));
    }

    #[tokio::test]
    async fn push_remote_device_not_found() {
        let store = Arc::new(RwLock::new(PeerStore::new()));
        let transfer = NebLinkTransfer::new(store);
        let result = transfer
            .push_remote("nonexistent", "/path", vec![1, 2, 3])
            .await;
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("not found"));
    }
}
