---
type: spec
status: active
---

# 输入面规范（input surface spec）—— 全站唯一输入框造型

> 作者裁定（2026-11-06 07:44，逐字，附手机端实拍一张）：
> 「这个蓝色输入框怎么到处在显示 我不是说去掉吗 就采用我们主对话窗口这种
> 灰色圆角阴影」
>
> 作者裁定（2026-11-06 07:45，补令，逐字）：
> 「输入框的这个样式也要成为统一标准 不可以出现第二种」
>
> 本文是**「输入框长什么样」的唯一落点**。听到/读到「输入框」，答案就在这一页：
> 灰调玻璃底 + 中性灰细边 + 12px 圆角 + 三档柔影，指针态零环、键盘态一圈内缩压灰环。
> 可执行面 = `scripts/check-input-surface.mjs`（前端门禁链 `make frontend-gates` +
> `make input-surface`）。**改判据改本文，改豁免也改本文**，脚本只是执行器。

## 0. 词条（术语表条目）

| 词条 | 别名 | 唯一落点 |
|---|---|---|
| **输入面**（input surface） | 输入框、输入件、文本框、文本域、下拉、field、composer 内层 | ① 令牌 `--nf-field-*`（`css/stand-tokens.css`）② 类/规则 `.nf-field`（`css/inputSurface.css`）③ 机检 `scripts/check-input-surface.mjs` ④ 本文件 |

一句话标准：**灰调玻璃底 + 中性灰细边 + 圆角 + 柔影；蓝只属于键盘可见焦点，
且永不成块。** 全站只此一档造型。

## 1. 唯一形态（参照物 = 主会话输入框 `#input-bar`）

| 维度 | 标准值（令牌） | 参照物 `#input-bar` | 关系 |
|---|---|---|---|
| 底 | `--nf-field-bg` = `--nf-glass-bg`（亮 `#ffffff8C` α .549 / 暗 `#181c26AD` α .678） | 同一个令牌 | **逐值同源**（不是抄的值，是同一条令牌） |
| 边框 | `--nf-field-border` = 1px solid `--nf-field-border-color`（正文色 14% 混色） | `--nf-glass-border-color`（白光边 35% / 暗 6%） | **刻意偏离**：参照物的白细边落在面板上是看不见的；作者要「灰调」，故选正文色混色，两主题自适应、永不为品牌蓝 |
| 圆角 | `--nf-field-radius` = `--ds-radius-xl`（12px） | `--ds-radius-shell`（20px） | **刻意偏离**：20px 落在 32px 高的单行输入上会读成胶囊；12px 是「条状容器」档，形状语言与外壳同族 |
| 阴影 | `--nf-field-shadow` = `--nf-glass-shadow`（顶缘内高光 + `0 2px 8px` + `0 8px 24px`） | 同一个令牌 | **逐值同源** |
| 内距 | `--nf-field-pad-y` 6px / `--nf-field-pad-x` 12px | `--ds-space-1h` / `--ds-space-3` | **逐值同** |
| 字号 | `--nf-field-fs` = `--ds-fs-row`（13.5px）/ 行高 `--nf-field-line` 1.4 | `#input` 内层 13.5px / 1.4 | **逐值同** |

两条「刻意偏离」都有同一类理由：**参照物的某一项是为「68px 高的浮条外壳」调的，
不是为「32px 高的输入件」调的**。比例不匹配时照抄数值得到的不是同一形态，
而是同一组数字；故边框改中性灰、圆角下调一档，其余四项逐值照收。

## 2. 三个变体（同一标准的三种用法，不是三套造型）

