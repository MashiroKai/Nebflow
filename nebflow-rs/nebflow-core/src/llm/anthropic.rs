//! Anthropic API adapter — message formatting, SSE parsing, request building.
//! Mirrors nebflow.llm.providers.AnthropicAdapter from Scala.

use std::collections::HashMap;

use crate::config::{self};
use crate::llm::adapter::ProviderAdapter;
#[allow(unused_imports)]
use crate::types::{
    AdapterResponse, ContentBlock, LlmError, Message, MessageContent, MessageRole,
    SendMessageParams, StreamChunk, TokenUsage, ToolCall, ToolDefinition,
};
use crate::ChunkStream;
use async_trait::async_trait;
use futures::StreamExt;
use serde_json::{json, Value};

pub struct AnthropicAdapter {
    endpoint: String,
    api_key: String,
    http_client: reqwest::Client,
}

impl AnthropicAdapter {
    pub fn new(base_url: &str, api_key: &str, http_client: reqwest::Client) -> Self {
        let base = base_url.trim_end_matches('/');
        let endpoint = if base.ends_with("/v1/messages") {
            base.to_string()
        } else {
            format!("{base}/v1/messages")
        };
        Self {
            endpoint,
            api_key: api_key.to_string(),
            http_client,
        }
    }

    /// Convert messages to Anthropic API format.
    /// Filters System messages (handled separately via system blocks).
    /// Merges consecutive same-role messages.
    fn to_anthropic_messages(messages: &[Message]) -> Vec<Value> {
        let filtered: Vec<&Message> = messages
            .iter()
            .filter(|m| m.role != MessageRole::System)
            .collect();
        let merged = merge_consecutive(&filtered);
        merged.iter().map(message_to_anthropic).collect()
    }

    /// Convert tool definitions to Anthropic tools format with cache_control on last.
    fn to_anthropic_tools(tools: &[ToolDefinition]) -> Value {
        let tool_jsons: Vec<Value> = tools
            .iter()
            .map(|t| {
                json!({
                    "name": t.name,
                    "description": t.description,
                    "input_schema": t.input_schema,
                })
            })
            .collect();

        if tool_jsons.is_empty() {
            return Value::Array(vec![]);
        }

        let (init, last) = tool_jsons.split_at(tool_jsons.len() - 1);
        let mut last_with_cache = last[0].clone();
        if let Some(obj) = last_with_cache.as_object_mut() {
            obj.insert("cache_control".into(), json!({"type": "ephemeral"}));
        }
        let mut result = init.to_vec();
        result.push(last_with_cache);
        Value::Array(result)
    }

    /// Build the system field as content blocks with cache_control on the stable prefix.
    fn build_system_blocks(params: &SendMessageParams) -> Option<Value> {
        let stable = params.system_stable.as_deref().filter(|s| !s.is_empty());
        let dynamic = params.system_dynamic.as_deref().filter(|s| !s.is_empty());

        // Fallback: extract from messages
        let fallback = params
            .messages
            .iter()
            .find(|m| m.role == MessageRole::System)
            .map(|m| m.text_content())
            .filter(|s| !s.is_empty());

        let stable = stable.or(fallback.as_deref());

        if stable.is_none() && dynamic.is_none() {
            return None;
        }

        let mut blocks = vec![];
        if let Some(text) = stable {
            blocks.push(json!({
                "type": "text",
                "text": text,
                "cache_control": {"type": "ephemeral"},
            }));
        }
        if let Some(text) = dynamic {
            blocks.push(json!({
                "type": "text",
                "text": text,
            }));
        }
        Some(Value::Array(blocks))
    }

