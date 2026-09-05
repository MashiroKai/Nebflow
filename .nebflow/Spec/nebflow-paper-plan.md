> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Nebflow 学术论文可行性评估与投稿方案（v2，按用户纠偏修订）

> 调研基准日 2026-08-16 · 纯调研产出，不涉及源码改动 · 关键事实带来源链接（§8）
> v2 修订：论文主线由"用户纠偏组织学习"改为**单一输入口架构**——纠偏闭环降级为主线的配套机制

---

## 0. 执行摘要

**结论：可行。论文主张一句话——"用户只与一个编排者对话；并行性是系统关注点，不是用户关注点"（The user talks to exactly one orchestrator; parallelism is a systems concern, not a UX concern）。**

三个判断：

1. **主张是经典的"打破权衡"系统论文叙事**。现有多智能体工具把用户推入两难：**多会话并行**（Claude Code 多窗口/Codex——上下文割裂、记忆不连贯、重复交代）或**单会话连贯**（朴素 chat/OpenHands 单 agent——无法并行、上下文爆炸）。Nebflow 用单一持久入口（Nebula）+ 系统侧并行机制（Delegate/Mail/Flow/Team）+ 两级记忆，同时拿到两头。这是 OS"单终端 + 后台进程/job control"级别的架构隐喻，审稿人 30 秒能懂。
2. **相关工作全部不在位**。9 个主流框架的"并行"都是**任务内**（run-and-done 的多 agent 流水线），没有一个是**工作流级持久单入口**；多会话方案（工业实践）无学术论文但有真实痛点。空白真实存在。
3. **推荐路线（已确认）**：ASE 2027 稳线（约 2027-04 截稿），约 8 个月从容做实验；期间 arXiv 技术报告先行（2026-09 前后，占坑+引用锚点）+ SWE-bench 参赛顺产外部基准数据。ICLR 2027（09-25）/FSE 2027（10-02）距今天 5.5-6.5 周，不作首投。

---

## 1. 相关工作扫描

### 1.1 用户交互模型对比（本论文的锐利对比表）

