> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Flow Map 图谱化重设计——固定视口 + 三级渐进信息（Obsidian Graph View 范式）

> **状态**：⛔ 已封存 2026-09-03 01:58，作者拍板转 Galaxy 风格方向，本稿留档不实施。继任稿：`20260903_flowmap-graphview-galaxy-design.md`（v2，Galaxy View 范式）。本文 §3 相机状态机数学、§4 工程防抖结论、§8 断言口径仍可被 v2 引用复用。
> ~~**状态**：draft v1.0（待作者确认冻结）~~
> **作者需求原文**（2026-09-03）：「画面固定比例。不设置滚动条。通过交互来查看具体细节。基础的有一些信息，hover 能放大一点，hover 后点击，能再次放大查看详细信息。」
> **日期**：2026-09-03 · **产出**：design-engineer · **交付物**：本规格书 + 可交互原型 `assets/20260903_flowmap-graphview/prototype.html`
> **基线**：主仓 HEAD（flowMapTab.js / flowMap.css / flowCss.js / flowViewers.js 现状），deps 虚线边按 `20260902_flowmap-engine-evolution-design.md` §1（即将实施）。
> **边界**：本文档为设计规格 + 原型，不改产品代码；实施由 nebflow-project 按本文 §10 改造清单派发。

---

## 0. 一句话目标

把 Flow Map 从「滚动条拉动画布」改造为 **Obsidian 图谱式固定视口**：全图自适应收进视口（fit）、零滚动条，滚轮 CAD 式鼠标跟随缩放 + 拖拽平移 + 全程平滑动画；节点信息三级渐进（默认紧凑卡 → hover 局部放大+邻接聚焦 → 点击居中放大+玻璃详情面板），全部既有语义（六状态/WS 实时/分组/TTL/双边类型/双主题/reduced-motion）无损保留。

---

## 1. 参考与依据

| 来源 | 提炼的可执行规则 | 链接 |
|---|---|---|
| Obsidian Graph View | ① 视口固定无滚动条，滚轮缩放锚定光标、拖空白平移、双击空白回正；② hover 节点 = 该节点 + 直接邻居 + 连接边高亮、其余淡出（focus+context）；③ 节点尺寸随连接度；④ 标签随缩放级别渐进显隐（缩太远标签先消失） | https://help.obsidian.md/plugins/graph |
| d3-zoom（CAD 式缩放的参考实现） | 缩放不变量 = 「缩放前后光标下的内容点屏幕位置不变」；transform = translate·scale，缩放补偿公式 `T_new = P − (P − T_old)·k`；wheel 直接操作零动画、程序化 fit/focus 走插值动画 | https://d3js.org/d3-zoom |
| 本仓既有裁定 | CAD 式鼠标跟随是作者硬偏好（固定中心缩放已被明确否决），数学与边界处理沿用 `canvas-zoom-follow-cursor-spec.md` 方案 A | ~/.nebflow/docs/Nebflow/canvas-zoom-follow-cursor-spec.md |
| visual-style 铁律 | 详情面板/弹层必须毛玻璃（blur + 半透明）、禁 overlay 背景暗化；可交互控件玻璃质感；字重 400/600/500；低调克制 | ~/.nebflow/skills/nebflow/visual-style/SKILL.md |
| deps 边设计（即将实施） | 输出边实线三态（delivered/inflight/idle）+ deps 边虚线 `3 4` 双重编码 + 图例 | 20260902_flowmap-engine-evolution-design.md §1 / 20260902_deps-dependency-connection-design.md §D.1 |
| WAI-ARIA APG | 节点 role=button + aria-label；详情面板 role=dialog + Esc 关闭 + 焦点管理 | https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/ |

**范式取舍说明**：Obsidian 力导向布局**不采纳**（见 §2.4 权衡），只采纳其**交互壳**（固定视口/缩放/平移/hover 聚焦/渐进标签）；布局保留 DAG 层次——Flow Map 是执行拓扑，方向语义（上游→下游 = 时间/数据流）是核心读图线索，力导向会抹掉它。

---

## 2. 布局与信息架构

### 2.1 视口结构（零滚动条）

