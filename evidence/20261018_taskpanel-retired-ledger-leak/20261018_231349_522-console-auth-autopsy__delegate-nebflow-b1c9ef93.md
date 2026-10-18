# #522 控制台鉴权态全面体检 — 取证记录

批次：delegate-nebflow-b1c9ef93（#522 控制台鉴权态）· 2026-10-18 23:1x
互引：#520（图片点不开，delegate-nebflow-0296fa4f）——同一 Safari 标签、同一族根因（见 §5）。

## 0. 结论速览

| 作者症状 | 真因 | 主嫌疑（旧键漏扫）成立？ |
|---|---|---|
| 插件面板「要登录」 | `/api/plugins` 403 → `plugins.js` 401/403 分态卡（非读键判定） | **否** |
| 项目列表 403 | `/api/projects` 403，同上 | **否** |
| 403 本体 | 浏览器未出示任何有效凭据：会话 cookie 座位失败（`Secure` over plain-http，Safari 丢弃）+ localStorage Bearer 已被 Stage B' 按设计清除 | — |

**全仓 web/ 树 token/locale 键盘点：零漏网**（§1）。#500 的 key() 派生面是干净的；
两症状不是「读旧键=假未登录」，而是「会话座位（Set-Cookie）在 Safari 上根本没落进 jar」。

## 1. 键盘点（任务①）——具名清单

对 `src/main/resources/web`（js/ts/jsx/html）全量 grep `'nebflow_token'` / `'nebflow_locale'`
/ 裸 `'locale'`/`'token'` 作 localStorage 键 / 任意 `'nebflow_'` 字面：

**代码级命中（非注释）全部为兼容层本体，逐一具名：**

| file:line | 内容 | 定性 |
|---|---|---|
| `web/js/branding.js:31` | `LEGACY_PREFIX = 'nebflow_'` | 兼容层本体（迁移源前缀），必须保留 |
| `web/js/branding.js:42-48` | `LEGACY_IRREGULAR` 表（`nebflow-task-collapsed` 等） | 兼容层本体（旧拼写规范化），必须保留 |
| `web/js/ws.js:441-443` | `purgeCookie('nebflow_token')` + `localStorage.removeItem('nebflow_token')` | Stage B' 跨品牌清扫的旧拼写分支（`TOKEN_COOKIE !== 'nebflow_token'` 才走），必须保留 |
| `web/index.html:75` | 头部 classic script 读 `nebflow_sidebar_collapsed` 兜底 | branding.js 无法进 classic script 的启动期兜底（双读：`__b+'_…'` 在先），非 auth，保留 |

**token/locale 读取面：26 处 `localStorage.*Item(key('token')|key('locale'))` 全部走 branding.js 派生；**
**旧字面直读 0 处**（`getItem('nebflow_token'|'nebflow_locale'|'locale'|'token')` 唯一命中 = 上表 ws.js:443 清扫行）。
鉴权消费方抽验：`plugins.js:151`、`flowHelpers.js:12 (authHeaders)`、`nodeData.js→flowHelpers`、
`neblink.js:292`、`deviceRoster.js:35`、`i18n.js:9`、`state.js:45-48`、`ws.js:12,572` —— 全部 `key()`。

**本批修掉的漏网字面（与 #500 mobile 三控制器同族）：**
- `web/js/ws.js:477` `GUARD = 'nebflow_ws_remint_at'`（sessionStorage 防重载闸）→ `key('ws_remint_at')`。
  旧值弃置无害（最坏多一次 remint 尝试）。diff 见 commit。

**点名但不动（非 auth、非 `nebflow_` 前缀纪律内）：**
- `web/js/ws.js:634` `localStorage.getItem('voiceMuted')`（语音静音态，裸键，历史存在）；
- `web/js/input/slashCommands.js:14` `'nebflow_slash.enabled'` 仅存于 SEALED 注释块（真实代码用 `key('slash.enabled')`）。

