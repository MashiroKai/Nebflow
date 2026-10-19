# Canvas 面板 · 标签行改叠卡 · 设计 demo 报告 v4（整区缩放模型）

- 上位文档：`reports/20261019_230310_canvas-card-stack-design-v3__delegate-nebflow-19bc02b5.md`（v3 报告，含 v1/v2 链接；开放问题清单仍有效，本篇只写增量）。
- **v4 样张：`reports/20261020_024534_canvas-card-stack-mockup-v4__delegate-nebflow-19bc02b5.html`**（自包含，双击浏览器直接打开；暗亮切按钮页右上；面板宽度 720/500/340 三档可切；源码内修订点带 `[v4①]`…`[v4⑥]` 标签）。
- 本批仍为**纯 demo，零源码改动**；实施批落点备忘见 v1 报告 §三，v4 有更新（见下 §二末条）。
- 交付注：本批次两文件直写主仓 `reports/`（delegate 会话 worktree 已回收，无分支可挂），当前为未跟踪态，归档提交由调度方处理（同 `0f2f2ee0a` / `f69388968` / `a08189256` 先例）。

## 一、v3 → v4 修订对照（作者二次澄清）

作者澄清：「把整个 canvas 当作卡片，点堆叠按钮的时候，整个 canvas 放缩为堆叠视角，等于是在切 canvas，而不是现在在 canvas 上进行堆叠。」——v3 的卡组舞台仍挂在 canvas 内容内部（`#deck-stage` 是 `#canvas-content` 子层），观感是「canvas 里长了卡组」；v4 把缩放主体升级为 canvas 根、卡组移出 canvas 子树。

| # | 澄清 | v3（旧模型） | v4（新模型） |
| --- | --- | --- | --- |
| ① | 缩放的是整个 canvas 区 | 面板内容内的 face 物化为前卡（scale 0.89 / 下移 8px）——缩放发生在 canvas 内部 | **整区缩放**：transform scale 只作用于 canvas 根（实施 = `#canvas-panel`），整个 canvas 连同其内容一起缩成 ~85% 的最前卡——等于切 canvas；全程只有这一次整区缩放变换 |
| ② | 卡组在 canvas 外 | `#deck-stage` 挂 `#canvas-content` 内（z-10），卡「长在面板面上缘背后」 | 卡组层 = canvas 区内 canvas 根的**兄弟层**（`#deck-layer` z-5 < 面板 z-10）；**canvas 子树内不存在任何卡片容器/浮层**（DOM 可断言 `panel.contains(deckLayer) === false`）；缩小态下 canvas 区外露出的边缘/背景就是卡组后卡 |
| ③ | 后卡同尺寸堆叠 | translateZ 负深度 + rotateX 后仰 + 逐级缩放（0.886 起 −0.016/级） | 后卡与缩放后 canvas **同尺寸**（85%）：**无 translateZ、无逐级缩放通道**；食谱盒纵深 = translateY 露边阶梯（d=1 露名称条上缘 ~22px、每级净露 ~6px）+ rotateX 微后仰（−1.6°/级）+ 逐级减淡压暗；卡顶名称条照旧（v2③） |
| ④ | 点后卡 = 放大回满幅 | 后卡飞入面板基姿（y 8 / scale 0.89）落位换面 | 点后卡 = 该卡**从露边位放大回满幅**（×1/0.85 恰铺 canvas 区）即成为当前 canvas（内容随之切换）；**原当前卡以 ghost 缩回堆叠**深处；全程 ~0.65s |
| ⑤ | 收回 = 解除缩放 | 面板面解除物化、卡组收回背后 | ESC / 点空白 / 点 canvas / 再点浮钮 = 卡组收回背后 + canvas 根 scale→1 回满幅常态（同一次整区变换的逆，内容不变） |
| ⑥ | 关闭照旧重锚 | 划走 / 卡顶 × 作用于 face | 划走 / × 重锚缩放态 canvas 根（甩出/点关 = 关当前页签，邻位接管同 `closeTab`）；× 落头行右端（浮钮左侧，仅 deck 态） |

