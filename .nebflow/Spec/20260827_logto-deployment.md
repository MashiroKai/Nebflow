> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Logto 自托管部署纪要（2026-08-27）

> Backend 执行 · Nebula P1 直派。前置：2026-08-18 stage-1 三仓代码对接方案已实施（jwks.rs/device register/device flow）。
> 本文 = 部署现场记录 + 剩余初始化 runbook。**密钥值永不写入本文件。**

## 一、现状总览

| 组件 | 形态 | 状态 |
|---|---|---|
| Logto OSS | `svhd/logto:latest`，core :3001（仅栈内 expose），admin :3002（仅容器网络） | 运行中 |
| Postgres | `postgres:17-alpine`，named volume `logto-db`，healthcheck 门控 | healthy |
| Caddy 站点块 | `auth.neblink.space` → `logto:3001`，TLS 自动签发 | 生效 |
| neblink-server | env 打开 `NEBLINK_JWKS_URL` / `NEBLINK_OIDC_ISSUER`，RS256/ES384 双读验证 | 日志实证 JWKS enabled |

分支：neblink-server @ **feat/logto-deploy**（b67e652→46c64f9→42022de）。DNS A auth.neblink.space → 203.0.113.10（阿里云，08-27 启用）。

## 二、已验证事实（2026-08-27 10:4x）

- ✅ `https://auth.neblink.space` TLS 有效，根路径 302 → sign-in 页
- ✅ OIDC 发现端点 200：issuer=`https://auth.neblink.space/oidc`；`device_authorization_endpoint` 在位（Native Device Flow 可用）
- ✅ JWKS 发布 EC P-384 键（alg ES384）——**因此 jwks.rs 补了算法白名单 {RS256, ES384}**（42022de；硬编码 RS256 时 Logto token 会全拒）
- ✅ neblink-server 无回归：`https://neblink.nebflow.space/api/health` 200，日志 `JWKS verification enabled`
- ✅ 生产部署纪律全部遵守：配置改动均入仓（compose/Caddyfile/.env.example），服务器手改仅限 .env 秘钥值

## 三、Admin Console 入口（2026-08-27 12:0x 终版 — :8443 专用 origin）

**入口 URL：`https://auth.neblink.space:8443/console/default/welcome`**

### 为什么是这个形态（源码实证，logto-io/logto @ master）

1. `getTenantId`（core/src/utils/tenant.ts）按 **URL origin 精确匹配** adminUrlSet 决定 admin 租户 → ADMIN_ENDPOINT 若写 `https://auth.neblink.space`（同 origin 全量）会把 443 上所有请求（含 /oidc、experience API）路由进 admin 租户，毒化终端用户流；
2. console 的 OIDC redirect 白名单由 ADMIN_ENDPOINT 动态推导（oidc/adapter.ts transpileMetadata），容器内名 `http://logto:3002` 会让重定向指向浏览器不可解析的主机——**这正是早前 SSH 隧道失效的根因**；
3. 同域路径拆分不可行：路径型 ADMIN_ENDPOINT 会双重拼接 `/console/console/callback`；且浏览器侧 redirect_uri 按窗口 origin 计算，无法对齐；
4. **同域不同端口 = 独立 origin**：两约束同时满足，复用已签发的同域名 Let's Encrypt 证书，零 DNS 改动。
5. 路由表注意：console 客户端路由挂 `/:tenantId/*` 下，bootstrap 入口是 `/console/default/welcome`（`/console/welcome` 缺租户段会渲染 SPA 404「deep space」假页——状态码 200 的壳假象即此）。

### 已验证证据（2026-08-27 11:4x-11:5x）

