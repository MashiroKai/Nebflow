---
type: design-spec
status: draft
title: 悬浮按钮/切换器统一设计稿（回归 stand 自有玻璃语言）
created: 2026-11-01
author: stand (delegate-nebflow-cc28a33e)
mockup: "[[unified-button-mockup.html]]"
related:
  - "[[20261031_084400_liquid-glass-effect-v2__delegate-nebflow-a9ba5eff.zh-CN]]"
  - "[[20261031_121500_liquid-glass-effect-v3__delegate-nebflow-9e155df5.zh-CN]]"
  - "[[20261031_170000_liquid-glass-ab-final__delegate-nebflow-a1031f13]]"
tags: [design, visual, buttons, frosted-glass, tokens]
---

# 悬浮按钮/切换器统一设计稿（回归 stand 自有玻璃语言）

> 只设计不实施。本稿给 stand 控制台一切**悬浮控件**（浮在内容之上的圆钮/切换器/浮条）定一套单一设计语言：**尺寸与样式 = canvas 的 markdown 源码/渲染切换器（基准件 A）；毛玻璃配方 = 主会话 Header + 输入框（基准件 B/C）**。今早的液态玻璃调研结论降级为参考资料，不作主方向（§4）。

## §0 作者令（本稿依据，逐字）

「统一设计风格。按钮的大小和样式统一为我们 canvas 的 markdown 源码/渲染，切换器的样式，然后它的毛玻璃效果，要参考我们主会话的 Header 和输入框的毛玻璃效果。先给我一个样式设计和应用面的设计稿给我。」

## §1 裁定链、范围与概念

**裁定链**：作者令（本稿）⇐ 撤向 = 今早液态玻璃 v2/v3 批（[[20261031_084400_liquid-glass-effect-v2__delegate-nebflow-a9ba5eff.zh-CN]]、[[20261031_121500_liquid-glass-effect-v3__delegate-nebflow-9e155df5.zh-CN]]，A/B 回归证据 [[20261031_170000_liquid-glass-ab-final__delegate-nebflow-a1031f13]]）⇐ 更早 = 2026-10-22 作者令「全站一套毛玻璃」+ 2026-10-28 件三统一批「以聊天窗口控件现用的毛玻璃效果为唯一风格模板」（`sapphire.css:44-49`、`split.css:1877` 注）。本稿不是新方向，是**回到这两道已批旧令的全站化**：那两道令当时只落了 canvas 族，其余悬浮件随后被液态玻璃 v2/v3 的 F1 材质盖掉（`liquid-glass.css:190-221`），现在把它们统一收回基准件档。

**收编范围**（谁进本稿）：一切「浮在内容之上」的控件 —— 圆钮（FAB）、切换器（toggle）、胶囊、浮条（查找条/缩放条/工具条）、浮钮徽标。
**范围外**（谁不进）：① 控件族（发送键/确认键/卡片内按钮等「坐在内容里面」的按钮）——维持 2026-10-31 作者 12:32 拍板的扁平族（`--ctl-*` token，`stand-tokens.css:296-326`），发送键绿色不回翻；② ghost 家族（透底、hover 才显玻璃的行内小钮，如 Header 内记忆键、导航/活动栏钮）——它们不浮在内容上，维持 sapphire Pattern A 现状；③ 主会话 `#header` 本体——零触碰（chat.css 既有裁定）。

**概念速查**（首现即释）：
- **毛玻璃/backdrop-filter**：CSS 属性，对元素**背后**的内容做模糊+饱和处理，元素自身半透明填充 ⇒ 形成磨砂玻璃观感。stand 的配方见 §3.1。
- **token**：CSS 自定义属性（`--xxx`）形式的样式单源；本稿判据 = 一个字面量只允许存在一处，改一处全站生效。
- **F1**：液态玻璃 v3 作者点选的悬浮族配方档（填充 .30 / blur 20 / sat 2.2），现为树上的活覆盖层，本稿将其退役（§4）。
- **no-backdrop 降级**：引擎不支持 `backdrop-filter` 时（旧 WebView/微信内核）挂 `html.no-backdrop` 类，玻璃退化为实色填充，视觉不破（既有纪律，`js/scrollUnderGlass.js:17`、`liquid-glass.css:247-294`）。

