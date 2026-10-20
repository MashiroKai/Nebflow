# 移动端 375 · 二级界面 P2-P12 实施批 — 实施 spec 与验收记录

- 基线：样张 v3.5（`reports/20261021_025933_mobile-mockup-375-v3.5__delegate-nebflow-74eb4637.html`）+ v3.6 作者三裁定（`reports/20261020_081636_mobile-secondary-screens-design-v3.6__delegate-nebflow-74eb4637.md`）
- 依据：v3.5 设计报告（§2 布局 / §4 动效 / §5 视觉 / §8 验收点）+ v3.6 报告（§1 三修正 / §3 验收守卫）
- 实施序：P2 → P3 → P4 → P5 → P6 → P8 → P9 → P10 → P11 → P12 → P7（画板挂起，最后处置）；每屏一提交
- 架构纪律（沿 v3 已批口径）：移动层只做呈现与交互面 —— 面板/页面复用桌面 DOM 与导出 API，功能逻辑零重造；桌面视口零行为改动（桌面痕迹仅 `body.mobile-layer` 类域内的 CSS + 手机层自建节点）；桌面共享文件只允许**加法缝**（v3 collectChains 先例）
- 测试口径：样张 §8/v3.6 §3 的样张 DOM 类名 → 实机 DOM 映射逐条记录于各屏「验收映射」小节；全部转 Playwright（`tests/mobile-p2-search.spec.mjs` 等），零后端夹具

---

## P2 搜索聊天记录 — 已实施

**提交**：`feat(mobile-p2)`（本屏一提交）

### 落地面

- **几何（§8-1）**：`#search-modal` 手机层迁出居中 modal 族 → 顶挂面板（任务浮层同族恒等式）：`left:6px / top:calc(58px+safe-area) / width:min(308px,100vw-24px) / height:min(560px,100dvh-130px) / radius:16px`；surface = mh-tpanel 同族 glass-control 值（零新 token）。样张 top 54 落地为 v3 已批面族 58px（与任务浮层一致），映射已记录。
- **二级液态**：morph 自左列搜索键（floatController 既有 `.on` 观察路径，零新增拦截）。桌面 `modalIn` 入场动画在手机层压平（`animation:none` + `#search-modal.mh-morph-anim/-close` 特异性补强）——桌面 ID 选择器特异性高于 `.mh-morph-anim` 类规则，不补强则形变被 modalIn 压住不播（自检 T1 实测发现）。
- **裁定② 主会话收窄（v3.6 §1②）**：
  - 范围/代理/工具 select + 搜索钮整行退场（`.search-filters{display:none}`）；`searchFace.js` 在激活与面板每次打开（overlay `.on` MutationObserver）把 `#search-scope` 钉在 `current`（服务端口径 = 恒主会话）；
  - 计数行（`#search-status`）=「主会话 · N 条结果（+截断尾注）」——`chatSearch.statusSummary` 加 `body.mobile-layer` 分支，i18n 新键 `search.scopeMain`（主会话 / Main session），桌面分支逐字不动；
  - 结果行会话名前缀退场（`.search-result-session{display:none}`，主会话内无二义）；
  - 筛选两行：第一行 = 类型·日期 tabs（桌面 `CONTENT_TABS` 五档沿用）；第二行 = **角色行**（`searchFace.js` 新建 `#mh-search-kind` select：全部来源/用户/助手/AI/工具 —— i18n `search.sourceAll`/`search.type*` 同源键），档值写 `window.__mhSearchKind` 缝；
  - 脚注（`#mh-search-foot`）：「仅检索当前主会话 · 类型/日期/角色筛选可叠加 · 空态：{noResults}——{noResultsSuggestion}」（empty/hint 腿 = 现网同源键，新壳键 `search.mobileScopeNote`）。
- **角色筛选缝（chatSearch.js 两处加法，桌面恒真零行为改动）**：`matchesKind(m)`（`window.__mhSearchKind` 词表 = data-kind 同款 user|agent|ai|tool）接入 `buildView`（browse 流）与 `runSearch` 匹配环（one-shot）；`scheduleSearch` 升级导出（角色行 change 走与 scope/tool select 同一条去抖调度）。
- **面板头图标（§8-2）**：`searchFace.js` 建 `.mh-search-head-ic`（lucide `search`，24px 座 15px 阴）插面板头首。
- **mark 高亮（§8-3 + 开放点 P2①）**：sapphire 族 30% —— 产品 sapphire 字面 `rgba(91,127,191,.30)`；样张 `rgba(127,156,245,.30)` 为样张侧调色，同族同 alpha，映射记录于此。

