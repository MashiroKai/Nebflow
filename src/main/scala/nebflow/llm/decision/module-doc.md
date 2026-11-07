# llm / decision

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

模型/代理决策机（Judge/Llm/Plugin 分配）。

**入口建议**：先读 [`DelegateJudgeMachine.scala`](DelegateJudgeMachine.scala)（本模块最大文件）。

## 文件（15）

| 文件 | 说明 |
|---|---|
| [`DecisionHealthMonitor.scala`](DecisionHealthMonitor.scala) | Health state of the decision face (mirrors `SearchApiHealth`). |
| [`DecisionHttpTransport.scala`](DecisionHttpTransport.scala) | Shared sttp transport for the decision providers (Face A / P1-A1). Mirrors the standalone-search leg's direct- |
| [`DecisionProvider.scala`](DecisionProvider.scala) | Decision-provider face (JeV integration, Face A / P1-A1). ==Why an independent file and NOT `ProviderAdapter`  |
| [`DecisionProviders.scala`](DecisionProviders.scala) | Decision provider factory (Face A / P1-A1). One place turns resolved [[JevSettings]] into a live provider, so  |
| [`DelegateJudgeMachine.scala`](DelegateJudgeMachine.scala) | DelegateJudgeMachine —— 判定机执行体（worktree-judge-fifo 批 #1224 增补 §3.1： ⑦ 判定机两层＝Jev 优先＋SmallJudge（LLM）兜底**，与分配族机制同 |
| [`DelegateModeMachine.scala`](DelegateModeMachine.scala) | DelegateModeMachine —— **delegate 模式选择器执行体**（双模式批 2026-10-28；设计 `20261028_213056_delegate-dual-mode-redesign`  |
| [`JevAllocator.scala`](JevAllocator.scala) | JeV allocator: task text + capability catalog -> capability set (JeV integration Face B/C, P2 + P3 method tran |
| [`JevSettings.scala`](JevSettings.scala) | Resolved, ready-to-call decision settings (Face A / P1-A2 + A3 consumption). Produced from the top-level `jev` |
| [`JevTaskSearchRerank.scala`](JevTaskSearchRerank.scala) | TaskSearch 的 Jev 重排执行体（task-rag 批 #1227 / §Batch 7「可选 Jev 重排」； ⑦「Jev 优先＋兜底」家族在检索面的同族落地）。 |
| [`JevWireCodec.scala`](JevWireCodec.scala) | JeV wire codec (Face A / P1-A1 adaptation layer). Renders the canonical internal question form into the JeV re |
| [`LayaProvider.scala`](LayaProvider.scala) | Self-hosted open-source LAYA provider (Face A / P1-A1). Transport is deliberately the SAME HTTP shape as the h |
| [`LayaRequestAdapter.scala`](LayaRequestAdapter.scala) | LAYA-side adaptation layer (Face A / P1-A1, P0 #2 adjudicated). The canonical internal form IS the JeV normati |
| [`LlmAllocator.scala`](LlmAllocator.scala) | LlmAllocator —— **LLM 兜底分配腿**（jev-plugins 批 #1226；原文「未配 Jev → 新 LlmAllocator（单次 LLM JSON 选插件，同语义）」的执行体）. |
| [`PluginAllocatorMachine.scala`](PluginAllocatorMachine.scala) | PluginAllocatorMachine —— **插件分配族编排体**（jev-plugins 批 #1226；⑦ 分配族 = Jev 优先＋LLM 兜底，与 #1224 判定机同族、机制同一——原文既定，裁定背书 |
| [`TypesafeJevProvider.scala`](TypesafeJevProvider.scala) | Hosted TypeSafe JeV provider (Face A / P1-A1). Wire: `POST <endpoint>` with `state` / `model` / `questions`, b |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/jev/`](../../core/jev/module-doc.md)、[`llm/`](../module-doc.md)、[`core/delegate/`](../../core/delegate/module-doc.md)、[`prompts/`](../../prompts/module-doc.md)、[`core/`](../../core/module-doc.md)、[`core/index/`](../../core/index/module-doc.md)
