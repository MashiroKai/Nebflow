# Thinking 显示统一 OpenAI 范式 · 设计规格书

> 版本：v1.0（2026-08-20） · 状态：**draft · 待确认冻结** · 任务：#345 · 排程：Token 看板（#310）之后
> 用户裁定（2026-08-20 21:13）：「思考模式改为 OpenAI 的为显示选择。把 Anthropic 的放到后台，根据 OpenAI 的模式来做对应。」
> 定位：轻量（半天量）——渲染层改动，**数据层零改动**（依据见 §2.4）。实施方：nebflow-project Frontend。

---

## 0 · 一句话目标

thinking 显示统一为 ChatGPT 形态：进行态折叠条「思考中…」脉冲 → 完成态「已思考 N 秒」可展开；各 provider thinking 数据经既有归一链路只呈现这一种 UI。

## 1 · 参考与依据

1. **用户裁定**（最高优先级）：前端 thinking 显示统一 OpenAI 风格；Anthropic 原生 thinking 后台化（数据层照常处理，不上前端）。
2. **OpenAI 官方 · Reasoning models guide**（https://platform.openai.com/docs/guides/reasoning）：raw reasoning 不暴露给用户/API；产品以 **reasoning summary** 呈现；chat/completions 请求侧只有 `reasoning_effort`，响应不回传 reasoning 明文（o-series）。
3. **ChatGPT 产品 UI 行为观察**（用户指定范式的直接来源）：折叠条单行——进行态「Thinking…」+ shimmer 脉冲；完成态「Thought for 12 seconds / 2 minutes」；点击展开显示思考过程（流式期间可展开实时滚动）；默认折叠，完成时未手动展开则保持折叠；工具调用交错的多个 thinking 段各自独立折叠条。
4. **visual-style 铁律**（`~/.nebflow/skills/nebflow/visual-style/SKILL.md`）：克制专业感、中度字重、复用既有设计语言不造新轮子。思考折叠条**非可交互控件类**（按钮/输入），沿用现有 muted 文本形态，不引入玻璃质感。
5. **design-system 案例 001 教训**：i18n key 先行（断言比对运行时 locale 输出，不硬编码字面）；状态语义显式到可执行。
6. **现状代码锚点**：`chat.js:2214-2304`（appendThinkingDelta/finishThinking）、`main.js:404-429/472-492`（thinkingDelta/thinking 事件）、`persistence.js:108-120/324-334`（历史渲染+截断）、`css/chat.css:1708-1760`（thinking 样式）、`AgentCore.scala:1294-1320`（WS 帧）、`#327 spec`（`subagent-thinking-bubble-spec.md`，数据链路史）。

## 2 · 现状盘点与映射表

### 2.1 数据链路现状（结论：归一已完成，前端只见一种事件）

所有 provider 的 thinking 在 **adapter 层**已归一为 `StreamChunk.ThinkingDelta(text)`：

| Provider / 协议 | 原始字段 | 归一路径 | 到达前端 |
|---|---|---|---|
| Anthropic 原生 | SSE `thinking_delta`（content block type=thinking） | `AnthropicAdapter.scala:379-381` → ThinkingDelta | WS `thinkingDelta{sessionId, delta}` |
| Anthropic signature | `signature_delta` / block `signature` | → `ThinkingSignature`，**仅内部 passback 用**（`AnthropicAdapter.scala:393-411`），从不进 WS | **不到前端**（后台化已天然满足） |
| DeepSeek（Anthropic 兼容） | thinking block（signature 可空） | 同上两行 | 同 thinkingDelta |
| DeepSeek（OpenAI 兼容） | `choices[].delta.reasoning_content` | `OpenAiAdapter.scala:381-387` → ThinkingDelta | 同上 |
| GLM（OpenAI 兼容） | `choices[].delta.thinking` | 同上 | 同上 |
| OpenAI o-series（chat/completions 现状） | 请求 `reasoning_effort`；**响应无 reasoning 明文**（官方文档 §1.2） | 无 ThinkingDelta 产生 | **无 thinking 帧 → 无折叠条**（即降级=「无 thinking」一致行为） |
| OpenAI Responses API（未来） | `reasoning.summary` | 未接入 | 预留：summary 文本按 ThinkingDelta 同路径处理即可，UI 零改 |

后端汇合点 `AgentCore.scala:1294-1320`：主会话与子 agent（`agentThinking` 带 `delta`+`nodeSessionId`，ws.js:84-85 转标准 `thinkingDelta`）**统一发 `thinkingDelta{sessionId, delta}`**。前端 main.js:404 只消费这一种。

### 2.2 渲染现状（chat.js / persistence.js）

