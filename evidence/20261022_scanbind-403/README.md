# evidence/20261022_scanbind-403 — 扫码绑定失败(io) + projects 403 诊断修复批证据

## 时间线读数（2026-10-22，+0800）

来源：中继侧 `ssh root@45.32.62.185 'docker logs neblink-server'`（**只读**）、宿主侧 `~/.stand/logs/stand.log`（只读）、`~/.stand/shutdown-marker.json` / `install-manifest.json`、生产库 `sqlite3 file:…?mode=ro`（只读）。

| 时刻 | 事件 | 来源 |
|---|---|---|
| 00:14–02:53 | 宿主→中继心跳 HTTP 超时簇（HttpConnectTimeout/HttpTimeout bucket 计数至 30） | stand.log |
| 09:32–09:34 | `Relay tunnel error: no HTTP response (Network is unreachable)` ×4 爆发，09:34:00 重连 | stand.log |
| 09:36:36 / 09:39:35 | 隧道断连（158s/140s 存活）+ 僵尸连接 abort ×2，09:39:41 重连 | stand.log |
| **10:25:54** | **beta.2 自更新安装落位**（install-manifest installedAt 2026-10-22T02:25:54Z） | ~/.stand/install-manifest.json |
| **10:26:06** | 旧引擎优雅关停（SIGTERM；shutdown-marker） | ~/.stand/shutdown-marker.json |
| 10:26:37 | 隧道断开（对齐中继侧 02:26:37Z disconnect 6a64d249） | stand.log + relay log |
| **10:35:39** | 新引擎进程起（PID 36826，beta.2，sbt） | ps/.pid.meta |
| 10:35:56 | 隧道重连 | stand.log |
| 10:54:12 / 11:05:52 | 中继侧 `portal body_frame for unknown cycle — dropped` 爆发 / `portal ws pump queue full`（在途浏览器请求异常中止） | relay log（02:54:12Z / 03:05:52Z） |
| 11:53:43–52 | 中继 3-pong 逐出 6a64d249（宿主）+ 宿主侧 zombie abort ×2 → 0.5s 重连 | relay log（03:53:43Z）+ stand.log |
| 12:12–12:49 | 中继侧另一用户设备 bbbee77a（120.239.206.8）同窗反复被逐 ×6 ⇒ 跨境公共路径抖动 | relay log（04:12–04:49Z） |
| 12:31 | 宿主侧 `portal uplink inbox full — uplink item dropped` | stand.log |

## 排除项（生产只读取证）

- 中继 ingress 滑动准入**已部署**：容器二进制逐字含 "ingress gate"/"sliding admit" 标记串（binary grep 1/1）；当日零该 WARN = 未触发达臂。
- 作者 refresh 行存活：`refresh_tokens WHERE user_id=63mdczgnd8gy` 最新存活行 10-18 签发（30d），无全吊销。
- `account_host_binding` = bound：63mdczgnd8gy → ad71113b → **6a64d249 = Mashiros-MacBook-Pro-iso-a659864a（本宿主，:8080，2026.10.21-beta.2）**；`not_bound` 排除。
- 今日零 ticket 兑换、零 WS 鉴权拒绝日志（宿主侧）＝会话重座未发生/作者未开台，非新拒绝源。

## 代码锚（改前）

- `web/js/socialPanel.js:1542`（begin 失败兜底 `'io'`）、`:1598`（轮询连击兜底 `'io'`）、`:1627`（failed 态 error 非串兜底 `'io'`）。
- `web/js/nodeData.js fetchProjectsRaw`：`throw new Error('projects ' + r.status)` ⇒ 面板错误态副标题 = "projects 403"（`projectTab.js:727` 渲染 `project.loadFail` + hint）。
- `noteAuthRejected` 改前仅两处接线：`nfTicket.js:210`（铸票腿）+ sessionGate 保活拍——项目列表与扫码两腿均未接入（本批补齐）。

## 规格

- `tests/authgate-projects-403.spec.mjs`：7/7（403 消息+横幅+读数+reload；401 同收口；503 零横幅零 reload）。
- `tests/social-panel.spec.mjs` SAG 套件：6/6（SOCIAL_SUITE=scanauth 可独立跑）。
- 回归：session-gate 15/15；ilink-fields 4/4（真页面模块装载冒烟）；checkJs 257=257 基线。
- 全量 AFTER 链 headless 基线失败与本批无关的 A/B：`git archive HEAD` 提 pristine 树重跑同位同形 TimeoutError（#social-btn hidden，openPanel 挂），与 `evidence/20261020_111006_bind-transactional/README.md` 运行安全边界备案一致。

## 截图

- `shots/sag1-begin403-before-1440-light.png` — 改前树：begin 403 → 卡内「扫码绑定失败：io」（sessionGate 零动作）。
- `shots/sag1-begin403-after-1440-light.png` — 改后树：begin 403 → 红色「登录状态已过期，正在重新进入控制台… / Session expired — re-entering the console…」横幅（自愈 reload 随后）；卡内文案改「扫码绑定失败：登录已过期，正在重新进入控制台…」（SAG-1 断言）。

`shot-sag1.mjs`：本地静态服（127.0.0.1 随机口）+ page.route 403 注入，`node shot-sag1.mjs before|after` 可复现（before 树 = `/tmp/ab-web` pristine 提取）。
