# R4 — Flow 设计指导：什么是好的 Flow

> 四实体生态设计讨论报告 · 2026-08-14 · 纯分析文档，不改代码
> 对应用户反馈第 6 点（防过分拆分、单节点合法性）与第 8 点总纲（判断条件 / 收敛 / 创建指导）
> 基线：/tmp/entity-ecosystem-design.md §2（实体判定表 E3）为本报告的上游框架

---

## 1. 用户观点解读

用户原话：

> "比如 Flow，如何避免过分的拆分节点？Flow 也可以只有一个 agent。"

以及第 8 点总纲：

> "架构框架讨论好，但是更重要的是规则和指导。什么是一个好的 skill flow Team Agent？判断条件是什么？如何收敛？如何才能创建一个好的？"

拆解出五个诉求：

| # | 诉求 | 本报告对应 |
|---|------|-----------|
| U1 | **防过分拆分**：节点数不是越多越专业，拆分有真实成本 | §3.2 拆分成本清单 |
| U2 | **单节点 flow 合法**：1 个节点的 flow 不应被歧视，它有存在价值 | §3.4 |
| U3 | **判断条件**：好的 Flow 要有可检查的判据，不是玄学 | §4 checklist |
| U4 | **收敛设计**：flow 要有明确的完成判定，不能跑飞 | §3.3 |
| U5 | **可操作的创建指导**：产出能直接进 entity-creator 的文档 | §5 |

U5 是总纲精神的核心：架构讨论（四实体怎么分）已经差不多了，现在缺的是**工程纪律层**——让每个创建 flow 的 agent（entity-creator 的 architect 节点）有一把可执行的尺子。

---

## 2. 现状核实（代码与数据事实）

### 2.1 现有 flow 的节点数分布（~/.nebflow/flows/ 全量 7 个）

| flow | 节点数 | maxLoop | 结构（agents） | 触发者 |
|------|-------|---------|---------------|--------|
| code-review | 3 | 5 | scanner→reviewer→fixer（fixer→reviewer 回边，check-fix 循环） | Nebula（flows 白名单） |
| entity-creator | 3 | 3 | architect→builder→reviewer（verdict 循环） | Nebula / entity-creator flow |
| git-merge | 3 | 3 | scanner→evaluator→merger | — |
| memory-consolidation | 2 | 3 | scanner→consolidator | — |
| nebflow-review-merge | 3 | **1** | scanner→reviewer→merger | Nebula |
| release-stable | 2 | 3 | reviewer→coder | Nebula |
| research | 3 | 3 | researcher→analyst→reviewer | — |

分布特征：**全部 2-3 节点、DAG 深度 ≤3、每节点恰好一个动词式职责**（scan/review/merge、research/synthesize/verify）。实践中已经收敛在"小 flow"形态——用户担心的"过分拆分"目前没有发生，但没有任何机制阻止它发生（见 2.4）。

### 2.2 执行模型：上下文如何流动（拆分成本的物理根源）

FlowDagExecutor（`FlowDagExecutor.scala`）关键事实：

- **每个节点 spawn 一个全新的 AgentActor**（独立 session `dag-<flow>-<node>-<时间戳>`，`FlowDagExecutor.scala:412, 442-459`），节点结束后 session 即删（`:486`）。**节点之间零共享状态、零记忆**——唯一的上下文通道是 input 模板。
- **上下文传递是窄带显式的**：`$task` 与 `$<nodeId>.output` 模板替换（`:78-88`），全文替换不截断。**没写进模板的信息对下游节点等于不存在**——下游 agent 不知道上游"看过什么文件、试过什么方案、放弃了什么"。
- verdict 路由：FlowReport 工具结构化上报（verdict 必须是 flow.json 声明的 case key 之一，"the flow's contract is the single authority"，`FlowReportTool.scala` description），无 FlowReport 时三级 fallback（JSON 字段 → 文本 regex → substring 容忍匹配+警告，`FlowDagExecutor.scala:551-585`）。
- 每个节点注入的是 flow 局部 agent（`flows/<name>/agents/<agent>/`，可 extends 全局 agent 的 prompt——memory-consolidation 的 consolidator 直接复用 Coder 的 system.md，scanner 复用 Explorer，`EntityLoader.scala:168-173`）。

