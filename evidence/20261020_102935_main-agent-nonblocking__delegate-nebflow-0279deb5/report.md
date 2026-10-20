# main-agent-nonblocking：主 agent 前台零阻塞（预算到点自动转后台，不杀）

批次：2026-10-21 作者裁定重申「主 agent 一点不能阻塞（之前的设计是**不杀但自动转后台**）」
分支：`delegate/delegate-nebflow-0279deb5`（merge 并 main，零 push）
证据目录：`evidence/20261020_102935_main-agent-nonblocking__delegate-nebflow-0279deb5/`

---

## 0. 结论（一段话）

当日两次实撞的根因是**两机制的次序倒挂**：前台 Bash 的自动转后台预算取自
`Defaults.BashMaxTimeoutMs`（缺省 60min），而无输出停滞阈值
`Defaults.ForegroundNoProgressTimeoutMs`（缺省 600s）——静默的 1GB+ 克隆
（网络等待、零输出、CPU 低于 33ms/s 判据）必然在 600s 处先被
`foregroundNoProgressWatch` **杀树**，自动转后台腿结构性不可达，主会话每次
硬冻结 10 分钟。本次改动：前台等待预算收缩为**短预算**
`Defaults.ForegroundWaitBudgetMs`（缺省 **120s**，作者经 nebflow.json
`bashForegroundWaitBudgetMs` 或 system prop `nebflow.bash.foregroundWaitBudgetMs`
可调，建议 60-120s 量级），判据 = **时间**（与输出活性无关）；预算到点即
收编后台（回执含 job id + 查询方式，进程不杀，完成照旧通知）；前台 no-progress
ceiling 语义收缩（缺省面结构性不可达 = 自然消亡；收编即退役；仅作者把预算调
大于 600s 时保留为前台真挂死兜底）。

## 1. 第一段·现状测绘（改动前基线；锚点 = main 树 `1f1c0ffef`）

### 1.1 前台执行路径全链

| # | 环节 | 锚点（改动前） | 事实 |
|---|------|----------------|------|
| 1 | 入口分流 | `src/main/scala/nebflow/core/tools/BashTool.scala:501-582` | 非 `run_in_background`、非 remote → `executeForeground`（:582） |
| 2 | 前台等待预算取值 | `BashTool.scala:645`（`explicitTimeoutMs.getOrElse(MAX_TIMEOUT)`）；`BashTool.scala:28`（`MAX_TIMEOUT = Defaults.BashMaxTimeoutMs`）；`src/main/scala/nebflow/shared/Defaults.scala:181-182` | 缺省 **3,600,000ms = 60min**；system prop `nebflow.bash.maxTimeoutMs` 每次调用现读 |
| 3 | 预算机制实现 | `BashTool.scala:670-680` | `IO.racePair(joinResult(execFiber), IO.sleep(waitBudgetMs))`——**纯时间判据**；预算侧赢 → `autoBackground`（:680）→ `shell.adoptAsBackgroundJob`（shell.scala:890）收编 + 回执，**不杀** |
| 4 | 无输出停滞阈值实现 | `src/main/scala/nebflow/core/tools/shell.scala:702-740`（`foregroundNoProgressWatch`，:764 起 fiber——`isBackground=false` 且非 sleep 才起） | 每 `ForegroundSampleIntervalMs`（30s，Defaults.scala:722-723）采样：无新输出 ∧ 单窗 CPU 增量 ≤ `ForegroundCpuProgressNanos`（1s/窗 = 3.3% 单核，Defaults.scala:713-714）持续 `ForegroundNoProgressTimeoutMs`（**600s**，Defaults.scala:732-733）→ `ProcessTree.killProcessTree`（shell.scala:733）+ TimeoutException（shell.scala:782-791） |
| 5 | 停滞杀的用户面 | `BashTool.scala:715-716` | `formatOutcome` → `Left(ToolError("[Command stalled] …"))`——**杀**，不是留挂 |

### 1.2 谁先触发 + 触发后行为（②）

两机制**并行**（racePair 的预算 sleep ↔ 进程内的停滞 watch fiber）：

