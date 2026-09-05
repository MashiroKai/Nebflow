# R6 — Agent 设计指导：最小心智与自我进化

> 四实体生态设计讨论报告 R6 · 2026-08-14 · 基线：/tmp/entity-ecosystem-design.md（本文补齐其未展开的 Agent 个体设计维度）
> 事实来源：源码 archive/scala 分支 + ~/.nebflow 实测（memory 文件全量、skills/proposals 目录、prompt 长度分布）

---

## 1. 用户观点解读

用户两条输入：

**"Agent 如何自己进化？"**——这是对四实体生态的动态性提问。静态地建好一个 agent 只是起点；真正的设计问题是：agent 在日常工作中产生的经验（踩过的坑、发现的用户偏好、重复执行的方法），通过什么通道、什么门槛、什么保护机制，沉淀回自己的定义（memory → skill → system prompt），并且**不膨胀、不退化、可回滚**。

**"前端的 agent，不仅是有自己的审美提示词，最重要的是理解用户的审美"**——这条界定了"好 agent"与"好工具"的差别：审美提示词（system prompt 里的领域知识）是静态注入，人人可复制；"理解用户的审美"是**运行时持续习得的软能力**——用户每一次打回、每一次澄清（"正常我们应该是毛玻璃面板"）都是训练信号，必须有地方存、有机制让下一次任务用上。这是进化循环里最微妙的一类知识：它不是技术事实（不进代码库），不是通用方法论（不适合全局 skill），而是**用户×领域**的交叉事实——放哪一层，正是本报告要回答的问题。

两句话合起来：**进化 = 把经验从会话（易失）搬到定义（持久）的最小心智compatible通道**。"最小心智compatible"是硬约束——进化的结果不能是 prompt 无限变肥，否则与分域的初衷（R5 命题 A）自相矛盾。

---

## 2. 现状核实

### 2.1 memory 机制全景：一套相当完整的骨架已经存在

**Save turn 四步循环**：compaction 将至时（而非每 turn），注入 save reminder，agent 带工具执行 RECORD→ORGANIZE→VERIFY→CLEAR 四步（CompactService.scala:76-107），配质量三标准——HARD TO OBTAIN（代码可读的不记）、REUSABLE（一次性细节不记）、CURRENT-STATE-FIRST（现实优先于记忆，CompactService.scala:88-96）。这个设计已经内置了"防膨胀"（CLEAR 删除）与"防过期"（VERIFY 核对）。

**三 profile 分化**（按 depth 判定，CompactionProfile.scala:24-27）：

| Profile | 判定 | save 焦点 | pre-compaction hook |
|---------|------|----------|---------------------|
| Root (Nebula) | depth 0 | 用户动态事实→User.md + 编排知识→Nebula memory | NebulaMemoryHook：LLM 抽取事实 → User.md（NebulaMemoryHook.scala:18-38，≥20 条消息触发） |
| Manager | depth 1 | 协调状态（待派发/决策及理由/派发经验/产物位置） | ManagerProgressHook：**无 LLM** 启发式进度摘要追加 memory（ManagerProgressHook.scala:15-46） |
| Worker | depth 2+ | 技术经验（坑与修复/有效实践/工具行为）+ skill 沉淀 | WorkerSkillHook：LLM 抽取经验 → skill-proposals（WorkerSkillHook.scala:25，≥10 条消息触发） |

**save turn 注入条件**：Nebula + 所有 team 成员（AgentCore.scala:173-181 按 teamOfSession 判定；212-216 决定 Save/Compact 两阶段）。standalone 与 flow agent 无 save turn。

**实测 memory 产出**（质量出乎意料地好）：
- nebflow-project：Manager 82 行 / Backend 63 / Frontend 59 / prompt-engineer 10 / qa-frontend 5；全局 Nebula 191 行；共 17 个 team memory 文件
- Frontend memory 的条目质量是范本：每条坑都带"Read when 触发条件"（如"Read when 动任何弹窗 overlay/面板样式"）、根因、修法、甚至用户原话（"现在是透明面板，正常我们应该是毛玻璃面板"——**用户审美裁定的原始证据就存在这里**）
- Manager memory 的"当前任务状态"段即 ManagerProgressHook 的自动产物

