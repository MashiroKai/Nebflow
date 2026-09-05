# R2 — 实体创建权限模型 + entity-creator 重构 + extends 确认

> 2026-08-14 · 生态设计讨论报告 · 基线：/tmp/entity-ecosystem-design.md
> 用户观点（原话三点）：
> ① "实体创建是 Nebula 垄断，Team的创建由Nebula垄断，但是flow，skill，Team自己的成员，都应该可以由Team自己创建。"
> ② "我是建议把entity-creator拆分，或者改为分支的架构，因为创建flow，team等，还是各有侧重和区别的。"
> ③ "我记得extends机制我是移除了吧？"

---

## 0. TL;DR

| 项 | 结论 |
|---|------|
| extends | **用户记忆正确：机制已移除**。AgentDef/AgentEntry 均无此字段（EntityTypes.scala Decoder 不解析）。但 **prompt 层有 4 个文件残留 extends 模板**——entity-creator 的 architect/builder/reviewer 会照模板产出含 extends 的 agent.json，被 Decoder 静默忽略、继承不生效。必须清理 |
| 权限模型 | 现状"垄断"不是工具拦截的结果，而是**触发路径垄断**（Delegate 为 Nebula 专属 → 只有 Nebula 能启动 entity-creator）。工具层对实体文件写入**零拦截**（Write/Bash 是全员 BaseTools）。落地方案：流程把关（entity-creator callerScope）+ flows 声明放权，不动 Write 权限 |
| entity-creator 重构 | 推荐**方案 B：单 flow 内 DAG 分支架构**——architect 通用入口（含 callerScope 权限闸）→ `switch($architect.entityType)` → agent/flow/team/skill 四条 builder-reviewer 支线。用 flow 自己已经在用的 switch 原语，改动面最小、分支可视化 |
| 与 R1 的耦合 | Team 要能自建 flow 并使用，前置依赖 R1 的 FlowTrigger 放权（flows 白名单驱动） |

---

## 1. 用户观点解读

三点各对应一个设计决策：

1. **权限矩阵重画**（①）：把基线文档 D2 的三级权限改成更明确的**两垄断 + 一自建**：
   - Nebula 垄断：Team 创建（新 Team = 新权限域 + 新 lead 提名，是组织级提权面）+ 全局 standalone agent（跨 Team 共享的公共资源）
   - Team 自建：**自己的成员**（`teams/<t>/agents/`）、**flow**、**skill**——这三样都限于 Team 域内，不污染全局命名空间
   - 注意用户说的是"由 Team 自己创建"而非"由 Manager 创建"——执行者可以是 Manager 发起的 entity-creator 流程，但归属权和决定权在 Team。
2. **entity-creator 拆分**（②）：三类实体的创建"各有侧重"——agent 的重心是 tools 红线与 description 质量；flow 的重心是 DAG 有效性（死路/switch 路由/maxLoop）；team 的重心是 lead 存在性与组织合理性。单一 architect/builder/reviewer 的 prompt 要同时背负三套知识，指令互相稀释。
3. **extends 确认**（③）：这是一次记忆核对请求——答案见 §2.1，且牵出一批必须清理的 prompt 残留（这不是观点冲突，是观点与现状的**确认 + 延伸**）。

---

## 2. 现状核实（代码事实）

### 2.1 extends：机制已移除，prompt 有残留

**代码层（已移除，确认）**：
- `AgentDef`（AgentDef.scala:12-25）：字段为 name/description/tools/systemPrompt/avatar/displayName/voiceEnabled/model/preset/category/mcpServers/skills/flows——**无 extends**
- `AgentEntry` Decoder（EntityTypes.scala:30-56）：逐字段解析，无 extends——agent.json 里写了也会被静默忽略（circe 不报未知字段）
- 前端 typescript/src、现存 agent.json/team.json：无 extends 字段（grep 确认）
- 唯一代码残留：RestApiRoutes.scala:1136 过时注释 `// GET /agents/list — list all global agents (for extends dropdown)`
- 历史：extends 随 2026-08-03 大重构 dc7589eb（"unified flow engine, entity system, tool overhaul"）移除

**prompt 层（残留，有实际危害）**：

