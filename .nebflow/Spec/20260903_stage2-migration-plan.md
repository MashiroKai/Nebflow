# Nebflow 阶段 2 迁移方案总文档——Project+Node 全面取代 Team/Flow

> **版本**：**v2**（2026-09-03）。v1：2026-09-03（~/.nebflow commit 9552714）。

## 修订记录

| 版本 | 日期 | 依据 | 变更摘要 |
|---|---|---|---|
| v1 | 2026-09-03 | 上游「设计-阶段2迁移方案总文档·重派」节点产出 | 初版（独立重做；前次设计节点 09-03 02:25 宿主重启打断废弃） |
| v2 | 2026-09-03 | **作者 2026-09-03 12:18 拍板（五条裁定）** | ① §3.3 八个决策点全部落定：均采纳建议 a，原选项表保留存档；② 废除预定 node 模板路线：§2 改写为「分发器按任务自主组织拓扑」，5 个原「转拓扑模板」flow 改为蒸馏 skill / 直接归档二选一（§1.3），配方库（project-node-patterns.md）计划取消；③ Task 落为 project 唯一触发入口：Mail(→project) 兼容入口从计划删除，Mail(旧 team 名) fallback 按 DP7-a 保留为安全网，过渡期存续 team 任务仍走 Mail 至归档；④ 新增 §6 新机制：Project 面板归档按钮 / ProjectCreate 未知路径交互面板 / 「无 delegate、全走 Project」影响分析；⑤ §5 里程碑调整：§6 新机制实施插入 M1，「全部迁移」口径落档（存续任务终态即迁、不等活动期），时间线重排；另顺带修正 §1.2 czt 批次号笔误（6→4，与 §1.5 对齐） |

> 撰写：2026-09-03（重派节点独立产出——前次设计节点于 09-03 02:25 宿主重启打断、wiring 悬挂废弃，本文档为全新重做，非旧节点续写）。
> 性质：**纯方案设计，不写任何产品代码**；主仓 `/Users/dev/Claude code/Nebflow` 全程只读；唯一写入面 = 本文档。
> 依据（按序）：
> ① `20260831_project-node-architecture.md`（v2 主文档，下称【v2】——阶段划分与机制框架；【v2】指该参考文档自身版本号，与本文档版本 v2 无关）
> ② `20260902_flowmap-engine-evolution-design.md`（下称【引擎演进】——deps 边/LoopNode/反馈协议/blocked 重入）
> ③ `20260903_flowmap-graphview-v3-final.md`（下称【图视图】——前端最终形态）
> ④ `20260902_project-architecture-phase2-design.md`（下称【phase2】——沙箱/Plugins/agent 收敛总设计；本文与其 F/G 章零矛盾，显式修正处逐条标注依据）
> 机制细节一律引用上述文档对应章节，不复述；判定用表格一行理由；不写行号级易腐细节。
> 现状实况核实（2026-09-03）：4 个 project 定义已挂载（nebflow 主仓试点 / phd-notebook 试点 / nebflow-website / slideblocks——后两个与同名 team 并存、team 实质空转）；9 team（51 个 team domain agent 定义）/ 9 flow 存量待迁；引擎侧串联/barrier/deps 边/blocked 重入已经 09-02/03 全量实战验证（本轮 nebflow Flow Map 即运行证据）。

---

## §1 迁移对象清单与优先级

### 1.1 迁移总动作（每 team 固定五步，来自【phase2】§F.4 通用动作，不逐 team 重复）

① `ProjectCreate`（workspace = team 对应 repo）→ ② rules.md 正文并入工作区根 `AGENTS.md`（映射规则见【phase2】§E.3）→ ③ 成员领域知识蒸馏 skill（用户层 `~/.nebflow/skills/` 先行落地、节点 skill 参数即可引用；【phase2】2b 落地后随批迁入 plugin 封装——迁移轨道不被 2b 阻塞）→ ④ 一个真实任务走通 project 链（冒烟验收）→ ⑤ `git mv team.json team.json.archived`（【phase2】§F.3 归档机制，git 历史保全、回滚可逆）。Manager 定义一律归档（职责见 1.2 逐行映射）。

### 1.2 9 team 逐个盘点

