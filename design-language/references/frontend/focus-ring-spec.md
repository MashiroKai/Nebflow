---
type: design-spec
status: approved
date: 2026-11-22
title: 焦点高亮框规范（单规则 · 单色源 · 内缩 · 禁光晕 · 全面圆角标准 · 框完整显示）
branch: feat/focus-ring-impl → feat/focus-ring-round2（round2 2026-11-22：圆角落标准、方角登记废除、框完整显示）→ feat/round-corner-sweep（round3 2026-11-22：全面圆角、全树画框面清扫、新闸 G1–G4 + P-6）
supersedes: [[focus-ring-unify-design__20261121_185700]] 的实现细则部分（设计件保留为决策记录）
related: ["[[keyboard-support-audit__20261121_185700]]", "[[input-focus-ring__20261105_213238]]", "[[design-language-recon__20261102_234917__dlc-frontend]]"]
evidence: 见 §7（round1 对照板 + 运行时读数）、§8（round2 整框对照板 + 真实实例读数）与 §9（round3 全树圆角 改前/改后对照板 + 逐格读数；vault assets/20261122_round-corner-sweep/）
---

# 焦点高亮框规范

**结论先行：全站键盘焦点指示器只有一种 —— `:focus-visible` 下 `outline: var(--nf-focus-ring-w) solid var(--nf-focus-ring)`、`outline-offset: calc(var(--nf-focus-ring-w) * -1)`（内缩 2px），颜色只有一个源 `--nf-focus-ring: #9ca3af`（灰系，亮暗同值，作者 2026-11-21 批 B 案）。禁止焦点 `box-shadow` 光晕、禁止字面量焦点色、禁止无配对环的 `outline: none`。**

本文是**判据正文**，三份机器面跟着它走：
- 静态闸 `scripts/check-focus-ring.mjs`（F1–F8，读 `src/main/resources/web/css/**`）；
- 运行时闸 `scripts/check-focus-ring-runtime.mjs`（P-1–P-6，真 Chromium 差分像素 + 计算样式普查）；
- **全面圆角静态闸 `scripts/check-round-corners.mjs`（G1–G4，全树画框面）**；
- 豁免台账 `scripts/focus-ring-exemptions.json`（只承载「无环」豁免；脚本内零硬编码豁免）。

## 1. 本批之前（问题面）

改前实测与源码证据在设计件 [[focus-ring-unify-design__20261121_185700]] §1：焦点色 7 套
（`--nf-focus-ring` / `--btn-ring` / `--ds-focus-ring` / `--dropdown-focus` / `--nf-field-ring` /
`--nf-toggle-ring` 五套别名 + `rgb(var(--sapphire)/α)` 与 `#hex` 字面量）、几何内外混用
（外扩 2px / 内缩 1–3px / 无 offset）、焦点光晕 14 处、裸去环 15 处。

本批把它们收成一个规则、一个色源，并**删光**上述别名与光晕。改前/改后真实像素对照见 §7。

## 2. 规范

### 2.1 单一规则（唯一合法写法）

```css
/* css/keyboard.css —— 全站唯一色源 + 唯一几何源 */
:root {
  --nf-focus-ring: #9ca3af;   /* 灰系，亮暗同值（作者 2026-11-21 批 B 案） */
  --nf-focus-ring-w: 2px;
}
:focus-visible {
  outline: var(--nf-focus-ring-w) solid var(--nf-focus-ring);
  outline-offset: calc(var(--nf-focus-ring-w) * -1);   /* 内缩：压在组件自己的边上 */
}
```

- 组件侧**只允许写同一对声明**（同一令牌、同一内缩值）。要改颜色/宽度 ⇒ 改令牌，不写新值。
- 内缩的理由：外扩环与框体之间必有缝（读作「外面又一圈」）；内缩环不占盒、不被
  `overflow:hidden` 的祖先（树行 / 叠卡 / 胶囊条）裁切。
- `--nf-focus-ring` 与 `--btn-focus-ring` **分开**：后者仍是链路蓝（`friends.css` 一类文字链
  在用），焦点不借它。这也是本批不复用旧别名的原因。

### 2.2 禁止项（R 系列，逐条有静态判据）

| 编号 | 禁止 | 判据 |
|---|---|---|
| R-1 | 焦点环写第二套颜色/宽度/几何 | F1 / F2 |
| R-2 | 用 `box-shadow` 画焦点环或光晕 | F3 |
| R-3 | 焦点规则的 outline / box-shadow 里出现字面量（`rgb(var(--sapphire)/α)`、`#hex`、`rgba()`） | F1 |
| R-4 | `outline: none/0` 于焦点规则且**同文件同基座无配对环**、又不在台账 | F4 |
| R-5 | 新增 `--btn-ring*` / `--ds-focus-ring` / `--dropdown-focus` / `--nf-field-ring*` 引用 | F6 |
| R-6 | 给可交互件方角造型（0 圆角，或 <6px 的非微格档） | F7 / P-5 |
| R-7 | 在焦点规则里写 `border-radius`（= 聚焦瞬间改组件形状，圆钮变方钮） | F8 |

