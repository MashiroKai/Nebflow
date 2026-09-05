# 「Email sign-in is not configured」根因报告：staging 登录断裂（Logto env 引号污染）

> 2026-09-02 · 纯分析 + 修复方案（**未改任何配置/代码**）
> 范围：mashiro.staging.nebflow.space（网站 staging）邮箱登录报「Email sign-in is not configured. Please contact the administrator.」
> 数据来源：VPS Logto 容器/DB 实证 + Vercel 部署与运行时日志 + 网站仓库 staging 分支代码 + 线上 HTTP 探测

---

## 0. 结论摘要（3 句话）

1. **「Email sign-in is not configured」不是 Logto 的错**——Logto 侧 sign-in experience 的 Email/password 方法**已启用**（DB 与公开端点双重实证）；这条文案是**网站自己的登录页 UI**（`app/login/LoginContent.tsx` + `lib/auth-i18n.ts:336`）对 URL 参数 `?error=logto_not_configured` 的翻译，该参数只在 `GET /api/auth/logto` 检测到服务器端 `LOGTO_*` 环境变量缺失（`logtoConfig()` 返回 null）时产生。
2. **根因 = 域名迁移期间 Vercel 正式项目（nebflow-website）的 LOGTO env 配置事故，分两阶段**：① 23:46–23:51（域名改绑正式项目后、env 补齐前）staging 部署**完全没有 LOGTO_* env** → 用户看到「Email sign-in is not configured」；② 23:51 补 env 时**值被带上了字面双引号**（`LOGTO_ENDPOINT="https://auth.nebflow.space"`、`LOGTO_APP_ID="ih1w2ihuvwefvgvnkzqr3"`）→ `new URL()` 解析失败，staging 上所有登录路径持续 500 / server_error，**至今未恢复**。
3. **修复 = 把 nebflow-website 项目 6 个 LOGTO_* env 去掉引号重设（production+preview 两 target）后重新部署 staging**；Logto 侧零改动（client、redirect 白名单、sign-in experience 均正常）。验收：`POST /api/auth/signin` 走通 Logto Experience-API 返回 200，`/api/auth/logto` 307 跳到 `https://auth.nebflow.space/oidc/auth`（无引号）。

---

## 1. 时间线（2026-09-01 → 09-02，+08）

| 时间 | 事件 | 证据 |
|---|---|---|
| 09-01 白天 | Logto ENDPOINT 迁移执行（P2）：AUTH_PUBLIC_URL → `https://auth.nebflow.space`，issuer 切换 | OIDC discovery 实证（§3.1） |
| 23:43 | staging 分支 48aca6e（logo 修复）部署 dpl_6Aigxy6 —— **正式项目首个 staging 部署，无 LOGTO env** | Vercel 部署列表 |
| 23:46 | `mashiro.staging.nebflow.space` 域名绑定正式项目 gitBranch=staging | Vercel domain API（createdAt=1788282412485） |
| 23:46–23:51 | **空窗期：域名已指向正式项目但 env 未配** → `GET /api/auth/logto` 走 `logtoConfig()==null` 分支 → 302 `/login?error=logto_not_configured` → **用户所见 banner 的源头** | 代码路径 + 部署时序 |
| 23:51 | 正式项目补 LOGTO_* env（updatedAt=1788282675887），**值带字面双引号** | Vercel env API 时间戳 + 运行时日志 ERR_INVALID_URL |
| 23:52 | afd73ca「redeploy staging with LOGTO env」部署 dpl_ASepkmmx（当前 live） | Vercel 部署列表 |
| 09-02 01:10 | **用户报告「Email sign-in is not configured」**（页面/重定向为迁移空窗期遗留状态） | 归档 20260902-011038 |
| 01:14 起 | 线上探测：signin→400 server_error、logto→500 ERR_INVALID_URL（引号 env 生效中） | Vercel runtime logs（§3.6） |

---

## 2. 关键事实：这不是 Logto 的配置问题

### 2.1 sign-in experience：Email/password 已启用 ✅

Logto DB（VPS，`neblink-logto-db` 容器，postgres:17）`default` 租户：

