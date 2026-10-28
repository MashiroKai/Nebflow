---
type: design-note
status: draft
---

# delegate 双模式重构设计报告（设计批·零实施代码）

> **批性质**　本报告是**设计批**产物：按作者总令（2026-10-28 21:18 逐字）只出设计文档，零实施代码；作者确认设计后才另派实施批。全部「落点」「改为」均为设计意图陈述，不代表已实现。
>
> **输入**　[[20261028_171800_审计报告二五机制__delegate-nebflow-a13975e4]]（下称「报告二」——作者令「根据这个文档，来优化里面说到的问题」，其问题清单逐条映射见 §1，硬验收）；9.19 版本任务分发器考古（§2，tag `2026.9.19` = commit `249a83958`）；现状代码面（随文锚点，路径+行号）。
>
> **术语速查**　**delegate**（委托出去跑单个任务的后台子会话——有自己的分支/工作区/生命周期，跑完把结果交回父会话）；**极简内核执行器**（本设计模式一：delegate 去掉编排类工具后的单会话执行形态）；**分发器模式**（本设计模式二：delegate 会话升格为任务分发器，用 flow 图编排多个节点会话）；**flow / flowmap**（Flow Map——持久节点图：节点可依赖排布、可验证、可失败重入；本文中 per-delegate 作用域的图简称 flowmap）；**JeV**（已配置的决策服务集成面——引擎经 `jev` 配置块调用外部决策模型做判定/分配）；**LLM fallback**（JeV 不可用或未装配时，用引擎内置模型候选直接发一次 LLM 调用兜底的腿）；**fail-open**（失败时默认放行/返回空集继续，绝不阻塞派发）。更多词条见 [[glossary]]。

目录：

