# 设计规格书 · NebLink 好友与消息系统 UI（A2A 一期）

**版本**：v1.2 · **日期**：2026-08-18 · **作者**：design-engineer
**状态：frozen · 待实现**（[U3 2026-08-18 09:52 用户终审]：提醒降级——**"不需要提示音和横幅。就图标角标就行。"** R4 从微信形态五级回撤为纯 badge；NL 号规则同步 [U3] 裁定，见 arch 文档 v1.2）
**修订记录**：v1.1（2026-08-17 21:53 用户终审拍板）——§2.1 R1、§3.3 R7 标记终审通过；§4 提醒分级重写；§9 断言与 i18n key 增补。
v1.2（2026-08-18 09:52 用户终审拍板）——[U3] R4 回撤为**纯 badge**（L1/L1b/L2）：删除 L3 title 前缀、L4 系统通知（横幅）、L6 提示音及全部规格（§4.1/§4.2、断言 A19-A23、`settings.messageSound` i18n、settings-modal toggle 涉及面）；§3.1 搜索段同步 NL 号新规则（默认值=注册邮箱、可自定义，详见 friends-messaging-arch v1.2 §4）。
**需求原话**（2026-08-17 21:41）：侧边栏加「消息」「联系人」两个按钮（类似微信）；NebLink 号/邮箱搜人加好友、对方同意才能通信；好友消息在专门窗口（像设备互联 Dropbox 窗，不直接注入 agent）；窗口上方有「转发给 agent」按钮；agent 可以代用户给联系人发消息并显示在会话流；新消息提醒等细节由本规格设计。
**硬约束遵循**：nebflow/visual-style（铁律，最高优先级）· design-system 案例 001（断言口径三坑已内嵌 §9）· activity-bar-spec v1.2（冻结，面板注册表架构；图标序冲突已由 [U2] 终审裁定 R1 覆盖，见 §2.1）

---

## 0. 一句话目标

在 Activity Bar 上新增「消息」「联系人」两个面板钮（微信范式、消息第一性），提供好友请求闭环（搜索→请求→同意→通信）与会话列表；好友间文本消息在**独立毛玻璃聊天弹窗**（参照 Dropbox 设备窗形态）中收发，窗口支持一键「转发给 agent」与 agent 代发标注；新消息提醒为**纯图标角标**——消息钮未读总数 badge + 联系人钮请求计数 badge + 会话行未读数（[U3 2026-08-18 09:52 用户裁定："不需要提示音和横幅。就图标角标就行。"]，覆盖 [U2] 微信形态五级裁定）。

## 1. 参考与依据

| # | 来源 | 提炼规则 | 链接 |
|---|------|---------|------|
| R1 | 微信桌面版侧栏（用户点名范式） | 头像下依次：**消息 → 通讯录**；消息第一性，是主回路入口；会话列表行 = 头像/昵称/最后消息摘要/时间/未读角标；未读角标红/数字、99+ 截断（产品行为观察） | https://weixin.qq.com/ |
| R2 | 微信「新的朋友」 | 好友请求集中入口在通讯录顶部，带未处理计数；请求条目 = 头像/昵称/验证消息/同意·拒绝按钮；同意后转入好友列表（产品行为观察） | 同上 |
| R3 | 微信隐私边界 | 搜索只凭微信号/绑定信息命中；结果页只展示昵称+微信号+头像，**不回显查询键（邮箱/手机号）**；非好友无法发起聊天（产品行为观察） | 同上 |
| R4 | Apple HIG · Notifications | badge 承载计数语义，非装饰；打扰需克制——[U3 2026-08-18 用户裁定] 一期提醒**仅 badge**（无横幅无提示音），克制原则的极值形态 | https://developer.apple.com/design/human-interface-guidelines/notifications |
| R5 | WAI-ARIA APG · Listbox | 会话列表/好友列表键盘可达：↑/↓ 移动、Enter 激活、语义 role=listbox/option | https://www.w3.org/WAI/ARIA/apg/patterns/listbox/ |
| R6 | **visual-style 铁律 1/2/3/4/5/6** | 弹窗禁 overlay 遮罩（`--overlay-bg: transparent` 已是现状，sapphire.css:31/49）+ 面板必须毛玻璃；可交互控件 glass-control；字重 400/500/600；气泡边缘极淡 border；激活态用阴影不用竖线；低调克制 | ~/.nebflow/skills/nebflow/visual-style/SKILL.md |
| R7 | design-system 案例 001 踩坑 | i18n key 先行 / 状态语义显式 / 375px 增量断言口径——§9 全部照此执行 | ~/.nebflow/skills/design-system/SKILL.md |
| R8 | activity-bar-spec v1.2（冻结） | 面板注册表 `registerSidePanel({id, buttonId, panelId, i18nKey})` 架构——消息/联系人面板**只注册不动布局**；Side Bar 收展/互斥/持久化机制全部复用 | /tmp/activity-bar-spec.md（v1.2 冻结，§2.2 已标注 U2 覆盖；迁移后基准为 docs/Nebflow/ 同名活文档） |
| R9 | 现状代码 · Dropbox 设备窗 | 聊天窗形态参照：`cfg-modal-overlay`（透明 overlay）+ `cfg-modal` 毛玻璃面板 + tab 条 + 消息流（in/out 气泡 + meta 行 + hover 复制钮）+ 输入栏；WS 协议 `dropbox-*` 为同构先例 | src/main/resources/web/{js/dropbox.js, css/dropbox.css, css/sidebar.css:1136-1195} |
| R10 | 现状代码 · 消息注入 agent 通道 | 「转发给 agent」复用既有通道：`sendWs({type:'ask', question, sessionId})`（input.js:543）——转发=把消息文本作为用户消息发进当前 agent 会话 | src/main/resources/web/js/input.js |

