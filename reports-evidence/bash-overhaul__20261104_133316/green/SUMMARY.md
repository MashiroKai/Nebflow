# bash overhaul · GREEN 相证据（T1–T7）

作者令 2026-11-04（逐字）：「先安排重启，然后组织优化 bash，用业界成熟做法完整实现，一步到位。」

本目录 = **绿相机械证据**：`sbt Test/compile` + 逐 T `sbt "testOnly …"` 的原始输出（ANSI 已剥离）。
红相对照目录 = `reports-evidence/20261104_bash-overhaul/red/`（断言与「改前必红」形态，本相未改其任何字节）。

- 工作树：`.nebflow/worktrees/delegate-nebflow-485bb4f4`（分支 `delegate/delegate-nebflow-485bb4f4`）
- 环境：Scala 3.5.2 / sbt 1.10.10（`-Xmx1500m`，本机 sbt 全局串行闸）/ macOS darwin
- 复现命令见各日志首行 `###`。

---

## 1. 编译

| 项 | 读数 | 证据 |
|---|---|---|
| `sbt Test/compile` | **0 error / 0 warning；`[success] Total time: 35 s`** | `compile-green.log` |
| 红相基线 | 同命令红相为 **19 errors** | `../red/08-compile-red-full.log` |

---

## 2. 逐 T 一览（断言 / 红证据 / 绿证据 / 耗时 / Total-Failed）

| T | 断言（要点） | 红证据 | 绿证据 | 耗时 | Total / Failed |
|---|---|---|---|---|---|
| **T3** | R1 同 turn 20 次同参 Bash **查询**不 Terminate/Freeze；R2 10 次同参**工作**调用仍 Terminate（「10 times in a row」）；R3 8 次同参**失败**查询仍 Terminate（「failed 8 times」，S1 单点保留）；R4 第 5 拍同参查询 = `Warn` 且文案含 `BgWait`；R5 20 次同参 `BgWait` 不 Terminate | `red/01-R1-…`, `02-R4-…`, `03-R5-…`（R2/R3 为对照臂，红相即绿：`red/00-batch1-full.log`） | `green/T3-green.log` | 12 s | **29 / 0** |
| **T1** | `BgWaitTool.name == "BgWait"`；schema 含 `jobIds`(required)+`deadline`；5s 任务 `deadline=10000` → `[completed]` 含 jobId+`exit`+输出尾段；`deadline=300` → `[still running]` 且注册行存活（**不杀**）；`deadline=10000000` 在 500ms 预算 ctx 下按预算返回（单上限同源）；20 次同参 `BgWait` 永不计 R-call | `red/09-T1-bgwait-compile-red.log`（`Not found: BgWaitTool` ×6） | `green/T1-green.log` | 15 s | **5 / 0** |
| **T2** | `targetRef=None + targetSessionId=Some` ⇒ 真实落盘待补投标记（`CompactionQueueStore.load` 读到 1 条，correlationId `bg-redelivery-<job>`，metadata `bgTaskId`）；`None+None` 不炸；T2(a) awaited 完成不重复推送但账本行 + 输出留存仍落；T2(b) `agentActorRef=None` 仍返回真回调且留存 `completed`；T2(c) 投递腿看到账本行（先落账再投递）。含既有 `BgNotifyDeliverySpec` 6 例零漂移 | `red/06-T2-notify-noref-silent-red.log`（`Obtained 0 / expected 1`）、`red/11-T2-compile-red-notify.log` | `green/T2-green.log` | 7 s | **11 / 0** |
| **T4** | `Defaults.BgGateCompletionHold == true`（缺省翻转）；显式 `-Dnebflow.bgGate.completionHold=false` 仍 false；显式 `=true` 仍 true（双向保留） | `red/04-T4-completion-hold-default-red.log`（`got false`） | `green/T4-green.log` | 6 s | **3 / 0** |
| **T5** | 真实进程 >11MiB 输出：① `TAIL-MARK` 存活（旧口径丢尾）；② 回执含 `[Output truncated: showing last`，退役标注 `[Output truncated due to size limit]` **必须消失**；③ 标注给出全文落盘路径、文件存在且**头尾标记都在**（= 全文而非尾段另存）；④ 回执 < 3MB | `red/05-T5-truncation-tail-dropped-red.log`（`bytes=10485866`，tail marker GONE） | `green/T5-green.log` | 8 s | **1 / 0** |
| **T6** | `platformPlan`：Windows→`TaskKill`、`Mac OS X`→`SignalStopThenKill`、Linux→`TermThenKill`；`taskkillArgs(4242)` = 5 元（`System32` **绝对路径** taskkill.exe, `/F`, `/T`, `/PID`, `4242`）；darwin 活体 `bash -c 'sleep 30 & sleep 30 & wait'` → `killProcessTree` 后 3s 内后代归零 | `red/10-T6-processtree-compile-red.log`（`value Plan/platformPlan/taskkillArgs is not a member` ×10） | `green/T6-green.log` | 7 s | **3 / 0** |
| **T7** | 设备腿（`ctx.isRemoteExec=true`）`timeout=2000` 的长命令到预算 → 回执 `[Background job started]`、`Right`（`isError=false`）、任务行进 `BgTaskRegistry`，**不是** `[Command timed out after …]`。含既有 `DeviceBgTaskSpec` 7 例零漂移 | `red/07-T7-device-fg-timedout-red.log`（`[Command timed out after 2000ms]`） | `green/T7-green.log` | 9 s | **8 / 0** |
| **REG** | 回归面 11 个 suite：`BashToolSpec` `BashForegroundBudgetSpec` `BashAutoBackgroundSpec` `BashBackgroundHardTimeoutSpec` `BashPersistentBgTaskSpec` `BgTaskRegistrySpec` `BgTaskLedgerSpec` `BgNotifyPayloadSpec` `BgTaskCancelSpec` `BgTaskOutputDiskSpec` `ShellOutputSpillSpec` | —（新增/回归面，无红相） | `green/REG-green.log` | 113 s | **72 / 0** |

