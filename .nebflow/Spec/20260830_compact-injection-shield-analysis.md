# qa-frontend 压缩死亡根因 + 压缩期注入屏蔽实现状态排查

- **类型**：阶段文档（一次性诊断，完成即冻结，不登记 INDEX）
- **日期**：2026-08-30
- **范围**：只分析，未改任何代码
- **代码基线**：`/Users/dev/Claude code/Nebflow` @ main（排查时刻）
- **会话**：`nebflow-project/qa-frontend/5b5d04ec`
- **用户原话**（08-26 00:17 首提，09:04 重申）：「压缩阶段不让任何外部注入干扰压缩；但注入不能丢失——压缩完成后一起注入（mail、tool 结果、delegate 结果等所有外部事件）」

## 结论速览

| 问题 | 定论 |
|---|---|
| Q1 qa-frontend 死亡根因 | **Compact 阶段 thinking-only 响应滑落 mail-check 死循环**。压缩 LLM 把摘要写在 thinking 里、text 为空 → 分支梯第 3 档（`AgentActor.scala:2519`）要求 `text.nonEmpty` 不匹配 → 滑入第 7 档 mail-check → 工具已禁用的 Compact 轮不可能调 Mail → reminder×2 → give-up → finishTurn → zombie-guard 记 `compaction-abandoned`。全天三次同构死亡（03:29 / 03:41 / 08:45）。**INTERRUPT Mail 注入未参与打断**——它是启动该 turn 的输入，早于压缩 9ms，压缩窗口内零外部事件入队。 |
| Q2 屏蔽功能实现状态 | **部分实现**。入队保持 + 边界守卫 + no-resume 路径压缩后重放均已存在（08-14/08-18 引入，早于用户提问）且工作正常；缺口 = ① 自动压缩默认路径（`resumeAfterCompact=true`）压缩完成后**不排空队列**；② 三个队列纯内存态，压缩期崩溃/重启全丢（本事故恰以此结局收场）。**08-26 提问后零实现提交**（git log 全量核实）。 |

---

## §1 Q1：qa-frontend 死亡因果链

### 1.1 背景（E0）

qa-frontend 自 08-29 夜间起上下文持续膨胀，03:23-03:41 已达 205-210K tokens / ~380 msgs，超过自动压缩阈值 204800。当天凌晨两次自动压缩以**完全相同的方式**死亡：

| 次序 | 时间 | job | 关键数字 | 日志行（nebflow.log） |
|---|---|---|---|---|
| 1 | 03:29:12 | compact-9c4c2160 | thinking=706, msgs=372 | 5917-5920 |
| 2 | 03:41:50 | compact-e62eae58 | thinking=368, msgs=383 | 6172-6175 |
| 3 | 08:45:52 | compact-95b52824 | thinking=182, msgs=392 | 7558-7561 |

三次序列完全同构：`auto-compact-trigger`（205123 / 210175 / 215775 > 204800）→ `save-phase-complete` → `mail-reminder`×2 → `mail-give-up` → `compaction-abandoned phase=Compact`。qa 全天 0 次 `compaction-complete`（成功压缩只出现在 Frontend/Backend/html-builder 会话），0 次 `compaction-failed`——失败从未走显式失败通道，全部静默死于 abandoned 路径。两次凌晨死亡后上下文滞留 ~210K，agent 空闲。

### 1.2 08:45 事故窗口精确时序（日志行 7482-7561 + anthropic 消息日志）

