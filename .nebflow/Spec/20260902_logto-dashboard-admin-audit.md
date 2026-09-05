> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Logto 管理控制台（dashboard）与管理员账号绑定审计——新域切换后验证

> 2026-09-02 · **只测不改**（独立验证，未修改任何配置/代码/数据；登录测试产生的审计日志属测试行为本身）
> 范围：Logto 自托管（VPS 203.0.113.10，容器 neblink-logto v1.42.0 / neblink-logto-db postgres:17），域名统一后唯一域 = `https://auth.nebflow.space`（issuer `auth.nebflow.space/oidc`），旧域 `auth.neblink.space` 保留 Caddy 兜底
> 数据来源：VPS 容器/DB 实证 + 公网 HTTP 探测 + SSH 隧道内 Playwright 浏览器级验证 + Management API（M2M）+ Logto Experience API 往返
> 凭据：VPS 凭据 `~/.nebflow/vps.env`（chmod 600，未外泄）；M2M 凭据 `~/.nebflow/logto-registration.env`；管理员凭据由作者本次会话提供（**不入本报告，见 §5 建议**）

---

## 0. 结论摘要（3 句话）

1. **Dashboard 与管理员绑定在新域下一切正常**：console（SSH 隧道 `localhost:3002`）可达、SPA 加载正常、bootstrap 管理员（username `Mashiro`）登录成功并进入 `/console/get-started`；Dashboard/Applications/Sign-in & account/Connectors/User management/Settings 六页抽查全部渲染且数据与 Management API 完全一致。
2. **新域登录链路全绿**：OIDC discovery（issuer=新域）、公开 sign-in-exp（email+password 启用、社交 github/google）、Experience API 往返（含错密负例 422、管理员 username 标识符类型）、终端用户登录页渲染（邮箱/密码表单 + Continue with GitHub/Google 按钮）——全部通过。
3. **发现 2 个值得处理的点（均非故障）**：① bootstrap 管理员自 08-27 创建后从未登录过 console，本次是迁移后首次登录验证（建议常态巡检）；② 旧域 OIDC discovery 文档存在 issuer（新域）/端点（旧域）不一致，严格校验 issuer 的客户端在旧域兜底路径上可能被拒（低危，正式入口为新域）。

---

## 1. 部署现状确认（审计基线）

| 项 | 实测值 | 证据 |
|---|---|---|
| Logto 容器 | `neblink-logto` Up 4h（域名迁移时重启），端口 `3001/tcp, 127.0.0.1:3002->3002/tcp` | `docker ps` |
| 版本 | **v1.42.0**（console 页脚显示） | Playwright 提取 |
| ENDPOINT env | `https://auth.nebflow.space`（迁移已生效） | `docker exec neblink-logto env` |
| ADMIN_ENDPOINT | `http://localhost:3002`（loopback-only，console 无公网路由） | 容器 env + Caddyfile（无 :8443/console 站点块） |
| 数据库 | `neblink-logto-db` healthy，用户 41 = default 40 + admin 1 | psql 实证 |
| 租户 | `default`（业务）+ `admin`（console 管理租户） | `tenants` 表 |

---

## 2. 逐项测试结果

### 2.1 Dashboard（管理控制台）可访问性 ✅

**console 无公网路由（设计如此，非缺陷）**：`https://auth.nebflow.space/console` 返回的是**终端用户 sign-in experience SPA 壳**（`window.logtoSsr` 含 signInExperience 数据），不是管理控制台——与部署纪要 §三「SPA 壳假象」一致。Caddyfile 已删 :8443 块，console 仅容器回环可达。

**正式访问路径 = SSH 隧道**：
```
ssh -L 3002:127.0.0.1:3002 root@203.0.113.10   →   http://localhost:3002/console
```

