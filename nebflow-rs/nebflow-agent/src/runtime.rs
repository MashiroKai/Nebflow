use crate::context;
use crate::core as agent_core;
use crate::resources::SharedResources;
use crate::{AgentEvent, AgentMessage};
use nebflow_core::tools::ToolRegistry;
use nebflow_core::types::ToolContext;
use nebflow_core::types::{
    AgentDef, AgentModelConfig, Message, MessageContent, MessageRole, ToolCall,
};
use std::sync::Arc;
use tokio::sync::mpsc;

/// AgentRunner — the core event loop for an agent.
///
/// Implements the full message cycle:
///   UserInput → build system prompt → LLM stream → consume →
///   (tool exec → tool results → repeat) → TurnComplete
pub struct AgentRunner {
    def: AgentDef,
    msg_rx: Option<mpsc::Receiver<AgentMessage>>,
    event_tx: mpsc::Sender<AgentEvent>,
    resources: Option<Arc<SharedResources>>,
    tools: Option<Arc<ToolRegistry>>,
    session_id: String,
    messages: Vec<Message>,
    depth: usize,
    context_window: usize,
    agent_model: Option<AgentModelConfig>,
    tool_call_counter: u64,
    /// Flag set when an Interrupt message is received during a turn.
    /// process_turn checks this between iterations and exits early.
    interrupted: bool,
    /// Cancellation token for the current turn. Cloned into consume_llm_stream_cancellable
    /// so the LLM stream can be cancelled mid-flight.
    cancel_token: tokio_util::sync::CancellationToken,
    /// Flow membership registry for Mail address resolution.
    flow_membership: Option<Arc<nebflow_core::flow::membership::FlowMembership>>,
    /// Mail registry for delivering messages to other agents.
    mail_registry: Option<Arc<AgentMailRegistry>>,
    /// Persistent agent handles — keeps Delegate persistent spawns alive.
    persistent_handles:
        Arc<tokio::sync::Mutex<std::collections::HashMap<String, crate::AgentHandle>>>,
}

