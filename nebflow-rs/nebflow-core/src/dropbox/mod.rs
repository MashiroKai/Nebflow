//! Dropbox — peer-to-peer file transfer and messaging.
//!
//! Ported from Scala `nebflow.dropbox` package. Provides:
//! - Text and file messaging between devices
//! - File offer/accept/reject protocol
//! - SHA-256 integrity verification
//! - HTTP-based file transfer

pub mod sync;
pub mod transfer;

// ── Re-exports ────────────────────────────────────────────────

pub use sync::{DropboxMessage, DropboxSync, MessageKind, MessageStatus};
pub use transfer::{FileTransfer, TransferError, TransferManager, TransferState};

use serde::{Deserialize, Serialize};

// ── Shared types ──────────────────────────────────────────────

/// A unique identifier for a dropbox message or transfer.
pub type MessageId = String;

/// Configuration for the Dropbox subsystem.
///
/// Scala: various config values in DropboxService.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DropboxConfig {
    /// Directory where downloaded files are stored.
    pub downloads_dir: String,
    /// Maximum file size allowed for transfer (bytes).
    pub max_file_size: u64,
    /// Chunk size for streaming transfers.
    pub chunk_size: usize,
    /// Whether to resolve filename conflicts with timestamps.
    pub timestamp_conflict_resolution: bool,
}

impl Default for DropboxConfig {
    fn default() -> Self {
        Self {
            downloads_dir: "downloads".to_string(),
            max_file_size: 100 * 1024 * 1024, // 100 MB
            chunk_size: 64 * 1024,            // 64 KB
            timestamp_conflict_resolution: true,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dropbox_config_default() {
        let cfg = DropboxConfig::default();
        assert_eq!(cfg.downloads_dir, "downloads");
        assert_eq!(cfg.max_file_size, 100 * 1024 * 1024);
        assert_eq!(cfg.chunk_size, 64 * 1024);
        assert!(cfg.timestamp_conflict_resolution);
    }
}
