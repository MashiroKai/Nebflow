# 设计规格书 · Nebflow 客户端「Sub-Agents 面板」视觉设计优化

- 日期：2026-08-25 · 作者：design-engineer · 状态：**frozen（用户 08-25 拍板冻结）**
- 任务来源：用户 08-25 反馈「主窗口侧边栏 Sub-Agents 面板（展示 Delegate/SubTask/team agent 后台任务）设计不清晰」→ 设计先行产出视觉规格书，经确认后由 Frontend 实施，qa-frontend 转 Playwright 断言。
- 适用代码：`src/main/resources/web/js/main.js`（`renderBgAgentDropdown` / `updateBgAgentIndicator`）· `css/chat.css`（`#bgagent-indicator` / `#bgagent-dropdown` · `.bg-task-*`）· `index.html`（`#bgagent-dropdown`）。后端字段语义依 `src/main/scala/nebflow/agent/protocol.scala`（`AgentRecord`/`AgentKind`/`AgentStatus`）与 `core/tools/AgentControlTool.scala`。
- 铁律依据：`~/.nebflow/skills/nebflow/visual-style/SKILL.md`（最高优先级）；软参考 `~/.nebflow/skills/design-system/SKILL.md`（本规格书必读后按同功能类型复用）。
- **用户裁定（2026-08-25 拍板，冻结依据）**：① 信息取舍**认可**——barrier 默认隐藏、retries 仅 >0 显示（§2.2/§8 已按此锁定）；② 布局**默认不分组**——kind 用徽标区分，行数大增才分组（§2.3 已按此撰写）；③ stuck 交互**popup 内操作**——不采用行内浮现取消/重启按钮，保持「点行进 popup 再操作」（§3/§7 相应修订）；④ Header 分级方案「仅自动隐藏」见规格书②（本规格书不列，避免混叠）。
- **数据依赖提示（诚实声明）**：以下若干展示项（`barrier`、连续 `idle` 时长）后端 `AgentRecord` 已有，但**未通过 WS `activeAgents` 快照推给前端**。本规格书锁定「目标态」与可断言点；对数据未就绪的项标注「**(data)**」——需后端补推字段或复用现有 `taskStuck` 事件，实施/评审以实际可得字段为准，不得虚构值。

---

## 0 · 一句话目标

把当前「裸文字 running/done 列表」升级为**克制的专业进程监控面板**——用「状态色阶 + 图标」替代裸文字、用「stuck 告警 + 时长 + 重试×N」给出行动动机、用「kind 徽标 + 任务 + 当前工具」交代业务上下文；对**噪音**（barrier/裸 sessionId）默认隐藏；全程低调克制吻合视觉铁律。

## 1 · 参考与依据

