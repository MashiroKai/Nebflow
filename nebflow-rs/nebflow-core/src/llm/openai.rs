//! OpenAI-compatible API adapter — message formatting, SSE parsing, request building.
//! Mirrors nebflow.llm.providers.OpenAiAdapter from Scala.

use std::collections::HashMap;

use crate::config;
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

pub struct OpenAiAdapter {
    endpoint: String,
    api_key: String,
    http_client: reqwest::Client,
}

impl OpenAiAdapter {
    pub fn new(base_url: &str, api_key: &str, http_client: reqwest::Client) -> Self {
        let base = base_url.trim_end_matches('/');
        let endpoint = if base.ends_with("/chat/completions") {
            base.to_string()
        } else {
            format!("{base}/chat/completions")
        };
        Self {
            endpoint,
            api_key: api_key.to_string(),
            http_client,
        }
    }

    /// Map Anthropic-style budget_tokens to OpenAI reasoning_effort.
    fn budget_to_effort(budget: u64) -> &'static str {
        if budget <= 2048 {
            "low"
        } else if budget <= 8192 {
            "medium"
        } else if budget <= 32768 {
            "high"
        } else {
            "xhigh"
        }
    }

    /// Build thinking-related request body based on the model.
    fn thinking_body(model: &str, thinking: &Option<Value>) -> Value {
        match thinking {
            Some(t) if t.get("type").and_then(|v| v.as_str()) == Some("enabled") => {
                let m = model.to_lowercase();
                if m.contains("glm") {
                    let budget = t
                        .get("budget_tokens")
                        .and_then(|v| v.as_u64())
                        .unwrap_or(32000);
                    json!({"thinking": {"type": "enabled", "budget_tokens": budget}})
                } else {
                    let budget = t
                        .get("budget_tokens")
                        .and_then(|v| v.as_u64())
                        .unwrap_or(32000);
                    json!({"reasoning_effort": Self::budget_to_effort(budget)})
                }
            }
            _ => json!({}),
        }
    }

    /// Build system message from stable + dynamic parts.
    pub fn build_system_message(params: &SendMessageParams) -> Option<Value> {
        let stable = params.system_stable.as_deref().filter(|s| !s.is_empty());
        let dynamic = params.system_dynamic.as_deref().filter(|s| !s.is_empty());
        let fallback = params
            .messages
            .iter()
            .find(|m| m.role == MessageRole::System)
            .map(|m| m.text_content())
            .filter(|s| !s.is_empty());

        let text = match stable.or(fallback.as_deref()) {
            Some(s) => {
                if let Some(d) = dynamic {
                    format!("{s}\n\n{d}")
                } else {
                    s.to_string()
                }
            }
            None => dynamic?.to_string(),
        };

        if text.is_empty() {
            None
        } else {
            Some(json!({"role": "system", "content": text}))
        }
    }

    /// Convert messages to OpenAI API format.
    ///
    /// Handles all ContentBlock types:
    /// - Assistant messages: Text → content, ToolUse → tool_calls, Thinking → skipped
    /// - User messages: Text → content, Image → vision format, ToolResult → role:"tool" messages
    /// - Empty assistant messages (no text, no tool_calls) are filtered out
    pub fn to_openai_messages(messages: &[Message]) -> Vec<Value> {
        messages
            .iter()
            .filter(|m| m.role != MessageRole::System)
            .flat_map(|msg| {
                let role = match msg.role {
                    MessageRole::User => "user",
                    MessageRole::Assistant => "assistant",
                    MessageRole::System => "user",
                };

                match &msg.content {
                    MessageContent::Text(text) => {
                        vec![json!({"role": role, "content": text})]
                    }

                    // Assistant with blocks: extract text + tool_calls
                    MessageContent::Blocks(blocks) if msg.role == MessageRole::Assistant => {
                        let text: String = blocks
                            .iter()
                            .filter_map(|b| match b {
                                ContentBlock::Text { text } => Some(text.clone()),
                                _ => None,
                            })
                            .collect::<Vec<_>>()
                            .join("");

                        let tool_calls: Vec<Value> = blocks
                            .iter()
                            .filter_map(|b| match b {
                                ContentBlock::ToolUse { id, name, input } => Some(json!({
                                    "id": id,
                                    "type": "function",
                                    "function": {
                                        "name": name,
                                        "arguments": serde_json::to_string(input)
                                            .unwrap_or_default()
                                    }
                                })),
                                _ => None,
                            })
                            .collect();

                        // Skip empty assistant messages (no text, no tool_calls)
                        if text.is_empty() && tool_calls.is_empty() {
                            return vec![];
                        }

                        let mut msg_obj = json!({"role": "assistant"});
                        if !text.is_empty() {
                            msg_obj["content"] = json!(text);
                        } else {
                            // OpenAI accepts null content when tool_calls present
                            msg_obj["content"] = Value::Null;
                        }
                        if !tool_calls.is_empty() {
                            msg_obj["tool_calls"] = json!(tool_calls);
                        }
                        vec![msg_obj]
                    }

                    // User with blocks: ToolResult → role:"tool", Text/Image → user
                    MessageContent::Blocks(blocks) => {
                        let mut result = vec![];

                        // Collect ToolResult blocks → independent "tool" messages
                        let tool_results: Vec<Value> = blocks
                            .iter()
                            .filter_map(|b| match b {
                                ContentBlock::ToolResult {
                                    tool_use_id,
                                    content,
                                    is_error,
                                } => {
                                    let content_str = if is_error.unwrap_or(false) {
                                        format!("Error: {content}")
                                    } else {
                                        content.clone()
                                    };
                                    Some(json!({
                                        "role": "tool",
                                        "tool_call_id": tool_use_id,
                                        "content": content_str
                                    }))
                                }
                                _ => None,
                            })
                            .collect();

                        // Collect Text blocks
                        let text: String = blocks
                            .iter()
                            .filter_map(|b| match b {
                                ContentBlock::Text { text } => Some(text.clone()),
                                _ => None,
                            })
                            .collect::<Vec<_>>()
                            .join("");

                        // Collect Image blocks → OpenAI vision format
                        let images: Vec<Value> = blocks
                            .iter()
                            .filter_map(|b| match b {
                                ContentBlock::Image { data, media_type } => Some(json!({
                                    "type": "image_url",
                                    "image_url": {
                                        "url": format!("data:{media_type};base64,{data}")
                                    }
                                })),
                                _ => None,
                            })
                            .collect();

                        // Build user message if there's text or images
                        if !images.is_empty() {
                            if !text.is_empty() {
                                // Text + images → multipart content
                                let mut content = vec![json!({"type": "text", "text": text})];
                                content.extend(images);
                                result.push(json!({"role": "user", "content": content}));
                            } else {
                                // Images only
                                result.push(json!({"role": "user", "content": images}));
                            }
                        } else if !text.is_empty() {
                            result.push(json!({"role": "user", "content": text}));
                        }

                        // Tool results as independent messages (must follow
                        // the assistant tool_calls message)
                        result.extend(tool_results);

                        result
                    }
                }
            })
            .collect()
    }

    /// Convert tool definitions to OpenAI tools format.
    fn to_openai_tools(tools: &[ToolDefinition]) -> Value {
        Value::Array(
            tools
                .iter()
                .map(|t| {
                    json!({
                        "type": "function",
                        "function": {
                            "name": t.name,
                            "description": t.description,
                            "parameters": t.input_schema,
                        }
                    })
                })
                .collect(),
        )
    }

    /// Build the full request body.
    pub fn build_request_body(&self, params: &SendMessageParams, stream: bool) -> Value {
        let system_msg = Self::build_system_message(params);
        let base_messages = Self::to_openai_messages(&params.messages);
        let mut all_messages: Vec<Value> = system_msg.into_iter().collect();
        all_messages.extend(base_messages);

        let mut body = json!({
            "model": params.model,
            "messages": all_messages,
            "max_tokens": params.max_tokens.unwrap_or(config::MAX_TOKENS_COMPACT),
        });

        if stream {
            body["stream"] = json!(true);
            body["stream_options"] = json!({"include_usage": true});
        }

        if let Some(tools) = &params.tools {
            if !tools.is_empty() {
                body["tools"] = Self::to_openai_tools(tools);
            }
        }

        // Thinking parameters are model-specific
        let thinking_body = Self::thinking_body(&params.model, &params.thinking);
        if let Some(obj) = thinking_body.as_object() {
            for (k, v) in obj {
                body[k.clone()] = v.clone();
            }
        }

        if let Some(sid) = &params.session_id {
            body["metadata"]["session_id"] = json!(sid);
        }
        if let Some(aid) = &params.agent_id {
            body["metadata"]["agent_id"] = json!(aid);
        }

        body
    }

    /// Parse a single OpenAI SSE data line into zero or more StreamChunks.
    pub fn parse_sse_data(
        data: &str,
        tool_call_state: &mut HashMap<usize, (String, String, String)>,
    ) -> Vec<StreamChunk> {
        if data == "[DONE]" {
            return vec![];
        }

        let json: Value = match serde_json::from_str(data) {
            Ok(v) => v,
            Err(_) => return vec![],
        };

        // Check for usage-only chunk (stream_options.include_usage sends a final chunk with empty choices)
        let usage_opt = json.get("usage").map(|u| {
            let prompt_tokens = u.get("prompt_tokens").and_then(|v| v.as_u64()).unwrap_or(0);
            let cached = u
                .get("prompt_tokens_details")
                .and_then(|d| d.get("cached_tokens"))
                .and_then(|v| v.as_u64());
            TokenUsage {
                input_tokens: prompt_tokens,
                output_tokens: u
                    .get("completion_tokens")
                    .and_then(|v| v.as_u64())
                    .unwrap_or(0),
                cache_read_tokens: cached,
                cache_write_tokens: None,
            }
        });

        let choices_empty = json
            .get("choices")
            .and_then(|c| c.as_array())
            .map(|a| a.is_empty())
            .unwrap_or(true);

        if usage_opt.is_some() && choices_empty {
            return vec![StreamChunk::Done {
                stop_reason: None,
                usage: usage_opt,
                context_window: None,
            }];
        }

        let choice = match json
            .get("choices")
            .and_then(|c| c.as_array())
            .and_then(|a| a.first())
        {
            Some(c) => c,
            None => return vec![],
        };

        let delta = choice.get("delta");
        let finish_reason = choice
            .get("finish_reason")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string());

        let mut chunks = vec![];

        if let Some(d) = delta {
            let mut had_content = false;

            // Text content
            if let Some(text) = d.get("content").and_then(|v| v.as_str()) {
                if !text.trim().is_empty() {
                    chunks.push(StreamChunk::TextDelta(text.into()));
                    had_content = true;
                }
            }

            // Reasoning/thinking content (GLM, DeepSeek, etc.)
            let think_text = d
                .get("reasoning_content")
                .and_then(|v| v.as_str())
                .or_else(|| d.get("thinking").and_then(|v| v.as_str()))
                .filter(|s| !s.trim().is_empty());
            if let Some(t) = think_text {
                chunks.push(StreamChunk::ThinkingDelta(t.into()));
                had_content = true;
            }

            // Tool calls
            if let Some(tcs) = d.get("tool_calls").and_then(|v| v.as_array()) {
                if !tcs.is_empty() {
                    had_content = true;
                }
                for tc in tcs {
                    let index = tc.get("index").and_then(|v| v.as_u64()).unwrap_or(0) as usize;
                    let id = tc.get("id").and_then(|v| v.as_str()).map(|s| s.to_string());
                    let name = tc
                        .get("function")
                        .and_then(|f| f.get("name"))
                        .and_then(|v| v.as_str())
                        .map(|s| s.to_string());
                    let args = tc
                        .get("function")
                        .and_then(|f| f.get("arguments"))
                        .and_then(|v| v.as_str())
                        .unwrap_or("");

                    match (id, name) {
                        (Some(tool_id), Some(tool_name)) => {
                            tool_call_state
                                .insert(index, (tool_id, tool_name.clone(), args.to_string()));
                            chunks.push(StreamChunk::ToolCallStart { name: tool_name });
                            if !args.is_empty() {
                                chunks.push(StreamChunk::ToolArgDelta {
                                    tool_name: tool_call_state[&index].1.clone(),
                                    delta: args.into(),
                                });
                            }
                        }
                        _ => {
                            if let Some((_, tname, sb)) = tool_call_state.get_mut(&index) {
                                sb.push_str(args);
                                if !args.is_empty() {
                                    chunks.push(StreamChunk::ToolArgDelta {
                                        tool_name: tname.clone(),
                                        delta: args.into(),
                                    });
                                }
                            }
                        }
                    }
                }
            }

            // If delta existed but produced no content/reasoning/tool_calls,
            // and there's no finish_reason, emit a Heartbeat to reset the
            // inactivity timeout timer.
            if !had_content && chunks.is_empty() && finish_reason.is_none() {
                chunks.push(StreamChunk::Heartbeat);
            }
        }

        // Handle finish_reason
        if let Some(fr) = &finish_reason {
            let is_tool_finish = fr.contains("tool_calls") || fr.contains("function_call");
            if is_tool_finish {
                // Flush accumulated tool calls
                let completed: Vec<(String, String, String)> =
                    tool_call_state.drain().map(|(_, v)| v).collect();
                for (id, name, sb) in completed {
                    let input: serde_json::Map<String, Value> =
                        serde_json::from_str(&sb).unwrap_or_default();
                    chunks.push(StreamChunk::ToolCallComplete(ToolCall { id, name, input }));
                }
            }
            chunks.push(StreamChunk::Done {
                stop_reason: Some(fr.clone()),
                usage: usage_opt,
                context_window: None,
            });
        }

        chunks
    }

    /// Extract tool calls from a non-streaming response.
    pub fn extract_tool_calls(response: &Value) -> Vec<ToolCall> {
        response
            .get("choices")
            .and_then(|c| c.as_array())
            .and_then(|a| a.first())
            .and_then(|c| c.get("message"))
            .and_then(|m| m.get("tool_calls"))
            .and_then(|t| t.as_array())
            .map(|tcs| {
                tcs.iter()
                    .map(|tc| {
                        let id = tc.get("id").and_then(|v| v.as_str()).unwrap_or("");
                        let name = tc
                            .get("function")
                            .and_then(|f| f.get("name"))
                            .and_then(|v| v.as_str())
                            .unwrap_or("");
                        let args = tc
                            .get("function")
                            .and_then(|f| f.get("arguments"))
                            .and_then(|v| v.as_str())
                            .unwrap_or("{}");
                        let input: serde_json::Map<String, Value> =
                            serde_json::from_str(args).unwrap_or_default();
                        ToolCall {
                            id: id.into(),
                            name: name.into(),
                            input,
                        }
                    })
                    .collect()
            })
            .unwrap_or_default()
    }

    /// Check if the response has reasoning content (thinking models).
    pub fn has_reasoning_content(response: &Value) -> bool {
        let message = response
            .get("choices")
            .and_then(|c| c.as_array())
            .and_then(|a| a.first())
            .and_then(|c| c.get("message"));
        message
            .and_then(|m| {
                m.get("reasoning_content")
                    .and_then(|v| v.as_str())
                    .or_else(|| m.get("thinking").and_then(|v| v.as_str()))
            })
            .map(|s| !s.trim().is_empty())
            .unwrap_or(false)
    }
}

