# 阶段 3 · Mail 地址面硬删 + AgentControl team scope + 登记表本体 + flows.json 归档 — 证据

- 批次：`team-feature-deletion-20261104`（阶段 1 停生产能力 → 阶段 2 摘挂载入口 → **阶段 3 = 本步** → 阶段 4 团队数据归档）
- 作者裁定：**2026-11-04**「团队功能整体删掉」+ 开工补充四项（Mail 地址面三形态怎么退役 / `AgentControl` team scope 删还是降级 / `FlowTreeActor` 双职责先拆后删 / `MountedFlowStore` + 24 个 `flows.json` 一并归档清掉）
- 工作区：`/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-f97716ed`，分支 `delegate/delegate-nebflow-f97716ed`
- 基线：`main = 2af513702`（分支起点），分支领先 4 个本阶段提交（见 §1）

本目录：
| 文件 | 内容 |
|---|---|
| `README.md`（本件） | 结论与读数摘要 |
| `grep-zero.txt` | 收尾 grep 归零读数（四条硬约束名字 + `MountedFlowStore` + `flows.json` 计数） |
| `archive-flows.log` | 24 个 `flows.json` 归档流水（前后计数、MANIFEST 逐行复核、冻结权限、逐件 sha256 抽样） |

全量命令原文见 `../data-archive/commands.log`（阶段 4）与提交信息本身；本批未另建 commands.log。

---

## 1. 四个提交

| # | commit | 内容 | compile | testOnly |
|---|---|---|---|---|
| 1 | `82d42614b` | 拆文件：`TeamSessionRegistry` 整段搬出 `FlowTreeActor.scala`（**纯搬移零语义**，242 行全等、宿主文件 703→461 行） | `[success] 6 s` | `TeamSessionRegistrySpec` 14/14（该 spec 于提交 2 删除） |
| 2 | `a0d575cf9` | 删团队那半：`FlowTreeActor` / `FlowTreeRegistry` / `FlowTreeTypes` + 树 spec 删除；9 处死 import 摘除 | `[success] 27 s`；`Test/compile` 19 s | `DeleteSessionCleanupSpec` + `DeviceFaceHardeningRoutesSpec` 12/12 |
| 3 | `cf0c5336b` | Mail 地址面硬删 + `AgentControl` team scope 整段删 + 登记表本体 `git rm` + teams REST 7 条 + 6 个前端孤儿 | `[success] 34 s`；`Test/compile` exit 0 | 23 spec 合跑 185 项：**Passed 184 / Failed 1 / Ignored 2**（唯一红项 = routes spec 的 collateral 名单，按裁定更新后 7/7） |
| 4 | 本提交 | `MountedFlowStore.scala` 删除 + 24 个 `flows.json` 归档（`cp -a` → sha256 全等 → `mv` → `chmod a-w`） | `[success] 1 s`（增量，零依赖） | 无代码读者 ⇒ 无 spec 受影响 |

---

## 2. 四项裁定逐条落地

### ① Mail 地址面三形态硬删（**不留 `MAIL_TEAM_REMOVED` 之类特判**）

| 删除项 | 原职责 |
|---|---|
| `TeamOnlyRoutingError` | 「团队外发信方只能发团队名」判据（现无对象） |
| `deliverToShortName` / `deliverShortNameUnscoped` | 短名 / `team/agent` 解析腿 |
| `deliverToSession` | 团队内会话直投 |
| `CrossTeamMailMarker` / `checkCrossTeamExplicitRoute` / `checkTeamScope` | 跨团队显式路由标记 + 团队作用域闸 |
| `canMailRoot` | 团队成员给 root 发信的两条判据（`isManager` / `team.lead` 名匹配） |
| `mailNotFound` | 「Use a team name or agent short name」文案 + 团队 lead 指路 |

角色面收敛：`SenderRole.{Root, Dispatcher, Delegate, Standalone}`（原 `Teamish` 删除）。团队名此后落**通用**「不认识的地址」错误（`unresolvableError` / `unresolvableAddressInQueueError`），**无专门错误码**。

