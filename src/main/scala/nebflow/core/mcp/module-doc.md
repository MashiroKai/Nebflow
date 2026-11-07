# core / mcp

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

MCP 管理、MCP 工具门与注册。

**入口建议**：先读 [`transports.scala`](transports.scala)（本模块最大文件）。

## 文件（5）

| 文件 | 说明 |
|---|---|
| [`AgentMcpLoader.scala`](AgentMcpLoader.scala) | Loads agent-scoped MCP servers from agent directory `tools/mcp/` (each `*.json` file holds one server config). |
| [`JsonRpc.scala`](JsonRpc.scala) | JSON-RPC 2.0 types |
| [`McpClient.scala`](McpClient.scala) | 定义: McpClient |
| [`McpManager.scala`](McpManager.scala) | Manages lifecycle of all MCP servers: config loading, connection, tool registration, shutdown. |
| [`transports.scala`](transports.scala) | MCP Transport interface |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/tools/`](../tools/module-doc.md)、[`neblink/core/`](../../neblink/core/module-doc.md)
