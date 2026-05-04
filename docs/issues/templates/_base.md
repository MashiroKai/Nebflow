# Issue 基础模板

> 本文件不直接使用。所有 issue 模板通过脚本从本文件注入公共区块。
> 修改本文件后，运行 `./_build.py` 重新生成所有模板。

---

<!-- BLOCK: header -->
> **[必填]** 标记为必须填写，其余按需填写。
> 术语定义见 [`_glossary.md`](./_glossary.md)。
> **必填时机说明**：`[创建]` = 创建时必须填写；`[结束]` = 关闭/发布/完成/偿还前必须填写；未标注 = 按需填写
> 状态流转见 `_glossary.md` → 对应类型。
<!-- END_BLOCK -->

---

<!-- BLOCK: meta -->
## [创建] 元信息

| 字段 | 内容 |
|------|------|
| 负责人 | @username |
| 状态 | 见 `_glossary.md` → 状态流转 |
| 标签 | 见 `_glossary.md` → 标签 |
| 优先级 | P0-阻塞 / P1-高 / P2-中 / P3-低 |
| 创建日期 | YYYY-MM-DD |
| 目标日期 | YYYY-MM-DD / 未确定 |
| 预估工时 | Xh / 未评估 |
| 实际工时 | Xh / 进行中 |
<!-- END_BLOCK -->

---

<!-- BLOCK: oneliner -->
## [创建] 一句话描述

> 用一句话精确定义本 issue。例见各类型模板。
<!-- END_BLOCK -->

---

<!-- BLOCK: scope -->
## [创建] 范围

### In Scope

- [ ] 明确列出必须包含的内容

### Out of Scope

- [ ] 明确列出不包含的内容
- [ ] 本次不处理的关联问题（如有，拆分为新 issue）：
<!-- END_BLOCK -->

---

<!-- BLOCK: risk -->
## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| | Low / Medium / High | |
<!-- END_BLOCK -->

---

<!-- BLOCK: related -->
## 关联

- Depends on `xxx.md` — 前置依赖
- Blocks `yyy.md` — 阻塞后续工作
- Related to `zzz.md` — 相关但无依赖
- Supersedes `www.md` — 替代旧 issue
- Introduced by `commit/PR` — 引入该问题的变更
<!-- END_BLOCK -->

---

<!-- BLOCK: changes -->
## 变更文件

| File | Action | Notes |
|------|--------|-------|
| | | |

> 创建时填写"计划"，关闭前更新为"实际"。
<!-- END_BLOCK -->

---

<!-- BLOCK: changes_example_bug -->
| `Foo.scala` | **Modify** | 修复逻辑 |
| `FooSpec.scala` | **Modify** | 补充回归测试 |
<!-- END_BLOCK -->

---

<!-- BLOCK: changes_example_feature -->
| `Foo.scala` | **Modify** | 新增接口 |
| `Bar.scala` | **Create** | 核心实现 |
<!-- END_BLOCK -->

---

<!-- BLOCK: changes_example_refactor -->
| `Foo.scala` | **Delete** | 原因 |
| `Bar.scala` | **Rewrite** | 目标行数 |
| `Baz.scala` | **Modify** | 具体改动 |
| `OldName.scala` | **Move** | 重命名为 `NewName.scala` |
| `Qux.scala` | **Create** | 职责说明 |
<!-- END_BLOCK -->

---

<!-- BLOCK: changes_example_test -->
| `FooSpec.scala` | **Create** | 新增单元测试 |
| `BarIntegrationSpec.scala` | **Create** | 新增集成测试 |
<!-- END_BLOCK -->

---

<!-- BLOCK: changes_example_quickfix -->
| `Foo.md` | **Modify** | |
<!-- END_BLOCK -->

---

<!-- BLOCK: close_reason_intro -->
> 见 `_glossary.md` → 关闭原因。必须选择一项。
<!-- END_BLOCK -->

---

<!-- BLOCK: retro_intro -->
> `[结束]` 时填写。若关闭原因为"重复"/"无法复现"/"已过时"，可跳过。
<!-- END_BLOCK -->
