# Refactor Template

> **[必填]** 标记为必须填写，其余按需填写。
> 术语定义见 [`_glossary.md`](./_glossary.md)。
> **必填时机说明**：`[创建]` = 创建时必须填写；`[结束]` = 关闭/发布/完成前必须填写；未标注 = 按需填写
> 状态流转见 `_glossary.md` → 对应类型。
>
> **升级规则**：见 `_glossary.md` → Quick Fix 升级规则。满足任一条件时，改用完整模板。
> 小型重构可跳过可选区块。

---

## [创建] 元信息

| 字段 | 内容 |
|------|------|
| 负责人 | @username |
| 状态 | 见 `_glossary.md` → 状态流转 |
| 标签 | 见 `_glossary.md` → 标签 |
| 优先级 | P0-阻塞 / P1-高 / P2-中 / P3-低 |
| 创建日期 | 2026-05-06 |
| 目标日期 | YYYY-MM-DD / 未确定 |
| 预估工时 | Xh / 未评估 |
| 实际工时 | Xh / 进行中 |

---

## [创建] 一句话描述

> 用一句话精确定义本 issue。例见各类型模板。
> 例：Extract CRUD from SessionActor into cats-effect services.

---

## 背景（可选）

> 按需填写。如果本次变更是某个更大重构的后续步骤，简要说明前置 issue 已完成什么、本次 issue 解决剩余哪一部分。

> 例：Issue `002-simplify-session-actor.md` 已将 CRUD 提取为 Service，但 `wsSend` 仍留在 `SharedResources` 中。本次 issue 完成 actor/IO 边界清理的最后一步。

---

## [创建] 动机

**现状是什么？为什么它是个问题？**

- 用 1-3 段描述当前代码/架构的问题。
- 如果涉及职责混乱，用表格列出各职责的当前归属和应归属。

| Concern | Current Location | Should Be |
|---------|------------------|-----------|
| ... | ... | ... |

- 引用关键代码片段（精简，只保留核心矛盾）。

```scala
// 问题示例：per-connection state 混在 global singleton 中
case class SharedResources(
  // ... global singletons ...
  wsSend: Json => IO[Unit],  // <-- 问题：per-connection state 不应在这里
)
```

---

## [创建] 目标

**一句话定义成功状态。**

> 例：Remove `wsSend` from `SharedResources` entirely. Pass it as an explicit constructor parameter to every component that needs it.

---

## [创建] 范围

### In Scope

- [ ] 明确列出必须包含的内容

### Out of Scope

- [ ] 明确列出不包含的内容
- [ ] 本次不处理的关联问题（如有，拆分为新 issue）：

> 例：No behavior changes — all WebSocket messages sent should be identical.

---

## 当前架构（可选）

> 按需填写。仅在结构复杂、文字难以表达时描述当前架构。

### 组件关系

| 组件 | 职责 | 问题 |
|------|------|------|
| GuardianActor | 全局单例管理 | |
| SessionActor | 每连接管理 | 过于臃肿（~475 行），混合了 CRUD 和 actor 逻辑 |
| AgentActor | 每轮对话 | |

### 具体问题

1. **Mixed concerns**: 400+ lines handling ...
2. **`unsafeRunAndForget` everywhere**: ...

---

## 设计原则（可选）

> 按需填写。用一句话概括指导本次变更的核心设计原则。

---

## 目标架构（可选）

> 按需填写。仅在结构复杂时描述目标组件关系。

| 组件 | 技术栈 | 职责 |
|------|--------|------|
| WebSocket connection | cats-effect fiber | 连接生命周期管理 |
| SessionService | cats-effect | session CRUD + notifications |
| SessionActor | Pekko Typed | 精简至 ~120 行，仅处理消息路由 |
| AgentActor | Pekko Typed | 每轮对话，保持不变 |

---

## [结束] 详细变更

### [变更点标题]

**Current:** 现状描述

**New:** 变更后描述

```scala
// 变更后的代码示例
```

**Rationale:** 为什么这样改？

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| | Low / Medium / High | |

---

## 兼容性

- **向后兼容：** Yes / No
- **行为变更：** 如有，列出具体变更
- **迁移指南：** 如果需要调用方调整，说明步骤

---

## 关联

- Depends on `xxx.md` — 前置依赖
- Blocks `yyy.md` — 阻塞后续工作
- Related to `zzz.md` — 相关但无依赖
- Supersedes `www.md` — 替代旧 issue
- Introduced by `commit/PR` — 引入该问题的变更

---

## 迁移步骤（可选，用于复杂重构）

> 风险等级：Low（可回滚）/ Medium（需监控）/ High（需灰度）

### Phase 1: [阶段名称]（Low/Medium/High）

1. **步骤一** — 说明
2. **步骤二** — 说明

### Phase 2: [阶段名称]（Low/Medium/High）

...

---

## [结束] 成功标准

- [ ] 可量化的成功指标 1
- [ ] 可量化的成功指标 2
- [ ] All existing functionality preserved.

---

## [结束] 验证

### 编译检查

```bash
sbt compile
```

### 静态检查

```bash
# 用 grep 等命令验证关键约束
grep -rn "resources\.wsSend" src/main/scala/nebflow/agent/
# Expected: no output
```

### 运行时检查

- [ ] 步骤一：...
- [ ] 步骤二：...

### Code Review Checklist

- [ ] 关键约束 1
- [ ] 关键约束 2

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| | | |

> 创建时填写"计划"，关闭前更新为"实际"。

| `Foo.scala` | **Delete** | 原因 |
| `Bar.scala` | **Rewrite** | 目标行数 |
| `Baz.scala` | **Modify** | 具体改动 |
| `OldName.scala` | **Move** | 重命名为 `NewName.scala` |
| `Qux.scala` | **Create** | 职责说明 |

---

## 权衡总结（可选）

> 按需填写。复杂重构时对比关键指标的变化。

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
