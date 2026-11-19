# neblink / portal

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

门户隧道：relay 帧桥、wire 编解码、上行背压、宿主面、一次性票据。

**入口建议**：先读 [`PortalTunnelBridge.scala`](PortalTunnelBridge.scala)（本模块最大文件）。

## 文件（7）

| 文件 | 说明 |
|---|---|
| [`ConsoleTicketStore.scala`](ConsoleTicketStore.scala) | One-time console ticket store for the portal mint leg (contract 2.10 / 2.11, phase 3a design section 3.4). |
| [`PortalBindingState.scala`](PortalBindingState.scala) | Host-side account-binding state machine — minimal phase 3a face (design section 3.5): `bound -> unbound`, driv |
| [`PortalHostFaces.scala`](PortalHostFaces.scala) | Late-bound, process-global faces the portal tunnel bridge consumes (phase 3a design sections 3.1/3.2). |
| [`PortalTunnelBridge.scala`](PortalTunnelBridge.scala) | Portal frame bridge for ONE relay WebSocket connection (phase 3a design sections 3.1-3.5): consumes the downli |
| [`PortalUplinkBackpressure.scala`](PortalUplinkBackpressure.scala) | 台账 #949（2026-10-26 作者微信报，Mac 引擎 stand.log 近 2000 行刷屏）—— portal 上行背压的**纯机制件**，供 [[PortalTunnelBridge]] 的 |
| [`PortalWireCodec.scala`](PortalWireCodec.scala) | Production codec for the portal wire contract (v1.3 additive re-freeze, 2026-10-27; v1.1 phase 3c / v1.2 errat |
| [`PortalWsMessageAssembler.scala`](PortalWsMessageAssembler.scala) | The JDK listener delivers partial messages. The relay browser pump sends whole messages, so only complete UTF- |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/`](../../core/module-doc.md)、[`neblink/relay/`](../relay/module-doc.md)、[`neblink/core/`](../core/module-doc.md)
