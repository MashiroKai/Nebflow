> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Logto Account API 调用序列（账号中心二期：用户自改 昵称/用户名/头像）

> 租户：`https://auth.neblink.space`（Logto v1.42 OSS 自托管）。结论先行：**v1.42 内置终端用户 Account API（`/api/my-account` 族），不需要自研用户资料端点**；头像上传例外——Logto 端点依赖 storage provider（未配置即 400），采用 VPS 轻存储 + `PATCH avatar URL` 方案（见 §6）。
>
> 全部错误码与状态均为 **2026-08-27 真租户实测**（测试用户已清理）。部署源码位置：容器内 `/etc/logto/packages/core/src/routes/account/`。

## 1. 调用总览

| 语义 | Method & Path | 认证 | 关键约束（实测） |
|---|---|---|---|
| 读资料 | `GET /api/my-account` | Bearer 用户 opaque token（scope `openid`） | 受 account-center 字段过滤 |
| 改昵称 | `PATCH /api/my-account` `{name}` | 同上（scope `profile`） | `fields.name=Edit` |
| 改头像 URL | `PATCH /api/my-account` `{avatar: url\|null}` | 同上 | `fields.avatar=Edit`；任意合法 URL |
| 改用户名 | `PATCH /api/my-account` `{username}` | 同上 + **step-up** | 见 §5（冲突 422） |
| 改扩展资料 | `PATCH /api/my-account/profile` `{profile}` | 同上（address 需 `address` scope） | `fields.profile=Edit` |
| 改密码 | `POST /api/my-account/password` `{password}` | 同上 + step-up | `fields.password=Edit` |
| 头像文件上传 | `POST /api/my-account/user-assets/avatar`（multipart `file`） | 同上 | **storage provider 未配置 → 400 `storage.not_configured`（实测）** |
| step-up 凭据 | `POST /api/verifications/password` `{password}` | Bearer 同一 token | 201 `{verificationRecordId, expiresAt}`，TTL 10min |
| 关联/解绑社交身份 | `POST/PUT/DELETE /api/my-account/identities(:target)` | 同上 + `identities` scope + step-up | `fields.social=Edit`（P1 用，当前未放开） |

## 2. 启用开关（Management API，M2M token）

```bash
# 现状（默认 enabled=false, fields={}）
GET  /api/account-center        # → {enabled, fields:{...}, ...}

# 启用 + 放开字段（枚举严格大小写：'Off' | 'ReadOnly' | 'Edit'，小写 edit 会 400）
PATCH /api/account-center  {"enabled": true,
  "fields": {"name":"Edit","avatar":"Edit","username":"Edit","profile":"Edit","customData":"Edit"}}
```

注意：
- `fields` 枚举值 zod 报错会直出合法值清单（`invalid_enum_value` options），排查方便。
- `profileFields` 是**自定义 profile 字段**（custom_profile_fields）引用，不是 name/avatar——传 `[{"name":"name"}]` 会 400 `custom_profile_fields.entity_not_exists_with_names`。不配自定义字段就不传。
- **当前租户已处于启用态**（本任务 e2e 实测后保留）：`enabled=true, fields={name,avatar,profile,username,customData}=Edit`。`password/social/email/phone` 未放开（password 改密走官网注册时已有通道语义，social 等 P1 时再开）。

## 3. 用户 token 两通道（取舍）

`/api/my-account` 的认证中间件是 `koaOidcAuth`（`middleware/koa-auth/koa-oidc-auth.ts`）：`AccessToken.find(opaque token)` → 要求 **accountId 非空（用户 token）+ scopes 含 `openid`**。**JWT 格式 token 查不到 → 401**；M2M token 无 accountId → 401。即：必须用「无 resource 绑定」的 opaque 用户 access token（oidcScopes 内含 openid/profile/email/address/phone）。

### 3.1 通道 A（推荐，官网前端直连）
用户在官网走标准 OIDC authorize（SPA `neblink-web` 客户端）：
`GET /oidc/auth?client_id=<SPA>&redirect_uri=<官网回调>&response_type=code&scope=openid profile&PKCE` → code 换 token（无 resource 参数 → opaque token，scope openid+profile）→ 前端 `fetch('https://auth.neblink.space/api/my-account', {headers:{Authorization:'Bearer '+at}})`。

**CORS 已验证放开**：`/api/my-account` 前缀在 `koaCors(…, [accountApiPrefix, verificationApiPrefix])` 的 any-origin 白名单里（koa-mount 剥掉 `/api` 前缀后 `startsWith('/my-account')` 命中）——跨域（neblink.space → auth.neblink.space）浏览器直调可行，无需官网后端代理。

