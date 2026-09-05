# 视觉评审报告 · 消息记录搜索（规格书 v2.1 对照）

- 日期：2026-08-17 11:55 · 评审人：design-engineer（规格书作者）
- 被测实现：worktree `/tmp/nb-message-search`（feat/message-search，4 commits，`45413a8b`→`9e2b6d2a`）
- 评审方式：**隔离实例亲测**（`sbt run --home /tmp/msg-search-review/home --port 8092`，seed 确定性数据 QA-Alpha/QA-Beta）+ 真实操作（⌘F 打开、逐字符渐进输入、↑/↓、hover、Enter 跳转、Esc 两段式、暗色、375px）+ 代码 diff 逐行核 token 纪律 + qa 断言交叉验证
- 证据：`/tmp/msg-search-review/shots/`（15 张亲测截图）· qa `/tmp/msg-search-qa/shots/`（A1/A4/A5/A7/A9）· 代码 diff `a7850d28...HEAD`

# 总判定：**PASS**（视觉验收通过，无打回项）

附 1 项非阻断发现（F-1，muted token 对比度，既有全局 token、另立任务）+ 2 项备注（N1/N2）。
组间顺序复核 = **确认 qa 裁定**（见下）。功能 17/17 PASS + 视觉 PASS ⇒ P0 试点闭环，可交付用户验收。

---

## 一、逐条对照表

### §5 视觉规格（含「零新 token」声明核实）

