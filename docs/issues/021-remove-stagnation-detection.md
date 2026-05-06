# Refactor: 移除停滞检测逻辑

> **[必填]** 标记为必须填写，其余按需填写。
> 术语定义见 [`_glossary.md`](../issues/templates/_glossary.md)。
> **必填时机说明**：`[创建]` = 创建时必须填写；`[结束]` = 关闭/发布/完成前必须填写；未标注 = 按需填写
> 状态流转见 `_glossary.md` → 对应类型。

---

## [创建] 元信息

| 字段 | 内容 |
|------|------|
| 负责人 | @MashiroKai |
| 状态 | Draft |
| 标签 | refactor |
| 优先级 | P2-中 |
| 创建日期 | 2025-07-27 |
| 目标日期 | 未确定 |
| 预估工时 | 1h |
| 实际工时 | 进行中 |

---

## [创建] 一句话描述

移除 agent 的停滞检测/计数机制——LLM 循环是其自身问题，框架不应干预，整条检测链路包括前端提醒一并删除。

---

## 背景

停滞检测机制（stagnation detection）通过追踪每轮工具调用是否有"进展"来计数，达到阈值 5 时向前端发送警告气泡。但该机制存在根本性问题：

- **本质上是 LLM 自身的问题**：重复调用、无进展循环是模型推理能力问题，框架无法真正解决
- **检测结果粗糙**：当前 `evaluateProgress` 仅判断"是否有工具调用"，等于把任何无工具调用的纯文本回复都算作"停滞"，这并不合理
- **只做警告不干预**：既然只发一个提醒气泡，复杂的计数+检测+状态传递链就不值得维护

---

## [创建] 动机

**现状是什么？为什么它是个问题？**

停滞检测逻辑散布在 6+ 个文件中，涉及状态管理、消息传递、前端渲染，但最终效果仅是一个提醒气泡。整条链路：

```
AgentCore.pipeToolExecutions
  → evaluateProgress(freshCalls, state)    // 判断有无进展
  → newStagnationCount++                   // 计数
  → SafetyContext.stagnationCount           // 存储状态
  → ToolsComplete.nextStagnationCount       // 传递
  → AgentActor 更新 state.stagnationCount
  → 达到 5 时 emitStream StagnationWarning  // 发送事件
  → protocol.toJson                        // 序列化
  → WebSocketRoutes 记录 UiMessage.Stage   // 持久化
  → 前端 renderStagnationWarning            // 渲染气泡
  → persistence.js 渲染历史                 // 持久化气泡
```

维护成本与价值不匹配。

---

## [创建] 目标

删除整条停滞检测链路，包括 `evaluateProgress`、`stagnationCount` 状态、`StagnationWarning` 事件、前端渲染代码。保留 `DeclareWaitTool`（它有独立语义：告诉框架"我在等外部条件"）。

---

## [创建] 范围

### In Scope

- [ ] 删除 `SafetyContext.stagnationCount` 字段
- [ ] 删除 `AgentState` 构造器中 `stagnationCount` 参数
- [ ] 删除 `AgentState.stagnationCount` accessor 和 `withStagnationCount` helper
- [ ] 删除 `ToolsComplete.nextStagnationCount` 字段
- [ ] 删除 `AgentStreamEvent.StagnationWarning` case class 及其 `toJson` 分支
- [ ] 删除 `AgentCore.evaluateProgress` 方法
- [ ] 删除 `AgentCore.pipeToolExecutions` 中停滞计数和警告发射逻辑
- [ ] 删除 `AgentActor` 中 `tc.nextStagnationCount` 的使用
- [ ] 删除 `UiMessage.Stage` case class 及其编解码
- [ ] 删除 `WebSocketRoutes` 中 `stagnationWarning` 的 UI 消息记录
- [ ] 删除前端 `renderStagnationWarning` 函数、`stagnationWarning` 消息处理、`persistence.js` 中 Stage 渲染

### Out of Scope

- [ ] `DeclareWaitTool` — 保留，它有独立用途（声明等待外部条件，不触发上下文压缩等）
- [ ] `SafetyContext` 其余字段（`recentToolCalls`、`recentFilesRead`、`hasInjectedAntiLoop`）— 保留，anti-loop 仍需要
- [ ] 前端 `stage` 类型消息的其他用途（如果有）— 需确认 `UiMessage.Stage` 是否仅用于停滞

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| 旧 localStorage 中有 `StagnationWarning` 消息 | Low | `persistence.js` 中 `stage` 类型用 switch/case，未匹配的 stage 会走 fallback（不渲染或简单渲染），无需特殊处理 |
| 编译遗漏引用 | Low | `sbt compile` 验证 |

---

## 兼容性

- **向后兼容：** No（删除了 WebSocket 事件类型）
- **行为变更：** 前端不再显示停滞警告气泡
- **迁移指南：** 无需——旧历史中的 `stagnationWarning` 消息会被前端忽略

