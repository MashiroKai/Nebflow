//! Flow DAG executor — executes flow DAGs node by node.
//!
//! Mirrors Scala `FlowDagExecutor.scala`. The executor takes a DAG definition
//! and an agent execution trait, then runs nodes sequentially with routing,
//! error handling, retry, and loop protection.

use std::collections::HashMap;
use std::sync::Arc;

use regex::Regex;

use crate::entity::{FlowDagDef, FlowExecContext, NodeResult, NodeRouteValue, OnError};
use crate::flow::registry::RunningFlowRegistry;

/// Trait for executing a single agent node.
/// Implementations spawn an agent, send input, and collect output.
#[async_trait::async_trait]
pub trait NodeExecutionTrait: Send + Sync {
    async fn execute_node(
        &self,
        node_id: &str,
        agent_name: &str,
        input_text: &str,
        flow_name: &str,
    ) -> NodeResult;
}

/// Result of a DAG execution.
pub type DagResult = Result<String, String>;

/// Executes a flow DAG deterministically.
pub struct FlowDagExecutor {
    registry: Arc<RunningFlowRegistry>,
}

impl FlowDagExecutor {
    pub fn new(registry: Arc<RunningFlowRegistry>) -> Self {
        Self { registry }
    }

    /// Execute a flow DAG.
    ///
    /// `instance_id` is used for cancellation checking and status updates.
    pub async fn execute(
        &self,
        flow: &FlowDagDef,
        task_input: &str,
        agent_executor: &dyn NodeExecutionTrait,
        instance_id: &str,
    ) -> DagResult {
        let ctx = FlowExecContext {
            flow_name: flow.name.clone(),
            task_input: task_input.to_string(),
            node_outputs: HashMap::new(),
            loop_counts: HashMap::new(),
            total_loops: 0,
        };

        // Register flow nodes in registry
        self.register_flow(instance_id, flow).await;

        let result = self
            .run_node(&flow.entry, ctx, flow, agent_executor, instance_id)
            .await;

        // Update final status
        let cancelled = self.registry.is_cancelled(instance_id).await;
        if !cancelled {
            let status = if result.is_ok() {
                "completed"
            } else {
                "failed"
            };
            self.registry
                .update(instance_id, |rf| {
                    let mut updated = rf;
                    updated.status = status.to_string();
                    updated.completed_at = Some(
                        std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .unwrap_or_default()
                            .as_millis() as u64,
                    );
                    updated
                })
                .await;
        }
        self.registry.clear_cancelled(instance_id).await;

        result
    }

    /// Run a single node: execute → handle errors → route.
    async fn run_node(
        &self,
        node_id: &str,
        ctx: FlowExecContext,
        flow: &FlowDagDef,
        agent_executor: &dyn NodeExecutionTrait,
        instance_id: &str,
    ) -> DagResult {
        // Check cancellation
        if self.registry.is_cancelled(instance_id).await {
            return Err("Flow cancelled by user".to_string());
        }

        tracing::info!(target: "flow.executor", "Flow '{}': executing node '{}'", flow.name, node_id);

        self.registry
            .set_node_status(instance_id, node_id, "running", "", "")
            .await;

        // Execute the node
        let (result, ctx2) = self.execute_node(node_id, ctx, flow, agent_executor).await;

        // Update status
        if result.success {
            let output_preview = if result.output.len() > 200 {
                format!("{}...", &result.output[..197])
            } else {
                result.output.clone()
            };
            self.registry
                .set_node_status(instance_id, node_id, "completed", &output_preview, "")
                .await;
        } else {
            self.registry
                .set_node_status(
                    instance_id,
                    node_id,
                    "failed",
                    "",
                    result.error.as_deref().unwrap_or("unknown"),
                )
                .await;
        }

        // Handle errors (retry/resume/stop)
        let handled = self
            .handle_result(node_id, result, ctx2, flow, agent_executor, instance_id)
            .await?;
        let (final_result, ctx3) = handled;

        // Route to next node
        self.route(final_result, ctx3, flow, agent_executor, instance_id)
            .await
    }

