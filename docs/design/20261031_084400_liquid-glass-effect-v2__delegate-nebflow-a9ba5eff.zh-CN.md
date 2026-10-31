---
type: design-spec
status: draft
title: 液体玻璃设计语言 v2（无顶栏 + 悬浮圆钮/药丸钮）设计稿
created: 2026-10-31
author: stand (delegate-nebflow-a9ba5eff)
mockup: "[[liquid-glass-effect-mockup-v2.html]]"
supersedes: "[[20261031_082100_liquid-glass-effect__delegate-nebflow-764d8fb0.zh-CN]]"
tags: [design, visual, liquid-glass, glassmorphism, v2]
---

# 液体玻璃设计语言设计稿 v2（只设计不实施）

配套效果图：[[liquid-glass-effect-mockup-v2.html]]（亮/暗双版 · 手机三屏 + 桌面按钮族对照，可滚动可交互）。
前版：[[20261031_082100_liquid-glass-effect__delegate-nebflow-764d8fb0.zh-CN]]（v1，**已被本版取代**）。
前作关联：[[20261030_201846_button-system-monochrome-redesign__delegate-nebflow-fa3d4d6f.zh-CN]]（墨族单色批）· 视觉铁律 `visual-style`。

## §0 一句话目标与一句话配方变化

把作者 10-31 08:43 对 v1 的三条裁定落成可拍板的 v2 效果图与材质配方。

**一句话配方变化（v1 → v2）**：顶栏容器（手机端）退役为「顶缘 88px 滚入渐隐带」
（blur 18 / sat 1.5 / mask 两段式 ease-out + 底色 veil），按钮从 r8–r12 矩形族换代为
**48px 正圆悬浮钮 + 999px 全药丸钮**（blur 10→16 / sat 1.2→1.8，新增上半镜面高光层与
spring 按压回弹），材质与动效参数两档主题全端通用，无顶栏样式仅手机端。

## §1 版本演进记录

| 版本 | 时间 | 状态 | 关键内容 |
|---|---|---|---|
| v1 | 10-31 08:21 | **已被 v2 取代** | 顶栏保留为栏体 + 56px 渐隐尾；按钮为 r12 矩形族 + 36px 圆钮 |
| 作者裁定 | 10-31 08:43 | 终裁（最高优先级） | ① 按钮形态不对：要圆形悬浮钮 + 圆角药丸钮（真液体玻璃）；② 不要明确顶栏：移动端无 header，钮左右悬浮，内容滚到顶边渐隐；③ 按钮全端通用，无栏仅手机端 |
| **v2（本稿）** | 10-31 08:44 | 待作者拍板 | 按裁定重做：手机端无顶栏 + 渐隐带；圆钮/药丸钮族；桌面端仅按钮族换代 |

**v1 被取代处（对读用）**：v1 §2 的 `#header` 栏体改造面、§5.1「顶栏配方」、
§5.4 顶栏两行、效果图 v1 的栏体演示——全部由本稿「渐隐带 + 悬浮圆钮」方案取代；
v1 的卡片/输入条/墨族共存/降级/动效结论沿用（本稿 §5–§7 重申）。
桌面端顶栏：v1 的 `#header` 改造面**整体作废**（作者裁定 ③：桌面维持现状不动）。

## §2 作者裁定原文（10-31 08:43，逐字）

1. 按钮形态不对：要参考图那样——**圆形悬浮图标钮（hamburger 圈）与圆角药丸钮（Select）**，真液体玻璃质感
2. 顶部不要明确的顶栏：移动端无 header 容器，操作钮左右两边悬浮；顶部边缘=内容区，
   文字滚动到顶边时逐渐模糊渐隐（scroll-under edge fade），不是一条 bar
3. 按钮样式全端通用；顶部无栏样式只考虑手机端

参考图：`/Users/kaiyu/.stand/uploads/d7c3cb54-dd90-4f3f-bbf6-e7898c425cff/556425261168916_IMG_1634.jpg`
（暗 · 圆钮+Select 药丸特写）、`.../556425262604166_IMG_1632.jpg`（亮 · Projects 页无顶栏）。

