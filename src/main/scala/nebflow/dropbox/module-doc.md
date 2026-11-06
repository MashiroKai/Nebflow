# dropbox

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

跨设备消息与文件传输服务。

**入口建议**：先读 [`DropboxService.scala`](DropboxService.scala)（本模块最大文件）。

## 文件（7）

| 文件 | 说明 |
|---|---|
| [`ChunkTransport.scala`](ChunkTransport.scala) | 分块传输的**传输层缝** —— P2P 主腿 / relay 兜底腿 / 测试自环三者共用同一契约。 WHY 缝：分块循环（读盘 → 逐块摘要 → 发送 → 重试 → 换腿 → 续传）是纯编排逻辑， |
| [`ChunkedTransfer.scala`](ChunkedTransfer.scala) | 分块/流式传输引擎（应用层协议，传输无关）。 契约要点（设计件 §3.1–§3.4，逐条可判红）： |
| [`DropboxChunkHeaderParser.scala`](DropboxChunkHeaderParser.scala) | P2P 分块请求头解析 —— 从 `RestApiRoutes.parseDropboxChunkHeaders` 原样抽出 （**逐字等价**，仅为可判定性抽出：路由类构造依赖过重，钉无法在不实例化整条路由的 |
| [`DropboxChunkTransports.scala`](DropboxChunkTransports.scala) | P2P 分块传输腿（Dropbox 主腿）—— 直连对端网关的 HTTP 端点。 端点（`RestApiRoutes`）： |
| [`DropboxService.scala`](DropboxService.scala) | P0 wtmove — the resolved state of a transfer's temp path, produced by the single guard `DropboxService.guarded |
| [`DropboxUtil.scala`](DropboxUtil.scala) | Pure utility functions for Dropbox file handling. Extracted for testability. |
| [`TargetDirGuard.scala`](TargetDirGuard.scala) | 接收端 `targetDir` 判定链 —— **不变量 I-DIR 的唯一实现点**。 > 接收端不得被发送端指定到任意目录。发送端的 `targetDir` 是**请求**，不是**指令**； |

## 依赖的兄弟模块

[`shared/`](../shared/module-doc.md)、[`neblink/core/`](../neblink/core/module-doc.md)、[`neblink/misc/`](../neblink/misc/module-doc.md)、[`core/`](../core/module-doc.md)、[`core/sandbox/`](../core/sandbox/module-doc.md)
