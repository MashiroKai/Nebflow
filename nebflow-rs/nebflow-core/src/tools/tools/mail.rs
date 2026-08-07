//! MailTool — agent-to-agent communication.
//! Mirrors `nebflow.core.tools.MailTool` from Scala.

use crate::types::{ToolContext, ToolError};
use crate::Tool;
use async_trait::async_trait;

pub struct MailTool {
    schema: serde_json::Map<String, serde_json::Value>,
}

impl MailTool {
    pub fn new() -> Self {
        let mut schema = serde_json::Map::new();
        schema.insert("type".into(), "object".into());
        let mut props = serde_json::Map::new();
        props.insert("address".into(), serde_json::json!({"type": "string", "description": "Recipient: team name or agent name"}));
        props.insert(
            "message".into(),
            serde_json::json!({"type": "string", "description": "The message or question to send"}),
        );
        props.insert("fork".into(), serde_json::json!({"type": "boolean", "description": "If true, fork target's context and get immediate response"}));
        props.insert("type".into(), serde_json::json!({"type": "string", "enum": ["INFO", "FOLLOW_UP", "PARALLEL", "INTERRUPT", "RESULT"], "description": "Message type tag"}));
        schema.insert("properties".into(), serde_json::Value::Object(props));
        schema.insert("required".into(), serde_json::json!(["address", "message"]));
        Self { schema }
    }
}

impl Default for MailTool {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Tool for MailTool {
    fn name(&self) -> &str {
        "Mail"
    }
    fn description(&self) -> &str {
        "Send a message to an agent within your team.\n\nRequired: address, message\n\nThe address can be a team name or an agent name.\n\nDefault mode (fork omitted or false): Async send. If the recipient is idle, delivered immediately. If busy, queued.\n\nFork mode (fork: true): Forks the target agent's context, asks the question, and returns the answer immediately."
    }
    fn input_schema(&self) -> &serde_json::Map<String, serde_json::Value> {
        &self.schema
    }

    async fn call(
        &self,
        input: &serde_json::Map<String, serde_json::Value>,
        ctx: &ToolContext,
    ) -> Result<String, ToolError> {
        let address = super::get_str(input, "address").unwrap_or("");
        let message = super::get_str(input, "message").unwrap_or("");
        let fork = super::get_bool(input, "fork").unwrap_or(false);
        let msg_type = super::get_str(input, "type").unwrap_or("INFO");

        if address.is_empty() {
            return Err(ToolError::InvalidInput("address is required".into()));
        }
        if message.is_empty() {
            return Err(ToolError::InvalidInput("message is required".into()));
        }

        // Validate message type
        let valid_types = ["INFO", "FOLLOW_UP", "PARALLEL", "INTERRUPT", "RESULT"];
        if !valid_types.contains(&msg_type) {
            return Err(ToolError::InvalidInput(format!(
                "Invalid message type: {msg_type}"
            )));
        }

        // If an AgentMailer is available, use it to actually deliver the message.
        if let Some(mailer) = &ctx.agent_mailer {
            match mailer
                .send_mail(&ctx.session_id, address, message, msg_type, fork)
                .await
            {
                Ok(result) => {
                    if fork {
                        let reply = result.reply.unwrap_or_else(|| "(no response)".into());
                        let addr = result.address.unwrap_or_else(|| address.to_string());
                        return Ok(format!(
                            "[Mail fork] Address: {addr}, Type: [{msg_type}]\nReply: {reply}"
                        ));
                    } else if result.delivered {
                        let addr = result.address.unwrap_or_else(|| address.to_string());
                        return Ok(format!(
                            "[Mail sent] Address: {addr}, Type: [{msg_type}]\nMessage delivered."
                        ));
                    } else {
                        return Ok(format!(
                            "[Mail queued] Address: {address}, Type: [{msg_type}]\nRecipient is busy — message queued for delivery."
                        ));
                    }
                }
                Err(e) => {
                    return Err(ToolError::Execution(format!("Mail delivery failed: {e}")));
                }
            }
        }

        // Fallback: no mailer available — return formatted string (stub mode).
        if fork {
            Ok(format!("[Mail fork] Address: {address}, Type: [{msg_type}]\nFork mode: will get immediate response."))
        } else {
            Ok(format!("[Mail sent] Address: {address}, Type: [{msg_type}]\nMessage delivered asynchronously."))
        }
    }

