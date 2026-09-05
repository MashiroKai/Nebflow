# 微信式中间过程收起 · 设计规格书

> 版本：v1.2（2026-08-21） · 状态：**冻结** · 任务：#346（用户需求 2026-08-20 21:21；v1.2 迭代裁定 2026-08-21 12:08）
> 定位：中等（渲染层 + 持久化读路径改动）——前端为主，后端**零改动**起步（可选 P1：补失败终态标记，见 §2.5 决策 D1）。实施方：nebflow-project Frontend。
> 上游规格：[thinking-display-openai-spec.md](thinking-display-openai-spec.md)（#345，进行中）——本规格覆盖并整合之，见 §2.6。

---

## 0 · 一句话目标

turn 成功完成（`done`）后，把该 turn 的全部中间过程（thinking 段、工具卡片、中间文本段）自动收进一个可展开的「过程组」，界面只留「用户消息 + 最终回复」的微信式一问一答；失败/中断 turn 保持展开供排障；收起内容不删除，点击可复展。

**与用户需求逐条对齐**（2026-08-20 21:21）：
| 用户原意 | 本规格落点 |
|---|---|
| 工作期间中间过程实时展示 | 流式渲染路径零改动（thinking/工具卡片照旧实时上屏），§3 |
| turn 成功后自动收起，只留一问一答 | `done` 触发归拢折叠，§4 状态机 T-done |
| 失败 turn 不收起 | `error`/`interrupted`/`maxTokens`/`timeout` 四类终态打 `.turn-failed`，永不自动收起，§4 |
| 收起不删除、可复展 | DOM 节点原样保留在组内（仅隐藏）；ui.json 持久化本就全量保留，历史重载按 turn 结果决定初始态，§3/§7 |

## 1 · 参考与依据

1. **用户需求**（最高优先级）：任务 #346 派发单（2026-08-20 21:21）——「微信式中间过程收起」，四条原意见 §0 表。
2. **微信聊天范式**（用户点名的最终形态）：对话流只呈现「我一句，你一句」的干净气泡序列，任何处理过程不占据常态视野（产品行为观察；https://weixin.qq.com/）。注意：微信本身无 agent 过程——本设计是「微信最终态 + agent 可观测性」的折中，过程实时可见、完成后收起。
3. **ChatGPT / Claude.ai 过程折叠范式**（同类功能的直接先例）：ChatGPT「Thought for N seconds」折叠条完成后默认收起、点击复展；Claude.ai 工具调用完成后收起为「N tool uses」摘要行。二者共性 = **完成态默认折叠 + 单行摘要 + 一键复展 + 内容不删除**，与本需求完全同构（产品行为观察）。
4. **Apple HIG · Progressive disclosure**：默认呈现最少信息，按需展开细节；折叠控件须有明确的展开/收起指示（https://developer.apple.com/design/human-interface-guidelines/progressive-disclosure）。
5. **visual-style 铁律**（`~/.nebflow/skills/nebflow/visual-style/SKILL.md`）：总基调「低调克制、专业感优先，复用既有设计语言不造新轮子」——摘要条走 muted 文本形态（同 #345 thinking 折叠条先例，§6），不引入玻璃质感、不造新色。
6. **#345 thinking 显示规格**（`~/.nebflow/docs/Nebflow/thinking-display-openai-spec.md`）：本规格与其关系见 §2.6——**整合并覆盖**：#345 管「段级」thinking 折叠条形态，#346 管「turn 级」过程成组收起，两层正交叠加。
7. **design-system 案例 001 教训**（`~/.nebflow/skills/design-system/SKILL.md`）：i18n key 先行（断言比对运行时 `t()` 输出）；状态语义显式到可执行；布局断言用增量口径。本规格 §9 全部遵守。

## 2 · 现状盘点与改动面

### 2.1 turn 生命周期信号（已存在，全部可复用）

前端 main.js 已有完整的 turn 终态处理分支（本设计的挂载点）：

| WS 事件 | 语义 | main.js 位置 | 现行为 |
|---|---|---|---|
| `done` | turn 成功完成 | main.js:732 | 清 busy、finishThinking/finishAi、存盘、渲染 duration-badge |
| `roundComplete` | 轮内文本定稿但 turn 未结束 | main.js:830 | finalize 当前气泡，turn 继续 |
| `error` | turn 失败 | main.js:861 | finishThinking/finishAi + `renderError`（红色 `.error-card`） |
| `interrupted` | 用户/系统中断 | main.js:894 | finishThinking/finishAi，静默 |
| `timeout` | 流超时 | main.js:918 | `renderTimeoutNotice`（error-card + 重试按钮） |
| `maxTokens` | 截断 | main.js:937 | `renderError('Max tokens…')` |

