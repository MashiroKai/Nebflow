# 扫码绑定失败(io) + projects 403 · 诊断修复批报告

批次：2026-10-22（台账新批，关联 #717）· 作者症状原话：「现在还是会有，扫码绑定失败：io和项目列表加载失败 projects 403 的问题」

---

## 每症状一行结论

| # | 症状 | 一行结论 |
|---|------|---------|
| ① | 扫码绑定失败：io | `socialPanel.js:1542` 的 `'io'` 是**兜底文案字面量**——begin/status 任一 fetch 非 2xx 或传输错都落它（1542/1598/1627 三处）。403（会话被拒）与 503（隧道断）在旧代码里同显示「io」，作者永远看不到真因。**本批已修**：401/403 改走 sessionGate 统一自愈 + 卡内文案改「登录已过期」；传输错保持 'io'。 |
| ② | io 错误 | 与 ① 同源（同一 'io' 文案族），无独立病。 |
| ③ | 项目列表加载失败 projects 403 | `nodeData.js fetchProjectsRaw`：`throw new Error('projects ' + r.status)` ⇒ 面板错误态副标题正是 "projects 403"（作者原话逐字）。403 来源 = **宿主会话被引擎重启清零**（今日 10:25:54 beta.2 自更新 → 10:26 关停 → 10:35:39 新进程；会话存储纯内存，重启即清零是注册权衡），转发请求到宿主被 403 顶回。**本批已修**：401/403 交 sessionGate（横幅 + 静默重铸 reload，与 nfTicket #520 同纪律）。 |

## 根因链（三症状一家族）

```
宿主引擎(本机) 10:25 beta.2 自更新重启 ──┐
  └ 宿主会话清零(GatewaySessionStore 纯内存) ──→ 转发 /api/* 被宿主 403 ──→ 「projects 403」
跨境隧道不稳定（持续背景噪声）──────────────┘
  ├ 09:32–09:34 本机出口 "Network is unreachable" ×4
  ├ 09:36 / 09:39 隧道断连 + 僵尸连接 abort ×2
  ├ 10:26–10:35 重启窗（隧道整段断开）
  ├ 10:54 / 11:05 中继侧 body_frame unknown-cycle 丢弃爆发 + ws pump queue full（在途浏览器请求异常中止）
  ├ 11:53 宿主侧 zombie abort ×2 + 中继 3-pong 逐出（同时段另一用户设备 bbbee77a 同症状 ⇒ 公共跨境路径抖动）
  └ 12:31 宿主侧 portal uplink inbox full 丢弃
       └→ console 请求间歇性失败 ──→ 扫码 begin/status 失败 ──→ 「扫码绑定失败：io」（无自愈、无真因）
```

- 中继 ingress 平面**健康**，本批排除：`require_user` 已带 #520 滑动准入（部署二进制逐字含 "sliding admit" 标记串）；作者 refresh 行存活（最新存活行 10-18 签发，30d）；`account_host_binding` = bound（63mdczgnd8gy → ad71113b → 6a64d249 = 本机 Mashiros-MacBook-Pro-iso-a659864a :8080）；当日零 "ingress gate" 滑动准入 WARN。
- 宿主引擎日志（`~/.stand/logs/stand.log`）今日**零** scan-bind 日志行 ⇒ 作者扫码的 begin 大概率从未到达引擎（在传输层就失败了），与 'io' 文案自洽。
- 今日**零** ticket 兑换日志 ⇒ 诊断时点宿主会话尚未经自愈重座（作者下一次打开控制台时 ws.js 双平面自愈链会自动重铸，该链随 beta.2 今日 10:35 起已生效）。

## 与 #717 的关系核对（任务令第 2 项）

#717 实修的是**独立卡窗静态路由**：`/index.html` 落根白名单外 → 404 空体 → 白窗（commit 5cc463353，2026-10-22 11:48 合入）。与扫码绑定/403 **非同族根因**（该修复「会话非挡板」结论成立）。注：5cc463353 在 beta.2 tag 之后合入，随下一次重启窗生效。

## 修复点（全部本仓客户端面；亮暗无关）

| 面 | 改动 |
|---|---|
| `web/js/socialPanel.js` `beginInlineScan` | 401/403 ⇒ `noteAuthRejected('social-scan')`（sessionGate 可见横幅 + 1.5s 自愈 reload，单飞 + 60s 冷却防环）+ 卡内文案改 `t('social.scan.authExpired')`（「登录已过期，正在重新进入控制台…」），不再误报 'io'；其余非 2xx 与传输错保持既有 'io' 兜底逐字不动 |
| `web/js/socialPanel.js` `tickSession` | status 轮询 401/403 ⇒ 同一 `noteAuthRejected('social-scan')`；连击计数与 'io' 兜底语义不变 |
| `web/js/nodeData.js` `fetchProjectsRaw` | 401/403 ⇒ `noteAuthRejected('projects')`；错误消息仍带状态码（面板错误态显示不变）；5xx 静默降级不变 |
| locales zh-CN/en | 新键 `social.scan.authExpired` |
| `tests/authgate-projects-403.spec.mjs`（新） | 7 断言：403 拒绝消息+横幅+触发面读数+reload 腿 / 401 同收口 / 503 零横幅零 reload |
| `tests/social-panel.spec.mjs`（+SAG 套件 6 断言 + `SOCIAL_SUITE=scanauth` 独立入口） | begin 403 ⇒ 横幅 social-scan + 卡内文案非 'io' + reload 腿；status 中途 403 ⇒ 同横幅；begin 503 ⇒ 'io' 保持、零横幅零 reload |

