//! TaskCreate tool — creates a background task entry.
//!
//! The Scala original persists tasks in a shared taskStore actor. The Rust
//! implementation uses a process-wide in-memory store (static Mutex<HashMap>),
//! which keeps TaskCreate/TaskUpdate functional within a single process run.

use async_trait::async_trait;
use serde_json::{Map, Value};
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use crate::types::{ToolContext, ToolError};
use crate::Tool;

/// A single task record in the in-memory store.
///
/// Some fields (id, session_id, description, created_at) are not read by the
/// current tool set but are part of the record schema — they will be consumed
/// by the TaskList/TaskDetail APIs and are asserted on in tests.
#[derive(Debug, Clone)]
#[allow(dead_code)]
pub(crate) struct TaskRecord {
    pub id: String,
    pub session_id: String,
    pub description: String,
    pub status: String,
    pub created_at_epoch_ms: u64,
    pub updated_at_epoch_ms: u64,
    pub result: Option<String>,
}

/// Process-wide in-memory task store.
pub(crate) fn task_store() -> &'static Mutex<HashMap<String, TaskRecord>> {
    static STORE: OnceLock<Mutex<HashMap<String, TaskRecord>>> = OnceLock::new();
    STORE.get_or_init(|| Mutex::new(HashMap::new()))
}

pub(crate) fn now_epoch_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

pub(crate) fn new_task_id() -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let n = COUNTER.fetch_add(1, Ordering::Relaxed);
    format!("task-{}-{}", now_epoch_ms(), n)
}

pub struct TaskCreateTool {
    schema: Map<String, Value>,
}

impl TaskCreateTool {
    pub fn new() -> Self {
        let schema = serde_json::json!({
            "type": "object",
            "properties": {
                "description": {
                    "type": "string",
                    "description": "Clear, concise description of the task"
                }
            },
            "required": ["description"]
        });
        Self {
            schema: schema.as_object().unwrap().clone(),
        }
    }
}

impl Default for TaskCreateTool {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Tool for TaskCreateTool {
    fn name(&self) -> &str {
        "TaskCreate"
    }

    fn description(&self) -> &str {
        "Creates a background task record. Tasks live in a process-wide \
         in-memory store (no cross-process persistence in the Rust backend yet)."
    }

    fn input_schema(&self) -> &Map<String, Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &Map<String, Value>,
        ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let description = input
            .get("description")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim();

        if description.is_empty() {
            return Err(ToolError::InvalidInput("description is required".into()));
        }

        let id = new_task_id();
        let now = now_epoch_ms();
        let record = TaskRecord {
            id: id.clone(),
            session_id: ctx.session_id.clone(),
            description: description.to_string(),
            status: "pending".into(),
            created_at_epoch_ms: now,
            updated_at_epoch_ms: now,
            result: None,
        };

        task_store()
            .lock()
            .map_err(|e| ToolError::Execution(format!("task store lock poisoned: {}", e)))?
            .insert(id.clone(), record);

        Ok(format!(
            "Task created: {} (status: pending, session: {})",
            id, ctx.session_id
        ))
    }

    fn summarize(&self, input: &Map<String, Value>) -> String {
        let desc = input
            .get("description")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        let short: String = desc.chars().take(40).collect();
        format!("TaskCreate({})", short)
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

    #[tokio::test]
    async fn creates_task_in_store() {
        let tool = TaskCreateTool::new();
        let mut input = Map::new();
        input.insert("description".into(), Value::String("do a thing".into()));
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.contains("Task created:"));

        // Extract the id and verify the record exists in the store
        let id = result
            .strip_prefix("Task created: ")
            .and_then(|s| s.split(' ').next())
            .unwrap()
            .to_string();
        let store = task_store().lock().unwrap();
        let rec = store.get(&id).expect("record should exist");
        assert_eq!(rec.description, "do a thing");
        assert_eq!(rec.status, "pending");
        assert_eq!(rec.session_id, "s1");
    }

    #[tokio::test]
    async fn empty_description_rejected() {
        let tool = TaskCreateTool::new();
        let mut input = Map::new();
        input.insert("description".into(), Value::String("   ".into()));
        let result = tool.call(&input, &ctx()).await;
        assert!(matches!(result, Err(ToolError::InvalidInput(_))));
    }
}
