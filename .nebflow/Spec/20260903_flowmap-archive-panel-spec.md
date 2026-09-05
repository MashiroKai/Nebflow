> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Nebflow 产品 Flow Map 重设计——整链归档 + 归档面板 设计规格书

> **状态**：draft（待作者审定 → frozen → dispatch → shipped）· **交互原型 v3 已出**（2026-09-03 20:26 反馈修订，`assets/20260903_flowmap-archive-panel/prototype.html`，回归 88/88 PASS，修订记录见 §18；v2 记录见 §17；v1 现场修正见 §16）
> **日期**：2026-09-03（v1）→ 2026-09-03 19:20（v2）→ 2026-09-03 20:26（v3，作者五条反馈修订）
> **作者指令**：2026-09-03 13:53（§1.1）+ 2026-09-03 19:20 四条反馈（§1.1b）+ 2026-09-03 20:26 五条反馈（§1.1c）
> **规格撰写**：design-engineer（设计先行流程；本文件只定义设计，不含产品代码改动）
> **关联文档**：
> - **交互原型 v3**：`assets/20260903_flowmap-archive-panel/prototype.html`（真实快照驱动，本规格的可视化载体；回归 `regression.mjs` 88/88 PASS）
> - `20260901_project-node-contract.md`（REST/WS/数据结构契约）
> - `20260903_flowmap-graphview-v3-final.md`（v3 原型终稿——排版不用于产品，交互件结论可引用）
> - `20260902_flowmap-engine-evolution-design.md`（blocked 反馈协议、悬空节点补投递）
> - 既有裁定「完成节点显示保留 1 天」→ **本规格将其更新**（见 §1.3）

---

## 0 一句话目标

Flow Map 主图 = 「运行中 + 待运行 + **链未齐的已完成节点**」的工作面（保持现有 DAG 层级排版）；**归档单位 = 任务链**——同一次任务触发创建的整组节点全部到达终态后，**整链同一帧一起从主图消失、一起进归档**（一条整体动画，非逐节点滴入）；链未齐时已完成成员**保留在主图**（终态色卡样式，边天然连着，无锚点设计）。归档面板由**画布内右上角悬浮钮**点开（mailbox 式链条目列表）；点击主图节点或链内成员 → **右侧滑出节点详情面板**（z-index 高于归档面板、宽视口并排不错位，永远完整可见）；**点击画布空白处统一收起所有悬浮面板**；**归档条目自完成起保留 24h，到期自动清理**（徽章同步减）。

**任务拆解**：① 主图终态过滤 + 不可见上游入边的锚点呈现；② 链判定语义与链缓冲；③ 画布内归档悬浮钮 + 计数徽章；④ 归档面板（链条目/排序/展开/关闭）；⑤ 右侧详情面板；⑥ 从悬浮钮拓展开的展开收起动画；⑦ 前端数据派生与状态恢复；⑧ 边界情况；⑨ 交付物与验收。

## 1 范围、裁定基线与数据事实

### 1.1 作者指令原文（2026-09-03 13:53）

> 「Flow map 设计，还是使用目前nebflow使用的DAG层级排版，但是已完成的节点。通过右上角的归档小图标查看。归档小图标的设计参考目前的md/html 渲染/源码转换图标的设计。这样，flow map实际显示的都是运行中和待运行。归档小图标点开了之后，里面显示的是各节点的条目，一条一条，按完成时间顺序排列，就像我们mailbox那样。需要设计展开动画，看起来就是从小图标拓展开来的界面。」

### 1.1b 作者反馈原文要点（2026-09-03 19:20，v2 修订依据）

> **① 归档粒度 = 整条链**：「一个完成任务被归档应该是他整一条链路——这个节点跟下面的依赖可能三四个节点，是整条电路完成了之后一起归档，而不是一个一个归档。」→ §3.5 重写（v1 逐节点归档废弃）；面板条目 = 链（链名/节点数/完成时间，展开见成员）。
> **② 右侧详情展开必须保留**（产品既有行为）：主图点击运行中节点 → 右侧详情；归档链展开后点击链内节点 → 右侧详情展示完整结果；动画语言与展开面板一致 → §5.8 新增。
> **③ 归档按钮重做为画布内悬浮钮**：参考产品「元素选择器」悬浮设计语言（画布上悬浮的胶囊/圆钮），放在画布内部右上角，不占 nav-bar；样式与元素选择器尽量一致 → §4 重写。
> **④ 保持不变**：mailbox 式条目列表质感、展开动画从悬浮钮拓展（origin 改为悬浮钮位置）、hover 联动、双主题、禁 emoji/文字不出卡/克制专业感。

### 1.1c 作者反馈原文要点（2026-09-03 20:26，v3 修订依据）

> **① 整链一起消失（主图语义级变更，推翻 v2 逐节点流转）**：「任务节点完成不逐个消失——同一条链上的节点，整条链路全部完成后一起消失、一起进归档。」链未齐时已完成节点**保留在主图**（终态样式区分，如完成色卡），边天然连着；链最后一员终态 → 整链一起过渡进归档（一条整体动画，非逐节点滴入）。链判定语义沿用 v2（同派发批次 ≤120s）→ §3 重写。
> **② 锚点设计整体删除**：「不要有什么 Pin 的设计」——v2/v1 目标锚点小圆点全部移除；上游完成节点既已保留主图（①），锚点机制不再需要 → §3.2 废除。
> **③ 归档面板点节点 → 详情面板被挡住**：修复层级——详情面板必须完整可见（z-index 高于归档面板 + 位置错开不被覆盖）→ §5.8b 层级方案。
> **④ 全局点击空白收起面板**：所有悬浮面板（归档/详情/任何展开态）支持点击画布空白处收起（统一交互语言；与画布缩放拖拽、元素选择的冲突须规避）→ §5.10。
> **⑤ 归档条目 24h TTL**：归档条目超 24h 从面板清理（按完成时间判定；条目过期不再显示、计数徽章同步减）→ §5.9。
> **保持不变**：链条目形态、悬浮钮、右侧详情（保留但修层级）、双主题、禁 emoji/文字不出卡/克制专业感。

### 1.2 范围

- **产品前端**：主仓 `src/main/resources/web/`（`flowMapTab.js` 等），就地视图（`projectTab.js openFlowMapInPlace`）与 legacy 独立标签页（`flowMapTab.js renderFlowMap`）两条路径同规格。
- **排版不动**：DAG 层级排版（tier 分层、贝塞尔竖直入竖出）保持现状；v3 原型的紧凑布局不引入，但其交互结论可引用（曲线连线、禁 emoji、文字不出卡、局部 hover、CAD 式缩放，见 v3 终稿 §交互件）。
- **后端不动**：本规格全部功能仅改前端；后端数据缺口如实记录（§7.4）并给出 P2 增强选项（待拍板 §14-5/§14-7）。

### 1.3 裁定基线变更

| 项 | 旧裁定 | 本规格更新为 | 依据 |
|---|---|---|---|
| 终态节点去留 | 完成节点显示保留 1 天（TTL 倒计时徽标），到期从主图消失 | **v3：链粒度去留**——链未齐时终态节点保留主图（终态色卡）；链齐瞬间整链一起消失进归档；归档条目本身保留 24h（§5.9） | 作者 20:26 反馈①⑤（§1.1c） |
| 归档单位 | v1：逐节点归档（一节点 = 一条目） | **整链归档（一同触发批次全部终态 = 一条目）**；链未齐时终态成员保留主图（v3 起不再「主图照消」） | 作者 19:20 反馈① + 20:26 反馈① |
| 归档入口 | v1：nav-bar 右端 28px 图标按钮 | **画布内右上角 40px 圆形玻璃悬浮钮**（元素选择器同语言） | 作者 19:20 反馈③（§1.1b） |
| 节点详情 | 产品现状：居中 overlay viewer（openViewerShell） | **右侧滑出详情面板**，动画与归档面板同语言；**v3：z-index 70 高于归档面板（60）+ 宽视口 dock-left 并排错开，永远完整可见** | 作者 19:20 反馈② + 20:26 反馈③ |
| 不可见上游入边 | v2：目标锚点（7px 圆点 + 引入段） | **v3：锚点设计整体删除**——终态上游保留主图，边天然连着；仅已归档链（整链消失）的上游引用不画边 | 作者 20:26 反馈②（§1.1c） |
| 面板收起 | v2：外点（pointerdown）/ Esc / 再点 / ✕ | **v3：统一 click 空白收起**（画布空白点击收所有悬浮面板；click 而非 pointerdown 判定以避让拖拽） | 作者 20:26 反馈④（§1.1c） |

### 1.4 数据事实（规格依据，已对后端源码核实）