### 2.3 例外（必须进台账，逐条具名理由）

只有两类允许「无环」：
1. **不是控件**：焦点陷阱宿主（`.nf-trap-root`）、monaco 编辑器容器（`#config-editor`）——
   控件在它里面，给盒子画环等于画两层。
2. **作者明令取消**：主会话输入条内层 `#input` / `.fm-input`（作者令 2026-11-03 10:11 ④
   「输入区键盘态不再给输入区加高亮」）、指针模态复位（`html[data-nf-modality="pointer"]`
   下的输入件）。

台账 `scripts/focus-ring-exemptions.json` 是这些例外的**唯一机器源**；F5 钉住「只减不增」，
并核对每条在对应 CSS 里仍能找到该选择器（僵尸豁免 = 红）。round2 起台账**只承载「无环」
豁免**：方角不再靠登记（见 §2.6），台账若还留 `squareCorners` 段 = 红。

### 2.4 颜色与对比（实测读数，不是估算）

| 主题 | 令牌值 | 对规范底色（§2.4 基准） | 实测承载面 | 实测「环对相邻像素」 |
|---|---|---|---|---|
| 亮 | `#9ca3af` | 对 `#ffffff` **2.54:1**（低于 3:1，作者知情接受） | 2.35:1（产品实际底色被 veil/玻璃抬深） | 1.07–2.54:1 |
| 暗 | `#9ca3af` | 对 `#0d1117` **7.45:1** | 7.14:1 | 1.06–7.06:1 |

读法：**门槛按作者裁定分档** —— 暗 ≥3:1 硬门槛；亮 ≥2.5:1（作者 2026-11-22 下调，
知悉低于 3:1）。P-2 的门禁基准是「令牌对规范底色」这一档（作者批准该值时的依据）；
另外两个读数逐件留痕：承载面 < 门槛、或「环对相邻像素」<3:1 的每一件都必须在运行时
读数里带注解（`deviations` / `subThreeAnnotated`），不许静默通过。

已知偏差（本批实测，供作者裁量）：
- 亮档**饱和填充控件**（绿色发送键一类）：内缩灰环与该控件自身的底色对比只有 1.07:1 ——
  环在控件内部几乎只剩「色相/彩度差」可辨。改前的蓝环在同一位置也是 ~1.05:1，**不是本批
  引入的回归**，但灰比蓝更弱。
- 亮档**玻璃面**（设置弹层等）：实测 2.07–2.35:1，低于 2.5 档。
- 若作者要求「实测也过 2.5」，把令牌调到 `#868c94`（对纯白 3.58 / 对实测玻璃 2.73 /
  对暗底 5.32）即可；本批不擅改作者裁定的颜色。

### 2.5 玻璃与裁切

- 环画在组件自身 border-box 内（内缩），**不依赖**父级玻璃容器的 backdrop；毛玻璃容器
  （`glass-spec.md`）内的控件不需要额外层。
- 已知教训「backdrop root 断层」（父级 `backdrop-filter` 形成新的 backdrop root）本批
  **实测覆盖**：P-4 在带 `backdrop-filter` 的具名玻璃面内取样 104 件，0 失败。
- 例外：聚焦会改变自身布局的件（浮动待办岛在聚焦时扩张）上，「越界像素」无法与「整体
  位移」区分 ⇒ P-3 弃判并在读数里具名（本批 2 件），不硬判成红也不静默当绿。

### 2.6 全面圆角标准（round3 升格：全树画框面，不只是环基座）

**作者令（逐字，按时间）——**
- 2026-11-22 08:03：「我说了要圆角的框」「而且像主会话这种框目前还无法完全显示 我要能
  完全显示」「并且把这个落为标准」；
- 2026-11-22 10:17：「对 但是我看到还是存在部分不是圆角的 **得全面圆角** 而且**成为规范
  和自动化/lint检查**」。

round2 把「登记制」换成了几何判据，但判定面只到**焦点环基座**（可交互件）。作者的复查意见
指出了这个缺口：屏幕上画方角的并不都参与 Tab 序 —— 徽章、chip、统计卡、提示条、分隔面、
媒体占位、状态点都在画盒子，F7/P-5 一个也看不见。round3 因此把标准**升格为「全树画框面」**：

