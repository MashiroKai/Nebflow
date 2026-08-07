//! Agents REST API — mirrors Scala RestApiRoutes agent endpoints.
//!
//! GET /api/agents              — three-layer aggregation (global + team + flow)
//! GET /api/agents/:name        — single agent detail (system.md + tools)
//! GET /api/agents/manifest.json — public agent manifest for the frontend
//!
//! Agent definitions live on disk:
//!   global: ~/.nebflow/agents/<name>/{agent.json,system.md}
//!   team:   ~/.nebflow/teams/<team>/agents/<name>/{agent.json,system.md}
//!   flow:   ~/.nebflow/flows/<flow>/agents/<name>/{agent.json,system.md}

use std::path::{Path, PathBuf};

use axum::extract::{Path as AxumPath, State};
use axum::http::HeaderMap;
use axum::response::Json;
use serde_json::Value;

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

/// Validate an agent name: only alphanumeric, `-`, `_`, `.` allowed and must
/// not be able to escape the agents directory (mirrors Scala
/// `isValidAgentName`).
fn is_valid_agent_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 128
        && name != "."
        && name != ".."
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.')
}

/// A loaded agent definition: parsed agent.json + system.md content.
#[derive(Debug, Clone)]
struct LoadedAgent {
    json: Value,
    system_prompt: String,
}

/// Load `<dir>/agent.json` + `<dir>/system.md`. Returns None if agent.json is
/// missing or unparseable.
fn load_agent_from_dir(dir: &Path) -> Option<LoadedAgent> {
    let json_path = dir.join("agent.json");
    let raw = std::fs::read_to_string(json_path).ok()?;
    let mut json: Value = serde_json::from_str(&raw).ok()?;
    let system_prompt = std::fs::read_to_string(dir.join("system.md")).unwrap_or_default();
    // If agent.json has no name, fall back to the directory name.
    if json.get("name").is_none() {
        if let Some(dir_name) = dir.file_name().and_then(|n| n.to_str()) {
            json.as_object_mut()?
                .insert("name".into(), Value::String(dir_name.to_string()));
        }
    }
    Some(LoadedAgent {
        json,
        system_prompt,
    })
}

/// List immediate subdirectories of `dir` (sorted for stable output).
fn subdirs(dir: &Path) -> Vec<PathBuf> {
    let mut dirs: Vec<PathBuf> = std::fs::read_dir(dir)
        .map(|rd| {
            rd.filter_map(|e| e.ok())
                .map(|e| e.path())
                .filter(|p| p.is_dir())
                .collect()
        })
        .unwrap_or_default();
    dirs.sort();
    dirs
}

/// Fields common to every layer's list entry.
fn agent_list_fields(agent: &LoadedAgent, layer: &str, scope: Option<&str>) -> Value {
    let j = &agent.json;
    let name = j.get("name").and_then(Value::as_str).unwrap_or("");
    let mut obj = serde_json::json!({
        "name": name,
        "description": j.get("description").and_then(Value::as_str).unwrap_or(""),
        "displayName": j.get("displayName").and_then(Value::as_str).unwrap_or(name),
        "layer": layer,
    });
    let map = obj.as_object_mut().expect("object literal");
    // Scala: category is agent.json's category for global agents; the layer
    // name ("team"/"flow") for scoped agents.
    let category = if layer == "global" {
        j.get("category")
            .and_then(Value::as_str)
            .unwrap_or("standalone")
            .to_string()
    } else {
        layer.to_string()
    };
    map.insert("category".into(), Value::String(category));
    if let Some(scope) = scope {
        map.insert("scope".into(), Value::String(scope.to_string()));
    }
    obj
}

/// Scan all three layers and return `(entries, total_agent_defs)` where
/// entries are the JSON list payloads.
fn scan_all_layers(data_root: &Path) -> Vec<Value> {
    let mut all = Vec::new();

    // Global layer (~/.nebflow/agents/) — Scala excludes "Nebula" from the list.
    for dir in subdirs(&data_root.join("agents")) {
        if let Some(agent) = load_agent_from_dir(&dir) {
            let name = agent
                .json
                .get("name")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_string();
            if name == "Nebula" {
                continue;
            }
            all.push(agent_list_fields(&agent, "global", None));
        }
    }

    // Team layer (~/.nebflow/teams/<team>/agents/).
    for team_dir in subdirs(&data_root.join("teams")) {
        let team_name = team_dir
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or_default()
            .to_string();
        for dir in subdirs(&team_dir.join("agents")) {
            if let Some(agent) = load_agent_from_dir(&dir) {
                all.push(agent_list_fields(&agent, "team", Some(&team_name)));
            }
        }
    }

    // Flow layer (~/.nebflow/flows/<flow>/agents/).
    for flow_dir in subdirs(&data_root.join("flows")) {
        let flow_name = flow_dir
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or_default()
            .to_string();
        for dir in subdirs(&flow_dir.join("agents")) {
            if let Some(agent) = load_agent_from_dir(&dir) {
                all.push(agent_list_fields(&agent, "flow", Some(&flow_name)));
            }
        }
    }

    all
}