### 开放点处置（v3.5 P2 五条 → v3.6 后三条，全按设计批推荐）

| 开放点 | 处置 |
|---|---|
| ① mark 高亮底 = sapphire 30% | 采纳（`rgba(91,127,191,.30)`，见上映射） |
| ② 「工具 · WebSearch」徽标措辞 | 沿用现网 `search.typeTool`（`工具 · WebSearch` 徽标即桌面同款拼接） |
| ③ 卡片内嵌展开与跳转并存、默认展开否 | 沿用现网行为（`search-result-card` 内嵌 + 点行跳转并存），样张未另立口径 |
| ④ 范围默认 | **已裁定**（v3.6②：恒主会话） |
| ⑤ 五档筛选沿用 | **已裁定**（v3.6②：类型·日期 + 角色两行） |

### 验收映射（spec → `tests/mobile-p2-search.spec.mjs`，11/11 绿含 v3 回归）

| 样张验收点 | 实机断言 |
|---|---|
| §8-1 盒模型 6/54/308/560/r16（需截图） | T1：box x=6, w=308, h=560, radius 16px（top 落地 58px+safe 口径） |
| §8-2 面板头图标 = i-search | T2：`.mh-search-head-ic svg` 计数 1 |
| §8-3 mark 背景 sapphire 30% | T3：computed backgroundColor = `rgba(91, 127, 191, 0.3)` |
| v3.6-3 裁定②守卫（无 seg / 计数「主会话」开头 / 筛两行含「全部来源」/ 脚注含「无匹配结果」） | T2+T3：`.search-filters` display none、status `^主会话 ·`、tabs 5 + 角色行 5 档首档「全部来源」、foot 全文三腿 |
| §8-8 reduced-motion 无动画 | T4：`reducedMotion:'reduce'` 下无 morph 类 + computed animationName none |
| §8-5 面族返回契约 | T1：关闭 ⇒ `#search-overlay` 摘 `.on` |
| §8-9 零 console error | 各测试 pageerror+console error 双收集 = 0 |

---

## P3 定时任务 — 已实施

**提交**：`feat(mobile-p3)`（本屏一提交）

### 落地面

- **几何（§8-1 同族）**：`#reminder-panel` 手机层迁出 v3 面板族（316/60vh）→ 精确顶挂 `left:6 / top:58+safe / 308×560 / r16`；补 `display:flex` 纵向（桌面为块流 + max-height 裁切，固定高下 body 无法自滚）。
- **三分组（开放点③⑤按设计批推荐）**：`scheduled-task.js renderList` 加 `body.mobile-layer` 分支 —— **即将触发**（enabled≠false 且未超时）→ **已超时**（`isOverdue` 独立分组，红 clock-alert/「已超时」tag 语义仍由行级携带）→ **已停用**（`enabled=false` 整组垫底 + 行级降透明 0.72，文字档沿用桌面 .disabled）；组签 `.mh-rd-grp`（tp-grp 同族 11px/0.06em/muted）；i18n 新键 `task.grpUpcoming`/`task.grpDisabled`，「已超时」复用现网 `task.overdue`。桌面扁平序分支外逐字不动。
- **行结构零重造**：空心圈 + 内容 + 重复徽标（`repeatLabel` 单次返回 '' 不建节点 = §8-4 二值达成）+ 下次触发主行（`.reminder-row-time`，逾期带 clock-alert + tag）+ 上次触发副行（`.reminder-row-last`）——桌面既有结构即样张同构，原样沿用。
- **创建入口（开放点④）**：头部 `#reminder-create-btn`（+）与空态引导（`task.empty`/`task.emptyHint` 现网文案恰为样张口径「暂无定时任务 · 点击空白处或按 + 创建」）并存，行内创建表单（datetime + 重复四档 seg）原样可用。

### 开放点处置（v3.5 P3 五条，全按设计批推荐）

| 开放点 | 处置 |
|---|---|
| ① 下次触发主行/上次副行 | 沿用桌面既有双行结构（样张同构） |
| ② 重复徽标四档、单次不显 | 沿用 `repeatLabel`（单次不建徽标节点，T3 断言守护） |
| ③ 超时 err 红独立分组 | 采纳（已超时组 + 行级红语义保留） |
| ④ 创建入口 | 头部 + 钮与空态引导并存（桌面既有双入口原样） |
| ⑤ 已停用降透明垫底 | 采纳（组垫底 + 行级 0.72） |

