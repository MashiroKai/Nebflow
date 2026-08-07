//! TaskUpdate tool — updates the status/result of an existing task.
//!
//! Shares the in-memory task store with TaskCreate (see task_create.rs).

use async_trait::async_trait;
use serde_json::{Map, Value};

use super::task_create::{now_epoch_ms, task_store};
use crate::types::{ToolContext, ToolError};
use crate::Tool;

/// Valid task statuses.
const VALID_STATUSES: &[&str] = &["pending", "running", "completed", "failed", "cancelled"];

pub struct TaskUpdateTool {
    schema: Map<String, Value>,
}

impl TaskUpdateTool {
    pub fn new() -> Self {
        let schema = serde_json::json!({
            "type": "object",
            "properties": {
                "taskId": {
                    "type": "string",
                    "description": "ID of the task to update"
                },
                "status": {
                    "type": "string",
                    "description": "New status: pending, running, completed, failed, cancelled"
                },
                "result": {
                    "type": "string",
                    "description": "Optional result/output text for the task"
                }
            },
            "required": ["taskId"]
        });
        Self {
            schema: schema.as_object().unwrap().clone(),
        }
    }
}

impl Default for TaskUpdateTool {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Tool for TaskUpdateTool {
    fn name(&self) -> &str {
        "TaskUpdate"
    }

    fn description(&self) -> &str {
        "Updates an existing task's status and/or result in the in-memory task store."
    }

    fn input_schema(&self) -> &Map<String, Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &Map<String, Value>,
        _ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let task_id = input.get("taskId").and_then(|v| v.as_str()).unwrap_or("");

        if task_id.is_empty() {
            return Err(ToolError::InvalidInput("taskId is required".into()));
        }

        let status = input.get("status").and_then(|v| v.as_str());
        let result = input.get("result").and_then(|v| v.as_str());

        if status.is_none() && result.is_none() {
            return Err(ToolError::InvalidInput(
                "at least one of status or result must be provided".into(),
            ));
        }

        if let Some(s) = status {
            if !VALID_STATUSES.contains(&s) {
                return Err(ToolError::InvalidInput(format!(
                    "invalid status \"{}\". Valid: {}",
                    s,
                    VALID_STATUSES.join(", ")
                )));
            }
        }

        let mut store = task_store()
            .lock()
            .map_err(|e| ToolError::Execution(format!("task store lock poisoned: {}", e)))?;

        let record = store
            .get_mut(task_id)
            .ok_or_else(|| ToolError::NotFound(format!("Task not found: {}", task_id)))?;

        if let Some(s) = status {
            record.status = s.to_string();
        }
        if let Some(r) = result {
            record.result = Some(r.to_string());
        }
        record.updated_at_epoch_ms = now_epoch_ms();

        Ok(format!(
            "Task {} updated: status={}, result={}",
            task_id,
            record.status,
            record
                .result
                .as_deref()
                .map(|r| format!("\"{}\"", r.chars().take(80).collect::<String>()))
                .unwrap_or_else(|| "(none)".into())
        ))
    }

    fn summarize(&self, input: &Map<String, Value>) -> String {
        let id = input.get("taskId").and_then(|v| v.as_str()).unwrap_or("");
        format!("TaskUpdate({})", id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tools::tools::task_create::TaskCreateTool;

    fn ctx() -> ToolContext {
        ToolContext {
            session_id: "s1".into(),
            agent_id: "a1".into(),
            working_directory: "/tmp".into(),
            depth: 0,
            ..Default::default()
        }
    }

    async fn create_task() -> String {
        let tool = TaskCreateTool::new();
        let mut input = Map::new();
        input.insert("description".into(), Value::String("updatable".into()));
        let result = tool.call(&input, &ctx()).await.unwrap();
        result
            .strip_prefix("Task created: ")
            .and_then(|s| s.split(' ').next())
            .unwrap()
            .to_string()
    }

    #[tokio::test]
    async fn updates_status_and_result() {
        let id = create_task().await;
        let tool = TaskUpdateTool::new();
        let mut input = Map::new();
        input.insert("taskId".into(), Value::String(id.clone()));
        input.insert("status".into(), Value::String("completed".into()));
        input.insert("result".into(), Value::String("all done".into()));
        let out = tool.call(&input, &ctx()).await.unwrap();
        assert!(out.contains("status=completed"));

        let store = task_store().lock().unwrap();
        let rec = store.get(&id).unwrap();
        assert_eq!(rec.status, "completed");
        assert_eq!(rec.result.as_deref(), Some("all done"));
    }

    #[tokio::test]
    async fn unknown_task_rejected() {
        let tool = TaskUpdateTool::new();
        let mut input = Map::new();
        input.insert("taskId".into(), Value::String("task-ghost".into()));
        input.insert("status".into(), Value::String("running".into()));
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::NotFound(_))));
    }

    #[tokio::test]
    async fn invalid_status_rejected() {
        let id = create_task().await;
        let tool = TaskUpdateTool::new();
        let mut input = Map::new();
        input.insert("taskId".into(), Value::String(id));
        input.insert("status".into(), Value::String("bogus".into()));
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }

    #[tokio::test]
    async fn no_fields_rejected() {
        let id = create_task().await;
        let tool = TaskUpdateTool::new();
        let mut input = Map::new();
        input.insert("taskId".into(), Value::String(id));
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }
}
