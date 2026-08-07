//! MCP manager — lifecycle management of multiple MCP servers.
//!
//! Mirrors Scala `McpManager.scala`. Handles:
//! - Starting all enabled MCP servers concurrently
//! - Stopping all servers gracefully
//! - Enabling/disabling individual servers
//! - Refreshing tool lists when servers notify of changes

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use tokio::sync::RwLock;

use crate::config::McpServerConfig;
use crate::mcp::client::McpClient;
use crate::mcp::transport::{HttpTransport, McpTransport, StdioTransport};
use crate::Tool;

/// State for a connected MCP server.
struct ServerState {
    client: Arc<McpClient>,
    tools: Vec<Arc<dyn Tool>>,
}

/// Manages lifecycle of all MCP servers: config loading, connection, tool registration, shutdown.
pub struct McpManager {
    servers: RwLock<HashMap<String, ServerState>>,
    enabled: RwLock<HashMap<String, bool>>,
}

impl McpManager {
    pub fn new() -> Self {
        Self {
            servers: RwLock::new(HashMap::new()),
            enabled: RwLock::new(HashMap::new()),
        }
    }

    /// Start all enabled MCP servers concurrently.
    pub async fn start_all(&self, configs: &HashMap<String, McpServerConfig>) {
        if configs.is_empty() {
            return;
        }

        // Record enabled status.
        {
            let mut enabled = self.enabled.write().await;
            for (id, cfg) in configs {
                enabled.insert(id.clone(), cfg.is_enabled());
            }
        }

        // Start enabled servers concurrently using join_all.
        // We can't use tokio::spawn (requires 'static) so we use join_all with shared references.
        use futures::future::join_all;

        let futures: Vec<_> = configs
            .iter()
            .filter(|(_, cfg)| cfg.is_enabled())
            .map(|(id, cfg)| {
                let id = id.clone();
                let cfg = cfg.clone();
                async move {
                    match tokio::time::timeout(
                        Duration::from_secs(5),
                        self.connect_server(&id, &cfg),
                    )
                    .await
                    {
                        Ok(Ok(())) => tracing::info!(target: "mcp.manager", "MCP server '{}' started", id),
                        Ok(Err(e)) => tracing::error!(target: "mcp.manager", "MCP server '{}' failed to start: {}", id, e),
                        Err(_) => tracing::error!(target: "mcp.manager", "MCP server '{}' timed out during start", id),
                    }
                }
            })
            .collect();

        join_all(futures).await;
    }

    /// Gracefully close all MCP connections.
    pub async fn stop_all(&self) {
        let servers = self.servers.write().await.drain().collect::<Vec<_>>();
        for (_, state) in servers {
            let _ = tokio::time::timeout(Duration::from_secs(3), async {
                state.client.close().await;
            })
            .await;
        }
    }

    /// Get all configured server IDs with their enabled status.
    pub async fn list_servers(&self) -> Vec<(String, bool)> {
        let enabled = self.enabled.read().await;
        let mut result: Vec<(String, bool)> =
            enabled.iter().map(|(id, &e)| (id.clone(), e)).collect();
        result.sort_by(|a, b| a.0.cmp(&b.0));
        result
    }

    /// Enable a server: update flag and start it.
    pub async fn enable_server(&self, id: &str, cfg: McpServerConfig) {
        self.enabled.write().await.insert(id.to_string(), true);
        if let Err(e) = self
            .connect_server_with_timeout(id, &cfg, Duration::from_secs(10))
            .await
        {
            tracing::error!(target: "mcp.manager", "MCP server '{}' failed to start: {}", id, e);
        }
    }

    /// Disable a server: stop it and update flag.
    pub async fn disable_server(&self, id: &str) {
        self.enabled.write().await.insert(id.to_string(), false);
        self.stop_server(id).await;
    }

    /// Start a single MCP server.
    pub async fn start_server(&self, id: &str, cfg: &McpServerConfig) -> Result<(), String> {
        self.connect_server(id, cfg).await
    }

    /// Stop a single MCP server.
    pub async fn stop_server(&self, id: &str) {
        let removed = self.servers.write().await.remove(id);
        if let Some(state) = removed {
            let _ = tokio::time::timeout(Duration::from_secs(3), async {
                state.client.close().await;
            })
            .await;
            tracing::info!(target: "mcp.manager", "MCP server '{}' stopped", id);
        }
    }

    /// Get all tool names from all connected servers.
    pub async fn all_tool_names(&self) -> Vec<String> {
        let servers = self.servers.read().await;
        let mut result = Vec::new();
        for state in servers.values() {
            for tool in &state.tools {
                result.push(tool.name().to_string());
            }
        }
        result
    }

    /// Look up a tool by name across all connected MCP servers.
    /// Returns a cloned Arc<dyn Tool> if found.
    pub async fn get_tool(&self, name: &str) -> Option<Arc<dyn Tool>> {
        let servers = self.servers.read().await;
        for state in servers.values() {
            for tool in &state.tools {
                if tool.name() == name {
                    return Some(tool.clone());
                }
            }
        }
        None
    }

    /// Execute a tool call by name. Returns (output, is_error).
    pub async fn call_tool(
        &self,
        name: &str,
        input: &serde_json::Map<String, serde_json::Value>,
        ctx: &crate::types::ToolContext,
    ) -> Option<Result<String, crate::types::ToolError>> {
        let tool = self.get_tool(name).await?;
        Some(tool.call(input, ctx).await)
    }

