# stand-dev — 插件使用指导（Plugin.md）

> 本文件是 stand-dev 插件的**包根使用指导件**（Plugin.md）：**插件分配时注入给 agent 的提示词 = 本文件内容**（作者令 2026-10-21「plugin.md = 插件必要文件机制」批；SKILL.md 回归按需查阅件——面板胶囊点击打开，不再随分配整文注入）。选配判定仍看 plugin.json 的 description；逐 skill 作业指令见各 references 深件（按需查阅）。**本文件是导览不是手册：与 SKILL.md / references 不一致时以 SKILL.md 与 references 为准并回改本文。** 文内路径均为**自包根的文件相对形式**（不使用 `${SKILL_DIR}`）。

## 1. 速览

stand-dev 是 **stand 本体开发统一知识包**（2026-10-31 作者令「开发插件统一批」：原 qa / frontend-dev / backend-dev / docs-prompt / pipelines / nebflow-plugin-creator 六包合并，能力零丢失）：节点获得 stand 全栈开发域的综合能力——验证怎么把关、前端怎么写、后端怎么写、引擎工程纪律（合并/发版/审查/文档/提示词/插件封装）。单 skill 目录结构，内部按面分**四章**：

- **qa 章**——`skills/stand-dev/references/qa/`（5 份）：前后端五维审核、隔离真实例冒烟、自验 harness、验红实证。
- **前端章**——`skills/stand-dev/references/frontend/`（3 份）：web/ 实现规范、用户视觉裁定铁律、UI/UX 范式案例库。
- **后端章**——`skills/stand-dev/references/backend/`（3 份）：Scala 技术栈与分层铁律、sbt 实践、prompt cache、工具系统设计。
- **引擎章**——`skills/stand-dev/references/engine/`（7 份）：合并/发版/三维审查、技术文档、提示词工程、插件封装与描述规范。

**包纪律一句话**：四章不混装——按任务域进对应章，章内 references 深件按需 Read；「写」（后端/前端章）与「验」（qa 章）与「闸」（引擎章 pipelines）是三道不同关卡，交付自测完成后交 QA 验收、审查前置再合并，不自行跳关。

## 2. 安装与启用

- **安装**：本包属 Nebflow seed 官方分发，随实例 seed mirror 自动下发（默认预装集成员），无需手工安装。
- **启用/选配**：装载为「在位即生效」；分发器按 description 触发词选配（改 stand 前端/后端、交付前验证、合并发版、写文档/提示词、封装插件等），分配时注入本文件（Plugin.md），SKILL.md 与 references 为按需查阅件。
- **停用**：面板「更多 → 封禁该插件」/ `POST /api/plugins/stand-dev/revoke` / `nebflow plugin revoke stand-dev`。
- **内容变更**：包内任何字节改动都会漂移 digest，面板把该包标为「内容已变更」（可见性提示，不拦截装载；新内容对新派发即刻生效）。

## 3. 目录结构

```
stand-dev/                              （包根）
  plugin.json
  Plugin.md                             ← 本文件（包根使用指导件）
  skills/
    stand-dev/                          ← 唯一 skill（四章总纲 SKILL.md = 按需查阅件）
      SKILL.md                          ← 章导览 / 核心指令摘要 / 决策树
      references/
        qa/         （5 份 .md）
        frontend/   （3 份 .md）
        backend/    （3 份 .md）
        engine/     （7 份 .md）
      scripts/
        validate_plugin.py              ← 插件包机械校验脚本（M1-M15 + C 组，三档 profile）
      assets/
        manifest.template.json          ← manifest 起步模板
        pluginmd.template.md            ← Plugin.md 起步模板
        skill-template/                 ← skill 目录骨架
```

## 4. 章导览（怎么用四章）

> 全量口径以 SKILL.md 与 references 为准；本节是跨章速查。

