//! File transfer logic — HTTP-based chunked file transfer with SHA-256 verification.
//!
//! Scala: `nebflow.dropbox.DropboxService` (file transfer portion) + `DropboxUtil`

use crate::dropbox::DropboxConfig;
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use tokio::io::AsyncWriteExt;
use tracing::{debug, info};

/// Error type for file transfer operations.
#[derive(Debug, thiserror::Error)]
pub enum TransferError {
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("hash mismatch: expected {expected}, got {actual}")]
    HashMismatch { expected: String, actual: String },
    #[error("file too large: {size} bytes (max {max})")]
    TooLarge { size: u64, max: u64 },
    #[error("http error: {0}")]
    Http(String),
    #[error("transfer not found: {0}")]
    NotFound(String),
    #[error("invalid state: {0}")]
    InvalidState(String),
}

impl From<reqwest::Error> for TransferError {
    fn from(e: reqwest::Error) -> Self {
        Self::Http(e.to_string())
    }
}

/// State of a file transfer.
///
/// Scala: transfer state in FileTransfer.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TransferState {
    /// File offered, awaiting acceptance.
    Offered,
    /// Transfer accepted, in progress.
    Transferring,
    /// Transfer completed and verified.
    Completed,
    /// Transfer rejected by recipient.
    Rejected,
    /// Transfer failed (network error, hash mismatch, etc.).
    Failed,
}

/// Metadata for a file transfer.
///
/// Scala: `nebflow.dropbox.FileTransfer`
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct FileTransfer {
    pub id: String,
    pub filename: String,
    pub from_device: String,
    pub to_device: String,
    pub size: u64,
    pub expected_hash: String,
    pub state: TransferState,
    pub bytes_transferred: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub local_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub actual_hash: Option<String>,
    pub created_at: u64,
    pub completed_at: Option<u64>,
}

impl FileTransfer {
    pub fn new(
        filename: impl Into<String>,
        from: impl Into<String>,
        to: impl Into<String>,
        size: u64,
        hash: impl Into<String>,
    ) -> Self {
        Self {
            id: format!("transfer-{}", now_millis()),
            filename: filename.into(),
            from_device: from.into(),
            to_device: to.into(),
            size,
            expected_hash: hash.into(),
            state: TransferState::Offered,
            bytes_transferred: 0,
            local_path: None,
            actual_hash: None,
            created_at: now_millis(),
            completed_at: None,
        }
    }

    /// Progress as a fraction (0.0 to 1.0).
    pub fn progress(&self) -> f64 {
        if self.size == 0 {
            return 1.0;
        }
        self.bytes_transferred as f64 / self.size as f64
    }

    /// Is the transfer finished (any terminal state)?
    pub fn is_terminal(&self) -> bool {
        matches!(
            self.state,
            TransferState::Completed | TransferState::Rejected | TransferState::Failed
        )
    }

    /// Mark transfer as completed.
    pub fn complete(&mut self, local_path: impl Into<String>, actual_hash: impl Into<String>) {
        self.state = TransferState::Completed;
        self.bytes_transferred = self.size;
        self.local_path = Some(local_path.into());
        self.actual_hash = Some(actual_hash.into());
        self.completed_at = Some(now_millis());
    }

    /// Mark transfer as failed.
    pub fn fail(&mut self) {
        self.state = TransferState::Failed;
        self.completed_at = Some(now_millis());
    }

    /// Mark transfer as rejected.
    pub fn reject(&mut self) {
        self.state = TransferState::Rejected;
        self.completed_at = Some(now_millis());
    }
}

/// Manages file transfers — writing received data to disk, verifying hashes.
///
/// Scala: file transfer portion of `DropboxService` + `DropboxUtil`
pub struct TransferManager {
    config: DropboxConfig,
}

impl TransferManager {
    pub fn new(config: DropboxConfig) -> Self {
        Self { config }
    }

    /// Compute the SHA-256 hash of a byte slice (hex-encoded).
    pub fn compute_hash(data: &[u8]) -> String {
        let mut hasher = Sha256::new();
        hasher.update(data);
        let result = hasher.finalize();
        hex_encode(&result)
    }

