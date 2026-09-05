# Nebflow 客户端登录链路排查报告

- 日期：2026-09-01（用户 12:37 报告）
- 范围：只读排查，未改动任何代码/配置/运行实例
- 状态：根因已确认，修复方案分项输出（含待作者定夺项）

---

## 一、结论摘要（3 句话）

1. **profile 链接错误**：客户端点击头像后打开 `https://neblink.example/profile`，来源是 `activityBar.js:251` 直接消费 `brand.domain` 构建 URL，而 `brand.domain` 是 gateway 注入的 `brand.conf:7` 占位符 `neblink.example`——后端注释（WebSocketRoutes.scala:4690）明确约定该字段「display-only, never consumed to build a URL」，前端是唯一违约点；真实 profile 页在官网 `neblink.space/profile`（已探测 200）。
2. **登录路径与用户期望不符**：客户端 PKCE 打开的是 Logto hosted 授权页（`auth.neblink.space/oidc/auth`，native app client + loopback 回跳），全程不经过官网 `neblink.space/login`；官网登录是另一套 web-app OAuth（`LOGTO_APP_ID` + secret）。两者同一 Logto 租户、同一账号体系，但入口和 UI 各自独立。
3. **头像链全线断裂**：客户端头像数据源是 neblink-server `users.avatar_url`，而该字段**只对 GitHub 用户写入**（`upsert_github_user` store.rs:447），Logto 用户 upsert（store.rs:1647）不写 avatar → 恒 NULL；上传接口 `/api/avatar`（routes.rs:808）无任何调用方，且实现只写文件、不写 store、不 PATCH Logto（注释声明的意图未实现）；客户端 PKCE scope 无 `profile`（拿不到 picture claim）。账号体系统一的**唯一缺口就是头像**——Logto sub 已是官网/neblink-server/客户端的统一主键，设备、好友已同体系。

---

## 二、现状链路（逐段标注证据）

### 2.1 客户端登录（PKCE，现状）

```
点击头像 (activityBar.js:246)
  └ 未登录 → showLoginModal (activityBar.js:254)
      └ startPkceLogin (neblink.js:366) → POST /api/neblink/auth/start
          └ gateway: 生成 verifier/challenge/state (RestApiRoutes.scala:903-906)
              └ authorizeUrl = Logto hosted (LogtoAuthCode.scala:88-105)
                  endpoint = auth.neblink.space（effectiveLogto 嵌入默认, NeblinkModel.scala:183/269）
                  client_id = pkceClientId（native app "nebflow-desktop-pkce"）
                  redirect_uri = http://127.0.0.1:{port}/auth/callback（loopback, RFC 8252）
              └ 浏览器打开 Logto hosted 登录页（不经官网 login）
                  └ Logto 回跳本地 /auth/callback (RestApiRoutes.scala:2725)
                      └ code 换 token (RestApiRoutes.scala:2765-2774)
                          └ enroll neblink-server (LogtoDeviceFlow.register → /api/device/register,
                            routes.rs:638+；verify_token_dual 三条路径 routes.rs:140)
                              └ NeblinkEnrollment.persist 落盘 device.json + 热插拔 + 头像落本地
                                (NeblinkEnrollment.scala:29-56)
```

### 2.2 官网登录（web-app OAuth，与客户端独立）

```
neblink.space/login (nebflow-website LoginContent.tsx)
  └ Email 按钮 → /api/auth/logto (lib/logto.ts authorizeUrl:36)
      └ Logto hosted（web app client：LOGTO_APP_ID + secret，scope 含 profile）
          └ 回跳 /api/auth/logto/callback → setSessionCookies (lib/logto.ts:162)
              └ session = { userId: sub, email, avatarUrl: id_token.picture, bearerToken: at }
                (lib/session.ts:27-45)
```

### 2.3 账号数据（统一主键已存在）

