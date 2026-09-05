# 分层压缩提示词实施报告（规格书落地）

> 2026-09-03。依据：`20260903_compaction-prompt-by-level.md`（~/.nebflow repo commit 056ac58）。
> 施工：worktree `compaction-prompts`（基线 main@2d8dc9f1）独占完成，已合并回本地 main（未 push 远程）。

---

## 1. 规格书章节对照

| 规格书章节 | 落地情况 |
|---|---|
| §2.1 Nebula 台账版草稿 | ✅ 全文照抄落码（替换原 Root 模板主体，val 名 `NebulaCompactReminder`） |
| §2.2 Dispatcher 版草稿 | ✅ 落码（`DispatcherCompactReminder`），含一处**纯新增**偏差，见 §3 声明 |
| §2.3 Node 版草稿 | ✅ 全文照抄落码（`NodeCompactReminder`） |
| §3.1 CompactionProfile 改动 | ✅ enum +2 case；fromDepth +sessionId 前缀分流，常量引用非裸字符串 |
| §3.2 buildCompactReminder | ✅ +sessionId 参数与 profile 分发，Root 分支兜住 Legacy |
| §3.3 调用点 AgentCore:301 | ✅ 一行改动 |
| §4.1 断言清单 | ✅ 逐条落地全过（见 §5） |
| §4.2 编译与回归 | ✅ sbt compile 通过；压缩子集全绿；全量 2081 全绿 |
| §1.5-2 isTeamLeadForCompaction 不动 | ✅ 零改动 |
| §2.4 旧体系过渡期不动 | ✅ ManagerCompactReminder/WorkerCompactReminder 零改动 |
| 硬约束（F0-F3/队列/emergency 不动） | ✅ 未触碰任何相关文件 |

## 2. 改动清单（文件/函数级）

**分支 compaction-prompts，commit `000758f5`（4 文件，+236/−44）：**

1. `src/main/scala/nebflow/core/compact/CompactionProfile.scala`
   - enum +2 case：`Dispatcher` / `ProjectNode`（命名照规格书 §3.2 说明，`ProjectNode` 避免与 nebflow.core.node 语义混淆）
   - `fromDepth(depth, isLead, sessionId: Option[String] = None)`：新参数默认 None（既有调用零改动兼容）；depth==1 时前缀判定**优先于 isLead**；depth 0 恒 Root；depth≥2 恒 Worker
   - 前缀判据引用既有常量：`nebflow.core.project.ProjectActor.DispatcherSessionPrefix`（ProjectActor.scala:151）/ `nebflow.core.project.NodeEngine.SessionPrefix`（NodeEngine.scala:563）——不裸写字符串，先例同 `AgentControlTool.isProjectFlowSession`（AgentControlTool.scala:104-105）
2. `src/main/scala/nebflow/core/compact/CompactService.scala`
   - `buildCompactReminder(depth, isLead, sessionId)`：+参数，profile match 加 `ProjectNode → NodeCompactReminder`、`Dispatcher → DispatcherCompactReminder` 分支，`case _ => NebulaCompactReminder`（Root 分支兜住 Legacy，规格书 §3.2 原样形态）
   - +`NebulaCompactReminder`（替换 RootCompactReminder 模板主体，§2.1 全文）
   - +`DispatcherCompactReminder`（§2.2）、+`NodeCompactReminder`（§2.3）
   - 三版均复用 `CompactPreamble` + `CompactEpilogue`；原 `RootCompactReminder` val 删除
   - `PreCompactionHooks.forProfile`（PreCompactionHook.scala:46-49）**未改**：`case _ => NoOpHook` 自动覆盖新 profile，符合 2026-08-31 无记忆裁定
3. `src/main/scala/nebflow/agent/AgentCore.scala`
   - `startDirectCompaction` 内 :301 一行：`buildCompactReminder(depth, isLead, state.sessionId)`
   - :286 的 hook 选择处 fromDepth 调用**未改**（不带 sessionId）——dispatcher/node 两路均落 NoOpHook，行为不变，规格书仅要求改一行
   - `isTeamLeadForCompaction`（:245-255）零改动
