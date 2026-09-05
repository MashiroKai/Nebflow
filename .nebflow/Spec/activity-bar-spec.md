# 设计规格书 · Nebflow 侧边栏 Activity Bar 化

> **状态**：shipped（三道防线全过，合并 @3124ceef，关卡 255/255）｜ **维护者**：design-engineer ｜ **域**：Nebflow
>
> **版本日志**
> - 2026-08-17 v1 初版（design-engineer）
> - 2026-08-17 v1.1（21:33 用户终审三问拍板）——推翻 v1 §2.3 裁定 b：待办区维持浮窗不进 Side Bar；Activity Bar 不设 Todos 槽位与 badge；侧边栏改造独立先行。修订处标注 [U1 2026-08-17 用户裁定]
> - 2026-08-17 v1.2（21:53 用户终审）——§2.2 图标序被 A2A 好友消息规格 R1「消息第一性」覆盖：头像→消息→联系人→Files→空隙→Teams/Flows/Agents→Settings，标注 [U2 2026-08-17 用户裁定]；消息/联系人按钮本体归 friends-messaging-spec，本规格只锁顺序；A1 相应更新。**冻结待实现**
> - 2026-08-17 实现+验收闭环（Frontend 实现 17a652a5 → qa/design/合并三道防线全过，merged @3124ceef）——**A10 第①句按 Manager 裁定修订**（循消息搜索 B12 先例）：「打开面板态 scrollWidth === 折叠态基线」→「≤ 折叠态基线 + 300px 面板合法宽度预算」，正文 §8 已并入；裁定依据：两态溢出贡献者（.header-right / 离屏 #canvas-panel）为存量本底溢出（案例 001 时期 650px 在案），不绑本特性验收，全文档窄视口响应式归独立 backlog
**需求原话**（2026-08-17 21:23）："我们的目前左侧栏，现在是只有文件浏览器，我需要把它变成侧边栏的一个按钮，放于用户头像下面，类似于Vscode的布局。左侧栏我们未来会有其他的用处。"
**硬约束遵循**：nebflow/visual-style（铁律，最高优先级）· design-system 案例库（案例 001 断言口径教训已内嵌本文 §8）

---

## 0. 一句话目标

把「文件浏览器」从侧栏常驻面板改造成 Activity Bar 上的一个可切换面板按钮（紧随用户头像之下），并把 Side Bar 升级为「图标 + 面板注册」的可扩展两层架构——未来加面板只注册不动布局。

## 1. 参考与依据

| # | 来源 | 提炼规则 | 链接 |
|---|------|---------|------|
| R1 | VSCode 官方文档 · User Interface | Activity Bar 在最左，切换 Side Bar 视图；图标可带数字 badge（如 Git outgoing changes）；⌘B 切换 Side Bar 可见性；布局状态跨重启持久化 | https://code.visualstudio.com/docs/getstarted/userinterface |
| R2 | VSCode 产品行为（惯例） | 再点当前激活图标 → 收起 Side Bar；同一时刻只显示一个视图；Side Bar 宽度可拖拽 | （产品行为观察，文档同上） |
| R3 | **visual-style 铁律 5**（用户裁定 2026-08-12） | 「移除侧边栏按钮点击后竖线显示状态的效果，改为仅用阴影」——**覆盖 VSCode 的左缘高亮条惯例**，见 §5 冲突裁定 C1 | ~/.nebflow/skills/nebflow/visual-style/SKILL.md |
| R4 | visual-style 铁律 1/2/6 | 面板毛玻璃（已有）；可交互控件 glass hover（`.activity-btn` 已符合）；低调克制 | 同上 |
| R5 | design-system 案例 001 踩坑 | i18n key 先行 / 状态语义显式 / 375px 增量断言口径——本规格 §8 全部照此执行 | ~/.nebflow/skills/design-system/SKILL.md |
| R6 | Nebflow 现状代码 | `#activity-bar` 已存在（index.html:40-51，nav.css:146-286，activityBar.js）；sidebar 折叠机制已存在（`body.sidebar-collapsed` + LS + ⌘B，main.js:2396-2416）；宽度拖拽已存在（colResizer.js，min 180px/默认 236px/持久化/双击复位） | src/main/resources/web/ |

