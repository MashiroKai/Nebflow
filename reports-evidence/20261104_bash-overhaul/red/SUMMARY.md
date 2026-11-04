# bash overhaul · RED 相证据（作者令 2026-11-04）

作者令 2026-11-04（逐字）：「先安排重启，然后组织优化 bash，用业界成熟做法完整实现，一步到位。」

本目录 = **红相机械证据**：只写测试、**零 src/main 改动**，把「改前必红」逐条钉死。
事实源（本相不重做考古）：

- `~/.stand/projects/stand/.stand/docs/20261104_110500_our-bash-tool-defects.md`（缺陷 D1–D11 + file:line）
- `~/.stand/projects/stand/.stand/docs/20261104_113008_external-agent-bash-design-survey.md`（业界矩阵 + 防循环解法）

工作树：`.nebflow/worktrees/delegate-nebflow-485bb4f4`（分支 `delegate/delegate-nebflow-485bb4f4`）。
环境：Scala 3.5.2 / sbt 1.10.10 / macOS（darwin），**冷编译**（工作树无 `target/`）。

---

## 0. 复现命令

```bash
# 第一批（行为红）——5 个 suite，能编译、跑起来即红
cd ".nebflow/worktrees/delegate-nebflow-485bb4f4"
sbt -batch "testOnly nebflow.core.processor.LoopGuardSpec \
  nebflow.core.tools.BashOverhaulGateSpec \
  nebflow.core.tools.BashOverhaulTruncationSpec \
  nebflow.core.tools.BashOverhaulNotifySpec \
  nebflow.core.tools.DeviceBashAutoBackgroundSpec"

# 第二批（编译红）——加入 T1/T6 + T2 编译腿后，Test/compile 整体失败 = 预期红
sbt -batch Test/compile
```

原始日志（ANSI 已剥离）：

| 文件 | 内容 |
|---|---|
| `00-batch1-full.log` | 第一批完整 sbt 输出（`Failed: Total 36, Failed 7, Errors 0, Passed 29`） |
| `08-compile-red-full.log` | 第二批 `Test/compile` 完整输出（`19 errors found`） |
| `01…07-*` | 第一批逐 T 抽取段 |
| `09…11-*` | 第二批逐 T 编译错误段 |

**耗时**：冷编译（737 main sources + 1029 test sources）首轮 `Total time: 125 s`；第二批完整跑测
`Total time: 341 s`（含单文件改动触发的 1029 test sources 重编译 + 36 个用例执行，本机另有 session 抢 CPU）。

---

## 1. 逐 T 一览

