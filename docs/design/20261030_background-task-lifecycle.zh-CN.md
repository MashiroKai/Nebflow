---
type: design
status: draft
title: 后台任务生命周期管理——reconcile+监督混合骨架设计稿
date: 2026-10-30
batch: delegate-nebflow-129ce7ad
tags: [background-task, lifecycle, reconcile, supervision, observability, zero-timeout]
related:
  - "[[20261030_merge-lane-state-machine.zh-CN]]"
  - "[[held-receipt-drain-silence-spec.zh-CN]]"
  - "[[delegate-supervision-revival-design.zh-CN]]"
  - "[[turn-liveness-spec.zh-CN]]"
  - "[[20261026_detached-apply-restart-update-spec.zh-CN]]"
  - "[[20261027_cli-isolation-and-detached-restart-spec.zh-CN]]"
---

# 后台任务生命周期管理（reconcile + 监督混合）

> **批性质**　设计批（只设计不实施，呈作者拍板）。作者令 2026-10-30 11:21 逐字：「后台任务管理不足，要借鉴成熟方案定一个好方案落地」。本稿给骨架定案 + 四事故逐条对策 + 参照四家对照 + 分期迁移，实施另批。
>
> **红线照旧**　零超时——正确性靠监测与退避表达，不设 wall-clock 判死。本稿全部「挂死检测」一律是**启发式健康轨**（空闲超基线 → nudge 升级），任何升级动作都不杀任务、不杀会话、不改终态语义；既有 wall-clock 腿（B2 停滞杀 / bg-wait cap）逐条自查定位见 §6，本设计**新增 wall-clock 判死数 = 0**。

目录：

