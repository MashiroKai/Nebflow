VERDICT: AUTHOR-GATED

# p4-verify：p3-fix 独立验收（只认事实）

- 验收 seat：`/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-4280aa54`（`delegate/delegate-nebflow-4280aa54`）
- HEAD：`fa6e592dc`；基线：`main` tip `3047906d0`
- 采集窗口：2026-11-04 15:13–15:22（local = macOS / KAI = Windows）
- 本节点全程只读取证，代码面零改动：未改源码、未改防火墙、未重启实例、未 `tailscale up`、未 kill 任何 sbt/java/nebflow 进程；唯一写盘 = 本目录 3 个日志 + 本文件。
- 判定口径：`PASS`（根因已定位 + 修复已落地 + 代码面全绿 + 运行期未通项诚实标注）/ `FAIL`（越界改动 / 编译或 spec 红 / 回落退化 / 根因无证据）/ `AUTHOR-GATED`（根因在宿主侧，代码已就位，等作者清单）。
  ⇒ 本批取 AUTHOR-GATED：根因（本机 Tailscale Stopped）在宿主侧、代码无法修，且 p3 的代码改动尚未生效（禁重启），P2P 运行期仍 0%。

---

## 0. 四读数总表

| # | 项 | 读数 | 判定 |
|---|---|---|---|
| 1 | 本机 → KAI:6138 连通性 | 两候选全 `Operation timed out`；`curl` 4005ms 超时；`tailscale status` = `Tailscale is stopped.` | 未通（宿主侧，待作者；非本批可修） |
| 2 | `connectionsHasKey=true` | 0 条（`false` = 86,017）；新代码未生效（实例启动 12:42:14 < 提交 15:03:52/15:04:16） | 0，已诚实标注（禁重启） |
| 3 | P2P 命中 / 往返延迟 | P2P 命中 0；P2P 尝试成本 n=7（1.204–1.235s，中位 1.208s）；relay 端到端 n=5（745–2017ms，中位 773ms） | 如实取数；审计行无延迟字段（格式所限，见 §3） |
| 4 | 失败面不退化（回落 relay） | 运行期实证：5 次真实下发全部 relay 成功；探针 1 走完 `p2p 失败 → relay 回落`；代码面 `sendDataOrRelay` / `core/tools/` 零 diff | 已证明未退化 |
| 5 | 代码面复跑 | `clean compile` 742 sources `[success] 87s`；11 spec `Passed: Total 91, Failed 0, Errors 0`；改动 = 5 文件；红线文件零 diff；`NeblinkPresenceService.scala` = 1 增 0 删 | 全绿，未越界 |

---

## 1. 连通性实测（本机 → KAI）

```
$ nc -vz -w 3 100.91.165.120 6138
nc: connectx to 100.91.165.120 port 6138 (tcp) failed: Operation timed out
exit=1

$ nc -vz -w 3 192.168.1.145 6138
nc: connectx to 192.168.1.145 port 6138 (tcp) failed: Operation timed out
exit=1

$ curl -sv --max-time 4 http://100.91.165.120:6138/ 2>&1 | head -20
*   Trying 100.91.165.120:6138...
* Connection timed out after 4005 milliseconds
* Closing connection

$ tailscale status
Tailscale is stopped.

$ tailscale ip -4
2026/11/04 15:13:54 no current Tailscale IPs; state: Stopped

$ tailscale ping -c 2 100.91.165.120
Tailscale is stopped.

$ route -n get 100.91.165.120
   route to: 100.91.165.120
destination: 100.91.165.120
    gateway: 100.65.0.1
  interface: en0
```

读数判定：未通。两条 `nc` 均为 `Operation timed out`（不是 `refused`）⇒ SYN 被丢弃，非「对端端口没监听」；
`tailscale ping` 连 tailnet 层都进不去（`Tailscale is stopped.`）⇒ tailnet 层与端口层都没通，断点在 tailnet 层，
与 p1 根因结论逐字一致（本机在 tailnet 里仍是 `offline, last seen 1d ago`，未随时间自愈）。

