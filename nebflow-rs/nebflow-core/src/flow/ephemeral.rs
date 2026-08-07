//! Ephemeral agent runner — runs a standalone agent definition ephemerally.
//!
//! Mirrors Scala `EphemeralAgentRunner.scala`. Spawns an agent, sends input,
//! waits for completion, extracts output, then cleans up.
//!
//! In the Rust port, agent execution is represented via a trait.

use std::sync::Arc;

use crate::entity::AgentEntry;

/// Trait for running an agent ephemerally.
#[async_trait::async_trait]
pub trait EphemeralExecutionTrait: Send + Sync {
    /// Run an agent with the given definition and input. Returns the agent's output.
    async fn run_agent(
        &self,
        agent_entry: &AgentEntry,
        task_input: &str,
        depth: u32,
    ) -> Result<String, String>;
}

/// Ephemeral agent runner.
pub struct EphemeralAgentRunner {
    executor: Arc<dyn EphemeralExecutionTrait>,
}

impl EphemeralAgentRunner {
    pub fn new(executor: Arc<dyn EphemeralExecutionTrait>) -> Self {
        Self { executor }
    }

    /// Run an agent ephemerally and return the output.
    pub async fn run(
        &self,
        agent_entry: &AgentEntry,
        task_input: &str,
        depth: u32,
    ) -> Result<String, String> {
        tracing::info!(
            target: "flow.ephemeral",
            "Running ephemeral agent '{}' at depth {}",
            agent_entry.name,
            depth
        );

        self.executor
            .run_agent(agent_entry, task_input, depth)
            .await
            .inspect(|_| {
                tracing::info!(
                    target: "flow.ephemeral",
                    "Ephemeral agent '{}' completed",
                    agent_entry.name
                );
            })
            .map_err(|e| {
                tracing::warn!(
                    target: "flow.ephemeral",
                    "Ephemeral agent '{}' failed: {}",
                    agent_entry.name,
                    e
                );
                format!("[Agent '{}' failed: {}]", agent_entry.name, e)
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct MockEphemeralExecutor;

    #[async_trait::async_trait]
    impl EphemeralExecutionTrait for MockEphemeralExecutor {
        async fn run_agent(
            &self,
            agent_entry: &AgentEntry,
            task_input: &str,
            _depth: u32,
        ) -> Result<String, String> {
            Ok(format!("{} says: {}", agent_entry.name, task_input))
        }
    }

    fn make_agent(name: &str) -> AgentEntry {
        AgentEntry {
            name: name.to_string(),
            description: "test".into(),
            use_when: "testing".into(),
            tools: vec!["*".into()],
            voice: false,
            system_prompt: "test prompt".into(),
            category: "standalone".into(),
            mcp_servers: vec![],
            model: None,
        }
    }

    #[tokio::test]
    async fn run_ephemeral_agent_success() {
        let runner = EphemeralAgentRunner::new(Arc::new(MockEphemeralExecutor));
        let agent = make_agent("Coder");

        let result = runner.run(&agent, "write a function", 1).await;
        assert!(result.is_ok());
        assert_eq!(result.unwrap(), "Coder says: write a function");
    }

    struct FailingExecutor;

    #[async_trait::async_trait]
    impl EphemeralExecutionTrait for FailingExecutor {
        async fn run_agent(
            &self,
            _agent_entry: &AgentEntry,
            _task_input: &str,
            _depth: u32,
        ) -> Result<String, String> {
            Err("agent crashed".into())
        }
    }

    #[tokio::test]
    async fn run_ephemeral_agent_failure() {
        let runner = EphemeralAgentRunner::new(Arc::new(FailingExecutor));
        let agent = make_agent("Crasher");

        let result = runner.run(&agent, "task", 1).await;
        assert!(result.is_err());
        let err = result.unwrap_err();
        assert!(err.contains("Crasher"));
        assert!(err.contains("agent crashed"));
    }
}
