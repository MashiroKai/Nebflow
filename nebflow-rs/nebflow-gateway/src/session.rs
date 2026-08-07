//! Session store — mirrors Scala SessionStore.scala.
//!
//! Manages session persistence: session metadata index, LLM messages,
//! and UI messages (frontend rendering history). Uses JSON files on disk.
//!
//! Layout (under `~/.nebflow/sessions/`):
//! - `_index.json` — { activeId, sessions: [SessionMeta], folders: [Folder] }
//! - `<id>.json` — LLM messages (full conversation history)
//! - `<id>.ui.json` — UI messages (frontend rendering, capped to MaxStoredUiMessages)
//! - `<id>.meta.json` — folderId sidecar (for orphan recovery)

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::sync::Mutex;

use nebflow_core::protocol::SessionMeta;

/// Maximum UI messages retained per session.
const MAX_STORED_UI_MESSAGES: usize = 800;

/// Maximum chars of tool content stored in UI history.
const MAX_STORED_TOOL_CONTENT_CHARS: usize = 50_000;

/// A folder for organizing sessions.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Folder {
    pub id: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub parent_id: Option<String>,
    #[serde(default)]
    pub agent_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub project_root: Option<String>,
    pub created_at: u64,
    pub updated_at: u64,
}

/// UI message types (for frontend rendering history).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum UiMessage {
    #[serde(rename = "user")]
    User {
        text: String,
        #[serde(default)]
        attachments: Vec<serde_json::Value>,
        timestamp: u64,
    },
    #[serde(rename = "ai")]
    Ai {
        text: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        model: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        duration_ms: Option<u64>,
        timestamp: u64,
    },
    #[serde(rename = "tool")]
    Tool {
        label: String,
        summary: String,
        content: String,
        is_error: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        input: Option<serde_json::Value>,
        timestamp: u64,
    },
    #[serde(rename = "system")]
    System {
        text: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        class_hint: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        extra: Option<serde_json::Value>,
        timestamp: u64,
    },
}

/// Session index data persisted to `_index.json`.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SessionIndex {
    active_id: String,
    sessions: Vec<SessionMeta>,
    #[serde(default)]
    folders: Vec<Folder>,
}

/// Session store — manages session metadata and messages on disk.
pub struct SessionStore {
    sessions_dir: PathBuf,
    index: Arc<Mutex<SessionIndex>>,
}

impl SessionStore {
    /// Create a new SessionStore. Does NOT load from disk — call `load()` first.
    pub fn new(sessions_dir: PathBuf) -> Self {
        Self {
            sessions_dir,
            index: Arc::new(Mutex::new(SessionIndex::default())),
        }
    }

    /// Load session index from disk. Creates the directory if it doesn't exist.
    pub async fn load(&self) -> Result<(), std::io::Error> {
        tokio::fs::create_dir_all(&self.sessions_dir).await?;

        let index_path = self.sessions_dir.join("_index.json");
        if index_path.exists() {
            let content = tokio::fs::read_to_string(&index_path).await?;
            let parsed: SessionIndex = serde_json::from_str(&content).unwrap_or_default();
            *self.index.lock().await = parsed;
        } else {
            // Create default session
            self.create_default_session().await?;
        }
        Ok(())
    }

    /// Create the default session (for first run).
    async fn create_default_session(&self) -> Result<(), std::io::Error> {
        let id = uuid::Uuid::new_v4().to_string();
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis() as u64;
        let meta = SessionMeta {
            id: id.clone(),
            name: "Nebula".into(),
            created_at: now,
            updated_at: now,
            has_unread: false,
            agent_name: Some("Nebula".into()),
            model_ref: None,
            bridges: HashMap::new(),
            folder_id: None,
            safety_mode: "confirm-edits".into(),
            git_branch: None,
            flow_name: None,
        };

        // Write empty messages file
        self.save_session_messages(&id, &[]).await?;

        let mut idx = self.index.lock().await;
        idx.active_id = id;
        idx.sessions = vec![meta];
        drop(idx);
        self.save_index().await
    }