| 事实 | 出处 | 对设计的影响 |
|---|---|---|
| 节点状态词汇：`wiring / pending / running / completed / failed / blocked / cancelled` | `NodePayload`、契约 §3 | 过滤口径定义（§3.1） |
| 载荷含 `completedAt`（终态毫秒时间戳）与 `createdAt`（创建毫秒时间戳） | `ProjectTypes.scala` NodePayload.buildNodeJson | 链判定（§3.5）与归档排序（§5.4）字段现成可用 |
| **NodeDef 无批次/触发标识字段**：NodeEdit 单节点创建（`NodeTools.proceed`，`createdAt = 调用时刻`），无 batchId/triggerId | `NodeTools.scala:407-470`、`ProjectTypes.scala:41-71` | 链只能前端派生（§3.5 批次窗口口径）；P2 triggerId 选项（§14-7） |
| **磁盘归档区 `flow-map-archive.json` 保留完整 NodeDef**（in/out/deps/createdAt/completedAt 齐全，结果全文） | 已对 nebflow-website 实测核实（16 条归档节点逐字段确认） | 链派生跨刷新可行（P2 归档读端点落地后口径一致） |
| 载荷 `result` 统一截断 ≤500 字符——REST 快照与 WS 事件同一序列化点 | `NodePayload.buildNodeJson` | 详情正文与现有节点结果查看器同源同限，全文渲染属 P2（§7.4） |
| 终态节点 TTL 24h 后由 TTL sweep 移入磁盘归档区，WS 推 `nodeRemoved` | `NodeEngine.scala:560`、`FlowMapStore.scala` | 前端「纯过滤」方案的可见窗口边界（§7.4） |
| 磁盘归档区**无 REST 读端点**，载荷仅带 `meta.archived` 计数 | `RestApiRoutes.scala:310` | 徽章计数口径与 P2 端点动机（§4.3、§7.4） |
| WS 事件：`nodeCreated / nodeUpdated / nodeCompleted / nodeRemoved`，前端已有四路 handler + 增量动画 | `flowMapTab.js`、契约 §2 | 完成即刻消失的动画走既有增量管线（§3.4） |
| `css/flowMap.css` 在 `index.html` 全局挂载，就地/legacy 两路径共用 | `index.html:41` | 样式单点落位（§12） |
| **元素选择器悬浮钮 `.canvas-ref-select`**：40px 圆形玻璃钮（--glass-control-* token、opacity .6 → hover 1 + scale 1.05、active sapphire 染色、18px crosshair 图标 stroke 2） | `css/split.css:706`、`viewers/shared.js:180` | 归档悬浮钮设计语言（§4.2，作者反馈③指定参照） |
| 产品节点详情现状 = `openNodeResultViewer`（flowViewers.js:82，openViewerShell 居中 overlay 玻璃面板：name/agent/status/worktree/cfg/blocked/result markdown） | `flowMapTab.js:709-730`、`flowViewers.js:82-124`、`flowCss.js:161-171` | 详情面板内容同源（§5.8）；形态按作者裁定改右侧滑出 |
| mailbox 条目范式：`flow-mail-row`（`flowCss.js:179`）+ `openMailbox`（`flowViewers.js:289`）；markdown 渲染 `renderMarkdownWithMath`（`utils.js:98`）；时间格式化 `fmtTime / fmtRelTime` | 见左 | 面板条目直接移植该构型（§5） |

## 2 参考与依据

| 来源 | 用途 | 链接 |
|---|---|---|
| Nebflow visual-style 铁律（用户裁定，最高优先级）：玻璃质感、无 overlay 遮罩、中度字重、克制专业感 | 面板/悬浮钮/详情/动效的全部视觉基调 | `~/.nebflow/skills/nebflow/visual-style/SKILL.md` |
| design-system 案例库：mailbox 范式（列表条目构型、倒序、行内展开、markdown 正文）、chatSearch 焦点管理先例 | §5 面板条目、§10 无障碍 | `~/.nebflow/skills/design-system/SKILL.md` |
| 产品「元素选择器」`.canvas-ref-select`（作者反馈③指定参照） | §4 悬浮钮全部样式 token | `css/split.css:706` |
| Apple HIG — Sidebars / Panels：轻量附属面板不遮挡主内容、锚定触发器展开 | 面板形态（anchored panel 而非模态） | <https://developer.apple.com/design/human-interface-guidelines/sidebars> |
| Apple HIG — Reduction of Motion / `prefers-reduced-motion` | §6 动效降级 | <https://developer.apple.com/design/human-interface-guidelines/motion> |
| Material Design 3 — Motion easing（emphasized/standard 曲线族） | §6 缓动曲线选型参照 | <https://m3.material.io/styles/motion/easing-and-duration/tokens-specs> |
| WAI-ARIA APG — Disclosure (Show/Hide) Pattern：`aria-expanded` + `aria-controls` + ESC 关闭 | §4.4 / §5.7 / §10 | <https://www.w3.org/WAI/ARIA/apg/patterns/disclosure/> |
| Linear / Notion 通知面板范式：计数徽章 + 倒序条目列表 + 行内展开 | 归档面板交互结构 | <https://linear.app>（公开产品观察） |
| 产品内既有范式（文件级锚点见 §1.4 表） | 悬浮钮族 / mailbox 列表 / 玻璃面板 / 增量动画全部复用 | `split.css`、`flowTeams.js`、`flowViewers.js`、`flowMapTab.js` |

**冲突取舍**：范式之间冲突时以 visual-style 铁律与作者 taste（低调克制专业感）为准。例如 Notion 通知面板的彩色高亮条目在本产品中降级为玻璃中性底 + 单色状态徽章。

## 3 主图过滤规则（v3 重写 = 整链语义；锚点设计废除）

### 3.1 过滤口径（可断言）

| 集合 | 状态 | 去向 |
|---|---|---|
| **活动图（主图渲染）** | `wiring / pending / running / blocked` + **链未齐链的终态成员**（`completed / failed / cancelled`） | 卡片照常渲染；终态保留卡用终态色卡样式区分（§3.2），交互不变（hover/点击详情） |
| **归档集** | **整链全部终态**（completed/failed/cancelled） | 链齐瞬间**整链同一帧集体淡出**（一条整体动画 350ms，§3.4），整链成为面板条目（§3.5） |
| **墓碑（abandon 产物等）** | `cancelled`（abandon 后） | 统一按终态参与链判定与归档（见 §8.2 拍板项） |

- 排版输入 = 活动图节点（活动 + 链未齐终态保留）；层级（tier）计算与坐标仅对可见节点进行。**v3 起 `in` 边参与深度推导**（终态保留卡使 barrier 上游在图，层级语义完整；原型实测：无此项时保留卡全部塌到 depth 0 平排）。
- 主图头部摘要（running/wait/done/fail 计数）保留现状语义，`done/fail` 计数继续统计终态总数；v3 摘要补「链未齐终态保留 {n}」。
- 本规格不改变「节点完成即持久保存结果」「悬空节点接通下游自动补投递」等既有机制。

### 3.2 终态保留卡样式（v3 新增，取代锚点设计——锚点已按作者 20:26 反馈②整体删除）

**锚点（v1/v2 的目标锚点小圆点）整体废除**：链未齐时上游完成节点保留主图，入边天然有着落，锚点机制不再需要；已归档链（整链消失）的上游引用**不画边、不画锚点**（等待说明 ✦ 后缀兜底，§3.3）。

终态保留卡 = 现有节点卡 + 终态色卡语言（可断言）：

- 整体 `opacity: .78`（视觉后退一层；hover 回 1）；
- 静态轨道环染终态色：completed 绿 / failed 红 / cancelled 灰（`border-color` 对应 token，opacity .55）；
- 节点名降 `--color-text-dim`；状态徽章 SVG（勾/叉/横线）照常显示；
- `title` 追加「（链未齐·终态保留）」；点击 → 右侧详情（§5.8），与活动卡一致。

### 3.3 边的呈现（v3：边天然连着；in 边代理渲染）

- 可见节点间的出边（`out`）/ 依赖边（`deps`）：现状三态渲染不变；**上游 completed → delivered 态自然呈现「结果已投递」**。
- **`in`（barrier 输入）边代理渲染**：下游可见且上游可见时，若上游 `out` 未指向本节点（out 边已覆盖则不双画），按 barrier 输入补画同语言边（源 = 上游卡，态 = 上游状态三态）。原型实测快照中全部 9 张终态保留卡的 `in` 引用因此天然连着。
- 上游已随整链归档（不可见）→ 该入边**不画**（无锚点兜底，反馈②）；等待说明行（`等待: …`）对不可见上游以 `✦` 后缀标注（= 已归档）。
- 活动节点 → 终态目标（如目标被取消）→ 该出边**整条隐藏**，源卡出端口的状态徽标语义保留（见 §8.3）。
- `out="Nebula"`（汇入根会话）与悬空出边的现状表达不变。

### 3.4 完成瞬间的增量过渡（v3 重写：原地转终态 / 整链同帧消失）

`nodeCompleted` WS 到达时序：

1. **链未齐**：完成卡**原地转终态色卡**（不消失、不移位），与其下游的边转 delivered；面板与徽章均不动；可选 toast「链未齐（k/n）· 终态卡保留主图」。
2. **链齐**：**整链全部成员卡同一帧加退场态**（opacity→0 + scale→0.8，350ms，一条整体动画，非逐节点滴入）→ 整链同时从主图移除 → 存活卡平滑补位（`applyNodeDiff` + CSS `left/top` 400ms 既有曲线）→ 徽章 +1（脉冲）→ 面板若开着新链条目 prepend 并闪烁一次。
3. 全程无需全图重排；相机不动。

### 3.5 归档粒度 = 任务链（判定语义沿用 v2，缓冲语义按 v3 更新）

**归档单位不是节点，是链。** 同一次任务触发创建的整组节点全部到达终态 → 整链同一帧消失、归档为一条面板条目；链内最后一个节点完成之前，已完成成员**保留在主图**（终态色卡，§3.2）。