## 2. 403 定性（任务④）——哪个 endpoint、谁拒的

**Endpoint**：`GET /api/plugins` 与 `GET /api/projects`（`RestApiRoutes`，`GatewayAuth.Rails.ApiFull`：
session cookie > Bearer(file token) > PAT）。两面板症状同根。

**wire 证据（本机 8080，`stand.log` 之外的第一手 curl 矩阵，2026-10-18 22:5x）：**

```
GET /api/projects (无凭据)            → 403 {"error":"Unauthorized"}
GET /api/plugins (无凭据)             → 403 {"error":"Unauthorized"}
GET /api/projects (Bearer 无效)       → 403   ← presented-but-invalid 落穿，不短路（GatewayAuth.scala:91）
GET /api/projects (Bearer file token) → 200   ← 服务端凭据轨完全健康
GET /?ticket=<本地票据> 换 cookie 后：
GET /api/projects (仅 stand_session)  → 200   ← cookie 轨完全健康
GET /api/plugins (仅 stand_session)   → 200
```

⇒ **纯「浏览器没出示有效凭据」，不是服务端另有拒因，也不是前端读错键**
（面板 fetch 带 cookie（fetch 默认 same-origin）且 localStorage 有 token 时还带 Bearer）。

**浏览器侧凭据为何双双缺席：**
1. **会话 cookie 座位失败（本批根因，§3）**；
2. localStorage Bearer 按**设计**不在了：`ws.js:427-444 purgeLongLivedCopies()` ——
   session 轨一经验证（`/api/nf-authcheck` 204 + rail header）即清除 `key('token')`
   localStorage 副本与旧 cookie（token retirement Stage B'，计划 §6.6）。

**日志证据（`~/.nebflow/logs/stand.log`）：**
- `22:00:38` / `22:14:09` / `22:59:56` / `23:00:24` / `23:05:18` `WARN nebflow.ws - WebSocket auth failed from ::1/127.0.0.1`
  ——作者卡住的 localhost 标签**此刻仍在**反复握手失败（#520 在查的同一标签）；
- 会话店（in-memory）现况（`/api/auth/sessions`，file-token Bearer 读取）：
  `NGa7RhYy relay-mint created 22:00:33 lastSeen 22:15:26` —— 冷启动门户腿 22:00:33 座位、
  22:15 后无触碰 ⇒ **relay/门户侧会话活着，localhost 标签的 jar 里没有它**（origin 隔离/座位失败）。

## 3. 根因：会话 cookie `Secure` over plain-http，Safari 丢弃

链条（全部第一手取证）：

1. `GatewaySessionStore.newCookie`（scala, :201 起）冻结属性含 **`secure = true`**；
2. 唯一座位点 `ConsoleTicketRoutes.scala:86`（`GET /?ticket=` 兑换）把该 cookie 发在
   **http://127.0.0.1:8080** 的 303 上。抓包原文：
   `Set-Cookie: stand_session=…; Max-Age=43200; Path=/; SameSite=Lax; Secure; HttpOnly`（无 HTTPS！）；
3. **WebKit 不豁免 localhost**：plain-http 上送 `Secure` cookie 一律不入 jar
   （Chrome 89+/Firefox 75+ 豁免 localhost，Safari 没有 —— 同族行为差异解释了「有时好有时坏」）；
4. Safari 实测复现（23:06:30 本批 mint 本地票据 + `open -a Safari`）：兑换 303 后
   `stand.log` 立刻出现 `23:06:31.701 / 23:06:33 / 23:06:37 / 23:06:47 WebSocket auth failed from 127.0.0.1`，
   且新会话 `p6WrVO29`（local-ticket 23:06:31）`lastSeen` **永停在建档秒** ——
   页面自身后续每个 /api 调用都 403，cookie 果然没进 jar；