    /// Build the full request body.
    pub fn build_request_body(&self, params: &SendMessageParams, stream: bool) -> Value {
        let mut body = json!({
            "model": params.model,
            "messages": Self::to_anthropic_messages(&params.messages),
            "max_tokens": params.max_tokens.unwrap_or(config::MAX_TOKENS),
        });

        if stream {
            body["stream"] = json!(true);
        }

        if let Some(system) = Self::build_system_blocks(params) {
            body["system"] = system;
        }

        if let Some(tools) = &params.tools {
            if !tools.is_empty() {
                body["tools"] = Self::to_anthropic_tools(tools);
            }
        }

        if let Some(thinking) = &params.thinking {
            body["thinking"] = thinking.clone();
        }

        if let Some(sid) = &params.session_id {
            body["metadata"]["session_id"] = json!(sid);
        }
        if let Some(aid) = &params.agent_id {
            body["metadata"]["agent_id"] = json!(aid);
        }

        body
    }

    /// Parse a non-streaming response JSON into AdapterResponse.
    fn parse_non_streaming_response(json: &Value) -> AdapterResponse {
        let content = json
            .get("content")
            .and_then(|c| c.as_array())
            .cloned()
            .unwrap_or_default();

        let reply: String = content
            .iter()
            .filter(|b| b.get("type").and_then(|t| t.as_str()) == Some("text"))
            .filter_map(|b| b.get("text").and_then(|t| t.as_str()))
            .collect();

        let tool_calls: Vec<ToolCall> = content
            .iter()
            .filter(|b| b.get("type").and_then(|t| t.as_str()) == Some("tool_use"))
            .map(|b| {
                let id = b.get("id").and_then(|v| v.as_str()).unwrap_or("");
                let name = b.get("name").and_then(|v| v.as_str()).unwrap_or("");
                let input = b
                    .get("input")
                    .and_then(|v| v.as_object())
                    .cloned()
                    .unwrap_or_default();
                ToolCall {
                    id: id.into(),
                    name: name.into(),
                    input,
                }
            })
            .collect();

        let usage = json.get("usage").map(|u| {
            let input_tokens = u.get("input_tokens").and_then(|v| v.as_u64()).unwrap_or(0);
            let cache_read = u.get("cache_read_input_tokens").and_then(|v| v.as_u64());
            let cache_write = u
                .get("cache_creation_input_tokens")
                .and_then(|v| v.as_u64());
            let total_input = input_tokens + cache_read.unwrap_or(0) + cache_write.unwrap_or(0);
            TokenUsage {
                input_tokens: total_input,
                output_tokens: u.get("output_tokens").and_then(|v| v.as_u64()).unwrap_or(0),
                cache_read_tokens: cache_read,
                cache_write_tokens: cache_write,
            }
        });

        AdapterResponse {
            reply,
            tool_calls,
            usage,
        }
    }

