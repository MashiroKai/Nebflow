> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Nebflow 新架构试点——Project + 任务分发器 + Node + Flow Map 完整架构方案（v5 修订）

> 阶段文档（方案设计，完成即冻结；试点推进时以本方案为基准修订）。
> **v2 修订（作者 08-31 23:34 修改意见，已整合入正文，修订点见下）**：
> 1. **工具集精简**：NodeCreate / NodeConnect / NodeDisconnect 合并为单一 **NodeEdit**（in / out / nodename 三参数即可完成创建、改接、断开）；**NodeStatus 并入 NodeList**（状态信息随 NodeList 返回）；**NodeResult 删除**（与「结果保存 + 自动投递」机制重叠）；**WorktreeCreate / WorktreeList 删除**（worktree 用 git + bash 管理，不需要新工具）。工具集收敛为：NodeEdit / NodeList / NodeCancel + BaseTools(读) + Mail（v3：Mail 从分发器工具集移除——无需回报，见 v3 修订）。
> 2. **Node 结果持久保存**：节点完成 → 结果持久保存；**悬空 node（无 out）完成后结果保留，之后被接线（out 指向新节点）→ 结果自动投递给下游**（不必重新跑）。5min TTL 只管**显示**——节点从活动图消失 ≠ 结果丢弃，接线仍可触发投递（从归档读结果）。
> 3. **NodeList 字段补充**：每项含 id / name / agent / status / in / out / **hasWorktree** / result 摘要等；任务分发器据此动态创建合并 node（如：单线工作直接报 Nebula，旧工作未完成新工作来时开并行 worktree node，分发器把两条线接给合并 node，合并后再给 Nebula）。
> 4. **前端设计（新增 §3.5）**：侧边栏 Team/Flow 面板退役，改为 **Project 面板**；Project 列表一行一个（name / 工作区 +「在文件浏览器中打开」/ Agent.md / 描述 / 当前后台运行 agent 数 / 简要状态）；点击 project → **页面内导航**进入 Flow Map 视图（复用 flow-run 面板显示设计，非弹窗非新标签页，左上角返回；**v5 修订：改为标签页形态，见 v5 修订**）；已完成节点保持 5 分钟后不显示；**node 会话都不持久、无记忆**（ephemeral，与记忆 Nebula-only 裁定一致，节点间靠结果传递而非记忆）。
> 5. **联动更新**：§0 约束表、§1.3 取代映射表、工具 schema、§4 试点场景验收点、§5 风险（R5 重写）、§6 结论随工具精简同步调整。
>
> **v3 修订（作者 08-31 23:5x 修改意见，已整合入正文，修订点见下）**：
> 1. **去 Mail 回报**：任务分发器不需要回报，Node 也不需要 Mail——Node 结果沿 out 边自动投递到下游节点输入（与 Delegate 结果回报机制同构：完成即结果自动注入/投递，非 Mail 通信）。分发器建完节点后无需 Mail 回报 Nebula——拓扑与状态都在 Flow Map / NodeList（flow-map.json），Nebula 用 NodeList 只读查看即可。Mail 仅保留 Nebula → Project 触发语义；分发器工具集移除 Mail（§2.2）。
> 2. **Node 不设超时**：Node 不设任何超时（与 Delegate/flow 设计一致，前台近似无限）；卡死检测靠既有 TaskStuckWatcher 10min 零活动检测兜底。TTL 仅用于完成节点 5 分钟从 Flow Map 消失的显示语义（barrier 等待同样不设超时），与运行超时无关。
>
> **v4 修订（作者 09-01 00:00 修改意见，已整合入正文，修订点见下）**：
> 1. **去 FlowReport / verdict**：新架构 Node 只有 1 个 out（无分支）、无校验循环（无 switch/redo 回路）——verdict（pass/fail）没有任何消费方 → **FlowReport 工具整体不保留**。
> 2. **Node 结果 = 最终输出**：节点 agent 完成时其**最终输出文本即节点结果**，由 NodeRunner 自动读取 → 写入节点 result 字段（持久保存）→ 沿 out 边投递。无需任何「上报工具」，无 verdict 字段（与 Delegate 语义一致：子 agent 最后的汇报文本即结果）。
> 3. **failed 保留**：节点 failed（执行失败/异常/取消）是**执行态**，不是校验判定，与 verdict 无关。
> 4. **时间性迭代表述**：「评审节点输出问题 → Nebula 判断是否再触发 fix 轮」仍成立；「fail」不是节点报告的 verdict，而是评审节点**输出文本里的结论**（如「发现 3 处问题：…」），由 Nebula 读结果后判断是否触发新一轮。
>
> **v5 修订（作者 09-01 00:22 前端设计补充意见，已整合入正文，修订点见下）**：
> 1. **Flow Map 展示在标签页**：点击项目 → 在**标签页**打开 Flow Map（与 flow-run 标签页同形态，非弹窗；关闭标签页不影响项目数据/Flow Map 状态）。§3.5 原「页面内导航（非弹窗、非新标签页）、左上角返回按钮」表述作废——标签页形态下无需页面内返回按钮（标签页自身可关闭/切换即退出；返回 Project 列表 = 关闭/切换标签页，与 flow-run 标签页形态一致）。
> 2. **侧边栏 Project 按钮取代 Team/Flow 按钮**：项目入口占据当前 Team/Flow 按钮的位置（取代语义，非并列）。试点期旧体系仍在运行——旧 Team/Flow 面板**收进二级入口/折叠**（阶段 2），阶段 3 移除；原「试点期可与旧面板并列」表述作废。
> 3. **Agent.md 可点击查看、可改**：交互与当前 team 面板 rules.md 入口一致——点击查看 + 编辑，保存写回工作区 `.nebflow/Agent.md`。
> 4. **整体参考现有 team 面板设计**：Project 列表/卡片/进入交互沿用 team 面板既有视觉与交互范式（§3.5 Project 列表字段表保留，交互范式注明「参考现有 team 面板设计」）。
>
> 作者裁定（2026-08-31）：新架构 **完全取代** 现有 Team/Flow（含 Delegate/SubTask），不是共存。
> 本方案为纯设计：未修改任何代码/定义文件。
> 依据：作者新架构构想（uploads/3335124794821291）、作者 08-31 23:5x 两点修改意见（去 Mail 回报 / Node 不设超时）、作者 09-01 00:00 修改意见（无分支/校验循环 → 去 FlowReport/verdict，Node 结果=最终输出自动保存投递）、作者 09-01 00:22 前端设计补充意见（Flow Map 标签页展示 / Project 按钮取代 Team-Flow 按钮 / Agent.md 可点击查看可改 / 参考 team 面板设计）、flow-execute skill、`20260831_flow-syntax-and-delegate-unification.md`（NodeRunner 共享内核结论）、主仓现状实现（源码级核对）。

---

## 0. 设计基线——作者构想 → 方案的十条硬约束

