//! FileHistory — VS Code-style file snapshots before overwrites.
//! Mirrors `nebflow.core.tools.FileHistory` from Scala.
//!
//! Storage layout:
//!   ~/.nebflow/history/{pathHash}/{timestamp}      — snapshot content
//!   ~/.nebflow/history/{pathHash}/{timestamp}.identity — agent identity (optional)

use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use sha2::{Digest, Sha256};

/// Configuration constants.
const DEFAULT_MAX_ENTRIES: usize = 50;
const DEFAULT_MAX_FILE_SIZE: u64 = 1024 * 1024; // 1 MB

/// File history manager. Thread-safe via Mutex on the in-memory index.
pub struct FileHistory {
    history_root: PathBuf,
    max_entries: usize,
    max_file_size: u64,
    // In-memory index: path_hash -> sorted list of snapshot timestamps (newest first)
    index: Mutex<HashMap<String, Vec<u64>>>,
}

impl FileHistory {
    pub fn new(history_root: impl Into<PathBuf>, max_entries: usize, max_file_size: u64) -> Self {
        let root = history_root.into();
        let _ = fs::create_dir_all(&root);
        Self {
            history_root: root,
            max_entries,
            max_file_size,
            index: Mutex::new(HashMap::new()),
        }
    }

    /// Create with default settings at ~/.nebflow/history.
    pub fn default_path() -> Self {
        let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
        Self::new(
            PathBuf::from(home).join(".nebflow").join("history"),
            DEFAULT_MAX_ENTRIES,
            DEFAULT_MAX_FILE_SIZE,
        )
    }

    /// Snapshot a file's current content before it gets overwritten.
    /// No-op if the file doesn't exist, is a directory, or exceeds the size limit.
    pub fn snapshot(&self, file_path: &Path, identity: Option<&str>) {
        if !file_path.exists() || file_path.is_dir() {
            return;
        }

        let size = match file_path.metadata() {
            Ok(m) => m.len(),
            Err(_) => return,
        };

        if size > self.max_file_size {
            return;
        }

        let key = path_hash(file_path);
        let dir = self.history_root.join(&key);
        if fs::create_dir_all(&dir).is_err() {
            return;
        }

        let ts = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_nanos() as u64)
            .unwrap_or(0);

        let dest = dir.join(ts.to_string());
        if fs::copy(file_path, &dest).is_err() {
            // Fallback: read-write
            if let Ok(bytes) = fs::read(file_path) {
                let _ = fs::write(&dest, bytes);
            }
        }

        // Write identity metadata alongside the snapshot
        if let Some(id) = identity {
            let meta_dest = dir.join(format!("{ts}.identity"));
            if let Ok(mut f) = fs::File::create(&meta_dest) {
                let _ = f.write_all(id.as_bytes());
            }
        }

        // Update in-memory index
        if let Ok(mut index) = self.index.lock() {
            let entries = index.entry(key.clone()).or_default();
            entries.push(ts);
            entries.sort_by(|a, b| b.cmp(a)); // newest first
            if entries.len() > self.max_entries {
                entries.truncate(self.max_entries);
            }
        }

        // Clean up excess files on disk
        self.cleanup_old(&key, &dir);
    }

    /// Clear all history.
    pub fn clear(&self) {
        if self.history_root.exists() {
            // Remove all contents but keep the root dir itself
            if let Ok(entries) = fs::read_dir(&self.history_root) {
                for entry in entries.flatten() {
                    let _ = fs::remove_dir_all(entry.path());
                }
            }
        }
        self.index.lock().unwrap().clear();
    }

    fn cleanup_old(&self, _key: &str, dir: &Path) {
        let Ok(entries) = fs::read_dir(dir) else {
            return;
        };

        let mut files: Vec<(u64, PathBuf)> = entries
            .flatten()
            .filter(|e| {
                e.path().is_file() && !e.file_name().to_string_lossy().ends_with(".identity")
            })
            .filter_map(|e| {
                let name = e.file_name().to_string_lossy().to_string();
                let ts: u64 = name.parse().ok()?;
                Some((ts, e.path()))
            })
            .collect();

        // Sort newest first
        files.sort_by_key(|b| std::cmp::Reverse(b.0));

        if files.len() > self.max_entries {
            for (_, path) in files.iter().skip(self.max_entries) {
                let _ = fs::remove_file(path);
                // Also clean up associated .identity file
                let identity_file = path.with_extension("identity");
                let identity_path = format!("{}.identity", path.to_string_lossy());
                let _ = fs::remove_file(&identity_path);
                let _ = fs::remove_file(identity_file);
            }
        }
    }
}

