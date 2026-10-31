---
type: audit-report
status: published
date: 2026-10-31
scope: delegate 待命语义全链审计（生命周期状态机 / judge 判词 / Mail 回合边界 / 提醒注入现状）+ 待命态设计草案；活体证据 = delegate-nebflow-13446387（注册表沉降批）与 delegate-nebflow-8b2f50d3（液态玻璃批）
---

# Delegate 待命语义审计（作者 10-31 11:10 令「先审计一下现状吧」）

关联：[[20261031_093600_device-peer-mobile-chat-windows__delegate-nebflow-002f6988]]（同日delegate批范例）、[[index]]。

## 结论先行（五句话）

1. **Delegate 没有「待命/空等/parked」状态**：任务行词表只有 running/restarting/completed/failed/ready-to-merge/cancelled（`src/main/scala/nebflow/shared/SubAgentTaskModels.scala:25`），会话侧最接近的是 `AgentStatus.Waiting`（`src/main/scala/nebflow/actor/AgentState.scala:234-245`），但它的写点只有 merge-queue 停靠一条腿——普通 delegate 回合结束后就是裸 `Idle`，等待与否平台不可见。
2. **「停手待命」Mail 到达 → 起新 turn → 回执 → 回合自然结束 → 无条件触发终态判词**：判词序漂移闸＞确定性预检＞判定机＞自申报＞**默认 Completed**（`src/main/scala/nebflow/core/delegate/DelegateTerminal.scala:143-188`），而确认回执是非空文本、确定性预检不拦（`hasRealOutput` 只查非空与空烧 stub，`DelegateTerminal.scala:384-390`），判定机判据主轴是 **git 形态学**而非文本语义（`src/main/scala/nebflow/llm/decision/DelegateJudgeMachine.scala:168-178`）——所以一条确认回执必然升级成某种终态，这是结构性默认，不是判官失误。
3. **两个活体实例各命中一端**：13446387（分支 0 领先提交）被判定机实际判了「无法完成」但按 #566 折成 `completed＋note`、父会话收到 eventType=completed 的终态回执（`~/.stand/delegates/delegate-nebflow-13446387.receipt.json`）；8b2f50d3（分支有 17 文件未并提交）被判 ready-to-merge → MergeQueue 入队当拍即放行 → **merge-turn 提醒自动注入、delegate 被唤起自行合并**（merge-ledger：readyAt 11:03:14 = grantedAt 11:03:14，closedAt 11:03:37）——「通知合并」不需要任何人下令，FIFO 空着就会自发发生。
4. **Mail 与回合的关系是「边界注入」而非「中间通信」**（#939 批：回合进行中 → 工具调用边界注入，`src/main/scala/nebflow/agent/TurnBoundaryDrains.scala:130-193`），delegate 的回合一旦自然结束，最终 assistant text 就是「结果」——平台没有「回执≠完工」的区分通道，`delegate_report` 只是状态槽、**未申报不阻断终态**（`src/main/scala/nebflow/core/tools/DelegateReportTool.scala:32-34`）。
5. **等待期是纯事件驱动、零主动探测**：现存的四类提醒（held 回执催注 10/20/30min、bg-lifecycle nudge 10/20/40min、crash nudge 5/10/60s 重启延迟、merge-turn 放行）最短首催档全是 10 分钟（`src/main/scala/nebflow/shared/Defaults.scala:803-884`），没有 10/30/60s 短档退避；设计草案推荐 **案A（显式待命状态）为主干 + 案B 的 JeV 判断注入作兜底识别 + 短档退避看待护**，根治「确认回执=完工」。

## 活体实例复盘（记录实证）

### 实例 1：delegate-nebflow-13446387（注册表沉降批）→ completed

- 11:02:33 收到作者 Mail「立即停手待命——不做任何新动作…等我下一条 Mail 再续」（会话 transcript `~/.stand/sessions/delegate-nebflow-13446387.json`）。
- 11:02:56 回合回执：「收到，立即停手。当前状态冻结如下…待命，等下一条 Mail 再续。」回合自然结束。
- 终态判词：判定机实际读出「没做完」（回执附注 `[delegate-judge] note: judge: could not complete — substance in the terminal text`）——但 #566 起 blocked 判词**折 completed＋注记**（`DelegateJudgeMachine.scala:203-210`、`DelegateJudge.scala:49-61` 两态词表），父会话 11:02:58 收到 eventType=`completed` 终态回执 → 根会话汇报「delegate completed」。
- 分支 `delegate/delegate-nebflow-13446387` 领先提交数 = 0 ⇒ 未触发 ready-to-merge 翻转（`DelegateTerminal.scala:213-216` 零领先降级；merge-ledger 无该成员），**未进合并队列**。
- 会话死否：transcript 至此终止，无后续 turn——从平台视角这个 delegate 已经「completed」，作者下一条 Mail 投给的是已完成会话（实际引擎侧 delegate 通道仍可续聊，但任务行/面板均已终态化）。

