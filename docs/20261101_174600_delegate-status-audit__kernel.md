---
type: audit
status: done
related: "[[20261028_171800_审计报告二五机制__delegate-nebflow-a13975e4]]"
---

# delegate 子系统现状审计（2026-11-01 17:46 快照，kernel 单会话）

> 批令（作者 17:37：「审查一下我们delegate的目前的现状。给我一个报告」）。本批 **kernel 单会话，不派链不派节点**；只读源码 / 台账 / 日志 / 盘面，不改产品代码。事实截至 **2026-11-01 17:46**（运行时快照时刻见 §4），所有锚点可复核：源码 = 主仓 main（tip 7d18dabb0），台账 = `~/.stand/chains/*.json`、`~/.stand/delegates/*.json`、`~/.stand/merge-ledger.json`，日志 = `~/.stand/logs/stand.log`。图谱：[[index]] · 词表 [[glossary]]。

## 0. 结论先行

**执行面稳定，编排面未通。** 今天 delegate 一共派发 **64 次**、落地合并 **31 次**、合并队列快照时刻 **0 悬空**——kernel（单会话执行器）这条腿的表现是健康的：55 次 kernel 派发里没有一起今天的断供形态。问题全部集中在 **dispatcher（分发器）模式**：今天 7 次派发，**6 次判词逐字同为折叠式 "could not complete"**，只有 1 次走到合并；四次断供的链工作（今天实为 **10 个真提交**）至今滞留在三条无人收货的分支上。

一句话定性：**病在生命周期与结果必达，不在 agent 能力**。链的生死连着会话终态（[[20261101_165531_dispatcher-chain-lifecycle-rca__kernel]] §3 已证），结果回投的四条通路今天仍全断；而「批次未完」这个状态在状态机里依然不存在（同报告 §4）。今天新增两条实证（见 §2.6）：**续聊腿不继承模式**（dispatcher 被续成 kernel，两次）、**两批死于上游 provider 格式错**（非 delegate 机制）。

先修三件（详情与其余项见 §5 优先级表）：

1. **续聊腿 mode 继承**（一行接线级）——今天两次把 dispatcher 续成 kernel，等于让一个编排者坐进执行者座位；
2. **RCA 小修档 S1/S2/S3**（诚实化）——报表不再把「批次未完」写成 completed；
3. **滞留提交收割 + S4/D4**（孤儿可管）——10 个提交今天仍在无主分支上，孤儿分发器今天还活着。

灰度结论建议：**dispatcher 在 S1-S5 / D2 落地前不宜扩面**；kernel 作为缺省执行形态已是事实（55/62）。

## 1. 架构现状盘点（十面）

