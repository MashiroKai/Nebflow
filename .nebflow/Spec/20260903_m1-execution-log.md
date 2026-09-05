# Nebflow 阶段 2 迁移 M1 启动批——执行日志

> 执行节点：n-744a964e（nebflow project，2026-09-03）。上游：n-1563ee11「修订-迁移方案文档v2」（completed，~/.nebflow commit 481771d）结果自动投递为本节点输入。
> 执行依据：`20260903_stage2-migration-plan.md`（v2）flow 判定表 + 作者 2026-09-03 12:18 五条终裁（全部现有项目都迁移、无预定义模板、Task 唯一项目触发入口、手动归档不自动归档）；与指令不一致处以本次指令为准。
> 红线遵守：全程 git mv 可逆（零 rm 删除）；归档仅 nebflow-project 一个团队；宿主（PID 87216 / 端口 8080）零触碰；所有 commit 未 push；git add 只加具体文件。

## 一、Part 1：nebflow-dev skill 组蒸馏（来源：nebflow-project 9 成员）

来源结构核实：9 成员实际定义在 `teams/nebflow-project/agents/<名>/`（team domain agent），非 `~/.nebflow/agents/`（该处同名者为全局残留，属文档 v2 §1.4 后续批次处置，本批不动）。

| skill | 来源成员 | 内容概要 |
|---|---|---|
| nebflow-backend | Backend | Scala 3/Pekko/http4s/cats-effect 栈、Actor/IO 分层铁律、sbt 实践（-batch/锁冲突/OOM）、worktree 纪律 |
| nebflow-frontend | Frontend | web/ 无构建 vanilla JS 结构、Sapphire Glass 设计系统、设计原则、硬约束、截图驱动验证流；与 nebflow/visual-style、nebflow/frontend-verification 分层引用不重复 |
| nebflow-docs | Docs | 写作五规范、双语同步、DOCS_NAV 导航、文档一致性联动、产出路径纪律 |
| nebflow-prompt-engineering | prompt-engineer | 六核心原则、提示词解剖、编辑纪律与反模式、工具描述写法、周期体检清单 |
| nebflow-tool-dev | tool-engineer | 工具管线与架构（内置 Scala/ScriptTool/权限模型）、五设计原则、新建/优化流程 |
| nebflow-cache | cache-engineer | 指标定义、数据源、已知事实（间隔悬崖/107 网关/冷启动路由/基线精简）、先量化再动手 |
| nebflow-qa-backend | qa-backend | 五维审核结构、sbt 锁冲突口径、运行安全红线、✅/❌ 报告格式、只审不改 |
| nebflow-qa-frontend | qa-frontend | 隔离真实例三步、禁静态替身规则、FAIL 判定、契约静态审核四维、只验不改 |

- **Manager 不蒸馏**：其定义内容全部为旧体系编排方法论（Mail 路由/SubTask coaching/scope 拒绝协议/RESULT 处置义务），新架构由 project-dispatcher 承载；无 nebflow 领域事实需要并入（QA 直触条款已由 rules.md→AGENTS.md 核对承载）。
- 命名查重：ls skills/ 核对，nebflow-* 八名均无冲突（既有 `nebflow/` 命名空间 skill 四个不受影响）。
- **commit：`7b074b9`**（8 files，+480）。

## 二、Part 2：nebflow-project team 收尾归档

### 2.1 rules.md 核对进主仓 AGENTS.md

- 开工前 `git status --porcelain` 干净确认（在飞节点合并已完成）。
- 141 行逐项核对结论：基底已覆盖绝大多数条目（技术栈/协作协议防双等/开发约定/版本发布/Git 安全/worktree/运行安全/架构原则/文档一致性/前端规范三条/文档产出路径/用户裁定 9 条）。**补 2 条、改写 2 处（均注明）**：
  - 改：「主开发分支 archive/scala」→ `main`（现实核对：main 活跃 Scala 开发，archive/scala 分支已删除；rules.md「main 已切换为 Rust」「当前分支 fix/canvas-source-toggle-bugs」过时，注明）；
  - 改：合并审查「走 code-review flow」→「先过 review 审查（原 flow 已蒸馏为 review skill）」+ 并入 worktree 命名规范（分支名与目录名一致）；
  - 改：协作分工参考注记 → team 已归档、按方向组建节点、领域知识见 nebflow-* skill 组。
- **commit：`5bb8bd88`**（主仓，只 AGENTS.md 一文件）。

### 2.2 冒烟记录（不重跑，近两日 Flow Map 即运行实证）

nebflow project Flow Map（`.nebflow/flow-map.json`，67 节点）全链实证：

