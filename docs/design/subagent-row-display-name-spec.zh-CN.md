---
type: design-note
status: done
---

# 子智能体面板行显示名 spec：delegate 行全生命周期 = 批次标题

批：子智能体面板行显示名修复批（2026-10-22）· 修「先长名后收缩成 sessionId」
实施：`WebSocketRoutes.delegateDisplayTitle` + `activeAgentEntryJson`（恢复脸第四档）
+ `WsSessionChatHandlers.handleGetActiveAgents`（解析薄调用）+
`web/js/main/bgAgents.js`（组头行显示名契约 + sessionId 降级 tooltip）。
验证：`src/test/scala/nebflow/gateway/ActiveAgentsEntrySpec.scala`（+6 契约测试）、
`tests/subagent-row-display-name-shots.mjs`（S1 收缩触发点 / S2 重启恢复脸 /
S3 八批密度，MODE=before 复现 + 修后全 PASS，前后对照截图）。

## 0. 症状与根因

**症状**（作者实测截图）：子智能体面板 delegate 行**先显示批次长名，某时刻收缩成
`delegate-nebflow-2c2db017` 这类 sessionId**——8 批并行时每行不可辨，可读性极差；
重启后全部行都是 id。

**根因**（逐跳钉死，三处证据均为实查）：

1. **live 脸正常**：`agentStart`（`AgentSessionExecution` 发射）携带
   `name = agentDef.name`（执行器名，如 "nebflow"）+ `taskDescription =
   state.sessionName`（= Delegate 工具的 description = 批次标题全文）⇒ 新建行 =
   「nebflow · 【批名】…」长名。
2. **恢复脸（重启后从持久快照重建的显示形态，与 live 实时脸相对）缺第四档**：`activeAgentEntryJson` 的 `agentName` 链 =
   `meta.agentName` → `rec.displayName` → `sessionId`。delegate 会话**从不过
   `SessionStore.createSession`**（实查 `sessions/_index.json`：842 条中 0 条
   delegate 前缀 ⇒ meta 恒 None）；`NodeRunner.registerAgent` 对 delegate **不设
   displayName** ⇒ 直落 sessionId 兜底；`task` = meta.name = "" ⇒ 行只剩裸 id。
3. **合并规则①放大**：delegate 的 live `agentId` == 会话 id == 快照 `agentId`，
   前端 `activeAgents` 合并「同 key 快照为准」——**每次开面板**
   （`fetchActiveAgentsOnOpen`，1s 冷却）都会把 live 长名行覆盖成短 id；
   重启后只剩快照脸 ⇒ 全行 id。

两个持久面本就持有批次标题（实查：`DelegateRegistry.Record.title` 与
`SubAgentTask.description` 对截图会话均有完整批名，且 Record 随落盘跨重启存活），
恢复脸两边都不查——本批对齐。

## 1. 显示名契约

**delegate/SubTask 一级行（组头）显示名，全生命周期稳定 = 批次标题/任务描述首行**
（CSS 单行截断 + 省略号；title 属性承载完整名）；**sessionId 降为 tooltip 次要
信息**（`【批名】 · delegate-nebflow-xxxx`）。密度纪律：8+ 批并行时每行以标题
首行一眼可辨——标题截断永远优于 id。

### 1.1 主名数据链（前端 `bgTaskRowHtml`，opts.headLevel 行）

| 顺位 | 源 | 说明 |
|---|---|---|
| 1 | `rowTitle`（组头注册行 title） | `DelegateRegistry.Record.title` 的 provider 镜像（`/api/chains` 行投影同源）——spawn（派发创建会话）落盘、跨重启存活，**存量记录零迁移回填** |
| 2 | `firstLine(info.task)` | live 脸 `taskDescription` = 批次标题全文（取首行） |
| 3 | `info.name`（≠ sid 时） | 恢复脸后端回填的 `agentName`（= 批名） |
| 4 | `sid` → 桶 `id` | 全源缺席的退化兜底（诚实形态，不臆造） |