| 文件 | 残留内容 |
|------|---------|
| `~/.nebflow/flows/entity-creator/agents/architect/system.md` | L18 模板含 `"extends": "<BaseAgent>"`；L23 "optional if extends is used"；L83/96-106 整节 "The extends pattern"（教 builder 继承 system.md + tools）；L141 qa 模板 extends Explorer |
| `~/.nebflow/flows/entity-creator/agents/builder/system.md` | L39 模板 `"extends": "Coder"`；实施规则 L4 "Don't write system.md if extends is used" |
| `~/.nebflow/flows/entity-creator/agents/reviewer/system.md` | 检查清单第 4-6 条：校验 extends 父存在、extends 时 tools 可省、system.md 可继承 |
| `~/.nebflow/teams/flow-creator/agents/Entity{Architect,Builder}/system.md` | 同款残留（遗留 team） |

**危害链**：architect 按 L96-106 的 "extends pattern" 设计 → builder 照模板写 `"extends": "Coder"` 且**不写 system.md**（L4 规则）→ Decoder 忽略 extends → 新 agent 无 system prompt、无继承，**静默变成空壳**。reviewer 还在检查这个无效字段（检查通过 = 假阳性 PASS）。这不是理论风险——architect prompt 的 Design Philosophy 第 1/3/6 节（复用表格、extends 模式、验证角色模板）整个建立在已死的机制上。

### 2.2 entity-creator 现状

`~/.nebflow/flows/entity-creator/flow.json`：三节点线性 + verdict switch——

```
architect (input=$task) → builder (input=spec+$architect.output+$reviewer.output)
  → reviewer (switch $reviewer.verdict: pass→$return, revise→builder), maxLoop=3
```

三个 agent 均 category=flow、工具最小化（architect/reviewer：Read/Glob/Grep/Bash/RemoveUnnecessary；builder 加 Write/Edit）——**创建实体的真实写路径就是 builder 的 Write/Bash 直接写文件**，不经过任何专门 API。

另存在遗留 team `flow-creator`（team.json 自述 "已被 entity-creator Flow 替代，一般应拒绝新任务，仅处理遗留维护"），其成员 prompt 同样带 extends 残留。

### 2.3 "谁能创建实体"——三层现状

| 层 | 现状 | 依据 |
|----|------|------|
| **工具层（写文件）** | **零拦截**。Write/Edit/Bash/Glob/Grep/Read/Issue/RemoveUnnecessary 是全员 BaseTools（AgentCore.scala:1179-1188），任何 agent（包括每个 team 成员、每个 flow 节点 agent）都能直接写 `~/.nebflow/agents/<X>/agent.json` 并热生效（文件即插件） | AgentCore.scala:888 fixedToolsFor 对所有 category 注入 BaseTools |
| **触发层（启动 entity-creator）** | **Nebula 垄断**。唯一触发工具 Delegate 是 NebulaExclusiveTools（AgentCore.scala:1167-1170）；Manager 的 tools 列表无 Delegate（nebflow-project Manager agent.json）；slash 触发只发生在用户会话（Nebula） | DelegateTool.scala:197 是 FlowDagRunner 全仓唯一 spawn 点 |
| **prompt 层（自律）** | Manager 被明令禁止："Nebula will use entity-creator to design, create, and hot-reload the new agent into your team. **Do NOT try to create agents or edit team.json yourself**" | ~/.nebflow/teams/nebflow-project/agents/Manager/system.md:60 |

**结论：所谓"Nebula 垄断"是 prompt 自律 + 触发路径事实垄断的合谋，不是机制强制**。一个"不听话"的成员今天就能写一个 agent.json 到全局目录并立即热生效——没有任何代码会拦它。用户要的权限模型目前只有倡议效力。

**EntityLoader 写路径补充**：writeTeam/writeFlow/writeAgent/deleteAgent（EntityLoader.scala:341-377，原子写）**无任何调用方 = 死代码**。若未来要做"工具层拦截"，这些是现成的受控写入原语，但当前架构（文件即插件 + 评审流程）不依赖它们。

### 2.4 其它相关现状

