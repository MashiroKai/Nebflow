# bash overhaul · 全量回归证据（SUITE 相）

作者令 2026-11-04：「组织优化 bash，用业界成熟做法完整实现，一步到位。」
本目录 = 全量 `sbt test` 的**原始日志 + 数字 + 逐条归属**。上游两相：`../red/`（改前必红）、`../green/`（逐 T 绿）、`../e2e/`（端到端复现）。

- 工作树：`.nebflow/worktrees/delegate-nebflow-485bb4f4`（分支 `delegate/delegate-nebflow-485bb4f4`）
- 本批改动集：`git diff 77afefc77..HEAD` = **104 文件**（`src/main` 30 + spec 11 + 前端/证据件）
- 环境：Scala 3.5.2 / sbt 1.10.10 / macOS darwin（16GB，`--release:17` 字节码目标、运行 JDK 23.0.1）

---

## 0. 三轮跑一览（含一次被环境毒化的作废跑，如实留档）

| 轮 | 起止（本地） | 命令要点 | sbt Total time | 结果 | 日志 |
|---|---|---|---|---|---|
| **RUN#1（作废）** | 16:02:42 → 16:41:06（墙钟 38m23s） | 走 sbt 串行闸缺省 `-Xmx1500m`，**不设 metaspace** | — | **`OutOfMemoryError: Metaspace`**：跑到第 864/1006 个 suite 时打满，其后 **172 个 suite 全部 `initializationError`**；`sbt exit=1` | `full-test-run1-ENV-METASPACE-OOM.log` |
| **RUN#2（全量首跑）** | 16:41:39 → 17:33:52（sbt **52m05s**） | `SBT_OPTS="-Xms512m -Xmx2560m -XX:MaxMetaspaceSize=1536m"` | **3125 s** | **Total 9048 / Failed 48 / Errors 0 / Passed 8994 / Skipped 6 / Ignored 14** | `full-test.log` |
| **BASELINE（基线对照）** | 17:34:49 → 17:52:54（sbt 17m16s） | 同 JVM 参数，只跑 RUN#2 的 27 个红 suite | **1036 s** | **Total 311 / Failed 50 / Errors 0 / Passed 260 / Skipped 1** | `baseline-77afefc77-reds.log` |

**串行闸排队单列**：RUN#2 与 BASELINE 均 `[sbt-gate] gate acquired after 0s`（**零排队**）；RUN#2 的闸日志逐字为
`[sbt-gate] heap cap not injected: SBT_OPTS already carries -Xmx (explicit wins)` —— 显式堆参数被尊重，闸只做并发串行化。

### 0.1 为什么 RUN#1 作废（环境天花板，非本批回归）

本机 `JAVA_OPTS` 全局钉了 `-XX:MaxMetaspaceSize=512m`（sbt 启动 JVM、测试跑在同一 JVM 内：`Test / parallelExecution := false` 且未 fork），
1006 个 suite 在一个 JVM 里装不下。**这不是本批引入的红**，仓内已有独立先例：
`reports-evidence/b1-dataroot__20261104_110844/green/full-suite-oometaspace.log`（2.1MB，`OutOfMemoryError: Metaspace` 命中 **435** 次）。

解法取项目自己的钉值：`.github/workflows/ci.yml:12-16`（作者 2026-10-24 CI 迁移批）逐字写
`SBT_OPTS: "-Xms512m -Xmx3g -XX:MaxMetaspaceSize=2g"`，注释为「Metaspace 上限是 OOM 的直接解」。
本机按 16GB + swap 已用 3.5G 收一档为 `-Xmx2560m -XX:MaxMetaspaceSize=1536m`（两个上限都记在每份日志首行）——**RUN#2/RUN#3 全量 0 次 OOM**。

---

## 1. RUN#2 全量数字与失败清单

```
[error] Failed: Total 9048, Failed 48, Errors 0, Passed 8994, Skipped 6, Ignored 14
```

- **Total 9048 / Failed 48 / Errors 0 / Passed 8994 / Skipped 6 / Ignored 14**
- 有红 suite：**27** 个（48 条用例）
- 无一 OOM、无一编译错（`Test/compile` 在 RUN#2 前已绿，见 `../green/compile-green.log`）