- **进行态**：`appendThinkingDelta` 建立气泡后**全文流式渲染**（rAF + markdown + cursor），完成时才折叠——与 OpenAI 相反（OpenAI 进行态默认折叠）。
- **完成态**：`finishThinking` 收起 content、label 变 `collapsible` 可点击展开/收起 ✓（已有）；label 文本静态「思考过程」（`chat.thinkingLabel`），**无时长**。
- **turn 等待态**：`thinking` 事件建 `.thinking-placeholder` + 趣味短语计时器（i18n `think.0-18` 宇宙学短语，19 条）。
- **历史重载**：persistence.js:324-334 渲染 thinking-done 折叠气泡 ✓；thinking 保存时 >2000 字符截断（persistence.js:108-120，保留）；**ui.json 无 thinking 时长字段**。
- **多轮**：工具轮间 thinking 各自独立气泡 ✓（chat.js:2217-2226 同轮 text 后 thinking 不建 DOM 仅入缓冲，保留）。

### 2.3 Provider thinking 数据 → 统一 UI 映射表

| 数据情形 | UI 行为（唯一形态） |
|---|---|
| thinkingDelta 流式到达中 | 折叠条「思考中…」+ 三点脉冲；点击可展开实时观看流式内容（展开态持续 rAF 渲染） |
| 该段 thinking 结束（首个 textDelta / textDone / 工具轮切换触发 finishThinking） | 折叠条转完成态「已思考 N 秒」（时长=该段首个 delta 到 finishThinking）；流式期间用户手动展开过则保持展开 |
| 无任何 thinking 数据（含 OpenAI o-series 经 chat/completions） | 不出现折叠条（与普通消息无差别） |
| thinking 内容为空串/纯空白 | 不出现折叠条 |
| 流中断/超时（resetStreamTimeout 触发 renderTimeoutNotice） | 未完成折叠条按已流逝时长定格为完成态，杜绝永久「思考中…」 |
| 历史消息有 thinking 文本 | 折叠条完成态；无时长数据 → 降级「已思考」（无假数字） |
| signature / reasoning_effort / budget_tokens | 一律不上前端（后台数据层照常） |

### 2.4 差距结论（轻量依据）

数据层**零改动**（归一在 adapter 层早已完成，signature 本就不到前端）。改动集中三处：`chat.js`（进行态默认折叠+时长记录+label 更新）、`persistence.js`（历史降级文案）、`css/chat.css` + i18n（label 样式与新 key）。**后端/adapter/WS 协议不碰。**

## 3 · 布局与信息架构

DOM 结构完全复用现有（零新结构）：

```
.row.ai.thinking-row > .bubble.ai.thinking-bubble[.thinking-done]
  > .thinking-label[.collapsible|.expanded]   ← 「思考中…」/「已思考 N 秒」+ chevron
  > .thinking-content                          ← 展开时可见（markdown 渲染，沿用 renderMarkdownWithMath）
```

- 折叠条占据独立消息行，位于文本气泡**之前**（现状顺序保留）；多段 thinking 依到达顺序堆叠，不编号。
- 与工具消息交错：thinking 折叠条 → 工具卡片 → 下一轮 thinking 折叠条（现状顺序逻辑不动）。
- token 纪律：零新 token，全部 `var(--color-text-muted)` 等既有引用；chevron 用内联 SVG（12px，muted 色），不引图标库。

## 4 · 交互状态机

| 状态 | label 文案（i18n key） | content | 转移 |
|---|---|---|---|
| streaming-collapsed（默认） | `chat.thinkingInProgress`「思考中…」+ 脉冲点 | 隐藏（不渲染进 DOM 或 display:none） | 点击 label → streaming-expanded |
| streaming-expanded | 同上 | 可见，流式 rAF 渲染+cursor | 点击 → streaming-collapsed；finishThinking → done-expanded |
| done-collapsed（默认） | `chat.thoughtSeconds`「已思考 {n} 秒」/ `chat.thoughtMinutes`「已思考 {m} 分钟」 | 隐藏 | 点击 → done-expanded |
| done-expanded | 同上 | 可见，最终 markdown（无 cursor） | 点击 → done-collapsed |
| done-degraded（历史，无时长） | `chat.thought`「已思考」 | 同上 | 同 done-* |
| absent（无 thinking） | — | — | 不建 DOM |

时长取值：段内首个 thinkingDelta 时间戳 → finishThinking 时间戳（前端计时，逐段独立）。格式：<2s「思考了片刻」`chat.thoughtMoment`；2-59s `{n}` 秒；≥60s 取整分钟 `{m}` 分钟（不复用整轮 durationMs——语义不同，禁混用）。

**待确认决策 D6**：趣味短语计时器（`think.0-18`，19 条宇宙学文案）退役，turn 等待 placeholder 统一「思考中…」+ 三点脉冲。属用户可见特性移除，按裁定「统一 OpenAI 显示」推断为是，冻结前需 Manager/用户点头（key 保留在 locale 文件不删，仅停引用）。

## 5 · 动效规范