impl AgentRunner {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        def: AgentDef,
        msg_rx: mpsc::Receiver<AgentMessage>,
        event_tx: mpsc::Sender<AgentEvent>,
        resources: Option<Arc<SharedResources>>,
        tools: Option<Arc<ToolRegistry>>,
        session_id: String,
        initial_messages: Vec<Message>,
        depth: usize,
        context_window: Option<usize>,
        agent_model: Option<AgentModelConfig>,
    ) -> Self {
        Self::with_membership(
            def,
            msg_rx,
            event_tx,
            resources,
            tools,
            session_id,
            initial_messages,
            depth,
            context_window,
            agent_model,
            None,
            None,
        )
    }

    /// Create an AgentRunner with flow membership and mail registry injected.
    /// This enables the Mail tool to resolve addresses and deliver messages
    /// to other agents in the same team/flow.
    #[allow(clippy::too_many_arguments)]
    pub fn with_membership(
        def: AgentDef,
        msg_rx: mpsc::Receiver<AgentMessage>,
        event_tx: mpsc::Sender<AgentEvent>,
        resources: Option<Arc<SharedResources>>,
        tools: Option<Arc<ToolRegistry>>,
        session_id: String,
        initial_messages: Vec<Message>,
        depth: usize,
        context_window: Option<usize>,
        agent_model: Option<AgentModelConfig>,
        flow_membership: Option<Arc<nebflow_core::flow::membership::FlowMembership>>,
        mail_registry: Option<Arc<AgentMailRegistry>>,
    ) -> Self {
        let cw = context_window
            .or_else(|| resources.as_ref().map(|r| r.context_window))
            .unwrap_or(nebflow_core::config::CONTEXT_WINDOW);

        Self {
            def,
            msg_rx: Some(msg_rx),
            event_tx,
            resources,
            tools,
            session_id,
            messages: initial_messages,
            depth,
            context_window: cw,
            agent_model,
            tool_call_counter: 0,
            interrupted: false,
            cancel_token: tokio_util::sync::CancellationToken::new(),
            flow_membership,
            mail_registry,
            persistent_handles: Arc::new(tokio::sync::Mutex::new(std::collections::HashMap::new())),
        }
    }

    /// Run the agent event loop. Returns when a Stop message is received
    /// or the message channel is closed.
    pub async fn run(mut self) {
        // Take msg_rx out of self so we can select between process_turn
        // and incoming Interrupt messages without double-borrowing self.
        let mut msg_rx = self.msg_rx.take().unwrap();

        while let Some(msg) = msg_rx.recv().await {
            match msg {
                AgentMessage::Stop { .. } => break,

                AgentMessage::Interrupt => {
                    let _ = self.event_tx.send(AgentEvent::Interrupted).await;
                    while let Ok(AgentMessage::Interrupt) = msg_rx.try_recv() {}
                    continue;
                }

                AgentMessage::UserInput { text, blocks, .. } => {
                    let user_msg = Message {
                        role: MessageRole::User,
                        content: if let Some(b) = blocks {
                            MessageContent::Blocks(b)
                        } else {
                            MessageContent::Text(text.clone())
                        },
                        timestamp: now_millis(),
                    };
                    self.messages.push(user_msg);
                    self.interrupted = false;
                    self.cancel_token = tokio_util::sync::CancellationToken::new();
                    let cancel = self.cancel_token.clone();

                    tokio::select! {
                        biased;
                        Some(msg) = msg_rx.recv() => {
                            if matches!(msg, AgentMessage::Interrupt) {
                                self.interrupted = true;
                                cancel.cancel();
                                while let Ok(AgentMessage::Interrupt) = msg_rx.try_recv() {}
                            } else {
                                self.interrupted = true;
                                cancel.cancel();
                            }
                        }
                        result = self.process_turn() => {
                            if let Err(e) = result {
                                if !self.interrupted {
                                    let _ = self.event_tx.send(AgentEvent::TextDelta {
                                        text: format!("[Error: {e}]"),
                                    }).await;
                                }
                            }
                        }
                    }

                    if self.interrupted {
                        let _ = self.event_tx.send(AgentEvent::Interrupted).await;
                    }
                }

                AgentMessage::ImmediateInput { text, blocks } => {
                    let user_msg = Message {
                        role: MessageRole::User,
                        content: if let Some(b) = blocks {
                            MessageContent::Blocks(b)
                        } else {
                            MessageContent::Text(text.clone())
                        },
                        timestamp: now_millis(),
                    };
                    self.messages.push(user_msg);
                    self.interrupted = false;
                    self.cancel_token = tokio_util::sync::CancellationToken::new();
                    let cancel = self.cancel_token.clone();

                    let turn_text = tokio::select! {
                        biased;
                        Some(msg) = msg_rx.recv() => {
                            if matches!(msg, AgentMessage::Interrupt) {
                                self.interrupted = true;
                                cancel.cancel();
                                while let Ok(AgentMessage::Interrupt) = msg_rx.try_recv() {}
                            } else {
                                self.interrupted = true;
                                cancel.cancel();
                            }
                            String::new()
                        }
                        result = self.process_turn() => {
                            result.unwrap_or_default()
                        }
                    };

                    if self.interrupted {
                        let _ = self.event_tx.send(AgentEvent::Interrupted).await;
                    }

                    // Complete any pending fork request waiting for this session
                    if let Some(registry) = &self.mail_registry {
                        registry.complete_fork(&self.session_id, turn_text).await;
                    }
                }

                _ => {
                    // Other message types (ExternalEvent, etc.) are
                    // acknowledged but not fully processed in this phase.
                }
            }
        }
    }

    /// Process a single user turn: LLM call → tool execution → repeat until done.
    /// Returns the accumulated text output from this turn.
    async fn process_turn(&mut self) -> Result<String, String> {
        let max_iterations = 50; // Safety limit to prevent infinite loops
        let mut turn_text = String::new();

        for _ in 0..max_iterations {
            // ── Check for interrupt between iterations ──
            if self.interrupted {
                return Ok(turn_text);
            }

            // ── Build system prompt (stable + dynamic separated) ──
            let project_root = self
                .resources
                .as_ref()
                .and_then(|r| r.project_root.as_deref());
            let agent_home = self.resources.as_ref().map(|_| {
                let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
                std::path::PathBuf::from(home)
                    .join(".nebflow")
                    .join("agents")
                    .join(&self.def.name)
            });
            let prompt_ctx = context::build_prompt_context_with_paths(
                &self.def,
                self.depth as u32,
                None,
                project_root,
                agent_home.as_deref(),
            );
            let system_stable = context::build_system_stable(&self.def);
            let system_dynamic = context::build_system_dynamic(&prompt_ctx);

            // ── Build LLM request ──
            let tools = self.get_tool_definitions().await;
            let thinking = self.get_thinking_config();

            let req = agent_core::build_llm_request(
                self.messages.clone(),
                system_stable,
                system_dynamic,
                tools,
                &self.session_id,
                &self.def.name,
                None, // max_tokens — use provider default
                thinking,
                self.agent_model.clone(),
            );

            // ── Call LLM ──
            let stream_result = self.call_llm(&req).await?;

            // ── Consume stream events ──
            let _ = self.event_tx.send(AgentEvent::Thinking).await;

            let cancel = self.cancel_token.clone();
            let consume = match stream_result {
                agent_core::StreamResult::Stream(stream) => {
                    agent_core::consume_llm_stream_cancellable(stream, cancel).await
                }
                agent_core::StreamResult::Error(e) => {
                    return Err(e);
                }
            };

            // ── Stream text deltas to the frontend ──
            if !consume.text.is_empty() {
                turn_text.push_str(&consume.text);
                let _ = self
                    .event_tx
                    .send(AgentEvent::TextDelta {
                        text: consume.text.clone(),
                    })
                    .await;
            }

            // ── Build assistant message and append to history ──
            let assistant_msg = agent_core::build_assistant_message(&consume);
            self.messages.push(assistant_msg);

            // ── Check if we're done (no tool calls) ──
            if consume.tool_calls.is_empty() || consume.stop_reason.as_deref() == Some("end_turn") {
                let _ = self
                    .event_tx
                    .send(AgentEvent::Done {
                        model: None,
                        context_window: Some(self.context_window),
                        input_tokens: consume.usage.as_ref().map(|u| u.input_tokens),
                        compact_threshold: None,
                    })
                    .await;
                return Ok(turn_text);
            }

            // ── Execute tools (skip if interrupted) ──
            if self.interrupted {
                return Ok(turn_text);
            }
            let tool_results = self.execute_tools(&consume.tool_calls).await;

            // ── Send tool result events ──
            for (tc, (output, is_error)) in consume.tool_calls.iter().zip(tool_results.iter()) {
                let _ = self
                    .event_tx
                    .send(AgentEvent::ToolEnd {
                        label: tc.name.clone(),
                        summary: output.chars().take(200).collect(),
                        content: output.clone(),
                        is_error: *is_error,
                        input: Some(serde_json::Value::Object(tc.input.clone())),
                    })
                    .await;
            }

            // ── Build tool result messages and append ──
            let results_formatted: Vec<(String, String, bool)> = consume
                .tool_calls
                .iter()
                .zip(tool_results.iter())
                .map(|(tc, (output, is_error))| (tc.id.clone(), output.clone(), *is_error))
                .collect();

            let tool_msgs =
                agent_core::build_tool_result_messages(&consume.tool_calls, &results_formatted);
            self.messages.extend(tool_msgs);

            // ── Check for micro-compaction ──
            let compact_config = crate::compact::CompactConfig::default();
            if let Some(compacted) = agent_core::maybe_micro_compact(
                &self.messages,
                self.context_window,
                &compact_config,
            ) {
                self.messages = compacted;
            }
        }

        // Exhausted iterations — emit Done with a warning
        let _ = self
            .event_tx
            .send(AgentEvent::Done {
                model: None,
                context_window: Some(self.context_window),
                input_tokens: None,
                compact_threshold: None,
            })
            .await;

        Ok(turn_text)
    }

    /// Call the LLM. Returns a stream to consume, or an error string.
    async fn call_llm(
        &self,
        req: &nebflow_core::types::LlmRequest,
    ) -> Result<agent_core::StreamResult, String> {
        if let Some(res) = &self.resources {
            // Use the real LLM handle
            let stream = res.llm.send_stream(req);
            Ok(agent_core::StreamResult::Stream(stream))
        } else {
            // No resources — this is a bare agent without LLM connectivity.
            Err("no LLM resources configured".into())
        }
    }

    /// Execute a batch of tool calls and return their results.
    async fn execute_tools(&mut self, tool_calls: &[ToolCall]) -> Vec<(String, bool)> {
        let mut results = Vec::with_capacity(tool_calls.len());
        let tool_registry = self.get_tool_registry();
        let ctx = self.build_tool_context();

        for tc in tool_calls {
            // Emit ToolStart event
            let _ = self
                .event_tx
                .send(AgentEvent::ToolStart {
                    label: tc.name.clone(),
                })
                .await;
            let _ = self
                .event_tx
                .send(AgentEvent::ToolCallDetected {
                    name: tc.name.clone(),
                })
                .await;

            let (output, is_error) = match tool_registry.get(&tc.name) {
                Some(tool) => match tool.call(&tc.input, &ctx).await {
                    Ok(output) => {
                        let truncated = truncate_result(&output, tool.max_result_size());
                        (truncated, false)
                    }
                    Err(e) => (e.to_string(), true),
                },
                None => {
                    // Not a built-in tool — try MCP tools if available
                    if let Some(res) = &self.resources {
                        match res.mcp_manager.call_tool(&tc.name, &tc.input, &ctx).await {
                            Some(Ok(output)) => (output, false),
                            Some(Err(e)) => (e.to_string(), true),
                            None => (format!("Tool '{}' not found", tc.name), true),
                        }
                    } else {
                        (format!("Tool '{}' not found", tc.name), true)
                    }
                }
            };

            results.push((output, is_error));
        }

        results
    }

    /// Build a ToolContext from the runner's current state.
    fn build_tool_context(&self) -> ToolContext {
        let mut ctx = ToolContext {
            session_id: self.session_id.clone(),
            agent_id: self.def.name.clone(),
            working_directory: std::env::current_dir()
                .map(|p| p.to_string_lossy().to_string())
                .unwrap_or_else(|_| "/tmp".into()),
            depth: self.depth,
            agent_def: Some(self.def.clone()),
            messages: self.messages.clone(),
            tool_call_id: self.tool_call_counter,
            ..Default::default()
        };

        if let Some(res) = &self.resources {
            ctx.read_tracker = Some(res.read_tracker.clone());
            ctx.file_history = Some(res.file_history.clone());
            ctx.file_lock_manager = Some(res.file_lock_manager.clone());
            ctx.project_root = res.project_root.clone();
            ctx.llm = Some(res.llm.clone());
            ctx.device_transfer = res.device_transfer.clone();
            // Inject the AgentSpawner so Delegate tool can spawn sub-agents
            ctx.agent_spawner = Some(Arc::new(AgentSpawnerImpl {
                resources: res.clone(),
                tools: self.get_tool_registry(),
                agent_def: self.def.clone(),
                persistent_handles: Some(self.persistent_handles.clone()),
            }));
        }

        // Inject flow membership and agent mailer for Mail tool
        ctx.flow_membership = self.flow_membership.clone();
        if let (Some(membership), Some(registry)) = (&self.flow_membership, &self.mail_registry) {
            ctx.agent_mailer = Some(Arc::new(AgentMailerImpl {
                membership: membership.clone(),
                mail_registry: registry.clone(),
            }));
        }

        ctx
    }

    /// Get tool definitions for the LLM request.
    /// Merges built-in tool definitions with MCP server tool definitions.
    async fn get_tool_definitions(&self) -> Option<Vec<nebflow_core::types::ToolDefinition>> {
        let registry = self.get_tool_registry();
        let mut defs: Vec<nebflow_core::types::ToolDefinition> = if self.def.tools.is_empty() {
            Vec::new()
        } else {
            registry.definitions(Some(&self.def.tools))
        };

        // Merge MCP tool definitions if the agent has MCP servers configured
        if !self.def.mcp_servers.is_empty() {
            if let Some(res) = &self.resources {
                let mcp_defs = res.mcp_manager.tool_definitions().await;
                defs.extend(mcp_defs);
            }
        }

        if defs.is_empty() {
            None
        } else {
            Some(defs)
        }
    }

    /// Get the tool registry (from resources or a standalone one).
    fn get_tool_registry(&self) -> Arc<ToolRegistry> {
        if let Some(t) = &self.tools {
            t.clone()
        } else if let Some(res) = &self.resources {
            res.tool_registry.clone()
        } else {
            // Fallback: create a fresh builtin registry.
            // This is inefficient but only hit when no resources are configured.
            Arc::new(ToolRegistry::builtin())
        }
    }

    /// Get thinking config if enabled.
    fn get_thinking_config(&self) -> Option<serde_json::Value> {
        if let Some(res) = &self.resources {
            // Try to read thinking config — use try_read to avoid async context issues.
            // For now, we use a blocking read which is fine since thinking_config
            // is rarely contended.
            let guard = res.thinking_config.try_read();
            if let Ok(config) = guard {
                if config.enabled {
                    return Some(serde_json::json!({
                        "type": "enabled",
                        "budget_tokens": config.budget_tokens
                    }));
                }
            }
        }
        None
    }
}

