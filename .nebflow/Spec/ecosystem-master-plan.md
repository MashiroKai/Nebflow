> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Nebflow 四实体生态 · 统一实施方案（收口整合）· v2

> **v2 · 2026-08-15 修订**（v1 同日创建）· 修订依据：用户终审反馈三条——① 三条定量红线太过严格且没必要 → 软化为人工判断（§8.2-4）② 部分方案工程上过于理想化 → 首版降级为渐进 MVP（W2/W3/W4）③ 大工程实施要保质保量 → 新增"§10 实施纪律" · 纯文档整合，不改代码 · 输入：R1-R8 八份设计报告 + 用户四轮反馈（三轮设计反馈 + 终审）+ 已定决策（skill 命名空间）
> 本方案职责：把八份"各说各的"报告咬合成**一份有依赖顺序、无内部矛盾、逐条可验收**的实施蓝图。所有设计细节以原报告为准，此处只做裁决、排序与接线。

---

## 0. 一页结论

| 项 | 结论 |
|---|---|
| 主线 | **一台引擎**（R7 用户纠偏闭环）驱动三块进化面（R3 skill / R6 agent / R5 team），**一个执行面**（R8 结构化契约承接 R4 尺子），**一个底座**（R1 FlowTrigger 放权承载 R2 权限矩阵） |
| 矛盾 | 8 处全部裁决（§2），其中 4 处为任务预标注、4 处为本次整合新发现 |
| 波次 | Wave 0 在途（清障+放权地基）→ **W1 纠偏闭环点火**（零代码为主，可与 W2 并行）→ **W2 Flow 引擎升级**（代码大头）→ **W3 创建放权 + entity-creator v2** → **W4 组织自优化与收敛体检** |
| 新增并入 | `input_history.jsonl` 用户原语查询通道（用户第三轮要求，§5.3） |
| v2 修订 | 红线软化为人裁（§8.2-4）；W2-P2/P3、W3、W4-1 首版降级为 MVP；W1 锚点 2 改事件驱动；entities 版本化前置到 W1 前；新增"§10 实施纪律"（两段式节奏+实测回顾+并行纪律+交付清单） |

---

## 1. 主线叙事：八份文档如何咬合成一个系统

![四实体生态总架构：实体 × 机制 × 文档来源](assets/ecosystem-master-arch.svg)

八份报告不是并列的八个专题，而是一个动力系统：**用户纠偏是能量来源，权限底座决定能量往哪流，结构化契约决定流程可信度，三块进化面消耗能量让生态越用越准。**

### 1.1 一台引擎：R7 是 R3/R6/R5/R2 的共同动力源

R7（用户纠偏闭环）解决的不是单一问题，而是同时给四份报告供血：

- **R7 → R3**：skill 体系最大的死结是"机制齐备、全线断路"（0/36 agent 声明 skills、proposals 零产出零消费）。纠偏闭环是 skill 的**第一个真实供给源**——用户纠正动机最强、当事 agent 上下文最全（R7 §4-2"冷启动由用户纠正这个最强动机点燃"）。同时"纠偏替换原则"取代了 R3 已被否决的 90 天日历复审，成为 skill 防过时的机制本体。
- **R7 → R6**：R6 三层进化（memory→skill→prompt）的触发器现状只有 compaction 一个被动信号。R7 的信号分级（A 纠正/B 打回/C 偏好/D 澄清）与计数（seen 2+ / 同类≥3）就是三层进化的输入信号源；T3 taste 三层配方（prompt 给方向→memory 攒判例→skill 定标准）是 R6 进化循环在"用户审美"维度的特化。
- **R7 → R5**：R5 的"Team 随用户纠偏优化自己变得更专用"缺一个触发器——R7 §3.4 给出事件阈值（同类裁定累计 ≥3 次 → Manager 向 Nebula 提组织变更提案），组织演化从口号变成可计数机制。
- **R7 → R2**：上述组织变更的执行通道就是 entity-creator（R2）——闭环由此接入创建底座。四层路由判据（User.md/rules/memory/skill）同时是 R2 权限矩阵中"谁能写哪个载体"的对偶面。

**一句话：没有 R7，R3 的 skill 目录是空仓库、R6 的进化是随机漫步、R5 的自优化是没有触发器的愿望。**

### 1.2 一个执行面：R8 是 R4 尺子的引擎化

R4 给出了"什么是好的 Flow"的判断条件（存在性三判据、拆分三理由、收敛三层、17 条 checklist），但它是**文档纪律**——靠 architect/reviewer 自觉。R8 把其中最关键的三条变成了**机制事实**：

| R4 的尺子（纪律） | R8 的引擎（机制） |
|---|---|
| C2 "switch 必须含终止路径，case keys 覆盖决策空间" | FlowReport 强制 verdict ∈ case keys + strict 默认 + per-node 契约注入 + ToolError 当轮自纠（R8 §3.3） |
| "verdict 靠文本猜肯定不对"（用户原语） | `$x.slots.<field>` 结构化引用；substring 兜底降级为显式 opt-in（R8 §3.2） |
| 收敛三层"每个循环要能回答转几圈" | 加载校验：纯环 reject、单节点 reject、可达性+终止路径检查（R8 §5 Phase 2） |
| 拆分三理由之"收敛控制点" | 计数 Barrier + maxFanout 护栏（并行扇出"阻塞等齐所有结果"，用户原语的直接机制化） |

同时 R8 §4 的审计把 R4 的实体判定落到存量资产上：2 个纯环断裂 flow（memory-consolidation / release-stable）、research 错位、academic-survey 实为 flow。**尺子量出来的病灶，由引擎升级一并治。**