| 章 | 任务信号 | 关键判据（速查） | 深件 |
|---|---|---|---|
| qa | 交付前验证 / 合并前把关 / verify 节点 | 后端五维全覆盖（compile/test/验收逐项/质量/回归）；前端隔离真实例三步缺一不可、禁静态替身；修 bug 必做验红实证 | `references/qa/*.md` |
| 前端 | 改 `src/main/resources/web/` | 无构建 vanilla JS；CSS 禁裸 hex 一律 `var(--color-*)`；视觉冲突以 visual-style 铁律终裁 | `references/frontend/*.md` |
| 后端 | 改 `src/main/scala/` / 调 flow engine / 建工具 | Actor 与 IO 分层铁律；最小改动/向后兼容；system prompt 前缀改动前必读 nebflow-cache | `references/backend/*.md` |
| 引擎 | 合并 / 发版 / 审查 / 文档 / 提示词 / 封装插件 | 合并 `--no-ff` + go/no-go 三判据；tag 打在 release 分支 merge commit；review 三维评分 check-fix ≤3 轮；插件封装落盘即生效、自检后冻结 | `references/engine/*.md` |

- **关卡顺序**：写（前端/后端章）→ 自验（qa 章 frontend-verification）→ 交付 QA（qa 章 nebflow-qa-*）→ 审查（引擎章 review）→ 合并（引擎章 merge-pipeline）→ 需要发版时（引擎章 release-pipeline）。
- **红线**：宿主 gateway 端口保护 / PID 验身（qa 章）；不 push 除非明确指示（引擎章 merge-pipeline）；上传 gated 永不自动 publish（release-pipeline）；落盘后不得再写包内文件（plugin-packaging）。

## 5. references 总目录

| references 路径 | 来源（原包/skill） | 一句话括注 |
|---|---|---|
| `qa/nebflow-qa-backend.md` | qa/nebflow-qa-backend | Scala 后端五维审核：sbt compile/test/验收逐项/三维质量/回归 + 运行安全红线 |
| `qa/nebflow-qa-frontend.md` | qa/nebflow-qa-frontend | 前端合并回归：隔离真实例三步验证、资源可达性、禁替身、契约静态审核 |
| `qa/frontend-verification.md` | qa/frontend-verification | 实现侧自验 harness：wire 探针、契约夹具、Playwright 坑、分层验证 |
| `qa/isolated-smoke-verification.md` | qa/isolated-smoke-verification | 隔离实例冒烟：临时 home + 独占端口 + mock 路由、沙盒泄漏三信号 |
| `qa/verification-rigor.md` | qa/verification-rigor | 验红实证/变异安全/spec 载力/异步排障/字段改名四面验证 |
| `frontend/nebflow-frontend.md` | frontend-dev/nebflow-frontend | web/ 结构、Sapphire Glass 设计系统、硬约束、截图驱动验证流 |
| `frontend/visual-style.md` | frontend-dev/visual-style | 用户视觉裁定铁律：弹窗毛玻璃无 overlay、控件玻璃质感、中度字重 |
| `frontend/design-system.md` | frontend-dev/design-system | 功能类型→成熟范式→适配规则案例库（软参考，冲突以 visual-style 为准） |
| `backend/nebflow-backend.md` | backend-dev/nebflow-backend | Scala 3/actor/http4s 技术栈、Actor/IO 分层、编码规范、sbt、worktree 纪律 |
| `backend/nebflow-cache.md` | backend-dev/nebflow-cache | prompt cache 命中率：指标/数据源/间隔悬崖/共享前缀维护纪律 |
| `backend/nebflow-tool-dev.md` | backend-dev/nebflow-tool-dev | 工具管线/权限模型/六条设计原则/新建工具流程 |
| `engine/merge-pipeline.md` | pipelines/merge-pipeline | 分支四事实扫描→go/no-go→`--no-ff` 合并→报告，不 push |
| `engine/release-pipeline.md` | pipelines/release-pipeline | 四角色闭环、VERSION 单源、tag 纪律、三平台打包、上传 gated |
| `engine/review.md` | pipelines/review | 工程哲学三条、Phase 0 门禁、三维评分、check-fix ≤3 轮、前端适配段 |
| `engine/nebflow-docs.md` | docs-prompt/nebflow-docs | 写作规范/双语同步/一致性联动/产出路径纪律/图文报告图片内嵌 |
| `engine/nebflow-prompt-engineering.md` | docs-prompt/nebflow-prompt-engineering | 六核心原则/提示词解剖/编辑纪律/工具描述写法/agent 体检 |
| `engine/plugin-packaging.md` | nebflow-plugin-creator/plugin-packaging | 三输入→生成→自检→交付封装主流程，六分叉处置，交付报告模板 |
| `engine/description-quality.md` | nebflow-plugin-creator/description-quality | manifest 五段式描述规范、核心词表圈定法、M1-M15 判定、四例对照 |

