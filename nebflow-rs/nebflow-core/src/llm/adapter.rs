//! ProviderAdapter trait — low-level adapter for a specific provider API.

use crate::{AdapterResponse, ChunkStream, LlmError, SendMessageParams};
use async_trait::async_trait;

/// ProviderAdapter — low-level adapter for a specific provider API
/// (Anthropic, OpenAI, etc.). Corresponds to Scala ProviderAdapter trait.
#[async_trait]
pub trait ProviderAdapter: Send + Sync {
    async fn send_message(&self, params: &SendMessageParams) -> Result<AdapterResponse, LlmError>;

    fn send_message_stream(&self, params: &SendMessageParams) -> ChunkStream;
}