### 2.3 超时与失败半径

- **NodeTimeout = 5 分钟**（`FlowDagExecutor.scala:34`），节点超时→NodeResult 失败→默认 onError=Stop 整个 flow 失败。**不可按节点配置**。
- **GlobalFlowTimeout = 30 分钟**（`:37`），IO.race 全局兜底。
- **maxLoop 按 edge 计数**（loopKey `from->to`，`:178-186`），超过即失败——这是防死循环的，不是防规模的；节点再多不循环就不会被它拦。
- **今天（2026-08-14）的两次事故**（用户报告）：nebflow-review-merge 两次节点超时。结构上完全可解释：reviewer/merger 节点要跑编译+测试+合并+清理（重 IO 任务），5 分钟硬上限对单节点过紧；且 maxLoop=1 意味着零重试，一次超时=整个 pipeline 失败。同日日志另有 code-review flow 无法正常结束的排查记录（2026-08-14 09:35）及一次 `Max loop (3) exceeded at edge remaining=0`。
- 失败半径：串行链 N 个节点，任一节点失败（且 onError 未配置 resume/restart）→ 前面所有节点的工作白费（节点输出仅存于内存 ctx.nodeOutputs，flow 失败即丢）。

### 2.4 约束现状：几乎为零

- FlowDagDef 解码**无任何节点数/结构校验**（`EntityTypes.scala:227-235`：name/description/nodes/entry/maxLoop，完）。
- **单节点 flow 代码上完全支持**：entry 节点 + `onComplete: "$return"` → 直接返回（NodeRoute.Return，`EntityTypes.scala:126`；route 分支 `FlowDagExecutor.scala:175-176`）。无最小节点数限制。
- flow.json 里没有显式 edges 字段——路由内嵌于各节点 onComplete（Goto/Switch/Return），前端 DAG 图由 executor 动态推导（`FlowDagExecutor.scala:295-309`）。
- 触发入口唯一：DelegateTool(flow=...)，且强制 flows 白名单（agent 未声明该 flow → 拒绝，`DelegateTool.scala:176-181`）。
- system-prefix-for-flows.md 近乎空文件（3 行注释），执行规则在 FlowAgentActivator 注入——**不存在任何"如何设计 flow"的指导文档**。

---

## 3. 设计建议（含权衡）

### 3.1 好的 Flow 的本质定义（判断的锚）

> **Flow 是"值得固化路由的协作模式"**：≥2 个步骤，顺序/分支可预先枚举，输入输出同构，且这个模式会被反复以相同形状调用。

三个可证伪的判据（不满足任一条就不该建 flow）：

1. **路由可枚举**：你能**现在**画出它的节点图和每个分支的 case keys——画不出来说明流程还没定型，该用 agent/团队 emergent 协作去探索，定型后再固化。
2. **输入输出同构**：每次调用的 $task 形状一致（都是"review 一个 PR"/"merge 一个分支"），节点的 input 模板不需要为不同调用重写。
3. **复用频次**：这个形状的流程预期执行 ≥N 次（基线 E3 的说法："愿意用它 maxLoop 次以上"）。一次性流程用一次性的东西（单 agent 或 ad-hoc Mail 链），固化是负债。

### 3.2 防过分拆分：拆分的真实成本清单

用户问"如何避免过分拆分"。答案不是"规定 ≤N 节点"，而是**让拆分的成本可见**——每个多出来的节点付出如下真实代价：

