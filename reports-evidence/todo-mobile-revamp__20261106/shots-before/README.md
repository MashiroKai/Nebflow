# shots-before — 手机端「任务」页改前基线实拍（todo-mobile-revamp 批 · 2026-11-06）

本目录 = 本批报告的**左半栏**：在任何实现改动落地**之前**，对 worktree 现役手机端「任务」页
（`chatChrome [data-chrome='tasks']` → `navController pushPage('tasks')` → `js/mobile/taskPanel.js`
→ `js/todoFace.js`）所拍的**实拍帧 + 几何读数**。后续实现落地后请用同一脚本跑「改后腿」做逐项对照。

- worktree：`/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/todo-mobile-revamp`
- 拍前基点：`HEAD = ade35a92d`（本目录提交前，`src/` 零改动）
- 复现：`node reports-evidence/todo-mobile-revamp__20261106/shots-before/capture-before.mjs`

## 1. 诚实申报（读这张表前请先读本节）

| 项 | 事实 |
|---|---|
| 渲染链 | **真链**。真 `index.html` + 真 `js/*`（`bootstrap.js` 自举 `body.mobile-layer`、`chatChrome` 圆钮、`navController.pushPage('tasks')`、`taskPanel.ensureTaskPanel`、`todoFace` 面板/抽屉全部走产品代码），无桩替换、无 DOM 手搓。 |
| 静态面 | 真 web 树 `src/main/resources/web` 由 `page.route` 从磁盘逐文件注入（`tests/_todo-face-harness.mjs` 同源零后端法），**无本地端口、无 sbt、无宿主实例**（不碰 `:6138`）。 |
| 数据面 | **夹具**。WS 帧桩只发 `configData` / `sessionList` / 空 `historyPage`；待办数据经 `todoStore.__seed({ tagList: [], itemList: [] })` 本地种入（夹具模式，不发写帧）。⇒ 页面上的「0 待办 / 暂无提醒 / 空 chips」是**夹具构造的空列表态**，不是真实用户数据。 |
| 视口 | 375×812 CSS px、`deviceScaleFactor: 2`、`hasTouch: true`、`isMobile: true`（`matchMedia(max-width:768px)` 命中 ⇒ `body.mobile-layer` 激活，已断言）。 |
| 已知噪声 | 2 条 `404 http://localhost:1/agents/Nebula/agent.json`（前端会向该路径取 agent 配置，零后端桩不提供）—— 不影响任何被测几何，不遮蔽任何截图区域。 |
| 确定性 | 同一脚本连跑两次，三张 PNG **逐字节 md5 一致**（`938ada82…` / `1fe8f110…` / `105a8436…`），`readings-before.json` 内容一致 ⇒ 可作基线。 |

## 2. 截图清单（像素尺寸 + 目标元素 rect）

座标系 = CSS px（截图物理像素 = 下列值 ×2）。`page` = `.mobile-page[data-page="tasks"]`，rect `{x:0,y:0,w:375,h:750}`（页面体止于 Tab 栏上缘 750，`#mobile-dock` rect `{x:12,y:750,w:351,h:54}`）。

### tasks-01-page.png —— 空列表态整页（PNG **750×1624**）

拍法：入口圆钮 → 任务页 → 点 `.tfl-filter-toggle` 展开筛选栏 → 整页截图（`fullPage:false`，视口帧）。页面文案实读：`任务 / 0 待办 / 全部 / 无 低 中 高 / 暂无提醒`。