```sql
SELECT tenant_id, sign_in, social_sign_in FROM sign_in_experiences;
-- default | {"methods":[{"identifier":"email","password":true,"verificationCode":false,"isPasswordPrimary":true},
--                     {"identifier":"username","password":true,"verificationCode":false,"isPasswordPrimary":false}]}
--        | {"automaticAccountLinking":true}   ← social 无启用项，connector 级配置在 connectors 表
```

公开端点（同源实证）：`GET https://auth.nebflow.space/api/.well-known/sign-in-exp` →
`tenantId:"default"`、`signIn.methods[0]={identifier:"email",password:true,isPasswordPrimary:true}`、`socialSignInConnectorTargets:["github","google"]`。

→ **signInMethods 含 EmailPassword，Login 页邮箱+密码输入框本应正常渲染**。

### 2.2 Logto 服务与版本 ✅

- 容器 `neblink-logto` = `svhd/logto:latest`，镜像 label `org.opencontainers.image.version: 1.42.0`（构建 2026-07-30）
- env：`ENDPOINT=https://auth.nebflow.space`（迁移已生效）、`ADMIN_ENDPOINT=http://localhost:3002`、`TRUST_PROXY_HEADER=1`
- `GET https://auth.nebflow.space/oidc/.well-known/openid-configuration` → `issuer: https://auth.nebflow.space/oidc` ✅
- `GET /api/health` 404 属正常（Logto 无此路径，带 `logto-core-request-id` 响应头说明 core 活着）

### 2.3 OIDC web client 与 redirect 白名单 ✅

`applications` 表：`ih1w2ihuvwefvgvnkzqr3`（name=`nebflow-website`）redirectUris 含
`https://mashiro.staging.nebflow.space/api/auth/logto/callback`（staging 已加白）与 `https://neblink.space/...`（旧域）。

### 2.4 对照组：neblink.space（preview 项目）登录链路正常 ✅

`GET https://neblink.space/api/auth/logto` → 307 → `https://auth.neblink.space/oidc/auth?client_id=ih1w2ihuvwefvgvnkzqr3&...`（**URL 无引号、结构正确**，旧域走 Caddy 兜底仍通）。证明代码与 Logto 流程本身没问题，问题只在 staging 所在项目的 env。

---

## 3. 根因证据链：Vercel 正式项目 LOGTO env 带字面双引号

### 3.1 报错文案的真实来源（网站 UI，非 Logto）

| 文件 | 内容 |
|---|---|
| `nebflow-website/lib/auth-i18n.ts:336` | `logtoNotConfigured: "Email sign-in is not configured. Please contact the administrator."`（:194 中文「邮箱登录暂未配置，请联系管理员」） |
| `app/login/LoginContent.tsx:16-17` | `errorKey === "logto_not_configured" ? dict.errors.logtoNotConfigured` |
| `app/api/auth/logto/route.ts:23-27` | `logtoConfig()==null → redirect("/login?error=logto_not_configured")` |
| `lib/logto.ts:21-27` | `logtoConfig()` 任一 `LOGTO_ENDPOINT/LOGTO_APP_ID/LOGTO_APP_SECRET` 缺失/空 → 返回 null |

### 3.2 运行时日志实锤（Vercel，deployment dpl_ASepkmmx，MISS 非缓存）

```
GET /api/auth/logto -> 500
Error: URL is malformed ""https://auth.nebflow.space"/oidc/auth?client_id=%22ih1w2ihuvwefvgvnkzqr3%22&..."
[cause]: TypeError: Invalid URL
  input: '"https://auth.nebflow.space"/oidc/auth?client_id=%22ih1w2ihuvwefvgvnkzqr3%22&...'
  code: 'ERR_INVALID_URL'
```

- `%22` = 编码后的 `"` → `LOGTO_ENDPOINT` 实际值 = `"https://auth.nebflow.space"`（首尾各一个字面双引号）
- `LOGTO_APP_ID` 实际值 = `"ih1w2ihuvwefvgvnkzqr3"`（同样带引号）
- 同一批次 23:51 设置的 `LOGTO_APP_SECRET`、`LOGTO_M2M_CLIENT_ID/SECRET`、`LOGTO_MANAGEMENT_RESOURCE`、`NEXT_PUBLIC_GOOGLE_CONNECTOR_ID` 大概率同样被引号污染（值不可读，按批次推定，修复时一并重设）

