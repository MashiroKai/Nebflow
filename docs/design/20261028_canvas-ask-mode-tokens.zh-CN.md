---
type: design-note
status: draft
---

# Canvas 文件 ask mode · 玻璃 token 规格页（设计资产）

> 本页是 [[20261028_canvas-ask-mode-design.zh-CN]]（设计稿）与 [[20261028_canvas-ask-mode-mockup.html]]（效果演示样张（[[glossary#^mockup-sample]]））的配套资产页：把演示里用到的全部样式整理成 **token 表 + 配方块**，作者确认样张后，实施批按本页直接消费——这是「之后工作能复用」的载体。状态：前端设计确认前草案。

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

1. 样张与实施全部玻璃值可追溯到 A 表（sapphire.css / chat.css / base.css / split.css / mobileLayer.css），实现代码内**禁止出现**本页值的字面量复本；
2. B 表新增 token 随实施批落入同一 token 文件并回填实际名；
3. 暗档为准（本设计首发桌面暗色），亮档值在亮主题下自动生效（`prefers-color-scheme` 同源切换）。