**结论：「成功 vs 失败」的判定权威在后端，信号已以终态事件形式送达前端**——本设计不需要新协议，只需在这些既有分支上挂「成组 + 折叠/保持」动作。

### 2.2 流式渲染现状（保持不动的部分）

- thinking：`appendThinkingDelta`/`finishThinking`（chat.js:2214-2305）——流式全文渲染，完成后 label 可点击展开（仅段级）。
- 工具卡片：`renderToolPending`（spinner）→ `renderTool`（完成态，chat.js:922-1061）；Pop/Mail/Card 工具有特殊卡型；`tool-stream-body` 流式参数预览。
- AI 文本：`appendAiText`/`finishAi`（chat.js:584-689）；多轮 turn 中 `roundComplete` 会把中间文本段 finalize 成独立 `.row.ai`。
- 等待占位：`.thinking-placeholder` + 趣味短语计时器。
- 所有行直接 `appendChild` 到 `view.dom.chat`，无 turn 级容器——**这是本设计要补的唯一结构缺口**。

### 2.3 持久化现状（历史重载的决定性约束）

- **ui.json 消息序列**（protocol.scala:240-296 `UiMessage`）：`User / Ai(text,durationMs,model,thinking) / Tool / Agent / AskUser / Ask / AskPermission / System`。
- 后端 `SessionRecorder.scala`：`done` → 追加最终 `Ai`（带 durationMs）或回填 durationMs 到末条 Ai（`updateLastAiMeta`）；`toolEnd` → 追加 `Tool`；**`error`/`interrupted`/`maxTokens` 不落任何终态标记**——失败 turn 在 ui.json 里与「写了一半的成功 turn」不可区分。
- 历史重载两条路径都逐条平铺渲染、无 turn 分组：`restoreFromStorage`（persistence.js:~280-470，localStorage 缓存）、`restoreFromBackendHistory`（persistence.js:570-，ui.json 权威源，会话切换/分页用）。

### 2.4 差距结论

| 需求点 | 现状 | 差距 |
|---|---|---|
| 工作中实时展示 | ✓ 已满足 | 无（流式路径零改动） |
| 成功 turn 自动收起 | ✗ 无 turn 级概念 | 新增：组容器 + done 触发折叠 |
| 失败 turn 保持展开 | ✗ | 新增：四类失败终态打标、跳过折叠 |
| 收起可复展 | △ 仅 thinking 段级可展开 | 升级为 turn 级摘要条 ⇄ 全组展开 |
| 历史重载正确初始态 | ✗ ui.json 无 turn 边界/结果 | P0 启发式推断（§2.5）；P1 可选后端终态标记 |

### 2.5 核心决策 D1 · 收起触发时机由谁判定

**推荐：前端判定（消费既有终态事件），后端零协议改动；可选 P1 增强 = 后端补显式终态标记。**

理由：
1. **折叠是呈现层状态，不是业务数据**。ui.json 是多端/历史搜索共用的回放源，「已收起」写进持久化会把视图态混入数据；且展开/收起是纯本地视图行为，不应产生写盘。
2. **判定所需的权威信号已存在**：turn 成功与否由后端裁决，且已通过 `done` / `error` / `interrupted` / `maxTokens` / `timeout` 五个终态事件送达前端（§2.1）——前端只是「执行」后端已做出的判定，不存在前端猜结果的问题。
3. **成本与风险不对称**：前端方案零协议改动、半天~一天量、不动数据层（与 #345「数据层零改动」同纪律）；后端标记方案需动 `UiMessage` schema + `SessionRecorder` + 存量会话无标记的降级处理，收益仅在历史重载的确定性。
4. **历史重载的缺口可用启发式兜底（P0）**：成功 turn 的末条 `Ai` 必有 `durationMs`（SessionRecorder 的 `done` 分支保证：要么直接带，要么 `updateLastAiMeta` 回填——SessionRecorder.scala:119-134）；失败 turn 无 `done` → 末条 `Ai` 无 `durationMs`。重载时按此推断初始折叠态（推断规则见 §7-E4）。该信号虽隐式，但由后端既有写盘逻辑保证，断言可锁（§9 A8）。
5. **P1 增强项（非阻断，留给 Manager 裁量）**：后端在 `SessionRecorder` 对 `error`/`interrupted`/`maxTokens` 落一条轻量终态标记（如 `UiMessage.System(i18nKey="turnFailed", params={reason})`），使历史重载从启发式升级为显式判定。本规格 §7-E4 同时给出有/无标记两种规则，实现向前兼容。

