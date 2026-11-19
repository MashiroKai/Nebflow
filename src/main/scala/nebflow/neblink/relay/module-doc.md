# neblink / relay

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

中继与发现：relay WS 隧道、peer 发现、presence 拨号、隧道诊断。

**入口建议**：先读 [`NeblinkRelayTunnel.scala`](NeblinkRelayTunnel.scala)（本模块最大文件）。

## 文件（4）

| 文件 | 说明 |
|---|---|
| [`NeblinkDiscovery.scala`](NeblinkDiscovery.scala) | Discovers Stand peers via the NebLink Server. Each cycle logs in (or heartbeats) to the NebLink Server to ge |
| [`NeblinkPresenceService.scala`](NeblinkPresenceService.scala) | Outcome of the most recent presence dial attempt against a peer (C3, 2026-09-11 P2P 直连修复批). |
| [`NeblinkRelayTunnel.scala`](NeblinkRelayTunnel.scala) | WebSocket relay tunnel to the NebLink Server. Device B connects this tunnel to the server so that cross-networ |
| [`RelayTunnelDiagnostics.scala`](RelayTunnelDiagnostics.scala) | Failure classification for the relay-ws upgrade (2026-09-10 隧道鉴权自愈批, report `20260910_134229_device-channel-at |

## 依赖的兄弟模块

[`neblink/core/`](../core/module-doc.md)、[`shared/`](../../shared/module-doc.md)、[`neblink/social/`](../social/module-doc.md)、[`core/`](../../core/module-doc.md)、[`neblink/identity/`](../identity/module-doc.md)、[`neblink/misc/`](../misc/module-doc.md)、[`neblink/portal/`](../portal/module-doc.md)、[`neblink/logto/`](../logto/module-doc.md)、[`core/hotupdate/`](../../core/hotupdate/module-doc.md)、[`core/tools/`](../../core/tools/module-doc.md)