接 p1 根因结论 ⇒ 待作者在 KAI（macOS 侧本机）执行 p1 清单第 A 步，具体一步引用 p1 逐字命令：

```bash
# A. 恢复本机 tailnet（主因，macOS，首要一条）
open -a Tailscale
# 或 CLI 版：
tailscale up
# 验证：
tailscale status
tailscale ip -4
```

（后续 B/C 步见 §6 一页式清单。本节点未执行 A——无宿主写权限，且 brief 明令不改宿主面。）

---

## 2. `connectionsHasKey=true` 读数

### 2.1 实测（当前生产日志，按 mtime 取最新）

```
$ ls -lt ~/.stand/logs/ | head
-rw-r--r--@ 1 kaiyu  staff   44005712 11月  4 15:09 stand.log          ← 取此文件
-rw-r--r--@ 1 kaiyu  staff   2664428  11月  4 14:42 relay-exec-audit.jsonl

$ grep -c 'presence sendData.*connectionsHasKey=tru[e]' ~/.stand/logs/stand.log
0
$ grep -c 'presence sendData.*connectionsHasKey=fals[e]' ~/.stand/logs/stand.log
86017
```

首个 `true` 的时间戳与原始日志行：不存在——`true` 条数 = 0，没有任何一行可引。

首个 `false` 原始行（时间戳 2026-11-04 09:09:47.099，stand.log:879）：
```
2026-11-04 09:09:47.099 [io-compute-blocker-11] INFO  nebflow.neblink.presence - presence sendData: device=40742cd4-851a-4ec4-a49d-6db2509bf03d channel=agent-identity connectionsHasKey=false
```

设备维度：86,017 条全部是同一对端 `40742cd4-…`（= KAI），无第二条设备。

### 2.2 取数陷阱（本节点实测踩到并排除，防下游误采）

朴素命令 `grep -c 'connectionsHasKey=true' stand.log` 返回 1。逐行追查（`grep -n`）证明该命中
不是读数，是本节点自己那条 Bash 命令行被 `nebflow.handlers` 回显进了同一份日志：

```
228992:2026-11-04 15:14:04.603 [io-compute-0] INFO  nebflow.handlers - nebflow 独立验收读数 Tool Bash START: cd ~/.stand/logs && echo "=== stand.log count true ===" && grep -c 'connectionsHasKey=true' stand.lo
229003:  (cd ~/.stand/logs && echo "=== stand.log count true ===" && grep -c 'connectionsHasKey=true' stand.lo...) OK (3027ms)
229031:2026-11-04 15:14:16.226 [io-compute-6] INFO  nebflow.handlers - nebflow 独立验收读数 Tool Bash START: cd ~/.stand/logs && grep -n 'connectionsHasKey=true' stand.log; echo ...
```

⇒ 该 1 条被判为伪命中并弃用；本文件全部读数用自匹配免疫写法 `tru[e]` / `fals[e]`。
任何后续取数务必用 `tru[e]`，否则 grep 自己会造出一条 true。

### 2.3 诚实条款：为什么必然是 0（代码已提交但未生效，因禁止重启生产实例）

| 事实 | 证据 |
|---|---|
| 生产实例 PID 78176 启动于 2026-11-04 12:42:14 | `ps -p 78176 -o lstart=` → `三 11月/ 4 12:42:14 2026` |
| 它跑的是 12:42 那一刻的 jar，不是本 worktree 的 `target/` | `ps` 命令行 = `java @…/sbt-args*.tmp`，`-classpath …Stand/target/bg-jobs/sbt_e174ffb4/job-1/target/f4e141f8/83f63c49/stand_3-2026.10.26-beta.2.jar`，该 jar mtime = `11月 4 12:42`；进程 cwd = `/Users/kaiyu/Claude code/Stand`（主仓，非本 worktree） |
| p3 的两个 commit 在 15:03:52 / 15:04:16 | `git log -3 --format='%h %ad %s' --date=iso` |
| ⇒ jar（12:42）比代码（15:03/15:04）早 2 小时 21 分，本批明令不许重启 ⇒ 新代码一行都没被加载 | 上两行 |

