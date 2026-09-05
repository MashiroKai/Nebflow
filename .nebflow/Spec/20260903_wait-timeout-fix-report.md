# 实施报告：等待期超时修复（R1–R4）

- 日期：2026-09-03
- 实施人：Coder（Nebflow 执行管线）
- 审计依据：`~/.nebflow/docs/Nebflow/20260903_timeout-askuser-freeze-audit.md`（~/.nebflow commit 187178f，行号级证据 + 当日 116 条误报日志）
- 验收基准（作者裁定，最高）：①权限确认等待=无超时；②MCP 超时与普通工具统一；③AskUser pending/冻结/权限确认三种正常等待期零超时误报
- 交付形态：合并进本地 main（merge commit `4ba50fa0`，--no-ff）；**运行时生效需重建+重启宿主——本次严禁任何重启动作，未做**

---

## 1. 预检执行记录（冲突窗口）

| 项 | 结果 |
|----|------|
| 开工时 main 基线 | `34a986bd`，主仓工作区干净（无他人未提交改动） |
| result-delivery-fix（五向量，AgentActor/AgentCore 同域，关键等待项） | **已合并落 main**（`7f633b37`/`7418ce8f` 等），直接以合并后 main 为基线，无需等待 |
| collapse-keep-text（chat.js 域） | 未合并，但 `git diff --stat main...collapse-keep-text` 显示仅改 `turnGroup.js` 103 行——与本次目标文件域**零交集**，按协议记录不等待；后续该轨道以 `579f1fd4`/`2552fb57` 先落 main，合并时零冲突 |
| canvas-html-fix（viewers/tests 域） | 已合并落 main（`3c6f9864` 等），零交集 |
| sendbtn-green（input.css 域） | 已在 main（`18a50432`） |
| sbt 撞锁 / index.lock | 未发生 |

## 2. R1–R4 逐项：根因 → 修复 → 改动清单

### R1 权限等待去超时（裁定①）

- **根因**（审计 Q3a）：`AgentCore.scala:29` `PermissionTimeout = 5.minutes`；`:1232` `.timeoutTo(PermissionTimeout, IO.pure(None))` 到期自动拒绝 + `permissionExpired` 撤卡 + 超时错误文本注入 LLM。用户 5min01s 回答时卡已被撤——违反裁定①。前端本已同等对待 askUser/askPermission（都清杀 turn 计时器），后端只给权限 5min。
- **修复**：
  - 删除 `PermissionTimeout` 值与注释；权限等待提取为 `object AgentCore.awaitPermissionDecision(deferred): IO[Boolean] = deferred.get`（无超时，与 AskUser 的 `timeout=None` 同构），`askUserPermission` 调用点改用它；
  - 整个「case None => 超时分支」（permissionExpired 发射 + 超时错误文本）删除——permissionExpired 不再作为超时信号自动触发；用户主动拒绝（approved=false）路径照旧（#12 劝停计数语义不变，原「超时不计数」注释随超时消亡更新）；
  - InteractionHub 3 处「5-minute timeout」过时语义注释同步改写（文件头 hub 崩溃恢复预期、F4 fallback 注释、#12 malformed answer 注释）——hub 崩溃时 pending 等待丢失后 turn 无限期等待（与 AskUser 既有暴露一致），用户 Interrupt 是保证可达的退出。
- **「错过弹窗」覆盖面核对**：hub 既有 F4 fallback（`InteractionHub.handleRequest`，目标 root 不可达时扇出全部其他已注册 root + `fallback:true` 全局 toast，答案按 requestId 跨窗路由）+ AskUser 侧刚落地的刷新重放（ListPendingAsks）继续覆盖可达性；R1 后不依赖任何定时器。

### R2 WaitingForUser 状态接线

- **根因**（审计 Q1）：AskUser 派发与权限挂起期间 registry 状态残留 `Processing`、`lastActivityMs` 不再刷新；`AgentStatus.WaitingForUser`（protocol.scala:752）定义后全仓零使用；TaskStuckWatcher 30s 扫描必然命中——当日 116 条误报，其中 Delegate/Ephemeral/SubTask 走 Stop→硬取消破坏性链（问句永久失效）。
- **标记点**：
  - `AgentActor` AskUser 处理器（hub 存在分支，Request 发送前）：`touchRegistryActivity(..., WaitingForUser)`；
  - `AgentCore.askUserPermission`（无重复 pending 分支，sendPermissionRequest 前）：同上。