**关键现状结论**：这不是从零设计——Activity Bar 玻璃卡片、折叠动画（`--panel-ease cubic-bezier(0.32,0.72,0,1)` / 0.32s）、⌘B、宽度拖拽全部已就绪。本规格的核心是**接入 + 架构化**，视觉与动效全面复用既有 token，零新色值。

## 2. 布局与信息架构

### 2.1 布局结构（改造后）

```
┌──────────┬──────────────────┬───┬─────────────────────┬───┬─────────┐
│ Activity │  Side Bar        │ ⋮ │  Main (chat)        │ ⋮ │ Canvas  │
│ Bar 48px │  #sidebar        │re-│                     │re-│ (既有)  │
│ (既有卡) │  玻璃卡 220px默认 │sizer                    │sizer        │
│          │                  │   │                     │   │         │
│ ┌──────┐ │ ┌──────────────┐ │   │                     │   │         │
│ │头像  │ │ │ panel 头     │ │   │                     │   │         │
│ ├──────┤ │ ├──────────────┤ │   │                     │   │         │
│ │📁文件│◄┼─┤ EXPLORER     │ │   │                     │   │         │
│ ├──────┤ │ │  (既有内容   │ │   │                     │   │         │
│ │ …未来│ │   零改动)    │ │   │                     │   │         │
│ ├──────┤ │ └──────────────┘ │   │                     │   │         │
│ ├──────┤ │                  │   │                     │   │         │
│ │(空隙)│ │                  │   │                     │   │         │
│ ├──────┤ │                  │   │                     │   │         │
│ │Teams │ │                  │   │                     │   │         │
│ │Flows │ │                  │   │                     │   │         │
│ │Agents│ │                  │   │                     │   │         │
│ │⚙设置│ │                  │   │                     │   │         │
│ └──────┘ │                  │   │                     │   │         │
└──────────┴──────────────────┴───┴─────────────────────┴───┴─────────┘
 常显(折叠面板时也在)   可整体收起                 宽度拖拽(既有 colResizer)
```

### 2.2 Activity Bar 图标清单与顺序（裁定 · [U1 2026-08-17 用户裁定] · 序位于 v1.2 被 [U2] 覆盖）

图标序（v1.2 终审版）：**头像 → 消息 → 联系人 → Files → 空隙 → Teams/Flows/Agents（沉底不动）→ Settings**。**无 Todos 槽位**（Q2 裁定：待办不进 Activity Bar，无图标、无 badge）。

**[U2 2026-08-17 用户裁定：R1 消息第一性覆盖 U1 时代的 Files 序位 2——当时消息功能未立项，「头像下面有文件浏览器」是空档期裁定；A2A 好友消息规格点名微信范式（头像下依次消息→通讯录），消息/联系人插于 Files 之前。]** 消息（`#messages-btn`）/联系人（`#contacts-btn`）按钮本体的面板注册、badge、i18n 由 **friends-messaging-spec v1.1（冻结）** 定义，本规格只锁顺序与架构，不重复定义。

| 顺序 | 项 | 类型 | 行为 |
|------|-----|------|------|
| 1 | **头像**（既有） | 账号入口 | 不变：未登录→登录弹窗；已登录→新标签开个人主页。**非面板切换钮** |
| 2 | **消息 Messages**（`#messages-btn`，[U2] 新增） | **面板切换钮** | 展开 Side Bar 激活会话列表面板；再点收起；未读 badge——本体规格见 friends-messaging-spec §2.1/§4 |
| 3 | **联系人 Contacts**（`#contacts-btn`，[U2] 新增） | **面板切换钮** | 同上，激活好友列表面板；请求计数 badge——本体规格见 friends-messaging-spec |
| 4 | **文件 Files**（新增 `#files-btn`，lucide `files` 或 `folder`） | **面板切换钮** | 点击：展开 Side Bar 并激活 explorer 面板；再点：收起 Side Bar |
| 5+ | …未来面板 | 面板切换钮 | 只通过注册加入，禁止改布局代码 |
| — | `.activity-spacer`（既有） | 分隔 | 面板钮组与底部分组之间 |
| 6 | Teams / Flows / Agents（既有） | **Canvas 启动钮** | 不变：开 Canvas tab。非面板切换钮，不参与互斥 |
| 7 | Settings（既有，最底） | 弹窗钮 | 不变：居中毛玻璃弹窗 |