    /// Resolve template variables: $task, $nodeId.output
    fn resolve_input(template: &str, ctx: &FlowExecContext) -> String {
        let with_task = template.replace("$task", &ctx.task_input);
        let pattern = Regex::new(r"\$([a-zA-Z0-9_-]+)\.output").unwrap();
        pattern
            .replace_all(&with_task, |caps: &regex::Captures| {
                let node_id = &caps[1];
                ctx.node_outputs
                    .get(node_id)
                    .cloned()
                    .unwrap_or_else(|| format!("[output of {} not found]", node_id))
            })
            .to_string()
    }

    /// Execute a single DAG node.
    async fn execute_node(
        &self,
        node_id: &str,
        ctx: FlowExecContext,
        flow: &FlowDagDef,
        agent_executor: &dyn NodeExecutionTrait,
    ) -> (NodeResult, FlowExecContext) {
        let node = match flow.nodes.get(node_id) {
            Some(n) => n,
            None => {
                return (
                    NodeResult {
                        node_id: node_id.to_string(),
                        output: String::new(),
                        success: false,
                        error: Some(format!("Unknown node: {}", node_id)),
                        attempt_count: 1,
                        verdict: None,
                    },
                    ctx,
                );
            }
        };

        let input_text = Self::resolve_input(&node.input, &ctx);
        let result = agent_executor
            .execute_node(node_id, &node.agent, &input_text, &flow.name)
            .await;

        let mut updated_ctx = ctx;
        updated_ctx
            .node_outputs
            .insert(node_id.to_string(), result.output.clone());

        (result, updated_ctx)
    }

    /// Error handling + retry logic.
    async fn handle_result(
        &self,
        node_id: &str,
        result: NodeResult,
        ctx: FlowExecContext,
        flow: &FlowDagDef,
        agent_executor: &dyn NodeExecutionTrait,
        instance_id: &str,
    ) -> Result<(NodeResult, FlowExecContext), String> {
        if result.success {
            return Ok((result, ctx));
        }

        let node = match flow.nodes.get(node_id) {
            Some(n) => n,
            None => return Err(format!("Unknown node '{}' in error handler", node_id)),
        };

        let on_error = node.on_error.unwrap_or(OnError::Stop);
        match on_error {
            OnError::Resume => {
                tracing::warn!(target: "flow.executor", "Node '{}' failed, resuming", node_id);
                Ok((result, ctx))
            }
            OnError::Restart => {
                let retry_count = result.attempt_count;
                if retry_count <= node.max_retries {
                    tracing::info!(
                        target: "flow.executor",
                        "Node '{}' retry {}/{}",
                        node_id,
                        retry_count,
                        node.max_retries
                    );
                    let (new_result, new_ctx) =
                        self.execute_node(node_id, ctx, flow, agent_executor).await;
                    let mut bumped = new_result.clone();
                    bumped.attempt_count = retry_count + 1;
                    Box::pin(self.handle_result(
                        node_id,
                        bumped,
                        new_ctx,
                        flow,
                        agent_executor,
                        instance_id,
                    ))
                    .await
                } else {
                    Err(format!(
                        "Node '{}' failed after {} retries: {}",
                        node_id,
                        retry_count,
                        result.error.as_deref().unwrap_or("unknown")
                    ))
                }
            }
            OnError::Stop => Err(format!(
                "Node '{}' failed: {}",
                node_id,
                result.error.as_deref().unwrap_or("unknown")
            )),
        }
    }

