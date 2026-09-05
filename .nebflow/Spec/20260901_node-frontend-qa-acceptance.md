# #27 方向调整前端交付验收报告（QA Pass）

- **日期**：2026-09-01
- **验收人**：qa-frontend
- **产出者**：Frontend
- **交付**：分支 `feat/node-frontend`，commit `6f89d04f48dddd061fba02be71f836f1c594b208`，worktree `/Users/dev/Claude code/.nb-worktrees/nb-node-frontend`
- **验收基准**：隔离静态实例 `http://127.0.0.1:8322`（Python http.server，PID 10882，非宿主，cwd = worktree `src/main/resources/web`，serving 交付物）
- **结论**：**PASS（全部 5 条验收条件 + 独立复验通过）**

---

## 交付边界核对

`git show 6f89d04f --stat`：14 文件改动，**全部在 `src/main/resources/web/`**，无越界。
- 新增：`js/agentFileViewer.js`、`js/flowMapTab.js`（由 `flowMapView.js` 重命名）、`js/projectTab.js`
- 删除：`js/projectPanel.js`、`js/flowMapView.js`（重命名迁移）
- 修改：`index.html`、`css/flowMap.css`、`css/projectPanel.css`、`js/activityBar.js`、`js/flowHelpers.js`、`js/flowViewers.js`、`js/main.js`、`js/nodeData.js`、`js/locales/{en,zh-CN}.js`
- 基线：HEAD `6f89d04f` 基于 main ancestor `7251b2e5`（`git merge-base --is-ancestor 7251b2e5 HEAD` = YES）

**路由完整性（静态文件替身关键核对）**：所有新增/改动文件均落在已挂载路径——`/js/**`（任意深度经 `WebSocketRoutes.jsRoutes` 服务）、`/css/<file>`（单段，已服务这些 css）。**未引入任何新子目录路由**，故 Python 静态服务作测试台不掩盖路由缺口（无缺口可掩盖）。`js/flowMapView.js`、`js/projectPanel.js` 已无悬空引用（grep 仅命中 `projectPanel.css` 仍存在并被 index.html 引用）。

---

## 验收条件逐项结果

| # | 验收条件 | 结果 | 证据 |
|---|---------|------|------|
| 1 | 标签页形态：Projects 开 Canvas 标签页（与 flow-run 同形态）；点项目名开第二个 flow-map tab；active 切换正确；关闭标签页不影响项目数据 | ✅ PASS | 见 A |
| 2 | 按钮位置：projects-btn 占原 Team/Flow 按钮区位（index.html 在 teams/flows-btn 之前）；teams/flows-btn 仍存在 | ✅ PASS | 见 B |
| 3 | Agent.md 查看/编辑：覆盖层打开、编辑器含内容、save 生效（mock 状态提示） | ✅ PASS | 见 C |
| 4 | 团队面板范式：项目卡视觉与 team 面板一致（team-card 样式/标题/状态摘要/工作区字段） | ✅ PASS | 见 D |
| 5 | overlayRoot 修复：从 flow-map/projects tab 点节点详情 → viewer 在 active pane 可见、可关闭 | ✅ PASS | 见 E |

---

### A. 标签页形态（条件 1）

- 产者 harness `/tmp/nb-node-frontend/verify-node-tabs.mjs` **独立复跑 17/17 PASS**（textContent 断言非 DOM 存在性），无 page/console error：
  - `PASS Project button opens projects tab — active=项目`
  - `PASS flow-map opens as a tab (not new window) — ["团队","项目","phd-notebook"]`
  - `PASS flow-map tab is active — active=phd-notebook`
  - `PASS flow-map renders nodes — 4` / `renders edges — 2` / `TTL countdown — 2` / `worktree badge — 1`
  - `PASS flow-map header summary — 1 运行中 · 1 等待 · 2 已完成`
- **独立盲区探针** `/tmp/nb-node-frontend/qa-probe-close.mjs`（精准关闭 flow-map tab）：8/8 PASS
  - `flow-map tab removed precisely — 3->2`
  - `active switched to a remaining tab, NOT the removed flow-map — active=项目`
  - `projects pane is on-screen (visible) after flow-map close`
  - `project cards rendered after close (data not wiped) — ["phd-notebook","nebflow","writer-blog"]`
  - `re-open flow-map after close renders nodes — 4`
- 截图 `/tmp/qa-shots-1-projects.png`、`/tmp/qa-shots-2-flowmap.png` 确认标签页形态（团队/项目/phd-notebook 三个 canvas-tab，active 高亮正确）。

### B. 按钮位置（条件 2）

- `index.html` DOM 顺序实测：`projects-btn`（L72）位于 `teams-btn`（L73）/`flows-btn`（L74）之前，占原 Team/Flow 按钮区位；`usage-btn`（L68）+ `activity-spacer`（L69）之后。
- 探针断言：`projects-btn exists`、`teams-btn still exists`、`flows-btn still exists`、`projects-btn before teams-btn in DOM order` 全 PASS。

### C. Agent.md 查看/编辑（条件 3）