非组头行（live/store 子行、leftover 非 delegate 行）**逐字走旧形态**，零改动。

### 1.2 后端恢复脸（`activeAgentEntryJson` 第四档）

`agentName` 链扩为四档：

```
meta.agentName → rec.displayName → delegateTitle → sessionId
```

- `delegateTitle` 由纯函数 `WebSocketRoutes.delegateDisplayTitle(kind,
  registryTitle, storeDescription)` 解析（单一解析缝、零类依赖可单测）：
  - `AgentKind.Delegate`：`**DelegateRegistry.Record.title**（主源，spawn 落盘、
    跨重启存活）→ SubAgentTask.description（任务记录回填）`；
  - `AgentKind.SubTask`：只走 `SubAgentTask.description`（DelegateRegistry 只装
    delegate）；
  - 其余 kind：`None`（team/node/ephemeral 既有链逐字不动）。
- `task` 字段**不改**（delegate 行保持 ""）——避免「标题 · 标题」重复渲染。
- 缺省参数 `delegateTitle = None` ⇒ 行为与旧版逐字节一致（向后兼容；
  存量旧前端无感）。

### 1.3 触发点覆盖

| 场景 | 修前 | 修后 |
|---|---|---|
| live 新建（agentStart） | 长名「nebflow · 批名」 | 批名首行（组头契约第 2 源） |
| 开面板（快照回填 ≥1s） | **收缩成 id**（合并①覆盖） | 批名首行（第 1/3 源兜底） |
| F5 / WS 重连 | 恢复成 id | 批名（后端第四档 + 前端第 1 源） |
| 引擎重启（delegate 复活/存续） | 全行 id | 批名（DelegateRegistry 落盘主源） |
| 存量记录（已是 id 的恢复行） | 保持 id | 下次快照即回填批名（零迁移） |

## 2. 可断言点（钉）

1. **S1 收缩触发点**：live 长名行 → 注入退化形快照（agentName=sessionId）→
   行主名**仍含批次标题首行、不含裸 sessionId**；`title` 属性含完整名 + sessionId。
2. **S2 重启恢复脸**：无 live 帧纯快照恢复，两种形态（agentName=批名 / agentName=
   sessionId 存量形）行主名均为批名首行。
3. **S3 密度**：8 批并行（最劣输入：快照全退化形）8/8 行以各自标题首行可辨。
4. **后端契约**（ActiveAgentsEntrySpec）：delegateTitle 回填 agentName；顺位
   meta > displayName > delegateTitle > sessionId；缺省 None 回归 sessionId；
   `delegateDisplayTitle` 三用例（主源/回填、空白过滤、非任务种类不动）。
5. **邻族零回归**：delegate-ui-twolayer 5/5、turn-activity-stream、
   taskpanel-blocked-retire、tasklist-reconcile-shots、bgagent-orphan-cleanup
   全绿；checkJs（前端 JS 类型检查质量闸）257 = 基线（零新增）。

## 相关文档

- [[index]] — 库入口（本篇挂入图谱）。
- [[delegate-supervision-revival-design.zh-CN]] — delegate boot 复活（引擎重启后行名恢复所依赖的持久源 DelegateRegistry 即其复活现场四件之一）。
- 术语：[[glossary]]。

## 3. 纪律记录

- **单症状聚焦**：只修「delegate 一级行显示名收缩」；子行/leftover/弹窗头/
  任务列表行/管理面板零触碰。
- **零新建存储**：主源（DelegateRegistry）与回填源（SubAgentTaskStore）均为既有
  持久面，本批只建「解析缝」一个纯函数。
- **向后兼容**：`activeAgentEntryJson` 新参缺省 None ⇒ 旧调用点/旧前端逐字节
  无感；`bgTaskRowHtml` 新 opts 纯呈现参数，非组头行渲染逐字不变。
- **存量零迁移**：DelegateRegistry 记录落盘自带 title（实查 10-22 全部在册记录），
  下一次快照回填即恢复显示名。
