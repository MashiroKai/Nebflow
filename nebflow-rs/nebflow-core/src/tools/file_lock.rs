//! FileLockManager — per-file write lock to serialize concurrent writes.
//! Mirrors `nebflow.core.tools.FileLockManager` from Scala.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

/// Per-file write lock shared by all agents.
/// Each file path maps to a binary semaphore (permit count = 1).
pub struct FileLockManager {
    locks: Mutex<HashMap<PathBuf, Arc<Mutex<()>>>>,
}

impl FileLockManager {
    pub fn new() -> Self {
        Self {
            locks: Mutex::new(HashMap::new()),
        }
    }

    /// Get or create a lock for the given path.
    fn get_or_create(&self, path: &Path) -> Arc<Mutex<()>> {
        let mut locks = self.locks.lock().unwrap();
        locks
            .entry(path.to_path_buf())
            .or_insert_with(|| Arc::new(Mutex::new(())))
            .clone()
    }

    /// Execute a closure with the write lock held for the given path.
    pub fn with_write_lock<R>(&self, path: &Path, f: impl FnOnce() -> R) -> R {
        let lock = self.get_or_create(path);
        let _guard = lock.lock().unwrap();
        f()
    }
}

impl Default for FileLockManager {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn basic_lock_unlock() {
        let mgr = FileLockManager::new();
        let path = PathBuf::from("/tmp/test.rs");
        let result = mgr.with_write_lock(&path, || 42);
        assert_eq!(result, 42);
    }

    #[test]
    fn same_path_reuses_lock() {
        let mgr = FileLockManager::new();
        let path = PathBuf::from("/tmp/test.rs");
        mgr.with_write_lock(&path, || {
            // Should acquire the same lock
        });
        // Should not deadlock
        mgr.with_write_lock(&path, || {});
    }

    #[test]
    fn different_paths_independent() {
        let mgr = FileLockManager::new();
        let p1 = PathBuf::from("/tmp/a.rs");
        let p2 = PathBuf::from("/tmp/b.rs");
        // Locking p1 should not block p2
        let lock1 = mgr.get_or_create(&p1);
        let _guard = lock1.lock().unwrap();
        let result = mgr.with_write_lock(&p2, || "ok");
        assert_eq!(result, "ok");
    }
}
