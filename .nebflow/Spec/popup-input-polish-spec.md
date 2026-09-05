# 子 agent 弹窗输入区视觉对齐规格书（popup-input-polish）

> **状态**: superseded · 2026-08-22 用户裁定覆盖——子窗口输入栏整体移除，本规格的对齐目标不复存在
> **最后更新**: 2026-08-22
> **所属**: Nebflow 前端（bgAgentPopup.js / flowAgentPopup.js）
> **关联**: [msg-window-interaction-spec.md](msg-window-interaction-spec.md)（#323 功能缺口 G1-G3 本规格的由来）、nebflow/visual-style skill（铁律）、`css/input.css`（主窗口输入区事实基准）、`css/sapphire.css`（设计 token）

## 版本日志

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-08-19 | 初稿：主窗口 vs 弹窗输入区逐项差异清单（精确 CSS 值）+ 修改方案 + 二值验收断言 |
| — | 2026-08-22 | **作废**：用户裁定（2026-08-22 22:20）子窗口去输入栏改管理面板（commit 244acdb1）。本规格全部输入栏对齐段失效，管理面板设计见 [msg-window-interaction-spec.md](msg-window-interaction-spec.md) §9 |

---

## 0. 一句话目标

子 agent 弹窗（bgAgentPopup.js + flowAgentPopup.js）输入区与主窗口输入区实现**完全一致**的视觉体验——同样的玻璃质感、同样的按钮标准（淡绿发送 / 红色停止 / icon-btn 附件）、同样的圆角/字号/阴影/折射线、同样的暗色主题适配与交互手感。

## 1. 参考与依据

- **铁律（最高优先级）**：`~/.nebflow/skills/nebflow/visual-style/SKILL.md`
  - 铁律 2：可交互控件统一玻璃质感（发送按钮淡绿参数 rgba(7,193,96,0.42) + blur 8px + 立体边缘 + 淡边框 0.15）
  - 铁律 6：低调克制、复用既有设计语言，不造新轮子
- **主窗口事实基准**（本规格所有「应改成」值的唯一来源，禁止近似）：
  - `src/main/resources/web/css/input.css` — `#input-area`(:2) `#input-bar`(:12,::before:30) `#input-wrap`(:46) `#attachment-preview`(:56) `#input`(:153,::placeholder:169) `.icon-btn`(:173-202) `#send-btn`(:207-259) `#stop-btn`(:261-282) `#slash-dropdown`(:350-371) `#queue-bar`(:446-533) 暗色覆盖(:673-711)
  - `src/main/resources/web/css/sapphire.css` — 玻璃 token（`--glass-bg/--glass-blur/--glass-border` :6-51）、`.glass-control`(:112-122)、`.icon-btn` 同族 Pattern A(:139-174)
  - `index.html:211-233` — 主窗口输入区 DOM；`main.js` — primary view.dom 绑定
- **现状缺口出处**：`msg-window-interaction-spec.md` §5 G1-G3（「输入区无任何 CSS 样式」「popup 控件不匹配主窗口 ID 选择器」「slash/queue 类样式缺失」）——本规格将 G1-G3 细化为逐项可执行差异表。

## 2. 现状结论（为什么用户说「输入框很粗糙」）

#323（commit 881634fd）给两个弹窗加了输入区 DOM 与 `initInput` 事件绑定，**但没有加任何配套 CSS**。全仓检索确认：

- `.flow-agent-input-area`、`.fa-input-bar`、`.fa-input-wrap`、`.fa-icon-btn` 四个类 **无任何 CSS 定义**（grep 全仓仅命中 DOM 使用处）
- `#bgagent-input` / `#flow-input`（textarea）**无样式** → 浏览器默认白底方框、系统字体、默认边框、默认 focus 描边——与整体玻璃 UI 割裂的最大来源
- 发送/停止按钮只挂了中性 `.glass-control` 基类 → 灰色半透明小方块（无 36px 圆形、无绿色/红色语义、无 hover、图标 24px 未缩）
- `.attachment-preview`（类）、`.slash-dropdown`（类）、`#bgagent-queue-bar`/`#flow-queue-bar`（ID）均无样式 → 附件预览不换行无间距、slash 下拉错位、队列栏无面板
- 与 footer 状态条之间零间距（输入条直接顶住 border-top）

