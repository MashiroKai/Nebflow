> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# 同类面板统一设计规格书（Sub-Agents / Flows / Task 三面板）

| 字段 | 值 |
|---|---|
| 状态 | **draft**（待 Manager/用户冻结 → dispatch） |
| 作者 | design-engineer |
| 创建 | 2026-08-30 |
| 作者指令 | 2026-08-30 09:28（三要点：① Sub-Agents 面板各列排版丑、要统一；② 统一设计覆盖 flow/task 面板；③ flow 计数器改黄色与 subagent 区分） |
| 前置假设 | Sub-Agents 面板暗色文字 P0 修复（主题感知颜色，Frontend 快修中）已就位——本规格不覆盖该修复，仅在其上统一字体层级/列布局/间距/计数器色 |
| 证据目录 | `~/.nebflow/docs/Nebflow/assets/20260830_unified-panel/`（暗+亮截图 + computed styles 取证） |
| 关联规格 | `20260825_subagents-panel-spec.md`（L1 头/状态色阶继承）· `20260826_flow-complement-design.md` §5.3（badge 形态继承、颜色裁定被本规格替换） |

## §0 一句话目标

用一套 type ramp + 列网格 + 节奏 + badge 规范（统一设计语言）覆盖 Sub-Agents / Flows / Task 三面板，消除「各列各是各的排版」；flow 计数器改黄色、subagent 计数器蓝宝石加深，形成可区分的计数色体系。

## §1 参考与依据

| 来源 | 提炼规则 | 链接 |
|---|---|---|
| Nebflow visual-style 铁律（最高优先级） | 面板毛玻璃；可交互控件玻璃质感；中度字重 body 400/标题 600/按钮 500；低调克制专业感，不造新轮子 | `~/.nebflow/skills/nebflow/visual-style/SKILL.md` |
| Material 3 Type Scale | 密集列表用有限级数 type ramp（body/label 各 2-3 级），size/weight/line-height 成对定义；label 级用 medium 500 + 字距强化层级而非加大字号 | https://m3.material.io/styles/typography/type-scale-tokens |
| Apple HIG · Typography | 层级靠「字号 + 字重 + 颜色」三变量组合；同一界面内字号级数克制；等宽数字用于对齐的时间/计数 | https://developer.apple.com/design/human-interface-guidelines/typography |
| macOS 活动监视器（进程列表范式） | 监控列表 = 名称列 + 数值列右对齐；时间/数值用等宽 tabular 数字；状态用「色点 + 文字标签」配对 | https://support.apple.com/guide/activity-monitor/welcome/mac |
| IBM Carbon · Status indicator | 颜色必须与形状/标签配对，不单靠颜色；severity 用形状差异强化 | https://carbondesignsystem.com/patterns/status-indicator-pattern/ |
| WCAG 2.1 §1.4.3 | 文本对比度 ≥4.5:1（正文级）；本规格 badge 计数按正文级要求 | https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum.html |
| 既有规格 20260825_subagents-panel-spec | L1 面板头规格（11px/600/uppercase/ls 0.5px）、状态色阶（绿/琥珀/红/蓝宝石/中性）、kind chip、颜色+标签配对——本规格**继承不重造** | `~/.nebflow/docs/Nebflow/20260825_subagents-panel-spec.md` |
| 既有规格 20260826_flow-complement-design §5.3 | flows badge「镜像 bgagent badge」形态——本规格**继承形态、替换颜色**（作者 2026-08-30 裁定黄色） | `~/.nebflow/docs/Nebflow/20260826_flow-complement-design.md` |
| design-system 案例库 001 | 二值断言口径显式化纪律（文案→i18n key、布局→增量基线）；零新颜色 token 纪律先例 | `~/.nebflow/skills/design-system/SKILL.md` |

**冲突取舍**：
- M3 的 label-small 11px/medium 与 Nebflow 现状 L4=11px 一致，直接对齐；M3 body-medium 14px 不采用——Nebflow 全站 body 基线 13px（base.css 按钮/气泡实测），面板行主体沿用 13px 不引入新基线。
- 0825 规格「零新增颜色 token」与本规格新增黄色 token 冲突：**作者显式裁定优先**（黄色区分是本次任务的显式需求），新增 2 个 token（`--accent-flow` 暗/亮成对 + `--accent-agent-light` 仅亮）并在 §6 显式说明理由；蓝宝石亮主题加深同理（亮主题对比度修复，0825 未覆盖的盲区）。