```
flowmap-view（容器，overflow:hidden，撑满标签页/就地视图）
├── fm-viewport（固定视口 = 相机，overflow:hidden，监听 wheel/pointer）
│   └── fm-world（世界坐标系，transform: translate(tx,ty) scale(s)，含既有 SVG 边层 + 节点层）
├── fm-detail（右侧详情面板，毛玻璃，选中态出现）
├── fm-hud（右下：−/百分比/+/⤢fit 玻璃控件簇）
└── fm-legend（左下图例：输出边三态 + deps 虚线，hover 前后常驻低调）
```

- `.solar-scroll`（overflow:auto）退场；**任何容器层级不得出现滚动条**（A1 断言）。
- 世界坐标 = 现有 layoutNodes 输出坐标原样使用；相机 transform 全由 JS 管理（单点 `applyCamera()`）。
- 节点数多时**整体缩小**（fit scale 变小），永不出现滚动（作者裁定）。

### 2.2 布局：DAG 层次紧凑化（推荐案 A）

- 保留现有 `layoutNodes` 层次算法（上游→下游自上而下），**间距紧凑化**适配紧凑节点：
  - `NODE_W 150→120`、`NODE_H 108→44`（紧凑单行卡）、`V_SPACING 180→96`、`H_SPACING 210→156`、`PAD 70→48`。
- 紧凑后 12 节点 4 层图 ≈ 620×340 世界坐标，典型 800×560 视口 **scale≈1 原生放下**；30 节点图 fit 到 ~0.55 仍全图可见，符合「节点数量多时整体缩小」。
- deps 边纳入层次计算（按 20260902 deps 设计 §D.2：有 deps 的节点不是根）。

### 2.3 三级渐进信息

| 级别 | 触发 | 展示 |
|---|---|---|
| **L0 默认** | — | 紧凑卡：状态点（8px，色+动效编码六状态）+ 节点名；failed ✗ / blocked ⚑ 附加图标（需注意力的两态）。全图一屏可见 |
| **L1 hover** | 指针入节点 | ① 该节点 scale 1.12 + 投影抬升；② 直接邻居 scale 1.06 + 邻接边高亮（加粗提色），其余节点 opacity→0.15、边→0.06（focus+context）；③ 离开 80ms 宽限后还原 |
| **L2 点击（选中）** | click（≤3px 位移） | ① 相机动画：节点居中 + scale 提到 max(当前, 1.6)（已 ≥1.6 则只居中）；② 右侧 300px 毛玻璃详情面板滑入：状态徽章、agent、worktree、TTL 实时倒计时、上/下游邻接（可点击跳转）、blocked 反馈面板（category/detail/suggestion/blockCount）、result 全文（滚动）；③「完整结果」按钮 → 复用现有 `flowViewers.openNodeResultViewer` 浮层（零重写） |

### 2.4 布局策略权衡（两案对比）

| | 案 A：DAG 层次紧凑化（**推荐**） | 案 B：Obsidian 力导向 |
|---|---|---|
| 方向语义 | ✅ 上→下 = 执行流/数据流，一眼读出拓扑顺序 | ❌ 无方向感，执行拓扑读成「一团关系」 |
| WS 实时稳定性 | ✅ 增删节点只局部移位，其余节点纹丝不动 | ❌ 模拟重收敛导致全图「呼吸漂移」，实时更新时不断晃动 |
| 确定性 | ✅ 同一拓扑永远同一布局（跨刷新/跨人一致） | ❌ 初速度/随机种子导致布局不可复现 |
| 性能 | ✅ O(V+E) 一次计算 | ❌ 每次变更持续模拟耗电 |
| 有机观感 | △ 工整（用 bezier 边 + 紧凑间距缓解） | ✅ 有机 |
| 结论 | **采纳**：层次为基底 + Obsidian 交互壳 | 否决：知识图谱无方向语义所以力导向成立；执行拓扑不成立 |

### 2.5 节点分组（Nebula 多项目视角，预留）

单项目图无分组。多项目聚合图（未来）：按项目分**列带**（每个项目一条水平列带，带首项目名 chip，带内保持层次布局），项目间列距 2×H_SPACING，列带背景 3% 透明度色差 + 顶部标签。数据面 `NodeList` 已带 project 归属，纯前端布局扩展，不影响本设计其余部分。

### 2.6 设计 token 纪律