> **凡在本树里画出一个可见盒子（背景 / ≥2 条可见边 / 投影）的规则，必须给出圆角档位；
> 没有「这件可以方」的登记出口，唯一的结构性例外是「贴边满幅」（§2.6.3）。**

#### 2.6.1 档位表（不改：令牌族是唯一阶梯）

| 件类型 | 圆角 |
|---|---|
| 画框面（按钮 / 行 / 格 / 卡片 / 徽章 / chip / 提示条 / 媒体占位 / 分隔面 / 输入面） | **6–10px**（`--ds-radius-chip` 6 / `--ds-radius-input` 8 / `--ds-radius-button` 10 / 卡档 `--ds-radius-card-*`） |
| 胶囊 / 圆形造型件（既有形态档） | `--ds-radius-pill`（999px）/ `50%`，不受 6–10px 约束 |
| 微格（短边 ≤12px：热力格、状态点、光标块） | `--ds-radius-thin`（2px）—— 6px 在 11px 盒上被浏览器钳到 50%，格子变圆点，热力图从「密度」读成「珠串」；这是**几何事实**，不是豁免 |
| 标记类（自身无几何、圆角跟随宿主） | `border-radius: inherit`（伪元素覆盖层 / 裸输入内芯 / 卡内文标） |

三条硬纪律（round2 保留，仍然生效）：
1. **环半径 = 组件半径**：焦点环由 `outline` 画，`outline` 自动跟随 `border-radius`；
   **焦点规则里禁止写 `border-radius`**（R-7/F8）—— 实测反例：`#deck-fab:focus-visible
   {border-radius:6px}` 让圆形钮在键盘聚焦时变成圆角方钮。
2. **不写方角字面量**：`border-radius: 0` = 红（G2）。症状性写法只有一个出口：`inherit`。
3. **圆角只能来自令牌族**：字面量 `6px`/`8px`/`999px` = 红（G3）—— 与 F1/F2 的「单源」
   口径一致，第二套阶梯一旦存在，下一个人就会再写第三套。
4. 组内部件圆角后，行间分隔线必须是**内缩**线（`::before`/`::after` 定位线，左右让出内距），
   不能留整宽 `border-bottom` —— 圆角行上的整宽线会在行角处转弯，读作弧线。

#### 2.6.2 判定面与判据（全树）

| 判定面 | 判据 | 机器面 |
|---|---|---|
| 静态：任何「画框」规则（背景非透明 / ≥2 条可见边 / 有投影） | 必须有圆角声明或可继承上下文（同基座/同类族前缀） | `check-round-corners.mjs` G1 |
| 静态：写成方角（0 / 0px） | 红（无登记出口） | G2 |
| 静态：圆角写成字面量 | 红（必须走令牌族；`inherit` / `50%` 放行） | G3 |
| 运行时：页面上**每个**画得出盒子的元素（含非交互面） | 计算样式圆角 ≥6px，或胶囊/圆形，或微格（短边 ≤12px）的 2px 档 | `check-focus-ring-runtime.mjs` P-6 |
| 运行时：贴边满幅面 | 按其几何豁免（§2.6.3），并在读数里**逐条具名** | P-6 的 `structural` 段 |

判定面里被明确排除的（不是豁免，是「没有盒形可圆」）：`@keyframes` 步进选择器、
`::selection` / `::placeholder` / `::marker` / `::backdrop` 这类文本级伪元素、焦点规则
（圆角归基座，见纪律 1）。清单写在静态闸的 `NOT_A_BOX` 里，逐条带理由。

#### 2.6.3 结构豁免 = 几何判据（贴边满幅）

唯一的结构性例外是**盒边与视口边重合**的面：它的圆角会被视口裁掉，画了也看不见。
判据是几何的，不是「谁登记谁方角」：

- 运行时：`rect.left ≤1 && rect.right ≥ vw-1`（左右贴边）或 `top ≤1 && bottom ≥ vh-1`
  （上下贴边），且计算圆角为 0 —— 这类件进 P-6 的 `structural` 段，逐条带 `why` 具名。
- 静态：`html` / `body` / `#app` / `.app-shell` / `.flowmap-card`（`flex:1` 填满标签页）/
  `body.mobile-layer .fm-context-menu`（`position:fixed; top/left/right:0` 全屏动作表）——
  每条在 `STRUCTURAL_FULLBLEED` 里写明「哪条边就是视口边（或面板内壁）」。
- 该清单是**结构类别**，不是 round1 那种 per-item 方角登记：新增条目的门槛是「这个盒子的
  边就是视口边」，不是「这一件看起来方一点更好看」。

> 为什么登记制必须死：round1 的六条方角登记里，`.set-disclosure` 这种**登记机制之外**的方角
> 件照样被实测抓到（0px）。任何「人来登记才被豁免」的机制都必然漏，几何判据不会。

