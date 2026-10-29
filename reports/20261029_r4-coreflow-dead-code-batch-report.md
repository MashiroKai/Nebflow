# R4 legacy 死代码带清理批报告（core/flow 卫生批，映射表 P4）——2026-10-29

批：R4 core/flow 死代码清理（依据 `docs/design/20261028_213056_delegate-dual-mode-redesign__delegate-nebflow-192d10a5.zh-CN.md` §1 P4 行 + [[20261028_171800_审计报告二五机制__delegate-nebflow-a13975e4]] §3.4-①；作者令 2026-10-29 18:12「问题映射表派批解决」）。
分支：`delegate/delegate-nebflow-ff6dbec1`（基于 local main 982625c1b，无漂移）。纪律：独立 commit，不 push，在产零触碰。
**注意**：2026-09-06 工具面裁撤批曾有红线「FlowDagRunner 引擎零触碰」——本批按作者 2026-10-29 之令解除该红线（P4 即引擎残留清理批），registry.scala 注释已补记沿革。

## 一、删除清单（每项「为何安全」）

| 文件 | 行数 | 为何安全 |
|---|---|---|
| `core/flow/FlowDagRunner.scala` | 94（整文件） | 唯一 spawn 点 FlowTriggerTool 已于 2026-09-06 退役；全仓（src+build+resources）零生产调用点，仅 2 处陈旧注释提及（已同步改写）；无专属 spec |
| `core/flow/EphemeralAgentRunner.scala` | 151（整文件） | 全仓零生产 spawn 点；其死亡监视（death-watch）模式先例已由 NodeStarter bridge 重实现（2026-09-04 假尸修复，「先例同款」）；生产引用仅存在于 4 处陈旧注释（已同步改写）；仅 EphemeralBridgeWatchSpec 引用（连删） |
| `test/.../EphemeralBridgeWatchSpec.scala` | 200（整文件） | 仅 spec 自嗨——被测主体 EphemeralAgentRunner 已删，测试对象不复存在 |
| `core/flow/FlowTreeActor.scala` | −149（852→703） | 死子树外科切除：`UnmountBranch`/`WatchAgent`/`ResumeAgent`/`Shutdown` 四命令全仓零发送者；`handleUnmount` 仅由 UnmountBranch 调；`Terminated` 监视分支依赖 `WatchAgent` 注册的 watch（从未注册 ⇒ 永不触发）；`restoreInterruptedTurns`+`resumeInterruptedAgent` 读 TurnStateStore（无人写 ⇒ 假链路，运行时恒 None）。**存活职责全部保留**：MountTeam（Load 工具）、ReloadDefinition（POST /team/rules/:name）、CancelPipeline（WS cancelFlow）、restoreTeams 启动恢复（真链路：flows.json 由 mount/restore 写入）、文件监视器 |
| `core/flow/TurnStateStore.scala` | −66（96→30） | `save` 零生产调用点（审计二 §3.4-① 独立复核成立）；`load` 仅被上述死子树消费；`TurnState` 编解码随之无用。保留 `clear`（deleteSession 卫生清理，SessionStore 调用、DeleteSessionCleanupSpec 以裸文件断言——该 spec 原样通过） |
| `core/flow/FlowTreeTypes.scala` | −37（56→19） | `MountResult` 唯一生产点（MountTeam replyTo）恒为 None（唯一发送者 LoadTool 传 None）⇒ 回执协议不可达；四死命令定义随之删除；存留三命令均有在产发送者 |
| `core/flow/MountedFlowStore.scala` | −3 | 仅 `delete` 方法零调用者（session 删除直接删 session 文件，从不经此 API）；save/load 为真恢复链路，保留 |

外围同步（非删除）：`LoadTool.scala` MountTeam 单参化调用点；`DeleteSessionCleanupSpec.scala` 移除已失效的 TurnStateStore import（spec 逻辑零改动）；陈旧注释改写 4 处（`registry.scala` 补 R4 沿革、`WebSocketRoutes`/`AgentState` spawn 点清单去 EphemeralAgentRunner、`AgentRuntimePort` 工厂镜像构造点清单更新、`AgentState.turnStartMessageCount` 注释去 TurnStateStore 假链路表述）。

## 二、判定保留（live 面，编译在用）

`TeamSessionRegistry`（team 会话注册表，MailTool/AgentFinishTurn/Ws*/Presence 全在用）、`FlowTreeRegistry`（LoadTool/WS 路由/Presence awaitRestore）、`RunningFlowRegistry`（FlowDagExecutor/AgentFinishTurn/MailIdleGate）、`MailQueueStore`/`MailDeliveryDedup`/`MailIdleGate`（邮件激活链，AgentIdle/AgentFinishTurn/MailTool）、`FlowMailStore`（MailTool.append 在写，PresenceRoutes 读）、`FlowSemantics`（NodeStatus/VerdictFamily ← core/entity FlowDagExecutor 在用）、`MountedFlowStore.save/load`、9/10 个 core/flow spec 全留。

## 三、验证证据

- `sbt Compile/compile` 绿（665 源文件）；`Test/compile` 绿（912 源文件，`-Xfatal-warnings` 开启下零告警失败）。
- 受影响 spec 族回归：**151 passed / 0 failed / 0 errors**（2 ignored 为 DeleteSessionCascadeSpec 既有 @Ignore 基线件，与本批无关）。覆盖：core/flow 全部 9 个保留 spec、DeleteSessionCleanupSpec、entity 引擎族（Parallel/Barrier/DynamicFanout/StrictVerdict/ExecutorCancel/NodeSupervision）、MailActivateLifecycle、ColdQueueActivation、NodeSessionDeathFinalize、DeleteSessionCascade。
- `git diff --stat`：**13 files changed, +55 / −740（净 −685 行）**。

## 四、待观察（调用图存疑或越带，本批不动）

1. **`FlowDagExecutor`（core/entity）**：FlowDagRunner 删除后，其在产调用点归零（仅 spec 直调）——下批（entity 带）候选，本批不动（NodeStatus/VerdictFamily/RunningFlowRegistry 仍被其编译引用，故 FlowSemantics 等文件保留）。
2. **`AgentCommand.ResumeTurn`（actor/AgentState.scala:532）**：本批后全仓零发送者且无 handler（唯一发送者是被删的 resumeInterruptedAgent）；属 actor/ 带，越界不动。
3. **`TeamSessionRegistry.registerActor` 4 参重载**：生产调用点仅被删的恢复路径；仍被 MailActivateLifecycleSpec/ColdQueueActivationSpec 使用 ⇒ 保留（宁可少删）。
4. **`treeBranchMounted`/`treeBranchUpdated` WS 事件**：活路径仍在发，但前端 js 无监听者（空放）；行为面不动。
5. **FlowTreeActor 会话删除不停机**：removeRootAgent 只注销 registry，从未有 Shutdown 发送（删除的 handler 生前也无发送者）——既有观察，越界不动。

## 五、其他事实

- `git fetch` 失败（离线，github 不可达）；本地 main = 分支基点 982625c1b，无本地漂移；并行批冲突由合并序裁决。
- boot 恢复双链路甄别：**真链路**（MountedFlowStore flows.json：mount 写、启动 restoreTeams 读 + 全量 auto-mount）保留；**假链路**（TurnStateStore turn-state.json：无人写、恢复恒空转）整体切除。无 boot 恢复专项 spec 需要改真——TurnState 面唯一 spec（DeleteSessionCleanupSpec）测的是文件卫生清理，原样通过。