/// Truncate tool result to the maximum allowed size.
fn truncate_result(result: &str, max_size: usize) -> String {
    if result.len() <= max_size {
        result.to_string()
    } else {
        format!(
            "{}\n\n[Output truncated: {} → {} chars]",
            &result[..max_size],
            result.len(),
            max_size
        )
    }
}

/// AgentSpawnerImpl — implements AgentSpawner trait for the Delegate tool.
/// Uses AgentBuilder to actually spawn sub-agents.
struct AgentSpawnerImpl {
    resources: Arc<SharedResources>,
    tools: Arc<ToolRegistry>,
    agent_def: AgentDef,
    /// Persistent agent handles — keeps spawned persistent agents alive.
    /// Keyed by session ID. When the parent agent stops, these are dropped.
    persistent_handles:
        Option<Arc<tokio::sync::Mutex<std::collections::HashMap<String, crate::AgentHandle>>>>,
}

#[async_trait::async_trait]
impl nebflow_core::types::AgentSpawner for AgentSpawnerImpl {
    async fn spawn(
        &self,
        params: nebflow_core::types::SpawnParams,
    ) -> Result<nebflow_core::types::SpawnResult, String> {
        // For ephemeral lifecycle: spawn the agent, send the prompt, wait for
        // completion, and collect the result.
        // For persistent lifecycle: spawn the agent and return immediately.

        let depth = params.parent_depth + 1;
        let session_id = format!("{}-sub-{}", params.session_id, now_millis());

        // Determine the agent definition to use:
        // - If agent name is specified, load it from the library
        // - If flow is specified, create a flow executor agent
        // - Otherwise, clone the current agent (self-clone)
        let sub_def = if let Some(agent_name) = &params.agent {
            // Try to load the named agent from the library
            let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
            let agents_dir = std::path::PathBuf::from(home)
                .join(".nebflow")
                .join("agents");
            let library = crate::library::AgentLibrary::new(agents_dir);
            library
                .get(agent_name)
                .ok_or_else(|| format!("Agent '{agent_name}' not found in library"))?
        } else {
            // Self-clone: use the parent agent's definition
            self.agent_def.clone()
        };

        // Build initial messages
        let initial_messages = if params.fork {
            // Fork: pass current conversation context
            // In a full implementation, this would copy the parent's messages.
            // For now, just pass the prompt as the first user message.
            vec![nebflow_core::types::Message {
                role: nebflow_core::types::MessageRole::User,
                content: nebflow_core::types::MessageContent::Text(params.prompt.clone()),
                timestamp: now_millis(),
            }]
        } else {
            vec![nebflow_core::types::Message {
                role: nebflow_core::types::MessageRole::User,
                content: nebflow_core::types::MessageContent::Text(params.prompt.clone()),
                timestamp: now_millis(),
            }]
        };

        // Spawn the sub-agent
        let handle = crate::AgentBuilder::new(sub_def)
            .with_llm(self.resources.clone())
            .with_tools(self.tools.clone())
            .with_session(&session_id)
            .with_messages(initial_messages)
            .with_depth(depth)
            .spawn();

        let address = session_id.clone();

        if params.lifecycle == "persistent" {
            // Persistent: store the handle so it stays alive and can receive
            // follow-up Mail messages. The handle is kept in the persistent
            // registry keyed by session ID.
            if let Some(registry) = &self.persistent_handles {
                registry.lock().await.insert(session_id.clone(), handle);
            } else {
                // No registry — leak as fallback (legacy behavior)
                std::mem::forget(handle);
            }
            return Ok(nebflow_core::types::SpawnResult {
                address,
                started: true,
            });
        }

        // Ephemeral: wait for the agent to complete and collect results
        // The agent will process the prompt and emit events.
        // We drain events until Done or timeout.
        let mut agent_handle = handle;
        let mut got_done = false;

        // Wait up to 5 minutes for the sub-agent to complete
        let timeout = std::time::Duration::from_secs(300);
        let deadline = tokio::time::Instant::now() + timeout;

        loop {
            tokio::select! {
                event = agent_handle.recv() => {
                    match event {
                        Some(nebflow_core::protocol::AgentEvent::Done { .. }) => {
                            got_done = true;
                            break;
                        }
                        Some(_) => { /* other events — ignore */ }
                        None => break, // Agent finished
                    }
                }
                _ = tokio::time::sleep_until(deadline) => {
                    // Timeout — stop the agent
                    break;
                }
            }
        }

        // Stop the agent gracefully
        agent_handle.stop().await;

        let _ = got_done;

        Ok(nebflow_core::types::SpawnResult {
            address,
            started: true,
        })
    }
}

