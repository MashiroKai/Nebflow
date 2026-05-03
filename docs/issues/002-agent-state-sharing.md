# Issue 002: Agent 间缺乏状态共享机制

## 问题描述

当前多 Agent 架构中，父子 Agent 之间仅通过 `delegate` / `report` / `ask_parent` 三个合成工具进行通信。子 Agent 无法访问父 Agent 的任何内部状态（已读文件、工具执行历史、用户偏好等），导致：

1. **重复工作**：子 Agent 可能重复读取父 Agent 已经读过的文件
2. **上下文丢失**：父 Agent 做的分析结论，子 Agent 无法直接引用
3. **协作效率低**：Agent 间更像"任务外包"而非"团队协作"

## 当前行为

```scala
// AgentActor.scala:653-683 handleDelegate
case Some(slot) =>
  resources.agentLibrary.get(slot.agent).flatMap { defnOpt =>
    IO(ctx.self ! AgentCommand.SubagentDefLoaded(...))
  }.as(ToolExecResult(s"Delegated to $agentName: ${task.take(100)}"))
```

子 Agent 被 spawn 时只收到 `task` 字符串和 `initialMessages`（父 Agent 的完整对话历史），没有结构化的共享状态。

## 期望行为

子 Agent 应该能够访问：
- 父 Agent 最近读取的文件内容缓存
- 父 Agent 已执行的工具结果摘要
- 用户在该会话中的显式偏好/决策
- 项目级别的共享知识（如技术栈、架构决策）

## 建议实现方案

### 方案 A：SharedContext 传递（推荐）

在 `AgentActor` 中引入 `SharedContext`，随 `delegate` 传递给子 Agent：

```scala
case class SharedContext(
  fileCache: Map[String, String],        // 文件路径 -> 最近读取内容
  toolSummaries: List[ToolSummary],      // 工具执行摘要
  userPreferences: Map[String, String],  // 用户偏好
  projectNotes: String                   // 项目级笔记
)

// AgentState 中增加
case class AgentState(
  messages: List[Message],
  status: AgentStatus,
  sharedContext: SharedContext,          // 新增
  ...
)

// SubagentSlot 中增加传递规则
case class SubagentSlot(
  name: String,
  agent: String,
  shareFiles: Boolean = true,            // 是否共享文件缓存
  shareHistory: Boolean = true           // 是否共享最近 N 轮对话
)
```

子 Agent spawn 时，将父 Agent 的 `sharedContext` 注入到 system prompt 中：

```
You are working on a sub-task delegated from another agent. 
Here is the shared context from the parent agent:

[Files already read]
- src/auth.ts: JWT validation using jsonwebtoken
- src/routes.ts: 3 endpoints defined

[Recent tool results]
- Grep("auth"): Found 12 matches in 4 files
- Read("package.json"): Uses Express 4.18, no auth middleware

[User preferences]
- Prefer TypeScript over JavaScript
- Use functional components for React
```

### 方案 B：Agent Memory Store

引入一个独立的 `AgentMemory` Actor，所有 Agent 将关键发现写入内存，其他 Agent 按需读取：

```scala
object AgentMemory:
  case class Record(key: String, value: String, agentId: String, timestamp: Long)
  case class Query(pattern: String, replyTo: ActorRef[List[Record]])
```

优点：更灵活，支持跨会话记忆  
缺点：增加架构复杂度，需要设计查询语义

### 方案 C：消息增强（最小改动）

不改架构，仅在 `delegate` 工具的 `task` 参数中自动追加父 Agent 的上下文摘要：

```scala
private def buildDelegateTask(task: String, state: AgentState): String =
  val context = summarizeRecentTools(state.messages)
  s"""$task
     |
     |--- Context from parent agent ---
     |$context
     |""".stripMargin
```

优点：改动最小  
缺点：浪费 token，摘要质量不可控

## 需要修改的文件

1. **`src/main/scala/nebflow/agent/protocol.scala`**
   - 增加 `SharedContext` 类型
   - 修改 `AgentState` 和 `SubagentSlot`

2. **`src/main/scala/nebflow/agent/AgentActor.scala`**
   - 在 `handleDelegate` 中构建并传递 `SharedContext`
   - 在 `buildSystemPrompt` 中注入共享上下文
   - 在工具执行后更新 `SharedContext`（如 Read 工具缓存文件内容）

3. **`src/main/scala/nebflow/agent/AgentDef.scala`**
   - `SubagentSlot` 增加共享配置选项

4. **`src/main/scala/nebflow/agent/AgentLibrary.scala`**
   - 加载时验证 `SubagentSlot` 配置

## 优先级

**中** — 当前系统可用，但 Agent 协作效率受限。建议在 Issue 001 完成后实施。