    /// Parse a single Anthropic SSE event into zero or more StreamChunks.
    pub fn parse_sse_event(
        event_type: &str,
        data: &str,
        tool_call_state: &mut HashMap<usize, (String, String, String)>,
        tokens: &mut AnthropicTokens,
    ) -> Vec<StreamChunk> {
        if data == "[DONE]" {
            return vec![];
        }

        let json: Value = match serde_json::from_str(data) {
            Ok(v) => v,
            Err(_) => return vec![],
        };

        match event_type {
            "message_start" => {
                if let Some(usage) = json.get("message").and_then(|m| m.get("usage")) {
                    tokens.input = usage
                        .get("input_tokens")
                        .and_then(|v| v.as_u64())
                        .unwrap_or(0);
                    tokens.cache_read = usage
                        .get("cache_read_input_tokens")
                        .and_then(|v| v.as_u64());
                    tokens.cache_write = usage
                        .get("cache_creation_input_tokens")
                        .and_then(|v| v.as_u64());
                }
                vec![]
            }
            "content_block_delta" => {
                let delta = json.get("delta");
                let delta_type = delta
                    .and_then(|d| d.get("type"))
                    .and_then(|t| t.as_str())
                    .unwrap_or("");

                match delta_type {
                    "text_delta" => {
                        let text = delta
                            .and_then(|d| d.get("text"))
                            .and_then(|t| t.as_str())
                            .unwrap_or("");
                        if !text.is_empty() {
                            vec![StreamChunk::TextDelta(text.into())]
                        } else {
                            vec![]
                        }
                    }
                    "thinking_delta" => {
                        let thinking = delta
                            .and_then(|d| d.get("thinking"))
                            .and_then(|t| t.as_str())
                            .unwrap_or("");
                        if !thinking.is_empty() {
                            vec![StreamChunk::ThinkingDelta(thinking.into())]
                        } else {
                            vec![]
                        }
                    }
                    "input_json_delta" => {
                        let idx = json.get("index").and_then(|v| v.as_u64()).unwrap_or(0) as usize;
                        let partial = delta
                            .and_then(|d| d.get("partial_json"))
                            .and_then(|t| t.as_str())
                            .unwrap_or("");
                        if let Some((_id, name, sb)) = tool_call_state.get_mut(&idx) {
                            sb.push_str(partial);
                            if !partial.is_empty() {
                                vec![StreamChunk::ToolArgDelta {
                                    tool_name: name.clone(),
                                    delta: partial.into(),
                                }]
                            } else {
                                vec![]
                            }
                        } else {
                            vec![]
                        }
                    }
                    "signature_delta" => {
                        let sig = delta
                            .and_then(|d| d.get("signature"))
                            .and_then(|t| t.as_str())
                            .unwrap_or("");
                        if !sig.is_empty() {
                            vec![StreamChunk::ThinkingSignature(sig.into())]
                        } else {
                            vec![]
                        }
                    }
                    _ => vec![],
                }
            }
            "content_block_start" => {
                let cb = json.get("content_block");
                let cb_type = cb
                    .and_then(|c| c.get("type"))
                    .and_then(|t| t.as_str())
                    .unwrap_or("");

                match cb_type {
                    "tool_use" => {
                        let id = cb
                            .and_then(|c| c.get("id"))
                            .and_then(|v| v.as_str())
                            .unwrap_or("");
                        let name = cb
                            .and_then(|c| c.get("name"))
                            .and_then(|v| v.as_str())
                            .unwrap_or("");
                        let idx = json.get("index").and_then(|v| v.as_u64()).unwrap_or(0) as usize;
                        tool_call_state.insert(idx, (id.into(), name.into(), String::new()));
                        vec![StreamChunk::ToolCallStart { name: name.into() }]
                    }
                    "thinking" => {
                        // Some providers send empty signature here; filter it out
                        let sig = cb.and_then(|c| c.get("signature")).and_then(|v| v.as_str());
                        match sig.filter(|s| !s.is_empty()) {
                            Some(s) => vec![StreamChunk::ThinkingSignature(s.into())],
                            None => vec![],
                        }
                    }
                    _ => vec![],
                }
            }
            "content_block_stop" => {
                let idx = json.get("index").and_then(|v| v.as_u64()).unwrap_or(0) as usize;
                if let Some((id, name, sb)) = tool_call_state.remove(&idx) {
                    let input: serde_json::Map<String, Value> =
                        serde_json::from_str(&sb).unwrap_or_default();
                    vec![StreamChunk::ToolCallComplete(ToolCall { id, name, input })]
                } else {
                    vec![]
                }
            }
            "message_delta" => {
                let stop_reason = json
                    .get("delta")
                    .and_then(|d| d.get("stop_reason"))
                    .and_then(|v| v.as_str())
                    .map(|s| s.to_string());

                let usage_obj = json.get("usage");
                let output_tokens = usage_obj
                    .and_then(|u| u.get("output_tokens"))
                    .and_then(|v| v.as_u64())
                    .unwrap_or(0);

                // Some providers report input_tokens in message_delta
                let delta_input = usage_obj
                    .and_then(|u| u.get("input_tokens"))
                    .and_then(|v| v.as_u64());
                let delta_cache_read = usage_obj
                    .and_then(|u| u.get("cache_read_input_tokens"))
                    .and_then(|v| v.as_u64());
                let delta_cache_write = usage_obj
                    .and_then(|u| u.get("cache_creation_input_tokens"))
                    .and_then(|v| v.as_u64());

                let input_tokens = delta_input.filter(|&x| x > 0).unwrap_or(tokens.input);
                let cache_read = delta_cache_read.or(tokens.cache_read);
                let cache_write = delta_cache_write.or(tokens.cache_write);
                let total_input = input_tokens + cache_read.unwrap_or(0) + cache_write.unwrap_or(0);

                vec![StreamChunk::Done {
                    stop_reason,
                    usage: Some(TokenUsage {
                        input_tokens: total_input,
                        output_tokens,
                        cache_read_tokens: cache_read,
                        cache_write_tokens: cache_write,
                    }),
                    context_window: None,
                }]
            }
            _ => vec![],
        }
    }
}

