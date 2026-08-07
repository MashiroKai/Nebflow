//! Skills REST API.
//! GET /api/skills — list available skills from all sources.
//!
//! Mirrors Scala SkillService: three sources (user / project / legacy commands),
//! deduplicated by name with first occurrence winning (user > project > commands).

use std::collections::HashSet;
use std::path::{Path, PathBuf};

use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::response::Json;
use serde::Serialize;

use crate::routes::AppState;

fn check_auth(state: &AppState, headers: &HeaderMap) -> bool {
    let token = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .filter(|s| s.starts_with("Bearer "))
        .map(|s| s[7..].to_string())
        .unwrap_or_default();
    state.auth.validate(&token)
}

/// Skill metadata — JSON field names match Scala SkillInfo encoder.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SkillInfo {
    pub name: String,
    pub description: String,
    pub file_path: String,
    /// Scala encoder emits explicit `null` for absent optionals — match that.
    pub when_to_use: Option<String>,
    pub allowed_tools: Vec<String>,
    pub argument_hint: Option<String>,
    pub argument_names: Vec<String>,
    pub user_invocable: bool,
    pub model_invocable: bool,
    pub version: Option<String>,
    /// Where this skill was loaded from: "user" | "project" | "commands".
    pub source: String,
}

/// GET /api/skills — list all skills (user + project + legacy commands).
pub async fn list_skills(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Vec<SkillInfo>>, (StatusCode, String)> {
    if !check_auth(&state, &headers) {
        return Err((StatusCode::UNAUTHORIZED, "Invalid token".into()));
    }
    let cwd = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    let data_root = nebflow_core::config::data_root();
    let skills = tokio::task::spawn_blocking(move || scan_all_skills(&data_root, &cwd))
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(skills))
}

/// Scan all skill sources and deduplicate by name (first occurrence wins).
pub fn scan_all_skills(data_root: &Path, cwd: &Path) -> Vec<SkillInfo> {
    let mut all = Vec::new();
    // User-level: <data_root>/skills/<name>/SKILL.md
    all.extend(load_from_skills_dir(&data_root.join("skills"), "user"));
    // Project-level
    all.extend(load_from_skills_dir(
        &cwd.join(".nebflow/skills"),
        "project",
    ));
    all.extend(load_from_skills_dir(&cwd.join(".claude/skills"), "project"));
    // Legacy commands
    all.extend(load_from_commands_dir(&cwd.join(".nebflow/commands")));
    all.extend(load_from_commands_dir(&cwd.join(".claude/commands")));

    let mut seen = HashSet::new();
    all.retain(|s| seen.insert(s.name.clone()));
    all
}

/// Load skills from a directory of `<name>/SKILL.md` (or `skill.md`) subdirectories.
fn load_from_skills_dir(dir: &Path, source: &str) -> Vec<SkillInfo> {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return Vec::new();
    };
    let mut skills: Vec<SkillInfo> = entries
        .flatten()
        .filter_map(|e| {
            let sub = e.path();
            if !sub.is_dir() || sub.file_name()?.to_str()? == "_example" {
                return None;
            }
            let skill_file = resolve_skill_file(&sub)?;
            let dir_name = sub.file_name()?.to_str()?.to_string();
            Some(parse_skill_file(&skill_file, source, Some(&dir_name)))
        })
        .collect();
    skills.sort_by(|a, b| a.name.cmp(&b.name));
    skills
}

/// Priority: SKILL.md > skill.md (Claude Code vs Nebflow convention).
fn resolve_skill_file(dir: &Path) -> Option<PathBuf> {
    let upper = dir.join("SKILL.md");
    if upper.is_file() {
        Some(upper)
    } else {
        let lower = dir.join("skill.md");
        lower.is_file().then_some(lower)
    }
}

/// Load legacy command skills: single `.md` files, name = filename without extension.
fn load_from_commands_dir(dir: &Path) -> Vec<SkillInfo> {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return Vec::new();
    };
    let mut skills: Vec<SkillInfo> = entries
        .flatten()
        .filter_map(|e| {
            let f = e.path();
            if !f.is_file() || f.extension()?.to_str()? != "md" {
                return None;
            }
            let name = f.file_stem()?.to_str()?.to_string();
            Some(parse_skill_file(&f, "commands", Some(&name)))
        })
        .collect();
    skills.sort_by(|a, b| a.name.cmp(&b.name));
    skills
}

