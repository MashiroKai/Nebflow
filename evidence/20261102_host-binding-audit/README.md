# 2026-11-02 门户「绑定主机」机制核查 — 证据索引

本批以**核查**为主，未改产品代码。全部读数采集于 2026-11-02 11:45–12:10 CST。

代码真源三处（路径相对各自仓根）：
- Stand（本仓）：`src/main/scala/...`、`src/main/resources/...`、`src/test/resources/neblink/portal-wire-contract-v1.3.md`
- link-server（中继服务，`~/.stand/projects/link-server`，HEAD `53448f0`）：`src/routes.rs`、`src/store.rs`、`src/relay.rs`、`src/portal_wire.rs`、`src/main.rs`
- stand-website（门户，`~/.stand/projects/stand-website`，HEAD `b465131`）：`components/home/PortalHomeCards.tsx`、`app/api/portal/bind-host/route.ts`、`lib/portal-state.ts`、`lib/auth-i18n.ts`、`app/settings/SettingsContent.tsx`、`app/portal/PortalContent.tsx`

## A. 生产实测（只读 curl，无凭据）

| 请求 | 读数 |
|---|---|
| `curl -sS -o /dev/null -w '%{http_code}' https://mystand.space/settings -H 'Accept-Language: zh-CN'` | `200` |
| 同请求 SSR HTML grep | 命中「绑定为我的主机」「解绑主机」「我的设备」「输入旧主机上显示的 6 位确认码」 |
| 同请求 `Accept-Language: en` | 命中 `Bind as my host`、`Unbind host`、`Bind as my host` 键族 |
| `curl -sS https://mystand.space/portal` | `302` → `/login?next=%2Fportal`（匿名不可见） |
| `curl -sS https://mystand.space/api/portal/status`（匿名） | `401 {"error":"unauthorized"}` |
| `curl -sS https://mystand.space/api/portal/console-ticket`（匿名） | `401 {"error":"unauthorized"}` |
| `curl -sS -X POST https://mystand.space/api/portal/bind-host`（匿名） | `401 {"error":"unauthorized"}`（**代理存在**，门前拦） |
| 生产 bundle `/_next/static/chunks/12y1a._5d.-vo.js` grep | 命中 `fetch("/api/portal/bind-host"` 与 `r.pairing.bindEntry` |

结论：**门户确有绑定主机面**，但它在登录后、且在 `/settings` 路由；`/portal` 是控制台进入页。

复验（2026-11-02 12:26 CST，同一组只读请求）：`settings_zh=200`（SSR 含「绑定为我的主机」×1、「解绑主机」、
「输入旧主机上显示的 6 位确认码」）；`portal=302 redirect=https://mystand.space/login?next=%2Fportal`；
`bindhost_anon=401`。读数与上文一致，未漂移。

## B. 本机（作者 Mac，deviceId `6a64d249-e526-52b1-b5b6-3b19ea029b26`）读数

| 项 | 读数 | 命令/文件 |
|---|---|---|
| 网关进程 | `lsof -iTCP:6138 -sTCP:LISTEN` ⇒ **PID 30450** | lsof |
| 启动时刻 | `ps -p 30450 -o lstart` ⇒ **2026-11-01 13:23:19**（etime 22h+） | ps |
| PID 台账 | `~/.stand/.pid.meta` = `{pid:30450, startedBy:"sbt", mode:"foreground", startedAt:"2026-11-01T05:23:19.702Z"}` | `.pid.meta` |
| 设备卡 | `~/.stand/device.json` deviceId `6a64d249-e526-52b1-b5b6-3b19ea029b26`，deviceName `Mashiros-MacBook-Pro-iso-a659864a` | `device.json` |
| **绑定墓碑（存在）** | `~/.stand/neblink/binding-state.json` = `{"status":"bound","updatedAt":1791861697174}`（= 2026-10-13 11:21:37） | 文件存在 |
| neblink 身份 | `~/.stand/neblink/device.json`：serverUrl `https://neblink.nebflow.space`、networkId `ad71113b-b480-462f-badf-dfed5632df22`、deviceId `6a64d249-…`、`logto.idToken.exp` = 2026-10-31 01:25:42（**已过期**）、`refreshToken: ""` | `neblink/device.json` |
| 服务端认定=主机（实证） | `~/.stand/logs/stand.log` @ **2026-11-02 10:53:46.116**：`console ticket redeemed: session established (accountSub=63mdczgnd8gy, origin=relay-mint, sid=sMbU6xVZ…, networkId=ad71113b-…)` | 见 key-excerpts §4 |
| 破玻璃（对照） | 同日志 12:02–12:08 多条 `origin=local-ticket`（本机 UI 直开控制台，不走 relay-mint） | 同上 |
| 隧道重连 | `Relay tunnel connected … deviceId=6a64d249…` @ 10:54:20 / 10:57:05 / 10:57:17 / 11:04:56 / 11:04:57 | 同上 |
| 18692 / 20332 | `~/.stand` 全日志与 `.pid.meta` 中**零命中** | grep -rn |

