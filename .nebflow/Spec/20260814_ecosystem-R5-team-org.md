# R5 — Team 组织学：定位、边界与涌现协作

> 四实体生态设计讨论报告 R5 · 2026-08-14 · 基线：/tmp/entity-ecosystem-design.md（本文深化其 D5/D6，从组织学视角展开）
> 事实来源：源码 archive/scala 分支 + ~/.nebflow 实测数据（flow-mailbox 全量留痕、7 个 team 的配置与 memory）

---

## 1. 用户观点解读

用户三点反馈合并后的完整主张，可以拆成四个组织学命题：

**命题 A —— 最小心智原则（为什么分这么多队员）**。分队员不是分工秀，而是让每个成员"只关注自己的内容，把自己的内容做好"。提示词工程师的系统 prompt 和工具就只关心如何写好提示词；前端 agent 有自己的审美提示词，但更重要的是"理解用户的审美"。**成员的价值 = 侧重 × 边界**：有侧重而无边界（什么都顺手做）和有边界而无侧重（什么都浅尝）都是坏成员。

**命题 B —— Manager 是合理的触发点，不是全路径中转站**。Manager 收任务、做拆分——这像正常的项目经理：分配好各自任务之后，队员之间涌现合作（前端和后端自己沟通对齐），任务完成后自己交给 QA。即：**入口集中，过程自治，结果汇聚**。这与基线方案 D5（Manager 从调度中转站转为契约守卫+能力园丁+兜底仲裁者）方向一致，本文回答的是其中缺的组织学细节：怎样的拆分和怎样的合作是"好的"。

**命题 C —— 边界与规模**。Team 的作用单位可以是**一个项目**（nebflow-project、czt-project），也可以是**某一类事物**（如 flow-creator 管实体创建）。成员规模一般 5-10，最大 20，"多了效率低"。

**命题 D —— 用户认可现行 Mail 上行限制的设计动机**：成员不能直 Mail Nebula、不能跨 team Mail，是为了"让 Manager 拥有项目的完整的全局的记忆"。注意：用户认可的是**上行单通道**，并没有认可"成员之间也要经 Manager"——恰恰相反，命题 B 明确要求成员间自由互连。这两件事经常被混为一谈，本文 §3.1 将把它们拆开。

---

## 2. 现状核实

### 2.1 Mail 路由边界：三条硬约束 + 一个半透膜

| 约束 | 实现 | 事实依据 |
|------|------|---------|
| 成员 → Nebula：**不通** | `canMailNebula` 仅放行 managerMap 注册的 sid 或任意 team 的 lead 名；其余返回 "Cannot mail Nebula directly. You are a team worker. Report to your Manager via Mail." | MailTool.scala:678-683, 510-517, 649-659, 786-787 |
| 成员 → 跨 team 短名：**拦** | `checkTeamScope`：地址是其它 team 名 → "Cannot mail outside your team. Use your Manager to escalate to Nebula." | MailTool.scala:770-789 |
| 成员 → 本 team 短名：**通**（传输层已就绪） | 同 team 优先解析，immediate/queue/ask 三模式 × 5 种 TYPE 标签 | FlowTreeActor.scala:137-168；MailTool.scala:17-30, 637-669 |
| **半透膜后门**：显式 `team/agent` 格式跨 team：**通** | `checkTeamScope` 对含 `/` 的地址做 `loadTeam(address)` 返回 None → 落入 `case None => None` 放行；`resolveSessionId` 支持 `team/agent` 精确查找（目标 team 已挂载时）。MailTool 的工具描述也明文允许 "explicit scoped route from anywhere" | MailTool.scala:777-789（None 分支）、54-55；FlowTreeActor.scala:123-136 |

另有边缘事实：**standalone agent（Coder/Explorer 等）也不能 Mail Nebula**（`canMailNebula` 对非 lead 一律 false），它们只能靠 Delegate/SubTask 的完成回调汇报——上行单通道比用户认知的还要严。