**链判定语义（可断言，一句话，v2 不变）**：

> **链 = 同一次任务触发创建的节点集合；前端派生口径 = 全部节点按 createdAt 升序、相邻创建间隔 ≤120s 的连续节点归为同一派发批次，一批次 = 一链。**

- **依据**：Nebula 一次任务派发 = 连续若干次 NodeEdit 调用（无批量 API，`NodeTools.proceed` 单节点创建），同批 createdAt 间隔实测 ≤80s、跨任务间隔实测 ≥3min（nebflow-website 真实快照 36 节点逐条验证）；窗口取 120s（≈2× 实测最大同批间隔）。
- **接线（in/deps/out）不参与链归属**——接线只决定主图层级排版与边渲染。跨批引用（如修复节点 `in` 上一批诊断节点）**不并链**，保持「同触发」语义纯粹；跨批引用同时是 §5.6 hover 联动的数据基础。
- **被否方案（接线连通分量）**：会把「邮件模板接入 + 清场重建」6 节点跨三次任务并成一链（汇聚 DAG 结构性过并），违背作者「同一次任务触发」定义。
- **链齐判定**：批次内全部成员 status ∈ 终态（completed/failed/cancelled）→ 链齐；链齐时刻 = 整链消失时刻 = 归档时刻。
- **链未齐（原「链缓冲」）**：终态成员保留主图（终态色卡）、面板不显示、徽章不计数；链齐瞬间整链成一条目（含早已完成的成员及其各自结果），徽章 +1。
- **单节点批次** = 链长 1 的链（完成即整链消失归档）。
- **面板条目字段**：链名（§5.3 三级推导）、节点数、链完成时间（= 成员最晚 completedAt）、链状态徽章（最坏优先 failed > cancelled > completed）。
- **链成员身份跨刷新稳定**：磁盘归档区保留完整 NodeDef（createdAt 齐全，§1.4），刷新后批次窗口重算结果一致；P2 triggerId 落地后升精确。

## 4 归档入口 = 画布内悬浮钮（v2 重写，作者 19:20 反馈③）

### 4.1 位置（可断言）

- **画布内部右上角**：悬浮于画布视觉层（就地视图 `flowmap-view-body` / 本原型 `.fm-stage` 内，`position:absolute; top:16px; right:16px`），**不占 nav-bar、不随画布平移缩放**（挂在视口层而非世界层）。
- 参照：产品「元素选择器」`.canvas-ref-select`（`split.css:706`）与渲染/源码切换 `.canvas-source-toggle`（`split.css:675`）的画布悬浮件语言——作者反馈③指定参照。
- legacy 独立标签页：同构锚定于画布容器右上角。
- **v1 的 nav-bar 右端按钮方案废弃**；nav-bar 恢复纯净（仅返回键 + 标题）。
- 视图容器需 `position:relative` 作为悬浮钮与两个面板的定位上下文（就地视图 `projectPanel.css` 一行改动，§12）。

### 4.2 按钮样式（可断言——与元素选择器同 token/同质感/同尺寸语言）

- **40×40px 圆形**（`border-radius:50%`）；`border: 1px solid var(--glass-control-border)`；`background: var(--glass-control-bg)`；`backdrop-filter: blur(var(--glass-control-blur)) saturate(1.2)`；`box-shadow: inset 0 1px 0 var(--glass-control-highlight), inset 0 -1px 0 var(--glass-control-underedge)`——全部与 `.canvas-ref-select` 逐值一致。
- **默认 opacity 0.6**；hover → opacity 1 + `scale(1.05)` + `--glass-control-bg-hover`；**active（面板开）→ opacity 1 + sapphire 染色**（`color: var(--color-primary)`、border sapphire 0.35、background sapphire 0.12，同 `.canvas-ref-select.active`）。
- 图标：lucide `archive`，**18×18、stroke 2、currentColor、圆头圆角**（与元素选择器 crosshair 同尺寸同 stroke 语言）。
- `transition: all 0.2s ease`（同源）；focus-visible 2px sapphire ring。
- 禁 emoji；图标为唯一图形语言（v3 交互结论沿用）。

### 4.3 计数徽章（可断言）

- 形态移植 `team-mail-badge`（`flowCss.js:57`）：钮右上角叠 16px 高圆角胶囊（`top:-5px; right:-5px` 适配圆形钮），sapphire 系底色 token、白字 10px/500，计数 >99 显示 `99+`。
- **计数口径 = 面板链条目数**（已归档链的条数，§3.5）——链缓冲成员不计；不用 `meta.archived`（磁盘全量计数，与列表长度不一致会造成观感 bug）。
- 计数为 0 → 徽章隐藏（图标裸显）。
- 计数变化（新链归档）：徽章数字直接更新，可选一次 scale 脉冲（§6.3，reduced-motion 下禁用）。

### 4.4 提示与语义（可断言）

- `title` + `aria-label`：「已归档任务链（{n}）」；`aria-expanded`（面板开合）、`aria-controls`（指向面板 id）；`type="button"`。
- 项目未挂载（notMounted 视图）不渲染该按钮。


## 5 归档面板（mailbox 式链条目列表）

### 5.1 形态与定位（可断言）

- **锚定下拉面板**（anchored panel，非模态）：绝对定位于画布层、`top:64px`（悬浮钮 16+40 下缘 +8px）、`right:16px`，右缘对齐悬浮钮右缘；**无背景遮罩**（visual-style 铁律①）。
- 尺寸：宽 380px；窄视口（≤420px）`calc(100vw - 24px)`；高度自适应内容，`max-height: min(70vh, 560px)`，超出内部滚动（`scrollbar-color: var(--color-frame-border) transparent`）。
- 玻璃质感（对齐 `.flow-viewer` 面板语言）：`background: var(--glass-bg)` + `backdrop-filter: blur(var(--glass-blur)) saturate(1.15)` + 1px `--glass-border` + 圆角 18px + `box-shadow: 0 8px 32px rgba(0,0,0,0.16)`。
- z-index 60；详情面板 70 浮前（§5.8b 层级方案，作者 20:26 反馈③）。
- 面板打开时主图可继续平移/缩放/悬停（面板锚定视口层，不随画布动）。

### 5.2 面板头部

- 左：标题「已归档任务链」（13px/600）+ 计数「{n} 条链 · {m} 节点」（11px/400 muted）。
- 右：关闭 ✕（16px，opacity .5 → hover 1，移植 `flow-viewer-close` 语言）。
- 底部 1px `--glass-border` 分隔。

### 5.3 链条目构型（移植 `flow-mail-row`，可断言）

**条目 = 链**。每条目两行：

```
[链状态徽章] 链名            [N 节点] 09-03 02:40  ▾
  最后完成成员结果摘要预览（单行省略）…
```

- **行 1**：链状态徽章（内联 SVG：completed 对勾 / failed 叉 / cancelled 横线；**链状态 = 成员最坏态**，failed > cancelled > completed 优先；禁 emoji）→ 链名（12px/500 主文字色，超长省略）→ **节点数徽标**（10px/500 muted、1px 描边小胶囊「N 节点」，单节点链显示「1 节点」）→ 链完成时间（11px/400 muted 右对齐，= 成员最晚 `completedAt`，`fmtTime`）→ 展开指示 `▾/▸`（10px muted）。
- **链名推导（确定性三级，可断言）**：① ≥2 成员 task 含【…】且【】内公共前缀 ≥2 字 → 该前缀（尾部截去分隔符与序号，实测产出「Logto 邮件链」「忘记密码误直登」「Logto 托管 UI 品牌」「邮件模板接入」）；② 成员名去角色前缀（`诊断-/修复-/合并-/实施-/验收-/部署-/取证-`）后最长公共前缀 ≥3 字（实测产出「console-404」「LogtoRequestError」）；③ 链首（createdAt 最早）节点名。
- **行 2**：最后完成成员 `result` 前 60 字符（11px/400 muted，单行 ellipsis）；`result` 空回落 `task`；再空不显示行 2。
- 条目容器：圆角 8px + 1px `rgba(128,128,128,0.12)` 边框 + `rgba(128,128,128,0.06)` 底（`flow-mail-row` 原值），条目间距 8px；hover 玻璃底加深。
- 整条可点（`cursor:pointer`，语义为 button，见 §10）。

### 5.4 排序（可断言）

- **链完成时间倒序（最新链在最上）**——mailbox History 同例；「刚完成了什么」是最高频问题，倒序让新链条目直接出现在视野顶部，与链齐 prepend 增量自然衔接。
- 同毫秒并列按链名字典序兜底。

### 5.5 点击条目行为（可断言）

- 点击链条目 = **行内展开/收起链内成员列表**（mailbox 同构 toggle，独占式：同时最多展开一条）：
  - 展开区 = **成员逐条**（按成员 completedAt 倒序）：`[成员状态徽章] 节点名 · agent 名 · 完成时间 ›` + 结果单行预览（前 60 字符）；成员行圆角 7px 次级玻璃底。
  - **点击成员 → 右侧详情面板**（§5.8）展示该节点完整结果——正文承载移至详情面板，条目列表保持克制（v1 的行内 markdown 正文取消）。
  - 展开态视觉：条目边框变 sapphire（`rgba` 0.25 级）+ 指示符转 `▾`；`aria-expanded` 同步。
  - 成员行 = button 语义（`tabindex="0"`，Enter/Space 触发）。
