# 控制台冷启动加载体验设计（启动状态卡 / placeholder · 只设计不实施）

批：设计批 · 控制台启动加载体验 · 2026-10-19 · worktree delegate-nebflow-cae4c10b
作者令：「直接用 sbt run 连接客户端的时候还是有卡片显示当前状态的，我们目前是加载又慢又没有卡片或者 placeholder 之类的东西来帮助缓解」
交付：本文档 + 交互样张 `20261019_171617_coldstart-loading-card-mockup__delegate-nebflow-cae4c10b.html`（自包含，双开即演示）。零代码改动。

---

## 1. 现状盘点

### 1.1 当年的「启动状态卡」长什么样（git 考古）

仓库可见历史起点 = 2026-04-28 前端拆分批 `7bcb3b173` / `55bf1876a`（更早的单体 index.html 不在可达树内）。当时的壳（`src/main/resources/web/index.html` + `style.css` + `app.js`）有两件状态显示物：

| 物件 | 形态 | 位置（当年 file:line） | 行为 |
|---|---|---|---|
| `#conn` 连接圆点 | header 标题旁 8px 圆点，绿=已连 / 红=断 | index.html:9（`<div class="conn off" id="conn">`）；style.css:22-26（`.conn` 绿 `#4caf50` / `.conn.off` 红 `#f44336`）；app.js:714-722（`onopen` 摘 `off`；`onclose` 挂 `off` + 2s 重连） | 页面打开即见红点→握手成功变绿；sbt run 直连年代的「当前状态卡」主体 |
| `#status-wrap` 忙碌条 | 输入框上方黑色椭圆胶囊：lottie spinner + `#status-text` | index.html:13-16；app.js:40-42、373-381（`setStatus`/`clearStatus` + spinner play/stop） | 会话级 busy 指示（启动期也借道：WS 未开时输入禁用 + 状态条可见） |

### 1.2 哪批没了

| 时点 | commit | 事件 |
|---|---|---|
| 2026-07-23 | `713af07e9` / `fd94dbf75`（checkpoint: preserve uncommitted changes before merge） | 侧栏布局大改写：**`#conn` 圆点与旧 `#status-wrap` 一并删除，无替代**（`id="conn"` 的 -S 首尾对：55bf1876a 加 → 713af07e9 删） |
| 2026-07-24 | `9fec14d19`（revert main margin + html-level init lock） | 引入 `html.ui-init` init lock——至今唯一的「boot 态」信号，但只抑制 transition（index.html:75 添加；main.js:3539-3543 双 rAF 移除），**用户不可见** |
| 2026-08-09 | `2d83de059` / `b39e4db57`（feat(web): remove status-wrap black oval bar above input） | 七月重写中复活的 `#status-wrap`（index.html 4 行 + chat.css 23 行 + main.js/sidebar.js/delegatePopup.js/flowAgentPopup.js 的 setStatus 挂钩共 36 行）**整体摘除**——启动期最后一次有「活着的加载指示」的时点 |

此后至今：冷启动全程白屏/死壳无反馈，作者体感根因成立。

### 1.3 现在的启动序列与可挂状态钩子

现状冷启动时间线（慢腿=手机远程/relay 隧道）：

```
[T0] HTML 解析 ── head 15 个 CSS（sapphire 压轴）── 首帧 = 完整应用壳（空侧栏+空聊天）
     │  head classic script ×2：反递归门(index.html:4-13)、侧栏预绘制恢复(index.html:174-199)
     │  body 尾同步 vendor 脚本 ×6 ≈1.1MB（lucide 348K / lottie 300K / katex 272K /
     │  highlight 124K / qrcode 60K / marked 36K）+ main.js 模块图 —— 慢腿上显著拉长
[T1] main.js 模块体执行 → connect()（main.js:4035）
[T2] connect()（ws.js:568）: cookie 对账 → authcheck 探针（GET /api/nf-authcheck，
     3s 超时，ws.js:406-424）→ WS 握手（ws.js:615-617）
[T3] WS onopen（ws.js:625-662）: 引导命令族 getSkills/getTeams/getConfig/
     getToolsList/getModelOptions… + 心跳武装
[T4] sessionList 到达（main.js:1542-1582）→ renderSessionSidebar + auto-restore
     （resetChatForActiveSession → getHistory，sidebar.js:2128）
[T5] historyPage 到达（main.js:1587+）→ restoreFromBackendHistory 首帧历史渲染
[T6] ui-init 摘除 —— ⚠️ 实际发生在 main.js:3539（模块体），早于 T1 connect()，
     只覆盖布局初始化，与网络无关
```

前端现成可挂状态的钩子（零引擎改动、纯前端可落）：

