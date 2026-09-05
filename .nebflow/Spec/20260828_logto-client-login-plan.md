# 客户端登录统一到 Logto OIDC — 流程图级方案（2026-08-28）

只读探索产出。源码核实：Nebflow main @186d0a20、neblink-server @01ca6f4。

## 0. 与任务假设不符的事实（显式标注）

1. **客户端已不是 GitHub Device Flow**。2026-08-18 已完成 Logto 迁移 Stage 1（`LogtoDeviceFlow.scala`，219 行，含单测级注入 transport）：双模式**完整实现而非雏形**——`~/.nebflow/neblink/config.json` 配了 `logto{endpoint,clientId}` 就走 Logto RFC 8628；未配才回落 neblink-server 自托管 `/api/device/code|token`（GitHub 遗留）。
2. **auth.json ≠ 登录凭证**（`gateway/auth.scala:13`）：存的是本地 gateway 随机 API token（web UI→本地 Bearer）。登录凭证是 `~/.nebflow/neblink/device.json` 的长期 deviceToken（`DeviceCredentialStore.scala:20-31`）。
3. 真正差距：① device flow UX（用户须抄 user code 到 hosted 页）→ 换 AC+PKCE 免抄码回跳；② refresh_token 语义缺失；③ GitHub 字段/文案残留（`activityBar.js:396` 仍写「GitHub 授权」）；④ 官网同源语义其实已达成（Logto 账号即身份，见 §3）。

## 1. 现链（已核实）

```
[头像登录] web/js/activityBar.js:421 startFlow()
   │ POST /api/neblink/device-flow/start ──► gateway RestApiRoutes:655
   │   (logto 已配) ──► Logto /oidc/device/auth ──► {userCode, verificationUri}
   ▼
window.open(verificationUri)  // Logto hosted 页，邮箱+密码可登录（GitHub 是其中社交项）
   ▼ 用户在页上确认
web 轮询 POST /neblink/device-flow/poll (RestApiRoutes:699)
   └─► Logto /oidc/token ──► Logto access_token
       └─► POST server /api/device/register (Bearer access_token, routes.rs:584)
           verify_token_dual（JWKS RS256 主 + HS256 遗留辅）→ resolve_user(sub) → 长期 deviceToken
   ▼
completeDeviceEnrollment (RestApiRoutes:2500)：device.json 持久化 + config 写入 + 热切换 NeblinkClient
   ▼
NeblinkClient.login (NeblinkClient.scala:166)：POST /api/device/session(deviceToken) → 内存 sessionToken
   └─ heartbeat / 好友 API 全部 Bearer sessionToken（server 侧 require_user_or_device→user_id）
```

## 2. 新登录链（Authorization Code + PKCE）

```
JS: POST /api/neblink/auth/start ──► Scala 生成 code_verifier/challenge + state（内存暂存）
    └─ 返回 {authorizeUrl}  // Logto /oidc/auth?client_id&redirect_uri&response_type=code
                            // &scope=openid offline_access&code_challenge&state
JS: window.open(authorizeUrl) ──► Logto hosted 登录页（邮箱+密码主体）
    ▼ 用户登录
Logto 302 ──► http://127.0.0.1:<gatewayPort>/auth/callback?code&state   [gateway 已有监听，零新端口]
    ▼
Scala callback 路由：校验 state → POST /oidc/token (code+code_verifier)
    └─ {access_token, id_token, refresh_token}
        └─► POST /api/device/register (Bearer access_token)   ← 复用现路，下游零改动
            └─► completeDeviceEnrollment + refresh_token 追加持久化
    ▼
callback 页「登录成功」+ JS 轮询 /api/neblink/auth/state 知晓完成 → 刷状态/成功横幅
```

### 回调方案对比

