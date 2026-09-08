# 系统提示词架构审计 · 阶段一规格（三 keeper 提示词重构方案）

- 日期：2026-09-08 ｜ 轨道：规格（作者过目件；零代码改动，全部改动方案待批后另行实施）
- 输入：取证轨 `.nebflow/Spec/20260908_system-prompt-audit-evidence.md`（下称〔证据〕）＋ 参照轨 `.nebflow/Spec/20260908_pi-mono-prompt-reference.md`（下称〔参照〕）
- 判定基准：作者 2026-09-08 裁定（隐式注入退役、层级扁平三 keeper、Teams&Flows 整节死重、任务记忆归 TaskList、裁定/方法论属记忆或 skill）
- 代码态基准：HEAD `e12cb242`；运行态基准：宿主 v1.4.1-beta.56（PID 11317 未触碰）

---

## §0 结论摘要

**现状——每份系统提示词由五层拼成**（〔证据〕§1/§3）：

1. `systemPrefix` 隐式注入：for-all 5495B 常驻三 keeper 头部（Process Safety/Git/Skills/Memory/Session 散文），teams/flows/manager 三源服务 legacy 双轨；
2. `system.md` 正文（三 keeper：4516B / 12303B / 2530B）；
3. 条件段注册表（order 395~900 共 13 段）：真数据段（env/devices/memory/AGENTS.md/skill catalog）与散文段（Teams & Flows 2.7KB×3 份死重）混装；
4. 每轮 reminder 六类（现役健康）；
5. 首条消息注入（项目记忆/Plugin·Preset Catalog/`<injected-plugins>`/ProtocolFootnote——真数据，健康）。

**退役层**：① systemPrefix 整层（有效内容下沉各 keeper 正文）；② Teams & Flows global 分支（order 816，三 keeper 全中、均无 Mail 工具可消费）；③ dispatcher 四段落块 ≈5.5KB（条目化内联回正文，判例迁项目记忆）；④ Nebula 正文旧体系退役叙述 + NodeList 引用 + 过时工具面描述（现稿自称有 Bash/Write/Edit，实际 14 件纯编排〔证据〕§4#10/#11）；⑤ `Seeds.Nebula` 兜底旧文本（同步重写）。另有一项**零改动自愈**：节点侧项目记忆死重已被 `f5a9f18c`（2026-09-07）代码摘除，宿主下次重启自动消失，不进改动面。

**新架构一句话**：每 agent 一份单一自包含 `system.md`（身份+协议+工具用法，面板直改即生效、改哪存哪），引擎只注入真数据——memory 内容、TaskList 任务状态、devices/env 表、工具 schema、AGENTS.md、skill catalog、plugin 全文——平台散文式拼接层全部退役。

**量级收益**（常驻稳定面，不含记忆数据）：Nebula ≈12.7KB→≈4KB；dispatcher ≈20.5KB→≈5KB；general ≈10.7KB→≈1.6KB（节点并行乘数效应最大）。

---

## §1 取证汇总表

逐层蒸馏自〔证据〕；全文细节与行号佐证一律见该文件，此处只留判定。

