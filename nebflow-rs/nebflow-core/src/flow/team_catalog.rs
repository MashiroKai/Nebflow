//! Team catalog — builds catalog strings for system prompt injection.
//!
//! Mirrors Scala `TeamCatalog.scala`.

use std::collections::HashMap;

use crate::entity::{AgentEntry, FlowDagDef, TeamDef};

/// Builds Team catalog strings for system prompt injection.
pub struct TeamCatalog;

impl TeamCatalog {
    /// Build the Team catalog section for a team agent's system prompt.
    /// Shows team lead, members (with description + useWhen), and available flows.
    pub fn build_catalog(
        team: &TeamDef,
        agents: &HashMap<String, AgentEntry>,
        flows: &HashMap<String, FlowDagDef>,
    ) -> String {
        let lead_line = match agents.get(&team.lead) {
            Some(a) => format!(
                "- {}: {}\n  Use when: {}",
                team.lead, a.description, a.use_when
            ),
            None => format!("- {}: (agent not found)", team.lead),
        };

        let member_lines: Vec<String> = team
            .members
            .iter()
            .filter_map(|name| {
                agents
                    .get(name)
                    .map(|a| format!("- {}: {}\n  Use when: {}", name, a.description, a.use_when))
            })
            .collect();

        let flow_lines: Vec<String> = team
            .flows
            .iter()
            .filter_map(|name| {
                flows
                    .get(name)
                    .map(|f| format!("- {}: {}", f.name, f.description))
            })
            .collect();

        format!(
            "=== Team: {} ===\n\n{}\n\nTeam Lead (Mail by name):\n{}\n\nTeam Members (Mail by name):\n{}\n\nAvailable Flows (Mail by name to trigger):\n{}\n\n=== End Team ===",
            team.name,
            team.description,
            lead_line,
            member_lines.join("\n"),
            flow_lines.join("\n"),
        )
    }

