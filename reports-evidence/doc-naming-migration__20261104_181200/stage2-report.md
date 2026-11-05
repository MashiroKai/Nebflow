# 文档命名改制 · 阶段 2 执行报告（重命名 + 引用重写）

- 执行窗口：2026-11-04 18:36–18:55（本地）；本机为活跃实例，执行期间有并发会话在写 vault
- 映射唯一来源：同目录 `manifest.md` §7 逐行迁移表（239 行）；执行前用 `xcheck-map.py` 独立重算交叉校验
- 回滚点：`/tmp/doc-naming-backup-20261104_183642.tgz`（18M，424 条目）

## 1. 读数（验收口径）

| 指标 | 读数 |
| --- | --- |
| 重命名总数 | **263**（vault 239 + reports-evidence 24） |
| 重命名失败 | 0 |
| **回滚** | **0 篇**（无任何一篇文章因改名出现断链） |
| 新名撞名 | 0 |
| 引用改写（链接语法） | wikilink **660**（`.md` 651 + `.html` 9）/ embed **0** / markdown **2** / 其中 `index.md` 内 **41** |
| 被改写文件（链接语法） | **219**（`.md` 214 + `.html` 5） |
| 硬编码路径改写（补充轮） | **46 文件**（正文里的 `旧名` 字面量，非链接语法） |
| 旧制**文件名**残留 | **0**（vault 与 reports-evidence 顶层均 0） |
| 旧名形态**链接目标**残留 | 1（换名前即悬空，§5） |
| 新增断链 | **0**（换名前 57 次/48 目标 → 换名后 58 次/49 目标，+1 是并发会话新写文档） |
| `check_wiki_docs.py --project stand` | **exit 0，零违规**（350 篇文档，18:52 快照） |
| 未解析链接 | **0**（校验器口径：既非文档也非已注册词条者；本批引入 0 条） |

## 2. 交叉校验（执行前，只读）

`xcheck-map.py` 独立重算 239 件 vs manifest §7：
集合差 0、新名撞名 0、快照漂移 0；名称差异仅 3 条，且正是裁定 2 要求的「4 位 HHMM 补 00」处
（§7 保留 4 位，本执行按裁定补 `00`）。

## 3. 日级精度占位（裁定 1，29 件 vault 文档，全部写 `000000`）

| # | 新名 |
| --- | --- |
| 1 | `cmdcode-provider-removal__20261030_000000.md` |
| 2 | `imagegen-blocking-semantics__20261030_000000.md` |
| 3 | `mobile-askuser-whitescreen__20261031_000000.md` |
| 4 | `owner-lost__20261031_000000.md` |
| 5 | `portal-session-shortlife-rootfix__20261031_000000.md` |
| 6 | `bg-panel-single-scroll__20261102_000000__delegate-nebflow-ef2390e1.md` |
| 7 | `canvas-ring-tabbar-align__20261102_000000__delegate-nebflow-ea997ea1.md` |
| 8 | `center-tab-no-fade__20261102_000000__delegate-nebflow-e1c239be.md` |
| 9 | `mobile-pinned-search__20261102_000000__delegate-nebflow-43af6929.md` |
| 10 | `muse-avatar-shrink__20261102_000000__delegate-nebflow-d616c46a.md` |
| 11 | `muse-head-restore__20261102_000000__delegate-nebflow-accdcd3b.md` |
| 12 | `muse-head-same-row__20261102_000000__delegate-nebflow-8c0b5ca5.md` |
| 13 | `plugin-panel-layout__20261102_000000__delegate-nebflow-f1fc2f78.md` |
| 14 | `title-capsule-height__20261102_000000__delegate-nebflow-f003855e.md` |
| 15 | `todo-capsule__20261102_000000__delegate-nebflow-35eeaf1e.md` |
| 16 | `time-wheel-picker-implementation__20261104_000000__delegate-nebflow-4bff07ce.md` |
| 17 | `design/nodeprogress-node-task-card__20260928_000000.md` |
| 18 | `design/detached-apply-restart-update-spec__20261026_000000.zh-CN.md` |
| 19 | `design/cli-isolation-and-detached-restart-spec__20261027_000000.zh-CN.md` |
| 20 | `design/hot-reload-foundation-spec__20261027_000000.zh-CN.md` |
| 21 | `design/canvas-ask-mode-design__20261028_000000.zh-CN.md` |
| 22 | `design/canvas-ask-mode-tokens__20261028_000000.zh-CN.md` |
| 23 | `design/triple-line-integration-contract__20261028_000000.zh-CN.md` |
| 24 | `design/errorfrozen-dialog-minimal-spec__20261029_000000.zh-CN.md` |
| 25 | `design/background-task-lifecycle__20261030_000000.zh-CN.md` |
| 26 | `design/human-readable-presentation__20261030_000000.zh-CN.md` |
| 27 | `design/memory-dimension-profiles__20261030_000000.zh-CN.md` |
| 28 | `design/merge-lane-state-machine__20261030_000000.zh-CN.md` |
| 29 | `design/orchestration-execution-split__20261030_000000.zh-CN.md` |

