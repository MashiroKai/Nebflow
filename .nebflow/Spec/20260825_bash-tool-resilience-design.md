# Bash 工具层卡死防护方案设计（2026-08-25）

> 状态：设计稿 v1.0 · 待用户确认后派发
> 性质：阶段文档（冻结后不再修订，实施派发引用本文件）
> 关联裁定：08-19 用户裁定「bash 无自动超时、移除 autoBackgroundThreshold」→ 08-25 用户新裁定「5 分钟（300s）自动转后台」；本设计兼容两者（300s 转后台 ≠ 自动超时）
> 排队任务：#391（restart 联动 killProcessTree + 长 RUNNING 告警）

---

## 1. 现状分析（代码级证据）

### 1.1 Bash 执行链路

```
BashTool.call (BashTool.scala L315)
├─ background_job_id 查询/取消模式（L331-369）
├─ run_in_background=true → shell.executeBackground（L386-397）
├─ isRemoteExec=true → 同步 execute + 默认 30s timeout（L398-413）
└─ foreground → executeForeground（L414, L442-479）
     ├─ processTimeout = explicitTimeoutMs.getOrElse(365.days)（L450）
     ├─ startActivityBridge 活动桥接（L494-531）
     └─ shell.execute → runProcess（shell.scala L445-640）
          ├─ timeout watchdog：IO.sleep(timeout) → killProcessTree（L561-566）
          ├─ 前台 no-progress ceiling：10min 无输出且无 CPU → kill（L531-551）
          ├─ 后台 stuck 检测：30s 宽限 + 无输出 + CPU 2s 采样<10ms → kill（L574-601）
          └─ poll-based readStream（L821-876）：不阻塞读，进程退出+250ms 宽限收尾
```

安全网现状汇总：

| 安全网 | 适用范围 | 触发条件 | 动作 |
|---|---|---|---|
| timeout watchdog | 前台+后台 | 显式 timeout 到点 | killProcessTree |
| no-progress ceiling | 前台 | 10min 无输出**且**无 CPU | killProcessTree |
| 后台 stuck 检测 | 后台 | 30s 宽限后无输出 + CPU 2s 采样 <10ms | killProcessTree |
| 后台 idle timeout | 后台 | 无输出 300s（BgIdleTimeoutSec） | killProcessTree + TimeoutException |

### 1.2 B9 卡死盲区（两次实证：3.7h / 42min）

**盲区 1：前台无超时。** `executeForeground` L450：无显式 timeout → `365.days`。B9 的 Chrome 命令未带 timeout 参数，理论最长可跑一年。

**盲区 2：no-progress ceiling 被 CPU 活动绕过。** shell.scala L540：`lines > lastLines || (cpu - lastCpu) >= CpuActiveThresholdNanos`（10ms/30s）→ 有 CPU 活动即重置窗口。Chrome headless 渲染海外站挂起时持续消耗 CPU（网络重试 / TLS / 渲染循环），ceiling 永不触发。

**盲区 3：活动桥接把 CPU 微增长当「进展」。** BashTool L527-529：
```scala
val hasProgress = alive && (lines > lastLines || cpu > lastCpu || sleepLike)
```
`cpu > lastCpu` **无 10ms 阈值**——Chrome 哪怕 30s 内烧 1ms CPU 也算「有进展」→ `touchAgentActivity` 刷新 `lastActivityMs` → TaskStuckWatcher（判定：Processing + lastActivityMs > 10min，TaskStuckWatcher.scala L119）永远判「不卡死」→ 零告警。

**盲区 4：AgentControl restart 不杀 Bash 子进程树（B9 残留根因）。** 代码路径：
1. `AgentControlTool.doRestart`（L366-407）→ `rec.ref ! AgentCommand.Stop`
2. AgentActor Stop handler（L519/1050/1576/2962）→ `ctx.cancelCurrentTurn()`
3. `LocalActorContext.cancelCurrentTurn`（ActorContext.scala L87-90）——**只取消 turn fiber**（cats-effect Fiber），不碰 OS 进程
4. shell.scala 全程 `IO.blocking`（L452/477/489/501）——**IO.blocking 取消不中断线程**，fiber 取消后 runProcess 继续跑；bracket release（L638-640 `ProcessTree.killProcessTree(proc)`）要等 IO.blocking 返回才执行 → 永不执行

