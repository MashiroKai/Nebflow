# neblink / misc

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

杂项动作：头像代理、后台任务取消、文件传输、附件文件 IO。

**入口建议**：先读 [`FileTransferAction.scala`](FileTransferAction.scala)（本模块最大文件）。

## 文件（4）

| 文件 | 说明 |
|---|---|
| [`AvatarProxy.scala`](AvatarProxy.scala) | Server-side fetch of the local account avatar, backing the web local-first avatar cache (web/js/avatarCache.js |
| [`BgCancelAction.scala`](BgCancelAction.scala) | Handler for the "BgCancel" action — 对端 remote-exec 后台进程的按 jobId 精确 取消（后台任务生命周 P2 批 · 单任务取消的远端腿接收端）。 |
| [`FileTransferAction.scala`](FileTransferAction.scala) | Handler for the "FileTransfer" action — direct file IO that bypasses ToolRegistry. Used by both the relay tunn |
| [`NeblinkFiles.scala`](NeblinkFiles.scala) | 附件上传/下载腿的**文件 IO 单点**（4b 腿 A）。 为什么单独一处：整件 sha256（E1 声明值，§B.1）与分块读取（E2）必须与 |

## 依赖的兄弟模块

[`neblink/core/`](../core/module-doc.md)、[`neblink/relay/`](../relay/module-doc.md)、[`neblink/logto/`](../logto/module-doc.md)、[`core/tools/`](../../core/tools/module-doc.md)、[`dropbox/`](../../dropbox/module-doc.md)、[`shared/`](../../shared/module-doc.md)
