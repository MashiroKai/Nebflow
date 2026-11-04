# T7 设备腿 · 合并后等价性裁定（route-tiers 批相交）

作者令 2026-11-04「组织优化 bash，用业界成熟做法完整实现」。本文件记录本批唯一一次**跨批代码相交**的处置与裁定。

## 1. 相交事实

- 本批 T7（`2e916643d`）改 `src/main/scala/nebflow/core/tools/RemoteExecutor.scala`（设备腿：超预算自动后台、不再降级 isError、可 BgWait）。
- route-tiers 批（前移 `51309ec51 → 4828151c2`）**也改同一文件**（84 行 / 6 个 hunk，行号 1627–1884）。
- 合入结果：`bb33e709f`（第 26 次前移，25 次前移一次性合入）与 `9edce92a9`（第 27 次前移）均**零冲突**；`git diff --name-only --diff-filter=U` 为空。
- 合并后本批标识符完整：`RemoteExecutor.scala` 含 `BgWait` ×2、`BashTool.scala` 含 `BgWait` ×19。

## 2. 逐 hunk 静态裁定：不相交

route-tiers 的 6 个 hunk 全部落在**选路面**：

| hunk | 内容 | 是否触及 T7 |
|---|---|---|
| `@@ -1627` | `p2pExecute` 文档：三步选路（① Tailscale 档 / ② 自研 P2P / ③ relay 兜底） | 否 |
| `@@ -1657` | `plan.isEmpty` ⇒ 直接返回 `Cannot reach …（0ms，未付探测税）` | 否 |
| `@@ -1669` | `resolveP2pEndpoint` 增 `plan: RoutePlan` 参、候选集改 `plan.attempt`、每分支落 `[route]` 日志 | 否 |
| `@@ -1704` | 探测日志 `decision=${DirectRoute.viaOf(ep)}` | 否 |
| `@@ -1820` | 文档：选路计划在重试循环**之外**算一次 | 否 |
| `@@ -1830` | `attempt(n)` → `attempt(n, plan)`；`DirectRoute.plan(...)` 包住重试循环 | 否 |

**判据**：在整个 route-tiers diff 中检索 `BgWait|bgWait|background_job|autoBackground`，命中 **0 次**。它改的是「拨哪个端点、要不要拨」，T7 改的是「设备腿的超预算语义」——两条正交轴。

唯一语义接触点（已登记，非缺陷）：`plan.isEmpty` 时设备调用会**提前**返回 `Left(ToolError("Cannot reach …"))`，即直连档全跳过时不进入命令执行——这发生在 T7 的超预算判定**之前**，不改变 T7 语义（不产生预算竞争，也不产生 isError 降级）。

## 3. 运行时复核：未能完成，如实登记

- 计划：合并后 `testOnly nebflow.core.tools.DeviceBashAutoBackgroundSpec nebflow.core.tools.BgWaitToolSpec nebflow.core.tools.DeviceBgTaskSpec`。
- 实际：该 sbt 于 18:57 排队、**19:01:51 取得 sbt 串行闸**（`acquired after 286s`），随后其 java 子进程 **35 分钟仅 0.1–0.2% CPU、stdout 0 行**（`merge-parity-T7.log` 全文仅 1 行闸日志），同时段机器上另有 3 个重型 sbt 在跑、load average 9–12、`Pages free` 1.7 万 ⇒ 判定为**环境级阻塞**，非本批代码问题。
- 按项目铁律**不对任何 sbt/java 进程发信号**（含 kill），故该进程保持原状、不中断。
- 已有替代证据链（机器可读）：
  1. `suite/full-test-run3-final.log`：`DeviceBashAutoBackgroundSpec`（8 例）+ `DeviceBgTaskSpec` + `BgWaitToolSpec` **全绿**（该轮跑于 route-tiers 合入**之前**）；
  2. 本文 §2 的逐 hunk 静态裁定（route-tiers 零触碰 T7 语义面）；
  3. 合并在 `git` 层零冲突。
- **待补证（后续任一批可顺手做）**：机器负载正常时单跑上述 3 个 spec，落 `merge-parity-T7-runtime.log`。
