# llm

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

模型调用层：provider 适配、回退链、健康监控（3 个子模块）。

**入口建议**：先读 [`interface.scala`](interface.scala)（本模块最大文件）。

## 文件（8）

| 文件 | 说明 |
|---|---|
| [`AdaptiveThrottle.scala`](AdaptiveThrottle.scala) | LLM provider 自适应并发限流器（429 反应式，作者令 2026-10-29 21:32）——**并发维 的自适应闸，与既有 retry/health 的时间维退避（Stream retry 60s / Pr |
| [`HealthMonitor.scala`](HealthMonitor.scala) | Interval between background probe cycles for Down providers (2 minutes). |
| [`ModelRegistry.scala`](ModelRegistry.scala) | Loads and caches model capability metadata from `~/.nebflow/models.json`. Priority for capability resolution: |
| [`SearchProviderResolver.scala`](SearchProviderResolver.scala) | How a capable provider wants search armed in an OpenAI-compatible request. |
| [`adapter.scala`](adapter.scala) | Stable system prompt — cached by providers that support it. |
| [`fallback.scala`](fallback.scala) | Raised when the all-Down gate ([[ProviderHealthMonitor.waitForAnyUp]]) timed out waiting for any provider to r |
| [`interface.scala`](interface.scala) | Raised when a JVM shutdown hook aborts an in-flight LLM request. |
| [`registry.scala`](registry.scala) | One candidate of a fallback chain. 生效**上下文窗口 = `min(configured, modelMaxContext)`：配置值（用户填写）与 provider 侧真值的较小者。 |

## 子模块（3）

- [`decision/`](decision/module-doc.md) — 模型/代理决策机（Judge/Llm/Plugin 分配）。
- [`imagegen/`](imagegen/module-doc.md) — 生图能力（编译期 seal）。
- [`providers/`](providers/module-doc.md) — provider 适配器与工具入参解码。

## 依赖的兄弟模块

[`shared/`](../shared/module-doc.md)、[`core/`](../core/module-doc.md)、[`db/format/`](../db/format/module-doc.md)、[`db/`](../db/module-doc.md)、[`llm/providers/`](providers/module-doc.md)