| 变体 | 用于 | 拿到什么 |
|---|---|---|
| `.nf-field`（盒式） | 独立输入件：设置项、表单、弹窗输入、卡片输入 | 完整的底 + 边 + 圆角 + 影 + 内距 + 字档 |
| `.nf-field--bare`（裸件） | 嵌在**已成形的容器**里的输入：主会话输入条、ask 输入条、记事本、画布查找条、wiki 搜索胶囊、邮件收件人/主题行、提醒行内输入与描述（`.reminder-inline-input` / `.reminder-inline-desc`）、todo 行标题、移动端搜索框、delegate 弹窗头部模型切换（`.bgagent-model-switch`，裸件 + 下拉双登记） | 无底、无边、无圆角、无影、无环（外壳给材质，内件只留字） |
| `.nf-field--select`（下拉） | 原生 `<select>`：`.ud-select` / `.wg-switch-select` / `.cfg-select` / `.mp-add-select` / `.bgagent-model-switch`（delegate 弹窗头部，裸件成员身份取无底无边） | 盒式 + 原生箭头（`--nf-field-caret`，只由 `currentColor` 组成，不引入第二色） |

**元素级兜底**：`css/inputSurface.css` 的第一条规则按**元素种类**收编全部文本输入件
（`input:not([type])` 与 text/text-search/password/email/number/url/tel/date/time/
datetime-local/month/week、`textarea`、`select`）。新增一个 `<textarea>` 不需要谁
记得加类 —— 它出生就是标准形态。裸件成员在标准文件里以**具名成员表**列出。

## 3. 焦点只有两个通道，无障碍不丢

| 通道 | 表现 | 实现 |
|---|---|---|
| 指针（鼠标点进来 / 触屏点） | 那根灰线压深一档（14% → 26% 正文色混色），**零环、零外发光、零位移** | `:focus` + `html[data-nf-modality="pointer"] … :focus-visible { outline: none }` |
| 键盘（Tab 进来） | 2px 内缩描边环（`outline-offset: -1px`，压在框体自己的边上 = 读作「边框加粗」），色 `--nf-field-ring` = 品牌蓝 45% + 正文色的**压灰混色** | `:focus-visible` + `html[data-nf-modality="keyboard"]`（由 `js/focusModality.js` 写 `data-nf-modality`） |

为什么必须有那一层 JS：浏览器把**文本输入件**的 `:focus-visible` 口径定成「永远是」，
鼠标点一下也算键盘可见焦点 —— 纯 CSS 做不到「点进去没有环、Tab 进去有环」。
`focusModality.js` 只写一个 `data-` 属性（`pointerdown` → pointer，`Tab` → keyboard），
焦点可见性仍完全由 CSS 决定；**它没加载时环照样在**（`:focus-visible` 兜底），
所以键盘可达性不依赖 JS。

对比度（WCAG 1.4.11 / 2.4.11 门槛 3:1）：

| 档 | 环色 vs 输入框底 | 读数 |
|---|---|---|
| 亮 | `--nf-field-ring` vs `--nf-field-bg` 合成底 | 见 §5 实测表（> 3:1） |
| 暗 | 同上 | 见 §5 实测表（> 3:1） |

## 4. 判据（可执行面 = `scripts/check-input-surface.mjs`）

| 判据 | 绿的条件 | 红的样子 |
|---|---|---|
| I1 令牌单一源 | `--nf-field-*` 只在 `css/stand-tokens.css` 定义 | 别的文件里冒出 `--nf-field-xxx:` |
| I2 造型单一源 | 输入面选择器上的 `background*` / `border*` / `border-radius` / `box-shadow` / `outline*` / `backdrop-filter` 只出现在 `css/inputSurface.css` | 组件自写「底 + 边 + 圆角」三件套（第二套在长） |
| I3 数值单一源 | 标准文件里输入面规则的造型值都取 `var(--nf-field-*)` | 标准文件里写死 `#fff` / `8px` / 阴影字面量 |
| I4 蓝不进输入面 | 输入面规则里没有 `--btn-focus-ring` / `--nf-focus-ring` / `--ds-focus-ring` / `--sapphire*` / `--glass-etched-border-focus` 与蓝字面量 | `border-color: rgb(var(--sapphire) / .45)`、`outline: 2px solid var(--nf-focus-ring)` |
| I5 JS 注入面 | 没有对输入件内联写 `border*/background*/borderRadius/boxShadow/outline` | JS 里 `el.style.border = …` 造第二套 |
| I6 豁免纪律 | 例外清单表每行都有具名选择器 + 判据号 + 理由（空理由 = 红）；缺表 = 红 | 「反正就这一次」的自留造型 |

