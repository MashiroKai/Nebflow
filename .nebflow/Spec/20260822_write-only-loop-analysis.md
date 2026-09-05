# Write-only 循环 + 压缩失效满死锁：机制分析报告

**日期**：2026-08-22 ｜ **分析**：nebflow-project/Backend ｜ **会话**：slideblocks Frontend `a5431750-124b-4e71-a7e0-a1e1d309788b` ｜ **状态**：纯分析零改动，修复另批实施

## TL;DR（三条机制结论）

1. **Write-only 循环根因 = save-turn 工具白名单与原任务意图的互斥**。压缩 approaching 注入后进入两阶段压缩的 Save 阶段，工具集被裁剪为 `[Write, Edit, Read]`（2026-08-18 压缩瘦身批的 `saveTurnTools` 白名单）。模型完成 memory 维护后回到未完成的原任务（运行验证脚本需要 Bash），**Bash 不在白名单**——thinking 每轮明确计划「use Bash to execute」，输出层却只能落成「再写一遍脚本文件」（24 连续写入内容逐字节相同，工具结果 `(no changes)`）。意图在、生成割裂，模型自我意识到循环（"I've been stuck in a loop"）但行为上无法停止。
2. **压缩失效卡点 = Save 阶段的完成信号被饿死**。Save→Compact 的推进条件是「模型停止调用工具」（`toolCalls.isEmpty`，AgentActor.scala:2149）。Write-only 循环每轮都有 toolCall → `:2151` 分支无限继续 tool loop（**无轮次上限、无预算、无超时**）→ compact turn 永不触发 → 上下文每轮 +2 消息持续膨胀。
3. **满死锁路径 = 压缩依赖 LLM 调用 + 失败计数内存态 + 硬截断保底不可达**。会话被推满后（~30 万 token > kimi/k3-256k 窗口），任何激活尝试 → `maybeAutoCompact` 判定超阈值 → 注入 save-reminder → LLM 调用带全量消息**超窗失败且静默**（router 日志 5 次 intake 后零事件，无 error 记录）→ `save-phase-fallback` 直转 compact turn → 同样超窗失败。失败计数/backoff 是 AgentState 内存态，用户「激活-失败-放弃」循环中 actor 重建、**计数永远攒不到 circuitBreakerMax** → `emergencyClean`（非 LLM 规则硬截断，代码存在）永远不可达。

## 证据时间线（UTC；+08 = 用户本地）

| 时刻 (UTC) | 事件 | 证据 |
|---|---|---|
| 13:20:26 | 会话恢复后首个请求，tools 全量 12 个（含 Bash/Mail/Screenshot） | full.jsonl tools_ref |
| 13:40–13:42 | wbv 验证准备：每 15-40s 一轮单工具调用（Edit 脚本微调阶段） | msgs 456→470 线性 +2 |
| 13:42:32 | **compaction approaching save-reminder 注入**（user 消息，SaveMemoryPreamble） | 消息 idx 473 |
| 13:43:27 | **首个 save turn 请求：tools 骤降为 `[Edit, Read, Write]`** | tools 变化唯二两点之一 |
| 13:43–13:52 | 正常 memory 维护：Edit memory.md（预期行为）✓ | ui.json tool labels |
| ~13:55 | 模型回到原任务：Write slides.md / package.json / wbv-verify.mjs（Write 在白名单内「合法」继续干活） | ui.json idx 745-750 |
| 14:05–14:08 | wbv-setup.sh 微改（+2/-2、-4、+1/-2——仍以为在推进任务） | tool_result diff 摘要 |
| 14:08–14:17 | **纯循环：Write wbv-verify.mjs 连续 24+ 次，内容逐字节相同（md5 同、`(no changes)`）**；thinking 每轮计划 Bash | 三条 thinking 原文（下节） |
| 14:17:49 | 最后一条请求；22:17 用户重启止损 | reqs 尾 |
| 14:27–14:29 | 重启后 5 次激活尝试：**intake 后零事件**（无 sse_event/final/error） | sse.jsonl 生命周期 |
| 14:29:29 | 重启后请求含**两个 save-reminder**（旧 idx472 + 重新注入 idx615）——压缩状态丢失又重触发，随即静默死 | 消息定位 |