结论（照实写）：`connectionsHasKey=true` 仍为 0 —— 代码已提交但未生效，因禁止重启生产实例。
这不是「修复无效」，是「修复未上机」。

生效后取数命令（重启/发版后原样跑）：
```bash
# ① 总数（自匹配免疫，务必带 tru[e]）
grep -c 'presence sendData.*connectionsHasKey=tru[e]' ~/.stand/logs/stand.log
# ② 首个 true 的时间戳与原始行
grep -m1 -n 'presence sendData.*connectionsHasKey=tru[e]' ~/.stand/logs/stand.log
# ③ 对照 false 总数
grep -c 'presence sendData.*connectionsHasKey=fals[e]' ~/.stand/logs/stand.log
# ④ 生效判据（新代码在跑）：拨号失败行里的候选数
grep -c 'Presence dial failed for KAI' ~/.stand/logs/stand.log
# ⑤ 负证据生效判据（跳过 p2p）
grep '\[p2p-probe\]' ~/.stand/logs/stand.log | tail -5
```

替代证据（新 spec 用例，18 条，`PresenceCandidateProbeSpec`，file: `src/test/scala/nebflow/neblink/PresenceCandidateProbeSpec.scala:36-223`）：
覆盖的是 C-1 / C-3 / C-4，不是「拨号命中」链：

| 覆盖项 | 用例（行号） |
|---|---|
| 候选集保真（C-1）：入站记录不得抹掉名册候选 | `mergeObserved：入站观察记录（endpoints = Nil）不得抹掉名册候选` :139；验红点 `改前行为对照——整记录替换会把候选集塌缩成 Nil` :154；`address 恒等于 endpoints.head 不变量` :190；幂等 :180；名册腿零行为变化 :170 |
| 上报面卫生（C-3）：丢 169.254/回环/组播/隧道网卡非 tailnet 地址、保序、去重、封顶 4 | :43 / :55 / :69 / :79 / :89 / :111 / :116 / :122 / :127 |
| TCP 建连上界（C-4）= 3000ms 且与最贵档预算对齐 | :36 |

未覆盖（照实说）：brief 点名的「黑洞候选被跳过 + 可通候选被选中 + 失败计入负证据 TTL」三条断言
不在本 worktree 里——它们属并行批 76417e90，其 spec（如 `NeblinkPresenceDialConcurrencySpec`）
在本 worktree 不存在（`ls src/test/scala/nebflow/neblink/ | grep -i concurrency` 无命中）。
但该行为在生产实例上已运行期实证（见 §3.3：探针 1 失败 p2p 后，探针 2–5 在负证据窗口内直接跳过 p2p）。

---

## 3. P2P 命中与往返延迟

### 3.1 审计行格式（file:line）—— 关键限制：无延迟字段

- 落盘位置：`src/main/scala/nebflow/core/tools/RelayExecAudit.scala:100-101` → `<dataRoot>/logs/relay-exec-audit.jsonl`（实测 `~/.stand/logs/relay-exec-audit.jsonl`，验收结束时刻 6,838 行）
- 行格式（键集合，`:212-221`）：`ts` / `deviceId` / `targetDeviceId` / `via` / `action` / `command`(redact) / `projectRoot` / `cwd`（`kind` 加性可选 `:222-224`）
- `record()` 无任何耗时/延迟字段（`:201-235` 逐字可核）⇒ 「逐条延迟」无法从 RelayExecAudit 取，这是格式事实，不是取数失败。
- `via` 的语义（关键，防误读）：`RemoteExecutor.scala:1597-1599` 注释逐字——
  「T4：一次逻辑下发的审计行（在第一次网络尝试前落——重试是同一行下发，不重复记行；下发本身失败也记）」
  ⇒ `via="p2p"` = 「p2p 被尝试过」，不是「p2p 成功」。把 `via=p2p` 条数当命中率是错的。
- p2p 出口 `:1599`（`auditDispatch(..., "p2p", ...) *> attempt(0)`）；relay 出口 `:1744-1753`（`relayExecAudited`，唯一 relay 出口）。

