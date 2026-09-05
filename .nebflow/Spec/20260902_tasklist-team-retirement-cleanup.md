# 任务列表 team 区块退役清理清单

- 来源：任务列表×节点整合迭代 v2（2026-09-02，作者反馈①「『上面的任务』是 team 任务，后期可能都要退役，要做好方案后期要清理」）
- 基线：主仓 commit 99d8b1c8（v1 整合）+ v2 解耦迭代（节点区块 `.task-node-*` 独立命名空间）
- 本文件是后续自动恢复源；正式并入 `20260902_project-architecture-phase2-design.md` §F.7（阶段 3 team/flow 退役时序）
- 前提：v2 已完成解耦——节点区块（`.task-node-*` 类、自有判定 `sessionShowsNodeEntries`、自有渲染 `buildNodeRow`/`buildNodeSection`/`buildNodeGlyph`）与 team 区块零交叉引用，可按下表一次性删除 team 域而不伤节点区。

## 0. 边界定义（删什么、留什么）

| 层 | team 任务域（删） | 面板基建 + 节点区块（留） |
|---|---|---|
| 数据源 | `state.teamTasks` 缓存 + `teamTaskListUpdate` 帧 | `nodeCache` + 4 类节点 WS 帧 + NodeList 快照 |
| 判定 | `sessionShowsTeamTasks` | `sessionShowsNodeEntries`（节点自有，语义同「Nebula 统一面板」） |
| 渲染 | 任务行/分组/空态 | `buildNodeRow`/`buildNodeGlyph`/`buildNodeSection` |
| CSS | 任务行 + 分组 + 空态类 | `#task-list`/`.task-card`/`.task-header`/`.task-body*` + `.task-node-*` 全部 |
| ticker | —— | `refreshLastActive`/`ensureLastActiveTimer`/`formatLastActive`（面板级，`.task-node-time` 在用，**不删**） |

## 1. taskList.js（src/main/resources/web/js/taskList.js）

### 1.1 删除函数（team 任务域，v2 标注 `[team 区块 · 退役边界]` 注释带内）
- [ ] `sessionShowsTeamTasks`（**导出函数**——须与 main.js 的 import 同批删，见 §2）
- [ ] `mergeTeamTasks`
- [ ] `taskTeam` / `taskMember`
- [ ] `isVisible`（visible set = pending+in_progress，纯任务域）
- [ ] `kindOf`
- [ ] `taskTs` / `sortByActiveDesc` / `sortProgress`
- [ ] `buildCheck` / `buildRow`
- [ ] `buildGroupHeader` / `buildSubgroupHeader` / `buildEmpty`

### 1.2 删除 renderTaskList / redraw 内分支
- [ ] `renderTaskList`：`mergeTeamTasks(tasks, sessionId).filter(isVisible)` 一行及注释；`has-tasks`/timer/空态判定的 `visible.length` 项改为只看 `nodes.length`；函数可顺手改名 `renderNodePanel(sessionId)`（可选，改名则同步 main.js 调用点）
- [ ] `redraw`：`const progress = visible.filter(...)` 起至 `inner.appendChild(progressSection)` 的 progress 段整体删除（含 ungrouped/byTeam/teamOrder 分组循环）
- [ ] `redraw`：header stats `t('task.statsProgress', { progress: progress.length })` → 节点计数（`t('flowmap.title')` + nodes.length）或删（拍板点）
- [ ] 保留：`collectZoneById`（选择器已兼容 `.task-node[data-node-key]`，删任务域选择器半边可选）、入场动画循环中 `.task-item` 半边可选删、`buildNodeSection` 调用点

## 2. main.js（src/main/resources/web/js/main.js）
- [ ] `onMessage('teamTaskListUpdate', ...)` 处理器（v2 基线 ~2528-2532，含 `sessionShowsTeamTasks` gate 分支）
- [ ] `sessionShowsTeamTasks` 的 import 与调用

## 3. state.js（src/main/resources/web/js/state.js）
- [ ] `teamTasks: {}` 字段及其注释（v2 基线 ~149-152）

## 4. ws.js（src/main/resources/web/js/ws.js）
- [ ] `GLOBAL_MSG_TYPES` 中 `'teamTaskListUpdate'`（v2 基线 ~174）

## 5. taskList.css（src/main/resources/web/css/taskList.css）