#[async_trait]
impl ProviderAdapter for OpenAiAdapter {
    async fn send_message(&self, params: &SendMessageParams) -> Result<AdapterResponse, LlmError> {
        let body = self.build_request_body(params, false);

        let resp = self
            .http_client
            .post(&self.endpoint)
            .header("Authorization", format!("Bearer {}", self.api_key))
            .header("content-type", "application/json")
            .json(&body)
            .send()
            .await
            .map_err(|e| LlmError::Network(e.to_string()))?;

        let status = resp.status();
        if !status.is_success() {
            let text = resp.text().await.unwrap_or_default();
            return Err(LlmError::Provider {
                message: format!("OpenAI API error {}: {}", status.as_u16(), text),
                status_code: Some(status.as_u16()),
            });
        }

        let json: Value = resp
            .json()
            .await
            .map_err(|e| LlmError::Stream(format!("Failed to parse response: {e}")))?;

        let reply = json
            .get("choices")
            .and_then(|c| c.as_array())
            .and_then(|a| a.first())
            .and_then(|c| c.get("message"))
            .and_then(|m| m.get("content"))
            .and_then(|v| v.as_str())
            .unwrap_or("");

        let tool_calls = Self::extract_tool_calls(&json);
        let reasoning = Self::has_reasoning_content(&json);

        if reply.is_empty() && tool_calls.is_empty() && !reasoning {
            let finish_reason = json
                .get("choices")
                .and_then(|c| c.as_array())
                .and_then(|a| a.first())
                .and_then(|c| c.get("finish_reason"))
                .and_then(|v| v.as_str())
                .unwrap_or("");
            return Err(LlmError::Provider {
                message: format!("LLM returned empty response (finish_reason: {finish_reason})"),
                status_code: None,
            });
        }

        let usage = json.get("usage").map(|u| TokenUsage {
            input_tokens: u.get("prompt_tokens").and_then(|v| v.as_u64()).unwrap_or(0),
            output_tokens: u
                .get("completion_tokens")
                .and_then(|v| v.as_u64())
                .unwrap_or(0),
            cache_read_tokens: None,
            cache_write_tokens: None,
        });

        Ok(AdapterResponse {
            reply: reply.to_string(),
            tool_calls,
            usage,
        })
    }

