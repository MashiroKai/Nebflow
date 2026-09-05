# 实体图标映射 + Nebula 独立标识 · 视觉规格书

- 状态：draft（待用户/Manager 确认后冻结）
- 日期：2026-08-24
- 作者：design-engineer
- 范围：Nebflow 客户端 `src/main/resources/web/`（activity bar 实体入口、Agents Canvas 面板、导航栏 agent 列表）
- 硬规则依据：`nebflow/visual-style`（最高优先级）；软参考：`design-system` 案例库（案例 001 的「断言口径显式化」教训已应用于 §8）

---

## 0 一句话目标

Team/Flow/Agent 三个一等公民实体各配语义明确的 lucide 图标，并把编排者 Nebula 从 standalone agent 列表中独立分区标识，亮/暗双主题、16–32px 全尺寸可读，零新依赖、零新颜色 token。

## 1 参考与依据

| 来源 | 提炼规则 | 链接 |
|---|---|---|
| lucide 官方图标语义 | `users`=多人/团队；`workflow`=节点+连线的 DAG 工作流；`bot`=自动化个体；`orbit`=中心体+环绕轨道（调度/编排） | https://lucide.dev/icons/ |
| Apple HIG · SF Symbols 选型原则 | 图标应直指实体隐喻，不借用相邻领域的近似符号（`git-branch` 是版本控制隐喻，误用于 Flow 即此类） | https://developer.apple.com/design/human-interface-guidelines/icons |
| VS Code activity bar | 侧边入口图标单色、静止态 muted、激活态仅靠既有 active 样式，不为单个图标单独着色 | https://code.visualstudio.com/docs/getstarted/userinterface |
| Linear / Slack 侧栏分区 | 列表分组 = 小号 muted 组头 + 分隔间距；特殊身份（如 Slack 的「本人/机器人」）用专属图标 + 徽标，不用高饱和色块 | https://linear.app/ · https://slack.com/ |
| Nebflow 现状（代码事实） | activity bar 当前映射 Team=`network` / Flow=`git-branch` / Agent=`users`（`index.html:64-66`）；Agents 面板 Nebula 混在 "Global Agents" 组（`agentManager.js:159-179`），仅徽标处特判 `a.name !== 'Nebula'`（`agentManager.js:105`） | 本仓代码行号 |
| 用户裁定（2026-08-24） | ①现图标与实体语义不符 ②Nebula 是编排者（理解意图/派发/验收），与 standalone agent（功能执行者）本质不同，需独立区分 | 本任务背景 |

**取舍说明**：范式之间无冲突；全部选择以「语义直指 + 克制专业」为准，放弃更有表现力但语义偏移的候选（见 §2 备选淘汰）。

## 2 图标映射（核心决策）

lucide vendor 版本 v0.454（`vendor/lucide.min.js`），下列图标**已全部逐一验证存在**于该 vendor（PascalCase 键：Workflow/Bot/Users/Orbit），**零新依赖**。

| 实体 | 选定图标 | data-lucide | 语义匹配理由 | 替换现状 |
|---|---|---|---|---|
| **Team**（持久项目级协作） | `users` | `users` | 多人协作直指 Team 的「持久、多角色项目组织」本质；lucide 官方归类 people/teams | 替换 `network`（当前读作网络拓扑/图结构，与「协作团队」无语义关联） |
| **Flow**（DAG 流水线） | `workflow` | `workflow` | lucide 官方工作流图标：节点+有向连线，即 DAG 本体；语义与 Flow 定义一一对应 | 替换 `git-branch`（版本控制分支隐喻，属相邻领域误借） |
| **Agent**（无状态功能个体） | `bot` | `bot` | 单个自动化执行体；业界 AI agent 通用符号（区别于「人」）；单数形态纠正当前 `users` 的复数误配 | 替换 `users`（复数人群，恰恰应是 Team 的图标） |
| **Nebula**（编排者，非并列实体） | `orbit` | `orbit` | 中心体+环绕轨道 = 编排/调度的空间隐喻：Nebula 居中理解意图，众 agent 环绕受其派发；克制无攻击性 | 新增身份标识（此前无专属图标，用字母头像 "N"） |

**备选淘汰记录**（防复审重复争论）：

- Team：`users-round`（风格更圆润但与现有 `users` 系线条粗细不一致，vendor 内混用显杂）→ 弃
- Flow：`waypoints`（散点路径，DAG 的方向性弱）、`git-merge`（仍是 git 隐喻）→ 弃
- Agent：`cpu`（硬件隐喻，偏底层）、`user-cog`（「用户设置」歧义大）→ 弃
- Nebula：`sparkles`（泛「AI 魔法」语义，无编排指向）、`crown`（等级/支配意味，违背协作调性）、`compass`（导航≠编排）→ 弃

