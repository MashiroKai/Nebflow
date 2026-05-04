# Bug: System Reminders 设计完成但未实际注入 LLM 上下文

> **[必填]** 标记为必须填写，其余按需填写。
> 术语定义见 [`_glossary.md`](./templates/_glossary.md)。
> **必填时机说明**：`[创建]` = 创建时必须填写；`[结束]` = 关闭前必须填写；未标注 = 按需填写
> 状态流转见 `_glossary.md` → 对应类型。

---

## [创建] 元信息

| 字段 | 内容 |
|------|------|
| 负责人 | @username |
| 状态 | 待处理 |
| 标签 | bug, system-reminder, agent-actor |
| 优先级 | P1-高 |
| 创建日期 | 2025-06-28 |
| 目标日期 | 未确定 |
| 预估工时 | 4h / 未评估 |
| 实际工时 | 进行中 |

---

## [创建] 一句话描述

`SystemReminders.collectAll` 及 7 种 reminder 类型（session info、context pressure、permission policy、file changes、verification、skill discovery）已完整实现，但从未被调用，导致所有 system reminder 无法注入 LLM 上下文。

---

## [创建] 影响评估

| 维度 | 评估 |
|------|------|
| 影响范围 | 全部用户 |
| 严重程度 | High |
| 数据风险 | 无 |
| 复现频率 | 必现 |

---

## [创建] 复现

### 环境

- 版本/分支：`main` (commit `f427b0b`)
- 引入该 bug 的 commit/PR：system reminder 模块在早期实现后，AgentActor 重构时未接入
- 运行方式：任意 WebSocket 会话

### 步骤

1. 启动 nebflow 并连接 WebSocket
2. 发送任意用户消息触发 AgentActor
3. 观察 LLM 请求的 system prompt

### 实际行为

system prompt 仅包含：
- `Repl.loadSystemPrompt()` 加载的 `system.md`
- `Repl.buildEnvInfo()` 生成的环境/Git 信息

没有任何 `<system-reminder>` 块被注入。

### 期望行为

根据 `ReminderState` 的状态，在每次 LLM 调用前动态注入以下 reminder：
- 新 session / idle 10min 后注入当前时间
- token 使用率跨越阈值时注入压力提醒
- session 开始时注入权限策略状态
- 策略变更后注入变更提醒
- 检测到外部文件修改时注入变更列表
- 连续 Write/Edit 未 Read 时注入验证提醒
- 向量匹配到 skill 时注入技能发现提醒

---

## [结束] 根因分析

### 根本原因（已定位时填写）

1. **`collectAll` 零调用点**

   `SystemReminders.collectAll` 定义在 `src/main/scala/nebflow/core/reminders.scala:91`，但全代码库 grep 无调用：

   ```scala
   def collectAll(
     stateRef: Ref[IO, ReminderState],
     usage: Option[TokenUsage],
     contextWindow: Int,
     fileChangesOpt: Option[SystemReminder],
     currentPolicy: PermissionPolicy,
     skillMatchOpt: Option[SkillMatch] = None
   ): IO[List[SystemReminder]] = ...
   ```

2. **system prompt 构建路径绕过 reminders**

   `AgentActor.buildSystemPrompt` (`AgentActor.scala:1308-1310`) 直接拼接静态内容：

   ```scala
   private def buildSystemPrompt(agentDef: AgentDef, resources: SharedResources): String =
     if agentDef.systemPrompt.nonEmpty then agentDef.systemPrompt
     else Repl.loadSystemPrompt() + "\n\n" + Repl.buildEnvInfo(resources.projectRoot.toString)
   ```

   完全没有调用 `SystemReminders.collectAll` 或拼接 reminder 渲染结果。

3. **`writesWithoutRead` 计数未维护**

   `ReminderState.writesWithoutRead` 初始为 0，且在代码中没有任何地方被递增或重置（除了 `ReadTracker` 能记录读取，但未与 `ReminderState` 关联）。

4. **基础设施存在但未接线**

   - `GatewayMain` 创建了全局 `Ref[IO, ReminderState]` (`GatewayMain.scala:86`)
   - `FileChangeTracker` 实现了外部文件变更检测 (`filewatch.scala`)
   - `WebSocketRoutes` 在 `setPolicy`/`clear` 时更新 `ReminderState` (`WebSocketRoutes.scala:167,180`)
   - `SharedResources` 将 `reminderStateRef` 传递给所有 agent
   - 但 `SessionActor`/`AgentActor` 的消息流中完全没有接入

---

## 修复方案

### 方案概述

在 `AgentActor.pipeLlmCall` 中，于构造 `messagesWithSystem` 之前，异步调用 `SystemReminders.collectAll`，并将渲染后的 reminders 拼接进 system prompt。

### 具体改动