- 预算（60min）与停滞（600s）：**停滞阈值必然先到**（600 < 3600）。
- 停滞触发后行为 = **杀**（killProcessTree，进程死），带 "[Command stalled]" 错误回模型。
- 预算触发后行为 = 转后台不杀（但旧缺省下**永远轮不到它**）。
- 结论：文档所称「预算到点自动转后台未杀」仅在 `预算 < 600s` 时成立；旧缺省
  60min ≫ 600s ⇒ **自动转后台腿在缺省面结构性死亡**。两机制无互斥/优先级设置——
  就是两条独立计时线，短的那条赢。

### 1.3 turn 级与后台侧看护（对照面）

| 机制 | 锚点 | 行为 |
|------|------|------|
| TaskStuckWatcher 轴② 工具相位 | `src/main/scala/nebflow/core/processor/TaskStuckWatcher.scala:36-38`；`Defaults.ToolPhaseStuckMs`（600s） | 单个工具调用 >10min 判 turn 卡死（恢复链）；停滞杀没杀成时的下一道网 |
| B1 后台 idle | `shell.scala:1053-1082`；`Defaults.BgIdleTimeoutSec`=300 | **只提醒**（bg-slow 事件 + warn），不杀 |
| B2 后台硬超时停滞杀 | `BashTool.scala:538`；`Defaults.BashBackgroundNoHardTimeoutMs`（Long.MaxValue，Defaults.scala:250） | 已被哨兵豁免——后台任务无时间杀 |
| 显式后台 ceiling | `shell.scala:764`（`isBackground=true` 不起 watch） | 原生后台任务从不受前台 ceiling 管辖 |

### 1.4 今天两次案例的精确行为路径（③）

```
前台 clone 1GB+（无显式 timeout）
→ BashTool.doCall → executeForeground（预算 = 60min sleep 计时，BashTool.scala:645,672）
→ runProcess 起 foregroundNoProgressWatch（shell.scala:764）
→ 克隆形态：零输出 + 每窗 CPU 增量 ≤ 1s 门槛 → idleMs 只增不减
→ 600s 判死窗满（shell.scala:727）→ warn + stuckFlag + killProcessTree（:730-733）
→ TimeoutException("…foreground no-progress ceiling…")（:782-791）
→ BashTool.formatOutcome → Left("[Command stalled] …")（BashTool.scala:715-716）
→ 主会话收到错误、进程已被杀；10 分钟冻结 = 判死窗本身
```

### 1.5 子 agent 会话同一纪律还是主 agent 专属（按现架构如实判断）

**同一套，实例级一份，无主 agent 专属面。** 前台预算此前取自全局
`Defaults.BashMaxTimeoutMs`（无 per-agent 配置）；本次新增的预算同样走
实例级单点：nebflow.json → `GatewayMain`（src/main/scala/nebflow/gateway/GatewayMain.scala:608-620）→
`SharedResources.bashResilience`（src/main/scala/nebflow/agent/SharedResources.scala:133）→
`AgentSessionExecution.scala:1220`（`bashConfig = resources.bashResilience`）→ 全部会话
（主 agent / 子 agent / 节点 / team）共用的 `ToolContext.bashConfig`
（src/main/scala/nebflow/core/tools/types.scala:116）。即：**主 agent 与子 agent
同一纪律**。这与裁定兼容——子 agent 前台同样零阻塞是增益而非回归；节点会话
配合节点完成闸（等待型后台任务完成前节点不终态）结果不丢。

## 2. 第二段·按裁定实施（改动清单）

