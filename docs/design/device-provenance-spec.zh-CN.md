# 入站消息来源设备标记 spec（device-provenance 批）

批：入站消息标记来源设备批（2026-10-23，作者微信令）
实施：`nebflow/bridge/DeviceProvenance.scala`（纯函数面）+ `gateway/WebSocketRoutes.scala`
（注入点）+ `gateway/WsDispatch.scala` / `WsSessionChatHandlers.scala`（腿接线）+
`web/js/deviceIdentity.js`（客户端声明面）+ `web/js/input.js` / `errorRecovery.js`
（随帧声明）+ `web/js/sidebar.js`（设置面）。
验证：`src/test/scala/nebflow/bridge/DeviceProvenanceSpec.scala`（标签生成）+
`src/test/scala/nebflow/gateway/DeviceProvenanceInjectionGateSpec.scala`（注入源契约门）+
`tests/device-provenance.spec.mjs`（客户端声明 + 设置面 E2E）。

## 0. 作者令与目标

作者令（2026-10-23 19:40，逐字）：「我们现在能够标记消息是从微信还是飞书来的，
让我们的消息也标记设备，比如手机端，mac，windows，让你能知道是哪台设备来的，
是否为我们设备列表里的设备。」

即来源标记从**渠道级**（微信 weixin-ilink / 飞书 feishu）细化到**设备级**。
每条网页控制台入站消息在模型上下文里携带：

1. **来源设备**——手机/Mac/Windows 端别 + 可读设备名；
2. **登记状态**——该设备是否在平台设备列表（NebLink 花名册 self + peers）里。

## 1. 现状考古（三点的答案）

| 考古点 | 结论 |
| --- | --- |
| 现有渠道标记怎么实现 | `BridgeOrigin(channelId, channelDisplay, chatRef)`（`bridge/BridgePlugin.scala`）：渠道供给纯数据，模板单点 `BridgeOrigin.marker` 渲染 `<system-reminder>以下消息来自微信（社交接口 · 渠道 weixin-ilink）· 发送方：X · 会话：Y</system-reminder>`，**只进模型面**（`ExternalEvent.payload`），会话 `.ui.json` 行与 `bridgeUser` 帧保持裸正文（source-marker relocation 批 2026-10-01 的两 face 分离） |
| 平台设备列表在哪 | NebLink 花名册：`GET /api/neblink/status` 的 `device{id,name,platform,…}`（self = 本机 `~/.nebflow/device.json` 的 `DeviceIdentity`）+ `peers[{deviceId,deviceName,platform,…}]`（`NeblinkService.identity` / `NeblinkService.peers`，Ref 内存读）。前端既有单一读取点 `web/js/deviceRoster.js` |
| web 会话带什么身份 | 什么都不带——控制台消息（typeless / immediateInput 帧）今日**无来源标注**直达 agent（`AgentCommand.UserInput` / `ImmediateInput(content, fromUser)`）；localStorage 全站经 `branding.js key()` 命名空间，无既有 clientId |

## 2. 设计

### 2.1 数据流（三段，各归其主）

```
[客户端声明] web/js/deviceIdentity.js
  clientId（localStorage 持久，跨会话稳定）＋ 用户命名（可选）＋
  绑定花名册设备 id（可选）＋ UA 辅助 platform/browser
        │  随用户消息帧的 device 键上行
        ▼
[网关解析] WebSocketRoutes.deviceProvenanceOf(json)
  ClientDevice.fromFrame 防御解码（无 clientId ⇒ 不标注）
  DeviceProvenance.resolve 对照花名册（boundDeviceId 精确匹配 self/peers）
        │  marker / compose / prependMarker
        ▼
[模型面注入] 两腿：
  · typeless 帧 → ContentBlock.Text(标记块) 前插为 blocks 首块
  · immediateInput 帧 → ImmediateInput text = 标记块 + "\n\n" + 正文
  会话显示面（.ui.json 行、气泡）恒裸正文 —— 两 face 分离原律
```

### 2.2 客户端身份（web/js/deviceIdentity.js）

- `clientId`：`crypto.randomUUID`（退化时间+随机），`key('device_client_id')`
  持久；同一浏览器的多次会话同身份。
- `platform`：UA 判定，值域 `phone / tablet / mac / windows / linux`
  （iPadOS 13+ 桌面模式伪装 Macintosh 由 `maxTouchPoints > 1` 揭穿）；判不出不猜
  （缺席键省略，网关按「未知设备」渲染）。
- `browser`：UA 判定 Chrome / Safari / Edge / Firefox / Opera。
- `name`（用户命名）与 `boundDeviceId`（绑定）由设置面写入
  （`key('device_name')` / `key('device_bound_id')`）。
- `deviceStamp()` = 随帧上行对象；缺席键省略（不发 null）。

### 2.3 花名册解析（DeviceProvenance.resolve）

- **绑定键 = 花名册设备 id**（`boundDeviceId` 精确匹配 self.deviceId 或
  peers[].deviceId）。id 是配对真源；设备名可重名可伪造，**不作匹配键**。