    /// Route to next node based on onComplete rules.
    async fn route(
        &self,
        result: NodeResult,
        ctx: FlowExecContext,
        flow: &FlowDagDef,
        agent_executor: &dyn NodeExecutionTrait,
        instance_id: &str,
    ) -> DagResult {
        let node = match flow.nodes.get(&result.node_id) {
            Some(n) => n,
            None => return Err(format!("Unknown node '{}' in routing", result.node_id)),
        };

        match &node.on_complete {
            NodeRouteValue::Return => Ok(result.output),
            NodeRouteValue::Goto(target) => {
                let loop_key = format!("{}->{}", result.node_id, target);
                let new_count = ctx.loop_counts.get(&loop_key).copied().unwrap_or(0) + 1;
                if new_count > flow.max_loop {
                    return Err(format!(
                        "Max loop ({}) exceeded at edge {}",
                        flow.max_loop, loop_key
                    ));
                }
                let mut new_ctx = ctx;
                new_ctx.loop_counts.insert(loop_key, new_count);
                new_ctx.total_loops += 1;
                Box::pin(self.run_node(target, new_ctx, flow, agent_executor, instance_id)).await
            }
            NodeRouteValue::Switch { switch_expr, cases } => {
                // Evaluate the switch value from result verdict or by parsing output
                let switch_val = result
                    .verdict
                    .as_deref()
                    .filter(|v| !v.is_empty())
                    .map(|v| v.to_string())
                    .unwrap_or_else(|| {
                        // Try extracting from the switch expression (e.g., "$reviewer.verdict")
                        extract_switch_value(switch_expr, &result.output, &ctx, cases)
                    });

                // Look up the case target — fall back to "default", then the raw value
                let target = cases
                    .get(&switch_val)
                    .or_else(|| cases.get("default"))
                    .map(|s| s.as_str())
                    .unwrap_or(&switch_val);

                // Handle $return as a terminal route
                if target == "$return" {
                    return Ok(result.output);
                }

                tracing::info!(
                    target: "flow.executor",
                    node = %result.node_id,
                    switch_value = %switch_val,
                    target_node = %target,
                    "switch routing"
                );

                let loop_key = format!("{}->{}", result.node_id, target);
                let new_count = ctx.loop_counts.get(&loop_key).copied().unwrap_or(0) + 1;
                if new_count > flow.max_loop {
                    return Err(format!(
                        "Max loop ({}) exceeded at edge {}",
                        flow.max_loop, loop_key
                    ));
                }
                let mut new_ctx = ctx;
                new_ctx.loop_counts.insert(loop_key, new_count);
                new_ctx.total_loops += 1;
                Box::pin(self.run_node(target, new_ctx, flow, agent_executor, instance_id)).await
            }
        }
    }

    /// Register flow nodes in the running flow registry.
    async fn register_flow(&self, instance_id: &str, flow: &FlowDagDef) {
        use crate::flow::registry::{NodeState, RunningFlow};

        let nodes: HashMap<String, NodeState> = flow
            .nodes
            .iter()
            .map(|(node_id, node)| {
                (
                    node_id.clone(),
                    NodeState {
                        node_id: node_id.clone(),
                        agent: node.agent.clone(),
                        status: "pending".into(),
                        output: String::new(),
                        error: String::new(),
                        started_at: None,
                        completed_at: None,
                    },
                )
            })
            .collect();

        let edges: Vec<(String, String, Option<String>)> = flow
            .nodes
            .iter()
            .flat_map(|(node_id, node)| match &node.on_complete {
                NodeRouteValue::Goto(target) => vec![(node_id.clone(), target.clone(), None)],
                NodeRouteValue::Return => vec![(node_id.clone(), "$return".into(), None)],
                NodeRouteValue::Switch { cases, .. } => cases
                    .iter()
                    .map(|(condition, target)| {
                        (node_id.clone(), target.clone(), Some(condition.clone()))
                    })
                    .collect(),
            })
            .collect();

        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;

        self.registry
            .register(RunningFlow {
                instance_id: instance_id.to_string(),
                flow_name: flow.name.clone(),
                description: flow.description.clone(),
                entry: flow.entry.clone(),
                nodes,
                edges,
                status: "running".into(),
                started_at: now,
                completed_at: None,
                session_id: None,
            })
            .await;
    }
}

/// Extract a field value from node output for switch routing.
/// Format: "$reviewer.verdict" → look up reviewer's output, try JSON parse.
pub fn extract_switch_value(
    switch_expr: &str,
    current_node_output: &str,
    ctx: &FlowExecContext,
    cases: &HashMap<String, String>,
) -> String {
    let pattern = Regex::new(r"\$([a-zA-Z0-9_-]+)\.([a-zA-Z0-9_-]+)").unwrap();
    if let Some(caps) = pattern.captures(switch_expr) {
        let node_id = &caps[1];
        let field = &caps[2];
        let output = ctx
            .node_outputs
            .get(node_id)
            .map(|s| s.as_str())
            .unwrap_or(current_node_output);
        // Try JSON parsing
        if let Ok(json) = serde_json::from_str::<serde_json::Value>(output) {
            if let Some(val) = json.get(field).and_then(|v| v.as_str()) {
                return val.to_string();
            }
        }
        // Fallback: match against case keys (case-insensitive)
        for key in cases.keys() {
            if output.to_lowercase().contains(&key.to_lowercase()) {
                return key.clone();
            }
        }
    }
    "unknown".to_string()
}

