> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# 官网/账号体系统一方案：域名 · Logto · API · 发布流收敛

> 2026-09-01 · 纯分析 + 方案（未改任何代码/配置/实例）
> 作者原话：「我觉得 neblink 只是一个我们用于测试的域名，单独搞一套体系是非常大的心智负担。给我一个统一方案」
> 目标：**最小心智负担**为第一目标；约束：①零中断现有功能（登录/设备互联/官网）②迁移可回滚 ③neblink-server 底层名保留、用户可见层全 nebflow
> 全部结论基于 2026-09-01 实证探测（DNS 解析、HTTP 状态码、OIDC discovery、代码行号），证据索引见 §9

---

## 0. 结论摘要（3 句话）

1. **域名收敛**：可以且应该去掉 neblink.space 独立测试域。推荐 **Vercel Preview 部署**（方案 A）——单 Vercel 项目、单域名命名空间，测试不再需要第二个域名/项目/环境，测试只是"同一系统的 Preview 部署环境 + 一个 feature flag 开关"。
2. **Logto 收敛**：auth.neblink.space → **auth.nebflow.space**（同一实例、同一 client、多 redirect URI）。域名切换只改 issuer，Logto sub 主键/用户数据/设备/好友/消息**零迁移**；设备互联凭据（neblink-server 签发的 deviceToken）**不受影响**；唯一代价 = 切换后 1 小时窗口内官网 session 需重登一次（SSO 一键，可自愈）。
3. **API 收敛**：neblink.nebflow.space → **api.nebflow.space**（neblink-server 内部名保留，仅域名层去 neblink）。发布流从「两步域名发布」收敛为「merge main 自动上线」，feature flag 按部署环境（Preview 开 / Production 关）取代测试域。

**统一后长什么样**：四个域名收敛为 nebflow.space 一个命名空间（+ auth./api. 两个子域），neblink 从所有用户可见层消失；官网只有正式版（nebflow.space），测试内容 = 每个功能分支自动生成的 Vercel preview URL；Logto 一套账号、一个 client，测试/正式共用；neblink-server 改名 api.nebflow.space 但内部代号不变；brand.conf 定死 nebflow.space，域自动替换机制退役；测试功能靠 NEXT_PUBLIC_ENABLE_* flag 隔离，不靠域名隔离。心智负担从「两套域名+两套项目+两套 env」降为「一套系统、一个域名、一套账号，测试只是环境开关」。

---

## 1. 现状拓扑（2026-09-01 实证）

![现状拓扑](assets/20260901_unified-domain-current.svg)

| 域名 | 角色 | DNS | 实测状态 | 归属 |
|---|---|---|---|---|
| `nebflow.space` | 正式官网（Vercel 项目 `nebflow-website`，git 连 main 自动部署） | A → 76.76.21.21 | `/profile` `/login` 均 200 | Vercel |
| `neblink.space` | 测试官网（Vercel 项目 `nebflow-website-preview`，CLI 手动 `vercel --prod`） | A → 76.76.21.21 | `/profile` `/login` 均 200 | Vercel |
| `auth.neblink.space` | Logto 自托管（VPS Caddy → logto:3001） | A → 203.0.113.10 | OIDC discovery 200，issuer=`https://auth.neblink.space/oidc` | VPS |
| `neblink.nebflow.space` | neblink-server API（VPS Caddy → neblink-server:9090） | A → 203.0.113.10 | `/api/health` 200 | VPS |

**账号体系现状（关键事实）**：

