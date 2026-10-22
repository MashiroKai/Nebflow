---
type: design-note
status: open
---

# wiki-graph 极简化重设计 · 设计稿（候作者批）

> 本篇是图谱功能重设计的**设计稿**，只出稿与样张（样张 = 只做设计演示、不进产品代码的静态 HTML，[[glossary#^mockup-sample]]；样张本体见 [[wiki-graph-redesign-sample.html]]），**不含实施代码**；实施在作者批准本稿后另开批。截图渲染自样张，数据为仓内 `docs/` 真实引用关系（由 `check_wiki_docs.py --graph` 自动抽取）。

## 1. 背景与现状病灶

作者判词：「图谱功能做的非常极简好用 我现在都有点看不懂」——现状不是功能少，是**同屏信息通道太多**。对照 `web/js/wikiGraph.js` 逐条列病灶（行号为主分支现状）：

| # | 病灶 | 现状证据 |
|---|------|---------|
| ① | **控件层过载** | 同屏 5 层控件：工具栏（搜索/刷新/计数条，`renderInto` L188-262）+ 三排下拉 facet（kind/type/status）+ 缩放簇（L280-307）+ 侧栏文件列表（`syncGraphExplorerList`）+ 悬停邻域；打开图谱先要「学会面板」 |
| ② | **视觉语义并行过多** | 节点同时承载 5 个编码：形状（圆/方/虚线幽灵）× 状态环（doneRing）× 度数灰阶（`paintNodes`）× 悬停光晕（GLOW）× 焦点脉冲（`focusPulse`）；加上弧线边、邻域高亮、过滤压暗——读图要先解码 |
| ③ | **冷启动空读** | 引用关系（[[glossary#^citation-edge]]）靠 wiki-link（[[glossary#^wiki-link]]），而存量 `docs/` 几乎零引用（全库仅 1 篇文档有真实引用边）⇒ 图谱打开 = 一盘散点，无边可读 |
| ④ | **无摘要无预览** | 悬停只有标题（≤28 字符截断，L695），单击**直接跳走**打开文件（`bindCanvas` click→openTab）——没有「先看一眼再决定」的中间层，也没有摘要可读 |

## 2. 设计目标（作者令的转译）

1. **打开即懂**：默认视图三问内建答案——这是什么图（左上图例一句话）、节点是什么（悬停=标题+摘要）、边是什么（引用关系，图例注明）。
2. **节点=文档，边=引用**：数据从 Obsidian 链接自动抽取（管线见 §5），图谱零手工维护。
3. **极简交互**：悬停看摘要、单击出预览、再点才跳转；过滤器只留两个（搜索 + 目录）。
4. **视觉对齐 mystand.space**：单色石墨系、细线、大留白、无光晕无彩色强调，见 §4。

## 3. 交互规格（全部交互一张表）

| 动作 | 行为 |
|------|------|
| 悬停节点 | 光标旁 tooltip：文档标题 + 一行摘要 + 度数（「被引 3 · 引出 2」）；其余节点与边降至 12% 透明度，邻域保持全亮 |
| 单击节点 | 右侧预览卡滑入（280px）：标题、摘要、目录（前 5 条）、出入引用列表（各前 5 条，可点）、状态与类型徽标、「打开文档」按钮 |
| 再单击「打开」/双击节点 | 跳转打开文档（现状行为，仅多一层确认） |
| 单击空白 | 收起预览卡，回全图 |
| 拖拽空白 | 平移；拖拽节点 | 微调布局（布局自主收敛，不做手动摆位承诺） |
| 滚轮 | 以光标为锚缩放（8%-320%）；不设缩放按钮簇（滚轮 + 触控板已覆盖，少一层控件） |
| 搜索框 | 按标题/路径过滤，命中外全暗（保留现状语义，唯一文字过滤器） |
| 目录 chips | 顶栏一排目录过滤片（`design/`、`templates/`、`issues/`…，来自节点 `dir` 字段），单选「全部 | 某目录」——**替代三排 facet 下拉** |
| Esc | 逐层退出：预览卡 → 过滤 → 无 |

**信息行（替代计数条 + 侧栏）**：左下一行灰字「N 篇文档 · M 条引用 · K 条未解析引用（[[glossary#^unresolved-ref]]）」；断链不立节点、不弹窗，点 K 展开断链清单浮层——比 Obsidian 更克制。

## 4. 视觉规格（对齐 mystand.space）

- **形态**：节点=实心圆点（度数映射半径 5-11px），边=1px 直线（去弧线弯），无光晕、无脉冲、无装饰强调条。
- **色彩**：单色石墨系，无彩色强调；选中/悬停态用墨色加深表达。两主题变量表（对齐官网 `@theme` 令名，数值取自本仓官网邮件模板实测色板）：

| 令名 | Light | Dark |
|------|-------|------|
| `--color-background` | `#f5f6f7` | `#101112` |
| `--color-card` | `#ffffff` | `#1b1c1e` |
| `--color-foreground`（标题/实心节点） | `#141416` | `#f2f2f4` |
| `--color-muted`（边/次级文字/叶节点） | `#6f727b` | `#8f929b` |
| `--color-card-border`（细线/分隔） | `#e8e9ec` | `#313236` |

- **标签**：文档名直接标在节点下（≤22 字符截断），小图全显、大图随缩放淡出——「打开即懂」优先于省墨。
- **字重**：标题 500、正文 400、徽标 500，全站无 700（视觉裁定字重方案 B）；系统字体栈，无自定义字体。
- **动效**：布局收敛 <1.2s；预览卡滑入 180ms；无常驻动画（力导向稳定后停帧，省 CPU——现状 raf 常驻是隐性成本）。
- **无 emoji、无图标堆砌**：控件面只剩搜索框、目录 chips、信息行三个元素。

## 5. 数据管线（从 Obsidian 链接自动抽取）

现状管线已通，**零新依赖**：

```
docs/*.md ──解析──▶ WikiIndex.scala（wikilink/markdown 链接/front-matter/块锚；fence 屏蔽）
              │     （已实现：src/main/scala/nebflow/core/wiki/WikiIndex.scala）
              ▼
GET /api/wiki/graph ──▶ 前端 wikiGraph.js 渲染
```

实施时的**增量建议**（均为后端 JSON 字段增补，不改协议形状）：

1. `WikiIndex.nodeJson` 增 `summary` 字段：抽取规则 = 首 H1 后第一个非空非标题段落，去行内标记截 140 字符（样张已按此规则出数据，可复核效果）。
2. 增 `unresolvedCount` 汇总（或复用现有 warnings 通道）供信息行计数。
3. 前端节点 `dir` 字段由 `rel` 首段在客户端派生即可，无需后端改动。

冷启动问题（病灶③）的解法不在图谱层而在写作纪律层：wiki-docs 强制条款（本批已固化，见 [[glossary#^concept-first-use]] 与 visual-report 插件 `skills/wiki-docs/SKILL.md`）保证新文档自带引用边，图谱随文档批自然长出结构。

## 6. 样张与截图

样张：[[wiki-graph-redesign-sample.html]]（自包含 HTML，双击即开；`?node=<路径>` 直达某文档预览态、`?theme=light|dark` 强制主题、`?hover=1` 钉住悬停态，截图即由此拍摄）。数据 = `check_wiki_docs.py --graph` 于本批从仓内 `docs/` 实抽：**94 节点 / 82 条引用边 / 9 条未解析引用**（含本批新文档互相引用形成的真实簇；样张自身与截图附件也在图中——图谱包含它自己）。

**默认视图（light）**：

![[wiki-graph-redesign/overview-light.png]]

**默认视图（dark）**：

![[wiki-graph-redesign/overview-dark.png]]

**悬停态**（tooltip：标题+摘要+度数，邻域外压暗）：

![[wiki-graph-redesign/hover.png]]

**预览态**（单击节点右侧预览卡，出入引用可点）：

![[wiki-graph-redesign/preview.png]]

## 7. 实施映射（批准后开工，本批不动代码）

| 面 | 文件 | 动作 |
|----|------|------|
| 前端主体 | `src/main/resources/web/js/wikiGraph.js` | 按本稿重写渲染与交互层（力导向内核与数据装载可保留） |
| 样式 | `src/main/resources/web/css/split.css` | `wiki-graph-*` 面按 §4 变量表收敛；删三排 facet 样式 |
| 后端增量 | `src/main/scala/nebflow/core/wiki/WikiIndex.scala` | `nodeJson` 增 `summary`（§5 规则）；spec 补摘要抽取用例 |
| 词条 | `web/js/locales/{zh-CN,en}.js` | `wiki.*` 词条按新信息行/图例改写 |
| 测试 | `src/test/scala/nebflow/core/wiki/WikiIndexSpec.scala` | summary 字段断言 |

## 8. 验收清单（作者批样张时逐条过）

- [ ] 打开图谱 3 秒内能答：这是什么、节点是什么、边是什么（图例 + 摘要 tooltip）。
- [ ] 同屏控件 ≤3 种：搜索框、目录 chips、信息行。
- [ ] 悬停有摘要；单击出预览；预览卡内可跳转；空白单击收起。
- [ ] 两主题下无边框辉光、无彩色强调，与 mystand.space 石墨系一致。
- [ ] 布局稳定后动画停帧（无常驻 CPU 占用）。
- [ ] 断链不立节点，信息行计数可展开清单。

## 9. 左栏控制面契约（2026-10-21 作者令追加批，已实施）

作者令：图谱功能所有按钮和交互不要堆在 canvas 上——canvas 只显示简单的图谱本体；激活图谱视图时，把左侧栏（原文件浏览器那一栏）的空间利用起来放控制面。本节是 §3 交互表的**取代性修订**（§3 的画布顶栏/浮动预览卡/断链浮层随之退役），力导向内核与 §4 视觉规格不变。

### 9.1 面的划分

| 面 | 内容 |
|----|------|
| canvas（`.wiki-graph-pane`） | 力导向图 + 悬停 tooltip，**仅此两件**；顶栏（搜索+chips）、预览卡、信息行、图例、断链浮层全部撤出 |
| 左栏（`#wiki-graph-side`，挂在 `#panel-sessions` 内、`#explorer-section` 的兄弟位） | 搜索框 · 目录 chips 过滤 · 选中节点详情卡（徽标/摘要/目录/引出/被引/路径/打开文档——替代浮动预览卡）· 断链清单（固定区块，替代浮层）· 统计行（N 篇文档 · N 条引用 · N 条未解析） |

### 9.2 双向联动

- 左栏 → canvas：目录 chips 单选过滤；详情卡词条（引出/被引）点击 = 换选并居中；断链清单行点击 = 选中源文档；搜索框实时过滤。
- canvas → 左栏：单击节点 → 详情卡刷新（Esc 或空白单击退选回空态）；悬停 tooltip 不变。
- Esc 逐层退出：选中 → 搜索过滤 → 目录过滤。

### 9.3 展开/还原（承接 2026-10-19 批 R 系语义）

图谱「变为可见」的**跃迁**才驱动左栏：打开 tab / 切回 tab / Canvas 重开 / 刷新 restore → 自动展开 Side Bar 并激活 files 面板（走 `showSidePanel` 既有持久化 API，零新键）；图谱可见期间手动收起 → 本查看会话安静；切走/关 tab/收 Canvas → 左栏还原文件浏览器，且**绝不自动收起**；移动端 ≤768px 不自动展开（抽屉互斥）。

### 9.4 主题与窄面板

canvas 沿用 §4 的 `--wg-*` 石墨双主题表；左栏控制面穿侧栏玻璃卡既有 token（`--color-frame-*` / `--glass-*`），亮暗随 `prefers-color-scheme` 自动。窄面板走 container query（`wgside`，≤209px 紧凑档：收紧内距、chips 与行字号降档）——面板宽度由拖拽手柄决定，与视口无关。

### 9.5 移动层

手机壳没有左栏：`.wg-panebar`（搜索 + chips + 统计行）随 pane 收编进移动页，仅 `body.mobile-layer` 显示——桌面 canvas 保持零控件。断链清单在移动层不给（计数仍在统计行）。

### 9.6 验证

- `scripts/e2e-wiki-graph-redesign.cjs`：真实 docs 数据 playwright 截图——canvas 纯图态 / 左栏控制面 / 点选联动 / 断链区块 四态 × 亮暗，另含窄面板容器查询与移动层 panebar；证据落 `evidence/20261022_wiki-graph-sidepanel/`。
- `tests/wiki-graph-ux.spec.mjs`：左栏契约 spec（展开/还原 R 系 + canvas 纯度 + 双向联动 + 内容口径断言）。

### 9.7 追加批（2026-10-22 作者令：触控板手感 / 毛玻璃统一 / 渐隐退役，已实施）

1. **触控板缩放手感（excalidraw/figma 同款约定）**：滚轮/双指滚动 = 平移（1:1 跟手，Shift+纵滚 = 横移）；触控板捏合（ctrl/meta+wheel）/ ⌘(Ctrl)+滚轮 = 以光标为锚点缩放——每事件按 `exp(-Δy·0.0015)` 累积进 zoomTarget，rAF 每帧追近 25%（速度∝剩余距离，剩余差 <0.0005 直接贴合），锚点 = 最近指针位置做几何补偿，追帧不唤醒布局 sim（§4 停帧语义保留）。约定写进左栏 `.wg-hint` 图例行，canvas 保持纯图。
2. **毛玻璃统一标准（全站一套 frosted）**：canvas 顶部胶囊、左栏搜索框/chips 与 chat header 同材质——`--glass-bg` + `blur(var(--glass-blur)) saturate(1.15)` + `--glass-border` + `--frost-shadow`（新增于 sapphire.css，亮/暗两档，#header 同步改用 token 单源）；chips 无折射线，`.on` 选中片为 accent 态不在契约内。
3. **canvas 渐隐退役（与聊天窗口渐隐同令）**：`.canvas-header.scroll-under-glass::after` 裙边桌面 `display:none`（胶囊本体 frosted 保留）；画布内容（节点/连线/文档文字）不再穿胶囊渐变淡出。手机层维持现状——与 UI 批⑥⑦「顶部渐隐穿顶 = 手机层唯一」同一条桌面/手机分界线。
4. **验证**：harness 增 ⑥ frost 逐项相等 / ⑦ 裙边退役 / ⑧ 24 帧连续捏合+40 帧采样（逐帧步进 ≤0.06、锚点世界点漂移 <2px、收敛 zoomTarget）共 16 断言（63/63 过）；spec 增 §E（wheel 平移 vs 捏合阻尼缩放）/ §F（frosted 相等 + 裙边退役）两条契约（12/12 过）。

### 9.8 追加批二（2026-10-22 作者令：canvas 顶部胶囊交互重做，已实施）

1. **平时态**：单胶囊（`.canvas-title`），整头缩一档（padding 4/7/12、t-name 12px）；单页签时 hover 不展开（平时态即全部）。
2. **hover 态 = 胶囊环**（`.capsule-ring`，纯前端组件：CSS 3D 沿弧排布 + backdrop filter，与左栏控制面零共享）：各页签胶囊沿圆环排布、**最多同时可见 3 个**——中心 = 环游标（内容-only 覆层，底 = 标题头胶囊本体，同位无缝 morph），±1 缘位沿曲率 `rotateY(∓34°) translateZ(-110px)` + `blur(1.6px) saturate(1.25)` + opacity .82（cover-flow 透视模糊扭曲），|rel|≥2 弧外不可见；**左右滚动换选**（触控板横滑 wheel deltaX / Shift+纵滚，56px 一格，累加器余量随动、环绕）；**点击即切换**（缘位 click；中心位经标题头手势 finish !moved 臂 `ringClickCenter`）。材质 = 全站统一 frosted token（缘位完整药丸：`--glass-bg/--glass-blur saturate(1.15)/--glass-border/--frost-shadow`，与 chat header computed 逐项相等）。
3. **按住 = 交互按钮语义**：`.canvas-header` 全头 `user-select:none`（严禁文字多选/双击选中）+ `dragstart` preventDefault（拦从胶囊发起的原生拖拽，OS 文件拖入 drop 落区不受影响）；环胶囊 = `div[role=button]`（`<button>` 会落标题头手势「钮群直通」守卫，环开着时按压/竖滑换卡/横滑关卡手势全灭）；缘位 pointerdown stopPropagation（纯点击钮），中心位事件穿透标题头（CC1 按压触感 / CC2 换卡 / CC3 关卡手势在环开着时照旧）。
4. **右键菜单**（`capsuleMenuActions` 一份语义，标题头与环胶囊共用）：引用当前页（保留，CC4 契约）＋ **多开**（铺开叠卡环 openDeck；deck 开着换形「收起叠卡」）＋ **关闭**（deck 态 deckRequestClose / 常态 closeTab；deck 态另有「关闭其他」）＋ **在独立窗口打开**（`popoutCardWindow`/`toggleCardWindow` = #717 卡窗链；独立中换形「合并回主窗」；非文件卡不挂）。
5. **#716 分区（横滑不冲突）**：环上滚轮 preventDefault + stopPropagation 优先消费——胶囊环上横滑 = 环换选，画布区横滑仍 = 平移、捏合仍 = 缩放（wiki spec §G 双侧钉：环上换选时图谱 vx/vy/scale/zoomTarget 逐字不变）。
6. **边界**：手机层/卡窗态整腿让位；hover 展开（pointerenter，非触针）+ 离环 140ms 宽限收起 + Esc 收环；任何换签收环；页签增减重建/收环（<2 收）。
7. **验证**：`tests/canvas-capsule-ring.spec.mjs` 24 断言（展开/收起/换选/切换/菜单四项/popout 往返/deck 开收/按钮语义/单胶囊不展开）全绿；`tests/wiki-graph-ux.spec.mjs` 增 §G（13/13）；`tests/canvas-cardhead-capsule.spec.mjs` legacy 契约回归 28/28；证据 `shots/8-capsule-ring-dark.png`。


## 相关文档

- 样张：[[wiki-graph-redesign-sample.html]]（HTML，双击打开）。
- 数据抽取与纪律校验：visual-report 插件 `skills/wiki-docs/scripts/check_wiki_docs.py`（`--graph` 出本样张数据）。
- 术语：[[glossary]]；库入口：[[index]]。
- 现状参考：`docs/design/visual-report-gate-plan.zh-CN.md`（图谱门面历史方案，含 wiki-docs 早期口径）。
