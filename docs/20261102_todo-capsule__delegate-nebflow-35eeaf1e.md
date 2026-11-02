---
type: batch
status: done
related: "[[20261102_title-capsule-height__delegate-nebflow-f003855e]]"
---

# 待办胶囊重做批：小胶囊 + 逾期翻面 + 点击展开大面板（2026-11-02 11:52 作者令）

> 相邻面见 [[20261102_title-capsule-height__delegate-nebflow-f003855e|面板头胶囊高度批]]（其 48 档是面板 chrome 的胶囊，本批 26 档是输入框下方的小胶囊；两批共用「胶囊 = 全圆 + 内容定宽」的语言，但档位分属两个面，互不改写）。文档索引：[[index]]，名词见 [[glossary]]。

## 摘要（先说人话）

作者原文：「收缩到只显示 8 待办 这么几个文字的大小程度的一个在对话框下方的一个胶囊。然后出现逾期的时候就是翻转一下 然后只显示 1 逾期 胶囊的大小不改变。然后点击的时候就是从这么个小胶囊拓展到一个大的界面。样式要设计好，现在上下都没居中。」

- **为什么原来是通栏且上下不居中**：`#task-list` 原本挂在顶带 `#top-overlays`（`top:68px`、撑满 `#main`），槽内 `.tfl-capsule` 写死 `width:100%` ⇒ 宽度跟着容器走（1280 下 450px、1440 下 530px，占输入区 100%），内容左对齐；同一槽里的 `.tfl-card` 高 30px 而里面文字行盒只有 11px 且贴顶 ⇒ 垂直方向偏 8.5px。上下都没居中不是"差一点 padding"，而是**槽位挂错了层 + 宽度继承容器**两件事叠加。
- **现在怎么保证两态等宽**：不做 canvas 量字、不写死宽度数字。胶囊内层 `.tfl-cap-faces` 是 `inline-grid` 的**单一格**，两段文案（`N 待办` / `N 逾期`）各占同一格叠在一起 ⇒ 外框宽 = 两者自然宽的 **max**，窄的那面在格内居中。宽度差是**构造上恒 0**，不是靠调参凑出来的；三位数（128 待办）也自然撑宽、不溢出。
- **翻转语义怎么定**：`overdueCount() > 0` ⇒ 只显示「N 逾期」（红字 + 红点），否则显示「N 待办」。**不并排、不同时显示**——作者原话「只显示 1 逾期」，所以「待办」那一段在逾期态 `visibility:hidden` + `aria-hidden=true`，读屏与取文本口径都拿不到它。逾期消失即翻回「N 待办」。`prefers-reduced-motion: reduce` 下整条 keyframes 退役（直接换面，状态读数一致）。
- **与扁平按钮族对接**：胶囊材质复用 `--ctl-neutral-bg / -ink / -hover / -active`（`css/stand-tokens.css` 单源，与 `.cfg-btn` 族 Pattern B 同源），圆角 999、无模糊无浮影；面板仍用玻璃 token。
- 读数：胶囊 **71.2 × 26**（占输入区 15.2% @1280 / 13.0% @1440），居中偏差 **0px**，行盒/圆点 **0px**，左右内距 12/12；两态宽高差 **0 / 0**；面板 **420 × 373.8**，居中 0px，底缘距输入区顶 **8px**（不重叠）。改前对照：**450×11 @y69**（1280）/ **530×11**（1440），文案 `8 待办1 逾期` 并排，宽度占输入区 **100%**。

## 1. 现实现定位（改前）

| 面 | 路径:行 | 事实 |
|---|---|---|
| 槽位 | `src/main/resources/web/index.html:838-843`（改前 `<div id="task-list"></div>` 在 `#top-overlays` 内） | 顶带 `top:68px`、撑满 `#main` ⇒ 天然通栏 |
| 槽规则 | `css/taskList.css` `#task-list.has-tasks`（padding 0 8px / `display:flex` / `flex-direction:column`） | 列向通栏容器 |
| 胶囊 | 改前 `css/todo-face.css:73` `.tfl-capsule { width:100%; padding:0 12px }` | 宽度随容器（450 / 530），文字左对齐 |
| 卡容器 | 改前 `css/todo-face.css:43` `.tfl-card { height:30px }` | 30px 盒里 11px 文字行盒贴顶 ⇒ 上下偏 8.5px |
| 渲染 | `js/todoFace.js` `renderCapsule()` | 改前并排两段（`8 待办` + `1 逾期` 同时存在） |

改前实测（`evidence/20261102_todo-capsule/logs/before-HEAD-sizes.log`，`git worktree` 于 `73c305f81`）：1280 下胶囊 `w=450, h=11, x=319, y=69`，卡容器 `h=30`，文本 `"8 待办1 逾期"`，`capWidthRatio=1`；1440 下 `w=530`。

