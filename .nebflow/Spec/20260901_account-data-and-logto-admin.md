> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# NebLink/Nebflow 账号数据关系 + Logto 管理员验证调研

- 日期：2026-09-01
- 类型：调研分析（纯只读，未改任何代码/配置/实例）
- 回答作者疑问：「neblink 和 nebflow 的账号数据共同吗？」「dashboard 的管理员验证怎么做，logto 有提供吗？」
- 代码证据源：`~/.nebflow/projects/neblink-server/`（@ feat/logto-deploy 系，2026-09-01 GitHub 移除后状态）+ `docs/Nebflow/` 既有纪要 + Logto 官方文档

---

## 主题一：neblink 与 nebflow 账号数据关系

### 1. 结论先行（3 句话）

1. **同一身份源、数据分存储**：登录凭证/邮箱/密码/头像 URL 的权威源是 **Logto**（2026-09-01 GitHub OAuth 移除后唯一身份源）；neblink-server 持有**独立的业务存储**（SQLite `neblink.db`），两者通过 **Logto sub 作为统一主键**关联——不是「完全一体」，也不是「两套账号」。
2. **主键级联统一**：Logto sub = 官网 session.userId = neblink-server `users.id` = 客户端设备归属。设备、好友、消息、网络、会话全部挂在同一个 sub 下（`users.id`），无一例外。
3. **neblink-server 的 users 表是 Logto claims 的惰性快照**：只在设备 enroll/register 时 upsert（email/avatar 同步，**name 恒不同步**——upsert 调用方传 None）。改名/换邮箱在 neblink 侧延迟到下次 enroll；官网每次登录读新 claims 即时生效。这是「session claim 快照问题」的实态。

### 2. 用户存储结构（users 表）

`store.rs:100-108`（CREATE TABLE）+ `store.rs:215-224`（迁移列）：

```sql
CREATE TABLE users (
    id           TEXT PRIMARY KEY,   -- Logto 用户：id = Logto sub（直接复用，无映射表）
    github_id    INTEGER UNIQUE NOT NULL,  -- 遗留 GitHub 字段；Logto 行用合成负数（MIN(github_id)-1，永不与真 GitHub 正数冲突，store.rs:1576-1587）
    email        TEXT,
    name         TEXT,               -- Logto 用户恒 NULL（upsert 从不写 name）
    avatar_url   TEXT,               -- Logto picture claim 同步（C1 修复）/ 上传落库（C2 修复）
    created_at   INTEGER NOT NULL,
    last_login_at INTEGER,
    -- 迁移追加列：
    github_login TEXT,               -- 遗留 GitHub 用户名（不再解析）
    neblink_id   TEXT UNIQUE,        -- 好友 ID（默认=注册邮箱，可自定义，store.rs:1508-1516）
    logto_id     TEXT UNIQUE         -- Logto sub 冗余列（双保险唯一索引）
);
```

**主键**：Logto 用户 `users.id = logto_sub`（Logto 签发的 UUID），`upsert_logto_user`（store.rs:1534-1601）INSERT 时 `id = logto_sub AND logto_id = logto_sub` 双写；遗留 GitHub 行保留 uuid id 与正数 github_id，两族永不冲突。`resolve_user`（store.rs:390-401）把验证通过后的 claims.sub **原样**当 user id 用——没有 sub→内部 id 的映射表。

### 3. 账号关联：token → neblink-server 用户

登录全部在客户端侧完成（RFC 8628 device flow / auth code），neblink-server 只做**验 token + 换长期设备凭据**：

```
客户端（PKCE/官网 OAuth）→ Logto 签发 access token
  → POST /api/device/register（Bearer，routes.rs:523-554）
    → verify_token_dual（routes.rs:138-178）三通道：
      ① HS256 共享密钥 NEBFLOW_JWT_SECRET（NebLink 自签 JWT，iss=neblink）
      ② RS256/ES384 对 Logto /oidc/jwks 验签（NEBLINK_JWKS_URL，kid 缓存 1h + 强制刷新一次）
      ③ opaque token（43 字符非 JWT）→ /oidc/me 用户信息端点 introspect（fail-closed）
    → enroll_response_for_user（routes.rs:436-512）：
      upsert_logto_user（#290 gap ①：首次 enroll 物化 users 行）
      → get_or_create_default_network（account = network）
      → enroll_device 铸长期 device_token（device_credentials 表，sha256 存哈希）
      → register_credential_session（#290 gap ②：deviceToken 立即可作 Bearer）
```

