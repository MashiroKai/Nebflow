# Nebflow 四实体生态设计 — skill / flow / agent / team 的功能区分、自主创建与自组织协作

> 版本 v1 · 2026-08-14 · 状态：待审阅
> 原则：基于现有架构演进，最小精准改动；机制进代码，策略进文件（呼应智能路由 v3 的"外部规则文件 + case 独热"思想与 dsh 的 "Everything is a Plugin"）。
> 核心诉求（用户原话）："Teams 我希望能自发地涌现来合作，而不是靠 manager 去派发。比如完成了代码任务，自己去找 qa 验证，然后 qa 打回修改或者汇报。所以设计这个 manager 很重要。"

---

## 0. TL;DR — 六个设计决策

| # | 问题 | 决策 |
|---|------|------|
| D1 | 四实体怎么区分 | 一张**独热判定表**（有序问答，首个命中即决策），落到 `system-prefix-for-all.md`，agent 可直接执行 |
| D2 | 谁能创建实体 | **三级权限**：成员自服务（自己目录内的 skill/工具/能力自述）→ Manager 自主（team 域内的 agent/skill，走 entity-creator）→ Nebula 独占（全局 agent、新 team、跨域共享） |
| D3 | 创建走什么机制 | **不新建 EntityTool**。演进现有 entity-creator flow：加 callerScope 边界 + 落盘前备份 + 试用标记。文件即插件，评审即流程 |
| D4 | 能力怎么广播 | 广播机制**已经免费存在**（ContextRefresher 每 turn 重读 agent.json → TeamCatalog 注入全员）。缺的是**更新协议**：何时改、改什么、谁负责——用触发器规则补齐，不写一行代码 |
| D5 | Manager 变成什么 | 从"调度中转站"改为**契约守卫 + 能力园丁 + 兜底仲裁者**。验收责任不丢：不再逐任务路由，但只认"带验证证据的 RESULT" |
| D6 | 自组织怎么防失控 | **Task Envelope（任务信封）**：SENDER 归责（结果回到发起者而非 Manager）+ HOP 跳数上限 + 打回次数上限 + 环检测 + 超时升级。全部先以纯文本协议落地（P0 零代码） |

---

## 1. 现状事实（设计依据）

### 1.1 机制层已经具备的能力（很多人不知道）

| 机制 | 现状 | 文件依据 | 设计含义 |
|------|------|---------|---------|
| 成员互 Mail | 同 team 短名路由已支持，immediate/queue/ask 三模式，5 种 TYPE | MailTool.scala:637-669, 43-81 | 自组织协作的**传输层已通**，缺的是协议和文化 |
| 每 turn 热刷新 | team agent 的 agent.json/system.md 每个回合从磁盘重读；TeamCatalog（成员 description+useWhen）、rules.md、memory 全部每 turn 注入 | ContextRefresher.scala:289-389 | 成员改自己的能力自述 → 下一 turn 全团队自动可见。**广播零成本** |
| 成员自建工具 | 四层外部工具 global/agent/team/flow 目录 + 文件 watcher 热加载 + 层级优先（flow>team>agent>global） | ToolLoader.scala:18-60, ExternalToolConfig.scala | 成员在 `teams/<team>/tools/*.json` 放脚本工具即热生效。**能力增强的最低层已就绪** |
| 实体评审流水线 | entity-creator flow：architect→builder→reviewer，verdict switch 循环（maxLoop 3） | flows/entity-creator/flow.json | 创建实体的**评审机制已存在**，只需放权 + 加边界 |
| Mail 全量留痕 | team 内每封 Mail 记入 FlowMailStore + WS 事件 | MailTool.scala:967-1001 | 自组织协作链路**可审计**，失控可检测 |
| 防失控基件 | flow maxLoop、Mail ask 超时（60s 转后台/flow 5min 硬超时）、queue 持久化重启恢复 | EntityTypes.scala:222, MailTool.scala:35-38 | 循环上限、超时、持久化有先例可循 |

### 1.2 机制层的事实缺口

| 缺口 | 现状 | 文件依据 |
|------|------|---------|
| Flow 只能由 Delegate 触发，Manager 没有 Delegate 工具 | FlowDagRunner 唯一触发点在 DelegateTool(flow=...)；nebflow-project Manager 的 tools 列表无 Delegate | DelegateTool.scala:182-200；teams/nebflow-project/agents/Manager/agent.json |
| Skill 目录是 opt-in 且只有全局层 | agent.skills 为空 → 完全不注入 skill catalog；skills 只在 `~/.nebflow/skills/`，无 team 作用域 | SkillService.scala:189-203 |
| 实体创建是 Nebula 垄断 | Manager prompt 明令 "Do NOT try to create agents or edit team.json yourself"；成员无任何创建路径 | agents/Manager 体系 prompt（manager-prefix / team override） |
| 一切经 Manager 的文化 | Manager prompt："Verify-fix loop … **You are the junction**"；成员 prefix："Team Member → address='Manager'" 写死 | prompts/system-prefix-for-teams.md「完成回报契约」段 |
| 成员不能直 Mail Nebula、不能跨 team Mail | 路由层硬约束（仅 lead 可上行；跨 team 拒绝） | MailTool.scala:770-789, 678-683 |

