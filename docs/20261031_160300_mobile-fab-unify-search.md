---
type: design-spec
status: frozen
date: 2026-10-31
scope: 手机交互层（css/mobileLayer.css、js/mobile/、js/messages.js 加性缝、locales zh+en）——悬浮钮统一批 + 消息/联系人页搜索批（作者令 10-31 16:03，四件）
---

# 手机端悬浮钮统一 + 消息/联系人搜索批 · 设计规格书（2026-10-31 16:03 作者令）

关联：[[20261031_084400_liquid-glass-effect-v2__delegate-nebflow-a9ba5eff.zh-CN]]（g2-fab 基准材质来源）、[[20261030_202253_chat-search-convo-only-kind-colors__delegate-nebflow-115ab498]]（消息搜索弹窗裁定②）、[[20261030_100600_projcreate-device-flat-three-fixes__delegate-nebflow-a97665b1]]（页面壳先例）、[[20261030_084500_dock-message-badge__delegate-nebflow-12dfb591]]（dock 语言）。范式参考见 §1。

## §0 一句话目标

手机端全部悬浮钮以主对话顶缘双钮（48px g2-fab 液体玻璃正圆，top:16px+safe 左右 16px）为唯一基准规格归位，全钮统一 spring 按压反馈；消息页与联系人页右上加 48px 搜索钮，点击后搜索栏从钮处展开、内容整体下拉让位——消息页=类微信跨会话聊天记录检索（范围切换/高亮/跳转定位），联系人页=既有搜好友逻辑换展开式搜索栏承载。

## §1 参考与依据

- **基准规格**：`chatChrome.js` 双钮 + `liquid-glass.css .g2-fab`（48px 移动档、blur/sat 双档、上半镜面高光、spring 按压 scale(.88) + `cubic-bezier(0.34,1.56,0.64,1)` 180ms、边缘光折射）。作者令原文「大小/位置以主对话窗口顶部左右两钮为基准规格」。
- **微信会话内搜索**（weixin.qq.com 产品行为观察，作者令点名考古）：① 查找聊天内容 = 顶部搜索栏 + 范围/分类入口行 + 结果按会话聚合分组、组内时间倒序；② 命中关键词高亮（亮黄底 in 微信；本项目按双主题视觉语言收敛为 sapphire 30% 底，先例 = chatSearch 手机层 mark 档）；③ 点结果跳转会话并定位到该消息（上下文 + 命中闪烁）；④ 空态有引导文案（HIG Searching）。
- **iOS 按钮弹性**（HIG Direct Manipulation + 项目既有 g2 配方）：按下缩小回弹、松手过冲回位，180ms spring；`prefers-reduced-motion` 退化为透明度（既有 g2 降级块沿用）。
- **取舍**：不采用 Telegram 顶部内嵌搜索条（与既有页面壳「无顶栏、控件悬浮」语言冲突）；范围切换收敛为会话类型 chips（全部/好友/设备/群聊）——数据源 conversation.kind 原生语义，零新数据面。

## §2 布局与信息架构

### 2.1 页面悬浮 chrome 带（新统一层）

- 所有手机页（dock 根页 + push 详情页）共享一条**悬浮 chrome 带**：高度 48px，起点 `top: calc(16px + env(safe-area-inset-top))`。
- 返回钮（详情页）：48px g2-fab，`left:16px`；标题胶囊：带内水平居中（`left:50% + translateX(-50%)`，垂直中心与钮中心对齐）；页面体 `padding-top: calc(72px + safe)` 让位（72 = 16+48+8）。
- 消息/联系人根页：无返回钮（dock 语义，批②沿用），右上浮搜索钮 48px g2-fab `right:16px`，标题胶囊同带居中。
- canvas 页：左 ✕ 关文档 + 右堆叠视角钮同带浮动，原 `.mf-cv-toolbar` 通栏退化为透明浮动层；计数文本带内居中。
- z 序：钮在各自 page section 内 absolute（页面 z156 > fab 层 30，页面间互斥零冲突）。

### 2.2 展开式搜索栏（消息/联系人两页共用交互件）

- 触发：右上浮搜索钮（48px g2-fab，search 图标）→ 页面 section 挂 `.mh-search-open` → 搜索区从钮所在右上缘展开（宽度自右向左生长，grid-rows 0fr→1fr + opacity + scaleX origin right，220ms spring），页面体内容整体下拉让位（in-flow 天然推挤）；钮图标切换 search↔x，再点收合，Esc 收合。
- 联系人页内容 = 既有 `.fm-search-block`（contacts.js buildSearch，桌面逻辑零改动；手机层默认 collapsed，`mh-search-open` 下展开，结果卡 in-flow 推挤下方列表）。
- 消息页内容 = 新 `.mh-msgsearch` 块（输入行 + 范围 chips + 状态行 + 分组结果流），插会话列表前。

