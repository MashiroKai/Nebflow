---
type: fix-report
status: published
date: 2026-10-31
scope: 登录失效弹窗循环 / 全局登录态单例 / 旧域 auth 残留
---

# 登录失效弹窗循环 + 旧域残留修复批（2026-10-31 作者手机实测报障）

> 报障：微信内置浏览器访问 link.mystand.space 工作台，会话一过期即「This site is attempting to open a pop-up window」反复弹；点 Allow 跳去旧域 nebflow.space；联系人页同屏挂两块「登录已失效」。根因令（作者 07:37）：社交功能开放后联系人页轮询鉴权接口，portal 会话一过期，每个面板各自判失效、各自 window.open。

## 根因（一句话）

登录失效后 `friendsApi.req()` 对**每个** 401/403 都派一次 `fm-auth-required`（轮询不停 ⇒ 事件流不停），两个 `{auto:true}` 监听每过 15s 守卫窗就重拉整条 PKCE 链并 `window.open` —— 微信对非手势开窗弹系统拦截窗 ⇒ 弹窗循环；同时 `LogtoConfig.embeddedDefault.endpoint` 仍指旧域 `auth.nebflow.space` ⇒ 登录页跳旧域。

## 修复（四处收敛）

1. **自动链零 window.open**：`messages.js` / `contacts.js` 的 fm-auth-required 监听改 `{auto:true, deferPopup:true}` —— 登录卡页面内单飞，卡内「重新打开登录页面」键才是真手势的唯一点开入口（`activityBar.js` 既有 deferPopup 形态，零新轮子）。
2. **全局登录态单例**：`friendsApi.js` 新增 `notifyAuthRequired()` 闩 —— 一个失效回合全站只派一次事件；`neblink.js` 在 `loggedIn` 任意翻转时派 `fm-auth-resolved` 复位闩（下一段失效照常首派）。`plugins.js` 的 401/403 也并入同一闩。
3. **失效块单例**：`contacts.js` 每 render 回合只允许第一个命中 auth 态的区域挂「重新登录」键（`mountAuthExpired`），其余区域只留文案；auth 态不再给「重试」（重拉对已死会话无意义）。修前同一 `listErrorKind='auth'` 驱动列表区 + 请求区同屏各挂一块。
4. **旧域残留**：`NeblinkModel.scala` 内嵌默认 Logto 端点 `https://auth.nebflow.space` → `https://auth.mystand.space`（REBRAND.md 裁定：官网登录用 auth.mystand.space，旧域仅留灰期回调别名）；`NeblinkModelSpec` / `HeadlessLoginE2ESpec` 钉值同步。

## 残留点清单（全仓 grep `nebflow.space` 甄别后）

- **运行时真残留（已修）**：`NeblinkModel.scala:766` embeddedDefault.endpoint（唯一会跳到浏览器的旧域字面量）。
- **合法保留**：服务子域字面量 `neblink.nebflow.space`（NfFileRoutes/RestApiCtx/NeblinkClient/Protocol 注释与护栏判据，`EnrollGuard.ProdDomains` 保留旧域是为仍指着旧中继的安装留闸）；测试夹具（`official-fixtures/plugin.json`、PatAuth/MachineTokenAnchor 的 api./auth. audience 串）；`scripts/check-legacy-domain.mjs` 门禁本体。裸域字面量门禁：白名单外 = 0。
- **未触碰**：link-server 侧配置（跨仓，portal 下发的登录 URL 若其 config 仍写旧域需在服务端改）。

## TTL 核查（portal 会话是否缩回 ~10-15min）

本仓网关会话 = 30d 绝对 + 7d 滑动（`GatewaySessionStore.scala:182,193`，session TTL 批 2026-10-21，#908/#618 的修法仍在）；ConsoleTicket 30-120s 只是换票凭据不是会话。10-15min 体感过期来自 **link-server 的 portal 会话**（跨仓，本批够不着）——挂账建议查 link-server 会话 TTL。

## 验证

- 新增 `tests/login-expiry-singleflight.spec.mjs`（自足，12 断言全绿）：失效首派一次 / 自动链零 popup / 闩内 401 连爆零增量 / fm-auth-resolved 复位再首派 / 联系人失效块单例 / 真手势点开 authorizeUrl=auth.mystand.space。
- 既有回归：`rcptcode-friendterminal`（authRequired===1 钉面）、`cardwin-session-scenarios`（11 断言）、`mobile-v3` + `settings-cleanup`（17 断言）、`check-legacy-domain.mjs` 门禁、`npm run build` 全绿。
- `check-circular.mjs` 报的两个 import 环（popArtifacts 族 / mobile navController 族）为**改动前既有**，与本批无关，未动。

相关：[[glossary]]、[[20261030_211200_coldstart-batch-8fixes__delegate-nebflow-7ceafc0d]]（同域登录护栏）、[[20261025_030145_legacy-domain-sweep]]（旧域清扫前批，见 reports/）。
