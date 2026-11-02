---
type: audit
status: published
date: 2026-11-02
scope: 门户「绑定主机」机制核查（Stand NeblinkRoutes/BindCommand/PortalBindingState + link-server routes/store/relay + stand-website PortalHomeCards/bind-host 代理 + 生产 mystand.space 实测）
---

# 门户「绑定主机」机制核查（2026-11-02 11:41 作者问「这是怎么回事」）

> **结论先行：网页上有「绑定主机」，但入口在 `mystand.space/settings` 的设备行上，不
> 在 `/portal`。** `/settings` 每台非主机设备行有「绑定为我的主机」按钮、主机行有
> 「解绑主机」、换绑时弹出 6 位确认码面板（生产 SSR 已实测含这三处中文字面）；
> `/portal` 只是控制台进入页，匿名访问 302 到登录——**那份分析没有登录，所以看不到，
> 并因此断言「网页上没有这个动作」，这条是错的**。
>
> 其余 9 条断言：4 条成立（收码面 PLACEHOLDER、BearerFileOnly 原文、72h 免码 72×3600、
> 码的换绑语义），2 条成立但表述要改（主机是账号的**单席位绑定行**不是"角色"；
> `bind_confirm` 帧不是"丢弃后关隧道"，而是并入 legacy 解析失败分支只留一条 warn），
> 1 条部分成立（门户入口存在性），1 条仓内查不到（`00c44b24` 的账号归属：权威入口在
> 门户自己），1 条把用法说反了（6 位码只用于 **bound→bound 换绑**，从不用于"注册成主机"）。
>
> 它另外**三条事实是错的**：本机 PID 不是 18692/20332 而是 **30450**（2026-11-01 13:23:19
> 起，未重启）；`~/.stand/neblink/binding-state.json` **存在**且 `status=bound`；
> `00c44b24` 是 **DESKTOP-V7EUCHT**，不是本机（本机 `6a64d249`）。这三条指向同一件事：
> **那份分析跑在另一台机器上，且把 V7EUCHT 当成了"本机"。**
>
> 本文只做核查，**未改产品代码**。证据落在 `evidence/20261102_host-binding-audit/`。
> 运维红线写法见 [[production-ops-checklist.zh-CN]]，PID/端口台账现状见
> [[parallel-session-resources.zh-CN]]。

## 1. 一句话机制

一个账号最多有**一条**主机绑定行 `account_host_binding(user_id → network_id, device_id)`
（主键就是 `user_id`）。门户 `console-ticket` 铸造能不能成功，第二步校验的就是「这个
账号的绑定行指向的 device 是不是你要的那台」；中继隧道升级门再用同一行的 `status`
做断连判据。**「绑定主机」= 把这条行指到某台设备**，界面在 `/settings`。

## 2. 十条断言逐条核对

