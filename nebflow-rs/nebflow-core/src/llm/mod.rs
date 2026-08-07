//! LLM interface layer — provider adapters, fallback, health monitoring.
//!
//! Module structure:
//! - `adapter`: ProviderAdapter trait (moved from lib.rs)
//! - `anthropic`: Anthropic API adapter (SSE streaming, message format)
//! - `openai`: OpenAI-compatible API adapter (SSE streaming, message format)
//! - `handle`: LlmHandle implementation with fallback + health integration
//! - `registry`: Provider registration + candidate chain building
//! - `fallback`: Error classification + retry logic
//! - `health`: HealthMonitor for provider up/down tracking

pub mod adapter;
pub mod anthropic;
pub mod fallback;
pub mod handle;
pub mod health;
pub mod openai;
pub mod registry;

pub use adapter::ProviderAdapter;
pub use anthropic::AnthropicAdapter;
pub use fallback::classify_error;
pub use handle::LlmHandle;
pub use health::HealthMonitor;
pub use openai::OpenAiAdapter;
pub use registry::ProviderRegistry;
