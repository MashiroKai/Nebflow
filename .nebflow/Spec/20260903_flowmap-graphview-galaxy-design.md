> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Flow Map 图谱视图 v2.1——星系风格设计规格书（Galaxy View 范式 × 径向轨道拓扑）

> **[SEALED 2026-09-03 09:40] 作者裁定 Galaxy 风格舍弃，最终方向=v1 DAG 层次+简约背景+交互升级，见 v3 原型**（`assets/20260903_flowmap-graphview/prototype-v3.html`，回归 20/20 全绿）+ 精简说明 `20260903_flowmap-graphview-v3-final.md`。本文全部 Galaxy 视觉章节（径向轨道布局/星体/光轨/深空背景）就此封存，仅供追溯；v2.2 中与视觉无关的交互裁定（result 内联直显、产品现有节点样式）已由 v3 继承。
>
> **附记（2026-09-04，验证收尾，不改变封存状态）**：v2.0 星体原型（`assets/20260903_flowmap-graphview-galaxy/prototype.html`）断言回归 **19/19 全绿**、控制台零错误；A14/A15 两条失败项根因=原生 focus scroll-into-view 编程滚动 overflow:hidden 容器（M15 陷阱），已在原型层修复并归档——根因/修法/证据见 `20260904_flowmap-galaxy-v2-verify-closeout.md`；**v3 原型仍有 stage 层未设防的同款隐患，实施方必读 closeout §4**。
>
> **状态**：sealed（v2.2 draft 封存于 2026-09-03 09:40 作者裁定；此前状态：draft v2.2，v2.1 径向轨道布局已经作者拍板冻结「用这一版」；v2.2 两处修订：①节点样式回归产品现有样式 ②详情面板结果内联直显）
> **v2.2 修订依据（作者原话，2026-09-03）**：「节点样式要利用我们现有的样式，我们只是优化交互逻辑」——Galaxy 重做的边界=布局算法+相机交互+背景氛围，节点视觉语言必须与产品现有 Flow Map 完全一致（现有状态色、节点 chip/card 结构、字号字重），不发明新样式；「节点结果直接在详情页展示，不需要说再有一个按钮来弹窗显示完整结果」——L2 面板 result 内联直显，「查看完整结果」按钮与二级弹窗作废。
> **作者需求原文**（2026-09-03）：「画面固定比例。不设置滚动条。通过交互来查看具体细节。基础的有一些信息，hover 能放大一点，hover 后点击，能再次放大查看详细信息。」+ 同日拍板：参考 Obsidian Galaxy View 插件的星系隐喻重做，与 nebflow 彩色星云品牌契合；DAG 语义不丢；相机骨架沿用 v1 已裁定部分。
> **v2.1 重设计依据（作者布局批评原文，2026-09-03）**：「我选择这个方案的原因就是 DAG 层次布局有问题。比如它会分层。由于没有依赖的占大多数，第一层的是最多的。然后一般不会超过三层，这就导致了横向特别长，纵向空间没怎么利用。」——§2.2 据此推翻层次盘面，改径向轨道布局。
> **日期**：2026-09-03 · **产出**：design-engineer · **前版**：`20260903_flowmap-graphview-design.md`（v1，已封存，其 §3 相机数学 / §4 防抖结论 / §8 断言口径被本文沿用）
> **基线**：v1 封存稿 @cf1ce3e + 其可交互原型 `assets/20260903_flowmap-graphview/prototype.html`（交互骨架参照物）；**v2.2 可交互原型**：`assets/20260903_flowmap-galaxy/prototype.html`（v2.1 原型就地修订：真实快照 32 节点内嵌 + 产品现有节点卡 + result 内联 markdown；CAD 缩放/L1/L2/双主题全交互，Playwright 回归 15/15 全绿：锚点漂移 0.14px、hover/click/chip 跳转/reduced-motion 全过）；主仓 flowMapTab.js / flowMap.css / flowCss.js / nodeData.js 现状。
> **边界**：本文档为设计规格，不改产品代码；实施由 nebflow-project 按本文 §12 派发。

---

## 0. 一句话目标

把 Flow Map 从 v1 的「毛玻璃紧凑卡图谱」升级为**星系风格执行拓扑图**：**径向轨道布局**（v2.1 冻结）与相机骨架承载几何层，视觉层 = **产品现有节点卡 + 光轨边 + 四层深空背景**（v2.2 裁定：节点视觉语言与产品 Flow Map 完全一致，星系感由布局/相机/边/背景氛围交付，节点不换皮）——与 nebflow 彩色星云品牌同源；三级渐进信息（L0 全景星图 → L1 hover 邻域聚焦增亮 → L2 点击 fly-to + 毛玻璃详情面板）完整保留，L2 面板 result 内联 markdown 直显（v2.2）；六状态/方向/deps/双主题/reduced-motion 语义零损失；固定比例零滚动条铁律不变。

**设计公式**：`v2.2 = 径向轨道布局（冻结） + 产品现有节点卡（v2.2 裁定，§3.1/§4.1） + v1 交互骨架（已裁定） + Obsidian Galaxy View 视觉语言（仅光轨/深空氛围层） + DAG 执行拓扑语义（不可妥协）`。

---

## 1. 参考与依据

### 1.1 Obsidian Galaxy View 插件（作者点名参考，本文主要视觉源）

社区插件页 https://community.obsidian.md/plugins/galaxy-view · README（obsidianstats.com 镜像全文，repo `Longwind1984/galaxy-view`，v0.6.1）。提炼的可执行规则：

| # | Galaxy View 原文事实 | 提炼规则 | v2 采纳方式 |
|---|---|---|---|
| R1 | 深空背景拆四层：**star shell（星幕）/ nebula veil（星云面纱，baked once + 主题着色）/ drifting field stars（漂移星场，视差）/ cluster mist（簇雾，拥抱高密度区）** | 背景不是一张图，是可独立控制、可按主题着色、可分视差速度的**图层栈** | §3.3 全盘采纳（2D 化：视差系数分层，canvas 一次性烘焙） |
| R2 | 「thin **desaturated** link filaments」默认低饱和细光丝；focus 模式「highlights its links at **full saturation**」 | 边默认克制低饱度，聚焦时局部满饱和——**对比造重点**，不是全线高亮 | §3.2 边三态 + hover 满饱和聚焦 |
| R3 | links 可弯曲离开核心（linkCurve 滑杆，0=直线，默认 0.35）；「curved **filaments**」 | 连线美感优先 = 曲率参数化（贝塞尔弓出），非机械直线 | §3.2 曲率 c=0.3 定值（不做滑杆，克制） |
| R4 | cinematic camera：点击笔记 **fly to**（相机滑翔不跳变）；开图时 reveal 动画「**blooms the galaxy out of the center**」 | 进出图谱都要有「星系绽放/滑翔」的仪式感，但一次性、不打断操作 | §5 G1 首挂载 reveal（~700ms）+ C6 fly-to 沿用 v1 |
| R5 | 暗色「always-dark deep space」与亮色「designed **ink-on-paper** light mode」两条视觉方向 | 暗色=深空是本色；亮色必须**专门设计**（墨点星图），不能暗色反色了事 | §3.4 双主题：暗=深空，亮=晨图墨点 |
| R6 | 移动端质量分层：关 postprocessing、按度数取 top 节点、底栏卡片 | 质量≠全开：**档位化降级**是设计的一部分，不是事后优化 | §9 三档质量档位 T0/T1/T2 |
| R7 | 3230 nodes / 19337 links @ 60fps，<10 draw calls，力模拟在 Web Worker | 性能数字是愿景参照；Nebflow 规模几十节点，DOM/SVG 路线可达同等流畅 | §9 保留 DOM/SVG 主路线，>150 节点才评 canvas |

### 1.2 既有裁定与内部依据（约束源，优先级高于 1.1）

