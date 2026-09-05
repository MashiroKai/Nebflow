# R1 — Delegate 工具拆分：flow 触发独立成工具

> 2026-08-14 · 生态设计讨论报告 · 基线：/tmp/entity-ecosystem-design.md
> 用户观点（原话）："我建议把Delegate工具拆分，触发flow和agent分开为两个工具，Delegate只触发agent。"

---

## 0. TL;DR

| 项 | 结论 |
|---|------|
| 判断 | **同意拆分，且代码现状强烈支持**。Delegate 的 flow 分支与 agent 分支共享的只有参数解析头（约 10 行），运行时是完全不同的两条机制链（FlowDagRunner vs AgentActor） |
| 新工具命名 | **FlowTrigger**（备选 RunFlow / TriggerFlow，见 §3.1） |
| 参数收敛 | Delegate 删 `flow` 参数剩 6 个；FlowTrigger 只要 `flow` + `prompt`（2 个） |
| 权限建议 | FlowTrigger **不进** NebulaExclusiveTools，改由 `agent.json flows` 白名单驱动（机制已存在，EntityTypes.scala:25）——这是给 R2"Team 自建 flow 并触发"预留的口子 |
| 顺带修复 | 拆分同时修掉一个**现存 bug**：`/flow-name` slash 触发教 agent 用 `Mail(flow)`，而 Mail 根本不路由 flow（MailTool.scala:57） |
| 代价 | 中等。约 6 个代码文件 + 3 处 prompt 文案；前端无硬编码工具名，零改动 |

---

## 1. 用户观点解读

用户要求把 Delegate 的两种触发目标拆开：

1. **语义纯净**。Delegate 的 description 自称 "Spawn a background sub-agent"（DelegateTool.scala:84），但 `flow=` 分支根本不 spawn sub-agent——它启动一个一次性 DAG 流水线。"委派一个 agent" 和 "触发一条流水线" 是两个动词，应该是两个工具。
2. **参数解耦**。当前 7 个参数中，`agent`/`flow` 互斥（173-174 行专门写互斥校验——互斥参数本身就是拆分信号），`fork`/`lifecycle`/`taskDescription` 只对 agent 分支有意义。LLM 每次调用都要在 schema 里理解一堆无关参数。
3. **权限分离**。agent 委派是"算力/角色扩展"，flow 触发是"编排调度"——后者的权限敏感度更高（一条 flow 会连续驱动多个 agent）。拆开后两个权限维度可以独立演进（flow 触发放权给 Team，agent 委派保持 Nebula 专属——或反之）。
4. **这是拆分故事的第二章**。2026-08-11 已把 team 成员场景拆成 SubTaskTool（commit 2ea6e156 "Delegate 拆分方案 A P1+P2"）；本次把 flow 触发拆出，Delegate 最终收敛为"Nebula 专属的 standalone agent 委派器"，与 SubTaskTool.scala:12 注释里预留的架构叙事完全一致。

---

## 2. 现状核实（代码事实）

### 2.1 Delegate 当前签名与代码分叉

`DelegateTool.scala`，参数 7 个（inputSchema L111-149）：`prompt`* / `description`* / `fork` / `lifecycle` / `taskDescription` / `agent` / `flow`。

`call()` 内三个分叉（L163-314）：

| 分叉 | 行号 | 做什么 |
|------|------|--------|
| 校验头 | L172-174 | prompt 非空；`agent`+`flow` 互斥检查 |
| **flow 分支** | L175-212 | ① `agentDef.flows` 白名单校验（L177-181）② 解析 callerRootSession（L186-191）③ `EntityLoader.loadFlow`（L192）④ spawn `FlowDagRunner` 并发 `RunFlow`（L194-206） |
| **agent 分支** | L213-314 | ① MaxDepth 检查（L213）② resolveAgent：`agent=` 指 standalone / 省略则 self-clone（L219-239）③ safetyMode + callerRoot（L259-268）④ spawnPersistent / spawnBackground（L272-307） |

### 2.2 两条路径的运行时差异（拆分的机制依据）