> 关键判断：**用户要的"自组织涌现"，传输层和审计层已经支持，真正缺的是三样东西——协议（成员间怎么协作）、放权（谁能建什么）、守夜人（Manager 的新职责）**。这就是本设计的三个主轴。

---

## 2. Part 1 — 四实体功能区分决策框架

### 2.1 本质区分：四个不同维度的"复用单位"

| 实体 | 复用什么 | 有无运行时 | 有无身份（可被 Mail） | 生命周期 | 文件形态 |
|------|---------|-----------|---------------------|---------|---------|
| **Skill** | 方法论（"怎么做"的知识） | 无（注入现有 agent 的 prompt） | 无 | 静态文档 | `skills/<name>/SKILL.md` |
| **Tool**（编外第五实体，机制已有） | 动作（"能做什么"的命令） | 有（脚本进程） | 无 | 静态配置 | `tools/*.json`（四层目录） |
| **Agent** | 角色（身份 + 能力集 + prompt） | 有（AgentActor，按需激活） | **有** | 会话级持久 | `agents/<Name>/{agent.json,system.md}` |
| **Flow** | 流程（固定步骤 DAG，无记忆） | 有（一次性 DAG runner） | 有（可触发，一次性） | 单次执行 | `flows/<name>/flow.json` |
| **Team** | 组织（多角色 + 契约 + 共享记忆） | 有（持久挂载） | 有（Mail 到 lead） | 项目级持久 | `teams/<name>/{team.json,rules.md,agents/}` |

一句话版：**Skill 教会现有的人，Tool 给他新工具，Agent 雇一个新的人，Flow 定一条流水线，Team 建一个部门。**

### 2.2 独热判定表（agent 可执行形式，P0 直接注入 system-prefix）

> 形式呼应智能路由 v3：**有序求值、首个命中、一问一答互斥**。可直接放 `system-prefix-for-all.md` 或外置 `~/.nebflow/prompts/entity-decision.md`。每个 case 命中即终止判定。

```markdown
## Entity Decision Table（按序判定，命中即停）

输入：一个"能力/协作需求" R。

- case E1【知识沉淀】R 是"某类任务反复用到的方法/步骤/模板"，且不需要新角色来承载
  → 写 Skill（或更新已有 Skill）。不建 agent。
- case E2【动作缺口】R 是"一个可参数化的操作"，现有工具做不了，脚本能做
  → 建外部 Tool（teams/<team>/tools/*.json 或全局）。
- case E3【固定流水线】R 是"≥2 步、步骤顺序固定、路由可枚举、每次输入输出同构"的流程
  → 建 Flow。判断标志：你能画出它的 DAG 且愿意用它 maxLoop 次以上。
- case E4【单一角色缺口】R 需要"一个可被反复寻址（Mail/Delegate）的专职角色"，
  每次调用独立完成一类任务，不需要多角色协作
  → 建 Agent（team 域内走 entity-creator；优先 extends 泛型基座）。
- case E5【持续项目协作】R 围绕"一个长期存在的项目/目标"，需要多角色 + 共享上下文
  （rules/memory）+ 内部分工
  → 建 Team（Nebula 独占权限）。
- case E0【什么都不建】R 是一次性的、低频的（<3 次）、或现有实体加上一点上下文就能覆盖
  → 直接做，把可复用的部分写进 memory 或 rules.md。

顺序不可换：E1/E2 先于 E3-E5（能用知识和工具解决的，绝不动组织结构）；
E3 先于 E4（能固化成流程的不要占一个角色）；E4 先于 E5（缺一个角色不是建一个部门的理由）。
```

### 2.3 "创建新实体 vs 复用现有"的判定（同样独热）

```markdown
## Create-vs-Reuse（命中即停）

- case R1【零成本复用】现有实体 + 在任务 Mail 里多写两段上下文即可覆盖
  → 复用。创建的固定成本（目录、评审、catalog 膨胀）永远大于一次上下文。
- case R2【拉伸检测】你发现自己在"硬凑"——把任务塞给一个 description 并不匹配的成员，
  或给现有 skill 塞进第三个不相关主题
  → 停止拉伸，走创建（E1-E5）。
- case R3【频次法则】同类需求已出现 ≥3 次，且每次都要重新解释方法/路由/角色
  → 创建对应实体（对应 E1-E5 哪个 case 就建哪个）。
- case R4【职责分离】现有成员既当构建者又当验证者（Build vs Verify 分离原则）
  → 建/启用独立验证角色，而不是给现有成员加"顺便验证一下"。
- case R0【默认】以上都不命中 → 复用现有，不创建。
```