/// Token tracking state for Anthropic streaming.
#[derive(Debug, Clone, Default)]
pub struct AnthropicTokens {
    pub input: u64,
    pub cache_read: Option<u64>,
    pub cache_write: Option<u64>,
}

/// Merge consecutive same-role messages.
fn merge_consecutive(messages: &[&Message]) -> Vec<Message> {
    if messages.is_empty() {
        return vec![];
    }
    let mut result = vec![(**messages.first().unwrap()).clone()];
    for &msg in &messages[1..] {
        let last = result.last().unwrap();
        if last.role == msg.role {
            let merged = merge_messages(last, msg);
            *result.last_mut().unwrap() = merged;
        } else {
            result.push(msg.clone());
        }
    }
    result
}

fn merge_messages(a: &Message, b: &Message) -> Message {
    let merged = match (&a.content, &b.content) {
        (MessageContent::Text(at), MessageContent::Text(bt)) => {
            MessageContent::Text(format!("{at}\n{bt}"))
        }
        _ => a.content.clone(),
    };
    Message {
        role: a.role,
        content: merged,
        timestamp: a.timestamp.max(b.timestamp),
    }
}

/// Convert a single message to Anthropic API JSON format.
fn message_to_anthropic(msg: &Message) -> Value {
    let role = match msg.role {
        MessageRole::User => "user",
        MessageRole::Assistant => "assistant",
        MessageRole::System => "user",
    };

    match &msg.content {
        MessageContent::Text(text) => json!({"role": role, "content": text}),
        MessageContent::Blocks(blocks) => {
            let content: Vec<Value> = blocks
                .iter()
                .filter_map(content_block_to_anthropic)
                .collect();

            if content.is_empty() {
                json!({"role": role, "content": " "})
            } else {
                json!({"role": role, "content": content})
            }
        }
    }
}

/// Convert a ContentBlock to Anthropic API JSON. Returns None for thinking
/// blocks without signature (DeepSeek compatibility fix).
fn content_block_to_anthropic(block: &ContentBlock) -> Option<Value> {
    match block {
        ContentBlock::Text { text } => Some(json!({"type": "text", "text": text})),
        ContentBlock::Image { data, media_type } => Some(json!({
            "type": "image",
            "source": {
                "type": "base64",
                "media_type": media_type,
                "data": data,
            }
        })),
        ContentBlock::ToolUse { id, name, input } => Some(json!({
            "type": "tool_use",
            "id": id,
            "name": name,
            "input": Value::Object(input.clone()),
        })),
        ContentBlock::ToolResult {
            tool_use_id,
            content,
            is_error,
        } => {
            let mut obj = serde_json::Map::new();
            obj.insert("type".into(), json!("tool_result"));
            obj.insert("tool_use_id".into(), json!(tool_use_id));
            obj.insert("content".into(), json!(content));
            if let Some(true) = is_error {
                obj.insert("is_error".into(), json!(true));
            }
            Some(Value::Object(obj))
        }
        ContentBlock::Thinking {
            thinking,
            signature,
        } => {
            // Filter out thinking blocks without signature (DeepSeek compat)
            signature.as_ref().filter(|s| !s.is_empty()).map(|sig| {
                json!({
                    "type": "thinking",
                    "thinking": thinking,
                    "signature": sig,
                })
            })
        }
    }
}

