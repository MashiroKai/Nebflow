# 工具结果体积保护机制：现状盘点 · 膨胀路径 · 分层完善方案

- 日期：2026-09-01（阶段文档，冻结不修订）
- 范围：Nebflow Scala 主仓（`src/main/scala/nebflow/`），纯分析，未改代码
- 触发：qa-backend 会话膨胀失能——Anthropic 拒绝 `requested 3.26M tokens > 1M limit`；restart/cancel/删文件/deleteSession 全无效（deleteSession 后 ~12s 历史仍灌回 ~10MB）
- 结论先行：**防护机制主体存在且已接线，但存在 4 个结构性缺口**：① ReadTool 豁免守卫（`maxResultSizeChars = Int.MaxValue`）② 单级压缩的 compact turn 必须带全量历史 → 超限时压缩数学上不可行 ③ ToolResultTtl 默认关闭 + FastMicroCompact 仅冷缓存触发 → 活跃会话无主动剪枝 ④ deleteSession 只删磁盘文件，不杀 team 成员 actor、不清 TeamSessionRegistry 映射、不清 tool-results 目录 → 内存 10MB 被下一次 persist 原样写回。

---

## 1. 现有保护机制盘点

### 1.1 机制总表

| # | 机制 | 位置 | 存在? | 接线? | 生效条件 / 覆盖范围 | 大小上限 |
|---|------|------|-------|-------|---------------------|---------|
| M1 | ToolResultGuard 单结果守卫 | `core/tools/ToolResultGuard.scala:42` `guardResult` | ✅ | ✅ `AgentCore.scala:962` | 结果 > min(工具声明上限, 50_000) → **落盘** + 2K 预览替换 LLM 可见内容 | 通用 50_000 chars；Bash 30_000；Grep 20_000；**Read = ∞（跳过）** |
| M2 | ToolResultGuard 批量预算 | `ToolResultGuard.scala:65` `guardBatch` | ✅ | ✅ `AgentCore.scala:965` | 单轮全部结果合计 > 200_000 chars → 最大的若干落盘直到达标（`results.size<=1` 时跳过） | 200_000 chars/轮 |
| M3 | 落盘位置 + 摘要回注 | `ToolResultGuard.scala:27-33` | ✅ | ✅ | `~/.nebflow/tool-results/{sessionId}/{toolUseId}.txt`；LLM 可见内容 = `<persisted-output>` 头 + 路径 + 2K 预览；全文保留在 `frontendContent`（仅 WS 展示） | — |
| M4 | FastMicroCompact 规则压缩 | `core/compact/FastMicroCompact.scala:59` | ✅ | ✅ `AgentCore.scala:447` | 仅冷缓存（距最后 assistant 消息 > 30min）；保最近 5 条；省不足 40% 跳过；覆盖 Read/Bash/Grep/Glob/WebSearch/WebFetch/Curl/Edit/Write（**不含 Mail/SubTask/Delegate/Flow**） | 直接改写 state.messages（落盘历史） |
| M5 | ToolResultTtl 请求级清理 | `core/compact/ToolResultTtl.scala:57` | ✅ | ✅ `AgentCore.scala:650-653` | **默认关闭**（`toolResultTtl.enabled=false`，用户裁定）；REQUEST-ONLY 不碰会话文件；TTL 60min / keepRecent 5 / minChars 2000；同 M4 工具集 | 仅请求副本 |
| M6 | 单级 LLM 压缩（compact turn） | `AgentCore.scala:296-334` `startDirectCompaction` | ✅ | ✅ | 阈值：contextWindow>300K 固定 256K，否则 80%（`CompactThreshold.scala:30`）；compact turn **跳过 M4/M5**（"需要全量输入"）→ 把全部历史 + reminder 发给 LLM 求摘要 | 无大小剪枝 |
| M7 | P0-2 紧急硬守卫 | `AgentCore.scala:93-112,190-236` `runEmergencyCompact` | ✅ | ✅ | 估算/上报 > 0.95×contextWindow（1M 窗口 → 950K）且冷却 ≥60s；`CompactUtils.emergencyClean` 三阶段：全量 tool result 剥成占位符 → 删旧结果对 → 留最后 20 条 | 全量剥除（核选项，无差别） |
| M8 | 压缩失败熔断 | `CompactConfig.scala`；`AgentActor.scala:2238-2244` | ✅ | ✅ | 失败 3 次 → circuitBreakerOpen → 转 M7；退避 30s×2^(n-1) | — |
| M9 | 请求级预检/预裁剪 | `llm/providers/AnthropicAdapter.scala` | ❌ | ❌ | adapter 只做协议级清理（pairToolResults/mergeConsecutive），**无大小预检**——超限请求原样发出，由 provider 拒绝 | 无 |
| M10 | deleteSession 清理 | `gateway/SessionStore.scala:816-858` | ⚠️ 部分 | ✅ | 删 `<id>.json`/`.ui.json`/meta sidecar/tasks/uploads + 停 **root** agent（`WebSocketRoutes.removeRootAgent:307`）| **不清 tool-results 目录、不清 TeamSessionRegistry 内存映射、不停 team 成员 actor** |

