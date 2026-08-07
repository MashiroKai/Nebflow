//! Dropbox message model and sync logic.
//!
//! Scala: `nebflow.dropbox.DropboxModels` + messaging portion of `DropboxService`

use crate::dropbox::{DropboxConfig, MessageId};
use crate::neblink::DeviceId;
use async_trait::async_trait;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{debug, info};

/// Kind of dropbox message.
///
/// Scala: message type discriminator in DropboxMessage.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MessageKind {
    /// A plain text message.
    Text,
    /// A file offer (sender proposes to transfer a file).
    FileOffer,
    /// Acceptance of a file offer.
    FileAccept,
    /// Rejection of a file offer.
    FileReject,
}

/// Status of a message in the delivery pipeline.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MessageStatus {
    /// Message created but not yet sent.
    Pending,
    /// Message sent to server, awaiting delivery.
    Sent,
    /// Message delivered to recipient.
    Delivered,
    /// Message could not be delivered.
    Failed,
}

/// A dropbox message — either text or a file-related protocol message.
///
/// Scala: `nebflow.dropbox.DropboxMessage`
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct DropboxMessage {
    pub id: MessageId,
    pub kind: MessageKind,
    pub from: DeviceId,
    pub to: DeviceId,
    /// For text messages: the message body. For file offers: the filename.
    pub text: String,
    /// For file offers: the file size in bytes.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub file_size: Option<u64>,
    /// For file offers: the SHA-256 hash of the file content (hex).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub file_hash: Option<String>,
    /// Unix timestamp (millis) when the message was created.
    pub timestamp: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub status: Option<MessageStatus>,
}

impl DropboxMessage {
    /// Create a new text message.
    pub fn text_msg(
        from: impl Into<String>,
        to: impl Into<String>,
        text: impl Into<String>,
    ) -> Self {
        Self {
            id: generate_message_id(),
            kind: MessageKind::Text,
            from: from.into(),
            to: to.into(),
            text: text.into(),
            file_size: None,
            file_hash: None,
            timestamp: now_millis(),
            status: Some(MessageStatus::Pending),
        }
    }

    /// Create a file offer message.
    pub fn file_offer(
        from: impl Into<String>,
        to: impl Into<String>,
        filename: impl Into<String>,
        size: u64,
        hash: impl Into<String>,
    ) -> Self {
        Self {
            id: generate_message_id(),
            kind: MessageKind::FileOffer,
            from: from.into(),
            to: to.into(),
            text: filename.into(),
            file_size: Some(size),
            file_hash: Some(hash.into()),
            timestamp: now_millis(),
            status: Some(MessageStatus::Pending),
        }
    }

    /// Create a file accept message (in response to a FileOffer).
    pub fn file_accept(from: impl Into<String>, to: impl Into<String>) -> Self {
        Self {
            id: generate_message_id(),
            kind: MessageKind::FileAccept,
            from: from.into(),
            to: to.into(),
            text: String::new(),
            file_size: None,
            file_hash: None,
            timestamp: now_millis(),
            status: Some(MessageStatus::Pending),
        }
    }

    /// Create a file reject message.
    pub fn file_reject(from: impl Into<String>, to: impl Into<String>) -> Self {
        Self {
            id: generate_message_id(),
            kind: MessageKind::FileReject,
            from: from.into(),
            to: to.into(),
            text: String::new(),
            file_size: None,
            file_hash: None,
            timestamp: now_millis(),
            status: Some(MessageStatus::Pending),
        }
    }

    /// Is this message a file-related message?
    pub fn is_file_message(&self) -> bool {
        matches!(
            self.kind,
            MessageKind::FileOffer | MessageKind::FileAccept | MessageKind::FileReject
        )
    }
}

/// Trait for the message transport (HTTP to NebLink server).
#[async_trait]
pub trait MessageTransport: Send + Sync {
    async fn send_message(&self, msg: &DropboxMessage) -> Result<(), SyncError>;
}

/// Error type for sync operations.
#[derive(Debug, thiserror::Error)]
pub enum SyncError {
    #[error("network error: {0}")]
    Network(String),
    #[error("message not found: {0}")]
    NotFound(String),
    #[error("invalid message: {0}")]
    InvalidMessage(String),
}

/// The Dropbox sync manager — holds the message store and coordinates
/// sending/receiving messages.
///
/// Scala: `nebflow.dropbox.DropboxService` (messaging portion)
pub struct DropboxSync {
    config: DropboxConfig,
    transport: Option<Arc<dyn MessageTransport>>,
    /// Local message store, keyed by message ID.
    messages: Arc<RwLock<HashMap<MessageId, DropboxMessage>>>,
}

