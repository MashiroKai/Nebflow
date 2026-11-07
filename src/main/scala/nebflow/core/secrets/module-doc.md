# core / secrets

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

机密存储。

**入口建议**：先读 [`SecretsStore.scala`](SecretsStore.scala)（本模块最大文件）。

## 文件（4）

| 文件 | 说明 |
|---|---|
| [`AskUserSecretSink.scala`](AskUserSecretSink.scala) | secret 型 AskUser 的落库Sink（askuser-secret 批，2026-10-08 · 设计规格 §2/§4.4）。 AskUser 密码题的值在引擎内的**唯一落点**：网关 `handleAsk |
| [`SecretInjection.scala`](SecretInjection.scala) | The C-case injection allowlist (secrets-consumption batch, 2026-10-01). BY PATH is the universal baseline: a c |
| [`SecretsCatalog.scala`](SecretsCatalog.scala) | The three-field catalogue face of the secrets store (secrets-consumption batch, 2026-10-01). |
| [`SecretsStore.scala`](SecretsStore.scala) | Unified secrets store (secrets-panel backend leg, 2026-09-27). One management face over the EXISTING `~/.nebfl |

## 依赖的兄弟模块

[`core/`](../module-doc.md)、[`shared/`](../../shared/module-doc.md)、[`social/`](../../social/module-doc.md)