### 实例 2：delegate-nebflow-8b2f50d3（液态玻璃批）→ ready-to-merge → merged

- 11:01:56 收到同文「停手待命」Mail → 11:02:13 回合回执确认。回合结束。
- 判词素材：终文 = 确认回执；git digest = 分支有 17 文件 +1421/-246 未并提交 ⇒ 命中 ready-to-merge 判据「working branch + deliverable commits ahead」（`DelegateJudgeMachine.scala:175-177`）。
- 11:03:14 判 ready-to-merge 并入队；**入队当拍即 grant**（FIFO 净空，`MergeQueue.pumpLocked`，`src/main/scala/nebflow/core/delegate/MergeQueue.scala:658-681`）→ 11:03:14 merge-turn brief 以 `UserInput(eventType="merge-turn")` 注入（`MergeQueue.scala:861-884`）→ delegate 被唤起，11:03:30 自行执行 `git merge --no-ff` → 11:03:37 care-tick 账实核对关账 merged（closeReason：「merge landed (care-tick fact-check; owner did not report back)」）。
- 作者的「怎么就通知合并」即此链：**停手回执本身不改变分支上的既有产物，产物在 ⇒ 回合结束 ⇒ 判词只看形态 ⇒ 入队 ⇒ 空队列当拍放行**。全程无人工节点。

## 问题① 生命周期状态机现状：回合收尾 → judge → verdict → merge 通知全链

### 状态全集

任务行（`SubAgentTask.status`）：`running | restarting | completed | failed | ready-to-merge | cancelled`（`SubAgentTaskModels.scala:25`）；另有外推墓碑 `removed` 不落盘（`src/main/scala/nebflow/agent/SubAgentTaskStore.scala:262-266`）。`blocked` 已于 #566 批（2026-10-19）退役：「delegate 是不应该有 blocked 型状态的，直接上报就行了」（`DelegateTerminal.scala:38-42`），现存活形态只有注记与 metadata（`MergeQueueBootSweep.scala:297` 的 `failureType="blocked"`）。

### 「回合正常结束」走的固定路径

内部腿（`DelegateTerminal.onCompleted`，`DelegateTerminal.scala:97-240`）：

1. 收集素材：终文、git digest（含漂移闸读数）、`selfReport`（registry 状态槽）、`sessionToolCalls` 全会话计数（`:114-164`）。
2. 判词序（`:170-188`）：**漂移闸确证 ＞ 确定性预检 ＞ 判定机 ＞ 自申报回落 ＞ 默认 Completed**。注意最后一个 `.getOrElse(DelegateJudge.Verdict.Completed)`（`:188`）——判不出来也是 completed（外部腿同构：判定机吞错后裸 `Outcome.Completed`，`src/main/scala/nebflow/core/executor/ExternalSessionRunner.scala:560-571`）。
3. 判词后机械闸：ready-to-merge × 分支锚齐 × 零领先提交 ⇒ 降级 completed（`:213-216`）；completed × worktree 座位 × 确有领先提交 ⇒ **翻转 ready-to-merge 自动入队**（`:217-226`，「交付绝不静默丢失」）。
4. 分流（`src/main/scala/nebflow/agent/BackoffSupervisor.scala:178-296`）：Release ⇒ 投父 completed（带判词行 `verdictLineCompleted`，`DelegateTerminal.scala:617-619`）；HoldForMerge ⇒ **不投父**、任务行写 ready-to-merge、registry 置 Waiting、入 MergeQueue（`:211-272` + `DelegateTerminal.scala:767-858`）。
5. merge 通知：入队即 pump，grant ⇒ `notifyTurn` 把 `<system-reminder>` 合并 brief 注进 delegate（`MergeQueue.scala:861-884`，brief 文本 `:1214-1227`）；放行节拍 = 事件 + `MergeQueueCare.tick` 搭 TaskStuckWatcher 30s 扫描拍（`src/main/scala/nebflow/core/delegate/MergeQueueCare.scala:6-36`）。

### 为什么确认回执被升级为 completed / ready-to-merge