**合计**：8 次 `testOnly` 调用 / **132 用例 / 0 失败 / 0 错误**（29+5+11+3+1+3+8+72）。

---

## 3. M3 内存不变量（分发器裁定第 2 条：**不可退让**）

`ShellOutputSpillSpec`（M3 批的验红/验绿读数，本批必须保持绿）在绿相实测：

```
[M3-evidence] heapBefore=217777KB heapAfter=217873KB heapDelta=96KB outLen=51394 spilledBytes=104857600
```

读法（100MB 输出 / 1024B 行）：**堆增长 96KB**（阈值 1MB；M3 批红相基线 ≈10MB/流），回执可见 **51,394B**
（阈值 256KB），溢写文件 **104,857,600B = 100MB 全量**（阈值 100MB−4096）。
⇒ 「尾窗 + 流式写盘」在 T5 新实现内部保住了「堆有界」这条既有约束，**未回退到 M3 的类**。

---

## 4. 绿相改动的既有断言（逐条「旧 → 新 + 依据」）

**没有任何断言被削弱**；下面四条是红相 spec 自身的**缺陷修正 / 口径对齐**，全部同义或更强：

| 文件:行 | 旧断言/形态 | 新形态 | 依据 |
|---|---|---|---|
| `BgWaitToolSpec.scala:60-65` | `required.contains("jobIds")`，其中 `required: List[Option[String]]` | `required: Vector[String]`（先 `flatMap(_.asString)` 再 `contains`） | **红相写法缺陷**：`List[Option[String]].contains("jobIds")` 取 `Any` 超类型 ⇒ 编译通过但**恒为 false**，任何合法 schema 都无法满足（T1 交付报告已点名）。修后是真检查，**同义更强** |
| `ShellOutputSpillSpec.scala:131-144` | 「可见内容 ≤256KB」+「含 `truncated`」+「溢写目录非空」 | 追加一条：回执必须含 `full output: `（全文指针） | T5 统一口径（尾段可见 + 全文路径可读）。**只增不减**；「堆增长 ≤1MB」「spilled ≥100MB−4096」等原断言逐字保留 |
| `ShellOutputSpillSpec.scala:16-18, 103, 131` | 标题/注释写「首 64KB + 尾 64KB」「`<dataRoot>/shell-spill/`」 | 标题/注释改为「滚动尾窗 ≤2000 行 ∧ ≤50KB」「注记给溢写路径」 | M3 的 `OutputSpillBuffer` 已被 T5 的 `OutputTailWindow` 取代（语义归本批 T5）；**断言零改动**，只改陈旧描述 |
| `BashOverhaulTruncationSpec.scala:27-42` | 文件头写「保头 8MB + 保尾 2MB + 中间省略」（调研件候选方案） | 改为「保尾 ≤2000 行 ∧ ≤50KB + 溢出全文落盘回绝对路径」 | 同上；**四条断言逐字未动**（①尾标记存活 ②新标注 ③文件存在且含头尾标记 ④回执 <3MB） |