| 来源 | 提炼的可执行规则 | 链接 |
|---|---|---|
| AppMaster · Background tasks progress UI | ① 用户要「三件事」：清晰状态（queued/running/done）+ 时间感（哪怕粗略估计）+ 明确下一步（等待/继续/取消/稍后再看）；② 状态保持少且可预测：Queued/Running/Done/Failed；③ 无确定分母时**不用百分进度条**（易误导），用 step 或 indeterminate + 保持文案新鲜；④ 更新停止时的「Last updated 2 分钟前」要成**一等状态**并给出刷新——对应 stuck 告警；⑤ 长任务给一个可回看的 Activity panel；⑥ 部分成功「完成但有瑕疵」比「失败」更诚实 | https://appmaster.io/blog/background-tasks-progress-ui |
| IBM Carbon · Status indicator pattern | ① 状态色语义：red=danger/error、orange=serious warning、yellow=regular warning、green=normal/success、blue(中性)=passive/通知/流程进行中；② **颜色必须与形状/图标/标签配对，不能只靠颜色**（可及性）；③ severity 用「形状差异」强化 | https://carbondesignsystem.com/patterns/status-indicator-pattern/ （页面较大，可参考 https://v10.carbondesignsystem.com/patterns/status-indicator-pattern/ ） |
| macOS 活动监视器 / 进程列表 | 进程监控列表 = 名称 + CPU% + 运行时长；状态以「颜色点 + 标签」呈现；列表可排序、可选中看详情；默认只展示关键列，详情点击展开 | https://support.apple.com/guide/activity-monitor/welcome/mac |
| Nebflow manage-panel 先例（managePanel.js） | 已确立：**克制红 stuck 标签**（`t('manage.stuck', {secs})`，非满屏红）；**retries 只在 >0 时显示 ×N chip**；**uptime 仅在状态非 done 时显示**；**kind 权限矩阵**（Delegate/SubTask/Ephemeral 可操作，Team/Flow/Root read-only）——本规格书复用同一套克制语义，不另造 | `src/main/resources/web/js/managePanel.js`（`syncManageControls`/`managePolicy`） |
| Nebflow 现状（bgagent-dropdown） | 已有点：header sapphire 胶囊 `#bgagent-indicator`（点击开下拉）、下拉行含 status/name/task/tool、行可点开 agent popup。**状态仅「running/done」裸文字、无色阶/图标、无 stuck/时长/kind/重试** | `src/main/resources/web/js/main.js`（`renderBgAgentDropdown`）· `css/chat.css` |
| Nebflow visual-style 铁律 | 面板毛玻璃（`--glass-bg`+`blur(24px)`+`--glass-border`）；可交互控件玻璃质感；中度字重（body 400/标题 600/按钮 500）；低调克制专业感，不造新轮子 | `~/.nebflow/skills/nebflow/visual-style/SKILL.md` |
| WAI-ARIA APG · Listbox | 监控列表用 `role=list`/`listitem`；可键盘 Tab 到行、Enter 激活、Esc 关闭；状态区 `aria-live="polite"` | https://www.w3.org/WAI/ARIA/apg/ |

**冲突取舍**：范式间冲突以 visual-style 铁律与用户 taste（克制专业感）为准；Carbon 的「颜色+形状/标签配对」与 Nebflow 现有「绿/琥珀/红点 + 脉冲动画」一致，直接复用不另造色阶。

## 2 · 布局与信息架构

### 2.1 形态决策（核心取舍）
保留**顶部 header 下拉**形态（`#bgagent-indicator` 点击 → `#bgagent-dropdown`），不改为独立侧边栏页、不做居中大弹窗。理由：① Sub-Agents 是**同位反馈**（用户看主对话时瞥一眼）；② 现有视觉铁律与 `#bgagent-dropdown` 毛玻璃面板已合规；③ 与 Apple 活动监视器「同屏概览」同构。**本规格书做「视觉清晰度」优化，不推翻数据管线与打开方式。**

### 2.2 信息层级（信息取舍判定——哪些展示 / 哪些隐藏）

| AgentControl 字段 | 判定 | 理由 |
|---|---|---|
| **kind**（Delegate/SubTask/Team/Ephemeral） | ✅ **展示**（徽标） | 区分任务类型（短委托/子任务批次/团队协作/一次性），一眼知道「这是谁的活」 |
| **status**（Processing/Idle/Frozen/Error/stuck 派生） | ✅ **展示**（状态点+标签） | 用户三件事之首：现在什么状态 |
| **stuck?** | ✅ **突出告警**（红 + 怠速秒数） |「卡住」是最需要行动的信号（appmaster「Last updated 2 分钟前」一等状态） |
| **uptime**（startedAt） | ✅ **展示**（相对时长） | 时间感（appmaster 三件事之二） |
| **retries**（retryCount） | ✅ **条件展示**（仅 >0 时 ×N chip） | 复用 manage-panel 先例；正常任务 0 次是噪音 |
| **task 描述** | ✅ **展示**（单行省略） | 交代「在做什么」 |
| **当前 tool** | ✅ **展示**（次级说明行） | 交代「做到哪一步」（indeterminate 进度的诚实替代，见 §2.5） |
| **barrier**（outstanding/held） | ❌ **默认隐藏**（**(data)**） | 是诊断性语义（issue #31 phantom slot），非用户行为驱动；仅 AgentControl `status`/详情工具给管理员排查。若后端补推且 `outstanding>0 && idle`，可在详情行加一条内敛「⚠ batch 挂起」提示，**不进列表默认面** |
| **sessionId / agentId（裸 ID）** | ❌ **隐藏** | 纯技术噪音，行内不放；点击行打开 agent popup 详情时可见 |
| **model 徽标** | ❌ **不进列表** | 列表行放 model 会挤爆；保留在 agent popup 详情（现状已有 `#bgagent-model`） |

