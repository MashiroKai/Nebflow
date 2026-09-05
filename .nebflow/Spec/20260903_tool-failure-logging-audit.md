# 工具执行日志现状盘点（重点：失败记录）

**日期**: 2026-09-03 · **类型**: 只读探查报告（阶段文档，未改任何源码）
**范围**: `/Users/dev/Claude code/Nebflow/src/main/scala/nebflow/`，结论经真实 `~/.nebflow/logs/nebflow.log` 数据交叉验证

---

## 一句话结论

**有日志，但没有"工具遥测"**。每次工具调用（成功+失败）都在 `~/.nebflow/logs/nebflow.log` 留一行文本（单点集中在 `AgentCore.executeToolInner`）：成功=INFO+耗时，失败=WARN+耗时+错误文本前 100 字符。完整错误文本和输入参数只落在**会话历史**（`sessions/<id>.ui.json`）与 **LLM 请求日志**（`logs/router/*.jsonl`），没有结构化、无关联 ID、无聚合统计——作者想按工具/按原因分析失败率，现状只能正则扫混流文本日志。

---

## 1. 工具执行链路的日志埋点——现状

### 1.1 唯一 choke point：AgentCore

所有 agent kind（Root/Team/Delegate/SubTask/Flow/Ephemeral）的工具轮都经过同一入口：

| 环节 | 位置 | 说明 |
|------|------|------|
| 权限判定→执行 | `agent/AgentCore.scala:968-976` | Allow→executeTool；Deny→isError 结果 |
| **executeTool** | `agent/AgentCore.scala:1318` | 参数 JSON 畸形检测（#18）后进 inner |
| **executeToolInner** | `agent/AgentCore.scala:1335-1422` | **核心埋点**：计时 + flatTap 日志 |

记录代码（`AgentCore.scala:1415-1420`）：

```scala
.flatTap { result =>
  val elapsed = (System.nanoTime() - start) / 1_000_000
  if result.isError then
    logger.warn(s"$logCtx Tool $summary failed (${elapsed}ms): ${result.content.take(100)}")
  else logger.info(s"$logCtx Tool $summary OK (${elapsed}ms)")
}
```

### 1.2 记录了什么字段

- **工具名+参数摘要**: `summary = tool.summarize(input)`（如 `NodeEdit(实施-xxx)`、`Read(file.scala, offset=5)`，见 `ReadTool.scala:82-94`）
- **耗时**: 毫秒
- **上下文前缀**: agent 名 + session **名**（`ctxPrefix`，`AgentCore.scala:1340-1342`；`logging.scala:26-27`）
- **失败时**: 错误文本**前 100 字符**（`result.content.take(100)`）

### 1.3 没记录什么（关键字段缺口）

