# 单实例顶出弹窗四修 批报告（作者令 2026-10-29 15:30，console 渠道）

分支 `delegate/delegate-nebflow-f52794b2`；独立 commit；在产实例零触碰（前端验证全部走
自足静态 harness + playwright-core 隔离端口；后端走 ConsoleTakeoverSpec 真实注册表 +
真实 WsHub 装配语义，不 boot 网关）。作者报障截图：
`/Users/kaiyu/.stand/uploads/d7c3cb54-dd90-4f3f-bbf6-e7898c425cff/408011486529875_截屏2026-10-29 15.26.08.jpg`。

原批：2026-10-27 控制台单实例（`reports/20261027_231456_console-single-instance/DESIGN.md`）。
本批修订其 §7.3「数据透明照旧」口径（作者令 reverse）+ §6 退出面（作者令 replace）。

---

## 一、四件逐条（根因 / 改法 / 证据）

### ① 数据传输真切断（隐私面）

- **根因**：原批设计明确「数据可见性照旧」（DESIGN §7.3：数据面帧照发全部连接，含
  superseded 面）——被顶出的标签页 WS 不断、内容帧照播，后台仍看到最新对话。前端
  `state.consoleSuperseded` 旗标写了没人读（canvas 联动留接线注释未实施）。
- **改法（两级）**：
  - **引擎/API 侧**：`ConsoleTakeover.gateFrame(sessionId, text)`（新）——按会话裁决
    wsHub 广播/定向扇出：owner==本会话 / owner 未定（claim 前引导窗）/ 总闸关 ⇒ 放行；
    **owner==他会话（superseded）⇒ 只放行 takeover/takeoverResult 接管协议帧**，
    对话内容帧（message/textDelta/sessionList…）一律断喂。心跳面不受影响（pong 走
    perConnJsonSend 旁路，不经闸）。装配在 `WebSocketRoutes` 的 hub 注册点：session 轨
    连接逐连接套 `gatedWsSend`（Bearer 轨 None ⇒ 恒放行，零漂移）。热路径零解析：
    owner 命中只一次 Ref 读；仅 superseded 会话付 type 解析代价（稀有面）。retake 翻位后
    owner==本会话 ⇒ 闸自动放开，无需任何显式解锁。
  - **前端兜底**：ws.js `onmessage` 入口闸——`state.consoleSuperseded` 在场时除
    takeover/takeoverResult 外一律不 dispatch（旧引擎/本连接直发帧兜底；放在 pong 之后，
    通道活性水位照常推进）。唯一写点仍是 takeoverModal.js。
- **证据**：`ConsoleTakeoverSpec` **B1–B5 全 PASS**（B5 = 真实 WsHub 装配语义：superseded
  连接广播断喂、sendTo 的 takeover 帧可达、retake 后闸自动放开、反向踢后新 superseded
  连接断喂）；前端 spec **F5 全 PASS**（正对照：未顶出态同帧正常分发渲染；顶出态
  SECRET_MARKER 不分发不渲染；接管面不受闸误收）。

### ② 毛玻璃背景

- **根因**：弹窗只靠 `#modal-overlay`（`--overlay-bg: transparent`）全透明遮罩，背景
  内容清晰可读。
- **改法**：takeoverModal.js 随弹窗开关给 overlay 切 `.takeover-blur`；modal.css 新增
  该态——`backdrop-filter: blur(var(--cv-blur)) saturate(var(--cv-saturate))`（**--cv-*
  玻璃族同源**：24px / 1.15，chat #header 同配方）+ 低 α dim（`rgba(15,18,24,.14)`，唯一
  新字面量，亮暗双档通用；毛玻璃本身即隐私面，dim 只让模糊更可读）+ 0.25s 柔和过渡。
- **证据**：spec F1/F1-dark/F2 断言类在场 + `blur(24px) saturate(1.15)` 计算样式 +
  收面即摘除；截图 `evidence/20261029_153300_takeover-fourfix__delegate-nebflow-f52794b2/
  after/takeover-{light,dark}.png`（整页模糊、内容不可读）。

### ③ 卡片风格（极简 + stand tokens）

- **根因**：卡片沿用 10-27 的 16px 标题 + 14px 正文 + 8×18 实心灰按钮，与
  errorfrozen-dialog-minimal 批（2026-10-29 13:51 作者令「极简，结合 stand 风格」）口径
  不同档。
- **改法**：modal.css 接管模态块重写——15px/600 标题一行 + 13px 中性说明一行 + 操作
  chip 行（**skip-freeze-btn 同族**：无底无框透明，色只落字上，主操作【重新接管】sapphire
  字、次级【退出】中性字，hover 才起淡底，:disabled 明确 inert）；卡体保留玻璃族材质
  （shared selector 不动，半径 20→18、padding 收敛）。亮暗双档零新 token（sapphire/
  color-frame-* 现成面）。
- **证据**：spec F1 断言两按钮 `border 0px + background transparent`；亮暗截图见 ②。

