# loop-detected 系统性诱因分析（2026-08-28 十起事件）

- **日期**：2026-08-28 完成（分析）/ 数据采集截至 2026-08-29 00:00
- **性质**：只分析不改码。所有结论均给出代码位置与日志证据。
- **数据源**：`~/.nebflow/sessions/`（会话 JSON）、`~/.nebflow/logs/nebflow.log`（当日 28396 行）、`~/.nebflow/logs/router/2026-08-28_*.jsonl`、Nebflow 源码（worktree 主目录）。

---

## 0. 结论速览（先读这里）

1. **「61 轮」不是巧合，是机制值**：LoopGuard S2 工具轮预算 `maxToolRoundsPerTurn=60`，判定条件 `roundCount > 60`，即第 **61** 个非进展轮触发 L1 Terminate。警告在 70%（42 轮）触发。08-28 全部 10 起 loop-detected 签名逐字相同：`tool-round budget exceeded (61 rounds with no structural progress`。
2. **「Processing stuck 28m43s」是确定性 bug**：LlmFailed fatal 路径（loop-detected 走的路）**遗漏 agentRegistry 状态回写**——actor 行为已回 idle，registry 永远停在 `Processing` + 停摆的 `lastActivityMs`。TaskStuckWatcher（10 分钟阈值、30 秒节奏）对 Team 类只读告警、绝不自动停，于是每 30 秒告警一次直到人工 restart。
3. **「restart 后空白会话」是语义错位**：Team 成员 restart = Stop → 按持久化 history 重建 actor → **只有 mail-queue.json 非空才续跑**。两会话 queue 均为 `[]`，所以 restart 后 turn 不恢复、actor 直接 Idle。叠加 full compaction 已把会话文件重写为单条摘要（preservedRounds=0），视觉上就是「全新空白 / never active」。
4. **诱发的根因（为什么合法任务会撞上 61）**：S2 的「进展」定义 = 仅成功的 Edit/Write/TaskUpdate/TaskCreate/TeamTask*。**Bash/Read/Grep 的成功不算进展**。QA 验证、真链 E2E、修复批的测试/变异验证——主体恰恰是 Bash+Read。LoopGuard 2026-08-27 上线，08-28 首日即 10 起，全部 S2、零起 S1/S3——守卫把「高活动高产出的验证工作」误判为「高活动零进展」。
5. **LLM 全程无辜**：router 日志证实两起坠毁前每一轮请求 200 OK、输出正常；guard 在工具轮评估阶段杀掉的是健康的 turn。

---

## 1. 当日全景时间线（10 起，非 2 起）

`nebflow.log` 中 `event=llm-fail detail=err=loop-detected: tool-round budget exceeded (61 rounds...` 共 10 条：

| # | 时刻 (CST) | Agent | 会话 | 后续 |
|---|---|---|---|---|
| 1 | 01:59:09 | html-deck-studio/html-builder | 52ad5890 | 02:01:46 Manager restart |
| 2 | 02:00:23 | slideblocks/Frontend | a5431750 | 02:01:46 restart（同 audit 行） |
| 3 | 02:58:11 | nebflow-project/Backend | 676780c5 | （12:18 再次坠毁） |
| 4 | 10:46:48 | slideblocks/Frontend | a5431750 | 10:56:20 restart「第二次 loop-detected（61轮）」 |
| 5 | 11:53:11 | slideblocks/Frontend | a5431750 | 12:09:43 Nebula restart「stuck 16m21s, loop rounds=61」 |
| 6 | 12:18:48 | nebflow-project/Backend | 676780c5 | — |
| 7 | **13:41:54** | **nebflow-project/qa-frontend** | **5b5d04ec** | 13:52–13:59 watcher 告警 ×13；13:59:20 restart |
| 8 | 20:09:17 | nebflow-project/Frontend | aac1c33a | 20:19:35 起 watcher 告警 |
| 9 | 21:06:45 | html-deck-studio/html-builder | 52ad5890 | — |
| 10 | **23:19:30** | **nebflow-project/Backend** | **676780c5** | 23:29:35 起 watcher 告警；23:48:26 restart「stuck 28m43s」 |

slideblocks/Frontend 一天三连坠（同会话）、html-builder 两连坠——单会话反复坠毁再 restart 再坠毁，说明 restart 没有消除诱因（工作形态不变，重入即再撞墙）。slideblocks Manager 12:10:45 的邮件原话「**checkp remaining=0**」与用户观察「checkpoint 无可恢复内容」互相印证。

