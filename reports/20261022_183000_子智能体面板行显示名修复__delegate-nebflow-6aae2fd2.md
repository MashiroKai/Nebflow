# 子智能体面板行显示名修复批 · 收口报告（delegate 行全生命周期 = 批次标题）

2026-10-22 · `delegate-nebflow-6aae2fd2`（提交 `d7b0f898f` 修复 + `d1b53eea2` 验证/文档/证据）

## 1. 症状与诊断

作者实测（截图 `612526280445208_截屏2026-10-22 17.26.38.jpg`，8 行全 id）：
「为什么现在还是老是会有一个收缩过程，先是一个长的名词，然后换成这个短的。
这个可读性真的很差。」

**根因三跳（逐跳钉死，全部实查）**：

1. **live 脸正常**：`agentStart` 携带 `name=agentDef.name`("nebflow") +
   `taskDescription=state.sessionName`(=批次标题) ⇒ 新建行 = 「nebflow · 【批名】」长名。
2. **恢复脸缺档**：`activeAgentEntryJson` 的 `agentName` 链 meta → displayName →
   sessionId 对 delegate 恒落 sessionId——实查 `sessions/_index.json` 842 条中
   **0 条 delegate 会话**（meta 恒 None）；`NodeRunner.registerAgent` 对 delegate
   **不设 displayName**。
3. **合并规则①放大（收缩触发点）**：delegate 的 live `agentId` == 会话 id == 快照
   `agentId`，前端「同 key 快照为准」——**每次开面板**（getActiveAgents，1s 冷却）
   把 live 长名行覆盖成短 id；F5/重连/引擎重启后只剩快照脸 ⇒ 全行 id。

两个持久面实查均持批次标题（`DelegateRegistry.Record.title` 落盘跨重启 +
`SubAgentTask.description`——截图会话两者都有完整批名），恢复脸两边都不查。

## 2. 交付面

| # | 面 | 实现 | 钉子 |
|---|---|---|---|
| 1 | 后端恢复脸第四档 | `WebSocketRoutes.delegateDisplayTitle`（纯函数解析缝：Delegate 主源 DelegateRegistry.Record.title → SubAgentTask.description 回填；SubTask 只走任务记录）+ `activeAgentEntryJson` agentName 链扩四档 + `handleGetActiveAgents` 薄调用 | ActiveAgentsEntrySpec +6 测试 |
| 2 | 前端显示名契约 | `bgAgents.js bgTaskRowHtml` 组头行主名 = rowTitle(DelegateRegistry provider 镜像) → task 首行 → name(≠sid) → sid → id；**sessionId 降为 title tooltip**（`【批名】 · delegate-nebflow-xxxx`）；非组头行逐字不动 | shots harness S1-S3 8 断言 |
| 3 | 密度可读性 | 8+ 批并行每行以批名首行一眼可辨（CSS 既有 ellipsis，零样式改动）；存量记录零迁移回填（下一次快照即恢复批名） | shots S3 前后对照 |
| 4 | spec | `docs/design/subagent-row-display-name-spec.zh-CN.md`（主名数据链 + 四档链 + 触发点覆盖表 + 可断言点） | — |

前后对照证据（`evidence/20261022_subagent-row-display-name__delegate-nebflow-6aae2fd2/shots/`，
MANIFEST 同目录）：

- `s1-shrink-before.png` — **修前复现**：live 长名行 → 开面板退化形快照覆盖 ⇒
  行退化为 `delegate-nebfl…`（与作者截图同款收缩）。
- `s1-shrink-after.png` — **修后**：同触发序列行名仍 = 【delegate 监督链自动…】/
  【canvas 顶部胶囊居…】，title tooltip = 完整批名 · sessionId。
- `s2-restart-restore-before/after.png` — 重启恢复脸（无 live 帧纯快照）：修前存量
  退化形行 = id；修后两种形态均 = 批名首行。
- `s3-density-8-before/after.png` — 八批并行（最劣输入）：修前 8/8 `delegate-nebfl…`
  不可辨；修后 8/8 各自批名首行一眼可辨。

## 3. 验证记录

- **Scala**：`testOnly ActiveAgentsEntrySpec TaskBadgeActiveAgentsEntrySpec` ——
  【结果见文末补充】。
- **前端 harness**：`tests/subagent-row-display-name-shots.mjs` —
  `MODE=before`（HEAD~1 归档树）3 复现断言 ALL PASS；修后 8 契约断言 ALL PASS。
- **邻族**：delegate-ui-twolayer 5/5、turn-activity-stream、taskpanel-blocked-retire、
  tasklist-reconcile-shots、bgagent-orphan-cleanup、subagent-panel-cancel-realtime、
  subagent-panel-open-geometry（单独跑 3/3）、bgagent-project-sessions 等全绿。
- **门禁**：checkJs 干净提交上 257 = 基线零新增 PASS；`npm run build` 179 sources
  orphans 0。

**既有失败确证（与本批无关，全部在干净基线 cbdf4a527 上复现）**：
delegate-panorama T3（canvas-header 点击拦截 flake）、bgagent-dedupe:106
（team ghost-dup）、delegate-chain-stub T7/T8/T8b（链块折叠/S10 链视图，基线同样 3 挂）。
工作树 checkJs 的 input.js TS2554 来自外来脏状态（旧版 chatQueue.js 与 HEAD
input.js 不配套），干净提交上无此问题。

## 4. 过程记录：外部 reset 事件（第二次）

工作树于本批实施期间被**外部 `git reset` 回 HEAD**（HEAD reflog `reset: moving to
HEAD` ×2，非本会话所为），5 个已编辑文件未提交改动被清盘——与 #566 批
（delegate-nebflow-aab5503d）记录的**同款平台行为**。恢复：从会话上下文确定性
重放全部编辑后立即提交锁定；此后随做随交。另：工作树存在外来脏状态
（chatQueue.js + 4 张 chatinput 证据 png，非本批所为），未提交、未触碰。
建议平台层排查 worktree 管理器在何种条件下对工作树执行 reset。

## 5. 纪律要点

- **单症状聚焦**：只修「delegate 一级行显示名收缩」；子行/leftover/弹窗/任务列表/
  管理面板零触碰；`task` 字段不改（避免「标题 · 标题」重复）。
- **零新建存储**：主源/回填源均为既有持久面，只建一个纯函数解析缝。
- **向后兼容**：新参缺省 None ⇒ 旧调用点/旧前端逐字节无感；存量记录零迁移。
- **无重启验证的替代**：本节点运行于活体引擎内（严禁自杀式重启），重启场景以
  「清本地行 + 纯快照恢复」 harness 等价模拟（S2），后端持久面实查佐证跨重启存活。

## 6. sbt 结果补充

`sbt "testOnly nebflow.gateway.ActiveAgentsEntrySpec nebflow.gateway.TaskBadgeActiveAgentsEntrySpec"`
→ **Passed: Total 23, Failed 0, Errors 0**（2026-10-22 20:08 完成）：

- `ActiveAgentsEntrySpec` 18/18：12 既有契约零回归 + **6 个新显示名契约测试**
  （delegateTitle 回填 agentName / 四档顺位 meta > displayName > delegateTitle >
  sessionId / 缺省 None 回归 sessionId / `delegateDisplayTitle` 主源-回填-空白过滤-
  非任务种类三用例）。
- `TaskBadgeActiveAgentsEntrySpec` 5/5：taskbadge 快照面零回归。

编译记录：586 主源 + 802 测试源全量冷编译通过（首个 sbt JVM 在测试编译段因机器
5 实例并行内存压力退出，增量续编完成——非代码问题）。
