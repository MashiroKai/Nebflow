# Progress-Aware Adaptive Loop

> **[必填]** 标记为必须填写，其余按需填写。
> 术语定义见 [`_glossary.md`](./_glossary.md)。
> **必填时机说明**：`[创建]` = 创建时必须填写；`[结束]` = 关闭前必须填写；未标注 = 按需填写
> 状态流转见 `_glossary.md` → 对应类型。

---

## [创建] 元信息

| 字段 | 内容 |
|------|------|
| 负责人 | @MashiroKai |
| 状态 | 草稿 |
| 标签 | backend |
| 优先级 | P1-高 |
| 创建日期 | 2026-05-04 |
| 目标日期 | 未确定 |
| 预估工时 | 8h / 未评估 |
| 实际工时 | 进行中 |

---

## [创建] 一句话描述

实现进展感知自适应循环，用停滞检测替代硬 turn 上限，保障长任务能力的同时防止无意义循环。

---

## 背景

Issue `loop-detection-comprehensive-fix.md` 已通过硬 turn 上限（`MaxTurns = 50`）和工具去重缓解了 AgentActor 的 296-turn 无限循环问题。但硬上限会截断合法的长任务（如大规模重构、深度调试），违背了「不限制模型能力」的设计原则。本 issue 提出一种更优雅的治本方案。

---

## [创建] 动机

**现状：**

- `MaxTurns = 50` 硬上限在 `pipeLlmCall` 顶部检查，超限强制 `finishTurn`，Agent 没有机会继续推进合法长任务。
- 当前去重仅检测 5 轮内的精确重复工具调用，无法捕获 A→B→C→A→B→C 的周期性循环。
- 没有区分「健康长任务」和「无意义循环」的机制。

**如果不做：**
- 用户发送复杂请求时，Agent 可能在完成前被截断。
- 需要不断调高 `MaxTurns` 数值，治标不治本。

---

## [创建] 目标

Agent 可以执行任意长度的任务，只要每轮都在产生新的、有价值的结果。当连续多轮无进展时，系统逐步收紧行为空间（而非突然截断），最终把控制权交还给用户（可继续、可完成、可拆分）。

---

## 优先级

| 属性 | 评估 |
|------|------|
| 优先级 | P1-高 |
| 目标版本 | 未确定 |
| 目标发布日期 | 未确定 |

---

## [创建] 范围

### In Scope

- [ ] 进展追踪引擎（Progress Tracker）：每轮评估「进展度」
- [ ] 自适应约束层（Adaptive Constraints）：4 个阶段（正常 → 谨慎 → 保守 → 暂停），由 `stagnationCount` 驱动
- [ ] 阶段回退机制：Agent 恢复有效推进后自动回退约束
- [ ] 智能子任务分解（Smart Subtask Decomposition）：复杂任务自动拆分为独立 subagent
- [ ] 结构化完成信号：显式 `finish` 工具替代「无 tool_use = 完成」
- [ ] `turnIdx` 降级为信息性指标（仅提示，不约束）

### Out of Scope

- [ ] 前端「计划确认」UI 组件的完整设计实现（本次仅预留事件/协议）
- [ ] 权限模式重构（Auto Mode 分类器）
- [ ] 工具结果截断/压缩（已在 `loop-detection-comprehensive-fix` 中处理）
- [ ] 上下文窗口压力信号（已有 auto-compaction）

---

## [创建] 需求

### 功能需求

- [ ] **FR-1** 进展度评估：每轮结束后判定本轮是否有「新信息」「副作用发生」或「知识增量」
- [ ] **FR-2** 停滞计数器：`stagnationCount` 在无进展轮次 +1，有进展时归零；连续 2/3/4 轮分别触发谨慎/保守/暂停阶段
- [ ] **FR-3** 阶段约束：
  - 谨慎（`stagnationCount ≥ 2`）：禁止重复读取已读文件，每轮需声明预期新信息，并行工具上限 3
  - 保守（`stagnationCount ≥ 3`）：禁用 Write/Edit/Bash；Read 工具允许读取已读文件的**不同 offset/段落**（视为新信息），但禁止重复读取已读文件的**相同位置**
  - 暂停（`stagnationCount ≥ 4`）：禁用所有工具，强制调用 `finish` 给出当前最佳答案；系统发送 `paused` WebSocket 事件给前端，前端以通知/提示条形式展示（不强制弹窗阻塞）；同时 Agent 输出文本形式的摘要
