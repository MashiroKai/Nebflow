# #37 Project+Node+Flow Map v5 阶段 0 前端 — QA 验收报告（PASS 放行）

- **日期**：2026-09-01
- **验收人**：qa-frontend
- **产出者**：Frontend
- **交付**：worktree `/tmp/nb-project-node-frontend`，分支 `feat/project-node-frontend`，commit **77157827**（基于 main @8d938194）
- **方案**：`~/.nebflow/docs/Nebflow/20260831_project-node-architecture.md` §3.5 + §2.1/§2.2/§2.6/§2.7
- **改动**：11 文件，全在 src/main/resources/web/（index.html / activityBar.js / explorer.js / projectTab.js / flowMapTab.js / nav.css / projectPanel.css / flowMap.css / i18n.js / locales en+zh）
- **验收基座**：**隔离真实 http4s 实例** `http://localhost:8098`（worktree /tmp/nb-project-node-frontend，java PID **52074**，health 200）
- **宿主保护**：8080 = PID 33481 我宿主全程未触碰；8098 已 PID 验身为独立 java 实例（cwd=`/private/tmp/nb-project-node-frontend`）
- **结论**：**PASS —— 放行。**

---

## 两个关键实现判断核验（交付重点）

### 判断 1：旧按钮保留 id 且留在 DOM ✅
index.html 中 `teams-btn` / `flows-btn` 保留原 id、留在 DOM（收进 body 层 `#legacy-pop`），activityBar.js 注释明确「registerCanvasPanelButton 按 id 绑定并同步 pressed 态，换 id/移出 DOM = 旧面板静默失联」。
- 实测：`registerCanvasPanelButton('teams','teams-btn',...)` / `('flows','flows-btn',...)`（flowCanvas.js:651-652）用 `document.getElementById(buttonId)` 绑定（canvas.js:362）。
- **盲区探针**：点击 Teams 项 → `[data-tab-id="teams"]` pane 出现、`teams-btn.active=true`、`aria-pressed="true"`；点击 Flows 项 → `flows-btn.active=true`。**旧面板正常打开，未失联**。本批未改 flowCanvas/canvas/agentManager 的打开逻辑（diff 不含）。

### 判断 2：popover 挂 body 层（不被 activity-bar filter 裁切）✅
index.html 把 `#legacy-pop` 放在 `#activity-bar` 外的 body 层，注释记载「活动栏 overflow:hidden + backdrop-filter 使 filter 成为 fixed 包含块，浮层嵌里面会被裁成 48px 一条（DOM 在、屏幕不可见）」。deliver 明确警告"只断言 hidden===false 会假绿"。
- **实测（真实实例）**：`painted:true`（r.width>0 && r.height>0 && r.right<=innerWidth && r.bottom<=innerHeight）、`clearsBar:true`（pop 左边 ≥ 活动栏右边，未被裁）、`onTop:true`（elementFromPoint 命中 pop 内元素）。**实际绘制 + 可命中，非仅 DOM 存在**。
- 锚定 anchorLegacyPop 视口夹取：resize 后 `inViewport:true`（top/left 均 clamp）。

---

## 验收条件逐项核对

### 1. 活动栏：Projects 可见且在旧面板入口之前；teams/flows 栏内不可见（收进 popover）；点 Projects 打开项目标签页 ✅
```json
projects-btn: visible, legacy-btn: visible, teams-btn: hidden, flows-btn: hidden
order: [...,projects-btn,legacy-btn,...]  (projects 在 legacy 之前)
```
- Projects 独占原 Team/Flow 槽位，teams/flows 在栏内不可见（收进 popover）
- verify-real：`projects sits before the legacy entry` PASS（order 索引比较）

### 2. 旧面板 popover：绘制在视口内 + 可点击命中；点项打开对应面板 + popover 关闭；Esc/外点关闭；毛玻璃无遮罩激活态阴影 ✅
- **绘制+命中**：`painted:true clearsBar:true onTop:true`（见判断 2）
- **点选打开+关闭**：Teams → pane 出现 + 关闭；Flows → flows-btn active + 关闭（盲区探针 8/8）
- **Esc 关闭 / 外点关闭**：均 PASS
- **resize 重锚**：`inViewport:true` PASS
- **毛玻璃**：`blur(24px) saturate(1.15)`、`bg rgba(255,255,255,0.55)`（非透明）✓
- **无遮罩**：`no dimming overlay behind it: false`（无 overlay-dim）✓
- **激活态阴影**：活动栏按钮 active class 同步（canvas.js syncCanvasPanelButtons）

### 3. Project 卡片六项齐全 + 标签本地化（无 project. 裸键）✅
```json
name: phd-notebook | workspace: /Users/dev/phd-notebook | openBtnPath: /Users/dev/phd-notebook
agentLink: 查看 / 编辑 | running: 2 | desc: 博士论文调研与写作试点项目 | brief: 2 节点运行中
labels: ["工作区","Agent.md","运行中 agent"]  (无 project. 裸键)
headerActions: 0  (无重复 Agent.md 图标)
```
- 六项：项目名（点击开 Flow Map）/ 工作区路径 + 打开按钮 / Agent.md 查看-编辑入口 / 运行中 agent 数 / 描述 / 简要状态，**全齐全且本地化**