| 系统 | 论文/venue | 用户交互模型 | 任务级并行机制 | 跨任务上下文/记忆 | Nebflow 差异 |
|---|---|---|---|---|---|
| **AutoGen** (微软) | arXiv [2308.08155](https://arxiv.org/abs/2308.08155)（2023 技术报告） | 用户可作对话方之一加入群聊 | 会话内多 agent 对话 | ✗ run-and-done | 并行在"任务内"；用户入口不唯一、不持久 |
| **MetaGPT** | **ICLR 2024 Oral**，arXiv [2308.00352](https://arxiv.org/abs/2308.00352) | 一次性需求输入 | SOP 阶段流水线（任务内） | ✗ 单次 run 生命周期 | 无持久工作流入口；SOP 预固化不进化 |
| **OpenHands** | **ICLR 2025**，arXiv [2407.16741](https://arxiv.org/abs/2407.16741)（908 引用） | 单会话单 agent | ✗ 无任务级并行 | △ condenser 压缩维持单会话 | 连贯靠压缩、牺牲并行；**平台论文先例（OpenHands 模式）** |
| **CAMEL** | **NeurIPS 2023**，arXiv [2303.17760](https://arxiv.org/abs/2303.17760) | 无用户角色（自主协作研究） | 角色扮演双人对话 | ✗ | 机制研究非工程系统 |
| **ChatDev** | **ACL 2024**（[2024.acl-long.810](https://aclanthology.org/2024.acl-long.810/)，最具影响力 #8） | 一次性需求 | chat chain 瀑布（任务内） | ✗ | 同 MetaGPT |
| **AgentVerse** | **ICLR 2024** | 一次性任务 | 动态组队（任务内） | ✗ | 组队是临时编制非持久组织 |
| **AFlow** | **ICLR 2025 Oral**，arXiv [2410.10762](https://arxiv.org/abs/2410.10762) | 无用户入口概念 | MCTS 搜索的工作流（任务内） | ✗ | 自动**搜索**流程 vs Nebflow 从使用与纠偏中**生长**流程 |
| **CrewAI / LangGraph** | 无代表性论文（工业库） | 库 API，开发者组装 | 图执行/crew 执行 | ✗ | 无用户入口抽象，学术空白 |
| **Claude Code / Codex / Cursor**（工业对照，无论文） | — | **多会话手动管理** + 会话内 subagent | 用户开 N 个窗口自己调度 | △ CLAUDE.md/rules 是割裂补丁 | **痛点本体**：上下文割裂、记忆不连贯、用户当调度器。Nebflow 把调度还给系统 |

**读法**：第 3 列没有一行是"持久单一用户入口 + 系统侧工作流级并行"。要么并行是任务内的，要么根本没有用户级并行设计。

### 1.2 记忆/经验学习线（配套机制的威胁面）

| 工作 | venue | 与 Nebflow 的关系 |
|---|---|---|
| **AWM** (CMU) | arXiv [2409.07429](https://arxiv.org/abs/2409.07429) | 从**任务轨迹**归纳 workflow；Nebflow 的沉淀信号是**用户裁决+复用频次**——信号源不同，可作对比 |
| **Voyager** | arXiv [2305.16291](https://arxiv.org/abs/2305.16291) | 技能库鼻祖（环境反馈驱动、具身域） |
| **ExpeL** | arXiv [2308.10144](https://arxiv.org/abs/2308.10144) | 任务成败提炼 insights，无用户信号 |
| **LEGOMem** (微软) | arXiv [2510.04851](https://arxiv.org/abs/2510.04851) | 多 agent 程序性记忆，无用户信号无治理 |
| **个性化长期交互** | arXiv [2510.07925](https://arxiv.org/abs/2510.07925) | 概念最近（用户画像演化）但 QA 场景+5 天试点，非工程编排 |
| **综述锚点** | 记忆综述 [2404.13501](https://arxiv.org/abs/2404.13501)、自进化综述 [2507.21046](https://arxiv.org/abs/2507.21046)、"下半场"记忆综述 [2602.06052](https://arxiv.org/abs/2602.06052)（2026，点名 agentic coding 的长程用户依赖场景） | 综述公认"用户依赖+长程+连贯"是痛点且工程化解法稀缺——引言弹药 |

### 1.3 定位图

![定位图：权衡打破](assets/nebflow-positioning.svg)

---

## 2. Novelty 提炼（1 核心 + 2 支撑 + 1 工程）

### N1（核心贡献）：单入口编排架构（Single-Entry Orchestration）

**机制链**（源码依据）：
1. **唯一持久入口**：会话默认解析到 Nebula，无其他备选（`WebSocketRoutes.scala:73-82` ensureRootAgent→nebulaFallback）——用户在架构上不可能绕过编排者
2. **系统侧并行**：Delegate（任务下发）、Mail（互发消息）、Flow（DAG 并行节点）、Team（持久项目组织）四机制让任务在编排层并行，用户的单一上下文永不被任务细节淹没——Nebula 只回填结论与需裁决事项
3. **连贯性保障**：两级记忆（User.md 全局 / Agent 级）注入 systemStable + save/compact 两阶段压缩（`CompactService.scala`）——跨任务、跨会话的记忆连贯是机制产物而非用户自律
4. **单通道信号增值**（原核心主张降级为推论）：正因为只有一个通道，通道内的用户裁决必须被机制化保全——[USER-RULING] 契约（当轮强信号 Mail + 攒批弱信号，`CompactService.scala:158-205`）、Evidence 逐字溯源审计（`SkillService.scala:186-193`）、2+ 次频次门控沉淀为 skill、四层记忆路由

**给审稿人的一句话**：*"Existing tools force a trade-off: parallel work via multiple user-managed sessions (context fragmentation) or coherence via a single session (no parallelism). Nebflow's single-entry architecture resolves this trade-off by moving parallelism into the orchestration layer, with mechanism-guaranteed memory coherence."*

**攻击与防御**：
- *"Claude Code 也有 subagent/teams"* → 会话内 subagent 是任务内并行；多窗口是用户当调度器；CLAUDE.md 是无治理的补丁。且它无论文——你做的是把这个设计空间学术化并给出评测
- *"就是个 master-worker"* → master-worker 是拓扑；这里的主张是**用户交互模型**（UX-level single entry）+ 治理语义（验收/仲裁/沉淀），并给出权衡的量化评测
- *"单入口会不会成瓶颈"* → 正是实验要回答的：E2b 测 Nebula 上下文增长曲线 + 两阶段压缩的控制；E1 证明质量不降

### N2（支撑）：治理导向的组织模型——并行的"操作系统"

单入口能成立，是因为任务分出去能被可靠收回：Manager=契约守卫+能力园丁+兜底仲裁（非消息路由器），成员互 Mail 自组织（完成代码自己找 qa 验证），Flow 结构化契约（outputs/slots/strictVerdict）+ check-fix loop + 无墙钟超时（actor 自愈）。对照：MetaGPT/ChatDev 角色是 prompt 预设瀑布；AutoGen 管理员是路由器。表述：**"from topology to governance"**——治理是单入口架构的执行机构。

### N3（工程卖点，辅助）：本地优先 + 模型无关

`~/.nebflow` 全本地、NebLink P2P/Relay、GLM/DeepSeek/Qwen 生态。论文里作设计约束一节；是中文 venue 与国产生态叙事的主打点。

**贡献列表草案**：
1. 单入口编排架构：将工作流级并行从用户侧移入系统侧的机制设计，消除多会话上下文割裂
2. 支撑该架构的治理机制：Manager 治理角色、结构化 Flow 契约、[USER-RULING] 纠偏保全与频次门控技能沉淀
3. 开源实现（Scala 3/Pekko → Rust 迁移中，749 测试）与两类实证：权衡量化（E2）+ 外部基准（E1）

---

## 3. 论文类型：系统论文（Systems Paper）

"打破权衡 + 机制 + 量化评测"是系统论文金标准模板；Nebflow 的主张恰好是这个形状。

| 类型 | 契合 | 判断 |
|---|---|---|
| **系统论文**（架构+机制+评测） | ★★★★★ | **主案**。OpenHands（ICLR 2025）证明平台型可上顶会；SE venue 系统文先例：AutoCodeRover（ISSTA 2024）、Agentless（ICSE 2025） |
| 实证研究 | ★★★☆☆ | E2 本身是实证，可作系统论文内的实验章节；单独抽出来撑不起全文 |
| Tool Demo / 短文 | ★★★★☆ | 副线占位（ICSE 2027 Demonstrations / ASE 2027 Tool，截稿约 2026-10~12，需官网确认） |
| Vision / position | ★★☆☆☆ | 有实现有系统，浪费 |
| 软件学报（中文） | ★★★☆☆ | 姊妹篇换角度（见 §7） |

---

## 4. 投稿目标与时间线（主线已确认：ASE 2027 稳线）

![投稿窗口时间线](assets/nebflow-venue-timeline.svg)

| Venue | 截稿（AoE） | 契合 | 备注 |
|---|---|---|---|
| ICLR 2027 | 摘要 09-18 / 全文 **09-25**（[官网](https://www.iclr.cc/Conferences/2027/AuthorGuidelines)） | 中 | 5.5 周来不及；OpenHands 先例 venue，留作奇迹备选 |
| FSE 2027（深圳） | **2026-10-02** | 高 | 6.5 周，赌性大；系统叙事对 FSE 很配，若 9 月 arXiv+部分实验可考虑 |
| AAMAS 2027（河内） | 10-02 / **10-09** | 中 | 组织/编排叙事契合，但 v2 主线（用户交互模型）偏 SE/HCI 语境 |
| WWW 2027 | 2026-10-18 | 中 | 需重包装，非首选 |
| **COLM 2027** | 约 2027-03（估） | 中高 | 若想把"权衡+学习"讲给 ML 社区 |
| **ASE 2027（主案）** | 约 2027-04（估） | **★★★★★** | SE venue 对"系统+实证"最友好；8 个月从容；与 SWE-bench 参赛时间线咬合 |
| ICSE 2028（接力） | 约 2027-07（估，ICSE 2027 已于 07-08 截稿） | 高 | ASE 若被拒的最优接力 |
| TOSEM/TSE（期刊） | 滚动 | 高 | 大改稳态出口 |
| 软件学报 / JCST | 滚动 | 中 | 国内认定/基金结题 |

**已确认路线**：arXiv（2026-09，占坑）→ 实验三阶段（09-12 基建 / 12-02 跑分 / 02-03 分析）→ **ASE 2027 投稿（约 2027-04）** → 被拒则 ICSE 2028（约 2027-07）。

---

## 5. 三位一体：框架 × Benchmark × 论文

### 5.1 联动机制

```
SWE-bench 参赛 harness (E1) ──→ 榜单成绩 = 论文 Table 1（质量底线：并行编排不牺牲任务质量）
       │
       ├── 权衡实验 (E2)    ──→ 论文核心图：并行性×连贯性双指标（独有）
       └── 消融 (E3)        ──→ 单入口各机制的贡献分解
框架开源 ←──── 引用/复现 ←──── 论文（arXiv 锚点）←──── 榜单曝光引流
```

### 5.2 实验设计（v2 重写）

| 实验 | 内容 | 指标 | 说明 |
|---|---|---|---|
| **E1 外部基准** | SWE-bench Verified 500（或 [SWE-bench Pro](https://arxiv.org/abs/2509.16941) 公开 731）：Nebflow 编排 vs 同模型单 agent baseline | resolve rate、token 成本、墙钟 | 证明单入口并行**不降质量**；harness 与参赛共用 |
| **E2a 连贯性实验（独有·核心图）** | 模拟用户工作日：10-20 个任务跨 3-4 个项目线，任务间存在知识依赖（B 需要 A 的结论/用户在 A 给过的纠偏）。三条件对比：**(a)** Nebflow 单入口全机制 **(b)** 单入口但关并行机制（串行 delegate）**(c)** 多会话模拟（每任务独立会话+CLAUDE.md 式共享文件，即工业 baseline） | 跨任务知识可用率（B 正确使用 A 产出/纠偏的比例，LLM 裁判+人工抽检）、纠偏重犯率、用户重复交代次数（token 重复注入量） | **这张图证明权衡被打破**：(c) 高并行低连贯、(b) 高连贯低并行、(a) 两头都拿 |
| **E2b 并行效率** | 同上场景测墙钟时间、任务吞吐、Nebula 上下文增长曲线（两阶段压缩效果） | 吞吐、压缩比、上下文规模 | 回答"单入口是否成瓶颈" |
| **E3 消融** | 逐个关闭：两级记忆 / [USER-RULING] 沉淀 / Flow 契约 / Manager 治理 | 各指标降级幅度 | 机制贡献分解，防"堆料"批评 |
| **E4 系统报告** | NebLink 跨设备开销、actor 自愈（无墙钟超时）稳定性 | 延迟、故障恢复 | 系统论文的工程完整性章节 |

E2 设计要点（预答审稿人）：任务序列双来源（自构+真实 issue 历史抽取）；知识依赖显式标注；条件 (c) 的 CLAUDE.md 写入规则做公平调优；LLM 裁判需报告与人工标注的一致性。

---

## 6. 工作量评估与路线图

**总估 4-6 人月**（1 名熟练研究者 3 个月可出投稿包；建议配 1 名学生执行实验）：

| 阶段 | 时间 | 任务 | 产出 |
|---|---|---|---|
| P0 | 2026.08-09 | 架构文档体系化（现有 docs/ 素材充足：design/memory-system.md、inter-agent-mail.md、flow-engine.md 等）、系统图 6 张、arXiv 技术报告 | **arXiv 技术报告**（占坑；README 挂链接引流） |
| P1 | 2026.09-12 | E1 harness 接 SWE-bench + 报名参赛；E2 三条件实验平台搭建（任务序列、知识依赖标注、LLM 裁判） | 榜单首跑；E2 平台 |
| P2 | 2026.12-2027.02 | E2/E3 全量跑分+统计（多次运行方差、效应量）；论文正文 | 完整稿 |
| P3 | 2027.03 | 内审打磨（E2a 双指标图是封面图，花最多时间）；**投 ASE 2027** | 投稿 |
| P4 | 2027.05-07 | 审稿意见大改；ICSE 2028 接力 | 备选 |

---

## 7. 现实建议

1. **作者身份**：开源 maintainer 主笔+实验室挂靠（OpenHands 模式：工程作者+学术作者混排，社区贡献者按实质列入或致谢）。E2 涉及模拟用户日志，注意数据授权声明；若加真人用户研究需 IRB。
2. **arXiv 先行**：AAMAS/ICSE/FSE 政策均明确 arXiv 不算一稿多投。N1 的设计空间正被工业界（Claude Code 的 teams/memory 演进）快速逼近，**学术窗口约 12-18 个月，占坑时间戳重要**。
3. **中文 venue**：不建议同内容双发。姊妹篇换角度：《面向国产大模型的本地优先单入口智能体编排系统与实践》（软件学报，工程实践+生态视角）。
4. **写作定位**：全文叙事是"**一个被打破的权衡**"，不是"一个很特殊的架构"。第一节就必须出现对比表（§1.1）：多会话割裂 vs 单会话无法并行 vs Nebflow。系统隐喻（单终端+后台进程/job control）用于 abstract 第一段。
5. **NIMA 经验复用**：统计规范直接迁移；E2a 是全文脸面，投入最多打磨时间。

---

## 8. 参考来源

**框架论文**
- AutoGen: https://arxiv.org/abs/2308.08155 · MetaGPT: https://arxiv.org/abs/2308.00352（ICLR 2024 Oral）
- OpenHands: https://arxiv.org/abs/2407.16741（ICLR 2025）· CAMEL: https://arxiv.org/abs/2303.17760（NeurIPS 2023）
- ChatDev: https://aclanthology.org/2024.acl-long.810/（ACL 2024）· AgentVerse: https://arxiv.org/abs/2308.10848（ICLR 2024）
- AFlow: https://arxiv.org/abs/2410.10762（ICLR 2025 Oral）

**记忆/经验学习**
- Voyager: https://arxiv.org/abs/2305.16291 · ExpeL: https://arxiv.org/abs/2308.10144 · AWM: https://arxiv.org/abs/2409.07429
- LEGOMem: https://arxiv.org/abs/2510.04851 · 个性化: https://arxiv.org/abs/2510.07925
- 综述: https://arxiv.org/abs/2404.13501 · https://arxiv.org/abs/2507.21046 · https://arxiv.org/abs/2602.06052

**Benchmark**
- SWE-bench Pro: https://arxiv.org/abs/2509.16941 · https://scale.com/blog/swe-bench-pro

**Venue 截稿**
- ICLR 2027: https://www.iclr.cc/Conferences/2027/AuthorGuidelines（09-18/09-25）
- FSE 2027: https://www.getpaperpilot.com/deadlines/fse-2027.html（10-02）
- AAMAS 2027: https://openreview.net/group?id=ifaamas.org/AAMAS/2027/Conference（10-02/10-09）
- ICSE 2027 已截稿: https://icse2027.hotcrp.com/deadlines · EuroSys 2027: https://2027.eurosys.org（09-24）· OSDI 2027: https://www.usenix.org/conference/osdi27

**Nebflow 源码依据**（本机）
- `src/main/scala/nebflow/gateway/WebSocketRoutes.scala:49-90`（rootAgents 会话→Nebula 唯一入口与 fallback）
- `src/main/scala/nebflow/core/compact/CompactService.scala:145-216`（两级记忆、[USER-RULING] 契约、频次门控沉淀、Manager/Worker 分角色）
- `src/main/scala/nebflow/core/skill/SkillService.scala:186-193`（Evidence 逐字溯源与终审抽查）
- `docs/design/memory-system.md`、`docs/design/inter-agent-mail.md`、`docs/design/flow-engine.md`

---

*标"约"的截止日期为按惯例估计，投稿前需官网二次确认；v2 已按用户 2026-08-16 纠偏修订主线（单入口架构），纠偏闭环降级为 N1 的第 4 项机制。*