根因：**主窗口样式全部用 ID 选择器（`#input`/`#send-btn`…），弹窗元素 ID 不同（`#bgagent-*`/`#flow-*`）无法继承**；弹窗自建的类名又从未写过样式。

## 3. 差异表（核心：位置 / 主窗口规格（精确值） / 弹窗现状 / 应改成）

> 约定：所有「应改成」以 input.css 精确值为准，**禁止近似值**（如 16px≠20px 圆角、flex-end≠center、14px≠14.5px 字号——弹窗与主窗口并排时任何偏差都会被用户感知）。

### A. 输入条容器（玻璃面板）

| 项 | 主窗口 `#input-bar`（input.css:12-45） | 弹窗 `.fa-input-bar` 现状 | 应改成 |
|---|---|---|---|
| 背景 | `var(--glass-bg)`（亮 rgba(255,255,255,0.55)/暗 rgba(24,28,38,0.68)） | 无（裸 div） | 同左 |
| 毛玻璃 | `backdrop-filter: blur(var(--glass-blur)=24px) saturate(1.15)`（+`-webkit-`） | 无 | 同左 |
| 边框 | `1px solid var(--glass-border)` | 无 | 同左 |
| 圆角 | `border-radius: 20px` | 无（直角） | **20px**（禁止 16px） |
| 内边距 | `padding: 8px 12px` | 无 | 8px 12px |
| 布局 | `display: flex; align-items: center; gap: 8px; flex-shrink: 0` | 无（默认 block） | 同左（**center**，禁止 flex-end——主窗口按钮随 textarea 增高垂直居中） |
| 阴影 | `inset 0 1px 0 rgba(255,255,255,0.25), 0 2px 8px rgba(0,0,0,0.04), 0 8px 24px rgba(0,0,0,0.06)` | 无 | 同左（暗色换 :673 深阴影组） |
| 顶部折射线 | `::before`：top:0、left/right 10%、高 1px、`linear-gradient(90deg, transparent 10%, var(--sapphire-refraction) 50%, transparent 90%)` | 无 | 同左（玻璃面板标志性细节，弹窗 modal/header/footer 已有同款，缺此处） |

### B. textarea

| 项 | 主窗口 `#input`（input.css:153-171） | 弹窗 `#bgagent-input`/`#flow-input` 现状 | 应改成 |
|---|---|---|---|
| 边框/背景 | `border: none; background: transparent` | 浏览器默认（白底 + 2px inset 边框） | 同左 |
| 圆角 | `18px` | 默认 2px | 18px |
| 内边距 | `padding: 8px 14px` | 默认 2px | 8px 14px |
| 字号/行高 | `font-size: 14.5px; line-height: 1.4` | 系统默认（约 13.3px 等宽字体栈） | 14.5px / 1.4（禁止 14px） |
| 字体 | `font-family: inherit` | 默认 monospace 栈 | inherit |
| 高度行为 | `resize: none; overflow-y: auto; max-height: 200px` | 默认固定 2 行 + resize 手柄 | 同左（自动增高的 CSS 前提，JS 已由 initInput 共用绑定） |
| focus | `outline: none` | 默认蓝色 focus 描边 | 同左 |
| 文字色 | `color: var(--color-text)`；placeholder `var(--color-text-muted)` | 默认黑/默认灰 | 同左 |
| 暗色 | color `#e0e2e5`；placeholder `#555860`（:680-683） | 无（暗色下白底刺眼） | 同左 |

### C. 发送按钮（用户最敏感的「淡绿玻璃标准」）