### 4. 工作区按钮：侧边栏文件浏览器根切到工作区 + Files 面板展开 ✅
```json
{collapsed:false, filesActive:true, title:"phd-notebook", titleAttr:"/Users/dev/phd-notebook"}
```
- openExplorerAt(path)：persistRoot + 清展开态 + showSidePanel('files') + renderTree —— **标题=目录名、title=全路径**（§3.5 真实动作，非 toast 占位）

### 5. Agent.md：viewer 显示内容、可编辑、保存 PUT + 成功 ✅
- viewer 打开含内容（'arXiv'）、textarea 存在
- 保存 PUT `/api/projects/<name>/agent.md` body=`{content:"...编辑后的内容..."}`，status「✓ 已保存」

### 6. Flow Map 标签页：卡片字段 + barrier 多入边 + 边线 + TTL 每秒递减 + 终态隐藏但 total 在 + 点卡详情 ✅
- 6 节点 mock（§2.2）：5 可见 / total=6（已到期 ttlLeftSec=0 的不渲染但计数在）
- 卡片：name/agent/status/worktree 徽标(1)/result 摘要(4)
- barrier hint = 1（merge 节点 dual 入边）
- edges = 2（进 barrier）
- **TTL 递减实测**：`⏱ 239s → ⏱ 237s`（1s ticker）
- 点卡 → result 详情（`flow-agent-block-readonly` + 全文）

### 7. 边语义（§2.7）：completed 上游=实线加重（无行军蚁）；running 上游=虚线动画；其余静止 ✅
```json
edgeStates: ["delivered","inflight"]
deliveredStroke: "none" (实线无行军蚁)
```
- `edgeStateOf(n)`：completed→delivered / running→inflight / else→idle
- delivered 边 strokeDasharray='none'（实线）、inflight 边虚线动画（CSS flowMap.css）

### 8. WS 事件订阅：nodeCreated/Updated/Completed/Removed 在 projectTab.js + flowMapTab.js ✅
```js
projectTab.js:168-171  onMessage(nodeCreated/Updated/Completed/Removed) → rerenderProjectsTab()
flowMapTab.js:332-335  onMessage(same 4) → refreshOpenFlowMap()
```

### 9. 静态资源：无新增文件，无 4xx（含动态 import 链）✅
- **C1 全量扫描**：`278/278 PASS`（真实实例，零 4xx）
- **动态 import 链**：`flowViewers.js` + `viewers/{shared,monaco,markdown,yaml,html,image,pdf,docx,xlsx,pptx,epub}.js` + `explorer.js`（projectTab 动态 import）均 **200**
- 注：交付列 `flowViewers/utils.js` 路径——实际链是 `flowViewers.js` + `viewers/*.js`，非 `flowViewers/utils.js`（该子目录不存在），已按真实链验证全 200

---

## 附加独立核验
- **checkJs 门禁**：`326 = baseline` PASS
- **check-circular**：`acyclic (73 files)` PASS（新增 explorer→activityBar 依赖未成环）
- **i18n 键数**：en 884 = zh 884，无单边键；新增键对称（activity.legacy/project.runningAgents/project.agentFileLabel/project.agentFileOpen/project.workspaceOpened/project.openWorkspaceFail），删 openWorkspaceSoon
- **改动范围**：11 文件，全在 web/，零 Scala
- **盲区探针**（我的独立交叉验证）：**8/8 PASS**——点选 Teams/Flows 打开旧面板 + popover 关闭、外点关闭、Esc 关闭、resize 重锚、无 console error
- **交付自验脚本**（verify-real.cjs 改造跑真实实例）：**48/48 PASS**

---

## 关于我独立探针的一次修正说明
我的首批盲区探针曾报 Teams 面板 FAIL，后甄别为**探针 async 时序 bug**（openTeams 是 async：autoRestore().then() + fetchRunningFlows().then()，需轮询等待），非代码缺陷。轮询修正后 Teams pane 正常出现（`paneText:"团队"`、`btnPressed:"true"`）。console 仅 WebSocket 403（未认证实例 WS 握手失败）+ persistTabs 日志，无 JS 异常。

## 结论
**PASS —— 放行。** 九个验收条件全绿，两个关键实现判断（旧按钮保 id 留 DOM / popover 挂 body 层）均实测确认。checksJs/circular/i18n/静态资源/动态 import 链全 PASS。

安全：8098 = java PID 52074（cwd=/private/tmp/nb-project-node-frontend），8080 = 33481 我宿主全程未触碰。隔离实例确认在跑新代码（served index.html 含 legacy-pop / projects-btn，非快照陈旧）。

已知边界（accept）：① 真实后端联调（WS 实时、真实 flow-map.json）属阶段 1 ② Flow Map 深度 0 多节点横向滚动非缺陷 ③ 旧面板阶段 3 才整体移除。

报告：~/.nebflow/docs/Nebflow/20260901_project-node-stage0-frontend-qa.md
