# R3 — Skill 体系：opt-in 全局目录与防过时

> 四实体生态设计讨论报告 · 2026-08-14 · 纯分析文档，不改代码
> 对应用户反馈第 5 点：Skill 目录组织 + 防过时
> 基线：/tmp/entity-ecosystem-design.md（v1，2026-08-14）

---

## 1. 用户观点解读

用户原话两段：

> "Skill 目录是 opt-in 且只有全局层的设计是统一创建的区域，然后 agent 通过 agent.json 配置自己需要的 skill。"

> "skill，如何避免 skill 过时？"

拆解出四个设计主张：

| # | 主张 | 含义 |
|---|------|------|
| U1 | **opt-in 订阅** | skill catalog 不再无条件进 system prompt；agent 只看到自己在 agent.json 里声明过的 skill |
| U2 | **只有全局层** | skill 的创建区域唯一：`~/.nebflow/skills/`。不建 team 层 / flow 层 / project 层的 skill 目录 |
| U3 | **声明式配置** | agent.json 的 `skills: [...]` 字段是 agent 与 skill 的唯一绑定关系 |
| U4 | **防过时是硬需求** | skill 是知识资产，知识会腐烂（代码变了、工具变了、方法论过时了），必须有机制对抗

U1/U3 与 Agent/Tool 的组织哲学一致（能力都是"声明了才有"）；U2 是**主动收敛**——与 Agent 的三层目录（global → teams/*/agents → flows/*/agents）和 Tool 的四层目录（global/agent/team/flow）形成对比，skill 刻意选择单层。理由（推测，报告第 3 节论证）：skill 是**知识**而非**身份或权限**，知识应当全局共享、按需订阅，分层只会造成副本漂移；而 agent/tool 涉及"谁可以做什么"的权限边界，需要域隔离。

---

## 2. 现状核实（代码事实）

### 2.1 opt-in 机制已经存在——用户诉求 U1/U3 已实现

**关键发现：当前 archive/scala 分支的 skill catalog 注入就是 opt-in 的，不是全局注入。**

- `SkillService.buildPerAgentCatalog(skillNames)`：`skillNames.isEmpty` → 返回空字符串（不注入、无全局回退）。`SkillService.scala:189-203`
- 注入入口：`ContextRefresher.refreshTurn` 第 363 行 `skillCatalog <- SkillService.buildPerAgentCatalog(globalDef.skills)`，每个 turn 按当前 agent 的 skills 字段重建。`ContextRefresher.scala:363`
- `skills` 字段在 AgentDef/AgentEntry 中已有完整编解码支持（可选字段，缺省 Nil）。`EntityTypes.scala:24, 40, 53, 68`
- 注入格式：`# Skills` 标题 + 说明行 + `- name: description.take(200)` 条目列表——**名字+描述，不含全文**（progressive disclosure，agent 按需 Read SKILL.md）。

**但存在一个历史遗留的全局版**：`buildSkillCatalog(currentDelegateCount)`（`SkillService.scala:222-246`，含 TTL 缓存、过滤 `modelInvocable`）**全代码库无任何调用者——是死代码**。它是"曾经全局注入"的遗迹（任务背景描述的"全局注入所有 agent"对应的就是这段代码的历史行为）。

同构先例：`flows` 字段走完全相同的 opt-in 模式（`buildPerAgentFlowCatalog`，`SkillService.scala:209-220`），且 `DelegateTool` 在运行时强制校验白名单（未声明的 flow 拒绝触发，`DelegateTool.scala:176-181`）。**skill 目前只有"注入过滤"，没有"使用校验"**——声明与否只影响看得见，不影响能否 Read。

### 2.2 采用率：零

扫描全部 36 个 agent.json（全局 `~/.nebflow/agents/` + 各 team `~/.nebflow/teams/*/agents/`）：