    /// Save the index to disk (atomic write via temp file + rename).
    async fn save_index(&self) -> Result<(), std::io::Error> {
        let idx = self.index.lock().await.clone();
        let json = serde_json::to_string_pretty(&idx)?;
        let index_path = self.sessions_dir.join("_index.json");
        let tmp_path = self
            .sessions_dir
            .join(format!("_index.json.tmp.{}", uuid::Uuid::new_v4()));
        tokio::fs::write(&tmp_path, json).await?;
        tokio::fs::rename(&tmp_path, &index_path).await
    }

    // ── Session file paths ──

    fn session_file(&self, id: &str) -> PathBuf {
        self.sessions_dir.join(format!("{id}.json"))
    }

    fn ui_file(&self, id: &str) -> PathBuf {
        self.sessions_dir.join(format!("{id}.ui.json"))
    }

    fn meta_sidecar_file(&self, id: &str) -> PathBuf {
        self.sessions_dir.join(format!("{id}.meta.json"))
    }

    // ── LLM Messages ──

    /// Load LLM messages for a session from disk.
    pub async fn load_session_messages(&self, id: &str) -> Vec<nebflow_core::types::Message> {
        let path = self.session_file(id);
        match tokio::fs::read_to_string(&path).await {
            Ok(content) => serde_json::from_str(&content).unwrap_or_default(),
            Err(_) => Vec::new(),
        }
    }

    /// Save LLM messages for a session (atomic write).
    pub async fn save_session_messages(
        &self,
        id: &str,
        messages: &[nebflow_core::types::Message],
    ) -> Result<(), std::io::Error> {
        let path = self.session_file(id);
        let tmp = self
            .sessions_dir
            .join(format!("{id}.json.tmp.{}", uuid::Uuid::new_v4()));
        let json = serde_json::to_string_pretty(messages)?;
        tokio::fs::write(&tmp, json).await?;
        tokio::fs::rename(&tmp, &path).await
    }

    // ── UI Messages ──

    /// Load UI messages for a session from disk.
    pub async fn load_ui_messages(&self, id: &str) -> Vec<UiMessage> {
        let path = self.ui_file(id);
        match tokio::fs::read_to_string(&path).await {
            Ok(content) => serde_json::from_str(&content).unwrap_or_default(),
            Err(_) => Vec::new(),
        }
    }

    /// Save UI messages for a session (atomic write).
    pub async fn save_ui_messages(
        &self,
        id: &str,
        messages: &[UiMessage],
    ) -> Result<(), std::io::Error> {
        let path = self.ui_file(id);
        let tmp = self
            .sessions_dir
            .join(format!("{id}.ui.json.tmp.{}", uuid::Uuid::new_v4()));
        let json = serde_json::to_string(messages)?;
        tokio::fs::write(&tmp, json).await?;
        tokio::fs::rename(&tmp, &path).await
    }

    /// Append UI messages to a session (capped at MAX_STORED_UI_MESSAGES).
    pub async fn append_ui_messages(
        &self,
        id: &str,
        new_msgs: Vec<UiMessage>,
    ) -> Result<(), std::io::Error> {
        if new_msgs.is_empty() {
            return Ok(());
        }
        let mut existing = self.load_ui_messages(id).await;
        let sanitized = sanitize_for_storage(new_msgs);
        existing.extend(sanitized);
        // Trim to retention cap
        if existing.len() > MAX_STORED_UI_MESSAGES {
            let start = existing.len() - MAX_STORED_UI_MESSAGES;
            existing = existing[start..].to_vec();
        }
        self.save_ui_messages(id, &existing).await
    }

    /// Get a page of UI messages (forward pagination from offset).
    /// Returns (messages, total_count).
    pub async fn get_ui_messages(
        &self,
        id: &str,
        offset: usize,
        limit: usize,
    ) -> (Vec<UiMessage>, usize) {
        let all = self.load_ui_messages(id).await;
        let total = all.len();
        let start = offset.min(total);
        let end = if limit == 0 {
            total
        } else {
            (start + limit).min(total)
        };
        (all[start..end].to_vec(), total)
    }

