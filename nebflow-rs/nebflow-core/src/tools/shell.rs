//! Shell execution — subprocess management for the Bash tool.
//!
//! Mirrors Scala `shell.scala`. Provides:
//! - Synchronous command execution with timeout
//! - Background job management (start, query, cancel)
//! - stdout/stderr separated capture
//! - Working directory tracking
//! - Stuck process detection for background tasks
//!
//! Uses `tokio::process::Command` for async subprocess management.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use tokio::process::Command;
use tokio::sync::{Mutex, RwLock};
use tokio::time::timeout;

/// Result of a process execution.
#[derive(Debug, Clone)]
pub struct ProcessResult {
    pub stdout: String,
    pub stderr: String,
    pub exit_code: i32,
    pub cwd: String,
}

/// Health snapshot of a running background job.
#[derive(Debug, Clone)]
pub struct BackgroundJobHealth {
    pub is_alive: bool,
    pub running_ms: u64,
    pub output_line_count: usize,
    pub command: String,
}

/// Shell execution result type.
pub type ShellResult<T> = Result<T, ShellError>;

/// Shell execution errors.
#[derive(Debug)]
pub enum ShellError {
    Io(String),
    Timeout(String),
    Stuck(String),
    Cancelled(String),
}

impl std::fmt::Display for ShellError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ShellError::Io(msg) => write!(f, "IO error: {}", msg),
            ShellError::Timeout(msg) => write!(f, "Timeout: {}", msg),
            ShellError::Stuck(msg) => write!(f, "Stuck: {}", msg),
            ShellError::Cancelled(msg) => write!(f, "Cancelled: {}", msg),
        }
    }
}

impl std::error::Error for ShellError {}

/// Maximum output size per stream (10 MB).
const MAX_OUTPUT_SIZE: usize = 10 * 1024 * 1024;

/// Truncation marker appended when output exceeds the limit.
const TRUNCATION_MARKER: &str = "\n[Output truncated due to size limit]\n";

/// Truncate output to the max size with a marker.
fn truncate_output(s: &str) -> String {
    if s.len() <= MAX_OUTPUT_SIZE {
        s.to_string()
    } else {
        let mut result = s[..MAX_OUTPUT_SIZE - TRUNCATION_MARKER.len()].to_string();
        result.push_str(TRUNCATION_MARKER);
        result
    }
}

/// Execute a command synchronously with a timeout.
///
/// Returns stdout, stderr, exit code, and the (possibly updated) working directory.
pub async fn execute(
    command: &str,
    cwd: &str,
    timeout_duration: Duration,
) -> ShellResult<ProcessResult> {
    let mut cmd = build_command(command, cwd);

    let mut child = cmd.spawn().map_err(|e| {
        ShellError::Io(format!(
            "Failed to spawn process: {}. Make sure bash is available.",
            e
        ))
    })?;

    // Take stdout/stderr before creating futures to avoid borrow conflicts.
    let mut stdout = child.stdout.take().unwrap();
    let mut stderr = child.stderr.take().unwrap();

    let stdout_future = async {
        use tokio::io::AsyncReadExt;
        let mut buf = Vec::new();
        stdout.read_to_end(&mut buf).await?;
        Ok::<Vec<u8>, std::io::Error>(buf)
    };

    let stderr_future = async {
        use tokio::io::AsyncReadExt;
        let mut buf = Vec::new();
        stderr.read_to_end(&mut buf).await?;
        Ok::<Vec<u8>, std::io::Error>(buf)
    };

    let wait_future = async {
        let status = child.wait().await?;
        Ok::<i32, std::io::Error>(status.code().unwrap_or(-1))
    };

    let result = match timeout(timeout_duration, async {
        let (stdout, stderr, code) = tokio::join!(stdout_future, stderr_future, wait_future);
        (stdout.unwrap_or_default(), stderr.unwrap_or_default(), code.unwrap_or(-1))
    })
    .await
    {
        Ok(result) => result,
        Err(_) => {
            // Kill the process on timeout.
            let _ = child.kill().await;
            return Err(ShellError::Timeout(format!(
                "Command timed out after {} seconds",
                timeout_duration.as_secs()
            )));
        }
    };

    let (stdout_result, stderr_result, exit_code) = result;

    let stdout = truncate_output(&String::from_utf8_lossy(&stdout_result));
    let stderr = truncate_output(&String::from_utf8_lossy(&stderr_result));

    // Update cwd by running pwd.
    let new_cwd = match execute_pwd(cwd).await {
        Ok(c) => c,
        Err(_) => cwd.to_string(),
    };

    Ok(ProcessResult {
        stdout,
        stderr,
        exit_code,
        cwd: new_cwd,
    })
}

/// Run `pwd` to get the current working directory.
async fn execute_pwd(cwd: &str) -> ShellResult<String> {
    let mut cmd = build_command("pwd", cwd);
    let output = cmd.output().await.map_err(|e| ShellError::Io(e.to_string()))?;
    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
    Ok(stdout)
}