| 时刻 | 事件 | 说明 |
|---|---|---|
| 08:41:59 | 用户在 Nebula 会话催问 qa 进展 | 触发 Manager 催 qa 两批验收结论 |
| 08:43:03 | Nebula → Manager 转发催促 | — |
| 08:43:28.130 | Manager 的 `[INTERRUPT]` Mail 到达**空闲** qa → turn 启动 | `start(384)`。Mail 经 idle→ImmediateInput→UserInput 转换启动 turn（此路径不打 `immediate-input-queued` 日志）。**这是 turn 的启动输入，不是压缩中途的注入** |
| 08:43:28.139 | 首个 `pipeLlmCall` → `auto-compact-trigger` | 215775 > 204800，`startDirectCompaction`，job=compact-95b52824，phase=Save（team agent，注入 save reminder）。Mail 到达与压缩启动相距 **9ms**，压缩启动时 Mail 已在 turn 输入内 |
| 08:43:28.140 | log 7484：Manager Mail OK（11ms） | MailTool 回执，非注入 |
| 08:43:47 | Save 轮 LLM 返回（218188 in） | — |
| 08:43:57 | out=333，end_turn，无工具 | Save 轮正常 |
| 08:43:58.187 | `save-phase-complete`（log 7496） | phase := Compact，注入 CompactPreamble（"All tools are DISABLED for this turn. TEXT ONLY"），`pipeLlmCall` 工具禁用（`AgentCore.scala:~433` `isCompactTurn → tools=Some(Nil)`） |
| 08:44:18 | Compact 轮 LLM 调用（217603 in） | — |
| 08:45:04.164 | Compact 轮返回：out=1637，end_turn，**无工具** | 模型把两阶段摘要以流式输出呈现（用户在 UI 看到「两批验收结论属压缩 summary 范畴…循环完成。」），但 `ConsumeResult.text` 为空——内容全在 thinking 通道（耗时 46s） |
| 08:45:04.445 | `mail-reminder` 1/2 | 分支梯滑落（见 §1.3） |
| 08:45:09 | LLM 重试（219291 in），out=53，thinking-only | — |
| 08:45:11.582 | `mail-reminder` 2/2 | — |
| 08:45:50 | LLM 再试（219395 in），out=49，thinking-only | — |
| 08:45:52 | `mail-give-up` → `finishTurn` → `compaction-abandoned` + `turn-ended-empty` | log 7558-7561：`reason=turn-ended-before-compact job=compact-95b52824 phase=Compact`；`textLen=0 msgs=392 model=kimi/k3-256k` |

### 1.3 死亡机制：分支梯滑落（代码级）

`handleLlmCompleteBranch`（`AgentActor.scala:2496-2555`）判定梯，Compact 轮 thinking-only 响应的走向：

```
:2515  pendingCompaction.phase==Save && toolCalls.isEmpty      → 不匹配（phase 已是 Compact）
:2517  pendingCompaction.phase==Save && toolCalls.nonEmpty     → 不匹配
:2519  pendingCompaction.isDefined && toolCalls.isEmpty && result.text.nonEmpty
                                                            → 不匹配（text 为空 ←← 致命前提）
:2521  pendingCompaction.isDefined && toolCalls.nonEmpty       → 不匹配
:2527  text.nonEmpty || thinking.nonEmpty                      → 匹配（thinking 非空）
:2530    expectsMail && !mailUsedThisTurn && mailReminders < 2 → handleMissingMail
         （注入「必须调 Mail」reminder，重新调 LLM）
```

**死循环的确定性**：Compact 轮工具被机制禁用（`tools=Some(Nil)`），模型物理上不可能调 Mail，但 `handleMissingMail` 每轮注入「必须调 Mail」reminder 并重试——最多 `MaxMailReminders=2`（`AgentActor.scala:138`）次后 `mail-give-up` → `finishTurn`。两次重试（08:45:09、08:45:50）各花 ~40s、各吃 219K+ input tokens，纯属烧钱空转。

**为何 abandoned 后空结束而非续跑**：`finishTurn` 的 zombie-compaction guard（`AgentActor.scala:2574-2607`）检测到 `pendingCompaction` 残留——这是防御性兜底（防止 job 幸存进 idle 后下一轮正常回复被误判为压缩 summary 而替换全部消息，即历史 zombie bug），它只做三件事：记 `compaction-abandoned` 日志、清 job、`compactionFailures+1`。**没有重试压缩、没有续跑**。「压缩后续跑」逻辑只存在于 `CompactionComplete(Right)` 处理（`:1990+`），而本事故中该消息从未产生——模型输出被分支梯吞进 mail-check，摘要内容（在 thinking 里）从未被解析、从未应用到消息历史。最后 `turn-ended-empty`（`:2611-2619`，#22 留痕机制）记录 `textLen=0 msgs=392`，actor 回 idle。

### 1.4 后果与恢复（E8）

- 压缩从未完成：上下文仍 ~215K / 392 msgs；`[INTERRUPT]` Mail 携带的任务无人应答、无 Mail 回报——从外部（Manager、用户）看 = **静默死亡**。
- 08:56:49 用户问「qa 是不是死了」→ 08:57:52 Nebula 用 AgentControl restart（AUDIT log 7690）→ 08:58:44 新 actor `start(393)` + `emergency-compact-trigger` P0-2（estimated=157585 > hardLimit=121600，log 7708）→ 硬截断至 ~52 msgs → 08:59:39 / 09:04:18 补发 RESULT Mail → 09:05:03 `turn-complete msgs=52`，恢复。