    /// Verify that data matches the expected hash.
    pub fn verify_hash(data: &[u8], expected: &str) -> Result<(), TransferError> {
        let actual = Self::compute_hash(data);
        if actual.eq_ignore_ascii_case(expected) {
            Ok(())
        } else {
            Err(TransferError::HashMismatch {
                expected: expected.to_string(),
                actual,
            })
        }
    }

    /// Resolve the final download path for a filename, handling conflicts
    /// by appending a timestamp suffix.
    ///
    /// Scala: `DropboxUtil.resolveFinalPath`
    pub fn resolve_final_path(&self, filename: &str) -> PathBuf {
        let base = Path::new(&self.config.downloads_dir);
        let direct = base.join(filename);

        if !self.config.timestamp_conflict_resolution || !direct.exists() {
            return direct;
        }

        // File exists — append timestamp before extension
        let path = Path::new(filename);
        let stem = path
            .file_stem()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_else(|| filename.to_string());
        let ext = path
            .extension()
            .map(|s| format!(".{}", s.to_string_lossy()))
            .unwrap_or_default();
        let ts = now_millis();
        let new_name = format!("{}_{}{}", stem, ts, ext);
        base.join(new_name)
    }

    /// Write received file data to disk, verifying the hash.
    ///
    /// Scala: `DropboxUtil.streamToFileWithHash` + hash verification in `DropboxService`
    pub async fn write_file(
        &self,
        transfer: &mut FileTransfer,
        data: &[u8],
    ) -> Result<PathBuf, TransferError> {
        // Check size limit
        if data.len() as u64 > self.config.max_file_size {
            return Err(TransferError::TooLarge {
                size: data.len() as u64,
                max: self.config.max_file_size,
            });
        }

        // Verify hash
        Self::verify_hash(data, &transfer.expected_hash)?;

        // Ensure downloads dir exists
        let dir = Path::new(&self.config.downloads_dir);
        if !dir.exists() {
            tokio::fs::create_dir_all(dir).await?;
        }

        // Resolve path (avoid conflicts)
        let path = self.resolve_final_path(&transfer.filename);

        // Write file
        let mut file = tokio::fs::File::create(&path).await?;
        file.write_all(data).await?;
        file.flush().await?;

        let actual_hash = Self::compute_hash(data);
        let path_str = path.to_string_lossy().to_string();
        transfer.complete(path_str, actual_hash);

        info!(
            target: "dropbox::transfer",
            filename = %transfer.filename,
            path = %path.display(),
            "file written and verified"
        );

        Ok(path)
    }

    /// Write data in chunks, updating transfer progress.
    ///
    /// This is the streaming variant for large files.
    pub async fn write_file_chunked<R>(
        &self,
        transfer: &mut FileTransfer,
        mut reader: R,
    ) -> Result<PathBuf, TransferError>
    where
        R: tokio::io::AsyncRead + Unpin,
    {
        use tokio::io::AsyncReadExt;

        let dir = Path::new(&self.config.downloads_dir);
        if !dir.exists() {
            tokio::fs::create_dir_all(dir).await?;
        }

        let path = self.resolve_final_path(&transfer.filename);
        let mut file = tokio::fs::File::create(&path).await?;
        let mut hasher = Sha256::new();
        let mut buf = vec![0u8; self.config.chunk_size];
        let mut total_written: u64 = 0;

        transfer.state = TransferState::Transferring;

        loop {
            let n = reader.read(&mut buf).await?;
            if n == 0 {
                break;
            }

            // Check size limit
            total_written += n as u64;
            if total_written > self.config.max_file_size {
                transfer.fail();
                return Err(TransferError::TooLarge {
                    size: total_written,
                    max: self.config.max_file_size,
                });
            }

            file.write_all(&buf[..n]).await?;
            hasher.update(&buf[..n]);
            transfer.bytes_transferred = total_written;
        }

        file.flush().await?;

        let hash_result = hasher.finalize();
        let actual_hash = hex_encode(&hash_result);

        // Verify hash
        if !actual_hash.eq_ignore_ascii_case(&transfer.expected_hash) {
            transfer.fail();
            return Err(TransferError::HashMismatch {
                expected: transfer.expected_hash.clone(),
                actual: actual_hash,
            });
        }

        let path_str = path.to_string_lossy().to_string();
        transfer.complete(path_str, actual_hash);

        Ok(path)
    }