| 文件 | 改动 |
|------|------|
| `src/main/scala/nebflow/shared/Defaults.scala` | 新增 `ForegroundWaitBudgetMs`（缺省 120000，prop `nebflow.bash.foregroundWaitBudgetMs` 每次现读）；`BashMaxTimeoutMs` 语义收缩为入参钳 + remote-exec 面（缺省 60min 不变）；`BashResilienceConfig` 新增 `foregroundWaitBudgetMs` 字段 |
| `src/main/scala/nebflow/shared/config.scala` | nebflow.json 顶层键 `bashForegroundWaitBudgetMs: Option[Long]`（与 #391 bash 族键同族） |
| `src/main/scala/nebflow/gateway/GatewayMain.scala` | fail-safe 读取该键 → `BashResilienceConfig.foregroundWaitBudgetMs` |
| `src/main/scala/nebflow/core/tools/BashTool.scala` | `executeForeground`：预算 = `ctx.bashConfig.foregroundWaitBudgetMs`（≥1 钳），显式 `timeout` **min 钳**到该预算（agent 不能越过作者零阻塞裁定）；回执追加查询/取消方式；工具 description / inputSchema 同步新契约 |
| `src/main/scala/nebflow/core/tools/shell.scala` | `JobHealth` 新增 `adoptedAsBackground`（单向旗）；`adoptAsBackgroundJob` 收编瞬间置位；`foregroundNoProgressWatch` 每窗先查旗——收编即退役（下一采样窗静默退出），**转后台后的任务永不被 ceiling 杀** |
| `src/test/scala/nebflow/core/tools/BashForegroundBudgetSpec.scala` | 新增（7 条验收，见 §3） |
| `src/test/scala/nebflow/core/tools/BashToolForegroundSpec.scala` | 头注与 >60s 用例标题同步新缺省预算（行为断言不变：65s < 120s 仍前台） |

### 行为 spec（验收①）

1. **超预算命令被转后台**：前台命令运行超过 `foregroundWaitBudgetMs`（缺省
   120s，作者可调）→ 立即收编 `BgTaskRegistry` + WS 指示灯 + `BgTaskOutputStore`，
   与显式 `run_in_background` 同面。
2. **判据 = 时间**：与输出活性无关——静默命令与持续吐输出的命令同样在预算到点
   转后台（racePair 只看钟）。
3. **不杀进程**：收编后进程树存活跑完；收编瞬间 `adoptedAsBackground` 置位 ⇒
   前台 ceiling 退役；B2 已被 NoHardTimeout 哨兵豁免 ⇒ 后台任务无任何时间杀。
4. **回执形态**（裁定原文「已转后台+job 标识+如何查询」）：
   `[Background job started] Job ID: <id>` + `Auto-backgrounded after <n>s …
   NOT killed` + 完成通知承诺 + `Query progress anytime with background_job_id:
   <id>; cancel it with background_job_id: <id> + cancel_background_job: true.`
5. **完成通知**：照旧经 `makeNotifyCallback` 后台回调链投递（转后台前后同链）。
6. **停滞阈值语义收缩**：缺省 120s 预算 < 600s ceiling ⇒ ceiling 在前台阶段
   结构性不可达（自然消亡）；剩余管辖 = 作者把预算调大于 600s 时的前台真挂死
   兜底 + 收编即退役；后台任务的停滞面归 B1（只提醒）。
7. **兼容面**：引擎自身短命令（配置读写/git 短操作，毫秒级）≪ 120s 预算，
   照常前台返回真实输出（N-7）；remote-exec 同步面超时上限不变（仍
   `BashMaxTimeoutMs` 60min 钳，不适用短预算）；显式后台路径逐字未动。

### 配置面（作者可调，三档等价）

| 档 | 取法 | 生效时机 |
|----|------|----------|
| nebflow.json 顶层键（作者主面） | `"bashForegroundWaitBudgetMs": 90000` | 重启即生效 |
| system prop | `-Dnebflow.bash.foregroundWaitBudgetMs=90000` | 重启即生效 |
| 代码缺省 | `Defaults.ForegroundWaitBudgetMs = 120000` | — |

## 3. 验收自查（每条二值判定 + 证据）

