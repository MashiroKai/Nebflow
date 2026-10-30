---
type: design
status: draft
title: 编排层/执行层分离——跨设备执行架构设计稿
date: 2026-10-30
batch: delegate-nebflow-0eb200dc
tags: [orchestration, execution, cross-device, worktree, merge-lane, neblink]
related:
  - "[[20261028_triple-line-integration-contract.zh-CN]]"
  - "[[20261030_merge-lane-state-machine.zh-CN]]"
  - "[[20261028_213056_delegate-dual-mode-redesign__delegate-nebflow-192d10a5.zh-CN]]"
  - "[[delegate-supervision-revival-design.zh-CN]]"
  - "[[device-provenance-spec.zh-CN]]"
---

# 编排层/执行层分离——跨设备执行架构设计稿

> **批性质**　设计批（台账 #1113，只设计不实施）。作者令 2026-10-30 09:49 逐字：「正确的跨项目的思路和做法应该是，同时关注一下张安齐的项目的设计。就是我们会把编排层和执行层分开。编排层负责LLM调用等这些内容。然后执行，比如跨设备，你选择了一个项目，项目在KAI上，那么Jev分配worktree 合并都应该是在KAI上进行。」呈作者拍板后另批落地。
>
> **参照系**　张安齐（phenol1145，USTC）triple 线 + Stand 平行实现（stand-parity：1 front + 14 cap 微服务）。材料清单与证据分级见 §7；凡引用两次审计读数处标注【亲验】/【转引】承袭原报告口径。
>
> **红线照旧**　零超时设计——正确性靠监测表达，不允许设计性 wall-clock 超时当正确性手段；只设计不实施、在产零触碰、不 push。本稿所有跨设备等待面（放行等待、回合回报、通道重投）一律事件驱动 + 活性监测表达，wall-clock 只允许作幻影检测的降级触发器并逐处显式申报（§2.4、§5-R3）。

目录：

