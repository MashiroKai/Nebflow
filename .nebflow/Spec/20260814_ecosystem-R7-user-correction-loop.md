# R7 — 用户纠偏闭环：捕获、沉淀、传递与 Team 自优化

> 四实体生态设计讨论报告 R7 · 2026-08-15 · 纯设计文档，不改代码
> 事实来源：源码 archive/scala 分支 + ~/.nebflow 实测；衔接 R3（skill 体系）/ R5（组织学）/ R6（Agent 三层进化）

---

## 1. 用户观点解读

用户五条输入拆解为五个设计命题：

| # | 原话要点 | 系统语言 |
|---|---------|---------|
| U1 | "skill 重要来源是用户的纠正，Nebula 有很强的职责去编写 skill，应用到具体 agent" | 用户纠偏是 skill 体系的一等输入源，不是副产品 |
| U2 | "代码层面已有记录用户输入的基座，但实际没有利用" | 基座（sessions 全量落盘）已存在，缺的是**消费机制** |
| U3 | "用户教过一次、纠正过一次，要能记住。特别是怎么传递到实际使用 skill 的 agent，因为 Nebula 看不到这些 skill" | 闭环的两难段：**写入端**（谁识别、写哪）与**触达端**（执行 agent 怎么用上） |
| U4 | "设计哲学、taste 要学会沉淀和传递，一定要落实到具体执行的 agent" | 软知识（无可执行代码对应的偏好）也要有结构化载体 |
| U5 | "team agent 是针对 team 作用域专门 taste 的 agent；Team 要随用户纠偏优化自己，变得更加专用" | 纠偏的累计效应要能驱动**组织层**演化（成员/skill/rules），不止个体记忆 |

一句话：**纠偏 = 分布式产生（任意会话）→ 语义路由（四层载体）→ 确定触达（执行 agent）→ 累计演化（team）→ 有界收敛（防膨胀）**。

---

## 2. 现状核实（代码事实）

### 2.1 用户输入基座：三处全量落盘，一处被动消费

- **sessions 全量落盘**：`~/.nebflow/sessions/<id>.json` 存完整消息列表（SessionStore.scala:339 `os.write.over(sessionFile(id), msgs.asJson)`）。实测单会话 110 条 user / 85 条 assistant 消息。AskUserQuestion 的回答以 tool result 回注会话（AskUserQuestionTool 阻塞等待、答案进消息流），权限批准/拒绝经 gateway 落盘。**"教过什么"在磁盘上全部存在。**
- **唯一消费路径 = Nebula 的 compaction save turn**：RootSaveMemoryReminder 明文要求记录 "Corrections of your output or approach — record what they wanted instead" 到 User.md（CompactService.scala:117-119）；NebulaMemoryHook 在压缩前用 LLM 抽事实 → User.md（NebulaMemoryHook.scala:26-37，≥20 条消息触发）。问题：**触发时机是 compaction 将至（被动、随机）**，且只覆盖 Nebula 主会话。
- **team 成员会话是捕获盲区**：用户直接在 Frontend 面板说"要毛玻璃"这类最高价值纠偏，不在任何抽取机制范围内——Frontend memory 里确实记了用户原话（"现在是透明面板，正常我们应该是毛玻璃面板"），但那是成员凭 system.md 常识的自觉行为，非机制产物。且成员 depth=1 拿 Manager profile（MailTool.scala:896），Root/Worker 段的纠偏文案一个都收不到（R6 §2.2 profile 错位）。

### 2.2 沉淀四层现状：载体全在，路由判据散落

| 载体 | 注入机制（代码锚点） | 现状 |
|------|---------------------|------|
| User.md | buildMemoryBlock 每 turn 注入所有有 memory 的 agent（ContextRefresher.scala:255-277, 374-376） | Nebula 维护，活跃 |
| agent memory | 同上，条件 `isTeamAgent \|\| name=="Nebula"` | team 成员自持，质量好但孤岛（R6 §3.2） |
| team rules.md | TeamCatalog 注入 `=== Team Rules ===` 段（ContextRefresher.scala:417-422）+ RulesStore folder 链 | 人类约束窗口，无用户裁定段（缺口） |
| skill | buildPerAgentCatalog opt-in（SkillService.scala:189-203） | 0/36 agent 声明；skill-proposals 断链（零产出零消费者，已定两周期后移除） |

四层路由判据目前散落在三个 save reminder prompt + R6 三层分置表，没有统一契约；"什么该升级成 skill"的判定只存在于 Worker 段文案（"seen 2+"），而 team 成员收不到。

### 2.3 传递现状：主干免费存在，缺口在通知与 standalone