## §2 统一设计语言

### §2.1 Type ramp（面板内五级字）

| 级 | 语义 | size | weight | line-height | 其他 | 颜色 token |
|---|---|---|---|---|---|---|
| L1 | 面板头 | 11px | 600 | normal | uppercase · ls 0.5px | `--color-frame-text-muted` |
| L2 | 分组头 | 10-11px | 500-600 | normal | 三变体：zone 级 11/500 不 uppercase（待办/任务）；team 级 10/600 uppercase · ls 0.5px；member 级 10/500 · ls 0.2px · 不 uppercase | `--color-text-muted` / `--color-frame-text-muted` |
| L3 | 行主体 | 13px | 400（active 行 500） | 1.4 | nowrap+ellipsis；**L3-desc 变体（仅 task）**：12px/400 · 2 行 clamp · `--color-text-dim` | `--color-text`（内容区）/ `--color-frame-text`（框区） |
| L4 | 行 meta | 11px | 400 | 1.4 | nowrap+ellipsis | `--color-text-muted` / `--color-frame-text-muted` |
| L5 | micro | 10px | 500 | normal | 时间类 mono + tabular-nums；chip 类（kind）承 size/weight 不承 mono | `--color-text-muted` / `--color-frame-text-muted` |

字重纪律：对齐全站方案 B（body 400 / 标题 600 / 按钮 500）——L3=400（active/flow-name=500）、L1/L2-team=600、L2-zone/L2-member/L4=400-500、chip/按钮=500。

### §2.2 列网格（行结构）

三面板行结构统一为五槽位（存在性按面板数据模型裁剪，槽位顺序不变）：

```
[status] [meta-lead] [primary] [spacer] [meta-trail]
   1         2          3         —         4/5
```

| 槽位 | 内容 | 对齐 | 弹性 | 截断 |
|---|---|---|---|---|
| 1 status | 状态点（6px，subagent/flow）或勾选控件（20px，task） | 垂直：单行行 `align-items:center`；双行行 `flex-start` + `margin-top` 使点中心对齐 L3 首行基线（task 现状 1px 沿用） | flex-shrink:0 | 不截断 |
| 2 meta-lead | 状态词（进行中/空闲/排队中）· kind chip（subagent） | 左对齐，与 primary **基线对齐**（`align-items:baseline` 于 `.bg-task-line`/`.flows-line`） | flex-shrink:0 | 不截断（词短） |
| 3 primary | 名称/subject（L3） | 左对齐 | `flex:1; min-width:0` | 单行 `nowrap+ellipsis`（subagent/flow）；task subject 单行 ellipsis，desc 独立 2 行 clamp（现状保留） |
| 4 spacer | — | `margin-left:auto` 推右 | — | — |
| 5 meta-trail | 时间（uptime/elapsed/last-active）· 计数 chip（retries）· 进度（flow 2/5 节点） | **右对齐**，等宽 tabular-nums | flex-shrink:0 | 窄视口按 §7 顺序让步 |

**基线对齐规则**（核心修复）：行内所有文本槽位（2/3/5）以 L3 首行基线对齐——`.bg-task-line`、`.flows-line` 改 `align-items:baseline`；非文本元素（状态点、chip）以 `align-self:center` 回退视觉居中。双行行（task 带 desc）status 与 meta-trail 对齐首行（现状 `flex-start` + 微调保留）。

**列宽策略**：不引入固定 table 列宽（三面板行内容异构）；用「primary 弹性吸收 + trail 右对齐」实现跨面板视觉列对齐——所有面板的时间列右缘同贴面板右内边距线（12px），形成贯穿三面板的右对齐时间栏（活动监视器范式）。

**现状偏差（取证）**：subagent uptime 已 `margin-left:auto` 右对齐 ✓；flow elapsed 在第二行左对齐 ✗（目标：移入 `.flows-line` 右缘）；task status-word/last-active 右对齐 ✓ 但无 tabular-nums ✗。

