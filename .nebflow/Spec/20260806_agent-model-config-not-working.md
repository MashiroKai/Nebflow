# Agent 模型配置修改后无法生效 — 根因分析与修复方案

## 现状分析

### 问题
用户在 Agent 详情页修改模型配置（preferred model / fallback chain），PUT 请求成功写入 agent.json，但：
1. **运行时不生效**：team/flow agent 对话时仍使用全局默认模型
2. **刷新后不显示**：重新打开详情页，model 配置显示为空

### 根因
存在两套互不兼容的 agent.json 解析数据结构：

| 数据结构 | 使用者 | 有 model 字段？ | 扫描范围 |
|---------|--------|----------------|---------|
| `AgentJson` | AgentLibrary | ✅ 有 | 仅全局 `agents/` |
| `AgentEntry` | EntityLoader | ❌ **缺失** | 全局 + team + flow 三层 |

PUT 写入用 `EntityLoader.findAgentDir`（三层搜索，正确找到文件并写入）。但运行时读取 team/flow agents 经由 `EntityLoader.loadTeamAgent` / `loadFlowAgent` → `AgentEntry`（无 model 字段），model 配置被直接丢弃。

### 实证
`~/.nebflow/teams/nebflow-project/agents/Frontend/agent.json` 已包含：
```json
"model": { "preferred": "kimi/k3-256k", "fallbacks": ["zhipu/GLM-5V-Turbo"], ... }
```
但运行时 Frontend agent 的 `AgentDef.model = None`。

## 涉及文件

### Bug 1: AgentEntry 缺少 model 字段
- `src/main/scala/nebflow/core/entity/EntityTypes.scala:12-57`
  - `AgentEntry` case class 加 `model: Option[AgentModelConfig] = None`
  - Decoder 加 `c.downField("model").as[Option[AgentModelConfig]]`
  - Encoder 加 `model` 输出
  - 需 `import nebflow.shared.AgentModelConfig`

### Bug 2: AgentDef 构造时不传 model（4 处）
1. `src/main/scala/nebflow/core/entity/EntityLoader.scala:251-260` — `findAgentByName`
2. `src/main/scala/nebflow/core/flow/FlowAgentActivator.scala:39-47` — `resolveAgentFromDisk`
3. `src/main/scala/nebflow/core/flow/FlowAgentActivator.scala:152-159` — team agent `activate`
4. `src/main/scala/nebflow/core/entity/FlowDagExecutor.scala:95-103` — flow node 执行

每处 `AgentDef(...)` 构造加一行 `model = entry.model`

### 可选改进
- `src/main/scala/nebflow/gateway/RestApiRoutes.scala:1146-1155` — GET /agents/:name 响应加 model 字段（前端可显示）

## 修改详情

### 1. EntityTypes.scala — AgentEntry 加 model 字段

```scala
// import nebflow.shared.AgentModelConfig  ← 加 import

case class AgentEntry(
  name: String,
  description: String,
  useWhen: String,
  tools: List[String] = List("*"),
  voice: Boolean = false,
  systemPrompt: String = "",
  category: String = "standalone",
  mcpServers: List[String] = Nil,
  model: Option[AgentModelConfig] = None  // ← 新增
)
```

Decoder 加：
```scala
model <- c.downField("model").as[Option[AgentModelConfig]].map(_.flatMap(identity))
// 或: c.downField("model").as[Option[AgentModelConfig]].toOption.flatten
```

Encoder 加：
```scala
.deepMerge(a.model.map(m => Json.obj("model" -> m.asJson)).getOrElse(Json.obj()))
```

### 2. 四处 AgentDef 构造 — 透传 model

每处加 `model = entry.model,`：

EntityLoader.scala:252-259:
```scala
AgentDef(
  name = entry.name,
  description = entry.description,
  tools = entry.tools,
  systemPrompt = entry.systemPrompt,
  category = entry.category,
  mcpServers = entry.mcpServers,
  model = entry.model  // ← 新增
)
```

同理修改 FlowAgentActivator.scala (2处) 和 FlowDagExecutor.scala (1处)。

### 3. RestApiRoutes.scala — GET /agents/:name 响应加 model (可选)

```scala
// line 1147-1155, Ok(...) 中加:
"model" -> defn.model.asJson,
```

## 行为描述

**当前行为**：用户修改 Frontend agent 的模型为 kimi/k3-256k，保存后 agent.json 正确写入了 model 字段。但重新打开页面配置显示为空，实际对话使用全局默认模型。

**预期行为**：保存后刷新页面正常显示已配置的模型，Frontend agent 对话使用 kimi/k3-256k，不可用时降级到 fallback 链。对全局 standalone agents、team agents、flow agents 均生效。

## 验收条件

1. `sbt compile` 编译通过
2. `sbt test` 所有测试通过（377+ tests）
3. 给 team agent（如 Frontend）设置 model 后，刷新页面配置正常显示
4. 向该 team 发送消息，Frontend agent 使用配置的 preferred model
5. 给 flow agent（如 scanner）设置 model 后，执行 flow 时使用配置的 model
6. 全局 standalone agent（如 Coder）的 model 配置不受影响（回归测试）