- 声明 `skills` 的 agent：**0 个**
- 声明 `flows` 的 agent：1 个（Nebula：`flows: ["code-review","release-stable"]`，其 `skills: null`）

即：opt-in 机制上线后，没有任何 agent 订阅过任何 skill。机制存在 ≠ 机制被用。

### 2.3 目录层现状：与 U2 基本一致，但有三层来源

`SkillService.listSkills()`（`SkillService.scala:256-269`）实际加载三层：

| 层 | 路径 | source 标记 |
|----|------|------------|
| user（全局） | `~/.nebflow/skills/` | "user" |
| project | `<cwd>/.nebflow/skills/`、`<cwd>/.claude/skills/` | "project" |
| legacy commands | `<cwd>/.nebflow/commands/`、`<cwd>/.claude/commands/`（单文件 md） | "commands" |

去重优先级 user > project > commands。**没有 team 层**——与 U2 一致（project 层是 cwd 相关的兼容设计，非组织层级）。对比：Agent 三层（EntityLoader.loadTeamAgent 先查 teams/<t>/agents/ 再回退全局，`EntityLoader.scala:163-165`）、外部 Tool 四层（ToolLoader，flow>team>agent>global）。

当前全局 skill 库 12 个（含 `_example` 模板与内置 `skill-creator`，`ensureDefaults` 首次启动自动写入，`SkillService.scala:57-69`），全量 description 合计约 1382 字符 ≈ 345 token。

### 2.4 防过时现状：完全没有机制

- frontmatter `version` 字段：**可选解析、无任何消费**（parseSkillFile 解析后只进 JSON 输出，不参与注入/校验）。`SkillService.scala:387`
- 无"最后验证时间"、无"引用了哪些代码/文件"、无定期复审
- **沉淀机制的半成品**：压缩前钩子 `WorkerSkillHook`（depth≥2 的 worker，messages≥10 时触发，`WorkerSkillHook.scala:25-29`）→ `ExperienceExtractor` 用独立 LLM 调用提取 1 条经验（CLASS: skill|memory|skip，SKILL_MATCH 命中已有 skill 则文件名记为 `update-<name>-<ts>`，`ExperienceExtractor.scala:115-138`）→ 写入 `~/.nebflow/agents/<name>/skill-proposals/<name>.md`，frontmatter 含 `proposed_by / proposed_date / status: pending / based_on_skill`。
  - **skill-proposals 目录当前为空**（甚至未创建过）
  - **全代码库 + 全部 prompt 文件中无任何消费者**（grep 仅命中写入方）——"for manager review" 的注释是愿景，链路断在最后一公里
- 唯一的"定期复审"先例是 memory-consolidation flow（scanner+consolidator 两节点，扫 memory 条目做验证/清理）——skill 版的复审 flow 不存在
- CLI 只有 `skill list / skill run`（`SkillCommand.scala:10`），无 audit/verify 子命令

### 2.5 三个缺陷/漂移（顺手发现）

1. **`buildPerAgentCatalog` 漏了 `modelInvocable` 过滤**：死代码版有 `filter(_.modelInvocable)`，opt-in 版只 filter `description.nonEmpty`（`SkillService.scala:194`）。`disable-model-invocation: true` 的 skill 一旦被 agent 声明，仍会注入——与字段语义矛盾。
2. **prompt 文案漂移**：`system-prefix-for-all.md` 第 5 行宣称 "A skill catalog is injected into your system prompt every turn"——在 opt-in + 零采用的现实下，绝大多数 agent 什么 catalog 都收不到，文案在撒谎（本 agent 运行时即如此：有 Skills 说明段、无任何条目）。
3. **`whenToUse` 字段解析了但注入时丢弃**：`buildPerAgentCatalog` 只输出 name+description，frontmatter 的 `when_to_use`（`SkillService.scala:385`）不进 catalog——而这恰是帮助 agent 判断"何时读全文"的信号。

---

## 3. 设计建议（含权衡）