| # | 断言 | 判定 | 证据（路径:行号） |
|---|---|---|---|
| 1 | 门户「绑定主机」动作不存在 | **假** | `stand-website/components/home/PortalHomeCards.tsx:802-813`（非主机行的「绑定为我的主机」按钮 → `bindHost(networkId, deviceId)`）、`:786-800`（主机徽标 + 「解绑主机」）、`:849-897`（6 位码面板 + 提交）；文案 `lib/auth-i18n.ts:614` `bindEntry: "绑定为我的主机"`、`:616` `hostBadge: "主机"`；代理 `app/api/portal/bind-host/route.ts:102`（POST）、`:220`（DELETE）；服务端 `link-server/src/main.rs:630` `/api/portal/bind-host`、`src/routes.rs:2970` `bind_host`、`:3220` `unbind_host` |
| 2 | 主机 = 同一设备角色？ | **部分对（表述欠精确）** | 它是账号级**单席位绑定行**而非设备角色：`link-server/src/store.rs:2105-2114` `account_host_binding (user_id TEXT PRIMARY KEY, network_id, device_id, updated_at, status)`；`status ∈ bound/unbound` 是墓碑，供 relay-ws 升级门读（`store.rs:2099-2114` 建表、`:2114-2127` 加 `status` 列并回填、`routes.rs:4692-4706`）。同一设备可属于多个网络，但**只有那一行指向的设备**算"主机" |
| 3 | console-ticket 有「账号持有目标主机」校验 | **真** | `link-server/src/routes.rs:3274-3286`：`get_host_binding(user)` 且 `b.device_id == req.device_id`，否则 `403 reason=not_bound`；`:3288-3299` 隧道不在则 `404 host_offline`；`:3300-3306` `429 rate_limited`；`:3330-3365` `409 link_unstable` |
| 4 | 6 位码的服务端收码面在契约里还是 PLACEHOLDER | **真，且比文档更糟** | 契约 `src/test/resources/neblink/portal-wire-contract-v1.3.md:467-480`：§3.3 "Migration orchestration endpoint - PLACEHOLDER"，明写含 confirmation-code 提交面的整条带外确认链 "deferred to the phase 5 contract freeze"；§7 端点清单（`:535-541`）**根本没有 `POST /api/portal/bind-host`**。实现侧：`link-server/src/store.rs:3874-3889` 注释逐字自认 "The confirm arm is production-unusable in this window … the portal entry therefore always fails `code_mismatch`"，且 `stage_bind_confirmation`（`:3887`）在 HEAD 里**零非测试调用者** ⇒ 服务端从未把 CLI 铸的码落成 pending |
| 5 | 铸码路由明写 BearerFileOnly / never mintable by a browser session | **真（逐字）** | `src/main/scala/nebflow/gateway/NeblinkRoutes.scala:670-674` 注释含 `🔴 Auth rail = BearerFileOnly` 与 `never mintable by a browser session`；`GatewayAuth.scala:45,78` `BearerFileOnly = Rails(session=false, bearerFile=true, pat=false)`；`AuthAdminRoutes.scala:13-27,44` 破玻璃路由同轨 |
| 6 | bind_confirm 被丢弃 + "printed code can never match" | **真（机制描述要改）** | 原文逐字：`src/main/scala/nebflow/neblink/NeblinkRelayTunnel.scala:1103-1112`、`src/main/scala/nebflow/cli/BindCommand.scala:22`。但服务端**不是"丢弃后关隧道"**：`link-server/src/relay.rs:119` `enum ClientToServer` 只认 RelayResponse/Ping/Pong/Ack，无 `bind_confirm`；`portal_wire.rs:506-533` 未知 type ⇒ `Probe::Legacy`；`relay.rs:1860-1897` 分流到 legacy；`relay.rs:1453-1455` `tracing::warn!("relay ws parse: {e}")` 后返回 `true` **继续收帧**（连接保活） |
| 7 | 码的语义（transfer vs 注册） | **对（语义），用法被说反** | 码 = bound→bound **席位换绑**确认码：`link-server/src/routes.rs:2970` `bind_host` 全链 → `:3077` `d6_guard_refuse_or_admit`；首次绑定 / 同席位重绑**不需要码**（`routes.rs:3017-3028`：只有 `transfer` 为真才进 D6 guard）。码从不用于"把某设备注册成主机" |
| 8 | 72h 免码 | **真（常数即 72×3600）** | `link-server/src/routes.rs:82` `D6_REBIND_GRACE_DEFAULT_SECS: u64 = 72 * 3600`，env `NEBLINK_D6_REBIND_GRACE_SECS`（`:81,86-91`）；判定 `:3099-3101` `grace_eligible = !old_online && grace_secs > 0 && last_seen_age > grace_secs`（旧主机当前不在隧道且非 alive/stale，且心跳距今超 grace ⇒ 免码放行，`:3102-3114` 留审计行）；Stand 侧同语义文案 `BindCommand.scala:29,82,91`、`gateway/BindConfirmStore.scala:20-21,79`（主机侧 pending TTL 900s）|
| 9 | `00c44b24`/DESKTOP-V7EUCHT 属于哪个账号 | **仓内查不到（明说）** | 本仓只有**跨端执行画像** `src/main/scala/nebflow/core/tools/DeviceProfile.scala:22,154-158`（`~/.stand/neblink/device-profiles.json`：cwd/shell/地址/探测时刻，**无账号字段**），其中 `00c44b24…` = DESKTOP-V7EUCHT。间接线索：它进过本机设备花名册（`core/reminders.scala:118-170`，日志 2026-11-01 20:06:08 `Devices changed: +DESKTOP-V7EUCHT (00c44b24) [offline]`）且走 `channel=dropbox` 存在性通道。生产库不在本机（`list_networks`/`get_peers` 均按 `owner_id`/`user_id` 过滤：`link-server/src/store.rs:2549-2580`、`:3172-3212`）⇒ **唯一权威判定**：登录 `https://mystand.space/settings?device=00c44b24-40b0-5f55-beec-9ecb3c749135`，看该设备行是否出现 |
| 10 | 一个账号能有几台主机 | **1 台（结构即上限）** | `link-server/src/store.rs:2105-2114` 主键 `user_id` ⇒ 单席位；代码与配置中**没有"上限台数"常量**（无可调参数），换主机是 upsert 覆盖（`:3716-3752` 返回被替换者做位移传播） |