| 成本 | 机制根源 | 量化感受 |
|------|---------|---------|
| **上下文窄带损耗** | 节点间仅 `$node.output` 模板传递，全新会话零记忆（§2.2） | 每跳丢弃"过程性知识"（试错、排除项、文件印象）；跳数越多，末端节点对任务的理解越稀薄 |
| **串行延迟叠加** | DAG 串行链 + 每节点冷启动（新 actor、新 system prompt、重新理解任务） | 每节点至少多一轮完整的"读题-干活-汇报"；深度 d 的链 ≈ d 倍冷启动 |
| **超时失败半径** | NodeTimeout 5min 不可配，任一节点超时→整 flow 失败，中间产物不落盘（§2.3） | N 节点串行链的失败概率 ≈ 1-∏(1-pᵢ)；今天的 review-merge 事故即此 |
| **契约维护面** | 每个 switch 节点是一份 verdict 契约（case keys），每个 input 模板是一份上下文契约 | 每多一个节点 = 两份要同步维护的隐式契约；契约漂移 = flow 静默路由错 |
| **审查/认知成本** | 人或 entity-creator reviewer 要理解整个 DAG 才能改任何一环 | 7 个现存 flow 全部 ≤3 节点可一眼读完；节点翻倍则修改恐惧症出现 |

**拆分的正当理由只有三个**（checklist 反向使用）：
1. **关注点强制分离**：下游必须不带上游的偏见（builder ≠ reviewer 的 Build vs Verify 分离）——靠新会话的"无记忆"特性实现，这是 flow 相对单 agent 多轮的唯一不可替代优势。
2. **不同角色/工具面/模型**：节点需要不同的 tools 白名单或 model（scanner 只读、merger 可写）。
3. **收敛控制点**：需要在中途设一个结构化 verdict 关卡决定"继续/回环/终止"。

不满足这三条而拆的节点（如"先分析再分析"、"取数→洗数→算数"同角色三连），本质是把一个 agent 的多轮思考硬拆成三个失忆患者传话——**应合并为单节点内多轮完成**。

### 3.3 收敛条件设计（U4：如何收敛）

flow 的收敛 = 每个节点都知道自己何时结束、整个 DAG 知道自己何时返回。三层设计：

1. **节点层：verdict 枚举闭包**。switch 节点的 cases 必须覆盖决策空间且以 `$return` 兜底（默认分支或显式 default）。现存实践的样板：code-review 的 reviewer（pass→$return / fix→fixer）、nebflow-review-merge 的 reviewer（merge→merger / reject→$return / default→$return）。**case keys 是流程契约**，FlowReport 强制 verdict ∈ case keys（FlowReportTool 契约语义），无 switch 的顺序节点统一 "done"。反面模式：case 只写"继续"不写"终止"——所有路径都前进的 DAG 没有收敛保证，全靠 maxLoop 兜底爆掉（entity-creator 曾出现 Max loop exceeded 即此类边缘）。
2. **回环层：maxLoop 与循环语义匹配**。check-fix 循环（code-review）maxLoop=5 是"允许打回重修 5 次"；单程 pipeline（review-merge）maxLoop=1 是"不允许任何回环"。**每个回边都必须能回答"这个循环最多转几圈、转完没收敛算谁的"**。回边 ≥2 个的 flow 需要在 flow.json 之外写文档解释每个循环的退出条件。
3. **全局层：失败也是收敛**。onError 三策略（resume/restart/stop，`EntityTypes.scala:159-181`）目前所有现存 flow 均未配置（默认 stop）。指导原则：可重试的瞬时失败（网络、超时）→ restart + maxRetries≥1；有替代路径的 → resume；只有不可恢复的才 stop。今天 review-merge 的教训之一：重编译类节点应当 restart 而非裸 stop，或未来把 NodeTimeout 做成节点级可配（见 §6）。

### 3.4 单节点 flow 的合法场景（U2）

**结论：单节点 flow 合法且有独特价值，代码已支持（§2.4），应写进指导文档而非禁止。**

单节点 flow = "带 flow 基础设施的受控调用入口"。它比裸 Delegate(agent) 多出：