| 项 | 主窗口 `#send-btn`（input.css:207-259） | 弹窗 `#bgagent-send-btn`/`#flow-send-btn` 现状 | 应改成 |
|---|---|---|---|
| 背景 | `rgba(7,193,96,0.42)` | 中性 `--glass-control-bg`（亮 rgba(255,255,255,0.45)——灰玻璃） | **rgba(7,193,96,0.42)**（铁律 2 标准） |
| 毛玻璃 | `blur(8px) saturate(1.3)`（+`-webkit-`） | `blur(10px) saturate(1.2)`（glass-control） | blur(8px) saturate(1.3) |
| 图标色 | `color: #fff` | 继承默认文字色（深色图标） | #fff |
| 边框 | `1px solid rgba(7,193,96,0.15)` | `var(--glass-control-border)` 白色系 | rgba(7,193,96,0.15) |
| 阴影（立体边缘） | `inset 0 1px 0 rgba(255,255,255,0.35), inset 0 -1px 0 rgba(0,0,0,0.08), 0 1px 4px rgba(7,193,96,0.2), 0 2px 8px rgba(0,0,0,0.06)` | 中性阴影 | 同左 4 层 |
| 形状/尺寸 | `border-radius: 50%; width/height: 36px; flex-shrink: 0; display:flex; align/justify:center` | 默认直角、内容撑高（约 26px） | 50% / 36×36 / 同左 |
| hover | 背景 0.55、边框 0.25、阴影加深（:229-237） | 无任何 hover 规则 | 同左 |
| active | `inset 0 1px 3px rgba(0,0,0,0.12), 0 1px 2px rgba(7,193,96,0.12)`（:238-242） | 无 | 同左 |
| disabled | 灰玻璃 `rgba(170,170,170,0.3)` + 淡边框（:243-248） | 无（`nodeSessionId` 为空时 JS 只设 opacity 0.4） | 同左 |
| disconnected | 灰色「不可用」态（:253-259，`refreshSendButtonState` 会往 `activeView.dom.sendBtn` 挂该类，弹窗按钮也会被挂上） | 类已挂但无 CSS 命中（`#send-btn.disconnected` 不匹配 `#bgagent-send-btn`） | 弹窗选择器补 `.disconnected` 灰态 |
| 图标 | `svg 18×18 stroke-width 2.5`（:249） | lucide 默认 24×24 stroke 2 | 18×18 / 2.5 |

### D. 停止按钮

| 项 | 主窗口 `#stop-btn`（input.css:261-282） | 弹窗 `#bgagent-stop-btn`/`#flow-stop-btn` 现状 | 应改成 |
|---|---|---|---|
| 背景 | `var(--color-error)` | 中性玻璃 | var(--color-error) |
| 图标色 | `#fff` | 深色 | #fff |
| 边框/阴影 | `1px solid rgba(255,255,255,0.2)` + `inset 0 1px 0 rgba(255,255,255,0.3), inset 0 -1px 0 rgba(0,0,0,0.12), 0 1px 4px rgba(0,0,0,0.12)` + `blur(8px) saturate(1.3)` | 中性 | 同左 |
| 形状/尺寸 | `border-radius: 50%; 36×36; display:none`（忙时 JS 转 flex） | 直角内容撑高；display 靠内联 `style="display:none"` | 同左（CSS 兜底 display:none + flex 居中，与 syncInputButtons 内联切换兼容） |
| hover | `#d32f2f`（:281） | 无 | #d32f2f |
| 图标 | `svg 18×18 stroke-width 2.5` | 24×24 | 18×18 / 2.5 |

### E. 附件按钮

| 项 | 主窗口 `.icon-btn`（input.css:173-202，类选择器——弹窗可直接复用） | 弹窗 `.fa-icon-btn`（挂 glass-control）现状 | 应改成 |
|---|---|---|---|
| 静息态 | `background: none; border: 1px solid transparent`（预留 hover 边缘不位移）；`color: var(--color-frame-text-muted)` | glass-control 半透明底 + 白边框 | **DOM 类改为 `icon-btn`**（复用主窗口类，零新增 CSS）；移除 glass-control |
| 形状/尺寸 | `width/height: 36px; border-radius: 50%; padding: 6px; flex center` | 无 | icon-btn 自带 |
| hover | 玻璃：bg-hover + `blur(10px) saturate(1.2)` + border + inset 边缘 + `color: var(--color-frame-text)`（:186-195） | 无 | icon-btn 自带 |
| active | `scale(0.96)`（:196） | 无 | icon-btn 自带 |
| 图标 | `svg 20×20 stroke-width 2`（:202） | 24×24 | icon-btn 自带 |
| 暗色 | `color: #7b7e88`（:684） | 无 | icon-btn 自带 |

### F. 附件预览容器

| 项 | 主窗口 `#attachment-preview`（input.css:56-61） | 弹窗 `.attachment-preview`（类）现状 | 应改成 |
|---|---|---|---|
| 布局 | `display: flex; gap: 6px; margin-bottom: 4px; flex-wrap: wrap` | 无容器样式（chips 的 `.att-thumb`/`.att-file`/`.att-taskref` 是类选择器已生效，但容器默认 block → 不换行、无间距） | 弹窗作用域下镜像同值（`.flow-agent-input-area .attachment-preview`） |