- **解除点（标记-解除成对，状态机闭环）**：
  1. **答案回填**：`AskUserQuestionTool.askUser` 的 `.?` 返回后 `restoreRegistryAfterAnswer(ctx)` → 恢复 `Processing` + 活动戳刷新（ watcher 窗口自此重启；比照 BashTool.touchAgentActivity 的 tool 侧 touch 先例；handleError 兜底，touch 失败不毁回答）；
  2. **权限决定落地**：`askUserPermission` 的 deferred 完成后 → 恢复 `Processing`；
  3. **用户取消**：`Interrupt` 处理器补 `touchRegistryActivity(..., Idle)`（顺带修复既有「打断后 registry 残留 busy」旧缺口）；`ResetSession`（processing 行为）同样补 Idle；
  4. **turn 正常收尾**：finishTurnCont → Idle（既有）。
- **真挂死兜底可达性论证**：等待态不是终态——上述每条解除路径都把 session 送回被扫描的 Processing/Idle；等待期间用户随时可 Interrupt（spec 有断言）；AgentControl cancel 走 supervisor/registry 移除（记录整行消失，无滞留）；hub 缺席时 ask 直接丢弃（不标注）。没有任何路径能永久滞留 WaitingForUser 而无人工出口。
- **TaskStuckWatcher 扫描排除**：scan 链显式追加 `.filter(status != WaitingForUser)`。**诚实说明**：该 filter 当前被既有 `status == Processing` 过滤行为学蕴含（AgentStatus 枚举：Idle/Processing/WaitingForUser/Frozen/Error，两集合不相交），行为修复由标注接线承担；此行作为显式守卫保留——未来若有人放宽扫描条件，不会无声地把「等人」重新纳入「卡死」。审计报告「一行改动消灭全部误报」的表述实为「标注接线 + 显式守卫」组合生效，已在代码注释与本报告中如实修正。
- **R4-b 归属说明**：审计 Q2-C（AskUser 挂在工具 deferred 上、冻结 gate 拦不到挂起 fiber → 冻结窗状态保持 Processing → watcher 误报放大）的危害即 watcher 误报，随 R2 标注自动消解；零 token 铁律由回答后续跑的 dispatch 边界冻结 gate（pipeLlmCall shadow，既有）保证；挂起本身按 D11 交互豁免语义不冻结（用户在场等回答，冻结等待违背该语义）。故 R4-b 无独立 gate 代码，由 R2 + 既有 gate 复检共同覆盖（已对照审计原文逐句核实）。

### R3 MCP 超时统一（裁定②）

- **根因**（审计 Q3b）：`McpClient.callTool` 一刀切 `.timeout(120.seconds)` 包住整个 tools/call——与内置工具「领域自治（Bash 无命令级超时/文件工具无限）+ 会话级兜底」语义完全不同；浏览器自动化/深度检索/长仿真 120s 必死，且 120s 内无活动桥接还会连带触发 stuck 误报。
- **修复**：
  - 删除 120s 硬顶；`McpClient` 构造新增 `callTimeout: Option[FiniteDuration] = None`，`callTool` 为 `callTimeout.fold(send)(send.timeout(_))`；
  - `tools/list` 的 30s 基础设施探测**保留不动**（审计明确保留项；spec 有源码级钉子防误删/防硬顶复活）；
  - **mcp.json 可选 per-server 超时字段设计**：`McpServerConfig` 新增 `timeoutMs: Option[Long] = None`（命名对齐既有 `stuckThresholdMs` 惯例：毫秒、camelCase；deriveDecoder 派生，存量配置零改动）。语义：**缺省/None = 无超时**（跑到返回，真卡死由 no-progress ceiling/TaskStuckWatcher 会话级兜底收口）；**配置 = 该 server 内所有工具受上限控制**，仅作用于 tools/call（不作用于 initialize/listTools），到限报错语义与现状一致（TimeoutException → wrapper `ToolError("Error: …")` → 下一轮 LLM）。示例：`{"command":"uvx","args":["foo"],"timeoutMs":45000}`。
  - `McpManager.connectServer` 接线：`cfg.timeoutMs.map(_.millis)` 传入 McpClient。

### R4 冻结漏网堵截