    fn send_message_stream(&self, params: &SendMessageParams) -> ChunkStream {
        let body = self.build_request_body(params, true);
        let endpoint = self.endpoint.clone();
        let api_key = self.api_key.clone();
        let client = self.http_client.clone();

        Box::pin(async_stream::stream! {
            let resp = client
                .post(&endpoint)
                .header("Authorization", format!("Bearer {api_key}"))
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
                    message: format!("OpenAI API error {status}: {text}"),
                    status_code: Some(status),
                });
                return;
            }

            let mut tool_call_state: HashMap<usize, (String, String, String)> = HashMap::new();
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

                while let Some(pos) = buffer.find('\n') {
                    let line = buffer[..pos].trim().to_string();
                    buffer = buffer[pos + 1..].to_string();

                    if line.is_empty() {
                        continue;
                    }
                    if let Some(data) = line.strip_prefix("data:") {
                        let data = data.trim();
                        let chunks = Self::parse_sse_data(data, &mut tool_call_state);
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
            model: "gpt-4o".into(),
            tools: None,
            max_tokens: Some(4096),
            thinking: None,
            system_stable: Some("You are helpful.".into()),
            system_dynamic: None,
            session_id: Some("test-session".into()),
            agent_id: Some("test-agent".into()),
        }
    }

