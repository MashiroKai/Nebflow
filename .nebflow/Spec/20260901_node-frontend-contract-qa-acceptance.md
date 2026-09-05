# #27/#28 0b 契约对接前端交付验收报告（QA Pass）

- **日期**：2026-09-01
- **验收人**：qa-frontend
- **产出者**：Frontend
- **交付**：分支 `feat/node-frontend`，commit `499d86d49106b8932629a8d688c7c09463f0facc`，worktree `/Users/dev/Claude code/.nb-worktrees/nb-node-frontend`
- **前置基线**：上一增量 `6f89d04f`（#27 骨架，已 QA PASS）为本 HEAD 的祖先（`git merge-base --is-ancestor 6f89d04f HEAD` = YES）
- **验收基准**：隔离静态实例 `http://127.0.0.1:8322`（Python http.server，PID 10882，非宿主，cwd = worktree `src/main/resources/web`）+ Playwright 拦截 `/api` 返回契约 shape + MockWS 注入节点事件
- **结论**：**PASS（6/6 验收条件 + 独立复验通过）**

---

## 交付边界核对

`git show 499d86d4 --stat`：5 文件改动，**全部在 `src/main/resources/web/js/`**（`nodeData.js`、`ws.js`、`flowMapTab.js`、`projectTab.js`、`agentFileViewer.js`），无越界。
- **新增静态文件：无**（仅改 JS）。路由完整性：全部落在 `/js/**`（任意深度经 `WebSocketRoutes.jsRoutes` 服务），**未引入新子目录路由**，静态测试台不掩盖路由缺口。

## 契约对齐核对（nodeData.js ↔ Backend #28 0b 契约文档）

对照 `~/.nebflow/docs/Nebflow/20260901_project-node-contract.md`：

| 契约点 | 契约文档 | 前端实现 | 一致 |
|---|---|---|---|
| §1 项目列表 | `GET /api/projects` → 200 `{projects:[...]}`，401 失败 | `API.projects='/api/projects'`，`fetchProjects()` GET + `authHeaders()`，校验 `data.projects` 数组 | ✅ |
| §1 flow-map | `GET /api/projects/<name>/flow-map` → NodeList，404 `{error}`（未挂载） | `API.flowMap(name)`，404→返回空 NodeList（dag-empty），非 404 错误上抛 loadFail | ✅ |
| §3 NodeList 载荷 | `{nodes:[{id,name,agent,status,in,out,hasWorktree,worktree,result,retries,createdAt,completedAt,ttlLeftSec}], worktrees:[], meta:{project,updatedAt,archived}}` | `renderFlowMap`/`nodeHtml`/`summarize` 消费字段逐一对应 | ✅ |
| §2 WS 广播帧 | `{type,project,nodeId,node}` 无 sessionId，全应用级 | `ws.js` 4 种类型入 `GLOBAL_MSG_TYPES`；`flowMapTab`/`projectTab` 均 `onMessage` 订阅 | ✅ |

**契约语义核对（ws.js 路由）**：节点事件无 `sessionId` 且为 `GLOBAL_MSG_TYPES` 成员 → `onmessage` 路由行 452 走 `else setActiveView(savedView)`（**保留当前视图不 setActiveView 为 primary**），随后行 576-580 分发到 `handlers[msg.type]`（projectTab + flowMapTab 都收）。GLOBAL 过滤（行 431-435）跳过会话不匹配短路。机制正确。

---

## 验收条件逐项结果

| # | 验收条件 | 结果 | 证据 |
|---|---------|------|------|
| 1 | 项目列表来自契约（非 mock 名），工作区来自契约字段 | ✅ PASS | 见 A |
| 2 | 未挂载项目 flow-map 404 → 项目卡「空闲」（dag-empty 态），不报错 | ✅ PASS | 见 B |
| 3 | flow-map 来自契约（节点/边/TTL/worktree/header 摘要） | ✅ PASS | 见 C |
| 4 | WS 实时刷新 flow-map（nodeUpdated/nodeRemoved/nodeCreated） | ✅ PASS | 见 D |
| 5 | WS 实时刷新 projects（状态摘要重新计算） | ✅ PASS | 见 E |
| 6 | Agent.md 查看/编辑仍可用（契约 §1 无 agent.md REST 端点，暂用 mock） | ✅ PASS | 见 F |

### A. 项目列表来自契约（条件 1）
- 产者 contract harness `/tmp/nb-node-frontend/verify-contract.mjs` **独立复跑 15/15 PASS**（拦截 `/api/projects` 返回契约 shape `alpha-demo`/`beta-demo`）：
  - `projects render from /api/projects — ["alpha-demo","beta-demo"]`
  - `project workspace from contract — ["/srv/alpha","/srv/beta"]`
- 独立盲区探针 `qa-probe-contract-blindspots.mjs`：`only contract projects render`、`mock names GONE (no phd-notebook/nebflow/writer-blog)` → **确认 MOCK_PROJECTS 已移除**，仅契约名渲染。

### B. 未挂载 404 → 空闲（条件 2）
- **独立盲区探针**（产者 harness 用的是 200-空 NodeList，未测 404 分支；本探针拦截 flow-map 返回 404 `{error}`）：
  - `404 project card shows 空闲`
  - `404 flow-map tab shows dag-empty (no crash)`
  - `404 flow-map tab active — active=gamma-404`
  - 无 page/console error（未误报开发态资源加载噪音）