- ✅ Playwright 浏览器级：`/console/default/welcome` 渲染出真实 bootstrap 表单 —— `TITLE: "Create your account"`、`input[name=identifier]`、"Create account" 按钮
- ✅ 截图存档 `docs/Nebflow/assets/20260827_logto-console-bootstrap/1-welcome-form.png`
- ✅ 回调闭环在原点内：`:8443/oidc/auth?...redirect_uri=:8443/console/callback` → 303 回 `https://auth.neblink.space:8443/console/callback?...`（curl 未带 PKCE 得到的 error 是预期策略拒绝，恰证明 redirect_uri 已被接受、整轮回环都在 :8443）
- ✅ 主域不受影响：443 discovery 200，issuer 仍 `https://auth.neblink.space/oidc`
- 配置 commits：feat/logto-deploy @ **9496cc7**（compose ADMIN_ENDPOINT+Caddyfile :8443 块+caddy 端口映射）、3c33b3a（回环映射前置步骤）

### ⚠️ 收口清单（管理员 + M2M 凭据到位后立即执行）

1. Caddyfile 删 `:8443` 站点块；compose 删 caddy `"8443:8443"` 映射
2. compose `ADMIN_ENDPOINT` 回退 loopback-only 值（如 `http://localhost:3002`）
3. `up -d caddy logto` + commit（在收口 commit 中注明 bootstrap 完成）

### 用户操作（仅两步，浏览器打开入口 URL 后）

1. **建管理员**：welcome 表单设管理员标识+密码（自动登录进 Console）
2. **建 M2M**：Applications → Create → Machine-to-Machine → 勾 default tenant 的
   **Management API 全部权限（All）** → 把 **Client ID + Secret 经安全渠道交给 Nebula**
   —— 之后的应用创建、测试账号、资源绑定、端到端 RS256 验收全部程序化自动执行，
   无需再打开 console。

（可选替代）不想给 M2M secret 的话，用户也可直接在 console 完成：Traditional Web app
（redirect 到官网 callback）+ Native app + API Resource（indicator 建议复用 `https://api.neblink.space`）
+ 测试邮箱账号注册闭环。两条路选其一即可进入验收。

## 四、凭据存放地图（严禁入库/聊天二次传播）

| 内容 | 位置 | 说明 |
|---|---|---|
| VPS SSH 密码 | `~/.nebflow/vps.env`（chmod 600，git 不跟踪层） | 既有约定 |
| Logto DB 密码 | VPS `/root/neblink-server/deploy/docker/.env`（600） | 部署时 openssl rand -hex 24 生成 |
| bootstrap token | VPS `/root/.logto-bootstrap-token.json`（600，会过期） | 早期尝试残留，可删 |
| 未来 M2M/Web App 凭据 | 用户提供后 → Nebula 配 Vercel env（LOGTO_APP_ID/SECRET）；桌面侧只 clientId 进主仓 nebflowServer 配置块 | 按 stage-1 方案契约 |

## 五、验收状态对照

| # | 验收项 | 状态 |
|---|---|---|
| a | auth.neblink.space 开 Logto 页 TLS 有效 | ✅（302 sign-in，TLS 自动签发） |
| b | 测试账号「邮箱注册→登出→重登」 | ⏳ 等 runbook 第 1 步管理员就绪后自证（Experience API 编程走查） |
| c | access_token 调 /api/device/register 得 EnrollResponse | ⏳ 同上；前置 ES384 兼容补丁已随 42022de 部署 |
| d | neblink.nebflow.space health 200 无回归 | ✅ 实测 200 |
| e | 配置全文件化入仓 | ✅ compose/Caddyfile/.env.example 三件套（b67e652 / 46c64f9），.env 秘钥值仅 VPS |

## 六、遗留项

- [ ] Logto DB 数据卷为 named volume `deploy-docker_logto-db`（restart/down 保留；`down -v` 才删——灾备意识）
- [ ] VPS 磁盘 81%（4.2G 余）：镜像稳定后建议 `docker image prune -f` 回收悬挂层
- [ ] ES384 验签的深度单测（需真实 P-384 键生成依赖或真实 token 样本）
- [ ] GitHub 连接器位：阶段 2 再配（灰度期 GitHub 登录走既有 OAuth，不依赖 Logto）