```
Logto sub（auth.neblink.space 单一身份源）
  ├─ 官网：session.userId = claims.sub (session.ts:35)
  ├─ neblink-server：users.id = logto_sub (upsert_logto_user, store.rs:1693-1699)
  │    ├─ network = account（get_or_create_default_network, routes.rs:577）
  │    ├─ device → network 下 enroll（routes.rs:587-613）
  │    ├─ friends → NebLink user 关系表（friends.rs）
  │    └─ avatar_url → ❌ Logto 用户恒 NULL（断裂点）
  └─ 客户端：device.json deviceToken + refresh_token（DeviceCredentialStore.scala:21-31）
```

---

## 三、逐项排查证据

### 排查项 1：neblink.example 全量出现点

| 位置 | 类型 | 说明 |
|---|---|---|
| `brand.conf:7` | 配置 | `domain = neblink.example  # 占位（用户拍板 D4）`——占位符源头 |
| `src/main/scala/nebflow/core/Branding.scala:73` | 运行时 | `val domain = get("domain")`；:144-145 注释明确占位符「must not leak into outbound strings」 |
| `src/main/scala/nebflow/gateway/WebSocketRoutes.scala:4701` | 运行时 | `brandScriptTag` 把 `Branding.domain` 注入 `window.__BRAND__`；:4690 注释「display-only, never consumed to build a URL」 |
| **`src/main/resources/web/js/activityBar.js:251`** | **运行时（违约点）** | `window.open(\`https://${brand.domain}/profile\`, '_blank')` —— 唯一消费 domain 构建 URL 的地方 |
| `src/main/resources/web/js/brand.js:21` | 前端 fallback | `fallback.domain = 'nebflow.space'`（静态托管/测试环境才用；gateway 托管时被注入值覆盖） |
| `BrandingSpec.scala:40` / `BrandInjectionSpec.scala:21` / `IndexWithBrandServeSpec.scala:47` | 测试 | 三处断言 `"neblink.example"`，跟随 brand.conf |
| `PkceLoginSessionSpec.scala:90-120` / `NeblinkEnrollmentPersistSpec.scala:53-121` | 测试 | `https://neblink.example` 仅作测试数据（serverUrl），与品牌无关 |

**结论**：`neblink.example` 是 brand.conf 占位符；它通过 gateway 注入前端 `brand.domain`，被 `activityBar.js:251` 消费生成 profile 链接。探测确认 `https://neblink.example` 无解析（超时），点击必然打不开。真实 profile 页在官网 `neblink.space/profile`（探测 200）。

### 排查项 2：客户端登录实际流程 vs 用户期望

**现状（代码链路见 §2.1）**：客户端 PKCE → **Logto hosted 页**（`auth.neblink.space`，已探测 `/oidc/jwks` 200）→ loopback 回跳 → 换 token → enroll → 登录完成。**不经过官网 login**。

**官网入口**（§2.2）：`neblink.space/login` 是 web-app OAuth（client = `LOGTO_APP_ID`，redirect = 官网 callback），与客户端 native client 是两个独立 Logto application。

**差距**：
1. 客户端弹的登录页是 Logto 默认 hosted 页（无品牌定制），用户感知「不是官网登录」；
2. 官网登录态（cookie `logto_at/logto_idt/logto_rt`）**与客户端无任何传递机制**——两者靠同一 Logto 租户的账号关联，不共享 session；
3. 但**同浏览器下 Logto 有 SSO 行为**：官网登录后，客户端 PKCE 打开 hosted 页时会命中既有 Logto session，免密一键授权回跳——这是 Logto 原生能力，用户未感知。

**可行性约束**：OAuth native 流**不能**把 redirect 指向官网或经官网中转 code（破坏 PKCE code 安全性）；「官网登录态 → 客户端」只能走两条正道：① Logto hosted 页品牌化（视觉统一 + SSO 自动通过）；② 官网 session 签发一次性连接凭证（类似现有 pair 机制）供客户端兑换。

