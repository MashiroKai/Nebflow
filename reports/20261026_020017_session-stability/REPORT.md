# 稳定性批 · 大文件传输掉登录 + 长连接 403 泛滥 —— 归因与修复报告

日期：2026-10-26　分支：`delegate/delegate-nebflow-1e1489bf`（基点 3dfa54b03）
症状（作者 01:09 两条）：① 5MB+ 大文件传输中途弹「登录状态失效」要求重登；② 长连接期间 `/api/nf-file` 票据面、token 用量等纷纷 403。

---

## 1. 归因链（证据 → 机械链）

### 1.1 双会话平面（TTL 现状核查）

| 平面 | 实现 | TTL | 重启行为 |
|---|---|---|---|
| relay ingress（link-server，Rust） | `require_user`（`~/.stand/projects/link-server/src/routes.rs:558`） | access JWT 15min + refresh 30d（`auth.rs:10-12`）；#520 sliding admit（`:563-584`）**已在生产**（deploy 859336d，2026-10-20） | 无状态 JWT，重启不丢 |
| 引擎宿主会话（Scala） | `GatewaySessionStore.scala` | 30d 绝对 + 7d 滑动闲置窗 | **纯内存，引擎每次重启清零**（本实例 2026-10-25 00:40–02:27 重启 13 次，log 为证） |

症状②的直接根：引擎重启清零 ⇒ 全部引擎鉴权面 403。日志证据：`~/.stand/logs/stand.2026-10-25.0.log` 中 40 条「WebSocket auth failed from 127.0.0.1」+ tunnel-per-ip-cap 429 风暴。

### 1.2 症状①机械链：隧道上行无优先级 → 控制帧饿死

`PortalTunnelBridge.scala` 的 `RelayFrameSender`（修前 ：849+）只有**单一非公平监视器**一个车道：5MB+ 传输的 bulk `body_frame`（256KiB→base64 ≈349K 字符/帧）逐帧占住发送位，控制帧（隧道活性 pong / 浏览器控制台 WS 的 `ws_frame` pong / `http_response` 头）顶在队列。三族后果同源：

1. relay 侧 2-miss 判隧道死 → 撕隧道；
2. 前端 3-miss 自我逐出（heartbeat shelter 只包 `/api/nf-file` 的 **fetch** 腿，不包 `<img>` 腿）；
3. ingress `PORTAL_RESP_HEADER_TIMEOUT=15s`（`portal_ingress.rs:53`；ws_idle=90s `:55`）超窗回 504 → 前端把**拥堵**误读成**凭据死** → 登录重定向/横幅。

### 1.3 症状②机械链：403 无法分形 + 无静默重座

- 修前引擎 403（`RestApiCtx` JSON `{"error":"Unauthorized"}`，**无 reason 头**）与 relay 门 403 JSON 同形 → 前端无法区分「引擎会话没了（可静默重座）」和「relay 凭据死（须重登）」。
- `sessionGate.noteAuthRejected` 直接横幅 + reload，不走 WS 臂已有的静默重座链（ws.js `remintHostSessionAndReload`）。
- 会话保活 12h 一拍太稀：WS-only 长会话的滑动续期最长滞后 12h。

### 1.4 免修面（核查后不动）

`nfTicket.js` 票据面已有自愈（静默退避 + 取数腿 reMint 一次），本批不改。

---

## 2. 修复面（5 个生产文件 + 测试件）

| 文件 | 修复 |
|---|---|
| `src/main/scala/nebflow/neblink/PortalTunnelBridge.scala` | `RelayFrameSender` **双车道让位**：`ReentrantLock(true)`（公平锁=确定性基座）+ `laneGate` 条件 + `controlWaiters`（AtomicInteger，控制线程拿锁前自增）+ `bulkCredit`；`isControlSized`（≤64KiB 字符=控制车道）单点判定；bulk 等待条件 `controlWaiters>0 && bulkCredit<ControlGrantCap(16)`（1s 有界重查），控制放行 credit+1、bulk 放行清零；构造参数 `controlGrantCap`（测试缝）。同流顺序由单 fiber 逐帧等完成保证，跨流重排与修前同权 |
| `src/main/scala/nebflow/gateway/RestApiCtx.scala` | 新增 `AuthRejectedReason`（`X-Nf-Reason: credential-required`）+ `authRejected` helper；withAuth/gate/bootstrapGate 三处换用 |
| `src/main/scala/nebflow/gateway/NfFileRoutes.scala` | 新增 `authDenied`（403 + `X-Nf-Reason: credential-required`）；nfTicketRoutes + nfAuthcheckRoutes 换用 |
| `src/main/resources/web/js/ws.js` | 导出 `attemptSilentReseat()`（remintHostSessionAndReload 的单飞包装，`reseatInFlight` 去重）；保活注释 12h→1h |
| `src/main/resources/web/js/sessionGate.js` | `noteAuthRejected` 换代：**冷却 → 静默重座优先**（`reseatImpl(source)`，动态 import ws.js，`RESEAT_CAP_MS=8s` 竞速封顶）→ true=导航接管不横幅；否则横幅+reload。`KEEPALIVE_INTERVAL_MS` 12h→**1h**。`_sessionGateTestHooks` 增加 `setReseat` |

测试件：`RelayFrameSenderPrioritySpec.scala`（P1 越队/P2 零回归/P3 信用上限/P4 车道判定，公平锁 FIFO 泊位可钉）、`PrefixQueueOrderProbe.scala`（修复前后同件对照探针，lane-aware fixture）、`RelayFrameSenderTimingHarness.scala`（NF_TIMING_HARNESS=1 手动跑）、`tests/session-gate.spec.mjs`（①c 重座成功零横幅/①d 悬挂 8s 封顶/⑤ 1h 拍）、authgate×2 加 `setReseat(false)` 确定化缝、NfTicketRoutesSpec/ApiGuardFollowupRoutesSpec 加 `X-Nf-Reason` 断言。

