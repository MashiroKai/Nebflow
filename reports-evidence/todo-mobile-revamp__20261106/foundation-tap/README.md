# foundation-tap — 手机端最小可点尺寸「改前」嫌疑读数（min-tap 批 · 2026-11-06）

本目录 = 作者令「手机端所有的交互 最小按钮大小就是我们统一的对话框顶部悬浮按钮的样式」
的**改前基数证据**。三件：

| 文件 | 是什么 | 怎么复现 |
|---|---|---|
| `before.json` | 机检脚本 `--json` 的完整输出（机器读：逐条违规 + 差多少 + 静态腿无法判定清单） | `node scripts/check-mobile-tap.mjs --json > before.json` |
| `before.log` | 同一次运行的**原样文本输出**（125 行，exit=1） | `node scripts/check-mobile-tap.mjs > before.log 2>&1` |
| `README.md` | 本文件：复核结论 + 读数摘要 + 判读 |

输入读数（主口径）= `reports-evidence/todo-mobile-revamp__20261106/shots-before/readings-before.json`
（375×812 真链、手机端「任务」页 + 新建抽屉的几何快照，由同目录 `capture-before.mjs` 产出）。

---

## 1. 先复核再引用：48 是不是真值？

**是。** 复核链（每步都有 file:line）：

| 步骤 | 事实 | 锚点 |
|---|---|---|
| ① 作者说的「对话框顶部悬浮按钮」是哪个选择器 | 聊天根顶缘两颗圆钮 = `#mobile-fab-layer` 内 `.mh-chrome-btn.g2-fab` | `js/mobile/chatChrome.js:258`（任务键）/ `:274`（搜索键） |
| ② 它的边长在哪声明 | `body.mobile-layer .g2-fab { width: 48px; height: 48px }` —— **手机档唯一声明点** | `css/glass.css:717-720` |
| ③ 排除桌面腿 | 桌面是 `--cv-size-circle` 的 40px 回退，与本档无关 | `css/glass.css:554-556` |
| ④ 真链实测 | 同族圆钮 `.mobile-back-btn.g2-fab` = **48×48**；`.tfl-fab-add.g2-fab` = **48×48** | `shots-before/readings-before.json` 首条 / 第 10 条 |
| ⑤ 既有令牌 | `--ds-hit-tap-lg: 48px`（手机档悬浮圆钮，桌面无此档），经 `--mtap-lg` 供全族消费 | `design-system.css:417`、`mobileLayer.css:328` |

⇒ 任务书给的 48 **成立**，无需改判。新令牌两档都**别名**到既有单源（不新造数值）：
`--ds-hit-tap-min: var(--ds-hit-tap-lg)` / `--ds-hit-box-min: var(--ds-hit-tap-min)`
（`css/design-system.css` §㉓）。

同时复核到两条**不能照抄**的既有读数，已在规范里点明：
- `--ds-turn-hit: 44px`（`design-system.css:459`）是**桌面指针档**，不是手机档；手机腿的
  `.ds-turn-btn` 本体已由 `--ds-turn-btn-size: var(--mtap-lg)` = 48（`mobileLayer.css:2867`）。
- `--ds-hit-row: 44px`（`design-system.css:250`）是**列表行最小高**，不是按钮档。

---

## 2. 命令

```bash
cd "/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/todo-mobile-revamp"
node scripts/check-mobile-tap.mjs            # 人读；exit=1（改前应有 17 条违规）
node scripts/check-mobile-tap.mjs --json     # 机器读（= before.json）
```

---

## 3. 原样输出（`before.log` 首段逐字）

```
mobile-tap 门禁 — 视觉档 48px（--ds-hit-tap-min → --ds-hit-tap-lg） · 命中盒档 48px（--ds-hit-box-min → --ds-hit-tap-min → --ds-hit-tap-lg）
主口径（运行态几何）：reports-evidence/todo-mobile-revamp__20261106/shots-before/readings-before.json
  可点元素 19 · 合规 2 · 违规 17 · 无法判定 49（运行态 0 / 静态 49）
```

末段逐字：

```
✗ mobile-tap gate FAILED — 违规 17 条（上限 0）
  处置：视觉边可小于 48，但必须用 ::after / padding / 透明外扩把命中盒补到 48×48（写法与豁免判据见 design/liquid-morph__20261106/TAP.md）。
```

完整 125 行见同目录 `before.log`（含每条违规的两行明细 + 49 条静态腿「无法判定」）。

---

## 4. 改前嫌疑条数 = **17 条**（可点元素 19 条中，仅 2 条合规）

判据（脚本逐字实现）：`视觉边 < 48` **且** `命中盒 < 48`。两轴任一不达标即算「该轴差」。