- Logto = 唯一身份源（邮箱+密码，GitHub 已清）；主键 = Logto sub；官网 `session.userId` = neblink-server `users.id` = 客户端设备归属 = sub（已统一，见 `20260901_account-data-and-logto-admin.md`）
- 官网 web client：`LOGTO_APP_ID` + secret（env 配置）；客户端 native client：`nebflow-desktop-pkce`（loopback 回跳）；两 client 同租户
- 客户端 embedded Logto endpoint 默认值 = `https://auth.neblink.space`（`NeblinkModel.scala:267`）
- 官网 `lib/config.ts`：`SITE.domain = nebflow.space`（硬编码）、`SITE.neblinkUrl = https://neblink.nebflow.space`；`lib/logto.ts` 验 id_token 时 **issuer 钉死在 `${endpoint}/oidc`**（`verifyIdToken`，`lib/logto.ts:107-121`）——这是判断「切换 Logto 域名 = 一次重登」的关键证据
- `brand.conf`：`domain = neblink.space`（调试真值）、`profileUrl = https://neblink.space/profile`、`serverUrl = https://neblink.nebflow.space`；发布环境用 `NEBFLOW_BRAND_DOMAIN`/`NEBFLOW_PROFILE_URL` env 覆盖为 nebflow.space（`Branding.env` 双前缀读取）
- VPS compose：`ENDPOINT: ${AUTH_PUBLIC_URL:-https://auth.neblink.space}`（**驱动 OIDC issuer**，compose 注释原文）；Caddyfile 站点块已参数化 `{$SITE_DOMAIN}` / `{$AUTH_DOMAIN}`
- 测试功能标记：`NEXT_PUBLIC_ENABLE_*`（Hub 首个落地，`lib/feature-flags.ts` 在 feat/hub-flag 分支，未合入当前开发线 feat/logto-stage1）；默认 false，`.env.production` 显式 false 兜底
- 官网 dev 线分支积压：feat/logto-stage1（当前）为主，另有 hub-page / account-settings / byo-login / avatar-upload 等十余个分支

---

## 2. Q1 域名收敛：能否去掉 neblink.space 独立测试域？

### 2.1 先明确「测试域到底提供了什么」

| neblink.space 提供的价值 | 本质 |
|---|---|
| 一个**稳定 URL** 给作者验收最新在途内容 | 可被「分支 preview 稳定 URL」替代 |
| 登录链路（PKCE/Web OAuth）在测试内容上可跑 | **唯一硬约束**：OIDC redirect URI 是精确匹配，任意 preview URL 需逐个加白名单 |
| 测试内容永不污染正式域 | 可被「Preview 环境与 Production 环境天然隔离」替代 |
| 未 commit 的本地内容直接预览（`vercel --prod` 打包当前目录） | 工作流习惯，收敛后需改为 push 分支（或临时 `vercel` 非 prod 部署拿临时 URL） |

### 2.2 三方案对比

| 维度 | **A. Vercel Preview 部署**（推荐） | B. staging.nebflow.space 子域 | C. 保留 neblink.space 纯测试域 |
|---|---|---|---|
| 域名命名空间 | **零额外域名**（自动 `*.vercel.app`） | nebflow.space 下（品牌统一） | neblink 命名空间残留 |
| 独立部署目标 | **无**（单项目内 Preview 环境） | 第二个 Vercel 项目（域名绑 prod channel） | 第二个项目（现状） |
| Vercel 项目数 | **1**（合并） | 2 | 2 |
| env 维护 | **1 套**，Preview/Production 环境级隔离，零同步 | 2 套，需同步（现状） | 2 套，需同步（现状） |
| 登录测试 | 分支 preview URL 需逐个加 Logto redirect（churn 低，仅涉登录分支）；**长期 staging 分支只加一次** | 加一条 redirect URI | 已加（现状） |
| 未 commit 本地预览 | `vercel`（非 --prod）→ 临时 URL；或 push 分支 | `vercel --prod` 发 staging（保留现状习惯） | 现状支持 |
| 测试数据隔离 | Preview 环境天然隔离（flags/env 按环境烘焙） | 同左 | 同左 |
| 心智负担 | **最低**（一套系统） | 中（仍是两套系统，只是换名） | 高（现状痛点） |
| 迁移成本 | 中（工作流变更：测试内容进 git + 删项目/域名） | 低（改域名绑定 + DNS） | 零 |
| 风险 | 中（单项目下 `vercel --prod` 误操作=污染正式域，需护栏） | 低 | 低 |

**关键洞察**：
- **方案 B 只是给第二套体系换品牌名**——双项目、双 env、双部署通道全部保留，心智负担基本不变（作者要消灭的是「单独一套体系」，不是「neblink 这个名字」）。B 的价值仅在作者拒绝「测试内容必须进 git」时作为保底。
- **方案 A 是唯一真正消除第二套体系的方案**：单项目、单 env 命名空间、零自定义测试域名。Vercel git 集成对每个分支自动产出稳定 URL（`nebflow-website-git-<branch>-kais-projects-e8bd6cfb.vercel.app`，随 push 自动更新）——这就是「测试版」，只是不再叫 neblink.space。
- 登录测试的 redirect 白名单是 A 的唯一硬伤，解法：长期 `staging` 分支（稳定 preview URL，一次性加白名单，保留作者「打开一个固定 URL 验收」的习惯）+ 涉登录的临时分支按需加白名单（churn 极低）。

