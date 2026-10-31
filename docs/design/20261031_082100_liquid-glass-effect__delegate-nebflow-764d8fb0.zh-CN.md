---
type: design-spec
status: draft
title: 液体玻璃设计语言（顶栏渐隐 + 液体玻璃按钮）效果图设计稿
created: 2026-10-31
author: stand (delegate-nebflow-764d8fb0)
mockup: "[[liquid-glass-effect-mockup.html]]"
tags: [design, visual, liquid-glass, glassmorphism]
---

# 液体玻璃设计语言设计稿（只设计不实施）

配套效果图：[[liquid-glass-effect-mockup.html]]（亮/暗双版，组件可交互）。
前作关联：[[20261030_201846_button-system-monochrome-redesign__delegate-nebflow-fa3d4d6f.zh-CN]]（墨族单色批）· 视觉铁律 `visual-style`。

## §0 一句话目标

把作者 10-31 指定的标杆效果——**顶栏毛玻璃向下渐隐 + iOS 26 液体玻璃按钮**——落成
可拍板的效果图与材质配方，并评估能否统一到 Stand 控制台全局（顶栏/按钮/卡片/输入框）。

作者令 10-31 08:1x（逐字）：「看这个顶部 这个才是我要的毛玻璃渐隐效果 以及按钮的
液体玻璃效果 先设计个效果图给我看 然后我们看看能不能统一到全局」

## §1 参考与依据

| 依据 | 链接/出处 | 取舍 |
|---|---|---|
| 作者外截标杆图 | `/Users/kaiyu/.stand/media/weixin-inbound/5925e75c0164-im-bot/v1-16328315246351084668-image.jpg` | 最高优先级（用户 taste） |
| Apple HIG · Liquid Glass | https://developer.apple.com/design/human-interface-guidelines/liquid-glass | 按压回弹、镜面高光语汇 |
| 仓内既有渐隐范式 | `scrollUnderGlass.css:43-59`（backdrop-filter + mask 渐隐裙边）、`ask.css:140-147`（渐隐 veil） | 技术先例已在仓内，非新轮子 |
| 视觉铁律 | `visual-style`（frontend-dev 插件）：控件统一玻璃质感、低调克制专业感 | 冲突终裁；本稿不引入彩色 |
| 前作裁定 | [[20261030_201846_button-system-monochrome-redesign__delegate-nebflow-fa3d4d6f.zh-CN]]（10-30）：可交互主操作走墨族 `--btn-ink-*` | 本稿墨族**共存不推翻**（见 §5.3） |

**历史裁定注意**：10-22「Header 底缘零边界批」曾退役顶栏底部渐隐裙边（chat.css:50-76）。
10-31 作者令明确要求「毛玻璃渐隐」，按 visual-style 维护条款「用户改主意 = 最高优先级」
视为**取代**；实现批须同步回写该裁定，避免实现方按旧批执行。

## §2 布局与信息架构

不改布局，只改材质。受影响面（均为既有组件，类名不变）：

- `#header`（chat.css:43-77，index.html:484）— 聊天页顶栏
- `.glass-control` / `.cfg-btn` 族（sapphire.css）— 次级按钮、图标按钮、胶囊控件
- `.cfg-btn-primary`（sidebar.css:1219-1230）— 墨族主按钮
- `#input-bar`（input.css:14-57）— 聊天输入框
- 卡片类（`.session-item` sidebar.css:120-146 等平面行卡）— 可选升级面

新 token 建议以 `--lg-*` 前缀挂 `stand-tokens.css` 双档体系（`html[data-theme]` +
`prefers-color-scheme` 三态回退，同 `--btn-ink-*` 结构）；不覆盖任何既有变量名。

## §3 交互状态机表（液体玻璃按钮族）

| 状态 | 填充/材质 | 投影 | 形变 | 说明 |
|---|---|---|---|---|
| rest | `--lg-btn-bg`（亮 55% / 暗 10% 白）+ 上半镜面高光层 | 0 6px 20px 扩散浮影 | — | 高光层 = `linear-gradient(to bottom, --lg-btn-specular, transparent 42%)` |
| hover | 提升至 `--lg-btn-bg-hover` | 不变 | — | 只动填充，不动描边（亮档可辨性约束同灰族批） |
| active（按压） | 不变 | 投影收短 | `scale(.9)` 圆形 / `scale(.96)` 矩形 | spring 180ms 回弹，高光收敛 |
| disabled | 材质不变 | 不变 | — | opacity .5，禁形变 |
| focus | 墨族沿用 `--btn-focus-ring`（蓝）；玻璃族加 2px 外描边 `--lg-btn-border` 色 | — | — | 见 §7 |

## §4 动效规范