### 2.6 与 #345 thinking 显示规格的关系：整合并覆盖

- **#345 = 段级**（thinking 折叠条形态：进行态「思考中…」脉冲 → 完成态「已思考 N 秒」）。
- **#346（本规格）= turn 级**（done 后整个中间过程组收起为一行摘要）。
- 两层**正交叠加、不冲突**：turn 进行中 thinking 段按 #345 形态显示；turn 完成后整个组（含 #345 折叠条、工具卡、中间文本）被收进摘要条；复展后组内各 thinking 段回到 #345 形态。
- **排期依赖**：本规格不强依赖 #345 先行——基于现状 thinking 气泡也能成组。但若两者先后落地，建议 #345 先行或同批，避免 thinking 行样式两轮返工。本规格引用 thinking 行时以「#345 落地后的折叠条形态」为准、现状形态为降级兼容。
- **取代关系声明**：#345 §2.3「完成态默认折叠」仍然成立（段级）；本规格在其之上追加 turn 级整组折叠。旧「thinking 显示层方案」（本任务书所提）中被取代的部分 = 「thinking 段级折叠即终点」的定位——它降级为 turn 级折叠的组内成员。

## 3 · 布局与信息架构

### 3.1 DOM 结构（新增一层 turn 组容器）

```
.view.dom.chat
  ├─ .row.user                      ← 用户消息（不进组）
  ├─ .turn-group[data-turn-state="streaming|done|failed"]
  │    ├─ .turn-summary            ← 摘要条（done 态可见；failed 态元素不存在，A5）
  │    │    ├─ .turn-summary-icon  ← chevron（12px SVG，muted）
  │    │    └─ .turn-summary-text  ← 「✻ 短语，时长 · 模型 · 工具 n 次」摘要（i18n + 模型可见，v1.2）
  │    ├─ .turn-steps              ← 中间过程容器（折叠时整体隐藏，节点不删）
  │    │    ├─ .row.ai.thinking-row / .thinking-bubble   ← thinking 段（#345 形态）
  │    │    ├─ .row.tool / .tool-card                     ← 工具卡片（各型）
  │    │    └─ .row.ai（中间文本段，roundComplete finalize 产物）
  │    └─ (最终回复不在此：最终 AI 气泡保持在 .turn-steps 之外，见 3.2)
  ├─ .row.ai                        ← 最终回复气泡（常态可见，微信式「你一句」）
  └─ .row.error / .error-card       ← 失败终态提示（failed turn 保留）
```

### 3.2 关键结构决策

1. **最终回复不进组**：微信式终态 = 用户气泡 + 最终 AI 气泡并排可见。组只装「中间过程」：thinking 行、工具行、中间文本段（`roundComplete` 产生的非末轮 AI 段）。判定「中间 vs 最终」= 该 turn 内除最后一个 `.row.ai` 文本段外的所有过程行。
2. **done 时刻归拢**：`done` 处理器（main.js:732 既有分支）追加一步——把本 turn 的过程行移入 `.turn-steps`、插入摘要条、折叠。行是 DOM 移动（`appendChild` 重挂载），**不重建**，保留事件监听与滚动位置记忆。
3. **失败 turn 同样成组但不折叠**：`error`/`interrupted`/`timeout`/`maxTokens` 分支把过程行移入组、打 `data-turn-state="failed"` + `.turn-failed` 类，摘要条不显示（全程展开），error-card 保持在组下方。成组是为了复展/定位结构一致，不是为了收起。
4. **流式期间不建组**：thinking/工具行照常直接挂 `view.dom.chat`（零流式改动），仅在终态时一次性归拢——避免流式高频 append 被容器层拦截引入回归。
5. **token 纪律**：零新 color token；摘要条全部 `var(--color-text-muted)` / `var(--color-frame-border)` 等既有引用；chevron 内联 SVG（12px，currentColor）。
6. **单会话多 turn**：每个 turn 一个组；历史重载（§7-E4）按 ui.json 顺序重建组边界。

## 4 · 交互状态机

### 4.1 组级状态（`.turn-group[data-turn-state]`）