## 二、v4 模型细节裁定（实施批注意）

- **结构**：canvas 区（面板的 docked 容器）内两层兄弟——`#deck-layer`（z-5，背后卡组，透视容器）+ `#canvas-panel`（z-10，canvas 根）。canvas 子树内只有头行 + 内容 + 悬浮钮。区 `overflow:hidden` = 卡组舞台边界，深处后卡顶到区缘被裁是预期形态（「卡组收在 canvas 区里」）。
- **基姿常量单一来源**：JS 注入 `--stack-scale: 0.85 / --stack-shift: 8px`；CSS `.stacked`、飞行落点（×1/0.85）、ghost 起点（canvas 基姿）全部同源——v3 报告「三处同步改」的教训在 v4 由 CSS 变量承载，实施批改一处即全局一致。
- **同尺寸铁律与深度通道的取舍**：v3 式 translateZ 负深度在 v4 被删除——透视投影随深度逐级缩水（−14px/级即 ~1.2%/级），与「后卡同尺寸」判据直接冲突，且正是 v3「级联塌陷坑」（y 步进须压过半高收缩）的根源；v4 级联无收缩项可对抗，该坑在模型层面不存在。深度感由露边错位 + 微后仰（投影偏差 <0.6%，肉眼同尺寸）+ 减淡压暗表达。
- **几何常量**：peek(d) = 22·a₁ + 6·a₂（×sizeK，sizeK = min(1, 卡高/300)；v3 的 1.2 上限在 v4 去掉——露边带是区高 7.5% 的固定像素带，步进不应随卡高放大）；op 自 d>1.2 线性衰减；压暗 0.11·d 封顶 0.30；460 高区下露边带 ~42px 恰容 4 级阶梯，第 5 级起被区缘裁切。
- **换入编排**（v4④）：选中卡 `fly` 类（圆角 17px，×1.1765 投影后 ≈ 面板 20px 圆角）位姿 lerp → 满幅落点（y→0、scale→1/0.85），期间 `#deck-layer.over` 瞬时抬 z-60；落位即换内容 + 标题交叉淡入 + canvas 根 `no-trans` 瞬时回满幅（被落位卡严丝合缝盖住，无跳变）+ ghost 旧卡从满幅位缩向堆叠深处（0.3s）。
- **环形语义（实施批注意，修正 v3 报告的过度声明）**：擦洗是环形取模——**d=1 最上露边位的卡 = `ring[mod(round(pos)+1, M)]`**，随擦洗易主，不是固定的 ring[0]；原当前卡缩回堆叠后按页签环形序落在 canvas 正背后一位（ring 末位 = d=0），擦洗一格即到最上露边位。v3 报告 §二「旧 tab 固定置于最上露边位」与 v3 代码实际行为不符，实施批以本条为准。
- **互斥与守卫**（v2 §二 / v3 §二全量继承）：`finalizing` + `selFly/ghostFx/faceFx/drag` 任一活跃时滚轮/键盘/浮钮/二次关闭全忽略；`prefers-reduced-motion` 下插值系数置 1、FX 0.05s、过渡 0.01s。`overscroll-behavior: contain` 防滚链。
- **实施批落点更新（canvas.js）**：整区缩放类（`.stacked`）加在 `#canvas-panel`；`deck-layer` 挂 canvas 区容器（面板 docked 父级）内、面板之前；常态头 = `.canvas-title`（兼作前卡卡顶名称条）；浮钮挂面板层随整区缩放；数据源 = `tabs` Map（卡序 = active 起的环形序，不含前卡）；dirty 复用 `isTabDirty()`；右键「引用当前页」复用 `refForTab()`；关闭接管同 `closeTab()`；「OS 文件拖入开新签」落区 = 整个 canvas 区（含卡组态）。

## 三、保留不变（v1 → v2 → v3 → v4）

