---
type: audit
status: final
date: 2026-10-31
topic: 后台任务面板「owner lost」根因调查
---

# 后台任务「owner lost」根因调查（2026-10-31）

关联：[[20261031_123300_bg-panel-hier-name-split__delegate-nebflow-4cbce603]]（后台任务面板批）、[[glossary]]、reports/20261031_death-watch-ownership-fix-report.md（同仓 reports/）、缺陷登记 #1193 / #1220、修复 commit `748b5a81c`、归属批 `e84381bd5`。

## 一句话根因

「owner lost」不是单一缺陷：death-watch（BgTaskReaper）的**三个判死分支各自存在误报/噪声路径**——① 自然完成的亚秒级竞态被记成 `process dead`；② 引擎重启关机窗内被杀的任务被记成 `owner session gone`；③ 行与会话任务图的**静默发散**（job 仍在跑但任务图查无此项）被记成 `not owned by session`（即 #1220 残余，微机制未完全闭环）。与归属批（e84381bd5）无关（该批只加 ownerName 展示字段，且 14:10 才合入 main、引擎尚未重启加载）。

## 1. 「owner lost」文案出处与判死分支清单

文案唯一生产点：`BgTaskReaper.reapOne`（`src/main/scala/nebflow/core/tools/BgTaskReaper.scala:121`），收割动作（同文件 110-146 行）：取走 registry 行 → 账本 `recordCompletion(failed, "…owner lost: $reason")` → 输出留存区 `finalizeTask(cancelled, "owner lost: $reason")` → WS 广播 `backgroundTaskUpdate(cancelled)` → 沿属主/根会话链注入「等待对象已终止」ExternalEvent（模型转录里可见 system-reminder）。

判死分支（`BgTaskLiveness.scala:35-57`，全部"零墙钟"读内核事实）：

| 分支 | 判据 | 含义 |
|---|---|---|
| `owner session gone` | `peekSession` 返回 None（不在册/已死） | 属主会话已殁 |
| `not owned by session` | 会话在册但 `probeJobLiveness` 返回 None（任务图无此 job） | 行指向的会话不持有该任务 |
| `process dead` | 任务图持有、句柄已落、但 `proc.isAlive=false` | 进程可验证已死 |
| （豁免） | 句柄未落（`handlePresent=false`）⇒ **Alive** | A-4 修复：spawn 在飞窗口不判死 |
| `remote wait leg gone` | remote 行的等待 fiber 句柄缺席 | 终态腿绝路 |
| Unknown | RemoteExecutor 未初始化 | 一律跳过（宁可漏收） |

收割调用点：`BgLifecycleReconciler.scan`（每 30s，`BgLifecycleReconciler.scala:306`）、`BackoffSupervisor.delegateBgWaitGate`（回合尾 Completed 复检，`BackoffSupervisor.scala:516`）、`NodeStarter` Completed 桥（`NodeStarter.scala:968`）。

## 2. 修复后（748b5a81c 上线，11:33 重启）仍发生的三起实证

引擎日志 `~/.stand/logs/stand.log`，留存区 `~/.stand/bg-task-logs/*.meta.json`。

### ① 5ae83e33 — `process dead`：自然完成的亚秒级竞态（误报）

- 12:54:54.152 job 启动（delegate-nebflow-d2fc050f，"Rerun uifix-quad alone"）；758ms 后 12:54:54.910 被收割 `process dead`；184ms 后 12:54:55.094 真完成腿落地：`completed (exit 0) (row already terminal — output store only, no re-notify)`。
- 实情：node 脚本因 EADDRINUSE **瞬间崩溃**（留存区 stderr 为证），进程已死而 deferred 完成腿尚在飞。探针恰落在两者之间 → 误收。任务本身已自然终态，无害，但账本/面板/WS 留下「owner lost」假证。
- 代码缝隙：`BgTaskLiveness` **从不读 `probe.done`**（`JobLivenessProbe.done` 字段落了但判读不用）；"进程死 + 终态腿在飞"与"进程死 + 真绝路"无法区分。

### ② 639c9d15 — `owner session gone`：重启关机窗误报

- 13:34:36 作者触发 `nebflow-restart.sh --force`；13:34:46 "stand shutting down" + daemon 停止；**13:34:40** delegate（2be86ba4）在垂死窗口内仍发出一个后台任务（"Rerun baseline suite…"）；13:34:55 关机流程已杀会话，reaper 探针命中 → `owner session gone`。
- 实情：任务死于**重启本身**，被记成 owner-lost。重启脚本报文（/tmp/stand-restart-run4.log）确认旧进程树全灭。

### ③ 887c8244 — `not owned by session`：行 ↔ 任务图静默发散（#1220 残余，未闭环）