## 2. 契约（几何与 token）

| 项 | 值 | 取值理由 |
|---|---|---|
| 胶囊高 `--tfl-cap-h` | **26px** | 4px 栅格；11px 字 + 上下各 7.5px 对称余量 |
| 左右内距 `--tfl-cap-pad-x` | **12px** | 左右同值 ⇒ 内距差 0；= 半径 13 内收一档 |
| 圆角 `--tfl-cap-radius` | **999px** | 全圆胶囊（扁平按钮族同语言） |
| 点↔字间距 `--tfl-cap-gap` | **6px** | 与槽位呼吸位同档 |
| 圆点 | **6px** | 中性色 38% 混色；逾期态 `--tfl-red` |
| 字体 | `500 11px/1` ui-monospace + `tabular-nums` | 等宽数字 ⇒ 数字增减不抖动 |
| 胶囊 ↔ 输入框呼吸 | `#task-list { padding: 6px 8px }` | 4px 栅格；计入 `#input-area.offsetHeight` ⇒ 聊天让位自动带上 |
| 面板宽 | `min(420px, calc(100vw - 32px))` | 与胶囊同中线；窄屏不溢出 |
| 面板锚 | `position:absolute; bottom:calc(100% + 8px)` | 锚在 `#input-area` 顶缘之上 8px，**不占输入区高度** |
| 面板圆角 | `19px 19px 24px 24px` | 沿用刘海语言 `--tfl-rt/--tfl-rb`（ReminderIsland NotchView） |

**居中三重（作者「上下左右都居中」）**：① 外框 `inline-flex + align-items:center + line-height:1` ⇒ 文字行盒中心 = 胶囊中心；② 圆点与文字同一 flex 行 ⇒ cy 同轴；③ 左右 `padding` 同值 + 宽度 = 内容 + 2×pad ⇒ 内距差 0。

**为什么 `#task-list` 必须是 `position: static`**：`.tfl-card` 是 `position:absolute`，它的包含块必须是 `#input-area`（`position:absolute`）才能 `bottom:100%` = 「整个输入区之上」。若在 `#task-list` / `.tfl-mount` 上建定位祖先，面板会贴胶囊行下缘、**压住输入框本身**（首版实测 `panel.b 856 vs area.y 780` 失败，改成 `static` 后 `panel.b 856 ≤ area.y 856` 通过）。

**`pointer-events` 的一个真回归**：首版给 `#task-list` 写 `pointer-events:none`，导致旧 delegate 链渲染路径（`js/taskList.js`）的 `.task-card` 点击透传给 `#chat`，`delegate-chain-stub` T7/T10、`tasklist-chain-blocks` T2 三例报 `#chat intercepts pointer events` 卡死超时。改回 `auto`（槽位在输入区内部，不遮挡聊天体）后三例全绿。

## 3. 改动面

- `src/main/resources/web/index.html:838-843 / 904-909`：`#task-list` 槽位从顶带 `#top-overlays` 迁入 `#input-area`（`#input-bar` 之后），两处各留批次注记。顶带让位公式（`js/main.js` `bandBottom`）只读 `#header`/`#top-overlays` 盒子 ⇒ 节点离场后自带收缩，零 JS 改动。
- `src/main/resources/web/js/todoFace.js`：
  - `buildDom()` L175-238：新增 `.tfl-mount` 居中行；胶囊结构 = `button.tfl-capsule` > `.tfl-capsule-dot` + `.tfl-cap-faces`（内含 `.tfl-cap-face-todo`（`.tfl-capsule-count`）与 `.tfl-cap-face-overdue`（`.tfl-capsule-overdue`）两格叠层）；DOM 顺序 `mount > card > capsule`（声明顺序 = 视觉顺序：面板在胶囊上方）。
  - `renderCapsule()` L339-376：`overdue = overdueCount() > 0` ⇒ `has-overdue`/`is-overdue` 类；**只在面真的切换那一拍**挂 `.tfl-cap-flip`（260ms 后摘，同一面内数字增减不重播）；两面切 `aria-hidden`，胶囊 `aria-label` 带出当期唯一读数。
  - `expand()/collapse()/measurePanel()/pop()` L888-972：去掉旧的"卡容器高度弹簧"，改 **scale 弹簧**（0.94↔1，`transform-origin:50% calc(100%+8px)` = 胶囊中心 ⇒ 视觉上"从胶囊长出来"）；collapse 时数据态在弹簧落地后才翻成 `capsule`（保证有退场）；`pop()` 改为胶囊 scale 1.08 弹跳（**不动宽高**）。
  - `bindOutsideClose()/unbindOutsideClose()` L973-1020：document capture `pointerdown`（含 `path.includes(this.mount)` 判定）+ **capture `keydown` Esc**（焦点不在面板子树才接管，`isImeComposing` 期间不接管），解绑时一并移除。
  - `handleEscape()` L872-886：浏览态 Esc 改走 `collapse()`。
