use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::Arc;

/// ContentBlock — corresponds to Scala ContentBlock sealed trait.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum ContentBlock {
    #[serde(rename = "text")]
    Text { text: String },

    #[serde(rename = "image")]
    Image { data: String, media_type: String },

    #[serde(rename = "tool_use")]
    ToolUse {
        id: String,
        name: String,
        input: serde_json::Map<String, serde_json::Value>,
    },

    #[serde(rename = "tool_result")]
    ToolResult {
        tool_use_id: String,
        content: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        is_error: Option<bool>,
    },

    #[serde(rename = "thinking")]
    Thinking {
        thinking: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        signature: Option<String>,
    },
}

/// MessageRole — corresponds to Scala MessageRole enum.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum MessageRole {
    System,
    User,
    Assistant,
}

/// MessageContent — Either[String, List[ContentBlock]] equivalent.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum MessageContent {
    Text(String),
    Blocks(Vec<ContentBlock>),
}

impl MessageContent {
    /// Convenience: get text if this is Text variant.
    pub fn as_text(&self) -> Option<&str> {
        match self {
            MessageContent::Text(t) => Some(t),
            MessageContent::Blocks(_) => None,
        }
    }

    /// Convenience: get blocks if this is Blocks variant.
    pub fn as_blocks(&self) -> Option<&[ContentBlock]> {
        match self {
            MessageContent::Blocks(b) => Some(b),
            MessageContent::Text(_) => None,
        }
    }
}

/// Message — corresponds to Scala Message case class.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Message {
    pub role: MessageRole,
    pub content: MessageContent,
    pub timestamp: u64,
}

impl Message {
    /// Convenience: extract plain text content regardless of variant.
    pub fn text_content(&self) -> String {
        match &self.content {
            MessageContent::Text(t) => t.clone(),
            MessageContent::Blocks(_) => String::new(),
        }
    }
}

/// AgentCategory — corresponds to Scala AgentCategory enum.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum AgentCategory {
    Standalone,
    TeamLead,
    TeamMember,
    FlowNode,
}

/// AgentModelConfig — corresponds to Scala shared/AgentModelConfig.scala.
///
/// The agent declares which models to use — a `preferred` primary and an
/// ordered `fallbacks` list. If both are empty, the global candidate chain
/// (nebflow.json `model.default` + `model.fallbacks`) is used instead.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentModelConfig {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub preferred: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub fallbacks: Vec<String>,
}

/// AgentDef — corresponds to Scala AgentDef.scala.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentDef {
    pub name: String,
    pub description: String,
    pub tools: Vec<String>,
    pub system_prompt: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub avatar: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub display_name: Option<String>,
    pub voice_enabled: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<AgentModelConfig>,
    pub category: AgentCategory,
    pub mcp_servers: Vec<String>,
}

/// ToolCall — a parsed tool invocation from the LLM.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolCall {
    pub id: String,
    pub name: String,
    pub input: serde_json::Map<String, serde_json::Value>,
}

/// TokenUsage — token usage stats from the provider.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TokenUsage {
    pub input_tokens: u64,
    pub output_tokens: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cache_read_tokens: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cache_write_tokens: Option<u64>,
}

/// ToolDefinition — schema describing a tool to the LLM.
#[derive(Debug, Clone, Serialize)]
pub struct ToolDefinition {
    pub name: String,
    pub description: String,
    pub input_schema: serde_json::Value,
}

/// LlmRequest — parameters for a non-streaming LLM call.
#[derive(Debug, Clone)]
pub struct LlmRequest {
    pub messages: Vec<Message>,
    pub session_id: String,
    pub agent_id: String,
    pub tools: Option<Vec<ToolDefinition>>,
    pub max_tokens: Option<usize>,
    pub thinking: Option<serde_json::Value>,
    pub system_stable: Option<String>,
    pub system_dynamic: Option<String>,
    pub agent_model: Option<AgentModelConfig>,
}

/// StreamChunk — incremental events from a streaming LLM call.
#[derive(Debug, Clone)]
pub enum StreamChunk {
    TextDelta(String),
    ThinkingDelta(String),
    ThinkingSignature(String),
    ToolCallStart {
        name: String,
    },
    ToolArgDelta {
        tool_name: String,
        delta: String,
    },
    ToolCallComplete(ToolCall),
    /// Heartbeat — an empty SSE delta that keeps the stream alive.
    /// Resets the inactivity timeout timer without producing any content.
    Heartbeat,
    Done {
        stop_reason: Option<String>,
        usage: Option<TokenUsage>,
        context_window: Option<usize>,
    },
}