1. **AgentActor：在 `pipeLlmCall` 中接入 collectAll**

   ```scala
   // 在 buildSystemPrompt 调用之后、构造 messagesWithSystem 之前
   val fileChangesOpt <- resources.fileChangeTracker.checkChanges()
   val skillMatchOpt <- resources.skillDiscovery.traverse(...)
   reminders <- SystemReminders.collectAll(
     resources.reminderStateRef,
     state.latestUsage,
     resources.contextWindow,
     fileChangesOpt,
     currentPolicy, // 需从 runtimePrefs 获取
     skillMatchOpt
   )
   val remindersText = SystemReminder.renderAll(reminders)
   val systemPrompt = buildSystemPrompt(agentDef, resources)
   val fullSystem = if remindersText.nonEmpty then s"$systemPrompt\n\n$remindersText" else systemPrompt
   val messagesWithSystem = Message(MessageRole.System, Left(fullSystem)) :: state.messages
   ```

2. **维护 `writesWithoutRead` 计数**

   在 `AgentActor.pipeToolExecutions` 中，每次 Write/Edit 工具执行成功后，通过 `resources.reminderStateRef.update` 递增计数；每次 Read 执行成功后重置计数。由于 `ReadTracker` 已存在于 `AgentState` 中，也可在 `pipeLlmCall` 前检查 `ReadTracker` 状态来更新计数。

3. **（可选）简化 `buildSystemPrompt` 返回值**

   将 system prompt 拆分为静态部分（`system.md` + `envInfo`）和动态部分（reminders），使职责更清晰。

---

## [创建] 范围

### In Scope

- [ ] 在 `AgentActor.pipeLlmCall` 中调用 `SystemReminders.collectAll`
- [ ] 将 reminder 渲染结果拼接进 system prompt
- [ ] 修复 `writesWithoutRead` 计数维护逻辑
- [ ] 确保 `/clear` 后 `sessionStarted` 重置（已有逻辑）仍然生效
- [ ] 补充简单日志验证 collectAll 被调用

### Out of Scope

- [ ] 不新增 reminder 类型
- [ ] 不修改 reminder 内容或阈值
- [ ] 不修改 `FileChangeTracker` 或 `SkillDiscovery` 的底层逻辑
- [ ] 本次不处理的关联问题（如有，拆分为新 issue）：`ReadTracker` 与 `ReminderState` 的深度整合可后续优化

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| 接入 collectAll 引入额外 IO（文件扫描、Ref 读取），增加 LLM 调用延迟 | Low | `FileChangeTracker.checkChanges` 已有 5s debounce；`collectAll` 是纯内存操作，可接受 |
| Reminder 注入导致 system prompt 变长，意外消耗 context window | Medium | reminder 内容本身已精简；可在拼接前估算长度，超限时不注入非关键 reminder |
| 并发消息时 `ReminderState` 竞争 | Low | `collectAll` 内部使用 `Ref.modify` 原子更新，已正确处理 |

---

## [结束] 验证

### 复现验证

- [ ] 启动 nebflow，连接 WebSocket，发送消息
- [ ] 检查日志中 `[sessionInfo]`、`[contextPressure]` 等 reminder 被输出
- [ ] 检查实际发送到 LLM 的 system prompt 包含 `<system-reminder>` 块

### 回归测试

```bash
# 运行相关测试
sbt test
```

- [ ] 现有功能不受影响
- [ ] AgentActor 正常完成多轮对话
- [ ] `/clear` 后 session reminder 重新注入

---

## 关联

- Depends on `xxx.md` — 前置依赖
- Blocks `yyy.md` — 阻塞后续工作
- Related to `zzz.md` — 相关但无依赖
- Supersedes `www.md` — 替代旧 issue
- Introduced by `commit/PR` — AgentActor 重构时遗漏接入

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `AgentActor.scala` | **Modify** | 在 `pipeLlmCall` 中接入 `collectAll` 并拼接 reminders |
| `reminders.scala` | **Modify** | 如有需要，调整 `collectAll` 签名以适配 AgentActor 调用 |

> 创建时填写"计划"，关闭前更新为"实际"。

---

## [结束] 关闭原因

> 见 `_glossary.md` → 关闭原因。必须选择一项。

- [ ] 已修复
- [ ] 重复 — 重复 issue：
- [ ] 无法复现
- [ ] 不予处理 — 理由：
- [ ] 已过时
- [ ] 已取消 — 理由：

---

## [结束] 复盘（可选）

> `[结束]` 时填写。若关闭原因为"重复"/"无法复现"/"已过时"，可跳过。

| 问题 | 回答 |
|------|------|
| 为什么这个 bug 会发生？ | System Reminder 模块早期实现后，AgentActor 从 REPL 模式重构为 Actor 模式时，system prompt 构建逻辑迁移到了 `buildSystemPrompt`，但未接入已有的 `SystemReminders.collectAll` |
| 为什么现在才发现？ | 该缺陷是"静默失效"——功能看起来存在（代码完整、状态机正常），但实际未生效，需要代码审查或 LLM 请求日志分析才能发现 |
| 如何防止同类问题？ | 1. 对 system prompt 构建增加集成测试，断言特定条件下包含 `<system-reminder>` 标签<br>2. 模块设计时明确"调用点"作为验收标准 |
| 工时预估偏差原因？ | |