| 来源 | 约束 | 链接 |
|---|---|---|
| 作者硬约束（2026-09-03） | 画面固定比例零滚动条；三级渐进 L0/L1/L2；DAG 语义（方向+状态+deps）星系视觉下必须可辨；相机骨架沿用 v1 已裁定部分；缩放时鼠标下内容不动 | 本文任务下发原文 |
| 作者布局裁定（2026-09-03，**v2.1 布局依据**） | **推翻 DAG 层次盘面**：无依赖节点占大多数 → 分层布局第一层堆积过半；层数通常 ≤3 → 横向极长、纵向浪费。布局改为径向/轨道式 2D 均衡分布，方向语义改「由内向外」可读 | 本文任务下发原文（批评全文见文首） |
| 作者 v2.2 裁定（2026-09-03，**节点样式 + 面板结果**） | ①「节点样式要利用我们现有的样式，我们只是优化交互逻辑」：节点=产品现有 .solar-node 卡（状态色/卡结构/字号字重全沿用，v2.1 六状态星体作废）；②「节点结果直接在详情页展示，不需要说再有一个按钮来弹窗显示完整结果」：L2 result 内联直显，无「完整结果」按钮/二级弹窗 | 本文任务下发原文 |
| v1 封存稿 @cf1ce3e | 相机状态机 C1-C8 与 CAD 缩放公式、布局案权衡（力导向否决）、三级渐进信息架构、详情面板数据超集、防抖参数、A1-A14 断言口径 | 20260903_flowmap-graphview-design.md |
| canvas-zoom-follow-cursor-spec | CAD 缩放数学方案 A（锚点公式 `T′=P−(P−T)·k`） | ~/.nebflow/docs/Nebflow/canvas-zoom-follow-cursor-spec.md |
| visual-style 铁律 | 详情面板/弹层毛玻璃、禁 overlay 暗化；控件玻璃质感；字重 400/600/500；低调克制专业感 | ~/.nebflow/skills/nebflow/visual-style/SKILL.md |
| deps 边设计 | deps 虚线 `3 4` 双重编码 + 图例 + 有 deps 非根 | 20260902_flowmap-engine-evolution-design.md §1 / 20260902_deps-dependency-connection-design.md §D.1 |
| design-system 案例库 | 图谱视图**首例**，无同类先例可复用；案例 001 共性教训直接适用：**二值断言口径必须在规格书里显式到可执行** | ~/.nebflow/skills/design-system/SKILL.md |

### 1.3 范式冲突取舍（三处，均以「克制专业 + DAG 语义优先」裁决）

1. **Galaxy View 的 3D 飞行 / 自由飞行（WASD）/ idle cruise 自动漂移**：**不采纳**。理由：Flow Map 是工程读图界面，自动漂移破坏「正在读拓扑」的心智定位；3D 增加方向语义歧义（DAG 上游→下游在 3D 里不可靠）；v1 §2.4 已裁定执行拓扑不吃力导向/有机布局。星系感由视觉层（星体/光轨/深空）交付，几何层保持 2D 确定性。
2. **Galaxy View 的 8 视觉预设 / 6 配色主题（cyberpunk、Matrix…）**：**不采纳**。Nebflow 品牌色只有 sapphire 星云系 + 语义四色，预设选择器=花哨（违反 visual-style 总基调）。只做暗/亮双主题（R5）。
3. **Galaxy View 力导向布局（Web Worker 模拟）**：**不采纳**（v1 §2.4 裁定沿用：方向语义、WS 实时稳定性、确定性、性能四条理由全部继续成立）。星系隐喻落在视觉皮，不落在布局骨。

---

## 2. 布局与信息架构（含 L0-L2 三级渐进）

### 2.1 视口结构（零滚动条，v1 骨架 + 深空图层栈）

```
flowmap-view（容器，overflow:hidden）
├── fm-viewport（固定视口 = 相机，overflow:hidden，监听 wheel/pointer）
│   ├── fm-bg-near（canvas，星云面纱+簇雾，视差 0.6×，resize/theme/layout 时重绘）
│   ├── fm-bg-far（canvas，漂移星场 ≤200 星点，视差 0.3×，resize/theme 时重绘）
│   ├── fm-world（世界坐标系，transform: translate(tx,ty) scale(s)，SVG 边层 + 节点卡层）
│   └── fm-vignette（纯 CSS 径向渐晕，固定不随相机，四角压暗 4-8%）
├── fm-detail（右侧详情面板，毛玻璃，选中态出现，v1 §3.4 内容超集不变）
├── fm-hud（右下：−/百分比/+/⤢fit 玻璃控件簇，v1 不变）
└── fm-legend（左下图例：输出边三态 + deps 虚线，v1 不变）
```

- `.solar-scroll` 保持退场；**任何容器层级不得出现滚动条**（A1 断言，口径沿 v1：overflow:hidden 渲染 + 页面根不可滚）。
- 背景双层 canvas 在 **fm-viewport 内、fm-world 下**，各自维护视差 transform（单点 `applyCamera()` 同步驱动三层 transform：world 1.0×/near 0.6×/far 0.3×）。
- 渐晕层 `pointer-events:none`；背景 canvas 均不拦截事件（事件监听在 fm-viewport 上，v1 同）。

### 2.2 布局：径向轨道盘面（v2.1 按作者批评重设计，推翻层次盘面）

> **重设计依据 = 作者批评原文**（2026-09-03，逐字）：「我选择这个方案的原因就是 DAG 层次布局有问题。比如它会分层。由于没有依赖的占大多数，第一层的是最多的。然后一般不会超过三层，这就导致了横向特别长，纵向空间没怎么利用。」
>
> **真实快照验证**（`.nebflow/flow-map.json` @2026-09-03，32 节点：wiring 17 / completed 9 / running 3 / cancelled 3，in 边 25 条 + deps 边 0 条）：有边连通分量 6 个（最大 4 节点），**孤立节点 17 个（53%）**；DAG 最大深度仅 2。层次盘面下 21 个节点挤第 0 层横队、画幅约 2700×420（纵横比 ~6.4）；径向轨道盘面世界画幅 **~1340×1340（纵横比 1.0，v2.2 卡宽口径实测）**——纵横两维均衡利用，正是批评要求的形态。可交互原型 `assets/20260903_flowmap-galaxy/prototype.html` 内嵌该真实快照，供作者按真实项目形态检验。

**新布局：径向/轨道式 2D 均衡分布**——依赖链从中心向外辐射（每个依赖深度一圈轨道），无依赖的大多数节点按角度散布在轨道环/星野带；分层语义弱化为**轨道深度**（中心=root/入口，外圈=叶/交付端），流动方向改为「**由内向外**」可读（沿轨渐变+终点箭头+流光三通道照旧，§4.2）。

**确定性算法五步**（O(V+E) 单次计算，无迭代模拟、无随机布局——v1 §2.4 确定性/性能裁定继续成立；伪随机仅用于抖动/星野散布且按 id 种子固定）：

| 步 | 名称 | 规则 |
|---|---|---|
| ① | **轨道深度** | `depth(v) = 0`（无 in-map 上游）否则 `1 + max(depth(上游))`；上游 = `in` + `deps`（**deps 纳入深度计算的既有语义保留**）。深度 d → 轨道环半径 `r(d) = R0 + d·RING`，定值 `R0=92`、`RING=160`（可按视口纵横比微调，比例不变） |
| ② | **旋臂扇区** | 图按边做无向闭包分连通分量；分量按（规模降序，最小 id 升序）排序，各分一块角向扇区，扇区宽 ∝ 分量节点数，总利用率 `U=0.82`（臂间留隙）。分量内自根 BFS 递归分配子扇区：子扇区宽 ∝ **后代闭包规模**（叶多的支脉更宽），节点取子扇区角心 |
| ③ | **环上防重叠** | 每环节点按角排序，最小角间距 `Δθ_min = 132px / r(d)`（v2.2：弦距 132px = 卡宽 124px + 呼吸 8px，原 60px 星体口径随节点卡作废），违反对交替向两侧推移，3 轮收敛；星野带同法 2 轮、`Δθ_min = 0.26 rad`（卡宽适配） |
| ④ | **孤立节点星野带** | 度数 0（无任何边）**不排队**——散布外围环带 `[r_max+84, r_max+194]`；角度与半径由 `id 哈希 → mulberry32` 种子确定性生成（跨刷新一致）。呈现微降调（v2.2）：**仅整卡 opacity 0.82**，卡内字号/结构不动（产品卡样式不因带位置改变），**L1/L2 交互不降级** |
| ⑤ | **向心力密度梯度** | 枢纽星内收：`r_actual = r(①) × (1 − KAPPA·min(deg,5)/5)`，`KAPPA=0.12`——连接度越高越贴近银河核，形成核密外疏的星系密度梯度；叠加 id 种子 ±5px 径向抖动，破机械同心圆感 |