## §2 考古实测（三个基准件的真实参数，全部带出处）

### 2.1 基准件 A —— canvas markdown 源码/渲染切换器（尺寸与样式的模板）

`.canvas-source-toggle`（`split.css:1881-1905`）及其同族（引用选取 `.canvas-ref-select` `split.css:1912-1950`、文件刷新 `.canvas-refresh-toggle` `split.css:1967-2004`、查找 `.canvas-find-toggle`、图谱 `.canvas-graph-toggle`）：

| 维度 | 桌面实测值 | 出处 |
|---|---|---|
| 尺寸 | 40×40px 正圆（`--cv-size-circle`） | `sapphire.css:233` |
| 圆角 | 50% | `split.css:1887` |
| 图标 | 18px（`--cv-icon`）；条内小钮图标 14px（`--cv-icon-btn`） | `sapphire.css:235-236` |
| 边框 | 1px `--cv-border`（= `--glass-border`） | `sapphire.css:216` |
| 填充 | `--cv-bg`（= `--glass-bg`） | `sapphire.css:212` |
| 玻璃 | `blur(24px) saturate(1.15)` + `-webkit-` 双写 | `split.css:1890-1891` |
| 阴影 | `--cv-shadow`（= #input-bar 三档栈，见 2.3） | `sapphire.css:221` |
| hover | 填充→`--glass-control-bg-hover` + `scale(1.05)` | `split.css:1901-1904` |
| 按下 | `scale(0.96)` | `split.css:1905` |
| 选中态 | 文字 `--color-primary` + 边框 `rgb(--sapphire/0.35)` + 填充 `rgb(--sapphire/0.12)` | `split.css:1938-1942` |
| 禁用 | `opacity: 0.35` + `not-allowed` + hover 不形变不换底 | `split.css:1943-1950` |
| 簇槽位 | 右下角 bottom:16 起排，槽距 48px（`--cv-row-pitch`）：source 16 · ref 64 · find 112 · 缩放条 160 | `split.css:1884/1915`、`split.css:2868-2874` |

手机端（`body.mobile-layer`）：圆钮 48×48、图标 20px、按压改弹簧 `scale(0.88)`（180ms `cubic-bezier(0.34,1.56,0.64,1)`）、`prefers-reduced-motion` 时形变退化为 opacity 0.8（`mobileLayer.css:1922-1971`）。

### 2.2 基准件 B —— 主会话 Header（毛玻璃配方的模板之一）

`#header`（`chat.css:44-80`）：

| 维度 | 实测值 |
|---|---|
| 填充 | `--glass-bg`：亮 `rgba(255,255,255,0.55)` / 暗 `rgba(24,28,38,0.68)`（`sapphire.css:37,99`） |
| 玻璃 | `blur(var(--glass-blur)) saturate(1.15)`，blur = 24px；`-webkit-` 双写（`chat.css:48-49`） |
| 边框 | 1px `--glass-border`：亮 `rgba(255,255,255,0.35)` / 暗 `rgba(255,255,255,0.06)`；**底缘透明化**（零边界批，`chat.css:51-55`） |
| 顶缘折射线 | 80%×1px sapphire 渐变细线：`transparent 10% → var(--sapphire-refraction) 50% → transparent 90%`（亮档白 .45 / 暗档蓝 .06，`sapphire.css:11,93`） |
| 圆角 | 20px |
| 阴影 | 仅顶缘 inset 高光（亮 `rgba(255,255,255,0.25)` / 暗 `rgba(255,255,255,0.04)`）——外投影豁免（底缘零边界批，`chat.css:69-80`） |

### 2.3 基准件 C —— 主会话输入框（毛玻璃配方的模板之二 + 面阴影模板）

`#input-bar`（`input.css:17-62`）：

