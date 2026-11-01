---
type: design-spec
status: draft
title: 时间段滚轮选择器设计稿（Apple 滚轮同构 · 时间段文字填收编清单）
created: 2026-11-01
author: stand (delegate-nebflow-0c50686f)
mockup: "[[20261101_time-wheel-picker-mockup__delegate-nebflow-0c50686f.html]]"
related:
  - "[[20261029_1815_model-schedule-unavailable-spec__delegate-nebflow-b3020083.zh-CN]]"
  - "[[glossary]]"
tags: [design, interaction, time-picker, wheel, tokens]
---

# 时间段滚轮选择器设计稿（Apple 滚轮同构 · 时间段文字填收编清单）

> 只设计不实现。作者过目批准后才排实施（本稿 status: draft，批准后冻结）。样式全部引 HEAD 在树 token，不依赖在飞批未落地模块（官网推送/按钮玻璃/卡片家族/迁移链/RCA链/toggle回绿零触碰）；数据形状与 wire 契约零改，落点只换「字段皮」。

## §0 作者令（本稿依据）

作者令（2026-11-01 16:14，要点逐字）：「设置里模型的不可用时间调整，现在是文字填——改成点击弹出小交互窗，像 Apple 那样**滚轮调整时间段**；此设计落实到同类型，比如冻结时间段填写。」参考实拍 = iOS 闹钟 Add Alarm 滚轮（IMG_1667：深色 sheet、双列滚轮、中置圆角横带、数字上下渐隐、右上角确认钮）。

**滚轮选择器**——点击字段后弹出、以纵向滚动列表选值的控件（Apple HIG「Pickers」范式）；本文档给它 stand 全站形态与两落点收编设计。

## §1 一句话目标与收编范围

一句话：把全部「时间段/时间范围文字输入」收编为点击弹出的 Apple 式滚轮选择器（桌面锚定 popover——锚定触发字段的小浮层；手机底部 sheet——自底部滑入的面板，苹果同构），数据层零改。

**收编清单（统一收编落点）**：

| # | 落点 | 现状（考古出处） | 收编动作 |
|---|---|---|---|
| L1 | 设置 → 冻结时间（非工作时间）· 每段「起 / 至 / 止」 | 每段 2 个 `.time-input` 纯文本框（`web/js/sidebar.js:444-456`；段编辑器 `:409-437`；校验契约注释 `:729-733`） | 文本框 → 滚轮触发字段；段行/显式保存草稿/跨零点徽标语义原样 |
| L2 | 设置 → 模型服务商 · 编辑弹窗「不可用时段」 | 单文本框逗号分隔多段「`14:00-18:00, 23:00-07:00`」，正则解析 + toast 报错（`web/js/sidebar/providersUi.js:349`、解析 `:412-429`、携带 days `:396-405`） | 文本框 → 段列表（每段一颗触发字段，× 删除 / ＋ 添加）；正则与 toast 校验退役 |
| L3 | models.json 手编 per-model `unavailableWindows`（含 `days` 星期规则） | 手编面，前端保存按 id 原样携带（`providersUi.js:396-405`） | **不进弹窗编辑**：段列表以只读徽标「按星期 · 手编只读」保真展示；编辑面另行排批（O1） |
| L4 | 定时任务提醒 `datetime-local`（`web/js/scheduled-task.js:674`） | 原生控件，**单点时刻**非时段 | 范围外观察：同族单列滚轮可另批统一（O4），本稿不锁其设计 |

范围外：一切非「时间段」语义输入（单点时刻、数字、日期、cron）。

## §2 考古现状与数据契约（选择器的真源约束）

### 2.1 落点现状语义

- **L1 冻结时间**（黑名单：段内 agent 冻结不调 LLM）：段可多条、重叠取并集；`start == end` 拒绝（零长度无意义，且不定义为 24h 全冻结——误配即全停过于危险）；`start > end` **合法 = 跨午夜段**（23:00-08:00），行内已有「跨零点」徽标；编辑走本地草稿 + 显式 Save/Reset（`sidebar.js:367-407`）。
- **L2 不可用时段**（provider/模型计划性不可用，fallback 链剔除）：多段逗号分隔、正则 `^([01]\d|2[0-3]):([0-5]\d)-([01]\d|2[0-3]):([0-5]\d)$` 逐段校验、toast 报错；空 = null 无限制；文本框形状装不下 `days`（保存丢 days = 显式降级，上游规格已注记）。