| # | 作者裁定 | 本方案的落实 |
|---|---|---|
| 1 | 以 Project 为单位，项目指定工作区 | Project 实体 = 定义（`projects/<name>/project.json`）+ 工作区 + 工作区 `.nebflow/` 运行时 |
| 2 | rule.md 改为工作区 Agent.md | `Agent.md` 承载项目级 agent 指令（取代 team rules.md） |
| 3 | Nebula 工作模式改为 Mail 给 Project | Mail(→project 名) → ProjectActor → 触发任务分发器 |
| 4 | 每个 Project 有任务分发器 + Flow Map | 分发器 = 单次会话 agent（每次由 Nebula 触发）；Flow Map = 每项目唯一（磁盘 + 内存 + 前端面板） |
| 5 | Node 参数：IN/Agent/Skill/MCP/Worktree/OUT/Name | Node 数据模型见 §2.1；工具集见 §2.2 |
| 6 | Node 创建即运行、不阻塞 | 入口节点（有 task）创建即启动；异步后台运行（复用 AgentActor） |
| 7 | OUT 可悬空/可改接；用户补充走改接 | 边为第一类实体 + 原子改接 + 竞态一致性规则（§2.3） |
| 8 | 并行多 to1（不支持 1 对多）；Worktree 并行 + 合并节点 | barrier 语义（复用现有 join）；1 对多工具层拒绝（§2.4/§2.5） |
| 9 | Node 创建全部走工具（不写文件，热加载 + 语法正确） | NodeEdit / NodeList / NodeCancel 全部工具化，0 文件写入（§2.2） |
| 10 | loop detect + 有向无环图 | 创建/改接时 DAG 环检测 + 节点级重复检测（§2.6） |

---

## 1. 新架构全貌

### 1.1 实体模型

#### Project（取代 Team）

| 项 | 设计 |
|---|---|
| 定义 | `~/.nebflow/projects/<projectName>/project.json`：`name`、`description`、`workspace`（绝对路径）、`agentFile`（工作区 `.nebflow/Agent.md` 路径）、`createdAt` |
| 工作区 | 项目实际代码/素材目录（如 `~/phd-notebook/`）；ProjectCreate 时指定 |
| 工作区 `.nebflow/` | `Agent.md`（取代 rule.md）、`flow-map.json`（Flow Map 存储）、`worktrees/`（并行工作区）、`.gitignore`（预置 `.nebflow/`，防项目 repo 污染） |
| 常驻组件 | `ProjectActor`：每项目一个（轻量 actor）——收 Mail、持有 Flow Map 存储、触发分发器会话、管理节点事件/TTL 定时器（TTL 仅终态节点 5min 显示消失语义；**Node 运行本身不设超时**） |
| 生命周期 | ProjectCreate（Nebula 用）→ 挂载 → 使用 →（迁移完成）退役 |

> 与旧 Team 的差异：Team 把 agent 定义（agents/ 目录）装在团队内；Project 不装 agent——节点从**全局通用 agent 库**选（作者裁定「Agent是从通用的Agent中选择」），项目只持有工作区/Agent.md/Flow Map。

#### 任务分发器（Dispatcher，取代 Manager）

| 项 | 设计 |
|---|---|
| 定位 | 单次会话 agent：每次由 Nebula 触发，跑一个会话（读 Flow Map → 建 Node → 接线 → 结束；**无需回报**——拓扑与状态已落 Flow Map，Nebula 用 NodeList 只读查看），**无持久上下文** |
| 触发 | `Mail(→<project>)` → ProjectActor spawn 分发器会话（fresh session），注入：项目上下文 + 任务文本 + **Flow Map 当前快照**（NodeList 输出） |
| 上下文保持 | Flow Map 是**唯一状态载体**——分发器每次开场先 NodeList 看现状再决策；它上次建的节点都在图上 |
| 工具集 | **NodeEdit**（创建/接线/改接/断开统一） / **NodeList**（含 status/result 摘要/hasWorktree） / **NodeCancel** + BaseTools(读，含 Bash 执行 git/worktree 管理)。**不含 Mail**（v3：分发器无需回报——拓扑与状态在 Flow Map / NodeList，Nebula 只读查看；Node 结果沿 out 边自动投递，与 Delegate 结果回报同构）。**不含** TeamTask/Delegate/SubTask/FlowExecute（新模型下已退役）；不含 NodeStatus/NodeResult/WorktreeCreate/WorktreeList（v2 精简，见 §2.2） |
| 无全局编排视角？ | 否——Flow Map 就是全局视角：分发器 NodeList 拿到的即全项目当前拓扑（§5 风险 R3 对策） |

#### Node（取代 Team 成员 / Delegate / SubTask / flow 节点）

数据模型（§2.1 详）：`id / name / agent / skill? / mcp? / worktree? / preset? / task? / in[]（入边）/ out?（出边，≤1）/ status / result / retries / ttlExpireAt`。

本质：**Node 就是一个 agent，以 flow 形式组织起来**（作者原文）。执行上 = 现有 flow 节点 / Delegate 目标的同一执行内核（AgentActor），干净上下文、leaf、结果回传（**无 Mail 身份**——结果沿 out 边自动投递，与 Delegate 结果回报机制同构）；组织上由分发器逐个创建、边接线、Flow Map 显示。**Node 会话不持久、无记忆**（ephemeral——v2 明确：与记忆系统 Nebula-only 裁定一致，节点间靠结果传递而非记忆，见 §3.5）。

#### Flow Map（取代 Flow DAG / TeamTask 看板）

| 项 | 设计 |
|---|---|
| 存储 | 磁盘 `<workspace>/.nebflow/flow-map.json`（节点 + 边 + worktrees + 项目 meta + **archive 结果区**，write-through 持久化，重启恢复）+ 内存 Ref（ProjectActor 持有）+ WS 事件推送前端 |
| 显示 | 前端每项目一个 Flow Map 视图：节点卡片（状态色）+ 边连线 + 运行指示 + TTL 倒计时（§3.5；TTL 仅终态节点显示语义，非运行超时） |
| 语义 | 既是**显示**，也是**动态 Node 载体**——分发器的决策依据（NodeList = 图快照） |
| TTL | completed/failed/cancelled 节点**显示 5 分钟后从活动图移除**，节点记录（含结果全文）移入 `flow-map-archive.json`（项目历史）——**显示消失 ≠ 结果丢弃**，归档结果仍可被接线投递（§2.1/§2.6） |
| 唯一性 | 一项目一个 Flow Map |

### 1.2 全貌架构图

![新架构全貌](assets/nb-arch-overview.svg)

读图要点：
- **左侧**：用户 → Nebula（唯一有记忆/验收/编排入口）；Nebula 不再直接派 agent，只 Mail 给 Project。
- **中间**：Project 域——ProjectActor 常驻收 Mail 并触发分发器；分发器单次会话用 **NodeEdit** 建节点、接线（图中分发器 → Node 的绿色边即 NodeEdit）；节点挂在 Flow Map 上（状态/结果写入 flow-map.json）。
- **右侧**：节点拓扑——入口节点创建即运行；OUT 边把结果传给下游；合并节点（barrier）多 to1 全到才启动；最终结果回 Nebula。
- **Worktree**：可选参数，指向 `工作区/.nebflow/worktrees/<name>`，节点在其内独立工作；**worktree 创建/管理走 git + bash**（v2：WorktreeCreate/WorktreeList 工具删除）。

### 1.3 与旧体系映射表（每项：替代关系 + 退役方式）

