# 本机近路（loopback nearpath）· 实测台与四道闸取证 FINDINGS

> 批：近路批 · 节点「近路-实测台」 · 2026-11-04
> 代码基线：worktree `delegate/delegate-nebflow-be138470` 的 **HEAD 56720568e**（工作区 `src/**` 有别的节点未提交的改动且 Compile 失败，见 §9 局限 L1）
> 被测代码：`src/main/resources/web/js/ws.js`（HEAD 与工作区**逐字节相同**，sha256 `172fc7f3f6047e3cc9bc428f2fdfd17da4d4a2bd406bcb7de7b6dd15a272dd5b`）
> 隔离引擎：`java` 直启，`--port 6410`、`STAND_HOME=/tmp/nearpath-fire/rig-home`、源码树 `/tmp/nearpath-fire/engine-src`（`git archive HEAD`）
> 生产引擎（pid 41715 / 6138）全程零接触

---

## 0. 结论速览（先给判词）

| 项 | 结论 |
|---|---|
| **Chromium（151.0.7922.34）** | **四闸全通**：`/api/portal/status` → `loopback-info` → 跨源探测 `/api/health`（deviceId 相等）→ 铸票 → **整页真跳到 `http://127.0.0.1:6410/?ticket=…`**，落地页 `#input` 可见（截图 `red/screenshots/chromium-portal-origin-…-V0b-direct.png`） |
| **WebKit（26.5）** | **卡死在闸③**：跨源探测 `http://127.0.0.1:<port>/api/health` 被**混合内容策略**拦下 —— `[blocked] The page at https://mystand.space/ requested insecure content from http://127.0.0.1:6411/api/health. This content was blocked…`；`sessionStorage['stand_ws_nearpath_loopback_miss'] = {"at":…,"reason":"probe_unreachable"}`（ws.js:805） |
| **生产「0 命中」根因** | 落在**浏览器侧第三道闸**（WebKit/Safari 的混合内容），不在服务端、不在铸票、不在 deviceId、**也与 presence/P2P 无关**（§6 实测） |
| **四闸可达/不可达总账** | ①②④ 两端浏览器都能过（WebKit 也过 ①②，并有 ④ 之前的全链证据）；③ 是唯一因引擎差而不同的闸 |
| **可复跑断言** | `ASSERT nearpath-gate-chain-zero-presence: PASS`（闸链窗口内 presence 行数 = 0，§6/§7） |

---

## 1. 台子选路：a / b / c 三条，选了 a，放弃 b，c 见 §9

### (a) 自建「门户 ingress」+ 假中继 + 隔离引擎 —— **选中**

`rig/rig.mjs` 一件事做三件：

1. **https 门户 ingress（:7443，自签证书）**：页面 origin 是 https（真实门户就是 https；混合内容/PNA 只有 https 面能复现）。
   - `/api/portal/status` → rig 自己答（等价 link-server 门户面：`{"boundHost":{"deviceId":"…"}}`）；
   - `/api/portal/console-ticket` → rig 发 `ConsoleTicketMint` 帧、收 `ConsoleTicketResult`、回 `{"consoleUrl":…?ticket=…}`（等价 link-server 的 `POST /api/portal/console-ticket`）；
   - **其余一切**（`/`、`/js/**`、`/api/**`、`/ws`）→ 按 **portal wire 契约**走隧道帧给引擎（`http_request`/`body_frame`/`http_response`、`ws_open`/`ws_frame`/`ws_close`），逐字照 `PortalWireCodec.scala`。
2. **假中继（:18990，同进程）**：`/api/device/session` 交换 token、`/api/device/relay-ws` 收引擎拨入、转帧、回 `{"type":"pong"}` 保心跳、下发铸票帧。
3. **环回探测观测代理（:6411）**：闸③ 的跨源探测落在这里 —— 逐条记录 `Origin` / `Access-Control-Request-Private-Network` / 预检头，再原样转发给引擎 `:6410`，并按 `probeCorsHeaders`（HealthRoutes.scala:238-255）同一白名单复刻 CORS/PNA 应答头。

**为什么需要自建 ingress**：ws.js 的四道闸只在**经中继加载的页面**上成立（闸① 读 `/api/portal/status` 的 `boundHost.deviceId`，只有门户面才有；直连腿该端点 404 ⇒ 一进链就短路）。而真实门户页面是 https —— 只有 https 面才能让「https 页面 fetch `http://127.0.0.1`」这件事真实发生。

