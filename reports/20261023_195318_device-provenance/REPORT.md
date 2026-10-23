# REPORT — 入站消息标记来源设备（device-provenance 批）

- 分支：`delegate/delegate-nebflow-cab3b02d`（独立分支交付，交回合并）
- 作者令：2026-10-23 19:40 微信（逐字见 §0）
- 设计件：`docs/design/device-provenance-spec.zh-CN.md`
- 红线遵守：最小改动（8 文件改 + 5 文件新增，全部围绕单一条缝）；未重启引擎；
  未触社交渠道模板；未 commit 到 main。

## 0. 作者令

「我们现在能够标记消息是从微信还是飞书来的，让我们的消息也标记设备，比如手机
端，mac，windows，让你能知道是哪台设备来的，是否为我们设备列表里的设备。」

目标：来源标记从渠道级（微信/飞书）细化到设备级——每条控制台入站消息带
①来源设备（手机/Mac/Windows + 可读设备名）②是否在平台设备列表（已配对登记）。

## 1. 考古结论（三点）

1. **渠道标记参照**：`BridgeOrigin`（`src/main/scala/nebflow/bridge/BridgePlugin.scala`）
   ——渠道供纯数据、模板单点渲染 `<system-reminder>以下消息来自微信（社交接口 ·
   渠道 weixin-ilink）· 发送方：X · 会话：Y</system-reminder>`，只进模型面
   （source-marker relocation 批 2026-10-01 两 face 分离纪律）。
2. **设备列表**：NebLink 花名册——`GET /api/neblink/status` 的
   `device{id,name,platform}`（self，源自 `~/.nebflow/device.json` 的
   `DeviceIdentity`）+ `peers[{deviceId,deviceName,…}]`（`NeblinkService.identity/peers`，
   Ref 内存读）；前端既有单一读取点 `web/js/deviceRoster.js`。
3. **web 会话身份**：此前为零——控制台消息（typeless / immediateInput）无来源
   标注直达 agent；无既有 clientId。

## 2. 实现

数据流（三段各归其主，详见 spec §2）：

```
deviceIdentity.js（clientId 持久 + UA 判定 + 命名/绑定）
  → 帧 device 键 → WebSocketRoutes.deviceProvenanceOf（解码 + 花名册对照）
  → 标记块只进模型面：typeless=blocks 首块前插 / immediateInput=text 前缀
  会话显示面（.ui.json 行、气泡）恒裸正文
```

| 文件 | 变更 |
| --- | --- |
| `nebflow/bridge/DeviceProvenance.scala` **新增** | 纯函数面：`ClientDevice`（帧解码）、`resolve`（花名册解析）、`marker/compose/prependMarker`（渲染）；渠道 `console`/「网页控制台」；复用 `BridgeOrigin` 围栏常量 |
| `gateway/WebSocketRoutes.scala` | `deviceProvenanceOf` + `deviceRosterEntries`（fail-open 空册）；typeless 腿块前插；`dispatchUserText/handleUserText` 增默认形参 `device`（既有调用点零改动） |
| `gateway/WsDispatch.scala` | ctx 注入缝 `deviceProvenanceImpl` + `handleUserTextImpl` 五元化 |
| `gateway/WsSessionChatHandlers.scala` | `handleImmediateInput` 先解析 device 再下发 |
| `web/js/deviceIdentity.js` **新增** | clientId 持久、UA 判 platform/browser、命名/绑定、`deviceStamp()`、设置区挂载 `mountDeviceStampSettings` |
| `web/js/input.js` | 六处真人发送点随帧带 `device: deviceStamp()` |
| `web/js/errorRecovery.js` | 重发腿同律 |
| `web/js/sidebar.js` | 设置「运行时」段挂「消息设备标记」区 |
| `locales/zh-CN.js` / `en.js` | 新增 8 个 `settings.deviceStamp*` 键 |

关键裁定：

