//! Running flow registry — tracks running flow DAG instances.
//!
//! Mirrors Scala `RunningFlowRegistry.scala`. Supports cancel, node status
//! updates, and stale cleanup.

use std::collections::{HashMap, HashSet};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use tokio::sync::RwLock;

/// Node execution status within a running flow.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NodeState {
    pub node_id: String,
    pub agent: String,
    /// "pending" | "running" | "completed" | "failed"
    pub status: String,
    #[serde(default)]
    pub output: String,
    #[serde(default)]
    pub error: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub started_at: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub completed_at: Option<u64>,
}

/// A running flow instance.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RunningFlow {
    pub instance_id: String,
    pub flow_name: String,
    pub description: String,
    pub entry: String,
    pub nodes: HashMap<String, NodeState>,
    /// (from, to, condition)
    pub edges: Vec<(String, String, Option<String>)>,
    /// "running" | "completed" | "failed" | "cancelled"
    pub status: String,
    pub started_at: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub completed_at: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub session_id: Option<String>,
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

/// Tracks running flow DAG instances for the frontend.
pub struct RunningFlowRegistry {
    flows: RwLock<HashMap<String, RunningFlow>>,
    cancelled: RwLock<HashSet<String>>,
}

impl RunningFlowRegistry {
    pub fn new() -> Self {
        Self {
            flows: RwLock::new(HashMap::new()),
            cancelled: RwLock::new(HashSet::new()),
        }
    }

    /// Register a running flow.
    pub async fn register(&self, flow: RunningFlow) {
        self.flows
            .write()
            .await
            .insert(flow.instance_id.clone(), flow);
    }

    /// Mark a flow as cancelled.
    pub async fn cancel(&self, instance_id: &str) {
        self.cancelled.write().await.insert(instance_id.to_string());
        self.update(instance_id, |rf| RunningFlow {
            status: "cancelled".into(),
            ..rf
        })
        .await;
    }

    /// Check if a flow has been cancelled.
    pub async fn is_cancelled(&self, instance_id: &str) -> bool {
        self.cancelled.read().await.contains(instance_id)
    }

    /// Clear the cancel flag.
    pub async fn clear_cancelled(&self, instance_id: &str) {
        self.cancelled.write().await.remove(instance_id);
    }

    /// Update a running flow.
    pub async fn update<F>(&self, instance_id: &str, f: F)
    where
        F: FnOnce(RunningFlow) -> RunningFlow,
    {
        let mut flows = self.flows.write().await;
        if let Some(rf) = flows.get(instance_id) {
            let updated = f(rf.clone());
            flows.insert(instance_id.to_string(), updated);
        }
    }

    /// Set a node's status within a running flow.
    pub async fn set_node_status(
        &self,
        instance_id: &str,
        node_id: &str,
        status: &str,
        output: &str,
        error: &str,
    ) {
        self.update(instance_id, |rf| {
            let now = now_ms();
            let mut updated = rf.clone();
            if let Some(ns) = updated.nodes.get_mut(node_id) {
                ns.status = status.to_string();
                if !output.is_empty() {
                    ns.output = output.to_string();
                }
                if !error.is_empty() {
                    ns.error = error.to_string();
                }
                if status == "running" {
                    ns.started_at = Some(now);
                }
                if status == "completed" || status == "failed" {
                    ns.completed_at = Some(now);
                }
            }
            let new_status = if status == "failed" {
                "failed".to_string()
            } else if node_id == updated.entry && status == "completed" {
                "completed".to_string()
            } else {
                updated.status.clone()
            };
            updated.status = new_status.clone();
            if new_status == "completed" || new_status == "failed" {
                updated.completed_at = Some(now);
            }
            updated
        })
        .await;
    }

    /// List all running flows, sorted by start time.
    pub async fn list(&self) -> Vec<RunningFlow> {
        let flows = self.flows.read().await;
        let mut list: Vec<RunningFlow> = flows.values().cloned().collect();
        list.sort_by_key(|f| f.started_at);
        list
    }