**判据盲点（照实记，不装作没有）**：I2 的输入面判定看「最右复合选择器的元素名或
类名末段（input/textarea/select）」，因此①容器类（`.wg-search` 这类外壳）不在判据内
——本批已把它们里**会变蓝的** `:focus-within` 逐条摘除，但没有机器判据盯着新长的一处；
②以别名类名包装的输入件（外壳 `<div class="my-box">` 内嵌裸 input 且造型写在 div 上）
不在判据内 —— 这一缺口由「新增面必须先在三层登记」的流程纪律兜，写在这里是为了让
下一个人知道往哪儿补，而不是以为门禁万能。

## 5. 实测读数（本批改前 / 改后）

> 装置：`tests/input-surface.spec.mjs`（零端口，真 Chromium，`page.route` 从磁盘服务
> `src/main/resources/web`）。改前读数用 `NEBFLOW_WEB_ROOT` 指向 `git archive` 出来的
> 前一版树，同一夹具、同一视口。

| 面 | 维度 | 改前 | 改后 |
|---|---|---|---|
| 记事本多行输入（`.note-body textarea`，手机 375 档） | 边框 | 2px `rgb(45,102,214)`（蓝） | 0px（裸件，外壳供材质） |
| 同上 | 键盘/指针态 | 两者都是整圈蓝框 | 指针 0 环；键盘内缩压灰环 |
| 设置 API Key 输入（`.secrets-input`） | 边框色 | `rgba(91,127,191,.10)`（蓝灰 etched） | 正文色 14% 中性灰 |
| 同上 | 圆角 / 阴影 | 8px / none | 12px / `--nf-glass-shadow` 三档栈 |
| 主会话输入框（`#input-bar` + `#input`） | 参照物（未改） | 玻璃底 / 20px / 三档栈 | 同（标准以它为基准） |

（完整逐项读数与截图见批报告；本节只留结论性对照。）

## 6. 例外清单

**仅 1 条。** 例外 = 「这不是输入件」，不是「这个输入件的造型不一样」——
后者一律改回标准，不开豁免口子。

| 选择器片段 | 判据号 | 理由 |
|---|---|---|
| `.canvas-ref-select` ⇢ css/split.css | I2 | 它是 `<select>` 但形态是 canvas 右下 48px 悬浮圆钮（音色/参照物选择），材质归 `css/glass.css` 圆钮级浮件档、圆角 50% 是钮的身份；把它改成输入件盒式等于把钮改成框 —— 不是输入面，故登记豁免。 |
| `.ask-dock.closed .ask-input` ⇢ css/ask.css | I2 | 收起态 ask 面的「种子」= 48px 正圆钮（`--ask-seed-size` = fab 同尺寸），border-radius 50% 是钮的身份而非输入面造型（与 `.canvas-ref-select` 同性质）；开放态造型不在本规则（closed 档仅形变种子）。 |

## 7. 落点索引

| 面 | 文件 | 说明 |
|---|---|---|
| 设计令牌单一源 | `src/main/resources/web/css/stand-tokens.css` | `--nf-field-*` 段（唯一出生地） |
| 唯一标准（造型 + 焦点态） | `src/main/resources/web/css/inputSurface.css` | 元素级兜底 + 三个变体 + 两个焦点通道 |
| 输入嫌体 | `src/main/resources/web/js/focusModality.js` | `html[data-nf-modality]`（pointer / keyboard） |
| 机检 | `scripts/check-input-surface.mjs` | `make frontend-gates` / `make input-surface` / `--inventory` / `--json` |
| 实测装置 | `tests/input-surface.spec.mjs` | 改前/改后对照 + 指针态/键盘态对照截图 |
| 规范正文 | 本文件 | 词条、形态、变体、焦点、判据、例外 |

相关：[[glass-spec]]（输入面的底与影来自毛玻璃标准，本文件不重复其配方）；
[[flat-button-spec]]（同族的按钮规范，共用「禁自造厚度」的纪律）；
[[design-tokens-ledger]]（几何刻度正文：圆角/间距/内距/字号）。
