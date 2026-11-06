# core / hooks

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

hook 配置加载与引擎。

**入口建议**：先读 [`HookRunner.scala`](HookRunner.scala)（本模块最大文件）。

## 文件（5）

| 文件 | 说明 |
|---|---|
| [`HookEngine.scala`](HookEngine.scala) | Hook engine — the core execution orchestrator. All events go through the single `emit()` entry point. The engi |
| [`HookMatcher.scala`](HookMatcher.scala) | Tool name matching for HookRule.matcher. Supports: exact, pipe-separated, wildcard, regex. |
| [`HookRunner.scala`](HookRunner.scala) | Spawns a hook process, sends JSON via stdin, reads JSON from stdout. |
| [`HookTypes.scala`](HookTypes.scala) | Event group — determines whether matcher is used. |
| [`HooksConfigLoader.scala`](HooksConfigLoader.scala) | Loads and parses hooks config from nebflow.json. |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)