**用户给的两个样本指认修正**：`aa15dbcc` 实为 html-deck-studio/content-planner 的会话（全天健康，与本案无关）；qa-frontend 真身是 `5b5d04ec-e1d1-4516-8f3a-0e53ac92efaf`（index + 日志均证实）。其 15:01:12 收到 Manager 重派邮件：「[任务·重派：**前会话 loop-detected 终止**，本任务零残留从头做]」。

---

## 2. 案例逐轮取证

### 2.1 qa-frontend（5b5d04ec，坠毁于 13:41:54）

**会话文件形态**：文件首条消息 = 13:28:09 full compaction 摘要（`Compressed 359 messages into summary`，preservedRounds=0）——坠毁 turn 之前约 2.5 小时的历史被压成单条摘要（原件入 archives 机制，本会话 archives 目录未见落盘产物，见 §5 待取证）。

**摘要内容证实 turn 是正当工作**：Logto AC+PKCE 登录真链 E2E——配置解析取证（NeblinkModel.scala 行级定位）、路由取证（auth/start、callback handler）、Management API 换 token、建 QA 账号、隔离 home + worktree、写 e2e 驱动 `qa-logto-e2e-real.mjs`（R1-R10）。全程是验证型工作，非同参重试循环。

**13:28–13:41 逐轮**（会话 [2]–[49]）：21 个工具轮，其中成功的 Edit 仅 2 个（13:36:37、13:36:55），其余为 Bash（跑测试/渲染截图）、Read（读截图与文件）、Grep。13:32:51 L0 警告 `42 of 60`。此后 19 个非进展轮，13:41:09 最后一个 tool_result 落盘，13:41:53.980 最后一次 LLM 请求正常返回（kimi/k3-256k，1317 输出 tokens，HTTP 200），**0.8 秒后** 13:41:54.763 llm-fail loop-detected——第 61 个非进展轮在工具轮评估中触发 Terminate。

**坠毁后**：
- 13:52:03 起 TaskStuckWatcher 每 30 秒告警：`stuck in Processing for 609s → 1029s`（13:59:03 最后一条），共 13 条。
- 13:59:06 Nebula restart（audit 目标误写成 html-builder 52ad5890，reason 却是 qa-frontend 语境——audit 目标串扰，见 §5）；13:59:20 对 5b5d04ec 的 restart 落地：「新发现卡死：html-deck-studio 成员 stuck 16m52s/loop 61 轮无进展——let-it-crash checkpoint 恢复」（reason 文本同样有串扰，但目标是 5b5d04ec 无误）。
- restart 后 actor 重建为 Idle，**无任何 turn 恢复**（mail-queue.json = `[]`）。会话静默至 15:01:12 Manager 重派。「坠毁前零工件」与重派邮件「零残留从头做」一致：坠毁 turn 只做了验证动作，未产出报告/提交。

### 2.2 Backend（676780c5，坠毁于 23:19:30）

**会话文件形态**：首条 = 22:05:57 full compaction 摘要（`Compressed 426 messages`）。23:00:42 收到新任务邮件「三缺口修复批立项」→ **新 turn 开始，S2 计数清零**。

**23:00:42–23:19:20 逐轮**（[246]–[393]）：约 74 轮。成功 Edit 10 次（Rust store.rs +103 行、routes.rs +28/+10/+4/+15 行、单测 +123 行、Scala NeblinkEnrollmentPersistSpec 等），其余为 cargo check/test、sbt 编译、变异验证（M-R1/M-R2）、读文件。23:12:58 L0 警告 `42 of 60`——agent 在 [352] 明确回应「Loop guard 42/60——Rust 侧完成……」并继续 Scala 侧收口（**警告被读到、被理解、无法在 18 轮内收尾**）。此后 21 轮仅 2 次成功 Edit → 42+19=61，23:19:24.85 最后一次 LLM 正常响应（GLM-5.3-Flash，112 tokens），23:19:30.306 llm-fail loop-detected。坠毁时 Rust 侧已 commit（56121ce）、Scala 侧缺口③单测跑到一半。

**坠毁后**：会话文件最后一条即 23:19:20 的 tool_result；23:29:35 起 watcher 告警（605s→…）；23:48:26 Nebula restart（audit 原文：`loop-detected 0/61 rounds + stuck 28m43s 零活动——今日第四起 loop-detected（与 qa-frontend 61 轮同模式）`）。28m43s = 23:19:30（最后活动）→ 23:48 restart，分钟级吻合。**restart 后 mail-queue 为空 → turn 不恢复**，会话 updatedAt 永远停在 23:19:30，修复批无人续做，直到用户介入。

