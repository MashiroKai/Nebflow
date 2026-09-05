# 重启后补验② Agent.md 面板 + Flow Map（真实实例 8080，只读+编辑还原）

- **日期**：2026-09-01
- **验证人**：qa-frontend
- **上位指令**：Manager [PARALLEL]「重启后补验②：Agent.md 面板查看/编辑」
- **环境**：**真实实例 8080（宿主）**，main @9bb38304；constraint：不触发节点/spawn、编辑后还原、只验证不修改代码
- **结论**：任务 1/2/3 **PASS**；任务 4 视图打开但本次自动化会话渲染为空（finding）；另发现 **#27 面板在真实实例上的渲染可靠性问题**（见 ⑥），需人工确认。

---

## 前置：基线核实
- 项目挂载：`GET /api/projects` → 200 `['phd-notebook']` ✓
- Agent.md 内容源：`~/.nebflow/projects/phd-notebook/.nebflow/Agent.md`（1599B / 852 字符），含 CZT 亚像素/素材库八目录/阶段 1 试点上下文 ✓
- flow-map：`GET /api/projects/phd-notebook/flow-map` → 200，当前节点 `cancel-test-10`(completed) / `cancel-test-11`(completed) / `lit-survey-light`(running)（cancel-test-9 已 TTL 归档消失）

## 任务 1 — Agent.md 内容显示 ✅ PASS
打开 Project 面板 → phd-notebook → Agent.md 视图，textarea 内容 = 852 字符，逐项命中：
- `CZT 像素型探测器` ✓ / `亚像素` ✓ / `阶段 1 试点` ✓ / 素材库八目录（ideas/literature/data/methods/figures/observations/refs/assets）全部命中 ✓
- 截图 `/tmp/nb-real/agentmd-panel-before.png`：标题「phd-notebook · Agent.md」+ hint + 内容 + 保存按钮，形态正常。

## 任务 2 — 编辑/保存/持久/还原 ✅ PASS（功能路径）
- 编辑：textarea 追加唯一标记 `QA-RESTORE-MARKER-20260901` → 保存 → `GET /api/projects/phd-notebook/agent.md` 复读：**标记存在**（len 852→890）→ **PUT 持久化确认**。
- 还原：`PUT` 回原内容（`/tmp/nb-real/agent-original.md` 852 字符）→ `GET` 复读：`==orig True`、无标记（len 852）。**无残留测试标记** ✓。
- 说明：保存按钮的**指针点击**在真实实例上被 pane 拦截（见 ⑥），本次以程序化 `click()` 驱动 PUT（功能链路完整）；REST 往返已独立用 curl 复核（任务 3）。

## 任务 3 — REST 侧 GET/PUT 往返 ✅ PASS
| 步骤 | 结果 |
|---|---|
| GET agent.md | 200，内容=磁盘一致（852） |
| PUT 标记 | 200（body `{content}`） |
| GET 复读 | 标记在（890）——持久化 |
| PUT 原内容 | 200 |
| GET 复读 | `==orig True`、无标记——还原 |
> auth 形式：`Authorization: Bearer <token>`（auth.json 为裸 JSON 字符串）。

## 任务 4 — Flow Map 视图渲染 ⚠️ FINDING
- 视图**能打开**为标签页（activeTab=`phd-notebook`），但**本次自动化会话渲染为 dag-empty「暂无节点，项目空闲」**（`nodes:[]`, `edges:0`, `summary:空闲`），**而后端 flow-map 端点此时有 3 个真实节点**（cancel-test-10/11 + lit-survey-light）。
- 截图 `/tmp/nb-real/flowmap-render.png`。→ 前端未渲染出后端真实 NodeList 载荷。

---

## ⑥ 发现：#27 面板在真实实例上的渲染可靠性问题（非阻塞但需关注）

在真实实例上多次独立加载观察到的具体现象（相关度从高到低）：

1. **FLOW_CSS 未注入到「项目」恢复标签页**：`openProjectTab` 含 `if (!pane.querySelector('#team-canvas-style')) pane.insertAdjacentHTML('afterbegin', FLOW_CSS)`（合并源码 projectTab.js L19-22 确认存在），但激活 `#projects-btn` 后实测 `#team-canvas-style` 仍 `false`、DOM 中无任何含 `flow-viewer-overlay` 的 `<style>`（`overlayCssAnywhere:false`）。
   - 后果：`.flow-viewer-overlay` 的 `z-index:60`/`pointer-events:auto` 不生效 → Agent.md 编辑器覆盖层样式/置顶缺失 → **保存按钮指针点不到**（`elementFromPoint` 返回 `canvas-tab-pane active`，非保存按钮），textarea 无法可靠聚焦。
2. **项目卡渲染不稳定**：`#projects-btn` 后同一次干净加载，有时卡片出现（agentmd-edit 成功点开）、有时 `cardCount:0`（projects-pane-inspect / flowcss-diagnose2 / mouse-click-test 点卡片超时）；flowmap-render 也需**最多 6 次重试**才出现卡片。
3. **Flow-map 空渲染**：同上，展开后显示 dag-empty 而非真实节点。

> 谨慎性说明：以上为**我的无头自动化对活实例多次加载**的观察，可能部分受实时 WS 广播（lit-survey-light running / 节点 TTL 事件）+ 多 onMessage 订阅触发的 render churn 影响；但 FLOW_CSS 未注入是确定性的 DOM/样式事实。**建议作者在真实浏览器手动点一次**（项目→agent.md 编辑保存、项目→flow-map）以复现/排除。我不修复（只验收不修改）。

---

## 清理（透明披露）
- Agent.md：编辑→还原，最终 `==orig`、无标记（已复核）。
- 共享标签态 `~/.nebflow/canvas_tabs.json`：验证期间我打开过 `flow-map-phd-notebook` 测试标签 → 已关闭；**最终保留作者真实标签** `[项目, 20260901_mhs-model-hardware-standard.md, 20260901_agent-recursion-analysis.md]`（未改动作者 mhs/agent-recursion 文档标签）。
- 未触发任何节点创建/spawn（全程只读 + Agent.md 文件 PUT）。

## 证据文件
- `/tmp/nb-real/agentmd-panel-before.png`（任务1 内容显示）
- `/tmp/nb-real/agentmd-panel-edited.png`（编辑后）
- `/tmp/nb-real/flowmap-render.png`（任务4 空渲染）
- `/tmp/nb-real/agent-original.md`（原内容备份，还原源）
- REST 往返：`agent-get.json` / `agent-get2.json` / `agent-put.json`

## 复现命令
```bash
TOKEN=$(python3 -c "import json;print(json.load(open('$HOME/.nebflow/auth.json')))")
# REST 往返
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/projects/phd-notebook/agent.md
curl -s -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"content":"..."}' http://localhost:8080/api/projects/phd-notebook/agent.md
# 面板验证（node /tmp/nb-real/*.mjs）：agentmd-edit / flowmap-render
```