### 1.1 逐 T 绿红状态（本批 7 条改造线 + 回归面）

| 面 | spec | RUN#2 状态 |
|---|---|---|
| **T1** BgWait 唯一等待原语 | `BgWaitToolSpec`（5 例） | 🟢 全绿 |
| **T2** 持久先落账再投递 + awaited 去重 | `BashOverhaulNotifySpec`（11 例）+ 既有 `BgNotifyDeliverySpec` | 🟢 全绿 |
| **T3** LoopGuard 查询腿/等待腿 | `LoopGuardSpec`（29 例，含 R1–R5） | 🟢 全绿 |
| **T4** 完成门腿 1 缺省翻转 | `BashOverhaulGateSpec`（3 例） | 🟢 全绿 |
| **T5** 尾窗 + 全文落盘 | `BashOverhaulTruncationSpec`（1 例）、`ShellOutputSpillSpec`（**M3 必绿面**） | 🟢 全绿 |
| **T6** 进程树 | `ProcessTreeSpec`（3 例） | 🟢 全绿 |
| **T7** 设备腿口径 | `DeviceBashAutoBackgroundSpec`（8 例）+ 既有 `DeviceBgTaskSpec` | 🟢 全绿 |
| **REG** 回归面 11 suite | `BashToolSpec` `BashForegroundBudgetSpec` `BashAutoBackgroundSpec` `BashBackgroundHardTimeoutSpec` `BashPersistentBgTaskSpec` `BgTaskRegistrySpec` `BgTaskLedgerSpec` `BgNotifyPayloadSpec` `BgTaskCancelSpec` `BgTaskOutputDiskSpec` `ShellOutputSpillSpec` | 🟢 全绿 |
| **E2E** 端到端复现 | `BashOverhaulEndToEndSpec`（5 例） | 🟢 全绿 |
| **本批改动的既有 spec** | `RootSixBaseToolsSpec`、`RemoteableDeviceParamSpec`、`NodeReportReminderSpec`、`BgTaskOutputDiskSpec`、`LoopGuardSpec` | 🟡 见 §3（`RemoteableDeviceParamSpec` 一条负控 = 既有红） |

**结论：T1–T7 + REG + E2E 在全量里 0 失败。**

---

## 2. 逐条归属（本批 vs 既有）——48 条精确三分

判据 = **逐用例名**在两条腿上取差集（不是按 suite 粗判）：

| 集合 | 条数 | 含义 |
|---|---|---|
| **BOTH**（RUN#2 红 ∧ 基线红） | **41** | **既有红**，与本批无关（基线证明在 §4） |
| **RUN#2 only** | **7** | 需要逐条定性 |
| **BASELINE only** | 9 | 基线红、RUN#2 绿（`UpdateOrchestratorSpec` 一族），与本批无关 |

RUN#2-only 的 7 条，进一步定性：

| # | 用例 | 定性 | 依据 |
|---|---|---|---|
| 1 | `core.tools.DelegateToolSpec.nebflow tool face = BaseTools + AskUserQuestion + Subagent + AgentFlow (9 items)` | **本批红（已修）** | `AgentCore.BaseTools` 六→七（+`BgWait`）⇒ `NebflowFixedTools` 九→**十**。断言写死 9 |
| 2 | `agent.AllowedToolSetSpec.team agent concrete tools list yields those tools + base + Mail + SubTask + TeamTask*` | **本批红（已修）** | 期望集缺 `BgWait`（obtained 多一件，见 RUN#2 日志 `+ "BgWait"`） |
| 3 | `core.project.NodeReportReminderSpec.R4` | **噪声（非本批）** | 详见 §2.1 |
| 4 | `agent.FreezeGateSpec.ER-5` | **噪声** | `java.nio.file.DirectoryNotEmptyException: /var/folders/…/T/…/sessions` —— 临时目录清理竞态 |
| 5 | `core.delegate.WorktreeReuseSelectionSpec.J6c` | **噪声（工作树生命周期）** | 用例钉「真实仓库里必须枚举到具体树 `delegate-nebflow-4b756df9`」；该树已不在本机清单（现 119 棵树） |
| 6 | `core.processor.TaskStuckWatcherSpec.run 循环跨 sleep 边界递归…` | **噪声（负载）** | `expected >=2 Stop across loop rounds, got 1` |
| 7 | `core.processor.TaskStuckWatcherSpec.Project flow 会话连续分级接管…` | **噪声（负载）** | `TimeoutException: test timed out after 31 seconds` |

