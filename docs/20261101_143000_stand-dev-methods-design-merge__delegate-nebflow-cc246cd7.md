---
type: audit
status: done
related: "[[20261031_223533_stand-dev-plugin-merge]]"
---

# stand-dev 二批并入：design-spec + engineering-methods（2026-11-01）

> 批令（作者 11-01）：把 design-spec 和 engineering-methods 两个包并进 stand-dev 开发统一包，并与已有内容做好适配。本稿 = 全批留痕：定位、结构决策、迁移保真、适配点、双副本与单仓同步、验证与申报。

## 1. 盘点与定位

- `tools/stand-plugins list`：design-spec 1.0.1 / engineering-methods 1.0.1 均 dispatchable 在列；stand-dev 1.0.0 在列。
- 包实体：实例 `~/.stand/plugins/{design-spec,engineering-methods}/` 与单仓 `~/.stand/plugin-repos/{design-spec,engineering-methods}/` 双副本均在。**diff 发现单仓副本落后于实例**——合并批（[[20261031_223533_stand-dev-plugin-merge]]）的引用面改写（「另配 nebflow-*/qa 插件」→「另配 stand-dev 前端章/qa 章」）只落了实例侧，单仓仍为旧口径（tag v1.0.1）。**迁移源取实例副本**（新口径）。
- 仓内 seed 树（`src/main/resources/seed/plugins/{design-spec,engineering-methods}/`）与实例副本亦有同一性质的漂移（seed 侧仍是 `frontend-dev`/`qa` 旧指向，实例侧已是 stand-dev 章指向）——即上一批的「runtime 与 seed 双副本同改」对这两包实际只落了 runtime。**本批不改两独立包**（退役/改写等作者令），如实申报（§6）。

## 2. 结构决策（沿用一批手感）

- 单 skill 目录不变（`skills/stand-dev/`），references 按任务域扩为**六章**：新增 **方法章**（`references/methods/`，grill + parallel-research，2 份）与**设计章**（`references/design/`，design-spec，1 份）。
- 章序按开发生命周期重排：方法（选题/调研/方案）→ 设计（设计先行）→ 前端/后端（写）→ qa（验）→ 引擎（闸）。原四章章名（qa/前端/后端/引擎）全部保留，包外按章名的既有引用（design-cards、design-language、explorer-toolkit、learn-anything、document-production 等包内的「另配 stand-dev 前端章/引擎章」）零破坏。
- 迁移样式与一批同款：每件头部加**迁址说明 + 章内衔接注**；正文逐字迁入，仅跨包引用改写为章内指向。

## 3. 迁移保真（diff 可证）

| 新件 | 来源 | 正文保真 |
|---|---|---|
| `references/methods/grill.md` | 实例 design-spec 包同款源：`engineering-methods/skills/grill/SKILL.md` | **逐字一致**（diff 仅迁址头注 + 章内衔接注；frontmatter 原样保留） |
| `references/methods/parallel-research.md` | `engineering-methods/skills/parallel-research/SKILL.md` | **逐字一致**（同上） |
| `references/design/design-spec.md` | `design-spec/skills/design-spec/SKILL.md` | 逐字一致，仅 5 处跨包引用改写（「stand-dev 前端章」→「同包前端章」，配合关系节改写为章内+跨包分列），语义零改动 |

## 4. 适配点（引用而不复制，无双源）