### 2.2 发现：profile 错位——team 成员被当成"Manager"对待

team 成员由 Mail 激活时 `depth = 1`（MailTool.scala:896；挂载路径 FlowTreeActor.scala:682、FlowDagExecutor.scala:447 同），于是**所有 team 成员拿到的是 Manager profile**：save reminder 焦点是"协调状态"，hook 是进度摘要——而 Worker profile（技术经验+skill 沉淀）只属于 depth 2+ 的临时 sub-agent。实际结果错位有趣：成员凭自己 system.md 的常识仍写了大量技术坑（Frontend memory 主体是"经验教训"而非"协调状态"），说明**成员的自然行为符合 Worker 焦点，系统的提示焦点却是 Manager 的**。这是配置层与行为层的静默分歧——不致命（共享 preamble 兜底），但 skill 沉淀的门槛文案（"seen 2+ times"）写在 WorkerSaveMemoryReminder 里，**team 成员根本收不到这段话**。

### 2.3 skill 沉淀通道：机制三件，全部未跑通

1. **文案门槛**（CompactService.scala:186-190，WorkerSaveMemoryReminder）："同类过程 seen 2+ 次或明显可泛化 → 按 skill-creator 规范写成 ~/.nebflow/skills/<name>/SKILL.md"。规范本身完备（skill-creator/SKILL.md：one skill = one purpose；description 必须写 what AND when——它是对话式注入时 agent 判断相关性的唯一信号）。
2. **旁路提取**（ExperienceExtractor）：pre-compaction LLM 抽取，skill 类写入 `dataRoot/agents/<agentName>/skill-proposals/`（ExperienceExtractor.scala:115-140）。**实测该目录在整台机器上一个都不存在——通道从未产出**。且路径按 agentName 全局落盘，4 个团队都有 Frontend，提案会混名串团队。memory 类经验被显式丢弃，注释理由 "Workers have no persistent memory"（ExperienceExtractor.scala:104-111）——这句注释对 flow worker 成立、对 team 成员不成立（他们有 memory.md 且写得很勤），是又一个 profile 语义漂移的痕迹。
3. **登记激活**（SkillService.scala:185-203）：`agent.skills` 为空则 skill catalog 完全不注入（opt-in，无全局 fallback）。**实测所有 team agent 的 skills 字段全空**——12 个全局 skill（academic-survey、card-design、visual-report……）没有任何 team 成员引用，catalog 从未注入过任何一个 team agent。

结论：**skill 层是"机制齐备、全线断路"**——没人收到沉淀指令（2.2）、提案无人产出无人评审（2.3.2）、写好的 skill 无人登记（2.3.3）。进化循环在 memory 层活跃、在 skill 层停摆、在 system prompt 层集中。

### 2.4 system.md 长度分布实测（最小心智的量化体检）

- team agent：33-229 行，中位约 110。最小 nebflow-project/Backend 38 行/130 词（一句话职责+核心纪律，好样本）；最大 flow-creator/EntityArchitect 229 行（复杂判定角色，接近合理上限）
- Frontend 模板 180 行/1163 词，在 nebflow-project、nebflow-rust、slideblocks、voice-recognition-test 四团队近乎相同——**"理解本项目用户审美"的定制内容占比为零**（用户命题的直接反证）
- 全局：Explorer 414 行（重度多能力角色，上限样本）、Nebula 175、Coder 57、Planner 14
- qa-backend 事件（nebflow-project 的 qa-backend 描述仍是"Rust 迁移审核员"，与 rules.md 冲突——R5 §2.5 已详述）：**prompt-engineer 体检本可发现**，说明目前没有周期性体检机制

### 2.5 防退化设施现状

- **~/.nebflow 未 git 化**；backups/ 目录只有 nebflow.json 的时间戳快照（配置级，无 agent 级）
- memory-consolidation flow 存在（清理 stale/conflicting/redundant 条目），但按需手动触发
- agent 修改 system prompt 的现行制度是**集中制**：User.md 明文规定"行为规则/系统提示词修改走 Explorer 分析 → nebflow-project prompt-engineer 实施，Nebula 不自己改自己的 system.md"；Manager prompt 明令禁止自建 agent/编辑 team.json