    /// Get the latest messages (or messages before an index for pagination).
    /// Returns (messages, total, offset, has_more).
    pub async fn get_history_page(
        &self,
        id: &str,
        limit: usize,
        before_index: Option<usize>,
    ) -> (Vec<UiMessage>, usize, usize, bool) {
        let all = self.load_ui_messages(id).await;
        let total = all.len();
        match before_index {
            None => {
                let offset = total.saturating_sub(limit);
                (all[offset..].to_vec(), total, offset, total > limit)
            }
            Some(before) => {
                let offset = before.saturating_sub(limit);
                let actual_limit = limit.min(before);
                (
                    all[offset..offset + actual_limit].to_vec(),
                    total,
                    offset,
                    before > limit,
                )
            }
        }
    }

    /// Delete UI messages file for a session.
    pub async fn delete_ui_messages(&self, id: &str) -> Result<(), std::io::Error> {
        let path = self.ui_file(id);
        if path.exists() {
            tokio::fs::remove_file(&path).await?;
        }
        Ok(())
    }

    // ── Session CRUD ──

    /// Create a new session.
    pub async fn create_session(
        &self,
        name: &str,
        agent_name: Option<String>,
        folder_id: Option<String>,
    ) -> Result<SessionMeta, std::io::Error> {
        let id = uuid::Uuid::new_v4().to_string();
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis() as u64;
        let meta = SessionMeta {
            id: id.clone(),
            name: name.into(),
            created_at: now,
            updated_at: now,
            has_unread: false,
            agent_name,
            model_ref: None,
            bridges: HashMap::new(),
            folder_id,
            safety_mode: "confirm-edits".into(),
            git_branch: None,
            flow_name: None,
        };

        self.save_session_messages(&id, &[]).await?;
        if let Some(ref fid) = meta.folder_id {
            self.write_meta_sidecar(&id, Some(fid)).await?;
        }

        let mut idx = self.index.lock().await;
        idx.sessions.push(meta.clone());
        drop(idx);
        self.save_index().await?;
        Ok(meta)
    }

    /// Delete a session by ID. Returns true if the session was found and deleted.
    pub async fn delete_session(&self, id: &str) -> Result<bool, std::io::Error> {
        let mut idx = self.index.lock().await;
        let was_active = idx.active_id == id;

        // Remove from sessions list
        let original_len = idx.sessions.len();
        idx.sessions.retain(|s| s.id != id);
        if idx.sessions.len() == original_len {
            return Ok(false);
        }

        // Pick new active if needed
        if was_active {
            idx.active_id = idx
                .sessions
                .first()
                .map(|s| s.id.clone())
                .unwrap_or_default();
        }
        drop(idx);

        // Remove files
        let session_path = self.session_file(id);
        if session_path.exists() {
            let _ = tokio::fs::remove_file(&session_path).await;
        }
        let ui_path = self.ui_file(id);
        if ui_path.exists() {
            let _ = tokio::fs::remove_file(&ui_path).await;
        }
        let meta_path = self.meta_sidecar_file(id);
        if meta_path.exists() {
            let _ = tokio::fs::remove_file(&meta_path).await;
        }

        self.save_index().await?;
        Ok(true)
    }

    /// Switch the active session. Returns the new active ID.
    pub async fn switch_session(&self, id: &str) -> Result<String, String> {
        let mut idx = self.index.lock().await;
        if !idx.sessions.iter().any(|s| s.id == id) {
            return Err(format!("Session {id} not found"));
        }
        idx.active_id = id.to_string();
        let active = idx.active_id.clone();
        drop(idx);
        self.save_index().await.map_err(|e| e.to_string())?;
        Ok(active)
    }

    /// Rename a session.
    pub async fn rename_session(&self, id: &str, new_name: &str) -> Result<(), std::io::Error> {
        let mut idx = self.index.lock().await;
        for s in &mut idx.sessions {
            if s.id == id {
                s.name = new_name.into();
                break;
            }
        }
        drop(idx);
        self.save_index().await
    }

    /// Mark a session as having unread content.
    pub async fn mark_unread(&self, id: &str) -> Result<(), std::io::Error> {
        let mut idx = self.index.lock().await;
        for s in &mut idx.sessions {
            if s.id == id {
                s.has_unread = true;
                break;
            }
        }
        drop(idx);
        self.save_index().await
    }

