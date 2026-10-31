---
type: batch
status: final
date: 2026-10-31
topic: 后台智能体面板徽标归位批（顶层只标 delegate 模式 kernel/dispatcher）
---

# 后台智能体面板徽标归位批（2026-10-31 23:02 作者令 + 实拍截图）

关联：[[20261031_123300_bg-panel-hier-name-split__delegate-nebflow-4cbce603]]（面板层级/命名批）、[[20261031_owner-lost]]（同面板归属调查）、任务模式标注批 commit `5b7fe0670`（row.mode 键的引入批）。

## 裁定口径（逐字）

- **顶层行只标一个徽标 = delegate 模式**（kernel 或 dispatcher，即 `delegate:type`），不再出现 Delegate/Subagent 字样混在顶层；
- **子级行**（下钻展开的下一层）才标 subagent 或 agentflow（视其实际类型）。

行级判定锚 = **层级**（组头/leftover = 顶层；缩进行 = 子级），不是历史类型残留。

## 乱象与考古（徽章来源字段取错）

作者实拍截图：顶层智能体行徽标三种乱标——有的 kernel、有的 Delegate、有的 Subagent（「配置风格统一批」「Inventory …」等顶层批挂着 Subagent），同层混排。

根因在组头徽标词函数 `bgDelegateBadgeWord`（`src/main/resources/web/js/main/bgAgents.js`）的**兜底链**：`row.mode`（kernel/dispatcher，双模式总闸开时才有值）缺席时逐级回退——

1. `row.orchestrationMode`（G1 编排模式：subagent-star → `Subagent`、agentflow → `Flow`）；
2. 快照 kind（AgentKind.SubTask → 显示词 `Delegate`）；
3. 会话 id 前缀启发（`bgAgentKindFromSession`）。

这三个字段全是**历史类型残留**：编排模式随子代理 spawn 记账惰性升级（SubAgentTaskStore 记账点 `upgradeOrchestrationMode`）、kind 是 spawn 时刻的会话种类——它们回答「这批曾经怎么编排」而非「这行在面板的哪一层」。双模式总闸（`delegate.dualMode.enabled`，缺省 false）关着时 `row.mode` 恒空 ⇒ 兜底链把残留词顶上顶层组头 ⇒ 截图乱象（10-30 任务模式标注批的「闸关回落既有编排词」正是病灶入口）。

## 修法（产品代码两处，均在 bgAgents.js）

1. **`bgDelegateBadgeWord` 收口**：只认 `row.mode` ∈ {kernel, dispatcher}；缺席 ⇒ 返回 `''`（无徽标——无信息胜过错信息）。兜底链整体删除（orchestrationMode/kind/前缀启发不再上顶层）。
2. **顶层残留摘帽（bgTaskRowHtml 行内兜底）**：非 `childLevel` 行（组头 + 未被认领的 leftover/孤儿行）的 kind 词若为 `Delegate`/`Subagent` ⇒ 不渲染该 chip（「来自 …」孤儿角标、任务归属徽标不受影响）。子级行的 kind 词显示面不动（bgChildKindWord/bgKindDisplayWord 只在渲染点用，数据面零改动）。

## 取证（双端样张）

`tests/bg-badge-homecoming-shots.mjs`（新增，作者令双端截图取证）——样张矩阵 = 顶层三形态（kernel 批 / dispatcher 批 / 旧盘 mode 缺席批）× 子层两族词（store subagent 行 `Subagent`、agentflow flow-step 行 `AgentFlow`），桌面 1280（light+dark）+ 手机 375（dark）：

`evidence/20261031_bg-badge-homecoming__delegate-nebflow-7f69c44f/shots/{desktop-light,desktop-dark,mobile-375-dark}.png`

审计断言（三端全 PASS）：顶层无 Delegate/Subagent 字样；kernel 批顶层 = `kernel`；dispatcher 批顶层 = `dispatcher`；旧盘批顶层无徽标（不回退 Subagent）；子层 `Subagent`/`AgentFlow` 在场；零 console/pageerror。

## 回归

- `tests/delegate-ui-twolayer.spec.mjs`：夹具行补 `mode` 键；T2/T3 组头徽标断言改 kernel/dispatcher；新增 **T2b**（mode 缺席 + orchestrationMode 残留 ⇒ 顶层零徽标——裁定钉）。**同批存量红清欠**：行取数面选择器 `#bg-dropdown > .bg-dropdown-list:last-child` → `#bg-dropdown .bg-section-agents .bg-dropdown-list`（后台合并批 2026-10-23 ⑥ + 分区批 2026-10-29 ③ 后的真实 DOM；HEAD 对照实证 T2/T3 先于本批即红）。6/6 PASS。
- `tests/subagent-panel-merge-status.spec.mjs`：同族选择器重锚（HEAD 对照 7 failed 存量红清欠）。7/7 PASS。
- `tests/bg-device-origin-chip.spec.mjs`：同族选择器重锚（任务行落 `.bg-section-tasks`）。3/3 PASS。
- 全绿面：bg-merged-panel / bg-mode-badge / remote-bg-agents / mobile-p6-bgstatus / subagent-panel-open-geometry / delegate-panorama / taskbar-flow-overlap / mobile-v3（合计 34 PASS）。
- checkJs 门：344 < 基线 350，零新增（bgAgents.js 改动净减 6）。
- 存量红（HEAD 对照实证非本批，另挂欠账）：`tests/subagent-row-display-name-shots.mjs`、`tests/mobile-keybadge-shots.mjs`（独立 shots 脚本，面板 DOM 漂移遗留）。

## 边界裁定（本批明示不改）

- 远端投影行（`bgRemoteRowHtml`，台账 #929）保留 source kind 词（`remote-bg-agents` spec 钉契约）——跨设备子级投影行，无下钻层级可锚，display-only。
- store 子行 `subtask` source 的显示词 `Delegate`（修订批九点② 2026-10-09 更名）不动——子级行按实际类型标词，该词是子级身份的忠实呈现。