## §3 布局与信息架构

### 3.1 手机端（新范式，取代 v1 顶栏方案）

- **无 header 容器**：顶缘就是内容区；页标题（27px/600）在内容流内，随滚动离开屏幕。
- **悬浮圆钮**：左右各一（左☰菜单 / 右＋新建），48px 正圆，液体玻璃材质，`top:16px`、
  左右 16px，z 层高于渐隐带。
- **顶缘滚入渐隐带（edge-fade）**：高 88px 的 `pointer-events:none` 层，
  `backdrop-filter: blur(18px) saturate(1.5)` + 底色 veil 渐层，整体被
  `mask-image: linear-gradient(#000, rgba(0,0,0,.55) 50%, transparent)` 衰减——
  内容滚入「先糊后没」。**不是栏**：无背景容器、无边框、无固定标题。
- 输入条维持底部悬浮玻璃条（结构不动，发送键改药丸墨玻璃）。

### 3.2 桌面端（裁定 ③：不在本批范围）

`#header` 顶栏容器**维持 10-30 现状不动**；本批仅按钮族换代（圆钮 40px / 药丸高 36，
材质与手机端同一 token 族）。

### 3.3 受影响面（均为既有组件，类名不变；实现批落位处）

- 手机端 `.app-header` / `#header` 容器 —— **退役**（改为渐隐带 + 悬浮钮层）
- `.glass-control` / `.cfg-btn` 族（sapphire.css）—— 换代为圆钮/药丸液体玻璃
- `.cfg-btn-primary`（sidebar.css:1219-1230）—— 墨族 → 墨玻璃药丸
- `#input-bar` 发送键（input.css）—— 改药丸墨玻璃；输入条仅材质升级
- 新 token 以 `--g2-*`（或实现批自选前缀）挂 `stand-tokens.css` 三态双档结构（同 `--btn-ink-*`，勿复制 `--glass-*` 的 data-theme 裂缝，v1 §6 风险条沿用）

## §4 交互状态机（圆钮 / 药丸，全端通用）

| 状态 | 填充/材质 | 投影 | 形变 | 说明 |
|---|---|---|---|---|
| rest | `--g2-fab-bg`（亮 .60 / 暗 .14）+ 上半镜面高光层 | 0 8px 24px + 0 1px 3px 扩散浮影 | — | 高光层 = `linear-gradient(to bottom, specular, transparent 46%)` |
| hover | 提升至 `-hover`（亮 .74 / 暗 .20） | 不变 | — | 只动填充（Δ≥14/255 可辨，同灰族批口径） |
| active | 不变 | 投影收短、高光收敛 | 圆钮 `scale(.88)` / 药丸 `scale(.95)` | spring 180ms 回弹 |
| disabled | 不变 | 不变 | 禁形变 | opacity .5 |
| focus | 墨族沿用 `--btn-focus-ring`（蓝）；玻璃族 2px `--g2-fab-border` 色外描边 | — | — | 见 §8 |

## §5 视觉规格（v2 材质配方全量）

### 5.1 一句话配方

**顶缘渐隐带**（仅手机端，替代顶栏）= `blur(18px) saturate(1.5)` + 底色 veil
（亮 rgba(245,246,248,.92→0) / 暗 rgba(19,22,28,.90→0)）+ mask 两段式 ease-out
（`#000 → .55@50% → transparent@100%`，全高 88px）；
**圆钮/玻璃药丸** = 白玻璃 .60（暗 .14）+ `blur(16px) saturate(1.8)` + 上半镜面高光层
+ 1px 亮白边 + 底部 inset 折射 + `0 8px 24px` 扩散浮影；按压 `scale(.88/.95)` spring 180ms。

### 5.2 token 表（亮档 / 暗档）

