# 设计规格书 · Nebflow 客户端「Header 按钮碰撞体积 + 自适应隐藏」（#396）

- 日期：2026-08-25 · 作者：design-engineer · 状态：**frozen（用户 08-25 拍板冻结）**
- 任务来源：用户 08-25 13:24 裁定——Header 按钮做碰撞体积：**任意宽度零图标重叠（硬约束）**；空间不足时固定保留「左右收起/展开 + 上下文窗口用量 + subagent/后台任务显示 + Nebula/memory 入口」；其余按体积自动隐藏，隐藏优先级由本规格书定义。
- 适用代码：`src/main/resources/web/index.html`（`#header`）· `css/chat.css`（`#header` / `.header-left` / `.header-center` / `.header-right`）· `js/main.js`（`initHeaderResizeObserver`）。
- 铁律依据：`~/.nebflow/skills/nebflow/visual-style/SKILL.md`（最高优先级）。
- **用户裁定（2026-08-25 拍板，仅自动隐藏）**：Header 分级方案**不建 `⋯` overflow 菜单**——Tier B overflow 收纳设计**删除**；非固定按钮只按优先级（P1→P5）自动隐藏；保留 Tier A 隐藏 + Tier C 超窄兜底固定集（§4.2 已修订，§8 新增 A7 锁定「不建 overflow」）。

---

## 0 · 一句话目标

把现在「宽度不足时图标直接溢出/被裁、`.header-compact` 无任何 CSS 效果（死代码）」的 Header，改为**度量驱动的自适应**：固定保留集永不隐藏、其余按优先级（P1→P5）自动隐藏（**不建 `⋯` overflow 菜单**，用户 08-25 裁定「仅自动隐藏」），**任意宽度零图标重叠**，全程克制专业。

## 1 · 参考与依据

| 来源 | 提炼的可执行规则 | 链接 |
|---|---|---|
| PatternFly · Overflow menu | ① 空间不足才用 overflow（把放不下的动作收进 `⋯` kebab）；② 桌面端 overflow 收「**次要**动作」，移动端可把主/次动作一并收起；③ **≤2 个动作不用 overflow**；④ overflow 按钮放**容器右侧**；⑤ 用 kebab 图标；⑥ 不要用它隐藏「你不想给用户看」的内容，应该用 expandable section | https://www.patternfly.org/components/overflow-menu/design-guidelines/ |
| PatternFly · Toolbar（按钮分级） | ① 工具栏「**要有选择性**地露出动作」；② **1-2 个动作**：primary/secondary 按钮直出；③ **3 个动作**：1 个露出 + 其余收 overflow（避免 overflow 只含 1 项）；④ **>3 个**：primary + secondary 露出，其余 overflow；⑤ **小视口下 primary/secondary 也可收进 overflow**；⑥ 图标组进一步省空间；⑦ **bulk selector + 排序图标在所有断点保持可见**（=「固定保留集」），过滤/动作收进 toggle group / overflow；⑧ 移动端「折叠或隐藏占用空间大的元素」 | https://www.patternfly.org/components/toolbar/design-guidelines/ |
| Fluent 2 · Toolbar | 空间不足时「把最后一个选项变成 overflow 菜单按钮，露出其余工具」；overflow 内工具带文字标签较清晰 | https://fluent2.microsoft.design/components/web/react/core/toolbar/usage |
| KendoReact · Toolbar adaptive | 随容器尺寸「调整溢出工具的渲染」——度量驱动，非固定断点 | https://www.telerik.com/kendo-react-ui/components/buttons/toolbar/adaptive-rendering |
| Nebflow 现状（#header） | `.header-center` 绝对居中（`left:50%; translateX(-50%); max-width:70%`）但 `.header-right` `margin-left:auto`、左/右都是 `flex:0 0 auto`；**窄宽度时绝对居中的 center 会与左/右簇重叠/被裁**；`initHeaderResizeObserver` 只 toggle `header-compact` 类但**无对应 CSS 规则**（死代码） | `src/main/resources/web/css/chat.css` `#header` 段 · `js/main.js` `initHeaderResizeObserver` |
| Nebflow visual-style 铁律 | 可交互控件玻璃质感；不造新轮子；低调克制；Header 自家玻璃条 `--glass-bg`+`--glass-blur` | `~/.nebflow/skills/nebflow/visual-style/SKILL.md` |