### 1.3 一个底座：R1 放权是 R2 权限矩阵的机制依据

R2 的核心结论——"谁能创建实体"的本质 = "谁能触发创建流程"——在机制上完全依赖 R1：

1. R1 把 flow 触发从 Delegate（Nebula 专属）拆出为 FlowTrigger，且**不进 NebulaExclusiveTools、由 agent.json `flows` 白名单驱动**（R1 §3.3-B）；
2. 于是 R2 只需一行配置放权：Manager `flows: ["entity-creator"]` → Team 获得自建能力（callerScope 闸保证其只能产 team 域实体）；
3. caller 身份无需新认证机制——"只有声明者能触发 flow + 只有 lead 能上行"天然构成身份链（R2 §3.3-v2-1）。

R1 拆分同时修掉的 slash 触发 bug（教 agent 用不路由 flow 的 Mail）是这条链上的清障项。

### 1.4 互为表里：R5×R6 与命名空间裁决

- **R5 是 R6 的环境，R6 是 R5 的细胞**：R5 命题 A（最小心智）= R6 好 Agent 五条的操作环境；R5 的换域校验（qa-backend 漂移）= R6 好/坏样本的机制化对策；R6 的周期体检（prompt-engineer 转型体检医生）= R5 checklist 的执行者。
- **skill 命名空间（已定决策）消解 R3/R5 的结构性张力**：R2 原方案"team 域 skill 目录"（teams/<t>/skills/）与 R3"只有全局层"直接冲突。最终裁决：**物理上只有 `~/.nebflow/skills/` 一个目录**（统一创建区=统一治理区，引用追踪/防过时需要全库视角），**语义归属用命名空间表达**（`nebflow/release`、`slideblocks/visual-style`），frontmatter 加 `audience: <team>`，该 team 新建成员默认订阅。"这个 skill 属于谁"由订阅表达，不由目录表达——与 R3 §3.2"归属粒度由订阅表达"完全一致，同时满足 R5"team 专用资产"的诉求。
- project 层（cwd skills）保留为"随 git 仓库分发的项目 skill"，与组织层级无关（R3 §6 已定）。

### 1.5 实体边界的最终判定（全生态统一口径）

| 实体 | 一句话判定 | 出处 |
|---|---|---|
| Skill | 教 agent **怎么做一件事**（知识注入，单 agent 内完成） | 用户原语 + R8 §4 |
| Flow | **把一件事交给固定多节点流程**（引擎强制路由+结构化收敛，≥2 角色分离） | 用户原语 + R8 §4 |
| 单 agent flow | **非法**——一个 agent 能做的用 skill 代替；加载 reject 节点数 <2 | 用户否决 R4 §3.4 |
| Agent | 持久身份+可进化定义（最小心智），需要权限/观测入口时用 flows 白名单而非 flow 包装 | R6 + R8 §1.4 |
| Team | 需要跨调用共享记忆/身份的持久组织；事物型 team 的终局常是 flow（组织退化为管线） | R5 §3.6 |

---

## 2. 矛盾裁决记录（8 项）

| # | 冲突 | 裁决 | 依据 |
|---|---|---|---|
| 1 | R3 §3.4-③ 90 天日历复审 skill vs 用户否决 | **纠偏替换原则取代**："用户改主意"是最高优先级删除信号；周期体检只看结构指标（长度/订阅率/路由偏离）且并入事件触发（consolidation 例行），不做日历式内容复审 | R7 §3.5 + R7 开放 5 |
| 2 | R4 §3.4 单节点 flow 合法 vs 用户"单 agent 完全可以用 skill 代替" | **不合法**。加载校验 reject 节点数 <2；需要权限/观测/入口稳定性 → agent + flows 白名单（机制已存在）。R4 决策树相应改写 | 用户二轮 + R8 §1.4 |
| 3 | R4 §5-P2 NodeTimeout 节点级可配 + checklist D1/D2（5min/30min 预算）vs v2.1 已删全部超时 | **R4 该节与 D1/D2 整体作废**（85053fa7 已合并）。删超时后的成本护栏 = R8 maxFanout=4 + agent 内部超时；D4 保留，C4 onError 保留 | 用户"超时已移除看最新实现" |
| 4 | R7 §2.1 "用户输入基座 = sessions 全量落盘" vs 用户"有专门的 jsonl" | **现状修正**：查询主通道是 `~/.nebflow/input_history.jsonl`（WebSocketRoutes.scala:623-648 写入：text≤2000 字符/ts/type∈input·paste·file，**无 sessionId**；NebulaBackup.scala:33 已纳入备份），sessions 降为上下文回溯。新增查询通道设计见 §5.3 | 用户三轮 ① |
| 5 | R2 §3.4-4 + P1-3 team 域 skill 目录（加载层级 global<team）vs R3 §3.2 只有全局层 vs 用户拍板 | **R2 P1-3 作废**。物理唯一全局层 + `audience` 命名空间 + 默认订阅（§1.4）。R2 §3.1 权限矩阵中"Skill（team 域）"改为"Skill（全局目录 + team 命名空间）" | 已定决策（消解 R3/R5 张力） |
| 6 | R3 §3.5 skill-proposals 消费闭环（Manager 采收）+ ExperienceExtractor 旁路 vs R6 §7-2 降级可选 vs R7 §4-5 移除 | **旁路移除（已定），proposals 闭环不复活**。skill 供给唯一通道 = R7 闭环：当事 agent 写草案（附用户原话）→ Manager 转报/初判路由 → Nebula 元数据终审（四权分置） | R7 §3.2（取代 R3 §3.5） |
| 7 | R5 §7-3 "硬线 20 可做成创建时 blocker"（暗示代码）vs 用户"成员上限不写代码" | **上限全部进 prompt 层**：entity-creator reviewer 清单（>10 警告须论证、>20 拒绝）+ R5 §4 设计 checklist；不进 TeamDef Decoder、不进任何代码校验 | 用户二轮 R5 拍板 |
| 8 | R4 checklist D3 "能用 extends 全局 agent 就不新写"（D3 与 §2.2 均用 extends 措辞）vs extends 机制已移除 | **表述性错误**：实际机制是 loadFlowAgent 同名回退全局 agent（EntityLoader.scala:168-173）。checklist 修订时改为"同名回退复用"，与 W0 的 extends 残留清理同口径 | R2 §2.1 代码事实 |