    #[test]
    fn build_request_body_basic() {
        let adapter = OpenAiAdapter::new(
            "https://api.openai.com/v1",
            "sk-test",
            reqwest::Client::new(),
        );
        let body = adapter.build_request_body(&test_params(), false);

        assert_eq!(body["model"], "gpt-4o");
        assert_eq!(body["max_tokens"], 4096);
        // system message should be first
        assert_eq!(body["messages"][0]["role"], "system");
        assert_eq!(body["messages"][1]["role"], "user");
    }

    #[test]
    fn build_request_body_stream() {
        let adapter = OpenAiAdapter::new(
            "https://api.openai.com/v1",
            "sk-test",
            reqwest::Client::new(),
        );
        let body = adapter.build_request_body(&test_params(), true);
        assert_eq!(body["stream"], true);
        assert_eq!(body["stream_options"]["include_usage"], true);
    }

    #[test]
    fn build_request_body_with_tools() {
        let mut params = test_params();
        params.tools = Some(vec![ToolDefinition {
            name: "Read".into(),
            description: "Read a file".into(),
            input_schema: json!({"type": "object"}),
        }]);
        let adapter = OpenAiAdapter::new(
            "https://api.openai.com/v1",
            "sk-test",
            reqwest::Client::new(),
        );
        let body = adapter.build_request_body(&params, false);
        assert_eq!(body["tools"][0]["type"], "function");
        assert_eq!(body["tools"][0]["function"]["name"], "Read");
    }