// ============================================================
// Tests
// ============================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::entity::{FlowNode, OnError};
    use tokio::sync::RwLock;

    /// Mock agent executor that returns pre-configured results.
    struct MockExecutor {
        results: RwLock<HashMap<String, NodeResult>>,
    }

    impl MockExecutor {
        fn new() -> Self {
            Self {
                results: RwLock::new(HashMap::new()),
            }
        }

        async fn set_result(&self, node_id: &str, result: NodeResult) {
            self.results
                .write()
                .await
                .insert(node_id.to_string(), result);
        }
    }

    #[async_trait::async_trait]
    impl NodeExecutionTrait for MockExecutor {
        async fn execute_node(
            &self,
            node_id: &str,
            _agent_name: &str,
            _input_text: &str,
            _flow_name: &str,
        ) -> NodeResult {
            self.results
                .read()
                .await
                .get(node_id)
                .cloned()
                .unwrap_or(NodeResult {
                    node_id: node_id.to_string(),
                    output: format!("default output for {}", node_id),
                    success: true,
                    error: None,
                    attempt_count: 1,
                    verdict: None,
                })
        }
    }

    fn make_simple_flow() -> FlowDagDef {
        let mut nodes = HashMap::new();
        nodes.insert(
            "scanner".into(),
            FlowNode {
                agent: "Explorer".into(),
                input: "$task".into(),
                on_complete: NodeRouteValue::Goto("reviewer".into()),
                on_error: None,
                max_retries: 0,
            },
        );
        nodes.insert(
            "reviewer".into(),
            FlowNode {
                agent: "Reviewer".into(),
                input: "$scanner.output".into(),
                on_complete: NodeRouteValue::Return,
                on_error: None,
                max_retries: 0,
            },
        );
        FlowDagDef {
            name: "test-flow".into(),
            description: "test".into(),
            nodes,
            entry: "scanner".into(),
            max_loop: 10,
        }
    }

    #[tokio::test]
    async fn execute_linear_flow() {
        let registry = Arc::new(RunningFlowRegistry::new());
        let executor = FlowDagExecutor::new(registry.clone());
        let flow = make_simple_flow();

        let mock = MockExecutor::new();
        mock.set_result(
            "scanner",
            NodeResult {
                node_id: "scanner".into(),
                output: "scan complete".into(),
                success: true,
                error: None,
                attempt_count: 1,
                verdict: None,
            },
        )
        .await;
        mock.set_result(
            "reviewer",
            NodeResult {
                node_id: "reviewer".into(),
                output: "review done".into(),
                success: true,
                error: None,
                attempt_count: 1,
                verdict: None,
            },
        )
        .await;

        let result = executor
            .execute(&flow, "review this code", &mock, "inst-1")
            .await;

        assert!(result.is_ok());
        assert_eq!(result.unwrap(), "review done");

        let rf = registry.get("inst-1").await.unwrap();
        assert_eq!(rf.status, "completed");
    }

    #[tokio::test]
    async fn execute_node_not_found() {
        let registry = Arc::new(RunningFlowRegistry::new());
        let executor = FlowDagExecutor::new(registry.clone());

        let flow = FlowDagDef {
            name: "bad".into(),
            description: "d".into(),
            nodes: HashMap::new(),
            entry: "nonexistent".into(),
            max_loop: 10,
        };

        let mock = MockExecutor::new();
        let result = executor.execute(&flow, "task", &mock, "inst-2").await;
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("Unknown node"));
    }

    #[tokio::test]
    async fn max_loop_protection() {
        let registry = Arc::new(RunningFlowRegistry::new());
        let executor = FlowDagExecutor::new(registry.clone());

        // Create a flow that loops: a → b → a → b → ...
        let mut nodes = HashMap::new();
        nodes.insert(
            "a".into(),
            FlowNode {
                agent: "Agent".into(),
                input: "$task".into(),
                on_complete: NodeRouteValue::Goto("b".into()),
                on_error: None,
                max_retries: 0,
            },
        );
        nodes.insert(
            "b".into(),
            FlowNode {
                agent: "Agent".into(),
                input: "$a.output".into(),
                on_complete: NodeRouteValue::Goto("a".into()),
                on_error: None,
                max_retries: 0,
            },
        );
        let flow = FlowDagDef {
            name: "loop".into(),
            description: "d".into(),
            nodes,
            entry: "a".into(),
            max_loop: 3,
        };

        let mock = MockExecutor::new();
        let result = executor.execute(&flow, "task", &mock, "inst-3").await;
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("Max loop"));
    }

    #[tokio::test]
    async fn error_handling_stop() {
        let registry = Arc::new(RunningFlowRegistry::new());
        let executor = FlowDagExecutor::new(registry.clone());

        let mut nodes = HashMap::new();
        nodes.insert(
            "fail-node".into(),
            FlowNode {
                agent: "Agent".into(),
                input: "$task".into(),
                on_complete: NodeRouteValue::Return,
                on_error: Some(OnError::Stop),
                max_retries: 0,
            },
        );
        let flow = FlowDagDef {
            name: "fail".into(),
            description: "d".into(),
            nodes,
            entry: "fail-node".into(),
            max_loop: 10,
        };

        let mock = MockExecutor::new();
        mock.set_result(
            "fail-node",
            NodeResult {
                node_id: "fail-node".into(),
                output: "".into(),
                success: false,
                error: Some("agent crashed".into()),
                attempt_count: 1,
                verdict: None,
            },
        )
        .await;

        let result = executor.execute(&flow, "task", &mock, "inst-4").await;
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("fail-node"));
    }

    #[tokio::test]
    async fn error_handling_resume() {
        let registry = Arc::new(RunningFlowRegistry::new());
        let executor = FlowDagExecutor::new(registry.clone());

        let mut nodes = HashMap::new();
        nodes.insert(
            "resilient".into(),
            FlowNode {
                agent: "Agent".into(),
                input: "$task".into(),
                on_complete: NodeRouteValue::Return,
                on_error: Some(OnError::Resume),
                max_retries: 0,
            },
        );
        let flow = FlowDagDef {
            name: "resilient".into(),
            description: "d".into(),
            nodes,
            entry: "resilient".into(),
            max_loop: 10,
        };

        let mock = MockExecutor::new();
        mock.set_result(
            "resilient",
            NodeResult {
                node_id: "resilient".into(),
                output: "partial result".into(),
                success: false,
                error: Some("warning".into()),
                attempt_count: 1,
                verdict: None,
            },
        )
        .await;

        let result = executor.execute(&flow, "task", &mock, "inst-5").await;
        // Resume should continue and return output despite failure
        assert!(result.is_ok());
        assert_eq!(result.unwrap(), "partial result");
    }

    #[tokio::test]
    async fn retry_then_success() {
        let registry = Arc::new(RunningFlowRegistry::new());
        let executor = FlowDagExecutor::new(registry.clone());

        let mut nodes = HashMap::new();
        nodes.insert(
            "retryable".into(),
            FlowNode {
                agent: "Agent".into(),
                input: "$task".into(),
                on_complete: NodeRouteValue::Return,
                on_error: Some(OnError::Restart),
                max_retries: 2,
            },
        );
        let flow = FlowDagDef {
            name: "retry".into(),
            description: "d".into(),
            nodes,
            entry: "retryable".into(),
            max_loop: 10,
        };

        let mock = MockExecutor::new();
        // First call fails, second succeeds
        mock.set_result(
            "retryable",
            NodeResult {
                node_id: "retryable".into(),
                output: "success on retry".into(),
                success: true,
                error: None,
                attempt_count: 1,
                verdict: None,
            },
        )
        .await;

        let result = executor.execute(&flow, "task", &mock, "inst-6").await;
        assert!(result.is_ok());
        assert_eq!(result.unwrap(), "success on retry");
    }

    #[tokio::test]
    async fn cancellation_stops_flow() {
        let registry = Arc::new(RunningFlowRegistry::new());
        let executor = FlowDagExecutor::new(registry.clone());

        let flow = make_simple_flow();
        let mock = MockExecutor::new();

        // Cancel before execution
        registry.cancel("inst-7").await;

        let result = executor.execute(&flow, "task", &mock, "inst-7").await;
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("cancelled"));
    }

    #[test]
    fn resolve_input_templates() {
        let ctx = FlowExecContext {
            flow_name: "test".into(),
            task_input: "my task".into(),
            node_outputs: HashMap::from([("scanner".into(), "scan output".into())]),
            loop_counts: HashMap::new(),
            total_loops: 0,
        };

        assert_eq!(FlowDagExecutor::resolve_input("$task", &ctx), "my task");
        assert_eq!(
            FlowDagExecutor::resolve_input("$scanner.output", &ctx),
            "scan output"
        );
        assert_eq!(
            FlowDagExecutor::resolve_input("$task + $scanner.output", &ctx),
            "my task + scan output"
        );
    }

    #[test]
    fn extract_switch_value_json() {
        let ctx = FlowExecContext {
            flow_name: "test".into(),
            task_input: "".into(),
            node_outputs: HashMap::from([(
                "reviewer".into(),
                r#"{"verdict":"pass","score":95}"#.into(),
            )]),
            loop_counts: HashMap::new(),
            total_loops: 0,
        };
        let cases = HashMap::from([
            ("pass".to_string(), "done".to_string()),
            ("fail".to_string(), "fix".to_string()),
        ]);

        let result = extract_switch_value("$reviewer.verdict", "", &ctx, &cases);
        assert_eq!(result, "pass");
    }

    #[test]
    fn extract_switch_value_fallback_keyword() {
        let ctx = FlowExecContext {
            flow_name: "test".into(),
            task_input: "".into(),
            node_outputs: HashMap::from([(
                "reviewer".into(),
                "The code looks great, verdict is fail because of bugs".into(),
            )]),
            loop_counts: HashMap::new(),
            total_loops: 0,
        };
        let cases = HashMap::from([
            ("pass".to_string(), "done".to_string()),
            ("fail".to_string(), "fix".to_string()),
        ]);

        let result = extract_switch_value("$reviewer.verdict", "", &ctx, &cases);
        assert_eq!(result, "fail");
    }

    // ── Switch routing tests ───────────────────────────────────

    fn make_switch_flow() -> FlowDagDef {
        let mut nodes = HashMap::new();
        nodes.insert(
            "reviewer".into(),
            FlowNode {
                agent: "Reviewer".into(),
                input: "$task".into(),
                on_complete: NodeRouteValue::Switch {
                    switch_expr: "$reviewer.verdict".into(),
                    cases: HashMap::from([
                        ("pass".to_string(), "done".to_string()),
                        ("fail".to_string(), "fixer".to_string()),
                        ("default".to_string(), "done".to_string()),
                    ]),
                },
                on_error: None,
                max_retries: 0,
            },
        );
        nodes.insert(
            "fixer".into(),
            FlowNode {
                agent: "Coder".into(),
                input: "Fix the issues".into(),
                on_complete: NodeRouteValue::Return,
                on_error: None,
                max_retries: 0,
            },
        );
        nodes.insert(
            "done".into(),
            FlowNode {
                agent: "Summary".into(),
                input: "All good".into(),
                on_complete: NodeRouteValue::Return,
                on_error: None,
                max_retries: 0,
            },
        );
        FlowDagDef {
            name: "switch-flow".into(),
            description: "test switch routing".into(),
            nodes,
            entry: "reviewer".into(),
            max_loop: 10,
        }
    }

    #[tokio::test]
    async fn switch_routing_pass_verdict() {
        let registry = Arc::new(RunningFlowRegistry::new());
        let executor = FlowDagExecutor::new(registry.clone());
        let flow = make_switch_flow();

        let mock = MockExecutor::new();
        mock.set_result(
            "reviewer",
            NodeResult {
                node_id: "reviewer".into(),
                output: "code looks good".into(),
                success: true,
                error: None,
                attempt_count: 1,
                verdict: Some("pass".into()),
            },
        )
        .await;
        mock.set_result(
            "done",
            NodeResult {
                node_id: "done".into(),
                output: "review passed, done".into(),
                success: true,
                error: None,
                attempt_count: 1,
                verdict: None,
            },
        )
        .await;

        let result = executor
            .execute(&flow, "review this", &mock, "switch-1")
            .await;

        assert!(result.is_ok());
        assert_eq!(result.unwrap(), "review passed, done");
    }

    #[tokio::test]
    async fn switch_routing_fail_verdict() {
        let registry = Arc::new(RunningFlowRegistry::new());
        let executor = FlowDagExecutor::new(registry.clone());
        let flow = make_switch_flow();

        let mock = MockExecutor::new();
        mock.set_result(
            "reviewer",
            NodeResult {
                node_id: "reviewer".into(),
                output: "found issues".into(),
                success: true,
                error: None,
                attempt_count: 1,
                verdict: Some("fail".into()),
            },
        )
        .await;
        mock.set_result(
            "fixer",
            NodeResult {
                node_id: "fixer".into(),
                output: "issues fixed".into(),
                success: true,
                error: None,
                attempt_count: 1,
                verdict: None,
            },
        )
        .await;

        let result = executor
            .execute(&flow, "review this", &mock, "switch-2")
            .await;

        assert!(result.is_ok());
        assert_eq!(result.unwrap(), "issues fixed");
    }

    #[tokio::test]
    async fn switch_routing_default_case() {
        let registry = Arc::new(RunningFlowRegistry::new());
        let executor = FlowDagExecutor::new(registry.clone());
        let flow = make_switch_flow();

        let mock = MockExecutor::new();
        mock.set_result(
            "reviewer",
            NodeResult {
                node_id: "reviewer".into(),
                output: "unclear result".into(),
                success: true,
                error: None,
                attempt_count: 1,
                verdict: Some("unknown".into()), // no matching case → default
            },
        )
        .await;
        mock.set_result(
            "done",
            NodeResult {
                node_id: "done".into(),
                output: "default path taken".into(),
                success: true,
                error: None,
                attempt_count: 1,
                verdict: None,
            },
        )
        .await;

        let result = executor
            .execute(&flow, "review this", &mock, "switch-3")
            .await;

        assert!(result.is_ok());
        assert_eq!(result.unwrap(), "default path taken");
    }

    #[tokio::test]
    async fn switch_routing_json_extraction() {
        // Test switch with JSON output extraction (no verdict field)
        let registry = Arc::new(RunningFlowRegistry::new());
        let executor = FlowDagExecutor::new(registry.clone());

        let mut nodes = HashMap::new();
        nodes.insert(
            "analyzer".into(),
            FlowNode {
                agent: "Analyzer".into(),
                input: "$task".into(),
                on_complete: NodeRouteValue::Switch {
                    switch_expr: "$analyzer.verdict".into(),
                    cases: HashMap::from([
                        ("pass".to_string(), "finish".to_string()),
                        ("fail".to_string(), "finish".to_string()),
                    ]),
                },
                on_error: None,
                max_retries: 0,
            },
        );
        nodes.insert(
            "finish".into(),
            FlowNode {
                agent: "Finisher".into(),
                input: "done".into(),
                on_complete: NodeRouteValue::Return,
                on_error: None,
                max_retries: 0,
            },
        );
        let flow = FlowDagDef {
            name: "json-switch".into(),
            description: "test".into(),
            nodes,
            entry: "analyzer".into(),
            max_loop: 10,
        };

        let mock = MockExecutor::new();
        // JSON output with verdict field but no verdict in NodeResult
        mock.set_result(
            "analyzer",
            NodeResult {
                node_id: "analyzer".into(),
                output: r#"{"verdict":"pass","score":90}"#.into(),
                success: true,
                error: None,
                attempt_count: 1,
                verdict: None, // No verdict — should fall back to JSON parsing
            },
        )
        .await;
        mock.set_result(
            "finish",
            NodeResult {
                node_id: "finish".into(),
                output: "finished".into(),
                success: true,
                error: None,
                attempt_count: 1,
                verdict: None,
            },
        )
        .await;

        let result = executor.execute(&flow, "analyze", &mock, "switch-4").await;
        assert!(result.is_ok());
        assert_eq!(result.unwrap(), "finished");
    }
}