### G. slash 命令下拉

| 项 | 主窗口 `#slash-dropdown`（input.css:350-371） | 弹窗 `.slash-dropdown`（类）现状 | 应改成 |
|---|---|---|---|
| 定位 | `position: absolute; bottom: 100%; left/right: 12px`（浮在输入条上方） | 无 → 默认 static，选项内联排进输入条区域、错位 | 弹窗作用域镜像（容器 `.flow-agent-input-area { position: relative }` + dropdown absolute bottom:100% left/right:0） |
| 显隐 | `display: none`；`.on { display: block }`（input.js 只 toggle `.on`） | 无 → 元素永远 display:block（空时 0 高，有内容即裸显示） | 同左 |
| 面板 | 玻璃 + `border-radius: 20px` + 阴影 + `margin-bottom: 4px` + `max-height: 200px; overflow-y: auto` + z-index | 无 | 同左（`.slash-item` 等条目样式是类选择器已生效） |
| 暗色 | 阴影加深（:688-693） | 无 | 同左 |

### H. 队列栏（忙时消息排队）

| 项 | 主窗口 `#queue-bar`（input.css:446-533） | 弹窗 `#bgagent-queue-bar`/`#flow-queue-bar` 现状 | 应改成 |
|---|---|---|---|
| 面板 | 玻璃 + 20px 圆角 + 阴影 + `::before` 折射线；`.visible` 展开 max-height 280px | 无任何样式 → 排队内容裸露在输入条上方，无面板无过渡 | 弹窗作用域镜像（`.flow-agent-input-area #bgagent-queue-bar, .flow-agent-input-area #flow-queue-bar`） |
| 过渡 | `max-height/opacity/margin-bottom` 0→280px（.visible） | 无 | 同左（`.queue-item` 等条目类已生效） |

### I. 与 chat 滚动区的分隔

| 项 | 主窗口 | 弹窗现状 | 应改成 |
|---|---|---|---|
| 布局模式 | `#input-area` absolute bottom:0 浮于 chat 之上（input.css:2-10），chat 底部 padding 由 JS 按输入条高度动态留白（chat.css:11-17 注释） | `.flow-agent-chat`（flowAgentPopup.js:116-122）`padding: 12px 16px 8px`；输入区 in-flow 紧随 chat | 弹窗为 in-flow 布局，分隔靠显式间距：`.flow-agent-input-area { padding: 4px 16px 8px }` + chat 底部 padding 提到 ≥ 12px，合计视觉间隙 ≈ 主窗口浮条效果 |

### J. 与底部 footer 状态条的关系

| 项 | 主窗口 | 弹窗现状 | 应改成 |
|---|---|---|---|
| 布局 | 无 footer（输入条底 padding 10px 即窗口底） | modal 纵向：header / chat / `.flow-agent-input-area` / `.flow-agent-footer`（footer 有 border-top + 折射线，flowAgentPopup.js:125-143） | `.flow-agent-input-area` 底部 padding ≥ 10px（对齐主窗口 10px 呼吸感），避免 `.fa-input-bar` 直接顶住 footer border-top；footer 样式不动（已是标准玻璃分隔线） |

### K. 暗色 / 亮色主题

| 项 | 主窗口（input.css:673-711） | 弹窗现状 | 应改成 |
|---|---|---|---|
| 输入条阴影 | 亮 rgba(255,255,255,0.04) inset + 0.20/0.35 深阴影 | 无 | POPUP_CSS 内 `@media (prefers-color-scheme: dark)` 镜像 |
| textarea | color #e0e2e5 / placeholder #555860 | 无（白底黑字） | 同左 |
| 附件按钮 | icon-btn #7b7e88 | 无 | icon-btn 复用自带 |
| slash/queue | 深阴影组 | 无 | 同左 |

### L. 交互态汇总（用户对 UI 细节极敏感，逐项核对）