| 维度 | flow 分支 | agent 分支 |
|------|----------|-----------|
| 运行时实体 | `FlowDagRunner` 一次性 actor（FlowDagRunner.scala:33-64） | `AgentActor` sub-agent + BackoffSupervisor |
| 深度递归 | **不检查 MaxDepth**（flow 节点内的 agent 由 FlowDagExecutor 另管） | `depth+1`，≥5 拒绝（L213, AgentCore.scala:21） |
| 结果交付 | `ImmediateInput` 注入 caller 回合（FlowDagRunner.scala:54-62） | `ExternalEvent` 事件通知（L452-469） |
| 生命周期 | runner 自停（Behaviors.stopped） | ephemeral / persistent 两种 + 崩溃重启 |
| 权限声明 | `AgentEntry.flows` 白名单（EntityTypes.scala:25） | `AgentLibrary` 按 category="standalone" 校验（L225-228） |

除了共用 `ctx` 和返回格式，两分支没有任何共享状态——**拆分是纯提取，无逻辑纠缠**。

### 2.3 Nebula 专属机制

- `NebulaExclusiveTools = Set("Schedule", "Delegate")`（AgentCore.scala:1167-1170）；非 Nebula agent 一律剔除（AgentCore.scala:890），SubTask worker 再显式剥离（L914）。
- SubTask 拆分先例：SubTaskTool.scala:12 注释 "Delegate split, 方案 A"，commit 2ea6e156（2026-08-11）+ 工具注入跟进 2ef8be3a（2026-08-13）。nebflow-project Manager 的 agent.json tools 已含 `SubTask`、不含 `Delegate`。
- **先例的教训——prompt 漂移**：拆分后 `~/.nebflow/prompts/system-prefix-for-teams.md:116` 仍有一整段 "## Parallel Work — Use Delegate" 教团队成员用 Delegate，但工具层根本不给（Nebula 专属过滤）。**本次拆分必须把 prompt 同步改写列进验收条件**，避免重蹈覆辙。

### 2.4 flow 触发的其它路径与现存矛盾

| 路径 | 现状 | 问题 |
|------|------|------|
| `/flow-name` slash | WebSocketRoutes.scala:3622-3642 `executeSkill`：先 `loadFlow` 探测，是 flow 则注入指令 `Mail("$skillName", "$safeInput")` | **Mail 不路由 flow**——MailTool.scala:57 明文 "For triggering flows ... use the Delegate tool instead"。agent 照做会收到路由失败，再自行改用 Delegate（多一轮试错） |
| flow catalog 注入 | ContextRefresher.scala:364-366 → SkillService.buildPerAgentFlowCatalog（SkillService.scala:209-220） | catalog 只列名字+description，**不告诉 agent 用什么工具触发** |
| 过时注释 | FlowDagRunner.scala:14 "Spawned by MailTool when a Mail targets a flow name"；RunningFlowRegistry.scala:10 "triggered via Mail" | Mail 触发路径已不存在（全仓 grep 确认 FlowDagRunner 唯一 spawn 点是 DelegateTool.scala:197） |
| team.json `flows` 字段 | nebflow-project/team.json 写了 `"flows": ["code-review","git-merge"]` | **TeamDef 无此字段**（EntityTypes.scala:79-84），Decoder 静默忽略，全仓无消费点——team 级 flow 声明是装饰品 |

### 2.5 权限声明机制现状

`AgentEntry.flows`（agent.json 的 `flows` 数组）是唯一的 flow 触发白名单，DelegateTool.scala:178 强制校验。Nebula 声明了 `["code-review","release-stable"]`。该机制与触发工具解耦——**换触发工具不需要动白名单语义**。

---

## 3. 设计建议

![Delegate 拆分前后对比](/tmp/r1-delegate-split.svg)

### 3.1 新工具命名

| 候选 | 优 | 劣 | 判断 |
|------|----|----|------|
| **FlowTrigger**（推荐） | 动宾结构，与 TaskCreate/TaskUpdate 命名风格一致；语义无歧义 | 无 | ✓ |
| RunFlow | 与 FlowDagRunner.RunFlow 内部消息同名，代码检索会混淆 | | ✗ |
| TriggerFlow | 等价 FlowTrigger | 动词开头不符合现有工具命名习惯（Read/Write/Mail/Load 均非动词开头…其实 Mail/Load 是） | 可接受 |
| Flow | 最短 | 过泛，catalog/上下文里 "flow" 是高频普通名词 | ✗ |

