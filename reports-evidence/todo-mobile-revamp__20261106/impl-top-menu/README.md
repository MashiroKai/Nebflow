# 待办手机页 · 顶部「⋯」两级菜单 + 新建从顶部（实施项 ①②）

**批**：todo-mobile-revamp（2026-11-06）· worktree `/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/todo-mobile-revamp`
**提交**：`feat(todo-mobile): …`（见 `git log -1`）
**自足验收**：`node tests/todo-mobile-topmenu.spec.mjs` → **27 passed / 0 failed**（输出见 `spec-output.txt`）

---

## 1. 做了什么（逐条对应任务书）

### ① 新建从顶部、零内容位移

| 项 | 改前 | 改后 |
|---|---|---|
| 表单承载 | `.mf-drawer` 底部抽屉（`justify-content: flex-end`） | 同族类名 + **`.mf-drawer-top`** 修饰类：主轴翻到 top、盒圆角翻到下沿、起点 `translateY(-100%)` |
| 入口 | 仅右下角加号 / 底部 footer Add | 页顶「⋯」菜单首行**新建待办**（新增）+ 原右下加号（保留，同一 `startCreate`） |
| 显隐写口 | `renderRows()` 里 `sheetEl.hidden = !creating` | 唯一写口 `setSheetOpen(open)`（摘 hidden → 下一帧翻 `.is-open`，收场延时 hidden） |
| 动画 | 无（抽屉瞬显） | `js/motion/liquidMorph.js` 的 `morphTransition(['transform','opacity'], { duration: 'slow' })`（400ms / 唯一曲线 / 白名单），reduced-motion 同源降级 1ms |
| 聚焦 | `createTitle.focus()`（缺省滚动） | 手机腿 `focus({ preventScroll: true })`（防止页体滚轴被拽动 = 内容位移） |
| 写口 | `buildCreateRow()` / `saveCreate()` | **零改动**（同一条 DOM、同一条写口，零第二套表单） |
| 标题框蓝边 | 聚焦时 `2px inset outline`（`css/inputFocus.css:66-79` 的元素种类级统一规则，`--nf-field-focus-line`） | `.mf-drawer-top` 内 `outline: none` + `border-color: transparent`，焦点态换成**容器底色一档**（`--color-text` 9%，零颜色字面量） |

**共用面处置（`.mf-drawer` 一族与定时任务页共用）**：顶部承载**不改基础类**，只增 `.mf-drawer-top` 修饰类，
`js/scheduled-task.js:999` 建的仍是裸 `.mf-drawer`（底抽屉）⇒ 类名/几何/材质零改动。
实证：`playwright test tests/mobile-p3-reminder.spec.mjs` → 8 passed（T5「右下悬浮加号 → 底部抽屉」仍绿）。

### ② 顶部悬浮「⋯」+ 两级菜单

| 项 | 结果 |
|---|---|
| 顶部唯一入口 | `button.tfl-topmenu-btn.g2-fab`（48×48 视觉 = `--ds-hit-tap-min` / 命中盒 ≥ `--ds-hit-box-min`），落位镜像页面壳返回钮：`top: calc(--mchrome-top + safe)` / `right: --mpage-fab-inset` |
| 旧入口退役 | `.tfl-filter-toggle` / `.tfl-collapse-btn` / `.tfl-chips-row` / `.tfl-prio-row` 在手机腿**DOM 层不建**（`buildHeader` 早退、`buildDom` 不调 `buildFilterBar`）⇒ 不是隐藏，无第二套并存 |
| 桌面腿 | **零改动**：`if (this.mobile)` 门控；spec H1/H2 断言 1440 下旧控件仍在（各 1 枚）且无 ⋯ 钮 / 无 `.mf-drawer-top` |
| 一级菜单 | `.tfl-menu-panel.nf-glass`：图标 + 文字行、次行 = 当前值、组间 1px 分隔线（伪元素，内缩 `--mpad-x`）、危险项红字 `--color-danger`、点面板外（透明幕布 `.tfl-menu-scrim`）收起 |
| 一级行 | 新建待办 · 标签（次行=当前视图）· 重要度（次行=当前筛选）· 新建标签 · 删除标签（红） |
| 二级面板 | `.tfl-menu-sub.nf-glass` 叠在原面板下沿（对齐被点那一行、右缘再内收一档），一级面板 `.is-back`（opacity .45 + scale .98）**仍可见**；标签/重要度为单选/多选位、左列 ✓ 槽（选中/未选同宽），选中即回写 store 并整菜单收起；删除标签为红字逐行删除 |
| 动画 | 一级/二级均 `morphTransition(['transform','opacity'], { duration: 'base' })`（280ms / 唯一曲线），reduced-motion 由同模块降 1ms |