| 交互 | 主窗口 | 弹窗现状 | 应改成 |
|---|---|---|---|
| 发送 hover/active/disabled/disconnected | 全套（:229-259） | 无 | 镜像（见 C 行） |
| 停止 hover（#d32f2f） | :281 | 无 | 镜像（见 D 行） |
| 附件 hover/active | icon-btn（:186-196） | 无 | DOM 复用 icon-btn 自带 |
| textarea focus | outline:none（无焦点环，玻璃条整体即反馈） | 默认蓝描边 | 镜像（见 B 行） |
| 自动增高 | `input` 事件 → `min(scrollHeight, 200)px`（input.js:1087-1090，initInput 共用） | JS 已绑定但 CSS 缺 max-height/overflow/resize → 增高行为异常 | CSS 补齐（B 行）后自动对齐，无需改 JS |

## 4. 涉及文件与改动点

| 文件 | 改动 | 说明 |
|---|---|---|
| `src/main/resources/web/js/flowAgentPopup.js` | **POPUP_CSS 块（:26-198）内新增输入区样式段**（唯一 CSS 注入点，`style id="flow-agent-popup-css"`，bg/flow 共用类——一次改动双弹窗生效） | 新增：`.flow-agent-input-area`（含 position:relative + padding）、`.fa-input-bar`（含 ::before 折射线）、`.fa-input-wrap textarea`、send/stop 按钮（选择器 `#bgagent-send-btn, #flow-send-btn` 与 stop 同法）、`.attachment-preview`（弹窗作用域）、`.slash-dropdown`（弹窗作用域）、queue-bar（弹窗作用域）、全套 hover/active/disabled/disconnected/svg 尺寸 + `@media (prefers-color-scheme: dark)` 覆盖。数值逐项取自 input.css（§3 表），顶部注释标注「镜像 input.css:12/153/207/261/350/446，主窗口改样式须同步此处」防 drift |
| `src/main/resources/web/js/bgAgentPopup.js` | 模板（:116-130）无 CSS 改动；**仅 DOM 微调**：附件按钮类 `glass-control fa-icon-btn` → `icon-btn`（与 flow 同改） | 复用主窗口 `.icon-btn` 类（input.css 全局加载，弹窗同 document 生效） |
| `src/main/resources/web/js/flowAgentPopup.js` | 模板（:301-315）同上 DOM 微调 | 同上 |
| 主窗口 `input.css` / `chat.css` / `sapphire.css` / `index.html` / 后端 | **不改** | 回归零风险目标；主窗口样式是唯一事实基准，只读不写 |
| JS 逻辑（initInput/send/syncInputButtons） | **不改** | 事件、发送、send/stop 显隐、自动增高均已由 #323 共用绑定；本次纯视觉层 |

**方案取舍**：样式放 POPUP_CSS（而非把 input.css 的 ID 选择器重构为类选择器供复用）——与弹窗既有模式一致（`.flow-agent-modal`/`.flow-agent-footer` 已在 POPUP_CSS 中镜像主窗口玻璃值），主窗口零回归风险。代价是 send/stop 绿/红硬编码值与主窗口双份——用 CSS 变量（`--glass-*`）消除一半重复，硬编码的绿/红参数与主窗口一致地硬编码并在注释中标明来源行，防 drift。

**已知边界（本规格不解决，另行立项）**：
1. G5 `_inputBound` 复用丢事件（msg-window-interaction-spec §5，纯功能 bug，close 时重置一行修复）
2. popup 输入 `/ask` 等模式命令时 `updateInputIndicator` 会操作主窗口的 `#ask-indicator` 等元素（popup 无独立 indicator DOM）——功能边界，非视觉
3. popup 无语音按钮（dummy 隐藏，v1 明确不做）

## 5. 验收断言（二值化）

> 分两层：CSS 静态断言（grep，实现完成即跑）+ 视觉评审断言（隔离实例截图 + DOM getComputedStyle 数值比对 + 与 qa-frontend Playwright 结果交叉验证，防裸看图误报——User.md 教训）。

### 5.1 CSS 静态断言（grep `flowAgentPopup.js`，全二值）

