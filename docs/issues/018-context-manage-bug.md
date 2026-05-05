# Bug：ContextManage 工具调用导致混合工具结果丢失 + 子 agent 崩溃

> **[必填]** 标记为必须填写，其余按需填写。
> 术语定义见 [`templates/_glossary.md`](./templates/_glossary.md)。

---

## [创建] 元信息

| 字段 | 内容 |
|------|------|
| 负责人 | @MashiroKai |
| 状态 | 已确认 |
| 标签 | backend, bug, agent |
| 优先级 | P1-高 |
| 创建日期 | 2025-06-14 |
| 目标日期 | 未确定 |
| 预估工时 | 4h |
| 实际工时 | 进行中 |

---

## [创建] 一句话描述

LLM 调用 ContextManage 工具时，`pipeToolExecutions` 返回含更新状态（turnIdx/stagnation）的 `processing` behavior，但 `AgentActor` 中 `didContextManage` 分支返回 `Behaviors.same` 丢弃了该状态更新；当 ContextManage 与其他工具混合调用时，所有工具结果被丢弃；子 agent `context-manage-*` 崩溃后 `DelegateResult` 的 Left 路径丢失 compaction 上下文。

---

## [创建] 影响评估

| 维度 | 评估 |
|------|------|
| 影响范围 | 全部用户 |
| 严重程度 | High |
| 数据风险 | 可能丢失 — 混合调用时其他工具结果被丢弃，turnIdx/stagnation 状态不同步 |
| 复现频率 | 高频 — 任何触发 ContextManage 的场景都会遇到 |

---

## [创建] 复现

### 环境

- 版本/分支：`fix/abort-stream-cleanup`
- 引入该 bug 的 commit：ContextManage 作为 synthetic tool 引入时（`AgentCore.pipeToolExecutions` line 387-390）
- 运行方式：正常对话，当上下文窗口接近阈值时自动触发 compaction

### 步骤

1. 启动应用，进行长时间对话直到上下文接近阈值
2. 或者直接让 LLM 调用 ContextManage 工具
3. 观察：如果 LLM 同时调用了 ContextManage + Read 等其他工具，Read 的结果被丢弃
4. 观察：子 agent `context-manage-*` 可能 terminate unexpectedly

### 实际行为

1. **混合工具结果丢失**：`AgentActor.processing` 的 `ToolsComplete` handler 检查 `didContextManage`，为 true 时返回 `Behaviors.same`，丢弃了所有工具结果（包括其他正常工具的返回）
2. **状态更新丢失**：`pipeToolExecutions` 返回的 `processing` behavior 包含更新后的 `updatedState`（turnIdx+1, 新 stagnation count, 新 stage），但 `AgentActor` 的 `ToolsComplete` handler 在 `didContextManage` 分支返回 `Behaviors.same`，这些状态更新永远不会生效
3. **`TriggerCompaction` race**：`pipeToolExecutions` 在 `ContextManage` case 中 `ctx.self ! TriggerCompaction`，同时方法末尾返回 `processing(agentDef, ..., updatedState, ...)`。`TriggerCompaction` 到达时 handler 也返回 `processing(state.withPendingCompaction(...))`。后者覆盖前者，前者的状态更新丢失
4. **子 agent 崩溃**：`context-manage-ef5f0acf` terminated unexpectedly — 可能是 compaction sub-agent 的 LLM 调用失败（无错误日志），或 supervisor restart limit 耗尽
5. **`ContextManageTool.call` 是死代码**：`AgentCore.pipeToolExecutions` line 387-390 将 `ContextManage` 作为 synthetic tool 拦截，从不调用 `ContextManageTool.call`。但该工具仍在 `ToolRegistry` 中注册

### 期望行为

1. ContextManage 不应与其他工具混合调用的结果产生冲突
2. turnIdx/stagnation/stage 状态更新应始终生效
3. 子 agent 崩溃应有清晰的错误日志和优雅降级
4. `ContextManageTool.call` 要么被正确调用，要么从 registry 中移除

---

## [结束] 根因分析

### 根本原因（已定位时填写）

**问题 1：混合工具调用结果丢失**

`AgentActor.scala:149-154`：

```scala
case tc: ToolsComplete =>
  val didContextManage = tc.results.exists { case (call, _) => call.name == "ContextManage" }
  if didContextManage then
    // ALL results discarded — including non-ContextManage tool results
    Behaviors.same
  else
    // normal processing...
```

当 LLM 返回 `[ContextManage, Read, Grep]` 三个工具调用时，`ToolsComplete` 包含三个结果，但 `didContextManage = true` 导致 `Behaviors.same`，Read 和 Grep 的结果全部丢弃。

**问题 2：`pipeToolExecutions` 双重返回 behavior**

`AgentCore.scala:387-390` 发送 `TriggerCompaction` 消息：

```scala
case "ContextManage" =>
  val mode = call.input("mode").flatMap(_.asString).getOrElse("full")
  ctx.self ! AgentCommand.TriggerCompaction(mode, None)
  IO.pure(ToolExecResult(s"[ContextManage] Triggered $mode compaction", isError = false))
```

同时 `pipeToolExecutions` 末尾 line 512 返回：

```scala
processing(agentDef, resources, depth, parentRef, updatedState, stash, ctx)
```

`TriggerCompaction` handler (`handleTriggerCompaction`) 又返回 `processing(state.withPendingCompaction(...))`。Pekko actor 中后到消息的处理结果覆盖先到的，`updatedState` 中的 turnIdx/stagnation 更新丢失。

**问题 3：`ContextManageTool.call` 死代码**