| 能力 | 单节点 flow | 裸 Delegate(agent) |
|------|------------|-------------------|
| flows 白名单权限（谁能触发谁不能） | 有（DelegateTool 校验） | 无（任何有 Delegate 的 agent 可调任何 agent） |
| 前端 DAG 可视化 / flowStarted/Completed 事件 | 有 | 无（只有 delegate 弹窗） |
| RunningFlowRegistry 状态追踪 / 取消 | 有 | 部分 |
| 未来加步骤不用改调用方（入口稳定性） | 是——调用方只认 flow 名 | 加步骤=改所有调用方 |

**正当场景**：(a) 权限边界——一个昂贵/危险 agent（如 release coder）只应经特定入口调用；(b) 入口预留——预期流程会长大（review→review+fix），先立 flow 名再扩节点，调用方零改动；(c) 统一观测——把某类操作全部流量过 flow 事件管道。**不正当场景**：纯为"有流程感"而包装——那就是过度设计，直接 Delegate。

**边界辨析（何时 Flow / 何时单 agent / 何时 Team）**——决策树（可直接并入基线 §2.2 判定表之后）：

```
需求是一个可复用的调用模式吗？
├─ 否（一次性） → 直接做 / Mail 一个成员
├─ 是 → 步骤顺序能预先枚举吗（现在就能画出 DAG 和 case keys）？
│    ├─ 否 → 先用 agent/team 跑 2-3 次，定型后再回来建 flow
│    └─ 是 → 需要多次调用间共享记忆/身份吗？
│         ├─ 是 → Team（agent 持久在场，Mail 可寻址）
│         └─ 否 → 每次调用独立完成？
│              ├─ 是且单角色 → Agent（可被 Delegate/Mail），
│              │    除非需要 flow 的权限/观测/入口稳定性 → 单节点 Flow
│              └─ 是且 ≥2 角色/需中途 verdict 关卡 → 多节点 Flow
```

### 3.5 节点数指导：软指导为主 + 硬上限兜底

**指导值（写文档）：默认 2-4 节点，DAG 深度 ≤4，>5 节点必须逐节点论证拆分正当性（对照 §3.2 三理由）；单节点合法。** 依据：现存 7 个 flow 全部 2-3 节点且运转良好；深度每 +1，上下文损耗与失败半径非线性上升；而 flow 的表达力（switch+回环）在 3-4 节点内已足够表达 review-fix 类复杂模式。

**硬上限（进代码 lint）：建议加载/创建时对 >8 节点或深度 >6 的 flow 出 warning（不是 reject）。** 理由见 §6 开放问题——warning 兼得"防呆"与"不挡合法长流程"。

---

## 4. Flow 设计 checklist（产出物：可直接进 entity-creator 指导文档）

> 用途：entity-creator 的 architect 节点设计前逐条自检，reviewer 节点验收时逐条对照。每条二值判定。

**A. 该建吗（存在性）**
- [ ] A1 路由可枚举：我能现在画出完整节点图 + 每个 switch 的全部 case keys
- [ ] A2 输入同构：未来每次调用的 $task 形状与今天一致
- [ ] A3 复用频次：预期同形状调用 ≥3 次（一次性的不建）
- [ ] A4 不该是别的实体：不需要跨调用记忆（否则 Team）；单角色且无需权限/观测/入口预留（否则 Agent 或单节点 Flow）

**B. 节点设计（防过分拆分）**
- [ ] B1 每个节点一个动词式职责（scan / review / merge），能用 ≤5 个词说清
- [ ] B2 每个节点通过 §3.2 三理由之一自证存在（关注点分离 / 角色差异 / 收敛关卡）——说不出来的节点合并进邻居
- [ ] B3 节点数 ≤5（>5 逐节点书面论证）；深度 ≤4；单节点（=1）同样合法
- [ ] B4 同一 agent 不得既做 builder 又做 verifier（Build vs Verify 分离，基线 R4 条款）