## 2. 布局与信息架构

### 2.1 Activity Bar 图标序（裁定 R1 · 与 v1.1 冲突显式裁决）

**裁定 R1 [✅ 终审通过 · U2 2026-08-17 21:53 用户裁定：消息第一性定案]**：图标序 = **头像 → 消息 → 联系人 → Files → 空隙 → Teams/Flows/Agents → Settings**。

- 与 activity-bar-spec v1.1 §2.2 的冲突：v1.1 锁死「Files 在序位 2」，依据是用户原话「（文件浏览器）放于用户头像下面」。
- 覆盖理由：① 该锁的语境是栏内只有 Files 一个面板钮；② 本次用户点名微信范式，微信桌面端头像下依次是消息、通讯录，消息第一性是范式核心；③ 「头像下面有文件浏览器」的语义在序位 4 依然成立（头像→三个面板钮之首组内）。**[U2 终审记录]**：用户确认覆盖成立——U1 时代消息功能未立项，Files 序位 2 是空档期裁定；activity-bar-spec 同步修订 v1.2 标注此覆盖关系。
- 沉底组（Teams/Flows/Agents/Settings）不动（v1.1 已锁，肌肉记忆零收益改动）。

| 顺序 | 项 | 类型 | 行为 |
|------|-----|------|------|
| 1 | 头像（既有） | 账号入口 | 不变 |
| 2 | **消息 Messages**（新增 `#messages-btn`，lucide `message-circle`） | 面板切换钮 | 展开 Side Bar 激活会话列表面板；再点收起；**带未读总数 badge**（§4） |
| 3 | **联系人 Contacts**（新增 `#contacts-btn`，lucide `contact-round` 或 `book-user`） | 面板切换钮 | 同上，激活好友列表面板；**带待处理好友请求计数 badge**（§4） |
| 4 | Files（既有 `#files-btn`） | 面板切换钮 | 不变 |
| — | `.activity-spacer` | 分隔 | 不变 |
| 5 | Teams/Flows/Agents | Canvas 启动钮 | 不变 |
| 6 | Settings | 弹窗钮 | 不变 |

### 2.2 两个新面板（Side Bar 内，走注册表零布局改动）

- **消息面板** `#panel-messages`：panel 头「消息」+ 会话列表（§3.2）。注册 `registerSidePanel({id:'messages', buttonId:'messages-btn', panelId:'panel-messages', i18nKey:'activity.messages'})`。
- **联系人面板** `#panel-contacts`：panel 头「联系人」+ 搜索添加区 + 「新的朋友」入口 + 好友列表（§3.1）。注册 `id:'contacts'`。
- 面板互斥/收展动画/宽度拖拽/持久化：全部复用 v1.1 机制，零新代码路径。

### 2.3 聊天窗形态（裁定 R2）

**裁定 R2：独立居中毛玻璃弹窗**（参照 Dropbox 设备窗 `cfg-modal` 形态），**不做面板内切换**。