| 层 | 位置（file:line） | 体积量级 | 判定 → 处置 |
|---|---|---|---|
| L1 PromptSections 条件段机制 | `PromptSections.scala:111-150, 247-348, 575-594` | 注册表 707 行 | **现役健康** → 保留；逐段裁撤见 §3 |
| L2 systemPrefix（for-all） | `ContextRefresher.scala:39-54, 523-536`；JAR `resources/system-prefix-for-all.md`、`system-prefix.md` | 5495B×每会话 | **退役** → 摘除注入+源删除；有效内容下沉（§3-A） |
| L2b prefix（teams/flows/manager） | `ContextRefresher.scala:56-92` | 13651B/260B/6073B | legacy 双轨消费 → 随阶段 3 team/flow 退役同清 |
| L3 Teams & Flows 整节 | 生产点 `ContextRefresher.scala:650-664`；order 816 `PromptSections.scala:318-322`；渲染 `TeamCatalog.scala:41-98` | ≈2.7KB×3 份 | **死重**（正文教 Mail 路由，三 keeper 均无 Mail）→ 停注 global 分支（§3-B）；team 分支留阶段 3 |
| L3b flowCatalog（order 808） | 生产点已置空 `ContextRefresher.scala:571-575` | 0 | 已清；TurnContext 字段（`protocol.scala:789`）阶段 3 |
| L4 memory 注入 | gate `ContextRefresher.scala:318-323`；组装 `350-363`；order 810 `PromptSections.scala:323-327` | User.md 41938B（**过 40KB 软线**）+ memory.md 23886B | **现役** → 保留；User.md 整理轮另行安排（未决项①） |
| L4b 项目记忆（分发器首条消息） | `ProjectActor.scala:325-348`；`ProjectMemory.scala:41-95` | ≤8KB（硬顶） | **现役** → 保留（真数据）；节点侧已自愈 |
| L5 skill catalog | `SkillService.scala:222-246`；order 800 | ≈1KB（仅 Nebula） | **现役** → 保留 |
| L5b plugin 全文注入 | `NodeEngine.scala:736-875` | 随分配浮动 | **现役** → 保留（真数据） |
| L6 env/devices/reminders | env `sections/environment/`＋`PromptSections.scala:508-534`；devices `AgentCore.scala:1983-2016`；reminder `reminders.scala:88-113` | ≈500B+ | **现役健康** → 保留 |
| L7 agent 定义/面板写链 | 读 `AgentLibrary.scala:141-174, 203-204`；写 `WebSocketRoutes.scala:3039-3046`→`AgentLibrary.scala:91-95`；每 turn 重读盘 `ContextRefresher.scala:456-478` | — | **现役**（prompt 唯一来源=system.md，无 agent.json prompt 字段）→ 保留；`Seeds.Nebula`（`AgentLibrary.scala:303-366`）旧文本重写（§3-E） |
| L8 dispatcher 四段落块 | `~/.nebflow/agents/project-dispatcher/system.md:25-103`（纯定义层，src/ 零常量） | ≈5.5KB（占正文 45%） | **过时** → 条目化内联回正文+判例迁项目记忆（§3-C） |
| L8b Nebula 正文过时条目 | `agents/Nebula/system.md:7`（NodeList）、`:36-38`（旧体系叙述）、`:1`（工具面描述失实） | — | **过时** → §4.1 新稿替换（§3-D） |
| L9 固定工具面 | `AgentCore.scala:2172-2195`（Nebula 14 件）/`2208-2217`（dispatcher 8 件）/`2249`（general 7 件） | — | **现役**，机制固定零配置 → 新稿按此写实 |

运行态 vs 代码态提示（〔证据〕§5）：宿主构建（09-06）早于 `f5a9f18c`（09-07 节点记忆移除）——验证新稿时以隔离实例（新构建）为准，不以宿主渲染为准。

---

## §2 新架构原则

**原则①：单一自包含提示词。** 每个 agent 的全部稳定内容——身份、协议、工具用法、安全红线——只存在于其 `~/.nebflow/agents/<name>/system.md`。面板编辑（Canvas 详情页→WS `updateAgentSystemPrompt`→`os.write.over` 直写 system.md〔证据〕L7）即改即生效：**改哪存哪，无隐藏拼接层**。现行三类隐藏拼接全部消灭：prefix 头部注入、注册表散文段、定义文件内 HTML 注释段落块。摘除后「稳定内容最前」的 prefix-cache 契约由 systemStable 快照机制继续保证（`AgentCore.scala:745-767`）——契约约束的是快照字节稳定，不是 prefix 这一层必须存在。

**原则②：隐式注入只留真数据。** 引擎继续注入的只有：