impl DropboxSync {
    pub fn new(config: DropboxConfig) -> Self {
        Self {
            config,
            transport: None,
            messages: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub fn with_transport(mut self, transport: Arc<dyn MessageTransport>) -> Self {
        self.transport = Some(transport);
        self
    }

    /// Store a message locally (e.g., a received message).
    pub async fn store(&self, msg: DropboxMessage) {
        let id = msg.id.clone();
        self.messages.write().await.insert(id, msg);
    }

    /// Send a text message to a peer.
    pub async fn send_text(
        &self,
        from: &str,
        to: &str,
        text: &str,
    ) -> Result<DropboxMessage, SyncError> {
        let msg = DropboxMessage::text_msg(from, to, text);
        self.send(msg).await
    }

    /// Offer a file to a peer.
    pub async fn offer_file(
        &self,
        from: &str,
        to: &str,
        filename: &str,
        size: u64,
        hash: &str,
    ) -> Result<DropboxMessage, SyncError> {
        if size > self.config.max_file_size {
            return Err(SyncError::InvalidMessage(format!(
                "file size {} exceeds max {}",
                size, self.config.max_file_size
            )));
        }
        let msg = DropboxMessage::file_offer(from, to, filename, size, hash);
        self.send(msg).await
    }

    /// Accept a file offer.
    pub async fn accept_file(
        &self,
        from: &str,
        to: &str,
        offer_id: &str,
    ) -> Result<DropboxMessage, SyncError> {
        // Verify the offer exists
        {
            let store = self.messages.read().await;
            let offer = store
                .get(offer_id)
                .ok_or_else(|| SyncError::NotFound(format!("file offer {} not found", offer_id)))?;
            if offer.kind != MessageKind::FileOffer {
                return Err(SyncError::InvalidMessage(format!(
                    "message {} is not a file offer",
                    offer_id
                )));
            }
        }
        let msg = DropboxMessage::file_accept(from, to);
        self.send(msg).await
    }

    /// Reject a file offer.
    pub async fn reject_file(
        &self,
        from: &str,
        to: &str,
        _offer_id: &str,
    ) -> Result<DropboxMessage, SyncError> {
        let msg = DropboxMessage::file_reject(from, to);
        self.send(msg).await
    }

    /// Internal: store and optionally send a message.
    async fn send(&self, mut msg: DropboxMessage) -> Result<DropboxMessage, SyncError> {
        if let Some(ref transport) = self.transport {
            match transport.send_message(&msg).await {
                Ok(()) => {
                    msg.status = Some(MessageStatus::Sent);
                    info!(
                        target: "dropbox::sync",
                        msg_id = %msg.id,
                        kind = ?msg.kind,
                        "message sent"
                    );
                }
                Err(e) => {
                    msg.status = Some(MessageStatus::Failed);
                    debug!(target: "dropbox::sync", error = %e, "send failed");
                    return Err(e);
                }
            }
        }
        let id = msg.id.clone();
        self.messages.write().await.insert(id, msg.clone());
        Ok(msg)
    }

    /// Get a message by ID.
    pub async fn get(&self, id: &str) -> Option<DropboxMessage> {
        self.messages.read().await.get(id).cloned()
    }

    /// Get all messages to/from a specific peer.
    pub async fn messages_with(&self, peer_id: &str) -> Vec<DropboxMessage> {
        self.messages
            .read()
            .await
            .values()
            .filter(|m| m.from == peer_id || m.to == peer_id)
            .cloned()
            .collect()
    }

    /// Get the total number of stored messages.
    pub async fn message_count(&self) -> usize {
        self.messages.read().await.len()
    }

    /// Access the config.
    pub fn config(&self) -> &DropboxConfig {
        &self.config
    }
}

// ── Helpers ───────────────────────────────────────────────────

fn generate_message_id() -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let n = COUNTER.fetch_add(1, Ordering::SeqCst);
    let ts = now_millis();
    format!("msg-{}-{}", ts, n)
}

fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU32, Ordering};

    struct MockTransport {
        sent_count: AtomicU32,
        should_fail: bool,
    }

    #[async_trait]
    impl MessageTransport for MockTransport {
        async fn send_message(&self, _msg: &DropboxMessage) -> Result<(), SyncError> {
            if self.should_fail {
                Err(SyncError::Network("mock failure".into()))
            } else {
                self.sent_count.fetch_add(1, Ordering::SeqCst);
                Ok(())
            }
        }
    }

    #[test]
    fn message_kind_serde() {
        let json = serde_json::to_string(&MessageKind::FileOffer).unwrap();
        assert_eq!(json, "\"file_offer\"");
        let back: MessageKind = serde_json::from_str(&json).unwrap();
        assert_eq!(back, MessageKind::FileOffer);
    }

    #[test]
    fn message_text_constructor() {
        let msg = DropboxMessage::text_msg("dev-a", "dev-b", "hello");
        assert_eq!(msg.kind, MessageKind::Text);
        assert_eq!(msg.from, "dev-a");
        assert_eq!(msg.to, "dev-b");
        assert_eq!(msg.text, "hello");
        assert!(!msg.is_file_message());
    }

    #[test]
    fn message_file_offer_constructor() {
        let msg = DropboxMessage::file_offer("dev-a", "dev-b", "doc.pdf", 1024, "abc123");
        assert_eq!(msg.kind, MessageKind::FileOffer);
        assert_eq!(msg.text, "doc.pdf");
        assert_eq!(msg.file_size, Some(1024));
        assert_eq!(msg.file_hash.as_deref(), Some("abc123"));
        assert!(msg.is_file_message());
    }

    #[tokio::test]
    async fn sync_send_text_no_transport() {
        let sync = DropboxSync::new(DropboxConfig::default());
        let msg = sync.send_text("dev-a", "dev-b", "hello").await.unwrap();
        assert_eq!(msg.status, Some(MessageStatus::Pending)); // no transport → stays pending
        assert_eq!(sync.message_count().await, 1);
    }

    #[tokio::test]
    async fn sync_send_text_with_transport() {
        let transport = Arc::new(MockTransport {
            sent_count: AtomicU32::new(0),
            should_fail: false,
        });
        let sync = DropboxSync::new(DropboxConfig::default()).with_transport(transport);
        let msg = sync.send_text("dev-a", "dev-b", "hello").await.unwrap();
        assert_eq!(msg.status, Some(MessageStatus::Sent));
    }

    #[tokio::test]
    async fn sync_send_failure() {
        let transport = Arc::new(MockTransport {
            sent_count: AtomicU32::new(0),
            should_fail: true,
        });
        let sync = DropboxSync::new(DropboxConfig::default()).with_transport(transport);
        let result = sync.send_text("dev-a", "dev-b", "hello").await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn sync_offer_file_size_limit() {
        let config = DropboxConfig {
            max_file_size: 100,
            ..Default::default()
        };
        let sync = DropboxSync::new(config);
        let result = sync.offer_file("a", "b", "big.bin", 200, "hash").await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn sync_offer_file_ok() {
        let sync = DropboxSync::new(DropboxConfig::default());
        let msg = sync
            .offer_file("dev-a", "dev-b", "doc.pdf", 1024, "abc123")
            .await
            .unwrap();
        assert_eq!(msg.kind, MessageKind::FileOffer);
    }

    #[tokio::test]
    async fn sync_accept_file() {
        let sync = DropboxSync::new(DropboxConfig::default());
        // First store an offer
        let offer = DropboxMessage::file_offer("dev-a", "dev-b", "file.txt", 100, "h");
        let offer_id = offer.id.clone();
        sync.store(offer).await;

        // Now accept it
        let accept = sync.accept_file("dev-b", "dev-a", &offer_id).await.unwrap();
        assert_eq!(accept.kind, MessageKind::FileAccept);
    }

    #[tokio::test]
    async fn sync_accept_nonexistent_offer() {
        let sync = DropboxSync::new(DropboxConfig::default());
        let result = sync.accept_file("dev-b", "dev-a", "nonexistent").await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn sync_messages_with_peer() {
        let sync = DropboxSync::new(DropboxConfig::default());
        sync.send_text("dev-a", "dev-b", "hello").await.unwrap();
        sync.send_text("dev-b", "dev-a", "hi").await.unwrap();
        sync.send_text("dev-a", "dev-c", "other").await.unwrap();

        let msgs = sync.messages_with("dev-b").await;
        assert_eq!(msgs.len(), 2);
    }

    #[tokio::test]
    async fn sync_store_and_get() {
        let sync = DropboxSync::new(DropboxConfig::default());
        let msg = DropboxMessage::text_msg("a", "b", "test");
        let id = msg.id.clone();
        sync.store(msg).await;
        assert!(sync.get(&id).await.is_some());
        assert!(sync.get("nonexistent").await.is_none());
    }

    #[tokio::test]
    async fn sync_reject_file() {
        let sync = DropboxSync::new(DropboxConfig::default());
        let offer = DropboxMessage::file_offer("a", "b", "f.txt", 10, "h");
        let offer_id = offer.id.clone();
        sync.store(offer).await;

        let reject = sync.reject_file("b", "a", &offer_id).await.unwrap();
        assert_eq!(reject.kind, MessageKind::FileReject);
    }
}
