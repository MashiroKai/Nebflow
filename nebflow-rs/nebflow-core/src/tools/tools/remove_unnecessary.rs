//! RemoveUnnecessary tool — validates a context-compaction request.
//!
//! The Scala original compacts recent tool-call rounds in the live agent
//! context. The Rust backend has no live context manager yet, so this tool
//! validates the parameters (rounds in [1,10], non-empty summary) and
//! returns a confirmation message.

use async_trait::async_trait;
use serde_json::{Map, Value};

use crate::types::{ToolContext, ToolError};
use crate::Tool;

const MIN_ROUNDS: u64 = 1;
const MAX_ROUNDS: u64 = 10;

pub struct RemoveUnnecessaryTool {
    schema: Map<String, Value>,
}

impl RemoveUnnecessaryTool {
    pub fn new() -> Self {
        let schema = serde_json::json!({
            "type": "object",
            "properties": {
                "rounds": {
                    "type": "number",
                    "description": "Number of recent tool call rounds to summarize (1-10)"
                },
                "summary": {
                    "type": "string",
                    "description": "Summary to replace the selected tool result content. Be specific: include file paths with backticks, line numbers, key findings."
                }
            },
            "required": ["rounds", "summary"]
        });
        Self {
            schema: schema.as_object().unwrap().clone(),
        }
    }
}

impl Default for RemoveUnnecessaryTool {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Tool for RemoveUnnecessaryTool {
    fn name(&self) -> &str {
        "RemoveUnnecessary"
    }

    fn description(&self) -> &str {
        "Summarize and replace recent tool call results to free up context \
         window space. Validates the request; live context compaction is \
         performed by the runtime that owns the agent context."
    }

    fn input_schema(&self) -> &Map<String, Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &Map<String, Value>,
        _ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let rounds = input
            .get("rounds")
            .and_then(|v| v.as_u64())
            .ok_or_else(|| ToolError::InvalidInput("rounds is required (integer 1-10)".into()))?;

        if !(MIN_ROUNDS..=MAX_ROUNDS).contains(&rounds) {
            return Err(ToolError::InvalidInput(format!(
                "rounds must be between {} and {}, got {}",
                MIN_ROUNDS, MAX_ROUNDS, rounds
            )));
        }

        let summary = input
            .get("summary")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim();

        if summary.is_empty() {
            return Err(ToolError::InvalidInput(
                "summary is required and must not be empty".into(),
            ));
        }

        Ok(format!(
            "Compaction request validated: {} round(s) to be replaced with a \
             {}-character summary. The runtime owning the agent context applies \
             the replacement.",
            rounds,
            summary.len()
        ))
    }

    fn summarize(&self, input: &Map<String, Value>) -> String {
        let rounds = input.get("rounds").and_then(|v| v.as_u64()).unwrap_or(0);
        format!("RemoveUnnecessary({} rounds)", rounds)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx() -> ToolContext {
        ToolContext {
            session_id: "s1".into(),
            agent_id: "a1".into(),
            working_directory: "/tmp".into(),
            depth: 0,
            ..Default::default()
        }
    }

    fn base_input() -> Map<String, Value> {
        let mut m = Map::new();
        m.insert("rounds".into(), Value::Number(3.into()));
        m.insert(
            "summary".into(),
            Value::String("Summarized 3 rounds: findings about `foo.rs:42`.".into()),
        );
        m
    }

    #[tokio::test]
    async fn valid_request_accepted() {
        let tool = RemoveUnnecessaryTool::new();
        let result = tool.call(&base_input(), &ctx()).await.unwrap();
        assert!(result.contains("3 round(s)"));
    }

    #[tokio::test]
    async fn rounds_out_of_range_rejected() {
        let tool = RemoveUnnecessaryTool::new();
        let mut input = base_input();
        input.insert("rounds".into(), Value::Number(11.into()));
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));

        let mut input0 = base_input();
        input0.insert("rounds".into(), Value::Number(0.into()));
        let result0 = tool.call(&input0, &ctx()).await;
        assert!(matches!(result0, Err(ToolError::InvalidInput(_))));
    }

    #[tokio::test]
    async fn empty_summary_rejected() {
        let tool = RemoveUnnecessaryTool::new();
        let mut input = base_input();
        input.insert("summary".into(), Value::String("   ".into()));
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }
}
