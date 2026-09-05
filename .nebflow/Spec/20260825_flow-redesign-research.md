# 动态 Flow 体系重设计 v4——取代架构版（调研 + 方案）

> 需求提出：用户 2026-08-25（flow 变为 agent 临时写的一次性 flow；Team 成员与 Nebula 默认具备 flow 能力；64 页 PPT → 64 并行 worker + 64 并行 QA 的典型用例）
> **v2 定位修正（用户 2026-08-25 15:03 三点裁定）**：① 动态 flow **取代**现有 flow 架构（非增量补充）；② entity-creator **转 skill**，以后 flow 调用统一走动态 flow；③ 新工具是**执行语义**（FlowExecute，非 FlowCreate）。
> **v3 修正（用户 2026-08-25 15:12 两点裁定）**：① **不限制并发**——删除成本护栏三件套中的「并发限流（Semaphore 分波）」与「全局硬上限（maxFanoutCap）」，保留「per-node preset 节点配置模型」；② **flow 流程由 agent 根据任务动态创建，无固定模板**——全文「动态模板」「模板化」概念修正为「skill 指导 + agent 动态创建」，skill 只提供指导原则/最佳实践，不是可复用模板。
> **v4 修正（用户 2026-08-25 15:1x 裁定）**：**UI 形态**——flow 在新架构下**没有常驻 UI 入口**：去掉侧边栏（flow 列表/入口）、去掉常驻标签页（flow 管理面板）；flow 只在**运行时**才在标签页**弹出**显示——FlowExecute 触发 → 动态弹出标签页展示运行状态（节点进度/并行 worker/聚合结果）；运行结束（verdict/完成/失败）后关闭或可手动关闭；**无 flow 管理视图——flow 不可见除非运行中**。新增 §0.3 v3→v4 偏差对照、§4.10 UI 形态（v4 裁定）；阶段 1 增加运行时弹出落地、阶段 5 增加常驻 UI 移除；验收补运行时弹出断言。
> 本文为调研 + 设计分析，不触碰产品代码。代码证据来自当前主仓 `/Users/dev/Claude code/Nebflow`（archive/scala 分支，v1.4.1-beta.51）。
> 状态：draft v4.0 · 待用户/Manager 确认后派发实施
> 配套：`/tmp/flow-redesign-arch.svg`（64+64 编排架构图）
> 版本日志：v1.0（a1a7fe2，增量定位）→ v2.0（取代定位，按 08-25 15:03 用户裁定重写）→ v3.0（按 08-25 15:12 用户两点修正：删并发限流/全局硬上限、flow 由 agent 动态创建无固定模板）→ v4.0（本版，按 08-25 15:1x 用户裁定：**UI 形态**——flow 去掉侧边栏与常驻标签页，只在运行时在标签页弹出显示；新增 §0.3 / §4.10，阶段与验收同步更新）

---

## 0. 人类可读摘要（TL;DR）

**你说了什么**：flow 不应该是写死的长久流水线，而应该让 agent 临时写一个、用一次就扔——像点外卖一样，用完即弃。Team 成员和 Nebula 默认就该有这能力（不用在 agent.json 里手动声明）。典型场景：做 64 页 PPT，不是整份 deck 一个人慢慢做，而是 **64 个 worker 一人做一页 + 64 个 QA 一人审一页**，全并行。

**08-25 15:03 你又定了三点（v2 的根）**：
1. 动态 flow 是**取代**——不是给现有 flow 体系「加一个补充通道」，而是**新的 flow 架构**本身。现有的预定义 flow（`~/.nebflow/flows/` 目录 + FlowTrigger 白名单触发 + 预定义 DAG 流水线）逐条迁移，最终退役。
2. entity-creator（创建 agent/team/flow 的评审流水线）**转为 skill**——以后创建实体不走 flow DAG，由 agent 按 skill 指导直接做；所有 flow 调用统一走动态 flow。
3. 新工具不叫 FlowCreate——语义是**执行**不是创建。暂定 **FlowExecute**（备选 RunFlow / ExecuteFlow / flow-execute，命名最终由你拍板，本方案 §4.2 给推荐 + 理由）。
4. **UI 形态（08-25 15:1x，v4 的根）**：flow 没有常驻 UI——去掉侧边栏（flow 列表/入口）、去掉常驻标签页（flow 管理面板）；只在 flow 运行时，在标签页**弹出**显示运行状态（节点进度/并行 worker/聚合结果），运行结束（verdict/完成/失败）后关闭或可手动关闭。**flow 不可见除非运行中**（§4.10）。

**现状是什么**：Nebflow 现在的 flow 是**预定义文件**（`~/.nebflow/flows/<name>/flow.json`），agent 通过 FlowTrigger 工具触发。执行引擎（FlowDagExecutor）已经支持**动态并行展开**（fanout）：一个节点输出一个数组，引擎自动实例化 N 个并行节点、每个节点拿到数组里的一项、跑完自动汇聚。也就是说「64 个并行 worker」的**引擎底座已经有了**，缺的是：

1. **「agent 自己写 flow」的入口**——现在 flow 必须是磁盘上的文件，agent 不能临时造一个；
2. **默认能力注入**——现在 agent 要在 agent.json 里声明 flows 白名单才有 FlowTrigger，Team 成员默认没有；
3. **「每页 worker → 每页 QA」的两级并行**——现在的 fanout 只支持一级（worker 并行 → 汇聚 → 审），QA 要复用同一份 64 页清单再展开一轮（引擎需要小扩展）；
4. **成本控制**——64 个 agent 同时跑很烧钱；**按节点选模型**（worker 低成本 / QA 强模型）是用户认可的配置模型（「节点配置模型是对的」）；**不做并发上限与限流**（用户 08-25 15:12 裁定，与 #395 provider 限流全部去掉理念一致：信任 agent 动态创建流程）。
5. **前端 UI 形态**（v4 裁定）——现在 flow 有常驻入口（侧边栏团队卡片 Flows 区 + Flows 管理标签页）；新架构下这些全部去掉，只剩「运行时弹出标签页」（§4.10）——flow 不可见除非运行中。

**开源怎么做的**（调研 4 个代表性方案，v2 保留）：
- **Anthropic 研究系统**（最接近你要的模式）：lead agent 根据查询复杂度**动态创建任意数量**的并行 subagent，各自独立探索，结果压缩后回传 lead 汇总，最后有个专门的引用核验 agent。关键教训：subagent 数量按任务复杂度定规则（简单任务 1 个、复杂任务 10+ 个），防止 agent 乱开 50 个 worker。
- **LangGraph**：`Send` API 就是「动态 fanout + 每分支独立状态 + 归约聚合」，和 Nebflow 的 ParallelDynamic 同构；每个并行分支还可以是完整的子图（worker→QA→重做 可以写进一个分支内部）。
- **Magentic-One（微软 AutoGen）**：Orchestrator 用「任务台账」跟踪每个子任务，失败就**重新计划**而不是硬重试；不同 agent 用不同模型省钱（主力模型做规划，便宜模型干活）。
- **Temporal**：工作流引擎，child workflow 可以无限并行，靠**事件历史持久化**保证崩溃后从断点续跑，worker 池控制并发来控成本。

**v2 方案**：新增 **FlowExecute 工具**（agent 直接传一份 flow 的 DAG JSON，引擎校验后立即执行，用完即弃、不落盘污染 flows/ 目录）；按 #381 的先例，在工具注入层给 Team 成员和 Nebula **默认加上**这个能力；64+64 场景用**两级 fanout 共享同一份页清单**（worker 展开 64 个 → QA 再展开 64 个，QA 只审自己那页），再加每节点可选模型 + 失败页重做回路（**不限制并发**——用户 08-25 15:12 裁定：不做并发限流与全局上限）。**与 v1 的根本区别：动态 flow 是新的 flow 架构本体**——存量 9 个预定义 flow 逐条迁移（entity-creator → skill，其余 → **skill 指导 + agent 动态创建**，v3 修订），`flows/` 目录与 FlowTrigger 分阶段退役。

**分 5 阶段（取代路径，不是增量路径）**：① FlowExecute 工具 + 机制层注入 + **前端运行时弹出标签页**（含冒烟测试）→ ② 大规模并行能力（跨节点引用 + 失败重做 + 节点配置）→ ③ 存量 flow 逐个迁移 → ④ entity-creator skill 化 → ⑤ FlowTrigger/flows/ 退役 + **常驻 UI 移除**（flow 收敛为仅运行时弹出）。

---

## 0.1 v1 → v2 偏差对照（用户 08-25 15:03 裁定逐条落位）

| 维度 | v1（draft v1.0，a1a7fe2） | 用户裁定 | v2 修正 |
|---|---|---|---|
| 定位 | 动态 flow 是**增量补充**；预定义 flow「全部保留」 | 「动态 Flow，dynamic flow，我的目的是**取代目前的 flow 架构**」 | v2 全文按**取代架构**写：预定义 flow 是过渡态，逐条迁移后退役 |
| entity-creator | 保留为正式流水线（「价值独立于一次性 flow」） | 「把 entity creator 这种**转换为 skill**」；「以后调用 flow 都使用 dynamic flow」 | §4.7 entity-creator → skill 完整设计；§4.8 全部 flow 转 **skill 指导 + agent 动态创建**（v3 修订，见 §0.2）；flow 调用统一走动态 flow |
| 工具命名 | FlowCreate（**创建**语义） | 「工具名不应该叫 flow create 吧？应该叫 **flow 执行**」 | §4.2 FlowExecute（**执行**语义，创建隐含在定义中）；备选 RunFlow / ExecuteFlow / flow-execute，待用户拍板 |
| 演进路径 | 阶段 4 可选「一次性 flow 固化为预定义 flow」 | 方向反转：预定义体系整体退役 | §5 分 5 阶段取代；固化路径删除，改为「skill 指导 + 动态创建」（v3 修订） |

## 0.2 v2 → v3 偏差对照（用户 08-25 15:12 两点裁定逐条落位）

| 维度 | v2（draft v2.0，d9e6ff4） | 用户裁定 | v3 修正 |
|---|---|---|---|
| 并发限流 | §4.5 新增 Semaphore 分波（`FlowDagDef.concurrency`，仿 Temporal worker 池，64 页分 8 波） | 「不要限制并发和限流」 | **删除**：并发不做限制；`concurrency` 字段、Semaphore 分波、阶段 2 限流断言全部移除（§4.5 重写、§5 阶段 2、§6 清单） |
| 全局硬上限 | §4.5 新增 `flow.maxFanoutCap`（默认 128），`N = min(len, maxFanout, cap)` | 同上（不限制并发） | **删除**：无全局天花板；`maxFanout` 保留为 **flow 声明的并行规模**（agent 按任务定，非引擎限制）；成本提示仅写入 FlowExecute description（提示性，非强制） |
| per-node preset | §4.5 保留（worker 低成本 / QA 强模型） | 「节点配置模型是对的」 | **保留**：`FlowNode.preset` 扩展照旧（§4.5），成本控制只剩模型分级 + 提示词级预算 |
| 动态模板概念 | §4.8「其余 8 个 flow → 动态 flow 模板」；迁移路径表「迁移后形态 = 动态模板」 | 「flow 的流程是由 agent 根据任务动态创建的，当然 skill 里会有一定指导」 | **修正**：flow 每次由 agent 根据任务动态创建，**无固定模板**；skill 只提供指导原则/最佳实践（64 页两级 fanout 范例、entity-creator 创建指导）；§4.8 迁移路径表「动态模板」→「转 skill 指导 + agent 动态创建」；「模板载体」→「指导载体」 |
| flow-execute skill | §2/§4.4 列为「可选补充（opt-in，不阻塞）」 | 「skill 里会有一定指导」 | **升格**：flow-execute skill 是动态创建的**指导载体**（DSL 语法 + 编排原则 + 范例），不阻塞阶段 1（阶段 1 靠 FlowExecute 自文档）；§2、§4.4 相应更新 |