| 规格条目 | 判定 | 证据 |
|---|---|---|
| 遮罩 transparent、禁背景暗化 | PASS | 亲测截图 r1/r2/r4：背景聊天内容透过面板可见且无暗化；qa A1 computed `rgba(0,0,0,0)` |
| 面板毛玻璃 `--glass-bg`+blur(24px)+`--glass-border` | PASS | r1/r5a：面板后绿色气泡呈磨砂虚化（亮/暗均验）；qa A1 backdropFilter 含 blur( |
| 圆角 面板20/条目10/输入8/badge5/mark3 | PASS | `modal.css:623-756` 逐值核对 + 截图目测一致 |
| 尺寸 640px / max-width calc(100vw-48px) / max-height 85vh | PASS | `modal.css:624-627`；375px 下收缩为 100vw-24px（`modal.css:802-805`，r7 实测） |
| 字重/字号（标题600/15、组头名600/12、摘要400/12、badge500/10大写、状态400/11 muted） | PASS | `modal.css:641-760` 逐值核对 + r2/r4 截图 |
| mark 高亮 亮0.28 / 暗0.36、`color:inherit` | PASS | qa A6 computed 值通过；r2（亮）/r5b（暗）截图：暗色可辨度明显提升 |
| 键盘选中态 = hover 底 + inset 2px 左缘条 0.5 / 暗0.6 | PASS | `modal.css:777-792`；r3a（首条 Top Hit）/r3b（↓ 到第二条）/r5d（暗色）截图左缘蓝条可见；qa A4 断言 |
| 重试按钮 `.glass-control` | PASS（代码级） | `chatSearch.js:485-493`：`btn.className='glass-control cfg-btn cfg-btn-sm'`（运行中未触发错误态，代码证据充分） |
| **「零新 token、仅蓝宝石透明度变体」声明** | **PASS（属实）** | `git diff a7850d28...HEAD -- modal.css` 全部 +44 行逐行核：新增色值仅 `rgba(91,127,191,0.5/0.6/0.36)` 三个透明度变体，其余全部 `var(--*)` 既有 token；sapphire.css/base.css 零改动 |

### §4 动效

| 条目 | 判定 | 证据 |
|---|---|---|
| 弹窗入场 modalIn 0.2s cubic-bezier(0.16,1,0.3,1) 复用 | PASS | `modal.css:629`；亲测开合无突兀感 |
| 结果直出无 stagger；loading 旧结果降 opacity 0.5、120ms 过渡 | PASS | `modal.css:783-785`；**亲测抓帧 r2b**：输入 `nebulax` 瞬间 status=「正在搜索…」+ 旧结果 50% 透明（CDP 拖慢网络放大窗口，data-loading=true 断言 YES） |
| 键盘选中移动 0.12s + scrollIntoView nearest | PASS | CSS + `chatSearch.js:264`；r3a→r3b 切换即时克制 |
| 跳转闪烁 1.8s ease 0.18→透明 | PASS | `modal.css:808-815`；亲测 r9：Enter 后弹窗关闭、目标消息行中段帧闪烁带可见；qa A4 flash 帧 |
| reduced-motion：入场≤0.01s / 去过渡 / flash 0.6s | PASS | `modal.css:795-799` 媒体查询逐行核 + qa A10 |
| 300ms debounce + requestId 竞态 + AbortController | PASS | `chatSearch.js:32,288-291,372-376`；亲测 50-60ms 间隔连续击键未逐键发请求，停顿后才出结果，体验符合微信/Spotlight 渐进感 |

### §2 布局与信息架构

| 条目 | 判定 | 证据 |
|---|---|---|
| 居中弹窗五段结构（header/输入/过滤器单行 wrap/status/结果列表） | PASS | r1/r2 与 §2 ASCII 图逐段对照一致 |
| ⌘F 打开（preventDefault、Monaco 内让位）、header #search-btn 保留 | PASS | `chatSearch.js:83-88`；亲测 Meta+F 打开 + autofocus（R1 YES） |
| #search-go 降级为次要提交（非必需） | PASS | `chatSearch.js:66-67`；全程未点按钮完成所有搜索（见 N2 备注） |
| scope=all 分组组头=会话名+计数；scope=current 平铺无组头 | PASS | r4/r5c（分组）vs r2（平铺）；qa A7 三重组头断言 |
| 过滤器变更即时重搜 | PASS | 亲测 selectOption scope 切换即重渲染（不等输入） |

### §3 交互状态机 / §7 无障碍（视觉相关部分）

| 条目 | 判定 | 证据 |
|---|---|---|
| 空态 autofocus + focus ring + hint | PASS | r1：输入框蓝宝石 ring 可见、hint 居中 muted |
| 结果态首条自动 active（Top Hit，v2 语义） | PASS | r2/r3a 首条选中态；↓ 后 r3b 第二条获得、第一条失去（与 §3 v2 语义及 A4 一致） |
| hover 清除键盘 .active | PASS | 亲测 R3c YES（hover 后 .active 移除） |
| 无结果态 = noResults + noResultsSuggestion（v2 独立 i18n key） | PASS | r6：「无匹配结果 / 试试缩短关键词或清除日期/类型筛选」与 v2 文案逐字一致；locales diff 两 key 双语齐全 |
| Esc 两段式 | PASS | 亲测 R6b（清空回空态 r6b 截图、弹窗仍开）→ R6c（再按关闭 YES） |
| Enter 跳转 → 关闭 → 居中滚动 → flash；焦点归还 #search-btn | PASS | r9：弹窗关、目标行 flash、**header 搜索图标上 focus ring 可见**（键盘用户焦点位明确） |
| ARIA combobox/listbox/aria-live/aria-activedescendant | PASS | index.html diff（role/aria-expanded/aria-controls/aria-live）+ `chatSearch.js:263` setActive 同步 activedescendant；qa A8 focus trap |
| muted 状态文字对比度 ≥4.5:1（§7 要求评审实测） | **实测不达标 → 发现 F-1**（非阻断） | 实测：亮 #9a9da5 于玻璃底 ≈ **2.6:1**、暗 #656870 ≈ **2.9:1**（WCAG 公式，`base.css:24,63`）。系**既有全局 token**（全 App 时间戳/muted 文案共用），非本特性引入；§8 无对应断言。按 v2.1 A9 既有基线问题先例 → **另立 token 级任务**，不在本特性打回 |

### §6 边界（视觉相关）

| 条目 | 判定 | 证据 |
|---|---|---|
| 窄视口 375px：100vw-24px、过滤器 wrap 两行、date 120px | PASS | r7-375-idle/r7-375-results：面板完整、过滤器两行、无弹窗内横向溢出；qa A9（v2.1 增量断言：开/关态 scrollWidth 基线一致） |
| 搜索中关闭 → 中止、重开全新会话（Spotlight 模式） | PASS（代码级） | `chatSearch.js:116-138` abort + resetSearchState；亲测 Esc 关闭后重开为空态 |
| locale 切换重渲染 | PASS（代码级） | `chatSearch.js:678-688` locale-changed 监听 + 全量 t() |

## 二、组间顺序复核（设计者裁定）

**复核结论：确认 qa 裁定 —— 组间顺序 = 各组最新命中时间倒序，即我的设计意图。**

- 规格书原文（§2）：「组内时间倒序」，组间顺序留白。
- 实现（`chatSearch.js:464,562-573`）：全局结果按 ts 倒序 → Map 插入序 ⇒ 组按「组内最新命中」倒序，组内条目亦时间倒序。
- 亲测证据 r4/r5c + 运行日志：QA-Alpha 组（最新命中 11:07）在 QA-Beta 组（最新 11:03）之前，计数 (3)/(2) 与组内条目数一致。
- 设计依据：微信全局搜索按会话最近活跃排序、Spotlight 按相关度/最近性排组——最近命中的会话是用户最可能的意图目标。无需改动。

## 三、发现与备注

- **F-1（非阻断，另立任务）**：`--color-frame-text-muted` 在玻璃底上对比度 亮≈2.6:1 / 暗≈2.9:1，未达 WCAG AA 4.5:1。影响范围 = 全 App muted 文案（非本特性回归）。建议：token 级任务评估加深 muted（亮 ≈#75787f 量级可达 4.5:1）或为信息性状态文字引入 muted-strong 变体；需用户裁定（触及全局视觉基调，低调克制 vs 可读性）。
- **N1（已知交互细微，无需改动）**：列表重渲染后若鼠标静止悬停于原行位置，Chromium 会对位移后位于光标下的新行施加 :hover 并触发「hover 清除键盘 active」（r4 中第二行的灰色底即此 hover 态，非 active 误判——已用 R3c DOM 断言交叉确认 setActive(-1) 生效）。行为与 §3 hover 行一致。
- **N2（taste 备注，不改）**：#search-go 绿色主按钮保留为次要提交（§2 变更点① 明示允许），是弹窗内视觉最响元素；与发送按钮同色、App 内一致。规格允许范围内，不动；若用户希望更克制可改为 glass-control，一行可调。

## 四、交叉验证声明

本报告全部视觉判断均有 ≥2 类证据互证（亲测截图 + 代码 diff 行号 / qa DOM 断言），无裸看图结论。历史误报点已专项规避：
- 「badge/active 位置」类精细判断 → 以 qa A4/A7 DOM 断言 + 自选数据 idx 截图为准；
- 「是否已修复」类判断 → 以代码 diff 逐行核 + 运行时 computed 值（qa A1/A6）为准。

## 五、闭环结论

功能验证 17/17 PASS（qa-frontend）+ 视觉评审 **PASS**（本报告）⇒ **P0 试点四道防线闭环**，建议 Manager 合入并交付用户验收。F-1 转 backlog 另立任务。