| 项 | 值 |
|---|---|
| 按压回弹 | `transform .18s cubic-bezier(.34,1.56,.64,1)`（spring overshoot） |
| 材质过渡 | `background-color .18s ease, box-shadow .18s ease` |
| 渐隐尾 | 静态，无动效（跟随滚动自然显现/消失） |
| prefers-reduced-motion | 按下类声明，形变退化为透明度变化（实现批补） |

## §5 视觉规格（材质配方）

### 5.1 一句话配方

**顶栏** = `blur(28px) saturate(1.6)` + 白 78%→60% 垂直微渐层 + `inset 0 1px 0` 镜面高光
+ `::after` 56px 渐隐尾（`mask-image: linear-gradient(#000, rgba(0,0,0,.35) 45%, transparent)`）；
**按钮** = 白玻璃 55%（暗 10%）+ `blur(14px) saturate(1.8)` + 上半镜面高光层 + 1px 亮白边
+ 底部 inset 折射暗边 + `0 6px 20px rgba(0,0,0,.10)` 扩散浮影；按压 `scale(.9–.96)` spring 180ms。

### 5.2 提案 token 表（亮档 / 暗档）

| token | 亮档 | 暗档 | 用途 |
|---|---|---|---|
| `--lg-header-blur` / `--lg-header-sat` | 28px / 1.6 | 同左 | 顶栏+输入框折射强度 |
| `--lg-header-bg` | `linear-gradient(rgba(255,255,255,.78), .6)` | `linear-gradient(rgba(28,32,44,.82), rgba(24,28,38,.66))` | 顶栏底色微渐层 |
| `--lg-header-specular` | `rgba(255,255,255,.9)` | `rgba(255,255,255,.16)` | 顶栏 inset 镜面高光 |
| `--lg-header-border` | `rgba(255,255,255,.7)` | `rgba(255,255,255,.14)` | 顶栏亮边 |
| `--lg-btn-bg` / `-hover` | `.55` / `.72` | `.10` / `.16` | 液体玻璃按钮填充 |
| `--lg-btn-specular` / `-highlight` | `.85` / `.95` | `.22` / `.30` | 镜面高光层 / inset 顶高光 |
| `--lg-btn-border` | `rgba(255,255,255,.85)` | `rgba(255,255,255,.22)` | 1px 亮边 |
| `--lg-btn-underedge` | `rgba(0,0,0,.07)` | `rgba(0,0,0,.35)` | 底部 inset 折射 |
| `--lg-btn-shadow` | `rgba(0,0,0,.10)` | `rgba(0,0,0,.45)` | 扩散浮影 |
| `--lg-card-*` | bg `.5` / border `.65` / specular `.8` / shadow `.06` | `.06` / `.10` / `.12` / `.30` | 卡片（可选面） |
| `--lg-ink-highlight` / `-shadow` | `.18` / `rgba(17,24,39,.25)` | `.5` / `rgba(0,0,0,.5)` | 墨玻璃主按钮叠加层 |

### 5.3 墨族共存方案（推荐，不换代）

- **主按钮（`.cfg-btn-primary`）**：保留 `--btn-ink-*` 墨族底色（10-30 单色批语义不动），
  叠加 `--lg-ink-highlight`（inset 顶高光）+ `--lg-ink-shadow`（浮影）升级为**墨玻璃**；
  补 `:active`（token 已存在但无规则消费——sidebar.css 现状缺陷）。
- **次级/图标/胶囊控件**：从现行 `--glass-control-*`（blur 10 / sat 1.2，无高光层、无按压态）
  升级到液体玻璃配方（blur 14 / sat 1.8 + 高光层 + 按压回弹）。
- **渐变线**：墨族管「主操作身份」，液体玻璃管「材质质感」，二者叠加不冲突；
  若作者拍板「全局纯液体玻璃」备选方案，墨族降级为 focus ring 与文字色专用
  （改造面与风险见 §6，不推荐一步到位）。

### 5.4 与现状的差异要点（效果图对照列即此表的可视化）

| 面 | 现状（10-30 实现值） | 提案 |
|---|---|---|
| 顶栏模糊 | `blur(24px) saturate(1.15)`，白 55% 平涂 | `blur(28px) saturate(1.6)`，白 78→60% 微渐层 + **56px 渐隐尾** |
| 顶栏边缘 | inset 高光 25%，底缘 hard-stop | 镜面高光 90%（暗 16%），底缘渐隐无边界 |
| 圆形图标按钮 | 36px，blur 10 / sat 1.2，无高光层无按压 | 40px，blur 14 / sat 1.8，镜面高光层 + spring 按压 |
| 次级按钮 | 4 层阴影但饱和度低、无 `:active` | 液体玻璃 + 按压 `scale(.96)` |
| 主按钮 | 墨族纯实心，无阴影无 active 消费 | 墨玻璃：+ inset 高光 + 浮影 + active |
| 卡片 | 平面平涂（`--color-frame-active`） | 可选：blur 20 折射 + 高光 + 浮影（列表行不建议全量，见 §6） |
| 输入框 | 与顶栏同族（blur 24/1.15） | 与顶栏同步升级（blur 28/1.6/镜面高光），发送键并入墨玻璃 |

