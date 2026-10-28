# Canvas 控件三件 UI/UX 修 · 交付报告（作者令 2026-10-28 16:0x）

- 批依据：作者令 2026-10-28 16:0x，四张截图在手；报障来自 link.mystand.space 门户面（与本地同前端，纯前端批 css/js 生效面一致）。
- 分支：`delegate/delegate-nebflow-7e5196ed`（工作树 `.nebflow/worktrees/delegate-nebflow-7e5196ed`）。**在产引擎（pid 70481 / gateway 6138）全程零触碰**，未向 :6138 发任何请求。
- 性质：纯前端批（2 css + 2 js），随下一波包生效；数据面/后端/手机层（mobileLayer.css、canvasGestures、navController）零改动。

## 〇、三件各自改了什么

### 件一 · 独立展开窗口的胶囊行对齐 canvas 同款 + 水平居中

- 修前：卡窗（`body.card-window`）胶囊行 kept 基态 `left:14` 靠左（截图 1）；居中批（2026-10-23）的桌面规则以 `:not(.card-window)` 排除了卡窗。
- 修后（split.css §卡窗）：卡窗胶囊行并入面板同款 —— `left:50%` + `translateX(-50%)` 并入 translate3d 首参（与桌面居中批同式，手势变量语义不变）；胶囊几何同面板档（180×36 固定、999 圆角、聊天模板玻璃）。
- 「合并」钮（`.canvas-merge-btn`）改与面板 `.canvas-pop-btn` 完全同语言的 ghost 小钮（26px/8px 圆角/静默透明 + hover 抬亮），修前 `--glass-control` 自绘 chip 面退役 —— 胶囊行视觉语言与面板完全同款。

### 件二 · 顶部行固定、下方专用、永不重叠（布局排他）

- header 行定义（作者令逐字）：**胶囊 + 堆叠钮（含「独立中」角标）这一行 = 固定 header 行**。行几何 token 化（sapphire.css）：`--cv-head-top 10px` + `--cv-head-h 36px` → 行下缘 `--cv-head-band 46px`；其余一切控件从 `--cv-head-clear`（+10 呼吸缝 = 56px）起算。
- **「独立中」角标迁移**：渲染点从 pane `::after` 迁 **`#canvas-slide[data-independent]::after`**（split.css §独立中 + canvas.js `syncIndependentChip()`）。两个根因：① pane 可能自身是滚动容器（docx/xlsx 族 `.scrollable`），absolute 角标随内容滚走漂出 header 行；slide 不滚，角标恒钉行内。② 位置从 `right:12`（与堆叠钮同角叠印，截图 2）改 **`right:58` 堆叠钮左侧让位位**（fab 40 + 右缝 12 + 缝 6）—— 同排并立零相交。属性同步单点 = `syncCardHeaderPop`（活动切换/toggle 两路都汇于此）；pane 的 `data-independent` 属性保留（CW 链数据面仍消费）。
- **查找条改锚行下缘**：`.nf-findbar` top 从 `10px` 改 `var(--cv-head-clear)`（=56px）——修前与胶囊同顶带，长胶囊即压字（截图 4）。#926 件2 的 `right:66` 让位带随之整体退役：堆叠钮已固定在 header 行内、查找条在行下方，空间正交，**结构排他取代碰运气式让位**。
- **独立态胶囊收窄护栏**：`#canvas-slide[data-independent] .canvas-header` max-width 收窄 `calc(100% - 236px)` —— 窄面板 + 独立态极端组合下胶囊（180 固定宽）也不会伸进角标带，护栏内 t-name ellipsis 兜底。header 行三件（胶囊/堆叠钮/角标）任意组合零相交，排他是布局性的（行占位 + 下方起算），非 z-index 遮掩。
- 工具圆钮簇/缩放条本来就 bottom 锚（行下方），本批以 spec 钉死其与行带零相交（GU1-4/1-5）。

### 件三 · 统一毛玻璃风格模板（聊天控件 = 唯一模板，token 单源）