| token | 亮档 | 暗档 | 用途 |
|---|---|---|---|
| 圆钮尺寸 | 移动 48 / 桌面 40，正圆；图标 20 / 17 | 同左 | 悬浮操作钮（全端） |
| `--g2-fab-bg` / `-hover` | 白 `.60` / `.74` | 白 `.14` / `.20` | 液体玻璃填充（圆钮/玻璃药丸共用） |
| `--g2-fab-blur` / `-sat` | `16px` / `1.8` | 同左 | 折射（v1 为 14/1.8） |
| `--g2-fab-specular` / `-highlight` | `.90` / `.95` | `.28` / `.35` | 镜面高光层 / inset 顶高光 |
| `--g2-fab-border` | `rgba(255,255,255,.90)` | `rgba(255,255,255,.32)` | 1px 亮边 |
| `--g2-fab-underedge` / `-shadow` | `rgba(0,0,0,.08)` / `.12` | `.40` / `.50` | 底部 inset 折射 / 浮影 |
| 药丸形态 | `r999`；高 44（移动）/ 36（桌面） | 同左 | 主/次按钮统一药丸 |
| `--g2-ink-*`（墨玻璃药丸） | 底 `#111827` 系，高光 `.18`，影 `rgba(17,24,39,.25)` | 底 `#e6edf3` 系，高光 `.50`，影 `.50` | 主操作（墨族语义保留） |
| `--g2-fade-height` / `-blur` / `-sat` | 88px / 18px / 1.5 | 同左 | 顶缘渐隐带（仅手机端） |
| 渐隐曲线 | mask `#000 0% → .55@50% → transparent 100%`；veil 底色 `.92→0`（暗 `.90→0`） | 同左 | 「先糊后没」衰减 |
| 输入条 / 发送键 | `blur(24px) saturate(1.6)` + 微渐层 + 镜面高光；发送键墨玻璃药丸 `h32 r999` | 同结构，暗档值 | 底部输入条 |

### 5.3 与 v1 配方的差异要点

| 面 | v1 | v2（本稿） |
|---|---|---|
| 顶部 | 栏体（白 78→60% 微渐层）+ 56px 渐隐尾 | **无栏**；88px 渐隐带（blur 18 + veil + mask），仅手机端 |
| 按钮形态 | r12 矩形 + 40px 圆钮 + r8 墨族 | **999px 全药丸** + 48/40 圆钮；墨族同改药丸 |
| 折射 | blur 14 / sat 1.8 | blur **16** / sat 1.8 |
| 高光 | 有（v1 首创） | 保留并加强（镜面高光层 + inset 顶高光双层） |
| 按压 | scale .9–.96 | 圆钮 `.88` / 药丸 `.95`（形变随形态细分） |
| 输入条发送键 | r12 墨玻璃 | r999 药丸墨玻璃 `h32` |

### 5.4 墨族共存（沿用 v1 §5.3，结论不变）

主按钮保留 10-30 墨族语义，叠加镜面高光 + 浮影升级为**墨玻璃药丸**；
液体玻璃管圆钮/次级药丸/渐隐带/输入条材质。二者叠加不冲突；10-30 单色批继续有效。

## §6 动效规范（沿用 v1 §4，形变值随 v2 形态更新）

| 项 | 值 |
|---|---|
| 按压回弹 | `transform .18s cubic-bezier(.34,1.56,.64,1)`（spring overshoot） |
| 按压形变 | 圆钮 `scale(.88)`；药丸 `scale(.95)`；投影收短、高光收敛 |
| 材质过渡 | `background-color .18s ease, box-shadow .18s ease` |
| 渐隐带 | 静态无动效（跟随滚动自然显现/消失） |
| prefers-reduced-motion | 按下类声明，形变退化为透明度变化（实现批补） |

## §7 边界与异常 / 改造面与降级

**改造面（作者拍板后）**：

1. `stand-tokens.css` — 新增 `--g2-*`（或批定前缀）双档 token 块
2. 手机端 header 容器退役：内容区直抵顶缘 + 渐隐带层 + 悬浮圆钮层（新组件，语义标签 + aria-label）
3. `.glass-control` / `.cfg-btn` 族（sapphire.css）—— 换代圆钮/药丸液体玻璃 + 补 `:active`
4. `.cfg-btn-primary` — 墨玻璃药丸化 + 补 `:active`
5. `input.css` — 发送键药丸化；输入条材质参数同步
6. **桌面端只动按钮族**（尺寸档 40/36），`#header` 不动

