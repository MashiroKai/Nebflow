# 手机端最小可点尺寸 — 规范条目

批：min-tap · 2026-11-06 · 作者令（逐字）：「手机端所有的交互 最小按钮大小就是我们统一的
对话框顶部悬浮按钮的样式」。

- 令牌 = `src/main/resources/web/css/design-system.css` §㉓（`--ds-hit-tap-min` / `--ds-hit-box-min`）
- 机检 = `scripts/check-mobile-tap.mjs`
- 改前读数 = `reports-evidence/todo-mobile-revamp__20261106/foundation-tap/before.json` + 同目录 `README.md`
- 同目录兄弟件 `DESIGN.md` = 液态形变动效语言（**本条目与动效无关**，故独立成篇，见 §0）

---

## 0. 为什么单独成篇（而不是塞进 DESIGN.md）

任务书二选一：「`design/liquid-morph__20261106/DESIGN.md` 的新章节」或「该目录下 `TAP.md`」。
**选 `TAP.md`**：`DESIGN.md` 是「液态形变」这门动效语言的规范，它的判据都围绕**过渡**
（时长 / 曲线 / 属性白名单）；可点尺寸是**几何静态属性**，与过渡无关，写进去会让那份文档
出现第二个主题。同一目录是因为这批的规范产出都落在 `design/liquid-morph__20261106/`
（作者令指定的目录），不是因为它属于动效语言。

---

## 1. 两档语义（一个真值 48，两个用途）

| 档位 | 令牌 | 值 | 管什么 | 谁必须达标 |
|---|---|---|---|---|
| 视觉档 | `--ds-hit-tap-min` | 48px | **看得见的那块面**的边长 | 所有可点元素的可见盒 |
| 命中盒档 | `--ds-hit-box-min` | 48px | **可点区域**的边长（含外扩） | 所有可点元素的可点区 |

两档分开的唯一目的：允许「视觉比 48 小的图标钮」合法存在（图标 20px 放在 28×24 的玻璃面里
是正常的视觉语言），但**不许**因此把触控面积也缩到 28×24。

**判定是一句话**：视觉达标 ⇒ 直接合规；视觉不达标 ⇒ 必须靠外扩把命中盒补到 48×48 才算合规。

### 1.1 取值出处（实测复核，不照抄任务书）

任务书给的数字是 48，但要求「若复核发现 48 并非真值，以实测为准」。复核结论：**48 成立**。

| 步骤 | 事实 | 锚点 |
|---|---|---|
| ① 定位「对话框顶部悬浮按钮」 | 聊天根顶缘两颗圆钮 = `#mobile-fab-layer` 内 `.mh-chrome-btn.g2-fab` | `js/mobile/chatChrome.js:258`（任务键）、`:274`（搜索键） |
| ② 找边长声明点 | 手机档唯一声明 `width:48px; height:48px` | `css/glass.css:717-720`（`body.mobile-layer .g2-fab`） |
| ③ 排除桌面腿污染 | 桌面腿是 `--cv-size-circle` 的 40px 回退 | `css/glass.css:554-556` |
| ④ 真链实测 | 同族 `.mobile-back-btn.g2-fab` = **48×48**；`.tfl-fab-add.g2-fab` = **48×48** | `reports-evidence/todo-mobile-revamp__20261106/shots-before/readings-before.json` |
| ⑤ 不新造数值 | 视觉档**别名**到既有单源 `--ds-hit-tap-lg`（48px，§⑮ :417），经手机桥 `--mtap-lg` 供全族悬浮圆钮消费 | `design-system.css:417`、`mobileLayer.css:328` |

⇒ `--ds-hit-tap-min: var(--ds-hit-tap-lg)`；`--ds-hit-box-min: var(--ds-hit-tap-min)`。
**新批若要改真值，改 `--ds-hit-tap-lg` 一处即可**（两档同时跟随，不会漂）。

### 1.2 与既有热区档的关系（不许混用）

| 既有槽 | 值 | 适用面 | 本条目怎么说 |
|---|---|---|---|
| `--ds-hit-row` | 44px | 列表行最小高（WCAG 2.5.5 触控下限） | **行高**仍是 44（行不是「可点元素」，是容器）；行内的**按钮**按本条目 48 |
| `--ds-turn-hit` | 44px | 桌面指针档的 `::after` 外扩目标 | 桌面腿**不动**；手机层内以后者为准的元素，改面时改读 `--ds-hit-box-min` |
| `--mhit` / `--mtap` | 44px | 手机层历史热区桥 | 新代码不得再新增 44 档消费点；旧面改到时一并迁移 |
| `--mtap-lg` / `--mchrome-btn` | 48px | 手机悬浮圆钮族 | 与本条目的视觉档**同值同源**，天然达标 |

