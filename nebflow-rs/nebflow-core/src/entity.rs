//! Entity types — AgentEntry, TeamDef, FlowDagDef, etc.
//! Mirrors nebflow.core.entity.EntityTypes and nebflow.core.flow.FlowTreeTypes from Scala.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

use crate::types::AgentModelConfig;

// ============================================================
// Agent (global Agent library entry — corresponds to agent.json)
// ============================================================

/// Runtime representation of agent.json.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentEntry {
    pub name: String,
    pub description: String,
    #[serde(default)]
    pub use_when: String,
    #[serde(default = "default_tools")]
    pub tools: Vec<String>,
    #[serde(default)]
    pub voice: bool,
    /// Loaded from system.md, not in agent.json.
    #[serde(default)]
    pub system_prompt: String,
    #[serde(default = "default_category")]
    pub category: String,
    #[serde(default)]
    pub mcp_servers: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<AgentModelConfig>,
}

fn default_tools() -> Vec<String> {
    vec!["*".into()]
}

fn default_category() -> String {
    "standalone".into()
}

// ============================================================
// Team (corresponds to team.json)
// ============================================================

/// Team definition. Corresponds to ~/.nebflow/teams/<name>/team.json
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TeamDef {
    pub name: String,
    pub description: String,
    pub lead: String,
    #[serde(default)]
    pub members: Vec<String>,
    #[serde(default)]
    pub flows: Vec<String>,
}

// ============================================================
// Flow DAG (corresponds to flow.json)
// ============================================================

/// Routing target after a node completes.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum NodeRoute {
    /// Route to a specific node (string form: "reviewer").
    Goto(String),
    /// Conditional branch.
    Switch {
        switch: String,
        cases: HashMap<String, NodeRouteValue>,
    },
    /// Terminate the flow, return result (string "$return").
    Return,
}

/// Helper for recursive NodeRoute serde — since `untagged` enums with
/// recursion are tricky, we use a separate type for the cases map values.
///
/// Serialization: `Goto("reviewer")` -> `"reviewer"`, `Return` -> `"$return"`.
/// Deserialization: a string is parsed as Goto, unless it's `"$return"`.
///
/// Also supports `Switch` variant for conditional branching:
/// `{"switch": "$reviewer.verdict", "cases": {"pass": "next", "fail": "fix"}}`
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum NodeRouteValue {
    Goto(String),
    Return,
    /// Conditional branch: evaluate `switch_expr` against node output/verdict,
    /// route to the matching case target.
    Switch {
        switch_expr: String,
        cases: HashMap<String, String>,
    },
}

impl NodeRouteValue {
    /// Check if a string value represents the Return route.
    fn is_return(s: &str) -> bool {
        s == "$return"
    }
}

/// Custom deserialization: treat "$return" as Return, a string as Goto,
/// or an object with "switch" and "cases" as Switch.
impl<'de> Deserialize<'de> for NodeRouteValue {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        let value = serde_json::Value::deserialize(deserializer)?;
        match value {
            serde_json::Value::String(s) => {
                if Self::is_return(&s) {
                    Ok(NodeRouteValue::Return)
                } else {
                    Ok(NodeRouteValue::Goto(s))
                }
            }
            serde_json::Value::Object(obj) => {
                let switch_expr = obj
                    .get("switch")
                    .and_then(|v| v.as_str())
                    .ok_or_else(|| serde::de::Error::missing_field("switch"))?
                    .to_string();
                let cases_val = obj
                    .get("cases")
                    .ok_or_else(|| serde::de::Error::missing_field("cases"))?;
                let cases = cases_val
                    .as_object()
                    .ok_or_else(|| serde::de::Error::custom("'cases' must be an object"))?;
                let mut case_map = HashMap::new();
                for (key, val) in cases {
                    let target = val
                        .as_str()
                        .ok_or_else(|| serde::de::Error::custom("case values must be strings"))?;
                    case_map.insert(key.clone(), target.to_string());
                }
                Ok(NodeRouteValue::Switch {
                    switch_expr,
                    cases: case_map,
                })
            }
            _ => Err(serde::de::Error::custom(
                "onComplete must be a string or a switch object",
            )),
        }
    }
}

/// Custom serialization: Return -> "$return", Goto(s) -> s,
/// Switch { switch_expr, cases } -> {"switch": switch_expr, "cases": {...}}.
impl Serialize for NodeRouteValue {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        use serde::ser::SerializeMap;
        match self {
            NodeRouteValue::Return => serializer.serialize_str("$return"),
            NodeRouteValue::Goto(s) => serializer.serialize_str(s),
            NodeRouteValue::Switch { switch_expr, cases } => {
                let mut map = serializer.serialize_map(Some(2))?;
                map.serialize_entry("switch", switch_expr)?;
                let case_map: HashMap<&str, &str> = cases
                    .iter()
                    .map(|(k, v)| (k.as_str(), v.as_str()))
                    .collect();
                map.serialize_entry("cases", &case_map)?;
                map.end()
            }
        }
    }
}