**页 origin 怎么做成生产 origin（两次失败后的第三条路）**：
- ❌ `--host-resolver-rules=MAP mystand.space 127.0.0.1:7443`：本机 Playwright/Chromium 151 **不生效**，四种拼法（`MAP host` / `MAP host:443` / 加 `--disable-features=DnsOverHttps` / 加 `--ignore-certificate-errors --no-proxy-server`）全部打到了**真实公网 mystand.space**（实测：ingress 零命中、页面是官网首页，截图 `red/screenshots/chromium-portal-origin-…` 之外的早期试跑已覆盖；脚本 `rig/tmp-hostsresolver-test.mjs` 保留为反例证据，`node tmp-hostsresolver-test.mjs` 可复验 hits=0）。
- ❌ 本机非回环地址 origin（`https://100.65.49.141:7443`）：该 100.64/10 接口上 node 监听**外部读不到**（`curl` 退出码 52 空应答；同 IP 上 java 生产引擎 6138 却可达），浏览器 `net::ERR_CONNECTION_CLOSED`。
- ✅ **改造为 rig 转发代理（:7444）**：Chromium/WebKit 都以 `proxy: http://127.0.0.1:7444` 启动，`mystand.space` 的 CONNECT 一律落到本机 ingress（自签证书 SAN 含 `DNS:mystand.space`）⇒ **页面 origin 逐字等于生产 origin `https://mystand.space`**，引擎 CORS 白名单默认就含它；而 `127.0.0.1` / `localhost` 的 CONNECT 原样直连 ⇒ 闸③ 的探测仍是「浏览器 → 本机端口」真链路（不被代理狸猫换太子）。

### (b) 本机起 link-server —— **放弃**

`~/.stand/projects/link-server`（Rust，独立仓，只读）能给出真门户面，但要：cargo 依赖 + 真 DB / user JWT / logto 会话 / 无 TLS 面（生产前面还有一层反代），且它自己一套凭据模型要造。**台子不需要它也能把四道闸逐条钉死**（闸① 只读 `boundHost.deviceId` 一个字段，闸④ 只读 `consoleUrl` 里的 `ticket`），所以选成本更低、可控性更高的 (a)。若后续节点要验「link-server 侧的铸票/绑定闸」，仍可另起 (b)，rig 的 ingress 面可整块替换。

### (c) 真实生产门户只读 —— 见 §9 局限 L4（登录墙，拿不到）

---

## 2. 台子拓扑与端口表

```
浏览器 (Chromium/WebKit, proxy=127.0.0.1:7444)
   │  https://mystand.space  (CONNECT → 本机 ingress)
   ▼
rig ingress  https :7443 ──┬─ /api/portal/status           （rig 自答：boundHost.deviceId）
（自签证书 DNS:mystand.space）├─ /api/portal/console-ticket  （rig 转 ConsoleTicketMint 帧）
                            └─ 其余全部 → portal wire 隧道帧
                                     │
假中继 :18990 (ws /api/device/relay-ws) ◀── 引擎拨入
                                     ▼
隔离引擎 java :6410   home=/tmp/nearpath-fire/rig-home
                                     ▲
环回探测观测代理 :6411 （浏览器闸③ 的 http://127.0.0.1:6411/api/health → 记录 → 转发 :6410）
```

| 端口 | 用途 | 谁在听 |
|---|---|---|
| **6410** | 隔离引擎（本节点唯一自测实例端口） | rig spawn 的 java（**每次结束按自己 spawn 的 pid 精确 kill**） |
| 6411 | 闸③ 环回探测观测代理（可切 `pass/http500/wrong_id/pna_refuse`） | rig（node） |
| 7443 | 门户 ingress（https，自签） | rig（node） |
| 7444 | 转发代理（把 `mystand.space` 的 CONNECT 打到 :7443） | rig（node） |
| 18990 | 假中继（HTTP device/session + WS 隧道） | rig（node） |
| ~~6138~~ | **生产引擎，全程零接触**（pid 41715，sbt run） | 不碰 |

---

## 3. 四道闸逐闸结论（file:line / 判据 / 实测 / 原始错误）

判据读取面：`sessionStorage['stand_ws_nearpath_loopback_miss']` 原文（`noteLoopbackMiss`，`ws.js:757-765`）+ `console.info '[nearpath] loopback probe miss: <reason>'` + 页面是否真跳走。