    /// Update the model reference for a session.
    pub async fn update_session_model(
        &self,
        id: &str,
        model_ref: Option<String>,
    ) -> Result<(), std::io::Error> {
        let mut idx = self.index.lock().await;
        for s in &mut idx.sessions {
            if s.id == id {
                s.model_ref = model_ref.clone();
                break;
            }
        }
        drop(idx);
        self.save_index().await
    }

    /// Set the safety mode for a session.
    pub async fn set_safety_mode(&self, id: &str, mode: &str) -> Result<(), std::io::Error> {
        let mut idx = self.index.lock().await;
        for s in &mut idx.sessions {
            if s.id == id {
                s.safety_mode = mode.into();
                break;
            }
        }
        drop(idx);
        self.save_index().await
    }

    /// Update the git branch for a session.
    pub async fn update_git_branch(
        &self,
        id: &str,
        branch: Option<String>,
    ) -> Result<(), std::io::Error> {
        let mut idx = self.index.lock().await;
        for s in &mut idx.sessions {
            if s.id == id {
                s.git_branch = branch.clone();
                break;
            }
        }
        drop(idx);
        self.save_index().await
    }

    // ── Queries ──

    /// List all sessions sorted by updatedAt descending.
    pub async fn list_sessions(&self) -> Vec<SessionMeta> {
        let idx = self.index.lock().await;
        let mut sessions = idx.sessions.clone();
        sessions.sort_by_key(|s| std::cmp::Reverse(s.updated_at));
        sessions
    }

    /// Get the active session ID.
    pub async fn get_active_id(&self) -> String {
        self.index.lock().await.active_id.clone()
    }

    /// Get the active session's metadata.
    pub async fn get_active_meta(&self) -> Option<SessionMeta> {
        let idx = self.index.lock().await;
        idx.sessions.iter().find(|s| s.id == idx.active_id).cloned()
    }

    /// Get a session's metadata by ID.
    pub async fn get_session_meta(&self, id: &str) -> Option<SessionMeta> {
        let idx = self.index.lock().await;
        idx.sessions.iter().find(|s| s.id == id).cloned()
    }

    /// List all folders.
    pub async fn list_all_folders(&self) -> Vec<Folder> {
        self.index.lock().await.folders.clone()
    }

    /// List folders for a specific agent.
    pub async fn list_folders(&self, agent_name: &str) -> Vec<Folder> {
        let idx = self.index.lock().await;
        idx.folders
            .iter()
            .filter(|f| {
                f.agent_name == agent_name || (f.agent_name.is_empty() && agent_name == "Nebula")
            })
            .cloned()
            .collect()
    }

    /// Create a new folder.
    pub async fn create_folder(
        &self,
        name: &str,
        parent_id: Option<String>,
        agent_name: String,
    ) -> Result<Folder, std::io::Error> {
        let id = uuid::Uuid::new_v4().to_string();
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis() as u64;
        let folder = Folder {
            id,
            name: name.into(),
            parent_id,
            agent_name,
            project_root: None,
            created_at: now,
            updated_at: now,
        };

        let mut idx = self.index.lock().await;
        idx.folders.push(folder.clone());
        drop(idx);
        self.save_index().await?;
        Ok(folder)
    }

    /// Delete a folder (and move its sessions to root).
    pub async fn delete_folder(&self, id: &str) -> Result<(), std::io::Error> {
        let mut idx = self.index.lock().await;

        // Collect all descendant folder IDs
        let ids_to_remove = collect_descendant_ids(id, &idx.folders);

        idx.folders.retain(|f| !ids_to_remove.contains(&f.id));

        // Move sessions in deleted folders to root
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis() as u64;
        for s in &mut idx.sessions {
            if let Some(ref fid) = s.folder_id {
                if ids_to_remove.contains(fid) {
                    s.folder_id = None;
                    s.updated_at = now;
                }
            }
        }
        drop(idx);
        self.save_index().await
    }

