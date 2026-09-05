# Logto 后台能力 + Nebflow 遥测盘点

- 日期：2026-09-01
- 类型：调研盘点（只读，未改任何代码/配置/实例）
- 回答作者疑问：「Logto 有 dashboard 之类的功能吗？看我们的后台数据」+「我们 nebflow 有一套旧的遥测，也总结一下」

---

## 第一部分 · Logto 自托管管理后台（Admin Console）能力

### 0. 部署现状（证据：`docs/Nebflow/20260827_logto-deployment.md` + 今日实测）

| 项 | 值 | 证据 |
|---|---|---|
| 实例 | `svhd/logto:latest`（core :3001 栈内 / admin :3002 容器网络） | 部署纪要 §一 |
| 数据库 | `postgres:17-alpine`，named volume `logto-db` | 部署纪要 §一 |
| 反向代理 | Caddy → `auth.neblink.space:443` → logto:3001，TLS 自动签发 | 部署纪要 §一 |
| 位置 | VPS `/root/neblink-server/deploy/docker/`（.env 含 DB 密码，chmod 600） | 部署纪要 §四 |
| 活体 | ✅ `https://auth.neblink.space/oidc/.well-known/openid-configuration` → **200**，issuer=`https://auth.neblink.space/oidc`，含 device_authorization_endpoint、PAR endpoint、id_token ES384 | 本次实测 curl |
| 分支 | neblink-server @ feat/logto-deploy（b67e652→46c64f9→42022de→9496cc7→3c33b3a） | 部署纪要 §一/§三 |

### 1. 结论先行

**Logto 有 dashboard，且自托管 OSS 完整可用**：Admin Console 提供 Dashboard（总用户/今日新增/近 7 天新增/DAU 曲线/WAU/MAU）+ Users（用户列表/总数/详情）+ Audit Logs（登录等全量事件日志）+ Applications（含 M2M）+ API Resources 等页面。你要看的用户数、登录活跃、头像、M2M、API 资源全部能看。

### 2. Admin Console 访问入口

| 入口 | 状态 | 说明 |
|---|---|---|
| `https://auth.neblink.space:8443/console/default/welcome` | ⚠️ **公网当前不可达**（TCP 超时，本次实测） | 部署纪要 §三 定稿入口；**收口清单**（§三⚠️）要求 bootstrap 完成后删除 :8443 块、ADMIN_ENDPOINT 回退 loopback-only——超时与「收口已执行」一致 |
| SSH 隧道 → `http://localhost:3002/console/default/welcome` | 推测（需 VPS 侧确认） | ADMIN_ENDPOINT 回退后的 console 访问方式；Caddy 无 :8443 后仅容器网络/回环可及 |

> ⚠️ **需实测确认**：收口是否已执行、console 当前经何种方式可达（SSH 隧道端口/路径）。入口 URL 的形态依据（为何是 :8443 而非同域路径）见部署纪要 §三 1-5 点源码实证（`getTenantId` 按 origin 匹配 admin 租户、console OIDC redirect 白名单动态推导）。

### 3. Dashboard 页面能看什么（源码实证 logto-io/logto @ master）

**页面**：`packages/console/src/pages/Dashboard/index.tsx`（recharts AreaChart 面积图）

**三个统计块 + DAU 曲线**：

| 指标 | API（OSS core 已实现，`packages/core/src/routes/dashboard.ts`） | 语义 |
|---|---|---|
| 总用户数 | `GET /dashboard/users/total` → `{totalUserCount}` | users 表 count |
| 今日新增（+环比昨日 delta） | `GET /dashboard/users/new` → `{today:{count,delta}, last7Days:{count,delta}}` | 14 天窗口内按日计数，今日 vs 昨日、近 7 天 vs 前 7 天 |
| 近 7 天新增（+delta） | 同上 | 同上 |
| DAU 曲线（30 天面积图，日期选择器） | `GET /dashboard/users/active?date=yyyy-MM-dd` → `{dauCurve:[{date,count}×30], dau, wau, mau}` | 30 天逐日活跃用户曲线（有交互日志的用户去重计数） |
| WAU / MAU（+delta） | 同上 | 7 天 / 30 天活跃用户 |