    #[test]
    fn thinking_body_glm() {
        let thinking = Some(json!({"type": "enabled", "budget_tokens": 8000}));
        let body = OpenAiAdapter::thinking_body("glm-4.6", &thinking);
        assert_eq!(body["thinking"]["type"], "enabled");
        assert_eq!(body["thinking"]["budget_tokens"], 8000);
    }

    #[test]
    fn thinking_body_glm_default_budget() {
        let thinking = Some(json!({"type": "enabled"}));
        let body = OpenAiAdapter::thinking_body("glm-5", &thinking);
        assert_eq!(body["thinking"]["type"], "enabled");
        assert_eq!(body["thinking"]["budget_tokens"], 32000);
    }

    #[test]
    fn thinking_body_openai_o_series() {
        let thinking = Some(json!({"type": "enabled", "budget_tokens": 8000}));
        let body = OpenAiAdapter::thinking_body("o3", &thinking);
        assert_eq!(body["reasoning_effort"], "medium");
    }

    #[test]
    fn thinking_body_disabled() {
        let thinking = None;
        let body = OpenAiAdapter::thinking_body("gpt-4o", &thinking);
        assert!(body.as_object().unwrap().is_empty());
    }

    #[test]
    fn budget_to_effort_mapping() {
        assert_eq!(OpenAiAdapter::budget_to_effort(1000), "low");
        assert_eq!(OpenAiAdapter::budget_to_effort(2048), "low");
        assert_eq!(OpenAiAdapter::budget_to_effort(2049), "medium");
        assert_eq!(OpenAiAdapter::budget_to_effort(8192), "medium");
        assert_eq!(OpenAiAdapter::budget_to_effort(8193), "high");
        assert_eq!(OpenAiAdapter::budget_to_effort(32768), "high");
        assert_eq!(OpenAiAdapter::budget_to_effort(40000), "xhigh");
    }