| # | 面 | 现状一句话 | 关键锚点 |
|---|---|---|---|
| A | Delegate 工具面 | 一个入口三腿（内置 nebflow / 外部 CLI / 远端设备），参数 `task`+`name`(≤12字短名)+`project`+`executor`+`mode`；回执给 `task #N` 与续聊地址 `delegate:<id>` | `core/tools/DelegateTool.scala:69-121`（schema）、`:435-487`（call/mode 校验） |
| B | 双模式（kernel / dispatcher） | 总闸 `delegate.dualMode.enabled` **今天为 true（灰度开着）**；模式是 spawn 时终身属性，`modeFaceOf` 是工具面/挂载面的单点判据（闸关 = 逐字节回退） | `core/delegate/DualMode.scala:69-81`；`DelegateRegistry.scala:465-471`；`~/.stand/nebflow.json` delegate.dualMode.enabled=true |
| C | 链注册表与 Flow 会话 | 一 delegate 一作用域图（`<基工作区>/.nebflow/flow-maps/<会话id>/`），NodeEngine 自根；台账三行族落 `chains/<key>.json`（spawn / terminal-verdict / merge-queue），链号台账独立（出生即定、改号留别名） | `core/project/DelegateFlowHosts.scala:17-30`；`core/project/FlowMapStore.scala:12-25`；`core/delegate/ChainStore.scala:33-107`；`core/project/ChainLedger.scala:9-30`；读面 REST `/api/chains` = `ChainsReadFace.scala:9-30` |
| D | worktree / 分支纪律 | spawn 前判定机判「会否改文件」，**判是/判否一律**建 `delegate/<id>` 分支＋轻量 worktree；合并后机械回收（零领先守卫，脏树拒绝即留现场）；brief 首屏带 reset 警示行 | `core/delegate/DelegateWorkspace.scala:12-38`；`PostMergeCleanup.scala:11-30`；`DelegateTool.scala:66-67`（ResetHazardLine）、`:152-170`（brief 装配单点） |
| E | 判定机 / 合并队列 | 判定机两层（Jev 优先 + SmallJudge 兜底，全败 fail-open）；判词两态+注记（completed / ready-to-merge，blocked 已退役折注记）；合并队列 = FIFO 交通引导员制（waiting/turn-granted/merged/owner-gone/failed），write-ahead 通道护重启丢队，care-tick 一拍三件事 | `core/delegate/DelegateJudge.scala:16-45`；`llm/decision/DelegateJudgeMachine.scala:203-210, 471, 504-510`；`core/delegate/MergeQueue.scala:68-94, 862-926`；`MergeQueueChannel.scala:8-40`；`MergeQueueCare.scala:8-25` |
| F | 邮箱化投递 | **P1+P2 已落 main**（89b87579d）：磁盘邮箱唯一真源、actor 消息降为门铃、mailOnce 入队幂等、五唤醒点 drain、boot 扫箱；**P3（全通知面迁移 + 旧机器退役）未派**——旧重投 sweep 仍在跑 | `core/mail/SessionMailbox.scala`（484 行）；`agent/MailboxDrain.scala`、`MailboxBootSweep.scala`、`MailboxProducer.scala`；设计稿 [[design/20261101_090600_delegate-terminal-mailbox-design__delegate-nebflow-4a58b3db.zh-CN]] §7 |
| G | 面板三面 | ①后台任务面板（`bgMergedPanel.js`，模型单点 bgPanelModel）②链会话可见性（b605518fe：Flow 会话溯源分组 + 「链运行中」延续态徽标，后端零改动）③会话详情面板（`bgAgentPopup.js` + `flowAgentPopup.js`）——**今天第③面有正文空白缺陷，归因中** | `web/js/bgMergedPanel.js`、`delegateChainProvider.js`、`chainDetailEntry.js`、`bgAgentPopup.js`、`flowAgentPopup.js`；[[20261101_162000_bg-flow-chain-visibility__delegate-nebflow-73346fa5]] |
| H | AgentControl 管理面 | 四操作 list/status/cancel/restart；**rootSessionId 同桶权限**是硬门：属主会话死掉后，别的 root 桶一律无权管——孤儿分发器的成因 | `core/tools/AgentControlTool.scala:430-441`（权限守卫） |
| I | 任务台账联动 | 派发即建任务行（`task #N`，project 字段随派发落库）；`delegate_report` 只写状态槽（finish / merge 两态），终态判定机消费它；续聊补注写台账 | `DelegateTool.scala:490-509`；`DelegateReportTool.scala:40-98`；`MailTool.scala:1377-1388` |
| J | 磁盘/现场回收（旁证） | worktree 座累计 **180 个目录 / 73G**（git 登记 175）——回收守卫只在「合并落地 + 零领先」时动手，断供批的座永远留着 | `.nebflow/worktrees/`；`PostMergeCleanup` 零领先守卫 |

## 2. 今日实案卷宗

### 2.1 四起断供 + 孤儿链所有权（已有归因，转引不重查）

9b828eae / a3194495 / 94fe78c9 / 1f99803a 四批 dispatcher 派发全部同一签名：写链 → 发开工宣言 → 回合结束 → 判词折叠成 completed → 终态清理把在飞链台账 cancelled → 结果四处全断。根因、证据链与修法分档见 [[20261101_165531_dispatcher-chain-lifecycle-rca__kernel]]（已在 main，commit 9e5ea35b9）。本审计只补两条**今天仍在发生**的事实：