| 检查点 | 结果 | 证据 |
|---|---|---|
| 隧道内 console SPA 加载 | ✅ HTTP 200，真 console 应用（`/console/assets/index-*.js` 751KB / vendors 1.5MB 均 200） | curl |
| `/console` → 登录页跳转 | ✅ → `/sign-in?app_id=admin-console`（admin 租户体验，username+password 表单渲染） | Playwright |
| console 登录负例（错密） | ✅ 提交 `Mashiro`+错密 → admin 租户 Experience API `POST /api/experience/verification/password` **422** → UI 显示 "Incorrect account or password. Please check your input."，停留在登录页 | Playwright 截图 `console-3-signin.png` |
| console 登录正例 | ✅ `Mashiro` + 正确密码 → `/console/get-started`，console v1.42.0 加载，无 5xx、无 JS 错误 | Playwright 截图 `console-4-after-login.png` |

### 2.2 管理员账号状态 ✅（见 §5 建议 ①）

DB 实证（`users` / `users_roles` / `roles`）：

| 项 | 值 |
|---|---|
| 管理员用户 | `4kny94cvrxqs`，tenant=`admin`，username=`Mashiro`，**primary_email = NULL**，password_encryption_method 有值（密码已设） |
| 角色 | `default:admin`（j7clhgd9q7ruxk69xpzot）+ `user`（id57n264jashjgjaemyy2）——**admin 角色在位** ✅ |
| 社交绑定 | `identities = {}` —— **无 GitHub/Google 绑定** |
| 最后登录 | `2026-08-27 04:02:58`（bootstrap 当天）——**迁移后从未登录过 console，本次为首验** |

**邮箱/密码是否可登录**：管理员账号**无邮箱**，登录方式为 **username + password**（admin 租户 sign-in experience 即 username+password 主登录，DB 与登录页表单双重实证）。邮箱+密码登录方式在 **default 租户**（终端用户）已启用且实测通过（§2.4/§2.5）。

**社交绑定入口是否可用**：✅ 用户详情页存在 **Social connections** 区块（"The user links third-party accounts for social sign-in..."），对已绑定账号显示连接器+User ID+Manage 按钮。实测样本：真实账号 `user@example.com`（63mdczgnd8gy）已绑定 **GitHub（User ID 1234567890）** + **Google（User ID 116266933021466550461）**——UI 与 DB `identities` 完全一致；Token 状态 Inactive（社交 access token 过期，属正常，登录时刷新）。**未实际发起第三方绑定**（按任务要求避免外部依赖）。截图 `console-user-detail.png`。

### 2.3 Dashboard 核心功能抽查（登录后）✅ 六页全过

| 页面 | 实测内容 | 与 API 一致性 |
|---|---|---|
| **Dashboard**（`/console/dashboard`） | Total users **40**、New today **1 (+1)**、New past 7 days **40 (+40)**、DAU 0、WAU **27 (+27)**、MAU **27 (+27)**、30 天 DAU 曲线 | ✅ 与 Management API `dashboard/users/total\|new\|active` 完全一致（截图 `console-Dashboard.png`） |
| **Applications** | 6 应用：nebflow-website（Traditional）、nebflow-desktop（Native|Device flow）、nebflow-desktop-pkce（Native）、neblink-webapi（Traditional）、**neblink-bootstrap（M2M）**、**neblink-registration（M2M）** | ✅ 与 `/api/applications` 一致（截图 `console-Applications.png`） |
| **Sign-in & account**（SIE） | 五 tab（Branding/Sign-up and sign-in/Collect user profile/Account center/Content）：Branding 品牌色 **#07C160**、logo/favicon（neblink.space 资源）、暗色模式；**Sign-in：Email+Password、Username+Password**；**SOCIAL：GitHub、Google**、自动关联开启 | ✅ 与 `/api/sign-in-exp` + 公开 sign-in-exp 一致（截图 `console-sie-signup.png`、`console-sie-editor.png`） |
| **Connectors** | Social connectors：**Google、GitHub** 均列出；banner "You've set up connectors" | ✅ 与 `/api/connectors` 一致（截图 `console-social-connectors.png`） |
| **User management** | 用户列表（来源应用 + 最近登录列）：staging-test@nebflow.space（nebflow-website，**2026-09-02 今日登录**）、si_probe_a/b、qa 系、user@example.edu.cn、user@example.com 等 | ✅ 与 `/api/users`（40 条分页）一致（截图 `console-User-management.png`） |
| **Settings** | OIDC configs（session TTL）、Signing keys（EC 密钥 `rgx4t21gg2wp4a36lxldm` Current）、rotate 入口 | ✅（截图 `console-Settings.png`） |