## 6. 选章决策树

- 任务是**改 web/ 前端页面/组件/样式** → 前端章（nebflow-frontend + visual-style；设计新 UI 前先查 design-system 案例库）。
- 任务是**写/改 Scala 后端、调 flow engine、建工具** → 后端章（nebflow-backend / nebflow-tool-dev；cache 指标相关加读 nebflow-cache）。
- 任务是**交付 QA、验证、verify 节点** → qa 章（后端交付→nebflow-qa-backend；前端交付→nebflow-qa-frontend；修 bug 必附 verification-rigor 验红；跨 agent 行为→isolated-smoke-verification）。
- 任务是**合并分支** → 引擎章 merge-pipeline；**发版** → release-pipeline；**代码审查/review** → review。
- 任务是**写文档/改系统提示词/体检 agent 定义** → 引擎章 nebflow-docs / nebflow-prompt-engineering。
- 任务是**封装新插件/体检 plugin.json** → 引擎章 plugin-packaging（+ description-quality）。
- **官网 mystand.space** → 不进本包：另配 `website`；**通用 Word/PDF/PPT/Excel** → 另配 `document-production`。

## 7. 脚本与资产

| 资产 | 用途 | 使用方 |
|---|---|---|
| `skills/stand-dev/scripts/validate_plugin.py` | 插件包机械校验：M1-M15 + C 组、三档 profile（official/creator/user）、digest 复算 | 引擎章 plugin-packaging 第五步；协议面回归 |
| `skills/stand-dev/assets/manifest.template.json` | manifest 起步模板（五段式 description 骨架） | 引擎章 plugin-packaging 3.1 |
| `skills/stand-dev/assets/pluginmd.template.md` | Plugin.md 起步模板 | 引擎章 plugin-packaging 3.4 |
| `skills/stand-dev/assets/skill-template/` | skill 目录骨架 | 引擎章 plugin-packaging 3.2 |

> 脚本/资产自原 nebflow-plugin-creator 包**迁入本包**（取舍：脚本被 description-quality 与 plugin-packaging 两处引用且随包分发，迁入后 `${SKILL_DIR}/scripts/` 引用链最短；原包已随合并批卸除，无留原位引用的必要）。

## 8. 扩展位（未来候选）

| 候选项 | 现状 | 备注 |
|---|---|---|
| 新开发域章（如 mobile/桌面端专章） | 待域知识沉淀到可拆面 | 沉淀后入 §5 总目录，勿提前立空章 |
| mcp.json 形态生产首例 | 实验性 | 首例产出建议隔离实例验证 MCP 启动链后再交付 |

## 9. 维护约定

- **references 即原 skill 全文**：18 份 references = 原六包 SKILL.md 逐字迁入（仅跨包引用改写 + 迁址头注）；改章内容 = 改对应 references 文件，并在 SKILL.md 章导览同步口径。
- **digest 纪律**：包内任何字节改动即 digest 漂移（可见性提示）；seed 副本（仓内 `src/main/resources/seed/plugins/stand-dev/`）与运行时副本（`<data_root>/plugins/stand-dev/`）**双副本逐字节同步**，改动两侧同落。
- **本文件定位**：Plugin.md 是分配注入体；SKILL.md 与 references 是内容权威——不一致时以 SKILL.md / references 为准并回改本文；跨章共用的包级内容才进本文，单章细节留在对应 references。
- **合并溯源**：2026-10-31「开发插件统一批」卸除的六包（qa/frontend-dev/backend-dev/docs-prompt/pipelines/nebflow-plugin-creator）留痕见仓内批文档 `docs/20261031_*_stand-dev-plugin-merge*.md` 与 `<data_root>/plugins-backups/*_pre-stand-dev-merge-*/` 备份。