- **孤儿分发器今天还活着**：`dispatcher-20b0e8ea`（属主 9b828eae，15:03 已终态）在 17:34 仍出现在 UI 缓存驱逐行（`stand.log:66793`），即 RCA §6 的孤儿状态已持续 2.5 小时未收；
- **滞留提交 10 个**：94fe78c9 分支 6 提交（含 e61c2d7d0「统一玻璃单源包」）、1f99803a 分支 3 提交（USTC CI 平移）、9b828eae 分支 1 提交（基线盘点），`git rev-list --count main..<branch>` 逐条实测。其中玻璃单源包后来由 kernel 批 ac7ffa98 重做并落地（dd78d5d05）——**同一件事今天做了两遍**，这是「结果必达缺失」最贵的代价。

### 2.2 会话详情面板正文被吞（**归因中**）

作者 17:21 实拍：点进 delegate 会话详情，正文不可见、只剩归因徽章行。今天这条批走了两棒：

| 时刻 | 事件 | 事实 |
|---|---|---|
| 17:22 | 18d8fe69 派出（归因批） | 落 `.probe/probe-live-panel.mjs` 探针，跑过 35.9s 实拍 |
| 17:42:36 | **该批死于上游 provider 格式错**，零提交 | `deepseej/deepseek-flash` 返回 400 ``The `content[].thinking` in the …``，两候选耗尽 → `llm-fail` → delegate failed（`stand.log:67484-67485`） |
| 17:43 | d86b41ad 重派（修复批） | 快照时刻仍在飞、零提交 |

结论当前状态：**归因未出，不得写死原因**。可复述的既有事实只有两条：① 面板三层数据源（`activeAgents` 快照 / WS 帧 / `restoreFromBackendHistory` 历史重放）都不区分主会话与子会话；② 渲染层对**所有视图**的历史重放都套 output 面（`web/js/persistence.js:742`）——这解释了为什么它「像」output 块吞文，但见 §2.3。

### 2.3 output 块误判（作者口径纠正 ①）

根会话曾把 §2.2 的面板空白归因到 output 块剥离。**作者裁定：output 块纪律仅主 agent；面板问题是老 bug。** 代码事实支持这条裁定：output 块纪律的**提示词注入面**只对根会话无条件开（`agent/PromptSections.scala:29-33, 377-409`，条件 `isRootAgent`），delegate / 子代理 / 节点只是 brief 里带一行同源文本（`DelegateTool.scala:168`、`BlockingSubagent.scala:229`、`NodeEngineContract.scala:673`）——**纪律面 ≠ 渲染面**；把渲染层的空白归到纪律面，是跨面错配。

### 2.4 搜索范围误判（作者口径纠正 ②）

根会话曾据「搜索结果里没有后台代理的消息」判断搜索被限制在主会话。**事实相反：搜索本就覆盖全实例**——`chatSearch.js` 的 scope 是两档（`current` 当前会话 / `all` 全实例，`:213, :1131-1143`），后端 `/api/search/chat` 只在显式传 `sid` 时才收窄（`gateway/ChatSearchRoutes.scala:11-30`）。作者据此的裁定是**反向的**：主 agent 的聊天搜索**不应**包含后台代理产生的消息（收窄令），在飞批 7966cb44。

### 2.5 诚实记录：我方（根会话）两次带偏口径

上面两条都是根会话先给出了错口径、被作者当场纠正的：① 把面板空白归到 output 块（错面）；② 把「搜索含全实例」读成「搜索只搜主会话」（错读）。两次的共性是**拿机制条文的表面形状推断具体缺陷**，没有先读渲染/查询的实际作用面。本审计据此把「读代码作用面，不读条文名」列为后续审计的硬纪律，并在此留痕，供后续文档复核。

### 2.6 今天新发现的两条实证（本批首次落账）

**① 续聊腿不继承模式（老 bug，今天两次现场）**

`Mail(address="delegate:<id>")` 对已终态实例会续起一个新会话，但续起腿**不传 `requestedMode`**（`MailTool.scala:1347-1373` 调 `spawnBackground` 时只给 agentDef/task/workspace/parent…），于是 `decideMode` 走 auto 分支让选择器重判（`DelegateTool.scala:834-851`）。今天两次实测都是「dispatcher 续成 kernel」：

