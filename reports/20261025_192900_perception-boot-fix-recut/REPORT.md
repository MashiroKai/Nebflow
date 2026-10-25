# P0 感知域 boot 崩溃修复（sweepLoop/spoolLoop 构造期自引用 SOE）

批次：20261025_192900_perception-boot-fix-recut · 分支 delegate/delegate-nebflow-5201f3c4
状态：**修复已落 main（作者紧急授权直并）**；全量 release 重切版（门禁 B/C + 生产位替换）按作者令推迟到下一版，本轮只做「合并 + main HEAD 最小 boot-smoke 证活」。

## 1. 崩溃机制

- 症状：main HEAD 任何新 daemon 起不来——boot 必经点 `PerceptionService.scala:72` `core.sweepLoop.start` 求值 `sweepLoop` 时 **StackOverflowError**，listener 起不来（切版批 f12732c8 实证 2/2，RED 报告 `reports/20261025_191111_prod-release-cut__delegate-nebflow-f12732c8/REPORT.md`）。
- 根因：`def sweepLoop = IO.sleep(10s) *> sweepOnce *> sweepLoop`——`*>`（productR）右操作数是**急切**方法调用：构造一个迭代的 IO 描述需同步递归构造全部未来迭代 → 值构造期无限递归（尚未 `.start`、尚未跑任何 effect 即炸）。
- 同型排查（全仓 `def *loop` × `*>` 交叉扫描）：`spoolLoop`（同文件 164 行）**同型同炸**，一并修净；其余命中（GatewayMain/RemoteExecutor/ScheduledTaskActor/HealthMonitor/NeblinkRelayTunnel/BashTool 等）递归腿全在 `else`/`case`/`flatMap` lambda 惰性位，安全；`MemoryRecallDaemon` 已用 `IO.defer` 修过。本仓同 bug 类已有两次 QA 前科与修复先例：`ProjectActor.ttlScanner`（`*> IO.defer(loop)`）、`TaskStuckWatcher.run`（for 推导）。
- 引入：`183c375c2`（邮件感知批，经 `7746771a0` 落 main）。

## 2. 修法

`PerceptionService.scala` 两处（merge 后 main 行号 158/173）：

```scala
// 前：IO.sleep(10s) *> sweepOnce *> sweepLoop          // 构造期无限递归
// 后：IO.sleep(10s) *> IO.defer(sweepOnce *> sweepLoop) // 递归腿惰性化
//    IO.sleep(45s) *> IO.defer(replaySpool().attempt.void *> spoolLoop)
```

- `IO.defer` 的 by-name thunk 构造期不求值 → 构造期零递归；运行期每轮只构造一层，递归跨 async 边界堆分配，栈安全。
- sweep 语义与背压节奏**逐字不变**（10s/45s sleep、sweepOnce/replaySpool 错误隔离形态原样）。
- 风格与同仓先例 `ProjectActor.ttlScanner` 一致；`*>`-族其余循环不动。

## 3. 回归钉（PerceptionCoreSpec，+4）

感知域循环节奏固定（10s/45s），不能像先例那样缩 interval——钉法：

| 钉 | 旧代码行为 |
|---|---|
| `sweepLoop`/`spoolLoop` **求值不炸栈**（boot 必经点） | 当场 SOE → 红 |
| `sweepLoop` fiber **可启动/进 sleep 腿/可取消** | start 前 SOE → 红 |
| `spoolLoop` 求值不炸栈 + fiber 启停 | 当场 SOE → 红 |
| `sweepLoop` **跨 sleep 边界推进 ≥1 轮**（过期窗被清为可观察证据，上限 13s） | 到不了 → 红 |

验红依据：钉 1/3 的测点行 `val _ = core.sweepLoop` 即切版批实证 2/2 SOE 的同一求值点（证据 `evidence-branch-perception-spec.log` + RED 批 `evidence-repro-daemon.out`）。

## 4. 读数

- **感知族 spec**：PerceptionCoreSpec（感知域唯一 spec）**34/34 绿**，0 失败（含 4 新钉；推进钉 10.17s——真实跨过 10s sleep 边界完成第一轮 sweep）。证据 `evidence-branch-perception-spec.log`。
- **门禁 A**（ops/test/release-pipeline.test.sh，89 断言，沙箱）：**PASS=89 FAIL=0**（分支基线跑）。证据 `evidence-branch-gateA-assertions.log`。
- **boot-smoke**（分支 jar，2026.10.21-beta.2 版名）：**BOOT-SMOKE: PASS**，boot_ready=4s、checks=11（portal 200/44449B、health 200 带 deviceId、WS 101、teardown 零残留）。对照 RED 批同关 `listener_seen=0` 秒死。证据 `evidence-branch-assembly-boot-smoke.log`。
- **main HEAD 最小 boot-smoke**（合并后补跑，作者令 #2）：见 §6 附录。

## 5. 基线与合并

- 合并：`127ff2c59` merge --no-ff `delegate/delegate-nebflow-5201f3c4` → main（作者紧急授权直并，绕过滞留合并队列；冲突预判：分叉侧 6 提交零触及感知域文件，实际零冲突）。
- 交付 commit：`837653392`（fix+spec）、`02fcf17c8`（分支级证据）。
- 新 main HEAD 树已验含修复（`git show main:...PerceptionService.scala` 158/173 行 IO.defer 在位）。
- main 分叉侧并入的其他改动（fbe4618c/e983c3b5 等：DelegateDeliveryReceipts/AgentIdle/NfFileRoutes 等）未过本批验证——已由 main HEAD boot-smoke 兜底证活，全量门禁留给下一版。

## 6. 附录：main HEAD（127ff2c59）assembly + boot-smoke 读数

- 2026-10-25 23:16 合并后补跑（作者令 #2）：`sbt assembly` rc=0 → **BOOT-SMOKE: PASS** jar=stand-assembly-2026.10.21-beta.2.jar（内容=main HEAD 127ff2c59，版名沿用 VERSION 文件值）、port=8089、**boot_ready=4s、checks=11**、health 200（listener 起）、teardown 零残留。证据 `evidence-mainhead-assembly-boot-smoke.log`。
- 结论：**合并后 main HEAD daemon 起得来**（对照：修复前 main HEAD 同关 listener_seen=0 秒死）。

## 7. 未做 / 待下一版

- 门禁 B（全链真实 E2E，stage 冒烟须 BOOT-SMOKE: PASS）与门禁 C（M1 形态 ×4 battery）：**未跑**（作者令：全量 release 门禁等下一版）。
- 生产位切版（`~/.stand/bin` 指针 + versions/ 版本包）：**未动**——现役指针仍 `2026.10.25-dev.8ae0e5317`，在产 daemon 未重启。**重启待批**：批准后 `stand start` 即从新 main HEAD 解析新包；下一版切版批按完整 A/B/C 门禁 + `-dev.<sha>` 惯例走。
- tag：未打（tag 只在 approve 之后，本批未走切版流水线）。
