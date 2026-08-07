//! Agent library — loads agent definitions from disk.
//! Mirrors Scala AgentLibrary.

use nebflow_core::types::{AgentCategory, AgentDef, AgentModelConfig};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

/// Loads agent definitions from a directory structure.
pub struct AgentLibrary {
    agents_dir: PathBuf,
}

impl AgentLibrary {
    pub fn new(agents_dir: impl Into<PathBuf>) -> Self {
        Self {
            agents_dir: agents_dir.into(),
        }
    }

    pub fn load_all(&self) -> HashMap<String, AgentDef> {
        let mut agents = HashMap::new();
        if self.agents_dir.exists() {
            if let Ok(entries) = std::fs::read_dir(&self.agents_dir) {
                for entry in entries.flatten() {
                    let path = entry.path();
                    if path.is_dir() {
                        if let Some(agent) = self.load_agent(&path) {
                            agents.insert(agent.name.clone(), agent);
                        }
                    }
                }
            }
        }
        if !agents.contains_key("Nebula") {
            agents.insert("Nebula".into(), Self::nebula_fallback());
        }
        agents
    }

    pub fn get(&self, name: &str) -> Option<AgentDef> {
        self.load_all().get(name).cloned()
    }

    fn load_agent(&self, dir: &Path) -> Option<AgentDef> {
        let json_path = dir.join("agent.json");
        let system_md_path = dir.join("system.md");
        let json_str = std::fs::read_to_string(&json_path).ok()?;
        let json: serde_json::Value = serde_json::from_str(&json_str).ok()?;
        let name = json
            .get("name")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let description = json
            .get("description")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let system_prompt = std::fs::read_to_string(&system_md_path)
            .unwrap_or_default()
            .trim()
            .to_string();
        let tools = json
            .get("tools")
            .and_then(|v| v.as_array())
            .map(|a| {
                a.iter()
                    .filter_map(|v| v.as_str().map(String::from))
                    .collect()
            })
            .unwrap_or_else(|| vec!["*".into()]);
        let voice = json.get("voice").and_then(|v| v.as_bool()).unwrap_or(false);
        let category = json
            .get("category")
            .and_then(|v| v.as_str())
            .unwrap_or("standalone")
            .to_string();
        let mcp_servers = json
            .get("mcpServers")
            .and_then(|v| v.as_array())
            .map(|a| {
                a.iter()
                    .filter_map(|v| v.as_str().map(String::from))
                    .collect()
            })
            .unwrap_or_default();
        let model = json.get("model").and_then(|v| {
            if v.is_null() {
                None
            } else {
                Some(AgentModelConfig {
                    preferred: v
                        .get("preferred")
                        .and_then(|v| v.as_str())
                        .map(String::from),
                    fallbacks: v
                        .get("fallbacks")
                        .and_then(|v| v.as_array())
                        .map(|a| {
                            a.iter()
                                .filter_map(|v| v.as_str().map(String::from))
                                .collect()
                        })
                        .unwrap_or_default(),
                })
            }
        });
        let avatar = json
            .get("avatar")
            .and_then(|v| v.as_str())
            .map(String::from);
        let display_name = json
            .get("displayName")
            .and_then(|v| v.as_str())
            .map(String::from);
        let agent_category = match category.as_str() {
            "team-lead" => AgentCategory::TeamLead,
            "team-member" => AgentCategory::TeamMember,
            "flow-node" => AgentCategory::FlowNode,
            _ => AgentCategory::Standalone,
        };
        Some(AgentDef {
            name,
            description,
            tools,
            system_prompt,
            avatar,
            display_name,
            voice_enabled: voice,
            model,
            category: agent_category,
            mcp_servers,
        })
    }

    fn nebula_fallback() -> AgentDef {
        AgentDef {
            name: "Nebula".into(),
            description: "Nebflow's primary assistant".into(),
            tools: vec!["*".into()],
            system_prompt: "You are Nebula.".into(),
            avatar: None,
            display_name: Some("Nebula".into()),
            voice_enabled: false,
            model: None,
            category: AgentCategory::Standalone,
            mcp_servers: vec![],
        }
    }

    pub fn update_system_prompt(&self, name: &str, content: &str) -> std::io::Result<()> {
        let dir = self.agents_dir.join(name);
        std::fs::create_dir_all(&dir)?;
        std::fs::write(dir.join("system.md"), content)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn load_all_from_empty_dir() {
        let tmp = tempfile::tempdir().unwrap();
        let lib = AgentLibrary::new(tmp.path());
        let agents = lib.load_all();
        assert!(agents.contains_key("Nebula"));
    }

    #[test]
    fn load_all_from_nonexistent_dir() {
        let lib = AgentLibrary::new("/nonexistent/path/agents");
        let agents = lib.load_all();
        assert!(agents.contains_key("Nebula"));
    }

    #[test]
    fn load_agent_from_disk() {
        let tmp = tempfile::tempdir().unwrap();
        let agent_dir = tmp.path().join("test-agent");
        std::fs::create_dir_all(&agent_dir).unwrap();
        std::fs::write(agent_dir.join("agent.json"),
            r#"{"name":"test-agent","description":"A test","tools":["Read","Write"],"category":"standalone"}"#
        ).unwrap();
        std::fs::write(agent_dir.join("system.md"), "You are a test agent.").unwrap();
        let lib = AgentLibrary::new(tmp.path());
        let agent = lib.get("test-agent").unwrap();
        assert_eq!(agent.description, "A test");
        assert_eq!(agent.system_prompt, "You are a test agent.");
        assert_eq!(agent.tools, vec!["Read", "Write"]);
    }

    #[test]
    fn load_agent_defaults() {
        let tmp = tempfile::tempdir().unwrap();
        let agent_dir = tmp.path().join("minimal");
        std::fs::create_dir_all(&agent_dir).unwrap();
        std::fs::write(
            agent_dir.join("agent.json"),
            r#"{"name":"minimal","description":"Minimal"}"#,
        )
        .unwrap();
        let lib = AgentLibrary::new(tmp.path());
        let agent = lib.get("minimal").unwrap();
        assert_eq!(agent.tools, vec!["*".to_string()]);
        assert!(!agent.voice_enabled);
    }

    #[test]
    fn nebula_fallback_has_correct_fields() {
        let agent = AgentLibrary::nebula_fallback();
        assert_eq!(agent.name, "Nebula");
        assert!(!agent.system_prompt.is_empty());
    }

    #[test]
    fn update_system_prompt_writes_file() {
        let tmp = tempfile::tempdir().unwrap();
        let lib = AgentLibrary::new(tmp.path());
        lib.update_system_prompt("test", "New content").unwrap();
        let content = std::fs::read_to_string(tmp.path().join("test").join("system.md")).unwrap();
        assert_eq!(content, "New content");
    }
}