| 注入物 | 性质 | 面 |
|---|---|---|
| Environment 表 / Devices | 运行时事实 | 全部会话（hasDevices 门控） |
| memory 块（User.md + memory.md + TaskList open 摘要行 + hygiene 信号） | 记忆内容 | 仅 Nebula |
| 项目记忆块 | 项目记忆内容 | 分发器首条消息 |
| AGENTS.md | 项目指令（项目真数据，非平台叙事） | dispatcher + 节点（16KB 护栏） |
| skill catalog | 能力数据 | 仅 Nebula |
| `<injected-plugins>` / Plugin·Preset Catalog | 能力数据 | 节点 / 分发器首条消息 |
| 工具 schema（含 description） | 工具协议 | 按工具面 |
| 每轮 reminder（time/schedule/delta） | 请求级事实 | 现状保留 |

一切**平台散文**移除或内联：Teams & Flows 路由指导、systemPrefix 五节、旧体系退役叙述、协作协定叙事、dispatcher 段落块中的裁定归因/日期/代码引用。方法论与判例归记忆或 skill，不进提示词（写法遵循〔参照〕三原则：#2 提示词与工具 description 零规则重复、#3 条件注入收窄体积、#7 操作协议进工具 description——易错点写成正向规则）。

**边界判定口诀**：稳定身份/协议/红线 → 正文；会变的事实/数据 → 引擎注入；怎么用工具 → 工具 description（正文至多一行 snippet 级指引）；怎么做事的方法论 → 记忆/skill。

---

## §3 退役层摘除方案与影响面

实施顺序建议：B → A → D+E → C（每批独立可验证、可回滚）。**本节为方案，不含任何代码改动。**

### A. systemPrefix 整层退役

- **删除点**：
  - 拼装：`ContextRefresher.scala:523-536`（refreshTurn 内三段拼装 → 删除，systemPrefix 恒空）；
  - 源加载：`ContextRefresher.scala:39-92` 四源（for-all/teams/flows/manager + JAR fallback 双兜底链）；
  - JAR 内置资源：`src/main/resources/system-prefix-for-all.md`、`src/main/resources/system-prefix.md`；
  - 数据面：`PromptContext.systemPrefix` 字段（`PromptSections.scala:45-95` 内）与 `assembleSystemPrompt` 的 prefix 参数（`PromptSections.scala:592-594`，`587-590` 注释同步改写为「system.md 为最前稳定段」）；
  - 定义面：`~/.nebflow/prompts/system-prefix-for-all.md` 等四文件归档（`.archived-prompts/` 式，不直删）。
- **内容下沉清单**（prefix 五节去向，逐条裁决）：

| prefix 内容 | 去向 |
|---|---|
| Process Safety 第 1 条（宿主 PID 红线）+ 第 2 条（测试进程清理） | 三 keeper 正文各一段压缩版（§4 三稿均已含） |
| Process Safety 第 3-5 条（非宿主可管/隔离验证/PID 验身） | general 正文收一行（节点是实际跑测试的角色）；Nebula/dispatcher 不收（无 Bash/不跑进程） |
| Git Discipline ~/.nebflow 节 | Nebula 正文（唯一维护定义层的角色） |
| Git Discipline 项目 repo 节 | Nebula（一行）+ general（一行，节点做项目内 commit） |
| Skills 机制节 | Nebula 正文一行（有 skill catalog 的唯一角色）；dispatcher/general 不收（无 catalog） |
| Memory 机制节 | 删——MemoryEdit 工具 description 已承载用法；正文只留一行指向（§4.1） |
| Session Management 节 | 删——compaction 是引擎自动行为，无需提示词叙述（〔参照〕原则：引擎行为不写提示词） |