### 2.1 #3–#7 的闭环证据：同参数复跑全绿

```bash
SBT_OPTS="-Xms512m -Xmx2560m -XX:MaxMetaspaceSize=1536m" sbt -batch \
  "testOnly nebflow.agent.AllowedToolSetSpec nebflow.core.tools.DelegateToolSpec \
   nebflow.core.project.NodeReportReminderSpec nebflow.agent.FreezeGateSpec \
   nebflow.core.delegate.WorktreeReuseSelectionSpec nebflow.core.processor.TaskStuckWatcherSpec"
```

`fix-verify-01.log`（sbt 115 s）：**Total 148 / Failed 1 / Errors 0 / Passed 146 / Skipped 1**，唯一红 =
`DelegateToolSpec.schema: exactly task/name/project/executor/mode`（= **既有红**，基线同红，见 §3）。
`TaskStuckWatcherSpec` ×2、`NodeReportReminderSpec.R4`、`FreezeGateSpec.ER-5`、`WorktreeReuseSelectionSpec.J6c` **全部转绿**
⇒ 它们在 RUN#2 的红是负载/临时目录/工作树清单三类环境形态，不是本批行为回归。

`NodeReportReminderSpec.R4` 额外核对：本批对该 spec 只改了注释 + 把 `bgGateCompletionHold` **显式注入 false**（`mountProject` 早已如此），
生产侧 `NodeEngine.scala` / `NodeStarter.scala` 的 diff **只有注释**（`git diff 77afefc77..HEAD -- …NodeEngine.scala …NodeStarter.scala` 可核，零逻辑行）。

---

## 3. 既有红清单（41 条 / 24 suite）——不加掩饰，逐 suite 列

| suite | 条数 | 形态（一句话） |
|---|---|---|
| `agent.DelegateBgWaitHoldSpec` | 3 | A-1/A-2/A-4 全 `waitUntil` 超时 |
| `agent.EmptyShellNotifySpec` | 3 | 三条 `waitUntil` 超时 |
| `agent.DelegateNotifyRedirectSpec`→`core.project` | 3 | R1/R2/R3 各 30s `waitUntil` 超时 |
| `agent.DelegateFlowHostsSpec`→`core.project` | 3 | H3/H4、H3b、H5 `waitUntil` 超时 |
| `agent.MergeReceiptNotifyChainSpec` | 2 | R1/R2 `waitUntil` 超时 |
| `agent.SubAgentTaskStatusSpec` | 2 | Completed/Failed 事件 3s `waitUntil` 超时 |
| `agent.BgWaitCapLedgerSpec` | 1 | C1 `waitUntil` 超时 |
| `agent.SubAgentInboxMirrorSpec` | 1 | ③-2「12 处调用点零改」计数断言 |
| `agent.DelegateFreezeE2ESpec` | 1 | e2e 冻结/唤醒链 |
| `core.tools.MailDelegateContinuationDeliverySpec` | 2 | (4)(5) `waitUntil` 超时 |
| `core.tools.BgTaskReaperSpec` | 2 | 判定表 / 收割副作用 |
| `core.tools.DelegateToolSpec` | 1 | `schema: exactly task/name/project/executor/mode`，实测多出 `worktree`/`force` |
| `core.tools.PresetCatalogInToolDocSpec` | 1 | 「Delegate inputSchema 无 preset」 |
| `core.tools.RemoteableDeviceParamSpec` | 1 | 负控「Delegate 漏斗面恰四参数」 |
| `core.plugin.OfficialIdentitySpec` | 5 | `'seed/plugins/nebflow-plugin-creator' is not on the test classpath` |
| `core.plugin.PluginRemoveSpec` | 1 | ⑤ `NullPointerException: url is null` |
| `core.project.ProjectCreatePanelSpec` | 2 | ① / ⑪ 脚手架计数 |
| `core.project.DispatcherNotifyP0P1Spec` | 1 | D4 文案真值 |
| `core.hotupdate.UpdateOrchestratorSpec` | 1 | criterion 4（locale key `neblink.updateCard.phase.idle` 缺失） |
| `gateway.FriendApiRoutesSpec` | 2 | `route fell through` |
| `gateway.Mvp2DeviceRoutesSpec` | 1 | `/conversations` 透传行 |
| `llm.decision.PluginCatalogLayerSpec` | 2 | T4a C17 / T4b C18 |
| **合计** | **41** | 全部经基线对照证实「改前即红」 |