### 验收映射（spec → `tests/mobile-p3-reminder.spec.mjs`，5/5 绿）

| 样张验收点 | 实机断言 |
|---|---|
| §8-4 重复徽标 {每小时,每天,每周} 枚举、单次行无徽标节点 | T3：三档徽标文本 + 单次行 `.reminder-repeat-badge` 计数 0 |
| §8-1 盒模型（同 P2 面族口径） | T1：x=6 / 308×560 / r16 |
| 开放点①③⑤ 分组与行结构 | T2：组签文本序「即将触发 · 4 / 已超时 · 1 / 已停用 · 1」+ 行序 + disabled/overdue 类 + 降透明 <1 |
| 开放点④ 创建入口 | T5：头部 + 钮 → 行内表单；空态文案 + 点击 → 行内表单 |
| §8-9 零 console error | 各测试双收集 = 0 |

测试夹具 = WS（`listScheduledTasks` → `scheduledTaskList`）；中段改推走 `routeWebSocket` 的 WebSocketRoute.send（`page.on('websocket')` 观察对象无 send，不注帧）。Esc 键会关整个面板（桌面全局键），行内创建的退出腿改走面板体内取消路径（分组签点击）。

---

## P4 心跳进程 — 已实施

**提交**：`feat(mobile-p4)`（本屏一提交）

### 落地面

- **几何（§8-1 同族）**：`#daemon-panel` 手机层迁出 v3 面板族（316/60vh）→ 精确顶挂 `left:6 / top:58+safe / 308×560 / r16` + `display:flex` 纵向。
- **进程行（样张 §2 行结构，零重造）**：桌面 `.daemon-row` 全字段沿用 —— 状态点（running/starting/stopped/error/crashed 五档语义色）+ 名（portOpen 时原生 `<a>` 链）+ `:port` + 命令 + autoStart 自启开关 + 动作钮；手机层**两行化**（行 1 = 点/名/:port/开关，行 2 = 命令 ellipsis + 动作钮右对齐；`flex-wrap` + order 重排，字段与功能零删减）。
- **运行点脉冲（样张 §4：1.6s 脉冲光环）**：桌面既有 `daemon-pulse 1.6s ease-out infinite` 原样沿用（T2 断言 animationName 守护）。
- **脚注（开放点③文案沿用）**：`daemonFace.js` 填桌面恒空节点 `.daemon-panel-footer` —— 「每 5 秒轮询 · 」+ 现网 `daemons.emptyHint2` 原文（「宿主周期检测服务端口，进程掉线约 10 秒后自动拉起。」）；「每 5 秒轮询」= 新键 `daemons.pollEvery`（轮询周期 = openPanel 既有 5000ms 实现值，非虚构）。

### 开放点处置（v3.5 P4 四条）

| 开放点 | 处置 |
|---|---|
| ① 进程行 + 近次时间线合成形态 | **时间线不实施**：后端无心跳事件流字段（`/api/daemons` 无 history/lastSeen；daemonStatus WS 为单点状态），不伪造数据 —— 记「需后端透出心跳事件流【新增提案】」，行合成形态中时间线腿缺位 |
| ② 延迟毫秒展示【新增提案】 | **不实施**（同上，无字段） |
| ③「掉线约 10 秒自动拉起」文案沿用 | 采纳（脚注后腿 = emptyHint2 原文） |
| ④ 行内自启开关与桌面双向同步 | 天然满足：开关即桌面同一 DOM 节点（optimistic flip + PUT，失败回滚），T4 守护 flip → PUT |

### 验收映射（spec → `tests/mobile-p4-daemons.spec.mjs`，4/4 绿）

| 样张验收点 | 实机断言 |
|---|---|
| §8-1 盒模型（同 P2/P3 面族口径） | T1：x=6 / 308×560 / r16 |
| §2 进程行五字段 + §4 脉冲 | T2：三行状态点类（running/starting/error）、portOpen 链 href、`:8111`、命令文本、开关勾选态、`animationName = daemon-pulse` |
| 开放点③ 脚注 | T3：含「每 5 秒轮询」+「掉线约 10 秒后自动拉起」 |
| 开放点④ 开关同步 | T4：label 点按 flip → `PUT /api/daemons/d1` body `{autoStart:bool}` 双向 |
| §8-9 零 console error | 各测试双收集 = 0 |

---