**零新颜色 token**。全部引用既有变量：`--color-bg/--color-surface/--color-text/--color-text-muted/--color-text-dim/--color-border/--color-primary(#07c160)/--color-error(#f44336)/--color-success(#4caf50)/--color-warning(#ff9800)/--glass-bg/--glass-blur/--glass-border/--sapphire(91 127 191)/--amber`。唯一新增是**布局尺寸常量**（§2.2）与两个结构类名（`fm-viewport/fm-world/fm-detail/fm-hud/fm-legend`）。

---

## 3. 交互状态机

### 3.1 相机（viewport）状态

| # | 当前态 | 事件 | 动作 | 次态 |
|---|---|---|---|---|
| C1 | autoFit | 布局变化（WS 增删节点） | 重算 fit，280ms 动画过渡 | autoFit |
| C2 | autoFit | wheel / 拖拽 | §3.2 / §3.3 对应动作 | **userNav**（autoFit 挂起） |
| C3 | userNav | wheel(Δy) | 锚点=光标 P：`s′=clamp(s·k)`，`tx′=P.x−k(P.x−tx)`，`ty′=P.y−k(P.y−ty)`，k=exp(−Δy·0.0015)（**即时，无动画**） | userNav |
| C4 | 任意 | pointerdown→移动>3px | 平移 tx,ty += Δ（即时跟随）；节点上起拖同样平移（节点位置由布局管理，不支持拖节点改布局） | userNav |
| C5 | userNav | 点击 ⤢fit / 双击空白 | 动画回 fit（280ms） | autoFit |
| C6 | 任意 | L2 选中节点 | 相机动画：目标居中 + s′=max(s,1.6)（280ms） | userNav（选中态相机锁定语义不变） |
| C7 | 任意 | 键盘 Tab 聚焦到视口外节点 | 相机动画把焦点节点带入居中 | 不变 |
| C8 | 任意 | 缩放触界 | clamp 到 [fitScale×0.4, 4.0]，越界 wheel 无效果（无橡皮筋） | 不变 |

### 3.2 节点状态机

| # | 当前态 | 事件 | 动作 |
|---|---|---|---|
| N1 | L0 默认 | pointerenter | → L1：节点 `.fm-hi`（scale1.12+投影）；邻居+邻接边 `.fm-hi`；其余 `.fm-dim` |
| N2 | L1 | pointerleave | 80ms 宽限期后还原 L0（跨缝隙不闪烁）；期间进入新节点则直接切换高亮目标 |
| N3 | L0/L1 | click（位移≤3px 且 <400ms） | → 选中：C6 相机动画 + 详情面板滑入；节点 `.fm-selected`（sapphire 描边） |
| N4 | 选中 | 点击空白 / Esc / ✕ | 面板滑出（260ms）；节点还原；**相机不动**（保留用户导航位）；焦点归还节点 |
| N5 | 选中 | 点击另一节点 | 切换选中：面板内容换、相机重新居中 |
| N6 | 选中 | 详情面板「上游/下游」条目点击 | 相机飞向该邻居 + 切换选中（图谱跳转闭环） |
| N7 | 任意 | WS 状态迁移 | 状态色/图标 350ms 平滑过渡；completed 时出边 idle/inflight→delivered 渐变；节点到期 → 淡出移除（现有语义） |
| N8 | 任意 | 键盘 focus + Enter/Space | 等价 N3 |

### 3.3 输入设备差异

| 输入 | 行为 |
|---|---|
| 鼠标滚轮 | 缩放（CAD 式跟随光标，C3）——图无滚动语义，滚轮**只做缩放**（Obsidian 同款裁定） |
| 触控板双指滚动（无 ctrl） | 同滚轮=缩放（与 C3 同路径；Safari/Chrome 均合成 wheel 事件） |
| 触控板捏合 pinch | macOS 合成 ctrl+wheel → 同 C3；Safari `gesturestart/gesturechange` 用 `pointermove` 追踪的最近光标位作锚点（沿 canvas-zoom-follow-cursor-spec §3.1 方案） |
| 触屏 | 单指拖=平移；tap=选中；双指捏合=以两指中心缩放（touch 事件计算，P1） |
| 键盘 | Tab 循环节点；Enter/Space 选中；Esc 关面板；+/− 缩放（视口中心为锚）、0=fit |

### 3.4 选中详情面板内容（= openNodeResultViewer 数据超集）