## 0.3 v3 → v4 偏差对照（用户 08-25 15:1x UI 形态裁定逐条落位）

| 维度 | v3（draft v3.0，8750c8b） | 用户裁定 | v4 修正 |
|---|---|---|---|
| 侧边栏 flow 列表/入口 | 团队卡片内 Flows 区（flowTeams.js：flow 行 + 内联 DAG + 展开） | 「去掉侧边栏」 | **移除**：团队卡片不再渲染 flow 列表/入口；运行中的 flow 也不出现在侧边栏——只在弹出标签页 |
| 常驻标签页（flow 管理面板） | Flows 标签页（flowCanvas.js `openFlows`/`renderFlowsTab` + flowList.js：flow 定义列表 / View DAG / 管理） | 「去掉标签页」 | **移除**：无 flows-btn、无 flow 定义列表、无管理面板——flow 定义只在 FlowExecute 调用瞬间存在，无持久身份可管理 |
| 运行时弹出标签页 | flow-run 标签页（P5 已有：`flowStarted` → `maybeAutoOpenFlowsTab` → `openFlowRunTab`，solar-system 视图 + 节点状态） | 「只在 flow 运行时，在标签页弹出显示」 | **升格为唯一 flow UI**：保留现有弹出机制，补「结束关闭」逻辑（flowCompleted/flowFailed → 自动关闭或停留结束态可手动关闭）；动态 flow 的全部可见性 = 此标签页 |
| 阶段划分 | 阶段 1-2 无前端改动（「无需新事件，动态实例照常渲染」） | （同上） | 阶段 1 增加「运行时弹出标签页」落地；阶段 3 团队卡片 Flows 区随各 flow 迁移移除；阶段 5 常驻 UI（侧边栏 Flows 区 + Flows 管理标签页）彻底移除；§4.9 前端条目同步修正 |
| 验收条件 | 无运行时弹出断言 | （同上） | 阶段 1 补「触发 → 弹出 → 节点进度 → 结束关闭」断言；阶段 5 补「无常驻入口」断言 |

---

## 0.4 v4 → v5 偏差对照（用户 08-26 00:09 互补裁定逐条落位）

> **v5 定位（本文档正文保留 v4 取代架构版作为版本轨迹，v5 以 `20260826_flow-complement-design.md` 为准）**：用户 08-26 00:09 修正「之前说用 dynamic flow 替代目前的 flow，但我觉得这两种是**互补**的。需要重新出方案」——动态 flow 与预定义 flow 不是取代关系，是**互补关系**；并新增「运行中 flow 进展可重入查看」设计（核心缺口，阶段 1）。

| 维度 | v4（draft v4.0，6e1977b） | 用户裁定（08-26 00:09） | v5 修正（互补） |
|---|---|---|---|
| 定位 | 动态 flow **取代**预定义 flow（v2 起：存量 9 个预定义 flow 逐条迁移 → 最终退役） | 「dynamic flow 与预定义 flow 是互补的」 | **迁移/退役路径作废**：预定义 flow 保留为正式能力，不迁移、不退役（§3 评估：8 个留预定义、1 个转 skill） |
| 阶段 3-5 | 存量 flow 逐个迁移 → 退役（转 skill 指导 + 动态创建）；FlowTrigger/flows/ 分阶段退役 | （同上） | **全部作废**：`~/.nebflow/flows/` 9 个 flow.json 全保留，FlowTrigger 白名单全保留（§4.1 工具分界） |
| 常驻 UI（阶段 5） | 侧边栏 flow 列表/入口 + Flows 管理标签页移除（「flow 不可见除非运行中」） | （同上） | **保留**：预定义 flow 有持久身份（flow.json 文件），Flows 管理标签页 + 团队卡片 Flows 区不移除；dynamic flow 仍无常驻管理入口（定义即执行，无持久身份可管理） |
| entity-creator | 转 skill（08-25 15:03 裁定） | （同上） | **裁定维持**：互补框架下仍成立——architect→builder→reviewer 循环的本质是行为纪律（§2.4），单一 agent 按 skill 步骤承担全部角色，非引擎编排需求 |
| 运行中 flow 可见性 | 「flow 不可见除非运行中」：flow-run 标签页关闭后（dismissedFlowRuns）永不自动重开、无手动重开入口 | 「dynamic flow 的标签一旦关闭，还无法进入 flow 进展查看了」 | **部分修正——新增运行中指示器**：聊天窗口顶部「运行中 flows」badge + 下拉列表（参考 Sub-Agents 面板），点击行重新打开 flow-run 标签页；关闭不自动重开（v4 裁定保留），但**随时可手动重开**；dismissed 标记改 sessionStorage 持久化（刷新不复活，与语义一致）；后端 registerFlow 补 sessionId（§5） |

**v5 两阶段**：① 进展重入（前端为主 + 最小后端）→ ② 互补边界落地（文档 + skill + 工具描述修订，即本 §0.4 与 flow-execute SKILL.md §1、FlowExecute/FlowTrigger 工具描述）。阶段 2 验收见方案 §6。

---

## 1. 开源方案调研（v2 保留——结论仍适用）

### 1.1 Anthropic 多智能体研究系统（orchestrator-worker，动态创建 subagent）