| # | 条件 | 结果 | 证据 |
|---|------|------|------|
| ① | 行为 spec（转后台/回执/通知/不杀） | **PASS 7/7** | `test-output-new-spec.log`：N-1/N-2/N-2b/N-3/N-5/N-6/N-7 全绿 |
| ② | 今日案例回归：无输出 1GB 克隆形态在预算到点转后台而非冻结 600s | **PASS** | N-2（2s 预算 + 静默 python 等待：~2s 转后台、进程活、完成投递，11.4s）+ N-3 对照（同形命令无预算注入 = 事故路径精确复现：WARN `Foreground command idle for 4s … killing` → `[Command stalled]`，8.0s）+ N-5（收编后跨过 ceiling 判死点仍活，运行时日志 `Foreground no-progress ceiling retired — command adopted as background job`，10.4s） |
| ③ | 引擎既有命令族回归 | **PASS 19/19 + 全包 878/879** | `test-output-regression.log`（Foreground/AutoBackground/HardTimeout/PersistentBg 四套 19 绿：短命令前台、35s/65s 前台、显式 timeout 转后台、orphan 面、adopt/cancel 面、B1/B2 注入阈值面、persistent 豁免面）；另跑 `nebflow.core.tools.*` 全包 879 用例 = 878 绿 + 1 既有 skip、0 失败（含 ShellStuckDetector/ShellSessionRestart/ShellKillOnRestart/BashTool/BashActivityBridge 等，见 test-output-full-tools.log） |
| ④ | evidence 目录（现状测绘+新行为+对照） | **PASS** | 本目录：report.md + 3 份测试日志 |

格式化状态：scalafmtCheckAll 在 main 即全树飘红（79 文件，既有，root cause
533f43b34 新配置对全树的改排要求）——非本批回归。本批 5 个 main 文件经
scalafmtOnly 重排后干净且测试全绿；2 个 spec 文件因 scalafmt 3.8.6 对「括号包裹
for 推导式」补插 `end for` 与 scalac 对位规则冲突（4 处 misaligned end marker
编译错误）而撤回其重排（删除插入的 5 个 marker），fmt 态保持与全树一致，重跑
18/18 绿（详见 test-output-full-tools.log「格式化状态说明」）。

## 4. 生效方式

**全部改动属「重启即生效」，无热生效面。** 引擎为 Scala/JVM（sbt assembly →
`make install` → 重启 Nebflow 实例）。重启前运行中的实例继续按旧缺省
（60min 预算 / 600s 停滞杀）行为。

## 5. 未决项 / 已知情接受项

1. **收编后前台阶段输出不进详情卡**（既有缺口，转后台批已记录）：前台相位输出
   被 读线程 消费，`BgTaskOutputStore` 收编时才建——运行中详情卡显示 0 行；
   完成后 `background_job_id` 查询仍返回全量输出。
2. **B1 300s idle 提醒对收编任务照发**（只提醒不杀）——静默克隆转后台约 5min
   后会出现一条 `bg-slow` 审计事件/查询面 ⚠ 提示。属「只对后台任务提醒」的
   保留语义，符合裁定；如嫌吵可后续把收编任务的 idle 阈值单独调大（未做）。
3. 预算判据 = 时间 ⇒ `sleep 300` 这类长 sleep 也会在 120s 转后台（无害：完成
   照旧通知）。属裁定的直接推论，非缺陷。

## 6. 关键锚点（改动后）

- 预算取值：`Defaults.scala` `ForegroundWaitBudgetMs`（约 :189-227）；
  `BashResilienceConfig.foregroundWaitBudgetMs`（文件尾）。
- 预算消费：`BashTool.scala` `executeForeground`（`budgetMs = math.max(1L,
  ctx.bashConfig.foregroundWaitBudgetMs)`；`waitBudgetMs = explicitTimeoutMs.fold(budgetMs)(_.min(budgetMs))`）。
- 回执：`BashTool.scala` `autoBackground` yield 段（含查询/取消提示）。
- 收编退役：`shell.scala` `JobHealth.adoptedAsBackground` / `adoptAsBackgroundJob`
  置位行 / `foregroundNoProgressWatch` 旗检查分支。
- 配置链：`config.scala` `bashForegroundWaitBudgetMs` → `GatewayMain.scala`
  bashResilience 构造 → `SharedResources.scala:133` → `AgentSessionExecution.scala:1220`。
