# 修复批 #566 — 任务面板陈旧终态 + delegate blocked 语义退役（两域）

日期：2026-10-19 · 分支：`delegate/delegate-nebflow-aab5503d` · 基线：`80fbde315`
证据目录：`evidence/20261019_185500_taskpanel-blocked-retire__delegate-nebflow-aab5503d/`

## 0. ⚠️ 恢复事件（本批验证前置）

工作树在 2026-10-19 16:22:49 与 16:32:47 被**外部 reset --hard 回 HEAD**（HEAD reflog：
两条 `reset: moving to HEAD`；非本会话所为），本批 ~25 文件的未提交改动被清盘，仅
untracked 的 `tests/taskpanel-blocked-retire.spec.mjs` 幸存。

恢复方式：从会话转录
`~/.nebflow/sessions/delegate-nebflow-aab5503d/compaction/20261019-164013-before.json`
按序重放全部成功的 Write/Edit 文件操作：

- 转录内 90 条文件操作，其中 89 条原始成功（`isError=false`）全部重放落位；
- `seq247`（delegateChainStub.js 头注）原始即失败（isError=true），被 `seq251` 的成功
  重试覆盖语义，重放中如实跳过；
- `seq115`（DelegateJudge.scala 文件头 scaladoc）重放时锚缩进不匹配（转录锚 3 空格、
  盘面 1 空格——原始应用后经 scalafmt 归一），按归一化缩进手工等价应用（纯注释）；
- 重放后 `sbt scalafmtAll`（复现转录 seq455 的最终格式化步），随后**全部测试重跑**
  （见 §2）——重跑即恢复后状态的直接验证。

## 1. 修复面（两域）

### A 域 — 任务完成后行状态不更新/不消失（#566 bug A）

根因双点：
1. 前端 `rowFromRegistry`（`src/main/resources/web/js/delegateChainStub.js:185`）旧推导只读
   `reportStatus`/`live`，引擎终态从不到达行 → 终态委托回落 running（「进行中」陈旧行）。
2. 后端 `DelegateTerminal.onTerminalCleanup` 的 blocked 判词归档豁免使终态记录永留现役窗。

修复：
- `src/main/scala/nebflow/core/project/ChainsReadFace.scala:64-67`：`rowsJson` 增第三参
  `taskStatuses: Map[String,String]`（:45 注释），行投影新键 `taskStatus`（:154-155）——
  SubAgentTaskStore 引擎终态权威直供；**#506 现役窗闸原样未动**（windowed 闸照旧）。
- `src/main/scala/nebflow/agent/SubAgentTaskStore.scala:90`：新增 `findAllTasks()`
  （单遍扫目录）。
- `src/main/scala/nebflow/gateway/PresenceRoutes.scala:460`：`/api/chains` 臂接
  `findAllTasks` → statuses（fail-soft 空表）。
- `src/main/resources/web/js/delegateChainStub.js:170-189`：`rowFromRegistry` ①
  `taskStatus` 引擎终态直映（终态权威）② 退役 `reportStatus='blocked'` 容错折 completed；
  `verdictToStatus`（:339）blocked→completed、增 failed/cancelled 真值映射。
- `src/main/resources/web/js/taskList.js:125`：`ACTIVE_ROW_STATUSES` 去 `'blocked'`——
  终态行（completed/failed/cancelled）按既有归档规则消失（行退场 = bug A 验收面）；
  `:346` 现役窗过滤链原样。
- `src/main/scala/nebflow/core/delegate/DelegateTerminal.scala`：**blocked 归档豁免整条
  删除**（一切终态照归 = A 根因钉死）；引擎清理行写真值 `verdict=failed/cancelled`
  `source="engine"`（:717-718）。

### B 域 — delegate 面退役 blocked（作者令：「delegate 是不应该有 blocked 型状态的，
直接上报就行了，blocked 是给 agentflow 用的」）

控制流零变化：Blocked 与 Completed 同为 Release；折 `CompletedNote` 后全部路径行为等价
（验收闸对 completed 形全过；注记形**不触发** readOnlySeatFlip/auto-merge——判官注记
不被吞，随交付尾注 `[delegate-judge] note:` 走）。

- `src/main/scala/nebflow/core/delegate/DelegateJudge.scala:49-67`：Verdict 枚举改
  `Completed | CompletedNote(note) | ReadyToMerge`＋`isCompletedShaped`；VerdictWire 保留
  `Blocked` 常量仅解析用（:171 起，engine 永不写出）；新增 `engineTerminalPayload`
  （:229）与 Failed/Cancelled 常量。
- `src/main/scala/nebflow/core/delegate/DelegateTerminal.scala`：预检/漂移闸
  Penalty/自申报 blocked/验收拒绝/holdForMerge 缺锚 全折 CompletedNote
  （:163/:215/:283/:331/:380/:717）。
- `src/main/scala/nebflow/llm/decision/DelegateJudgeMachine.scala`：verdictFromChoice/
  parseVerdict 的 "blocked" 答案 → CompletedNote。
- `src/main/scala/nebflow/core/delegate/ExternalJudgeHook.scala`：镜像枚举
  Blocked→Failed；CompletedNote→Completed。
- `src/main/scala/nebflow/core/executor/ExternalSessionRunner.scala`：Outcome.Blocked→
  Failed；finalizeTask "failed"；notify "failed —"（外部腿「做不成」= 真失败，无独立
  blocked）。