---

## 3. 设计分析

![三层进化循环](/tmp/r6-evolution.svg)

### 3.1 好 Agent 的判断条件（进化的前提是先有一个好的静止态)

五条，每条可检验：
1. **单一职责**：一句话职责陈述，且这句话能通过"换团队不换词"测试（职责是角色属性不是项目属性；项目属性在 rules.md）；
2. **心智边界**：system.md ≤150 行/≤900 词（软预算，Explorer 414 行是特批上限），工具白名单反推自"不该做的事"（qa 无 Edit、prompt-engineer 无 Bash 是范式）；
3. **工具最小集**：注册即审计——每个工具都能回答"这个角色为什么需要它"；
4. **可判定 useWhen**：catalog 里的一句话必须让路由者（Manager/成员）在 3 秒内判定"是不是它"（SkillService 对 skill description 的要求"what AND when"同样适用于 agent 自述）；
5. **可独立验收**：存在明确的验证配对与 DoD（R5 §3.4）——不能被独立验收的 agent 是"气氛组成员"。

### 3.2 "理解用户"类软能力的沉淀通道：三层分置

用户×领域交叉事实（审美裁定、表达偏好、打回原因）有三个候选存放层，实测显示**目前全部挤在 agent memory 一层**（Frontend memory 里的用户原话），带来两个问题：Designer/qa-frontend 看不到（孤岛）；Frontend 换团队时模板复制不带 memory（丢失）。分置原则：

| 层 | 存什么 | 判据 | 实测锚点 |
|----|--------|------|---------|
| **User.md**（Nebula 维护，全 agent 可见） | 跨领域稳定的用户偏好与工作风格："用户厌恶绝对化表述""迭代式设计、先方案后实现" | 去掉领域词后仍然成立 | User.md"工作风格"段已是此层范本 |
| **rules.md**（team 共享） | 用户对本项目/本产物的裁定："弹窗面板必须毛玻璃、overlay 不暗化"——前后端+QA 都要遵守的视觉契约 | 多于一个成员需要知道 | rules.md 目前无此段，是缺口 |
| **agent memory**（域内自持） | 领域技巧与用户裁定的**执行细节**："实现毛玻璃要含 -webkit- 前缀、注意 JS 注入 CSS 不在 css/ 里" | 只有本角色干活时用得上 | Frontend memory"弹窗禁令的精确语义"条目 |

**上行链路的真实断点**：成员在 save turn 识别出"这是用户级事实"后，无法直 Mail Nebula（R5 §2.1 硬约束），只能 RESULT 报 Manager、由 Manager 转报 Nebula 记入 User.md。这条链今天没有契约支持（Manager 的默认行为是消化任务结果而非转报用户事实）。**倾向不破上行单通道**（用户明确认可其价值），而是在协作契约中加一条：*"Mail 中出现用户原话级裁定/偏好 → Manager 判定层级：项目级记 rules.md，用户级转报 Nebula，域内留给成员 memory"*。这样用户审美的"理解"发生在成员，"记忆共享"发生在正确层级。

### 3.3 三层进化循环：触发器、门槛、验证、回滚

| 层 | 载体 | 进化触发器 | 沉淀门槛 | 验证 | 回滚 |
|----|------|-----------|---------|------|------|
| **memory（事实）** | teams/<t>/agents/<n>/memory.md | compaction save turn（现状唯一触发点，节奏合理：攒够一波再整理） | 质量三标准（已有，CompactService.scala:88-96） | VERIFY 步骤 + memory-consolidation flow（已有） | **缺口**：无版本化。建议 agent 域目录 git 化（或 save turn 前自动快照到 backups/） |
| **skill（能力）** | skills/<name>/SKILL.md | 同类事实/方法 seen 2+（文案已有但送错对象，§2.2） | ① skill-creator 规范（one skill one purpose）② 登记 agent.skills 才生效（机制已有）③ 新 skill 自测一次（基线方案 §4.2 自测义务） | 下次同类任务实际调用成功 | 删目录即回滚（文件即插件，天然优势） |
| **system prompt（行为）** | system.md + agent.json | 职责漂移信号（useWhen 与实际产出不符、连续收到域外任务）或周期体检发现 | **分级门槛**（见下） | 试用期对比（基线 §5.2 已定义 trial 机制） | backups/ 快照（现状缺，entity-creator v2 的备份规则可复用） |

