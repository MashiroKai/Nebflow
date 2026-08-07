//! Flow membership — tracks which agents belong to which flows/teams.
//!
//! Mirrors Scala `FlowMembership.scala`. Maps (flowId, agentName) → sessionId
//! for inter-agent Mail routing.

use std::collections::{HashMap, HashSet};
use tokio::sync::RwLock;

/// Global registry tracking which agents belong to which flows.
pub struct FlowMembership {
    // (flowId, agentName) → sessionId
    session_registry: RwLock<HashMap<(String, String), String>>,
    // sessionId → flowId
    session_flows: RwLock<HashMap<String, String>>,
    // flowId → manager sessionId
    flow_managers: RwLock<HashMap<String, String>>,
    // flowId → parent session ID
    flow_parent_sessions: RwLock<HashMap<String, String>>,
    // Sessions currently processing a turn
    busy_sessions: RwLock<HashSet<String>>,
}

impl FlowMembership {
    pub fn new() -> Self {
        Self {
            session_registry: RwLock::new(HashMap::new()),
            session_flows: RwLock::new(HashMap::new()),
            flow_managers: RwLock::new(HashMap::new()),
            flow_parent_sessions: RwLock::new(HashMap::new()),
            busy_sessions: RwLock::new(HashSet::new()),
        }
    }

    /// Register the parent session for a flow instance.
    pub async fn register_parent_session(&self, flow_id: &str, parent_session_id: &str) {
        self.flow_parent_sessions
            .write()
            .await
            .insert(flow_id.to_string(), parent_session_id.to_string());
    }

    /// Get the parent session ID for a flow instance.
    pub async fn parent_session_of(&self, flow_id: &str) -> Option<String> {
        self.flow_parent_sessions.read().await.get(flow_id).cloned()
    }

    /// Register a flow agent's session at mount time.
    pub async fn register_session(&self, flow_id: &str, agent_name: &str, session_id: &str) {
        self.session_registry.write().await.insert(
            (flow_id.to_string(), agent_name.to_string()),
            session_id.to_string(),
        );
        self.session_flows
            .write()
            .await
            .insert(session_id.to_string(), flow_id.to_string());
    }

    /// Register an alias (makes aliasName point to an existing session).
    pub async fn register_alias(&self, flow_id: &str, alias_name: &str, session_id: &str) {
        self.session_registry.write().await.insert(
            (flow_id.to_string(), alias_name.to_string()),
            session_id.to_string(),
        );
    }

    /// Register a flow's manager session.
    pub async fn register_flow_manager(&self, flow_id: &str, manager_session_id: &str) {
        self.flow_managers
            .write()
            .await
            .insert(flow_id.to_string(), manager_session_id.to_string());
    }

    /// Resolve a team agent by exact (instanceName, agentName) lookup.
    pub async fn resolve_team_agent(&self, team_name: &str, agent_name: &str) -> Option<String> {
        self.session_registry
            .read()
            .await
            .get(&(team_name.to_string(), agent_name.to_string()))
            .cloned()
    }

    /// Resolve a short name to a sessionId.
    /// Resolution order:
    /// 1. Sender's flow scope (same flow agent)
    /// 2. Cross-flow matches (only for Nebula — no flowId)
    /// 3. Flow manager name (only for Nebula)
    /// 4. Nebula escalation (team managers can reach parent session)
    pub async fn resolve_session_id(&self, sender_session_id: &str, name: &str) -> Option<String> {
        let sf = self.session_flows.read().await;
        let sr = self.session_registry.read().await;
        let fm = self.flow_managers.read().await;
        let fps = self.flow_parent_sessions.read().await;

        let flow_id = sf.get(sender_session_id).cloned();
        let is_manager = fm.values().any(|v| v == sender_session_id);

        // Step 1: try sender's own flow
        if let Some(ref fid) = flow_id {
            if let Some(sid) = sr.get(&(fid.clone(), name.to_string())) {
                return Some(sid.clone());
            }
        }

        // Step 2: cross-flow matches — only for Nebula (flowId is None)
        if flow_id.is_none() {
            for ((_, agent_name), sid) in sr.iter() {
                if agent_name == name {
                    return Some(sid.clone());
                }
            }
        }

        // Step 3: flow manager lookup — only for Nebula
        if flow_id.is_none() {
            if let Some(sid) = fm.get(name) {
                return Some(sid.clone());
            }
        }

        // Step 4: Nebula escalation
        if name == "Nebula" && is_manager {
            if let Some(ref fid) = flow_id {
                if let Some(parent) = fps.get(fid) {
                    return Some(parent.clone());
                }
            }
        }

        None
    }

