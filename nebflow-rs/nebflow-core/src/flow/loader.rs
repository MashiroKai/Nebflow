//! Entity loader — loads agent/team/flow definitions from disk.
//!
//! Mirrors Scala `EntityLoader.scala`.
//!
//! Three-layer agent search: global (`~/.nebflow/agents/`)
//! → team (`~/.nebflow/teams/*/agents/`)
//! → flow (`~/.nebflow/flows/*/agents/`).

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use tokio::fs;

use crate::entity::{AgentEntry, FlowDagDef, NodeRouteValue, TeamDef};
// FlowNode used in validate_flow tests
#[cfg(test)]
use crate::entity::FlowNode;

/// Loads Team/Flow/Agent definitions from disk.
pub struct EntityLoader {
    data_root: PathBuf,
}

impl EntityLoader {
    pub fn new(data_root: PathBuf) -> Self {
        Self { data_root }
    }

    /// Create with default `~/.nebflow` data root.
    pub fn default_for(home: &Path) -> Self {
        Self::new(home.join(".nebflow"))
    }

    fn agents_dir(&self) -> PathBuf {
        self.data_root.join("agents")
    }

    fn teams_dir(&self) -> PathBuf {
        self.data_root.join("teams")
    }

    fn flows_dir(&self) -> PathBuf {
        self.data_root.join("flows")
    }

    // ==========================================================
    // Team
    // ==========================================================

    /// Load a team definition by name from `teams/<name>/team.json`.
    pub async fn load_team(&self, name: &str) -> Option<TeamDef> {
        let json_path = self.teams_dir().join(name).join("team.json");
        match fs::read_to_string(&json_path).await {
            Ok(content) => match serde_json::from_str::<TeamDef>(&content) {
                Ok(td) => Some(td),
                Err(e) => {
                    tracing::warn!(target: "entity.loader", "Failed to parse team '{}': {}", name, e);
                    None
                }
            },
            Err(_) => None,
        }
    }

    /// List all teams from `teams/<name>/team.json`.
    pub async fn list_teams(&self) -> HashMap<String, TeamDef> {
        let mut result = HashMap::new();
        let teams_dir = self.teams_dir();
        let mut entries = match fs::read_dir(&teams_dir).await {
            Ok(e) => e,
            Err(_) => return result,
        };
        while let Ok(Some(entry)) = entries.next_entry().await {
            if !entry.path().is_dir() {
                continue;
            }
            let dir_name = entry.file_name().to_string_lossy().to_string();
            let json_path = entry.path().join("team.json");
            if let Ok(content) = fs::read_to_string(&json_path).await {
                if let Ok(td) = serde_json::from_str::<TeamDef>(&content) {
                    result.insert(dir_name, td);
                }
            }
        }
        result
    }

    /// Load team rules from `teams/<name>/rules.md`.
    pub async fn load_team_rules(&self, name: &str) -> String {
        let p = self.teams_dir().join(name).join("rules.md");
        fs::read_to_string(&p).await.unwrap_or_default()
    }

    // ==========================================================
    // Flow DAG
    // ==========================================================

    /// Load a flow DAG definition. Tries directory format first, then single file.
    pub async fn load_flow(&self, name: &str) -> Option<FlowDagDef> {
        let dir_path = self.flows_dir().join(name).join("flow.json");
        let file_path = self.flows_dir().join(format!("{}.json", name));

        let path = if dir_path.exists() {
            dir_path
        } else if file_path.exists() {
            file_path
        } else {
            return None;
        };

        match fs::read_to_string(&path).await {
            Ok(content) => match serde_json::from_str::<FlowDagDef>(&content) {
                Ok(fd) => Some(fd),
                Err(e) => {
                    tracing::warn!(target: "entity.loader", "Failed to parse flow '{}': {}", name, e);
                    None
                }
            },
            Err(_) => None,
        }
    }