### 3.2 通道 B（服务端代用户：subject-token + token-exchange）
官网后端需要以用户身份调 Account API 时（如管理工具、迁移脚本）：
1. M2M 调 `POST /api/subject-tokens {userId}` → 201 `{subjectToken: "sub_…", expiresIn: 600}`（**一次性消费**，10 分钟）
2. `POST /oidc/token`：
   ```
   grant_type=urn:ietf:params:oauth:grant-type:token-exchange
   client_id=<confidential>&client_secret=…
   subject_token=sub_…&subject_token_type=urn:ietf:params:oauth:token-type:access_token
   scope=openid profile
   ```
   → 200 opaque access token（3600s，sub=该用户）

**token-exchange 的客户端硬前提（实测踩坑）**：
- 必须 **confidential 客户端**（type `Traditional`）。SPA/Native（public，`token_endpoint_auth_method=none`）→ 401 `invalid_client`。
- 必须 `customClientMetadata.allowTokenExchange=true`（`PATCH /api/applications/{id}` {"customClientMetadata":{"allowTokenExchange":true}}）。不开启 → 400 `requested grant type is not allowed for this client`。
- **`oidcClientMetadata.grantTypes` 字段会被 API 静默忽略**（schema 里根本没有该字段，grant 集合由类型+customClientMetadata 派生，见 `oidc/utils.ts getConstantClientMetadata`）——别在这上面浪费时间。
- 已建好的客户端：`neblink-webapi`（type Traditional，redirect `https://auth.neblink.space/callback`，allowTokenExchange=true），凭据在 `~/.nebflow/logto-webapi.env`（600）。

## 4. 实测请求/响应样例

```bash
AT=<opaque access token>
# 读
curl -H "Authorization: Bearer $AT" https://auth.neblink.space/api/my-account
# → 200 {"id":"…","username":"qal_1d06ec","name":null,"avatar":null,"lastSignInAt":…,
#        "createdAt":…,"updatedAt":…,"profile":{},"applicationId":"<签发token的client>",
#        "isSuspended":false,"hasSecurityVerificationMethod":true}

# 改昵称（一步生效，响应即新资料）
curl -X PATCH -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' \
  -d '{"name":"QA User One (renamed)"}' https://auth.neblink.space/api/my-account
# → 200 {"name":"QA User One (renamed)", …}

# 改头像 URL（任意合法 URL；null 清除）
curl -X PATCH … -d '{"avatar":"https://neblink.space/logto/logo-bright.png"}' …/api/my-account   # 200, avatar 回显
curl -X PATCH … -d '{"avatar":null}' …/api/my-account                                            # 200, avatar=null
```

## 5. 改用户名：step-up 验证 + 冲突语义（全套实测）

改 username 是敏感操作，服务端要求**新鲜的身份复核**（`logto-verification-id` header 指向活跃 verification record，`isUserPermissionVerificationRecord` 认 Password / UserPermissionValidation 类验证码两类）。网站侧实现 =「重新输入密码」弹层：

```bash
# ① step-up：同一 access token 调（注意：挂在 userRouter 的 /api/verifications，不是 experience 那族）
curl -X POST -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' \
  -d '{"password":"<用户密码>"}' https://auth.neblink.space/api/verifications/password
# → 201 {"verificationRecordId":"s8ociv29…","expiresAt":"2026-08-27T10:56:41.796Z"}   # TTL 10min

# ② 带 header 提交新用户名
curl -X PATCH -H "Authorization: Bearer $AT" -H "logto-verification-id: <verificationRecordId>" \
  -H 'Content-Type: application/json' -d '{"username":"new_name"}' …/api/my-account
```

实测语义表：

| 场景 | 结果 |
|---|---|
| 不带 header 改 username | 401 `verification_record.permission_denied` "Permission denied, please re-authenticate." |
| header + 密码正确 + 新值合法 | 200，`username` 即时更新（响应回显） |
| header + 目标值已被占用 | **422 `user.username_already_in_use`** "This username is already in use." |
| 改回自己原值 | 200（幂等通过） |
| username 置 null | 需保留至少一个登录标识（`assertUserHasRemainingIdentifier`，会连带查 SSO 身份数） |
| username 字符集 | `[a-zA-Z0-9_]`（连字符拒，MGMT 建号同规则）+ sign-in experience 的 usernamePolicy 校验 |