- [0. 执行摘要](#0-执行摘要)
- [1. 分层定界与逐组件现状盘点](#1-分层定界与逐组件现状盘点)
- [2. 层间接口：设备通道上的执行控制消息](#2-层间接口设备通道上的执行控制消息)
- [3. 与在飞批的接合：#1112 定位与迁移分期](#3-与在飞批的接合1112-定位与迁移分期)
- [4. 参照 Triple 的对照表](#4-参照-triple-的对照表)
- [5. 风险表](#5-风险表)
- [6. 未决项清单（需作者拍板）](#6-未决项清单需作者拍板)
- [7. 参照材料清单与证据分级](#7-参照材料清单与证据分级)
- [相关文档](#相关文档)

---

## 0. 执行摘要

**一句话**：把「谁想」与「谁动手」按设备切开——**编排层**（LLM 调用/Jev 判定/路由派发/台账监管/合并账与放行通知/UI）留在编排机（今＝派发引擎所在的 Mac）；**执行层**（delegate 会话执行、worktree 分配、git 操作与合并执行、构建测试、任务执行）全部落到**项目所在设备**。项目声明了 origin 设备（如 KAI）后，该项目的分支/worktree/合并轮只允许在那台设备上发生，编排机只持有指针、账本与放行权。

三个判据支柱：

1. **不变式先行**：`seat(device(project))`——git 座位（分支、worktree、merge）的设备归属 = 项目 origin 设备。今天这条不变式被违反正是 #1112 报障的直接原因：挂载 remote-origin 项目的 delegate 座位被焊在编排机 brain 根（`DelegateTool.resolveWorkspace` 挂载腿，`DelegateTool.scala:290-303`），分支与合并全在 Mac 发生，项目在 KAI。
2. **账不动、手搬家**：作者 2026-10-26 裁定合并排队系统＝「交通引导员」（只持账 + 只发通知，永不自己跑 merge，`MergeQueue.scala:1-29`）——这个裁定天然把「账/通知」留在编排层、「merge 执行」留给属主。本设计只是把这半步走完：**属主搬到设备侧**。合并账、放行窗、验收读数定义都不动。
3. **通道复用优先**：跨设备派发/回投/回执/outbox 四条腿已在生产（`DeviceDelegate` 全件，§2.1），本设计新增的层间消息只有两类——**远端 worktree 指令**与**远端合并指令**——挂在既有数据通道的 kind 面上（闭集动词 + HMAC 域分隔，镜像 triple-star bridge-host 的命令面纪律）。

**与 #1112 的关系（接合结论）**：#1112（跨设备项目 worktree 归属修复，战术层、在飞）**就是本架构的第 0 期**——它不是与本设计并行的补丁，而是不变式的第一次落地。其验收必须含架构不变式（remote-origin 项目的 git 座位全在 origin 设备），否则会把错误形态固化成存量（§3.1、风险 R1）。

**显式不做**：不拆微服务（单机多进程现状，理由 §4.3）；不动 LLM 调用与 Jev 判定的物理位置（留编排机，作者令原文）；不改合并排队「交通引导员」制；不实施跨引擎会话接管/复活（设备侧会话的 crash-recovery 是大件，单独立项登记，§5-R4）；不动外部 CLI 执行器腿（zcode/claude-code/codex）。

---

## 1. 分层定界与逐组件现状盘点

### 1.1 分层定义

```
┌─ 编排层（编排机 = 派发引擎所在设备；今 Mac/T0）──────────────────┐
│  LLM 调用：主对话、模型路由/回退（llm/ 包）、AdaptiveThrottle      │
│  决策调用：Jev 外部决策面、DelegateJudgeMachine 判定、模式选择、   │
│           插件分配（判定结果=指令，不在本机应用执行面副作用）        │
│  路由派发：Delegate 工具面、resolveWorkspace 派生、DeviceDelegate │
│           投递机械、TaskLedger/台账、链账本 ChainStore            │
│  监管面：MergeQueue 账+放行通知（交通引导员）、DelegateLedger-     │
│          Reconcile、flow map、面板/UI、审计日志                   │
└──────────────┬──────────────────────────────────────────────────┘
               │ 设备通道（NebLink presence WS + relay 兜底；Dropbox 字节面）
               ▼
┌─ 执行层（项目所在设备 = project.origin.device；如 KAI）───────────┐
│  delegate 会话执行：spawnBackground 本地会话、actor/Backoff-       │
│           Supervisor、外部 CLI 子进程（ExternalSessionRunner）     │
│  worktree 分配：分支 delegate/<id>、worktree 建立/清理              │
│           （DelegateWorkspace.prepare、GitWorktrees、PostMerge-    │
│           Cleanup）、Jev 分配结果的应用                            │
│  git 操作与合并执行：commit、合并轮 git merge --no-ff、机械验收、   │
│           base 净空卫生（MergeArtifactCare）、MERGE_HEAD 道闸读数  │
│  任务执行：Bash/文件工具、构建测试、产物产出                        │
└──────────────────────────────────────────────────────────────────┘
```

**判定原则（一句话归宗）**：一条职责归编排层还是执行层，看它的**字节副作用落在哪块盘上**——LLM token 与台账字节落编排机（留），分支/worktree/构建产物/合并提交落项目盘（走）。「Jev 分配 worktree」按作者令拆两半：Jev **判定**（LLM 调用）在编排机，判出来的 **worktree 分配**（建分支建座位）在项目设备——这正是作者令「Jev分配worktree…都应该是在KAI上进行」的准确读法。

### 1.2 逐组件现状盘点（现在各跑在哪侧）

锚点基线＝本批 worktree HEAD `9389ff749`（main 尖）。【亲验】＝本批直读核对；【转引】＝承审计/侦查报告锚点、本批未逐行复核。

| # | 组件 | 现状跑在哪侧 | 锚点 | 判定 |
|---|---|---|---|---|
| 1 | 主 LLM 对话/模型路由（ProviderRegistry/LlmAllocator/AdaptiveThrottle） | 派发引擎 JVM 本机 | `src/main/scala/nebflow/llm/`（包面） | ✅ 已符合（编排层件，留） |
| 2 | Jev 判定/判定机（DelegateJudgeMachine 两层＝Jev 优先+SmallJudge 兜底） | 派发引擎本机（LLM 调用面） | `src/main/scala/nebflow/llm/decision/DelegateJudgeMachine.scala:1-60`【亲验】 | ✅ 调用面留编排机；⚠️ 但其产出直接驱动本机建座位（见 #5）→ 应用面迁移项 |
| 3 | 模式选择器/插件分配（JeV 问对→分配→fail-open 三态底座） | 派发引擎本机 | `src/main/scala/nebflow/llm/decision/JevAllocator.scala`【转引】 | ✅ 决策留编排机；结果应用随执行层化（第 3 期） |
| 4 | 路由派发（Delegate 工具、resolveWorkspace 派生 Local/RemoteRoute） | 派发引擎本机 | `src/main/scala/nebflow/core/tools/DelegateTool.scala:219-333`【亲验】 | ✅ 派生判定留编排机（DeviceDelegate 头注裁定：delegate 不加 device 参数，`project.origin.device` 派生路由） |
| 5 | **worktree 分配**（DelegateWorkspace.prepare：判定→一律建分支+worktree） | **派发引擎本机盘**（焊死） | `src/main/scala/nebflow/core/delegate/DelegateWorkspace.scala:89-146`【亲验】；`src/main/scala/nebflow/core/GitWorktrees.scala`（createWorktree 族） | 🔴 迁移项（应落项目设备）——#1112 病灶 |
| 6 | 挂载 remote-origin 项目座位（brainWorkspace 本机大脑根 + 会话设备上下文） | **编排机 brain 根**（git 座位在 Mac，文件工具路由 KAI——双形态分裂） | `DelegateTool.scala:284-303`【亲验】；`src/main/scala/nebflow/core/project/ProjectStore.scala:121`（brainWorkspace） | 🔴 病灶面（#1112 报障的落点）：git 面与文件面分裂在两台机器 |
| 7 | 未挂载 remote-origin 项目路由（RemoteRoute→DeviceDelegate.dispatch 整会话派设备） | 对端引擎原生（worktree 随对端 spawnBackground 本机建立，✅ 形态正确） | `DelegateTool.scala:305-330`【亲验】；`src/main/scala/nebflow/core/delegate/DeviceDelegate.scala:1-50`【亲验】 | ✅ 已符合（这就是目标形态的现状样板） |
| 8 | delegate 会话执行（spawnBackground/actor/BackoffSupervisor/外部 CLI） | 派发引擎本机 | `src/main/scala/nebflow/core/executor/ExternalSessionRunner.scala:1-25`【亲验】 | 🔴 迁移项（随路由收口：remote-origin 项目会话整体在项目设备跑） |
| 9 | 合并账+放行通知（MergeQueue：只持账、只发通知、永不自己 merge） | 派发引擎（编排职责） | `src/main/scala/nebflow/core/delegate/MergeQueue.scala:1-60`【亲验】 | ✅ 已符合（交通引导员制留编排层，作者 2026-10-26 裁定） |
| 10 | **合并执行**（合并轮指令注入属主会话，属主跑 `git -C <baseWs> merge --no-ff`） | **派发引擎本机**（属主＝本机会话 ⇒ merge 在 Mac 盘发生） | `MergeQueue.scala:716-895`（grant/notifyTurn）【转引】；`:1211-1221`（合并轮执行命令原文）【亲验】 | 🔴 迁移项（指令与执行体搬到项目设备；账不动） |
| 11 | 台账监管（TaskLedger/DelegateLedgerReconcile/ChainStore/flow map/面板） | 派发引擎 | `src/main/scala/nebflow/core/delegate/DelegateLedgerReconcile.scala`【转引】 | ✅ 已符合（留编排层） |
| 12 | 跨设备通道（delegate-spawn/accept/result、HMAC、NonceCache、DeviceResultOutbox write-ahead） | 编排↔执行通道本体 | `DeviceDelegate.scala:33-50`【亲验】 | ♻️ 复用项（§2.1） |
| 13 | 崩溃恢复/复活（DelegateRevival/SubAgentStartupRecovery/boot sweep） | **只认本机 worktree+本地 transcript** | `src/main/scala/nebflow/agent/SubAgentStartupRecovery.scala`【转引】；10-28 多设备审计 §二.3【亲验审计】 | ⚠️ 缺口（设备侧会话的复活/接管＝跨引擎监督，单独立项，不在本期） |
| 14 | 字节/制品面（ChunkTransport/NEBFLOW_PULL 回捞/Dropbox 文件同步） | 设备间 | `src/main/scala/nebflow/dropbox/DropboxService.scala`【转引】 | ♻️ 复用项（非控制面） |
| 15 | 构建位（引擎 jar 构建链） | 已切 KAI（phase2 三步流：bundle 同步→KAI 出包→Mac `stand-jar-pull`） | `ops/README.md`；10-28 多设备审计 §一.0【亲验审计】 | ✅ 已符合（且是执行层化的前奏实证） |

**盘点小结**：15 件里 7 件已符合编排层定界、2 件复用、1 件缺口登记、**5 件迁移项集中在一条链上**——派发→座位→会话→合并执行。这条链正是 10-28 多设备审计「机制 1：Delegate(project=X) 的 worktree 焊死在派发引擎本机」的代码级展开；其中 #6（挂载 remote-origin 座位）是正在报障的裂缝，#7（未挂载 RemoteRoute）是已在生产的正确样板——**目标架构不是新造，是把 #7 的形态升格为唯一形态**。

### 1.3 双形态收口（挂载 vs 未挂载 remote-origin）

现状 remote-origin 项目有两条腿，行为相反：

- **未挂载**（RemoteRoute）：整会话派设备，对端引擎用自己的 executor 集原生执行，worktree 建在对端盘——✅ 符合目标。
- **已挂载**（Local+设备上下文）：座位＝编排机 brain 根（`brainWorkspace`），会话工具靠 `fleet.enforceExplicitDevice`/sessionDevice 上下文把文件操作路由到设备——git 面在 Mac、文件面在 KAI，**一条 delegate 的两次提交可以一次落 Mac worktree 一次落 KAI 盘**（双写面无原子性，10-28 审计 §一.6 边界同族病灶）。

收口方向（第 1 期落地，§3.2）：remote-origin 项目的「挂载语义」重新定义为**「编排机持有项目注册与监管投影、执行座位一律在 origin 设备」**——挂载不再 implies 本机 brain 座位。`brainWorkspace` 路径对 remote-origin 项目退潮（保留给本机项目），`sessionDevice` 设备上下文腿退役或收窄为展示面。

---

## 2. 层间接口：设备通道上的执行控制消息

### 2.1 通道本体（复用，不新造）

设备通道＝NebLink presence WS 结构化数据通道（`sendData`）+ relay 兜底（`relayNotify`→对端同一入口），既有安全面全量继承：已配对设备（DeviceRegistry）+ `allowSpawn=true` 闸 + HMAC 验签（ts±90s + nonce 防重放 + 常数时间比对）+ spawnId 幂等 + fail-closed 显式拒绝（`DeviceDelegate.scala:30-50`【亲验】）。Dropbox 是字节/制品面（文件搬运），不承担控制消息。

### 2.2 执行控制消息/能力清单（逐条：复用 or 新增）

| # | 消息/能力 | 方向 | 现状 | 处置 |
|---|---|---|---|---|
| M1 | 远程派发 `delegate-spawn` / 回执 `delegate-accept` / 终态 `delegate-result` | 编排→执行 / 执行→编排 | ✅ 在位（DeviceDelegate 三 kind） | **复用**。第 1 期起 remote-origin 挂载项目也走此腿（收口双形态） |
| M2 | 结果回投可靠性：交付回执 ack/重投 + `DeviceResultOutbox` write-ahead（at-least-once，宿主按 spawnId 判重） | 执行→编排 | ✅ 在位 | **复用**。回合终态回报复用同一 outbox 纪律 |
| M3 | 状态投影：AgentSessionSync/identity-sync（presence 载荷，<2KB 摘要，prompt 永不上机） | 执行→编排 | ✅ 在位（投影级） | **复用**。面板「远端行」现状照旧 |
| M4 | **远端 worktree 指令**：建/删座位、建分支、base tip 查询、commit 存在性核对、净空卫生读数（闭集动词，如 `WT-CREATE / WT-REMOVE / TIP-QUERY / DIRTY-READ`） | 编排→执行 | ❌ 无（今天只有整会话派设备或文件级工具路由，无 git 控制面） | **新增**。挂数据通道新 kind（`wt-op`）+ HMAC 域分隔（镜像 `spawnCanonical` 拼装纪律）；节点零调度逻辑——编排侧准入/裁决、执行侧照做回执（借 triple-star「闭集执行器+节点零执法」模式，可行性报告 §2-A-2【转引】） |
| M5 | **远端合并指令**：放行通知携带 base 当前 tip（既有 notifyTurn 载荷）→ 设备侧执行腿跑 `git merge --no-ff` → 回合终态/拒绝原因回报 | 编排→执行→编排 | ⚠️ 半在位：notifyTurn 载荷与回报协议已有，但收发两端都假设属主会话在编排机 | **新增跨设备腿**：编排侧只改投递对象（注入设备侧执行腿而非本机属主会话）；执行侧新执行腿（轻量会话或受控执行器，能力面=合并轮指令集）。账/放行窗/单飞行语义零改动 |
| M6 | 进度回报：节点状态、阻塞通知（#943 BlockNotify 语义）、心跳拍（活性，非 wall-clock 超时） | 执行→编排 | ⚠️ 本机面在位，跨设备面只有终态 | **扩展**：执行腿事件驱动回报（状态迁移即报，不轮询）；活性丢失的判死沿用「真源侧心跳行连丢 K 拍」第三方判定制（triple-star 05-distribution 模式【转引】，与既有心跳 K=3 同构） |
| M7 | 能力预演/准入查询：`ADMIT` 式只读预演——「此刻派这台会不会收」（设备在线、盘空间、工具链在位、同名座位冲突预检） | 编排→执行 | ❌ 无 | **新增**（借 triple-star govd `DEVICE-ADMIT` 准入预演模式，可行性报告 §2-A-1【转引】——其建议本已落在 C2 设计同构位）。派发前查询，预演不过=显式失败列原因，绝不静默落 host |
| M8 | 字节/制品面：日志、证据、截图回捞（`NEBFLOW_PULL`）、tar/bundle 同步 | 双向 | ✅ 在位（ChunkTransport/Dropbox/ops 三步流） | **复用**（M4/M5 只管控制面，字节一律走 M8） |

**新增面合计**：M4、M5、M7 三条腿 + M6 扩展。全部落在既有通道的 kind/载荷面上——**零新传输基建**，这与 10-28 分层契约「零新基建」的变更通知机制同一工程哲学。

### 2.3 消息面安全与幂等纪律（新增腿继承）

1. **闭集动词**：M4/M5 执行侧只认白名单动词，未识别=显式拒绝回执（带原文），绝不猜执行。
2. **幂等**：所有指令带 `opId`（编排侧铸造）；执行侧按 opId 去重（`processedSpawns` 同款 ConcurrentHashMap 模式，`DeviceDelegate.scala:52-56`）；at-least-once 通道重放由编排侧按回执判重。
3. **账实可核对**：每条执行控制消息落链账本行（ChainStore appendSync 同型，键含 projectKey+opId），编排侧任何账面对账都能回放「我何时让哪台设备干了什么」。
4. **fail-closed**：设备离线/未配对/验签失败＝显式失败（`DELEGATE_DEVICE_OFFLINE` 语义延用，列在线设备），**绝不静默落编排机本机**（G.12 路径错觉防护，`DelegateTool.scala:334-340`【亲验】——这条既有纪律就是防「以为在 KAI 跑实际在 Mac 跑」，本设计全继承）。

### 2.4 零超时红线对照（新增腿逐面自查）

- 放行等待：编排侧等回合终态＝等事件（M6 回报/outbox 补投），不设「合并轮 wall-clock 上限当正确性手段」；既有 careTick/stall 监测面语义不变。
- 指令投递：write-ahead + 重投（M2 同款），失败升级=通知 dispatcher（既有 BlockNotify/owner-gone 语义延用），不 sleep-retry。
- 活性判死：K 拍心跳连丢（第三方按真源侧判定），心跳拍本身是活性监测不是超时器。
- 唯一 wall-clock 允许位：幻影检测降级触发器（与既有 watchdog「不是任务超时、绝不杀会话」同纪律），逐处注释申报。

---

## 3. 与在飞批的接合：#1112 定位与迁移分期

### 3.1 #1112 的定位：第 0 期，不是并行补丁

**事实面**：#1112（跨设备项目 worktree 归属修复）＝作者 2026-10-30 09:44 报障，病灶＝§1.2 #6——项目「跨设备测试」（workspace 在 KAI）的 delegate 座位给了编排机 brain 根，worktree/分支落 Mac。10-30 09:49 作者令（本设计稿输入）在报障后 5 分钟下达——**两针同源**：#1112 修的是不变式的第一个裂缝，本稿画的是不变式的全图。

**接合结论（三条）**：

1. **#1112 的验收标准必须含架构不变式**：remote-origin 项目的 git 座位（分支+worktree）与合并发生地＝origin 设备，可取证（对端 `git worktree list`/分支列表/合并提交的设备侧证据）。只修「文件写对地方」不修「座位归对设备」＝把双形态固化为存量。
2. **#1112 修法取向的建议（供在飞批参考，不代裁）**：优先走已验证的 RemoteRoute/整会话派设备腿（§1.2 #7，10-26 晚 KAI 引擎原生派发三轮实证【转引 10-28 审计 §三.2 路由 A】）——它是现状样板、触碰面最小；「引擎侧 brain 会话 + M4 远端 worktree 指令」形态是第 1-2 期的形态，若 #1112 现在就建 M4，等于提前开工且与在飞六批抢文件面（§3.4）。
3. **若 #1112 因时限落 Mac 座位止损形态**：登记为已知债，第 1 期第一批返工（风险 R1），不得以此为理由把 brain 座位写进任何长期文档为「现状正确」。

### 3.2 迁移分期表（每期独立可验收）

| 期 | 名称 | 做什么 | 不变式/验收（二值可判定） | 触碰面（主） |
|---|---|---|---|---|
| **0** | worktree 归属修复（**＝在飞 #1112**） | remote-origin 项目 delegate 座位落到 origin 设备 | ①#1111 场景（项目在 KAI）全链路复现：worktree/分支/合并全在 KAI，设备侧证据可取；②Mac brain 根零新座位；③在飞批既有验收照旧 | #1112 自声明（本稿不越权点 pathspec） |
| **1** | 路由收口：座位执行侧化 | 收口双形态：remote-origin 挂载项目不再给本机 brain 座位，统一走整会话派设备（M1 复用）；`brainWorkspace`/`sessionDevice` 对 remote-origin 退潮；M7 准入预演随期落地（派发前必查） | ①不变式测试：`∀ remote-origin 项目：座位设备=origin 设备`（spawn payload 字段断言）；②已挂载/未挂载两形态行为一致（同一路由出口）；③设备离线=显式失败列在线设备（fail-closed）；④#1111 回归 | `DelegateTool.resolveWorkspace`、`ProjectStore.brainWorkspace` 调用面、`DeviceDelegate`（路由派生单点）、spec 新增 |
| **2** | 合并执行侧化 | M5 远端合并指令腿：notifyTurn 投递对象改设备侧执行腿；设备侧跑合并轮+机械验收+道闸读数（MERGE_HEAD/活性），回报关账；编排侧 MergeQueue 账/放行窗/单飞行/owner-gone 语义零改动 | ①合并提交（git log --format=%H 设备侧）在 origin 设备发生；②账实核对跨通道（mergeEvidence=isAncestor 由设备侧读数回报）；③方向闸/净空闸语义与 20261030 合并道状态机总图逐状态对照不回归；④失败=failed/owner-gone 显式关账，绝不静默 | `MergeQueue`（notifyTurn/grant 投递面）、新执行腿件、`GitWorktrees` 读数回报面、spec |
| **3** | Jev 分配执行侧化 | Jev/判定/模式选择/插件分配的**应用面**（建座位、挂插件、能力面装配）迁为「编排侧出指令、设备侧应用」；LLM/Jev 调用面物理位置不动（留编排机） | ①编排机对 remote-origin 项目零执行副作用（spawn 后不触发本机 git 写）；②分配结果跨通道下发可回放（链账本行）；③插件挂载在设备侧会话生效（面板投影读数一致） | `DelegateWorkspace.prepare` 应用面、`AgentSessionExecution` 装配面、`SharedResources` 注册面、spec |

**分期顺序的理由**：座位（0-1）是合并（2）的前置——合并轮跑在 `baseWs` 上，base 的盘归属先归正，合并执行搬家才有落点；Jev 应用面（3）最贴会话装配，最后动且可分期内部再切。

### 3.3 与「开发迁 KAI」总纲的关系

10-25/26 定版「KAI=开发主位、Mac=发布集成面」（10-28 审计 §一.0【亲验】），构建腿已走完（KAI 出包→Mac 拉取）。本设计是**派发腿的架构版答案**：审计 §四补齐动作 ①（问路由纪律）②（声明式对端 worktree）③（修设备网络面让挂载通过）分别落在第 0/1/2 期——三期合起来就是审计说的「不需要大工程」的全貌；审计另列的「跨引擎接管」大件显式不在本期（§5-R4）。

### 3.4 在飞六批文件面隔离纪律

每期实施批立项时必须：①pathspec 点名清单与六批在飞分支（投递可靠性 f2451d50 的 MergeQueue/DelegateTerminal/BackoffSupervisor/TaskStuckWatcher 面、CLI 隔离批的 ops/launcher 面、agentflow N1 的 MailTool/NodeEngine/ChainsReadFace 面、ProjectTool/unmount 批的项目管理面、插件协议批、canvas ask 批）做**交集自核**（manifest.json 零交集纪律，沿各在飞批既有做法）；②交集非空⇒改期或改面，不得硬抢；③本稿 §1.2 锚点表即各期的「预期触碰面」申报基线，实施批可重核但不许静默扩大。

---

## 4. 参照 Triple 的对照表

### 4.1 组件对照（stand-parity 14 cap 服务 ↔ 我方组件 ↔ 层位）

| Triple 服务 | 我方对应组件 | 层位（本设计定界） | 抄/不抄 |
|---|---|---|---|
| **llmgw**（LLM 网关） | `llm/` 包：ProviderRegistry/LlmAllocator/AdaptiveThrottle + Jev 外部决策面 | 编排 | 抄**概念**：网关独立可观测、模型链候选遍历（10-29 fail-open 整链修复同款刀已在我方落地）。不抄**服务化**（§4.3） |
| **agentd**（agent 会话服务） | AgentCore/actor 会话：spawnBackground/BackoffSupervisor/DelegateRevival | 执行（会话执行体） | 词表对齐（与安齐对齐议程 §3，[[20261028_triple-line-integration-contract.zh-CN]]）；不抄实现（Rust 复刻，行为对拍关系不变） |
| **executord**（执行器服务） | `core/executor/`：ExternalSessionRunner/NodeRunner + executor registry | 执行 | 词表对齐（executor 面两线同题）；不抄 |
| **workspaced**（工作区服务） | ProjectStore/DelegateWorkspace/GitWorktrees/brainWorkspace | 执行（座位分配） | **本期正主**：我方「worktree 归属」就是它的服务边界。借它的**服务化边界概念**给 M4 动词面命名（WT-CREATE/WT-REMOVE/TIP-QUERY），不借部署形态 |
| **projectd**（项目服务） | ProjectTool/ProjectCreateService/ProjectRuntimeRegistry（origin.device 定义面） | 编排（注册/路由） | 词表对齐；`origin.device` 派生路由我方已有且更早（v2.1 §A.3），不抄 |
| **linkd**（设备链接服务） | `neblink/` 包：DeviceRegistry/presence WS/relay/ChunkTransport/DeviceHmac | 通道 | 不抄——它是我们 NebLink 的**同名复刻**（审计稿 §1.6【亲验】），方向相反：通道本体以我方为准，M1-M8 消息面我方定义后它可对拍 |
| canvasd / memd / plugd / mcpd / schedd / permd / identityd / sysd | canvas / memory+recall / PluginRegistry+PluginInjection / core/mcp / TaskBoard+sched / permissions.scala+PermShield+McpToolGate / identity-sync / GatewayMain+ops | 各层（非本期范围） | 已有 10-28 分层契约四钉覆盖（§2.2 钉一-钉四），不重复展开 |

### 4.2 它 10-24 最终方案与本设计的关系（方向判定）

triple-star 10-24 最终架构（517 行，feat/stand-agent-tools 支，未合默认支）＝**中心化服务器跑 engine/TGS/agent/cap + 用户设备只装瘦客户端 + sandbox grant**（审计稿 §1.6【亲验】）。与本设计**同题不同向**：它把执行收拢到中心服务器，我们把执行下放到项目设备（P2P 设备线）；它服务多租户分发面（G4 问题域），我们服务自托管跨设备开发。10-22「互补不竞争」裁定在它 Phase 1 落地前需重估（审计稿 §3④-1）——**但本设计不依赖该重估结论**：编排/执行分离按设备切，与它是中心还是 P2P 正交。可抄的仍是模式件：闭集动词+节点零执法、准入预演、digest 钉扎、fail-closed（已在 10-28 分层契约与 10-22 可行性报告逐条登记）。

### 4.3 微服务粒度：不抄，给理由

不抄「1 front + 14 cap」的拆法。四条理由：

1. **切分轴不同**：它按「复刻面」切 14 服务（对拍矩阵的模块化），我们按「设备」切两层——编排机与项目设备各跑一个自包含引擎实例，层内不需要再切成 14 个进程才能分得开。
2. **部署成本不成比例**：自托管单机多进程现状（单 JVM 引擎 + daemon + 外部 CLI 子进程，README「self-hosted」定位），14 进程编排（K8s 路线）对单用户是净增运维面，换不来本设计要的任何一条不变式。
3. **在产机制已足**：我们要的隔离（编排/执行分层、HMAC 配对、allowSpawn 闸、闭集工具面）全部在单引擎模块边界内可表达（§1.2 盘点：15 件仅 5 件迁移且都是逻辑搬家非进程搬家）。
4. **两线纪律**：语言异构+两线独立开发是既定契约（[[20261028_triple-line-integration-contract.zh-CN]] §2.4 负面清单），把它 14 服务当部署模板＝事实上的源级依赖，违契约精神。

---

## 5. 风险表

| # | 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|---|
| R1 | #1112 落 Mac 座位止损形态，固化为存量 | 中 | 第 1 期返工+错误文档化 | §3.1：验收含架构不变式；落止损形态⇒登记已知债第一批返工 |
| R2 | M4/M5 新增面与在飞六批撞文件面 | 中 | 合并冲突/回归 | §3.4 pathspec 交集自核纪律；第 2 期尤其避开 f2451d50 的 MergeQueue 面（其合并后窗口再立项） |
| R3 | 跨设备等待面被实现成轮询/sleep，违反零超时红线 | 低 | 纪律性退化 | §2.4 逐面自查 + 实施批 spec 必含「等待面事件驱动」断言（沿 [[held-receipt-drain-silence-spec.zh-CN]] 的红线对照先例） |
| R4 | 设备侧会话重启即失联（复活只认本机 worktree+本地 transcript，10-28 审计 §二.3【亲验】） | 高（现状即如此） | 执行侧化后误期待——**实际上整会话派设备后对端会话随对端引擎**，Mac 重启不再陪葬（这正是收益）；但对端引擎自身重启的接管缺位 | 面板投影（M3）如实显示；跨引擎接管单独立项（审计 §四「大」项），不在本期承诺 |
| R5 | 准入预演（M7）读数与实际受理之间的 TOCTOU 窗 | 中 | 预演过、派发时设备满 | 预演≠承诺：执行侧受理时二次判定（govd 同款「预演与实际启动同一判定函数」模式【转引】），拒收=显式失败回执重路由 |
| R6 | 「编排层=LLM 调用」被读成「会话上下文/prompt 全留编排机」导致对端会话失忆 | 中 | 执行侧化后质量下降 | 边界声明：留编排机的是**调用与判定**；任务 brief、工具面、插件注入随会话走（字节在设备盘）。prompt 永不上机纪律（M3 <2KB 摘要面）不变 |
| R7 | triple 线 10-24 方案落地后词表漂移（stand-app 归档/四仓改名） | 低 | §4.1 对照表锚点过期 | 对照表标注 HEAD 基线（e500e51, 2026-10-24【亲验】）；漂移由 10-28 分层契约变更通知机制兜底 |

---

## 6. 未决项清单（需作者拍板）

| # | 未决项 | 问题全文 | 本稿倾向（不代裁） |
|---|---|---|---|
| U1 | **编排机唯一性** | 编排层是否只允许一台（今 Mac/T0）？第二台编排机（如 KAI 引擎自己当编排机派自己的项目）是否允许共存？多编排机并存时合并账/台账的唯一权威在哪？ | 本期定「单编排机」——与单实例控制台裁定（#986，ConsoleTakeover 会话粒度 claim）同哲学；多编排机是另一个架构问题，不混入本期 |
| U2 | **#1112 修法取向** | 走已验证的整会话派设备腿（RemoteRoute，触碰面最小），还是直接建 M4 远端 worktree 指令面（提前开工第 1-2 期基建）？ | §3.1：优先整会话腿；M4 留给第 2 期。请作者对在飞批给一针 |
| U3 | **设备侧执行腿的身份与能力面** | M5 的设备侧合并执行腿用什么身份跑：完整 delegate 会话（工具面全量）还是受控执行器（闭集动词+合并轮指令集）？权限面沿用 permd/PermShield 还是新增 device-scoped grant（triple-sandbox/v1 词表，对齐议程 §3）？ | 受控执行器（闭集），与「闭集执行器+节点零执法」纪律一致；词表对齐走议程不新造 |
| U4 | **合并道的账/手最终归属** | 本稿假定「账+放行窗留编排机、执行在项目设备」符合作者「交通引导员」裁定原意——请确认合并执行搬家不推翻 2026-10-26 裁定。 | 维持交通引导员制零改动 |
| U5 | **跨引擎接管是否进路线图** | 设备侧会话的复活/收殓/接管（R4）本期只做面板如实投影；跨引擎监督是否单独立项、何时立？ | 单独立项；触发条件＝第 1 期落地后跑一周真实跨设备作业（沿 10-22 可行性报告「真实作业数据为启动条件」纪律） |
| U6 | **Jev 判定的调用位置再确认** | 作者令「编排层负责LLM调用」⇒ Jev/判定调用留编排机、结果下发设备应用（§1.1 读法）。若作者本意是 Jev 服务部署也要随项目走（执行侧自建决策面），第 3 期范围不同。 | 前者（调用留编排机）；请作者逐字确认 |
| U7 | **挂载语义重定义** | 第 1 期把 remote-origin 挂载重新定义为「注册+投影在编排机、座位在执行机」——现有「挂载项目」面板语义/文件浏览器（brainWorkspace 服务面）对 remote-origin 项目退潮，是否认可？ | 认可；展示面留、执行面走 |
| U8 | **对 KAI 验证债的依赖** | 执行侧化后「在项目设备验证」成为默认，KAI 电池基线 157 红/PerceptionCoreSpec 四红/sbt -Xmx6G 未固化（10-28 审计 §二.4【亲验】）会挡住第 2 期验收。是否把「KAI 验证基线重立」列为第 1 期前置批？ | 列为前置小批（SBT_OPTS 固化+四红定因+基线重立），与第 1 期并行 |
| U9 | **编排层/执行层分离是否回灌 triple 对齐议程** | 本设计产出的 M4/M5 消息面定义是否作为第 2 章契约的第五条钉（执行控制协议钉点）纳入对安齐议程？ | 建议纳入（议题 1 总纲下增一条），等 U2/U3 拍板后随下一对齐窗口发出 |

---

## 7. 参照材料清单与证据分级

| # | 材料 | 位置 | 本稿用法 | 分级 |
|---|---|---|---|---|
| 1 | 多设备现状与「开发迁 KAI」根因审计（2026-10-28，台账 #994） | `~/.stand/docs/20261028_103000_多设备现状与KAI迁移根因/REPORT.md` | §1.2 机制事实（worktree 焊死本机、双形态、复活只认本机、构建位已切）与 §3.3 补齐动作映射 | 【亲验】（审计本身亲验；本稿承引） |
| 2 | USTC 张安齐名下六仓只读审计（2026-10-28，台账授权 10-28 11:46） | `~/.stand/docs/20261028_121310_zaq-ustc-repos-audit.md` | §4 全部 triple 侧事实（六仓拓扑、14 cap 同名、10-24 最终方案、成熟度） | 【亲验】（原批亲验；本稿未重跑六仓） |
| 3 | triple-star 可行性报告（2026-10-22 批，仓只读克隆 /tmp/triple-star-ro@e752ca1） | `reports/20261022_201856_triplestar-feasibility/REPORT.md`（已合主线） | §2.2 M7（准入预演）、闭集执行器、K 拍判死、§4.2 互补裁定与 10-24 转向时效 | 【亲验/转引】混编，沿用其标注 |
| 4 | triple 线结合方案：分层契约与四层路线图（2026-10-28 文档腿） | `docs/design/20261028_triple-line-integration-contract.zh-CN.md`（本仓） | §4.3 两线纪律、§2.2 零新基建哲学、对齐议程（U3/U9） | 【亲验】（本仓直读） |
| 5 | delegate 机制只读侦查（2026-10-28，main=c4cf7388c） | `reports/20261028_103847_delegate-mech-recon__flow-4a0918e2.md`（本仓） | §1.2 DelegateRegistry/Terminal/MergeQueue/复活面锚点底账 | 【转引】（锚点经本批抽验 §1.2 标注） |
| 6 | 合并道状态机总图（2026-10-30，f2451d50 批收口件） | `docs/design/20261030_merge-lane-state-machine.zh-CN.md`（本仓） | §3.2 第 2 期「逐状态不回归」的对照基线 | 【亲验】（本仓直读） |
| 7 | 本批代码直读（HEAD 9389ff749） | `DelegateTool.scala:219-333`、`DelegateWorkspace.scala:89-146`、`DeviceDelegate.scala:1-50`、`MergeQueue.scala:1-60/1211-1221`、`ProjectStore.scala:121`、`ExternalSessionRunner.scala:1-25` | §1.2/§2.1 全部【亲验】锚点 | 【亲验】 |
| 8 | 台账 #1111/#1112/#1113 | `~/.stand/tasks-v2.json` | #1112 报障事实与在飞状态（标题在台账侧即截断，病灶细节以材料 1+作者令为准） | 【亲验】 |

**材料边界申报**：①任务书所称「10-24/10-28 两次审计」，本批只寻得 10-28 两次（材料 1、2）与 10-22 可行性（材料 3）；**未寻得独立成文的 10-24 triple/star 审计件**——10-24 的 triple 侧事实（最终架构方案）经由材料 2 §1.6 亲验覆盖，若「10-24 审计」另有所指请作者补一针。②六仓只读克隆副本（zaq-audit/）已不在盘上，本稿未重克隆——材料 2/3 的锚点足够支撑对照表，无新增取证需求；若作者要求对 triple-star 当前 HEAD 重核（其 10-24 后仍在动），另批只读重克隆。③#1112 在飞批的实施细节本稿不可见（其批次材料未落盘），§3.1 全部表述以「病灶事实+作者令」为据，不臆断其已实现形态。

---

## 相关文档

- [[20261028_triple-line-integration-contract.zh-CN]]——两线分层契约（大脑/分发面/边缘执行面）与对安齐对齐议程；本稿是其「跨机放置语义权威在主线」在执行架构上的落地。
- [[20261030_merge-lane-state-machine.zh-CN]]——合并道状态机权威形态；第 2 期的不回归对照基线。
- [[20261028_213056_delegate-dual-mode-redesign__delegate-nebflow-192d10a5.zh-CN]]——delegate 双模式（极简内核/分发器）；模式选择器留编排机的既有裁定。
- [[delegate-supervision-revival-design.zh-CN]]——复活/收殓设计；R4/U5 的登记落点。
- [[device-provenance-spec.zh-CN]]——设备归属判定（设备名册自动标注）；M7 准入预演的名册面。
- [[glossary]]——delegate/worktree/合并道/Jev/NebLink 词条。

---

*本稿为设计批交付（台账 #1113）：零实施代码、零产品代码触碰、在产零触碰、不 push。全部「落点」「改为」均为设计意图陈述；作者拍板后按 §3.2 分期另批落地。*