- 依据：用户原话「在专门的窗口，像我们的设备互联那样」——点名 Dropbox 窗形态，排除微信桌面版的面板内切换范式（范式冲突时以用户裁定优先，分层纪律）。
- 规格：`overlay` 用既有 `.cfg-modal-overlay`（`--overlay-bg: transparent`，铁律 1 已满足，**禁止加暗化**）；面板 `cfg-modal` 毛玻璃体系；宽 560px（比 dropbox 520 略宽，承载 header 工具区）· max-width 92vw · 高 70vh · max-height 80vh；圆角 20px；既有 `cfg-modal` 阴影组。
- 互斥：同一时刻至多 1 个聊天弹窗；点开另一会话 → 替换内容（瞬时，无动画，克制）。
- 关闭：× 钮 / Esc / 点 overlay 空白（复用 dropbox 三出口惯例）。
- 打开入口：① 消息面板点会话行；② 联系人面板点好友行「发消息」。

## 3. 功能规格

### 3.1 联系人面板

**结构（自上而下）**：
1. **搜索添加区**：搜索框（placeholder「NebLink 号 / 邮箱」）+ 回车或右侧搜索钮**提交式查询**（防爬，不做渐进搜索——与案例 001 渐进搜索区分：这是跨用户目录查询，不是本地数据）。
2. **「新的朋友」入口行**：图标 + 文案 + 右侧待处理请求计数 badge（0 隐藏）。点击展开请求列表（面板内分区切换，不进弹窗）。
3. **好友列表**：每行 = 头像 36px / 昵称（13px/500）/ NebLink 号（11px muted）。

**搜索与加好友流程**：
- 输入 NebLink 号 → 提交 → 命中：结果卡（头像/昵称/NebLink 号 + 「加好友」glass-control 按钮）；未命中：空态文案「未找到该用户」。**NebLink 号规则 [U3 2026-08-18 用户裁定]**：默认值 = 注册邮箱（邮箱即初始 NL 号），用户可自定义（字符集限字母+数字、长度 3-32、唯一性实时检测——数据模型/API 一期支持，自定义 UI 阶段 2，详见 friends-messaging-arch v1.2 §4）；查询为单字段精确匹配（邮箱是默认 NL 号，无需双键）。
- **隐私裁定 R3（[U3] 修订）**：搜索结果**只展示**头像+昵称+NebLink 号；不可按昵称模糊搜索（防枚举，一期裁定）。原「查询键不回显邮箱」条款随 [U3] 失效——默认态 NebLink 号即注册邮箱，结果卡展示的号天然是邮箱（用户裁定邮箱为默认公开标识）；用户自定义 NL 号后与邮箱脱钩，邮箱不再是查询键（隐私回升）。
- 点「加好友」→ 弹出验证消息输入（可选一句，≤50 字，微信式）→ 发送 → 按钮态变「等待验证」（disabled）。
- 自己搜自己 → 结果卡显示「这是你自己」，无加好友钮。

**好友请求（新的朋友）**：
- 收到的请求条目：头像/昵称/NebLink 号/验证消息 + 「同意」「拒绝」两钮；已处理条目降级为状态字（「已添加」/「已拒绝」），按钮消失。
- 发出的请求条目（同列表，分组在「我发出的」）：状态字「等待验证」/「已同意」/「已拒绝」。
- 同意 → 双方好友列表出现对方；消息面板自动建立会话（空会话，摘要「你们已成为好友」系统提示）。

**在线状态裁定 R4**：一期**不做在线状态**。理由：presence 需要服务端心跳与隐私策略（对谁可见），A2A 网络拓扑未定前不做假状态；好友行只显示头像/昵称/NebLink 号。P2 候选。

**空态**：无好友 = 居中插图位（暂用文字）「暂无联系人，搜索 NebLink 号或邮箱添加」；无请求 = 「新的朋友」行无 badge，点入显示「暂无好友请求」。

### 3.2 消息面板（会话列表）

**会话行**（微信式，R1）：头像 40px / 昵称（13px/500）/ 最后一条消息摘要（12px muted，单行省略，agent 代发消息摘要带前缀「[Agent]」）/ 右侧时间（11px muted，当天 HH:mm、昨天「昨天」、更早 M/D）/ 未读数 badge。

**排序与置顶裁定 R5**：一期按**最新消息时间倒序**，无置顶（克制；置顶 P2）。新消息到达 → 该会话移到顶部。

**已读清除**：打开会话（聊天弹窗打开该会话）→ 该会话未读清零、上报服务端；Activity Bar 消息 badge 同步减。

### 3.3 聊天弹窗