### 排查项 3：头像链

**客户端展示链**：
```
activityBar.js renderAvatar (557-583) ← st.device.avatarUrl
  ← fetchNeblinkStatus (neblink.js:54-78) /api/neblink/status
    ← gateway: device.avatarUrl = id.avatarUrl (RestApiRoutes.scala:645)
      ← DeviceIdentityStore.avatarUrl（enroll 时落盘）
        ← NeblinkEnrollment.persist:55 updateDeviceInfo(avatarUrl)
          ← neblink-server EnrollResponse.avatarUrl（routes.rs:620-626）
            ← store.get_user_profile (routes.rs:614-618) → users.avatar_url
```

**断裂点**（三处）：

| # | 断裂点 | 证据 | 影响 |
|---|---|---|---|
| A | Logto 用户 upsert **不写 avatar_url** | `upsert_logto_user`（store.rs:1647-1699）INSERT 无 avatar/name 字段；唯一写 avatar 的路径是 `upsert_github_user`（store.rs:447） | Logto 账号头像恒 NULL → 客户端/好友/官网代理永远无头像（**主因**） |
| B | 上传接口 `/api/avatar` **无调用方且不持久化** | 官网/客户端代码均无调用（grep 无命中）；实现（routes.rs:808-891）只写 VPS 文件返回 URL，**不写 store、不 PATCH Logto**；注释（routes.rs:741-745）声明的「PATCH Logto /api/my-account」未实现 | 头像无处可设、设了也不落库 |
| C | 客户端 PKCE scope 无 `profile` | `LogtoAuthCode.scala:100`：`openid offline_access email` | id_token 无 picture claim，无法从 token 读头像 |

**对比**：GitHub 登录用户（legacy 链）有头像（`upsert_github_user` 从 GitHub API 拿 avatar_url）；Logto 邮箱登录用户无——与用户报告「没有头像」完全吻合。官网侧能显示头像的唯一路径是 `id_token.picture`（session.ts:37），官网 profile 页**没有头像上传 UI**（app/profile/page.tsx 仅展示）。

### 排查项 4：账号体系统一现状

| 维度 | 现状 | 证据 |
|---|---|---|
| 账号主键 | **已统一**：Logto sub = 官网 userId = neblink-server users.id | session.ts:35；store.rs:1693-1699 |
| 设备 | **已统一**：network = account，设备 enroll 到 network；官网 /api/profile 代理展示（forward bearerToken → `/api/user/profile`） | routes.rs:577-613；website app/api/profile/route.ts:115-118 |
| 好友 | **已统一**：NebLink user 关系表；客户端 friends/contacts 面板 | friends.rs |
| 头像 | **断裂**：Logto picture 与 neblink-server users.avatar_url 不同步 | 见排查项 3 |
| 登录入口 | **双入口**：客户端 Logto hosted / 官网 login 页（同租户不同 UI） | §2.1 / §2.2 |
| profile 页域名 | **错误**：客户端用占位符 neblink.example | 排查项 1 |

**结论**：账号体系统一的地基（Logto sub 主键）已经打好；缺口集中在「头像同步 + 入口统一 + profile 域名」三处，其中头像是最实质的功能断裂。

---

## 四、修复方案分项

### ① brand.conf domain 定值（修 profile 链接）

**建议：`domain = neblink.space`**（当前官网真实部署域，探测 200；profile 页真实存在）。`nebflow.space` 是品牌最终目标域，但官网当前不在该域部署，定 `nebflow.space` 会再次出现「链接指向不存在的页面」。**待作者定**：若近期官网将迁往 `nebflow.space`，可同步改域名后再定值。