/// Error handling strategy.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum OnError {
    #[serde(rename = "resume")]
    Resume,
    #[serde(rename = "restart")]
    Restart,
    #[serde(rename = "stop")]
    Stop,
}

/// A single node in the flow DAG.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FlowNode {
    pub agent: String,
    pub input: String,
    pub on_complete: NodeRouteValue,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub on_error: Option<OnError>,
    #[serde(default)]
    pub max_retries: u32,
}

/// Flow DAG definition. Corresponds to ~/.nebflow/flows/<name>.json
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FlowDagDef {
    pub name: String,
    pub description: String,
    pub nodes: HashMap<String, FlowNode>,
    pub entry: String,
    #[serde(default = "default_max_loop")]
    pub max_loop: u32,
}

fn default_max_loop() -> u32 {
    10
}

// ============================================================
// Runtime state (internal to DAG executor)
// ============================================================

/// Execution result of a single node.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NodeResult {
    pub node_id: String,
    pub output: String,
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(default = "default_attempt_count")]
    pub attempt_count: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub verdict: Option<String>,
}

fn default_attempt_count() -> u32 {
    1
}

/// Runtime context for a flow execution.
#[derive(Debug, Clone, Default)]
pub struct FlowExecContext {
    pub flow_name: String,
    pub task_input: String,
    /// nodeId -> output
    pub node_outputs: HashMap<String, String>,
    /// edge -> traversal count
    pub loop_counts: HashMap<String, u32>,
    pub total_loops: u32,
}

// ============================================================
// Flow Tree types (from FlowTreeTypes.scala)
// ============================================================

/// Result of mounting/unmounting a flow or team.
#[derive(Debug, Clone)]
pub enum MountResult {
    Mounted { name: String, address: String },
    Unmounted { name: String },
    Error { message: String },
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn agent_entry_serde() {
        let entry = AgentEntry {
            name: "test".into(),
            description: "test agent".into(),
            use_when: "testing".into(),
            tools: vec!["Read".into(), "Write".into()],
            voice: false,
            system_prompt: "".into(),
            category: "standalone".into(),
            mcp_servers: vec![],
            model: None,
        };
        let json = serde_json::to_string(&entry).unwrap();
        let back: AgentEntry = serde_json::from_str(&json).unwrap();
        assert_eq!(back.name, "test");
        assert_eq!(back.tools.len(), 2);
    }

    #[test]
    fn agent_entry_defaults() {
        let json = r#"{"name":"test","description":"test"}"#;
        let entry: AgentEntry = serde_json::from_str(json).unwrap();
        assert_eq!(entry.tools, vec!["*".to_string()]);
        assert!(!entry.voice);
        assert_eq!(entry.category, "standalone");
    }

    #[test]
    fn team_def_serde() {
        let team = TeamDef {
            name: "nebflow-project".into(),
            description: "main team".into(),
            lead: "Manager".into(),
            members: vec!["Backend".into(), "Frontend".into()],
            flows: vec!["code-review".into()],
        };
        let json = serde_json::to_string(&team).unwrap();
        let back: TeamDef = serde_json::from_str(&json).unwrap();
        assert_eq!(back.lead, "Manager");
        assert_eq!(back.members.len(), 2);
    }

    #[test]
    fn team_def_defaults() {
        let json = r#"{"name":"t","description":"d","lead":"L"}"#;
        let team: TeamDef = serde_json::from_str(json).unwrap();
        assert!(team.members.is_empty());
        assert!(team.flows.is_empty());
    }

    #[test]
    fn on_error_serde() {
        assert_eq!(
            serde_json::to_string(&OnError::Resume).unwrap(),
            r#""resume""#
        );
        assert_eq!(
            serde_json::to_string(&OnError::Restart).unwrap(),
            r#""restart""#
        );
        assert_eq!(serde_json::to_string(&OnError::Stop).unwrap(), r#""stop""#);
    }

    #[test]
    fn flow_dag_def_serde() {
        let json = r#"{
            "name": "review",
            "description": "code review flow",
            "nodes": {
                "scanner": {
                    "agent": "Explorer",
                    "input": "$task",
                    "onComplete": "reviewer",
                    "maxRetries": 0
                },
                "reviewer": {
                    "agent": "Reviewer",
                    "input": "$scanner.output",
                    "onComplete": "$return",
                    "maxRetries": 1
                }
            },
            "entry": "scanner",
            "maxLoop": 5
        }"#;
        let dag: FlowDagDef = serde_json::from_str(json).unwrap();
        assert_eq!(dag.name, "review");
        assert_eq!(dag.entry, "scanner");
        assert_eq!(dag.nodes.len(), 2);
        assert_eq!(dag.max_loop, 5);
        let scanner = &dag.nodes["scanner"];
        assert_eq!(scanner.agent, "Explorer");
        assert_eq!(scanner.on_complete, NodeRouteValue::Goto("reviewer".into()));
        let reviewer = &dag.nodes["reviewer"];
        assert_eq!(reviewer.on_complete, NodeRouteValue::Return);
        assert_eq!(reviewer.max_retries, 1);
    }