**结构**：
```
┌─ cfg-modal（毛玻璃） 560px ────────────────┐
│ header: 对方昵称 · NebLink号     [转发⧉] × │  ← 工具区
│ ────────────────────────────────────────── │
│ 消息流（滚动区，in/out 气泡，复用 dropbox  │
│   气泡体系 + agent 代发标注 + 已转发 chip）│
│ ────────────────────────────────────────── │
│ 输入栏: [文本输入            ] [发送]       │
└────────────────────────────────────────────┘
```

**消息流**（一期只文本）：
- 气泡复用 dropbox 体系：in = `--glass-etched-bg` + 极淡 border（铁律 4）；out = `rgba(91,127,191,0.15)` 蓝；meta 行 = 时间 + hover 操作钮（复制 · **转发给 agent**）。
- **agent 代发标注（裁定 R6）**：agent 发出的消息方向 = out（我方侧），气泡 meta 行前置 chip「**Agent 代发**」（11px/500，`--color-frame-text-muted` 文字 + `--glass-etched-bg` 底，**不新开颜色**）；DOM 类 `.fm-msg-agent-badge`。会话列表摘要同步带「[Agent]」前缀。理由：铁律 4 的来源标注语义（注入消息须可溯源）在 A2A 场景的同构延伸。
- **转发状态**：被转发的消息气泡 meta 行追加 chip「**已转发给 agent**」（同 chip 体系，DOM 类 `.fm-msg-forwarded-badge`），持久标注不消失。

**「转发给 agent」（裁定 R7 [✅ 终审通过 · U2 2026-08-17 21:53] · 本期核心交互）**：
- **位置**：两处——① header 工具区「转发给 agent」钮（glass-control，转发**当前会话最近一条对方消息**）；② 每条消息 hover 操作钮（转发**该条**）。两个入口同一动作。
- **行为**：把消息文本作为用户消息发进**当前激活 agent 会话**：`sendWs({type:'ask', question: text, sessionId})`，文本前缀 `[来自 {好友昵称}] ` 以便 agent 理解来源（R10）。
- **反馈**：① 气泡即时打「已转发给 agent」chip；② 聊天弹窗顶部短暂 toast「已发送到当前会话」；③ **agent 的回复不回流进好友会话窗**——转发是单向把消息送进本地 agent，agent 输出在主聊天区（好友窗 ≠ agent 窗，两个回路不混淆）。一期无「agent 处理结果回传好友」的自动链路（那属于 agent 主动发消息能力，见下）。
- **人在环**：转发本身即用户点击授权；agent 若要给好友回消息，走「agent 代发」通道（下方），UI 上以代发标注呈现。

**agent 主动发消息**（需求 5）：用户跟自己 agent 对话时，agent 可调用发消息能力给联系人发消息；该消息实时出现在对应好友会话流（out + 「Agent 代发」chip）与会话列表摘要（「[Agent] …」）。若聊天弹窗正开着该会话 → 消息流即时追加并滚到底部；未开 → 会话列表摘要更新（**不产生未读角标**——自己侧发出的消息不计未读）。

**非好友拦截**：会话存在但好友关系已解除 → 输入框禁用 + 消息流顶部系统提示条「对方已不是你的好友，无法发送消息」（居中灰色系统条，非气泡，微信式）；后端同时拦截，UI 提示为前馈。

### 3.4 好友关系解除后的历史（边界裁定 R8）

删除好友入口一期**不提供**（需求未提，克制）；但后端关系解除（任何途径）发生时 UI 行为必须定义：会话保留在列表、消息历史**只读**、输入框禁用 + 系统提示条（§3.3 拦截条）。重新成为好友 → 输入框恢复，历史连续。

## 4. 新消息提醒（纯 badge 三级 · [U3 2026-08-18 09:52 用户裁定：只要图标角标]）

用户终审原话：**"不需要提示音和横幅。就图标角标就行。"** [U3] 覆盖 [U2] 的微信形态五级裁定——L3 title 前缀、L4 系统通知（横幅）、L6 提示音全部**删除不做**（连降级层也不保留，克制到底）；提醒体系收敛为纯 badge 三级。