**顺序理由**：~~用户原话「放于用户头像下面」锁死 Files 在序位 2~~ **[被 U2 覆盖]**：U1 锁序时栏内仅 Files 一个面板钮；[U2] 终审确认消息第一性（微信范式），「头像下面有文件浏览器」语义在序位 4 仍成立（头像下面板钮组内）。Canvas 启动钮与 Settings 沉底分组不动（既有用户肌肉记忆，改动零收益）。~~待办区紧随其后~~ **[废弃 U1]**：Q2 裁定待办不进栏，v1 的 Todos 预留槽描述整体删除。

### 2.3 ★ 待办区容器裁定（[U1 2026-08-17 用户裁定] · 终审记录）

**[U1 2026-08-17 用户裁定：待办区维持浮窗，不进 Side Bar、无图标槽位]**

v1 本节裁定 b（待办区成为 Side Bar 新面板 + badge，废弃顶部浮窗）经用户 2026-08-17 21:33 终审**推翻**，终审结论：

- **Q1 = 维持浮窗**：待办区不进 Side Bar，维持现有任务卡片浮窗形态。待办区规格书另一实例按浮窗设计，与本裁定一致，无冲突。
- **Q2 = 待办不放**：Activity Bar **不放待办图标**——无 Todos 槽位、无 badge。
- **Q3 = 接受分期**：侧边栏改造独立先行（本规格范围不受 Q1/Q2 影响，仍成立）。

连带删除项（[废弃 U1]）：v1 的「Badge 契约（跨规格书接口）」章节整体删除——badge 数字口径、0 隐藏、99+ 三条契约随 Todos 图标一并废弃；待办面板的可见性诉求由浮窗形态自身承接，归待办区规格书。

## 3. 交互状态机

### 3.1 面板切换钮（Files / 未来面板钮）状态机

| 状态 | 触发 | 视觉（全部复用既有 token，零新值） |
|------|------|----------------------------------|
| 默认 | 面板未激活 | `color: var(--color-frame-text-muted)`，透明背景 |
| hover | 指针悬停 | 既有 `.activity-btn:hover` 玻璃 hover（`--glass-control-bg-hover` + blur + 立体边缘，铁律 2 已满足） |
| focus（键盘） | Tab 聚焦 | 新增 focus ring：`box-shadow: 0 0 0 2px var(--glass-control-border)`（见 §7，既有 `:focus{outline:none}` 需补可见焦点） |
| pressed | 按下瞬间 | 复用头像 `:active` 的 `transform: scale(0.97)`，迁移到 `.activity-btn:active` |
| **active（面板激活）** | 面板展开且为当前面板 | 既有 `.activity-btn.active`：`background: var(--color-frame-active)` + `box-shadow: inset 0 1px 3px rgba(0,0,0,0.08), 0 1px 2px rgba(0,0,0,0.04)`（暗色：nav.css:177 既有变体）。**无竖线**（裁定 C1） |

（v1「badge 有数」状态行 [废弃 U1]：无 Todos 图标即无 badge。）

### 3.2 Side Bar 显隐状态机（复用既有机制，不改语义）

- 状态载体：`body.sidebar-collapsed`（既有）+ 新增「当前激活面板 id」状态
- 收起：宽度 → 0、opacity → 0，动画 `--panel-duration 0.32s` / `--panel-ease cubic-bezier(0.32,0.72,0,1)`（base.css:88-110 既有，零改动）
- 展开：反向同动画
- **再点同一激活图标 → 收起**（VSCode R2）；**点另一面板图标 → 面板内容瞬时切换**（不播宽度动画，VSCode 同为瞬时；克制铁律）
- 折叠时 Activity Bar 常显（nav.css:181-185 既有，零改动）

### 3.3 状态持久化（显式语义，防案例 001 踩坑②）

| 状态 | 存储 | 恢复语义 |
|------|------|---------|
| Side Bar 显隐 | 既有 `key('sidebar_collapsed')`（品牌前缀 LS key） | 既有 inline script（index.html:36）首帧恢复 class，**无闪烁契约保持** |
| 当前激活面板 id | **新增** `key('sidebar_active_panel')`，值 = 面板注册 id（如 `'files'`） | reload 后：若未折叠 → 恢复激活对应面板 + 对应图标 `.active`；若已折叠 → 不展开，仅记录上次面板，下次展开时恢复之 |
| 面板宽度 | 既有 `key('col_widths')`（colResizer） | 零改动 |