### 3.1 opt-in 模型确认：默认空，不是默认全量

**结论：维持"默认空 + agent.json 显式声明"，即用户主张的纯 opt-in。** 理由：

- **信噪比先于体积**。当前 12 skill 全量仅 ~345 token，体积不是问题；但 description 的相关性是问题——guizang-ppt-skill 对 czt-physicist 是纯噪声。opt-in 的本质是"让每行注入都有订阅者负责"，目录膨胀时成本只落在声明者头上，全局目录可以放心长。
- **skills 字段是双向契约**：agent 声明 = "我承诺会用它"（Manager/team 目录里可见能力信号）；skill 侧可反向追踪引用者（防过时的关键输入，见 3.4）。默认全量会摧毁反向追踪的意义——"人人都看得见"等于"没人负责"。
- **与 flows 字段对称**：flows 已是白名单+运行时校验，skills 同构，认知一致。

**权衡与代价**（必须直说）：
- 冷启动问题：新建 agent 不知道 skill 库里有什么，无法凭空声明。缓解见 3.3（发现路径）。
- 迁移问题：存量 skill 若曾靠全局注入被发现，收紧后失联。现实是零 agent 声明 skills，所以没有存量用户受损——**现在是切纯 opt-in 成本最低的时刻**。

### 3.2 只有全局层：采纳 U2，否决基线方案的 team 域 skill

**基线冲突声明**：基线文档 §4.3 P1-3 建议"team 域 skill 目录（SkillService 增加加载层级 global < team）"；§3.2 建议 entity-creator 的 caller=Manager 时 skill 写 `teams/<team>/skills/`。**用户新观点（只有全局层）否决了这两条**。本报告以用户观点为最高输入：

- **知识不分域，身份才分域**。skill 是方法论，天然跨项目复用（thesis-review 对 czt-project 和任何学术 agent 都有用）；硬塞进 team 目录会造成"同一个方法在三个 team 各有一份副本"的漂移——这正是防过时要对抗的东西。
- **统一创建区域 = 统一治理区域**。防过时需要全库视角（引用追踪、复审队列、版本演化）；多目录立刻碎片化治理面。
- **归属粒度由订阅表达，不由目录表达**："这个 skill 是 czt 团队的"的正确表达是 czt 团队的相关 agent 声明了它，而不是文件长在 czt 目录下。
- 保留 project 层（cwd 相对路径）作为**版本化随库分发**的通道（.nebflow/skills 进 git，团队共享 checkout 即得），这与"组织层级"是两回事，建议保留但文档中明确其定位为"项目随附 skill"。

### 3.3 发现路径：opt-in 的配套（冷启动解法）

opt-in 不等于不可发现。三层发现，全部零/低成本：

1. **skill-creator skill 自举**：它已在全局目录，但零人声明所以零人看见。建议给 skill-creator 特殊地位——**skill-creator 始终对所有 agent 可见**（注入时无条件附带这一条），因为它是"发现与创建"的元入口。实现即 buildPerAgentCatalog 里 append 固定条目。
2. **Nebula / Manager 可见全库索引**：编排者需要全库视角做"该沉淀什么/该给谁订什么"的判断。Nebula 的 agent.json 声明全量，或专门给 category=orchestrator 注入完整列表。skills 白名单从此也成为 Manager 园丁职责的一部分（基线 §5.1 能力园丁的 skill 维度）。
3. **沉淀时自动提议订阅**：ExperienceExtractor 产出 proposal 时记录了 proposed_by；闭环落地（3.5）时，合并进全局库的动作应同步提议"给该 agent 的 skills 字段追加此 skill"——发现-订阅-使用一气呵成。

### 3.4 防过时机制：四件套设计

skill 过时的三种形态：**(a) 内容腐坏**（引用的代码/工具/路径变了）；**(b) 无人引用**（订阅者清零或从未有人订阅，成为僵尸）；**(c) 语义漂移**（description 说 A，内容早已变成 B，或与其它 skill 重叠）。