→ bash + Chrome + helpers 进程树残留，需手动 pkill。TaskStuckWatcher 的 Stop/硬取消只救 turn，不救进程。

**盲区 5：后台无兜底总时长。** 后台任务 `execute(command, 365.days, ...)`（shell.scala L653）无总时长上限；idle timeout（L769）只看 `health.lastActivityMs`（**输出行时刷新**，L484/494）——**持续输出**的任务（curl 进度、Chrome 日志、tail -f）永不 idle，可无限跑。

**代理事实**：127.0.0.1:7890 是 **macOS 系统代理**（`scutil --proxy` 确认：HTTP/HTTPS/SOCKS 均指向，Clash 默认端口），非 Nebflow 代码配置（代码库无 7890 硬编码；`env` 无 http_proxy 系列变量）。Chrome headless 默认跟随系统代理 → Clash 挂 → 海外站渲染挂起。curl 不读系统代理 → `curl --max-time 15` 15s 直连完成，随后 `;` 后的 Chrome 挂起——与 B9 时间线吻合。

### 1.3 后台任务结果注入机制（现状，机制 A 复用）

- `executeBackground`（shell.scala L113-144）→ `BackgroundJob`（fiber + deferred + health）
- 完成 → `backgroundExecute`（L642-680）→ `on_complete` = `makeNotifyCallback`（BashTool L614-684）：
  1. `AgentCommand.ExternalEvent(source="background-task", eventType="completed"/"failed")` → agent（L655-660）
  2. WS `backgroundTaskUpdate` → 前端指示器（L663-673）
  3. `BgTaskRegistry.unregister`（L678）
- 查询：`background_job_id` → `getBackgroundResult`（消费后移除）/ `getBackgroundJobHealth`（含 idleMs > BgStuckThresholdSec=600s 的 stuck 标记，BashTool L345-351）
- 前端通道已就绪：`taskStuck`（flowAgentPopup.js L836-843 显示 stuck/restart）、`backgroundTaskUpdate`（main.js L2226）

### 1.4 auto-background 历史实现（#319 移除前，可直接恢复）

git `bd9a9bb8`（2026-08-19 移除）显示旧实现：
- `executeForegroundWithAutoBackground`：threshold（Nebula 30s / worker 300s，`autoBackgroundThresholdMs(depth)`）后**不杀进程**，`shell.registerBackgroundJob(autoBgJobId, commandFiber, ...)` 注册（shell.scala L198-223 **该钩子至今保留未删**）→ 返回 `"[Command moved to background]"` → 完成时 `makeNotifyCallback` 通知。
- 08-19 用户裁定移除（「系统猜命令要跑多久不合理」）；08-25 新裁定恢复，统一 300s。

---

## 2. 机制 A：5 分钟（300s）自动转后台（用户 08-25 裁定）

### 2.1 触发条件

| 条件 | 值 | 说明 |
|---|---|---|
| 命令来源 | foreground 分支 | 非 `run_in_background=true`、无 `background_job_id`、非 `isRemoteExec` |
| 运行时长 | ≥ `BashAutoBackgroundMs`（默认 300_000ms，可配） | 纯时间阈值，**不看输出/CPU**（用户裁定精神：不猜命令性质） |
| 显式 timeout | 先到者生效 | `timeout < 300s` → 显式 timeout 杀；`timeout ≥ 300s` 或 None → 300s 转后台 |

### 2.2 转后台语义

