pub mod compact;
pub mod context;
pub mod core;
pub mod language;
pub mod library;
pub mod prompt;
pub mod resources;
pub mod runtime;
pub mod usage;

// Re-export the canonical AgentMessage and AgentEvent from nebflow-core.
// The Phase 0 stub types have been replaced by the full protocol definitions.
pub use nebflow_core::protocol::{AgentEvent, AgentMessage};
// Re-export AgentMailRegistry so gateway can register agent message senders.
pub use runtime::AgentMailRegistry;

use nebflow_core::tools::ToolRegistry;
use nebflow_core::types::{AgentDef, AgentModelConfig, Message};
use resources::SharedResources;
use std::sync::Arc;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

/// Handle to a running agent. Created by `AgentBuilder::spawn()`.
pub struct AgentHandle {
    msg_tx: mpsc::Sender<AgentMessage>,
    event_rx: mpsc::Receiver<AgentEvent>,
    join_handle: Option<JoinHandle<()>>,
}

impl AgentHandle {
    /// Send user input to the agent.
    pub async fn send_user_input(&self, text: impl Into<String>) {
        let _ = self
            .msg_tx
            .send(AgentMessage::UserInput {
                text: text.into(),
                client_message_id: None,
                blocks: None,
                chat_width: 0,
            })
            .await;
    }

    /// Send a raw AgentMessage to the agent.
    pub async fn send_message(
        &self,
        msg: AgentMessage,
    ) -> Result<(), mpsc::error::SendError<AgentMessage>> {
        self.msg_tx.send(msg).await
    }

    /// Try to receive an event from the agent (non-blocking).
    pub fn try_recv(&mut self) -> Option<AgentEvent> {
        self.event_rx.try_recv().ok()
    }

    /// Receive an event from the agent (blocking, async).
    pub async fn recv(&mut self) -> Option<AgentEvent> {
        self.event_rx.recv().await
    }

    /// Stop the agent and wait for it to finish.
    pub async fn stop(mut self) {
        let _ = self
            .msg_tx
            .send(AgentMessage::Stop {
                reason: "shutdown".into(),
            })
            .await;
        if let Some(handle) = self.join_handle.take() {
            let _ = handle.await;
        }
    }
}

/// Builder for creating and spawning agents.
pub struct AgentBuilder {
    def: AgentDef,
    resources: Option<Arc<SharedResources>>,
    tools: Option<Arc<ToolRegistry>>,
    session_id: Option<String>,
    initial_messages: Vec<Message>,
    depth: usize,
    context_window: Option<usize>,
    agent_model: Option<AgentModelConfig>,
    flow_membership: Option<Arc<nebflow_core::flow::membership::FlowMembership>>,
    mail_registry: Option<Arc<AgentMailRegistry>>,
}

impl AgentBuilder {
    /// Create a new AgentBuilder from an AgentDef.
    pub fn new(def: AgentDef) -> Self {
        Self {
            def,
            resources: None,
            tools: None,
            session_id: None,
            initial_messages: Vec::new(),
            depth: 0,
            context_window: None,
            agent_model: None,
            flow_membership: None,
            mail_registry: None,
        }
    }

    /// Provide shared resources (LLM, tools, config, etc.).
    pub fn with_llm(mut self, resources: Arc<SharedResources>) -> Self {
        self.resources = Some(resources);
        self
    }

    /// Provide a custom tool registry.
    pub fn with_tools(mut self, tools: Arc<ToolRegistry>) -> Self {
        self.tools = Some(tools);
        self
    }

    /// Set the session ID for this agent.
    pub fn with_session(mut self, session_id: impl Into<String>) -> Self {
        self.session_id = Some(session_id.into());
        self
    }

    /// Provide initial message history (e.g. loaded from session store).
    pub fn with_messages(mut self, messages: Vec<Message>) -> Self {
        self.initial_messages = messages;
        self
    }

    /// Set the agent depth (0 for root, 1+ for sub-agents).
    pub fn with_depth(mut self, depth: usize) -> Self {
        self.depth = depth;
        self
    }

    /// Override the context window size.
    pub fn with_context_window(mut self, window: usize) -> Self {
        self.context_window = Some(window);
        self
    }

    /// Override the agent's model configuration.
    pub fn with_agent_model(mut self, model: AgentModelConfig) -> Self {
        self.agent_model = Some(model);
        self
    }

    /// Inject flow membership and mail registry for inter-agent Mail.
    /// When set, the spawned agent's Mail tool can resolve addresses and
    /// deliver messages to other agents in the same team/flow.
    pub fn with_membership(
        mut self,
        flow_membership: Arc<nebflow_core::flow::membership::FlowMembership>,
        mail_registry: Arc<AgentMailRegistry>,
    ) -> Self {
        self.flow_membership = Some(flow_membership);
        self.mail_registry = Some(mail_registry);
        self
    }

    /// Spawn the agent runtime task and return a handle.
    pub fn spawn(self) -> AgentHandle {
        let (msg_tx, msg_rx) = mpsc::channel::<AgentMessage>(64);
        let (event_tx, event_rx) = mpsc::channel::<AgentEvent>(64);

        let def = self.def;
        let resources = self.resources;
        let tools = self.tools;
        let session_id = self.session_id.unwrap_or_else(|| {
            format!(
                "session-{}",
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis())
                    .unwrap_or(0)
            )
        });
        let initial_messages = self.initial_messages;
        let depth = self.depth;
        let context_window = self.context_window;
        let agent_model = self.agent_model;
        let flow_membership = self.flow_membership;
        let mail_registry = self.mail_registry;

        // Register this agent's message sender in the mail registry so that
        // other agents can deliver Mail to it.
        if let Some(registry) = &mail_registry {
            // We need to clone the sender before it's moved into the runner.
            // The registry gets a clone; the original goes into the channel.
            // Note: msg_tx is already a Sender, and we clone it for the registry.
            let registry_clone = registry.clone();
            let tx_clone = msg_tx.clone();
            let sid = session_id.clone();
            tokio::spawn(async move {
                registry_clone.register(&sid, tx_clone).await;
            });
        }

        let join_handle = tokio::spawn(async move {
            let runner = runtime::AgentRunner::with_membership(
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
                flow_membership,
                mail_registry,
            );
            runner.run().await;
        });

        AgentHandle {
            msg_tx,
            event_rx,
            join_handle: Some(join_handle),
        }
    }
}