## 3. 正确的机制说明（人话）

**门户什么时候出现绑定入口**：登录后进 `/settings`（页面内嵌 `PortalHomeCards`，
`stand-website/app/settings/SettingsContent.tsx:10,458`）。其中「我的设备」按**当前账号
自己的网络**列设备（`link-server/src/routes.rs:2826-2900` `portal_status`：`list_networks
WHERE owner_id` → `list_devices by network`，并回 `bound_host` + 在线状态/心跳龄/lanCandidates）：

- 没有任何绑定行 ⇒ 无主机空态，引导「在任意设备上安装并登录即可入网」；
- 有绑定行 ⇒ 该设备行显示「主机」徽标 + 「解绑主机」；**其余每台设备行**显示「绑定为我的主机」；
- 点它 ⇒ `POST /api/portal/bind-host`（门户服务端代理转发，Bearer 不出浏览器）→
  `bind_host` 链：登录态 → 账号在线（M-1 `account_offline`）→ 测试账号禁占 → 网络属主 →
  设备在该网络 → D6 guard → 写绑定行（迁移 + 向旧主机广播位移）。

**三种结果**：首次/同席位重绑 ⇒ 一次成功；换到另一台且旧主机在线 ⇒ `409 confirm_required`
（去旧主机跑 `stand bind confirm` 拿 6 位码，10 分钟内输回）；旧主机离线且心跳超 72h ⇒
免码放行；**旧主机离线但不足 72h** ⇒ `409 grace_period_active`（最常见的"卡住"）。

**作者这台 Mac（`6a64d249`）的实际状态**：已在网络 `ad71113b-…` 内；`~/.stand/neblink/
binding-state.json` 存在且 `status=bound`（2026-10-13 写入）；且今日 10:53:46 有一条
`console ticket redeemed: session established (accountSub=63mdczgnd8gy, origin=relay-mint, …)`
——**relay-mint 兑换的第二步就是"账号绑定行必须指向本机"，所以服务端此刻认定它就是主机**。
隧道今日多次重连正常。真正的阻塞点不是绑定，而是 **Logto 登录态**：`~/.stand/neblink/
device.json` 的 `idToken.exp` 停在 2026-10-31 01:25:42、`refreshToken` 为空 ⇒ 门户侧需重新
登录；本机本地控制台靠破玻璃 `local-ticket` 仍可开（日志 12:02–12:08 那批 `origin=local-ticket`
就是它，**不校验绑定**，不能当绑定证据）。

**让某台设备成为主机，可行与不可行**：

| 路径 | 可行 | 条件 |
|---|---|---|
| 门户 `/settings` 点「绑定为我的主机」 | ✅ | 该设备已登录并入同一账号的网络；首次绑定免码 |
| 换绑：旧主机在线 → 旧主机 `stand bind confirm` 出码 → 门户输入 | ⚠️ 机制在、**生产不可用** | 码 10 分钟有效；但服务端收码面未接线（§2 第 4 条）⇒ 必然 `code_mismatch` |
| 旧主机离线超 72h 自动放行 | ✅ | 旧主机不连隧道且心跳龄 > 72h |
| 用 6 位码"把本机注册成主机" | ❌ | 码只用于换绑；且收码面未接线 |
| 浏览器里直接调铸码路由 | ❌ | BearerFileOnly，`never mintable by a browser session` |