1. **不杀进程**：进程继续跑，fiber 不取消
2. **turn 释放**：`BashTool.call` 返回占位消息 `"[Command moved to background] Job ID: xxx\n已运行超过 300s，已转后台，完成时通知"` → LLM 拿到 tool_result 继续/结束 turn
3. **注册**：`shell.registerBackgroundJob(jobId, commandFiber, command, health)`（恢复 #319 前骨架）——后台任务 map 接管，继承心跳 + idle 检测 + 查询/取消能力
4. **完成异步注入**：`makeNotifyCallback` → ExternalEvent + WS + BgTaskRegistry（§1.3 链路复用）
5. **前端**：`emitBgTaskStarted` 发 `backgroundTaskUpdate(status="running")`，指示器出现

### 2.3 与显式 run_in_background 的优先级/交互

- 显式 `run_in_background=true` → 走 `executeBackground` 分支，**不经过** auto-background 判定（现状保持）
- auto-background 只作用于 foreground 分支，两者互斥
- 转后台后**与显式后台任务完全同构**：可 `background_job_id` 查询、`cancel_background_job` 取消、心跳告警、30min 停滞兜底（机制 B）全覆盖

### 2.4 sleep 类命令

统一时间阈值，`sleep 500` 前台也会转后台（完成时通知）。sleep-like 排除仅保留在 no-progress ceiling / idle timeout 语义中（防误杀），不豁免转后台。

### 2.5 失败路径

| 失败 | 处理 |
|---|---|
| 300s 恰好进程退出 | 正常返回结果（竞态：以 execute 返回为准，不转后台） |
| registerBackgroundJob 失败 | 返回错误，进程已无法管理（防御性 catch；正常不会发生） |
| **完成时 agent 不可达**（已 restart/stop） | ExternalEvent 投递静默丢弃 → 结果丢失。**缓解**：转后台命令完成时检查 agent 在线状态（agentRegistry Processing/Idle），不在线则结果保留在 BgTaskRegistry（不注销）+ WS 仍推送；agent 重启后可用 `background_job_id` 查询。**根治**：restart 联动（机制 E）会把该 session 全部 shell 进程杀掉，不存在「重启后孤儿结果」 |

### 2.6 验收断言（机制 A）

- [ ] A-1 `sleep 320` 前台 → 300s 返回含 `"[Command moved to background]"` + jobId；进程不杀（`ps` 可见 sleep）
- [ ] A-2 转后台命令完成后（sleep 320 自然结束）→ agent 收到 ExternalEvent(completed)；WS 收到 backgroundTaskUpdate(completed)；BgTaskRegistry 注销
- [ ] A-3 显式 `timeout: 2000` + `sleep 10` → 2s 超时杀，返回 timed out（不转后台）
- [ ] A-4 显式 `run_in_background=true` → 直接返回 `[Background job started]`，不经 auto-background
- [ ] A-5 `isRemoteExec=true` → 同步返回，不转后台
- [ ] A-6 阈值可配（`BashAutoBackgroundMs`），测试注入 3s → `sleep 5` 3s 转后台、5s 完成通知

---

## 3. 机制 B：后台兜底超时 + 停滞检测（30min，可配）

### 3.1 现状 gap

- 后台 idle timeout（300s）只看**输出**：持续输出的任务永不触发（§1.2 盲区 5）
- 后台 stuck 检测（30s 宽限）只覆盖「启动后就没输出」类
- **无「输出+CPU 双维度停滞」的长期观察**——卡死但持续吐日志/烧 CPU 的后台命令可无限跑

### 3.2 停滞定义（统一）

**停滞** = 输出零增长 **且** CPU 零消耗（2s 采样窗口增量 < `CpuActiveThresholdNanos`=10ms）连续 ≥ `BashStuckWindowSec`（默认 120s）。

### 3.3 兜底规则

