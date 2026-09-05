> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Nebflow 登录功能交接指南（协作者调试用）

> 2026-09-04 · 基于当日对四个组件配置面的只读盘点（代码 + env 键位 + VPS 容器/Logto DB 实查 + 线上探测）
> **纪律：本文档不含任何 secret 明文**——只写变量名、存放路径与用途。值一律走 §5 的渠道线下交付。

---

## 0. 快速索引

| 你要做什么 | 看哪节 |
|---|---|
| 本地跑通官网登录/注册 | §3.1 + §3.3 |
| 本地调客户端 PKCE 登录 | §3.2 |
| 协作部署/验证 staging | §4 |
| 判断自己需要哪些权限和凭据 | §2 交接矩阵 → §7 分级方案 |
| 排查登录问题 | §8 常见坑（先看这个，全是真实事故） |

---

## 1. 架构总览

![登录链路架构图](assets/20260904_login-handover-arch.svg)

| 组件 | 入口/域名 | 代码位置 | 运行位置 | 登录职责 |
|---|---|---|---|---|
| **Logto（身份源）** | auth.nebflow.space（终端用户）<br>console.nebflow.space（管理台，basic_auth 防护） | 无（Docker 镜像 `svhd/logto:latest`，v1.42.0） | VPS 203.0.113.10，容器 `neblink-logto`(:3001) + `neblink-logto-db`(postgres:17) + `neblink-caddy`(TLS) | 签发所有 token；应用/connector/登录方式配置都在这 |
| **官网 nebflow-website** | mashiro.staging.nebflow.space（**登录功能在这**）<br>nebflow.space（main，旧版无 Logto） | GitHub `MashiroKai/nebflow-website`，本地 `~/.nebflow/projects/nebflow-website`（当前在 staging 分支） | Vercel（项目 `nebflow-website`） | /login 自绘登录页（Logto Experience API 中继）+ /register 直通建号（Management API） |
| **neblink-server** | api.nebflow.space（Caddy 反代 :9090） | `~/.nebflow/projects/neblink-server`（Rust） | VPS，容器 `neblink-server`，compose 目录 `/root/neblink-server/deploy/docker` | 校验 token（JWKS）、设备 enroll、账号数据存储 |
| **Nebflow 客户端** | 本机 gateway（默认 :8080） | 主仓 `/Users/dev/Claude code/Nebflow`（Scala，sbt） | 用户本机 | PKCE 登录发起、loopback 回调、code 换 token、enroll |

**当日盘点核实的关键事实**（影响调试起点）：

1. **登录功能目前只在 staging 分支**：main 落后 staging 97 个提交，main 上还是旧版 GitHub/JWT 登录（无 `/api/auth/logto*` 路由）。checkout main 会看到假象。
2. **staging 登录当前正常**：`GET mashiro.staging.nebflow.space/api/auth/logto` → 307 跳 `auth.nebflow.space/oidc/auth`（09-02 的 env 引号事故已修复）。
3. **本地 dev 回调已白名单**：Logto web app 已注册 `http://localhost:3000/api/auth/logto/callback`——官网本地调试**零 Logto 侧改动**。
4. **旧域仍在线**：auth.neblink.space / neblink.nebflow.space 解析同一 VPS，由 Caddy 的 env 兜底站点块（`AUTH_DOMAIN`/`SITE_DOMAIN` 默认值）继续服务，用于旧客户端兼容。
5. **客户端 PKCE 零配置可用**：config.json 缺 `logto` 段时回落主仓内置默认（embeddedDefault），值与线上一致。

### 谁调谁（一次官网登录 + 一次客户端登录）

```
官网登录（Traditional Web app，有 secret）:
  浏览器 → mashiro.staging/login → POST /api/auth/signin（服务端中继 Logto Experience API）
         → 302 /api/auth/logto/callback?code → 服务端 code 换 token → 种 cookie（logto_at/rt/idt/state）

注册（M2M，无用户交互）:
  /register 表单 → POST /api/auth/register → 服务端 M2M 换 Management API token
                → POST {LOGTO_ENDPOINT}/api/users 直接建号

客户端登录（PKCE Native app，public 无 secret）:
  gateway POST /api/neblink/auth/start → 生成 verifier/challenge/state → 返回 authorizeUrl
  浏览器打开 auth.nebflow.space/oidc/auth → hosted 页登录
  → 302 http://127.0.0.1:{gateway端口}/auth/callback?code&state（端口无关，见 §8-6）
  → gateway 换 token → POST neblink-server /api/device/register enroll
  → device.json 落盘（deviceToken + logto refreshToken）
```