### 闸①　`/api/portal/status` 的 `boundHost.deviceId`（ws.js:776-781）

| 项 | 结论 |
|---|---|
| 判据 | `status.ok` 且 `boundHost.deviceId` 是非空 string |
| Chromium | **过**（200 + deviceId） |
| WebKit | **过**（同一条链；WebKit 的 V1/V3 变体都跑到闸②/闸③ 才断，反证 ① 已过） |
| 不过时的分支 | `ws.js:779` `portal_status_http_${status}`；`ws.js:781` `no_bound_host` |
| 实测原文 | V1: `{"at":1793763107000,"reason":"portal_status_http_404"}`；V2: `{"…","reason":"no_bound_host"}`（`red/results-chromium-portal_*.json`） |
| 备注 | 闸① 是**门户腿独有面**：跳到环回腿后同一页面再跑链，该端点 404 ⇒ 立刻短路（实测里每次 hit 后落地页都记 `portal_status_http_404`，与设计同构） |

### 闸②　`/api/health/loopback-info` 端口锚（ws.js:782-787）

| 项 | 结论 |
|---|---|
| 判据 | `info.ok` 且 `port` 是 1..65535 的整数（服务端 `HealthRoutes.scala:97-110`，`withAuth` 门） |
| Chromium | **过**（200 `{"port":6410}`；引擎日志 `nearpath: loopback-info served (relay-leg page probing local port anchor, host=mystand.space)`） |
| WebKit | **过**（同样拿到 port，随后死在闸③） |
| 不过时的分支 | `ws.js:785` `loopback_info_http_${status}`；`ws.js:787` `no_loopback_port` |
| 实测原文 | V3: `{"…","reason":"loopback_info_http_403"}`；V4: `{"…","reason":"no_loopback_port"}` |
| 服务端侧证据 | rig 引擎日志 `11:34:17.848 … nearpath: loopback-info served (… host=mystand.space)`（全批 8 次，见 `red/summary.json` `loopbackInfoServed`） |

### 闸③　环回探测 `http://<host>:<port>/api/health` 的 deviceId 相等（ws.js:794-809，双 host 各 4s）

| 项 | 结论 |
|---|---|
| 判据 | `candidate.ok` 且响应 JSON 的 `deviceId` == 闸① 的 deviceId；host 依次 `127.0.0.1`、`localhost` |
| **Chromium** | **过**（探测命中 200，`deviceId` 相等）。观测代理记录：`{"method":"GET","url":"/api/health","origin":"https://mystand.space","acrm":null,"acrh":null,"acrpn":null}` —— **没有 OPTIONS 预检、没有 `Access-Control-Request-Private-Network` 头** |
| **WebKit** | **不过**：混合内容策略在**网络层之前**就拦掉了 |
| 不过时的分支 | `ws.js:805` `probe_http_${status}` / `probe_unreachable`；`ws.js:809` `device_mismatch (${probeHost})` |
| 实测原文（WebKit，portal origin） | `[warning] [blocked] The page at https://mystand.space/ requested insecure content from http://127.0.0.1:6411/api/health. This content was blocked and must …`；`[pageerror] Fetch API cannot load http: /127.0.0.1:6411/api/health due to access control checks.`；同一秒对 `http://localhost:6411/api/health` 重复一次；随后 `[info] [nearpath] loopback probe miss: probe_unreachable`；`sessionStorage` 原文 `{"at":1793763285741,"reason":"probe_unreachable"}` |
| 实测原文（WebKit，回环 origin `https://127.0.0.1:7443`） | 同一条混合内容拦截（`The page at https://127.0.0.1:7443/ requested insecure content from http://127.0.0.1:6411/api/health`）⇒ **不是「公网 origin 才拦」，https→http 一律拦** |
| 实测原文（Chromium，分支面） | V5: `probe_unreachable`（端口没人听）；V6: `probe_http_500`；V7: `device_mismatch (127.0.0.1)`；V8（模拟对端不回 ACAPN）: `probe_http_403` |

### 闸④　铸票 + 整页跳（ws.js:810-825）