| 原会话（dispatcher） | 续聊会话（kernel） | 台账证据 |
|---|---|---|
| 94fe78c9 15:04 dispatcher | 8bbf8cf7 16:02 **kernel** | `~/.stand/chains/1287.json` 两条 mode-decision 行 |
| 1f99803a 15:25 dispatcher | 5dc44e31 16:39 **kernel** | `~/.stand/chains/1291.json` 同形 |

后果：续聊会话拿到 kernel 工具面（编排工具被摘），但它要接着干的仍是编排活——属于「座位与活不匹配」，且第二个会话不会接管原作用域图。修法很小：续起腿把 `record.mode` 作为 `requestedMode` 传入（`leg=manual` 语义），缺省时再回退 auto。

**② 两批死于上游 provider 格式错（非 delegate 机制）**

17:42:37 / 17:43:07 两条 `verdict=failed, source=engine`（18d8fe69、288e7641）不是 delegate 判定机判的——是 `deepseek-flash` 对 thinking 字段返回 400 格式错、候选链两轮耗尽后 fail-fast（`stand.log:67481-67484`）。这条与 delegate 无关，但它解释了「今天为什么有两批零产出阵亡」，也提示 provider 侧格式契约是当前最高频的单点风险（同因在 17:43 后又导致 288e7641 阵亡 → 17:43 重派为 de5866a9）。

## 3. 已落 vs 未落对账

### 3.1 今天已合并进 main 的 delegate 相关批次（可复核）

| 批次 | commit | 合并 commit |
|---|---|---|
| 终态通知 actor 邮箱化 P1+P2（核心机制） | 6efd3f08c | 89b87579d |
| 终态通知邮箱化设计稿（只设计） | 46ea13528 | d52d402ad |
| delegate 静默死亡缺陷分析（审计） | dbe5ce593 | 771649a40 |
| 分发器座位提示词按 9.19 纯编排口径重写 | 47e1e9ccf | 8c0173dc8 |
| 完工卡恢复 diff 统计 + 版式重排 | c4f8a0d91 | c73713c72 |
| 后台面板 Flow 链会话可见性（b605518fe） | d9be29a14 | b605518fe |
| 分发器链式执行连环断供 RCA | afc25141e | 9e5ea35b9 |
| output 块纪律渠道接线（微信/飞书出站剥离） | 18ab16172 | 5a2738de8 |

### 3.2 开放项（未落）

| # | 开放项 | 现状 | 归属/建议 |
|---|---|---|---|
| O1 | **邮箱化 P3**（全通知面迁移 + 旧机器退役 + `injection-queues.json` 销案） | 未派；旧重投 sweep 仍在跑，日志每 30s 一条 `retained=7` 的老回执 WARN（`stand.log:67444` 一带） | 排期批 |
| O2 | **RCA 小修 S1-S5** | 未实施（S1/S2/S3 判词诚实化、S4 孤儿例外、S5 失败通知属主校验） | 一个 kernel 批可整包落 |
| O3 | **RCA 结构修 D1-D4** | 未实施（D1 链生命周期与会话解耦、D2 结果必达回执、D3 chain-end 强制、D4 所有权项目化+孤儿收割） | D2+D4 优先，D1 需设计批 |
| O4 | **RCA 提示词修 P1/P2** | 未实施（完工=链收束的申报纪律、写链纪律与 D3 同口径） | 随 D3 同批，避免口径先行 |
| O5 | **续聊腿 mode 继承**（本批新实证 §2.6①） | 未修 | 建议并入 O2 同批 |
| O6 | **面板正文空白 bug** | 在飞（d86b41ad 17:43 重派） | 归因出后另批修 |
| O7 | **张安齐直推 USTC dev/main 的 10 条**（类型债/NH_HOME/退出登录/视觉基线/R1 门面化） | eed3ad45 17:42 在飞；撞车交集 6 文件（3c9aad64e 已盘点） | 合并批在飞，纪律面待作者裁 |
| O8 | **kernel 模式灰度观察结论** | 今天 kernel 55 派 0 断供；dispatcher 7 派 6 折（只 b42b77bf 走到合并） | 见 §5：维持 kernel 缺省，dispatcher 显式点名 |
| O9 | worktree 现场累积 | 180 座 / 73G；回收只在合并落地时发生 | 需一次性清场批（只删零领先且已合并的座） |

