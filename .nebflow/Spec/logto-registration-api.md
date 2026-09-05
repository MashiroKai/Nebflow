# Logto 注册服务 API 调用序列（nebflow.space /register 直建账号）

> 缺口1 方案(b) Backend 半场交付件（2026-08-27，Nebula 裁定）。
> 语义：官网注册表单收集**邮箱+密码**，后端经 Management API 直建 Logto 账号——零验证码、建号即可登录（MVP「直通」语义；平台守卫使租户级 email 注册标识符在此构建下不可行，见 §5 背景）。
> 本文不含任何凭据明文。凭据来源见 §2。

---

## 1. 调用总览

| 步骤 | 端点 | 方法 | 说明 |
|---|---|---|---|
| 1 | `POST {LOGTO_ENDPOINT}/oidc/token` | form-urlencoded | M2M 换 Management API access token |
| 2 | `POST {LOGTO_ENDPOINT}/api/users` | JSON | 建号：primaryEmail + password + username |
| 3 | （登录验收）Experience API 密码登录 | — | 与原生登录页同链路，直接可用 |

全部调用走 HTTPS TLS。

## 2. 凭据

- 本机参考档：`~/.nebflow/logto-registration.env`（600，gitignore 外）
- Vercel env 注入键名约定：
  - `LOGTO_M2M_CLIENT_ID`
  - `LOGTO_M2M_CLIENT_SECRET`
  - `LOGTO_ENDPOINT = https://auth.neblink.space`
- 身份：M2M 应用 **neblink-registration**（app id 见 env），角色 `neblink-registration`（scope=`management-api-all` 全域）。Secret 常驻 Vercel env 属知情接受；轮换走 §6 backlog。
- ⚠️ 官网 Web App（OIDC client）没有也不应有 client_credentials——永远从**服务端**取 token，勿把 secret 下发浏览器。

## 3. 步骤 1 — 取 token

```http
POST https://auth.neblink.space/oidc/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
&client_id={LOGTO_M2M_CLIENT_ID}
&client_secret={LOGTO_M2M_CLIENT_SECRET}
&resource=https://default.logto.app/api
&scope=all
```

响应 `{"access_token": "…ES384 JWT ~567B", "token_type":"Bearer", "expires_in":3600}`。

实测注意：
- **`scope=all` 必须显式携带**——缺省授权集为空，请求会得到 token 但所有 `/api/*` 返回 403 `auth.forbidden`。
- token 有时效，服务端进程内缓存到 `expires_in - 60s` 再续，勿每请求重取。

## 4. 步骤 2 — 建号（核心）

```http
POST https://auth.neblink.space/api/users
Authorization: Bearer {access_token}
Content-Type: application/json

{
  "primaryEmail": "user@example.com",
  "password": "<用户注册输入的原始密码>",
  "username": "<按 §4.1 规则派生>"
}
```

成功 `200`，返回完整 user 对象（`id` 即 Logto sub，建议落库关联自家账号表）。

### 4.1 username 派生规则（必带字段！）

创建时**必须同时给 username**：本构建 experience 登录在提交时会校验 profile 完整性，无 username 的账号首次 sign-in 报 `422 user.missing_profile (["username"])`（实证），需用户现场补填打断流程。预置 username 后整链零摩擦。

- 字符集白名单实测：`[a-zA-Z0-9_]`——**连字符 `-` 被 regex 拒绝**（400 guard.invalid_input）；`.` 同理慎用。
- 推荐派生：`local-part 中非 [a-z0-9_] 全部替换为 '_'` → `+ '-'→'_'` → 尾部加短哈希防碰撞（如 `"_" + base36(email sha256 前 4 位)`)。示例：`user.name-x@gmail.com` → `user_name_x_7f3a`。
- 若源 local-part 为空/全非法（罕见），回退 `nf_` 前缀 + 随机串。