手机腿的 `.ds-turn-btn` 本体已是 `--ds-turn-btn-size: var(--mtap-lg)` = 48
（`mobileLayer.css:2867`），`::after` 的 `min(0px, …)` 在 48 本体上收到 0 ⇒ 天然达标，不需要外扩。

---

## 2. 违规判据（机检逐字实现）

> 元素 E 违规 ⟺ `min(E.w, E.h) < --ds-hit-tap-min` **且** `min(E.hitW, E.hitH) < --ds-hit-box-min`

- `E.w / E.h` = 元素自身盒的 CSS 像素边长（`getBoundingClientRect`）。
- `E.hitW / E.hitH` = `max(自身盒, 所有「定位 absolute/fixed + 有内容 + pointer-events 非 none
  + 不是 display:none/visibility:hidden」的 ::before/::after 盒)`。**外扩按外扩算**。
- 外扩取值解析不出（`auto` / 百分比算不出 / 伪元素缺失）⇒ 该元素记「**无法判定**」，
  **既不当 0，也不当合规**（静默放过是这类闸最典型的失效方式）。
- 两条同时不达标才算违规；只有视觉不达标 + 命中盒达标 = §3 的**合规写法**，不报违规。

---

## 3. 合规写法（视觉小、命中盒大）

### 3.1 首选：`::after` 透明外扩（零布局影响）

```css
/* 视觉 28×24 的图标钮，命中盒补到 48×48 */
body.mobile-layer .my-icon-btn {
  position: relative;            /* 伪元素定位基准，必须 */
  width: 28px; height: 24px;     /* 视觉档（可小于 48） */
}
body.mobile-layer .my-icon-btn::after {
  content: '';
  position: absolute;
  border-radius: 50%;            /* 圆形钮才加；方形钮会造出「圆角命中盒」 */
  /* 本体 < 48 ⇒ 负 inset 外扩；本体 ≥ 48 ⇒ 收到 0（不外溢压邻居） */
  inset: min(0px, calc((100% - var(--ds-hit-box-min)) / 2));
}
```

`min(0px, …)` 是**必须**的：固定负 inset 会让本体已经达标的元素向外溢出，
把邻居的点击抢走（`design-system.css:689` `.ds-turn-btn::after` 同式先例）。
**横向或纵向单独不达标**时，用 `top/bottom` 与 `left/right` 分开写，别用一个 inset 全扩。

⚠ 外扩受**父容器**裁剪（`overflow:hidden`）。两个相邻小钮同排时，各自的圆环会互相覆盖，
后渲染的赢 —— 所以相邻元素之间至少留 8px，或改成 `padding` 撑行高（见 3.2）。

### 3.2 次选：`padding` 撑到 48（命中盒与视觉盒同大）

```css
body.mobile-layer .my-chip {
  box-sizing: border-box;
  min-height: var(--ds-hit-box-min);   /* 命中盒 = 视觉盒 = 48 高 */
  padding-block: 0;                    /* 高度由 min-height 定，padding 只调横向 */
  padding-inline: var(--ds-space-3);
  display: inline-flex; align-items: center;
}
```

代价：视觉面变高（chip 从 26 变 48），列表密度下降。**只在「视觉允许变大」时用**；
需要保持小视觉的，一律走 3.1。

### 3.3 禁用写法

- `transform: scale()` 放大 —— 视觉与被点击区域一起变，还可能糊。
- 给整行挂 `onclick` 让小按钮「蹭」行面积 —— 行内其它可点元素会被抢走点击。
- 透明遮罩层盖在多个小钮之上 —— 一个遮罩只能落一个点击目标，多钮互相偷。
- `pointer-events` 伪造（父吃事件再 `elementFromPoint` 派发）—— 命中盒仍按视觉盒判，机检照红。

---

## 4. 作用域

**作用域 = `body.mobile-layer` 内的所有可点元素**（手机档唯一总开关；桌面零命中）。

「可点元素」的机械定义（机检与人工评审同一条）：

```
button | a[href] | [role=button] | select | textarea | input（含 type=checkbox/radio/range）
| [tabindex]:not([tabindex="-1"]) | 带 cursor:pointer 的元素 | 带 onclick/事件绑定的自定义块
```

不含：纯装饰伪元素、`aria-hidden="true"` 的视觉件、`display:none` / `visibility:hidden` /
`opacity:0` 的隐藏态、`disabled` 且无可点行为的元素。

---

## 5. 豁免判据（明确，不许暧昧）

**只有一类豁免：文本流内的纯文本链接（prose link）。** 其余一律不豁免。

判据必须**同时**满足以下 4 条，缺一条即不豁免：