| 挂点 | 现有锚 | 状态语义 |
|---|---|---|
| A 卡片挂载 | index.html `<body>` 首子元素静态标记 + head 内联 CSS | 首帧即卡（T0 前）——这是冷启动卡本体 |
| B 连接阶段 | ws.js `connect()` 入口 / authcheck settled / `new WebSocket` 前 | 「连接中」——authcheck 与握手前端不可区分，计一段 |
| C 已连接·加载资源 | ws.js `onopen`（ws.js:625） | 「已连接，正在加载资源」 |
| D 恢复会话 | main.js `sessionList` handler（main.js:1542）→ auto-restore | 「正在恢复会话」 |
| E 完成·淡出 | main.js `historyPage` handler 首帧（main.js:1587 `isInitialLoad` 分支，:1599-1619） | 渲染完成 → 卡淡出 |
| F 失败面 | ws.js `onclose` 未开腿（ws.js:669-684 remint/ticketEntry 登录门） | 卡让位给登录门（开放问题 Q3） |
| G 超慢判定 | authcheck 耗时（唯一 boot 期网络读数）+ 卡内计时器 | 慢腿降级文案 |

现有同类视觉语言（设计要复用、不新造）：
- 连接态唯一现存活面：`send-btn.disconnected` + `task-list.task-ws-down`（ws.js:121-127 `syncSendButtonConnState`）——启动期用户看不到。
- `history-loader`（main/historyIndicators.js:6-17 + chat.css:740-756）：14px CSS spinner + 12px 灰字「加载中...」——卡内阶段行 spinner 的同族语言。
- 顶部横幅族：`notificationBanner` / sessionGate 横幅（244c132dc）——卡的非全屏降级形态参照。
- 品牌 logo slot：`/brand/stand/illustrator/svg/stand-symbol-black.svg` / `stand-symbol-white.svg`（index.html:97-98 亮暗 `<picture>` 现成对）；`window.__BRAND__.logoUrl/logoDarkUrl` 注入覆盖（index.html:103 同款 classic script 模式）。

---

## 2. 交互样张设计

样张文件：`reports/20261019_171617_coldstart-loading-card-mockup__delegate-nebflow-cae4c10b.html`（自包含双主题、375px 档、阶段自动步进演示、慢腿态、完成淡出、skeleton 对比形态）。

### 2.1 形态结论：全屏冷启动卡（推荐）+ 侧栏局部 skeleton（可选辅助，候拍 Q5）

- **全屏冷启动卡（主形态，推荐）**：冷启动期间整个应用尚无任何可用信息（侧栏空、聊天空、会话未知），局部 skeleton 无处依附；全屏卡是唯一不撒谎的形态，且天然承载慢腿降级文案与失败让位。视觉走 sapphire 玻璃语言，居中卡片 + 页面底色。
- **局部 skeleton（辅助，可不做）**：只在「卡淡出后、侧栏会话列表未到」的窄窗口有价值；聊天区内容形状不可预知，做骨架必撒谎 → 不做聊天区 skeleton，侧栏骨架做成可选项候拍。
- **中途断线不复用本卡**：作者令只提启动；断线现有 send-btn 红点 + 重连循环，另案。

### 2.2 启动卡结构（阶段步进，不做假进度）

```
┌──────────────────────────────┐
│        [stand-symbol]        │   品牌 logo（亮暗 picture 对，__BRAND__ 注入同款覆盖）
│         Nebflow 控制台        │   标题（__BRAND__.name 槽；classic script 读，不依赖 i18n 模块）
│                              │
│  ● 正在启动                   │   阶段行（步进清单，非进度条）
│  ◌ 连接服务器…                │   ← 当前阶段：spinner 行
│  ○ 加载资源                   │   ← 未来阶段：dim
│  ○ 恢复会话                   │
│                              │
│  （>4s）远程连接较慢，仍在加载… │   慢腿降级文案（单行淡入，不换布局）
│  （>12s）仍无响应？检查网络或刷新 │   极慢档
└──────────────────────────────┘
```

阶段映射（前端可观测事件，诚实步进）：

| 步 | 标签 | 置位事件 | 摘除事件 |
|---|---|---|---|
| 1 启动 | 正在启动 | 卡随首帧出现 | main.js 模块体起跑（connect() 调用前） |
| 2 连接 | 连接服务器 | connect() 进入（ws.js:568，authcheck+握手合计一段——前端不可分，见 Q2） | ws.js onopen（:625） |
| 3 资源 | 加载资源 | onopen | sessionList 到达（main.js:1542） |
| 4 会话 | 恢复会话 | sessionList → auto-restore（restoreTabs 并行不计入） | 首帧 historyPage 渲染完成（main.js:1599 `isInitialLoad` 分支尾）→ **卡整体淡出（300ms）+ display:none，min-display 1.2s 防闪** |

诚实性纪律：**步进清单 = 已观测事件序列，不是进度条**——每步置位有真实帧/回调锚点，绝不估算百分比；某步卡住 = spinner 停在该步，用户看到的永远是真的。

