# neblink / core

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

设备互联核心：客户端/服务门面/领域模型/wire 协议/出站 HTTP/更新收敛。

**入口建议**：先读 [`NeblinkClient.scala`](NeblinkClient.scala)（本模块最大文件）。

## 文件（10）

| 文件 | 说明 |
|---|---|
| [`EgressWhitelist.scala`](EgressWhitelist.scala) | 浏览面**出口白名单**（v2 §C.4 / v2.1 阶段 1 §C.1-3）。 `scope = "browse"` 的 FileTransfer 请求（direction=list 元数据列举 / directio |
| [`NeblinkClient.scala`](NeblinkClient.scala) | NebLink Server configuration. |
| [`NeblinkModel.scala`](NeblinkModel.scala) | Owner-only ACL bound to a credential WRITE PATH (A6, 2026-09-20 device-face hardening batch). |
| [`NeblinkService.scala`](NeblinkService.scala) | Start periodic sync. |
| [`NeblinkSingleFlight.scala`](NeblinkSingleFlight.scala) | 进程级**单飞登记表**（keyed single-flight）—— 「闸不被 hot-swap 重置」的落地件。 WHY 进程级而不是实例级（kaiauth 修法批 ③，2026-09-16 作者「治本」已批）： |
| [`NeblinkSwitchHandoff.scala`](NeblinkSwitchHandoff.scala) | Switch-account **single-window handoff** marker (one-window switch batch, 2026-09-16). Gateway memory only. |
| [`NeblinkUpdateConvergence.scala`](NeblinkUpdateConvergence.scala) | 统一更新收敛闭环 · 设备侧领账与采纳闸（M3 ③，DESIGN §7.2）。 职责单一：心跳响应带来 `update.target` 挂账（[[onHeartbeatTarget]]）→ **采纳闸 |
| [`NeblinkWiring.scala`](NeblinkWiring.scala) | Boot wiring seam for the A2A friend domain (2026-09-11 boot-snapshot fix). WHY a seam: the 2026-09-11 incident |
| [`OutboundHttpClients.scala`](OutboundHttpClients.scala) | Single, auditable source of truth for the gateway's **short-lived** outbound HTTP clients (D5 收敛，2026-09-13 cl |
| [`Protocol.scala`](Protocol.scala) | Wire-contract constants for the device-interop subsystem (L3 rebrand batch 3, 2026-08-17). D2 decision: the ne |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`neblink/relay/`](../relay/module-doc.md)、[`neblink/logto/`](../logto/module-doc.md)、[`neblink/identity/`](../identity/module-doc.md)、[`neblink/social/`](../social/module-doc.md)、[`core/`](../../core/module-doc.md)、[`neblink/misc/`](../misc/module-doc.md)、[`neblink/attachment/`](../attachment/module-doc.md)、[`core/project/`](../../core/project/module-doc.md)、[`core/hotupdate/`](../../core/hotupdate/module-doc.md)