**C. 契约设计（上下文与收敛）**
- [ ] C1 每个节点的 input 模板显式列出它需要的全部上游输出（$x.output）；下游"需要但模板没给"的信息 = 设计缺陷
- [ ] C2 每个 switch 节点：case keys 覆盖决策空间、含终止路径（→$return 或 default:$return）；无 switch 的节点 verdict="done"
- [ ] C3 每条回边（循环）写明退出条件与 maxLoop 值的对应关系；单程 pipeline maxLoop=1
- [ ] C4 onError 显式选择：瞬时失败类节点给 restart+maxRetries，不可恢复才默认 stop

**D. 工程现实**
- [ ] D1 每节点预估耗时 < 5 分钟（NodeTimeout 硬上限）；重编译/长任务要么拆步骤要么申请调大该节点预算
- [ ] D2 flow 总预算（节点耗时和）< 30 分钟（GlobalFlowTimeout）
- [ ] D3 flow agents 复用优先：能用 extends 全局 agent（Explorer/Coder）就不新写 system.md（memory-consolidation 先例）
- [ ] D4 description 写清 trigger 条件（"Use after an agent completes work in a worktree"式），它是 flows catalog 里的唯一路由信号

---

## 5. 实施要点

1. **落地位置**：checklist 进 entity-creator 的 architect 与 reviewer 节点 system.md（A/B 类给 architect 自检，C/D 类给 reviewer 验收）；决策树（§3.4）并入基线 §2.2 实体判定表；单节点合法性与"拆分三理由"进 skill-creator 同级的 flow-creator 知识（或 entity-decision.md 外置规则文件，基线 §2.2 的 P1 路径）。
2. **机制补强候选（P1，按今天事故优先级）**：FlowNode 增可选 `timeoutMinutes`（覆盖全局 5min 默认）——review-merge 的 reviewer/merger 需要 ≥10min；onError:maxRetries 在现存 flow 中补配置；加载时 lint（节点数/深度/无终止路径的 switch → warning 日志）。
3. **验收锚点**：entity-creator 收到一个"6 节点同角色串行"的 flow 提案 → architect 或 reviewer 按 B2 主动合并为 ≤3 节点并给出理由；收到单节点提案 → 不以"节点太少"为由驳回，按 A4 判定；今天 review-merge 场景改造后（timeoutMinutes + restart）重跑一次真实 worktree merge 不再整体失败。

## 6. 开放问题（附倾向）

| 问题 | 选项 | 倾向 |
|------|------|------|
| 节点数上限：硬约束还是软指导 | 代码校验拒绝 / 代码 warning / 纯文档 | **文档软指导（≤5）+ 代码 warning（>8/深度>6）**。硬 reject 会挡住未来的合法长流程（如 9 步发布流水线），纯文档又对 agent 无强制力；warning 进日志/reviewer 输出，成本一行、防呆有效。校验放 flow 加载或 entity-creator builder 侧，不进运行时热路径 |
| NodeTimeout 是否节点级可配 | 保持全局 5min / flow.json 节点级 timeoutMinutes | **节点级可配**。今天两次事故的直接根因之一就是"重编译节点被通用超时误杀"；5min 对 LLM-only 节点宽裕、对编译测试节点致命，一刀切两头不讨好。上限仍受 GlobalFlowTimeout=30min 约束 |
| 失败节点输出是否落盘 | 现状丢弃 / 失败时把 nodeOutputs 快照到日志 | **落盘快照**（低成本高价值）：flow 失败时 5 个节点的中间产物全丢，retry 只能全重跑；快照进日志或 FlowReportStore 可支撑断点重试的 P2 演进 |
| verdict 契约漂移检测 | 无 / entity-creator reviewer 对照 agent system.md 与 case keys | **reviewer 增一项 C2 检查**（agent prompt 里宣称的 verdict 词 vs flow.json case keys 的一致性）；运行时已有三级 fallback 但那是容忍不是校验 |
| 单节点 flow 与 Agent 的目录重复问题 | 允许 flows/<name>/agents/ 与全局 agents/ 同名 extends | 维持现状（loadFlowAgent 回退全局已优雅解决）；不建议为单节点 flow 设特殊短格式 JSON——两种格式两种解析路径的维护成本 > 省几行 JSON |