---

## 2. 改前 → 改后 file:line 对照

| 位置 | 改前 | 改后 |
|---|---|---|
| `js/todoFace.js` | `448` `panel.append(header, filterWrap, listWrap, footer)` | `456-457` — filterWrap 缺省（手机）时跳过 |
| `js/todoFace.js` | `474-500` `buildHeader()` 无条件建筛选/收起 | `493-495` 手机腿建完计数即 `return` |
| `js/todoFace.js` | `502-552` `buildFilterBar()` 恒建 | `432-440` 仅 `!this.mobile` 建；`renderFilterBar()` 首行 `:1129-1130` 空守卫 |
| `js/todoFace.js` | `613-639` `buildCreateSheet()` 底抽屉 | `629-660` 顶部变体（`.mf-drawer mf-drawer-top`，存 `sheetBox`） |
| `js/todoFace.js` | —（无开合动画写口） | `662-690` `setSheetOpen()`（唯一显隐写口 + 液态形变过渡串） |
| `js/todoFace.js` | `976` `sheetEl.hidden = !creating` | `1278` `this.setSheetOpen(this.creating)` |
| `js/todoFace.js` | `1298` 聚焦无 preventScroll | `1599-1606` 手机腿 `focus({ preventScroll: true })` |
| `js/todoFace.js` | `1960` `openTagPopover(editTagId)`（锚点必查 chipsTrack） | `2268` 增可选 `anchorEl` 形参 + `2298-2301` 空守卫（手机锚 = ⋯ 钮） |
| `js/todoFace.js` | —（无顶部菜单） | `704-737` `buildTopMenu()`；`738-780` `toggleMenu/openMenu/closeMenu`；`782-812` `renderMenu()`；`814-843` `menuRow()`；`845-855` 次行读数；`857-930` `openMenuSub()/paintSub()` |
| `js/todoFace.js` | `754-758` `onStoreChange()` | `1047-1057` 菜单开着时同步重画两级读面 |
| `css/mobileLayer.css` | 末尾 5342 行（抽屉块止） | 新增 **5343-5627** ⑤ 块：⋯ 钮 / 菜单根与面板 / 行与分隔线 / 二级面板与 ✓ 槽 / `.mf-drawer-top` / 标题框焦点态 |
| `css/mobileLayer.css` | `4389-4395` `.tfl-complete::after` 用 `--mhit`(44) | `4406-4411` 本件升至 `--ds-hit-box-min`(48)（顺带对齐最小可点尺寸令牌） |
| `css/mobileLayer.css` | `2810-2817` `.tfl-del::after` `-13px`（=44 命中盒） | `2810-2820` 改公式 `calc((var(--ds-hit-box-min) - 100%) / -2)`（=48） |
| `js/locales/zh-CN.js` / `en.js` | — | 新增 `todoface.menu` / `menuView` / `menuPrio` / `menuNoTags`（4 键 × 2 语言） |
| `tests/todo-entry-collapse.spec.mjs` | `318-320` 点 `.tfl-filter-toggle` ⇒ 断言四签 + 分组 chips | `318-333` 口径换代：旧控件零命中 + ⋯ 菜单二级面板承载四签/分组（信息不丢） |

`inputFocus.css:66-79` = 蓝边真身（未改该文件：它是全站焦点单源，本面在同一令牌体系内换形态）。

---

## 3. 三组几何读数（① 硬验收，375×812 / deviceScaleFactor 2）

开合取帧：`⋯ → 新建待办` 前 / 后（表单落位 + 400ms 动画 + 60ms 聚焦全部走完）。