- [ ] **FR-4** 阶段回退：连续 2 轮有进展后自动回退到上一级约束
- [ ] **FR-5** 子任务分解触发：Agent 在规划阶段生成的执行计划（plan）中包含 >8 个步骤、或连续 5 轮未生成可识别的子任务边界（即未使用 `delegate` 工具或明确的步骤编号）、或用户显式要求时，生成计划卡片并分步 spawn subagent
- [ ] **FR-6** 结构化完成：Agent 必须显式调用 `finish` 工具并提供结构化 JSON 才算正常完成
- [ ] **FR-7** 异常申报：Agent 可通过系统级工具 `declareWait(reason: String)` 声明本轮预期无进展（如等待外部条件），该轮不计入 `stagnationCount`

### 非功能需求

- [ ] **NFR-1** 向后兼容：不破坏现有 `AgentActor` 消息协议和外部 API
- [ ] **NFR-2** 可观测性：`turnIdx`、`stagnationCount`、`currentStage` 通过 WebSocket 事件暴露给前端进度条
- [ ] **NFR-3** 性能：进展评估需在 actor 线程内同步完成，不引入 IO 延迟

---

## 设计

### 接口/行为变更

- **新增状态字段**（`protocol.scala`）：
  - `AgentState.stagnationCount: Int = 0`
  - `AgentState.stage: AdaptiveStage = Normal`
  - `AgentState.allPreviouslyReadFiles: Set[String] = Set.empty`
  - `AgentState.progressScore: Int = 0`（预留字段，未来用于更细粒度的进展度量）
- **新增枚举**（`protocol.scala`）：
  ```scala
  enum AdaptiveStage:
    case Normal, Cautious, Conservative, Paused
  ```
- **新增工具**：
  - `finish` 工具（内置），Agent 调用表示任务完成
  - `declareWait` 工具（内置），Agent 声明本轮预期无进展，该轮不计入 `stagnationCount`。Schema：`{"reason": String}`，系统验证后返回确认
- **新增 WebSocket 事件**：
  - `progressUpdate`（`turnIdx`, `stagnationCount`, `stage`）
  - `paused`（`summary`, `options: ["continue", "finish", "decompose"]`）：暂停阶段触发，前端以通知/提示条形式展示，不强制弹窗阻塞

### 关键决策

| 决策点 | 选项 A | 选项 B | 选择 | 理由 |
|--------|--------|--------|------|------|
| 阶段跃迁由谁驱动 | `turnIdx` + `stagnationCount` | 仅 `stagnationCount` | **B** | 不限制合法长任务 |
| 阶段是否单向 | 单向上升 | 可回退 | **可回退** | 防止「一次失误永久受限」 |
| 暂停后如何恢复 | 自动继续 | 用户确认 | **用户确认** | 参考 Claude Code Auto Mode，强制 checkpoint |
| 子任务拆分时机 | 仅用户触发 | 自动+用户 | **自动+用户** | 在问题发生前拆分 |
| 进展评估位置 | Actor 线程同步 | IO 线程异步 | **Actor 线程** | 避免 race，零延迟 |

### 待决策项

- [x] ~~保守阶段是否允许 `Read` 已读文件的不同 offset？~~ 已解决：允许读取不同 offset/段落，禁止重复读取相同位置
- [ ] `finish` 工具 schema 的具体字段
- [ ] 前端「暂停」弹窗的交互流程细节

### 代码示例

```scala
// 进展评估伪代码
def evaluateProgress(
  thisRoundTools: List[ToolCall],
  toolResults: List[ToolExecResult],
  state: AgentState
): ProgressResult =
  // 设计假设：同一文件路径若内容变更（如外部编辑），仍视为已读。
  // 如需追踪内容变更，可将 Set[String] 扩展为 Map[String, ContentHash]。
  val hasNewRead = thisRoundTools.exists { t =>
    t.name == "Read" && t.input("file_path").flatMap(_.asString)
      .exists(!state.allPreviouslyReadFiles.contains(_))
  }
  val hasSideEffect = thisRoundTools.exists { t =>
    Set("Write", "Edit", "Bash").contains(t.name) &&
    !toolResults.exists(_.content.contains("无变化"))
  }
  val hasKnowledgeGain = /* hash 比较本轮与前 10 轮结果 */
  ProgressResult(hasNewRead || hasSideEffect || hasKnowledgeGain)
```