**风险与口径**：

- **历史裁定回写**：10-22「Header 底缘零边界批」与 v1「顶栏渐隐尾批」均被 08:43 裁定取代
  （手机端直接无顶栏）；实现批以本稿为准。
- **渐隐带不是栏**：禁止在渐隐带加背景容器/边框/固定标题（作者裁定 ② 逐字：「不是一条 bar」）；
  渐隐带 `pointer-events:none`，不拦截点击。
- **性能**：渐隐带 = 每屏一个 backdrop 层，与 v1 顶栏同量级，无新增面数；仍禁入列表行。
- **暗档浓度**：白 .14 玻璃 + .28 高光在 #13161c 底上可辨（效果图 C 节已验），若实现批实机偏暗，
  允许在 ±.04 内微调并回写 token。

**降级（微信内置浏览器 / 旧 WebView / X5，不支持 backdrop-filter）**：

- 圆钮/药丸：实色回落（亮 .60→.78 / 暗 .14→.26），高光/投影/按压保留。
- 渐隐带：退化为纯 `linear-gradient` 底色罩（无折射仍有渐隐），视觉不破。
- `@supports` 探测 + 类开关；`mask-image` 双写 `-webkit-` 前缀（效果图已双写）。

## §8 无障碍（沿用 v1 §7）

- focus ring：墨族 `--btn-focus-ring`（亮 #3b82f6 / 暗 #60a5fa）；玻璃族 2px 外描边。
- 按压双通道反馈（形变 + 投影收短）；disabled 用 opacity .5 + 禁形变。
- 圆钮（☰ / ＋）必须 `aria-label`；渐隐带 `aria-hidden`。

## §9 可断言验收点（供 QA 转 Playwright）

1. 手机视口下 header 容器不存在（DOM 断言），渐隐带元素存在且 `pointer-events:none`
2. 渐隐带 computed `backdrop-filter` 含 `blur(18px)` 与 `saturate(1.5)`；`mask-image` 非 none
3. 圆钮为正圆（`border-radius:50%`）且移动档 48px / 桌面档 40px（getComputedStyle）
4. 主/次按钮 `border-radius:999px`；圆钮 `:active` 含 `scale(.88)`、药丸含 `scale(.95)`，时长 ≈180ms（CSSOM 断言）
5. 亮档 hover 前后填充 Δ ≥ 8/255 或边框/投影变化（Δ 基线断言）
6. `--g2-*` token 在 `html[data-theme="light"|"dark"]` 与无属性 prefers-color-scheme 三态均可解析且值不同
7. no-backdrop 降级态：圆钮亮档 alpha ≥ .78 / 暗档 ≥ .26（截图 + 像素采样）
8. 桌面视口下 `#header` computed 样式与 10-30 基线一致（回归断言：顶栏未被波及）

## §10 参考链接

- 作者裁定参考图：`/Users/kaiyu/.stand/uploads/d7c3cb54-dd90-4f3f-bbf6-e7898c425cff/556425261168916_IMG_1634.jpg`、`.../556425262604166_IMG_1632.jpg`
- 效果图：[[liquid-glass-effect-mockup-v2.html]]（v1 效果图 [[liquid-glass-effect-mockup.html]] 已被取代）
- 被取代的前版：[[20261031_082100_liquid-glass-effect__delegate-nebflow-764d8fb0.zh-CN]]
- 前作：[[20261030_201846_button-system-monochrome-redesign__delegate-nebflow-fa3d4d6f.zh-CN]]
- Apple HIG Liquid Glass：https://developer.apple.com/design/human-interface-guidelines/liquid-glass
- 仓内先例：scrollUnderGlass.css（渐隐裙边）、ask.css（渐隐 veil）