**几何常量**（v2.2 随节点卡更新）：节点卡 = 产品 124px 宽 × 内容高（min-height 88px，带 result 摘要 ~110px）；`R0=92 / RING=160 / U=0.82 / KAPPA=0.12 / 星野带 84~194 / 弦距 132px`（星等三档 NODE_R 已随 §3.1 作废）。命中区 = 卡本体（≥24px 自然满足）。32 节点真实快照 fit ≈0.61 全图可见——「节点多时整体缩小、永不滚动」不变；L2 fly-to ≥1.6 时卡内 12px 标签恢复产品原尺寸可读。

**WS 增量重排**：新节点只影响其所在旋臂（连通分量）——该扇区内局部重算，**其余旋臂角向锁定不动**；新增深度层才触发全环半径重排（实测层数 ≤3、增层罕见，v2 对案 B 的「增层全环重排」顾虑在此规模下可接受）。

**布局裁决变更记录**：v1/v2 案 A（层次盘面）依作者批评**废弃**；v2 案 B（同心轨道环）升级为**案 B′ 径向轨道盘面**（即本节五步算法）采纳；案 C 力导向维持否决。v2 否决案 B 的两条理由逐条消解：①「跨环边横穿盘面」→ 旋臂扇区化使同臂边留在臂内，跨臂边（快照中 0 条）经曲率侧弓避让；②「增层全环重排」→ 见上，规模实证可接受。相机骨架 C1-C8、三级渐进 L0-L2、星体/光轨视觉（§3-§6）与布局解耦，全部照用。

### 2.3 三级渐进信息架构（L0-L2，触发链沿 v1，载体换星系皮）

