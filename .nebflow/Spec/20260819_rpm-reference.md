# Provider RPM / 并发参考表（ConcurrencyGate 配置指南）

- 日期：2026-08-19
- 作者：Backend（nebflow-project）
- 上下文：#296 API 并发管理收尾——RPM 参考表 + per-provider 并发配置喂入
- 状态：活文档（随实测更新）

## 1. 背景

Nebflow 的 ConcurrencyGate（`src/main/scala/nebflow/llm/gate.scala`）提供 per-provider
并发管理：maxConcurrency（并发上限）+ rpm（每分钟请求数）+ FIFO 排队。请求在 gate
饱和时**等待**而非直接打进 provider 限流器——防止多 agent 并发撞 API 限流引发重试风暴
（issue #19 家族，2026-08-18 2 亿 token 事故同源）。

本表给出各 provider 的**建议配置值**，用于 `~/.nebflow/nebflow.json` 的
`llm.providers.<id>` 下填写 `maxConcurrency` / `rpm` / `queueTimeoutMs`。
未显式配置的 provider 走默认值（`Defaults.scala`：maxConcurrency=3、RPM 无限制、
queueTimeout=60s）。

## 2. 参考表

| Provider | 实测/已知限制 | maxConcurrency 建议 | rpm 建议 | queueTimeoutMs 建议 | 依据 |
|----------|--------------|--------------------|----------|---------------------|------|
| **107**（llm.example.com） | **20 req/60s 滑动窗口**（litellm，按 api_key）；并发≥4 开始 429；有效吞吐峰值 ~35rpm（并发 2 档） | **2**（保守）~4 | **15**（留 25% 余量） | 60_000 | 2026-08-18 实测（`20260818_107-api-test.md` §5） |
| **kimi**（api.kimi.com） | 免费/低配档并发低，429 频繁 | 2 | 30 | 60_000 | 历史故障（k3 Overloaded → Stream retry 循环，token 事故根因之一）；建议实测补充 |
| **deepseek**（api.deepseek.com） | 免费档 RPM 限制严格（约 60 RPM）；`content[].thinking must be passed back` 循环易触发重试放大 | 2 | 45 | 60_000 | 2026-08-18 12:52 实证 thinking 循环；RPM 值待实测确认 |
| **zhipu**（open.bigmodel.cn） | GLM-5.3 无 vision（带图=静默假成功）；限流情况未知 | 3 | 无限制 | 60_000 | 保守默认；建议实测 |

## 3. 配置示例

`~/.nebflow/nebflow.json` 的 `llm.providers` 下：

```json
"107" : {
  "apiKey" : "sk--...",
  "baseUrl" : "https://llm.example.com/",
  "models" : [ ... ],
  "protocol" : "anthropic",
  "maxConcurrency" : 2,
  "rpm" : 15,
  "queueTimeoutMs" : 60000
}
```

### 语义要点（gate.scala 契约）

- `maxConcurrency: 0` = 并发无限制（RPM 仍生效）
- `maxConcurrency: N` = 同时最多 N 个 in-flight 请求，超出 FIFO 排队
- `rpm: M` = 60s 滑动窗口内最多 M 个请求（含排队后放行的），超出精确睡眠到窗口滑出
- `queueTimeoutMs` 到期 = `QueueTimeout`（Transient）→ fallback 链下一个 provider，
  不重试当前 provider、不标记 DOWN
- 配置变更通过 `POST /api/providers/reload` 或 WS 命令即时生效（gate 缓存重建），
  无需重启

## 4. 卡死恢复（TaskStuckWatcher 兜底，2026-08-19 确认语义）

并发管理配套的卡死恢复已完整落地（`TaskStuckWatcher.scala`）：

- **判定**：仅对 taskKinds（Delegate/Ephemeral/Flow/SubTask）+ Root，`status == Processing`
  且 `lastActivityMs` 超过阈值（默认 10min）→ 卡死
- **有进展不判卡死**：LLM 流 chunk / 工具完成 / turn 完成 touch 活动戳；
  BashTool 活动桥接（#319，2026-08-19）在长前台命令有输出/CPU/sleep 时刷新——
  长 build/test 不再被误杀
- **恢复**：子 agent 发 Stop → BackoffSupervisor 退避重启（5s→60s，maxRestarts 熔断）；
  根 agent 广播 taskStuck WS 事件由用户决定
- **idle 永不判卡死**：run_in_background 后台任务等待期是合法状态（防误杀铁律）

## 5. 待办

- [ ] kimi / deepseek / zhipu RPM 实测补充（当前为保守估计值）
- [ ] 429 退避策略与 `llm-fail-retry` 预算（per-turn ≤4）的联动验证
