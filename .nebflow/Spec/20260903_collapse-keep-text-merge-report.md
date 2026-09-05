# collapse-keep-text 分支合并收编报告

日期：2026-09-03 ｜ 执行：Coder（barrier 节点：合并+融合+子集复跑+清理）｜ 上游节点：验收-收起保留中间文字（n-dcccc389）

## 1. 上游判定复述

上游 QA 独立验收六项全 PASS：核心场景重放（9/9）、交替形态双主题视觉、边界回归独立复跑（含 08-26 闭合裁定活体验证）、改动面审查（仅 turnGroup.js + 新 harness + 新 spec，无 WS/存储/chat.js/persistence/scala 越界）、check-js-types 逐字节一致零新增、既有失败归因做实（bgagent-dedupe 为基线同败）。**最终判定 PASS（可合并）**。

上游实施要点转述：根因是 `isAgentRow()` 把 ai 文字行归为过程行 + `findFinalRow()` 只豁免最后一条这对判定的涌现结果；改动拆为 `isCollapsibleRow`（ai 文字显式 false）+ `collapsibleRuns`（文字作分隔符切连续 run），三条建组路径逐 run 建组逐组落收起状态。

## 2. 合并前现场

| 项 | 值 |
|---|---|
| 主仓分支 / HEAD | main @ `2fadf6f6`（与上游报告开工值一致） |
| 主仓工作区 | 干净（git status --porcelain 空） |
| 分支提交链 | `7710190c`（基线）→ `d99de687`（实施）→ `e50ec989`（新 spec，分支 HEAD） |
| worktree | `.nebflow/worktrees/collapse-keep-text` 干净，HEAD = `e50ec989` |

## 3. 冲突预判 vs 实际

| 侧 | 自基线 `7710190c` 起改动文件 |
|---|---|
| collapse-keep-text | `turnGroup.js`、`tests/fixtures/turn-collapse/harness.html`（新）、`tests/turn-collapse-keep-text.spec.mjs`（新） |
| main | `input.css`、`paths.scala`、`NodeEngine.scala`、`GlobTool.scala`、`NodeTools.scala` + 3 个 scala 测试 |

- 预判：交集为空；重点核对的 chat.js 双方均未触碰（main 侧 sendbtn 在飞链落在 input.css 域，与分支零交集）。
- 实际：`git merge --no-ff` ort 策略一次通过，**零冲突**，无融合决策需要记录。
- Merge commit：`2552fb57`（3 文件，+610/-38）。

## 4. 合并后子集复跑（不跑全量、不跑 sbt）

### 4.1 spec：tests/turn-collapse-keep-text.spec.mjs — 8 passed / 0 failed（54.2s）

| # | 用例 | 结果 |
|---|---|---|
| 1 | 文字→工具→文字→注入→工具→最终：文字原位可见，折叠区仅工具+注入 | passed |
| 2 | #403 外部注入闭合后开新组，已闭合组不动 | passed |
| 3 | 同轮重终态 heal（failTurn 溶解重收所有 run 组）＝ungroupTail 路径 | passed |
| 4 | 历史重建：中途注入进折叠区，badge 后注入开新组 | passed |
| 5 | busyTail：无 badge 中途尾平坦（光标在尾首）；闭合后 failed 重收 | passed |
| 6 | E5 纯文字不收 + E6 纯思考保持可见 | passed |
| 7 | 暗色主题交替折叠渲染 | passed |
| 8 | 亮色主题交替折叠渲染 | passed |

busyTail/ungroupTail/E5/E6/badge后注入等既有回归用例均已包含于该 spec（上游实施时写入），与上游「10/10」覆盖面一致。

### 4.2 node scripts/check-js-types.mjs

- `turnGroup.js`（本批合并唯一改动 js）：**0 错误**。
- total 343 > baseline 326、chat.js TS2339 7>6、flowMapTab.js/micOrb.js/orbSettingsUI.js 新文件报错——与上游验收时记录的 main 存量漂移数字逐项一致（上游已做实分支输出与基线 `7710190c` 逐字节一致）。合并带入文件中仅 turnGroup.js 参与 js 类型检查，**零新增**。

## 5. 清理记录（子集绿之后执行）

| 动作 | 结果 |
|---|---|
| `git worktree remove .nebflow/worktrees/collapse-keep-text` | 成功，worktree list 已无该项 |
| `rm .nebflow/collapse-keep-text` 软链 | 成功 |
| `git branch -d collapse-keep-text` | 成功（Git 确认已合并，曾为 `e50ec989`，未需 -D） |

其余既有 worktree（canvas-html-fix、proj-archive-btn 等）与本任务无关，未触碰。

## 6. 纪律遵守

- 全程未 push、未动 origin；未跑 sbt（纯前端零 scala）；宿主进程（PID 87216 / 端口 8080）全程未触碰；未 NodeCancel/abandon 任何节点；git add 均为具体文件。

## 7. 生效说明

本批改动随**前端产物重建 + 宿主重启包**生效，本节点不做重启。

## 8. 遗留问题

- main 存量 check-js-types 漂移（343 > 326、chat.js 7>6）为本批之前已存在，不属本批范围，待后续专项治理。
- smoke×5 / askuser 真实后端链路 spec 依赖 sbt 实例（纪律禁跑），沿用上游历史归因，未在本轮验证。