    /// Get a specific running flow.
    pub async fn get(&self, instance_id: &str) -> Option<RunningFlow> {
        self.flows.read().await.get(instance_id).cloned()
    }

    /// Remove a flow instance.
    pub async fn remove(&self, instance_id: &str) {
        self.flows.write().await.remove(instance_id);
    }

    /// Remove flows that completed more than `retention_ms` ago.
    pub async fn cleanup_stale(&self, retention_ms: u64) {
        let now = now_ms();
        let mut flows = self.flows.write().await;
        flows.retain(|_, rf| {
            if rf.status == "completed" || rf.status == "failed" {
                if let Some(completed) = rf.completed_at {
                    return now - completed <= retention_ms;
                }
            }
            true
        });
    }
}

impl Default for RunningFlowRegistry {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_test_flow(instance_id: &str) -> RunningFlow {
        RunningFlow {
            instance_id: instance_id.to_string(),
            flow_name: "test-flow".into(),
            description: "test".into(),
            entry: "node1".into(),
            nodes: HashMap::new(),
            edges: vec![],
            status: "running".into(),
            started_at: 1000,
            completed_at: None,
            session_id: None,
        }
    }

    #[tokio::test]
    async fn register_and_get() {
        let registry = RunningFlowRegistry::new();
        let flow = make_test_flow("inst-1");
        registry.register(flow).await;

        let got = registry.get("inst-1").await;
        assert!(got.is_some());
        assert_eq!(got.unwrap().flow_name, "test-flow");
    }

    #[tokio::test]
    async fn cancel_and_check() {
        let registry = RunningFlowRegistry::new();
        registry.register(make_test_flow("inst-2")).await;

        assert!(!registry.is_cancelled("inst-2").await);
        registry.cancel("inst-2").await;
        assert!(registry.is_cancelled("inst-2").await);

        let flow = registry.get("inst-2").await.unwrap();
        assert_eq!(flow.status, "cancelled");
    }

    #[tokio::test]
    async fn clear_cancelled() {
        let registry = RunningFlowRegistry::new();
        registry.cancel("inst-3").await;
        assert!(registry.is_cancelled("inst-3").await);
        registry.clear_cancelled("inst-3").await;
        assert!(!registry.is_cancelled("inst-3").await);
    }

    #[tokio::test]
    async fn set_node_status() {
        let registry = RunningFlowRegistry::new();
        let mut flow = make_test_flow("inst-4");
        flow.nodes.insert(
            "node1".into(),
            NodeState {
                node_id: "node1".into(),
                agent: "Agent".into(),
                status: "pending".into(),
                output: "".into(),
                error: "".into(),
                started_at: None,
                completed_at: None,
            },
        );
        registry.register(flow).await;

        registry
            .set_node_status("inst-4", "node1", "running", "", "")
            .await;
        let f = registry.get("inst-4").await.unwrap();
        assert_eq!(f.nodes["node1"].status, "running");

        registry
            .set_node_status("inst-4", "node1", "completed", "result", "")
            .await;
        let f = registry.get("inst-4").await.unwrap();
        assert_eq!(f.nodes["node1"].status, "completed");
        assert_eq!(f.nodes["node1"].output, "result");
        // Entry node completed → flow completed
        assert_eq!(f.status, "completed");
    }

    #[tokio::test]
    async fn list_sorted_by_started_at() {
        let registry = RunningFlowRegistry::new();
        let mut f1 = make_test_flow("inst-a");
        f1.started_at = 2000;
        let mut f2 = make_test_flow("inst-b");
        f2.started_at = 1000;
        registry.register(f1).await;
        registry.register(f2).await;

        let list = registry.list().await;
        assert_eq!(list.len(), 2);
        assert_eq!(list[0].instance_id, "inst-b"); // earlier startedAt
    }

    #[tokio::test]
    async fn remove_flow() {
        let registry = RunningFlowRegistry::new();
        registry.register(make_test_flow("inst-5")).await;
        assert!(registry.get("inst-5").await.is_some());
        registry.remove("inst-5").await;
        assert!(registry.get("inst-5").await.is_none());
    }
}
