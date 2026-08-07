//! Conditional prompt sections — mirrors Scala PromptSections.

/// Runtime state that determines which sections are included.
#[derive(Debug, Clone, Default)]
pub struct PromptContext {
    pub available_tools: Vec<String>,
    pub depth: u32,
    pub voice_enabled: bool,
    pub has_devices: bool,
    pub has_active_sessions: bool,
    pub language: Option<String>,
    pub chat_width: u32,
    pub agent_category: String,
    pub agent_name: String,
    pub env_info: String,
    pub device_info: String,
    pub skill_catalog: String,
    pub team_catalog: String,
    pub memory_block: String,
    pub agent_sessions_text: String,
    pub task_list_text: String,
    pub rules_md: Option<String>,
}

/// A single section of the system prompt with a condition and ordering.
#[derive(Clone)]
pub struct PromptSection {
    pub order: u32,
    pub should_include: fn(&PromptContext) -> bool,
    pub render: fn(&PromptContext) -> String,
}

// Condition helpers
fn cond_devices(ctx: &PromptContext) -> bool {
    ctx.has_devices
}
fn cond_sessions(ctx: &PromptContext) -> bool {
    ctx.has_active_sessions
}
fn cond_language(ctx: &PromptContext) -> bool {
    ctx.language.is_some()
}
fn cond_tasks(ctx: &PromptContext) -> bool {
    !ctx.task_list_text.is_empty()
}
fn cond_skills(ctx: &PromptContext) -> bool {
    !ctx.skill_catalog.is_empty()
}
fn cond_team(ctx: &PromptContext) -> bool {
    !ctx.team_catalog.is_empty()
}
fn cond_memory(ctx: &PromptContext) -> bool {
    !ctx.memory_block.is_empty()
}
fn cond_rules(ctx: &PromptContext) -> bool {
    ctx.rules_md.is_some()
}

// Render helpers
fn render_devices(ctx: &PromptContext) -> String {
    format!("# Devices\n\n{}", ctx.device_info)
}
fn render_sessions(ctx: &PromptContext) -> String {
    ctx.agent_sessions_text.clone()
}
fn render_language(ctx: &PromptContext) -> String {
    let lang = ctx.language.as_deref().unwrap_or("");
    format!("# Language\n- Respond in {lang}.\n- All user-visible text must be in {lang}.")
}
fn render_tasks(ctx: &PromptContext) -> String {
    ctx.task_list_text.clone()
}
fn render_skills(ctx: &PromptContext) -> String {
    ctx.skill_catalog.clone()
}
fn render_team(ctx: &PromptContext) -> String {
    ctx.team_catalog.clone()
}
fn render_memory(ctx: &PromptContext) -> String {
    ctx.memory_block.clone()
}
fn render_rules(ctx: &PromptContext) -> String {
    format!(
        "## Project Rules\n\n{}",
        ctx.rules_md.as_deref().unwrap_or("")
    )
}

fn dynamic_sections() -> Vec<PromptSection> {
    vec![
        PromptSection {
            order: 600,
            should_include: cond_devices,
            render: render_devices,
        },
        PromptSection {
            order: 610,
            should_include: cond_sessions,
            render: render_sessions,
        },
        PromptSection {
            order: 620,
            should_include: cond_language,
            render: render_language,
        },
        PromptSection {
            order: 630,
            should_include: cond_tasks,
            render: render_tasks,
        },
        PromptSection {
            order: 800,
            should_include: cond_skills,
            render: render_skills,
        },
        PromptSection {
            order: 816,
            should_include: cond_team,
            render: render_team,
        },
        PromptSection {
            order: 810,
            should_include: cond_memory,
            render: render_memory,
        },
        PromptSection {
            order: 900,
            should_include: cond_rules,
            render: render_rules,
        },
    ]
}

/// Build the conditional blocks string from the registry.
pub fn build_conditional_blocks(ctx: &PromptContext) -> String {
    let sections = dynamic_sections();
    let mut filtered: Vec<&PromptSection> = sections
        .iter()
        .filter(|s| (s.should_include)(ctx))
        .collect();
    filtered.sort_by_key(|s| s.order);
    filtered
        .iter()
        .map(|s| (s.render)(ctx))
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join("\n\n")
}

/// Remove a `## Section` block from a prompt string.
pub fn strip_section(prompt: &str, header: &str) -> String {
    let marker = format!("## {header}");
    prompt
        .split("\n## ")
        .filter(|s| !s.trim().starts_with(&marker[3..]))
        .collect::<Vec<_>>()
        .join("\n## ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_context_produces_empty_blocks() {
        assert!(build_conditional_blocks(&PromptContext::default()).is_empty());
    }

    #[test]
    fn language_section_included_when_set() {
        let ctx = PromptContext {
            language: Some("Chinese".into()),
            ..Default::default()
        };
        let blocks = build_conditional_blocks(&ctx);
        assert!(blocks.contains("# Language"));
        assert!(blocks.contains("Respond in Chinese"));
    }

    #[test]
    fn devices_section_included_when_has_devices() {
        let ctx = PromptContext {
            has_devices: true,
            device_info: "MacBook".into(),
            ..Default::default()
        };
        let blocks = build_conditional_blocks(&ctx);
        assert!(blocks.contains("# Devices"));
        assert!(blocks.contains("MacBook"));
    }

    #[test]
    fn devices_section_excluded_when_no_devices() {
        let ctx = PromptContext {
            has_devices: false,
            ..Default::default()
        };
        assert!(!build_conditional_blocks(&ctx).contains("# Devices"));
    }

    #[test]
    fn sessions_section_included() {
        let ctx = PromptContext {
            has_active_sessions: true,
            agent_sessions_text: "Active: Backend".into(),
            ..Default::default()
        };
        assert!(build_conditional_blocks(&ctx).contains("Active: Backend"));
    }

    #[test]
    fn skills_section_included() {
        let ctx = PromptContext {
            skill_catalog: "# Skills".into(),
            ..Default::default()
        };
        assert!(build_conditional_blocks(&ctx).contains("# Skills"));
    }

    #[test]
    fn rules_section_included() {
        let ctx = PromptContext {
            rules_md: Some("Always test".into()),
            ..Default::default()
        };
        let blocks = build_conditional_blocks(&ctx);
        assert!(blocks.contains("## Project Rules"));
        assert!(blocks.contains("Always test"));
    }

    #[test]
    fn sections_ordered_by_order_value() {
        let ctx = PromptContext {
            has_devices: true,
            device_info: "D".into(),
            language: Some("Chinese".into()),
            rules_md: Some("R".into()),
            ..Default::default()
        };
        let blocks = build_conditional_blocks(&ctx);
        let dp = blocks.find("# Devices").unwrap();
        let lp = blocks.find("# Language").unwrap();
        let rp = blocks.find("## Project Rules").unwrap();
        assert!(dp < lp && lp < rp);
    }

    #[test]
    fn memory_and_team_sections() {
        let ctx = PromptContext {
            memory_block: "# Memory".into(),
            team_catalog: "# Team".into(),
            ..Default::default()
        };
        let blocks = build_conditional_blocks(&ctx);
        assert!(blocks.contains("# Memory"));
        assert!(blocks.contains("# Team"));
    }

    #[test]
    fn strip_section_removes_matching_header() {
        let prompt = "# Title\n\n## Voice Output\n\nContent\n\n## Other\n\nMore";
        let stripped = strip_section(prompt, "Voice Output");
        assert!(!stripped.contains("Voice Output"));
        assert!(stripped.contains("## Other"));
    }
}
