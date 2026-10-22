# 鸡生蛋缺陷（主流程一号缺陷）：匿名引导面整条缺失 — 引导腿修复（紧急批）

分支：`delegate/delegate-nebflow-4a1ffcec` · 2026-10-22 22:05 · 代码修复 + 路由级测试 12 例

## 0. 作者当场执行（先验证，再谈合并）

**A. 修复构建部署后（Windows PowerShell，三条面一次验完）：**

```powershell
# ① 登录发起（原翻车命令，逐字不变——翻车原因就是 403 门，不是命令本身）
Start-Process ((Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/api/neblink/auth/start" -ContentType "application/json" -Body '{}').authorizeUrl)
# ② 登录轮询（引导方读 outcome；修前 403）
Invoke-RestMethod http://127.0.0.1:8080/api/neblink/auth/state
# ③ 状态读取（作者实截的「状态读取失败（HTTP 403）」横幅应消失）
Invoke-RestMethod http://127.0.0.1:8080/api/neblink/status
```

①弹出托管登录页 → 登录 → 303 回 `http://127.0.0.1:8080/auth/callback` → 网关换码 + register + persist；②应回 `{"status":"success"}`；③应回含 `loggedIn:true` 的设备状态 JSON。控制台复验：开 `http://127.0.0.1:8080/` → 头部不再挂 403 横幅 → 点「登录 stand 账号」→ 弹窗走完 → 头像/账号面落位。

**B. 今天（当前生产构建上）就能绑定设备的等价命令**（机器本地文件令牌 `%USERPROFILE%\.stand\auth.json`，JSON 字符串体）：

```powershell
$t = Get-Content "$env:USERPROFILE\.stand\auth.json" | ConvertFrom-Json; Start-Process ((Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/api/neblink/auth/start" -ContentType "application/json" -Headers @{Authorization="Bearer $t"} -Body '{}').authorizeUrl)
```

或走控制台会话：`stand token` 铸 120s 一次性票 → 粘进控制台签到卡（或开 `/?ticket=<票>`）→ 会话落座后点「登录 stand 账号」（cookie 随行，`auth/start` 照常过门）。

## 一行结论

**作者实截证实：不止 `auth/start` 一个端点——整条匿名引导面在零凭据设备上缺失**：登录发起（`auth/start`）、登录轮询（`auth/state`）、状态读取（`/api/neblink/status`）全在 session/Bearer 门后面 ⇒ 解锁命令 403、控制台头部恒挂「状态读取失败（HTTP 403）」、点登录按钮无效，登录永远无法开始。已修：**发起 + 回调 + 状态读取三面全链打通**（回调 `/auth/callback` 本就匿名；start/state/status 三条换引导腿门），控制台按钮与 CLI 两条发起路都活。

## ① 修复内容（作者令「登录发起+回调+状态读取三面全链打通」的落法）

- 判据单点：`RestApiCtx.bootstrapGate`（`src/main/scala/nebflow/gateway/RestApiCtx.scala`，紧挨既有 `gate`）
  - 有凭据：`checkAuth` 命中（session cookie / Bearer 文件令牌 / PAT）⇒ 放行，**任何腿**，逐字不变；
  - 无凭据：仅 `LoopbackLeg.isLoopback(req)`（机械 socket 对端地址，与 `localTicketOnLeg` 同一单点判据，客户端不可伪造）放行——LAN / 公网 / 隧道桥（in-process，remoteAddr=None）一律仍 403 `{"error":"Unauthorized"}`（体逐字不变，无枚举面）。
- 换面三条（`NeblinkRoutes.scala`）：
  - `POST /neblink/auth/start` → `bootstrapGate`（登录发起，CLI 与控制台按钮同一端点）；
  - `GET /neblink/auth/state` → `bootstrapGate`（登录轮询——发起面开了轮询面锁着，引导方永远读不到 outcome，同一条腿的两半）;
  - `GET /neblink/status` → `withNeblinkBootstrap`（`withNeblink` 的引导腿变体；状态读取——实截横幅的直接来源，登录成功后控制台 `loggedIn`/头像/账号面收口的唯一读点）。
  - `/auth/callback` 与 `/auth/logged-out`：本就匿名（浏览器导航跳，PKCE state 即防 CSRF），零改动。`auth/end-session`、`handoff` 等其余面**不动**。