/// AgentMailerImpl — implements AgentMailer trait for the Mail tool.
/// Uses FlowMembership to resolve the target session ID, then forwards
/// the message to the target agent's message channel via AgentMessage.
///
/// The actual message delivery uses a shared registry of agent message
/// senders (AgentMailRegistry) that maps session_id → mpsc::Sender<AgentMessage>.
/// When agents are spawned, they register their message sender so that
/// other agents can send Mail to them.
struct AgentMailerImpl {
    membership: Arc<nebflow_core::flow::membership::FlowMembership>,
    mail_registry: Arc<AgentMailRegistry>,
}

/// Registry of agent message senders, keyed by session ID.
/// When an agent is spawned, its message sender is registered here so
/// that other agents can deliver Mail to it.
///
/// Also tracks pending fork requests — when a Mail fork call is made,
/// a oneshot sender is registered here. When the target agent finishes
/// its turn (Done), the AgentRunner looks up the pending fork and sends
/// the collected reply text.
pub struct AgentMailRegistry {
    senders: tokio::sync::RwLock<std::collections::HashMap<String, mpsc::Sender<AgentMessage>>>,
    /// Pending fork requests: target_session_id → oneshot sender for reply text.
    pending_forks: tokio::sync::RwLock<
        std::collections::HashMap<String, tokio::sync::oneshot::Sender<String>>,
    >,
}