| team | 成员 agent 定义去向 | Manager 职责并入分发器哪一环节 | rules/skills 迁移 | 批次 |
|---|---|---|---|---|
| **nebflow-project** | Backend/Frontend/Docs/prompt-engineer/tool-engineer/cache-engineer 领域知识 → `nebflow-dev` skill 组（按域拆 skill 文件）；qa-backend/qa-frontend → 验证域 skill（决策点 4）；9 个定义全部归档 | 已被分发器事实取代（nebflow project 运行中）；回炉仲裁 → blocked 反馈协议升级（【引擎演进】FeedbackRouter） | rules.md 141 行 → 主仓根 `AGENTS.md` 增补（基底已存在）；成员 skills 订阅 → 节点级 skill 分配 | **1** |
| **slideblocks** | Frontend/Backend/Docs → `slideblocks-dev` skill 组（registry 约定/i18n 规范）；3 定义归档 | 分发器标准环节 | rules.md 50 行 → workspace 根 `AGENTS.md`（已存在，查漏增补） | **1** |
| **nebflow-website** | Frontend → 前端工程 skill；Designer → `design-spec` plugin（【phase2】§F.2 已裁）；2 定义归档 | 分发器标准环节 | rules.md 71 行 → workspace 根 `AGENTS.md`（已存在） | **1** |
| **ReminderIsland** | swift-dev 五域知识（焦点链/键盘链/popover/Timer-RunLoop/IME）→ `swift-dev` skill 组；qa-swift 验证流程（构建+复现+截图对比）→ `swift-verification` skill（决策点 4）；2 定义归档 | 合并把关 → barrier 合并节点；回炉计数 → LoopNode 轮次 + blockCount 升级；QA 中转职责取消（§3.2 直触等价物） | rules.md 94 行 → `/Users/dev/Claude code/Reminder/AGENTS.md` | **2** |
| **html-deck-studio** | deck-* 家族 10 agent（同一能力参数化——【phase2】§F.4 判定其为"改提示词做专业化"反面教材）+ content-planner/html-builder/visual-reviewer → `deck-studio` skill（制作流程 SOP）+ general 节点参数化；13 定义归档 | 分发器标准环节；deck 质量裁决 → LoopNode（worker=制作/verify=视觉评审） | rules.md 71 行 → deck 工作区 `AGENTS.md`；slideblocks skill 订阅保持用户层 | **2** |
| **sipm-paper** | sipm-physicist/sipm-writer/sipm-verifier → `sipm-review` skill（期刊规范/回复信格式/逐条验证清单——审稿多轮复用价值高）；3 定义归档 | 分发器标准环节；作者终审把关 = out=Nebula 验收 + 补充改接 | rules.md 65 行 → NIMA workspace `AGENTS.md` | **3**（DP2-a；v2 全部迁移口径：存续任务终态即迁，不等审稿窗口） |
| **czt-project** | czt-physicist/czt-writer/czt-researcher → `czt` skill（物理仿真/论文写作方法论）；3 定义归档 | 分发器标准环节 | rules.md 31 行 → CZT workspace `AGENTS.md`（或并入 phd-notebook——决策点 3） | **4**（DP3-a；v2 全部迁移口径：存续任务终态即迁，不等周期收官） |
| **voice-recognition-test** | 实验结束直接归档不迁移（【phase2】H-13 ②已裁）；若实验有正产出，vr-algorithm 领域知识届时再蒸馏 | — | rules.md 60 行随归档保留（git 历史） | **4**（静默判据触发，决策点 5） |
| **nebflow-rust** | 不迁移不蒸馏，定义归档；Rust 域知识留在 git 历史（`git log --follow` 可追溯），未来重启直接开新 project | — | rules.md 111 行随归档保留 | **4**（立即归档） |

> **对【phase2】§F.4 的两处显式修正**（任务书授权"可修正但给依据"）：① nebflow-rust 由 F.4"开发中、优先级 3"修正为**直接归档**——依据：该轨道已静默、无在途任务，按 H-13 同款逻辑"无存续任务的 team 迁移是负产出"，且其与 nebflow-project 共用域名 agent 定义（Backend/Frontend/Docs/qa-*）但实体独立，归档无跨项目影响；② nebflow-website/slideblocks 由 F.4"维护/活跃开发"修正为**批次 1 直接归档**——依据：二者的同名 project 已挂载、AGENTS.md 已就位，迁移实质已完成，仅剩归档收尾。

### 1.3 9 flow 逐个判定（v2 二选一：蒸馏 skill / 直接归档）

> v2 裁定（作者 2026-09-03 12:18）：**废除预定 node 模板路线**——无 flow 转拓扑模板；SOP 中可复用的纪律性内容蒸馏进 skill（节点 skill 参数引用），一次性流程价值低的直接归档。v1 的「转拓扑模板」判定随模板路线废止，原文见 ~/.nebflow git 历史（v1 commit 9552714）。

| flow | 判定 | 一行理由 |
|---|---|---|
| **research** | 蒸馏 skill `parallel-research` | 正交拆题、来源强制、逐轨核验、双轨综合的调研纪律可复用于任何调研任务；拓扑形状不复用，由分发器按任务即席重建（§2.3 示例） |
| **code-review** | 蒸馏进既有 `review` skill（增补轮次纪律） | 三维评审锚定量表已由 `review` skill（290 行）承载，增补扫描清单与修复-复审轮次纪律即可；verdict 门禁语义由 Nebula 读结果+重触发承载（§2.4 示例） |
| **git-merge** | 蒸馏 skill `merge-pipeline` | 分支扫描→就绪评估→冲突处理→合并回滚的纪律跨项目通用；worktree=None → root=主仓语义由【phase2】§A.6 现成承载 |
| **nebflow-review-merge** | 转 skill（并入 nebflow-dev skill 组） | 项目专属 SOP（分支扫描→编译测试→审查→合并→worktree 清理），流程纪律文档化为 skill 供分发器引用；建图由分发器按任务即席完成（无模板） |
| **release-stable** | 蒸馏 skill `release-pipeline` | 审→做→包→复审是低频高危的发版纪律、走样代价大，必须文档化；轮次形状由分发器按任务选型（LoopNode/重触发） |
| **presentation-prep** | 直接归档（检索/规划纪律并入 `deck-studio` skill） | 演示前置与 deck 制作同域、独立 flow 价值低；素材真实性/禁占位/时长匹配校验纪律由 html-deck-studio 迁移产出的 deck-studio skill 吸收（§1.2 批次 2） |
| **memory-consolidation** | 转 skill | 清理动作 = Nebula 调 MemoryEdit 的 replace_section/remove（【phase2】§C.2 已裁），单一能力无多角色拓扑必要；方法论做成 skill 供 Nebula 使用 |
| **weekly-summary** | 转 skill | 读一周会话→蒸馏→写盘是单能力，2 节点链无角色分离必要；Schedule 周日 22:00 触发保留，任务文本改指 project 单节点完成 |
| **entity-creator** | 转 skill/plugin（远期）——双轨保留最久 | 自举依赖：收敛期仍靠它造实体（【phase2】§F.5 原判）；待 2b/2c 终态后改造为 plugin 管理工具（决策点 6），期间 flow 原样保留 |

> 转型收尾：`flows/` 目录在全部转型完成后归档（`flow.json → flow.json.archived`）；`presentation-prep.zip` 残留 `git rm`（【phase2】§F.5）。v2 裁定：配方库（`project-node-patterns.md`）计划随预定模板路线一并取消——拓扑无预定模板（§2 原则段），机制语义存于 §2.1 可用机制参考。

### 1.4 全局 standalone agents 处置

