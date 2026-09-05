> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Pi Agent 调研：极简工具集对我们的启示

> 触发：用户 08-25 17:10「调研一下 pi agent。它的工具集十分简单，对我们有什么启示？」
> 类型：调研报告（阶段文档，冻结即终稿）
> 日期：2026-08-25
> 调研人：Explorer
> 结论一句话：Pi 用「4 个工具 + 全行业最短 system prompt」证明——**工具集精简不是能力妥协，而是主动设计**。Nebflow 的「多」大部分有价值（平台级安全/编排/可观测必需），但有几处冗余可直接借鉴 Pi 收敛，方向与你 17:09 裁定的 Edit/MultiEdit 合并完全一致。

---

## 摘要（用户可读）

**Pi 是什么**：OpenClaw（GitHub 18 万 star 的个人智能体项目）底层的编码智能体引擎，作者 Mario Zechner（libGDX 作者），Flask 作者 Armin Ronacher 在 2026-01-31 发博文力荐并称其为「自己唯一在用的编码 agent」。主仓库 `earendil-works/pi`。

**它有多简单**：默认只给模型 **4 个工具**：`read`、`write`、`edit`、`bash`。全行业最短 system prompt。刻意不做 MCP、sub-agents、plan mode、权限弹窗——需要时让 agent 自己写扩展（"code writing and running code"）。

**为什么简单还能强大**：
- 前沿模型已经通过 RL 训练学会了怎么用工具，过多的工具列表反而消耗决策带宽、增加选错工具的概率；
- 能力外置：需要新能力时用 Extension（TS 代码）/ Skill（Markdown）补，而不是往内核塞；
- 会话树 + 热重载让 agent 能自举——自己写扩展、重载、测试、迭代。

**对我们（Nebflow）的直接启示**：
1. 你裁定的 Edit/MultiEdit 合并，正是 Pi 的方向（Pi 自己从 7 工具收敛到 4）；
2. BaseTools 固定注入集建议瘦身：`Issue` 等非核心工具移出固定集，只保留「读/写/搜/执行」四类；
3. 网络三工具（WebSearch/WebFetch/Curl）职责重叠，可评估合并或把 Curl 降为按需；
4. 注入策略借鉴「最小默认 + 按需扩展」：默认给极简集，任务需要时再注入，而不是白名单 + 固定集双膨胀；
5. 工具描述质量 > 数量：每个工具 description 都在持续烧 token 和决策注意力。

**不适用部分**（Pi 的做法对我们不可照搬）：
- Pi 不做 MCP/sub-agents/plan mode，是因为它是单用户本地 harness；Nebflow 是多 agent 编排平台，Delegate/AgentControl/FlowTrigger 等是平台级必需，不能砍；
- Pi 的「让 agent 自己写扩展」依赖完整代码闭环，我们已有等价基础（外部工具 JSON + 文件热重载），可以补的是引导闭环。

---

## 1. Pi Agent 确认

### 1.1 同名候选甄别

| 候选 | 是什么 | 匹配度 |
|---|---|---|
| **Pi（pi-coding-agent）** | OpenClaw 底层的极简编码智能体，`earendil-works/pi`，TypeScript，Mario Zechner 著 | **极高** |
| pi.ai | Inflection AI 的聊天机器人（对话产品，无 agent 工具集概念） | 低——没有"工具集"可言 |
| GitHub 上的其他 `pi-agent` 重名项目 | Pinterest 增长 agent、各种个人项目 | 低——小众、非"工具集极简"著称 |
| CSDN 中文社区「Pi Agent 编程助手」文章 | 多为对上述 Pi 的中文解读/教程 | 指代同一对象 |

### 1.2 判断依据

用户特征描述是「**工具集十分简单**」——这正是 Pi 最出名的特征：Armin Ronacher 原话「*It has the shortest system prompt of any agent that I'm aware of and it only has four tools: Read, Write, Edit, Bash*」。且 OpenClaw/Pi 是 2026 年 1-8 月现象级热度项目（OpenClaw 18 万 star），用户关注度吻合。结论：**指 OpenClaw 底层的 Pi 编码智能体**。