### 1.5 三个子问题直答

1. **注入是否打断了压缩 turn？** 没有。① 该 Mail 早于压缩 9ms 到达且是 turn 的启动输入（空闲→启动，不是 processing 中注入）；② 整个压缩窗口（08:43:28-08:45:52）内该会话**零入队日志**（无 `immediate-input-queued` / `mail-queued` / `external-event-queued` / `user-input-queued`）；③ drain guards（`TurnBoundaryDrains.drainHead/drainBarrier`，`AgentActor.scala:51-53/84-99`）全程完好。死亡的锅 100% 在分支梯 + mail-check 组合，与外部注入无关。
2. **abandoned 后为何空结束而非续跑？** 因为死亡发生在 `mail-give-up → finishTurn` 终态路径，zombie-guard 只清 job 不重试；「压缩后续跑」分支挂在 `CompactionComplete(Right)` 上，而本次压缩从未产生该消息。设计假设「合法压缩流程永远不会走到 finishTurn」（`:2578` 注释），但分支梯第 3 档的 `text.nonEmpty` 前提为 thinking-only 响应留了一个漏网通道，打破了这个假设。
3. **392 msgs 的角色？** 放大器与陷阱：① 推高 inputTokens（215775）强制每 turn 必触发压缩；② 每次压缩失败反增 ~8 条消息（372→383→392），越失败越大；③ 每次重试吃 215K+ 输入、单轮 40-46s；④ 最终触发 P0-2 硬截断（157585 > 121600），摘要内容彻底丢失，只能靠截断恢复。

---

## §2 Q2：压缩期注入屏蔽——实现状态定论

### 2.1 压缩期外部事件实际路径（现状核查）

| 事件类型 | 压缩期（`pendingCompaction` 非空）行为 | 代码位置 |
|---|---|---|
| Mail immediate | `ImmediateInput` → `pendingImmediateInputs :+`；ToolsComplete 边界 `drainHead(compactionPending=true)` 压住不取 | `AgentActor.scala:2289-2301`、`1691-1692` |
| Mail queue | 仅 `pendingMailQueueCount+1`，内容在磁盘 MailQueueStore | `AgentActor.scala` MailQueued 分支 |
| 工具结果 / delegate 结果 / 后台任务 | `ExternalEvent` → `pendingEvents :+`（log `external-event-queued`）；ToolsComplete 边界 `drainBarrier(compactionPending=true)` 压住 | `AgentActor.scala:2156-2176`、`1670-1675` |
| 用户文字输入 | `pendingUserInputs :+`（log `user-input-queued`） | `AgentActor.scala:2263-2288` |

**hold/defer 机制确实存在且本次事故中工作正常**——压缩窗口内队列只进不出，无一条事件被注入压缩 turn。

### 2.2 已有实现（全部早于用户 08-26 提问）

| 机制 | 引入 | 位置 |
|---|---|---|
| 队列消费即丢修复 + `TurnBoundaryDrains` guard | 2026-08-14 `c3f9ca07` | `AgentActor.scala:42-99` |
| immediate inputs 边界守卫 | 2026-08-11 `2b13ecb7` | ToolsComplete `drainHead` |
| events barrier（subagent 批次合批语义） | 2026-08-18 `c324ee37` | `drainBarrier` |
| `CompactionComplete` no-resume 分支压缩后重放（三档优先级：immediate → user → events） | 08-14 系修复 | `AgentActor.scala:2004-2101` |
| 队列跨 interrupt/restart 存活（#13） | — | `protocol.scala:1316-1329` |

**`resumeAfterCompact=false` 路径满足用户要求**：压缩完成后按优先级排空三类队列（`immediate-input-injected-after-compaction` `:2026` / user inputs forward `:2059-2068` / `pending-events-injected-after-compaction` `:2089`）。

### 2.3 缺口（部分实现的落点）

