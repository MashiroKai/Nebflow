# dirty-2 分片保全与判定说明

配套交付：`dirty-2.tsv`（19 行 + 表头，10 列）。清单源：`dirty-2.txt`（19 棵）。

## 口径

1. 逐棵记 `pre_head` / 分支 / `status --porcelain`（共 121 行脏行）。
2. 逐文件分拣：按任务书判废 glob 机械匹配（含 untracked 单文件 > 5 MiB）→ 结果 **判废 0 行**，故无 `junk_detail` 条目，19 棵全部提交保全。
3. 只 `git add` 保全路径，提交前自查 `git diff --cached --name-only` 无判废模式（19 棵均 0 命中）。
4. 提交信息格式按任务书：`salvage(<树名>): 未提交改动保全 — <一句话内容判定>（原批次 <delegate-id>，末次活动 <YYYY-MM-DD>）`。
5. 不变量：`保全数 + 判废数 == 提交前 porcelain 行数` → 121 + 0 = 121，逐棵校验通过（无 MISMATCH）。
6. **未删任何树 / 未删任何分支 / 未动主仓工作区**；主仓 HEAD 保持 `0fffa26b51e130f012329269d27f70be19fa3ab5`，主仓 `status --porcelain` 0 行，worktree 登记数 123 与 recon 基线一致。

## 判定方法（与任务书字面口径的一处偏离，需复核者知悉）

`git diff --stat main..HEAD` 在 19 棵上均非空且量级为 2918–3924 文件 —— 因为这些树 fork 自很旧的 main，该 diff 主要由 **main 侧反向进度** 构成，不能作为「是否需要并入」的信号。

改用两条硬证据：

- `git cherry main HEAD`（`-` = 等价补丁已在 main；`+` = 未并入）→ `-` 4 棵：147dadf8、9fa60def、eed3ad45、fbf9b18e；`+` 15 棵。
- 逐 blob / 逐新增行比对：对每棵的 `pre_head..HEAD` 变更文件，与 `main:<path>` 做 blob 相等判断；对文本文件进一步用 `comm -23` 统计「新增行中有多少行不在 main」。

## 「被取代」的成立依据

main 于 **2026-11-02** 落地过程产物停 track 裁定（`5ba295d2a chore: stop tracking evidence/ reports/ and docs/`、`1a42c7128 chore: purge tracking of evidence/ reports/ docs/ reintroduced by USTC merge`），且 main 现行 `.gitignore` 含 `/evidence/`、`/reports/`；`docs/` 现于 main 不 track。

本分片 19 棵末次活动最晚为 **2026-11-01**，即停 track 裁定晚于全部树 → `evidence/`、`reports/`、`docs/` 类内容被该裁定取代，不再入 repo（本地保留在树内）。

唯一具备**文件级** main 提交证据的是 dcc13390：`docs/20261029_175151_plugin-repo-registry.md` 被 `5ba295d2a`（2026-11-02）直接删除，晚于该树末次提交 2026-10-30。

## 需复核者注意

1. **提交信息有缺陷**：19 条 salvage 提交的「原批次 / 末次活动」两个槽位为空，且相邻两个全角标点被吃掉首字节（macOS 自带 bash 3.2 在 `zh_CN.UTF-8` 下多字节 + `$var` 展开的已知缺陷；zsh 正常）。**正确值在 `dirty-2.tsv` 的 `branch` 列与 `notes` 列**（每行 notes 首段即「末次活动 <日期>」）。未使用 `--amend` 修正，以遵守「不重写既有提交」的 git 安全约定。
2. **不确定条目 1 件**：`delegate-nebflow-18d8fe69` 的 `.probe/`（2 个 mjs，148 行）。目录名不字面匹配判废 glob `*-probe*/`，按「拿不准先保全」提交；main 无且不被 ignore，判「需合并」但**价值低**（一次性 history/live-panel 探针脚本），可不并入。
3. `delegate-nebflow-2637ab1d` 与 `432df36a` 是混合树：既含 evidence 截图（被停 track 取代），又含受 track 的 `tests/` 内容（`_spec-floor-sweep.sh`、`mobile-dock.spec.mjs`）—— 判「需合并」以后者为准。
4. `delegate-nebflow-6e4185d6` 部分已并入：5 文件中 3 文件（WebSocketRoutes 1/42、AgentState 0/2、ActiveAgentsEntrySpec 0/46 新增行不在 main）已由 main 独立收敛，仅 `WsSessionChatHandlers`(23/32)、`bgAgents`(8/40) 仍需合并。
5. porcelain 行数 ≠ 文件数：`?? 目录/` 折成 1 行，而提交展开为目录内各文件（合计 121 行 → 231 个文件）。`kept_files` 列统一记 **porcelain 行数**以维持不变量，展开后的文件数写在 notes。
6. 未做 **任何** merge / cherry-pick 进 main；未 kill 任何 sbt/java/nebflow 进程；未触碰 `/private/tmp/x1367-*`（13 个目录仍在）。
