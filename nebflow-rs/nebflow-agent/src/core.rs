//! Agent core — pipeLlmCall + pipeToolExecutions logic.

use crate::compact;
use nebflow_core::types::{
    ContentBlock, LlmRequest, Message, MessageContent, MessageRole, StreamChunk, TokenUsage,
    ToolCall, ToolDefinition,
};
use nebflow_core::ChunkStream;

/// Result of initiating an LLM call — either a stream to consume, or an error.
pub enum StreamResult {
    Stream(ChunkStream),
    Error(String),
}

#[derive(Debug, Clone, Default)]
pub struct ConsumeResult {
    pub text: String,
    pub tool_calls: Vec<ToolCall>,
    pub stop_reason: Option<String>,
    pub usage: Option<TokenUsage>,
    pub thinking: Option<String>,
}

pub async fn consume_llm_stream(mut stream: ChunkStream) -> ConsumeResult {
    use futures::StreamExt;
    let mut result = ConsumeResult::default();
    let mut thinking_parts: Vec<String> = vec![];
    while let Some(chunk_result) = stream.next().await {
        match chunk_result {
            Ok(chunk) => match chunk {
                StreamChunk::TextDelta(text) => {
                    result.text.push_str(&text);
                }
                StreamChunk::ThinkingDelta(text) => {
                    thinking_parts.push(text);
                }
                StreamChunk::ToolCallComplete(tc) => {
                    result.tool_calls.push(tc);
                }
                StreamChunk::Done {
                    stop_reason, usage, ..
                } => {
                    result.stop_reason = stop_reason;
                    result.usage = usage;
                }
                _ => {}
            },
            Err(_) => break,
        }
    }
    if !thinking_parts.is_empty() {
        result.thinking = Some(thinking_parts.join(""));
    }
    result
}

/// Consume an LLM stream with interrupt support.
/// If the `cancel` signal fires, the stream is dropped immediately and
/// whatever has been accumulated so far is returned.
pub async fn consume_llm_stream_cancellable(
    stream: ChunkStream,
    cancel: tokio_util::sync::CancellationToken,
) -> ConsumeResult {
    use futures::StreamExt;
    let mut result = ConsumeResult::default();
    let mut thinking_parts: Vec<String> = vec![];
    let mut stream = stream;

    loop {
        tokio::select! {
            _ = cancel.cancelled() => {
                // Interrupted — return what we have so far
                break;
            }
            chunk_result = stream.next() => {
                match chunk_result {
                    Some(Ok(chunk)) => match chunk {
                        StreamChunk::TextDelta(text) => { result.text.push_str(&text); }
                        StreamChunk::ThinkingDelta(text) => { thinking_parts.push(text); }
                        StreamChunk::ToolCallComplete(tc) => { result.tool_calls.push(tc); }
                        StreamChunk::Done { stop_reason, usage, .. } => {
                            result.stop_reason = stop_reason;
                            result.usage = usage;
                        }
                        _ => {}
                    },
                    Some(Err(_)) => break,
                    None => break,
                }
            }
        }
    }
    if !thinking_parts.is_empty() {
        result.thinking = Some(thinking_parts.join(""));
    }
    result
}

#[allow(clippy::too_many_arguments)]
pub fn build_llm_request(
    messages: Vec<Message>,
    system_stable: Option<String>,
    system_dynamic: Option<String>,
    tools: Option<Vec<ToolDefinition>>,
    session_id: &str,
    agent_id: &str,
    max_tokens: Option<usize>,
    thinking: Option<serde_json::Value>,
    agent_model: Option<nebflow_core::types::AgentModelConfig>,
) -> LlmRequest {
    LlmRequest {
        messages,
        session_id: session_id.into(),
        agent_id: agent_id.into(),
        tools,
        max_tokens,
        thinking,
        system_stable,
        system_dynamic,
        agent_model,
    }
}