    fn summarize(&self, input: &serde_json::Map<String, serde_json::Value>) -> String {
        let address = super::get_str(input, "address").unwrap_or("?");
        format!("Mail({address})")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::{AgentMailer, MailResult};
    use std::sync::Arc;

    fn ctx() -> ToolContext {
        ToolContext {
            session_id: "test".into(),
            agent_id: "test".into(),
            working_directory: "/tmp".into(),
            depth: 0,
            ..Default::default()
        }
    }

    struct MockMailer {
        result: Result<MailResult, String>,
    }

    #[async_trait::async_trait]
    impl AgentMailer for MockMailer {
        async fn send_mail(
            &self,
            _sender_session_id: &str,
            _address: &str,
            _message: &str,
            _msg_type: &str,
            _fork: bool,
        ) -> Result<MailResult, String> {
            self.result.clone()
        }
    }

    fn ctx_with_mailer(result: Result<MailResult, String>) -> ToolContext {
        let mailer: Arc<dyn AgentMailer> = Arc::new(MockMailer { result });
        ToolContext {
            session_id: "test".into(),
            agent_id: "test".into(),
            working_directory: "/tmp".into(),
            depth: 0,
            agent_mailer: Some(mailer),
            ..Default::default()
        }
    }

    #[tokio::test]
    async fn mail_basic() {
        let tool = MailTool::new();
        let mut input = serde_json::Map::new();
        input.insert("address".into(), "backend".into());
        input.insert("message".into(), "fix the bug".into());
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.contains("backend"));
        assert!(result.contains("[INFO]"));
    }

    #[tokio::test]
    async fn mail_fork_mode() {
        let tool = MailTool::new();
        let mut input = serde_json::Map::new();
        input.insert("address".into(), "backend".into());
        input.insert("message".into(), "status?".into());
        input.insert("fork".into(), true.into());
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.contains("fork"));
    }

    #[tokio::test]
    async fn mail_empty_address() {
        let tool = MailTool::new();
        let mut input = serde_json::Map::new();
        input.insert("message".into(), "hello".into());
        let result = tool.call(&input, &ctx()).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn mail_empty_message() {
        let tool = MailTool::new();
        let mut input = serde_json::Map::new();
        input.insert("address".into(), "backend".into());
        let result = tool.call(&input, &ctx()).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn mail_interrupt_type() {
        let tool = MailTool::new();
        let mut input = serde_json::Map::new();
        input.insert("address".into(), "backend".into());
        input.insert("message".into(), "urgent!".into());
        input.insert("type".into(), "INTERRUPT".into());
        let result = tool.call(&input, &ctx()).await.unwrap();
        assert!(result.contains("[INTERRUPT]"));
    }

    #[tokio::test]
    async fn mail_with_mailer_async() {
        let tool = MailTool::new();
        let mut input = serde_json::Map::new();
        input.insert("address".into(), "backend".into());
        input.insert("message".into(), "fix the bug".into());
        let result = tool
            .call(
                &input,
                &ctx_with_mailer(Ok(MailResult {
                    delivered: true,
                    address: Some("backend-session".into()),
                    reply: None,
                })),
            )
            .await
            .unwrap();
        assert!(result.contains("[Mail sent]"));
        assert!(result.contains("backend-session"));
    }

    #[tokio::test]
    async fn mail_with_mailer_fork() {
        let tool = MailTool::new();
        let mut input = serde_json::Map::new();
        input.insert("address".into(), "backend".into());
        input.insert("message".into(), "status?".into());
        input.insert("fork".into(), true.into());
        let result = tool
            .call(
                &input,
                &ctx_with_mailer(Ok(MailResult {
                    delivered: true,
                    address: Some("backend-session".into()),
                    reply: Some("all good".into()),
                })),
            )
            .await
            .unwrap();
        assert!(result.contains("[Mail fork]"));
        assert!(result.contains("all good"));
    }

    #[tokio::test]
    async fn mail_with_mailer_queued() {
        let tool = MailTool::new();
        let mut input = serde_json::Map::new();
        input.insert("address".into(), "backend".into());
        input.insert("message".into(), "fix the bug".into());
        let result = tool
            .call(
                &input,
                &ctx_with_mailer(Ok(MailResult {
                    delivered: false,
                    address: None,
                    reply: None,
                })),
            )
            .await
            .unwrap();
        assert!(result.contains("[Mail queued]"));
    }

    #[tokio::test]
    async fn mail_with_failing_mailer() {
        let tool = MailTool::new();
        let mut input = serde_json::Map::new();
        input.insert("address".into(), "backend".into());
        input.insert("message".into(), "fix the bug".into());
        let result = tool
            .call(&input, &ctx_with_mailer(Err("agent not found".into())))
            .await;
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("agent not found"));
    }
}