/// Parse a skill markdown file's YAML frontmatter into a SkillInfo.
/// Frontmatter parsing is line-based (no YAML dependency), matching the Scala implementation.
pub fn parse_skill_file(file_path: &Path, source: &str, name_override: Option<&str>) -> SkillInfo {
    let content = std::fs::read_to_string(file_path).unwrap_or_default();
    let fm = extract_frontmatter(&content);

    let name = extract_field(fm, "name")
        .or_else(|| name_override.map(|s| s.to_string()))
        .unwrap_or_else(|| {
            file_path
                .file_stem()
                .and_then(|s| s.to_str())
                .unwrap_or("unknown")
                .to_string()
        });

    let user_invocable = extract_field(fm, "user-invocable")
        .map(|v| v.eq_ignore_ascii_case("true"))
        .unwrap_or(true);
    let model_invocable = extract_field(fm, "disable-model-invocation")
        .map(|v| !v.eq_ignore_ascii_case("true"))
        .unwrap_or(true);

    SkillInfo {
        name,
        description: extract_field(fm, "description").unwrap_or_default(),
        file_path: file_path.to_string_lossy().to_string(),
        when_to_use: extract_field(fm, "when_to_use").or_else(|| extract_field(fm, "when-to-use")),
        allowed_tools: extract_list_field(fm, "allowed-tools"),
        argument_hint: extract_field(fm, "argument-hint"),
        argument_names: extract_list_field(fm, "arguments"),
        user_invocable,
        model_invocable,
        version: extract_field(fm, "version"),
        source: source.to_string(),
    }
}

/// Extract the YAML frontmatter block between the leading `---` markers.
fn extract_frontmatter(content: &str) -> &str {
    let trimmed = content.trim_start();
    if !trimmed.starts_with("---") {
        return "";
    }
    let rest = &trimmed[3..];
    match rest.find("---") {
        Some(end) => rest[..end].trim(),
        None => "",
    }
}

/// Extract a scalar frontmatter field (`field: value`), stripping surrounding quotes.
fn extract_field(frontmatter: &str, field: &str) -> Option<String> {
    frontmatter
        .lines()
        .map(str::trim)
        .find(|line| {
            line.starts_with(&format!("{field}:")) || line.starts_with(&format!("{field} :"))
        })
        .and_then(|line| {
            let idx = line.find(':')?;
            let value = line[idx + 1..]
                .trim()
                .trim_matches('"')
                .trim_matches('\'')
                .to_string();
            (!value.is_empty()).then_some(value)
        })
}