另有 1 件 `000000` 不属裁定 1：`reports-evidence/time-wheel-picker__20261103_000000__delegate-nebflow-4bff07ce`
（旧目录名日期 20261103，git 首次提交 20261104_095805，日期不符 ⇒ 不臆造跨日时刻，退日级占位）。

## 4. 4 位 HHMM 补 00（裁定 2，3 件）

| 旧 | 新 |
| --- | --- |
| `20261102_2010_profile-settings-closeout__delegate-nebflow-16c2e54c.md` | `profile-settings-closeout__20261102_201000__delegate-nebflow-16c2e54c.md` |
| `design/20261029_1815_model-schedule-unavailable-spec__delegate-nebflow-b3020083.zh-CN.md` | `design/model-schedule-unavailable-spec__20261029_181500__delegate-nebflow-b3020083.zh-CN.md` |
| `evidence/20260929_0200_brief-transport-silent-truncation.md` | `evidence/brief-transport-silent-truncation__20260929_020000.md` |

## 5. reports-evidence 目录改名（24 条，全部 `git mv`）

时间分量取法：**git 首个添加该路径的 commit 的 author date** 的 HHMMSS（该目录全部文件 mtime 都是
工作树物化时刻 18:14:10，等值无信息，故不用 mtime）。

`link-server-edge-pong__20261028_202129.patch`、`merge-fifo__20261103_205559`、
`standby-matrix__20261103_185636`、`time-wheel-picker__20261103_000000__delegate-nebflow-4bff07ce`、
`b1-dataroot__20261104_110844`、`bash-overhaul__20261104_133316`、`interrupt-recovery__20261104_114731`、
`isolated-instance-kill__20261104_104528`、`kai-probe-tax__20261104_113545`、`log-slim__20261104_112201`、
`memory-mitigations__20261104_133908`、`multi-device-shared-compute__20261104_142251`、
`nearpath-fire__20261104_114520`、`presence-dial-overhaul__20261104_140826`、
`presence-direct-261104__20261104_153345`、`process-leak-fold__20261104_153023`、
`root-migration-rehearsal__20261104_154715`、`route-tiers__20261104_174836`、
`sbt-client-server-verification__20261104_144556`、`self-explain-plugin-rule__20261104_141145`、
`todo-unify-backend__20261104_145310__delegate-nebflow-79768ef5`、`tool-card-image-face__20261104_140435`、
`worktree-cleanup__20261104_172339`、`zhang-sanqi-runtime-recon__20261104_144117`。
`session-stability/`（无日期）与 `doc-naming-migration__20261104_181200/`（已是新制）不动。

## 6. index.md 判定

`index.md` 是**手写**（全仓无生成器：`grep -rn 文档库索引 --include=*.py/*.mjs/*.sh/*.scala` 零命中）。
按手写处置：只机械改写链接（41 处），行数 66 → 66 未变，未做任何内容重排。

## 7. 未闭合项（不改，登记）

1. **vault 内 1 处旧名形态链接目标**：`dispatcher-chain-lifecycle-rca__20261101_165531__kernel.md:4`
   frontmatter `related: "[[20261028_102700_delegate机制与数据同步现状]]"` —— 换名前即悬空
   （`links-before.json` 有记录），不在 manifest §7 / rename-map 内（该文件从未存在于本 vault），
   按「不猜」原则原样保留。
2. **1 处新增悬空（非本批所致）**：`design-scale-migration__20261104_184032__delegate-nebflow-7ecba82c.md`
   的 `[[design-language-tokens]]` —— 该文档由并发会话于 18:40 新写，其目标为插件内文件（不在 vault）。
3. **seed 插件树 2 处旧文档路径**（有意不改）：
   `src/main/resources/seed/plugins/visual-report/skills/wiki-docs/SKILL.md:232`
   （`docs/design/20261030_human-readable-presentation.zh-CN.md`）与 `:163` 的折行同名路径。
   改它会让插件 digest 漂移且需与 `<data_root>/plugins/visual-report/` 运行时副本同步（规则源批的活），
   故本批不碰，转交规则源/插件批。
4. **并发**：本批执行窗口内另有接管批 `delegate-nebflow-d432ab0c` 在同一工作树工作，vault 亦有
   实时写入（.md 337 → 338 篇、校验器 349 → 350 篇）。本报告读数为 18:55 前后快照。

## 8. 脚本

| 脚本 | 用途 |
| --- | --- |
| `xcheck-map.py` | §7 独立重算交叉校验（只读） |
| `rename-execute.py` | 执行重命名 + 落盘 `rename-map.tsv` |
| `rewrite-links.py` | 引用重写（链接语法 + 硬编码路径 token；映射只读 `rename-map.tsv`） |
| `verify-links.py` | 链接健康度（基线 `links-before.json` / 换名后 `links-after.json`） |
| `residue-scan.py` | 四项验收扫描（命名合规 / 旧名残留 / 断链回归） |
