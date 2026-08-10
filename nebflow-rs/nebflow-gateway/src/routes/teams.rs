//! Teams + flows REST API — mirrors Scala RestApiRoutes team/flow endpoints.
//!
//! GET /api/flows/list — list all flow definitions (not running instances)

use axum::extract::State;
use axum::http::HeaderMap;
use axum::response::Json;
use serde_json::{json, Value};

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

/// GET /api/flows/list — list all flow definitions (not running instances).
///
/// Returns: `{ flows: [{ name, description, nodeCount, entry }] }`
pub async fn list_flows(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Value>, (axum::http::StatusCode, String)> {
    if !check_auth(&state, &headers) {
        return Err((axum::http::StatusCode::UNAUTHORIZED, "Invalid token".into()));
    }
    let data_root = nebflow_core::config::data_root();
    let flows_dir = data_root.join("flows");
    let mut flows: Vec<Value> = vec![];
    if let Ok(entries) = std::fs::read_dir(&flows_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            // Directory format: flows/<name>/flow.json
            if path.is_dir() {
                let flow_json = path.join("flow.json");
                if let Ok(content) = std::fs::read_to_string(&flow_json) {
                    if let Ok(f) =
                        serde_json::from_str::<nebflow_core::entity::FlowDagDef>(&content)
                    {
                        flows.push(json!({
                            "name": f.name,
                            "description": f.description,
                            "entry": f.entry,
                            "nodeCount": f.nodes.len(),
                        }));
                    }
                }
            } else if path.is_file() {
                // Single file format: flows/<name>.json
                if path
                    .file_name()
                    .and_then(|n| n.to_str())
                    .map(|n| n.ends_with(".json"))
                    .unwrap_or(false)
                {
                    if let Ok(content) = std::fs::read_to_string(&path) {
                        if let Ok(f) =
                            serde_json::from_str::<nebflow_core::entity::FlowDagDef>(&content)
                        {
                            flows.push(json!({
                                "name": f.name,
                                "description": f.description,
                                "entry": f.entry,
                                "nodeCount": f.nodes.len(),
                            }));
                        }
                    }
                }
            }
        }
    }
    flows.sort_by(|a, b| {
        a.get("name")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .cmp(b.get("name").and_then(|v| v.as_str()).unwrap_or(""))
    });
    Ok(Json(json!({ "flows": flows })))
}