**relay-mint 兑换成功 ⇒ 服务端此刻认定本机是 bound host**（`console_ticket` 第二步就是 `get_host_binding` + `device_id` 相等）。`origin=local-ticket` 是破玻璃豁免路径（不校验绑定），不可用来证明绑定关系——两者必须分开看。

## C. 远端设备只读探测

| 设备 | 读数 |
|---|---|
| **KAI** `40742cd4`（`.stand` = `C:\Users\Kai\.stand`） | `netstat` :6138 LISTEN **pid 14528**；`.pid.meta` `startedAt 2026-10-26T06:38:55Z, startedBy=launcher, mode=daemon`；`device.json` deviceId `40742cd4…`、账号 sub `63mdczgnd8gy`（与本机同账号）；**`neblink/binding-state.json` 不存在**；无 `neblink/config.json`；`neblink/device.json` `refreshToken: ""`、idToken 早过期 |
| **DESKTOP-IJBBEOT** `ad1016bb` | `netstat` :8080 LISTEN pid 9112；`C:\Users\PC\.stand\neblink\device.json` **不存在**（存在 `.nebflow` legacy 目录） |
| **DESKTOP-V7EUCHT** `00c44b24` | 当前 `offline`（fleet 花名册）；画像 `~/.stand/neblink/device-profiles.json` 有记录：cwd `D:/workspace@zcode/nebflow/nebflow-stand`、bash 5.3.9、MINGW64_NT-10.0-26200、地址 100.65.0.160 / 100.88.39.75 / 192.168.10.100:6138、probedAt 2026-11-01 15:10:13 |

## D. 本机日志中 V7EUCHT 的轨迹（判定账号归属的间接证据，非结论）

```
2026-11-01 15:04:48  存在性会话（00c44b24）
2026-11-01 15:09:55  presence sendData: device=00c44b24-40b0-5f55-beec-9ecb3c749135 channel=dropbox
2026-11-01 15:56:xx  画像探测 → device-profiles.json 落盘
2026-11-01 20:06:08  [devices] Devices changed: +DESKTOP-V7EUCHT (00c44b24) [offline]
```

`Devices changed` 由 `src/main/scala/nebflow/core/reminders.scala:118-170` 从 **systemStable 设备花名册**的设备增删算出 ⇒ V7EUCHT 进过本机可见的设备集合。**但 device-profiles.json 本身只是跨端执行画像（`core/tools/DeviceProfile.scala:22,154-158`），不是账号归属证明**；生产库不在本机，仓内查不到权威归属。

## E. 复现命令（只读）

```bash
# 门户面
curl -sS -H 'Accept-Language: zh-CN' https://mystand.space/settings | grep -o '绑定为我的主机' | head -1
curl -sS -o /dev/null -w '%{http_code}\n' https://mystand.space/api/portal/bind-host
# 本机状态
lsof -iTCP:6138 -sTCP:LISTEN -P -n
ps -p <pid> -o lstart=,command=
cat ~/.stand/neblink/binding-state.json
grep -n 'console ticket redeemed' ~/.stand/logs/stand.log | tail -3
```

逐条代码证据见 `key-excerpts.md`。