1. **barrier 串联/自动投递链**：n-1563ee11（阶段2迁移方案文档修订 v2，completed）→ 结果自动投递，直接触发本节点 n-744a964e（M1 启动批）开工——barrier 完成即下游开工的时序信号链。
2. **验证→合并→清场闭环**：n-01b4c9e6（send-btn 绿色身份强化·视觉验收 barrier，completed，判定为下游合并闸门）→ n-fcb52a38（sendbtn-green 合并收编进本地 main + 作者交付，completed）；n-a83c68ed（micorb-config-hide 合并收编：QA 闸门→执行合并→冲突融合→子集复跑→worktree 清理，completed）。
3. **blocked 重入/重派自愈**：n-b794ac96（结果投递链丢失修复·重派4 续作，completed——前次会话被宿主清场误杀后经重派链成功重入）；n-4e1f95f6（FlowMap 引擎缺口三合一修复，含「归档不补投递」缺口修复，completed）。
4. **审查闸门合并**：n-0d37883d（PR #44 合并执行，completed——上游 n-46e25f90 三维审查判定为闸门）。

### 2.3 归档（git mv，全程可逆）

1. **team 下架**：`git mv teams/nebflow-project/team.json teams/nebflow-project/team.json.archived` → **commit `3203986`**（防归档成员窗口期被 Mail 触达；目录注入随实体装载自动摘除，运行中宿主不重启、下次装载生效）。
2. **跨团队同名引用排查**（归档成员前执行）：grep 命中 nebflow-rust（Backend/Frontend/Docs/qa-backend/qa-frontend/Manager）、slideblocks（Frontend/Backend/Docs/Manager）、nebflow-website（Frontend/Manager）、voice-recognition-test（Backend/Frontend/Manager）等。**实测各团队均拥有自己独立的 team-domain 副本**（teams/<团队>/agents/ 目录逐一核实），与 nebflow-project 的定义是独立实体——**归档零跨团队功能影响**；影响面备注：仅名义同名，无共享定义。
3. **9 成员归档**：`mkdir -p archived-agents` + 逐个 `git mv teams/nebflow-project/agents/<名> archived-agents/<名>/`（Backend/Frontend/Docs/prompt-engineer/tool-engineer/cache-engineer/qa-backend/qa-frontend/Manager，18 文件 R 状态）→ **commit `82c2dfd`**。
- rules.md 原地保留（要点已核对进 AGENTS.md；不删不 mv，git 历史可溯）。

## 三、Part 3：slideblocks / nebflow-website 物料先行（归档延后）

### 3.1 skill 蒸馏

| skill | 来源 | commit |
|---|---|---|
| slideblocks-frontend / slideblocks-backend / slideblocks-docs | slideblocks 成员定义 | `5541d98` |
| website-frontend / website-design | nebflow-website 成员定义 | `e83465e` |

- slideblocks-docs 内容单薄仍独立成 skill（理由写入 skill：独立能力域、一员一 skill 映射清晰）。
- **换域校验发现并处置**：slideblocks/Frontend 原定义系 nebflow-project Frontend 整份复制残留（含 Sapphire Glass、vanilla JS 无构建、src/main/resources/web/ 结构等与 Astro+Vue 栈完全不符的内容）——蒸馏时剔除 nebflow 专属内容，skill 内留历史注记（对应 prompt-engineer 周期体检「跨团队复制残留整批排查」教训）。
- Manager（两团队）不蒸馏：编排职责由分发器承载，同 Part 1 口径。
- 命名查重：slideblocks-*/website-* 与既有 `slideblocks`（deck 制作路线）、`deploy-website`（部署操作）均不冲突、不重叠。

### 3.2 rules.md 核对进各自项目 repo 的 AGENTS.md（一项目一 repo，只 add AGENTS.md）

| 项目 | workspace（project.json） | 增补内容 | commit（分支） |
|---|---|---|---|
| slideblocks | ~/.nebflow/projects/slideblocks | 技术栈与项目概述、每任务新分支/tarball-ours-theirs 禁令/对账防吞块、PR 通道偏好、文档产出路径、2026-08-28 deck-平台分离裁定 | `22088e9`（fix/deck-workbench，在飞实施线，正交无冲突） |
| nebflow-website | ~/.nebflow/projects/nebflow-website | 技术栈与项目结构、Git 安全、设计规范、文档产出路径（docs/nebflow-website/）、wordmark 全小写与 localhost 预览废弃两条用户裁定 | `f045e6e`（staging 部署线；他人未提交的 project.json 与 probe-*.mjs 未触碰） |

