---
type: implementation-report
status: published
date: 2026-11-01
scope: delegate 完成判定改状态矩阵（complete 只作触发事件、与 delegate_report 状态槽取交集）——standby 申报停靠 / idle 未申报提醒 / merge FIFO / finish 唯一终判；含 10-31 丢失批回收与 S3/D1 对账
---

# Delegate 完成判定状态矩阵批（作者令 2026-11-01 18:35）

关联：[[20261031_112344_delegate-standby-semantics-audit__delegate-nebflow-c0a2b939]]（审计基线——本批 = 其案A+案B 荐案的落地）、[[20261101_165531_dispatcher-chain-lifecycle-rca__kernel]]（S3 引擎闸——对账互补）、[[index]]。

## 状态矩阵（判定词序改版）

引擎侧 `complete`（回合结束）只是**触发判定的事件**，绝不直接等于完成；必须与 `delegate_report` 状态槽取交集。判读序从旧「漂移闸＞预检＞判定机＞自申报＞兜底 completed」改为：

**状态槽闸 ＞ 漂移闸 ＞ 确定性预检 ＞ 判定机 ＞ 自申报 ＞ 兜底 completed**

四组合（`src/main/scala/nebflow/core/delegate/DelegateTerminal.scala` onCompleted 矩阵前置段）：

| complete × 申报槽 | 处置 | 钉死 spec |
|---|---|---|
| **standby**（申报待命） | Outcome.Standby——跳过终判、零判词行、dock 停靠（standbySince + Waiting 态 + 任务行「待命」徽标）；防退化闸（ahead>0 拒停靠）照常在位 | `DelegateTerminalStandbySpec` ①②④ |
| **无申报**（idle） | Outcome.NudgeIdle——system reminder 提醒补报，**不终判**（旧 JeV 兜底在此退役）；退避 10s/30s/60s→10min×3（`DelegateStandbyCare.IdleNudge`，双语在册）；链在飞 × 未申报（dispatcher 断供形态）同腿提醒 | `DelegateTerminalMatrixSpec` ②、`DelegateTerminalPrecheckSpec` ①②④ |
| **merge** | 既有 MergeQueue FIFO（入队 + 单持有人 + 任务行 ready-to-merge） | `DelegateTerminalMatrixSpec` ③ |
| **finish** | **唯一允许判完成的组合**（判定机缺席时 = 自申报背书平线 completed，source=judge） | `DelegateTerminalMatrixSpec` ④、`DelegateTerminalPrecheckSpec` ④(b) |

保留面（作者令「不许静默丢失」）：queueFlip 翻转（completed × worktree × ahead>0 ⇒ 翻 ready-to-merge 自动入队）与零领先降级回归绿（MatrixSpec ⑤）；合并轮豁免——引擎授命的合并执行窗里槽位（含陈旧 standby 申报）不得拦截，机械验收闸照常（MatrixSpec ⑥）。唤醒续跑：Mail 注入点 `clearStandby`（`MailTool.scala` deliverToDelegate live 腿）双槽复位 ⇒ 唤醒回合无新申报仍归提醒腿、有 finish 申报正常判（StandbySpec ③）；新申报覆写旧停靠（updateReport 非 standby 清停靠槽，StandbySpec ④）。

unjudged 诚实标注在矩阵下的可达角收窄为：**未申报 × ahead>0 × 无 worktree × 判定机缺席**（防退化进链 + 自申报缺席）——`DelegateTerminalPrecheckSpec` ③（真仓夹具钉死：completed (unjudged — judge unavailable) + source=unjudged + 零翻转零停靠）。finish 申报在场 = 自申报背书，不再标 unjudged。

## 10-31 批查找结论（作者令「丢失批回收」）

丢失批 = **delegate-nebflow-164aec7e**（taskId 1203，10-31 11:40 派「案A 实施批」）：分支 delegate/delegate-nebflow-164aec7e 零领先提交、实现以**未提交形态**躺在 worktree `/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-164aec7e`（20 改 + 4 新文件），diff 已导出 `/tmp/standby-salvage-164aec7e.patch`。本批**已移植**（不重做）并按新令扩面：DelegateReportTool 三态、DelegateStandbyCare（新）、DelegateTerminal 矩阵前置段、BackoffSupervisor 两腿、MailTool 唤醒复位、DelegateRevival 待命豁免、Defaults props、WebSocket/前端「待命」徽标与快照复原。审计实例 13446387 的「确认回执→假 completed」结构性默认自此由矩阵②根治。

## S3/D1 对账（与 [[20261101_165531_dispatcher-chain-lifecycle-rca__kernel]] 的 S3 引擎闸）

- **本批（D1 申报通道 + 提醒面）**：接住「链在飞 × 未申报」——MatrixSpec ②钉死 chainsInFlight=true × 无申报 ⇒ NudgeIdle（零终态、零父投递），提醒文案点名在飞链数（chainsInFlight 计入 payload）；
- **S3 引擎闸**（已合 main 的独立批）：链在飞的宿主侧硬闸，与本批互补不重叠；
- 分工边界：申报通道腿不判链生死、S3 闸不补申报提醒——合一包交付即完整闭环。

## 验收对账（作者令五条）