/// LlmError — errors from the LLM provider layer.
#[derive(Debug, Clone)]
pub enum LlmError {
    Network(String),
    Auth(String),
    RateLimit(String),
    InvalidRequest(String),
    Stream(String),
    /// Provider returned an HTTP error. Contains the error message and
    /// optionally the HTTP status code from the response.
    Provider {
        message: String,
        status_code: Option<u16>,
    },
    AllProvidersExhausted,
}

impl LlmError {
    /// Extract the HTTP status code from the error, if available.
    pub fn status_code(&self) -> Option<u16> {
        match self {
            Self::Provider { status_code, .. } => *status_code,
            Self::RateLimit(_) => Some(429),
            Self::Auth(_) => Some(401),
            _ => None,
        }
    }
}

/// LlmResponse — the result of a non-streaming LLM call.
#[derive(Debug, Clone)]
pub struct LlmResponse {
    pub content: Vec<ContentBlock>,
    pub stop_reason: Option<String>,
    pub usage: Option<TokenUsage>,
    pub context_window: Option<usize>,
}

/// ToolError — errors from tool execution.
#[derive(Debug, Clone)]
pub enum ToolError {
    InvalidInput(String),
    Execution(String),
    NotFound(String),
    Permission(String),
}

impl std::fmt::Display for ToolError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ToolError::InvalidInput(msg) => write!(f, "{msg}"),
            ToolError::Execution(msg) => write!(f, "{msg}"),
            ToolError::NotFound(msg) => write!(f, "{msg}"),
            ToolError::Permission(msg) => write!(f, "{msg}"),
        }
    }
}

impl std::error::Error for ToolError {}

/// WsSender — trait for sending events to WebSocket clients.
/// Implemented by the gateway layer; used by tools that need to push events
/// (e.g. AskUser, Card) directly to the frontend.
pub trait WsSender: Send + Sync {
    fn send(&self, msg: &str);
}

/// A concrete WsSender backed by a tokio unbounded channel.
pub struct ChannelWsSender {
    pub tx: tokio::sync::mpsc::UnboundedSender<String>,
}

impl WsSender for ChannelWsSender {
    fn send(&self, msg: &str) {
        let _ = self.tx.send(msg.to_string());
    }
}

/// Parameters for spawning a sub-agent via AgentSpawner.
#[derive(Debug, Clone)]
pub struct SpawnParams {
    /// Task prompt for the sub-agent.
    pub prompt: String,
    /// Short description/label for the task.
    pub description: String,
    /// Target standalone agent name, or None for self-clone.
    pub agent: Option<String>,
    /// Flow name to trigger, or None.
    pub flow: Option<String>,
    /// If true, pass current conversation context to the sub-agent.
    pub fork: bool,
    /// "ephemeral" or "persistent".
    pub lifecycle: String,
    /// Depth of the parent agent (sub-agent will be depth + 1).
    pub parent_depth: usize,
    /// Parent session ID.
    pub session_id: String,
}

/// Result of a sub-agent spawn.
#[derive(Debug, Clone)]
pub struct SpawnResult {
    /// Address/ID of the spawned agent session.
    pub address: String,
    /// Whether the agent started successfully.
    pub started: bool,
}

/// AgentSpawner — trait for spawning sub-agents from within tools.
/// Implemented by the agent runtime layer; injected into ToolContext.
/// This avoids a circular dependency between nebflow-core and nebflow-agent.
#[async_trait::async_trait]
pub trait AgentSpawner: Send + Sync {
    /// Spawn a sub-agent synchronously and return when it completes (ephemeral)
    /// or when it has started (persistent).
    async fn spawn(&self, params: SpawnParams) -> Result<SpawnResult, String>;
}

/// Result of a Mail delivery.
#[derive(Debug, Clone)]
pub struct MailResult {
    /// Whether the message was delivered successfully.
    pub delivered: bool,
    /// Target agent's address (session ID) if resolved.
    pub address: Option<String>,
    /// Fork response (only set in fork mode, if the target replied).
    pub reply: Option<String>,
}

/// AgentMailer — trait for delivering inter-agent messages.
/// Implemented by the agent runtime layer; injected into ToolContext.
/// Uses FlowMembership to resolve addresses and forwards messages
/// to the target agent's message channel.
#[async_trait::async_trait]
pub trait AgentMailer: Send + Sync {
    /// Send a message to an agent. In async mode, returns after delivery.
    /// In fork mode, waits for the target's response and returns it.
    async fn send_mail(
        &self,
        sender_session_id: &str,
        address: &str,
        message: &str,
        msg_type: &str,
        fork: bool,
    ) -> Result<MailResult, String>;
}