**冲突取舍**：经用户 08-25 拍板「仅自动隐藏」——**不采用** PatternFly/Fluent 的 `⋯` overflow 收纳方案（§1 相关参考仅借鉴「隐藏优先级/分级」与「固定集在所有断点可见」的思想，**不采纳 overflow 组件**）。隐藏项直接 `display:none` 降级为「空间不足时自动隐藏」，放弃 overflow 可发现性；以「固定集永不隐藏 + 优先级隐藏」满足 #396 硬约束，隐藏项的可替代路径（快捷键/命令面板/popup）在 P 序理由中交代。

## 2 · Header 全部按钮/入口清单（**全清单**，读前端源码确认）

> 位置均依 `index.html` `#header`（140-175 行）。**隐藏优先级（Hide Priority）**：数值越小**越先隐藏**（空间不足时先没掉）；「固定」= #396 裁定永不隐藏，不参与 priority 排序。

### 2.1 Header Left（`.header-left`）

| # | id | 名称 | 功能 | 当前可隐藏? | 固定? | Hide Priority |
|---|---|---|---|---|---|---|
| L1 | `#sidebar-toggle`（`.panel-toggle-btn`） | **左侧收起/展开** | 收起/展开侧边栏 | 否 | ✅ **固定** | — |
| L2 | `#header-model-info`（`.ctx-ring-wrap`） | **上下文窗口用量环** | 显示 context 占用 %（绿/琥珀/红 + 阈值刻度），tooltip 给 model/token 明细 | 否（仅无数据时 `display:none`） | ✅ **固定** | — |

### 2.2 Header Center（`.header-center`，绝对居中）

| # | id | 名称 | 功能 | 当前可隐藏? | 固定? | Hide Priority |
|---|---|---|---|---|---|---|
| C1 | `#session-name` | 会话名 | 显示当前会话名（white-space:nowrap） | 否（可省略） | 非固定 | P5（先截断省略，再隐藏） |
| C2 | `#memory-btn`（`.memory-btn`） | **Nebula/Memory 入口** | 打开 Memory 编辑器（Agent/User 两个 tab）；由 `showMemoryButton()` 控制显隐 | 是（`hidden` attr） | ✅ **固定** | — |

### 2.3 Header Right（`.header-right`）

| # | id | 名称 | 功能 | 当前可隐藏? | 固定? | Hide Priority |
|---|---|---|---|---|---|---|
| R1 | `#search-btn` | 搜索聊天记录 | 打开消息搜索弹窗 | 否 | 非固定 | **P3** |
| R2 | `#bypass-toggle`（`.bypass-toggle`） | 编辑模式（安全模式） | 切换 confirm-edits/auto-edits/auto-all 三态下拉 | 否 | 非固定 | **P1（最先隐藏）** |
| R3 | `#voice-toggle-btn`（`.voice-toggle`） | 语音输出开关 | 切换 voice output | 否 | 非固定 | **P2** |
| R4 | `#reminder-btn` | 定时任务 | 打开 Scheduled Tasks 面板 | 否 | 非固定 | **P4** |
| R5 | `#daemon-btn` | 心跳进程 | 打开 Heartbeat/daemon 面板 | 否 | 非固定 | **P5（与 C1 同级，先于 C1 隐藏）** |
| R6 | `#bg-indicator` | **后台任务显示** | 绿色胶囊（dot+count），显示 Bash 后台任务 | 是（`.hidden`） | ✅ **固定** | — |
| R7 | `#bgagent-indicator` | **subagent 显示** | sapphire 胶囊（dot+count），显示 Sub-Agents 运行中 | 是（`.hidden`） | ✅ **固定** | — |
| R8 | `#canvas-toggle-btn`（`.panel-toggle-btn`） | **右侧收起/展开（Canvas）** | 收起/展开 Canvas 面板 | 否 | ✅ **固定** | — |

### 2.4 现有响应式处理的问题（现状诊断）