| 维度 | 实测值 |
|---|---|
| 填充/玻璃/边框/圆角 | 与 #header 同配方（`--glass-bg` + blur 24 sat 1.15 双写 + `--glass-border` + 20px） |
| 内距 | 6px 12px |
| **面阴影（亮）** | `inset 0 1px 0 rgba(255,255,255,.25), 0 2px 8px rgba(0,0,0,.04), 0 8px 24px rgba(0,0,0,.06)`（`input.css:32-35`） |
| **面阴影（暗）** | `inset 0 1px 0 rgba(255,255,255,.04), 0 2px 8px rgba(0,0,0,.20), 0 8px 24px rgba(0,0,0,.35)`（`sapphire.css:263`，自 input.css 逐字转录的单源档） |
| 顶缘折射线 | `::before` 同 #header 配方（`input.css:47-62`） |

### 2.4 既有 token 家族盘点（沿用 / 收编 / 退役）

| 家族 | 定义处 | 本稿处置 |
|---|---|---|
| `--glass-*`（面/border/etched） | `sapphire.css:37-58, 99-111` | **沿用**（玻璃配方单源，即基准件 B/C 材质） |
| `--frost-shadow` | `sapphire.css:50-53, 104-107` | 沿用（全屏面用；浮件用 `--cv-shadow`） |
| `--glass-control-*`（白玻璃控件 v2 值） | `sapphire.css:155-185` | **沿用 hover/选中抬亮档** + ghost 家族 |
| `--cv-*`（canvas 控件玻璃档） | `sapphire.css:210-265` | **收编升格**：从「canvas 专属」升为「全站悬浮控件单源」，本稿一切浮件只准引用本族 |
| `--capsule-*`（胶囊几何 36×180） | `sapphire.css:82-83` | 沿用 |
| `--g2-*`（液态玻璃 F1 族） | `stand-tokens.css:168-238` | **退役**（§4 遗产表；veil 子族待裁） |
| `--ctl-*`（扁平控件族） | `stand-tokens.css:296-326` | 保留不动（范围外） |
| `--btn-ink-*`（墨单色族） | sidebar/input 各处引用 | 保留（emphasis 药丸/主操作沿用） |

## §3 统一规格（token 表 + 状态矩阵）

### 3.1 材质配方（浮件玻璃 = 基准件 B/C 逐字，直接抄）

所有悬浮圆钮/浮条/胶囊一律：

```css
background: var(--cv-bg);            /* = --glass-bg，亮 .55 白 / 暗 .68 深蓝灰 */
backdrop-filter: blur(24px) saturate(1.15);
-webkit-backdrop-filter: blur(24px) saturate(1.15);   /* 双写，纪律见 §3.7 */
border: 1px solid var(--cv-border);  /* = --glass-border */
box-shadow: var(--cv-shadow);        /* = #input-bar 三档栈（亮/暗两档见 §2.3） */
```

- **面级浮件**（标题胶囊、查找条、缩放条、工具条容器）加顶缘折射线（`::before`，#header/#input-bar 同配方），并按各自既有裁定做底缘透明化（`.canvas-header` 现状即此，`split.css:703-731`）。
- **圆钮级浮件**不加折射线（40/48px 圆上放 1px 细线不可读，现状基准件 A 也没有）。
- 抛弃 F1 配方（填充 .30 / blur 20 / sat 2.2 / 单层大投影）——它是「另一种玻璃」，不是 Header 这款（§4）。

### 3.2 尺寸档（全站四档 + 手机触达下限）

| 档 | token | 桌面 | 手机（body.mobile-layer） | 适用 |
|---|---|---|---|---|
| 圆钮 | `--cv-size-circle` | **40px** | **48px** | source/ref/find/graph/refresh、deck-fab、关签钮、ask-fab、dock 待办/搜索浮钮 |
| 条内小钮 | `--cv-size-btn` | 26px | 26px（浮条内） | 查找条/缩放条内按钮 |
| 胶囊 | `--capsule-h/w` | 36×180px | 36×180（手机卡头自有形态沿用） | 标题胶囊、页签胶囊 |
| 药丸 | — | h36 r999 | h44 | 引用返回 chip 等胶囊形态（形状语义保留） |

- **手机触达下限**：48px ≥ Apple HIG 44pt 最低触达，且与手机 16px 边距栅格、`safe-area-inset-top` 让位规则（`mobileLayer.css:141-149,1975-1981`）同构——全部保留。
- 图标：桌面 18px（圆钮）/ 14px（条内小钮）；手机 20px（圆钮）。图标一律 `currentColor` 继承文字色。
- 槽距：圆钮簇 48px pitch（8px 视觉缝），右下簇槽位 16/64/112/160 不动。