| 机制 | 设计 | 成本 |
|------|------|------|
| **① 元数据（写入侧）** | frontmatter 增两个可选字段：`last_verified: 2026-08-14`（最后验证日期）、`verified_against: <代码库版本/commit/路径>`（验证时依据什么）。skill-creator 的 Best Practices 增加填写指引；无需改解析代码（未知字段天然忽略），P1 再进 SkillInfo | 纯文档 |
| **② 引用追踪（观测侧）** | 反向索引：静态扫描 `agents/*/agent.json` + `teams/*/agents/*/agent.json` 的 skills 字段 + skill-proposals 的 based_on_skill，产出 "skill → 订阅者列表"。纯文件扫描，可做成 CLI 子命令（`skill audit`）或 memory-consolidation 式 flow 的输入。**这是 opt-in 模型送的免费红利——声明式订阅本身就是引用登记表**（默认全量注入则此表不存在，这是 opt-in 的第二重价值） | 低 |
| **③ 定期复审（触发侧）** | 新建 skill-audit flow，仿 memory-consolidation 两节点：scanner 扫全库（last_verified 距今 >90 天 / 订阅者=0 / description 与正文首段语义背离 / 引用的文件路径不存在）→ consolidator 产出动作建议（update / re-verify / deprecate / delete）并按订阅者路由通知。触发：手动 + 闲时（Schedule 工具已存在于 Nebula 的工具列表，周期触发能力现成） | 中 |
| **④ 生命周期状态（演化侧）** | frontmatter `status: draft / active / deprecated`。deprecated 的 skill 从 catalog 注入中排除，但正文保留"替代者指向"（`replaced_by: <name>`）——订阅了 deprecated skill 的 agent 在 catalog 位置看到一条迁移提示。删除是物理动作，deprecated 是逻辑动作，中间隔着引用者确认 | 低 |

**防过时的闭环逻辑**：写入时留验证锚点（①）→ 平时免费积累引用事实（②）→ 定期用事实驱动复审（③）→ 复审结论走状态机而非直接删（④）。

### 3.5 沉淀闭环：给 skill-proposals 一个消费者

现状断裂点（2.4）必须接上，否则防过时无从谈起（没有入口的资产谈不上维护）。设计：