| 件类型 | 圆角 |
|---|---|
| 可交互件（按钮 / 行 / 格 / 输入面 / 披露行） | **6–10px**（`--ds-radius-chip` 6 / `--ds-radius-input` 8 / `--ds-radius-button` 10） |
| 胶囊 / 圆形造型件（既有形态档） | `--ds-radius-pill`（999px）/ `50%`，不受 6–10px 约束 |
| 微格（短边 < 12px：热力格、徽标点） | `--ds-radius-thin`（2px）—— 6px 在 11px 格上被浏览器钳到 50%，格子变圆点，热力图从「密度」读成「珠串」；这是**几何事实**，不是豁免 |
| 标记类（自身无几何、圆角跟随宿主） | `border-radius: inherit` |

> 为什么登记制必须死、几何判据才可执行：静态闸不做级联，但**运行时闸可以做** —— P-5/P-6
> 直接量计算样式（真像素上的 `getComputedStyle(el).borderRadius`），这是「元素到底是不是
> 方角」的权威读数；静态闸只用「同基座/同类族前缀」做近似，负责在**写代码的当下**拦住
> 新方角。两道闸的分工是「写的时候拦住、跑起来还能查」。

### 2.7 框必须完整显示（round2 新增：作者令「像主会话这种框……我要能完全显示」）

**判据：一个框（组件外框 / 容器外壳框 / 环）在评审图上必须是完整的矩形外沿 —— 不许被
裁切、不许变形、不许只截到一条内层文字带。** 这条同时落在产品侧与证据侧：

- **产品侧**（真实渲染）：环是内缩的（`outline-offset ≤ −环宽`），所以**不会被任何祖先的
  `overflow` 裁掉**；容器框本体（`#input-bar` 一类 20px 圆角外壳）整框必须在视口/面板内 ——
  round2 在运行中的宿主要实例实测：composer 框 548×78 @ (310,812) 于 1440×900 视口，
  唯一「裁切祖先」是 `body{overflow:hidden}`（= 视口本身），四边余量均 >10px（读数见 §7.3）。
- **证据侧**：对照板的每一格必须 `frameComplete=true` —— 该格裁剪区完整包含件的外框 +
  10px 边距；采集器按**容器框本体**取图（`frameSel`），聚焦态由内层控件制造（`focusSel`），
  合成时**等比缩放**（单一比例因子，只缩不裁）。任一格拍不全即按失败报出，不当成已采到。
- 大框的评审单位是**框本体**，不是它里面的透明控件：主会话 composer 的控件 `#input` 是
  作者令 2026-11-03 ④ 的无环豁免件，照它裁图只能得到一条没有外框的文字带（round1 对照板
  第 5 行的实况；round2 已改为整框入格）。

## 3. 静态判据（`scripts/check-focus-ring.mjs`）

| 判据 | 规则 | 通过条件 |
|---|---|---|
| F1 颜色单源 | `:focus` / `:focus-visible` 规则 outline 的颜色 | 只能是 `var(--nf-focus-ring)`；`Highlight` 仅限 `@media (forced-colors: active)` 内 |
| F2 几何单源 | 焦点规则的 outline 宽度 / `outline-offset` | 宽度 `var(--nf-focus-ring-w)`（等价值 `2px`）；offset 只能是内缩 `calc(var(--nf-focus-ring-w) * -1)`（等价值 `-2px`） |
| F3 无光晕 | 焦点规则的 `box-shadow` | 只能是 `none` |
| F4 无裸去环 | `outline:none/0` 且同文件同基座无配对环 | 必须在台账具名登记 |
| F5 豁免只减 | 台账条数 / 条目有效性 | ≤ `_baseline`（4）；每条理由 ≥8 字；选择器在对应 CSS 里仍存在；**台账不得再带 `squareCorners` 段**（round2 废除） |
| F6 别名收编 | `--btn-ring*` / `--ds-focus-ring` / `--dropdown-focus` / `--nf-field-ring*` | 全树 0 处（含定义处） |
| F7 圆角标准 | 每个画环的基座（141 个）在整棵 CSS 树里的生效圆角 | ≥6px；胶囊/圆形（999px / 50%）放行；微格（同族声明短边 <12px）允许 ≤4px；**查不到任何圆角声明 = 红**。生效值按 `index.html` 的真实加载序 + 行号近似级联（含 `@media` 内规则），不做特异性计算 |
| F8 环不改形状 | 焦点规则里的 `border-radius` | 0 处（环半径 = 组件半径）。焦点规则写半径 = 聚焦瞬间改组件形状 |