> 注：SIE 页首次访问会显示「Customize sign-in experience」引导卡，点 Get started → Got it → Skip 后完整编辑器出现——首次访问引导，**非缺陷**（§5 建议 ③）。

### 2.4 API 侧印证（新域）✅

| 端点 | 结果 |
|---|---|
| `GET /oidc/.well-known/openid-configuration` | 200，issuer=`https://auth.nebflow.space/oidc`，token/jwks/userinfo 端点均新域 |
| `GET /api/.well-known/sign-in-exp` | 200：`signIn.methods` = email+password（isPasswordPrimary）+ username+password；`socialSignInConnectorTargets` = [github, google]；passwordPolicy（min 8 / 字符类型 3 / pwned 拒绝） |
| Management API（M2M `client_credentials`，resource=`https://default.logto.app/api`，scope=all） | `dashboard/users/total`=40、`new`=1(+1)、`active` DAU 曲线；`/api/users`（total-number 40）；`/api/applications` 6 条；`/api/connectors` github-universal+google-universal；`/api/sign-in-exp`；`/api/swagger.json` 200 |
| Experience API v2 往返 | nav（带 PKCE）→ `PUT /api/experience` **204** → `POST /api/experience/verification/password` 错密 → **422 `session.invalid_credentials`**（未知账号 / 真实账号 / 管理员 username `Mashiro` 三类标识符均正确走完验证链） |
| 终端用户登录页（新域） | OIDC authorize（PKCE）→ `/sign-in?app_id=ih1w2ihuvwefvgvnkzqr3`，渲染 identifier+password 表单 + **"Continue with GitHub" / "Continue with Google"** 按钮，零 JS 错误（截图 `enduser-signin-newdomain.png`） |

### 2.5 旧域 auth.neblink.space 兜底 ✅（带 1 个低危观察，见 §5 ②）

| 检查点 | 结果 |
|---|---|
| 根路径 | 302 → `https://auth.nebflow.space/unknown-session`（**统一指向新域**，迁移语义正确） |
| OIDC discovery | 200（端点按 Host 动态生成为旧域；**issuer 固定为新域**——见 §5 ②） |
| sign-in-exp | 200，socialTargets=[github, google] 一致 |
| console | 同新域：返回 SPA 壳，非 console 入口（console 仅隧道） |

---

## 3. 截图证据

存于 `~/.nebflow/docs/Nebflow/assets/20260902_logto-dashboard-admin-audit/`：

| 文件 | 内容 |
|---|---|
| `console-3-signin.png` | console 登录页（admin 租户，username+password 表单） |
| `console-4-after-login.png` | 登录成功 → `/console/get-started`（v1.42.0） |
| `console-Dashboard.png` | Dashboard：40 用户 / 今日 +1 / WAU 27 / DAU 曲线 |
| `console-Applications.png` | Applications：6 应用（含 2 个 M2M） |
| `console-sie-editor.png` / `console-sie-signup.png` | SIE 编辑器：Branding + Sign-up and sign-in（Email+Password、GitHub、Google） |
| `console-social-connectors.png` | Connectors：Google + GitHub |
| `console-User-management.png` | User management 用户列表 |
| `console-user-detail.png` | 用户详情：Social connections（GitHub/Google 已绑 + Manage 按钮） |
| `console-Settings.png` | Settings：OIDC configs / 签名密钥 |
| `enduser-signin-newdomain.png` | 新域终端用户登录页（邮箱/密码 + 社交按钮） |

---

## 4. 发现的问题（严重度标注）

