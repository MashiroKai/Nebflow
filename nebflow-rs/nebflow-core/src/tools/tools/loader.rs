//! ToolLoader — loads external tool definitions from JSON config + scripts.
//! Mirrors `nebflow.core.tools.ToolLoader` from Scala.
//!
//! External tools are defined as JSON files with:
//! - name, description, inputSchema
//! - A command template that gets executed with the tool's input

use crate::types::{ToolContext, ToolError};
use crate::Tool;
use async_trait::async_trait;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;

/// Configuration for an external tool loaded from JSON.
#[derive(Debug, Clone, serde::Deserialize)]
pub struct ExternalToolConfig {
    pub name: String,
    pub description: String,
    #[serde(rename = "inputSchema")]
    pub input_schema: serde_json::Value,
    /// Command template: `{{input}}` gets replaced with the JSON-serialized input.
    #[serde(default)]
    pub command: Option<String>,
    /// Script path relative to the tool config directory.
    #[serde(default)]
    pub script: Option<String>,
    /// Working directory for the command.
    #[serde(default)]
    pub cwd: Option<String>,
    /// Timeout in milliseconds.
    #[serde(default = "default_timeout")]
    pub timeout_ms: u64,
}

fn default_timeout() -> u64 {
    30_000
}

/// A tool loaded from an external JSON configuration.
pub struct ExternalTool {
    config: ExternalToolConfig,
    schema: serde_json::Map<String, serde_json::Value>,
    config_dir: PathBuf,
}

impl ExternalTool {
    pub fn new(config: ExternalToolConfig, config_dir: PathBuf) -> Result<Self, ToolError> {
        let schema = match config.input_schema.as_object() {
            Some(obj) => obj.clone(),
            None => {
                return Err(ToolError::InvalidInput(format!(
                    "Tool '{}' inputSchema must be a JSON object",
                    config.name
                )));
            }
        };
        Ok(Self {
            config,
            schema,
            config_dir,
        })
    }
}

#[async_trait]
impl Tool for ExternalTool {
    fn name(&self) -> &str {
        &self.config.name
    }

    fn description(&self) -> &str {
        &self.config.description
    }

    fn input_schema(&self) -> &serde_json::Map<String, serde_json::Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &serde_json::Map<String, serde_json::Value>,
        _ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let input_json = serde_json::to_string(input)
            .map_err(|e| ToolError::Execution(format!("Failed to serialize input: {e}")))?;

        // Execute command or script
        let cmd_str = if let Some(script) = &self.config.script {
            let script_path = self.config_dir.join(script);
            format!("{} {}", script_path.display(), shell_escape(&input_json))
        } else if let Some(command) = &self.config.command {
            command.replace("{{input}}", &shell_escape(&input_json))
        } else {
            return Err(ToolError::InvalidInput(format!(
                "Tool '{}' has no command or script",
                self.config.name
            )));
        };

        let cwd = self.config.cwd.as_deref().unwrap_or(".");

        let output = tokio::process::Command::new("bash")
            .arg("-c")
            .arg(&cmd_str)
            .current_dir(cwd)
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .output()
            .await
            .map_err(|e| ToolError::Execution(format!("Failed to execute tool: {e}")))?;

        let stdout = String::from_utf8_lossy(&output.stdout);
        let stderr = String::from_utf8_lossy(&output.stderr);
        let exit_code = output.status.code().unwrap_or(-1);

        if exit_code != 0 {
            let err_msg = if !stderr.trim().is_empty() {
                stderr.trim().to_string()
            } else {
                format!("Tool exited with code {exit_code}")
            };
            return Err(ToolError::Execution(err_msg));
        }

        Ok(stdout.trim().to_string())
    }
}

/// Shell-escape a string for safe inclusion in a bash command.
fn shell_escape(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\"'\"'"))
}

/// Load external tools from a directory.
/// Each `.json` file in the directory defines one tool.
pub fn load_tools_from_dir(dir: &PathBuf) -> Vec<Arc<dyn Tool>> {
    let mut tools = Vec::new();

    let Ok(entries) = fs::read_dir(dir) else {
        return tools;
    };

    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_file() || path.extension().and_then(|e| e.to_str()) != Some("json") {
            continue;
        }

        let Ok(content) = fs::read_to_string(&path) else {
            continue;
        };

        match serde_json::from_str::<ExternalToolConfig>(&content) {
            Ok(config) => {
                let config_dir = path.parent().unwrap_or(dir).to_path_buf();
                match ExternalTool::new(config, config_dir) {
                    Ok(tool) => tools.push(Arc::new(tool) as Arc<dyn Tool>),
                    Err(e) => {
                        tracing::warn!(
                            "Failed to create external tool from {}: {}",
                            path.display(),
                            e
                        );
                    }
                }
            }
            Err(e) => {
                tracing::warn!(
                    "Failed to parse external tool config {}: {}",
                    path.display(),
                    e
                );
            }
        }
    }

    tools
}