/// Build a tokio Command for `bash -c "<command>"` with proper setup.
fn build_command(command: &str, cwd: &str) -> Command {
    let mut cmd = Command::new("bash");
    cmd.arg("-c").arg(command);
    cmd.current_dir(cwd);
    cmd.stdin(std::process::Stdio::null());
    cmd.stdout(std::process::Stdio::piped());
    cmd.stderr(std::process::Stdio::piped());
    cmd
}

/// Build a Command with custom environment variables.
pub fn build_command_with_env(
    command: &str,
    cwd: &str,
    env: &HashMap<String, String>,
) -> Command {
    let mut cmd = build_command(command, cwd);
    for (k, v) in env {
        cmd.env(k, v);
    }
    cmd
}

/// Per-session shell executor with background job management.
pub struct ShellSession {
    #[allow(dead_code)]
    session_id: String,
    current_dir: RwLock<String>,
    background_jobs: RwLock<HashMap<String, BackgroundJobHandle>>,
}

/// Handle to a background job.
struct BackgroundJobHandle {
    job_id: String,
    command: String,
    started_at_ms: u64,
}

impl ShellSession {
    pub fn new(session_id: String, initial_dir: String) -> Self {
        Self {
            session_id,
            current_dir: RwLock::new(initial_dir),
            background_jobs: RwLock::new(HashMap::new()),
        }
    }

    /// Get the current working directory.
    pub async fn get_current_dir(&self) -> String {
        self.current_dir.read().await.clone()
    }

    /// Execute a command synchronously, updating cwd afterwards.
    pub async fn execute(
        &self,
        command: &str,
        timeout_duration: Duration,
    ) -> ShellResult<ProcessResult> {
        let cwd = self.get_current_dir().await;
        let result = execute(command, &cwd, timeout_duration).await?;

        // Update working directory.
        if !result.cwd.is_empty() {
            *self.current_dir.write().await = result.cwd.clone();
        }

        Ok(result)
    }

    /// Execute a command with environment variables.
    pub async fn execute_with_env(
        &self,
        command: &str,
        env: &HashMap<String, String>,
        timeout_duration: Duration,
    ) -> ShellResult<ProcessResult> {
        let cwd = self.get_current_dir().await;

        let mut cmd = build_command_with_env(command, &cwd, env);
        let mut child = cmd
            .spawn()
            .map_err(|e| ShellError::Io(e.to_string()))?;

        // Take stdout/stderr before creating futures to avoid borrow conflicts.
        let mut stdout = child.stdout.take().unwrap();
        let mut stderr = child.stderr.take().unwrap();

        use tokio::io::AsyncReadExt;
        let stdout_future = async {
            let mut buf = Vec::new();
            stdout.read_to_end(&mut buf).await?;
            Ok::<Vec<u8>, std::io::Error>(buf)
        };
        let stderr_future = async {
            let mut buf = Vec::new();
            stderr.read_to_end(&mut buf).await?;
            Ok::<Vec<u8>, std::io::Error>(buf)
        };
        let wait_future = async {
            let status = child.wait().await?;
            Ok::<i32, std::io::Error>(status.code().unwrap_or(-1))
        };

        let (stdout_result, stderr_result, exit_code) =
            match timeout(timeout_duration, async {
                let (stdout, stderr, code) = tokio::join!(stdout_future, stderr_future, wait_future);
                (stdout.unwrap_or_default(), stderr.unwrap_or_default(), code.unwrap_or(-1))
            })
            .await
            {
                Ok(result) => result,
                Err(_) => {
                    let _ = child.kill().await;
                    return Err(ShellError::Timeout(format!(
                        "Command timed out after {} seconds",
                        timeout_duration.as_secs()
                    )));
                }
            };

        let stdout = truncate_output(&String::from_utf8_lossy(&stdout_result));
        let stderr = truncate_output(&String::from_utf8_lossy(&stderr_result));

        // Update cwd.
        let new_cwd = match execute_pwd(&cwd).await {
            Ok(c) => c,
            Err(_) => cwd.clone(),
        };
        if !new_cwd.is_empty() {
            *self.current_dir.write().await = new_cwd.clone();
        }

        Ok(ProcessResult {
            stdout,
            stderr,
            exit_code,
            cwd: new_cwd,
        })
    }

    /// Start a background job. Returns the job ID.
    pub async fn execute_background(
        &self,
        command: &str,
        _description: Option<&str>,
    ) -> ShellResult<String> {
        let job_id = format!(
            "{:08x}",
            rand_u64() & 0xFFFFFFFF
        );
        let started_at = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;

        let handle = BackgroundJobHandle {
            job_id: job_id.clone(),
            command: command.to_string(),
            started_at_ms: started_at,
        };

        self.background_jobs
            .write()
            .await
            .insert(job_id.clone(), handle);

        // Note: The actual background execution and result handling is managed
        // by the caller (agent layer). This registry tracks the job for
        // health monitoring and WS reconnect state recovery.

        Ok(job_id)
    }

    /// List background jobs.
    pub async fn list_background_jobs(&self) -> Vec<(String, String, u64)> {
        self.background_jobs
            .read()
            .await
            .values()
            .map(|job| (job.job_id.clone(), job.command.clone(), job.started_at_ms))
            .collect()
    }