**新增**（非改动）：`BgTaskOutputDiskSpec.scala` 追加 1 例
`A1: the truncation marker names the job's output-retention file — absolute, existing, and readable`（钉分发器裁定的 A1 交付面，见 §5）。

---

## 5. 绿相为实现转绿所做的 src/main 改动（3 处）

| 文件 | 改动 | 为什么 |
|---|---|---|
| `BgWaitTool.scala:131` | `.distinct` → `.distinct.toList` | **真编译错**（T2 已点名）：`parseJobIds` 声明 `Either[String, List[String]]`，`asArray`/`asString` 的 lub 得 `Seq[String]` ⇒ `Right(ids)` 类型不匹配。纯类型收敛，零语义改动 |
| `BgTaskOutputStore.scala:119-138` | 新增 `readableOutputPath(jobId): Option[String]` | **A1 交付面**（分发器裁定第 3 条）：给出该 job 输出留存文件的**绝对路径**，`Files.isRegularFile` **存在才给**；来源 = `open` 时登记的 LogWriter 路径 → 回落 `persistDir` 推导的同值路径（`bg-task-logs/<jobId>.log`）；都不可得 ⇒ `None` |
| `BgNotifyPayload.scala:128-152`（+ 对象头 §2 文档） | 截断标注指针从 `full output: background-tasks panel, job <id>` 改为 `readableOutputPath` 命中的**绝对路径**，取不到才回落面板指针 | 同上（作者 T5 口径「溢出全文落文件并**回路径**」）。形态 `[truncated: showing last N of M lines — full output: …]` **未变**：读线程面与通知面同骨，只差「全文在哪」 |

**诚实性（分发器裁定第 5 条）**：核对 `OutputTailWindow.spill`（`shell.scala:1966-1994`）—— 首次溢写时**先把驻留的全部已读行回放进新文件**，再逐行续写；且溢出判据（`totalBytes > keepBytes`）与尾窗裁剪判据同阈值、裁剪发生在追加**之后** ⇒ 溢出点之前**一行都没被丢过**，盘上文件确实**从第一行起**。
因此标注可以无条件写 `full output`（收口方案 (a) 已由实现满足），**不存在会骗人的标注**。

---

## 6. 未做 / 开放项

1. **Windows / 设备实机未证**：`taskkill /F /T` 的活体效果与 host↔peer 双实例收编链在 darwin 上无法验证（T6 只钉纯函数 + Windows 短路判定；T7 只钉 peer 侧前台腿 + 既有 `DeviceBgTaskSpec`）。需另派 KAI 实机冒烟。
2. **T7 双侧同预算竞态**（T7 报告已如实登记）：>2min 的设备命令会「host 先收编、设备也收编」，host 行转 handoff 正文、真实输出留在对端留存区。契约未给豁免，本相不擅自加。
3. **全量 `sbt test` 未跑**（本轮只跑 brief 指定的 7 T + 回归面 11 suite）。新缺省 `BgGateCompletionHold=true` 的全局影响面（T4 报告 §4 提示）需一次全量回归确认，不在本节点范围。
4. `BgTaskCancel.scala:24-26` 注释仍写「迟到的真完成只补输出留存区（P2 守卫）」——P2 闸门已按 T2 契约退场，该注释过期（非本节点授权文件，未改）。
