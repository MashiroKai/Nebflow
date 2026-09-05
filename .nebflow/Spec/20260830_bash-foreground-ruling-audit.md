# 2026-08-30 #26 Bash 工具行为调整（恢复前台直跑语义）——交付核对表

> 阶段：Backend 实现完成，自测中，待 qa-backend 验收。
> 分支 feat/bash-foreground（worktree ~/Claude code/.nb-worktrees/nb-bash-foreground），基线 main @dae6f410。
> 作者裁定原文：「Bash 工具不再自动转后台，依赖卡死检测就行了，不设超时。」

## 裁定 → 实现映射

| # | 裁定要求 | 实现 | 验证 |
|---|---|---|---|
| 1 | 移除自动转后台：前台 Bash 不再 >300s 转后台 | **机制 A 整体删除**：BashTool.executeForeground 去掉 `IO.race(resultRef.get, IO.sleep(autoBackgroundMs))` 与 registerBackgroundJob/emitBgTaskStarted 占位分支——改为直接 `shell.execute` 等完成返回真实输出 | BashToolForegroundSpec sleep 2/35/65 三用例全部前台直跑完成无「moved to background」（旧 30s/300s 阈值下必然转后台） |
| 2 | 不设超时：前台 Bash 无硬超时 | 无显式 timeout 时 `processTimeout = 365.days`（近似无限）；删 Defaults.BashAutoBackgroundMs + BashResilienceConfig.autoBackgroundMs + nebflow.json bashAutoBackgroundMs 键 | 编译通过；sleep 65 直跑（无 timeout 参数） |
| 3 | 卡死检测兜底：TaskStuckWatcher 10min → restart | **保留全部卡死兜底链**：①活动桥接（有进展/CPU/sleep-like 刷新 lastActivityMs，有进展不判卡死）②前台 no-progress ceiling（shell.scala #22：10min 零输出零 CPU 非 sleep-like 停滞杀——命令级）③TaskStuckWatcher（turn 级：真卡死命令不刷新 → 10min 零活动 restart 杀进程树） | 代码审查（BashTool.scala 注释块 + shell.scala no-progress ceiling 未动） |
| 4 | 显式 run_in_background 照常 | executeBackground 显式后台路径未动（start/query/cancel/health/机制 B 兜底全保留） | BashBackgroundHardTimeoutSpec 等 6 套件 21/21 绿（含显式后台硬超时/停滞） |

## 评估点答复（任务书要求实现时回答）

**1. 显式后台任务兜底（机制 B 30min 硬超时+停滞窗口）——建议保留 ✅**
作者「不设超时」针对**前台** Bash（机制 A 推翻）。显式后台任务不占 turn 活动（executeBackground 独立 fiber，TaskStuckWatcher 检测不到其卡死），去掉机制 B 后后台任务真卡死（如 grep 挂 FUSE mount）无任何兜底——正是 08-25 #391 引入机制 B 的原始痛点。机制 B 只碰显式后台、不碰前台，与「前台直跑」语义零冲突。**已保留**（shell.scala startJobHealthCheck B1/B2）。

**2. 自动转后台代码清理——直接删，不留死代码 ✅**
- BashTool：executeForeground 转后台分支、summarizeResult "Auto-background" 分支、Deferred/race 结构全删
- shell.scala：registerBackgroundJob 方法 + startJobHealthCheck 的 isRegisteredJob 参数/分支删除（deadNotified 分支仅注册任务用）
- 配置：Defaults.BashAutoBackgroundMs、BashResilienceConfig.autoBackgroundMs、nebflow.json bashAutoBackgroundMs 键、GatewayMain 传参全删
- 测试：BashAutoBackgroundSpec 删除（git rm）

**3. RemoteExecutor（远程执行）——同裁定处理 + 活动心跳补偿 ✅**
远程 Bash（device= 参数）同受裁定约束：删除 30s 自动转后台（executeForegroundWithAutoBackground → executeForegroundSync 前台直跑）。**关键补偿**：远程调用无本地进程树、无进展探测（HTTP 等待中无法区分「运行中」/「设备无响应」），原 30s 转后台的隐性作用是 turn 释放防 TaskStuckWatcher 误杀——直跑后必须用**本地活动心跳**（每 30s touch agent lastActivityMs）保 turn 活动，否则远程长命令 >10min 被 watcher 误判卡死 restart。真挂起仍由 BgTimeout（3600s 网络超时）兜底——远程固有局限（无法输出/CPU 停滞检测），网络层超时是唯一防线。

**4. sleep 单独检测逻辑——随机制 A 移除，sleep-like 豁免保留**
「前台 sleep 计时特判」= 机制 A 的 sleep 转后台特判（Defaults 注释），随机制 A 删除。shell.scala 的 SleepCommandRe 豁免（前台 no-progress ceiling / 后台停滞检测豁免 sleep-like）是**卡死检测**语义（sleep 无输出无 CPU 但正常），保留——否则 `sleep 300` 会被 no-progress ceiling 当停滞杀。

## 改动清单（7 文件 +105/-240）

- BashTool.scala：机制 A 删除（executeForeground 直跑化 + summarizeResult + isRemoteExec 注释）
- RemoteExecutor.scala：删除 AutoBgThreshold + executeForegroundWithAutoBackground → executeForegroundSync + touchAgentActivity 心跳
- shell.scala：删 registerBackgroundJob + isRegisteredJob 分支 + 注释清理
- Defaults.scala：删 BashAutoBackgroundMs；BashResilienceConfig 去 autoBackgroundMs
- config.scala：删 bashAutoBackgroundMs 键
- GatewayMain.scala：删 autoBackgroundMs 传参
- BashToolForegroundSpec.scala：新增 sleep 65 用例（>60s 直跑，机制 A 全删的可测下限）+ 注释更新
- BashAutoBackgroundSpec.scala：删除（git rm）

## 验证终态

- compile ✅（/tmp/nb-bash-compile.log）
- Bash 相关 6 套件 21/21 ✅（/tmp/nb-bash-spec.log：BashToolForegroundSpec 含新 sleep 65 用例 / BashBackgroundHardTimeoutSpec / BashActivityBridgeSpec / ShellStuckDetectorSpec / ShellKillOnRestartSpec / ShellSessionRestartSpec）
- **全量 1867/1867 Passed 0 Failed**（/tmp/nb-bash-fulltest.log；基线 -4 机制 A 用例 +1 前台 sleep 65 用例，零回归）
- commit **c7c6961f**（8 files +106/-417，含删除 BashAutoBackgroundSpec）
- ⚠️ 生效随重启包：宿主 8080 实例仍是旧代码（本次全量测试轮询时实测旧宿主 480s 轮询被转后台——机制 A 真实存在证据）
