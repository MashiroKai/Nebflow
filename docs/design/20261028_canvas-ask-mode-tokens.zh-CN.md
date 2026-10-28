---
type: design-note
status: draft
---

# Canvas 文件 ask mode · 玻璃 token 规格页（设计资产）

> 本页是 [[20261028_canvas-ask-mode-design.zh-CN]]（设计稿）与 [[20261028_canvas-ask-mode-mockup-v2.html]]（效果演示样张（[[glossary#^mockup-sample]]）v2，v1 样张 superseded 留档）的配套资产页：把演示里用到的全部样式整理成 **token 表 + 配方块**，作者确认样张后，实施批按本页直接消费——这是「之后工作能复用」的载体。状态：前端设计确认前草案。（2026-10-28 更新：样张 v2 已获作者验收，**E 表已随实施批落位** `web/css/ask.css`——见 §E 实施落位注记。）
> **2026-10-28 17:5x 作者八条令增补**：形态改版（右下角最靠右入口钮 → 点击弹输入框 → 气泡列从按钮位置长出；无窗体背景、毛玻璃气泡、顶部渐变模糊过渡、小巧不弹窗；品牌色取 stand-website 色板；每文件独立 session；开合动画锚定按钮）——见 **E 表**；A/C 表继续有效，B 表几何档被 E-2 取代（留档）。

## 0. 单源原则

本设计的控件**从第一天起引用现有玻璃 token 单源**（[[glossary#^concept-first-use]]口径：单源 = 改一处即全站生效），不另起炉灶：

- 玻璃面/控件面/胶囊几何 = `src/main/resources/web/css/sapphire.css`（2026-10-22 作者令「全站一套毛玻璃」，消费方 #header、.canvas-header 胶囊、wiki 左栏控制面——禁止各画各的）；
- 气泡族 = `src/main/resources/web/css/chat.css` `.bubble` 族；
- 主题底色/文字 = `src/main/resources/web/css/base.css` 暗档；
- 卡面/bottom sheet = `split.css` / `mobileLayer.css`。

演示样张（mockup.html）`:root` 里的值即本页 A 表的值，二者逐字一致；sapphire.css 若调值，样张与实施同步漂移。

**注**：另一在途批正把聊天控件毛玻璃整体提取为设计变量；本页 A 表全部引用其**现有变量名**（该批落地后名字不变、值同源），因此无需对齐动作。B 表新增 token 用 `--ask-` 前缀，命名沿用 `--{域}-{对象}-{档位}` 惯例（参见 `--glass-control-bg-hover`、`--card-dim-0`）。

## A. 引用现有单源（零新增）

### A-1 面板级玻璃（ask 窗体、主会话侧板）

| token | 亮档值 | 暗档值 | 本设计适用控件 |
|---|---|---|---|
| `--glass-bg` | `rgba(255,255,255,0.55)` | `rgba(24,28,38,0.68)` | ask 窗体、输入框容器、主会话侧板 |
| `--glass-blur` | `24px` | `24px` | 同上（配方 `blur(24px) saturate(1.15)`） |
| `--glass-border` | `rgba(255,255,255,0.35)` | `rgba(255,255,255,0.06)` | 窗体 1px 描边「whisper edge」 |
| `--frost-shadow` | `inset 0 1px 0 0 rgba(255,255,255,0.25), 0 2px 8px rgba(0,0,0,0.04), 0 8px 24px rgba(0,0,0,0.06)` | `inset …0.04), 0 2px 8px rgba(0,0,0,0.20), 0 8px 24px rgba(0,0,0,0.35)` | 窗体/输入框标准阴影（桌面窗另叠 `0 24px 64px rgba(0,0,0,0.42)` 落影档） |

### A-2 控件级玻璃（入口钮、窗头钮、chip、页签钮）

| token | 亮档值 | 暗档值 | 本设计适用控件 |
|---|---|---|---|
| `--glass-control-bg` | `rgba(255,255,255,0.45)` | `rgba(255,255,255,0.05)` | 入口角标钮 rest、窗头钮、快捷 chip |
| `--glass-control-bg-hover` | `rgba(255,255,255,0.65)` | `rgba(255,255,255,0.10)` | 同上 hover |
| `--glass-control-border` | `rgba(255,255,255,0.40)` | `rgba(255,255,255,0.08)` | 控件 1px 边 |
| `--glass-control-highlight` / `-hover` | `rgba(255,255,255,0.50)` / `0.62` | `0.08` / `0.13` | `inset 0 1px 0` 顶缘高光 |
| `--glass-control-underedge` | `rgba(0,0,0,0.08)` | `rgba(0,0,0,0.25)` | `inset 0 -1px 0` 底缘 |
| `--glass-control-glow` / `--glass-control-shadow` | `rgba(0,0,0,0.06)` / `0.04` | `0.20` / `0.15` | `0 1px 3px` / `0 2px 8px` |
| `--glass-control-blur` | `10px` | `10px` | 配方 `blur(10px) saturate(1.2)` |
| 蚀刻输入面 `--glass-etched-bg` / `-border` / `-border-focus` | `rgba(0,0,0,0.025)` / `rgba(91,127,191,0.10)` / `0.35` | `rgba(255,255,255,0.04)` / `rgba(91,127,191,0.08)` / `0.35` | 窗头上下文状态行、输入 focus 环 |

### A-3 气泡族（chat.css `.bubble`，逐字沿用——ask 窗就是主聊气泡语言）

| token / 规则 | 值 | 说明 |
|---|---|---|
| `.bubble` 公共 | `max-width:80%; padding:10px 14px; border-radius:16px; font-size:14.5px; line-height:1.55` | 行容器 `.row`：`margin 6px 0` + fadeIn 0.2s |
| 用户 | `background: var(--color-bubble-user)` = `#95ec69`；暗档字色 `#111`；`border-bottom-right-radius:4px` | 右对齐 |
| ask 应答 | `background: var(--color-bubble-ai)`（暗 `#1c1f27`）；`border:1px solid rgba(0,0,0,0.04)`；`box-shadow:0 1px 2px rgba(0,0,0,0.04)`；`border-bottom-left-radius:4px` | 左对齐「whisper edge」 |
| 系统瞬间（发给主会话回执） | `.bubble.injected` 同族：`rgb(var(--sapphire) / 0.12)`（`--sapphire: 91 127 191`），同 user 剪影 | 读作普通气泡，非框卡 |
| 发送钮 | `rgb(var(--brand-green))` = `#07c160`（`--brand-green: 7 193 96`），32×32 圆 | 与 `#send-btn` 族同语言 |

### A-4 几何与动效

| token | 值 | 本设计消费 |
|---|---|---|
| `--capsule-h` / `--capsule-w` | `36px` / `180px` | **不新增顶行控件**（入口不进顶行，见设计稿 §2 推荐案 A） |
| `--panel-ease` / `--panel-duration` | `cubic-bezier(0.32,0.72,0,1)` / `0.32s` | ask 窗开合 |
| `--ease-enter` | `cubic-bezier(0.16,1,0.3,1)` | 控件微动效 |
| `--spring`（mobileLayer 档） | `cubic-bezier(0.3,1.36,0.45,1)` | 移动端窗入场 |
| 字体 | `-apple-system, BlinkMacSystemFont, "PingFang SC", "Segoe UI", Roboto, sans-serif`；mono `ui-monospace, SFMono-Regular, Menlo` | 全件 |

## B. 本设计新增（提案档，实施批落 `--ask-` 进同一 token 文件）

> **v2 superseded 注记（2026-10-28 八条令）**：v1 形态是「卡头角标钮 + 380×520 玻璃窗」，本表几何档（`--ask-panel-*` / `--ask-entry-size` / `--ask-radius` / `--ask-dock-gap`）随形态作废，现行几何见 **E-2**；命名惯例 `--{域}-{对象}-{档位}` 不变。表体留档不删。

| token（提案名） | 桌面档 | 移动档 | 适用控件 |
|---|---|---|---|
| `--ask-panel-w` × `--ask-panel-h` | `380px` × `520px` | sheet 内浮层自适应（left/right 12 · top 60 · bottom 108） | ask 窗默认档 |
| `--ask-panel-w-wide` × `--ask-panel-h-wide` | `480px` × `640px` | 不设（移动无放大档） | 展开钮二档 |
| `--ask-entry-size` | `24px`（视觉圆，命中盒 ≥32 建议） | `44px` 命中盒 / 视觉圆 30（7px 透明边 + padding-box，#988 同件） | 入口角标钮 |
| `--ask-radius` | `20px`（= 面板圆角惯例档） | 同 | 窗体 |
| `--ask-dock-gap` | `26px`（右缘距 / 顶 64px 锚卡头下） | `12px` 侧距 | 窗体落位 |

## C. 三块核心配方（复制即用，值 = A/B 表）

```css
/* ① ask 窗体 = 面板级玻璃 + 折射线 */
.ask-panel {
  border-radius: var(--ask-radius, 20px);
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  box-shadow: var(--frost-shadow), 0 24px 64px rgba(0, 0, 0, 0.42);
}
.ask-panel::before { /* 顶缘折射线，与 #input-bar / #header 同构 */
  content: ""; position: absolute; top: 0; left: 10%; width: 80%; height: 1px;
  background: linear-gradient(90deg, transparent 10%, var(--st-glass-refraction, rgba(91,127,191,0.45)) 50%, transparent 90%);
}

/* ② 入口角标钮 = 控件级玻璃（.glass-control 同配方） */
.ask-entry {
  width: var(--ask-entry-size); height: var(--ask-entry-size); border-radius: 50%;
  background: var(--glass-control-bg);
  -webkit-backdrop-filter: blur(var(--glass-control-blur)) saturate(1.2);
  backdrop-filter: blur(var(--glass-control-blur)) saturate(1.2);
  border: 1px solid var(--glass-control-border);
  box-shadow: inset 0 1px 0 var(--glass-control-highlight), inset 0 -1px 0 var(--glass-control-underedge),
              0 1px 3px var(--glass-control-glow), 0 2px 8px var(--glass-control-shadow);
  opacity: 0; /* hover 卡片显现，与 .deck-card-x 同律 */
}
.deck-card:hover .ask-entry, .ask-entry:focus-visible { opacity: 1; }
/* 移动端：44×44 命中盒，视觉圆 30 —— border:7px solid transparent + background-clip:padding-box（#988 件） */

/* ③ 窗内输入框 = #input-bar 缩档 */
.ask-input {
  border-radius: 16px; background: var(--glass-bg); border: 1px solid var(--glass-border);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  box-shadow: var(--frost-shadow);
}
.ask-input input { font: 400 14.5px inherit; background: transparent; border: none; }
.ask-input:focus-within { border-color: var(--glass-etched-border-focus); }
```

## D. 验收口径（token 面）

1. 样张与实施全部玻璃值可追溯到 A 表（sapphire.css / chat.css / base.css / split.css / mobileLayer.css）与 E-1 表（stand-website 色板），实现代码内**禁止出现**本页值的字面量复本；
2. B/E 两表新增 token 随实施批落入同一 token 文件并回填实际名（v2 消费 A + E，B 表留档）；
3. 暗档为准（本设计首发桌面暗色），亮档值在亮主题下自动生效（`prefers-color-scheme` 同源切换）。

## E. v2 形态增补（作者八条令 2026-10-28 17:5x）

> v2 形态 = **canvas 右下角最靠右入口钮 → 点击弹输入框 → 气泡列从按钮位置长出**：无窗体背景（零面板/零弹窗/零遮罩、不占 canvas）、毛玻璃气泡、顶部渐变模糊过渡、每文件独立 session、开合动画锚定按钮位置。载体：[[20261028_canvas-ask-mode-mockup-v2.html]]。八条令 → 设计落点：①入口钮（E-2 `--ask-fab-*`）②输入框（E-3 `.ask-input`）③气泡列（E-3 `.ask-col`，弹幕式升腾+按龄渐隐）④无窗体（覆盖层无面板背景，只有浮件自身）⑤毛玻璃+顶部渐变模糊（E-3 双 mask）⑥品牌色板（E-1）⑦每文件独立 session（`ask:<hash(文件路径)>`，切卡即切、收起不销毁）⑧开合动画（E-3 `transform-origin` 锚定按钮圆心）。

> **实施落位注记（2026-10-28 实施批）**：E 表已随实施批消费——
> - **落位**：全部 `--ask-*` token 落 `src/main/resources/web/css/ask.css`（ask 域单文件；玻璃基底照 §0 引 sapphire.css 单源，本文件零新增玻璃值字面量进产品 CSS）；`web/index.html` 在 `split.css` 后挂载。
> - **E-2 值逐字未改**（44/14/372/316·250/64·56/306·252/16·8/z24 全对表；`--ask-fab-bottom` 基准 14px，角落圆钮簇在场时 JS 实测让位 = `syncSeat`，属表意内的动态座位非改值）。
> - **E-1 落法**：亮暗双机制 = `@media (prefers-color-scheme: dark)` + `html[data-theme='dark']` 双写（sapphire 同款）+ `html[data-theme='light']` 显式亮档（防手选亮主题吃 media 暗值）；E-1 色值逐字，`--ask-pixel` 零消费照表。
> - **实施假设档（回验点）**：亮档气泡/输入/徽标面色（E-3 配方的亮档衍射：user/sys 渐变、`--ask-bub-bg` 系）——样张仅给暗档基准，亮档按 E-1 文字/底列同式派生；作者过目亮档后如有异，只改 ask.css 亮档段一处。
> - **D-2 回填**：E 表提案名即实际名，零回填差异；B 表留档未消费（v2 不适用）。

### E-1 品牌色板（取自 stand-website 仓 `app/globals.css`，逐字）

出处：亮档 = `@theme`（globals.css:4-21）；暗档 = `html[data-theme="dark"]` 覆盖（:115-127）；绿非交互注记 = globals.css:14-20。

| token（v2 提案名） | 亮档值 | 暗档值 | 源变量 | ask 面消费 |
|---|---|---|---|---|
| `--ask-brand` | `#3b82f6` | `#60a5fa` | `--color-primary` | **功能/交互色**：入口钮、发送钮、用户气泡玻璃、focus 环 |
| `--ask-brand-light` | `#60a5fa` | `#93c5fd` | `--color-primary-light` | 入口钮图标、渐变端 |
| `--ask-accent` | `#8b5cf6` | `#a78bfa` | `--color-accent` | **强调**：session 徽标点、转交钮、转交回执气泡 |
| `--ask-pixel`（禁交互） | `#07c160` | `#07c25f` | `--color-pixel` | **不消费**——源码注记「deliberately NOT an interactive control color」，ask 面无绿钮（v1 的绿发送钮随 E 废） |
| 文字/底 | `#0f172a` / `#ffffff` / `#f8fafc` / `#64748b` | `#e6edf3` / `#0d1117` / `#161b22` / `#98a0aa` | `--color-foreground/-background/-card/-muted` | 气泡文字、session 徽标副字 |
| 链接（docs-scope） | `#2563eb` / `#1d4ed8` | `#7aa5f8` / `#9dbcfa` | `--color-link(-strong)` | cite 引用徽标 |

与单源的关系（§0 不变）：玻璃基底/几何仍取 `sapphire.css`；品牌色是叠加层——实施批以 E-1 的 `--ask-*` 别名落 token，**不在产品 CSS 硬编码 hex**；官网若调色，改 stand-website 一处、`--ask-*` 回填一处。

### E-2 v2 几何/材质/z 档（提案名，实施批落 `--ask-*`）

| token（提案名） | 桌面档 | 移动档 | 消费方 |
|---|---|---|---|
| `--ask-fab-size` | `44px`（命中盒=视觉径） | `44px`（同） | 入口钮：右下角**最靠右**（作者令①） |
| `--ask-fab-gap` | `14px` | `12px` | 钮距画布右/下缘 |
| `--ask-dock-w` | `372px` | `calc(100% - 24px)` | 覆盖层宽（= 输入框档宽） |
| `--ask-col-maxh` | `316px` | `250px` | 气泡列 max-height（超出内部滚动，小巧不占 canvas） |
| `--ask-fade-h` | `64px` | `56px` | 顶部渐变模糊过渡带高（作者令⑥） |
| `--ask-bub-maxw` | `306px` | `252px` | 气泡 max-width |
| `--ask-bub-blur` / `--ask-veil-blur` | `16px` / `8px` | 同 | 气泡毛玻璃 blur / 顶部模糊幕 blur（配方 `blur() saturate(1.4)`） |
| `--z-ask-dock` | `24`（< 顶行 `30`） | 同 | **覆盖层与顶行排他 z 序**：ask 层永在顶行（主胶囊/堆叠钮/卡头/切换条）之下，顶带渐隐即视觉边界 |

### E-3 v2 核心配方（复制即用；值 = A 表 + E-1/E-2）

```css
/* ④⑤ 毛玻璃气泡（无窗体——气泡自身就是全部容器，无面板背景） */
.ask-bub {
  background: rgba(22, 27, 34, 0.52); border: 1px solid rgba(240, 246, 252, 0.10);
  -webkit-backdrop-filter: blur(var(--ask-bub-blur)) saturate(1.4);
  backdrop-filter: blur(var(--ask-bub-blur)) saturate(1.4);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.26), inset 0 1px 0 rgba(255, 255, 255, 0.06);
  border-radius: 15px; padding: 9px 13px; font-size: 13.5px; line-height: 1.6;
  max-width: var(--ask-bub-maxw);
}
.ask-bub.user { /* 用户侧 = 官网品牌蓝玻璃 */
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.30), rgba(37, 99, 235, 0.20));
  border-color: rgba(96, 165, 250, 0.40);
}
.ask-bub.sys { /* 转交回执（injected 族）= 官网紫强调 */
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.24), rgba(124, 58, 237, 0.14));
  border-color: rgba(167, 139, 250, 0.38);
}

/* ⑥ 顶部渐变模糊过渡：列 mask 渐隐 + 顶带模糊幕（双 mask 叠加，旧气泡化入画布） */
.ask-col {
  -webkit-mask-image: linear-gradient(to bottom, transparent 0, rgba(0,0,0,.55) 26px, #000 var(--ask-fade-h));
  mask-image: linear-gradient(to bottom, transparent 0, rgba(0,0,0,.55) 26px, #000 var(--ask-fade-h));
}
.ask-veil {
  height: var(--ask-fade-h); pointer-events: none;
  -webkit-backdrop-filter: blur(var(--ask-veil-blur)); backdrop-filter: blur(var(--ask-veil-blur));
  -webkit-mask-image: linear-gradient(to bottom, #000, transparent);
  mask-image: linear-gradient(to bottom, #000, transparent);
}

/* ⑧ 开合动画锚定按钮位置：transform-origin = 右下角按钮圆心，开合同一曲线 */
.ask-dock {
  transform-origin: 100% 100%;
  transition: transform var(--panel-duration) var(--panel-ease), opacity 0.24s ease;
}
.ask-dock.closed { transform: translateY(16px) scale(0.6); opacity: 0; pointer-events: none; }
```