impl AgentMailRegistry {
    pub fn new() -> Self {
        Self {
            senders: tokio::sync::RwLock::new(std::collections::HashMap::new()),
            pending_forks: tokio::sync::RwLock::new(std::collections::HashMap::new()),
        }
    }

    /// Register a message sender for a session.
    pub async fn register(&self, session_id: &str, tx: mpsc::Sender<AgentMessage>) {
        self.senders
            .write()
            .await
            .insert(session_id.to_string(), tx);
    }

    /// Remove a session's sender (when the agent stops).
    pub async fn unregister(&self, session_id: &str) {
        self.senders.write().await.remove(session_id);
    }

    /// Get the sender for a session.
    pub async fn get(&self, session_id: &str) -> Option<mpsc::Sender<AgentMessage>> {
        self.senders.read().await.get(session_id).cloned()
    }

    /// Register a pending fork request. Returns the receiver that will
    /// receive the reply text when the target agent completes its turn.
    pub async fn register_fork(
        &self,
        target_session_id: &str,
    ) -> tokio::sync::oneshot::Receiver<String> {
        let (tx, rx) = tokio::sync::oneshot::channel();
        self.pending_forks
            .write()
            .await
            .insert(target_session_id.to_string(), tx);
        rx
    }

    /// Complete a pending fork request — called by AgentRunner when the
    /// target agent finishes its turn. Returns true if a fork was waiting.
    pub async fn complete_fork(&self, target_session_id: &str, reply: String) -> bool {
        if let Some(tx) = self.pending_forks.write().await.remove(target_session_id) {
            let _ = tx.send(reply);
            true
        } else {
            false
        }
    }