4. `src/test/scala/nebflow/core/compact/CompactionProfileSpec.scala`
   - 按 §4.1 追加 10 个 test（只增不删，既有 7 个断言零改动）

## 3. 三版草稿一致性声明

- **Nebula 版 / Node 版**：与规格书 §2.1 / §2.3 草稿**逐字一致**（正文 + ScalaDoc），无任何改写。
- **Dispatcher 版**：正文与 ScalaDoc 照抄 §2.2 草稿，**唯一偏差为纯新增**（未改动草稿任何既有措辞）：规格书 §2.2 草稿正文缺失 §1.5-3（「新两版必须继承同一声明」）与 §4.1 断言共同要求的无记忆兜底声明，故在首段与 `<summary>` 之间纯新增一段（样式沿用 Manager/Worker 既有 IMPORTANT 声明）：
  > `IMPORTANT: you have NO persistent memory fallback after compaction — this summary is your ONLY in-session record. Preserve every trigger task, topology change, and unfinished gap in full.`
  >
  > ScalaDoc 同步补一行 2026-08-31 单级压缩注释。此为使 §1.5-3 与 §4.1 可同时满足的最小处置。

## 4. 路由表实现（§3.4 对照）

代码位置：`CompactionProfile.scala` `fromDepth`，depth==1 分支内两个前缀 `if/else if` 先于 `else if isLead`（前缀优先于 isLead，堵「Manager 名节点错拿协调者提示词」错位）。

| 会话 | depth | sessionId | 路由 | 提示词 | 验证 |
|---|---|---|---|---|---|
| Nebula 根会话 | 0 | 任意（含 dispatcher-/node- 前缀） | Root | Nebula 版 | ✅ 断言 |
| project-dispatcher | 1 | `dispatcher-*` | Dispatcher | Dispatcher 版 | ✅ 断言（含 isLead=true 时仍 Dispatcher） |
| Project Node | 1 | `node-*` | ProjectNode | Node 版 | ✅ 断言 |
| 旧 team lead | 1 | 常规 id | Manager | Manager 版（不动） | ✅ 回归断言（FLOW MANAGER marker） |
| 旧 dag- / delegate- / subtask- / 无前缀 | 1 | 各自值 | Worker | Worker 版（不动） | ✅ 回归断言 |
| depth≥2 | ≥2 | 任意 | Worker | Worker 版（不动） | ✅ 断言 |

## 5. §4.1 断言逐条结果（testOnly CompactionProfileSpec：Total 17, Passed 17, Failed 0）