| 级别 | 触发 | 用户看到 | 星系视觉呈现 |
|---|---|---|---|
| **L0 全景星图** | 默认 | 全图一屏：所有节点卡 + 光轨边一览；运行中节点轨道旋转、result 摘要高亮（产品语义在深空底上天然醒目） | 节点=产品 .solar-node 卡（毛玻璃+轨道环，§3.1）；边=低饱和光丝；深空背景静场 |
| **L1 邻域聚焦** | pointerenter 节点卡 | 该卡+直接邻居+邻接光轨**提亮放大**，其余星域**压暗**（光污染降低）；含 80ms 离开宽限 | 自身/邻居卡 scale 1.15（v1 hover 语义，载体=卡）；邻接边满饱和（R2）；非邻接卡 opacity→0.16、非邻接边→0.06 |
| **L2 节点详情** | click（≤3px 位移且 <400ms） | 相机 fly-to（节点居中 + scale≥1.6，280ms 滑翔）+ 右侧 320px 毛玻璃详情面板滑入：状态徽章/agent/worktree/上下游邻接（可点击跳转）/blocked 反馈面板/**result 内联 markdown 直显（面板内滚动，v2.2）** | 面板=深空玻璃皮（`--glass-bg`+blur+左缘 1px `--glass-border`），**无 overlay 遮罩**（铁律 1）；背后星域保持可交互（N5 切换选中）；**无「完整结果」按钮、无 `openNodeResultViewer` 二级弹窗（v2.2 作废）** |

**渐进一致性**：L1 是 L2 的预览（信息不倒挂——L1 只透露邻接关系与状态亮度，详情一律 L2）；L0→L1→L2 亮度与细节单调递增，无跳变断层。

### 2.4 多项目聚合分组（预留，沿 v1 §2.5 语义，v2.1 随径向布局改形）

单项目图无分组；未来多项目图按项目分**扇区带**（星系语境：每项目一条角向扇区族，可含多条旋臂 + 对应星野带段，带首项目名 chip 置于扇区中角外沿，扇区族间以簇雾色差 3% 区分）。数据面 NodeList 已带 project 归属，纯前端扩展，不影响本设计。

---

## 3. 视觉体系：产品节点卡 / 光轨边 / 深空背景

### 3.1 节点卡（v2.2 裁定：产品现有样式逐字沿用，替代 v2.1 星体三层）

> 作者裁定：「节点样式要利用我们现有的样式，我们只是优化交互逻辑」。**节点本身的视觉语言（状态色、卡结构、字号字重、圆角、玻璃质感）与产品现有 Flow Map 完全一致**，本节只定义「产品卡 × 径向盘面」的落位适配，不发明任何新卡样式。本文余下章节的「星体」词面泛指节点卡载体。

- **结构与样式来源**（实现 = 零新卡样式，复用现有类）：`flowMapTab.js nodeHtml()` 的卡结构原样沿用——`.solar-node.fm-node.{status}`（status 经 `nodeData.NODE_STATUS_CLS` 映射，wiring→pending，`data-status` 保留原值）内含 `.solar-orbit`（3 环 3 点，30px）→ `.fm-node-head`（wt 徽标 + ✓/✗/⚑/— 状态图标）→ `.solar-node-label`（12px/600 单行省略）→ `.solar-node-sub`（agent + ⏱TTL，10px muted）→ `.fm-barrier-hint`（barrier ×N）/ `.fm-wait-note`（⏳ 等待脚注，名(id)/裸id✦）/ `.fm-result-summary`（46 字摘要省略，running 态蓝底「运行中…」）；样式定义在 flowCss.js（FLOW_CSS 注入 `.solar-node` 基座）与 flowMap.css（`.fm-node` 扩展字段 + 六状态变体）。
- **径向盘面落位适配**（仅几何，不改样式）：卡中心 = 布局点（`translate(x,y) translate(-50%,-50%)`）；边锚点 = 卡中心（光轨终端被卡片覆盖属正常层次）；轨道旋转动画沿用 flowAnim.js rAF 语义（仅 running 旋转，相位 0/120/240、方向 +−+、周期 3/4.5/6s；终态停基准角）。
- **hover/选中**（冻结交互语义，载体=卡）：hover 自身/邻居卡 scale 1.15（§2.3 L1）；选中 sapphire 描边 `border-color: rgb(var(--sapphire)/.65)` + `box-shadow: 0 0 0 3px rgb(var(--sapphire)/.18)`（v1 `.fm-selected` 语义平移）；产品 `:hover brightness(1.04)` 保留。
- **孤立星野带**：仅整卡 opacity 0.82，卡内不动（§2.2 ④）。
- **hit 目标**：卡本体即命中区（124×88，≥24px 规则自然满足），`tabindex=0` `role=button`。
- **作废**：v2.1 星体三层（glow/core/label）与星等编码（尺寸=连接度）整体作废；连接度信息由布局向心力（KAPPA）承载，不再做尺寸编码。

### 3.2 边光轨（Obsidian 式连接展示：美感优先，R2/R3 落地）

- **几何**：三次贝塞尔（沿 v1），曲率 c=0.3（控制点垂直偏移 = 0.3×边长，方向按**径向流向统一侧弓**——内→外统一顺时针法向，同环并行边交替左右避免重叠；v2.1 随布局由「层间流向」改「径向流向」，弓向规则不变）。
- **低缩放光轨增强**（v2.1 原型实测补充）：fit scale <0.85 时 trail 描边 1.5→1.8px、渐变端点 alpha 0.35/0.95 → 0.5/1.0——原型在 fit≈0.75 下 1.5px 光轨不可辨，方向语义保真优先于定值；hover 满饱和规则不受影响。
- **渲染（双层描边法，禁 per-edge SVG filter）**：
  - **halo stroke**：宽 5px、`rgb(状态色 / 0.12)`、`stroke-linecap: round`——光轨的「晕」；
  - **trail stroke**：宽 1.5px、状态色满色、圆帽——光轨的「芯」。
- **方向编码（三重冗余，保证星系视觉下不丢）**：
  1. **沿轨渐变**：`linearGradient` 沿路径从源端 `alpha 0.35` → 目标端 `alpha 0.95`（能量流向下游变亮）；gradient 单元 `userSpaceOnUse`，端点=路径首尾；
  2. **终点箭头**：目标端 3px 状态色圆点（v1 语义）；deps 空心 2.5px；
  3. **inflight 流光**：流动虚线仅向下游方向行军（dashoffset 递减），动态方向线索。
- **状态三态（沿 v1/deps 设计定值，换光轨皮）**：

| 态 | halo | trail | 动效 |
|---|---|---|---|
| delivered（输出） | sapphire/0.12 | `rgb(var(--sapphire))` 渐变芯 | 无（静轨） |
| inflight（输出） | sapphire/0.15 | `rgb(var(--sapphire-glow))` 亮芯 | flow-dash 行军 1.2s ∞ |
| idle（输出） | 透明 | `--color-border` 1.5px 静丝 | 无 |
| deps（双类型） | 透明 | 状态色虚线 `3 4`（delivered=success/inflight=warning 蚁/idle=border） | 仅 inflight 蚁行 |

- **聚焦联动（L1）**：邻接边 halo 0.12→0.22 + trail 满饱和（R2「full saturation」）；非邻接 opacity 0.06（v1 同值）。

### 3.3 深空背景图层栈（R1 四层 2D 化，性能分层见 §9）

| 层 | 内容 | 视差 | 重绘时机 | 克制约束 |
|---|---|---|---|---|
| ① 星幕基底 | viewport 底色：深空径向渐变（暗主题 `--color-bg` 上叠 sapphire 4% 中心晕；亮主题纸白） | 固定（不随相机） | theme | 渐变中心=视口中心偏上 40%，模拟「银河平面」环境光 |
| ② 星云面纱 nebula veil | 2–3 个大型径向渐变斑（sapphire 5% / amber 3% / sapphire-glow 4%，位置由布局质心+固定种子偏移决定），**baked once**（R1） | 0.6× | layout / theme | alpha ≤5%；亮主题降为 3%（晨图淡彩）；禁动画（静态氛围） |
| ③ 漂移星场 field stars | ≤200 颗 1–2px 星点，固定伪随机种子（确定性，跨刷新一致），亮度三档（text-dim 系 alpha .25/.45/.7） | 0.3× | theme / resize | 亮主题=墨点（`--color-text` alpha .12–.3）；**永不闪烁**（闪烁=花哨，违反克制；reduced-motion 下本层本就静态） |
| ④ 簇雾 cluster mist | 拥抱高密度区（度数质心邻域）的大型柔光，sapphire 4% | 0.6×（与②同 canvas） | layout / theme | 仅暗主题启用；亮主题关闭（墨点纸面不需要）；节点 <8 时自动关闭 |
| ⑤ 渐晕 vignette | 纯 CSS 径向渐变四角压暗 | 固定 | theme | 暗主题 8%、亮主题 4%；`pointer-events:none` |

②④ 合绘 `fm-bg-near`（0.6×），③ 绘 `fm-bg-far`（0.3×），① 为 viewport CSS 背景，⑤ 为 CSS 覆层——**共 2 个 canvas**，全部 transform 驱动（GPU 合成），重绘只在 theme/layout/resize（§9）。

### 3.4 双主题（R5：暗=深空本色，亮=专门设计的晨图）

| 维度 | 暗主题（默认/优先） | 亮主题 |
|---|---|---|
| 基调 | deep space：近黑蓝底，节点卡为深空玻璃，光轨为光源 | ink-on-paper 晨图：纸白底，节点卡=白玻璃（产品亮主题值），光轨为细墨线+状态色芯 |
| 节点卡（v2.2） | 产品暗主题卡值逐字：卡底 `rgba(24,28,38,.68)`、边 `rgba(255,255,255,.06)`、文字 `#e8eaed/#7b7e88`（base.css dark） | 产品亮主题卡值逐字：卡底 `rgba(255,255,255,.55)`、边 `rgba(255,255,255,.35)`、文字 `#1b1e26/#8b8e96`（base.css light）——双主题零适配成本（本来就是产品双主题样式） |
| 边/背景层 | 星系层继续用星系 token（sapphire 星云斑等） | 同左（星系层与卡层 token 分离，§3.5） |
| 全部色值 | **零新增色相**：卡层=产品 token 逐字；星系层一律 `rgb(var(--sapphire)/α)`、既有 `--color-*`/`--glass-*` 派生 | 同左 |

### 3.5 token 纪律（零新颜色 token，v1 纪律延续）

全部颜色经既有变量派生：星系层（背景/光轨/HUD）用 `--sapphire/--amber/--color-*/--glass-*` 派生；**节点卡层（v2.2）= 产品 token 逐字复用**（sapphire.css / base.css / flowCss.js / flowMap.css 现有定义；原型中以 `--p-*` 前缀别名映射便于评审比对，实现时直接用产品原 token、无别名）。**新增仅**：布局/卡几何常量（§2.2）、结构类名（`fm-bg-near/fm-bg-far/fm-vignette` 等）、canvas 重绘触发约定——无任何新 hex/rgb 字面色进产品（原型 `--p-*` 别名的值全部逐字来自产品文件）。

---

## 4. DAG 语义 → 星系视觉映射表

**总原则**（作者硬约束「DAG 语义不丢」的落地口径）：星系皮只允许**加通道**（亮度/尺寸/动效/渐变），不允许**替换或吞掉**既有语义通道。每个语义 ≥2 条独立通道（色弱可辨，v1 双通道纪律延续）。

### 4.1 节点状态 → 产品现有状态样式（v2.2 重写；v2.1 六状态星体形态作废）

状态视觉 = 产品 `.solar-node` 六态变体原样（flowCss.js / flowMap.css 现行规则，此处仅登记口径供断言；wiring 经 NODE_STATUS_CLS 映射为 pending 类，`data-status` 保留原值）：

| 状态 | 卡 class | 产品现有视觉（逐字沿用） | 通道数 |
|---|---|---|---|
| wiring / pending | `.pending` | 三环虚线 + 环 opacity .4 + 轨道点 .3 + 名称 opacity .4（未点燃降调） | 线型+透明度 |
| running | `.running` | 轨道 3 点 rAF 旋转（3/4.5/6s，相位 0/120/240，方向 +−+）+ result 摘要蓝底「运行中…」 | 动+色 |
| completed | `.completed` | 名称 opacity .6 + head ✓（#4caf50）+ result 摘要浮现；轨道点停基准角 | 色+图标 |
| failed | `.failed` | 环描边 `--color-error` #f44336 + opacity .5 + head ✗（#f44336） | 色+图标 |
| cancelled | `.cancelled` | head「—」，卡面无特殊变体（muted 即语义） | 图标 |
| blocked | `.blocked` | 琥珀环 + 卡描边/外光晕琥珀（`rgb(var(--amber)/.55)` + 双层 shadow）+ result 摘要琥珀化 + head ⚑（20260902 反馈路径设计沿用） | 色+环+图标 |

**语义保真红线**：每状态 ≥2 独立通道（上表末列），色弱单通道可判读；快照 32 节点六态中 failed/blocked 现值为 0，断言用 class 翻转验证（原型 V3 已做）。

### 4.2 连接语义 → 光轨形态

| 语义 | 视觉通道 | 判读方式 |
|---|---|---|
| **方向（上游→下游）** | ① 沿轨渐变（源 0.35→目标 0.95 alpha）② 目标端箭头圆点 ③ inflight 流光行军方向 | 任意一条通道单独可判读 |
| **边类型：输出 vs deps** | 输出=实芯光轨；deps=细虚线光丝 `3 4` + 空心箭头（v1/deps 设计双重编码原样保留） | 线型即类型 |
| **输出边三态** | delivered=静亮光轨（sapphire 芯）；inflight=行军流光（sapphire-glow 亮芯+蚁行）；idle=暗淡静丝（border 色） | 亮度+动效双通道 |
| **deps 边三态** | delivered=success 虚丝；inflight=warning 虚丝+蚁行；idle=border 虚丝 | 色+动效双通道 |
| **连接度（枢纽节点）** | 向心力内收（§2.2 ⑤：度数越高越贴近银河核）+ deps 入边计入 | 构图密度（v2.2：星等尺寸编码随产品卡作废） |
| **层间流向（读图线索）** | 布局**内→外**（轨道深度，v2.1 径向化）+ 曲率统一侧弓（不反弓） | 构图方向（非颜色） |

### 4.3 时间语义 → 星系事件（WS 实时）

| 数据事件 | 星系演绎 | 动效参数（详见 §6） |
|---|---|---|
| 节点新建（WS） | 卡点火：opacity 0→1 + scale .85→1（产品 fm-enter 语义） | 350ms ease-out（沿 v1 节点入场参数） |
| 节点完成 | 状态色平滑过渡（primary→success）+ 出边 inflight→delivered 渐变（流光熄灭为静亮轨） | 350ms（沿 v1 N7） |
| 节点失败/阻断 | 色过渡 + ✗/⚑ 图标浮现 | 350ms + 图标 150ms 延迟错峰 |
| 节点移除（TTL 到期） | 卡熄灭：opacity→0 + scale .8（产品 fm-exit 语义） | 300ms ease-in（沿 v1 出场） |
| 边新建 | 光轨生长：stroke-dashoffset 描画 | 450ms（沿 v1 边生长） |
| 全图首次挂载 | 星系绽放 reveal（G1） | ~700ms，一次性，reduced-motion 跳过 |

**语义保真红线**：任何星系动效不得掩盖状态变更本身——若 reduced-motion 或 T2 降档关闭动效，状态仍必须经**颜色与图标**即时可辨（双通道下限）。

---

## 5. 交互状态机

### 5.1 相机状态机 C1-C8（v1 已裁定，**逐条沿用不改**，含 CAD 数学）

| # | 当前态 | 事件 | 动作 | 次态 |
|---|---|---|---|---|
| C1 | autoFit | 布局变化（WS 增删节点） | 重算 fit，280ms 动画过渡（背景层视差同步） | autoFit |
| C2 | autoFit | wheel / 拖拽 | §5.3 / §5.4 对应动作 | **userNav**（autoFit 挂起） |
| C3 | userNav | wheel(Δy) | 锚点=光标 P：`s′=clamp(s·k)`，`tx′=P.x−k(P.x−tx)`，`ty′=P.y−k(P.y−ty)`，k=exp(−Δy·0.0015)（**即时无动画，鼠标下内容不动**——作者硬要求） | userNav |
| C4 | 任意 | pointerdown→移动>3px | 平移 tx,ty += Δ（即时跟随）；星体上起拖同样平移（不支持拖星改布局） | userNav |
| C5 | userNav | 点击 ⤢fit / 双击空白 | 动画回 fit（280ms） | autoFit |
| C6 | 任意 | L2 选中星体 | 相机滑翔（fly-to）：目标居中 + s′=max(s,1.6)（280ms，Galaxy View「glides instead of jumps」） | userNav（选中态相机锁定语义不变） |
| C7 | 任意 | 键盘 Tab 聚焦到视口外星体 | 相机动画把焦点星体带入居中 | 不变 |
| C8 | 任意 | 缩放触界 | clamp 到 [fitScale×0.4, 4.0]，越界无橡皮筋 | 不变 |

**背景层联动**：`applyCamera()` 单点同步 world(1.0×) / near(0.6×) / far(0.3×) 三个 transform；wheel/拖拽即时路径同样三写（A22 断言）。

### 5.2 星系新增状态 G1-G4

| # | 事件 | 动作 | 备注 |
|---|---|---|---|
| G1 | 视图首次挂载且数据就绪 | **星系绽放 reveal**：星幕底色先现（120ms）→ 星体按布局层距视口质心的距离错峰绽放（stagger 18ms/星，单星 350ms，总长 ≤750ms）→ 光轨最后生长（450ms，与末批星体交叠 100ms） | 一次性；WS 增量更新**不**触发；reduced-motion / T2 档直接呈现 |
| G2 | WS 节点新建 | 星体点火（§4.3） | 不打断相机；若处 autoFit 由 C1 重排 |
| G3 | hover 邻域聚焦 | 邻域提亮/满饱和，全域压暗至 0.18（§2.3 L1） | 纯 class 切换 + rAF 合帧 |
| G4 | 主题切换（系统偏好变更） | 背景双 canvas 重绘（≤1 帧）；星体/光轨颜色经 token 自动过渡 250ms；相机/选中态不丢 | 无 JS 参与色值，canvas 才需重绘 |

**明确否决**（克制裁定，防实现自由发挥）：idle 自动漂移/自动旋转（Galaxy View idle cruise）——工程读图不需要相机自走；开图每次都 reveal——只首挂载一次；星点闪烁 twinkle——亮度变化是状态语义通道，装饰性闪烁会污染它。

### 5.3 星体状态机 N1-N8（v1 沿用，载体换星系皮）

| # | 当前态 | 事件 | 动作 |
|---|---|---|---|
| N1 | L0 默认 | pointerenter | → L1：卡 `.fm-hi`（scale 1.15）；邻居卡+邻接边 `.fm-hi` 满饱和；其余 `.fm-dim`（卡 0.16/边 0.06）（G3） |
| N2 | L1 | pointerleave | 80ms 宽限期后还原 L0；期间进入新星体直接切换高亮目标 |
| N3 | L0/L1 | click（位移≤3px 且 <400ms） | → 选中：C6 fly-to + 详情面板滑入；星体 `.fm-selected`（sapphire 描边环） |
| N4 | 选中 | 点击空白 / Esc / ✕ | 面板滑出（260ms）；星体还原；**相机不动**；焦点归还星体 |
| N5 | 选中 | 点击另一星体 | 切换选中：面板内容换、相机重新居中 |
| N6 | 选中 | 详情面板「上游/下游」条目点击 | fly-to 该邻居 + 切换选中（图谱跳转闭环） |
| N7 | 任意 | WS 状态迁移 | §4.3 星系事件表；350ms 平滑过渡 |
| N8 | 任意 | 键盘 focus + Enter/Space | 等价 N3 |

### 5.4 输入设备差异（v1 沿用不改）

| 输入 | 行为 |
|---|---|
| 鼠标滚轮 | 缩放（CAD 式 C3）——滚轮只做缩放，无滚动语义 |
| 触控板双指滚动（无 ctrl） | 同滚轮=缩放 |
| 触控板捏合 pinch | ctrl+wheel 同 C3；Safari gesture 事件以最近光标位作锚点（canvas-zoom-follow-cursor-spec §3.1） |
| 触屏 | 单指拖=平移；tap=选中；双指捏合=两指中心缩放 |
| 键盘 | Tab 循环星体；Enter/Space 选中；Esc 关面板；+/− 缩放（视口中心锚）、0=fit |

### 5.5 选中详情面板（v2.2：result 内联直显；毛玻璃无遮罩不变）

```
┌ fm-detail（毛玻璃，无遮罩，320px，面板内滚动）─┐
│ 节点名                        [状态徽章] ✕     │
│ agent · wt badge                                │
│ 上游: ●卡A        下游: ●卡B                    │  ← 可点击跳转（N6，保留）
│ [blocked 反馈面板]（仅 blocked 态，保留）        │
│ 任务（pre，滚动区）                              │
│ 结果（.md-body：markdown 内联渲染，全文可读）    │  ← v2.2：无按钮、无二级弹窗
└──────────────────────────────────────────────────┘
```

- **result 内联直显**（作者裁定改动 2）：result 全文以 markdown 渲染进面板（标题/表格/代码块/列表/引用/行内代码粗体链接，先转义后渲染）；阅读 = 面板内滚动，不设 result 区 max-height 截断。
- **删除**：「完整结果」按钮与 `openNodeResultViewer` 二级弹窗（v2.1 §5.5 该条作废）；flowViewers.js 的 viewer 保留给其他入口，Galaxy L2 不再调用。
- 面板渲染条目名从「节点」改称可沿实现现状（i18n 键 `flowmap.detail.*` 沿 v1 清单，无新增键）。

---

## 6. 动效规范

| 交互 | 属性 | 时长 | 缓动 | reduced-motion |
|---|---|---|---|---|
| fit / fly-to 相机 | translate+scale（world+near+far 三层同步） | 280ms | cubic-bezier(0.33,0,0.2,1) | 0.01ms |
| 滚轮缩放/拖拽平移 | translate+scale | **0（直接操作）** | — | 同 |
| hover 卡增亮 | transform scale 1→1.15（卡） | 160ms | cubic-bezier(0.33,0,0.2,1) | 0.01ms |
| 邻域聚焦/压暗 | opacity + stroke | 180ms | ease | 0.01ms |
| G1 星系绽放 | 节点卡 opacity+scale .85→1（错峰 18ms/卡） | 单卡 350ms，总 ≤750ms | ease-out | 整段跳过（0ms 直接呈现） |
| 光轨生长 | stroke-dashoffset | 450ms | cubic-bezier(0.33,0,0.2,1)（沿 v1） | 跳过（直接全长） |
| 卡点火（WS 新建，产品 fm-enter 语义） | opacity 0→1 + scale .85→1 | 350ms | ease-out | 直接出现 |
| 卡熄灭（移除，产品 fm-exit 语义） | opacity→0 + scale .8 | 300ms | ease-in | 直接移除 |
| 状态过渡 | 环/点/名称 opacity 与描边色（产品 .fm-node 过渡语义） | 350ms | ease | 0.01ms |
| 布局移位（WS） | left/top | 400ms | cubic-bezier(0.645,0.045,0.355,1)（沿 v1） | 0.01ms |
| 详情面板 | translateX 100%→0 | 260ms | cubic-bezier(0.33,0,0.2,1) | 0.01ms |
| running 轨道旋转（产品 flowAnim 语义） | .solar-dot-wrap rotate（3/4.5/6s ∞，相位 0/120/240，方向 +−+） | rAF 连续 | linear | 静止基准角 |
| inflight 流光 | stroke-dashoffset 行军（flow-dash 沿用） | 1.2s ∞ | linear | 静态虚线 |
| 主题切换星体过渡 | fill/stroke 颜色 | 250ms | ease | 0.01ms |

**防抖关键参数（v1 结论逐条沿用）**：hover 切换 rAF 合帧；pointerleave 80ms 宽限；拖拽/点击阈值 3px/400ms；相机动画可被新输入随时打断并从当前值续跑（`animateGTranslate` 续跑模式）；G1 错峰用单一定时器批量计算，禁每星一 timer。

**动效-语义红线**（§4.3 重申）：呼吸/流光只属于语义状态（running/inflight）；G1/点火/熄灭是事件动效；**禁止**任何装饰性循环动画（闪烁、漂移、旋转的背景层）。

---

## 7. 边界与异常

| 场景 | 行为 |
|---|---|
| 空态/未挂载/全部归档/加载失败 | 沿用现有四态文案，居中渲染于固定视口（无滚动）；HUD/图例/**背景双 canvas 不绘制**（星幕基底保留——空态也是深空，文案像悬浮在夜空上） |
| G1 后 WS 立刻连发 | reveal 只做一次且可被 WS diff 插队更新（新星体按 §4.3 点火入场，不等 reveal 队列）；reveal 计时器与 diff rAF 泵互不阻塞 |
| 30+ 节点 | fit scale 变小；hover 是 CSS transform（与卡数无关）；邻接 map O(V+E) 一次预计算 |
| 61–150 节点（T1 档） | 渐进降显：scale<0.7 卡整体 opacity→0.5（点阵可辨），≥0.7 恢复；背景降档（§9） |
| >150 节点（T2 档） | 超出 DOM/SVG 预算：默认降级路径=关卡玻璃 blur+星场减半+簇雾关；canvas/WebGL 重写为 P2 评估项（Nebflow 现实规模几十节点，不阻塞） |
| hover 闪烁/抖动 | §6 防抖参数（80ms 宽限 + rAF 合帧 + 目标变更才重算类名） |
| 缩放极值 | C8：[fitScale×0.4, 4.0]；fitScale 记于容器 `data-fit-scale` |
| 超长名称/result | 卡内名称单行省略 + title（产品行为）；result 内联 markdown 全文渲染、面板内滚动（v2.2，无 max-height 截断） |
| WS 快速连发 | 沿用现有 diff 管线 + rAF 泵；userNav 态不触发 autoFit（C2 挂起） |
| 双主题切换瞬间 | 星体/光轨 token 自动过渡（G4）；双 canvas 重绘 ≤1 帧；相机/选中态不丢 |
| canvas 不可用（极端环境/测试 harness） | 背景层降级为纯 CSS 渐变（星幕基底仍在），图谱功能零损失（canvas 只是氛围，不是功能依赖） |
| 触控板误滚动 | 图内无滚动语义，wheel 一律缩放 + C8 钳制 |

---

## 8. 无障碍

- 节点卡 `tabindex=0` `role=button` `aria-label="节点名，状态，agent"`（轨道环等纯装饰不进可访问名）；命中区 = 卡本体。
- 焦点环 `outline: 2px solid rgb(var(--sapphire)/.6); outline-offset: 2px`——深空底上克制但可见；亮主题同 token 自动适配。
- Enter/Space 选中（N8）；选中后焦点移入详情面板关闭钮，Esc/关闭后焦点归还星体（APG dialog 模式，v1 沿用）。
- Tab 聚焦到视口外节点卡 → 相机带入（C7）；键盘可达全部节点卡。
- 详情面板 `role=dialog` `aria-label="节点详情"`；result 区可滚动可全选。
- 对比度：星名 `--color-text`（≥12:1）；图例/图注 `--color-text-dim`（过 4.5:1 档）；muted 仅装饰性 meta。
- **信息双通道**（语义保真红线）：六状态 = 产品卡双通道（§4.1 末列：线型/透明度/图标/动效/环色 ≥2 通道）；方向 = 渐变 + 箭头 + 流光 ≥2 通道；deps = 线型 + 箭头形状。色盲单通道可判读。
- `prefers-reduced-motion: reduce` → §6 末列全量降级：G1/点火/流光/呼吸/生长全部静止，状态即时以最终视觉呈现。

---

## 9. 性能预算：分层渲染策略与质量档位

### 9.1 总预算（60fps = 16.7ms/帧）

| 预算项 | 上限 | 手段 |
|---|---|---|
| 常态帧（相机移动/hover） | 合成only，主线程 <4ms | 一切动效只碰 transform/opacity/stroke-dashoffset；卡 hover 用 CSS transform；三层背景 transform 同步 |
| 节点卡质感 | 卡玻璃 = 产品既有 backdrop-filter（产品现状即如此，不计新增预算）；禁再叠 drop-shadow/glow 动画 | v2.1 光晕层已随星体作废；卡上动效只剩 transform/opacity |
| 边光轨 | 每 2 描边（halo+trail），禁 per-edge SVG filter | §3.2 双层描边法；渐变 defs 按「状态色×方向象限」合并复用 ≤12 个 gradient 定义 |
| inflight 流光 | 仅 inflight 边动（运行中通常 <10 条） | dashoffset 动画限 `pathLength=1` 归一 + CSS animation；delivered/idle 边零动画 |
| 背景层 | 常态零绘制成本 | 双 canvas 一次性烘焙；重绘仅 theme/resize/layout 变更（G4/C1 后一次）；星点 ≤200 |
| 布局/邻接 | O(V+E) 单次 | layoutNodes 既有算法；邻接 map 布局时预计算 |
| WS 连发 | 合帧 ≤1 次/帧 | 既有 diff 管线 + rAF 泵原样 |

### 9.2 质量档位（R6 落地；档位记于容器 `data-fm-quality`，A20 断言）

| 档位 | 触发 | 差异 |
|---|---|---|
| **T0 全效**（默认） | ≤60 节点 | §3 全部视觉：卡玻璃 blur+星场 200+簇雾+渐晕+全部动效 |
| **T1 降档** | 61–150 节点，或窗口宽 <768px | ① 卡 backdrop-filter 关（纯色玻璃底）；② 星场减半 ≤100；③ 簇雾关；④ 渐进降显（scale<0.7 卡 opacity .5）；⑤ 边 halo stroke 仅邻接边保留 |
| **T2 极简** | >150 节点，或 `prefers-reduced-motion`+大图并存 | T1 基础上：背景只留星幕基底+静态星场 60 点；G1 跳过；canvas 重写评估为 P2（§7） |

档位判定在布局后一次计算；跨档位阈值抖动用滞回（升档需连续两次判定，降档立即）。

### 9.3 渲染技术路线（含升级路径）

- **主路线 DOM+SVG**（Nebflow 现实规模几十节点，v1 §6 判断继续成立）：星体=div（transform 合成层），边=SVG path。 Galaxy View 的 WebGL/Web Worker 是 3000+ 节点的解法，几十节点下引入 Three.js 反而增加包体与分层成本——不采纳，仅作 R7 愿景参照。
- **升级触发器**（写入实现，防静默性能劣化）：布局后节点数 >150 或 fit scale <0.25 时打 `data-fm-quality="t2"` 并上报计数（console.debug 一次性），供 P2 决策。
- **内存**：星点种子/渐变 defs 均确定性复用；无逐帧对象分配（动画全 CSS/rAF 复用闭包）。

---

## 10. 可断言验收点（供 qa-frontend 转 Playwright）

**沿用 v1 A1-A14 全部口径**（选择器随载体平移：v1 `.fm-node` → v2.2 `.star`（世界定位壳）+ 内层产品卡 `.solar-node`，断言语义不变）：A1 零滚动条（overflow:hidden 渲染 + 页面根不可滚口径）· A2 初始 fit 全体落视口内（容差 2px）· A3 CAD 锚点（wheel 前后光标下节点不变/世界反算位移 ≤2px）· A4 hover 两级（自身 scale≥1.1、邻居 `.fm-hi`、非邻居 `.fm-dim` opacity≤0.2）· A5 点击面板开+居中 ≤60px+**无 overlay 元素**（铁律回归）· A6 面板数据=载荷 · A7 deps 虚线 `3 4` + 输出实线 · A8 Esc 关闭+焦点归还 · A9 极值 clamp（4.0 / fit×0.4）· A10 双击 refit · A11 reduced-motion ≤0.01s · A12 双主题三态截图 · A13 WS 边档位迁移零整页重建 · A14 键盘带入+Enter 选中。

**v2 新增（星系视觉专属）**：

| # | 断言（二值） | 截图 |
|---|---|---|
| A15 | 背景层存在且分层：fm-viewport 内含 `data-fm-bg-layer="near"` 与 `"far"` 各一 canvas；`fm-vignette` computed `pointer-events:none`；两 canvas 尺寸 = viewport 尺寸（容差 1px） | 否 |
| A16 | G1 仅一次：首次挂载后 world 容器 `data-fm-revealed="1"`；注入 WS 新节点后该属性不重置（无二次 reveal）；reduced-motion 下挂载即 `revealed=1` 且无 reveal 动画 | 否 |
| A17 | L1 满饱和聚焦：hover 节点卡 X 后，X 邻接边 trail stroke 的 computed `stroke` 不含低透明 alpha（与 idle 态 `--color-border` 色不同）且 halo opacity > idle 值；非邻接边 computed opacity ≤0.06 | 是 |
| A18 | 六状态产品卡映射（v2.2）：六状态卡 class 命中 §4.1 表（wiring 卡含 .pending 且 data-status=wiring）；pending 环 computed border-style=dashed + 名称 opacity≈0.4；completed 名称 opacity≈0.6；failed 环 border-color=#f44336 且含 ✗；blocked 含 ⚑ + 琥珀卡描边；running 轨道点 transform 在变化（快照缺态用 class 翻转验证，原型 V3 口径） | 是 |
| A19 | 方向三通道：任取输出边 path，其关联 linearGradient 端点坐标与路径起止一致（源端 alpha < 目标端 alpha，解析 stop-opacity）；目标端存在 marker/箭头圆点元素；deps 边箭头为空心（fill=none） | 否 |
| A20 | 质量档位：mock 注入 80 节点后容器 `data-fm-quality="t1"`；scale<0.7 时任一卡 computed opacity=0.5（v2.2 渐进降显口径），放大 ≥0.7 后恢复 1 | 是 |
| A21 | inflight 流光运行：inflight 边 path 的 `stroke-dashoffset` 动画在运行（getAnimations 非空或连续两帧 dashoffset 值不同）；reduced-motion 下为静态虚线且无动画 | 否 |
| A22 | 视差联动：拖拽平移 world 位移 Δ 后，far canvas transform 位移 ≈0.3Δ、near ≈0.6Δ（容差 ±2px） | 否 |
| A23 | **作废（v2.2）**：星等尺寸编码随星体形态删除；连接度仅由布局向心力（§2.2 ⑤）承载，不做断言 | — |
| A24 | 主题重绘：暗→亮切换后两 bg canvas 像素变化（toDataURL 前后不等）且 <100ms 完成；卡色/星系层色经 token 过渡无 JS 写死色 | 是 |
| A25 | 空态深空：空数据态星幕基底仍在（viewport 背景 computed 非 transparent），双 canvas 无星点绘制（width 或绘制内容为空），文案居中无滚动 | 是 |
| A26 | **径向轨道布局（v2.1，v2.2 弦距改口径）**：任取卡，其世界半径 = `r(d)×(1−0.12·min(deg,5)/5) ± 5px`（d=布局接口暴露的轨道深度，JS 可读）；度数 0 卡全部落 `[r_max+84, r_max+194]` 环带且透明度 ≤0.85；同环任意两卡弦距 ≥126px（132px 口径容差）；世界包围盒纵横比 ∈ [0.5, 2]（均衡分布口径，防回退横条布局） | 是 |
| A27 | **节点=产品卡结构（v2.2）**：每个 `.star` 内恰一个 `.solar-node.fm-node.{cls}`（cls=NODE_STATUS_CLS 映射），含 `.solar-orbit`（3 ring + 3 dot）+ `.fm-node-head` + `.solar-node-label` + `.solar-node-sub`；文档零星体残留元素（`.star` 下 .core/.glow/.dring/.hit 计数=0）；卡玻璃/文字 computed 色 = 产品 token 值（暗 `rgba(24,28,38,.68)`/`#e8eaed`，亮 `rgba(255,255,255,.55)`/`#1b1e26`） | 是 |
| A28 | **result 内联直显（v2.2）**：点击有 result 的节点 → `#dResult` 为 `.md-body` div 且渲染出 markdown 元素（h3/h4/table/code 任一 + 文本非空）；`#detail` 内无「完整结果/查看」类按钮；全文档无 `.flow-viewer-overlay`（无二级弹窗）；上游/下游 `.nbr` chip 存在且点击后选中切换（N6 闭环） | 是 |

