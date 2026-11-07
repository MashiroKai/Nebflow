# bridge

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

外部消息桥（Telegram 等）与桥接上下文。

**入口建议**：先读 [`DeviceProvenance.scala`](DeviceProvenance.scala)（本模块最大文件）。

## 文件（5）

| 文件 | 说明 |
|---|---|
| [`AskAnswerReceipt.scala`](AskAnswerReceipt.scala) | AskUser 社交腿答案的**回执反向通道注册表**（answer-landing 实施批 2026-10-21）。 == 为什么需要它（缺口定位）== |
| [`BridgeManager.scala`](BridgeManager.scala) | Manages the lifecycle of all bridge plugins. - Registers as a WsHub listener to forward agent events to all pl |
| [`BridgePlugin.scala`](BridgePlugin.scala) | A remote-bridge plugin that connects Nebflow sessions to an external platform. Implementations: Telegram, Disc |
| [`DeviceProvenance.scala`](DeviceProvenance.scala) | 入站消息来源设备标记（device-provenance 批，2026-10-23 作者令）： 「我们现在能够标记消息是从微信还是飞书来的，让我们的消息也标记设备，比如手机端， |
| [`UnifiedMedia.scala`](UnifiedMedia.scala) | 社交通道入站媒体统一适配（weixin-inbound-media-unify batch, 2026-10-20）—— 桥接缝的**统一登记/构块纯面**。渠道（微信腿先飞，飞书腿接口就位）只递 |

## 依赖的兄弟模块

[`shared/`](../shared/module-doc.md)