错误码速查（Account API 常见）：`account_center.not_enabled`（未启用开关）/ `account_center.field_not_editable`（fields 未放开 Edit）/ `auth.unauthorized`（scope 缺 profile 等）/ `guard.invalid_input`（zod，含合法枚举 options）/ `storage.not_configured`（头像上传无 storage provider）。

## 6. 头像存储方案（定案：VPS 轻存储 + PATCH URL）

Logto 原生上传链（`POST /api/my-account/user-assets/avatar`）把文件写 storage provider（OSS/S3 系），**本租户未配置 → 400 `storage.not_configured`（实测）**。为头像单独引入云对象存储不值得（配置面、凭据面、成本全增加）。

**定案**（符合 Manager「VPS 轻存储/测试域静态路径」倾向）：
1. 上传走 **neblink-server 新增一个小端点**（如 `POST /api/avatar`，Bearer 用户 token 鉴权）：校验类型（jpeg/png/gif/webp/bmp，拒 svg——对齐 Logto 的 mime 白名单）与大小（建议 ≤2MB），落盘 VPS 本地目录（如 `/var/www/avatars/<userId>/<rand>.<ext>`，覆写式每用户一文件即可）。
2. Caddy 加一条静态路由（`file_server`，路径如 `https://neblink.space/avatars/*`，Cache-Control 长缓存 + immutable）。
3. 前端拿到 URL 后 `PATCH /api/my-account {"avatar":"https://neblink.space/avatars/…"}`——**avatar 字段就是纯 URL 存储**（实测任意合法 URL 可写可清除），展示侧（登录页/Account API 读侧）直接消费该 URL。

取舍记录：不配 Logto storage provider 的代价 = 用户无法在 Logto 官方 account center UI（若未来启用）里自传头像；我们 BYO-UI 自绘 /settings 页，此路径不经过，无实际损失。

## 7. e2e 参考脚本（本批实测的完整链）

- `/private/tmp/nb-logto/account_e2e.py` — 启用开关 → 建测试用户 → subject-token → token-exchange → GET/PATCH name → username 无 header 401 → 头像上传 400 storage.not_configured。
- `/private/tmp/nb-logto/login_e2e.py` — **脚本化完整登录**（authorize → `PUT /api/experience` → `POST /api/experience/verification/password` → `POST /api/experience/identification` → `POST /api/experience/submit` → redirectTo 带 code → code 换 token）→ step-up `POST /api/verifications/password` → username 冲突 422/成功 200/还原 200。这一条同时验证了「官网自绘登录页可用的纯 API 登录链」（BYO-UI 的后端参照）。
- 关键坑（脚本里都踩过并修正）：interaction 创建是 **`PUT /api/experience`**（不是 /interaction）；提交是 **`POST /api/experience/submit`**；code 在跟随重定向后的最终 callback URL 里解析；step-up 用 userRouter 的 `/api/verifications/password`（experience 那族的 verification record 不落 DB、不带 userId，过不了 Account API 的复核——实测 401）。

## 8. P1 预留：GitHub 账号关联（只列清单，未实施）

- 端点族（源码确认）：`POST/PUT /api/my-account/identities`（body `{newIdentifierVerificationRecordId}`，PUT 允许替换）/ `DELETE /api/my-account/identities/:target`；前置 = `fields.social=Edit`（当前未放开）+ scope `identities` + step-up。
- 绑定 UX = experience 的 social verification 走一遍 GitHub OAuth 回环拿 verification record，再 POST identities。**sign-in methods 不加社交项，就不会出现独立 GitHub 登录入口**（保持「账号关联」语义）。
- 需要用户侧动作：第二个 GitHub OAuth App（回调 `https://auth.neblink.space/callback/<connector-id>`）+ connector 配置——届时另出步骤清单。

## 9. 安全注记与 Backlog

- 凭据：`neblink-webapi` client secret 落 `~/.nebflow/logto-webapi.env`（600，git 白名单外）；本文档不回显任何密钥值。
- subject-token 是高权限模拟通道，只限服务端使用、用后即焚（一次性消费）；官网前端永远走通道 A。
- 本批租户变更清单（审计）：account-center 启用 + 5 字段 Edit；新建客户端 `neblink-webapi`（Traditional + allowTokenExchange）；测试用户全部删除（含分页补扫）。
- Backlog：①P1 GitHub connector 配置 + `fields.social=Edit`；②手机号（P2，押后——identifier 双守卫见 registration-api.md §背景）；③若未来想用 Logto 官方 account center UI，再评估 storage provider。