- **Skill 只有全局层**：SkillService 只扫 `~/.nebflow/skills/`（SkillService.scala:189-203 buildPerAgentCatalog，agent.skills opt-in）——"Team 自建 skill"目前没有目录可落。
- **Load 工具是现成的生效关口**：type=team 时 validate + mount（LoadTool.scala:100-129），type=flow 时 validate（L135-187）。Team 自建 flow 后用 Load 校验，不需要新机制。
- **team.json `flows` 字段无消费**（TeamDef 无此字段，EntityTypes.scala:79-84）——team 级 flow 声明是装饰品（与 R1 §2.4 同一事实）。

---

## 3. 设计建议

### 3.1 权限矩阵（目标态）

| 实体 | Nebula | Team（Manager 执行） | 普通成员 |
|------|--------|---------------------|---------|
| 新 Team | **垄断**（entity-creator team 分支仅 caller=Nebula 可达） | 提名 → Nebula 执行 | 提名 → Manager |
| 全局 standalone agent（`~/.nebflow/agents/`） | **垄断** | 提名 | 提名 |
| Team 自己的成员（`teams/<t>/agents/`） | 可（监管） | **自建**（entity-creator agent 分支，强制 team 域落盘） | 提名 |
| Flow（team 域或全局） | 可 | **自建**（entity-creator flow 分支） | 提名；建成后触发权由 flows 白名单决定 |
| Skill（team 域） | 可 | **自建**（entity-creator skill 分支；P1 先建 `teams/<t>/skills/` 目录机制） | 建议同样放行（写文件零门槛，见 §3.2 论证） |
| 红线字段（tools/mcpServers/model/preset/lead） | 可 | **一律不可**（reviewer 强制检查） | 不可 |

与基线文档 D2（三级：成员自服务 → Manager 域内 → Nebula 全局）相比，本矩阵是它的收敛版：把"Manager 域内创建"明确为"Team 域内创建"（执行者不限于 Manager），把 skill 的地位从 L0 边角料提为正式分支。

### 3.2 权限怎么落地——三个机制选项

| 选项 | 做法 | 评估 |
|------|------|------|
| M1. 工具层拦截 | 新增 EntityWrite 工具，实体文件只能经它写；Write/Bash 屏蔽 `~/.nebflow/{agents,teams,flows}/` 路径 | **否**。Bash 天然绕过路径屏蔽；Write 变重；违背"文件即插件"架构（基线 §3.2 方案 A/B 已否决过同类思路）；且现状全员可写已是既成事实，收回成本高 |
| M2. 目录写权限 | 文件系统层（权限位/沙箱）限制 | **否**。单用户本机部署（~/.nebflow 属主即运行用户），OS 权限无区分度；过度工程 |
| **M3. 流程把关 + 声明放权（推荐）** | ① 正门：创建走 entity-creator v2，architect 强制 callerScope（caller=Manager → 只能 team 域 + 拒绝 team/全局实体），reviewer 检查红线字段；② 触发权：Team 触发 entity-creator 的能力 = Manager agent.json `flows: ["entity-creator"]`（R1 拆分后 FlowTrigger 白名单驱动，机制现成）；③ 生效闸：新建 Team 须经 Load mount（validate + mount 已有）；④ 漏网之鱼：成员绕过流程直接写文件——接受（本机单人系统，绕过 = 用户自己的意志），但 memory-consolidation 式巡检 flow 可事后发现未登记实体 | 与基线"机制进代码、策略进 prompt"一致；不新增强制层；把"垄断"从 prompt 自律升级为**流程内强制**（callerScope 是 architect 的输入约束，不是倡议） |

M3 的第 ② 点是关键杠杆：**"谁能创建"的机制本质 = "谁能触发创建流程"**。R1 的 FlowTrigger 白名单放权一落地，Manager 就能自主启动 entity-creator（在 callerScope 约束内），Nebula 从"唯一执行者"退为"Team 创建 + 全局实体的守门人"。

### 3.3 entity-creator 重构——三个方案对比

![entity-creator v2 分支架构](/tmp/r2-entity-creator.svg)

