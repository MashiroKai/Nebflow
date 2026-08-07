//! Presence service — maintains a persistent WebSocket connection
//! to the NebLink server for real-time peer presence updates.
//!
//! Scala: `nebflow.neblink.NelinkPresenceService`

use crate::neblink::{heartbeat::HeartbeatTracker, DeviceId, DeviceIdentity, PeerInfo};
use async_trait::async_trait;
use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{debug, info, warn};

/// Configuration for the presence service.
#[derive(Debug, Clone)]
pub struct PresenceConfig {
    /// WebSocket URL of the NebLink server.
    pub ws_url: String,
    /// Authentication token (if required by the server).
    pub auth_token: Option<String>,
    /// Heartbeat interval in seconds.
    pub heartbeat_interval_secs: u64,
    /// Presence timeout in seconds (considered offline after this).
    pub timeout_secs: u64,
    /// Maximum reconnect backoff in seconds.
    pub max_backoff_secs: u64,
}

impl Default for PresenceConfig {
    fn default() -> Self {
        Self {
            ws_url: String::new(),
            auth_token: None,
            heartbeat_interval_secs: 5,
            timeout_secs: 10,
            max_backoff_secs: 30,
        }
    }
}

/// Connection state for the presence WebSocket.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionState {
    /// Not yet connected.
    Disconnected,
    /// Attempting to connect.
    Connecting,
    /// Connected and healthy.
    Connected,
    /// Connection lost, waiting to reconnect.
    Reconnecting,
    /// Permanently failed (max retries exceeded or shutdown requested).
    Failed,
}

/// Messages exchanged over the presence WebSocket.
///
/// Scala: WS message types in NelinkPresenceService.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(tag = "type")]
pub enum PresenceMessage {
    /// Heartbeat ping.
    #[serde(rename = "ping")]
    Ping { timestamp: u64 },
    /// Heartbeat pong.
    #[serde(rename = "pong")]
    Pong { timestamp: u64, latency_ms: u64 },
    /// A peer came online.
    #[serde(rename = "peer_online")]
    PeerOnline { peer: PeerInfo },
    /// A peer went offline.
    #[serde(rename = "peer_offline")]
    PeerOffline { device_id: DeviceId },
    /// Full peer list sync (e.g., on initial connect).
    #[serde(rename = "peer_sync")]
    PeerSync { peers: Vec<PeerInfo> },
}

/// Trait for the WebSocket transport.
/// Allows mocking in tests.
#[async_trait]
pub trait PresenceTransport: Send + Sync {
    /// Send a message to the server.
    async fn send(&self, msg: &PresenceMessage) -> Result<(), PresenceError>;
    /// Try to receive the next message (non-blocking, with timeout).
    async fn recv(&self) -> Result<PresenceMessage, PresenceError>;
    /// Check if the underlying connection is open.
    async fn is_connected(&self) -> bool;
}

/// Error type for presence operations.
#[derive(Debug, thiserror::Error)]
pub enum PresenceError {
    #[error("connection closed")]
    ConnectionClosed,
    #[error("send error: {0}")]
    Send(String),
    #[error("recv error: {0}")]
    Recv(String),
    #[error("serialization error: {0}")]
    Serialize(String),
    #[error("not connected")]
    NotConnected,
}

impl From<serde_json::Error> for PresenceError {
    fn from(e: serde_json::Error) -> Self {
        Self::Serialize(e.to_string())
    }
}

/// The presence client maintains connection state and processes
/// presence messages from the NebLink server.
///
/// Scala: `nebflow.neblink.NelinkPresenceService`
pub struct PresenceClient {
    config: PresenceConfig,
    identity: DeviceIdentity,
    transport: Arc<dyn PresenceTransport>,
    state: Arc<RwLock<ConnectionState>>,
    heartbeat: Arc<RwLock<HeartbeatTracker>>,
    consecutive_failures: Arc<RwLock<u32>>,
    shutdown: Arc<tokio::sync::Notify>,
}

