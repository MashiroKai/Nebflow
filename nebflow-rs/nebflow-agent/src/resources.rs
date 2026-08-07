//! Shared resources container — mirrors Scala SharedResources.
//!
//! Holds shared singletons available to all agent runtime tasks:
//! LLM handle, HTTP client, session store, config, tool registry, etc.

use std::path::PathBuf;
use std::sync::Arc;

use nebflow_core::config::NebflowServiceConfig;
use nebflow_core::config::ThinkingConfig;
use nebflow_core::llm::{HealthMonitor, LlmHandle, ProviderRegistry};
use nebflow_core::mcp::McpManager;
use nebflow_core::tools::file_history::FileHistory;
use nebflow_core::tools::file_lock::FileLockManager;
use nebflow_core::tools::read_tracker::ReadTracker;
use nebflow_core::tools::ToolRegistry;
use nebflow_core::types::DeviceTransfer;
use tokio::sync::RwLock;

/// Shared resources available to all agent runtime tasks.
pub struct SharedResources {
    pub llm: Arc<LlmHandle>,
    pub registry: Arc<ProviderRegistry>,
    pub health: Arc<HealthMonitor>,
    pub config: Arc<NebflowServiceConfig>,
    pub thinking_config: Arc<RwLock<ThinkingConfig>>,
    pub context_window: usize,
    pub data_root: PathBuf,

    // ── Tool system ──
    pub tool_registry: Arc<ToolRegistry>,
    pub read_tracker: Arc<ReadTracker>,
    pub file_history: Arc<FileHistory>,
    pub file_lock_manager: Arc<FileLockManager>,

    // ── MCP ──
    pub mcp_manager: Arc<McpManager>,

    // ── NebLink device transfer ──
    pub device_transfer: Option<Arc<dyn DeviceTransfer>>,

    // ── Project root ──
    pub project_root: Option<PathBuf>,
}

impl SharedResources {
    pub fn new(
        config: NebflowServiceConfig,
        data_root: PathBuf,
        thinking_config: ThinkingConfig,
    ) -> Self {
        let registry = Arc::new(ProviderRegistry::new(config.clone()));
        let health = Arc::new(HealthMonitor::new());
        let llm = Arc::new(LlmHandle::new(registry.clone(), health.clone()));

        let context_window = registry
            .get_candidates()
            .first()
            .map(|c| c.context_window)
            .unwrap_or(nebflow_core::config::CONTEXT_WINDOW);

        let tool_registry = Arc::new(ToolRegistry::builtin());
        let read_tracker = Arc::new(ReadTracker::new());
        let file_history = Arc::new(FileHistory::default_path());
        let file_lock_manager = Arc::new(FileLockManager::new());
        let mcp_manager = Arc::new(McpManager::new());

        Self {
            llm,
            registry,
            health,
            config: Arc::new(config),
            thinking_config: Arc::new(RwLock::new(thinking_config)),
            context_window,
            data_root,
            tool_registry,
            read_tracker,
            file_history,
            file_lock_manager,
            mcp_manager,
            device_transfer: None,
            project_root: None,
        }
    }

    /// Set the project root directory.
    pub fn with_project_root(mut self, root: PathBuf) -> Self {
        self.project_root = Some(root);
        self
    }

    /// Set the device transfer implementation (for NebLink cross-device transfers).
    pub fn with_device_transfer(mut self, transfer: Arc<dyn DeviceTransfer>) -> Self {
        self.device_transfer = Some(transfer);
        self
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_shared_resources() {
        let config_json = r#"{
            "llm": {
                "providers": {
                    "anthropic": {
                        "baseUrl": "http://localhost:1",
                        "apiKey": "sk-test",
                        "protocol": "anthropic",
                        "models": [{"id": "claude", "maxTokens": 16384, "contextWindow": 200000}]
                    }
                },
                "model": {"default": "anthropic/claude"}
            }
        }"#;
        let config: NebflowServiceConfig = serde_json::from_str(config_json).unwrap();
        let resources = SharedResources::new(
            config,
            PathBuf::from("/tmp"),
            ThinkingConfig {
                enabled: true,
                budget_tokens: 32000,
            },
        );
        assert_eq!(resources.context_window, 200000);
        assert!(resources.tool_registry.get("Read").is_some());
        assert!(resources.project_root.is_none());
    }

    #[test]
    fn shared_resources_with_project_root() {
        let config_json = r#"{
            "llm": {
                "providers": {
                    "anthropic": {
                        "baseUrl": "http://localhost:1",
                        "apiKey": "sk-test",
                        "protocol": "anthropic",
                        "models": [{"id": "claude", "maxTokens": 16384, "contextWindow": 200000}]
                    }
                },
                "model": {"default": "anthropic/claude"}
            }
        }"#;
        let config: NebflowServiceConfig = serde_json::from_str(config_json).unwrap();
        let resources = SharedResources::new(
            config,
            PathBuf::from("/tmp"),
            ThinkingConfig {
                enabled: true,
                budget_tokens: 32000,
            },
        )
        .with_project_root(PathBuf::from("/home/user/project"));
        assert_eq!(
            resources.project_root.as_deref(),
            Some(std::path::Path::new("/home/user/project"))
        );
    }
}