用法：`node scripts/check-focus-ring.mjs`（门禁）、`--json`（机器读）、`--inventory`（盘点）。

F7 的**已知边界**（不是豁免，是判据的观测面）：裸元素基座（`textarea` 这类）与 ID 主体基座
（`#send-btn` —— 它的形状来自元素上的类）静态不可判，跳过；它们由 P-5 在运行时按计算样式判
（见 §6 盲点①）。

### 3.2 全面圆角静态闸（`scripts/check-round-corners.mjs`，G1–G4）

| 判据 | 规则 | 通过条件 |
|---|---|---|
| G1 画框必圆 | 任何画框规则（背景非透明 / ≥2 条可见边 / 有投影 / 可见 outline） | 必须有 `border-radius` 声明，或查得到「同基座 / 同类族前缀（BEM 修饰、复合类）」已声明的圆角上下文 |
| G2 禁方角字面量 | 画框规则的 `border-radius` | 不得为 `0` / `0px`（round1 的方角登记已废除，无任何出口） |
| G3 档位单源 | 画框规则的圆角值 | 必须来自 `--ds-radius-*` 族（`inherit` / `50%` 放行）；字面量 px = 红 |
| G4 结构豁免具名 | 判定面之外的结构面 | 只有「贴边满幅」类别（`html` / `body` / `#app` / `.app-shell` / `.flowmap-card` / 移动端全屏动作表），每条带几何理由；该清单是**结构类别**而非 per-item 登记 |

用法：`node scripts/check-round-corners.mjs`（门禁）、`--json`、`--inventory`（全部画框规则 +
圆角来源盘点）、`--root=<dir>`。它已注册进 `scripts/run-checks.mjs` 的 `static`/`frontend` 两档。

判定面（`NOT_A_BOX`，逐条带理由）：`@keyframes` 步进选择器、文本级伪元素
（`::selection` / `::placeholder` / `::marker` / `::backdrop` / `::cue`）、焦点规则（圆角归基座）。

## 4. 运行时判据（`scripts/check-focus-ring-runtime.mjs`）

测量方法是**同一像素有/无指示器差分**（WCAG「焦点外观」口径）：同一裁剪区先失焦拍一帧、
再聚焦拍一帧，逐像素差分 = 指示器真实画出来的像素。不猜环色 —— 半透明控件、玻璃混色、
抗锯齿都会让绝对色匹配失准。

| 判据 | 规则 | 通过条件 |
|---|---|---|
| P-1 可见 | 键盘模态下可 Tab 到的可交互件 | `:focus-visible` 命中、outline 宽 2px、style solid、差分像素 > 0 |
| P-2 对比 | 环色对承载面 | 暗 ≥3:1；亮 ≥2.5:1；两个实测读数（承载面 / 相邻像素）<3:1 时逐件带注解 |
| P-3 不裁切 | 差分像素包围盒 | 完全落在组件 border-box 内（1px 抗锯齿容差）；`outline-offset` 必须 ≤ −环宽 |
| P-4 玻璃 | 具名 `backdrop-filter` 面内控件 | 与 P-1/P-3 同口径且差分 > 0（叠加层没把它吃掉） |
| P-5 圆角件 | 每个被判件的**计算样式** `border-radius` | ≥6px；胶囊/圆形（50% / ≥999px）放行；短边 <12px 的微格允许 ≤4px；方角 = 红（round2 新增；这是「登记机制废除」的执行面） |
| P-6 全面圆角 | 页面上**每个画得出盒子的元素**（含非交互面：徽章 / chip / 统计卡 / 提示条 / 分隔面 / 媒体占位）的计算样式 | ≥6px，或胶囊/圆形，或微格（短边 ≤12px）的 2px 档；方角 = 红；**贴边满幅**（盒边 = 视口边）按几何豁免并逐条具名进读数 `structural` 段（round3 新增；与 G1–G4 成对） |

用法：`node scripts/check-focus-ring-runtime.mjs`（门禁）、`--json`、`--out=<dir>`（落读数 +
逐件前后帧 + `round-corners-runtime-readout.json` 普查读数）。零端口：走 `scripts/lib/visual-harness.mjs` 的 `page.route` 打桩，碰不到宿主实例。

## 5. 例外清单（机器源 = `scripts/focus-ring-exemptions.json`）

| 选择器 | 理由 |
|---|---|
| `css/sidebar.css` `#config-editor:focus` | monaco 自管聚焦视觉，DOM 侧描边会被编辑器内部选区顶掉 |
| `css/inputSurface.css` `html[data-nf-modality="pointer"] …:focus-visible` | 指针模态复位（JS 在 pointerdown 挂标记），「点」不出环、「Tab」出环 |
| `css/inputSurface.css` `#input:focus, #input:focus-visible, .fm-input:focus, .fm-input:focus-visible` | 作者令 2026-11-03 10:11 ④：输入区键盘态不再加高亮 |
| `css/keyboard.css` `.nf-trap-root:focus, .nf-trap-root:focus-visible` | 焦点陷阱宿主是盒子不是控件 |