- **下轮刷新已经免费存在**：ContextRefresher 所有源每 turn 从盘上重解析（mtime cache，未变文件只付一次 stat）——memory/rules/skill catalog/agent.json 的任何改动，**正在运行的会话下一个 turn 自动生效**。agent.json 热重载即证据（ContextRefresher.scala:300-307 注释：panel 编辑在运行中 actor 上生效）。U3 的"传递"问题比直觉乐观：**写入正确的文件 = 传递自动完成**。
- 真实缺口三个：(a) **无更新感知**——对比 gitBranch 有 SystemReminder 变更提醒（ContextRefresher.scala:215-239），memory/skill 更新没有任何提醒通道，跑长任务的 agent 不知道自己刚被教了；(b) **standalone agents 无 memory 注入**（Coder/Explorer 拿不到 User.md 与任何 memory——ContextRefresher.scala:374-376 的注入条件排除了它们）；(c) **Nebula 自己 skills 字段为空**——连 skill catalog 都收不到，"很强的职责去编写 skill"缺少全库视野支撑。
- 事实澄清：Nebula "看不到 skill" 不是权限限制（Read 工具可读任意文件），是**注入与职责设计**使然——system prompt 不给它全库索引，也没有 prompt 把"审 skill"写进它的职责。

---

## 3. 闭环五环节设计

![用户纠偏闭环](/tmp/r7-loop.svg)

### 3.1 捕获：当事者识别为主，上报为辅

**什么算纠偏**（信号分级，决定处理时机）：

| 级 | 信号 | 例 |
|----|------|----|
| A 显式纠正 | 否定语义词："不对/错了/别这样/我说的是/重新做" | "不是毛玻璃，是磨砂" |
| B 行为否决 | 验收打回、方案重做、拒绝合并、RETRY | 方案 Pop 后否决重做 |
| C 偏好陈述 | 规范性表达："以后都/统一/我喜欢" | "以后按钮统一圆角 12px" |
| D 澄清回答 | AskUserQuestion 的选择、权限拒绝（否掉某种工具用法） | 拒绝 `rm -rf` 类操作 |

**捕获机制选型**：

| 方案 | 机制 | 优点 | 缺点 |
|------|------|------|------|
| M1 当事 agent 自识别 | 会话内识别（A/B 当轮、C/D 攒 save turn 批处理） | 语义最准（完整上下文在当事会话）；搭 save turn 便车零新增 LLM 调用 | 依赖 prompt 纪律 |
| M2 当事 agent 主动上报 | A/B 类当轮发 `[USER-RULING]` 标签 Mail 给 Manager | 实时、结构化、可累计计数 | 每会话多一次 Mail |
| M3 Nebula 会话内识别 | 现状（save turn 文案） | 已存在 | 只覆盖主会话 |
| M4 异步扫描 sessions | 独立 LLM 扫历史会话 | 全覆盖、零打扰 | 成本高、滞后、脱水文本识别质量差——且与 ExperienceExtractor 旁路同病（已定移除） |

**推荐 M1+M2 混合，A/B 当轮、C/D 攒批**。理由：纠偏的识别必须在有完整上下文的当事会话做（M4 拿到的是没有工具结果、没有方案原文的脱水记录）；save turn 四步循环（RECORD→ORGANIZE→VERIFY→CLEAR）是现成的批量沉淀节奏，不必新造触发器。M4 明确不采用——但保留 sessions 基座的**审计价值**：用户质疑"我什么时候教过这个"时可回溯，这是基座的正确利用方式。
**前置依赖**：修复 profile 错位（R6 差距 #1）——team 成员拿到含纠偏文案的 Worker 焦点 save reminder，捕获才有触达面。

### 3.2 沉淀：四层路由判据 + Nebula 写 skill 的操作化

**路由判据**（合并 R6 三层分置，补 skill 层，写入 Manager/Nebula 契约）：

| 信号特征 | 去处 | 判据（一句话可判） |
|---------|------|------------------|
| 跨领域稳定偏好 | User.md | 去掉领域词仍成立（"用户厌恶绝对化表述"） |
| 本 team 多于一个成员要遵守 | team rules.md | 消费者 ≥2（"弹窗一律毛玻璃"——前后端+QA 都要管） |
| "怎么做某类事"的可复用方法 | skill（全局目录 + team 命名空间） | 跨会话可复用 + 有明确触发场景（"如何发布 Release 版"） |
| 只有当事角色干活用得上 | agent memory | 域内执行细节（"实现毛玻璃要带 -webkit- 前缀"） |