/// Find a single agent by name across all layers. Global takes precedence,
/// then teams, then flows (first match wins).
fn find_agent_by_name(data_root: &Path, name: &str) -> Option<LoadedAgent> {
    if !is_valid_agent_name(name) {
        return None;
    }
    let direct = data_root.join("agents").join(name);
    if direct.is_dir() {
        if let Some(agent) = load_agent_from_dir(&direct) {
            return Some(agent);
        }
    }
    for scope_dir in ["teams", "flows"].iter().map(|d| data_root.join(d)) {
        for parent in subdirs(&scope_dir) {
            let dir = parent.join("agents").join(name);
            if dir.is_dir() {
                if let Some(agent) = load_agent_from_dir(&dir) {
                    return Some(agent);
                }
            }
        }
    }
    None
}

/// GET /api/agents — three-layer aggregation.
///
/// Response: `{ agents: [...] }` matching the Scala gateway.
pub async fn list_agents(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Value>, (axum::http::StatusCode, String)> {
    if !check_auth(&state, &headers) {
        return Err((axum::http::StatusCode::UNAUTHORIZED, "Invalid token".into()));
    }
    let agents =
        tokio::task::spawn_blocking(|| scan_all_layers(&nebflow_core::config::data_root()))
            .await
            .map_err(|e| (axum::http::StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(serde_json::json!({ "agents": agents })))
}

/// GET /api/agents/:name — single agent detail.
///
/// Response: `{ name, description, tools, systemPrompt, displayName, model }`
/// matching the Scala gateway.
pub async fn get_agent(
    State(state): State<AppState>,
    headers: HeaderMap,
    AxumPath(name): AxumPath<String>,
) -> Result<Json<Value>, (axum::http::StatusCode, String)> {
    if !check_auth(&state, &headers) {
        return Err((axum::http::StatusCode::UNAUTHORIZED, "Invalid token".into()));
    }
    if !is_valid_agent_name(&name) {
        return Err((
            axum::http::StatusCode::BAD_REQUEST,
            "Invalid agent name".into(),
        ));
    }
    let agent = tokio::task::spawn_blocking({
        let name = name.clone();
        move || find_agent_by_name(&nebflow_core::config::data_root(), &name)
    })
    .await
    .map_err(|e| (axum::http::StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;

    match agent {
        None => Err((
            axum::http::StatusCode::NOT_FOUND,
            format!("Agent '{name}' not found"),
        )),
        Some(loaded) => {
            let j = &loaded.json;
            let name_str = j.get("name").and_then(Value::as_str).unwrap_or(&name);
            Ok(Json(serde_json::json!({
                "name": name_str,
                "description": j.get("description").and_then(Value::as_str).unwrap_or(""),
                "tools": j.get("tools").cloned().unwrap_or(serde_json::json!(["*"])),
                "systemPrompt": loaded.system_prompt,
                "displayName": j.get("displayName").and_then(Value::as_str).unwrap_or(name_str),
                "model": j.get("model").cloned().unwrap_or(Value::Null),
            })))
        }
    }
}

/// GET /api/agents/manifest.json — agent manifest.
///
/// Public (no auth) — used by the frontend before the WS connection exists.
/// Returns lightweight entries for all agents, with the built-in "Nebula"
/// assistant first.
pub async fn manifest(State(_state): State<AppState>, _headers: HeaderMap) -> Json<Value> {
    let agents = tokio::task::spawn_blocking(|| {
        let data_root = nebflow_core::config::data_root();
        let mut entries = vec![serde_json::json!({
            "name": "Nebula",
            "description": "Default assistant",
            "displayName": "Nebula",
            "avatar": Value::Null,
        })];
        for value in scan_all_layers(&data_root) {
            let j = &value;
            entries.push(serde_json::json!({
                "name": j.get("name").cloned().unwrap_or(Value::Null),
                "description": j.get("description").cloned().unwrap_or(Value::String(String::new())),
                "displayName": j.get("displayName").cloned().unwrap_or(Value::Null),
                "avatar": Value::Null,
            }));
        }
        entries
    })
    .await
    .unwrap_or_else(|_| {
        vec![serde_json::json!({
            "name": "Nebula",
            "description": "Default assistant",
            "displayName": "Nebula",
            "avatar": Value::Null,
        })]
    });
    Json(serde_json::json!({ "agents": agents }))
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

    fn make_state() -> AppState {
        let dir = tempdir().unwrap();
        AppState::new(
            Arc::new(Auth::with_token("t".into())),
            Arc::new(RateLimiter::new()),
            Arc::new(SessionStore::new(dir.path().to_path_buf())),
            Arc::new(WsHub::new()),
            "test",
            None,
            None,
            None,
        )
    }

    fn auth_headers() -> HeaderMap {
        let mut h = HeaderMap::new();
        h.insert("authorization", "Bearer t".parse().unwrap());
        h
    }

    fn write_agent(root: &Path, layer_path: &str, name: &str, desc: &str) {
        let dir = root.join(layer_path).join(name);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(
            dir.join("agent.json"),
            serde_json::json!({ "name": name, "description": desc }).to_string(),
        )
        .unwrap();
        std::fs::write(dir.join("system.md"), format!("# {name} prompt")).unwrap();
    }

    #[test]
    fn valid_agent_names() {
        assert!(is_valid_agent_name("Frontend"));
        assert!(is_valid_agent_name("my-agent_1.2"));
        assert!(!is_valid_agent_name(""));
        assert!(!is_valid_agent_name(".."));
        assert!(!is_valid_agent_name("../etc"));
        assert!(!is_valid_agent_name("a/b"));
    }

    #[test]
    fn scan_layers_aggregates() {
        let root = tempdir().unwrap();
        let root = root.path();
        write_agent(root, "agents", "Coder", "global coder");
        write_agent(root, "agents", "Nebula", "excluded from list");
        write_agent(root, "teams/t1/agents", "Backend", "team backend");
        write_agent(root, "flows/f1/agents", "Reviewer", "flow reviewer");

        let all = scan_all_layers(root);
        assert_eq!(all.len(), 3);
        let names: Vec<&str> = all.iter().map(|a| a["name"].as_str().unwrap()).collect();
        assert!(names.contains(&"Coder"));
        assert!(!names.contains(&"Nebula"));
        let team_entry = all.iter().find(|a| a["name"] == "Backend").unwrap();
        assert_eq!(team_entry["layer"], "team");
        assert_eq!(team_entry["scope"], "t1");
        let flow_entry = all.iter().find(|a| a["name"] == "Reviewer").unwrap();
        assert_eq!(flow_entry["layer"], "flow");
        assert_eq!(flow_entry["scope"], "f1");
    }

    #[test]
    fn find_agent_searches_all_layers() {
        let root = tempdir().unwrap();
        let root = root.path();
        write_agent(root, "teams/t1/agents", "Backend", "team backend");
        let found = find_agent_by_name(root, "Backend").unwrap();
        assert_eq!(found.system_prompt, "# Backend prompt");
        assert!(find_agent_by_name(root, "missing").is_none());
        assert!(find_agent_by_name(root, "../x").is_none());
    }

    #[tokio::test]
    async fn list_agents_no_auth() {
        let state = make_state();
        let result = list_agents(State(state), HeaderMap::new()).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn list_agents_with_auth_returns_object() {
        let state = make_state();
        let result = list_agents(State(state), auth_headers()).await;
        assert!(result.is_ok());
        let body = result.unwrap().0;
        assert!(body["agents"].is_array());
    }

    #[tokio::test]
    async fn get_agent_invalid_name() {
        let state = make_state();
        let result = get_agent(State(state), auth_headers(), AxumPath("../bad".into())).await;
        assert!(result.is_err());
        assert_eq!(result.unwrap_err().0, axum::http::StatusCode::BAD_REQUEST);
    }

    #[tokio::test]
    async fn manifest_is_public() {
        let state = make_state();
        let result = manifest(State(state), HeaderMap::new()).await;
        let json = result.0;
        assert!(json["agents"].is_array());
        assert_eq!(json["agents"][0]["name"], "Nebula");
    }
}