方角登记（round1 的 `.explorer-item` / `.nf-dv-file` / `.set-nav` / `.bg-task-row` / `.ud-cell` /
`.tfl-row` 六条）**已随 round2 删除**：前四条与 `.tfl-row` 改 6px（`--ds-radius-chip`），
`.ud-cell` 按 §2.6 的微格几何档留 2px。台账因此只剩「无环」豁免 4 条。

## 6. 判据盲点（诚实记账）

1. **不做级联计算**：静态闸只读 CSS 文本。F7 用「`index.html` 加载序 + 行号 + 圆角声明」
   近似级联（含 `@media` 内规则），不判特异性；裸元素基座与 ID 主体基座直接跳过 ——
   这两类的形状由 P-5 在运行时按计算样式判。由 JS 内联写出的焦点样式不在判定面（本批实测
   未发现内联焦点样式）。
2. `:focus-within` 的容器环不判（那是「里面有人聚焦」，不是「控件被聚焦」）。
3. P-5 也只判**上屏的样本**：零端口装置的两个场景（主面 / 设置弹层）之外的件（如
   `.ud-cell`、`.tfl-row`、`.nf-dv-file`）没有像素/计算样式样本，只由 F7 静态覆盖。
   不拿「没采到」当绿 —— 读数里逐条具名。
4. P-3 在两类件上弃判：聚焦会改自身 bbox 的件、以及整块（>80%）重绘的件 —— 读数里具名。
5. 打桩场景里「给了矩形但没真正上屏」的件不判环（`notPainted` 列表逐件报出），不当绿。
6. 合并卡是**独立窗口**（`mergeWindow`），本仓零端口装置不接管；它的焦点样式由 F1–F6 覆盖，
   **没有**逐像素证据。
7. 对照板只证明「改前/改后的真实像素」，不证明「作者在人手实例里看到的那一刻」：
   round2 的主会话框完整性另有**真实产品实例**读数（§7.3），两者不可互替。

round3 追加的盲点（全面圆角批）：

8. **G1 的「可继承上下文」是选择器字符串近似**：本闸用「同基座 + 类族前缀（BEM 修饰 /
   复合类）」近似真实级联，不计算特异性。它只会**放过**（族里有人圆了就放），不会误杀；
   代价是「族根圆了、变体其实该更圆」这种情况静态看不出 —— 那由 P-6（计算样式）兜。
9. **P-6 只普查上屏元素**：零端口装置只有主面 / 设置弹层两个场景；画布、媒体灯箱、
   移动端页面等面的画框件没有 P-6 样本，只由 G1–G4 静态覆盖。不拿「没采到」当绿。
10. **微格是按实测短边判的**：短边 ≤12px 放行 2px 档，因此一个「本该 6px 的小卡」若其
   上屏短边恰好 ≤12px，会被放行 —— 这是有意的口径（物理钳制优先于审美），读数里
   `p6MicroAccepted` 逐件计数，不静默。
11. **贴边满幅的结构豁免除运行时具名外，还有静态清单**（`STRUCTURAL_FULLBLEED`）；
   静态清单是「结构类别」的代理，无法自己量视口 —— 若某条清单项被改成非满幅，
   运行时 P-6 会把它当普通画框面真判（那时它会因圆角 0 变红）。两个方向都在记账。

## 7. 本批读数与证据（2026-11-22 实施批）

静态闸：`focus-ring: OK — 焦点环单规则单色源；豁免 4/4 条、方角登记 6 条、别名引用 0 处`。
运行时闸（真 Chromium 151，亮/暗两档，106 样本）：

| 项 | 读数 |
|---|---|
| P-1 失败 | 0 |
| P-2 失败（令牌对规范底色） | 0（亮 2.54 / 暗 7.45） |
| P-3 失败 | 0（2 件弃判：待办岛整块重绘） |
| P-4 样本 / 失败 | 104 / 0 |
| 「环对相邻像素」<3:1 已逐件标注 | 62 件 |
| 承载面实测低于门槛的偏差 | 55 件（读数 `deviations` 段） |
| 未真正上屏因而未判环 | 6 件（`notPainted` 段） |

改动面：焦点光晕 14 处删除、非内缩 offset 99 处归零、字面量/别名环行 100 行删除、
canonical 环 113 处、`outline:none` 焦点规则 15 → 5（4 豁免 + 1 配对拆分写法）。

