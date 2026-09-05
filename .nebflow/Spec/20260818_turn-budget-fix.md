# TurnBudgetExceeded 误杀修复方案（紧急）

> 2026-08-18 23:37 用户反馈：Coder/正常干活 agent 换 deepseek 后仍报
> `turn LLM budget exceeded: 4 calls in turn 5 (max 4, includes retries)`。
> 根因：per-turn budget 把「正常工具循环调用」与「异常重试」混计，工具密集型 agent（读文件→改→编译→再改，每步一次 LLM 调用）正常干活 5 步即触发 fail-fast。

## 根因（已定位）

- `src/main/scala/nebflow/llm/fallback.scala` L68：
  `val MaxTurnLlmCalls: Int = 4` —— turn 内 LLM 调用总上限 4
- `src/main/scala/nebflow/agent/protocol.scala` L673-680：
  `llmFailRetries`（已有独立重试计数，成功即重置）+ `llmCallsThisTurn`（turn 内全部调用计数，含正常工具循环）
- `src/main/scala/nebflow/agent/AgentCore.scala` L571：
  `withLlmCallsThisTurn(stateWithBranch.llmCallsThisTurn + 1)` —— **每次 pipeLlmCall 都 +1**，包括工具循环后的正常继续调用。4 步工具循环即超预算。

设计假设「Normal turns make 1-3 calls」（fallback.scala L64 注释）对工具密集型 agent 不成立。

## 修复

### 方案 B（应急，1 行，立即解除误杀）

`fallback.scala` L68：
```scala
val MaxTurnLlmCalls: Int = 4
```
改为：
```scala
val MaxTurnLlmCalls: Int = 30
```
覆盖正常工具循环（10-20 步）+ 重试余量；30 次重试 × 250k 仍会 fail（防放大语义保留）。

### 方案 C（正确，budget 只计重试）

1. `AgentCore.scala` L569-571：递增从「每次 LLM 调用」改为「**仅失败重试路径**」——
   - 在 LlmFailed 分支（fallback 失败/llm-fail-retry）递增 `llmCallsThisTurn`
   - 正常工具循环的成功调用**不递增**
2. `MaxTurnLlmCalls` 可保持 4（语义变为「同一 turn 内失败重试 ≤4 次即 fail fast」——正合防重试放大原意）
3. 更新 `protocol.scala` L674-680 注释（`llmCallsThisTurn` 语义：仅重试计数）

### 验证

1. `sbt compile` 通过
2. 派一个 Coder 做多步工具任务（读文件→Edit→再读），不再触发 `TurnBudgetExceeded`
3. 构造重试场景（如断网/坏 key），确认失败重试仍 ≤4 次 fail fast（防放大不失效）

## 附带提醒

- 修复后需重启 Nebflow 生效
- `llmFailRetries`（protocol.scala L673）已有成功重置逻辑——方案 C 可复用它，避免双计数器
