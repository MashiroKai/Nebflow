# 子智能体面板行显示名修复批 — delegate 行全生命周期 = 批次标题

日期：2026-10-22 · 分支：`delegate/delegate-nebflow-6aae2fd2` · 提交：`d7b0f898f`
证据目录：`evidence/20261022_subagent-row-display-name__delegate-nebflow-6aae2fd2/`
spec：`docs/design/subagent-row-display-name-spec.zh-CN.md`

## 0. ⚠️ 恢复事件（本批过程记录）

工作树于本批实施期间被**外部 `git reset` 回 HEAD**（HEAD reflog：`reset: moving to
HEAD` ×2——与 #566 批 delegate-nebflow-aab5503d 记录的同款平台行为，非本会话所为），
5 个已编辑文件（3 Scala + 1 spec + 1 前端）的未提交改动被清盘。

恢复方式：全部编辑从会话上下文确定性重放（Edit 原文逐字），重放后立即提交
`d7b0f898f` 锁定，后续批内改动随做随交。注：工作树另有**外来脏状态**
（`chatQueue.js` 回退旧版 + 4 张 chatinput 证据 png），非本批所为，未提交、未触碰。

## 1. 症状与根因（逐跳钉死，全部实查）

作者实测（截图 `612526280445208_截屏2026-10-22 17.26.38.jpg`）：子智能体面板
delegate 行「先是一个长的名词，然后换成这个短的」——8 行全部显示
`delegate-nebflow-2c2db017` 这类 sessionId。

根因三跳：

1. **live 脸正常**：`agentStart` 带 `name=agentDef.name`("nebflow") +
   `taskDescription=state.sessionName`(=批次标题) ⇒ 新建行 = 长名。
2. **恢复脸缺档**：`activeAgentEntryJson` 的 `agentName` 链 meta → displayName →
   sessionId 对 delegate 恒落到 sessionId——实查 `sessions/_index.json` 842 条中
   **0 条 delegate 会话**（meta 恒 None）；`registerAgent` 对 delegate 不设
   displayName。
3. **合并规则①放大**：delegate 的 live agentId == sessionId == 快照 agentId，
   前端「同 key 快照为准」——**每次开面板**（getActiveAgents ≥1s 冷却）把 live
   长名行覆盖成短 id；重启后全行 id。

两持久面实查均持标题：`DelegateRegistry.Record.title`（`delegate-nebflow-2c2db017`
=「【delegate 监督链自动恢复机制批】（作者令，引擎机制补全）」，落盘跨重启）+
`SubAgentTask.description`（同值）——恢复脸两边都不查，本批对齐。

## 2. 修复面

后端（恢复脸权威）：
- `WebSocketRoutes.delegateDisplayTitle`：纯函数解析缝（Delegate 主源
  DelegateRegistry.Record.title → SubAgentTask.description 回填；SubTask 只走
  任务记录；其余 kind None）。
- `WebSocketRoutes.activeAgentEntryJson`：`agentName` 链扩第四档
  meta → displayName → **delegateTitle** → sessionId（缺省 None 向后兼容）。
- `WsSessionChatHandlers.handleGetActiveAgents`：薄调用（IO.blocking + fail-soft）。

前端（显示名契约 + id 降级）：
- `web/js/main/bgAgents.js` `bgTaskRowHtml`：组头行主名 =
  `rowTitle`(DelegateRegistry provider 镜像，跨重启存活) → `task` 首行 → name(≠sid)
  → sid → id；sessionId 降为 title tooltip（`【批名】 · delegate-nebflow-xxxx`）。
  非组头行逐字不动；CSS 既有截断零改动。

## 3. 验证

### 3.1 前后对照（`tests/subagent-row-display-name-shots.mjs`）

| 截图 | 内容 |
|---|---|
| `s1-shrink-before.png` / `-after.png` | **收缩触发点**：live 长名行 → 开面板退化形快照覆盖。修前覆盖后 = `delegate-nebfl…`；修后仍 = 【delegate 监督链自动…】/【canvas 顶部胶囊居…】 |
| `s2-restart-restore-before.png` / `-after.png` | **重启恢复脸**：无 live 帧纯快照。修前存量退化形行 = id；修后两种形态（后端回填形 + 存量退化形）均 = 批名首行 |
| `s3-density-8-before.png` / `-after.png` | **八批密度**（最劣输入：快照全退化形）：修前 8/8 行 `delegate-nebfl…` 不可辨（复刻作者截图）；修后 8/8 行各自批名首行一眼可辨 |

执行记录：`MODE=before`（HEAD~1 归档树）ALL PASS（3 条复现断言）；
修后 ALL PASS（8 条契约断言）。

### 3.2 测试全绿

- Scala：`ActiveAgentsEntrySpec` +6（delegateTitle 回填/四档顺位/缺省回归/
  解析缝三用例）+ `TaskBadgeActiveAgentsEntrySpec`（见 §4 运行记录）。
- 前端邻族：delegate-ui-twolayer 5/5、turn-activity-stream、
  taskpanel-blocked-retire、tasklist-reconcile-shots、bgagent-orphan-cleanup 全绿
  （12 passed + 1 见 §3.3）。
- 门禁：checkJs 257 = 基线零新增（干净提交上复验 PASS）；`npm run build` 179
  sources orphans 0。

### 3.3 既有失败（非本批，已确证）

- `tests/delegate-panorama.spec.mjs` T3（canvas-header 遮挡返回钮的点击拦截
  flake）在干净 `cbdf4a527`（本批基线，零改动）上**同样失败**——与本批无关，
  原样留档。
- 工作树 checkJs 报的 `input.js TS2554` 来自外来脏状态（旧版 `chatQueue.js` 与
  HEAD `input.js` 不配套）；干净提交上 257 = 基线 PASS。

## 4. 存量回填（零迁移）

DelegateRegistry 记录在册者落盘自带 title（实查 10-22 全部在册记录）——修复
部署后，下一次 getActiveAgents 快照（开面板/刷新/重连任一触发）即把存量「已是
id」的恢复行回填为批次标题；无数据迁移、无记录改写。