### 1.3 Pi 生态

![Pi 生态架构图](assets/pi-agent/pi-arch.svg)

- **Pi 三层**：`pi-ai`（统一 LLM 抽象，20+ 厂商）→ `pi-agent-core`（无状态 agent 循环 + 状态管理）→ `pi-coding-agent`（交互式编码 CLI 产品壳）
- **OpenClaw**：Pi 的嵌入式宿主——把 Pi 内核接到聊天通道（Slack/Telegram），就是 OpenClaw；MCP 用外部 `mcporter` CLI 桥接而非内建
- 同源扩展：`pi-tui`（终端 UI 库）、`pi-web-ui`（Web 组件）、`mom`（Slack bot 实证）、`pods`（GPU 编排）

---

## 2. Pi 的工具集设计分析

### 2.1 四个工具

| 工具 | 职责 | 设计要点（pi-book 第 19-23 章） |
|---|---|---|
| `read` | 读文件 | 偏移/分页读取、截断策略、图片读取；返回带行号文本而非原始内容 |
| `write` | 写文件 | 整文件写入 |
| `edit` | 精确编辑 | **oldText→newText 精确替换而非行号**；file-mutation-queue 串行化并发编辑；pluggable I/O（本地 fs/SSH/远程） |
| `bash` | 执行命令 | 有指引告诉模型何时用/何时不用；BashOperations 同样是 pluggable 接口 |

### 2.2 为什么简单：三条设计哲学

1. **极简内核（Tiny Core）**——内核只做三件事：调模型、跑循环、管状态。所有可选能力（skill、extension、prompt template、theme）都是外部资源。核心信念（中文社区总结，原话见来源 [2]）：「更少的工具和更短的提示词，反而可能让智能体更强大——前沿模型已经通过 RL 学会了如何使用工具，过度的系统提示词反而限制模型的自主判断空间」。

2. **能力外置，不内建**——需要新能力时，**让 agent 自己写扩展**（"You ask the agent to extend itself. It celebrates the idea of code writing and running code"）。Armin 的实测：他自己只额外加载了 **1 个工具**（本地 issue/todo 工具），其余全部是 skills（纯 Markdown 提示）和 TUI 扩展。

3. **每个"不做"背后都是"用更底层的机制组合出来"**：
   - 不做 sub-agents → 用 tool call 模拟
   - 不做 MCP → skill 更轻量、人类可审计；需要时用外部 `mcporter` CLI 桥接
   - 不做权限弹窗 → `beforeToolCall` / `afterToolCall` 钩子（prepare→execute→finalize 三阶段）更灵活
   - 不做 plan mode → `transformContext` 钩子可实现任何上下文策略

### 2.3 少工具如何完成复杂任务

- **钩子机制兜底安全**：工具执行三阶段 `prepare → execute → finalize`，`beforeToolCall/afterToolCall` 钩子承载安全审计、速率限制、权限控制；TypeBox schema 做参数验证——模型犯错时工具层兜底
- **会话树**：JSONL + parentId 树形会话，可分支/回溯——"修坏工具的 side-quest"不污染主会话上下文
- **热重载 + 自举闭环**：extension 状态可持久化到会话文件 → agent 写扩展 → 热重载 → 测试 → 迭代（docs 和 examples 就是给 agent 自己看的）
- **skills 即提示**：不带运行时能力，零依赖、人类可审计、版本控制友好，随用随取、不用即弃

### 2.4 工具收敛趋势（7 → 4）

pi-book（分析 v0.66.0，2026-04）描述的是 **7 个核心工具**（含独立的 find/grep/ls）；当前 main 分支 README（2026-08）明示 **4 个工具**。也就是说 **Pi 自己在 4 个月内主动砍掉了近一半工具**——把搜索类工具收回 bash/提示层。这与我们 17:09 裁定 Edit/MultiEdit 合并是同一个方向的收敛。

