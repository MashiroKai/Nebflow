//! Agent protocol — AgentCommand/AgentMessage and AgentStreamEvent enums.
//! Mirrors nebflow.agent.protocol.scala from Scala.
//!
//! Note: This module defines the pure data types only. Actor-specific concerns
//! (ActorRef, Deferred) are not represented — they'll be replaced by Rust
//! channel types in the agent runtime.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

use crate::types::{ContentBlock, Message, TokenUsage};

// ============================================================
// AgentCommand → AgentMessage
// ============================================================

/// Commands sent to an agent actor. Corresponds to Scala AgentCommand sealed trait.
#[derive(Debug, Clone)]
pub enum AgentMessage {
    /// User text input.
    UserInput {
        text: String,
        #[allow(dead_code)]
        client_message_id: Option<String>,
        blocks: Option<Vec<ContentBlock>>,
        chat_width: u32,
    },

    /// Immediate (non-queued) input — injected directly into processing.
    ImmediateInput {
        text: String,
        blocks: Option<Vec<ContentBlock>>,
    },

    /// Interrupt the current turn.
    Interrupt,

    /// LLM call completed successfully.
    LlmComplete { result: ConsumeResult, turn_id: u64 },

    /// LLM call failed.
    LlmFailed { error: String, turn_id: u64 },

    /// Tool execution batch completed.
    ToolsComplete {
        results: Vec<(ToolCallResult, ToolExecResult)>,
        original_text: String,
        compacted_messages: Option<Vec<Message>>,
        thinking: Option<String>,
        thinking_signature: Option<String>,
    },

    /// Compaction process completed.
    CompactionComplete {
        result: Result<Vec<Message>, String>,
    },

    /// Trigger context compaction.
    TriggerCompaction {
        mode: String,
        post_compact_instruction: Option<String>,
    },

    /// User answered an AskUser prompt.
    UserAnswered { answers: Vec<String> },

    /// User answered a permission request.
    PermissionAnswered { approved: bool },

    /// External event injected into the agent (background task, mail, etc.).
    ExternalEvent {
        source: String,
        event_type: String,
        payload: String,
        correlation_id: Option<String>,
    },

    /// Replace tool results (context pruning).
    ReplaceToolResults { rounds: u32, summary: String },

    /// Update the context window size.
    UpdateContextWindow { window: usize },

    /// Ask a question (sub-agent to parent).
    AskQuestion {
        question: String,
        session_id: String,
    },

    /// Skill activation command.
    SkillActivate {
        skill_name: String,
        input: String,
        session_id: String,
        skill_content: String,
        skill_base_dir: String,
    },

    /// Retry the last dispatch.
    Retry { reason: String },

    /// Update safety mode for this session.
    SetSafetyMode { mode: String },

    /// Update git branch tracking.
    UpdateGitBranch { branch: Option<String> },

    // ── Plan mode commands ──
    /// Start plan mode with the given task.
    StartPlan { task: String },

    /// Plan agent completed a turn.
    PlanTurnComplete { plan_text: String },

    /// Plan agent failed or terminated.
    PlanFailed { error: String },

    /// User approved the plan.
    PlanApproved,

    /// User sent feedback to adjust the plan.
    PlanFeedback { text: String },

    /// User cancelled plan mode.
    PlanCancelled,

    // ── Lifecycle commands ──
    /// Resume a turn after crash recovery.
    ResumeTurn {
        turn_start_message_count: usize,
        turn_idx: u32,
    },

    /// Stop the agent (graceful shutdown).
    Stop { reason: String },

    /// Clear the read tracker.
    ClearReadTracker,

    /// Reset the session.
    ResetSession,

    /// Check dream mode (memory consolidation).
    CheckDream,

    /// Dream mode completed.
    DreamComplete {
        facts: Vec<String>,
        message_count_at_dream: usize,
    },

    /// Supervisor-triggered restart.
    RestartAgent { level: RestartLevel },

    /// Background task notification.
    BackgroundTaskNotification {
        task_id: String,
        description: String,
        status: String,
        output: String,
        exit_code: Option<i32>,
    },

    // ── Session tracking (for delegate/flow sub-agents) ──
    /// A sub-agent session started.
    SessionStarted {
        address: String,
        agent_name: String,
        task_description: String,
    },

    /// A sub-agent session closed.
    SessionClosed { address: String },

    /// A sub-agent session status update.
    SessionUpdate { address: String, status: String },
}