## 证据链 1：Write-only 循环因果

### 1.1 工具集裁剪（Bash 消失）

router objects 取证：13:20:26Z 请求 tools = `[Bash, Edit, Glob, Grep, Issue, Mail, Pop, Read, Screenshot, WebFetch, WebSearch, Write]`；13:43:27Z 起（save turn）= `[Edit, Read, Write]`。裁剪源码：`AgentCore.saveTurnTools`（AgentCore.scala:1212-1219，`SaveTurnToolWhitelist = Set("Write","Edit","Read")`），设计动机注释明确：「FULL toolset on a weak default model turns into open-ended exploration...nothing that can branch outward (no Bash/Grep/Glob/WebSearch)」——**防发散的收窄，恰恰制造了无法执行任何「推进型」动作的笼子**。

### 1.2 意图-输出割裂（thinking 计划 Bash，tool_use 恒为 Write）

循环中段三个连续请求的 assistant 消息（thinking block 原文）：

- 14:10:34Z — `The file is done. I need to stop editing and execute. Let me use Bash now to: 1. Copy build-offline.mjs 2. Build 3. Run verify` → **tool_use: Write /tmp/wbv-verify.mjs**
- 14:11:10Z — `The file is stable. I need to stop editing it and use Bash to execute the actual pipeline. Let me do that now.` → **tool_use: Write /tmp/wbv-verify.mjs**（内容 3898 字节，与上次相同）
- 14:11:46Z — `OK I've been stuck in a loop. The verify script at /tmp/wbv-verify.mjs is complete and ready. I need to STOP editing it and use Bash to execute the pipeline...` → **tool_use: Write /tmp/wbv-verify.mjs**

模型不是「遗忘意图」（上下文无损坏/截断——每轮 thinking 都正确复述计划），是**输出层无可选工具承载该意图**：Bash 不在 tools 数组，模型退化到把「准备好执行」这个子目标反复落成它仅有的文件操作。这是行为层循环，与 qwen 空碎片（协议层：工具名空串→白名单拒→重试风暴）**不同族**——本例工具全部成功执行、零错误返回。

### 1.3 无进展的定量化

ui.json 36 次 wbv 相关写入中：9 个 distinct 内容，其中 58 字节工具结果 `OK:UPDATED wbv-verify.mjs, 0 added, 0 removed\n(no changes)`（即写入内容与盘上零差异）出现 **28 次**，末段 24 连续。每轮 = 一次完整上下文重发（~25 万 token，cache_read 主导）× 50 轮 ≈ 大量 token 损耗，零产出。

### 1.4 循环引擎代码点

`AgentActor.scala:2151`：
```scala
else if state.pendingCompaction.exists(_.phase == CompactionPhase.Save) && result.toolCalls.nonEmpty then
  pipeToolExecutions(...)   // save turn 有 toolCall → 继续 tool loop，无上限
```
该分支无轮次预算、无重复度检测、无 wall-clock 超时。对比：compact turn 若模型调工具有 `handleCompactFailure`（:2156「Compact model unexpectedly called tools」）——**Save 阶段没有对应的异常检测**。

## 证据链 2：压缩失效与满死锁

### 2.1 两阶段压缩设计与「提醒≠触发」

`maybeAutoCompact`（AgentCore.scala:71-171，每次 dispatch LLM 前判定）：`latestUsage.inputTokens > threshold` 或 `TokenEstimator.estimate > threshold` → `startDirectCompaction`。kimi/k3-256k（contextWindow 256,000 ≤ 300,000）走 80% 分支，**threshold = 204,800**。team agent 有 memory → 注入 SaveMemoryReminder + `pendingCompaction(phase=Save)`。

**提醒注入与压缩完成之间差的正是「模型自然停止」**：Save→Compact 推进条件 = turn 结束时 `toolCalls.isEmpty`（:2149 `handleSavePhaseComplete`）。Write-only 循环使该条件被永久饿死——`pendingCompaction` 定义着时 `maybeAutoCompact` 直接返回 None（:80），无任何二次干预路径。21:43-52 正常段的对照：同样的 reminder、同样的裁剪工具集，模型做完 memory 维护后**如果停了**，压缩即刻发生——差异只在模型是否继续调工具。