### 2.3 消息页搜索结果流

- 范围 chips：全部/好友/设备/群聊（默认全部；单选，选中 = 既有 brand-green 胶囊态口径）。
- 结果按会话分组：组头 = 会话标签 + 命中数；组内时间倒序；行 = 发送者 · 命中片段（`<mark>` 高亮全部命中） · 时间。
- 行点击 → 打开该会话（既有 openConversation 通路 + navController 收编弹页）→ `revealMessage(messageId)` 缝（messages.js 加性导出）：窗口内未载则按既有 keyset 回退链自动翻旧页（上限 25 轮），命中节点 scrollIntoView(center) + 1.6s sapphire 闪烁。
- 数据面：纯客户端渐进检索——`friendsApi.getConversations()` 快照 + 每会话 `getMessages(after=0, limit=50)` 前向翻页（每会话上限 20 页=1000 条，脚注声明），300ms debounce + requestId 竞态丢弃 + AbortController 中止（chatSearch 同族纪律）。

## §3 交互状态机

| 元素 | 态 |
|---|---|
| 悬浮钮 | rest / hover(填充 Δ≥14) / active(scale .88 spring 180ms) / focus-visible(2px --g2-fab-border) / disabled(opacity .5 禁形变) / reduced-motion(透明度 .8，0.01s) |
| 搜索钮 | rest(search 图标) / active / open 态(x 图标，aria-expanded=true) |
| 展开搜索区 | collapsed(高 0 不可见) / expanding(220ms spring 中段) / open(焦点进输入框) / 结果态(搜索中…→N 条结果/无匹配+引导/失败可重试) |
| 范围 chips | 默认全部；单选切换即重搜（去抖） |
| 结果行 | rest / active(.97) / 点击后弹会话页（搜索页状态保留，返回可续搜） |

## §4 动效规范

- 按压（全悬浮钮）：`transform .18s cubic-bezier(0.34,1.56,0.64,1)` + 投影收短（g2 既有）；按下 scale(.88)，松手回弹过冲——既有 g2-fab 配方即本批基准，双主题一致（token 单源）。
- 展开/收合：220ms，缓动 `cubic-bezier(0.34,1.3,0.64,1)`（克制过冲）；位移 = grid-rows + 内容 translateY(-6px→0) + opacity 0→1；`prefers-reduced-motion` 全部退化为 0.01s 直切。
- 跳转定位：scrollIntoView center（瞬时）+ 命中节点 `.fm-reveal-flash` 1.6s（sapphire 底 30%→0）。

## §5 视觉规格（全部 token 引用，零新色值）

- 钮：`.g2-fab` 全件（--g2-fab-* 单源）；48px 移动档（liquid-glass.css body.mobile-layer 块）。
- 展开栏：玻璃输入条（--glass-control-bg/border/blur 族）圆角 999 胶囊；chips = 既有 `.mf-strip-chip` 语言（r999 玻璃药丸，选中 brand-green 0.14 底/0.5 边）。
- mark 高亮：`rgb(var(--sapphire) / 0.30)` 底 + 继承字色（先例 = chatSearch 手机层 mark 档）。
- 组头/状态/脚注：12px `--color-frame-text-muted`；结果行 14px 正文 + hairline 分隔（--color-border）。
- 命中闪烁：同 mark 底起步渐隐。

## §6 边界与异常

- 封存态（features.friends OFF）：两搜索钮不建（同 ensureSocialPage 封存口径）。
- 未登录：消息页列表面有登录引导，搜索钮隐藏（无数据源）。
- 空会话/零命中：状态行双文案（无匹配 + 引导：换关键词或切范围）。
- 检索上限：每会话最近 1000 条，脚注常驻声明（诚实降级，不静默）。
- 跳转未命中（消息被撤回/超出上限）：toast「未能定位到该消息」，仍停在会话页。
- 多语言：zh/en 全键；窄视口 320px 下结果片段省略号收敛（max-width 100% + ellipsis 纪律）。

## §7 无障碍

- 搜索钮 `aria-expanded` + `aria-label`（i18n）；展开区输入框 autofocus + `aria-label`；状态行 `role=status`（aria-live）。
- Esc 收合搜索区（焦点归还搜索钮）；chips 用 button + aria-pressed。
- 全部钮 ≥44px 热区（48px 满足）；focus-visible 2px 描边（g2 既有）。

## §8 可断言验收点