- 防滥用（作者令「一次性 nonce/短时限即可」的映射，零新轮子）：
  - PKCE `state` = 一次性 nonce（`take` 单次消费）；待决登录 15min 短时限（`PkceLoginSession.ExpiryMs`）；进程内单飞槽（新 start 顶替旧 attempt，无资源放大）；authorize URL 本身不绑定任何凭据——绑定只发生在真人完成托管登录且回调 state 命中之后（登录即绑定）；
  - `status` 的环回匿名读**无新增披露面**：载荷 = 设备自身状态，而本地进程本就可读 `auth.json` 文件令牌 ⇒ 全量 API（网关绑 0.0.0.0，但引导腿被环回判据机械锁在本机）。

## ② 零凭据控制台全链走读（修后形态）

1. 开 `127.0.0.1:8080/` → 静态面加载；`fetchNeblinkStatus` → **200**（匿名环回）→ `loggedIn=false` ⇒ 头部不再挂 403 横幅，登录卡状态正确；
2. 点「登录 stand 账号」→ `startPkceLogin` → **200** authorizeUrl → 托管页登录 → `/auth/callback`（本就匿名）→ 换码 + register + persist（登录即绑定）；
3. `pollPkceState` → **200** → success → `refresh()` → `status` **200** `loggedIn=true` ⇒ 弹窗 success、头像/账号落位；
4. 控制台聊天面（WS/API）仍走**票面**（会话只有 `ConsoleTicketRoutes` 一条铸造路，PKCE 回调不铸控制台会话——既有设计非本缺陷）：`stand token` 拿一次性票即入座。

## ③ 测试与改动

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/gateway/RestApiCtx.scala` | 新增 `bootstrapGate`（判据单点 + 防滥用映射注释） |
| `src/main/scala/nebflow/gateway/NeblinkRoutes.scala` | `auth/start` / `auth/state` 换 `bootstrapGate`；`neblink/status` 换 `withNeblinkBootstrap`；各带引导腿注释 |
| `src/test/scala/nebflow/gateway/NeblinkBootstrapLegSpec.scala` | 新增 12 例：匿名环回（v4 + v4-mapped）start/state/status 全通；LAN、隧道形态（remote=None）全 403 逐字体；凭据调用（LAN/隧道 + Bearer）零回归 |

测试：`NeblinkBootstrapLegSpec` 12/12 绿；`sbt compile` + `Test/compile` 绿；`nebflow.gateway.*` + `nebflow.neblink.*` 回归套结果见提交信息。合并前终验复跑（2026-10-23 00:17，sbt 前台）：`Test/testOnly nebflow.gateway.* nebflow.neblink.*` = **1615 total / 0 failed / 0 errors / 2 skipped（既有 skip）**，950s 全绿，与提交信息基线一致。

## ④ 缺陷定性（按作者「登录即绑定」裁定 = 主流程一号缺陷）

根因：引导链的三条读面（发起/轮询/状态）与运营面共用同一鉴权门——引导的**存在前提**（零凭据）与门的**通过前提**（有凭据）互斥。修复原则：引导面最小放行（恰三条 + 回调、仅环回腿、复用既有一次性 nonce/短时限机制），运营面鉴权零松动。

## 残余边界（候批，本批未动）

1. **控制台会话的登录侧铸造**：若要「点登录 = 控制台聊天面也整体解锁」，需 PKCE 回调给完成登录的那个浏览器铸控制台会话——引入第三种 session origin（新撤销/腿绑定语义），需要作者裁定，未擅动。现状收口 = `stand token` 票。
2. `stand login` 设备码死腿（上一报告 `reports/20261022_212500_device-approval-deadend` 后续第 1 项）仍在候批。