慢腿降级（卡内计时器，纯前端）：
- T+4s 仍未完成当前步：淡入「远程连接较慢，仍在加载…」（12px muted，阶段行下方单行）。
- T+12s：换「连接较慢——请检查网络，或刷新页面重试」。不自动刷新（remint 自愈腿 ws.js:676 已有自己的 reload 节流，不叠加）。
- 步进 spinner 保持转动（「活着」信号），文案只降级不恐吓。

### 2.3 主题 / 移动档 / 让位规则

- **双主题**：纯 `@media (prefers-color-scheme: dark)`，token 全取 base.css 现值（亮 `--color-bg #f5f6f8` / 暗 `#13161c`；文字 `#1b1e26`/`#e8eaed`；muted `#8b8e96`/`#7b7e88`）；卡底=玻璃（sapphire.css `--glass-*`），边=`--color-border`；logo=`<picture>` 亮暗 SVG 对。零 JS 判主题。
- **375px 档**：`@media (max-width: 768px)`——卡宽 `min(340px, calc(100vw - 48px))`，logo 40px，阶段行 13px，安全区 `env(safe-area-inset-bottom)`；文案不换行截断兜底。手机档 step 清单保持 4 步（纵向空间足够），不缩成一行点。
- **让位规则**：登录门（ticketEntry/remint 跳转）出现 → 卡淡出；authcheck 403 = 停在「连接」步 + 显示登录引导文案（Q3 候拍确认谁接管）。JS 全挂时卡才摘——`noscript` 不处理（现状同理）。
- **不挡性能**：卡为静态 HTML + 内联 CSS + 一个 `window.__nfBoot` 微对象（`stage(i)`/`done()` 两个函数），经典脚本，模块未加载前可用；各挂点一行调用，模块加载失败时卡不消失（永远诚实——卡死了说明应用也死了）。

### 2.4 接线清单（实施批的 file:line 预告，本批零改动）

1. index.html：`<body>` 首子元素插卡标记 + head 内联一段 `<style>`（保证 CSS 阻塞期内即渲染卡）+ 尾部 classic script 定义 `window.__nfBoot`。
2. ws.js:568 `connect()` 入口 `__nfBoot.stage(2)`；ws.js:625 `onopen` `stage(3)`。
3. main.js:1542 `sessionList` handler 首行 `stage(4)`；main.js:1599 `isInitialLoad` 分支尾 `done()`。
4. main.js 模块体 connect() 调用前（main.js:4035 邻位）`stage(2)` 前置的 `stage(1)`→即时过（阶段1仅防御性存在）。
5. i18n：卡文案走 classic script 双语内联（读 `localStorage[brand+'locale']`，与 i18n.js:9-11 同键），或静态中文+英文 tooltip（候拍 Q6）。

---

## 3. 开放问题候拍

| # | 问题 | 选项 |
|---|---|---|
| Q1 | **连接路径质量提示**要不要上卡（「经中继/直连 · 信号一般」）？ | a) 不上——boot 期唯一网络读数是 authcheck 耗时，粗且易误判，违背「不撒谎」纪律；b) 只在 >8s 慢腿文案里带一句「网络较慢」，不做质量判定（样张按 b 画）；c) 后端补一帧 relay/直连判定帧（越零引擎改动界，需另批） |
| Q2 | 阶段颗粒度：authcheck 探针与 WS 握手前端不可分（同在 connect()），3 步够不够？ | a) 4 步里「连接」合并探针+握手（样张现状，推荐）；b) 后端/前端拆两帧（要动 ws.js 时序，收益低） |
| Q3 | 失败让位：403/登录门出现时，卡是淡出还是常驻底衬等登录完成？ | a) 淡出让位（登录门已自带视觉，推荐）；b) 卡降为底衬显示「等待登录…」 |
| Q4 | 中途断线要不要复用卡（现只有 send-btn 红点）？ | 本批默认不做（作者令只提启动）；若做，横幅形态（sessionGate 同族）比全屏卡合适 |
| Q5 | 卡淡出后侧栏要不要补局部 skeleton（会话列表形状可预知）？ | a) 不做，卡兜到 sessionList（最简）；b) 做侧栏 3 行骨架卡（样张含演示态） |
| Q6 | 卡文案语言：classic script 读 localStorage locale 双语内联，还是静态中文？ | a) 读键双语（与 i18n.js:9-11 同键，推荐）；b) 静态中文；c) 静态英文 |

## 4. 本批边界

- 零代码改动：未触碰 src/ 任何文件；样张为 reports/ 自包含 HTML。
- 阶段事件锚点均为既有回调（ws.js onopen/onclose、main.js sessionList/historyPage handler），实施批无需引擎侧配合（Q1c 除外）。