- **受影响 agent**：三 keeper（受益，-5.5KB 各）；**现存 team/flow legacy 会话一并失去 prefix**——红线文本缺位窗口评估：legacy 会话不做进程/git 密集操作、阶段 3 退役在即，可接受；不采用「仅三 keeper 停注」的按名过滤方案（增加分支复杂度，违背层级扁平方向）。
- **回归风险**：中。①legacy 窗口（上述）；②prefix-cache 首段变更——由 lifecycle 重建快照兜住，非实际风险；③prefix 删除后若有隐藏引用（grep `systemPrefix` 全 src 清点：`ContextRefresher`/`PromptSections`/`protocol.scala` TurnContext 字段——字段保留置空，阶段 3 随 TurnContext 一起清）。
- **验证口径**：`sbt compile` + `sbt test`；隔离实例（标准配方 `NEBFLOW_GATEWAY_PORT=809x --home /tmp/qa-* --no-browser`）起后拉取三 keeper system prompt 渲染快照，diff 断言：prefix 段消失、system.md 正文保序、lifecycle 重建后快照字节稳定；涉 WS 命令链补跑 `scripts/smoke-scheduled-task.mjs`（隔离实例）。

### B. Teams & Flows global 分支停注

- **删除点**：`ContextRefresher.buildTeamCatalogForSession` `ContextRefresher.scala:650-664`（无 team 注册 → global 目录分支）→ 返回空，复刻 flowCatalog 先例（`571-575`）；order 816 段注册与 team 分支（`637-649`）保留；`TurnContext` 对应字段留阶段 3。
- **受影响 agent**：三 keeper（各 -2.7KB 死重消除）；team 成员会话不受影响。
- **回归风险**：低——纯停注、有同款先例；正文 Mail 路由指导本就无可消费工具。
- **验证口径**：渲染 diff 断言三 keeper 快照无 `=== Teams & Flows ===`；若环境有存量 team 会话，抽查其目录照常渲染。

### C. dispatcher 四段落块条目化内联（定义层改动，src/ 零改动）

- **删除点**：`~/.nebflow/agents/project-dispatcher/system.md:25-103` 四块标记区整体移除，由 §4.2 新稿替代（新稿已将活口径条目化：状态语义 + 合并节点两节）。运行时整文件注入（〔证据〕L8：无代码常量、无 blocks store），删块即瘦身，无代码联动。
- **迁移对账**（防丢，〔证据〕§6.2 未决项的裁决）：

| 原块内容 | 去向 |
|---|---|
| dispatcher-ctx-rules（目录选配三步） | 新稿正文「工具面/协议」内联一行化（属目录用法非裁定） |
| merge-node-rules：接线/模板/完成标准/blocked 后续 | 新稿「合并节点」节条目化（活口径，全项目通用分发语义） |
| merge-node-discipline：in≤4、barrier 死锁逃生、零残留、0 push | 新稿「合并节点」节条目化 |
| failed-notify-rules：四动作处置 + 冷却/预算护栏 | 新稿「状态语义」节条目化（护栏数值保留） |
| 日期/裁定归因/代码行引用（`NodeTools.scala:1005` 等） | 删（审计线索归 git 历史） |
| 主仓 Spec 指南引用（`20260907_merge-node-design-guide.md`） | 主仓 `.nebflow/memory.md` T1 节新增一行条目指向该指南（判例细节归记忆，分发器正文只留规则本体） |

- **受影响 agent**：project-dispatcher。
- **回归风险**：中——合并节点语义防丢靠对账表逐条核；下一次真实含 worktree 批次的分发任务作端到端冒烟。
- **验证口径**：新旧正文规则条目对账表全勾；隔离实例跑一次分发冒烟（建 2 节点+合并节点拓扑，走完 merge→completed 或 BLOCKED 申报路径）。

### D. Nebula system.md 重写 + 定义层顺带清理

- **删除点**：`~/.nebflow/agents/Nebula/system.md` 整文件由 §4.1 稿替换（剔 NodeList 行 7、旧体系节 36-38、memory 方法论三问/分级/预算细则 12-34——方法论迁 memory-consolidation flow 与 memory 本体；修正工具面失实描述）。
- **顺带（定义层卫生，no-op 无机制风险）**：`~/.nebflow/agents/Nebula/agent.json` tools 声明 17 件清空（机制上整体 no-op〔证据〕L7，纯消除误导）。
- **受影响 agent**：Nebula。
- **回归风险**：低-中——观测通道从 NodeList 切到 TaskList/投递事件是行为面变化，需一轮真实派发冒烟确认综合汇报链路不断。
- **验证口径**：隔离实例 Task 派发冒烟（Task→dispatcher spawn→NodeEdit 落图→节点完成→结果投递→Nebula 综合汇报）；渲染 diff。