> **设计理由**：判定表放 prompt 而非代码——这是策略不是机制（呼应 v3 的规则/代码边界）；表是纯文本、每 turn 注入成本 <400 token；修改不需要重编译。P1 若验证有效，可外置为独立规则文件由 ContextRefresher 注入（与 system-prefix 同模式）。

---

## 3. Part 2 — Agent 自主创建/更新四类实体的机制

### 3.1 权限分级（谁能动什么）

| 级别 | 主体 | 可自助完成（无需审批） | 需 entity-creator 评审 | Nebula 独占 |
|------|------|----------------------|----------------------|------------|
| **L0 自服务** | 任何 team 成员 | 自己的 `memory.md`；team 域 `tools/*.json`（机制已支持热加载）；自己的 `agent.json` 的 `description`/`useWhen`/`skills` 字段（能力自述） | — | — |
| **L1 域内创建** | **Manager** | — | 在**自己 team 域内**创建/修改 agent（写入 `teams/<team>/agents/`）、创建 team 域 skill、修改 rules.md 的契约段 | — |
| **L2 全局变更** | Nebula（可由 Manager 提名触发） | — | — | 全局 standalone agent、新 team、删除实体、跨 team 共享、修改任何 agent 的 `tools`/`mcpServers`（提权面） |

**三条安全红线（entity-creator reviewer 强制检查项）：**
1. **不得自提权**：任何创建/修改不得变更 `tools`、`mcpServers`、`model`、`preset` 字段——这些字段变更一律 L2。新 agent 的 tools 必须按 architect 的角色模板给出，且 caller 是 Manager 时只能给 ≤ caller 自身工具面。
2. **不得自封官**：不得修改 `team.json` 的 `lead`；Manager 不能创建全局实体（所有产出落 team 域目录）。
3. **失败可回滚**：builder 落盘前将原文件备份到 `~/.nebflow/backups/entities/<时间戳>/`（新目录，沿用 tmp+move 原子写模式）；reviewer verdict=fail 或试用期退役 → 从备份恢复。

### 3.2 创建路径：演进 entity-creator，不新建 EntityTool

三个候选方案对比：

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| A. 新建 EntityTool（agent 直接调工具建实体） | 一步到位 | 绕过评审；权限校验全压进工具层；鼓励"顺手建一堆"；违背 dsh 对照里"创建应走受控流程"的教训 | ✗ |
| B. 成员直接写文件 + 事后评审 | 最灵活 | 文件已生效才评审 = 评审形同虚设；提权风险无法前置拦截 | ✗ |
| **C. 统一走 entity-creator + 放权 + 边界**（选定） | 复用既有 architect-builder-reviewer 循环 = 天然评审；改动面最小；文件即插件（写 JSON/MD 即生效） | 多一跳 | ✓ |

**entity-creator v2 演进内容（P1）：**

1. **callerScope 边界**：architect 节点输入新增 caller 信息。规则：
   - caller=Nebula → 权限不变（可建全局）
   - caller=Manager → 强制 team 域：agent 写 `teams/<caller-team>/agents/<Name>/`，skill 写 `teams/<team>/skills/`（P1 新目录），自动追加 team.json members（这一步允许——它不是提权，是组织扩张，Manager 本来就该管编制）
   - caller=普通成员 → 拒绝，回复"请经你的 Manager 发起"（成员的创建通道 = 向 Manager 提名，见 Part 3）
2. **builder 备份规则**：写任何实体文件前，已存在则先备份；report 中列出生成物与备份路径。
3. **reviewer 清单扩充**：在现有结构校验上加 4 项——红线字段检查（tools/mcpServers/model/preset/lead）、catalog 膨胀检查（description 是否与现有成员语义重叠 → 建议合并而非新建）、试用期标注、能力自述质量（useWhen 是否可判定）。
4. **试用标记**：新 agent 的 agent.json 允许 `"trial": true`（未知字段对现有 Decoder 无害，向后兼容；P1 再机制化为 catalog 显示 `(trial)`）。

**触发路径现状与改法**：Manager 目前无 Delegate 工具，无法触发 flow。
- P0（零代码）：Manager 保持"Mail Nebula 提名 → Nebula 触发 entity-creator"现状路径，但把提名格式标准化（见 3.3）。
- P1（一行配置 + 少量代码）：给 Manager 的 agent.json tools 加 `Delegate`（或 MailTool 增加 flow 名识别路由，与全局 catalog 文案"Mail flow-name"对齐），Manager 即可自主触发。entity-creator 的 builder 信任 caller 声明的 team 归属（lead 身份即凭证，路由层已保证只有 lead 能上行）。

### 3.3 各角色的创建入口速查

| 你是谁 | 想建 Skill | 想建 Tool | 想建 Agent | 想建 Flow | 想建 Team |
|--------|-----------|----------|-----------|----------|----------|
| 成员 | 自己写（L0，P1 前 team 域目录缺失 → 写全局 + 在自己 skills 字段登记，或先 nominations） | 直接写 team tools/ 目录（已支持） | Mail Manager 提名（附：缺口证据 ≥2 例、建议 useWhen） | Mail Manager 提名 | Mail Manager → 升级 Nebula |
| Manager | 自己写 / 走 entity-creator | 同上 | **触发 entity-creator（P1 自主 / P0 经 Nebula）** | 触发 entity-creator | Mail Nebula |
| Nebula | 任意 | 任意 | 任意（entity-creator） | 任意（entity-creator） | 任意（entity-creator） |