    #[test]
    fn flow_dag_def_default_max_loop() {
        let json = r#"{"name":"f","description":"d","nodes":{},"entry":"x"}"#;
        let dag: FlowDagDef = serde_json::from_str(json).unwrap();
        assert_eq!(dag.max_loop, 10);
    }

    #[test]
    fn node_result_serde() {
        let result = NodeResult {
            node_id: "scanner".into(),
            output: "scan complete".into(),
            success: true,
            error: None,
            attempt_count: 1,
            verdict: Some("pass".into()),
        };
        let json = serde_json::to_string(&result).unwrap();
        let back: NodeResult = serde_json::from_str(&json).unwrap();
        assert!(back.success);
        assert_eq!(back.verdict.as_deref(), Some("pass"));
    }

    #[test]
    fn node_route_value_goto() {
        let json = r#""reviewer""#;
        let route: NodeRouteValue = serde_json::from_str(json).unwrap();
        assert_eq!(route, NodeRouteValue::Goto("reviewer".into()));
    }

    #[test]
    fn node_route_value_return() {
        let json = r#""$return""#;
        let route: NodeRouteValue = serde_json::from_str(json).unwrap();
        assert_eq!(route, NodeRouteValue::Return);
    }

    #[test]
    fn node_route_value_switch_deserialize() {
        let json = r#"{"switch":"$reviewer.verdict","cases":{"pass":"done","fail":"fix"}}"#;
        let route: NodeRouteValue = serde_json::from_str(json).unwrap();
        match route {
            NodeRouteValue::Switch { switch_expr, cases } => {
                assert_eq!(switch_expr, "$reviewer.verdict");
                assert_eq!(cases.get("pass"), Some(&"done".to_string()));
                assert_eq!(cases.get("fail"), Some(&"fix".to_string()));
            }
            _ => panic!("expected Switch variant"),
        }
    }

    #[test]
    fn node_route_value_switch_serialize() {
        let route = NodeRouteValue::Switch {
            switch_expr: "$scanner.verdict".into(),
            cases: HashMap::from([
                ("ok".to_string(), "next".to_string()),
                ("error".to_string(), "$return".to_string()),
            ]),
        };
        let json = serde_json::to_string(&route).unwrap();
        assert!(json.contains("\"switch\":\"$scanner.verdict\""));
        assert!(json.contains("\"cases\""));
        assert!(json.contains("\"ok\":\"next\""));
        assert!(json.contains("\"error\":\"$return\""));
    }

    #[test]
    fn flow_dag_def_with_switch_route() {
        let json = r#"{
            "name": "review",
            "description": "code review with switch",
            "nodes": {
                "scanner": {
                    "agent": "Explorer",
                    "input": "$task",
                    "onComplete": "reviewer",
                    "maxRetries": 0
                },
                "reviewer": {
                    "agent": "Reviewer",
                    "input": "$scanner.output",
                    "onComplete": {
                        "switch": "$reviewer.verdict",
                        "cases": {
                            "pass": "done",
                            "fail": "fixer"
                        }
                    },
                    "maxRetries": 1
                },
                "done": {
                    "agent": "Done",
                    "input": "",
                    "onComplete": "$return",
                    "maxRetries": 0
                },
                "fixer": {
                    "agent": "Coder",
                    "input": "fix it",
                    "onComplete": "$return",
                    "maxRetries": 0
                }
            },
            "entry": "scanner",
            "maxLoop": 5
        }"#;
        let dag: FlowDagDef = serde_json::from_str(json).unwrap();
        assert_eq!(dag.nodes.len(), 4);
        let reviewer = &dag.nodes["reviewer"];
        match &reviewer.on_complete {
            NodeRouteValue::Switch { switch_expr, cases } => {
                assert_eq!(switch_expr, "$reviewer.verdict");
                assert_eq!(cases.get("pass"), Some(&"done".to_string()));
                assert_eq!(cases.get("fail"), Some(&"fixer".to_string()));
            }
            other => panic!("expected Switch, got {:?}", other),
        }
    }
}