**`RemoteableDeviceParamSpec` 的诚实注记**：该 spec 本批**已改**（9→10 计数与 device 面字面量），
但它那条负控（`Delegate` 漏斗面恰四参数）在**基线 77afefc77 上同样红** —— 归因不在本批，在更早的 Delegate 参数演进（`worktree`/`force` 已进 schema，负控未随之更锚）。本批不动它（不属本批范围）。

---

## 4. 基线对照：命令与结论（本批红/既有红的唯一分界）

```bash
# ① 开 detached 基线工作树（本批之前最新 main；含 M1–M4 内存缓解批与 dataroot 分层批，
#    故 M3 的 ShellOutputSpillSpec 与 dataroot 五 spec 不会被误判成本批引入的红）
git worktree add --detach /tmp/bashoverhaul-baseline-aade799e 77afefc77

# ② 只在基线树里跑 RUN#2 的那 27 个红 suite（绝不在当前工作树用 stash/reset 做对照）
cd /tmp/bashoverhaul-baseline-aade799e
SBT_OPTS="-Xms512m -Xmx2560m -XX:MaxMetaspaceSize=1536m" sbt -batch "testOnly <27 suites>"   # → baseline-77afefc77-reds.log

# ③ 逐用例名取差集（脚本见下）
# ④ 收工
git worktree remove --force /tmp/bashoverhaul-baseline-aade799e
```

**基线读数**：`Total 311 / Failed 50 / Errors 0 / Passed 260 / Skipped 1`（sbt 1036 s）。
**结论**：RUN#2 的 48 条里有 **41 条**在基线上同样红 ⇒ **既有红，与本批无交集**；
剩下 7 条中 **2 条是本批红（已修，复跑绿）**、**5 条是负载/临时目录/工作树清单噪声（复跑绿）**。

---

## 5. 本批修掉的回归（2 条，全在测试侧）

机制面变更（`BaseTools` 六→七，+`BgWait`）导致两处「按件数/按名单写死」的既有断言过期。**生产码零改动**，只更锚断言：

| 文件 | 旧 | 新 | 为什么不是「放宽」 |
|---|---|---|---|
| `src/test/scala/nebflow/core/tools/DelegateToolSpec.scala:356-368` | 标题「(9 items)」+ `assertEquals(face.size, 9)` | 标题「(10 items)」+ `assertEquals(face.size, 10)`，并注明机制面依据 | 上一行 `assertEquals(face, BaseTools + "AskUserQuestion" + "Subagent" + "AgentFlow")` **逐字未动**（集合等式仍先钉死），只把随机制面变化的**计数**更锚；「带 `device` 者恰六件」不变式仍由 `RemoteableDeviceParamSpec` 逐件钉 |
| `src/test/scala/nebflow/agent/AllowedToolSetSpec.scala:135-158` | team 面期望集 11 件 | 期望集 +`"BgWait"` = 12 件 | 是**全等断言**（`assertEquals(allowed, Set(...))`），不是子集断言 ⇒ 多一件就红；`BgWait` 经 `BaseTools` 派生进 team 面是 T1 的**意图行为**，差异化的 Mail/SubTask/TeamTask* 逐字未动 |

复跑证据：`fix-verify-01.log` 中这两条均为 `+`（绿）；其余 4 个 suite 的 5 条噪声项同轮转绿。

---

## 6. 未做 / 开放项（如实登记）