| 方案 | 形态 | 优 | 劣 |
|------|------|----|----|
| A. 拆成多个独立 flow（agent-creator / flow-creator-v2 / team-creator / skill-creator） | 4 个 flow.json，各自三段式 | 每个 flow 完全特化；单 flow 失败不影响其它 | 入口分裂（caller 要先选 flow——选择逻辑被推给上层 prompt）；architect 的通用知识（复用判定、description 质量、callerScope）复制 4 份；catalog 里多 4 个条目 |
| **B. 单 flow 分支架构（推荐）** | 保留 entity-creator 单入口；architect 通用（需求分析 + 复用判定 + callerScope 闸 + 输出 entityType）；flow.json 用 `switch($architect.entityType)` 路由到 4 条 builder→reviewer 支线（agent/flow/team/skill），各支线 verdict 循环独立 | ① switch 路由原语已被本 flow 自身验证（reviewer verdict 就是 switch）；② 分支在 DAG 面板可视化、可审计；③ architect 知识单点维护；④ caller=Nebula 时 team 支线才可达——**权限矩阵直接编进 DAG 拓扑**；⑤ 对用户"拆分或分支"二选一的折中：逻辑上拆了（8 个特化 agent），结构上是分支 | flow.json 变大（4×2 节点 + 4 个 switch）；8 个 agent 目录的维护面 |
| C. 保持三 agent，prompt 内按 entityType 分段 | architect/builder/reviewer 各自 system.md 加分支指令段 | 改动最小 | 一个 agent 背 4 套知识，正是用户说"各有侧重和区别"要解决的问题；无结构可见性；callerScope 只能靠 prompt 纪律 |

**推荐 B 的核心理由**：用户的原话"拆分，**或者**改为分支的架构"本身就容许分支形态；而 B 把权限模型（§3.1）变成了**图的拓扑**——team-builder 只能从 caller=Nebula 的路径到达，这不是 prompt 倡议而是结构事实。同时它复用了 entity-creator 已验证的全部机制（switch、verdict 循环、maxLoop、FlowReport 契约）。

**v2 的三处实质增强**（承接基线 §3.2 entity-creator v2，略去与本报告无关的备份/trial 细节）：
1. **callerScope 闸（architect 输入约束）**：caller=Manager → 所有产出强制 `teams/<caller-team>/` 域内 + 拒绝 team/全局实体请求（回复"Team 创建请经 Nebula"）；caller=Nebula → 全域。caller 身份由触发路径天然保证（只有 lead 能上行、只有声明者能触发 flow），不需要新的认证机制。
2. **红线检查（reviewer 各支线强制项）**：tools/mcpServers/model/preset/lead 字段变更一律 fail（Nebula 专属）；agent-builder 的 tools 面不得超 caller 自身。
3. **extends 清理**（§2.1 的全部残留），Design Philosophy 重写为"复制参考模板"模式（复用表格保留，但 "extends Coder/Explorer" 的说法改为"复制 Coder/Explorer 的 tools 与 system.md 再改"）。

### 3.4 与现状差距汇总

1. extends 残留 4 文件 + 1 注释（§2.1）——P0 清理。
2. entity-creator 是线性三段（flow.json），无 entityType 分支、无 callerScope——P1 重构为分支拓扑。
3. Manager 无任何 flow 触发能力（agent.json 无 flows、无 FlowTrigger）——R1 落地后一行配置放权。
4. SkillService 无 team 域（`teams/<t>/skills/`）——P1 机制补齐（加载层级 global < team，与外部工具四层目录同思想）。
5. "Nebula 垄断"目前是 prompt 自律（Manager system.md:60）——升级为流程强制后，该禁令改写为"创建走 entity-creator（你自己能触发）；Team/全局实体仍经 Nebula"。

---

## 4. 实施要点

### P0（零代码，立即可做）
1. **extends 清理**：重写 entity-creator 三 agent 的 system.md（删 extends 模板/规则/检查项，Design Philosophy 1/3/6 节改为复制模板模式）；同步清理 flow-creator team 两个 prompt；删 RestApiRoutes.scala:1136 过时注释（代码注释改动可留 P1 一起提交）。
2. Manager system.md:60 的禁令改写（预放权文案，P1 前 Team 仍经 Nebula 中转）。