> 结论：用户认知"不能跨 team Mail"与实现存在出入——短名被拦、显式 `team/agent` 可通。这是设计意图（文档化了）还是疏漏（checkTeamScope 忘了处理含斜杠地址），代码注释未说明，列入 §7 开放问题。

### 2.2 涌现协作实测：传输层通，行为层零发生

对 `~/.nebflow/sessions/*/flow-mailbox/` 全量留痕（FlowMailStore，每封 team 内 Mail 自动记录，MailTool.scala:968-1002）做通信对统计：

| 团队 | 邮件数 | 通信对分布 |
|------|--------|-----------|
| slideblocks | 200 | Nebula→Manager 57、Manager↔Frontend 88、Backend/Docs 全经 Manager |
| voice-recognition-test | 47 | Nebula→Manager 18、Manager↔Frontend 27 |
| nebflow-website | 46 | Nebula→Manager 18、Manager↔Frontend 24 |
| nebflow-rust | 31 | Manager↔Backend 19、Nebula→Manager 8、Nebula→Backend 4（历史跨线，b9d427c6 修复前残留，User.md 有记录） |
| nebflow-project（3 个实例合计） | 17 | 全部 Nebula↔Manager、Manager↔Backend/Frontend |
| czt-project | 4 | Nebula→Manager |

**345 封 team 通信中，成员↔成员直连 = 0 封。** 对照组：73 封 flow 内部邮件中存在真实的节点直连（code-review 的 scanner→reviewer→fixer，memory-consolidation 的 scanner→consolidator）——**只要 DAG 明确给了直连指令，agent 就会直连；自由环境下则一律回退到找 Manager**。这证明缺的不是传输层，而是协议（成员不知道何时该直连、直连时怎么带上下文）与激励（prompt 把回报对象写死为 Manager：system-prefix-for-teams.md:84-95 "Team Member → address=\"Manager\""；nebflow-project 的 Manager system.md:36 自任 "You are the junction"）。

### 2.3 Manager 全局记忆的现有保全机制

Manager 在不中转的情况下能"免费"获得的团队视野，现有四条：

1. **FlowMailStore 全量留痕**：成员间直连 Mail 也会记录（`onMailDelivered` 不区分收发方是否 Manager），落到挂载 session 的 `flow-mailbox/<team>.json`——但它是**审计数据，不是 Manager 上下文**：Manager 的 system prompt 并不注入这些记录。
2. **ManagerProgressHook**（ManagerProgressHook.scala:15-46）：compaction 前把进度摘要**启发式（无 LLM）**追加进 Manager memory.md——实测各 team Manager memory 均有"当前任务状态"段，即由此产生。
3. **TeamCatalog 每 turn 注入**（TeamCatalog.scala:11-34）：Manager 能看到全员 description+useWhen——知道"谁会什么"，不知道"谁在干什么"。
4. **rules.md 每 turn 注入**：静态契约，不含动态状态。

即：**"全局记忆"今天 = 静态能力视图 + 自己经手的邮件 + 压缩前快照**。成员直连一旦放开，Manager 对过程的确即时可见性为零——这是用户"完整的全局的记忆"关切的真实缺口。

### 2.4 团队规模与形态统计

7 个 team，成员数 3/4/4/4/4/6/8，平均 4.7，最大 8（nebflow-project：Manager+7 成员）。全部低于用户给的 5-10 常规带。team.json 的 members 字段无任何数量校验（EntityTypes.scala:83-94，Decoder 只管存在性）。形态上：6 个项目型（nebflow-project / nebflow-rust / czt-project / slideblocks / nebflow-website / voice-recognition-test），1 个事物型（flow-creator，功能域服务团队），两类组织的差异目前只体现在 description 措辞上，没有结构差别。

### 2.5 职责拆分现状：好样本与坏样本