- 条目上不提供重跑/重开等操作（超出本规格范围；终态再接线走既有 NodeEdit 链路）。

### 5.6 条目 ↔ 主图联动（hover 联动保留；v3 起无锚点加亮路径）

- **hover 链条目 → 主图高亮该链全部成员的直接可见下游**（跨批引用场景：已归档链喂给仍在运行的后续批）：下游卡加 `.fm-adj` 高亮；移出恢复。**hover 成员行 → 仅该成员的下游**。
- **语义注记**（原型实测，§17-C8）：批次窗口口径下活动节点的上游皆为同批兄弟，故「已归档链」在活动图中通常无下游——联动在**跨批引用**数据形态（后续批 `in` 已归档链成员，如「修复-console-404」`in`「诊断-console-404」）下激活；v3 起链未齐终态成员保留主图，同批兄弟引用也会命中（hover 链内成员 → 其可见下游卡高亮）；机制由回归钩子覆盖（§11-A12）。

### 5.7 关闭方式与焦点（可断言；v3 统一空白收起见 §5.10）

关闭路径：① **点击面板外空白**（统一 click 判定，§5.10）；② `Esc`（Esc 分层：详情面板在顶时先关详情，再按才关归档面板，§5.8）；③ 再点悬浮钮；④ 头部 ✕。任何一路关闭后焦点归还**悬浮钮**（chatSearch 先例；空白点击不收焦点——keepFocus）；面板关闭不重置条目展开态以外的任何数据。

### 5.8 右侧详情面板（主图点节点 + 链内点成员两路触发）

- **触发**：① 主图点击节点（活动卡与终态保留卡同）；② 归档链展开后点击链内成员（§5.5）。
- **形态**：右侧滑出面板（非模态、无遮罩）：绝对定位于画布层右缘，`top:64px`（悬浮钮下方）`right:16px` `bottom:16px`，宽 360px（窄视口 `calc(100vw - 24px)`）；玻璃面板语言同归档面板（`--glass-bg`/blur/border、18px 圆角、panel-shadow）；**z-index 70**（层级方案 §5.8b）。
- **内容**（与产品 `openNodeResultViewer` 同源，`flowViewers.js:82`）：头部 = 状态徽章 + 节点名 + ✕；正文 = meta（agent · 状态 · id · 创建/完成时间 · **所属链名及链齐状态**）→ 任务（task 全文，140px 内滚）→ 结果（result markdown 渲染，`renderMarkdownWithMath` 既有链路，≤500 字符同限 §7.4；空 →「暂无结果」；渲染异常 → 纯文本兜底）。
- **动画（与归档面板同语言，可断言）**：入场 `translateX(24px) + opacity 0→1`，**220ms `cubic-bezier(0.33, 0, 0.2, 1)`**；退场反向 160ms `ease`，结束后 `display:none`；`prefers-reduced-motion` → opacity-only ≤10ms（§6.4）。
- **切换与关闭**：开着时点击另一节点 = 内容原位切换（不重复滑出动画）；关闭路径：✕ / Esc（分层）/ **画布空白点击（§5.10，v3 新增）**。
- **平移拖拽保护（原型实测，§17-C6）**：画布视口 `pointerdown` 不得立即 `setPointerCapture`——否则 click 被重定向到视口、卡片永远点不开详情；拖拽成立（位移 >3px）后才捕获。

### 5.8b 层级方案（v3 新增，作者 20:26 反馈③——详情面板必须完整可见）

**一句话：详情面板 z-index 70 > 归档面板 60；宽视口（≥800px）两面板同开时详情自动左靠并排（dock-left），矩形零重叠；窄视口退回同位、z-70 浮前覆盖——任何情况下详情完整可见。**

| 层 | z-index | 说明 |
|---|---|---|
| toast | 80 | 顶层提示 |
| **右侧详情面板** | **70** | 永远浮于归档面板之前 |
| 归档面板 | 60 | 锚定下拉 |
| 悬浮钮 | 45 / HUD 30 / nav-bar 40 | 不变 |

- **位置错开（宽视口 ≥800px）**：归档面板开着时详情 `right: 404px`（= 面板宽 380 + 间隙 8 + 右缘 16），详情右缘与面板左缘相距 8px，两面板并排、**矩形零重叠**（可断言）；`right` 带 220ms 同曲线过渡，面板开合时详情平滑左右移动。
- **窄视口（<800px）**：放不下并排 → 详情退回 `right:16px` 同位，**z-70 浮前覆盖归档面板**（CSS 媒体查询兜底），详情始终完整可见。
- Esc 分层不变：详情在顶先关详情，再按关归档面板。

### 5.9 归档条目 24h TTL（v3 新增，作者 20:26 反馈⑤）

- **判定**：条目（链）自 `completedAt`（= 成员最晚完成时间）起保留 **24 小时**；到期从归档面板清理——条目不再显示、**计数徽章同步减**、面板头计数同步。
- **对齐后端**：节点本体 24h TTL sweep 出库（`NodeEngine.scala:560`，§1.4）——前端面板 TTL 与数据生命周期同口径，会话内到期即清（前端周期检查 + 事件驱动各一次；原型 30s 轮询演示）。
- **即将过期提示（克制）**：剩余 <60min 的条目在行 1 时间后显示「即将过期」小标签（10px `--color-warning` 低饱和），`title` 说明「归档条目自完成起保留 24 小时，到期自动清理」；其余条目不显示任何 TTL 痕迹。
- **出库即出派生输入**（原型实测，§18-C10）：到期链成员节点须同时从链派生输入（nodes 表）移除——否则下一次链重判（任何节点完成）会把已清理链重新派生回面板。
- 空态：全部条目过期后面板回落「暂无已归档任务链」，徽章隐藏。

### 5.10 画布空白点击 = 统一收起所有悬浮面板（v3 新增，作者 20:26 反馈④）

- **统一交互语言**：点击画布空白处（或面板之外的任意界面空白）→ 归档面板与详情面板**同时收起**。
- **冲突规避（可断言）**：
  ① **用 `click` 而非 `pointerdown` 判定**——pointerdown 是拖拽/框选的起点，不能触发收起（v2 的 pointerdown 外点关闭废止）；
  ② **拖拽豁免**：位移 >3px 的平移拖拽不触发收起（`suppressCardClick` 抑制本次 click）；
  ③ **元素选择豁免**：点击节点卡 = 选择（开详情），不收起任何面板（卡片 click `stopPropagation`）；
  ④ 边元素 `pointer-events:none`，击边即击空白（符合「空白即收起」直觉）；
  ⑤ 点击面板/详情/悬浮钮内部不收起（各自 stopPropagation/包含判定）。


## 6 展开 / 收起动画

### 6.1 打开（可断言）

| 属性 | 值 |
|---|---|
| `transform-origin` | **`calc(100% - 20px) 0`**——原点 = **悬浮钮圆心**（面板右缘与钮右缘对齐 right:16px，钮半径 20px；视觉「从悬浮钮拓展开」，反馈④ origin 改悬浮钮位置） |
| 初态 → 终态 | `opacity: 0 → 1`；`transform: scale(0.94) translateY(-6px) → scale(1) translateY(0)` |
| 时长 | **220ms** |
| 缓动 | `cubic-bezier(0.33, 0, 0.2, 1)`（产品既有边入场同曲线；克制、无回弹） |

- 断言口径（§16-C1 沿用）：浏览器计算值为像素（x = 面板宽 − 20，y = 0），按像素等价判定。
- 不做 height/clip-path 尺寸动画：内容异构导致高度动画抖动，且与现有面板 fade/scale 语言不一致。
- 右侧详情面板入场 = 同曲线同时长的 `translateX(24px) + fade`（§5.8）；dock-left 左右移动 = `right` 220ms 同曲线（§5.8b）。

### 6.1b 整链消失动画（v3 新增，作者 20:26 反馈①）

- **链齐瞬间：整链全部成员卡同一帧加退场态**——`opacity → 0` + `scale(1 → 0.8)`，**350ms `ease`**（复用 `animateNodeExit` 语言），一条整体动画，**非逐节点滴入**；动画结束整链同时移除 + 存活卡补位（400ms 既有曲线）。
- 链未齐时完成卡**原地转终态色卡**（无位移动画，仅样式切换 + 关联边转 delivered 350ms 描边过渡）。

### 6.2 收起（可断言）

- 对称反向：`scale(0.94) translateY(-6px)` + `opacity → 0`，**160ms** `ease`（退出略快于进入），动画结束后 `display:none`（不留透明层拦截点击）。

### 6.3 附加微动效（可选，reduced-motion 下全部禁用）

- 徽章计数 +1：scale `1 → 1.18 → 1`，150ms。
- 面板开着时新链条目 prepend：入场背景闪烁一次 0.5s（`fm-node-flash` 同族语言）。
- 链未齐提示 toast（原型演示件）：玻璃胶囊底部居中 fade/slide 220ms 同曲线，2s 自消——产品侧可选保留（帮助用户理解「节点完成了但链未齐、卡保留主图」），拍板项 §14-8。

### 6.4 降级（可断言）

`@media (prefers-reduced-motion: reduce)`：开/合动画（归档面板 + 详情面板）降级为**仅 opacity、时长 ≤10ms**；徽章脉冲与条目闪烁直接禁用。