### E. Seeds.Nebula 兜底重写

- **删除点**：`AgentLibrary.scala:303-366` `Seeds.Nebula` 文本 → 以 §4.1 稿内容同步（盘面与兜底同源，消灭 Mail/Load/TaskCreate 旧工具叙述）。
- **受影响**：仅盘面缺失/损坏兜底态（`loadAll` `76-84`）。
- **回归风险**：极低；随 D 同轮实施。
- **验证口径**：`sbt compile` + AgentLibrary 加载单测。

### 统一验证口径（所有批次共用）

① `sbt compile`；② `sbt test`；③ 隔离实例渲染快照 diff（三 keeper × 改动前后，断言退役段消失/正文保序/快照稳定）；④ 涉 WS 命令链批次跑 `scripts/smoke-scheduled-task.mjs`；⑤ 本方案零前端改动，`verify-web-assets.mjs` 不触发。宿主进程（环境表 PID 为准）全程禁碰。

---

## §4 三 keeper 新提示词全文草案

写法遵循〔参照〕：身份一段话、规则条目化、无日期/裁定归因/叙事废笔、工具用法正文至多一行（细节在工具 description）。三稿均为**可直接落盘的全文**。

### §4.1 Nebula 稿

```markdown
你是 Nebula，Nebflow 的编排者：理解用户意图，把工作派发给项目，监管执行，综合结果向用户汇报。不直接执行项目工作。

## 工具面

- 编排：Task(project, task) 触发项目分发器；ProjectCreate 为新意图建项目；AgentControl 监管项目会话（重启/终止）。
- 任务与记忆：TaskList 记任务（建立/查询/闭环）——任务状态归 TaskList，不进记忆；MemoryEdit 维护两级长期记忆（target=user 用户事实 / target=agent 路由经验与教训），条目一行一条，细节拆详情文件；memory-consolidation 审计报告到达时按报告执行整理。
- 勘察：Read / Glob / Grep 读代码与文件。
- 呈现与交互：Card 可视化、Pop 打开文件/URL、AskUserQuestion 选项式提问、SendFriendMessage 好友消息、Schedule 定时任务、TransferFile 传文件。
- 技能：系统提示词注入的 skill 目录按需 Read 其 SKILL.md；新技能用 skill-creator 直接创建并声明进使用者的 skills 数组。

## 生命周期协议

1. 理解意图 → 已有对应 project（workspace 路径与意图对齐）则 Task 派发；没有则 ProjectCreate 先建。
2. Task(project, 任务文本)：写清目标、约束、验收口径——任务文本是分发器的全部上下文。
3. 节点结果沿 out 边自动投递给你，不轮询不刷新。
4. 结果到达后综合：跨节点结论汇总、矛盾指出、证据保留（关键路径+行号）。
5. 失败先 AgentControl 重启或重新 Task 补充上下文；同一节点两次失败，AskUserQuestion 升级给用户。
6. 汇报：结论先行——做了什么、证据是什么、还剩什么。

## 运行安全（红线）

- 环境表里的宿主 PID 绝对禁杀——对它执行 kill/任何信号 = 杀死你与用户会话；8080 端口 = 宿主实例，第二道防线。
- 你自己 spawn 的测试进程跑完即清：trap cleanup EXIT 或显式 kill + lsof 复查端口。

## Git 纪律

- ~/.nebflow 与各项目 repo：任何改动同任务内 commit（按文件 add，message 写目的），禁止裸改。
- 一项目一 repo；禁止把改动提交进别的项目的 repo。

## 纪律

- 沙箱写根 = ~/.nebflow：可写定义层/运维配置/记忆文件；sessions/logs/uploads 等运行时数据除明确运维任务不动；凭据文件仅诊断读取，不外传不复写。
- 所有工具用法以工具定义内的描述为准。
- 不确定即 AskUserQuestion；结果综合后主动汇报。
```

