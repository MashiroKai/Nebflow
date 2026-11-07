# neblink / social

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

好友与跨设备邮件：好友服务、名册纯函数、设备邮件收件与 ack。

**入口建议**：先读 [`FriendService.scala`](FriendService.scala)（本模块最大文件）。

## 文件（4）

| 文件 | 说明 |
|---|---|
| [`DeviceMailAck.scala`](DeviceMailAck.scala) | 跨设备 Nebula 邮件的 **ack 关联与超时腿**（device-mail 批，2026-09-15；契约 v2 ④）。 契约（逐字，冻结）：`{"type":"ack","eventId":"message-< |
| [`DeviceMailInbox.scala`](DeviceMailInbox.scala) | 跨设备 Nebula 邮件的**收件腿**（device-mail 批，2026-09-15）：对端设备经服务端设备 事件流**（`friend_event` 信封内 `event.type == "agent_mail |
| [`FriendRoster.scala`](FriendRoster.scala) | 好友名册共用纯函数（好友消息改造批 ⑩，方案 `20260912_011320_好友消息改造批方案` §4.5「与 SendMessage 复用同一份好友来源与 |
| [`FriendService.scala`](FriendService.scala) | A2A 一期客户端好友服务（spec friends-messaging-arch.md v1.2 §11 客户端）。 职责（全部收敛在桌面客户端，服务端已在阶段 1 交付）： |

## 依赖的兄弟模块

[`neblink/core/`](../core/module-doc.md)、[`core/`](../../core/module-doc.md)、[`shared/`](../../shared/module-doc.md)、[`core/tools/`](../../core/tools/module-doc.md)、[`neblink/relay/`](../relay/module-doc.md)、[`actor/`](../../actor/module-doc.md)、[`neblink/attachment/`](../attachment/module-doc.md)
