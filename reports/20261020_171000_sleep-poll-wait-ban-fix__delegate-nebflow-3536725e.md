# 修复批：明令禁止 sleep/轮询等待 —— 根因分析与机制修复（A 等待屏障 / B sleep-poll 护栏 / C 启动铁律）

## 一、根因分析（747cd36a 实撞复盘）

**案例**：`delegate-nebflow-747cd36a`（Stand #620 微信绑定事务化硬化批，2026-10-20 10:58–16:19，5h21m）。
会话轨迹（`~/.nebflow/sessions/delegate-nebflow-747cd36a.json`，257 条）：

- `[139]` 以 `run_in_background: true` 启动 `sbt -batch test`（job 100df7d7，回执明示「完成会自动通知——继续别的工作或结束回合」）；
- `[141–170]` 正确地并行推进前端改动（这一段是教科书姿势）；
- `[171]` 起**原地等**：`sleep 120 && tail` → `[181]` `sleep 240` → `[187]` `sleep 420` → `[191]` 起定档 `sleep 570; echo wN` 心跳，一路 w1→w27，穿插 `ps aux | grep 49177` 探引擎/sbt JVM 活性——**纯轮询 5h16m**；
- `[256]` 止于第 27 拍心跳（引擎重启窗口被带走，5 小时等待全部蒸发）。

**根因链（两条腿叠加）**：

1. **交付链 + 历史语义推断**：其交付链要求「家族回归绿才交付」（brief 明令回归），后台回归跑完前不能交；而历史教训 #535「delegate 结束回合 = 会话终结 = 后台作业被带走」让「结束回合等通知」看起来会丢掉后台作业与完成通知——同步轮询成了表面唯一安全的姿势。修复批 A 段直接拆除这个前提：**结束回合不再终结会话**。
2. **LoopGuard 盲区**：`LoopGuard.scala`（`core/processor/LoopGuard.scala`）三条信号全部不命中——
   - S1 同参同败：心跳全是**成功**调用；
   - R-call 同参指纹（`identicalCallHard=10`）：fp = sha256(tool+args)，`echo wN` **每拍变参** ⇒ 指纹恒新，永不连续；
   - R-text 同文本：助手文本每拍不同。
   轮询等待在 exemptTools 设计里本属「轮询类豁免」（TaskQuery 族）的近邻，但 sleep 变参心跳是它抓不到的**多态循环**。修复批 B 段换判据：不看指纹，看**命令形态**。

## 二、三件机制修复

### A. 等待屏障（delegate wait barrier）

- **落点**：`src/main/scala/nebflow/agent/BackoffSupervisor.scala`
  - `apply(...)` 新增 `bgWaitCapMs` spec 注入缝（:74-79）+ setup 期 hold 状态三 Ref（:96-99：`bgWaitHeld` / `bgWaitCapFiber` / `bgWaitCapFired`）；
  - `active(...)` 新参四枚（:139-146）；Completed 分支先过 `delegateBgWaitGate`（:352-361）；
  - 屏障门 `delegateBgWaitGate`（:406-433）：判据 = `BgTaskRegistry.waitingFor(subagentId)`（`core/tools/BgTaskRegistry.scala:249`，与节点完成闸同一等待集权威——自有任务 sessionId ∪ 子代理任务 rootSessionId，persistent 服务型不在等待集）；
  - **hold**（`holdForBgWait` :299-318）：不报父、不停 child、任务库保持 running；单发 logger + `delegateBgWait` WS 帧；武装兜底计时器（`armBgWaitCap`，每等待期重臂）；
  - **唤醒**：后台完成回调（`BashTool.makeNotifyCallback` :1043-1048 → `ExternalEvent(source="background-task")` 注入仍活着的 child）→ 续跑轮 → 又一次 Completed → 门复检；等待集清空 ⇒ 释放终判（对齐 NodeStarter 桥 bg-wait 语义，`NodeStarter.scala:770-791`）。
  - **cap 兜底**（绝不悬挂成僵尸）：每等待期超 `bgWaitCapMs`（默认 `Defaults.BgGateWaitTimeoutMs` 2h，与节点闸同值）仍非空 ⇒ 置 `bgWaitCapFired` 闩 + 重发 Completed ⇒ 复检回合带诚实注记强制终判（`bgWaitCapNote` :267-272——写明未结任务与其通知将随会话终结丢失；fail-open 交付，绝不静默丢失）。已知边界：cap 触发恰逢唤醒轮在飞且唤醒轮又开了新等待任务时，本轮以注记交付（注记如实列出当时 pending 面）。
  - **respawn 作废旧 hold**（:521-524）：崩溃续跑后旧计时器/旧消息快照全部作废。