| agent | 处置 | 依据 |
|---|---|---|
| **Coder** | 能力保留、定义归档：工程交付纪律（构建验证分离/git 纪律/结果自检）并入 general 模版新增"工程交付"节 + `dev-toolkit` skill；**不保留第 4 个全局 agent 定义**（决策点 1 供作者改判） | 【phase2】裁定 4"收敛三定义"是硬裁定；任务书"Coder 保留为通用劳动力"落实为**能力保留**而非定义保留，零矛盾 |
| **Explorer** | explorer-toolkit plugin（exploration-method + solution-planning 两 skill）+ visual-report 合并增强；定义归档 | 【phase2】§F.1 已裁，不重复展开 |
| **design-engineer** | design-spec plugin；定义归档 | 【phase2】§F.2 已裁 |
| **Manager**（全局目录残留） | 归档——分发器全面取代 | 【v2】§1.3 映射表 |
| **Planner** | 归档——分解职责上移分发器（flow 的 planner 节点同理，见 §2.1 行 5） | 分发器即分解者（【v2】§2.4） |
| **qa-backend / qa-frontend / qa-swift** | 验证能力蒸馏为验证域 skill（决策点 4 定粒度）；定义归档 | 【引擎演进】裁定 A：verify 侧 = 通用 agent + plugins 按验证域分配 |
| 其余全局目录（Jarvis/Mail/MemoryAgent/Backend/Frontend/Docs 等历史与域名残留） | 随对应 team 迁移蒸馏后归档；空壳目录直接 `git rm` | 【phase2】§F.3 归档范围与操作序列 |

### 1.5 迁移顺序与依据

**批次 1（nebflow-project / slideblocks / nebflow-website）→ 批次 2（ReminderIsland / html-deck-studio）→ 批次 3（sipm-paper，决策点 2）→ 批次 4（czt-project 决策点 3、voice-recognition-test 判据触发、nebflow-rust 立即）**。

对任务书初步意见（"html-deck-studio/sipm-paper 活跃靠前"）的修正与确认：
- html-deck-studio **保持靠前**（批次 2）——确认。依据：演示制作是当前高频真实需求，且 deck 家族 10 agent 收敛为参数化 general 节点是新架构"专业化=plugin 分配"的最佳实证场景。
- sipm-paper **批次 3，存续任务终态即迁**——v2 修正。依据：二轮审稿修改正在 team 架构上活跃运行，活跃中切换有在途协作链断裂风险（【phase2】裁定 1"逐渐取代"语义：存续期任务跑完再切）；v2「全部迁移」口径（作者 09-03 12:18：「目前有的项目都迁移」）：在途任务全部终态即迁——不等下一轮审稿意见落地、三轮不来也迁，原「回落 F.4 直接归档」分支删除。
- 排序总依据：① project 已挂载的先收尾（零新机制）；② 活跃开发其次（蒸馏收益与图谱验证价值最大）；③ 存续任务未终态的随后——其终态即触发迁移，不等活动期（v2 口径）；④ 无存续任务的最后归档。

---

## §2 拓扑组织：分发器按任务自主建图（无预定模板）

> **原则（v2 裁定；作者原话：「不要有预定node模版，分发器自己根据任务，自己可以组织」）**：无预定 node 模板——拓扑由分发器在单次会话内按任务现场组织，标准动作序列：**NodeList 读图谱现状 → 任务分解（planner 职责上移，分解产物写进各下游节点 task）→ worktree 评估（是否需要隔离工作区）→ NodeEdit 建节点接线 → 自检（无环 / 入口有 task / in 引用存在）→ 会话结束**。拓扑形状由任务决定、逐次可不同；旧 flow 不转成模板，仅其 SOP 纪律蒸馏进 skill（§1.3）供节点 skill 参数引用。下表保留的技术机制语义（串行链 / barrier / deps 边 / LoopNode 等）是分发器建图时的可用机制参考，不是预定形状。

### 2.1 可用机制参考（旧 flow 机制 → 新架构等价语义）

| # | 旧 flow 机制 | 新架构形态 | 要点 |
|---|---|---|---|
| 1 | stage 串行链（A.onComplete→B→C） | 串行 Node 链 | 分发器逐个 NodeEdit：A（有 task 无 in）创建即运行，B/C 以 in 接线；结果沿 out 边自动投递（【v2】§2.7） |
| 2 | verdict 门禁（switch $x.verdict cases） | verify 节点 + 分发器重入改接 | verdict 机制整体消失（【v2】v4 修订：结果=最终输出文本）；评审节点把结论写进输出文本，Nebula 读结果判断未通过 → 再 Task(project=…) 触发 fix 轮节点子图，DAG 保持无环；单点验证用此形态 |
| 3 | 修订循环（reviewer⇄fixer，maxLoop=N） | **LoopNode**（需反复打磨直到通过）或轮次重触发（轻量） | LoopNode：worker/verify 双会话内循环、FAIL 自动打回、K=5 内容死循环兜底（【引擎演进】§2）；轻量场景每轮新节点（fix 节点 + 复审节点），无轮数帽、Nebula 验收层控制轮次 |
| 4 | 并行 fanout（onComplete parallel [r1..rN]） | N 个独立入口节点 + barrier 合并节点 | 并行 = "多个入口同时运行"（入口节点创建即运行），汇聚 = 合并节点 in 累积 N 条、全到才启动（【v2】§2.4） |
| 5 | planner / 分解节点 | **分发器自身取代** | 分发器的本职就是分解任务（【phase2】§C.3 协议③）；planner 的 slots 输出（如 topics）由分发器拆写进各下游节点 task 文本——planner 节点在转换后通常不复存在 |
| 6 | slots 结构化传递（$x.slots.y） | 结果文本内嵌结构化段 / 分发器转述 | 节点结果 = 最终输出文本；跨节点结构化数据 = 上游文本中带标注段落，或分发器读结果后写进下游 task（分解职责上移的自然推论） |
| 7 | 纯触发依赖（无结果消费，只要"先后"） | **deps 边** | 只等上游完成信号、不投递结果（【引擎演进】§1.0：如"先建 worktree 再开工"） |
| 8 | 单节点流（GUIDE 判非法） | 单节点 project 触发 | NodeEdit 单节点 out=Nebula——单任务即单节点图（目标架构无 delegate，v2 裁定 ④③；【v2】§1.3） |
| 9 | maxFanout / maxLoop 常量 | 消失 | 并行度由分发器按任务定（非定义固化）；循环上限哲学由 LoopNode K=5 精确重复检测承接（无轮数帽） |