| 项 | 结论 |
|---|---|
| 判据 | `POST /api/portal/console-ticket` 200、`consoleUrl` 非空、其 `?ticket=` 存在 ⇒ `window.location.href = http://${probeHost}:${port}/?ticket=…` |
| Chromium | **过**：页面真跳到 `http://127.0.0.1:6410/?ticket=VpJmo3-…`（V0b-direct，落地页 `#input` 可见）；V0-full 跳到 `http://127.0.0.1:6411/?ticket=…`（闸③ 观测代理赢了探测，跳转目标与探测命中面一致 —— 正是 `ws.js:822-824` 的设计） |
| WebKit | 未到（闸③ 已断）；但铸票面本身在 WebKit 下也是同一条 HTTP 链，无引擎相关性 |
| 不过时的分支 | `ws.js:817` `mint_http_${status}`；`ws.js:819` `mint_no_url`；`ws.js:821` `mint_no_ticket` |
| 实测原文 | V9: `mint_http_403`；V10: `mint_no_url`；V11: `mint_no_ticket` |
| 服务端侧证据 | 引擎日志 `console ticket redeemed: session established (accountSub=sub-rig-owner, origin=relay-mint, …)` —— 全批 18 条（`red/summary.json` `ticketsRedeemed`），其中每条 hit 一次 |

### 全矩阵（24 次运行，0 条与预期不符）

见 `red/summary.json`（原始 JSON）与下表（`node rig/summarize.mjs` 直接重生成）：