| 旧体系 | 新体系 | 替代关系 | 退役方式 |
|---|---|---|---|
| **Team**（`teams/<name>/team.json` + `rules.md` + `agents/`） | **Project**（`projects/<name>/project.json` + 工作区 `.nebflow/Agent.md`） | Team 的「成员集合 + 规则」→ Project 的「工作区 + Agent.md + Flow Map」；成员不再预定义，按需成为 Node | 阶段 2 逐项目迁移；阶段 3 删除 `teams/` |
| **Manager**（常驻 lead agent） | **任务分发器**（单次会话 agent，Mail 触发） | 分解任务、路由工作的职责从常驻会话 → 每次触发的新会话；状态从会话记忆 → Flow Map；「汇报」职责消失——分发器无需 Mail 回报（拓扑与状态落 Flow Map，Nebula 用 NodeList 只读查看） | Manager agent 定义退役 |
| **Team 成员**（常驻会话 + Mail 身份 + TeamTask） | **Node**（leaf agent，边组织，无 Mail 身份） | 干活主体不变（同一 AgentActor 内核）；身份从「常驻成员」→「按任务创建的临时节点」；协作从 Mail 对话 → 边投递结果 | 成员 agent 定义退役（如需保留能力 → 沉淀为全局 agent 或 skill） |
| **Flow 预定义**（`flows/<name>/flow.json` + FlowTrigger，9 个） | **节点组合配方**（文档化 pattern，分发器按配方建节点） | 固定流水线形状 → 分发器逐个建节点的参考配方（如 code-review = 扫描→评审→打回轮次 = 每轮新节点子图，轮次由重触发实现，DAG 保持无环） | `flows/` 归档为配方库；FlowTrigger 退役 |
| **FlowExecute**（内联 DAG 一次性编排） | **分发器逐个 NodeEdit** | DAG 一次性写完 → 逐节点动态创建（创建即运行）；fanout 变为「多个独立入口节点」；join 变为 barrier 合并节点 | FlowExecute 退役 |
| **Delegate**（Nebula 派 standalone worker） | **NodeEdit**（单节点：`nodename` + `task` + `out: "Nebula"`） | 「Delegate = 单节点 flow」（统一分析结论）在新模型直接落地为「单节点 = 最简单的 Node」 | DelegateTool 退役 |
| **SubTask**（team 成员 self-clone） | **NodeEdit**（分发器建节点，目标 = 通用 agent，非 self-clone） | self-clone 语义消失——节点按名解析全局 agent | SubTaskTool 退役 |
| **Mail team 路由**（team 名 → Manager；short name；team/agent） | **Mail project 路由**（project 名 → ProjectActor → 分发器） | 路由目标从「团队常驻会话」→「项目分发器（每次触发新会话）」；节点无 Mail 身份（结果走边）；**Mail 仅做触发、无回报**（结果沿 out 边自动投递，分发器不 Mail 回报） | 路由改造；旧 team 名解析在阶段 3 移除 |
| **TeamTask**（Create/List/Update，team 内任务看板） | **Flow Map 生命周期**（NodeList = 实时看板；节点状态 = 任务状态） | 任务跟踪被节点生命周期天然覆盖（更实时、带拓扑） | TeamTask 工具退役 |
| **FlowTreeActor**（team 挂载/会话生命周期） | **ProjectActor**（项目挂载/分发器会话/Flow Map 状态） | 职责同名迁移 | 重构 |
| **FlowDagRunner / FlowDagExecutor**（DAG 步进执行） | **NodeRunner**（共享内核：spawn AgentActor + 结果捕获 + 监督） | 复用统一分析方案 A：DAG 步进 → 单节点执行；barrier/checkpoint/监督逻辑迁移 | 重构 |
| **FlowReport / FlowReportStore**（verdict/output/slots） | **节点 result 字段**（NodeRunner 自动读取最终输出） | 结果不再由节点上报——节点 agent 完成时其**最终输出文本即结果**，NodeRunner 自动读取写入 result（持久保存）并沿 out 边投递；verdict（pass/fail）无消费方（无分支、无校验循环） | **退役（v4）** |
| **NodeStatus enum**（Pending/Running/Completed/Failed/Cancelled） | 复用 | 生命周期状态机直接复用（+Wiring/Removed 两个运行态扩展，见 §2.1）；**NodeStatus 工具删除**，状态随 NodeList 返回（v2） | enum 保留复用；工具退役 |
| **MaxDepth = 5**（四入口统一深度上限） | **节点深度 = 1** | 节点是 leaf（无 Node 工具），天然无递归；分发器是唯一 Node 工具使用者且不嵌套 | 简化（深度概念保留为「分发器不可再触发分发器」） |
| **checkpoint 恢复 / BackoffSupervisor / onError 监督** | NodeRunner 内监督（restart + maxRetries + checkpoint） | 复用 flow 节点监督（checkpoint 优于 Delegate 从头重跑） | **保留复用** |
| **F0-F3 注入屏蔽 / 压缩机制 / 记忆 Nebula-only** | 不变 | 与 Node 模型正交，直接继承 | **保留** |
| **侧边栏 Team/Flow 面板** | **侧边栏 Project 面板 + Flow Map 视图** | Team/Flow 面板退役（§3.5）；Project 列表一行一个 + 点击进入 Flow Map | 阶段 2 换皮，阶段 3 移除旧面板 |

---

## 2. 核心机制设计

### 2.1 Node 数据模型与生命周期状态机

```json
{
  "id": "n-3f9a2b",                 // 稳定 id（uuid8），边引用用
  "name": "调研-康普顿成像原理",       // 显示名（Flow Map 卡片标题）；同 Flow Map 内唯一（NodeEdit 句柄）
  "agent": "Explorer",              // 必填：全局通用 agent 名（动态解析，同动态 flow 规则）
  "skill": null,                    // 可选：注入 skill 指令（如 "academic-pdf-fallback-chain"）
  "mcp": null,                      // 可选：追加 MCP 工具
  "worktree": null,                 // 可选："worktrees/<name>" 相对路径（相对工作区 .nebflow/；存在性校验，创建走 git）
  "preset": null,                   // 可选：模型分级（LowCost/Vision/...，复用 preset 机制）
  "task": "<本节点任务上下文文本>",    // 入口语义：有 task 且无 in → 创建即运行
  "in": ["n-1a2b"],                 // 入边（可多条 = barrier；由 NodeEdit in 参数累积）
  "out": "n-5c6d" | "Nebula" | null,// 出边（≤1，1 对多被工具层拒绝；null = 悬空；由 NodeEdit out 参数设/替换/清除）
  "deliveredTo": ["n-1a2b"],        // 已投递出边目标（dedup，防改接重投）
  "status": "running",              // pending/running/completed/failed/cancelled + wiring（NodeStatus 工具删除，随 NodeList 返回）
  "result": null,                   // "<节点最终输出全文>"——完成即由 NodeRunner 自动读取写入并持久保存（活动区），归档时整体保留（v2/v4：结果=最终输出，无 verdict/slots）
  "createdAt": 1725123456789, "startedAt": null, "completedAt": null,
  "retries": 0, "maxRetries": 1,
  "ttlExpireAt": null               // 终态 +5min（只管显示；到期移入 archive，结果全文保留）
}
```

![Node 生命周期](assets/nb-node-lifecycle.svg)

**启动语义（创建即运行）**——三态判定：

| 创建参数 | 语义 |
|---|---|
| 有 `task` 且无 `in` | **入口节点**：创建即运行（异步不阻塞），task 即 IN=Task |
| 有 `in`（上游节点） | 等上游结果投递；多 in = barrier（全到才启动） |
| 无 `task` 且无 `in` | **Wiring 态**：只挂载 Flow Map 不运行，等 NodeEdit 接入上游后才进入等待/启动 |

**结果持久保存（v2 修订——作者：悬空 node 完成后，接上下一个节点时结果自动给下游）**：

- 节点完成 → result 写入 flow map store 的节点记录（**活动区**，flow-map.json）+ 按 `out` 投递（§2.7）。
- **悬空完成（out=null）**：结果保存在节点 result（持久化，不丢）。之后任意时刻被接线（NodeEdit 把 out 指向新节点）→ **自动投递给下游**——投递裁决从上游 result 读（活动区或归档区），**不必重新跑**。
- **显示消失 ≠ 结果丢弃**：5min TTL 只管**活动图显示**（前端不显示、NodeList 活动区不出现）；到期节点记录移入 `flow-map-archive.json`（结果全文保留，可长期接线投递取用）。
- 保存时长：结果**长期保留**（归档区 = 项目历史）；跨触发阶段、跨重启均可接线投递取用。跨阶段需要上游结果时，分发器从 NodeList（活动区）或接线投递（归档区）获取，无需显式读取工具（NodeResult 已删除，v2）。