### 2.3 推荐：A（Vercel Preview），B 为保底

**决策点（待作者拍板）**：能否接受「测试内容必须 push 到分支才能预览」？能 → A（终态）；不能 → B（staging.nebflow.space，保留 CLI 手动发测试版，仍达成去 neblink 命名空间）。本文后续按 A 展开。

---

## 3. Q2 Logto 收敛：auth.neblink.space → auth.nebflow.space

### 3.1 结论

**改域名**：是。同一 Logto 实例（数据零迁移），只换对外域名 + issuer。**实例/client 策略**：同一实例 + 同一 web client（多 redirect URI）+ 同一 native client——一套账号体系，不因测试/正式分裂。

### 3.2 域名迁移影响面（逐项）

| 受影响项 | 具体改动 | 影响 |
|---|---|---|
| Logto 自身 | compose `AUTH_PUBLIC_URL`（=ENDPOINT）→ `https://auth.nebflow.space`；Caddy `AUTH_DOMAIN` 同步 | **issuer 由 ENDPOINT 运行时推导**（compose 注释实证），重启即切换，DB 零改动 |
| 官网 env | Vercel `LOGTO_ENDPOINT` → `https://auth.nebflow.space`（两项目 → 收敛后单项目） | 验签 JWKS 与 issuer 钉死值随 env 变（`lib/logto.ts`） |
| neblink-server env | `NEBLINK_JWKS_URL` / `NEBLINK_OIDC_ISSUER` → 新域 | 验签目标切换 |
| 客户端 | `NeblinkModel.scala:267` embedded endpoint → 新域 | 新构建生效 |
| Logto 应用配置（redirect URI） | **无需改**：web client redirect = `https://nebflow.space/api/auth/logto/callback`（网站域，不变）；native client redirect = loopback（不变）。auth 域不在任何 redirect URI 里 | 零改动（仅收尾时删 neblink.space 的 callback 白名单项） |
| 存量 token/session | 见 3.3 | 一次性重登窗口 |
| 存量数据 | sub UUID、users 表、设备/好友/消息、Logto DB | **零迁移**（sub 与 issuer 无关；设备/好友/消息挂 sub） |

### 3.3 存量 token / session / 设备互联影响（关键分析）

| 资产 | 切换后行为 | 中断？ |
|---|---|---|
| 官网 session（cookie 内 id_token） | `verifyIdToken` 钉死 issuer = 旧域 → 校验失败 → 401 → 用户重登（Logto SSO 一键，同浏览器免密）；refresh token 在**同一实例**换新 URL 刷新即自愈（新 token 新 iss） | **1 小时窗口内一次重登**（id_token 有效期），可自愈 |
| 客户端 PKCE access token | 旧 token 带旧 iss → neblink-server `NEBLINK_OIDC_ISSUER` 校验失败 | 过渡期解法：**临时去掉 NEBLINK_OIDC_ISSUER env**（回退到只验签名+exp，`NEBLINK_OIDC_ISSUER` 文档语义「缺省只验签名+exp」），双 iss 自然兼容 48h 后恢复 |
| 客户端 refresh_token | 发到新域 token endpoint，同一实例接受（绑定 client+DB session，非 URL）→ 换发新 token | 无（下次刷新自动修复） |
| **设备互联 deviceToken** | neblink-server 自签长期凭据（HS256/RS256 自签），**与 Logto issuer 无关** | **零中断** |
| 旧版本客户端（embedded 仍指旧域） | Caddy 双域共存期间走旧域 → 同一实例照常签发 token（iss 已是新域）→ neblink-server 过渡期不验 iss | 无（平滑） |
| 进行中的 PKCE 流程 | 切点时刻进行中的 auth code 可能失效 → 重试一次 | 可忽略 |

**结论**：Logto 域名切换 = **数据零迁移 + 设备互联零中断 + 一次重登窗口**（仅官网 session，1h 内，SSO 自愈）。这是整个统一方案中唯一的用户可见中断点，选择低活跃时段执行即可。

### 3.4 测试/正式共用策略：同一实例 + 同一 client（多 redirect URI）

