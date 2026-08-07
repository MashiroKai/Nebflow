//! FlowReport tool — reports a result to the flow pipeline.
//!
//! The Scala original routes the report to the flow engine which picks the
//! next node via switch routing. The Rust implementation keeps a process-wide
//! in-memory report log keyed by session, providing full record/retrieve
//! functionality within a single process run.

use async_trait::async_trait;
use serde_json::{Map, Value};
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use crate::types::{ToolContext, ToolError};
use crate::Tool;

/// A single flow report record.
#[derive(Debug, Clone)]
pub struct FlowReportRecord {
    pub session_id: String,
    pub verdict: String,
    pub output: String,
    pub reported_at_epoch_ms: u64,
}

/// Process-wide in-memory report log, keyed by session id.
pub fn report_log() -> &'static Mutex<HashMap<String, Vec<FlowReportRecord>>> {
    static LOG: OnceLock<Mutex<HashMap<String, Vec<FlowReportRecord>>>> = OnceLock::new();
    LOG.get_or_init(|| Mutex::new(HashMap::new()))
}

pub struct FlowReportTool {
    schema: Map<String, Value>,
}

impl FlowReportTool {
    pub fn new() -> Self {
        let schema = serde_json::json!({
            "type": "object",
            "properties": {
                "verdict": {
                    "type": "string",
                    "description": "Assessment verdict. Must match a case key in the flow's switch routing."
                },
                "output": {
                    "type": "string",
                    "description": "Your full work output — findings, analysis, code changes, etc."
                }
            },
            "required": ["verdict", "output"]
        });
        Self {
            schema: schema.as_object().unwrap().clone(),
        }
    }
}

impl Default for FlowReportTool {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Tool for FlowReportTool {
    fn name(&self) -> &str {
        "FlowReport"
    }

    fn description(&self) -> &str {
        "Report your result to the flow pipeline. The verdict determines which \
         node runs next in the pipeline."
    }

    fn input_schema(&self) -> &Map<String, Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &Map<String, Value>,
        ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let verdict = input
            .get("verdict")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim();

        if verdict.is_empty() {
            return Err(ToolError::InvalidInput("verdict is required".into()));
        }

        let output = input
            .get("output")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim();

        if output.is_empty() {
            return Err(ToolError::InvalidInput("output is required".into()));
        }

        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);

        let record = FlowReportRecord {
            session_id: ctx.session_id.clone(),
            verdict: verdict.to_string(),
            output: output.to_string(),
            reported_at_epoch_ms: now,
        };

        let mut log = report_log()
            .lock()
            .map_err(|e| ToolError::Execution(format!("report log lock poisoned: {}", e)))?;
        let entries = log.entry(ctx.session_id.clone()).or_default();
        let seq = entries.len() + 1;
        entries.push(record);

        Ok(format!(
            "Flow report #{} recorded for session {}: verdict=\"{}\" ({} chars of output). \
             The flow runtime picks the next node via switch routing.",
            seq,
            ctx.session_id,
            verdict,
            output.len()
        ))
    }

    fn summarize(&self, input: &Map<String, Value>) -> String {
        let verdict = input.get("verdict").and_then(|v| v.as_str()).unwrap_or("");
        format!("FlowReport({})", verdict)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx() -> ToolContext {
        ToolContext {
            session_id: format!("flow-test-{}", std::process::id()),
            agent_id: "a1".into(),
            working_directory: "/tmp".into(),
            depth: 0,
            ..Default::default()
        }
    }

    fn base_input() -> Map<String, Value> {
        let mut m = Map::new();
        m.insert("verdict".into(), Value::String("done".into()));
        m.insert("output".into(), Value::String("work complete".into()));
        m
    }

    #[tokio::test]
    async fn report_recorded() {
        let tool = FlowReportTool::new();
        let c = ctx();
        let result = tool.call(&base_input(), &c).await.unwrap();
        assert!(result.contains("verdict=\"done\""));

        let log = report_log().lock().unwrap();
        let entries = log.get(&c.session_id).expect("session entries exist");
        let last = entries.last().unwrap();
        assert_eq!(last.verdict, "done");
        assert_eq!(last.output, "work complete");
    }

    #[tokio::test]
    async fn empty_verdict_rejected() {
        let tool = FlowReportTool::new();
        let mut input = base_input();
        input.insert("verdict".into(), Value::String("  ".into()));
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }

    #[tokio::test]
    async fn empty_output_rejected() {
        let tool = FlowReportTool::new();
        let mut input = base_input();
        input.insert("output".into(), Value::String("".into()));
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }
}