impl PresenceClient {
    pub fn new(
        config: PresenceConfig,
        identity: DeviceIdentity,
        transport: Arc<dyn PresenceTransport>,
    ) -> Self {
        let hb_config = crate::neblink::heartbeat::HeartbeatConfig {
            interval: std::time::Duration::from_secs(config.heartbeat_interval_secs),
            timeout: std::time::Duration::from_secs(config.timeout_secs),
            max_backoff: std::time::Duration::from_secs(config.max_backoff_secs),
        };
        Self {
            config,
            identity,
            transport,
            state: Arc::new(RwLock::new(ConnectionState::Disconnected)),
            heartbeat: Arc::new(RwLock::new(HeartbeatTracker::new(hb_config))),
            consecutive_failures: Arc::new(RwLock::new(0)),
            shutdown: Arc::new(tokio::sync::Notify::new()),
        }
    }

    /// Get the device identity for this presence client.
    pub fn identity(&self) -> &DeviceIdentity {
        &self.identity
    }

    /// Get the current connection state.
    pub async fn state(&self) -> ConnectionState {
        *self.state.read().await
    }

    /// Send a heartbeat ping.
    pub async fn send_ping(&self) -> Result<(), PresenceError> {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;

        self.transport
            .send(&PresenceMessage::Ping { timestamp: now })
            .await?;
        self.heartbeat.write().await.on_ping_sent();
        Ok(())
    }

    /// Process an incoming presence message.
    pub async fn handle_message(&self, msg: PresenceMessage) {
        match msg {
            PresenceMessage::Pong { latency_ms, .. } => {
                self.heartbeat.write().await.on_pong();
                debug!(target: "neblink::presence", latency_ms, "pong received");
            }
            PresenceMessage::PeerOnline { peer } => {
                info!(
                    target: "neblink::presence",
                    peer = %peer.identity.device_id,
                    "peer online"
                );
            }
            PresenceMessage::PeerOffline { device_id } => {
                info!(
                    target: "neblink::presence",
                    peer = %device_id,
                    "peer offline"
                );
            }
            PresenceMessage::PeerSync { peers } => {
                debug!(
                    target: "neblink::presence",
                    count = peers.len(),
                    "peer sync"
                );
            }
            PresenceMessage::Ping { .. } => {
                // Server pinging us — respond with pong
                let now = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_millis() as u64;
                let _ = self
                    .transport
                    .send(&PresenceMessage::Pong {
                        timestamp: now,
                        latency_ms: 0,
                    })
                    .await;
            }
        }
    }

    /// Calculate the reconnect backoff duration.
    ///
    /// Scala: exponential backoff with sequence 0s, 1s, 2s, 4s, 8s, ..., max
    pub async fn reconnect_backoff(&self) -> std::time::Duration {
        let failures = *self.consecutive_failures.read().await;
        if failures == 0 {
            return std::time::Duration::ZERO;
        }
        let base_secs: u64 = 1u64
            .checked_shl(failures.saturating_sub(1))
            .unwrap_or(u64::MAX);
        std::time::Duration::from_secs(base_secs)
            .min(std::time::Duration::from_secs(self.config.max_backoff_secs))
    }

    /// Signal the presence loop to shut down.
    pub fn shutdown(&self) {
        self.shutdown.notify_waiters();
    }

    /// Set connection state.
    async fn set_state(&self, new_state: ConnectionState) {
        *self.state.write().await = new_state;
    }