---

## 3. 波次总览

| Wave | 主题 | 主要来源 | 关键依赖 | 状态 |
|---|---|---|---|---|
| 0 | 清障 + 放权地基 | R1 全部、R2 P0、R3 P1 三修、R5/R6 P0 清障项 | 无 | **在途**（不重复排，只列出口条件） |
| 1 | 纠偏闭环点火 + skill 冷启动 | R7 P0/P1、R3 P0/剩余 P1、jsonl 通道（新） | W0 profile 修复；**entities git 化（§10-1，自 W4-5 前置）** | 未实施，**可与 W2 并行** |
| 2 | Flow 引擎升级（三阶段）+ R4 尺子修订 | R8 全部、R4 修订 | 独立（strict 迁移期用 strictVerdict 开关） | 未实施 |
| 3 | 创建放权 + entity-creator v2 + 三 checklist 入库 | R2 P1、R4/R5/R6 checklist | W0 FlowTrigger；**W2-P1 真实跑稳**（新 flow 直接按 strict 契约写；非仅测试全绿，§10-4） | 未实施 |
| 4 | 组织自优化 + 收敛体检（版本化已前置到 W1 前，§10-1） | R5 P1、R6 P1、R7 §3.4 | W1 有真实数据（**实测回顾通过**，§10-3）；W3（组织变更走 v2） | 未实施 |

---

## 4. Wave 0（在途）——清障与放权地基

已在途，不重复排任务，仅钉住**波次出口条件**（全部满足才开 W1/W3 的依赖项）：

1. FlowTrigger 上线：`Delegate(flow=)` 返回引导错误；未声明 flows 的 agent 调 FlowTrigger 被拒并列 allowed；slash `/flow` 触发首选工具即 FlowTrigger；全 prompt 无 `Delegate(flow=` 残留；SubTask worker 工具集不含 FlowTrigger（R1 §5 验收 1-8）。
2. extends 残留归零：`grep -r '"extends"' ~/.nebflow/flows/entity-creator ~/.nebflow/teams/flow-creator` 零命中（R2 §4-2）。
3. skill 三修：disable-model-invocation 的 skill 不再注入；catalog 条目含 when_to_use；buildSkillCatalog 死代码已删；system-prefix-for-all 文案与 opt-in 行为一致（R3 §2.5/§5）。
4. 跨 team 收紧：非 lead 的 team/agent 显式路由被拦或警告（R5 §7-1）。
5. qa-backend 描述换域完成，与 rules.md 无冲突（R5 §2.5）。
6. profile 错位修复：team 成员（非 lead）save turn 拿 Worker 焦点 reminder（含"seen 2+"沉淀文案与纠偏提示）——**这是 W1 捕获环节的硬前置**（R6 差距 1/2）。
7. 组织学协议 prompt 层：CC 纪律（关键事件 type=RESULT 抄送 Manager）、Team 间协作走 Nebula、rules（人类约束窗口）与 memory（事实记录）分工、四模式协作契约进各 rules.md（R5 P0/R6 P0）。
8. 已合并基座：v2.1 超时移除（85053fa7）、G3/G5。

---

## 5. Wave 1 —— 纠偏闭环点火（R7 主线 + R3 冷启动 + jsonl 通道）

### 5.1 目标

用户说过的每一次纠正都能被**当事 agent 识别、按四层路由沉淀、下 turn 自动触达执行者**；Nebula 获得 skill 终审能力与全库视野；skill 采用率从 0 起步。本波 90% 为 prompt/契约/文案改动，与 W2 零耦合。

### 5.2 任务清单