- **现状已是**：两域共用同一 Logto 实例 + 同一 web client（preview 项目复制 LOGTO_APP_ID）；Logto 支持一 client 多 redirect URI（`https://nebflow.space/api/auth/logto/callback` + `https://neblink.space/api/auth/logto/callback` 已在白名单，登录实测 200 可证）。
- **保持共用、不分裂**：分 client = 分账号体系风险（测试 client 的账号数据与正式 client 共享同一 Logto 用户库，分 client 只分应用入口不分用户；但维护两套 client_id/secret/回调 = 心智负担 + 2 倍配置面，与目标相悖）。
- **收敛后反而更简单**：neblink.space 下线后 redirect URI 白名单只剩 `nebflow.space` callback + loopback（+ 可选 staging preview URL），配置面缩小。

---

## 4. Q3 neblink-server API 收敛：neblink.nebflow.space → api.nebflow.space

**结论**：是。neblink-server 内部名（代码/容器/SQLite/协议字段）全保留，仅域名层去 neblink。

| 受影响项 | 改动 |
|---|---|
| DNS | 阿里云加 `api.nebflow.space` A → 203.0.113.10 |
| VPS Caddy | `SITE_DOMAIN` → `api.nebflow.space`（Caddyfile 已参数化）；**双域共存**过渡（旧块保留 1-2 周，旧客户端平滑） |
| 官网 | `lib/config.ts` `SITE.neblinkUrl` → `https://api.nebflow.space`（单点，`lib/config.ts:6` 注释即「single source: change it here when the server moves」） |
| 客户端 | `brand.conf` `serverUrl` → `https://api.nebflow.space` |
| CORS | `NEBLINK_CORS_ORIGINS` 更新（收敛后仅 `https://nebflow.space`；头像文件走 Caddy `/avatars/` 静态服务，无 CORS 需求） |
| 数据 | SQLite/设备/好友/消息：**零影响** |

风险：低（API 无状态迁移面，双域共存可回滚）；Caddy 双块已由 `{$SITE_DOMAIN}` 参数化支持，改动就是改 env + 加一块。

---

## 5. Q4 发布流统一

### 5.1 统一后发布流（推荐终态）

```
开发:  feature 分支 --push--> GitHub ──► Vercel 自动 Preview 部署
       └─ 每分支稳定 URL：nebflow-website-git-<branch>-kais-projects-e8bd6cfb.vercel.app
       └─ 长期 staging 分支 = 作者固定验收 URL（一次加 Logto redirect 白名单）
       └─ env：Preview 环境 NEXT_PUBLIC_ENABLE_*=true（测试功能可见）

验收:  作者打开分支 preview（或 staging URL）→ 视觉/功能验收
       └─ 涉登录分支：该分支 preview URL 加入 Logto redirect 白名单（一次性）

发布:  验收 PASS → merge main → push → Vercel Production 自动部署 nebflow.space
       └─ env：Production 无 NEXT_PUBLIC_ENABLE_*（或 .env.production=false 兜底）→ 测试功能 404/无入口
```

### 5.2 feature flag 是否取代测试域？——是

- 测试域的历史职能「让测试内容可见且不污染正式域」被拆解为：**可见性** = Preview 部署环境（分支级，天然隔离）；**不污染** = flag 按环境烘焙（Preview ON / Production OFF）+ `.env.production=false` 双兜底。flag 机制已落地样例（Hub，`lib/feature-flags.ts`），新模式照此推广。
- 红线不变：flag 只 gate 页面本体与导航入口，工具链端点不 gate（`/api/hub/items` 等）；任何新功能上线前先问「测试功能还是正式功能」。

### 5.3 安全护栏（单项目收敛的必配项）

单项目后 `vercel --prod` = 发正式域——必须立护栏：
1. **本地禁止 `vercel --prod`**（调试预览一律走 git push 分支，或 `vercel` 非 prod 部署拿临时 URL）；
2. Agent.md 双域铁律改为单域 + Preview 语义（防止 agent 误发）；
3. Vercel 侧 Production 部署只接受 main 分支 push（现状 git 集成已满足）。

### 5.4 brand.conf 收敛

- `domain` → `nebflow.space`（实证 `/profile` 200）；`profileUrl` → `https://nebflow.space/profile`；`serverUrl` → `https://api.nebflow.space`（P1 后）
- **NEBFLOW_BRAND_DOMAIN / NEBFLOW_PROFILE_URL 发布覆盖机制退役**（统一后 domain 唯一真值，无需替换；`Branding.env` 双前缀读取机制保留——其他 9 个变量仍依赖，只停用 domain/profileUrl 两个 env 用法）