- `src/main/resources/web/css/todo-face.css`：`#task-list` 槽位 L49-83（`static`/`padding:6px 8px`/`overflow:visible`/`pointer-events:auto`/`:not(.has-tasks){display:none}`）；`.tfl-mount` L87-100；胶囊 token 与形态 L115-197（含 `.tfl-cap-faces` inline-grid 叠层 + `@keyframes tfl-cap-flip`）；`.tfl-card` L199-249（绝对浮层 + 玻璃 + 双主题阴影）；手机变体 L866-878；reduced-motion L881+。
- 新增 `tests/todo-capsule-redesign-shots.mjs`（九条断言 + 实拍证据）、`tests/capsule-before-shots.mjs`（改前对照帧，只用旧选择器 ⇒ 能在 `73c305f81` 快照树上跑）。
- `tests/visual/baseline/darwin-chromium-151/{canvas-code-tab-dark.png, canvas-markdown-tab-dark.png, manifest.json}`：**按改动期望重采集**（归因见 §5）。

## 4. 尺寸读数表

**改后**（`evidence/20261102_todo-capsule/logs/todo-capsule-redesign.log`，`metrics` / `panelMetrics` 行）：

| 视口 | 胶囊 W×H | 占输入区 | 水平偏差 | 行盒偏差 | 圆点偏差 | 内距 L/R | 两态宽/高差 | 面板 W×H | 面板居中 | 面板底↔输入区顶 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1280 亮 | 71.2 × 26 | **15.2%** | 0px | 0px | 0px | 12/12 | 0 / 0 | 420 × 373.8 | 0px | 8px |
| 1280 暗 | 71.2 × 26 | 15.2% | 0px | 0px | 0px | 12/12 | 0 / 0 | 420 × 373.8 | 0px | 8px |
| 1440 亮 | 71.2 × 26 | **13.0%** | 0px | 0px | 0px | 12/12 | 0 / 0 | 420 × 373.8 | 0px | 8px |
| 1440 暗 | 71.2 × 26 | 13.0% | 0px | 0px | 0px | 12/12 | 0 / 0 | 420 × 373.8 | 0px | 8px |
| 1440 暗 reduced-motion | 71.2 × 26 | 13.0% | 0px | 0px | 0px | 12/12 | 0 / 0 | 420 × 373.8 | 0px | 8px |

**改前**（`logs/before-HEAD-sizes.log`，同一脚本在 `73c305f81` 快照工作树上跑）：

| 视口 | 胶囊 W×H | 占输入区 | 位置 | 文案 |
|---|---|---|---|---|
| 1280 亮/暗 | **450 × 11** | **100%** | 顶带 `x=319, y=69` | `8 待办1 逾期`（并排） |
| 1440 亮/暗 | **530 × 11** | **100%** | 顶带 `x=319, y=69` | `8 待办1 逾期`（并排） |

改前同一脚本读数：`13 pass / 30 fail`（`logs/before-HEAD-capsule-probe.log`）—— 失败项正是本批要立的九条断言（`逾期态显示「1 逾期」` / `胶囊宽度 < 输入区 × 40%` / 行盒居中 等），可作「断言不是事后编的」的证据。

## 5. 测试账本

| 套件 | 结果 | 日志 |
|---|---|---|
| `node tests/todo-capsule-redesign-shots.mjs`（九条断言 + 证据） | **187 pass / 0 fail** | `logs/todo-capsule-redesign.log` |
| `node tests/todo-face-shots.mjs`（既有 todo suite） | **116 pass / 0 fail** | `logs/todo-face-shots.log` |
| 同上，`73c305f81` 干净基线（同一施加了 §5.1 夹具修正的脚本） | **112 pass / 0 fail** | `logs/before-HEAD-todo-face-shots.log` |
| `npx playwright test tests/mobile-dock.spec.mjs tests/mobile-pinned-search.spec.mjs` | **32 passed** | `logs/mobile-dock+pinned-search.log` |
| `node tests/mobile-panel-title.spec.mjs`（独立脚本） | ALL PASS | `logs/mobile-panel-title.log` |
| `npx playwright test tests/mobile-topbar.spec.mjs` | **3 passed** | `logs/mobile-topbar.log` |
| `node tests/ime-guard.spec.mjs`（独立脚本） | ALL PASS | `logs/ime-guard.log` |
| `npx playwright test delegate-chain-stub / taskbar-flow-overlap / tasklist-chain-blocks` | **24 passed / 1 failed**（该 1 项 = 既有红，见下） | `logs/chain-panel-legacy.log` |
| `node scripts/visual-baseline.mjs --check` | canvas 四帧 SAME；余 8 项既有红（非本批） | `logs/visual-baseline-check.log` |

