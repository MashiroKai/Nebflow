---
type: spec
status: active
---

# 按钮扁平化规范（flat button spec）

> 作者令（2026-11-05 13:35，逐字）：
> 「我们的按钮扁平化风格要求要落实到各处 不管是hover还是点击 或者按下。」
>
> 本文是**按钮扁平化的唯一规范正文**：禁止项、允许项、六态定义、单点机制、
> 例外清单。可执行面 = `scripts/check-flat-buttons.mjs`（前端门禁链
> `make frontend-gates` + CI），它从本文的「例外清单」节读数据 ——
> **改判据改本文，改豁免也改本文**，脚本只是执行器。

## 1. 扁平的定义

扁平 = **不靠阴影 / 浮雕 / 高光营造厚度**。按钮的"可点击感"来自颜色与形状的
状态变化，而不是"它看起来浮在页面上面"。

### 1.0 判据覆盖哪些控件（按钮面 = 可点击件，不是容器）

机检的「按钮面选择器」按名字段机械判定（切分口径 `-` / `_`），命中即整块进扫描
范围：

| 段 | 归属 | 说明 |
|---|---|---|
| `btn` / `btns` / `button` / `buttons` | 硬标记（无歧义） | `.cfg-btn` / `button.fm-send-btn` / `button` 类型选择器 |
| `toggle` / `toggles` / `switch` / `switches` | **硬标记（2026-11-05 toggle-unify 批补）** | 开关是真实 `<button role="switch">`（`js/toggle.js`），与按钮同属可点击件 —— 厚度道具对它一视同仁 |
| `pill` / `fab` | 软标记 | 其后所有段都是修饰词才算按钮（`.g2-pill-primary` 算，`.cal-pill-listbox` 不算） |

> **补盲的由来（2026-11-05）**：判据段此前只有 `btn/button`，`.nb-toggle` 整族落在
> 扫描面之外 —— `sidebar.css` 组件节那两条 `inset` 内高光从 09-05 一直长到 11-05，
> 全站门禁读数是 0 命中。把 `toggle/switch` 补进段后，同一份脚本立刻在 6 个文件里
> 抓到 6 条命中（`.nb-toggle`、`.bypass-toggle:hover`、`.voice-toggle:hover`、
> `.dvp-zone-toggle:focus-visible`、`.pp-toggle::after`、`.wg-switch-select`），
> 其中三族是**自画的第二套开关**（`.tfl-switch` / `.daemon-autostart` / `.pp-toggle`）
> —— 已全部收编到唯一组件，据此可见「判据不认 = 约定不成立」。

### 1.1 禁止项（命中即红）

| 编号 | 禁止项 | 为什么 |
|---|---|---|
| F1 | `box-shadow` 任何非 `none` 值（含 `inset` 浮雕高光/暗边、含用 `0 0 0 Npx` 画的焦点环影子） | 投影/浮雕 = 三件事里最强的厚度信号；焦点环是"线"，走 `outline` 不用影 |
| F2 | `text-shadow` 任何非 `none` 值 | 字面投影造浮雕感，与扁平冲突 |
| F3 | `background` / `background-image` 里的 `*gradient(` | 上下明暗的渐变 = 曲线造立体（球面/金属面） |
| F4 | `filter: drop-shadow(...)` | 投影的另一种写法，绕不过判据 |
| F5 | `transform` 里的 `translateY(...)` / y 轴 `translate3d` | "按下去物理位移一格"配合阴影 = 实体按键；扁平只允许缩放 |

### 1.2 允许项（必须保留可点击感，别做成不可点的死块）

| 通道 | 用法 | 判据 |
|---|---|---|
| 背景色阶 | 默认 → hover 升一档 → 按下再深一档（`--btn-hover-bg` / `--btn-press-bg`） | 三档必须两两可辨 |
| 描边 | `1px solid` 常态描边；按下/选中可加深或换色 | 描边是"线"，不是"影" |
| 文本色 / 透明度 | 状态的次要通道；禁用走不透明度 | 禁用 ≠ 不可读（见 §2.5） |
| 焦点环 | `outline: var(--btn-ring-w) solid var(--btn-ring)` + `outline-offset` | 键盘导航必须可见（WCAG 2.4.7） |
| 极轻微缩放 | 按下 `scale(var(--btn-press-scale))` = **0.98**（触屏 0.97） | 幅度写死在令牌里；**不许配任何阴影** |

## 2. 六态定义（每个按钮族缺一不可）

### 2.1 默认
族自己的底色 / 描边 / 字色 / 几何。零阴影。

### 2.2 `:hover`
底色升一档。**必须关在 `@media (hover: hover)` 里** —— 触屏没有 hover，不隔离
会在手机上"粘滞高亮"，且触屏用户永远看不到 hover 那档（见 §2.7）。

### 2.3 `:active`（按下）
底色再深一档 **+** `transform: scale(var(--btn-press-scale))`。不位移、不配影。
"hover 有变化、按下没变化"是**断裂**，判红（本批前 46 个登记族里 25 族这个态
是空的）。

### 2.4 `:focus-visible`
`outline: 2px solid var(--btn-ring)` + `outline-offset: 2px`。禁用 `box-shadow`
画环：环是线，影是厚度。`outline: none` 在焦点态里一律不许留。

### 2.5 `:disabled` / `[aria-disabled="true"]`
`opacity: var(--btn-disabled-opacity)`（0.5）+ `cursor: not-allowed`，按下态
的缩放在禁用档里必须撤销。**不额外灰字**：灰字 + 半透明 = 对比度双重衰减，
禁用件仍是给用户读的。