---

## 6. 分阶段迁移路径（P0 → P4）

> 每阶段独立可验收、可回滚；阶段间无硬依赖（P2/P3 可并行，但建议 P0→P1→P2→P3→P4 顺序，先低风险后工作流变更）。

### P0 品牌真值落定（≈0.5 天 · 低风险 · 先行）

| 项 | 内容 |
|---|---|
| 改动 | `brand.conf`：`domain = nebflow.space`、`profileUrl = https://nebflow.space/profile`（删 neblink 真值）；三处测试断言同步（`BrandingSpec:40`/`BrandInjectionSpec:21`/`IndexWithBrandServeSpec:47`）；Branding.scala 注释更新 |
| 为什么可行 | 2026-09-01 实测 `https://nebflow.space/profile` = 200（官网 main 已含 profile 页，login-chain 报告的「官网未迁」已过时） |
| 风险 | 低（纯客户端品牌值） |
| 回滚 | git revert |
| 验收 | 客户端登录后点头像 → 新标签 `https://nebflow.space/profile` 200；`sbt test` 绿 |

### P1 API 域收敛 neblink.nebflow.space → api.nebflow.space（≈0.5-1 天 · 低风险）

| 项 | 内容 |
|---|---|
| 改动 | 阿里云：`api.nebflow.space` A → 203.0.113.10；VPS：Caddy 加 `api.nebflow.space` 站点块（`{$SITE_DOMAIN}` 参数化，双域共存）；官网 `lib/config.ts` `SITE.neblinkUrl` → 新域；客户端 `brand.conf serverUrl` → 新域；`NEBLINK_CORS_ORIGINS` 更新 |
| 风险 | 低（API 无状态迁移面） |
| 回滚 | 客户端配置改回；Caddy 删块 |
| 验收 | `curl https://api.nebflow.space/api/health` 200；官网 `/api/profile` 代理仍工作；客户端设备互联 enroll 冒烟（隔离实例） |

### P2 Logto 域收敛 auth.neblink.space → auth.nebflow.space（≈1 天 · 中风险=一次重登）

| 项 | 内容 |
|---|---|
| 改动 | ①阿里云：`auth.nebflow.space` A → 203.0.113.10；②VPS Caddy 加 `auth.nebflow.space` 块（**双域共存**）→ 验证 TLS + discovery；③切换 compose `AUTH_PUBLIC_URL`（ENDPOINT）→ 新域 → issuer 变更；④neblink-server **临时移除 `NEBLINK_OIDC_ISSUER` env**（回退只验签名，双 iss 兼容 48h 后恢复）；⑤消费方更新：Vercel `LOGTO_ENDPOINT`（两项目）、客户端 `NeblinkModel.scala:267`、neblink-server `NEBLINK_JWKS_URL`/`NEBLINK_OIDC_ISSUER` |
| 风险 | 中：官网 session 1h 窗口内一次重登（SSO 一键）；设备互联 deviceToken 零中断；**数据零迁移** |
| 回滚 | ENDPOINT 改回 + 消费方配置改回（数据无损） |
| 验收 | `curl https://auth.nebflow.space/oidc/.well-known/openid-configuration` 200 且 issuer=`https://auth.nebflow.space/oidc`；官网登录闭环（nebflow.space/login → 回跳）；客户端 PKCE 登录闭环；neblink-server 日志 `JWKS verification enabled`；旧域 auth.neblink.space 仍可访问（双域共存） |

### P3 官网测试域收敛 neblink.space → Vercel Preview（≈1-2 天 · 中风险=工作流变更）