1. **既有红 41 条未修**：全部经基线证实「改前即红」，属其它批的存量，不在本批授权面内（本批不借全量回归顺手改别人的锚）。
2. **Windows / 设备实机腿未证**：darwin 环境限制（`taskkill /F /T` 活体、host↔peer 双实例收编链），沿 `../green/SUMMARY.md` §6 未闭合项。
3. **`WorktreeReuseSelectionSpec.J6c` 属结构性脆弱点**：它钉「真实仓库里必须枚举到具体树 `delegate-nebflow-4b756df9`」，该树一旦被别的 session 回收，本 spec 就红。本轮复跑转绿是事实，但它会在任意一次工作树回收后再红——这不是本批的账，登记在此供后续批次重锚。
4. **`fix-verify-02.log` 被并发命名迁移截断**：本轮跑期间另一批（过程件目录命名迁移）把 `reports-evidence/20261104_bash-overhaul` 改名为 `reports-evidence/bash-overhaul__20261104_133316`，该日志的打开句柄只留下 177 字节表头；其读数（`Total 28 / Failed 2`）已由完成回执留存，且两条均由 `baseline-77afefc77-2suite-noise.log` 与 `fix-verify-03-adaptive-throttle-solo.log` 覆盖定性。

---

## 7. RUN#3（修后终轮全量）——最终读数

```
[error] Failed: Total 9085, Failed 41, Errors 0, Passed 9037, Skipped 7, Ignored 14
[sbt-gate] acquired after 410s
[error] Total time: 3061 s (0:51:01.0)
```

| 项 | 值 |
|---|---|
| **Total** | **9085** |
| **Failed** | **41** |
| **Errors** | **0** |
| **Passed** | **9037** |
| **Skipped / Ignored** | 7 / 14 |
| sbt 本体耗时 | 3061 s（51m01s） |
| 闸排队（单列） | **410 s**（另一 session 持锁；逃生口 `SBT_GATE=off` 未使用） |
| 墙钟 | 57m57s |
| 日志 | `full-test-run3-final.log` |

### 7.1 41 条逐条归属：**本批红 = 0**

与 `RUN#2 ∩ BASELINE` 的既有红集（41 条）做逐用例名比对：

- **39 条** = 既有红集内（同 RUN#2、同基线）。
- **+1 条** `core.processor.TaskStuckWatcherSpec.run 循环跨 sleep 边界递归 ≥2 轮不栈溢出` → **既有红**：
  `baseline-77afefc77-2suite-noise.log` 在 **77afefc77 上同样红**（`6.493s munit.FailException`）。间断性（`fix-verify-01.log` 里曾绿）。
- **+1 条** `llm.AdaptiveThrottleSpec.T9 healthJson：有闸才有键；载荷含 limit/rung/inFlight/queued` → **噪声**：
  该 spec **不在本批 diff**；基线上 **13/13 全绿**；单跑隔离复验 `fix-verify-03-adaptive-throttle-solo.log` **13/13 全绿（4s）** ⇒ 顺序/负载耦合的间歇，非回归。
- **−2 条**（RUN#2 红、RUN#3 绿）：`agent.DelegateFreezeE2ESpec.e2e`、`core.project.DispatcherNotifyP0P1Spec.D4` —— 二者基线亦红 ⇒ 同属**间断性既有红**。

> 因此终轮 41 红 = **40 条既有红 + 1 条间歇噪声**，**本批引入的红 0 条**。

### 7.2 与 RUN#2 的对照（修复生效的直接证据）

| 项 | RUN#2（修前） | RUN#3（修后） |
|---|---|---|
| Failed | 48 | **41** |
| 本批红 | 2 | **0** |
| 有红 suite | 27 | 14 |
| 两条本批红 | `DelegateToolSpec`「(9 items)」`assertEquals(face.size, 9)` 实测 10；`AllowedToolSetSpec` team 面期望集缺 `BgWait` | 两条均 `+` 绿 |
| 其余 | 46 条 = 既有红 41 + 噪声 5 | 40 条既有红 + 1 条噪声 |

### 7.3 一句话结口

**全量 `sbt test` 跑完（9085 例），本批引入的回归 0 条**；41 条红全部经「detached 基线 77afefc77 逐用例对照」证实为改前即红或负载/顺序间歇，
本批 2 条真红已修并复跑转绿（`fix-verify-01.log`）。全量不是「全绿」，是「**本批零红 + 既有红逐条有据**」——按此口径交付，不粉饰。