---

## 3. Nebflow 现状对比

### 3.1 三层体系（现状盘点）

| 层 | 内容 | 数量 |
|---|---|---|
| 内置 | Scala `ToolRegistry`（`registry.scala`）：文件/搜索/shell/网络/任务/编排全量内置 | **24 个**（Read/Write/Edit/MultiEdit/Glob/Grep/Bash/WebSearch/WebFetch/Curl/Pop/AskUserQuestion/TaskCreate/TaskUpdate/TaskQuery/Schedule/Mail/Delegate/AgentControl/FlowTrigger/SubTask/TransferFile/Load/FlowReport） |
| 外部 | `ToolLoader` JSON+脚本，**四层扫描** global < agent < team < flow（同名高优先级覆盖，内置永远赢），文件 watcher 500ms 防抖热重载 | 当前加载 3 个（Screenshot/Issue/check-issues） |
| MCP | `McpManager` 生命周期管理 + `buildAllowedToolSet` **per-agent 过滤**（agent.json mcpServers 白名单 + agent 专属 server 自动放行） | 按配置 |

### 3.2 注入机制（AgentCore）

- **BaseTools 固定集**（7 个，全 agent 无条件注入）：Read / Write / Edit / Glob / Grep / Bash / Issue
- **agent.json 白名单**：`tools: ["*"]` 或显式列表；Coder 显式 10 个、Nebula 显式 21 个
- **机制层注入**（#381 先例）：按 agent 类别固定追加——team 类 +Mail+SubTask，flow 类 +FlowReport，Nebula 专享 Schedule/Delegate/AgentControl，worker 剥夺 Mail/SubTask/Delegate/FlowTrigger
- 附加过滤链：flows 白名单驱动 FlowTrigger；fork 上下文剥 18 个副作用工具；save-turn 只留 Write/Edit/Read

### 3.3 数量对比

![工具数量对比](assets/pi-agent/tools-compare.svg)

| 维度 | Pi | Nebflow |
|---|---|---|
| 模型可见工具数（默认/实测） | **4** | 通用 agent ~10，Nebula **~28**（21 显式 + BaseTools + FlowTrigger + MCP） |
| 内核注册表 | 4 | 24 内置 + 3 外部 + MCP |
| 扩展机制 | Extension/Skill/Prompt/Template/Package | 外部 JSON 工具 + Skills + MCP + Teams/Flows |
| system prompt 长度 | 全行业最短 | 长（工具指南 + 团队/流程 + 记忆全注入） |
| 注入方式 | 默认 4 工具 + 按需 skill | BaseTools 固定集 + 白名单 + 机制层 |

### 3.4 哪些「多」是有价值的（不可砍）

| 类别 | 工具 | 为什么必须保留 |
|---|---|---|
| 平台级编排 | Delegate / AgentControl / FlowTrigger / SubTask / Load / FlowReport | 多 agent 编排是 Nebflow 的立身之本；Pi 单用户不需要 |
| 安全边界 | per-agent 白名单、worker leaf 剥夺、fork 剥离 18 副作用工具、Nebula 专享隔离 | 08-14 P0（flow agent 卡死）、08-21 双写事故的防线 |
| 任务状态机 | TaskCreate / TaskUpdate / TaskQuery / Schedule | 可见的任务生命周期与冻结/取消——Pi 用外部 todo 工具替代 |
| 人机交互 | AskUserQuestion / Pop | 产品差异化能力 |
| 可观测 | TaskQuery / FlowReport | 面板数据源 |

### 3.5 哪些「多」是冗余/可收敛（借鉴点）