### 2.3 布局（列表行结构）

```
┌──────────────────────────────────────────────┐
│ Sub-agents ························ [×]      │  面板头（现状 `.bg-dropdown-header`，补计数）
├──────────────────────────────────────────────┤
│ ● ● ▍ Delegate · 编译测试 · 3m20s ×1         │  行：状态点+标签 · kind徽标 · 名称+任务 · 时长 · 重试chip
│   ↳ 正在执行: run-tests（工具字幕）            │  次级行：当前工具（省略号）
│ 〇 ▍ SubTask · 批量校验 12 项 · 12s           │  行2：空闲（琥珀）
│ ✖ ▍ Delegate · 等待远端 · **stuck · 14m**    │  行3：stuck（红+闪烁）+「14m 怠速」
│ ✓ ▍ Team · 汇总文档 · done                    │  行4：完成（中性/绿实心）
└──────────────────────────────────────────────┘   底部：行数过多 → 面板内滚动（sticky 头）
```

- **行结构**：`[状态点][kind 徽标][名称 · 任务(单行省略)][uptime][retries×N]` + 次级行 `当前工具`（若在跑）。点击整行 → 打开的仍是现有 agent popup（`openBgAgentPopup`/`openFlowStepPopup`），**不改导航**。
- **kind 徽标**：小号文字 chip（Delegate/SubTask/Team/Ephemeral），用现有 `--color-frame-text-muted` 底色 + 次要文字色，低调不喧宾；不引入新彩色语义（避免与状态色混淆）。
- **分组**：默认**不分 kind 分组**（单列表，按稳定序 = kind 内的进入时间）。理由：Sub-Agents 同行数有限（通常 ≤ 6），分组头徒增垂直噪音；kind 已用徽标区分。若未来行数大量增加（如 Team 批量 20+），再做 kind 分组二级策略——本规格书不预判。
- **token 纪律**：全复用既有 token（`--glass-bg`/`--glass-border`/`--glass-blur`、`--color-success`/`--color-error`/`--color-frame-text-muted`、`rgb(var(--sapphire)/a)`），**零新增颜色 token**。仅在需要时新增**类名（非 token）**：`.bg-task-kind`（kind 徽标）、`.bg-task-stuck`（stuck 高亮）、`.bg-task-uptime`（时长）、`.bg-row-status`（状态点复用现有 `.bg-task-status`）。

## 3 · 交互状态机表

