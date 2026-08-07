//! DelegateTool — spawn a background sub-agent for a subtask.
//! Mirrors `nebflow.core.tools.DelegateTool` from Scala.

use crate::types::{SpawnParams, ToolContext, ToolError};
use crate::Tool;
use async_trait::async_trait;

const MAX_DEPTH: usize = 5;

pub struct DelegateTool {
    schema: serde_json::Map<String, serde_json::Value>,
}

impl DelegateTool {
    pub fn new() -> Self {
        let mut schema = serde_json::Map::new();
        schema.insert("type".into(), "object".into());
        let mut props = serde_json::Map::new();
        props.insert("prompt".into(), serde_json::json!({"type": "string", "description": "Self-contained task description for the sub-agent"}));
        props.insert(
            "description".into(),
            serde_json::json!({"type": "string", "description": "Short label for the task"}),
        );
        props.insert("agent".into(), serde_json::json!({"type": "string", "description": "Target standalone agent name (e.g. 'Coder', 'Explorer')"}));
        props.insert("flow".into(), serde_json::json!({"type": "string", "description": "Flow name to trigger a flow DAG pipeline"}));
        props.insert("fork".into(), serde_json::json!({"type": "boolean", "description": "If true, pass current conversation context to the sub-agent"}));
        props.insert("lifecycle".into(), serde_json::json!({"type": "string", "enum": ["ephemeral", "persistent"], "description": "ephemeral (default): sub-agent completes and exits. persistent: sub-agent stays alive for follow-up Mail."}));
        schema.insert("properties".into(), serde_json::Value::Object(props));
        schema.insert("required".into(), serde_json::json!(["prompt"]));
        Self { schema }
    }
}

impl Default for DelegateTool {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Tool for DelegateTool {
    fn name(&self) -> &str {
        "Delegate"
    }
    fn description(&self) -> &str {
        "Spawn a background sub-agent to work on a subtask while you continue your own work. The sub-agent shares your tools and system prompt, runs autonomously with its own context, and reports back when done.\n\nMultiple Delegate calls in one response run concurrently — use this to parallelize independent work."
    }
    fn input_schema(&self) -> &serde_json::Map<String, serde_json::Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &serde_json::Map<String, serde_json::Value>,
        ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let prompt = super::get_str(input, "prompt").unwrap_or("");
        if prompt.is_empty() {
            return Err(ToolError::InvalidInput("prompt is required".into()));
        }

        if ctx.depth >= MAX_DEPTH {
            return Err(ToolError::Permission(format!(
                "Maximum delegation depth ({MAX_DEPTH}) reached. Cannot spawn further sub-agents."
            )));
        }

        let agent = super::get_str(input, "agent");
        let flow = super::get_str(input, "flow");
        let lifecycle = super::get_str(input, "lifecycle").unwrap_or("ephemeral");
        let fork = super::get_bool(input, "fork").unwrap_or(false);
        let description = super::get_str(input, "description")
            .unwrap_or("")
            .to_string();

        if agent.is_some() && flow.is_some() {
            return Err(ToolError::InvalidInput(
                "Cannot specify both 'agent' and 'flow' parameters".into(),
            ));
        }

        // If an AgentSpawner is available, use it to actually spawn the sub-agent.
        if let Some(spawner) = &ctx.agent_spawner {
            let params = SpawnParams {
                prompt: prompt.to_string(),
                description,
                agent: agent.map(|s| s.to_string()),
                flow: flow.map(|s| s.to_string()),
                fork,
                lifecycle: lifecycle.to_string(),
                parent_depth: ctx.depth,
                session_id: ctx.session_id.clone(),
            };

            match spawner.spawn(params).await {
                Ok(result) => {
                    let target = agent.or(flow).unwrap_or("self-clone");
                    let kind = if flow.is_some() { "flow" } else { "agent" };
                    return Ok(format!(
                        "[Delegate] Spawned {kind} '{target}' (address: {}, lifecycle: {lifecycle}, fork: {fork}, depth: {})\nPrompt: {}",
                        result.address,
                        ctx.depth + 1,
                        if prompt.len() > 200 { format!("{}...", &prompt[..200]) } else { prompt.to_string() }
                    ));
                }
                Err(e) => {
                    return Err(ToolError::Execution(format!(
                        "Failed to spawn sub-agent: {e}"
                    )));
                }
            }
        }

        // Fallback: no spawner available — return formatted string (stub mode).
        let target = agent.or(flow).unwrap_or("self-clone");
        let kind = if flow.is_some() { "flow" } else { "agent" };

        Ok(format!(
            "[Delegate] Spawned {kind} '{target}' (lifecycle: {lifecycle}, fork: {fork}, depth: {})\nPrompt: {}",
            ctx.depth + 1,
            if prompt.len() > 200 { format!("{}...", &prompt[..200]) } else { prompt.to_string() }
        ))
    }