### 3.2 逐条原始延迟值

(a) 本节点实测（n=5，本轮 15:17，全部真实下发到 KAI，原始日志行）

```
229571:  (echo "NEBVERIFY-PROBE-1"; date +%H:%M:%S.%N 2>/dev/null || date +%H:%M:%S) OK (2017ms)
229597:  (echo "NEBVERIFY-PROBE-2"; date +%H:%M:%S.%N) OK (773ms)
229645:  (echo "NEBVERIFY-PROBE-3") OK (745ms)
229662:  (echo "NEBVERIFY-PROBE-4") OK (796ms)
229671:  (echo "NEBVERIFY-PROBE-5") OK (749ms)
```
配对审计行（`~/.stand/logs/relay-exec-audit.jsonl` 尾部，UTC）：
```
07:17:10.627Z via=p2p   NEBVERIFY-PROBE-1        ← p2p 尝试
07:17:11.844Z via=relay NEBVERIFY-PROBE-1        ← 1.217s 后回落 relay（成功）
07:17:24.392Z via=relay NEBVERIFY-PROBE-2        ← 负证据命中，直接 relay
07:17:39.808Z via=relay NEBVERIFY-PROBE-3
07:17:47.367Z via=relay NEBVERIFY-PROBE-4
07:17:55.281Z via=relay NEBVERIFY-PROBE-5
```
relay 端到端逐条：2017 / 773 / 745 / 796 / 749 ms，中位 773ms。
（2017ms 那条含冷启动 + 一次 p2p 失败成本；纯 relay 稳态 = 745–796ms。）

(b) P2P 尝试成本（当日，配对法，n=7，逐条）

配对法：同日同 `command` 的 `via=p2p` 行 ts → 随后 `via=relay` 行 ts 之差 = 该次 p2p 尝试烧掉的时间。

```
原始值(s): 1.235 / 1.209 / 1.204 / 1.208 / 1.204 / 1.204 / 1.217
中位 1.208s，min 1.204s，max 1.235s
```
⇒ 与 `P2pPathDecision.probeBudget(directOnline=false)` 的 1.2s 探针档吻合（`RemoteExecutor.scala:1676`）。

(c) 当日 via 分布（原始计数）

```
2026-11-04  p2p = 207 条（attempt）  relay = 225 条
其中 199/207 条 p2p 尝试在 60s 内出现同命令 relay 行 ⇒ p2p 失败并回落；
8 条无 relay 后继（全在 02:xx UTC = 10:xx local，action=Bash/Write，target=KAI）——
审计行无结果字段，无法判定其成功/失败；且这些下发未在本实例 stand.log 留下 handler 行
（grep 'cwd=$(pwd)' stand.log 无命中），来源不可追 ⇒ 不计入命中。
```

### 3.3 判定

| 读数 | 值 | 说明 |
|---|---|---|
| P2P 命中（可证明的） | 0 条 | 原因 = 端口未通（宿主侧 tailscale stopped），非实例未生效（实例本来就在跑，p2p 尝试每次都真拨了） |
| relay 延迟（本轮 n=5） | 2017/773/745/796/749 ms，中位 773ms | 与 brief 给的 relay 中位 2200ms 基线相比更快（约 -65%）；该 2200ms 基线本轮未复现，且 brief 未给其取数命令 ⇒ 按「不可复现基线」记录，不做「优化了多少」的推论 |
| 对照 | — | relay 完好（5/5 成功），P2P 0 ⇒ 结论仍是 relay 独木桥，与 p1 一致 |

---

## 4. 失败面不退化（回落 relay）—— 已证明未退化

两条腿都做了，主证据是运行期实证（比 spec 更强）：

(A) 运行期实证（真下发，非静态）
- 探针 1：`p2p 尝试（07:17:10.627Z）→ 失败 → 1.217s 后 relay 回落成功（07:17:11.844Z）`，工具结果 `OK (2017ms)`，
  远端真实返回 `NEBVERIFY-PROBE-1 / 15:17:12.521114200` ⇒ 回落链路端到端跑通。
