# D12 delegate reset 零闸治理（b 方案：审计事件 + 派发面警示行）

状态: COMPLETED（实现 + spec 全绿；2026-10-23 收口，待合并）

任务: 微批 D12——delegate/subagent 就地腿 reset 零门槛治理。作者裁定走 b 方案（硬确认闸 a 方案否）：
1. 每次 reset 写审计事件进既有审计/事件日志（谁、目标会话 id、何时、目标当时状态/在跑任务）；
2. 派发面加一行警示：delegate 任务 brief 头部注明 reset 会丢弃未持久化的进行中工作，已完成并落盘的工作不受影响。

红线：设计零超时；最小改动；跟随仓库风格与测试框架；不重启引擎。

---

## 0. 背景与裁定链

- 案例⑦（delegate worktree 被外部 git reset 清盘）的三候选归因中 **T2 = auto-all 默认档 reset 系零闸**：`ToolReversibility.isReversible` 的 `AutoAll => true`（`src/main/scala/nebflow/core/permissions.scala:173-176`）无条件放行——`BashTool.FixedDangerousPatterns` 的「需用户批准」清单在启动默认档（auto-all，2026-09-12 作者令）是**死信**。就地腿 delegate 与根会话/人共享检出区座位 ⇒ 任意一方一条 reset 系命令即清掉他方**未持久化**的进行中工作，且案发前无任何留痕。
- 源报告：`reports/20261022_232018_delegate-arch-review/REPORT.md` §3 D12（P2；选项 a = 最小硬闸 / 选项 b = 只落审计 + brief 警示，两案都附带 brief 增补行）。作者裁定（本批任务书）：**a 否，走 b**。

## 1. 实现（三文件，最小改动）

### 1.1 审计事件（b 方案第 1 条）—— 新增 `src/main/scala/nebflow/core/tools/ResetAudit.scala`

- **审计面 = 既有审计 logger `nebflow.audit`**（`AgentControlTool.auditTeamAction` 同族单点——Team cancel/restart 审计先例，随 stand.log 落盘；零新存储面）。行式事件，每次 reset 至少一行：
  ```
  AUDIT ts=<millis> event=git-reset caller=<sid> callerAgent=<agent名> command="<首行截断160>" cwd="<会话座椅>" target=<sid|none> [targetKind=Delegate] [targetStatus=<status>] [targetTask="<任务摘要>"]
  ```
  四要素齐备：谁（caller+callerAgent）、目标会话 id（target）、何时（ts）、目标当时状态/在跑任务（targetStatus/targetTask）。
- **目标解析（best-effort）**：`DelegateRegistry`（持久 delegate 记录，含 cwd/worktree/title）× `agentRegistry` 活体键（`AgentKind.Delegate`）× 同检出区判定，逐目标一行；零目标 = 单行 `target=none`——reset 本身必须留痕，谁/何时/命令三条永不缺。
- **同检出区判据**（`sharesCheckout`，纯函数）：座位路径与 delegate 座位互含 = 同一工作树；**自带 worktree 的 delegate 只在调用者坐进同一 worktree 时算在场**（主检出区 reset 不进链接 worktree——git 隔离语义，防误报）。路径归一（反斜杠/尾分隔符）跨设备可比。
- **判别面**（`ResetPatterns`，与 BashTool 闸面同域、出入注明）：`git reset --hard` / `git checkout (--)? .` / `git clean -f` / `git branch -D <名>`。两处出入：① 裸 `checkout .` 闸面匹配不到、审计面覆盖（报告点名形态）；② `branch -D` 审计面要求带分支名、闸面只钉 main|master。已知**同隙**（两处都匹配不到，不单方面收紧）：`git -C <path> reset --hard`。
- **失败语义**（`RelayExecAudit` 同款）：审计写入/注册表读失败一律吞掉 + WARN，**绝不影响命令执行**；逐行 emit 隔离（单行失败不中断其余行）。**零超时设计**：不新增任何时间参数；解析面 = 一次小文件目录扫描，仅 reset 系命令触发（非 reset 命令零开销透传）。

### 1.2 BashTool 执行腿接线 —— `src/main/scala/nebflow/core/tools/BashTool.scala`（+12 行）

- 位置 = `doCall` 执行腿（interactive 检查之后、`ShellSession.forSession` 之前）：**权限闸之后、进程起跑之前**——到此处即「已批准将执行」（低档下危险命令在 `ToolReversibility` 已被闸面/确认卡处理；auto-all 直通），覆盖前台与 run_in_background 两条腿；bg 任务查询腿（`background_job_id`）不触发。

### 1.3 派发面警示行（b 方案第 2 条）—— `src/main/scala/nebflow/core/tools/DelegateTool.scala`（+18/-3 行）

- 新公开常量 `DelegateTool.ResetHazardLine`，进 `assembleBrief` 头部（工作座行之后、`BriefReportLine` 之前——单 turn 会话第一屏即见）：
  > [reset hazard] This checkout is shared with other live sessions (root, other delegates) — any party's `git reset --hard` / `checkout .` / `clean -f` / `branch -D` instantly discards every session's unpersisted in-progress work here (each one is written to the audit log). Work already committed or otherwise persisted to disk is unaffected. Never run reset-family commands in this workspace.
- 两要素（任务书原文）齐备：**未持久化的进行中工作会丢**（unpersisted in-progress work）＋**已完成并落盘的工作不受影响**（persisted to disk is unaffected），并注明已入审计日志。