**既有红的归因（不甩锅、逐条给证据）**：
- `tasklist-chain-blocks` T1（`iconBox.y - glyphBox.y = 2.48 > 2`）：**在 `73c305f81` 干净基线工作树上同样红**（`logs/before-HEAD-chain-legacy.log`，同一断言、同一量级 `3.43`）。本批 `#task-list` 槽位与 `.task-card` 几何零触碰（只改 `.tfl-*` 面与槽位的 `position/padding/pointer-events`），该断言的元素 `.task-delegate-icon` / `.task-node-spin` 都在 delegate 链块内 ⇒ 既有红。
- visual baseline 8 项（app-empty / chat-messages / settings-llm-empty / bgtask-panel，各亮暗）= **既有红，且是同一批既有红**：在 `73c305f81`（本批起点）上这 8 项同样红（`logs/before-HEAD-visual-baseline-check.log`）。根因与本批无关 —— 存库基准的 `app-empty` 场景里**根本没有待办胶囊**，而 `73c305f81` 的实现已经在该场景渲染出顶带胶囊（改前读数 `diff=16963px (1.309%) bbox={x0:310,y0:27,x1:857,y1:97}`，正是顶带那一行；存库帧同位置为空）。本批只是把同一个胶囊从顶带挪到输入框下方，于是差异区跟着搬家、像素数变大（`diff=58850px (4.541%) bbox={x0:289,y0:27,x1:878,y1:899}`），但**红的那 8 项一字不差**。这类「场景基准早于待办面落地」的期望漂移应由待办面自己的重采批次统一处理，本批不擅自扩权去改 6 张场景基准。
- canvas 两帧暗色差异**是本批的**：逐像素 `#242730 → #24272F`（B 通道 48→47），位于 `x=0, y=791..855` —— `#input-area` 新增的 6px 上下 padding 把玻璃底缘抬进视口，A/B 合成降 1 级。已按期望重采集（light 两帧 md5 未变），复跑全量 canvas 四帧 SAME。

**申报**：任务书的 `make check-visual`（`Makefile:27`）在本机不可用，已按指令改跑 `scripts/visual-baseline.mjs --check`（在 `73c305f81` 快照树上需要 `NEBFLOW_PW_PATH` 指向主仓 `node_modules/playwright`）。

### 5.1 既有 todo suite 的一处夹具修正（必须申报）

`tests/todo-face-shots.mjs` 的 I 段「点击外部收起」原本写死点击点 `(720, 600)`，注释是「聊天体空白」。**该点在改前成立、在本批之后不成立**：面板从 30px 高的条变成 420×374 的浮层，`(720, 600)` 现在落在**面板内部** —— 于是这条断言退化成了「点面板内部应能收起」，语义已不是它自称的「点外部」。

改法是让夹具自己证明「点的是外部」，而不是换一个魔法数字：
1. 取面板盒子，取它左侧 24px 之外、纵向落在面板中线的点（`x = max(8, round(card.left - 24))`）；
2. **先断言** `elementFromPoint(x, y)` 命中的元素不在 `.tfl-card` 内（新增断言 `click-outside target is truly outside the panel`）；
3. 再点。

改后该套件 **116 pass / 0 fail**（比基线多出的 4 条就是上述自证断言，chromium/webkit × 亮/暗）；同一脚本（含修正）在 `73c305f81` 干净基线上跑 **112 pass / 0 fail** —— 修正只补了自证，没有放宽任何判定。

## 6. 证据

- `evidence/20261102_todo-capsule/shots/`（57 张）：1280/1440 × 亮/暗 × {逾期帧、待办帧、两态胶囊特写、三位数、hover、focus、展开、收起后}；1440 暗另有 reduced-motion 一整腿；手机 375 亮/暗各一帧（手机面零影响）。
- `evidence/20261102_todo-capsule/shots-before/`（14 张）：同一脚本在 `73c305f81` 快照树上的改前对照帧。
- `evidence/20261102_todo-capsule/logs/`：断言日志、改前读数、各套件日志、visual baseline 复跑日志。

## 7. 未做 / 边界

- 手机面零改动：`mobileLayer.css` 仍把 `#task-list` 整条 `display:none`，手机共用 `.tfl-mobile` 展开页（本批只加 `.tfl-mount-mobile` 兜底与 `.tfl-mount-mobile .tfl-capsule{display:none}`）。
- 面板内容（列表/筛选/标签）沿用既有实现，本批只改容器几何与出场动效。
- 与 [[20261102_title-capsule-height__delegate-nebflow-f003855e]] 的 48 档无冲突：那是手机面板头吸附胶囊，本批是桌面输入框下方小胶囊，两档分属两个面。