**作者面证据**（vault 目录 `<dataRoot>/projects/stand/.stand/docs/assets/20261122_focus-ring-impl/`）：
- `focus-contact-sheet-implemented.png` —— 2454×2059 对照板，13 组件 × 亮/暗 × 改前/改后，
  四列全为真像素（改前源树 = `feat/base` 的 css 字节，改后 = 本工作树）；
- `focus-readout.json` —— 逐格几何读数（环像素/盒尺寸/offset）；
- `focus-runtime-readout.json` —— P-1–P-4 逐样本读数（含 `deviations` / `subThreeAnnotated` /
  `notPainted` 三个具名段）。

完整过程证据（100+ 帧、门禁原始输出、codemod）留在 worktree 侧
`.stand/tmp/focus-ring-impl/evidence/`，按临时物 TTL 7 天管理，不入库。

## 8. round2 读数与证据（2026-11-22 · 支 `feat/focus-ring-round2`）

作者令 2026-11-22 08:03：「还需要改 我说了要圆角的框 而且像主会话 这种框目前还无法完全显示
我要能完全显示 并且把这个落为标准」。round2 因此做三件事：① 圆角成为标准、方角登记机制
废除；② 大框（composer 外壳一类）**完整显示**并在产品侧与证据侧各自实测；③ 全部落规范
（本文 §2.2/§2.6/§2.7/§3/§4/§5/§6 + 台账 + 双闸 + 设计稿 backfill）。

### 8.1 圆角（①）

| 件 | round1 | round2 |
|---|---|---|
| `.explorer-item`（split.css） | 0（登记方角） | `--ds-radius-chip` 6px |
| `.nf-dv-file`（diffView.css） | 0（登记方角） | 6px + 行间分隔线改内缩 `::before` |
| `.set-nav`（settingsRows.css） | 0（登记方角） | 6px |
| `.bg-task-row`（chat.css） | 0（登记方角） | 6px + 行间 hairline 改内缩 `::after` |
| `.tfl-row`（todo-face.css） | 4px（登记 badge 档） | 6px |
| `.ud-cell`（usage.css） | 2px（登记 thin 档） | 2px（**微格几何档**，非登记；11px 格 6px 会被钳成圆点） |
| `.set-disclosure`（settingsRows.css） | **不在任何登记里**（实测 0px） | 6px |

外加 F7 首跑点名的 22 个环基座一并落 6px（`.nfc-link` / `.turn-header` / `.perm-cmd` /
`.nf-rcpt-top` / `.nf-rcpt-head` / `.nf-ar-fold` / `.fm-panel-close` / `.fm-detail-close` /
`.fm-fwd-row` / `.fm-att-dl` / `.g2-pill-primary` / `.project-link-btn` / `.project-desc-cancel` /
`.secrets-chip` / `.secrets-value-eye` / `.document-term-open` / `.tool-img-ref` / `.nf-nav-stop` /
`.cfg-card-head-btn`（删：旧类已清零）/ `.save-btn`（删：旧类已清零）等），以及 12 处**焦点规则
里的 `border-radius`** 删除（环半径 = 组件半径；F8 钉住）。

### 8.2 大框完整显示（②）—— 先诊断后修

**诊断（真实产品实例，非打桩台架）**：宿主实例 `:6138`（sbt 运行中的 Stand 自身，cwd =
主检出）实测 `#input-bar`：548×78 @ (310,812)，视口 1440×900，`border-radius: 20px`，
`overflow: visible`，唯一「裁切祖先」= `body{overflow:hidden}`（即视口本身），四边余量
左 310 / 右 582 / 上 812 / 下 10 —— **产品侧没有裁切，框是完整的**。
⇒ 根因落在**对照板采集侧**（四处叠加）：裁切框被 `min(vp−x, …)` 截断、边距只有 6px、按
控件（无框的 `#input`）而非容器框本体取图、合成时横纵各自限幅把宽图压扁。四处全部修掉
（`scripts/capture-focus-ring.mjs`：`frameSel`/`focusSel` 取框本体 + 10px 边距 + 整框
`frameComplete` 硬判 + 等比缩放），并新增 `§2.7` 判据把「框必须完整显示」写成标准。
证据：`assets/20261122_focus-ring-round2/`（整框版本，§8.3）+ 真实实例整框读数与截图
（`live-composer-{light,dark}.png` / `live-composer-geometry.json`，过程证据在 worktree 侧）。

### 8.3 门禁与证据读数（round2 实测）