- 探针 2–5：负证据命中，直接 relay-only，773/745/796/749ms 全部成功。
- ⇒ 在不改防火墙、不重启实例的前提下，用进程内可逆手段（正常下发 + 负证据账本自然收敛）证明了回落路径完好。

(B) 代码面零改动（file:line）

```
$ git diff --stat main -- src/main/scala/nebflow/dropbox/DropboxService.scala
(空)
$ git diff main -- src/main/scala/nebflow/core/tools/ | wc -l
0
```
- `sendDataOrRelay` 定义：`src/main/scala/nebflow/dropbox/DropboxService.scala:371-383`（`sendData` 返回 false ⇒ `relayClientOpt` 走 `relayNotify`）——该文件 vs main 零 diff。
- 远端下发侧回落：`RemoteExecutor.scala:1644-1708`（`executeViaBestPath`，`shouldSkipP2pForRelay` 在 `:77-82`，`relayWithColdStartRetry` 在 `:1756+`）——整个 `core/tools/` 目录零 diff。

结论：回落能力已证明未退化（运行期 + 代码面双证）。

---

## 5. 代码面复跑（独立重跑，不采信 p3 转述）

### 5.1 全量重编（非增量：先 clean）

```
$ sbt -batch clean compile
[info] compiling 742 Scala sources to .../delegate-nebflow-4280aa54/target/scala-3.5.2/classes ...
[info] done compiling
[success] Total time: 87 s (0:01:27.0), completed 2026年11月4日 15:17:07
```
（p3 报 66s 增量编译；本节点 clean 后 87s，742 sources 全编，0 error。）

### 5.2 p3 声称跑过的 11 个 spec —— 全量重跑

```
[info] Passed: Total 91, Failed 0, Errors 0, Passed 91
[success] Total time: 190 s (0:03:10.0), completed 2026年11月4日 15:20:23
```
逐 spec 全绿（第二个日志 `logs-specs-full-*.log`，23s 增量复跑得同一结论 `Total 91, Failed 0, Errors 0`）：
`PresenceCandidateProbeSpec` / `NeblinkPresenceServiceSpec` / `EndpointPreferenceSpec` / `NeblinkDialStatusRouteSpec` /
`NeblinkPresenceConvergenceSpec` / `NeblinkHeartbeatUiPortSpec` / `NeblinkGracePeriodSpec` / `NeblinkDiscoverySpec` /
`NeblinkClientReloginSpec` / `NeblinkClientSessionHealSpec` / `NeblinkHeartbeatUpdateWireSpec`
⇒ 91 = p3 报的 18+5+46+22，逐字吻合。

### 5.3 越界核对

```
$ git log --oneline -3
fa6e592dc fix(neblink): 接入 PresenceCandidateProbe —— 候选集保真(C-1) / 上报卫生(C-3) / TCP 建连上界(C-4)
16f209c41 feat(neblink): 新增 PresenceCandidateProbe（候选端点面纯逻辑件）+ 专用 spec
3047906d0 chore(evidence): 时间滚轮批证据图重跑更新…（= main tip，基线）

$ git diff --stat main
 src/main/scala/nebflow/neblink/NeblinkClient.scala              |   5 +-
 src/main/scala/nebflow/neblink/NeblinkPresenceService.scala     |   1 +
 src/main/scala/nebflow/neblink/NeblinkService.scala             |  18 +-
 src/main/scala/nebflow/neblink/PresenceCandidateProbe.scala     | 166 ++++
 src/test/scala/nebflow/neblink/PresenceCandidateProbeSpec.scala | 223 ++++
 5 files changed, 408 insertions(+), 5 deletions(-)

$ git diff --numstat main
4	1	NeblinkClient.scala
1	0	NeblinkPresenceService.scala      ← 1 增 0 删（上限 3 行，未越界）
14	4	NeblinkService.scala
166	0	PresenceCandidateProbe.scala
223	0	PresenceCandidateProbeSpec.scala

$ git status --porcelain
(空)
```