| 规则 | 触发条件 | 动作 |
|---|---|---|
| B2a 硬超时 + 停滞 | `runningMs > BashBackgroundHardTimeoutMs`（默认 30min）且停滞（连续 2min 零输出零 CPU） | killProcessTree + TimeoutException 报错 + WS 告警 |
| B2b 停滞观察期 | `runningMs > 30min` 后持续采样（30s 间隔，对齐前台 ceiling） | 有进展（输出/CPU ≥ 阈值）重置停滞计数；**总时长不重置**（30min 是允许运行总时间的硬顶，之后必须持续证明活着） |
| B1 idle timeout CPU 豁免 | 后台 idle 判定（300s 无输出） | **加 CPU 豁免**：idle 判定期间 CPU 有消耗 → 不算 idle，不杀（避免误杀 CPU 忙的合法任务；配合 B2a 兜底防无限跑） |

阈值可配：`BashBackgroundHardTimeoutMs`（默认 30min）、`BashStuckWindowSec`（默认 120s）、`BgIdleTimeoutSec`（现状 300s，已有）。配置走 nebflow.json 顶层键（沿用 `stuckThresholdMs` 先例，llm/config.scala L154-155）。

### 3.4 与现有机制的关系

- 30min 前的停滞：idle timeout（输出维度，300s）管；B1 豁免后 CPU 忙任务由 B2a 在 30min 后兜底
- B2 本质 = 前台 no-progress ceiling（shell.scala L531-551）逻辑扩展到后台 + 30min 起点 + 可配阈值

### 3.5 失败路径

| 失败 | 处理 |
|---|---|
| 杀进程时进程已退出 | killProcessTree 幂等（ProcessTree 内部 try/catch） |
| 阈值误配（过小导致合法长任务被杀） | 显式 run_in_background 的超长任务（如 40min sbt 编译）需用户调大 `BashBackgroundHardTimeoutMs`；文档注明默认值依据 |
| WS 告警失败 | 日志兜底（现有 handleErrorWith 模式） |

### 3.6 验收断言（机制 B）

- [ ] B-1 后台 `sleep 3600`（无输出无 CPU）→ 注入硬超时阈值（如 10s）→ killProcessTree + 报错 `[Background command timed out]` + WS 告警
- [ ] B-2 后台 `python3 -c 'while True: pass'`（无输出 CPU 忙）→ idle timeout（300s）**不杀**（B1 豁免）→ 30min 后停滞观察：CPU 忙 → 不杀（符合双条件裁定）
- [ ] B-3 后台命令输出持续（`yes`）→ idle timeout 不触发；`yes` 停掉后（如 `yes | head -c 100` 先吐后停）→ 停滞窗口到点 → 杀
- [ ] B-4 阈值可配注入验证（`BashBackgroundHardTimeoutMs` / `BashStuckWindowSec` 单测注入）

---

## 4. 机制 C：网络类命令识别 + 代理状态检测

### 4.1 触发条件

**低阈值转后台**：网络类命令（正则识别）→ `BashNetworkAutoBackgroundMs`（默认 30s，可配）转后台（复用机制 A 的转后台管线，仅阈值不同）。

识别正则（一期，可扩展）：
```
\b(curl|wget|ftp)\b
(chrome|chromium|google-chrome).*(--headless|--screenshot|--dump-dom)
--virtual-time-budget
(node|python|python3).*(fetch|requests|urllib)   # 可选，防误报先不加
```
命中即网络类；**不命中不猜**（保持 08-19「不猜命令」精神——只对明确网络命令给低阈值）。

### 4.2 代理状态检测

- 检测点：BashTool 执行网络类命令**之前**，一次性探测（上限 500ms，失败跳过不阻塞）
- 检测源（跨平台）：
  1. 环境变量 `http_proxy` / `https_proxy` / `all_proxy`（有则解析 host:port）
  2. macOS `scutil --proxy`（HTTP/HTTPS/SOCKS；B9 现场即此路径，127.0.0.1:7890）
- 检测方法：TCP 连接探测（`java.net.Socket.connect(host, port, 500ms)`）
- **判定**：代理配置存在但不可达 → 视为「代理挂」