| # | 任务 | 来源 | 涉及文件 |
|---|---|---|---|
| 1 | 纠偏识别与上报契约：A/B 类（显式纠正/行为否决）当轮 Mail `[USER-RULING]` 附方案原文+用户原话；C/D 类（偏好陈述/澄清）save turn 攒批记 memory | R7 §3.1/§5-P0-1 | `~/.nebflow/prompts/system-prefix-for-teams.md` |
| 2 | Worker/Manager save reminder 文案：Worker 段补"用户原话级裁定先记 memory 再上报；同类 seen 2+ 提议 skill" | R7 §5-P0-2 + R6 §6-P0 | `CompactService.scala`（文案级） |
| 3 | Manager 裁定路由职责：四层路由判据（去领域词仍成立→User.md / 消费者≥2→rules.md / 可复用方法→skill / 域内细节→memory）+ 同类累计 ≥3 提组织变更 | R7 §3.2/§5-P0-3 | `~/.nebflow/prompts/manager-prefix.md` |
| 4 | Nebula skill 四权分置终审职责（定规范/审重叠/管登记/派订阅；元数据审+Evidence 抽查，不全文审）+ agent.json 声明全库 skill 索引 | R7 §3.2/§5-P0-4 + R3 §3.3-2 | `~/.nebflow/agents/Nebula/system.md`、`agent.json` |
| 5 | skill-creator 规范补齐：`audience` 字段指引、纠偏来源撰写标准（用户原话进 Evidence、做法进正文）、`last_verified`/`status`/`replaced_by` 元数据 | R7 §5-P0-5 + R3 §3.4-①/§5-P0 | `~/.nebflow/skills/skill-creator/SKILL.md` |
| 6 | rules.md 模板增"用户裁定"段（标注日期与来源，区别于人类约束窗口） | R7 §5-P1-8 | 各 team `rules.md` |
| 7 | Mail 标签约定 `RESULT + [USER-RULING]`（零机制改动，FlowMailStore 天然留痕供计数） | R7 §5-P1-6 | prompt 契约（同 #1） |
| 8 | skill-creator 固定注入（发现路径元入口）+ `skill audit` CLI（引用追踪：扫描 agent.json skills 字段产出"skill→订阅者"，报孤儿/超期） | R3 §3.3-1/§5-P1 | `SkillService.scala`、`SkillCommand.scala` |
| 9 | **用户原语查询通道（新增设计，见 §5.3）** | 用户三轮 ①② | `WebSocketRoutes.scala`、Nebula/manager prompt |
| 10 | 首批纠偏 skill 示范：把 nebflow-project 已有 memory 判例（毛玻璃面板等）按闭环走一遍 → 产出 `nebflow/visual-style` 命名空间 skill + 登记 + 订阅 | R7 §4-1/2（示范即验收） | `skills/nebflow/visual-style/`、相关 agent.json |

### 5.3 新增设计：input_history.jsonl 用户原语查询通道

**事实基座（修正 R7 §2.1）**：用户全部会话输入逐条落入 `~/.nebflow/input_history.jsonl`——每行 `{text(≤2000), ts, type}`，type ∈ input/paste/file，由 `logInputHistory`（WebSocketRoutes.scala:625-648）在网关层写入，quit/exit 与空输入过滤，已纳入 NebflowBackup。当前 8096 行。**缺口：无 sessionId/agent 字段**——能查到"用户说过什么"，查不到"对谁说的"。

**双通道设计**（用户原语："Nebula 传递时能传递用户原语，或者 agent 可以自己查询用户原语，很重要"）：

1. **传递通道**（零代码，已含在闭环）：`[USER-RULING]` Mail 强制附用户原话；skill 的 Evidence 段引用原话与 ts；Manager 转报 Nebula 时原话随行。纠偏类知识永远携带出处。
2. **查询通道**：
   - P0（零代码）：Nebula system.md 与 manager-prefix 写入检索指引——`Grep input_history.jsonl` 关键词得 text+ts；需上下文（当时谁在答、前后文）按 ts 回溯 `sessions/<id>.json`。消费场景：Nebula 审 skill 时 Evidence 抽查（原话是否支撑做法）、Manager 收到 [USER-RULING] 后核对、"我什么时候教过这个"审计回溯。
   - P1（小代码，一行级）：`logInputHistory` 增记 `sessionId`（与 agent 名）——调用处有会话上下文，旧条目无字段向后兼容。增强后可精确回答"这句话是对哪个 agent 说的"，R7 的审计回溯从时间对齐模糊匹配变为确定性查找。
3. **边界**：jsonl 只做回溯审计与终审抽查，**不做异步扫描沉淀**（M4 已否决——脱水文本识别质量差，与已移除的 ExperienceExtractor 同病）。

### 5.4 依赖

- **开工前置（波次启动前完成，v2 前置）**：agents/teams/skills 定义文件 entities git 化（原 W4-5，见 §10-1）——本波起全部改动均为 prompt/实体文件，先有回滚能力再动手（主仓代码改动本身已有 git）。
- 波内：#2 依赖 W0-6（profile 修复）；#4→#10（先有终审职责再做示范）；#5→#10。
- 跨波：无对 W2 的依赖（可并行）；W4-2（自优化计数）依赖本波 #7 的留痕数据。

### 5.5 验收锚点

1. nebflow-project/Frontend 会话制造纠偏（"以后弹窗都用毛玻璃"）→ Manager **当轮**收到 [USER-RULING]（flow-mailbox 留痕），下次 save turn 后 rules.md 或 memory 出现该条（含用户原话与日期）。
2. 方法类纠偏（"Release 要先跑冒烟再 tag"）→ **下一次真实发生时全链路可走通**：`nebflow/release` skill 出现（audience 正确）+ 相关 agent.json skills 更新 + 下一 turn catalog 含条目。**不设日历期限**（依赖用户行为的时间表是纸面美观——v2 改事件驱动）。
3. Nebula 会话问"我教过什么关于弹窗的规矩" → 回答引用 input_history.jsonl 检索结果（原话+日期）。
4. 防膨胀：连续三次同类裁定 → 断言升格合并（memory 条目合并/进 skill），而非三处重复各记一份。
5. `skill audit` 对零订阅 skill 正确报孤儿；Nebula catalog 含全库索引。
6. 实测回顾（§10-3）：完成后跟踪一周 [USER-RULING] 实际上报情况，与已知用户纠正交叉核对有无漏报；机制不成立先修机制，不堆下一层功能。

---

## 6. Wave 2 —— Flow 引擎升级（R8 三阶段 + R4 尺子修订）

### 6.1 目标

分支判断从"猜文本"变为"结构化契约"；并行扇出有 Barrier 等齐语义；存量 3 个病灶 flow 治愈；R4 尺子修订后成为引擎与创建流程共用的判断标准。

### 6.2 任务清单（阶段即波内依赖顺序）