---

## 4. Part 3 — 成员自我能力增强与能力广播

### 4.1 三层自增强路径（由轻到重）

```
第 1 层【工具】  放 teams/<team>/tools/*.json → watcher 热加载 → 立即可用
第 2 层【Skill】 把重复劳动沉淀为 SKILL.md → 登记 agent.json skills 字段 → catalog 注入
第 3 层【角色】  能力缺口反复出现 → 提名/触发 entity-creator 建 agent（Part 2）
```

**升级触发器（什么时候从第 n 层升到 n+1 层）**：
- 工具→skill：同一个"工具 + 操作序列"被你使用 ≥3 次 → 沉淀成 skill（工具是原子动作，skill 是方法）。
- skill→agent：这个方法需要**独立身份被别人直接调用**（别人要 Mail"谁"来做这件事，而不是"告诉你怎么做"）→ 提名建 agent。
- 判定回到 Part 1 的 E1/E2/E4。

### 4.2 能力自述与广播协议（P0 全部可落地，零代码）

**关键事实**：广播机制已免费存在——成员改 `agent.json` 的 description/useWhen/skills，ContextRefresher 下一 turn 重读，TeamCatalog 自动注入所有成员。**不需要 Mail 广播、不需要新建能力目录服务。**

**缺的是更新纪律，用触发器规则补齐（写入 system-prefix-for-teams.md）：**

```markdown
## Capability Self-Update — 能力自述维护

你的 agent.json（description / useWhen / skills 三字段）是全团队路由到你 的唯一信号，
每回合自动广播给所有成员。以下触发器出现时，立即用 Edit 更新：

- 新工具建成并自测通过 → description 里加一句能力，必要时 useWhen 扩场景
- 新 skill 沉淀完成 → skills 数组登记
- 连续 2 次收到与 useWhen 不符的硬塞任务 → 收紧或修正 useWhen，Mail [INFO] Manager 说明边界
- 发现自己的 useWhen 与实际产出漂移（描述说做 A，实际总做 B）→ 以实际为准修正

纪律：
- description/useWhen/skills 之外的字段（tools/model/preset/mcpServers）你没有修改权——
  需要变更时 Mail Manager 走 entity-creator（红线见实体设计文档 §3.1）
- 重大能力域变化（如"我现在能做 X 了，X 属于新领域"）才 Mail [INFO] Manager + 相关成员；
  日常微调不需要广播——catalog 会自动同步，广播风暴比信息滞后更有害
```

**自建工具/自建 skill 的自测义务**（写入同一 prefix）：建成后必须用样例输入跑一次成功，才能写进能力自述。未自测的能力声明 = 对全团队的错误路由信号。

### 4.3 P1 机制补强（可选，按需实施）

| 补强 | 内容 | 改动面 |
|------|------|--------|
| team 域 skill 目录 | SkillService 增加加载层级 `global < team`（skills 目录扫描逻辑 + buildPerAgentCatalog 合并），成员 skill 不污染全局命名空间 | SkillService.scala、ContextRefresher.scala |
| capability map 自动化 | TeamCatalog.buildCatalog 输出成员行时附 skills 名单（数据已有，纯展示增强），Manager 巡检团队能力一览无余 | TeamCatalog.scala |
| tools/ 目录写权限审计 | FlowMailStore 之外，tools watcher 记录"谁写了哪个工具"（现在 watcher 只 reload 不记账） | ToolLoader.scala（watcher 加日志） |

---

## 5. Part 4 — Manager 重新设计（用户强调的重点）

### 5.1 角色转型：从"调度者"到三个职责带

| 现状 | 问题 | 转型后 |
|------|------|--------|
| 逐任务分解→Mail 成员→等结果→转下一步 | Manager 是全队吞吐瓶颈；每个 verify-fix 循环都要两次经它 | **只在任务入口和异常点出现** |
| Verify-fix loop "You are the junction" | QA 往返全部过 Manager，延迟×2 | 验证协议下放给成员直连（Part 5），Manager 只收**带验证证据的最终 RESULT** |
| 能力缺口 → Mail Nebula（被动） | 等缺口积累才扩编 | 主动园丁：从路由失败信号里识别缺口（见 5.3） |

**三个职责带：**

1. **契约守卫（Contract Keeper）**
   - 拥有并维护 rules.md 的「Collaboration Contract」段（模板见 §6.2）——团队的 DoD、验证配对表、循环上限、升级路径。
   - **验收责任不丢**：Manager 对 Nebula/用户报告的每个 RESULT 必须含验证证据（QA 报告/测试输出/冒烟结果）。无证据 → 打回补验证。这是"不再逐任务路由"之后 Manager 保住质量关口的机制。