### 1.2 关键确认（回答用户记忆中的三个疑问）

**① 压缩流程是否对超大 tool results 做剔除/截断？——否，且比记忆更糟。**
- 「压缩 v3.1 两阶段模型（save turn 整理记忆→compact turn 零改动）」**已不存在**：`d70f66b9`（two-stage v3.1）→ `a4eef9a6`（2026-08-31 memory-redesign，改单级）。当前代码 `AgentCore.scala:296-299` 明确「Single-stage compaction — Memory maintenance is OUT of the compaction round」。
- 单级 compact turn 显式跳过 FastMicroCompact 与 ToolResultTtl（`AgentCore.scala:445-447, 651-653`：「Compact/ask turns skip FastMicroCompact: the agent needs the full history for the summary」）→ **大结果在压缩路径上零剔除**。代码注释自己承认该路径「数学上不可行」（`AgentCore.scala:84-92`：save/compact turn 都要带全量消息再调一次 LLM）。
- 唯一兜底是 M7 紧急清理——但它**无差别剥除全部 tool result**（含小结果），破坏信息密度，是核选项而非精准剔除。

**② 工具结果是否有大小上限？超限截断还是落盘？——有，落盘+摘要（M1/M2/M3），但 Read 豁免。**
- 通用上限 50K chars（`Defaults.scala:127` `DefaultMaxResultSizeChars`），Bash 30K（`BashTool.scala:17`），Grep 20K（`GrepTool.scala:13`）。
- 超限 → **落盘** `~/.nebflow/tool-results/{sessionId}/{toolUseId}.txt` + LLM 可见内容替换为 2K 预览 + 路径提示；`frontendContent` 保留全文仅供 WS 展示（`ToolResultGuard.scala:141-145`）；会话文件存预览（`AgentActor.scala:1804` `ContentBlock.ToolResult(call.id, r.content, ...)`）。
- **缺口：`ReadTool.maxResultSizeChars = Int.MaxValue`（`ReadTool.scala:35`）→ 单结果守卫整段跳过**（`ToolResultGuard.scala:51` `if declaredMax == Int.MaxValue then IO.pure(result)`）。ReadTool 内部上限 512KB 文件 / 2000 行（`ReadTool.scala:13-14`），但显式大 limit 时单次可回 ~512KB ≈ 128K tokens 进上下文。

**③ 「大结果落盘 + 摘要回注，agent 自行 Read」机制是否真实存在并生效？——存在、已接线，但被 Read 豁免反噬。**
- M3 正是该设计，且是唯一生效的写路径防护。
- **反噬路径**：守卫落盘后，预览文本指示「Full output saved to: $path」→ agent 按提示 Read 该 .txt → ReadTool 豁免守卫 → 全文经 Read 回到上下文。守卫自己制造了最大的膨胀源。

### 1.3 写路径防护有效性结论

| 工具结果来源 | 单结果守卫 | 批量守卫 | FastMicroCompact | TTL | 结论 |
|---|---|---|---|---|---|
| Read（含回读 tool-results 文件） | ❌ 豁免 | ✅（>200K/轮时） | ✅（仅冷缓存） | 默认关 | **主膨胀源** |
| Bash / Grep / Curl / WebSearch / WebFetch | ✅ | ✅ | ✅ | 默认关 | 受控 |
| Mail / SubTask / Delegate / Flow | ✅（默认 50K） | ✅ | ❌ 不在工具集 | 默认关 | 受控但无法老化剪枝 |
| 压缩轮自身 | — | — | 显式跳过 | 显式跳过 | **超限即死锁** |