### 2.2 转换纪律（三条）

1. **无预定模板——分发器即席建图**：拓扑不来自任何预定义模板或配方库（v2 裁定废除，配方库计划取消）；分发器在单次会话内按任务现场组织（§2 原则段动作序列）。旧 flow 的形状不固化复用，仅其 SOP 中可复用的纪律性内容蒸馏进 skill（§1.3 v2 判定）。
2. **错误分支 → BLOCKED 协议**：旧 flow 的 switch error 分支（如 code-review scanner 失败即返回）由 BlockedReader 自然覆盖——节点输出 BLOCKED 锚定 → blocked 终态 → 反馈路由重入，无需在拓扑里表达错误边（【引擎演进】§0.2）。
3. **循环优先 LoopNode**：凡"产出-验证-打回"闭环（code-review fixer、git-merge merger 回环、ReminderIsland qa 打回）默认 LoopNode；只有轮次间需要换 agent/换验收基准时才用重触发多节点形态。

### 2.3 即席建图示例一：调研类任务（原 research flow 场景）

旧拓扑（flow.json 实测，仅作机制对照）：`planner → parallel[r1, r2] → verify → writer → $return`。

分发器即席建图（文字图，单次会话内建完——形状由本任务需要决定，非模板）：

```
Nebula Task(project=…)「调研 <主题>」
  └─ 分发器会话：
     ① 拆 2 个正交子题（planner 职责上移，§2.1 行 5）
     ② NodeEdit r1（agent=general, skill=web-research 类检索 skill,
        task="调研子题 A：<子题文本>。每个论断必须带来源链接。", out→verify）
     ③ NodeEdit r2（同上, task=子题 B, out→verify）
     ④ NodeEdit verify（task="逐轨核验：来源链接可达性；论断与来源实际内容一致性；
        标记无支撑/矛盾论断。", in=[r1, r2]/*barrier 全到才启动*/, out→writer）
     ⑤ NodeEdit writer（task="综合成文：合并双轨、纳入核验结论（无支撑论断标注或剔除）、
        附完整来源清单。", in=[verify]/*verify 输入已含双轨原文，边最小化*/, out=Nebula）
     ⑥ 自检（无环、入口有 task、下游 in 引用存在）→ 会话结束
r1/r2 并行跑 → verify barrier 启动 → writer → 结果投 Nebula 验收
```

与旧 flow 的两点结构差异：① planner 节点消失（分发器拆题）；② writer 的 in 从"三路全收（r1+r2+verify）"减为单路 verify（verify 的输入已聚合双轨，避免同一原文双份投递）。可选触发 2：verify 结论显示某子题需补查 → Nebula 触发分发器建补查节点（in=verify 归档结果自动投递，【v2】§2.6 跨阶段引用）。

### 2.4 即席建图示例二：评审-修复类任务（原 code-review flow 场景）

旧拓扑（flow.json 实测，仅作机制对照）：`scanner →switch[ok→reviewer / error→$return]；reviewer →switch[pass→$return / fix→fixer]；fixer→reviewer（maxLoop 5）`。

分发器即席建图（文字图）：

```
触发 1（Nebula Task(project=…)「review <分支/PR>」）：
  └─ 分发器：
     ① NodeEdit scan（agent=general, skill=review,
        task="扫描 <目标> 变更，产出 issue 清单 + diff 摘要。", out→review）
        // 旧 error 分支不再需要：扫描本身失败 → 输出 BLOCKED 锚定 → blocked 重入（转换纪律 2）
     ② NodeEdit review（agent=general, skill=review,
        task="三维评审（架构设计/代码质量/安全+可观测），锚定量表 1-5 打分，
              按严重度分类。未通过时首行 VERDICT: FAIL 并逐条列出问题。",
        in=[scan], out=Nebula）
  scan 完成 → review 启动 → 结论投 Nebula

触发 2（review 结论未通过——Nebula 读结果文本判断）：
  └─ 分发器（推荐形态：修复需反复打磨 → LoopNode，§2.1 行 3）：
     ① NodeEdit fix-loop（nodeType=loop,
        agent=general + dev skill 组/*worker 侧*/,
        task="修复 review 指出的 Critical/Warning 问题，修复后重跑自动化检查。",
        verifyTask="逐条核对问题已修复、无回归；不通过则列具体问题。",
        in=[review]/*review 已终态，归档结果自动投递*/, out=Nebula)
     // worker↔verify 内循环直到 PASS；K=5 逐字相同兜底；无轮数帽
  备选轻量形态（单轮修复即可过）：NodeEdit fix(in=[review], out→review2)
  + NodeEdit review2(in=[fix], out=Nebula)——每轮新节点，DAG 无环
```

maxLoop=5 的语义去向：LoopNode 无轮数上限、由 K=5 内容死循环检测兜底（【引擎演进】裁定哲学：删模糊进展判定、留精确重复检测）——"最多 5 轮"的软约束转化为"连续 5 轮零进展即失败"的硬信号。

---

## §3 调度切换

### 3.1 Nebula 路由切换——机制与节奏

**机制基础**：system prompt 注入层的 Teams & Flows 目录从实体定义动态生成（启动装载 teams/ flows/ projects/）——归档即下架，无需手工维护摘除清单。这是本节的核心结论：**目录切换是归档动作的自动伴随，不是独立工程**。