    /// Check if a session is a flow/team manager.
    pub async fn is_manager(&self, session_id: &str) -> bool {
        self.flow_managers
            .read()
            .await
            .values()
            .any(|v| v == session_id)
    }

    /// Mark a session as busy.
    pub async fn mark_busy(&self, session_id: &str) {
        self.busy_sessions
            .write()
            .await
            .insert(session_id.to_string());
    }

    /// Mark a session as idle.
    pub async fn mark_idle(&self, session_id: &str) {
        self.busy_sessions.write().await.remove(session_id);
    }

    /// Check if a session is busy.
    pub async fn is_busy(&self, session_id: &str) -> bool {
        self.busy_sessions.read().await.contains(session_id)
    }

    /// Reverse lookup: sessionId → (flowId, agentName).
    pub async fn agent_of_session(&self, session_id: &str) -> Option<(String, String)> {
        let sf = self.session_flows.read().await;
        let sr = self.session_registry.read().await;
        let flow_id = sf.get(session_id)?;
        for ((fid, name), sid) in sr.iter() {
            if fid == flow_id && sid == session_id {
                return Some((fid.clone(), name.clone()));
            }
        }
        None
    }

    /// Remove a flow's session registrations (unmount).
    pub async fn unregister_flow_sessions(&self, flow_id: &str) {
        let matches = |fid: &str| fid == flow_id || fid.starts_with(&format!("{}/", flow_id));

        self.session_registry
            .write()
            .await
            .retain(|(fid, _), _| !matches(fid));
        self.session_flows
            .write()
            .await
            .retain(|_, fid| !matches(fid));
        self.flow_managers
            .write()
            .await
            .retain(|fid, _| !matches(fid));
        self.flow_parent_sessions
            .write()
            .await
            .retain(|fid, _| !matches(fid));
    }

    /// Get all session IDs for a flow, including nested sub-flows.
    pub async fn session_ids_of(&self, flow_id: &str) -> Vec<String> {
        let matches = |fid: &str| fid == flow_id || fid.starts_with(&format!("{}/", flow_id));
        self.session_registry
            .read()
            .await
            .iter()
            .filter(|((fid, _), _)| matches(fid))
            .map(|(_, sid)| sid.clone())
            .collect()
    }

    /// List all mounted flows with their agent names.
    pub async fn list_mounted_flows(&self) -> HashMap<String, Vec<(String, String)>> {
        let sr = self.session_registry.read().await;
        let mut result: HashMap<String, Vec<(String, String)>> = HashMap::new();
        for ((flow_id, agent_name), sid) in sr.iter() {
            if !flow_id.contains('/') {
                result
                    .entry(flow_id.clone())
                    .or_default()
                    .push((agent_name.clone(), sid.clone()));
            }
        }
        for agents in result.values_mut() {
            agents.sort_by(|a, b| a.0.cmp(&b.0));
        }
        result
    }

    /// Get flow name for a session.
    pub async fn flow_of_session(&self, session_id: &str) -> Option<String> {
        self.session_flows.read().await.get(session_id).cloned()
    }