---

## [创建] 兼容性

- **向后兼容：** Yes
- **迁移指南：** 无。新字段均有默认值，旧 actor 实例自动兼容。
- **废弃计划：** 完成本 issue 后，`MaxTurns` 硬上限可保留为最终安全网（值调大到 200），但主要依赖进展感知约束。

---

## [结束] 成功标准

- [ ] Agent 能在 50+ turn 的健康长任务中不被截断
- [ ] A→B→C 周期性循环在 4 轮内被检测到并进入暂停
- [ ] 同文件重复读取在谨慎阶段被拦截
- [ ] 暂停后用户可「继续」恢复 Agent，计数器重置
- [ ] 所有现有功能不受影响：现有 WebSocket 事件协议不变；现有 `AgentActor` 消息类型不变；SessionStore 读写行为不变（回归通过）
- [ ] `sbt compile` 通过

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| 进展评估误判（合法等待被算无进展） | Medium | 异常申报机制（FR-7）允许 Agent 声明等待状态 |
| 阶段回退过于敏感（刚回退又停滞） | Low | 回退要求连续 2 轮有进展，降低抖动 |
| 子任务拆分引入额外上下文开销 | Medium | subagent 只接收步骤所需上下文，不携带完整历史 |
| 前端暂停弹窗打断自动工作流 | Low | 无人值守模式下默认「继续」并通知用户 |
| 保守阶段死锁：任务必须写文件才能完成但写操作被禁用 | High | 用户可通过 `declareWait` 工具或显式回复授权一次写操作；Agent 在暂停阶段可请求用户授权 |

---

## [结束] 验证

### 功能验证

- [ ] 步骤一：发送需要 30+ turn 的复杂任务，验证不被截断
- [ ] 步骤二：构造 A→B→C 循环输入，验证 4 轮内进入暂停
- [ ] 步骤三：验证暂停后继续可重置计数器并恢复全能力
- [ ] 步骤四：验证子任务分解生成计划卡片并正确 spawn subagent

### 测试策略

- [ ] 单元测试：进展评估逻辑覆盖所有维度
- [ ] 单元测试：阶段跃迁和回退状态机
- [ ] 集成测试：端到端循环检测与暂停/恢复

### 回归检查

- [ ] 现有 AgentActor 消息协议不变
- [ ] WebSocket 事件不破坏前端

---

## 关联

- Depends on — 无前置依赖
- Blocks — 无
- Related to `loop-detection-comprehensive-fix.md` — 当前硬上限方案
- Supersedes — 本 issue 完成后，`loop-detection-comprehensive-fix.md` 中的硬上限降级为最终安全网
- Introduced by — 无

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `AgentActor.scala` | **Modify** | 新增进展评估、自适应约束、阶段跃迁/回退逻辑 |
| `protocol.scala` | **Modify** | 新增 `AdaptiveStage` 枚举、`AgentState` 新字段、结构化完成信号 |
| `AgentStreamEvent.scala` | **Modify** | 新增 `progressUpdate` 和 `paused` 事件类型 |
| `system.md` | **Modify** | 更新 Loop Prevention 为进展感知导向的提示 |
| `finish-tool.md` | **Create** | `finish` 工具 schema 定义文档（如有独立工具注册） |
| `declareWait.md` | **Create** | `declareWait` 工具 schema 定义文档（或并入 `finish-tool.md`） |
| `ProgressTrackerSpec.scala` | **Create** | 进展评估逻辑单元测试 |
| `AdaptiveLoopIntegrationSpec.scala` | **Create** | 阶段跃迁/回退集成测试 |

---

## [结束] 关闭原因

> 见 `_glossary.md` → 关闭原因。必须选择一项。

- [ ] 已发布
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
| 用户反馈如何？ | |
| 工时预估偏差原因？ | |
