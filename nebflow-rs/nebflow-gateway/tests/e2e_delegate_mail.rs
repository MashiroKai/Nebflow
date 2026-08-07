//! E2E: Delegate + Mail — tests sub-agent spawning and inter-agent messaging.
//!
//! Tests the full Delegate → AgentSpawnerImpl → sub-agent spawn chain,
//! and the Mail → AgentMailerImpl → message delivery chain.

use nebflow_agent::resources::SharedResources;
use nebflow_agent::{AgentBuilder, AgentMailRegistry};
use nebflow_core::config::{NebflowServiceConfig, ThinkingConfig};
use nebflow_core::flow::membership::FlowMembership;
use nebflow_core::tools::ToolRegistry;
use nebflow_core::types::{AgentCategory, AgentDef, AgentMailer, MailResult, ToolContext};
use std::sync::Arc;

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

fn test_def() -> AgentDef {
    AgentDef {
        name: "test-agent".into(),
        description: "E2E test agent".into(),
        tools: vec![],
        system_prompt: "You are a test agent.".into(),
        avatar: None,
        display_name: None,
        voice_enabled: false,
        model: None,
        category: AgentCategory::Standalone,
        mcp_servers: vec![],
    }
}

// ── Delegate E2E ──

#[tokio::test]
async fn delegate_tool_without_spawner_returns_stub() {
    let registry = ToolRegistry::builtin();
    let tool = registry.get("Delegate").expect("Delegate tool registered");

    let ctx = ToolContext {
        session_id: "e2e-delegate-1".into(),
        agent_id: "test".into(),
        working_directory: "/tmp".into(),
        depth: 0,
        ..Default::default()
    };

    let mut input = serde_json::Map::new();
    input.insert("prompt".into(), serde_json::json!("do something"));
    input.insert("agent".into(), serde_json::json!("Coder"));

    let result = tool.call(&input, &ctx).await.unwrap();
    assert!(result.contains("Coder"));
    assert!(result.contains("depth: 1"));
}

#[tokio::test]
async fn delegate_tool_max_depth_blocked() {
    let registry = ToolRegistry::builtin();
    let tool = registry.get("Delegate").expect("Delegate tool registered");

    let ctx = ToolContext {
        session_id: "e2e-delegate-2".into(),
        agent_id: "test".into(),
        working_directory: "/tmp".into(),
        depth: 5,
        ..Default::default()
    };

    let mut input = serde_json::Map::new();
    input.insert("prompt".into(), serde_json::json!("too deep"));

    let result = tool.call(&input, &ctx).await;
    assert!(result.is_err());
    assert!(result
        .unwrap_err()
        .to_string()
        .contains("Maximum delegation depth"));
}

// ── Mail E2E ──

#[tokio::test]
async fn mail_tool_without_mailer_returns_stub() {
    let registry = ToolRegistry::builtin();
    let tool = registry.get("Mail").expect("Mail tool registered");

    let ctx = ToolContext {
        session_id: "e2e-mail-1".into(),
        agent_id: "test".into(),
        working_directory: "/tmp".into(),
        depth: 0,
        ..Default::default()
    };

    let mut input = serde_json::Map::new();
    input.insert("address".into(), serde_json::json!("backend"));
    input.insert("message".into(), serde_json::json!("fix the bug"));

    let result = tool.call(&input, &ctx).await.unwrap();
    assert!(result.contains("backend"));
    assert!(result.contains("[INFO]"));
}

#[tokio::test]
async fn mail_tool_validates_address() {
    let registry = ToolRegistry::builtin();
    let tool = registry.get("Mail").expect("Mail tool registered");

    let ctx = ToolContext {
        session_id: "e2e-mail-2".into(),
        agent_id: "test".into(),
        working_directory: "/tmp".into(),
        depth: 0,
        ..Default::default()
    };

    let input = serde_json::Map::new();
    let result = tool.call(&input, &ctx).await;
    assert!(result.is_err());
}

