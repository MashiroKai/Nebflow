VERDICT: PASS

# 脏工作树落地保全二轮 —— 汇总表 · 逐棵复核 · 删树台账 · 最终报告

**判词基线 / 重验基线（三行事实，全部现算，不写死 sha）**

- 分片判词基线 = `0fffa26b5`（recon §1，各分片作业时的 main tip）
- 重验基线 = `a0597efdd`（`git -C "/Users/kaiyu/Claude code/Stand" rev-parse main` 于 2026-11-04 19:0x 现算；本节点开工时为 `2996e6fbb`）
- 期间 main 移动 **5 次**：`0fffa26b5 → d1d669aab`（设计尺度迁移批：`src/main` 75 文件 + 该批 evidence 24 文件）→ `9be1797fd`（仅该批 README.md，1 文件）→ `f3bc0a7be`（PresenceCandidateProbe.scala + Spec，2 文件）→ `2996e6fbb`（命名迁移批 + wiki-docs 脚本，368 文件）→ `a0597efdd`（doc-naming 收口报告 3 文件）。逐次影响见 §8。

**台账口径**：待处理 103 棵 = `shards/dirty-1..5.tsv`(19+19+19+18+18=93) + `unmerged.tsv`(2) + `detached.tsv`(8)，与 `shards/recon.md` §3 的 103 完全一致，无缺分片。全部 103 棵在主表 §2 各占一行（行数 103 == 待处理棵数 103，未抽样）。

**产出目录（新制名）**：`reports-evidence/worktree-salvage__20261104/`（原 `reports-evidence/20261104_worktree-salvage/`，本批收工按命名新制改名，见 §7）。

---

## §1 摘要

| 项 | 读数 | 依据 |
|---|---|---|
| 待处理棵数 | **103** | `shards/*.tsv` 数据行 19+19+19+18+18+2+8 |
| **删除棵数** | **100**（81 棵免 `--force` + **19 棵 `--force`**） | `git -C <主仓> worktree remove`，逐棵三步复核后执行；台账 `/tmp` 原始 JSON 已折算进本报告 §2 |
| **保留棵数** | **3**（全部属「活树」，判不清 0 棵） | `delegate-nebflow-183b016d` / `98f9ca81` / `e7c1a868`：`~/.stand/delegates/*.json` 里 `reportStatus=None` 且 27h 内 ⇒ 按任务书 b 条跳过 |
| 保全提交数 | **94** | tsv `commit_sha` 非 `-` 的行数（19+19+19+17+17+3） |
| 保全文件总数 | **28,907**（分片口径）/ **29,017**（git 展开实测）/ **29,015**（有体积读数） | 差额 110 = dirty-2 分片按 porcelain 行计（121 行 → 231 文件），已在 §2 该片各行 evidence 注明；另 2 个非 ASCII 路径在体积统计中未解析 |
| **判废丢弃总量** | **716 文件 / 1,856.18 MiB**（1,946,343,613 B） | 19 棵 `--force` 树的实测残留，逐文件清单见 `shards/force-discard-round2.tsv`（716 行 + 表头） |
| 磁盘回收（`du`） | `51,709,828 KB → 7,573,048 KB` ⇒ **回收 44,136,780 KB ≈ 42.1 GiB** | `.nebflow/worktrees`，删树前后各现算一次（开工基线 recon §7 为 49G，期间他批另有增减） |
| 磁盘回收（`df`） | 可用 `69,156,064 KB → 115,586,256 KB`（`+46,430,192 KB ≈ 44.3 GiB`）；报告落盘时现场再读 **110Gi 可用**（88%） | `df -k/-h "/Users/kaiyu/Claude code/Stand"`；开工基线 recon §7 = 69Gi。`df` 含并发批次增减，口径以 `du` 为准 |
| 判定分布（重验后） | 被取代 **46** / 已并入 **30** / 需合并 **17** / 不确定 **10** | §2 表末列；= 103 |
| 主仓分支数 | 开工 **400** / 收工 **400**（本节点 `git branch -d/-D` = **0 次**） | `git branch \| wc -l` |
| 工作树登记数 | recon 开工 **123** → 删树前 **125**（他批新起）→ 收工 **25** | `git worktree list \| wc -l`；差 = 本节点删 100 |

### §1.1 保全量的分解（28,907 / 29,017 个文件到底是什么）

按 git 实测（`pre_head..commit_sha` 的 diff 文件清单 + `cat-file --batch-check` 取体积，共 29,015 个有读数）：

| 顶层前缀 | 文件数 | 占比 | 说明 |
|---|---|---|---|
| `evidence/**` | **23,905** | **82.4%** | 各批截图/日志/复现证据（`.gitignore:130` 锚定 `/evidence/`，主仓本就不入库） |
| `reports/**` | **4,731** | **16.3%** | 批次报告 md/html（`.gitignore:131`） |
| `src/**` | 351 | 1.2% | 真正的代码改动 |
| `tests/**` | 18 | 0.1% | 浏览器/e2e 脚本 |
| `docs/**`、`.probe/`、`scripts/` | 6 | 0.02% | 见 §5 |
| 树根散件（`*.tmp.mjs`、`brand.conf` 等） | 5 | 0.02% | 见 §5 |

⇒ **98.7% 是过程证据、仅 1.2% 是代码**。这就是 2.9 万的来源，不是「把工作成果刷进 git」。

**贡献最大的前 10 个二级目录**（合计 5,604 文件 / 19.3%；其余是大量小批，完整清单可由 `git -C <分支> diff --name-only <pre_head>..<sha>` 复算）：

| 目录前缀 | 文件 | 体积 |
|---|---|---|
| `evidence/20261019_145000_avatar-sharp/**` | 1,602 | 10.7 MiB |
| `evidence/20261022_120500_console-3symptoms__delegate-nebflow-f7d4c37a/**` | 578 | 32.1 MiB |
| `evidence/20261022_133900_deckfab-rollback__delegate-nebflow-f7d4c37a/**` | 544 | 25.9 MiB |
| `evidence/20261022_secrets-eye-converge/**` | 493 | 25.2 MiB |
| `evidence/20261021_124500_canvas-stack-ui5/**` | 476 | 21.1 MiB |
| `evidence/20261006_branch-cleanup-6verdicts/**` | 450 | 23.4 MiB |
| `evidence/20261022_chatinput-two-defects/**` | 382 | 83.6 MiB |
| `evidence/20261021_console-load-perf/**` | 374 | 6.8 MiB |
| `evidence/20261019_235300_mobile-v3-impl__delegate-nebflow-0417c2ca/**` | 357 | 35.0 MiB |
| `evidence/20261031_mobile-emergency-inspect/**` | 348 | 31.6 MiB |

**单目录 > 2,000 文件：无。** 最大单目录 `evidence/20261019_145000_avatar-sharp` = 1,602 文件（重拍截图套组）。所以不存在「某个巨型目录偷偷进 main」的情形。

**保全集按体积倒序前 10 个文件**（证明没有把大二进制塞进 git）：

| 体积 | 文件（同 blob 在几棵树里出现） | 结论 |
|---|---|---|
| 1.97 MiB | `reports/20261023_195528_delegate-fullflow-audit.html`（×9 棵） | 同 blob，git 只存 1 份 |
| 1.04 MiB | `evidence/20261029_term-citation-batch2/live/03-live-back-button.png`（×6） | 同上 |
| 1.04 MiB | `…/live/02b-live-jump-landed.png`（×6） | 同上 |
| 0.99 MiB | `…/live/02-live-jump-highlight.png`（×6） | 同上 |
| 0.95 MiB | `…/live/04-live-returned.png`（×6） | 同上 |
| 0.92 MiB | `…/live/01-live-hover-bubble.png`（×6） | 同上 |
| 0.86 MiB | `evidence/20261022_wiki-graph-multivault__delegate-nebflow-56930fbd/shots/2-stand-light.png`（×17） | 同上 |
| 0.86 MiB | `reports/20261027_230551_beta2-verify/shots/42_docx_after_scroll600.png`（×8） | 同上 |
| 0.85 MiB | `reports/20261027_230551_beta2-verify/shots/10_awareness_panel.png`（×8） | 同上 |
| 0.82 MiB | `reports/20261027_230551_beta2-verify/shots/32_capsule_badge_hover.png`（×8） | 同上 |

> 保全集**内容体积合计 2,424,159,611 B ≈ 2.26 GiB**（去重前），因多棵树的 evidence 是同一批的副本，git 对象层去重后远小于此。

**单 blob > 5 MiB 被提交的：0 个**（`cat-file --batch-check` 全量扫 29,015 件，最大 1.97 MiB）⇒ §5 无此类条目。

### §1.2 判废侧体积

判废 722 文件（分片口径）= 722 个残留实测件 = **1,946,435,978 B ≈ 1,856.27 MiB**；其中 716 件随 19 棵 `--force` 删除被丢弃（1,856.18 MiB），另 6 件（90 KiB）在保留树 `e7c1a868` 内仍在。判废构成：`*.log` 629、`*.bundle` 18（88.9 MiB×18）、`*.png` 34（全是 >5 MiB 或重复重拍）、`*.json` 10、`*.md` 9（`*-probe*` 目录命中，见 §5）、其余 21。

---

## §2 主表（103 棵逐棵，一行不漏）