1. ✅ 矩阵四组合各钉引擎级 spec：`DelegateTerminalMatrixSpec`（②③④⑤⑥）+ `DelegateTerminalStandbySpec`（①②③④）+ `DelegateStandbyCareSpec`（退避/双语/槽 round-trip）；状态翻转与降级回归绿（⑤ + DelegateTerminalSpec 既有⑨组）。核心四件 **19/19 全绿**。
2. ✅ system reminder 文案双语在册：`DelegateStandbyCare.nudgePayload`（en + zh「【状态矩阵】/【待命看护】」）——`DelegateStandbyCareSpec` nudgePayload test 钉死（`<system-reminder>` 形态、短退避链 + 10min×3 长档、末次点名零超时尾）。
3. ✅ 任务行/详情卡显「待命」态：standbySince 透出（`WebSocketRoutes.activeAgentEntryJson` / `WsSessionChatHandlers` 快照缝）；badge「待命（等作者）」+ tooltip=申报的在等什么（`bgAgentWorkStrip.js` Waiting 分支两型、`main/bgAgents.js` 任务行、`locales/en.js`/`zh-CN.js`）。实拍 5 张全 PASS（20/20 断言）：
   `evidence/20261101_delegate-standby-matrix__delegate-nebflow-c6c62891/shots/`（desktop light/dark、mobile-375 light/dark、desktop-light-standby-row-closeup；harness `tests/delegate-standby-shots.mjs`——JS click 经 `#bgagent-indicator`，手机端 CSS priority-hide 下 playwright click 不达）。
4. ✅ 本件即批报告（wiki-docs 纪律：frontmatter + wikilink；矩阵表、10-31 批查找、S3/D1 对账、开放项俱在）。
5. ⏳ 真实合并 main 的 commit hash——合并落地后在终稿报（走 merge queue，勿直接合）。

## 开放项（不阻塞本批）

- **外部腿（external executor / ExternalSessionRunner / ExternalJudgeHook）不经本矩阵**：onCompleted 仅 source=delegate 触发，external 保持原语义——申报通道给外部腿的覆盖归独立批。
- **chainsInFlight=true 的挂宿 e2e**：spec 层已钉（MatrixSpec ②），挂宿端到端（真宿主 + 断供 + 提醒注入实测）归 S3 批验证面。
- **unmerged-work 注记行在就地座位（无 worktree）不可达**（aheadOfBase 只对 worktree/ReadyToMerge 座位计算——既有语义，本批未动）：矩阵下未申报×ahead>0×就地座位只有 Release，注记行只剩 worktree 形态；如需就地座位的诚实化注记归后续小批。
- **DelegateBudget 已是墓碑 no-op**（无 wall-clock 超时）——「待命无超时红线」天然满足；若未来重启预算机制，DelegateStandbyCare 的 Waiting 腿需豁免复核。
- **复活×矩阵的新语义（本批顺带钉死）**：复活的 delegate 续跑回合**无申报**（如 stub 回声形态）收尾 ⇒ 提醒腿（NudgeIdle），绝不自动 completed——父收结果从「复活即自动判完」变为「等 delegate 真申报 finish」（DelegateRevivalSpec V1 已改写为矩阵语义）。复活的 nudge 文案可考虑补一句申报义务提醒（提示词面，归后续）。
- **邮箱化批遗留红顺带修复**：DelegateRevivalSpec V1/V2 原断言已退役的 F2 队列脸（CompactionQueueStore），delegate 轨唯一投递面实为持久邮箱（SessionMailbox）——本批改写断言对齐（非矩阵面，属邮箱化批收尾）。

## 验收对账补记

全量回归 **99/99 全绿**（四核心件 19 + Terminal 24 + Revival 10 + MergeQueue/Acceptance/FinishWorkResult/EmptyBurn/PromptFace/ReportTool/Registry 合计 99，`Passed: Total 99, Failed 0`）。

## 实现清单（依据路径）

- 引擎：`DelegateTerminal.scala`（Outcome.Standby/NudgeIdle、矩阵前置段、verdictChain 逐字迁入 chains 入参、parkStandbyOutcome/standbyGate/unmergedAheadOf、onTerminalCleanup+standbyClear）、`DelegateStandbyCare.scala`（新）、`DelegateRegistry.scala`（standbyAfter mode + park/clear 单写）、`DelegateReportTool.scala`（+standby 三态）、`DelegateTool.scala`（BriefReportLine 三态）、`MailTool.scala`（注入即 clearStandby）、`BackoffSupervisor.scala`（Standby/NudgeIdle 两腿）、`DelegateRevival.scala`（standbySince 豁免收殓）、`Defaults.scala`（四 props）、`WebSocketRoutes.scala`/`WsSessionChatHandlers.scala`（standbySince 透出）。
- 前端：`bgAgentWorkStrip.js`、`main.js`、`main/bgAgents.js`、`locales/en.js`、`locales/zh-CN.js`。
- 测试：`DelegateTerminalStandbySpec`、`DelegateTerminalMatrixSpec`、`DelegateStandbyCareSpec`（新）；`DelegateTerminalPrecheckSpec`、`DelegateTerminalSpec`、`BackoffSupervisorEmptyBurnSpec`、`DelegateRevivalSpec`、`PromptFaceSplitSpec`、`DelegateReportToolSpec`（改）；harness `tests/delegate-standby-shots.mjs`。