| 层级 | 位置 | 一期裁定 | 规格 |
|------|------|---------|------|
| L1 | **Activity Bar 消息钮 badge** | ✅ 做 | 未读总数数字 badge：18px 圆点、`--color-primary` 绿底白字 10px/600（既有 token，零新色）；0 隐藏；>99 显示「99+」；挂 `#messages-btn` 右上角，active/hover 态不消失 |
| L1b | **联系人钮 badge** | ✅ 做 | 待处理好友请求计数，同 badge 体系挂 `#contacts-btn`；0 隐藏 |
| L2 | **会话列表未读数** | ✅ 做 | 每会话行右侧数字 badge（同体系小一号 16px）；打开会话清零 |
| ~~L3~~ | ~~页面 title 未读计数~~ | ❌ 删除（[U3]） | 不做 |
| ~~L4~~ | ~~系统通知 Notification API~~ | ❌ 删除（[U3]） | 不做——不请求 Notification 权限、不构造通知、无降级路径需要 |
| L5 | **Dock 图标角标** | ❌ 不做（维持原裁定） | Web 前端不可达原生层（NSDockTile / Tauri）；无 PWA/Electron 壳之前技术上不可行 |
| ~~L6~~ | ~~消息提示音~~ | ❌ 删除（[U3]） | 不做——无提示音、无 Settings 开关、无 `fm_sound_enabled` LS 状态 |

**未读语义显式锁定**（防案例 001 踩坑②）：未读数 = 该会话中 direction=in 且晚于「本端已读游标」的消息数；已读游标 = 聊天弹窗打开该会话**并渲染出最新消息**的瞬间上报；agent 代发消息不产生未读；badge 数字源为服务端推送的未读集合，前端不做本地推算。

## 5. 视觉规格（全部 token 引用，零新色值）

| 元素 | 规格 | 来源 |
|------|------|------|
| 面板钮（消息/联系人） | 36×36px、圆角 10px、lucide 19px stroke 1.8；hover/active 复用 `.activity-btn` 体系；**active 无竖线仅阴影**（铁律 5 / v1.1 裁定 C1） | nav.css 既有 |
| badge | `--color-primary` 底 + 白字；消息钮 18px / 会话行 16px 圆点 | base.css:2 既有 |
| 面板容器/头 | 既有 `#sidebar` 玻璃卡 + `.panel-header/.panel-title`（13px/500/muted/uppercase） | sidebar.css 既有 |
| 会话行/好友行 | hover `--glass-control-bg-hover`；选中（面板内）`--color-frame-active` + inset 阴影（与 activity-btn active 同语义）；无竖线 | 既有 token |
| 聊天弹窗 | `.cfg-modal` 毛玻璃体系（`--glass-bg` + blur(`--glass-blur`) saturate(1.15) + `--glass-border` + 既有阴影组）；overlay `--overlay-bg`（transparent，铁律 1） | sidebar.css:1136-1188 既有 |
| 气泡 | in = `--glass-etched-bg` + `--glass-etched-border`；out = rgba(91,127,191,0.15)（蓝宝石蓝变体，案例 001 零新色纪律同款） | dropbox.css:146-161 既有 |
| 「Agent 代发」/「已转发」chip | 11px/500、`--color-frame-text-muted` 字、`--glass-etched-bg` 底、圆角 9999px | 全部既有 token |
| 按钮 | 「加好友」「同意」「发送」「转发给 agent」= `.glass-control` 体系（铁律 2）；「拒绝」= 次级玻璃钮（无绿底） | sapphire.css 既有 |
| 字重 | body 400 / 昵称 500 / 面板标题 600（铁律 3） | 既有 |

**新颜色 token：零。**

## 6. 状态机

### 6.1 好友请求

| 状态 | 触发 | UI |
|------|------|-----|
| pending（待处理） | 收到/发出请求 | 收方：「同意」「拒绝」钮；发方：「等待验证」 |
| accepted（已同意） | 收方点同意 | 双方入好友列表 + 自动建空会话 + 系统提示「你们已成为好友」 |
| rejected（已拒绝） | 收方点拒绝 | 收方条目显示「已拒绝」；发方显示「已拒绝」；**可重新发起请求**（一期不做冷却期，服务端防刷兜底） |

### 6.2 消息

| 状态 | 触发 | UI |
|------|------|-----|
| sending（发送中） | 点发送 | 气泡 meta 行 spinner（复用 pulse 动画，dropbox.css:244 既有） |
| delivered（已送达） | 服务端 ACK | 静默，无标识（克制） |
| failed（失败） | 超时/服务端错误 | 气泡旁红色「!」+ 点击重发 |
| ~~已读~~ | — | **一期不做已读回执**（裁定 R9：微信亦无已读回执；隐私与克制；P2 候选） |

### 6.3 聊天弹窗显隐