### 4.2 密码传输语义（「按 Logto 规则哈希传输」的准确含义）

- ✅ 正确：HTTPS POST 把**用户输入的原始密码**交给 Management API——Logto 服务端用 argon2id 自行加盐哈希落库。
- ❌ 错误：客户端/服务端预先 hash 后再传——Logto 会把你的哈希值当原密码再哈希，密码就变成那串哈希本身且无法从客户端密码复原登录。
- 唯一正确姿势 = TLS 通道 + 原文传输 + 服务端哈希。

### 4.3 关于 emailVerified

本构建 User schema **不存在** emailVerified / primaryEmailVerified 字段（GET 响应键集合实证、PATCH 尝试 422 `guard.invalid_input` 实证、schemas 源码 grep 零命中）。邮箱即写即生效、无任何验证环节——与 MVP 直通语义天然等效，无需也无从设置该标志。

## 5. 幂等与错误语义（全部实测）

| 场景 | 状态码 | code | 处理建议 |
|---|---|---|---|
| 邮箱已注册 | 422 | `user.email_already_in_use` | 展示「该邮箱已有账号，请直接登录」 |
| username 含非法字符 | 400 | `guard.invalid_input`（regex, path=username） | 检查派生规则 |
| password 违反复杂度 | — 管理面不强制策略 | （未拦截） | 官网侧自行做强度校验，勿依赖 Logto |
| DELETE 不存在的 id（幂等删） | 404 | `entity.not_found` | 可安全重试 |

## 6. 步骤 3 — 建号即登录的验收链（官网 e2e 参考）

```python
s = requests.Session()
s.get(f"{BASE}/oidc/auth", params={
    "client_id": WEB_APP_ID,
    "redirect_uri": "https://nebflow.space/api/auth/logto/callback",
    "response_type": "code", "scope": "openid"}, allow_redirects=True)
s.put(f"{BASE}/api/experience", json={"interactionEvent": "SignIn"})
r = s.post(f"{BASE}/api/experience/verification/password",
           json={"identifier": {"type": "email", "value": EMAIL}, "password": PWD})
vid = r.json()["verificationId"]
s.post(f"{BASE}/api/experience/identification", json={"verificationId": vid})
r = s.post(f"{BASE}/api/experience/submit")          # 200 + redirectTo(含 code)
```

实测锚点（2026-08-27，探针用户全清场）：create(email+username) 200 → 上述链路一路 200/204 → submit redirectTo 带 code → exchange 得 id_token.sub == 新建 uid。全程零验证码、零补填。

## 7. 安全注记与 Backlog

- Secret 常驻 Vercel env：知情接受（Nebula 裁定 2026-08-27）。secret 只经安全通道进 Vercel env 配置，不进聊天/git/前端 bundle。
- **Backlog（不阻塞当前）**：
  1. 定期轮换 neblink-registration secret —— Management API `GET/POST/DELETE /api/applications/{id}/secrets`（按 name 标识，GET 直出 value；轮换模式同 bootstrap 已实践过的新建→验证→同步→删旧四步）
  2. SMTP connector 升级路径（方案 a）：若未来产品要求真实验证码，接邮件 connector 后 signUp.identifiers 可切 email（届时体验 API 注册在 Logto 原生层闭环，可退役本直建通道或保留并存）
  3. IP 频控 / 滥用防护由官网侧实现（Logto 管理面另有 sentinelPolicy 默认值兜底爆破，不覆盖注册频控）

## 8. 背景存档（为什么需要这条通道）

租户配置尝试让 email 进 signUp.identifiers 被 core 双守卫硬拦：`passwordless_requires_verify`（非 username 标识符强制 verify）+ `enabled_connector_not_found`（Email 标识符要求已启用邮件 connector）——「未配 SMTP 的邮箱验证码直通注册」在该构建不可通过配置达成，故裁定向方案 (b)。完整证据与备用出路记于 Backend agent memory 与部署纪要。