### 2.2 Node 工具集（任务分发器用）——全部走工具，0 文件写入

> 工具化理由（作者裁定）：① 热加载——工具描述动态枚举可用 agent/skill/mcp/worktree，免重启；② 语法正确——环检测/引用校验/状态守卫在工具层做，杜绝手写文件产生坏拓扑。
> **v2 精简原则（作者 08-31 23:34）**：节点本质就是 in / out 两个口——NodeList 看清现状后，NodeEdit 传 in / out / nodename 三个参数即可完成**创建、改接、断开**全部操作；NodeStatus 并入 NodeList；NodeResult 与「结果保存 + 自动投递」重叠故删除；worktree 用 git + bash 管理，不需要专门工具。**最终工具集 = NodeEdit / NodeList / NodeCancel + BaseTools(读)。**（v3：Mail 从分发器工具集移除——分发器无需回报，Node 结果沿 out 边自动投递，见 v3 修订）

```
NodeEdit(project?, nodename, agent?, task?, in?, out?, skill?, mcp?, worktree?, preset?, maxRetries?)
  // nodename 不存在 → 新建节点（新建必填 agent；task 与 in 至少给一个，否则 Wiring 态）
  // nodename 已存在 → 编辑该节点：
  //   in  = 追加一条入边（多 in 累积 = barrier）；in=null → 清空全部入边（悬空化：上游 out 同步清除、barrier 计数回退）
  //   out = 设/替换出边（原子替换旧 out，out 单值 → 1 对多天然被拒）；out=null → 断开出边（悬空化，结果保留可再投）
  //   from 已 completed 且结果缓冲/归档中 → 立即向 to 投递（to 未运行才可；barrier 计数同步更新，归零 → to 启动）
  //   from 已 completed 且结果已投递给旧目标（旧目标尚未启动）→ 原子改投（旧目标入边移除、计数回退）
  //   to 已在运行 → 拒绝：「目标已运行，输入已冻结，先 NodeCancel 再重建」
  //   DAG 环检测（DFS：to 的传递下游含 from → 拒绝）
NodeList(project)
  // Flow Map 快照：nodes[]（id/name/agent/status/in/out/hasWorktree/worktree/result 摘要/retries/创建时间/ttl 剩余）
  //   + worktrees[]（磁盘 git worktree list 推导，仅信息展示）+ meta
  // 状态信息并入 NodeList（NodeStatus 工具删除，v2）；分发器会话开场必读
NodeCancel(node-id)
  // 取消运行中节点（复用 supervisor cancel 语义）；结果不投递；上游已投结果留缓冲/归档
ProjectCreate(name, workspace, description?) // Nebula 用：建项目定义 + 工作区 .nebflow/（Agent.md 模板 + flow-map.json + .gitignore）
```

**NodeList 返回结构（v2：字段补充——含 hasWorktree / result 摘要，支撑分发器动态建合并 node）**：

```json
{
  "nodes": [
    { "id": "n-3f9a2b", "name": "调研-康普顿成像", "agent": "Explorer",
      "status": "completed", "in": ["n-1a2b"], "out": "n-5c6d",
      "hasWorktree": false, "worktree": null,
      "result": "<最终输出摘要 500 字>",   // 节点结果 = 最终输出全文，NodeList 显示摘要（v4：无 verdict/slots）
      "retries": 0, "createdAt": 1725123456789, "completedAt": 1725123499999, "ttlLeftSec": 120 },
    { "id": "n-7c1d2e", "name": "合并-两条线成稿", "agent": "Explorer",
      "status": "pending", "in": ["n-3f9a2b", "n-8a1b3c"], "out": "Nebula",
      "hasWorktree": true, "worktree": "worktrees/wt-new", "result": null, "retries": 0, "ttlLeftSec": null }
  ],
  "worktrees": ["worktrees/wt-new"],      // 磁盘推导（git worktree list），仅展示；管理走 git/bash（v2）
  "meta": { "project": "phd-notebook", "updatedAt": 1725123500000 }
}
```

> **hasWorktree 的用途（作者原意）**：任务分发器 NodeList 看到节点是否在 worktree 内工作，据此**动态创建合并 node**——例如一开始是单线工作（A.out=Nebula 直接汇报）；旧工作未完成时新工作来了 → 分发器开并行 worktree node（W，worktree 参数）→ 建合并 node（M，in=A+W 的 barrier）→ 把 A 改接到 M、W 接到 M → M 合并两线后 out=Nebula。NodeList 的 hasWorktree 字段让分发器一眼看清各线工作区归属，决策合并拓扑（完整示例见 §2.8 与附录）。

**校验清单（工具层统一拦截，0 spawn 0 token）**：
- agent/skill/mcp/worktree 存在性（动态枚举，热加载；worktree 目录存在性——不存在提示「先 git worktree add」）
- 引用的 in/out 节点存在
- DAG 环检测（DFS）
- loop detect（节点级重复：同 agent + 同 task 文本归一化，TTL 窗口内已有 running/completed → 拒绝并提示「疑似重复派发」）；nodename 同 Flow Map 内唯一（重复 = 编辑请求，工具层按编辑处理）
- 1 对多拒绝（NodeEdit out 单值替换语义）
- 状态守卫（to 已运行 → 拒绝接线；改接旧目标已消费 → 拒绝并引导）

### 2.3 动态拓扑：OUT 悬空 / 改接的竞态一致性

**核心规则：边是原子的第一类变更；节点输入在启动时冻结。（v2：全部由 NodeEdit 表达）**

| 场景 | 规则 |
|---|---|
| 节点运行中，改接其 OUT | 自由——未来投递走新目标（结果未产生，无竞态） |
| 节点已 completed，结果缓冲中，改接 | 立即向新目标投递缓冲结果（旧目标若有入边则移除、barrier 计数回退）——「5min 保留 + 归档」正是为改接服务的（v2：归档后仍可接线投递） |
| 节点已 completed，结果**已投递给旧目标**，且旧目标**尚未启动** | 原子改投：旧目标入边移除、计数回退；新目标入边 +1；缓冲结果投新目标 |
| 结果已投递给旧目标，且旧目标**已启动/已完成** | **拒绝**——旧目标输入已消费，改投会造成语义漂移。引导：`NodeCancel` 旧目标 → 改接 → 重建旧目标节点 |
| 改接目标已在运行 | 拒绝（§2.2 状态守卫） |
| 悬空节点的 out 被接入下游 | 等同「已完成节点改接」的投递路径——结果自动投递，**不必重新跑**（v2 强化：含归档结果） |

**实现落点**：flow map store 维护 `edges`（from→to，status: pending/delivered）+ 每节点 barrier 剩余计数（从入边推导）。NodeEdit 对 edges + 计数做**单事务更新**（Ref.update 原子性），投递动作在事务内裁决。这与现有 `FlowDagExecutor.pendingJoins` 的 barrier 计数模型同构，迁移成本低。

### 2.4 并行多 to1（barrier）与「不支持 1 对多」的执行

**多 to1（支持）**：多个上游节点 out 指向同一下游 → 下游为 barrier 合并节点。启动条件 = 全部入边 delivered（恰好一次激活）。语义与现有 flow barrier join（`inDegrees ≥ 2` + counting barrier）**完全一致**——直接迁移 pendingJoins 逻辑；取消/失败的上游按 `collect` 语义结算（占位符标记 + 计数到零继续），或按 onFail=abort 终止合并（二选一，默认 collect——研究型更常用；工具层不给配 onFail，节点级由分发器在 task 文本中声明意图）。