/// DeviceTransfer — trait for transferring files to/from remote devices via NebLink.
/// Implemented by the gateway layer; injected into ToolContext.
/// Used by the TransferFile tool for cross-device transfers.
#[async_trait::async_trait]
pub trait DeviceTransfer: Send + Sync {
    /// Transfer a file from a remote device to the local machine.
    /// `device` is the peer device name, `remote_path` is the file path on the remote.
    /// Returns the file contents.
    async fn fetch_remote(&self, device: &str, remote_path: &str) -> Result<Vec<u8>, String>;

    /// Transfer a file from the local machine to a remote device.
    /// `device` is the peer device name, `remote_path` is the destination on the remote.
    /// `data` is the file contents.
    async fn push_remote(
        &self,
        device: &str,
        remote_path: &str,
        data: Vec<u8>,
    ) -> Result<(), String>;
}

/// ToolContext — context passed to tools during execution.
/// Mirrors Scala ToolContext, carrying everything a tool needs to operate.
#[derive(Clone, Default)]
pub struct ToolContext {
    pub session_id: String,
    pub agent_id: String,
    pub working_directory: String,
    /// Agent depth — 0 for root agent, 1+ for sub-agents.
    pub depth: usize,

    // ── Project paths ──
    /// Root of the project Nebflow is running in.
    pub project_root: Option<PathBuf>,

    // ── Runtime services ──
    /// LLM handle for sub-agent calls or LLM-assisted tools.
    pub llm: Option<Arc<crate::llm::LlmHandle>>,
    /// WebSocket sender for pushing events to the frontend.
    pub ws_sender: Option<Arc<dyn WsSender>>,
    /// Agent spawner for Delegate tool — injected by the runtime layer.
    pub agent_spawner: Option<Arc<dyn AgentSpawner>>,
    /// Agent mailer for Mail tool — resolves addresses via FlowMembership
    /// and forwards messages to target agents.
    pub agent_mailer: Option<Arc<dyn AgentMailer>>,
    /// Flow membership registry — maps (flowId, agentName) → sessionId.
    /// Used for address resolution in team/flow contexts.
    pub flow_membership: Option<Arc<crate::flow::membership::FlowMembership>>,
    /// Device transfer for cross-device file transfers via NebLink.
    pub device_transfer: Option<Arc<dyn DeviceTransfer>>,

    // ── File tooling state ──
    /// Read tracker — records file reads for context.
    pub read_tracker: Option<Arc<crate::tools::read_tracker::ReadTracker>>,
    /// File history — snapshots before overwrites.
    pub file_history: Option<Arc<crate::tools::file_history::FileHistory>>,
    /// File lock manager — serializes concurrent writes.
    pub file_lock_manager: Option<Arc<crate::tools::file_lock::FileLockManager>>,

    // ── Agent metadata ──
    /// The agent definition this context belongs to.
    pub agent_def: Option<crate::types::AgentDef>,
    /// Full message history for the current session.
    pub messages: Vec<Message>,
    /// Next tool call ID counter (for generating unique IDs).
    pub tool_call_id: u64,
}

impl std::fmt::Debug for ToolContext {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ToolContext")
            .field("session_id", &self.session_id)
            .field("agent_id", &self.agent_id)
            .field("working_directory", &self.working_directory)
            .field("depth", &self.depth)
            .field("project_root", &self.project_root)
            .field("has_llm", &self.llm.is_some())
            .field("has_ws_sender", &self.ws_sender.is_some())
            .field("has_agent_spawner", &self.agent_spawner.is_some())
            .field("has_agent_mailer", &self.agent_mailer.is_some())
            .field("has_flow_membership", &self.flow_membership.is_some())
            .field("has_device_transfer", &self.device_transfer.is_some())
            .field("has_read_tracker", &self.read_tracker.is_some())
            .field("has_file_history", &self.file_history.is_some())
            .field("has_file_lock", &self.file_lock_manager.is_some())
            .field("has_agent_def", &self.agent_def.is_some())
            .field("messages_len", &self.messages.len())
            .field("tool_call_id", &self.tool_call_id)
            .finish()
    }
}