**质量闸**：checkJs **257 = 基线 257**（零新增）；`authgate-projects-403` **7/7**；social-panel SAG **6/6**；`session-gate` 回归 **15/15**；`ilink-fields` 红证明套件 **4/4**（真页面 + 改后 socialPanel.js 模块图装载冒烟）。全量 social AFTER 链在本环境 headless 基线即挂（`#social-btn` 不可见，`evidence/20261020_111006_bind-transactional` 已备案）——本批 A/B 复核：pristine HEAD 树同位同形失败（`git archive HEAD` 提树重跑，同 TimeoutError），**与本批无关**实锤。

## 生产侧动作清单（**未动生产**，候作者逐项批准）

1. **中继 3-pong 逐出容忍窗**（link-server `TUNNEL_MAX_MISSED_PONGS=3`，~1s 拍 ⇒ 跨境静默 ~3s 即逐出）：今日宿主隧道 11:53 被逐、在途浏览器请求成批异常中止。候选：逐出阈放宽到 6–10 连击（或 ping 拍放宽到 3–5s），僵尸清理语义不变。⇒ link-server 改码 + VPS 重部署，**生产动作**，候批。
2. **ingress 拒绝可观测性**（可选）：`require_user` 拒绝目前零日志（本批只能间接排除），加一条拒绝计数 WARN/metric，下次定位从推断变直读。⇒ 同上，生产部署动作。
3. 宿主会话重启清零：注册权衡不动（session-TTL 批 §六已论证落盘备选另批议）。
4. Logto SSO TTL 维持 30d（session-TTL 批 §四建议，无动作）。

## 作者自查步骤（修复生效后）

1. 本批为 **web 前端面**，随 jar 走，**候下一次引擎重启窗生效**（当前引擎 10:35 起 = beta.2，含 10-21 两批：宿主会话 30d/7d + authcheck 12h 保活 + ws.js 双平面自愈已生效）。
2. 生效后再遇「项目列表加载失败 / 扫码绑定失败」：应先弹红色横幅「登录状态已过期，正在重新进入控制台…」并**自动恢复**——不再出现裸「io」。若横幅后仍反复：F12 看 `/api/projects` 返回码——**403** = 会话被拒（等自愈/重登中继）；**503 host_link_down** = 宿主↔中继隧道瞬断（数秒自愈，属跨境链路噪声）。
3. 隧道健康自查：`grep "Relay tunnel" ~/.stand/logs/stand.log | tail` 看 disconnect 频率；频繁 zombie/pong 逐出 ⇒ 候准第 1 条生产放宽项。

## 开放问题

1. **作者在哪个端扫码**（待确认）：本批按**控制台社交面板**的微信 iLink/飞书扫码链路处理——'io' 兜底文案只存在于该面板；若作者实指「NebLink 手机端扫设备码绑定」请告知，另开小批核对（生产库未见 today 的扫码到达痕迹，暂无法从服务端定谳）。
2. 11:53 等窗口断链归属：本机出口抖动 vs 跨境公共路径——中继侧另一用户设备同时段同症状指向后者，需作者侧网络佐证定谳。
3. 今日 00:14–02:53 心跳 HTTP 超时簇（bucket 计数到 30）与 09:32 "Network is unreachable" 簇的归因同上。

## 关键文件

| 面 | 路径 |
|---|---|
| 扫码面板 403 收口 | `src/main/resources/web/js/socialPanel.js`（beginInlineScan / tickSession） |
| 项目列表 403 收口 | `src/main/resources/web/js/nodeData.js`（fetchProjectsRaw） |
| 统一自愈面 | `src/main/resources/web/js/sessionGate.js`（既有，零改动，仅复用） |
| 文案 | `web/js/locales/zh-CN.js` / `en.js`（social.scan.authExpired） |
| 规格钉 | `tests/authgate-projects-403.spec.mjs`、`tests/social-panel.spec.mjs`（SAG 套件） |
| 生产侧只读取证 | link-server `src/routes.rs`（require_user:558 / console_ingress_http:3712）、`src/relay.rs`（TUNNEL_MAX_MISSED_PONGS:1192）、VPS `docker logs neblink-server`（只读）、`/root/neblink-server/deploy/docker/data/neblink.db`（sqlite mode=ro） |
| 证据 | `evidence/20261022_scanbind-403/`（before/after 截图 + shot 脚本 + 时间线读数） |