- **Logto 是唯一身份源**：GitHub OAuth 端到端移除（`oauth.rs` 头部注释、main.rs:91-94、auth.rs Claims 注释均 2026-09-01 落笔）；`users.github_id` 列保留仅供历史行，无代码路径解析（resolve_user 注释）。
- 认证边界 = **token 签名**：HS256 用共享密钥，RS256/ES384 用 JWKS 公钥，opaque 用 provider introspect——sub 只是 claims 里的标识符，信任来自验签/内省结果。

### 4. 「一个账号体系」的实态（设备/好友/头像归属）

![账号数据关系](/Users/dev/.nebflow/docs/Nebflow/assets/20260901_account-data.svg)

| 数据 | 归属 | 证据 |
|---|---|---|
| 网络 | `networks.owner_id = users.id (= sub)` | store.rs:83-90 |
| 设备 | `devices` 挂 network（PK id+network_id）；`device_credentials` 同理；`sessions.user_id` | store.rs:91-99, 116-133 |
| 好友/消息 | `friendships.user_lo/user_hi`、`conversations.user_a/user_b`、`messages.sender_id`、`read_cursors.user_id` —— 全部是 users.id | store.rs:154-192 |
| 头像 | **双向**：Logto picture claim → `users.avatar_url`（enroll 时，C1）；上传 → 本地文件 + 写 users.avatar_url（C2，routes.rs:685-773）→ 官网侧再 `PATCH /api/my-account {avatar}`（logto-account-api.md §6） | store.rs:439-446；routes.rs:617-622 |
| 好友 ID | `users.neblink_id`（默认=email，可自定义）—— **neblink-server 自有字段，Logto 不知情** | store.rs:1508-1516, 1655+ |
| Hub 提交 | `hub_items.user_id` | store.rs:135-153 |

**结论**：设备、好友、消息、头像 URL 全部挂在同一 Logto sub 下；头像的**权威源是 Logto**（URL 字符串），neblink-server 只是同步快照 + 上传中转。

### 5. 边界与不一致点（改名/头像/邮箱同步）

neblink-server 的 `users` 行是 **enroll 时刻的快照**，刷新时机 = 设备 enroll/register（upsert_logto_user 更新 email/avatar/last_login）：

| 字段 | Logto 改了之后 | 官网（读 Logto claims） | neblink-server（读 users 表） |
|---|---|---|---|
| email | PATCH /api/my-account | 下次登录即新值（token claims 实时） | enroll 时 upsert 更新；日常 auth_refresh 只查不写（routes.rs:323-363）→ **延迟同步** |
| avatar | PATCH /api/my-account | 下次登录即新值（id_token.picture） | enroll 时同步（C1）；上传路径即时（C2）→ **基本同步** |
| name（昵称） | PATCH /api/my-account | 官网 profile 读新值 | **永不同步**：upsert 调用方恒传 name=None（routes.rs:456），UPDATE 分支 `COALESCE(NULL, name)=name` 不生效；`user_public` 对 NULL name 回退到 neblink_id/email 显示（store.rs:1719-1723） |
| username | PATCH /api/my-account（需 step-up 重验，logto-account-api.md §5） | 即时 | neblink 侧无 username 概念，无影响 |
| neblink_id（好友 ID） | —（Logto 无此字段） | 官网看不到 | 自定义值，neblink 独有 |

**回答作者「改名同步」的疑问**：改名（name）在 neblink 好友列表/资料展示里**永远不会同步**——不是延迟，是 upsert 压根不写 name。若期望同步，需在 enroll_response_for_user 里把 Logto 的 name claim（/oidc/me 或 id_token）传入 upsert。邮箱/头像则是「下次 enroll 才刷新」的延迟快照语义。