| 项 | 读数 |
|---|---|
| `check-focus-ring.mjs` | **OK** —— 豁免 4/4 条、F7 覆盖 141 个环基座（方角登记机制已废除）、F8 0 处、别名 0 处 |
| `check-focus-ring-runtime.mjs` | **OK** —— 样本 106（亮/暗）；P-1 0 / P-2 0 / P-3 0（2 件弃判：待办岛整块重绘）/ P-4 104 样本 0 失败 / **P-5 104 样本 0 失败**；计算圆角分布 standard 86 / circle 10 / pill 8 / 豁免 2 |
| 对照板 | 2694×2191，15 行 × 4 列（亮/暗 × 改前/改后），60/60 格，**全部 `frameComplete=true`**（含整框 + 10px 边距，无裁切、无变形） |
| 真实实例框读数 | `#input-bar` 548×78 @ (310,812) / 1440×900 / 20px / 四边余量 310·582·812·10 |

**作者面证据**（vault 目录 `<dataRoot>/projects/stand/.stand/docs/assets/20261122_focus-ring-round2/`）：
- `focus-contact-sheet.png` —— round2 对照板（整框版：主会话输入条外壳框整框入格）；
- `focus-readout.json` —— 逐格读数，含 `frameComplete` / `frameRadius` / `frameOverflow`。

完整过程证据在 worktree 侧 `.stand/tmp/focus-ring-round2/evidence/`（TTL 7 天，不入库）。

## 9. round3 读数与证据（2026-11-22 · 支 `feat/round-corner-sweep` · 作者令 10:17）

**作者令（逐字）**：「对 但是我看到还是存在部分不是圆角的 得全面圆角 而且成为规范和自动化/lint检查」。

| 闸 | 读数 |
|---|---|
| `check-round-corners.mjs`（新，静态 G1–G4） | **OK** —— 画框规则 **1535** 个全部有圆角（全树 6979 条规则 / 50 个 CSS 文件）；结构全幅豁免命中 3 条，清单 6 项 |
| `check-focus-ring-runtime.mjs`（P-6 新增） | **OK** —— P-1 0 / P-2 0 / P-3 0 / P-4 104 样本 0 失败 / P-5 104 样本 0 失败 / **P-6 普查 130 件，方角失败 0**（微格 2px 档放行 2 件，结构满幅 0 件） |
| `check-focus-ring.mjs` | **OK** —— 豁免 4/4，F7 覆盖 141 个环基座，F8 0 处，别名 0 处 |
| `check-theme-token-mirror.mjs` | PASS |
| `check-noclip-floating.mjs` | PASS —— 416 条全在台账，**未新增** |
| `check-css-structure` / `check-flat-buttons` / `check-input-surface` | PASS / 718 块 0 违规 / OK（豁免 2 条） |
| `keyboard-check.mjs` | 102 passed / 7 failed —— 失败项与 round2 基线**逐字相同**（非本批引入） |
| `run-checks --profile static` | 88/93 —— 红项 test-hermetic / border-weight / js-types / takeover-identify / worktree-manifest，与 round2 基线同源 |

**①–⑤ 落点**：① 全树改圆 = 34 文件 +253 条、literal→token 12 处；② 新闸 = `scripts/check-round-corners.mjs` + P-6（`scripts/check-focus-ring-runtime.mjs` 的 `SURVEY`），登记 `scripts/run-checks.mjs`；③ 结构豁免 = `STRUCTURAL_FULLBLEED`（几何判据，按清单具名）；④ 本节 + §2.6 升格 + §3.2 G1–G4 + §4 P-6 + §6 盲点 8–11；⑤ 对照板 84/84 整框。

**结构满幅豁免清单（贴边满幅，6 项，双向记账）**：`html`、`body`、`#app`、`.app-shell`、`.flowmap-card`、`body.mobile-layer .fm-context-menu`。新增满幅件必须入清单并写理由，否则 G4 红。

**A/B 裁定（`#settings-modal-close`）**：改前 `shrunk` 显示 999px→6px 语义下降（圆钮变方钮）。已由既有修正闭环：glass.css 材质共享块不再统一写档位、sapphire.css 把 `.panel-btn` 共享块与 `.g2-fab` 几何拆开；复测计算值 **50%**（40px 圆，与原 999px 视觉等价），P-6 0 失败。不再加冗余规则。

**开放项**：`.ud-cell` 2px 微格（几何档，回作者裁）；composer `#input` 出环与否（回作者裁，沿用 2026-11-03 无环豁免）；活动栏容器 `#activity-bar` 不进对照板（874px 高于视口，由 P-6 覆盖）。

**对照板**：`<dataRoot>/projects/stand/.stand/docs/assets/20261122_round-corner-sweep/focus-contact-sheet.png`（2694×3833，21 行 × 4 列，84/84 `frameComplete=true`）+ `focus-readout.json`（逐格读数，beforeRef=b05156183，zoom=3）。