### 3.3 圆角档（沿用 `--cv-radius-*` 单源）

胶囊 999px · 查找条 12px · 缩放条 10px · 胶囊内小钮 8px · 条内小钮 7px · 圆钮 50%（`sapphire.css:239-243`）。

### 3.4 状态矩阵（深浅双主题对照）

| 状态 | 桌面 | 手机 | 亮/暗差异 |
|---|---|---|---|
| 默认 rest | 玻璃配方（§3.1）+ 文字 `--color-text` | 同 | 仅 token 换色，几何零参与（既有裁定） |
| hover | 填充→`--cv-hover-bg`（=`--glass-control-bg-hover`）+ `scale(1.05)` | **无 hover**（触屏无悬停） | 亮 .74 白 / 暗 .20 白 |
| 按下 active | `scale(0.96)` | 弹簧 `scale(0.88)`（180ms spring；reduced-motion 退 opacity 0.8） | — |
| 选中/接通 selected | 文字 `--color-primary` + 边框 `rgb(--sapphire/0.35)` + 填充 `rgb(--sapphire/0.12)`（ref-select.active 档为准） | 同 | 双主题同一配方（sapphire 档） |
| 禁用 disabled | `opacity: 0.35` + `not-allowed`，hover 不形变不换底 | `pointer-events: none` + opacity .35（.mf-cv-toolbar 现行档） | — |
| 强调 emphasis | 主操作药丸 = 墨单色族 `--btn-ink-*`（范围外，维持现状） | 同 | — |
| 焦点 focus-visible（**新增建议**） | `outline: 2px solid rgb(var(--sapphire)/0.5); outline-offset: 2px` | 同 | 基准件 A 现无焦点态，补 a11y 缺口 |

> 注：`#deck-fab` 现行 pressed 档是 sapphire 14% 填充 + 全 sapphire 文字（`split.css:1540-1543`），统一后并入 ref-select 档（12%/35%），两处选中语义归一。

### 3.5 WebKit 兼容注记（沿用今早跨浏览器约束，逐条保留）

1. **`-webkit-backdrop-filter` 必须与标准属性双写**——iOS Safari/旧 Chrome 只认带前缀版；全站既有 CSS 已双写，收编面继续保持。
2. **no-backdrop 降级双保险**：JS 特性检测挂 `html.no-backdrop`（`js/scrollUnderGlass.js:17`）+ `@supports not (backdrop-filter…)` 媒体查询（`liquid-glass.css:247-294` 先例）。回落实色档 = `--glass-bg` 原值（亮 .55/暗 .68 半透明已够读；F1 时代用 `--g2-fab-solid` .55 白——退役后不再需要新常量）。
3. **mask 双写**：凡用 `mask-image` 渐隐（顶缘渐隐带若保留）必须 `-webkit-mask-image` 同步（`liquid-glass.css:241-242` 先例）。
4. **`prefers-reduced-motion`**：一切形变按压必须有 opacity 退化档（`mobileLayer.css:1951-1971` 模板）。
5. **主题切换零几何参与**：亮暗只走 token 换色，任何选择器不得把尺寸/位移写进主题分支（`split.css:1958-1961` 既有判据）。
6. **SVG filter 折射不进实现面**：`filter:url(#…)` 叠 `backdrop-filter` 在 Safari/iOS WebKit 不可用（§4），浮件实现禁止依赖。

### 3.6 深浅双主题对照总表（亮 → 暗）

| token | 亮 | 暗 |
|---|---|---|
| `--glass-bg`（填充） | `rgba(255,255,255,0.55)` | `rgba(24,28,38,0.68)` |
| `--glass-border`（描边） | `rgba(255,255,255,0.35)` | `rgba(255,255,255,0.06)` |
| `--sapphire-refraction`（折射线） | `rgba(255,255,255,0.45)` | `rgba(91,127,191,0.06)` |
| `--cv-shadow`（面阴影） | inset .25 高光 + .04/.06 双外影 | inset .04 高光 + .20/.35 双外影 |
| hover 填充 | `rgba(255,255,255,0.74)` | `rgba(255,255,255,0.20)` |
| 选中 | sapphire 12% 底 / 35% 边 / primary 字 | 同左 |