    /// Rename a folder.
    pub async fn rename_folder(&self, id: &str, new_name: &str) -> Result<(), std::io::Error> {
        let mut idx = self.index.lock().await;
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis() as u64;
        for f in &mut idx.folders {
            if f.id == id {
                f.name = new_name.into();
                f.updated_at = now;
                break;
            }
        }
        drop(idx);
        self.save_index().await
    }

    /// Move a session to a folder.
    pub async fn move_session_to_folder(
        &self,
        session_id: &str,
        folder_id: Option<String>,
    ) -> Result<(), std::io::Error> {
        let mut idx = self.index.lock().await;
        for s in &mut idx.sessions {
            if s.id == session_id {
                s.folder_id = folder_id.clone();
                break;
            }
        }
        drop(idx);
        self.write_meta_sidecar(session_id, folder_id.as_ref())
            .await?;
        self.save_index().await
    }

    /// Ensure a singleton session exists for the given agent name.
    /// Returns (meta, was_created).
    pub async fn ensure_agent_session(
        &self,
        agent_name: &str,
    ) -> Result<(SessionMeta, bool), std::io::Error> {
        let mut idx = self.index.lock().await;

        // Check if agent already has a session
        let matching: Vec<&SessionMeta> = idx
            .sessions
            .iter()
            .filter(|s| s.agent_name.as_deref() == Some(agent_name))
            .collect();

        if matching.len() == 1 {
            return Ok((matching[0].clone(), false));
        }

        if matching.is_empty() {
            // Try to adopt a legacy session (no agent_name)
            let legacy = idx.sessions.iter_mut().find(|s| s.agent_name.is_none());
            if let Some(legacy) = legacy {
                legacy.agent_name = Some(agent_name.into());
                legacy.name = agent_name.into();
                let meta = legacy.clone();
                drop(idx);
                self.save_index().await?;
                return Ok((meta, true));
            }
            // Create new
            drop(idx);
            let meta = self
                .create_session(agent_name, Some(agent_name.into()), None)
                .await?;
            return Ok((meta, true));
        }

        // Multiple matches — dedup, keep the newest
        let keeper_id = matching
            .iter()
            .max_by_key(|s| s.updated_at)
            .map(|s| s.id.clone())
            .unwrap();
        let keeper: SessionMeta = matching
            .iter()
            .find(|s| s.id == keeper_id)
            .cloned()
            .cloned()
            .unwrap();
        idx.sessions
            .retain(|s| !(s.agent_name.as_deref() == Some(agent_name) && s.id != keeper_id));
        drop(idx);
        self.save_index().await?;
        Ok((keeper, false))
    }

    /// Ensure an agent session exists AND is the active session.
    pub async fn ensure_active_agent_session(
        &self,
        agent_name: &str,
    ) -> Result<SessionMeta, std::io::Error> {
        let (meta, _) = self.ensure_agent_session(agent_name).await?;
        let active_id = self.get_active_id().await;
        if active_id != meta.id {
            self.switch_session(&meta.id)
                .await
                .map_err(std::io::Error::other)?;
        }
        Ok(meta)
    }

    // ── Sidecar ──

    async fn write_meta_sidecar(
        &self,
        id: &str,
        folder_id: Option<&String>,
    ) -> Result<(), std::io::Error> {
        let path = self.meta_sidecar_file(id);
        match folder_id {
            Some(fid) => {
                let json = serde_json::json!({ "folderId": fid });
                tokio::fs::write(&path, serde_json::to_string(&json)?).await?;
            }
            None => {
                if path.exists() {
                    tokio::fs::remove_file(&path).await?;
                }
            }
        }
        Ok(())
    }

    /// Get the sessions directory path.
    pub fn sessions_dir(&self) -> &Path {
        &self.sessions_dir
    }
}

/// Collect all descendant folder IDs (including self).
fn collect_descendant_ids(folder_id: &str, all: &[Folder]) -> std::collections::HashSet<String> {
    let mut result = std::collections::HashSet::new();
    result.insert(folder_id.to_string());
    loop {
        let mut added = false;
        for f in all {
            if let Some(ref pid) = f.parent_id {
                if result.contains(pid) && !result.contains(&f.id) {
                    result.insert(f.id.clone());
                    added = true;
                }
            }
        }
        if !added {
            break;
        }
    }
    result
}