**关键点**：这三个 dashboard 端点在 **OSS 自托管 core 中完整实现**（非 Cloud-only）——`dashboard.ts` + `dashboard.openapi.json` + `dashboard.test.ts` 均在 `packages/core/src/routes/`。且它们是 **Management API 的一部分**：拿到 M2M token 后可直接 `GET {endpoint}/api/dashboard/users/total` 等，程序化拉数据（dashboard 页面前端就是这么调的）。

### 4. Admin Console 全页面清单（源码实证 console pages 目录）

| 页面 | 能看/能做什么 | 对应作者关心的指标 |
|---|---|---|
| **Dashboard** | 总用户、今日/近 7 天新增、DAU 曲线、WAU、MAU | 用户数、登录活跃 |
| **Users**（+ UserDetails / UserSessionDetails / UserIdentityDetails） | 用户列表 + 搜索（username/ID/邮箱/手机/姓名）、每用户详情（profile 含头像 picture、identities、roles、会话、该用户日志）、改密/删除/停用、角色分配 | 用户数、头像覆盖 |
| **Audit Logs**（+ AuditLogDetails） | 全量事件日志流：登录/登出、token 签发、用户创建/更新、应用事件；可按用户/应用/事件类型/时间过滤，每条含 payload 详情 | 登录事件、审计 |
| **Applications**（+ ApplicationDetails / DynamicAppDetails） | 全部应用（Traditional Web / SPA / Native / **M2M** / SAML / 第三方），app ID/secret、设置、角色 | M2M 应用 |
| **API Resources**（+ ApiResourceDetails） | 资源 indicator 列表、scope/权限定义 | API 资源 |
| Organizations / Roles / Connectors / Webhooks / Sign-in Experience / Tenant Settings / Security / MFA / SSO | 组织与 RBAC、社交连接器、webhook、登录页定制、租户设置 | — |

### 5. 与作者关心指标的对应关系

| 指标 | Logto 怎么看 | 备注 |
|---|---|---|
| **用户数** | Dashboard 总用户卡；Users 页列表（可分页/搜索/按角色过滤） | 直接可得 |
| **登录活跃** | Dashboard DAU/WAU/MAU（「活跃」= 该时间窗内有交互日志的用户去重）；Audit Logs 可按 `SignIn` 事件类型过滤 | 活跃定义以 Logto 日志为准，与业务活跃不完全等价 |
| **头像覆盖** | Users 页每用户 profile 有 avatar（picture claim 字段，可编辑） | ⚠️ 我们的断裂点（见下）：neblink-server 的 `upsert_logto_user` 不写 avatar（`store.rs:1647`），客户端 PKCE scope 无 `profile` → **客户端侧头像恒 NULL**；但 Logto 本身存得了、Console 看得到 |
| **M2M 应用** | Applications 页，类型筛选 M2M，含 client ID/secret 管理 | 可直接核对 |
| **API 资源** | API Resources 页 | 可直接核对 |

> ⚠️ **需实测确认**：① Console 当前可达方式（收口后）；② 部署镜像 `svhd/logto:latest` 的具体版本是否含 Dashboard 页面（master 源码有；2026-08-27 部署时拉取，大概率含）；③ 登录活跃的口径——DAU 按「有日志交互的用户」计，与「实际登录次数」有差异。

### 6. 附加：Management API 程序化路径

- 若建了 M2M（部署纪要 runbook 第 2 步），可直接用 `client_credentials` 换 token 调 Management API：`/api/users?page=&page_size=`（用户列表+总数分页）、`/api/users/search`、`/api/logs?logKey=`（审计日志）、`/api/dashboard/users/total|new|active`（统计）、`/api/applications`（含 M2M）。全部有 openapi 契约（`packages/core/src/routes/*.openapi.json`）。
- 这为「Nebula 定期拉取统计」提供程序化通道，不依赖人工开 Console。

---

## 第二部分 · Nebflow 旧遥测系统盘点

### 0. 结论先行（4 条）

