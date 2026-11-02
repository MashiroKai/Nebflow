---
type: batch
status: done
related: "[[20261102_085500_mobile-two-stage-title__delegate-nebflow-1bae7308]]"
---

# 件五 · 联系人设备更新入口退役（profile-settings 批，2026-11-02）

> 作者令（2026-11-02）：「联系人里的设备更新去掉，现在统一由官网进行统一更新」。
> 范围**仅联系人面**：设置页设备区的「全部更新 / 更新此设备」（fleet 批 2026-11-01）
> 与 `fleetUpdate.js` / `FleetUpdateRoutes.scala` 全部保留、零触碰——作者只说「联系人里」。

## 1. 退役面（`src/main/resources/web/js/contacts.js`）

hotupdate 批 3 · G7 在联系人设备行上的整组更新入口，本批**整组退役**：

| 件 | 原行号 | 处置 |
|---|---|---|
| `deviceUpdateEntry(d)`（`[data-device-update-btn]` 单钮 + `[data-device-update-state]` 行内状态文本） | 544-555 | 删 |
| `triggerDeviceUpdate(d)`（`markDeviceUpdating` + 「300s 受理等待」+ `sendWs remoteUpdate`） | 532-541 | 删 |
| `newClientRequestId()`（`clientRequestId` 幂等键生成，契约 §D.2） | 524-529 | 删 |
| `REMOTE_UPDATE_ACCEPT_TIMEOUT_MS = 300_000` | 521 | 删 |
| `syncDeviceUpdateUI()`（扫 `[data-device-update-state]` 回显四态） | 559-589 | 删 |
| 调用点：面板 `render()` 尾 | 357 | 删 |
| 调用点：`initContacts()` 的 `onDeviceUpdateChange(syncDeviceUpdateUI)` | 1255 | 删 |
| `deviceRow(d)` 尾的 `if (!d.isLocal) for (const node of deviceUpdateEntry(d))` | 491 | 删 |
| 导入面 `getDeviceUpdateState, markDeviceUpdating, markDeviceUpdateTimeout, onDeviceUpdateChange, pendingDeviceUpdateKey`（`neblink.js`）与 `sendWs`（`ws.js`） | 8 / 13 | 删（全部零消费点） |

**零空白占位**：`deviceRow` 行结构回到四件 `icon → name → presence → status`，行尾不留
空 button / 空容器 / 空白 flex 占位（spec 的 E 段逐行钉 `kidCount === 4` 且 `buttons === 0`）。

## 2. 共享 store 的保留判定（`neblink.js`）

`deviceUpdateState` 状态机 + `initNeblink()` 里 `remoteUpdateResult` 的**唯一结果帧处理体**
**保留**：远程更新是服务端既有能力（`WebSocketRoutes.scala` 的 `case "remoteUpdate"`），
本模块退场后它只是「当前零消费者」，不是坏引用。同理保留的导出访问器
`getDeviceUpdateState` / `markDeviceUpdating` / `markDeviceUpdateTimeout` /
`pendingDeviceUpdateKey` / `onDeviceUpdateChange`——宁留共享 store 也不留死调用。
`_rerender`（设置账号块）与既有设置页腿不受影响。

## 3. i18n 键：删 3 留 1（zh-CN / en 对称）

| 键 | 处置 | 依据 |
|---|---|---|
| `neblink.updating` | **删** | 唯一消费者是已退役的 `syncDeviceUpdateUI`；全仓零引用（grep 实证） |
| `neblink.restarting` | **删** | 同上 |
| `neblink.updateTimeout` | **删** | 同上 |
| `neblink.update` | **留** | **仍有引用**：设置页设备区每行 `messages.js:5639` 的 `[data-settings-device-update]` 用它（fleet 批 2026-11-01） |

两文件键行 diff 逐条对称（各 `-3` 行）：zh 2294 → 2291，en 2287 → 2284（既有 zh/en
净差 7 不变，本批不引入新偏差）。删除处留了原位注释说明「为什么删 / 为什么留」。

## 4. CSS：零改动（无孤儿规则）

`.neblink-peer-update-btn` **保留**——设置页每行仍在用（`messages.js:5639`），
`neblink.css:192-206` 与 `sapphire.css:334/354/370` 三处规则继续有效。
`.neblink-ch-btn` / `.neblink-update-inline` / `.neblink-update-status` 与本批无关
（联系人面从未使用，`sapphire.css` 仍与设置面共享），**不动**。本批 CSS diff = 0 行。

## 5. 机械验收读数

```
$ node node_modules/@playwright/test/cli.js test \
    tests/profile-device-update-retire.spec.mjs tests/mobile-topbar.spec.mjs --workers=1
Running 5 tests using 1 worker
[1/5] mobile-topbar.spec.mjs T1 根页顶栏退役
[2/5] mobile-topbar.spec.mjs T2 头像搬家
[3/5] mobile-topbar.spec.mjs T3 个人主页层级
[4/5] profile-device-update-retire.spec.mjs 件五 A-F 联系人设备段零更新面
[5/5] profile-device-update-retire.spec.mjs 件五 G 行激活不回退
  5 passed (6.6s)
```