| 状态 | 进入条件 | 摘要条 | `.turn-steps` | 说明 |
|---|---|---|---|---|
| `streaming` | turn 进行中（thinking/工具行直接挂 chat，组尚未建） | — | — | 此态下 DOM 上其实无组；列出仅为语义完整 |
| `done` | `done` 事件处理完成归拢 | 显示（定格工作状态行形态，v1.1） | 隐藏 | 微信式终态 |
| `done-expanded` | 用户点击摘要条 | 显示，chevron 向下 | 可见 | 复展排障/追溯 |
| `failed` | `error`/`interrupted`/`timeout`/`maxTokens` | 不显示（永久） | 可见 | **无折叠入口**——失败 turn 不提供一键收起（刻意决策：排障优先；若后续用户要求可加手动收起按钮，属 P2） |
| `failed-expanded` | —（即 failed 本体） | — | — | failed 无 ⇄ 切换，保留单态 |

### 4.2 转移表

| 当前 | 触发 | 目标 | 动作 |
|---|---|---|---|
| streaming | `done` | done | 归拢过程行入组 → 建摘要条（定格状态行短语/时长 + 工具次数）→ **立即折叠（同步，无 linger 延迟——用户裁定 v1.1）** → 若视口贴底则保持贴底 |
| streaming | `error`/`interrupted`/`timeout`/`maxTokens` | failed | 归拢过程行入组 → 打 `.turn-failed` → 保持展开 → error-card 追加在组后 |
| done | 点击摘要条 / Enter / Space | done-expanded | `.turn-steps` 显示，chevron 旋转，`aria-expanded=true` |
| done-expanded | 点击摘要条 | done | 反向隐藏 |
| done / done-expanded | 页面刷新 / 会话切换重载 | done（默认折叠） | 历史重载统一 done 态初始折叠（§7-E4）；不记忆用户手动展开态（刻意：跨会话一致性优先） |
| failed | 页面刷新 / 会话切换重载 | failed（展开） | 启发式判定为失败 → 展开（§7-E4） |

### 4.3 显式语义锁定（案例 001 教训②）

- **归拢范围**：从「触发本 turn 的用户行之后」到「终态事件时刻」之间的所有 `.row.ai.thinking-row`、`.row.tool`、`.row.card-content`、非最终 `.row.ai` 文本段、`.thinking-placeholder`（done 时已被 finishAi 清理，防御性兜底）。
- **「最终 AI 段」判定**：归拢时刻 DOM 中最后一个含非空文本的 `.row.ai`（不含 thinking-row）。零文本 turn（纯 thinking 响应，main.js:805-816 现有分支）→ 该 thinking 行本身即「最终产物」，**不进组、不折叠**（用户唯一可读内容消失是严重回归）。
- **摘要统计（v1.1 用户补充：与工作状态行结合 + 调用工具数量）**：摘要条 = **定格的工作状态行**——`✻ {诗意短语}，漂了 {duration}`（短语取 done 时刻状态行正在显示的短语，同一短语池，随 locale）后接 ` · 工具 {n} 次`（n = 组内 `.tool-card` 数，i18n key `chat.turnSummaryTools`，en 单数 `1 tool call`）；时长 = durationMs 复用 `formatDuration`（chat.js:695）。步数（工具卡数 + thinking 段数）保留为展开态统计口径。
- **模型名进摘要行（v1.2 用户裁定 2026-08-21 12:08）**：模型名（如 `zhipu/GLM-5.3`）从 v1.1 的 `title` tooltip **升级为摘要行可见文字**——收起态一眼可见本 turn 用的模型，无需悬停。排布由实施定（如 `✻ {短语}，{duration} · {model} · 工具 {n} 次` 或副段），断言口径=摘要行 textContent 含模型名。thinking 摘要短语（✻ 行）已在 v1.1 摘要形态内，不变。
- **气泡 footer 统一二项（v1.2 用户裁定 2026-08-21 12:08）**：AI 消息气泡下方 footer 统一为**仅时间 + 复制按钮**——模型名标签等其他元数据全部移出 footer（模型名的展示位归 turn 摘要行，见上条）。适用于所有 AI 气泡（不限收起 turn）。
- **多 turn 交叠**：WS 层 busySessionIds 已保证单会话串行 turn，无并发 turn 归拢竞态；弹窗 ChatView（flow/bg-agent）同一渲染路径自动生效（view 隔离既有机制）。
- **手动展开后同页再次 done 同 turn**：不存在（turn 一次性）。用户手动展开后**不做自动再收起**——尊重用户意图。

## 5 · 动效规范

总基调：**克制、不花哨**（用户偏好，visual-style 铁律 6）。全部动效只做「状态可感知」所需的最小量，不做装饰性动画。

