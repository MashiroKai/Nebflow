# 独立窗口内容物收口批 · 交付报告（2026-10-22）

作者令（逐字，2026-10-22 18:54）：「现在独立窗口打开，打开的是整个stand，而不是一个具体的文档，仅允许文档、文件等这些非功能性面板的可独立打开吧」

分支：`delegate/delegate-nebflow-3a877cec`（候合并 main）。基线：卡窗白屏批 `b73159a5a`。

---

## 一、考古：独立窗口全部入口与当前打开目标

全仓 `window.open` 审计 + 独立窗链考古，向应用自身开窗的入口**只有 canvas 卡窗一族**：

| # | 入口 | 面 | 代码位 | 当前打开目标 | 内容物门（收口前） |
|---|---|---|---|---|---|
| 1 | 卡头胶囊「独立/合并」钮 `#canvas-pop-btn` | 桌面 canvas 胶囊 | canvas.js `syncCardHeaderPop` → `toggleCardWindow` | `window.open('/?card-window=1&path=…&title=…', winId, 'popup=yes,780×680')` | `entry.absPath`（非文件页签 hidden——但 hidden 档被族内 display 规则压过，**实际仍渲染**，见修复③） |
| 2 | 叠卡环卡 pop 钮 `.deck-card-pop` | deck 环卡 | canvas.js 环卡建购 | 同上 | `entry.absPath`（钮不建） |
| 3 | 右键菜单「在独立窗口打开」 | 页签条/胶囊/环名条 | canvas.js `showCanvasRefMenu` + `capsuleMenuActions` | 同上 | `cardCanPop`（非文件页签**静默省略**菜单项） |
| 4 | 手机卡头「独立」钮 `.mobile-card-pop` | 手机卡片层 | navController.js `syncCardChrome` → `mobileCardToggle` | `window.open(url, winId)` 新标签页姿态 | `cardCanPop`（hidden——同①的 hidden 失效问题） |
| 5 | `/index.html` 路由别名 | 服务端 | WebSocketRoutes.scala `GET/HEAD /index.html`（白屏批） | 200 直出全量应用（query 原样）——深链兜底面，非打开链目标 | ——（别名自身是第二应用 URL） |

**非入口澄清**：`chatSearchFloat.js` = 页内 DOM 浮窗（非浏览器窗，无独立窗面）；activityBar/neblink 的 `window.open` = OAuth 外链；daemons = localhost 端口链接；chatSearch = 消息内外链。

## 二、根因（「打开的是整个 stand」的可见面）

入口门（absPath）本身没破——破在**呈现时序**：`card-window` 模式类由 canvas.js `initCardWindowMode` 落，位于 main.js 模块图加载完成后（~L3612）。冷缓存/慢隧道下，弹窗从首帧 HTML 到模块图执行完之间渲染的是**未隐藏的全功能壳**（活动栏/侧栏/聊天/输入框 = 整个 stand），数秒后才塌缩成单卡；模块图在该行之前崩溃则永久全功能 UI。次因：`/index.html` 别名是第二个应用 URL（别名上可驻留独立窗深链）。

## 三、修复面（五件，全部前端——刷新即生效，零重启依赖）

