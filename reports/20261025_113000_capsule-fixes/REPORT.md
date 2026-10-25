# 修复批 · 胶囊三 bug（作者报障 2026-10-25 10:4x + 同日追加）

工单逐字：
1. 「现在还是存在胶囊底下，文件名称重复显示的问题，主要是图谱。」
2. 「然后胶囊的交互也有问题，就是hover的效果应该是只要鼠标在胶囊上，就应该一直能上下滚动，现在是如果滚动，切换了其他卡片，那么就无法再次滚动，需要重新hover触发。」
3. （追加）「而且所有面板的胶囊大小应该保持一致，现在是我们内部的面板明显小一圈，而且还有无法完全显示的bug」

交付：三修复落 `delegate/delegate-nebflow-2fe0b232`（全前端，零 Scala 面）＋ Playwright spec 三件全绿＋ checkJs 零新增。

---

## Bug A：图谱悬停面「文件名称重复显示」——根因两处（同屏同名复读）

胶囊＝canvas 顶带标题胶囊（及其 hover 展开环）；「主要是图谱」的重复显示来源在图谱悬停 pill（`.wg-tip` 玻璃胶囊）与画布标签的同屏复读链：

### A-① 引用边 pill 的 `.td` 行复读 `.tt` 行的同一文档名
- **根因**：`src/main/resources/web/js/wikiGraph.js:1443`（修前）——`showEdgeTip` 第三行 `.td` = `(d.title || d.rel) + ' · ' + 度数/跳转`，而第一行 `.tt` 已是同一个 `d.title`。非段落态（普通引用边）**恒复读**；段落快照态在 `para.title` 缺失时（`.tt` = § + 文档名）也复读。pill 内部上下两行同名 = 「胶囊底下，文件名称重复显示」的逐字现场。
- **旁证（"还是存在"的来历）**：节点悬停 pill（`showTip`）的 `.td` 早已是干净口径（目录 · 被引/引出，无标题）——早前修过一半，边悬停腿漏网。
- **修复**（wikiGraph.js `showEdgeTip`）：`.td` 只补元信息（`点击跳转…` / `被引 X · 引出 Y`）；文档名仅在 § 段落快照态且 `para.title` 存在时保留（那时 `.tt` 是段标题，文档名是归属交代不是重复）。`wiki-graph-paragraph` §P3 契约（回退态 `.td` 跳转提示）保持。

### A-② 悬停节点的画布标签与 pill 标题同屏复读
- **根因**：`src/main/resources/web/js/wikiGraph.js:1124-1133`（修前）——`draw()` 标签密度规则 `isFocusNode → labelAlpha = 1`：悬停节点的画布标签必然绘制，而悬停 pill 同刻显示同一标题 → 节点上下两处同名。图谱是唯一有此悬停面的视图，故「主要是图谱」。
- **修复**（wikiGraph.js `draw()` + `showTip`/`showEdgeTip`/`hideTip` + `bindCanvas` pointermove 重排）：新增 `view.tipFor`（当前 pill 点名的节点：悬停节点 / 引用边目标 b）；`draw()` 对 pill 点名节点让位画布标签（`pillNamed → labelAlpha = 0`）。**消除重复来源，非掩盖**：pill 收起（`hideTip` 清 `tipFor`）下一帧标签即恢复。pointermove 重排 = pill 命名（tipFor）先落、重绘后行；hover/边/target 易主才重绘（静息 hover 内移动零重绘）。
- 探针：`window.__wikiGraphProbe()` 增 `hover` / `tipFor` 两读数（承 `__canvasDeck` 探针纪律）。

### A-③ 图片查看器的「文件名+体积」标签行与标题胶囊叠置（作者实截图第三现场）
- **根因**：`src/main/resources/web/js/viewers/image.js:78-81`（修前）——`img.onload` 在 pane 顶部渲染 `.image-info` 行 = `` `${fileName} — ${formatSize(size)}` ``（逐字即截图中的「fig07_He破案_….png — 279.2 KB」）。canvas 顶带标题胶囊（icon + 文件名，居中档）与它同屏叠置：胶囊叠在标签行上，标签行只露出边缘 = 「胶囊背后浮着一层更大的暗色文字层」。**「图谱」语境**：图谱里点开附件节点（fig07_*.png）→ canvas 换到图片页签 → 标签行现形。`viewers/video.js:49-52` 同型（`split.css:2103` 那条规则由此而来），一并退役。
- **修复**（胶囊互斥令：胶囊在场 ⇒ 不渲染底层标签，非 z-index 遮盖）：image.js / video.js 渲染端摘除整层 `.image-info`（`formatSize` import 随之剪除；`escapeHtml` 错误面板腿保留）；`split.css:2068`（image）/ `:2103`（video）两条规则随层退役（留痕注释）。**退役安全性核查**：image/video viewer 唯一消费腿 = canvas 文件页签（canvas.js:1213/1307）＋附件预览页签（attachmentPreview.js renderIntoTab→openTab）＋卡窗——三腿标题胶囊全在场，无「无胶囊」消费面；文件名由胶囊单面承载，体积不再展示（胶囊本就不含，非重复信息丢失）。
- 探针/验红：`tests/canvas-image-info-retire.spec.mjs` R1a 前置走真 `img.onload` 路径（修前创建 info 的同一路径），stash 三文件后 R1b/R1c/R3/R4 四断言全败（标签行复现 + CSS 规则回归），pop 后 7/7 绿。

