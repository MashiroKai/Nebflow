---
type: design-spec
status: approved
date: 2026-11-22
title: 焦点高亮框规范（单规则 · 单色源 · 内缩 · 禁光晕 · 圆角档）
branch: feat/focus-ring-impl
supersedes: [[focus-ring-unify-design__20261121_185700]] 的实现细则部分（设计件保留为决策记录）
related: ["[[keyboard-support-audit__20261121_185700]]", "[[input-focus-ring__20261105_213238]]", "[[design-language-recon__20261102_234917__dlc-frontend]]"]
evidence: 见 §7（对照板 + 运行时读数落在本批 worktree 的证据目录）
---

# 焦点高亮框规范

**结论先行：全站键盘焦点指示器只有一种 —— `:focus-visible` 下 `outline: var(--nf-focus-ring-w) solid var(--nf-focus-ring)`、`outline-offset: calc(var(--nf-focus-ring-w) * -1)`（内缩 2px），颜色只有一个源 `--nf-focus-ring: #9ca3af`（灰系，亮暗同值，作者 2026-11-21 批 B 案）。禁止焦点 `box-shadow` 光晕、禁止字面量焦点色、禁止无配对环的 `outline: none`。**

本文是**判据正文**，两份机器面跟着它走：
- 静态闸 `scripts/check-focus-ring.mjs`（F1–F6，读 `src/main/resources/web/css/**`）；
- 运行时闸 `scripts/check-focus-ring-runtime.mjs`（P-1–P-4，真 Chromium 差分像素）；
- 豁免与方角登记台账 `scripts/focus-ring-exemptions.json`（脚本内零硬编码豁免）。

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
| R-6 | 无登记地给可交互件方角造型 | §2.6 |

### 2.3 例外（必须进台账，逐条具名理由）

只有两类允许「无环」：
1. **不是控件**：焦点陷阱宿主（`.nf-trap-root`）、monaco 编辑器容器（`#config-editor`）——
   控件在它里面，给盒子画环等于画两层。
2. **作者明令取消**：主会话输入条内层 `#input` / `.fm-input`（作者令 2026-11-03 10:11 ④
   「输入区键盘态不再给输入区加高亮」）、指针模态复位（`html[data-nf-modality="pointer"]`
   下的输入件）。

台账 `scripts/focus-ring-exemptions.json` 是这些例外的**唯一机器源**；F5 钉住「只减不增」，
并核对每条在对应 CSS 里仍能找到该选择器（僵尸豁免 = 红）。

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

### 2.6 圆角规则（本批新增）

- **可交互件默认圆角 6–10px**（`--ds-radius-chip` 6 / `--ds-radius-input` 8 档）。
- **既有低档保留**：`--ds-radius-badge` 4px 的行族（如 `.tfl-row`）、`--ds-radius-thin` 2px
  的表格格（如 `.ud-cell`）属既定令牌账本档，下调值不改。
- **方角造型件登记豁免**：树行、diff 文件行、设置导航行、后台任务行（0 圆角）必须在
  `scripts/focus-ring-exemptions.json` 的 `squareCorners` 段登记「文件 + 选择器 + 半径 +
  理由」；登记后 F5 会核对选择器仍存在（僵尸登记 = 红）。
- 胶囊/圆形造型件（999px / 50%）是既有形态档，不受 6–10px 约束。
- 为什么不做级联推断：静态闸不跑 CSS 级联，元素实际命中哪条圆角要运行时才知道；因此
  §2.6 的可执行面是**登记 + 核对**，不假装能全自动判定（见 §6 盲点）。

## 3. 静态判据（`scripts/check-focus-ring.mjs`）

| 判据 | 规则 | 通过条件 |
|---|---|---|
| F1 颜色单源 | `:focus` / `:focus-visible` 规则 outline 的颜色 | 只能是 `var(--nf-focus-ring)`；`Highlight` 仅限 `@media (forced-colors: active)` 内 |
| F2 几何单源 | 焦点规则的 outline 宽度 / `outline-offset` | 宽度 `var(--nf-focus-ring-w)`（等价值 `2px`）；offset 只能是内缩 `calc(var(--nf-focus-ring-w) * -1)`（等价值 `-2px`） |
| F3 无光晕 | 焦点规则的 `box-shadow` | 只能是 `none` |
| F4 无裸去环 | `outline:none/0` 且同文件同基座无配对环 | 必须在台账具名登记 |
| F5 豁免只减 | 台账条数 / 条目有效性 | ≤ `_baseline`（4）；每条理由 ≥8 字；选择器在对应 CSS 里仍存在 |
| F6 别名收编 | `--btn-ring*` / `--ds-focus-ring` / `--dropdown-focus` / `--nf-field-ring*` | 全树 0 处（含定义处） |

用法：`node scripts/check-focus-ring.mjs`（门禁）、`--json`（机器读）、`--inventory`（盘点）。

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

用法：`node scripts/check-focus-ring-runtime.mjs`（门禁）、`--json`、`--out=<dir>`（落读数 +
逐件前后帧）。零端口：走 `scripts/lib/visual-harness.mjs` 的 `page.route` 打桩，碰不到宿主实例。

## 5. 例外清单（机器源 = `scripts/focus-ring-exemptions.json`）

| 选择器 | 理由 |
|---|---|
| `css/sidebar.css` `#config-editor:focus` | monaco 自管聚焦视觉，DOM 侧描边会被编辑器内部选区顶掉 |
| `css/inputSurface.css` `html[data-nf-modality="pointer"] …:focus-visible` | 指针模态复位（JS 在 pointerdown 挂标记），「点」不出环、「Tab」出环 |
| `css/inputSurface.css` `#input:focus, #input:focus-visible, .fm-input:focus, .fm-input:focus-visible` | 作者令 2026-11-03 10:11 ④：输入区键盘态不再加高亮 |
| `css/keyboard.css` `.nf-trap-root:focus, .nf-trap-root:focus-visible` | 焦点陷阱宿主是盒子不是控件 |

方角登记（§2.6）：`.explorer-item`(0) / `.nf-dv-file`(0) / `.set-nav`(0) / `.bg-task-row`(0) /
`.ud-cell`(2px) / `.tfl-row`(4px badge 档)。

## 6. 判据盲点（诚实记账）

1. **不做级联计算**：静态闸只读 CSS 文本。由 JS 内联写出的焦点样式不在判定面（本批实测
   未发现内联焦点样式）。
2. `:focus-within` 的容器环不判（那是「里面有人聚焦」，不是「控件被聚焦」）。
3. 元素实际命中的圆角需运行时复核（§2.6 的可执行面因此是登记 + 核对）。
4. P-3 在两类件上弃判：聚焦会改自身 bbox 的件、以及整块（>80%）重绘的件 —— 读数里具名。
5. 打桩场景里「给了矩形但没真正上屏」的件不判环（`notPainted` 列表逐件报出），不当绿。
6. 合并卡是**独立窗口**（`mergeWindow`），本仓零端口装置不接管；它的焦点样式由 F1–F6 覆盖，
   **没有**逐像素证据。

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
