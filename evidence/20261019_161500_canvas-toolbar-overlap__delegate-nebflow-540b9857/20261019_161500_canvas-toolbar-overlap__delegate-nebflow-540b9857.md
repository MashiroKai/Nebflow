# Canvas HTML 预览底部工具条按钮重叠 · 修复批（canvas-toolbar-overlap）

**症状**（作者截图 2026-10-19 15.58，uploads/d7c3cb54-…/347942943706958_截屏2026-10-19 15.58.43.jpg）：
HTML 文件在 Canvas 打开后，底部浮动缩放工具条按钮叠印——缩小/100%/放大与「放大镜带箭头」形态的按钮叠在一起（= find 圆钮压在缩放条右端 Fit 钮上）；右侧准星（ref-select）与代码视图（source-toggle）正常。

## 根因

HTML pane 底右 chrome 簇全部 absolute 右锚定，槽位以 48px 节距叠放（40px 圆钮 + 8px 缝）：

| 元素 | 定位 | 出处 |
|---|---|---|
| `.canvas-source-toggle`（代码视图） | bottom:16 · right:16 | split.css:870 |
| `.canvas-ref-select`（准星） | bottom:16 · right:64 | split.css:901 |
| `.canvas-find-toggle`（放大镜） | bottom:16 · right:112（inline） | shared.js `addFindToggle(pane, 112)` ← html.js:709，wiki-graph 批（2026-10-14）新设槽位 |
| `.canvas-zoom-bar`（缩放条，140×36） | bottom:18 · **right:112** | split.css:1272（值定版时簇里只有 16/64 两个圆钮，注释「source/ref toggles at right:16/64」可证） |

wiki-graph 批把 find 圆钮放进 right:112 槽位后，没人挪缩放条 → 两条同坐 112 槽，find 圆钮（z-index 10）盖住缩放条右端 Fit 钮（z-index 5），实测交叠 1440px²（40×36）。

## 修复

`src/main/resources/web/css/split.css`：`.canvas-tab-pane[data-type="html"] .canvas-zoom-bar` 的 `right: 112px → 160px`——延续 48px 槽距节奏（16/64/112/160），与 find 圆钮右缘保持 8px 缝；注释同步改写（记录槽位全表与事故来源）。缩放条右锚定，面板宽度不改变簇内相对位置，任何宽度下结构性不复发。

## 验收

新增回归 spec `tests/canvas-html-toolbar-overlap.spec.mjs`（真实链路：workspace-open-item → viewHtml → 缩放条 + 三圆钮；boundingBox 几何实测 + elementFromPoint 命中面）：

- **修复前复跑（临时回退 right:112）**：13 PASS / 9 FAIL——三宽度全数复现 `find∩bar=1440px²`、gap=−40px、Fit 钮中心命中 `find`（= 作者截图，spec 可证伪）；
- **修复后**：**22 PASS / 0 FAIL**——
  - T-normal（1600 视口，面板 45vw）：两两零交叠、gap=8.0px、Fit/find 命中面正确；
  - T-narrow320（`--canvas-width:320px` = canvas.js MIN_CANVAS_WIDTH 拖拽下限）：同上零复发，缩放条完整落在 pane 内；
  - T-overlay（viewport 860 → body.canvas-overlay 抽屉形态）：同上零复发；
  - R0 三场景主文档零 JS 错误。
- 相关 canvas spec 回归全绿：`canvas-html-zoom` 23/23（缩放条行为全量）、`canvas-html-interactive` 17/17、`canvas-preview-nav-parity` 63 PASS / 2 SKIP（SKIP=live 门禁档腿，非本批）。

截图（本目录 `shots/`）：`20261019_toolbar-overlap-normal.png`（正常宽度）、`…narrow320.png`（320px 窄面板）、`…overlay.png`（窄窗口抽屉）——四组控件等距分离，与作者截图对照修复成立。

## 改动面

| 文件 | 改动 |
|---|---|
| src/main/resources/web/css/split.css | HTML pane 缩放条 right:112→160 + 注释改写（唯一行为改动，1 行） |
| tests/canvas-html-toolbar-overlap.spec.mjs | 新增回归 spec（本批验收） |
| evidence/20261019_161500_canvas-toolbar-overlap__delegate-nebflow-540b9857/ | 本报告 + 3 张验收截图 |
