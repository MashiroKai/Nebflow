# core / compact

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

上下文压缩、工具结果 TTL、历史归档。

**入口建议**：先读 [`FullCompact.scala`](FullCompact.scala)（本模块最大文件）。

## 文件（13）

| 文件 | 说明 |
|---|---|
| [`CompactConfig.scala`](CompactConfig.scala) | Exponential backoff: delay = compactionRetryDelayMs * 2^(failures - 1) Returns 0 for failures=0 (no backoff ne |
| [`CompactService.scala`](CompactService.scala) | Inline context compaction — reuses the agent's own LLM call with cached system prompt and tool definitions.  N |
| [`CompactUtils.scala`](CompactUtils.scala) | Persisted-output marker — mirrors ToolResultGuard's tag so previews that were already persisted by the write-p |
| [`CompactionProfile.scala`](CompactionProfile.scala) | Determines compaction behavior for an agent based on its role in the flow hierarchy. Profiles select the pre-c |
| [`DreamMode.scala`](DreamMode.scala) | Dream-mode extraction helpers — fact-line parsing and entry identity. The Dream-mode mechanism is retired: the |
| [`FastMicroCompact.scala`](FastMicroCompact.scala) | Rule-driven micro compaction — no LLM call needed. Fires ONLY when the prompt cache is cold (distance from las |
| [`FullCompact.scala`](FullCompact.scala) | Outcome of a successful full-compaction parse. ≤ CompactConfig.preservedRounds; guardrails (char |
| [`HistoryArchiver.scala`](HistoryArchiver.scala) | Result of archiving a compaction — paths to generated files. |
| [`PingpongConfig.scala`](PingpongConfig.scala) | 乒乓压缩（主会话无感上下文压缩一期，spec §G2）配置开关。 读 `nebflow.json` 的 `pingpong.enabled`；**默认 false**（一期灰度主会话—— |
| [`PreCompactionHook.scala`](PreCompactionHook.scala) | Hook executed before LLM compaction runs. Each role (Root/Manager/Worker) has its own hook that extracts durab |
| [`RootMemoryHook.scala`](RootMemoryHook.scala) | Pre-compaction hook for the Root agent (Nebula, depth 0). Replaces DreamMode's idle timer: instead of a 5-minu |
| [`TokenEstimator.scala`](TokenEstimator.scala) | Token estimation for compaction triggering heuristics. Uses `chars/4` base ratio with CJK weighting — inspired |
| [`ToolResultTtl.scala`](ToolResultTtl.scala) | Tool-result TTL cleanup (#341, design tool-result-ttl 设计件). |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`prompts/`](../../prompts/module-doc.md)、[`core/hooks/`](../hooks/module-doc.md)、[`db/`](../../db/module-doc.md)