    fn summarize(&self, input: &serde_json::Map<String, serde_json::Value>) -> String {
        let agent = super::get_str(input, "agent")
            .or_else(|| super::get_str(input, "flow"))
            .unwrap_or("self-clone");
        let desc = super::get_str(input, "description").unwrap_or("");
        if desc.is_empty() {
            format!("Delegate({agent})")
        } else {
            format!("Delegate({agent}: {desc})")
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::{AgentSpawner, SpawnParams, SpawnResult};

    fn ctx(depth: usize) -> ToolContext {
        ToolContext {
            session_id: "test".into(),
            agent_id: "test".into(),
            working_directory: "/tmp".into(),
            depth,
            ..Default::default()
        }
    }

    struct MockSpawner {
        result: Result<SpawnResult, String>,
    }

    #[async_trait::async_trait]
    impl AgentSpawner for MockSpawner {
        async fn spawn(&self, _params: SpawnParams) -> Result<SpawnResult, String> {
            self.result.clone()
        }
    }

    fn ctx_with_spawner(depth: usize) -> ToolContext {
        let spawner: Arc<dyn AgentSpawner> = Arc::new(MockSpawner {
            result: Ok(SpawnResult {
                address: "sub-agent-123".into(),
                started: true,
            }),
        });
        ToolContext {
            session_id: "test".into(),
            agent_id: "test".into(),
            working_directory: "/tmp".into(),
            depth,
            agent_spawner: Some(spawner),
            ..Default::default()
        }
    }

    fn ctx_with_failing_spawner(depth: usize) -> ToolContext {
        let spawner: Arc<dyn AgentSpawner> = Arc::new(MockSpawner {
            result: Err("agent not found".into()),
        });
        ToolContext {
            session_id: "test".into(),
            agent_id: "test".into(),
            working_directory: "/tmp".into(),
            depth,
            agent_spawner: Some(spawner),
            ..Default::default()
        }
    }

    use std::sync::Arc;

    #[tokio::test]
    async fn delegate_basic_stub() {
        let tool = DelegateTool::new();
        let mut input = serde_json::Map::new();
        input.insert("prompt".into(), "Review the code".into());
        input.insert("agent".into(), "Coder".into());
        let result = tool.call(&input, &ctx(0)).await.unwrap();
        assert!(result.contains("Coder"));
        assert!(result.contains("depth: 1"));
    }

    #[tokio::test]
    async fn delegate_with_spawner() {
        let tool = DelegateTool::new();
        let mut input = serde_json::Map::new();
        input.insert("prompt".into(), "Review the code".into());
        input.insert("agent".into(), "Coder".into());
        let result = tool.call(&input, &ctx_with_spawner(0)).await.unwrap();
        assert!(result.contains("sub-agent-123"));
        assert!(result.contains("Coder"));
        assert!(result.contains("depth: 1"));
    }

    #[tokio::test]
    async fn delegate_with_failing_spawner() {
        let tool = DelegateTool::new();
        let mut input = serde_json::Map::new();
        input.insert("prompt".into(), "Review the code".into());
        input.insert("agent".into(), "Coder".into());
        let result = tool.call(&input, &ctx_with_failing_spawner(0)).await;
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("agent not found"));
    }

    #[tokio::test]
    async fn delegate_empty_prompt() {
        let tool = DelegateTool::new();
        let input = serde_json::Map::new();
        let result = tool.call(&input, &ctx(0)).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn delegate_max_depth() {
        let tool = DelegateTool::new();
        let mut input = serde_json::Map::new();
        input.insert("prompt".into(), "test".into());
        let result = tool.call(&input, &ctx(MAX_DEPTH)).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn delegate_max_depth_even_with_spawner() {
        let tool = DelegateTool::new();
        let mut input = serde_json::Map::new();
        input.insert("prompt".into(), "test".into());
        let result = tool.call(&input, &ctx_with_spawner(MAX_DEPTH)).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn delegate_agent_and_flow_conflict() {
        let tool = DelegateTool::new();
        let mut input = serde_json::Map::new();
        input.insert("prompt".into(), "test".into());
        input.insert("agent".into(), "Coder".into());
        input.insert("flow".into(), "code-review".into());
        let result = tool.call(&input, &ctx(0)).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn delegate_flow_mode_stub() {
        let tool = DelegateTool::new();
        let mut input = serde_json::Map::new();
        input.insert("prompt".into(), "review branch feature-x".into());
        input.insert("flow".into(), "code-review".into());
        let result = tool.call(&input, &ctx(0)).await.unwrap();
        assert!(result.contains("flow"));
        assert!(result.contains("code-review"));
    }

    #[tokio::test]
    async fn delegate_flow_mode_with_spawner() {
        let tool = DelegateTool::new();
        let mut input = serde_json::Map::new();
        input.insert("prompt".into(), "review branch feature-x".into());
        input.insert("flow".into(), "code-review".into());
        let result = tool.call(&input, &ctx_with_spawner(0)).await.unwrap();
        assert!(result.contains("flow"));
        assert!(result.contains("code-review"));
        assert!(result.contains("sub-agent-123"));
    }
}
