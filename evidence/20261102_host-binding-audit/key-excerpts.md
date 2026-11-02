# 逐条证据摘录（路径:行号 + 原文）

路径前缀缩写：`S/` = Stand worktree 根；`LS/` = `~/.stand/projects/link-server`（HEAD 53448f0）；`WS/` = `~/.stand/projects/stand-website`（HEAD b465131）。

---

## 1. 门户「绑定主机」界面真实存在

**WS/components/home/PortalHomeCards.tsx**
- `:215` `export default function PortalHomeCards({ dict }: { dict: AuthDict })`（组件；`:195` 是 `DevicesSkeleton`）
- `:455-524` `bindHost(networkId, deviceId, confirmationCode?)` → `fetch("/api/portal/bind-host", {method:"POST"})`；`:527` `unbindHost()`
- `:786-800` 主机行：`{p.pairing.hostBadge}` 徽标（`p.pairing.hostBadge` 在 `:787`）+「解绑主机」按钮
- `:802-813` **非主机**设备行：`onClick={() => bindHost(networkId, dev.deviceId)}`，文案 `{busy ? p.pairing.binding : p.pairing.bindEntry}`
- `:849-897` 6 位确认码面板（`:865` `confirmCodeLabel`、`:876-882` 输入框 `maxLength={6}`、`:891` 提交）
- `:688-689` 无主机空态引导（"install + sign in on a machine enrolls it"），安装命令常量 `:60`
- `:49` 注释："The no-host onboarding lives here when no device has enrolled yet."

**WS/app/api/portal/bind-host/route.ts:102,220** — server-side proxy（`POST` / `DELETE`，Bearer 不外泄）
- `:13-42` 头注释：`confirmationCode` 承载 D6 guard 的 6 位码；`:19-21` 逐字 "this endpoint is NOT in the
  portal-wire contract's §7 endpoint list, so the request shape is owned by the server's routes/model code"；
  `:36-42` 拒绝码枚举 `confirm_required` / `grace_period_active` / `code_mismatch` 等（409 CONFLICT + reason）

**WS/lib/auth-i18n.ts**
- `:118-131` pairing 键族头注释 `AWAITING AUTHOR VERBATIM REVIEW`
- `:614` `bindEntry: "绑定为我的主机"`
- `:616` `hostBadge: "主机"`；`:617` `confirmCodeLabel: "输入旧主机上显示的 6 位确认码"`
- `:619-622` `mismatch` / `graceActive` / `accountOffline` 文案
- `:891` 英文 `bindEntry: "Bind as my host"`
- `bound.unbind`（`:627` `"解绑主机"`；`:494` 是控制台行的 `"解绑"`）

**WS/app/settings/SettingsContent.tsx:10,458** — `<PortalHomeCards dict={dict} />` 内嵌于 `/settings` ⇒ **真正的设备/主机管理面在 `/settings`**
**WS/app/portal/PortalContent.tsx:29,110-137,294** — `/portal` 只是控制台进入页（头注释："/portal — the console enter route. The unified home (/settings) now carries …"）；`?device=` 深链在**无主机态**才 `replace("/settings?device=…")`（`:135`）；`/settings` 另有常驻入口（`:294`）

**LS/src/main.rs:628-645** — 路由表含 `/api/portal/status`、**`/api/portal/bind-host`（POST + DELETE）**、`/api/portal/console-ticket`、`/api/portal/console-ws`、`/ws`

---

## 2. 「主机」= 账号的单席位绑定行

**LS/src/store.rs:2099-2129**
```sql
CREATE TABLE IF NOT EXISTS account_host_binding (
  user_id TEXT PRIMARY KEY,   -- 单席位：一个账号同时只有一条
  network_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  status TEXT NOT NULL,       -- 'bound' | 'unbound'（墓碑）
  ...
)
```
- `:3675-3693` `get_host_binding`：LEFT JOIN devices（设备已消失则名字空）
- `:3716-3752` `set_host_binding`：upsert by user_id，返回被替换者（用于"位移传播"）
- `:3760-3775` `mark_host_binding_unbound`

---

## 3. console-ticket 的「账号持有目标主机」校验

**LS/src/routes.rs:3240-3400** `console_ticket`，六步：
1. `require_user`（JWKS / AT）
2. **`:3276-3286`** `get_host_binding(user)` 且 `b.device_id == req.device_id`，否则 `403 {"error":"not_bound"}`
3. `:3288-3297` 查 tunnels 活跃，否则 `404 {"error":"host_offline"}`
4. mint 预算，否则 `429 {"error":"rate_limited"}`
5. 发 `ConsoleTicketMint` 帧 + oneshot 等回，超时 `409 {"error":"link_unstable"}`
6. 记 `minted_tickets`，返回 `consoleUrl = https://{console_domain}/?ticket=`

