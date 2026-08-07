//! Agent manager — mirrors Scala GatewayMain's `routeToAgent` + agent registry.
//!
//! Maintains a `session_id → AgentHandle` map so that WebSocket `userInput`
//! messages can be routed to a live agent. Agents are lazily created on first
//! input: the agent definition is resolved from the AgentLibrary (falling back
//! to the built-in Nebula), and the session's persisted LLM message history is
//! loaded as initial context.

use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::Mutex;

use nebflow_agent::library::AgentLibrary;
use nebflow_agent::resources::SharedResources;
use nebflow_agent::{AgentBuilder, AgentHandle};
use nebflow_core::types::AgentDef;

use crate::session::SessionStore;

/// Central registry of live agents keyed by session ID.
pub struct AgentManager {
    resources: Arc<SharedResources>,
    library: Arc<AgentLibrary>,
    session_store: Arc<SessionStore>,
    agents: Mutex<HashMap<String, Arc<Mutex<AgentHandle>>>>,
}

impl AgentManager {
    pub fn new(
        resources: Arc<SharedResources>,
        library: Arc<AgentLibrary>,
        session_store: Arc<SessionStore>,
    ) -> Self {
        Self {
            resources,
            library,
            session_store,
            agents: Mutex::new(HashMap::new()),
        }
    }

    /// Get the live agent for a session, creating it on first use.
    ///
    /// The returned handle is wrapped in `Arc<Mutex<_>>` because receiving
    /// events requires `&mut` access — callers that want to read events must
    /// hold the lock for the duration of the receive loop.
    pub async fn get_or_create(&self, session_id: &str) -> Arc<Mutex<AgentHandle>> {
        let mut agents = self.agents.lock().await;
        if let Some(handle) = agents.get(session_id) {
            return handle.clone();
        }

        let def = self.resolve_agent_def(session_id).await;
        let messages = self.session_store.load_session_messages(session_id).await;

        let handle = AgentBuilder::new(def)
            .with_llm(self.resources.clone())
            .with_tools(self.resources.tool_registry.clone())
            .with_session(session_id)
            .with_messages(messages)
            .spawn();

        let handle = Arc::new(Mutex::new(handle));
        agents.insert(session_id.to_string(), handle.clone());
        handle
    }

    /// Remove the agent for a session (e.g. after the turn completes).
    /// The handle is dropped, which closes the message channel and causes
    /// the agent runner task to exit.
    pub async fn remove(&self, session_id: &str) {
        self.agents.lock().await.remove(session_id);
    }

    /// Number of live agents (for diagnostics/tests).
    pub async fn len(&self) -> usize {
        self.agents.lock().await.len()
    }

    /// Whether any agents are live.
    pub async fn is_empty(&self) -> bool {
        self.agents.lock().await.is_empty()
    }

    /// Resolve the AgentDef for a session: use the session's configured
    /// agent if set and found in the library, otherwise fall back to Nebula.
    async fn resolve_agent_def(&self, session_id: &str) -> AgentDef {
        let agent_name = self
            .agent_name_for_session(session_id)
            .await
            .unwrap_or_else(|| "Nebula".to_string());
        self.library
            .get(&agent_name)
            .or_else(|| self.library.get("Nebula"))
            .expect("AgentLibrary always provides a Nebula fallback")
    }

    /// Look up the agent name configured on the session metadata.
    async fn agent_name_for_session(&self, session_id: &str) -> Option<String> {
        self.session_store
            .list_sessions()
            .await
            .into_iter()
            .find(|m| m.id == session_id)
            .and_then(|m| m.agent_name)
            .filter(|n| !n.is_empty())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::session::SessionStore;
    use nebflow_core::config::{NebflowServiceConfig, ThinkingConfig};
    use std::path::PathBuf;

    fn test_config() -> NebflowServiceConfig {
        let config_json = r#"{
            "llm": {
                "providers": {
                    "anthropic": {
                        "baseUrl": "http://localhost:1",
                        "apiKey": "sk-test",
                        "protocol": "anthropic",
                        "models": [{"id": "claude", "maxTokens": 16384, "contextWindow": 200000}]
                    }
                },
                "model": {"default": "anthropic/claude"}
            }
        }"#;
        serde_json::from_str(config_json).unwrap()
    }

    fn thinking_config() -> ThinkingConfig {
        ThinkingConfig {
            enabled: true,
            budget_tokens: 32_000,
        }
    }

    fn make_manager() -> (AgentManager, tempfile::TempDir, tempfile::TempDir) {
        let sessions = tempfile::tempdir().unwrap();
        let agents_dir = tempfile::tempdir().unwrap();
        let resources = Arc::new(SharedResources::new(
            test_config(),
            PathBuf::from("/tmp"),
            thinking_config(),
        ));
        let library = Arc::new(AgentLibrary::new(agents_dir.path()));
        let store = Arc::new(SessionStore::new(sessions.path().to_path_buf()));
        (
            AgentManager::new(resources, library, store),
            sessions,
            agents_dir,
        )
    }

    #[tokio::test]
    async fn get_or_create_reuses_handle_for_same_session() {
        let (mgr, _s, _a) = make_manager();
        let h1 = mgr.get_or_create("sess-1").await;
        let h2 = mgr.get_or_create("sess-1").await;
        assert!(Arc::ptr_eq(&h1, &h2));
        assert_eq!(mgr.len().await, 1);
    }

    #[tokio::test]
    async fn different_sessions_get_different_handles() {
        let (mgr, _s, _a) = make_manager();
        let h1 = mgr.get_or_create("sess-1").await;
        let h2 = mgr.get_or_create("sess-2").await;
        assert!(!Arc::ptr_eq(&h1, &h2));
        assert_eq!(mgr.len().await, 2);
    }

    #[tokio::test]
    async fn remove_drops_agent() {
        let (mgr, _s, _a) = make_manager();
        let _ = mgr.get_or_create("sess-1").await;
        assert_eq!(mgr.len().await, 1);
        mgr.remove("sess-1").await;
        assert_eq!(mgr.len().await, 0);
    }
}