```
┌ fm-detail（毛玻璃，无遮罩）────────┐
│ 节点名                    [状态徽章] ✕ │
│ agent · wt badge · ⏱ TTL(实时)      │
│ 上游: ●节点A ●节点B   下游: ●节点C    │  ← 可点击跳转（N6）
│ [blocked 反馈面板: category/说明/建议/│  ← 仅 blocked 态
│  已被阻断 N 轮]                      │
│ 节点结果（滚动区，pre-wrap）          │
│ [完整结果] 按钮 → openNodeResultViewer│
└─────────────────────────────────────┘
```

---

## 4. 动效规范

| 交互 | 属性 | 时长 | 缓动 | reduced-motion |
|---|---|---|---|---|
| fit / focus 相机 | translate+scale | 280ms | cubic-bezier(0.33,0,0.2,1) | 0.01ms |
| 滚轮缩放/拖拽平移 | translate+scale | **0（直接操作）** | — | 同（直接操作无动画本就成立） |
| hover 放大 | transform scale 1→1.12 | 160ms | cubic-bezier(0.33,0,0.2,1) | 0.01ms |
| 邻接高亮/淡出 | opacity + stroke | 180ms | ease | 0.01ms |
| 节点入场（WS 新建） | opacity 0→1 + scale .85→1 | 350ms | ease-out | 0.01ms |
| 节点出场（到期/移除） | opacity→0 + scale .8 | 300ms | ease-in | 直接移除 |
| 布局移位（WS） | left/top | 400ms | cubic-bezier(0.645,0.045,0.355,1)（沿用现有） | 0.01ms |
| 边生长（WS 新边） | stroke-dashoffset | 450ms | cubic-bezier(0.33,0,0.2,1)（沿用现有） | 跳过 |
| 详情面板 | translateX 100%→0 | 260ms | cubic-bezier(0.33,0,0.2,1) | 0.01ms |
| running 脉冲 | box-shadow 环（沿用 flow-pulse） | 1.6s ∞ | ease-out | 静态点 |
| inflight 行军蚁 | stroke-dashoffset（沿用 flow-dash） | 1.2s ∞ | linear | 静态虚线 |

**防抖关键参数**：hover 切换经 rAF 合帧；pointerleave 80ms 宽限；拖拽/点击阈值 3px/400ms；相机动画可被新一轮输入随时打断并从当前值续跑（不回起点，沿用 `animateGTranslate` 续跑模式）。

---

## 5. 视觉规格

### 5.1 六状态视觉映射（语义无损，紧凑化载体）

| 状态 | 点 | 点动效 | 节点卡 | 图标 | 沿用 |
|---|---|---|---|---|---|
| wiring / pending | `--color-text-muted` 40% | 无 | 卡整体 opacity .55 | — | solar pending 虚线环语义 → 点虚线描边 |
| running | `--color-primary` | flow-pulse 1.6s | 满色 | — | flow-pulse 现有 |
| completed | `--color-success` | 无 | 满色 | — | — |
| failed | `--color-error` | 无 | 满色 | ✗（9px，error 色） | solar ✓/✗ 语义 |
| cancelled | `--color-text-muted` | 无 | 满色 | —（muted 色） | solar — 语义 |
| blocked | `rgb(var(--amber))` | flow-pulse 1.6s（amber） | amber 描边 + 淡琥珀底光（沿用现有 blocked 卡样式） | ⚑（amber） | 20260902 反馈路径 §4.2 |

### 5.2 紧凑节点卡（L0）

- 尺寸 120×44（内容 `min-height` 自适应不裁字，同现 `.fm-node` 纪律）；圆角 10px；毛玻璃 `--glass-bg` + `blur(--glass-blur)` + `--glass-border` 1px + 投影 `0 2px 8px rgba(0,0,0,.06), 0 4px 16px rgba(0,0,0,.04)`（同 solar-node 现值）。
- 布局：`[状态点 8px] [节点名 600 12px 单行省略] [⚑/✗ 可选]`；hover title 属性保留全名。
- 节点名 = id（现状语义：主行 nodeId，agent 副行）——紧凑化后 agent 移入 L2 详情；hover 的 aria-label 含 agent。

### 5.3 边（三态 + 双类型，全部沿用 deps 设计已定值）