列说明：`保全提交 sha` 形如 `pre_head → salvage`；`—` = 该树无保全提交（clean 或全判废）；`判词依据` 列保留分片原文、`【重验改判】` 标记的为该行对本节点现算 tip 复核后的修正。
| # | 路径 | 分支 / salvage 分支 | 保全提交 sha | 保全 | 判废 | 合并判定 | 处置 | 判词依据（evidence，原文保留） |
|---|---|---|---|---|---|---|---|---|
| 1 | `.nebflow/worktrees/delegate-nebflow-00381b66` | `delegate/delegate-nebflow-00381b66` | `9389ff749` → `5268a9fd1` | 7 | 0 | 被取代 | 已删 · 无需 force | pre_head 是 main 祖先(cherry 空输出/ahead=0)；保全 7 代码文件 vs main：6 个内容不同且 5 个 main 有晚于 2026-10-30 的提交(即 main 已自行改写)；主仓缺 DeviceProjectWorktreeSeatSpec.scala 但已有 WorktreeSeatValida… |
| 2 | `.nebflow/worktrees/delegate-nebflow-0cda5510` | `delegate/delegate-nebflow-0cda5510` | `f1ce641c7` → `cb7a940e8` | 2 | 0 | 不确定 | 已删 · 无需 force | 对 `a0597efdd` 复核：cherry +1（patch 不在 main）；own delta 2 个代码文件 / 新增 3 行，其中 0 行已在 main（0.0%）；main 缺 1 文件（如 `src/main/resources/web/js/attFileGlyph.js`）；与基座移动面交集 M1:1 —— 经查该批对同文件为字面量→`var(--ds-*)` 等值令牌迁移（非语义取代）；分片原文：保全 2 文件：attFileGlyph.js 为主仓完全缺失的新模块(全库无 attFileGlyph 字面引用、无同名孪生)，chat.js 有 2 处引用它；但 main 的… |
| 3 | `.nebflow/worktrees/delegate-nebflow-189e295a` | `delegate/delegate-nebflow-189e295a` | `091bd8459` → `4256fb5b8` | 189 | 0 | 被取代 | 已删 · 无需 force | pre_head 是 main 祖先；保全 189 代码文件 vs main：3 个内容一致、184 个不同且其中 181 个 main 有晚于 2026-10-19 的提交；主仓缺 DelegateAutoMerge.scala+Spec 系 main 已退役(merge-base 有该文件，main 现存 MergeQueue/Mer… |
| 4 | `.nebflow/worktrees/delegate-nebflow-22c29d3d` | `delegate/delegate-nebflow-22c29d3d` | `2e6a8211f` → `9a76f9861` | 2 | 0 | 已并入 | 已删 · 无需 force | pre_head 是 main 祖先(cherry 空输出/ahead=0)；保全内容 2 张 console 接管弹窗 png 全在 reports/ 下，主仓按 .gitignore 不入库过程产物的策略不跟踪，无代码级差异 |
| 5 | `.nebflow/worktrees/delegate-nebflow-35eeaf1e` | `delegate/delegate-nebflow-35eeaf1e` | `d00e8e48d` → `1429de0c3` | 96 | 0 | 已并入 | 已删 · 无需 force | pre_head 是 main 祖先；保全 96 文件全为 evidence/20261102_*mobile-panel-title 视觉证据，主仓 .gitignore 锚定 /evidence/ 不入库；无代码文件 |
| 6 | `.nebflow/worktrees/delegate-nebflow-491a5f27` | `delegate/delegate-nebflow-491a5f27` | `682fae4aa` → `414cb4875` | 1 | 0 | 已并入 | 已删 · 无需 force | pre_head 是 main 祖先；保全 1 文件为 reports/20261102_jev-imagegen-align-llm/contract.md，属过程报告面，主仓不入库；无代码文件 |
| 7 | `.nebflow/worktrees/delegate-nebflow-5e45698c` | `delegate/delegate-nebflow-5e45698c` | `4f544421b` → `3687269e4` | 4 | 0 | 已并入 | 已删 · 无需 force | pre_head 是 main 祖先；保全 4 张 queue-restore 队列条截图(evidence/)，主仓不入库过程产物；无代码文件 |
| 8 | `.nebflow/worktrees/delegate-nebflow-6cbd7d08` | `delegate/delegate-nebflow-6cbd7d08` | `7908982c4` → `4aceaa489` | 957 | 29 | 需合并 | 已删 · `--force`（丢 29 判废文件 / 99.8 MiB） | 对 `a0597efdd` 复核：cherry +4（patch 不在 main）；own delta 10 个代码文件 / 新增 321 行，其中 148 行已在 main（46.1%）；main 缺 3 文件（如 `src/main/resources/web/js/input/inputHistory.js`）；与基座移动面交集 M1:3 —— 经查该批对同文件为字面量→`var(--ds-*)` 等值令牌迁移（非语义取代）；分片原文：pre_head 非 main 祖先(ahead=3, cherry +3/-0)；主仓从未有过的真新文件 3 个：src/main/resources/web/js/input/… |
| 9 | `.nebflow/worktrees/delegate-nebflow-7961c23f` | `delegate/delegate-nebflow-7961c23f` | `cbdf4a527` → `95602846e` | 12 | 0 | 被取代 | 已删 · 无需 force | pre_head 是 main 祖先；保全 6 代码文件 vs main：4 个主仓缺但其中 tests/filehead-capsule-shots.mjs 在 main 已有改名孪生 tests/diff-filehead-capsule-shots.mjs，另 3 个为根目录探针脚本(probe-filehead-geometry.… |
| 10 | `.nebflow/worktrees/delegate-nebflow-7fa8a6bb` | `delegate/delegate-nebflow-7fa8a6bb` | `29af36d58` → `86d0e2b3c` | 31 | 0 | 已并入 | 已删 · 无需 force | pre_head 是 main 祖先；保全 31 文件全为 evidence/ 下 mobile 长按退役与面板标题两批截图，主仓不入库过程产物；无代码文件 |
| 11 | `.nebflow/worktrees/delegate-nebflow-89580610` | `delegate/delegate-nebflow-89580610` | `778e15513` → `dc4d05632` | 25 | 0 | 被取代 | 已删 · 无需 force | pre_head 是 main 祖先；保全 7 代码文件 vs main：1 个内容一致(mobileLayer.css 等已在 main)、6 个不同且 6 个 main 均有晚于 2026-11-02 的提交；另 18 张 evidence png 为索引残留冲突态 |
| 12 | `.nebflow/worktrees/delegate-nebflow-9b828eae` | `delegate/delegate-nebflow-9b828eae` | `8443d81b5` → `ef91dca17` | 2232 | 45 | 被取代 | 已删 · `--force`（丢 45 判废文件 / 111.4 MiB） | pre_head 非 main 祖先(ahead=2, cherry +2/-0)；该树独有文件 37 个全部为主仓删除的旧件(main 现存 seed/plugins 335 件为改名后的新族)；真新文件 0 个；其 ahead 提交仅「chore: untrack process artifacts」+ 一批准入盘点过程报告 |
| 13 | `.nebflow/worktrees/delegate-nebflow-ae1c2d58` | `delegate/delegate-nebflow-ae1c2d58` | `67df8918c` → `bc27158c3` | 951 | 29 | 被取代 | 已删 · `--force`（丢 29 判废文件 / 99.8 MiB） | pre_head 非 main 祖先(ahead=2, cherry +2/-0)；真新文件仅 1 个 tests/canvas-tabs-lww.spec.mjs(2026-10-25 wip 落盘)，主仓同类 bug 已用另一条路线解决：CanvasTabStoreSpec/CanvasTabTombstoneSpec/canvas-… |
| 14 | `.nebflow/worktrees/delegate-nebflow-bf049776` | `delegate/delegate-nebflow-bf049776` | `fc3c60dbf` → `22cf27045` | 28 | 0 | 已并入 | 已删 · 无需 force | pre_head 是 main 祖先；保全 28 文件全为 evidence/20261031_*mobile-msg-longpress-quote 五批截图，主仓不入库过程产物；无代码文件 |
| 15 | `.nebflow/worktrees/delegate-nebflow-cd833b36` | `delegate/delegate-nebflow-cd833b36` | `80fbde315` → `d23bc58f1` | 53 | 0 | 被取代 | 已删 · 无需 force | pre_head 是 main 祖先；保全 31 代码文件 vs main：22 个内容与 main 完全一致(品牌批已吸收)、9 个不同且其中 8 个 main 有 2026-10-18~11-04 的更新提交(manifest/brand.conf/css/js 均被 main 后续改写)；主仓无缺失文件 |
| 16 | `.nebflow/worktrees/delegate-nebflow-d8f43803` | `delegate/delegate-nebflow-d8f43803` | `07096d73d` → `be70d031e` | 951 | 29 | 被取代 | 已删 · `--force`（丢 29 判废文件 / 99.8 MiB） | pre_head 非 main 祖先(ahead=8, cherry +8/-0)；该树独有文件 60 个全部为主仓删除的旧件；真新文件 0 个；ahead 提交为 CI 存量红分诊/停机保护落盘类，主仓已更好演进(876 个改动文件中 170 个 main 有更新提交) |
| 17 | `.nebflow/worktrees/delegate-nebflow-ea997ea1` | `delegate/delegate-nebflow-ea997ea1` | `c9ae8699a` → `3fbc368a6` | 72 | 0 | 已并入 | 已删 · 无需 force | pre_head 是 main 祖先；保全 72 文件全为 evidence/20261102_*mobile-panel-title 两批截图，主仓不入库过程产物；无代码文件 |
| 18 | `.nebflow/worktrees/delegate-nebflow-f6a21b25` | `delegate/delegate-nebflow-f6a21b25` | `3ff12e1f3` → `6bf401929` | 3477 | 85 | 需合并 | 已删 · `--force`（丢 85 判废文件 / 111.8 MiB） | 对 `a0597efdd` 复核：cherry +7（patch 不在 main）；own delta 8 个代码文件 / 新增 116 行，其中 3 行已在 main（2.6%）；main 缺 3 文件（如 `src/main/resources/web/css/mobile-todo-apple.css`）；分片原文：pre_head 非 main 祖先(ahead=6, cherry +6/-0)；主仓从未有过且无同名孪生的真新文件 3 个：src/main/resources/web/js/… |
| 19 | `.nebflow/worktrees/delegate-nebflow-fbe4618c` | `delegate/delegate-nebflow-fbe4618c` | `d11a3a0a0` → `b0da1fca1` | 1060 | 30 | 被取代 | 已删 · `--force`（丢 30 判废文件 / 99.8 MiB） | pre_head 非 main 祖先(ahead=3, cherry +3/-0)；该树独有文件 66 个全部为主仓删除的旧件；真新文件 0 个；ahead 提交为全量 sbt 三跑实录报告 + NfFileDenyLogSpec realpath 重锚，主仓 658 个改动文件中 177 个已有更新提交 |
| 20 | `.nebflow/worktrees/delegate-nebflow-03e396b4` | `delegate/delegate-nebflow-03e396b4` | `9a1e6a8e4` → `36f9f65ae` | 7 | 0 | 需合并 | 已删 · 无需 force | 对 `a0597efdd` 复核：cherry +1（patch 不在 main）；own delta 7 个代码文件 / 新增 394 行，其中 152 行已在 main（38.6%）；main 缺 2 文件（如 `src/main/scala/nebflow/core/presets/PresetChainRegistry.scala`）；分片原文：cherry=+；main 无 PresetChainRegistry.scala(132 行独有)、PresetChainMigrationSpec.scala(185 行独有)… |
| 21 | `.nebflow/worktrees/delegate-nebflow-147dadf8` | `delegate/delegate-nebflow-147dadf8` | `b1cbd0e18` → `f5927cd64` | 1 | 0 | 被取代 | 已删 · 无需 force | cherry=-；内容为 evidence/20261031_dd-selector-reskin/ 8 张 PNG；main 不 track evidence/(main .gitignore 有 /evidence/，1a42c7128 2026-11-02 purge tracking) |
| 22 | `.nebflow/worktrees/delegate-nebflow-18d8fe69` | `delegate/delegate-nebflow-18d8fe69` | `a17090124` → `184d0f521` | 1 | 0 | 需合并 | 已删 · 无需 force | 对 `a0597efdd` 复核：cherry +1（patch 不在 main）；own delta 2 个代码文件 / 新增 0 行，其中 0 行已在 main；main 缺 2 文件（如 `.probe/probe-history.mjs`）；分片原文：cherry=+；.probe/probe-history.mjs + .probe/probe-live-panel.mjs(共 148 行)main 无，且 .probe 不在… |
| 23 | `.nebflow/worktrees/delegate-nebflow-2637ab1d` | `delegate/delegate-nebflow-2637ab1d` | `6e2b16e5d` → `4dda2ede6` | 21 | 0 | 需合并 | 已删 · 无需 force | 对 `a0597efdd` 复核：cherry +1（patch 不在 main）；own delta 1 个代码文件 / 新增 0 行，其中 0 行已在 main；main 缺 1 文件（如 `tests/_spec-floor-sweep.sh`）；分片原文：cherry=+；tests/_spec-floor-sweep.sh main 无(tests/ 受 track 且未被 ignore)；其余 20 行为 evidence/ 截… |
| 24 | `.nebflow/worktrees/delegate-nebflow-432df36a` | `delegate/delegate-nebflow-432df36a` | `83dba7890` → `15ec4f369` | 9 | 0 | 需合并 | 已删 · 无需 force | 对 `a0597efdd` 复核：cherry +1（patch 不在 main）；own delta 1 个代码文件 / 新增 9 行，其中 3 行已在 main（33.3%）；main 文件都在但新增内容只有部分命中 ⇒ 疑与 main 独立实现重叠；分片原文：cherry=+；tests/mobile-dock.spec.mjs 新增 9 行中 6 行不在 main；另 8 张 evidence/20261031_mobile-dock… |
| 25 | `.nebflow/worktrees/delegate-nebflow-5017517a` | `delegate/delegate-nebflow-5017517a` | `982625c1b` → `d910003fa` | 6 | 0 | 需合并 | 已删 · 无需 force | 对 `a0597efdd` 复核：cherry +1（patch 不在 main）；own delta 6 个代码文件 / 新增 631 行，其中 217 行已在 main（34.4%）；main 文件都在但新增内容只有部分命中 ⇒ 疑与 main 独立实现重叠；分片原文：cherry=+；新增行不在 main：MergeQueue 131/165、MergeQueueChannel 136/166、MergeQueueBootSweep 33/36… |
| 26 | `.nebflow/worktrees/delegate-nebflow-5fa55cde` | `delegate/delegate-nebflow-5fa55cde` | `b87dfc3da` → `faed9ca5f` | 11 | 0 | 需合并 | 已删 · 无需 force | 对 `a0597efdd` 复核：cherry +1（patch 不在 main）；own delta 11 个代码文件 / 新增 114 行，其中 18 行已在 main（15.8%）；main 缺 1 文件（如 `src/main/scala/nebflow/core/delegate/MergeWindowCare.scala`）；分片原文：cherry=+；main 无 MergeWindowCare.scala(6 行)；不在 main 的新增行：SingleInstanceGuard 4/4、StaticRout… |
| 27 | `.nebflow/worktrees/delegate-nebflow-6e4185d6` | `delegate/delegate-nebflow-6e4185d6` | `9cdfb54c1` → `f83095e9a` | 5 | 0 | 被取代 | 已删 · 无需 force | 【重验改判】main 侧已有等价实现：5/5 文件在 main，175 新增行中 170 行逐字已存在（97.1%），main 上同文件末次提交 da6aaf230/4d944ec91(2026-11-03) 晚于树末次 2026-10-22 ⇒ 由 `需合并` 改判；原文判词：需合并 |
| 28 | `.nebflow/worktrees/delegate-nebflow-7966cb44` | `delegate/delegate-nebflow-7966cb44` | `8899898c1` → `7a2c4141c` | 1 | 0 | 需合并 | 已删 · 无需 force | 对 `a0597efdd` 复核：cherry +1（patch 不在 main）；own delta 1 个代码文件 / 新增 15 行，其中 1 行已在 main（6.7%）；main 文件都在但新增内容只有部分命中 ⇒ 疑与 main 独立实现重叠；分片原文：cherry=+；chatSearch.js 新增 15 行中 14 行不在 main |
| 29 | `.nebflow/worktrees/delegate-nebflow-824276f8` | `delegate/delegate-nebflow-824276f8` | `838d3f02c` → `d474e8473` | 15 | 0 | 被取代 | 已删 · 无需 force | cherry=+；12 张 evidence PNG + reports/20261031_liquid-glass-final/(18 PNG)；main 不 track /evidence/ 与 /reports/(.gitignore + 1a42c7128 2026-11-02) |
| 30 | `.nebflow/worktrees/delegate-nebflow-8b2f50d3` | `delegate/delegate-nebflow-8b2f50d3` | `3f83e3367` → `ca51e555f` | 22 | 0 | 被取代 | 已删 · 无需 force | cherry=+；19 张 evidence PNG + reports/20261031_104500_liquid-glass-v2-impl__delegate-nebflow-8b2f50d3.md + reports/20261031_liquid-glass-v2-impl/ 证据目录；main 不 track /eviden… |
| 31 | `.nebflow/worktrees/delegate-nebflow-9fa60def` | `delegate/delegate-nebflow-9fa60def` | `1fbfc5c91` → `31914f5b5` | 2 | 0 | 被取代 | 已删 · 无需 force | cherry=-；2 个 evidence 目录(20261031_130407 / 20261031_140203 长按引用)共 14 PNG；main 不 track evidence/ |
| 32 | `.nebflow/worktrees/delegate-nebflow-b21e2edf` | `delegate/delegate-nebflow-b21e2edf` | `b5306a471` → `2bd2f2d9e` | 4 | 0 | 被取代 | 已删 · 无需 force | cherry=+；evidence/20261023_capsule-six/shots/ 4 张 PNG；main 不 track evidence/ |
| 33 | `.nebflow/worktrees/delegate-nebflow-bf078e5e` | `delegate/delegate-nebflow-bf078e5e` | `3e5e0a4db` → `a3685eaf9` | 5 | 0 | 被取代 | 已删 · 无需 force | cherry=+；evidence/search-scope/api/ 下 README.txt + 4 个抓取 json；main 不 track evidence/ |
| 34 | `.nebflow/worktrees/delegate-nebflow-ce004aa9` | `delegate/delegate-nebflow-ce004aa9` | `a68b7ec74` → `bd61454df` | 3 | 0 | 被取代 | 已删 · 无需 force | cherry=+；evidence/20261029_term-citation-batch2/shots/ 3 张 PNG；main 不 track evidence/ |
| 35 | `.nebflow/worktrees/delegate-nebflow-dcc13390` | `delegate/delegate-nebflow-dcc13390` | `e4507ad01` → `036aa33a1` | 1 | 0 | 被取代 | 已删 · 无需 force | cherry=+；git log --oneline main -- docs/20261029_175151_plugin-repo-registry.md → 5ba295d2a 2026-11-02 chore: stop tracking evidence/ reports/ and docs/(删除该文件，晚于本树末次提交 20… |
| 36 | `.nebflow/worktrees/delegate-nebflow-eed3ad45` | `delegate/delegate-nebflow-eed3ad45` | `fdcf8d568` → `eb472e46c` | 2 | 0 | 被取代 | 已删 · 无需 force | cherry=-；2 个 evidence 目录(20261101_094854_mobile-panel-title 24 PNG + 20261101_095035_pop-footer-family 14 文件)；main 不 track evidence/ |
| 37 | `.nebflow/worktrees/delegate-nebflow-f6d3906f` | `delegate/delegate-nebflow-f6d3906f` | `dda644590` → `c6b0cb2f5` | 4 | 0 | 被取代 | 已删 · 无需 force | cherry=+；evidence/20261023_queue-restore/after/ 4 张 PNG；main 不 track evidence/ |
| 38 | `.nebflow/worktrees/delegate-nebflow-fbf9b18e` | `delegate/delegate-nebflow-fbf9b18e` | `b9123bf2b` → `4f34355cb` | 1 | 0 | 被取代 | 已删 · 无需 force | cherry=-；evidence/20261031_052537_mobile-msg-longpress-quote/ 7 PNG；main 不 track evidence/ |
| 39 | `.nebflow/worktrees/delegate-nebflow-064972f7` | `delegate/delegate-nebflow-064972f7` | `55aa24bc2` → `2c07f673c` | 7 | 0 | 需合并 | 已删 · 无需 force | 对 `a0597efdd` 复核：cherry +1（patch 不在 main）；own delta 7 个代码文件 / 新增 499 行，其中 164 行已在 main（32.9%）；main 文件都在但新增内容只有部分命中 ⇒ 疑与 main 独立实现重叠；分片原文：7 个改动文件在 main 的 blob 全部等于本树基线版本(55aa24bc)，即改动未落 main；新增行命中 main 仅 152/499=30%；cherry main … |
| 40 | `.nebflow/worktrees/delegate-nebflow-164aec7e` | `delegate/delegate-nebflow-164aec7e` | `838d3f02c` → `9be46c24c` | 24 | 0 | 不确定 | 已删 · 无需 force | 对 `a0597efdd` 复核：cherry +1（patch 不在 main）；own delta 24 个代码文件 / 新增 1103 行，其中 790 行已在 main（71.6%）；main 缺 1 文件（如 `src/test/scala/nebflow/agent/DelegateStandbyLifecycleSpec.scala`）；与基座移动面交集 M1:2 —— 经查该批对同文件为字面量→`var(--ds-*)` 等值令牌迁移（非语义取代）；分片原文：新增行命中 main 719/1103=65%；main 的 DelegateStandbyCare.scala 已被 2026-11-03 提交延续(log: d2d1a16a6… |
| 41 | `.nebflow/worktrees/delegate-nebflow-1ac734ef` | `delegate/delegate-nebflow-1ac734ef` | `d8ffa1394` → `7b6e47cd9` | 12 | 0 | 已并入 | 已删 · 无需 force | git merge-base --is-ancestor pre_head main = YES，rev-list --count main..pre = 0 ⇒ 本树工作历史全部已是 main 祖先(cherry 等价全 -)；本批新增 diff 12 件全为 evidence/20261028_141517_canvas-stack-… |
| 42 | `.nebflow/worktrees/delegate-nebflow-296aafdb` | `delegate/delegate-nebflow-296aafdb` | `c9233b0ca` → `f643598da` | 1457 | 44 | 被取代 | 已删 · `--force`（丢 44 判废文件 / 99.8 MiB） | 本树独有实质提交 43190b324(2026-10-29 词条系统第三批·早期变体，24 文件无 MdHtmlGate 单点)；main 2650bd2fe(2026-10-29「判据单点 MdHtmlGate + 三处接入点」)采的是 865a936b 的单点版，main 的 MdHtmlGate.scala blob=93d0711… |
| 43 | `.nebflow/worktrees/delegate-nebflow-4398a05f` | `delegate/delegate-nebflow-4398a05f` | `4207dc263` → `e8eafddf8` | 945 | 29 | 被取代 | 已删 · `--force`（丢 29 判废文件 / 99.8 MiB） | main 的 StaticRoutes.scala 已含 immutable 长缓存头 10 处(⊇ 本树 7 处语义)，且 main 在该文件上的 bbfaae14d(2026-11-01)/2e6a8211f(2026-10-29) 晚于本树实质提交 bcddc32a5(2026-10-24「console-slow 立即档④」)；m… |
| 44 | `.nebflow/worktrees/delegate-nebflow-5856419d` | `delegate/delegate-nebflow-5856419d` | `c77836cdf` → `03a4e15ae` | 7 | 0 | 已并入 | 已删 · 无需 force | merge-base --is-ancestor YES + main..pre 计数 0 ⇒ 工作历史已全并入；cherry main HEAD=1-(本批保全提交的 patch 在 main 历史中同构：该证据目录曾被跟踪，后被 5ba295d2a 撤索引)；diff main..HEAD 只有 7 件 evidence/202610… |
| 45 | `.nebflow/worktrees/delegate-nebflow-5fb4d5e4` | `delegate/delegate-nebflow-5fb4d5e4` | `13b6864be` → `669502f36` | 4 | 0 | 已并入 | 已删 · 无需 force | ancestor YES + ahead 0 ⇒ cherry 等价全 -；本批 diff 仅 4 件 evidence/20261023_queue-restore/after/Q-queuebar-rows-*.png 截图更新(main 无 evidence 树，按 ruling 不入 main) |
| 46 | `.nebflow/worktrees/delegate-nebflow-7297e18b` | `delegate/delegate-nebflow-7297e18b` | `c5fa6815b` → `53827b340` | 21 | 0 | 已并入 | 已删 · 无需 force | ancestor YES + ahead 0 ⇒ cherry 等价全 -；21 件全为 evidence/20261102_042328_mobile-longpress-retire + evidence/20261102_042405_pop-footer-family 证据件 |
| 47 | `.nebflow/worktrees/delegate-nebflow-7c249f3f` | `delegate/delegate-nebflow-7c249f3f` | `410e48239` → `d83e3025b` | 4 | 0 | 已并入 | 已删 · 无需 force | ancestor YES + ahead 0 ⇒ cherry 等价全 -；4 件为 evidence/20261022_wiki-graph-paragraph/shots/paragraph-{highlight,hover}-{dark,light}.png 截图更新 |
| 48 | `.nebflow/worktrees/delegate-nebflow-865a936b` | `delegate/delegate-nebflow-865a936b` | `88fbb65c8` → `45ea64724` | 1462 | 44 | 已并入 | 已删 · `--force`（丢 44 判废文件 / 99.8 MiB） | 本树实质提交 483a9bc4d 引入的 src/main/scala/nebflow/core/wiki/MdHtmlGate.scala 在 main 的 blob 同为 93d071195c0e3a687fb556ed0fcc88523e0018db(逐字节相同)，由 main 2650bd2fe(2026-10-29) 落地 ⇒ … |
| 49 | `.nebflow/worktrees/delegate-nebflow-94fe78c9` | `delegate/delegate-nebflow-94fe78c9` | `102ab6784` → `5f989f000` | 2309 | 46 | 被取代 | 已删 · `--force`（丢 46 判废文件 / 111.4 MiB） | main dd78d5d05(2026-11-01「统一按钮+玻璃设计实施批——全站玻璃单源包 glass.css」)与本树 e61c2d7d0(2026-11-01「统一玻璃单源包·阶段〇+一」)同批异路线：main 已无 src/main/resources/web/js/liquidGlass.js，改以 css/glass.css… |
| 50 | `.nebflow/worktrees/delegate-nebflow-a26da039` | `delegate/delegate-nebflow-a26da039` | `a5fb3e7b3` → `2fe301ca1` | 17 | 0 | 已并入 | 已删 · 无需 force | 新增行命中 main 636/675=94%，且 src/test/scala/nebflow/llm/NetworkUnreachableWaitSpec.scala 的 blob 与 main 完全相同；仅 RestartingWatchdogSpec 160/165、AgentActor 22/28 等少量行差(main 侧更新) |
| 51 | `.nebflow/worktrees/delegate-nebflow-bfad8ba9` | `delegate/delegate-nebflow-bfad8ba9` | `d52d402ad` → `226c7f693` | 7 | 0 | 需合并 | 已删 · 无需 force | 对 `a0597efdd` 复核：cherry +1（patch 不在 main）；own delta 7 个代码文件 / 新增 104 行，其中 38 行已在 main（36.5%）；main 缺 1 文件（如 `src/test/scala/nebflow/core/project/MsgTokenDeliveryPointsSpec.scala`）；分片原文：新增行命中 main 仅 29/104=28%；src/test/scala/nebflow/core/project/MsgTokenDeliveryPointsSpec.sca… |
| 52 | `.nebflow/worktrees/delegate-nebflow-bd39ddf7` | `delegate/delegate-nebflow-bd39ddf7` | `ae30d1caf` → `89f03b10b` | 13 | 0 | 已并入 | 已删 · 无需 force | ancestor YES + ahead 0 ⇒ cherry 等价全 -；13 件=7 张既有截图更新(canvas-toolbar-overlap 3 + capsule-six 4) + 6 张 evidence/20261025_103955_canvas-stack-impl 新证据(main 无 evidence 树) |
| 53 | `.nebflow/worktrees/delegate-nebflow-d2fc050f` | `delegate/delegate-nebflow-d2fc050f` | `dc7c965bb` → `82e41d406` | 4 | 0 | 已并入 | 已删 · 无需 force | ancestor YES + ahead 0 ⇒ cherry 等价全 -；4 件 evidence/20261030_canvas-card-fan/shots/1-fan-open-dark/2-rest-dark/3-fan-open-light/4-rest-light.png |
| 54 | `.nebflow/worktrees/delegate-nebflow-e0b069db-c` | `delegate/delegate-nebflow-e0b069db-c` | `4bc7a74a0` → `55d56c721` | 190 | 1 | 已并入 | 已删 · `--force`（丢 1 判废文件 / 88.9 MiB） | 本树实质提交 dd9958b5c(2026-10-19「面C 追加批 TUI/GUI 显示设计」)的 2 份 reports 内容在 main 历史中由 3c0d8595b(2026-10-19「外部执行器任务可观测面设计批」)落地，后被 5ba295d2a(2026-11-02 index-only 撤跟踪)移除 ⇒ cherry 2+… |
| 55 | `.nebflow/worktrees/delegate-nebflow-f1fc2f78` | `delegate/delegate-nebflow-f1fc2f78` | `ea8967a51` → `629e91ba2` | 96 | 0 | 已并入 | 已删 · 无需 force | ancestor YES + ahead 0 ⇒ cherry 等价全 -；96 件为 evidence/20261102_021737_mobile-panel-title + evidence/20261102_023456_mobile-panel-title 证据件 |
| 56 | `.nebflow/worktrees/delegate-nebflow-f7c28d49` | `delegate/delegate-nebflow-f7c28d49` | `587db0035` → `311ac3278` | 7 | 0 | 已并入 | 已删 · 无需 force | ancestor YES + ahead 0；cherry main HEAD=1-(同 5856419d：该证据目录内容曾在 main 历史内，现按 5ba295d2a 撤索引)；diff main..HEAD 仅 7 件 evidence/20261031_155058_mobile-msg-longpress-quote 证据件 |
| 57 | `.nebflow/worktrees/delegate-nebflow-fe4f891e` | `delegate/delegate-nebflow-fe4f891e` | `8d348a82c` → `f8eaea10c` | 4 | 0 | 需合并 | 已删 · 无需 force | 对 `a0597efdd` 复核：cherry +1（patch 不在 main）；own delta 3 个代码文件 / 新增 149 行，其中 17 行已在 main（11.4%）；main 文件都在但新增内容只有部分命中 ⇒ 疑与 main 独立实现重叠；分片原文：新增行命中 main 仅 13/149=9%；HealthRoutes.scala 10/70、HealthRoutesSpec.scala 3/69、GatewayMain.sc… |
| 58 | `.nebflow/worktrees/delegate-nebflow-09fffbda` | `delegate/delegate-nebflow-09fffbda` | `207cf379a` → `cf6f3eb64` | 28 | 0 | 被取代 | 已删 · 无需 force | cherry=+(1) rev-list main..HEAD=1 diff-main..HEAD=3271 files; pre_head 207cf379a 已是 main 祖先(唯一未并入=本批保全提交); 未并入内容全为 evidence/ 截图 — main 5ba295d2a(2026-11-02 16:20) 停跟踪进程产物… |
| 59 | `.nebflow/worktrees/delegate-nebflow-1818bb74` | `delegate/delegate-nebflow-1818bb74` | `0acec2b53` → `3d0bcf2dc` | 8 | 0 | 被取代 | 已删 · 无需 force | cherry=+(1) rev-list=1 diff=4838 files; pre_head 0acec2b53 已在 main；未并入=savaged 8 张 PNG(main-ignored)；main 5ba295d2a(2026-11-02 16:20) 晚于本树末次提交 2026-11-02 13:45 + .gitigno… |
| 60 | `.nebflow/worktrees/delegate-nebflow-1cb7b55c` | `delegate/delegate-nebflow-1cb7b55c` | `20fab2f4e` → `b25b4a49c` | 952 | 29 | 不确定 | 已删 · `--force`（丢 29 判废文件 / 99.8 MiB） | 对 `a0597efdd` 复核：cherry +3（patch 不在 main）；own delta 11 个代码文件 / 新增 121 行，其中 10 行已在 main（8.3%）；main 缺 1 文件（如 `src/test/scala/nebflow/core/entity/SubagentGeneralizationSpec.scala`）；分片原文：cherry=+(3) rev-list=3 diff=2941 files; 未并入提交=16c6f29b6(WIP stranded-batch guard 2026-10-2… |
| 61 | `.nebflow/worktrees/delegate-nebflow-298b4ddb` | `delegate/delegate-nebflow-298b4ddb` | `aed15b77c` → `b594b825d` | 57 | 0 | 需合并 | 已删 · 无需 force | 对 `a0597efdd` 复核：cherry +1（patch 不在 main）；own delta 1 个代码文件 / 新增 0 行，其中 0 行已在 main；main 缺 1 文件（如 `tests/flat-button-shots.mjs`）；分片原文：cherry=+(1) rev-list=1 diff=4263 files; main 缺 tests/flat-button-shots.mjs(638 非空新增行, git … |
| 62 | `.nebflow/worktrees/delegate-nebflow-43af6929` | `delegate/delegate-nebflow-43af6929` | `870929a69` → `29a43c0a3` | 24 | 0 | 被取代 | 已删 · 无需 force | cherry=+(1) rev-list=1 diff=4177 files; pre_head 870929a69 已在 main；未并入=24 张 evidence 截图；main 5ba295d2a(2026-11-02 16:20) 晚于本树末次提交 2026-11-02 08:26 + .gitignore:130 |
| 63 | `.nebflow/worktrees/delegate-nebflow-5ca27781` | `delegate/delegate-nebflow-5ca27781` | `ab50afa01` → `54d673de0` | 3050 | 63 | 被取代 | 已删 · `--force`（丢 63 判废文件 / 111.6 MiB） | cherry=+(4) rev-list=4 diff=4006 files; 未并入=3 个原提交(3aae478c7/68c0877c0 跨设备路径选择器 probe docs+evidence, ab50afa01 本树 untrack 保洁)+保全提交；docs/ 命中 .gitignore:39、evidence/ 命中 :13… |
| 64 | `.nebflow/worktrees/delegate-nebflow-6aae2fd2` | `delegate/delegate-nebflow-6aae2fd2` | `406de249c` → `c5b8b50f0` | 5 | 0 | 被取代 | 已删 · 无需 force | cherry=+(1) rev-list=1 diff=2921 files; 保全内容=chatQueue.js 排队呈现回退批(清空行级明细/collapseBtn/grip) + 4 张 PNG；main 4f15bab77(2026-10-23 09:42) 输入消息排队回退批=恢复行级明细，晚于本树末次提交 2026-10-22… |
| 65 | `.nebflow/worktrees/delegate-nebflow-76727b4a` | `delegate/delegate-nebflow-76727b4a` | `e12779943` → `20f9c6f1e` | 2 | 0 | 被取代 | 已删 · 无需 force | cherry=+(1) rev-list=1 diff=3149 files; pre_head e12779943 已在 main；未并入=2 张 console-single-instance 截图；main 5ba295d2a(2026-11-02 16:20) 晚于本树末次提交 2026-10-29 14:30 + .gitign… |
| 66 | `.nebflow/worktrees/delegate-nebflow-7ceafc0d` | `delegate/delegate-nebflow-7ceafc0d` | `5f8657cfc` → `98c205811` | 6 | 0 | 已并入 | 已删 · 无需 force | cherry 全 -(1 行 -) rev-list=1 diff=3061 files(diff 非空仅为 main 领先内容)；未并入=savaged evidence/20261030_130912_canvas-stack-impl/ 6 文件(main-ignored) |
| 67 | `.nebflow/worktrees/delegate-nebflow-87290cd9` | `delegate/delegate-nebflow-87290cd9` | `982625c1b` → `7398c8194` | 2 | 0 | 需合并 | 已删 · 无需 force | 对 `a0597efdd` 复核：cherry +1（patch 不在 main）；own delta 2 个代码文件 / 新增 87 行，其中 50 行已在 main（57.5%）；main 缺 1 文件（如 `src/test/scala/nebflow/agent/R1RespawnRefusalSpec.scala`）；分片原文：cherry=+(1) rev-list=1 diff=3168 files; main 缺 src/test/scala/nebflow/agent/R1RespawnRefus… |
| 68 | `.nebflow/worktrees/delegate-nebflow-991f7f0f` | `delegate/delegate-nebflow-991f7f0f` | `39e925e72` → `0ba25954c` | 41 | 0 | 被取代 | 已删 · 无需 force | cherry=+(1) rev-list=1 diff=4642 files; pre_head 39e925e72 已在 main；未并入=41 个 evidence/20261102_friend-remark/ 文件；main 5ba295d2a(2026-11-02 16:20) 晚于本树末次提交 2026-11-02 11:20… |
| 69 | `.nebflow/worktrees/delegate-nebflow-a77a4e54` | `delegate/delegate-nebflow-a77a4e54` | `bd07a09ec` → `45443d4f9` | 6 | 0 | 已并入 | 已删 · 无需 force | cherry 全 -(1 行 -) rev-list=1 diff=3278 files；未并入=savaged evidence/20261031_092249_canvas-stack-impl/ 6 文件(main-ignored) |
| 70 | `.nebflow/worktrees/delegate-nebflow-bd54b0ee` | `delegate/delegate-nebflow-bd54b0ee` | `8eb8110af` → `20877a956` | 2 | 0 | 被取代 | 已删 · 无需 force | cherry=+(1) rev-list=1 diff=3219 files; pre_head 8eb8110af 已在 main；未并入=2 张 human-face 明暗 PNG；main 5ba295d2a(2026-11-02 16:20) 晚于本树末次提交 2026-10-31 16:01 + .gitignore:130 |
| 71 | `.nebflow/worktrees/delegate-nebflow-c1da3caa` | `delegate/delegate-nebflow-c1da3caa` | `48347ef79` → `e27086a96` | 2404 | 47 | 被取代 | 已删 · `--force`（丢 47 判废文件 / 111.4 MiB） | cherry=+(3) rev-list=3 diff=3586 files; 未并入原提交=a70beb153(2026-11-01 17:37:55 feat(settings) 模型选择顺序节精简)；main c7dd22a97(2026-11-01 17:58:03 feat(settings) 模型选择顺序节精简批——三交互退役… |
| 72 | `.nebflow/worktrees/delegate-nebflow-d71b0ea4` | `delegate/delegate-nebflow-d71b0ea4` | `83dba7890` → `793709103` | 19 | 0 | 被取代 | 已删 · 无需 force | cherry=+(1) rev-list=1 diff=3521 files; 保全内容=删除 mobile/msgLongPress.js + chat/contextMenu/msgQuote/popArtifacts 联动 + 四轮 evidence；main da47f377a(2026-11-01 12:38) 手机端长按退役批… |
| 73 | `.nebflow/worktrees/delegate-nebflow-e7c1a868` | `delegate/delegate-nebflow-e7c1a868` | `901fba6fb` → `—` | 0 | 6 | 已并入 | 保留（live tree: unfinished delegate delegate-nebflow-e7c1a868 reportStatus=None age=10.1h） | cherry 无行(0 提交) rev-list main..HEAD=0 => HEAD 完全被 main 包含；diff=505 files 仅为 main 领先；保全 0 文件 |
| 74 | `.nebflow/worktrees/delegate-nebflow-f23846aa` | `delegate/delegate-nebflow-f23846aa` | `b3930acde` → `fac2c2290` | 19 | 0 | 需合并 | 已删 · 无需 force | 对 `a0597efdd` 复核：cherry +1（patch 不在 main）；own delta 16 个代码文件 / 新增 584 行，其中 133 行已在 main（22.8%）；main 缺 6 文件（如 `src/main/scala/nebflow/gateway/DecisionProbe.scala`）；与基座移动面交集 M1:3 —— 经查该批对同文件为字面量→`var(--ds-*)` 等值令牌迁移（非语义取代）；分片原文：cherry=+(1) rev-list=1 diff=3184 files; main 缺 src/main/scala/nebflow/gateway/DecisionProb… |
| 75 | `.nebflow/worktrees/delegate-nebflow-f8815d4c` | `delegate/delegate-nebflow-f8815d4c` | `072425363` → `9ad8b52fa` | 14 | 0 | 已并入 | 已删 · 无需 force | cherry 全 -(1 行 -) rev-list=1 diff=4189 files；未并入=savaged 两轮 mobile-longpress-retire evidence 14 文件(main-ignored) |
| 76 | `.nebflow/worktrees/delegate-nebflow-0c50686f` | `delegate/delegate-nebflow-0c50686f` | `bb03d7680` → `93e56f4f7` | 2289 | 46 | 不确定 | 已删 · `--force`（丢 46 判废文件 / 111.4 MiB） | 对 `a0597efdd` 复核：cherry +3；该树 own delta 无代码文件（纯 evidence/reports 归档），不确定点只在归档去向、不在代码面；分片原文：cherry main HEAD 全 3 个加号；rev-list --count main..HEAD=3；diff --stat main..HEAD=3477 文件 +184… |
| 77 | `.nebflow/worktrees/delegate-nebflow-183b016d` | `delegate/delegate-nebflow-183b016d` | `56c7f8896` → `a91fc4c6f` | 3 | 0 | 需合并 | 保留（live tree: unfinished delegate delegate-nebflow-183b016d reportStatus=None age=5.7h） | 对 `a0597efdd` 复核：cherry +1（patch 不在 main）；own delta 3 个代码文件 / 新增 10 行，其中 0 行已在 main（0.0%）；main 文件都在但新增内容只有部分命中 ⇒ 疑与 main 独立实现重叠；分片原文：cherry +1；diff --stat main..HEAD=21 文件 +86/-2547；3 个 JS 逐对比 main tip blob：socialSearch.js … |
| 78 | `.nebflow/worktrees/delegate-nebflow-1f4dbedf` | `delegate/delegate-nebflow-1f4dbedf` | `83dba7890` → `7569945f6` | 3 | 0 | 被取代 | 已删 · 无需 force | cherry +1；3 张 png 均为 evidence/20261031_bg-badge-homecoming__delegate-nebflow-7f69c44f/shots 下的重渲染；main 该路径 0 命中（bf8489d9f 2026-10-31 23:55 徽标批之后被 5ba295d2a 停跟踪）；main 5ba2… |
| 79 | `.nebflow/worktrees/delegate-nebflow-2be86ba4` | `delegate/delegate-nebflow-2be86ba4` | `838d3f02c` → `80c971bb5` | 16 | 0 | 被取代 | 已删 · 无需 force | cherry +1；唯一 porcelain 项为未跟踪目录 reports/20261031_liquid-glass-final/（16 文件 2.7MB）→ 已整目录保全；main 5ba295d2a(2026-11-02 16:20:38)「chore: stop tracking evidence/ reports/ and d… |
| 80 | `.nebflow/worktrees/delegate-nebflow-48dfd701` | `delegate/delegate-nebflow-48dfd701` | `3c01cc83d` → `7657595d0` | 9 | 0 | 被取代 | 已删 · 无需 force | cherry +1；9 张 settings-panel-fourfix 截图(imagegen-two-forms 6 + provider-test-modal 3)；main 该路径 0 命中——末次触碰为 af3d583b0(四fix LIVE 验收证据)、随后 5ba295d2a 停跟踪；main 5ba295d2a(2026-… |
| 81 | `.nebflow/worktrees/delegate-nebflow-5d5aa2be` | `delegate/delegate-nebflow-5d5aa2be` | `00c9eb926` → `eb5b90581` | 52 | 0 | 被取代 | 已删 · 无需 force | cherry +1；todoStore.js(+280/-42) 走 localStorage 持久化腿(改动后 localStorage 命中 5 处)，main 0d9b1b874(2026-11-04, feat(todo): todoStore 换血为后端真源适配器) 晚于该树末次活动 2026-11-02 12:01，且 mai… |
| 82 | `.nebflow/worktrees/delegate-nebflow-6c055b42` | `delegate/delegate-nebflow-6c055b42` | `c8dd31c37` → `83033d23e` | 4 | 0 | 被取代 | 已删 · 无需 force | cherry +1；4 张 evidence/20261023_queue-restore/after 截图；main 该路径末次触碰 2e6fb73f0(2026-10-28 11:59 queuedirect 批) 之后 5ba295d2a 停跟踪，现 0 命中；main 5ba295d2a(2026-11-02 16:20:38)「… |
| 83 | `.nebflow/worktrees/delegate-nebflow-76aa2f3c` | `delegate/delegate-nebflow-76aa2f3c` | `4f55e958c` → `—` | 0 | 33 | 已并入 | 已删 · `--force`（丢 33 判废文件 / 0.3 MiB） | cherry main HEAD 零输出、rev-list --count main..HEAD=0、工作区 porcelain 仅 scratch/（全判废）⇒ 无任何待并内容；main 5ba295d2a(2026-11-02 16:20:38)「chore: stop tracking evidence/ reports/ and … |
| 84 | `.nebflow/worktrees/delegate-nebflow-7e5196ed` | `delegate/delegate-nebflow-7e5196ed` | `3a836d6d6` → `8dc459223` | 18 | 0 | 被取代 | 已删 · 无需 force | cherry +1；3 个 evidence/20261028_0(81433, 85614, 90328)_canvas-stack-impl/ 目录共 18 文件；main 0 命中——main 侧同批由 b3a943e05「chore(merge-hygiene): absorb untracked generated artifa… |
| 85 | `.nebflow/worktrees/delegate-nebflow-87cbdee0` | `delegate/delegate-nebflow-87cbdee0` | `c3b154ccd` → `5573d40ae` | 6 | 0 | 被取代 | 已删 · 无需 force | cherry +1；evidence/20261031_140143_canvas-stack-impl/ 6 文件；main 同路径由 0e7e417c6「absorb untracked generated artifacts」吸收过、5ba295d2a 停跟踪后 0 命中；main 5ba295d2a(2026-11-02 16:2… |
| 86 | `.nebflow/worktrees/delegate-nebflow-9a522ebe` | `delegate/delegate-nebflow-9a522ebe` | `9f345de57` → `b34fd4cc9` | 10 | 0 | 被取代 | 已删 · 无需 force | cherry +1；4 张 evidence/20261023_capsule-six/shots 重渲染 + evidence/20261025_053138_canvas-stack-impl/ 6 文件；main 侧 c3b154ccd(2026-10-31 22:09:28)=本树父提交已「absorb spec-regenera… |
| 87 | `.nebflow/worktrees/delegate-nebflow-add4c91e` | `delegate/delegate-nebflow-add4c91e` | `db0068003` → `df3c3eb22` | 48 | 0 | 被取代 | 已删 · 无需 force | cherry +1；8 个代码文件 + 5 个证据目录(39 文件) + mediaFooter.js；main da47f377a(2026-11-01, feat(mobile): 手机端长按退役批——消息长按菜单整条退役 + 文件/图片消息补 footer 信息条) 晚于该树末次活动 2026-10-31 22:40 且同批同意图：… |
| 88 | `.nebflow/worktrees/delegate-nebflow-bd7a0f1c` | `delegate/delegate-nebflow-bd7a0f1c` | `c0abd6632` → `208a1d228` | 952 | 29 | 不确定 | 已删 · `--force`（丢 29 判废文件 / 99.8 MiB） | 对 `a0597efdd` 复核：cherry +3（patch 不在 main）；own delta 8 个代码文件 / 新增 1536 行，其中 1412 行已在 main（91.9%）；main 文件都在但新增内容只有部分命中 ⇒ 疑与 main 独立实现重叠；分片原文：cherry +1；diff main..HEAD 3168 文件(基数噪声)；952 个保全路径逐条对 main tip → 0 命中，内容 main 完全没有；main 5ba… |
| 89 | `.nebflow/worktrees/delegate-nebflow-c6ce9f87` | `delegate/delegate-nebflow-c6ce9f87` | `eeb933e2d` → `9cdd4725a` | 952 | 29 | 不确定 | 已删 · `--force`（丢 29 判废文件 / 99.8 MiB） | 对 `a0597efdd` 复核：cherry +3（patch 不在 main）；own delta 4 个代码文件 / 新增 355 行，其中 67 行已在 main（18.9%）；main 缺 1 文件（如 `src/test/scala/nebflow/core/executor/ExternalSessionRunnerStreamSpec.scala`）；分片原文：cherry +1；952 个保全路径对 main tip 0 命中；main 5ba295d2a(2026-11-02 16:20:38)「chore: stop trackin… |
| 90 | `.nebflow/worktrees/delegate-nebflow-d78d3e3f` | `delegate/delegate-nebflow-d78d3e3f` | `cf8f08b09` → `87474d048` | 1 | 0 | 被取代 | 已删 · 无需 force | cherry +1；docs/design/20261028_canvas-ask-mode-tokens.zh-CN.md (+136/-83)；main 同路径末次修订 caee1c66f(2026-10-28 23:16:51, docs(ask): v2.1 简化修订回写) 晚于该树末次活动 2026-10-28 18:18:20… |
| 91 | `.nebflow/worktrees/delegate-nebflow-e90a4202` | `delegate/delegate-nebflow-e90a4202` | `588b0dc09` → `0a897a943` | 4 | 0 | 被取代 | 已删 · 无需 force | cherry +1；与 6c055b42 同 4 张 evidence/20261023_queue-restore/after 截图（另一路重渲染）；main 该路径末次 2e6fb73f0(2026-10-28 11:59) 后 5ba295d2a 停跟踪、现 0 命中；main 5ba295d2a(2026-11-02 16:20:… |
| 92 | `.nebflow/worktrees/delegate-nebflow-f30e15f7` | `delegate/delegate-nebflow-f30e15f7` | `9d172faec` → `921f687d0` | 956 | 29 | 不确定 | 已删 · `--force`（丢 29 判废文件 / 99.8 MiB） | 对 `a0597efdd` 复核：cherry +3（patch 不在 main）；own delta 7 个代码文件 / 新增 1178 行，其中 1159 行已在 main（98.4%）；main 文件都在但新增内容只有部分命中 ⇒ 疑与 main 独立实现重叠；与基座移动面交集 M1:1 —— 经查该批对同文件为字面量→`var(--ds-*)` 等值令牌迁移（非语义取代）；分片原文：cherry +1；956 个保全路径对 main tip 0 命中；main 5ba295d2a(2026-11-02 16:20:38)「chore: stop trackin… |
| 93 | `.nebflow/worktrees/delegate-nebflow-f91c3ca6` | `delegate/delegate-nebflow-f91c3ca6` | `e9d48a0df` → `770006659` | 12 | 0 | 被取代 | 已删 · 无需 force | cherry +1；10 张 evidence/ask-input-composer 截图(chromium/webkit × dark/light)；main 该路径末次触碰 e9d48a0df(2026-11-01 19:33:12)=本树父提交（同批 feat(web): ask 输入行为包批），随后 5ba295d2a 停跟踪、现… |
| 94 | `.nebflow/worktrees/delegate-nebflow-92944aba` | `delegate/delegate-nebflow-92944aba` | `58edd5549` → `—` | 0 | 0 | 已并入 | 已删 · 无需 force | 原文判词：不需要（WIP 参考快照，main 已含等价面） |
| 95 | `.nebflow/worktrees/delegate-nebflow-98f9ca81` | `delegate/delegate-nebflow-98f9ca81` | `5c046b9ee` → `—` | 0 | 0 | 需合并 | 保留（live tree: unfinished delegate delegate-nebflow-98f9ca81 reportStatus=None age=8.5h） | 对 `a0597efdd` 复核：cherry +2（patch 不在 main）；own delta 31 个代码文件 / 新增 2001 行，其中 1186 行已在 main（59.3%）；main 文件都在但新增内容只有部分命中 ⇒ 疑与 main 独立实现重叠；原文判词：部分仍需要（19 文件 main 零等价接线）；不可原样 cherry-pick（7 文件冲突 + 5 文件会回退 main 已落地实现）；分片原文：porcelain=0 ⇒ 无可保全，diff pre_head..HEAD=0 行（对齐 0==0）；ahead=6（ef74c3f75 ff03bf890 6132990f4 … |
| 96 | `.nebflow/worktrees/delegate-nebflow-6110565f` | `-` | `ec4a7f96d` → `—` | 0 | 0 | 已并入 | 已删 · 无需 force | rev-list --count main..HEAD=0；merge-base --is-ancestor HEAD main=YES；cherry main HEAD 输出为空（main 已含该 HEAD） |
| 97 | `.nebflow/worktrees/tmp-bisect-63be` | `-` | `1a1cfe29c` → `—` | 0 | 0 | 已并入 | 已删 · 无需 force | rev-list --count main..HEAD=0；is-ancestor HEAD main=YES；cherry main HEAD 空 |
| 98 | `/private/tmp/capsule-before` | `-` | `73c305f81` → `—` | 0 | 0 | 已并入 | 已删 · 无需 force | rev-list --count main..HEAD=0；is-ancestor HEAD main=YES；cherry main HEAD 空 |
| 99 | `/private/tmp/g2-base-check` | `salvage/g2-base-check` | `bc437df64` → `af398c7f2` | 30 | 0 | 不确定 | 已删 · 无需 force | 对 `a0597efdd` 复核：cherry +1；该树 own delta 无代码文件（纯 evidence/reports 归档），不确定点只在归档去向、不在代码面；分片原文：cherry main HEAD=+af398c7f（main 无此 patch）；git diff --stat --no-renames pre_head..HEAD = 30… |
| 100 | `/private/tmp/lg-baseline` | `-` | `bc437df64` → `—` | 0 | 0 | 已并入 | 已删 · 无需 force | rev-list --count main..HEAD=0；is-ancestor HEAD main=YES；cherry main HEAD 空 |
| 101 | `/private/tmp/lg-current` | `salvage/lg-current` | `312d6fb28` → `fdd1a4f42` | 30 | 0 | 不确定 | 已删 · 无需 force | 对 `a0597efdd` 复核：cherry +1；该树 own delta 无代码文件（纯 evidence/reports 归档），不确定点只在归档去向、不在代码面；分片原文：cherry main HEAD=+fdd1a4f4（main 无此 patch）；pre_head..HEAD = 30 files changed；rev-list --cou… |
| 102 | `/private/tmp/np-baseline` | `-` | `c4ac5a816` → `—` | 0 | 0 | 已并入 | 已删 · 无需 force | rev-list --count main..HEAD=0；is-ancestor HEAD main=YES；cherry main HEAD 空；main..HEAD 481 files 系落后 main 185 提交所致 |
| 103 | `/private/tmp/verify-main-baseline` | `salvage/verify-main-baseline` | `7fabc3eb6` → `876be58d0` | 12 | 0 | 不确定 | 已删 · 无需 force | 对 `a0597efdd` 复核：cherry +1；该树 own delta 无代码文件（纯 evidence/reports 归档），不确定点只在归档去向、不在代码面；分片原文：cherry main HEAD=+876be58d（main 无此 patch）；pre_head..HEAD = 12 files changed；rev-list --cou… |
### §2b `--force` 留痕（19 棵，每棵路径 + 被丢弃文件清单与体积）

`--force` 只在「status 剩余全部是在该行 `junk_detail` 里登记过名字或体积的一次性产物」时使用。逐文件清单（路径 + 字节 + 登记理由）见 **`shards/force-discard-round2.tsv`**（716 行）。

| 树 | 丢弃文件 | 丢弃体积 | 主要构成（均已在 junk_detail 登记） |
|---|---|---|---|
| `delegate-nebflow-5ca27781` | 63 | 111.55 MiB | 58×`*.log` + 1×`*-probe*` 目录项 + 4×>5MiB（bundle 88.9 + 2 png + 12.1MB json） |
| `delegate-nebflow-f6a21b25` | 85 | 111.82 MiB | 81×`*.log` + bundle 88.9MiB（junk_detail 逐路径:体积登记） |
| `delegate-nebflow-c1da3caa` | 47 | 111.44 MiB | 42×`*.log` + 1×`*-probe*` + 4×>5MiB |
| `delegate-nebflow-0c50686f` | 46 | 111.43 MiB | bundle 93,199,372B + json 12,141,862B + 2 png + 42×`*.log` |
| `delegate-nebflow-94fe78c9` | 46 | 111.43 MiB | 同上（逐路径:体积登记） |
| `delegate-nebflow-9b828eae` | 45 | 111.42 MiB | 同上类 |
| `delegate-nebflow-296aafdb` | 44 | 99.84 MiB | 41×`*.log` 280KB + bundle + 2 png |
| `delegate-nebflow-865a936b` | 44 | 99.84 MiB | 同上 |
| `delegate-nebflow-76aa2f3c` | 33 | 0.28 MiB | `scratch/` 整目录（topdir 判废；509 文件含 node_modules，见 §5①） |
| `delegate-nebflow-fbe4618c` | 30 | 99.81 MiB | bundle + png + `*.log` |
| `delegate-nebflow-1cb7b55c` | 29 | 99.80 MiB | 26×`*.log` + 3×>5MiB |
| `delegate-nebflow-4398a05f` | 29 | 99.80 MiB | 26×`*.log` + bundle + 2 png |
| `delegate-nebflow-6cbd7d08` | 29 | 99.80 MiB | 逐路径:体积登记 29 项 |
| `delegate-nebflow-ae1c2d58` | 29 | 99.80 MiB | 同上 |
| `delegate-nebflow-bd7a0f1c` | 29 | 99.80 MiB | bundle + 2 png + 26×`*.log` |
| `delegate-nebflow-c6ce9f87` | 29 | 99.80 MiB | 同 `bd7a0f1c`（junk_detail 写「同 bd7a0f1c」并附同名体积） |
| `delegate-nebflow-d8f43803` | 29 | 99.80 MiB | 逐路径:体积登记 |
| `delegate-nebflow-f30e15f7` | 29 | 99.80 MiB | 同 `bd7a0f1c` |
| `delegate-nebflow-e0b069db-c` | 1 | 88.88 MiB | `backup-local-main-e718625e.bundle` 93,199,372B |
| **合计 19 棵** | **716** | **1,856.18 MiB** | 单文件最大 93.2 MiB（`.bundle` 本地备份，6 棵各一份） |

复核机制（删前逐棵跑）：①`git status --porcelain -uall` 每个残留文件必须命中该行 junk_detail 的「路径:体积 / basename / 已声明的模式（`*.log`、`*-probe*`、`scratch/`、`>5MiB`、`bundle`）」之一，**0 个未登记文件**（103/103 通过）；②残留件数 vs tsv 判废列：**722 == 722**；③活进程与 delegate 双路扫描见下；④`commit_sha` 非 `-` 的全部 `git cat-file -e <sha>^{commit}` 成功。

**复核失败而跳过删除的树：0 棵**（无「出现未登记新文件」）。**因活树跳过：3 棵**（`183b016d`、`98f9ca81`、`e7c1a868`，理由见 §5③）。

---

## §3 需合并清单（≤5 条，全部出自重验后结果）

按「改动面小 + 一句话产品价值清楚 + main 完全零等价」挑，**98f9ca81 按任务书纪律必列**。

| # | 分支 | 保全/未合 sha | 改了什么 | 一句话产品价值 | 为什么没自动合（交作者逐条裁定） |
|---|---|---|---|---|---|
| 1 | `delegate/delegate-nebflow-98f9ca81` | 未合提交 `5ae680e99`（无保全提交） | 26 文件 +1270/−70：三层数据根实现 + 账号层接线（`Main`/`cli/{GatewayClient,LoginCommand,PidMeta,ProcessManager}`/`core/daemon/*`/`core/hotrestart`/`core/secrets`/`gateway/*`/`neblink/*`/`Shared/InstanceScope` 等 19 个「接线面」文件） | 登录写 active-account 指针、登出清指针、owner 拒启、机器层文件搬家、端口按账号归属 —— **main 现在只有根面实现，这些调用点一个都没有**（当前 main `OwnerGuard` 仅 1 文件 2 命中且都是定义、`writeActiveAccountPointer`=0、`InstanceScope.machineRoot`=0） | 整提交 cherry-pick 会**回退 main 已落地且已绿的姊妹实现**；19 文件单独摘取才能零冲突。取舍属产品判断（见 §4） |
| 2 | `delegate/delegate-nebflow-6cbd7d08` | `7908982c4` → `4aceaa489` | 3 个 main 完全缺失的新文件：`src/main/resources/web/js/input/inputHistory.js`、`tests/input-history-attach-recall.spec.mjs`、`tests/shots-inputhist-attach.mjs`（另改 `chat.js`/`input.js`/`input.css`） | ↑ 键召回历史输入时**把附件（文本 + 附件描述符，缺失态可见）一并带回**，配网关「帧附件 path 直取腿」使历史召回重发零重传 | main 有 `input.js` 的 `state.inputHistory` 存储但**没有这个召回模块**（文件级 0 命中）。本轮统一不合 |
| 3 | `delegate/delegate-nebflow-f6a21b25` | `3ff12e1f3` → `6bf401929` | 3 个 main 完全缺失的新文件：`web/js/mobile/todoAppleFace.js`、`web/css/mobile-todo-apple.css`、`tests/mobile-todo-apple-shots.mjs`（共 8 个代码件，新增 116 行仅 3 行在 main） | 待办页按作者令 2026-11-02 11:57 做 **Apple 提醒事项式信息架构重做**（四卡矩阵 / 列表卡 / 详情页 / 新建态）+ FAB 回归 `.g2-fab` 基准类修 | 全库无 `todoApple`/`todo-apple` 任何字面 ⇒ main 零等价。本轮统一不合 |
| 4 | `delegate/delegate-nebflow-87290cd9` | `982625c1b` → `7398c8194` | 2 文件：`src/test/scala/nebflow/agent/R1RespawnRefusalSpec.scala`（304 新增行，main 0 命中，**文件不存在**）+ `BackoffSupervisor.scala`（87 行新增，50 行已在 main） | 单实例/重生拒绝（R1）的**回归 spec**：把「同 id 重生被拒」的行为钉住，防后续重构静默放开 | 主实现已在 main，缺的是那 304 行断言；改动面极小、零冲突风险，但属 spec 归属判断。本轮统一不合 |
| 5 | `delegate/delegate-nebflow-183b016d` | `56c7f8896` → `a91fc4c6f` | 3 文件共 10 行新增，**逐字 0 行在 main**：`web/js/todoStore.js`（JSDoc 补 `completedAt`）、`web/js/ws.js`（`/** @type {any} */` 断言）、`web/js/mobile/socialSearch.js`（该处已由 main `c00c8db47` 同义落地） | 待办数据模型与 ws 负载的**类型/文档契约补齐**（`completedAt` 在 JSDoc 里显式声明），零行为变更、零回归面 | 纯文档/类型级改动，价值低但成本为零；本轮统一不合。**注意该树按「活树」保留在盘上**（§5③） |

### §3b 其余「需合并」12 条（一笔带过，完整判据在各分片 tsv）

| 分支 | sha | 一句话 | 主表判据要点 |
|---|---|---|---|
| `…-5017517a` | `d910003fa` | MergeQueue 通道化（MergeQueueChannel 等 6 文件，新增 631 行） | main 已有独立 `MergeQueueChannel.scala`，仅 217/631 行命中 ⇒ 判不清是否已被取代，见 §5② |
| `…-064972f7` | `2c07f673c` | TaskSearch 索引/账本 + 3 spec（7 文件，499 行） | 7 文件都在 main，164 行命中（32.9%），`TaskLedgerStore` 9 行 0 命中 |
| `…-bfad8ba9` | `226c7f693` | PromptSections/SubTask/BlockingSubagent/NodeLoopRunner（7 文件） | `MsgTokenDeliveryPointsSpec.scala` 600 行 **main 不存在** |
| `…-f23846aa` | `fac2c2290` | sidebar 图像生成 + DecisionProbe（16 代码件，1357 行） | 6 文件 main 缺失（DecisionProbe 系），命中率 9.8% |
| `…-fe4f891e` | `f8eaea10c` | 本地加速 M1 健康探测 4 件（149 行） | 3 文件都在 main 但仅 17 行命中（11.4%），`GatewayMain` 10 行 0 命中 |
| `…-5fa55cde` | `faed9ca5f` | 单实例守卫 / 合并窗口关怀（11 文件） | `MergeWindowCare.scala` **main 不存在** |
| `…-03e396b4` | `36f9f65ae` | preset 链路注册与迁移（7 文件） | `PresetChainRegistry.scala` + `PresetChainMigrationSpec.scala` main 不存在 |
| `…-2637ab1d` | `4dda2ede6` | `tests/_spec-floor-sweep.sh`（1 文件） | main 0 命中，单文件、零冲突 |
| `…-298b4ddb` | `b594b825d` | `tests/flat-button-shots.mjs`（1 文件） | main 0 命中 |
| `…-432df36a` | `15ec4f369` | `tests/mobile-dock.spec.mjs` 9 行 | main 有同名文件但 6/9 行缺失 ⇒ 见 §5② |
| `…-7966cb44` | `7a2c4141c` | `web/js/chatSearch.js` 15 行 | main 有同名文件但仅 1/15 命中 ⇒ 见 §5② |
| `…-18d8fe69` | `184d0f521` | `.probe/` 2 个 mjs（148 行） | main 0 命中，**价值低（一次性探针）**，建议不并入 |

完整「需合并」共 **17 条**，除本节 5 条 + §3b 12 条外无遗漏；原始判据在 `shards/dirty-*.tsv` 的 `verdict_evidence` 列。

---

## §4 `98f9ca81` 专项结论（引用 `shards/98f9ca81-analysis.md`，并按 `a0597efdd` 复核）

**定性：混合体 —— 代码类为主 + 取证类为辅，不是纯取证也不是纯代码。**（该树 clean，无保全提交，无判废）

| 提交 | 类别 | main 是否有 | 对该 tip 复核 |
|---|---|---|---|
| `ef74c3f75` 5 个 dataroot spec（+976） | 代码 spec | 逐字节相同（`b21a9a16f`） | 无需 |
| `ff03bf890` red-scala.log + import | 取证 + spec | 逐字节相同（`33ac54a61`） | 无需 |
| `6132990f4` red-scala.log 勘误 | 取证 | 逐字节相同（`73d081e93`） | 无需 |
| `5f04afa67` AccountRootSpec 措辞 | 代码 spec | 逐字节相同（`ea8c4d0f0`） | 无需 |
| `5ae680e99` 三层数据根实现（26 文件 +1270/−70） | 代码 | **部分**（7 文件等价 / 19 文件零等价） | 仍需要 |
| `5c046b9ee` 两份 green 证据 | 取证 | 见下（1/3 已入 main、2/3 全库无 blob） | 唯一 |

**A. 证据部分逐文件比对（对 `a0597efdd` 现算，按命名新制目录 `b1-dataroot__20261104_110844/` 比）**

| 分支内旧路径 | main 新名对应 | 结论 |
|---|---|---|
| `reports-evidence/20261104_b1-dataroot/red/red-scala.log` | `reports-evidence/b1-dataroot__20261104_110844/red/red-scala.log` | **内容等价（sha1 同为 `a9d6e61c…`，277 行逐字节相同）⇒ 判「已并入（经命名迁移批以新名入库，非本支入库）」** |
| `…/green/tree-accounts.txt`（3,164 B，blob `09076742…`） | 无同名文件；旧名目录在 main 计 0 文件 | blob 在 main 全库 **0 命中** ⇒ 仍缺（0700/0600 stat 与目录树读数） |
| `…/green/v1_v2_v5_impl-root.log`（17,067 B，blob `63baf750…`） | 同上 | blob 在 main 全库 **0 命中** ⇒ 仍缺（21/21 绿 + shared 178 / cli 241 / 合并 440 读数） |

⇒ 证据 3 件里 **1 件已并入、2 件仍独有**。

**B. 代码部分（31 个 `src/main|src/test` 文件）对当前 tip 复核**
- 路径层：31/31 在 main 存在（main 有平行姊妹实现，2026-11-04 12:19:37 的 `872bcdae3`）；整支 own delta 新增 2,001 行中 1,186 行已在 main（59.3%）。
- **19 个「接线面」文件仍零等价**：`OwnerGuard` 在 main 只有 1 文件 2 命中（全是 `Guards.scala` 自身定义）、`ownerGate`=0、`writeActiveAccountPointer`=0、`InstanceScope.machineRoot`=0 ⇒ 账号层确实没接线。
- **与四次基座移动的交集：0。** 19 个文件在 `0fffa26b5..a0597efdd` 之间**一个都没被改过**（该区间的 `src/main` 变化只有 `PresenceCandidateProbe.scala`，不在这 19 个里；设计尺度迁移批改的是 CSS/tokens）⇒ **「其中 0 个文件的当前 main 版本已被迁移批改动，cherry-pick 冲突面未进一步扩大」**。
- 冲突面仍是分析文档的结论：整提交 pick 有 7 文件冲突（AutoStartService 9 处 / NeblinkModel 5 处 / paths.scala 3 处 / core/root×4 各 1 处），且会**回退 main 已落地的 5 文件实现**；**19 文件单独摘取 = 零冲突**。

**结论与建议处置（不替作者拍板）**：**仍需要、但不 cherry-pick 整提交**。两条候选：C1 只摘 19 个接线面文件并把调用点对齐 main 现行 `core/root` 符号（零冲突起步、不动已绿资产）；或 C2 整批换姊妹版（干净但要重跑 5 spec + 回归）。前 4 个提交 pick 是空操作，不必再取。**该树因活树规则未删，仍在盘上（`reportStatus=None`，见 §5③）。**

---

## §5 我不确定的三类（逐条 路径 + 疑点 + 建议处置，不替作者拍板）

### ① 判不清是垃圾还是工作的

| 路径 | 疑点 | 建议处置 |
|---|---|---|
| `…/delegate-nebflow-76aa2f3c/scratch/`（509 文件 / 32,432,128 B，含 `scratch/pkg/dbm-memory/vendor/dbm/src/*.js` 等 33 个非 ignored 文件） | 按 topdir `scratch*` 判废，但它是一份**vendored 第三方包（dbm-memory）+ node_modules**；可能是作者拷进来研究的插件源码。**该树无保全提交 ⇒ 这 509 文件已随删树消失，无分支可回溯** | 若确认是自研/需保留：从 `~/.stand` 备份或上游仓库重新获取；若确是第三方依赖：忽略。**这是本批唯一「删了之后 git 里也找不回」的删除**，已在此单列 |
| `reports/20261025_134448_windows-charset-probe/REPORT.md`（7,259 B；`5ca27781`、`c1da3caa` 两棵） | 被 `*-probe*` 目录前缀规则判废，但它是日期戳批次目录里的正经 REPORT.md（规则误伤） | 内容未丢：同 blob 已作为保全提交在 7 棵其它树的分支里（`9b828eae`/`f6a21b25`/`fbe4618c`/`296aafdb`/`865a936b`/…），需要就从任一分支 `git show` 取回 |
| `…/delegate-nebflow-18d8fe69/.probe/`（`probe-history.mjs`、`probe-live-panel.mjs`，148 行） | 不字面匹配判废 glob，按「拿不准先保全」进了 `184d0f521`；内容是一次性探针 | 不并入 main；分支留着即可 |
| `…/delegate-nebflow-7961c23f/` 根目录 `probe-filehead-geometry.tmp.mjs`、`shadow-probe.tmp.mjs`、`shadow-probe2.tmp.mjs` | 扩展名 `.mjs`、不在 `*-probe*/` 目录下 ⇒ 不算判废，已保全；实为调试残渣 | 不并入；若将来捞 `diffView.css` 批，这三件别带 |
| `…/delegate-nebflow-e7c1a868/reports-evidence/20261104_isolated-instance-kill/green-*.log`（6 件 / 90 KiB） | 按 `*.log` 判废未提交，但它们是**当天绿门禁日志**，删树即丢。该树按活树保留，故仍在盘 | 要留档就单独拷出；不要就随树走 |

### ② 判不清是否已并入 main

| 路径 / 分支 | 疑点 | 建议处置 |
|---|---|---|
| `…-0cda5510` 的 `src/main/resources/web/js/attFileGlyph.js`（+ chat.js 2 行 wiring） | main 全库无 `attFileGlyph` 字面、无同名孪生；但 main 的 chat.js 另有 2 行含 glyph 逻辑且提交晚于该树（2026-11-01）。是「main 已内联同一形态」还是「新模块漏合」，机械判不出 | 在 main 上查 `renderAttachmentPreview`：手机层附件图标盒已内联 ⇒ 改判被取代；否则 ⇒ 需合并这两件 |
| `…-5017517a` MergeQueue 通道化（6 文件 / 新增 631 行，217 行在 main） | main **已有自己的** `MergeQueueChannel.scala`（同路径不同实现），命中 34% | 比两侧 `MergeQueueChannel` 的公开面与写前日志语义，再决定「取补齐」还是「判被取代」 |
| `…-7966cb44` `web/js/chatSearch.js`（15 行，1 行命中） | main 有同名文件但内容不同 | 比两侧搜索排序/高亮的差异行，判断是否同一意图的另一实现 |
| `…-432df36a` `tests/mobile-dock.spec.mjs`（9 行，3 行命中） | main 有同名 spec，6 行断言缺失 | 逐行比对断言，缺失的 6 行是否仍是现行期望 |
| `…-164aec7e` standby 新件 `src/test/scala/nebflow/agent/DelegateStandbyLifecycleSpec.scala`（main 不存在），其余 23 文件 790/1103 行已在 main | 疲惫点：main 的 `DelegateStandbyCare.scala` 在 2026-11-03 仍在演进（`d2d1a16a6`/`cb18b8ec6`/`59ab23ea2`），但对应 spec 没有 | 人工确认 standby 生命周期是否已有别处 spec 覆盖；没有就摘这一份 spec |
| `…-f30e15f7` / `…-bd7a0f1c` 代码面（98.4% / 91.9% 已在 main） | 代码面基本无缺口，但两树的**证据归档内容唯一**且 main 已停跟踪 `evidence/`——是「已经并进去了」还是「永远进不去」，取决于归档政策而非代码 | 只需裁定归档去向（§5③），代码不必再合 |

### ③ 判不清该留该删

| 路径 | 疑点 | 建议处置 |
|---|---|---|
| 活树边界 3 棵：`.nebflow/worktrees/delegate-nebflow-{183b016d,98f9ca81,e7c1a868}` | 我按机械规则保留（`reportStatus=None`、`createdAt` 5.7~10.1h 内、`standbySince=None`、lsof 无命中）。**`reportStatus=None` 且 `standbySince=None` 说明它们既不在跑也不在守**，很可能是「死了但没写收尾」的残留记录 | 若作者确认这三条 delegate 已死：下一批可直接删（`98f9ca81` 的内容全在分支上、`183b016d`/`e7c1a868` 也一样），先看 §3 第 5 条与 §4 |
| 5 棵 evidence 大归档树（`0c50686f` 2,289 / `bd7a0f1c` 952 / `f30e15f7` 956 / `c6ce9f87` 952 / `1cb7b55c` 952 个保全文件） | 内容唯一（对 main 0 命中）、末次活动 11-02 晚于停跟踪裁定 ⇒ main 无落点，**已随本轮删树离开磁盘，只剩 salvage 分支** | 裁定归档处（vault / 归档盘 / 让分支长期留着）。别再让它们躺在工作树里 |
| 15 个 `docs/` 孤儿文档（已在 `rescued-docs/` 保全） | 15 个文件在任何分支历史里都不存在（其余 5,423 个 docs 文件都已在某个 salvage 分支上）；它们本在 `.gitignore:30` 的仓库内 `docs/` 下、不在 `status` 里 ⇒ 删树会真丢 | 已原样拷入本批产出 `rescued-docs/`（未改名、未改写）。建议作者/后续批迁入 `<dataRoot>/projects/<project>/.stand/docs/` vault 并按其命名新制改名。清单见 §5.4 |
| `…/delegate-nebflow-76aa2f3c/scratch/` 的 509 文件 | 同上①：全树判废、无保全提交，已随删树消失 | 见 §5① |
| 清单外新树 | 本节点作业期间 `git worktree list` 从 125 涨到别批新起的树（如 `/private/tmp/todo-collapse-pre`，dirty-4 报过）；本批清单 103 棵未覆盖它们 | 留给后续清仓批；本批不越界 |

### §5.4 rescued-docs 明细（15 个唯一文档，0.5 MiB，原样未改名）

| 文件 | 体积 | 来源树 |
|---|---|---|
| `docs/20261031_153527_settings-profile-params-wiring-audit__delegate-nebflow-bd54b0ee.md` | 5,459 B | bd54b0ee |
| `docs/20261031_172100_desktop-margin-revert.md` | 2,325 B | a77a4e54 |
| `docs/20261031_225000_mobile-dock-shape-batch__delegate-nebflow-f7c28d49.md` | 8,569 B | f7c28d49 |
| `docs/20261031_230000_mobile-longpress-retire-media-footer__delegate-nebflow-add4c91e.md` | 6,201 B | add4c91e |
| `docs/20261031_231000_mobile-phone-access-entry-removal.md` | 4,262 B | 5856419d |
| `docs/20261101_003500_mobile-longpress-retire-media-footer__delegate-nebflow-d71b0ea4.md` | 7,021 B | d71b0ea4 |
| `docs/20261101_095417_msgtoken-expansion-seam-audit__delegate-nebflow-bfad8ba9.md` | 6,288 B | bfad8ba9 |
| `docs/20261102_080000_mobile-interaction-standard__delegate-nebflow-f8815d4c.md` | 16,995 B | f8815d4c |
| `docs/20261102_125500_friend-remark-no-self-heal-recon__friend-remark-20261102.md` | 21,319 B | 991f7f0f |
| `docs/evidence/20261102_project-unmount/{project-unmount-confirm,group,panel}-{dark,light}.png` | 6×64~109 KiB | 183b016d |

---

## §6 边界自查表

| 约束 | 读数 | 结论 |
|---|---|---|
| 主仓 `status --porcelain` 行数 | 开工 **0** / 收工 **0** | ✅ 未动主仓工作区 |
| 有过删分支吗 | `git branch \| wc -l`：开工 **400** / 收工 **400**（recon 基线 399，期间他批新增 1，非本节点）；本节点 `git branch -d/-D` **0 次** | ✅ |
| 动过 `/private/tmp/x1367-*` 吗 | 未访问；两个 tarball 仍在：`/private/tmp/x1367-restored/restored-3dirs.tar` **464,652,288 B**（11-02 19:39）、`/private/tmp/x1367-backup/tracked-3dirs.tar` **464,652,288 B**（11-02 19:32），`/private/tmp` 下 13 个 `x1367-*` 条目全在 | ✅ |
| kill 进程次数 | **0**（只读 `lsof`/`ps` 采样，未发任何信号） | ✅ |
| `--force` 次数 | **19**（全部留痕：§2b 每棵 + `shards/force-discard-round2.tsv` 716 行逐文件） | ✅ |
| `rm -rf` 次数 | 对任何 **git worktree = 0**（100 棵全部走 `git -C <主仓> worktree remove`，无一例 `rm -rf`）；**非树目录 2 次**（`_d3/`、`_d4/` 过程底稿，分发器补充 2 明确授权）+ `rm -f` 10 个散件脚本/日志 | ✅（与「必须 0」的差异已在此明示：删树路径 0 次 `rm -rf`） |
| merge 进 main / cherry-pick 进 main | **0 次**（全部只读判定） | ✅ |
| 主仓 checkout / reset / clean / stash | **0 次** | ✅ |
| 自删 | 未删主仓、未删本节点树 `delegate-nebflow-750143b4` | ✅ |
| 删除前复核失败而跳过 | **0 棵**；因活树跳过 **3 棵** | 见 §5③ |
| 提交 | 仅本节点工作树内 1 个提交，未 push | ✅ |

---

## §7 命名新制（本批产出目录改名）

- 本批产出目录已按 `<topic>__<YYYYMMDD>` 新制改名：**`reports-evidence/20261104_worktree-salvage/` → `reports-evidence/worktree-salvage__20261104/`**（`mv`，不留旧名副本、无 `rm -rf`；`shards/`、`rescued-docs/` 随之迁移；本报告正文所有路径一律写新名）。
- 改名后 `git status --porcelain` 只剩一条重命名/新增条目（旧目录此前未被跟踪，故呈现为 `?? reports-evidence/worktree-salvage__20261104/`）。
- **`reports-evidence/20261104_worktree-cleanup/` 属上一轮别人的交付，本批未动**（未删、未改名、未回头改其 `inventory.md` 里的路径）。
- 本批改名与 **`doc-naming-migration__20261104_181200`** 批（main 第四动带进来的 338 条存量重命名）**并行进行、互不覆盖**：本节点只改自己这一个目录，未回改任何 `20261104_*` 旧名目录。
- 过程杂件未入提交：`_d3/`（165 文件 2.4M）、`_d4/`（97 文件 1.8M）、`lists/`（55 文件 956K）、以及 6 个脚本 + 4 个 `*-log.txt`/`raw-*.txt` 已从工作目录删除（删前确认全部为未跟踪 `??`；其内容已汇入 `shards/*.tsv`、`shards/force-discard-round2.tsv` 与本报告）。收工 `git status --porcelain` 为空。

---

## §8 基座移动影响（判词的时效依据）

main 在本链运行期间前进 **5 次**。逐次移动面 + 与本批的交集（交集 = `git diff --name-only <前> <后>` ∩ 各树 `merge-base..HEAD` 的**己方**改动集；**不用 `main..HEAD` 总量**，那是反向差异，见下方警告）：

| 次 | 区间 | 改了什么 | 对本批的影响 |
|---|---|---|---|
| 1 | `0fffa26b5 → d1d669aab` | 99 文件 = `src/main` **75**（几乎全是 `src/main/resources/web/css/*.css` 与 `seed/plugins/design-language` tokens，字面量→`var(--ds-*)` 等值迁移，零像素）+ `reports-evidence/20261104_design-scale-migration/` 24 文件 | **唯一有实质交集的一次**：与 **5 棵**树的己方改动集相交（`0cda5510` 1、`6cbd7d08` 3、`164aec7e` 2、`f23846aa` 3、`f30e15f7` 1 文件）。逐文件核过：该批对这些文件的改动**全是字面量→token 的机械替换**（如 `chat.js` 16 行改动中 7 行含 `var(--ds-`，`input.css` 214 行中 106 行），**不是语义取代** ⇒ 5 棵判词一个都没翻。 |
| 2 | `d1d669aab → 9be1797fd` | **1** 文件：`reports-evidence/20261104_design-scale-migration/README.md` | 交集 = **空**（`git diff --name-only d1d669aab 9be1797fd` 与该批 103 棵任一己方改动集求交为空）⇒ 零影响 |
| 3 | `9be1797fd → f3bc0a7be` | **2** 文件：`PresenceCandidateProbe.scala`、`PresenceCandidateProbeSpec.scala`（均为既有文件修改） | 交集 = **空**（`grep PresenceCandidateProbe shards/*.tsv` = **0 命中**；本节点 28 行复验的己方改动集里也无这两个路径）⇒ 零影响 |
| 4 | `f3bc0a7be → 2996e6fbb` | **368** 文件：`reports-evidence` 363（338 条目录改名成 `<topic>__<YYYYMMDD>_<HHMMSS>`，含 `b1-dataroot__20261104_110844`）+ `src/main/resources/seed/plugins/visual-report/…` 3 + `GLOSSARY.md` + `ops/README.md` | 路径层交集 = **空**；但对 §4 有实质影响：`98f9ca81` 的 `red/red-scala.log` 已被这批**以新名入库**（同 blob `a9d6e61c…`）⇒ 其证据部分由「唯一」改判「1 件已并入、2 件仍独有」 |
| 5 | `2996e6fbb → a0597efdd` | **3** 文件：`reports-evidence/doc-naming-migration__20261104_181200/{closeout-report.md, residue-classification.tsv, residue-classify.py}` | 交集 = **空**（全在 `reports-evidence/`、非代码、与本批任何己方改动集不相交）⇒ 零影响 |

**重验了多少行 / 改判多少行**：对所有「需合并」18 + 「不确定」10 = **28 行**逐棵用**现算 tip** `a0597efdd` 重跑 `git diff main..HEAD`、`git cherry main HEAD`、`merge-base`、己方 delta 与移动面交集、以及「己方新增行在 main 的逐字命中率」。**判词被改写 1 行**：

| 树 | 旧判词 → 新判词 | 依据 |
|---|---|---|
| `delegate-nebflow-6e4185d6` | **需合并 → 被取代** | 5/5 文件在 main；175 新增行中 **170 行逐字已存在（97.1%）**；main 侧同文件末次提交 `da6aaf230`/`4d944ec91`（2026-11-03，delegate-identity / panel-IA 两批）晚于该树末次 2026-10-22；残差 5 行只是 `.map(_.title)` 等写法差异 |

**两次「不改判词但必须记一笔」的收窄**：`bd7a0f1c`（91.9% 已在 main）、`f30e15f7`（98.4% 已在 main）—— 代码面无缺口，其「不确定」此后**只指 evidence/reports 归档去向**，不再是「是否已并入 main」。

**必须记下的方法论警告（避免被过期读出误导）**：`git -C <tree> diff main..HEAD` 在全部这些树上都是 **2,600~4,300 文件**，其中几乎全是 main 自己前进造成的反向「删除」表象；`98f9ca81` 上更是 664 文件 / 巨大负差。**本报告一律不用这个总量作判据**，只用 `git cherry` + 己方 delta（`merge-base..HEAD`）+ 逐文件 `main:<path>` 存在性与逐行命中率。

---

## 附：产出文件清单

| 文件 | 内容 |
|---|---|
| `reports-evidence/worktree-salvage__20261104/inventory-round2.md` | 本报告（主表 103 行 == 待处理棵数） |
| `shards/dirty-1..5.tsv`、`shards/unmerged.tsv`、`shards/detached.tsv` | 各分片台账（10 列），103 行数据 |
| `shards/force-discard-round2.tsv` | **本节点新增**：19 棵 `--force` 的逐文件丢弃台账（716 行 + 表头：tree/path/bytes/status/登记理由） |
| `shards/recon.md`、`shards/98f9ca81-analysis.md`、`shards/dirty-2-README.md`、`shards/manifest.tsv`、`shards/*.txt` | 上游开工盘点与专项分析 |
| `rescued-docs/` | 15 个在任何分支历史里都不存在的 `docs/` 孤儿文档（原样，未改名） |
