//! WebSocket hub — mirrors Scala WsHub.scala.
//!
//! Multicast hub that decouples root agents from individual WebSocket
//! connections. All WS connections register their per-connection send
//! callback; agents broadcast events to every registered connection.

use serde_json::Value;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{mpsc, Mutex};

/// A connection's send channel — sends JSON messages to the WS client.
pub type WsSender = mpsc::UnboundedSender<String>;

/// WebSocket multicast hub.
///
/// Thread-safe: all operations go through an internal `Mutex`.
#[derive(Clone)]
pub struct WsHub {
    inner: Arc<Mutex<HashMap<String, WsSender>>>,
}

impl WsHub {
    /// Create a new empty hub.
    pub fn new() -> Self {
        Self {
            inner: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    /// Register a connection. Returns a unique connection ID.
    pub async fn register(&self, sender: WsSender) -> String {
        let id = uuid::Uuid::new_v4().to_string()[..8].to_string();
        self.inner.lock().await.insert(id.clone(), sender);
        id
    }

    /// Remove a connection by ID.
    pub async fn unregister(&self, id: &str) {
        self.inner.lock().await.remove(id);
    }

    /// Broadcast a JSON message to every registered connection.
    /// Errors on individual sends are silently ignored (connection closed).
    pub async fn broadcast(&self, json: Value) {
        let conns = self.inner.lock().await;
        let text = serde_json::to_string(&json).unwrap_or_else(|_| "{}".to_string());
        for sender in conns.values() {
            // send is non-blocking; if the channel is closed, it returns an error
            // which we ignore — the connection will be cleaned up later.
            let _ = sender.send(text.clone());
        }
    }

    /// Send to a single connection by ID.
    pub async fn send_to(&self, id: &str, json: Value) {
        let conns = self.inner.lock().await;
        if let Some(sender) = conns.get(id) {
            let text = serde_json::to_string(&json).unwrap_or_else(|_| "{}".to_string());
            let _ = sender.send(text);
        }
    }

    /// Get the number of connected clients.
    pub async fn connection_count(&self) -> usize {
        self.inner.lock().await.len()
    }
}

impl Default for WsHub {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::sync::mpsc;

    #[tokio::test]
    async fn register_and_unregister() {
        let hub = WsHub::new();
        let (tx, _rx) = mpsc::unbounded_channel();
        let id = hub.register(tx).await;
        assert_eq!(hub.connection_count().await, 1);
        hub.unregister(&id).await;
        assert_eq!(hub.connection_count().await, 0);
    }

    #[tokio::test]
    async fn broadcast_reaches_all() {
        let hub = WsHub::new();
        let (tx1, mut rx1) = mpsc::unbounded_channel();
        let (tx2, mut rx2) = mpsc::unbounded_channel();
        hub.register(tx1).await;
        hub.register(tx2).await;

        hub.broadcast(serde_json::json!({"type": "test"})).await;

        let msg1 = rx1.recv().await.unwrap();
        let msg2 = rx2.recv().await.unwrap();
        assert!(msg1.contains("test"));
        assert!(msg2.contains("test"));
    }

    #[tokio::test]
    async fn send_to_specific_connection() {
        let hub = WsHub::new();
        let (tx1, mut rx1) = mpsc::unbounded_channel();
        let (tx2, mut rx2) = mpsc::unbounded_channel();
        let id1 = hub.register(tx1).await;
        let _id2 = hub.register(tx2).await;

        hub.send_to(&id1, serde_json::json!({"type": "private"}))
            .await;

        let msg = rx1.recv().await.unwrap();
        assert!(msg.contains("private"));
        // rx2 should not receive anything
        assert!(rx2.try_recv().is_err());
    }

    #[tokio::test]
    async fn broadcast_to_closed_connection_ignored() {
        let hub = WsHub::new();
        let (tx, _rx) = mpsc::unbounded_channel();
        let _id = hub.register(tx).await;
        // _rx is dropped here, simulating a closed connection

        // Should not panic
        hub.broadcast(serde_json::json!({"type": "test"})).await;
    }

    #[tokio::test]
    async fn send_to_nonexistent_id_is_noop() {
        let hub = WsHub::new();
        hub.send_to("nonexistent", serde_json::json!({"type": "test"}))
            .await;
        // No panic, no error
    }

    #[tokio::test]
    async fn connection_count_tracks_registrations() {
        let hub = WsHub::new();
        assert_eq!(hub.connection_count().await, 0);
        let (tx1, _rx1) = mpsc::unbounded_channel();
        let (tx2, _rx2) = mpsc::unbounded_channel();
        hub.register(tx1).await;
        assert_eq!(hub.connection_count().await, 1);
        hub.register(tx2).await;
        assert_eq!(hub.connection_count().await, 2);
    }

    #[tokio::test]
    async fn multiple_hubs_are_independent() {
        let hub1 = WsHub::new();
        let hub2 = WsHub::new();
        let (tx, _rx) = mpsc::unbounded_channel();
        hub1.register(tx).await;
        assert_eq!(hub1.connection_count().await, 1);
        assert_eq!(hub2.connection_count().await, 0);
    }

    #[tokio::test]
    async fn register_returns_unique_ids() {
        let hub = WsHub::new();
        let (tx1, _rx1) = mpsc::unbounded_channel();
        let (tx2, _rx2) = mpsc::unbounded_channel();
        let id1 = hub.register(tx1).await;
        let id2 = hub.register(tx2).await;
        assert_ne!(id1, id2);
    }
}