- **R4-a（前端，审计 Q2-A）**：`main.js` `agentFrozen` 处理器新增——清除 `msg.rootSessionId || msg.sessionId` 对应会话的 `sessionBusyTimeouts`。根因：子代理冻结只标记 bgAgent 条目 + applyLocalFreeze（CSS），root turn 挂在 outstandingSubagentResults 屏障上 busy 保持、零活动事件 → 10.5min（streamTimeoutMs 600s+30s）计时器到点 → `interrupt` 杀 root turn + 「响应超时」卡，冻结跨小时 100% 复现。修复比照既有 `frozen` 处理器的 F8/F4 契约；`agentResumed` 后由活动事件重武装（resumed → activity re-arms，既有契约，无代码改动）。
- **R4-b**：见 R2 节归属说明（由 R2 覆盖 + 既有 gate 复检，零额外 gate 代码）。

## 3. 改动清单（文件级）

| 文件 | 改动 |
|------|------|
| `src/main/scala/nebflow/agent/AgentCore.scala` | R1：删 PermissionTimeout；awaitPermissionDecision 提取；超时分支删除；R2 权限侧标注/解除 |
| `src/main/scala/nebflow/agent/AgentActor.scala` | R2：AskUser 处理器标注；Interrupt/ResetSession 补 Idle 解除 |
| `src/main/scala/nebflow/agent/InteractionHub.scala` | R1：3 处 5min 语义注释同步 |
| `src/main/scala/nebflow/core/tools/AskUserQuestionTool.scala` | R2：restoreRegistryAfterAnswer（答案回填解除） |
| `src/main/scala/nebflow/core/processor/TaskStuckWatcher.scala` | R2：WaitingForUser 显式排除（守卫） |
| `src/main/scala/nebflow/llm/config.scala` | R3：McpServerConfig.timeoutMs 字段 |
| `src/main/scala/nebflow/core/mcp/McpClient.scala` | R3：callTimeout 参数化，删 120s 硬顶 |
| `src/main/scala/nebflow/core/mcp/McpManager.scala` | R3：cfg.timeoutMs 接线 |
| `src/main/resources/web/js/main.js` | R4-a：agentFrozen 清 root 计时器 |
| `build.sbt` / `project/Dependencies.scala` | 测试基建：cats-effect-testkit（Test，TestControl 虚拟时钟） |
| 新增 spec ×4 Scala + ×1 Playwright | 见 §4 |

## 4. 时间模拟测试手法说明

- **零真实等待**：时间相关断言一律不吃真实时钟——
  - registry 侧：直接注入回拨的 `lastActivityMs`（now − threshold − 1000）+ 参数化 threshold 驱动单轮 `TaskStuckWatcher.scan`（沿用既有 TaskStuckWatcherSpec 先例）；
  - 等待原语侧：cats-effect **TestControl** 虚拟时钟——「权限等待跨 6min（> 被移除的 5min）」「MCP 工具跑 125s（> 旧 120s 硬顶）」「配 timeoutMs=1s 跑 5s 到限」均虚拟推进到毫秒级真实时间；
  - 端到端接线（真实 AgentActor+Hub+AskUserQuestionTool）：回答落地后以 secondGate 阻断第二轮 LLM 制造稳定观察窗，断言中间态恢复，不被 turn 秒完淹没。
- **既有 spec 只读不改**：新增 4 个 Scala spec 文件 + 1 个 Playwright spec 文件，既有 spec 零改动。

## 5. 验收逐条结果（五条 + 变异验红红绿证据）

新增 spec：`WaitTimeoutR2ScanSpec`（4 用例）、`WaitTimeoutR1PermissionSpec`（2）、`WaitTimeoutAskUserWiringSpec`（2）、`McpCallTimeoutSpec`（4）、`tests/wait-timeout-frozen-timer.spec.mjs`（3）——合计 15 用例全绿。