/// Restart level for supervisor-triggered agent restart.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RestartLevel {
    /// Cancel current work, re-dispatch LLM call (same messages).
    Soft,
    /// Truncate last tool call pair, inject error, re-dispatch.
    Rollback,
    /// (future) Context prune + restart.
    Prune,
    /// (future) Reset to empty, reload from persisted history.
    Full,
}

// ============================================================
// AgentStreamEvent → AgentEvent
// ============================================================

/// Events emitted by the agent to the outside world (streamed via WS).
/// Corresponds to Scala AgentStreamEvent enum.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum AgentEvent {
    /// Streaming text delta.
    #[serde(rename = "textDelta")]
    TextDelta { text: String },

    /// Tool execution started.
    #[serde(rename = "toolStart")]
    ToolStart { label: String },

    /// Tool execution completed.
    #[serde(rename = "toolEnd")]
    ToolEnd {
        label: String,
        summary: String,
        content: String,
        is_error: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        input: Option<serde_json::Value>,
    },

    /// Sub-agent started.
    #[serde(rename = "agentStart")]
    AgentStart {
        agent_name: String,
        agent_type: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        task_description: Option<String>,
    },

    /// Sub-agent finished.
    #[serde(rename = "agentEnd")]
    AgentEnd { agent_name: String },

    /// Agent is thinking (waiting for first token).
    #[serde(rename = "thinking")]
    Thinking,

    /// Tool call detected in LLM output.
    #[serde(rename = "toolCallDetected")]
    ToolCallDetected { name: String },

    /// Retry status message.
    #[serde(rename = "retryStatus")]
    RetryStatus { message: String },

    /// Turn complete — final done event for a user turn.
    #[serde(rename = "done")]
    Done {
        #[serde(skip_serializing_if = "Option::is_none")]
        model: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        context_window: Option<usize>,
        #[serde(skip_serializing_if = "Option::is_none")]
        input_tokens: Option<u64>,
        #[serde(skip_serializing_if = "Option::is_none")]
        compact_threshold: Option<f64>,
    },

    /// Usage update (token count, context window).
    #[serde(rename = "usageUpdate")]
    UsageUpdate {
        input_tokens: u64,
        context_window: usize,
        compact_threshold: f64,
    },

    /// Compaction started.
    #[serde(rename = "compactStart")]
    CompactStart {
        mode: String,
        input_tokens: Option<u64>,
        threshold: Option<usize>,
    },

    /// Compaction completed.
    #[serde(rename = "compactComplete")]
    CompactComplete {
        before: usize,
        after: usize,
        #[serde(skip_serializing_if = "Option::is_none")]
        report_path: Option<String>,
    },

    /// Compaction failed.
    #[serde(rename = "compactFailed")]
    CompactFailed {
        reason: String,
        attempt: u32,
        max_attempts: u32,
    },

    /// Background task status update.
    #[serde(rename = "backgroundTaskUpdate")]
    BackgroundTaskUpdate {
        task_id: String,
        description: String,
        status: String,
    },

    /// External event received (background task, mail, etc.).
    #[serde(rename = "externalEventReceived")]
    ExternalEventReceived {
        source: String,
        event_type: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        correlation_id: Option<String>,
    },

    /// Agent was interrupted.
    #[serde(rename = "interrupted")]
    Interrupted,
}

// ============================================================
// Agent lifecycle types
// ============================================================

/// Agent completion event (sent to parent/supervisor).
#[derive(Debug, Clone)]
pub struct AgentCompletedEvent {
    pub session_id: String,
    pub messages: Vec<Message>,
}

/// Agent failure event.
#[derive(Debug, Clone)]
pub struct AgentFailedEvent {
    pub session_id: String,
    pub error: AgentError,
}

/// Agent error type.
#[derive(Debug, Clone)]
pub struct AgentError {
    pub agent_id: String,
    pub agent_name: String,
    pub depth: u32,
    pub error_type: AgentErrorType,
    pub message: String,
    pub cause: Option<Box<AgentError>>,
}

/// Error type classification.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AgentErrorType {
    LlmFailed,
    ToolFailed,
    Timeout,
    Interrupted,
    DepthExceeded,
    Unknown,
}

/// Agent status.
#[derive(Debug, Clone, PartialEq)]
pub enum AgentStatus {
    Idle,
    Processing,
    WaitingForUser,
    Error(String),
}

/// Compaction result.
#[derive(Debug, Clone)]
pub struct CompactionResult {
    pub before: usize,
    pub after: usize,
}