**截图清单**（A12 扩展）：暗/亮 ×（L0 全景/L1 hover 聚焦/L2 选中+面板）三态 = 6 张基线 + A20 T1 档 80 节点全景（A23 星等特写随星等作废删除，v2.2）。

---

## 11. 与 v1 规格书的沿用/废弃对照表

| 类别 | 条目 | v1 出处 | v2 处置 |
|---|---|---|---|
| **沿用·交互骨架** | 相机状态机 C1-C8（CAD 锚点公式、userNav/autoFit、clamp、fly-to） | §3.1 | **逐条沿用**（§5.1），仅 C6 措辞改 fly-to |
| 沿用 | 星体/节点状态机 N1-N8（80ms 宽限、3px/400ms 阈值、Esc/焦点归还、N6 跳转闭环） | §3.2 | 沿用（§5.3），高亮参数 1.12/1.06 → 1.15/邻域满饱和（星系皮适配） |
| 沿用 | 输入设备差异表（滚轮只缩放/pinch/触屏/键盘） | §3.3 | 逐条沿用（§5.4） |
| 沿用 | 三级渐进触发链（L0 默认→L1 hover→L2 click）与详情面板内容超集 | §2.3/§3.4 | 沿用（§2.3/§5.5），L0/L1 载体换星系皮 |
| 沿用 | DAG 层次布局案 A + deps 纳入层次（力导向否决） | §2.2/§2.4 | deps 纳入深度语义沿用；**层次盘面本身已被作者批评推翻（v2.1 §2.2 径向轨道盘面替代）** |
| 沿用 | 边语义定值（输出三态色/deps `3 4`/箭头尺寸/图例） | §5.3 | 沿用，载体换光轨双层描边 |
| 沿用 | 防抖参数全表（rAF 合帧/80ms/3px/400ms/续跑） | §4 | 逐条沿用（§6） |
| 沿用 | 无障碍骨架（role/焦点环/Esc/双通道/reduced-motion） | §7 | 沿用并扩展双通道定义（§8） |
| 沿用 | 断言口径 A1-A14 + A1 增量口径教训 | §8/§9 | 全部沿用（§10，选择器平移） |
| **视觉废弃** | 紧凑卡节点（120×44 毛玻璃卡、卡面状态点布局） | §5.2 | **废弃** → 星体质感节点（§3.1）；卡面信息已全数迁移 L2 面板，无信息损失 |
| 视觉废弃 | 紧凑卡布局常量（NODE_W/H 120/44 等） | §2.2 | 废弃 → 星体几何常量（NODE_R/格距，§2.2） |
| 视觉废弃 | 边单层描边 + 箭头即方向唯一通道 | §5.3 | 升级 → 光轨双层描边 + 方向三通道（§3.2） |
| **新增** | 深空背景图层栈（星幕/星云/星场/簇雾/渐晕，视差 0.6/0.3） | — | §3.3（Galaxy View R1 2D 化） |
| 新增 | 星等编码（尺寸=连接度三档） | — | §3.1（Obsidian 语义） |
| 新增 | G1 星系绽放 reveal / G2 点火 / G4 主题重绘；G 系否决清单（idle 漂移/twinkle） | — | §5.2 |
| 新增 | 质量档位 T0/T1/T2 + 升级触发器 | — | §9（Galaxy View R6） |
| 新增 | 断言 A15-A25（背景层/reveal/满饱和/星体映射/方向三通道/档位/视差/星等/主题重绘/空态深空） | — | §10 |
| 沿用其文 | v1 §9 原型与 §10 实施清单的 flowViewers.js 零改动、flowCss.js 不动、WS diff 管线保留原则 | §10 | §12 继承更新 |
| **视觉废弃（v2.2）** | 星体质感节点（glow/core/label 三层、径向渐变光点、星等三档尺寸编码、六状态星体形态表） | §3.1/§4.1（v2.1） | **作废** → 产品现有 .solar-node 卡逐字沿用（§3.1/§4.1 v2.2 重写）；连接度尺寸编码删除，向心力保留 |
| **交互废弃（v2.2）** | L2「完整结果」按钮 + `openNodeResultViewer` 二级弹窗 | §2.3/§5.5（v2.1） | **作废** → result 内联 markdown 直显（面板内滚动） |
| 修订（v2.2） | 布局尺寸常量：弦距 60px→132px、星野带 0.20→0.26 rad、fit pad 84/72（卡宽 124px 适配） | §2.2 | 布局五步算法骨架不变，仅常量随节点卡更新 |