- 实现核对：`nodeData.fetchFlowMap()` 404→`{nodes:[],worktrees:[],meta}` → `summarize` nodes.length===0 → brief='空闲'；`renderFlowMapTab` 空 nodes → dag-empty。与契约 §1「未挂载 → 404」语义一致。

### C. flow-map 来自契约（条件 3）
- harness：`flow-map tab active — active=alpha-demo`、`flow-map nodes from contract — ["alpha-node-1","alpha-node-2"]`、`edges — 1`、`TTL countdown — 1`、`worktree badge — 1`、`header summary — 1 运行中 · 1 已完成`。全部来自契约 NodeList 载荷（非 mock phd-notebook 拓扑）。

### D. WS 实时刷新 flow-map（条件 4）
- harness：`nodeUpdated refreshes flow-map status — before=running after=completed`、`refresh shows node result — Alpha result two`、`nodeRemoved removes node — ["n-a2"]`、`nodeCreated adds node — ["n-a2","n-a3"]`。
- 独立盲区探针 `qa-probe-contract-cd.mjs`：`WS nodeUpdated preserves active tab (not torn down) — active=alpha-demo`、`WS refresh applied new status — status=completed`、`WS refresh shows new result — alpha done`、`WS refresh shows TTL on newly completed node — ⏱ 300s`。**关键补验：WS 节点事件不 tear down 当前视图**（GLOBAL_MSG_TYPES 修复生效）。

### E. WS 实时刷新 projects（条件 5）
- harness：`projects tab recomputes summary on WS — 1 节点等待`（初始「1 节点运行中」→ WS 后重算为等待）。
- 截图 `/tmp/nb-contract-projects.png` 确认 alpha-demo 卡片摘要为「1 节点等待」（契约数据重算），workspace `/srv/alpha`。

### F. Agent.md 查看/编辑（条件 6）
- **独立盲区探针**：`Agent.md editor opens (mock) — {"hasOverlay":true,"hasTextarea":true,"hasSave":true,"title":"alpha-demo · Agent.md"}`、`Agent.md save works (mock) — ✓ 已保存`。
- ⚠️ 符合验收前置说明：契约 §1 无 agent.md REST 端点 → 暂用 `nodeData` 本地 mock（`MOCK_AGENT_FILES`），`agentFileViewer.js` 已移除 `API` 导入（不再调用不存在的端点），注释标注待后端补 GET/POST 替换。

---

## 独立质量复验

- **checkJs 门禁**：worktree `node scripts/check-js-types.mjs` → `checkJs: 326 errors (baseline 326, zero-gate files: 3)` / `checkJs PASS: no new errors above baseline.`
- **无残留 mock/API 悬空引用**：`grep -rn "MOCK_PROJECTS\|MOCK_FLOWMAPS\|API.agentFile\|API.openWorkspace" js/` → 无残留（命中仅为 i18n `project.openWorkspace` 显示标签 + projectTab button title/aria，非 nodeData API 调用）。
- **i18n 键一致**：`flowmap.cardRunning/resultTitle/runningDetail/noResultDetail`、`agentFile.hint` 等新引用键在 `locales/{en,zh-CN}.js` 存在。

### 遗留说明（非阻塞）
1. **契约测试基座是「虚构后端」**：本验收通过 Playwright 拦截 `/api` 返回契约 shape + MockWS 注入节点事件，验证的是**前端↔契约对齐**。真实 Backend #28 0b 实现是否与契约一致，属 qa-backend 侧验收（Backend 交付时）。
2. **合并关卡 verify-web-assets**：本增量无新增静态文件、无新子目录路由（已路由分析），生产 http4s 挂载点不缺口；门禁在 Manager 审合时对**真实隔离 http4s 实例**执行。
3. **生效需重启 JVM**（前端资源改动，见上增量）。
4. **minor 遗点**：`agentFileViewer.js` 导入 `authHeaders`（`flowHelpers`）但已被 mock 数据层替代后不再使用——建议移除该死导入（纯代码卫生，不影响本次功能；契约补 agent.md 端点后此文件改用真实 GET/POST 时自然清除）。

## 复现命令

```bash
# 交付基线
git -C "/Users/dev/Claude code/.nb-worktrees/nb-node-frontend" merge-base --is-ancestor 6f89d04f 499d86d4 && echo OK
git -C "/Users/dev/Claude code/.nb-worktrees/nb-node-frontend" show 499d86d4 --stat

# 隔离实例 PID 验身
lsof -i :8322   # PID 10882, Python, cwd = worktree web dir

# 产者 contract harness（15/15）
cd /tmp/nb-node-frontend && BASE=http://127.0.0.1:8322 node verify-contract.mjs

# 独立盲区探针（404 分支/视图保持/Agent.md）
BASE=http://127.0.0.1:8322 node /tmp/nb-node-frontend/qa-probe-contract-blindspots.mjs
BASE=http://127.0.0.1:8322 node /tmp/nb-node-frontend/qa-probe-contract-cd.mjs

# checkJs 门禁
cd "/Users/dev/Claude code/.nb-worktrees/nb-node-frontend" && node scripts/check-js-types.mjs
```