| 动效 | 触发 | 参数 | reduced-motion |
|---|---|---|---|
| 三点脉冲（thinking-dot ×3） | streaming 态 label 右侧 | 沿用现值：1.2s ease-in-out infinite，delay 0/0.15/0.3s | `prefers-reduced-motion: reduce` 时 animation-duration ≤0.01s（案例 001 A10 先例） |
| chevron 旋转 | expanded 切换 | transform rotate(90deg)，transition 0.15s ease | 保留（非位移动效，可接受；同禁用亦可） |
| 完成态 label 切换 | finishThinking | 文案直接替换，无额外动画（克制） | — |
| 展开收起 | label 点击 | 无高度动画，直接显隐（现状一致，避免内容高度测量开销） | — |

## 6 · 视觉规格（visual-style 适配）

| 项 | 值 | 依据 |
|---|---|---|
| 折叠条背景 | `transparent`（现状） | 非交互控件，不引入玻璃质感；克制 |
| label 字号/字重 | 12px / **500** | OpenAI 句式小字；visual-style 字重 B（按钮 500 档）；**去掉现 uppercase + letter-spacing 0.4px**（ChatGPT 为句式大小写，中文无大写，UPPERCASE 使中英混排突兀） |
| label 颜色 | `var(--color-text-muted)`，opacity 0.55 / hover 0.8 | 现值微调（0.45→0.55 提升可点击感知），不引新色 |
| chevron | 12px 内联 SVG，currentColor | muted 跟随 label |
| content 字号/行高/颜色 | 13px / 1.5 / `var(--color-text-muted)`（现状） | 保留 |
| 脉冲点 | 3×3px，muted | 沿用 thinking-dot 现值 |

## 7 · 边界与异常

- **空 thinking**：delta 全空白 → 不建折叠条（appendThinkingDelta 建立前 trim 检查）。
- **超长文本**：持久化截断 2000 字符（现状保留）；展开态内容区自身滚动，不撑破气泡（现状 pre/p 样式保留）。
- **多轮**：≥2 段 thinking 各自折叠条、各自时长；同轮 text 后的 thinking 不建 DOM 仅入缓冲（现状保留）。
- **中断/超时**：见 §2.3 行 5——未完成段定格完成态。
- **窄视口 375px**：折叠条单行不换行（时长文案短）；展开内容正常换行。
- **子 agent 弹窗**（#327 已打通链路）：同一渲染路径自动生效，样式同步（popup CSS 若有覆盖需同步 label 样式——Frontend 检查 bgAgentPopup/flowAgentPopup 引用）。
- **多语言**：全部走 i18n key；en「Thinking… / Thought for {n}s / Thought for {m}m / Thought」。

## 8 · 无障碍

- label 为 `<div>` + click：补 `role="button"` + `tabindex="0"` + Enter/Space 触发（现有 collapsible 无键盘支持——增量修复）。
- `aria-expanded` 随 expanded 同步。
- 折叠/展开内容用 `aria-live="polite"` 不必加（非关键通告，避免流式噪音）。
- 对比度：muted 文本对气泡底 ≥4.5:1（现有主题已达标；qa 抽查亮/暗各一）。

## 9 · 可断言验收点（qa-frontend 转 Playwright）

| # | 断言（二值） | 截图 |
|---|---|---|
| A1 | thinking 流式期间，`.thinking-bubble` 存在且 `.thinking-content` 不可见（折叠条形态，非全文展开） | 是（进行态） |
| A2 | 进行态 label 文本 === 运行时 locale 的 `t('chat.thinkingInProgress')` 输出（zh：「思考中…」） | 是 |
| A3 | finishThinking 后 ≤1s，label 匹配 `/已思考 (\d+ ?(秒|分钟)|思考了片刻)/`（时长为正） | 是（完成态） |
| A4 | 无 thinking 数据的 AI 消息，DOM 中不存在 `.thinking-row` | 否 |
| A5 | 点击完成态 label → `.expanded` class 加入且 content 可见；再点 → 移除且不可见 | 否 |
| A6 | 历史重载（重开会话）：有 thinking 的消息渲染折叠条且 label === `t('chat.thought')`（无数字）；无 thinking 消息无 `.thinking-row` | 是 |
| A7 | `prefers-reduced-motion: reduce` 下 `.thinking-dot` computed animation-duration ≤0.01s | 否 |
| A8 | 两轮工具调用场景（mock 或录制的两段 thinking）→ `.thinking-row` 数量 ≥2 且 DOM 顺序 = 到达顺序 | 是 |

## 10 · 参考链接

- 用户裁定原文：任务 #345 派发单（2026-08-20 21:13）
- OpenAI Reasoning guide：https://platform.openai.com/docs/guides/reasoning
- ChatGPT Thinking UI：产品行为观察（用户指定范式）
- Apple HIG · Progressive disclosure（折叠交互旁证）：https://developer.apple.com/design/human-interface-guidelines/progressive-disclosure
- visual-style 铁律：`~/.nebflow/skills/nebflow/visual-style/SKILL.md`
- #327 子 agent thinking 数据链路史：`~/.nebflow/docs/Nebflow/subagent-thinking-bubble-spec.md`
