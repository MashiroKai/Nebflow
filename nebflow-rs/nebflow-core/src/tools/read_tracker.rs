//! ReadTracker — records file reads for context and compaction.
//! Mirrors `nebflow.core.tools.ReadTracker` from Scala.

use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

/// A single read entry.
#[derive(Debug, Clone)]
pub struct ReadEntry {
    pub path: PathBuf,
    pub timestamp: u64,
    pub is_partial_view: bool,
}

/// In-memory tracker of recent file reads. Thread-safe via Mutex.
pub struct ReadTracker {
    entries: Mutex<Vec<ReadEntry>>,
}

impl ReadTracker {
    pub fn new() -> Self {
        Self {
            entries: Mutex::new(Vec::new()),
        }
    }

    /// Record a file read. Updates timestamp if already tracked.
    pub fn record_read(&self, path: &PathBuf, is_partial_view: bool) {
        let mut entries = self.entries.lock().unwrap();
        entries.retain(|e| e.path != *path);
        entries.push(ReadEntry {
            path: path.clone(),
            timestamp: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0),
            is_partial_view,
        });
    }

    /// Clear all tracked reads.
    pub fn clear(&self) {
        self.entries.lock().unwrap().clear();
    }

    /// Return the N most recently read file paths (most recent first).
    pub fn recent_files(&self, n: usize) -> Vec<PathBuf> {
        let entries = self.entries.lock().unwrap();
        entries
            .iter()
            .rev()
            .take(n)
            .map(|e| e.path.clone())
            .collect()
    }

    /// Return all tracked paths.
    pub fn all_paths(&self) -> HashSet<PathBuf> {
        self.entries
            .lock()
            .unwrap()
            .iter()
            .map(|e| e.path.clone())
            .collect()
    }
}

impl Default for ReadTracker {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn record_and_retrieve() {
        let tracker = ReadTracker::new();
        tracker.record_read(&PathBuf::from("/tmp/a.rs"), false);
        tracker.record_read(&PathBuf::from("/tmp/b.rs"), false);
        let recent = tracker.recent_files(2);
        assert_eq!(recent.len(), 2);
        assert_eq!(recent[0], PathBuf::from("/tmp/b.rs"));
    }

    #[test]
    fn update_timestamp_on_reread() {
        let tracker = ReadTracker::new();
        tracker.record_read(&PathBuf::from("/tmp/a.rs"), false);
        tracker.record_read(&PathBuf::from("/tmp/b.rs"), false);
        tracker.record_read(&PathBuf::from("/tmp/a.rs"), false);
        let recent = tracker.recent_files(3);
        assert_eq!(recent.len(), 2);
        assert_eq!(recent[0], PathBuf::from("/tmp/a.rs"));
    }

    #[test]
    fn clear_all() {
        let tracker = ReadTracker::new();
        tracker.record_read(&PathBuf::from("/tmp/a.rs"), false);
        tracker.clear();
        assert_eq!(tracker.recent_files(10).len(), 0);
    }

    #[test]
    fn all_paths() {
        let tracker = ReadTracker::new();
        tracker.record_read(&PathBuf::from("/tmp/a.rs"), false);
        tracker.record_read(&PathBuf::from("/tmp/b.rs"), true);
        let paths = tracker.all_paths();
        assert_eq!(paths.len(), 2);
        assert!(paths.contains(&PathBuf::from("/tmp/a.rs")));
    }
}
