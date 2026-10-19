# backend-dev — 插件使用指导（Plugin.md）

> 本文件是 backend-dev 插件的**包根使用指导件**（Plugin.md）：**插件分配时注入给 agent 的提示词 = 本文件内容**（作者令 2026-10-21「plugin.md = 插件必要文件机制」批；SKILL.md 回归按需查阅件——面板胶囊点击打开，不再随分配整文注入）。选配判定仍看 plugin.json 的 description；逐 skill 作业指令见各 `skills/<skill>/SKILL.md`（按需查阅）。**本文件是导览不是手册：与 SKILL.md 不一致时以 SKILL.md 为准并回改本文。** 文内路径均为**自包根的文件相对形式**（不使用 `${SKILL_DIR}`）。

## 1. 速览

backend-dev 是后端开发域知识包：节点获得写/改 Scala 后端代码的技术栈与纪律、prompt cache 命中率优化方法、工具系统设计纪律三块域知识。内含 3 个 skill：

- `skills/nebflow-backend/`——Scala 后端开发域知识：技术栈与架构铁律（Actor 与 IO 分层不互渗）、编码规范、sbt 编译测试实践、worktree 纪律、交付口径。
- `skills/nebflow-cache/`——prompt cache 命中率优化域知识：指标定义与数据源、已知事实沉淀、排查方法、system prompt 共享前缀维护纪律、保活与冷启动策略。
- `skills/nebflow-tool-dev/`——工具系统域知识：工具管线与权限模型、内置与外部脚本工具架构、六条设计原则、新建与优化工具流程、代码安全。

**包纪律一句话**：本包是「怎么写／怎么优化／怎么设计」的域知识面——「怎么验证」不在本包（验证域另配 `nebflow-qa` 插件）；三块知识各自独立成 skill，按任务域挂用，不要求齐挂。

## 2. 安装与启用

- **安装**：本包不属 seed 官方分发，走**手工安装**：自插件仓获取整个包目录，拷入实例的插件目录即落位生效（拷包时连同本文件一起带走）。
- **启用/选配**：装载为「在位即生效」；分发器按 description 触发词选配（写/改 Scala 后端代码、调 API 与 flow engine、优化 prompt cache 命中率、排查 sbt 构建问题、设计/修改工具、工具权限与 schema 等），派发时三份 SKILL.md 全文注入节点。
- **停用**：面板「更多 → 封禁该插件」/ `POST /api/plugins/backend-dev/revoke` / `nebflow plugin revoke backend-dev`。
- **内容变更**：包内任何字节改动都会漂移 digest，面板把该包标为「内容已变更」（可见性提示，不拦截装载；新内容对新派发即刻生效）。

## 3. 目录结构

```
backend-dev/             （包根）
  plugin.json
  Plugin.md              ← 本文件（包根使用指导件：速览 / 安装启用 / 用法导览 / 技能总目录）
  skills/
    nebflow-backend/     ← Scala 后端开发域知识（注入面）
      SKILL.md
    nebflow-cache/       ← prompt cache 命中率优化域知识（注入面）
      SKILL.md
    nebflow-tool-dev/    ← 工具系统域知识（注入面）
      SKILL.md
```

纯知识包：无 references/、assets/、scripts/，无 mcp.json——每个 skill 的全部口径都在其 SKILL.md 单文件内。

## 4. 包级核心用法（三块域知识怎么配合）

> 全量口径以注入的各 `SKILL.md` 为准；本节是跨 skill 的分工速查。

| 任务信号 | 用哪个 skill | 关键纪律（速查） |
|---|---|---|
| 实现/修复/重构后端代码、排查构建 | nebflow-backend | 先设计再编码；最小改动、向后兼容；Actor 与 IO 分层不互渗；并行任务各走独立 worktree，不自行 merge/push |
| 命中率下降排查、cache 指标分析、system prompt 前缀改动评估 | nebflow-cache | 先量化再动手；任何前缀改动都使共享缓存失效，改前必读；改动前留基线快照、落地后对比验证、无效即回滚 |
| 设计/新建/优化/调试工具（内置或外部脚本工具）、工具权限与 schema | nebflow-tool-dev | 新建前先查既有工具能否扩展；六条设计原则（最小参数面 / 可描述错误 / 结构化结果 / 向后兼容 / schema 即文档 / 按角色分化先走定义层） |

- **与验证的分工**：本包管「怎么写」；测试与验收方法论归 `nebflow-qa` 插件（如 verification-rigor）。交付自测完成后交 QA 验收，不自行合并。
- **改动联动**：改 system prompt 前缀、改工具 schema/description 这类「面广」的改动，动手前先读对应 skill 的代价条款（cache 失效面 / 向后兼容要求）。

## 5. 各技能总目录

| skill 路径（自 skills/） | 一句话括注 |
|---|---|
| nebflow-backend/SKILL.md | Scala 后端开发域知识：技术栈、Actor/IO 分层铁律、编码规范、sbt 编译测试实践、worktree 纪律、交付口径 |
| nebflow-cache/SKILL.md | prompt cache 命中率优化：指标定义、数据源、已知事实沉淀、排查方法、共享前缀维护与保活/冷启动策略 |
| nebflow-tool-dev/SKILL.md | 工具系统域知识：工具管线与权限模型、内置/外部（ScriptTool）架构、六条设计原则、新建/优化工具流程、代码安全 |

## 6. 选 skill 决策树

- 任务落在**后端代码本身**（实现、修复、重构、测试编写、构建排查）→ `nebflow-backend`。
- 任务落在**成本与性能面**（cache 命中率、token 消耗、保活/冷启动策略、前缀改动评估）→ `nebflow-cache`。
- 任务落在**工具面**（新工具、改工具、工具权限/schema、工具失败排查）→ `nebflow-tool-dev`。
- 任务是**验证/验收**（测试策略、QA 验收方法论）→ 不在本包：另配 `nebflow-qa`。
- 三者常连用：一次后端改动可同时触发 nebflow-backend（怎么改）与 nebflow-tool-dev（被改的工具面要不要动）或 nebflow-cache（前缀/token 面有没有动）。

## 7. 共享件专节

本包无 assets/、scripts/、references/ 共享件——全部内容承载于三个 SKILL.md；包级资产面为空是本包的合法形态（skills 非空即满足协议）。若未来某 skill 深内容超载，按体例下沉该 skill 的 `references/`，并同步 §3 目录结构与 §5 总目录。

## 8. 扩展位（未来候选）

| 候选项 | 前置条件 | 备注 |
|---|---|---|
| 新域知识 skill（部署/发布流程、数据迁移等） | 该域知识在生产中稳定沉淀后再立 skill | 先入本表，落地后迁出并入 §5 |

> 只登记候选与前置条件；候选落地后迁出本表、入 §5 总目录。

## 9. 维护约定

- **域知识更新**：SKILL.md 内沉淀的结论性条目（如 cache 的已知事实、tool-dev 的设计原则）随生产观测更新；更新时核对其余条目未被推翻，避免同一 skill 内新旧结论并存。
- **跨包口径**：与 `nebflow-qa` 的「写/验证」分工边界若调整，两侧同步。
- **本文件定位**：Plugin.md 是按需 Read 面——装载器不注入；SKILL.md 是注入面权威，两者不一致时以 SKILL.md 为准并回改本文。