**G1（主要）— 自动压缩默认路径压缩完成后不排空队列。** `resumeAfterCompact=true`（`maybeAutoCompact` 触发的自动压缩默认值）分支（`AgentActor.scala:1990-2004`）在压缩完成后**直接 `pipeLlmCall` 续跑原任务，不查任何队列**。压缩期间排队的事件要等到续跑 turn 走到下一个 ToolsComplete 边界或 turn 结束（`finishTurnCont` 的 ladder，`:2779/:2832-2868/:2893`）才被注入。对照用户要求「压缩完成后**一起注入**」：事件注入被推迟了一个不确定的时间窗，若续跑 turn 是纯文本短轮次甚至可能在 turn 结束才见到。08-26 的排查报告（`/tmp/compaction-input-shield-findings.md` 第 49 行）自己就写着这个行为，当时结论「完整覆盖」是过宽的判断。

**G2 — 队列纯内存态，崩溃即丢。** `pendingEvents`（`protocol.scala:872` 注释 "In-memory only"）、`pendingImmediateInputs`（`:885` 同）、`pendingUserInputs` 均不落盘。本事故的结局恰是压缩期死亡 → 人工 restart——虽然 #13 修复让队列跨 restart 存活（actor 内存不死），但进程级崩溃/强杀场景下压缩期排队的事件全丢。例外：queue 模式 Mail 本体在磁盘 MailQueueStore。

**G3（有意例外，非缺陷）— 用户 Interrupt 会杀压缩。** `AgentActor.scala:1845-1860`：processing 中收到 `Interrupt` → `cancelCurrentTurn` + 清 `pendingCompaction`。这是用户终止语义的逃生门（排队输入保留，后续正常 drain），严格字面上属于「外部输入打断了压缩」，按设计意图保留。

**G4（未完全核实，标注）—** schedule 定时触发的投递路径未单独走查；AskUserQuestion 应答走 InteractionHub Deferred 独立通道，不经输入队列（无丢失风险，仅延迟）。

### 2.4 08-26 后是否有实现提交？

**没有。** `git log --since=2026-08-26 -- src/main/scala/nebflow/agent/AgentActor.scala AgentCore.scala core/compact/` 全量核对：19 个提交（task progress 语义、权限梯、LoopGuard、mail idle gate、mail wedge 修复、ask 移除、dedicated-agents、save-turn 白名单、backoff 修复、flow-node supervision、cancel-batch、#418、#407 等）**无一是压缩屏蔽/合批注入的新实现**。用户在 08-26 00:17 提问后，Nebula 派 Explorer 核查并于 00:19:44 写了排查报告即止，未转化为实现任务。

---

## §3 与现有 `pending-events-injected-at-tools-complete` 机制的关系

`pending-events-injected-at-tools-complete`（`AgentActor.scala:1684`）本身就是屏蔽机制的一部分——它是「压缩期 hold、工具完成边界注入」的实现点之一，带 `compactionPending` guard（压缩中不排空）。用户要的功能 = 在此之上补两块：

1. **压缩完成即刻合批注入**（补 G1）：现在只有 no-resume 路径有，自动压缩（resume）路径没有。
2. **持久化防丢**（补 G2）：现在三个队列全内存。

不存在机制冲突：`drainBarrier` 已是共享纯函数，resume 分支复用同一套 drain 即可；`buildEventReminder`（`:2092` 已在用）天然支持多事件合批为单条 system-reminder。

---

## §4 修复方案（只规划，未实施）

### F0（前置，修 Q1 死亡）— Compact 轮 thinking-only 响应禁止滑落 mail-check

落点：`handleLlmCompleteBranch`，`AgentActor.scala:2519` 一带。

```
新增分支（置于 :2519 之后、:2521 之前或合并判定）：
  pendingCompaction.isDefined && phase==Compact && toolCalls.isEmpty && result.text.isEmpty
    → 按压缩失败处理：handleCompactFailure（或带次数上限的压缩重试，
      重试 = 重新 pipeLlmCall 压缩轮，而非注入 mail reminder）
```

理由：Compact 轮工具禁用 + expectsMail 组合是确定性死循环（本事故三次实证）。`handleCompactFailure`（`:4034`）已有完整的失败清理 + 回退路径。辅助：`CompactPreamble`（`CompactService.scala:254`）追加一句「你的正文（text）必须非空——摘要写在正文里，不是思考里」，针对 kimi/k3-256k 这类把输出倾入 thinking 的模型。