| 阶段 | 任务 | 来源 | 涉及文件 |
|---|---|---|---|
| P1 结构化契约 | FlowNode 增 `outputs` 声明、Switch 增 `lenient`、flow 级 `strictVerdict`；FlowReport 参数扩 slots + call 内契约校验 ToolError 当轮自纠；三级文本猜测移入 lenient 分支（默认关）；per-node case keys+slots schema 注入工具描述 | R8 §3.2/3.3 | `EntityTypes.scala`、`FlowReportTool.scala`、`FlowDagExecutor.scala`、`AgentCore.scala` |
| P1 迁移 | 4 个健康 flow（code-review/entity-creator/git-merge/nebflow-review-merge）的节点 agent prompt 补 FlowReport 契约句 + 声明 outputs；过渡期 `strictVerdict=false` 兜底一个 release 周期 | R8 §3.5 | 4 个 flow 的 agents/*/system.md |
| P2 并行 | NodeRoute 增 `Parallel(fan)`（decoder 接受 `{"parallel":[...]}` 与字符串两种形态）；FlowExecContext Ref 化 + 分支 fiber + 计数屏障；扇出 `onFail: abort/collect`；per-branch cancel 穿透；`maxFanout=4`。**v2 首版最小化**：只做**静态 parallel 数组** + 计数屏障；**回边再装填与动态 fanout（$slots 展开）不入首版**——标注"验证需要后再做"，并入开放项 5 | R8 §2.1-2.3 | `EntityTypes.scala`、`FlowDagExecutor.scala`、`RunningFlowRegistry.scala` |
| P2 校验 | 加载校验：节点数 <2 reject（单节点非法）、纯环/无终止路径 reject、join 入度≥2 须有 parallel 上游、可达性、maxFanout 上限 | R8 §5 Phase 2 | `EntityLoader.scala` |
| P2 前端 | flowStarted edges 扩 parallel 边，DAG 渲染并行同层 | R8 §5 Phase 2 | 前端 DAG 视图 |
| P3 审计落地 | **research 重写（v2 首版瘦身）**：planner（slots 拆题）→ **r1/r2 两路并行调研** → verify（**2 入边 Barrier**，逐条抽查来源——Barrier 语义的最小验证载体）→ writer 汇总；**第三路调研与 revise 打回回边待首版真实跑稳后再扩**（开放项 5）；memory-consolidation 加出口 switch（clean→$return/found→consolidator，consolidator→$return）；release-stable 加出口 switch（ready→coder/blocked→$return）；academic-survey 升格为 flow，SKILL.md 缩为报告写作规范 | R8 §2.5/§4.1/§4.2 + v2 终审 | `flows/research/`（全量）、`flows/memory-consolidation/`、`flows/release-stable/`、`skills/academic-survey/` |
| P3 尺子修订 | R4 checklist 修订版定稿：删 D1/D2（超时作废，护栏=maxFanout+agent 内部超时）；§3.4 单节点段反转为"非法，用 skill"；D3 改"同名回退复用"；新增并行设计条目（fan 清单化/join 模板显式列全上游/onFail 显式选择/回边注明循环轮数与 maxLoop 对应）；A/B/C 类保留 | R4 §4 + 裁决 2/3/8 | checklist 文档（W3 入 entity-creator） |

### 6.3 跨波依赖

- 上游：无（独立于 W1）。
- 下游：**W3 的 entity-creator v2 直接按 strict 契约写节点 prompt**，避免二次迁移；W3 的加载校验复用 P2。

### 6.4 验收锚点（R8 §5 七条 + 尺子）

1. 冒烟：真实启动后 FlowTrigger(flow="research") 触发，WS flowStarted 收到 **4 节点 5 边** DAG（planner→r1/r2→verify→writer），r1/r2 两个 Running 时间戳重叠（并行证据）。
2. Barrier：verifier.startedAt ≥ max(r1,r2.completedAt)；总耗时 ≈ max(单路) 而非 sum（"阻塞等齐所有结果，不是一个一个发"）。
3. 打回回边（**二期扩项**，首版真实跑稳后再做，开放项 5）：扩成后 verdict=revise → 各路重跑、loopCounts 递增；连续 2 次 revise → `Max loop (2) exceeded`。
4. cancel 穿透：并行中途取消 → 两个 agent 均 5s 内 stopped，flow 终态 cancelled。
5. 结构化：非法 verdict → FlowReport ToolError 当轮自纠；strict 下拒 FlowReport 的 switch 节点 → Failed 且错误含期望 keys；无 lenient 时无 substring warn。
6. 校验：单节点 flow.json 与纯环 flow.json 加载即 reject；`sbt test` 全绿 + Parallel/Barrier/StrictVerdict 新 Spec。
7. 修订版 checklist 发布：无超时条款、单节点标非法、D3 用同名回退措辞（裁决 2/3/8 落实）。

---

## 7. Wave 3 —— 创建放权 + entity-creator v2（R2 + 三 checklist 入库）

### 7.1 目标

"谁能创建实体"从 prompt 自律升级为流程强制（权限编进 DAG 拓扑）；Manager 获得自建能力；flow-creator 遗留团队退役。

### 7.2 任务清单

| # | 任务 | 来源 | 涉及文件 |
|---|---|---|---|
| 1 | entity-creator v2 分支拓扑：architect（需求分析+复用判定+**callerScope 闸**+输出 entityType）→ `switch($architect.entityType)` → **首版只建 agent + team 两条支线**（最高频路径，4 个特化 agent，verdict 独立循环，maxLoop=3）；**flow/skill 支线与其余 agent 二期**——skill 自建本就有轻量通道（成员自服务+四权分置，W1 闭环），不急于入 DAG；flow 创建需求先经 Nebula 直建 | R2 §3.3-B + v2 终审 | `flows/entity-creator/flow.json` + 4 个 agents/（二期扩至 8） |
| 2 | callerScope 闸：caller=Manager → 产出强制 `teams/<caller-team>/` 域 + 拒绝 Team/全局实体请求（回复"经 Nebula"）；caller=Nebula → 全域；**team 支线仅 caller=Nebula 可达**（权限=图拓扑） | R2 §3.3-v2-1 | architect system.md + flow.json 拓扑 |
| 3 | 红线检查：tools/mcpServers/model/preset/lead 变更一律 fail；agent-builder 的 tools 面不超 caller 自身 | R2 §3.3-v2-2 | 2 个 reviewer system.md（首版；二期扩至 4） |
| 4 | 放权接线：nebflow-project Manager `flows: ["entity-creator"]`（试点）；普通成员仍走提名→Manager | R2 §4-P1-2 + R1 开放 2 | Manager agent.json |
| 5 | 三 checklist 入库：R4 修订版（W2-P3 产出）A/B 类进 architect、C/D+并行条目进 reviewer；R5 checklist（职责拆分/协作设计/边界维护、换域校验、成员数 >10 警告>20 拒——**仅 prompt 层**，裁决 7）；R6 checklist（建 agent 时/运行中/体检三段） | R2 §3.3 + R5 §4 + R6 §4 | architect/reviewer system.md |
| 6 | audience 自动订阅：新建 team agent 时按命名空间默认填 skills 字段；换域校验同时查订阅漂移 | R7 §5-P1-7 + 已定决策 | agent-builder/reviewer system.md（首版；skill 支线相关二期随支线扩） |
| 7 | team.json `flows` 字段决断：不接活，字段删除——成员级 flows 声明唯一（两字段并存必然漂移） | R1 开放 4（倾向） | nebflow-project/team.json |
| 8 | flow-creator team 退役归档（描述已自述被替代）；EntityLoader 死代码 writeTeam/writeFlow/writeAgent 标 @deprecated | R2 §2.2 + R5 §3.6 | `~/.nebflow/teams/flow-creator/`、`EntityLoader.scala` |

### 7.3 依赖

- 上游：W0-1（FlowTrigger 白名单机制 = 放权通道）；**W2-P1 真实跑稳**（strict 契约，v2 节点 prompt 直接按新规范写——非仅 Spec 全绿，见 §10-4）。
- 下游：W4-2（组织变更走 v2 执行）。

### 7.4 验收锚点

1. E2E callerScope：Manager 收"给团队加一个 release-notes 写手"→ 触发 entity-creator → 新 agent 落 `teams/nebflow-project/agents/`（非全局）、team.json members 追加、DAG 面板可见且走 agent 支线。
2. E2E 权限闸：同一 Manager 请求"新建一个 Team"→ team 支线不可达/拒绝，回复含"经 Nebula"指引；`~/.nebflow/teams/` 无新目录。
3. 红线：请求"给现有 agent 加 mcpServers"→ reviewer verdict=fail 并指出路径。
4. 订阅：新建 team agent 的 skills 字段自动含本 team 命名空间条目；下一 turn catalog 可见。
5. 回归：Nebula 直接触发建全局 agent 老路径行为不变；v2 全链路在 strictVerdict=true 下跑通。
6. `grep '"extends"'` 持续零命中；flow-creator 目录归档后 catalog/TeamCatalog 无幽灵条目。
7. 首版边界：Manager 请求建 skill/flow → architect 给出正确指引（skill 走"成员自服务+四权分置"轻量通道，flow 走 Nebula 直建或待二期），不误入不存在支线。

---

## 8. Wave 4 —— 组织自优化与收敛体检（R5/R6/R7 后半）

### 8.1 目标

闭环数据开始驱动组织演化（Team 变得更专用）；体检例行覆盖结构指标，**红线项改人工判断**（v2 软化，§8.2-4）；entities 版本化已前置到 W1 开工前（§10-1），本波仅保留 memory.md 纳入决断与例行快照。

### 8.2 任务清单

| # | 任务 | 来源 | 涉及文件 |
|---|---|---|---|
| 1 | F3 周报聚合（**v2 首版降级到 prompt 层**）：Manager save turn reminder 加一行"自查 flow-mailbox 的 [USER-RULING] 计数"（零代码，与 W1-2 同为文案级改动）；**ManagerProgressHook 代码化**（从本 team flow-mailbox 聚合周期 digest——直连协作统计+异常事件+计数——写 Manager memory）**待 prompt 层验证有效后再做** | R5 §3.1/§6-P1 + v2 终审 | `CompactService.scala`（文案级，首版）→ `ManagerProgressHook.scala`（二期） |
| 2 | Team 自优化例行：Manager 三处计数（[USER-RULING] 上报/rules 裁定段变更频率/成员 memory 纠偏密度），同类 ≥3 → 向 Nebula 提组织变更提案 → 走 entity-creator v2 执行；审批分层（skill 订阅与 rules 修订 Manager+Nebula 两级；成员增删加用户确认） | R7 §3.4 | manager-prefix、rules.md |
| 3 | 周期体检例行化（**事件触发并入 consolidation 例行，非日历**——体检看结构指标，内容过时归纠偏驱动，裁决 1）：prompt-engineer 转型体检医生——system.md 长度分布/useWhen 与 FlowMailStore 实际路由偏离/skills 空置率/跨团队复制残留/零引用 skill（仅列出，裁决按 #4 人裁） | R6 §3.3/§7-1 + R7 开放 5 | prompt-engineer system.md |
| 4 | **三条红线软化（v2：人工判断取代定量触发——不设硬数字、不强制、不自动退役）**：① memory 行数不作触发——整理已由 compaction 周期 RECORD→ORGANIZE→VERIFY→CLEAR 承载，体检时行数仅作参考信号（明显膨胀再提示），不触发动作、不设阈值 ② system.md 超长不强制拆层——体检医生可给拆层建议（超长时列出收益与代价），拆不拆由 Owner/Manager 裁量，连贯性优先于长度指标 ③ 零引用（孤儿）skill 在体检报告**列出即止**，退役决定权在 Nebula/Manager（新建冷启动期天然豁免），不自动退役、不设周期数 | R6 §3.4 + R3 §3.4-④ + v2 终审 | 体检流程 + skill-audit |
| 5 | entities 版本化**前置到 W1 开工前**（§10-1，v2 保质保量纪律——W1 起全是 prompt/实体文件改动，先有回滚能力再动手）；本波仅保留 memory.md 纳入决断（征询用户后定，开放项 4）与例行 backups/ 快照（§10-5 惯例） | R6 §5-P1-④ + R3 回滚 + v2 前置 | （已前置）`~/.nebflow/` 仓库初始化 |
| 6 | ExperienceExtractor 移除执行（两体检周期零产出确认后）；WorkerSkillHook 链路随之简化 | R6 §7-2 + R7 §4-5 | `ExperienceExtractor.scala` 等 |
| 7 | 轻量审计：entity-creator builder 完成后 [INFO] 通报 Nebula；巡检 flow 周期比对磁盘实体 vs 登记，未登记实体汇总报 Nebula | R2 开放 2（倾向） | 新巡检 flow（可挂 Schedule） |
| 8 | （可选 P2）T2 SystemReminder 知识更新提醒：skill/memory 落盘后向运行中会话注入提醒（gitBranch reminder 模板复用）——仅当 T1 延迟被实证为问题 | R7 §3.3-T2/开放 4 | `ContextRefresher.scala` |

### 8.3 依赖

- 上游：W1 有真实数据（**实测回顾通过**，§10-3——要有 [USER-RULING]/flow-mailbox 数据可计数）；W3（组织变更走 entity-creator v2）。
- 无下游（终波）。

### 8.4 验收锚点

1. Manager save turn 自查记录入 memory（含 [USER-RULING] 计数，prompt 层首版）；F4 全量镜像确未发生；ManagerProgressHook 结构化 digest（非全文抄送）待 prompt 层验证有效后补充验收。
2. 人为注入漂移样本（复制一个跨团队 agent 不改描述）→ 下一体检周期被抓出。
3. 一次真实组织变更全链路：同类裁定累计 ≥3 → Manager 提案 → Nebula 审 → entity-creator v2 执行 → 新成员落 team 域且自动订阅命名空间 skill。
4. 孤儿 skill 出现在体检报告**且有人裁决**——Nebula/Manager 明确决策（保留 / 修订 / 退役；若退役则 archives + 订阅者收到 replaced_by 迁移提示）。
5. 改动前 backups/ 快照惯例落地（抽查一个快照时点可恢复；git 化验收随 W1 前置完成，§10-1）；memory.md 纳入与否有用户明确答复。

---

## 9. 用户原语溯源表（关键决策 → 原话）

| 决策/机制 | 用户原语（简写） |
|---|---|
| Delegate/FlowTrigger 拆分 + flows 白名单驱动 | "Delegate 拆分，触发 flow 和 agent 分开两个工具，Delegate 只触发 agent" |
| Team 自建成员/flow/skill，Nebula 垄断 Team+实体创建 | "实体创建 Nebula 垄断、Team 创建由 Nebula 垄断，但 flow/skill/Team 自己的成员可由 Team 自建" |
| entity-creator v2 分支架构（W3） | "建议拆分，或者改为分支的架构——创建 flow/team 等各有侧重和区别" |
| skill=教做一件事 / flow=交给固定流程（§1.5 判定） | "skill 本质是指导怎么做一件事；flow 是把一件事交给固定流程" |
| skill 一等供给源 + Nebula 四权分置（W1） | "skill 重要来源是用户的纠正，Nebula 有很强职责编写 skill 应用到具体 agent" |
| 闭环的两难段：写入端+触达端（W1 全波） | "用户教过一次纠正过一次要能记住，特别是怎么传递到实际使用 skill 的 agent，因为 Nebula 看不到这些 skill" |
| jsonl 查询通道（§5.3，新增） | "用户输入记录不是 session 落盘——有专门的 jsonl"+"Nebula 传递时能传递用户原语，或者 agent 可以自己查询用户原语，很重要" |
| taste 三层配方 T3（W1 传递环节） | "设计哲学/taste 要学会沉淀和传递，一定要落实到具体执行的 agent" |
| Barrier 等齐语义（W2-P2） | "多对少节点必须阻塞等齐所有结果，不是一个一个发" |
| strict verdict + slots（W2-P1） | "靠 agent 输出文本判断肯定不对" |
| 单节点 flow 非法（裁决 2） | "单 agent flow 的判断是错的——一个 agent 完全可以用 skill 代替" |
| 超时条款作废（裁决 3） | "超时已移除，看最新实现" |
| research flow 重写样板（W2-P3） | "多 agent 并行调研→初步报告→校验→不通过打回（循环）→通过交文档编写汇总" |
| team agent 按作用域专门设计（=移除 extends 的目的） | "每个 team agent 根据 team 作用域专门设计；nebflow 和 slideblocks 的 frontend 不一样，backend 是 Scala 有自己约束" |
| Team 随纠偏自优化（W4-2） | "Team 随项目开发用户纠偏优化自己，变得更加专用" |
| 联邦制：入口集中/过程自治（W0-7 契约） | "Manager 合理触发+任务拆分，队员涌现合作——前端后端自己沟通，任务完成给 qa" |
| 上行单通道保持（不破） | "成员不能直 Mail Nebula 不能跨 team Mail（Manager 全局记忆）" |
| CC 只要摘要不要细节 | "Manager 不需要细节，只要总体进展" |
| rules/memory 职责分工 | "rules 是人类约束窗口，memory 记事实：做了什么到哪了关键决策" |
| Team 间协作走 Nebula | "Team 间合作可能通过 Nebula 好点" |
| 最小心智原则 | "分队员是为了各自维护最小心智——提示词工程师只关心提示词；前端有审美提示词+理解用户审美" |
| 成员上限不写代码（裁决 7） | "成员上限不写代码" |
| skill 命名空间（裁决 5，已定） | "skill 目录只有全局层，team 专用用命名空间" |
| 防过时=纠偏驱动（裁决 1） | "skill 修正基准是用户纠偏，用户输入是宝贵资源，代码层已有记录基座但没利用" |
| 三条定量红线软化为人裁（§8.2-4，v2） | "三条定量红线太过严格且没必要" |
| 理想化项降级为渐进 MVP（W2-P2/P3 瘦身、W3 减半、W4-1 降级、W1 锚点 2 事件驱动，v2） | "部分内容方案漂亮但工程上过于理想化" |
| entities 版本化前置 + 两段式节奏/实测回顾/交付清单（§10，v2） | "大工程实施要保质保量" |

---

## 10. 实施纪律（v2 新增：保质保量）

> 依据：用户终审反馈"大工程实施要保质保量"。以下纪律约束 W1 起全部波次；**波次出口条件 = 真实使用验证通过，不是纸面验收全绿**。

1. **版本化前置（零成本高收益）**：agents/teams/skills 定义文件 entities git 化（原 W4-5）提前到 **W1 开工前**——W1 起全是 prompt/实体文件改动，先有回滚能力再动手（主仓代码改动本身已有 git）。memory.md 纳入仍征询用户后定（开放项 4）。
2. **两段式节奏**：每波先做 MVP（最小可验证版本）→ 真实使用跑稳 → 再扩展。本次 v2 的 W2-P3 瘦身、W2-P2 最小化、W3 减半、W4-1 降级均循此则；扩项统一停入开放项，标注"验证需要后再做"。
3. **实测回顾点**：每波完成后跑一次真实数据检验——
   - W1：跟踪一周 [USER-RULING] 实际上报，与已知用户纠正交叉核对有无漏报；
   - W2：跑一次真实 nebflow-review-merge 全链（非仅 Spec 全绿）；
   - W3：nebflow-project Manager 经 v2 真实创建一名团队成员走全链；
   - W4：一次真实组织变更全链（从计数阈值到 v2 执行）。
   **机制不成立先修机制，不堆下一层功能。**
4. **并行纪律**：W1（prompt 层）∥ W2（代码层）并行保留（零文件冲突）；**W3 必须等 W2-P1 真实跑稳**；**W4 必须等 W1 有数据**（实测回顾通过）。
5. **交付清单**：波次内每个任务交付时附"改动清单+回滚方式"；改动前快照 backups/ 惯例沿用（实体文件改动前先快照）。

---

## 11. 风险与开放项

**风险与对策**

| 风险 | 对策 |
|---|---|
| W1 [USER-RULING] 上报靠 prompt 纪律，可能漏报 | flow-mailbox 全量留痕可事后审计；体检周期统计"memory 中纠偏密度 vs 上报数"偏离；A/B 强信号当轮上报（非攒批）降低丢失窗口 |
| W2 strict 迁移期旧 flow 失败 | `strictVerdict=false` 过渡开关保一个 release 周期；4 个健康 flow 的 prompt 迁移是每个 agent 加 2 行的量级 |
| W3 callerScope 依赖 caller 身份强推断 | P1 仅放权 Manager（lead）；普通成员触发等自建流程跑稳再议（R2 开放 3） |
| jsonl 无 sessionId（现状） | P1 一行增强补记；未增强前用 ts 时间对齐回溯 sessions（模糊但可用） |
| 并行 fiber 复杂度（屏障/回边再装填） | v2 首版只做静态 parallel 数组 + 计数屏障，**回边再装填与动态 fanout 移入开放项 5**（"验证需要后再做"）；复用 runNode 全部正确逻辑（cancel/onError/route）；新增 Parallel/Barrier/StrictVerdict 三个 Spec 锚定 |

**开放项（沿用各报告倾向，不再新开讨论）**

1. T2 即时提醒——P2，先实测 T1 在 Mail 短会话下的真实延迟（W4-8）。
2. grill / thesis-review 两个边界 skill——观察，若加"评审-打回"循环则升格 flow（R8 §4.2）。
3. 显式 edges 数组格式——出现两个以上独立汇聚点的真实需求再议（R8 §6）。
4. memory.md 是否进 git——征询用户后定（定义文件已随 §10-1 前置 git 化；memory.md 暂不纳入）。
5. research 扩项（第三路调研 + revise 打回回边）与并行回边再装填、动态 fanout（$slots 展开）——**首版真实跑稳后再做**（v2 去理想化："验证需要后再做"；见 W2-P2/P3 首版范围与 §10-2 两段式节奏）。

---

*方案文件：~/.nebflow/docs/Nebflow/ecosystem-master-plan.md · 架构图：assets/ecosystem-master-arch.svg（graphviz 源 assets/ecosystem-arch.dot）*