## 7 数据与状态

### 7.1 原则（可断言）

**不动后端**：数据仍来自 flow-map store（REST `GET /api/projects/<name>/flow-map` 快照 + 四路 WS 增量）。主图过滤、链派生、面板渲染全部前端完成。

### 7.2 前端链派生与归档 store（v3 更新，可断言）

每 pane 一份（随 flowMapTab 现有 store 生命周期）：

- **播种**：快照载入 → 全量节点（活动 + 终态）入 nodes 表 → **链派生**（§3.5 批次窗口，`deriveChains`）→ 全终态链 → 面板条目；混合链（部分终态）→ 终态成员**保留主图**（可见集 = 未归档节点全量）。播种后执行一次 TTL 清理（§5.9，对齐 TTL sweep 语义）。
- **增量**：`nodeCompleted`（及终态 `nodeUpdated`：failed/cancelled）→ 节点转终态、**卡原地转终态色卡** → **重判所属链**：链齐 → 整链同帧消失、整链成条目（prepend + 徽章 +1）；未齐 → 卡保留主图（面板、徽章均不动）。
- `nodeRemoved`（24h TTL 出库）→ **条目保留到期判定以 completedAt 为准**（§5.9）；出库节点若属链未齐链，按「已终态」计入链齐判定（其终态事实不变）。
- **TTL 清理**（§5.9）：到期链条目移除 + 徽章同步减；**成员节点同时从 nodes 表移除**（防链重判复活，§18-C10）。
- **排序字段**：链完成时间 = 成员最晚 `completedAt`。
- 项目切换 / 重连重建快照时整体重建。

### 7.3 徽章与空态（可断言）

- 徽章计数 = 已归档链条数（§4.3；TTL 清理同步减）。
- 面板空态：居中 muted 文案「暂无已归档任务链」（新 i18n key），无插图（克制）。
- 主图全归档（无可见节点但有已归档链）：主图空态卡文案「全部节点已完成，结果收入右上角归档」+ 悬浮钮保持可用（现 `flowmap.allArchived` TTL 文案下线，换新 key）；主图与面板可同时为「空图 + 有条目」。
- 主图零节点（项目空闲）：沿用 `flowmap.empty` 现状。

### 7.4 已知边界：不动后端的三条显示边界（如实记录）

1. **时间窗口**：磁盘归档区无 REST 读端点（§1.4）。纯前端方案的条目窗口 = 快照播种时的 24h TTL 窗口 + 本次会话积累；**刷新后 >24h 的条目不可见**（数据在后端磁盘，仅 UI 不可达）。
2. **正文长度**：载荷 `result` 统一 ≤500 字符 → 详情面板正文与现有节点结果查看器**同源同限**，无回退；全文渲染需后端支持。
3. **链判定为派生近似**：NodeDef 无 triggerId/batchId（§1.4）→ §3.5 批次窗口口径存在理论误并/误拆（两个不同任务 120s 内相继派发会被并为一链；同批间隔 >120s 会被拆开）——真实快照 36 节点验证零误并，但口径非精确。

**P2 后端增强（待拍板 §14-5/§14-7，不在本规格交付内）**：① 只读端点 `GET /api/projects/<name>/flow-map-archive`（`FlowMapStore.archiveSnapshot` 已有，route + 载荷数组化）→ 跨刷新全历史 + `result` 全文；② NodeEdit 载荷加 `triggerId` 透传（Nebula 每次派发生成一个 id）→ 链判定升精确。前端规格为此预留：链派生函数单点化（未来换精确口径只改 `deriveChains` 一处）、播种函数单点化（切数据源只改播种处）。

### 7.5 重连 / 刷新 / 切换（可断言）

- 刷新或 WS 重连后：**面板与详情默认收起**（不恢复展开态），store 由快照重建，徽章计数按 §7.2 口径重算且自洽（条目数 = 徽章数）。
- 同 pane 切换项目：store 与面板内容随新项目快照重建，面板收起。
- 多 pane（多项目）互不影响：独立 store、独立徽章、独立开合态。

### 7.6 死代码清理（可断言）

主图不再出现终态卡 → `tickTtl` 主图 TTL 倒计时 ticker、卡片 `fm-ttl` 徽标、「{n} 个节点已到期归档」提示逻辑移除；`nodeRemoved` handler 职责收缩为空态判定（§7.3）。

## 8 边界与异常

| # | 场景 | 处理 | 断言 |
|---|---|---|---|
| 8.1 | **blocked 节点** | **保留主图**（活动图成员，卡 + ⚑ 反馈协议图标 + blockCount 环数照旧），**不进归档、不参与链齐判定**——blocked 非终态（可被反馈协议重入复活）。采纳任务建议；作为拍板项 §14-1 附带确认 | §11-A1 附带 |
| 8.2 | **abandon（墓碑）** | abandon 产物 = `cancelled` → 终态，参与链判定（与 NodeCancel 的 cancelled 同呈现，后端无 abandon 独立标记，前端不特判）；链条目含 cancelled 成员 → 链徽章灰。若要求面板完全不可见，需后端加 abandon 布尔标记（P2，拍板项 §14-4） | §11-A15⑤ 附带 |
| 8.3 | 活动源 → 终态目标 | 出边整条隐藏；源卡出端口状态徽标保留现状语义 | §11-A1 附带 |
| 8.4 | 上游已随整链归档（不可见） | 入边不画（无锚点，反馈②）；等待说明 `✦` 后缀兜底 | §11-A1/A12 附带 |
| 8.5 | barrier 多上游（均在图） | 每条 in 边各自贝塞尔渲染（in 边代理渲染，§3.3），无锚点排布问题 | §11-A1 附带 |
| 8.6 | 超长 result / 超长节点名 / 超长链名 | 条目行 1 省略号；摘要单行省略；详情正文滚动 | §11-A6 附带 |
| 8.7 | 窄视口 375px | 面板与详情面板均 `calc(100vw - 24px)`；悬浮钮仍锚定画布右上（16/16）；无横向溢出；详情 z-70 浮前完整可见 | §11-A20 |
| 8.8 | 面板开着时主图重排/缩放 | 面板锚定视口层不随动；主图交互不被面板阻断（无遮罩） | §11-A5 附带 |
| 8.9 | notMounted | 不渲染悬浮钮与两个面板 | §11-A4 附带 |
| 8.10 | 暗亮双主题 | 全 token 化；悬浮钮/徽章/面板/详情两主题截图验收 | §11 截图项 |
| 8.11 | deps-only 下游（无 in 只有 deps） | deps 虚线边照常（上游在图时）；上游已归档则不画 | §11-A1 附带 |
| 8.12 | 面板滚动触达（链条目极多） | 内滚 + 头部 sticky；滚动不泄漏给主画布 | §11-A5 附带 |
| 8.13 | **链未齐**（部分成员仍活动/长期 wiring） | 终态成员**保留主图**（终态色卡 §3.2，面板不显、徽章不计）；**链永久不齐**（成员卡死 wiring）→ 链永不归档、终态卡永久保留——语义忠实（链确实没完成）；不提供强制归档操作（超范围） | §11-A16③ |
| 8.14 | **跨批引用**（后续批 `in` 已归档链成员） | 不并链（§3.5）；hover 联动借此呈现「结果传给了谁」（§5.6） | §11-A12 |
| 8.15 | 同批误并（两任务 120s 内相继派发） | 接受为派生口径近似（真实快照 36 节点零误并）；P2 triggerId 消解（§14-7） | 文档级 |
| 8.16 | 归档面板与详情面板同开 | **详情浮前（z 70 > 60）+ 宽视口 dock-left 并排零重叠**（§5.8b）；Esc 分层先关详情 | §11-A9/A20 |
| 8.17 | **画布空白点击 vs 拖拽/选择冲突** | click（非 pointerdown）判定；拖拽 >3px 豁免；点节点 = 选择不收起（§5.10） | §11-A11 |
| 8.18 | **归档条目超 24h** | 从面板清理、徽章同步减、成员节点出库（§5.9）；全部过期 → 空态 + 徽章隐藏 | §11-A18 |


## 9 交互状态机汇总表

### 9.1 归档悬浮钮

| 状态 | 视觉 | 行为 |
|---|---|---|
| 默认 | 40px 圆形玻璃钮，opacity 0.6；计数 0 无徽章 | 点击 → 开面板 |
| hover | opacity 1 + scale(1.05) + `--glass-control-bg-hover` | — |
| focus-visible | 2px focus ring（§10） | Enter/Space 同点击 |
| active（面板开） | opacity 1 + sapphire 染色（`.canvas-ref-select.active` 同式） | 点击 → 关面板 |
| 计数变化 | 数字更新（+可选脉冲） | — |
| notMounted | 不渲染 | — |

### 9.2 归档面板

| 状态 | 视觉 | 行为 |
|---|---|---|
| closed | 不在 DOM（或 `display:none`） | — |
| open | 玻璃面板、无遮罩、z 60 | 空白点击（§5.10）/Esc/再点悬浮钮/✕ → closing |
| opening（220ms） | scale+fade 入场（§6.1，origin = 悬浮钮圆心） | 动画中可中断为 closing |
| closing（160ms） | 反向出场（§6.2） | 结束 → closed |

### 9.3 链条目