1. **Edit / MultiEdit 双工具**（17:09 已裁定合并，registry 仍保留两个注册）——Pi 只有一个 `edit`；
2. **网络三工具 WebSearch / WebFetch / Curl** 职责重叠：WebFetch 已内含反爬 fallback 链，Curl 场景可被 WebFetch 覆盖大半；
3. **BaseTools 固定集膨胀风险**：从概念上的「读/写/搜/执行」四类（≈ Pi 的 4 工具）涨到了 7 个——`Issue` 是外部工具晋升固定集的先例，一旦开了口子，Screenshot/CheckIssues 都可能被塞进 BaseTools；
4. **描述/指南膨胀**：Read 工具附了长篇行为指南、Pop 附了完整可视化规范——这些是「工具指南」不是「工具描述」，全部随工具每次注入，持续烧 token；
5. **外部工具与内置并存**：Issue 既是外部 JSON 又在 BaseTools，双轨制让「工具全景」难以一眼看清（前端 builtinToolNames 列 24 个，界面臃肿——呼应你 17:03 的界面反馈）。

---

## 4. 启示（核心）

### 4.1 工具集精简原则：什么样的工具该保留/合并/移除

以 Pi 的「约束即保护」为标准，给每个工具打四问：

| 问题 | 保留 | 合并 | 移除/降级 |
|---|---|---|---|
| 是否每个 turn 都可能用到？ | Read/Write/Edit/Bash/Grep/Glob | — | 低频工具（如 Curl、TaskQuery） |
| 是否与其他工具职责重叠？ | — | Edit↔MultiEdit、WebFetch↔Curl | — |
| 是否平台级必需（安全/编排/可观测）？ | Delegate/AgentControl/FlowTrigger/Mail/SubTask/FlowReport/Task* | — | — |
| 是否描述可被提示/技能替代？ | — | 部分机制工具 → skills | 过度细分的工具收归 Bash + 提示指引 |

**判定标准（建议写进 tool-review 约定）**：新增工具须满足「与现有工具无重叠 且 无法用 skill/提示实现 且 有真实调用频率预期」，否则不进入内置注册表，走外部 JSON 或 skills。

### 4.2 注入策略：BaseTools 最小集 + 按需

Pi 的「默认 4 工具」启示：**固定注入 ≠ 白名单双轨并行**。建议：

- **BaseTools 收敛到核心五件**：Read / Write / Edit / Bash / Grep（Glob 可并入 Grep 或保留其一，见下）——砍掉 `Issue`（改按需：agent.json 显式声明才给，而非无条件注入）；
- **机制层注入保持**（#381 先例方向正确：team→Mail+SubTask 由机制兜底，防人工漏配）——但新增机制注入须过「类别必需」审查，防固定集膨胀；
- **按需注入实验**：对低频工具（TaskQuery/Curl/Schedule）实验「首用注入」——agent 在 prompt 中被告知「需要 X 能力时可请求启用」，命中后再注入工具定义，可显著降首轮上下文。

### 4.3 工具描述质量 vs 数量

Pi 最短 system prompt 的隐含结论：**每个工具的 description + schema 都是每轮调用的固定开销**。Nebula ~28 工具 × 平均 400-800 字描述 ≈ 1-2 万 token 的固定上下文成本——这还没算系统提示里随工具注入的长篇行为指南。

建议：
- 工具描述遵循「当用则用 / 怎么用 / 参数」三段式，砍掉营销性/背景性文字；
- 长行为指南（如 Read 的 live-update 说明、Pop 的可视化规范、Bash 的安全红线）移到**按需文档**（skill 或帮助文件），agent 首用该工具时再读——这恰好是 Pi skills 的定位；
- 对照 Armin 2026-07-04 博文《Better Models: Worse Tools》：新模型在工具调用上反而会退化，工具面越大退化风险越高。

### 4.4 极简默认工具集 + 按任务扩展（可选演进方向）

Pi 的「Adapt pi to your workflows, not the other way around」——给 agent 一个**极简默认工具集**，任务需要时再扩展，而不是一开始就给全量。落地方案：

