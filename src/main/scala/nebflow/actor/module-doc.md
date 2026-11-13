# actor

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

自研 actor 内核：ActorSystem / Behavior / AgentState / 命令与事件。

**入口建议**：先读 [`AgentState.scala`](AgentState.scala)（本模块最大文件）。

## 文件（12）

| 文件 | 说明 |
|---|---|
| [`ActorContext.scala`](ActorContext.scala) | Capabilities available to an actor during message processing. The actor uses this to: |
| [`ActorPath.scala`](ActorPath.scala) | Address of an actor in the Stand actor system. Format: nebflow://device/segment1/segment2/... |
| [`ActorRef.scala`](ActorRef.scala) | Public handle for sending messages to an actor. Two operations: |
| [`ActorSystem.scala`](ActorSystem.scala) | The actor system — registry + message routing + death watch. spawn creates local actors (Queue + Fiber + Behav |
| [`AgentCommand.scala`](AgentCommand.scala) | Restart level for supervisor-triggered agent restart. |
| [`AgentDef.scala`](AgentDef.scala) | Per-node output contract for flow agents (R8-P1). Built by FlowDagExecutor from the node's flow.json declarati |
| [`AgentEvent.scala`](AgentEvent.scala) | Interaction kind — what the user is being asked (P2). P0-1 / P-M1（2026-09-20，机制裁点 S3 = a）：新增第三种 kind `McpPermi |
| [`AgentState.scala`](AgentState.scala) | Display/routing kind of an agent (P1 统一注册表). Replaces the nodeSessionId string-prefix conventions (team-/dag-/ |
| [`Behavior.scala`](Behavior.scala) | System signals — delivered via a separate channel from regular messages. Actors handle them via [[Behavior.onS |
| [`InteractionHubCommand.scala`](InteractionHubCommand.scala) | Commands accepted by the InteractionHub actor. |
| [`LocalActorRef.scala`](LocalActorRef.scala) | Local actor implementation: backed by a Queue + Fiber. Messages are offered to the queue. A fiber processes th |
| [`RootAgentIdentity.scala`](RootAgentIdentity.scala) | 根 agent 身份名的全仓单点（Nebula→Root 重命名 Phase，行为保持）： 所有「根 agent 名」的判定 / 默认值 / 查找键统一引用 [[Name]]，值恒为旧名 |

## 依赖的兄弟模块

[`shared/`](../shared/module-doc.md)、[`neblink/social/`](../neblink/social/module-doc.md)

## Managed timers

`ActorTimers.resource` owns a local Pekko Typed timer actor and a Cats Effect Dispatcher. Gateway startup and `ActorSystem.resource` use this runtime for `ActorContext.scheduleOnce`. Timer delivery remains independent of turn cancellation and checks recipient liveness before sending. Resource release stops pending timers and cancels delivery effects. Agent mailboxes, supervision, and watch persistence still use the local runtime. Synchronous `ActorSystem.apply` constructors retain the sleeping-fiber compatibility path until their callers adopt managed resources. An injected timer runtime is closed by its Resource owner, not by `stopAll`.