### P1（代码 + flow 重构，依赖 R1 的 FlowTrigger）
1. **entity-creator v2 分支拓扑**：
   - `flow.json` 重写：architect → `switch($architect.entityType)` → {agent-builder, flow-builder, team-builder, skill-builder} → 各自 reviewer（verdict switch 独立循环，maxLoop 3）
   - 8 个 agent 目录（4 builder + 4 reviewer），工具面沿用现有最小化原则（builder 含 Write/Edit；reviewer 只读 + Bash）
   - architect system.md 增 callerScope 规则段 + entityType 输出契约（FlowReport verdict 机制输出 entityType 供 switch 匹配——沿用 `$reviewer.verdict` 同款原语）
2. **放权接线**：目标 Team 的 Manager agent.json `flows: ["entity-creator"]`（nebflow-project 先试点）；R1 的 FlowTrigger 白名单机制为其提供工具。
3. **team 域 skill 目录**：SkillService 加载层级 `global < team`（`teams/<t>/skills/`，buildPerAgentCatalog 合并注入）；ContextRefresher 同步。
4. EntityLoader 死代码处置：writeTeam/writeFlow/writeAgent/deleteAgent 要么删除要么标注 @deprecated 留作未来受控写入原语（倾向后者，零成本）。

### 验收条件（二值可断言）
1. **冒烟**：`sbt run` → `curl /api/health` 200；`sbt test` 全绿。
2. **extends 清理验证**：`grep -r '"extends"' ~/.nebflow/flows/entity-creator ~/.nebflow/teams/flow-creator` 零命中。
3. **E2E callerScope（放权主场景）**：nebflow-project Manager 收到"给团队加一个 release-notes 写手"→ 触发 entity-creator → 断言：新 agent 落 `teams/nebflow-project/agents/`（非全局 `~/.nebflow/agents/`）；team.json members 已追加；流程各节点在 `/api/running-flows` DAG 面板可见且走的是 agent 分支。
4. **E2E 权限闸**：同一 Manager 请求"新建一个 Team"→ architect 分支路由不可达/team-builder 拒绝，回复中含"经 Nebula"指引；断言 `~/.nebflow/teams/` 无新目录。
5. **红线测试**：让 entity-creator 处理"给现有 agent 加 mcpServers"→ reviewer verdict=fail 且指出 L2 路径。
6. **team 域 skill**：在 `teams/nebflow-project/skills/demo/SKILL.md` 放 skill → 该 team 成员下一 turn catalog 含 demo；全局 agent 的 catalog 不含。
7. **回归**：Nebula 直接触发 entity-creator 建全局 agent 的老路径行为不变（caller=Nebula 全域可达）。

---

## 5. 开放问题（需拍板，附倾向）

| # | 问题 | 倾向 |
|---|------|------|
| 1 | Team 创建是否绝对垄断？"两个现有 Team 想合并/派生子 Team"也走 Nebula？ | **绝对垄断**。Team 是权限域的根（lead、members、域内实体），自建 Team = 自我扩权。合并/派生场景让 Nebula 执行正好体现"守门人"价值 |
| 2 | Team 自建实体要不要 Nebula 侧审计/可见性？ | **轻量通报 + 周期巡检**，不做前置审批：创建完成后 builder 向 Nebula 发一条 `[INFO]` 登记 Mail（FlowMailStore 天然留痕）；另跑一个 memory-consolidation 式巡检 flow 周期比对磁盘实体 vs 登记记录，发现未登记实体汇总报 Nebula。前置审批会让"自建"退化回"垄断"，违背用户放权本意 |
| 3 | 普通成员能否直接触发 entity-creator（不经 Manager）？ | **P1 不放**。成员→Manager 提名（`[NOMINATION]` 格式）→ Manager 触发。理由：callerScope 依赖"caller 是谁"的强推断（lead 身份），普通成员触发会让 caller 判定复杂化；等自建流程跑稳再议 |
| 4 | skill 是否需要 callerScope（Manager 专属）还是成员自服务？ | **成员自服务**（基线 L0 的延续）：skill 是纯知识注入，无运行时、无身份、无提权面，且 opt-in（不登记 agent.skills 就不注入）——风险已由注入机制本身兜住。Team 域目录（P1）+ 成员直接写 + catalog 自动广播即可 |