| 项 | 内容 |
|---|---|
| 前置 | feat/hub-flag（flag 机制 + Hub）合入 dev 线（当前在 feat/logto-stage1 未合）；在途分支（hub-page/account-settings/byo-login/avatar-upload 等）按各自 preview 验收后合 main |
| 改动 | ①Vercel 单项目 `nebflow-website` 开启完整 Preview（git 全量连接，Production branch=main 已就绪）；②建长期 `staging` 分支 → 稳定 preview URL（作者验收入口）；③Preview env 设 `NEXT_PUBLIC_ENABLE_HUB=true` 等；Production 保持无 flag + `.env.production=false` 兜底；④Logto web client redirect 白名单加 staging preview URL（一次）；⑤**删除** `nebflow-website-preview` 项目 + `neblink.space` DNS 记录（grace 1-2 周，确认无依赖后）；⑥Agent.md 双域铁律 → 单域 + Preview 语义 |
| 风险 | 中：工作流变更（测试内容需进 git）；`vercel --prod` 误操作风险（护栏 §5.3） |
| 回滚 | preview 项目重建 + DNS 恢复（Vercel 部署历史与 git 内容零丢失） |
| 验收 | 分支 push → 自动生成 preview URL 且内容更新；staging URL 稳定可达；`nebflow.space` 无任何测试功能（Hub 404、导航无入口）；staging preview 登录可用（redirect 白名单生效）；`curl neblink.space` 无解析（DNS 已删） |

### P4 收尾清理（≈0.5 天 · 低风险）

| 项 | 内容 |
|---|---|
| 清理 | ①VPS Caddy 删 `auth.neblink.space` / `neblink.nebflow.space` 旧块 + 阿里云删对应 DNS（grace 期满后）；②Logto web client redirect 白名单移除 `neblink.space` callback；③发布流停用 `NEBFLOW_BRAND_DOMAIN`/`NEBFLOW_PROFILE_URL` env（机制保留）；④neblink.space 域名到期不再续费（注册保留至自然到期）；⑤文档更新：`20260824_website-publish-flow.md` 标注 superseded、README/REBRAND 清理 neblink 域引用、Agent.md 定稿 |
| 验收 | `rg -i "neblink\.space" ~/.nebflow/docs/Nebflow ~/.nebflow/projects/nebflow-website ~/Claude\ code/Nebflow` 仅剩历史归档/测试数据；四域名实测收敛为三（nebflow.space/auth./api.） |

---

## 7. 验收点总表（二值 · 可自动化）

| # | 验收点 | 命令/方式 | 阶段 |
|---|---|---|---|
| AC-1 | brand.conf domain 定值 nebflow.space 并 commit（~/.nebflow 与主仓各自 commit） | `git log` + 文件内容 | P0 |
| AC-2 | 客户端 profile 链接打开 `https://nebflow.space/profile` 200 | 客户端点头像 + `curl -I` | P0 |
| AC-3 | `sbt test` 全绿（Branding 三断言与新值一致） | `sbt test` | P0 |
| AC-4 | `api.nebflow.space` health 200 | `curl https://api.nebflow.space/api/health` | P1 |
| AC-5 | 官网 `/api/profile` 代理走新域仍工作 | 登录态 `curl /api/profile` | P1 |
| AC-6 | 旧域 `neblink.nebflow.space` 双域共存期仍 200 | `curl` | P1 |
| AC-7 | `auth.nebflow.space` discovery 200 + issuer=新域 | `curl .../oidc/.well-known/openid-configuration` + jq issuer | P2 |
| AC-8 | 官网登录闭环（新域下 code flow） | 浏览器登录 + `curl` cookie 断言 | P2 |
| AC-9 | 客户端 PKCE 登录闭环（enroll 成功） | 客户端登录 + `/api/neblink/status` loggedIn:true | P2 |
| AC-10 | 设备互联零中断（切换前后 deviceToken 均可鉴权） | 隔离实例 + 存量 token 冒烟 | P2 |
| AC-11 | 分支 push 自动出 preview URL 且内容更新 | Vercel 部署列表 + curl preview URL | P3 |
| AC-12 | staging preview URL 稳定可达 | curl 固定 URL 200 | P3 |
| AC-13 | 正式域无测试功能（Hub 404、导航无入口）；preview 域有 | `curl nebflow.space/hub` 404 vs preview 200 | P3 |
| AC-14 | staging preview 登录可用（redirect 白名单生效） | 浏览器登录闭环 | P3 |
| AC-15 | `neblink.space` 无解析 | `dig +short neblink.space` 空 | P3 |
| AC-16 | neblink 域名引用清零（用户可见层） | `rg -i "neblink\.space|neblink\.nebflow"` 代码/配置层 | P4 |
| AC-17 | 全套冒烟：官网/Logto/API 三端真实启动 + curl 断言 | 按 §1 表四 URL 重测 | P4 |

---

## 8. 风险与取舍