| 状态 | 触发 | 视觉表现 | 退出路径 |
|---|---|---|---|
| 关闭 | 无 Sub-Agents 或下拉隐藏 | `#bgagent-indicator` hidden（现状）；下拉 `display:none` | 有 agent 出现 / 点 indicator → 打开 |
| 打开·空态 | 点 indicator 但 `sessionBgAgents` 空 | 显示 `.bg-dropdown-header` + 一条内敛空态文案 `subagents.empty`（如「当前无运行中的 sub-agent」） | 有 agent → 列表态；Esc/外点 → 关闭 |
| 打开·列表态 | 有 ≥1 个 agent | 行渲染状态点+标签+kind+任务+uptime（+retries×N/工具字幕） | 行点击 → agent popup；Esc/外点 → 关闭 |
| 行·hover | 鼠标悬停在行 | `.bg-task-row:hover` 现有底色；**行内不浮现任何操作按钮**（取消/重启操作统一走 popup） | 移开 → 恢复 |
| 行·焦点 | 键盘 Tab 到行 | 显式 focus ring（**不靠 outline 裸线**，用 `box-shadow` inset 2px 左缘条 + 底，参考消息搜索 active 态）；`aria-selected` 语义 | Enter 打开；↓/↑ 行间移动 |
| 工作中（active） | status∈{Processing,thinking,tool} | 状态点绿色 + `bg-pulse` 脉冲动画（复用现有）；工具字幕显示当前 tool | 状态变更 |
| 空闲（idle/waiting） | 存活但无动作 | 状态点琥珀 `#d4a030`（无脉冲） | 状态变更 |
| **stuck 告警** | `taskStuck` 事件（idle>阈值） | 状态点红 + `bg-status-blink` 闪烁（复用现有）；行内追加 `stuck · Xm 怠速` 标签（克制红，`t('manage.stuck')`）；**不提供行内取消/重启按钮**——取消/重启统一走行点击打开的 agent popup（依 kind 矩阵，`managePolicy` 判定可操作） | 重启/取消后清除；恢复活动后清除 |
| 完成（done） | `agentDone` | 状态点转为**中性灰/绿实心**（无脉冲），标签 `done`；uptime 行隐藏（新增了终态判断）；行移出「活动区」视觉（如整行降不透明度 0.6） | 用户手动关闭 / agent 清除 |
| 失败（error） | status 前缀 `Error` | 状态点红（**实心不闪**），标签 `error`；行内不堆错误详情（详情走 agent popup） | 同 done 处理 |
| 冻结（frozen，await-user） | `agentFrozen` 事件 | 状态点 `rgb(var(--sapphire))`，标签 `frozen`（sapphire 与「后台 agent」主色呼应）；可显示 `freezeReason` 简述 | `agentResume` / escalate |
| 空列表清空 | 最后一个 agent done/removed | 下拉自动隐藏（现状逻辑）；`#bgagent-indicator` hidden | — |

## 4 · 动效规范

| 动效 | 触发 | 时长 | 缓动 | 位移/形态 | prefers-reduced-motion |
|---|---|---|---|---|---|
| 状态点脉冲 | agent 工作中（active） | 1.5s 循环 | ease-in-out | 透明度 1→0.35→1（复用现有 `bg-pulse`） | 关闭脉冲（`@media (prefers-reduced-motion: reduce)` 下 `animation:none`，静态实心点） |
| stuck 闪烁 | 卡住（红点） | 1s 循环 | ease-in-out | 透明度 1→0.3→1（复用 `bg-status-blink`） | 关闭闪烁，静态红点 |
| 行浮现 | 下拉打开 | ≤180ms | ease-out | 高度/透明度渐变（克制） | `animation-duration≤0.01s` |
| color 过渡 | 状态点变色 | 0.4s | ease | 背景/填充色 transition | 保持（颜色 transition 不触发运动敏感） |
| 移动 | 无 | — | — | **无平移/漂移动效**（监控面板克制，不做入场滑入） | ± |

**设计决策**：Sub-Agents 是监控面板，**不做多余的入场动画/闪烁之外的装饰动效**（低调克制，造新轮子禁止）；唯一「动」的元素是表达活/卡死状态的点脉冲/闪烁，均已有复用。

## 5 · 视觉规格