1. **Token 消耗看板（#310）——完整活着**：数据管线（结构化埋点+五维聚合）已合入、前端（GitHub 范式热力图）已完成且在主分支、数据今日仍在写入、入口 = 侧边栏 Activity Bar 用量图标。**记忆中的「前端待 design-engineer」状态已过时——前端已由 Frontend 实现到 v1.3 并合入 main**（spec `token-dashboard-spec.md` v1.3 frozen 2026-08-23）。
2. **Router 日志（~/.nebflow/logs/router/）——活着**：今日文件持续写入，含 usage 字段；但**仓库内无查看 UI**（Router viewer 是纯读端，未在本仓前端）。
3. **CloudBase 匿名遥测——已死**：`TelemetryReporter.isEnabled = false`，CloudBase 依赖已移除，纯残留骨架。
4. **nebflow.log 主日志——活着**：正常滚动。

### 1. 盘点总表

| # | 系统 | 采集项 | 落点 | 聚合/API | 展示 | 可用性 |
|---|---|---|---|---|---|---|
| ① | **结构化 usage 埋点**（Token 看板数据源） | 每次成功 LLM 调用：provider/model/agent/sessionId/input/output/cacheRead/cacheWrite（`AgentActor.scala:1483-1511` LlmComplete 后写入） | `~/.nebflow/usage-records/usage-records.jsonl`（append-only，ReentrantLock 串行，**61,564 条 / 14MB，今日 17:40 仍在写**） | **五维聚合** `dim=provider\|model\|agent\|hour\|day` + provider/model/agent 过滤 + costEquivalent（cache 0.1x 计费口径）（`UsageRecordStore.scala:142-197`；`GET /api/usage/aggregate`，`RestApiRoutes.scala:100-112`） | **usageDashboard.js v1.3**：全年 GitHub 热力图（365 格铺满）、统计卡（1d/1w/1m/3m 区间解耦）、点格日内 24h 曲线（dim=hour）、下钻表、消耗量/命中率双着色；入口 = Activity Bar `#usage-btn`（`usageDashboard.js:1169-1175`，`main.js:67/3109`） | ✅ **全链活着**（git：后端 36c7bf60/dff0066a/3c3c035e，前端 8fcdb33e/2cdc7bec/b3a84271 均在 main） |
| ② | **Router 日志**（LLM 请求/响应 JSONL） | 每次 LLM 调用：request（model/agent/session/messages_count/system_length/tools_count…）+ response（usage 含 input/output/cache_read/cache_creation，`LlmLogWriter.scala`，调用点 `AgentCore.scala:763` pipeLlmCall 后） | `~/.nebflow/logs/router/{date}_summary/_full/_sse.jsonl` + `objects/{hash}.json`（内容寻址存 system/tools/messages），UTC 日界切分，**3 天硬保留自动清理**；今日 09-01 三文件均在写（full 今日 352MB） | 无内置聚合（原始 JSONL） | **无**：注释称「Router viewer（纯读端）」watch 该目录展示（`LlmLogWriter.scala:23-24`），但**本仓 web 前端无 viewer**（grep 无命中） | ⚠️ **数据活着、无展示 UI**；需外部 viewer 或自建查询 |
| ③ | **CloudBase 匿名遥测** | 结构性事件（event+timestamp+properties，不含用户内容）；批量上报 | 内存队列 → `~/.nebflow/telemetry-queue.json` 离线缓存（从未生效） | 无（上报即弃） | 无 | ❌ **禁用**：`isEnabled = false`、`DefaultEndpoint = ""`（`TelemetryReporter.scala:80-83`「CloudBase dependency removed」）；Headless 模式亦禁（:150）——纯死代码骨架 |
| ④ | **nebflow.log 主日志** | 应用全量日志 | `~/.nebflow/logs/nebflow.log`（按日滚动） | 无 | 无（人工 tail/grep） | ✅ 活着（今日 20MB） |

### 2. 「五维聚合」是什么

`UsageRecordStore.aggregate` 的 `dim` 参数：**provider | model | agent | hour | day**（`UsageRecordStore.scala:159-165`），可叠加 provider/model/agent 精确过滤（先过滤后分组，`RestApiRoutes.scala:106-108`）。hour 桶 key 形如 `2026-08-18T13`、day 形如 `2026-08-18`（本地时区，:199-207）。这就是 Token 看板的全部聚合能力——热力图=day 桶、日内曲线=hour 桶、排行=provider/model/agent 桶。