| # | 严重度 | 问题 | 状态 |
|---|---|---|---|
| ① | **中** | **bootstrap 管理员迁移后从未登录过 console**（last_sign_in=08-27 bootstrap 当天）；本次审计是迁移后首次登录验证——此前 dashboard 功能与管理员登录自部署后未做过系统性验证 | 已验证可用；巡检缺失 |
| ② | **低** | 旧域 OIDC discovery 文档 **issuer（`auth.nebflow.space/oidc`，固定 ENDPOINT env）与端点（`auth.neblink.space/oidc/...`，按请求 Host 动态）不一致**；严格校验 issuer-origin 一致的 OIDC 客户端在旧域兜底路径可能被拒 | 正式入口为新域，兜底场景低风险 |
| ③ | **低** | SIE console 页首次访问只显示「Customize sign-in experience」引导卡（点 Get started → Got it → Skip 后编辑器出现），首次使用者可能误以为配置页缺失 | 非缺陷，操作指引问题 |
| ④ | **低** | 社交 connector Token 状态 Inactive（GitHub/Google 身份链接在位，access token 过期属正常生命周期） | 无影响 |
| ⑤ | **提示** | 管理员账号**无邮箱**（仅 username+密码）：无法走邮箱找回/重置流程；且密码与历史 CloudBase 遥测后台密码相同（记忆文件实证），本次会话中明文传递过 | 建议轮换+补邮箱 |
| ⑥ | **提示** | console 无公网路由（SSH 隧道唯一入口）——符合 2026-08-27 收口安全基线，非缺陷；如需公网入口按 20260901 报告方案 B（Caddy remote_ip 白名单） | 设计如此 |

## 5. 修复建议（供后续安排，本次未执行）

1. **① 管理员巡检常态化**：每月经隧道登录 console 一次，配合 Audit Logs 查看 `SignIn` / `TokenExchange` 事件；管理员操作留痕。
2. **② 旧域一致性（可选）**：若需保留旧域兜底，接受 issuer/端点不一致（正式客户端全部切新域后无影响）；彻底方案 = 旧域 Caddy 对 `/oidc/.well-known/openid-configuration` 301 到新域，或后续退役旧域。
3. **③ 操作说明**：在部署文档补一句「console 首次打开 Sign-in & account 需点 Get started 进入编辑器」。
4. **⑤ 管理员加固（建议优先）**：为 admin 账号补 `primary_email`（console User management 或 Management API）、**轮换密码为独立强密码**、开启 MFA（TOTP）——MFA 为全局开关，会同时作用于终端用户登录，启用前需评估。
5. **一般**：`/root/.logto-bootstrap-token.json`（VPS，08-27 残留，内容为 invalid_client 错误响应）可删除。

---

## 6. 附：证据索引

| 论断 | 证据 |
|---|---|
| 容器/版本/端口/env | `docker ps`；`docker exec neblink-logto env`（ENDPOINT/ADMIN_ENDPOINT/TRUST_PROXY_HEADER）；console 页脚 v1.42.0 |
| console 无公网路由 | Caddyfile（无 console 站点块，注释明示 SSH 隧道方式）；443 `/console` 返回体验 SPA 壳（window.logtoSsr） |
| 管理员账号/角色/绑定 | psql：`users`（4kny94cvrxqs 无邮箱有密码）、`users_roles`（default:admin+user）、`roles`、`sign_in_experiences`（admin 租户 username+password） |
| 社交绑定 | psql `identities`：admin={}、user@example.com={github:1234567890, google:116266933021466550461}；console 用户详情 Social connections 区块 |
| Dashboard/应用/connector 数据 | Management API（M2M client_credentials）与 console 页面 DOM 提取双源一致 |
| 新域登录链路 | OIDC discovery issuer；`PUT /api/experience` 204；错密 422；登录页表单+社交按钮（Playwright） |
| 旧域兜底 | discovery 200（issuer 新域/端点旧域）；根路径 302→新域；sign-in-exp 200 |
| 凭据存放 | `~/.nebflow/vps.env`（600）、`~/.nebflow/logto-registration.env`（M2M）；管理员密码本次会话提供，未落盘 |

---

*审计完成 · 未修改任何配置/代码/数据 · 临时文件与隧道已清理*