/// Compute SHA-256 hash of a path, take first 16 bytes as hex.
fn path_hash(path: &Path) -> String {
    let mut hasher = Sha256::new();
    hasher.update(path.to_string_lossy().as_bytes());
    let result = hasher.finalize();
    result[..16].iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snapshot_nonexistent_is_noop() {
        let tmp = tempfile::tempdir().unwrap();
        let fh = FileHistory::new(tmp.path(), 50, 1024 * 1024);
        fh.snapshot(Path::new("/nonexistent/file.txt"), None);
        // Should not crash, no files created
    }

    #[test]
    fn snapshot_creates_copy() {
        let tmp = tempfile::tempdir().unwrap();
        let fh = FileHistory::new(tmp.path(), 50, 1024 * 1024);

        let test_file = tmp.path().join("test.txt");
        fs::write(&test_file, "hello world").unwrap();

        fh.snapshot(&test_file, Some("agent-1"));

        // History dir should have a subdirectory
        let entries: Vec<_> = fs::read_dir(tmp.path()).unwrap().flatten().collect();
        assert_eq!(entries.len(), 2); // test.txt + hash dir

        // Hash dir should contain snapshot + identity
        let hash_dir = entries.iter().find(|e| e.path().is_dir()).unwrap().path();
        let snap_files: Vec<_> = fs::read_dir(&hash_dir).unwrap().flatten().collect();
        assert_eq!(snap_files.len(), 2); // snapshot + .identity
    }

    #[test]
    fn snapshot_skips_large_files() {
        let tmp = tempfile::tempdir().unwrap();
        let fh = FileHistory::new(tmp.path(), 50, 10); // 10 byte limit

        let test_file = tmp.path().join("big.txt");
        fs::write(&test_file, "this is more than 10 bytes").unwrap();

        fh.snapshot(&test_file, None);

        // No history subdirectory should be created
        let entries: Vec<_> = fs::read_dir(tmp.path()).unwrap().flatten().collect();
        assert_eq!(entries.len(), 1); // only big.txt
    }

    #[test]
    fn snapshot_skips_directories() {
        let tmp = tempfile::tempdir().unwrap();
        let fh = FileHistory::new(tmp.path(), 50, 1024 * 1024);

        fh.snapshot(tmp.path(), None); // it's a directory

        // No subdirectory created
        let entries: Vec<_> = fs::read_dir(tmp.path()).unwrap().flatten().collect();
        assert_eq!(entries.len(), 0);
    }

    #[test]
    fn clear_removes_everything() {
        let tmp = tempfile::tempdir().unwrap();
        let fh = FileHistory::new(tmp.path(), 50, 1024 * 1024);

        let test_file = tmp.path().join("test.txt");
        fs::write(&test_file, "hello").unwrap();
        fh.snapshot(&test_file, None);

        fh.clear();

        let entries: Vec<_> = fs::read_dir(tmp.path()).unwrap().flatten().collect();
        assert_eq!(entries.len(), 1); // only test.txt remains
    }

    #[test]
    fn path_hash_is_deterministic() {
        let h1 = path_hash(Path::new("/tmp/test.rs"));
        let h2 = path_hash(Path::new("/tmp/test.rs"));
        assert_eq!(h1, h2);
        assert_eq!(h1.len(), 32); // 16 bytes as hex
    }
}