---

## 2. 膨胀路径：3.26M tokens 怎么涨出来的

量级核对：~10MB 历史 ≈ 3.26M tokens（4 chars/token 估算，代码/日志混合），与用户观测一致——**整个会话历史文件就是膨胀本体**。

### 2.1 主路径：ReadTool 豁免 + 回读落盘文件（估计贡献 >80%）

1. 活跃会话（qa-backend 持续收到 Mail/任务，缓存热）→ FastMicroCompact 的 30min 冷缓存条件**永不满足**（`FastMicroCompact.scala:70`）。
2. ToolResultTtl 默认关 → 无请求级老化。
3. 每次大 Read（显式大 limit / 长行文件，单次 ≤512KB ≈ 128K tokens）**全量进上下文并持久化**（豁免，`ToolResultGuard.scala:51` 短路）。
4. M3 落盘的大 Bash 输出被 agent 按预览提示 Read 回读 → 同一份内容第二次经豁免路径进入。
5. 单轮多条大 Read 合计超 200K 时批量守卫会兜底，但**单条大 Read 独占一轮（常见：`Read` 一个大文件后思考）完全无防护**。
6. 累积 ~25-30 次大 Read ≈ 3.2M tokens，期间压缩阈值 256K（`CompactThreshold.scala:9`）早该触发——但见 2.2，压缩在此量级上失败。

### 2.2 压缩死锁路径（为什么「压缩无效」）

```
上下文 ~256K → 触发单级压缩（M6）
  → compact turn 携带全量历史（跳过 M4/M5 剪枝）
  → 若此刻历史 > 1M（如某轮并行大 Read 直接灌爆）→ provider 拒绝 compact 请求
  → compactionFailures++、lastCompactionFailureAt=now（AgentActor:2243-2244）
  → 退避 30s/60s/120s……期间每次正常 dispatch：
       P0-2 冷却（60s）未满足 → 硬守卫跳过（AgentCore:100-112）
       → 全量 3.26M 请求发出 → provider 拒绝 → LlmFailed
  → 60s 后 P0-2 终于触发 emergencyClean → 剥除全部 tool result → 应可自愈
```
实际观测「restart 无效」的原因：restart → actor 重建 → 从 `<id>.json` 重载 10MB（`AgentActor.scala:2588` `restartStateFor(Full)`；FlowTreeActor `loadMessagesForSession`）→ 上下文再次 3.26M → 重演上述循环；若 P0-2 的 LLM 调用也失败（如模型侧限流），`lastCompactionFailureAt` 被刷新 → 冷却永不满足 → **永久死锁**。

### 2.3 次路径：无预检的请求

`AnthropicAdapter.scala:39-101` 无任何大小预检/预裁剪——超限请求原样出网。`requested 3.26M tokens > 1M limit` 是 provider 侧拒绝，Nebflow 无前置拦截、无降级（如自动转 emergencyClean）。

---

## 3. deleteSession 后历史灌回：根因确认

**根因：deleteSession 只清理磁盘与 root agent，team 成员 agent（qa-backend 属 team 成员）的三处状态全部存活。**

| 状态载体 | deleteSession 是否清理 | 证据 |
|---|---|---|
| `<id>.json` / `.ui.json` / meta sidecar / tasks / uploads | ✅ 删除 | `SessionStore.scala:840-853` |
| root AgentActor | ✅ Stop | `WebSocketRoutes.scala:307-319` `removeRootAgent`（仅 `rootAgents` map） |
| **team 成员 AgentActor（qa-backend）** | ❌ **存活**（注册于 `TeamSessionRegistry.actorMap`，`FlowTreeActor.scala:37,55-79`） | deleteSession 链路不触碰 |
| **TeamSessionRegistry 内存映射**（instance/agent → sessionId） | ❌ **保留** | `FlowTreeActor.scala:35,47-48`；仅 unmount/cleanup 清除（:457-469, :510-530） |
| **`~/.nebflow/tool-results/{sessionId}/`** | ❌ **保留** | `SessionStore.deleteSession` 未列 |
| 存活 actor 的 `state.messages`（~10MB） | ❌ **保留** | actor 未停 |