### 4.3 行为（代理挂时）

| 命令类型 | 行为 |
|---|---|
| 网络类 + 代理挂 | 返回前缀提示 `⚠ 系统代理 127.0.0.1:7890 不可达——网络类命令可能挂起` + **30s 低阈值转后台**（不直接拒绝——不猜命令是否会挂，转后台兜底 + 提示） |
| 网络类 + 代理正常 | 无提示，30s 低阈值转后台（正常低阈值语义） |
| Chrome 截图类 + 代理挂 | 同上（提示 + 转后台；**不拒绝**——代理挂不一定必挂，直连/本地 URL 仍可成功） |

设计决策：**不直接拒绝**截图类命令（拒绝 = 猜命令会失败，违背 08-19 裁定精神）；提示 + 低阈值转后台 + 机制 B 停滞兜底构成完整防护链。

### 4.4 失败路径

| 失败 | 处理 |
|---|---|
| 代理探测超时/异常 | 跳过检测，按无代理处理（网络类仍 30s 转后台） |
| 正则误命中（如本地 `curl localhost`） | 30s 转后台（无害——本地命令通常 < 30s；超过即转后台，语义正确） |
| 代理配置读取失败（非 macOS/无环境变量） | 无代理配置 → 不检测，仅低阈值转后台 |

### 4.5 验收断言（机制 C）

- [ ] C-1 `curl http://example.com`（命令注入代理不可达状态）→ 返回含 `代理 ... 不可达` 提示 + 30s（可配注入）转后台
- [ ] C-2 `chrome --headless --screenshot ... https://xxx` → 识别为网络类 → 30s 转后台
- [ ] C-3 `sleep 320`（非网络类）→ 不命中低阈值，走 300s 常规转后台
- [ ] C-4 代理可达 → 无提示，正常执行/转后台
- [ ] C-5 代理探测函数可注入（单测直接喂不可达/可达/无配置三态）

---

## 5. 机制 D：活动桥接改进——真实活动判断

### 5.1 现状问题

BashTool L528：`cpu > lastCpu` 无阈值——卡死进程哪怕 30s 烧 1ms CPU 也算「有进展」→ 持续 touch lastActivityMs → TaskStuckWatcher 判不了（§1.2 盲区 3）。

### 5.2 改进

```scala
// 现状：hasProgress = alive && (lines > lastLines || cpu > lastCpu || sleepLike)
// 改进：CPU 对齐 CpuActiveThresholdNanos（10ms），与 no-progress ceiling 同标准
val cpuActive = (cpu - lastCpu) >= CpuActiveThresholdNanos
val hasProgress = alive && (lines > lastLines || cpuActive || sleepLike)
```

- 输出行数增长：保持（有输出 = 有活动）
- CPU 增量 ≥ 10ms/30s 采样：算活动（对齐 shell.scala L540 前台 ceiling 标准）
- sleep-like：保持豁免
- **效果**：Chrome 卡死（CPU 微消耗 < 10ms/30s）不再 touch → watcher 可判卡死（10min）→ Stop/restart 恢复 + 机制 E 杀进程树。真正合法长任务（build/test：CPU 忙 >> 10ms/30s）仍豁免误杀。

### 5.3 与机制 A 的协同

300s 自动转后台后，前台卡死命令最迟 300s 转后台（不占 turn）；活动桥接改进保住的是**转后台前的 300s 窗口**内 watcher 的可判性 + 长 RUNNING 告警（机制 F）的准确性。

### 5.4 验收断言（机制 D）

- [ ] D-1 伪造 JobHealth：输出零增长 + CPU 增量 1ms/30s → 不 touch lastActivityMs（agentRegistry 时间戳不变）
- [ ] D-2 伪造 JobHealth：CPU 增量 100ms/30s → touch（合法长任务不受影响）
- [ ] D-3 TaskStuckWatcherSpec 回归（12/12 绿，语义不变：有进展不判卡死）

---