- **好样本（最小心智落地）**：nebflow-project Backend system.md 仅 38 行/130 词，职责一句话；prompt-engineer 工具白名单无 Bash/WebSearch（不能跑命令不能搜网，只能读写编辑提示词文件）；Manager 无 Write/Edit（不能写代码）；qa-backend 无 Edit（只审不改，"你只审核不修改"）。工具白名单即职责边界的机械化表达。
- **坏样本（心智串域/漂移）**：nebflow-project 的 qa-backend 描述与 system.md 仍是"Nebula **Rust 迁移**代码质量审核员、cargo build/clippy/test、工作目录 /tmp/nb-rust-migration"——从 nebflow-rust 复制后未改，与本 team rules.md"nebflow-project 负责 Scala 版开发、两团队不要耦合"直接冲突。Frontend system.md 在 4 个团队间近乎相同（1163/1165 词），跨团队复制模板导致"理解本项目用户审美"的部分为零。**拆分做对了，维护没跟上**。

---

## 3. 设计分析

### 3.1 信息流模型：从星型到联邦制

![信息流模型对比](/tmp/r5-infoflow.svg)

用户模型翻译成结构语言是**联邦制**，与现行星型的差别只在"过程层"：

| 层 | 星型（现状） | 联邦制（目标） | 用户原话对应 |
|----|------------|--------------|-------------|
| 入口层 | Nebula→team 名→Manager | 不变 | "Manager 是合理的触发，它收到任务进行拆分" |
| 过程层 | 一切经 Manager 中转 | 成员网状直连（结对/交接/审查/打回） | "前端和后端自己沟通，任务完成后给 qa" |
| 上行层 | 只有 Manager 能到 Nebula | **不变**（这是命题 D，用户明确认可） | "让 Manager 拥有完整的全局的记忆" |
| 审计层 | FlowMailStore 被动留痕 | 升级为 Manager 全局视野的主动来源 | — |

**关键设计问题：Manager 不中转后，全局记忆从哪来？** 候选方案与权衡：

| 方案 | 机制 | 优点 | 缺点 |
|------|------|------|------|
| F1 留痕注入 | Manager prompt 定期（或按需）Read 本 team 的 flow-mailbox/<team>.json | 零机制改动；数据已全量存在 | 邮件原文噪声大，token 成本随协作量线性涨 |
| F2 CC 机制 | 契约规定关键事件（PASS/FAIL/升级/接口变更）的 Mail 必须 CC Manager（type=RESULT 已有标签可复用） | Manager 只收摘要级信号，成本可控；语义清晰 | 依赖纪律，成员可能漏 CC |
| F3 周报聚合 | 扩展 ManagerProgressHook（现为启发式追加）为定期 digest：从 flow-mailbox 聚合"本周直连协作统计+异常事件"写进 Manager memory | 全局视野结构化、自动、可持续 | 需要小量代码；粒度取决于聚合质量 |
| F4 全量实时 | 成员间 Mail 同时镜像投递 Manager | 视野最全 | Manager 退化为抄送垃圾桶，上下文污染，违背放权初衷 |

**倾向：F2 为主 + F3 为底 + F1 为审计兜底**。F2 解决"过程关键节点可见"，F3 解决"周期性全局视野"，F1 只在事后追责/复盘时读。F4 应明确禁止——否则 Manager 会重新成为信息汇聚点，心智负担比中转还重。

### 3.2 最小心智原则的操作化

"最小心智"要从口号变成四条可检查的规范（每条都有现状锚点）：

1. **system prompt 只写本域**：职责、领域约定、本域质量标准；不写其它成员怎么用（那是 TeamCatalog 的事）、不写项目全史（那是 rules.md/memory 的事）。锚点：Backend 38 行是好样本；Explorer（全局，414 行）是长 prompt 的合理上限参照。建议软预算 **≤150 行或 ≤900 词**，超了先问"这段是否属于 rules/skill/memory"。
2. **工具白名单 = 职责边界**：每剔除一个工具就少一类越界行为。拆分新成员时，从"它绝不该做的事"反推工具集（qa 不给 Edit、prompt-engineer 不给 Bash 就是范式）。
3. **上下文不串域**：成员间只见 TeamCatalog 的 description+useWhen（现状即如此，TeamCatalog.scala:11-34），不注入彼此 system.md 全文；跨 team 更不可见（catalog 只到本 team）。要跨界知识时走 skill 全局层，不走 prompt 复制。
4. **记忆同域**：memory.md 只存本域事实；发现自己记了别人的域（Backend 记了前端 CSS 坑）→ 迁给对应成员或升格为 skill。

