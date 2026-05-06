# 023 — 解耦 core/tools 对 agent 层的反向依赖

---

## [创建] 元信息

| 字段 | 内容 |
|------|------|
| 负责人 | @MashiroKai |
| 状态 | Open |
| 标签 | tech-debt, architecture |
| 优先级 | P3-低 |
| 创建日期 | 2026-05-06 |
| 目标日期 | 未确定 |
| 预估工时 | 未评估 |

---

## [创建] 一句话描述

将 `core/tools/` 中 6 个文件对 `nebflow.agent` 包的直接依赖替换为 trait 抽象，恢复 `shared ← core ← agent` 单向依赖规则。

---

## 背景

项目的依赖方向约定为 `shared ← core ← agent`，但 `core/tools/` 中有 6 个文件直接导入了 `nebflow.agent.AgentCommand` 等 agent 层类型，形成反向依赖。这是历史遗留的结构性债务。

---

## [创建] 动机

**现状：** 以下文件从 `core/tools/` 导入 `nebflow.agent` 包类型：

| 文件 | 导入的 agent 类型 |
|------|-------------------|
| `types.scala` | `AgentCommand`, `AgentDef`, `AgentLibrary` |
| `AskUserQuestionTool.scala` | `AgentCommand` |
| `DelegateTool.scala` | `AgentCommand` |
| `AskParentTool.scala` | `AgentCommand`, `AgentCommand.ParentAnswer` |
| `ReportTool.scala` | `AgentCommand` |
| `ContextManageTool.scala` | `AgentCommand` |

`ToolContext` 中有 4 个字段直接引用 agent 层类型：

| 字段 | 当前类型 | 使用方 |
|------|----------|--------|
| `agentActorRef` | `Option[ActorRef[AgentCommand]]` | AskUserQuestionTool, DelegateTool, ReportTool, ContextManageTool |
| `parentRef` | `Option[ActorRef[AgentCommand]]` | AskParentTool, ReportTool |
| `agentDef` | `Option[AgentDef]` | DelegateTool |
| `agentLibrary` | `Option[AgentLibrary]` | DelegateTool |

实际使用的 `AgentCommand` 子类型仅 6 个：`AskUser`, `SubagentDefLoaded`, `SubagentQuestion`, `ParentAnswer`, `DelegateResult`, `TriggerCompaction`。

---

## [创建] 目标

`core/tools/` 零导入 `nebflow.agent` 包。agent 层的具体类型仅在 `agent/AgentCore.scala` 中出现（作为 trait 实现注入 `ToolContext`）。

---

## [创建] 范围

### In Scope

- [ ] 在 `core/tools/types.scala` 中定义 3 个 trait：`AgentMessenger`, `AgentQuerier`, `SubagentResolver`
- [ ] `ToolContext` 中 4 个 agent 类型字段替换为 trait
- [ ] 6 个 tool 文件移除 `nebflow.agent` 导入，改用 trait 接口
- [ ] `AgentCore.scala` 构造 `ToolContext` 时注入 trait 实现

### Out of Scope

- `McpManager` 对 `nebflow.llm` 的反向依赖（独立 issue）
- `LlmInterface` 对 `nebflow.core.NebflowLogger` 的反向依赖（独立 issue）
- agent 层对 gateway 层的依赖（独立 issue）

---

## 目标架构

### 新增 3 个 trait（定义在 `core/tools/types.scala`）

```scala
/** Fire-and-forget commands to agent actor. */
trait AgentMessenger:
  def triggerCompaction(mode: String): IO[Unit]
  def reportResult(agentId: String, result: Either[String, String]): IO[Unit]
  def loadSubagentDef(call: ToolCall, name: String, task: String,
                      defn: Option[AgentDef], depth: Int): IO[Unit]

/** Request-response queries to agent actor. */
trait AgentQuerier:
  def askUser(requestId: String, items: List[AskItem]): IO[List[String]]
  def askParent(question: String): IO[String]

/** Subagent definition lookup. */
trait SubagentResolver:
  def listSubagents: List[String]
  def resolve(name: String): IO[Option[AgentDef]]
```

> 注：`AgentDef`、`AskItem`、`ToolCall` 已在 `shared/` 或 `core/` 中定义，不引入新的 agent 依赖。
> 若 `AgentDef` 当前在 `agent/` 包中，需先将其移至 `shared/` 或 `core/`。

### ToolContext 字段替换

