//! Load tool — loads a flow definition from JSON and validates its structure.
//!
//! The Scala original mounts the flow into the flow engine. The Rust
//! implementation parses + validates the flow JSON (nodes, edges, required
//! fields) and returns a summary; mounting into a runtime engine is not
//! available yet.

use async_trait::async_trait;
use serde_json::{Map, Value};

use crate::types::{ToolContext, ToolError};
use crate::Tool;

pub struct LoadTool {
    schema: Map<String, Value>,
}

impl LoadTool {
    pub fn new() -> Self {
        let schema = serde_json::json!({
            "type": "object",
            "properties": {
                "flow": {
                    "type": "string",
                    "description": "Flow definition as a JSON string"
                }
            },
            "required": ["flow"]
        });
        Self {
            schema: schema.as_object().unwrap().clone(),
        }
    }
}

impl Default for LoadTool {
    fn default() -> Self {
        Self::new()
    }
}

/// Validate a parsed flow JSON value. Returns (name, node_count, edge_count).
fn validate_flow(flow: &Value) -> Result<(String, usize, usize), ToolError> {
    let obj = flow
        .as_object()
        .ok_or_else(|| ToolError::InvalidInput("flow must be a JSON object".into()))?;

    let name = obj
        .get("name")
        .and_then(|v| v.as_str())
        .ok_or_else(|| ToolError::InvalidInput("flow.name is required (string)".into()))?
        .to_string();

    let nodes = obj
        .get("nodes")
        .and_then(|v| v.as_array())
        .ok_or_else(|| ToolError::InvalidInput("flow.nodes is required (array)".into()))?;

    if nodes.is_empty() {
        return Err(ToolError::InvalidInput(
            "flow.nodes must contain at least one node".into(),
        ));
    }

    // Every node needs an id
    let mut node_ids = std::collections::HashSet::new();
    for (i, node) in nodes.iter().enumerate() {
        let id = node.get("id").and_then(|v| v.as_str()).ok_or_else(|| {
            ToolError::InvalidInput(format!("flow.nodes[{}].id is required (string)", i))
        })?;
        if !node_ids.insert(id.to_string()) {
            return Err(ToolError::InvalidInput(format!(
                "duplicate node id: {}",
                id
            )));
        }
    }

    // Edges are optional but must reference known nodes
    let edges: &[Value] = obj
        .get("edges")
        .and_then(|v| v.as_array())
        .map(|a| a.as_slice())
        .unwrap_or(&[]);

    for (i, edge) in edges.iter().enumerate() {
        for field in ["from", "to"] {
            let target = edge.get(field).and_then(|v| v.as_str()).ok_or_else(|| {
                ToolError::InvalidInput(format!("flow.edges[{}].{} is required (string)", i, field))
            })?;
            if !node_ids.contains(target) {
                return Err(ToolError::InvalidInput(format!(
                    "flow.edges[{}].{} references unknown node: {}",
                    i, field, target
                )));
            }
        }
    }

    Ok((name, nodes.len(), edges.len()))
}

#[async_trait]
impl Tool for LoadTool {
    fn name(&self) -> &str {
        "Load"
    }

    fn description(&self) -> &str {
        "Loads and validates a flow definition (JSON). Returns a summary of \
         the flow structure. Mounting into a live flow engine is not available \
         in the Rust backend yet."
    }

    fn input_schema(&self) -> &Map<String, Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &Map<String, Value>,
        _ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let flow_str = input.get("flow").and_then(|v| v.as_str()).unwrap_or("");

        if flow_str.is_empty() {
            return Err(ToolError::InvalidInput("flow is required".into()));
        }

        let parsed: Value = serde_json::from_str(flow_str)
            .map_err(|e| ToolError::InvalidInput(format!("flow is not valid JSON: {}", e)))?;

        let (name, node_count, edge_count) = validate_flow(&parsed)?;

        Ok(format!(
            "Flow \"{}\" validated: {} node(s), {} edge(s). \
             Note: flow mounting into a live engine is not available in the Rust backend yet.",
            name, node_count, edge_count
        ))
    }

    fn summarize(&self, input: &Map<String, Value>) -> String {
        let flow_str = input.get("flow").and_then(|v| v.as_str()).unwrap_or("");
        let name = serde_json::from_str::<Value>(flow_str)
            .ok()
            .and_then(|v| v.get("name").and_then(|n| n.as_str()).map(String::from))
            .unwrap_or_else(|| "?".into());
        format!("Load(flow: {})", name)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx() -> ToolContext {
        ToolContext {
            session_id: "test".into(),
            agent_id: "test".into(),
            working_directory: "/tmp".into(),
            depth: 0,
            ..Default::default()
        }
    }

    fn input_with(flow: &str) -> Map<String, Value> {
        let mut m = Map::new();
        m.insert("flow".into(), Value::String(flow.into()));
        m
    }

    #[tokio::test]
    async fn valid_flow() {
        let flow = r#"{
            "name": "demo",
            "nodes": [{"id": "a"}, {"id": "b"}],
            "edges": [{"from": "a", "to": "b"}]
        }"#;
        let tool = LoadTool::new();
        let result = tool.call(&input_with(flow), &ctx()).await.unwrap();
        assert!(result.contains("demo"));
        assert!(result.contains("2 node(s)"));
        assert!(result.contains("1 edge(s)"));
    }

    #[tokio::test]
    async fn invalid_json_rejected() {
        let tool = LoadTool::new();
        let result = tool.call(&input_with("{not json"), &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }

    #[tokio::test]
    async fn missing_nodes_rejected() {
        let tool = LoadTool::new();
        let result = tool.call(&input_with(r#"{"name": "x"}"#), &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }

    #[tokio::test]
    async fn edge_to_unknown_node_rejected() {
        let flow = r#"{
            "name": "bad",
            "nodes": [{"id": "a"}],
            "edges": [{"from": "a", "to": "ghost"}]
        }"#;
        let tool = LoadTool::new();
        let result = tool.call(&input_with(flow), &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }
}