反面对策：**跨 team 复制即漂移**。qa-backend 事件（§2.5）的根因是"复制整个 agent 目录"这个创建动作没有任何"换域校验"。对策进 entity-creator reviewer 清单：新建/复制 agent 时必须重写 description/useWhen 并检查其与目标 team rules.md 无冲突（基线方案 §3.2 第 3 条的落地细化）。

### 3.3 职责拆分标准：按什么拆，何时拆，何时并

三条候选轴，各自适用场景（对照现有 7 个 team 的实证）：

| 轴 | 含义 | 适用 | 实证 |
|----|------|------|------|
| **按学科**（discipline） | 同一技能域服务不同项目 | 事物型 team | flow-creator（architect/builder/reviewer 全是"实体工程"学科） |
| **按产物**（artifact） | 产出物的天然边界 | 项目型 team | nebflow-project（Backend/Frontend/Docs/qa-* 按产物切） |
| **按生命周期**（lifecycle） | 产出→验证→发布的阶段切分 | 质量关键流程 | Build vs Verify 分离：qa-frontend/qa-backend 独立于产出者 |

**判据（何时拆新成员）**——四个信号任一出现：
1. 单一职责超载：某成员 system.md 被迫持续膨胀（软预算 150 行挡不住）；
2. 工具白名单冲突：一个成员同时需要"写代码的 Bash"和"不该写代码的审慎"；
3. 路由信号重叠：两个任务的 useWhen 判定边界开始模糊（Manager 出现"塞给谁都勉强"）；
4. 频次法则：同类缺口被提名 ≥2 次（基线方案 §5.2 已定义）。

**判据（何时合并）**：成员月度被派发 <2 次（闲人）；两名成员的 Mail 往来中 ≥70% 是彼此交接（说明边界切错了位置）；或 catalog 中两条 description 语义重叠导致路由犹豫（qa-backend 事件即反向警钟——重叠到连描述都复制了）。

**拆分的守恒律**：成员数 × 平均心智 ≈ 团队总心智。拆成员不减少总复杂度，只是把它从"一人扛全部"变成"多人各扛一份 + 路由开销"。所以拆分必须换来真实的**专注收益**（用户命题 A），否则宁可用 skill/rules 加深现有成员。

### 3.4 涌现协作模式库（结合 nebflow-project 实际运作）

四个基础模式，全部映射到现有 Mail 语义（TYPE + delivery），不需要新机制：

| 模式 | 形态 | Mail 语义 | nebflow-project 实例 |
|------|------|----------|---------------------|
| **结对**（pairing） | 双向高频对齐，无依赖传递 | immediate + INFO/ask | Frontend memory 记录"等后端字段上线（前端兼容层已就绪）"——这就是一个本应发生的结对：注事件 delivery 字段、/api/provider/models 端点、{id,contextLength} 结构。现状它只能写在自己 memory 里等 Manager 转达 |
| **交接**（handoff） | 单向交付+验收责任转移 | queue + RESULT（带验收条件） | Backend 完成后交 qa-backend——基线方案 §6.4 已给完整时序 |
| **审查**（review） | 独立方按清单检查 | queue + RESULT（PASS/FAIL+证据） | code-review flow 的 reviewer→fixer 已是此模式，team 内缺的是复制它的契约 |
| **打回**（bounce） | 审查失败回退到产出者 | immediate + RESULT（FAIL+逐条） | 需配防失控：RETRY≥2 升级（基线 §6.2 已定义） |

**涌现的三个先决条件**（为什么 flow 里直连发生了而 team 里没有）：① 明确的触发规则（flow 有 DAG，team 需要 DoD 契约："过 QA 才算完成"）；② 对象可知（TeamCatalog 已满足）；③ 归责清晰（结果向谁报——信封 SENDER 解决，基线 §6.3）。因此本报告不另造协议，确认基线方案的 Task Envelope + Collaboration Contract 正是命题 B 的机制化，**P0 落地优先级应最高**。