- **轨道面**：只挂 delegate 轨（`source == "delegate"`）；subtask 轨零变化（A-3 钉）。
- **开关**：`nebflow.delegate.bgWaitHold.enabled`（默认 **true**；现读 prop 可关回退）。
- **TaskStuckWatcher 互斥**：hold 期 registry 状态 = Idle，watcher 对 Idle 不判卡死（G7 既有判据，节点侧同哲学实证）。
- **spec**：`src/test/scala/nebflow/agent/DelegateBgWaitHoldSpec.scala`（4 测：A-1 747cd36a 复现——hold 不报父/不停 child、清空后唤醒轮放行；A-2 无作业立即终判回归；A-3 subtask 轨不扩散；A-4 cap 兜底强制终判带 `[delegate-bg-wait]` 注记）。

### B. sleep-poll 护栏（Bash 工具级介入）

- **落点**：
  - `src/main/scala/nebflow/core/tools/SleepPollGuard.scala`（新，纯函数核心）——检测正则 `SleepCommandRe`（数字后边界含 `;` 等 shell 分隔符——`sleep 570; echo wN` 现场原样形态）；会话级阶梯 `decide`：首犯 `WarnExecute`（放行 + 回执首部警告指导），第 N 次（默认 3）`Reject`（拒绝执行）；30min 冷却归零；开关/阈值/升级档 Config 注入缝。
  - `src/main/scala/nebflow/core/tools/BashTool.scala`——`sleepPollCounts` 会话计数表（:349-351）+ `sleepPollGate`（:353-376，计数落账 + 裁决分发）+ `escalateSleepPoll`（:378-393，`taskStuck` 帧 action=sleep-poll——既有 TERMINAL 帧族，main.js :2126 按 sessionId 标注子代理行，面板可见）+ 前台腿包裹（:659-661，只包本地前台；显式 `run_in_background` / remote-exec / `background_job_id` 查询腿豁免——后台是正道姿势）。
- **豁免面**：显式 run_in_background、persistent、查询/取消腿、≤60s 碎睡（`sleep 2 && flaky-test` 合法）。
- **开关/参数**：`nebflow.shell.sleepPollGuard.enabled`（默认 true）/ `nebflow.shell.sleepPollThresholdSec`（60，作者建议值）/ `nebflow.shell.sleepPollEscalateAfter`（3）。
- **spec**：`src/test/scala/nebflow/core/tools/SleepPollGuardSpec.scala`（10 测：747cd36a 心跳形态检测、多段取最大、子串不误判、阶梯 1→2→3、变参心跳同会话累计（与 LoopGuard 指纹语义互补的锚）、冷却、开关、文案三要素、生产默认值）。

### C. 启动语境明令（三启动面单一权威文本）