### F1（补 G1）— `CompactionComplete(Right)` 统一排空

落点：`AgentActor.scala:1990-2004`（resume 分支）。

- 抽出单一 `drainQueuesAfterCompaction(compactedState)`，resume=true / false 两分支共用（现 no-resume 分支 `:2004-2101` 的三档排空逻辑原地提取）。
- resume=true 时：压缩摘要消息 + `postCompactInstruction`（如有）+ **全部排队的 immediate inputs + user inputs head + events（`buildEventReminder` 合批）** 一次性拼入续跑轮的消息序列，再 `pipeLlmCall`。
- 日志：复用现有 `*-injected-after-compaction` 事件名，计数字段带全队列规模。

### F2（补 G2）— 队列持久化

- 入队即写盘（复用 MailQueueStore / TurnStateStore 的存储模式），`start`/restart 恢复时重放。
- 最低限度覆盖 `pendingImmediateInputs` 与 `pendingEvents`（`pendingUserInputs` 的 WS 层已有 `appendUiMessages` 前端留存，可二期）。

### F3（可观测性，可选）— 压缩窗口队列快照

- 压缩开始（`startDirectCompaction`）时记 `compaction-window-start queued=imm:a user:b events:c`；
- 完成排空时记注入计数。法证可直接断言「窗口内入队数 == 完成后注入数」，零丢失可审计。

---

## §5 验收条件（二值可断言）

| # | 条件 | 验证方式 |
|---|---|---|
| V1 | 编译 + 既有测试通过 | `sbt compile` 成功；`sbt "testOnly nebflow.agent.AgentActorCompactionSpec"` 全绿（该 spec 已含 drain guard / 压缩后重放 / zombie 归一化用例） |
| V2 | 压缩期保持 + 完成后全量注入（单元） | 构造 `pendingCompaction` + 混合队列（ImmediateInput×a / ExternalEvent×b / UserInput×c）→ 断言 `drainHead`/`drainBarrier` 返回保持；发 `CompactionComplete(Right)` 后断言注入日志计数 `events=a+b+c`、三队列 `remaining=0` |
| V3 | E2E 合批注入（隔离实例，端口 ≠ 8080） | 隔离实例强制触发压缩；压缩轮进行中投递 2 条 Mail + 1 个 delegate 结果；断言：① 压缩期间日志**无** `*-injected-at-tools-complete`；② `compaction-complete` 出现后，三条事件在**同一续跑轮**注入（日志计数 3）且回复内容引用了它们 |
| V4 | 崩溃恢复（F2 落地后） | 压缩期带队列杀掉隔离实例 → 重启 → 断言队列幸存且后续注入（日志可见 `*-injected-*` 且计数匹配入队数） |
| V5 | Q1 回归 | 构造 thinking-only 压缩响应（mock ConsumeResult.text=""、thinking 非空）→ 断言：① 不出现 `mail-reminder`；② 走 `handleCompactFailure`/重试通道；③ 生产日志不再出现 `compaction-abandoned phase=Compact` + `mail-give-up` 同日组合（grep 断言） |
| V6 | 冒烟（红线） | 隔离实例 `sbt run` 真实启动 → `curl /api/health` 200 → `curl /` 返回 HTML → 正常对话一轮 → 正常关闭（**严禁触碰 8080 宿主实例**） |

**自查**：含冒烟（V6，真实启动非 mock）✓；E2E 从用户可见行为断言（V3 回复内容）✓；每条可脚本化、二值 ✓；隔离实例纪律：所有启停实验用端口 ≠ 8080，杀前 `lsof -ti :<port>` 核实 PID 非宿主 ✓。

---

## 附录：证据索引

