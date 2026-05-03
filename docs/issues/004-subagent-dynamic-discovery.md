# Issue 004: Subagent 配置静态化，不支持运行时动态发现

## 问题描述

当前 `AgentDef.subagents` 是一个静态的 `List[SubagentSlot]`，在 Agent 定义加载时固定：

```scala
// AgentDef.scala:8-19
case class AgentDef(
  name: String,
  description: String,
  modelRoute: String = "default",
  contextWindow: Int = Defaults.ContextWindow,
  maxTokens: Int = Defaults.MaxTokens,
  tools: List[String] = Nil,
  subagents: List[SubagentSlot] = Nil,  // 静态配置
  keepAlive: Boolean = false,
  systemPrompt: String = "",
  configPath: String = ""
)
```

`AgentActor.buildToolList` 根据这个静态列表生成 `delegate` 工具的 schema：

```scala
// AgentActor.scala:831-865
if agentDef.subagents.nonEmpty then
  synthetic += ToolDefinition(
    name = "delegate",
    description =
      s"Delegate a task to a sub-agent. Available agents: ${agentDef.subagents.map(s => s"${s.name} (${s.agent})").mkString(", ")}",
    ...
  )
```

这意味着：
1. Agent 只能委派给预定义的子 Agent，无法使用运行时加载的其他 Agent
2. 新增 Agent 后，需要手动修改父 Agent 的 `agent.json` 才能使用
3. 无法构建"通用协调 Agent"，根据任务动态选择最合适的子 Agent

## 影响范围

- 自定义 Agent 的灵活性受限
- AgentLibrary 中已加载的 Agent 无法被未显式配置的父 Agent 使用
- 用户需要理解 Agent 间的依赖关系才能正确配置

## 期望行为

1. **动态发现**：Agent 应该能发现 AgentLibrary 中所有可用的 Agent（或按标签/能力过滤）
2. **运行时选择**：`delegate` 工具应该允许 LLM 从完整 Agent 列表中选择
3. **向后兼容**：保留静态 `subagents` 配置作为白名单/显式授权机制

## 建议实现方案

### 方案 A：动态 Agent 列表 + 静态白名单（推荐）

修改 `buildToolList` 逻辑：

```scala
private def buildToolList(
  agentDef: AgentDef,
  depth: Int,
  hasParent: Boolean,
  availableAgents: List[AgentDef]  // 从 AgentLibrary 动态获取
): Option[List[ToolDefinition]] =
  val base = ...
  
  // 确定可委派的 Agent 列表
  val delegatableAgents = 
    if agentDef.subagents.nonEmpty then
      // 有静态配置：只暴露配置的子 Agent（白名单模式）
      agentDef.subagents.flatMap { slot =>
        availableAgents.find(_.name == slot.agent).map(def => slot.name -> def.description)
      }
    else
      // 无静态配置：暴露所有可用 Agent（除自己）
      availableAgents.filterNot(_.name == agentDef.name).map(d => d.name -> d.description)
  
  if delegatableAgents.nonEmpty then
    synthetic += ToolDefinition(
      name = "delegate",
      description =
        s"Delegate a task to a specialized sub-agent. Available agents: ${delegatableAgents.map(s => s"${s._1}: ${s._2}").mkString(", ")}",
      inputSchema = JsonObject.fromIterable(
        List(
          "type" -> Json.fromString("object"),
          "properties" -> Json.fromFields(
            List(
              "agent" -> Json.fromFields(
                List(
                  "type" -> Json.fromString("string"),
                  "enum" -> Json.fromValues(delegatableAgents.map(a => Json.fromString(a._1))),
                  "description" -> Json.fromString("Name of the sub-agent to delegate to")
                )
              ),
              "task" -> Json.fromFields(
                List(
                  "type" -> Json.fromString("string"),
                  "description" -> Json.fromString("The task description to delegate")
                )
              )
            )
          ),
          "required" -> Json.fromValues(List(Json.fromString("agent"), Json.fromString("task")))
        )
      )
    )
  end if
  ...
```

配置语义变化：

| `subagents` 配置 | 行为 |
|-----------------|------|
| `Nil`（默认） | 暴露所有 Agent（开放模式） |
| 非空列表 | 仅暴露列表中的 Agent（白名单模式） |

### 方案 B：Agent 标签系统

引入标签机制，按能力分类 Agent：

```scala
case class AgentDef(
  ...
  tags: List[String] = Nil  // e.g., ["code", "review", "test", "docs"]
)
```

`delegate` 工具增加 `tag` 参数，LLM 可以按标签筛选：

```json
{
  "agent": "code-reviewer",
  "tag": "review",
  "task": "Review the authentication module"
}
```

优点：更语义化，适合 Agent 数量多的场景  
缺点：增加配置复杂度

### 方案 C：完全动态（最小改动）

不改 `AgentDef`，仅在 `buildToolList` 中总是暴露所有 Agent：

```scala
val allAgents = resources.agentLibrary.loadAll()  // 需要改为同步或缓存
```

优点：最简单  
缺点：失去控制能力，所有 Agent 互相可见可能导致循环委派

## 需要修改的文件

1. **`src/main/scala/nebflow/agent/AgentActor.scala`**
   - 修改 `buildToolList` 签名，接收 `availableAgents: List[AgentDef]`
   - 修改 `delegate` 工具 schema 生成逻辑
   - 在 `pipeLlmCall` 中调用 `resources.agentLibrary.loadAll()` 并传入

2. **`src/main/scala/nebflow/agent/AgentDef.scala`**
   - （可选）增加 `tags` 字段

3. **`src/main/scala/nebflow/agent/AgentLibrary.scala`**
   - 增加缓存机制，避免每次 LLM 调用都重新加载所有 Agent
   - 或提供 `loadAllSync` / `getAll` 方法

4. **文档**
   - 更新 Agent 配置文档，说明 `subagents` 的新语义

## 注意事项

- **循环委派风险**：动态暴露所有 Agent 时，A → B → A 的循环可能发生。需要：
  - 深度限制（已有 `MaxDepth = 5`）
  - 可选：记录委派链，禁止循环
- **性能**：`agentLibrary.loadAll()` 每次 LLM 调用都执行会频繁读盘。建议：
  - 在 `SharedResources` 中缓存 Agent 列表
  - 文件系统 watcher 自动刷新缓存
- **安全性**：某些 Agent（如 `context-manage`）可能不应该被任意委派

## 优先级

**中** — 当前静态配置可用，但限制了 Agent 系统的扩展性。建议在 Issue 001 和 Issue 002 之后处理。