**「loop 0/61」的解释**：restart 重建 actor 时 registry 被全新 AgentRecord 覆盖（`loopStreak/loopRounds` 归零，MailTool.activateAgent 760-774 行），AgentControl list 因此显示 loop=0；「61」来自 llm-fail 日志签名。两段信息被拼进同一条 restart reason，非 UI 单一数值。

---

## 3. 「61」的机制含义（问题 4 的答案）

`src/main/scala/nebflow/core/processor/LoopGuard.scala`：

```scala
maxToolRoundsPerTurn: Int = 60          // L46
val s2Over = !s2Exempt && roundCount > cfg.maxToolRoundsPerTurn   // L213
→ Verdict.Terminate("loop-detected: tool-round budget exceeded ($roundCount rounds with no structural progress this turn)...")  // L234
// L0 警告：roundCount == max(1, 60*7/10) = 42                                // L242
```

- `roundCount`：**非进展轮**计数（「有进展轮不计——成功的 Edit/Write/任务状态迁移」，L16-17）。
- turn 边界（UserInput/Mail 投递/外部事件）清零；ToolsComplete 续轮、retry、**save-compact 续跑不清零**（Counters 是会话级状态，qa-frontend 跨 compaction 从 42 继续累到 61）。
- 所以：**任何 turn 恰好在第 61 个非进展轮被杀，61 = 预算 60 + 1**。两起「恰好 61」以及全天 10 起全部 61，都是这个不等式的确定性行为。

**根因不在「循环」，在「进展」的定义**（L107-108）：

```scala
private val ProgressTools: Set[String] =
  Set("Edit", "Write", "TaskUpdate", "TaskCreate", "TeamTaskCreate", "TeamTaskUpdate")
```

成功的 Bash（跑测试、跑 e2e、编译）、Read、Grep 一律记为**非进展**。qa-frontend 的 E2E 验证、Backend 的测试/变异验证、slideblocks/Frontend 的截图工作流——产出实体都是「验证结论」而非文件写入，被系统性判为零进展。**设计目标（打掉 280 次同参同败，见 LoopGuard 头注释 08-25 fork/html-builder 事故）针对的 S1 场景全天零触发；误伤全部来自 S2。**

---

## 4. 「Processing stuck + 空白会话」机制链（问题 5 的答案）

### 4.1 卡死：fatal 路径漏写 registry（实锤，代码级）

loop-detected L1 的投递路径：`AgentCore.scala:1015-1020` → `ctx.self ! AgentCommand.LlmFailed(LoopDetectedError(...), replyTo, state.currentTurnId, Some(loopCounters))`。

`AgentActor.scala` LlmFailed handler（1404-1682 行）：
- LoopDetectedError 不 retry（`llmFailureRetryable` L121-125：非 overload 类 → false）、不冻结（L1533 显式 `case _: LoopDetectedError => false`）→ 走 **fatal 分支**（1555-1682 行）。
- fatal 分支做了：WS 发 error+Done+busy=false（forkTurn 异步）、持久化会话、`AgentEvent.Failed` 清偿 completion debt、无债务时 `parent ! ExternalEvent(failed)`、行为转 `idle(...)`（status=AgentStatus.Error 只写在**内存 behavior state**，1662 行）。
- fatal 分支**没做**：任何 `touchRegistryActivity` / `agentRegistry` 状态回写。

对照全部 registry 状态写入点：

| 路径 | 位置 | 回写 |
|---|---|---|
| 正常 turn 完成 | AgentActor.scala:2859 | `touchRegistryActivity(Idle)` ✓ |
| Interrupt | :3638 | Idle ✓ |
| Stop | :3650 | Idle ✓ |
| ResetSession | :3927 | Idle ✓ |
| ErrorFrozen | :3474 | Frozen ✓ |
| **LlmFailed fatal** | **1578-1682** | **无** ✗ |

而 TaskStuckWatcher 的判据（TaskStuckWatcher.scala:116-119）恰恰只看 registry：`rec.status == Processing && now - rec.lastActivityMs > 600s`。Team 类恢复动作是**只读**（L138-159：绝不自动 Stop，只告警 + 广播 taskStuck）。于是：**loop-detected 后 registry 僵尸在 Processing，watcher 每 30 秒告警，永远等不到人工 restart**。日志中 609s/605s 起步的告警序列与代码逐点吻合。