| | A. loopback `127.0.0.1:<port>/callback` | B. deep link `nebflow://callback` |
|---|---|---|
| 新增监听 | **无**（gateway 本就监听，只加一条路由） | 无 |
| 端口可变 | RFC 8252 对 loopback 回调**不校验端口**（注册一条 URI 即可，待对部署版 Logto 实测确认） | 无关 |
| OS 注册 | **无** | mac Info.plist + Win 注册表，安装器（WiX msi/dmg）都要做 |
| dev 环境 | 直接可用 | sbt run 未注册协议→失效 |
| 浏览器确认弹窗 | 无 | 「是否打开 Nebflow？」多一步 |
| 多版本/便携版冲突 | 无 | 处理器指向哪个安装不确定 |

**推荐 A（loopback）**：跨平台零注册、dev 可用、改动最小。B 仅当未来出真原生壳（Tauri 等）再议。
注：桌面形态=本机 gateway+用户浏览器，**登录起点已在浏览器内**，无需 `open`/`start` 拉浏览器；仅未来 CLI `login` 子命令才需（mac `open` / win `cmd /c start`）。

## 3. Token 存储与刷新语义

- **deviceToken 不变**：长期设备凭证，neblink WS/REST 的实际通行证（session 短票由它换）。Logto access_token 仅在 register 时用一次 → Logto token 短有效期不构成压力。
- **refresh_token（新增）**：`device.json` 演进为 `{serverUrl, networkId, deviceId, deviceToken, logto?: {refreshToken, updatedAt}}`（新字段向后兼容：旧文件无 `logto` 也能解）。Logto refresh token 会轮换——**每次刷新回写最新值**。
- **静默重登**：deviceToken 失效（401/吊销/换 server）→ gateway 用 refresh_token 走 `/oidc/token` grant_type=refresh_token → 拿新 access_token → 重新 register → 恢复；refresh 也失败才弹登录。用户长期无感知。

## 4. 会话建立与身份（已同源，零改动）

server 侧 `resolve_user`（store.rs:383）：`github:<id>` 前缀→老站用户，其余 sub 直接作 uuid → **Logto sub 即全局 user_id**。好友/设备全挂在 user_id（friends.rs:41-57，device 凭证也 resolve 到同一 user）。客户端登录成功后：avatar/名称来自 EnrollResponse（字段仍叫 `githubUsername`，wire 契约先不动），好友列表心跳刷新——与官网同一身份自动互通，无需新代码。

## 5. 旧链处置（过渡语义）

```
beta.53（本版）：logto 已配 → AC+PKCE 为主
                 ├─ PKCE 失败（回调被占/被禁）→ 自动回落 Logto device flow（保留，零成本）
                 └─ logto 未配 → 现 legacy 双模式原样（KAI beta.52 验证兼容）
beta.54+（下版）：确认 AC+PKCE 稳定 → 删 legacy 自托管 code/token 代理路径 + server /device/{user_code} GitHub 页
                 /api/device/enroll 配对码链路**保留**（LAN 无账号配对，另属机制）
```
GitHub 语义 = Logto 社交登录绑定项（官网已如此），客户端不再有任何 GitHub 专有代码路径；`githubLogin` 字段仅作展示残留，随 wire 契约版本再清理。

## 6. 登录 UI 方案

复用现头像弹窗骨架（activityBar.js:395-457）：文案改「在浏览器中登录 NebLink 账号」，`window.open(authorizeUrl)` 后弹窗转等待态，JS 轮询 `/api/neblink/auth/state`（pending/success/error 三态，替代 user-code 展示区）。callback 页 = gateway 静态小页「登录成功，可关闭本页」。hosted 页水印问题已知→先跑通后排（见 §8-Q3）。

## 7. Windows/macOS 差异点