- harness：`Agent.md opens editor overlay — {"hasOverlay":true,"hasTextarea":true,"hasSave":true,"title":"phd-notebook · Agent.md"}`、`Agent.md editor has content`、`Agent.md save works — ✓ 已保存`。
- 截图 `/tmp/qa-shots-4-agentmd.png` 确认：title「phd-notebook · Agent.md」、hint「此文件是项目工作区的 agent 指令…」、textarea 含 `# Agent.md` 内容、绿色「保存」按钮。

### D. 团队面板范式（条件 4）

- 项目卡复用 `.team-card.project-card`（`projectTab.js` `projectCardHtml()`），结构 = card header（title + Agent.md 图标 + 状态摘要 dot）→ 工作区字段（mono 路径 + folder-open 图标）→ description。
- 截图 `/tmp/qa-shots-1-projects.png` 目检确认与 team 面板视觉一致（同卡样式/标题/状态摘要/工作区字段）。
- harness：`project cards render 3 projects — ["phd-notebook","nebflow","writer-blog"]`、`show workspace — 3`、`show Agent.md entry — 3`、`show status summary — 3`。

### E. overlayRoot 修复（条件 5）

- **盲区探针** `/tmp/nb-node-frontend/qa-probe-blindspots.mjs` 独立验证（补产者 harness 未覆盖的「当前 active pane 可见性」）：
  - `overlay present`、`overlay is NOT hidden 0x0 (visible) — w=550 h=805`
  - `overlay is inside the ACTIVE pane — pane=flow-map-phd-notebook active=flow-map-phd-notebook`
  - `overlay inside an on-screen visible pane (fix objective: not the hidden 0x0 teams pane)` → PASS
  - `viewer closes` → PASS
- 截图 `/tmp/qa-shots-3-node-viewer.png` 确认 viewer 在 flow-map tab 内居中可见（title「调研-康普顿成像原理 · 节点结果」、meta「Explore · completed」、结果内容），可关闭（✕）。Agent.md 覆盖层从 projects tab 打开同样落在 active projects pane。
- 根因核对：`flowHelpers.js overlayRoot()` 现改为「首选 `.canvas-tab-pane.active`」+ fallback teams/flows + body；`.canvas-tab-pane` 基类 `split.css:631` 含 `position:relative`，故任意类型 tab pane 均可作 overlay 定位容器，覆盖 flow-run/flow-map/projects。

---

## 独立质量复验

- **checkJs 门禁**：worktree `node scripts/check-js-types.mjs` → `checkJs: 326 errors (baseline 326, zero-gate files: 3)` / `checkJs PASS: no new errors above baseline.`
- **无悬空引用**：`grep -rn "flowMapView\|projectPanel" src/main/resources/web/` 仅命中 `projectPanel.css`（仍存在并被引用），无指向已删 JS 的 import。
- **i18n 键一致**：`flowmap.resultTitle`/`flowViewers.save|saved|saving|saveFailed|loading`、`agentFile.hint`、`project.*` 等新引用键均在 `locales/{en,zh-CN}.js` 中存在（en/zh 同步新增）。

---

## 遗留说明（非阻塞）

1. **mock 数据**：`nodeData.js` 当前用 `MOCK_PROJECTS`/`MOCK_FLOWMAPS`/`MOCK_AGENT_FILES`，Backend #28 契约到后只需替换 `fetchProjects/fetchFlowMap/fetchAgentFile/saveAgentFile` + WS 驱动刷新（`nodeCreated/nodeUpdated/nodeCompleted/nodeRemoved` 已接），交互不变。
2. **生效需重启 JVM**：本交付改动仅在前端 `resources/web/`，运行中的宿主/sbt 实例不会热加载；合并进入生产需重启服务进程（隔离环境下验证，非宿主）。
3. **合并关卡 verify-web-assets**：本验收未在 Python 静态 server 上跑 `verify-web-assets.mjs`——该脚本映射的是**生产 http4s 路由器** URL，跑在静态服务器上等于用替身（正好是禁替身规则要堵的伪阳性）。已改为**路由分析法**核对（`/js/**` 任意深度 + `/css/<file>` 单段均已覆盖全部新增/改动文件，无新路由缺口）。合并门禁在 Manager 审合时对**真实隔离 http4s 实例**执行 `scripts/verify-web-assets.mjs`（产者已报 276/276）。

## 复现命令

```bash
# 交付基线
git -C "/Users/dev/Claude code/.nb-worktrees/nb-node-frontend" merge-base --is-ancestor 7251b2e5 6f89d04f && echo OK
git -C "/Users/dev/Claude code/.nb-worktrees/nb-node-frontend" show 6f89d04f --stat

# 隔离实例 PID 验身
lsof -i :8322   # PID 10882, Python, cwd = worktree web dir

# 产者 harness（17/17）
cd /tmp/nb-node-frontend && BASE=http://127.0.0.1:8322 node verify-node-tabs.mjs

# 独立盲区探针（8/8 + overlay-root 断言）
BASE=http://127.0.0.1:8322 node /tmp/nb-node-frontend/qa-probe-close.mjs
BASE=http://127.0.0.1:8322 node /tmp/nb-node-frontend/qa-probe-blindspots.mjs

# checkJs 门禁
cd "/Users/dev/Claude code/.nb-worktrees/nb-node-frontend" && node scripts/check-js-types.mjs
```