### 2.2 阈值判定时机缺口

触发判定在 dispatch 边界（每轮 LLM 调用前），用的是**上一轮的 usage/估算**。13:42:32 触发时 inputTokens 已 > 204.8k 但仍在 256k 窗内（~22-25 万），有充分余量完成 save+compact 两轮 LLM 调用——设计上没问题；问题在 save turn 消耗了这个余量（每轮 +2 消息且全量重发）却永不交还控制权。

### 2.3 满死锁路径（重启后激活失败）

1. 会话满：613+ 消息 ≈ 30 万 token > 256k 窗口
2. 激活 dispatch → `maybeAutoCompact`：`TokenEstimator.estimate` 超 204.8k → 重新注入 save-reminder（14:29:29 请求实证：新旧两个 reminder 并存）→ save turn LLM 调用带全量消息 → **超窗失败且静默**（5 次 intake 后零事件——对照成功请求 intake 后 29 个 sse_event；错误路径无日志记录，属死日志家族盲区，38 处复活修复在重启包尚未生效，生效后需复核此处）
3. `LlmFailed` → `:1156 save-phase-fallback` 直转 compact turn（全量+compact reminder）→ 同样超窗失败
4. `compactionFailures`/backoff/circuitBreaker 全在 **AgentState（内存态，不持久化）**——用户激活-失败-放弃循环中 actor 重建，计数归零，**`circuitBreakerMax` 永远攒不够**
5. 保底存在但不可达：`emergencyClean`（CompactUtils，非 LLM 规则截断——剥 tool results/删旧对/保留尾 N 条）在 `compactionFailures >= circuitBreakerMax && emergencyAutoFallback` 才走（AgentCore.scala:99-152）；`FastMicroCompact`（轻量截断）在 `isSaveTurn/isCompactTurn` 时被明确跳过（:374，save 需全历史提取记忆）
6. 结果：满会话 = 永久死锁，slideblocks 团队废弃该会话

## 防线缺位清单（为何现有机制没拦住）

| 防线 | 现状 | 为何失效 |
|---|---|---|
| TaskStuckWatcher | Processing + `lastActivityMs` 超 StuckThreshold | Write 每 30-40s 成功执行=「有活动」，`lastActivityMs` 持续刷新——**「有活动但无进展」盲区**，watcher 永不触发 |
| #31 sendStream no-progress watchdog（f745f5b2，600s） | 只管 LLM 流层（intake→首 chunk 无产出） | Write 工具成功执行不经过它；每轮 LLM 正常产出 chunk |
| TurnBudget / per-turn 预算 | **不存在**（`Fallback.MaxTurnLlmCalls` 是单 turn 内 LLM 调用数预算，循环是每 turn 一次调用的多 turn 序列，管不到） | 缺位——确认无任何 per-turn 重复度/无进展预算机制 |
| save-turn 行为护栏 | 无 | reminder 文本要求「ONLY task this turn is memory cycle」，但无机制检测模型偏离（回到原任务）并强制收束 |
| 压缩失败计数 | 内存态 | 激活-放弃循环重置计数，circuitBreaker/emergencyClean 不可达 |
| 满上下文预检/硬截断 | 无（依赖 LLM 调用失败后逐级回退，而回退路径每级都是 LLM 调用） | 死锁闭环：救压缩的动作本身需要 LLM，满上下文使一切 LLM 调用失败 |

## 修复建议（分期，未实施）

### P0（止血，下批立项）