- 输出边：delivered=`rgb(var(--sapphire)/.75)` 2px 实线；inflight=muted 1.5px 虚线 5 4 + flow-dash；idle=`--color-border` 1.5px 实线；箭头 3px 圆点随状态色。
- deps 边：`stroke-dasharray: 3 4` + 三态色（delivered=success、inflight=warning 蚁、idle=border）；箭头空心 2.5px。
- hover 邻接态：邻接边 stroke → `rgb(var(--sapphire)/.9)` 2.5px；非邻接 opacity .06。

### 5.4 详情面板（毛玻璃，禁遮罩）

- 宽 300px；`--glass-bg` + `backdrop-filter: blur(var(--glass-blur)) saturate(1.15)`；左边框 1px `--glass-border`；圆角左上/左下 16px；投影 `0 8px 32px rgba(0,0,0,.16)`。
- **无 overlay 遮罩层**：面板背后图谱仍可交互（点击节点=切换选中 N5，铁律 1 禁暗化禁模糊背景）。
- 字重：标题 600 13px / 正文 400 12px / 标签行 600 9px 大写（沿用 `.flow-agent-block-label` 基线）；按钮 500（玻璃质感同 `.flow-viewer-save` 家族）。
- HUD 控件簇与图例：同 `.glass-control` 家族（blur 8px、立体边缘、淡边框 .15、hover 提亮），26px 方形，低调右下/左下角。

### 5.5 双主题

全部颜色经既有 token 引用，亮暗自动切换（同 sapphire 双主题机制）；边/点的暗色可见性由 token 层保证，不写死色值。暗色下毛玻璃 `--glass-bg` 取暗色值。

---

## 6. 边界与异常

| 场景 | 行为 |
|---|---|
| 空态/未挂载/全部归档/加载失败 | 沿用现有四态文案，居中渲染在固定视口内（无滚动）；HUD/图例隐藏 |
| 30+ 节点 | fit scale 自动变小；hover 放大是 CSS transform（GPU 合成，与节点数无关）；邻接高亮建 O(V+E) adjacency map 一次预计算，hover 时 O(deg) 查表 |
| 61–150 节点（粗模式） | 渐进标签：scale < 0.7 时节点名 opacity→0（只留状态点），≥0.7 恢复——Obsidian「缩太远标签先消失」语义 |
| >150 节点 | 超出本设计 DOM 方案预算，P2 评估 canvas 渲染（Nebflow 现实规模为几十节点，不阻塞） |
| hover 闪烁 | §4 防抖参数（80ms 宽限 + rAF 合帧 + 目标变更才重算类名） |
| 缩放极值 | C8：[fitScale×0.4, 4.0]；fitScale 记录于容器 `data-fit-scale` |
| 超长节点名/result | 节点名单行省略 + title；result 详情面板内滚动区 max-height 320px |
| WS 快速连发 | 沿用现有 diff 管线 + rAF 泵；用户手动导航中不触发 autoFit（C2 挂起），只有 autoFit 态才随布局重算 fit（C1） |
| 双主题切换瞬间 | 无 JS 参与，token 层自动；相机/选中态不丢 |
| 触控板误滚动 | 图内无滚动语义，wheel 一律缩放（可预期，无「滚跑了」问题）；配合 C8 触界钳制 |

---

## 7. 无障碍

- 节点 `tabindex=0` `role=button` `aria-label="节点名，状态，agent"`；焦点环 `outline: 2px solid rgb(var(--sapphire)/.6); outline-offset: 2px`（克制但可见）。
- Enter/Space 选中（N8）；选中后焦点移入详情面板关闭钮，Esc/关闭后焦点归还节点（APG dialog 模式）。
- Tab 聚焦到视口外节点 → 相机带入（C7），键盘用户可达全部节点。
- 详情面板 `role=dialog` `aria-label="节点详情"`；面板内 result 区可滚动可全选。
- 对比度：正文 `--color-text`（≥12:1）；次要标签 `--color-text-dim`（亮主题 4.6:1 过 4.5 档），muted 仅用于装饰性 meta。
- 信息双通道：状态=颜色+图标/动效（色弱可辨）；hover 级信息全部有键盘等价路径（选中面板）。
- `prefers-reduced-motion: reduce` → §4 末列全部降级；脉冲/蚂蚁动画停。

---

## 8. 可断言验收点（供 qa-frontend 转 Playwright）