**1 对多（不支持）**：一个节点的结果只投递一个目标。执行方式 = **工具层硬拒绝**：
- `NodeEdit.out` schema 单值（数组 → 校验错误）；out 是**替换**语义而非追加（要换目标直接给新 out）；
- 与旧 FlowExecute fanout 的差异：**fanout 不再是图结构能力**——并行 = 分发器建 N 个独立入口节点（各自 task 子任务），N 路并行由「多个入口同时运行」实现，汇聚由 barrier 合并节点实现。分发器承担了原来 planner 节点的分解职责。

### 2.5 Worktree：并行开发 + 合并（v2：创建/管理走 git + bash）

- **创建（v2 修订）**：`git worktree add <workspace>/.nebflow/worktrees/<name>`——由分发器在会话内用 **Bash** 执行（分发器工具白名单含 Bash），或用户手动；`.nebflow/` 已 gitignore，不污染项目 repo。**WorktreeCreate / WorktreeList 工具删除**（作者：「worktree不是用git和bash就搞定了吗」）；节点 `worktree` 参数保留（NodeEdit 传入，指向已存在的 worktree 目录）。
- **接线模式**（作者原意）：

```
Task ──→ A（现有功能，主工作区）
Task ──→ W（新功能，worktree 参数）──┐
                                    ├─→ M（合并节点，barrier）→ Nebula
              A ────────────────────┘
```

- 节点 `worktree` 参数 → NodeRunner spawn AgentActor 时 `projectRoot = <workspace>/.nebflow/worktrees/<name>`（现有 projectRoot 机制已支持）；工具层校验目录存在（不存在 → 提示「先 git worktree add」）。
- **合并节点纪律**（复用 64 页 deck 并行范式）：并行节点各自独占 worktree 互不写共享；git 提交/合并**收口到合并节点单线程执行**（M 的 task 文本明确「合并 worktree 到主分支 + 处理冲突」）；A 与 W 若需共享契约文件，由分发器在 task 文本中固化（契约唯一写者原则）。
- **分发器视角**：NodeList 返回 `hasWorktree` + `worktrees[]`（磁盘推导）——分发器据此判断哪些线在独立工作区、可并行合并（§2.2）。

### 2.6 Flow Map：存储 / 环检测 / loop detect / 5min 消失

| 机制 | 设计 |
|---|---|
| 存储 | 磁盘 `flow-map.json`（write-through，每次变更落盘，重启恢复；含活动区 nodes/edges + 归档区 archive）+ 内存 Ref（ProjectActor）+ WS 事件（nodeCreated/nodeUpdated/nodeCompleted/nodeRemoved）推前端 |
| DAG 环检测 | NodeEdit（in/out 参数）时 DFS 校验（O(V+E)，项目图小无性能顾虑）；成环 → 工具层拒绝（错误码 + 路径提示）。**结构上 DAG 无环 → 天然无死循环拓扑** |
| loop detect（节点级重复） | 同 agent + task 文本归一化匹配，TTL 窗口内已有 running/completed 节点 → 拒绝「疑似重复派发」；防分发器/Nebula 重复触发同一子任务 |
| 循环（redo）如何表达 | **轮次 = 重触发**：评审节点把结论写进**输出文本**（如「发现 3 处问题：…」）→ Nebula 读结果判断未通过 → 触发新一轮节点子图（fix 节点 + re-review 节点），DAG 仍无环。无 switch/redo 回路——时间性迭代由 Nebula/分发器在验收层表达（§4 配方示例；v4：读输出文本判断，非 verdict） |
| 5min 消失 | 终态节点 `ttlExpireAt = completedAt + 5min`；ProjectActor 定时器到期 → 从活动图移除（WS nodeRemoved）+ 节点记录（**含结果全文**）移入 `flow-map-archive.json`。**显示消失 ≠ 结果丢弃**（v2）——NodeEdit 接线仍可引用归档节点结果投递（§2.1）。**测试档可配置缩短（如 10s）** |
| 跨阶段引用 | 活动图移除后，NodeEdit 接线仍从归档读结果投递；跨触发阶段（>5min）需要上游结果时，分发器 prompt 由 Nebula 携带结果文本（Nebula 是结果的第一接收者，天然持有上下文）——NodeResult 工具已删除，无显式读取工具（v2） |

### 2.7 结果回传（结果 = 节点最终输出，NodeRunner 自动保存 + 沿 out 投递）

- 节点 agent 完成 → **其最终输出文本即节点结果** → NodeRunner 自动读取（无需任何上报工具）→ 写入节点 result + 状态 Completed。**result 持久保存**（活动区 flow-map.json；终态 TTL 到期整体移入归档区，结果全文保留）。无 verdict 字段——结果就是输出文本本身（v4：与 Delegate 语义一致，子 agent 最后汇报文本即结果）。
- 投递（按 out 边）：
  - `out = 节点` → 该节点入边 delivered，barrier 计数--，归零 → 启动下游（输入 = task 上下文 + 各上游 result，每份带 `=== Node <name> ===` 头，同现有 `.all.output` join 格式）；
  - `out = Nebula` → `ImmediateInput("[Node '<name>' completed]\n<result>", source="node", eventType="completed")`——复用现有 flow 结果注入气泡语义；
  - `out = null（悬空）` → 结果保留在节点 result（持久化）；**之后 NodeEdit 接线（out 指向新节点）→ 自动投递**（v2：从活动或归档读结果，不必重跑）。
- 失败：`ImmediateInput("[Node '<name>' failed]\n<err>")` 投 Nebula（out=Nebula）或按上游 collect 结算（out=节点）。

### 2.8 任务分发器：交互协议与上下文

```
用户 ──任务──▶ Nebula ──Mail(→project)──▶ ProjectActor ──spawn──▶ 分发器会话(fresh)
                                                                   │
  分发器: NodeList(读 Flow Map 现状)                               │
        → 分解子任务 → NodeEdit ×N（入口节点创建即运行）             │
        → NodeEdit 接线（barrier/串联/悬空/改接）                   │
        → 会话结束（无需回报：拓扑/状态已落 Flow Map，Nebula 用 NodeList 只读查看）◀───────┘
                                                                   
节点后台运行 → 结果经 out 边投递（下游节点 / Nebula；悬空结果保存，接线后自动投递）
Nebula 验收 → 需补充/下一阶段/打回 → 再 Mail(→project)（新一轮分发器会话，读图继续）
```

**协议要点**：
1. **分发器上下文 = Flow Map 快照 + 任务文本**（注入 prompt，无会话记忆）；「我上次干了什么」的答案在图上（NodeList 可读）。
2. **一次触发覆盖一个阶段**：入口节点（可能并行）+ 合并节点 + 接线在同一会话完成；结果依赖后续决策的（如「收集完再决定成文结构」）→ 阶段拆分 = 多次触发（上游结果先进 Nebula 或悬空保存，下一轮分发器从 NodeList / 归档接线投递或 Nebula 转述续接——NodeResult 工具已删除，v2）。
3. **分发器是唯一 Node 工具使用者**；节点 leaf（无 Node 工具）→ 深度天然为 1。
4. 用户补充：Nebula 收到补充 → 触发分发器 → 分发器 NodeList 看到运行中 A → NodeEdit 建 B（in=A 或改接）→ 完成接线。

**动态合并 node 示例（v2 新增——作者：单线工作 + 旧工作未完时新工作来）**：

```
触发 1：NodeEdit(nodename="A", agent=..., task=旧工作, out=Nebula)
        // 单线工作，A 完成后直接汇报 Nebula
触发 2（A 运行中，新工作来）：分发器 NodeList 看到 A running（hasWorktree=false）
  → NodeEdit(nodename="W", agent=..., task=新工作, worktree="worktrees/wt-new")   // 并行 worktree 节点，先悬空，创建即运行
  → NodeEdit(nodename="M", agent=..., task=「合并 A、W 两条线成稿」)               // 合并节点，Wiring 态
  → NodeEdit(nodename="A", out="M")   // A 运行中改接 = 自由；A 完成 → 结果自动投 M
  → NodeEdit(nodename="W", out="M")   // W 完成 → 结果自动投 M
  → NodeEdit(nodename="M", out=Nebula) // M barrier 两线全到 → 启动合并 → 汇报 Nebula
```

