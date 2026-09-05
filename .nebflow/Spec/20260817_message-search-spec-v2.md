# 设计规格书 · Nebflow 客户端「消息记录搜索」

- 日期：2026-08-17 · 作者：design-engineer · 状态：待用户确认冻结
- **v2（2026-08-17 10:08 裁定修订）**：① §3 无结果态建议句显式指定 i18n key `search.noResultsSuggestion`（消除 A5 断言歧义）；② §3 结果态补 activeIndex 显式语义（消除 §3/A4 歧义）。修订处以「**(v2)**」标注。
- **v2.1（2026-08-17 10:24 A9 争议裁定修订）**：③ A9 后半句改为「弹窗不新增横向滚动」的增量断言（原 `scrollWidth ≤ 375` 因既有 #daemon-panel/#canvas-panel 布局在 375px 视口本底溢出而不可达，与搜索弹窗无关；既有面板溢出另立任务，不在本规格范围）。修订处以「**(v2.1)**」标注。
- 适用代码：`src/main/resources/web/js/chatSearch.js` + `css/modal.css`(§Chat History Search Modal) + `index.html`(#search-overlay)
- 铁律依据：`~/.nebflow/skills/nebflow/visual-style/SKILL.md`（最高优先级）

---

## 0 · 一句话目标

把现有「提交式搜索弹窗」升级为**微信/Spotlight 式的渐进搜索体验**——保留居中毛玻璃弹窗形态（视觉铁律），输入即搜、键盘全程可达、结果按会话分组、跳转精准高亮，全程克制专业。

## 1 · 参考与依据

| 来源 | 提炼的可执行规则 | 链接 |
|---|---|---|
| 微信 macOS（用户点名参考） | ① 全局搜索**输入即出结果**（无需提交），结果按类型分组（联系人/群聊/聊天记录…），聊天记录组内按会话聚合；② 会话内「查找聊天内容」面板顶部搜索框 + 分类筛选（日期/成员/类型）；③ 结果按时间倒序，点击定位并高亮 | 产品行为观察（macOS 微信 4.x）；辅助：https://weixin.qq.com/ |
| macOS Spotlight | ① 渐进搜索：每次击键即时更新结果，首条为 Top Hit；② ↑/↓ 导航、Return 打开；③ **Esc 两段式**——有内容先清空、空时再关闭；④ 分类分组 + 组头计数 | https://support.apple.com/guide/mac-help/search-with-spotlight-mchlp1008/mac |
| Apple HIG · Searching | ① 结果即时显示（provide results as people type）；② 提供搜索建议/历史降低输入成本；③ 空态与无结果态都要有明确引导文案 | https://developer.apple.com/design/human-interface-guidelines/searching |
| Slack | ① ⌘F = 会话内搜索 / ⌘G = 全局搜索的双快捷键模式；② 结果页用类型 tab + Filters 收敛过滤器；③ 修饰符语法（from:/in:/before:）留给高级用户，不占 UI 面积 | https://slack.com/help/articles/202528808-Search-in-Slack |
| Telegram Desktop | ① 会话内搜索栏内嵌聊天区顶部、↑/↓ 直接在消息流中跳匹配；② 日历日期筛选。→ **取舍：不采用内嵌栏形态**（理由见 §2） | https://telegram.org/ ; 快捷键行为：https://quickref.me/telegram.html |
| WAI-ARIA APG · Combobox/Listbox | 弹窗内焦点陷阱、↑/↓ 移动 aria-activedescendant、Enter 激活、Esc 关闭的键盘契约 | https://www.w3.org/WAI/ARIA/apg/patterns/combobox/ |
| Nebflow 现状（chatSearch.js） | 已有：居中弹窗、scope/agent/tool/date 过滤器、结果点击跳转会话 + `.search-hit-flash` 闪烁、REST 后端为数据源、MAX_RESULTS=200 截断。本规格书在此之上升级交互，**不推翻已有数据管线** | `src/main/resources/web/js/chatSearch.js` |

## 2 · 布局与信息架构

**形态决策（核心取舍）**：保留**居中毛玻璃弹窗**（640px 宽，现状尺寸），不改为 Telegram 式内嵌搜索栏、不改为侧边栏。理由：① Nebflow 搜索默认跨会话（scope=all），需要承载过滤器与分组结果列表，弹窗容量合适；② 视觉铁律已锁定「弹窗 = 无 overlay 遮罩 + 面板毛玻璃」，现有 `#search-modal` 已合规，保持形态零风格风险；③ 与微信「查找聊天内容」面板同构。

```
┌──────────────────────────────────────────────────┐
│ Search Messages  ·························  [×]  │  header（保留）
├──────────────────────────────────────────────────┤
│ [🔍 keyword input — autofocus, Esc 两段式]       │  主输入（升级：去提交按钮化）
│ [scope▾] [agent▾] [type▾] [date from] [date to]  │  过滤器行（保留现状单行 wrap）
├──────────────────────────────────────────────────┤
│ status 行：进度 x/y · 结果数 · 截断提示           │
├──────────────────────────────────────────────────┤
│ ▾ 会话 A（3）                                     │  scope=all 时：按会话分组，
│   ○ 结果条目（badge · 时间 · 摘要 mark 高亮）      │  组头=会话名+计数，组内时间倒序
│ ▾ 会话 B（1）                                     │  scope=current 时：平铺，无组头
│   ○ 结果条目                                      │
└──────────────────────────────────────────────────┘
```

- **搜索入口**：header 右侧 `#search-btn`（现状保留）；新增快捷键 **⌘F / Ctrl+F**（Slack 模式；绑定在聊天区上下文，不全局劫持浏览器查找——需 e.preventDefault 并只在弹窗打开后拦截 Esc/Enter）
- **变更点 vs 现状**：① 移除 `#search-go` 主按钮的必需性（降级为可点击的次要提交，或改为 input 内嵌清除按钮 ×）；② 结果列表加分组组头（仅 scope=all）；③ 结果条目加键盘选中态样式（见 §3）
- **token 引用**：面板 `--glass-bg` + `--glass-blur`(24px) + `--glass-border`（既有共享 modal 规则，不动）；输入框 `--glass-etched-bg` / `--glass-etched-border` / focus 用 `--glass-etched-border-focus`；过滤器复用 `.cfg-select/.cfg-input`；**不新增任何颜色 token**
- **新增类名（非 token）**：`.search-group-header`（组头）、`.search-result.active`（键盘选中态）

## 3 · 交互状态机表

| 状态 | 触发 | 视觉表现 | 退出路径 |
|---|---|---|---|
| 关闭 | — | `#search-overlay` 无 `.on`，display:none | 点 #search-btn / ⌘F → 打开 |
| 打开·空态（idle） | 打开弹窗，关键词为空 | 输入框 autofocus + focus ring；结果区显示 `.search-hint` 引导文案（现有 `search.hint`）；过滤器行可见 | 输入字符 → 搜索中；Esc → 关闭 |
| 搜索中（loading） | 输入变更后 300ms debounce 到期 | status 行显示 `search.searching` 或进度 `search.progress(done,total)`（现状逻辑保留）；结果区保留上一次结果并降不透明度 0.5（避免闪烁抖动） | 完成 → 结果态；新输入 → 取消旧请求重新计时 |
| 结果态 | 搜索完成且有命中 | status 行 = `search.results(n)`（+ 截断提示）；列表渲染分组结果；首条自动置 `.active` **(v2)**——语义即：渲染完成瞬间 `activeIndex = 0`（第一条 active），按 ↓ 后 `activeIndex = 1`（第二条获得 `.active`、第一条失去），与 §8 A4 断言一致 | 输入变更 → 搜索中；↑/↓ 导航；Enter 跳转；Esc 清空 → 空态 |
| 无结果态 | 搜索完成且 0 命中 | `.search-hint` 显示 `search.noResults` + 一行建议文案。**(v2)** 建议句走独立 i18n key `search.noResultsSuggestion`，经 `t()` 输出、禁硬编码（§6 locale 纪律）；参考文案结构 =「动作建议 + 触发对象」，zh：`试试缩短关键词或清除日期/类型筛选` / en：`Try a shorter keyword or clear the date/type filters` | 输入变更 → 搜索中 |
| 错误态 | fetch 抛错（现状 catch） | status 行显示 `search.failed: {msg}`，**新增**行内「重试」按钮（`.glass-control` 样式） | 点重试 / 输入变更 → 搜索中 |
| 结果条目 hover | 鼠标悬停 | `background: var(--color-frame-hover)`（现状保留）；同时清除键盘 `.active` | 移出恢复 |
| 结果条目键盘选中 | ↑/↓ | `.search-result.active`：`background: var(--color-frame-hover)` + 左缘 2px `rgba(91,127,191,0.5)` inset 条（选中态比 hover 多一层标识，键盘用户可见焦点位置） | Enter 跳转 / hover 转移 |
| 跳转中 | Enter / 点击条目 | 弹窗关闭 → `switchToSession` → 平滑滚动 `scrollIntoView({block:'center'})` → 目标消息 `.search-hit-flash` 1.8s（现状动画保留） | flash 结束 → 常规聊天态 |
| disabled | 无活动会话且 scope=current | status 行显示 `search.noSession`（现状保留），输入框仍可输入（切 scope=all 即可搜） | 切换 scope |
| 输入框 focus | Tab/点击 | 现有双层 ring：`border-color: rgba(91,127,191,0.5)` + 双层 box-shadow（现状保留，合 §7 对比度） | blur |
| 过滤器变更 | select/date change | **即时重搜**（不等输入变更），复用 300ms debounce | — |

**键盘契约（Spotlight/APG 模式）**：
- `↑/↓`：结果间移动 `.active`，列表内循环；`PageUp/PageDown` 跳 5 条
- `Enter`：跳转 `.active` 条目；无结果时不动作
- `Esc` 两段式：输入框非空 → 清空并回空态；输入框为空 → 关闭弹窗
- `Tab`：焦点在弹窗内循环（focus trap），不逃逸到背景聊天区
- 弹窗打开期间背景聊天区快捷键全部静默（避免误发消息）

## 4 · 动效规范

| 动效 | 触发 | 参数 | reduced-motion 处理 |
|---|---|---|---|
| 弹窗入场 | 打开 | `modalIn` 0.2s `cubic-bezier(0.16,1,0.3,1)`，scale(0.96)→1 + translateY(8px)→0（既有 keyframes，复用） | 时长降为 0.01s（等价瞬现），仅保留 opacity 淡入 |
| 结果列表更新 | 搜索完成 | 无入场动画（克制原则：结果直出，不做逐条 stagger）；旧结果→新结果间 opacity 0.5→1 过渡 120ms | 去掉过渡，直接替换 |
| 键盘选中移动 | ↑/↓ | `background` transition 0.12s（与 hover 同参数，既有）+ `scrollIntoView({block:'nearest'})` 即时无平滑 | 同左（本身无位移动画） |
| 跳转闪烁 | jumpToResult | `searchHitFlash` 1.8s ease，rgba(91,127,191,0.18)→透明（既有） | 保留 1 次纯色闪烁但时长 0.6s（用户需要定位反馈，不可完全去除） |
| 媒体查询落地 | — | 全部包进 `@media (prefers-reduced-motion: reduce)` | — |

**debounce**：输入/过滤器变更后 **300ms** 触发；请求竞态用递增 requestId 丢弃过期响应。

## 5 · 视觉规格

| 元素 | 规格 | 来源 |
|---|---|---|
| 遮罩 | `#search-overlay` `background: var(--overlay-bg)` = **transparent**，**禁**任何背景暗化/模糊 | 铁律 1 + `sapphire.css:31,49` |
| 面板 | `--glass-bg`(亮 rgba(255,255,255,0.55) / 暗 rgba(24,28,38,0.68)) + `backdrop-filter: blur(24px) saturate(1.15)` + 1px `--glass-border` + 顶部折射线 `--sapphire-refraction` + 共享 modal 阴影 | 铁律 1 + `modal.css:30-93` |
| 圆角 | 面板 20px；结果条目 10px；输入框 8px；badge 5px；mark 3px | 现状，全部保留 |
| 尺寸 | 面板宽 640px、`max-width: calc(100vw - 48px)`、`max-height: 85vh` | 现状保留 |
| 字重 | 标题 600（`--color-frame-text-bright` 15px）；组头会话名 600/12px；结果摘要 400/12px；badge 500/10px uppercase；状态行 400/11px muted | 铁律 3 方案 B；现状字号保留 |
| 字体 | `-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif` | `base.css:37` |
| 高亮 mark | `background: rgba(91,127,191,0.28)`，暗色主题下提升为 0.36 保证可辨（**新增一条 dark override**，不算新 token——同一蓝宝石色相的透明度变体） | 现状 + 暗色修正 |
| 键盘选中态 | `.search-result.active` = hover 背景 + inset 2px 左缘条 `rgba(91,127,191,0.5)` | 新增类，色值取既有 sapphire 蓝 |
| 重试按钮 | `.glass-control` 标准（`--glass-control-*` 全家桶 + blur 10px） | 铁律 2 |
| 颜色纪律 | 全部引用既有 token；唯一新色值是 mark 暗色透明度变体与 active 左缘条，均为既有 `rgb(91,127,191)` 蓝宝石蓝的透明度变体 | 克制原则 |

## 6 · 边界与异常

| 场景 | 规格 |
|---|---|
| 空关键词 + 仅过滤器 | 允许搜索（现状支持：kw 空时匹配全部），按过滤器出结果 |
| 结果 >200 | 截断 + status 行 `search.truncated(200)` 提示（现状保留）；截断提示用 muted 色，不做「加载更多」（后端非分页，超出现阶段范围） |
| 超长会话名 | 组头与条目头 `max-width` + ellipsis（现状 `.search-result-session` 模式扩展到组头） |
| 超长关键词（>200 字符） | 输入框 `maxlength` 不限，但 mark 匹配只按完整串；摘要截取逻辑现状保留（以首个命中为中心 ±60 字符） |
| 无时间戳消息（tool/ask/agent 类） | 设了日期范围时被排除（现状规则），status 行**不**解释（避免噪音）；无日期范围时排列表尾 |
| 会话历史异步加载中跳转 | 现状重试链（250ms × 12 次）保留；最终失败 toast `search.jumpFailed` |
| 切换 locale 时弹窗开着 | 重渲染静态文案（现状 `locale-changed` 监听保留），i18n key 全部走 `t()`，禁硬编码中英 |
| 窄视口 <768px | 面板 `max-width: calc(100vw - 24px)`；过滤器行 wrap 成两行（现状 flex-wrap 已支持）；date 输入宽度缩至 120px |
| 暗色主题 | 全部走 dark token 分支（`sapphire.css` 既有）；mark 透明度 0.36；active 左缘条 0.6 |
| 搜索中关闭弹窗 | 中止请求（AbortController），重开时从空态开始，不恢复上次结果（Spotlight 模式：每次打开是全新会话） |
| 未登录/401 | fetch 失败进错误态，「重试」按钮可触发重新认证后的重搜 |

## 7 · 无障碍

- **焦点管理**：打开 → focus `#search-keyword`；关闭 → focus 归还 `#search-btn`；Tab/Shift+Tab 在弹窗内 trap（× 按钮 → 输入框 → 过滤器 → 首个结果 → 循环）
- **结果列表 ARIA**：容器 `role="listbox"` + `aria-label`；条目 `role="option"` + `aria-selected`；输入框 `role="combobox"` + `aria-expanded="true"` + `aria-controls="search-results"` + `aria-activedescendant` 指向 `.active` 条目 id
- **status 行** `aria-live="polite"`：进度/结果数/错误对屏幕阅读器播报
- **focus ring**：输入框既有双层 ring 保留；结果条目键盘选中用左缘条 + 背景（不依赖 outline，视觉克制但可见）
- **对比度**：muted 状态文字 `--color-frame-text-muted`(#9a9da5 亮 / #656870 暗) 在面板玻璃底上 ≥ 4.5:1 需在视觉评审时实测；mark 高亮不改变文字色（`color: inherit`）保证原对比度
- **非鼠标可达**：全部功能键盘可完成（打开 ⌘F → 输入 → ↑↓ → Enter → Esc 关闭）
- **reduced-motion**：见 §4 表

## 8 · 可断言验收点（供 qa-frontend 转 Playwright）

| # | 断言（二值） | 类型 | 需截图 |
|---|---|---|---|
| A1 | 打开弹窗后 `getComputedStyle(#search-overlay).backgroundColor === 'rgba(0, 0, 0, 0)'`（无遮罩）且 `#search-modal` 的 `backdropFilter` 含 `blur(` | DOM | 是（亮/暗各一） |
| A2 | 在 `#search-keyword` 输入关键词、**不点击任何按钮**，800ms 内 `#search-status` 文本从空变为「搜索中/结果数」之一（渐进搜索生效） | 行为 | 否 |
| A3 | Esc 两段式：有输入时按 Esc → `#search-keyword.value === ''` 且弹窗仍开（`.on` 存在）；再按 Esc → `.on` 移除 | 行为 | 否 |
| A4 | ≥2 条结果时按 ↓ → 第二条获得 `.search-result.active` 且第一条失去；按 Enter → 弹窗关闭且聊天区出现 `.search-hit-flash` 元素 | DOM | 是（active 态 + flash 帧） |
| A5 | 搜索不存在的关键词 → `.search-hint` 出现且文本含 `search.noResults` 对应 locale 文案 + **(v2)** `search.noResultsSuggestion` 对应 locale 建议句（断言比对 `t('search.noResultsSuggestion')` 的当前 locale 输出，非字面硬编码中文） | DOM | 是 |
| A6 | 结果摘要内 `<mark>` 的 computed `background-color` 在亮色主题 = `rgba(91, 127, 191, 0.28)`、暗色 = `rgba(91, 127, 191, 0.36)` | DOM | 否 |
| A7 | scope=all 且命中跨 ≥2 会话时，结果区存在 ≥2 个 `.search-group-header`，组头文本含会话名与计数 | DOM | 是 |
| A8 | Tab 循环：从 × 按钮起连续 Tab 焦点不离开 `#search-modal` 子树（20 次 Tab 后 `document.activeElement` 仍在弹窗内） | 行为 | 否 |
| A9 | 视口 375px 宽：面板宽度 ≤ 351px（`#search-modal.getBoundingClientRect().width ≤ 351`）且**弹窗不新增横向滚动 (v2.1)**——`document.scrollingElement.scrollWidth` 在弹窗打开态 === 关闭态（打开前先记录关闭态基线），且 `#search-modal` 子树内无任何元素 `getBoundingClientRect().right > 375` 或 `.left < 0`。注：既有 #daemon-panel（固定 400px）/#canvas-panel（离屏 right=459）在 375px 视口的本底溢出不属本规格范围（与弹窗开/关无关，实测两态溢出清单一致），另立任务处理 | DOM | 是（响应式） |
| A10 | `prefers-reduced-motion: reduce` 模拟下，`#search-modal` 的 `animation-duration` ≤ 0.01s 或 animation-name 为 none | DOM | 否 |

## 9 · 参考链接

1. 微信（产品范式，用户点名）：https://weixin.qq.com/
2. macOS Spotlight 使用手册：https://support.apple.com/guide/mac-help/search-with-spotlight-mchlp1008/mac
3. Apple HIG · Searching：https://developer.apple.com/design/human-interface-guidelines/searching
4. Slack · Search in Slack：https://slack.com/help/articles/202528808-Search-in-Slack
5. Telegram Desktop 快捷键与搜索行为：https://quickref.me/telegram.html ；官网 https://telegram.org/
6. WAI-ARIA APG · Combobox Pattern：https://www.w3.org/WAI/ARIA/apg/patterns/combobox/
7. Nebflow 视觉铁律：`~/.nebflow/skills/nebflow/visual-style/SKILL.md`
8. Nebflow 现状实现：`src/main/resources/web/js/chatSearch.js`、`css/modal.css:622-771`、`css/sapphire.css`、`index.html:306-327`

---

### 附：本规格书的变更摘要（vs 现状实现）

| # | 变更 | 类型 |
|---|---|---|
| 1 | 提交式 → 渐进搜索（300ms debounce + requestId 竞态 + AbortController） | 交互升级 |
| 2 | scope=all 结果按会话分组（新增 `.search-group-header`） | 信息架构 |
| 3 | 键盘契约补全：↑/↓ 导航 + `.active` 选中态 + Esc 两段式 + focus trap + ⌘F 打开 | 交互/无障碍 |
| 4 | 错误态加「重试」按钮（`.glass-control`） | 状态补全 |
| 5 | reduced-motion 适配 + 暗色 mark 透明度变体 | 边界补全 |
| 6 | ARIA combobox/listbox 角色 + aria-live 播报 | 无障碍 |
