# 设备码 LQVM-2VKT 批准死局 — 实证与解锁命令（紧急批）

分支：`delegate/delegate-nebflow-4a1ffcec` · 2026-10-22 21:25 · 纯读盘 + 只读探针，生产零改动

## 一行结论

**LQVM-2VKT 在今天的生产上无法被任何人批准**——中继侧的设备码浏览器批准腿已于 2026-09-01 删除，`poll_device_code` 只会永远回 `authorization_pending`（write-only 死链）。倒计时到 900s 自然过期打印 `expired_token` 是**预期行为**，不是新故障。**可用的登录路径是 PKCE（Logto 托管页）**，在 Windows 机上一行命令即可，已实证 provider 端活。

## 作者当场执行（Windows 机 PowerShell，浏览器须在同一台 Windows 机上）

```powershell
Start-Process ((Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/api/neblink/auth/start" -ContentType "application/json" -Body '{}').authorizeUrl)
```

- 默认浏览器打开 auth.nebflow.space 托管登录页 → 用统一 nebflow 账号登录 → Logto 303 回 `http://127.0.0.1:8080/auth/callback`（RFC 8252 loopback）→ 网关完成 register+persist+隧道热换。
- 验证：`stand whoami`（应显示账号 + Device 行）；或 `curl http://127.0.0.1:8080/api/neblink/auth/state`（`{"status":"success"}`）。
- 备选入口：本机浏览器开 `http://127.0.0.1:8080/` 本地控制台 → 登录面板（同一链，`web/js/neblink.js` 调同一 `auth/start`）。
- 旧的那条 `stand login` 可以 Ctrl+C 掐掉（或等它过期）。

## 证据链（为何 LQVM-2VKT 批不了）

| # | 证据 | 位置 |
|---|------|------|
| 1 | 生产中继（link-server）`DeviceCodePollResult { Pending }` 单臂；注释逐字「the legacy GitHub-web approval path was removed 2026-09-01, so a code is either still pending or an error」 | `/Users/kaiyu/.stand/projects/link-server/src/store.rs:255-262` |
| 2 | `DeviceCode` 元数据「currently write-only — kept for future flows」 | 同上 `store.rs:271-275` |
| 3 | `/api/device/token` 处理器只会回 `authorization_pending`/错误，无 Approved 臂 | 同上 `routes.rs:1189-1211` |
| 4 | 中继真实 verificationUri = `https://link.mystand.space/device/{userCode}`（curl 实测 HTTP 200），页面自述「此页面已停用……不再接受浏览器登录授权」 | 实探 + 页面 HTML L50-55 |
| 5 | CLI 打的 `https://nebflow.space/device` 是**硬编码品牌文案**（刻意不打印响应里的 verificationUri），该页属 website 仓跨仓清单项、从未建成 → 作者撞的 404 | `src/main/scala/nebflow/cli/LoginCommand.scala:116-129` |
| 6 | Windows 机铸出的是中继码（`logto` 块缺席 ⇒ 走 relay 代理腿；`LQVM-2VKT` 与本批探针码 `JGJC-USH6` 同为 4-4 中继格式、expiresIn=900 同源） | `NeblinkRoutes.scala:671-708` + 实探响应 |
| 7 | PKCE 链活：`GET https://auth.nebflow.space/oidc/auth?client_id=csxh16cas0x03bgk6w7ej&redirect_uri=http://127.0.0.1:8080/auth/callback&...` ⇒ **HTTP 303 → auth.nebflow.space/sign-in**（provider 接受该 AC app + loopback redirect） | 实探（只读，无状态残留） |

## 后续（候批，本批未动）

1. **`stand login` 死腿处置**：`logto` 块缺席的部署上 `stand login` 铸出的码**永远批不了**（write-only），CLI 又不打印真实 verificationUri——要么给 `stand login` 加 PKCE 回退（打 authorizeUrl），要么下线该腿；需要作者裁定。
2. **website 仓 `/device` 页**（跨仓清单既有项）：即便补建，今天的中继也没有任何可调的批准 API（M-1 `approve_device` 是已注册凭据的 pending→approved 生命周期，不是 userCode 批准）——门户页只能做引导/重定向，除非中继恢复一条绑 Logto 会话的批准面。
3. CLI 文案位 `LoginCopy.DevicePromptAddress` 硬编码品牌域 + 刻意丢弃响应 verificationUri 的裁定（07:45）在当前生产形态下必然导去死页，建议随 1 一起复盘。

## 生产安全声明

本批只发起了：一次中继 `/api/device/code` 探针（自铸一个 900s 自过期码 `JGJC-USH6`，等价于 CLI 正常行为）、若干 GET/未带有效体的 4xx 探针、一次 Logto authorize 只读 303 探针。零写、零配置改动、零凭据落盘。