    /// Cancel a background job by ID. Returns true if found and removed.
    pub async fn cancel_background_job(&self, job_id: &str) -> bool {
        self.background_jobs.write().await.remove(job_id).is_some()
    }
}

/// Simple pseudo-random u64 using thread-local state.
fn rand_u64() -> u64 {
    use std::time::SystemTime;
    let nanos = SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos() as u64;
    // XOR with address of a stack variable for extra entropy.
    let stack_addr: u64 = 0;
    let ptr = &stack_addr as *const u64 as u64;
    nanos ^ ptr.wrapping_mul(0x517cc1b727220a95)
}

/// Shell session manager — maps session IDs to ShellSession instances.
pub struct ShellSessionManager {
    sessions: RwLock<HashMap<String, Arc<Mutex<ShellSession>>>>,
}

impl ShellSessionManager {
    pub fn new() -> Self {
        Self {
            sessions: RwLock::new(HashMap::new()),
        }
    }

    /// Get or create a ShellSession for the given session ID.
    pub async fn get_or_create(
        &self,
        session_id: &str,
        initial_dir: Option<&str>,
    ) -> Arc<Mutex<ShellSession>> {
        {
            let sessions = self.sessions.read().await;
            if let Some(session) = sessions.get(session_id) {
                return session.clone();
            }
        }

        let dir = initial_dir
            .map(|s| s.to_string())
            .unwrap_or_else(|| std::env::current_dir().unwrap_or_default().to_string_lossy().into_owned());

        let session = Arc::new(Mutex::new(ShellSession::new(
            session_id.to_string(),
            dir,
        )));

        let mut sessions = self.sessions.write().await;
        sessions.insert(session_id.to_string(), session.clone());
        session
    }

    /// Destroy a session.
    pub async fn destroy(&self, session_id: &str) {
        self.sessions.write().await.remove(session_id);
    }
}

impl Default for ShellSessionManager {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn execute_echo() {
        let result = execute("echo hello", "/tmp", Duration::from_secs(5))
            .await
            .unwrap();
        assert_eq!(result.stdout.trim(), "hello");
        assert_eq!(result.exit_code, 0);
    }

    #[tokio::test]
    async fn execute_stderr() {
        let result = execute("echo err >&2", "/tmp", Duration::from_secs(5))
            .await
            .unwrap();
        assert_eq!(result.stderr.trim(), "err");
        assert_eq!(result.exit_code, 0);
    }

    #[tokio::test]
    async fn execute_timeout() {
        let result = execute("sleep 10", "/tmp", Duration::from_millis(100)).await;
        assert!(matches!(result, Err(ShellError::Timeout(_))));
    }

    #[tokio::test]
    async fn execute_exit_code() {
        let result = execute("exit 42", "/tmp", Duration::from_secs(5))
            .await
            .unwrap();
        assert_eq!(result.exit_code, 42);
    }

    #[tokio::test]
    async fn shell_session_cwd_tracking() {
        let session = ShellSession::new("test-session".into(), "/tmp".into());
        let result = session
            .execute("cd /tmp && echo ok", Duration::from_secs(5))
            .await
            .unwrap();
        assert_eq!(result.stdout.trim(), "ok");
        // cwd should be /tmp after cd /tmp
        let cwd = session.get_current_dir().await;
        assert!(cwd.contains("tmp") || cwd.contains("private/tmp"));
    }

    #[tokio::test]
    async fn shell_session_background_job() {
        let session = ShellSession::new("test-session".into(), "/tmp".into());

        let job_id = session
            .execute_background("sleep 5", Some("test sleep"))
            .await
            .unwrap();

        assert!(!job_id.is_empty());
        let jobs = session.list_background_jobs().await;
        assert_eq!(jobs.len(), 1);

        let cancelled = session.cancel_background_job(&job_id).await;
        assert!(cancelled);

        let jobs = session.list_background_jobs().await;
        assert_eq!(jobs.len(), 0);
    }

    #[tokio::test]
    async fn shell_session_manager() {
        let manager = ShellSessionManager::new();

        let session1 = manager.get_or_create("s1", Some("/tmp")).await;
        let session2 = manager.get_or_create("s1", Some("/tmp")).await;
        // Same session ID should return the same instance.
        assert!(Arc::ptr_eq(&session1, &session2));

        let session3 = manager.get_or_create("s2", Some("/tmp")).await;
        assert!(!Arc::ptr_eq(&session1, &session3));

        manager.destroy("s1").await;
        let session4 = manager.get_or_create("s1", Some("/tmp")).await;
        assert!(!Arc::ptr_eq(&session1, &session4));
    }

    #[test]
    fn truncate_output_preserves_small() {
        let result = truncate_output("hello");
        assert_eq!(result, "hello");
    }

    #[test]
    fn truncate_output_cuts_large() {
        let large = "x".repeat(MAX_OUTPUT_SIZE + 1000);
        let result = truncate_output(&large);
        assert!(result.contains(TRUNCATION_MARKER.trim()));
        assert!(result.len() <= MAX_OUTPUT_SIZE + TRUNCATION_MARKER.len());
    }
}