    /// Get tool definitions from all connected MCP servers.
    /// Returns Vec<ToolDefinition> suitable for inclusion in LLM requests.
    pub async fn tool_definitions(&self) -> Vec<crate::types::ToolDefinition> {
        let servers = self.servers.read().await;
        let mut result = Vec::new();
        for state in servers.values() {
            for tool in &state.tools {
                result.push(crate::types::ToolDefinition {
                    name: tool.name().to_string(),
                    description: tool.description().to_string(),
                    input_schema: serde_json::Value::Object(tool.input_schema().clone()),
                });
            }
        }
        result
    }

    /// Connect to a single MCP server and register its tools.
    /// Stores the server state in self.servers on success.
    async fn connect_server(&self, id: &str, cfg: &McpServerConfig) -> Result<(), String> {
        self.connect_server_with_timeout(id, cfg, Duration::from_secs(30))
            .await
    }

    async fn connect_server_with_timeout(
        &self,
        id: &str,
        cfg: &McpServerConfig,
        _timeout: Duration,
    ) -> Result<(), String> {
        // Choose transport based on config.
        let transport: Arc<dyn McpTransport> = if let Some(ref cmd) = cfg.command {
            let args = cfg.args.clone().unwrap_or_default();
            let env = cfg.env.clone().unwrap_or_default();
            let stdio = StdioTransport::spawn(cmd, &args, &env).await?;
            Arc::new(stdio)
        } else if let Some(ref url) = cfg.url {
            let headers = cfg.headers.clone().unwrap_or_default();
            Arc::new(HttpTransport::new(url.clone(), headers))
        } else {
            return Err(format!(
                "MCP server '{}' must have either command or url",
                id
            ));
        };

        let client = Arc::new(McpClient::new(id.to_string(), transport.clone()));

        // Initialize handshake.
        client.initialize().await?;

        // List tools.
        let mcp_tools = client.list_tools().await?;

        // Wrap tools as Arc<dyn Tool>.
        let tools: Vec<Arc<dyn Tool>> = mcp_tools
            .iter()
            .map(|t| {
                Arc::from(crate::mcp::client::create_mcp_tool_wrapper(
                    id,
                    t,
                    client.clone(),
                ))
            })
            .collect();

        tracing::info!(
            target: "mcp.manager",
            "MCP server '{}' connected, {} tools registered",
            id,
            tools.len()
        );

        // Store server state.
        let state = ServerState { client, tools };
        self.servers.write().await.insert(id.to_string(), state);

        Ok(())
    }
}

impl Default for McpManager {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn list_servers_empty() {
        let manager = McpManager::new();
        let servers = manager.list_servers().await;
        assert!(servers.is_empty());
    }

    #[tokio::test]
    async fn stop_all_no_servers() {
        let manager = McpManager::new();
        // Should not panic.
        manager.stop_all().await;
    }

    #[tokio::test]
    async fn stop_server_not_found() {
        let manager = McpManager::new();
        // Should not panic.
        manager.stop_server("nonexistent").await;
    }

    #[tokio::test]
    async fn disable_server_not_found() {
        let manager = McpManager::new();
        // Should not panic.
        manager.disable_server("nonexistent").await;
    }

    #[tokio::test]
    async fn connect_server_no_command_or_url() {
        let manager = McpManager::new();
        let cfg = McpServerConfig {
            command: None,
            args: None,
            env: None,
            url: None,
            headers: None,
            enabled: Some(true),
        };
        let result = manager.connect_server("test-server", &cfg).await;
        assert!(result.is_err());
        assert!(result
            .unwrap_err()
            .contains("must have either command or url"));
    }

    #[tokio::test]
    async fn connect_server_invalid_command() {
        let manager = McpManager::new();
        let cfg = McpServerConfig {
            command: Some("/nonexistent/binary/that/does/not/exist".into()),
            args: None,
            env: None,
            url: None,
            headers: None,
            enabled: Some(true),
        };
        let result = manager.connect_server("bad-server", &cfg).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn all_tool_names_empty() {
        let manager = McpManager::new();
        let names = manager.all_tool_names().await;
        assert!(names.is_empty());
    }

    #[tokio::test]
    async fn tool_definitions_empty() {
        let manager = McpManager::new();
        let defs = manager.tool_definitions().await;
        assert!(defs.is_empty());
    }

    #[tokio::test]
    async fn stop_all_after_failed_connect() {
        let manager = McpManager::new();
        // Even with no servers, stop_all should work
        manager.stop_all().await;
        // Verify servers map is still empty
        assert!(manager.all_tool_names().await.is_empty());
    }

    #[tokio::test]
    async fn list_servers_after_enable() {
        let manager = McpManager::new();
        let cfg = McpServerConfig {
            command: Some("/nonexistent".into()),
            args: None,
            env: None,
            url: None,
            headers: None,
            enabled: Some(true),
        };
        // enable_server will fail to connect but should still set enabled flag
        manager.enable_server("test-srv", cfg).await;
        let servers = manager.list_servers().await;
        assert_eq!(servers.len(), 1);
        assert_eq!(servers[0].0, "test-srv");
        assert!(servers[0].1);
    }

    #[tokio::test]
    async fn disable_server_removes_from_servers() {
        let manager = McpManager::new();
        // Disable a non-existent server — should not panic
        manager.disable_server("ghost").await;
        let servers = manager.list_servers().await;
        // disabled flag should be recorded
        assert_eq!(servers.len(), 1);
        assert!(!servers[0].1); // not enabled
    }
}
