---
type: report
status: done
date: 2026-11-03
tags: [delegate, standby, completion-notification, matrix]
---

# 会话四状态 × 完成通知矩阵（standby / merge × complete = 零注入零唤醒）

批次：`delegate/delegate-nebflow-3e517a60`，提交 `7f7349f73`（spec 先行，改前红）+ `d2d1a16a6`（修复 + 证据），已过独立验收（8 个 spec 43/43 全绿）。
矩阵单点定义见 [[20261103_191215_session-four-state-completion-matrix#② 四状态 ↔ 代码实体映射]]（本档即该机制的正文说明）。

## ① 作者裁定原文

**2026-11-03 18:14 四状态矩阵裁定**（逐字，代码注释同引于 `DelegateCompletionMatrix.scala:6-9`）：

> 我们有四个状态 idle、stand by、merge、finish。只有在 idle 状态下，进入等待（complete），才会有 system reminder 通知。它都已经报了 stand by 了，还通知什么？所以这里的四个状态 idle complete 通知，stand by/merge complete 不通知 finish complete 回报给主 agent。

**2026-11-03 18:16 加急令要点**：文档可后补，**最小可读版即可**，不要为文档卡住交付 —— 故本档只保留决策表、改动锚点、原始证据行与风险，不复制上游整篇报告。

## ② 四状态 ↔ 代码实体映射

| 会话状态（作者口述） | 代码实体 | complete 时决策 | 实际行为 |
|---|---|---|---|
| idle（回合收尾未申报） | `Outcome.NudgeIdle` 腿 + `DelegateStandbyCare.IdleNudge` caretaker | `InjectReminder` | 唯一会发 system reminder 的状态：退避序列 + 答复下限 |
| stand by（已显式申报、已停靠） | `Outcome.Standby(_, declared=true)`、`DelegateStandbyCare.Declared` | `QueueSilently` | **零 caretaker / 零记账 / 零注入**，完成事件静默入队 |
| merge（等合并 / 道忙重排） | `Outcome.HoldForMerge`、`Outcome.RequeueForLane` | `QueueSilently` | 零完成提醒；`MergeQueue.notifyTurn`（引擎授命的合并轮 brief）保留 |
| finish（终态） | `Outcome.Release` / 终态腿 | `ReportToParent` | 既有 `notifyParentAndStop` 主路径，零额外 reminder |

决策单点（纯函数，真源）：`src/main/scala/nebflow/core/delegate/DelegateCompletionMatrix.scala:66-70`（`decide`），审计标签 `:73-77`（`describe`）。spec 逐行锚：`DelegateCompletionMatrixSpec.scala:26-36`（四行各一条断言）、`:38-59`（总量不变量：`InjectReminder` 恰 1 行、`ReportToParent` 恰 1 行）、`:63-86`（standby 行断言**接线效果**：`arm(Declared)` 后 `caretakerLive=false`、`careStateOf=None`；`arm(IdleNudge)` 后 `caretakerLive=true`）。

## ③ 改动清单（文件:行号）

真接线点（`BackoffSupervisor` 五条腿，均实调用 `decide(...)` 而非装饰）：

| 文件:行 | 改动 |
|---|---|
| `core/delegate/DelegateCompletionMatrix.scala`（新增 79 行） | 四状态 × 决策的纯函数真源（`SessionState` / `NotifyDecision` / `decide` / `describe`） |
| `agent/BackoffSupervisor.scala:208-219` | finish 腿接 `decide(Finish)`，随后既有 `notifyParentAndStop` 照旧（`:218`） |
| `agent/BackoffSupervisor.scala:250-266` | `HoldForMerge` 腿接 `decide(Merge)`（本腿零注入的机械自证） |
| `agent/BackoffSupervisor.scala:341-353` | `RequeueForLane` 腿接 `decide(Merge)` |
| `agent/BackoffSupervisor.scala:432-444` | Standby 腿：删 `arm(Declared)` 调用，改 `decide(Standby)` 校验 + 审计行 |
| `agent/BackoffSupervisor.scala:512-528` | `NudgeIdle` 腿：`decide(Idle)` ⇒ `arm(IdleNudge)`（唯一存活的武装点 `:522`） |
| `core/delegate/DelegateStandbyCare.scala:237-262` | `arm(kind=Declared)` ⇒ 只清槽（撤 `arms`/`states`）+ 一条 info 审计行，不建 caretaker / 不写 `CareState` / 不起 fiber |
| `core/delegate/DelegateStandbyCare.scala:177-192` | `armDecision` 删「换型 ⇒ StartFresh」，改单调 `ResumeAt(attempt+1)`（kind 只作记账标签） |
| `core/delegate/DelegateStandbyCare.scala:74-76` | 新增纯函数 `effectiveDelay(attempt, delays, longMs, floorMs, answers)`：`answers>0 \|\| attempt>=2 ⇒ max(base, floor)` |
| `core/delegate/DelegateStandbyCare.scala:354-367` | loop 用 `effectiveDelay` 取 sleep + `slotStillCurrent` 槽校验 + `answer floor applied` 审计行 |
| `core/delegate/DelegateStandbyCare.scala:427-432` | `stillWaiting` 增 `standbySince.isEmpty` 判据（申报待命 = 静默信号，caretaker 自我了断） |
| `shared/Defaults.scala:1008-1009` | `DelegateStandbyNudgeAnswerFloorMs`（prop `nebflow.delegate.standby.answerFloorMs`，缺省 600000ms = 10min） |
| `core/tools/DelegateReportTool.scala:195` | standby 回执文案改为「Declared standby gets NO reminder and NO wake-up（作者 18:14 矩阵）」 |
| `core/tools/MailTool.scala:1296-1301` | 注释同步：Mail 到达清 `standbySince`，caretaker 以其置位为静默信号 |
| `reports-evidence/20261103_standby-matrix/` | red.log / red-compile.log / green.log / scalafmt-check.log / scalafmt-only.log / mail-spec-baseline.log |

## ④ 机制说明

**为什么申报 standby / merge 停靠 ⇒ 零注入零唤醒**：standby 与 merge 都是「会话**已经知道自己要等**」的状态——等待的必要性由申报本身宣告过了，再发「你还在等吗」的 reminder 只是噪声（作者原话「都已经报了 stand by 了，还通知什么」）。故这两行决策为 `QueueSilently`：完成事件静默入队，会话在**下一次自然醒来**（Mail 到达 / 新指令 / 作者续聊）时才看见它；非提醒副作用（registry `Waiting` 快照、WS `delegateWaiting`、任务行状态、budget 记账）一律照旧。finish 是终态，走既有父投递主路径，也不加 reminder。

**唤醒面不依赖 nudge（零提醒不活锁）**：Mail 到达（`MailTool.scala:1331 clearStandby` + `UserInput` 注入）、链节点完成回流（`DelegateFlowHosts.scala:186-192` → `DelegateNotify.inject` → `ImmediateInput`）、重启续醒（`SessionResumption.scala:337-345`）、终态清算（`DelegateTerminal.scala:922`）；standby 腿不注册任何墙钟（对比 merge 腿的 `merge-window-timeout`），`PostMergeCleanup.scala:72-74` 对 `standbySince` 现场一律保留 ⇒ 无墙钟判死。

**nudge 只剩真停滞信号**：idle 腿的 reminder 只服务「回合收尾却什么都没申报」这一种真停滞。两道止血 ——（a）`armDecision` 单调续档：同一待命期内重申/换型不再把退避序打回第 1 档（旧行为 = 首档无限复读 = 作者报障的分钟级连戳形态）；（b）`effectiveDelay` 答复下限：第 2 档起（或已答复过）的相邻提醒间隔不低于 `answerFloorMs`（缺省 10min），序列穷尽即静默。

## ⑤ 证据

**RED（改前红，真实 munit 输出）**

`reports-evidence/20261103_standby-matrix/red.log:107`：

```
[error] Failed: Total 10, Failed 3, Errors 0, Passed 7
```

关键三行（`red.log:16` / `:36-40` / `:86`）：

- `① armDecision … 换型不回首档` ⇒ obtained `StartFresh()`，expected `ResumeAt(attempt = 3)`
- `④ 申报 standby ⇒ 零注入` ⇒ 实际注入 4 条 `[delegate-standby] You are parked in standby. Reminder #1..#4`
- `⑤ 实测间隔` ⇒ `[standby-floor-spec] rows=3 gaps=List(50, 51)`（无 floor，正是分钟级连戳形态）

编译红 `red-compile.log`（矩阵独点与纯函数改前不存在）：`Not found: DelegateCompletionMatrix` / `value effectiveDelay is not a member of object …DelegateStandbyCare` / `value DelegateStandbyNudgeAnswerFloorMs is not a member of object …Defaults`，共 8 errors。`git diff --stat 7f7349f73^ 7f7349f73 -- src/main` = 0 行 ⇒ 提交①只含 spec，非假红。

**GREEN（`green.log:52`，命令 `sbt -batch "compile" "testOnly DelegateCompletionMatrixSpec DelegateStandbyCareAnswerSpec DelegateStandbyCareSpec DelegateTerminalMatrixSpec DelegateTerminalStandbySpec"`）**

```
[info] Passed: Total 24, Failed 0, Errors 0, Passed 24
```

**nudge 间隔断言**（同一 spec 红→绿）：`[standby-floor-spec] rows=3 gaps=List(604, 602)`（`green.log:27`），配套审计行 `answer floor applied … (attempt=2/3, base=50ms, floor=600ms ⇒ sleep=600ms)`（`green.log:24-25`）；spec 用 prop 把 floor 压到 600ms 提速，生产缺省 600000ms。

**独立复验**（验收节点自跑，非上游截图）：8 个 spec 43/43 全绿（`Passed: Total 43, Failed 0, Errors 0, Passed 43`），其自有跑次 `gaps=List(612, 608)`；`touch` 全部源文件后重编再跑仍 43/43。

**提交 hash**：`7f7349f73`（test: spec 先行，改前红）/ `d2d1a16a6`（fix(delegate): 零注入矩阵 + nudge 下限止血 + 六份证据）。`git diff --name-only main...HEAD` 只含 `src/` 与 `reports-evidence/`，`ops/` 零改动。

## ⑥ 残余风险与未完成项

1. **矩阵在 merge / finish 两行是 warn-on-drift 守卫，不是行为闸**：把矩阵改成 `InjectReminder` 只会打 WARN、不会真注入；这两行的「零提醒」本质上由腿内无注入注入点的结构保证。要让它成为真闸，腿应按决策分支而非恒 `IO.unit`。
2. **floor 在 `attempt>=2` 无条件生效**：缺省档位（10s/30s/60s + 10min×3、floor 10min）下 30s/60s 两档实际被 10min 吞掉，idle 阶梯变成 10s → 10min…。满足「不出现 1 分钟连戳」，但比裁定字面「答复无变化后」更宽。
3. **RED 拆成两条**：行为红（`red.log`）那一跑矩阵 spec 不在树内（它改前编不过），矩阵的红由 `red-compile.log` 承担，未在同一跑内覆盖。
4. **`MailDelegateContinuationDeliverySpec` (4)(5) 两条红不属本批**：上游用纯基线（`mail-spec-baseline.log`：同样 `waitUntil: condition not met within 15 seconds`）证其既存；验收节点未能在 merge-base 复跑佐证（禁止 checkout 家族命令），保留为未闭环项。
5. **`arm(Declared)` 分支仍在（零动作）**：现无任何生产调用点（唯一存活武装点 = `BackoffSupervisor.scala:522` 的 `IdleNudge`）；将来若有调用方传 `Declared`，会撤掉正在跑的 IdleNudge 槽。
6. **申报 standby 后若无任何入站事件，会话无限期静默**：这是裁定本意；可见性只靠 registry `Waiting` /「待命」徽标 + 任务行 running，无超时兜底。
7. **本档图谱接入**：本 worktree 的 `docs/` 是独立新 vault（仅此一篇），主 checkout 既有的待命语义审计文档不在本分支内，故本档唯一 wikilink 为自锚（见文首）。合入主干后应补双向链接到既有 standby 审计档与词表（`docs/glossary.md` 目前不存在，R3 概念首现检查未启用）。
8. **scalafmt 既存红不属本批**：`PerceptionService.scala:1211` / `PerceptionDaemonLifecycleSpec.scala:62`；本批 7 个文件已用 `scalafmtOnly` 单独格式化（exit 0）后重跑仍全绿。