| 节奏 | 动作 | 断言 |
|---|---|---|
| T0（现在） | 双轨并存：Mail(team 名)→Manager（旧，仅存续 team）；Task(project 名)→ProjectActor（新）。**v2 裁定：Task 是 project 唯一触发入口——Nebula 不再用 Mail 触发 project，Mail(→project) 兼容入口从计划删除**。Nebula 提示词双轨期规则已裁（【phase2】§C.2 第 4 节：新工作一律 Project；team/flow 仅承接存续期任务） | 两种路由各自可达（team 走 Mail、project 走 Task） |
| T1（每 team 迁移时） | 顺序纪律：先 ProjectCreate（project 条目进目录）→ 真实任务冒烟走通 → 归档 team（team 条目自动消失）。归档后 Mail(旧 team 名) 走 fallback：路由层"团队名优先、project 名兜底"（【phase2】§F.6）自然落同名 project，非同名返回迁移提示——**DP7-a 安全网，零改动，v2 确认保留** | Mail(旧名) 到达 project 或返回明确提示（二值） |
| T2（全部归档后） | 目录仅剩 project 条目；queue 模式串行链语义由边表达（A→B→C），Task 为唯一触发入口（【v2】§3.2）；Mail(旧 team 名) 仅剩 fallback 安全网（DP7-a） | 目录注入内容 grep 零 team 条目 |
| T3（阶段 3 退役批） | 删注入代码段与 team 路由分支（§4.1），目录层 Team/Flow 段整体移除 | 编译零残留引用 |

**双轨期语义三条**（均沿用【phase2】§F.6，此处只列执行口径）：① 新工作一律 Project——Nebula 提示词承载，不靠目录间接引导；② 在途 team 任务跑完自然终态，不迁移不中断；③ Task 是 project **唯一触发入口**——Nebula 不再用 Mail 触发 project，Mail(→project) 兼容入口从计划删除（v2 裁定，显式偏离【phase2】兼容入口表述，依据=作者 09-03 12:18 拍板）；Mail(旧 team 名) fallback 按 DP7-a 保留为安全网（零改动，失败提示语）；过渡期存续 team 的任务仍走 Mail 直到该 team 归档。

### 3.2 QA 直触协议等价物

**现状盘点**（rules.md 与裁定记录实文核实）：team 域 qa-* agent（qa-swift/qa-backend/qa-frontend）为常驻成员、有 Mail 身份。直触链路 = 产出者（如 swift-dev）自测后**直接 Mail 同 team qa-***（附改动摘要+复现/验收步骤）→ qa-* 构建+复现+截图对比 → **PASS 抄送 Manager 单行信号 / FAIL 直接 Mail 产出者打回**（附逐条问题），Manager 不做中转（只收信号、回炉 ≥2 次升级仲裁）；跨 team 直触被禁（2026-08-23 规矩：先报 Manager 确认路由）。该协议的存在前提 = 成员有 Mail 身份 + 常驻会话——两者在新架构均消失（Node 是 leaf、无 Mail 身份、结果走边投递，【v2】§3.2）。

**等价设计（推荐）——验证关系从消息协议变为图拓扑**：

| 现状语义 | 新架构等价 | 机制落点 |
|---|---|---|
| 产出者直接触发 QA（不经 Manager） | 分发器建图纪律：凡产出节点其后接 verify 节点（verify.in=产出节点, out=Nebula）；产出完成 → 结果自动投递 → verify **自动启动**——"直触"由边承载，零消息协议 | in 边投递（【v2】§2.7） |
| FAIL 打回产出者 | worker↔verify 内循环，verify 意见逐轮注入 worker | LoopNode（【引擎演进】§2.2） |
| PASS 才算交付 | FAIL 不产生 completeNode——verify 通过才向外投递 worker 产出 | LoopNode 终态语义（§2.2 状态机） |
| 回炉 ≥2 次升级仲裁 | blockCount>2 升级（数值恰好对齐现状规矩） | blocked 反馈协议 MaxBlockRoundsPerNode + FeedbackRouter（【引擎演进】§0.2） |
| PASS/FAIL 信号抄送 Manager | 节点 result 文本即信号，Nebula 是 out=Nebula 结果第一接收者 | 结果=最终输出（【v2】v4 修订） |
| 跨 team 直触禁止 + 路由确认 | 问题自然消解：验证能力 = skill/plugin 不绑定成员身份，任何 project 的分发器都可分配验证域 skill；跨项目验证由 Nebula 编排多 project | 裁定 A（【引擎演进】§6 #5） |

**推荐理由**：① 零新机制——全部复用 in 边投递 + LoopNode + blocked 反馈协议，无任何新工具/新消息类型；② "构建与验证分离"纪律从**规矩**升级为**结构强制**——图上没有 verify 节点，产出就无法到达 Nebula 验收（拓扑即流程，比纪律约束可靠）；③ 轻量场景不需要完整 LoopNode 时，退化为"产出节点 out→verify 节点"两条边的即席建图，分发器按任务自由选型。

### 3.3 决策点（v2 已全部拍板；原选项表保留存档）

> **作者 2026-09-03 12:18 拍板：DP1-8 全部采纳建议 a**。下表为原选项存档（不删），结论见末列；DP2/DP3 的时点口径另按 v2「全部迁移」裁定修正（见末列与 §5 M3）。