**来源**：[How we built our multi-agent research system](https://www.anthropic.com/engineering/built-multi-agent-research-system)（2025-06-13，工程博客）

**架构**：用户查询 → LeadResearcher（lead agent）分析任务、制定策略，**用工具动态创建任意数量的 subagent**（图中 2 个，实际数量由任务决定）→ 各 subagent 独立并行搜索（各自独立的 context window）→ 结果返回 lead → lead 综合、判断是否需要更多研究（可再建新 subagent 或调整策略）→ 满足后交给专门的 **CitationAgent**（引用核验）→ 交付用户。

**与「agent 临时写一次性 flow」直接相关的点**：

| 维度 | Anthropic 做法 | 对我们的启发 |
|---|---|---|
| 动态 agent 实例化 | lead 通过工具调用创建 subagent，**数量运行时决定**（不写死）；subagent 各有独立 context window，互不干扰 | 对应 Nebflow 的 ParallelDynamic 按 slot 数组长度展开——底座已具备 |
| 生命周期 | subagent 任务完成即结束，结果**压缩后**回传（progressive summarization），不留常驻 | 一次性 flow 即用即弃语义一致；聚合时「压缩再合并」值得借鉴到 aggregator 提示词 |
| 失败/失控 | 早期失败模式：简单查询开 50 个 subagent、无尽搜索、互相干扰——**用提示词规则治理** | 并发数上限不能只靠引擎 clamp，还要提示词级「按复杂度定路数」规则（scaling rules） |
| 成本 | 实测 multi-agent 系统 token 消耗约为 chat 的 **15 倍**；token 用量解释性能差异的 80% | 64+64 并行成本设计：**模型分级 + 每 worker token 预算**（不做并发上限/限流——用户 08-25 15:12 裁定；成本靠节点配置模型 + 提示词级预算） |
| 可靠性 | 长流程需要**持久化 + 断点续跑**（不能从头重来）；LLM-as-judge 评估质量 | Nebflow 有 RunningFlowRegistry + crash resume；质量回路靠独立 QA 角色 |

**成本规则原文**（scaling rules 思路）：简单事实查询 → 1 个 agent + 3-10 次工具调用；直接对比 → 2-4 个 subagent + 各 10-15 次调用；复杂研究 → 10+ subagent 明确分工。**「按任务复杂度给并发数定档」应写进 planner/worker 提示词**。

### 1.2 LangGraph（Send API：动态 fanout + 归约聚合 + 断点）

**来源**：[LangGraph 官方文档](https://docs.langchain.com/oss/python/langgraph/)（`Send` API / map-reduce 模式；原 how-to 页 `langchain-ai.github.io/langgraph/how-tos/map-reduce/` 已随文档迁移）

**核心机制**：
- **`Send(node, state)`**：一个节点返回多个 Send 对象 → 每个 Send 以**独立 state** 并行启动目标节点的 N 次执行（map 阶段）——与 Nebflow `ParallelDynamic` 的「slot 数组 → N 个实例」**同构**，区别在 LangGraph 每次分支带完整 state，Nebflow 用 `{{item}}/{{index}}` 占位符注入。
- **归约（reduce）**：各分支结果经 state channel 的 reducer（如 `Annotated[list, operator.add]`）自动聚合，最后归约节点处理。
- **Checkpointer**：每个 super-step 持久化，崩溃/中断后从**最后一个检查点恢复**，不是从头重跑。
- **子图（subgraph）**：每个 Send 分支可以是完整子图——「worker→QA→条件重做」可封装成一个分支内部循环。
- **Retry policy**：节点级指数退避重试。

**对我们的启发**：
- 「每页 worker + 每页 QA」在 LangGraph 里 = **两次 Send 通行**（先 Send worker per page，再 Send QA per page），或每个 Send 分支内嵌 worker→QA 子图。Nebflow 用两级 ParallelDynamic 表达（§4.3），等价。
- checkpointer 思路：Nebflow 的 turn 级 crash resume 已覆盖 agent 层；flow 层断点续跑可作 backlog（现有 `restoreInterruptedTurns` 是团队会话恢复，flow 一次性执行无持久化——可接受，因为一次性 flow 失败重跑成本低）。

### 1.3 Magentic-One（AutoGen：Orchestrator + 任务台账 + 重计划恢复）

**来源**：[arXiv:2411.04468](https://arxiv.org/abs/2411.04468)（2024-11）+ [微软官方博客](https://www.microsoft.com/en-us/research/blog/magentic-one-a-generalist-multi-agent-system-for-solving-complex-tasks/)

**架构**：Orchestrator（lead）双循环：
- **外循环**：维护 **Task Ledger**（facts / educated guesses / plan）。发现进展停滞（Progress Ledger 连续若干步无进展）→ **更新 Task Ledger、重新制定计划**（re-plan），而不是盲目重试。
- **内循环**：维护 **Progress Ledger**（当前进度 / 子任务分配）。逐步自省检查任务是否完成，未完成则给 4 个专业 agent（WebSurfer/FileSurfer/Coder/ComputerTerminal）之一分配子任务。
- **模块化**：agents 即插即用，加减 agent 不需改核心。
- **成本**：**异构模型**——Orchestrator 用强推理模型（GPT-4o / o1），其他 agent 可用更便宜的模型；「不同 agent 承担不同成本档」是显式设计。

**对我们的启发**：
- **失败处理 = 台账 + 重计划**，比「重试 N 次」更接近「失败页重做回路」：QA 判 fail 的页 = Progress Ledger 里的失败子任务 → 重新分配给 worker（重做回路）。Nebflow 阶段 2 的「filtered item 源」就是这个语义的 DAG 化。
- **模型分级**：worker（机械性做页）用低成本模型、QA/aggregator 用强模型——Nebflow 已有 preset 机制（`model-presets.json` + `PresetResolver` + Delegate/SubTask 的 `preset` 参数），**给 FlowNode 加 per-node preset 是低成本高收益扩展**（§4.5）。

### 1.4 Temporal（持久化工作流：child workflow 无限并行 + 可靠失败处理）

**来源**：[Temporal Docs — Child Workflows (Java SDK)](https://docs.temporal.io/develop/java/child-workflows)

**核心机制**：
- **Child Workflow Execution**：父 workflow 用 `Async.function` 启动任意多个 child workflow（各自独立的 workflow 定义与状态），返回 Promise 并行等待——**fanout 上限只受 worker 池容量约束**。
- **持久化**：StartChildWorkflowExecutionInitiated/Started/Completed 事件全部写入 Event History——崩溃后从事件历史**确定性重放**，无需手动断点。
- **Parent Close Policy**：父关闭时 child 的处置（默认 TERMINATE 终止，可 ABANDON 放任）。
- **重试**：内置 retry policy（指数退避）。
- **成本控制**：**worker 池并发上限**（同时运行的 workflow 数受 worker 数约束，任务排队），而不是一次性全开。

**对我们的启发**：
- 「64 个并行 + 排队」是 Temporal 的机制（worker 池限并发，其余排队）。**Nebflow 不采用**（用户 08-25 15:12 裁定：不限制并发与限流）——现状一次性 fork 全部 N 个 fiber 保留；Temporal 的 worker 池排队仅作参考，不做 Semaphore 分波。成本改由节点配置模型（per-node preset）+ 提示词级预算控制（§4.5）。
- 失败处理：child 独立失败不拖垮父（Promise 可分别处理）——对应 Nebflow `onFail=collect` 的占位 + barrier 结算语义。

### 1.5 横向对比表（含未深挖的补充方案）

| 方案 | 动态创建 agent | 生命周期 | 每任务独立 agent/QA | 聚合 | 失败处理 | 成本控制 | 与本项目最相关的点 |
|---|---|---|---|---|---|---|---|
| **Anthropic Research** | ✅ lead 工具调用动态创建 | 任务完成即回收；长流程断点续跑 | 每方向独立 subagent；**单点** CitationAgent 核验（非每任务 QA） | lead 综合 + progressive summarization | 提示词治理失控；LLM-judge 评估 | **15× token 警示 + scaling rules** | 「agent 临时写一次性 worker 池」的原型 |
| **LangGraph** | ✅ Send API 按列表动态 fanout | checkpointer 断点恢复 | 每分支独立 state；**分支内可嵌套 worker→QA 子图** | state reducer 归约 | retry_policy + 条件边重做 | 无内置，靠应用层 | 两级 fanout + 分支内 QA 的 DAG 表达 |
| **Magentic-One (AutoGen)** | Orchestrator 动态分配子任务 | Orchestrator 双循环台账 | 子任务独立分配；Orchestrator 逐项跟踪 | Task Ledger + Progress Ledger | **重计划**（非硬重试） | **异构模型分级** | 失败页重做 = 台账里失败子任务重新分配 |
| **Temporal** | ✅ child workflow 任意数量 | 事件历史持久化 + 确定性重放 | 每个 child 独立 workflow | Promise 并行等待 | 内置 retry + Parent Close Policy | **worker 池并发上限**（排队） | 可靠失败语义（retry/重放）；worker 池并发机制**本项目不采用**（用户裁定不限制并发） |
| AutoGen Swarm | 运行时 handoff 切换 | 无显式生命周期（轻量） | 会话式，非池化 | 无内建 | 无 | 无 | 不适用（无 DAG） |
| CrewAI | 静态 crew（角色固定） | crew 一次 kickoff | 任务级并行但角色静态 | 任务输出拼接 | 任务级错误处理 | 无强护栏 | 角色静态 vs 我们每页独立实例 |
| n8n | 节点级批量（Split/Batch） | 工作流执行即弃 | 循环节点内批量 | Merge 节点 | Error Workflow + retry | 执行限额 | 「每项独立处理 + 独立错误流」概念近似 |
| Dify | 迭代节点（顺序为主，并行受限） | 工作流执行即弃 | 并行分支数写死 | 变量聚合 | 单节点重试 | token 计费 | 并行能力弱于前四者 |
| OpenAI Swarm | 无（手写循环） | 无 | 无 | 无 | 无 | 无 | 参考意义最低 |

### 1.6 调研结论（回答任务重点）

1. **动态 agent 实例化与生命周期**：主流一致做法是「运行时按数据量/复杂度决定 N，任务完成即回收」。Anthropic 与 LangGraph 都强调 N 由上游决定 + 独立 context window。Nebflow 的 ParallelDynamic（slot 数组 → N 实例 → barrier 汇聚）**已覆盖**；缺口是「agent 创建 flow 的入口」与「默认注入」。
2. **每任务独立 agent（含独立 QA）**：LangGraph 的「分支内嵌 worker→QA 子图」与「两级 Send」是两种标准表达；Magentic-One 用台账逐子任务跟踪。**Nebflow 用两级 ParallelDynamic 共享 item 源**（§4.3）实现「每页 worker + 每页 QA」，需要引擎小扩展（跨节点 slot 引用）。
3. **结果聚合与失败处理**：聚合 = 归约/台账/综合，Nebflow barrier join + `$all` 聚合已具备；失败 = Anthropic 提示词治理、Magentic-One 重计划、LangGraph 条件边重做、Temporal 重试。**Nebflow 需要的是「失败页定向重做回路」**（按 QA verdict 过滤的 item 源，§4.6）。
4. **token/成本控制**（v3 修订）：**模型分级**（Magentic-One 异构模型，Nebflow preset 机制可扩展 per-node——用户认可「节点配置模型是对的」）+ **提示词级预算**（Anthropic scaling rules）。**不做并发上限与限流**（用户 08-25 15:12 裁定：不限制并发；信任 agent 按任务定规模，与 #395 理念一致）。缺 per-node preset（§4.5）。

---

## 2. 指导 skill 检查结果（v2 更新）

按任务要求检查 `~/.nebflow/skills/` 与 `~/.nebflow/flows/` 下是否有 entity-creator / flow-creator 相关定义：

| 检查项 | 结果 | 说明 |
|---|---|---|
| `~/.nebflow/skills/entity-creator` | **不存在** | 现无「创建实体」的 skill——v2 的目标产物（§4.7） |
| `~/.nebflow/skills/flow-creator` | **不存在** | skills/ 目录 20 个 skill（skill-creator、guizang-ppt-skill、academic-survey 等），无 flow-creator |
| `~/.nebflow/skills/skill-creator` | 存在 | 它是「创建 skill」的指导，不是「创建 agent/team/flow」的；skill 结构规范（frontmatter/audience/订阅）可直接套用 |
| `~/.nebflow/flows/entity-creator/flow.json` | **存在** | architect → (agent/team/flow)-builder → reviewer 的评审循环（maxLoop=3, strictVerdict）；配套 `agents/` 7 个专用角色（architect / 3×builder / 3×reviewer） |
| `~/.nebflow/flows/GUIDE.md` | 存在 | 「Flow Design Guide (v2)」——预定义 flow 的设计检查清单；其「What deserves a Flow」决策树在 v2 下语义反转（不再有「固化 flow.json」这一步，见 §4.8 注） |

**结论**：没有现成的「flow creator skill」可作为指导。当前创建 flow 的路径 = 走 entity-creator flow（设计评审流水线）→ 产出 `~/.nebflow/flows/<name>/flow.json` 文件。v2 下这条路径**整体消失**：创建实体走 entity-creator skill（§4.7），执行 flow 走 FlowExecute 动态定义（§4.2）。

**设计回应**：动态 flow 的「指导」分两层承载（用户 08-25 15:12：「flow 的流程是由 agent 根据任务动态创建的，当然 skill 里会有一定指导」）：
1. **机制层默认指导**：内嵌到新工具 **FlowExecute 的 description**（工具自文档——DSL 语法、节点字段、占位符、聚合语法、限制、编排原则与范例全部写进工具描述，agent 调用时即见即用），不依赖 agent.json 声明 skill。
2. **skill 指导载体**：建一个 `flow-execute` skill 承载 DSL 语法、**编排最佳实践与参考范例**（如 64 页两级 fanout 范例、release 五步编排）——skill 提供「怎么做」的指导，**不是可复用模板**（flow 每次由 agent 根据任务动态创建）。不阻塞阶段 1（阶段 1 靠 FlowExecute 自文档即可）。
`flows/GUIDE.md` 的检查清单内容迁移进 FlowExecute description / flow-execute skill，随 flows/ 退役。

---

## 3. 现状分析（已具备什么）

### 3.1 执行引擎（实际状态澄清）

用户描述「FlowTreeActor 是唯一执行引擎」——按当前代码，职责已细分：

| 组件 | 职责 | 文件 |
|---|---|---|
| **FlowTreeActor** | 团队挂载/热加载/取消/崩溃恢复（注释明确「Flow DAG execution is handled entirely by FlowDagExecutor/FlowDagRunner」） | `core/flow/FlowTreeActor.scala` |
| **FlowDagExecutor** | **DAG 执行引擎**：walk/route/advance/barrier/fanout/merge | `core/entity/FlowDagExecutor.scala` |
| **FlowDagRunner** | one-shot 执行 actor：执行完经 ImmediateInput 回传结果、self-stop | `core/flow/FlowDagRunner.scala` |
| **FlowTriggerTool** | 触发入口：白名单 + params 校验 → spawn FlowDagRunner | `core/tools/FlowTriggerTool.scala` |

**v2 关注**：FlowDagExecutor（引擎）与 FlowDagRunner（one-shot 执行）是动态 flow 的**复用核心**，原样保留；FlowTriggerTool（白名单触发预定义 flow）与 FlowTreeActor 的 flow 挂载分支是**退役对象**（§4.8/§5）。

### 3.2 fanout 能力（commit 8520810f + e043ca20 已合入主线）

`FlowDagExecutor.parallelDispatchDynamic`（FlowDagExecutor.scala:644-800）已实现：

- **ParallelDynamic 路由**：`onComplete.parallel` 为 object（`{slots, template, onFail}`）→ 读 owner 节点的 array slot → **N = min(len, maxFanout)**（clamp + warn；v3 语义：maxFanout 是 **flow 声明的并行规模**（agent 按任务复杂度定，64 页用例声明 64），不是引擎/全局限制——无全局硬上限）→ 以 template 节点实例化 **`template#1 … template#N`**（flow.json 中声明的普通节点作模板）。
- **占位符运行时实例化**：input 模板支持 `{{item}}`（数组元素）/ `{{index}}`（1-based 序号）替换（FlowDagExecutor.scala:724-732）。
- **instances 注册表**：`st.instances: Ref[IO, Map[String, List[String]]]`（模板 → 实例 ID 列表，FlowDagExecutor.scala:105）。
- **$all 聚合**：`$template.all.output`（按 index 升序拼接，带 `=== Track N ===` 分隔头）、`$template.all.slots.<field>`（FlowDagExecutor.scala:195-223）。
- **barrier join**：动态 fan 的模板下游 join 在派发时按 N 武装计数，最后到达者激活（FlowDagExecutor.scala:773-786；FlowStructure.dynamicJoinNodes）。
- **onFail 双模式**：`abort`（fail-fast，穿透兄弟分支）/ `collect`（失败分支写占位输出、结算 barrier 继续）（FlowDagExecutor.scala:657-692）。
- **FlowTrigger params**：flow.json 顶层 `params` schema（type/default/min/max）+ FlowTriggerTool 校验合并 + `$params.<key>` 注入（FlowTriggerTool.scala:84-124；FlowDagExecutor.scala:180-190）。
- **运行态**：RunningFlowRegistry（instanceId 维度）+ WS 事件 `flowStarted` / `flowProgress` / `flowNodesAdded`（动态实例）/ `flowCompleted`——前端已能渲染动态展开的节点。

### 3.3 与「动态 flow 取代架构」的差距（v2 版）

| 能力 | 现状 | 差距 | v2 处置 |
|---|---|---|---|
| 按数组动态展开并行 | ✅ 已实现 | — | 保留（引擎核心） |
| **agent 临时创建并执行 flow** | ❌ flow 必须是 `flows/<name>/flow.json` 磁盘文件 | 需要 FlowExecute 工具（§4.2） | 阶段 1 |
| **默认能力注入** | ❌ FlowTrigger 靠 agent.json `flows` 白名单，Team 成员默认无 | 机制层注入 FlowExecute（§4.4）；白名单随 FlowTrigger 退役 | 阶段 1 / 5 |
| **两级 fanout（worker→QA 共享 item 源）** | ⚠️ 单级已支持；QA 级 fanout 需复用上游 slot（当前 ParallelDynamic 只读 owner 自己的 slot） | 跨节点 slot 引用扩展（§4.3） | 阶段 2 |
| 失败页定向重做 | ❌ 只有整体 retry/abort/collect | filtered item 源 + 重做回路（§4.6） | 阶段 2 |
| 每节点模型分级 | ⚠️ 全局 preset 机制已有；flow 节点级无 | FlowNode 加 preset 字段（§4.5）——用户认可「节点配置模型是对的」 | 阶段 2 |
| ~~并发限流~~ | ~~❌ 一次性 fork 全部 N 个 fiber~~ | ~~Semaphore 分波（§4.5）~~ | ~~阶段 2~~ **删除**（v3：不限制并发） |
| ~~成本硬上限~~ | ~~⚠️ flow.maxFanout（agent 自写可写 10000）~~ | ~~全局硬上限兜底（§4.5）~~ | ~~阶段 2~~ **删除**（v3：无全局上限；maxFanout 为 flow 声明的并行规模） |
| **预定义 flow 体系（flows/ + FlowTrigger + flow catalog）** | ✅ 现状架构本体 | **取代**：动态 flow 成为唯一 flow 形态（§4.8/§5） | 阶段 3-5 |
| **前端 flow 常驻 UI（侧边栏 flow 列表 + Flows 管理标签页）** | ✅ 现状（flowTeams.js Flows 区 + flowCanvas.js Flows 标签页） | **移除**（v4）：flow 无常驻 UI，仅运行时弹出标签页（§4.10） | 阶段 1（运行时弹出）/ 3-5（常驻移除） |
| **entity-creator** | ✅ flow（architect→builder→reviewer） | **转 skill**（§4.7） | 阶段 4 |
| FlowReport 注入 | ⚠️ 按 `category="flow"` 注入 | 动态 flow 复用的节点 agent 不一定是 flow category——改按**执行上下文**注入（§4.4） | 阶段 1 |

### 3.4 #381 机制层注入先例（SubTask）

`AgentCore.fixedToolsFor`（AgentCore.scala:1733-1738）是**按 category 的机制层自动注入**：

```scala
def fixedToolsFor(agentDef: AgentDef): Set[String] =
  agentDef.category match
    case "team" => BaseTools + "Mail" + "SubTask"   // ← category=team 自动 ∪ {Mail, SubTask}
    case "flow" => BaseTools + "FlowReport"
    case _ => BaseTools
```

- 不需要在 agent.json 声明；`buildAllowedToolSet` 把 fixedToolsFor 结果并入工具集（AgentCore.scala:1274-1281）。
- SubTask worker 是 leaf：`Mail/SubTask/Delegate/FlowTrigger` 全部剥离（AgentCore.scala:1315）——**一次性 flow 的 worker 同理应剥离创建/执行类工具，防无限递归**。

**这就是用户点名的先例**：新的 flow 能力照此模式注入（§4.4）。v2 下 `case "flow" => ...FlowReport` 分支随预定义 flow 退役而移除，FlowReport 改为按执行上下文注入。

---

## 4. 设计草案（v2：取代架构）

### 4.1 总体架构

```
agent（Team 成员 / Nebula）
  │  FlowExecute(flow: <内联 FlowDagDef JSON>, prompt: "...")   ← 定义即执行，无独立 create 步骤
  ▼
FlowExecuteTool ──校验（复用 FlowStructure.validate + EntityLoader.validateFlow）
  │             ├─ 非法 DAG → 拒绝（错误信息含具体违规项）
  │             └─ 合法 → spawn FlowDagRunner（复用现有 one-shot 执行）
  ▼
FlowDagExecutor.execute（复用现有引擎：walk/barrier/ParallelDynamic/$all 聚合）
  │  执行完 → ImmediateInput 回传结果 → runner self-stop（即用即弃）
  ▼
无任何 flows/ 目录落盘 · 无文件 watcher 感知 · 无 MountedFlowStore 持久化
```

**v2 关键差异**：这不是「现有体系旁边加一条通道」，而是**新架构本体**。预定义 flow（flows/ 目录 + FlowTrigger + flow catalog）在过渡期并存（阶段 1-2），随阶段 3-5 逐条迁移、退役。

### 4.2 动态 flow 架构：FlowExecute 工具（执行语义）

**命名决策**（用户裁定：工具名是「flow 执行」，不是「flow create」）：

| 候选 | 评价 | 结论 |
|---|---|---|
| **FlowExecute** | 与现有工具命名族一致（FlowTrigger / FlowReport / SubTask / Delegate 均为 PascalCase 动词式）；Flow* 前缀族在代码与文档中 grep 可一次搜全；「Execute」明确执行是主体动作，创建隐含在定义中 | ✅ **推荐** |
| RunFlow | 语义 OK，但与 FlowDagRunner 内部方法 `RunFlow`（FlowDagRunner.scala:21-34）撞名——工具层与内部方法同名易混淆 | 不采用 |
| ExecuteFlow | 动词在前亦可，但破坏 Flow* 前缀族一致性（FlowTrigger/FlowReport 都是 Flow 在前） | 备选 |
| flow-execute | kebab-case 不符合代码库工具命名惯例（工具均为 PascalCase） | 不采用 |

**语义差异（与 v1 的 FlowCreate 对比）**：FlowCreate 的语义是「创建一份 flow 定义（再另行触发）」——暗示定义与执行分离、定义可能持久化。FlowExecute 的语义是**「执行一次 flow」**：flow 定义（FlowDagDef JSON）是**执行调用的参数**，不是独立产物——没有 create 步骤、没有落盘、没有注册，定义随调用存在，用完即弃。这正好匹配「agent 临时写一个、用一次就扔」的心智模型。

**为什么不是写文件再触发**（对比了 3 个形态，结论与 v1 相同但语义按执行重述）：

| 形态 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A1: FlowExecute 工具（内联 JSON） | 即用即弃、无残留、天然并发安全；执行语义天然匹配 | 无文件留痕（调试靠 WS 事件） | ✅ **采用** |
| A2: FlowTrigger 加内联 flow 参数 | 复用触发通道 | 混淆「触发预定义 flow」与「执行临时 flow」两个语义（v2 下 FlowTrigger 本身要退役，此路不通）；白名单校验复杂化 | 不采用 |
| A3: 写临时 flow.json 再触发 | 复用 EntityLoader | 落盘污染、并发写同名冲突、watcher 误报「flow 有磁盘变更」；且与「取代预定义体系」方向相悖 | 不采用 |

**FlowExecute 调用形态**：

```
FlowExecute(
  name: "deck-64p",            // 显示名（WS 事件里用），内部用 uuid 隔离
  description: "64 页 PPT：每页独立 worker + 每页独立 QA",
  maxFanout: 64,               // 本 flow 声明的并行规模（agent 按任务定；N = min(len, maxFanout)，无全局上限——v3）
  maxLoop: 3,                  // 失败重做回路轮数上限
  params: { ... },             // 可选，同 FlowDagDef.params
  nodes: {                     // 完整 DAG（FlowNode 同现有 schema）
    "planner":   { "agent": "planner", "input": "$task ...", "outputs": {"pages": "array"},
                   "onComplete": { "parallel": { "slots": "pages", "template": "worker", "onFail": "collect" } } },
    "worker":    { "agent": "worker", "input": "做第 {{index}} 页：{{item}} ...", "onComplete": "join-worker" },
    "join-worker": { "agent": "…", "input": "$worker.all.output …" },
    ...
  },
  entry: "planner",
  prompt: "<自包含任务描述>"
)
```

**校验复用**：`FlowStructure.validate`（结构：≥2 节点/终止路径/fanout 上限/join 汇聚/混合到达）+ `EntityLoader.validateFlow`（agent 存在性）——**内联 DAG 与磁盘 flow 同一套校验**，拒绝时返回具体违规项（与现有加载报错一致）。

**生命周期**：
- 注册：`RunningFlowRegistry` 按唯一 instanceId（`inline-<uuid>`）登记，前端 `flowStarted` 触发**运行时弹出标签页**（§4.10，v4 唯一 flow UI）。
- 执行：`FlowDagRunner`（one-shot）→ `FlowDagExecutor.execute`——**与预定义 flow 完全同一引擎**。
- 回收：执行完 runner self-stop；`FlowReportStore`/session 清理走现有 `executeAgent` 收尾（FlowDagExecutor.scala:1018-1024）。
- 无残留：不写 `flows/` 目录、不写 MountedFlowStore、文件 watcher 不感知（watcher 只监听 `flows/agents/teams` 目录）。
- **agent 引用解析**：一次性 flow 无自己的 `agents/` 目录，fallback 链 = **全局 agent 库**（`~/.nebflow/agents/`）优先，可选扩展：调用者若在 team 中，追加该 team 的 `teams/<team>/agents/`（团队成员可让 flow 引用团队内角色）。

**安全**：动态 flow 的节点 agent 从**现有全局/团队 agent 库**解析，不做任意代码注入；FlowExecute 不提供自定义 agent 定义（要新角色先走 entity-creator skill）。

### 4.3 64 worker + 64 QA 编排模型（v2 保留）

**用户场景**：64 页 PPT → 64 并行 worker（每页一个）+ 64 并行 QA（每页一个）→ 聚合。核心洞察：**两级 fanout 共享同一份 item 源（planner 的 pages 数组），不是 worker 汇聚后再由单个 QA 审全部**。

![64 worker + 64 QA 两级 fanout 编排](/tmp/flow-redesign-arch.svg)

**DAG 表达**（FlowExecute 内联）：

```
planner（拆 64 页 → slots.pages: array）
  → ParallelDynamic(slot=pages, template=worker)   # 第 1 级：worker#1..#64 并行做页
    → barrier join
  → ParallelDynamic(slot=$planner.pages, template=qa)  # 第 2 级：qa#1..#64 并行审页（复用同一清单）
    → barrier join
  → aggregator（$worker.all.output + $qa.all.output + $qa.all.slots.verdict → 完整 deck）
    → $return
```

**所需引擎扩展（唯一必要的核心扩展）——跨节点 slot 引用**：

现状 `ParallelDynamic(slotField, template, onFail)` 只读 **owner 自己**的 array slot（FlowDagExecutor.scala:694-700）。QA 级 fanout 的 owner 是 join 节点，没有 pages 数组。扩展：`slotField` 支持两种形态：

- `"pages"` → 本节点 slot（现状语义不变）
- `"$planner.pages"` → **引用任意已完成节点的 array slot**（跨节点引用；planner 已完成，其 slots 在 ExecState 中可读）

这样 worker fanout 与 QA fanout **共享同一 item 源**（planner.pages），N 相同（64），`{{index}}` 天然对齐（worker#i 做第 i 页 ↔ qa#i 审第 i 页），两级的 barrier 计数各自独立正确。

**各节点职责与聚合**：

| 节点 | 输入 | 输出 | 说明 |
|---|---|---|---|
| planner | `$task` | `slots.pages`（64 页，每项含页主题/素材指引/产出要求） | 拆页 + 每页自包含指令（Anthropic「teach the orchestrator how to delegate」：每任务 objective/output format/边界） |
| worker#i | `{{item}}`（第 i 页清单）+ `{{index}}` | 第 i 页初稿（output） | **独立实例、干净 context**；提示词含 token 预算（每页 ≤ N tokens） |
| qa#i | 第 i 页清单 + `$worker.all.output` 中第 i 页部分（或 `{{item}}` + 对应页内容） | `verdict: pass/fail`（FlowReport）+ 修改意见（slots） | 独立实例；只审自己那页；verdict 走现有 FlowReport 结构化通道 |
| aggregator | `$worker.all.output`（64 页）+ `$qa.all.output`（64 条意见）+ `$qa.all.slots.verdict` | 完整 deck / 失败页清单 | 单 agent 综合；失败页重做回路见 §4.6 |

**为什么「每页独立 QA」而非「单 QA 审全部」**：与 Anthropic 的 subagent 独立 context window 同理——64 页全部塞给一个 QA 会超 context、审不细、失败相互传染；每页独立 QA 并行且互不影响，质量与速度兼得。这正是用户点名的编排模型，引擎只需「跨节点 slot 引用」一个扩展即可表达。

### 4.4 机制层注入（Team 成员 + Nebula 默认 flow 能力；v2 改 FlowExecute）

参照 #381 先例（`AgentCore.fixedToolsFor`），扩展固定工具注入：

```scala
// v2：注入 FlowExecute（取代 v1 草案的 FlowCreate）
case "team" => BaseTools + "Mail" + "SubTask" + "FlowExecute"
case _ if agentDef.name == "Nebula" => BaseTools + "FlowExecute"
// case "flow" => BaseTools + "FlowReport"  ← 随预定义 flow 退役移除（Phase 5）
case _ => BaseTools
```

**设计要点**：

1. **注入点**：`fixedToolsFor`（category/名字级，机制层）——Team 成员与 Nebula **无需在 agent.json 声明**即默认具备，与 #381 完全同构。
2. **FlowReport 改按执行上下文注入**：现状 `case "flow" => BaseTools + "FlowReport"` 只覆盖预定义 flow 的节点 agent（category="flow"）。动态 flow 复用的节点 agent 来自全局/团队库（category=standalone/team），它们**不在执行时自动获得 FlowReport**，而 verdict/switch 节点需要它。改为：`FlowDagRunner` spawn 节点 agent 时，按**执行上下文**（正在执行 flow 节点）统一注入 FlowReport——与 category 解耦。
3. **leaf 防递归**：仿 SubTask worker 剥离（AgentCore.scala:1315）——一次性 flow 的**节点 agent**（worker/qa/aggregator）是 leaf：`FlowExecute/FlowTrigger/SubTask/Delegate` 全部剥离，防止 flow 节点内再开 flow 造成递归爆炸。FlowExecute 只在**调用者**（Team 成员/Nebula）层可用。
4. **深度限制**：SubTask 有 `MaxDepth = 5`；FlowExecute 建议同机制（`ctx.depth >= MaxDepth` 拒绝）。
5. **指导注入**：FlowExecute 的 description 内嵌完整 DSL 语法（节点 schema、占位符、聚合、onFail、maxFanout/maxLoop、成本指导——**按节点选模型 + 提示词级 token 预算，不限制并发**）+ 编排原则（按任务复杂度定并行规模，Anthropic scaling rules）——工具自文档 = 机制层默认指导，不需要 agent.json 声明 skill。**skill 承载详细版指导与参考范例**（`flow-execute` skill，用户 08-25 15:12：「skill 里会有一定指导」——指导原则/最佳实践，非可复用模板）；不阻塞阶段 1。
6. **flow catalog 移除**：`buildPerAgentFlowCatalog`（SkillService.scala:252-265，按 agent.json flows 白名单注入预定义 flow 目录）随 flows/ 退役移除（Phase 5）；System prompt 中「Flow 能力提示」（什么时候该用 FlowExecute 而非 SubTask）以注入段落形式存在，与 SubTask 的「Spotting parallelizable work」段落风格一致。

### 4.5 成本控制（v3 修订——不限制并发，保留节点配置模型）

> **v3 裁定**（用户 08-25 15:12）：「不要限制并发和限流，节点配置模型是对的」。删除并发限流（Semaphore 分波）与全局硬上限（maxFanoutCap）两项；成本控制收敛为**节点配置模型（per-node preset）+ 提示词级预算**，与 #395（provider 限流全部去掉）理念一致：信任 agent 动态创建流程，不做限流。

| 控制维度 | 机制 | 说明 |
|---|---|---|
| **flow 声明的并行规模** | `flow.maxFanout`（现有，clamp 语义） | 64 页用例声明 64；引擎 `N = min(len, maxFanout)` 已实现。v3 语义：这是 **flow 自己声明的并行规模**（agent 按任务复杂度定），不是引擎/全局对并发的限制——**无全局硬上限**，agent 写多少并行多少（信任 agent） |
| **每节点模型分级（新增，保留）** | `FlowNode.preset: Option[String]` | 复用现有 preset 机制（`model-presets.json` + `PresetResolver` + Delegate/SubTask preset 参数先例）。worker 用低成本 preset、QA/aggregator 用强模型（Magentic-One 异构模型思路）——**用户认可的「节点配置模型」**。`executeAgent` 里 spawn 前 `PresetResolver.applyPreset` |
| **提示词级 token 预算** | worker/QA 提示词规则（Anthropic scaling rules） | 每 worker「产出 ≤ X tokens」、planner「按复杂度定路数」——写进 FlowExecute 工具 description 的写作指导，非引擎强制（提示性：提醒 agent 合理定规模，不做 clamp） |
| **成本可见性** | WS 事件 + 前端 flow 面板 | 现有 flowProgress/flowNodesAdded 已展示每实例状态；token 看板（#310）已有基础设施 |

### 4.6 失败页重做回路（v2 保留）

**目标**：QA 判 fail 的页自动重做，而不是整 flow 重跑。

**DAG 表达**（三节点回路 + 引擎扩展「filtered item 源」）：

```
planner → worker fanout(64) → join → qa fanout(64) → join → 判定节点
  → switch($判定.verdict)
      pass → aggregator → $return
      fail → 重做 fanout：ParallelDynamic(slot=$planner.pages,
               filter: { by: "$qa.all.slots.verdict", keep: "fail", matchIndex: true },
               template=worker)   # 只重做 fail 页
           → join → qa fanout（只审重做页，filter 同上）
           → 判定节点（maxLoop 控制轮数）
```

**引擎扩展**：`ParallelDynamic` 增加可选 `filter`——从 slot 源数组里按**兄弟 fanout 的 verdict 数组按 index 对齐**过滤（worker#i ↔ qa#i ↔ page i 的 index 对应关系天然成立，因为两级共享同一 item 源与顺序）。keep="fail" → 只对 fail 页重跑 worker→QA，pass 页不再动。

**轮数控制**：`maxLoop`（现有边计数）或 flow 级 `maxRounds`（新增可选）限制重做轮数；超限则聚合器收到「N 页仍 fail」清单，交付时标注待人工处理。

**成本**：重做只作用于 fail 子集——比「整 flow 重跑」省一个量级，且每轮 token 递减（fail 页越来越少）。

### 4.7 entity-creator skill 化设计（v2 新增重点）

**背景**：现有 `flows/entity-creator/flow.json`（v2.1：architect → 3 分支 builder → 3 分支 reviewer，maxLoop=3 评审循环，callerScope 门禁）+ 配套 `flows/entity-creator/agents/` 7 个专用角色。用户裁定：转 skill。**为什么可以转**：实体创建循环的本质是「设计 spec → 构建 → 自测 → 评审 → 迭代」——这是一个**行为指导**（教 agent 怎么做），不是「流水线编排才成立」的并行结构。循环中的 architect/builder/reviewer 是**角色**而非**并行实例**，单一 agent（调用者）按 skill 步骤即可承担全部角色（或按需委托现有 agent 做评审），不需要引擎来编排三个串行角色。

**skill 结构**（套用 skill-creator 规范）：

```
~/.nebflow/skills/entity-creator/
├── SKILL.md          # frontmatter + 正文（设计-构建-评审循环步骤 + 分支规则）
└── references/       # callerScope 门禁细则、验收点（验收点）书写规范、评审轮次纪律
```

**frontmatter**：

```yaml
---
name: entity-creator
description: Create or modify Agents, Teams, and Flows with a design-build-review loop. v2.1: agent branch (global agents for Nebula; team-domain agents for team leads via the callerScope gate), team branch (new teams, Nebula-only), flow branch (flow 动态创建指导, Nebula-only — flow 流程由 agent 根据任务动态创建，本 skill 只提供创建指导与最佳实践，无固定模板). Practice discipline: specs carry binary acceptance points (验收点); builders self-test and git-commit before review; at most 2 revise rounds, then Caller escalation; teams auto-mount via Load on pass. Use when a task asks to create, add, or restructure an agent, a team, or a flow. Triggerers MUST state caller identity: 'Caller: Nebula' or 'Caller: <lead-name> (team: <team-name>)' — unidentified tasks are refused by the gate.
language: zh
---
```

**与 skill-creator 的差异**：

| 维度 | skill-creator | entity-creator（skill 化后） |
|---|---|---|
| 产出 | skill（prompt 包，无状态） | agent/team **有状态系统定义**（影响整个系统）；flow = **动态创建指导**（无实体文件，agent 按任务动态创建，v3 修订） |
| 流程 | 直接创建（proposal 环节已废止，2026-08-24 裁定） | **保留**设计-构建-评审循环 + callerScope 门禁（实体是状态定义，需要验收与版本化，不能「直接创建」了事） |
| 门禁 | 无 | Caller 身份必须声明（'Caller: Nebula' 或 'Caller: <lead> (team: <team>)'），未声明拒绝 |
| 迭代纪律 | 无强制轮次 | 最多 2 轮 revise，第 3 轮仍 unresolved → Caller 升级（绝不第 3 次 revise） |

**audience / 订阅**：实体创建是**编排层能力**——team/flow 分支仅 Nebula 可用，agent 分支全局 agent 归 Nebula、团队域 agent 归各团队 Manager（callerScope 门禁）。`audience` frontmatter 机制（绑定单一 team 全员）不适合（受众是 Nebula + 各 Manager，非某 team 全员），改为**显式声明**：Nebula 的 agent.json `skills` 数组 + 各团队 Manager（team lead）的 agent.json `skills` 数组。存量订阅清理：agent.json `flows` 白名单随 FlowTrigger 退役。

**skill 正文步骤**（v2.1 纪律保留，角色由调用者承担或委托）：

1. **门禁检查**：任务文本必须含 Caller 行（'Caller: Nebula' 或 'Caller: <lead-name> (team: <team-name>)'）；缺失 → 不设计、不猜测，输出门禁说明 + 正确的重派格式，拒绝。
2. **设计（architect 角色）**：写设计 spec，**每条验收点必须是二值可测**（PASS/FAIL + 证据方式）；分支判定：agent（全局 vs 团队域）/ team（仅 Nebula）/ flow（**动态创建指导**，仅 Nebula——flow 无实体文件，agent 根据任务动态创建流程，skill 提供 DSL 与编排原则指导）。
3. **构建（builder 角色）**：创建/修改所有指定文件；**自测表**（每条验收点 PASS/FAIL + 证据）；**git commit**（按文件提交，禁止裸改）。构建可自己执行或委托现有实施 agent（Coder/Backend 等）。
4. **评审（reviewer 角色）**：独立评审磁盘上的实际文件；评审可委托现有评审 agent（qa-* / design-engineer）或动态 code-review flow。轮次纪律：1-2 轮 fixable → revise；第 3 轮仍 unresolved → verdict=fail + Caller 升级（汇报已尝试什么、需要什么裁定），**绝不第 3 次 revise**。
5. **收尾**：团队实体 PASS 后 Load 自动挂载；flow 实体 = **验证动态创建指导（§4.8）**——按指导用 FlowExecute 真实创建并执行一次 flow（测试 DAG），逐条过验收点。

**entity-creator flow 退役步骤**（阶段 4）：
1. 建 `~/.nebflow/skills/entity-creator/`（SKILL.md + references/）并声明订阅（Nebula + 各团队 Manager 的 agent.json `skills`）。
2. `flows/entity-creator/agents/` 7 个专用角色（architect/agent-builder/agent-reviewer/team-builder/team-reviewer/flow-builder/flow-reviewer）处置：职责并入 skill 正文（调用者承担或委托现有 agent），**专用角色随 flow 退役**——不在全局 agent 库留存（避免僵尸角色）。
3. 验证：用 skill 真实创建 1 个 agent + 1 个 team + 1 个 flow（测试实体），逐条过验收点。
4. `git rm flows/entity-creator/`（flow.json + agents/ + 相关引用）。
5. 引用迁移：Nebula system prompt、`flows/GUIDE.md`、docs 中 "entity-creator flow" 表述 → "entity-creator skill"。

### 4.8 存量 flow 迁移路径（v3 修订——取代的核心）

**总则**：现有 9 个预定义 flow 全部迁移。entity-creator → skill（§4.7）；其余 8 个 → **skill 指导 + agent 动态创建**（v3 裁定：flow 的流程每次由 agent 根据任务动态创建，**无固定模板**；skill 只提供指导原则/最佳实践——如各 flow 的编排模式、64 页两级 fanout 范例——agent 读指导后按当前任务重新写 DAG，即用即弃）。`flows/` 目录与 FlowTrigger 在迁移完成后退役（§5 阶段 5）。

**迁移路径表**：

| 现有 flow | 节点（角色） | 迁移路径 | 迁移后形态 |
|---|---|---|---|
| **entity-creator** | architect→3×builder→3×reviewer（评审循环） | → skill（§4.7） | skill |
| **presentation-prep** | planner→r1-r4→content-planner→visual-designer→verifier | → 转 skill 指导（presentation-prep 编排模式沉淀为指导/范例）+ **agent 动态创建**；**领域专用角色**（content-planner/visual-designer/verifier）迁移为全局 agent（category=standalone）或对应团队 agent 库，FlowExecute 按 fallback 链引用 | skill 指导 + 动态创建 |
| **research** | planner→r1,r2→verify→writer | → 转 skill 指导 + agent 动态创建（agent 每次按主题动态写 DAG + 研究员提示词；research 流程模式沉淀为指导） | skill 指导 + 动态创建 |
| **code-review** | scanner→reviewer→fixer | → 转 skill 指导 + agent 动态创建（可再委托给现有 qa-* / 评审 agent 作 fixer 角色） | skill 指导 + 动态创建 |
| **git-merge** | scanner→evaluator→merger | → 转 skill 指导 + agent 动态创建（git 操作下沉到 agent 直接执行，merger 角色可省或委托） | skill 指导 + 动态创建 |
| **nebflow-review-merge** | scanner→reviewer→merger | → 转 skill 指导 + agent 动态创建（worktree 清理等脚本逻辑下沉到 agent 直接执行） | skill 指导 + 动态创建 |
| **release-stable** | reviewer→coder→packager | → 转 skill 指导 + agent 动态创建（**最高风险，最后迁移**；需真实 release 冒烟验证；五步编排模式沉淀为指导） | skill 指导 + 动态创建 |
| **weekly-summary** | summarizer→reporter | → 转 skill 指导 + agent 动态创建（或 Schedule 定时任务由 agent 按指导动态创建） | skill 指导 + 动态创建 |
| **memory-consolidation** | scanner→consolidator | → 转 skill 指导 + agent 动态创建 | skill 指导 + 动态创建 |

**角色处置规则**（决定 `flows/<name>/agents/` 下专用角色去留）：

- **通用角色**（scanner / evaluator / merger / reviewer / coder / packager / summarizer…）→ 角色随 flow 退役；执行时由调用者直接承担或委托现有全局 agent（Coder / qa-* 等）。**不在全局库复制专用角色**。
- **领域专用角色**（presentation-prep 的 content-planner / visual-designer / verifier、research 的 researcher / writer）→ 有独立系统提示价值，**迁移到全局 agent 库或对应团队 agent 库**（category 改为 standalone/team），FlowExecute 按 §4.2 fallback 链引用。迁移时校验 description 中「用于 X flow」的表述改为「供 FlowExecute 动态 flow 引用」。

**指导载体**（v3 修订：无「模板」概念——flow 每次由 agent 根据任务动态创建，不落盘为 flow.json，那等于换个地方继续预定义体系）。指导/最佳实践承载在 **skill**：
1. **`flow-execute` skill（主要载体）**——DSL 语法 + 编排原则 + **参考范例**（如 64 页两级 fanout、release 五步、research 四段），agent 读指导后**按当前任务重新写 DAG**（范例是「怎么做」的参考，不是可加载执行的模板）；经验沉淀亦可写进团队 memory。
2. **各领域 skill / entity-creator skill**——如 presentation-prep 的编排模式沉淀进对应 skill references/。
保持「定义即执行、无持久 flow 身份」：指导可复用，flow 本身每次新建。

**flows/GUIDE.md 语义反转**：其「What deserves a Flow」决策树中「可复用 → 固化为 flow.json」分支删除——可复用的协作模式 → 沉淀为 **skill 指导/最佳实践**（agent 动态创建时参考），而非磁盘 flow 文件或可复用模板。GUIDE.md 内容并入 FlowExecute description / flow-execute skill 后随 flows/ 退役。

### 4.9 兼容性 / 过渡期形态（阶段 1-2 并存）

- **阶段 1-2 并存期**：FlowTrigger + 预定义 flow 行为**不变**（零改动，纯增量并存）；动态 flow 与预定义 flow **共享同一执行引擎**（FlowDagExecutor）——引擎演进（跨节点 slot 引用、per-node preset、filtered item 源）两边同时受益，**无分叉**。
- **前端（v4 修订）**：WS 事件协议复用现有 `flowStarted/flowNodesAdded/flowProgress/flowCompleted`，无需新事件；动态 flow 的运行视图 = **运行时弹出标签页**（§4.10，P5 flow-run 机制复用 + 结束关闭逻辑）。**常驻 flow UI 不适用于新架构**——过渡期（阶段 1-2）预定义 flow 的旧 UI（Flows 标签页 / 团队卡片 Flows 区）暂留以支撑存量 flow 管理，随阶段 3-5 迁移/退役移除（§5）。
- **使用分界（写进 FlowExecute description 的指导，过渡期）**：
  - 一次性的、任务绑定的并行结构（64 页 PPT、多路调研、批量验证）→ **FlowExecute**
  - 反复使用的固定流水线（过渡期存量）→ 预定义 flow + FlowTrigger（**仅过渡期**，迁移后统一 FlowExecute）
  - 需要评审/版本化的新实体（agent/team/flow 定义）→ **entity-creator skill**（不再有 flow 版）

### 4.10 UI 形态（v4 裁定）——flow 无常驻入口，仅运行时弹出

> **用户裁定（08-25 15:1x）**：「flow 在新架构下，需要去掉侧边栏、标签页，只在 flow 运行时，在标签页弹出显示」。flow 不再有常驻 UI 入口；**flow 不可见除非运行中**。

**目标形态**：

```
┌─ Canvas 标签页体系（其他标签页不变）───────────────────────┐
│  [Chat] [Teams] [flow-run-<uuid> ★] ...                    │ ← ★ 运行时自动弹出
│  ┌─ flow-run-<uuid>（唯一 flow UI）─────────────────────┐  │
│  │  DAG 运行视图（solar-system）                         │  │
│  │  planner ●done   worker#1 ●running                   │  │
│  │  worker#2 … worker#64 ●pending   qa#1 ●queued        │  │
│  │  ── 聚合结果区（barrier join 后逐步填充）               │  │
│  │  ── 结束态：verdict / 完成 / 失败 + 关闭按钮            │  │
│  └───────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────┘
```

**触发 → 弹出 → 实时状态 → 结束关闭**：

| 环节 | 行为 | 机制 |
|---|---|---|
| 触发 | FlowExecute 调用通过校验 → 引擎注册 instanceId（`inline-<uuid>`）→ 广播 `flowStarted` | 现有机制 |
| 弹出 | 前端收到 `flowStarted` → 自动 `openTab("flow-run-<instanceId>", flowName, {type:"flow-run", closable:true, pinned:true})`（**复用 P5 `maybeAutoOpenFlowsTab` / `openFlowRunTab`**）→ 标签页成为当前活动页 | 现有 P5 机制保留 |
| 实时状态 | 标签页内渲染 DAG 运行视图：节点进度（pending/running/done/failed）、并行 worker 实例（worker#1..#64）、聚合结果（barrier join 后 `$all` 内容逐步填充）；`flowProgress`/`flowNodesAdded`/`flowMail` 增量更新（复用 `renderFlowRunInto`） | 现有机制 |
| 结束关闭 | `flowCompleted`（success / verdict）或 flow 失败 → **标签页自动关闭**，或**停留结束态**（节点全 done/failed + 聚合结果 + 关闭按钮）由用户手动关闭——二选一，倾向「停留可手动关闭」（用户可回看运行结果）；被关闭后不复活 | **新增**：结束事件 → 标签页处置逻辑 |

**与现有 Flow 面板 / Canvas 的关系**：

- flow-run 标签页是 Canvas 标签页体系的一员（`openTab`/`getTabPane`/可拖拽/可关闭/可恢复），与 Chat / Teams 标签页同级——不是独立浮层，不新建面板概念。
- **Teams 面板保留**（团队是常驻组织视图：团队任务/成员/邮件仍常驻展示）；仅其 **Flows 区（flow 列表/入口）移除**。
- **Flows 管理标签页移除**（flowCanvas.js `openFlows`/`renderFlowsTab`、flowList.js、flows-btn）：flow 定义无持久身份（定义即执行、用完即弃），不存在「浏览/管理 flow 定义」的需求。
- 与「静态 DAG 预览标签页」（`flow-def-<name>`，View DAG 产物）的区别：后者是预定义 flow 体系的前端，随阶段 3-5 退役；动态 flow 只有 flow-run 标签页。

**无 flow 管理视图**：

- 不提供：flow 列表、flow 定义浏览、flow 目录文件视图、flow 触发按钮、flow 配置面板。
- flow 的全部生命周期可见性 = 运行时弹出标签页（从 `flowStarted` 弹出到结束关闭）。运行之外，flow 对用户**不可见**。
- 调试/回看：依赖 WS 事件与运行中标签页；运行结束标签页关闭即无留存（与「一次性 flow 无持久化」的 v3 语义一致，§1.2 checkpointer 对比的 backlog 结论不变）。

**移除清单**（代码级；阶段 1 起动态 flow 不走这些入口，阶段 3-5 彻底删除）：

| 现状组件 | 文件 | v4 处置 |
|---|---|---|
| 团队卡片 Flows 区（flow 行 + 内联 DAG，侧边栏入口） | `flowTeams.js`（flowsSectionHtml / bindFlowRowClicks） | 阶段 3 随各 flow 迁移移除对应行；阶段 5 删除渲染代码 |
| Flows 常驻标签页（flow 定义列表/管理面板） | `flowCanvas.js`（openFlows / renderFlowsTab）+ `flowList.js` | 阶段 5 移除（flows-btn、openFlows、renderFlowsTab、flowList 删除） |
| flow-run 运行时标签页 | `flowCanvas.js`（openFlowRunTab / renderFlowRunTab / maybeAutoOpenFlowsTab）+ `flowDag.js`（renderFlowRunInto） | **保留为唯一 flow UI**；阶段 1 补结束关闭逻辑 |
| 静态 DAG 预览标签页 | `flowCanvas.js`（renderStaticDag）+ `/api/flow/dag/:name` | 随预定义 flow 退役（阶段 3-5） |

---

## 5. 分阶段实施（v2 取代路径）+ 验收条件

> 与 v1 的「增量路径」根本区别：阶段 3-5 是**存量迁移与退役**，不是可选 backlog。每阶段验收仍以冒烟测试硬性首位。

### 阶段 1：FlowExecute 工具 + 机制层注入（核心，并存过渡开始）

**改动**：
- `core/tools/FlowExecuteTool.scala`（新）：接收内联 FlowDagDef JSON → `FlowStructure.validate` + `EntityLoader.validateFlow`（agent 存在性）→ 通过后 spawn `FlowDagRunner`（instanceId = `inline-<uuid>`）；拒绝时返回具体违规项。**执行语义**：定义即执行，无独立 create 步骤。
- `AgentCore.fixedToolsFor`：`case "team" => BaseTools + "Mail" + "SubTask" + "FlowExecute"`；Nebula 特判 + FlowExecute。
- `AgentCore.buildAllowedToolSet`：flow 节点 agent（一次性 flow 内）剥离 `FlowExecute/FlowTrigger/SubTask/Delegate`（leaf 防递归）；`MaxDepth` 校验。
- **FlowReport 改执行上下文注入**：FlowDagRunner spawn 节点 agent 时统一注入 FlowReport（与 category 解耦，动态 flow 复用非 flow-category agent 也能出 verdict）。
- `EntityLoader`/`loadFlowAgent`：一次性 flow 的 agent 引用 fallback 链（全局优先，可选团队追加）。
- FlowExecute description 内嵌 DSL 语法 + 成本指导（按节点选模型 + token 预算，不限制并发）+ 使用分界指导（含过渡期分界）。
- 前端（v4）：**flow 运行时弹出标签页**——FlowExecute 触发 → `flowStarted` → 自动弹出 `flow-run-<instanceId>`（复用 P5 `openFlowRunTab`/`maybeAutoOpenFlowsTab`，无需新事件）；新增**结束关闭逻辑**：`flowCompleted` / flow 失败 → 标签页自动关闭或停留结束态可手动关闭（§4.10）。

**验收（冒烟测试硬性首位）**：
- [ ] **冒烟测试**：`sbt run` 真实启动服务 → 用 FlowExecute 触发一个 2-worker 并行测试 flow（内联 DAG：planner 拆 2 topics → ParallelDynamic 展开 2 worker → barrier join → aggregator）→ 断言 WS 出现 `flowStarted` + 2 个实例节点的 `flowProgress(Running/Completed)` + `flowCompleted(success=true)`，最终输出包含 2 路聚合结果。
- [ ] **注入验证**：单元测试 `fixedToolsFor`——category=team 的 agent 工具集包含 `FlowExecute`（不写 agent.json 也含）；Nebula 包含；category=standalone 普通 agent 不含；一次性 flow 节点 agent 的工具集**不含** FlowExecute/FlowTrigger/SubTask/Delegate（防递归）。
- [ ] **FlowReport 上下文注入**：动态 flow 复用 category=standalone 的节点 agent，其 verdict 经 FlowReport 正常返回（switch 节点能读到 `$node.verdict`）。
- [ ] 校验单测：非法 DAG 拒绝（缺 entry / 无终止路径 / fanout 超限 / join 不汇聚 / 引用不存在 agent），错误信息含具体违规项；合法 DAG（静态 parallel + 动态 ParallelDynamic）通过。
- [ ] 无残留断言：执行后 `flows/` 目录无新增文件、MountedFlowStore 无新条目、文件 watcher 无「flow 磁盘变更」误报。
- [ ] **运行时弹出断言（v4）**：`sbt run` 真实启动 → FlowExecute 触发测试 flow → Playwright 断言 `flow-run-<instanceId>` 标签页自动弹出、渲染 DAG 节点进度（并行 worker 实例逐个 running→done）→ `flowCompleted` 后标签页进入结束态（自动关闭或停留可手动关闭，§4.10）。
- [ ] 回归：**过渡并存**——现有预定义 flow（research / code-review）经 FlowTrigger 端到端各跑通一次，行为不变。

### 阶段 2：大规模并行能力（64+64 + 失败重做）

**改动**：
- `FlowDagExecutor.parallelDispatchDynamic`：`slotField` 支持跨节点引用（`"$planner.pages"` → 读 `st.slots.get(planner)(pages)`），QA 级 fanout 复用上游 item 源。
- `FlowNode.preset: Option[String]`：`executeAgent` spawn 前 `PresetResolver.applyPreset`（worker 低成本 / QA 强模型——节点配置模型，v3 保留）。
- `ParallelDynamic.filter`（§4.6）：by 兄弟 fanout verdict 数组 + keep + matchIndex——fail 页子集重做回路；`maxRounds` 可选上限。
- （v3 已删：并发限流 Semaphore 分波、全局硬上限 maxFanoutCap——不限制并发，无新配置）

**验收**：
- [ ] **冒烟测试（64+64）**：`sbt run` 真实启动 → FlowExecute 触发 64 worker + 64 QA 测试 flow（maxFanout=64，2 页真实内容 + 62 页轻量占位）→ 断言 64+64 实例全部 `Completed`、两级 barrier 各恰好激活一次、aggregator 输出含 64 页 + 64 条 QA 意见、`flowCompleted success=true`；运行时弹出标签页渲染 128 个实例节点状态（Playwright 断言节点数）。
- [ ] per-node preset 断言：worker 节点实际使用低成本 preset 模型（日志/usage 事件验证），QA 节点使用指定强模型。
- [ ] 跨节点引用单测：`$planner.pages` 形态解析正确；引用不存在节点/字段 → 明确报错；own-slot 形态（`"pages"`）回归不变。
- [ ] **冒烟测试（失败重做）**：3 页样例 flow，QA 判其中 1 页 fail → 断言重做回路**只重跑 fail 页**（`flowNodesAdded` 只新增 fail 页的 worker/qa 实例）→ 第二轮该页 pass → aggregator 输出全 pass、`flowCompleted success=true`。
- [ ] 重做单测：filter 按 index 对齐正确（worker#i↔qa#i↔page i）；keep="fail" 空集（全 pass）→ 跳过重做直达 aggregator；轮数超限 → 交付带「N 页仍 fail」标注。
- [ ] 回归：不带 filter 的现有 fanout 行为不变。

### 阶段 3：存量 flow 逐个迁移（转 skill 指导 + agent 动态创建）

**顺序**（低风险先，高风险后）：weekly-summary / memory-consolidation / git-merge / code-review / research → presentation-prep / nebflow-review-merge → **release-stable 最后**（真实 release 风险最高，需要真实构建验证）。

**每个 flow 的迁移步骤**：
1. 沉淀编排模式为 skill 指导（§4.8 迁移路径；编排模式写进 flow-execute / 领域 skill references 作参考范例），用 FlowExecute 动态创建等价 DAG 跑通（专用角色 agent 先迁到全局/团队库）。
2. 用**真实任务**跑通验证（如 code-review 用一次真实 review、weekly-summary 用本周真实会话）——与旧预定义 flow 输出对拍。
3. 验证通过 → `git rm flows/<name>/`（flow.json + agents/ 专用角色按 §4.8 处置）→ 从 agent.json `flows` 白名单移除。
4. 更新引用（docs / 提示词 / 团队 memory 中该 flow 名 → skill 指导 + 动态创建描述）。
5. 团队卡片 Flows 区随该 flow 迁移移除对应行（v4：侧边栏 flow 入口逐步清空；渲染代码阶段 5 删除，§4.10）。

**验收**：
- [ ] **冒烟测试（迁移后等价）**：每个迁移的 flow，agent 按 skill 指导动态创建 DAG 跑一次真实任务，产出与迁移前预定义 flow 的等价输出一致（对拍：结构字段 + 关键内容）。
- [ ] **迁移存量断言**：阶段 3 结束时 `flows/` 下剩余预定义 flow 数 = 0（除 entity-creator，阶段 4 处理）；全系统无 agent.json `flows` 白名单残留引用。
- [ ] 专用角色迁移断言：迁出的领域角色 agent（content-planner/visual-designer/verifier/researcher/writer）在全局/团队库可被 FlowExecute 正常引用；通用角色无残留。
- [ ] release-stable 专项：**真实 release 冒烟**——按 skill 指导动态创建 DAG 完成一次 beta 版本 release（bump VERSION → merge → tag → 构建 installer → 校验产物），断言产物存在且校验通过。

### 阶段 4：entity-creator skill 化

**改动**：
- 建 `~/.nebflow/skills/entity-creator/`（SKILL.md + references/，§4.7）。
- 声明订阅：Nebula + 各团队 Manager 的 agent.json `skills` 数组。
- 验证 → `git rm flows/entity-creator/`（flow.json + agents/ 7 个专用角色）→ 引用迁移（"entity-creator flow" → "entity-creator skill"）。

**验收**：
- [ ] **冒烟测试（skill 生效）**：真实用 skill 创建 1 个 agent + 1 个 team + 1 个 flow（测试实体）→ 每条验收点 PASS + git commit 存在 + 团队实体 Load 自动挂载。
- [ ] 门禁断言：无 Caller 行的任务被 skill 拒绝（输出门禁说明 + 正确重派格式），不产生任何文件改动。
- [ ] 轮次纪律断言：模拟 reviewer 2 轮 revise 后仍 unresolved → 第 3 轮 verdict=fail + Caller 升级，**无第 3 次 revise**。
- [ ] 退役断言：`flows/entity-creator/` 已删除；Nebula system prompt / docs 中无 "entity-creator flow" 残留；agent.json `flows` 白名单已无 entity-creator。
- [ ] 订阅断言：Nebula 与各团队 Manager 的 catalog 可见 `entity-creator` skill（description 正确触发 when_to_use）。

### 阶段 5：FlowTrigger / flows/ 退役或降级兼容

**改动**：
- 移除 `buildPerAgentFlowCatalog`（flow catalog 不再注入 system prompt）。
- `flows/` 目录退役：目录不再加载（EntityLoader 不再扫描 flows/）；MountedFlowStore 清理。
- `FlowTriggerTool` 退役：移除工具（或降级为 legacy 兼容——保留实现但 `status: deprecated` + 文档标注，仅存量会话可用，新会话不可见）。
- `AgentCore.fixedToolsFor` 移除 `case "flow" => BaseTools + "FlowReport"`（FlowReport 已改执行上下文注入，§4.4）；FlowTreeActor 的 flow 挂载分支清理（团队挂载/崩溃恢复保留）。
- `flows/GUIDE.md` 归档或删除；内容并入 FlowExecute description / flow-execute skill。
- **前端常驻 UI 移除（v4）**：删除 flows-btn + Flows 常驻标签页（flowCanvas.js `openFlows`/`renderFlowsTab`、flowList.js）；删除团队卡片 Flows 区渲染（flowTeams.js flowsSectionHtml）；flow 收敛为**仅运行时弹出标签页**（flow-run，§4.10）。

**验收**：
- [ ] **冒烟测试（新架构端到端）**：`sbt run` 真实启动 → 全链路用 FlowExecute 完成一次「调研 → 评审 → 总结」动态 flow（多节点 + 并行），断言 WS 事件完整 + 输出正确——**无任何预定义 flow 参与**。
- [ ] **零引用断言**：`grep -r "FlowTrigger\|flows/\.json\|flow catalog"` 主仓 → 仅剩兼容/文档标注；system prompt 无 flow catalog 段。
- [ ] **无常驻入口断言（v4）**：Playwright 断言页面无 flows-btn、无 Flows 管理标签页、团队卡片无 Flows 区；仅 FlowExecute 运行时弹出 `flow-run-*` 标签页（触发 → 弹出 → 节点进度 → 结束关闭，全流程断言，§4.10）。
- [ ] 退役断言：`flows/` 目录下无活跃 flow.json（或仅 legacy 只读）；agent.json 无 `flows` 白名单字段。
- [ ] **回归**：SubTask / Delegate / Mail / Team 执行不受影响（各端到端跑通一次）；团队挂载 + 崩溃恢复正常（FlowTreeActor 团队分支回归）。
- [ ] 文档同步：`~/.nebflow/docs/GUIDE.md` 更新为动态 flow 章节；`flows/GUIDE.md` 移除。

### 阶段 6（backlog，可选）

- 运行中动态调整（fanout 中途增减）——沿用 20260824 方案结论：不做 fiber 级，由「params 重跑 + 多级 fanout」覆盖。
- flow 级断点续跑（仿 Temporal event history）——有真实高频场景再评估。
- ~~`flow-execute` skill（详细 DSL 参考）~~ → 已升格为动态创建指导载体（v3，§2/§4.4/§4.8），随阶段 2-3 建立，不再属 backlog。

---

## 6. 改动文件清单汇总

| 文件 | 阶段 | 改动 |
|---|---|---|
| `src/main/scala/nebflow/core/tools/FlowExecuteTool.scala`（新） | 1 | 内联 FlowDagDef 校验 + spawn FlowDagRunner + DSL 指导 description（执行语义） |
| `src/main/scala/nebflow/agent/AgentCore.scala` | 1,5 | `fixedToolsFor` 注入 FlowExecute（team / Nebula）；flow 节点 leaf 剥离（+FlowExecute/FlowTrigger）；MaxDepth；阶段 5 移除 `case "flow"` |
| `src/main/scala/nebflow/core/entity/EntityTypes.scala` | 2 | `FlowNode.preset`、`ParallelDynamic.filter`；slotField 跨节点形态（v3 已删：`FlowDagDef.concurrency`） |
| `src/main/scala/nebflow/core/entity/FlowDagExecutor.scala` | 2 | 跨节点 slot 引用；per-node preset；filtered item 源（v3 已删：Semaphore 分波、maxFanoutCap） |
| `src/main/scala/nebflow/core/entity/FlowStructure.scala` | 2 | 新字段校验（filter 引用合法性、preset 存在性；v3 已删：concurrency ≤ maxFanout） |
| `src/main/scala/nebflow/core/entity/EntityLoader.scala` | 1,5 | 一次性 flow agent 引用 fallback（全局 + 可选团队）；阶段 5 停扫 flows/ |
| `src/main/scala/nebflow/core/flow/FlowDagRunner.scala` | 1 | FlowReport 执行上下文注入（spawn 节点 agent 时） |
| `src/main/scala/nebflow/core/tools/FlowTriggerTool.scala` | 5 | 退役或降级 legacy（deprecated 标注） |
| `src/main/scala/nebflow/core/skill/SkillService.scala` | 5 | 移除 buildPerAgentFlowCatalog |
| `src/main/scala/nebflow/core/flow/FlowTreeActor.scala` | 5 | flow 挂载分支清理（团队挂载保留） |
| 配置（`nebflow.json` 或现有 config） | — | **无新增配置**（v3 已删：`flow.maxFanoutCap` 全局硬上限） |
| WS 前端 | 1-3 | **无需新事件**（复用 flowStarted/flowNodesAdded/flowProgress/flowCompleted） |
| `src/main/resources/web/js/flowCanvas.js` | 1,5 | 阶段 1：运行时弹出标签页复用（openFlowRunTab/maybeAutoOpenFlowsTab）+ 结束关闭逻辑；阶段 5：移除 Flows 常驻标签页（openFlows/renderFlowsTab）与静态 DAG 预览（renderStaticDag） |
| `src/main/resources/web/js/flowTeams.js` | 3,5 | 阶段 3 随各 flow 迁移移除 Flows 区对应行；阶段 5 删除团队卡片 Flows 区渲染（flowsSectionHtml/bindFlowRowClicks） |
| `src/main/resources/web/js/flowList.js` | 5 | 删除（flow 定义列表页，随常驻 Flows 管理面板退役） |
| `src/main/resources/web/js/flowDag.js` | 1 | flow-run 渲染复用（renderFlowRunInto）+ 结束态渲染 |
| `~/.nebflow/skills/entity-creator/`（新） | 4 | skill + references（§4.7） |
| `~/.nebflow/skills/flow-execute/`（新，可选） | 2,3 | 动态创建指导 + 编排范例（§2/§4.4/§4.8） |
| `~/.nebflow/flows/<name>/`（逐个） | 3 | 迁移后 git rm（flow.json + agents/ 按 §4.8 处置；编排模式先沉淀为 skill 指导） |
| `~/.nebflow/flows/entity-creator/` | 4 | git rm（flow.json + agents/） |
| agent.json（Nebula + 各团队 Manager） | 4,5 | `skills` 加 entity-creator；`flows` 白名单逐步清空 |
| `~/.nebflow/docs/GUIDE.md`、`flows/GUIDE.md` | 3,5 | 动态 flow 章节；退役说明 |

---

## 7. 来源链接

- Anthropic — How we built our multi-agent research system: https://www.anthropic.com/engineering/built-multi-agent-research-system
- LangGraph 官方文档（Send API / map-reduce / checkpointer）: https://docs.langchain.com/oss/python/langgraph/ （原 how-to: https://langchain-ai.github.io/langgraph/how-tos/map-reduce/）
- Magentic-One 论文: https://arxiv.org/abs/2411.04468 ；微软博客: https://www.microsoft.com/en-us/research/blog/magentic-one-a-generalist-multi-agent-system-for-solving-complex-tasks/
- Temporal Child Workflows (Java SDK): https://docs.temporal.io/develop/java/child-workflows

## 8. 本地代码证据（现状分析引用）

| 文件 | 关键位置 |
|---|---|
| `src/main/scala/nebflow/core/entity/FlowDagExecutor.scala` | parallelDispatchDynamic L644-800；resolveInput 聚合 L195-223；executeAgent L911-1062 |
| `src/main/scala/nebflow/core/entity/FlowStructure.scala` | validate L141-198；dynamicJoinNodes L111-119 |
| `src/main/scala/nebflow/core/entity/EntityTypes.scala` | NodeRoute L145-209；FlowNode L303-313；FlowDagDef L352-368；FlowParamSpec L404-410 |
| `src/main/scala/nebflow/core/tools/FlowTriggerTool.scala` | validateFlowParams L84-124；call L126-183 |
| `src/main/scala/nebflow/core/flow/FlowDagRunner.scala` | RunFlow L21-34；执行+回传 L36-75 |
| `src/main/scala/nebflow/agent/AgentCore.scala` | fixedToolsFor L1733-1738（#381 先例）；buildAllowedToolSet L1268-1329（leaf 剥离 L1310-1321） |
| `src/main/scala/nebflow/core/skill/SkillService.scala` | buildPerAgentFlowCatalog L252-265 |
| `~/.nebflow/flows/entity-creator/flow.json` | 预定义 flow 创建流程（architect→builder→reviewer，maxLoop=3）——阶段 4 转 skill 对象 |
| `~/.nebflow/flows/<name>/flow.json` ×9 | 存量预定义 flow——阶段 3 迁移对象 |
| `~/.nebflow/flows/GUIDE.md` | 「Flow Design Guide (v2)」——阶段 5 归档对象 |
| fanout 提交 | `8520810f`（引擎动态 fanout）+ `e043ca20`（触发 params） |
