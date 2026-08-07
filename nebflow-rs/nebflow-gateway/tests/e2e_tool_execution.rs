//! E2E: Tool execution — tests that tool calls are routed correctly.
//!
//! Since we can't mock the LLM to return tool_calls, we test the tool
//! execution path directly by constructing a ToolContext and calling
//! tools through the ToolRegistry. This verifies the wiring from
//! AgentRunner → ToolRegistry → Tool → result.

use nebflow_agent::resources::SharedResources;
use nebflow_core::config::{NebflowServiceConfig, ThinkingConfig};
use nebflow_core::tools::ToolRegistry;
use nebflow_core::types::ToolContext;
use std::sync::Arc;
use tempfile::tempdir;

fn test_config() -> NebflowServiceConfig {
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
    serde_json::from_str(config_json).unwrap()
}

fn make_resources() -> Arc<SharedResources> {
    Arc::new(SharedResources::new(
        test_config(),
        std::path::PathBuf::from("/tmp"),
        ThinkingConfig {
            enabled: false,
            budget_tokens: 0,
        },
    ))
}

fn make_ctx(resources: &Arc<SharedResources>) -> ToolContext {
    ToolContext {
        session_id: "e2e-tool-test".into(),
        agent_id: "test".into(),
        working_directory: "/tmp".into(),
        depth: 0,
        read_tracker: Some(resources.read_tracker.clone()),
        file_history: Some(resources.file_history.clone()),
        file_lock_manager: Some(resources.file_lock_manager.clone()),
        ..Default::default()
    }
}

#[tokio::test]
async fn bash_tool_executes_simple_command() {
    let registry = ToolRegistry::builtin();
    let resources = make_resources();
    let ctx = make_ctx(&resources);

    let mut input = serde_json::Map::new();
    input.insert("command".into(), serde_json::json!("echo hello_e2e"));

    let tool = registry
        .get("Bash")
        .expect("Bash tool should be registered");
    let result = tool.call(&input, &ctx).await.unwrap();

    assert!(result.contains("hello_e2e"), "Bash should echo: {result}");
}

#[tokio::test]
async fn read_tool_reads_file() {
    let registry = ToolRegistry::builtin();
    let resources = make_resources();
    let ctx = make_ctx(&resources);

    // Create a temp file to read
    let dir = tempdir().unwrap();
    let file_path = dir.path().join("test.txt");
    std::fs::write(&file_path, "e2e read test content").unwrap();

    let mut input = serde_json::Map::new();
    input.insert(
        "file_path".into(),
        serde_json::json!(file_path.to_string_lossy().to_string()),
    );

    let tool = registry
        .get("Read")
        .expect("Read tool should be registered");
    let result = tool.call(&input, &ctx).await.unwrap();

    assert!(result.contains("e2e read test content"));
}

#[tokio::test]
async fn write_tool_creates_file() {
    let registry = ToolRegistry::builtin();
    let resources = make_resources();
    let ctx = make_ctx(&resources);

    let dir = tempdir().unwrap();
    let file_path = dir.path().join("output.txt");

    let mut input = serde_json::Map::new();
    input.insert(
        "file_path".into(),
        serde_json::json!(file_path.to_string_lossy().to_string()),
    );
    input.insert("content".into(), serde_json::json!("written by e2e test"));

    let tool = registry
        .get("Write")
        .expect("Write tool should be registered");
    let result = tool.call(&input, &ctx).await.unwrap();

    assert!(
        result.contains("File created") || result.contains("File updated"),
        "Write should report success: {result}"
    );
    assert_eq!(
        std::fs::read_to_string(&file_path).unwrap(),
        "written by e2e test"
    );
}

#[tokio::test]
async fn unknown_tool_returns_error() {
    let registry = ToolRegistry::builtin();
    assert!(registry.get("NonExistentTool").is_none());
}

#[tokio::test]
async fn tool_registry_has_expected_tools() {
    let registry = ToolRegistry::builtin();
    let defs = registry.definitions(None);
    let names: Vec<&str> = defs.iter().map(|d| d.name.as_str()).collect();

    // Verify core tools are registered
    assert!(names.contains(&"Bash"), "Bash should be in registry");
    assert!(names.contains(&"Read"), "Read should be in registry");
    assert!(names.contains(&"Write"), "Write should be in registry");
    assert!(names.contains(&"Grep"), "Grep should be in registry");
    assert!(names.contains(&"Glob"), "Glob should be in registry");
}