    /// List all flows. Scans both directory format and single file.
    pub async fn list_flows(&self) -> HashMap<String, FlowDagDef> {
        let mut result = HashMap::new();
        let flows_dir = self.flows_dir();
        let mut entries = match fs::read_dir(&flows_dir).await {
            Ok(e) => e,
            Err(_) => return result,
        };
        while let Ok(Some(entry)) = entries.next_entry().await {
            let path = entry.path();
            if path.is_dir() {
                // Directory format: flows/<name>/flow.json
                let name = entry.file_name().to_string_lossy().to_string();
                let json_path = path.join("flow.json");
                if let Ok(content) = fs::read_to_string(&json_path).await {
                    if let Ok(fd) = serde_json::from_str::<FlowDagDef>(&content) {
                        result.insert(name, fd);
                    }
                }
            } else if path.extension().and_then(|e| e.to_str()) == Some("json") {
                // Single file format: flows/<name>.json
                let name = entry
                    .file_name()
                    .to_string_lossy()
                    .trim_end_matches(".json")
                    .to_string();
                if let Ok(content) = fs::read_to_string(&path).await {
                    if let Ok(fd) = serde_json::from_str::<FlowDagDef>(&content) {
                        result.insert(name, fd);
                    }
                }
            }
        }
        result
    }

    // ==========================================================
    // Agent
    // ==========================================================

    /// Load agent entry from an arbitrary directory.
    pub fn load_agent_from_dir(dir: &Path) -> Option<AgentEntry> {
        let json_path = dir.join("agent.json");
        if !json_path.exists() {
            return None;
        }
        let content = std::fs::read_to_string(&json_path).ok()?;
        let mut entry: AgentEntry = serde_json::from_str(&content).ok()?;
        // Load system.md if present
        let sys_md = dir.join("system.md");
        if sys_md.exists() {
            entry.system_prompt = std::fs::read_to_string(&sys_md).unwrap_or_default();
        }
        // Fall back to directory name if agent.json has no name field
        if entry.name.is_empty() {
            entry.name = dir
                .file_name()
                .and_then(|n| n.to_str())
                .unwrap_or("unknown")
                .to_string();
        }
        Some(entry)
    }

    /// Load agent entry from `agents/<name>/agent.json` + `system.md`.
    pub async fn load_agent(&self, name: &str) -> Option<AgentEntry> {
        let dir = self.agents_dir().join(name);
        // Use blocking call since load_agent_from_dir is sync
        let dir_clone = dir.clone();
        tokio::task::spawn_blocking(move || Self::load_agent_from_dir(&dir_clone))
            .await
            .ok()
            .flatten()
    }

    /// Load team-local agent: `teams/<teamName>/agents/<agentName>/` → fallback to global.
    pub async fn load_team_agent(&self, team_name: &str, agent_name: &str) -> Option<AgentEntry> {
        let team_agent_dir = self
            .teams_dir()
            .join(team_name)
            .join("agents")
            .join(agent_name);
        if team_agent_dir.join("agent.json").exists() {
            let dir = team_agent_dir.clone();
            if let Some(entry) =
                tokio::task::spawn_blocking(move || Self::load_agent_from_dir(&dir))
                    .await
                    .ok()
                    .flatten()
            {
                return Some(entry);
            }
        }
        // Fallback to global
        self.load_agent(agent_name).await
    }

    /// Load flow-local agent: `flows/<flowName>/agents/<agentName>/` → fallback to global.
    pub async fn load_flow_agent(&self, flow_name: &str, agent_name: &str) -> Option<AgentEntry> {
        let flow_agent_dir = self
            .flows_dir()
            .join(flow_name)
            .join("agents")
            .join(agent_name);
        if flow_agent_dir.join("agent.json").exists() {
            let dir = flow_agent_dir.clone();
            if let Some(entry) =
                tokio::task::spawn_blocking(move || Self::load_agent_from_dir(&dir))
                    .await
                    .ok()
                    .flatten()
            {
                return Some(entry);
            }
        }
        // Fallback to global
        self.load_agent(agent_name).await
    }