| 项 | 值（全部 token 引用） | 备注 |
|---|---|---|
| 面板容器 | `--glass-bg` + `-webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.15)` + `border:1px solid var(--glass-border)` + `border-radius:14px` + 顶部 `--sapphire-refraction` 1px 渐变线 | 复用现状 `#bgagent-dropdown` |
| 面板头 | `.bg-dropdown-header`（现状）：字体 11px/600/uppercase/letter-spacing 0.5px/`--color-frame-text-muted`；**补计数**「Sub-agents · N」 | 计数用 `aria-live="polite"` |
| 状态色阶 | active=绿 `var(--color-success)`（脉冲）；idle=琥珀 `#d4a030`；stuck/error=红 `var(--color-error)`（stuck 闪/error 实）；frozen=`rgb(var(--sapphire))`；done=中性 `--color-frame-text-muted` 实心 | 复用现有 `.bg-task-status`/`.bg-status-*`，新增 `.bg-status-idle`（琥珀）与 `.bg-status-done`（中性） |
| 状态点 | 6px 圆点，`flex-shrink:0`（复用现状） | 颜色+标签配对，不单靠颜色 |
| kind 徽标 | 小 chip：底色 `--glass-control-bg` + 边框 `--glass-control-border` + 文字 `--color-frame-text-muted`（11px/500），圆角 6px | 新增类名 `.bg-task-kind`，不新增颜色语义 |
| 名称+任务 | 名称 `--color-frame-text` 13px/500；任务接其后 · 分隔，`white-space:nowrap; overflow:hidden; text-overflow:ellipsis` | 单行省略 |
| 工具字幕 | `.bgagent-tool`（现状）：`--color-text-muted` 10px，`white-space:nowrap; ellipsis` | |
| uptime | 新标签 `.bg-task-uptime`：10px `--color-frame-text-muted` monospace | 相对时长（`fmtUptime`，复用 manage-panel） |
| retries chip | ×N：11px，`--color-frame-text-dim`，仅 >0 显示 | 复用 manage-panel「×N」语义 |
| stuck 标签 | `.bg-task-stuck`：`var(--color-error)` 11px/500 克制文本；**非满屏红块** | 复用 `t('manage.stuck')` 文案 |
| done/降噪 | 完成行降不透明度 0.6 | 终态视觉退位 |
| hover 底色 | `.bg-task-row:hover` 现有 `--color-frame-hover` | |
| 滚动 | `max-height:60vh; overflow-y:auto`；header sticky | 行数超出时不撑爆 |

## 6 · 边界与异常

| 场景 | 处理 |
|---|---|
| 空态 | 显示 `.bg-dropdown-header` + 一行 `subagents.empty` 文案（i18n，不硬编码）；不给假数据 |
| 行数多（>8） | `max-height:60vh` 内滚动，头部 sticky；计数在面板头 |
| 超长任务文本 | 单行省略 + `title` 完整；不换行撑爆 |
| 窄视口（375px） | 面板 `min-width:280px; max-width:380px`（现状）；行内 meta（kind/uptime/retries）自动 `flex-wrap` 到次级行，不横向溢出 |
| 多语言 | 状态标签/提示全走 `t()`（`subagents.*`/`manage.*` key）；stuck/error/done/frozen 均有 key |
| 数据缺失（**(data)**） | barrier 不推 → 不显示；status 缺 → 回退 running/done 裸态（现状兜底）；不显示「-」占位噪音 |
| done 后残留 | 完成行保留（可回看）但降噪；空列表时隐藏面板 |

## 7 · 无障碍

- **颜色非唯一信号**：每个状态点**必须**伴随文字标签（running/idle/stuck/error/frozen/done）；kind 徽标带文字非纯色块（对比 ≥4.5:1）。
- **对比度**：状态标签/时长/任务文字对面板背景对比 ≥4.5:1；琥珀 `#d4a030` 在暗色下需对照（必要时用 `--color-warning` 提亮变体，但**结论：本规格不新增 token**，若暗色对比不足则归入视觉评审 F 项记录，不改 token 纪律）。
- **键盘**：Tab 逐行聚焦；`role=list`/`listitem`；Enter 打开 agent popup；Esc 关闭下拉；↓/↑ 行间移动；焦点不逃逸（随 `agent popup` 现有 focus trap）。
- **ARIA**：`aria-live="polite"` 用于计数与 stuck 告警（stuck 用 `role="alert"` 更合适——卡住需即时感知）；`aria-expanded` 于 `#bgagent-indicator` 反映下拉开合。
- **非鼠标可达**：行操作（打开）键盘可达（Tab 聚焦 + Enter）；**取消/重启统一在 agent popup 内完成**（popup 沿现有 focus trap，键盘可达）——不依赖行内浮现按钮。

## 8 · 可断言验收点

