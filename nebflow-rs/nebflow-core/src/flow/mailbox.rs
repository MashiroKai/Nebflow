//! Flow mail store — persistent mail history per flow instance.
//!
//! Mirrors Scala `FlowMailStore.scala`. Records Mail messages between
//! flow/team agents so users can review communication history.
//!
//! Storage: `~/.nebflow/sessions/<sessionId>/flow-mailbox/<flowName>.json`

use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use tokio::fs;

/// A single mail record between agents.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MailRecord {
    pub from: String,
    pub to: String,
    pub message: String,
    #[serde(default)]
    pub timestamp: u64,
}

/// Persistent mail history per flow instance.
pub struct FlowMailStore {
    data_root: PathBuf,
}

impl FlowMailStore {
    pub fn new(data_root: PathBuf) -> Self {
        Self { data_root }
    }

    pub fn default_for(home: &std::path::Path) -> Self {
        Self::new(home.join(".nebflow"))
    }

    fn mailbox_dir(&self, session_id: &str) -> PathBuf {
        self.data_root
            .join("sessions")
            .join(session_id)
            .join("flow-mailbox")
    }

    fn safe_flow_name(flow_name: &str) -> String {
        flow_name.replace('/', "_")
    }

    fn mailbox_file(&self, session_id: &str, flow_name: &str) -> PathBuf {
        let safe = Self::safe_flow_name(flow_name);
        self.mailbox_dir(session_id).join(format!("{}.json", safe))
    }

    /// Append a mail record (atomic read-modify-write).
    pub async fn append(
        &self,
        session_id: &str,
        flow_name: &str,
        record: MailRecord,
    ) -> Result<(), String> {
        if session_id.is_empty() {
            return Ok(());
        }

        let file = self.mailbox_file(session_id, flow_name);
        let dir = file.parent().ok_or("Invalid path")?;

        // Read existing records
        let existing: Vec<MailRecord> = if file.exists() {
            match fs::read_to_string(&file).await {
                Ok(content) => serde_json::from_str(&content).unwrap_or_default(),
                Err(_) => Vec::new(),
            }
        } else {
            Vec::new()
        };

        // Append and cap at 200 entries
        let mut updated = existing;
        updated.push(record);
        if updated.len() > 200 {
            updated = updated.split_off(updated.len() - 200);
        }

        // Atomic write: write temp then rename
        fs::create_dir_all(dir).await.map_err(|e| e.to_string())?;
        let tmp = dir.join(format!(
            ".{}.tmp",
            file.file_name().unwrap().to_string_lossy()
        ));
        let json = serde_json::to_string(&updated).map_err(|e| e.to_string())?;
        fs::write(&tmp, &json).await.map_err(|e| e.to_string())?;
        fs::rename(&tmp, &file).await.map_err(|e| e.to_string())?;

        Ok(())
    }

    /// Load all mail records for a flow instance.
    pub async fn load(&self, session_id: &str, flow_name: &str) -> Result<Vec<MailRecord>, String> {
        if session_id.is_empty() {
            return Ok(Vec::new());
        }

        let file = self.mailbox_file(session_id, flow_name);
        if !file.exists() {
            return Ok(Vec::new());
        }

        let content = fs::read_to_string(&file).await.map_err(|e| e.to_string())?;
        serde_json::from_str(&content).map_err(|e| e.to_string())
    }

    /// Clear all mail records for a flow instance.
    pub async fn clear(&self, session_id: &str, flow_name: &str) -> Result<(), String> {
        if session_id.is_empty() {
            return Ok(());
        }

        let file = self.mailbox_file(session_id, flow_name);
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
    async fn append_and_load() {
        let tmp = TempDir::new().unwrap();
        let store = FlowMailStore::new(tmp.path().to_path_buf());

        let record = MailRecord {
            from: "Alice".into(),
            to: "Bob".into(),
            message: "hello".into(),
            timestamp: 12345,
        };

        store
            .append("sess-1", "team-1", record.clone())
            .await
            .unwrap();

        let loaded = store.load("sess-1", "team-1").await.unwrap();
        assert_eq!(loaded.len(), 1);
        assert_eq!(loaded[0].from, "Alice");
        assert_eq!(loaded[0].message, "hello");
    }

    #[tokio::test]
    async fn append_multiple() {
        let tmp = TempDir::new().unwrap();
        let store = FlowMailStore::new(tmp.path().to_path_buf());

        for i in 0..5 {
            store
                .append(
                    "sess-1",
                    "team-1",
                    MailRecord {
                        from: "A".into(),
                        to: "B".into(),
                        message: format!("msg {}", i),
                        timestamp: i as u64,
                    },
                )
                .await
                .unwrap();
        }

        let loaded = store.load("sess-1", "team-1").await.unwrap();
        assert_eq!(loaded.len(), 5);
        assert_eq!(loaded[4].message, "msg 4");
    }

    #[tokio::test]
    async fn clear_records() {
        let tmp = TempDir::new().unwrap();
        let store = FlowMailStore::new(tmp.path().to_path_buf());

        store
            .append(
                "sess-1",
                "team-1",
                MailRecord {
                    from: "A".into(),
                    to: "B".into(),
                    message: "hello".into(),
                    timestamp: 0,
                },
            )
            .await
            .unwrap();

        store.clear("sess-1", "team-1").await.unwrap();

        let loaded = store.load("sess-1", "team-1").await.unwrap();
        assert!(loaded.is_empty());
    }

    #[tokio::test]
    async fn empty_session_noop() {
        let tmp = TempDir::new().unwrap();
        let store = FlowMailStore::new(tmp.path().to_path_buf());

        store
            .append(
                "",
                "team-1",
                MailRecord {
                    from: "A".into(),
                    to: "B".into(),
                    message: "hello".into(),
                    timestamp: 0,
                },
            )
            .await
            .unwrap();

        let loaded = store.load("", "team-1").await.unwrap();
        assert!(loaded.is_empty());
    }

    #[test]
    fn safe_flow_name_replaces_slash() {
        assert_eq!(FlowMailStore::safe_flow_name("a/b/c"), "a_b_c");
        assert_eq!(FlowMailStore::safe_flow_name("normal"), "normal");
    }
}