| 红线文件 | `git diff main` 行数 | 判定 |
|---|---|---|
| `src/main/scala/nebflow/gateway/GatewayMain.scala` | 0 | 未越界 ✔ |
| `src/main/scala/nebflow/neblink/PresenceDialPool.scala` | 0 | 未越界 ✔ |
| `src/main/scala/nebflow/neblink/NeblinkRelayTunnel.scala` | 0 | 未越界 ✔ |
| `src/main/scala/nebflow/core/tools/`（回落面全部） | 0 | 未越界 ✔ |
| `src/main/scala/nebflow/dropbox/DropboxService.scala` | 0 | 未越界 ✔ |
| `NeblinkPresenceService.scala` | 1（增 1 / 删 0） | ≤3 行，未越界 ✔ |

`NeblinkPresenceService.scala` 唯一改动逐字：
```
@@ -525,6 +525,7 @@
        .proxy(java.net.ProxySelector.of(null)) // bypass HTTP proxy for P2P
+       .connectTimeout(java.time.Duration.ofMillis(PresenceCandidateProbe.MaxConnectTimeoutMs)) // 黑洞候选的 TCP 建连不再无限在飞
        .build()
```

改动面语义核对（逐字读 diff，非转述）：
- `NeblinkService.scala` 3 处（`:332-340` upsertPeer / `:393-399` handleAnnounce / `:453-459` handleHandshake）全部改为过 `PresenceCandidateProbe.mergeObserved(prev, finalPeer)`，名册腿（`endpoints` 非空）原样返回 ⇒ 零行为变化。
- `NeblinkClient.scala:292-298`：`detectLocalEndpoints` 结果过 `sanitizeReported`，过滤为空原样返回。
- 新件 `PresenceCandidateProbe.scala` 纯逻辑：无 socket / 无 HTTP / 无后台任务 / 无全局可变状态（`:69-166` 逐字可核），不 import 76417e90 的任何新类型。

### 5.4 一处对 p3 陈述的更正（事实层面，不影响验收）

p3 fix.md §5⑦ 写「`reachLedger = Some` 属合并后读数断言」。实测：负证据账本已是进程全局单例——
`RemoteExecutor.scala:305` `private def reachLedger: P2pReachability = P2pReachability.current`
（`P2pReachability.scala:171 def current = ledger`）。⇒ 负证据 TTL 当前生产实例已在生效，
不需要等 76417e90 合入；§3.2 的「探针 2–5 跳过 p2p」正是它的运行期实证。
（`grep "reachLedger = Some" GatewayMain.scala` 确实无命中，但那是另一套装配写法，不构成功能缺失。）

---

## 6. 已证 / 未证清单

已证（本节点实测）
1. 回落 relay 未退化：运行期 5/5 成功 + `sendDataOrRelay`（DropboxService.scala:371）及整个 `core/tools/` 零 diff。
2. 代码面全绿：clean 全量 742 sources 编译成功；11 spec 91 用例全过（两轮一致）。
3. 零越界：5 文件改动，`GatewayMain` / `PresenceDialPool` / `NeblinkRelayTunnel` / `core/tools/` / `DropboxService` 全零 diff；`NeblinkPresenceService.scala` = 1 增 0 删。
4. 新件为纯逻辑件（无探测、无 IO），与并行批零 import 耦合。
5. 负证据账本在当前生产实例已生效（探针 2–5 跳过 p2p）。
6. `connectionsHasKey=true` = 0 且原因已锁死（实例 jar 12:42 < 代码 15:03/15:04，禁重启）。