### 验收 1（AskUser 等待 11min+ 缩阈模拟：零误报/零重启/零取消）
- **绿**：wiring spec 主用例——派发后 registry=WaitingForUser、askUser 卡渲染；回拨超阈值后 scan **零广播零 Stop**、等待态原样保留；scan spec：Delegate/root/Team 三形态 WaitingForUser 超阈值均零动作。
- **验红（M1 扫描侧：还原扫描排除，让 scan 重新看见 WaitingForUser）**：
  ```
  ==> X WaitTimeoutR2ScanSpec.R2 验收1: Delegate WaitingForUser 超阈值挂起 → scan 零动作
      munit.ComparisonFailException: WaitingForUser 绝不触发 taskStuck 广播，got: List({...taskStuck...})
  ==> X ...root 会话... root 等待态绝不广播 taskStuck(attention)，got: List({...})
  ==> X ...Team... Team 等待态零广播，got: List({...})
  [error] Failed: Total 4, Failed 3, Errors 0, Passed 1   ← 唯一绿 = Processing 真卡死对照
  ```
  还原后 4/4 绿。**M4（标注侧：删 AgentActor.AskUser 标注）**：
  ```
  ==> X WaitTimeoutAskUserWiringSpec...java.lang.AssertionError: AskUser 派发后 registry 未标 WaitingForUser in time（两用例同红）
  [error] Failed: Total 2, Failed 2
  ```
  还原后 2/2 绿。

### 验收 2（权限卡挂起 6min+：卡仍在、不自动拒绝、不注入错误）
- **绿**：TestControl 虚拟时钟——等待跨虚拟 6min 观测点仍挂起（earlyAtSixMin=None）、迟到批准生效（result=true）；主动拒绝如实传播（false）。
- **验红（M2：还原 `.timeoutTo(5.minutes, IO.pure(false))` 语义）**：
  ```
  ==> X WaitTimeoutR1PermissionSpec.R1 验收2...
      assertEquals(earlyAtSixMin, None, "…若还原 5min 硬顶，此处为 Some(false)")
      Some( … -None +Some( …
  [error] Failed: Total 2, Failed 2, Errors 0, Passed 0
  ```
  还原后 2/2 绿。

### 验收 3（MCP 工具 >120s 缩阈模拟：未配上限正常完成；配置上限仍受控）
- **绿**：未配上限 + 虚拟 125s（>旧硬顶）→ 正常返回 "slow-but-fine"，虚拟耗时 ≥125s；配 timeoutMs=1s + 虚拟 5s → ≈1s 到限报错（elapsed ∈ [1s,5s)）；timeoutMs 解码三态（配置/缺省/存量兼容）全绿；源码钉子（30s 保留、120s 已删）绿。
- **验红（M3：还原 `.timeout(120.seconds)` 硬顶）**：
  ```
  ==> X McpCallTimeoutSpec.R3 验收3: 未配上限的 server，工具跑 125s…
      java.util.concurrent.TimeoutException: 120 seconds
  [error] Failed: Total 4, Failed 1, Errors 0, Passed 3   ← 配置上限用例正确保持绿
  ```
  还原后 4/4 绿。

### 验收 4（子代理冻结跨小时：root turn 不被杀、无「响应超时」卡）
- **绿**：Playwright spec T1——root busy 计时器武装 → agentFrozen 帧 → `sessionBusyTimeouts[root]` 被清除（计时器不存在则到点必不发 interrupt/不弹超时卡，因果链闭合）；T2——agentResumed 后活动事件重武装（F8/F4 契约保持）；T3——无关会话冻结不误清他人计时器、不产生幻影键。
- **验红（M5：删除 agentFrozen 清除块）**：
  ```
  2 failed
    › R4-a T1: 子代理 agentFrozen 清除 root 会话的杀 turn 计时器（冻结跨小时不被误杀）
    › R4-a T2: 冻结结束后活动事件重武装计时器…
  1 passed   ← T3 无关会话守卫正确保持绿
  ```
  还原后 3/3 绿。

### 验收 5（既有真卡死检测不回归 + R2 解除路径断言）
- 既有 `TaskStuckWatcherSpec` 全绿（全量 2148 内含）；新对照用例：同形记录 Processing → Stop+taskStuck 仍触发（M1 变异下该用例保持绿 = 变异只破坏等待态保护、不伤真卡死检测）；
- R2 解除路径断言：wiring spec 主用例——答案回填后恢复 **Processing** + 活动戳刷新（delta<10s）→ 后续模拟真卡死（回拨）→ scan **立即开火**（taskStuck 广播重现）= 「TaskStuckWatcher 重新覆盖该会话」；取消路径：pending 中 Interrupt → registry 回 Idle。

**变异汇总**：M1–M5 全部红-绿闭环，变异后均即时还原并以原用例复绿确认。