pub fn build_tool_result_messages(
    tool_calls: &[ToolCall],
    results: &[(String, String, bool)],
) -> Vec<Message> {
    let blocks: Vec<ContentBlock> = tool_calls
        .iter()
        .zip(results.iter())
        .map(|(tc, (_id, output, is_error))| ContentBlock::ToolResult {
            tool_use_id: tc.id.clone(),
            content: output.clone(),
            is_error: if *is_error { Some(true) } else { None },
        })
        .collect();
    vec![Message {
        role: MessageRole::User,
        content: MessageContent::Blocks(blocks),
        timestamp: now_millis(),
    }]
}

pub fn build_assistant_message(result: &ConsumeResult) -> Message {
    let mut blocks = vec![];
    if let Some(thinking) = &result.thinking {
        blocks.push(ContentBlock::Thinking {
            thinking: thinking.clone(),
            signature: None,
        });
    }
    if !result.text.is_empty() {
        blocks.push(ContentBlock::Text {
            text: result.text.clone(),
        });
    }
    for tc in &result.tool_calls {
        blocks.push(ContentBlock::ToolUse {
            id: tc.id.clone(),
            name: tc.name.clone(),
            input: tc.input.clone(),
        });
    }
    Message {
        role: MessageRole::Assistant,
        content: if blocks.is_empty() {
            MessageContent::Text(result.text.clone())
        } else {
            MessageContent::Blocks(blocks)
        },
        timestamp: now_millis(),
    }
}

pub fn maybe_micro_compact(
    messages: &[Message],
    context_window: usize,
    config: &compact::CompactConfig,
) -> Option<Vec<Message>> {
    if compact::should_compact(messages, context_window, config) {
        compact::fast_micro_compact(messages, config.micro_keep_recent)
    } else {
        None
    }
}

fn now_millis() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

pub struct MockLlmProvider {
    pub chunks: Vec<Result<StreamChunk, nebflow_core::types::LlmError>>,
}
impl MockLlmProvider {
    pub fn new(chunks: Vec<StreamChunk>) -> Self {
        Self {
            chunks: chunks.into_iter().map(Ok).collect(),
        }
    }
    pub fn text_response(text: &str) -> Self {
        Self::new(vec![
            StreamChunk::TextDelta(text.into()),
            StreamChunk::Done {
                stop_reason: Some("end_turn".into()),
                usage: Some(TokenUsage {
                    input_tokens: 100,
                    output_tokens: 50,
                    cache_read_tokens: None,
                    cache_write_tokens: None,
                }),
                context_window: Some(200_000),
            },
        ])
    }
    pub fn tool_call_response(tool_name: &str, tool_id: &str, args: serde_json::Value) -> Self {
        let input = match args {
            serde_json::Value::Object(m) => m,
            _ => serde_json::Map::new(),
        };
        Self::new(vec![
            StreamChunk::ToolCallStart {
                name: tool_name.into(),
            },
            StreamChunk::ToolCallComplete(ToolCall {
                id: tool_id.into(),
                name: tool_name.into(),
                input,
            }),
            StreamChunk::Done {
                stop_reason: Some("tool_use".into()),
                usage: Some(TokenUsage {
                    input_tokens: 100,
                    output_tokens: 50,
                    cache_read_tokens: None,
                    cache_write_tokens: None,
                }),
                context_window: Some(200_000),
            },
        ])
    }
    pub fn into_stream(self) -> ChunkStream {
        Box::pin(futures::stream::iter(self.chunks))
    }
}

