# Logto 阶段 1 · 三仓代码对接说明

> 2026-08-18 深夜批次③ ｜ 前置调研：/tmp/auth-redesign-survey.md（用户拍板 Logto，只做阶段 1）
> 硬边界：Logto 实例不部署（等腾讯云+备案）；R7 users.github_id 改造不做（阶段 2）；官网不 push 不部署。

## 目标链路（阶段 1 就位、灰度双读）

```
官网 (Next.js)                neblink-server (Rust)              客户端 (Scala)
  邮箱/GitHub 登录               /api/device/register  ←─────────  Device Flow
  → Logto OIDC code flow          ↑ Bearer RS256 token             POST {logto}/oidc/device/auth
  → cookie 存 Logto token          │ JWKS 验签                       轮询 {logto}/oidc/token
  → 转发 Bearer 调 server API ─────┘ (HS256 旧通道并行保留)           → 拿 access_token 调 register
                                                                    → EnrollResponse 不变，下游零改动
```

## 环境变量契约（三仓新增，全部 optional——未配置=现状行为字节不变）

| 变量 | 仓库 | 含义 |
|---|---|---|
| `NEBLINK_JWKS_URL` | neblink-server | Logto JWKS 端点（`{endpoint}/oidc/jwks`）。设置即启用 RS256 验签 |
| `NEBLINK_OIDC_ISSUER` | neblink-server | RS256 token 期望 `iss`（`{endpoint}/oidc`）。缺省只验签名+exp |
| `LOGTO_ENDPOINT` | 官网 | Logto 实例根 URL |
| `LOGTO_APP_ID` / `LOGTO_APP_SECRET` | 官网 | Traditional Web 应用凭据 |
| `logtoEndpoint` / `logtoClientId` | 主仓 neblinkServer 配置块 | Native 应用（公开客户端，无 secret） |

## 各仓改动

### 1. neblink-server（feat/logto-stage1，基于 main@663bea0）

- `src/jwks.rs`（新）：`JwksVerifier`——reqwest 拉 JWKS、按 kid 缓存、遇未知 kid 强制刷新一次（OIDC 标准轮换语义）。
- `src/auth.rs`：`verify_jwt_rs256(token, jwk, expected_iss)`——jsonwebtoken `DecodingKey::from_jwk`，Validation 限定 RS256 + exp + 可选 iss。
- 双读分发：`require_user` 先走现有 HS256（`store.verify_user_token`，语义不变）；失败且配置了 JWKS → RS256 路径，sub 复用现有映射（`github:{id}` 前缀反查，裸 sub 直接信任——签名即边界，与现行 neblink 自发 token 同语义）。
- `POST /api/device/register`（新）：Bearer Logto token + {deviceId, deviceName, platform} → 双读验证 → get_or_create_default_network → enroll_device → EnrollResponse（镜像 device_token 的 Approved 分支，复用下游全部逻辑）。
- AppState 持有 `Option<Arc<JwksVerifier>>`（启动时按 env 构建）。

### 2. 主仓（feat/logto-stage1，基于 94159b4c，worktree /tmp/nb-logto）

- `Protocol.scala`：`DeviceApi.register` 常量 + `LogtoOidc` 端点常量。
- `NeblinkServerConfig`：`logtoEndpoint`/`logtoClientId` 可选字段（decoder 向后兼容）。
- `LogtoDeviceFlow.scala`（新，neblink 包）：纯函数+IO，HTTP send 可注入（测试零网络）——start 请求构造（form 编码 RFC 8628）、响应映射（snake_case→前端既有 camelCase 契约：deviceCode/userCode/verificationUri=verification_uri_complete/interval/expiresIn）、poll 状态机（authorization_pending/slow_down→pending 透传）、成功后 register 交换。
- `RestApiRoutes` device-flow start/poll 双模式：配置了 logto 走 Logto+register，否则原样代理 neblink-server code/token。**前端零改动**（字段契约+pending 语义保持）。

### 3. 官网（本地分支，不 push）

- `lib/logto.ts`（新）：jose `createRemoteJWKSet` 验 id_token、code exchange、refresh 包装。
- `app/api/auth/logto/route.ts` + `callback/route.ts`：标准 authorization code flow，cookie 存 access/refresh/id token。
- `app/api/auth/providers/route.ts`（新）：按 env 返回可用登录方式 → 登录页条件渲染邮箱按钮（GitHub 保留，灰度共存）。
- `/api/auth/me`、profile 转发：logto cookie 优先，回退 nebflow_token。

## 灰度语义（阶段 1 → 2 衔接）

- 三处全部 env/配置门控：不配置=今天的行为，部署 Logto 后逐仓打开。
- HS256 旧通道完整保留（官网 30d / server access 15min+refresh 30d 自然过期）。
- 阶段 2 待办：users 表 github_id nullable + logto_id 列、迁移脚本（Management API）、Hub owner 校验改造、旧 secret 轮换销毁。

## 验收

- 主仓 `SBT_OPTS=-Xmx3g sbt test` 全绿（新增 LogtoDeviceFlowSpec）
- server 仓 `cargo test` 全绿，含 **mock JWKS 链路测试**：std TcpListener 起 mock JWKS server + rsa dev-dep 生成 keypair → 签 RS256 token（sub=github:seed）→ JwksVerifier 拉取验签 → sub 反查 user → enroll_device → verify_device_credential 全链
- 官网 `tsc --noEmit` 零错误

## 实现定稿（与初稿的差异，2026-08-18 补）

- **官网 session 层**：`lib/session.ts` 统一双读（logto cookie 优先 → id_token JWKS 验证出身份 + access_token 作转发 Bearer；回退 nebflow_token 本地 HS256）。me/profile/devices-update 三消费方全部改走它——转发 token 自动切换，无需逐路由判断。
- **官网 refresh**：`POST /api/auth/refresh`（RT 轮换，legacy 会话 no-op 200）。getSession 不做内联刷新（改 cookie 需 response 载体，侵入太大）——id_token 过期时回退 legacy cookie，都无则 401 → 登录页（IdP 有会话时一键重登，灰度期可接受）。
- **主仓传输约定**：`LogtoDeviceFlow.jdkSend` 传输异常映射 `(0, message)`，start/register/classifyPoll 对 status 0 直接取 body 作错误消息——对齐 legacy proxyPost 的 BadRequest 降级（不会 500）。
- **前端零改动确认**：start 响应映射层输出与自托管 DeviceCodeResponse 字节同构（camelCase 五字段）；poll 的 slow_down 在 classifyPoll 归一为 authorization_pending（前端只认这一个 pending 语义）。
- **neblink-server 语义保持**：`require_user` 改 async 双读（14 处调用点机械更新）；纯 legacy 部署（无 JWKS）错误消息与历史一致（"NEBFLOW_JWT_SECRET not configured" / "Invalid or expired token"）。device_token 的 Approved 分支与 device_register 共享 `enroll_response_for_user`。