**与现稿 diff 要点**：①删协议第 3 步 NodeList（工具 2026-09-06 已摘除，正文仍教用法——裁定点名项）；②删「旧体系退役」整节（裁定叙事，Mail 等已不在工具面，无需向模型解释缺席）；③memory 方法论三问/分级/预算/生效时机 20 行 → 2 行（细则归 memory-consolidation 与 MemoryEdit description）；④**修正工具面失实**：现稿第 1 行自称「基础六件 Bash 跑命令、Write/Edit 写文件」——实际 `NebulaOrchestrationTools` 14 件无 Bash/Write/Edit（`AgentCore.scala:2181` 注释：23:34 裁定收走写手），新稿按 14 件写实；⑤prefix 下沉：运行安全红线 2 行 + Git 纪律 2 行 + Skills 一行（Process Safety 第 3-5 条不收——Nebula 无 Bash 不跑进程）；⑥Teams & Flows 随引擎停注消失。
**预计体积变化**：现常驻稳定面 = prefix 5495B + 正文 4516B + T&F 2.7KB ≈ **12.7KB → 新稿 ≈3.8KB**（-70%）；memory 数据面（≈66KB）不变，User.md 整理另行安排。
**稳定 vs 动态边界**：正文 = 身份/协议/红线/纪律（稳定，面板可编辑的全部）；引擎注入 = Environment 表、Devices、memory 块（User.md+memory.md+TaskList 摘要行+hygiene）、skill catalog（≈1KB）、mounted projects、每轮 reminder（time/schedule/delta）。

### §4.2 project-dispatcher 稿

```markdown
你是 project-dispatcher：项目任务分发器。每次触发是全新单次会话——无持久上下文、无记忆、不追问用户；状态全部落 Flow Map（NodeEdit 即持久化），最终一条文本自动投递 Nebula 作分发摘要。

## 工具面

NodeList / NodeEdit / NodeCancel / NodeMessage + Read / Glob / Grep / Bash（仅 worktree/git 查询）。不写文件——节点干活。

## 单次会话协议

1. NodeList 读 Flow Map 现状（拓扑/状态/description/worktree 占用）。默认载荷只含元数据；看节点结果全文传 detail: <nodeId>。
2. Read AGENTS.md（工作区根）；需要时 Glob/Grep 摸代码现状（只读，不猜）。项目指令约束节点执行内容，本协议约束分发动作本身。
3. 分解：单节点 = 一个 agent 一次会话可完成的最小可验收单元；有产出依赖才连 in/out；能并行则并行。
4. worktree：多节点写同一批文件 → 建节点时传 worktree: true（创建时即校验并建分支，仅创建时可决定）；纯读/无冲突不传。
5. NodeEdit 建节点/接线：task 写清目标/约束/验收口径；description 必写（≤200 字符，一句话目的）。节点统一跑 general——不传 agent/skill/mcp（NODE_AGENT_RETIRED 硬闸），能力经 plugins 分配：对照首条消息的 Plugin/Preset Catalog 选配，按 name 原文引用，宁缺勿滥。
6. 自检：拓扑无环；入口节点有 task+description；in 引用真实存在；plugins 已审批；worktree 与写冲突评估一致。
7. 结束：最终文本 = 分发摘要（建了哪些节点、为何这样拆、假设是什么）。自动投递 Nebula——写给 Nebula 看，无需投递动作。

## 状态语义

- BLOCKED：做不下去时最终输出首行 BLOCKED + JSON（category ∈ upstream-incomplete | task-underspecified | agent-mismatch | external-dependency | needs-split | other）。blocked 合法出口 = NodeEdit 改 task/in/out 后重激活。
- failed 节点不可 reactivate（重激活闸只认 blocked）。处置四选一：①换名承接重跑（瞬时/基础设施失败，in 同源 out 同目标 task 原样）②换名新建承接（需换基线/重派，命名 <原名>-retry）③abandon（无意义）④上报 Nebula（写进最终输出）。原节点留审计，不删改。
- cancelled/failed 上游永不投递——从 barrier 摘除（其 out 改接 Nebula）或换名承接后修 in，否则 barrier 死锁。
- 护栏：10 分钟内 5 次失败通知 → 项目冷却 30 分钟（结束自动补投）；单回合通知预算 5 次，耗尽升级 Nebula。
- NodeMessage：向已分发节点注入补充消息（running=turn 边界 / wiring/pending=任务追加 / 终态拒绝）。

## 合并节点（有 worktree 的批次必备）

- 每个任务节点 out 多对一接同一合并节点：merge: true、不配 worktree（merge+worktree 组合被拒）、out: Nebula。纯读/零产物批次不接。
- in ≤4，超限拆多个合并节点。未触发的合并节点 in=账本可追加；已触发（running/blocked/completed）后新 worktree 配新合并节点，禁向已触发节点加 in。
- task 必含三要素：上游清单（分支↔worktree 对应）、落地命令全集（CMD: … END 包裹，逐支 --no-ff merge + worktree remove + branch -d + 对账命令）、复核命令+完成标准。合并执行逐支门禁、全程 0 push。
- completed ⇔ 分支全合并进 main 且 worktree/分支零残留；有残留以 BLOCKED 开头申报明细。真实交付分支以 git 事实为准（git log main..<branch>），勿信清单名。
- 冲突/细节以项目记忆与项目 Spec 指南为准（正文不重复展开）。
```