## 6. 既有真卡死检测回归证明

- 全量 sbt 2148 用例含 `TaskStuckWatcherSpec` 全绿（语义零回退）；
- 新增 Processing 对照用例显式钉住「非等待态仍触发」；
- 合并后主仓子集复跑含 TaskStuckWatcherSpec：绿（见 §8）。

## 7. 全量 sbt 结果（worktree 内，前台真实执行）

```
Passed: Total 2148, Failed 0, Errors 0, Passed 2148, Ignored 7
Total time: 481 s (0:08:01.0)
```
（sbt test 一次跑完；Ignored 7 为既有 @Ignore 存量）

## 8. 分支 commit 与 merge、合并后子集复跑

- 分支 commit：`9679810b`（R1–R4 全量修复 + 15 用例 + testkit 基建）
- 分支补并 main：`656e8c6a`（Merge branch 'main' into wait-timeout-fix，带入 canvas-html-fix/turn-collapse 等零交集改动）
- **merge commit（main，--no-ff）：`4ba50fa0`** —— 零冲突（ort 干净合并；与五向量在 AgentActor.scala 域无 hunk 交叠，逐文件核对无双方语义冲突）
- 合并后主仓子集复跑（新 spec ×4 + TaskStuckWatcherSpec + PermissionDenialMessageSpec + AskUserPendingInjectionSpec + InteractionHubSpec + LoopGuardWiringSpec + AgentLlmFailRetrySpec + AskUserQuestionToolSpec + AskUserBuildJsonSpec + 五向量 ×5）：
  - 首轮 88/89：`InteractionHubSpec` 一用例在 17 spec 组合批次下瞬时失败；
  - 该 spec 隔离复跑 15/15 绿；**同一子集批次整批复跑 89/89 全绿**——判定为组合批次瞬时干扰（时序），非合并回归（worktree 全量 2148 绿亦佐证）。
- 前端 Playwright（worktree 期执行，`--config` 指向 worktree tests）：3/3 绿。

## 9. 清理确认

- `git worktree remove .nebflow/worktrees/wait-timeout-fix` ✅（worktree list 零残留）
- `rm .nebflow/wait-timeout-fix` 软链 ✅
- `git branch -d wait-timeout-fix` ✅（曾指向 656e8c6a；--no-ff 已保全历史进 main）
- 临时文件 `/tmp/pw-wait-timeout.config.mjs` ✅ 已删
- 全程未 push、未动 origin；未碰宿主进程（PID 87216/端口 8080）；未 NodeCancel/abandon 任何节点

## 10. 生效说明

**本次改动已进本地 main，但运行时生效需重建（assembly/安装包）+ 重启宿主——按任务纪律本次严禁任何重启动作，未执行。** 宿主继续运行旧代码期间，等待期误报行为不变。

## 11. 遗留问题

1. **hub 崩溃时 pending 等待丢失**（AskUser/权限共同暴露，R1 后权限与 AskUser 一致化）：turn 无限期等待，依赖用户 Interrupt 退出；hub 自身有监督重启，只保障后续请求。可考虑 hub 恢复后 pending 快照重放（审计 R2「可选加固」中权限侧 ListPendingAsks 对称物）。
2. **扫描排除 filter 的行为冗余**：当前被 `status==Processing` 蕴含，作为显式守卫保留（§2 R2 节已论证）；若未来重写扫描条件，该守卫防止「等人」被重新纳入卡死。
3. **AskUser 回答落地的解除位于工具侧**（AskUserQuestionTool），依赖 `ctx.sharedResources/sessionId` 注入；harness 场景（两者缺省）自动 no-op——若未来出现绕过 ToolContext 的新 AskUser 入口，需记得补标注/解除对。
4. **审计 Q2-B（root 等子代理屏障的 Processing 长等待 → attention 噪声）未在本次范围**：R2 未改屏障等待的标注（root 等屏障是「等系统」不是「等用户」，不宜标 WaitingForUser）；维持 taskStuck attention 现状（不杀 root，仅噪声+面板标记），如需降噪可后续降频或引入专用状态。
5. **前端规格运行方式**：worktree 场景下 Playwright 需显式 `--config`（testDir 指向 worktree tests）；主仓 tests/ 下既有 spec 的运行入口未统一，后续可考虑沉淀一份 worktree 友好的 runner 说明。
