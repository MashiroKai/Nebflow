# fix：presence 候选面最小落地（p3-fix 实施记录）

- 分支：`delegate/delegate-nebflow-4280aa54`，基 main tip `3047906d0`，本节点产出 2 个 commit。
- 上游依据：`evidence/presence-direct-261104/probe/probe.md`（p1，运行事实）+ `audit/audit.md` / `audit/fix-spec.md`（p2）。
- 本批**未执行**任何宿主侧动作（未 `tailscale up`、未改防火墙、未重启实例、未 kill 进程）。

## 1. 根因层与分支

| 项 | 结论 |
|---|---|
| 唯一根因 | **宿主侧**：本机 Tailscale `Stopped`，离网约 1 天 ⇒ 对端 tailnet `100.91.165.120` 被丢给运营商网关 `100.65.0.1` 黑洞化（SYN 超时、非 refused） |
| 本批权限 | 宿主 / 防火墙 / 绑定面 / 实例形态均不改 ⇒ 适用**分支 B**（代码侧只做失败面韧性/收敛） |
| 与任务书点名偏差 | brief 的分支 B 写「可达性预探」；p2 的算术证据（audit.md §Q3）判定「先探再拨 = 同一事实付两遍」、并发首胜拨号本身即探测器 ⇒ **不新增预探**；§4③ 三条理由判定**不改排序**；负证据复用既有 `core/tools/P2pReachability` 账本（由并行批 76417e90 交付），**不另造第二套** |

## 2. 改动清单（`git diff --stat main`）

```
 src/main/scala/nebflow/neblink/NeblinkClient.scala              |   5 +-
 src/main/scala/nebflow/neblink/NeblinkPresenceService.scala     |   1 +
 src/main/scala/nebflow/neblink/NeblinkService.scala             |  18 +-
 src/main/scala/nebflow/neblink/PresenceCandidateProbe.scala     | 166 +++++++++
 src/test/scala/nebflow/neblink/PresenceCandidateProbeSpec.scala | 223 +++++++++
 5 files changed, 408 insertions(+), 5 deletions(-)
```

逐处接入：

1. `NeblinkService.upsertPeer` 的 `peersRef.modify` 闭包（入站 presence 经 `gateway/PresenceRoutes.scala:542` 的落点）
2. `NeblinkService.handleAnnounce`
3. `NeblinkService.handleHandshake`
   ⇒ 全部走 `PresenceCandidateProbe.mergeObserved(prev, observed)`
4. `NeblinkClient.detectLocalEndpoints` ⇒ `PresenceCandidateProbe.sanitizeReported(raw)`（`/24` 判据因而在过滤后地址集上算）
5. `NeblinkPresenceService.openConnection` 的 HttpClient 构建 ⇒ **+1 行** `connectTimeout(3000ms)`（该文件 1 增 0 删）

## 3. 设计依据（默认值 / 常数为何是这些数）

- `MaxReportedEndpoints = 4`：单轮拨号预算 12s ÷ 最贵档 3s = 4 个候选；多上报不提升成功率，只让对端每个候选各付一份预算（audit.md C-3）。
- `MaxConnectTimeoutMs = 3000`：取**最贵档**（`DialAddressClass.Tailnet.budgetMs`）⇒ 任何一类候选的合法 TCP 建连都不会被这层提前掐断；它只给黑洞地址一个资源上界（改前 `future.get(budgetMs)` 只让调用方不等，SYN 与 selector 线程继续在飞，audit.md C-4）。用字面量而非跨类引用，是为让本件不依赖被 76417e90 改写的拨号结构文件。
- `sanitizeReported` 三条不变量：保序（排序权仍在 `EndpointPreference.rank`）、不误杀 `100.64/10`（tailnet 挂在 `utun` 上也保留）、过滤为空原样返回（沿用 `NeblinkClient` 的 "never lose all endpoints"）。
- `mergeObserved` 只增不减：`endpoints = (observed.address :: prev.endpoints).distinct.take(4)`，`address == endpoints.head` 恒成立；`observed.endpoints` 非空 ⇒ 原样返回（名册腿零行为变化，且名册每拍把全量候选写回）。
- 未做的两项（刻意）：共享单例 HttpClient（为把 `NeblinkPresenceService.scala` 改动压到 0 删除行）、`reachHintMs` 兜底臂（只在 76417e90 被回滚时才需要）。

## 4. 验证原始结论行

```
sbt -batch compile
[info] compiling 742 Scala sources ...
[info] done compiling
[success] Total time: 66 s (0:01:06.0), completed 2026年11月4日 14:58:21

sbt -batch "testOnly nebflow.neblink.PresenceCandidateProbeSpec"
[info] Passed: Total 18, Failed 0, Errors 0, Passed 18

sbt -batch "testOnly nebflow.neblink.NeblinkPresenceServiceSpec"
[info] Passed: Total 5, Failed 0, Errors 0, Passed 5

相邻 6 spec（EndpointPreference / NeblinkDialStatusRoute / NeblinkPresenceConvergence /
NeblinkHeartbeatUiPort / NeblinkGracePeriod / NeblinkDiscovery）
[info] Passed: Total 46, Failed 0, Errors 0, Passed 46

客户端 3 spec（NeblinkClientRelogin / NeblinkClientSessionHeal / NeblinkHeartbeatUpdateWire）
[info] Passed: Total 22, Failed 0, Errors 0, Passed 22
```

## 5. 待 p4-verify 验收清单

1. **回落路径原样**：`git diff main -- src/main/scala/nebflow/core/tools/ src/main/scala/nebflow/neblink/NeblinkRelayTunnel.scala` 为空（本节点实测为空）；`sendDataOrRelay` / `shouldSkipP2pForRelay` 未改。
2. **红线文件零改动**：`GatewayMain.scala` / `PresenceDialPool.scala` 无 diff（实测为空）。
3. **`NeblinkPresenceService.scala` 改动 = 1 增 0 删**（`git diff main --stat` 该文件 `1 +`）。
4. **候选集保真可观测**：运行期入站 presence 之后 `neblinkService.peers` 的 `endpoints.size` 不再掉到 0；`RemoteExecutor.addressKey` 不再因入站记录抖动。
5. **上报面卫生**：本机上报的 `endpoints[]` 不含 `169.254.*`、不含 TAP/utun/ZeroTier 等隧道网卡的非 tailnet 地址；`100.64/10` 仍在且仍排首位。
6. **无重复探测**：`grep -rn "probeConcurrently\|new Socket" src/main/scala/nebflow/neblink/PresenceCandidateProbe.scala` 无命中（本件无任何 socket/HTTP 探测）。
7. **与并行批 76417e90 合并**：若其 `GatewayMain.scala` 已装配 `reachLedger = Some(P2pReachability.current)`，合并后应出现；本 worktree 基线**无**该行（76417e90 未合入）⇒ 属合并后读数断言，不属本节点职责。
8. **仍不通即宿主问题**：tailnet 恢复后若 `tailscale ping 100.91.165.120` 通过而 6138 仍 timed out，才回到代码面（此时看 `Presence dial failed` 行里的候选串是否已含 tailnet 候选）。

## 6. 假设

- 并行批 76417e90 会按 p2 所述形状合入（并发首胜 / 专用池 / 共用负缓存）；本件不 import 其任何新类型，故其合入与否不影响本件编译。
- 知识文档未落 `<dataRoot>/projects/<project>/.stand/docs/`：数据根下**不存在** `projects/presence-direct-261104`（本链无注册项目），沿用 p1/p2 的 `evidence/presence-direct-261104/` 目录（git-ignored）。