配套：**S/src/main/scala/nebflow/gateway/ConsoleTicketRoutes.scala:10-49,94-101**（`GET /?ticket=` 兑换；relay-mint 且 unbound ⇒ 拒；local-ticket 破玻璃豁免；`:99` 日志 `console ticket redeemed: session established (accountSub=…, origin=…, networkId=…)`）

---

## 4. 6 位码收码面 = PLACEHOLDER / phase 5；且实现未接线

**S/src/test/resources/neblink/portal-wire-contract-v1.3.md**
- `:426-455` §3.1 console-ticket 校验文字 + 错误枚举
- `:457-465` §3.2 status 增量字段（`boundHost` 等）
- **`:467-480`** §3.3 原文：`Migration orchestration endpoint - PLACEHOLDER`，并明写 "…including the confirmation-code submission face of the out-of-band confirmation chain - are deferred to the phase 5 contract freeze"
- `:535-541` endpoint inventory（**无 bind-host**），`:541` 标 `PLACEHOLDER migration-orchestration`

**LS/src/store.rs:3880-3945** `stage_bind_confirmation` doc 逐字自认：
> The confirm arm is production-unusable in this window … portal entry therefore always fails `code_mismatch`, and nothing on the wire tells the operator why … the grace arm … is the only production-usable transfer path

- **全仓 HEAD 中 `stage_bind_confirmation` 零非测试调用者**（`grep -rn stage_bind_confirmation LS/src` 只命中 store.rs 定义/测试）⇒ 服务端从未把 `bind_confirm` 帧落成 pending ⇒ 门户收码必然 `code_mismatch`。

**LS/src/routes.rs:3077-3150** `d6_guard_refuse_or_admit`：非 grace 路径走 `consume_bind_confirmation` → `409 {"error":"confirm_required"|"grace_period_active"|"code_mismatch"}`

---

## 5. BearerFileOnly + 「铸码路由明写 never mintable by a browser session」

**S/src/main/scala/nebflow/gateway/NeblinkRoutes.scala:664-734**（`POST /neblink/bind/confirm`）
- **`:670-674`** 注释逐字：`🔴 Auth rail = BearerFileOnly … never mintable by a browser session`
- `:679-692` 三 kind：`pending` / `offline` / `send_failed`；其中 `:682-688` 提到 "portal wire contract v1.2 §3.3, deferred to the phase-5 freeze" 与 "the guard's 72h grace branch is server-side"

**S/src/main/scala/nebflow/gateway/AuthAdminRoutes.scala:13-27,44,126** — `POST /api/auth/console-ticket`（破玻璃铸票，BearerFileOnly）
**S/src/main/scala/nebflow/gateway/GatewayAuth.scala:45,78** — `BearerFileOnly: Rails = Rails(session=false, bearerFile=true, pat=false)`
**S/src/main/scala/nebflow/neblink/PortalHostFaces.scala:124-157** — `mintConsoleTicket` 唯一策略链：binding gate → budget → identity；relay-mint 90s / local 120s
**S/src/main/scala/nebflow/neblink/ConsoleTicketStore.scala:168-173** — `RelayMintTtlSeconds = 90`、`LocalTicketTtlSeconds = 120`、origin 字面量

---

## 6. `bind_confirm` 被"丢弃" + 「printed code can never match」

原文逐字（Stand 侧，两处）：
- **S/src/main/scala/nebflow/neblink/NeblinkRelayTunnel.scala:1103-1112**（`sendBindConfirm` 注释）："…in them the printed code can never match"
- **S/src/main/scala/nebflow/cli/BindCommand.scala:22**："on any server without that face the printed code can never match"

帧形状（Stand 侧生产端）：
- `NeblinkRelayTunnel.scala:1437-1442` `bindConfirmFrame` = `{"type":"bind_confirm","code":…,"ttlSec":…}`
- `NeblinkRelayTunnel.scala:1093-1114` `sendBindConfirm`
- Stand 侧**没有** `receiveBindConfirm`（本侧不处理入向 confirm）

服务端处置（**不是"丢弃后关隧道"**）：
- **LS/src/relay.rs:104-155** `enum ClientToServer` = `RelayResponse | Ping | Pong | Ack` —— **无 bind_confirm**
- **LS/src/portal_wire.rs:506-533** `probe()`：未知 type ⇒ `Probe::Legacy`
- **LS/src/relay.rs:1860-1897** portal_wire probe 分流：`Legacy` → `relay_legacy_text`
- **LS/src/relay.rs:1224-1458** `relay_legacy_text`，其 `:1453-1455`：`Err(e) => tracing::warn!("relay ws parse: {e}")` 然后返回 `true` **继续收帧**（连接保活）

⇒ 该帧被当作"无法解析的 legacy 文本帧"吞掉，只留一条 warn。断言内容为真，机制描述需改成上面这条。

---

## 7. 码的语义：bound→bound 席位换绑（D6），非"注册"

**LS/src/routes.rs:2954-3075** `bind_host`：
`require_user` → `require_user_online`（M-1 `account_offline`）→ 测试账号禁占 → `check_network_owner` → `device_in_network` → D6 transfer guard → `set_host_binding`（迁移/位移传播）