### 3.2 参数收敛

| 工具 | 参数 | 说明 |
|------|------|------|
| **Delegate（收敛后）** | `prompt`* / `description`* / `agent` / `fork` / `lifecycle` / `taskDescription` | 删除 `flow`；遇到 `flow=` 返回引导性错误："use FlowTrigger" |
| **FlowTrigger（新）** | `flow`* / `prompt`* | description 不需要——RunningFlowRegistry 的展示文案取自 flowDef 自身的 description（RunningFlowRegistry.scala:27） |

FlowTrigger 复用 DelegateTool flow 分支的全部逻辑（flows 白名单校验、callerRoot 解析、loadFlow、spawn runner），整段代码迁移即可；`agent+flow 互斥` 校验随之消失（不同工具天然互斥）。

### 3.3 权限归属（本次拆分的核心收益点）

三个选项：

| 选项 | 内容 | 影响 |
|------|------|------|
| A. 维持 Nebula 专属 | FlowTrigger 加入 NebulaExclusiveTools | 权限模型不变，拆分只有语义收益；R2 的 "Team 自建 flow" 依然无法落地 |
| **B. flows 白名单驱动（推荐）** | FlowTrigger 不进 NebulaExclusiveTools；任何 agent 的 agent.json `flows` 数组非空即自动获得该工具（buildAllowedToolSet 按 `flows.nonEmpty` 注入，与 BaseTools 同模式） | 白名单机制已存在（DelegateTool.scala:178 校验照搬）；放权粒度 = 声明粒度；Team 想触发 flow 只需给自己的 agent 声明 flows——R2 的关键前置 |
| C. 完全开放（工具在即允许） | 无白名单 | 违背最小权限；放弃现有机制 | 

推荐 B 的理由：零新概念（flows 字段已存在、校验代码已存在）、权限收敛在一处（agent.json）、为 Team 自建+自触发 flow 打通机制链。Nebula 的行为完全不变（已声明 flows）。

**注意**：选项 B 下 SubTask worker 仍应剥离 FlowTrigger（AgentCore.scala:914 的剥离集合加一个名字）——worker 是叶子，不该触发流水线。

### 3.4 与 `/flow-name` slash 触发的统一

拆分后 slash 路径的指令模板改为：

```
Trigger the "code-review" flow:
FlowTrigger(flow="code-review", prompt="...")
Wait for the flow's reply and report the results.
```

同时把 FlowDagRunner.scala:14、RunningFlowRegistry.scala:10 的过时 "via Mail" 注释一并修正。**另一种更彻底的方案**（slash 不经 LLM、后端直接 spawn FlowDagRunner）留作开放问题——它省一次 LLM 调用，但丢掉 agent 中介的参数加工能力。

### 3.5 影响面盘点

| 层 | 改动 | 文件 |
|----|------|------|
| 工具实现 | flow 分支迁出 → 新文件 FlowTriggerTool.scala；Delegate 删分支+删参数 | DelegateTool.scala、新 FlowTriggerTool.scala |
| 注册 | ALL_TOOLS 加 FlowTrigger；注释更新 | registry.scala:42-45 旁 |
| 权限过滤 | （选 B）flows.nonEmpty 注入逻辑 + worker 剥离集合 | AgentCore.scala:880-917, 914, 1167-1170 |
| slash 指令 | Mail(flow) → FlowTrigger(flow) | WebSocketRoutes.scala:3629-3642 |
| catalog 文案 | flowCatalog 加触发方式说明 | SkillService.scala:209-220 |
| Nebula 教学 | `Delegate(flow="flow-name")` 三处改为 FlowTrigger | ~/.nebflow/agents/Nebula/system.md:30-35, 90-92, 98 |
| entity-creator 教学 | 工具清单里 Delegate 的说明拆开 | entity-creator/agents/architect/system.md:184 |
| 前端 | **零改动**——typescript/src 无 "Delegate" 硬编码（已 grep 确认），工具名数据驱动（ToolRegistry.builtinToolNames 自动含新工具） |
| 过时注释 | "via Mail" ×2 | FlowDagRunner.scala:14、RunningFlowRegistry.scala:10 |