> 口径纪律（design-system 案例 001 共性提炼）：凡涉文案→走 i18n key；涉顺序→显式语义；涉度量→增量基线。以下标注 **(auto)** = qa-frontend 可转 Playwright 自动化断言，**(截图)** = 需截图 + vision 对照，**(data)** = 依赖后端补推字段（当前数据未就绪，先断言可得部分）。

- **A1 (auto)** `#bgagent-dropdown` 打开态 computed `backdrop-filter` 含 `blur(24px)` 且背景为半透明（非纯透明）；面板 `border-radius:14px`；**无 overlay 暗化层**（铁律）。
- **A2 (auto)** 列表中每一行均含**状态点元素**（`.bg-task-status`）**且**伴随**文本状态标签**（非裸点）；抽查 running 行点色为 `var(--color-success)`、idle 行点色为 `#d4a030`（**(截图)** 补色值）。
- **A3 (auto)** stuck 行：点是红色且含 `stuck` 标签文本（`t('manage.stuck')` 的 locale 输出）+ 怠速时长；标签为克制文本非满屏红块。
- **A4 (auto)** retries chip 仅在 `retryCount>0` 时渲染 `×N`；`retryCount=0` 行无该元素（二值）。
- **A5 (auto)** barrier 相关元素**默认不在列表 DOM 中出现**（**(data)**——若后端未推，DOM 无 `.bg-task-barrier` 节点）。
- **A6 (auto)** 完成行（done）带 `.done` 态（不透明度 0.6），且 uptime 元素隐藏（终态退位）。
- **A7 (auto)** 行点击仍打开对应 agent popup（不改变导航；sessionId 前缀去 `team-` 后正确路由）；下拉在打开 popup 后收起（二值）。
- **A8 (auto)** 空态显示 `subagents.empty` 的 i18n 输出（**(截图)** 空态截图）。
- **A9 (auto)** 键盘：Tab 聚焦行、Enter 打开、Esc 关闭下拉；`#bgagent-indicator` `aria-expanded` 随开合翻转（二值）。
- **A10 (auto)** `@media (prefers-reduced-motion: reduce)` 下状态点 `animation:none`（脉冲/闪烁关闭）。
- **A11 (auto)** 窄视口（375px）增量口径：面板子树无横向滚动（`scrollWidth ≤ clientWidth`）；打开态与关闭态本底无新增溢出（增量基线，避免误伤既有面板）。
- **A12 (auto)** i18n：状态标签/空态/stuck 文案均来自 `t()`（比对运行时 locale 输出，禁用硬编码中文）。
- **A13 (auto)** stuck 行**无行内取消/重启按钮**（`display:none` 或不存在 `.bg-task-row` 内 cancel/restart 控件）——取消/重启仅在 agent popup 内。

**数据缺口提示给实现方**：A3 的「怠速秒数」来自 `taskStuck` 事件（`idleSecs`，已有）；A6 的 done 态依赖 `agentDone`/快照 status 判定（已有）；若欲做「连续 idle 时长列」与「barrier 提示」，需后端在 `activeAgents` 快照补推 `lastActivityMs`、`outstandingSubagents/pendingEventCount`——**不在本规格实现范围，另立任务**（避免与「视觉设计①」范围混叠）。

## 9 · 参考链接

- AppMaster · Background tasks with progress updates: https://appmaster.io/blog/background-tasks-progress-ui
- IBM Carbon · Status indicator pattern: https://carbondesignsystem.com/patterns/status-indicator-pattern/
- Apple · 活动监视器使用手册: https://support.apple.com/guide/activity-monitor/welcome/mac
- WAI-ARIA APG · Listbox: https://www.w3.org/WAI/ARIA/apg/patterns/listbox/
- Nebflow visual-style 铁律: `~/.nebflow/skills/nebflow/visual-style/SKILL.md`
- design-system 案例 001（断言口径共性）: `~/.nebflow/skills/design-system/SKILL.md`
- 后端字段语义: `src/main/scala/nebflow/agent/protocol.scala` · `core/tools/AgentControlTool.scala`