| # | 决策点 | 选项 | 建议 | 结论（v2） |
|---|---|---|---|---|
| 1 | **Coder 定义去留** | a) 能力并入 general 模版"工程交付"节 + dev-toolkit skill，定义归档（三定义不破）；b) 保留 Coder 为第 4 个全局 agent 定义（需修订【phase2】裁定 4 为"三定义+可选执行模版"） | **a**——裁定 4 是收敛的基石；"保留为通用劳动力"由能力承载，工程纪律写进 general 模版一节即可（约 10 行），独立定义反而保留"改提示词做专业化"的旧路 | 作者已拍板（2026-09-03）：采纳 a |
| 2 | **sipm-paper 迁移时点** | a) 批次 3 窗口迁移（在途任务终态后、下轮审稿意见前）；b) 维持【phase2】§F.4 原判——二轮修改完成后直接归档不迁移 | **a**——审稿多轮是常态（三轮五轮常见），sipm-review skill 与验证链拓扑有跨轮复用价值；若三轮不来则自然回落 b | 作者已拍板（2026-09-03）：采纳 a；v2「全部迁移」口径修正——「回落 b」分支删除，存续任务终态即迁（§5 M3） |
| 3 | **czt-project 处置** | a) 维持 F.4 原判：paper 周期结束归档 + czt skill 蒸馏；b) 研究仍活跃 → 并入 phd-notebook project（同域素材库、workspace 相邻）；c) 独立建 CZT project 即刻迁移 | **a**，若 CZT 研究在论文后继续则升级为 b（不新建 project——同域双 project 徒增分发割裂） | 作者已拍板（2026-09-03）：采纳 a；去向不变（蒸馏+归档、不新建 project），时点按 v2「全部迁移」口径修正为存续任务终态即迁 |
| 4 | **验证域 skill 粒度** | a) 每域一个（swift-verification / frontend-verification / backend-verification——验收工具链差异大：screencapture+xcodebuild / Playwright+截图 / curl+sbt）；b) 单一 verification plugin 内分域章节 | **a**——NodeEdit 分配粒度与 skill 触发精度都对齐"一个域一个包"；b 的单包内章节会让节点注入无关验证域内容 | 作者已拍板（2026-09-03）：采纳 a |
| 5 | **voice-recognition-test 归档判据** | a) 静默 30 天即归档（不等实验正式收官）；b) 严格等实验结束 | **a**——H-13 ②的精神是"无存续任务不迁移"，静默 30 天即事实上无存续任务；git mv 可逆，实验重启代价一次反向 mv | 作者已拍板（2026-09-03）：采纳 a（该 team 为「归档不迁移」例外——无存续任务，与 v2 全部迁移口径不冲突，见附：自检）；**⚠ 2026-09-03 23:58 作者否决「30 天静默归档」约定——DP5 作废，不按静默时长自动归档，voice 归档改等作者显式指示** |
| 6 | **entity-creator 改造时点** | a) 2b+2c 终态后作为独立专项节点执行（改造为 plugin 管理工具），期间 flow 双轨保留；b) 阶段 3 收尾前才动 | **a**——它是 9 flow 中唯一自举依赖，越晚改造阶段 3 收尾越被卡；改造本身也是 plugin 体系的第一个真实消费者验收 | 作者已拍板（2026-09-03）：采纳 a |
| 7 | **Mail(旧 team 名) fallback 语义** | a) 团队归档后旧名自然落同名 project（路由层已有兜底，零改动）；b) 显式返回"已迁移"提示，要求 Nebula 用新名重发 | **a**——零改动且对 Nebula 透明；迁移提示作为 fallback 失败时的错误消息补充即可 | 作者已拍板（2026-09-03）：采纳 a（v2 裁定 3 之安全网保留的依据） |
| 8 | **退役稳定期判据**（§4.3 详情） | a) 9 team 全归档 + 9 flow 全转型 + 连续 14 天日志零旧体系调用 + ≥3 活跃 Project 在用；b) 只看时间（归档后固定 30 天） | **a**——"零调用"必须以归档为前提（有 team 可调用就没有零调用可言），"≥3 活跃 Project"排除"没人用所以零调用"假阳性；14 天覆盖 2 个 weekly-summary 周期 | 作者已拍板（2026-09-03）：采纳 a |

---

## §4 退役清单（阶段 3）

### 4.1 代码删除面（类/模块级；前置 = §5 M5 稳定期判据满足）

| 域 | 删除项 |
|---|---|
| 实体与装载 | teams/ 装载链（TeamLibrary、EntityLoader team 分支）、flows/ 装载链（FlowLibrary、flow.json 解析）；`teams/` `flows/` 目录归档后从白名单装载面移除 |
| 编排执行 | TeamActor / FlowTreeActor / FlowTreeRegistry / TeamSessionRegistry；FlowDagRunner / FlowDagExecutor（barrier/checkpoint 已由 NodeRunner + FlowMapStore 接管，【v2】§3.1）；FlowTrigger / FlowExecute 工具；FlowReport / FlowReportStore（verdict 语义已死，【v2】v4） |
| 任务与派发工具 | DelegateTool、SubTaskTool、TeamTaskCreate/List/Update（任务跟踪已被 NodeList 生命周期取代） |
| Mail 路由 | team 名优先分支、team 内 short-name 路由、team/agent 显式路由、queue 模式（串行链由边表达）；保留 project 路由与 Task 唯一触发语义、Mail(旧 team 名) fallback 安全网（DP7-a） |
| 注入层 | TeamCatalog 目录注入段、system-prefix-for-teams、manager-prefix、fixedToolsFor 的 team/flow/Nebula legacy 分支、buildAllowedToolSet legacy 路径、PromptSections 中 team 成员条款与任务协议段（【phase2】§D.1 标"阶段 3"各项） |
| agent 配置面 | agent.json `tools` 字段解析、`mcpServers` 字段与 AgentMcpLoader（【phase2】裁定 11：工具可配置全走 plugins） |
| 前端 | 侧边栏 Team/Flow 按钮（阶段 2 已折叠为二级入口，此处移除残留）、Team 面板、旧 flow-run 运行视图路由（Flow Map 标签页已按【图视图】v3.1 接管显示）、任务列表 team 区块（删除清单以 `20260902_tasklist-team-retirement-cleanup.md` 为准，节点区块零伤已解耦） |

### 4.2 数据迁移