- **文本单点**：`src/main/scala/nebflow/shared/Defaults.scala` `NoSleepPollRule`（铁律三要素：禁止 sleep/轮询 → run_in_background + 结束回合、通知唤醒 → 作者门控事件（重启/批准）交付报告并结束回合）。
- **消费点三面**：
  1. **delegate brief**：`core/tools/DelegateTool.scala` `assembleBrief`（纯函数单点）——铁律置于任务正文之前（单 turn 会话第一屏即见）；`PromptFaceSplitSpec` 钉住的 `BriefReportLine` 逐字不动。
  2. **节点脚注**：`core/project/NodeEngineContract.scala` `protocolFootnoteFor`——铁律**前缀**注入，保住 `endsWith(ProtocolFootnote)` 身份断言（NodeChainAttributionSpec :324/:379）与末行措辞钉（TaskBoardInjectionSpec）；覆盖 Project 节点 + delegate 作用域链（ChainWrite/AgentFlow 步走同一 buildInput 面）。
  3. **subagent/workflow 步**：`core/tools/BlockingSubagent.scala` `promptWithRule`（首条消息前缀）——只读侦察腿无 Bash 纯提示成本（~60 token），workflow 执行步（defName=nebflow）是主对象。
- **spec**：`src/test/scala/nebflow/core/tools/SleepPollStartupRuleSpec.scala`（4 测：文本三要素、brief 装配序锚、脚注前缀+endsWith 身份、步包装前缀+原文保全）。

## 三、生效方式

引擎侧（BackoffSupervisor / BashTool / 注入文本均为引擎编译面）——**候重启窗生效**：本批合并进 main 并经引擎重启后，新 spawn 的 delegate/节点/subagent 会话携带 A/B/C 全部行为；重启前在飞会话不受影响（旧行为收尾）。

## 四、家族回归

| 家族 | 结果 |
|---|---|
| 新增三 spec（A/B/C） | 18/18 绿 |
| BackoffSupervisor 族（Cancel/RestartDelay/Nested/Team/StopHang/SubTaskStatus/CrashResume） | 全绿 |
| Bash 族（ToolSpec/Foreground/AutoBackground/Persistent/HardTimeout/ForegroundBudget/ActivityBridge） | 35/35 绿 |
| 节点完成闸族（CompletionGate/NodeBgCompletionGate G1-G9） | 73/73 绿 |
| 提示词面族（NodeChainAttribution/TaskBoardInjection/NodeSeedOutputContract/PromptFaceSplit） | 25/25 绿 |
| TaskStuckWatcher + Mail 续投族 | 15/15 绿 |

**已知预存失败（非本批引入，base 复跑同败）**：`BackoffSupervisorEmptyBurnSpec` 的 delegate 腿断言仍钉 #566 前的 `verdict: blocked` 判词行，而 #566 已退役 blocked（completed+注记）——属 #566 批遗留的 spec 未同步，与本批无关（已在 base commit 复跑确证同败）。

## 五、改动文件清单

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/shared/Defaults.scala` | +NoSleepPollRule / DelegateBgWaitHoldEnabled / SleepPollGuard 三 prop + 案例根因注记 |
| `src/main/scala/nebflow/agent/BackoffSupervisor.scala` | A 段等待屏障（门/hold/唤醒复检/cap 兜底/respawn 作废） |
| `src/main/scala/nebflow/core/tools/SleepPollGuard.scala` | 新文件（B 段纯函数核心） |
| `src/main/scala/nebflow/core/tools/BashTool.scala` | B 段接线（计数表/gate/面板升级帧/前台腿包裹） |
| `src/main/scala/nebflow/core/project/NodeEngineContract.scala` | C 段节点脚注前缀注入 |
| `src/main/scala/nebflow/core/tools/DelegateTool.scala` | C 段 brief 装配单点 `assembleBrief` |
| `src/main/scala/nebflow/core/tools/BlockingSubagent.scala` | C 段步首条消息包装 `promptWithRule` |
| `src/test/scala/.../SleepPollGuardSpec.scala` | 新（B，10 测） |
| `src/test/scala/.../DelegateBgWaitHoldSpec.scala` | 新（A，4 测） |
| `src/test/scala/.../SleepPollStartupRuleSpec.scala` | 新（C，4 测） |