---

## 12. 实施影响面（改造清单，供派发）

| 文件 | 改动 | 关键点 |
|---|---|---|
| `flowMapTab.js` | **主改造（v2.2 缩小）** | v1 清单 ①-⑧ 全部继承（渲染宿主/相机模块/邻接 map/选中面板/`focusNode`/WS 管线保留），布局层以径向轨道算法（§2.2 五步，弦距 132px）替换 `layoutNodes`；**节点渲染 = 现有 `nodeHtml()` 原样复用（v2.2 裁定后零新卡样式）**，仅宿主从 solar-canvas 换 galaxy world 层 + 定位改卡中心；新增：① 详情面板模块（毛玻璃、N3-N6、**result 内联 markdown 渲染器**，不再调用 `openNodeResultViewer`）；② 光轨渲染（贝塞尔 c=0.3 径向侧弓 + 双层描边 + gradient defs 复用 + marker + 低缩放增强）；③ 背景双 canvas 模块（烘焙/重绘触发/视差同步进 `applyCamera()`）；④ G1 reveal 控制器；⑤ 质量档位判定 + `data-fm-quality`；⑥ 主题切换 canvas 重绘钩子（G4）；轨道动画沿用 flowAnim.js（Galaxy 卡同结构，rAF 键控兼容） |
| `flowMap.css` | **主改造（v2.2 缩小）** | 节点卡样式**零新增**（产品 `.solar-node/.fm-node` 全复用；仅 galaxy 宿主定位类新增：定位/选中描边/带透明度）；光轨态/背景层/渐晕/档位差异（T1/T2）/双主题星系层/焦点环/reduced-motion 块；v1 的 `.fm-*` 动画类语义平移 |
| `flowCss.js` | 不动 | 沿 v1 裁定 |
| `flowViewers.js` | 不动 | Galaxy L2 **不再调用** `openNodeResultViewer`（v2.2 改面板内联 markdown）；viewer 本体保留给其他入口，文件零改动 |
| `nodeData.js` / `ws.js` | 不动 | 数据契约与 WS 订阅不变 |
| `locales/en.js` + `zh-CN.js` | 沿 v1 键清单 | `flowmap.detail.*`/`flowmap.hint.zoom`/`flowmap.legend.*`——v2 无新增键（星系皮不引入新文案；图例文案可按需加「光轨」措辞，实现在既有键值内调整） |
| `scripts/` | 新增 | `verify-flowmap-galaxy.cjs`：A1-A24、A26-A28 断言（A23 随星等作废跳过）+ 6 基线截图 + T1 特写（harness 模式照抄既有先例，不碰 8080） |