| 读数 | 改前 | 改后 | 判据 |
|---|---|---|---|
| 列表首条 `.tfl-row` rect | `x 22 · y 90.59 · w 331 · h 52.08` | `x 22 · y 90.59 · w 331 · h 52.08` | 位移 0 ≤ 0.5px ✅ |
| 底栏 `#mobile-dock` rect | `x 12 · y 750 · w 351 · h 54` | `x 12 · y 750 · w 351 · h 54` | 位置不变 ✅ |
| `.tfl-list` 滚区 | `scrollTop 0 · scrollHeight 661 · clientHeight 661` | 同左 | 三项逐一相等 ✅ |
| `document.scrollingElement` | `scrollTop 0 · scrollHeight 812` · `window.scrollY 0` | 同左 | 无 body 级滚动跳动 ✅ |
| 表单盒顶 | （改前为底抽屉，盒贴页底） | `box.y 0 = page.y 0` | 顶承载 ✅ |

> 注：任务书写的「dock（`[data-chrome]` 底栏）」在现役 DOM 里拆成两处 —— 底栏 = `#mobile-dock`，
> 顶缘 chrome 键 = `.mh-chrome-btn[data-chrome=…]`（48 圆钮族，本批未触碰）。上表量的是底栏；
> 顶缘两键同样未被本批任何规则命中（本批新增规则全部以 `.tfl-topmenu-btn` / `.tfl-menu*` /
> `.mf-drawer-top` 为前缀，且 `⋯` 落位与搜索键同侧但不同层：`right: --mpage-fab-inset`，z 6）。

---

## 4. spec 输出（全 PASS 才收工）

`node tests/todo-mobile-topmenu.spec.mjs` → **PASS 27 passed, 0 failed**（全文见 `spec-output.txt`）。要点：

```
B1 ⋯ 钮存在且可见 — {x:311,y:16,w:48,h:48}
B4 旧顶部控制零命中 — {.tfl-filter-toggle:0,.tfl-collapse-btn:0,.tfl-chips-row:0,.tfl-prio-row:0}
A1 首条元素零位移 — before/after 逐值相同
C3 过渡串 = 液态形变单源 — transform,opacity / 0.28s / cubic-bezier(0.4, 0, 0.2, 1)
D1 二级面板叠出且与一级面板矩形相交 — subVisible true / overlap true / panelOpacity 0.45
E3 选中态打勾 — 工作 aria-checked=true 且 ✓ 命中；全部/生活 无 ✓
F1 点面板外收起 — hidden true
G1 标题框聚焦无蓝边 — outlineStyle none / borderColor rgba(0,0,0,0)
H1/H2 桌面腿 — 旧控件各 1 枚、⋯ 钮 0、.mf-drawer-top 0
```

证据图：`menu-level1.png`（一级）/ `menu-level2.png`（二级叠显 + 一级变暗）/ `create-from-top.png`（顶部表单 + 标题行焦点态）。
读数：`spec-readings.json`、`readings-after-page.json`、`readings-after-sheet.json`。

### 最小可点尺寸（顺带读数，`scripts/check-mobile-tap.mjs --readings …`）

| 帧 | 扫描 | 合规 | 违规 |
|---|---|---|---|
| 列表帧（菜单收起、新建关） | 6 | 6 | **0** |
| 表单帧（新建开、标题聚焦） | 15 | 6 | 9 |

表单帧 9 条 = `.mf-drawer-cancel-btn` / `done-btn` 44²、`input.tfl-row-title-input` 16.19 高、
`input.tfl-edit-notes` 23.39 高、`.tfl-edit-prio-btn` ×4 32×26、`.nb-toggle` 命中 46 ——
**全部是 foundation-tap 批已登记的既有条目**（该批明确「只立令牌不铺面」），本批未改其几何。
门禁默认读数（`shots-before/readings-before.json`，改前空列表快照）**未覆盖、未放宽、未抬阈值**。

---

## 5. 门禁