## §4 与今早液态玻璃调研的关系（弃什么、留什么）

**弃 —— SVG feDisplacementMap 折射主路线（`html.g2-refract-on` 的 `filter:url(#g2-refract)`）**：
1. **跨端不对称**：该链路在 Safari/iOS WebKit 上不可用——作者的 iPhone 上只有降级 blur，折射效果看不到；一个作者主力设备上不可见的效果不配当主方向。
2. **违反单源令**：F1 配方（blur 20 / sat 2.2 / 填充 .30 / 单层大投影）与主会话玻璃（blur 24 / sat 1.15 / 三档栈阴影）是**两套并存的玻璃语言**，直接违背 2026-10-22「全站一套毛玻璃…禁止各画各的」（`sapphire.css:44-49`）。作者本令「毛玻璃参考 Header 和输入框」即终裁回归单源。

**留 —— 三样**：
1. **纯磨砂观感本身**：`blur+saturate` 不依赖任何 filter 链路，任何端都成立，且与 Header 玻璃同族——stand 本来就有，不缺新效果。
2. **v2/v3 批沉淀的工程纪律**（与本稿正交，全部保留）：手机 48px 触达档与弹簧按压、`prefers-reduced-motion` 退化、no-backdrop 双保险、mask 双写、`liquid-glass.spec` 的验收面写法。
3. **控件族分场景裁定**：悬浮件玻璃、内容内控件扁平（v3 作者 11:43 拍板）——本稿沿用这条分界线，只把悬浮件的玻璃**从 F1 换回 Header 款**。

**遗产处置表**：

| 遗产 | 处置 |
|---|---|
| `liquid-glass.css` ④ 扩面块（F1 盖 canvas 圆钮族/ask-fab/deck-fab） | **退役**——材质权交还 `--cv-*` |
| `.g2-fab/.g2-pill/.mh-chrome-btn` 基类材质 | 退役；`.mh-chrome-btn` 的尺寸/落位语义（48px、top 16+safe、左右 16）保留并收编 |
| `#input-bar` v2 改皮（渐变填充 .74→.58 / sat 1.6 / 边 .75） | **撤销**，回归 input.css 原配方（本稿基准件 C） |
| `--g2-fab-*` token 族 | 删除（避免双源漂移）；veil 子族（`--g2-veil` 等）随渐隐带待裁 |
| SVG `#g2-refract` defs（index.html）+ `js/liquidGlass.js` 能力探测 | 无消费方后退役 |
| `#g2-edge-fade` 手机顶缘渐隐带 | **待作者裁**（§5.3）：功能（滚动可读性）与玻璃语言无冲突，可保留但材质对齐 §3.1 |
| `--ctl-*` 扁平族 + 绿发送键 | 保留（作者已拍板，范围外） |

## §5 应用面收编清单（本稿核心章节）

量级：小 = 换 token 引用/删覆盖层，无结构改动；中 = 撤一块改皮或跨状态回归；大 = 结构性改动（本稿无大项）。

### 5.1 手机端（body.mobile-layer）