    #[test]
    fn parse_sse_text_delta() {
        let mut state = HashMap::new();
        let data = r#"{"choices":[{"delta":{"content":"hello"}}]}"#;
        let chunks = OpenAiAdapter::parse_sse_data(data, &mut state);
        assert_eq!(chunks.len(), 1);
        match &chunks[0] {
            StreamChunk::TextDelta(t) => assert_eq!(t, "hello"),
            _ => panic!("expected TextDelta"),
        }
    }

    #[test]
    fn parse_sse_thinking_delta() {
        let mut state = HashMap::new();
        let data = r#"{"choices":[{"delta":{"reasoning_content":"hmm"}}]}"#;
        let chunks = OpenAiAdapter::parse_sse_data(data, &mut state);
        assert_eq!(chunks.len(), 1);
        match &chunks[0] {
            StreamChunk::ThinkingDelta(t) => assert_eq!(t, "hmm"),
            _ => panic!("expected ThinkingDelta"),
        }
    }

    #[test]
    fn parse_sse_usage_only_chunk() {
        let mut state = HashMap::new();
        let data = r#"{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":50}}"#;
        let chunks = OpenAiAdapter::parse_sse_data(data, &mut state);
        assert_eq!(chunks.len(), 1);
        match &chunks[0] {
            StreamChunk::Done { usage, .. } => {
                assert_eq!(usage.as_ref().unwrap().input_tokens, 100);
                assert_eq!(usage.as_ref().unwrap().output_tokens, 50);
            }
            _ => panic!("expected Done"),
        }
    }

    #[test]
    fn parse_sse_finish_reason() {
        let mut state = HashMap::new();
        let data = r#"{"choices":[{"delta":{},"finish_reason":"stop"}]}"#;
        let chunks = OpenAiAdapter::parse_sse_data(data, &mut state);
        assert_eq!(chunks.len(), 1);
        match &chunks[0] {
            StreamChunk::Done { stop_reason, .. } => {
                assert_eq!(stop_reason.as_deref(), Some("stop"));
            }
            _ => panic!("expected Done"),
        }
    }

    #[test]
    fn parse_sse_heartbeat_on_empty_delta() {
        let mut state = HashMap::new();
        let data = r#"{"choices":[{"delta":{}}]}"#;
        let chunks = OpenAiAdapter::parse_sse_data(data, &mut state);
        assert_eq!(chunks.len(), 1);
        assert!(matches!(chunks[0], StreamChunk::Heartbeat));
    }

    #[test]
    fn parse_sse_done_marker() {
        let mut state = HashMap::new();
        let chunks = OpenAiAdapter::parse_sse_data("[DONE]", &mut state);
        assert!(chunks.is_empty());
    }

    // ── to_openai_messages tests ──