| 状态 | 视觉 | 行为 |
|---|---|---|
| 默认 | 玻璃中性底（`flow-mail-row` 原值）+ 节点数徽标 | 点击 → expanded |
| hover | 底色加深；主图可见下游高亮（§5.6） | — |
| focus-visible | 2px focus ring | Enter/Space 同点击 |
| expanded | 边框 sapphire 0.25 + `▾`；展开区 = 成员逐条 | 再点 → 默认（独占式）；点成员 → 右侧详情 |
| 新链条目入场（面板开着时） | 背景闪烁 0.5s×1 | 置顶 prepend |
| 即将过期（剩余 <60min，§5.9） | 行 1 时间后「即将过期」小标签（warning 低饱和） | 到期自动从面板清理、徽章 -1 |

### 9.4 终态保留卡（v3 新增，取代锚点状态机——锚点设计已废除）

| 状态 | 视觉 |
|---|---|
| completed（链未齐） | 卡 opacity .78、轨道环染绿（.55）、名降 dim、勾徽章；hover 回 opacity 1 |
| failed（链未齐） | 同上，环染红、叉徽章 |
| cancelled（链未齐） | 同上，环染灰、横线徽章 |
| 链齐瞬间 | 整链全部成员卡同帧 `fm-exit`（opacity→0 + scale→0.8，350ms，一条整体动画） |
| 点击 | → 右侧详情（§5.8），与活动卡一致 |

### 9.5 右侧详情面板

| 状态 | 视觉 | 行为 |
|---|---|---|
| closed | `display:none` | 点主图节点（活动/终态保留）/ 点链内成员 → open |
| open | 右侧玻璃面板、无遮罩、**z 70**；面板同开且视口 ≥800px → dock-left 并排 | ✕/Esc（分层）/画布空白点击（§5.10）→ closing；点另一节点 → 内容切换 |
| opening（220ms） | translateX(24px)+fade 入场 | — |
| closing（160ms） | 反向出场 | 结束 → closed |
| 内容切换 | 原位替换，不重复滑出动画 | — |


## 10 无障碍

| 项 | 规格 | 先例/依据 |
|---|---|---|
| 悬浮钮语义 | `role=button`（原生 button）、`aria-label`「已归档任务链（{n}）」、`aria-expanded`、`aria-controls` | WAI-ARIA APG Disclosure |
| 面板语义 | `role="region"` + `aria-label`「已归档任务链」（非模态，不用 dialog/modal 语义） | 同上 |
| 详情面板语义 | `role="region"` + `aria-label`「节点详情」 | 同上 |
| 键盘 | `Tab` 可达悬浮钮与条目与成员行；`Enter/Space` 触发；`Esc` 分层（详情 → 归档面板）并**焦点归还悬浮钮** | chatSearch 先例 |
| 条目/成员语义 | 条目与成员行均可聚焦（`role="button"` + `tabindex="0"`）；展开态 `aria-expanded` | flow-mail-row 升级 |
| focus ring | `:focus-visible` → `box-shadow: 0 0 0 2px var(--glass-control-border)` | `fm-nf-entry` 先例 |
| 对比度 | muted 文字两主题 ≥4.5:1；沿用现有 muted token，若现状不足随本规格一并校准（实现时用工具核） | visual-style 铁律 |
| 动效降级 | `prefers-reduced-motion` → opacity-only ≤10ms，脉冲/闪烁禁用（§6.4） | 既有 `flowMap.css` 先例 |
| 信息不依赖色彩 | 终态三色 + 徽章 SVG 形状（勾/叉/横线）双编码；tooltip 文字兜底 | HIG |


## 11 可断言验收点（供 qa-frontend 转 Playwright）

每条二值断言；**[截图]** = 交付时需附截图（亮暗双主题 × 必要视口），供视觉评审 vision 对照。编号与原型回归 `regression.mjs`（**88 断言**）一一对应。

| # | 断言 | 需截图 |
|---|---|---|
| A1 | 基线渲染（含活动 + 链未齐终态的快照）：主图 DOM 可见卡 = 20（活动 11 + 终态保留 9）；**终态保留卡带 `.fm-node.terminal`**（opacity .78、环染终态色、名 dim、title 含「链未齐·终态保留」）；**in 边参与层级推导且被代理渲染**（层级经入边中继推导，入边以断线虚线绘制）；活动卡齐全 | ✅ 亮+暗 |
| A2 | **锚点设计整体废除**：DOM 无 `.fm-anchor` / `.fm-anchor-*` 任何元素；CSS 无锚点规则；图例无锚点项 | ❌ |
| A3 | 徽章数 = 已归档链条数 = 面板条目数 = **10**（9 真实存活 + 1 TTL 演示；以快照最新时刻为基准、24h TTL 下最老 3 条真实链自然过期）；悬浮钮 aria-label「已归档任务链（{n}）」 | ❌ |
| A4 | **悬浮钮位置与样式**：挂载于画布层（非 nav-bar）、`position:absolute`、`top/right = 16px`（±2）；40×40 圆形（radius 50%/20px）；默认 opacity 0.6；nav-bar 内无归档入口 | ✅ 亮+暗 |
| A5 | 点击悬浮钮：面板可见、`aria-expanded=true`；**`transform-origin` 计算值 = (面板宽−20, 0)**（悬浮钮圆心）；时长 220ms（±20ms）；面板外主图区域无遮罩 | ✅ 亮+暗 |
| A6 | 链条目构型：状态徽章 SVG + 链名 + 节点数徽标「N 节点」+ 完成时间；**条目 = 10 条**；**TTL 演示链（末条）带「即将过期」标签** | ✅（TTL 标签） |
| A7 | 两条不同完成时间的链：时间戳较大者排在前（倒序）；节点数徽标抽样：c3=1、c2=2、c1=7 | ❌ |
| A8 | 点击链条目：容器获 expanded 态（边框 sapphire + `aria-expanded=true`）+ **展开区 = 链内成员逐条**（成员数 = 节点数徽标值，成员含状态/名称/agent/时间）；独占式（开新收旧） | ✅（展开态） |
| A9 | **点击链内成员 → 右侧详情面板打开**：标题 = 成员节点名、正文含 markdown 渲染产物、meta 含所属链信息；**层级方案：详情 z-index 70 > 面板 60**；宽视口（≥800px）同开时详情 `.dock-left` 右移 404px 与面板**并排零重叠**（`elementFromPoint` 命中详情自身） | ✅（并排态，亮+暗） |
| A10 | **主图点击节点 → 右侧详情**（活动卡与**终态保留卡**均可点：内容 = name/agent/状态/任务/结果）；点另一节点内容原位切换；详情动画 220ms 同曲线；Esc 分层（详情在顶先关详情、归档面板不受影响）；✕ 关闭 | ✅ |
| A11 | **空白点击收起**：点主图空白（非节点/非面板/非悬浮钮）→ 详情与归档面板同时收起；**拖拽豁免**（按下后位移 >3px 的拖拽结束不触发收起）；**点节点豁免**（节点卡 stopPropagation） | ✅ |
| A12 | hover 联动（无锚点）：hover 链未齐的已归档链条目 → 主图对应**终态保留卡**加亮（`.fm-adj`），移出清除；hover 无可见成员的链 → 主图不误亮 | ✅ |
| A13 | `Esc` 关面板、焦点归还悬浮钮（`document.activeElement` = 悬浮钮）、面板 `display:none` 不留透明层 | ❌ |
| A14 | 三路关闭各一次：空白点击（A11）/ 再点悬浮钮 / 头部 ✕，面板均收起 | ❌ |
| A15 | 暗主题切换：token 变化 + 面板/详情/悬浮钮/终态保留卡截图验收 | ✅ 暗 |
| A16 | **整链语义模拟（WS 增量 5 步）**：① 链内最后一员完成 → 整链成员**同帧**获 `fm-exit` 集体淡出 → 一条目置顶 + 徽章 10→**11**；② 链未齐 → 该节点**原地转终态保留卡**（不消失）、徽章/条目不动（仍 11→12 见③）；③ 再补一员仍未齐 → 保留卡 +1，徽章 12；④ 新链齐 → 徽章 **13**；⑤ 连击至全终态 → 全链归档、徽章 **18**、主图空态文案 + 悬浮钮可用 | ✅（可录制前后两帧） |
| A17 | 重置/刷新后：面板与详情默认收起、徽章数回到基线 10、终态保留卡恢复 9 | ❌ |
| A18 | **TTL 清理**：点「模拟 TTL 清理」→ TTL 演示链整链移除（removed=1）、徽章 10→**9**、面板条目同步减 1、到期链成员从派生输入表删除（不复活） | ✅ |
| A19 | `prefers-reduced-motion: reduce`：面板与详情开合 ≤10ms 且无位移、整链退场瞬切，开合仍可用 | ❌ |
| A20 | 375×667 视口：面板与详情宽 = `calc(100vw - 24px)`、悬浮钮仍锚定画布右上（16/16）、无横向溢出；**窄视口详情同位 z-70 浮前覆盖面板（完整可见，`elementFromPoint` 命中详情）**；条目与详情不破版 | ✅ |


## 12 交付物清单与实施工作量评估

### 12.1 文件面（实现方：nebflow-project Frontend；本 agent 不碰源码）