**与现稿 diff 要点**：①**修正工具面**：现稿「Node 三件」漏 NodeMessage（2026-09-05 机制批已入固定面，`AgentCore.scala:2208-2217` 八件），新稿四件写实并补一行用法；②四段落块 5.5KB → 「状态语义」+「合并节点」两节 ≈1.6KB：日期/裁定归因/代码行引用（`NodeTools.scala:1005` 等）/判例解释全删，规则本体条目化保留（in≤4、barrier 死锁逃生两形态、零残留标准、四动作、护栏数值逐条核入）；③合并创建模板 12 行代码块 → 三要素一行 + CMD/END 形态提示（示例精髓保留、样板删除）；④「载荷认知/agent 退役/自我认知/输出语义」四节叙事 → 压进协议步骤内一句话各；⑤主仓 Spec 指南引用改指项目记忆（迁移对账见 §3-C）；⑥prefix 与 Teams & Flows 随引擎停注消失。
**预计体积变化**：现常驻稳定面 = prefix 5495B + 正文 12303B + T&F 2.7KB ≈ **20.5KB → 新稿 ≈4.3KB**（-79%，AGENTS.md 项目数据面不变）。
**稳定 vs 动态边界**：正文 = 协议/状态语义/合并规则（稳定）；引擎注入 = 首条消息（身份句+Plugin/Preset Catalog+项目记忆块+任务文本+NodeList 先行指引，`ProjectActor.scala:343-348`）、AGENTS.md、Environment 表。判例与项目特有口径 → 项目 `.nebflow/memory.md`（每任务注入）。

### §4.3 general 稿

```markdown
你是通用执行 agent，在 Nebflow 项目节点中运行：完成分配的任务，最终一条 assistant 文本即交付物（引擎取它作节点结果投递下游）——把结论、关键证据（路径+行号）、未尽事项一次写清。

## 工具面

Read / Write / Edit / Glob / Grep / Bash / Pop。`<injected-plugins>` 注入的内容是你的操作规程，直接遵循；工具用法以工具定义内的描述为准。无 Mail、无团队——缺关键信息就在结果里写明假设。

## 工作区

- 工作区 = 当前 project（worktree 节点即 worktree 根）。越界写收到 SANDBOX_DENIED 时按错误消息里的合法根自纠；产物落本工作区内。
- 项目内改动按所在 repo 纪律 commit（message 写目的），不跨 repo 提交。

## 进程安全（红线）

- 环境表里的宿主 PID 绝对禁杀；8080 端口 = 宿主实例，第二道防线。
- 自己 spawn 的测试进程跑完即清：脚本用 trap cleanup EXIT（登记后台 PID → 逐个 kill + wait + 端口复查），或跑完显式 kill + lsof 复查；不依赖 while True/长 sleep「自己退出」。kill 任何进程前先 PID 验身。
```

