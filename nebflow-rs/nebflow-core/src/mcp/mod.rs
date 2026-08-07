//! MCP (Model Context Protocol) module — client, manager, JSON-RPC, transports.
//!
//! Mirrors the Scala `nebflow.core.mcp` package.
//!
//! Module structure:
//! - `jsonrpc`: JSON-RPC 2.0 message types
//! - `transport`: StdioTransport + HttpTransport
//! - `client`: McpClient (initialize, listTools, callTool)
//! - `manager`: McpManager (lifecycle management of multiple servers)

pub mod client;
pub mod jsonrpc;
pub mod manager;
pub mod transport;

pub use client::{create_mcp_tool_wrapper, McpClient};
pub use jsonrpc::{JsonRpcError, JsonRpcNotification, JsonRpcRequest, JsonRpcResponse, McpTool};
pub use manager::McpManager;
pub use transport::{HttpTransport, McpTransport, StdioTransport};

/// MCP protocol version (2025-06-18 — latest stable, Streamable HTTP).
pub const MCP_PROTOCOL_VERSION: &str = "2025-06-18";