| 动效 | 触发 | 参数 | reduced-motion 处理 |
|---|---|---|---|
| 归拢折叠（done） | `done` 归拢完成瞬间 | **无位移动画**：过程行直接隐藏、摘要条直接出现。不做「行飞进组」之类位移（花哨 + 高频长 turn 性能风险） | — |
| 摘要条入场 | 同上 | opacity 0→1，150ms ease-out（仅此一处轻量过渡，提示「有新状态」） | `prefers-reduced-motion: reduce` 时 duration ≤0.01s（案例 001 A10 先例口径） |
| chevron 旋转 | 展开 ⇄ 收起 | transform rotate(0→90deg)，150ms ease | 保留或同禁用（非位移，克制场景可接受） |
| 展开/收起内容 | 点击摘要条 | **无高度动画**：`.turn-steps` 直接显隐（display），不做 max-height 过渡——内容高度不可预测（长 turn 数百行），高度动画需测量且易抖。与 #345 段级「无高度动画」决策一致 | — |
| 失败 turn | `error` 等 | 无动效，error-card 沿用既有 fadeIn（chat.css:535 现状） | 现状已适配则不动 |
| 流式期间 | — | **零新增动效**：thinking/工具行的现有动画（spinner、cursor、脉冲）全部不动 | 现状 |

**刻意不做的**：行位移动画、弹性回弹、摘要条 shimmer/流光、折叠时的内容渐隐——均属花哨，违背「低调克制专业感」。

## 6 · 视觉规格

摘要条（`.turn-summary`）是唯一新增视觉元素，走 muted 文本形态（与 #345 thinking 折叠条同一设计语言，visual-style 铁律 6「不造新轮子」）。**非可交互控件类**（区别于按钮/输入框），不引入玻璃质感（铁律 2 适用范围不含信息展示条——同 #345 §6 决策）。

| 项 | 值 | 依据 |
|---|---|---|
| 摘要条背景 | `transparent` | 克制；与 thinking 折叠条一致 |
| 摘要条布局 | 左对齐，与 `.row.ai` 同侧；单行，max-width 85%（同 tool-card） | 视觉归属于 AI 侧 |
| 文案字号/字重 | 12px / 500 | #345 折叠条同规格（visual-style 字重 B 档） |
| 文案颜色 | `var(--color-text-muted)`，常态 opacity 0.55 / hover 0.8 | #345 同值；hover 提亮给出可点击感知 |
| chevron | 12px 内联 SVG，`currentColor` 跟随文案 | 不引图标库 |
| 文案内容 | 定格状态行形态：`✻ {短语}，{duration} · {model} · 工具 {n} 次`；短语池复用状态行（既有 i18n）；`工具 {n} 次` 走 `chat.turnSummaryTools`；**模型名为可见文字（v1.2 裁定，取代 v1.1 tooltip）**、时间戳进 title tooltip。0 工具 + 0 thinking 段不建组无摘要条（不变） | 与工作期状态行视觉连续（用户补充）；i18n key 先行（案例 001 教训①） |
| hover 底 | 无（透明条 + 文案/chevron 提亮即反馈） | 克制；不引入 hover 底色避免与 tool-card hover 语义混淆 |
| focus ring | 键盘聚焦时 `outline` 用既有 focus 样式（见 §8） | — |
| 失败 turn | 无摘要条；过程行样式零改动；error-card 现状（chat.css:1129 红色系）保留 | 失败态不新增视觉 |
| 暗色主题 | 全部引用既有 token，暗色自动适配；无硬编码色值 | token 纪律 |

**与 #345 的视觉一致性检查**：若 #345 先行落地，摘要条与段级折叠条的字号/字重/opacity/hover 参数必须逐项一致（同规格值），避免两条折叠条视觉打架。

## 7 · 边界与异常