---

## 4. 与现状差距

1. DelegateTool 单文件双语义（现状）→ 两工具（目标），纯提取式重构，无逻辑纠缠。
2. flow 触发权限 = Nebula 专属（Delegate 捆绑）→ flows 白名单驱动（目标），机制已备，差一次接线。
3. slash 触发教错工具（现存 bug）→ 指向 FlowTrigger（目标）。
4. flowCatalog 无触发指引 → 一行文案（目标）。
5. team.json `flows` 字段无消费 → 是否借本次接活（见开放问题 2）。

## 5. 实施要点

**文件级清单**（P1 一次提交可完成）：
1. 新建 `src/main/scala/nebflow/core/tools/FlowTriggerTool.scala`（迁移 DelegateTool.scala:175-212 + 校验头）
2. `DelegateTool.scala`：删 flow 参数与分支；`flow=` 传入时报 "Use FlowTrigger"
3. `registry.scala`：注册 FlowTrigger
4. `AgentCore.scala`：buildAllowedToolSet 按 `flows.nonEmpty` 注入 FlowTrigger（方案 B）；isSubTaskWorker 剥离集合加 FlowTrigger
5. `WebSocketRoutes.scala` executeSkill：指令改写
6. `SkillService.scala` buildPerAgentFlowCatalog：加 "Trigger via FlowTrigger(flow=...)" 文案
7. prompt 三处：Nebula system.md、entity-creator architect system.md、system-prefix-for-teams.md（顺带修 SubTask 拆分遗留的 "Use Delegate" 段——它应教 SubTask）

**验收条件（二值可断言）**：
1. **冒烟**：`sbt run` 启动 → `curl /api/health` 200；`sbt test` 现有测试全绿。
2. **E2E flow 触发**：Nebula 会话输入 "用 code-review 审查当前分支" → 观察工具调用为 `FlowTrigger(flow="code-review")` → `GET /api/running-flows` 出现该实例 → 完成后 Nebula 收到 `[Flow 'code-review' completed]` ImmediateInput。
3. **E2E agent 委派回归**：`Delegate(agent="Explorer", ...)` 正常 spawn；`lifecycle="persistent"` 会话地址返回照旧。
4. **互斥残留检查**：`Delegate(flow="x")` 返回引导错误（不是静默忽略）。
5. **白名单强制**：未声明 flows 的 agent（如新建空 agent）调 FlowTrigger → 拒绝并列出 allowed 列表。
6. **slash 回归**：`/code-review review branch x` → agent 首选工具即 FlowTrigger（不再先 Mail 失败一次）。
7. **prompt 一致性**：grep 全部 prompt/agent system.md，无残留的 `Delegate(flow=` 教学。
8. **worker 隔离**：SubTask worker 的工具集不含 FlowTrigger（单测断言 buildAllowedToolSet）。

## 6. 开放问题（需拍板，附倾向）

| # | 问题 | 倾向 |
|---|------|------|
| 1 | FlowTrigger 是否 Nebula 专属？ | **不专属，flows 白名单驱动**（§3.3-B）。拆分的目的之一就是解耦权限维度；专属化留给需要时一行配置就能收紧 |
| 2 | Team 成员（非 Manager）能否触发 flow？ | 分两步：P1 仅 Manager 层放权（Manager agent.json 声明 flows 即得工具——现状 Manager 无任何 flow 触发能力）；普通成员触发等 R2 的 Team 自建 flow 体系跑稳后再放 |
| 3 | slash `/flow-name` 是否绕过 LLM 直接触发？ | 倾向**保持 agent 中介**（改指令即可）；直连省 token 但丢失参数加工与上下文关联，且 RunningFlowRegistry 的 sessionId 关联逻辑要重做 |
| 4 | team.json 的 `flows` 字段接不接活？ | 倾向**本次不接**（避免改动面扩大），在 R2 的 Team 自建 flow 设计里统一决定（成员级 flows 声明 vs team 级声明，二选一即可，两个字段并存必然漂移） |
