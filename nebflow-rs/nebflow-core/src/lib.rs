pub mod config;
pub mod dropbox;
pub mod entity;
pub mod flow;
pub mod llm;
pub mod mcp;
pub mod neblink;
pub mod protocol;
pub mod tools;
pub mod types;

use async_trait::async_trait;
use futures::Stream;
use std::pin::Pin;
use types::*;

/// Stream type alias — a boxed, pinned async stream of LLM chunks.
pub type ChunkStream = Pin<Box<dyn Stream<Item = Result<StreamChunk, LlmError>> + Send>>;

/// Tool trait — corresponds to Scala Tool trait.
#[async_trait]
pub trait Tool: Send + Sync {
    fn name(&self) -> &str;
    fn description(&self) -> &str;
    fn input_schema(&self) -> &serde_json::Map<String, serde_json::Value>;

    async fn call(
        &self,
        input: &serde_json::Map<String, serde_json::Value>,
        ctx: &ToolContext,
    ) -> Result<String, ToolError>;

    fn summarize(&self, _input: &serde_json::Map<String, serde_json::Value>) -> String {
        String::new()
    }

    fn summarize_result(
        &self,
        _input: &serde_json::Map<String, serde_json::Value>,
        _result: &str,
    ) -> String {
        String::new()
    }

    fn max_result_size(&self) -> usize {
        50_000
    }
}

/// LlmProvider trait — high-level LLM interface (handles provider selection,
/// retries, health monitoring, fallback).
#[async_trait]
pub trait LlmProvider: Send + Sync {
    async fn send(&self, req: &LlmRequest) -> Result<LlmResponse, LlmError>;

    fn send_stream(&self, req: &LlmRequest) -> ChunkStream;
}