---

## 主题二：Logto dashboard 管理员验证

### 1. 结论先行（4 句话）

1. **Logto 提供完整的管理员验证体系**：Admin Console 登录走 **OIDC + 内置 admin 角色**（bootstrap 第一个用户自动成为管理员；后续管理员在 Console Users 页分配 admin role）；密码策略、**MFA（TOTP/WebAuthn/邮箱/短信/backup codes）**、审计日志（Audit Logs + `GET /api/logs`）、用户停用/踢会话全部是 Logto 现成能力。
2. **访问方式现状**：`:8443` 公开 bootstrap 窗口已于 2026-08-27 收口（admin 用户 + M2M 就位后，Caddyfile 删块 + ADMIN_ENDPOINT 回退 loopback），今天实测 `auth.neblink.space:8443` TCP 超时——**console 当前只能 SSH 隧道访问 `localhost:3002`**。
3. **程序化拉数不用开 Console**：M2M 应用 `client_credentials` → `/oidc/token`（resource=`https://default.logto.app/api`，scope=`all`）→ `GET /api/dashboard/users/total|new|active` 等 Management API，即 console Dashboard 页面前端同款调用。
4. **推荐方案**：维持 SSH 隧道为主（已收口、最小改动、安全基线）；需要公网访问时用 Caddy 站点块 + **remote_ip 白名单**（固定 IP 管理员），不推荐裸开 :8443。

### 2. Admin Console 登录验证机制

Logto 的 console 鉴权（OSS 源码实证 + 官方文档，机制层面标注「以 packages/core 源码为准」）：

| 环节 | 机制 |
|---|---|
| 首次 bootstrap | 打开 console → 「Create your account」表单（部署纪要 §三 Playwright 实证）→ **创建的第一个用户自动成为 admin** |
| 日常登录 | console 应用走标准 OIDC（authorize → 登录 → redirect 回 `/console/callback`）；core 校验该用户在 `users_roles` 中持有 **admin role**（内置 role，seed 固定）后才授权 console 访问 |
| 授权边界 | 只有 admin role 用户能登录 console 并调 Management API；普通用户无 console 权限（Users → Roles 可分配/回收 admin role） |
| 角色模型 | Logto RBAC：scope（权限）聚合为 role，role 分配给用户或应用（M2M）。内置 admin role + 「Logto Management API access」M2M role（新租户预置，官方文档） |
| 管理员回收 | Users 页停用/删除用户、改密、踢会话（session 管理）即剥夺 console 访问 |

> 需实测：① 当前 bootstrap 管理员账号是否已分配 admin role（Console → Users → 详情 Roles 可见）；② 后续管理员是否用「第二个用户 + 分配 admin role」流程。

### 3. 访问方式现状与推荐方案

**现状（已收口）**：

```
公网 https://auth.neblink.space:8443/console/...   → TCP 超时（2026-09-01 实测，收口已执行）
SSH 隧道  ssh -L 3002:127.0.0.1:3002 root@<vps>    → http://localhost:3002/console/default/welcome
```

证据：Caddyfile:59-62（console 无公开路由）、docker-compose.yml:93-100（`ADMIN_ENDPOINT=http://localhost:3002` + `127.0.0.1:3002:3002` loopback-only）、部署纪要 §三收口清单（2026-08-27 执行）。

**方案对比**：

| 方案 | 改动 | 优点 | 缺点 |
|---|---|---|---|
| **A. SSH 隧道（现状，推荐主用）** | 零改动 | 最小改动；console 永不公网；SSH 本身即认证 | 每次要开隧道；手机不便 |
| B. 重开 :8443 + Caddy remote_ip 白名单 | Caddyfile 加站点块（复用部署纪要 §三已验证形态）+ compose 加 `8443:8443` | 公网可达、TLS 加密、手机可访问 | 暴露端口；依赖固定 IP（IP 变需改配置）；console 自身仍是密码/MFA 单层 |
| C. Caddy Basic Auth 叠加（在 B 之上） | 同 B + basic_auth 指令 | 双因子式防护（OAuth + HTTP Basic） | 凭据管理额外负担；console CSP/redirect 需回归 |
| D. WireGuard/Tailscale 私有网 | 新组件 | 最干净 | 改动最大，杀鸡用牛刀 |