| 目标元素 | 选择器 | rect (x,y,w,h) |
|---|---|---|
| 返回圆钮 | `.mobile-back-btn.g2-fab` | 16, 16, 48, 48 |
| 任务标题 | `.mobile-page-title` | 87.77, 12, 199.45, 52 |
| 计数「0 待办」 | `.tfl-header-count` | 22, 72.2, 41.61, 15.59 |
| 表头行 | `.tfl-header` | 16, 64, 343, 33 |
| 筛选图标 | `.tfl-filter-toggle` | 325, 68, 28, 24（命中盒 44×44，见 §3） |
| 筛选区（展开态） | `.tfl-filter-wrap` | 16, 97, 343, 67 |
| chips 行 | `.tfl-chips-row` | 26, 101, 323, 26 |
| 「全部」chip | `.tfl-chip.tfl-chip-all.selected` | 26, 101, 49, 26 |
| 新建标签「+」 | `.tfl-chip-add` | 79, 101, 24, 26 |
| 垃圾桶 | `.tfl-chip-trash` | 325, 101, 24, 26 |
| 优先级四签 无/低/中/高 | `.tfl-chip-prio` ×4 | 26/63/100/137, 131, 35, 26 |
| 列表滚动面 | `.tfl-list` | 16, 164, 343, 586 |
| 右下新建加号 | `.tfl-fab-add.g2-fab` | 311, 686, 48, 48 |
| 空态文案（「暂无提醒」） | `.tfl-empty` | 拍本帧时在场（抽屉打开时被清走 ⇒ 见 §2.2 表内为 null） |

### tasks-02-drawer.png —— 新建抽屉 + 标题框聚焦蓝边（PNG **750×1624**）

拍法：`.tfl-fab-add` 点击 → 等 `.mf-drawer:not([hidden])` → 点抽屉内第一个 `input`（`.tfl-row-title-input`）→ 400ms 后整页截图。

| 目标元素 | 选择器 | rect (x,y,w,h) |
|---|---|---|
| 抽屉（含遮罩） | `.mf-drawer` | 0, 0, 375, 750 |
| 抽屉箱体 | `.mf-drawer-box` | 0, 547.42, 375, 202.58 |
| 抽屉标题「新建待办」 | `.mf-drawer-title` | 16, 561.42, 255, 18 |
| 取消 | `.mf-drawer-cancel-btn` | 275, 548.42, 44, 44 |
| 完成 | `.mf-drawer-done-btn` | 323, 548.42, 44, 44 |
| 标题输入框（**焦点蓝边真身**） | `.tfl-row-title-input` | 34, 605.42, 319, **16.19** |
| 备注输入框 | `.tfl-edit-notes` | 22, 635.61, 331, 23.39 |
| 抽屉内优先级签 ×4 | `.tfl-edit-prio-btn` | 22/56/90/124, 665, 32, 26 |
| 「无截止时间」开关 | `.nb-toggle` | 81, 697, 51, 31（命中盒 51×46） |

蓝边取证（`_probe-log.json` → `log.focusRings`）：本页 **唯一**带非零 outline 的元素就是
`input.tfl-row-title-input`，`2px solid color(srgb 0.203765 0.423529 0.785255) offset -1px`，
`border-width: 0px` ⇒ 视觉上的「加粗蓝框」= `css/inputFocus.css` 的统一焦点规则（`:focus` 2px 内缩描边），
不是组件自写边框；`document.activeElement` 同名同框。注意输入框**自身盒只有 16.19 高**，
视觉行高来自外层行容器 ⇒ 命中盒读数以本节表为准，不要拿 44px 的观感去推输入框的 rect。

### tasks-03-top-controls.png —— 顶部控制区特写（PNG **718×232**）

拍法：`clip` = `.tfl-header` ∪ `.tfl-filter-wrap` 外扩 8px → `{x:8, y:56, width:359, height:116}`（CSS px ⇒ 359×116，×2 = 718×232）。含「0 待办 / 筛选图标 / 全部 / + / 垃圾桶 / 无 低 中 高」全量，无 Tab 栏、无列表。

## 3. readings-before.json —— 可点元素 rect + 命中外扩

格式：`[{ "selector", "w", "h", "hitW", "hitH" }, ...]`（19 条），全部为 `getBoundingClientRect()` 的 **CSS px 真读数**。

