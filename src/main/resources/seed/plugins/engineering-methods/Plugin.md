# engineering-methods — 插件使用指导（Plugin.md）

> 本文件是 engineering-methods 插件的**包根使用指导件**（Plugin.md）：装载器不注入本文件、不参与选配；维护者与按需 Read 的节点/用户以此为包内地图（用户＋模型可见的按需 Read 面）。选配判定只看 plugin.json 的 description；作业指令在各 `skills/<skill>/SKILL.md`（spawn 时全文注入）。**本文件是导览不是手册：与 SKILL.md 不一致时以 SKILL.md 为准并回改本文。** 文内路径均为**自包根的文件相对形式**（不使用 `${SKILL_DIR}`，该替换仅发生在 SKILL.md 注入文本）。

## 1. 速览

engineering-methods 是工程方法论包：节点获得两块「怎么想清楚、怎么查证」的纪律——需求拆解与技术选型的双文档交付纪律、多视角可验证的并行调研纪律。内含 2 个 skill：

- `skills/grill/`——系统架构设计：需求澄清（模糊必问不猜）、技术调研（四维对比）、trade-off 选型（只推荐一个方案不给对比是失职）、响应分级（快速咨询/局部分析/完整架构）、双文档交付（人类摘要 `_ARCH_SUMMARY.md` ＋ 实施规格书 `_ARCH_SPEC.md`）。
- `skills/parallel-research/`——并行调研纪律：正交拆题（子题互斥覆盖完整）、来源强制（每论断带链接、≥3 独立来源、一手来源优先）、逐轨核验（核验者亲自重访来源，VERIFIED/FAILED/UNCHECKED 逐条定性）、双轨综合（FAILED 剔除、UNCHECKED 标注、来源清单全覆盖）。

**包纪律一句话**：本包是「怎么选题、怎么对比、怎么查证」的方法论面——不写生产代码（示例代码、接口定义、类型签名除外）；已定方案的执行验证与交付把关不在本包（另配 `nebflow-qa` 插件）。

## 2. 安装与启用

- **安装**：本包属 Nebflow seed 官方分发，随实例 seed mirror 自动下发，无需手工安装。
- **启用/选配**：装载为「在位即生效」；分发器按 description 触发词选配（需求拆解、技术调研、方案对比、trade-off 选型、架构设计、并行调研、文献调研、事实核查等），派发时两份 SKILL.md 全文注入节点。
- **停用**：面板「更多 → 封禁该插件」/ `POST /api/plugins/engineering-methods/revoke` / `nebflow plugin revoke engineering-methods`。
- **内容变更**：包内任何字节改动都会漂移 digest，面板把该包标为「内容已变更」（可见性提示，不拦截装载；新内容对新派发即刻生效）。

## 3. 目录结构

```
engineering-methods/            （包根）
  plugin.json
  Plugin.md                     ← 本文件（包根使用指导件：速览 / 安装启用 / 分工导览 / 总目录 / 决策树）
  skills/
    grill/SKILL.md              ← 系统架构设计：需求拆解·技术调研·trade-off·双文档交付（注入面）
    parallel-research/SKILL.md  ← 并行调研纪律：正交拆题·来源强制·逐轨核验·双轨综合（注入面）
```

纯知识包：无 references/、assets/、scripts/，无 mcp.json——每个 skill 的全部口径都在其 SKILL.md 单文件内。

## 4. 包级核心用法（两块方法论怎么配合）

> 全量口径以注入的两份 SKILL.md 为准；本节是分工速查。

| 任务信号 | 用哪个 skill | 关键纪律（速查） |
|---|---|---|
| 需求拆解、技术选型、架构设计、方案对比 | grill | 需求模糊先提问不猜；关键选型给 2-3 方案＋trade-off 等用户选择；每方案优点与代价并陈；完整架构＝双文档交付（`_ARCH_SUMMARY.md` 讲「为什么」给人类，`_ARCH_SPEC.md` 代码优先、无歧义、可直接实施给工程 Agent） |
| 多视角调研、文献调研、事实核查（结论需可验证来源） | parallel-research | 正交拆题（合起来覆盖、互不重叠、每子题一句范围声明）；每条论断必须带来源链接，≥3 独立来源、一手优先；核验者必须亲自重访来源（可达性/内容匹配/一致性）；综合只合并各轨与核验材料，不做新调研 |

- **两 skill 的衔接**：grill 的调研阶段（搜索文档、博客、开源项目）遇到「结论需要可验证来源支撑」的深度调研时，按 parallel-research 的流程升级执行——拆题 → 并行深挖 → barrier 核验 → 综合成文；轻量对比（快速咨询/局部分析级）留在 grill 内直接完成。
- **交付形态**：grill 表格优先、代码即文档、架构图用 graphviz/mermaid 等专业工具生成落盘（禁 ASCII 画图）；parallel-research 报告＝Executive summary → 主题章节 → Confidence & gaps → Sources（幸存论断逐条保留来源链接）。
- **与其他包的分工**：代码库现状探索与实施方案设计另配 `explorer-toolkit`；执行验证与交付把关另配 `nebflow-qa`。

## 5. 各技能总目录

| skill 路径（自 skills/） | 一句话括注 |
|---|---|
| grill/SKILL.md | 系统架构设计：需求澄清四场景、调研四维、响应三级、双文档交付（人类摘要＋实施规格书）、输出风格与禁忌 |
| parallel-research/SKILL.md | 并行调研纪律：正交拆题、每轨产出结构（Findings/Interpretation/Gaps）、逐轨核验三查、双轨综合成文规范 |

## 6. 选 skill 决策树

- 任务是**定方向**（需求拆解、技术选型、架构设计、方案对比）→ `grill`。
- 任务是**查证据**（多视角、来源可验证的深度调研）→ `parallel-research`（单节点轻量查证不必用本纪律）。
- 两者常连用：选型任务＝grill 出对比框架与决策结构 ＋ parallel-research 出可验证证据链。
- 已摸清现状、要产出实施方案与验收条件 → 不进本包：另配 `explorer-toolkit`。
- 执行验证与交付把关（测试策略、验收方法论）→ 不进本包：另配 `nebflow-qa`。

## 7. 共享件专节

本包无 assets/、scripts/、references/ 共享件——全部内容承载于两个 SKILL.md；包级资产面为空是本包的合法形态（skills 非空即满足协议）。若未来某 skill 深内容超载，按体例下沉该 skill 的 `references/`，并同步 §3 目录结构与 §5 总目录。

## 8. 扩展位（未来候选）

| 候选项 | 前置条件 | 备注 |
|---|---|---|
| 新方法论 skill（评审/复盘/决策记录等域） | 该域纪律在生产中稳定沉淀后再立 skill | 先入本表，落地后迁出并入 §5 |

> 只登记候选与前置条件；候选落地后迁出本表、入 §5 总目录。

## 9. 维护约定

- **方法论条目更新**：SKILL.md 内的纪律条目（如 grill 的响应分级、parallel-research 的核验覆盖线）随生产观测修订；更新时核对未推翻同文件内其余条目，避免新旧结论并存。
- **跨包口径**：与 `explorer-toolkit`（选题/探索分工）、`nebflow-qa`（思考/验证分工）的边界若调整，两侧同步。
- **本文件定位**：Plugin.md 是按需 Read 面——装载器不注入；SKILL.md 是注入面权威，两者不一致时以 SKILL.md 为准并回改本文。