**灌回时序（~12s）**：deleteSession 删文件 → qa-backend actor 仍持 ~10MB `state.messages` → 下一次活动（Mail 投递 / flow dispatch / 定时触发）→ `ToolsComplete`/`persistIfSession`（`AgentCore.scala:66-69, 1935`）→ `saveMessagesForSession(sid, ~10MB)`（2s 防抖，`SessionStore.scala:472-487, 1378-1416`）→ `<id>.json` 以 10MB 重建 → 前端仍持有该 sessionId 的视图重拉历史 → **~10MB 灌回**。

**restart 无效的叠加**：下次启动 `loadFromIndex` → `recoverOrphans`（`SessionStore.scala:293-325`）把重建的 UUID 文件当孤儿收编回 index → flow 重挂 team（`createSingleTeamSession` → `findSessionByName`，`FlowTreeActor.scala:492-501`）→ 沿用同一 sessionId → agent 重载 10MB → 死锁复现。

---

## 4. 分层完善方案（最小改动，不破坏流式与工具语义）

设计原则：**写路径上限（防新增）→ 压缩路径精准剔除（防积压）→ 删除路径闭环（防复活）**，三层各自可独立上线、独立验收。

### Layer A — 写路径：堵住 Read 豁免（最高优先，改动最小）

| 项 | 内容 |
|---|---|
| 改动 1 | `ReadTool.scala:35`：`maxResultSizeChars` 由 `Int.MaxValue` 改为 `Defaults.DefaultMaxResultSizeChars`（50K），并移除注释「exempt from guard」。ReadTool 内部 512KB/2000 行上限保留（那是工具自身安全网，与守卫互补）。 |
| 改动 2 | `ToolResultGuard.scala:51`：`Int.MaxValue` 语义改为「使用 DefaultMaxResultSizeChars」而非跳过（防御未来再有工具声明 ∞）。 |
| 改动 3（可选） | `ToolResultGuard.persistAndReplace`：落盘后路径提示追加「如确需全文请 Read 本文件」→ 改为「如需全文请 Read，注意单次最多 2000 行/50K 字符，必要时用 offset/limit 分段」——阻断「回读全文」反噬。 |
| 涉及文件 | `core/tools/ReadTool.scala`、`core/tools/ToolResultGuard.scala` |
| 改动量 | ~5 行 + 注释 |
| 验收点 | ① Read 一个 >50K 字符的文件（`limit` 足够大）：LLM 可见内容以 `<persisted-output>` 开头、含落盘路径，会话文件（`<id>.json`）中该 ToolResult content ≤ 2.2K；② 落盘文件存在且为全文；③ 既有 Read 正常语义不变（≤50K 不触发）；④ 相关 spec（若存在）同步更新。 |

### Layer B — 压缩路径：大结果精准剔除（防积压死锁）

| 项 | 内容 |
|---|---|
| 改动 1 | 新增纯函数 `CompactUtils.stripOversizedToolResults(messages, maxChars)`（镜像 `emergencyClean` 的 phase-1 但只处理超限结果、保留小结果与结构）：对 ToolResult content > maxChars（建议 50K）替换为占位符 + 落盘路径提示。落盘已有 M3 保证（若写路径未落盘则在此处补落盘）。 |
| 改动 2 | 单级压缩入口 `AgentCore.startDirectCompaction`（:296-334）：构造 `firstState` 时对 `state.messages` 先跑 stripOversizedToolResults 再追加 reminder——**压缩轮只需「全文概貌 + 大结果路径引用」，不需要大结果本体**；摘要完成后 `FullCompact.parseResponse` 可据路径恢复关键内容（复用现有 `restoreFileContent`，`CompactUtils.scala:201`）。 |
| 改动 3（可选） | `FastMicroCompact` 工具集加入 Mail/SubTask/Delegate/Flow（占位符语义改为「结果已归档，可重发 Mail 查询」），或单独为这类结果加 TTL 老化。 |
| 涉及文件 | `core/compact/CompactUtils.scala`、`agent/AgentCore.scala`（`startDirectCompaction`）、可选 `core/compact/FastMicroCompact.scala` |
| 改动量 | 新函数 ~40 行 + 调用点 ~10 行 |
| 验收点 | ① 构造一个含单条 300K 结果的 messages → 压缩轮发出的请求中该结果被替换为占位符+路径，请求体 < 100K；② 压缩摘要正常生成、`pendingCompaction` 正常清除；③ 小结果（<50K）原样保留；④ 压缩成功后历史恢复流程（postCompact 文件恢复）仍工作；⑤ 既有 spec 中「compact turn 全量输入」的假设若存在则同步修订。 |