保留（作者未裁定 / 边界登记「禁碰」）：`MailQueueStore` / `MailQueryStore` / `FlowMailStore`（遗留队列层与信箱存储，spec 直调面在库）、`EntityLoader` 的 team 实体函数、`AgentKind.Team` 枚举值、`activateAgent` 冷激活腿（零生产调用方，仅遗留队列层 spec 直调）。

一处必要的**行为修正**（不是回落成特判）：`call()` 的裸名落点改为「先判项目名 ⇒ project 拆除拒绝；否则通用错误」——`ProjectRuntimeRegistry` 命中即项目，与 `layeredRoute` 的 root 裸名腿同一判据，保证 `project:` 面的拆除拒绝**对所有角色一致**（`NodeAcceptanceSpec ⑩` 钉住）。

### ② `AgentControl` team scope 整段删除（只留 session / bgTask）

删除：`managerAnchors`、`isManagerSession`、`auditTeamAction` + `auditLogger`、Manager 的 confirm+reason 强确认门、Team 成员 restart 腿（`teamRestartSuccessText` / `teamRestartDeadText`）、`teamManageable` / `teamRestartable` 放行标注。
新口径：`AgentKind.Team` 目标由 `kindRejection` **fail-closed 显式拒绝**（"Team targets are not manageable … retired on 2026-11-04"）；`scopeFilterFor` 非 root 桶给**空范围**（失败方向是收窄）；`doStatus` 非 root 桶显式拒绝，Team 目标的 status 卡仍可读但 `manageable: no (read-only kind)`。

### ③ `FlowTreeActor` 双职责：先拆再删（两个提交）

提交 1 程序化比对：`TeamSessionRegistry` object 242 行**逐行全等**，`FlowTreeActor.scala` 其余部分逐字节不变，只多 5 行指针注 → **零行为变化**（compile 绿 + 搬移对象自身 spec 14/14）。
提交 2 才删团队那半（树类型三件 + 树 spec + 9 处死 import + `WebSocketRoutes.removeRootAgent` 的 `FlowTreeRegistry.unregister`）。

### ④ `flows.json` 归档清掉（与阶段 4 同一手法，**零 `rm`**）

- 24 个 `~/.stand/sessions/*/flows.json`：`cp -a` → 逐件 sha256 比对（全等才继续）→ `mv` → `chmod a-w`
- 归档相对路径保留层级 `sessions/<sid>/flows.json`，落阶段 4 同一归档根
- `MANIFEST.tsv` 追加 24 行（总 137 行 = 1 表头 + 136 数据行），逐行复核 **136/136 OK**
- `RESTORE.md` 追加 §12（表 + 死因 + 还原脚本 + 未动清单）；顺带修正 stage-4 遗留的 `RESTORE.md` 自身 manifest 行（字节/sha/mtime 刷新）
- 源目录实测：归档前 24 → 归档后 **0**；归档侧 **24**
- `MountedFlowStore.scala` `git rm`；`git grep "MountedFlowStore" -- src` = **0**
- 冻结：归档根 `dr-xr-xr-x`，文件 `-r--r--r--`（`chmod a-w` 后的常态；写入前按 `RESTORE.md §10` 的唯一例外 `chmod -R u+w` 破窗，写完重新冻结）

---

## 3. 收尾硬约束读数

| 约束 | 读数 |
|---|---|
| `src/main` 内 `FlowTreeActor` / `TeamSessionRegistry` / `TeamRegistry` / `MountTeam` 引用归零 | `git grep -c` 四条全 **exit=1（0 命中）**，见 `grep-zero.txt` |
| 编译真跑（`-mem 2600`） | commit 1/2/3/4 前各一次 `compile` 全绿（6 s / 27 s / 34 s / 1 s），`Test/compile` 全绿 |
| `testOnly` 实数 | 受影响 23 spec：Total **185** · Passed **184** · Failed **1** · Ignored **2**；修完唯一红项后 `DeviceFaceHardeningRoutesSpec` **7/7**、`NodeAcceptanceSpec` **30/30**、`MailAttachSpec` 全绿 |
| 归档 sha256 全等 | 136/136 行复核 OK（`archive-flows.log`） |
| 不碰 `Tests/` 视觉基线 | 零改动（`git status` 无 `Tests/` 路径） |