    /// Cancel a pending fork request (e.g. on timeout).
    pub async fn cancel_fork(&self, target_session_id: &str) {
        self.pending_forks.write().await.remove(target_session_id);
    }
}

impl Default for AgentMailRegistry {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait::async_trait]
impl nebflow_core::types::AgentMailer for AgentMailerImpl {
    async fn send_mail(
        &self,
        sender_session_id: &str,
        address: &str,
        message: &str,
        msg_type: &str,
        fork: bool,
    ) -> Result<nebflow_core::types::MailResult, String> {
        // Resolve the target session ID via FlowMembership
        let target_session = self
            .membership
            .resolve_session_id(sender_session_id, address)
            .await
            .ok_or_else(|| {
                format!("Cannot resolve address '{address}' from session '{sender_session_id}'")
            })?;

        // Get the target agent's message sender
        let tx = self
            .mail_registry
            .get(&target_session)
            .await
            .ok_or_else(|| {
                format!("Target agent '{address}' (session {target_session}) is not running")
            })?;

        if fork {
            // Fork mode: register a pending fork, send ImmediateInput,
            // then wait for the target agent's Done event with the reply text.
            let rx = self.mail_registry.register_fork(&target_session).await;

            let _ = tx
                .send(AgentMessage::ImmediateInput {
                    text: format!("[Mail from {sender_session_id} ({msg_type})] {message}"),
                    blocks: None,
                })
                .await
                .map_err(|e| format!("Failed to send mail: {e}"))?;

            // Wait up to 120 seconds for the fork response
            let timeout = std::time::Duration::from_secs(120);
            match tokio::time::timeout(timeout, rx).await {
                Ok(Ok(reply)) => Ok(nebflow_core::types::MailResult {
                    delivered: true,
                    address: Some(target_session),
                    reply: Some(reply),
                }),
                Ok(Err(_)) => {
                    // oneshot sender dropped (agent stopped)
                    Ok(nebflow_core::types::MailResult {
                        delivered: true,
                        address: Some(target_session),
                        reply: Some("(target agent stopped without response)".into()),
                    })
                }
                Err(_) => {
                    // Timeout — cancel the pending fork
                    self.mail_registry.cancel_fork(&target_session).await;
                    Ok(nebflow_core::types::MailResult {
                        delivered: true,
                        address: Some(target_session),
                        reply: Some("(fork response timed out after 120s)".into()),
                    })
                }
            }
        } else {
            // Async mode: send the message, return immediately.
            let _ = tx
                .send(AgentMessage::ImmediateInput {
                    text: format!("[Mail from {sender_session_id} ({msg_type})] {message}"),
                    blocks: None,
                })
                .await
                .map_err(|e| format!("Failed to send mail: {e}"))?;

            Ok(nebflow_core::types::MailResult {
                delivered: true,
                address: Some(target_session),
                reply: None,
            })
        }
    }
}