**风险与回滚**：单点改 flowMapTab.js + flowMap.css，回滚 = revert 单 commit；v2.2 节点卡零新样式 → 视觉回归风险集中在布局层（原型已按真实快照验证）。性能基线：T0 档 12–60 节点 <16ms/帧（§9.1 预算）；canvas 层独立可降（§7 兜底）。可交互原型已就位：`assets/20260903_flowmap-galaxy/prototype.html`（单文件零外请求 + `?theme/hover/select/noanim` 自动化参数）。

---

## 13. 参考链接

- Obsidian Galaxy View 插件页：https://community.obsidian.md/plugins/galaxy-view
- Galaxy View README 全文镜像（obsidianstats.com，repo `Longwind1984/galaxy-view`，v0.6.1）：https://www.obsidianstats.com/plugins/galaxy-view
- Obsidian Graph View（v1 交互壳参照）：https://help.obsidian.md/plugins/graph
- d3-zoom（缩放不变量与插值）：https://d3js.org/d3-zoom
- WAI-ARIA APG Dialog (Modal)：https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/
- Material Motion（缓动参照）：https://m3.material.io/styles/motion
- 本仓：`~/.nebflow/docs/Nebflow/20260903_flowmap-graphview-design.md`（v1 封存稿 @cf1ce3e——相机数学/防抖结论/断言口径来源）
- 本仓：`~/.nebflow/docs/Nebflow/canvas-zoom-follow-cursor-spec.md`（CAD 缩放方案 A）
- 本仓：`~/.nebflow/docs/Nebflow/20260902_flowmap-engine-evolution-design.md` §1 + `20260902_deps-dependency-connection-design.md` §D（deps 边与图例）
- 本仓：`~/.nebflow/skills/nebflow/visual-style/SKILL.md`（毛玻璃/禁遮罩/玻璃控件/字重铁律）
- 本仓：`~/.nebflow/skills/card-design/SKILL.md`（视觉默认值/暗色适配规范）
- 既有实现：`src/main/resources/web/js/flowMapTab.js`、`flowMap.css`、`flowViewers.js`；token：`src/main/resources/web/css/sapphire.css`