### 3.5 成员上限 5-10/max20 的落地

上限的机制依据（不是拍脑袋）：
- **TeamCatalog token 成本**：每成员 description+useWhen ≈ 60-90 token，每 turn 注入给全员。10 成员 ≈ 700-900 token/turn，20 成员逼近 2000——上限 20 正好是"catalog 开始显著挤占工作上下文"的量级；
- **Manager 路由准确率**：候选越多，选择越易错（现 7 团队实测最大 8 人，已有 qa-backend 这种复制漂移导致的路由噪声）；
- **凝聚上限**：用户直觉"多了效率低"对应的是通信复杂度 O(n²)——成员直连放开后 n=20 意味着 190 条潜在协作边，契约再好也守不住。

落地三件套：
1. **创建时校验（P1，机制）**：entity-creator reviewer 清单加一条——members >10 警告（要求给出拆分论证）、>20 拒绝（Nebula 特批除外）。不建议硬编码进 team.json Decoder（机制层管格式，策略层管数量）。
2. **超限治理路径（P0，文档）**：Manager 发现超限时按 §3.3 的轴提出拆分方案（项目型超限 → 按子域拆子 team；事物型超限 → 按学科细分），报 Nebula 执行。
3. **绿色区间（P0，文档）**：新 team 从 3-5 人起步（现平均 4.7 即健康区间），随任务密度增长，不预设满编。

### 3.6 项目型 vs 事物型：同构文件，异构组织

两类 team 用同一套文件结构（team.json+rules.md+agents/），但组织学差异应该写进各自 rules.md 模板：

| 维度 | 项目型（nebflow-project, czt-project…） | 事物型（flow-creator） |
|------|----------------------------------------|----------------------|
| 存在理由 | 一个长期对象（代码库/论文/产品） | 一类可复用职能（实体创建、代码评审） |
| rules.md 重心 | 项目状态（分支/worktree 协议/版本规则）+ 团队路由 | 服务契约（输入格式/产出标准/评审循环） |
| 成员谱系 | 按产物+生命周期（Backend/Frontend/QA） | 按学科工序（architect/builder/reviewer） |
| 记忆重心 | 项目事实（分支、待办、发布状态） | 方法演进（判定表、红线清单的版本化） |
| 生命周期 | 与项目共存亡，需定期清理项目状态记忆 | 长青，随方法成熟逐步固化甚至转 flow |
| 演化方向 | 项目沉淀为 flow（如 nebflow-review-merge） | 团队本身沉淀为 flow（entity-creator 已是雏形：flow-creator team 与 entity-creator flow 并存，说明边界在迁移中） |

值得注意：**事物型 team 的终局常常是 flow**（entity-creator flow 正在替代 flow-creator team——team 描述里已写"已被 entity-creator Flow 替代"）。这是四实体生态的自然收敛：过程稳定后组织退化为管线。设计上应鼓励而非阻止这种迁移（team → flow 的判定可并入基线方案 E3）。

---

## 4. Team 设计 Checklist

**职责拆分（建 team / 加成员时逐条过）**
- [ ] 每个成员能一句话说清职责，且与所有其他成员的 useWhen 无重叠区
- [ ] 每个成员的工具白名单反推过"它绝不该做的事"
- [ ] Build 与 Verify 分离：没有成员既当产出者又当自己的验证者
- [ ] system.md ≤150 行 / ≤900 词；超限段已问过"该进 rules/skill/memory 吗"
- [ ] 新成员 description/useWhen 是为本 team 重写的（复制来的必须换域校验）
- [ ] 成员数在 3-10；>10 有拆分论证；≤20 硬线
- [ ] 想清楚了这是项目型还是事物型，rules.md 模板选对了

