//! Context refresher — builds system prompt from agent definition + conditional blocks.
//! Mirrors Scala ContextRefresher.

use crate::prompt::{build_conditional_blocks, PromptContext};
use nebflow_core::types::AgentDef;

/// Build the stable system prompt — just the agent's system_prompt field.
/// This is the part that doesn't change between turns.
pub fn build_system_stable(agent_def: &AgentDef) -> Option<String> {
    if agent_def.system_prompt.is_empty() {
        None
    } else {
        Some(agent_def.system_prompt.clone())
    }
}

/// Build the dynamic system prompt — conditional blocks (rules, memory, skills,
/// devices, language, etc.). This part may change between turns as runtime
/// state evolves.
pub fn build_system_dynamic(ctx: &PromptContext) -> Option<String> {
    let conditional = build_conditional_blocks(ctx);
    if conditional.is_empty() {
        None
    } else {
        Some(conditional)
    }
}

/// Build the complete system prompt for an agent.
///
/// Structure: agent system_prompt + "\n\n" + conditional_blocks
pub fn build_system_prompt(agent_def: &AgentDef, ctx: &PromptContext) -> String {
    let mut parts = vec![];

    if !agent_def.system_prompt.is_empty() {
        parts.push(agent_def.system_prompt.clone());
    }

    let conditional = build_conditional_blocks(ctx);
    if !conditional.is_empty() {
        parts.push(conditional);
    }

    parts.join("\n\n")
}

/// Build a PromptContext from agent definition and runtime state.
///
/// Loads `rules.md` from the project root (if available) and `memory.md`
/// from the agent's home directory (`~/.nebflow/agents/<name>/memory.md`).
pub fn build_prompt_context(
    agent_def: &AgentDef,
    depth: u32,
    language: Option<String>,
) -> PromptContext {
    build_prompt_context_with_paths(agent_def, depth, language, None, None)
}