---

## 2. 交接矩阵（组件 × 需要 / 不需要）

| 组件 | 调试登录**需要** | **明确不需要** |
|---|---|---|
| Logto | 只读查看配置：协作者子账号（console）或由作者代查；调试 UI/connector 时需要登录管理台 | **不需要** admin 主账号密码；**不需要** VPS 权限（看日志除外） |
| 官网 | repo 只读 + 本地 `.env.local`（7 个 key，§3.1）+ Node 环境 | **不需要** Vercel 登录态（除非要部署，Tier B）；**不需要**生产 env 的写权限 |
| neblink-server | 无需本地跑——客户端 enroll 打的是线上实例（api.nebflow.space / neblink.nebflow.space） | **不需要** VPS root；**不需要**改它的 env（除非 OIDC issuer 迁移，罕见） |
| 客户端 | 主仓代码 + sbt/JVM；`~/.nebflow/neblink/config.json` 可零配置（embeddedDefault 兜底） | **不需要**任何 secret（PKCE app 是 public client，无 secret） |

---

## 3. 本地调试 runbook

### 3.1 官网本地 dev

```bash
git clone https://github.com/MashiroKai/nebflow-website && cd nebflow-website
# 协作者分支基线：staging（登录功能只在 staging！见 §8-5）
git checkout staging
npm install
npx next dev --turbopack        # 或 ./dev.sh；默认 localhost:3000
```

**`.env.local` 需要的 key**（值不写进任何文档/issue/聊天记录）：

| 变量 | 用途 | 本地调试是否必需 | 从哪拿 |
|---|---|---|---|
| `LOGTO_ENDPOINT` | Logto 根地址（authorize/token/JWKS 的 base） | ✅ 必需（登录） | Vercel env pull 或作者交付 |
| `LOGTO_APP_ID` | web app 的 client_id（公开值） | ✅ 必需（登录） | 同上 |
| `LOGTO_APP_SECRET` | web app 的 client secret（Traditional app） | ✅ 必需（登录） | 同上（敏感） |
| `LOGTO_M2M_CLIENT_ID` | M2M 应用的 client_id（调 Management API 建号） | /register 必需 | 同上 |
| `LOGTO_M2M_CLIENT_SECRET` | M2M 应用 secret | /register 必需 | 同上（敏感） |
| `LOGTO_MANAGEMENT_RESOURCE` | Management API 的 resource indicator（`https://default.logto.app/api`） | /register 必需 | 同上（非敏感，写死亦可） |
| `NEXT_PUBLIC_GOOGLE_CONNECTOR_ID` | 登录页 Google 按钮对应的 connector DB id | 可选（不配则用代码内默认占位） | 同上 |
| `NEXT_PUBLIC_GITHUB_CONNECTOR_ID` | GitHub connector DB id | 可选（代码默认值即线上真实 id） | — |
| `NEXT_PUBLIC_LOGTO_API` | my-account 类 API 的 base（改密/头像/社交绑定） | 可选；**注意代码默认值指向旧域** auth.neblink.space（§8-4） | — |
| `NEBFLOW_JWT_SECRET` | 旧版 JWT 会话遗留 | ❌ 不需要（Logto 链路不用） | — |

> ⚠️ **值里严禁带引号**（首尾字面 `"` 会让所有登录路径 500，见 §8-1）。`lib/env-value.ts` 已做防御性剥离，但不要依赖它。

已有 Vercel team 权限（Tier B）的话可以一步到位：

```bash
npx vercel link --project nebflow-website   # 注意项目名，见 §4 的双项目坑
npx vercel env pull .env.local               # 拉完 grep 确认无引号
```

**验证**：

```bash
curl -s -o /dev/null -w "%{http_code} %{redirect_url}\n" http://localhost:3000/api/auth/logto
# 期望: 307 且 Location 以 https://auth.nebflow.space/oidc/auth? 开头
curl -s http://localhost:3000/api/auth/providers
# 期望: {"logto":true}
```

然后浏览器开 `http://localhost:3000/login`（邮箱+密码登录）与 `/register`（直通建号后登录）。

