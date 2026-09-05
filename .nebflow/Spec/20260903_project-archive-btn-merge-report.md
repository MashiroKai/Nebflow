# 20260903 proj-archive-btn 合并报告（重派2 闸门 PASS）

## 0. 结论

**合并完成**：`proj-archive-btn` 已以 `--no-ff` 并入本地 main，merge commit **`815d2519`**。
合并后子集复跑**全绿**（后端 16/16、前端断言 2/2、check-js-types 零新增、i18n sweep 11/11、改动 JS node --check 3/3）。
**收尾受阻未执行**（详见 §5）：报告落盘 `~/.nebflow/docs/Nebflow/` 与 ~/.nebflow 仓 commit、以及 worktree/分支清理，被本会话沙箱的 `~/.nebflow` 只读限制挡住（与上游验证节点同一限制，本会话已正式尝试并留痕：Write 工具与 Bash touch 均被 SANDBOX_DENIED）。按「先落盘再收尾」铁律，清理动作保留现场、绝不提前执行。零仓库半成品、零破坏。

## 1. QA 判定复述

上游节点「验证-面板归档按钮·重派2」最终判定 **PASS**（独立重做，六条验收项全过，零产品缺陷）：
① 归档后不在面板显示（含 WS 刷新路径）· 隔离实例 8095 真实后端端到端 15/15；
② workspace 全保留 / project.json 手术式稳定（键集恰 +2，收殓区间 sha256 逐字节相同）；
③ 无自动归档（全仓 `archived` 唯一写点 = ProjectStore.archive，唯一调用方 = REST POST /projects/<name>/archive）；
④ 幂等与边界（双 POST 同值零重写 / 404×3 / 重启后标记保留）；
⑤ 回归（定向 suite 16/16 + check-js-types 0 新增 + i18n 11/11 + 全量 2187 中 2167 绿，20 失败逐条核验为会话沙箱环境性拒绝，同一提交非沙箱两次 2187/0）；
⑥ 隔离实例纪律（宿主 8080/PID 77854 零触碰）。
申报缺口（不阻塞）：NodeTools ProjectCreate 归档拒绝分支无专属 spec，建议后续补用例。

## 2. 开工盘点（实测）

| 项 | 值 |
|---|---|
| 分支 tip | `5654aca9` |
| main tip（合并前） | `f1e8b5a3` |
| merge-base（实测重算） | `2b064487` |
| 分支增量 | 恰 2 个实施 commit（79f4fd0c 后端 + 5654aca9 前端）/ 10 文件，零验证残留 —— 与闸门报告一致 |
| 工作区 | 干净（他人 WIP 的 CONTRIBUTING.md / CONTRIBUTING.zh-CN.md 已不在，零障碍） |
| 双方共同改动文件 | 仅 `ProjectTypes.scala`、`NodeTools.scala`；locales 零交集（任务预判的 locales 冲突实测不存在） |

## 3. 合并执行与冲突处置

命令：`git merge --no-ff proj-archive-btn -m "Merge proj-archive-btn: Project 面板手动归档按钮（重派验证 PASS）"`

| 文件 | 结果 | 处置 |
|---|---|---|
| ProjectTypes.scala | 自动融合 | main 侧 NodeDef 域（held/hold/deps/nebulaDeliveredAt，+15/-2）与分支侧 ProjectDef 域（archived/archivedAt）异区域；合并后 archived/archivedAt 字段在位（L176-177） |
| **NodeTools.scala** | **冲突（单 hunk）** | 见下 |
| 其余 8 文件 | 干净快取 | — |

### NodeTools.scala 冲突 hunk（ProjectCreate 幂等挂载链，L1250-1263）

- main 侧（新）：`ProjectStore.load(resolvedName)` + 「同名异 workspace 明确报错」（sameWorkspace 判定挂载）——resolvedName = name 参数或 workspace basename 派生，为最终落盘名。
- 分支侧（旧基线）：归档项目拒绝挂载（防「已挂载但面板不可见」僵尸态），用旧参数 `name`、无 workspace 检查（其分叉点尚无该逻辑）。
- **融合结果（双方语义零丢失）**：保留 main 侧 `resolvedName` + sameWorkspace 结构；插入归档拒绝分支于 workspace 判定之前（归档态优先——归档项目无论 workspace 异同一律拒绝，忠实分支侧意图）；错误消息与恢复路径提示升级为 `resolvedName` 与 main 侧口径一致。合并后 grep 冲突标记 0。

## 4. 合并后子集复跑（全绿）