#[tokio::test]
async fn mail_tool_with_mock_mailer_delivers() {
    use async_trait::async_trait;

    struct MockMailer;

    #[async_trait]
    impl AgentMailer for MockMailer {
        async fn send_mail(
            &self,
            _sender: &str,
            address: &str,
            _message: &str,
            _msg_type: &str,
            _fork: bool,
        ) -> Result<MailResult, String> {
            Ok(MailResult {
                delivered: true,
                address: Some(format!("session-for-{address}")),
                reply: None,
            })
        }
    }

    let registry = ToolRegistry::builtin();
    let tool = registry.get("Mail").expect("Mail tool registered");

    let mailer: Arc<dyn AgentMailer> = Arc::new(MockMailer);
    let ctx = ToolContext {
        session_id: "e2e-mail-3".into(),
        agent_id: "test".into(),
        working_directory: "/tmp".into(),
        depth: 0,
        agent_mailer: Some(mailer),
        ..Default::default()
    };

    let mut input = serde_json::Map::new();
    input.insert("address".into(), serde_json::json!("backend"));
    input.insert("message".into(), serde_json::json!("hello"));

    let result = tool.call(&input, &ctx).await.unwrap();
    assert!(result.contains("[Mail sent]"));
    assert!(result.contains("session-for-backend"));
}

// ── AgentMailRegistry E2E ──

#[tokio::test]
async fn mail_registry_register_and_get() {
    use tokio::sync::mpsc;

    let registry = AgentMailRegistry::new();
    let (tx, _rx) = mpsc::channel::<nebflow_agent::AgentMessage>(64);

    registry.register("session-A", tx.clone()).await;
    assert!(registry.get("session-A").await.is_some());
    assert!(registry.get("session-B").await.is_none());
}

#[tokio::test]
async fn mail_registry_unregister() {
    use tokio::sync::mpsc;

    let registry = AgentMailRegistry::new();
    let (tx, _rx) = mpsc::channel::<nebflow_agent::AgentMessage>(64);

    registry.register("session-X", tx).await;
    assert!(registry.get("session-X").await.is_some());

    registry.unregister("session-X").await;
    assert!(registry.get("session-X").await.is_none());
}

#[tokio::test]
async fn mail_registry_fork_complete() {
    let registry = AgentMailRegistry::new();

    let rx = registry.register_fork("fork-session").await;
    let completed = registry
        .complete_fork("fork-session", "fork reply text".into())
        .await;
    assert!(completed);

    let reply = rx.await.unwrap();
    assert_eq!(reply, "fork reply text");
}

#[tokio::test]
async fn mail_registry_fork_no_waiter() {
    let registry = AgentMailRegistry::new();
    let completed = registry.complete_fork("no-waiter", "reply".into()).await;
    assert!(!completed);
}

// ── FlowMembership E2E ──

#[tokio::test]
async fn flow_membership_resolves_within_team() {
    let membership = FlowMembership::new();
    membership
        .register_session("team-1", "Alice", "sess-alice")
        .await;
    membership
        .register_session("team-1", "Bob", "sess-bob")
        .await;

    // Alice can resolve Bob
    let result = membership.resolve_session_id("sess-alice", "Bob").await;
    assert_eq!(result.as_deref(), Some("sess-bob"));

    // Bob can resolve Alice
    let result = membership.resolve_session_id("sess-bob", "Alice").await;
    assert_eq!(result.as_deref(), Some("sess-alice"));
}

#[tokio::test]
async fn flow_membership_cross_flow_blocked() {
    let membership = FlowMembership::new();
    membership
        .register_session("team-1", "Alice", "sess-alice")
        .await;
    membership
        .register_session("team-2", "Bob", "sess-bob")
        .await;

    // Alice (team-1) cannot resolve Bob (team-2)
    let result = membership.resolve_session_id("sess-alice", "Bob").await;
    assert_eq!(result, None);
}

// ── AgentBuilder with membership E2E ──

#[tokio::test]
async fn agent_builder_with_membership_spawns() {
    let resources = make_resources();
    let membership = Arc::new(FlowMembership::new());
    let mail_registry = Arc::new(AgentMailRegistry::new());

    let handle = AgentBuilder::new(test_def())
        .with_llm(resources)
        .with_session("e2e-membership-1")
        .with_membership(membership, mail_registry)
        .spawn();

    // Agent should spawn and stop cleanly
    let _ = tokio::time::timeout(std::time::Duration::from_secs(5), handle.stop()).await;
}