复用 dropbox 三出口（×/Esc/overlay 空白）+ 会话互斥替换；弹窗打开状态**不持久化**（reload 后回会话列表，克制）。

## 7. 边界与异常

| 场景 | 裁定 |
|------|------|
| 未登录 | 消息/联系人面板显示登录引导空态（「登录 NebLink 后使用消息与联系人」+ 登录钮触发既有 device-flow 弹窗）；badge 全隐藏 |
| 非好友发消息 | 后端拦截 + §3.3 系统提示条；输入框禁用 |
| 好友关系解除 | §3.4：历史只读、输入禁用、提示条；重新加好友恢复 |
| 多设备同步未读 | 已读游标存服务端；任一端打开会话 → 各端 badge 同步清零（服务端广播）；UI 断言只验本端清零 |
| 超长文本 | 消息摘要单行省略（既有 ellipsis 惯例）；气泡内 `word-break: break-word`（dropbox 既有）；单条消息 ≤2000 字，输入框超限禁发 |
| 窄视口 | 不新增断点行为（v1.1 §6 同裁定）；375px 验收用**增量口径**（案例 001 踩坑③） |
| 搜索防抖/防爬 | 提交式查询（无渐进）；前端 1s 最小间隔 + 服务端限流 |
| 空态 | 无会话：「暂无会话」；无好友/无请求/未找到用户：§3.1 各处文案，全部 i18n key |
| WS 断线 | 聊天弹窗输入框禁用 + 顶部提示「连接已断开，正在重连…」（复用既有重连机制状态） |

## 8. 无障碍

- 会话/好友列表：`role="listbox"` + 行 `role="option"`；↑/↓ 移动、Enter 打开（R5）；`aria-selected` 同步
- badge 语义：`aria-label` 读「n 条未读消息」/「n 个好友请求」，不做纯装饰
- 聊天弹窗：打开后焦点进输入框；Esc 关闭后焦点归还触发会话行；focus trap 在弹窗内（案例 001 A8 同口径）
- 键盘可达：所有按钮 Tab 可达，focus ring = `box-shadow: 0 0 0 2px var(--glass-control-border)`（v1.1 §7 同补法）
- 对比度：muted 文字 on `--glass-etched-bg` 亮暗双主题 ≥4.5:1（既有 token 已验证；案例 001 F-1 muted 对比度为非阻断已知项，chip 文字 11px 属装饰性标注不阻断）
- prefers-reduced-motion：全部 transition/animation ≤0.01s（含 pulse spinner 降级为静态「…」）

## 9. 可断言验收点（二值 + 口径显式到可执行）

> 全部断言基于隔离实例 Playwright 可执行；📷 者需截图供视觉评审交叉验证。