### 3.2 客户端本地（PKCE）

```bash
cd "/Users/dev/Claude code/Nebflow"   # 主仓
sbt run                                  # gateway 默认 :8080
```

**配置面**：`~/.nebflow/neblink/config.json` 的 `logto` 段：

```json
"logto": {
  "endpoint": "https://auth.nebflow.space",
  "clientId": "",
  "pkceClientId": "<public client id, 无 secret>"
}
```

- `endpoint`：Logto 根地址
- `clientId`：旧 device-flow 应用 id，**可留空**（PKCE 链不用；仅 PKCE 失败回落 device flow 时才需要）
- `pkceClientId`：PKCE 应用 id（public Native app，无 secret——这不是省事，是 PKCE 的安全模型）
- **这整段都可以不写**：主仓 `NeblinkModel.scala` 的 `embeddedDefault` 内置了同样的 endpoint + pkceClientId，全新安装开箱即登录

**登录验证**：客户端 UI 点头像登录 → 浏览器弹出 Logto hosted 页 → 邮箱+密码登录 → 自动回跳 `127.0.0.1:<gateway端口>/auth/callback` → 客户端显示连接成功。

```bash
curl -s http://127.0.0.1:8080/api/neblink/auth/state
# 期望: {"status":"success"}
cat ~/.nebflow/neblink/device.json | python3 -c "import json,sys; d=json.load(sys.stdin); print(sorted(d.keys()))"
# 期望: 含 deviceToken（值不要外传）；logto.refreshToken 存在说明刷新链就绪
```

**需要一个测试账号**：hosted 页可自注册（邮箱+密码）；或用官网 staging `/register` 建号——同一 Logto 租户，账号互通。测试账号命名建议带 `+debug` 后缀（如 `yourname+debug@…`），便于作者事后清理。

### 3.3 localhost 回调在 Logto 哪里加什么

| 场景 | 要加什么 | 在哪加 |
|---|---|---|
| 官网本地 dev（默认 :3000） | **无需任何改动**——`http://localhost:3000/api/auth/logto/callback` 已在白名单 | — |
| 官网本地改了端口（如 3001） | `http://localhost:3001/api/auth/logto/callback` | console.nebflow.space → Applications → `nebflow-website`（Traditional）→ Redirect URIs |
| Vercel preview URL 上测登录 | `https://<preview-domain>/api/auth/logto/callback`（preview 域名每次都变，见 §4） | 同上 |
| 客户端本地 | **无需改动**——PKCE 应用注册的是端口无关的 `http://127.0.0.1/auth/callback`（RFC 8252 §7.3），本机任何端口都命中 | — |

console 入口：`https://console.nebflow.space`（HTTP basic_auth + Logto 管理员登录，两层；凭据不共享，见 §6）。若只是加回调这类小事，也可以请作者代加，或走 Management API（需 M2M 凭据）。

---

## 4. staging / preview 验证流

### 4.1 Vercel 项目拓扑（有一个容易踩的坑）

| Vercel 项目 | 域名 | 分支 | 说明 |
|---|---|---|---|
| `nebflow-website`（正式项目） | **mashiro.staging.nebflow.space** | `staging` | **登录功能的主战场** |
| 同上 | nebflow.space | `main` | 旧版（无 Logto 路由）；登录合 main 前别用生产域验证 |
| `nebflow-website-preview`（遗留项目） | neblink.space 等 | — | 旧部署；⚠️ **本地 `nebflow-website/.vercel/project.json` 目前 link 的是这个项目**——跑 `vercel env pull` / `vercel deploy` 前先确认目标项目，必要时 `--project nebflow-website` 显式指定 |

Team：`team_iMHfQexbwPmZiPj6pReL5K8h`（作者账号 `kaimashiro-2206`）。协作者加入方式 = Vercel Dashboard → Team Settings → Members → 邮件邀请（Tier B，见 §7）。

### 4.2 部署流（git 集成，无手动 deploy）

```bash
cd ~/.nebflow/projects/nebflow-website
git checkout staging && git push origin staging
# → Vercel 自动部署到 mashiro.staging.nebflow.space
# PR 则得到 *.vercel.app preview URL
```

### 4.3 线上 LOGTO_* env 现状（2026-09-02 修复后）