## 版本日志

- v1.0（2026-09-03）：初稿——Galaxy View 提炼 R1-R7、星系视觉换皮（星体三层/光轨双层/背景五组件/双主题）、DAG 语义映射表（六状态星体/方向三通道/时间语义）、C1-C8+N1-N8 沿用与 G1-G4 新增状态机、动效表、T0-T2 质量档位、A1-A25 断言、v1 沿用/废弃对照表、实施清单。前版 v1（DAG 紧凑卡方向）已封存 @cf1ce3e。
- v2.1（2026-09-03）：**§2.2 布局重做**——作者批评「DAG 层次布局分层导致第一层堆积、横向极长、纵向浪费」（原文见文首）推翻层次盘面，改**径向轨道盘面**（轨道深度=中心向外辐射、旋臂扇区、环上防重叠、孤立星野带、向心力密度梯度五步确定性算法），方向语义改「由内向外」；§2.4 列带→扇区带；§3.2 曲率弓向随布局改径向 + 新增低缩放光轨增强（原型实测反馈）；§4.2 层间流向改内→外；§10 新增 A26 径向布局断言；§11 对照表相应行更新；§12 布局层改造说明更新。**可交互原型**（真实快照 32 节点 + 全交互，Playwright 自测通过）落盘 `assets/20260903_flowmap-galaxy/prototype.html`。其余章节（§3/§5-§9/§11 交互骨架部分）与 v2 一致未动。
- v2.2（2026-09-03）：**两处作者裁定修订**（v2.1 径向布局拍板冻结后）——①**节点样式回归产品现有样式**（「节点样式要利用我们现有的样式，我们只是优化交互逻辑」）：§3.1 重写为产品 `.solar-node` 卡逐字沿用（orbit/head/label/sub/barrier/wait/result-summary，六态=NODE_STATUS_CLS），§4.1 星体形态表重写为产品状态样式表，星等编码/星体三层作废，§2.2 尺寸常量随卡宽更新（弦距 132px / 星野带 0.26 rad / fit pad 84·72），§3.4/§3.5 改「卡层=产品 token 逐字、星系层分离」；②**详情面板结果内联直显**（「节点结果直接在详情页展示，不需要说再有一个按钮来弹窗显示完整结果」）：§2.3 L2 / §5.5 result 内联 markdown 渲染（面板内滚动），「完整结果」按钮与 `openNodeResultViewer` 二级弹窗作废；§6 动效表载体改卡（running 呼吸→轨道旋转沿用 flowAnim 语义）；§8/§9 相应措辞与档位更新；§10 A18/A26 改口径、A23 作废、**新增 A27（产品卡结构）/A28（result 内联直显）**；§11 新增 v2.2 废弃/修订行；§12 实施清单缩水（节点渲染=现有 nodeHtml 复用、卡样式零新增）。原型就地升级 v2.2（Playwright 回归 15/15 全绿：CAD 锚点漂移 0.14px、hover L1、click fly-to、chip 跳转、双主题 token、reduced-motion、markdown 内联、无二级弹窗）。