### 3.3 引号如何让所有登录路径同时断裂

| 路径 | 代码 | 引号后果 |
|---|---|---|
| `GET /api/auth/logto`（社交按钮/邮箱入口） | `NextResponse.redirect(authorizeUrl(...))` → `new URL()` | **500** ERR_INVALID_URL |
| `POST /api/auth/signin`（内联邮箱+密码表单） | `lib/logto-signin.ts signIn()` → `fetch(""https://..."/oidc/auth")` | fetch 抛错 → `server_error`（线上实测 400 `{"ok":false,"error":"server_error"}`） |
| `GET /api/auth/logto/callback` | `exchangeCode` → fetch token endpoint 同错 | 失败 → 302 `logto_auth_failed` |
| `GET /api/auth/providers` | 只判 `logtoConfig() !== null` | 引号非空 → 误报 `{"logto":true}`（**这就是"配置了却用不了"的迷惑点**） |

### 3.4 线上行为对照（2026-09-02 01:17 实测）

| 探测 | staging (正式项目, 引号 env) | neblink.space (preview, 正常 env) |
|---|---|---|
| `/api/auth/providers` | 200 `{"logto":true}` | 200 `{"logto":true}` |
| `/api/auth/logto` | **500** | 307 → `auth.neblink.space/oidc/auth?...` ✅ |
| `POST /api/auth/signin` | **400 `server_error`** | （未测） |

### 3.5 为什么用户 01:10 看到的是 banner 而不是 500

banner（`?error=logto_not_configured`）只在 env 缺失时产生——对应 23:46 域名改绑后、23:51 env 补齐前的**空窗期**（该窗口内 live 部署 dpl_6Aigxy6 无 LOGTO env）。用户页面/重定向为此时遗留；01:10 之后（含当前）所有请求命中的是**引号 env 部署**，表现为 500 / server_error。两者同源：**迁移期间正式项目 LOGTO env 的配置事故（先缺失、后带引号）**。

---

## 4. 修复方案（可执行，Logto 侧零改动）

> 凭据说明：VPS 凭据在 `~/.nebflow/vps.env`（勿外泄）；Vercel 凭据在本机 `vercel` CLI 登录态。LOGTO_APP_SECRET 等敏感值与 preview 项目（neblink.space 在用、工作正常）同值——从 preview 项目 `vercel env ls` 对照取值，**去引号**后写入。

### 步骤 1：删除正式项目被污染的 env（每个 var、每个 target）

```bash
cd ~/.nebflow/projects/nebflow-website   # 或任意已 link 目录
for V in LOGTO_ENDPOINT LOGTO_APP_ID LOGTO_APP_SECRET LOGTO_M2M_CLIENT_ID LOGTO_M2M_CLIENT_SECRET LOGTO_MANAGEMENT_RESOURCE NEXT_PUBLIC_GOOGLE_CONNECTOR_ID; do
  vercel env rm $V production -y
  vercel env rm $V preview    -y
done
```

### 步骤 2：重设（不带引号，值从 preview 项目对照）

```bash
vercel env add LOGTO_ENDPOINT production   # 粘贴: https://auth.nebflow.space
vercel env add LOGTO_ENDPOINT preview      # 同上（统一新域）
vercel env add LOGTO_APP_ID production     # ih1w2ihuvwefvgvnkzqr3（无引号）
vercel env add LOGTO_APP_ID preview
vercel env add LOGTO_APP_SECRET production # <与 preview 项目同值的 secret，无引号>
vercel env add LOGTO_APP_SECRET preview
# LOGTO_M2M_* / LOGTO_MANAGEMENT_RESOURCE 同法；NEXT_PUBLIC_GOOGLE_CONNECTOR_ID 同样去引号
```

**自检**（重设后、部署前）：`vercel env ls` 后 `vercel env pull .env.local` 并 `grep` 确认值无首尾 `"`。

### 步骤 3：重新部署 staging

```bash
# 方案 A（推荐，走 git 集成）：
cd ~/.nebflow/projects/nebflow-website && git commit --allow-empty -m "chore: fix LOGTO env quoting (re-apply values without quotes)" && git push origin staging
# 方案 B（立即重发同一部署）：
vercel redeploy dpl_ASepkmmx1MPeCSGi19XZbWhppaNi --yes
```

