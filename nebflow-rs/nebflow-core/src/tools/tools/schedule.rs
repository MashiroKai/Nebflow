//! Schedule tool — validates a scheduled-job request.
//!
//! The Scala original persists scheduled jobs in a scheduler actor with cron
//! or one-shot delays. The Rust backend has no scheduler runtime yet, so this
//! tool validates the parameters (delay or cron expression) and returns a
//! clear message that scheduling is not persisted.

use async_trait::async_trait;
use serde_json::{Map, Value};

use crate::types::{ToolContext, ToolError};
use crate::Tool;

pub struct ScheduleTool {
    schema: Map<String, Value>,
}

impl ScheduleTool {
    pub fn new() -> Self {
        let schema = serde_json::json!({
            "type": "object",
            "properties": {
                "prompt": {
                    "type": "string",
                    "description": "The prompt to execute when the schedule fires"
                },
                "delayMs": {
                    "type": "number",
                    "description": "One-shot delay in milliseconds from now"
                },
                "cron": {
                    "type": "string",
                    "description": "Cron expression (5 fields: min hour dom mon dow) for recurring schedules"
                }
            },
            "required": ["prompt"]
        });
        Self {
            schema: schema.as_object().unwrap().clone(),
        }
    }
}

impl Default for ScheduleTool {
    fn default() -> Self {
        Self::new()
    }
}

/// Validate a 5-field cron expression (each field: number, "*", lists, ranges, steps).
fn validate_cron(expr: &str) -> Result<(), String> {
    let fields: Vec<&str> = expr.split_whitespace().collect();
    if fields.len() != 5 {
        return Err(format!(
            "cron expression must have 5 fields, got {}",
            fields.len()
        ));
    }
    for (i, field) in fields.iter().enumerate() {
        if field.is_empty() {
            return Err(format!("cron field {} is empty", i));
        }
        // Each comma-separated part must be: "*", a number, a range "a-b",
        // or any of those with a "/step" suffix.
        for part in field.split(',') {
            let (base, _step) = match part.split_once('/') {
                Some((b, s)) => {
                    if s.parse::<u32>().is_err() {
                        return Err(format!("invalid cron step: {}", part));
                    }
                    (b, Some(s))
                }
                None => (part, None),
            };
            if base == "*" {
                continue;
            }
            if let Some((lo, hi)) = base.split_once('-') {
                if lo.parse::<u32>().is_err() || hi.parse::<u32>().is_err() {
                    return Err(format!("invalid cron range: {}", base));
                }
            } else if base.parse::<u32>().is_err() {
                return Err(format!("invalid cron field value: {}", base));
            }
        }
    }
    Ok(())
}

#[async_trait]
impl Tool for ScheduleTool {
    fn name(&self) -> &str {
        "Schedule"
    }

    fn description(&self) -> &str {
        "Validates a scheduled-job request (one-shot delay or cron). \
         Persistent scheduling is not available in the Rust backend yet."
    }

    fn input_schema(&self) -> &Map<String, Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &Map<String, Value>,
        _ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let prompt = input
            .get("prompt")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim();

        if prompt.is_empty() {
            return Err(ToolError::InvalidInput("prompt is required".into()));
        }

        let delay_ms = input.get("delayMs").and_then(|v| v.as_u64());
        let cron = input.get("cron").and_then(|v| v.as_str());

        let mode = match (delay_ms, cron) {
            (Some(_), Some(_)) => {
                return Err(ToolError::InvalidInput(
                    "provide either delayMs or cron, not both".into(),
                ));
            }
            (Some(ms), None) => {
                if ms == 0 {
                    return Err(ToolError::InvalidInput(
                        "delayMs must be greater than 0".into(),
                    ));
                }
                format!("one-shot in {}ms", ms)
            }
            (None, Some(expr)) => {
                validate_cron(expr).map_err(ToolError::InvalidInput)?;
                format!("recurring cron \"{}\"", expr)
            }
            (None, None) => {
                return Err(ToolError::InvalidInput(
                    "either delayMs or cron is required".into(),
                ));
            }
        };

        Ok(format!(
            "Schedule request validated ({}). \
             Note: persistent scheduling is not available in the Rust backend yet; \
             no job was registered.",
            mode
        ))
    }

    fn summarize(&self, input: &Map<String, Value>) -> String {
        let prompt = input.get("prompt").and_then(|v| v.as_str()).unwrap_or("");
        let short: String = prompt.chars().take(40).collect();
        format!("Schedule({})", short)
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
        m.insert("prompt".into(), Value::String("run something".into()));
        m
    }

    #[tokio::test]
    async fn one_shot_delay_valid() {
        let tool = ScheduleTool::new();
        let mut input = base_input();
        input.insert("delayMs".into(), Value::Number(60_000.into()));
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.contains("one-shot in 60000ms"));
    }

    #[tokio::test]
    async fn cron_valid() {
        let tool = ScheduleTool::new();
        let mut input = base_input();
        input.insert("cron".into(), Value::String("*/15 9-17 * * 1-5".into()));
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.contains("recurring cron"));
    }

    #[tokio::test]
    async fn cron_invalid() {
        let tool = ScheduleTool::new();
        let mut input = base_input();
        input.insert("cron".into(), Value::String("not a cron".into()));
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }

    #[tokio::test]
    async fn both_modes_rejected() {
        let tool = ScheduleTool::new();
        let mut input = base_input();
        input.insert("delayMs".into(), Value::Number(1000.into()));
        input.insert("cron".into(), Value::String("* * * * *".into()));
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }

    #[tokio::test]
    async fn neither_mode_rejected() {
        let tool = ScheduleTool::new();
        let result = tool.call(&base_input(), &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }
}