- 日志：`~/.nebflow/logs/nebflow.log` 行 5917-5920 / 6172-6175 / 7482-7561 / 7690-7708；`nebflow.2026-08-25.1.log:1731`（08-26 用户提问原文）
- 代码：`src/main/scala/nebflow/agent/AgentActor.scala`（:42-99 drains / :1637-1716 ToolsComplete / :1845-1860 Interrupt / :1935-2104 CompactionComplete / :2156-2301 入队 / :2496-2555 分支梯 / :2574-2619 zombie-guard+turn-ended-empty / :3990 handleCompactResponse / :4034 handleCompactFailure）；`AgentCore.scala`（:71 maybeAutoCompact / :287 startDirectCompaction / :418 pipeLlmCall）；`protocol.scala`（:749 CompactionJob / :859 pendingEvents / :872,:885 in-memory 注释 / :889 pendingImmediateInputs / :1316-1329 resetForInterrupt）；`MailTool.scala`（:743 expectsMail / :848 ImmediateInput）；`CompactService.scala`（:254 CompactPreamble）
- git：`c3f9ca07`（08-14 drains）/ `2b13ecb7`（08-11）/ `c324ee37`（08-18 barrier）；08-26 后相关文件 19 个提交无屏蔽实现
- 前次排查：`/tmp/compaction-input-shield-findings.md`（08-26 00:19，结论「完整覆盖」偏宽——其第 49 行自述 resume 分支不即时排空，即本报告 G1）

---

## 批 2（F2 队列持久化）完成记录（2026-08-30 12:2x）

**范围**：F2 队列持久化——`pendingImmediateInputs` + `pendingEvents` 压缩窗口入队即落盘，崩溃后 spawn 重放。

**交付**：`feat/compact-injection-shield-b2` @ **d1445914**（worktree `~/Claude code/.nb-worktrees/nb-compact-shield-b2`，基线 main c0690a63）

- `CompactionQueueStore.scala`（新）：`sessions/<sid>/injection-queues.json` 原子写；空队列删文件；损坏 warn+None；失败吞错不阻塞入队路径；`pendingUserInputs` 明确不持久化（WS 层 appendUiMessages 已有前端留存）
- `protocol.scala`：`RecoverPersistedQueues` 命令
- `AgentActor.scala`：`persistQueues` 挂 6 集成点（IMM/EV 入队×2、压缩后 drain×2、turn 末 drain×2）+ spawn 自投 recover 幂等合并（盘上队列前置 in-memory）
- 测试：`CompactionQueueStoreSpec` 6 用例（round-trip 全字段/blocks/source/metadata/清空删文件/损坏容错/空 sid no-op/legacy 缺字段默认值）+ `AgentActorCompactionSpec` +2（V2-persist 入队即落盘、V2-recover 崩溃重放进续跑轮）+ `SelfSendSetupSpec` 2 用例（新，见下）

**关键发现并修复（真产品 bug）**：`ctx.self ! msg` 返回 `IO[Unit]`——在 `Behaviors.setup` factory 里裸语句是 **built-not-run**（IO 构建即弃，消息从未入队）。隔离探针实证：setup 期自投消息静默丢失、post-start 自投正常。修法=`(ctx.self ! RecoverPersistedQueues) *> IO.pure(idle(...))` 串行进 factory IO。`SelfSendSetupSpec` 钉死此机制回归。教训沉淀：**任何 `Behaviors.setup` 内的 `ctx.self !` 必须串行**——裸语句编译过但永远不投递，且「spawn 后消息应被处理」的直觉让此类 bug 极难肉眼发现（V2-recover 恰好抓出）。

**验证终态**：
- 定向：CompactionQueueStoreSpec 6/6 + AgentActorCompactionSpec 25/25 + SelfSendSetupSpec 2/2
- **全量 1848/1848** Failed 0（基线 1838 + 10 新增，日志 `/private/tmp/nb-compact-b2-fulltest.log`）
- **变异验红 ×2**：M1（sequencing 还原为裸语句）→ V2-recover 恰红（24/25）；M2'（入队 persist 双移除）→ V2-persist 恰红（NoSuchFileException），V2-recover 不受影响——两条修复正交
- **V4 崩溃恢复 E2E ALL PASS**（`/private/tmp/nb-compact-b2-e2e/run-e2e.sh`，端口 18288/18289）：压缩窗口入队 IMM+EV → 磁盘快照双断言 → SIGKILL 隔离实例（PID 验身 cwd=worktree）→ 重启同 home → `queues-recovered imm=1 events=1` → 续跑轮 req#5 双 marker 零丢失
- **V6 冒烟 PASS**（隔离 18288 ≠ 8080，双 boot health/html，杀前 PID 验身）

**E2E 基建教训**：run-e2e.sh 失败路径曾跳过 teardown 留孤儿（mock 占端口 + 旧 gateway 顶新实例导致假绿/假红）——改 `trap teardown EXIT` 全路径清理；driver 读盘断言须轮询（actor 串行处理，REST 返回时事件可能仍在 mailbox）。