### 3. 与「Token 消耗看板」记忆的对应

| 记忆线索 | 现状 |
|---|---|
| 2026-08-18：GitHub 热力图式，分 provider/模型/agent/时间段 | ✅ 已实现：v1.3 全年热力图 + 维度切换（总计/模型/agent 三档） |
| 结构化埋点 + 五维聚合，数据管线已合入 | ✅ 已合入（spec 表头「数据管线 已合入」；后端提交在 main） |
| 前端热力图待 design-engineer | ❌ **已过时**：design-engineer 出 spec（v1.0-v1.2），Frontend 实现到 v1.3（2026-08-22 用户裁定 GitHub 范式，commit 2cdc7bec），**前端完成并在 main** |
| Router 日志含 usage 字段 | ✅ 活着（response 事件 usage 字段；今日文件在写） |

### 4. 可用性结论

- **能用**：Token 消耗看板（①）——打开 Nebflow 客户端 → 侧边栏 Activity Bar 用量图标 → 完整看板；数据实时（usage-records.jsonl 每成功调用即追加）。
- **只剩日志**：Router 日志（②）——数据完整活着但无 UI，要展示需外部 viewer 或写查询脚本；nebflow.log（④）——原始日志。
- **已死**：CloudBase 遥测（③）——禁用、无 endpoint、无数据。
- **未完成**：无。Token 看板全链完成；Router viewer 是独立于本仓的纯读端（不在本仓范围）。

---

## 附：证据 file:line 索引

### Logto
- 部署拓扑/入口/收口清单：`docs/Nebflow/20260827_logto-deployment.md` §一/§三/§四/§五
- OIDC 活体：本次 `curl https://auth.neblink.space/oidc/.well-known/openid-configuration` → 200（issuer/device_authorization/PAR/ES384）
- Console 页面清单：`logto-io/logto` `packages/console/src/pages/`（Dashboard/Users/AuditLogs/Applications/ApiResources 等）
- Dashboard 前端：`packages/console/src/pages/Dashboard/index.tsx`（total/new/DAU 曲线/WAU/MAU）
- Dashboard 后端（OSS core）：`packages/core/src/routes/dashboard.ts`（`/dashboard/users/total|new|active`）+ `dashboard.openapi.json` + `dashboard.test.ts`
- 头像断裂：`docs/Nebflow/20260901_login-chain-analysis.md` §3（`upsert_logto_user` store.rs:1647 不写 avatar；PKCE scope 无 profile）

### Nebflow 遥测
- 埋点：`src/main/scala/nebflow/agent/AgentActor.scala:1483-1511`
- 存储/聚合：`src/main/scala/nebflow/core/UsageRecordStore.scala:24-34`（记录结构）、`:142-197`（aggregate）、`:104`（logPath）
- API：`src/main/scala/nebflow/gateway/RestApiRoutes.scala:100-112`
- 前端：`src/main/resources/web/js/usageDashboard.js:1-20`（v1.3 头注释）、`:335`（open）、`:1169-1175`（init/入口按钮）、`src/main/resources/web/js/main.js:67/3109`、`css/usage.css`、`index.html:47`
- Spec：`docs/Nebflow/token-dashboard-spec.md`（v1.3 frozen，§「数据管线 已合入」、:361 API 契约）
- Router 日志：`src/main/scala/nebflow/core/LlmLogWriter.scala:17-21`（目录/三文件）、`:23-24`（Router viewer 纯读端）、`:43`（3 天保留）、调用点 `AgentCore.scala:763`
- CloudBase 遥测：`src/main/scala/nebflow/core/telemetry/TelemetryReporter.scala:80-83`（禁用）、`:150`（Headless 禁）；`TelemetryEvent.scala`/`TelemetrySender.scala`（残留）
- 数据实况：`~/.nebflow/usage-records/usage-records.jsonl`（61,564 行 / 14MB / 今日写入）；`~/.nebflow/logs/router/2026-09-01_{summary,full,sse}.jsonl`（今日写入）