- 14:06:20.478 job 启动（delegate-nebflow-d7ccddf0，sbt 定向 spec）；14:06:40.655（整 20.2s 后）被收割 `not owned by session`。留存区 meta：`status=cancelled, errorHint="owner lost: not owned by session", totalLines=8`。
- **铁证**：进程并未死——14:09:41 完成回调 `completed (exit 0)`（row already terminal）；887c8244.log 的 mtime 定格在 14:06:40（收割关闭缓冲），内容停在 "compiling 698 Scala sources"。同会话 22s 后起的重试任务 8f8e2ae7 正常跑完 3m13s——**会话实例从未被替换/杀死**（修复后 replace 必留 `[bg-ownership]` 日志，全程无）。
- 排除法：任务图移除仅有三个代码点（`shell.scala` 323 行 `getBackgroundResult` 需 deferred 已终态；417 行 `cancelBackgroundJob` 会杀进程+完成 deferred；451 行 `kill()` 会完成 deferred Left——后两者与 "exit 0 回调" 证据矛盾）。**所有已知静默移除路径均被证据排除**，而行在、图无、进程活的组合在静态代码中无解释 ⇒ 存在未登记的发散路径（候选：某种形式的重复注册/实例错装），需加观测腿后才能定谳。
- 附带发现：判死豁免 `handlePresent=false ⇒ Alive` 对"重启恢复/收编的句柄永不再落"的任务会**永久 Alive**（46a86d3f 型僵尸任务免疫 death-watch，只能 suspect/nudge 循环）——方向相反的另一半缺陷。

## 3. 与近期改动对照

- **归属批 e84381bd5**（后台任务行标 delegate 短名）：纯增量展示字段（`ownerName`），不触碰 owner 生命周期/判死链；14:10:30 才合入 main，当前引擎（13:35 启动）未加载。**与「owner lost」无关**。
- **death-watch 修复 748b5a81c**：根治的是 10-31 上午的会话 TTL 误替换（83907ab5 等 5 起，修复后未再出现）。**今日三起是另外三条路径**，与该修复无关、非回归。
- 「老是报」的体感来源：每条 reap 会同时写四处（引擎 WARN + 会话转录 system-reminder + 留存区 errorHint + WS cancelled 帧），且 delegate 高频跑 sbt 短任务（编译分钟级），命中①③类路径的概率被放大。

## 4. 建议修法（只查未改，以下为改点清单）

1. **`BgTaskLiveness.probeLocal` 读 `done`**：`Some(p) if p.done ⇒ Alive`（终态腿在飞，下一拍行自注销；消灭 ①类假证）。配套 spec：进程死 + deferred 已落 ⇒ 不收割。
2. **幼龄行豁免**：行龄 < ~10s 且非 `owner session gone` ⇒ 跳过本轮（spawn/句柄/图落位窗口；③的 20s 误杀也部分覆盖）。①的亚秒场景由 `done` 分支兜。
3. **关机抑制**：引擎 shutdown 旗（AgentControl restart/Stop 置位），`reapDead` 入口检查，置位即整体跳过（消灭 ②类）。
4. **行↔图发散诊断腿**（③定谳前置）：`shell.scala` 323/417/451 三处移除点加 `logger.info(jobId, cause, sessionInstanceId)`；`executeBackground`/`adoptAsBackgroundJob` 注册点同样记实例 id。下一起 `not owned by session` 即可对上号。
5. **判死前二次确认**：`not owned by session`/`process dead` verdict 落地收割前，先复查 `BgTaskOutputStore.lineCounts` 近 N 秒是否有增长（有 ⇒ 判 Unknown 跳过一拍）——低成本防 ③类活任务误埋（该任务当时正在输出编译日志）。
6. **僵尸豁免收敛**：`handlePresent=false ⇒ Alive` 加期限（如行龄 > 基线窗仍无句柄 ⇒ 降级供健康轨处理），消灭"永久 Alive"另一半。

## 5. 证据索引

- 引擎日志：`~/.stand/logs/stand.log` 行 47591/47600（①）、50587/50728/50753（②+关机序列 13:34:36-55）、57288/57303/57420（③）。
- 留存区：`~/.stand/bg-task-logs/5ae83e33.meta.json`、`639c9d15.meta.json`、`887c8244.meta.json`（+ `.log`）。
- 代码：`BgTaskReaper.scala:110-146`、`BgTaskLiveness.scala:35-57`、`shell.scala:308/323/417/451/999-1080`、`BgLifecycleReconciler.scala:306`、`BackoffSupervisor.scala:516`。
- 修复/批次：`748b5a81c`（#1193 修复，已上线）、`e84381bd5`（归属批，未上线）、reports/20261031_death-watch-ownership-fix-report.md。