### ④ 两个按钮修真

- **【重新接管】根因**：原实现只有单飞锁 + `ok⇒hide()`，无 loading 态、无失败提示、无
  重同步——被顶出页在内容断喂下 hide 后画面是缺口态（未重同步），且 WS 抖动（后台标签
  被 #960 判死/重连在途）时回执缺席，点击零反馈 = 「点了没用」。
- **【重新接管】改法**：点 = 发 `takeoverRetake`（单飞，disabled+「接管中…」loading）→
  回执 `ok` ⇒ **reload 全量重同步重新进入控制台**（服务端已同时把另一端顶进同款弹窗态；
  闸窗期内容缺口只经 reload 愈合，不做增量续传——单实例语义同刻只有一个可信实例）；
  回执 `!ok`/4s 缺席 ⇒ 恢复可点 + 失败 toast「接管失败，请重试」（挤占竞态：互斥权最后
  写入者胜，败者可再点）。迟到 ok 同样安全（owner 确已回本会话）。
- **【退出】根因**：原实现 = 关 WS + 翻「退出面/重新进入」中间页——不是作者要的出口。
- **【退出】改法**：点 = `window.location.href = getProfileUrl()`——官网个人主页
  （brand 契约注入 `__BRAND__.profileUrl` = `https://mystand.space/profile`，brand.js
  唯一裁决点 + `from=client` 跨仓提示随行，activityBar.js 同源读点；旧网关/静态服务回退
  同 URL）。原退出面/重入门全链退役（DOM 钮、showExitFace、takeoverExited 旗标 +
  ws.js 两处读点、locales 3 键同步移除；state.js 槽文档改口）。
- **证据**：spec **F2**（ok ⇒ reload：WS 重连计数 +1、模态收起、毛玻璃摘除、旗标复位）
  **F2b**（桩吞回执 ⇒ loading 稳定可断言 → 4.3s 后恢复可点 + 文案复位 + 失败 toast +
  模态不收面 → 恢复回执后再点成功）**F3**（initScript 注 `profileUrl=https://profile.test/
  landing` 桩域路由 fulfill ⇒ waitForURL 命中且 `from=client` 随行）。

## 二、验证台账

| 项 | 结果 |
|---|---|
| `ConsoleTakeoverSpec`（A1–A10 原 + **B1–B5 新增**） | **15/15 PASS**（sbt） |
| `tests/console-takeover.spec.mjs`（F1 扩/F2/F2b/F3 改/F4/F5 新，亮暗截图） | **33/33 ALL PASS** |
| `tests/conn-banner.spec.mjs`（ws.js updateConnBanner 触面回归） | ALL PASS |
| `tests/errorfrozen-minimal.spec.mjs`（modal.css 族触面回归） | 39/39 PASS |
| `tests/session-gate.spec.mjs`（ws.js onclose 触面回归） | 23/23 PASS |
| `scripts/build-web.mjs` | OK（193 源全图 orphans 0） |
| `scripts/check-js-types.mjs` | 256 < 基线 260（-4）；本批触及文件零错。唯一 GATE FAIL = canvas.js 2 错 = **既有欠账**（errorfrozen 批报告已申报，本批零触碰） |
| `scripts/check-circular.mjs` | 仅 popArtifacts/reference/msgQuote 旧环（本批新边 takeoverModal→modal.js/brand.js 无环） |

证据目录：`evidence/20261029_153300_takeover-fourfix__delegate-nebflow-f52794b2/after/
{takeover-light.png, takeover-dark.png}`。

## 三、未做 / 开放项

1. **LIVE 双标签 e2e（A/B 实机互踢）未跑**：需要 boot 真网关 + 两条门户会话（ticket
   兑换链），单浏览器 harness 无法表达双 cookie-jar 会话。等价覆盖已就位：B5 用**真实
   WsHub + 真实注册表 + 生产同款 gatedWsSend 装配**钉断喂/可达/翻位全语义；前端 F1–F5
   走真实生产 CSS/JS 静态 harness。建议 QA 在 staging 引擎上过一遍作者原场景（Mac 开 →
   Windows 开 → Mac 弹窗断内容 → Mac 重新接管 → Windows 被顶 → 任一端退出落官网个人页）。
2. **服务端按连接拒写命令**（superseded 页面向引擎发命令的写闸）仍属设计稿 §7.4 的后续
   加固项——本批弹窗 overlay 阻塞 + 入口闸已覆盖作者报的隐私面，写闸爆炸半径大，未动。
3. canvas.js 的 checkJs「new file」欠账待其归属批重锚基线（非本批引入，不代偿）。
4. 直发帧（不经 wsHub 的本连接发送：serverConfig/sessionList 引导、watchSession 事件）
   不在 gateFrame 闸内——它们不含对话内容；如日后要求「superseded 零下行」，在
   perConnJsonSend 同点套 gateFrame 即可（单点扩展，本批留口）。