| # | 断言 | 显式口径 |
|---|------|---------|
| A1 | Activity Bar 按钮序：`#activity-bar` 子元素依次为 `#activity-avatar`、`#messages-btn`、`#contacts-btn`、`#files-btn`、`.activity-spacer`、`#teams-btn`、`#flows-btn`、`#agents-btn`、`#settings-btn` | 逐个 `nth-child` id 比对（裁定 R1 的可执行形式） |
| A2 | 点 `#messages-btn` → `#panel-messages.active` 恰好 1 个激活面板 + `#messages-btn.active`；再点 → 收起 + LS 恢复语义同 v1.1 A3/A4 | 注册表路径，复用 v1.1 机制断言 |
| A3 | 未登录：两面板各显示登录引导空态 + badge 不可见 | `getNeblinkState().loggedIn===false` seed |
| A4 | 搜索加好友：输入已 seed 的 NebLink 号提交 → 结果卡含昵称+号；seed 用户 NL 号为**自定义值（非邮箱）**时，结果卡不含邮箱文本（裁定 R3 [U3] 修订后可执行形式：`resultCard.textContent` 含自定义号、不匹配 seed 邮箱串） | 隐私断言必测 |
| A5 | 好友请求闭环：seed 待处理请求 → 点「同意」→ 好友列表出现该用户 + 消息面板出现新会话 + 请求条目状态字变「已添加」且按钮消失 | 三联合一 |
| A6 | 会话行结构：含头像/昵称/摘要/时间/未读 badge 五元素；摘要单行（`scrollHeight===clientHeight`） | 📷 |
| A7 | 聊天弹窗 📷：overlay computed `background-color === transparent` 且面板 `backdrop-filter` 含 blur（铁律 1 双联）；宽 560px | computed 值断言 |
| A8 | 转发给 agent：hover 消息点转发 → 该气泡出现 `.fm-msg-forwarded-badge` + WS 捕获 `{type:'ask', question 以 '[来自 ' 前缀, sessionId=当前会话}` + toast 出现 | WS 帧断言 + DOM |
| A9 | header 转发钮转发**最近一条对方消息**：seed 会话最后一条为 in 消息 X → 点 header 钮 → X 气泡得 forwarded chip（非其他消息） | 显式选中语义 |
| A10 | agent 代发：注入 agent 代发消息 → 气泡含 `.fm-msg-agent-badge` + 方向 out + 会话列表摘要前缀「[Agent]」+ **未读数不增** | 四联合一 |
| A11 | 未读双级联动：seed 2 会话各 2 未读 → `#messages-btn` badge=4 + 两行 badge=2；打开其中一会话 → 该会话 badge 消失 + 消息钮 badge=2 | 数字精确断言（[U3]：title 前缀层已删，不再断言） |
| A12 | badge 边界：0 隐藏（`offsetParent===null`）；seed 100 未读 → 显示「99+」 | 两分支 |
| A13 | 非好友拦截：seed 关系解除会话 → 输入框 disabled + 系统提示条存在且非气泡（无 `.fm-msg-bubble` 结构） | DOM 断言 |
| A14 | **激活态无竖线** 📷：`#messages-btn.active` computed `border-left-width===0px` 且 `box-shadow!=='none'` | v1.1 A9 同口径延展 |
| A15 | 375px 增量口径：聊天弹窗打开态全文档 scrollWidth === 关闭态基线 + 弹窗子树 scrollWidth ≤ 560 | 案例 001 踩坑③口径 |
| A16 | prefers-reduced-motion：弹窗与 badge 的 transition/animation-duration ≤0.01s | 案例 001 A10 口径 |
| A17 | i18n：切换 zh-CN/en 后两面板标题、空态文案、chip 文案、按钮文案为各自 key 运行时输出，无字面硬编码中文 | 案例 001 踩坑① |
| A18 | Esc 关闭聊天弹窗后焦点归还触发会话行 | 案例 001 A8 同口径 |
| A24 | **提醒边界负断言（[U3] 新增）**：seed 非聚焦会话新消息 → 全程无 Notification 构造（无调用路径）、无 Audio 播放、`document.title` 不含未读计数前缀——纯 badge 之外零提醒副作用 | [U3] 回撤的可执行形式，防 L3/L4/L6 残留代码 |

（原 v1.1 A19-A23——L4 通知触发/聚焦抑制/权限降级/点击跳转与 L6 提示音——随 [U3] 裁定全部删除，编号不回填以防引用漂移。）

### i18n key 清单（先行锁定）

| key | zh-CN | en |
|-----|-------|-----|
| `activity.messages` | 消息 | Messages |
| `activity.contacts` | 联系人 | Contacts |
| `panel.messages` | 消息 | Messages |
| `panel.contacts` | 联系人 | Contacts |
| `contacts.searchPlaceholder` | NebLink 号 / 邮箱 | NebLink ID / Email |
| `contacts.search` | 搜索 | Search |
| `contacts.notFound` | 未找到该用户 | User not found |
| `contacts.addFriend` | 加好友 | Add |
| `contacts.pendingVerification` | 等待验证 | Pending |
| `contacts.self` | 这是你自己 | This is you |
| `contacts.newFriends` | 新的朋友 | New Friends |
| `contacts.noRequests` | 暂无好友请求 | No friend requests |
| `contacts.accept` | 同意 | Accept |
| `contacts.decline` | 拒绝 | Decline |
| `contacts.accepted` | 已添加 | Added |
| `contacts.declined` | 已拒绝 | Declined |
| `contacts.sentRequests` | 我发出的 | Sent |
| `contacts.empty` | 暂无联系人，搜索 NebLink 号或邮箱添加 | No contacts yet — search by NebLink ID or email |
| `contacts.verifyMessagePlaceholder` | 发送验证消息（可选） | Add a message (optional) |
| `messages.empty` | 暂无会话 | No conversations |
| `messages.systemNowFriends` | 你们已成为好友 | You are now friends |
| `messages.forwardToAgent` | 转发给 agent | Forward to agent |
| `messages.forwarded` | 已转发给 agent | Forwarded to agent |
| `messages.forwardToast` | 已发送到当前会话 | Sent to current session |
| `messages.agentBadge` | Agent 代发 | Sent by agent |
| `messages.notFriendBlocked` | 对方已不是你的好友，无法发送消息 | You can no longer message this contact |
| `messages.loginRequired` | 登录 NebLink 后使用消息与联系人 | Log in to NebLink to use messages and contacts |
| `messages.reconnecting` | 连接已断开，正在重连… | Connection lost, reconnecting… |
| `messages.inputPlaceholder` | 发送消息 | Type a message |
| `messages.send` | 发送 | Send |
| `messages.yesterday` | 昨天 | Yesterday |
| `messages.ariaUnread` | {n} 条未读消息 | {n} unread messages |
| `contacts.ariaRequests` | {n} 个好友请求 | {n} friend requests |