**Nebula "编写 skill" 的职责操作化**（解 U3 写入端）：Nebula 对 skill 的职责不是逐字写正文，而是**四权分置**——定规范（skill-creator 标准）、审重叠（对照现有 12 个 skill，决定 new 还是 update）、管登记（agent.json skills 字段）、派订阅（team 默认订阅，见 §4）。**编写权在当事者**（只有它有纠偏的完整上下文：用户原话、被打回的方案、正确做法），上报 Mail 附 skill 草案全文（M2 扩展）；Nebula 元数据级终审。给 Nebula 注入全库 skill 索引（R3 §3.3-2 已设计）作为审批视野。这同时接上 R3 §3.5：skill-proposals 的消费者由本闭环的"Nebula 终审"替代（原旁路移除后不复活）。

### 3.3 传递：一条主干 + 两个增强

- **T1 下轮刷新（主干，零代码）**：§2.3 已证——写对文件，下 turn 自动进 system prompt。team 会话多为 Mail 短会话，"下一 turn"≈"下次激活"，延迟可接受。**传递问题的真正解法是沉淀路由写对地方，不是造新通道。**
- **T2 更新提醒（P2 可选）**：复用 SystemReminder 机制（gitBranch reminder 是现成模板）——skill/memory 落盘后向该 agent 正在运行的会话注入一条提醒。仅当 T1 延迟被实证为问题才做。
- **T3 taste 三层配方**（解 U4）："执行 agent 的审美"由三层叠加，重心随成熟度右移：
  1. **system prompt taste 段（基调）**：每 team 定制的一句话审美立场（nebflow 的 Frontend 与 slideblocks 的 Frontend 不同——现状四团队同模板 1163 词是反面锚点，R5 §2.5）；
  2. **memory 案例（判例）**：用户裁定的具体案例 + "Read when" 触发条件（Frontend memory 是现成范本）；
  3. **skill 规范（成文法）**：同类裁定 seen 2+ 后固化为量化标准（如 `slideblocks/visual-style`：圆角/间距/色板数值），system prompt 只留一句指向。
  演化路径即 R6 三层进化的 taste 特化：**prompt 给方向 → memory 攒判例 → skill 定标准**。
- **standalone 缺口的裁决**：Coder/Explorer 无 memory 注入，**维持**。它们是通用工具型 agent，taste 应由调用方携带（team agent 产出的方案文档/验收条件里内联用户裁定），而非全局注入——否则 Explorer 每次探索都背着所有项目的审美包袱，违背最小心智。

### 3.4 Team 自优化：纠偏累计 → 组织演化

- **证据积累**：Manager 从三处计数——成员 `[USER-RULING]` 上报、rules.md 裁定段变更频率、成员 memory 中 correction 密度（Manager 可 Read 成员 memory 文件，同域可见）。
- **触发**：事件阈值而非日历——同类裁定累计 ≥3 次，或某成员 memory 反复出现同类纠偏（说明其 prompt/skill 缺了这段），Manager 向 Nebula 提组织变更提案：修订 rules / 调整 skill 订阅 / 新建或拆分成员。
- **执行与审批**：组织变更走 entity-creator flow（已有）；审批分层与 R6 §3.3 同构——skill 订阅/rules 修订 Manager+Nebula 两级即可；涉及成员增删的加用户确认。
- **"更专用"的量化表达**（体检时看趋势，不设硬指标）：team 命名空间 skill 数、rules.md 用户裁定段密度、成员 system.md 本项目定制段占比。四团队 Frontend 同模板 = 定制度为零，是基线反例。

### 3.5 防膨胀：记住 ≠ 全记

- **入口三问**（写入闸门，进 save turn 契约）：跨会话可复用？作用域匹配（不是把 agent 细节写进 User.md）？非一次 Read 可得？——用户教过的东西默认满足第三条（HARD TO OBTAIN），这是纠偏相对普通观察的特殊优先权，但不豁免前两条。
- **纠偏替换原则**：新裁定到来时同主题旧条目必须失效——"用户改主意"是最高优先级删除信号，这是用户纠偏**驱动**防过时的机制本体（取代 R3 已否决的 90 天日历复审）。
- **升格泄压**：rules.md 裁定段 >20 行 → 说明该开 skill 了；memory ≤80 行触发 consolidation（R6 红线沿用）；team 命名空间 skill 零订阅两个体检周期 → 退役（R3 机制沿用）。
- **唯一的周期动作**是 save turn 本身——compaction 是容量事件不是日历事件，与"否决定期复审"不冲突。

---

## 4. 与已定架构的衔接

