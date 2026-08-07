//! Mounted flow store — persists the list of mounted flows per session.
//!
//! Mirrors Scala `MountedFlowStore.scala`.
//! Storage: `~/.nebflow/sessions/<sessionId>/flows.json`

use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use tokio::fs;

/// A mounted flow entry.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MountedFlowEntry {
    pub name: String,
    pub flow_name: String,
}

/// Persists the list of mounted flows per session.
pub struct MountedFlowStore {
    data_root: PathBuf,
}

impl MountedFlowStore {
    pub fn new(data_root: PathBuf) -> Self {
        Self { data_root }
    }

    pub fn default_for(home: &std::path::Path) -> Self {
        Self::new(home.join(".nebflow"))
    }

    fn session_dir(&self, session_id: &str) -> PathBuf {
        self.data_root.join("sessions").join(session_id)
    }

    fn store_file(&self, session_id: &str) -> PathBuf {
        self.session_dir(session_id).join("flows.json")
    }

    /// Save the list of mounted flows (atomic write).
    pub async fn save(&self, session_id: &str, entries: &[MountedFlowEntry]) -> Result<(), String> {
        if session_id.is_empty() {
            return Ok(());
        }

        let dir = self.session_dir(session_id);
        let file = self.store_file(session_id);
        let tmp = dir.join("flows.json.tmp");

        fs::create_dir_all(&dir).await.map_err(|e| e.to_string())?;
        let json = serde_json::json!({ "flows": entries });
        let content = serde_json::to_string(&json).map_err(|e| e.to_string())?;
        fs::write(&tmp, &content).await.map_err(|e| e.to_string())?;
        fs::rename(&tmp, &file).await.map_err(|e| e.to_string())?;

        Ok(())
    }

    /// Load the list of mounted flows.
    pub async fn load(&self, session_id: &str) -> Result<Vec<MountedFlowEntry>, String> {
        let file = self.store_file(session_id);
        if !file.exists() {
            return Ok(Vec::new());
        }

        let content = fs::read_to_string(&file).await.map_err(|e| e.to_string())?;
        let json: serde_json::Value = serde_json::from_str(&content).map_err(|e| e.to_string())?;

        if let Some(flows) = json.get("flows").and_then(|f| f.as_array()) {
            serde_json::from_value(serde_json::Value::Array(flows.clone()))
                .map_err(|e| e.to_string())
        } else {
            // Try parsing as bare array
            serde_json::from_str(&content).map_err(|e| e.to_string())
        }
    }

    /// Delete the store file.
    pub async fn delete(&self, session_id: &str) -> Result<(), String> {
        let file = self.store_file(session_id);
        if file.exists() {
            fs::remove_file(&file).await.map_err(|e| e.to_string())?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[tokio::test]
    async fn save_and_load() {
        let tmp = TempDir::new().unwrap();
        let store = MountedFlowStore::new(tmp.path().to_path_buf());

        let entries = vec![
            MountedFlowEntry {
                name: "review".into(),
                flow_name: "code-review".into(),
            },
            MountedFlowEntry {
                name: "deploy".into(),
                flow_name: "deploy-flow".into(),
            },
        ];

        store.save("sess-1", &entries).await.unwrap();
        let loaded = store.load("sess-1").await.unwrap();

        assert_eq!(loaded.len(), 2);
        assert_eq!(loaded[0].name, "review");
        assert_eq!(loaded[1].flow_name, "deploy-flow");
    }

    #[tokio::test]
    async fn load_empty_returns_empty() {
        let tmp = TempDir::new().unwrap();
        let store = MountedFlowStore::new(tmp.path().to_path_buf());
        let loaded = store.load("nonexistent").await.unwrap();
        assert!(loaded.is_empty());
    }

    #[tokio::test]
    async fn delete_removes_file() {
        let tmp = TempDir::new().unwrap();
        let store = MountedFlowStore::new(tmp.path().to_path_buf());

        store
            .save(
                "sess-1",
                &[MountedFlowEntry {
                    name: "x".into(),
                    flow_name: "y".into(),
                }],
            )
            .await
            .unwrap();

        store.delete("sess-1").await.unwrap();
        let loaded = store.load("sess-1").await.unwrap();
        assert!(loaded.is_empty());
    }
}