**显式语义锁定**：「默认状态」= 无任何 LS 记录 → Side Bar 展开 + files 面板激活 + `#files-btn.active`。

## 4. 动效规范

| 动效 | 触发 | 时长/缓动 | 说明 |
|------|------|----------|------|
| Side Bar 收展 | 面板钮点击 / ⌘B / header toggle | 0.32s · `cubic-bezier(0.32,0.72,0,1)` | 既有 `--panel-*` token，零改动 |
| 面板内容切换 | 点另一面板钮（Side Bar 已展开） | **无动画，瞬时** | 克制铁律 + VSCode 同行为 |
| 图标 hover/active/pressed | 指针 | 0.15s（既有 transition）/ 0.1s scale | 既有 |
| **prefers-reduced-motion** | 系统设置 | 全部 transition/animation ≤ 0.01s | 沿用案例 001 A10 口径，断言覆盖 |

（v1「badge 出现/消失」动效行 [废弃 U1]。）

## 5. 视觉规格（全部可断言）

### 裁定 C1 · 激活态标识冲突裁决
VSCode 惯例 = 左缘 2px 高亮竖条；用户裁定（铁律 5，2026-08-12 原话「移除竖线，改为仅用阴影」）= **阴影**。
**裁决：用既有 `.activity-btn.active`（背景 `--color-frame-active` + inset 阴影），禁止任何形式的左缘条/发光条。** 理由：用户裁定优先级高于行业范式（visual-style 分层纪律）；且既有 Teams/Flows/Agents/Settings 钮已是阴影激活态，全栏一致。
（注：`.nav-agent.active` 的 `drop-shadow` 发光图标样式不迁移到面板钮——面板钮保持 `.activity-btn` 体系，统一克制。）

### 具体规格

| 元素 | 规格 | token 来源 |
|------|------|-----------|
| Activity Bar 容器 | 48px 宽、圆角 20px、`--glass-bg` + blur(`--glass-blur`) saturate(1.15)、`--glass-border` | nav.css:146-167 既有，零改动 |
| 面板钮 | 36×36px、圆角 10px、lucide 图标 19px stroke 1.8 | `.activity-btn` 既有 |
| 面板区容器 | 既有 `#sidebar` 玻璃卡 + `#sidebar-panel` 220px，零改动 | sidebar.css:1-46 |
| 面板头 | 既有 `.panel-header`/`.panel-title`（13px/500/muted/uppercase），explorer 面板标题 i18n 化（见 §8 i18n） | sidebar.css:55-69 |
| 字重 | body 400 / 标题 600 / 控件 500（铁律 3） | 既有 |
| 新颜色 token | **零**（~~badge 用既有 `--color-primary`~~ [废弃 U1]） | 案例 001 零新色纪律 |

## 6. 边界与异常

| 场景 | 裁定 |
|------|------|
| **窄视口 <459px** | 现状：全站**无任何 max-width 断点**（已查证 css/ 全部 @media 均为 prefers-color-scheme）；<459px 本底溢出为已知 backlog（案例 001 A9）。裁定：**本规格不新增断点行为**——Activity Bar 48px 常显不隐藏；窄屏由用户用 ⌘B/图标收起面板腾空间。验收断言用**增量口径**（§8 A10）。`@media (max-width: 459px)` 自动收起为 P2 候选，不在本期 |
| **面板互斥** | 同一时刻恰好 0 个（折叠态）或 1 个 `.panel.active`；断言覆盖 |
| **空状态** | 无面板激活（折叠）时 Main 自然占满（既有行为） |
| **面板注册但未实现** | 注册表有序但按钮条件渲染——未注册的面板 id 在持久化恢复时被忽略并回退 files（v1「待办槽位预留」表述 [废弃 U1]） |
| **文件浏览器零回归** | explorer 面板内容（#explorer-section 树、新建文件/夹、打开文件夹按钮）原样迁移容器，DOM 断言覆盖（§8 A11） |
| **多语言** | 新按钮 tooltip 与面板标题全部走 i18n key（§8），禁止字面硬编码（案例 001 踩坑①） |

## 7. 无障碍

- 面板切换钮：`aria-pressed` 二值同步面板激活态；tooltip 用 `title`（既有惯例）
- 键盘：Tab 可达全部面板钮；Enter/Space 激活；焦点环可见（既有 `:focus{outline:none}` 需为新增面板钮补 `box-shadow` 焦点环——既有钮同补，统一修）
- 对比度：active 图标 `--color-frame-text-bright` on `--color-frame-active` 亮暗双主题均达标（既有 token 已验证）
- 面板切换后焦点不动（不抢断），与 VSCode 一致