2. **能力园丁（Capability Gardener）**
   - 识别缺口 → 触发 entity-creator → 试用期管理 → 转正/退役（§5.2-5.3）。
   - 事件驱动地维护 capability map（P1 起自动生成）：谁会什么、谁闲谁忙、哪个领域没覆盖。
3. **兜底仲裁（Escalation Arbiter）**
   - 只在枚举的异常点介入（§6.4）。成员间技术分歧由当事成员先自行协商一轮，协商不成才升级——Manager 是仲裁者不是传话筒。

### 5.2 新成员生命周期：识别 → 创建 → 试用 → 转正

```
[识别] 三信号任一：
  ① 成员提名 ≥2 次（同一能力缺口被不同成员/不同任务提起）
  ② Manager 拉伸路由自查：本月第 3 次把任务硬塞给不匹配的成员
  ③ Create-vs-Reuse 判定 R2/R3 命中且指向 E4（新角色）
        │
[创建] Manager 触发 entity-creator（P1 自主 / P0 经 Nebula）
  spec 必含：useWhen（可判定）、试用期任务定义（1-2 个真实小任务）、验证配对（谁验证它）
        │
[试用] agent.json 标 "trial": true（catalog 中 P1 起显示 (trial)）
  Manager 派 1-2 个真实小任务 → 按验证协议走完整链路
        │
   ┌────┴────┐
[转正]        [退役]
移除 trial     Manager 触发 entity-creator 归档：
Mail [INFO]    移入 ~/.nebflow/archives/（保留尸检价值），
全组宣布       rules.md 路由表删除该成员，
新成员能力     团队 memory 记一条"为什么不合适"
```

**试用期的意义**：新建 agent 的 description/useWhen 是 architect 猜出来的。试用期用真实任务校准它——转正前的每次任务后，Manager（或成员本人）微调 useWhen，让 catalog 信号收敛到真实能力。

### 5.3 能力缺口识别信号（Manager 的输入）

| 信号源 | 形态 | Manager 动作 |
|--------|------|-------------|
| 成员提名 Mail | `[NOMINATION] 缺口：X。证据：任务 A、B 都需要 X 但无人可做。建议 useWhen：…` | 记入待办，第二次同类提名 → 触发创建 |
| 路由失败自查 | Manager 自己连续对同一类任务找不到 fit 成员 | 触发创建或调整 rules.md 路由表 |
| 成员自述漂移报告 | 成员 [INFO] "我连续收到 X 类任务但 useWhen 不含 X" | 判断：扩成员 useWhen（L0 教它做）还是建新角色（职责分离） |
| QA 打回模式 | QA 报告同类问题反复出现（如"前端总是忘了暗色模式"） | 判断：写 skill（E1 知识问题）而非建人 |

> 最后一条是园丁的关键洞察：**很多"能力缺口"其实是"知识缺口"——先问 E1（写个 skill 教会所有人）再问 E4（雇个新角色）**。这是 Part 1 判定表顺序（E1/E2 先于 E3-E5）在 Manager 视角的投影。

### 5.4 Manager prompt 改造要点（P0 具体改动）

对 `~/.nebflow/prompts/manager-prefix.md`（及各 team override 的 Manager system.md）：

1. 删除/改写 "Verify-fix loop: … You are the junction" → 成员间直连验证，你只收带证据的 RESULT。
2. 新增「三个职责带」段 + §5.2 生命周期 + §5.3 缺口信号表。
3. 新增「介入点白名单」段（§6.4 枚举）——白名单之外的成员间协作**不主动过问、不索取中间状态**（防 Manager 退化为微管理者）。
4. "When to Request a New Specialist" 段：Mail Nebula 的格式改为 `[NOMINATION]` 标准格式；P1 后改为直接触发 entity-creator。

---

## 6. Part 5 — 自组织涌现协作协议（核心）

### 6.1 设计目标与现状阻碍

**目标**：成员完成任务后**自发**找 QA 验证 → QA 打回/汇报 → 全程不经 Manager → Manager 只收最终结果。涌现而非派发。

**现状阻碍（全部是 prompt 层，不是机制层）：**
- 成员 prefix 把完成回报写死为 `address="Manager"`；
- Manager prompt 自任 junction；
- 没有成员间任务传递的标准格式（上下文丢失风险是"不敢放权直连"的根因）；
- 没有防失控协议（用户担心的"打回循环""失控"没有答案）。

### 6.2 团队协作契约（写入各 team rules.md，Manager 拥有）