- 判官的输入里**没有任何信号区分「确认回执」与「任务完工」**：终文段标 UNTRUSTED 供审视（`DelegateJudgeMachine.scala:454`），但判据词（`:169-177`）全部围绕「分支/提交/digest」，文本只作佐证。
- 确定性预检只拦「零工具调用 + 空文本/空烧 stub」（`DelegateTerminal.scala:377-390`）；一句非空确认 = `hasRealOutput=true`，通过。
- 判定机两实例实读：13446387 判了「无法完成」仍折 completed＋note；8b2f50d3 判 ready-to-merge 完全合规（形态匹配）。**问题不在判官，在「回合结束即终判」的架构默认值**。
- judge 全败时还有 self-report 回落（`:176-187`）与 Completed 默认（`:188`）双保险把回合推向终态；`delegate_report` 未申报不阻断（`DelegateReportTool.scala:32-34`）。

## 问题② 「待命/空等/parked」状态是否存在

**不存在**。全仓 grep：`standby`/`parked`/`待命` 在引擎状态面零命中（前端「待命卡」是 UI 词，fiber 级 parked 是注释词）。

最接近的是 `AgentStatus.Waiting`（`AgentState.scala:234-245`），差三条：

| 差项 | 现状 | 待命语义需要 |
|---|---|---|
| 覆盖面 | 写点只有 merge-queue 停靠（`BackoffSupervisor.scala:247-258,323-330`） | 任何「回合结束等作者事件」的 delegate 会话 |
| 终判与否 | merge 停靠**已经判完**（ready-to-merge 是终态），Waiting 只是等执行 | 待命时**未终判**——任务行不许写 completed，判词挂起 |
| 恢复动作 | 合并提醒唤醒 = 继续执行既有终判 | Mail/续聊唤醒 = 续跑原任务，且唤醒前平台应可见「它在等」 |

另有 supervisor 级 bg-wait 等待屏障（未结后台作业 ⇒ 不终判、child 保活，`BackoffSupervisor.scala:382-472`），但那是「回合在法律意义上未完结」，与「回合已结束、任务未终」正好相反——后者在状态机里**没有容身之处**。

## 问题③ Mail 注入与回合边界：回执为何算「结果」

- Mail 到 Processing 会话：#939 批固定为**工具调用边界整包注入**（`TurnBoundaryDrains.scala:130-193`），不会打断当前 turn；到 Idle 会话：立即起新 turn（`MailIdleGate` 判 Waiting 视同可投递，`src/main/scala/nebflow/core/flow/MailIdleGate.scala:44-60`）。
- 「停手待命」Mail 唤醒 delegate 起的新 turn，其唯一动作就是回一句确认 → turn 自然结束。从引擎视角，**回合的自然结束 = 结果交付**：终文无条件进 settle → 判词链，没有「这是中间通信、不是交付」的通道。
- 平台给过区分工具——`delegate_report`（值域 finish|merge，`DelegateReportTool.scala:40-46`）——但它只是 registry 状态槽：不携汇报、不阻断终态、judge 在时只是 CONTEXT 一行先验（`DelegateJudgeMachine.scala:446-448`），judge 缺席时自申报才直接决定判词（`DelegateTerminal.scala:176-187`）；外部腿 selfReport 硬编码 None（`src/main/scala/nebflow/core/delegate/ExternalJudgeHook.scala:93`）。**回执与结果同权，是通道设计使然。**

## 问题④ 提醒注入现状

| 机制 | 触发 | 退避档 | 注入形式 | 出处 |
|---|---|---|---|---|
| held 回执催注（HeldDeliveryWatchdog） | idle 会话有到期 held 行 | **10min→20→30 封顶**，5 次穷尽 ≈2h | `DrainHeldReceipts` 命令强制补注 | `core/processor/HeldDeliveryWatchdog.scala:38-133`、`Defaults.scala:812-833` |
| bg-lifecycle nudge | 后台任务空闲 > 10min 地板 | **10min→20→40 封顶**，3 次后 Escalate | `ExternalEvent(source="bg-lifecycle")` system-reminder | `core/processor/BgLifecycleReconciler.scala:141-148,262-271`、`Defaults.scala:875-884` |
| crash/empty-burn nudge | 子 actor 崩溃或空烧 | 重启延迟 5s→10→60s（每代一次） | `UserInput(nudgeText)` | `agent/BackoffSupervisor.scala:795-802,915-917` |
| merge-turn 放行通知 | 队列 grant | 非退避型（事件+30s care tick） | `UserInput(eventType="merge-turn")` | `core/delegate/MergeQueue.scala:861-884` |

- **没有 10s/30s/60s 短档退避提醒**；所有提醒类首催档 ≥10min，秒级数字只是 respawn 延迟或扫描节拍。
- delegate 回合结束后的空等期：**纯事件驱动**。Waiting 停靠的唯一看护是 `careWaitingDock` 活性判据（活着=零动作，`agent/../TaskStuckWatcher.scala:853-879`）；held 台账空 = 零动作。「无超时」是明文红线（`HeldDeliveryWatchdog.scala:25-26`）。
- **LLM 选择性注入有现成先例**：`MailImportanceJudge`（一次 LLM 调用判 DELIVER/DROP，正文截 4KB、30s 超时、失败降级搁置，`src/main/scala/nebflow/perception/MailImportanceJudge.scala:10-65`）；JeV/SmallJudge 判定机（`DelegateJudgeMachine.scala`）则是两层判定的完整骨架。做「JeV 判断要不要催」直接套这两件。