## 6. 机制 E：restart 联动 killProcessTree（#391 第一部分）

### 6.1 现状

AgentControl restart → Stop → `cancelCurrentTurn` 只取消 fiber → IO.blocking 不中断 → OS 进程树残留（§1.2 盲区 4）。

### 6.2 设计

**ShellSession 增加活动进程注册表**：

```
ShellSession.activeProcesses: Ref[IO, Set[Process]]  // runProcess 启动时注册，退出时注销
```

- `runProcess`（shell.scala L463 bracket 内）注册 proc；bracket release 注销（进程已死）
- 前台进程 + 后台任务进程**都注册**（后台任务本就在 session 的 backgroundJobs map，前台进程是缺失项）

**restart/Stop 联动**：

```
AgentActor Stop handler（restart 场景）:
  cancelCurrentTurn()          // 现有：取消 turn fiber
  *> ShellSession.killActiveProcesses(sessionId)   // 新增：杀该 session 全部 OS 进程树
  *> 后台任务：遍历 backgroundJobs → ProcessTree.killProcessTree + deferred.complete(InterruptedException)
  *> BgTaskRegistry 注销该 session 全部 job + WS backgroundTaskUpdate(status="cancelled")
```

**范围**：restart 杀**该 session 全部** shell 进程（前台 + 后台）——session 重建后 backgroundJobs map 丢失，残留后台任务即孤儿；全杀 + 注销是最干净的语义。**不碰**：其他 session 的进程、JVM 自身（ProcessTree 只操作注册的 ProcessHandle）。

**复用**：`ProcessTree.killProcessTree` 已公共（core/util/ProcessTree.scala L38），幂等 + 三阶段（SIGTERM→5s→SIGKILL→2s→补刀）。

### 6.3 失败路径

| 失败 | 处理 |
|---|---|
| session 已销毁/不存在 | killActiveProcesses 幂等 no-op |
| 杀进程时进程已退出 | killProcessTree 幂等 |
| 后台任务 deferred 已 complete | attempt.void 吞掉 |
| 杀树超时（僵尸不可杀） | killProcessTree 内部三阶段已尽力；残留由 OS 回收（父进程死 → reparent → 无主进程最终 SIGKILL 覆盖） |

### 6.4 验收断言（机制 E）

- [ ] E-1 前台 `sleep 120 & wait` 挂起 → AgentControl restart → `ps` 断言 bash + sleep 进程树**全部消失**
- [ ] E-2 后台 `run_in_background` 的 `sleep 300` + 前台卡死命令 → restart → 两者进程树都消失；BgTaskRegistry 无该 session job；WS 收到 cancelled
- [ ] E-3 正常完成的前台命令（无卡死）→ restart 不影响（无残留进程可杀）
- [ ] E-4 其他 session 的后台任务不受本 session restart 影响（隔离性）

---

## 7. 机制 F：长 RUNNING 告警（#391 第二部分，WS 推 Sub-Agents 面板）

### 7.1 现状

`startActivityBridge`（BashTool L519-525）：前台 RUNNING 日志 10min 后 WARN（`elapsed >= 600`）——**仅日志**，无 WS 推送；后台心跳（shell.scala L721-727）idle 超阈值仅日志。

### 7.2 设计

| 事件 | 触发 | 推送 |
|---|---|---|
| 长 RUNNING（前台/转后台后） | 运行 > `BashLongRunningWarnMs`（默认 10min，可配） | WS `bashLongRunning`（sessionId + 命令摘要 + runningMs）+ 日志 WARN（现状保留） |
| 后台停滞 | idle > `BgStuckThresholdSec`=600s（现状已有标记，BashTool L345-351） | 复用 `taskStuck(action="attention")` 通道或并入 bashLongRunning |

前端：`flowAgentPopup.js` 已有 `taskStuck` 处理（L836-843，显示 stuck 状态 + restart 计数）——新增 `bashLongRunning` 监听或直接复用 taskStuck 通道显示「Bash 长运行」。WS 白名单：ws.js L150-153 需登记新事件类型。