## 8. 可断言验收点（二值 + 口径显式到可执行）

> 全部断言基于隔离实例 Playwright 可执行；标注 📷 者需截图供视觉评审交叉验证。

| # | 断言 | 显式口径 |
|---|------|---------|
| A1 | Activity Bar 按钮序（v1.2 [U2] 终审版）：`#activity-bar` 子元素依次为 `#activity-avatar`、`#messages-btn`、`#contacts-btn`、`#files-btn`、`.activity-spacer`（其后 Teams/Flows/Agents/Settings 沉底组不变）；**反向断言不变**：无 `#todos-btn`（[U1] Q2） | `#activity-bar > :nth-child(1)` id=activity-avatar；`nth-child(2)` id=messages-btn；`nth-child(3)` id=contacts-btn；`nth-child(4)` id=files-btn；`nth-child(5)` class 含 activity-spacer；`document.querySelector('#todos-btn') === null`。注：`#messages-btn`/`#contacts-btn` 由 friends-messaging-spec 一期落地，落地前本断言的 2/3 位视为「未注册不出现」——v1.2 前实现态只验 1=avatar、随后 files-btn、spacer |
| A2 | 清 LS 首次加载：body 无 `sidebar-collapsed` + `#files-btn.active` 存在 + `.panel.active` 恰好 1 个且包含 `#explorer-section` | 三联合一 |
| A3 | 点击 `#files-btn` → body 有 `sidebar-collapsed` + `#files-btn` 无 `.active` + LS `key('sidebar_collapsed')`==='true' | 三联合一 |
| A4 | 再点 `#files-btn` → A2 三项全部恢复 | 反向闭环 |
| A5 | A3 状态 reload → 首帧 body 已有 `sidebar-collapsed`（inline script 恢复，无闪烁） | `page.reload()` 后 `DOMContentLoaded` 前 evaluate classList |
| A6 | 持久化面板 id：写入 `key('sidebar_active_panel')`='files'；reload → 恢复 files 面板激活；LS 中为**未注册面板 id 时回退 'files'**（v1 todo 分支 [废弃 U1]，只验 files 回退） | 回退路径必测 |
| A7 | ⌘B 与面板钮等价：⌘B 收起后再 ⌘B 展开 → 恢复上次激活面板 + 对应钮 `.active` | keyboard.press('Meta+b') |
| A8 | 折叠态 Activity Bar 常显：body.sidebar-collapsed 下 `#activity-bar` offsetWidth===48 且 visibility!=='hidden' | 📷 |
| A9 | **激活态无竖线** 📷：`#files-btn.active` computed `border-left-width`===0px 且 `box-shadow`!== 'none'（阴影在） | 裁定 C1 的可执行形式 |
| A10 | 375px 增量口径（案例 001 踩坑③；**2026-08-17 Manager 裁定修订，循消息搜索 B12 先例**）：打开面板态全文档 scrollWidth ≤ 折叠态基线 + 300px（面板合法宽度预算）；且 `#activity-bar` 子树 scrollWidth ≤ 48 | 不断言绝对值 ≤375；两态溢出贡献者（.header-right / 离屏 #canvas-panel）为存量本底，归窄视口响应式独立 backlog |
| A11 | 文件浏览器零回归：`#explorer-new-file-btn`/`#explorer-new-folder-btn`/`#explorer-folder-btn` 存在且 visible；`#explorer-tree` 在激活面板内 | DOM 断言 |
| A12 | header `#sidebar-toggle` 与 `#files-btn` 状态同步：点 toggle 收起 → `#files-btn` 失 `.active`；再点 → 恢复 | 双入口一致性 |
| A13 | prefers-reduced-motion：`#sidebar` computed `transition-duration` ≤ 0.01s | 案例 001 A10 口径 |
| A14 | **[废弃 U1]** badge 契约断言整体删除（Q2：无 Todos 图标即无 badge）；编号保留占位不顺延 | — |
| A15 | i18n：切换 zh-CN/en 后 `#files-btn` title 与面板标题分别为两语言各自 key 的运行时输出，无字面硬编码中文 | 案例 001 踩坑① |

### i18n key 清单（先行锁定）