| T | 断言文本（要点） | 红证据类型 | spec 文件 | 红日志 | 关键行 |
|---|---|---|---|---|---|
| **R1** | 同 turn 内 20 次同参 Bash **查询**事件（`{"background_job_id":"abc","command":""}`，isError=false）→ `evaluate` 裁决不得是 Terminate/Freeze | behavioral | `src/test/scala/nebflow/core/processor/LoopGuardSpec.scala`（既有文件，**只追加**） | `01-R1-loopguard-query-rcall-red.log` | `must NOT Terminate (got: loop-detected: tool 'Bash' has now been called 20 times in a row with identical arguments …)` |
| **R2** | 10 次同参 Bash **工作**调用（`{"command":"ls"}`）→ 仍 Terminate（既有口径不得松动） | behavioral（**对照臂，当前绿**） | 同上 | `00-batch1-full.log` 第 38 行 `+ R2 …` | `+ R2 (bash overhaul): 10 identical Bash WORK calls still Terminate` |
| **R3** | 8 次同参**失败**查询（isError=true，错误文本相同）→ 仍 Terminate（S1 单点保留） | behavioral（**对照臂，当前绿**） | 同上 | `00-batch1-full.log` 第 39 行 `+ R3 …` | `+ R3 (bash overhaul): 8 identical FAILED queries still Terminate (S1 single point kept)` |
| **R4** | 第 5 次同参查询（`queryWarnAfter` 缺省 5）→ `Verdict.Warn` 且 msg 提到 `BgWait`（L0 提示，不 Terminate） | behavioral | 同上 | `02-R4-loopguard-query-nudge-red.log` | `expected Warn (L0 nudge) at the 5th identical query, got Pass` |
| **R5** | 20 次同参 `BgWait` 调用 → 不得 Terminate | behavioral | 同上 | `03-R5-loopguard-bgwait-rcall-red.log` | `must NOT Terminate (got: loop-detected: tool 'BgWait' has now been called 20 times in a row …)` |
| **T4** | `Defaults.BgGateCompletionHold == true`（缺省；显式 `-Dnebflow.bgGate.completionHold=false/true` 双向保留） | behavioral | `src/test/scala/nebflow/core/tools/BashOverhaulGateSpec.scala` | `04-T4-completion-hold-default-red.log` | `completion-hold gate must default ON …, got false`（另两条 prop 用例当前绿） |
| **T5** | 真实进程 >11MiB 输出：回执含 `TAIL-MARK`、含 `[Output truncated: showing last`、标注里的全文落盘文件存在且含 `HEAD-MARK`、回执 ≪ 11MiB | behavioral | `src/test/scala/nebflow/core/tools/BashOverhaulTruncationSpec.scala` | `05-T5-truncation-tail-dropped-red.log` | `tail must survive truncation (the tail marker is GONE) \| bytes=10485866`；末尾 `[Output truncated due to size limit]` |
| **T2** | `BgNotifyDelivery.deliverAgentEvent(targetRef=None, targetSessionId=Some(sid), …)` 必须产生**可观测的持久待补投**（真实写点 `BgInboxPort`→`CompactionQueueStore`） | behavioral | `src/test/scala/nebflow/core/tools/BashOverhaulNotifySpec.scala` | `06-T2-notify-noref-silent-red.log` | `Obtained 0 / expected 1`（`events.length` —— 静默 `IO.unit`） |
| **T2(b/c)** | `BgTaskRegistry.markAwaited(id)` 存在；`BashTool.makeNotifyCallback` 在 `agentActorRef=None` 下返回非 None 且仍落账本+输出留存；「先落账再投递」（投递腿看见账本行） | **compile** | 同上（追加段） | `11-T2-compile-red-notify.log` | `value markAwaited is not a member of object … BgTaskRegistry`；`method makeNotifyCallback cannot be accessed … private method`（×2） |
| **T1** | `BgWaitTool.name == "BgWait"`；schema 含 `jobIds`(required)+`deadline`；5s 任务 `deadline=10000` → `[completed]` 含退出码+尾段；`deadline=300` → `[still running]` 且**不杀** job；`deadline=10000000` 在 500ms 预算 ctx 下按预算返回（钳制同源 `ctx.bashConfig.foregroundWaitBudgetMs`） | **compile** | `src/test/scala/nebflow/core/tools/BgWaitToolSpec.scala` | `09-T1-bgwait-compile-red.log` | `Not found: BgWaitTool`（×6 处） |
| **T6** | `ProcessTree.platformPlan("Windows 11")==Plan.TaskKill`、`("Mac OS X")==SignalStopThenKill`、`("Linux")==TermThenKill`；`taskkillArgs(4242)` = Seq(`<System32 绝对路径>/taskkill.exe`, `/F`, `/T`, `/PID`, `4242`)；darwin 活体 `bash -c 'sleep 30 & sleep 30 & wait'` → killProcessTree 后 3s 内后代全死 | **compile** | `src/test/scala/nebflow/core/util/ProcessTreeSpec.scala` | `10-T6-processtree-compile-red.log` | `value Plan is not a member of object … ProcessTree`；`value platformPlan is not a member …`；`value taskkillArgs is not a member …`（共 10 处） |
| **T7** | 设备腿（`ctx.isRemoteExec=true`）前台长命令在压缩后的前台预算（`timeout=2000`）到点 → 回执 `[Background job started]`、`isError=false`、任务行进 `BgTaskRegistry`；**不是** `[Command timed out after …]` | behavioral | `src/test/scala/nebflow/core/tools/DeviceBashAutoBackgroundSpec.scala` | `07-T7-device-fg-timedout-red.log` | `got the timed-out error path: [Command timed out after 2000ms]` + `WARN nebflow.shell - Command timeout (2s) reached — killing process tree: sleep 12 && echo device-bg-done` |

合计第一批：**36 用例 / 7 失败 / 29 通过**（失败的 7 条正是 R1、R4、R5、T4、T5、T2、T7；R2/R3 是对照臂，按设计保持绿）。
第二批：**19 errors found**，全部落在 `BgWaitToolSpec`(6) / `ProcessTreeSpec`(10) / `BashOverhaulNotifySpec`(3) —— 无一落在第一批 5 个文件上（第一批文件完好）。

---

## 2. BASELINE（改前数字 — 旧语义的实测锚）

**(a) 同参查询第 10 次触发 `loop-detected` Terminate —— 本相机械复现 + 生产现场**

```
# 机械复现（本次红跑，R1 用例；旧代码 identicalCallHard=10 命中）
must NOT Terminate (got: loop-detected: tool 'Bash' has now been called 20 times in a row with
identical arguments — turn terminated to avoid spinning. Change the approach; for long-running
work use run_in_background instead of repeating the same call.)

# 生产现场（stand.log，本批 10:57 令下发前 1 分钟的节点 CanvasAskResize）
116937:2026-11-04 10:58:19.871 INFO nebflow.agent.lifecycle - … event=llm-fail detail=err=loop-detected: tool 'Bash' has now been called 10 times in a row with identical
116938:2026-11-04 10:58:19.901 WARN nebflow.node.engine - Node 'CanvasAskResize' failed: loop-detected: tool 'Bash' has now been called 10 times in a row with identical arguments — turn terminated to avoid spinning. …
117134:2026-11-04 10:58:30.134 INFO nebflow.core.tools.BashTool$ - Background job 2e4603d2 callback: completed (exit 1 · 52s) sessionId=node-9cef3a8a
117135:2026-11-04 10:58:30.155 WARN nebflow.bg-notify - [bg-notify-undelivered] background job 2e4603d2 reached 'completed' but target session 'node-9cef3a8a' no longer exists — nothing to activate, nothing persisted; …
```
读法：**任务跑了 52s，第 10 次查询落在第 41s —— 再等 11 秒就会拿到真结果**（`117134` 证明它 10:58:30 成功收尾），
守卫在第 10 拍把它判成空转，turn 终止后通知投给已收殓会话（`117135`）。