两团队 rules.md 原地保留（同 nebflow-project 口径）。

## 四、Part 4：flow 处置（按文档 v2 §1.3 判定表 + 本次指令）

| flow | 处置 | commit |
|---|---|---|
| memory-consolidation | 方法论 skill 化 `memory-consolidation`（触发时机/四判据/DELETE-UPDATE-MERGE 动作格式/外科手术纪律；清理执行=Nebula+MemoryEdit） | `1ac0a50` |
| research | 蒸馏 skill `parallel-research`（正交拆题/来源强制/逐轨核验/双轨综合+建图注记） | `7863b63` |
| code-review | SOP 增补进既有 `review` skill（先读后补，原有 290 行未动；新增：diff 扫描清单/fixer 四原则/复审轮次/UI selector 衰减门/verdict 门禁语义） | `5e14c6a` |
| git-merge | 蒸馏 skill `merge-pipeline`（扫描→评估→合并→回滚，不 push 红线） | `ce44edf` |
| release-stable | 蒸馏 skill `release-pipeline`（四角色闭环/tag-on-merge-commit/三平台打包/复审清单；**过时口径修正**：原 flow 的日期版号+archive/scala 已废弃，按现现实 semver+main 改写并注明） | `c173086` |
| presentation-prep | 直接归档：`git mv flows/presentation-prep flows/presentation-prep.archived`（检索/规划纪律按 v2 判定由批次 2 deck-studio skill 吸收） | `1dd213e` |
| weekly-summary | **只记入本日志**——Schedule 触发文本改指 project 单节点一事，由 Nebula 下次周日 22:00 触发前更新，本任务不改 Schedule 定义 | —（延后） |
| entity-creator | 不动作——按文档 v2 判定留后续批次（转 skill/plugin 远期，双轨保留最久 DP6-a） | —（后续批次） |
| nebflow-review-merge | 不动作——按文档 v2 判定留后续批次（转 skill 并入 nebflow-dev 组） | —（后续批次） |

- `flows/presentation-prep.zip`：**未跟踪文件**（git ls-files 确认），非 git 对象、无 mv/rm 必要，原地保留（文档 v2 的 git rm 建议留待后续批次显式处置）。

## 五、延后项清单

1. **slideblocks 归档**：延后——编辑模式实施在飞（fix/deck-workbench），等在飞任务终态，时机由 Nebula 另行通知。
2. **nebflow-website 归档**：延后——部署线活动（staging 分支 + probe 脚本在飞），等在飞任务终态，时机由 Nebula 另行通知。
3. **weekly-summary Schedule 更新**：触发文本改指 project 单节点，由 Nebula 下次周日触发前完成（本任务不改 Schedule 定义）；其 flow→skill 蒸馏随该步骤一并处置。
4. 全局 `~/.nebflow/agents/` 同名残留（Backend/Frontend/Manager/prompt-engineer/qa-backend/qa-frontend/tool-engineer）：属文档 v2 §1.4 全局 standalone agents 处置批次（M4 时点），本批未动。

## 六、验收自检记录

- [x] 17 个新 skill 全部有 YAML frontmatter（name/description/when_to_use），ls skills/ 复核命名零冲突
- [x] 三处 AGENTS.md 核对完成且各自 repo 独立 commit（主仓 5bb8bd88 / slideblocks 22088e9 / website f045e6e）
- [x] nebflow-project team.json → team.json.archived（3203986）+ 9 成员 → archived-agents/（82c2dfd），全程 git mv（18 文件 R 状态）
- [x] 归档仅 nebflow-project 一个团队（slideblocks/website 未归档，物料先行完成）
- [x] flow 处置逐条对上指令（6 处置 commit + 3 项延后/后续批次记录）
- [x] 所有 commit 未 push；宿主 PID 87216/端口 8080 零触碰；未跑 sbt；未触碰 .nebflow/worktrees/ 与任何在飞分支文件

## 七、遗留问题

1. `deploy-website` skill 内容过时（git add -A、手动 vercel deploy 口径，与双域发布两步纪律不一致）——本批 scope 外未改，建议 website 归档批次一并修订。
2. 主仓 AGENTS.md 中「Mail 中告知工作目录」等个别旧体系措辞残留（worktree 节），语义不受影响，后续批次随团队路由段一并清理。
3. 运行中宿主对 team.json.archived 的目录摘除在下次实体装载时生效（本任务不重启宿主）；窗口期内 Mail(nebflow-project) 仍可能命中内存态目录——新触发已被队列纪律禁止。
4. teams/nebflow-project/agents/ 目录归档后残留空目录（git 不跟踪空目录，无实质影响）。
