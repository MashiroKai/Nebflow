# HANDOFF — 文档命名新制迁移（2026-11-04）

**批次原主人**：`delegate-nebflow-71767a56`（已停止，不再产出交付物）
**接手批**：`delegate-nebflow-d432ab0c`
**接手时工作树里已有 337 条 staged 重命名（git index）——任何接手方禁止 `git add` / `git reset` / `git checkout` / `git stash`，一碰就毁。**

## 1. vault 实况（18:45 实测，只读）

```
D=/Users/kaiyu/.stand/projects/stand/.stand/docs
顶层旧制（^20YYMMDD_HHMMSS） = 0
顶层新制（__20YYMMDD_HHMMSS） = 197
递归 .md                     = 337
```

vault 的改名**已经完成**（不是我这条链做的：我的 `vault3` 节点死于 dead-session，未产出；改名由并发的另一条腿/接管批落到盘上）。vault 不在 git 内，**唯一回滚依据就是本目录的 manifest + rename-map**。

## 2. 可复用的判据与产物（全部在本目录）

| 文件 | 内容 | 用途 |
|---|---|---|
| `manifest.md` | 685 行迁移清单：§7 逐行迁移表 239 行（旧名→新名）、§3 统计、§4 重名、§5 topic 判定、§6 时间分量异常、§8 不动清单、§9 引用健康度、§10 其他项目 | **唯一权威映射与回滚依据** |
| `rename-map.tsv` | 266 行（含 3 行注释）：`旧名<TAB>新名<TAB>回滚标记`，scope 为 vault 相对路径 | 机械回滚脚本的输入 |
| `rename-map-evidence.tsv` | 24 行：`reports-evidence/` 旧路径→新路径 | 同上 |
| `verify-vault.json` | 迁移后自检：map_rows=239、old_still_present=[]、new_missing=[]、lang 段违规=[]、新名冲突={}、files_identical_to_prediction=334、files_diverged_after_migration=2、files_missing_on_disk=[]、rewrite_stats、29 件日级占位清单、3 件 4 位 HHMM 补零清单 | 迁移正确性证据 |
| `links-before.json` / `links-after.json` | 全量链接解析快照 | 断链基线与迁移后对比 |
| `build-manifest.py` / `xcheck-map.py` / `rename-execute.py` / `rewrite-links.py` / `verify-links.py` / `verify-rename-vault.py` / `residue-scan.py` | 一次性脚本，可直接复跑 | 复现与补扫 |

**改写引用统计（迁移后实测）**：wikilink 649 / embed 0 / markdown 2 / index 41 / 文件级 651。

## 3. 已知残留（接手方要处理的活）

- **断链基线**：迁移前 `broken_occurrences=57 / broken_targets=48`，迁移后 `58 / 49`（+1 为并发批新落的悬空目标，不是旧名残留）。**这批悬空主要是术语短链**：`[[GCR]]`、`[[目标]]`、`[[目标#标题]]`、`SupervisorStrategy.restart.withBackoff(...)`、`AllProvidersDown`、`report-chart.png` 等 —— 属历史遗留，需逐类判定是补词条还是改链接。
- **迁移后偏离预测的 2 篇**（被并发写改过，需人工确认内容未被误改）：
  - `20261104_153209_presence-direct-root-cause.md`
  - `20261104_174500_mergequeue-three-gaps-r2-r1-r5__delegate-nebflow-b3bdf8d3.md`
- **`reports-evidence/` 跨目录引用未改**：`rename-map-evidence.tsv` 的 24 条改名已完成（已 staged），但各证据目录 `README.md` 与相对路径里指向旧目录名的引用**尚未重写** —— 这是接手方第一件要做的活。

## 4. 规则源（已完成，提交 `16fe35377`，无需重做）

主仓 `AGENTS.md:269,271`（被 `.gitignore:104` 忽略，**只落盘不入库**）、`~/.stand/docs/CONVENTIONS.md:14-47`、`~/.stand/plugins/visual-report/skills/visual-report/SKILL.md:274`、`~/.stand/plugins/visual-report/skills/wiki-docs/SKILL.md:112-126`、两份 seed 副本（`src/main/resources/seed/plugins/...`，`cmp` 与盘面一致）、`~/.stand/projects/stand-website/AGENTS.md:170,175`。

## 5. 还没做（接手方清单）

1. `check_wiki_docs.py` **仍不校验文件名**（`collect_docs` 只按扩展名；`RULES` 只有 R1–R4）→ 需加 **R5 `doc-naming`**，校验 `<topic>__<YYYYMMDD>_<HHMMSS>[__<chainId>].md`，并同步 `src/main/resources/seed/plugins/.../scripts/check_wiki_docs.py`。
2. 提交那 337 条 staged 重命名（**独立提交**，与 `16fe35377` 规则源分开，便于单独 revert）。
3. 重写 `reports-evidence/` 内跨目录引用；跑全量断链扫描（本批引入必须 0）。
4. 写知识文档 `doc-naming-new-rule__<YYYYMMDD>_<HHMMSS>__delegate-nebflow-71767a56.md`（新制第一篇示范）。
5. `git merge main` 追平 → `git merge --no-ff` 合入 main（作者令：不等队列 grant）。
6. 代码面仍在散发旧制：`src/main/scala/nebflow/agent/PromptSections.scala:296-299`、`src/main/scala/nebflow/prompts/PromptCatalog.scala:263-264`、`~/.stand/agents/project-dispatcher/system.md:152` —— 作者已另派代码批，此处仅留交叉引用。

## 5.1 实测补记（合并轮只读复核，交回给接手方）

- 分支现有两个提交，边界正确：`16fe35377`（规则源）与 `ff7089b98`（reports-evidence 338 条改名）——作者要的「改名与规则源分开」已成立。
- `reports-evidence/` 顶层日期打头条目 = **0**（改名已落地）。
- **但 `python3 … check_wiki_docs.py --project stand` 当前 exit=1：9 条 `broken-link`**，全部集中在 `design/*.html` 里，引用仍指向**旧制名**（例：`[[20261102_084500_cursor-tab-intent-suggestion-design__delegate-nebflow-18a4e786]]`、`[[20261031_082100_liquid-glass-effect__delegate-nebflow-764d8fb0.zh-CN]]`）。**`rewrite-links.py` 只覆盖了 `.md`，漏了 vault 内的 `.html` 文档。**
- 复跑命令：`python3 /Users/kaiyu/.stand/plugins/visual-report/skills/wiki-docs/scripts/check_wiki_docs.py --project stand`
- 因此合并前置第①项**当前不成立**，本批不可合并；修法：让 `rewrite-links.py` 同时扫 `.html`（同目录的 wikilink 语法一致），重跑后 exit 0。
- `check_wiki_docs.py` 仍无 R5（`grep -n 'R5\|doc-naming'` 空），第①项的另一半也还没做。

## 6. 本批教训（写给接手方，别重犯）

1. 首轮 `ChainWrite` 漏写 `deps`，5 个节点并行起跑，分发器随即 `NodeCancel` 三个顺序敏感节点；引擎把该次取消记为 `source=user`，**实为分发器纠序发起，非作者取消**。
2. 改名批**不能拆成多条链分期重派**：`rewire2` / `vault3` 两次 dead-session 都发生在「链刚起、盘面已在变」的窗口里。并发批已在同一棵树上 staged 337 条时，派节点进同一棵树的唯一结果就是互相踩。
3. 结论：这类「盘面已在迁移中」的收口活，**单会话端到端做完**，不要再摆链。