- **修残留旧引用 3 处**（一批漏改的已退役包名）：`frontend/nebflow-frontend.md`（「见 \`qa\` 插件」→「见同包 qa 章」）、`backend/nebflow-backend.md` frontmatter（「stand-dev qa 章 插件」→「同包 qa 章」）、`engine/review.md`（「stand-dev 前端章 插件的 visual-style」→「同包前端章 visual-style」）。
- **design-spec × 前端章/qa 章 挂针**：`design-system.md` 头注（案例库是设计章防线 1 先例来源 + 防线 4 沉淀载体，规格书方法归设计章）；`nebflow-frontend.md` 头注（实现以设计规格书为唯一依据，防线 2）；`nebflow-qa-frontend.md` 头注（视觉评审归设计方、功能验收归 qa、§8 可断言点转 Playwright、口径含糊回问设计方）；`SKILL.md` §2/§3/§5 摘要同步。
- **engineering-methods × 后端章/pipelines/docs 挂针**：`nebflow-backend.md` 交付口径节（`_ARCH_SPEC.md` 为实现输入、任务清单即验收对照物、需求模糊回问）；`nebflow-docs.md` 产出路径纪律节（方法章落盘同守本节纪律）；`merge-pipeline`/`review` 经 SKILL.md §1 章间衔接与方法章头注覆盖（不逐件加注，避免重复）。
- **重复/矛盾排查结论**：设计章与 design-system 为方法/载体分层（无重复）；设计章视觉评审与 qa 章功能验收分工在两侧头注同口径互指（防双源）；方法章双文档落盘与 nebflow-docs 产出路径纪律并轨（docs 侧为主、方法章头注引用）；explorer-toolkit / design-cards 边界保持包外另配口径不变。

## 5. 包级改动与验证

- `plugin.json`：version 1.0.0 → **1.1.0**；description 五段式重写（**397/400 字符**，M07 过闸；方法/设计触发词与六章/21 份口径入文）；keywords 增补（需求拆解/技术调研/trade-off/并行调研/设计规格书/视觉评审）；useWhen 增补两章任务域。
- `Plugin.md`：速览/目录结构/章导览表（+方法、设计两行）/references 总目录（18 → 21 行）/决策树/维护约定（一批+二批溯源）全量同步。
- `SKILL.md`：六章总纲重写（含两章核心指令摘要与章间衔接）；决策树补「定方向/查证据/出设计」三条；维护约定 21 件口径与「二批原包未退役」状态注明。
- `validate_plugin.py --profile creator`：**PASS（32/32，0 FAIL）**，digest `sha256:d4948bb0ffdfef51e058c41d89fdd476cbcb19830431e2c48dec59519a43d003` files=30；C17 软顶告警（397>200）为统一包固有，与一批同样记为可接受。
- seed 四 spec（SeedManifestCoverageSpec / SeedServiceSpec / SeedPluginReconcileSpec / SeedPluginDeletionMarkerSpec）回归：见交付报告（stand-dev 按名锚定、内容无关，预期零改动通过）。
- 双副本：runtime `~/.stand/plugins/stand-dev/` 与 seed 逐字节同步（本批改动两侧同落）；`stand plugin list` 装载核实见交付报告。
- USTC 单仓：新 seed 导出覆盖 `~/.stand/plugin-repos/stand-dev/`，commit + 推 dev 远端 + tag **v1.1.0**（零 force，不动 GitHub）。

## 6. 申报（等作者令，本批未动作）

1. **两原独立包仍在实例装载**：design-spec 1.0.1 / engineering-methods 1.0.1 仍 dispatchable（`stand plugin list` 在列），能力已双载于 stand-dev 1.1.0——**退役与否、是否从 seed 树卸除，等作者令**。双载期间存在选配重叠面（设计/方法类任务两包 description 均可命中），分发器按触发词并存选配，不冲突但冗余。
2. **两独立包的 seed 副本滞后于实例副本**（§1 漂移）：seed 侧跨包引用仍是已退役包名（frontend-dev/qa），实例侧已是 stand-dev 章指向。runtime 副本手改后为 user-modified 态（seed 不会覆盖），但**全新实例首次镜像会拿到旧口径**。若作者令退役则随卸除消解；若保留独立包，建议下批把 seed 副本对齐 runtime 口径。
3. explorer-toolkit 包内 Plugin.md 亦有同类残留（「执行验证另配 \`qa\`」，qa 包已退役），属包外发现，未动，一并申报。

## 7. 边界遵守

- 零 force、未动 GitHub（design-spec/engineering-methods 的 gh 远端未触碰）、未碰 #1281 设计稿批 docs/ 写入面与 #1282 图谱手势批工作区。
- 两原独立包未卸除、未删任何目录；runtime 改动仅限 `~/.stand/plugins/stand-dev/`。