**协作设计（写 rules.md 协作契约时）**
- [ ] DoD 明确（代码过 QA 才算完成；交付必须带证据）
- [ ] 验证配对表就位（谁产出→谁验证→什么手段）
- [ ] 四模式有实例：至少一条结对线、一条交接线、审查/打回有上限（RETRY≥2 升级）
- [ ] 上行单通道保持：成员不直 Mail Nebula、跨 team 走 Manager 升级
- [ ] 过程可见性三件套：关键事件 CC Manager（F2）、周期 digest（F3）、flow-mailbox 审计（F1）

**边界维护（持续）**
- [ ] 无跨 team 复制粘贴的 agent（有则触发换域校验）
- [ ] 成员 memory 无串域条目
- [ ] 月度检视：闲成员（<2 次派发）、重叠路由、超载成员

---

## 5. 与现状差距

| # | 目标 | 现状 | 差距性质 |
|---|------|------|---------|
| 1 | 成员直连协作 | 传输层支持，实测 0 发生 | 纯 prompt/契约层（基线 P0 已覆盖，本文补充实证） |
| 2 | Manager 过程可见性 | 仅静态 catalog + 自经手邮件 | 缺 F2 CC 纪律与 F3 digest 机制（P0 契约 / P1 代码） |
| 3 | 上行单通道 | 与目标一致，且比认知更严（standalone 也禁） | 无差距；但 team/agent 后门需决断（§7） |
| 4 | 成员规模治理 | 无校验无文档 | P1 reviewer 清单 + P0 文档 |
| 5 | 最小心智规范 | 好/坏样本并存，无成文标准 | P0 写入设计 checklist 与 prompt-engineer 的检查基线 |
| 6 | 两类 team 的差异化组织 | 仅 description 措辞差异 | P0 rules.md 双模板 |
| 7 | 换域校验（防复制漂移） | qa-backend 漂移已发生 | P1 entity-creator reviewer 清单 |

## 6. 实施要点

1. **P0（零代码）**：① 按基线方案 P0 改 system-prefix-for-teams.md 完成回报契约 + 各 rules.md 协作契约——本报告的实证（345 封零直连）将其从"改进"升级为"必须"；② 契约中加入 F2 CC 纪律（"PASS/FAIL/接口变更/升级事件必须 CC Manager，type=RESULT"）；③ §4 checklist 交 prompt-engineer 并入 agent 体检流程。
2. **P1（小改动）**：① F3 周报聚合——扩展 ManagerProgressHook，从本 team flow-mailbox 聚合周期 digest（数据已存在，纯读侧功能）；② entity-creator reviewer 加"换域校验 + 成员数上限"两条；③ 决断 team/agent 后门去留（见 §7）。
3. **验收锚点（复用基线 P0 验收 + 新增）**：直连链跑通后，断言 Manager 收到的最终 RESULT 附带完整直连证据链；断言周期 digest 出现在 Manager memory；对 nebflow-project 修复 qa-backend 描述漂移并回归 rules.md 路由。

## 7. 开放问题（含倾向）

1. **team/agent 显式跨 team 路由留不留？** 倾向**留但收紧**：显式格式是有意的逃生门（调试、Nebula 直插），但应对非 lead 的 team 成员默认关闭、由 team rules.md 显式开启（如"允许 Frontend 直接对接 nebflow-website/Frontend 复用组件"）。零成本方案是先在 checkTeamScope 的 None 分支加一条警告文案。
2. **Manager 全局记忆选 F2+F3 还是 F1 直读？** 倾向 F2+F3（理由见 §3.1 表）；若 P0 阶段纪律不可靠，F1 可作为过渡（Manager 每日固定读一次 flow-mailbox 摘要，代价是 token）。
3. **成员上限 20 要不要进代码？** 倾向不进 Decoder（机制/策略分层），进 entity-creator reviewer + 设计 checklist；唯一例外是硬线 20 可以做成创建时的 blocker。
4. **项目型 team 的"项目状态记忆"要不要独立于 Manager memory？**（rules.md 现在承担了一部分，Manager memory 承担另一部分，存在双写漂移风险。）倾向：项目状态以 rules.md 为唯一权威，Manager memory 只存派发经验——文件职责单一化，与最小心智原则同构。