| # | 断言（二值） | 截图 |
|---|---|---|
| A1 | 视口容器 `overflow` computed = hidden（零滚动条渲染），页面根不可滚动（`documentElement.scrollWidth === clientWidth` 且 body 同）；`fm-world` 内容溢出属相机视口常态不计入（口径：滚动条与可滚动性，非内容几何） | 否 |
| A2 | 初始 fit：全部 `.fm-node` 的 `getBoundingClientRect()` 落在视口 rect 内（容差 2px）；容器 `data-fit-scale` 为正数 | 是 |
| A3 | CAD 锚点：在视口点 P 派发 wheel 后，P 点命中的节点 id 不变（elementFromPoint 前后一致），或无节点时世界反算坐标位移 ≤2px | 否 |
| A4 | hover 节点 X：X 含 `.fm-hi` 且 computed transform scale ≥1.1；直接邻居含 `.fm-hi`；非邻居含 `.fm-dim` 且 computed opacity ≤0.2 | 是 |
| A5 | click 节点：详情面板可见（`[data-fm-detail-state="open"]`）；节点中心与视口中心距离 ≤60px（动画结束后）；`body` 无新增遮罩元素（铁律：禁 overlay） | 是 |
| A6 | 面板内 name/agent/status 文本 === 模拟载荷字段 | 否 |
| A7 | deps 边 path 含 `fm-edge-deps` 类且 computed `stroke-dasharray ≠ none`；输出边 delivered 为实线（dasharray = none） | 否 |
| A8 | Esc 后面板关闭（state≠open）、焦点回到该节点（document.activeElement） | 否 |
| A9 | 连续 20 次放大 wheel 后 scale === 4.0 封顶；连续缩小至 fitScale×0.4 封底 | 否 |
| A10 | 双击视口空白 → 动画后所有节点重回视口内（A2 同款 rect 断言） | 否 |
| A11 | reduced-motion 下全部 transition-duration ≤0.01s | 否 |
| A12 | 亮/暗双主题 ×（默认/hover/选中）三态截图齐全，无文字溢出卡片/面板 | 是 |
| A13 | WS 模拟：注入 nodeCompleted 后对应出边 class 由 inflight→delivered，节点色平滑过渡，无整页重建（`.fm-world` 子元素复用断言） | 否 |
| A14 | 键盘：Tab 到视口外节点后该节点进入视口（C7）；Enter 选中等效 A5 | 否 |

---

## 9. 原型与自检

`assets/20260903_flowmap-graphview/prototype.html`——单文件零外请求：

- 模拟数据 12 节点：wiring×2、pending×1、running×2、completed×5、failed×1、blocked×1；含 1 条 deps 虚线边（环境准备⇢性能基准，已满足态）、1 个 barrier ×2（集成联调）、终态节点 TTL 实时倒计时。
- 全部交互真实可操作：滚轮 CAD 缩放 / 拖拽平移 / hover 聚焦 / 点击选中+详情面板 / Esc / 双击 fit / HUD 控件 / 键盘 Tab+Enter+±0 / reduced-motion / 亮暗主题切换（演示开关，产品走系统偏好）。
- 演示控制（顶栏，仅原型）：「模拟完成」注入 WS 式链路（n4/n5 完成 → 出边 inflight→delivered 渐变 → barrier 结算 n7 自动启动 running 脉冲）。
- 自动化参数：`?theme=dark|light&hover=<id>&select=<id>&noCamera=1` 供截图/断言直接进入指定状态。

**自检记录（2026-09-03，Playwright 走查 16/16 PASS、控制台零错误）**：A1 零滚动条（口径见 §8 注）· A2 初始 fit 12 节点全可见 fitScale=1.15 · A3 CAD 锚点（光标下节点 wheel 后不变）· A4 hover 两级高亮（自身 1.120 / 邻居 1.060 / 其余 0.15）· A5 点击居中 dist=0px · A6 面板数据=载荷 · A7 deps 虚线 3 4 + 输出实线 · A8 Esc 关闭 · A9 极值 clamp（4.0 / fit×0.4）· A10 双击 refit · A11 reduced-motion 1e-05s · A12 双主题三态截图（`shots/`）· A13 WS 链路（出边档位迁移 + barrier 结算 + 脉冲，零整页重建）· A14 键盘 focus 带入 + Enter 选中。走查脚本：`shots/verify-flowmap-proto.mjs`。
**口径教训（案例库坑③同型）**：A1 原写法 `viewport.scrollWidth===clientWidth` 误伤相机视口常态（世界坐标溢出 `overflow:hidden` 容器属正常，非滚动条）——断言口径改为「overflow:hidden 渲染 + 页面根不可滚」。