### 5.1 删除（任务行 / team 分组域）
- [ ] 行系：`.task-item`、`.task-item-text`、`.task-label`、`.task-desc`、`.task-item.task-has-desc`（两条）、`.task-item.task-has-desc .task-check`、`.task-active .task-label`、`.task-meta`、`.task-has-meta`（渲染类，无样式规则）
- [ ] 状态词/时间：`.task-status-word`、`.task-last-active`
- [ ] glyph：`.task-check`、`.task-check-box`、`.task-check.task-check-spin`、`@keyframes spin`
- [ ] 入场：`.task-item.task-entering`、`@keyframes task-enter`
- [ ] 分组/空态：`.task-group-header`、`.task-subgroup`、`.task-subgroup-header`（含 `.task-subgroup-member` 变体）、`.task-subgroup .task-item`、`.task-member-group .task-item`、`.task-empty`、`.task-body-inner > .task-group-header:first-child`
- [ ] 窄屏 ≤480px 块内：`.task-status-word`、`.task-last-active` 两条
- [ ] reduced-motion 块内：`.task-check.task-check-spin`、`.task-item.task-entering` 两条

### 5.2 保留（面板基建 + 节点区块，删了会伤节点区）
- `#task-list` / `.has-tasks` / `.task-card`（含 dark shadow）/ `.task-header` / `.task-toggle` / `.task-stats` / `.task-body` / `.task-body-inner` / `.task-card.collapsed` 三条 / `.task-section`（通用容器，`.task-section-nodes` 在用）/ `.task-node-*` 全部 / `@keyframes task-node-spin-rot` / `@keyframes task-node-enter` / 窄屏 `.task-node-word, .task-node-time` / reduced-motion 节点两条 / 窄屏 `.task-header`/`.task-stats`

## 6. spec / 脚本
- [ ] `tests/smoke.spec.mjs` ~355-376：`renderTaskList` 打桩段改为节点面板断言或删除
- [ ] `scripts/verify-task-progress.cjs`：team merge 用例（G7 等）删除/改写为节点用例
- [ ] `scripts/verify-unified-panel.cjs`：`renderTaskList`/`taskListUpdate` 相关断言同步
- [ ] `scripts/shot-tasklist-nodes.cjs`：mock 中 team 任务 `taskListUpdate` 段删除（截图只剩节点区）
- [ ] `tests/tasklist-nodes.spec.mjs`：**不动**（T1-T6 全部只断言节点区块，解耦后天然免改）

## 7. i18n 孤儿 key 候选（locales/en.js + zh-CN.js，删除前逐 key 全仓 grep 复核）
- [ ] `task.sectionProgress`、`task.progressEmpty`（progress 区空态）
- [ ] `task.statsProgress`（header 计数——若 §1.2 拍板保留节点计数则改用其他 key）
- [ ] `task.globalSource`（#16 全局任务来源标注，任务行专用）
- [ ] `task.inProgressShort`（任务行 in_progress 专用；节点 running 用 `flowmap.run`）
- 保留：`task.pendingShort`（NODE_WORD_KEY pending 在用）、`task.justNow`（formatLastActive）、`task.expand`/`task.collapse`（toggle）、`flowmap.*`、`flows.status.cancelled`、`project.openFlowMap`

## 8. 一次性删除 SOP（验收序列）
1. 同一 commit：§2 + §3 + §4 + §1 + §5（跨文件引用同批删，避免中间态编译错误）
2. 同 commit 或紧随：§6 spec/脚本同步
3. `grep -rn "task-item\|task-check\|task-subgroup\|task-member-group\|task-status-word\|task-last-active\|task-meta\b" src/main/resources/web/` → 零残留（`.task-node-*` 前缀类不匹配以上模式，天然安全）
4. `grep -rn "teamTaskListUpdate\|teamTasks\|sessionShowsTeamTasks\|mergeTeamTasks" src/main/resources/web/js/` → 零残留
5. §7 每个 key 全仓 grep 后删
6. 跑 `tests/tasklist-nodes.spec.mjs`（6/6 全绿）+ `tests/flowmap-realtime.spec.mjs`（2/2）+ smoke
7. `node scripts/shot-tasklist-nodes.cjs` 出图目检（只剩节点区的面板）

## 9. 触发条件（何时执行本清单）
- 阶段 3 team/flow 退役时序（设计文档 §G.6 / §F.4）中，team 域任务停止下发（`teamTaskListUpdate` 不再产生）后执行
- 或作者单独拍板「任务列表只留节点条目」时提前执行