改动范围：
- `brand.conf:7` → `domain = neblink.space`（删除占位注释，改为真值注释）
- 测试断言三处同步：`BrandingSpec.scala:40`、`BrandInjectionSpec.scala:21`、`IndexWithBrandServeSpec.scala:47`
- `Branding.scala:19/73/144` 的「placeholder (D4)」注释更新为真值说明
- `activityBar.js:251` 无需改（消费新 domain），但建议加注释说明 domain 已被批准为运行时 URL 输入（或改为独立字段，见备选）

备选（更稳）：`activityBar.js` 不再消费 `brand.domain` 构建 URL，改用新增 `profileUrl` 字段（brand.conf 独立真值字段，与 domain 解耦）——domain 继续保留纯展示语义。**此项改动面小，建议并入**。

验收点：
- [ ] `brand.conf` domain 改为真值并 commit（~/.nebflow 与主仓两个 repo 各自 commit）
- [ ] `sbt test` 通过（BrandingSpec / BrandInjectionSpec / IndexWithBrandServeSpec 断言与新值一致）
- [ ] 客户端登录后点击头像 → 新标签打开 `https://neblink.space/profile`，`curl -I` 返回 200 + text/html

### ② 登录路径统一到官网 login

**方案 B1（推荐，低成本）——Logto hosted 页品牌化 + 同浏览器 SSO**：
- Logto sign-in-experience 配置品牌 logo/色/CSS（`auth.neblink.space` 登录页与官网视觉统一；Logto 管理端配置，属部署运维项，可写脚本落库）
- 官网 login 页保留；客户端 PKCE 不动
- 用户体验：官网登录后，客户端点登录 → hosted 页显示品牌登录界面且**自动免密回跳**（Logto 既有 session，SSO 原生行为）

改动范围：Logto 部署配置（非代码仓库）；可选在部署目录加 `scripts/brand-sign-in.md` 文档。

**方案 B2（中成本）——官网 session 签发一次性连接凭证**：
- 官网登录后生成一次性 ticket（绑定 Logto sub）→ 客户端 UI 两段式：打开官网 → 获取连接码 → 客户端输入兑换 deviceToken
- 改动：官网加发码/兑换 API、neblink-server 加 ticket 签发/兑换、客户端登录模态改两段式
- 风险：绕过 OIDC 标准流，需安全评审；**不建议作为首选**

**方案 B3（不可行）**：客户端 authorize redirect 指向官网——OAuth 规范不允许（native client 必须 loopback 回跳；code 经中间人违背 PKCE 模型）。**排除**。

验收点（B1）：
- [ ] 未登录浏览器访问 `auth.neblink.space/oidc/auth?...` 显示品牌化登录页（logo/配色）
- [ ] 同浏览器先在 `neblink.space/login` 登录 → 再在客户端点登录 → hosted 页直接回跳（无二次输密码），客户端显示「连接成功」
- [ ] 客户端登录后 `/api/neblink/status` 返回 `loggedIn: true`

### ③ 头像打通（三件套，建议全部实施）

**C1：neblink-server enroll 时同步 Logto 头像**
- `routes.rs`（enroll/device_register 尾部，:614-626 附近）：若 `get_user_profile` 的 avatar 为空，用 access token 调 `/oidc/me`（或解析 id_token picture）→ 写 `users.avatar_url`
- `store.rs`：`upsert_logto_user` 增加 avatar/name 参数（或新增 `update_user_profile` 方法）
- 注意：`/oidc/me` 的 claims 受 scope 限制——客户端 PKCE scope 无 `profile` 时拿不到 picture，需配合 C2

**C2：客户端 PKCE scope 加 `profile` + gateway 解析 picture 落本地**
- `LogtoAuthCode.scala:100`：scope 增加 `profile`
- `RestApiRoutes.scala`（callback 成功路径）：从 id_token 解析 `picture` → `ms.updateDeviceInfo(avatarUrl = picture)`（立即生效，不等 enroll 返回）
- 影响面小（scope 加一个值 + 回调多写一个字段），Logto 端无需改应用配置（profile scope 是标准 scope）