**迁移注意**：`users` 图标从 Agents 入口**平移**到 Teams 入口；`network`、`git-branch` 从 activity bar 退役（Grep 全仓确认二者仅用于 `index.html:64-65`，无其他引用，退役无残留风险）。

## 3 布局与信息架构

### 3.1 activity bar（`index.html:64-66`）

仅替换 `data-lucide` 值，DOM 结构、尺寸、样式不变：

```html
<button id="teams-btn"  class="activity-btn" title="Teams">  <i data-lucide="users"></i></button>
<button id="flows-btn"  class="activity-btn" title="Flows">  <i data-lucide="workflow"></i></button>
<button id="agents-btn" class="activity-btn" title="Agents"> <i data-lucide="bot"></i></button>
```

### 3.2 Agents Canvas 面板（`agentManager.js renderAgentManager`）——Nebula 独立分区

分组顺序由现状「Global Agents / Team: × / Flow: ×」改为**四段式**：

```
┌─ Agents ─────────────────────────────┐
│ ◯ 编排者                    ← 组头：orbit 14px + 文字，sapphire 色
│ ┌──────────────────────────────────┐ │
│ │ ◯ Nebula            [编排者]     │ │ ← Nebula 卡：orbit 图标替代字母头像 + 徽标
│ │   理解意图 · 派发任务 · 验收      │ │
│ └──────────────────────────────────┘ │
│ ──────────────────────────────────── │ ← 组间既有间距（无新增分隔线）
│ Standalone Agents         ← 组头：muted（原 "Global Agents" 改名，Nebula 已移出）
│ ┌──────────────────────────────────┐ │
│ │ C Coder            [可直接委派]  │ │ ← standalone 卡保持现状
│ └──────────────────────────────────┘ │
│ Team: nebflow-project                │
│ …                                    │
│ Flow: code-review                    │
│ …                                    │
└──────────────────────────────────────┘
```

实现锚点（供实现方定位，非代码交付）：

1. `renderAgentManager` 分组逻辑：从 `global` 数组中取出 `a.name === 'Nebula'` 单列「编排者」组，置顶；剩余 global standalone 归入 "Standalone Agents" 组
2. `renderAgentCard`（`agentManager.js:99-117`）：Nebula 卡头像位渲染 `<i data-lucide="orbit">`（16px）替代 `initial` 字母；徽标位渲染「编排者」pill
3. 组头 DOM：`<i data-lucide="orbit" style="width:14px;height:14px">` + 文字，新 class `.agent-mgr-group-header.orchestrator`
4. 现有特判 `a.name !== 'Nebula'`（`agentManager.js:105`）保留——Nebula 永不显示「可直接委派」徽标

### 3.3 导航栏 agent 列表（`#nav-agent-list`，`sidebar.js renderAgentList:146-171`）

```
┌────┐
│ ◯  │ ← Nebula 置顶：orbit 20px 替代字母头像，class .nav-agent-orchestrator
│Nebu│
│ ╌╌ │ ← 细分隔线 1px var(--glass-border-dark)，宽 24px 居中
│ C  │
│Code│ ← standalone agents 保持现状（字母/自定义头像）
│ …  │
└────┘
```

- `renderAgentList` 内把 Nebula 排首（sort 时 Nebula 权重最高），其后插入 `.nav-agent-separator` 分隔元素
- Nebula 项图标：`<span class="nav-agent-icon"><i data-lucide="orbit"></i></span>`，svg 20px（与 `.nav-agent-icon svg` 既有规格一致，nav.css:66-68）

## 4 交互状态机

本特性全部为**静态标识**，无新增交互态；图标继承所在控件的既有状态，不单独定义：

| 场景 | 默认 | hover | active | 说明 |
|---|---|---|---|---|
| activity bar 按钮图标 | `var(--color-text-muted)`，静止 | 继承 `.activity-btn:hover`（Pattern A 玻璃浮现） | 继承 `.activity-btn.active` 既有样式 | 不为新图标新增任何状态样式 |
| Nebula 卡 / 组头 | 见 §5 配色 | 继承 `.agent-mgr-card:hover` | — | 徽标非交互元素 |
| 导航栏 Nebula 项 | 同其他 nav-agent | 继承 `.nav-agent:hover` | 继承 `.nav-agent.active` | orbit 图标随控件文字色变化（`currentColor`） |