- [0. 执行摘要](#0-执行摘要)
- [1. 与报告二的问题映射表（硬验收）](#1-与报告二的问题映射表硬验收)
- [2. 9.19 任务分发器考古](#2-919-任务分发器考古)
- [3. 双模式总体架构与模式选择器](#3-双模式总体架构与模式选择器)
- [4. 模式一 spec：极简内核执行器](#4-模式一-spec极简内核执行器)
- [5. 模式二 spec：分发器模式](#5-模式二-spec分发器模式)
- [6. 插件三层机制 spec（跨两模式）](#6-插件三层机制-spec跨两模式)
- [7. 数据流与状态机](#7-数据流与状态机)
- [8. 迁移路径](#8-迁移路径)
- [9. 风险表与开放问题（待作者裁定清单）](#9-风险表与开放问题待作者裁定清单)
- [相关文档](#相关文档)

## 0. 执行摘要

**一句话**：delegate（后台子会话执行器）重构为**双模式**——由 JeV（LLM fallback 兜底）在派发瞬间按任务内容二选一：简单任务走**极简内核执行器**（现 delegate 减去 Subagent／AgentFlow 编排面的样子），复杂/链式任务走**分发器模式**（9.19 任务分发器的 flow 用法 + 现 delegate 生命周期的合体：整链一次编写、插件分配上移到 node 生成时、无 worktree、无合并节点、出口与出错重启都归分发器自己）；外部派发/结果回传面两模式完全不变。

三个设计支柱：

1. **一个选择器，两套能力面**。模式选择是 delegate spawn（生成）时的一次性判定，复用插件分配已验证的三态底座（JeV 门 → JeV 分配 → LLM 兜底 → fail-open），判定结果落 registry/任务行成 delegate 的终身属性；能力面差异全部收敛在 `buildAllowedToolSet` 尾段 delegateSession 腿的单点分叉（`AgentSessionExecution.scala:2461-2470`），不做第二套生命周期。
2. **分发器模式 = 已落成的 per-delegate flowmap 底座 + 三条新纪律**。整链一次编写（ChainWrite，`ChainWriteTool.scala:76`）、per-delegate 作用域图（N1 刀1 `8f167cee3`）、出口投创建者 delegate（S19① `88368a259`）三件已在 main；本设计新增的只有：①插件分配从「分发器点名」改为「node 生成时分配器定」；②worktree 整链禁建（NodeEdit 层已 seal，补 delegate 本体腿跳过）；③合并节点裁掉（delegate 作用域已 seal）。
3. **插件三层机制把「分配」与「注入」解耦**。description 层（plugin.json 的 description——双消费面：面板给人看、目录喂分配器）→ 分配器选包 → 注入面只灌 **Plugin.md**（包根使用指导件全文，2026-10-21 批已落地，`PluginInjection.scala:203-221`）→ agent 按 Plugin.md 的指导**自己按需读** SKILL.md（skill 层不再随分配整文注入）。本设计把这条链的格式规范、双消费面契约与存量兼容补成文。

**不做的事**（显式声明）：本设计不实现任何代码；不改 merge-queue（合并队列——「引擎永不代并、放行=提醒属主」的交通引导员制）；不动外部执行器腿（zcode/claude-code/codex——外部 CLI 执行器，恒为单会话）；不复活 9.19 已裁撤的 FlowDag DSL（声明式 flow 引擎，工具面 2026-09-06 裁撤）；不引入「achieve 逻辑」（见 §9 开放问题 Q1 的考古结论与裁定建议）。

## 1. 与报告二的问题映射表（硬验收）

报告二问题清单完整拉出如下（含各节内缺陷，不止 §6 总表六条）。处置词义：**解决** = 本设计直接给出机制答案；**部分解决** = 本设计覆盖主要面、残余面显式指出；**不解决** = 与本设计正交或属独立批次，附理由。

| # | 报告二问题 | 证据锚（报告二） | 处置 | 本设计处置说明 |
|---|---|---|---|---|
| P1 | **R1 cancel 持久性三缝隙**：已取消 delegate 仍被复活（实证 2 件）。缝隙 A＝`AgentControlTool.scala:877` fire-and-forget tell；缝隙 B＝`updateStatus` 父文件/行不匹配静默 no-op；缝隙 C＝registry 无 cancelled 墓碑、复活资格不查取消指纹 | §1.3 / §6-R1 | **不解决＋依赖声明** | 属监督持久性面，与本设计（模式面重构）正交。但**本设计放大其后果**：分发器模式下一个被误复活的分发器会拖着整条链重来，爆炸半径从单任务升到整链。故把 R1 三修（post-cancel 校验/零命中告警/墓碑＋资格复查）列为**实施批前置依赖**（见 §9 风险 R-2） |
| P2 | **R2 delegate 腿能力静默缺失**：外部执行器零注入（`ExternalSessionRunner.scala:198`）；内置腿 brief 自动分配 fail-open 空集（本链实证 5 腿 4 空集） | §2.2 / §6-R2 | **部分解决** | 三层机制（§6）把分配结果做成显式回执链（`step-plugins` 台账行＋三态 mode 词＋duty INFO 行，`PluginInjection.scala:126-148`）——空集必留痕不静默；description 层双消费面提高分配命中率。**外部腿维持零注入但明示**：spawn 回执原文已声明「no platform tools or plugins are injected」（`ExternalSessionRunner.scala:193-201`）＝报告二建议的两个选项中落定「明示不支持」。残余：外部腿真注入不做（CLI 进程无注入通道，机制上不可行） |
| P3 | **R3 账实不一致＋归档膨胀**：chain-ledger 4 条 active vs flow-map 0 节点（取消链未走归档清扫）；28 天 54,309 轮 × 44KB manifest 压缩轮转 GB 级 | §3.4-②③ / §6-R3 | **部分解决** | flowmap 随 delegate 完成消失（§5.6）：终态即 unmount＋延迟删除＋**摘要先行降级登记**（chain-archived 事件＋台账行）——活动区不再堆积死图，账实不一致窗口被生命周期收口压缩。压缩轮转触发条件复查不属本设计（独立批次建议，报告二原建议照抄有效） |
| P4 | **R4 legacy 死代码带崩溃恢复假链路**：`TurnStateStore.save` 无生产调用点，FlowTree 恢复靠文件残留；core/flow 13 文件 2,241 行 dead weight | §3.4-① / §6-R4 | **不解决＋边界声明** | 本设计零实施代码；且明确**不依赖 legacy**：分发器模式复用现役 NodeEngine 底座（报告二 §3.1 已证 legacy 引擎不承现役流量）。core/flow 清理列为独立卫生批（与本设计无冲突、可并行） |
| P5 | **R5 合并队列重启丢队**：投递通道进程内不落盘（`MergeQueue.scala:123-133`），每次引擎重启把未关账成员打成 owner-gone 人工处置；ready 前被杀连账本条目都没有 | §4.3 / §6-R5 | **不解决＋兼容声明** | 双模式**不碰 MergeQueue 机制**：模式一照走现状判定机＋合并轮；模式二因无分支无 worktree，终态**无物可并、不进队**（§5.7），merge-ledger 零新形态。通道 write-ahead（报告二建议，DeviceResultOutbox 同型可搬）仍是正解，列独立批次 |
| P6 | **R6 门户数据面浪费**：头像内联 ~267KB/帧、vendor 首开、canvas-tabs 全量重写、v1.3 分片+gzip 结构性未部署 | §5 / §6-R6 | **不解决** | 与 delegate 模式面零交集，正交 |
| P7 | **插件利用率低**：21 装 7 trusted；revoked/dispatch 两表零使用；项目级按项目挂载零实例；MCP 型插件零个 | §2.3 | **部分解决** | description 层双消费面＋node 级分配（§5.3/§6.1）提高「装了真被分到」的比率；分配面集合语义（trusted→dispatchable 过滤链，`PluginRegistry.scala:1513-1539`）不变。MCP 型零个是**生态空缺**非机制空缺，不解决（机制面 mcp.json 支持完整在位） |
| P8 | **Delegate 工具无 plugins 键**：派发方无法点名插件，模型无热挂载权 | §2.2 | **不改变（维持）＋上移替代** | 派发方点名不新增（挂载权归派发侧的权威不变，`tools/stand-plugins` 头注「绝不提供挂载/改配置通道」）；但模式二把分配**上移到 node 生成时**（§5.3）——分配面从「spawn 时对整个 brief 一次性猜」细化为「对每个 node 的 task 逐点配」，同一权威、更细粒度 |
| P9 | **flow-map 事件构成偏运维**：dead-session-reaped＋node-session-dead-reminder 合计 42%，编排语义事件小 | §3.3 图 2 | **不解决＋监测项** | 死会话清理是环境病理（分发器模式会抬高节点事件量，构成比例自然向编排语义倾斜）；不为此改审计面，实施批后复查构成读数 |
| P10 | **取消链未走归档清扫**（R3 前半的机制根因） | §3.4-② | **部分解决** | 同 P3：模式二生命周期把「终态→归档清扫」钉进收口序列（终态三清＋flowmap 消失＋摘要登记，§5.6/§7），分发器模式的取消链必须走同一条收口路径（§7 状态机终态屏障单点） |

映射总读数：解决 0／部分解决 5（P2/P3/P7/P10＋P8 的替代面）／不解决 5（P1/P4/P5/P6/P9，各附理由或依赖声明）。**本设计对报告二的主贡献是 R2 的结构解与 R3 的生命周期解；R1 是前置依赖；R4/R5/R6 是正交独立批。**

## 2. 9.19 任务分发器考古

### 2.1 考古方法与对象辨析

- **版本锚**：tag `2026.9.19` = commit `249a83958`（merge: presdial-cfix-impl into main，2026-09-19 08:09:18 +0800）。下文「9.19 时点」均指该树。
- **两个「flow」世系必须分开**（作者口中的「任务分发器」是世系甲，不是乙）：
  - **世系甲＝NodeEdit 任务分发器**（作者关键词全命中此世系）：`core/project/FlowMapStore` + `core/project/NodeEngine` + `core/tools/NodeTools`（NodeEdit/NodeList/NodeCancel 三件，2026-09-01 「阶段 0b—Project/FlowMap/NodeEngine + Node 工具集」批诞生，commit `37301fc9d`）。9.19 时点为 **per-project** 形态（图长在项目上；per-delegate 作用域化是 2026-10-06 N1 刀1 `8f167cee3`）。配套 seed agent `seed/agents/project-dispatcher/`（system.md 11 行：NodeList 先读盘→排节点；worktree 纪律；合并节点复用纪律；Mail node:\<id\> 补充纪律）。
  - **世系乙＝FlowDag DSL**（声明式 flow 引擎）：`core/entity/FlowDagCompiler`（#424，36 错误码编译前端，commit `47b9f2147`）+ `FlowDagExecutor`（确定性执行器：计数屏障、动态扇出、maxLoop 回边）+ `core/flow/`（FlowTree 等）。其工具面 **FlowExecute/FlowTrigger/FlowReport 已于 2026-09-06 裁撤**（9.19 树内 `core/tools/registry.scala:60-63` 注释原文「FlowTrigger / FlowExecute / FlowReport retired 2026-09-06」）——即 **9.19 时点世系乙已无活跃 spawn 点，只剩引擎残留**。
  - 判定：「9.19 任务分发器的 flow，一样用法」锚定**世系甲**（NodeEdit/agentflow 用法）；世系乙不复活（§2.3 清单第 14 行）。这也与作者另一句「不用 achieve 逻辑」「合并节点不需要」的方向一致：要的是编排用法，不是声明式 DSL。
- **achieve 考古结论**：全仓 git 史（含 `backup/pre-mit-history-cleanup` 分支、全部 tag）grep `achieve/Achieve` **零机制命中**——仅有 TaskTool 台账注释里的英文动词（`TaskTool.scala:222-229`）与 slideblocks 插件文档、monaco vendor 件。9.19 树内同样零命中。**「achieve 逻辑」是作者记忆词，不是可考古的代码机制**；本设计按最可能所指解释并列为开放问题 Q1（见 §9）。

### 2.2 9.19 分发器机制要点（代码出处）

| 机制 | 9.19 时点形态 | 出处（tag `2026.9.19`） |
|---|---|---|
| 工具面 | NodeList 先读盘 → NodeEdit 建/接线 → NodeCancel 停；Mail 问询；TaskBoard 管任务单 | `seed/agents/project-dispatcher/system.md`（11 行全）；`core/tools/NodeTools.scala` |
| node 接线 | `in`（barrier 入边）/`deps`（仅完成信号）/`out`（边集：门集⊆{pass,failed,fail}×mode∈{result,signal,loop}）；out 设置事务内反向镜像目标 in | `NodeTools.scala` 头注（边模型单权威）；`out` 参数文档（:1057 邻段） |
| plugins per-node | NodeEdit `plugins` 键声明，漏传即拒（`NODE_PLUGINS_UNDECLARED`）；目录三级过滤 enabled→trusted→dispatchable | `NodeTools.scala:1057` 邻段 plugins 行；`PluginRegistry.renderCatalog`（今 `PluginRegistry.scala:1513-1539`） |
| **worktree 分配** | NodeEdit `worktree=true`（create-time only）→ 即建隔离 git worktree `.nebflow/worktrees/<derived-from-node-name>`（同名分支 off main，fail-fast，edit 拒） | `NodeTools.scala:1103`（schema 行）与 :1303-1308（布尔显式化闸） |
| **合并节点** | `merge=true`（create-only）批落汇点：全上游完成才触发、上游失败转 blocked、必须 in≥1（`NODE_MERGE_REQUIRES_UPSTREAM`）、禁携 worktree；in≤4 硬闸（`NODE_MERGE_IN_CAP`，2026-09-11 作者拍板「升引擎硬闸」）；已触发冻结 in（`NODE_MERGE_FIRED_NO_IN`） | `NodeTools.scala:36-44`（MergeInCap/MergeFiredStatuses）、:1105（merge schema 行） |
| **loop（判定式）** | ①校验期 loop detect：DAG 判环拒建，`:loop` 控制边豁免（nrloop 一期 2026-09-12）；②loop 节点＝worker/verify 双会话＋`maxRounds`＋LoopBudget（rounds/maxWallMs 双维熔断）；③loop 门集不变量：out 必须同时覆盖 pass+failed（P-2 强读法） | `NodeTools.scala:85-93`（deferredLoopEdges）、:276-302（loopGateViolation）；`FlowMapStore.scala:208-213`（控制边豁免） |
| **出口＝顶层 agent** | 末端节点 `out=Nebula` → root 通道通知**顶层 agent**（9.19 图 per-project，rootSessionId＝根会话）；root 通知打包窗（notifybatch 批 2026-09-18：quietMs 打包/条数上限） | `NodeEngine.scala`（Nebula 通知抑制表 :176-182；打包窗 :98-106）；notifybatch 批 `37d208643` |
| verdict 路由 | `role=verifier` 判定节点＋`(fail)<worker>:loop` 回边重跑；无 `VERDICT: PASS` 锚定→降级 Pass 直通 | `NodeTools.scala`（verifier 语义段）；`VerdictReader.scala:15-30`（今锚，报告二 §3.1） |
| 消息路由三语义 | `Mail node:<id>`：running→会话内注入（`[NODE-MESSAGE]` 头）/wiring·pending→追加进 task/terminal→拒绝（`NODE_TERMINAL_NO_MESSAGE`）；每条必写 `node-message` 审计 | 今 `NodeDelivery.scala:229-269`（三态路由单点）、:326-332（审计）；9.19 同构 |
| 归档/恢复 | flow-map-archive 整链归档；`restoreChain` 整链复活；boot 恢复＝`ProjectCrashRecovery`（crash-recovery 批 2026-09-07：快段认领＋慢段 rehydrate） | `FlowMapStore` 归档区；[[design/delegate-supervision-revival-design.zh-CN]] §1 考古表 L1 行 |
| 分发器纪律（提示词） | 「一 node＝一个会话能完成的最小单元」；worktree 只在并行可能冲突时建；合并节点复用、一合并节点 ≤4 worktree；同 kind 补充走 Mail 复用旧节点不另起链 | `seed/agents/project-dispatcher/system.md` (1)-(4) 条 |

### 2.3 9.19 功能清单 × 新令保留/裁掉标注

| # | 9.19 功能 | 新令（2026-10-28 21:18）裁定 | 说明 |
|---|---|---|---|
| 1 | NodeList 全态盘点先行 | **保留**（模式二标准流程步） | 分发器首动作 |
| 2 | NodeEdit 建/编节点（task/in/deps/out/门集/abandon/reactivate/restoreChain/notify） | **保留**（能力与 9.19 一致） | 用法不变，作用域换成 per-delegate |
| 3 | 整链一次编写 | **9.19 无此能力**（一 node 一次 NodeEdit）→ 新令要求 → **ChainWrite 承接** | ChainWrite 已落 main（`e8d5f98f3`，2026-10-07：一次调用整链登记、全过或整拒、单事务落盘）；AgentFlowTool 是其薄壳（`AgentFlowTool.scala:35` MaxSteps 同源）。「允许一次完成整个链的编写」＝现成能力收编进模式二 |
| 4 | loop detect＋loop 节点 worker/verify＋LoopBudget | **保留**（「9.19 的 loop 等功能都要有」） | 判定式循环原样 |
| 5 | verdict 路由（verifier＋(fail)worker:loop 回边） | **保留** | loop 的判定半边 |
| 6 | retry（下游持有的失败自动重试 {upstream,max}） | **保留** | 同上族 |
| 7 | **合并节点**（merge=true 全族闸） | **裁掉**（「合并节点不需要」） | delegate 作用域已 seal（`NODE_MERGE_UNSUPPORTED`，`e8d5f98f3` D1/D2 seal）；per-project 图不波及 |
| 8 | **worktree 分配**（node worktree=true） | **裁掉**（「不需要分配 worktree，不允许再创建 worktree（整链禁止）」） | delegate 作用域已 seal（`NODE_WORKTREE_UNSUPPORTED`，同上）；模式二 delegate 本体腿另需跳过建分支/座位（§5.7） |
| 9 | plugins per-node 声明（分发器点名） | **改造**（「插件分配上移到 node 生成时；分发器不参与插件分配」） | 分发器的 plugins 键改拒（§5.3）；分配器（JeV/LLM）在 node 生成时按 task 定 |
| 10 | 出口＝顶层 agent（out=Nebula root notify） | **改造**（「flow 出口＝分发器自己（9.19 是顶层 agent）」） | per-delegate 作用域的 Nebula 边已重映射投创建者 delegate（NotifyTargetKind.CreatorDelegate，`88368a259` S19①，`DelegateFlowHosts.scala:179`）＝现成机制收编 |
| 11 | Mail node:\<id\> 三态路由 | **保留** | 分发器修链主通道 |
| 12 | TaskBoard 项目任务单 | **裁掉/降级**（模式二分发器无项目上下文） | per-delegate 图无项目板块头（`DelegateFlowHosts` mount 传 board/projectGoal=None，N1 刀1 H1）；台账面由 ChainStore 审计行承担。见 Q8 |
| 13 | FlowDag DSL 世系（编译器/执行器/参数化/动态扇出） | **裁掉**（不复活） | 工具面 2026-09-06 已裁；「能力与 9.19 一致」锚定世系甲 |
| 14 | achieve 逻辑 | **不引入** | 考古零命中（§2.1）；解释为「目标自宣告式循环」，与判定式 loop 相斥；Q1 待作者确认 |
| 15 | 监督恢复（节点轨 boot 恢复＋BackoffSupervisor checkpoint 续跑） | **保留** | 「出错了也是分发器自己负责重启」的引擎侧承接（§5.5） |
| 16 | root 通知打包窗（quietMs/条数上限） | **保留** | 出口改投分发器后，通知风暴面同构存在，机制照用 |

## 3. 双模式总体架构与模式选择器

### 3.1 总览

```
父会话 Delegate(task, executor=…)
        │ spawn（唯一入口 DelegateTool.spawnBackground，DelegateTool.scala:655）
        ▼
┌─ 模式选择器（新增腿，判定机邻位）──────────────────────────┐
│ JeV 门(configured∧enabled) → JeV 问对(need_dispatcher/fit) │
│   → LLM fallback(单次 JSON) → fail-open 缺省 kernel        │
│   → 人工覆盖：task 参数 mode=kernel|dispatcher|auto         │
└──────────────┬─────────────────────────────────────────────┘
       mode=kernel │              │ mode=dispatcher
───────────────────▼──────────┬───▼──────────────────────────
模式一 极简内核执行器          │ 模式二 分发器
 · 现能力面 −{Subagent,        │ · 能力面 −{Subagent} +{ChainWrite,
   AgentFlow,+node 工具面}      │   NodeEdit,NodeList,NodeCancel}
 · 插件分配流程保留（spawn 时） │ · 分发器本体零插件
 · worktree 判定机照旧         │ · 整链禁 worktree；无合并节点
 · MergeQueue 判定机收口照旧   │ · ChainWrite 整链→逐 node 分配插件
                               │   →flow 执行(loop/verifier/retry)
                               │   →出口回投分发器→终态 flowmap 消失
───────────────────────────────┴───────────────────────────────
 外部执行器腿（zcode/claude-code/codex）：两模式均不参与——恒单会话、
 零平台工具零注入（ExecutorRegistry.scala:79-102；回执明示
 ExternalSessionRunner.scala:193-201）
```

两条不变的公共面：**派发/结果回传面不变**（父会话看到的一律是「后台任务＋完成系统消息＋delegate:\<id\> 续聊地址」）；**Mail 续聊复活腿不变**（同点复用 spawn，`DelegateTool.scala:655` 头注）。

### 3.2 模式选择器（ModeSelector）spec

**位置**：`DelegateTool.spawnBackground` 内、worktree 判定机（`DelegateJudge.DelegateJudgePort.decideWorktreeDetailed`，`DelegateWorkspace.scala:106`）邻位新增一条判定腿——只对**内置执行器腿**生效（外部执行器腿无模式概念，直接 kernel 形态语义）。

**决策链（复用插件分配三态底座，`PluginAllocatorMachine.scala:19-25` 同构）**：

1. **人工覆盖优先**：Delegate 工具新增可选 `mode` 参数（enum `kernel|dispatcher|auto`，缺省 `auto`）。非 auto 即权威，跳过分配器，零模型调用。
2. **JeV 门**（`JevGate`，applied = configured && enabled，`core/jev/JevGate.scala` 头注）：gate off / jev 块缺席 / provider 未装配 → 走 LLM fallback，并留 JevFallback 审计（fallback 从不静默，`JevSettings.scala:49` 起热读＋secret 缺失自动关腿同款纪律）。
3. **JeV 分配腿**：沿用 `JevAllocator` 方法论三件（`JevAllocator.scala:9-55`）：状态模板（固定前导＋任务文截断＋候选集块）、问对（`need_dispatcher` CHOICE{yes,no}＋`fit_dispatcher` SCORE{poor,partial,good}，一次批调）、成员规则（P(yes)≥0.5 → dispatcher）。**与插件分配的差异只在候选集**：这里候选集是两个模式而非 N 个插件——问对退化为单目标判定，模板更短。
4. **LLM fallback 腿**（`LlmAllocator` 同件复用）：单次 JSON 调用内置模型候选，输出 `{"mode":"kernel"|"dispatcher","reason":"…"}`；解析失败/无候选 → fail-open。
5. **fail-open 缺省方向＝kernel（极简）**。方向性安全论证：kernel 误判复杂任务＝单会话硬扛，质量降级但零机制风险、零额外开销；dispatcher 误判简单任务＝白付链开销。两错不对称，缺省取便宜的一侧（与插件分配 fail-open 空集同一哲学）。
6. **Inert 态**（总闸关闭——本设计对应 `delegate.dualMode.enabled=false`，见 §8）：不做任何选择，**全量走现行为**（＝kernel 能力面＋现插件流程），零审计行。

**选择依据（提示词骨架，单点可调）**：选择器状态模板中给模型的判据文字（实施批可调，此处为设计基线）——「单会话单产物、无阶段依赖、无并行分支诉求 → kernel；任务含多阶段依赖/需要并行多腿/需要多插件协作/显式要求编排 → dispatcher」。

**误判后果与人工覆盖路径**（作者令点名章节）：

| 误判 | 后果 | 纠正路径 |
|---|---|---|
| 复杂任务判成 kernel | delegate 单会话硬扛，产物质量/吞吐降级；无机制风险 | ①父会话续聊 `delegate:<id>` 让它自己拆（Mail 腿）；②终态后重派带 `mode="dispatcher"`；③审计行 mode-decision 供事后追认调判据 |
| 简单任务判成 dispatcher | 白付链建立开销与调度延迟；结果正确性不受影响（链可退化为单节点） | 同上重派带 `mode="kernel"` |
| 分配器全败 | fail-open → kernel（缺省安全侧） | mode-decision 台账行记录 leg=fail-open 与原因，作者可改缺省方向（配置旋钮，§8） |

**记账**：每次选择写一行 `mode-decision`（ChainStore 审计行族，同 `delegate-spawn` 形态）：`{delegateId, mode, leg: manual|jev|llm-fallback|fail-open|inert, reason}`——误判可追认、判据可回归调优。

## 4. 模式一 spec：极简内核执行器

**定义**（作者逐字）：「等于目前的 delegate 移除 subagent 和 agentflow 的样子；外部仍走正常 delegate 路径。」

### 4.1 生命周期（零变化）

spawn（`spawnBackground`）→ 三处登记（DelegateRegistry 持久地址簿＋ChainStore/TaskLedger＋任务行）→ worktree 判定机（判是/判否一律建 `delegate/<id>` 分支＋座位，`DelegateWorkspace.scala:89-146`）→ 监督三线（BackoffSupervisor 进程内退避 respawn——5s×2^n 封顶 60s、5 分钟窗 ≤2 次的滑动窗熔断；DelegateRevival 跨进程复活七闸；watchdog 在飞 ≥10min 无活体判 failed）→ 判定机收口（终态唯一写点 `notifyParentAndStop` 终态屏障）→ Release（就地放行）或 HoldForMerge（**等待所有权**——入 MergeQueue 排队，轮到即提醒属主自并，机械验收关账 `MergeAcceptance.scala:12` 零 LLM）→ 终态三清＋五件。全文锚见报告二 §1 与 [[design/delegate-supervision-revival-design.zh-CN]]。

### 4.2 能力面（哪些工具被移除）

| 工具 | 现状（内置腿 delegate 会话） | 模式一 |
|---|---|---|
| Subagent（只读侦察子代理，阻塞式） | 挂载于执行 agent 机制面 `NebflowFixedTools`（`AgentCore.scala:240-241`） | **移除**（显式摘除腿，见下） |
| AgentFlow（一次性步骤图薄壳） | 同上（`AgentFlowTool.scala:53` 家族位） | **移除** |
| ChainWrite＋NodeEdit/NodeList/NodeCancel（node 工具面 `DelegateNodeToolsFace`） | delegateSession 腿追加（`AgentSessionExecution.scala:2461`） | **随 agentflow 一并移除**（本设计延伸裁定，作者逐字只点名两件——延伸理由：该四件是链编排机器，极简内核无编排诉求；待作者确认，Q2） |
| Mail | delegateSession 腿追加（`AgentSessionExecution.scala:2470`） | 保留（续聊是外部派发面的一部分） |
| TaskBoard/node_report/delegate_report/AskUser | 既有挂载 | 保留（报告/交互面非编排面） |
| 基础工具（Read/Write/Edit/Bash/Glob/Grep…） | 既有 | 保留（执行内核本体） |

**落点（设计）**：`buildAllowedToolSet` 尾段 delegateSession 腿（`AgentSessionExecution.scala:2444-2470`）按 record.mode 分叉——kernel 腿在追加 `DelegateNodeToolsFace` 之前截断，并从有效集显式减去 `Subagent`/`AgentFlow`（NebflowFixedTools 是执行 agent 全局机制面，root 等非 delegate 会话不受影响——摘除只在 delegateSession 判据内，双保险形态与 TaskBoard/node_report 同款：挂载面＋工具内身份拒绝）。

### 4.3 插件注入链（description→选择→Plugin.md 注入）

模式一**保留插件分配流程**（作者逐字），链路即现状＋三层增强（§6）：

1. spawn 时 `PluginInjection.allocatePreparation`（`DelegateTool.scala:694`→`PluginInjection.scala:68-105`）：总闸 → PluginCatalog 目录（`CatalogEntry(name, description)`，`PluginCatalog.scala:25`——**description 层即分配器唯一能力输入**）→ PluginAllocator 三态（jev/llm-fallback/fail-open）。
2. 命中集合 → `resolvePreparation`（`PluginInjection.scala:156-187`）：逐包解析＋读包根 **Plugin.md 全文**（`injectedPluginBlock`，:203-221；缺失/不可读＝显式拒 spawn 不静默）→ `<injected-plugins>` 块进会话首条消息＋pluginMcp.acquire（引用计数，失败回滚整批）。
3. SKILL.md **不再随分配注入**（2026-10-21 批起，:189-193 注）——agent 按 Plugin.md 内的指导自己按需 Read（skill 层，§6.4）。
4. 分配审计：`step-plugins` 台账行（delegate 腿为 `delegate-spawn` 行族）＋duty INFO 单行（`PluginInjection.scala:228-237`）——空集必留原因（三态 mode 词）。

外部执行器腿：零变化（任务文直给 CLI，回执明示零注入）。

## 5. 模式二 spec：分发器模式

**定义**（作者逐字合成）：9.19 任务分发器＋现 delegate 的结合——「移除 subagent 工具；不给分发器本身分配插件；只走 agentflow 模式（＝9.19 任务分发器的 flow，一样用法），但允许一次完成整个链的编写；插件分配上移到 node 生成时；9.19 的 loop 等功能都要有；合并节点不需要；flow 出口＝分发器自己；出错了也是分发器自己负责重启；每个 delegate 有自己的 flowmap，可编辑，随 delegate 完成 flowmap 消失；不用 achieve 逻辑；外部仍走目前的 delegate 模式；不需要分配 worktree，不允许再创建 worktree（整链禁止）。」

### 5.1 分发器会话形态

- **工具面**：−{Subagent} ＋{ChainWrite, NodeEdit, NodeList, NodeCancel}＋Mail＋report 族。与现状 delegate 的差异只有两点：Subagent 摘除；node 工具面保留（kernel 才摘）。
- **分发器本体零插件**（作者逐字）：spawn 腿跳过 `allocatePreparation`，审计行记 `allocation=dispatcher-off`（Inert 族新词，语义＝「模式性豁免」非故障）；pluginMcp 零 acquire。分发器的编排能力全部来自平台工具（非插件），查询需求走 `tools/stand-plugins` 只读 CLI（非插件、无挂载面——是否给分发器挂此 CLI 见 Q7）。
- **座位与分支**：跳过 worktree 判定机（`DelegateWorkspace.prepare` 不调用）——不建 `delegate/<id>` 分支、不建座位，Binding＝in-place（registry 记录 cwd＝基工作区、无分支）。这是对现状「判是/判否一律建分支」的**显式偏离**，仅限 dispatcher 腿；kernel 腿照旧。
- **flowmap 宿主**：spawn 成功即 `DelegateFlowHosts.mount`（N1 刀1 既有腿，fail-soft）——per-delegate 作用域图（`.nebflow/flow-maps/<delegateId>/`）。

### 5.2 整链一次编写：输入格式与校验

**输入格式**（ChainWrite 既有 schema，`ChainWriteTool.scala:76`）：

```
ChainWrite(chainName?, nodes=[{id, name?, task, deps?, description?}])
```

- `id`：短唯一 token（地址）；`name`：可选人读名（唯一）；`task`：自足任务文（该 node 的完整 brief）；`deps`：上游 id/name（完成信号闸）；`description`：≤60 字符一行卡面。
- 语义（9.19 一致性）：`in`/`deps`/`out` 的接线语义由 NodeEdit 承接——整链登记后，分发器可用 NodeEdit 精修任一 node 的 out 门集/verifier/loop/retry/notify（能力与 9.19 一致的落点）。

**校验（all-or-nothing，任一不过整链拒收零落盘零 spawn，`ChainWriteTool.scala:19` 头注＋:223 Kahn 判环）**：nodes 非空、≤MaxNodes=12（`ChainWriteTool.scala:55`；上限讨论 Q6）、id 唯一合法、name 唯一且不与 id 撞、deps 闭合（批内引用）、Kahn 判环（有环整单拒）、task 必填、寻址无歧义。

**模式二专属校验增强（新闸，设计）**：

| 闸 | 触发 | 码（建议） |
|---|---|---|
| 分发器禁点名插件 | nodes[].plugins 键在场（模式二链） | `NODE_PLUGINS_DISPATCHER_ALLOCATED`（文案指路：插件由分配器在 node 生成时定，见 §5.3） |
| worktree 禁建 | nodes[].worktree 键在场 | 复用既有 seal `NODE_WORKTREE_UNSUPPORTED`（`e8d5f98f3` 已落，零新码） |
| 合并节点禁建 | nodes[].merge 键在场 | 复用既有 seal `NODE_MERGE_UNSUPPORTED`（同上） |
| node 级分配结果回执 | —（非闸，回执增强） | ACK 逐 node 附 `{nodeId, plugins, allocation}` 分配摘要行 |

### 5.3 node 生成时的插件分配协议（分配上移）

**时序**（作者令「分配器（Jev）根据每个 node 分到的任务内容，在 node 生成时给该 node 选插件能力；分发器不参与插件分配」）：

1. 分发器会话调 ChainWrite（整链一次）。
2. `registerChain` 落库**前**（仍处 0 spawn 阶段），对每个 node 独立跑分配：`PluginInjection.allocatePreparation(subject = "node:<id>", taskText = node.task)`——分配器三态链（JeV→LLM→fail-open）与 kernel 腿完全同件（§4.3），**输入从整份 brief 换成单 node 的 task**（现状步级分配同款输入面，`PluginInjection.scala:125` 注「步级分配输入＝节点任务文」——本设计把这条已有路径的触发时机从「node spawn 时」前移到「链登记时」，分配结果物化进 NodeDef.plugins，node spawn 走声明集权威路径零再分配）。
3. 分配结果逐 node 写入 NodeDef.plugins（与声明集同字段、同注入面）→ node spawn 时 `resolvePreparation` → Plugin.md 注入（§6.3）。
4. 审计：沿用 `step-plugins` 台账行（`PluginInjection.scala:126-148`，键挂链账本）＋ACK 分配摘要（§5.2 回执增强）。**分发器全程零参与**：其工具 schema 无 plugins 键（§5.2 闸 1），分配细节只在台账与回执可见。

**失败语义分层**（与现状同款，`PluginInjection.scala:44-46` 口径）：分配失败＝fail-open 空集**照走**（不阻塞整链，台账留痕）；解析/注入失败（选中包被封禁/Plugin.md 不可读）＝`Left` **整链显式拒**（0 spawn 原则不破）。惰性态（disabled/off/auto-off/empty-catalog）＝全节点空注入，合法无能力面。

**开销与批化选项**：缺省逐 node 独立分配（≤12 次，语义与现状步级一致）；可选优化（实施批裁定）＝JeV 问对批化（一次调用带 N×2 问，方法论模板不变仅批行扩展）。见 §9 风险 R-4。

### 5.4 flow 执行：loop 保留／无合并节点／无 achieve

- **loop 保留**：loop=true 节点＝worker/verify 双会话（`NodeStarter.scala:391-394`，worker 会话 `node-<8hex>`）＋maxRounds（缺省 5）＋LoopBudget 双维熔断（rounds/maxWallMs）＋门集 pass+failed 双覆盖不变量＋verifier `(fail)<worker>:loop` 回边（`:loop` 控制边不入 DAG、豁免判环）——全部为 9.19 已定型机制的现役延续，模式二零改动继承。
- **无合并节点**：merge=true 已 seal（§5.2）；批落汇点职能由「多入边 barrier 节点」承担（multi-in＝barrier，等齐全部上游结果才启动——9.19 语义保留，这不是 merge 节点，是普通节点的 barrier 语义）。分发器提示词纪律同步改写：9.19 的「复用合并节点、一合并节点 ≤4 worktree」两条随 worktree/合并节点一起退役。
- **无 achieve**：不引入目标自宣告式循环（agent 自评「已达成」即收的迭代形态）；一切循环走判定式（verify 判词驱动），与 9.19 loop 语义同源。Q1 待作者确认所指。
- **verifier/retry/notify/abandon/reactivate/restoreChain**：9.19 全族保留，模式二原样可用。

### 5.5 出口与自重启语义

- **出口＝分发器自己**：链末端节点 out=Nebula 的投递目标＝创建者 delegate 会话（NotifyTargetKind.CreatorDelegate 重映射，`DelegateFlowHosts.scala:179`，S19① `88368a259`）——「9.19 是顶层 agent」到「出口＝分发器自己」的改造**已由该机制承载**，模式二只是把 per-delegate 作用域定为唯一形态。creator live＝ImmediateInput 直投恰一次；creator 不 live（崩溃窗）＝结果 Park 滞留不旁路，复活后 `redeliverUnconsumedRootResults` 补投恰一次（N1 刀1 ⑷ 物理背书，H3/H4 spec 实证）。
- **分发器出错自重启＝监督链自动接住，引擎不代编**：①进程内崩溃→BackoffSupervisor 退避 respawn＋transcript checkpoint 续跑（断点续作非从头）；②引擎重启→DelegateRevival L3 复活腿（七闸＋boot cap，`DelegateRevival.scala`；其第 5 步**已含作用域宿主重挂**——flowmap 投递通道随复活恢复，[[design/delegate-supervision-revival-design.zh-CN]] §2.3）；③复活后的分发器拿到 `[system]` 续跑指令，按 9.19 分发器纪律自主处置在飞链（NodeList 盘点→注入修正/取消接管/判临时）——**「分发器自己负责重启」的准确语义：引擎负责让分发器活着并拿到现场，重启后干什么由分发器自己决策**；引擎不新增「代重跑整链」逻辑。
- **链内节点失败**：重试（retry 闸）/判词回边（loop）/blocked 申报三态照 9.19；分发器经完成通知与 Mail 通道知情并修链。
- **链级预算缺口（显式）**：节点级有 LoopBudget、分发器级有 DelegateBudget（wall-clock 3600s 复活重挂）——**整链无独立 wall-clock 预算**，长链生命周期实际锚在分发器预算上；是否补链级预算见 §9 R-5。

### 5.6 per-delegate flowmap 生命周期

- **生成**：spawn 成功 → `DelegateFlowHosts.mount` → `FlowMapStore.openScope(delegateId, …)`——一 delegate 一图、存储自包含随迁（flow-map.json/results/tasks/chain-ledger 同源派生，N1 刀1 A1，validScopeId 字符集契约 fail-closed）。
- **编辑入口**（作者令「可编辑」）：①**分发器会话自身**＝第一编辑者（NodeEdit/NodeList/NodeCancel 四件挂其工具面，A4 授能/防逃逸双保险：挂载面＋`resolveRuntime` 作用域 fail-closed——delegate 只解析自己作用域图，宿主查无 `DELEGATE_SCOPE_UNAVAILABLE`、显式他域拒 `DELEGATE_SCOPE_FOREIGN`）；②**人／父会话**＝只读面（面板 REST 投影＋`ChainsReadFace` 行投影，A8 既有）＋Mail 建议通道（人对父会话说、父会话 Mail delegate:\<id\> 转达）。**写面是否开放给面板**列 Q3（本设计默认不开放——编辑权威单点在分发器会话，防双头）。
- **随完成消失（落盘语义，本设计裁定）**：
  1. 终态（completed/failed/cancelled，经终态唯一写点）→ `DelegateTerminal.onTerminalCleanup` 序列内 `DelegateFlowHosts.unmount`（现状幂等腿，ready-to-merge 停靠不经此面——模式二无合并轮，必经）。
  2. unmount 前，链摘要先行降级登记：链级归档 sweep 产 chain-archived 事件＋ChainLedger 摘要行（现状 `sweepCompletedChainsDetailed` 机制，blocked 永不自动归档）——**图谱可考古**。
  3. unmount 后，作用域目录 `.nebflow/flow-maps/<delegateId>/` 进入**延迟删除**：宽限窗（缺省 10min，sys prop 可调）内结果文件仍可经 `ChainsReadFace` 盘面探针只读补拉（防终态通知与取件竞态）；宽限窗过后删除目录。删除是**结果已交付**前提下的卫生动作，与「零删除纪律」的 DelegateRegistry 地址簿不同层（registry 归档照旧）。
  4. 消失的边界：跨进程复活窗内（boot sweep 认领中）目录不删——复活判据第 4 步（座位/盘面在场）优先。
  - 备选形态（作者可改裁）：只 unmount 不删、目录走 TTL 归档（现状行为——即报告二 §3.3 的 284 个作用域目录堆积形态）。本设计选删除的理由：活动区账实一致性（P3/P10）＋盘面卫生；考古面由第 2 步摘要行承担。
- **孤儿收割**：delegate 会话已灭且图内无非终态 → `reapOrphans` 实例灭盘面存（现状 `DelegateFlowHosts.scala:343`）——模式二下「盘面存」被第 3 步延迟删除接续，孤儿目录不再永驻。

### 5.7 无 worktree 下的并发文件安全策略

**威胁模型**：模式二整链禁 worktree ⇒ 全部 node 会话＋分发器共享同一基工作区；N 个并行 node 可能同时写同一文件/目录。

**三层防线（设计）**：

1. **拓扑层（主防线，分发器纪律）**：9.19 纪律「只在并行可能冲突时建 worktree」在新令下翻转为**「可能写冲突就串行化」**——分发器在整链编写时用 `deps` 边把写域可能相交的任务排成串行；写域独立的任务才并行。此纪律写进分发器会话的提示词段（单点），并在 ChainWrite ACK 里回显一行提醒（静态提示，非校验）。
2. **声明层（软闸＋审计）**：nodes[] 增**可选键 `writeScope`**（自由文本路径/目录声明，可选不强制）：引擎对**同时活跃**（running）的节点对做静态相交启发（字符串前缀/目录包含比较，纯函数）——相交即写一行 `write-scope-collision` WARN 审计事件（flow-map-events.jsonl），**不拒绝不阻塞**（声明是软闸：误报零代价、漏报靠纪律层兜底）。
3. **工具层（现状即有）**：会话内文件工具已有读后写追踪/编辑守卫面（Edit 工具 exact-match 天然防并发覆盖写——stale 编辑直接报错重读）；**不新增引擎级强制文件互斥**（FileLockManager 现状服务面有限，强制化成本与误锁风险高——是否升级为强制互斥列 Q4）。

**与 MergeQueue 的关系**：无分支无 worktree ⇒ 链产物直接落基工作区，**无物可并**——模式二 delegate 终态走 Release 直收，不进 MergeQueue（merge-ledger 零新形态）；分发器**本人**也不建分支（§5.1）。模式一不受影响（判定机照旧）。

**残余风险显式化**：两 node 绕过纪律硬写同一文件＝后写覆盖先写（无引擎互斥）；缓解＝§7 状态机里分发器的修链职责＋审计可追责。若作者要求强保证，Q4 裁定升级路径（FileLockManager 强制化或 node 级 sandbox checkout）。