Vercel 正式项目现有 7 个自配 env（production + preview 两个 target 各一套，值相同）：
`LOGTO_ENDPOINT` `LOGTO_APP_ID` `LOGTO_APP_SECRET` `LOGTO_M2M_CLIENT_ID` `LOGTO_M2M_CLIENT_SECRET` `LOGTO_MANAGEMENT_RESOURCE` `NEXT_PUBLIC_GOOGLE_CONNECTOR_ID`

改动 env 的规矩：`vercel env rm` → `vercel env add`（**纯值、无引号**）→ 触发重部署（env 变更不自动生效，push 空提交或 redeploy）。

### 4.4 staging 验证命令组

```bash
curl -s -o /dev/null -w "%{http_code} %{redirect_url}\n" https://mashiro.staging.nebflow.space/api/auth/logto
# 期望 307 → auth.nebflow.space/oidc/auth（无引号）
curl -s https://mashiro.staging.nebflow.space/api/auth/providers          # {"logto":true}
curl -s -X POST https://mashiro.staging.nebflow.space/api/auth/signin \
  -H 'Content-Type: application/json' -d '{"identifier":"a@b.c","password":"wrong"}'
# 期望 401 {"ok":false,"error":"invalid_credentials"} —— 证明 Experience API 往返打通
curl -s -o /dev/null -w "%{http_code}\n" https://mashiro.staging.nebflow.space/register   # 200
```

---

## 5. Secret 清单（只有名字和位置，没有值）

| # | 名称 | 用途 | 当前存放 | 建议交付渠道 | 归属 |
|---|---|---|---|---|---|
| 1 | `NEBLINK_VPS_PW`（VPS root 密码） | VPS SSH | `~/.nebflow/vps.env`（chmod 600） | **不共享**；Tier C 走受限用户替代（§6.1） | C |
| 2 | Logto 管理员账密 | console.nebflow.space 登录 | `~/.nebflow/logto-admin-credentials.txt`（chmod 600） | **不共享**；按需建协作者子账号（§6.2） | A*/C |
| 3 | console basic_auth 凭据 | console.nebflow.space 外层 HTTP 防护 | Caddyfile 内只存哈希；明文作者持有（集中存档位置待作者确认） | **不共享** | C |
| 4 | DirectMail SMTP 凭据 | Logto 邮件 connector 发信（验证码/忘记密码邮件） | `~/.nebflow/directmail.env`（chmod 600） | 默认不给；仅当调试邮件验证码链路时按需 | A+ |
| 5 | `LOGTO_APP_SECRET` | 官网 web app 的 OIDC client secret | Vercel env（production+preview target）+ 本地 `.env.local`/`.env.preview` | Tier A：作者私下交付或 `vercel env pull` | A |
| 6 | `LOGTO_M2M_CLIENT_ID` / `LOGTO_M2M_CLIENT_SECRET` | 官网 /register 的 Management API 凭据 | Vercel env + `~/.nebflow/logto-registration.env`（chmod 600） | Tier A：同上 | A |
| 7 | Vercel CLI 登录态 | env 读写 / 部署 | 本机（`vercel whoami` → `kaimashiro-2206`） | **不共享登录态**；给协作者发 team invite | B |
| 8 | GitHub repo 写权限 | staging 分支 push | GitHub `MashiroKai/nebflow-website` | collaborator 邀请 | B |
| 9 | `deviceToken` / Logto `refreshToken` | 客户端个人会话凭证 | `~/.nebflow/neblink/device.json`（chmod 600） | **永不共享**（等同个人身份） | — |
| 10 | config.json 内 `deviceToken` | neblink 网络注册凭证 | `~/.nebflow/neblink/config.json` | **永不共享** | — |

公开可写进文档/issue 的值（出现在 URL 里，非 secret）：Logto 各应用 id（如 web app `ih1w…`、PKCE app `csxh…`）、connector DB id（GitHub `k06o…` / Google / SMTP）、`LOGTO_ENDPOINT`、`LOGTO_MANAGEMENT_RESOURCE`。

---

## 6. 安全边界建议

### 6.1 VPS root 不共享 → SSH 受限用户替代

```bash
# 作者在 VPS 执行（概要）：
adduser collaborator && usermod -aG docker collaborator   # docker 组 ≈ root，仅给可信协作者
# 更收紧的替代：sudoers 白名单只放行 docker logs / docker inspect 等只读命令
mkdir -p /home/collaborator/.ssh && vim /home/collaborator/.ssh/authorized_keys  # 协作者公钥
```