- `initHeaderResizeObserver` 在 `.header-center` `clientWidth<140` 加 `header-compact`、`>300` 移除；但 **`.header-compact` 在任意 CSS 无规则** → 纯死代码，缩窄后**无任何视觉变化**。
- `.header-center` 绝对居中 + `max-width:70%`，窄宽度时其内容可越过左/右簇导致**重叠**（正是 #396 要修的问题）。
- `.header-right` 各按钮 `flex:0 0 auto`（不收缩），空间不足时**溢出被裁**，而不是隐藏/收纳。

## 3 · 固定保留集 + 隐藏优先级排序

### 3.1 固定保留集（#396 裁定，任意宽度保留）
`L1 左侧收起展开` + `L2 上下文用量环` + `C2 Nebula/Memory` + `R6 后台任务` + `R7 subagent` + `R8 右侧收起展开`。共 6 个固定入口。

### 3.2 隐藏优先级（空间不足时，从 P1 开始依次隐藏）

| 优先级 | 入口 | 理由（为什么先藏它） |
|---|---|---|
| **P1** | `#bypass-toggle`（编辑模式） | 模式是**低频且可从行为推断**（用户正在编辑或放行，行为本身即信号）；且非"看状态"必备。最先藏。 |
| **P2** | `#voice-toggle-btn`（语音输出） | **小众功能**（语音输出开关），绝大多数用户少用；图标仅表示"开/关"，非关键当前态。 |
| **P3** | `#search-btn`（搜索） | 搜索**重要但可替代**——有快捷键（⌘F/Ctrl+F 习惯，见消息搜索规格书的快捷键绑定），藏了仍可键盘/命令面板到达。 |
| **P4** | `#reminder-btn`（定时任务） | 定时任务需主动管理，但**非高频**；`reminder-btn` 直接隐藏可接受（不建 overflow 逃逸口，藏即直出消失）。 |
| **P5** | `#daemon-btn`（心跳进程）+ `#session-name`（截断→隐藏） | 心跳/进程属**技术/调试导向**，最低用户价值；`session-name` 是**信息回显**（会话名也显示在 sidebar/消息流），先 `text-overflow:ellipsis` 截断，真不够再隐藏。 |

**排序总原则**：`低频/可有替代入口 ≤ 高频/必备`。越「非当前态必备、越有替代路径」越先藏；`session-name` 特殊——它**先截断（不消失）**再隐藏，属「信息降级」而非「功能隐藏」。

## 4 · 碰撞检测 / 自适应布局逻辑（flex-wrap 禁用降级策略）

### 4.1 为什么禁用 flex-wrap 与「直接 overflow 裁切」
- Header 是**三簇布局**（left/center-absolute/right），`flex-wrap` 会让按钮掉到第二行、破坏绝对居中的 session 名与玻璃条高度，且「换行 ≠ 隐藏」——不解决碰撞，反而打乱结构。
- 现「溢出被裁」则丢失可发现性。
- **结论**：改用**度量驱动的优先级填充（priority+）**，逐项按实测宽度排布，放不下就藏（**不建 `⋯` overflow 菜单**，用户裁定仅自动隐藏）。

### 4.2 分级策略（先隐藏低优先级 icon，超窄兜底固定集）

**Tier A · 隐藏（P1→P5）**：空间不足时，从 P1 起逐项 `display:none` 隐藏非固定按钮。隐藏即**退出占用**，为高位按钮让空间。**经用户 08-25 拍板（仅自动隐藏）：不建 `⋯` overflow 菜单——隐藏项直接消失，不做收纳逃逸口。**

**Tier C · 兜底（超窄）**：极窄（<380px、固定集已近满宽）时仅保留 6 个固定入口，其余非固定项全部隐藏——此时**不得遮挡/重叠**（保证硬约束）。属可接受下限。

### 4.3 碰撞/重叠保证机制（零重叠的数学保证）

Header 是三簇；**中心簇必须被「左簇实际宽 + 右簇实际宽」夹在中间**。核心用**实测宽度预算 + 动态 clamp**，而非静态断点：