| 断言（规格书 §4.1） | 测试名 | 结果 |
|---|---|---|
| depth 0 恒 Root（含带前缀 sessionId） | depth 0 is Root regardless of sessionId/isLead | ✅ |
| dispatcher- 前缀 → Dispatcher（前缀优先于 isLead） | depth 1 + dispatcher- prefix → Dispatcher (even if name would be a legacy lead) | ✅ |
| node- 前缀 → ProjectNode | depth 1 + node- prefix → ProjectNode | ✅ |
| 旧前缀回归 dag-/delegate-/subtask-/None→Manager | legacy prefixes keep old routing | ✅ |
| depth 2+ 恒 Worker | depth 2+ is always Worker regardless of sessionId prefix | ✅ |
| marker 路由 NEBULA/PROJECT DISPATCHER/NODE WORKER/FLOW MANAGER/FLOW WORKER | buildCompactReminder routes by sessionId | ✅ |
| Nebula 段落 Global Mission Board / In-Flight Dispatches / Facts, Decisions and Rulings / Pending / Blocked Items and their Gates | Nebula prompt sections | ✅ |
| Dispatcher 段落 Trigger Task(s) / Topology Changes Made This Session / Unfinished Work / AUTHORITATIVE | Dispatcher prompt sections | ✅ |
| Node 段落 Task Goal (IMMUTABLE / Commits: / Verification: / Blockers and Lessons / Remaining Steps / Result Statement So Far | Node prompt sections | ✅ |
| 无记忆兜底声明（Dispatcher/Node 含 "NO memory fallback" 或 "NO persistent memory"） | all prompts keep no-memory-fallback declaration | ✅ |

## 6. 全量 sbt test（worktree 内前台真实跑）

- **Total 2081, Failed 0, Errors 0, Passed 2081, Ignored 7**
- 耗时 **471 s（7 分 51 秒）**，一次跑完
- 压缩相关子集（testOnly nebflow.core.compact.* + AgentActorCompactionSpec + CompactionQueueStoreSpec）：**127 全绿**（合并前 worktree 与合并后主仓各跑一次）

## 7. 变异验红

- **方式**：临时把 `fromDepth` depth==1 分支的两个前缀判定短路为 `if false then Dispatcher / else if false then ProjectNode`（即禁用 dispatcher-/node- 前缀分发），跑 CompactionProfileSpec。
- **红**：`Failed: Total 17, Failed 5, Errors 0, Passed 12`——5 个新用例全红：
  - `X depth 1 + dispatcher- prefix → Dispatcher (even if name would be a legacy lead)`（ComparisonFail）
  - `X depth 1 + node- prefix → ProjectNode`（ComparisonFail）
  - `X buildCompactReminder routes by sessionId`（FailException）
  - `X Dispatcher prompt sections`（missing section: Trigger Task(s)）
  - `X Node prompt sections`（missing section: Task Goal (IMMUTABLE）
- **恢复**：还原两行后 `git diff --stat` 为空（工作区与 commit 完全一致），复跑 `Passed: Total 17, Failed 0, Errors 0, Passed 17` 全绿。
- **结论**：新增路由用例真实钉住前缀分发行为，非恒绿断言。

## 8. 分支 / merge commit

- 分支 compaction-prompts：`000758f5`（唯一实现 commit；基线 main@2d8dc9f1，开工 merge main 时 main 无新提交）
- merge commit（本地 main，--no-ff）：**`bc95dec1`**
- 未 push、未动 origin。

## 9. 合并流程与合并后复跑

- 合并前主仓 `git status --porcelain` 为空（无他人未提交改动，无文件域冲突），未触发等待重查。
- `git merge compaction-prompts --no-ff` → ort 策略零冲突，4 文件 +236/−44。
- 合并后主仓复跑子集（CompactionProfileSpec + nebflow.core.compact.* + AgentActorCompactionSpec + CompactionQueueStoreSpec）：**Total 127, Failed 0, Errors 0** 全绿（73s）。按规格未跑第二次全量。
- 仓库 QC 钩子自动审查通过（verdict：非阻塞，4 条小改进建议，见 §12）。

## 10. 清理确认（全部完成并验证）

- `git worktree remove .nebflow/worktrees/compaction-prompts` ✅（`git worktree list` 中已无此项）
- `rm .nebflow/compaction-prompts` 软链 ✅（ls 确认 No such file or directory）
- `git branch -d compaction-prompts` ✅（已删除，曾为 000758f5；历史经 --no-ff merge commit bc95dec1 保全）

## 11. 生效说明

改动已进本地 main 并通过验证，但 **Scala 代码需重建 + 重启 Nebflow 宿主进程后才运行时生效**（重启后下一次压缩即用新提示词）。**本次严禁重启——未执行任何重启动作**，重启由 Nebula 统一安排。

## 12. 遗留项

1. **`<files>` 段无消费者**（规格书 §1.5-1/§5）：CompactEpilogue 要求模型输出 `<files>` 列表，但 FullCompact.parseResponse 只提取 `<summary>`，输出被静默丢弃。按任务要求本批不修，建议后续二选一（解析并入 preservedFilePaths 或删除该提示词要求）。
2. QC 审查非阻塞建议（后续可选）：① buildCompactReminder 的 `case _` 通配使新增 enum case 时编译器不再强制 exhaustivity（可改 `case Root | Legacy =>`）；② AgentCore:286/301 两次 fromDepth 对同一会话可得不同 profile（今日无行为差异，因 forProfile 只认 Root）；③ depth-1 新架构会话仍会做一次被前缀路由丢弃的 isTeamLeadForCompaction 查询（含 EntityLoader.listTeams 文件 IO，压缩低频影响可忽略）；④ Legacy case 无测试钉住（既有现状，非本批引入）。