| 数据 | 处置 | 理由 |
|---|---|---|
| sessions/ 会话历史 | **只读保留**（原地不动） | 审计与回溯用途；weekly-summary 等 skill 仍需读历史 |
| TeamTask store / 任务列表历史 | 只读归档（不再写入） | 任务跟踪已由 Flow Map 生命周期取代；历史数据无迁移价值，删则失审计 |
| teams/ flows/ 目录 | `.archived` 就地保留（git mv 已保全历史） | 【phase2】§F.3：白名单不扩项、回滚可逆 |
| 运行中 flow / 在途 team 任务 | **不迁移**——跑完自然终态 | 【v2】§4 阶段 2 旧体系处置原则 |
| 各 project 的 flow-map.json | 不动 | 新架构原生数据，与退役正交 |

### 4.3 退役前置稳定期判据（启动 §4.1 删除批的闸门）

**判据（决策点 8 建议 a，四条同时满足）**：
1. 9 team 全部 `.archived`（或决策点裁定保留的，附保留理由与复评时点）；
2. 9 flow 全部完成二选一处置（skill 蒸馏 / 归档——v2 裁定废除模板路线）；
3. **连续 14 天日志零旧体系调用**——观测面：Mail 路由 fallback 命中、FlowTrigger/FlowExecute 调用、TeamTask 调用三类事件计数为零；
4. **≥3 个活跃 Project 在用**（观察期内有真实任务跑通）——排除"没人用所以零调用"的假阳性。

**14 天的理由**：覆盖 2 个 weekly-summary 触发周期 + 一个典型开发迭代周期；release-stable 月级低频，不靠等待覆盖、靠"已蒸馏为 release-pipeline skill 且触发方改指 project"保证（若稳定期内 release 真被触发，即是一次免费的 skill 实战验证）。判据满足后删除批按 §4.1 表逐域执行、每域独立 commit 可回滚（【phase2】§G.6 回滚纪律）。

---

## §5 里程碑排期

> 折算口径：1 个实施+验证闭环 ≈ 0.5-1 天（任务书给定节奏）。

| 里程碑 | 范围 | 可断言验收标准 | 时间线 | 依赖 |
|---|---|---|---|---|
| **M1 批次 1 归档 + 新机制** | nebflow-project / slideblocks / nebflow-website 三 team 归档；research / code-review / git-merge / release-stable 四 flow SOP 蒸馏（3 个新 skill + review skill 增补，§1.3 v2 判定）；weekly-summary / memory-consolidation 方法论 skill 化；**§6 新机制实施插入本里程碑**（Project 面板归档按钮 + ProjectCreate 未知路径交互面板——迁移五步的 ①ProjectCreate 与 ⑤归档自 M1 起即高频使用） | ① 3 个 team.json.archived 且 Mail(旧名) fallback 到同名 project（二值）；② 每归档 team 各 1 个真实任务走通 project 链（结果回 Nebula）；③ 四 flow SOP skill 落盘且节点 skill 参数可引用（review 增补部分核对完成）；④ AGENTS.md 增补核对完成（rules.md 要点清单逐项勾选）；⑤ §6 两机制可断言：归档按钮点击后 project 退出任务面板且 workspace 文件全保留、系统无任何自动归档路径；ProjectCreate 已知 path 直建、未知 path 弹出交互面板 | 3.5-4 天（含 §6 新机制约 1 天） | 无机制依赖（skill 用户层先行；plugin 封装随 2b 后补挂；§6 两机制为本里程碑自含实施项，不依赖 2b/2c/2d） |
| **M2 批次 2 活跃迁移** | ReminderIsland + html-deck-studio 迁移（ProjectCreate + AGENTS.md + swift-dev 五域/deck 流程 skill 蒸馏）；presentation-prep 归档（检索/规划纪律并入 deck-studio skill，§1.3 v2 判定） | ① 两 project 建成且 AGENTS.md 注入生效（分发器会话可见）；② ReminderIsland 跑 1 个"修复+验证"闭环任务——verify 接线拓扑实战（§3.2 等价物首次实证）；③ html-deck-studio 跑 1 个 deck 全流程（deck 家族收敛为参数化 general 节点的实证）；④ 两 team 归档 | 2.5-3 天 | LoopNode 未落地则验证闭环用 verify 节点+重触发形态（落地后升级 LoopNode，不阻塞） |
| **M3 批次 3/4 收尾** | sipm-paper 存续任务终态即迁（DP2-a；v2 全部迁移口径：不等下轮审稿意见，「回落不迁」分支删除）；czt-project 存续任务终态即迁（DP3-a；蒸馏+归档去向不变，不等周期收官）；voice-recognition-test 归档改显式人工决定（DP5-a 已否决）；nebflow-rust 立即归档 | ① 9 team 全部归档（或决策保留项有书面理由+复评时点）；② 注入目录 grep 零 team 条目；③ nebflow-rust/voice-recognition-test 归档 commit 各一（git mv 可追溯） | 1-2 天（事件驱动：起点=在途任务自然终态，等挂钟不计入排期） | 决策点已全部拍板（§3.3 v2）；迁移时点=存续任务终态（v2 全部迁移口径） |
| **M4 flow 收尾 + agents 归档** | entity-creator 改造为 plugin 管理工具（决策点 6）；全局 standalone agents 归档（§1.4 表逐行）；memory-consolidation 切 Nebula+MemoryEdit 实跑 | ① AgentLibrary 装载 = 恰好 3 定义（【phase2】§G.5 验收同款）；② flows/ 目录仅剩 entity-creator（改造完成后归档）；③ memory 清理实跑一轮且 MemoryEdit 越权路径拒绝 | 2-3 天 | **硬依赖 2b（plugins）+ 2c（三定义/MemoryEdit）终态** |
| **M5 阶段 3 退役删除批** | §4.1 清单逐域删除 + 前端旧面板移除 + 数据归档（§4.2）+ 文档/系统提示更新 | ① §4.3 四条判据全绿（先决）；② 每域独立 commit、全量 spec 绿、编译零残留引用（【phase2】§G.6 验收逐条）；③ 全量测试通过且旧工具调用返回"已退役"提示 | 删除批 3-5 天 + 稳定期 14 天（挂钟） | **硬依赖 2d（条件注入移除先行）**；M1-M4 全终态 |