/// Load tools from multiple layers: global, agent, team, flow.
pub fn load_all_layers(
    data_root: &Path,
    agent_name: Option<&str>,
    team_name: Option<&str>,
) -> Vec<Arc<dyn Tool>> {
    let mut tools = Vec::new();

    // Global tools: ~/.nebflow/tools/
    let global_dir = data_root.join("tools");
    if global_dir.is_dir() {
        tools.extend(load_tools_from_dir(&global_dir));
    }

    // Agent tools: ~/.nebflow/agents/<name>/tools/
    if let Some(name) = agent_name {
        let agent_dir = data_root.join("agents").join(name).join("tools");
        if agent_dir.is_dir() {
            tools.extend(load_tools_from_dir(&agent_dir));
        }
    }

    // Team tools: ~/.nebflow/teams/<name>/tools/
    if let Some(name) = team_name {
        let team_dir = data_root.join("teams").join(name).join("tools");
        if team_dir.is_dir() {
            tools.extend(load_tools_from_dir(&team_dir));
        }
    }

    tools
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx() -> ToolContext {
        ToolContext {
            session_id: "test".into(),
            agent_id: "test".into(),
            working_directory: "/tmp".into(),
            depth: 0,
            ..Default::default()
        }
    }

    #[test]
    fn load_from_nonexistent_dir() {
        let tools = load_tools_from_dir(&PathBuf::from("/nonexistent/path"));
        assert!(tools.is_empty());
    }

    #[test]
    fn load_from_empty_dir() {
        let tmp = tempfile::tempdir().unwrap();
        let tools = load_tools_from_dir(&tmp.path().to_path_buf());
        assert!(tools.is_empty());
    }

    #[test]
    fn load_valid_tool_config() {
        let tmp = tempfile::tempdir().unwrap();
        let config = r#"{
            "name": "echo-tool",
            "description": "Echoes input",
            "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}},
            "command": "echo {{input}}"
        }"#;
        fs::write(tmp.path().join("echo.json"), config).unwrap();

        let tools = load_tools_from_dir(&tmp.path().to_path_buf());
        assert_eq!(tools.len(), 1);
        assert_eq!(tools[0].name(), "echo-tool");
    }

    #[test]
    fn load_invalid_json_skipped() {
        let tmp = tempfile::tempdir().unwrap();
        fs::write(tmp.path().join("bad.json"), "not valid json").unwrap();
        fs::write(
            tmp.path().join("good.json"),
            r#"{"name": "good", "description": "test", "inputSchema": {"type": "object"}}"#,
        )
        .unwrap();

        let tools = load_tools_from_dir(&tmp.path().to_path_buf());
        assert_eq!(tools.len(), 1);
        assert_eq!(tools[0].name(), "good");
    }

    #[test]
    fn load_skips_non_json_files() {
        let tmp = tempfile::tempdir().unwrap();
        fs::write(tmp.path().join("readme.md"), "# Tools").unwrap();
        fs::write(
            tmp.path().join("tool.json"),
            r#"{"name": "test", "description": "test", "inputSchema": {"type": "object"}}"#,
        )
        .unwrap();

        let tools = load_tools_from_dir(&tmp.path().to_path_buf());
        assert_eq!(tools.len(), 1);
    }

    #[tokio::test]
    async fn external_tool_execute() {
        let tmp = tempfile::tempdir().unwrap();
        let config = r#"{
            "name": "echo-test",
            "description": "Echo tool",
            "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}},
            "command": "echo hello-from-tool"
        }"#;
        fs::write(tmp.path().join("echo.json"), config).unwrap();

        let tools = load_tools_from_dir(&tmp.path().to_path_buf());
        assert_eq!(tools.len(), 1);

        let input = serde_json::Map::new();
        let result = tools[0].call(&input, &ctx()).await.unwrap();
        assert!(result.contains("hello-from-tool"));
    }

    #[test]
    fn shell_escape_basic() {
        assert_eq!(shell_escape("hello"), "'hello'");
        assert_eq!(shell_escape("it's"), "'it'\"'\"'s'");
    }

    #[test]
    fn load_all_layers_empty() {
        let tmp = tempfile::tempdir().unwrap();
        let tools = load_all_layers(tmp.path(), None, None);
        assert!(tools.is_empty());
    }

    #[test]
    fn load_all_layers_agent() {
        let tmp = tempfile::tempdir().unwrap();
        let agent_dir = tmp.path().join("agents").join("test-agent").join("tools");
        fs::create_dir_all(&agent_dir).unwrap();
        fs::write(
            agent_dir.join("custom.json"),
            r#"{"name": "custom", "description": "test", "inputSchema": {"type": "object"}}"#,
        )
        .unwrap();

        let tools = load_all_layers(tmp.path(), Some("test-agent"), None);
        assert_eq!(tools.len(), 1);
        assert_eq!(tools[0].name(), "custom");
    }
}