### §2.3 行高/间距节奏

| 维度 | 值 | 现状对照 |
|---|---|---|
| 行内 padding（dropdown 行） | `8px 12px` | subagent/flow 现状一致 ✓ |
| 行内 gap（槽位间） | 8px | subagent `.bg-task-line` 8px ✓；flow `.flows-line` 8px ✓；task 8px ✓ |
| 行内双行 gap（primary+desc） | 3px（subagent `.bg-task-info`）/ 1px（task desc margin-top） | **统一为 2px**（取中，视觉等价） |
| 行间分隔 | `border-bottom: 1px solid var(--color-frame-border-light)`（dropdown）；task 面板无分隔线（现状保留，卡片内靠间距分组） | 现状 ✓ |
| 面板内边距 | 12px 水平（dropdown 行 padding 承担）；task 卡片 `8px 12px 6px` | 现状 ✓ |
| 分组头上下距 | team 级 `6px 2px 2px`；member 级同；zone 头 `8px 2px 2px` | task 现状 ✓ 沿用 |
| 缩进阶梯（task 三级分组） | team 行 +10px、member 行 +22px | task 现状 ✓ 沿用 |
| 圆角 | 面板 14px；chip 6px；badge 999px | 现状 ✓ |

**节奏原则**：4px 基线网格——所有 padding/margin 取 4 的倍数（2px 仅用于行内微调与 border 补偿）。现状已大体符合，本表锁定为纪律，禁止后续散值。

### §2.4 Badge/计数器规范

header 指示器 badge（`#bgagent-indicator` / `#flows-indicator`）与行内计数 chip（`.bg-task-retries`）共用一套几何：

| 维度 | header badge | 行内 chip |
|---|---|---|
| 形态 | 胶囊 999px + 状态点 7px + 计数 | 圆角 6px 玻璃 chip（kind chip 同款） |
| 几何 | padding `2px 9px` · gap 5px · font 12px/500 | padding `1px 6px` · font 11px/500 |
| 底色 | 主色 0.15 alpha over 透明 | `--glass-control-bg` + `--glass-control-border` |
| 环 | 1px 主色 0.35 alpha | 1px `--glass-control-border` |
| 文字色 | 主色（见 §6 色值表） | `--color-frame-text-muted` |
| 数字 | tabular-nums | tabular-nums |
| hover | 底色 alpha 0.15→0.25 | — |

**计数色体系**（§6 详）：subagent = 蓝宝石（亮主题加深）、flow = 黄色（作者裁定）、task 面板计数（`.task-stats`）= 中性 muted 无色相（待办/任务双段统计，不占色相槽）。三面板色相槽：蓝=agent、黄=flow、无色相=task——色相即语义。

## §3 三面板套用（现状 → 目标）

### §3.1 Sub-Agents 面板（`#bgagent-dropdown` · `#bgagent-indicator`）

现状取证（computed styles，暗/亮）：`.bg-task-name` **16px/400 继承 body**（无显式字号——「丑」的根因）；`.bg-task-state` 11px/500；`.bg-task-kind` 10px/500；`.bg-task-uptime` 10px/400 mono 右对齐；`.bg-task-retries` 11px/400；`.bg-dropdown-header` 11px/600 uppercase ✓。