- 命中 ⇒ `ResolvedDevice(花名册登记名, enrolled=true)`——显示名以**登记权威**为准；
- 未绑定 / 未命中（含花名册已消失的陈旧绑定）⇒ `enrolled=false`，显示名 =
  用户命名，缺席给可读默认名 **「未登记-手机 Chrome」**（作者令原文示例形态；
  平台缺席降级「未知设备」，名字恒可读，绝不空槽）；
- neblink 未装配 / 取数失败 ⇒ 空册 ⇒ 一律未登记（保守：读不到名册**永不冒认
  已登记**），且绝不影响消息本身的派发（fail-open）。

### 2.4 标记格式（扩展不重造）

与社交渠道头同一句骨架（`以下消息来自 X（渠道 Y）· 标注段`）、同一对围栏常量
（`BridgeOrigin.MarkerOpen/Close`）：

```
<system-reminder>
以下消息来自网页控制台（渠道 console）· 设备：Mashiros-MacBook-Pro（已登记）
</system-reminder>

<正文>
```

未登记态：`设备：未登记-手机 Chrome（未登记）`。登记状态**恒显式**——「是否为
设备列表里的设备」在标记里自答，不依赖模型对默认名的猜测。渠道 id = `console`，
显示名 = 「网页控制台」。

### 2.5 注入点与 face 纪律

| 腿 | 入口 | 模型面 | 显示面 |
| --- | --- | --- | --- |
| 无 type 帧（输入框 / busy 队列 / 离线补发 / 插件卡 / 重发） | `handleMessage` typeless 支 | `blocks` 首块 = 标记块（`prependMarker`，正文块逐字不动；纯媒体帧标记自成首块） | `.ui.json` 行裸正文（不变） |
| `immediateInput`（队列「立即发送」/输入框直投） | `handleImmediateInput` → `handleUserText` → `dispatchUserText` | `ImmediateInput.text` = 标记块 + 空行 + 正文 | `.ui.json` 行裸正文（不变） |
| CLI `userMessage` / REST `rest-turn` | 同上 | **不带 device 键 ⇒ 不标注，逐字节现状** | 不变 |

`dispatchUserText` 的 `device` 形参带默认值 `None` ⇒ 全部既有调用点（含
WsDispatchCtx 的 eta 展开）零改动可编译。

### 2.6 设置面（web/js/sidebar.js + deviceIdentity.js 挂载函数）

设置「运行时」段新增「消息设备标记」区（复用既有 settings-row / cfg-input /
cfg-select 族样式，零新 CSS）：

- **现状行**：绑定命中 → 花名册登记名（已登记）；否则 用户命名 / 默认名（未登记）；
- **本网页设备命名**：输入 + 保存（空 = 清除回默认名）；
- **绑定到设备列表**：下拉 = 不绑定 + self + peers（`deviceRoster.getDeviceRoster`
  既有 60s 缓存面，零新后端出口）；花名册已消失的绑定值如实回显，不静默丢弃；
- 动态文本一律 `textContent`（XSS 纪律）；提示行说明标记只进模型上下文、
  不显示在会话气泡。

## 3. 验收对照（作者令三端验收的落地面）

- **手机 / Mac / Windows 三端发消息**：三端浏览器各自持独立 clientId；Mac 端
  （引擎本机）可在设置里绑定到 self 设备 → 标注 `设备：Mashiros-MacBook-Pro（已登记）`；
  Windows 端绑定到 peers 里已登记的 Windows 实例 → 登记名标注；手机端不绑定 →
  `设备：未登记-手机 Safari（未登记）`（按实测 UA 派生）。
- **未登记设备被标记**：未绑定/未命中一律 `未登记-…（未登记）`，花名册不可得
  也绝不冒认已登记（§2.3）。
- **注入头正确**：`DeviceProvenanceSpec` 逐字钉模板两态；`DeviceProvenanceInjectionGateSpec`
  钉两 face 分离与块前插（行为读数归联调轮——本批不起栈）。
- **亮暗截图**：可选项——标记为纯模型面，会话 UI 无展示面；设置区为唯一 UI
  面，形态复用既有行样式（本批未截图，联调轮可补）。

## 4. 兼容与红线

- 帧 `device` 键为**加性**：老前端 / CLI / REST 不发 ⇒ 网关 `None` ⇒ 全部 face
  逐字节不变（`ClientDevice.fromFrame` 无 clientId 即 None，不猜）。
- 社交渠道（微信/飞书）面**零触碰**：`BridgeOrigin` 模板逐字未动。
- 构块面（`Some(blocksList).filter(_.nonEmpty)` 语义）不变：标记只在
  content/attachments 非空的帧上生效（空帧准入谓词照旧丢弃）。
- 引擎零重启：纯增量实现，重启窗外热生效需前端刷新 + 网关重启自然携带（本批
  未重启任何进程）。
