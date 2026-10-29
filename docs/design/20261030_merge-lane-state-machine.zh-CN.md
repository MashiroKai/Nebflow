---
type: design
status: current
title: 合并道状态机总图——delegate 合并排队「状态×迁移×守门」收口件（投递可靠性批 2026-10-29）
date: 2026-10-30
batch: delegate-nebflow-f2451d50
tags: [merge-queue, delegate, delivery-reliability, merge-lane]
related:
  - "[[20261028_213056_delegate-dual-mode-redesign__delegate-nebflow-192d10a5.zh-CN]]"
---

# 合并道状态机总图（病灶三型收口件）

作者令 2026-10-30 00:13：「三型修完请给一张合并道状态机总图（状态×迁移×守门）作为收口件」。本图是投递可靠性批（f2451d50）实施后的**权威形态**；代码锚 = `MergeQueue.scala`（账本 + 道闸）、`DelegateTerminal.onCompleted`（结算分类）、`BackoffSupervisor`（停靠/重排腿）、`TaskStuckWatcher.scan` 尾部（careTick 拍）。

## 一、状态（成员账本 `merge-ledger.json`；单飞行 = 每 projectKey 至多一个 `turn-granted`）

| 状态 | 含义 | 面板可见 |
|---|---|---|
| `waiting` | 排队（等窗/等道净/等净空） | 「排队第 N 位」 |
| `turn-granted` | 持窗：合并轮指令已注入属主，轮在飞 | 「窗口持有中」 |
| `merged` | 终态：合并真落地（账实核对或回合确认） | 已合并 |
| `owner-gone` | 终态：属主失联关账，需 dispatcher 处置 | 需处置 |
| `failed` | 终态：合并回合**真**拒绝（道净/己方残留），需处置 | 需处置 |

## 二、迁移 × 守门（判定单点全列）

```
                       ┌────────────────────────────────────────────────────────┐
                       │                    (spawn 侧)                          │
                       ▼
                 ┌───────────┐  工作轮终态判词 ready-to-merge
                 │  waiting   │◀────────── DelegateTerminal.holdForMerge（入账 + pump）
                 │           │◀────────┐
                 └────┬──────┘         │
                      │ pump(grant)    │
                      ▼                │
        ┌──────────────────────────────┤
        │ 守门序（grant，锁外逐项）：     │
        │ ① 账实核对 alreadyMerged       │  已并 ⇒ closeMerged(fact-check)
        │ ② 道闸 laneVerdictFor【新】    │  见下「道闸四态」
        │ ③ 净空闸 baseReady (fail-soft) │  方向闸 ⇒ requeueBlocked + #943 通知
        │ ④ notifyTurn（通道失败⇒owner-gone）│
        └──────────────┬───────────────┘
                       ▼
                ┌──────────────┐  合并轮在飞（属主自己 git merge --no-ff）
                │ turn-granted │
                └──────┬───────┘
                       │ 合并轮终态结算（DelegateTerminal.onCompleted × 机械验收闸）
                       ├── 验收通过 ──────────────▶ closeMerged(merge-round) ─▶ pump
                       ├── 验收拒绝 × 道忙【新】───▶ RequeueForLane：requeueForLane
                       │                             （waiting，会话停靠保活）──▶ 道净重排
                       ├── 验收拒绝 × 道净/己方残留 ─▶ closeFailed(原因全文+通知)
                       └── 回合外终态（取消/超时/死）─▶ onTerminal(None)：
                                                      账实核对先（被并⇒merged），
                                                      否则 markOwnerGone【孤儿 abort 随行】
```

## 三、道闸四态（`classifyLane` 纯判读；判据 = MERGE_HEAD × 属主活性）

| MERGE_HEAD | 归属 | 判读 | 处置 |
|---|---|---|---|
| 缺席 | — | `Clear` | 放行授窗 |
| == 己分支 tip | 自己 | `OwnLeftover` | 放行（brief 教学 abort+重试一次，属内自愈）；结算再拒 ⇒ failed |
| ≠ 己 tip，属开放成员，其会话**活** | 他批 | `BusyLive` | **不授窗**——requeueBlocked + #943 通知（病灶②预防位） |
| ≠ 己 tip，属开放成员，其会话**死** | 他批 | `BusyDeadHolder` | 不授窗，等一拍 careTick 收殓（abort 随行） |
| ≠ 己 tip，不可归属（人/未知分支） | — | `Foreign` | 不授窗 + 通知 + N 拍升级；**绝不 abort**（人的手工合并零触碰线） |

## 四、窗口释放（病灶③）——两个事件绑定，永不依赖属主下一拍还窗

1. **合并 commit 落 main**：careTick 每拍对持窗成员跑账实核对（`alreadyMerged`）⇒ 命中即 `closeMerged(fact-check-care-tick)` + pump 下一位（R1 批 109d8ab6 形态：commit 23:51:44 落、属主死、窗滞留——现绑死在「事实」上）。
2. **属主终态**：回合终态链（notifyParentAndStop → onTerminalCleanup → MergeQueue.onTerminal）＋ careTick 持窗尸体判死（childAlive 探针）⇒ owner-gone / merged（账实核对先行）。

面板「窗口持有中」标签与队列真实属主**同源**（`MergeQueue.queueViewOf` 读同一账本，无第二状态源）。

## 五、孤儿合并（病灶①）收场协议

- **判**：`markOwnerGone`（回合外终态 / careTick 尸体判死共用）时读主工作区 MERGE_HEAD；
- **abort 条件（唯一）**：MERGE_HEAD == 本成员分支 tip（引擎所授合并轮的死属主残留——恢复语义，作者裁定 2026-10-29 23:55「倾向=git merge --abort 回滚 + 该批合并轮作废重排」）；
- **零触碰**：MERGE_HEAD 指向他人分支/不可归属 ⇒ 不动（人的手工合并），只落 `orphan-abort-skipped` 审计行；
- **审计**：`orphan-abort` / `orphan-abort-skipped` ChainStore 行 + WARN 日志，全程可追。

## 六、投递面配套（本批其余修复，与道闸正交）

- **必达**：ready-to-merge 停靠点先投工作结果全文（停靠投递 + 交付回执）；合并轮 Release 附页据回执 ack 态去重 work-result 段（恰一次）；合并回执随后一行。
- **不过时**：ExternalEvent intake 身份判重（raw correlationId ∪ 回执归位 id）——已投递或在 pendingEvents 队列即丢弃 + `external-event-duplicate-suppressed` 审计；markDelivered/重放闸升级为身份键判读；cancel 回执单发。
- **可观测**：AgentControl list barrier 列 `o/h(+u)!N` 的 `!N` = 本会话名下未确认交付回执数（pendingDelivery）。