- 协作者日常只需要：`docker ps/logs/inspect`、看 Caddyfile、重启容器——docker 组足够
- root 密码留在作者手里；后续可关闭 root 密码登录（key-only）作为加固
- ⚠️ docker 组实质等于 root（可挂载宿主文件系统），只给 Tier C 可信协作者

### 6.2 Logto 管理员不共享 → 子账号方案

- Logto 原生多用户：协作者用**自己的邮箱**在 console OIDC 登录（console 入口有 basic_auth 外层），作者给其分配内置 **admin role**（Console → Roles）
- 可随时回收：停用用户 / 踢会话 / 移除 role；Audit Logs 有完整登录审计
- 不要把作者的管理员账密写进任何共享渠道；协作者需要 console basic_auth 过墙时，可与作者协商改 basic_auth 凭据（改 Caddyfile → `docker exec neblink-caddy caddy reload`）或按 IP 放行

### 6.3 按需给的原则

| 需求 | 给什么 | 不给什么 |
|---|---|---|
| 只调本地登录（Tier A） | `LOGTO_APP_SECRET` 等本地 env 值 | VPS / Vercel / admin 一概不给 |
| 调试邮件验证码（A+） | `directmail.env`（临时，用后建议轮换 SMTP 密码） | — |
| staging 部署（Tier B） | GitHub collaborator + Vercel invite | VPS、Logto admin |
| 服务器运维（Tier C） | 受限 SSH 用户 + 协作者 Logto 子账号 | root 密码、作者 admin 账密 |

---

## 7. 分级方案

| 级别 | 范围 | 协作者拿到什么 | 能做的验证 | 交付动作（作者侧） |
|---|---|---|---|---|
| **A 最小集：只调登录流程** | 官网本地 dev + 客户端 PKCE，全部打**线上** Logto/neblink-server | repo 读权限 + 5 个本地 env 值（§3.1 表）+ 测试账号 | 官网登录/注册端到端、客户端 PKCE 端到端（§9 Tier A 清单） | 私信交付 env 值；（可选）讲解 §8 坑 |
| **A+ 备选：独立 Logto 实例** | 不想污染生产租户时 | 同 A，另加本地 Docker 跑一套 Logto（`docker compose` 起 logto+db，指向 localhost），endpoint 改成实例地址，Logto 侧按 §3.3 建 web app + 回调 | 同 A，数据完全隔离 | 无（协作者自助）；注意 neblink-server 打的还是线上，enroll 依赖线上 Logto 校验——纯登录 UI 调试可忽略 |
| **B：+ 官网部署协作** | staging / preview 部署与 env 管理 | + GitHub collaborator、Vercel team invite、`.vercel` link 到正式项目 | staging push 部署、preview URL 登录验证（记得 §3.3 加回调）、env 增改 | Vercel Dashboard 邀请 + GitHub 邀请 + 告知双项目坑（§4.1） |
| **C：+ 服务器运维** | VPS 容器 / Caddy / Logto 部署变更 | + VPS 受限 SSH 用户（§6.1）+ Logto 子账号（admin role）+ console basic_auth（协商） | 容器状态/日志排查、Logto 应用与 connector 管理、compose env 变更 | 按 §6.1 建用户 + §6.2 建 role；交接 compose 目录 `/root/neblink-server/deploy/docker` 与部署文档（repo `deploy/README.md`） |

---

## 8. 常见坑（历史真实事故，排查前先过一遍）