新 spec `tests/profile-device-update-retire.spec.mjs`（375×812、手机层、`features.friends`
开启）七条判据：A `[data-device-update-btn]` = 0、B `[data-device-update-state]` = 0、
C 含「更新/更新此设备」文本的 button = 0、D 设备段内指向 update/upgrade/官网的
`<a href>` = 0、E 每行四件且零空壳子节点、F 零 console error、G 行点击仍开设备会话窗。

**验红实证**（判红纪律「改前不可满足」）：`RETIRE_WEB_ROOT=<改前树>`（`git archive HEAD`
导出）⇒ A 段红、读数 `expected 0 / received 2`（改前对端两行各挂 1 枚更新钮）。

```
$ node node_modules/@playwright/test/cli.js test tests/profile-device-update-retire.spec.mjs
  RETIRE_WEB_ROOT=/tmp/retire-baseline/...  →  1 failed（[data-device-update-btn] received 2）
```

**陈旧断言同步**：`tests/mobile-device-chat-windows.spec.mjs:137-140`（M1）与 `:224-225`（D1）
原钉「对端行 `[data-device-update-btn]` count=1」，按本批改为全列 0；该 spec 复跑
2 passed。同族复跑：`mobile-topbar.spec.mjs` 3 passed。

**死引用 grep（全空）**：

```
$ grep -rn "deviceUpdateEntry\|triggerDeviceUpdate\|syncDeviceUpdateUI\|newClientRequestId\|REMOTE_UPDATE_ACCEPT_TIMEOUT_MS" src/main/resources/web/js/contacts.js
（空）
$ grep -rn "dataset.deviceUpdateBtn\|dataset.deviceUpdateState" src/main/resources/web/js/
（空 —— 零生产点）
$ grep -rn "neblink\.updating\|neblink\.restarting\|neblink\.updateTimeout" src tests tools scripts
（仅剩三处原位注释说明删除原因；键定义 0 命中）
$ node scripts/build-web.mjs  →  build-web OK（orphans: 0, exemptions: 5），exit 0
```

既有红（非本批引入，基线一致）：`scripts/check-js-types.mjs` 25 errors（名单内
messages.js 7 / perception.js 10 等，contacts.js **不在**名单）；`.nebflow` 外无新增红。

## 6. 与全设备统一更新批（802520434）的重叠申报

**结论：非同一处，并列独立入口；本节点对设置页 = 零触碰。**

| 维度 | 联系人入口（本批退役） | 设置页入口（fleet 批 801520434 起保留） |
|---|---|---|
| DOM 宿主 | `.fm-device-list[data-device-section="1"]` 内 `.fm-device-row`（`contacts.js` `deviceRow`） | `.fm-device-list[data-settings-device-section="1"]` + `.fm-device-fleet-bar`（`messages.js` `settingsDevicesHTML`） |
| 键标记 | `[data-device-update-btn]`（**已删**） | `[data-fleet-open]` 全部更新 + `[data-settings-device-update]` 每行 |
| 触发腿 | `sendWs remoteUpdate` 直发 + 本地面 300s 超时 + 行内四态回显 | `fleetUpdate.js`：`fleetUpdate` / `fleetUpdateRetry` WS 命令 + 调度器快照 + **阻塞式状态卡** |
| 进度面 | 行内 `.neblink-peer-status` 文本（`deviceUpdateState` 现读） | 独立阻塞卡（计划/阶段/失败收口全在卡内，`FleetUpdateRoutes.scala`） |
| 幂等键 | 携带 `clientRequestId`（G8） | 不携带（调度器侧统一编排） |
| 共享 DOM / 共享进度状态 | **零** | **零** |

两入口在改前即为并列独立实现（勘察报告 §⑤ 已实证：无共享 DOM、无共享进度面）；
本批只摘联系人那一支，设置页那一支文件零改动（`messages.js` / `fleetUpdate.js` /
`FleetUpdateRoutes.scala` 三文件本批 diff = 0）。

**口径提醒（交接给下一节点）**：`tests/unifyfix-eight-points.spec.mjs` 的 R1d
（「设置页无『更新/频道』设备键组残留」）在 fleet 批之后即已长红（fleet 给设置行加回了
`.neblink-peer-update-btn`，实测 count=1）——这是**既有红、非本批引入**，本批不改该断言；
若下游批要收口，口径应是「设置页允许 fleet 两键、禁的是 hotupdate 批 3 的频道选择键组
（`.neblink-ch-btn` / `.neblink-ch-cancel`）」，需单独裁定。

## 7. 相关

- 勘察基础：reports/20261102_profile-settings-recon.md（五问实证 + 双描述契约 +
  i18n 基线 2360/2353；纯过程件，不入文档库故不设双括号互链）
- 同族上一批标题改造：[[20261102_085500_mobile-two-stage-title__delegate-nebflow-1bae7308]]