pub struct MockTool {
    pub name: String,
    pub result: String,
    pub is_error: bool,
}
impl MockTool {
    pub fn new(name: &str, result: &str) -> Self {
        Self {
            name: name.into(),
            result: result.into(),
            is_error: false,
        }
    }
    pub fn execute(&self, _input: &serde_json::Map<String, serde_json::Value>) -> (String, bool) {
        (self.result.clone(), self.is_error)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn consume_text_response() {
        let p = MockLlmProvider::text_response("Hello!");
        let r = consume_llm_stream(p.into_stream()).await;
        assert_eq!(r.text, "Hello!");
        assert!(r.tool_calls.is_empty());
        assert_eq!(r.stop_reason.as_deref(), Some("end_turn"));
    }

    #[tokio::test]
    async fn consume_tool_call_response() {
        let p =
            MockLlmProvider::tool_call_response("Read", "tc1", serde_json::json!({"path":"/tmp"}));
        let r = consume_llm_stream(p.into_stream()).await;
        assert_eq!(r.tool_calls.len(), 1);
        assert_eq!(r.tool_calls[0].name, "Read");
        assert_eq!(r.stop_reason.as_deref(), Some("tool_use"));
    }

    #[tokio::test]
    async fn consume_thinking_response() {
        let p = MockLlmProvider::new(vec![
            StreamChunk::ThinkingDelta("hmm".into()),
            StreamChunk::TextDelta("Answer".into()),
            StreamChunk::Done {
                stop_reason: Some("end_turn".into()),
                usage: None,
                context_window: None,
            },
        ]);
        let r = consume_llm_stream(p.into_stream()).await;
        assert_eq!(r.thinking.as_deref(), Some("hmm"));
        assert_eq!(r.text, "Answer");
    }

    #[test]
    fn build_assistant_message_text_only() {
        let r = ConsumeResult {
            text: "Hi!".into(),
            ..Default::default()
        };
        let m = build_assistant_message(&r);
        assert_eq!(m.role, MessageRole::Assistant);
        match &m.content {
            MessageContent::Blocks(b) => {
                assert_eq!(b.len(), 1);
                assert!(matches!(&b[0], ContentBlock::Text { text } if text == "Hi!"));
            }
            _ => panic!("expected blocks"),
        }
    }

    #[test]
    fn build_assistant_message_with_tool_calls() {
        let r = ConsumeResult {
            text: "Reading.".into(),
            tool_calls: vec![ToolCall {
                id: "tc1".into(),
                name: "Read".into(),
                input: Default::default(),
            }],
            ..Default::default()
        };
        let m = build_assistant_message(&r);
        match &m.content {
            MessageContent::Blocks(b) => {
                assert_eq!(b.len(), 2);
                assert!(matches!(&b[1], ContentBlock::ToolUse { name, .. } if name == "Read"));
            }
            _ => panic!("expected blocks"),
        }
    }

    #[test]
    fn test_build_tool_result_messages() {
        let tcs = vec![ToolCall {
            id: "tc1".into(),
            name: "Read".into(),
            input: Default::default(),
        }];
        let results = vec![("tc1".to_string(), "content".to_string(), false)];
        let msgs = build_tool_result_messages(&tcs, &results);
        assert_eq!(msgs.len(), 1);
        match &msgs[0].content {
            MessageContent::Blocks(b) => {
                if let ContentBlock::ToolResult { content, .. } = &b[0] {
                    assert_eq!(content, "content");
                }
            }
            _ => panic!("expected blocks"),
        }
    }

    #[test]
    fn build_llm_request_basic() {
        let req = build_llm_request(
            vec![Message {
                role: MessageRole::User,
                content: MessageContent::Text("hi".into()),
                timestamp: 0,
            }],
            Some("sys".into()),
            None,
            None,
            "s1",
            "a1",
            Some(16384),
            None,
            None,
        );
        assert_eq!(req.session_id, "s1");
        assert_eq!(req.system_stable.as_deref(), Some("sys"));
    }

    #[test]
    fn maybe_micro_compact_no_compaction_needed() {
        let config = compact::CompactConfig::default();
        let msgs = vec![Message {
            role: MessageRole::User,
            content: MessageContent::Text("short".into()),
            timestamp: 0,
        }];
        assert!(maybe_micro_compact(&msgs, 200_000, &config).is_none());
    }

    #[test]
    fn mock_tool_execute() {
        let t = MockTool::new("Read", "file content");
        let (out, err) = t.execute(&serde_json::Map::new());
        assert_eq!(out, "file content");
        assert!(!err);
    }

    #[test]
    fn mock_llm_text_response_chunks() {
        let p = MockLlmProvider::text_response("test");
        assert_eq!(p.chunks.len(), 2);
    }

    #[test]
    fn mock_llm_tool_call_response_chunks() {
        let p = MockLlmProvider::tool_call_response("Write", "tc1", serde_json::json!({}));
        assert_eq!(p.chunks.len(), 3);
    }
}