- `w`/`h` = 元素自身 border box；`hitW`/`hitH` = 元素盒 ∪ `::after`/`::before` 命中外扩的并集外包尺寸。
- 伪元素算不出来的（`left/right/top/bottom` 非 px 或存在不可解析值时）记 `null`（本批 **0 条 null**）。
- 计入伪元素的条件：有 `content`、非 `display:none`/`visibility:hidden`、`position` 为 `absolute`/`fixed`、且**不吃指针**（`pointer-events:none` 的伪元素不算命中盒）。
- 取点时序：第 1～10 条量于 **tasks-01 状态**（筛选展开、抽屉闭合）；第 11～19 条量于 **tasks-02 状态**（抽屉打开），选择器路径含 `.mf-drawer` 可自证来源。
- 范围：`.mobile-page[data-page="tasks"]` ∪ `#mobile-task-panel` ∪ `#todo-island` 子树内、且当时可见（rect > 0.5px）的
  `button / a / [role=button] / input / select / textarea / [tabindex]:not(-1) / .tfl-row / .tfl-chip(-trash/-add/-arrow) / .tfl-fab-add / .tfl-icon-btn / .mf-drawer-*-btn / .tfl-undo-btn`。
- **不含**：底部 Tab 栏五键（`#mobile-dock`，不属本页实现面）、抽屉遮罩 `.mf-drawer-scrim`（非按钮）、`.tfl-row` 列表行（空列表态无行，故本批 0 条）、隐藏件（`.tw-trigger`、`.tfl-due-input` 在关闭态为 0×0，已剔除）。

### 基线下限读数（后续「最小可点尺寸」机检的对照组）

| 元素 | w×h | 命中盒 hitW×hitH | 判定（<44 即不达标） |
|---|---|---|---|
| 筛选图标 `.tfl-filter-toggle` | 28×24 | **44×44** | 元素盒不达标，靠 `::after` 外扩补到 44 |
| 「全部」`.tfl-chip-all` | 49×26 | 49×26 | 高 26 不达标 |
| 「+」`.tfl-chip-add` | 24×26 | 24×26 | 宽高均不达标 |
| 垃圾桶 `.tfl-chip-trash` | 24×26 | 24×26 | 宽高均不达标 |
| 优先级签 `.tfl-chip-prio` ×4 | 35×26 | 35×26 | 高 26 不达标 |
| 抽屉标题框 `.tfl-row-title-input` | 319×16.19 | 319×16.19 | 自身盒极扁（外层行承担视觉） |
| 备注框 `.tfl-edit-notes` | 331×23.39 | 331×23.39 | 高 23.39 不达标 |
| 抽屉优先级签 `.tfl-edit-prio-btn` ×4 | 32×26 | 32×26 | 宽高均不达标 |
| 「无截止时间」`.nb-toggle` | 51×31 | **51×46** | 元素盒不达标，靠 `::after` 外扩补到 46 |
| 返回钮 / 新建加号 / 取消 / 完成 | 48×48 / 48×48 / 44×44 / 44×44 | 同元素盒 | 达标 |

> 结论（仅陈述读数，不代行改造决策）：本页**唯一**达到 44×44 命中盒的小控件是 `.tfl-filter-toggle`
> 与 `.nb-toggle`（均靠伪元素外扩）；chips 族（含垃圾桶、新建标签、优先级两处）全部 26 高、≤35 宽。

## 4. 未做 / 开放项

- 只拍**亮色**一档（`colorScheme` 默认 light）；暗色腿、双引擎（Chromium 之外）**未拍**。
- 只拍**空列表态**：有数据行的 `.tfl-row`、标签 chips、编辑面板、撤回条读数**未覆盖**（本批报告左半栏只要求空态四件）。
- 触屏证据法：本脚本用 `hasTouch: true` 的 Playwright 上下文点击，**非 CDP 真触屏**；若后续需要真触屏判据，须另走 CDP `Input.dispatchTouchEvent` 腿。
- `readings-before.json` 的 `hitW/hitH` 是**几何外包**估算（元素盒 ∪ 伪元素盒），非 `elementFromPoint` 二分实测；两者在本批交集处一致（`.tfl-filter-toggle` 44×44 与既有二分法读数相同），但不保证所有元素等价。
- 本文件按任务书指定路径落在 `reports-evidence/`（过程证据面，非知识文档 vault）；若需入 `.stand/docs/` 知识面请另令。