- [0. 执行摘要](#0-执行摘要)
- [1. 今天四事故与逐条对策](#1-今天四事故与逐条对策)
- [2. 参照方案对照表（四家）](#2-参照方案对照表四家)
- [3. 定案骨架](#3-定案骨架)
- [4. 取消粒度与 death-watch 传播](#4-取消粒度与-death-watch-传播)
- [5. 与既有机制的接合（复用优先）](#5-与既有机制的接合复用优先)
- [6. 零超时红线全书自查](#6-零超时红线全书自查)
- [7. 迁移分期与每期验收](#7-迁移分期与每期验收)
- [8. 风险表](#8-风险表)
- [9. 未决项（需作者拍板）](#9-未决项需作者拍板)

---

## 0. 执行摘要

**一句话**：给 BgTaskRegistry 补一条**正交健康轨**（healthy→suspect→escalated，启发式、不判死）+ 一个 reconcile 控制器 fiber（期望态 vs 观测态永不停比对，姐妹 HeldDeliveryWatchdog 同构）+ 三通道齐平的单任务取消 + death-watch 终态沿 bg-wait 等待链传播——四事故逐条收口，零新超时。

三个支柱：

1. **状态机（FSM）**：任务终态轨 `pending → running → succeeded/failed/cancelled` 不动；新增**健康轨**叠加其上——`suspect`（空闲超 N×基线，启发式命中）→ `escalated`（催注 k 次无进展，升人到面板/编排者）。任何进展信号（输出行增 / CPU 增量 / 心跳 ack）即时降回 healthy；**重排不判死**： escalated 之后动作的全部正确性由「通知 + 账本 + 放行语义」表达，不由杀表达。
2. **控制器 fiber**：`BgLifecycleReconciler` 每拍（30s 档，与 `HeldDeliveryScanIntervalSec` 同款）扫全表，比对「期望态（在册未终态 ⇒ 等它的会话终将被唤醒/放行）vs 观测态（lastActivity/outputLines/CPU/心跳）」。决策面 `scanCore` 纯函数（spec 直测锚），执行面只有四种动作：`Nudge`（mailbox 级提醒）、`BadgeSync`（`heldReceipts` 徽标同款帧）、`Escalate`（红徽+编排者通知+审计行）、`ReconcileNotify`（death-watch 终态沿等待链注入）。**无 Kill 动作**。
3. **单一事实源投影**：健康字段进 `ActiveTask` 行本身，面板快照帧、`bgWaiting` 面、`bgHealth` 徽标帧全部读同一账本——禁第二状态源（与 `MergeQueue.queueViewOf` 同纪律）。

**显式不做**：不实施任何产品代码（本批纯设计）；不把注册表扩成通用 job queue（等待语义已由 bg-wait 屏障表达，YAGNI）；不动 B1/B2 判据、不动 bg-wait cap 值（2h）、不动合并道语义、不动 429 限流档位；不引外部调度器/数据库（持久面复用 BgTaskLedger 既有会话级账本）；不抄 Temporal 的 server 端集中 workflow 库。

---

## 1. 今天四事故与逐条对策

锚点基线＝本批 worktree HEAD。判读均直读代码核对。

| # | 事故（作者令原文） | 结构因（现状代码锚点） | 对策（落点期） |
|---|---|---|---|
| ① | dock 通知批的后台 playwright spec 挂死 2h29m、AskUser 批的隔离实例重启挂死 1h21m——会话「空闲等后台」在系统眼里是健康态，无人知晓无人纠 | 三道既有守卫全不覆盖此形态：**B1** idle 300s 已降「只提醒」（`shell.scala:1101-1130`，写 `bg-slow` 事件不杀不终态）；**B2** 要「零输出零 CPU」双零才杀（`shell.scala:1131-1142`），playwright 卡死形态（worker 忙等/周期性吐行）双零不成立 ⇒ 永不杀；**TaskStuckWatcher** 对 idle 永不判卡死（防误杀铁律，`TaskStuckWatcher.scala:24-26`）⇒ 等后台的会话结构上不可见；bg-wait cap 2h 到点强制终判只把注记写进转录（`BackoffSupervisor.scala:461-500`），**不唤醒任何人**；cap 后任务变 orphan 仍在册照跑（`BgTaskLedger.markOrphaned` 只入账不杀），面板仍显示 running | 健康轨 + 控制器：**suspect**（空闲 > max(基线×N, 10min)）→ `Nudge` 提醒到等待会话转录 + 面板黄徽；**escalated**（催注 k 次退避无进展）→ 面板红徽 + 编排者通知 + 响亮审计——挂死 30 分钟内**可见可纠**，不再 2h+ 无人知晓。隔离实例重启腿同理：restart 完成判定在「交接已触发」（`UpdateOrchestrator.scala:234-260`），后继起没起来无人看 ⇒ 死亡观测量（successor 活性/心跳连丢 K 拍）接入 reconciler 期望态比对（P3 把重启腿升格为在册任务，P2 先接属主失联腿）。（P0+P1，重启腿 P3） |
| ② | 面板只有「等后台任务 ×N」计数，任务健康/年龄/状态对编排者和用户都不可见 | 面板数据源 `bgWaiting` 只有 (jobId, description, startedAtMs) 三元组（`WebSocketRoutes.scala:3402-3409` → `bgAgents.js:133-153` 只渲计数+首条描述）；快照面 `activeTasksJson` 的 status 硬编码 `"running"`（`BgTaskRegistry.scala:443-470`）；行内本有 startedAtMs 但面板不渲年龄；健康判据（B1 传感器）写了审计事件**不回写行** | `ActiveTask` 行补 health 字段（lastActivityMs / outputLines / suspicionLevel / escalatedAt），快照帧与 `bgHealth` 徽标帧同源输出；面板行展开「任务名 + 年龄 + 健康色（绿/黄/红）」；节点 `bgWait` 徽标从纯文本升级为结构化（`NodeCanceller.setNodeBgWait` 面不动语义）。（P0） |
| ③ | 取消粒度粗：只能杀整个 delegate 会话，不能单独杀某条后台任务 | 单任务取消**只在面板本地腿存在**：WS `handleCancelBackgroundJob`（`WsSystemHandlers.scala:184-254`）⇒ `ShellSession.cancelBackgroundJob`（`shell.scala:337`），仅本地 shell；**远端设备任务零取消面**（`RemoteExecutor.scala:824-863` 只有 start/heartbeat/notify 三腿）；agent 面 `AgentControl` cancel = 整会话（`AgentControlTool.doCancel:874`）；节点面 `NodeCanceller.cancelNodes` = 整节点（可级联） | 单任务 cancel 三通道齐平：**(a)** WS 路由泛化——local 直调 + remote 经设备通道新 kind `bg-cancel`（spawn 同款 HMAC/nonce 面）；**(b)** `AgentControl action=cancel scope=bgTask jobId=…`，守卫沿用 `doCancel` 既有护栏（`chainReaches`/`managerAnchors`/`cancelable`）；**(c)** 会话/节点 cancel 语义逐字不变（`reclaimSession` 仍是终态收殓）。取消正确性 = 取消通知事件 + 账本既有腿，零新机制。（P2） |
| ④ | 任务终态不沿等待链传播——delegate 不知道等的东西已经死了 | 完成通知的唤醒腿只覆盖「事件可达」的会话（`BgNotifyDelivery` 三段：直投/激活/持久补投；idle 三唤醒点补投 `AgentIdle.scala:179,592,876`）；**等待对象自己死亡且未产生终态事件**时（隔离实例重启挂死、属主会话被收殓、远端设备掉线），bg-wait 链另一侧永久空等——bg-wait 屏障只等「任务完成回调」，没有「任务属主活性」这一观测维 | death-watch 轨：reconciler 对每个等待集做双向比对——(i) 等待集内任务**终态**（任意因）⇒ 既有通知腿已覆盖，补 `BadgeSync` 即可；(ii) 任务**属主失联**（心跳行连丢 K 拍 / 会话已收殓 / 重启相位 Completed 后 successor 活性证伪）⇒ 沿 bg-wait 链注入「等待对象已终止」终态通知（payloadHead 判重幂等，`BgTaskLedger` 既有判重面），挂起会话收到即**唤醒或放行**（节点闸复检腿 `NodeEngine` Completed 桥既有）。（P2，重启活性 P3） |

**对策共性**：四事故没有一条需要「新超时」——全部靠**把已有的观测信号（心跳/输出/CPU/账本）接进一个不停比对的控制器**，把已有的动作（提醒/徽标/通知/收殓）按阶梯编排。

---

## 2. 参照方案对照表（四家）

| 方案 | 核心机制 | 我们可抄什么 | 不抄什么及理由 |
|---|---|---|---|
| **Erlang/OTP 监督树** | let-it-crash + 重启策略（one-for-one/all、永久/瞬时）+ 异常升级（子树崩溃沿监督树逐层上传，根级决策） | 「异常升级」哲学正对我们的 escalated 轨：suspect 会话内自治（nudge），escalated 沿 bg-wait 链上报编排者——升级不就地判死。崩溃恢复机制**已内化**：`BackoffSupervisor` death-watch → 退避重启 → maxRestarts 熔断 → 父通知（`BackoffSupervisor.scala:12-28`），零新机制 | 不抄 actor 粒度监督（BEAM 进程=监督单元；我们的「任务」=注册表行+OS 进程，监督单元粒度不同，硬套会把 OS 进程塞进 actor 语义）；不抄重启策略枚举（重排语义已由合并道/节点闸表达，第二份策略表=漂移源） |
| **K8s reconcile 控制器** | 期望态 vs 观测态**永不停比对**；指数退避重试；**不判死**（eviction 由人/策略决定，控制器只报 status 与事件） | 控制器骨架整体形态：scan 循环 + 纯决策面（`scanCore`，与 `HeldDeliveryWatchdog.scanCore` 同构已是我们的事实标准）；status 投影单一事实源（面板/CLI 只读账本，无第二状态源——与 `MergeQueue.queueViewOf` 同纪律）；「重排不判死」直接是我们的红线措辞 | 不抄 declarative spec 全量面（K8s 期望态是 YAML spec；我们的期望态是隐式不变式「在册未终态 ⇒ 等它的会话终将被唤醒/放行」，无 spec 载体需求）；不抄 eviction/GC 自动清尸（收殓已有 `reclaimSession` + `NodeDestroyWindowMs` 销毁窗，第二份清尸逻辑=双头收殓） |
| **Temporal 持久执行** | worker 心跳超时判挂（server 侧）；workflow 状态持久化 + 事件重放恢复；**补偿非杀**（活动失败走补偿事务，不杀工作流） | 「补偿非杀」= 本设计 escalated 的动作哲学（通知/账本/放行，不杀）；心跳面**已有**（`startHeartbeat` 30s→60s→120s 退避，`shell.scala:885-909`；远端 `startRemoteHeartbeat`）；持久执行**已有迷你版**（`BgTaskLedger` 落账 + idle 三唤醒点补投 = 会话级重放） | 不抄 server 端集中 workflow 数据库（我们是单引擎进程内账本+会话级磁盘账，量级匹配，引外部库=运维面净增）；不抄 workflow DAG 编程模型（agent 回合制事件驱动不是工作流图，硬套=给每个后台任务造状态机编排税） |
| **Agent harness 显式注册表**（DeepSeek/OpenHands/SWE-agent 公开材料同源的通用做法，无内部细节可引，按通用模式写） | 后台任务**显式登记**进注册表；任务可查（列表/状态/年龄）；可**单独取消**；完成事件**回注**创建它的会话（等待方显式等待而非轮询） | 注册表本体已有（`BgTaskRegistry`）且面已不小（快照/来源/设备/输出留存）——本设计补它缺的四格：健康轨（行内 suspicion 字段）、面板投影帧、远端单任务取消面、终态沿等待链传播。这正是「借鉴成熟方案」的主要落点 | 不把注册表扩成通用 job queue（提交/调度/优先级是另一套复杂度；等待语义已由 bg-wait 屏障+账本表达，通用化=YAGNI）；不抄「轮询式健康检查脚本」形态（我们的观测信号全是事件/心跳既有面，加轮询脚本=双头判据） |

**结论**：骨架推荐 = **reconcile（K8s 控制器形态）+ 监督（OTP 升级语义）混合**，Temporal 的「补偿非杀」作动作哲学，harness 注册表作落地载体。四家没有一家需要整搬——我们缺的不是机制，是**把既有机制按阶梯接起来的控制器**。若作者有更优论证可翻案，但四事故对策表（§1）的每条对策必须仍能被新骨架表达。

---

## 3. 定案骨架

### 3.1 任务 FSM（终态轨 × 健康轨正交）

```
终态轨（既有，逐字不动）：
  pending ──spawn/握手──▶ running ──完成──▶ succeeded
                                   ├─失败──▶ failed
                                   └─取消──▶ cancelled（人工/收殓）

健康轨（本设计新增，正交叠加，永不改终态轨）：
  healthy ──空闲>max(基线×N,10min)──▶ suspect ──催注k次退避无进展──▶ escalated
     ▲──────────────── 任何进展信号（行增/CPU增/心跳ack）────────────────┘
                          （重排不判死：降回 healthy，停滞计数清零）
```

- **suspect**：启发式命中（见 §3.3 判据）。动作 = `Nudge` 提醒 + 面板黄徽。进程照跑、会话照等、零杀。
- **escalated**：催注 k 次（指数退避）仍无进展，或空闲 > 基线×M。动作 = 面板红徽 + 编排者通知 + 响亮审计行。**仍不杀**——杀是人工/编排者决策（未决项 ①）。
- 健康轨状态**落盘**（扩展 `BgTaskLedger` 条目或节点持久字段，见 §8-R2），重启后可恢复——对齐「进程内存态重启即清」的既有诚实话术（`BgTaskRegistry.scala:90`）。

### 3.2 控制器 fiber（`BgLifecycleReconciler`）

姐妹 `HeldDeliveryWatchdog` / `FreezeScheduler` / `TaskStuckWatcher` 三件套成四件套，工程约束逐条继承（错误自愈吞 scan 异常防 fiber 崩、`>>` 递归防栈溢出、决策/执行分离）：

| 件 | 职责 | 锚点先例 |
|---|---|---|
| `run` / `scan` | 每拍扫 BgTaskRegistry 全表 + 各会话等待集 | `HeldDeliveryWatchdog.run:77-82` |
| `scanCore`（纯函数） | (sessionId, tasks, waitSets, heartbeatRows, now) → List[ScanAction]——spec 直测判红锚 | `HeldDeliveryWatchdog.scanCore:59-74` |
| 动作 `Nudge` | mailbox 级提醒注入等待会话（ExternalEvent，payloadHead 判重幂等）；退避指数（首催 10min 档，同 `HeldDeliveryFirstRetryMs` 哲学） | `HeldDeliveryWatchdog` 催注腿 |
| 动作 `BadgeSync` | unread/suspect/escalated 计数写 registry 快照 + 计数变化广播 `bgHealth` 帧 | `heldReceipts` 徽标腿（`HeldDeliveryWatchdog.scala:119-133`） |
| 动作 `Escalate` | 面板红徽 + 编排者可见通知 + `bg-lifecycle-escalated` 审计行（FlowMapEventLog 同款） | 升级 = OTP「异常升级」语义 |
| 动作 `ReconcileNotify` | death-watch：属主失联 ⇒ 沿 bg-wait 链注入终态通知（§4.2） | `BgNotifyDelivery` 三段腿 |

**控制器永不产出 Kill 类动作**——这条写进 `scanCore` 的返回类型（`sealed trait ScanAction` 无 Kill case），让编译器守门红线。

### 3.3 观测信号与基线（全部既有面，零新传感器）

| 信号 | 既有来源 | 用途 |
|---|---|---|
| lastActivityMs（输出尾行时刻） | `JobHealth.lastActivityMs`（`shell.scala:99`） | suspect 主判据（空闲时长） |
| 输出行数 | `BgTaskOutputStore.lineCounts`（已在快照帧使用，`BgTaskRegistry.scala:443-470`） | 进展信号 |
| CPU 增量 | `sampleProcessCpuTime`（`shell.scala:544-560`，B1/B2 同款） | 进展信号（豁免「无输出但 CPU 忙」的编译类任务） |
| 心跳行 | `startHeartbeat` / `startRemoteHeartbeat` | 远端任务活性；心跳连丢 K 拍 = 属主失联判据之一 |
| 会话活性 | registry `lastActivityMs`（agent 侧事件戳） | 等待会话是否仍活着 |
| 基线 | 自基线：任务头 K 分钟的观测中位×系数（默认档提案 N=3、M=10、k=3，全部 prop 可调，prop 缝先例 `Defaults.BgGateWaitTimeoutMs`） | 判据参数 |

### 3.4 状态投影（单一事实源）

- 行内字段：`ActiveTask` 补 `lastActivityMs / outputLines / suspicionLevel / escalatedAtMs`（默认值兼容旧调用方，向后兼容铁律）。
- 帧面：① 快照帧 `activeTasksJson` 带健康字段（status 不再硬编码 "running"——改读行内实际态）；② 新 `bgHealth` 徽标帧（unread/suspect/escalated 计数，`heldReceipts` 同款形态）；③ `bgWaiting` 载荷三元组扩五元组（+年龄+健康色），`bgAgents.js` 行渲染同步。
- 节点面：`setNodeBgWait` 的 desc 升级为结构化字段（任务名+年龄+健康），徽标与面板同源。

---

## 4. 取消粒度与 death-watch 传播

### 4.1 单任务 cancel（与会话 cancel 分离）

| 通道 | 现状 | 本设计 | 正确性表达 |
|---|---|---|---|
| 面板 WS | 仅本地（`handleCancelBackgroundJob`） | 泛化：kind=local 走 `cancelBackgroundJob` 直调；kind=remote 走设备通道新 kind `bg-cancel`（HMAC+nonce+确认帧，复用 `DeviceDelegate` 安全面） | 取消通知 ExternalEvent（`source=background-task`）+ `BgTaskOutputStore.finalizeTask`——既有腿 |
| Agent 面 `AgentControl` | 仅整会话 cancel | 新增 `action=cancel scope=bgTask jobId=…`：`chainReaches` 护栏（只能取消自有/可达链）+ `managerAnchors` 根桶守卫 + `cancelable` 判活——**三个守卫复用不新写** | 同上 |
| 会话/节点面 | `reclaimSession` / `cancelNodes`（可级联） | 语义逐字不变 | `reclaimSession` 全量收殓仍是终态腿 |

原则：**单任务取消是「精确手术」，会话取消是「终态收殓」**——两者共享通知与账本腿，不共享杀伤范围。

### 4.2 death-watch：终态沿 bg-wait 等待链传播

等待链今天形态：delegate/node 回合尾经 `BgTurnEndGate.onTurnEnd` 立持久栅栏（`BgTaskLedger.armBarrier`）→ bg-wait 屏障 hold 会话/节点完成闸（`BackoffSupervisor` bg-wait cap 腿、`NodeEngine` Completed 桥）→ 任务完成事件唤醒（`BgNotifyDelivery` + idle 三唤醒点补投）。

缺口（事故④）：链上等的是「完成回调」，但**等待对象可能以不产生完成事件的方式死亡**——属主会话被收殓、远端设备掉线、隔离实例重启挂死（restart 完成判定在「交接已触发」，`UpdateOrchestrator.scala:234-260`，后继活性无人观测）。

补法（双向比对，控制器每拍执行）：

1. **正向**：等待集内任务转终态（任意因）⇒ 既有通知腿覆盖唤醒，控制器只补 `BadgeSync` 对账（防面板滞留）。
2. **反向**：任务属主失联（心跳连丢 K 拍 ∨ 会话已收殓 ∨ 重启腿 Completed 后 successor 活性证伪）⇒ 控制器对该任务**代行终态记账**（`BgTaskLedger.markOrphaned` 同款，eventType 注明 `owner-lost`），并向等待链上每一环注入「等待对象已终止（原因）」ExternalEvent——payloadHead 判重幂等（`splitForRedelivery` 既有面）⇒ **挂起会话收到即唤醒或放行**：活会话唤醒续跑（B1 腿），节点完成闸复检腿（`NodeEngine` Completed 桥）自然放行终态化。
3. 正确性声明：注入的通知只是**把「死」这个事实送达**，放行/终态的语义仍由各闸口既有判定承担——控制器不替任何闸口做终态裁决（reconcile 不比对「该不该终态化」，只比对「该知道的是否已知道」）。

---

## 5. 与既有机制的接合（复用优先）

| 既有机制 | 关系 | 接合法 |
|---|---|---|
| **bg-wait 屏障**（`BgTaskLedger` 栅栏 + `BackoffSupervisor` cap + `NodeEngine` 完成闸） | 等待链本体 | 本设计**挂在链旁不嵌入链内**：健康轨只读写行内字段与注入提醒；death-watch 反向注入走既有 ExternalEvent+判重面；cap 值、hold 语义、复检腿**零改动** |
| **HeldDeliveryWatchdog** | 姊妹扫描器（最像的一件） | 形态全继承（scan 循环/纯决策面/徽标帧/退避哲学）；建议**共用一拍**（两扫描器同 tick 顺序跑，省 fiber 数）；`heldReceipts` 徽标的成功先例（面板「N 条回执待投递」同款）即 `bgHealth` 帧模板 |
| **合并道（MergeQueue）** | 正交 | 合并排队中的 delegate 也会持后台任务——`bgHealth` 徽标在合并道视图同源可读（读同一账本），但**合并道状态机零改动**（`20261030_merge-lane-state-machine` 的迁移×守门表不受影响） |
| **429 自适应限流（AdaptiveThrottle）** | 共振抑制 | escalated 通知编排者可能引发 LLM 调用风暴——控制器读 `AdaptiveThrottle` 账户级 429 面，**节流风暴期抑制非必要 Nudge**（只保留徽标）；Nudge 本身是 mailbox 级不进 LLM 上下文，天然不耗 token |
| **TaskStuckWatcher** | 互补不重叠 | watcher 守 Processing（「忙着但不动」），本设计守 idle-waiting（「等着但等的东西不动」）——两条轨互补覆盖全谱；**绝不给 watcher 加 idle 判据**（防误杀铁律照旧） |
| **BgRateGate（#753 熔断）** | 上游闸 | 不变。健康轨与创建速率闸正交（一个是「造太多」，一个是「跑太久」） |
| **BgNotifyDelivery / BgInboxPort** | 通知腿 | 正向终态传播全复用；death-watch 反向注入复用其活性判定（`BgSessionActivator` 同款注册器面）与持久补投腿 |

---

## 6. 零超时红线全书自查

全书既有 wall-clock 腿逐条定位（本设计新增 wall-clock 判死数 = 0）：

| 腿 | 位置 | 量级 | 判死？ | 正确性表达 | 本设计处置 |
|---|---|---|---|---|---|
| B1 idle 传感器 | `shell.scala:1101-1130` | 300s | **不判死** | 只写 `bg-slow` 审计事件+logger.warn（只提醒改造后明确「不杀进程、不 complete deferred、不写失败台账」） | 不动；其事件同时喂健康轨（suspect 判据输入之一） |
| B2 硬超时+停滞杀 | `shell.scala:1131-1142` | 30min + 连续 120s 双零 | 杀 **OS 进程**，不判任务语义死 | TimeoutException 经完成回调+`markFailed` 账本表达（节点 failed 注明，禁静默 completed） | **不动**（进程卫生腿；判据双零+硬超时是既有裁定）。健康轨与之正交：B2 够不着的形态（CPU 忙卡死）由 suspect/escalated 补**可见性** |
| bg-wait cap 强制终判 | `BackoffSupervisor.scala:461-500` / `Defaults.BgGateWaitTimeoutMs` | 2h | 不判死——**fail-open 交付** | 带注记强制终判 + `markOrphaned` 落账（续会话拾取有据） | 值与语义不动；从「唯一兜底」降为阶梯末档——escalated 在 cap 之前已可见可纠；cap 后 orphan 任务的持续 running 由健康轨 escalated 承接可见 |
| HeldDelivery 退避催注/穷尽 | `HeldDeliveryWatchdog` / `Defaults.HeldDelivery*` | 10min→30min 退避 ×5 | **不判死** | exhausted 终态旗落盘 + 响亮 WARN + 徽标（任何后续事件先补投）——「升人不杀」的范本 | 不动； escalated 阶梯同构复制 |
| TaskStuckWatcher | `TaskStuckWatcher.scala:71-130` | 10min（Processing 专属） | 判「卡死」但恢复链是 Stop→重启/硬取消 LLM fiber，非终态裁决 | 重启/熔断/父通知（OTP 既有） | 不动；idle 永不判铁律照旧 |
| Dispatcher 空闲保活窗 | `Defaults`（4h） | 4h | 不判死 | 资源回收（teardown 重建会话） | 无关 |
| 节点销毁窗 | `NodeDestroyWindowMs` | 30min | 不判死 | 终态后取证窗+禁 spawn，到期收殓 | 无关；death-watch 与该窗协同（销毁前先把终态送达等待链） |
| 429 AdaptiveThrottle | `AdaptiveThrottle.scala` | 反应式降档 | 不判死 | 并发维度调节，与任务生死无关 | 复用为 Nudge 抑制信号（§5） |

**自查结论**：红线守住的方式不是「没有时钟」，而是**每条时钟腿都被审过：要么只观测不裁决（B1/退避/徽标），要么裁决的是进程卫生而非任务语义（B2），要么裁决的是交付时机且带 fail-open 注记（cap）**。本设计新增的全部动作（Nudge/BadgeSync/Escalate/ReconcileNotify）无一携带杀伤语义，`ScanAction` 封闭类型在编译层守门。

---

## 7. 迁移分期与每期验收

**推荐顺序：面板投影先行（P0）→ 控制器（P1）→ 取消+death-watch（P2）→ 重启腿升格（P3 登记）**。理由：P0 几天内把事故②消掉，同时用真实运行验证行内健康字段的语义（给 P1 的判据喂实测基线）；控制器先行（无投影）则升级对人仍不可见——reconcile 的「status 投影单一事实源」原则要求投影先行。每期独立可回退（prop 开关缝先例：`nebflow.bgtask.gate.timeoutMs` 同款「≤0=关闭」）。

| 期 | 内容 | 验收条件（二值可判定） |
|---|---|---|
| **P0 面板投影**（先行） | `ActiveTask` 行补 health 字段；`activeTasksJson`/`bgWaiting` 载荷升级；`bgHealth` 徽标帧 + 面板行渲染（任务名+年龄+健康色）；节点 bgWait 结构化 | ① 冒烟红线：全量既有 spec 绿（零行为变化，投影纯增量）；② 造挂起后台任务，面板行在 1 拍内显示名称/年龄/绿色健康；挂 10min+ 无输出变黄色；③ `BgWaitingCountSpec` 扩断言：快照帧与实时帧字段同源；④ 刷新恢复路径（WS 重连快照）健康字段不丢 |
| **P1 控制器+FSM 记账** | `BgLifecycleReconciler`（run/scan/scanCore 纯面）；suspect/escalated 迁移+Nudge 退避注入；escalated 红徽+编排者通知+审计行；与 HeldDeliveryWatchdog 共拍；escalated 状态落盘 | ① `scanCore` spec 全覆盖：启发式命中/进展复位/不杀（断言 ScanAction 无 Kill case）/退避递增；② 造 idle>基线×N 任务 → `bg-slow` 事件后 Nudge 出现在等待会话转录，面板黄徽；催注 k 次无进展 → escalated 红徽 + 编排者收到通知 + 审计行；③ 任给进展信号 → 降回 healthy、停滞计数清零（重排不判死断言）；④ 全书 grep：本批无新 wall-clock 判死点（红线守门 spec）；⑤ 429 信号注入期 Nudge 被抑制、徽标照刷 |
| **P2 取消粒度+death-watch** | WS 单任务取消泛化（local+remote `bg-cancel` kind）；`AgentControl scope=bgTask`（三守卫复用）；death-watch 反向终态注入（属主失联三判据） | ① 三通道各自取消 N 条中 1 条、其余存活且通知到位；② 远端取消 round-trip：HMAC 验签 + nonce 防重放 + 确认帧 + 对端进程树真死；③ 等待会话在属主死亡后 1 拍内收到「等待对象已终止」通知并唤醒/放行（`DelegateBgWaitHoldSpec` 扩）；④ 注入幂等：重复注入零双注（payloadHead 判重断言）；⑤ 会话/节点取消语义回归全绿 |
| **P3 重启腿升格**（登记，不在本批承诺） | 重启/升级腿与 delegate 会话为**在册任务**（successor 活性 handshake 接入 reconciler 期望态）；链路级 reconcile 与 NodeEngine/MergeQueue 全面对账 | 立项另立（依赖 P1/P2 稳定运行观测）；验收草案：隔离实例重启挂死场景 1 拍内 escalated 可见 |

---

## 8. 风险表

| # | 风险 | 缓释 |
|---|---|---|
| R1 | Nudge 刷屏骚扰会话/转录膨胀 | 指数退避（首催 10min 档）+ 单发预算（`slowNotified` 同款 CAS 旗）+ 429 风暴期抑制；suspect 级只刷徽标不进转录（未决项 ④） |
| R2 | 健康轨进程内存态，重启即丢（`BgTaskRegistry` 本体同病） | escalated 落盘（`BgTaskLedger` 条目扩展，重启后一拍内由 reconciler 重建视图）；suspect 级重启丢可接受（重新积累判据即可） |
| R3 | 远端取消误杀对端进程 | HMAC+nonce+确认帧（`bg-cancel` 走 `DeviceDelegate` 同款安全面）；幂等：重复取消返回已取消不回二次杀 |
| R4 | 红线侵蚀：后续批借「健康轨」之名加时序判死 | 本表 §6 自查件为活文档；`ScanAction` 封闭类型编译守门；每期验收含「无新 wall-clock 判死」grep 断言 |
| R5 | 基线误判（慢任务误报 suspect） | 自基线只观察任务自己头 K 分钟；CPU 忙豁免（编译类）；系数全 prop 可调；误报代价=一次徽标，零杀伤 |
| R6 | death-watch 反向注入与正向完成竞态（双通知） | 既有 payloadHead 判重幂等面；`markOrphaned` 保留 completion 不降级（`BgTaskLedger.scala:207-234` 同款规则） |

---

## 9. 未决项（需作者拍板）

1. **escalated 之后允不允许自动杀进程？** 本设计立场：不允许——杀=人工/编排者决策（B2 进程卫生腿除外）。请裁定。
2. **基线来源**：自基线（头 K 分钟观测中位×系数）vs 分类基线表（description 词表→基线档）。建议先自基线+全局默认档（N=3、M=10、k=3），运行一个月后再决定要不要分类表。
3. **默认档数值**：suspect=max(基线×3, 10min)；escalated=催注 3 次（10/20/40min 退避）无进展 或 空闲>基线×10。请裁定或授权按此默认值落 P1。
4. **Nudge 载荷形态**：suspect 级仅徽标不打扰 vs 进转录提醒。建议两级——suspect 仅徽标，escalated 进转录+通知编排者（对齐 HeldDelivery「催注进会话、徽标进面板」的双面先例）。
5. **bg-wait cap（2h）与 escalated 的关系**：cap 保留为阶梯末档 fail-open（本设计立场，值不动）vs escalated 后提前放行。请裁定。
6. **远端取消通道**：新 kind `bg-cancel`（建议——闭集动词+HMAC 域分隔，与 `wt-op` 哲学同款）vs 复用既有 kind。请裁定。
7. **AgentControl 单任务 cancel 的开放面**：对 agent 开放（守卫=chainReaches+managerAnchors 复用，建议）vs 仅根/编排者可用。
8. **P3 范围**：重启腿升格为在册任务是否立项（本设计登记为候选，前置=P1/P2 稳定运行观测一个月）。

---

## 相关文档

- [[20261030_merge-lane-state-machine.zh-CN]]——合并道状态机总图（bgHealth 徽标在合并道视图同源的账本纪律）
- [[held-receipt-drain-silence-spec.zh-CN]]——held 回执催注/穷尽（escalated 阶梯的直接先例）
- [[delegate-supervision-revival-design.zh-CN]]——delegate 监督复兴（监督语义姊妹篇）
- [[turn-liveness-spec.zh-CN]]——回合活性（idle-waiting 轨与 Processing 轨互补的判据边界）
- [[20261026_detached-apply-restart-update-spec.zh-CN]] / [[20261027_cli-isolation-and-detached-restart-spec.zh-CN]]——重启/升级腿（P3 重启活性接入的落点）