/// SendMessageParams — parameters for the adapter layer.
/// Mirrors the Scala ProviderAdapter.SendMessageParams.
#[derive(Debug, Clone)]
pub struct SendMessageParams {
    pub messages: Vec<Message>,
    pub model: String,
    pub tools: Option<Vec<ToolDefinition>>,
    pub max_tokens: Option<usize>,
    pub thinking: Option<serde_json::Value>,
    pub system_stable: Option<String>,
    pub system_dynamic: Option<String>,
    pub session_id: Option<String>,
    pub agent_id: Option<String>,
}

/// AdapterResponse — the result from a provider adapter.
/// Mirrors the Scala AdapterResponse(reply, toolCalls, usage).
#[derive(Debug, Clone)]
pub struct AdapterResponse {
    pub reply: String,
    pub tool_calls: Vec<ToolCall>,
    pub usage: Option<TokenUsage>,
}

/// LlmMeta — metadata about which provider/model served a request.
#[derive(Debug, Clone)]
pub struct LlmMeta {
    pub session_id: String,
    pub agent_id: String,
    pub provider_id: String,
    pub model: String,
    pub duration_ms: u64,
    pub fallback_chain: Option<Vec<FallbackStep>>,
    pub context_window: Option<usize>,
}

/// FallbackStep — record of a failed attempt before a successful one.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FallbackStep {
    pub provider_id: String,
    pub model: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    pub duration_ms: u64,
}

// ============================================================
// Fallback types — from shared/fallback.scala
// ============================================================

/// Reason for a provider failover.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum FailoverReason {
    Auth,
    RateLimit,
    Overloaded,
    ServerError,
    ModelNotFound,
    ProviderError,
    Format,
    ConnectionReset,
    Timeout,
    EmptyStream,
    CapabilityMismatch,
    Unknown,
}

/// Permanence of an error — determines fallback behavior.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum ErrorPermanence {
    /// Retry with backoff on the same provider.
    Transient,
    /// Skip to the next provider.
    Permanent,
    /// Abort the entire fallback chain (affects all providers).
    Fatal,
}

/// Classification of an LLM error.
#[derive(Debug, Clone)]
pub struct ErrorClassification {
    pub reason: FailoverReason,
    pub permanence: ErrorPermanence,
    pub status_code: Option<u16>,
    pub message: Option<String>,
}

/// Record of a single fallback attempt (success or failure).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FallbackAttempt {
    pub provider_id: String,
    pub model: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<FailoverReason>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub permanence: Option<ErrorPermanence>,
    pub duration_ms: u64,
    pub retries_used: u32,
    pub timestamp: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

/// Result of a fallback chain — the successful data plus all attempts.
#[derive(Debug, Clone)]
pub struct FallbackResult<T> {
    pub data: T,
    pub attempts: Vec<FallbackAttempt>,
    pub used_candidate: ModelCandidate,
}

// ============================================================
// ModelCandidate — from registry.scala
// ============================================================

/// A resolved model candidate in the fallback chain.
#[derive(Debug, Clone)]
pub struct ModelCandidate {
    pub provider_id: String,
    pub model: String,
    pub max_tokens: usize,
    pub context_window: usize,
    pub vision: bool,
    pub capabilities: Vec<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn smoke_test() {
        let msg = Message {
            role: MessageRole::User,
            content: MessageContent::Text("hello".into()),
            timestamp: 0,
        };
        let json = serde_json::to_string(&msg).unwrap();
        let back: Message = serde_json::from_str(&json).unwrap();
        assert_eq!(json, serde_json::to_string(&back).unwrap());
    }