- **token 单源落 sapphire.css**（玻璃体系同文件，载入序最后）：`--cv-*` 档 —— 模糊配方（`--cv-bg/--cv-blur/--cv-saturate` = chat `#header` 的 `--glass-bg`/blur 24/saturate 1.15）、边框（`--cv-border`）、面阴影（`--cv-shadow` = `#header` 现值「仅顶缘 inset 高光、外投影零」，亮 0.25/暗 0.04 逐字同源）、hover-active 语言（`--cv-hover-bg/--cv-hover-blur/--cv-hover-saturate/--cv-hover-scale 1.05/--cv-active-scale 0.96`）、尺寸档（圆钮 40 / 条内小钮 26 / 图标 18·14 / 簇槽距 48 / 圆角 999·12·10·8·7 / 条内距与查找条输入宽）、header 行几何。**canvas 各控件只引用 token，零复制粘贴样式值**（GU2-7 静态钉）。
- **整族换装**：标题胶囊 `.canvas-header`、环胶囊 `.cr-capsule`、堆叠钮 `#deck-fab`、工具圆钮五枚（source/ref/find/graph/refresh）、文件内查找条 `.nf-findbar`（含内钮/输入/计数）、图片缩放条 `.canvas-zoom-bar`（含内钮）、独立/合并钮（`.canvas-pop-btn`/`.canvas-merge-btn`）、「独立中」角标位。要点：
  - find/graph 圆钮修前走 `--glass-control-*` 自绘（暗档 5% 白 + blur10 + 减淡 opacity .6）——与 source/ref 圆钮两种玻璃并存（截图 3 的「多种玻璃效果」主体）；现整族归一聊天模板，减淡退役（材质到位不靠减淡，S4-2 同规）。
  - 查找条修前自绘材质 + 自带外投影 `0 4px 16px`；现 token 化，外投影退役。
  - 缩放条内钮修前 `.glass-control` 实心 chip 面（zoom.js 去类）；现 ghost + hover 抬亮，与查找条内钮/聊天控件同语言。
  - `#deck-fab` 减淡 opacity .6 退役（A1-6 随批重钉为 opacity 1）。
  - 阴影对齐 `#header` 现值（仅顶缘 inset）：修前胶囊/圆钮走 `--frost-shadow`（外投影两档），本批统一后 **胶囊环 CR2-5「与 chat header 逐项相等」由红转绿**（该钉就是本令的前置钉，CSS 现在追上了）。
- 桌面居中/排他规则、环、deck、手机层自有形态不动；accent 态（ref-select.active、deck-fab[aria-pressed]、pop.is-independent、独立中角标的 sapphire chip）保留既有 sapphire 语言。

## 一、对照截图（暗色 · 面板形态 + 独立窗口形态 + 门户视口宽度）

`evidence/canvas-glass-unify/{before,after}/`（同机同 mock 同状态对拍；before = `git archive HEAD` 树实拍）：

| 文件 | 内容 | 修前症状 → 修后 |
|---|---|---|
| `desktop-2-find-open-band.png` | 开查找态 header 行（1440 暗色） | 查找条压住胶囊文字（截图 4）→ 胶囊+堆叠钮在行内、查找条在行下缘 |
| `desktop-4-independent-band.png` | 独立中态 header 行 | 「独立中」角标压堆叠钮（截图 2）→ 角标左移同排并立 |
| `cardwin-desktop.png` / `cardwin-portal.png` | 独立窗口形态（件一） | 胶囊行靠左 + chip 合并钮（截图 1）→ 水平居中 + ghost 合并钮 |
| `desktop-1-panel-md.png` / `portal-1-panel-md.png` | 面板形态全幅（含门户 1024 视口） | 圆钮两种玻璃 → 四枚圆钮同一材质 |
| `desktop-3-cluster-html.png` / `portal-3-cluster-html.png` | html 页签底右簇（圆钮+缩放条） | 缩放条 chip 内钮/异材 → 与圆钮同材同档 |

## 二、spec 结果

### 新验收钉 `tests/canvas-glass-unify.spec.mjs` — **31 PASS / 0 FAIL**

- **①header 行排他性**：GU1-1 开查找态胶囊×查找条 bounding box 零相交 + findbar top = `--cv-head-clear`(56px) token 锚；GU1-2 胶囊×堆叠钮零相交；GU1-3 独立态角标×堆叠钮/胶囊零相交（角标按 border-box 重构：badge [1317..1371] vs fab [1378..]，7px 实缝）；GU1-4/1-5 圆钮簇与缩放条×header 行带（0..46px）零相交。
- **②玻璃 token 生效证明**：GU2-1..5 关键控件（胶囊/圆钮 source·find·refresh/堆叠钮/查找条/缩放条）计算样式与 chat `#header` **逐项相等**（bg/backdropFilter/borderTopColor/boxShadow 四项全等，暗档实测 `rgba(24,28,38,0.68)` + `blur(24px) saturate(1.15)` + inset `rgba(255,255,255,0.04)`）；GU2-6 hover 抬亮 = `--cv-hover-bg` 计算值；GU2-7 token 单源静态钉（sapphire.css `--cv-*` 档在挂、查找条块消费 token、自绘外投影与 right:66 让位带退役）。GU-L1 亮档复跑逐项相等。
- **③独立窗口胶囊居中**：GU3-1 卡窗胶囊行中心 = 视口平分线（±3px 实测 390.0=390）；GU3-2 几何同面板档（36 高/999 圆角/180 宽）；GU3-3 材质与主窗胶囊逐项相等；GU3-4 合并钮在行内。

