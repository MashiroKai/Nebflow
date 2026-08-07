//! Flow DAG runner — one-shot entry point for DAG execution.
//!
//! Mirrors Scala `FlowDagRunner.scala`. Executes a DAG synchronously
//! and delivers the result to the caller.

use std::sync::Arc;

use crate::entity::FlowDagDef;
use crate::flow::executor::{DagResult, FlowDagExecutor, NodeExecutionTrait};
use crate::flow::registry::RunningFlowRegistry;

/// One-shot runner that executes a Flow DAG and returns the result.
pub struct FlowDagRunner {
    executor: Arc<FlowDagExecutor>,
}

impl FlowDagRunner {
    pub fn new(registry: Arc<RunningFlowRegistry>) -> Self {
        Self {
            executor: Arc::new(FlowDagExecutor::new(registry)),
        }
    }

    /// Run a flow DAG. Returns the final output or an error message.
    pub async fn run(
        &self,
        flow_def: &FlowDagDef,
        task_input: &str,
        agent_executor: &dyn NodeExecutionTrait,
    ) -> DagResult {
        let instance_id = format!(
            "flow-{}-{}",
            flow_def.name.chars().take(15).collect::<String>(),
            uuid_short()
        );

        tracing::info!(
            target: "flow.runner",
            "Starting DAG execution for flow '{}' (instance: {})",
            flow_def.name,
            instance_id
        );

        self.executor
            .execute(flow_def, task_input, agent_executor, &instance_id)
            .await
            .inspect(|_| {
                tracing::info!(
                    target: "flow.runner",
                    "Flow '{}' completed successfully",
                    flow_def.name
                );
            })
            .map_err(|e| {
                tracing::warn!(target: "flow.runner", "Flow '{}' failed: {}", flow_def.name, e);
                e
            })
    }
}

/// Generate a short UUID-like string (8 chars).
fn uuid_short() -> String {
    use std::time::SystemTime;
    let nanos = SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    format!("{:08x}", (nanos as u64) & 0xFFFFFFFF)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::entity::NodeResult;
    use crate::entity::{FlowNode, NodeRouteValue};
    use std::collections::HashMap;

    struct EchoExecutor;

    #[async_trait::async_trait]
    impl NodeExecutionTrait for EchoExecutor {
        async fn execute_node(
            &self,
            node_id: &str,
            _agent_name: &str,
            input_text: &str,
            _flow_name: &str,
        ) -> NodeResult {
            NodeResult {
                node_id: node_id.to_string(),
                output: format!("echo: {}", input_text),
                success: true,
                error: None,
                attempt_count: 1,
                verdict: None,
            }
        }
    }

    #[tokio::test]
    async fn run_simple_flow() {
        let registry = Arc::new(RunningFlowRegistry::new());
        let runner = FlowDagRunner::new(registry.clone());

        let mut nodes = HashMap::new();
        nodes.insert(
            "start".into(),
            FlowNode {
                agent: "Agent".into(),
                input: "$task".into(),
                on_complete: NodeRouteValue::Return,
                on_error: None,
                max_retries: 0,
            },
        );
        let flow = FlowDagDef {
            name: "simple".into(),
            description: "d".into(),
            nodes,
            entry: "start".into(),
            max_loop: 10,
        };

        let result = runner.run(&flow, "hello world", &EchoExecutor).await;
        assert!(result.is_ok());
        assert_eq!(result.unwrap(), "echo: hello world");
    }

    #[tokio::test]
    async fn run_failed_flow() {
        let registry = Arc::new(RunningFlowRegistry::new());
        let runner = FlowDagRunner::new(registry.clone());

        let flow = FlowDagDef {
            name: "bad".into(),
            description: "d".into(),
            nodes: HashMap::new(),
            entry: "nonexistent".into(),
            max_loop: 10,
        };

        let result = runner.run(&flow, "task", &EchoExecutor).await;
        assert!(result.is_err());
    }
}