### 2.6 深 / 浅主题各一份
所有值走 `var(--btn-*/--ds-*)` 令牌，双主题由令牌自身解析（`stand-tokens.css`
的按钮三态解析块 + `sapphire.css` 的按钮 token 块）。**按钮规则里禁裸色值**——
写了就是"只做了一档主题"。

### 2.7 触屏（`@media (hover: none)`）
无 hover 面另有加强档：按下幅度放大的 `--btn-press-scale-touch`（0.97）+ 一档
更深的底色 `--btn-press-bg`。触屏用户的反馈只能来自按下，不许缺席。

## 3. 单点机制（本批选定）

选了"**令牌族 + 基类 + 共享态层**"三件套，而不是让各按钮各抄一份 CSS：

| 件 | 落点 | 职责 |
|---|---|---|
| ① 令牌族 | `web/css/sapphire.css` 按钮 token 块的 `--btn-press-*` / `--btn-ring-*` / `--btn-disabled-*` / `--btn-hover-bg` / `--btn-press-bg` / `--btn-dur-*` | 幅度、环宽、禁用档、色阶跳档的**唯一数值出生地**（零散魔法数字不许出现） |
| ② 基类 | `web/css/design-system.css` 的 `.ds-btn` | 六态的**参考实现**：每个态该动哪个属性、动多少，全站照此 |
| ③ 共享态层 | `web/css/flatButtons.css`（index.html **最后加载**） | 把 ① ② 的**行为**一次性补给已登记族：`@media (hover:hover)` 隔离的 hover、`:active` 缩放、`:focus-visible` 描边环、`:disabled` 档、触屏档、减少动效兜底。族文件只留自己的配色与几何 |

**为什么共享态层要最后加载**：它用 `:is(…):state`（特异性 0,2,0）给"族自己没写"
的态补位，与各族的 `:hover/:active` 同档但更晚 ⇒ 只覆盖空位，不抢族自己的配色。

**加一个按钮族进共享层** = ①在 `flatButtons.css` 的登记表里加类名 ②在本文 §4
族表加一行。两处都改了才算登记（漏了族表下一批盘点会把它当"无主族"）。

## 4. 按钮族登记表

见本批交付报告（`<dataRoot>/projects/stand/.stand/docs/flat-button-everywhere__*.md`
§②）的盘点表：46 个登记族的六态现状、处置与证据。族表以那份表为准并随批追加。

### 4.1 随批追加的族

| 批次 | 按钮族 | 落点 | 六态 | 备注 |
|---|---|---|---|---|
| toggle-unify（2026-11-05） | `.nb-toggle`（**全站唯一开关族**） | `web/css/sidebar.css` 组件节（唯一造型）+ `web/css/stand-tokens.css` 的 `--nf-toggle-*` 段（唯一数值）+ `web/js/toggle.js`（唯一行为：`toggleHTML` / `createToggle` / `bindToggle` / `setToggleState` / `setToggleBusy`） | 默认（OFF：中性底 + 1px 描边）/ hover（OFF 档描边递进一档，ON 档保绿）/ ON（实心品牌绿轨道 + 圆钮走满行程 18px）/ `:focus-visible`（`2px outline` + `2px offset`，不用影子）/ `:disabled`（`opacity .5` + `not-allowed`）/ 不确定（`aria-busy="true"` 虚线描边 + 圆钮 `.55`）/ 减少动态（`prefers-reduced-motion` ⇒ 0.01ms 瞬间落位） | 几何 = 作者裁定「A 胶囊滑块」：桌面 40×22 轨道 + 16 圆钮（方案页 `design/toggle-options/` 实测档），手机层仍走独立 iOS 档（`body.mobile-layer`，51×31）。本批收编了最后三族自画开关（`.tfl-switch` / `.daemon-autostart` / `.pp-toggle`），全仓零第二套。**未申请任何例外**：轨道/圆钮零阴影、零渐变、位移只走 translateX。 |
| canvas-video-player（2026-11-05） | `.canvas-video-btn` | `web/css/split.css`（几何/配色）+ `web/css/flatButtons.css` 共享态层（六态）+ `web/js/viewers/video.js`（DOM/行为） | 默认（透明底 + 无边框）/ hover（`--btn-hover-bg`，关在 `(hover:hover)`）/ 按下（`--btn-press-bg` + `scale(--btn-press-scale)`）/ `:focus-visible`（2px 描边环 + 2px offset）/ `:disabled`（画中画在不支持的浏览器上）/ 触屏档（`--btn-press-scale-touch` + 更深底色） | 画布视频自研控件族（播放/静音/画中画/全屏）。进度条与音量条是 `role="slider"` 的滑轨件（不是按钮），零厚度道具走同一口径；本族随 `check-flat-buttons.mjs` 判据全绿，未申请任何例外。 |

## 例外清单

格式：`| 选择器片段 | 禁项号 | 理由 |`。选择器片段按**包含匹配**（写 `.g2-pill` 即覆
盖 `.g2-pill:hover` 等所有派生）。**理由栏为空 = 例外无效 = 红**：没有理由的例外
就是没人管的回退。`（无）` 行是哨兵行（不是例外），用来声明"本批零例外"。

| 选择器片段 | 禁项号 | 理由 |
|---|---|---|
| `.canvas-button` | F1 | 作者令 2026-11-16：仅 canvas 按钮统一 ask/note 阴影；仅允许 canvas 共享规则中的统一阴影令牌，其余按钮仍禁影。 |
