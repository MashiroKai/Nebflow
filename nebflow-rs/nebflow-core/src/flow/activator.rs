//! Flow agent activator — activates team/flow agent sessions on demand.
//!
//! Mirrors Scala `FlowAgentActivator.scala`. In the Rust port, agent activation
//! is represented as a trait that the agent engine implements.
//!
//! The activator loads team definitions, builds team context, and delegates
//! to the agent engine for actor creation.

use std::collections::HashMap;
use std::sync::Arc;

use crate::entity::{AgentEntry, FlowDagDef, TeamDef};
use crate::flow::loader::EntityLoader;
use crate::flow::membership::FlowMembership;
use crate::flow::team_catalog::TeamCatalog;

/// Trait for activating an agent session.
/// Implementations create an agent actor with the given definition and context.
#[async_trait::async_trait]
pub trait AgentActivationTrait: Send + Sync {
    /// Activate an agent session with the given agent definition and context.
    /// Returns the session ID of the activated agent.
    async fn activate(
        &self,
        agent_name: &str,
        agent_entry: &AgentEntry,
        system_prompt: &str,
        session_id: &str,
    ) -> Result<String, String>;
}

/// Flow agent activator.
pub struct FlowAgentActivator {
    loader: Arc<EntityLoader>,
    membership: Arc<FlowMembership>,
}

impl FlowAgentActivator {
    pub fn new(loader: Arc<EntityLoader>, membership: Arc<FlowMembership>) -> Self {
        Self { loader, membership }
    }

    /// Resolve a flow agent's definition from disk by team name + agent name.
    pub async fn resolve_agent_from_disk(
        &self,
        flow_name: &str,
        agent_name: &str,
    ) -> Option<AgentEntry> {
        // Check if it's a team
        if self.loader.load_team(flow_name).await.is_some() {
            return self.loader.load_team_agent(flow_name, agent_name).await;
        }
        None
    }

    /// Ensure a flow agent session has a running actor.
    /// If already running (tracked in membership), returns the existing session.
    /// If not, activates by loading the definition and delegating to the activation trait.
    pub async fn ensure_session(
        &self,
        session_id: &str,
        flow_name: &str,
        agent_name: &str,
        activator: &dyn AgentActivationTrait,
    ) -> Result<String, String> {
        // Check if already registered in membership
        if let Some(_existing) = self
            .membership
            .resolve_team_agent(flow_name, agent_name)
            .await
        {
            return Ok(session_id.to_string());
        }

        // Load agent definition
        let agent_entry = self
            .resolve_agent_from_disk(flow_name, agent_name)
            .await
            .ok_or_else(|| format!("Agent '{}' not found in team '{}'", agent_name, flow_name))?;

        // Load team definition
        let team_def = self
            .loader
            .load_team(flow_name)
            .await
            .ok_or_else(|| format!("Team '{}' not found", flow_name))?;

        // Build team context
        let all_agents = self.loader.list_agents().await;
        let team_agents: HashMap<String, AgentEntry> = all_agents
            .into_iter()
            .filter(|(name, _)| name == &team_def.lead || team_def.members.contains(name))
            .collect();

        let all_flows = self.loader.list_flows().await;
        let team_flows: HashMap<String, FlowDagDef> = all_flows
            .into_iter()
            .filter(|(name, _)| team_def.flows.contains(name))
            .collect();

        let is_lead = team_def.lead == agent_name;
        let team_context = build_team_context(
            flow_name,
            agent_name,
            is_lead,
            &team_def,
            &team_agents,
            &team_flows,
        );

        let combined_prompt = format!("{}{}", team_context, agent_entry.system_prompt);

        // Activate via the trait
        activator
            .activate(agent_name, &agent_entry, &combined_prompt, session_id)
            .await
    }
}

/// Ensure tools list includes "Mail".
pub fn ensure_mail(tools: &[String]) -> Vec<String> {
    if tools.contains(&"*".to_string()) || tools.contains(&"Mail".to_string()) {
        tools.to_vec()
    } else {
        let mut result = tools.to_vec();
        result.push("Mail".into());
        result
    }
}

/// Build the Team context string injected into a team agent's system prompt.
pub fn build_team_context(
    _team_name: &str,
    agent_name: &str,
    is_lead: bool,
    team: &TeamDef,
    agents: &HashMap<String, AgentEntry>,
    flows: &HashMap<String, FlowDagDef>,
) -> String {
    let catalog = TeamCatalog::build_catalog(team, agents, flows);
    let lead_note = if is_lead {
        "\n\nWhen the team's work is complete, Mail your final summary to the caller."
    } else {
        ""
    };

    format!(
        "{catalog}{lead_note}\n\n\
You are \"{agent_name}\" in this team.\n\
Usage: Mail(\"agent_name\", \"your message\"). Use short names only.\n\n\
Rules:\n\
- All team members can Mail each other.\n\
- Focus on your task. When done, Mail your result to the lead or next agent.\n\
- You are an execution pipeline, not an AI assistant. No pleasantries, no unnecessary explanations.\n\
- Report results via Mail only. Do not stream output to the user.\n\
- Workers report to the Team Lead via Mail. Do not contact agents outside this team directly.\n",
        catalog = catalog,
        lead_note = lead_note,
        agent_name = agent_name,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ensure_mail_adds_when_missing() {
        let tools = vec!["Read".to_string(), "Write".to_string()];
        let result = ensure_mail(&tools);
        assert!(result.contains(&"Mail".to_string()));
        assert_eq!(result.len(), 3);
    }

    #[test]
    fn ensure_mail_wildcard_no_change() {
        let tools = vec!["*".to_string()];
        let result = ensure_mail(&tools);
        assert_eq!(result, vec!["*".to_string()]);
    }

    #[test]
    fn ensure_mail_already_present() {
        let tools = vec!["Read".to_string(), "Mail".to_string()];
        let result = ensure_mail(&tools);
        assert_eq!(result.len(), 2);
    }

    #[test]
    fn build_team_context_includes_catalog() {
        let team = TeamDef {
            name: "dev".into(),
            description: "Dev team".into(),
            lead: "Manager".into(),
            members: vec!["Worker".into()],
            flows: vec![],
        };
        let mut agents = HashMap::new();
        agents.insert(
            "Manager".into(),
            AgentEntry {
                name: "Manager".into(),
                description: "Lead".into(),
                use_when: "leading".into(),
                tools: vec![],
                voice: false,
                system_prompt: "".into(),
                category: "team".into(),
                mcp_servers: vec![],
                model: None,
            },
        );

        let ctx = build_team_context("dev", "Manager", true, &team, &agents, &HashMap::new());
        assert!(ctx.contains("=== Team: dev ==="));
        assert!(ctx.contains("You are \"Manager\""));
        assert!(ctx.contains("Mail your final summary"));
    }

    #[test]
    fn build_team_context_non_lead_no_summary_note() {
        let team = TeamDef {
            name: "dev".into(),
            description: "d".into(),
            lead: "Manager".into(),
            members: vec!["Worker".into()],
            flows: vec![],
        };

        let ctx = build_team_context(
            "dev",
            "Worker",
            false,
            &team,
            &HashMap::new(),
            &HashMap::new(),
        );
        assert!(!ctx.contains("Mail your final summary"));
    }
}
