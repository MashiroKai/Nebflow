# llm / decision

> 模块向导（jev-retire 批 2026-11-16 收敛：JeV client 栈 10 件已随 JeV 全量退役删除；职责为人工定稿，文件说明取各自头注首句）。

## 功能

模型/代理决策机（Judge/SmallJudge 判定 + 小模型插件分配）。

**入口建议**：先读 [`DelegateJudgeMachine.scala`](DelegateJudgeMachine.scala)（本模块最大文件）。

## 文件（5）

| 文件 | 说明 |
|---|---|
| [`DecisionHealthMonitor.scala`](DecisionHealthMonitor.scala) | Health state of the decision face (mirrors `SearchApiHealth`). |
| [`DelegateJudgeMachine.scala`](DelegateJudgeMachine.scala) | DelegateJudgeMachine —— 判定机执行体（worktree-judge-fifo 批 #1224；jev-retire 批起判定 = SmallJudge（LLM）单层）. |
| [`DelegateModeMachine.scala`](DelegateModeMachine.scala) | DelegateModeMachine —— **delegate 模式选择器执行体**（双模式批 2026-10-28；设计 `20261028_213056_delegate-dual-mode-redesign`  |
| [`LlmAllocator.scala`](LlmAllocator.scala) | LlmAllocator —— **小模型分配腿**（jev-retire 批 2026-11-16 起唯一分配执行体；候选 = `SchemePolicy.smallLlmModel()`）. |
| [`PluginAllocatorMachine.scala`](PluginAllocatorMachine.scala) | PluginAllocatorMachine —— **插件分配编排体**（jev-plugins 批 #1226 起 face 装配；jev-retire 批后编排 = 小模型单腿）. |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/jev/`](../../core/jev/module-doc.md)、[`llm/`](../module-doc.md)、[`core/delegate/`](../../core/delegate/module-doc.md)、[`prompts/`](../../prompts/module-doc.md)、[`core/`](../../core/module-doc.md)、[`core/index/`](../../core/index/module-doc.md)
