//! Team loader — mounts a team by spawning its lead + member agents
//! and registering their sessions in FlowMembership.
//!
//! Mirrors Scala `TeamMounter.scala`. When a team is mounted:
//! 1. Load `team.json` via EntityLoader
//! 2. Spawn the lead agent and all member agents
//! 3. Register each agent's session in FlowMembership under the team's flow ID
//! 4. Register the lead as the flow manager
//! 5. Register each agent's message sender in AgentMailRegistry
//!
//! After mounting, agents can use the Mail tool to communicate with each
//! other by name (e.g. Mail("backend", "...")).

use std::collections::HashMap;
use std::sync::Arc;

use nebflow_agent::library::AgentLibrary;
use nebflow_agent::resources::SharedResources;
use nebflow_agent::{AgentBuilder, AgentHandle, AgentMailRegistry};
use nebflow_core::flow::membership::FlowMembership;
use nebflow_core::types::{AgentCategory, AgentDef};
use tokio::sync::Mutex;

/// Result of mounting a team.
#[derive(Debug)]
pub struct MountedTeam {
    /// The flow ID used for this team instance (usually the team name).
    pub flow_id: String,
    /// The lead agent's session ID.
    pub lead_session: String,
    /// Map of agent name → session ID for all team members (including lead).
    pub agent_sessions: HashMap<String, String>,
}

/// Team loader — mounts and unmounts teams.
pub struct TeamLoader {
    resources: Arc<SharedResources>,
    library: Arc<AgentLibrary>,
    membership: Arc<FlowMembership>,
    mail_registry: Arc<AgentMailRegistry>,
    /// Live agent handles, keyed by session ID. Kept alive while the team is mounted.
    handles: Mutex<HashMap<String, AgentHandle>>,
}

impl TeamLoader {
    pub fn new(
        resources: Arc<SharedResources>,
        library: Arc<AgentLibrary>,
        membership: Arc<FlowMembership>,
        mail_registry: Arc<AgentMailRegistry>,
    ) -> Self {
        Self {
            resources,
            library,
            membership,
            mail_registry,
            handles: Mutex::new(HashMap::new()),
        }
    }

    /// Mount a team: load team.json, spawn agents, register sessions.
    ///
    /// The `parent_session_id` is the session that triggered the mount
    /// (e.g. Nebula's session). It's registered as the parent session
    /// so that team managers can escalate back to it.
    pub async fn mount(
        &self,
        team_name: &str,
        parent_session_id: &str,
    ) -> Result<MountedTeam, String> {
        // 1. Load team definition
        let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
        let data_root = std::path::PathBuf::from(home).join(".nebflow");
        let entity_loader = nebflow_core::flow::loader::EntityLoader::new(data_root);

        let team_def = entity_loader
            .load_team(team_name)
            .await
            .ok_or_else(|| format!("Team '{team_name}' not found"))?;

        // 2. Validate: lead must exist
        let lead_def = self
            .resolve_agent_def(team_name, &team_def.lead)
            .await
            .ok_or_else(|| format!("Lead agent '{}' not found", team_def.lead))?;

        let flow_id = team_name.to_string();

        // 3. Register parent session
        self.membership
            .register_parent_session(&flow_id, parent_session_id)
            .await;

        let mut agent_sessions = HashMap::new();

        // 4. Spawn lead agent
        let lead_session = format!("{flow_id}-lead-{}", now_millis());
        let lead_handle = self
            .spawn_team_agent(&lead_def, &lead_session, &flow_id)
            .await?;

        // Register lead in membership
        self.membership
            .register_session(&flow_id, &team_def.lead, &lead_session)
            .await;
        self.membership
            .register_flow_manager(&flow_id, &lead_session)
            .await;
        agent_sessions.insert(team_def.lead.clone(), lead_session.clone());

        // Store handle
        self.handles
            .lock()
            .await
            .insert(lead_session.clone(), lead_handle);

        // 5. Spawn member agents
        for member_name in &team_def.members {
            let member_def = match self.resolve_agent_def(team_name, member_name).await {
                Some(def) => def,
                None => {
                    tracing::warn!(target: "team.loader", "Member agent '{}' not found, skipping", member_name);
                    continue;
                }
            };

            let member_session = format!("{flow_id}-{member_name}-{}", now_millis());
            let member_handle = self
                .spawn_team_agent(&member_def, &member_session, &flow_id)
                .await?;

            // Register in membership
            self.membership
                .register_session(&flow_id, member_name, &member_session)
                .await;
            agent_sessions.insert(member_name.clone(), member_session.clone());

            // Store handle
            self.handles
                .lock()
                .await
                .insert(member_session, member_handle);
        }

        Ok(MountedTeam {
            flow_id,
            lead_session,
            agent_sessions,
        })
    }

