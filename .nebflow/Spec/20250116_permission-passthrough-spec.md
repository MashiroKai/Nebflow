# 权限透传实施规格书

> 可视化文档: `/tmp/permission-passthrough-spec.html`
> 设计文档: `/tmp/permission-passthrough-design.html`

## 概述

修复子 Agent（Delegate/DAG/Fork/Team）的权限请求无法到达用户的问题。核心改动：ForwardPermission 接收时覆盖 permJson 的 sessionId 为父 Agent 的 sessionId，并注入来源信息。

## Phase 1: Delegate 子 Agent 权限透传（核心修复）

### 改动文件

| 文件 | 行号 | 改动 |
|------|------|------|
| `agent/protocol.scala` | L96 | ForwardPermission 增加 `sourceAgent: String, sourceSession: String` |
| `agent/AgentCore.scala` | L565-579 | permJson 加 sourceAgent/sourceSession 字段；ForwardPermission 构造加参数 |
| `agent/AgentActor.scala` | L316-323 (idle) | ForwardPermission 处理：deepMerge 覆盖 sessionId + 注入 source 字段 |
| `agent/AgentActor.scala` | L1141-1149 (processing) | 同上 |

### 核心代码变更

**protocol.scala L96** — ForwardPermission 增加 2 个字段

**AgentCore.scala L565-579** — 在 permJson 构建处增加 sourceAgent/sourceSession；在 ForwardPermission 构造处传入

**AgentActor.scala L316-323 + L1141-1149** — 两处 ForwardPermission handler 中，将 `state.wsSend(permJson)` 改为：
```scala
val modifiedJson = permJson.deepMerge(Json.obj(
  "sessionId" -> state.sessionId.asJson,
  "sourceAgent" -> sourceAgent.asJson,
  "sourceSession" -> sourceSession.asJson
))
state.wsSend(modifiedJson)
```

## Phase 2: Flow DAG Agent 权限透传

### 改动文件

| 文件 | 行号 | 改动 |
|------|------|------|
| `core/flow/FlowDagRunner.scala` | L29-34 | 传 `Some(replyTo)` 作为 parentAgentRef 给 Executor |
| `core/entity/FlowDagExecutor.scala` | L39-46 | execute 方法增加 `parentAgentRef: Option[ActorRef[AgentCommand]] = None` 参数 |
| `core/entity/FlowDagExecutor.scala` | L105 | executeNode 调用 executeAgent 时传 parentAgentRef |
| `core/entity/FlowDagExecutor.scala` | L328-369 | executeAgent 增加参数 + spawn AgentActor 时 `parentRef = parentAgentRef` |

### 关键点
- `RunFlow.replyTo` 就是触发 Flow 的 Agent 的 ActorRef（DelegateTool L157, L167 传入 `ctx.agentActorRef`），直接用作 parentAgentRef
- 不需要修改 DelegateTool — callerRef 已经传给了 RunFlow

## Phase 3: Mail Fork Agent 修复

### 改动文件

| 文件 | 行号 | 改动 |
|------|------|------|
| `core/tools/MailTool.scala` | L206 | `parentRef = None` → `parentRef = ctx.agentActorRef` |

### 当前状态
- R3 已修复: callerSafetyMode 继承（L174-177）, forkWs 路由到 ctx.wsSend（L196）
- 剩余: parentRef=None → 改为 ctx.agentActorRef

## Phase 4: Team Agent — 无需改动

Phase 1 的 ForwardPermission sessionId 覆盖逻辑自动修复 Team Agent 权限路由。

## Phase 5: 前端来源标签

### 改动文件

| 文件 | 行号 | 改动 |
|------|------|------|
| `web/js/main.js` | L906, L928, L1191 | renderPermissionPrompt 调用增加 sourceAgent/sourceSession 参数 |
| `web/js/main.js` | L912, L931 | saveMsg 增加 sourceAgent/sourceSession 持久化 |
| `web/js/chat.js` | L1346 | 函数签名增加 sourceAgent, sourceSession 参数 |
| `web/js/chat.js` | L1386-1388 | 插入 .perm-source-badge 渲染代码 |
| `shared/protocol.scala` | L266-267 | AskPermission case class 增加 sourceAgent/sourceSession |
| `shared/protocol.scala` | L298-304 | Encoder 增加 sourceAgent/sourceSession |
| `shared/protocol.scala` | L356-361 | Decoder 增加 sourceAgent/sourceSession |
| CSS | 新增 | .perm-source-badge 样式 |
| i18n | 新增 | chat.permSource 键 |

## 验收条件

### 冒烟测试（硬性）
1. `sbt compile` 编译通过
2. `sbt run` 启动服务成功
3. 浏览器打开 → 发消息 → 收到回复

### E2E 场景
1. Delegate Coder → 权限卡片在 Nebula 窗口显示，来源标签"来自: Coder"
2. Flow DAG agent → 权限卡片在触发者窗口显示
3. Mail fork agent → 权限卡片在调用者窗口显示
4. Team agent → 权限卡片在 Nebula 窗口显示（Phase 1 自动修复）
5. 根 Agent 自己的权限 → 行为不变，无来源标签
6. 权限拒绝 → 子 Agent 收到 "Permission denied"
7. 超时 → 5 分钟自动拒绝
8. 历史持久化 → 切换 session 再切回，来源标签仍显示