/// Sanitize UI messages for storage: cap oversized tool content.
fn sanitize_for_storage(msgs: Vec<UiMessage>) -> Vec<UiMessage> {
    msgs.into_iter()
        .map(|m| match m {
            UiMessage::Tool { ref content, .. } => {
                if is_card_content(content) || content.len() <= MAX_STORED_TOOL_CONTENT_CHARS {
                    m
                } else {
                    // Truncate
                    let truncated = format!(
                        "{}\n\n[...truncated, original output {} chars]",
                        &content[..MAX_STORED_TOOL_CONTENT_CHARS.min(content.len())],
                        content.len()
                    );
                    match m {
                        UiMessage::Tool {
                            label,
                            summary,
                            content: _,
                            is_error,
                            input,
                            timestamp,
                        } => UiMessage::Tool {
                            label,
                            summary,
                            content: truncated,
                            is_error,
                            input,
                            timestamp,
                        },
                        _ => unreachable!(),
                    }
                }
            }
            other => other,
        })
        .collect()
}

/// Check if content starts with a card marker.
fn is_card_content(content: &str) -> bool {
    content.starts_with("___") && (content.contains("_HTML___") || content.contains("_JSON___"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn make_store() -> (SessionStore, tempfile::TempDir) {
        let dir = tempdir().unwrap();
        let store = SessionStore::new(dir.path().to_path_buf());
        (store, dir)
    }

    #[tokio::test]
    async fn load_creates_default_session() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let sessions = store.list_sessions().await;
        assert_eq!(sessions.len(), 1);
        assert_eq!(sessions[0].name, "Nebula");
        assert!(sessions[0].agent_name.as_deref() == Some("Nebula"));
    }

    #[tokio::test]
    async fn create_and_list_sessions() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let meta = store.create_session("Test", None, None).await.unwrap();
        let sessions = store.list_sessions().await;
        assert_eq!(sessions.len(), 2);
        assert!(sessions.iter().any(|s| s.id == meta.id));
    }

    #[tokio::test]
    async fn delete_session() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let meta = store.create_session("ToDelete", None, None).await.unwrap();
        let deleted = store.delete_session(&meta.id).await.unwrap();
        assert!(deleted);
        let sessions = store.list_sessions().await;
        assert_eq!(sessions.len(), 1); // Only default remains
    }

    #[tokio::test]
    async fn delete_nonexistent_returns_false() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let deleted = store.delete_session("nonexistent-id").await.unwrap();
        assert!(!deleted);
    }

    #[tokio::test]
    async fn switch_session() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let meta = store
            .create_session("SwitchTarget", None, None)
            .await
            .unwrap();
        let new_active = store.switch_session(&meta.id).await.unwrap();
        assert_eq!(new_active, meta.id);
        assert_eq!(store.get_active_id().await, meta.id);
    }

    #[tokio::test]
    async fn switch_nonexistent_fails() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let result = store.switch_session("nonexistent").await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn rename_session() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let meta = store.create_session("OldName", None, None).await.unwrap();
        store.rename_session(&meta.id, "NewName").await.unwrap();
        let found = store.get_session_meta(&meta.id).await.unwrap();
        assert_eq!(found.name, "NewName");
    }

    #[tokio::test]
    async fn mark_unread() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let meta = store.create_session("Test", None, None).await.unwrap();
        store.mark_unread(&meta.id).await.unwrap();
        let found = store.get_session_meta(&meta.id).await.unwrap();
        assert!(found.has_unread);
    }

    #[tokio::test]
    async fn update_session_model() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let meta = store.create_session("Test", None, None).await.unwrap();
        store
            .update_session_model(&meta.id, Some("openai/gpt-4o".into()))
            .await
            .unwrap();
        let found = store.get_session_meta(&meta.id).await.unwrap();
        assert_eq!(found.model_ref.as_deref(), Some("openai/gpt-4o"));
    }

    #[tokio::test]
    async fn set_safety_mode() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let meta = store.create_session("Test", None, None).await.unwrap();
        store.set_safety_mode(&meta.id, "auto-all").await.unwrap();
        let found = store.get_session_meta(&meta.id).await.unwrap();
        assert_eq!(found.safety_mode, "auto-all");
    }

    #[tokio::test]
    async fn ensure_agent_session_creates_new() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let (meta, created) = store.ensure_agent_session("Backend").await.unwrap();
        assert!(created);
        assert_eq!(meta.agent_name.as_deref(), Some("Backend"));
    }

    #[tokio::test]
    async fn ensure_agent_session_idempotent() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let (_, _) = store.ensure_agent_session("Backend").await.unwrap();
        let (meta2, created2) = store.ensure_agent_session("Backend").await.unwrap();
        assert!(!created2);
        // Should be the same session
        assert_eq!(meta2.agent_name.as_deref(), Some("Backend"));
    }

    #[tokio::test]
    async fn ensure_active_agent_session_switches() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let meta = store.ensure_active_agent_session("Backend").await.unwrap();
        assert_eq!(store.get_active_id().await, meta.id);
    }

    #[tokio::test]
    async fn create_and_list_folders() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let _folder = store
            .create_folder("Projects", None, "Nebula".into())
            .await
            .unwrap();
        let folders = store.list_all_folders().await;
        assert_eq!(folders.len(), 1);
        assert_eq!(folders[0].name, "Projects");
    }

    #[tokio::test]
    async fn delete_folder_moves_sessions_to_root() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let folder = store
            .create_folder("Test", None, "Nebula".into())
            .await
            .unwrap();
        let meta = store
            .create_session("InFolder", None, Some(folder.id.clone()))
            .await
            .unwrap();
        store.delete_folder(&folder.id).await.unwrap();
        let found = store.get_session_meta(&meta.id).await.unwrap();
        assert!(found.folder_id.is_none());
        // Folder is gone
        assert_eq!(store.list_all_folders().await.len(), 0);
    }

    #[tokio::test]
    async fn rename_folder() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let folder = store
            .create_folder("Old", None, "Nebula".into())
            .await
            .unwrap();
        store.rename_folder(&folder.id, "New").await.unwrap();
        let folders = store.list_all_folders().await;
        assert_eq!(folders[0].name, "New");
    }

    #[tokio::test]
    async fn move_session_to_folder() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let folder = store
            .create_folder("Folder", None, "Nebula".into())
            .await
            .unwrap();
        let meta = store.create_session("Test", None, None).await.unwrap();
        store
            .move_session_to_folder(&meta.id, Some(folder.id.clone()))
            .await
            .unwrap();
        let found = store.get_session_meta(&meta.id).await.unwrap();
        assert_eq!(found.folder_id.as_deref(), Some(folder.id.as_str()));
    }

    #[tokio::test]
    async fn save_and_load_ui_messages() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let meta = store.create_session("Test", None, None).await.unwrap();
        let msgs = vec![
            UiMessage::User {
                text: "hello".into(),
                attachments: vec![],
                timestamp: 1000,
            },
            UiMessage::Ai {
                text: "hi there".into(),
                model: Some("test-model".into()),
                duration_ms: Some(500),
                timestamp: 2000,
            },
        ];
        store.save_ui_messages(&meta.id, &msgs).await.unwrap();
        let loaded = store.load_ui_messages(&meta.id).await;
        assert_eq!(loaded.len(), 2);
    }

    #[tokio::test]
    async fn append_ui_messages() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let meta = store.create_session("Test", None, None).await.unwrap();
        store
            .append_ui_messages(
                &meta.id,
                vec![UiMessage::User {
                    text: "first".into(),
                    attachments: vec![],
                    timestamp: 1000,
                }],
            )
            .await
            .unwrap();
        store
            .append_ui_messages(
                &meta.id,
                vec![UiMessage::User {
                    text: "second".into(),
                    attachments: vec![],
                    timestamp: 2000,
                }],
            )
            .await
            .unwrap();
        let loaded = store.load_ui_messages(&meta.id).await;
        assert_eq!(loaded.len(), 2);
    }

    #[tokio::test]
    async fn get_history_page_latest() {
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let meta = store.create_session("Test", None, None).await.unwrap();
        let msgs: Vec<UiMessage> = (0..10)
            .map(|i| UiMessage::User {
                text: format!("msg-{i}"),
                attachments: vec![],
                timestamp: 1000 + i,
            })
            .collect();
        store.save_ui_messages(&meta.id, &msgs).await.unwrap();
        let (page, total, offset, has_more) = store.get_history_page(&meta.id, 5, None).await;
        assert_eq!(page.len(), 5);
        assert_eq!(total, 10);
        assert_eq!(offset, 5);
        assert!(has_more);
        // Should be the latest 5
        if let UiMessage::User { text, .. } = &page[0] {
            assert_eq!(text, "msg-5");
        } else {
            panic!("expected User message");
        }
    }

    #[tokio::test]
    async fn save_and_load_llm_messages() {
        use nebflow_core::types::{Message, MessageContent, MessageRole};
        let (store, _dir) = make_store();
        store.load().await.unwrap();
        let meta = store.create_session("Test", None, None).await.unwrap();
        let msgs = vec![Message {
            role: MessageRole::User,
            content: MessageContent::Text("hello".into()),
            timestamp: 1234,
        }];
        store.save_session_messages(&meta.id, &msgs).await.unwrap();
        let loaded = store.load_session_messages(&meta.id).await;
        assert_eq!(loaded.len(), 1);
    }

    #[test]
    fn collect_descendants_includes_self() {
        let folders = vec![Folder {
            id: "a".into(),
            name: "A".into(),
            parent_id: None,
            agent_name: String::new(),
            project_root: None,
            created_at: 0,
            updated_at: 0,
        }];
        let ids = collect_descendant_ids("a", &folders);
        assert!(ids.contains("a"));
    }

    #[test]
    fn collect_descendants_recursive() {
        let folders = vec![
            Folder {
                id: "a".into(),
                name: "A".into(),
                parent_id: None,
                agent_name: String::new(),
                project_root: None,
                created_at: 0,
                updated_at: 0,
            },
            Folder {
                id: "b".into(),
                name: "B".into(),
                parent_id: Some("a".into()),
                agent_name: String::new(),
                project_root: None,
                created_at: 0,
                updated_at: 0,
            },
            Folder {
                id: "c".into(),
                name: "C".into(),
                parent_id: Some("b".into()),
                agent_name: String::new(),
                project_root: None,
                created_at: 0,
                updated_at: 0,
            },
        ];
        let ids = collect_descendant_ids("a", &folders);
        assert!(ids.contains("a"));
        assert!(ids.contains("b"));
        assert!(ids.contains("c"));
    }

    #[test]
    fn is_card_content_detects_html() {
        assert!(is_card_content("___CARD_HTML___{\"x\":1}"));
    }

    #[test]
    fn is_card_content_detects_json() {
        assert!(is_card_content("___CARD_JSON___{\"x\":1}"));
    }

    #[test]
    fn is_card_content_rejects_plain_text() {
        assert!(!is_card_content("normal output"));
    }

    #[test]
    fn sanitize_truncates_large_tool_content() {
        let large_content = "x".repeat(MAX_STORED_TOOL_CONTENT_CHARS + 1000);
        let msg = UiMessage::Tool {
            label: "test".into(),
            summary: "test summary".into(),
            content: large_content,
            is_error: false,
            input: None,
            timestamp: 1000,
        };
        let sanitized = sanitize_for_storage(vec![msg]);
        assert_eq!(sanitized.len(), 1);
        if let UiMessage::Tool { content, .. } = &sanitized[0] {
            assert!(content.contains("truncated"));
            assert!(content.len() < MAX_STORED_TOOL_CONTENT_CHARS + 1000);
        } else {
            panic!("expected Tool message");
        }
    }

    #[test]
    fn sanitize_preserves_card_content() {
        let card = "___CARD_HTML___<div>big content</div>";
        let msg = UiMessage::Tool {
            label: "Card".into(),
            summary: "".into(),
            content: card.to_string(),
            is_error: false,
            input: None,
            timestamp: 1000,
        };
        let sanitized = sanitize_for_storage(vec![msg]);
        if let UiMessage::Tool { content, .. } = &sanitized[0] {
            assert_eq!(content, card);
        } else {
            panic!("expected Tool message");
        }
    }
}