- **绑定键 = 花名册设备 id**（id 是配对真源；设备名可重名可伪造不作键）；
- 未登记默认名 = 「未登记-手机 Chrome」（作者令原文示例形态；平台缺席降级
  「未知设备」，名字恒可读）；
- 花名册不可得 ⇒ 一律**未登记**（读不到名册永不冒认已登记），且 fail-open
  不影响消息派发；
- 帧 `device` 键加性：CLI/REST/旧前端不发 ⇒ 逐字节现状；
- 标记登记状态恒显式（已登记/未登记），不依赖模型猜默认名。

## 3. 验证

| 层 | 载体 | 覆盖 |
| --- | --- | --- |
| 标签生成 | `src/test/scala/nebflow/bridge/DeviceProvenanceSpec.scala`（munit，12 例） | 帧解码三态 None 律、平台映射、默认名三例、绑定命中/未命中/陈旧绑定/空册、标记两态逐字、compose 顺序、块前插 |
| 注入面 | `src/test/scala/nebflow/gateway/DeviceProvenanceInjectionGateSpec.scala`（源契约门，先例 UserTextGateSpec） | 两 face 分离（UiMessage 行裸正文 + ImmediateInput compose）、块前插、immediateInput 腿接线、前端 ≥7 处随帧声明 |
| 客户端 + 设置面 | `tests/device-provenance.spec.mjs`（Playwright，假 WS + mock 花名册） | clientId 跨刷新稳定、stamp 形状、typeless 帧端到端带 device、设置区默认名/命名/绑定/解绑/绑定后随帧上行 |

运行结果：

- 后端：`sbt compile` ✅（597 sources，2026-10-23 20:01:20）；`sbt "testOnly
  nebflow.bridge.DeviceProvenanceSpec nebflow.gateway.DeviceProvenanceInjectionGateSpec"`
  ✅ **Passed: Total 19, Failed 0**（DeviceProvenanceSpec 14 例 + 注入门 5 例，
  2026-10-23 21:39:08）。两处既有测试 fake（WsBrowseDeviceLegSpec /
  ChainViewWsEntrySpec 的 WsDispatchCtx null-fake）随 ctx 新字段补一行
  `deviceProvenanceImpl = null`（null-fake 既有风格）。
- 前端：`node …/@playwright/test/cli.js test tests/device-provenance.spec.mjs`
  ✅ **3 passed**（稳定身份跨刷新 / typeless 帧带 device / 设置区命名·绑定·解绑，
  mock 花名册 + 假 WS；存储键经 `branding.js key()` 命名空间断言）。

## 4. 未做 / 未决

- **三端实测**：手机/Windows 真机端到端（真花名册绑定 + 真标记落地）需活的
  nebflow 引擎 + 三端浏览器，联调轮收口；本批以 E2E（桌面 UA）+ 源契约门覆盖。
- **亮暗截图**：可选项——标记为纯模型面（会话气泡零展示，纪律使然），唯一 UI
  面是设置区且全复用既有行样式；联调轮如需可补。
- **UI 泳道**：设置区文案经 i18n 双语键，未跑移动端三档响应式（区块为既有行
  布局，风险低）。
- **协议收口**：`immediateInput` 帧此前无 `clientMessageId` 消费面，本批沿既有
  wire 形状只加 `device` 键，未做命令形状收口（与 resend-dedup 批同一先例注释）。

## 5. 关键假设

- 「设备列表」= NebLink 花名册（self + peers），即 `GET /api/neblink/status` 口径；
  引擎本机浏览器视作**未绑定浏览器**而非自动 self（绑定后才算已登记）——与
  「是否为我们设备列表里的设备」的字面口径一致。
- 标记只进模型上下文、绝不显示在会话气泡（作者 2026-10-01 成文纪律
  「system reminder 永远不显示在会话中」）。
- engine 进程未重启（红线）；新代码在下次引擎重启时生效，前端随刷新生效。