    /// Build a global catalog for Nebula (not part of any team).
    /// Lists all teams, flows, and standalone agents with routing guidance.
    pub fn build_global_catalog(
        teams: &HashMap<String, TeamDef>,
        flows: &HashMap<String, FlowDagDef>,
        agents: &HashMap<String, AgentEntry>,
    ) -> String {
        let team_lines: Vec<String> = {
            let mut sorted: Vec<&TeamDef> = teams.values().collect();
            sorted.sort_by_key(|t| &t.name);
            sorted
                .iter()
                .map(|t| {
                    let mut all_members = vec![t.lead.clone()];
                    all_members.extend(t.members.clone());
                    all_members.dedup();
                    format!(
                        "- {}: {}\n  Members: {}",
                        t.name,
                        t.description,
                        all_members.join(", ")
                    )
                })
                .collect()
        };

        let flow_lines: Vec<String> = {
            let mut sorted: Vec<&FlowDagDef> = flows.values().collect();
            sorted.sort_by_key(|f| &f.name);
            sorted
                .iter()
                .map(|f| format!("- {}: {}", f.name, f.description))
                .collect()
        };

        let standalone_agents: Vec<&AgentEntry> = {
            let mut filtered: Vec<&AgentEntry> = agents
                .values()
                .filter(|a| a.category == "standalone" && a.name != "Nebula")
                .collect();
            filtered.sort_by_key(|a| &a.name);
            filtered
        };

        let agent_lines: Vec<String> = standalone_agents
            .iter()
            .map(|a| format!("- {}: {}", a.name, a.description))
            .collect();

        let agents_section = if !agent_lines.is_empty() {
            format!(
                "## Standalone Agents (Mail by name for direct delegation)\n{}\n",
                agent_lines.join("\n")
            )
        } else {
            String::new()
        };

        let teams_section = if !team_lines.is_empty() {
            team_lines.join("\n")
        } else {
            "(none — create one with entity-creator flow)".to_string()
        };

        let flows_section = if !flow_lines.is_empty() {
            flow_lines.join("\n")
        } else {
            "(none)".to_string()
        };

        format!(
            "=== Teams & Flows ===\n\n\
## When to use what\n\n\
**Agent** — functional specialist for simple tasks. No persistent context.\n  \
→ `Mail(\"agent-name\", \"your task\")`\n\n\
**Team** — ongoing project work. The Team Lead receives your task and coordinates members internally.\n  \
→ `Mail(\"team-name\", \"your task\")`\n\n\
**Flow** — structured one-shot pipeline. Executes a fixed DAG of agents, returns result.\n  \
→ `Mail(\"flow-name\", \"your task\")`\n\n\
{agents_section}\
## Teams\n{teams_section}\n\n\
## Flows\n{flows_section}\n\n\
=== End ===",
            agents_section = agents_section,
            teams_section = teams_section,
            flows_section = flows_section,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_agent(name: &str, desc: &str, use_when: &str) -> AgentEntry {
        AgentEntry {
            name: name.to_string(),
            description: desc.to_string(),
            use_when: use_when.to_string(),
            tools: vec!["*".into()],
            voice: false,
            system_prompt: String::new(),
            category: "standalone".into(),
            mcp_servers: vec![],
            model: None,
        }
    }

    #[test]
    fn build_catalog_includes_all_members() {
        let team = TeamDef {
            name: "dev-team".into(),
            description: "Dev team".into(),
            lead: "Manager".into(),
            members: vec!["Backend".into(), "Frontend".into()],
            flows: vec!["review".into()],
        };

        let mut agents = HashMap::new();
        agents.insert(
            "Manager".into(),
            make_agent("Manager", "Team lead", "coordinating"),
        );
        agents.insert(
            "Backend".into(),
            make_agent("Backend", "Backend dev", "API work"),
        );
        agents.insert(
            "Frontend".into(),
            make_agent("Frontend", "Frontend dev", "UI work"),
        );

        let mut flows = HashMap::new();
        flows.insert(
            "review".into(),
            FlowDagDef {
                name: "review".into(),
                description: "Code review pipeline".into(),
                nodes: HashMap::new(),
                entry: "start".into(),
                max_loop: 10,
            },
        );

        let catalog = TeamCatalog::build_catalog(&team, &agents, &flows);

        assert!(catalog.contains("=== Team: dev-team ==="));
        assert!(catalog.contains("Manager: Team lead"));
        assert!(catalog.contains("Backend: Backend dev"));
        assert!(catalog.contains("Frontend: Frontend dev"));
        assert!(catalog.contains("review: Code review pipeline"));
    }

    #[test]
    fn build_catalog_missing_agent() {
        let team = TeamDef {
            name: "t".into(),
            description: "d".into(),
            lead: "Ghost".into(),
            members: vec![],
            flows: vec![],
        };

        let catalog = TeamCatalog::build_catalog(&team, &HashMap::new(), &HashMap::new());
        assert!(catalog.contains("(agent not found)"));
    }

    #[test]
    fn build_global_catalog_lists_teams_and_flows() {
        let mut teams = HashMap::new();
        teams.insert(
            "proj".into(),
            TeamDef {
                name: "proj".into(),
                description: "Project team".into(),
                lead: "Manager".into(),
                members: vec!["Worker".into()],
                flows: vec![],
            },
        );

        let mut flows = HashMap::new();
        flows.insert(
            "deploy".into(),
            FlowDagDef {
                name: "deploy".into(),
                description: "Deploy pipeline".into(),
                nodes: HashMap::new(),
                entry: "build".into(),
                max_loop: 5,
            },
        );

        let catalog = TeamCatalog::build_global_catalog(&teams, &flows, &HashMap::new());

        assert!(catalog.contains("=== Teams & Flows ==="));
        assert!(catalog.contains("proj: Project team"));
        assert!(catalog.contains("deploy: Deploy pipeline"));
        assert!(catalog.contains("Mail(\"team-name\""));
        assert!(catalog.contains("Mail(\"flow-name\""));
    }

    #[test]
    fn build_global_catalog_with_standalone_agents() {
        let mut agents = HashMap::new();
        agents.insert(
            "Coder".into(),
            make_agent("Coder", "Code specialist", "coding"),
        );
        agents.insert(
            "Nebula".into(),
            make_agent("Nebula", "Main agent", "everything"),
        );

        let catalog = TeamCatalog::build_global_catalog(&HashMap::new(), &HashMap::new(), &agents);

        assert!(catalog.contains("Coder: Code specialist"));
        // Nebula should not appear in standalone agents
        assert!(!catalog.contains("Nebula: Main agent"));
    }

    #[test]
    fn build_global_catalog_empty() {
        let catalog =
            TeamCatalog::build_global_catalog(&HashMap::new(), &HashMap::new(), &HashMap::new());
        assert!(catalog.contains("(none"));
    }
}