未证（客观取不到，照实列）
1. 本机 → KAI:6138 直连：不通（宿主 tailscale Stopped），待作者执行 p1 清单 A 步。
2. `connectionsHasKey=true` > 0：取不到——新代码未上机（禁重启）；生效后按 §2.3 命令取。
3. P2P 命中率与 P2P 往返延迟：P2P 命中 0 条（端口未通）；且审计行无延迟字段，逐条 P2P 延迟在此格式下结构性不可取（relay 延迟已用工具耗时替代测量并逐条给出）。
4. 「黑洞候选被跳过 / 可通候选被选中 / 失败计入负证据 TTL」三条 spec 断言：本 worktree 无对应 spec（属 76417e90）；其中「失败计入负证据」已由运行期实证替代，另两条待合并后跑其 spec。
5. 候选集保真的生产读数（入站 presence 后 `peers.endpoints.size` 不再掉 0、`RemoteExecutor.addressKey` 不抖）：未取——同样受「新代码未上机」阻断。
6. p1 基线 `0 / 86,119` 的 86,119：本轮同口径复测为 86,017（差 -102），p1 未给取数命令与文件 ⇒ 差异无法归因，按「基线未复现」记录（结论方向不变：true 恒 0）。

---

## 7. 给作者的一页式操作与验证清单（从 p1 提取并去重 / 按依赖排序）

> 以下命令本批一条都没执行（无宿主权限 + brief 明令不改宿主面）。执行前请自行确认。

第 1 步（唯一有效路径 · macOS 本机）恢复 tailnet
```bash
open -a Tailscale          # GUI 版：打开并 Connect
# 或 CLI 版：
tailscale up
# 验证（期望打印 100.x.y.z，state 不再是 Stopped）：
tailscale status
tailscale ip -4
# 回滚：tailscale down
```

第 2 步 验证 tailnet 层（与 6138 无关的一关，先过）
```bash
tailscale ping 100.91.165.120
# 期望：pong from kai (100.91.165.120) via DERP(...) 或 via 192.168.1.x:41641
```

第 3 步 验证 6138 直连（tailnet 通之后）
```bash
nc -vz -w 3 100.91.165.120 6138
# 期望：succeeded；若是 refused ⇒ 查 KAI 侧进程监听（p1 L2 已证 0.0.0.0:6138 正常）
# 若仍 timed out ⇒ 回第 2 步（tailnet 未真通），别急着查代码
```

第 4 步 发版 / 重启实例（让 p3 的两个 commit 真正上机）
```bash
# 前提：本分支合并到 main 后按发版流程出包；当前生产跑的是 12:42 的 jar，新代码未加载
# 生效判据（重启后立刻可跑）：
grep -c 'presence sendData.*connectionsHasKey=tru[e]' ~/.stand/logs/stand.log   # 期望 > 0
grep 'Presence dial failed for KAI' ~/.stand/logs/stand.log | tail -3           # 候选数应 >=2
```

第 5 步（可选 · 只在需要 LAN 兜底时）KAI 侧防火墙
```powershell
# 只读确认：
Get-NetConnectionProfile | Select Name,InterfaceAlias,NetworkCategory
# 放行（最小改动，回滚 = 删规则）：
New-NetFirewallRule -DisplayName "Nebflow TCP 6138" -Direction Inbound -Protocol TCP -LocalPort 6138 -Action Allow -Profile Public
# 回滚：
Remove-NetFirewallRule -DisplayName "Nebflow TCP 6138"
```
> 注意：即便放开防火墙，LAN 直连仍需两机同网段——当前 `100.65.0.0/18`（Mac）vs `192.168.1.0/24`（KAI）不同段，LAN 兜底在现有拓扑下不可能成立。第 1 步是唯一有效路径。（与 p1 同结论，无新增项。）

---

## 8. 产物与边界声明

- 本文件：`evidence/presence-direct-261104/verify/verify.md`
- 原始日志（同目录，git-ignored）：
  - `logs-sbt-20261104_151349.log`（增量 compile + 2 spec 首跑）
  - `logs-clean-20261104_151513.log`（clean 全量编译 + 11 spec，主证据）
  - `logs-specs-full-20261104_152056.log`（11 spec 逐 spec 行复跑）
- 边界：未 kill 任何 sbt/java/nebflow 进程；未重启实例；未改宿主防火墙；未 `tailscale up`；未改源码；`git status --porcelain` 为空。唯一「副作用」= 5 次真实远端只读探针（`echo`），其审计行已在本文件 §3.2 点名标注，避免下游误当生产 P2P 命中。