    #[test]
    fn content_block_text_serde() {
        let block = ContentBlock::Text {
            text: "hello world".into(),
        };
        let json = serde_json::to_string(&block).unwrap();
        assert!(json.contains(r#""type":"text""#));
        assert!(json.contains(r#""text":"hello world""#));
        let back: ContentBlock = serde_json::from_str(&json).unwrap();
        if let ContentBlock::Text { text } = back {
            assert_eq!(text, "hello world");
        } else {
            panic!("expected Text variant");
        }
    }

    #[test]
    fn content_block_thinking_serde_with_signature() {
        let block = ContentBlock::Thinking {
            thinking: "hmm".into(),
            signature: Some("sig123".into()),
        };
        let json = serde_json::to_string(&block).unwrap();
        assert!(json.contains(r#""signature":"sig123""#));
    }

    #[test]
    fn content_block_thinking_serde_without_signature() {
        let block = ContentBlock::Thinking {
            thinking: "hmm".into(),
            signature: None,
        };
        let json = serde_json::to_string(&block).unwrap();
        assert!(!json.contains("signature"));
    }

    #[test]
    fn message_role_serde() {
        let json = serde_json::to_string(&MessageRole::User).unwrap();
        assert_eq!(json, r#""User""#);
        let back: MessageRole = serde_json::from_str(&json).unwrap();
        assert_eq!(back, MessageRole::User);
    }

    #[test]
    fn message_content_untagged_text() {
        let content = MessageContent::Text("hi".into());
        let json = serde_json::to_string(&content).unwrap();
        assert_eq!(json, r#""hi""#);
        let back: MessageContent = serde_json::from_str(&json).unwrap();
        assert!(matches!(back, MessageContent::Text(_)));
    }

    #[test]
    fn message_content_untagged_blocks() {
        let content = MessageContent::Blocks(vec![ContentBlock::Text { text: "x".into() }]);
        let json = serde_json::to_string(&content).unwrap();
        assert!(json.starts_with('['));
        let back: MessageContent = serde_json::from_str(&json).unwrap();
        assert!(matches!(back, MessageContent::Blocks(_)));
    }

    #[test]
    fn agent_def_serde_roundtrip() {
        let def = AgentDef {
            name: "test".into(),
            description: "test agent".into(),
            tools: vec!["Read".into()],
            system_prompt: "You are test".into(),
            avatar: None,
            display_name: Some("Test".into()),
            voice_enabled: false,
            model: None,
            category: AgentCategory::Standalone,
            mcp_servers: vec![],
        };
        let json = serde_json::to_string(&def).unwrap();
        let back: AgentDef = serde_json::from_str(&json).unwrap();
        assert_eq!(back.name, "test");
        assert_eq!(back.category, AgentCategory::Standalone);
        assert!(!back.voice_enabled);
    }

    #[test]
    fn tool_context_default() {
        let ctx = ToolContext::default();
        assert!(ctx.session_id.is_empty());
        assert_eq!(ctx.depth, 0);
        assert!(ctx.project_root.is_none());
        assert!(ctx.llm.is_none());
        assert!(ctx.ws_sender.is_none());
        assert!(ctx.read_tracker.is_none());
        assert!(ctx.file_history.is_none());
        assert!(ctx.file_lock_manager.is_none());
        assert!(ctx.agent_def.is_none());
        assert!(ctx.messages.is_empty());
        assert_eq!(ctx.tool_call_id, 0);
    }

    #[test]
    fn tool_context_with_default_fields() {
        let ctx = ToolContext {
            session_id: "s1".into(),
            agent_id: "agent1".into(),
            working_directory: "/tmp".into(),
            depth: 2,
            ..Default::default()
        };
        assert_eq!(ctx.session_id, "s1");
        assert_eq!(ctx.depth, 2);
        assert!(ctx.project_root.is_none());
    }

    #[test]
    fn channel_ws_sender_sends() {
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<String>();
        let sender = ChannelWsSender { tx };
        sender.send("hello world");
        let msg = rx.try_recv().unwrap();
        assert_eq!(msg, "hello world");
    }

    #[test]
    fn tool_context_debug_format() {
        let ctx = ToolContext {
            session_id: "test".into(),
            depth: 1,
            ..Default::default()
        };
        let debug_str = format!("{ctx:?}");
        assert!(debug_str.contains("test"));
        assert!(debug_str.contains("has_llm: false"));
    }

    #[test]
    fn llm_error_status_code_provider() {
        let err = LlmError::Provider {
            message: "API error 429: rate limited".into(),
            status_code: Some(429),
        };
        assert_eq!(err.status_code(), Some(429));

        let err_no_status = LlmError::Provider {
            message: "empty response".into(),
            status_code: None,
        };
        assert_eq!(err_no_status.status_code(), None);
    }

    #[test]
    fn llm_error_status_code_other_variants() {
        assert_eq!(
            LlmError::RateLimit("too fast".into()).status_code(),
            Some(429)
        );
        assert_eq!(LlmError::Auth("bad key".into()).status_code(), Some(401));
        assert_eq!(LlmError::Network("timeout".into()).status_code(), None);
        assert_eq!(LlmError::Stream("disconnected".into()).status_code(), None);
        assert_eq!(LlmError::InvalidRequest("bad".into()).status_code(), None);
        assert_eq!(LlmError::AllProvidersExhausted.status_code(), None);
    }

    #[test]
    fn llm_error_provider_debug_format() {
        let err = LlmError::Provider {
            message: "test error".into(),
            status_code: Some(500),
        };
        let debug = format!("{err:?}");
        assert!(debug.contains("Provider"));
        assert!(debug.contains("test error"));
        assert!(debug.contains("500"));
    }
}