5. 门面（ws gate）按 4fe366cd2（10-14 作者令「gate 即统一账号登录」）弹 PKCE 登录模态；
   PKCE 成功 = **设备注册**（`AuthRoutes` callback 成功路径零 `sessions.create`、零 Set-Cookie ——
   全仓 `sessions.create` 唯一调用点 = ConsoleTicketRoutes:78），**控制台会话依旧没有** ⇒
   WS 继续拒、面板继续 403 ⇒ 「老是显示要登录」死循环。作者之问「看插件为什么需要登陆？」
   的答案：**弹的不是「控制台登录」，是设备登录；控制台会话另有其门，而那扇门在 Safari@http 上焊死了。**

**重启的作用（22:02）**：会话店 in-memory、重启即清（GatewaySessionStore.scala:58-62 注册过的取舍）
⇒ 重启前能用的标签全部失效；但这只是**触发器**——根因是重启后任何再座位尝试在 Safari@http 上都必败。

## 4. 修复（任务②扩真因面）

| 文件 | 改动 |
|---|---|
| `src/main/resources/web/js/ws.js:476-481` | GUARD 裸字面 → `key('ws_remint_at')`（#500 同族收尾） |
| `src/main/scala/nebflow/gateway/GatewaySessionStore.scala` | `newCookie(sessionId, secure = true)` —— Secure 参数化，默认 true（https/隧道面不变） |
| `src/main/scala/nebflow/gateway/ConsoleTicketRoutes.scala` | 兑换面：`secure = req 是 https ‖ TunnelOriginMark.isMarked(req)` —— 隧道腿（门户 HTTPS 源）保留 Secure，plain-http 直连腿（本机控制台）去掉 Secure |

安全性论证：`TunnelOriginMark` = boot 装配的进程内 256-bit 随机标记，bridge 转发时注入、
客户端同名头先剥（TunnelOriginMark.scala:1-59 + PortalTunnelBridge:370,495）⇒ 「带匹配标记」⇔
「该 cookie 将被 HTTPS 源消费」；直连腿去 Secure 与退役前的 token cookie
（`ws.js:594` SameSite=Strict 无 Secure 同面）暴露面一致，且会话 12h 绝对上限 + 2h 滑窗 + 可吊销。

Spec：`ConsoleTicketRoutesSpec`（plain-http ⇒ 无 Secure；https ⇒ 有；隧道标记 ⇒ 有）
+ `GatewaySessionStoreSpec`（默认 Secure=true；显式 false 仅去 Secure，余属性冻结）。
**实测绿**：sbt testOnly 两 spec，20/20 passed（23:15:53，编译 568 main + 768 test 全绿）；ws.js `node --check` OK。

## 5. 与 #520 互引（任务⑤）

#520「图片点不开」查的是**同一标签**：nf-file/inject 面的会话闸与 WS 握手同吃
`stand_session` cookie（`GatewayAuth.WsAuth`）。本批 §3 的座位失败在 该标签上是**全 /
api 面**的（WS + REST + 文件面），图片面只是同族症状之一。本批修的 Secure 座位即共根：
**修一处，两面生效**。#520 侧「点击路径」（若有前端交互独立缺陷）仍归 #520。

## 6. 验证与生效面

- 生效：dev 重启窗（sbt run 重启即加载新 jar）后，Safari 打开 `http://127.0.0.1:8080/?ticket=<nebflow token 输出>` ——
  兑换 cookie 可入 jar（无 Secure）⇒ 控制台 WS 连上、插件面板/项目列表直接活；
  打包态候下次重启窗。
- 本批实测（修复前）：curl 三态矩阵（§2）+ Safari 两次兑换即时复现握手失败（§3.4）——
  修复前 Safari 双面**不可能**活，正是作者所见；修复后按 §6 首行操作即活（等重启窗落 jar）。

## 附：本批未动/移交

- 门面 UX（PKCE 登录成功≠控制台会话建立的用户误导）= 4fe366cd2 作者令的后续产品决策，
  本批不动，仅在本报告 §3.5 记录机制。
- `voiceMuted` 裸键（ws.js:634）非本批族，留观。