## 4. 纠正三条事实错误，并推断那份分析跑在哪台机器

| 断言 | 实测真值 | 依据 |
|---|---|---|
| 「现在跑的是 PID 20332 / 18692 不存在」 | **PID 30450**，`lstart 2026-11-01 13:23:19`（已跑 22h+，未重启） | `lsof -iTCP:6138 -sTCP:LISTEN`；`ps -p 30450 -o lstart`；`~/.stand/.pid.meta` = `{pid:30450, startedBy:"sbt", mode:"foreground", startedAt:"2026-11-01T05:23:19.702Z"}`。`~/.stand` 全日志里 **18692/20332 零命中** |
| 「`binding-state.json` 不存在」 | **存在**，`{"status":"bound","updatedAt":1791861697174}`（= 2026-10-13 11:21:37） | `cat ~/.stand/neblink/binding-state.json`；语义见 `src/main/scala/nebflow/neblink/PortalBindingState.scala:24-116`（**缺文件也读作 bound**，"不存在"连推理方向都不成立） |
| 「本机是主机（`00c44b24` 已入网…）」 | `00c44b24` = **DESKTOP-V7EUCHT**；本机是 `6a64d249-e526-52b1-b5b6-3b19ea029b26` / `Mashiros-MacBook-Pro-iso-a659864a` | `~/.stand/device.json`；`~/.stand/neblink/device-profiles.json` 里 `00c44b24…` 的 cwd 是 `D:/workspace@zcode/nebflow/nebflow-stand`（Windows）、地址 `100.65.0.160` 等 |

**它跑在哪台**（推断，非结论）：几乎可确定是 **KAI（`40742cd4`，数据根 `C:\Users\Kai\.stand`，
网关 pid 14528）**。理由三条同时成立且只在它身上成立：① 其 `neblink/binding-state.json`
**真的不存在**；② 其 `neblink/device.json` 正是 `refreshToken: ""` + idToken 早过期（与文中
"本机 idToken 过期"吻合）；③ 它把 `00c44b24` 当"本机"，而该 id 恰在共享的
`device-profiles.json`（跨端画像会被读成"我的设备"）。它的概念混淆都由此起点产生：把
**跨端执行画像**当成了**账号归属**，把**花名册里见过的设备**当成了**自己**。
注意：它自己可能都没意识到在 KAI 上——idToken「11-01 13:25Z 过期」在两台机器上都对不上
（本机今日首连 10:54:20；KAI 日志 GBK 乱码且无该行），疑为旧配置残留。

## 5. 治理建议（只出清单，本批不改文档）

**问题**：历史批次把「PID + 端口 + 红线」写进报告正文当锚点，PID 一变整批证据失锚，
后来者只能靠"猜测进程"复核——这正是这次两边读数打架的成因。

**清点口径**：`grep -rEni "pid[^0-9]{0,12}[0-9]{4,6}.*(红线|在产|零触碰|未动)|(红线|在产|零触碰|未动).*pid[^0-9]{0,12}[0-9]{4,6}" docs reports design`

**清点结果**：命中 **32 行 / 27 个文件，全部在 `reports/`（`docs/` 与 `design/` 下 0 处）**。

| PID | 行数 |
|---|---|
| 70481 | 16 |
| 72160 | 8 |
| 97486 | 2 |
| 88102 | 2 |
| 70153 / 51211 / 49177 / 15844 | 各 1 |

按批次日期：`20261020` ×3、`20261025` ×2、`20261026` ×10、`20261028` ×16、`20261029` ×1。
（更宽口径 `pid+4~6 位数字`：`reports/` 69 行、`docs/` 22 行、`design/` 1 行——这些是引用而非红线断言，
不在本轮置换范围。）