| # | 断言（grep 命中） | 通过条件 |
|---|---|---|
| A1 | `\.fa-input-bar` 且同块含 `border-radius: 20px` | PASS/FAIL |
| A2 | `\.fa-input-bar` 同块含 `var(--glass-bg)` 与 `blur(var(--glass-blur))` | PASS/FAIL |
| A3 | `.flow-agent-input-area textarea` 同块含 `background: transparent`、`font-size: 14.5px`、`line-height: 1.4`、`max-height: 200px`、`border: none`（五项缺一 FAIL） | PASS/FAIL |
| A4 | send 规则含 `rgba(7, 193, 96, 0.42)`、`border-radius: 50%`、`36px` | PASS/FAIL |
| A5 | send/stop svg 规则含 `18px` 与 `stroke-width: 2.5` | PASS/FAIL |
| A6 | stop 规则含 `var(--color-error)` 与 hover `#d32f2f` | PASS/FAIL |
| A7 | 两个弹窗模板附件按钮均为 `class="icon-btn`（不再含 `glass-control fa-icon-btn`） | PASS/FAIL |
| A8 | `.attachment-preview`（弹窗作用域）含 `gap: 6px` 与 `flex-wrap` | PASS/FAIL |
| A9 | `.slash-dropdown`（弹窗作用域）含 `display: none` 与 `.on` 显示规则 | PASS/FAIL |
| A10 | queue-bar（弹窗作用域）含 `.visible` 展开规则（max-height 280px） | PASS/FAIL |
| A11 | `@media (prefers-color-scheme: dark)` 块内含 `#e0e2e5` 与 `#555860` | PASS/FAIL |
| A12 | `.flow-agent-input-area` 含 `padding-bottom` ≥ 8px（footer 分隔） | PASS/FAIL |
| A13 | 主窗口文件 diff 为空：`git diff --stat HEAD -- src/main/resources/web/css/ src/main/resources/web/index.html` 无输出 | PASS/FAIL |

### 5.2 视觉评审断言（隔离实例，与 qa-frontend DOM 断言交叉验证）

前置：按团队惯例起隔离实例（不碰 8080 活实例），触发一个 delegate 子 agent 打开弹窗。

| # | 断言 | 通过条件 |
|---|---|---|
| V1 | 弹窗输入条 vs 主窗口输入条并排截图：玻璃质感/20px 圆角/阴影/顶部折射线一致 | 视觉评审 PASS + getComputedStyle 圆角=20px、backdropFilter 含 blur |
| V2 | 发送按钮 getComputedStyle：backgroundColor = rgba(7,193,96,0.42)、borderRadius = 50%、36×36 | 数值比对 |
| V3 | textarea 无边框无背景、fontSize 14.5px、lineHeight 1.4 | 数值比对 |
| V4 | hover 发送按钮 → backgroundColor rgba(7,193,96,0.55)（Playwright hover + 取值） | 数值比对 |
| V5 | 暗色主题截图：textarea 无白底、文字 #e0e2e5、placeholder 灰 | 截图 + computed |
| V6 | 忙时 stop 红玻璃显示 / 闲时 send 绿玻璃显示（syncInputButtons） | 截图 |
| V7 | 输入条与 footer 间距 ≥ 8px，不重叠 | computed margin/padding |
| V8 | 主窗口输入区回归：主窗口截图与基线无视觉差异（因 CSS 零改动，仅确认） | 截图对比 |
| V9 | slash 下拉弹出位置在输入条上方（不内联错位）、queue-bar 有玻璃面板 | 截图 |

> 评审执行：视觉评审（本 agent）截图 + vision 对照 §3 规格逐项核对，引用 qa-frontend Playwright DOM 断言与截图路径作二进制取证佐证；PASS/FAIL 二值结论交 Manager。

## 6. 参考链接

- visual-style 铁律：`~/.nebflow/skills/nebflow/visual-style/SKILL.md`
- 主窗口输入区事实基准：`src/main/resources/web/css/input.css`（:2/:12/:153/:173/:207/:261/:350/:446/:673）
- 设计 token：`src/main/resources/web/css/sapphire.css`（:6-51 玻璃 token、:112 glass-control、:139-227 Pattern A/B）
- 弹窗实现：`src/main/resources/web/js/bgAgentPopup.js`（模板 :107-136、wire :153-164、syncInputButtons :394-417）、`src/main/resources/web/js/flowAgentPopup.js`（POPUP_CSS :26-198、模板 :292-321、syncInputButtons :657-662）
- 共用 JS 逻辑：`src/main/resources/web/js/input.js`（initInput :1072-1475、send :480、自动增高 :1087-1090）
- #323 现状缺口：`msg-window-interaction-spec.md` §5（G1-G3 样式缺失 / G4 按钮初值 / G5 复用丢绑定）
- 主窗口 DOM：`src/main/resources/web/index.html:211-233`