---

## 3. 证据

### 3.1 探针红→绿（同件对照，饥锇缺陷本体）

`PrefixQueueOrderProbe.scala`（同一探针文件，断言：bulk 在飞+排队时控制帧必须先于队列排空完成）：

| 被测代码 | 结果 |
|---|---|
| **修前**（HEAD 3dfa54b03，/tmp/nf-prefix-evidence，`git diff` 证实 sender 代码为 pristine HEAD） | **RED**：`TimeoutException: 10 seconds`（控制帧等不到发送位）@ 2026-10-26 02:01:04 |
| **修后**（本分支工作区） | **GREEN**：0.367s @ 2026-10-26 01:50:40 |

fixture 注：修后控制帧**改变调用序**（这正是被观测的行为变化），故手工完成闸按**车道**派发（控制帧吃 control 队列）——断言语义两世界完全一致。

### 3.2 时延对照（真 socket harness，16×256KiB≈5.6MB 洪峰 + 300ms 控制拍，0.8MB/s 节流对端）

`sender-timing-prefix.jsonl` / `sender-timing-postfix.jsonl`（本目录）：

- 两个世界控制帧全部送达（prefix 15/15、postfix 10/10 截至洪峰结束），totalMs 同量级（prefix 尖峰 413–481ms、postfix 尖峰 480–639ms ≈ 1 个在飞 bulk 帧的写完时长）。
- **如实申报**：该 harness 的 bulk 泵是顺序 send（队列深 ≤1），**复现不了深队列饥饿**（那是探针的确定性深队列场景）；harness 的读数证明的是「修后控制面时延有界（≈1 在飞帧 + 自身写完，毫秒级 vs ingress 15s 窗三个数量级裕量）、bulk 吞吐无回归」。postfix 中出现 5 帧 1ms 完成的 bulk = 公平锁在控制拍间隙把发送位让给就绪 bulk（FIFO 税），有界且被 P3 信用上限防饿死。

### 3.3 门禁读数（全绿，除两件申报）

| 门禁 | 读数 |
|---|---|
| `RelayFrameSenderPrioritySpec` | 4/4（P1/P2/P3/P4） |
| `PrefixQueueOrderProbe`（修后） | 1/1 |
| `PortalTunnelBridgeSpec`+`HeartbeatSendSpec`+`SendCycleSpec`（WS 面回归） | 32/32（合并跑，01:57:54） |
| `NfTicketRoutesSpec`+`ApiGuardFollowupRoutesSpec` | 27/27（含新 X-Nf-Reason 断言） |
| `node tests/session-gate.spec.mjs` | 23/23 |
| `node tests/authgate-projects-403.spec.mjs` | 7/7 |
| `node tests/authgate-wiki-graph-403.spec.mjs` | 20/20 |
| `node tests/nf-ticket-403-heal.spec.mjs` | ALL PASS |
| `node tests/social-panel.spec.mjs` | 138/139 —— 1 fail = 该 spec 自带的 **SCOPE whitelist** 审计（它校验的是 social 批的文件面；本批 14 个在途文件按设计就在其白名单外）。SAG 段（sessionGate 交互）全绿 |
| `node tests/imgcard-click-safari-compat.spec.mjs` | 22/31 —— **与 HEAD 基线指纹完全相同**（在 main 工作区跑 HEAD 代码 = 同样的 chromium D3 fail + webkit 7 fail）：**修前已红，非本批回归**。本批在站 D 加了 `setReseat(false)` 确定化缝（消除本批作为变量）；D3 红的真因 = authgate 批拆掉铸票 403→sessionGate 漏斗后该 lab 无横幅触发源，另案 |
| `node scripts/check-js-types.mjs` | 260 = 基线 260，零新增 |
| `bash scripts/boot-smoke.sh <新 assembly jar>` | **PASS** boot_ready=4s checks=11（portal+health+WS 101 握手+teardown 零残留） |

注：worktree 无 node_modules，checkJs 以主仓 node_modules 符号链接运行（typescript 5.5.4 pinned 版本不变）。

---

## 4. 边界申报

1. **link-server 仓未改**（另一仓，独立发布面）：sliding admit 已在生产（deploy 859336d）；本批不动它的代码。
2. **已申报加重因子/遗留**：① ingress `PORTAL_RESP_HEADER_TIMEOUT=15s` 与浏览器 shelter 仅包 fetch 腿（`<img>` 腿裸奔）——修后控制帧不再被顶死，两因子不再触发，但机制上仍在（如需收口另案）；② `attemptSilentReseat` 的探链（portal status → console-ticket → consoleUrl）在本地直连源两探 404 即 false，横幅兜底路径保持；③ 引擎会话纯内存、重启清零的**持久化**未做（本批以「403 可分形 + 静默重座 + 1h 保活」把重启的可见代价降到无感，持久化另案）。
3. 基线已红件：imgcard D3（含 webkit 面）与 social-panel SCOPE 审计，见 §3.3，不属本批修复范围。

## 5. 附：证据文件

- `sender-timing-prefix.jsonl` —— 修前时延落盘（HEAD 代码）
- `sender-timing-postfix.jsonl` —— 修后时延落盘（本分支）
- 探针红/绿运行读数：见 §3.1（时间戳可复核 sbt 输出）