/// Agent session info (for delegate/flow tracking).
#[derive(Debug, Clone)]
pub struct AgentSessionInfo {
    pub address: String,
    pub agent_name: String,
    pub task_description: String,
    pub status: String,
    pub created_at: u64,
}

// ============================================================
// Auxiliary types used by AgentMessage variants
// ============================================================

/// Result of consuming an LLM response.
#[derive(Debug, Clone)]
pub struct ConsumeResult {
    pub text: String,
    pub tool_calls: Vec<crate::types::ToolCall>,
    pub stop_reason: Option<String>,
    pub usage: Option<TokenUsage>,
    pub thinking: Option<String>,
    pub thinking_signature: Option<String>,
    pub model: Option<String>,
    pub context_window: Option<usize>,
}

/// Simplified tool call result for the ToolsComplete message.
#[derive(Debug, Clone)]
pub struct ToolCallResult {
    pub id: String,
    pub name: String,
}

/// Tool execution result.
#[derive(Debug, Clone)]
pub struct ToolExecResult {
    pub output: String,
    pub is_error: bool,
}

// ============================================================
// Session metadata (from shared/SessionMeta.scala)
// ============================================================

/// Session metadata — persisted and sent to the frontend.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionMeta {
    pub id: String,
    pub name: String,
    pub created_at: u64,
    pub updated_at: u64,
    pub has_unread: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub agent_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model_ref: Option<String>,
    #[serde(default, skip_serializing_if = "is_empty_map")]
    pub bridges: HashMap<String, serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub folder_id: Option<String>,
    #[serde(default = "default_safety_mode")]
    pub safety_mode: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub git_branch: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub flow_name: Option<String>,
}

fn default_safety_mode() -> String {
    "confirm-edits".into()
}

fn is_empty_map(map: &HashMap<String, serde_json::Value>) -> bool {
    map.is_empty()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn agent_event_text_delta_serde() {
        let event = AgentEvent::TextDelta {
            text: "hello".into(),
        };
        let json = serde_json::to_string(&event).unwrap();
        assert!(json.contains(r#""textDelta""#));
        assert!(json.contains(r#""text":"hello""#));
    }

    #[test]
    fn agent_event_done_serde() {
        let event = AgentEvent::Done {
            model: Some("claude-sonnet-4-6".into()),
            context_window: Some(200_000),
            input_tokens: Some(5_000),
            compact_threshold: Some(0.8),
        };
        let json = serde_json::to_string(&event).unwrap();
        let back: AgentEvent = serde_json::from_str(&json).unwrap();
        match back {
            AgentEvent::Done { model, .. } => {
                assert_eq!(model.as_deref(), Some("claude-sonnet-4-6"));
            }
            _ => panic!("expected Done"),
        }
    }

    #[test]
    fn agent_event_thinking_serde() {
        let event = AgentEvent::Thinking;
        let json = serde_json::to_string(&event).unwrap();
        assert!(json.contains("thinking"));
    }

    #[test]
    fn agent_event_usage_update_serde() {
        let event = AgentEvent::UsageUpdate {
            input_tokens: 42_000,
            context_window: 200_000,
            compact_threshold: 0.7,
        };
        let json = serde_json::to_string(&event).unwrap();
        let back: AgentEvent = serde_json::from_str(&json).unwrap();
        match back {
            AgentEvent::UsageUpdate { input_tokens, .. } => {
                assert_eq!(input_tokens, 42_000);
            }
            _ => panic!("expected UsageUpdate"),
        }
    }

    #[test]
    fn session_meta_serde_roundtrip() {
        let meta = SessionMeta {
            id: "test-session".into(),
            name: "Test".into(),
            created_at: 1_000,
            updated_at: 2_000,
            has_unread: false,
            agent_name: Some("Backend".into()),
            model_ref: None,
            bridges: HashMap::new(),
            folder_id: None,
            safety_mode: "confirm-edits".into(),
            git_branch: Some("main".into()),
            flow_name: None,
        };
        let json = serde_json::to_string(&meta).unwrap();
        let back: SessionMeta = serde_json::from_str(&json).unwrap();
        assert_eq!(back.id, "test-session");
        assert_eq!(back.agent_name.as_deref(), Some("Backend"));
        assert_eq!(back.git_branch.as_deref(), Some("main"));
    }

    #[test]
    fn session_meta_defaults() {
        let json = r#"{"id":"x","name":"y","createdAt":0,"updatedAt":0,"hasUnread":false}"#;
        let meta: SessionMeta = serde_json::from_str(json).unwrap();
        assert_eq!(meta.safety_mode, "confirm-edits");
    }
}