- **E1 · 超长 turn（数十工具调用 + 多轮 thinking）**：归拢是一次性 DOM 移动（终态时刻单帧），不在流式路径上，无累积开销。摘要条文案恒定单行（步数/时长是短数字）；展开态 `.turn-steps` 内容多时撑高页面正常滚动，不设组内 maxHeight（避免「框中滚」套娃；既有 tool-body 内部滚动保持不变）。
- **E2 · 流式中断**（用户点停止 / 断网 / 后端 interrupted）：走 `interrupted` 分支 → failed 态保持展开，**已渲染的部分过程原样保留**（这是排障价值所在）；未完成 tool-card（`tool-card--pending`）现有逻辑已 remove（main.js:880-883），保持。断网重连后由历史重载（E4）恢复 failed 展开态。
- **E3 · 多条工具调用**：单轮多工具（一个 LLM 响应发起 N 个 tool call）→ N 张卡片全进组，摘要步数计 N；跨轮工具（多轮 turn）同样全进组。摘要只给总数不列明细（明细在展开态）。**并发工具乱序完成不影响**：归拢发生在终态，届时所有行已就位。
- **E4 · 历史重载初始态推断**（P0 启发式 + P1 显式标记兼容）：`restoreFromBackendHistory`/`restoreFromStorage` 按 ui.json 序列重建 turn 组，规则按「用户消息」切分 turn 边界（`UiMessage.User`——含 `injected=true` 的注入消息，它们同样可触发 turn——到下一个 `User` 之间为一个 turn），每 turn 结果判定：
  1. **P1（若有后端标记）**：turn 段内存在 `System(i18nKey="turnFailed")` → failed 展开；否则 done 折叠。
  2. **P0（无标记，当前）**：turn 段末条 `Ai` 有 `durationMs`（或本段含 durationMs>0 的 Ai）→ 判为成功 → done 折叠；无 `durationMs` 且段内有 Tool/中间内容 → 判为失败/未完成 → failed 展开。
  3. **兜底**：判定不确定时一律展开（宁可多显示过程，不可丢失可观测性——与用户需求「历史可追溯」对齐）。
  4. **存量会话**（本特性上线前的历史）：同一规则自然适用（durationMs 一直在写）；不做数据迁移。
  5. 已知误差（接受并记录）：`maxTokens` 截断 turn 的末轮 Ai 可能已被 SessionRecorder 在 done 前 flush 但无 durationMs 回填 → 正确判为失败；极罕见的「done 但 flush 竞态丢 durationMs」→ 误判为失败展开——误差方向是「多展开」，符合兜底原则。
- **E5 · 零中间过程 turn**（直接回复无工具无 thinking）：无过程行 → 不建组、不出现摘要条，界面与现状完全一致（零回归面）。
- **E6 · 纯 thinking 无文本响应**（main.js:805-816 既有分支）：thinking 即最终产物，不进组不折叠（§4.3 已锁）。
- **E7 · AskUser / askPermission 交互轮**：交互行（`.option-box` / permission prompt）**不进组**——它们是用户参与的节点不是 agent 内部过程；done 归拢时跳过这些行。回答完成后 turn 继续，最终 done 只收工具/thinking。
- **E8 · 注入消息 / Team / agent 子消息**：`buildInjectedRow` 产物（蓝色气泡）与 bg-agent 指示行不进组（非本 turn agent 过程）。
- **E9 · 窄视口 375px**：摘要条单行截断（`text-overflow: ellipsis`）不折行；展开态内容正常换行。增量断言口径同案例 001 教训③（只断言特性引入的溢出，不背既有面板本底）。
- **E10 · 消息搜索定位**（与已上线 message-search 的交互）：搜索命中组内过程行时，先自动展开对应组再滚动定位（否则命中行 display:none 无法聚焦）。Flash 高亮沿用既有。
- **E11 · 弹窗 ChatView**（flow/bg-agent/team popup / #343 子窗口）：**主窗口 + 弹窗同为 P0 交付范围（v1.1 用户裁定「主窗口 + 弹窗都收起」）**——同一渲染路径生效，弹窗内 turn 同样成组收起。popup CSS 若有 chat 样式覆盖需同步摘要条样式（Frontend 检查项，同 #345 §7）。
- **E12 · 撤回/删除消息**（deleteLastUserMessage）：撤回只删用户行，组不单独处理（其 turn 若无 Ai 回复则本无组）。
- **E13 · 多语言**：摘要文案全部 i18n key；步数 1 时用单数 key（`chat.turnSummaryOne`「已完成 · 1 步 · {duration}」）避免「1 步」复数瑕疵（en: `Done · 1 step · {duration}`）。

## 8 · 无障碍

- **摘要条是 `<div>`**：补 `role="button"` + `tabindex="0"` + Enter/Space 触发切换（与 #345 §8 对 thinking label 的要求同批，两处实现应共享一个 keyboard-activatable 工具函数）。
- **`aria-expanded`** 随展开态同步（true/false 二值，供读屏与断言共用）。
- **`aria-controls`** 指向 `.turn-steps` 的 id（每组唯一 id，如 `turn-steps-<sessionId>-<turnIndex>`）。
- **组隐藏用 `display:none`**（不用 `visibility:hidden`）——隐藏内容自然退出读屏/tab 序，无需额外 `aria-hidden` 管理；但实现若用其他隐藏手段则必须同步 `aria-hidden="true"`。
- **对比度**：摘要文案 muted 色 opacity 0.55 对页面底亮/暗主题均需 ≥4.5:1（与 #345 同检查项；qa 抽测亮暗各一，断言见 §9）。**〔v1.1 实施勘误 2026-08-21〕**：muted@0.55 与 A13 的 4.5:1 存在数学矛盾（muted@0.55 达不到双主题 4.5:1）——二元断言优先，实施取 `--color-text-dim`（亮 4.72:1 / 暗 6.90:1，@3831892e）；muted@0.55 保留给 #345 thinking label（其 §6 非 a11y 门项）。
- **非鼠标可达**：键盘 Tab 可达摘要条 → Enter/Space 展开 → 展开后组内工具卡的既有点击展开（tool body）同样键盘可达（现状 tool-card 无键盘支持属既有缺口，不在本特性范围，记录不背）。
- **不做 `aria-live`**：折叠状态变化非关键通告，加 live region 会在长 turn 场景产生噪音。
- **reduced-motion**：摘要条入场 opacity 过渡与 chevron 旋转在 `prefers-reduced-motion: reduce` 下 ≤0.01s（同案例 001 A10 口径）。