（注：AgentCore.scala:1046-1057 在每个工具轮结束会把 `loopStreak/loopRounds` 和 Processing touch 写进 registry——坠毁轮把 loopRounds=61、status=Processing 写成最后一条记录，之后无人再改。）

### 4.2 空白：restart 只重建、不续跑

`AgentControlTool.doRestart`（600-650 行）Team 分支：Stop 旧 actor → 等死 → `MailTool.activateAgent`。`MailTool.activateAgent`（635-779 行）：`loadMessagesForSession` 载入 history → spawn 新 actor（idle 起步）→ **唯一的续跑触发是 `MailQueueStore.load` 有排队邮件（775-779 行）**。

两会话 mail-queue.json 均为 `[]`（Backend：`676780c5.../mail-queue.json`；qa-frontend：`5b5d04ec.../mail-queue.json`）→ restart 后 turn 不恢复、无消息进出 → 前端看到的就是 Idle / 无活动 / 「never active」的空会话。audit reason 里的「checkpoint 恢复修复批任务」实际只兑现了「history 已载入」，**「断点续跑」（工具注释 608-609 行的承诺）在没有排队邮件时不成立**。

叠加因素：坠毁 turn 之前刚发生过 full compaction（qa-frontend 13:28、Backend 22:05），`FullCompact.scala:58-67` 用单条摘要**替换**全部历史（preservedRounds=0），会话文件视觉上只剩「摘要 + 少量轮次」，加重「全新空白」观感。

### 4.3 LLM 与压缩排除

router summary 日志（UTC）逐轮核对：qa-frontend 05:36-05:41 每轮 200 OK（kimi/k3-256k，input 66-76k tokens）；Backend 15:16-15:19 每轮 200 OK（GLM-5.3-Flash，input 218-222k）。无空响应、无重复同 prompt 风暴、无压缩触发异常。坠毁是 guard 在健康链路上主动杀人，不是 LLM 故障。

---

## 5. 根因假设（按证据强度排序）

**R1（确定，代码+日志双重实锤）：LlmFailed fatal 路径遗漏 registry 状态回写 → Processing 僵尸 → watcher 无限告警 → 只能人工 restart。**
证据：代码路径逐行对照（§4.1）；两案例 llm-fail 时刻与 watcher 告警起始时刻的 600s 整齐间距（13:41:54→13:52:03=609s；23:19:30→23:29:35=605s）。

**R2（确定）：S2「进展」定义与验证型工作形态结构性错配 → 合法任务批量撞 61 轮预算。**
证据：全天 10 起 S2、0 起 S1/S3；坠毁 turn 内容取证（§2）均为高产出验证工作；两案例坠毁前 L0 警告均被模型读取且无法在余量内收尾。

**R3（确定）：restart 语义与宣传不符——无排队邮件即不续跑，「checkpoint 恢复」实际只恢复 history；坠毁 turn 的工作就地搁浅，依赖 Manager 察觉后重派（qa-frontend 延迟 80 分钟；Backend 修复批搁浅至次日）。**
证据：doRestart/activateAgent 代码路径（§4.2）；两会话空 mail-queue；Backend 会话 updatedAt 止于坠毁时刻。

**R4（低置信，未完全取证）：audit 重启记录存在目标串扰**——13:59:06 的 restart 目标写的是 html-builder（52ad5890）而 reason 是 qa-frontend 语境（「qa-frontend loop 58 轮后停摆」）。可能是 Nebula 写 reason 时的自然语言混淆，也可能 audit 目标字段取值有 bug。影响的是事后取证可信度，不影响本案权责链（13:59:20 对 5b5d04ec 的 restart 独立成立）。

**R5（观察，待取证）：qa-frontend 坠毁 turn 的 compaction archives 未见落盘**（`~/.nebflow/archives/` 只有 8 月初的两个旧会话）。HistoryArchiver 声称失败非阻塞（AgentActor:2021 只 warn），若 08-28 的 archive 确实失败，则 359/426 条压缩前历史不可恢复——「零工件」有一部分是「证据也被压没了」。下一步在 nebflow.log 当日搜 `Compaction archive failed` 验证。

---

## 6. 修复方案建议（含验收条件；仅建议，未改码）

### F1（P0，最小修复）：fatal LlmFailed 路径补 registry 回写