**与【phase2】§G 其他轨道的依赖关系**：
- 本迁移轨道的**归档类动作**（team.json.archived / AGENTS.md 移植 / 目录切换）不依赖任何阶段 2 机制，可立即开始（M1-M3 主体）；
- **蒸馏类动作**的完整形态（plugin 封装 / verify 侧 plugin 分配）依赖 **2b Plugins**——过渡期用用户层 skill + 节点 skill 参数承载（现状可用），2b 落地后随批迁入 plugin，无返工（skill 文件原样移动）；
- **M4** 硬依赖 2b+2c；**M5** 硬依赖 2d——关键路径 = 2a→2b→2c→(M1-M4 并行推进)→2d→M5；
- LoopNode 落地（【引擎演进】§3.3 第 3/4 批）与 M2 无硬依赖（verify 节点形态先跑），落地后升级为推荐形态；
- 总量：迁移轨道 ~11-15 个闭环日（v2：M1 插入 §6 新机制约 1 天）+ 稳定期 14 天挂钟，与【phase2】§G.7"阶段 3：8-12 人日"口径一致（本文档是其执行序细化，蒸馏内容与 2e 重叠部分不重复计入）。

---

## §6 新机制（v2 新增）

> 依据：作者 2026-09-03 12:18 拍板新增。三项机制彼此独立、均不依赖阶段 2 其余轨道（2b/2c/2d）；实施插入 **§5 M1**——迁移五步的 ①ProjectCreate 与 ⑤归档自 M1 起即高频使用。

### 6.1 Project 面板归档按钮

- **手动触发**：前端 Project 面板提供归档按钮，仅由用户显式点击（或明确指令）触发；
- **归档后不在任务面板显示**：该 project 从任务面板消失（目录注入同步下架，与 team 归档同语义）；
- **本地 workspace 文件全部保留**：归档只变更实体状态，不删除、不移动任何 workspace 文件；
- **系统不自动归档**：任何情况下无自动归档路径——无静默判据、无定时任务、无生命周期钩子会触发归档；归档永远是显式人工动作。

### 6.2 ProjectCreate 工具（Nebula 编排工具）

- **背景**：沙箱已默认激活，所有任务都走 ProjectCreate；目标架构没有 delegate——Nebula 的编排一律以 project 为载体；
- **path 已知**（Nebula 自己知道，或用户在任务文本中告知）→ 直填直建，一步完成；
- **path 未知** → ProjectCreate 弹出 AskUserQuestion 式交互面板，由用户现场选择项目路径，选择结果回填后建 project；
- 实质 = ProjectCreate 的交互增强（未知路径分支），不改变既有直建路径。

### 6.3 「无 delegate、全走 Project」影响分析

- **对 Nebula 使用纪律**：编排动作全部改为 **Task(project=…) / ProjectCreate**——新工作一律 Task 指定 project，project 不存在则先 ProjectCreate（路径未知走 §6.2 交互面板）；Mail 仅剩两类合法用途：① 过渡期存续 team 的任务承接（该 team 归档即止）；② Mail(旧 team 名) fallback 安全网（DP7-a，零改动）。Nebula 不再用 Mail 触发 project（裁定 3，§3.1 语义③）。
- **对剩余 standalone agent（Explorer / Coder / design-engineer）过渡安排**：无 delegate 后，三个全局定义不再有编排承载场景——蒸馏先行（skill/plugin 用户层落地，节点 skill 参数即可引用）、定义归档统一对齐 §1.4 处置与 §5 M4 时点（硬依赖 2b/2c 终态）：Explorer → explorer-toolkit plugin；design-engineer → design-spec plugin；Coder → 工程纪律并入 general 模版「工程交付」节 + dev-toolkit skill（DP1-a）。

---

## 附：自检

- [x] 六章齐备（v2）：§1 清单与优先级 / §2 分发器自主建图+双例 / §3 调度切换（路由+QA 直触+决策点）/ §4 退役清单 / §5 里程碑 / §6 新机制
- [x] 与【phase2】零矛盾：三定义收敛（Coder 处置调和见 §1.4+决策点 1）、专业化走 plugin 不走改提示词（deck 家族处置即实证）、按阶段取代时序（M5 依赖 2d 对齐 G.6）；两处显式修正（nebflow-rust 直接归档 / 3 team 提前收尾）均标注依据且属任务书授权范围
- [x] 机制细节全部引用参考文档（【v2】【引擎演进】【图视图】【phase2】）不复述；判定表格一行理由；无行号级细节；全文无占位符
- [x] 现状数据全部实核（2026-09-03）：4 project.json / 9 team 成员与 rules 行数 / 9 flow 拓扑（flow.json 实读）/ qa 直触协议原文
- [x] 主仓零写入；唯一产出 = 本文档
- [x] v2 五条裁定逐条有落点：裁定 1→§3.3（八 DP 全部落定+选项表存档）；裁定 2→§1.3（5 flow 二选一重判）+§2（原则段+可用机制参考，模板/配方表述清零）+§4.3 判据 2+§5 M1/M2；裁定 3→§3.1（T0/T2/语义③）+§2.1 行 2/行 8+§2.3/§2.4 触发改 Task+§4.1 Mail 路由行；裁定 4→§6（三小节）；裁定 5→§1.2/§1.5（存续终态即迁）+§3.3 DP2/DP3 结论+§5 M1/M3（时间线重排）
- [x] v2 显式偏离【phase2】一处（Mail(→project) 兼容入口删除）已标注作者裁定依据；DP5-a（voice-recognition-test 静默归档——无存续任务，非迁移对象）与 v2 全部迁移口径（有存续任务者终态即迁、不等活动期）并存不冲突
- [x] v2 复检：全文无占位符；无预定模板/配方库表述残留（模板字样仅存于历史存档引用与 general agent 提示词模版等非拓扑语义处）