1. **实测**：用 `ResizeObserver` 监听 `#main`/`#header` 宽度变化；对 `.header-left` 与 `.header-right` 用 `getBoundingClientRect().width` 实测（左/右簇内固定项始终可见，宽度已知；右簇随 P 序隐藏而变化）。
2. **右簇填充**：自 P1 起，按优先级**贪心累加**每个非固定按钮的 `offsetWidth`；当「已有右簇宽 + 下一项宽」超过当前可用宽度 → 停止直出，剩余项**隐藏**（`display:none`，**不建 overflow**）。每次重排前先重置上次的显隐状态。
3. **中心簇 clamp**：`.header-center` 的 `max-width` 动态置为 `(headerW - leftClusterW - rightClusterW - 2·gap - padding)`；`min-width` 置为 `(memory-btn 28 + gap)`。`#session-name` 在其内 `text-overflow:ellipsis`。**这保证中心簇几何上永远落在左/右簇之间** → 零重叠。
4. **右簇内顺序**：固定项（R6/R7 胶囊、R8 toggle）恒在右簇；非固定项在固定项左侧按 P 序排布（**无 `⋯` overflow 按钮**）。
5. **无 flex-wrap**：各簇 `flex:0 0 auto`（不收缩）；重排仅通过 `display:none`，不改变文档流导致重排碰撞。

> **零重叠判定（实现自检）**：对任意宽度 W，遍历所有可见元素，取其 `getBoundingClientRect()`，断言两两不相交（或在同一簇内有 0 间距但无重叠）。此即 §8 A2 断言的自动化基础。

### 4.4 Header 三簇与现有 CSS 的改造点

| 现有 | 改造 | 目的 |
|---|---|---|
| `.header-center` `max-width:70%` 静态 | 改为由 JS 实测 clamp（动态设 `max-width`） | 中心簇不越界 → 防重叠 |
| `.header-compact` 死类（无 CSS） | **删除** `initHeaderResizeObserver` 的死代码，替换为上述 budget/priority 算法 | 生效的自适应 |
| 各按钮 `flex:0 0 auto`（不收缩） | 保留（不收缩），用显隐控制占位 | 避免静默收缩导致重叠 |

## 5 · 视觉规格

| 项 | 值 |
|---|---|
| Header 玻璃条 | 不变（现状 `--glass-bg` + `--glass-blur` + 顶缘渐变线） |
| 隐藏优先级视觉 | 隐藏项**不渲染**（`display:none`），不做「半透明占位」；**无 `⋯` overflow 按钮/下拉**（用户裁定仅自动隐藏） |

## 6 · 边界与异常

| 场景 | 处理 |
|---|---|
| 固定集宽度 > 窗口 | 极端窄（<380px）：保留 6 固定入口，`session-name` 隐藏、非固定项已全部隐藏；**固定集本身必须放得下**——若固定集 > 窗口（理论极限，如 <240px），则 context-ring/胶囊允许 `overflow:hidden` 裁剪而非重叠，但**硬约束是零重叠**，宁可裁信息也不重叠 |
| 0 后台任务 / 0 subagent | R6/R7 指标 `hidden`（现状）；固定集相应只剩 4 个可见 → 更多空间给非固定项 |
| 会话名过长 | 先 `ellipsis`，真不够再隐藏（P5） |
| context-ring 无数据 | L2 `display:none`（现状）；此时固定集少 1 |
| 多窗口/popup | Header 只在主窗口；popup 无 header 元素（`headerModelInfo:null`，现状）；本规格不改 |
| 键盘 | 按当前可见序 Tab（固定集 + 可见非固定项）；**无 `⋯` overflow 需 focus trap**（用户裁定仅自动隐藏） |
| 初始渲染闪烁 | 预算计算在首帧前完成（同 sidebar-restore 思想），避免首帧溢出+随后跳变 |

## 7 · 无障碍

- **无 `⋯` overflow 按钮**（用户裁定仅自动隐藏）：无 `aria-haspopup`/overflow 角色；隐藏项直接 `display:none`。
- 隐藏不是 `visibility` 残留焦点：被隐藏项从 tab 序**移除**（`display:none` 天然移除）。**注意**：隐藏即不可达——被隐藏的非固定按钮随之失去键盘路径，其可替代路径（快捷键/命令面板/popup）须保证可达（P 序理由中已交代）。
- 键盘：Tab 依「当前可见序」；固定集 + 当前可见非固定项可达。
- 对比度：可见控件文字 ≥4.5:1。
- 固定集**永不被隐藏**，故用户始终能收起/展开、看 context 用量、进 memory、看后台任务 — 这是 #396 的可及性承诺，不会因窄窗口丢失核心路径。