**推荐**：**方案 A 为主**（与收口后的安全基线一致，0 改动）；若作者想要随时可开的公网入口，上 **方案 B**（仅管理员固定 IP，Caddy 片段见下），并建议同时在 Logto 里给管理员开 MFA（见 §5）抵消暴露面。若再谨慎一层，B + C 叠加。

方案 B Caddy 片段（参考部署纪要 §三 已验证的 :8443 形态）：

```
https://auth.neblink.space:8443 {
    @allowed remote_ip 203.0.113.10        # 管理员固定 IP（示例）
    handle @allowed {
        reverse_proxy logto:3002
    }
    respond 403
}
```

（compose 需给 caddy 加 `"8443:8443"` 映射；ADMIN_ENDPOINT 需指回 `https://auth.neblink.space:8443`——注意部署纪要 §三 的 origin 匹配约束：不能写同域 443，必须独立 origin，否则毒化终端用户流。）

### 4. 程序化访问：M2M → Management API（拉 users total/new/active）

官方文档确认（OSS 自托管路径，`docs.logto.io/integrate-logto/interact-with-management-api`）：

1. **凭据**：Console → Applications → M2M 应用（bootstrap 时已建，部署纪要 §三 runbook 第 2 步——Client ID + Secret 经安全渠道交接）；需分配含 Management API `all` scope 的 M2M role（新租户预置「Logto Management API access」role，可直接用）。
2. **换 token**（OSS indicator = `https://default.logto.app/api`）：

```bash
curl -X POST https://auth.neblink.space/oidc/token \
  -H 'Authorization: Basic <base64(AppId:AppSecret)>' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&resource=https://default.logto.app/api&scope=all'
# → {access_token, expires_in: 3600, token_type: Bearer, scope: all}（token 的 sub = App ID）
```

3. **调 dashboard 三 API**（console Dashboard 页面前端同款，OSS core 已实现，`packages/core/src/routes/dashboard.ts`——20260901_logto-dashboard-and-telemetry.md §3 实证）：

```
GET https://auth.neblink.space/api/dashboard/users/total    → {totalUserCount}
GET https://auth.neblink.space/api/dashboard/users/new      → {today:{count,delta}, last7Days:{count,delta}}
GET https://auth.neblink.space/api/dashboard/users/active?date=yyyy-MM-dd → {dauCurve:[…30], dau, wau, mau}
```

其他常用：`GET /api/users?page=&page_size=`（Link + Total-Number 头分页）、`GET /api/logs?logKey=SignIn.*`（审计）。

> 需实测：① M2M 应用与 role 是否已就位（Console → Applications 查 M2M 类型）；② OSS 实例的 resource indicator 是否为默认值（可用 `/api/swagger.json` 或 Console → API resources 核对）。

### 5. Logto 现成的「管理员验证」能力清单

| 能力 | Logto 提供 | 说明 |
|---|---|---|
| **MFA / 2FA** | ✅ | TOTP（authenticator app）、Passkeys/WebAuthn、SMS、Email 验证码、backup codes；Console → Multi-factor authentication 一键开关（官方文档 `docs.logto.io/end-user-flows/mfa`）。管理员 console 登录走同一 OIDC 流，**启用后管理员同样被要求 MFA**（MFA 按登录体验全局生效，非按角色——需注意这是全局开关，会同时作用于终端用户登录） |
| **密码策略** | ✅ | Sign-in Experience 配置：长度（默认 8 起）+ 字符类型要求；`PATCH /api/sign-in-exp` 的 passwordPolicy 字段（需实测本实例配置值） |
| **审计日志** | ✅ | Audit Logs 页面 + `GET /api/logs`；事件含 SignIn、TokenExchange、用户创建/更新、应用/资源变更；可按用户/事件类型过滤；console 登录事件可查 |
| **登录失败防护** | ✅ | 密码错误重试限制、验证码、TOTP 锁定（Sign-in Experience / Security 设置） |
| **会话管理** | ✅ | Users 详情可看会话、踢下线；改密/停用立即生效 |
| **角色/权限** | ✅ | admin role 分配/回收；内置 role 不可删；M2M role 独立管理 |
| **操作审计** | ✅ | 管理端操作（应用创建、MFA 设置等）进 Audit Logs |