| # | 落点 | 现状 | 统一后 | 量级 | 风险 |
|---|---|---|---|---|---|
| M1 | dock 顶部待办浮钮 `.mh-chrome-btn[data-chrome=tasks]` | F1 玻璃 48px（`liquid-glass.css:105-122,313` + `mobileLayer.css:146`） | 48px 基准玻璃圆钮，图标 20 | 小 | 低（与 M2 同改，落位不动） |
| M2 | dock 顶部搜索浮钮 `[data-chrome=search]` | 同上（`mobileLayer.css:147`） | 同 M1 | 小 | 低 |
| M3 | 消息/联系人页右上搜索浮钮 `.mh-fab-search` | F1 48px（`mobileLayer.css:1975-1981`） | 同 M1 | 小 | 低 |
| M4 | 会话页 ask-dock 开合钮 `.ask-fab` | cv 底被 F1 覆盖，48px fixed 右下（`liquid-glass.css:198-212` + `mobileLayer.css:1912-1921`） | 撤覆盖回基准玻璃 48px；通知点/脉冲动画保留 | 小 | 低 |
| M5 | canvas 文档工具圆钮族（source/ref/find/refresh，文档页右下簇） | F1 48px（`mobileLayer.css:1922-1936`） | 基准玻璃 48px + 弹簧按压保留 | 小 | 低 |
| M6 | canvas 引用返回 chip `.canvas-refback-btn` | chip + 弹簧（`mobileLayer.css:1939-1944`） | chip 形状豁免（非圆钮），材质收编玻璃配方 | 小 | 低 |
| M7 | canvas 工具条浮钮 `.mf-cv-close/.mf-cv-stack` | g2 48px（`mobileLayer.css:1093-1101`） | 基准玻璃 48px；禁用 opacity .35 档保留 | 小 | 低 |
| M8 | 堆叠视角关闭钮 `.mf-stack-x` | g2 基类 48px（`mobileLayer.css:1175-1176`） | 基准玻璃 48px | 小 | 低 |
| M9 | 手机顶缘渐隐带 `#g2-edge-fade` | g2 veil（blur 18 / 渐变罩 88px，`liquid-glass.css:228-243`） | 待作者裁：保留+材质对齐 / 退役 | 中 | 中（滚动可读性回归 + 微信内核 mask 兼容） |
| M10 | 会话头头像+名称胶囊 `.g2-chat-head` | in-flow，glass-control 材质（`liquid-glass.css:334-392`） | 沿用（不浮在内容上，不在收编面） | 无 | — |

### 5.2 桌面端

| # | 落点 | 现状 | 统一后 | 量级 | 风险 |
|---|---|---|---|---|---|
| D1 | canvas 源码/渲染切换器 `.canvas-source-toggle`（**基准件**） | cv 档被 F1 覆盖（`liquid-glass.css:200`） | 撤覆盖，回归 §2.1 实测档（即 10-28 件三统一态） | 小 | 低 |
| D2 | 引用选取钮 `.canvas-ref-select` | 同上 + selected sapphire 档 | 同上；selected 档保留并成为全站选中标准 | 小 | 低 |
| D3 | 文件刷新钮 `.canvas-refresh-toggle`（busy 旋转动画保留） | F1 | 基准玻璃 40px | 小 | 低 |
| D4 | 查找钮 `.canvas-find-toggle` / 图谱钮 `.canvas-graph-toggle` | F1 | 基准玻璃 40px | 小 | 低 |
| D5 | canvas 堆叠钮 `#deck-fab`（aria-pressed） | F1 + pressed sapphire 14%（`split.css:1529-1544`） | 基准玻璃 40px；pressed 并入 12%/35% 选中档 | 小 | 低 |
| D6 | 桌面关签钮 `#canvas-head-close` | F1 40px（`split.css:1552-1560`） | 基准玻璃 40px | 小 | 低 |
| D7 | canvas 标题胶囊 `.canvas-header` / 页签胶囊 `.cr-capsule` | 已达标（cv 玻璃+折射线+底缘透明，`split.css:703-731`） | 沿用 | 无 | — |
| D8 | 文件内查找条 `.nf-findbar` / 图片 PDF 缩放条 `.canvas-zoom-bar` | 已达标（cv 玻璃 + 折射线，`split.css:2788-2856`） | 沿用 | 无 | — |
| D9 | 主会话 Header `#header` | 基准件 B | 零触碰（既有裁定） | 无 | — |
| D10 | 主会话输入框 `#input-bar` | 被 v2 改皮成渐变/sat 1.6（`liquid-glass.css:180-188`） | 撤改皮，回归基准件 C 原配方 | 中 | 中（frozen/disconnected/voice 态需回归） |
| D11 | ask-dock 桌面开合钮 `.ask-fab` | 44px（`--ask-fab-size`）+ F1（`ask.css:34,65-89`） | 基准玻璃；尺寸并入 40px 标准档（与右下圆钮簇 48px 槽距语义经 JS 让位逻辑兼容） | 小 | 低 |
| D12 | 独立窗浮件：媒体角钮 `.pop-media-canvas`（28px 深底白字）/ 展开堆叠 `.pill` / lightbox 入口 chip / `.fwd-btn`（30px ghost） | 各自为政（`chat.css:4746-4818`） | 圆钮类收编玻璃 26-40 档；chip 保形换材质 | 中 | 中高（独立窗衬底不可控，玻璃透媒体色需试）——**待作者裁是否本期收编** |
| D13 | ghost 家族：Header 记忆键 `#memory-btn`（28px）/ 导航·活动栏钮 | Pattern A（`chat.css:115-137`） | 沿用（范围外，维持 hover 显玻璃语言） | 无 | — |
| D14 | wiki 图谱左栏控制面控件 | frosted 单源消费者 | 沿用 | 无 | — |