    /// Run the main presence loop. Designed to be spawned as a tokio task.
    ///
    /// Loop logic (mirrors Scala NelinkPresenceService):
    /// 1. Connect to WebSocket
    /// 2. Periodically send pings
    /// 3. Receive and handle messages
    /// 4. On disconnect: exponential backoff reconnect
    pub async fn run_loop(&self) {
        loop {
            // Attempt to connect
            self.set_state(ConnectionState::Connecting).await;
            debug!(target: "neblink::presence", "connecting to presence server");

            // In a real implementation, this would open the WS connection.
            // Here we just check if the transport is connected.
            if self.transport.is_connected().await {
                self.set_state(ConnectionState::Connected).await;
                *self.consecutive_failures.write().await = 0;
                info!(target: "neblink::presence", "presence connected");

                // Main event loop
                loop {
                    // Check for shutdown
                    tokio::select! {
                        _ = self.shutdown.notified() => {
                            self.set_state(ConnectionState::Disconnected).await;
                            return;
                        }
                        // Try to receive a message
                        result = self.transport.recv() => {
                            match result {
                                Ok(msg) => self.handle_message(msg).await,
                                Err(e) => {
                                    warn!(target: "neblink::presence", error = %e, "connection error");
                                    self.set_state(ConnectionState::Reconnecting).await;
                                    break;
                                }
                            }
                        }
                    }
                }
            } else {
                warn!(target: "neblink::presence", "failed to connect");
            }

            // Reconnect with backoff
            let backoff = self.reconnect_backoff().await;
            if backoff >= std::time::Duration::from_secs(self.config.max_backoff_secs) {
                warn!(target: "neblink::presence", "max backoff reached, giving up");
                self.set_state(ConnectionState::Failed).await;
                return;
            }

            *self.consecutive_failures.write().await += 1;
            self.set_state(ConnectionState::Reconnecting).await;
            debug!(target: "neblink::presence", backoff_secs = backoff.as_secs(), "reconnecting after backoff");
            tokio::time::sleep(backoff).await;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, Ordering};

    /// Mock transport for testing.
    struct MockPresenceTransport {
        connected: AtomicBool,
        messages: tokio::sync::Mutex<std::collections::VecDeque<PresenceMessage>>,
        sent: tokio::sync::Mutex<Vec<PresenceMessage>>,
    }

    impl MockPresenceTransport {
        fn new(connected: bool) -> Self {
            Self {
                connected: AtomicBool::new(connected),
                messages: tokio::sync::Mutex::new(std::collections::VecDeque::new()),
                sent: tokio::sync::Mutex::new(Vec::new()),
            }
        }

        #[allow(dead_code)]
        async fn push_msg(&self, msg: PresenceMessage) {
            self.messages.lock().await.push_back(msg);
        }

        async fn sent_messages(&self) -> Vec<PresenceMessage> {
            self.sent.lock().await.clone()
        }
    }

    #[async_trait]
    impl PresenceTransport for MockPresenceTransport {
        async fn send(&self, msg: &PresenceMessage) -> Result<(), PresenceError> {
            self.sent.lock().await.push(msg.clone());
            Ok(())
        }

        async fn recv(&self) -> Result<PresenceMessage, PresenceError> {
            self.messages
                .lock()
                .await
                .pop_front()
                .ok_or(PresenceError::ConnectionClosed)
        }

        async fn is_connected(&self) -> bool {
            self.connected.load(Ordering::SeqCst)
        }
    }

    fn make_test_config() -> PresenceConfig {
        PresenceConfig {
            ws_url: "ws://test".into(),
            auth_token: None,
            heartbeat_interval_secs: 1,
            timeout_secs: 2,
            max_backoff_secs: 4,
        }
    }

    #[tokio::test]
    async fn presence_initial_state() {
        let transport = Arc::new(MockPresenceTransport::new(false));
        let client = PresenceClient::new(
            make_test_config(),
            DeviceIdentity::new("dev-1", "user-1"),
            transport,
        );
        assert_eq!(client.state().await, ConnectionState::Disconnected);
    }

    #[tokio::test]
    async fn presence_send_ping() {
        let transport = Arc::new(MockPresenceTransport::new(true));
        let client = PresenceClient::new(
            make_test_config(),
            DeviceIdentity::new("dev-1", "user-1"),
            transport.clone(),
        );

        client.send_ping().await.unwrap();
        let sent = transport.sent_messages().await;
        assert_eq!(sent.len(), 1);
        // Should be a Ping message
        match &sent[0] {
            PresenceMessage::Ping { .. } => {}
            other => panic!("expected Ping, got {:?}", other),
        }
    }

    #[tokio::test]
    async fn presence_handle_pong() {
        let transport = Arc::new(MockPresenceTransport::new(true));
        let client = PresenceClient::new(
            make_test_config(),
            DeviceIdentity::new("dev-1", "user-1"),
            transport,
        );

        client
            .handle_message(PresenceMessage::Pong {
                timestamp: 1000,
                latency_ms: 50,
            })
            .await;
        // Heartbeat should be healthy now
        let hb_state = client.heartbeat.read().await.state().clone();
        assert_eq!(hb_state, crate::neblink::heartbeat::HeartbeatState::Healthy);
    }

    #[tokio::test]
    async fn presence_handle_ping_responds_pong() {
        let transport = Arc::new(MockPresenceTransport::new(true));
        let client = PresenceClient::new(
            make_test_config(),
            DeviceIdentity::new("dev-1", "user-1"),
            transport.clone(),
        );

        client
            .handle_message(PresenceMessage::Ping { timestamp: 500 })
            .await;

        let sent = transport.sent_messages().await;
        assert_eq!(sent.len(), 1);
        match &sent[0] {
            PresenceMessage::Pong { .. } => {}
            other => panic!("expected Pong response, got {:?}", other),
        }
    }

    #[tokio::test]
    async fn presence_reconnect_backoff_sequence() {
        let transport = Arc::new(MockPresenceTransport::new(false));
        let client = PresenceClient::new(
            PresenceConfig {
                ws_url: "ws://test".into(),
                auth_token: None,
                heartbeat_interval_secs: 5,
                timeout_secs: 10,
                max_backoff_secs: 30,
            },
            DeviceIdentity::new("dev-1", "user-1"),
            transport,
        );

        // 0 failures → 0s
        assert_eq!(client.reconnect_backoff().await, std::time::Duration::ZERO);

        *client.consecutive_failures.write().await = 1;
        assert_eq!(
            client.reconnect_backoff().await,
            std::time::Duration::from_secs(1)
        );

        *client.consecutive_failures.write().await = 2;
        assert_eq!(
            client.reconnect_backoff().await,
            std::time::Duration::from_secs(2)
        );

        *client.consecutive_failures.write().await = 5;
        assert_eq!(
            client.reconnect_backoff().await,
            std::time::Duration::from_secs(16)
        );

        *client.consecutive_failures.write().await = 10;
        assert_eq!(
            client.reconnect_backoff().await,
            std::time::Duration::from_secs(30) // capped
        );
    }

    #[test]
    fn presence_message_serde() {
        let ping = PresenceMessage::Ping { timestamp: 12345 };
        let json = serde_json::to_string(&ping).unwrap();
        assert!(json.contains("\"type\":\"ping\""));
        let back: PresenceMessage = serde_json::from_str(&json).unwrap();
        match back {
            PresenceMessage::Ping { timestamp } => assert_eq!(timestamp, 12345),
            _ => panic!("wrong type"),
        }
    }

    #[test]
    fn presence_message_peer_offline_serde() {
        let msg = PresenceMessage::PeerOffline {
            device_id: "dev-x".into(),
        };
        let json = serde_json::to_string(&msg).unwrap();
        assert!(json.contains("\"type\":\"peer_offline\""));
        let back: PresenceMessage = serde_json::from_str(&json).unwrap();
        match back {
            PresenceMessage::PeerOffline { device_id } => assert_eq!(device_id, "dev-x"),
            _ => panic!("wrong type"),
        }
    }
}
