//! NebLink — peer-to-peer device discovery and presence.
//!
//! Ported from Scala `nebflow.neblink` package. Provides:
//! - Device identity and discovery protocol
//! - Presence service (WebSocket persistent connection with heartbeat)
//! - Peer capability advertisement

pub mod capabilities;
pub mod discovery;
pub mod heartbeat;
pub mod presence;
pub mod transfer;

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

// ── Re-exports ────────────────────────────────────────────────

pub use capabilities::{Capability, DeviceCapabilities};
pub use discovery::{DiscoveryClient, DiscoveryConfig};
pub use heartbeat::{HeartbeatConfig, HeartbeatState};
pub use presence::{ConnectionState, PresenceClient, PresenceConfig};
pub use transfer::NebLinkTransfer;

// ── Core identity types ───────────────────────────────────────

/// Unique identifier for a device in the NebLink mesh.
/// Corresponds to Scala `DeviceIdentity.deviceId`.
pub type DeviceId = String;

/// Unique identifier for a user across devices.
pub type UserId = String;

/// Identifies this device on the NebLink network.
///
/// Scala: `nebflow.neblink.DeviceIdentity`
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DeviceIdentity {
    pub device_id: DeviceId,
    pub user_id: UserId,
    pub device_name: String,
    pub platform: String,
}

impl DeviceIdentity {
    pub fn new(device_id: impl Into<String>, user_id: impl Into<String>) -> Self {
        Self {
            device_id: device_id.into(),
            user_id: user_id.into(),
            device_name: String::new(),
            platform: String::new(),
        }
    }
}

/// Information broadcast during discovery.
///
/// Scala: `nebflow.neblink.DeviceDiscoveryInfo`
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceDiscoveryInfo {
    pub identity: DeviceIdentity,
    pub capabilities: DeviceCapabilities,
    pub neblink_server: String,
    pub last_seen: u64,
}

/// Information about a discovered peer.
///
/// Scala: `nebflow.neblink.PeerInfo`
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerInfo {
    pub identity: DeviceIdentity,
    pub capabilities: DeviceCapabilities,
    pub address: String,
    pub last_seen: u64,
    pub online: bool,
}

/// Configuration for the NebLink subsystem.
///
/// Scala: `nebflow.neblink.NelinkConfig`
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NeblinkConfig {
    pub server_url: String,
    pub discovery_interval_secs: u64,
    pub heartbeat_interval_secs: u64,
    pub presence_timeout_secs: u64,
    pub max_reconnect_backoff_secs: u64,
}

impl Default for NeblinkConfig {
    fn default() -> Self {
        Self {
            server_url: String::new(),
            discovery_interval_secs: 30,
            heartbeat_interval_secs: 5,
            presence_timeout_secs: 10,
            max_reconnect_backoff_secs: 30,
        }
    }
}

/// In-memory store of known peers.
///
/// Scala: `nebflow.neblink.PeerDescriptionStore`
#[derive(Debug, Clone, Default)]
pub struct PeerStore {
    peers: HashMap<DeviceId, PeerInfo>,
}

impl PeerStore {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn upsert(&mut self, peer: PeerInfo) {
        self.peers.insert(peer.identity.device_id.clone(), peer);
    }

    pub fn get(&self, device_id: &str) -> Option<&PeerInfo> {
        self.peers.get(device_id)
    }

    pub fn remove(&mut self, device_id: &str) -> Option<PeerInfo> {
        self.peers.remove(device_id)
    }

    pub fn online_peers(&self) -> Vec<&PeerInfo> {
        self.peers.values().filter(|p| p.online).collect()
    }

    pub fn all_peers(&self) -> Vec<&PeerInfo> {
        self.peers.values().collect()
    }

    pub fn len(&self) -> usize {
        self.peers.len()
    }

    pub fn is_empty(&self) -> bool {
        self.peers.is_empty()
    }

    pub fn clear_offline(&mut self, older_than: u64, now: u64) {
        self.peers
            .retain(|_, p| p.online || (now - p.last_seen) < older_than);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn device_identity_new() {
        let id = DeviceIdentity::new("dev-1", "user-1");
        assert_eq!(id.device_id, "dev-1");
        assert_eq!(id.user_id, "user-1");
        assert!(id.device_name.is_empty());
    }

    #[test]
    fn peer_store_upsert_and_get() {
        let mut store = PeerStore::new();
        let peer = PeerInfo {
            identity: DeviceIdentity::new("dev-2", "user-1"),
            capabilities: DeviceCapabilities::default(),
            address: "192.168.1.10:8080".into(),
            last_seen: 1000,
            online: true,
        };
        store.upsert(peer.clone());
        assert_eq!(store.len(), 1);
        assert!(store.get("dev-2").is_some());
    }

    #[test]
    fn peer_store_online_filter() {
        let mut store = PeerStore::new();
        store.upsert(PeerInfo {
            identity: DeviceIdentity::new("online-dev", "u"),
            capabilities: DeviceCapabilities::default(),
            address: String::new(),
            last_seen: 0,
            online: true,
        });
        store.upsert(PeerInfo {
            identity: DeviceIdentity::new("offline-dev", "u"),
            capabilities: DeviceCapabilities::default(),
            address: String::new(),
            last_seen: 0,
            online: false,
        });
        assert_eq!(store.online_peers().len(), 1);
        assert_eq!(store.all_peers().len(), 2);
    }

    #[test]
    fn peer_store_remove() {
        let mut store = PeerStore::new();
        store.upsert(PeerInfo {
            identity: DeviceIdentity::new("dev-x", "u"),
            capabilities: DeviceCapabilities::default(),
            address: String::new(),
            last_seen: 0,
            online: true,
        });
        assert!(store.remove("dev-x").is_some());
        assert!(store.get("dev-x").is_none());
        assert!(store.is_empty());
    }

    #[test]
    fn peer_store_clear_offline() {
        let mut store = PeerStore::new();
        // offline but recent
        store.upsert(PeerInfo {
            identity: DeviceIdentity::new("recent", "u"),
            capabilities: DeviceCapabilities::default(),
            address: String::new(),
            last_seen: 90,
            online: false,
        });
        // offline and stale
        store.upsert(PeerInfo {
            identity: DeviceIdentity::new("stale", "u"),
            capabilities: DeviceCapabilities::default(),
            address: String::new(),
            last_seen: 0,
            online: false,
        });
        store.clear_offline(60, 100);
        assert_eq!(store.len(), 1); // only recent survives
        assert!(store.get("recent").is_some());
    }

    #[test]
    fn neblink_config_default() {
        let cfg = NeblinkConfig::default();
        assert_eq!(cfg.discovery_interval_secs, 30);
        assert_eq!(cfg.heartbeat_interval_secs, 5);
        assert_eq!(cfg.presence_timeout_secs, 10);
        assert_eq!(cfg.max_reconnect_backoff_secs, 30);
    }
}