## Bug B：胶囊 hover 滚动连续性丧失——根因一处（换签无条件收环 + pointerenter 不可重入）

- **根因**：`src/main/resources/web/js/canvas.js:3495`（修前）——`canvas-tab-switched → closeRing()` 无条件收环。滚轮监听（document capture 的 `onDocWheel`）随环拆走；而指针未离头时 `pointerenter` **不会再次触发** ⇒ 换签后滚动链死断，必须移出再 hover（复现 probe：环开 → 滚动换选 → 点击切换 → 环收 → 滚轮死）。
- **修复**（canvas.js `initCapsuleRing`）：换签时指针仍在 hover 区（`openPt ∪ 标题头 ∪ 非弧外胶囊`联合盒，与 `evaluateLeave` 同一判定）＝环保持开＋游标跳到新激活页签（`acc/accY` 清零、`layout()` 重排）——滚动零断链；指针已离区（他处换签）＝收环腿保留。配套：`onDocWheel` 增 deck 让位守卫（deck 态不开环铁律：环开着时 deck 开启 → 首次滚轮即收环让位叠卡擦洗）。
- 契约换代：旧钉「点击缘位＝切换＋环收」（canvas-capsule-ring.spec CR4，2026-10-22/23 批）被本令取代——指针在区换签不再收环；spec 已重钉（CR4/CR4b/CR4c），文件头注明换代依据。
- 滚动语义保持 2026-10-22 令不变：滚动＝换选、点击＝切换（CR3-1 钉保持）。若作者本意是「滚动直接切换激活页签」，那是 CR3-1 契约换代，需新令。

## Bug C：胶囊尺寸跨面板不一致 + 显示不全——根因两处

### C-① 高度随子件浮沉（「内部的面板明显小一圈」）
- **根因**：`src/main/resources/web/css/split.css:752-755`（修前）——`body:not(.mobile-layer) .canvas-header { height: auto; … }`（2026-10-22「平时态尺寸调小」批把高度交给内容撑）。文件页签有 `canvas-pop-btn`（26×26，`syncCardHeaderPop` 只给有 `absPath` 的文件卡装，canvas.js:1748）撑到 **36px**；功能性面板页签（图谱/插件/任务列表/设置，pop 钮 hidden）塌到 **27px**。实测对照：文件卡 36 vs 插件/图谱 27 = 面板间「明显小一圈」。
- **修复**：同一规则原位改 `height: 36px`（单一来源；全局 border-box（base.css:66）含 padding/border）——所有面板同高。卡窗配套 `split.css:3041` padding-top 8→4（旧 40px 形态遗产；8+4+26 合并钮=38 在 36 内溢出 2px）。手机层通栏卡头（mobileLayer.css §⑨ 自有形态）由 `:not(.mobile-layer)` 让位不锁高。

### C-② 循环百分比钳制截断一切标题（「无法完全显示」）
- **根因**：`src/main/resources/web/css/split.css:1262`（修前）`.canvas-title { max-width: 62% }`（deck-narrow 46% 同病，:1266）——全宽标题条时代（b5d507737 v5 叠卡批）的遗产；UI 批⑨ 胶囊化后 `.canvas-header` 变 `fit-content`（:669），子级百分比 max-width 对 fit-content 父级是**循环约束**，Chrome 收敛在「标题自然宽 ×~0.62」的定点 ⇒ 空间再充裕也截 ~38%。实测修前：插件 **6.9px**/24px 自然宽、文档图谱 **21.8px**/48px、short.md 44.3px/52px、长文件名 102.4px/250px —— 全截。
- **修复**：`max-width: none`（两处，附循环约束机理注）；显示上限交还父级真实几何 cap（居中批 `split.css:711` `min(76%, max(220px, calc(100% - 368px)))`，对 `#canvas-slide` 非循环）；`.t-name` ellipsis（:1284）只在真到缘时兜底；flex 收缩链 `min-width: 0`（title/t-inner/t-name 三级）保证不溢出。修后实测：插件 24/24、文档图谱 48/48、short.md 52/52 全显示；长文件名按设计省略号截断且不溢出（可见宽 102→146px，胶囊 220px ≤ cap 220，右缘不出面板）。

---

## 验证（全部实跑）