（[U3] v1.2 删除：`settings.messageSound`——提示音不做，开关与 i18n 同步移除；原 L4 通知无新增 key 的说明一并作废。）

## 10. 改造涉及面清单（实现方交接）

| 模块 | 改动 | 量级 |
|------|------|------|
| `index.html` `#activity-bar` | avatar 下插 `#messages-btn`/`#contacts-btn`（在 `#files-btn` 前）+ badge 元素槽位 | S |
| `index.html` `#sidebar-panel` | 注册 `#panel-messages`/`#panel-contacts` 容器 | S |
| `js/activityBar.js` | 注册两个面板（registerSidePanel，v1.1 架构）+ badge 渲染 API | M |
| 新增 `js/contacts.js` | 搜索/请求/好友列表 | M |
| 新增 `js/messages.js` | 会话列表 + 聊天弹窗 + 转发逻辑 + badge 联动 | M |
| 新增 `css/friends.css`（或并入 dropbox.css） | 会话/好友行、chip、badge、弹窗布局——**全部既有 token，零新色值** | M |
| `js/locales/zh-CN.js` / `en.js` | §9 key 清单 | S |
| 后端（交接说明） | 好友/请求/消息/未读游标 WS 协议与 REST（不在本规格范围，接口约定另立后端规格书；UI 按 seed 可测） | — |

## 11. 参考链接

1. 微信（产品行为观察，用户点名范式）：https://weixin.qq.com/
2. Apple HIG · Notifications：https://developer.apple.com/design/human-interface-guidelines/notifications
3. WAI-ARIA APG · Listbox：https://www.w3.org/WAI/ARIA/apg/patterns/listbox/
4. nebflow/visual-style 铁律：~/.nebflow/skills/nebflow/visual-style/SKILL.md
5. design-system 案例 001：~/.nebflow/skills/design-system/SKILL.md
6. activity-bar-spec（v1.2 冻结，R1 覆盖标注）：/tmp/activity-bar-spec.md
7. 现状代码：src/main/resources/web/{index.html, js/dropbox.js, js/neblink.js, js/activityBar.js, js/input.js, css/dropbox.css, css/sapphire.css, css/sidebar.css}

（[U3] v1.2 删除：MDN Notification API / Autoplay 政策两条引用——L4/L6 已撤。）

---

**终审记录**：
1. **裁定 R1（§2.1）**：图标序 = 头像→消息→联系人→Files→空隙→Teams/Flows/Agents→Settings，覆盖 activity-bar-spec v1.1「Files 锁序位 2」（U1 时代消息功能未立项的空档裁定）。✅ 终审通过（U2 2026-08-17 21:53），activity-bar-spec 同步修订 v1.2。
2. **提醒分级（§4）**：U2（2026-08-17 21:53）曾裁定"类似于微信就行"（L4/L6 入一期）；**[U3 2026-08-18 09:52 用户终审覆盖]**："不需要提示音和横幅。就图标角标就行。"——R4 回撤为纯 badge 三级（L1/L1b/L2），L3 title 前缀、L4 系统通知、L6 提示音全部删除不做；L5 Dock 角标维持不做。✅ 终审通过。
3. **裁定 R7（§3.3）**：单向转发确认——转发后 agent 回复不回流好友窗；agent 回好友消息走「代发」通道。✅ 终审通过（U2），维持原设计。
4. **NebLink 号规则（§3.1 搜索段）**：**[U3 2026-08-18 09:52 用户裁定]**——NL 号默认值=注册邮箱，用户可自定义（字母+数字、3-32、唯一性实时检测）；查询简化为单字段精确匹配。✅ 终审通过，数据模型与 API 详见 friends-messaging-arch v1.2 §4/§6。

**状态：已冻结待实现**（v1.2，U2/U3 终审裁定已全部并入正文）。