    /// Unmount a team: stop all agents and unregister sessions.
    pub async fn unmount(&self, flow_id: &str) {
        // Get all session IDs for this flow
        let session_ids = self.membership.session_ids_of(flow_id).await;

        // Unregister from membership
        self.membership.unregister_flow_sessions(flow_id).await;

        // Stop and remove handles
        let mut handles = self.handles.lock().await;
        for sid in &session_ids {
            if let Some(handle) = handles.remove(sid) {
                handle.stop().await;
            }
            self.mail_registry.unregister(sid).await;
        }
    }

    /// Spawn a team agent with membership and mail registry injected.
    async fn spawn_team_agent(
        &self,
        def: &AgentDef,
        session_id: &str,
        _flow_id: &str,
    ) -> Result<AgentHandle, String> {
        let handle = AgentBuilder::new(def.clone())
            .with_llm(self.resources.clone())
            .with_tools(self.resources.tool_registry.clone())
            .with_session(session_id)
            .with_membership(self.membership.clone(), self.mail_registry.clone())
            .spawn();

        Ok(handle)
    }

    /// Resolve an agent definition by name.
    /// Resolution order:
    /// 1. AgentLibrary (global agents directory)
    /// 2. EntityLoader::load_team_agent (team-local + global fallback)
    /// 3. Minimal default AgentDef (last resort)
    async fn resolve_agent_def(&self, team_name: &str, name: &str) -> Option<AgentDef> {
        // 1. Try the agent library first (fastest path)
        if let Some(def) = self.library.get(name) {
            return Some(def);
        }

        // 2. Try EntityLoader (team-local agents directory + global fallback)
        let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
        let data_root = std::path::PathBuf::from(home).join(".nebflow");
        let entity_loader = nebflow_core::flow::loader::EntityLoader::new(data_root);

        if let Some(entry) = entity_loader.load_team_agent(team_name, name).await {
            return Some(AgentDef {
                name: entry.name.clone(),
                description: entry.description.clone(),
                tools: if entry.tools.is_empty() {
                    vec!["*".into()]
                } else {
                    entry.tools.clone()
                },
                system_prompt: entry.system_prompt.clone(),
                avatar: None,
                display_name: None,
                voice_enabled: entry.voice,
                model: entry.model.clone(),
                category: match entry.category.as_str() {
                    "team" => AgentCategory::TeamMember,
                    "flow" => AgentCategory::FlowNode,
                    "team_lead" | "teamlead" => AgentCategory::TeamLead,
                    _ => AgentCategory::TeamMember,
                },
                mcp_servers: entry.mcp_servers.clone(),
            });
        }

        // 3. Last resort: minimal default AgentDef
        if name.is_empty() {
            None
        } else {
            Some(AgentDef {
                name: name.to_string(),
                description: format!("Team agent: {name}"),
                tools: vec!["*".into()],
                system_prompt: format!("You are {name}, a team agent."),
                avatar: None,
                display_name: None,
                voice_enabled: false,
                model: None,
                category: AgentCategory::TeamMember,
                mcp_servers: vec![],
            })
        }
    }
}

fn now_millis() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use nebflow_core::config::{NebflowServiceConfig, ThinkingConfig};

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

    fn make_loader() -> TeamLoader {
        let resources = Arc::new(SharedResources::new(
            test_config(),
            std::path::PathBuf::from("/tmp"),
            ThinkingConfig {
                enabled: false,
                budget_tokens: 0,
            },
        ));
        let library = Arc::new(AgentLibrary::new(std::path::PathBuf::from(
            "/tmp/nonexistent-agents",
        )));
        let membership = Arc::new(FlowMembership::new());
        let mail_registry = Arc::new(AgentMailRegistry::new());
        TeamLoader::new(resources, library, membership, mail_registry)
    }

    #[tokio::test]
    async fn team_loader_new() {
        let loader = make_loader();
        assert!(loader.handles.lock().await.is_empty());
    }

    #[tokio::test]
    async fn unmount_empty_flow_doesnt_panic() {
        let loader = make_loader();
        loader.unmount("nonexistent").await;
        // Should not panic
    }

    #[tokio::test]
    async fn resolve_agent_def_fallback() {
        let loader = make_loader();
        // Library is empty and EntityLoader won't find anything, but the
        // fallback should create a minimal def
        let def = loader
            .resolve_agent_def("test-team", "TestAgent")
            .await
            .unwrap();
        assert_eq!(def.name, "TestAgent");
        assert_eq!(def.category, AgentCategory::TeamMember);
    }

    #[tokio::test]
    async fn resolve_agent_def_empty_name_returns_none() {
        let loader = make_loader();
        assert!(loader.resolve_agent_def("test-team", "").await.is_none());
    }
}