## 4. 运行时快照（2026-11-01 17:46）

| 指标 | 读数 | 来源 |
|---|---|---|
| 今日派发总数 | 64（kernel 55 / dispatcher 7，另 2 条为续聊腿同模式行） | `~/.stand/chains/*.json` mode-decision 行 |
| 模式判定腿 | jev 56 / manual 6 | 同上 |
| 今日落地合并 | 31（全部 `merged`） | `~/.stand/merge-ledger.json` |
| 合并队列即时状态 | **0 waiting / 0 turn-granted**；全账 236 成员（210 merged / 25 failed / 1 owner-gone） | 同上 |
| 今日判词行 | completed 62 / ready-to-merge 31 / cancelled(engine) 9 / failed(engine) 2 | chains 台账 |
| 在飞会话（6） | 5dc44e31(16:39 续聊)、dd4c06dd(17:38 本批)、eed3ad45(17:42 张安齐合并)、d86b41ad(17:43 面板修复)、de5866a9(17:43 徽标重派)、5be497f9(17:44 cmdcode 排查) | `~/.stand/delegates/*.json`（reportStatus=None ∧ 未归档） |
| 归档存量 | delegate 记录 982 条；链台账 836 个文件 | 盘面计数 |
| 孤儿分发器 | `dispatcher-20b0e8ea` 仍活（17:34 仍在 UI 缓存驱逐行） | `stand.log:66793` |
| worktree 座 | 180 目录（git 登记 175）/ 73G | `git worktree list`、`du -sh` |
| 引擎 | pid 30450，v2026.10.26-beta.2，health=ok；`kimi/kimi-for-coding` down（429 周配额耗尽），`deepseek-flash` 今天返回过 thinking 字段格式错 | `.pid.meta`、`/api/health`、`stand.log:67481` |

## 5. 健康评估与建议（优先级表）

**总评：执行面 A，编排面 D，观测面 B。** 今天的 31 次合并、0 悬空队列说明「派发→干活→判词→合并」这条主链在 kernel 形态下是通的；不通的是「编排者会话」这一层的生命周期与结果必达。

| 优先级 | 事项 | 为什么现在 | 建议动作 | 规模 |
|---|---|---|---|---|
| **P0** | 续聊腿 mode 继承（O5） | 今天两次把编排者续成执行者；修法是一行接线（`MailTool` 续起腿传 `record.mode`） | 与 O2 同批，spec 钉「续聊 mode = 原 record.mode」 | 小 |
| **P0** | 判词诚实化 S1/S2/S3（O2 前半） | 报表今天 6 次把「批次未完」写成 completed——它污染 session-sync、任务卡、归档旗三处 | 判词指令补 inFlightChains 规则 + 注记带链读数 + dispatcher 非合并轮禁静默降级 | 小 |
| **P0** | 滞留提交收割（§2.1） | 10 个真提交无主（含与 ac7ffa98 重复劳动的玻璃包）；再放一天就彻底被遗忘 | 作者裁：94fe78c9 的玻璃提交与已落 dd78d5d05 比对后择优/丢弃；1f99803a 的 CI 三条单独核算 | 人工裁定 |
| **P1** | S4 孤儿例外 + S5 归属校验（O2 后半） | 孤儿 `dispatcher-20b0e8ea` 已活 2.5h，且它接过别的批的失败通知（跨根注入） | 属主死 ⇒ 同项目 root 桶可 cancel（不含 restart）+ 审计行；失败通知只喂属主活着的分发器 | 小 |
| **P1** | D2 结果必达（O3） | 唯一能消灭「开工宣言 = 事故」的一档；今天 4 条链的收尾全靠人肉发现 | 链任一终局 → 引擎投结构化回执给 requester；requester 死 → 升级 root/任务板 | 中 |
| **P1** | 邮箱化 P3（O1） | 旧重投机器仍在跑（`retained=7` 常驻 WARN），P1/P2 的红利被旧腿稀释 | 排期；先把旧回执排空跑完再拆 | 中 |
| **P2** | 面板正文空白（O6） | 在飞，归因未出；不要抢批 | 等 d86b41ad 归因，再单独修 | 在飞 |
| **P2** | D4 所有权项目化 + 孤儿收割（O3） | 与 S4 同族，S4 是止血、D4 是根治 | 随 D1 设计批一起 | 中 |
| **P2** | worktree 清场（O9） | 73G 是硬账；且断供批的座永远不清 | 一次性批：零领先 ∧ 已在 main 的座删除，其余列清单给作者 | 小 |
| **观察** | kernel/dispatcher 灰度（O8） | 今天 kernel 55 派 0 断供；dispatcher 7 派 6 折 | 维持 **kernel 缺省**，dispatcher 只在显式点名时启用；等 S1-S5+D2 落地再谈扩面 | — |
| **观察** | 上游 provider 格式契约（§2.6②） | 今天两批阵亡的直接原因，非 delegate 面 | 归 LLM 路由侧；delegate 侧只保证失败可见（已可见） | — |
| **人事/纪律** | 张安齐直推 USTC dev/main（O7） | 分支纪律的第一例外部断面；本地 main 今天 31 次合并全部是 `--no-ff` 形态 | 合并批在飞；**建议此后外部直推一律先落分支再合并**，由作者定纪律 | 裁定 |