- **改动**：`AgentActor.scala` fatal 分支（~1597-1681 行）在 `yield` 前补 `touchRegistryActivity(resources, state.sessionId, AgentStatus.Idle)`（或引入 `AgentStatus.Error` 并让 watcher 只认 Processing——语义更强，Idle 改动最小）。同步检查 BackoffSupervisor 的 restart-after-crash 路径是否有同类缺口。
- **验收**：
  1. 单测：构造 LlmFailed(LoopDetectedError) → 断言 registry 该 session `status != Processing`（二值）。
  2. 隔离实例（非 8080）真实跑一个必然触发 S2 的脚本化 agent → 断言 llm-fail 后 30s 内 TaskStuckWatcher 零告警、AgentControl list 状态非 Processing（冒烟：实例启动 + `/api/health` 200 先行）。

### F2（P1，消除误杀主体）：S2 进展判定纳入验证型工具的成功轮

- **改动方向（三选一，需用户裁定）**：
  a. ProgressTools 增补「成功的 Bash」——但会放进轮询类 Bash（watch/sleep 循环），需配「同 fp 成功 Bash 连续 N 次仍计非进展」的反向规则；
  b. S2 预算按 agent 角色差异化（qa-* / 验证型任务 120-180 轮），经 nebflow.json `supervision.loopGuard` 热读配置下发（loadConfig 已支持，零代码即可先调阈值）；
  c. 维持 60 轮，但把 L0 警告后的豁免窗口改为「警告后若出现任一成功工具轮即重置计数」（现在只有 Edit/Write/任务迁移算，改为任意成功工具）。
- **过渡措施（今日即可做，零代码）**：nebflow.json 设 `supervision.loopGuard.maxToolRoundsPerTurn: 180`。
- **验收**：回放 08-28 两个坠毁 turn 的工具序列（LoopGuardSpec 已有纯函数内核，直接加用例）→ 断言不再 Terminate；同时断言 08-25 类真循环序列（同参同败 280 次）仍被 S1 拦截（防回归）。

### F3（P1）：restart 的续跑语义补全或如实化

- **改动**：二选一——(a) doRestart 后若 mail-queue 为空，向新 actor 注入一条系统续跑指令（携带坠毁 turn 的最后状态摘要），兑现「断点续跑」承诺；(b) 保留不续跑，但 AgentControl restart 返回文本与 audit reason 模板改为「已重建（history 已恢复），**turn 未自动续跑**——需重新派发任务」，消除「checkpoint 恢复」的误导。
- **验收**：(a) 路径——restart 后断言新 actor 在 N 秒内进入 Processing 且产出首条消息；(b) 路径——restart 返回文本包含「未自动续跑」字样（字符串断言，二值）。

### F4（P2）：watcher 告警风暴降噪 + audit 串扰排查

- Team 类僵尸告警每 30 秒一条、持续到人工处置——改为指数退避（30s→2m→8m，封顶）+ 首条即附「已 loop-detected 终止」归因（registry 有 loopRounds=61 可直接引用）。
- 核查 13:59:06 audit 行目标/来源串扰（R4）：比对 AgentControl 调用时序与 audit 落库字段。

### 端到端回归（F1-F3 合并验收）

隔离实例真实启动 → 派发一个「必触发 S2」的受控任务 → 断言全链：loop-detected 触发（日志签名含 61）→ registry 60s 内脱离 Processing → watcher 零告警 → restart 后（按 F3 选定语义）会话可继续或如实报状态 → `/api/health` 全程 200。冒烟不通过则其余验收全部无效。

---

## 7. 下一步取证清单（当前证据不足闭合的部分）

1. **archives 缺失**：搜当日 `Compaction archive failed`；确认 5b5d04ec/676780c5 压缩前历史是否可找回（影响「零工件」的完整归因）。
2. **audit 串扰**：13:59:06/13:59:20 两条 restart 的调用栈与 audit 字段来源（R4 定级）。
3. **slideblocks/Frontend 三连坠**：三次 turn 是否同一任务形态重入（若是，F2 修掉后应自然消失；取其会话验证 S2 计数构成）。
4. **`sbt 编译空窗误报`**：qa-frontend 摘要提到「后台 job 被监督器标 failed（sbt 编译空窗误报，已知坑）」——与本案无直接因果，但属同族「监督器误判」，建议并入 F4 一并排查。

---

## 附：本调查自身的活体注脚

本 turn（Explorer 取证任务）于 2026-08-29 00:00 收到同款 L0 警告「42 of 60 non-progress rounds」——一次纯阅读取证型任务，与 qa-frontend/Backend 坠毁前的形态完全一致：**正当、高强度、写盘稀疏**。S2 预算对这类工作形态的杀伤力，在调查过程中被守卫亲自演示了一遍。
