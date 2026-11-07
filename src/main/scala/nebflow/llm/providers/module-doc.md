# llm / providers

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

provider 适配器与工具入参解码。

**入口建议**：先读 [`AnthropicAdapter.scala`](AnthropicAdapter.scala)（本模块最大文件）。

## 文件（4）

| 文件 | 说明 |
|---|---|
| [`AnthropicAdapter.scala`](AnthropicAdapter.scala) | Full endpoint URL: use base as-is if it already points to /v1/messages, otherwise append. |
| [`ModelListFaces.scala`](ModelListFaces.scala) | The model-list endpoint of each protocol face, as declared by the adapter that owns that face — never derived  |
| [`OpenAiAdapter.scala`](OpenAiAdapter.scala) | Per-index accumulator for one streaming tool call (OpenAI adapter). The StringBuilder and the empty-id/name co |
| [`ToolInputJson.scala`](ToolInputJson.scala) | Shared parser for LLM tool-call arguments (accumulated streaming fragments or non-streaming `arguments` string |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`llm/`](../module-doc.md)、[`neblink/core/`](../../neblink/core/module-doc.md)、[`core/compact/`](../../core/compact/module-doc.md)