- 无 toolUseId / request_id（无法与 router/*.jsonl 的 LLM 请求关联）
- 无完整输入参数（长命令只取首行前 100 字符，`BashTool.scala:518`）
- 无结果大小（成功行完全零内容）
- 无 sessionId（只有 sessionName 字符串，重名会话不可区分）；非 agent 上下文显示 `-`

### 1.4 写到哪里

- **文件**: `~/.nebflow/logs/nebflow.log`（`logback.xml:14-31`）——与全部其他日志**混流**；滚动 50MB/文件、7 天历史、200MB 总量上限
- **级别**: 文件 DEBUG 起、控制台 INFO 起 → 失败 WARN / 成功 INFO 均落盘
- **无专门结构化文件**: `logs/` 下现有 `nebflow.log`、`router/*.jsonl`（LLM 层）、`autostart.log`、`restart.log`、`watchdog.log`、`wd-fails`——**没有工具专属文件**
- 注：`NebflowLogger` 已支持 KV 尾参（`logging.scala:38-45`），但工具行没有用，是纯拼接文本

真实日志样例（今日实测）：

```
2026-09-03 03:54:46.489 [io-compute-blocker-1] WARN  nebflow.handlers - project-dispatcher dispatcher/nebflow Tool NodeEdit(修复-FlowMap引擎缺口·重派) failed (0ms): New node needs 'task' and/or 'in' ...
2026-09-03 10:47:04.044 [io-compute-blocker-34] INFO  nebflow.handlers - Manager html-deck-studio/Manager Tool TeamTaskCreate(海报 v6→v7 微调三点) OK (5ms)
```

---

## 2. 失败路径——记什么、落不落盘

### 2.1 失败来源（全部收敛为 `ToolExecResult(isError=true)`）

| 失败类型 | 位置 |
|---|---|
| 会话权限策略拒绝 | `AgentCore.scala:973` |
| 权限请求 pending / 用户拒绝 | `AgentCore.scala:1183, 1232, 1245` |
| 工具不存在 / 已下线 | `AgentCore.scala:1008, 1422` |
| 参数 JSON 解析失败（#18） | `AgentCore.scala:1330-1331` |
| Hook 阻断 | `AgentCore.scala:1349-1352` |
| 远程执行失败 | `AgentCore.scala:1369-1375` |
| 工具返回 Left(ToolError) | `AgentCore.scala:1385-1392` |
| 工具抛异常（含超时） | `AgentCore.scala:1410-1412` → `"Tool execution error: ..."` |
| 外层 attempt 兜底 | `AgentCore.scala:979-983` |

### 2.2 错误文本的三个落点

1. **nebflow.log WARN 行** — 有，但**截断到 100 字符**（`AgentCore.scala:1418`）
2. **会话历史（完整文本）** — WS `toolEnd`/`agentToolEnd` 事件（`protocol.scala:471, 540-560`，content=完整结果+`isError` 布尔+input JSON）→ `SessionRecorder.scala:53-62` 持久化为 `UiMessage.Tool`（`protocol.scala:283-290`）→ `~/.nebflow/sessions/<id>.ui.json`；Project node 子会话同样落（`WebSocketRoutes.scala:4128-4158`）
3. **LLM 请求日志（完整文本）** — 错误作为 tool_result(is_error) 进入下一轮消息历史，被 `LlmLogWriter` 记入 `logs/router/*.jsonl`（`LlmLogWriter.scala:277-283` 输出 `is_error` 字段）——但埋在 message_refs 对象里，不可直接查询，且只留 3 天

### 2.3 好检索吗？有没有聚合？

- **检索**: 二值可 grep——`WARN.*nebflow.handlers.*failed` vs `INFO.*nebflow.handlers.*OK`。可行但要正则，且混在几万行/天的全量日志里
- **聚合**: **没有**。全代码库无 toolStats/toolMetrics/失败计数存储（唯一 failCount 是 NeblinkDiscovery 自身重连逻辑，`NeblinkDiscovery.scala:32`）；`UsageRecordStore` 只记 LLM token 用量（`UsageRecordStore.scala:24`）

---

## 3. 已知机制核对

| 机制 | 覆盖 | 证据 | 与工具失败的关系 |
|---|---|---|---|
| **Bash START/RUNNING 留痕（#22）** | 长前台命令的 START 行 + 每 60s RUNNING 行（elapsed/输出行数/CPU/cmd 前 60 字符，≥10min 转 WARN）+ 后台任务停滞 kill WARN | `BashTool.scala:508-521, 549-593`；`shell.scala:798-805` | 补的是「活着但没完成」的时间线盲区，**不解决失败归因** |
| **router/*.jsonl（LlmLogWriter）** | LLM 请求/响应/SSE/usage 完整结构化，content-addressed objects，3 天保留，intake 事件（gate-wedge P2） | `LlmLogWriter.scala:29-56, 230-245` | **间接**含工具失败（下一轮消息历史的 is_error tool_result），但需解析 full.jsonl+对象存储才能统计，3 天窗口短 |
| **TaskStuckWatcher** | turn 级卡死识别：WARN 日志 + taskStuck WS 广播 + 分级恢复（Stop→硬取消→Cancelled 兜底） | `TaskStuckWatcher.scala:60, 135-146, 149-297` | 覆盖「卡死」而非「调用失败」；本身就是 WARN 文本，无结构化事件留存 |

**共同盲区**: 三者都是「文本 WARN 行 + WS 瞬时事件」，没有任何一个产出**可事后统计的结构化工具级记录**。

---

## 4. 盲区清单（差距汇总）

1. **无结构化工具执行日志** — nebflow.log 纯文本混流，工具行无 KV 字段（logger 有 KV 能力但未用，`logging.scala:38-45`）
2. **错误文本截断 100 字符** — 长 stack/长校验信息在日志里不完整（完整文本散落在各 session .ui.json）
3. **无关联 ID** — 无 toolUseId/request_id/sessionId，工具行与 router LLM 请求、跨 agent 链路对不上
4. **成功行零信息** — 无结果大小/行数，无法观察结果规模异常
5. **无聚合与失败率视图** — 按工具/按 agent/按错误类型统计 = 不存在，只能 ad-hoc 正则
6. **保留期不匹配** — nebflow.log 7 天/200MB cap（高频下实际更短），router 3 天；复盘窗口有限
7. **并发交错** — 所有 agent 工具行共用一个文件，靠 `agent session` 文本前缀区分，无会话隔离
8. **错误无分类** — 超时/权限拒绝/参数错误/异常/不存在都混为同一种 WARN，无 error class 标注（LLM 层有 `Fallback.classifyError`，工具层没有对应物）

---

## 5. 分层改进方案

### 方案 A — 最小改动：结构化 KV 化现有日志行（~0.5 天）

- **做法**: `AgentCore.scala:1415-1420` 的 flatTap 改用已有的 KV 重载：`logger.warn("Tool failed", "tool"->name, "agent"->..., "session"->..., "elapsedMs"->..., "error"->content.take(500))`；错误截断放宽至 500
- **涉及文件**: `AgentCore.scala`（1 处）
- **收益**: `grep 'status=failed' nebflow.log | awk` 即可按工具统计；改动几乎零风险
- **局限**: 仍是混流文本，保留期/关联 ID 问题不解决

### 方案 B — 结构化落盘：ToolsLogWriter 专用 JSONL（~1-2 天）⭐ 推荐

- **做法**: 新建 `core/ToolsLogWriter.scala`（模式照抄 `LlmLogWriter`）：每次工具执行 append 一行 JSON 到 `~/.nebflow/logs/tools/<date>.jsonl`，字段：`ts / tool / agent / session / rootSession / isError / elapsedMs / inputSummary / inputJson(截断) / errorText(全量) / resultChars / toolUseId`；接入点同 AgentCore flatTap（elapsed/summary/result 现成）；保留期 prune 复用 LlmLogWriter 逻辑（可放宽到 14 天）
- **关联增强（+0.5 天）**: 把当前轮 request_id 经 `ToolContext`（`tools/types.scala:13-67` 加一字段）传入，工具行即可与 `router/*.jsonl` 精确对齐
- **涉及文件**: 新文件 1 + `AgentCore.scala` 1 处 +（可选）`types.scala` 1 字段
- **收益**: `jq 'select(.isError)' tools.jsonl | group_by(.tool)` 直接出失败率；离线分析零侵入

### 方案 C — 理想态：工具遥测聚合 + 失败率面板（~3-5 天）

- **做法**: B 的基础上——① 内存聚合器（tool×agent×errorClass 滚动窗口计数，参照 `UsageRecordStore` 模式）+ WS 事件暴露；② 错误分类器（超时/权限/参数/异常/不存在——LLM 层 `Fallback.classifyError` 思路的工具版）；③ 前端 dashboard：失败率趋势、Top 错误、按 agent 分布，可点进单条工具行（关联 router 请求回放）
- **涉及文件**: 新 `ToolsLogWriter`/`ToolsMetrics` + `AgentCore.scala` + `gateway`（WS 事件）+ 前端面板（`src/main/resources/web/`）
- **收益**: 从「事后翻日志」升级为「持续可观测」

**建议路径**: A 可并入 B（反正改同一行）→ 直接做 B；C 待 B 跑一段时间确认字段设计后再上。