**system prompt 的分级门槛**（解决"自己改自己"的自肥风险）：
- 行为段（工作流程/质量标准/领域约定）：**自治**——agent 最懂自己的漂移；条件：memory 记一条变更理由 + [INFO] Manager；
- 能力自述（description/useWhen/skills）：**自助**——L0 自服务（基线方案 §3.1 已定义）；
- 红线字段（tools/mcpServers/model/preset）：**审批**——一律走 entity-creator/prompt-engineer，agent 无权（同基线红线）。

**进化的发动机缺口**：现状唯一触发器是 compaction（被动、随机）。建议增加**周期体检**（P1，可挂 Schedule 或并入 memory-consolidation 例行）：prompt-engineer 扫描各 agent system.md 长度分布、useWhen 与 FlowMailStore 实际路由的偏离、skills 字段空置率——qa-backend Rust 残留、Frontend 四团队同模板，都是一次体检就能抓出的病灶。

### 3.4 进化的收敛：三条定量红线

进化最大的失败模式不是不进化，是**无收敛地进化**（memory 变垃圾场、prompt 变补丁摞）。三条红线（全部有现状数据支撑刻度）：

1. **memory ≤80 行**触发 consolidation（实测最大 Manager 82 行——正好压线，说明现状已逼近自然饱和点；Frontend 59 行高密度无冗余，是健康基线）；
2. **skill 目录零引用退役**：skills 字段为空的 skill 连续两个体检周期无人登记 → 移入 archives（12 个全局 skill 目前全部零引用，属于待清理/待激活存量）；
3. **system prompt 软预算 150 行**，超限必须拆层（进 rules/skill/memory）而非续写——长度是职责是否单一的最便宜代理指标。

配套收敛机制全部已有雏形：CLEAR 步骤（每轮压缩时的例行减法）、memory-consolidation flow（深度清理）、catalog description 200 字符截断（SkillService.scala:197，天然防 skill 描述膨胀）。

---

## 4. Agent 设计 Checklist

**建 agent 时（静态质量）**
- [ ] 一句话职责，通过"换团队不换词"测试
- [ ] system.md ≤150 行/≤900 词；每段能回答"为什么不能放 rules/skill/memory"
- [ ] 工具白名单从"不该做的事"反推；每个工具有存在理由
- [ ] useWhen 写了 what AND when，路由者 3 秒可判定
- [ ] 有验证配对与 DoD（不被独立验收 = 不该存在）
- [ ] 复制来的 agent 已换域重写 description/useWhen（qa-backend 教训）

**运行中（进化纪律）**
- [ ] save turn 四步照做；条目带 "Read when" 触发条件（Frontend 范本）
- [ ] 用户原话级裁定按三层分置上报（User.md / rules.md / memory）
- [ ] 同类方法 seen 2+ → 写 skill → 自测 → 登记 skills 字段
- [ ] useWhen 与实际任务漂移 → 当轮修正（L0）
- [ ] 行为段修改记变更理由 + [INFO] Manager；红线字段走审批

**周期体检（收敛）**
- [ ] memory >80 行触发 consolidation
- [ ] 零引用 skill 退役；skills 空置率下降趋势
- [ ] system.md 长度分布无超限；无跨团队复制残留
- [ ] FlowMailStore 实际路由 vs useWhen 声明的偏离度

---

## 5. 与现状差距