---

## 3. 引擎改造

### 3.1 执行内核：NodeRunner 共享内核（复用 Delegate 统一分析方案 A）

统一分析结论：「Delegate/SubTask/flow 节点三处重复的 spawn 逻辑抽成共享 NodeRunner，工具层与结果形态保留差异」——新架构下差异入口全部退役，**NodeRunner 成为唯一执行内核**：

```
NodeRunner.run(nodeDef, input, resources):
  1. spawn AgentActor（agent 按名解析全局库；skill/mcp/preset 合入 agentDef；
     projectRoot = worktree 或工作区；rootSessionId = 项目根 session）
  2. sessionId = "node-<uuid>"（复用 dag- 会话命名/前端面板路由）
  3. 监督：onError=Restart + maxRetries + checkpoint 恢复（复用 dag 会话持久化）；**不设运行超时**（与 Delegate/flow 一致，卡死由 TaskStuckWatcher 既有 10min 零活动检测兜底）
  4. 结果：读取节点 agent 最终输出 → 写 flow map store（节点 result，持久保存）→ 投递 out 边
```

- FlowTreeActor → **ProjectActor**（挂载/分发器会话/Flow Map 状态/TTL 定时器——TTL 仅终态显示，非运行超时）；
- FlowDagRunner/FlowDagExecutor 的 barrier/join/checkpoint 逻辑迁移进 **FlowMapStore + NodeRunner**（barrier 计数从静态编译推断改为动态边状态推导——新模型边是运行期构建的）；
- 前置条件：**阶段 0 先抽共享内核零行为变化**（统一分析阶段 1 的验收点全量沿用），旧工具不动直到阶段 3 退役。

### 3.2 Mail 路由改造

| 现状 | 改后 |
|---|---|
| 无 team 上下文：Mail 只能发 team 名（→ Manager） | 无 project 上下文：Mail 只能发 project 名（→ ProjectActor → 分发器）；Nebula 收发不变 |
| team 内 short name 路由（成员常驻会话） | 无「成员」概念 → short name 路由删除；节点无 Mail 身份 |
| team/agent 显式路由 | 删除（无成员） |
| queue 模式（串行任务链） | **退役**——串行链由边表达（A→B→C），Mail 只做「触发分发器」（无回报：结果沿 out 边投递，不经 Mail），immediate 足够 |
| TeamSessionRegistry（team→session 映射） | ProjectRegistry（project→ProjectActor 映射） |

### 3.3 任务工具

- TeamTaskCreate/List/Update → **退役**：任务跟踪被 Node 生命周期 + Flow Map 取代（NodeList = 实时看板；节点状态 = 任务状态；Flow Map 视图 = 用户可见的任务视图）。
- 若试点暴露「跨节点非执行性协作任务」（如人工确认项）缺口 → v2 引入轻量 **ProjectTask**（项目作用域，工具层同 TeamTask 改造），但不进 v1。

### 3.4 保留清单（不动）

| 保留 | 理由 |
|---|---|
| F0-F3 注入屏蔽 / 压缩机制 / 记忆 Nebula-only | 已按 memory-redesign 落地，与 Node 模型正交 |
| BaseTools / MCP / preset / guardrails / 权限策略（rootSessionId 继承） | 执行内核共享（分发器白名单含 Bash，用于 git/worktree 管理，v2） |
| NodeStatus enum / checkpoint 恢复 | 直接复用（NodeStatus **工具**退役——状态随 NodeList 返回；enum 保留并扩展 wiring 态，v2）。FlowReport / FlowReportStore **移出保留清单**（v4：verdict 无消费方——结果=节点最终输出，NodeRunner 自动读取保存，无需上报工具） |
| WS 事件体系 / 前端面板框架 | 展示层复用（Sub-Agents 面板 → **Project 面板 + Flow Map 视图**换皮，§3.5） |
| Mail 的 immediate 投递 + type 标签（INFO/INTERRUPT 等；RESULT 退役——结果不经 Mail，沿 out 边投递） | Nebula → Project **触发** Mail 沿用；分发器无回报 Mail（v3） |

### 3.5 前端改造（v2 新增章节——作者 08-31 23:34 前端设计；v5 修订——作者 09-01 00:22 前端设计补充：Flow Map 标签页展示 / Project 按钮取代 Team-Flow 按钮 / Agent.md 可点击查看可改 / 整体参考 team 面板设计）

#### 侧边栏：Project 按钮取代 Team/Flow 按钮

- **项目入口占据当前 Team/Flow 按钮的位置**（取代语义，非并列）——点击打开 **Project 面板**。试点期旧体系仍在运行：旧 Team/Flow 面板**收进二级入口/折叠**（如侧边栏底部「旧面板」折叠项），阶段 3 移除。
- **整体参考现有 team 面板设计**：Project 列表/卡片/进入交互沿用 team 面板既有视觉与交互范式（列表布局、卡片样式、点击进入的行为模式一致，仅内容换为 Project 语义）。
- **Project 列表——一行一个 project**，每行展示：

| 字段 | 说明 |
|---|---|
| project name | 主标题，点击在**标签页**打开 Flow Map |
| 工作区 | 路径 + **「在文件浏览器中打开」按钮**（一键打开工作区目录） |
| Agent.md | 入口（点击查看/编辑项目 agent 指令——交互同 team 面板 rules.md 入口） |
| 描述 | project.json 的 description |
| 当前后台运行 agent 数 | 活动区 running 节点计数（实时） |
| 简要状态 | 如「3 节点运行中 / 空闲 / 1 节点失败」 |

- **Agent.md 可点击查看、可改**：交互与当前 team 面板里 rules.md 的入口一致——点击查看内容 + 编辑，保存写回工作区 `.nebflow/Agent.md`（取代 rule.md 的查看/编辑体验）。

#### Project Flow Map 视图（标签页形态）

- **点击 project → 在标签页打开 Flow Map**：与 flow-run 标签页同形态（非弹窗）；**关闭标签页不影响项目数据/Flow Map 状态**（标签页只是视图窗口，拓扑与结果在 flow-map.json，重开即恢复）。**主形态即标签页**——无页面内返回按钮（标签页自身可关闭/切换即退出；返回 Project 列表 = 关闭/切换标签页，与 flow-run 标签页一致）。
- **复用现有 flow-run 面板的显示设计**：节点卡片（状态色）+ 边连线 + 运行指示 + TTL 倒计时——把 flow-run 面板从「单次 flow 运行视图」换皮为「项目常驻拓扑视图」。
- **已完成节点保持 5 分钟后不显示**（TTL 只影响显示；结果保留归档，见 §2.1——前端隐藏不等于数据删除，接线投递不受影响）。
- 节点卡片展示：name / agent / status / hasWorktree 标记（在 worktree 内工作的节点显示工作区徽标）/ result 摘要；点击卡片可查看结果详情（从活动或归档读取）。

#### Node 会话语义（前端呈现基准）

- **node 会话都不持久、无记忆**：Node 是 ephemeral——每次运行是干净上下文（leaf），**不注入记忆**（与记忆系统 Nebula-only 裁定一致）；前端不展示节点「历史会话」，只展示本次运行状态与结果。
- **节点间靠结果传递而非记忆**：A 的结果经 out 边投递给 B（B 的输入 = task 上下文 + A 的结果文本），前端拓扑图上的边即结果流。Flow Map 视图是用户观察「结果如何流动」的窗口。

