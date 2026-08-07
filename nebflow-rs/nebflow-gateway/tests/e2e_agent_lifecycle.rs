//! E2E: Agent lifecycle — spawn, send input, collect events, stop.
//!
//! Verifies that an agent correctly:
//! - Starts and accepts messages
//! - Emits events in response to input
//! - Handles the absence of LLM resources gracefully (error, not hang)
//! - Stops cleanly when told to

use nebflow_agent::AgentBuilder;
use nebflow_agent::AgentEvent;
use nebflow_agent::AgentMessage;
use nebflow_core::types::{AgentCategory, AgentDef};

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

#[tokio::test]
async fn agent_spawns_and_stops_cleanly() {
    let handle = AgentBuilder::new(test_def()).spawn();
    // Should stop without hanging
    let result = tokio::time::timeout(std::time::Duration::from_secs(5), handle.stop()).await;
    assert!(result.is_ok(), "agent stop should not hang");
}

#[tokio::test]
async fn agent_accepts_user_input_without_hang() {
    let mut handle = AgentBuilder::new(test_def())
        .with_session("e2e-lifecycle-1")
        .spawn();

    handle.send_user_input("hello world").await;

    // The agent has no LLM resources, so process_turn will fail.
    // It should emit an error TextDelta or Done event, not hang.
    let mut got_event = false;
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(5);
    match tokio::time::timeout_at(deadline, handle.recv()).await {
        Ok(Some(_)) => got_event = true,
        Ok(None) => {} // agent finished
        Err(_) => {}   // timeout
    }
    assert!(got_event, "agent should emit at least one event");

    let _ = tokio::time::timeout(std::time::Duration::from_secs(5), handle.stop()).await;
}

#[tokio::test]
async fn agent_with_resources_emits_thinking_then_error() {
    use nebflow_agent::resources::SharedResources;
    use nebflow_core::config::{NebflowServiceConfig, ThinkingConfig};

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
    let resources = Arc::new(SharedResources::new(
        config,
        std::path::PathBuf::from("/tmp"),
        ThinkingConfig {
            enabled: false,
            budget_tokens: 0,
        },
    ));

    let handle = AgentBuilder::new(test_def())
        .with_llm(resources)
        .with_session("e2e-lifecycle-2")
        .spawn();

    handle.send_user_input("test message").await;

    // Should get at least a Thinking event, then an error (connection refused)
    let mut got_thinking = false;
    let mut got_done_or_error = false;
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(10);

    let mut h = handle;
    loop {
        match tokio::time::timeout_at(deadline, h.recv()).await {
            Ok(Some(AgentEvent::Thinking)) => {
                got_thinking = true;
            }
            Ok(Some(AgentEvent::Done { .. })) => {
                got_done_or_error = true;
                break;
            }
            Ok(Some(AgentEvent::TextDelta { .. })) => {
                // Error message or empty — either way the turn completed
                got_done_or_error = true;
                // Keep going to get Done
            }
            Ok(Some(AgentEvent::Interrupted)) => {
                got_done_or_error = true;
                break;
            }
            Ok(None) => break,
            Err(_) => break, // timeout
            _ => {}
        }
    }

    assert!(got_thinking, "should emit Thinking event before LLM call");
    assert!(got_done_or_error, "should emit Done or error event");

    let _ = tokio::time::timeout(std::time::Duration::from_secs(5), h.stop()).await;
}

#[tokio::test]
async fn agent_interrupt_stops_current_turn() {
    let def = test_def();
    let mut handle = AgentBuilder::new(def).with_session("e2e-interrupt").spawn();

    // Send input then immediately interrupt
    handle.send_user_input("long running task").await;
    handle.send_message(AgentMessage::Interrupt).await.unwrap();

    // Should get Interrupted event
    let mut got_interrupted = false;
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(5);
    loop {
        match tokio::time::timeout_at(deadline, handle.recv()).await {
            Ok(Some(AgentEvent::Interrupted)) => {
                got_interrupted = true;
                break;
            }
            Ok(Some(AgentEvent::Done { .. })) => break,
            Ok(None) => break,
            Err(_) => break,
            _ => {}
        }
    }
    assert!(got_interrupted, "should receive Interrupted event");

    let _ = tokio::time::timeout(std::time::Duration::from_secs(5), handle.stop()).await;
}

use std::sync::Arc;