```markdown
## Collaboration Contract — 协作契约

### Definition of Done（DoD）
- 代码类交付：必须经对应 QA 验证 PASS 后才算完成。未过 QA 直接 Mail Manager
  "完成" = 违约，Manager 打回补验证。
- 文档/研究类交付：自检清单全过 + 关键结论有出处。
- 所有交付：附验证证据（QA 报告 / 测试输出 / 冒烟结果）。

### 验证配对表
| 产出者 | 验证者 | 验证手段 |
|--------|--------|---------|
| Backend | qa-backend | sbt compile/test + 冒烟 curl |
| Frontend | qa-frontend | 截图三端对比 + 控制台无错 |
| Docs | Manager 抽查 | 事实核对 |
（研究型团队同理：czt-writer 的稿子 → czt-researcher 事实核查 → czt-physicist 公式核对）

### 循环与升级上限（防失控，硬性数字）
- 同一任务 QA 打回 ≥ 2 次仍未 PASS → 任意一方 MUST 升级 Manager（[INTERRUPT] + 打回历史摘要）
- 任务信封 HOP ≥ 3 → 禁止继续转发，MUST 升级 Manager
- 信封 CHAIN 中已出现过的成员不得再次进入 → 违反即升级（环检测）
- 下游成员 >30 分钟无响应（queue 未消化）→ Mail [INTERRUPT] Manager
```

### 6.3 Task Envelope（任务信封）— 成员间传递任务的标准格式

**纯文本协议（P0），放在 message 开头：**

```
[TASK] <一句话任务描述>
[CONTEXT] <自包含背景：为什么做、相关前情、文件路径。接收方没有你的上下文>
[ACCEPTANCE] <验收条件，逐条可二值判断>
[SENDER] <发起者短名——最终结果向此人汇报，不向 Manager>
[HOP] <n>  [CHAIN] <a→b→…>  <协作链，每转发一次 +1 并追加>
[DEADLINE/SIZE] <可选：预期规模，防止接包方低估>
```

**字段语义与防失控机理：**

| 字段 | 作用 | 防什么 |
|------|------|--------|
| SENDER | **归责制**：信封只有一个发起者，最终结果回到 SENDER。QA 的 PASS 报给 builder 而不是 Manager；builder 汇总后以自己名义报 Manager | 结果汇总失控——Manager 永远知道找谁要最终结果；不会出现"三个人都以为对方汇报了" |
| ACCEPTANCE | 接包方的完成定义 | 打回循环的一半源于验收标准缺失——没有标准的 PASS/FAIL 都是主观的 |
| HOP/CHAIN | 跳数上限 + 环检测 | 无限转发链、A→B→C→A 死循环 |
| CONTEXT | 自包含 | 上下文丢失——这是"不敢放权直连"的根因，格式化解决它 |

### 6.4 代码→QA 自组织协议（完整时序）

以 Backend 完成编码为例（Manager 已在最初派发时附上契约引用"完成后按 rules.md 协作契约执行"）：

```
1. Backend 完成 + 自测通过
2. Backend Mail qa-backend（delivery=queue，含 Task Envelope，SENDER=backend，HOP=1）
   —— 不 Mail Manager。契约 DoD：过 QA 才算完成。
3. qa-backend 验证，两种结果：
   a. PASS → Mail backend [RESULT] PASS + 证据（测试输出/冒烟结果）
      → backend 汇总（改动摘要 + QA 报告）Mail Manager [RESULT]
      → Manager 验收证据齐全 → Mail Nebula [RESULT]。链路闭环，Manager 只出现一次。
   b. FAIL → Mail backend [RESULT] FAIL + 逐条失败项 + 复现方式
      → backend 修复 → 重新 Mail qa-backend（同一信封，HOP 不变但 RETRY +1）
      → RETRY ≥2 仍 FAIL → 升级 Manager [INTERRUPT]（附两轮打回摘要）
4. Manager 介入点（白名单，除此之外不过问）：
   ① 循环上限触发（RETRY≥2 / HOP≥3 / 环检测命中）
   ② 超时（下游 >30min 无响应）
   ③ 成员间分歧协商一轮未决（技术方案之争、责任归属之争）
   ④ 需要跨 team 协作或上行 Nebula/用户
   ⑤ 能力缺口（信封传不下去——没人能接）
   ⑥ 最终验收（RESULT 证据检查）
5. Manager 收到无证据 RESULT → 打回："补 QA 验证后再报"（契约强制，不是官僚）
```

**与现状机制的契合（为什么不用写代码就能跑）**：
- queue 模式持久化 + 逐条处理 → qa-backend 正在忙也不丢（MailTool 已有）；
- [RESULT]/[INTERRUPT] TYPE 语义完全复用现有五种标签；
- FlowMailStore 自动记录 backend→qa、qa→backend、backend→Manager 全链路 → 事后可审计"这个任务走了直连协议还是退化成了经理中转"；
- expectsMail 提醒机制保证成员完成 turn 必须回 Mail（现在回报对象从"写死 Manager"变为"信封 SENDER"）。

### 6.5 防失控汇总表