### Layer C — 删除路径：deleteSession 闭环（防复活）

| 项 | 内容 |
|---|---|
| 改动 1 | `SessionStore.deleteSession`（:816-858）追加：删除 `PathUtil.dataRoot / "tool-results" / id`（递归）。 |
| 改动 2 | `WebSocketRoutes` deleteSession / batchDeleteSessions 处理器：删除前对**该 sessionId 对应的 team 成员 actor**（`TeamSessionRegistry.getRunningActor(sid)`）发 `AgentCommand.Stop`，并 `TeamSessionRegistry.unregisterActor(sid, resources)`；root 路径已有 `removeRootAgent`。 |
| 改动 3 | `SessionStore.deleteSession` 追加清 `TurnStateStore`（若该 session 有 in-progress 记录，否则重启后 `restoreInterruptedTurns` 会用旧 turnState 复活流程）。 |
| 改动 4（可选，根治） | `TeamSessionRegistry.registerActor` 注册的 actor 在 session 被外部删除时，由 FlowTreeActor 的 session 生命周期钩子（`setSessionChangedHook`，`SessionStore.scala:137`）通知 unregister；或 deleteSession 时同步 `sessionMap` 中该 sid 的条目。 |
| 涉及文件 | `gateway/SessionStore.scala`、`gateway/WebSocketRoutes.scala`、`core/flow/FlowTreeActor.scala`（TeamSessionRegistry）、`agent/protocol.scala` |
| 改动量 | 每处 3-10 行 |
| 验收点 | ① deleteSession 后 tool-results 目录消失；② qa-backend actor 收到 Stop、TeamSessionRegistry 无残留映射；③ 删除后等待 >30s，`<id>.json` 不重建、session 列表不复活；④ 重启后 `recoverOrphans` 不再收编该 session；⑤ 删除 root session 与 team session 两条路径行为一致。 |

### 推荐落地顺序

**A（堵写路径，半小时级）→ B（解压缩死锁，核心）→ C（删删除复活，收尾）**。A 单独上线即可显著降低新膨胀速率；B 解决存量死锁的自我修复能力；C 解决用户「删不掉」的操作层问题。

### 不改动的部分（避免破坏现有语义）

- M1/M2/M3 的落盘+预览机制本身不动（已是最佳实践的形态，仅补 Read 豁免）。
- 单级压缩的产物形态（summary 替换历史）不动——只改「喂给压缩轮的输入」。
- adapter 层不做请求裁剪（动协议层风险大）；预检可考虑只在 provider 拒绝后做降级（见 Layer B 兜底）。

---

## 5. 附：关键文件索引

| 文件 | 关键行 | 角色 |
|---|---|---|
| `core/tools/ToolResultGuard.scala` | :42-55, :65-107, :113-148 | 写路径守卫（单结果+批量+落盘） |
| `core/tools/ReadTool.scala` | :13-14, :35, :147-210 | Read 上限与豁免 |
| `core/compact/FastMicroCompact.scala` | :26-49, :59-118 | 冷缓存规则压缩 |
| `core/compact/ToolResultTtl.scala` | :33-123, :129-163 | 请求级 TTL（默认关） |
| `core/compact/CompactUtils.scala` | :27-74 | 紧急清理（无差别剥除） |
| `agent/AgentCore.scala` | :71-112, :190-236, :296-334, :424-459, :645-665 | 压缩触发/执行/请求构建 |
| `agent/AgentActor.scala` | :1796-1806, :2238-2244, :2580-2596 | ToolsComplete 写消息、压缩失败、Full restart 重载 |
| `gateway/SessionStore.scala` | :816-858, :387-411, :293-325 | deleteSession、消息读写、孤儿恢复 |
| `core/flow/FlowTreeActor.scala` | :32-130, :471-508, :646-678 | TeamSessionRegistry、team 会话创建、崩溃恢复 |
| `gateway/WebSocketRoutes.scala` | :305-319, :1535-1583 | removeRootAgent、deleteSession 路由 |
| `shared/Defaults.scala` | :124-137 | 50K / 200K / 2K 常量 |