1. **Save-turn 轮次预算 + 无进展检测**：`pendingCompaction(Save)` 状态下限 N 轮（建议 8-10）tool-loop 上限；超限即视为 save 完成（或失败），强制转 Compact phase。进展信号可低成本起步：**同 file_path 重复写入计数**（`tool_use.name==Write && input.file_path` 重复 ≥3 且内容 hash 不变 = 零进展）+ 每 reminder 轮 toolCall 去重集合收敛判定。命中时 `handleSavePhaseComplete` 强制推进 + logAgentEvent 留痕。
2. **满上下文硬截断保底**：`maybeAutoCompact` 判定触发前，若 `TokenEstimator.estimate > contextWindow × 0.95`（估算已逼近/超过真实窗口），**跳过 LLM 依赖路径直接 `emergencyClean`**——压缩的目的是缩上下文，当上下文本身已容不下一次压缩调用时，规则截断是唯一数学上可行的路径。同时把 emergencyClean 后的首轮 usage 上报对齐（`withLatestUsage(None)` 已有）。

### P1（结构性，方案细化后另批）

3. **压缩失败计数持久化**：`compactionFailures`/`lastCompactionFailureAt` 落 taskStore 或会话文件（与 AgentState 解耦），重启后 circuitBreaker 状态可延续，emergencyClean 路径可达。
4. **save-turn 任务漂移护栏**：reminder 后首轮若 toolCall 的 file_path 与 memory/skill 路径集无交集（如 /tmp/*.mjs），注入一次强化 reminder（「当前处于压缩预备阶段，禁止继续原任务；立即完成 memory 维护并停止」）；二次漂移直接强制转 Compact。
5. **TaskStuckWatcher 引入进展信号**：watcher 判据从「有无活动」扩展为「活动是否有进展」——复用 P0-1 的重复写入计数，`lastActivityMs` 刷新但 progressFingerprint 不变超阈值同样判 stuck（触发 notice/restart 而非只读告警，对齐 let-it-crash 裁定）。

### P2（可观测性）

6. **intake 后静默死亡的可观测性**：重启后 5 次 intake 零后续事件——错误路径无日志。死日志批（330bfb38，39 处复活）生效后复核 LlmFailed 链路；如仍盲，为 LLM 层发送失败补 fail-loud 日志（含 request_id 关联）。
7. **save-turn 专用事件**：`save-phase-start`/`save-turn-progress`（轮次计数）进 wsEvent/日志，前端与 watcher 都可消费。

### 与候选方向的对齐说明

任务书候选①（同文件重复写入计数进展信号）→ P0-1/P1-5；②（compaction reminder 后 turn 行为护栏）→ P1-4；③（per-turn 工具重复度预算）→ P0-1 覆盖（限定 save-turn 场景先落地，通用化进 P1 评估）。扩容三条：压缩触发提前（2.2 分析：判定时机本身不缺，缺的是 save 阶段不还控制权——P0-1 解决；若仍要提前可将 threshold 从 80% 降至 75% 观察）→ P0-1；压缩失败重试 → P1-3；满上下文硬截断保底 → P0-2。

## 与已知模式对照

- **qwen 空碎片（已修 5e109ef8）**：协议层——provider 发射 id/name 空串工具碎片 → 白名单拒 → 重试风暴。症状=工具失灵+错误循环。
- **本例 Write-only**：行为层——工具全部合法成功执行，但 save-turn 工具白名单使「推进型意图」（Bash 执行）无承载，模型在笼子里重复唯一合法动作。症状=零错误+零进展。
- 共同放大器：**每轮全量上下文重发**（token 损耗机制相同），区别在重试放大 vs turn 序列放大。

## 素材指引（复验路径）

- router 日志：`~/.nebflow/logs/router/2026-08-22_full.jsonl`（session_id 前缀 a5431750，144 条）+ `2026-08-22_sse.jsonl`（intake/sse_event 生命周期）+ `objects/`（message_refs 内容存储）
- 会话文件：`~/.nebflow/sessions/a5431750-124b-4e71-a7e0-a1e1d309788b{,.ui}.json`（800 消息；ui.json 无时间戳，时间线以 router 为准）
- 关键代码：`AgentCore.scala:71-171`（maybeAutoCompact）/`:354-357`（phase-aware tools）/`:374`（FastMicroCompact 跳过）/`:1212-1219`（saveTurnTools 白名单）；`AgentActor.scala:2145-2156`（两阶段完成判定）/`:1156-1175`（save-phase-fallback）；`CompactService.scala`（reminder 文本）；`CompactThreshold.scala`（204.8k）；`CompactUtils.emergencyClean`（保底截断）