**LS/src/routes.rs:3017-3028** —— 首次绑定 / 同席位重绑**不需要码**：只有 `transfer = bound.network_id != req.network_id || bound.device_id != req.device_id` 为真才进 D6 guard（注释原文 "A live binding on the SAME seat is not a transfer and passes without a code"）

**LS/src/routes.rs:3214-3234** `unbind_host`（DELETE）→ `mark_host_binding_unbound` + `revoke_and_disconnect_host`

---

## 8. 72h 免码

**LS/src/routes.rs:64-91**
- `:82` `D6_REBIND_GRACE_DEFAULT_SECS: u64 = 72 * 3600`
- env `NEBLINK_D6_REBIND_GRACE_SECS`（`D6_REBIND_GRACE_ENV`）、`:86-91` 解析

**LS/src/routes.rs:3095-3118** 判定：`grace_eligible = !old_online && grace > 0 && last_seen_age > grace`
（旧主机当前不在隧道 / 非 alive、非 stale，且其 `last_seen_at` 距今 >  grace ⇒ 免码放行）

**S/src/main/scala/nebflow/gateway/BindConfirmStore.scala:1-100** —— Stand 侧主机侧 pending 镜像（单槽 in-memory、TTL 默认 `900L` 秒，`:79` `val DefaultTtlSec: Long = 900L`；头注释 `:8-21` 明写 "The SERVER's pending copy is the authority — this store is the host-side mirror"，`:20-21` "the plan routes that case to the 72h grace branch, which the server-side guard owns"）；`BindCommand.scala:29,82,91` 的 72h 与免码换绑文案与其对齐。

---

## 9. `00c44b24` 的账号归属：仓内查不到（权威入口在门户）

- `S/src/main/scala/nebflow/core/tools/DeviceProfile.scala:22,154-158`：`<dataRoot>/neblink/device-profiles.json` = **跨端执行画像**（cwd/shell/地址/探测时刻），**不含账号字段**
- 本机 `device-profiles.json` 里 `00c44b24-40b0-5f55-beec-9ecb3c749135` = `DESKTOP-V7EUCHT`（**不是本机**；本机 `6a64d249`）
- `S/src/main/scala/nebflow/core/reminders.scala:118-170`：`Devices changed:` 提醒来自 systemStable 设备花名册 delta
- 归属唯一权威判定：`get_host_binding` / `list_devices` 均按 `owner_id` / `user_id` 过滤（**LS/src/store.rs:2549-2580** `list_networks WHERE owner_id=?1`；**`:3513+`** `list_devices` by network；**`:3172-3212`** `get_peers` 要求 `d.user_id == user_id`）⇒ 生产库不在本机，**仓内无答案**；登录 `/settings?device=00c44b24-…` 是否能命中该设备行即为答案。

---

## 10. 一个账号能有几台主机：1

- **LS/src/store.rs:2099-2114** `account_host_binding` 主键 = `user_id` ⇒ **单席位**
- 常量不在 Stand 仓；服务端无"上限"参数，结构本身即上限 1

---

## 11. 绑定状态墓碑（本地侧）

**S/src/main/scala/nebflow/neblink/PortalBindingState.scala:24-116** —— `<dataRoot>/neblink/binding-state.json`；**缺文件 / 损坏 = bound**；`markUnbound` 持久化 `"unbound"`；`clearTerminal` 写 `"bound"`
**LS/src/store.rs:3716-3752 / :3492-3505 附近** —— `set_host_binding` 成功后中继据此推送；**LS/src/routes.rs:4692-4706** relay-ws upgrade 的 binding gate：仅当 binding 行指向本设备且 status≠bound ⇒ `401 binding_revoked, terminal:true`

本机 `~/.stand/neblink/binding-state.json` 存在且 `status=bound`（2026-10-13 写入）⇒ 与"缺文件=bound"的默认值同义；其长期未更新不改变结论（`clearTerminal` 只在 unbound→bound 的终态路径写）。今日 relay-mint 兑换成功 = 服务端侧绑定有效的直接实证。

---

## 12. 门户 URL 与前端口

**S/src/main/scala/nebflow/gateway/PortalUrl.scala:21,32-66** —— 门户默认 `brand.conf` 的 `portalUrl` + `?device=<deviceId>`；逃生口 `NEBFLOW_PORTAL_URL`、`NEBFLOW_OPEN_UI=local`
**S/brand.conf** —— `portalUrl = https://mystand.space/portal`、`serverUrl = https://link.mystand.space`、`domain = mystand.space`
⇒ 本机 UI 的"打开控制台"按钮指向 `/portal`，而**绑定入口在 `/settings`** —— 这是"看不到绑定主机"的最大可用性缺口。

**S/src/main/resources/web/js/ws.js:659-700,760-870**、`cardwinDirect.js:109-125` —— 控制台侧 status → console-ticket → consoleUrl 链；**消费 `boundHost.deviceId`**