#[async_trait]
impl ProviderAdapter for AnthropicAdapter {
    async fn send_message(&self, params: &SendMessageParams) -> Result<AdapterResponse, LlmError> {
        let body = self.build_request_body(params, false);

        let resp = self
            .http_client
            .post(&self.endpoint)
            .header("x-api-key", &self.api_key)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .json(&body)
            .send()
            .await
            .map_err(|e| LlmError::Network(e.to_string()))?;

        let status = resp.status();
        if !status.is_success() {
            let text = resp.text().await.unwrap_or_default();
            return Err(LlmError::Provider {
                message: format!("Anthropic API error {}: {}", status.as_u16(), text),
                status_code: Some(status.as_u16()),
            });
        }

        let json: Value = resp
            .json()
            .await
            .map_err(|e| LlmError::Stream(format!("Failed to parse response: {e}")))?;

        Ok(Self::parse_non_streaming_response(&json))
    }

    fn send_message_stream(&self, params: &SendMessageParams) -> ChunkStream {
        let body = self.build_request_body(params, true);
        let endpoint = self.endpoint.clone();
        let api_key = self.api_key.clone();
        let client = self.http_client.clone();

        Box::pin(async_stream::stream! {
            let resp = client
                .post(&endpoint)
                .header("x-api-key", &api_key)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .json(&body)
                .send()
                .await;

            let resp = match resp {
                Ok(r) => r,
                Err(e) => {
                    yield Err(LlmError::Network(e.to_string()));
                    return;
                }
            };

            if !resp.status().is_success() {
                let status = resp.status().as_u16();
                let text = resp.text().await.unwrap_or_default();
                yield Err(LlmError::Provider {
                    message: format!("Anthropic API error {status}: {text}"),
                    status_code: Some(status),
                });
                return;
            }

            let mut tool_call_state: HashMap<usize, (String, String, String)> = HashMap::new();
            let mut tokens = AnthropicTokens::default();
            let mut event_type: Option<String> = None;

            let mut stream = resp.bytes_stream();
            let mut buffer = String::new();

            while let Some(chunk_result) = stream.next().await {
                let chunk = match chunk_result {
                    Ok(c) => c,
                    Err(e) => {
                        yield Err(LlmError::Stream(e.to_string()));
                        return;
                    }
                };

                buffer.push_str(&String::from_utf8_lossy(&chunk));

                // Process complete SSE lines
                while let Some(pos) = buffer.find('\n') {
                    let line = buffer[..pos].trim().to_string();
                    buffer = buffer[pos + 1..].to_string();

                    if line.is_empty() {
                        continue;
                    }
                    if let Some(et) = line.strip_prefix("event:") {
                        event_type = Some(et.trim().to_string());
                    } else if let Some(data) = line.strip_prefix("data:") {
                        let data = data.trim();
                        let et = event_type.take().unwrap_or_default();
                        let chunks = Self::parse_sse_event(&et, data, &mut tool_call_state, &mut tokens);
                        for chunk in chunks {
                            yield Ok(chunk);
                        }
                    }
                }
            }
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_params() -> SendMessageParams {
        SendMessageParams {
            messages: vec![Message {
                role: MessageRole::User,
                content: MessageContent::Text("hello".into()),
                timestamp: 0,
            }],
            model: "claude-sonnet-4-6".into(),
            tools: None,
            max_tokens: Some(16384),
            thinking: None,
            system_stable: Some("You are helpful.".into()),
            system_dynamic: None,
            session_id: Some("test-session".into()),
            agent_id: Some("test-agent".into()),
        }
    }

    #[test]
    fn build_request_body_basic() {
        let adapter = AnthropicAdapter::new(
            "https://api.anthropic.com",
            "sk-test",
            reqwest::Client::new(),
        );
        let body = adapter.build_request_body(&test_params(), false);

        assert_eq!(body["model"], "claude-sonnet-4-6");
        assert_eq!(body["max_tokens"], 16384);
        assert_eq!(body["messages"][0]["role"], "user");
        assert_eq!(body["messages"][0]["content"], "hello");
        assert!(body.get("system").is_some());
        assert_eq!(body["system"][0]["type"], "text");
        assert_eq!(body["system"][0]["cache_control"]["type"], "ephemeral");
    }

    #[test]
    fn build_request_body_stream() {
        let adapter = AnthropicAdapter::new(
            "https://api.anthropic.com",
            "sk-test",
            reqwest::Client::new(),
        );
        let body = adapter.build_request_body(&test_params(), true);
        assert_eq!(body["stream"], true);
    }

    #[test]
    fn build_request_body_with_tools() {
        let mut params = test_params();
        params.tools = Some(vec![ToolDefinition {
            name: "Read".into(),
            description: "Read a file".into(),
            input_schema: json!({"type": "object"}),
        }]);
        let adapter = AnthropicAdapter::new(
            "https://api.anthropic.com",
            "sk-test",
            reqwest::Client::new(),
        );
        let body = adapter.build_request_body(&params, false);
        assert!(body.get("tools").is_some());
        assert_eq!(body["tools"][0]["name"], "Read");
        // Last tool should have cache_control
        assert_eq!(body["tools"][0]["cache_control"]["type"], "ephemeral");
    }

    #[test]
    fn build_request_body_with_thinking() {
        let mut params = test_params();
        params.thinking = Some(json!({"type": "enabled", "budget_tokens": 16000}));
        let adapter = AnthropicAdapter::new(
            "https://api.anthropic.com",
            "sk-test",
            reqwest::Client::new(),
        );
        let body = adapter.build_request_body(&params, false);
        assert_eq!(body["thinking"]["type"], "enabled");
        assert_eq!(body["thinking"]["budget_tokens"], 16000);
    }

    #[test]
    fn parse_sse_text_delta() {
        let mut state = HashMap::new();
        let mut tokens = AnthropicTokens::default();
        let data = r#"{"delta":{"type":"text_delta","text":"hello world"}}"#;
        let chunks =
            AnthropicAdapter::parse_sse_event("content_block_delta", data, &mut state, &mut tokens);
        assert_eq!(chunks.len(), 1);
        match &chunks[0] {
            StreamChunk::TextDelta(text) => assert_eq!(text, "hello world"),
            _ => panic!("expected TextDelta"),
        }
    }

    #[test]
    fn parse_sse_thinking_delta() {
        let mut state = HashMap::new();
        let mut tokens = AnthropicTokens::default();
        let data = r#"{"delta":{"type":"thinking_delta","thinking":"hmm"}}"#;
        let chunks =
            AnthropicAdapter::parse_sse_event("content_block_delta", data, &mut state, &mut tokens);
        assert_eq!(chunks.len(), 1);
        match &chunks[0] {
            StreamChunk::ThinkingDelta(t) => assert_eq!(t, "hmm"),
            _ => panic!("expected ThinkingDelta"),
        }
    }

    #[test]
    fn parse_sse_tool_call_start_and_stop() {
        let mut state = HashMap::new();
        let mut tokens = AnthropicTokens::default();

        // content_block_start
        let start_data =
            r#"{"index":0,"content_block":{"type":"tool_use","id":"tc1","name":"Read"}}"#;
        let chunks = AnthropicAdapter::parse_sse_event(
            "content_block_start",
            start_data,
            &mut state,
            &mut tokens,
        );
        assert_eq!(chunks.len(), 1);
        match &chunks[0] {
            StreamChunk::ToolCallStart { name } => assert_eq!(name, "Read"),
            _ => panic!("expected ToolCallStart"),
        }

        // input_json_delta
        let delta_data =
            r#"{"index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}"#;
        let chunks = AnthropicAdapter::parse_sse_event(
            "content_block_delta",
            delta_data,
            &mut state,
            &mut tokens,
        );
        assert_eq!(chunks.len(), 1);

        let delta_data2 =
            r#"{"index":0,"delta":{"type":"input_json_delta","partial_json":"\"test.txt\"}"}}"#;
        let chunks = AnthropicAdapter::parse_sse_event(
            "content_block_delta",
            delta_data2,
            &mut state,
            &mut tokens,
        );
        assert_eq!(chunks.len(), 1);

        // content_block_stop
        let stop_data = r#"{"index":0}"#;
        let chunks = AnthropicAdapter::parse_sse_event(
            "content_block_stop",
            stop_data,
            &mut state,
            &mut tokens,
        );
        assert_eq!(chunks.len(), 1);
        match &chunks[0] {
            StreamChunk::ToolCallComplete(tc) => {
                assert_eq!(tc.id, "tc1");
                assert_eq!(tc.name, "Read");
                assert_eq!(
                    tc.input.get("path").and_then(|v| v.as_str()),
                    Some("test.txt")
                );
            }
            _ => panic!("expected ToolCallComplete"),
        }
    }

    #[test]
    fn parse_sse_message_delta_done() {
        let mut state = HashMap::new();
        let tokens_init = AnthropicTokens {
            input: 100,
            cache_read: None,
            cache_write: None,
        };
        let mut tokens = tokens_init;

        let data = r#"{"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":50}}"#;
        let chunks =
            AnthropicAdapter::parse_sse_event("message_delta", data, &mut state, &mut tokens);
        assert_eq!(chunks.len(), 1);
        match &chunks[0] {
            StreamChunk::Done {
                stop_reason, usage, ..
            } => {
                assert_eq!(stop_reason.as_deref(), Some("end_turn"));
                assert_eq!(usage.as_ref().unwrap().input_tokens, 100);
                assert_eq!(usage.as_ref().unwrap().output_tokens, 50);
            }
            _ => panic!("expected Done"),
        }
    }

    #[test]
    fn parse_sse_thinking_block_with_empty_signature_filtered() {
        let mut state = HashMap::new();
        let mut tokens = AnthropicTokens::default();
        let data = r#"{"index":0,"content_block":{"type":"thinking","signature":""}}"#;
        let chunks =
            AnthropicAdapter::parse_sse_event("content_block_start", data, &mut state, &mut tokens);
        assert!(chunks.is_empty()); // Empty signature should be filtered
    }

    #[test]
    fn parse_sse_thinking_block_with_signature() {
        let mut state = HashMap::new();
        let mut tokens = AnthropicTokens::default();
        let data = r#"{"index":0,"content_block":{"type":"thinking","signature":"sig123"}}"#;
        let chunks =
            AnthropicAdapter::parse_sse_event("content_block_start", data, &mut state, &mut tokens);
        assert_eq!(chunks.len(), 1);
        match &chunks[0] {
            StreamChunk::ThinkingSignature(s) => assert_eq!(s, "sig123"),
            _ => panic!("expected ThinkingSignature"),
        }
    }

    #[test]
    fn content_block_thinking_without_signature_is_filtered() {
        let block = ContentBlock::Thinking {
            thinking: "hmm".into(),
            signature: None,
        };
        assert!(content_block_to_anthropic(&block).is_none());

        let block = ContentBlock::Thinking {
            thinking: "hmm".into(),
            signature: Some("".into()),
        };
        assert!(content_block_to_anthropic(&block).is_none());
    }

    #[test]
    fn content_block_thinking_with_signature_included() {
        let block = ContentBlock::Thinking {
            thinking: "hmm".into(),
            signature: Some("sig".into()),
        };
        let json = content_block_to_anthropic(&block).unwrap();
        assert_eq!(json["type"], "thinking");
        assert_eq!(json["signature"], "sig");
    }

    #[test]
    fn parse_non_streaming_response() {
        let json = json!({
            "content": [
                {"type": "text", "text": "Hello!"},
                {"type": "tool_use", "id": "tc1", "name": "Read", "input": {"path": "/tmp/test"}}
            ],
            "usage": {
                "input_tokens": 100,
                "output_tokens": 50,
                "cache_read_input_tokens": 20
            }
        });

        let resp = AnthropicAdapter::parse_non_streaming_response(&json);
        assert_eq!(resp.reply, "Hello!");
        assert_eq!(resp.tool_calls.len(), 1);
        assert_eq!(resp.tool_calls[0].id, "tc1");
        assert_eq!(resp.tool_calls[0].name, "Read");
        assert_eq!(resp.usage.as_ref().unwrap().input_tokens, 120); // 100 + 20 cache_read
        assert_eq!(resp.usage.as_ref().unwrap().output_tokens, 50);
    }
}