动效：**无新增动效**。分隔线、组头、徽标全部静态；无需 `prefers-reduced-motion` 适配（无动画可减）。

## 5 视觉规格（全部既有 token，零新增）

| 元素 | 规格 | token / 值 |
|---|---|---|
| activity bar 图标尺寸 | 20×20px | 沿用 `.activity-btn svg` 现状（nav.css） |
| Agents 面板组头图标 | 14×14px | 内联 width/height（沿用 `sidebar.js:3144` 既有写法） |
| Nebula 卡 / 徽标图标 | 16×16px | 卡片列表项规格 |
| 面板头 / 详情页头 | 24–32px | 同一 lucide 名，仅改尺寸，stroke-width 恒为 2（lucide 默认，vendor 统一） |
| 实体图标颜色（Team/Flow/Agent，静止态） | muted 单色 | `var(--color-text-muted)`（现状继承，不改动） |
| Nebula 编排者组头文字 + orbit 图标 | sapphire 品牌蓝 | `rgb(var(--sapphire))` = `rgb(91,127,191)`（sapphire.css:7，亮暗同值） |
| 「编排者」徽标 pill | sapphire 淡底 + sapphire 字 | 底 `rgba(91,127,191,0.10)` + 字 `rgb(91,127,191)`（**复用** sidebar.css:1461-1462 既有配方，非新色值） |
| 分隔线 | 1px 极淡边 | `var(--glass-border-dark)`（sapphire.css:19/42 亮暗双值） |
| 组头字重 / 字号 | 600 / 12px（组头）；卡片名 500 | 铁律 3：标题 600、按钮/标签 500；组头小字沿用 `.agent-mgr-group-header` 现状 |
| 圆角 | 徽标 pill 沿用现有 `.agent-mgr-standalone-badge` 圆角 | 不新增 |

**颜色纪律声明**：唯一引入的「新」用色 = `--sapphire` 既有 token 的新使用位置（组头、徽标），色值本身与 sidebar 既有 sapphire 用法（sidebar.css:851/1461/1531 等 8 处）完全一致，符合案例 001 的「零新颜色 token」纪律。亮/暗双主题由 token 自身双值保证，无需 media query 特判。

## 6 边界与异常

| 场景 | 规格 |
|---|---|
| Team/Flow 名超长（组头 `Team: <name>`） | 组头单行 `text-overflow: ellipsis; white-space: nowrap; overflow: hidden`，完整名放 `title` 属性 |
| Nebula 名称 i18n | 「Nebula」为专有名词不翻译；「编排者」组头与徽标走 i18n（key 见下） |
| **i18n key（规格锁死，案例 001 教训①）** | 新增三个 key：`agents.group.orchestrator`（编排者/Orchestrator）、`agents.group.standalone`（Standalone Agents，替换现 "Global Agents" 硬编码）、`agents.badge.orchestrator`（编排者/Orchestrator）。断言比对运行时 `t()` 输出，不比字面 |
| standalone 组为空（只剩 Nebula 一个 global） | "Standalone Agents" 组头不渲染（沿用现有「组空则不渲染」逻辑） |
| 无 Nebula（异常配置） | 编排者组不渲染；其余组不受影响（防御性，不报错） |
| 侧边栏折叠态 | activity bar 保持 48px 可见（nav.css:181 既有行为），三个新图标在折叠态仍是唯一实体入口，必须可读 |
| 窄视口 375px | activity bar 纵向排列无横向溢出；Agents 面板卡片已有自适应，组头 ellipsis 生效 |
| lucide vendor 缺图标（防御） | `createIconsIn` 找不到键时保留 `<i>` 占位——已在 vendor v0.454 逐一验证四图标存在，此分支不应触发；§8 A9 留断言兜底 |
| 自定义 avatar 的 agent | 不受影响（avatar 优先于字母头像的既有逻辑保留；仅 Nebula 强制 orbit，不接受自定义 avatar——编排者身份标识需稳定一致） |

## 7 无障碍