---

## 4. 取代路径（分阶段）

### 阶段 0：试点准备——引擎地基（不改旧工具）

| 项 | 内容 |
|---|---|
| 目标 | 新模型最小可用内核 |
| 改什么 | Project 实体 + ProjectActor + FlowMapStore（含环检测/TTL/归档/结果持久保存）+ **NodeRunner 共享内核抽取**（统一分析阶段 1：抽 spawn/registry/结果捕获三处收敛，行为零变化）+ Node 工具集（**NodeEdit/NodeList/NodeCancel**，v2 精简）+ 分发器 agent（全局 agent，含 Node 工具 + Bash 白名单）+ Flow Map 前端面板最小版（Project 列表 + Flow Map 视图骨架） |
| 验收点 | ① NodeEdit→NodeList→NodeCancel 全链路可用（0 文件写入可证：创建节点后 `ls workspace/.nebflow/` 只有 flow-map.json 被 store 写，无手写文件）② 冒烟：模拟项目建 1 入口节点（task + out=Nebula）→ 节点跑 → 结果回 Nebula（真实启动，curl/WS 断言）③ 环检测：NodeEdit 构造 A→B→A 被拒（二值）④ 1 对多：NodeEdit 对已有 out 节点再给 out 被拒（二值）⑤ 旧体系全量 spec 回归绿（Delegate/SubTask/Flow 行为零漂移） |
| 风险 | NodeRunner 抽取动旧路径 → 方案 A 纪律：先抽共享内核、旧工具入口不动、全 spec 回归后再进试点 |

### 阶段 1：试点运行——单项目 + 简单 Node 流

| 项 | 内容 |
|---|---|
| 目标 | 真实项目验证模型 |
| 试点范围 | **1 个真实项目**，建议**调研/写作类**（如 phd-notebook 文献调研、或新开试点调研项目）：不依赖旧 Team 成员、不碰 Nebflow 主仓、天然适配「并行收集 → barrier 合并」；开发类试点（worktree 场景）留到阶段 1 后段或阶段 2 首项目 |
| 场景（5 个必跑，v2 语义） | ① 单节点调研（NodeEdit 建节点，task + out=Nebula）② 串联（NodeEdit 接线：调研→成文）③ 并行 3 路 → barrier 合并 → Nebula（NodeEdit in 累积）④ 用户补充改接（A 运行中：NodeEdit 改 A.out→B，B.out→Nebula）⑤ worktree 并行 + 合并节点（git worktree add 建工作区，NodeEdit 带 worktree 参数；如试点项目为开发类） |
| 旧体系处置 | **并行运行**：试点项目只走新架构；其他 Team 照常；**在途 Team 工作不迁移**（阶段 2 才动）。作者裁定「完全取代」是终态，试点期新旧并存是迁移的必要过渡——旧 Team 停摆会阻塞在途工作，不可接受 |
| 验收点 | ① 五场景端到端各跑一遍（Mail project → 分发器建节点 → 节点跑 → 结果 → Nebula 验收）② 改接竞态三断言：运行中改接 / 完成后缓冲改接 / 旧目标已启动时改接被拒 ③ barrier 时序：3 路全到才启动合并节点（观测 WS 事件序）④ 5min TTL（测试档 10s）：完成节点从 Flow Map 消失 + **归档保留可接线投递**（v2：显示消失后 NodeEdit 接线，下游仍收到结果——「显示消失 ≠ 结果丢弃」二值断言）⑤ 重启恢复：kill 后 flow-map.json 恢复，未完成节点状态正确（隔离实例，勿动 8080 宿主）⑥ 用户真实使用 2-3 个任务 |
| 风险 | 分发器上下文不足 → 每次触发注入完整快照 + prompt 自包含；试点范围失控 → 锁 1 项目 5 场景，验收全二值 |

### 阶段 2：铺开迁移——存量 Team → Project

| 项 | 内容 |
|---|---|
| 目标 | 逐项目迁移 |
| 迁移步骤（每项目） | ① 建 Project（ProjectCreate + workspace/.nebflow/ + Agent.md）② `rules.md` 内容移植进 Agent.md（团队纪律 → 项目指令）③ 成员职责 → 沉淀为**节点配方文档**（「这类任务怎么拆节点」供分发器参考，等价于把 Manager 的路由经验文档化）④ 9 个预定义 flow → 配方（code-review = 扫描→评审→fix 轮次（重触发实现）；research = N 入口并行→verify barrier；release = 审→做→包→复审轮次；weekly-summary/memory-consolidation 等短链直接配方化）⑤ 在途任务跑完再停旧 Team ⑥ 侧边栏：Project 按钮取代 Team/Flow 按钮位置（§3.5），旧面板收进二级入口/折叠 |
| 验收点 | ① 每迁移项目：同任务在新架构跑通（冒烟：真实启动 + 关键产出断言）② Mail(→旧 team 名) 返回迁移提示（路由先兼容后移除）③ 迁移项目数 ≥ 当前在用 Team 数 ④ 前端：Project 面板列表渲染 + 点击项目在**标签页**打开 Flow Map 视图 + 标签页关闭/切换（§3.5 验收） |
| 风险 | 新旧双路由并存 → 阶段 2 期间 Mail 同时支持 project（新）+ team（旧，仅路由到「已迁移」提示或照常）；配方质量不均 → 每个预定义 flow 迁移都带验收样例 |

### 阶段 3：退役旧体系

| 项 | 内容 |
|---|---|
| 内容 | 删除 Team/Flow/Delegate/SubTask/FlowExecute/FlowTrigger/TeamTask 工具与实体代码；`teams/` `flows/` 归档；Mail 路由只留 project + Nebula（Mail 仅触发、无回报语义——结果沿 out 边投递）；FlowTreeActor/FlowDagRunner/FlowDagExecutor 删除（NodeRunner 已接管）；**侧边栏 Team/Flow 面板移除**（含阶段 2 折叠进二级入口的旧面板项；仅留 Project 面板，v2） |
| 验收点 | ① 全量测试通过（旧工具 spec 移除或改断言「已退役」）② 旧工具调用返回「已退役，使用 Project + Node」③ 文档/系统提示更新（team 前缀注入移除、侧边栏面板替换） |
| 风险 | 遗漏调用方（Skill 文档/记忆中的 team 引用）→ 退役前 grep 全库调用点，逐处迁移 |

### 数据迁移要点

| 数据 | 迁移 |
|---|---|
| `teams/<name>/team.json` | → `projects/<name>/project.json`（lead/members 字段退役，仅保留 name/description/workspace） |
| `teams/<name>/rules.md` | → 工作区 `.nebflow/Agent.md`（内容移植 + 去 team 化措辞） |
| `teams/<name>/agents/*` | 有价值的成员角色 → 全局 agents/ 或 skill；其余归档 |
| `flows/<name>/flow.json` | → 配方文档（`~/.nebflow/docs/Nebflow/project-node-patterns.md`，阶段 2 建） |
| 在途任务（TeamTask store / 运行中 flow） | **不迁移**——跑完自然终态；新任务一律走新架构 |
| 会话历史（sessions/） | 不动（审计用途） |

---

## 5. 风险与对策