| # | 目标 | 现状 | 差距性质 |
|---|------|------|---------|
| 1 | team 成员收到 Worker 焦点 save reminder | 成员 depth=1 → Manager profile | **P1 代码**：profile 判定改角色（agent 名==team lead → Manager，否则 Worker），CompactionProfile.fromDepth 加 lead 参数 |
| 2 | skill 沉淀指令触达成员 | "seen 2+" 文案在 Worker 段，成员收不到 | 同上（修复 #1 即通） |
| 3 | skill-proposals 评审消费 | 目录零产出、无评审流程、路径跨团队混名 | P1：提案路径改 team 域 + Manager/prompt-engineer 例行采收（或直接废旁路、只走 save turn 正路——见 §7.2） |
| 4 | skills 字段登记激活 | 全部 team agent 空置，12 skill 零引用 | P0 纪律（建 skill 必登记）+ P1 体检 |
| 5 | 用户审美三层分置 | 全部挤在 Frontend memory | P0 契约（Manager 转报规则） |
| 6 | 进化可回滚 | ~/.nebflow 无版本化 | P1：agent 域目录 git 化或 save/体检前快照 |
| 7 | 周期体检 | 无（qa-backend 漂移存活即证） | P1：prompt-engineer 例行任务 |
| 8 | memory 质量维持 | **已达标**（四步循环+三标准+consolidation flow） | 维持即可——这是生态里完成度最高的一环 |

## 6. 实施要点

1. **P0（零代码）**：① 契约补三条——用户裁定三层分置规则（进 Manager 职责）、skill 沉淀纪律（成员版"seen 2+"文案并入 system-prefix-for-teams.md，绕开 profile 错位）、建 skill 必登记 skills 字段；② §4 checklist 交 prompt-engineer 作为体检基准。
2. **P1（小代码）**：① profile 按角色判定（改动集中在 CompactionProfile + 三处 depth 传参处补 lead 信息）；② skill-proposals 路径改 `teams/<team>/agents/<name>/skill-proposals/`；③ 体检任务（Schedule 驱动 prompt-engineer）；④ agent 域目录版本化。
3. **验收锚点**：① 修复 profile 后，给 nebflow-project/Frontend 喂两个同类视觉任务 → 断言其 save turn 提示含 Worker 焦点、memory 新增条目带 Read when；② 手动为 Frontend 登记 visual-report 类 skill → 断言下一 turn system prompt 含 Skills catalog；③ 制造一次用户裁定（"以后按钮统一圆角 12px"）→ 断言 30 天内 User.md 或 rules.md 出现该条而非只躺在 Frontend memory。

## 7. 开放问题（含倾向）

1. **agent 改自己的 system.md 要不要审批？** 倾向**分级混合制**（§3.3）：行为段自治+留痕、自述自助、红线审批。纯集中制（现状）的问题是瓶颈+信息损失——agent 是唯一实时感知自己职责漂移的主体，却无权校准；纯自治制的风险是自肥与漂移连锁（agent 顺手给自己加工具、放宽边界）。分级制把"改什么"映射到"怎么改"。集中制的 prompt-engineer 不撤销，转型为**体检医生+红线审批者**（从写 prompt 的人变成审 prompt 的人——这与 R5/基线中 Manager 从中转站转型是同构的权力重构）。
2. **ExperienceExtractor 旁路要不要留？** 倾向**降级为可选**：它与 save turn 正路功能重叠（都在 compaction 边界做经验提取），但一个是 LLM 旁路（成本、零产出、路径缺陷）一个是 agent 亲写（质量实证好）。修复 profile 错位让正路触达成员后，旁路的存在价值只剩"agent 忘了写时的保险丝"。可保留但降低期望，或在两个体检周期仍零产出后移除。
3. **memory 要不要 git 化整个 ~/.nebflow？** 倾向只 git 化 entities 目录（agents/teams/skills 的定义文件），memory.md 可选纳入——进化审计需要 diff，但 memory 含用户隐私事实，进 git 前应征询用户。
4. **"理解用户"是否值得做成机制（如独立的 preference-store）？** 倾向否：三层分置（User.md/rules.md/memory）已覆盖语义，新机制引入第四处真相源反而增加漂移面。先用契约跑三个月，出现"分置判据不够用"的真实案例再议。