### 步骤 4（可选加固，防再犯）

`lib/logto.ts` `logtoConfig()` 对三个值做 `trim()` + 剥离首尾引号（防御性，一行级改动），并把「env 值禁止带引号」写进 nebflow-website 的 Agent.md/README 部署章节。

### 步骤 5（遗留项，本次不阻塞）

- preview 项目 `LOGTO_ENDPOINT` 仍为旧域 `https://auth.neblink.space`（当前靠 Caddy 兜底可用）——按统一方案 P2 应同步改 `https://auth.nebflow.space`
- 生产域 `nebflow.space`（main 分支）尚无 Logto 路由（`/api/auth/providers` 404）——BYO 登录仅 staging 分支在途，合 main 时需同步确认 env

---

## 5. 验收条件（二值 · 可自动化）

| # | 验收点 | 命令 | 通过标准 |
|---|---|---|---|
| AC-1 | env 无引号 | `vercel env pull` + grep | `LOGTO_ENDPOINT` 值恰为 `https://auth.nebflow.space`（无 `"`） |
| AC-2 | logto 入口跳转正常 | `curl -sI https://mashiro.staging.nebflow.space/api/auth/logto` | 307 且 `Location` 以 `https://auth.nebflow.space/oidc/auth?` 开头（**无引号**） |
| AC-3 | 邮箱登录闭环（错密负例） | `curl -s -X POST https://mashiro.staging.nebflow.space/api/auth/signin -H 'Content-Type: application/json' -d '{"identifier":"<任意>","password":"<错误密码>"}'` | 401 `{"ok":false,"error":"invalid_credentials"}`（证明 Experience-API 往返打通；Logto 对错密与不存在账号统一 422→映射 invalid_credentials） |
| AC-4 | 邮箱登录闭环（正例，需真实账号） | 浏览器或带 cookie 的 curl 走 `POST /api/auth/signin` → 跟随返回 URL → callback | 302 回 `/` 且种下 `logto_*` session cookie；`GET /api/auth/me` → 200 |
| AC-5 | 登录页 UI | 浏览器打开 `https://mashiro.staging.nebflow.space/login` | 邮箱+密码输入框渲染；提交后不再出现「Email sign-in is not configured」 |
| AC-6 | 社交入口（GitHub/Google 按钮） | 浏览器点击 | 307 至 Logto（connector 未启用时由 Logto 优雅回退到托管页，不再 500） |
| AC-7 | 回归 | `GET https://neblink.space/api/auth/logto` | 仍 307（preview 项目不受影响） |

**说明**：登录页邮箱+密码输入框当前**一直渲染**（静态表单），故验收不以此为单一标准——真正判据是 AC-3/AC-4 的 Logto 往返打通（当前为 400 server_error / 500）。

---

## 6. 附：证据索引

| 论断 | 证据 |
|---|---|
| Logto sign-in experience 含 EmailPassword | `psql`: `sign_in_experiences.sign_in`（default 租户）+ `GET /api/.well-known/sign-in-exp` |
| Logto 版本 | 容器镜像 label `org.opencontainers.image.version=1.42.0` |
| ENDPOINT 迁移生效 | `GET auth.nebflow.space/oidc/.well-known/openid-configuration` issuer |
| staging 域名归属 | Vercel API：`nebflow-website` 项目 domains `mashiro.staging.nebflow.space` gitBranch=staging |
| 引号污染 | Vercel runtime logs：`ERR_INVALID_URL input: '"https://auth.nebflow.space"/oidc/auth?client_id=%22ih1w2ihuvwefvgvnkzqr3%22...'` |
| 代码触发链 | `nebflow-website` staging 分支：`lib/logto.ts:21-27` → `app/api/auth/logto/route.ts:23-27` → `LoginContent.tsx:16-17` + `auth-i18n.ts:336` |
| 对照组正常 | `GET neblink.space/api/auth/logto` → 307（URL 无引号） |
| redirect 白名单 OK | Logto `applications` 表 `ih1w2ihuvwefvgvnkzqr3.oidc_client_metadata.redirectUris` |