## 9 · 可断言验收点（qa-frontend 转 Playwright）

口径纪律（案例 001 教训）：文案断言比对运行时 `t()` 输出不硬编码字面；状态语义在 §4.3 已锁；布局断言用增量口径。

| # | 断言（二值） | 截图 |
|---|---|---|
| A1 | 含 ≥1 次工具调用的 turn 收到 `done` 后 ≤1s：存在 `.turn-group[data-turn-state="done"]`，其 `.turn-steps` 不可见（computed display === 'none' 或等效隐藏），且该组 `.turn-summary` 可见 | 是（完成态折叠） |
| A2 | 摘要条文案结构断言（v1.2 口径）：以 `✻` 开头；含运行时 `t('chat.turnSummaryTools', {n})` 输出，n === 组内 `.tool-card` 数（DOM 计数反算，不猜数字）；含 `formatDuration(durationMs)` 输出；**textContent 含本 turn 模型名**（可见文字，非 tooltip——v1.2 裁定） | 是 |
| A3 | 最终回复可见性：done 后含非空文本的最终 `.row.ai`（非 thinking-row）**不在** `.turn-steps` 内且可见；同 turn 的 `.row.tool` 全部在 `.turn-steps` 内 | 否 |
| A4 | 点击摘要条 → `[data-turn-state]` 组 `.turn-steps` 变可见且摘要条 `aria-expanded === "true"`；再点 → 反向。键盘 Enter 触发等价 | 是（展开态） |
| A5 | 失败 turn（mock `error` 终态）：过程行保持可见（无 `.turn-steps` 隐藏），该组 `data-turn-state === "failed"` 且 `.turn-summary` 不存在；`interrupted`/`maxTokens` 各断言一次同型 | 是（失败态展开） |
| A6 | 零过程 turn（纯文本直接回复）：done 后 DOM 新增 `.turn-group` 数 === 0（零回归面锁定） | 否 |
| A7 | 纯 thinking 无文本响应：done 后该 thinking 行可见且不在任何 `.turn-steps` 内 | 否 |
| A8 | 历史重载：成功 turn（末条 Ai 有 durationMs）重载后初始折叠；构造的无 durationMs 失败 turn 重载后初始展开（E4 规则锁定；若 P1 标记已实现则断言走标记分支） | 是（重载态） |
| A9 | 多工具 turn（单轮 ≥3 工具 mock）：done 后组内 `.tool-card` 计数 === 3 且 DOM 顺序 === 到达顺序 | 是 |
| A10 | 超长 turn（≥10 轮工具循环 mock）：done 归拢后主线程无长任务阻塞（归拢单帧完成，以 performance 计时 ≤50ms 为通过线）且摘要条单行（无折行，scrollHeight ≤ 单行阈值） | 否 |
| A11 | 消息搜索命中组内行：触发定位后对应 `.turn-group` 自动展开（`.turn-steps` 可见）后命中行进入视口 | 是 |
| A12 | `prefers-reduced-motion: reduce` 下摘要条入场过渡与 chevron 旋转 computed transition/animation-duration ≤0.01s | 否 |
| A13 | 摘要条对比度：亮/暗主题下 `.turn-summary-text` computed color 对页面背景对比度 ≥4.5:1（各主题一次） | 否 |
| A14 | 375px 窄视口增量口径：特性引入后无新增横向溢出（断言 = 「特性开启态 chat 区 scrollWidth === 特性关闭态基线」且 `.turn-group` 子树自身无溢出；案例 001 教训③口径） | 否 |
| A15 | 回归锚点：流式期间（done 前）thinking 行与工具卡实时可见（`.turn-group` 尚不存在）——锁定「流式路径零改动」承诺 | 是（流式态） |
| A16 | 摘要条工具计数：文案中数字 === 组内 `.tool-card` 计数（≥3 工具 mock 场景，DOM 反算） | 否 |
| A17 | 弹窗 ChatView（team/flow popup）内含工具 turn done 后同样折叠：弹窗内存在 `.turn-group[data-turn-state="done"]` 且其 `.turn-steps` 隐藏（v1.1 主窗口+弹窗同范围） | 是（弹窗态） |
| A18 | 气泡 footer 二项统一（v1.2 裁定）：AI 消息气泡 footer computed 可见交互/文本元素仅**时间 + 复制按钮**二项；模型名不出现在任何气泡 footer（其位在 turn 摘要行，A2） | 是 |