| 失控模式 | 防线 | 层级 |
|---------|------|------|
| 无限打回循环 | RETRY ≥2 强制升级 Manager | 契约（P0）|
| 无限转发链 | HOP ≥3 禁止转发 | 契约（P0）|
| 协作环 A→B→C→A | CHAIN 查重，违反即升级 | 契约（P0）|
| 下游失联 | 30min 无响应 → [INTERRUPT] Manager；queue 持久化保证不丢消息 | 契约 + 机制（已有）|
| 结果汇总黑洞 | SENDER 归责制：每信封一个负责人 | 契约（P0）|
| 成员绕过验证谎报完成 | Manager 只认带证据 RESULT | 契约（P0）|
| 广播风暴 | 能力微调不广播（catalog 自动同步）；重大变更才 [INFO] | 契约（P0）|
| Manager 微管理回潮 | 介入点白名单之外不过问 | 契约（P0）|
| 实体创建泛滥 | 三级权限 + entity-creator 评审 + R0-R4 复用优先判定 | 协议（P0/P1）|
| 提权攻击 | 红线字段（tools/mcpServers/model/preset/lead）变更一律 L2 | entity-creator 评审清单（P1）|

---

## 7. Part 6 — 实施路线图

### P0 — 最小可行（纯 prompt/文件层，零代码改动）

| # | 改动 | 文件 | 内容 |
|---|------|------|------|
| 1 | 成员回报契约改版 | `~/.nebflow/prompts/system-prefix-for-teams.md` | 「完成回报契约」段：`address="Manager"` → 按 Task Envelope 的 SENDER 回报；无信封任务回 Manager。新增：Task Envelope 格式、协作直连指引、能力自述维护协议（§4.2）、实体判定表（§2.2 可放此处或 system-prefix-for-all） |
| 2 | Manager 角色重定义 | `~/.nebflow/prompts/manager-prefix.md` + 各 team `agents/Manager/system.md` | §5.4 四项改造（junction 删除、三职责带、生命周期、介入白名单） |
| 3 | 团队契约落地 | 各 `~/.nebflow/teams/<name>/rules.md` | 增「Collaboration Contract」段（§6.2 模板，按团队定制配对表——nebflow-project 用 qa-backend/qa-frontend；czt-project 用 researcher/physicist 交叉验证） |
| 4 | 提名格式 | Manager prompt 内 | 成员→Manager→Nebula 的 `[NOMINATION]` 标准格式（P0 创建仍走 Nebula 中转） |
| 5 | 试点 | 选一个真实任务 | 在 nebflow-project 跑通完整直连链路（§7 验收条件） |

**P0 验收条件（二值可断言）：**
1. 给 nebflow-project 派一个含缺陷的代码任务（如"实现 X 但留一个测试不过的 bug"）。
2. 断言 FlowMailStore 记录序列包含：Manager→backend（派发）、backend→qa-backend（信封直连）、qa-backend→backend（FAIL 打回）、backend→qa-backend（重送）、qa-backend→backend（PASS）、backend→Manager（带证据 RESULT）——**全程 Manager→成员只有一次派发**。
3. 断言 backend 报 Manager 的 RESULT 含 QA PASS 证据；无证据时 Manager 打回（第 2 个测试用例：让 backend 跳过 QA 直接报 → Manager 应回"补验证"）。
4. 断言成员 agent.json description 更新后（手动改一个字），下一 turn 其他成员 system prompt 的 TeamCatalog 已含新文本（机制验证，证明广播免费）。

### P1 — 机制层（小改动，每项独立可实施）

| # | 改动 | 涉及文件 | 说明 |
|---|------|---------|------|
| 1 | Manager 可自主触发 entity-creator | Manager agent.json（tools +Delegate）或 MailTool（flow 名路由，与 catalog 文案对齐） | 放权 L1 落地。推荐后者：MailTool.deliverToShortName 前加 loadFlow 检查（EntityLoader.loadFlow 已有） |
| 2 | entity-creator v2（callerScope + 备份 + trial + 红线清单） | flows/entity-creator/ 三个 agent 的 system.md + flow.json（可选加 caller 输入） | 纯 prompt 演进 + builder 加备份纪律；红线检查进 reviewer 清单 |
| 3 | team 域 skill 目录 | SkillService.scala（层级加载 global<team）、ContextRefresher.scala | 成员 skill 不污染全局；catalog 合并注入 |
| 4 | capability map | TeamCatalog.scala（成员行附 skills 名单） | Manager 园丁的仪表盘 |
| 5 | 超时巡检（可选） | FlowTreeActor / 新 TimerService | 契约的"30min 无响应"机制化：定时扫描未消化 queue → 提醒 Manager。P0 用契约自律替代 |
| 6 | Envelope 机制化（可选） | MailTool.scala（TYPE 加 "TASK"；schema 加 hop/chain/senderReturnTo 可选字段）+ AgentActor（expectsMail 校验回报对象=SENDER） | 协议从"文本约定"升级为"结构化字段"，防漏填。验证 P0 纪律不足时才做 |