| engine | axis | variant | outcome | miss.reason |
|---|---|---|---|---|
| chromium | portal-origin(https://mystand.space via rig proxy) | V0-full | **hit** | — |
| chromium | portal-origin | V0b-direct | **hit**（落地 :6410） | — |
| chromium | portal-origin | V1-g1-404 | miss | `portal_status_http_404` |
| chromium | portal-origin | V2-g1-nobound | miss | `no_bound_host` |
| chromium | portal-origin | V3-g2-403 | miss | `loopback_info_http_403` |
| chromium | portal-origin | V4-g2-port0 | miss | `no_loopback_port` |
| chromium | portal-origin | V5-g3-unreachable | miss | `probe_unreachable` |
| chromium | portal-origin | V6-g3-http500 | miss | `probe_http_500` |
| chromium | portal-origin | V7-g3-deviceid | miss | `device_mismatch (127.0.0.1)` |
| chromium | portal-origin | V8-g3-pna-sim（模拟） | miss | `probe_http_403` |
| chromium | portal-origin | V9-g4-mint403 | miss | `mint_http_403` |
| chromium | portal-origin | V10-g4-nourl | miss | `mint_no_url` |
| chromium | portal-origin | V11-g4-noticket | miss | `mint_no_ticket` |
| chromium | loopback-origin(https://127.0.0.1:7443) | V0-full / V0b-direct | **hit** | — |
| chromium | loopback-origin | V7-g3-deviceid | miss | `device_mismatch (127.0.0.1)` |
| **webkit** | portal-origin | V0-full / V0b-direct / V7 / V9 | miss | `probe_unreachable`（混合内容） |
| **webkit** | portal-origin | V1-g1-404 | miss | `portal_status_http_404`（闸① 先断） |
| **webkit** | portal-origin | V3-g2-403 | miss | `loopback_info_http_403`（闸② 先断） |
| **webkit** | portal-origin | V5-g3-unreachable | miss | `probe_unreachable` |
| **webkit** | loopback-origin | V0-full | miss | `probe_unreachable`（混合内容） |

---

## 4. 六种可能性逐个坐实（哪些被证实、哪些被排除、哪些没复现）

| 可能性 | 判决 | 证据 |
|---|---|---|
| **混合内容拦截** | ✅ **证实（WebKit = 生产命中「0」的原因）** | WebKit 控制台原文 `[blocked] … requested insecure content from http://127.0.0.1:6411/api/health`；Chromium 同一条 fetch 直接 200（引擎/观测代理记录），无任何 blocked 行 |
| **PNA / LNA（Local Network Access）预检失败** | ❌ **本轮未复现（Chromium 151 未发预检）** | 观测代理全批记录里 `acrm/acrh/acrpn` **恒为 null**、无一条 `OPTIONS`；探测直接以 GET 200 通过。原因见 §9 局限 L3（探针目标是 127.0.0.1 ⇒ 环回地址空间，Chromium 视作 local，不触发 private-network 预检；`pna_refuse` 变体只能**模拟**「对端不回 ACAPN」的结果面 = `probe_http_403`）。服务端 ACAPN 头本身已由审计报告 §2.3 用 curl 双 origin 验过 |
| **CORS / 预检头缺失** | ✅ 机制证实（可致 `probe_unreachable`），但**不是生产根因** | V8 模拟（回 403 且不回 ACAPN）⇒ `probe_http_403`；数据库里 CORS 头缺失时的形态是浏览器 `blocked by CORS policy: No 'Access-Control-Allow-Origin' header`（本轮早期试跑的原始串，`red/` 早期快照可见 `results-chromium-loopback_…` 里的同名 console 行）。生产上 `probeCorsHeaders` 已对 `mystand.space` 放行（审计 §2.3） |
| **deviceId 不等** | ✅ 分支可复现，非生产根因 | V7 ⇒ `device_mismatch (127.0.0.1)` |
| **铸票失败（4xx）** | ✅ 分支可复现，非生产根因 | V9 ⇒ `mint_http_403`；且真实铸票在 rig 里成功 18 次（引擎 `console ticket redeemed`） |
| **iframe/sandbox 拦住整页跳** | ❌ 排除 | Chromium 裸页（非 iframe）真跳成功，落地 URL 带 `?ticket=`，引擎侧立刻出现 `console ticket redeemed` |
| **AbortSignal.timeout 4s 不够** | ❌ 排除（本机实测毫秒级） | 闸③ 探测在本机 ~20ms 内返回；闸链全程 < 1.1s（`red/summary.json` 的 `elapsedMs`：Chromium 命中 649–1047ms，拦截分支 550–900ms） |

---

## 5. 服务端侧证据（隔离引擎日志片段）

`red/engine-2026-11-04T03-34-11-724Z.log`（全量，近路相关行摘录）：

```
11:34:16.845 INFO nebflow.gateway  - console ticket redeemed: session established (accountSub=sub-rig-owner, origin=relay-mint, …)   ← 首次兑换（门户腿登录）
11:34:17.848 INFO nebflow.rest-api - nearpath: loopback-info served (relay-leg page probing local port anchor, host=mystand.space)  ← 闸② 真被调用
11:34:17.878 INFO nebflow.gateway  - console ticket redeemed: …（V0b 的铸票兑换）
11:34:19.419 INFO nebflow.gateway  - console ticket redeemed: …（V0-full 的铸票兑换，落 :6411）
```
- `loopback-info served` 全批 **8 次**，`console ticket redeemed` **18 次**（两条兑换链：门户腿登录 + 每次命中一枚中继票）。
- 引擎侧**看不到**闸③/④ 的失败原因（`red/summary.json` 里 `engineNearpathLines` 在拦截分支上只有闸② 那一行）—— 与审计 §2.2 的可观测性缺口一致，本轮把它坐实：**miss 只活在浏览器 sessionStorage**，服务端日志在白屏侧是静默的。
- 台子额外测到一台真机关：`tunnel-per-ip-cap`（`ConnGuard`，默认 1024 请求/60s/IP）。一次控制台冷加载会经隧道打 ~800+ 个资源请求，连跑多轮必撞 429 ⇒ 台子把 `STAND_GATEWAY_CONN_GUARD_TUNNEL_PER_IP_CAP/TOTAL_CAP` 放宽（**只放宽预算，不放宽任何判据**），生产侧该读数见 §9 局限 L4。

---

## 6. presence / P2P 平面旁证（并入派发侧线索，本轮补充令）

**结论：近路闸链与 presence/P2P 平面零因果。派发侧线索的两分法 —— 「(a) 环回被迫走 P2P 而失败」与「(b) P2P 本该连上却连不上」——两者都不是：P2P 是旁路平面，KAI 不可达只影响跨设备取件腿，不影响环回近路闸。**

### 6.1 时间相关性（生产日志重数，只读快照）

产物：`red/production-presence-correlation.txt`（含每条命令与读数）。**只认行首时间戳的行**（日志里混有工具命令回显，含同名关键字，必须先滤掉 —— 首次数出 66940/203 就是被回显污染的，滤后 68582/198）。

| 信号 | 全天总数 | 09 时 | 10 时 | 11 时 |
|---|---|---|---|---|
| `presence sendData` | **68 582** | 24 383 | 27 804 | 16 398 |
| `Presence dial failed` | **198** | 69 | 82 | 47 |
| `connectionsHasKey=false` | 68 580 | — | — | — |
| `connectionsHasKey=true` | **0** | — | — | — |

近路点事件（同一日志，引擎侧可观测的全部三条）：
- `09:09:54.840 console ticket redeemed (origin=relay-mint)` ← 门户首登
- `09:10:01.655 nearpath: loopback-info served (host=unknown)`
- `10:22:56.928 nearpath probe miss: enrolled=false` ← **LAN 腿**，不是环回腿

**重合/不重合**：presence 噪声是**全天连续背景**，不是聚簇。分钟粒度（`red/production-presence-correlation.txt` §4）：09:09=103、09:10=504、09:11=503、09:12=510、09:13=506、09:14=501 —— 近路事件所在的 09:10 与它前后分钟**同一量级**，没有任何尖峰或凹陷。窗口读数：兑换前 30s=56 行、兑换+loopback-info 后 60s=500 行、LAN 腿 miss 的 5 分钟窗口=2677 行 —— 全部是**背景速率**（≈8.3 行/秒），与闸事件无时间对齐关系。⇒ **不重合**（presence 噪声全天在，近路事件只发生 3 次且都嵌在同一个背景速率里）。

### 6.2 平面分离的实测确认（隔离台子，闸链评估窗口内）

台子在整批 24 轮闸链评估期间对隔离引擎日志做差分 grep（`presence sendData|Presence dial failed|connectionsHasKey=`）：

```
zero-presence assertion: PASS (presence lines in all gate windows = 0)
```

`red/summary.json` 里 `presenceInGateWindow = 0`、`zeroPresenceAssertion = "PASS"`；每条 run 记录另有 `presenceLinesDuringRun = 0`。

含义：`ws.js:767-829` 的环回闸链**全程只在浏览器 HTTP 面**——`/api/portal/status`、`/api/health/loopback-info`、跨源 `fetch http://127.0.0.1:<port>/api/health`、`POST /api/portal/console-ticket`、`window.location.href` 整页跳。它**不咨询 presence、不拨 P2P、不经 NeblinkPresenceService**，因此 presence 的成败对闸链没有因果影响（生产 65k 条 `connectionsHasKey=false` 只说明引擎↔KAI 的传输面没建起来，与浏览器这四闸是两条互不相交的平面）。

### 6.3 给下游的可复跑断言

`rig/assert-zero-presence.mjs`：

```
node rig/assert-zero-presence.mjs red/engine-<ts>.log
  → ASSERT nearpath-gate-chain-zero-presence: PASS (presence lines=0; file=…)
  → 退出码 0 = PASS / 1 = FAIL（可直接被后续节点 `set -e` 消费）

node rig/assert-zero-presence.mjs /Users/kaiyu/.stand/logs/stand.log \
     --from "2026-11-04 09:09:50" --to "2026-11-04 09:10:10"
  → ASSERT … : FAIL (presence lines=191)   ← 生产同窗口有 191 行**背景**噪声
```

⚠ 两种口径要分清：**隔离台子**的 PASS 是**因果证据**（闸链跑了、presence 一行没有）；**生产日志**同窗口 FAIL 是**背景噪声存在**的证据（KAI 传输面没建起来，8.3 行/秒匀速刷）。二者不矛盾——生产上「presence 在刷」与「闸链被混合内容拦」是同时成立的**两件独立的事**。

---

## 7. 复现步骤（一条命令）

```bash
cd /Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-be138470/reports-evidence/20261104_nearpath-fire/rig

# 0) 一次性准备（只在源码树/classpath 不存在时需要；冷启动 sbt 编译 ~5 分钟）
sh setup-engine.sh                     # git archive HEAD → /tmp/nearpath-fire/engine-src，sbt 编译 + 导出 classpath
sh make-cert.sh 100.65.49.141          # 自签证书（SAN: mystand.space/localhost/127.0.0.1/100.65.49.141）

# 1) 起台 + 跑全矩阵（Chromium 13 变体 + 回环对照 + WebKit 8 变体，约 45 秒）
node gates.mjs                          # 只跑一个引擎：--engine chromium|webkit；只跑指定变体：--only V0-full,V7-g3-deviceid

# 2) 断言（可被下游直接消费）
node assert-zero-presence.mjs ../red/engine-<ts>.log        # → ASSERT …: PASS / exit 0

# 3) 看表
node summarize.mjs                      # → markdown 表（本文件 §3 那张）
```

单轮读数在 `red/results-*.json`（每次运行一条：`kind` hit/miss、`missRaw` 原文、`consoleLines`、`probeLog`、`apiResponses`、`engineNearpathLines`、截图名）。

**独立起台（人工在浏览器里点）**：`node rig.mjs` → 打印 `origins`（`https://mystand.space` 经 :7444 代理、`https://127.0.0.1:7443`）与控制面 `GET /rig/status | /rig/ticket | POST /rig/modes`。收工：Ctrl-C（只 kill 自己 spawn 的引擎 pid 进程组）。

---

## 8. 台子复用（后续节点做改前/改后 A/B）

### 8.1 一条命令 + 换 web 资源

```bash
RIG_WEB=/path/to/your/web node gates.mjs            # 静态资源换成任意目录（命中即本地直出，未命中回落隧道=引擎 serve）
RIG_LAN_IP=100.65.49.141 sh make-cert.sh $RIG_LAN_IP # 若换 origin，重签证书
```

- `RIG_WEB` 命中规则：对 `GET` 且非 `/api/**`、非 `/ws` 的路径，先查 `<RIG_WEB>/<path>`，命中直出（带 MIME），否则走隧道给引擎。**改 `js/ws.js` 做 A/B 时只把该文件的父目录树放进 RIG_WEB 即可**（例如 `RIG_WEB=/tmp/ab-after` 里放 `js/ws.js`）。
- 注意：`/`（index.html）来自引擎（服务端要注入 `__BRAND__` / `__WEB_FINGERPRINT__` / `NF_HOME`），**不要**在 RIG_WEB 里放 index.html，否则注入丢失。
- 闸位旋钮（`POST /rig/modes` 或 `gates.mjs --only` 的变体矩阵）：`portalStatus: ok|http404|no_bound_host`、`loopbackInfo: engine|http403|port0|probe_proxy|dead_port`、`mint: ok|http403|no_url|no_ticket`、`probe: pass|pna_refuse|http500|wrong_id`。

### 8.2 端口占用表（复用前先 `lsof -nP -iTCP:<port>`）

| 端口 | 必须独占 | 说明 |
|---|---|---|
| 6410 | ✅ | 隔离引擎；被占则换 `DEFAULTS.enginePort`（同时改 `gates.mjs` 里的展示用常量） |
| 6411 | ✅ | 闸③ 观测代理 |
| 7443 | ✅ | 门户 ingress（https） |
| 7444 | ✅ | 转发代理 |
| 18990 | ✅ | 假中继 |
| 6138 | 🚫 **永不使用** | 生产引擎 |

### 8.3 样本文件（已知大小，供后续吞吐/断点腿复用）

内容 `/dev/urandom`，绝对路径 + sha256：

| 路径 | 大小 | sha256 |
|---|---|---|
| `/tmp/nearpath-fire/files/sample-5mib.bin` | 5 242 880 B (5 MiB) | `0dfefdafd66993e688b98cd13ed2749224d7fb03c037fd5f7b617f53336a02fb` |
| `/tmp/nearpath-fire/files/sample-10mib.bin` | 10 485 760 B (10 MiB) | `95c306e524f841acc8a7d409d7283ed50b6f3d75236645aed4cc1fb1a494bebb` |
| `/tmp/nearpath-fire/files/sample-100mib.bin` | 104 857 600 B (100 MiB) | `bcfaae999e8645994f8b4790fdbd21a114ac94ef5991311888f1a47e696f18dc` |

（`/tmp` 会被系统清理；重建命令：`head -c $((N*1024*1024)) /dev/urandom > sample-Nmib.bin`）

### 8.4 台子文件清单

```
rig/
  rig.mjs                   门户 ingress + 假中继 + 隔离引擎 + 闸③ 观测代理 + 控制面
  gates.mjs                 全矩阵驱动（Chromium/WebKit × 变体 × origin 轴），产出 red/
  assert-zero-presence.mjs  「闸链零 presence 参与」可复跑断言（PASS/FAIL + 退出码）
  summarize.mjs             summary.json → markdown 表
  setup-engine.sh           git archive HEAD → /tmp 源码树 + sbt 编译 + classpath 导出
  extract-cp.mjs            sbt classpath 输出解析（路径含空格，逐条 Attributed 取）
  make-cert.sh              自签证书（SAN 覆盖三个 origin）
  portal-readonly.mjs       真实生产门户只读取证（profile 副本，§9 L4）
  tmp-hostsresolver-test.mjs 反例证据：--host-resolver-rules 在本机不生效（选路留痕）
```

---

## 9. 本轮局限（哪些是模拟、哪些是真门户）

- **L1｜引擎来自 HEAD，不是工作区**：工作区 `src/**` 有其它节点未提交的改动（`NearPathStats.scala` 等，`Compile` 报 `value ip is not a member of com.comcast.ip4s.IpAddress`）⇒ 隔离引擎用 `git archive HEAD`(56720568e) 的干净树编译。被测的 `ws.js` 与工作区**逐字节相同**（sha256 已对账），所以闸链结论对本批代码成立；但**服务端**只要工作区改了 `HealthRoutes/ConsoleTicketRoutes`，结论就要按新树重跑（台子支持：重跑 `setup-engine.sh` 指向另一个源码树/分支即可）。
- **L2｜静态资源与 `/ws` 走的是**rig 的 portal wire 实现**：帧面逐字照 `PortalWireCodec.scala` + `portal-wire-contract-v1.3.md`，但**不是 link-server 的 Rust 实现**。差异面：link-server 还会做 user JWT、绑定闸、`x-forwarded-for` 注入、per-IP 预算记账。因此「link-server 自身的错」不在本台覆盖范围（见 §1(b)）。
- **L3｜PNA/LNA 没有真复现**：闸③ 的探测目标是 `127.0.0.1`（环回地址空间），Chromium 视作 local ⇒ 不发 private-network 预检（观测代理全批 `acrm/acrh/acrpn = null`、零 `OPTIONS`）。要真复现「公网页 → 私网/环回」的预检，需要**非本机地址空间**的 origin，本机起不来（`100.65.49.141` 上 node 监听外部不可达，curl 退出码 52；同 IP java 生产引擎可达 ⇒ 本机该路径对 node 不可用）；`--host-resolver-rules` 也不生效（§1(a)）。`V8-g3-pna-sim` 是**模拟**（故意不回 `Access-Control-Allow-Private-Network`），只证明「预检失败时的结果面 = `probe_http_403`/`probe_unreachable`」，**不证明**生产 Chrome/Safari 会怎么发预检。服务端 ACAPN 头本身已由审计 §2.3 curl 双 origin 验过（本台未重复）。
- **L4｜真实生产门户拿不到（撞登录墙，如实记录）**：`rig/portal-readonly.mjs` 用 Chrome profile 的**副本**（`~/Library/Application Support/Google/Chrome/Default` → `/tmp/nearpath-fire/chrome-profile-copy`，源目录零改动；用完即删）打开 `https://mystand.space/portal?device=6a64d249-e526-52b1-b5b6-3b19ea029b26`（生产 device id 取自 `~/.stand/neblink/device.json`）。
  **结果**：被 302 到 `https://mystand.space/login?next=%2Fportal%3Fdevice%3D6a64d249-…`，页面是登录表单（`Sign in to stand … Email or username / Password / Continue with GitHub / Continue with Google`），`document.cookie` 对 `mystand.space` **为空**（`ctx.cookies('https://mystand.space')` 返回 `[]`）。⇒ **需登录，无凭据无法复现真门户腿**；本节点不越权（不登录、不猜凭据、不改 profile）。原始产物：`red/portal-readonly-1793763552558.json` + 同批截图。
  **最接近的替代证据** = §1(a) 的 rig 门户 ingress（origin 逐字等于生产 origin，闸①②④ 的契约面逐字照 wire codec）+ 审计 §2.4 的生产侧读数（`lsof` 浏览器到 6138 = 0 条连接、`console ticket redeemed` 全天 1 次、`loopback-info served` 全天 1 次）。
- **L5｜WebKit 侧只验了「拦」没验「放行」**：WebKit 26.5 无法用 `proxy` 之外的手段造生产 origin，且闸③ 在 https 页面上必被混合内容拦 ⇒ **「WebKit 放开混合内容后能否走完四闸」没有证据**（也不需要：这正是要修的根因）。若要验修复态，用 `V0-full` 在 WebKit 上跑，PASS 条件 = `hit`。
- **L6｜端口/预算旋钮**：台子放宽了隧道 per-IP 预算（生产默认 1024/60s）。这**不影响**四闸判据（闸链是浏览器侧 + 既有端点），但意味着**台子里的资产加载不会 429**，生产里连开多个控制台标签可能会。