| 套件 | 结果 | 备注 |
|---|---|---|
| **tests/capsule-hover-chain.spec.mjs**（新·B） | **9/9 PASS** | 工单验收标准钉：光标不离胶囊点，连续 5 轮「滚动→点击切换」全链零失效（alpha→bravo→charlie→delta→alpha）＋链尾滚轮仍换选＋缘位点击腿＋deck 让位腿。**验红**：stash 修复后第 1 轮即死断（open=false），逐字复现报障 |
| **tests/wiki-graph-tip-dup-label.spec.mjs**（新·A） | **4/4 PASS** | T1 像素级：悬停中节点标签带墨迹清零、移开恢复；T2 普通边 `.td` 零复读；T3 § 态保留文档名归属；T4 回退态零复读＋§P3 跳转提示保持。**验红**：stash 后 T1/T2/T4 全败 |
| **tests/capsule-size-uniform.spec.mjs**（新·C） | **16/16 PASS** | C1 跨面板同高（文件卡/插件/图谱全 36px）；C2 三类短标题零截断；C3 长名 cap 内省略号不溢出；C4 高度与 pop 钮解耦；C5 卡窗同高 36＋全显示；C6 环开态高度不变。**验红**：stash 后 9 FAIL（面板 27px、标题全截、卡窗 40px） |
| **tests/canvas-image-info-retire.spec.mjs**（新·A③） | **7/7 PASS** | R1a 前置真 onload；R1b/R1c 图片页签 pane+全文档零 `.image-info`；R2 胶囊唯一名称面（#canvas-title-name = 文件名）；R3 split.css 零 `.image-info` 规则选择器（image+video 两规则同验）；R4 视频页签 video 在、info 无；R5 零 JS 错误。**验红**：stash 三文件后 R1b/R1c/R3/R4 四败（标签行复现） |
| canvas-capsule-ring.spec.mjs（CR4 重钉＋CR4b/CR4c 新增） | 36 PASS / 1 FAIL | 唯一 FAIL＝CR2-5 材质断言，**既有基点**（2026-10-23 批记录「CR2-5 材质=HEAD stash 逐位同败既有基点」，chat header 玻璃 token 的 border/shadow 计算值差为 CSS 面旧账） |
| wiki-graph-ux.spec.mjs | 12/13 | 唯一 FAIL＝§F frosted 材质，同 CR2-5 旧账（2026-10-23 批记录「§F 同败 main HEAD 既有」） |
| wiki-graph-paragraph.spec.mjs | 全绿 | §P3 契约保持 |
| canvas-card-window.spec.mjs | 22/22 | 邻族（卡窗面含 C 修复回归） |
| canvas-stack.spec.mjs | **144/144** | deck 滚轮/叠卡邻族（deck 前卡胶囊 36 档回归绿） |
| canvas-cardhead-capsule.spec.mjs | 25/28 | CC6-1/2/4 三红＝**HEAD 逐位同败**（stash 对照实跑证实，非本批引入；存量，疑属 340f4a922 面板合并+移动端 UX 七件后未重钉） |
| canvas-video-viewer.spec.mjs | 13/13 | video viewer 邻族（A③ video.js 同型摘除回归绿） |
| canvas-preview-nav-parity.spec.mjs | 54 PASS / 5 FAIL / 3 SKIP | 5 FAIL＝「修② 代理 URL 导航/返回条」腿，**HEAD 逐位同败**（stash 对照实跑同数同腿，iframe 代理导航存量，与本批无关） |
| checkJs gate | 260 = HEAD 260，**零新增** | 260−基线 257 的 3 错全来自 `nearpath.js`（ab81fbc02 提交时未补基线行的存量；stash 对照实跑同数）；本批触件 canvas.js / wikiGraph.js / split.css 零错误贡献 |

证据截图：`evidence/20261025_capsule-fixes/shots/`（亮暗 × 环开图谱现场 / 换签后滚轮仍活 / 节点悬停 pill 标签让位 / 收起常态）。

## 未做 / 边界

- CR2-5/§F 材质旧账、canvas-cardhead CC6 三红、nearpath.js 基线行：均 HEAD 既有（stash 对照实证），不属本批工单，未越界代修。
- 环缘位胶囊（.cr-capsule，24px cover-flow 深度档）与标题头胶囊的尺寸差是环几何的既有设计（spec 钉 edgeY/越出量以 24px offsetHeight 为基准），非「面板间不一致」范畴，未动；若作者要环缘位同 36px，属环几何换代需新令。
- 滚动＝换选契约见 Bug B 节尾注。

## 假设声明

- Bug A 按图谱悬停 pill 链定位（A-①/A-② 两处实证的同屏同名复读）；环本体（`#canvas-capsule-ring`）以作者 31 页签真数据集复演（canvas_tabs.json 灌入 probe）未见重复渲染，tab-strip/标题头/环均无 append-而非-replace 形态（`renderCanvasHeader`/`build()` 全 textContent/清空重建）。
- Bug C 的「内部的面板」取「canvas 内功能性面板页签」义（图谱/插件/任务列表/设置）——与「文件卡」的 27↔36px 高度差和全标题截断两处实证吻合；环开 hover 态与 deck 缩放反量（--stack-scale-inv，尺寸统一批既有）不在报障面。
- 环开态常驻后缘位胶囊按既有稳态门（0.36s）接管可点性——连续滚动场景不点缘位，无回归面。