- **消费者 = Manager（team 域）/ Nebula（全局）**：skill-audit flow 或 Manager 园丁巡检时收集本域成员的 skill-proposals/*.md（status: pending），逐条决定：合并进全局库（走 skill-creator 的标准结构）/ 驳回（写回 status: rejected + 理由）/ 转 memory。
- **合并动作 checklist**（进 skill-creator 指导文档）：frontmatter 完整（name/description/last_verified）、description 同时说清 what+when、references 不超过必要、**提议订阅者清单**（proposed_by 的 agent 是否自动加 skills 字段）、与现有 skill 是否重叠（有则走 update 不走 new——proposals 的 `update-<name>` 命名已支持增量）。
- proposals 文件本身带 `proposed_date`，**pending 超 14 天的 proposal 在 audit 中标红**——防止提案队列本身过时。

### 3.6 注入粒度（开放问题的倾向）

维持 **名字 + description（截断 200）**，不建议注入全文，理由：SKILL.md 正文长度无约束（现有 skill 从 4KB 到 4.4MB 资产目录），全文注入等于把 progressive disclosure 退化成全量注入；description 是 skill-creator 明文规定的"唯一相关性信号"，把信号写好是 skill 作者的责任，不是注入器的责任。**可做的增强**：把 `when_to_use`（已解析未使用）追加进条目——它是 description 的"when"补丁，一行成本换路由精度。条目格式建议：

```
- <name>: <description> [when: <when_to_use>]
```

---

## 4. 与现状差距汇总

| # | 目标状态 | 现状 | 差距性质 |
|---|---------|------|---------|
| G1 | opt-in 注入（默认空） | **已实现**（buildPerAgentCatalog） | 无差距；需删死代码 buildSkillCatalog 防回潮混淆 |
| G2 | agents 实际声明 skills | 0/36 声明 | 采用差距——缺发现路径与沉淀闭环的牵引 |
| G3 | 只有全局层 | 已无 team 层；但基线 P1-3/P2 计划开 team 域 | 方向差距——基线需按用户观点修订 |
| G4 | last_verified / status / replaced_by 元数据 | version 字段无消费 | 字段+消费机制双缺 |
| G5 | 引用追踪 | 无（但数据天然存在于 agent.json） | 只差工具化 |
| G6 | 定期复审 flow | 不存在（memory-consolidation 是模板） | 新建 |
| G7 | proposals 闭环 | 写入方存在、消费者为零、目录空 | 断链 |
| G8 | modelInvocable 过滤 / prompt 文案 / when_to_use 注入 | 三处小缺陷 | 顺手修复项 |

## 5. 实施要点（按依赖排序）

1. **P0 文档层**（零代码）：修订 skill-creator SKILL.md——frontmatter 增 last_verified/status 指引、合并 checklist、description 的 what+when 标准；修订 system-prefix-for-all.md 的 Skills 段文案与 opt-in 行为一致；基线文档勘误（撤销 team 域 skill 计划）。
2. **P1 小代码**：修 modelInvocable 过滤 bug；catalog 条目追加 when_to_use；删 buildSkillCatalog 死代码；skill-creator 固定注入（3.3-1）；`skill audit` CLI 子命令（扫描引用者 + last_verified 年龄 + 孤儿 skill）。
3. **P2 机制**：skill-audit flow（scanner/consolidator 复用 memory-consolidation 骨架，agent 可 extends Explorer/Coder 现成 prompt）；Schedule 定期触发；Manager prompt 增"proposals 收件箱"职责一段。
4. **验收锚点**：某 agent 声明 skills 后下一 turn catalog 含条目（机制回归）；声明 disable-model-invocation 的 skill 不出现；audit 命令对"订阅者=0 的 skill"正确报孤儿；deprecated skill 从 catalog 消失且订阅者收到迁移提示。

## 6. 开放问题（附倾向）

| 问题 | 选项 | 倾向 |
|------|------|------|
| opt-in 默认值 | 默认空 / 默认全量 / 新 agent 给推荐包 | **默认空** + skill-creator 固定可见 + 沉淀时自动提议订阅（3.3）。反对"推荐包"——它把订阅决策从 agent 挪到了模板，破坏声明式契约的 purity |
| 订阅是否运行时强校验（像 flows 那样拦 Delegate） | 只影响可见性 / 未声明则禁止 Read SKILL.md | **只影响可见性**。skill 是知识不是权限，Read 是通用工具拦不住（agent 可直接读文件路径）；强校验只制造假安全。可见性即引导足矣 |
| 注入粒度 | 名字+desc / +when_to_use / 全文 | **名字+desc+when_to_use**（3.6） |
| 复审周期与触发者 | 90 天 / 按事件（引用的文件变更时）/ 闲时 | **90 天兜底 + 闲时 Schedule**；"按事件"（监听代码变更触发）成本高且误报多，P2 再议 |
| project 层（cwd skills）去留 | 保留 / 收敛到全局 | **保留**，定位为"随 git 仓库分发的项目 skill"，与组织层级无关；文档中写清两层定位差异 |
| 防过时责任主体 | skill 作者（沉淀者）/ 订阅者 / Nebula-Manager 园丁 | **园丁持有队列、订阅者提供反馈**（用 skill 时发现失真 → Mail 园丁或直接提 update proposal）、作者义务仅在创建时（填 last_verified）。三方共担但以园丁为轴心 |