    #[test]
    fn to_openai_text_message() {
        let msgs = vec![Message {
            role: MessageRole::User,
            content: MessageContent::Text("hello".into()),
            timestamp: 0,
        }];
        let result = OpenAiAdapter::to_openai_messages(&msgs);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0]["role"], "user");
        assert_eq!(result[0]["content"], "hello");
    }

    #[test]
    fn to_openai_assistant_text_and_tool_use() {
        let msgs = vec![Message {
            role: MessageRole::Assistant,
            content: MessageContent::Blocks(vec![
                ContentBlock::Text {
                    text: "Reading file.".into(),
                },
                ContentBlock::ToolUse {
                    id: "tc1".into(),
                    name: "Read".into(),
                    input: serde_json::Map::new(),
                },
            ]),
            timestamp: 0,
        }];
        let result = OpenAiAdapter::to_openai_messages(&msgs);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0]["role"], "assistant");
        assert_eq!(result[0]["content"], "Reading file.");
        assert_eq!(result[0]["tool_calls"][0]["id"], "tc1");
        assert_eq!(result[0]["tool_calls"][0]["type"], "function");
        assert_eq!(result[0]["tool_calls"][0]["function"]["name"], "Read");
    }

    #[test]
    fn to_openai_assistant_tool_use_only() {
        let msgs = vec![Message {
            role: MessageRole::Assistant,
            content: MessageContent::Blocks(vec![ContentBlock::ToolUse {
                id: "tc1".into(),
                name: "Read".into(),
                input: serde_json::Map::new(),
            }]),
            timestamp: 0,
        }];
        let result = OpenAiAdapter::to_openai_messages(&msgs);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0]["role"], "assistant");
        assert!(result[0]["content"].is_null());
        assert_eq!(result[0]["tool_calls"][0]["id"], "tc1");
    }

    #[test]
    fn to_openai_assistant_thinking_only_filtered() {
        let msgs = vec![Message {
            role: MessageRole::Assistant,
            content: MessageContent::Blocks(vec![ContentBlock::Thinking {
                thinking: "hmm".into(),
                signature: None,
            }]),
            timestamp: 0,
        }];
        let result = OpenAiAdapter::to_openai_messages(&msgs);
        assert!(
            result.is_empty(),
            "thinking-only assistant message should be filtered"
        );
    }

    #[test]
    fn to_openai_assistant_empty_filtered() {
        let msgs = vec![Message {
            role: MessageRole::Assistant,
            content: MessageContent::Blocks(vec![]),
            timestamp: 0,
        }];
        let result = OpenAiAdapter::to_openai_messages(&msgs);
        assert!(
            result.is_empty(),
            "empty assistant message should be filtered"
        );
    }

    #[test]
    fn to_openai_user_tool_result() {
        let msgs = vec![Message {
            role: MessageRole::User,
            content: MessageContent::Blocks(vec![ContentBlock::ToolResult {
                tool_use_id: "tc1".into(),
                content: "file content here".into(),
                is_error: None,
            }]),
            timestamp: 0,
        }];
        let result = OpenAiAdapter::to_openai_messages(&msgs);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0]["role"], "tool");
        assert_eq!(result[0]["tool_call_id"], "tc1");
        assert_eq!(result[0]["content"], "file content here");
    }

    #[test]
    fn to_openai_user_tool_result_error() {
        let msgs = vec![Message {
            role: MessageRole::User,
            content: MessageContent::Blocks(vec![ContentBlock::ToolResult {
                tool_use_id: "tc1".into(),
                content: "File not found".into(),
                is_error: Some(true),
            }]),
            timestamp: 0,
        }];
        let result = OpenAiAdapter::to_openai_messages(&msgs);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0]["role"], "tool");
        assert!(result[0]["content"].as_str().unwrap().contains("Error:"));
        assert!(result[0]["content"]
            .as_str()
            .unwrap()
            .contains("File not found"));
    }

    #[test]
    fn to_openai_user_text_and_tool_result() {
        let msgs = vec![Message {
            role: MessageRole::User,
            content: MessageContent::Blocks(vec![
                ContentBlock::Text {
                    text: "Here's the result.".into(),
                },
                ContentBlock::ToolResult {
                    tool_use_id: "tc1".into(),
                    content: "file content".into(),
                    is_error: None,
                },
            ]),
            timestamp: 0,
        }];
        let result = OpenAiAdapter::to_openai_messages(&msgs);
        // Should produce: user text message + tool result message
        assert_eq!(result.len(), 2);
        assert_eq!(result[0]["role"], "user");
        assert_eq!(result[0]["content"], "Here's the result.");
        assert_eq!(result[1]["role"], "tool");
        assert_eq!(result[1]["tool_call_id"], "tc1");
    }

    #[test]
    fn to_openai_user_image() {
        let msgs = vec![Message {
            role: MessageRole::User,
            content: MessageContent::Blocks(vec![ContentBlock::Image {
                data: "base64data".into(),
                media_type: "image/png".into(),
            }]),
            timestamp: 0,
        }];
        let result = OpenAiAdapter::to_openai_messages(&msgs);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0]["role"], "user");
        assert_eq!(result[0]["content"][0]["type"], "image_url");
        assert!(result[0]["content"][0]["image_url"]["url"]
            .as_str()
            .unwrap()
            .contains("data:image/png;base64,base64data"));
    }

    #[test]
    fn to_openai_assistant_tool_use_then_user_tool_result_order() {
        // Verify message order: assistant(tool_calls) → tool(result)
        let msgs = vec![
            Message {
                role: MessageRole::Assistant,
                content: MessageContent::Blocks(vec![ContentBlock::ToolUse {
                    id: "tc1".into(),
                    name: "Read".into(),
                    input: serde_json::Map::new(),
                }]),
                timestamp: 0,
            },
            Message {
                role: MessageRole::User,
                content: MessageContent::Blocks(vec![ContentBlock::ToolResult {
                    tool_use_id: "tc1".into(),
                    content: "file content".into(),
                    is_error: None,
                }]),
                timestamp: 1,
            },
        ];
        let result = OpenAiAdapter::to_openai_messages(&msgs);
        assert_eq!(result.len(), 2);
        // First: assistant with tool_calls
        assert_eq!(result[0]["role"], "assistant");
        assert!(result[0]["tool_calls"].is_array());
        // Second: tool result
        assert_eq!(result[1]["role"], "tool");
        assert_eq!(result[1]["tool_call_id"], "tc1");
    }

    #[test]
    fn to_openai_multiple_tool_results() {
        let msgs = vec![Message {
            role: MessageRole::User,
            content: MessageContent::Blocks(vec![
                ContentBlock::ToolResult {
                    tool_use_id: "tc1".into(),
                    content: "result 1".into(),
                    is_error: None,
                },
                ContentBlock::ToolResult {
                    tool_use_id: "tc2".into(),
                    content: "result 2".into(),
                    is_error: None,
                },
            ]),
            timestamp: 0,
        }];
        let result = OpenAiAdapter::to_openai_messages(&msgs);
        assert_eq!(result.len(), 2);
        assert_eq!(result[0]["role"], "tool");
        assert_eq!(result[0]["tool_call_id"], "tc1");
        assert_eq!(result[1]["role"], "tool");
        assert_eq!(result[1]["tool_call_id"], "tc2");
    }

    #[test]
    fn to_openai_system_message_filtered() {
        let msgs = vec![
            Message {
                role: MessageRole::System,
                content: MessageContent::Text("system prompt".into()),
                timestamp: 0,
            },
            Message {
                role: MessageRole::User,
                content: MessageContent::Text("hello".into()),
                timestamp: 1,
            },
        ];
        let result = OpenAiAdapter::to_openai_messages(&msgs);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0]["role"], "user");
    }

    #[test]
    fn to_openai_assistant_thinking_and_text() {
        // Thinking blocks should be skipped, text should be kept
        let msgs = vec![Message {
            role: MessageRole::Assistant,
            content: MessageContent::Blocks(vec![
                ContentBlock::Thinking {
                    thinking: "internal reasoning".into(),
                    signature: Some("sig".into()),
                },
                ContentBlock::Text {
                    text: "Final answer.".into(),
                },
            ]),
            timestamp: 0,
        }];
        let result = OpenAiAdapter::to_openai_messages(&msgs);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0]["role"], "assistant");
        assert_eq!(result[0]["content"], "Final answer.");
        // No tool_calls
        assert!(result[0].get("tool_calls").is_none());
    }

    #[test]
    fn to_openai_tool_use_arguments_serialized() {
        let mut input = serde_json::Map::new();
        input.insert("path".into(), json!("/tmp/test.rs"));
        let msgs = vec![Message {
            role: MessageRole::Assistant,
            content: MessageContent::Blocks(vec![ContentBlock::ToolUse {
                id: "tc1".into(),
                name: "Read".into(),
                input: input.clone(),
            }]),
            timestamp: 0,
        }];
        let result = OpenAiAdapter::to_openai_messages(&msgs);
        assert_eq!(result.len(), 1);
        let args = result[0]["tool_calls"][0]["function"]["arguments"]
            .as_str()
            .unwrap();
        let parsed: serde_json::Value = serde_json::from_str(args).unwrap();
        assert_eq!(parsed["path"], "/tmp/test.rs");
    }
}