## 设计草案（对齐作者方向）

### 案A：显式待命状态（回执 ≠ 完工）

- 新增信号通道：`delegate_report` 扩 `category="standby"`（detail=在等什么），或 spawn brief 约定待命回执格式；**判定机/确定性预检优先读该槽**——standby ⇒ 回合收尾时**跳过终态判词**。
- 落点：任务行保持 `running`；会话置 `AgentStatus.Waiting`（复用现有等待屏障语义：TaskStuckWatcher 豁免/MailIdleGate 可投递/面板透出「Waiting」，`AgentState.scala:234-245`——写点从 merge 停靠一腿扩到 standby 一腿）；registry 加 standbySince 字段，面板/任务列表可见「待命(等作者)」。
- 唤醒：Mail/续聊到达 ⇒ 新 turn 续跑；唤醒后若回合再结束且非 standby ⇒ 照常终判。
- 风险与对策：standby 被滥用成逃终判的出口 ⇒ 必须配案B的看护（退避提醒问「是否继续空等」）；无申报的待命回执（两个活体实例都没调 delegate_report） ⇒ 靠案B的 JeV 兜底识别。

### 案B：退避式空等提醒 / JeV 判断注入（两子案）

- **B1 机械退避**：delegate 回合结束后非终态挂起时，按 **10s→30s→60s** 档注入 `<system-reminder>`（「你已空等 N 秒——继续待命/交报告/说明阻塞」），复用 BgLifecycleReconciler Nudge 形态、新档默认值进 `Defaults.scala`。确定性、零 LLM 成本；缺点：对真在等作者的 delegate 是骚扰（10/30/60s 的档每分钟内敲三次）。
- **B2 JeV 判断注入**：回合收尾前先过一次 JeV/SmallJudge 单问句（复用 `DelegateJudgeMachine` 两层骨架与 30s 预算）：「该终文是任务性收尾还是待命/中间回执？」——待命 ⇒ 不终判、转案A的 Waiting 态，**并按判断置选择性提醒**（判官可顺带给出「该催/不该催」）；收尾 ⇒ 照常判词。成本 = 每回合至多一次 utility 调用；现成先例 MailImportanceJudge。失败语义沿用 fail-open（判不出来 ⇒ 按现状终判，不悬挂）。

### 对比与推荐

| 维度 | 案A 显式待命态 | B1 机械退避 | B2 JeV 判断注入 |
|---|---|---|---|
| 根治「回执=完工」 | ✅ 状态语义层根治 | ❌ 只提醒不改判 | ✅ 识别层根治 |
| 识别无申报回执 | ❌ 依赖模型自觉申报 | — | ✅ 自动识别 |
| LLM 成本 | 零 | 零 | 每回合 ≤1 次 utility 调用 |
| 误扰风险 | 无 | 高（每分三敲） | 低（判官说不催则不催） |
| 改动面 | 状态词表+report 值域+面板 | 新 nudge 族+Defaults | 判定机一问句+接线 |

**推荐：A + B2 为主，B1 作为 B2 不可用时的兜底档（10s/30s/60s 各一次后转 10min 档对齐现有提醒哲学）**。落地次序：① `delegate_report` 增 `standby` 值域 + 判词序读槽跳过终判（确定性、零成本、可用手册约束模型）；② 判定机加「收尾 or 待命」问句做无申报兜底；③ Waiting 写点扩面 + standbySince 透出；④ 退避提醒族。①单独即可挡住「作者 Mail 停手 → 确认回执被判 completed」这类已发生场景的手动路径，②挡住模型没申报的自动路径。

## 未决项与假设

- 作者 11:10 令的两个实例均为内部腿（engine delegate）；外部腿（CLI 子进程）selfReport 恒 None，案A的申报通道对外部腿需要 CLI 侧对等工具，草案默认先落内部腿。
- 「standby 挂起后任务行保持 running」与 TaskStuckWatcher/启动恢复（`SubAgentStartupRecovery` 在飞视野含 running）的交互需在实施方案里逐条对账，本审计未展开。
- 13446387 分支（0 领先提交）与其 worktree 仍未清理——按现状语义属 completed 遗留物，案A落地后这类「待命被误判 completed」的悬挂行可由重启对账批一并沉降。