## §6 边界与异常 / 全局统一改造面清单

**改造面（作者拍板「全局统一」后）**：

1. `stand-tokens.css` — 新增 `--lg-*` 双档 token 块（约 20 个变量）
2. `sapphire.css` `.glass-control` 族 — 换液体玻璃配方 + 补 `:active`
3. `chat.css` `#header` — 微渐层底 + 渐隐尾 `::after` + 参数升级；**回写 10-22 零边界批**
4. `input.css` `#input-bar` — 参数同步；发送键主按钮材质
5. `sidebar.css` `.cfg-btn-primary` — 墨玻璃叠加 + 补 `:active`
6. 顶栏动作按钮改 40px 正圆液体玻璃（参考图形态）
7. 卡片类**可选**：浮层卡/弹窗面板（`scroll-under-glass` 系已有渐隐裙边，天然同族）；
   **不建议**会话列表等密集行卡全量玻璃化（性能 + 噪乱，违背「低调克制」）

**风险**：

- **性能**：`backdrop-filter` 面数增加（渐隐尾 = 每顶栏多一个 backdrop 层）；
  低端机/长列表滚动帧率风险——渐隐尾需限定在顶栏/输入框/浮层面，禁入列表行。
- **历史裁定冲突**：10-22 Header 零边界批须正式回写，否则实现批无所适从（本稿 §1 已标注）。
- **亮档可辨性**：白玻璃叠白底，hover 仅靠边框/投影表达（同灰族批教训，
  visual-style 铁律 2 已锁口径）；本稿 hover 只动填充 55%→72%（Δ 17/255 可辨），合规。
- **双主题机制裂缝**：`--glass-*` 只响应 `prefers-color-scheme` 不响应 `data-theme`
  （recon 实勘结论），`--lg-*` 新块应按 `--btn-ink-*` 三态结构写死，不复制此缺陷。

**降级（微信内置浏览器 / 旧 WebView / X5）**：不支持 `backdrop-filter` 时按仓内既有
no-backdrop 口径（scrollUnderGlass.css:63-70）回落为**更高不透明度实色玻璃**
（亮 78%→85%、暗 .82→.92），高光/投影/按压保留，层次不塌；`mask-image` 双写
`-webkit-` 前缀（效果图已双写）。@supports 探测 + 类开关，无感过渡。

## §7 无障碍

- focus ring：墨族沿用 `--btn-focus-ring`（亮 #3b82f6 / 暗 #60a5fa，对比度 ≥4.5:1 对玻璃底）；
  玻璃族 focus 加 2px `--lg-btn-border` 色外描边。
- 按压态不只依赖形变：同时投影收短（双通道反馈）；disabled 用 opacity .5 + 禁形变。
- 图标按钮（☰ / ＋）需 `aria-label`（现状已有则保持）。

## §8 可断言验收点（供 QA 转 Playwright）

1. `#header` computed `backdrop-filter` 含 `blur(28px)` 与 `saturate(1.6)`（需截图 + getComputedStyle）
2. `#header::after` 存在且 `mask-image`/`webkitMaskImage` 非 none（DOM 断言）
3. `.glass-control:active` 存在 transform scale 规则且时长 ≈180ms（CSSOM 断言）
4. 亮档 hover 前后按钮填充合成亮度差 > 8/255 或边框/投影有变化（Δ 基线断言，锁死口径）
5. `--lg-*` token 在 `html[data-theme="light"|"dark"]` 与无属性 prefers-color-scheme 三态下均可解析且值不同（DOM 断言）
6. no-backdrop 降级态（@supports 关闭模拟）下顶栏背景 alpha ≥ .85 亮档 / ≥ .92 暗档（截图 + 像素采样）

## §9 参考链接

- 标杆图：`/Users/kaiyu/.stand/media/weixin-inbound/5925e75c0164-im-bot/v1-16328315246351084668-image.jpg`
- Apple HIG Liquid Glass：https://developer.apple.com/design/human-interface-guidelines/liquid-glass
- 效果图：[[liquid-glass-effect-mockup.html]]
- 前作：[[20261030_201846_button-system-monochrome-redesign__delegate-nebflow-fa3d4d6f.zh-CN]]
- 仓内先例：scrollUnderGlass.css（渐隐裙边）、ask.css（渐隐 veil）、input.css/chat.css/sapphire.css/sidebar.css/stand-tokens.css（现状值来源）