**回答作者「管理员验证怎么做、Logto 有没有提供」**：Logto 提供的是**完整的管理员生命周期管理**——身份（OIDC + admin role）、强度（MFA + 密码策略）、追溯（Audit Logs）、回收（停用/踢会话）全部内置，**无需自研**。当前部署只用了 bootstrap 最小集（admin 账号 + M2M），**建议补三项**：① 管理员开 MFA（TOTP）；② 确认/收紧密码策略；③ 定期在 Audit Logs 查 `SignIn` 事件做登录审计。

### 6. 需实测项汇总（本报告标注「需实测」处）

1. SSH 隧道 `ssh -L 3002:127.0.0.1:3002 root@<vps>` → `http://localhost:3002/console/default/welcome` 是否可达（VPS 侧确认 3002 监听）。
2. bootstrap 管理员账号角色（Console → Users → 详情 Roles 应为 admin）。
3. M2M 应用 + 「Logto Management API access」role 是否已建、secret 是否在手。
4. OSS 实例 Management API resource indicator 是否默认 `https://default.logto.app/api`（`/api/swagger.json` 核对）。
5. `svhd/logto:latest` 版本是否含 Dashboard 页面与 MFA 配置页（2026-08-27 拉取，大概率含）。
6. `PATCH /api/sign-in-exp` 当前 passwordPolicy / MFA 开关实值。

---

## 证据索引（file:line）

| 论断 | 证据 |
|---|---|
| users 表结构（id 主键/github_id 负数合成/logto_id/neblink_id） | `neblink-server/src/store.rs:100-108, 215-224, 1534-1601` |
| sub 直接用作用户 id | `store.rs:390-401`（resolve_user）；`store.rs:1588-1600`（upsert 注释） |
| 三通道 token 验证（HS256/JWKS/opaque） | `routes.rs:138-178`（verify_token_dual）；`routes.rs:200-231`（introspect）；`jwks.rs:88-108` |
| enroll 物化 Logto 用户行 + 头像同步（C1） | `routes.rs:436-512`（enroll_response_for_user）；`routes.rs:523-554`（device_register）；`auth.rs:14-34`（Claims.picture） |
| name 恒不同步（upsert 传 None；user_public 回退 neblink_id） | `routes.rs:456`；`store.rs:1719-1723` |
| 头像上传落库（C2）+ PATCH Logto 意图 | `routes.rs:685-773`（update_user_avatar store.rs:439-446）；`routes.rs:617-622` 注释 |
| GitHub OAuth 移除、Logto 唯一身份源 | `oauth.rs:1-9`；`main.rs:91-94`；`auth.rs:14-21` 注释 |
| 好友/消息/网络/设备挂 sub | `store.rs:83-99, 116-192` |
| 部署拓扑与 :8443 收口 | `deploy/docker/Caddyfile:45-62`；`deploy/docker/docker-compose.yml:72-102`；`docs/Nebflow/20260827_logto-deployment.md §三` |
| Dashboard 三 API（OSS core 实现） | `docs/Nebflow/20260901_logto-dashboard-and-telemetry.md §3`（dashboard.ts） |
| Management API M2M（indicator/scope/Basic auth） | Logto 官方文档 `docs.logto.io/integrate-logto/interact-with-management-api` |
| MFA factors（TOTP/WebAuthn/短信/邮箱/backup） | Logto 官方文档 `docs.logto.io/end-user-flows/mfa` |
| Account API（PATCH name/avatar/username，username 需 step-up） | `docs/Nebflow/logto-account-api.md §4-§6`（v1.42 实测） |