## 6. 复核锚点汇总

- 工具面与 brief：`core/tools/DelegateTool.scala:69-121, 152-170, 203-213, 435-487, 791-851, 1156, 1227`
- 双模式：`core/delegate/DualMode.scala:69-81`；`core/delegate/DelegateRegistry.scala:465-471`；`~/.stand/nebflow.json` delegate.dualMode
- 链与作用域：`core/project/DelegateFlowHosts.scala:17-30`；`core/project/FlowMapStore.scala:12-25`；`core/delegate/ChainStore.scala:33-107`；`core/project/ChainLedger.scala:9-30`；`core/project/ChainsReadFace.scala:9-30`
- 终态与判定：`core/delegate/DelegateTerminal.scala:97-188, 629-720`；`core/delegate/DelegateFlowMapLifecycle.scala:120, 134`；`core/delegate/DelegateJudge.scala:16-45`；`llm/decision/DelegateJudgeMachine.scala:203-210, 463, 471, 504-510`
- 合并队列：`core/delegate/MergeQueue.scala:68-94, 862-926`；`MergeQueueChannel.scala:8-40`；`MergeQueueCare.scala:8-25`；`PostMergeCleanup.scala:11-30`
- 投递与续聊：`core/tools/MailTool.scala:1347-1373`；`core/mail/SessionMailbox.scala`；`agent/MailboxDrain.scala`、`MailboxBootSweep.scala`、`MailboxProducer.scala`
- 权限与管理：`core/tools/AgentControlTool.scala:430-441`；`core/tools/DelegateReportTool.scala:40-98`
- 前端三面：`web/js/bgMergedPanel.js`、`delegateChainProvider.js`、`chainDetailEntry.js`、`bgAgentPopup.js`、`flowAgentPopup.js`、`persistence.js:742`、`chatSearch.js:213, 1131-1143`、`gateway/ChatSearchRoutes.scala:11-30`
- 台账与日志：`~/.stand/chains/{1284,1287,1289,1291,1303-1311}.json`；`~/.stand/merge-ledger.json`；`~/.stand/delegates/*.json`；`~/.stand/logs/stand.log:66793, 67444-67485`

## 7. 未决项

1. **面板空白的真因**未出——本报告只记录现象与两棒经过（§2.2），不做归因；归因须由在飞批给出，并附「部分会话」这一条件性的解释（为什么是部分而不是全部）。
2. **RCA §6 的两条旁证异常**（9b828eae 为何写两条 11 节点链、作用域目录 grace 删除未生效）本批未展开，仍待单独核查。
3. **94fe78c9 与 ac7ffa98 的玻璃包重复度**未逐文件比对（只按提交标题与批文档判为同族），收割前需要一次真实 diff。
4. **灰度样本量**：dispatcher 今天只有 7 个样本，不足以给出「模式本身不可用」的结论；本报告的判断是「在现行生命周期机制下不可靠」，不是「思路错误」。
5. 本报告为**只读审计**，未改任何产品代码；§5 的每一档修法都需要另派批次。