---

## 10. 实施影响面（改造清单，供派发）

| 文件 | 改动 | 关键点 |
|---|---|---|
| `flowMapTab.js` | **主改造** | ① 渲染模板：`.solar-scroll` → `fm-viewport > fm-world` + detail/HUD/legend 骨架；② 布局常量紧凑化（§2.2）；③ `nodeHtml` 紧凑卡（点+名+⚑/✗，result/TTL/barrier 摘要移出卡面）；④ 新增相机模块 `fit/zoomAt/panBy/focusNode`（数学=canvas-zoom-follow-cursor-spec 方案 A，锚点公式见 C3）；⑤ 邻接 map + hover 委托（含 80ms 宽限/防抖）；⑥ 选中态 + 面板渲染（数据路径=现 `openNodeDetail` 不变，「完整结果」仍调 `flowViewers.openNodeResultViewer`——**flowViewers.js 零改动**）；⑦ `highlightFlowMapNode` 的 `scrollIntoView` → `focusNode` 相机居中（taskList 跳转入口自动受益）；⑧ WS diff 管线、seq/gen 双代、TTL ticker、事件订阅**全部保留**，仅 `applyNodeDiff`/`applyEdgeDiff` 的宿主从 scroll 换成 world 坐标系 |
| `flowMap.css` | **主改造** | viewport/world/detail/HUD/legend/紧凑卡/六状态点/边 dim+highlight/焦点环/面板 blocked 复用/双主题/reduced-motion 块；现 `.fm-*` 动画类（fm-enter/fm-exit/flash）语义平移 |
| `flowCss.js` | 不动 | FLOW_CSS 共享层不改；flow-map 专属样式全落 flowMap.css |
| `flowViewers.js` | 不动 | `openNodeResultViewer` 原样复用为 L2「完整结果」浮层 |
| `nodeData.js` / `ws.js` | 不动 | 数据契约与 WS 订阅不变 |
| `locales/en.js` + `zh-CN.js` | 增键 | `flowmap.detail.*`（面板标题/上游/下游/TTL/完整结果/关闭）、`flowmap.hint.zoom`、图例键（复用 20260902 deps 设计 §D.1 的 `flowmap.legend.*`） |
| `scripts/` | 新增 | `verify-flowmap-graphview.cjs`：A1–A14 断言 + 亮暗三态截图（harness 模式照抄 shot-tasklist-nodes.cjs 双主题先例，不碰 8080） |

**风险与回滚**：单点改 flowMapTab.js 渲染宿主，legacy 标签页与 projects 就地视图共用 `renderFlowMapInto`（现状即同管线），无分叉新增；回滚 = revert 单 commit。性能基线：12–60 节点 DOM 方案 <16ms/帧（transform/opacity 合成层），WS 连发由现有 rAF 泵合帧。

---

## 11. 参考链接

- Obsidian Graph View：https://help.obsidian.md/plugins/graph
- d3-zoom（缩放不变量与插值）：https://d3js.org/d3-zoom
- WAI-ARIA APG Dialog (Modal)：https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/
- 本仓：`~/.nebflow/docs/Nebflow/canvas-zoom-follow-cursor-spec.md`（CAD 缩放数学/边界，作者硬偏好出处）
- 本仓：`~/.nebflow/docs/Nebflow/20260902_flowmap-engine-evolution-design.md` §1 + `20260902_deps-dependency-connection-design.md` §D（deps 虚线边与图例）
- 本仓：`~/.nebflow/skills/nebflow/visual-style/SKILL.md`（毛玻璃/禁遮罩/玻璃控件/字重铁律）
- 既有实现：`src/main/resources/web/js/flowMapTab.js`（布局/diff 管线/TTL/WS）、`flowMap.css`（状态/边样式）、`flowViewers.js`（结果浮层）

## 版本日志

- v1.0（2026-09-03）：初稿——交互状态机 C1-C8/N1-N8、布局两案权衡（推荐 A）、动效参数表、A1-A14 断言、实施改造清单；配套原型 prototype.html。
