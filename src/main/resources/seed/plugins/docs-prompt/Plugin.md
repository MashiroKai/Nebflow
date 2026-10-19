# docs-prompt — 插件使用指导（Plugin.md）

> 本文件是 docs-prompt 插件的**包根使用指导件**（Plugin.md）：**插件分配时注入给 agent 的提示词 = 本文件内容**（作者令 2026-10-21「plugin.md = 插件必要文件机制」批；SKILL.md 回归按需查阅件——面板胶囊点击打开，不再随分配整文注入）。选配判定仍看 plugin.json 的 description；逐 skill 作业指令见各 `skills/<skill>/SKILL.md`（按需查阅）。**本文件是导览不是手册：与 SKILL.md 不一致时以 SKILL.md 为准并回改本文。** 文内路径均为**自包根的文件相对形式**（不使用 `${SKILL_DIR}`）。

## 1. 速览

docs-prompt 是文档与提示词工程域知识包：节点获得两块方法纪律——技术文档的写作/双语/一致性与产出纪律，以及系统提示词/工具描述/agent 定义的设计与编辑纪律。内含 2 个 skill：

- `skills/nebflow-docs/`——技术文档域知识：写作规范（准确/简洁/结构化/示例驱动/不过度展开）、中英双语文档同步、导航结构维护、文档一致性联动（代码改了文档跟着改）、产出路径纪律、图文报告图片内嵌纪律。
- `skills/nebflow-prompt-engineering/`——提示词工程方法论：六核心原则（唯一解析/渐进披露/token 效率/结构化/行为性/示例优先）、系统提示词解剖结构、编辑既有提示词的纪律与反模式清单、工具描述写法（含按角色分化先走定义层）、agent 定义周期体检清单。

**包纪律一句话**：改文档先保「与代码一致」，改提示词先保「每句话只有一种解析」——两块都讲最小改动、示例驱动、before/after diff 交付；代码实现与交付验证不在本包（另配 `nebflow-backend-dev`、`nebflow-qa`）。

## 2. 安装与启用

- **安装**：本包不属 seed 官方分发，走**手工安装**：自插件仓获取整个包目录，拷入实例的插件目录即落位生效（拷包时连同本文件一起带走）。
- **启用/选配**：装载为「在位即生效」；分发器按 description 触发词选配（写或更新文档、维护 README/文档导航、中英双语同步、改系统提示词、优化工具描述、agent 定义体检等），派发时两份 SKILL.md 全文注入节点。
- **停用**：面板「更多 → 封禁该插件」/ `POST /api/plugins/docs-prompt/revoke` / `nebflow plugin revoke docs-prompt`。
- **内容变更**：包内任何字节改动都会漂移 digest，面板把该包标为「内容已变更」（可见性提示，不拦截装载；新内容对新派发即刻生效）。

## 3. 目录结构

```
docs-prompt/                            （包根）
  plugin.json
  Plugin.md                             ← 本文件（包根使用指导件：速览 / 安装启用 / 分工导览 / 总目录 / 决策树）
  skills/
    nebflow-docs/SKILL.md               ← 技术文档域知识（注入面）
    nebflow-prompt-engineering/SKILL.md ← 提示词工程方法论（注入面）
```

纯知识包：无 references/、assets/、scripts/，无 mcp.json——每个 skill 的全部口径都在其 SKILL.md 单文件内。

## 4. 包级核心用法（两块纪律怎么配合）

> 全量口径以注入的两份 SKILL.md 为准；本节是分工速查。

| 任务面 | 用哪个 skill | 关键纪律（速查） |
|---|---|---|
| 写/改文档、双语同步、文档导航 | nebflow-docs | 准确第一（与代码一致，不臆测）；一侧改了另一侧跟上；新增文档挂进导航、删除文档摘出条目 |
| 基础设施改动后的联动更新 | nebflow-docs | 提示词/记忆/README/工具 description 受影响部分全链同步——「代码改了，文档/提示词/记忆也要跟着改，不留过时内容」 |
| 设计/优化系统提示词 | nebflow-prompt-engineering | 六原则对表（每句话一种解析、渐进披露、token 效率、为解析而结构化、行为性、示例优先）；章节尺寸克制（Identity ≤2 行、核心规则 5-7 条） |
| 编辑既有提示词 | nebflow-prompt-engineering | 动手前通读全文、找最小改动；动词数最少化、保留既有结构；对表反模式清单（模糊限定词/矛盾规则/死指令/冗余/缺省值缺失） |
| 工具 description / agent 定义 | nebflow-prompt-engineering | 工具描述 ≤100 词、只写不显然的；多角色语义先问 schema/定义层能不能分化，不靠提示词兜；agent 定义周期体检出报告不强制 |

- **共同交付口径**：改提示词展示 before/after diff 并解释 WHY；文档任务列改动文件清单、标注重写/更新；提示词内容不用 emoji。
- **图文报告（人读件）图片内嵌**：图片一律图语法内嵌、禁路径清单冒充正文；权威口径在项目仓 AGENTS.md 同名节，skill 内为执行面副本——两处禁两套口径。

## 5. 各技能总目录

| skill 路径（自 skills/） | 一句话括注 |
|---|---|
| nebflow-docs/SKILL.md | 技术文档域知识：写作规范、双语同步、一致性联动、产出路径纪律、图文报告内嵌 |
| nebflow-prompt-engineering/SKILL.md | 提示词工程方法论：六核心原则、提示词解剖、编辑纪律与反模式、工具描述写法、周期体检 |

## 6. 选 skill 决策树

- 写/更新 **README、API 文档、架构指南、CHANGELOG、代码注释** → `nebflow-docs`。
- **中英双语同步**或文档导航结构调整 → `nebflow-docs`。
- 设计/优化 **system prompt**、重构提示词章节、降 token 消耗 → `nebflow-prompt-engineering`。
- 写/优化 **工具 description**、agent 定义（system.md/agent.json）体检 → `nebflow-prompt-engineering`。
- **代码实现** → 不在本包：另配 `nebflow-backend-dev`；**交付验证** → 另配 `nebflow-qa`。
- 两 skill 常连用：一次基础设施改动＝代码外的一切文本面（文档走 nebflow-docs，提示词与工具描述走 prompt-engineering）按联动清单同步。

## 7. 共享件专节

本包无 assets/、scripts/、references/ 共享件——全部内容承载于两个 SKILL.md；包级资产面为空是本包的合法形态（skills 非空即满足协议）。

## 8. 扩展位（未来候选）

| 候选项 | 前置条件 | 备注 |
|---|---|---|
| 提示词评测/回归方法 skill | 评测口径在生产中稳定沉淀后再立 skill | 先入本表，落地后迁出并入 §5 |

> 只登记候选与前置条件；候选落地后迁出本表、入 §5 总目录。

## 9. 维护约定

- **权威面同步**：图文报告图片内嵌口径的权威面在项目仓 AGENTS.md 同名节——改任一侧必须同步另一侧，禁两套口径；skill 内该节为执行面副本。
- **跨包口径**：与 `nebflow-backend-dev`（代码实现）、`nebflow-qa`（交付验证）的分工边界若调整，两侧同步。
- **方法论沉淀**：反模式清单、周期体检检查项随生产观测增删；增删时核对其余条目未被推翻。
- **本文件定位**：Plugin.md 是按需 Read 面——装载器不注入；SKILL.md 是注入面权威，两者不一致时以 SKILL.md 为准并回改本文。