| 文件 | 改动 |
|---|---|
| `src/main/resources/web/js/flowMapTab.js` | **主改件**：① 渲染过滤管线（visible = 活动 + 链未齐终态保留，§3.1）；② **终态保留卡渲染**（`.fm-node.terminal` 终态色环/降 opacity/降 name dim，§3.2）+ **in 边参与层级推导与代理渲染**（§3.3）；③ **链派生（deriveChains 批次窗口单点函数）+ 链缓冲 + 归档 store + 整链同帧退场**（§3.4/§3.5/§6.1b/§7.2）；④ 归档面板组件（链条目/独占展开/成员行/hover 联动/焦点管理/Esc 分层，§5）；⑤ **右侧详情面板**（复用/抽取 `openNodeResultViewer` 内容装配，§5.7/§5.8）+ **层级方案**（z-70 + 宽视口 dock-left 404px，§5.8b）；⑥ 悬浮钮渲染 + 徽章（§4）；⑦ **TTL 清理**（24h、到期链成员出库防复活、徽章同步、30s 轮询 + 快照导入触发，§5.9）；⑧ **空白点击统一收起**（document click 管线 + 拖拽/节点豁免，§5.10）；⑨ 空态文案与死代码清理（§7.3/§7.6）；⑩ i18n keys 接线；⑪ 视口 pointer capture 时机修正（§5.8 拖拽保护） |
| `src/main/resources/web/js/projectTab.js` | 就地视图画布容器加 `position:relative` + 悬浮钮/面板/详情挂载点（不再动 nav-bar，v1 挂载方案废弃）；legacy 路径同构挂载 |
| `src/main/resources/web/css/flowMap.css` | 悬浮钮/徽章/面板/链条目/成员行/详情面板/**终态保留卡**/**「即将过期」标签**/动画样式（全局挂载，两路径共用）；含 `prefers-reduced-motion` 块；**层级表**（面板 60 / 详情 70 + 宽视口 dock-left 404px / toast 80，§5.8b） |
| `src/main/resources/web/js/locales/zh-CN.js` + `en.js` | 新增 keys：`flowmap.archive.button/title/count/empty`（「任务链」措辞）、`flowmap.archive.nodes`、`flowmap.archive.expiringSoon`（即将过期标签）、`flowmap.chain.*`（未齐提示等）、`flowmap.detail.*`；下线/改写 `flowmap.allArchived`；**锚点相关 keys（若 v1 已加）随锚点废除一并下线** |
| `nodeData.js` | （若现状无终态集合常量则新增）`TERMINAL_STATUSES` / 链派生 helper |

不动：后端任何文件、排版器（`layoutNodes` tier/坐标计算）、既有边三态与流动动画、blocked 反馈协议、补投递机制。

### 12.2 工作量评估

| 环节 | 估时 | 说明 |
|---|---|---|
| Frontend 实现 | **3 天** | 链派生 + 缓冲 + 面板 1 天（最重）；终态保留卡 + in 边代理渲染/层级 + 整链同帧退场 0.5–1 天；悬浮钮 + 详情面板 + 层级方案 + 动画 + 双主题 0.5–1 天；TTL 清理 + 空白收起管线 + WS 增量 + 空态 + 边界 + i18n 0.5–1 天（锚点 1 天工作量已随锚点废除移除） |
| qa-frontend 断言 | 0.5 天 | §11 A1–A20 转 Playwright 进 CI（原型回归 88 断言可直接对译） |
| 视觉评审（本 agent） | 0.5 天 | 隔离实例截图（亮/暗 × 1440/375 × 关键状态）对照本规格 |
| 后端 P2（若拍板 §14-5/§14-6） | 1 天 | 归档读端点 + triggerId 透传，另立任务 |

## 13 验收 checklist 汇总

- [ ] §11 A1–A20 全部 PASS（qa-frontend Playwright + 截图）
- [ ] 视觉评审 PASS（本 agent：亮/暗 × 1440/375 × 默认/链展开/详情并排/终态保留卡/整链退场/即将过期标签/空态，对照 §3–§10 逐项）
- [ ] 回归：既有 Flow Map 功能不回归——DAG 排版、边流动动画、blocked 反馈协议、**节点详情（形态变更为右侧滑出，内容不变）**、补投递、任务列表跳转高亮（`highlightNodeId`）
- [ ] 回归：mailbox / 渲染源码切换键 / 元素选择器等被参考范式不被本改动波及
- [ ] 双主题截图归档 `~/.nebflow/docs/Nebflow/assets/`（禁 /tmp 路径，CONVENTIONS.md）
- [ ] 交付后 24h/长会话场景人工核一轮（徽章自洽、终态保留卡不漂移、链缓冲口径、**TTL 自然到期路径**——原型只覆盖模拟触发）

## 14 待作者拍板项

| # | 问题 | 选项 A（推荐） | 选项 B |
|---|---|---|---|
| 1 | **归档范围**：终态全部 vs 仅 completed | **终态全部参与链判定与归档**（链徽章带 failed/cancelled；主图真正只剩活动节点） | 仅 completed（与「只显示运行中待运行」矛盾） |
| 2 | ~~条目排序~~ | **已定：链完成时间倒序**（v1/v2 原型均按此，作者两轮未异议） | 正序 |
| 3 | **hover 条目 → 主图高亮下游**（§5.6） | **内含**（反馈④明确保留 hover 联动） | 不做 |
| 4 | **abandon 产物**：统一终态语义进归档 vs 完全不可见 | **进归档**（链徽章灰；后端零改动；审计痕迹保留） | 面板也不显示（需后端 abandon 布尔标记，P2） |
| 5 | **后端归档读端点**（§7.4：跨刷新全历史 + result 全文） | **本期不做**，按纯前端交付；P2 另立任务 | 本期一起做 |
| 6 | **triggerId 透传**（§7.4-3：链判定从派生近似升精确） | **本期不做**（批次窗口实测零误并）；P2 与归档读端点同批 | 本期一起做（NodeEdit 载荷加字段 + Nebula 派发生成 id） |
| 7 | 链未齐 toast 提示（§6.3，原型演示件） | 产品保留，文案改为「该节点已终态，**链未齐·保留主图**」（帮助理解终态卡为何不消失） | 不保留（静默保留，最克制） |

> 19:20 反馈已裁定（不再列为拍板）：归档粒度 = 整链（§3.5）；入口 = 画布内悬浮钮（§4）；右侧详情面板保留（§5.7/§5.8）。
> 20:26 反馈已裁定（不再列为拍板）：**整链一起消失**（链未齐终态保留主图，链齐整链同帧进归档，§3.4）；**锚点设计废除**（§3.2）；**详情必须完整可见**（层级方案 §5.8b）；**全局空白点击收起**（§5.10）；**条目 24h TTL**（§5.9，TTL 值 24h 已定）。

## 15 参考链接

- Apple HIG — Sidebars：<https://developer.apple.com/design/human-interface-guidelines/sidebars>
- Apple HIG — Motion：<https://developer.apple.com/design/human-interface-guidelines/motion>
- Material 3 — Easing & duration：<https://m3.material.io/styles/motion/easing-and-duration/tokens-specs>
- WAI-ARIA APG — Disclosure Pattern：<https://www.w3.org/WAI/ARIA/apg/patterns/disclosure/>
- Lucide icons（archive / inbox / arrow-left）：<https://lucide.dev>
- 内部：`~/.nebflow/docs/Nebflow/20260901_project-node-contract.md` · `20260903_flowmap-graphview-v3-final.md` · `20260902_flowmap-engine-evolution-design.md` · `~/.nebflow/docs/CONVENTIONS.md` · `~/.nebflow/skills/nebflow/visual-style/SKILL.md` · `~/.nebflow/skills/design-system/SKILL.md`
- 实现锚点（v1.4.1-beta.54 主仓）：`src/main/resources/web/js/flowMapTab.js` · `projectTab.js`（openFlowMapInPlace）· `flowViewers.js`（openViewerShell/openNodeResultViewer/openMailbox）· `flowTeams.js`（team-act-btn/mailBadge）· `viewers/shared.js`（canvas-source-toggle/addElementRefToggle）· `css/split.css`（canvas-ref-select 元素选择器悬浮钮）· `flowCss.js`（flow-viewer/flow-mail-*）· `css/flowMap.css` · `css/projectPanel.css`（flowmap-nav-bar）· `index.html`（全局样式挂载）· 后端事实 `NodeEngine.scala` / `NodePayload` / `NodeTools.scala` / `RestApiRoutes.scala` / `FlowMapStore.scala`

## 16 原型现场修正记录（v1，2026-09-03 19:10，design-engineer）

交互原型 v1 以真实快照驱动（nebflow-website `flow-map.json` 直读：11 活动 + 25 归档条目），回归 46/46 PASS。现场修正（已回写正文相应节）：