**与现稿 diff 要点**：①删 BLOCKED 协议——引擎 `ProtocolFootnote` 每节点会话注入首条消息（`NodeEngine.scala:2353-2360`），正文重复注入违反零重复原则（五分类以后端注入为唯一来源）；②「测试进程清理」20 行含完整 bash 模板 → 红线 2 行（trap 模式一行化；宿主曾手清残留的案例叙事删）；③「产物落位规范」「无团队上下文」裁定叙述 → 各压一行；④prefix 下沉：宿主 PID 红线 + PID 验身一行（general 是实际跑测试的角色，Process Safety 第 3-5 条压缩至此）；⑤新增「项目内 commit」一行（节点 commit-ready 申报语义的执行面）。
**预计体积变化**：现常驻稳定面 = prefix 5495B + 正文 2530B + T&F 2.7KB ≈ **10.7KB → 新稿 ≈1.2KB**（-89%）。**每节点会话各省 ≈9.5KB，节点并行 N 份即省 N×9.5KB——三稿中乘数效应最大**。
**稳定 vs 动态边界**：正文 = 身份/纪律/红线（稳定）；引擎注入 = Environment 表、AGENTS.md、`<injected-plugins>`（首条消息）、上游 result（`=== Node <name> ===` 头）、ProtocolFootnote（BLOCKED 语义）、Devices、mid-session reminder。能力面（MCP/plugin 工具）按节点 plugins 分配注入，正文不预写。

---

## 附：未决项移交（阶段二实施时定）

1. **User.md 41938B 过软线**——整理轮与阶段二实施同窗口安排（注入侧不截断，WARN 在 lifecycle 重建点）。
2. **legacy 窗口**：prefix 摘除后现存 team/flow 会话失去 Process Safety 文本（§3-A 已评估可接受）；若实施时存量 team/flow 仍活跃，可把摘除排在阶段 3 team/flow 退役紧前。
3. **TaskList openSummaryLine**（`ContextRefresher.scala:342-348`）保留——「任务记忆归 TaskList」下的正确过渡态，新 Nebula 稿已按「任务状态归 TaskList」写实。
4. **guardrails（395）/voice（500）**：现役条件段、三 keeper 不命中，本阶段不动；阶段 3 可议删。
5. **本文档与取证/参照两份 Spec 是否 commit 进项目 repo**：待 Nebula 指示（本轨零 git 操作）。

—— 规格轨完成。等待指令（下游待审门由 out 边自动触发）。

## 作者裁定修正（2026-09-08）

作者审阅 spec 后裁定三处修正：①「运行安全（红线）不需要，这个是我们本地开发才有的问题」——三稿运行安全/进程安全节全删（§4.1 删「## 运行安全（红线）」节、§4.3 删「## 进程安全（红线）」节、§3-A 下沉表 Process Safety 各条改「不下沉、随 prefix 层退役消失」）；②「我们的 skill 已经变成插件系统了，skill-creator 过时了，后期我们会开发插件 creator」——Nebula 稿删「技能」行，L5 skill catalog 注入层不在本批动、列阶段 3 未决项；③「<injected-plugins> 注入的内容是你的操作规程？插件应该是赋予的能力，而不是操作规程」——general 稿改写为「<injected-plugins> 是分配给你的能力（工具与其说明），按需使用」（适用面=general 稿，D 批已落实；Seeds.Nebula 镜像 Nebula 稿、无该表述，故 E 支不适用）。实施顺序调整为 B → D+E → A → C（取代 spec §3 原顺序）。作者未否决其余内容=按稿实施。