| 元素 | 现状 | 目标 | 套级 |
|---|---|---|---|
| 面板头 `.bg-dropdown-header` | 11/600/uppercase/ls0.5 | 不变 | L1 |
| 状态词 `.bg-task-state` | 11/500 | 11/**400**（状态词非标题，降权让位 primary） | L4 |
| kind chip `.bg-task-kind` | 10/500 玻璃 chip | 不变 | L5+chip |
| 名称 `.bg-task-name` | **16/400 继承** | **13/400**（active 行不加重——subagent 行无 active 语义，状态点已表达） | L3 |
| uptime `.bg-task-uptime` | 10/400 mono 右对齐 | 10/500 mono + `font-variant-numeric:tabular-nums` 右对齐 | L5 |
| retries `.bg-task-retries` | 11/400 裸文本 | 11/500 玻璃 chip 化（与 kind chip 同族，`title` 保留） | L4+chip |
| 行内对齐 `.bg-task-line` | `align-items:center` + flex-wrap | `align-items:baseline`（文本槽基线对齐）；状态点 `align-self:center`；flex-wrap 保留（§7 窄视口） | §2.2 |
| 双行 gap `.bg-task-info` | gap 3px | **2px**（§2.3 节奏统一） | §2.3 |
| 状态点 `.bg-task-status` | 6px | 不变（0825 色阶继承） | — |
| header badge | 蓝宝石 0.15/0.35 | 亮主题文字色加深（§6） | §2.4 |

**不动项**：空态 `.bg-dropdown-empty`、stuck 告警行、focus 左缘条、reduced-motion 适配（0825 规格冻结项）。

### §3.2 Flows 面板（`#flows-dropdown` · `#flows-indicator`）

现状取证：`.flows-name` 12px/500；`.flows-progress` 11px/400；`.flows-elapsed` 11px/400（第二行左对齐）；`.flows-status` 8px 点（与 subagent 6px 不一致）；`.flows-cancel` 11px 边框按钮；header badge 与 subagent **完全同色**（蓝宝石，作者裁定要区分）。

| 元素 | 现状 | 目标 | 套级 |
|---|---|---|---|
| 面板头（复用 `.bg-dropdown-header`） | 11/600/uppercase | 不变 | L1 |
| 名称 `.flows-name` | 12/500 | **13/500**（flow 行无状态词 lead，名称即 primary 且承载运行语义，保留 500 呼应 task `.task-active`） | L3+500 |
| 进度 `.flows-progress` | 11/400 行内左 | 11/400 移入 meta-trail 右对齐（与 elapsed 同槽，gap 8px） | L4 |
| elapsed `.flows-elapsed` | 11/400 第二行左 | **10/500 mono tabular-nums** 右对齐（与 subagent uptime 同槽同款） | L5 |
| 取消 `.flows-cancel` | 11px 边框按钮 | 不变（按钮 500 纪律：补 `font-weight:500`） | 按钮 |
| 状态点 `.flows-status` | 8px | **6px**（对齐 subagent；margin-top 重算对齐基线） | — |
| 行结构 | 双行（line+meta） | 单行化：`[点][名称][spacer][进度][elapsed]`；取消按钮保留右缘（hover 显现可选，现状常显保留） | §2.2 |
| header badge | 蓝宝石全套 | **黄色**（§6 色值表） | §2.4 |

**不动项**：行点击重开 flow-run 标签页、dismiss sessionStorage 语义、空态、键盘 Enter/Space（0826 规格冻结项）。

### §3.3 Task 面板（`#task-list` · `.task-card`）

现状取证（源码版 CSS，route 拦截取证）：`.task-label` 13px/400（active 500）✓；`.task-desc` 12px/400 dim ✓；`.task-status-word`/`.task-last-active` 11px/400 右对齐 ✓；`.task-stats` 12px/400；zone 头 11px/500；team 头 10px/600 uppercase；member 头 10px/500。**task 面板是三面板中最合规的**——本规格以 task 为锚，主要补 tabular-nums 与 stats 字重纪律。

| 元素 | 现状 | 目标 | 套级 |
|---|---|---|---|
| header `.task-stats` | 12/400 muted | 12/400 + tabular-nums（计数数字对齐） | L4 |
| zone 头 `.task-group-header` | 11/500 | 不变 | L2-zone |
| team 头 `.task-subgroup-header` | 10/600 uppercase ls0.5 | 不变 | L2-team |
| member 头 `.task-subgroup-member` | 10/500 ls0.2 | 不变 | L2-member |
| subject `.task-label` | 13/400（active 500） | 不变 | L3 |
| desc `.task-desc` | 12/400 dim 2 行 clamp · margin-top 1px | 不变，margin-top **2px**（§2.3 节奏统一） | L3-desc |
| 状态词 `.task-status-word` | 11/400 | 不变 | L4 |
| 时间 `.task-last-active` | 11/400 | **10/500 mono tabular-nums**（与 subagent uptime / flow elapsed 同款右对齐时间栏） | L5 |
| 勾选控件 `.task-check` | 20px 圆/方/spinner | 不变（0830 任务重做裁定冻结） | — |
| 行内对齐 | 单行 center / 双行 flex-start | 不变（已符合 §2.2 双行规则） | §2.2 |

**不动项**：双区结构、三级分组缩进、完成动效（fill→collapse）、zone-enter/leaving、WS-down 禁用态、reduced-motion（任务工具重做规格冻结项）。

## §4 交互状态机表

本规格只管视觉层状态（行为状态机各依冻结规格：0825/0826/任务重做）。

| 状态 | 行（三面板通用） | header badge | 计数 chip |
|---|---|---|---|
| default | 现状各面板行样式 | 底色 0.15 · 环 0.35 | 玻璃 chip |
| hover | `--color-frame-hover` 底（dropdown 行）；task 行无 hover 底（现状保留） | 底色 0.25 | — |
| focus-visible | `inset 2px 0 0` 左缘条（subagent/flow 现状）；task 勾选控件 outline（现状） | 同 hover + outline | — |
| active/pressed | 无独立态（行点击即跳转 popup/标签页） | 底色 0.25 | — |
| disabled | task 勾选 WS-down `opacity:0.5 + not-allowed`（现状） | — | — |
| loading | flow 行无独立 loading（running 即进行态）；task spinner 环（现状冻结） | — | — |
| error | 状态点红 + 状态词红（subagent stuck/error；flow failed）——0825 色阶 | — | retries chip 不变色（计数非告警） |
| empty | `.bg-dropdown-empty` / `.task-empty`：L4 居中/左对齐 muted 文案（现状） | badge 隐藏（count=0，现状） | 隐藏（0 不显，现状裁定） |
| done/终态 | subagent `.done` opacity 0.6（现状）；flow 终态行不留在 dropdown（现状） | 归零隐藏 | — |

## §5 动效规范

| 动效 | 触发 | 时长 | 缓动 | 位移 | reduced-motion |
|---|---|---|---|---|---|
| badge 底色过渡 | hover | 0.2s | ease | 无 | 保留（颜色过渡不触发运动敏感） |
| 状态点脉冲 | active/running 常驻 | 1.5s | ease-in-out | 无（opacity/scale 微） | **停**（现状 `animation:none`） |
| stuck 闪烁 | stuck 常驻 | 1s | ease-in-out | 无 | **停** |
| 行 hover 底 | hover | 0.15s | ease | 无 | 保留 |
| task 入场/跨区/折叠 | 行增删/zone 移动/完成 | 0.16-0.3s | cubic-bezier(0.4,0,0.2,1) | ≤12px | **停**（现状冻结） |
| 本规格新增动效 | **无** | — | — | — | 字体/颜色/布局改为瞬时切换，不引入任何新动画 |

纪律：统一设计是静态规格——不借统一之名新增动效。

## §6 视觉规格（含计数器色值）

### §6.1 字体/字重/行高（汇总）

- 字体族：全站既有 `-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif`（不引入新族）
- 等宽族（L5 时间栏）：`ui-monospace, SFMono-Regular, monospace`（现状沿用）+ `font-variant-numeric: tabular-nums`
- 字重纪律：L3=400（active/flow-name=500）· L1/L2-team=600 · L2-zone/L2-member/L4=400-500 · chip/按钮=500——对齐全站方案 B
- 行高：L3/L4=1.4；L1/L2/L5=normal（现状一致）

### §6.2 计数器色值（作者裁定：flow=黄色）

**新增 token 2 个、色值 3 枚**（显式理由：作者 2026-08-30 显式裁定黄色区分 + 亮主题蓝宝石对比度盲区修复；非自由发挥）：

| token | 暗主题 | 亮主题 | 用途 | 对比度（实测计算） |
|---|---|---|---|---|
| `--accent-flow`（新） | `#e8b73f` | `#8a6a00` | flow badge 文字/点/环 | 暗 vs `#181c26` **9.15:1** ✓；亮 vs #fff **5.07:1** ✓；亮 vs badge 底（0.15 叠加 #ede9d9）**4.17:1**（计数 12px/500 按正文级 4.5 差 0.33——见取舍①） |
| `--accent-agent-light`（新） | —（暗主题沿用 `--sapphire`） | `#46639c` | subagent badge 亮主题文字/点/环 | 亮 vs #fff **5.96:1** ✓；vs badge 底 **#e6ecf5** **5.02:1** ✓（修复现状 4.01/3.38 不达标） |
| （暗主题 subagent） | `--sapphire` rgb(91,127,191) 不变 | — | — | 暗 vs glass **4.24:1**（现状保留，0825 冻结） |

badge 底色/环 alpha 结构不变（0.15/0.35，hover 0.25），仅换色相载体。

**取舍①**（亮主题 flow 文字 vs badge 底 4.17）：badge 底是 0.15 alpha 叠加的浅色，实际页面背景更浅时对比更高；且计数数字伴随 `title`/`aria-label` 全文案。若 qa 实测不达标，fallback = 亮主题 `--accent-flow` 降为 `#7a6200`（vs 底 5.39:1，色相 48° 仍黄）——**实现方二选一须在本表内选，禁自由调色**。

**色相语义**：蓝=agent（sapphire 既有）· 黄=flow（新）· 无色相=task（muted）。黄与 warning 橙 `#ff9800`（hue 33°）区分：flow 黄 hue 43-46° 偏金黄、暗主题明度更高；且 flow 黄只出现在 badge/计数槽位，warning 橙只出现在 stuck/cancelling 语义槽位——槽位互斥不混淆。

### §6.3 玻璃质感（继承，不重造）

- 面板壳：`--glass-bg` + `blur(--glass-blur)` + `--glass-border` + 14px 圆角 + 顶部 refraction 线（现状三面板一致 ✓）
- chip：`--glass-control-bg` + `--glass-control-border` + 6px 圆角（kind chip 现状；retries chip 套同款）
- 按钮（`.flows-cancel`/管理按钮）：`font-weight:500` 补齐；玻璃质感 `.glass-control` 仅用于 popup 管理按钮（现状），dropdown 内 cancel 保持轻量边框款（克制，不全员玻璃）

### §6.4 圆角/边框/阴影

全部继承现状（14px 面板 / 6px chip / 999px badge / `--glass-border` / 双层投影）——本规格零改动，仅锁定。

## §7 边界与异常

| 场景 | 规则 | 现状 |
|---|---|---|
| 空态 | 三面板空态文案 L4 muted；subagent/flow 居中（`.bg-dropdown-empty`）、task 左对齐（`.task-empty`）——对齐方式差异保留（dropdown 单列居中自然、task 卡片左对齐自然），不强行统一 | ✓ |
| 超长文本 | primary 单行 ellipsis + `title` 全文（subagent/flow/task-subject）；task desc 2 行 clamp + title；状态词不截断（词短） | ✓ 补 flow-name 已有 title ✓ |
| 计数溢出 | badge 计数不设上限截断（实际 ≤ 两位数）；retries `×N` 不截断 | ✓ |
| 窄视口 ≤480px | task：status-word → last-active 顺序隐藏（现状）；subagent/flow：`.bg-task-line`/`.flows-line` flex-wrap 换行，meta-trail 换行后仍右对齐（`margin-left:auto` 保持） | subagent ✓；**flow 现状无 flex-wrap → 批次 1 补（纯 CSS）** |
| 多语言 | 文案全走 `t()` i18n（现状）；本规格不新增文案 key（零文案改动） | ✓ |
| 暗/亮主题 | 全部颜色走 token；新增 3 token 暗/亮成对定义（§6.2）；**主题感知颜色 P0 修复（Frontend 快修中）是本规格前提**——`.bg-task-name` 等用 `--color-text`/`--color-frame-text` 而非硬编码 | 前提 |
| 行数多 | dropdown `max-height:60vh` 滚动（现状）；task `max-height:50vh`（现状） | ✓ |

## §8 无障碍

| 项 | 规则 | 现状 |
|---|---|---|
| 键盘导航 | 行 `tabindex=0` + Enter/Space 打开 + ↑/↓ 移动（subagent/flow 现状 APG listbox-lite）；task 勾选控件独立 tab stop | ✓ 不改 |
| focus ring | dropdown 行 inset 左缘条；task 勾选 outline 2px sapphire 0.5 | ✓ 不改 |
| ARIA | badge `role=button + aria-expanded + aria-controls`；面板头 `aria-live=polite`；行 `role=listitem`；stuck `role=alert`；task 行 aria-label 携带状态 | ✓ 不改 |
| 对比度 | 正文级 ≥4.5:1：本规格修复 subagent 亮主题 badge（4.01→5.96）；flow 黄亮主题 5.07（vs 白）/4.17（vs badge 底，取舍见 §6.2①）；L3-desc 用 `--color-text-dim`（≥4.5 既有裁定）。**已知残留**：暗主题 sapphire 4.24:1（0825 冻结现状，非本规格引入，记录不修） | 修复+锁定 |
| 非颜色信号 | 状态点必伴文字标签（0825 冻结）；flow 黄 badge 伴 `title`/`aria-label`「运行中的工作流」；色相语义（蓝/黄/无）均有文案兜底 | ✓ |
| reduced-motion | 脉冲/闪烁/入场全停（现状冻结）；本规格零新动效 | ✓ |

## §9 可断言验收点（供 qa-frontend 转 Playwright）

口径纪律（案例 001）：颜色断言比精确 rgb 字符串；布局断言用同容器相对量（增量口径），不依赖绝对视口；文案断言本规格零新增 key 故不涉及。

| # | 断言（二值） | 需截图 | 批次 |
|---|---|---|---|
| A1 | 三面板 primary 字号统一：`.bg-task-name`、`.flows-name`、`.task-label` computed `font-size` 均 === `13px` | 否 | 1 |
| A2 | 字重纪律：`.bg-task-name` weight `400`；`.flows-name` weight `500`；`.task-label` weight `400`（`.task-active .task-label` === `500`）；`.bg-dropdown-header` weight `600` | 否 | 1 |
| A3 | 基线对齐：`.bg-task-line` 与 `.flows-line` computed `align-items` === `baseline` | 否 | 2 |
| A4 | 时间栏同款：`.bg-task-uptime`、`.flows-elapsed`、`.task-last-active` 三者 computed `font-variant-numeric` 含 `tabular-nums`；前两者 `font-family` 含 `monospace` | 否 | 1/2 |
| A5 | 时间栏右对齐：三面板时间元素 `getBoundingClientRect().right` 与所在行内容右缘差 ≤ 2px（行 padding 12px 口径：`row.right - 12 - el.right` ∈ [-1, 1]） | 否 | 2 |
| A6 | flow 计数器黄色：`#flows-indicator` computed `color` 暗 === `rgb(232, 183, 63)`、亮 === `rgb(138, 106, 0)`（fallback `#7a6200`→`rgb(122, 98, 0)` 亦 PASS，须与 §6.2 表一致） | 是 | 1 |
| A7 | subagent 计数器区分：`#bgagent-indicator` computed `color` 暗 === `rgb(91, 127, 191)`、亮 === `rgb(70, 99, 156)`；且暗/亮两主题下 `#flows-indicator.color !== #bgagent-indicator.color` | 是 | 1 |
| A8 | 亮主题对比度修复：`#bgagent-indicator` 亮主题文字色 vs `#ffffff` 对比 ≥ 4.5（断言 computed color === 表值即蕴含，qa 用常量表复核） | 否 | 1 |
| A9 | flow 行单行化：`.flows-elapsed` 是 `.flows-line` 的后代（`line.contains(elapsed)` === true）；`.flows-status` computed `width` === `6px` | 否 | 2 |
| A10 | retries chip 化：`.bg-task-retries` computed `border-radius` === `6px` 且 `background-color` !== `rgba(0, 0, 0, 0)` | 否 | 2 |
| A11 | 零新动效：三面板容器子树 computed `animation-name` 集合 === 修复前基线集合（`bg-pulse`、`bg-status-blink`、`spin`、task 入场系）——无新增 | 否 | 1+2 |
| A12 | 暗/亮双主题：A1-A10 在 `colorScheme: dark` 与 `light` 两上下文各跑一遍全过 | 是（各一张三面板同框） | 1+2 |

截图证据要求：暗/亮各一张「header 双 badge + 三面板同框」对照图，入评审报告。

## §10 实施分期

**结论：两批交付**（三面板同文件域风险低，但批次 2 触两个冻结规格的断言选择器，须留回归窗口）。

| 批次 | 内容 | 文件域 | 回归面 |
|---|---|---|---|
| 1（纯样式） | 新增 2 token（sapphire.css 暗/亮块）；`.bg-task-name` 13/400；`.bg-task-state` 400；`.bg-task-uptime`/`.task-last-active` 500+tabular；`.flows-name` 13；`.flows-cancel` 500；`.flows-line` 补 flex-wrap；`.flows-status` 6px+margin-top 重算；badge 换色；retries chip 化；双行 gap 2px（`.bg-task-info`/`.task-desc`）——均仅 CSS，markup 不动 | sapphire.css · chat.css · taskList.css | 0825 断言全量重跑（选择器未动，应全绿） |
| 2（markup 微调） | `.bg-task-line`/`.flows-line` 改 baseline（CSS，依赖 markup 定型故随批 2 验）；flow 行单行化（`.flows-elapsed` 移入 `.flows-line`、`.flows-progress` 移入 trail 槽——flowCanvas.js renderFlowsDropdown 模板改） | flowCanvas.js markup + chat.css | 0826 §5.3 断言（手动重开/刷新恢复/终态消失）重跑防回归 |

**不分期理由记录**：作者指令「三面板同文件域风险低可一次」——若 Manager 裁定一次交付，批次 1+2 合并即可，断言表已标批次列供 qa 分组。

## §11 参考链接

- visual-style：`~/.nebflow/skills/nebflow/visual-style/SKILL.md`
- design-system 案例库：`~/.nebflow/skills/design-system/SKILL.md`
- M3 Type Scale：https://m3.material.io/styles/typography/type-scale-tokens
- Apple HIG Typography：https://developer.apple.com/design/human-interface-guidelines/typography
- macOS Activity Monitor：https://support.apple.com/guide/activity-monitor/welcome/mac
- Carbon Status Indicator：https://carbondesignsystem.com/patterns/status-indicator-pattern/
- WCAG 2.1 Contrast：https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum.html
- 既有规格：`20260825_subagents-panel-spec.md` · `20260826_flow-complement-design.md`
- 取证截图：`assets/20260830_unified-panel/`

## 附录 A · 现状取证清单

取证方法：Playwright 连活实例 :8080（只读 + 页面上下文内 mock 注入，不改产品代码/不写存储）；task 面板三级分组因活实例服务旧版资源，用 route 拦截服务源码版 js/css 取证（源码即现状）。

| 文件 | 内容 |
|---|---|
| `00-{dark,light}-full.png` | 全页 |
| `01-*-subagent-dropdown.png` | subagent 面板：name 16px 继承（过大）、state/kind/uptime 字号散 |
| `02-*-flows-dropdown.png` | flow 面板：name 12px、elapsed 左对齐第二行、点 8px |
| `03-*-task-panel.png` | task 面板：三级分组、13px 主体（最合规锚点） |
| `04-*-header-indicators.png` | **双 badge 同蓝宝石**（作者裁定要区分的现状） |
| `05-*-both-dropdowns.png` | 双面板同框：字号/对齐不一致直观对照 |

关键 computed 值（暗主题实测）：

| 选择器 | size/weight | 备注 |
|---|---|---|
| `.bg-task-name` | 16px/400 | 继承 body，无显式字号——根因 |
| `.bg-task-state` | 11px/500 | — |
| `.bg-task-uptime` | 10px/400 mono | 右对齐 ✓ |
| `.flows-name` | 12px/500 | — |
| `.flows-elapsed` | 11px/400 | 左对齐 ✗ |
| `.task-label` | 13px/400 | 锚点 |
| `.task-last-active` | 11px/400 | 右对齐 ✓ 无 tabular |
| `#bgagent-indicator` / `#flows-indicator` | 12px/500 · rgb(91,127,191) | **同色** ✗ |
| `.bg-dropdown-header` | 11px/600 uppercase | L1 锚点 |