/// Build a PromptContext with explicit path overrides for project root and
/// agent home directory. When paths are provided, rules.md and memory.md
/// are loaded from disk.
pub fn build_prompt_context_with_paths(
    agent_def: &AgentDef,
    depth: u32,
    language: Option<String>,
    project_root: Option<&std::path::Path>,
    agent_home: Option<&std::path::Path>,
) -> PromptContext {
    // Load rules.md from project root
    let rules_md = project_root.and_then(|root| {
        let rules_path = root.join("rules.md");
        std::fs::read_to_string(&rules_path)
            .ok()
            .filter(|s| !s.trim().is_empty())
    });

    // Load memory.md from agent home directory
    let memory_block = agent_home.and_then(|home| {
        let memory_path = home.join("memory.md");
        std::fs::read_to_string(&memory_path)
            .ok()
            .filter(|s| !s.trim().is_empty())
    });

    PromptContext {
        available_tools: agent_def.tools.clone(),
        depth,
        voice_enabled: agent_def.voice_enabled,
        language,
        agent_name: agent_def.name.clone(),
        rules_md,
        memory_block: memory_block.unwrap_or_default(),
        ..Default::default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use nebflow_core::types::{AgentCategory, AgentDef};

    fn test_agent() -> AgentDef {
        AgentDef {
            name: "test".into(),
            description: "test agent".into(),
            tools: vec!["Read".into(), "Write".into()],
            system_prompt: "You are a test agent.".into(),
            avatar: None,
            display_name: None,
            voice_enabled: false,
            model: None,
            category: AgentCategory::Standalone,
            mcp_servers: vec![],
        }
    }

    #[test]
    fn build_system_prompt_basic() {
        let agent = test_agent();
        let ctx = PromptContext::default();
        let prompt = build_system_prompt(&agent, &ctx);
        assert!(prompt.contains("You are a test agent."));
    }

    #[test]
    fn build_system_prompt_with_conditional() {
        let agent = test_agent();
        let ctx = PromptContext {
            language: Some("Chinese".into()),
            ..Default::default()
        };
        let prompt = build_system_prompt(&agent, &ctx);
        assert!(prompt.contains("You are a test agent."));
        assert!(prompt.contains("# Language"));
        assert!(prompt.contains("Respond in Chinese"));
    }

    #[test]
    fn build_system_prompt_empty_system_prompt() {
        let agent = AgentDef {
            system_prompt: "".into(),
            ..test_agent()
        };
        let ctx = PromptContext {
            rules_md: Some("Always test".into()),
            ..Default::default()
        };
        let prompt = build_system_prompt(&agent, &ctx);
        assert!(!prompt.contains("You are a test agent."));
        assert!(prompt.contains("## Project Rules"));
    }

    #[test]
    fn build_prompt_context_with_paths_loads_rules() {
        use std::fs;
        let dir = tempfile::tempdir().unwrap();
        let rules_path = dir.path().join("rules.md");
        fs::write(&rules_path, "# Rules\nBe careful").unwrap();

        let agent = test_agent();
        let ctx = build_prompt_context_with_paths(&agent, 0, None, Some(dir.path()), None);
        assert!(ctx.rules_md.is_some());
        assert!(ctx.rules_md.as_ref().unwrap().contains("Be careful"));
    }

    #[test]
    fn build_prompt_context_with_paths_loads_memory() {
        use std::fs;
        let dir = tempfile::tempdir().unwrap();
        let memory_path = dir.path().join("memory.md");
        fs::write(&memory_path, "Agent remembers this").unwrap();

        let agent = test_agent();
        let ctx = build_prompt_context_with_paths(&agent, 0, None, None, Some(dir.path()));
        assert!(!ctx.memory_block.is_empty());
        assert!(ctx.memory_block.contains("Agent remembers this"));
    }

    #[test]
    fn build_prompt_context_with_paths_no_files() {
        let dir = tempfile::tempdir().unwrap();
        let agent = test_agent();
        let ctx =
            build_prompt_context_with_paths(&agent, 0, None, Some(dir.path()), Some(dir.path()));
        assert!(ctx.rules_md.is_none());
        assert!(ctx.memory_block.is_empty());
    }

    #[test]
    fn build_prompt_context_from_agent() {
        let agent = test_agent();
        let ctx = build_prompt_context(&agent, 0, Some("Chinese".into()));
        assert_eq!(ctx.available_tools, vec!["Read", "Write"]);
        assert!(!ctx.voice_enabled);
        assert_eq!(ctx.agent_name, "test");
        assert_eq!(ctx.language.as_deref(), Some("Chinese"));
    }

    #[test]
    fn build_prompt_context_for_subagent() {
        let agent = test_agent();
        let ctx = build_prompt_context(&agent, 1, None);
        assert_eq!(ctx.depth, 1);
        assert!(ctx.language.is_none());
    }

    #[test]
    fn build_system_stable_returns_prompt() {
        let agent = test_agent();
        let stable = build_system_stable(&agent);
        assert_eq!(stable.as_deref(), Some("You are a test agent."));
    }

    #[test]
    fn build_system_stable_empty_returns_none() {
        let agent = AgentDef {
            system_prompt: "".into(),
            ..test_agent()
        };
        assert!(build_system_stable(&agent).is_none());
    }

    #[test]
    fn build_system_dynamic_empty_context_returns_none() {
        let ctx = PromptContext::default();
        assert!(build_system_dynamic(&ctx).is_none());
    }

    #[test]
    fn build_system_dynamic_with_rules() {
        let ctx = PromptContext {
            rules_md: Some("Always test".into()),
            ..Default::default()
        };
        let dynamic = build_system_dynamic(&ctx).unwrap();
        assert!(dynamic.contains("## Project Rules"));
        assert!(dynamic.contains("Always test"));
    }

    #[test]
    fn build_system_dynamic_with_language() {
        let ctx = PromptContext {
            language: Some("Chinese".into()),
            ..Default::default()
        };
        let dynamic = build_system_dynamic(&ctx).unwrap();
        assert!(dynamic.contains("# Language"));
    }

    #[test]
    fn stable_and_dynamic_match_combined_prompt() {
        let agent = test_agent();
        let ctx = PromptContext {
            language: Some("Chinese".into()),
            rules_md: Some("Always test".into()),
            ..Default::default()
        };
        let combined = build_system_prompt(&agent, &ctx);
        let stable = build_system_stable(&agent).unwrap();
        let dynamic = build_system_dynamic(&ctx).unwrap();
        assert_eq!(combined, format!("{stable}\n\n{dynamic}"));
    }
}