| # | 判据 | 机械测法 |
|---|---|---|
| E1 | 在文本流里，不是独立控件行 | `getComputedStyle(el).display === 'inline'`，且父元素是文本容器（`p` / `li` / `span` / `label` / 带非链接文本节点的 `div`） |
| E2 | 没有自己的盒 | 计算样式同时满足：`background-color` 透明、`border-width` 全 0、`border-radius` 0、`padding` < 4px、无 `box-shadow` |
| E3 | 不与别的可点元素相邻 | 与最近的其它可点元素中心距 ≥ 24px（同行 NPC 不构成误触） |
| E4 | 语义就是「正文里的跳转」 | 文案是句子/段落的一部分，删掉它句子不成句 |

→ 满足 E1–E4 ⇒ **视觉档与命中盒档都不判**（依据：WCAG 2.5.8 的 inline exception ——
正文行内链接若被强撑到 48×48 的命中盒，会横向覆盖上下两行文字，抢走文本选择与换行点击，
是**净负收益**）。

**不豁免（逐条钉死，防止下一批拿「它也是文字」来豁免）**：

| 形态 | 结论 | 理由 |
|---|---|---|
| 行内 chip / 标签 / 徽标（有自己的背景/描边/圆角盒） | **不豁免** | 它已经「从文字里长出了盒」= 自成一个控件；判据 E2 直接不满足。必须按 §3 补命中盒 |
| 开关（`.nb-toggle`，视觉 51×31） | **不豁免** | iOS 标准视觉宽高不构成豁免理由；高度须补到 48 |
| 输入件（`input` / `textarea`，单行视觉可低至 16px） | **不豁免命中盒** | 视觉高度由行盒决定（可变），但可点区必须 ≥48：靠容器 `padding` 或 `min-height: var(--ds-hit-box-min)` 补 |
| 图标钮（`28×24` 一类） | **不豁免** | 这是本条目最主要的整改对象，按 §3.1 补 `::after` |
| 卡片/行的「整行可点」 | **不豁免**（且**必须** ≥48 高） | 整行可点是命中盒的另一形态，行高 ≥48 即达标；行内若还有小钮，小钮自己的命中盒另算 |
| 纯文本（无 `a` / 无事件） | 不在扫描面 | 不可点，不是靶 |

---

## 6. 机检用法

```bash
node scripts/check-mobile-tap.mjs                           # 门禁：默认读改前基线读数
node scripts/check-mobile-tap.mjs --readings <path.json>    # 换读数（改后腿 / 新面）
node scripts/check-mobile-tap.mjs --json                    # 机器读
node scripts/check-mobile-tap.mjs --max-violations 17       # 过渡期「只许不增」（禁放宽判据）
```

退出码：`0` = 绿；`1` = 红 / 主口径不可用（读数缺失、令牌缺失、外扩不可解析）。

**两路输入，缺一必须报「无法判定」**：

- **(a) 静态腿**：扫 `css/` 里 `body.mobile-layer` 作用域内声明了 `<48px` 边长的可点类
  （`mobileLayer.css:3252` 的 `.mh-search-bar` 44px 一类），以及 `js/` 里凭空生成的类名在
  `css/` + `index.html` 内**零命中**的（无样式面）。静态**永远算不出最终几何**
  （级联 / `var()` / `calc` / 内容撑开 / JS 改写）⇒ 只产出「无法判定」，不产出合规结论。
- **(b) 运行态腿（主口径）**：读 `readings-before.json` 形状的几何读数
  （`[{selector, w, h, hitW, hitH}]`，`hitW/hitH` 已含伪元素外扩，解析不出写 `null`）。

---

## 7. 改前读数（本批实测，`before.json` 同源）

375×812 真链、手机端「任务」页 + 新建抽屉，可点元素 **19** 条：
**合规 2 · 违规 17** · 运行态无法判定 0 · 静态腿无法判定 49 条（16 条 CSS 声明 + 33 条无样式面 JS 类名）。

违规 17 条逐条见 `reports-evidence/todo-mobile-revamp__20261106/foundation-tap/README.md`。
收敛目标：0 条（实现节点按 §3 整改后，用同一脚本读改后腿读数，违规必须为 0）。

---

## 8. 改值路径

| 要改什么 | 改哪里 | 会连带改什么 |
|---|---|---|
| 真值 48 → 别的值 | `design-system.css:417` `--ds-hit-tap-lg` | 全族悬浮圆钮边长 + 本条目两档（`--mtap-lg` / `--mchrome-btn` / `--mpage-fab-clearance` 派生链）+ 机检阈值 |
| 只放松/收紧命中盒 | `--ds-hit-box-min` | 只有机检阈值与消费面外扩，不动视觉 |
| 新增豁免类型 | 本文件 §5 表 | `scripts/check-mobile-tap.mjs` 的扫描面函数（两处必须同改，否则规范与机检分叉） |