| 风险 | 等级 | 缓解 |
|---|---|---|
| Logto 域名切换的 1h 重登窗口（唯一用户可见中断） | 中 | 低活跃时段执行；SSO 一键重登；refresh 自愈；设备互联不受影响 |
| 单项目后 `vercel --prod` 误发正式域 | 中 | §5.3 护栏：本地禁用 + Agent.md 铁律 + Vercel 只接 main |
| 测试内容必须进 git（放弃未 commit 本地预览） | 中（工作流） | 作者拍板点；保底 = 方案 B（staging.nebflow.space） |
| preview URL 登录需加 redirect 白名单 | 低 | 仅涉登录分支；staging 分支一次搞定 |
| 双域共存期配置漂移（新旧域同时 live） | 低 | 每阶段限定时长（P1 1-2 周 / P2 48h-1 周），验收后即删 |
| 在途分支积压（十余个 feat/*） | 低 | P3 前置：按各分支 preview 验收后合 main，再删 preview 项目 |
| Logto 内若存在 API resource indicator `https://api.neblink.space`（部署纪要曾建议） | 极低 | 纯内部字符串，无客户端请求（authorize 不带 resource 参数），不动；可选清理 |

---

## 9. 证据索引（file:line / 实测）

| 论断 | 证据 |
|---|---|
| 四域名 DNS + 全端在线（2026-09-01 实测） | `dig +short` 四域；`curl` 307/200/200/200 |
| issuer = auth.neblink.space/oidc | `curl https://auth.neblink.space/oidc/.well-known/openid-configuration` 200 |
| Logto ENDPOINT 驱动 issuer | `neblink-server/deploy/docker/docker-compose.yml` `ENDPOINT: ${AUTH_PUBLIC_URL:-https://auth.neblink.space}` 注释「drives OIDC issuer + discovery」 |
| Caddy 站点块参数化 | `neblink-server/deploy/docker/Caddyfile` `{$SITE_DOMAIN:neblink.nebflow.space}` / `{$AUTH_DOMAIN:auth.neblink.space}` |
| 官网验 iss 钉死 endpoint | `nebflow-website/lib/logto.ts:107-121` `verifyIdToken` `issuer: ${cfg.endpoint}/oidc` |
| 官网 API 单点配置 | `nebflow-website/lib/config.ts:6` `SITE.neblinkUrl` 注释「single source」 |
| 客户端 embedded Logto endpoint | `NeblinkModel.scala:267` `endpoint = "https://auth.neblink.space"` |
| brand.conf 双域机制 | `brand.conf:2-11`（domain/profileUrl 调试真值 + NEBFLOW_BRAND_DOMAIN 覆盖注释） |
| neblink-server 双域部署参数化 | `neblink-server/deploy/docker/docker-compose.yml` SITE_DOMAIN/AUTH_DOMAIN env |
| NEBLINK_OIDC_ISSUER 缺省只验签名 | `2026-08-18_logto-stage1-integration.md` env 契约表（「缺省只验签名+exp」） |
| 设备互联凭据与 Logto issuer 无关 | `20260901_account-data-and-logto-admin.md` §1（deviceToken 为 neblink-server 自签，device_credentials 表） |
| sub 主键统一、数据挂 sub | `20260901_account-data-and-logto-admin.md` §1/§4（users.id=sub，设备/好友/消息全挂 sub） |
| 官网 profile/login 双域 200（P0 前提） | `curl https://nebflow.space/profile` = 200 |
| flag 机制（Hub 样例） | `nebflow-website/.nebflow/Agent.md`（双域铁律 + 测试功能标记规则）；`lib/feature-flags.ts` 在 feat/hub-flag 分支 |
| 双项目 Vercel 结构 | `20260824_website-publish-flow.md` §3（prod + preview 双项目）；`.vercel/project.json`（单项目 id，P3 合并目标） |
| Logto 唯一身份源、GitHub 已清 | `20260901_login-chain-analysis.md` §2.3；`20260901_account-data-and-logto-admin.md` §1 |

---

## 附：与在途修复方案的关系

- 本方案与 `20260901_login-chain-analysis.md` 的修复项正交：P0 顺带完成其①（brand.conf 定值），其余（②登录入口统一 B1 ③头像打通）不受域名收敛影响，可并行推进；P2 切换 Logto 域名时应一并确认 B1 品牌化配置（Logto sign-in-experience）。
- 本方案落地后 `20260824_website-publish-flow.md`（双域名分工）标记 superseded。