1. **默认档**（新 agent 建模板）：BaseTools 五件 + AskUserQuestion；
2. **增强档**（agent.json 显式声明）：+WebSearch/WebFetch/Pop/TransferFile 等；
3. **编排档**（Nebula/team lead）：机制层自动 + 全量白名单；
4. 前端「可配置工具列表」（builtinToolNames）按档位分组展示，低频工具折叠——呼应界面去臃肿。

### 4.5 自举能力闭环（我们有基础，缺引导）

Pi 最强的不是 4 个工具，而是「agent 自己写扩展」的闭环。Nebflow 已具备等价基础：
- 外部工具 JSON 定义 + ToolLoader **文件 watcher 热重载**（≈ Pi 的 extension 热重载）；
- skills 机制 + entity-creator 流程（agent 可创建/声明技能）。

缺口是**引导闭环**：没有像 Pi 那样让 agent 知道「你可以自己定义外部工具并热重载」的系统提示。建议在工具指南里加一段「自建工具指引」（写 JSON → 放 tools 目录 → 自动热重载 → 测试），把「往内置 registry 塞工具」的习惯引导为「按 agent 定制外部工具」。

---

## 5. 可直接借鉴清单

| 优先级 | 动作 | 依据 |
|---|---|---|
| P0 | **完成 Edit/MultiEdit 合并落地**：registry 移除 MultiEdit 注册，Edit 支持多编辑参数（含并发串行化），更新 EditTool 描述 | 17:09 已裁定；Pi 只有一个 edit |
| P1 | **BaseTools 瘦身**：Issue 移出固定注入集，改 agent.json 显式声明；BaseTools 收敛到 Read/Write/Edit/Bash/Grep/Glob | Pi 默认 4 工具；固定集膨胀风险 |
| P1 | **网络工具收敛评估**：WebFetch vs Curl 职责重叠分析，Curl 降为按需（不进默认白名单） | 工具四问法 |
| P2 | **工具描述 token 审计**：全量过一遍内置工具 description，长指南（Read/Pop/Bash）抽到按需文档 | 每轮固定开销；Better Models: Worse Tools |
| P2 | **按需注入实验**：低频工具（TaskQuery/Curl/Schedule）首用注入试点，量化首轮上下文降幅 | Pi 按需 skill 模式 |
| P2 | **自建工具引导**：系统提示加「外部工具自建 + 热重载」指引 | Pi 自举闭环 |
| P3 | **前端工具列表按档位分组**：默认/增强/编排三档展示，低频折叠 | 17:03 界面臃肿反馈 |

---

## 6. 不适用/边界说明

- Pi 不做 MCP/sub-agents/plan mode 是其单用户定位使然；Nebflow 的编排工具是平台必需，**不因本次调研砍任何编排/安全工具**；
- Pi 的「最短 system prompt」部分依赖单会话交互；Nebflow 多 agent + 记忆 + 团队注入的结构性信息无法也不应砍；
- 工具数量不是唯一变量：Pi 的稳定还来自工程质量（作者对可靠性的偏执）。我们的优先级是**消除冗余**而非追求最小数。

---

## 附：信息来源

1. **Armin Ronacher《Pi: The Minimal Agent Within OpenClaw》**（2026-01-31，lucumr.pocoo.org 博客原文）——一手设计哲学
2. **Pi coding-agent README**（earendil-works/pi main 分支）——4 工具默认、扩展系统、skips sub-agents/plan mode
3. **《pi 的设计艺术》大纲**（ZhangHanDong/pi-book，分析 pi-mono v0.66.0）——第六篇「工具设计·约束即保护」、第九篇「极简核心·能力外置」
4. **Nebflow 源码**：`src/main/scala/nebflow/core/tools/registry.scala`、`AgentCore.scala`（BaseTools/fixedToolsFor/buildAllowedToolSet）、`ToolLoader.scala`、`McpManager.scala`、`~/.nebflow/agents/*/agent.json`
5. 佐证：Armin《Better Models: Worse Tools》（2026-07-04）