    /// Clear all state (tests only).
    pub async fn clear(&self) {
        self.session_registry.write().await.clear();
        self.session_flows.write().await.clear();
        self.flow_managers.write().await.clear();
        self.flow_parent_sessions.write().await.clear();
        self.busy_sessions.write().await.clear();
    }
}

impl Default for FlowMembership {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn register_and_resolve_session() {
        let membership = FlowMembership::new();
        membership
            .register_session("team-1", "Alice", "sess-1")
            .await;
        membership.register_session("team-1", "Bob", "sess-2").await;

        // Resolve within own flow
        let result = membership.resolve_session_id("sess-1", "Bob").await;
        assert_eq!(result.as_deref(), Some("sess-2"));
    }

    #[tokio::test]
    async fn resolve_cross_flow_for_nebula() {
        let membership = FlowMembership::new();
        membership
            .register_session("team-1", "Alice", "sess-1")
            .await;

        // Nebula has no flow → cross-flow match
        let result = membership
            .resolve_session_id("nebula-session", "Alice")
            .await;
        assert_eq!(result.as_deref(), Some("sess-1"));
    }

    #[tokio::test]
    async fn team_agent_cannot_cross_flow() {
        let membership = FlowMembership::new();
        membership
            .register_session("team-1", "Alice", "sess-1")
            .await;
        membership.register_session("team-2", "Bob", "sess-2").await;

        // sess-1 is in team-1 → should NOT find team-2's Bob
        let result = membership.resolve_session_id("sess-1", "Bob").await;
        assert_eq!(result, None);
    }

    #[tokio::test]
    async fn busy_tracking() {
        let membership = FlowMembership::new();

        assert!(!membership.is_busy("sess-1").await);
        membership.mark_busy("sess-1").await;
        assert!(membership.is_busy("sess-1").await);
        membership.mark_idle("sess-1").await;
        assert!(!membership.is_busy("sess-1").await);
    }

    #[tokio::test]
    async fn unregister_flow_sessions() {
        let membership = FlowMembership::new();
        membership
            .register_session("team-1", "Alice", "sess-1")
            .await;
        membership.register_session("team-1", "Bob", "sess-2").await;
        membership
            .register_session("team-2", "Charlie", "sess-3")
            .await;

        membership.unregister_flow_sessions("team-1").await;

        // team-1 sessions removed
        assert!(membership
            .resolve_session_id("sess-3", "Alice")
            .await
            .is_none());
        // team-2 still intact
        let result = membership
            .resolve_session_id("nebula-session", "Charlie")
            .await;
        assert_eq!(result.as_deref(), Some("sess-3"));
    }

    #[tokio::test]
    async fn list_mounted_flows() {
        let membership = FlowMembership::new();
        membership
            .register_session("team-1", "Alice", "sess-1")
            .await;
        membership.register_session("team-1", "Bob", "sess-2").await;
        membership
            .register_session("team-1/sub", "Sub", "sess-3")
            .await;

        let flows = membership.list_mounted_flows().await;
        assert_eq!(flows.len(), 1); // sub-flow hidden
        let agents = &flows["team-1"];
        assert_eq!(agents.len(), 2);
        assert_eq!(agents[0].0, "Alice");
    }

    #[tokio::test]
    async fn agent_of_session_reverse_lookup() {
        let membership = FlowMembership::new();
        membership
            .register_session("team-1", "Alice", "sess-1")
            .await;

        let result = membership.agent_of_session("sess-1").await;
        assert_eq!(result, Some(("team-1".into(), "Alice".into())));

        let none = membership.agent_of_session("unknown").await;
        assert_eq!(none, None);
    }

    #[tokio::test]
    async fn parent_session_lookup() {
        let membership = FlowMembership::new();
        membership
            .register_parent_session("flow-1", "nebula-sess")
            .await;

        assert_eq!(
            membership.parent_session_of("flow-1").await.as_deref(),
            Some("nebula-sess")
        );
        assert_eq!(membership.parent_session_of("unknown").await, None);
    }
}