    /// List all agents with runtime category inference.
    pub async fn list_agents(&self) -> HashMap<String, AgentEntry> {
        let mut raw_agents = HashMap::new();
        let agents_dir = self.agents_dir();
        if let Ok(mut entries) = fs::read_dir(&agents_dir).await {
            while let Ok(Some(entry)) = entries.next_entry().await {
                if !entry.path().is_dir() {
                    continue;
                }
                let dir = entry.path();
                let dir_clone = dir.clone();
                if let Some(agent_entry) =
                    tokio::task::spawn_blocking(move || Self::load_agent_from_dir(&dir_clone))
                        .await
                        .ok()
                        .flatten()
                {
                    let name = entry.file_name().to_string_lossy().to_string();
                    raw_agents.insert(name, agent_entry);
                }
            }
        }

        let teams = self.list_teams().await;
        let flows = self.list_flows().await;

        // Infer category for agents with default "standalone"
        let mut inferred = HashMap::new();
        for (name, mut entry) in raw_agents {
            if entry.category == "standalone" {
                entry.category = classify_agent(&name, &teams, &flows);
            }
            inferred.insert(name, entry);
        }
        inferred
    }

    /// Find an agent directory by name across all three layers.
    pub async fn find_agent_dir(&self, name: &str) -> Option<PathBuf> {
        // 1. Global agents — name == dir name, fast path
        let global_dir = self.agents_dir().join(name);
        if global_dir.join("agent.json").exists() {
            return Some(global_dir);
        }

        // 2. Team agents — scan all team agent dirs
        let teams_dir = self.teams_dir();
        if let Ok(mut team_entries) = fs::read_dir(&teams_dir).await {
            while let Ok(Some(team_dir)) = team_entries.next_entry().await {
                if !team_dir.path().is_dir() {
                    continue;
                }
                let agents_subdir = team_dir.path().join("agents");
                if agents_subdir.exists() {
                    if let Ok(mut agent_entries) = fs::read_dir(&agents_subdir).await {
                        while let Ok(Some(agent_dir)) = agent_entries.next_entry().await {
                            if !agent_dir.path().is_dir() {
                                continue;
                            }
                            if let Some(entry) = Self::load_agent_from_dir(&agent_dir.path()) {
                                if entry.name == name {
                                    return Some(agent_dir.path());
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Flow agents
        let flows_dir = self.flows_dir();
        if let Ok(mut flow_entries) = fs::read_dir(&flows_dir).await {
            while let Ok(Some(flow_dir)) = flow_entries.next_entry().await {
                if !flow_dir.path().is_dir() {
                    continue;
                }
                let agents_subdir = flow_dir.path().join("agents");
                if agents_subdir.exists() {
                    if let Ok(mut agent_entries) = fs::read_dir(&agents_subdir).await {
                        while let Ok(Some(agent_dir)) = agent_entries.next_entry().await {
                            if !agent_dir.path().is_dir() {
                                continue;
                            }
                            if let Some(entry) = Self::load_agent_from_dir(&agent_dir.path()) {
                                if entry.name == name {
                                    return Some(agent_dir.path());
                                }
                            }
                        }
                    }
                }
            }
        }

        None
    }

    /// Validate a TeamDef: check lead and all members exist.
    pub fn validate_team(
        team: &TeamDef,
        agents: &std::collections::HashSet<String>,
    ) -> Vec<String> {
        let mut errors = Vec::new();
        if !agents.contains(&team.lead) {
            errors.push(format!("lead agent '{}' not found", team.lead));
        }
        for m in &team.members {
            if !agents.contains(m) {
                errors.push(format!("member agent '{}' not found", m));
            }
        }
        errors
    }

    /// Validate a FlowDagDef: check all node agents exist, entry is valid, routes are valid.
    pub fn validate_flow(
        flow: &FlowDagDef,
        agents: &std::collections::HashSet<String>,
    ) -> Vec<String> {
        let mut errors = Vec::new();

        if !flow.nodes.contains_key(&flow.entry) {
            errors.push(format!("entry node '{}' not in nodes", flow.entry));
        }

        let mut missing_agents: Vec<String> = Vec::new();
        for node in flow.nodes.values() {
            if !agents.contains(&node.agent) {
                missing_agents.push(format!("agent '{}' not found", node.agent));
            }
        }
        missing_agents.sort();
        missing_agents.dedup();
        errors.extend(missing_agents);

        for (id, node) in &flow.nodes {
            match &node.on_complete {
                NodeRouteValue::Goto(target) => {
                    if !flow.nodes.contains_key(target) {
                        errors.push(format!("node '{}' routes to unknown node '{}'", id, target));
                    }
                }
                NodeRouteValue::Return => {}
                NodeRouteValue::Switch { cases, .. } => {
                    for (condition, target) in cases {
                        if target != "$return" && !flow.nodes.contains_key(target) {
                            errors.push(format!(
                                "node '{}' switch case '{}' routes to unknown node '{}'",
                                id, condition, target
                            ));
                        }
                    }
                }
            }
        }

        errors
    }
}

/// Infer agent category from team/flow membership.
pub fn classify_agent(
    name: &str,
    teams: &HashMap<String, TeamDef>,
    flows: &HashMap<String, FlowDagDef>,
) -> String {
    let in_team = teams
        .values()
        .any(|t| t.lead == name || t.members.contains(&name.to_string()));
    let in_flow = flows
        .values()
        .any(|f| f.nodes.values().any(|n| n.agent == name));

    match (in_team, in_flow) {
        (true, _) => "team",
        (false, true) => "flow",
        (false, false) => "standalone",
    }
    .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn setup_test_env() -> (TempDir, EntityLoader) {
        let tmp = TempDir::new().unwrap();
        let loader = EntityLoader::new(tmp.path().to_path_buf());
        (tmp, loader)
    }

    async fn write_json(path: &Path, content: &str) {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).await.unwrap();
        }
        fs::write(path, content).await.unwrap();
    }

    #[tokio::test]
    async fn load_agent_from_global() {
        let (tmp, loader) = setup_test_env();
        let agent_dir = tmp.path().join("agents").join("test-agent");
        fs::create_dir_all(&agent_dir).await.unwrap();
        write_json(
            &agent_dir.join("agent.json"),
            r#"{"name":"test-agent","description":"A test agent"}"#,
        )
        .await;
        write_json(&agent_dir.join("system.md"), "You are a test agent.").await;

        let result = loader.load_agent("test-agent").await;
        assert!(result.is_some());
        let entry = result.unwrap();
        assert_eq!(entry.name, "test-agent");
        assert_eq!(entry.description, "A test agent");
        assert_eq!(entry.system_prompt, "You are a test agent.");
    }

    #[tokio::test]
    async fn load_agent_not_found() {
        let (_tmp, loader) = setup_test_env();
        let result = loader.load_agent("nonexistent").await;
        assert!(result.is_none());
    }

    #[tokio::test]
    async fn load_team_agent_fallback_to_global() {
        let (tmp, loader) = setup_test_env();
        // Create global agent
        let agent_dir = tmp.path().join("agents").join("shared");
        fs::create_dir_all(&agent_dir).await.unwrap();
        write_json(
            &agent_dir.join("agent.json"),
            r#"{"name":"shared","description":"Global agent"}"#,
        )
        .await;

        // No team-local agent — should fall back to global
        let result = loader.load_team_agent("some-team", "shared").await;
        assert!(result.is_some());
        assert_eq!(result.unwrap().name, "shared");
    }

    #[tokio::test]
    async fn load_team_from_disk() {
        let (tmp, loader) = setup_test_env();
        let team_dir = tmp.path().join("teams").join("my-team");
        fs::create_dir_all(&team_dir).await.unwrap();
        write_json(
            &team_dir.join("team.json"),
            r#"{"name":"my-team","description":"Test team","lead":"Manager","members":["Backend","Frontend"],"flows":["review"]}"#,
        )
        .await;

        let result = loader.load_team("my-team").await;
        assert!(result.is_some());
        let team = result.unwrap();
        assert_eq!(team.name, "my-team");
        assert_eq!(team.lead, "Manager");
        assert_eq!(team.members.len(), 2);
        assert_eq!(team.flows, vec!["review".to_string()]);
    }

    #[tokio::test]
    async fn load_flow_directory_format() {
        let (tmp, loader) = setup_test_env();
        let flow_dir = tmp.path().join("flows").join("review");
        fs::create_dir_all(&flow_dir).await.unwrap();
        write_json(
            &flow_dir.join("flow.json"),
            r#"{"name":"review","description":"Code review","nodes":{"scan":{"agent":"Explorer","input":"$task","onComplete":"reviewer","maxRetries":0},"review":{"agent":"Reviewer","input":"$scan.output","onComplete":"$return","maxRetries":1}},"entry":"scan","maxLoop":5}"#,
        )
        .await;

        let result = loader.load_flow("review").await;
        assert!(result.is_some());
        let flow = result.unwrap();
        assert_eq!(flow.name, "review");
        assert_eq!(flow.entry, "scan");
        assert_eq!(flow.nodes.len(), 2);
        assert_eq!(flow.max_loop, 5);
    }

    #[tokio::test]
    async fn load_flow_single_file_format() {
        let (tmp, loader) = setup_test_env();
        let flows_dir = tmp.path().join("flows");
        fs::create_dir_all(&flows_dir).await.unwrap();
        write_json(
            &flows_dir.join("deploy.json"),
            r#"{"name":"deploy","description":"Deploy flow","nodes":{"build":{"agent":"Builder","input":"$task","onComplete":"$return","maxRetries":0}},"entry":"build"}"#,
        )
        .await;

        let result = loader.load_flow("deploy").await;
        assert!(result.is_some());
        let flow = result.unwrap();
        assert_eq!(flow.name, "deploy");
        assert_eq!(flow.nodes.len(), 1);
    }

    #[tokio::test]
    async fn list_teams() {
        let (tmp, loader) = setup_test_env();
        for name in ["team-a", "team-b"] {
            let dir = tmp.path().join("teams").join(name);
            fs::create_dir_all(&dir).await.unwrap();
            write_json(
                &dir.join("team.json"),
                &format!(
                    r#"{{"name":"{}","description":"{}","lead":"lead"}}"#,
                    name, name
                ),
            )
            .await;
        }

        let teams = loader.list_teams().await;
        assert_eq!(teams.len(), 2);
        assert!(teams.contains_key("team-a"));
        assert!(teams.contains_key("team-b"));
    }

    #[tokio::test]
    async fn find_agent_dir_three_layer_search() {
        let (tmp, loader) = setup_test_env();
        // Create a flow-local agent
        let flow_agent_dir = tmp
            .path()
            .join("flows")
            .join("my-flow")
            .join("agents")
            .join("flow-agent");
        fs::create_dir_all(&flow_agent_dir).await.unwrap();
        write_json(
            &flow_agent_dir.join("agent.json"),
            r#"{"name":"flow-agent","description":"Flow agent"}"#,
        )
        .await;

        let result = loader.find_agent_dir("flow-agent").await;
        assert!(result.is_some());
        let found = result.unwrap();
        assert!(found.to_string_lossy().contains("flows"));
        assert!(found.to_string_lossy().contains("my-flow"));
    }

    #[tokio::test]
    async fn classify_agent_correctly() {
        let mut teams = HashMap::new();
        teams.insert(
            "t1".into(),
            TeamDef {
                name: "t1".into(),
                description: "d".into(),
                lead: "Alice".into(),
                members: vec!["Bob".into()],
                flows: vec![],
            },
        );
        let flows = HashMap::new();

        assert_eq!(classify_agent("Alice", &teams, &flows), "team");
        assert_eq!(classify_agent("Bob", &teams, &flows), "team");
        assert_eq!(classify_agent("Charlie", &teams, &flows), "standalone");
    }

    #[test]
    fn validate_flow_missing_entry() {
        let flow = FlowDagDef {
            name: "f".into(),
            description: "d".into(),
            nodes: HashMap::new(),
            entry: "nonexistent".into(),
            max_loop: 10,
        };
        let agents = std::collections::HashSet::new();
        let errors = EntityLoader::validate_flow(&flow, &agents);
        assert!(errors.iter().any(|e| e.contains("entry node")));
    }

    #[test]
    fn validate_flow_bad_route() {
        let mut nodes = HashMap::new();
        nodes.insert(
            "start".into(),
            FlowNode {
                agent: "Agent".into(),
                input: "$task".into(),
                on_complete: NodeRouteValue::Goto("unknown".into()),
                on_error: None,
                max_retries: 0,
            },
        );
        let flow = FlowDagDef {
            name: "f".into(),
            description: "d".into(),
            nodes,
            entry: "start".into(),
            max_loop: 10,
        };
        let mut agents = std::collections::HashSet::new();
        agents.insert("Agent".to_string());
        let errors = EntityLoader::validate_flow(&flow, &agents);
        assert!(errors.iter().any(|e| e.contains("unknown node")));
    }
}