### 2.2 后端契约（两端同律，出处）

| 契约 | 形状 | 段规则 | 出处 |
|---|---|---|---|
| 冻结调度 | `{enabled, segments:[{start,end}]}`，"HH:mm" 24h | [start,end)；start>end=跨午夜；start==end 非法（validate 拒绝、load fail-safe 视为关闭）；段重叠允许（并集）；enabled+空段非法 | `src/main/scala/nebflow/core/schedule/FreezeSchedule.scala:19-114` |
| 不可用窗口 | `{from,to, days?}`，"HH:mm" 24h | [from,to)；from>to=跨午夜；from==to 非法（fail-safe 永不命中）；days=ISO 星期（1-7），缺席=每天，跨零点清晨腿归昨天的星期 | `src/main/scala/nebflow/shared/config.scala:9-35` |

推论：**选择器只需产出 HH:mm「起-止」二元组**；相等拒绝 / 跨零点合法 / 并集语义全部后端已有，UI 校验与后端 validate 同律（§5），UI 不发明新语义。契约上游设计见 [[20261029_1815_model-schedule-unavailable-spec__delegate-nebflow-b3020083.zh-CN]]。

## §3 形态与关键裁定（逐条给理由）

- **R1 · 24h 制，无 AM/PM 列**。理由：① 数据契约即 24h "HH:mm"（§2.2），12h 列需双向换算并引入「12 AM/12 PM」歧义面；② 主语言 zh-CN、开发工具语境 24h 是系统习惯；③ 少一列 = 四列 + 「至」，手机 sheet 一排放得下。参考实拍的 AM/PM 列不收（那是美国 locale 的显示层）；若未来有 12h 需求 = 显示层本地化另批（O5），数据层恒 24h。
- **R2 · 跨零点 = 自然表达，不做「跨天模式」**。起止两轮独立拨，`起 > 止` 即跨零点（两后端皆此语义）；sapphire 提示条「至次日 HH:mm 结束」实时跟随（同现役 L1 行内跨零点徽标的语义，升级为面板内全局提示）。不加开关、不加次日标记列——UI 不发明后端没有的形状。
- **R3 · 分钟步进 = 5 分钟档**。理由：两落点（冻结窗、可用性窗）无 1 分钟精度需求；60 档轮过长难拨，12 档/圈手感接近实拍；精确分钟走「精确输入」直输行兜底。步进 = 实现常量（1/5/10/15 可调，列重建即换），实施批可调（O2）。
- **R4 · 桌面 = 锚定 popover，手机 = 底部 sheet，≤768px 自动切**。判据与现役 mobile-layer 同源（`max-width: 768px`）。弹窗遵守全站视觉铁律 1：**禁 overlay 遮罩**（无背景暗化/模糊），面板本身毛玻璃；sheet 皮同样不加暗化层，点面板外 = 取消、grabber——sheet 顶部横条把手——下拉关闭。canvas 面板的 bottom sheet 族退役是 dock 批对**该面板**的处置，非全站禁令；本 sheet 形态由作者令「苹果同构」直接授权。
- **R5 · 确认钮 = 微信绿实色族（#07C160 白字）**（视觉铁律 2 主行动语义）；取消 = 中性 ghost 文字钮。
- **R6 · 本稿样张**（[[glossary#^mockup-sample]]）与设计稿成对放 `docs/design/`，浏览器直开即拨（§11）。

## §4 布局与信息架构

桌面 popover（300 × ~400，锚定触发字段下方 6px，视口钳位 8px，空间不足翻转到字段上方）：

```
┌────────────────────────────────┐
│ 取消        选择时间段       确定 │ ← 头行：取消 ghost / 标题 15px·600 / 确定绿
│  ┌──┐  ┌──┐                    │
│  │07│  │50│   10    50         │ ← 滚轮区：起·时(24档) 起·分(12档)
│  │08│  │55│   11    55         │   「至」分隔 · 止·时 止·分
│  │09│  │00│   12    00  ←━━━  │ ← 中置选中带（带高 40 = 行高，圆角 10）
│  │10│  │05│   13    05         │   可见 5 行；上下渐隐 mask
│  │11│  │10│   14    10         │
│  └──┘  └──┘                    │
│ (全天)(深夜 23–08)(白天 09–18)  │ ← 快捷档 chips（一键双轮同拨）
│ [跨零点提示条 / 起止相等错误行] │ ← 状态行（互斥，实时）
│ 精确输入 HH:mm                  │ ← 直输回退 toggle → 双 HH:mm 输入行
└────────────────────────────────┘
```

- **触发字段（trigger，收编后的字段皮）**：时钟 glyph + tabular-nums 值（如 `09:00`），hover/焦点态复用现役 `.time-input` 的 sapphire 焦点语言（`sidebar.css:2036-2038`）；L1 保持「起 至 止」行布局，L2 为段列表 chip 行。字段呈现 `aria-haspopup="dialog"` + `aria-expanded`。
- **手机 sheet**：同滚轮核心 + 顶部 grabber + 同款头行；宽 = 全宽，顶圆角 20，底部 `safe-area-inset` 内边距；chips 一行铺开。
- **直输回退（键盘通道兜底）**：「精确输入 HH:mm」展开双输入行（`inputmode="numeric"`），Enter/失焦应用、非法红描边不应用。三通道矩阵：纯鼠标=滚轮/拖拽；纯键盘=Tab+↑↓/数字直输/精确输入；触摸=拨轮。任何一类用户不被锁死。

## §5 交互状态机表

面板态：`closed → open(皮 popover|sheet) → [逐列独立: idle|drag|coast|snap] → commit|revert → closed`。

| 触发 | 现态 | 次态 | 结果 |
|---|---|---|---|
| 点击触发字段 | closed | open | 开皮；四轮置字段现值；h1 轮获焦（触屏延迟至入场动画毕） |
| 指针按下列（触摸/鼠标） | idle/snap | drag | setPointerCapture；1:1 跟手（1px = 1/40 档） |
| 释放·速度<0.004 档/ms | drag | snap | 吸附——释放后对齐最近档（ease-out，≤180ms） |
| 释放·速度≥0.004 档/ms | drag | coast | 惯性——按初速续滚并衰减（§6），止于 snap |
| 滚轮/触控板（桌面） | open | snap | 像素累积阈值 30 → 步进 1 档（110ms）；滚轮打断惯性 → 先吸附 |
| ↑/↓（列聚焦） | open | snap | ±1 档（140ms）；Shift+↑↓ 或 PageUp/Down = 大步（时列 ±6h / 分列 ±1h）；Home/End = 00 / 末档 |
| 数字键（列聚焦） | open | snap | 两位内缓冲，900ms 或 Enter 应用；最短路径旋转至该值；分钟就近 5 档；>23 时钳 23 |
| 「精确输入」toggle | open | — | 展开直输行；Enter/失焦应用；非法（如 25:99）红描边不应用 |
| 快捷档 chip | open | snap | 全天 00:00–23:59 · 深夜 23–08 · 白天 09–18 · 午后 14–18（260ms 同拨） |
| 任一轮值变化 | open | — | 实时校验：起==止 → 禁确定 + 错误行；起>止 → 跨零点提示条（互斥显示） |
| 确定 | open·合法 | closed | 写回字段值（HH:mm）→ 走落点既有保存链路（L1 显式 Save 草稿 / L2 onSave）；触发字段还焦 |
| 取消 / ESC / 点面板外 / grabber 下拉>120px 或甩速>0.5 | open | closed | **还原**（不写回）；触发字段还焦 |

## §6 滚轮手感规格（动效参数全表）

| 维度 | 规格 |
|---|---|
| 跟手 | 拖拽 1:1；采样窗 100ms 取初速；初速钳 ±2.5 档/ms（防甩飞） |
| 惯性 | 指数衰减 `v *= exp(−Δt/325ms)`；\|v\| < 0.0015 档/ms 转吸附 |
| 吸附 | ease-out-cubic；时长 = clamp(90, 280ms, 按剩余距离)；步进场景固定短档：滚轮 110 / 键盘 140 / chip 260ms |
| 环形 | 值 ×3 份循环回收，静止归一中份邻域（防长会话漂移）；无端点、无橡皮筋（iOS 闹钟同构：时刻环形无界） |
| 选中带 | **带恒定不动、数字动**（苹果范式）；带 = 中置 40px 圆角横带 |
| 行衰减 | 透明度梯度 1 / .55 / .30 / .15 / .06（距选中 0/1/2/3/≥4 档）+ 微缩放 1→0.94；上下渐隐 mask：`linear-gradient(180deg, transparent 4%, #000 30%, #000 70%, transparent 96%)` |
| 列间关系 | 起止四轮**全独立、无级联钳制**（跨零点合法 ⇒ 无 min>max 约束可钳）；联动只发生在快捷档 chip（一键双轮）与校验提示 |
| 多点触控 | 每列独立 pointerId capture，双手指可各拨一列 |
| 皮动效 | popover 入场 160ms `cubic-bezier(0.2,0.8,0.2,1)` scale .96→1（transform-origin 锚向触发字段角）、退场 120ms；sheet 入场 280ms `cubic-bezier(0.32,0.72,0,1)` translateY 100%→0（iOS sheet 曲线）、退场 200ms；grabber 拖拽 1:1 跟手 |
| reduced-motion | 皮动画/惯性/吸附动画全退化为直跳（既有纪律） |

## §7 视觉规格（全 token 引用，双主题值逐字实测）

样式走现役 token——CSS 自定义属性（`--xxx`）形式的样式单源；下表全部为 HEAD 在树实测值（`web/css/sapphire.css:37-56,99-159` + `web/css/base.css:8,47-48`），不依赖在飞批：

| 件 | token | 亮档 | 暗档 |
|---|---|---|---|
| 面板填充 | `--glass-bg` | rgba(255,255,255,0.55) | rgba(24,28,38,0.68) |
| 面板玻璃 | `blur(var(--glass-blur)) saturate(1.15)`（`-webkit-` 双写） | 24px | 24px |
| 面板描边 | `--glass-border` | rgba(255,255,255,0.35) | rgba(255,255,255,0.06) |
| 选中带填充 | `--glass-etched-bg` | rgba(0,0,0,0.025) | rgba(255,255,255,0.04) |
| 触发字段/输入/chip 填充 | `--glass-control-bg`（hover `--glass-control-bg-hover`） | 0.60 / 0.74 | 0.05 / 0.10 |
| 触发字段/输入/chip 描边 | `--glass-control-border` | rgba(255,255,255,0.90) | rgba(255,255,255,0.08) |
| 数字/正文/选中行 | `--color-text` | #1b1e26 | #e8eaed |
| 次级文案/取消钮/邻行基色 | `--color-text-muted` | #8b8e96 | #7b7e88 |
| 聚焦环/跨零点提示条 | `rgb(var(--sapphire) / α)`（`--sapphire: 91 127 191`） | 环 0.35·字段态 0.60+0.12 / 底 0.10 边 0.18 | 同 α |
| 确定钮 | `--color-primary`（微信绿实色族） | #07c160 | 同色（暗档文字态提亮 #34c77b） |
| 错误行/红描边 | `--danger` 语义族（样张（[[glossary#^mockup-sample]]）用 rgb(229 72 77 / 底 .08 边 .22-.55)） | — | — |
| 面板投影 | `--shadow-pop`（样张双档栈：0 12px 40px α.14/.5 + 0 2px 8px α.08/.35） | — | — |

- 字重（铁律 3 方案 B）：标题 600 / 数字与按钮 500 / 正文 400；数字 `font-variant-numeric: tabular-nums`，字号选中行 20px。
- 圆角：popover 16 / sheet 顶 20 / 选中带 10 / 触发字段与输入 8 / chips 999（胶囊）。
- z-index：popover **10001**（provider 编辑弹窗 10000 之上；`modal.css` 实测档位 10000-11000）；sheet **11000**。
- 降级：`html.no-backdrop`（引擎不支持 backdrop-filter——旧 WebView 内核挂类的既有纪律）→ 面板实色填充，视觉不破。
- 自洽声明：本稿不引用任何在飞批未落地 token/模块；若在飞批（按钮玻璃/卡片家族）落地后与本稿冲突，以落地后作者裁定为准并回改本稿对应行。

## §8 边界与异常

- **宿主滚动/resize**：宿主滚动容器滚动（含 provider 弹窗内滚动）按「点外」处理=还原关闭；resize 收起（实现批可议重锚，倾向收起——低频场景不配动画复杂度）。
- **值域边界**：环形无端点；时 0–23、分 0–55（5 步进）；直输「25」钳 23、「07」分钟就近 05；非法串（`25:99` / 空）红描边不应用。
- **空值/清空**：选择器本体无「清空」概念——清空是列表层动作：L1 段删走既有 ×（enabled+空段后端拒绝的语义不变）；L2 段删光 = 空列表 = null 无限制（现役语义）。快捷档「全天」= 00:00–23:59（[start,end) 语义覆盖 1439/1440 分钟，留 1 分钟缝；真 24h 全冻结在后端是无翻转点特殊态且属危险面——「全天」chip 对 L1 的适用性列开放项 O3）。
- **多点触控**：每列独立 capture（§6）；双列同拨无冲突。
- **手机直输行**：聚焦拉起 iOS 键盘的视口顶起需 visualViewport 避让——实现注意（O6），样张未模拟。
- **provider 弹窗小屏打开**：≤768px 时 provider 弹窗内的触发字段弹 **sheet**（形态跟随视口，不跟随宿主容器类型）。

## §9 无障碍

- 列 = `role="spinbutton"`（`aria-valuemin/max/now` + `aria-valuetext`「09 时」），`aria-label`「开始·时 / 开始·分 / 结束·时 / 结束·分」；面板 = `role="dialog"` + `aria-label="选择时间段"`；触发字段 `aria-haspopup="dialog"` + `aria-expanded`。
- 全键盘可达：Tab 进面板并圈陷；列 ↑↓ 直拨、Shift 大步、Home/End 归零到底、数字直输；「精确输入」行为纯键盘兜底通道；ESC=还原关闭。
- 对比度：正文/选中行 `--color-text` 双主题对面板合成底 ≥4.5:1；邻行淡化（≤0.55）属装饰性预览，不承载必读信息（跨零点/相等等必读信息在状态行，全对比度）。
- 触达：列命中区 54×200px，远超 44pt 最低触达；chips 高 ~28px 属次级快捷件（可拨轮替代，无锁死）。

## §10 可断言验收点（供 QA 转自动化断言，全部二值）

1. 点击触发字段后：面板在 DOM 且 `role="dialog"`；**页面不存在 overlay/遮罩元素**（`.tw-overlay`、`.modal-backdrop` 之类均无）——铁律 1 断言。
2. 静止态：每个选中行几何中心与中置带几何中心距离 < 2px；可见行数 = 5、行高 40px。
3. 任意通道拨动后：分钟列 `aria-valuenow % 5 == 0`；`aria-valuetext` 形如「XX 时」/「XX 分」。
4. 起止相等：确定钮 `disabled=true` 且错误行含 `show` 类；起>止：跨零点提示行含 `show` 且文案含「次日」。两态互斥（同刻至多一行 show）。
5. ESC / 取消 / 点面板外 / grabber 甩关：字段值不变（还原语义），触发字段重新持有焦点。
6. 确定：字段值更新为 HH:mm 且落点保存载荷形状 = 现役契约（L1 `{start,end}` / L2 `{from,to}`）；含 days 的手编规则在保存后逐字节原样携带（数组未动）。
7. 形态分叉：视口 ≤768px → sheet 皮（grabber 在 DOM、z-index 11000）；>768px → popover（z-index 10001，锚定触发字段间距 6px、越界钳位 8px）。
8. 双主题断言：选中行文字计算色 = `--color-text` 计算值；面板背景计算色 = `--glass-bg` 计算值（token 断言，防裸 hex 回归）。
9. `prefers-reduced-motion: reduce` 下：面板入场 `getAnimations()` 为空（动效全退化）。
10. 打开→拨动→确定全程 **console 零错误**。

## §11 证据与样张

- **自包含样张**（[[glossary#^mockup-sample]]）：`docs/design/20261101_time-wheel-picker-mockup__delegate-nebflow-0c50686f.html`（[[20261101_time-wheel-picker-mockup__delegate-nebflow-0c50686f.html]]）——浏览器直开即拨：拖拽（触摸/鼠标）、桌面滚轮/触控板、键盘 ↑↓/Shift/Page/Home/End/数字直输、「精确输入」行、快捷档、双主题切换、形态切换；URL 定态参数（`?theme=&form=&open=&state=&hint=`）供截图与演示复现，参数表见样张头注。
- **交互自证**：playwright-core(chromium) **12/12 PASS**，覆盖 §10 ①③④⑤⑦⑩（滚轮步进、拖拽动量+吸附、键盘、数字直输、精确输入→跨零点、相等禁用、ESC 还原、双皮、零报错）；截图脚本不入仓（设计批零产品代码纪律），断言口径 = §10。
- **双主题关键态截图 8 张**：`docs/evidence/20261101_time-wheel-picker__delegate-nebflow-0c50686f/`（桌面亮/暗 popover、手机亮/暗 sheet、跨零点暗、相等错误亮、直输回退暗、provider 落点暗）。

关键帧（桌面暗 popover · 手机暗 sheet · 相等错误亮 · 跨零点暗）：

![[tw-desktop-popover-dark.png]]

![[tw-mobile-sheet-dark.png]]

![[tw-state-equal-error-light.png]]

![[tw-state-cross-midnight-dark.png]]

## §12 开放项（作者过目时请一并裁定）

- **O1 · days（按星期）编辑面**：本稿对含 `days` 手编规则只读保真（徽标「按星期 · 手编只读」）；星期多选编辑交互另行排批——形态（chips 周一到周日？）需作者裁定。
- **O2 · 分钟步进档位**：5 分钟为默认草案；出现 1 分钟需求时改实现常量 + 分列重建，本稿结构无冲突。
- **O3 · 「全天」chip 与 L1 的相容性**：冻结黑名单下「全天」≈全停（危险面 + 后端无翻转点特殊态）；倾向 L1 面板隐藏该 chip（chips 白名单按落点配置），实施批与作者确认。
- **O4 · L4（定时任务 datetime-local）**：单点时刻，非本批范围；同族单列滚轮（时/分/日）可另批收编，届时复用本稿 Wheel 核心。
- **O5 · 12h 制（AM/PM）**：数据层恒 24h；显示层本地化（en locale 偏好）另批，届时加 AM/PM 第五列的布局预留位已由「四列+至」结构说明。
- **O6 · 手机直输行键盘避让**：iOS visualViewport 顶起的处理=实现注意，样张未模拟。
- **O7 · 桌面窄窗形态**：现设计跟随宽度（≤768 即 sheet）；若出现「桌面窄窗想留 popover」诉求再议（Apple 无此形态分叉先例）。

## §13 参考与出处

- 参考实拍：iOS 闹钟 Add Alarm（IMG_1667，作者令附）——中置圆角横带、上下渐隐、双列滚轮、右上角确认、深色 sheet。
- Apple HIG · Pickers：`https://developer.apple.com/design/human-interface-guidelines/pickers`（滚轮选择器的呈现与确认范式）。
- L1 出处：`web/js/sidebar.js:366-456`（冻结段编辑器与显式保存草稿）、`:729-733`（校验契约注释）；`web/css/sidebar.css:2021-2038`（`.time-input` 现役样式/焦点语言）。
- L2 出处：`web/js/sidebar/providersUi.js:328-350,396-435`（文本框形态、days 携带、解析校验）。
- 后端契约：`src/main/scala/nebflow/core/schedule/FreezeSchedule.scala:19-114`；`src/main/scala/nebflow/shared/config.scala:9-35`；上游规格 [[20261029_1815_model-schedule-unavailable-spec__delegate-nebflow-b3020083.zh-CN]]。
- token 实测：`web/css/sapphire.css:37-56,99-159`、`web/css/base.css:8,47-48`、`web/css/modal.css`（z-index 档 10000-11000）。
- 视觉铁律：stand-dev 前端章 visual-style（弹窗禁 overlay/面板必须毛玻璃/字重方案 B/主行动绿实色族/no-backdrop 降级纪律）。