## 10 · 待确认决策（冻结前需 Manager/用户点头）

- **D1**：收起判定 = 前端消费终态事件（§2.5 推荐）。若 Manager/用户要求历史重载零启发式误差，则升级为后端 P1 标记（工作量追加约半天，规格已兼容）。
- **D2**：failed turn 无手动收起入口（§4.1 刻意决策）。若用户希望失败 turn 也可手动收起，P2 加一个组内收起按钮。
- **D3**：摘要文案形态「已完成 · N 步 · 时长」为推荐案；若用户偏好更短（如仅 chevron + 「过程」）或更长（列工具名），冻结前裁定——文案走 i18n key，改动成本低。
- **D4**：与 #345 的排期：同批 or #345 先行（§2.6）。两者独立可实施，同批可省一轮 thinking 行样式返工。
- **D5**：历史重载 turn 边界用 `User`（含注入）切分——若未来出现「无用户消息的自动 turn」（如 scheduled task 直接发起），需补边界规则，届时扩展 E4。

## 11 · 参考链接

- 用户需求原文：任务 #346 派发单（2026-08-20 21:21）
- 微信聊天范式：https://weixin.qq.com/（产品行为观察）
- ChatGPT thinking UI / Claude.ai tool-use 折叠：产品行为观察（同类功能先例）
- Apple HIG · Progressive disclosure：https://developer.apple.com/design/human-interface-guidelines/progressive-disclosure
- visual-style 铁律：`~/.nebflow/skills/nebflow/visual-style/SKILL.md`
- design-system 案例库（案例 001 教训）：`~/.nebflow/skills/design-system/SKILL.md`
- #345 thinking 显示规格（段级，被整合）：`~/.nebflow/docs/Nebflow/thinking-display-openai-spec.md`
- 代码锚点（现状基线）：`chat.js`（appendThinkingDelta:2214 / renderTool:922 / finishAi:629）、`main.js`（done:732 / roundComplete:830 / error:861 / interrupted:894 / timeout:918 / maxTokens:937）、`persistence.js`（restoreFromBackendHistory:570）、`css/chat.css`（thinking:1708 / tool-card:924）、`SessionRecorder.scala`（done/toolEnd 落盘 + updateLastAiMeta 回填）、`protocol.scala:240-296`（UiMessage schema）

---

## 变更日志

- v1.0（2026-08-20）：初稿。核心决策 D1（前端判定收起时机）待确认。
- v1.0 冻结（2026-08-20 23:55，Nebula 终审）：D1-D5 全按推荐案裁定——D1 前端判定（后端 P1 标记留作未来选项）/ D2 failed turn 无手动收起（P2 可加）/ D3 摘要文案「已完成 · N 步 · 时长」走 i18n / D4 与 #345 同批实施 / D5 User 切分+未来 E4 扩展。用户可在实施前推翻任一裁定。
- v1.1（2026-08-21 00:15，用户确认可实施 + 三点补充）：① 摘要条与工作状态行结合（定格 `✻ 短语，漂了 {duration}` 形态）并补充调用工具数量（模型/时间戳进 tooltip）——取代 v1.0 D3 文案 ② done 后**立即收起**（同步无 linger）③ 交付范围明确为**主窗口 + 弹窗都收起**（E11 升级 P0 范围）。断言 A2 改口径、新增 A16/A17。
- v1.2（2026-08-21 12:08，用户迭代裁定）：① 模型名收进摘要行可见文字（原 v1.1 tooltip 升级）② 气泡 footer 统一仅时间+复制二项，模型名等其他元数据移出 footer。断言 A2 改口径、新增 A18。
- v1.2.1（2026-08-21，实施勘误）：§6 文案内容行与 §3.1 结构注释对齐 v1.2 口径（模型可见文字、failed 态摘要条元素不存在）；footer 元数据改走 badge dataset（data-nf-phrase/data-nf-model）供 live 摘要与 E4 历史重建取数。