**P1 验收条件（摘要）：**
1. Manager 自主触发 entity-creator 创建 team 域 agent：断言新 agent 落在 `teams/<team>/agents/`（非全局）、team.json members 已追加、备份目录有原文件快照、新 agent.json 无 tools 字段（或 ≤ Manager 工具面）。
2. 红线测试：让 entity-creator 处理一个"给现有 agent 加 mcpServers"的请求 → reviewer 拒绝并提示 L2 路径。
3. team 域 skill：成员在 `teams/<t>/skills/x/SKILL.md` 建 skill → 该 team 成员下一 turn catalog 含 x；全局 agent 的 catalog 不含 x。
4. 回归：不带信封的普通 Mail 行为与 P0 完全一致（向后兼容）。
5. 冒烟：`sbt run` + `curl /api/health` 200；现有测试集全绿。

### P2 — 完整自组织生态

| # | 改动 | 说明 |
|---|------|------|
| 1 | 成员自建 flow（team 域） | entity-creator 放权到"信封链固化"：某协作链（builder→qa→builder）重复出现 ≥N 次 → Manager 建议固化为 flow（E3 判定），成员可提名 |
| 2 | 跨 team 实体共享 / 借调 | Nebula 仲裁：skill 全局化、agent 跨 team 挂载（team.json members 引用全局 agent 已支持，缺的是协议与目录） |
| 3 | Manager 能力审计 flow | 仿 memory-consolidation：定期扫描 FlowMailStore（打回率、拉伸路由、提名积压）→ 产出实体变更建议（该建 skill / 该扩 useWhen / 该退役） |
| 4 | 协作可视化 | FlowMailStore → 成员协作图/热力图（谁和谁直连最多、谁总被打回），Manager 巡检失控模式的前端面板 |

**P2 验收（方向性）**：一个从零开始的团队任务，Manager 仅一次派发 + 一次验收，FlowMailStore 显示完整直连链；协作图无 HOP≥3 链；审计 flow 至少产出一次正确的能力建议。

---

## 8. 风险与边界

| 风险 | 缓解 |
|------|------|
| 直连协议被成员无视，退回经理中转 | Manager 介入白名单 + 打回无证据 RESULT；FlowMailStore 可审计退化（P2 可视化后一目了然） |
| 信封字段漏填（P0 纯文本纪律） | expectsMail 机制保证有回 Mail；acceptance 缺失时 QA 有权拒收（"无验收条件不验证"写进 qa prompt）；P1 机制化兜底 |
| entity-creator 被 Manager 滥用（建人上瘾） | R0-R4 复用优先判定 + reviewer 的 catalog 膨胀检查 + 试用期淘汰成本 |
| 能力自述被改坏（description 写歪导致路由劣化） | 自述只开三个安全字段；试用期校准机制；Manager 抽查 capability map |
| 协作契约与现有 team rules 冲突 | 契约是 rules.md 的独立段落，每 team 独立定制；P0 试点先在一个 team 验证再推广 |
| 双 Manager prompt 体系（全局 manager-prefix + team override）漂移 | 全局 prefix 放通用协议（信封/介入白名单），team override 只放团队定制（配对表/DoD）——分层原则写进本文档，prompt 改造时执行 |

---

## 附：设计依据（文件:行）

- MailTool 三模式与路由边界：`src/main/scala/nebflow/core/tools/MailTool.scala:17-30, 637-669, 770-789`
- expectsMail 提醒：`src/main/scala/nebflow/agent/AgentActor.scala:1716-1719`；Manager 豁免 `MailTool.scala:903`
- 每 turn 热刷新（team agent def / catalog / rules / memory）：`src/main/scala/nebflow/agent/ContextRefresher.scala:289-389`
- TeamCatalog 注入成员 description/useWhen：`src/main/scala/nebflow/core/entity/TeamCatalog.scala:11-34`
- skill catalog opt-in 语义：`src/main/scala/nebflow/core/skill/SkillService.scala:189-203`
- 外部工具四层 + 热加载：`src/main/scala/nebflow/core/tools/ToolLoader.scala:18-60`、`ExternalToolConfig.scala:8-16`
- flow 唯一触发点（Delegate flow=）：`src/main/scala/nebflow/core/tools/DelegateTool.scala:182-200`
- flow DAG 路由原语与 maxLoop：`src/main/scala/nebflow/core/entity/EntityTypes.scala:112-246`
- entity-creator 三段式：`~/.nebflow/flows/entity-creator/flow.json`；复用优先/tool 最小化原则：其 architect/system.md §Design Philosophy
- 现行 Manager 中心化 prompt：`~/.nebflow/prompts/manager-prefix.md`、`~/.nebflow/teams/nebflow-project/agents/Manager/system.md`（junction/禁止自建条款）
- 成员完成回报契约（P0 改造点）：`~/.nebflow/prompts/system-prefix-for-teams.md`「完成回报契约」段
- Mail 留痕审计：`MailTool.scala:967-1001`（FlowMailStore.append）
- 独热 case 外置规则思想：`/tmp/smart-routing-design-v3.md` §2.5；Everything is a Plugin：`/tmp/deepseek-harness-flow-comparison-v2.md`