1. **命名空间（本轮拍板）**：team 专用 skill 物理全局 + frontmatter `audience: <team>` 元数据 + 该 team 新建成员默认订阅。闭环产出的 skill 天然带归属，Nebula 审批时核对 audience 与订阅清单。
2. **opt-in（R3）**：闭环最后一步强制登记 skills 字段——**没有订阅的 skill 等于没教**。这直接解 R3 G2（0/36 采用）：纠偏闭环是 skill 体系第一个真实供给源，冷启动由"用户纠正"这个最强动机点燃。
3. **三层进化（R6）**：纠偏闭环是三层进化的输入信号源——memory 层记判例（3.3-T3）、skill 层收方法（3.2 路由）、system prompt 层只承接基调与指向。"seen 2+"升级门槛与纠偏计数（≥3 触发组织变更）复用同一计数器语义。
4. **上行单通道（R5）**：成员纠偏上报一律经 Manager 转报 Nebula（Manager 初判路由、Nebula 终审全局层），不破"Manager 拥有完整全局记忆"的约束；Manager 的裁定路由职责写进 manager-prefix。
5. **ExperienceExtractor 移除（已定）**：本闭环捕获用 M1/M2（当事者 + 上下文），不复活旁路 LLM 扫描；sessions 基座转为审计回溯用途。

---

## 5. 实施要点（文件级）

**P0（零代码，契约/文案）**
1. `system-prefix-for-teams.md`：增"纠偏识别与上报"契约——A/B 类当轮 Mail `[USER-RULING]`（附方案原文与用户原话），C/D 类 save turn 批量记 memory。
2. `CompactService.scala` ManagerSaveMemoryReminder / WorkerSaveMemoryReminder（文案级改动）：Worker 段补"用户原话级裁定先记 memory 再上报"；**前置依赖**：R6 差距 #1 的 profile 修复（成员拿 Worker 焦点）。
3. `~/.nebflow/prompts/manager-prefix.md`：增裁定路由职责——用户级转 Nebula / 项目级记 rules.md / 方法类提议 skill（附草案）/ 域内留给成员 memory；同类累计 ≥3 提组织变更。
4. Nebula system.md：增 skill 终审职责段（四权分置）；agent.json 给 Nebula 声明全库索引（R3 §3.3-2 的实现位）。
5. skill-creator SKILL.md：补 `audience` 字段指引 + 纠偏来源 skill 的撰写标准（用户原话进 Evidence，做法进正文）。

**P1（小代码）**
6. MailTool TYPE 标签扩展或约定 `RESULT + [USER-RULING]`（零机制改动，走标签约定即可）；FlowMailStore 天然留痕供 Manager 计数。
7. entity-creator flow（entity-creator 的 reviewer 清单）：新建 team agent 时按 audience 命名空间自动填 skills 字段（默认订阅）；换域校验同时检查订阅漂移。
8. rules.md 模板增"用户裁定"段（区分于人类约束窗口的静态规则，标注日期与来源会话）。

**P2（可选）**
9. T2 SystemReminder 知识更新提醒（gitBranch reminder 模式复用）。

**验收锚点**
- 在 nebflow-project/Frontend 会话制造一次纠偏（"以后弹窗都用毛玻璃"）→ 断言 Manager 当轮收到 [USER-RULING]，下次 save turn 后 rules.md 或 memory 出现该条（含用户原话与日期）。
- 制造一次方法类纠偏（"Release 要先跑冒烟再 tag"）→ 断言 30 天内出现 `nebflow/release` skill（audience 正确）+ 相关 agent.json skills 字段更新 + 下一 turn catalog 含条目。
- 更新已有 skill 后，订阅 agent 的新会话对同类问题的回答引用新版本内容（问它"发 Release 第一步做什么"）。
- 防膨胀：连续三次同类裁定后断言规则升格（memory 条目合并/进入 skill），而非三处重复各记一份。

---

## 6. 开放问题（附倾向）

| # | 问题 | 倾向 |
|---|------|------|
| 1 | A/B 强信号是否必须当轮上报，还是全部攒 save turn？ | **当轮上报**——长会话中途教的规矩该当场生效；纯攒批会丢即时性，且 save turn 时机随机不可控 |
| 2 | Nebula 审 skill 是全文审还是元数据审？ | **元数据审 + Evidence 抽查**（用户原话是否支撑做法）——全文审会重新把 Nebula 变瓶颈 |
| 3 | system prompt 的 taste 基调段谁来写？ | team 创建时 entity-creator 引导用户口述一句基调；此后只由闭环沉淀修改，不手写扩写 |
| 4 | T2 即时提醒要不要做？ | **P2 再议**——先实测 T1 在 Mail 短会话模型下的真实延迟；跑长任务的场景出现再做 |
| 5 | 周期体检（R6 P1）与"否决定期复审"的边界？ | 体检看**结构指标**（长度/订阅率/路由偏离），内容过时由纠偏驱动——体检保留但并入事件触发（如 consolidation 例行），不做日历式内容复审 |
| 6 | 用户纠偏是否需要用户确认才沉淀（写 rules/skill 前问一句）？ | **分级**：memory 层免确认（私有笔记性质）；rules.md 与 skill 层首次落盘时 Manager/Nebula 顺带告知用户（不阻塞），用户可当场否决——教过的东西被看见，本身是信任反馈 |