| # | 修正点 | 发现经过 | 落点 |
|---|---|---|---|
| C1 | `transform-origin: 100% 0` 的浏览器**计算值**是像素（面板宽 380px → `380px 0px`），非百分比字符串 | 回归 A4 断言按 `100% 0` 字符串比对失败 | §11-A5 断言口径沿用像素等价（v2 变为面板宽−20） |
| C2 | 多锚点行整体对齐未定义，原型实测居中观感最优（单锚点正对卡中心、双锚点对称） | §3.2 只定义了 in 左 deps 右，未定义整行水平基准 | §3.2 补「整行以卡片水平中线居中」 |
| C3 | 条目行 1 五元素在 380px 面板的 flex 分配未定义 | 原型长节点名实测挤占时间戳 | §5.3 行 1 规则（v2 条目元素已改：徽章/链名/节点数徽标/时间/指示符） |
| C4 | 原型发现并覆盖的规格外实现决策：①`hidden` 属性必须被 `[hidden]{display:none}` 显式保护（面板 display:flex 会压过 hidden 语义）；②补位动画需在「重建 DOM 后从旧位写到新位」两步之间插 reflow（rAF 包裹验证可行） | 原型调试实证 | 本条备查（实现注意项） |
| C5 | v1 为演示 failed 徽章与 failed 空心环 deps 锚点补入 1 条拟真条目（`n-demo-fail-01`，已标注 src:"demo"）与其在 n-09cfa125.deps 的拟真接线；其余 24 条全部真实 | 真实快照当前无 failed/deps 边实例 | 仅原型数据，不涉产品（v2 沿用该拟真标注） |

## 17 v2 修订记录（2026-09-03 19:20 作者四条反馈 → 落点，design-engineer）

交互原型 v2 仍以真实快照驱动（11 活动 + 25 终态；**终态节点已补齐 in/out/deps/createdAt**——磁盘归档区完整 NodeDef 实测核实，链派生输入），链派生产出 **12 条已归档链（16 节点）+ 8 条缓冲链（9 终态节点待命）**，回归 `regression.mjs` **76/76 PASS**（Playwright，双主题 + 375px + reduced-motion 三上下文）。

| 反馈 | 落点 |
|---|---|
| ① 归档粒度 = 整条链 | §3.5 重写（批次窗口判定语义）+ §5.3 条目改链构型 + §7.2 链派生管线 + §8.13–8.15 新边界 + §11-A15 链粒度断言；v1「模拟节点完成逐个滴进归档」演示废弃 → 批次语义模拟队列（链齐整链归档 / 链未齐缓冲 toast） |
| ② 右侧详情保留 | §5.8 新增（主图点节点 + 链内点成员两路触发；220ms 同曲线滑出；Esc 分层）；§5.5 条目正文承载移至详情面板 |
| ③ 画布内悬浮钮 | §4 重写（元素选择器 `.canvas-ref-select` 逐值同语言：40px 圆形玻璃、opacity .6→1、active sapphire）；§6.1 origin 改 `calc(100% - 20px) 0`（悬浮钮圆心）；nav-bar 恢复纯净 |
| ④ 保持不变项 | mailbox 条目质感（§5.3 flow-mail-row 原值）、展开动画曲线（§6）、hover 联动（§5.6）、双主题（§8.10）、禁 emoji/文字不出卡/克制——全部保留并有回归覆盖 |

v2 现场修正：

| # | 修正点 | 发现经过 | 落点 |
|---|---|---|---|
| C6 | **指针捕获时机**：视口 `pointerdown` 即 `setPointerCapture` 会把后续 click 重定向到视口，卡片永远点不开详情 → 改为拖拽成立（位移 >3px）后才捕获 | 原型接详情点击后实测点不开 | §5.8 拖拽保护（实现注意项） |
| C7 | 回归断言的页面上下文必须显式区分（A18 曾把 `page.evaluate` 笔误成 ctx1 页面导致误 FAIL）——多上下文回归脚本注意项 | 排查 A18 假 FAIL | 本条备查（qa 转 Playwright 时注意） |
| C8 | **语义洞察**：批次窗口口径下「活动节点的上游皆同批兄弟」→ 已归档链在活动图中通常无下游，hover 联动在跨批引用形态（后续批 `in` 已归档链成员）下激活；本快照无活体实例，机制由回归钩子覆盖。接线连通口径会结构性过并（6 节点跨三任务并一链），已否 | 链派生算法在真实数据上验证（36 节点逐条分析 createdAt 突发与接线） | §3.5 被否方案 + §5.6 语义注记 + §11-A11 |
| C9 | 拟真条目 `n-demo-fail-01` 的 createdAt 需落在孤立时间点（不与任何真实批次同窗口），否则会被批次窗口并入真实链 | v2 批次口径下首跑发现并入清场-v2 批 | 仅原型数据（snapshot-data.js 已修，标注不变） |

## 18 v3 修订记录（2026-09-03 20:26 作者五条反馈 → 落点，design-engineer）

交互原型 v3 仍以真实快照驱动（输入 36 节点：11 活动 + 25 终态；终态节点完整 NodeDef 含 in/out/deps/createdAt）。**TTL 基准 = 快照最新时刻**（快照时间跨度 >25h，最老 3 条真实链按 24h TTL 自然过期）→ v3 基线 = **徽章 10 链 / 14 节点**（9 真实存活 + 1 TTL 演示链）+ **主图可见 20 卡**（活动 11 + 终态保留 9）。回归 `regression.mjs` **88/88 PASS**（Playwright，双主题 + 375px + reduced-motion 上下文）。

| 反馈 | 落点 |
|---|---|
| ① 整链一起消失 | §3.1 渲染口径改 visible = 活动 + 链未齐终态保留；§3.2 终态保留卡（`.fm-node.terminal` 终态色环/opacity .78/name dim/title 标注）；§3.4 原地转终态 + **链齐整链同帧 `fm-exit` 集体淡出**（§6.1b 新增）；§11-A1/A10/A16 断言 |
| ② 锚点设计整体删除 | 锚点 DOM/CSS/图例/钩子全删；§3.2 锚点方案废除声明（in 边改代理渲染承接层级语义，§3.3）；§5.6 hover 联动去锚点；§11-A2 反向断言（DOM 无 `.fm-anchor*`） |
| ③ 详情面板必须完整可见 | **§5.8b 层级方案新增**：z 层表（面板 60 / 详情 70 / toast 80）+ 宽视口（≥800px）同开时详情 `.dock-left` 右移 404px 与面板并排零重叠 + 窄视口退回同位 z-70 浮前；§11-A9/A20 断言（elementFromPoint 命中详情自身） |
| ④ 全局空白点击收起 | §5.10 新增：单个 document **click** handler（非 pointerdown，规避拖拽/选择冲突）+ 拖拽豁免（位移 >3px）+ 节点卡 stopPropagation 豁免 + 面板/悬浮钮/详情内部豁免；§8.17 边界；§11-A11 断言 |
| ⑤ 条目 24h TTL | §5.9 新增：`ARCHIVE_TTL_MS = 24h`（按链完成时间）、到期整链清理 + **成员从派生输入表删除（防链重判复活）** + 徽章/条目同步减、30s 轮询 + 快照导入触发；「即将过期」标签（<1h）；原型 TTL 演示链 `n-demo-ttl-01`（createdAt 落在快照空窗防误并）；§8.18 边界；§11-A18 断言 |

v3 现场修正：

| # | 修正点 | 发现经过 | 落点 |
|---|---|---|---|
| C10 | **TTL 清理必须把到期链成员从派生输入 nodes 表一并删除**，否则下一轮 refreshChains 会按缓冲数据把已清理链复活 | 模拟 TTL 清理后徽章数复涨，回归 A18 暴露 | §5.9 实现注意项（已在原型 purgeExpired 落实） |
| C11 | TTL 演示链 `n-demo-ttl-01` 的 createdAt 必须落在快照 createdAt 分布**空窗**（首次落在「诊断-LogtoRequestError」批次 120s 窗口内被误并） | v3 首跑徽章 11 而非预期 10，逐链排查 | 仅原型数据（snapshot-data.js 已修，类比 C9） |
| C12 | **种子期 TDZ**：seedFromSnapshot 内直接调 purgeExpired 会引用尚未声明的 DOM const（面板 body），须改由启动路径与重置路径调用 | v3 首跑 ReferenceError | 本条备查（实现注意项：清理函数不得在 DOM 装配前的种子路径内联调用） |
| C13 | 面板 DOM 跨开合保留链展开态 → 回归/验收脚本点击条目前须先查 expanded 态，否则「点开」实为「收起」 | A8 断言偶发 FAIL 排查 | 本条备查（qa 转 Playwright 时注意，类比 C7） |

v2 演示链（作者反馈①②③ + 反馈④保留项）：①主图 DAG 只显活动节点（11 卡）+ 完成上游锚点（11 个）；②**画布内右上角 40px 圆形玻璃悬浮钮**（opacity .6、hover 放大、active sapphire）+ 12 计数徽章；③点悬浮钮拓展开合（220ms/160ms，**origin = 悬浮钮圆心**，外点/Esc/再点/✕ 四路关闭 + 焦点归还悬浮钮）；④**mailbox 链条目**（链完成时间倒序、节点数徽标、独占式展开成员列表、成员点击 → **右侧详情面板**滑出展示完整结果；主图点击节点同入详情）；⑤暗亮双主题；⑥「模拟节点完成」演示链粒度归档全链：注册修复链整链 2 节点一次归档（含早已完成的诊断成员）→ 清场重建批归档 + 新锚点浮现 → **邮件链 3/4 完成时卡消失但面板不动（链未齐缓冲 toast）→ 4/4 完成瞬间 4 节点整链一次归档置顶闪烁** → 连击至全终态空态（20 链）+ 悬浮钮可用。

---

> **规格终态**：作者拍板 §14 八项 → 状态 frozen → dispatch nebflow-project Frontend → qa-frontend 断言 → design-engineer 视觉评审 → shipped。