### 存量 canvas/mobile spec 回归（零新增失败）

| spec | 修前基线 | 修后 | 备注 |
|---|---|---|---|
| canvas-capsule-ring | 42/1（CR2-5 红） | **43/0** | 预存红随本批转绿（材质=chat header 逐项相等） |
| canvas-stack | 144/0 | **144/0** | A1-6 随批重钉（fab rest opacity 0.6→1，件三减淡退役；断言口径更新并在断言文本注明） |
| canvas-card-window | 22/0 | **22/0** | CW1-5 角标探针随批更新（pane::after → slide::after，件二迁移对应数据面断言同步） |
| canvas-cardhead-capsule | 27/2（CC6-2/CC6-3 预存红） | 27/2 | 同两腿同判据，零新增 |
| canvas-capsule-six | 19/0 | 19/0 | S4-1/L1 阴影相等经第一析支（=header.shadow）保持绿 |
| canvas-html-toolbar-overlap | 22/0 | 22/0 | 底右簇排他零复发 |
| canvas-html-zoom | 23/0 | 23/0 | |
| canvas-error-capsule-clearance | 8/0 | 8/0 | C2a/C2b 文本钉未动（top:10px 字面保留） |
| canvas-image-info-retire | — | 7/0 | |
| canvas-preview-nav-parity | HEAD 树实测 54/5/3 | 54/5/3 | 同 5 腿同判据（代理 URL 返回条族，预存），零新增 |
| canvas-video-viewer | HEAD 树 13/13 | 13/13 | 并行跑时 12/13 为负载抖动，单跑复绿 |
| canvas-tab-tombstone-revive（playwright runner） | — | 3 passed | |
| user-attach-canvas-open（playwright runner） | — | 5 passed | |
| mobile-canvas-sheet（#988，runner） | 10 passed | **19 passed**（与 canvas-file-refresh 合跑） | #988 移动端交互零破坏 |
| canvas-file-refresh（runner） | — | （同上合计 19 passed） | 刷新钮材质 token 化后座位逻辑不受扰 |

### 门禁

| 门 | 结果 |
|---|---|
| checkJs（`node scripts/check-js-types.mjs`，pinned tsc 5.5.4） | **PASS：257 < 基线 260**；触及文件（canvas.js / viewers/zoom.js）零错误 |
| build-web（`node scripts/build-web.mjs`） | PASS：188 源全入图、orphans 0、exemptions 5 |
| sbt compile | **PASS：641 sources，exit 0** |
| node --check | 触及 .js 均过（spec 内运行即证） |

## 三、触及文件与假设

- 代码：`css/sapphire.css`（--cv-* token 档）、`css/split.css`（控件换装 + 行排他 + 卡窗居中 + 角标迁移）、`js/canvas.js`（syncIndependentChip，+15 行）、`js/viewers/zoom.js`（去 .glass-control 类）。
- spec：`tests/canvas-glass-unify.spec.mjs`（新增）、`tests/canvas-glass-unify-shots.mjs`（新增，对拍截图工具）、`tests/canvas-stack.spec.mjs`（A1-6 重钉）、`tests/canvas-card-window.spec.mjs`（CW1-5 探针随批）。
- 附带卫生：spec 重跑覆写的 7 张旧批 evidence PNG（20261019 toolbar-overlap ×3 / 20261023 capsule-six ×4）已按 HEAD 精确还原，不入本批。
- 假设边界：①「聊天窗口控件现用的毛玻璃效果」取 = `#header` 玻璃面 + `.memory-btn` hover 语言的**当前计算值**（含底缘零边界批后的 inset-only 阴影）——CR2-5 存量钉与此解读互证；②「工具圆钮（历史回退/图谱/搜索/代码）」按卡面底右簇五枚（refresh↻/ref✛/find🔍/graph◉/source<>）整族处理（作者截图 3 的四枚即其中同时在场子集）；③门户视口宽取 1024 档（link.mystand.space 桌面门户量级），移动层（≤768）自有形态本批零触碰。