## 2. 测试（munit，跟随仓库框架）

新增 `src/test/scala/nebflow/core/tools/ResetAuditSpec.scala`（14 例）：
- 判别面真值表：报告 D12 四式 + 实战旗标变体命中；安全 git 命令不命中；`git -C` 同隙钉。
- `sharesCheckout` 真值表：同目录/互含/异树；**worktree 隔离**（主检出区 reset 不波及 worktree 座位、调用者坐进同一 worktree 才算在场）；反斜杠归一。
- `affectedTargets`：live × 同检出区 × 非自身过滤 + id 稳序；座椅未知 ⇒ Nil。
- `line`：四要素字段逐项断言；`target=none` 兜底行；值引号包裹 + 转义 + 多行命令只取首行（单行不变量）。
- **审计事件写入本体**（注入式 emit 收集器）：有目标 = 逐目标一行含 status/task；零目标 = `target=none` 单行；无 runtime（REST/harness）= 降级单行；**emit 失败不上抛**（命令执行永不被审计闸住）。
- brief 警示行：装配在位 + 头部次序（workspace < hazard < report）+ 两要素 + 审计注记。

邻族回归（既有钉不受扰动）：`SleepPollStartupRuleSpec` 4/4（assembleBrief 布局钉）＋ `PromptFaceSplitSpec` 10/10（BriefReportLine 与提示词面分离钉）。

## 3. 结果

```
sbt> testOnly nebflow.core.tools.ResetAuditSpec nebflow.core.tools.SleepPollStartupRuleSpec nebflow.core.entity.PromptFaceSplitSpec
（终验读数见 §6 补记）
```

## 3.5 门禁对账（全仓既有红零回归）

- **check-scala-layers**：本 worktree 与**洁净 origin/main**（a5fb3e7b3 临时 worktree 复跑）失败集逐行相同（cli→gateway 反向边 ×N、BrowseGetCursor→dropbox、dropbox↔core 与 gateway↔cli 两个既有 SCC）——本批零新边（`ResetAudit` 的 core.tools→core.delegate / core→actor / core→shared 均为既有边，DelegateTool 先例同路）。
- **scalafmtCheckAll**：全仓 115 文件**既有漂移**（main 同红，含 BashTool/DelegateTool 两文件的既有区域）；本批四个文件的**新增 hunk 经 scalafmtOnly 验证 formatter-clean**（格式化输出对本批 hunk 字节不变），但为**最小改动红线**不携带同文件既有区域的格式 churn——已回退 scalafmt 对 BashTool/DelegateTool 既有区域的重排，交付 diff 保持字节最小。

## 4. 改动文件

| 文件 | 变更 |
|---|---|
| `src/main/scala/nebflow/core/tools/ResetAudit.scala` | 新增：reset 系命令审计事件（判别/目标解析/行组装/record 入口） |
| `src/main/scala/nebflow/core/tools/BashTool.scala` | +12 行：执行腿审计接线（权限闸后、进程起跑前） |
| `src/main/scala/nebflow/core/tools/DelegateTool.scala` | +18/-3 行：`ResetHazardLine` 常量 + assembleBrief 头部位形 + 布局注释同步 |
| `src/test/scala/nebflow/core/tools/ResetAuditSpec.scala` | 新增：14 例 spec（含审计事件写入本体） |

## 5. 已知边界与假设（如实登记）

1. **目标解析覆盖面 = live Delegate 会话**：Team 成员/SubTask/Ephemeral 的座位（cwd）无持久记录面（AgentRecord/SubAgentTask 均无 cwd 字段），本轮不解析——审计行本身（谁/何时/命令）对所有 reset 恒在，目标解析是 delegate 腿 best-effort 增强面。扩面需先给相应会话域落座位记录，另批。
2. **同检出区判定是路径启发**（字符串互含），非 `git rev-parse --show-toplevel` 级精确；共享同一检出区的两个**不嵌套**路径（如 `git worktree` 之外的绑定挂载）会漏判——现实座位形态（项目 workspace / worktree）下判据成立。
3. **`echo "git reset --hard"` 字符串字面会误报审计行**（与 BashTool 闸面既有误报行为一致，一致性优先）。
4. Shell 实际 cwd（会话内 `cd` 之后）不回读——审计行的 cwd 记**会话座椅**（`ctx.sessionCwd`），目标解析同源；子目录内 reset 与座椅同检出区，判据不受影响。
5. 旧口径缺口（auto-all 死信本身）**保留**——作者裁定 b 方案即「保留全放行语义」，本批只消灭「无人知晓」，不改闸面。

## 6. 终验补记

- `ResetAuditSpec` **14/14** ＋ `SleepPollStartupRuleSpec` 4/4 ＋ `PromptFaceSplitSpec` 10/10（终验 2026-10-23 10:33，27/27 全绿，最小 diff 定稿后复跑）。
- 邻族全量回归：`nebflow.core.tools.*` ＋ `nebflow.core.entity.*` **1050 passed / 1 skipped（既有）/ 0 failed**（2026-10-23 10:26，678s）。
- 编译：`sbt compile` / `Test / compile` PASS（worktree 隔离跑，未触碰生产实例；未重启引擎）。
- scalafmt / scala-layers 门禁对账见 §3.5（全仓既有红零回归）。