| # | 风险 | 等级 | 对策 |
|---|---|---|---|
| R1 | **动态拓扑竞态**：改接时节点已完成/结果已消费，改投造成语义漂移或重复投递 | 高 | 边为第一类实体 + 原子改接（单事务更新 edges + barrier 计数）；`deliveredTo` dedup；旧目标已启动 → 工具层拒绝并引导 NodeCancel 重建；§2.3 场景表全覆盖 + 阶段 1 三断言验收 |
| R2 | **分发器上下文不足**：单次会话无记忆，复杂多阶段任务衔接断裂 | 中 | Flow Map = 唯一状态载体（NodeList 快照注入每次会话）；阶段拆分 = 多次触发；跨阶段结果由 Nebula 持有转述或接线投递（v2：结果持久保存，归档可接线取用）；分发器 prompt 自包含模板（现状快照 + 任务 + 配方引用） |
| R3 | **Node 失控/编排无全局视角**：分发器逐个建节点，缺少 DAG 级整体监督 | 中 | Flow Map 即全局视角（NodeList 实时全量，含 status/result 摘要/hasWorktree）；`NodeCancel` 随时止损；**Node 不设超时**（与 Delegate/flow 一致），卡死兜底 = 复用 TaskStuckWatcher 既有 10min 零活动检测；loop detect 防重复派发；分发器会话结束前自检（NodeList 再读一遍核对拓扑） |
| R4 | **barrier 死锁**：上游取消/失败导致合并节点永远等不到 | 中 | 取消/失败节点按 collect 结算（占位符 + 计数归零继续），或 abort 终止（分发器在 task 文本声明意图，默认 collect）；barrier 等待卡死同样由 TaskStuckWatcher 既有零活动检测覆盖（等待不设超时） |
| R5 | **（v2 重写）结果保存与投递的边界**：原「5min 缓冲窗口」可能造成结果丢失/跨阶段取不到 | 中 | **结果持久保存**：活动区 flow-map.json + 归档区 flow-map-archive.json（结果全文保留，长期可接线投递）；**显示消失 ≠ 结果丢弃**（TTL 只管显示）；投递裁决从活动或归档读上游结果；结果第一接收者 = Nebula（持有上下文，跨阶段由 Nebula 转述）；TTL 可配置 |
| R6 | **工作区 git 污染**：`.nebflow/` 落进项目 repo | 中 | ProjectCreate 时预写 `.gitignore`（含 `.nebflow/`）；试点验收含「项目 repo 无 .nebflow 提交」断言 |
| R7 | **worktree 冲突**：并行节点写同一文件 / git 锁冲突 | 中 | 复用 64 页 deck 并行七条纪律（节点独占 worktree、契约唯一写者、git 收口合并节点、失败幂等）；worktree 数量受项目磁盘/分支约束，由 `git worktree list`（分发器 Bash/用户）透明化，NodeList hasWorktree 辅助（v2：WorktreeList 工具删除） |
| R8 | **与记忆系统衔接** | 低 | 节点 leaf 不注入记忆（ephemeral、无持久会话——v2 明确）；项目级知识沉淀走 Agent.md（分发器任务文本可引用）；记忆系统零改动 |
| R9 | **NodeRunner 抽取回归旧路径**（Delegate 是核心路径） | 高 | 统一分析方案 A 纪律：阶段 0 先抽共享内核零行为变化 + 全 spec 回归绿，再开新工具；试点期旧工具照常 |
| R10 | **试点范围失控 / 试点项目选错** | 中 | 锁 1 项目 5 场景；选调研/写作类不碰主仓；验收全二值；失败快速回退（新架构隔离在试点项目，不影响旧体系） |
| R11 | **1 对多约束过强**：某些旧 flow 形状（planner 扇出）无法表达 | 中 | 语义替换：fanout → 分发器分解为 N 个独立入口（分发器即 planner）；无法分解的（单节点结果天然要分发多路）→ 分发器用合并节点反向聚合并由合并节点分发（合并节点 out 仍单目标，分发给 Nebula 再由 Nebula/分发器续建）——保持 1 对多硬约束不变 |
| R12 | **结果注入语义变化**：ImmediateInput（flow 式）取代 ExternalEvent（delegate 式）的前端兼容 | 中 | 复用现有 flow 结果气泡（source="node"）；Sub-Agents 面板 → **Project 面板 + Flow Map 视图**换皮（§3.5）；阶段 1 前端断言含气泡与视图呈现 |

---

## 6. 结论与建议

1. **模型成立**：Project + 分发器 + Node + Flow Map 把「Team 常驻编排」收敛为「以图为状态、以工具建节点」的动态编排；执行内核完全复用现有 AgentActor/checkpoint（统一分析已证；v4：无 FlowReport——结果=节点最终输出，NodeRunner 自动读取保存），改动集中在**编排层**（FlowTreeActor→ProjectActor、DAG 编译器→Node 工具校验器、FlowDagExecutor→NodeRunner）——工程量可控。
2. **工具极简（v2）**：Node 的 in/out 两个口由单一 **NodeEdit** 表达（创建/改接/断开统一）；状态随 NodeList；结果靠「持久保存 + 自动投递」；worktree 走 git/bash——分发器工具面收敛为 NodeEdit/NodeList/NodeCancel + BaseTools（**无 Mail**——无需回报，v3），心智负担与实现量同步下降。
3. **推荐实施路径**：阶段 0（共享内核 + 新模型地基，旧体系零变化）→ 阶段 1（单项目 5 场景试点，新旧并行）→ 阶段 2（逐项目迁移 + 配方化 + 前端面板换皮）→ 阶段 3（退役）。试点是验证「创建即运行/改接/barrier/5min 显示 TTL + 结果持久投递」四条新语义的关键闸门，**试点通过前不铺开**。
4. **试点项目建议**：调研/写作类真实项目（如 phd-notebook），理由：不依赖旧 Team、天然适配并行收集→合并、风险隔离。
5. **试点期旧体系处置**：并行运行不迁移在途工作；「完全取代」在阶段 3 兑现，不是试点第一天。
6. **立即可做的三件事**（与试点并行，低风险）：① per-node `preset` 补实现（统一分析已指出的文档/实现落差）② NodeEdit 统一签名 + NodeList 字段设计落地（hasWorktree/result 摘要/状态并入，v2 核心）③ 9 个预定义 flow 配方化文档框架（阶段 2 前置）。

---

## 附录：旧 flow 配方化示例（阶段 2 用，v2 语义）

**code-review（旧：scanner→reviewer⇄fixer 循环）** → 节点配方：
```
触发 1：分发器建 扫描节点(NodeEdit S, task=代码扫描, out→评审节点 R) + 评审节点(NodeEdit R, in=S, out→Nebula)
        S 完成 → R 启动 → R 完成 → 结果到 Nebula
触发 2（R 输出文本表明未通过——如「发现 3 处问题：…」，Nebula 读结果判断后触发）：分发器建 fix 节点(NodeEdit F, in=R[归档结果自动投递]) + 复审节点(NodeEdit R2, in=F, out→Nebula)
        …每轮新节点，DAG 保持无环，轮次 = 重触发
```
**research（旧：planner→r×2→verify→writer）** → 节点配方：
```
触发 1：分发器 NodeEdit r1/r2（各自 task=子题, out 悬空）→ NodeEdit verify（task=核验指令, out→writer? 或 Nebula）→ NodeEdit r1 out→verify, r2 out→verify
        r1/r2 并行跑（入口节点创建即运行）→ verify barrier 全到启动 → 结果到 Nebula
触发 2（可选）：分发器 NodeEdit writer（in=verify, out→Nebula）→ 成文
```
**单线改并行合并（v2 新增——作者场景）** → 节点配方：
```
触发 1：NodeEdit A（task=旧工作, out=Nebula）          // 单线直接汇报
触发 2（A 未完，新工作来）：NodeEdit W（task=新工作, worktree=wt-new）→ NodeEdit M（task=合并指令）
        → NodeEdit A out→M（运行中改接）→ NodeEdit W out→M → NodeEdit M out→Nebula
        // M barrier 收 A+W 两条线 → 合并后汇报 Nebula
```
