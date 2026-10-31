---
type: design-note
status: published
date: 2026-10-31
scope: 后台任务面板层级+命名+版式批（作者 10-31 11:51 截图令 + 11:52 追加令④ + 11:58 锚点）——四件：层级显形 / Delegate 工具 name 参数 / 禁横向滚动 / 桌面拆两面板（手机端合并不变）
---

# 后台任务面板层级·命名·版式批实施记（2026-10-31）

关联：[[20261031_112344_delegate-standby-semantics-audit__delegate-nebflow-c0a2b939]]（同日前序 delegate 批）、[[index]]。

证据：`evidence/20261031_bg-panel-hier-name-split__delegate-nebflow-4cbce603/shots/`（desktop-agents / desktop-tasks / mobile-375 双主题截图 + 行级断言）。回归：`tests/bg-merged-panel.spec.mjs` T1–T10 全绿；`tests/bg-panel-hiername-shots.mjs` 自足视觉断言 ALL PASS。

## 四件与数据链

1. **层级显形**：delegate 组头行首 caret 折叠子行树（`bgAgents.js` `bgHeadCollapsePref` 用户偏好 > 默认规：终态组头收起、进行中父展开）；完成态灰化/计数签不变。10-29 面板树批（缩进+引导竖线）之上补折叠语义。
2. **行首短名**：Delegate 工具新增 `name` 参数（≤12 字，`DelegateRegistry.ShortNameMaxChars=12` 钳位）→ `Record.name`（手工 Decoder 缺键容忍，旧盘零迁移）→ 三条 spawn 腿落盘（内置/外部/远端）→ 快照面 `delegateName` 键（`activeAgentEntryJson` additive）+ live 面 `agentStart` 同键注入（`routeWsSend`）→ provider 行镜像 `row.name`（`delegateChainStub.rowFromRegistry`）→ 组头行首主名 = 短名、标题降 `.bg-task-sub` 次行（全文 tooltip）。无名旧数据回退既有标题截断显示。弹窗头名 = 行首已解析显示名（作者锚点：不再把执行器 def 名「nebflow」顶在头位）。description（dispatch 提示词面）带「用短名概括批次」口径 + `maxLength: 12`。
3. **一屏装完**：面板宽度钳进视口（`min/max-width: min(…, calc(100vw - 48px))`），列表/节 `overflow-x: hidden`，信息列槽位 `min-width:0 + max-width:100%`；`.bg-task-name` 分数收缩 `flex 0 0.5 auto` 在窄宽解析失稳（长名行 layout 越出容器）→ 改 `flex 0 1 auto`，徽章升 `flex-shrink: 2`（「徽章先截、名字半速」相对速率不变）。判据用布局矩形不出列表右缘 + overflow-x 机制（scrollWidth 会把刻意 clip 的省略文本算进来，不作判据）。
4. **桌面拆两面板**：`#bg-indicator` 开合挂 `bg-view-tasks`（隐智能体节）、`#bgagent-indicator` 挂 `bg-view-agents`（隐任务节）；CSS 闸 `body:not(.mobile-layer)`——手机端合并单面板逐字不变。

## 踩坑记录

- `page.evaluate` 不序列化闭包——Node 侧常量必须显式传参（shots 脚本 T0/LONG_TITLE 两撞）。
- 终态 delegate 组头默认收起后，旧有「点行开详情」行为不变；caret 停冒泡独立点击。
- Mail 复活腿/外部续聊腿重放 Record 时 `name` 回读存量记录补保（`shortName.orElse(find(id).flatMap(_.name))`），复活不丢名。
- 邻接 spec（`#bg-dropdown > .bg-dropdown-list:last-child` 选择器族：subagent-panel-merge-status / delegate-ui-twolayer / bgagent-dedupe / bg-device-origin-chip 等 11 件）在 pristine HEAD 上同数失败——10-23 合并批后陈旧选择器未跟着改，**先于本批存在**，未在本批范围内修复（开放项）。