    /// Download a file from a URL using reqwest.
    ///
    /// Scala: HTTP file download in DropboxService.
    pub async fn download_from_url(
        &self,
        transfer: &mut FileTransfer,
        url: &str,
    ) -> Result<PathBuf, TransferError> {
        debug!(target: "dropbox::transfer", url, "downloading file");

        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(300))
            .build()?;

        let response = client.get(url).send().await?;

        if !response.status().is_success() {
            transfer.fail();
            return Err(TransferError::Http(format!(
                "server returned {}",
                response.status()
            )));
        }

        // Check content-length if provided
        if let Some(len) = response.content_length() {
            if len > self.config.max_file_size {
                transfer.fail();
                return Err(TransferError::TooLarge {
                    size: len,
                    max: self.config.max_file_size,
                });
            }
        }

        let bytes = response.bytes().await?;
        self.write_file(transfer, &bytes).await
    }
}

/// Encode bytes as lowercase hex string.
fn hex_encode(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{:02x}", b)).collect()
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
    use tempfile::TempDir;

    fn test_config(dir: &Path) -> DropboxConfig {
        DropboxConfig {
            downloads_dir: dir.to_string_lossy().to_string(),
            max_file_size: 10 * 1024 * 1024,
            chunk_size: 1024,
            timestamp_conflict_resolution: true,
        }
    }

    #[test]
    fn compute_hash_known_value() {
        // SHA-256 of empty string
        let hash = TransferManager::compute_hash(b"");
        assert_eq!(
            hash,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );

        // SHA-256 of "hello"
        let hash = TransferManager::compute_hash(b"hello");
        assert_eq!(
            hash,
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        );
    }

    #[test]
    fn verify_hash_ok() {
        let data = b"hello";
        let hash = TransferManager::compute_hash(data);
        assert!(TransferManager::verify_hash(data, &hash).is_ok());
    }

    #[test]
    fn verify_hash_mismatch() {
        let result = TransferManager::verify_hash(b"hello", "0000000000000000");
        assert!(result.is_err());
    }

    #[test]
    fn verify_hash_case_insensitive() {
        let data = b"test";
        let hash = TransferManager::compute_hash(data);
        let upper = hash.to_uppercase();
        assert!(TransferManager::verify_hash(data, &upper).is_ok());
    }

    #[tokio::test]
    async fn write_file_success() {
        let tmp = TempDir::new().unwrap();
        let mgr = TransferManager::new(test_config(tmp.path()));

        let data = b"file content here";
        let hash = TransferManager::compute_hash(data);
        let mut transfer = FileTransfer::new("test.txt", "dev-a", "dev-b", data.len() as u64, hash);

        let path = mgr.write_file(&mut transfer, data).await.unwrap();
        assert!(path.exists());
        assert_eq!(transfer.state, TransferState::Completed);
        assert!(transfer.actual_hash.is_some());
        assert!(transfer.completed_at.is_some());

        // Verify file content
        let written = tokio::fs::read(&path).await.unwrap();
        assert_eq!(written, data);
    }

    #[tokio::test]
    async fn write_file_hash_mismatch() {
        let tmp = TempDir::new().unwrap();
        let mgr = TransferManager::new(test_config(tmp.path()));

        let mut transfer = FileTransfer::new("test.txt", "dev-a", "dev-b", 100, "wronghash");

        let result = mgr.write_file(&mut transfer, b"some data").await;
        assert!(result.is_err());
        assert_eq!(transfer.state, TransferState::Offered); // not updated on error from write_file
    }

    #[tokio::test]
    async fn write_file_too_large() {
        let tmp = TempDir::new().unwrap();
        let mut config = test_config(tmp.path());
        config.max_file_size = 5;
        let mgr = TransferManager::new(config);

        let data = b"this is more than 5 bytes";
        let hash = TransferManager::compute_hash(data);
        let mut transfer = FileTransfer::new("big.txt", "a", "b", data.len() as u64, hash);

        let result = mgr.write_file(&mut transfer, data).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn write_file_chunked_success() {
        let tmp = TempDir::new().unwrap();
        let mgr = TransferManager::new(test_config(tmp.path()));

        let data = b"chunked file data that is longer than one chunk";
        let hash = TransferManager::compute_hash(data);
        let mut transfer = FileTransfer::new("chunked.bin", "a", "b", data.len() as u64, hash);

        let cursor = std::io::Cursor::new(data.to_vec());
        let path = mgr.write_file_chunked(&mut transfer, cursor).await.unwrap();
        assert!(path.exists());
        assert_eq!(transfer.state, TransferState::Completed);
        assert_eq!(transfer.bytes_transferred, data.len() as u64);

        let written = tokio::fs::read(&path).await.unwrap();
        assert_eq!(written, data);
    }

    #[tokio::test]
    async fn write_file_chunked_hash_mismatch() {
        let tmp = TempDir::new().unwrap();
        let mgr = TransferManager::new(test_config(tmp.path()));

        let data = b"some data";
        let mut transfer = FileTransfer::new("bad.bin", "a", "b", data.len() as u64, "wronghash");
        let cursor = std::io::Cursor::new(data.to_vec());

        let result = mgr.write_file_chunked(&mut transfer, cursor).await;
        assert!(result.is_err());
        assert_eq!(transfer.state, TransferState::Failed);
    }

    #[tokio::test]
    async fn resolve_final_path_no_conflict() {
        let tmp = TempDir::new().unwrap();
        let mgr = TransferManager::new(test_config(tmp.path()));
        let path = mgr.resolve_final_path("newfile.txt");
        assert_eq!(path.file_name().unwrap(), "newfile.txt");
    }

    #[tokio::test]
    async fn resolve_final_path_with_conflict() {
        let tmp = TempDir::new().unwrap();
        let mgr = TransferManager::new(test_config(tmp.path()));

        // Create the file first
        let dir = Path::new(&mgr.config.downloads_dir);
        tokio::fs::create_dir_all(dir).await.unwrap();
        tokio::fs::write(dir.join("exists.txt"), b"old")
            .await
            .unwrap();

        // Now resolve should give a different name
        let path = mgr.resolve_final_path("exists.txt");
        let name = path.file_name().unwrap().to_string_lossy().to_string();
        assert_ne!(name, "exists.txt");
        assert!(name.starts_with("exists_"));
        assert!(name.ends_with(".txt"));
    }

    #[test]
    fn file_transfer_progress() {
        let mut t = FileTransfer::new("f.txt", "a", "b", 100, "h");
        assert_eq!(t.progress(), 0.0);
        t.bytes_transferred = 50;
        assert!((t.progress() - 0.5).abs() < 0.001);
        t.bytes_transferred = 100;
        assert!((t.progress() - 1.0).abs() < 0.001);
    }

    #[test]
    fn file_transfer_is_terminal() {
        let mut t = FileTransfer::new("f.txt", "a", "b", 10, "h");
        assert!(!t.is_terminal());

        t.complete("/tmp/f.txt", "h");
        assert!(t.is_terminal());

        let mut t2 = FileTransfer::new("f.txt", "a", "b", 10, "h");
        t2.fail();
        assert!(t2.is_terminal());

        let mut t3 = FileTransfer::new("f.txt", "a", "b", 10, "h");
        t3.reject();
        assert!(t3.is_terminal());
    }

    #[test]
    fn hex_encode_known() {
        assert_eq!(hex_encode(&[0x00, 0xff]), "00ff");
        assert_eq!(hex_encode(&[0xab, 0xcd, 0xef]), "abcdef");
    }
}