- `src/main/scala/nebflow/core/tools/DelegateReportTool.scala:18-49`：值域 `finish | merge`；
  blocked 拒绝（`RetiredBlockedValue`，:42）并给去处。
- `src/main/scala/nebflow/core/tools/DelegateTool.scala`：BriefReportLine 两态。
- `src/main/scala/nebflow/core/entity/BuiltinAgents.scala`：delegateEntry 提示词两态＋
  "NO separate blocked category"。
- `src/main/scala/nebflow/core/delegate/MergeQueue.scala`：合并 brief 改 report finish。
- 前端退役：`taskList.js:125`（ACTIVE 集去 blocked）+ `bgAgentPopup.js:123`
  （STRIP_VERDICT_KEY 去 blocked、加 failed/cancelled）+ `taskList.css:214`
  （死规则 `.task-node-dot-blocked` 删除并留注释）。
- **agentflow 面零误伤**：`task.nodeBlocked` locale 键保留
  （`locales/zh-CN.js:1478` / `locales/en.js:1533`）；flow 成员行映射保留
  （`delegateChainView.js:85`、`main/bgAgents.js:237`、`taskList.js:156`）——blocked
  只归 agentflow 节点。

## 2. 测试读数（恢复后全量重跑，2026-10-19 18:44–18:50）

### Scala（sbt Test/testOnly，18 specs）

**Passed: Total 110, Failed 0, Errors 0** — batch1＋batch2 全绿：

- batch1（本批触点 spec）：DelegateTerminalSpec（①④⑥⑭）、
  DelegateTerminalPrecheckSpec（①②）、DelegateAutoArchiveSpec（②豁免退役反钉＋③引擎
  真值行）、MergeAcceptanceSpec（⑤）、DelegateJudgeMachineSpec（重写）、
  ChainsReadFaceSpec（＋C1b taskStatus 投影钉）、DelegateReportToolSpec（＋退役类别
  拒绝）、PromptFaceSplitSpec
- batch2（邻接回归）：ExternalLegPrepareSpec、ExternalOutputStoreSpec、
  DelegateJudgeObserveSpec、DelegateJudgeInputHardeningSpec、DelegateUiDataPlaneSpec、
  ChainStoreSpec、MergeQueueSpec、DelegateAutoMergeSpec、PostMergeCleanupSpec、
  MergeQueueBootSweepSpec
- batch3（delegate 基建回归）：**Passed: Total 43, Failed 0, Ignored 7**
  （ignored = 存量 live-probe 跳过）——StuckDelegateReleaseSpec、
  NestedDelegateNotifySpec、DelegateDeleteCascade 族（*CascadeSpec）、
  AgentControlE2ESpec、DelegateWorkspaceWorktreeLegSpec、
  DeviceDelegateSpawnTrustSpec、DelegateRegistrySpec ×3、
  NodeSeedOutputContractSpec
- **#506 回归 = ChainsReadFaceSpec C4/C4b 绿**（现役窗闸零回退）。
- 编译：main 570 源全量 + test 增量全绿。

### JS（playwright，主仓 node_modules 驱动）

- `tests/taskpanel-blocked-retire.spec.mjs`：**6/6 passed**（A1 终态行消失＋A3
  ready-to-merge 在窗/archivedAt 退场＋A2 行翻状态面＋B1 退役申报行退场＋B2/B2b
  verdict=blocked 注记零徽章 zh-CN+en＋B3 agentflow flow 成员 blocked 保留对照）
- `tests/delegate-chain-stub.spec.mjs` ＋ `tests/tasklist-chain-blocks.spec.mjs`：
  **22/22 passed**（T2 零 blocked 断言、T4 判词映射钉、T4b taskStatus 权威＋终态行消失、
  row32→running）
- `tests/delegate-ui-twolayer.spec.mjs`：5/5 passed
- `tests/flowmap-task-slim-migration.spec.mjs`：6/6 passed

## 3. 预存红登记（非本批引入）

| 项 | 读数 | 定性 |
|---|---|---|
| `sbt scalafix --check` | 基线即红（重置前实测 63 文件违规，SoulMigration/AtomicJson 等大量未触碰文件）；本批裁定见 §4 | 预存基线 |
| `tests/mergequeue-pos.spec.mjs` | 「fixture 载荷」族 6 例红（3 viewport × light/dark） | 环境依赖（引擎 payload dump 缺席），预存 |
| `tests/queuepos-detail.spec.mjs` | 载入即抛：PAYLOAD_PATH 不存在，需先 `sbt "testOnly nebflow.core.project.MergeQueuePositionSpec"` | 环境依赖，预存 |

## 4. scalafix 裁定

见本目录 `scalafix-verdict.md`（跑批读数与本批 25 文件逐一比对结论）。

## 5. 提交面

pathspec 提交：25 代码/测试文件 + 本证据目录 + spec 文件。**不含**（非本批）：
`chat.js / input.js / main.js / state.js / ws.js / AgentFrozen.scala / AgentIdle.scala /
AgentProcessing.scala / AgentSession.scala`（另一批次现役 WIP，原样留在工作树）。
scalafmtAll 顺带格式化的 160 个无关文件已 `git checkout --` 还原（转录 seq455 同款
噪声面，与重置前计划一致）。