/// Parse a list field: inline comma-separated (`field: a, b`) or YAML block list
/// (`field:` followed by `- item` lines).
fn extract_list_field(frontmatter: &str, field: &str) -> Vec<String> {
    if let Some(inline) = extract_field(frontmatter, field) {
        return inline
            .split(',')
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string)
            .collect();
    }
    let lines: Vec<&str> = frontmatter.lines().collect();
    let start = lines.iter().position(|l| {
        let t = l.trim();
        t.starts_with(&format!("{field}:")) || t.starts_with(&format!("{field} :"))
    });
    match start {
        Some(i) => lines[i + 1..]
            .iter()
            .map(|l| l.trim())
            .take_while(|l| l.starts_with("- "))
            .map(|l| l.trim_start_matches("- ").trim().to_string())
            .filter(|s| !s.is_empty())
            .collect(),
        None => Vec::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::Auth;
    use crate::ratelimit::RateLimiter;
    use crate::session::SessionStore;
    use crate::ws_hub::WsHub;
    use std::sync::Arc;
    use tempfile::tempdir;

    fn make_state(dir: &Path) -> AppState {
        AppState::new(
            Arc::new(Auth::with_token("t".into())),
            Arc::new(RateLimiter::new()),
            Arc::new(SessionStore::new(dir.to_path_buf())),
            Arc::new(WsHub::new()),
            "test",
            None,
            None,
            None,
        )
    }

    fn auth_headers() -> HeaderMap {
        let mut headers = HeaderMap::new();
        headers.insert("authorization", "Bearer t".parse().unwrap());
        headers
    }

    #[tokio::test]
    async fn list_skills_no_auth() {
        let dir = tempdir().unwrap();
        let result = list_skills(State(make_state(dir.path())), HeaderMap::new()).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn list_skills_with_auth_returns_vec() {
        let dir = tempdir().unwrap();
        let result = list_skills(State(make_state(dir.path())), auth_headers()).await;
        assert!(result.is_ok());
    }

    #[test]
    fn parses_full_frontmatter() {
        let dir = tempdir().unwrap();
        let skill_dir = dir.path().join("my-skill");
        std::fs::create_dir_all(&skill_dir).unwrap();
        std::fs::write(
            skill_dir.join("SKILL.md"),
            "---\nname: my-skill\ndescription: Does things\nversion: 1.2.3\n\
             when_to_use: When testing\nuser-invocable: false\n\
             disable-model-invocation: true\nargument-hint: <file>\n\
             allowed-tools: Read, Grep, Bash\n---\n\n# Body\n",
        )
        .unwrap();
        let info = parse_skill_file(&skill_dir.join("SKILL.md"), "user", Some("my-skill"));
        assert_eq!(info.name, "my-skill");
        assert_eq!(info.description, "Does things");
        assert_eq!(info.version.as_deref(), Some("1.2.3"));
        assert_eq!(info.when_to_use.as_deref(), Some("When testing"));
        assert!(!info.user_invocable);
        assert!(!info.model_invocable);
        assert_eq!(info.argument_hint.as_deref(), Some("<file>"));
        assert_eq!(info.allowed_tools, vec!["Read", "Grep", "Bash"]);
        assert_eq!(info.source, "user");
    }

    #[test]
    fn parses_yaml_block_list_arguments() {
        let dir = tempdir().unwrap();
        let skill_dir = dir.path().join("args-skill");
        std::fs::create_dir_all(&skill_dir).unwrap();
        std::fs::write(
            skill_dir.join("SKILL.md"),
            "---\nname: args-skill\ndescription: d\narguments:\n  - first\n  - second\n---\n",
        )
        .unwrap();
        let info = parse_skill_file(&skill_dir.join("SKILL.md"), "project", None);
        assert_eq!(info.argument_names, vec!["first", "second"]);
        // defaults
        assert!(info.user_invocable);
        assert!(info.model_invocable);
    }

    #[test]
    fn missing_frontmatter_uses_dir_name_and_defaults() {
        let dir = tempdir().unwrap();
        let skill_dir = dir.path().join("plain");
        std::fs::create_dir_all(&skill_dir).unwrap();
        std::fs::write(skill_dir.join("skill.md"), "# No frontmatter\n").unwrap();
        let info = parse_skill_file(&skill_dir.join("skill.md"), "user", Some("plain"));
        assert_eq!(info.name, "plain");
        assert_eq!(info.description, "");
        assert!(info.allowed_tools.is_empty());
    }

    #[test]
    fn scan_dedupes_user_over_project() {
        let dir = tempdir().unwrap();
        let data_root = dir.path().join("data");
        let cwd = dir.path().join("cwd");
        for base in [&data_root.join("skills"), &cwd.join(".nebflow/skills")] {
            let skill_dir = base.join("dup");
            std::fs::create_dir_all(&skill_dir).unwrap();
            std::fs::write(
                skill_dir.join("SKILL.md"),
                "---\nname: dup\ndescription: from somewhere\n---\n",
            )
            .unwrap();
        }
        let skills = scan_all_skills(&data_root, &cwd);
        let dups: Vec<_> = skills.iter().filter(|s| s.name == "dup").collect();
        assert_eq!(dups.len(), 1);
        assert_eq!(dups[0].source, "user");
    }

    #[test]
    fn scan_loads_legacy_commands() {
        let dir = tempdir().unwrap();
        let commands = dir.path().join("cwd/.nebflow/commands");
        std::fs::create_dir_all(&commands).unwrap();
        std::fs::write(
            commands.join("deploy.md"),
            "---\ndescription: Deploy it\n---\n",
        )
        .unwrap();
        let skills = scan_all_skills(&dir.path().join("data"), &dir.path().join("cwd"));
        let cmd = skills.iter().find(|s| s.name == "deploy").unwrap();
        assert_eq!(cmd.source, "commands");
        assert_eq!(cmd.description, "Deploy it");
    }

    #[test]
    fn skill_info_json_field_names_match_scala() {
        let info = SkillInfo {
            name: "s".into(),
            description: "d".into(),
            file_path: "/p".into(),
            when_to_use: None,
            allowed_tools: vec![],
            argument_hint: None,
            argument_names: vec![],
            user_invocable: true,
            model_invocable: true,
            version: None,
            source: "user".into(),
        };
        let json = serde_json::to_value(&info).unwrap();
        for key in [
            "name",
            "description",
            "filePath",
            "whenToUse",
            "allowedTools",
            "argumentHint",
            "argumentNames",
            "userInvocable",
            "modelInvocable",
            "version",
            "source",
        ] {
            assert!(json.get(key).is_some(), "missing field {key}");
        }
    }
}