| 检查项 | 命令/方式 | 结果 |
|---|---|---|
| ProjectArchiveSpec + ProjectStoreSpec + ProjectActorSpec + ProjectStartupMountSpec | `sbt -Dsbt.boot.directory=/tmp/... testOnly ...`（boot 目录重定向规避 ~/.sbt 锁沙箱拒绝） | **16/16 绿**（Archive 6 + Store 8 + Actor 1 + StartupMount 1），与闸门报告一致 |
| tests/project-panel-archive.spec.mjs（仓库原样） | `node node_modules/@playwright/test/cli.js test ...` | 功能用例 **1/1 过**；截图用例断言零失败，败在截图落盘步骤（SHOT_DIR=`~/.nebflow/docs/Nebflow` 被沙箱拒，非产品问题） |
| 同 spec SHOT_DIR 重定向版 | sed 仅替换 SHOT_DIR 一处 → `/private/tmp`（diff 校验仅此一处差异，跑完即删临时文件） | **2/2 绿** —— 断言逻辑完全通过 |
| scripts/check-js-types.mjs | `node scripts/check-js-types.mjs` | **0 errors < baseline 326，零新增** PASS |
| i18n sweep | 自起静态服务器（8387，PID 核实非宿主后 SIGTERM，端口清零）+ `node scripts/verify-i18n-sweep.cjs 8387` | **11/11 PASS**（与闸门报告一致） |
| 改动 JS node --check | projectTab.js / locales/en.js / locales/zh-CN.js | **3/3 OK** |
| REST 归档路由 spec | 检索确认不存在（闸门报告同样口径，REST 路径靠写点审计覆盖） | 不适用 |

merge commit 内容核验：恰好 10 文件（8M + 2A），零夹带零越界；合并后 `git status` 干净。

## 5. 清理与收尾——受阻未执行（零破坏）

本会话为 workspace-write 沙箱：可写根仅项目目录与 /tmp，`~/.nebflow` 只读（实测 Write 工具与 Bash touch 均被 SANDBOX_DENIED，错误注明 "outside sandbox root"）。受影响子项：

| 动作 | 状态 | 原因 |
|---|---|---|
| 报告落 `~/.nebflow/docs/Nebflow/20260903_project-archive-btn-merge-report.md` | **未执行**（本报告暂存 `/private/tmp/nqa-shots/`） | ~/.nebflow 只读 |
| `~/.nebflow` 仓单独 commit | **未执行** | 同上（.git 写入亦被拒） |
| `git worktree remove .nebflow/worktrees/proj-archive-btn` | **未执行**（现场确认：worktree 在位 @ 5654aca9） | 按「先落盘再收尾」铁律，落盘受阻则清理绝不提前 |
| 删软链 `~/.nebflow/proj-archive-btn` | 不需要 | 现场确认**本就不存在** |
| `git branch -d proj-archive-btn` | **未执行**（分支保留 @ 5654aca9） | 同「先落盘再收尾」 |

### 宿主侧收尾命令（转投报告 + commit + 清理，一次执行）

```bash
# 1) 转投本报告与上游验证产物
cp /private/tmp/nqa-shots/20260903_project-archive-btn-merge-report.md ~/.nebflow/docs/Nebflow/
cp /private/tmp/nqa-shots/20260903_project-archive-btn-verify-report.md ~/.nebflow/docs/Nebflow/ 2>/dev/null || true
cp /private/tmp/nqa-shots/20260903_project-archive-btn-verify-after-*.png ~/.nebflow/docs/Nebflow/ 2>/dev/null || true

# 2) ~/.nebflow 仓单独 commit（按文件，禁 -A）
cd ~/.nebflow && git add docs/Nebflow/20260903_project-archive-btn-merge-report.md \
  && git commit -m "docs: proj-archive-btn 合并报告（重派2 PASS，merge 815d2519）"
# 验证节点的报告/截图如需入库，按具体文件另补 add+commit

# 3) 清理（报告落盘 commit 之后）
cd "/Users/dev/Claude code/Nebflow" \
  && git worktree remove .nebflow/worktrees/proj-archive-btn \
  && git branch -d proj-archive-btn
```

## 6. 纪律执行确认

- git add 仅具体文件（NodeTools.scala），未用 -A；未 push、未触碰 origin。
- 宿主 8080 / PID 77854 全程零信号；自起进程仅 8387 静态服务器（kill 前 lsof 核实为 Python http.server、cwd 在项目 web 目录，非宿主），已收殓、端口清零。
- 未 NodeCancel/abandon 任何节点；sandbox 内测试失败均已甄别为环境性拒绝并给出重定向解法，非产品缺陷。