**(b) 旧代码丢尾的 marker 行（T5 实测）**

```
tail must survive truncation (the tail marker is GONE) | bytes=10485866 |
head=[big-output-truncation]\n(cwd: …)\nHEAD-MARK-52d7de1cf73b\nfiller-xxx…
tail=…filler-xxx…\n[Output truncated due to size limit]
```
数字：命令输出 **11MiB+4KB**，回执 **10485866 字节**（= `MaxOutputSize` 10MB + 标记文字），
**`TAIL-MARK-52d7de1cf73b` 整段蒸发**，标注用的是退役口径 `[Output truncated due to size limit]`
（`shell.scala:1325-1345`）。⇒ 大输出里最有价值的尾部错误（构建/测试失败行）在旧口径下必定丢。

**(c) 设备腿 timed-out 行（T7 实测）**

```
WARN nebflow.shell - Command timeout (2s) reached — killing process tree: sleep 12 && echo device-bg-done
… device/remote foreground leg must auto-background at the budget, got the timed-out error path:
  [Command timed out after 2000ms]
```
说明：生产码位 `BashTool.scala:698` 是 `explicitTimeoutMs.getOrElse(DEFAULT_TIMEOUT)`，
`DEFAULT_TIMEOUT = 30_000L`（`BashTool.scala:18`）——本 spec 用 `timeout=2000` **压同一个预算面**
（零全局配置污染）复现同一条代码路径。**诚实注记**：本机保留日志里
`grep -c "Command timed out after 30000ms" ~/.stand/logs/stand.log` = **0**（缺省 30s 形态在
当前日志窗口内无命中），故 (c) 的 30s 数字取自代码常量 `BashTool.scala:18`，而非现场日志。
另一半证据：失败回执是 `isError=true`，会喂 S1 同败 streak（8 次 → L1）——这是 D9 的放大器。

---

## 3. 无法机械表达 / 边界（如实登记）

1. **T7 的完整设备腿**（host `RemoteExecutor` → HTTP → peer `NeblinkRoutes`/`NeblinkRelayTunnel`）需要
   两个真实实例。本相只钉**缺陷所在的 peer 侧那一半**（`BashTool.scala:691-709`），
   用 `ToolContext(isRemoteExec = true)` 构造生产同形上下文 —— 这是该缺陷**可机械表达的最接近面**。
   host 侧 HTTP 分发/等待上限（`RemoteExecutor.scala:852-935, 1740-1741`）**未在本 spec 覆盖**（不伪造）。
2. **T6 的 Windows 腿**无法在 darwin 上跑活体 `taskkill /T`：本 spec 钉纯函数（`platformPlan` /
   `taskkillArgs`，含 System32 绝对路径与 `/T`），活体树只钉 POSIX 腿（`bash & wait`）。
   Windows 实机验证仍归 KAI 设备上的冒烟（未做）。
3. **T2(c)「投递腿抛错仍留账本」**：本相用「投递腿被拦时账本行已在盘上」的拦截式断言（install 一个
   观察 face，读 `BgTaskLedger.pending` 的时间点先于交付动作）表达顺序；真正的
   「投递腿抛异常」路径未单独构造（`ref !` 是队列 offer，抛错形态不稳定，不伪造）。
4. **T1 的 `[completed]` 回执措辞**：spec 只要求含 `[completed]`、jobId、`exit` 字样与输出尾段，
   **不锁定具体文案**（`[completed]`/`[still running]` 两个方括号块头的形状锁定，字段排列留给实现）。
5. **第一批里 R2/R3 当前就是绿的**（对照臂，作者口径「既有不改松」）：它们的价值是**防过度修复**，
   不是红证据。

---

## 4. 纪律声明

- **零 src/main 改动**（本批只动 `src/test/scala/**`；`git diff --stat` 可核）。
- 既有文件只**追加**：`LoopGuardSpec.scala` 原有 24 个用例逐字未动（`git diff --numstat` = `75 0`，
  零删除行；5 个新用例中 R2/R3 为对照臂、当前即绿）。
- 未重启/未 kill 生产实例（pid 78176 未触碰）；未 `stand stop|restart`；未 push；未 commit 到 main。
- 测试只用 `/tmp` 与 `target/test-*` 临时根（`BashOverhaulNotifySpec` 把 `PathUtil.dataRoot` 重定向到
  `target/test-bash-overhaul-notify` 并在 `afterAll` 还原），零污染真实 `~/.stand`。
