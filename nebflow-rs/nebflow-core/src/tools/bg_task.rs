//! BgTaskRegistry — global registry of active background tasks.
//! Mirrors `nebflow.core.tools.BgTaskRegistry` from Scala.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

/// Information about an active background task.
#[derive(Debug, Clone)]
pub struct ActiveTask {
    pub job_id: String,
    pub session_id: String,
    pub description: String,
    pub started_at_ms: u64,
    pub kind: String, // "local" | "remote"
}

/// Global registry of active background tasks (local Bash + remote).
pub struct BgTaskRegistry {
    tasks: Mutex<HashMap<String, ActiveTask>>,
}

impl BgTaskRegistry {
    pub fn new() -> Self {
        Self {
            tasks: Mutex::new(HashMap::new()),
        }
    }

    /// Register a new background task.
    pub fn register(&self, job_id: &str, session_id: &str, description: &str, kind: &str) {
        self.tasks.lock().unwrap().insert(
            job_id.to_string(),
            ActiveTask {
                job_id: job_id.to_string(),
                session_id: session_id.to_string(),
                description: description.to_string(),
                started_at_ms: SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .map(|d| d.as_millis() as u64)
                    .unwrap_or(0),
                kind: kind.to_string(),
            },
        );
    }

    /// Unregister a background task by job ID.
    pub fn unregister(&self, job_id: &str) {
        self.tasks.lock().unwrap().remove(job_id);
    }

    /// Get all active tasks.
    pub fn active_tasks(&self) -> Vec<ActiveTask> {
        self.tasks.lock().unwrap().values().cloned().collect()
    }

    /// Get active tasks for a specific session.
    pub fn tasks_for_session(&self, session_id: &str) -> Vec<ActiveTask> {
        self.tasks
            .lock()
            .unwrap()
            .values()
            .filter(|t| t.session_id == session_id)
            .cloned()
            .collect()
    }

    /// Check if a job is still active.
    pub fn is_active(&self, job_id: &str) -> bool {
        self.tasks.lock().unwrap().contains_key(job_id)
    }
}

impl Default for BgTaskRegistry {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn register_and_unregister() {
        let reg = BgTaskRegistry::new();
        reg.register("job1", "session1", "building", "local");
        assert!(reg.is_active("job1"));
        assert_eq!(reg.active_tasks().len(), 1);

        reg.unregister("job1");
        assert!(!reg.is_active("job1"));
    }

    #[test]
    fn tasks_for_session() {
        let reg = BgTaskRegistry::new();
        reg.register("job1", "session1", "build", "local");
        reg.register("job2", "session2", "test", "local");
        reg.register("job3", "session1", "deploy", "remote");

        let s1_tasks = reg.tasks_for_session("session1");
        assert_eq!(s1_tasks.len(), 2);
    }

    #[test]
    fn active_task_fields() {
        let reg = BgTaskRegistry::new();
        reg.register("job1", "session1", "running tests", "local");
        let task = reg.active_tasks().pop().unwrap();
        assert_eq!(task.job_id, "job1");
        assert_eq!(task.session_id, "session1");
        assert_eq!(task.description, "running tests");
        assert_eq!(task.kind, "local");
        assert!(task.started_at_ms > 0);
    }
}