| 闸 | 结果 |
|---|---|
| `check-flat-buttons` | exit 0（655 按钮面规则块 / 0 违规） |
| `check-glass-unify` | exit 0（新面板只吃 `.nf-glass`；本文件零 `backdrop-filter`） |
| `check-component-contract` | exit 0（零新增；裸 innerHTML 未增 —— 菜单行全走 `el()`/`createElement`） |
| `check-circular` | exit 0（新 import 边 `todoFace → motion/liquidMorph`，未成环） |
| `check-self-explaining` | exit 0（27 命中 = 基线 27，新增全是动作标签） |
| `check-design-spec-sync` | exit 0 |
| `check-toggle-unify` / `check-msgstyle-single-source` | exit 0 |
| `check-design-tokens` | **exit 1，唯一红项 = `js/ws.js` 颜色字面量 12 > 基线 10**（本批未触碰该文件，入场前既有，未抬基线） |
| `tests/liquid-morph.spec.mjs` | PASS 36 / FAIL 0（单源模块未动，本批只是新消费点） |

### 回归腿（同批实跑）

- `tests/todo-capsule-create.spec.mjs` → 8 passed（含 ⑧ 手机端区分/加号 48）
- `tests/todo-entry-collapse.spec.mjs` → 2 passed（口径已更新，见 §2 末行）
- `tests/mobile-p3-reminder.spec.mjs` → 8 passed（**共用 `.mf-drawer` 的底抽屉腿零回归**）
- `tests/todo-tag-delete-mode.spec.mjs` → 3 passed
- `tests/todo-split.spec.mjs`（桌面腿）→ 6 passed
- `tests/todo-chip-reorder.spec.mjs`、`tests/ui-seven-todo-expand.spec.mjs` → 全 passed

---

## 6. 未做 / 开放项（诚实申报）

1. **`tests/ui-seven-todo-edit.spec.mjs:295`「件6 手机 375」为入场前既有红**：该 test 调
   `bootTodoFace(page)` 未传 `mobile: true`，而视口 375 ⇒ `#task-list` 手机层 `display:none`，
   harness 等 `visible` 超时。**已在 HEAD 基线树复跑（`TODOFACE_WEB_ROOT=<git archive HEAD>`）确认同样失败**，
   与本批无关；修法 = 该 test 改走手机「任务页」入口（另一批的判据改写），本批不动。
2. 表单帧 9 条最小可点尺寸违规（§4 表）未整改 —— 属 foundation-tap 的铺面批。
3. 只拍亮色一档、只跑 Chromium（未跑 WebKit）、点击用 `hasTouch` 上下文而非 CDP 真触屏。
4. 「新建标签」在手机腿复用桌面 popover 组件（`openTagPopover(null, anchorEl)`），
   锚点改为 ⋯ 钮 —— 未给手机重做一套毛玻璃新建标签面（避免第二套表单/第二块模糊面）。
5. 二级面板「删除标签」是**即时删除**（无二次确认）；桌面腿的 chips 删除模式（R06 二次确认）
   仍在桌面腿原样，手机腿未复用其确认弹层（该弹层锚在 chips 行，手机已无 chips）。
6. 未 push、未触碰 java/sbt/nebflow 进程（pid 55669）。

---

## 7. 关键假设

- **「页顶的＋」= ⋯ 菜单首行「新建待办」**：任务书 ② 明确「顶部改为一枚悬浮圆钮 ⋯」，页顶不可能同时挂
  第二颗 ＋；因此把「页顶 ＋」读作菜单内的新建入口，右下角加号（作者图1 里的橙色 ＋）保留为同一
  `startCreate` 的第二个触发器 —— 单实现、双触发，不开第二套表单。
- **危险项按本面实际能力给**：本面可删的是标签（`todoStore.deleteTag`），无「清空已完成」写口 ⇒
  危险项取「删除标签」并列在末行（红字 `--color-danger`）。
- **一级面板之外不加暗幕**：作者图2 里面板之外的内容本色不变 ⇒ 幕布透明（只吃指针做「点外部收起」）；
  变暗只发生在**二级打开时的一级面板自身**（图3 语义）。
- 证据与读数落在 `reports-evidence/`（过程证据面），未按知识文档 vault 规则改名迁址
  （`todo-mobile-revamp` 项目无 `.stand/docs` 注册库，与链上先前节点同一处置）。