### 7.3 验收断言（机制 F）

- [ ] F-1 前台命令运行 > 10min（阈值可配注入 5s）→ WS 收到 `bashLongRunning`；前端 Sub-Agents 面板显示长运行状态
- [ ] F-2 后台任务 idle > 600s → 前端可见停滞标记（现状已有，验证 WS 链路完整）
- [ ] F-3 事件不重复刷屏（每个 jobId 告警一次，去重）

---

## 8. 边界与交互

| 机制 | 边界 | 处理 |
|---|---|---|
| 冻结（FreezeScheduler） | 冻结只拦 LLM dispatch（frozen behavior），**工具执行不受冻结影响**（用户背景确认） | 转后台命令在冻结期间继续跑、完成通知照发。**风险点**：ExternalEvent 注入走 gated dispatch——需确认冻结时 ExternalEvent 不被 gate（实现时验证 AgentActor dispatch 路径；若被 gate，转后台完成通知在冻结期排队，解冻后注入，语义可接受） |
| 压缩（save turn） | 压缩在 turn 边界 | 转后台完成 ExternalEvent 与压缩并发 → 事件进 pendingEvents（protocol.scala L729）→ 压缩后按序处理；结果内容走 ToolResultGuard（maxResultSizeChars） |
| 并发 gate | LlmMaxConcurrency | 转后台不新增 LLM 调用；完成通知注入触发新 turn 时走 gate 排队（与现状 ExternalEvent 行为一致） |
| barrier | 只统计 Delegate/SubTask slot | Bash 转后台不产生 barrier slot；完成通知 ExternalEvent(source="background-task") 不递减 barrier。agent finishTurn 后 ExternalEvent 驱动新 turn（gated dispatch），结果注入 |
| remote-exec | 调用方管理生命周期 | isRemoteExec 分支保持同步执行（30s 默认 timeout），机制 A/C 均不适用（代码已有注释 L398-402） |
| 进程安全 | 不能杀 nebflow 自身 | 机制 E 只杀 ShellSession 注册的进程；BashTool DangerousPatterns 已拦 pkill/killall/kill 命令（L124-126）；ProcessTree 操作对象是子进程 ProcessHandle |
| 阈值配置 | nebflow.json | 新键：`bashAutoBackgroundMs`(300s)、`bashNetworkAutoBackgroundMs`(30s)、`bashBackgroundHardTimeoutMs`(30min)、`bashStuckWindowSec`(120s)、`bashLongRunningWarnMs`(10min)；沿用 `stuckThresholdMs`/`bgIdleTimeoutSec` 先例解析模式 |

---

## 9. 测试计划

### 9.1 单元测试（新增/更新）

| 测试 | 覆盖 | 方法 |
|---|---|---|
| BashAutoBackgroundSpec | A-1..A-6 | 阈值注入（3s），`sleep 5` 实跑转后台 + 完成通知断言；显式 timeout 优先；remote-exec 排除 |
| BashBackgroundHardTimeoutSpec | B-1..B-4 | 阈值注入（10s），`sleep 3600` 后台杀；CPU 忙任务 idle 豁免；停滞窗口注入 |
| BashNetworkClassifySpec | C-1..C-5 | 正则识别 + 代理探测函数注入（三态） |
| BashActivityBridgeSpec | D-1..D-3 | 伪造 JobHealth（CPU 增量 <10ms 不 touch；>10ms touch）；TaskStuckWatcherSpec 回归 12/12 |
| ShellKillOnRestartSpec | E-1..E-4 | 真起进程树 + `killActiveProcesses` → `ps` 断言消失；session 隔离 |
| BashLongRunningAlertSpec | F-1..F-3 | 阈值注入，WS 事件断言；去重 |

更新：`BashToolForegroundSpec`（L39-46 的 `sleep 35` 用例——35s < 300s 仍不转后台，**阈值注入 3s 时该用例需改用注入值 > 35s 或重构**；L30-36 断言 `!contains("moved to background")` 在注入小阈值时需按用例区分）。