| # | 文件 | 内容 |
|---|---|---|
| 1 | `index.html` | 内联经典脚本 pre-paint 门：URL 带 `card-window=1` ⇒ 首帧前落 `html.card-window-boot`（壳隐藏先于模块图，任何网络/缓存条件下「整个 stand 闪现」不可能出现）；同腿把 `/index.html` 深链 `history.replaceState` 归一到 canonical `/?…`（别名永远只是跳板，不得作为独立窗 URL） |
| 2 | `css/split.css` | ①壳隐藏规则扩 `html.card-window-boot` 键（首帧窗）；②`#canvas-panel`/`#main` 卡窗全幅规则双键化；③**面板恒可见修补**：卡窗态 `opacity:1 !important`——基态 opacity:0（画板关闭态）+ 入场动画无 fill ⇒ 无 path 弹出腿（永不 openCanvas）引导面/失败卡整体不可见（本批 spec 首次暴露的潜在缺陷）；④`hidden` 权威化：`.canvas-pop-btn[hidden]`/`.mobile-card-pop[hidden]` ⇒ `display:none !important`（族内 display 规则压过 UA [hidden] 档，面板页签的独立钮此前 hidden 置了仍渲染）；⑤右键菜单禁用态样式 `.disabled`（muted 面禁 hover） |
| 3 | `js/canvas.js` | ①弹出链终拒收口：`popoutCardWindow` 内容物门（absPath 单点判定 = 面板/url/flow-run 页签皆无 absPath）+ 拒绝腿 toast 禁用态提示——桌面/环卡/右键/手机全部入口的最终弹出腿，无视入口形态；②右键菜单不再静默省略：非内容物页签恒渲染「在独立窗口打开」**禁用态项**（class disabled + aria-disabled + title 提示 + 点击 toast），边界可见；③无 path 卡窗深链即刻失败卡（不等 12s 超时），专用文案，壳保持隐藏 |
| 4 | locales zh/en | `canvas.cardwinContentOnly`（仅文档、文件等内容物可独立窗口打开，功能面板不支持）+ `canvas.cardwinNoPath`（未携带内容路径）双语 |
| 5 | spec + shots | 新增 `tests/cardwin-content-only.spec.mjs`（23 钉）+ `tests/cardwin-content-shots.mjs`（亮暗×6 证据） |

**卡窗链可用性保持**：`/?card-window=1&path=…` 弹出→readFile→真容→引导面即撤，全链原样（spec C1 族 + 白屏批 S1 + ⑧批 CW1-6 全绿复跑）。

## 四、验证

- **新 spec** `tests/cardwin-content-only.spec.mjs` **23 PASS / 0 FAIL**：
  - C1 内容物入口正常打开：打开链 `/?card-window=1`（pathname=`/`）、**首帧即卡面**（html.card-window-boot pre-paint 在挂）、真容渲染、引导面即撤、全功能 UI 五面 display:none、双窗零 JS 错；
  - P1 功能面板无独立窗入口（plugins 页签）：卡头 pop 钮 hidden+display:none、环卡不建 pop 钮、右键菜单禁用态项（title 带提示）、点击禁用项 = toast + **零弹窗**；
  - P2 别名不得作为独立窗 URL：`/index.html?card-window=1…` 落地即归一 pathname=`/`（query 原样），落点 = 内容物真容；
  - P3 无 path 深链（亮暗双腿）：即刻失败卡（<3s）+ 专用文案 + 壳隐藏 + 零 JS 错。
- **邻族回归**：`canvas-card-window` 22/22、`canvas-cardhead-capsule` 28/28、`canvas-cardwin-shots` 三端重生成（popup closed=true）、`cardwin-whitepage-shots` 重生成（after content ok）。`cardwin-session-scenarios` 9 pass/2 fail——S2 两腿（无会话 relay 登录引导导航）**干净 HEAD 逐位同败**（git stash 对照实证，本批未触碰 ws.js/登录链，既有漂移非本批）；`msg-search-v3-smoke` TimeoutError 同为 HEAD 逐位同败（环境级，先例：pop-artifacts P7、mobile-p8 判例）。
- **checkJs**：254 < 基线 257（-3），零新增；`check-circular` 与 HEAD 逐字节同。
- **截图**：`evidence/20261022_cardwin-content-only/shots/` 亮暗×{内容物真容、面板入口禁用态、无 path 失败卡} 6 拍。

## 五、生效口径

纯前端批：`/` 与 `/index.html` 双入口刷新即生效，无引擎重启依赖。服务端别名仍为 200 兜底（深链由内联门归一）；可选的服务端 302 化（Location `/?<query>`，别名彻底非应用 URL）留作后续低优先项——需重启窗，本批不依赖。