环形取模首尾循环、连续滚轮擦洗（增益 1/240 / 指数趋近 / 可回滚）、键盘 ↑↓/Enter/Esc、右键菜单（引用/关闭/关闭其他）、脏标记绿点语义、缩略 CSS mock 族、卡顶名称横条结构（v2③）、常态头只留居中标题 + 40px 悬浮浮钮 + 常态无叉（v2①）、划走甩出 + × 点关 + 邻位接管（v2⑥）、全关收面板 + 重开浮钮、亮暗双主题 token 镜像。

## 四、自验记录（Playwright，本地 chromium，38 断言全 PASS）

| 组 | 断言 | 结果 |
| --- | --- | --- |
| A1 常态 | 卡组层空；canvas 根无 stacked / transform none；**deck-layer 非 canvas 根子节点（v4 判据）**；浮钮 rest 0.6；常态无 × | PASS |
| A2 整区缩放+长出 | 根 matrix(0.85,…,8)；环流 8 张；后卡 = 区 85%（容透视微缩 ≤1.5%）；d1 vs d3 宽差 <2%（无逐级缩放通道）；deck-layer z5 < 根 z10；d=1 露边 22px；阶梯 5.9/5.9px；露边带=名称条且在区缘内；静止位姿无 scale 通道；计数「当前 1/9 · 下一张 README.md」；foot 浮现 | PASS |
| A3 擦洗 | wheel 300 → target 0.25 连续格；pos 精确收敛；反向 −150 → −0.375 可回滚 | PASS |
| A4 换入 | 飞行中 deck-layer.over 抬层；activeId/标题切为该卡；根回满幅 scale 1；deck 收回清空；重开后原当前卡在 ring 末位（正背后一位） | PASS |
| A5 划走 | 拖 290px 甩出：页签 9→8、邻位接管（CanvasRoutes→package.json）、deck 保持、根复位回缩放基姿 | PASS |
| A6 卡顶 × | × 点关：页签 8→7、邻位接管（package.json→usage-10月.xlsx） | PASS |
| A7 收回 | ESC → deck 关、根回常态 transform none | PASS |
| A8 窄档 | 340：后卡 290×389（=85%）、露边 22px 仍在 | PASS |
| A9 主题 | 暗档 deck 截图正常（token 镜像） | PASS |
| A10 降级 | `prefers-reduced-motion` 根过渡 0.01s | PASS |
| 全局 | console 零错误 | PASS |

截图 8 张（`/tmp/canvas-v4-shots/`：亮/暗 × 常态/卡组/擦洗/换入飞行/换入后/甩出后/窄档）逐张目检通过。

**过程中被验收拦下的两处模型/实现问题（已修，均带教训）**：

1. **translateZ 深度通道违背同尺寸判据**：初版沿用 v3 的 −14px/级 translateZ 作深度暗示，投影 AABB 随深度缩水（d=1 即 −0.6%、高向 −1.8%，d3 对 d1 漂移 1.7%）——「后卡同尺寸」在投影层面不成立。删除 translateZ（仅留 −1.6° 微后仰，偏差 <0.6%）后漂移归零。教训：**「同尺寸」类视觉判据必须断言在投影盒（getBoundingClientRect）上，不能只断言 transform 字符串无 scale()**——透视/旋转通道同样改变投影尺寸。
2. **环形取模下的点击落点**：自动化先擦洗再点「ring[0]」，但擦洗后 d=1 位已易主（= `ring[mod(round(pos)+1, M)]`），ring[0] 转到 canvas 正背后被遮挡，点击落在面板上触发收回（deck 关了、卡没换）。教训：**环形卡组的自动化交互必须按位姿（d 值）寻址，不能按建环序（r 值）寻址**；v3 报告「旧 tab 固定置于最上露边位」的过度声明同源（把建环序当成了位姿），已在 §二修正。

**环境注**（继承 v2/v3）：无头 chromium rAF 实测 ~36fps，指数趋近收敛时间按比例拉长，自动化等待 ≥2.6s；真实浏览器 60fps 无此现象。

## 五、开放问题（继承 v1 §四，无新增裁决）

拖拽排序安放、preview/pinned 表达、面板/文件分堆、滚轮自然滚动方向——仍待作者拍板；本轮澄清未涉及，默认按 v1 文案执行。