| 字段 | 当前类型 | 替换为 |
|------|----------|--------|
| `agentActorRef` | `Option[ActorRef[AgentCommand]]` | `Option[AgentMessenger]` |
| `parentRef` | `Option[ActorRef[AgentCommand]]` | `Option[AgentQuerier]` |
| `agentDef` | `Option[AgentDef]` | 移除（由 `SubagentResolver` 承载） |
| `agentLibrary` | `Option[AgentLibrary]` | 移除（由 `SubagentResolver` 承载） |

新增：

| 字段 | 类型 |
|------|------|
| `messenger` | `Option[AgentMessenger]` |
| `querier` | `Option[AgentQuerier]` |
| `subagentResolver` | `Option[SubagentResolver]` |

### AgentCore.scala 注入点

```scala
// AgentCore.scala — 唯一知道具体 agent 类型的位置
val toolCtx = ToolContext(
  // ... 其他字段不变 ...
  messenger = Some(new AgentMessenger {
    def triggerCompaction(mode: String) = IO(ctx.self ! AgentCommand.TriggerCompaction(mode))
    def reportResult(agentId: String, result: ...) = IO(parentRef ! AgentCommand.DelegateResult(agentId, result))
    def loadSubagentDef(call, name, task, defn, depth) = IO(ctx.self ! AgentCommand.SubagentDefLoaded(call, name, task, defn, depth))
  }),
  querier = Some(new AgentQuerier {
    def askUser(requestId, items) = /* 现有 AskPattern 逻辑 */
    def askParent(question) = /* 现有 AskPattern 逻辑 */
  }),
  subagentResolver = Some(new SubagentResolver {
    def listSubagents = agentDef.subagents.map(_.name)
    def resolve(name) = resources.agentLibrary.get(name)
  })
)
```

---

## 迁移步骤

### Phase 1: 前置准备（Low）

1. 确认 `AgentDef`、`SubagentSlot` 等数据类型的位置 — 若在 `agent/` 包中，需先移至 `shared/` 或 `core/`
2. 确认 `AskItem` 已在 `core/` 中定义（当前在 `core/` 的 `handlers.scala`）

### Phase 2: 定义 trait（Low）

1. 在 `core/tools/types.scala` 中添加 `AgentMessenger`、`AgentQuerier`、`SubagentResolver`
2. 更新 `ToolContext` case class，添加新 trait 字段，标记旧字段为 `@deprecated`

### Phase 3: 逐文件迁移（Medium）

按工具逐个迁移，每迁移一个工具后运行编译+测试：

1. `ContextManageTool` — 最简单，仅 `triggerCompaction`
2. `ReportTool` — `reportResult` tell
3. `AskUserQuestionTool` — `askUser` ask pattern
4. `AskParentTool` — `askParent` ask pattern
5. `DelegateTool` — 最复杂，涉及 `messenger` + `subagentResolver`

### Phase 4: 清理（Low）

1. 移除 `ToolContext` 中 4 个旧字段
2. 移除 `types.scala` 中对 `nebflow.agent` 的导入
3. 全量 grep 确认 `core/tools/` 下无 `nebflow.agent` 导入

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| AgentDef 不在 shared/core 中 | Low | Phase 1 先迁移数据类型 |
| trait 方法签名不匹配 agent 实际行为 | Medium | Phase 3 逐文件迁移，每步编译验证 |
| 引入额外间接层降低可读性 | Low | trait 方法名语义明确，匿名实现简洁 |

---

## 验证

```bash
# 编译 + 测试
sbt compile && sbt test

# 确认 core/tools/ 无 agent 导入
grep -rn "import nebflow.agent" src/main/scala/nebflow/core/tools/
# Expected: no output
```

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `core/tools/types.scala` | **Modify** | 添加 3 个 trait，更新 ToolContext |
| `core/tools/ContextManageTool.scala` | **Modify** | 改用 AgentMessenger |
| `core/tools/ReportTool.scala` | **Modify** | 改用 AgentMessenger |
| `core/tools/AskUserQuestionTool.scala` | **Modify** | 改用 AgentQuerier |
| `core/tools/AskParentTool.scala` | **Modify** | 改用 AgentQuerier |
| `core/tools/DelegateTool.scala` | **Modify** | 改用 AgentMessenger + SubagentResolver |
| `agent/AgentCore.scala` | **Modify** | 注入 trait 实现 |

---

## 关联

- Related to `022-mcp-production-hardening.md` — 同期审查发现的技术债务
- Introduced by 初始架构 — tool 层直接持有 actor ref 的历史设计