### 9.2 E2E 测试

**冒烟（硬性条件，真实启动）**：
- 真实启动（sbt run 或测试 harness）→ Bash 全链路：`sleep 6`（注入阈值 5s）→ 转后台消息 → 完成后 ExternalEvent 注入 agent → 前端 backgroundTaskUpdate 指示器出现/消失

**卡死终止 E2E**：
- 后台命令 `while true; do :; done`（CPU 忙无输出，注入硬超时 15s）→ killProcessTree + 报错 + WS 告警 → `ps` 断言无残留

**restart 清理 E2E**：
- 真实 sub-agent（SubTask）→ 前台 `sleep 120 & wait` 挂起 → AgentControl restart → supervisor 重启 → `ps` 断言 bash/sleep 进程树消失 → agent 从 checkpoint 续跑

### 9.3 验收总纲（冒烟优先）

- [ ] **冒烟**：真实启动 + 转后台全链路（见 §9.2 第一项）——不通过则其余全部无效
- [ ] 全部单元测试绿（含 TaskStuckWatcherSpec / ShellStuckDetectorSpec 回归）
- [ ] E2E 三场景全过
- [ ] 阈值全部可配（nebflow.json 注入生效）

---

## 10. 涉及文件清单（实施参考）

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/core/tools/BashTool.scala` | executeForeground 加 auto-background 判定（L442-479 重构回 executeForegroundWithAutoBackground 骨架）；网络命令识别 + 代理探测注入（L315-425）；活动桥接 CPU 阈值（L528）；长 RUNNING WS 告警（L519-525） |
| `src/main/scala/nebflow/core/tools/shell.scala` | activeProcesses 注册表（runProcess L463）；killActiveProcesses；后台硬超时 + 停滞观察（startJobHealthCheck L733-797 扩展）；idle timeout CPU 豁免（L769） |
| `src/main/scala/nebflow/agent/AgentActor.scala` | Stop handler（L519/1050/1576/2962）追加 killActiveProcesses + 后台任务清理 + BgTaskRegistry 注销 |
| `src/main/scala/nebflow/shared/Defaults.scala` | 新阈值常量（§8） |
| `src/main/scala/nebflow/llm/config.scala` | nebflow.json 新键解析（沿用 stuckThresholdMs 模式 L154-155） |
| `src/main/resources/web/js/ws.js` | 白名单登记 `bashLongRunning`（L150-153） |
| `src/main/resources/web/js/flowAgentPopup.js` | `bashLongRunning` 监听（L836-843 旁） |
| 测试 | 新增 §9.1 各 Spec；更新 BashToolForegroundSpec |

## 11. 实施顺序建议

1. 机制 E（restart 杀进程树）——最小改动、最痛（B9 残留需手动 pkill）
2. 机制 A（300s 转后台）——用户裁定核心
3. 机制 B（后台硬超时 + 停滞）——依赖 A 的转后台任务接管
4. 机制 D（活动桥接阈值）——小改
5. 机制 F（长 RUNNING 告警）——依赖 D 的准确性
6. 机制 C（网络命令 + 代理检测）——独立，可并行

---

## 附：设计约束回执

- [x] 08-19 裁定「bash 无自动超时」→ 兼容：300s 转后台是 turn 释放不是杀进程；显式 timeout 语义不变；no-progress ceiling 保留
- [x] 08-19 裁定「移除 autoBackgroundThreshold」→ 08-25 新裁定覆盖（统一 300s，不再按 depth 区分 30s/300s）
- [x] 08-25 裁定「5 分钟自动转后台」→ 机制 A
- [x] #391 → 机制 E（一）+ 机制 F（二）
- [x] 用户裁定精神「不猜命令要跑多久」→ 机制 C 不猜性质（只识别明确网络命令 + 低阈值兜底），不直接拒绝