fn now_millis() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

// ============================================================
// Tests
// ============================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::AgentBuilder;
    use nebflow_core::types::{AgentCategory, AgentDef};

    fn test_def() -> AgentDef {
        AgentDef {
            name: "test".into(),
            description: "test agent".into(),
            tools: vec![],
            system_prompt: "You are a test agent".into(),
            avatar: None,
            display_name: None,
            voice_enabled: false,
            model: None,
            category: AgentCategory::Standalone,
            mcp_servers: vec![],
        }
    }

    #[tokio::test]
    async fn spawn_and_stop() {
        let handle = AgentBuilder::new(test_def()).spawn();
        handle.stop().await;
    }

    #[tokio::test]
    async fn send_input_and_stop() {
        let handle = AgentBuilder::new(test_def()).spawn();
        handle.send_user_input("hello").await;
        // process_turn will emit Error (no resources) and return, then Stop drains
        let _ = tokio::time::timeout(std::time::Duration::from_secs(2), handle.stop()).await;
    }

    #[tokio::test]
    async fn with_session_and_depth() {
        let def = test_def();
        let handle = AgentBuilder::new(def)
            .with_session("my-session")
            .with_depth(2)
            .spawn();
        handle.stop().await;
    }

    #[tokio::test]
    async fn with_messages() {
        let def = test_def();
        let initial = vec![Message {
            role: MessageRole::User,
            content: MessageContent::Text("previous message".into()),
            timestamp: 0,
        }];
        let handle = AgentBuilder::new(def).with_messages(initial).spawn();
        handle.stop().await;
    }

    #[test]
    fn truncate_result_short() {
        let result = truncate_result("hello", 100);
        assert_eq!(result, "hello");
    }

    #[test]
    fn truncate_result_long() {
        let long = "x".repeat(200);
        let result = truncate_result(&long, 50);
        assert!(result.contains("[Output truncated"));
        assert!(result.len() < 200);
    }

    #[tokio::test]
    async fn process_turn_no_resources_emits_error() {
        let (msg_tx, msg_rx) = mpsc::channel::<AgentMessage>(64);
        let (event_tx, mut event_rx) = mpsc::channel::<AgentEvent>(64);

        let mut runner = AgentRunner::new(
            test_def(),
            msg_rx,
            event_tx,
            None, // no resources
            None,
            "test-session".into(),
            vec![],
            0,
            None,
            None,
        );

        // Push a user message and run one turn
        runner.messages.push(Message {
            role: MessageRole::User,
            content: MessageContent::Text("hello".into()),
            timestamp: 0,
        });

        let result = runner.process_turn().await;
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("no LLM resources"));

        // call_llm fails immediately (returns Err), so the ? operator
        // propagates the error before Thinking is emitted.
        // No events should have been emitted.
        let event = event_rx.try_recv();
        assert!(event.is_err(), "expected no events before error");

        drop(msg_tx);
    }

    #[tokio::test]
    async fn tool_context_builds_correctly() {
        let (msg_tx, msg_rx) = mpsc::channel::<AgentMessage>(64);
        let (_event_tx, _event_rx) = mpsc::channel::<AgentEvent>(64);

        let runner = AgentRunner::new(
            test_def(),
            msg_rx,
            _event_tx,
            None,
            None,
            "session-1".into(),
            vec![],
            3,
            Some(100_000),
            None,
        );

        assert_eq!(runner.session_id, "session-1");
        assert_eq!(runner.depth, 3);
        assert_eq!(runner.context_window, 100_000);
        assert!(runner.agent_model.is_none());

        let ctx = runner.build_tool_context();
        assert_eq!(ctx.session_id, "session-1");
        assert_eq!(ctx.depth, 3);
        assert!(ctx.agent_def.is_some());

        drop(msg_tx);
    }

    #[tokio::test]
    async fn process_turn_with_resources_emits_events() {
        use crate::resources::SharedResources;
        use nebflow_core::config::ThinkingConfig;

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
        let config: nebflow_core::config::NebflowServiceConfig =
            serde_json::from_str(config_json).unwrap();
        let resources = Arc::new(SharedResources::new(
            config,
            std::path::PathBuf::from("/tmp"),
            ThinkingConfig {
                enabled: false,
                budget_tokens: 0,
            },
        ));

        let (msg_tx, msg_rx) = mpsc::channel::<AgentMessage>(64);
        let (event_tx, mut event_rx) = mpsc::channel::<AgentEvent>(64);

        let mut runner = AgentRunner::new(
            test_def(),
            msg_rx,
            event_tx,
            Some(resources),
            None,
            "test-session".into(),
            vec![],
            0,
            None,
            None,
        );

        runner.messages.push(Message {
            role: MessageRole::User,
            content: MessageContent::Text("hello".into()),
            timestamp: 0,
        });

        // The turn will fail (localhost:1 unreachable) but should emit
        // at least a Thinking event before the error.
        let _ = runner.process_turn().await;

        // Collect events — should get at least Thinking
        let mut got_thinking = false;
        for _ in 0..10 {
            match event_rx.try_recv() {
                Ok(AgentEvent::Thinking) => {
                    got_thinking = true;
                    break;
                }
                Ok(_) => {}
                Err(_) => break,
            }
        }
        assert!(got_thinking, "should have emitted Thinking event");

        drop(msg_tx);
    }

    #[test]
    fn agent_runner_new_with_context_window_override() {
        let (_tx, msg_rx) = mpsc::channel::<AgentMessage>(64);
        let (_event_tx, _event_rx) = mpsc::channel::<AgentEvent>(64);

        let runner = AgentRunner::new(
            test_def(),
            msg_rx,
            _event_tx,
            None,
            None,
            "s".into(),
            vec![],
            0,
            Some(50_000),
            None,
        );

        assert_eq!(runner.context_window, 50_000);
    }

    #[tokio::test]
    async fn run_processes_user_input_then_stops() {
        let def = test_def();
        let handle = AgentBuilder::new(def).with_session("test-run").spawn();

        // Send a few inputs — the agent should accept them without crashing
        handle.send_user_input("hello").await;
        handle.send_user_input("world").await;

        // Stop should cleanly shut down (timeout in case process_turn hangs without resources)
        let _ = tokio::time::timeout(std::time::Duration::from_secs(2), handle.stop()).await;
    }

    #[tokio::test]
    async fn interrupt_cancels_turn() {
        let def = test_def();
        let mut handle = AgentBuilder::new(def)
            .with_session("interrupt-test")
            .spawn();

        // Send user input then immediately send Interrupt
        handle.send_user_input("hello").await;
        handle.send_message(AgentMessage::Interrupt).await.unwrap();

        // The agent should emit Interrupted event (or Done)
        let mut got_interrupted = false;
        let mut got_done = false;
        // Give it some time to process
        for _ in 0..100 {
            match handle.try_recv() {
                Some(AgentEvent::Interrupted) => {
                    got_interrupted = true;
                    break;
                }
                Some(AgentEvent::Done { .. }) => {
                    got_done = true;
                    break;
                }
                _ => {}
            }
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
        assert!(
            got_interrupted || got_done,
            "should have received Interrupted or Done event"
        );

        handle.stop().await;
    }

    #[tokio::test]
    async fn process_turn_uses_system_dynamic() {
        use crate::resources::SharedResources;
        use nebflow_core::config::ThinkingConfig;

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
        let config: nebflow_core::config::NebflowServiceConfig =
            serde_json::from_str(config_json).unwrap();
        let resources = Arc::new(SharedResources::new(
            config,
            std::path::PathBuf::from("/tmp"),
            ThinkingConfig {
                enabled: false,
                budget_tokens: 0,
            },
        ));

        let (msg_tx, msg_rx) = mpsc::channel::<AgentMessage>(64);
        let (_event_tx, _event_rx) = mpsc::channel::<AgentEvent>(64);

        let runner = AgentRunner::new(
            test_def(),
            msg_rx,
            _event_tx,
            Some(resources),
            None,
            "test-dynamic".into(),
            vec![],
            0,
            None,
            None,
        );

        // Verify that build_system_stable and build_system_dynamic work
        let prompt_ctx = context::build_prompt_context(&runner.def, runner.depth as u32, None);
        let stable = context::build_system_stable(&runner.def);
        let dynamic = context::build_system_dynamic(&prompt_ctx);
        assert!(stable.is_some());
        assert_eq!(stable.as_deref(), Some("You are a test agent"));
        // With no conditional blocks (empty PromptContext), dynamic should be None
        assert!(dynamic.is_none());

        drop(msg_tx);
    }

    #[tokio::test]
    async fn process_turn_with_rules_emits_system_dynamic() {
        // Test that when rules_md is set in PromptContext, system_dynamic is non-empty
        let def = test_def();
        let prompt_ctx = crate::prompt::PromptContext {
            rules_md: Some("Always test".into()),
            ..Default::default()
        };
        let stable = context::build_system_stable(&def);
        let dynamic = context::build_system_dynamic(&prompt_ctx);
        assert!(stable.is_some());
        assert!(dynamic.is_some());
        assert!(dynamic.as_deref().unwrap().contains("Always test"));
        assert!(dynamic.as_deref().unwrap().contains("Project Rules"));
    }
}