| key | zh-CN | en |
|-----|-------|-----|
| `activity.files` | 文件 | Files |
| `panel.explorer` | 文件浏览器 | Explorer |

（[废弃 U1]：v1 的 `activity.todos` / `activity.todoBadge` / `activity.filesBadge` 三个 key 随 Todos 槽位与 badge 一并删除。）

（既有 `sidebar.toggle` 等 key 复用不动；新按钮 title 用 `t()` 注入，不用 data-i18n 硬挂——与 daemons 面板既有做法一致。）

## 9. 改造涉及面清单（实现方交接）

| 模块 | 改动 | 量级 |
|------|------|------|
| `index.html:40-51` | `#activity-bar` 内 avatar 下插入 `#files-btn`（~~+`#todos-btn` 条件槽位~~ [废弃 U1]）；`#sidebar-panel` 内 `#panel-sessions` 泛化为面板容器语义（id 保留，零 CSS 破坏） | S |
| `index.html:36` inline script | 追加恢复 `sidebar_active_panel` → 对应面板 `.active` class | S |
| `js/activityBar.js` | 新增**面板注册表**（`registerSidePanel({id, buttonId, panelId, i18nKey})`）+ 点击分发 + active 同步；Settings/Agents/Teams/Flows 逻辑不动 | M |
| `js/main.js:2395-2416` initSidebarToggle | 重构为单一状态源：`sidebar-collapsed` + active panel id 由 activityBar 模块统一读写，⌘B/header toggle 改为调同一 API（不再各自 toggle class） | M |
| `js/explorer.js` / `js/sidebar.js` | **零改动**（内容原样，仅容器归属由注册表管理） | — |
| `js/colResizer.js` | **零改动**（宽度拖拽已完备） | — |
| `css/nav.css` | 新增 `.activity-btn:focus-visible` 焦点环一条；~~新增 `.activity-badge` 一条~~ [废弃 U1]；其余零改动 | S |
| `css/base.css` / `css/sidebar.css` | **零改动**（折叠动画/面板容器均已就绪） | — |
| `js/locales/zh-CN.js` / `en.js` | §8 key 清单新增 | S |
| `js/taskList.js` / `#task-list` 浮窗 | 本规格**不动**；待办区独立规格书承接（浮窗形态，[U1 2026-08-17 用户裁定]） | — |

## 10. 参考链接

1. VSCode User Interface 官方文档：https://code.visualstudio.com/docs/getstarted/userinterface
2. nebflow/visual-style skill（铁律 1/2/3/5/6 与 2026-08-12 竖线裁定原话）：~/.nebflow/skills/nebflow/visual-style/SKILL.md
3. design-system 案例 001（断言口径三坑）：~/.nebflow/skills/design-system/SKILL.md
4. 现状代码：src/main/resources/web/{index.html, js/activityBar.js, js/main.js, js/colResizer.js, css/nav.css, css/base.css, css/sidebar.css}

---

**终审记录（2026-08-17 21:33 用户拍板，三问均已裁定）**：
- Q1：§2.3 待办区容器 → **裁定：维持浮窗**，不进 Side Bar（推翻 v1 裁定 b）。✅ 已裁定
- Q2：§2.2 图标顺序 → **裁定：待办不放**，Activity Bar 无 Todos 槽位、无 badge；图标序 = 头像 → Files → 空隙 → Teams/Flows/Agents → Settings。✅ 已裁定
- Q3：待办图标未落地期 Files 是唯一面板钮的中间态 → **裁定：接受分期**，侧边栏改造独立先行（此条不受 Q1/Q2 影响，仍成立）。✅ 已裁定

**终审记录 v1.2（2026-08-17 21:53 用户拍板 · U2）**：
- A2A 好友消息规格（friends-messaging-spec v1.1）裁定 R1「消息第一性」**覆盖** U1 时代的 Files 序位 2 锁——当时消息功能未立项，属空档期裁定。新图标序 = 头像 → 消息 → 联系人 → Files → 空隙 → Teams/Flows/Agents → Settings；消息/联系人按钮本体归 A2A 规格，本规格只锁顺序。✅ 已裁定，§2.2 与 A1 已并入正文。

**状态：shipped**（v1.2 + A10 裁定修订，[U1]/[U2] 用户裁定与 Manager A10 裁定已全部并入正文；三道防线全过，merged @3124ceef）。