## 8 · 可断言验收点

> **(auto)** = Playwright 可自动化；(截图) = 需截图。**口径纪律**：涉「优先级顺序」→ 用显式可见性序列断言；涉「重叠」→ 用可见元素 `getBoundingClientRect` 两两不相交；涉「固定集」→ 用固定集元素 `display` 恒非 none；涉「仅自动隐藏」→ 断言无 `⋯` overflow 元素。

- **A1 (auto)** 任意宽度下固定集元素（sidebar-toggle / header-model-info / memory-btn / bg-indicator(有数据时) / bgagent-indicator(有数据时) / canvas-toggle）`display` 恒不为 `none`（**(截图)** 补 3 个关键宽度截图）。
- **A2 (auto)** 任意宽度下：所有 `#header` 内**可见**元素两两 `getBoundingClientRect` 不相交（或同簇内 gap≥0 且不交）——**零图标重叠硬约束**。对宽度 420 / 768 / 1024 / 1440 各断言一次。
- **A3 (auto)** 隐藏优先级顺序正确：在固定视口宽度 W 下，若 `reminder-btn` 隐藏，则 `bypass-toggle` 与 `voice-toggle-btn` 也隐藏（P 序单调性：高优先级先隐，P1/P2 恒先于 P4 隐）；`search-btn` 可在 `reminder-btn/daemon-btn` 仍显示时隐藏（P3 vs P4/P5 顺序正确）。
- **A4 (auto)** 中心簇不重叠：`#session-name` + `#memory-btn`（中心簇）与左/右簇边界 `getBoundingClientRect` 不相交；中心簇 `max-width` 随容器动态 clamp（比对 computed `max-width` < (headerW - leftW - rightW)）。
- **A5 (auto)** 无 flex-wrap 重排：header 高度在任意宽度下恒定（`offsetHeight` 变化为 0），证明未换行。
- **A6 (auto)** `.header-compact` 死类已移除：`initHeaderResizeObserver` 中的 `header-compact` toggle 逻辑删除（源码 grep 断言无 `.header-compact` 写操作），且其 CSS 规则不复存在。
- **A7 (auto)** 仅自动隐藏裁定（锁「不建 overflow」）：DOM 中**不存在** `.header-overflow-btn` / `⋯` overflow 菜单元素；被隐藏的非固定按钮直接 `display:none`（源码 grep + 运行时断言，无新增 overflow 容器）。
- **A8 (auto)** 会话名降级：`#session-name` 在空间不足时先 `text-overflow:ellipsis`（computed 有 `ellipsis`），空间极小时隐藏（`display:none`）。
- **A9 (截图)** 在 375 / 768 / 1024 宽度各截一张 header 图，vision 核对：固定集齐全、无图标重叠、非固定项按优先级自动隐藏（**无 `⋯` overflow**）、视觉克制（无溢出断层）。
- **A10 (auto)** 数据相关：0 后台任务时 `bg-indicator` `display:none`；0 subagent 时 `bgagent-indicator` `display:none`；context-ring 无数据时 `header-model-info` `display:none`——此时固定集可见数相应变化，仍不重叠。

**给实现方/qa 的口径锁死**：A2 的「不相交」必须定义为**可见**元素（排除 `display:none`），因隐藏项不占布局；A3 的「顺序正确」建议用**固定视口 + 逐级缩宽**驱动，逐一记录可见性序列再比对 P 序，避免在同一宽度下断言依赖巧合。

## 9 · 参考链接

- PatternFly · Overflow menu design guidelines: https://www.patternfly.org/components/overflow-menu/design-guidelines/
- PatternFly · Toolbar design guidelines: https://www.patternfly.org/components/toolbar/design-guidelines/
- Fluent 2 · Toolbar usage: https://fluent2.microsoft.design/components/web/react/core/toolbar/usage
- KendoReact · Toolbar adaptive rendering: https://www.telerik.com/kendo-react-ui/components/buttons/toolbar/adaptive-rendering
- Nebflow visual-style 铁律: `~/.nebflow/skills/nebflow/visual-style/SKILL.md`
- Header 现状源码: `src/main/resources/web/index.html` · `css/chat.css`（`#header` 段）· `js/main.js`（`initHeaderResizeObserver`）