**第二类失效锚（端口→pid 常数表）**：`docs/parallel-session-resources.zh-CN.md:22,43-76`，
带 pid 的行 10 处：`:43` 8080/93752、`:44` 8090/69473、`:46` 8099/97915、`:57` 8106/22298、
`:58` 8108/60237、`:59` 8109/13964、`:73` 3000/93993、`:74` 3030/93946、`:76` 8765/93882，
另有 `:22` 的 pid 11347（历史事件叙述）。见 [[parallel-session-resources.zh-CN]]。

**替代模板（锚端口 + 校验启动时间/命令行，而非锚 PID）**：

```
在产实例快照 2026-11-02 12:05 CST
- 端口 :6138 LISTEN → pid <P>（lsof 现读，非历史值）
- 验身四件套：lstart=<T> ／ cwd=<repo> ／ command=<java … -Dstand.home=…> ／ 父链=<…>
- 与 ~/.stand/.pid.meta 对齐：pid=<P>, startedAt=<T'>, startedBy=<sbt|launcher>, mode=<foreground|daemon>
- 断言：lstart 与 .pid.meta.startedAt 差 ≤ 10 min，且命令行含该 stand.home 与端口
- 对端机器锚「设备名 + 数据根路径」而非 pid（例：KAI / C:\Users\Kai\.stand / :6138）
```

四件套取自 [[production-ops-checklist.zh-CN]] B1（kill 前验身）；B3「非目标端口零触碰」同样
应改写为端口 + 用途 + 校验方式，不写 pid。**本批不改**：这 32 处属历史报告的过程记录，
批量改写会污染当时读数，建议按批次归档时统一置换为上面的模板。

## 6. 顺带核出的真 bug / 漂移（只报告，未动手）

1. **契约 §7 端点清单缺 `POST/DELETE /api/portal/bind-host`**（`portal-wire-contract-v1.3.md:535-541`），
   而 `bind-host/route.ts:19-21` 自认 "this endpoint is NOT in the portal-wire contract's §7
   endpoint list, so the request shape is owned by the server's routes/model code"——门户↔中继这条面无契约。
2. **D6 确认码链路在生产无入口**：`stage_bind_confirmation` 零非测试调用者（`store.rs:3887`），
   用户侧表现为输对码仍 `code_mismatch`（注释自认，`:3882-3885`）。
3. **绑定入口的路由落差**：本机 UI「打开控制台」指向 `brand.conf portalUrl =
   https://mystand.space/portal`（`PortalUrl.scala:21,32-66`），而绑定面在 `/settings`
   ⇒ 用户被送到 `/portal` 后看不到任何绑定入口，这是"网页上没有绑定主机"观感的主因；
   建议在 `/portal` 无主机空态加一条指向 `/settings` 的管理深链。
4. **`binding-state.json` 停在 2026-10-13 未更新**但 relay-mint 仍在工作：与「缺文件=bound /
   `clearTerminal` 才写 bound」的解释一致，建议下一批加一行诊断日志确认而非靠推断。

## 7. 校验与产出

- 证据：`evidence/20261102_host-binding-audit/README.md`（生产 curl / 本机 / 远端读数）、
  `key-excerpts.md`（12 组路径:行号原文）。
- 生产实测（只读、无凭据）：`/settings` + `Accept-Language: zh-CN` ⇒ 200 且 SSR 含
  「绑定为我的主机」「解绑主机」「输入旧主机上显示的 6 位确认码」；`/portal` 匿名 ⇒ 302
  `/login?next=%2Fportal`；`/api/portal/{status,console-ticket,bind-host}` 匿名 ⇒ 401
  `{"error":"unauthorized"}`（代理存在，门前拦）。
- 本批**未改任何产品代码**，仅新增本文与证据文件。

**校验记录**：`docs/` 在 `.gitignore:30`，故先 `git archive HEAD docs | tar -x -C /tmp/vaultchk`
（再补入本文）后跑 `check_wiki_docs.py --root /tmp/vaultchk/docs`：

```
wiki-docs 校验：通过（/tmp/vaultchk/docs，<N> 篇文档，零违规）
EXIT=0
```

（`<N>` 随该次 archive 快照的文档数浮动——最后一跑为 156 篇；关键是**零违规**与 exit 0。）

工作区直跑会报既有 orphan（工作区 `docs/` 含未入仓文件），故以 archive 快照为准。