`AgentCore.pipeToolExecutions` 在 `SyntheticTools` 集合中包含 `"ContextManage"`（line 29），`call.name match` 在 line 387 拦截处理。`ToolRegistry` 仍注册了 `ContextManageTool`，其 `call` 方法永远不被调用。

### 排查记录

- 子 agent 崩溃：`context-manage-6384af70` / `context-manage-ef5f0acf` terminated unexpectedly。根因可能是 compaction sub-agent 的 LLM 请求过大（messages JSON）或 prompt 格式问题
- 需要确认 `CompactPrompts.full` / `CompactPrompts.micro` 是否在 agent 定义中正确配置

---

## 修复方案

### 方案 A：将 ContextManage 从 pipeToolExecutions 的 IO 遍历中摘除（推荐）

1. **`pipeToolExecutions` 中**：`ContextManage` 不参与 `limitedFreshCalls.traverse` 的 IO 执行，而是提取到单独的列表中，在 traverse 完成后批量发送 `TriggerCompaction`
2. **`ToolsComplete` handler 中**：不再需要 `didContextManage` 分支。ContextManage 的 `TriggerCompaction` 已经在 `pipeToolExecutions` 中发送，`ToolsComplete` 只包含真实工具的结果
3. **移除 `ContextManageTool`**：从 `ToolRegistry` 和 `SyntheticTools` 中移除，定义完全由 `buildToolList` 中的 inline `ToolDefinition` 提供

```scala
// AgentCore.scala — pipeToolExecutions 修改
// 将 ContextManage 从 freshCalls 分离
val (contextManageCalls, realToolCalls) = limitedFreshCalls.partition(_.name == "ContextManage")

// 只执行真实工具
val io = realToolCalls.traverse { call => ... }

// 在 IO 完成后发送 TriggerCompaction
.flatMap { freshResults =>
  // 发送 ContextManage 的 TriggerCompaction
  contextManageCalls.foreach { call =>
    val mode = call.input("mode").flatMap(_.asString).getOrElse("full")
    ctx.self ! AgentCommand.TriggerCompaction(mode, None)
  }
  // ... rest of logic
}
```

```scala
// AgentActor.scala — ToolsComplete handler 简化
case tc: ToolsComplete =>
  // 不再需要 didContextManage 分支
  val toolCalls = tc.results.map((call, _) => call)
  // ... normal processing
```

### 方案 B：保留 ContextManage 在 traverse 中但修复结果处理

1. `didContextManage` 分支中提取非 ContextManage 工具的结果并正常处理
2. 仅跳过 ContextManage 工具的 tool_use/tool_result 追加
3. 保留 `pipeToolExecutions` 返回的 `updatedState`

方案 A 更彻底，推荐方案 A。

---

## [创建] 范围

### In Scope

- [ ] 修复混合工具调用结果丢失（方案 A）
- [ ] 修复 turnIdx/stagnation 状态更新丢失
- [ ] 移除 `ContextManageTool` 死代码或正确连接其调用路径
- [ ] 补充回归测试：ContextManage + 其他工具混合调用

### Out of Scope

- [ ] 不改动 compaction 的 prompt 逻辑（`CompactPrompts`）
- [ ] 不改动 `TriggerCompaction` / `DelegateResult` 消息协议
- [ ] 不修复子 agent 崩溃的根因（需要单独排查 LLM 调用）

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| 方案 A 改动范围较大 | Medium | 逐步修改，每步 `sbt compile` 验证 |
| 移除 `ContextManageTool` 后 `ToolRegistry` 索引变化 | Low | 只影响 tool definition 生成，不影响运行时 |
| 子 agent 崩溃问题未在本 issue 修复 | Medium | 单独排查，当前 issue 确保崩溃不影响主 agent 状态 |

---

## [结束] 验证

### 复现验证

- [ ] 让 LLM 同时调用 ContextManage + Read，确认 Read 结果不被丢弃
- [ ] 确认 ContextManage 后 turnIdx/stagnation 状态正确更新

### 回归测试

```bash
sbt testOnly *AgentActor* *AgentCore*
```

- [ ] 现有功能不受影响
- [ ] 新增测试覆盖混合工具调用场景

---

## 关联

- Related to `017-actor-io-async-cleanup.md` — 同为 AgentActor 状态机正确性问题
- Related to `016-flatten-agent-state.md` — 状态拆分有助于暴露此类问题
- Blocks — 如果子 agent 崩溃是独立问题，需创建新 issue 跟踪

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `AgentCore.scala` | **Modify** | `pipeToolExecutions` 中将 ContextManage 分离出 traverse，单独发送 TriggerCompaction |
| `AgentActor.scala` | **Modify** | 移除 `didContextManage` 分支，简化 `ToolsComplete` handler |
| `ContextManageTool.scala` | **Delete** | 死代码，功能已由 synthetic tool inline 实现 |
| `registry.scala` | **Modify** | 移除 `ContextManageTool` 注册 |
| `AgentCore.scala` (buildToolList) | **Modify** | 确保 ContextManage ToolDefinition 仍被包含在 tool list 中 |

> 创建时填写"计划"，关闭前更新为"实际"。

---

## [结束] 关闭原因

> 见 `templates/_glossary.md` → 关闭原因。必须选择一项。

- [ ] 已修复
- [ ] 重复 — 重复 issue：
- [ ] 无法复现
- [ ] 不予处理 — 理由：
- [ ] 已过时
- [ ] 已取消 — 理由：

---

## [结束] 复盘（可选）

| 问题 | 回答 |
|------|------|
| 为什么这个 bug 会发生？ | |
| 为什么现在才发现？ | |
| 如何防止同类问题？ | |
| 工时预估偏差原因？ | |