| # | selector | 视觉 w×h | 命中盒 hit | 视觉差 | 命中盒差 |
|---|---|---|---|---|---|
| 1 | `button.tfl-icon-btn.tfl-filter-toggle`（筛选图标） | 28×24 | 44×44 | -20×-24 | **-4×-4** |
| 2 | `button.tfl-chip.tfl-chip-all`（「全部」chip） | 49×26 | 49×26 | 0×-22 | 0×-22 |
| 3 | `button.tfl-chip-add`（新建标签 +） | 24×26 | 24×26 | -24×-22 | -24×-22 |
| 4 | `button.tfl-chip-trash`（删除标签） | 24×26 | 24×26 | -24×-22 | -24×-22 |
| 5 | `button.tfl-chip.tfl-chip-prio`（优先级「无」） | 35×26 | 35×26 | -13×-22 | -13×-22 |
| 6 | `button.tfl-chip.tfl-chip-prio`（「低」） | 35×26 | 35×26 | -13×-22 | -13×-22 |
| 7 | `button.tfl-chip.tfl-chip-prio`（「中」） | 35×26 | 35×26 | -13×-22 | -13×-22 |
| 8 | `button.tfl-chip.tfl-chip-prio`（「高」） | 35×26 | 35×26 | -13×-22 | -13×-22 |
| 9 | `button.mf-drawer-cancel-btn`（抽屉「取消」） | 44×44 | 44×44 | **-4×-4** | **-4×-4** |
| 10 | `button.mf-drawer-done-btn`（抽屉「完成」） | 44×44 | 44×44 | **-4×-4** | **-4×-4** |
| 11 | `input.tfl-row-title-input`（标题输入框） | 319×16.19 | 319×16.19 | 0×-31.81 | 0×-31.81 |
| 12 | `input.tfl-edit-notes`（备注输入框） | 331×23.39 | 331×23.39 | 0×-24.61 | 0×-24.61 |
| 13 | `button.tfl-edit-prio-btn`（抽屉优先级「无」） | 32×26 | 32×26 | -16×-22 | -16×-22 |
| 14 | `button.tfl-edit-prio-btn`（「低」） | 32×26 | 32×26 | -16×-22 | -16×-22 |
| 15 | `button.tfl-edit-prio-btn`（「中」） | 32×26 | 32×26 | -16×-22 | -16×-22 |
| 16 | `button.tfl-edit-prio-btn`（「高」） | 32×26 | 32×26 | -16×-22 | -16×-22 |
| 17 | `button.nb-toggle`（抽屉内开关） | 51×31 | 51×46 | 0×-17 | **0×-2** |

**合规 2 条**（改前即达标，供对照）：

| selector | 视觉 w×h | 命中盒 |
|---|---|---|
| `.mobile-back-btn.g2-fab`（页面壳返回圆钮） | 48×48 | 48×48 |
| `.tfl-fab-add.g2-fab`（右下新建加号） | 48×48 | 48×48 |

### 判读（三类问题、三种修法）

1. **只差一点点（1/4/9/10/17）**：44×44 的抽屉钮差 4、`.nb-toggle` 命中盒差 2、
   `.tfl-filter-toggle` 已有 44 外扩但差 4 —— 这类是**把既有 44 档外扩目标抬到 48**，
   改 `::after` 的 `inset` 取值即可，视觉零变化。
2. **chips 族（2–8, 13–16）**：视觉 26 高、无任何外扩 —— 必须成品字形或圆形 `::after` 外扩；
   注意同排相邻 chips 会互相覆盖命中盒（规范 §3.1 已写明「相邻留 8px 或改 padding」）。
3. **输入件（11–12）**：视觉高 16.19 / 23.39 是**文字行盒高**，不是设计缺陷；修法是容器
   `min-height: var(--ds-hit-box-min)` 或纵向 padding，视觉高度可保持在 48 内人到眼的高度。

---

## 5. 静态腿：「无法判定」49 条（不是违规，也不许静默放过）

| 类别 | 条数 | 含义 |
|---|---|---|
| `css-decl` | 16 | `body.mobile-layer` 作用域内某条规则声明了 `<48px` 的可点类边长（如 `mobileLayer.css:3252` `.mh-search-bar` 44px、`mobileLayer.css:3753` `#memory-btn` 32px）—— 最终几何受级联 / `var()` / 内容 / JS 改写，静态不下结论 |
| `js-class` | 33 | JS 生成的类名在整个 `css/` + `index.html` 内**零命中**（如 `.delegate-archive-fab`、`.msg-quote-btn`）⇒ 无样式面，几何算不出来 |

这 49 条**不计入违规、也不计入合规**：脚本把它们逐条印出来（文本腿只印前 40 条并指路
`--json`），JSON 里全量。要判定它们，必须在真链上补一次几何快照（同 `capture-before.mjs` 法）。

---

## 6. 收敛目标与门槛用法

- 目标：**17 → 0**。实现节点按 `design/liquid-morph__20261106/TAP.md` §3 整改后，
  用同一脚本跑「改后腿」读数：`node scripts/check-mobile-tap.mjs --readings <改后读数.json>`，
  `violations` 必须为 0（`ok:true`，`exit=0`）。
- 过渡期若要把本闸挂进 CI，**只能用 `--max-violations 17`（只许不增）**，
  并在整改批里归零 —— 抬阈值或放宽判据等于把闸关掉。
- 本批**未**把闸挂进 CI / Makefile：改前树是红的（17 条 = 设计事实），挂进去立刻拦所有批。
  挂载动作随整改批一起做（届时基数为 0）。

---

## 7. 诚实申报

- 读数面 = 手机端**「任务」页 + 新建抽屉**这一屏（`shots-before/readings-before.json` 的全部
  19 条可点元素）。其它手机面（会话页 / 搜索面 / canvas 页 / 各弹层）本批**未实拍**，
  它们的嫌疑性由静态腿 49 条「无法判定」间接指认，**没有逐条运行态数字**。
- `.nb-toggle` 的命中盒 51×46 说明它**已有**某种纵向外扩（+15），只差 2px 达标 ——
  这类「差一点点」的读数证明当前各面外扩是**零散的手工值**，正是立统一令牌的动机。