**C3：打通上传（补注释缺失的实现）**
- 官网 profile 页加「上传头像」UI → 新 API route（或直接调）→ neblink-server `/api/avatar`（转发 bearerToken）→ 拿 URL 后 **PATCH Logto `/api/my-account {avatar}`**（补上 routes.rs:741-745 注释声明的步骤）→ 同时更新 neblink-server `users.avatar_url`
- `routes.rs upload_avatar`：返回 URL 后追加写 store（一行 UPDATE）
- 客户端无需改（45s syncInterval 后自动显示新头像）

验收点：
- [ ] Logto 用户（已设头像）enroll 后：`SELECT avatar_url FROM users` 非空；客户端 `/api/neblink/status` 的 `device.avatarUrl` 非空
- [ ] 客户端登录后 activity bar 显示头像图片（非 logo 兜底）
- [ ] 官网 profile 上传头像 → `neblink.nebflow.space/avatars/<file>` 可访问（200）→ Logto `/oidc/me` 返回新 picture → 客户端 45s 内 activity bar 头像更新
- [ ] 头像上传冒烟：neblink-server（隔离实例 `cargo run` 非 8080）→ 携带有效 token POST `/api/avatar`（2MiB 内 jpeg/png）→ 返回 200 + url 可访问

### ④ 账号体系统一（设备/好友/头像同一体系）

现状已统一（sub 主键 / 设备 / 好友），补齐项 = ① + ③。可选增强（**待作者定**）：
- 官网 profile 页加「好友」列表代理（/api/friends → neblink-server），与客户端 friends 面板同源
- 官网 profile 页头像区增加「在客户端同步头像」提示

验收点：
- [ ] 同一 Logto 账号：官网 profile 设备列表与客户端 settings 设备列表一致（同一 network/device 数据源）
- [ ] 头像两端一致（官网上传 → 客户端显示；客户端任意入口登录 → 官网显示同头像）

---

## 五、运维观察（非本次修复范围）

- `neblink.nebflow.space` 探测**无响应**（neblink-server VPS 可能停机/网络不通）——影响 enroll、头像文件服务、官网设备代理。**建议先确认 VPS 存活**，否则 ③ 的头像上传与 ② 的登录 enroll 都无法端到端验证。
- `neblink.space`（官网）与 `auth.neblink.space`（Logto）探测正常（均 200）。

---

## 附：证据索引（文件:行）

| 论断 | 证据 |
|---|---|
| 客户端 profile 链接 = brand.domain 占位符 | `src/main/resources/web/js/activityBar.js:251`；`brand.conf:7`；`WebSocketRoutes.scala:4701` |
| domain 设计为 display-only 不得建 URL | `WebSocketRoutes.scala:4690-4691`；`Branding.scala:144-145` |
| PKCE 走 Logto hosted、不经官网 | `LogtoAuthCode.scala:88-105`；`RestApiRoutes.scala:889-916`；`neblink.js:366-376` |
| 官网登录为独立 web-app OAuth | `nebflow-website/lib/logto.ts:16-45`；`app/login/LoginContent.tsx:54-68` |
| 账号主键统一（Logto sub） | `session.ts:35`；`store.rs:1693-1699` |
| Logto 用户不写 avatar_url | `store.rs:1647-1699`（对照 `upsert_github_user` store.rs:447） |
| 上传接口无调用方、不持久化 | `routes.rs:808-891`（注释声明 741-745）；官网/客户端 grep 无 /api/avatar 调用 |
| 客户端 PKCE scope 无 profile | `LogtoAuthCode.scala:100` |
| 官网头像仅来自 id_token.picture | `session.ts:37` |
| enroll 响应 avatar 来自 store | `routes.rs:614-626`；`NeblinkEnrollment.scala:29-56` |
| 状态接口 avatar 输出 | `RestApiRoutes.scala:645` |