- 浏览器拉起：本方案**不需要**（起点即浏览器页）；CLI 登录才涉 `open` vs `cmd /c start`。
- loopback 回调：双端无差异；127.0.0.1 回环不走 Windows 防火墙提示（gateway 0.0.0.0:8080 的既有提示与本案无关，已存在）。
- device.json 权限：POSIX rw------- 在 Win 是 no-op（现有 try-catch 已兜底，DeviceCredentialStore.scala:43-49）。
- Logto Native 应用 redirect URI：注册 `http://127.0.0.1/auth/callback` 一条即可（RFC 8252 端口豁免，需实测确认，见 Q1）。

## 8. 分工建议

**Backend (Scala)**：① `/api/neblink/auth/start|state` 路由 + PKCE/state 内存暂存；② `/auth/callback` 路由（code 换 token、调 register、复用 completeDeviceEnrollment、refresh_token 落盘）；③ DeviceCredential schema 加 `logto` 块；④ NeblinkClient 401→refresh_token 静默重登；⑤ Logto authorize URL 构造（纯函数，可仿 LogtoDeviceFlow 测试风格）。
**Frontend (web/js)**：① 弹窗改 PKCE 态机（去 user-code 展示，改 auth/state 轮询）；② activityBar.js:396 遗留 GitHub 文案清理；③ Settings 面板登录按钮同接入。

## 9. e2e 验收清单

1. 全新安装→头像登录→浏览器开 Logto（邮箱+密码）→回跳 127.0.0.1→device.json 含 deviceToken+refreshToken→头像/名称就位。
2. 重启应用→静默 /api/device/session→对端设备可见。
3. 同账号官网加好友→客户端好友列表出现（心跳刷新窗口内）。
4. 第二台设备同账号登录→同一 user_id，设备同网分组；两台互传文件。
5. 退出登录→device.json 清除→再登成功。
6. 模拟 deviceToken 失效→refresh_token 静默重登，无浏览器介入。
7. logto 未配的旧配置→legacy 路径仍通（兼容版验证）。
8. KAI Windows 实测：msi 安装→登录→回调路由通（无防火墙新弹窗）。

## 10. 风险与开放问题（待作者/Nebula 拍板）

- **Q1** 部署版 Logto 是否对 loopback redirect 实行 RFC 8252 端口豁免？若严格匹配端口则需注册默认 8080 + 少量备选端口，或强制 `--port` 时不允许改（需在 Logto 控制台实测）。
- **Q2** refresh_token 生命周期：Logto 默认 TTL（滚动约 14 天/轮换）是否满足「长期在线」预期？不满足→调 Logto 配置或加「临期提示重登」。
- **Q3** hosted 页水印：接受后排 or 直接上 BYO-UI（Logto experience API，客户端自绘登录页，工作量+1 天级）？建议先 hosted 跑通。
- **Q4** EnrollResponse 的 `githubUsername` 字段名（wire 契约）何时改通用名 `username`？涉及 server+client 同步版本。
- **Q5** deep link 协议名是否预留（`nebflow://`）？仅未来原生壳需要，本期不实现。
- **Q6** legacy 兜底保留几个版本？建议 beta.53 全保留、beta.54 视 KAI 反馈删。

## 关键文件索引

| 文件 | 位置 |
|---|---|
| LogtoDeviceFlow.scala（device flow 现实现） | neblink/，start L149 / poll L158 / register L169 |
| Protocol.scala（端点常量） | neblink/，DeviceApi L22-31、LogtoOidc L33-36 |
| DeviceCredentialStore.scala（device.json） | neblink/，L20-57 |
| NeblinkClient.scala（session/好友） | neblink/，login L166-223、friends L284+ |
| NeblinkModel.scala（LogtoConfig） | neblink/，L140-195 |
| RestApiRoutes.scala（登录路由+落盘） | gateway/，start L655 / poll L699 / completeDeviceEnrollment L2500 |
| activityBar.js + neblink.js（登录 UI） | web/js/，modal L395-457 / pollDeviceFlow L290 |
| neblink-server routes.rs（register/session） | /api/device/register L584、session L1137 |
| neblink-server store.rs / friends.rs | resolve_user L383、require_user_or_device L41 |