### 删测台账（每条都写进 spec 内的「删因」墓碑注，零「跑不过」式删除）

| spec | 删除/改写内容 | 删因 |
|---|---|---|
| `TeamSessionRegistrySpec` | 整文件 | 登记表本体已删 |
| `MailToolCheckTeamScopeSpec` | 整文件 | `checkTeamScope` 已删 |
| `FreezeGateSpec` B12 | 1 例 | 团队 busy（`markBusy`/`isBusy`）已退役 |
| `ReminderWiringSpec` ③ | 1 例 | 团队会话归属已退役（`team:<name>` 任务注入腿不可达） |
| `ActiveAgentsFilterSpec` | 4 例 + `withBusy` | 团队 busy 门已退役 |
| `MailToolRootSenderSpec` | 6 例（团队面）+ 夹具 | 团队地址面硬删 |
| `MailDeliveryRetireSpec` | 1 例（team 腿端到端）+ fixture | 团队短名地址面硬删（queue 墓碑面仍由 §1–§4 覆盖） |
| `MailActivateLifecycleSpec` | Block 0 注册链 1 例；death watch 例改写 | 团队注册链退役 / 登记表 actorMap 退役（agentRegistry 卫生保留并继续钉） |
| `AgentControlToolSpec` | Block 1（4）+ Block 2（2）+ F3(b)；2 例改写 | team scope 整段删除 |
| `AgentControlE2ESpec` | AC-T1/T2/T3 | team scope 整段删除 |
| `NodeAcceptanceSpec` | Task③ 改写；⑩ 断言改写 | 团队名路由硬删 / 裸名落点收敛为通用错误 |
| `DeleteSessionCleanupSpec` | 2 例 | 登记表 sessionMap/actorMap 已删 |
| `DeviceFaceHardeningRoutesSpec` | `stillServed` 名单移除 3 条 teams 路由 | 该 3 条路由按裁定删除 |
| `MailDedupWiring` / `MailDedupReplayWindow` / `MailAttach` / `MailIdleGateWiring` / `ColdQueueActivation` / `ChainViewWsEntry` / `TodoUnifyFrames` / `WsBrowseDeviceLeg` / `ShellSessionRestart` | 只摘 fixture / 参数 / 注释（12 处 `registerSession`-系、3 处 `stopTeamSessionActorsImpl = null`、1 处注释） | 夹具源已删；用例语义不变 |

---

## 4. 前端（随本阶段）

- `flowViewers.js` 395 → 60 行：删 `openMailbox` / `refreshMailboxPending`（+ `mailboxCtx`/`pendingRowHtml`/`loadPendingSection`）/ `openRules` / `openDefinition`（+ `populateAgentModel`/`renderAgentModelSection`）/ `openNodeResultViewer` / `mailBodyHtml`；`closeViewer` 降为**私有**（仍被 `openViewerShell` 使用）；`openViewerShell` 保留（`agentFileViewer.js:5` 唯一外部 import）。
- 7 条 teams REST 路由删除（`PresenceRoutes.scala`，含 `GET/POST /api/team/rules/:name` 的墓碑注 + `buildMountedTeamsJson` 墓碑更新）。

**未动（列为待作者裁定项）**：`ws.js:1586-1640` 的 `nodeSessionId` `team-` 前缀分支（生产者 `MailTool.teamWsSend` 仍在，但 `activateAgent` 已零生产调用方）；`flowHelpers.js` 的 `setMailPending` / `teamPendingCount`（本轮成为零调用方，属他批死码，未越界清理）。

---

## 5. 未合并状态

本阶段四个提交在 `delegate/delegate-nebflow-f97716ed` 上。`main` 在本步执行期间**多次前进**（`2af513702` → … → 请见收尾报告的实测 tip），合并动作由收尾报告如实申报（`git merge-base --is-ancestor`）。