- activity bar 按钮：保留既有 `title="Teams/Flows/Agents"`；lucide 替换后的 svg 自带 `aria-hidden="true"`，按钮可访问名由 title/aria-label 提供（现状已成立，不回归）
- Nebula 组头与徽标：文字即语义（「编排者」非纯图形标识），屏幕阅读器可直接读出；orbit 图标为装饰性，`aria-hidden="true"`
- 对比度：`rgb(91,127,191)` 在 `--glass-bg` 浅色底（白 55%）上对比度约 4.6:1 ≥ 4.5:1（12px 粗体大字标准按 3:1 亦达标）；暗色底（24,28,38 @68%）上对比度约 5.8:1。muted 图标颜色沿用全局正文规范
- 键盘：无新增可聚焦元素；Agents 面板卡片 Tab 序不变（Nebula 卡仍在首位，为原列表顺序的自然前移）
- 非鼠标可达：全部标识纯展示，无 hover-only 信息（完整名同时进 `title` 与组头文本）

## 8 可断言验收点（qa-frontend 转 Playwright 用；全部为二值判断）

口径说明：「icon 已生效」= lucide 已将 `<i data-lucide="X">` 替换为 `svg.lucide.lucide-X`（vendor 替换行为，utils.js:183-209）。

| # | 断言 | 需截图 |
|---|---|---|
| A1 | `#teams-btn svg.lucide-users`、`#flows-btn svg.lucide-workflow`、`#agents-btn svg.lucide-bot` 三者均存在（3 个子断言，全过才算 PASS） | 是（activity bar 特写） |
| A2 | Agents 面板第一个 `.agent-mgr-group` 的组头文本 === 运行时 `t('agents.group.orchestrator')`，且该组内 `.agent-mgr-card` 恰好 1 个且 `data-agent="Nebula"` | 是 |
| A3 | Nebula 卡不出现在其他任何 `.agent-mgr-group`；编排者组内不存在 `.agent-mgr-standalone-badge`；Standalone 组内每个 global standalone 卡存在该徽标（现状不回归） | 是 |
| A4 | 编排者组头 computed `color` === `rgb(91, 127, 191)`（亮、暗主题各跑一次，值相同） | 否 |
| A5 | Nebula 卡头像位为 `svg.lucide-orbit`（非字母 "N" 文本节点）；卡片内存在文本 === `t('agents.badge.orchestrator')` 的徽标元素 | 是 |
| A6 | `#nav-agent-list` 第一个 `.nav-agent` 的 `data-name="Nebula"` 且内含 `svg.lucide-orbit`；其后紧邻一个 `.nav-agent-separator` 元素 | 是 |
| A7 | 三个 activity 按钮 svg 静止态 computed `color` === `getComputedStyle(document.body).getPropertyValue('--color-text-muted)` 解析值（亮/暗各一次） | 否 |
| A8 | 分隔线 computed：`height` ≤ 1px 且 `background-color` 非透明（`--glass-border-dark` 解析值），亮/暗各一次 | 否 |
| A9 | 全文档不存在 `<i data-lucide="users|workflow|bot|orbit">` 未替换残留（vendor 缺图标防御兜底） | 否 |
| A10 | 375px 视口：activity bar 无横向溢出（`scrollWidth <= clientWidth`）；编排者组头 `scrollWidth <= clientWidth`（ellipsis 生效） | 是 |
| A11 | 零新依赖回归：`document.querySelectorAll('script[src*="vendor/"]')` 数量与基线一致（不新增 vendor script） | 否 |

**截图清单（供视觉评审）**：①activity bar 三图标特写（亮/暗）②Agents 面板全览含编排者分区（亮/暗）③导航栏 Nebula 置顶 + 分隔线特写（亮/暗）④375px 窄视口 Agents 面板。

## 9 参考链接汇总

- lucide 图标库：https://lucide.dev/icons/（users / workflow / bot / orbit 四图标官方语义页）
- Apple HIG · Icons：https://developer.apple.com/design/human-interface-guidelines/icons
- VS Code UI 参考：https://code.visualstudio.com/docs/getstarted/userinterface
- 本仓证据：`index.html:64-66`（现映射）· `agentManager.js:99-117,146-208`（Agents 面板）· `sidebar.js:146-171`（导航栏列表）· `sapphire.css:7-51`（token）· `sidebar.css:1461-1462`（sapphire 徽标配方）· `nav.css:57-138,181`（尺寸/折叠态）
- 内部规范：`~/.nebflow/skills/nebflow/visual-style/SKILL.md`（铁律）· `~/.nebflow/skills/design-system/SKILL.md` 案例 001（断言口径纪律）

---

### 版本日志

- 2026-08-24 draft：初版。核心决策：Team=`users` / Flow=`workflow` / Agent=`bot` / Nebula=`orbit`；Nebula 在 Agents 面板置顶独立「编排者」组 + 导航栏置顶分隔；零新 token、零新依赖。