---

## 关联

- Related to `016-flatten-agent-state.md` — state 扁平化时保留了 stagnationCount

---

## [结束] 详细变更

### 变更点 1: protocol.scala — 删除停滞相关定义

**Current:** `ToolsComplete` 有 `nextStagnationCount` 字段，`StagnationWarning` 是 `AgentStreamEvent` 子类，`SafetyContext` 有 `stagnationCount`，`AgentState` 有 accessor/mutator。

**New:** 删除上述所有字段和类。

**Rationale:** 停滞检测已无用，整条数据链可移除。

### 变更点 2: AgentCore.scala — 删除检测逻辑

**Current:** `evaluateProgress` 方法 + `pipeToolExecutions` 中计数/警告发射。

**New:** 删除 `evaluateProgress`，移除 `hadProgress`/`newStagnationCount`/警告发射代码。

**Rationale:** 核心检测逻辑已不需要。

### 变更点 3: AgentActor.scala — 移除状态更新

**Current:** `tc.nextStagnationCount.getOrElse(state.stagnationCount)` 更新 state。

**New:** 删除该行，直接使用原 state。

**Rationale:** 无数据需要传递。

### 变更点 4: shared/protocol.scala — 删除 UiMessage.Stage

**Current:** `UiMessage.Stage` case class + 编解码。

**New:** 删除。如果 `Stage` 是 `UiMessage` 的唯一子类使用了 `stage` 类型，确认删除安全。

**Rationale:** 无需持久化停滞警告。

### 变更点 5: WebSocketRoutes.scala — 删除记录逻辑

**Current:** `case "stagnationWarning"` 在 `makeRecordingWsSend` 中记录 `UiMessage.Stage`。

**New:** 删除该分支。

**Rationale:** 无事件需要记录。

### 变更点 6: 前端 JS — 删除渲染代码

**Current:** `chat.js` 的 `renderStagnationWarning`、`main.js` 的 `stagnationWarning` handler、`persistence.js` 的 Stage 渲染。

**New:** 删除函数、handler、渲染分支。

**Rationale:** 无事件需要渲染。

---

## [结束] 成功标准

- [ ] `grep -rn "stagnat\|Stagnat\|evaluateProgress" src/` 无结果（`DeclareWaitTool` 注释中的引用除外）
- [ ] `sbt compile` 通过
- [ ] 前端无 JS 报错
- [ ] 旧 localStorage 中的历史消息正常渲染（StagnationWarning 类型被优雅忽略）

---

## [结束] 验证

### 编译检查

```bash
sbt compile
```

### 静态检查

```bash
grep -rn "stagnat\|Stagnat\|evaluateProgress\|StagnationWarning" src/ --include="*.scala" --include="*.js"
# Expected: 无结果（或仅 DeclareWaitTool 注释）
```

### 运行时检查

- [ ] 发送消息，确认无 JS console 报错
- [ ] 确认历史消息正常加载

### Code Review Checklist

- [ ] `DeclareWaitTool` 保留且功能正常
- [ ] `SafetyContext` 其余字段不受影响
- [ ] `UiMessage` 其他子类不受影响

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `agent/protocol.scala` | **Modify** | 删除 `ToolsComplete.nextStagnationCount`、`StagnationWarning`、`SafetyContext.stagnationCount`、accessor/mutator |
| `agent/AgentCore.scala` | **Modify** | 删除 `evaluateProgress`、停滞计数和警告发射代码 |
| `agent/AgentActor.scala` | **Modify** | 删除 `nextStagnationCount` 使用 |
| `shared/protocol.scala` | **Modify** | 删除 `UiMessage.Stage` |
| `gateway/WebSocketRoutes.scala` | **Modify** | 删除 `stagnationWarning` 记录分支 |
| `web/js/chat.js` | **Modify** | 删除 `renderStagnationWarning` |
| `web/js/main.js` | **Modify** | 删除 `stagnationWarning` handler 和 import |
| `web/js/persistence.js` | **Modify** | 删除 Stage 渲染分支 |

> 创建时填写"计划"，关闭前更新为"实际"。

---

## [结束] 关闭原因

> 见 `_glossary.md` → 关闭原因。必须选择一项。

- [ ] 已完成
- [ ] 重复 — 重复 issue：
- [ ] 不予处理 — 理由：
- [ ] 已过时
- [ ] 已取消 — 理由：

---

## [结束] 复盘（可选）

> `[结束]` 时填写。若关闭原因为"重复"/"无法复现"/"已过时"，可跳过。

| 问题 | 回答 |
|------|------|
| 目标达成了吗？ | |
| 有什么意外？ | |
| 代码质量改善可量化吗？ | |
| 工时预估偏差原因？ | |