- A1（几何）：详情页返回钮 48×48、`top=16+safe left=16`；消息/联系人页搜索钮 48×48、`right=16` 同 top；canvas 页 ✕/堆叠钮同规格同带。
- A2（材质）：返回钮/搜索钮 computed background = `--g2-fab-bg` 解析值（token 同源探针）。
- A3（按压）：`.g2-fab` transition 含 `cubic-bezier(0.34, 1.56, 0.64, 1)` 且 `:active` transform=scale(.88)（reduced-motion 下退化为 opacity）。
- A4（展开）：点搜索钮 → page section 含 `.mh-search-open`，搜索区 computed 高度 >0、输入框 focused；再点/Esc 收合。
- A5（联系人承载）：展开后 `.fm-search-block` 可见且输入键入 → mock 搜索出结果卡（既有逻辑零改动承载）。
- A6（消息检索）：键入关键词 → 结果按会话分组（组头标签+计数），行内 `mark` 数 ≥1；scope chip 切换 → 组集变化。
- A7（跳转定位）：点结果行 → conversation 页 live + 目标消息节点带 `.fm-reveal-flash`（或 scrollIntoView 已执行）。
- A8（封存态）：features.friends=false 时两页无 `.mh-fab-search`。
- A9（spec 回归）：mobile-dock（D18 改版断言）/ mobile-canvas-sheet（48px 断言）/ mobile-v3 / mobile-p2-search 全绿。

## §9 参考链接

- g2-fab 基准：`src/main/resources/web/css/liquid-glass.css`、`js/mobile/chatChrome.js`
- 先例：[[20261030_202253_chat-search-convo-only-kind-colors__delegate-nebflow-115ab498]]（搜索裁定② / mark 档）
- 微信查找聊天内容产品行为：https://weixin.qq.com/ ；Apple HIG Searching：https://developer.apple.com/design/human-interface-guidelines/searching
- 考古结论：会话内搜索 = 搜索栏 + 分类/范围行 + 分组结果 + 高亮 + 跳转定位；跨会话检索结果按会话聚合（与本批 §2.3 一致）

## 验证补记（实现期实证，2026-10-31）

- **特异性坑**：收合规则带 `#mobile-pages` ID 锚，展开规则原写法特异性落败 ⇒ 联系人页块视觉收合而 Playwright `toBeVisible` 不判 opacity 仍绿（假阴）。展开规则已同维度对打（`#mobile-pages` 同锚），F3 改 computed 值断言（max-height/opacity）锁死。
- **headless 折射伪影**：`html.g2-refract-on` 的 `filter:url(#g2-refract)`（feTurbulence+feDisplacementMap）在 headless Chromium 栅格化为固态蓝盘并盖掉钮内图标 —— 基准 chrome 双钮同样中招（先存量，非本批引入）；真机 WebKit/Chrome 正常。截图取证统一摘折射类（`tests/shots-mobile-fab-search.mjs` 注记），材质/几何/按压其余层真渲。
- **checkJs 基线漂移**：`askDock.js`/`canvas.js` 在 HEAD 处已有 5+1 处 TS2339 超冻结基线（早批合入未跑门）——本批顺手以零行为 JSDoc cast 修净（345 < 350 基线，门绿）。
- **mobile-v3 T1 漂移**：T1 仍断言「dock 根页（工作台）有返回钮」，与 Canvas 改造批②（10-31 早批已合入）语义冲突 —— 预存红，非本批引入；已改写为批②语义（根页无「<」，返回 = 中键 chat home）。
- **p12-settings T1/T3 漂移**（同批②语义债，HEAD 处预存红）：「工作台根页头 < 回聊天根」两步断言随批②退役；已改走 `#mobile-dock .dock-key[data-dock="chat"]`（chat home），3/3 绿。
- **liquid-glass.spec 四处 v2 旧钉**（HEAD 处预存红）：M3/R1 折射层 blur/saturate 仍钉 v2 的 16px/1.8（v3 §D 已升 20px/2.2，stand-tokens.css 单源）、T1 `--g2-fab-bg` 仍钉 0.6/0.14（v3 为 0.30/0.10）、F1 no-backdrop 回落仍钉 .78/.26（v3 `--g2-fab-solid` 为 .55/.20）——均已对齐合入的 v3 设计值，11/11 绿。
- **mobile-pop-canvas-open D1 负载敏感**：active tab 读取在 canvas-open 后即刻执行、不等 WS 驱动的页签激活——并发负载下偶发读回默认页签（插件）。实证：同树连跑 3/3 过、HEAD 亦过，非本批回归；未改（超出本批范围，如实上报）。