### 5.3 待作者裁清单

1. **`#g2-edge-fade` 手机顶缘渐隐带**：保留（材质对齐本稿）还是随液态玻璃批一并退役？保留的话 M9 改动中、需滚动回归。
2. **独立窗（D12）是否进本期收编面**：玻璃在不可控衬底上可能显脏；不进则维持现状，仅统一圆角/尺寸。
3. **`.ask-fab` 桌面档 44→40**：统一到 40 标准档（本稿建议），或保留 44 作为「入口钮」特例档？

### 5.4 不漏面自查

dock 顶（待办/搜索）✓ · 会话页（ask-fab、会话头）✓ · canvas 手机件（工具簇/工具条/堆叠/返回 chip/渐隐带）✓ · canvas 桌面件（源码/ref/refresh/find/graph/deck/关签/标题胶囊/查找条/缩放条）✓ · 顶栏与输入框 ✓ · 独立窗 ✓ · ghost/导航（豁免申报）✓ · 控件族（豁免申报）✓。

## §6 样张与证据

- **自包含样张**：`docs/design/unified-button-mockup.html`（[[unified-button-mockup.html]]）——浏览器直开，含亮/暗主题切换按钮；按统一规格渲染：玻璃配方演示、圆钮六状态矩阵（桌面 40 / 手机 48）、canvas 右下簇与胶囊/查找条模拟、dock 顶部浮钮模拟、ask-fab、deck/关签顶排。
- **截图证据**：`docs/evidence/20261101_unified-button-design/`（亮/暗全页）。

## §7 可断言验收点（供 QA 转自动化断言，全部二值）

1. 收编面任一悬浮圆钮的 computed `backdrop-filter` = `blur(24px) saturate(1.15)`，且 CSS 源内 `-webkit-backdrop-filter` 与标准属性双写。
2. 圆钮直径：桌面 40px、`body.mobile-layer` 下 48px；图标 18/20px。
3. 选中态（`.canvas-ref-select.active`、`#deck-fab[aria-pressed=true]`）computed：background = sapphire 12% tint、border-color = sapphire 35%、color = `--color-primary`。
4. disabled：`opacity = 0.35`，hover 后 transform 保持 none、background 不变。
5. `html.no-backdrop` 下：圆钮 computed `backdrop-filter = none`，背景为 `--glass-bg` 原值实色档，文字可读（对比非断言，肉眼复核）。
6. `#input-bar` computed background 无 linear-gradient（= `var(--glass-bg)` 纯色），box-shadow 三档栈与 `sapphire.css:221/263` 逐值相等。
7. `prefers-reduced-motion: reduce` 下按压态无 transform（退化 opacity）。

## §8 参考

- 既有规格与考古出处已逐条内联（§2/§5 表内 file:line）。
- 液态玻璃批（降级为参考）：[[20261031_084400_liquid-glass-effect-v2__delegate-nebflow-a9ba5eff.zh-CN]] · [[20261031_121500_liquid-glass-effect-v3__delegate-nebflow-9e155df5.zh-CN]] · [[20261031_170000_liquid-glass-ab-final__delegate-nebflow-a1031f13]]
- 单源旧令出处：`sapphire.css:44-49`（2026-10-22 全站一套毛玻璃）、`split.css:1877`（件三统一批注）、`sapphire.css:200-209`（--cv-* 族立族注）。
- 术语：[[glossary]]。