1. **env 引号污染**（2026-09-02 staging 全断）：`LOGTO_*` 值带字面双引号 → `ERR_INVALID_URL` → 所有登录路径 500/server_error。迷惑点：`/api/auth/providers` 仍返回 `{"logto":true}`（非空 ≠ 可用）。诊断：看 Location 里 client_id 是否带 `%22`。修复：去引号重设 + 重部署。`lib/env-value.ts` 已加防御剥离，但根上别再犯。
2. **JWKS 算法是 ES384 不是 RS256**：Logto 签发 EC P-384 token；neblink-server 的 jwks 白名单是 `{RS256, ES384}`——自写验签时硬编码 RS256 会全拒。
3. **M2M 换 token 必须显式 `scope=all`**：`POST /oidc/token` 不带 `scope` 时 v1.42 签发的 token 无 scope claim → Management API 一律 403（DB 角色完好也会 403，别往权限丢的方向查）。
4. **`NEXT_PUBLIC_LOGTO_API` 默认值指向旧域** `auth.neblink.space`（`lib/account-api.ts`）：改密/头像/社交绑定类 my-account 调用在未覆盖 env 时会打到旧域（当前旧域仍在线，能用，但属迁移残留——遇到诡异 401/重定向先查这个）。
5. **main ≠ staging**：登录只在 staging 分支（main 落后 97 commits）。本地看到「GitHub 登录」说明 checkout 错分支。
6. **客户端 loopback 端口无关**：PKCE 应用注册的是 `http://127.0.0.1/auth/callback`（无端口）。本机 8080 被占、gateway 换端口后登录照样通——不要往「端口没注册」方向排查；反例才要查：URL 是 `localhost` 而非 `127.0.0.1`（注册的是后者）。
7. **`.env.example` 已过期**（CloudBase 时代残留），以 §3.1 表为准。
8. **官网与客户端会话不互通但账号互通**：两边是不同 Logto application，cookie 不共享；同浏览器下 Logto hosted 页有 SSO（官网登录后客户端登录免密直过）。这不是 bug，是 OAuth 模型。
9. **Vercel 双项目**（§4.1）：本地 `.vercel` 指向遗留 preview 项目，CLI 操作前先确认目标。

---

## 9. 交接完成判定（二值清单）

**Tier A（登录流程）**
- [ ] `curl -s -o /dev/null -w "%{http_code}" localhost:3000/api/auth/logto` = `307`，Location 无引号
- [ ] 浏览器 `localhost:3000/login` 邮箱+密码登录成功，种下 `logto_at` cookie
- [ ] `localhost:3000/register` 建号成功，新号可登录
- [ ] 客户端 `sbt run` → 登录 → 回跳成功；`/api/neblink/auth/state` 返回 `{"status":"success"}`；`device.json` 出现 `deviceToken`

**Tier B（部署协作）**
- [ ] push staging → mashiro.staging.nebflow.space 自动部署成功，`/api/auth/providers` 返回 `{"logto":true}`
- [ ] 某个 preview URL 在 Logto web app 加回调后 `/api/auth/logto` 307 正常

**Tier C（运维）**
- [ ] `docker ps` 四容器（logto / logto-db / caddy / neblink-server）up & healthy
- [ ] `curl -s https://auth.nebflow.space/oidc/.well-known/openid-configuration` 的 `issuer` = `https://auth.nebflow.space/oidc`
- [ ] 能看懂并安全变更 `deploy/docker` compose env（改完知道要 `docker compose up -d` 生效）

---

## 附：盘点证据索引（2026-09-04 实查）

| 事实 | 来源 |
|---|---|
| 官网 env 读取链 / dev 命令 / 回调路径 | `nebflow-website`：`lib/logto.ts` `lib/logto-signin.ts` `lib/logto-admin.ts` `lib/account-api.ts` `dev.sh` `app/api/auth/*` |
| 客户端配置链 / PKCE / embeddedDefault | 主仓：`NeblinkModel.scala`(L177-269) `LogtoAuthCode.scala`(L108 scope) `RestApiRoutes.scala`(L941-974, L2814)；`~/.nebflow/neblink/config.json` |
| neblink-server OIDC env 实值 | VPS `docker inspect neblink-server`（ISSUER=`https://auth.nebflow.space/oidc`，JWKS=容器内 `http://logto:3001/oidc/jwks`，CORS 含 neblink.space/www.nebflow.space） |
| Logto 应用/connector/回调清单 | VPS `neblink-logto-db` applications/connectors 表（10 应用 + 3 connector，全文 §3.3 与 §5 引用） |
| Vercel 拓扑 / env 现状 | `nebflow-website/.vercel/project.json` + 20260902_logto-email-signin-missing.md + 线上探测 |
| staging 健康 | 当日探测：`/api/auth/logto` 307（无引号）、login/register 200、`nebflow.space/api/auth/*` 404 |
| 历史事故与修复 | `20260902_logto-email-signin-missing.md`、`20260827_logto-deployment.md`、`logto-registration-api.md`、`20260903_logto-smtp-connector-setup.md`、`20260901_login-chain-analysis.md` |
