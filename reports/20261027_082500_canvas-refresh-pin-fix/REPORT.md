# canvas 刷新钮定位修复批 — REPORT

作者报障 2026-10-27 08:05：Canvas 文件视图刷新按钮（↻）悬浮在文档文字中间（压在「Artemis 任务安全」「32 类粒」等正文上），预期钉在面板可视区右下角不随内容滚动。

## 根因（一句话）

刷新钮以 `position:absolute; bottom:16px` 挂在页签 pane 里，而 docx/yaml/xlsx/pptx 等查看器把 `.scrollable` 加在 **pane 自身**（pane 即滚动容器，`.canvas-tab-pane.scrollable { overflow-y:auto }`，split.css:1012）——absolute 子元素的 containing block 是整份滚动内容盒，`bottom:16` 落到**文档末尾**而非可视区底缘，滚动即漂移（报障截图形态）；md/code 等 pane 不自滚的页签（自带 `.canvas-md-scroll` 内滚器）不受累，故 2026-10-25 落地批的 md 验收全绿而漏过此类。

## 修法

挂载点从 pane 上移到**不滚动的面板壳 `#canvas-content`**（`position:relative + overflow:hidden`，split.css:806），按钮改为**每文档单例**，可见性与座位随活动页签同步：

- **shared.js**：`addRefreshButton(pane, { host })` —— `host` 为挂载壳（缺省回落 pane），`:scope >` 只摘自己的单例；新增 `seatRefreshButton(btn, pane)` 导出：座位实测（source/ref/find/graph 圆钮 + 缩放条让位，row0/row1 溢出落位逻辑不变）+ **壳层 padding 补偿**（`#canvas-content` padding-bottom 8px 加回，视觉边距仍以 pane 为准的 16px）；`addSourceToggle` 换态后派发 `canvas-source-mode-changed`（bubbles，同 `canvas-viewer-rerendered` 解耦先例）。
- **canvas.js**：`fileRefreshBtn` 单例 + `syncFileRefreshButton()`（活动页签非文件页签/源码态 ⇒ `hidden`；切签/渲染后重测座位）；`mountFileRefreshButton` 三个既有调用点签名不变（渲染后 ensure+sync，壳层按钮不被 viewer innerHTML 清掉，重挂收敛为 no-op）；点击时解析**当前**活动文件页签（单例生命周期长于单页签闭包）；`setActiveTab` 挂 sync；`canvas-source-mode-changed` 监听同步源码态隐藏。
- **split.css**：`.canvas-refresh-toggle[hidden] { display:none !important }`（原生 hidden 被作者样式 display:flex 压过，显式钉死）；退役 pane 内后代源码态选择器（按钮已不在 pane 内）。z 层级盘点：z:10 压 pane 内容（pane z:auto 不建层叠上下文）；#912 错误态顶部让位带为纯 padding 非浮层、卡头胶囊/胶囊环（头部区 z-30）与右下角空间不交叠，无互遮挡面。

主题（亮暗）只换 token 色值无几何参与，位置恒定；独立卡窗（?card-window=1 同 app 实例）自带同构壳层，天然覆盖。

## 验证

- **spec**：`tests/canvas-file-refresh.spec.mjs` **9/9 PASS**（A1-A7 既有断言随单例迁移改挂载点作用域，语义不变）。新增两钉：
  - **A8 滚动钉**：yaml 页签（pane 自滚复现类）滚 600px 后按钮视口 X/Y/W/H 逐项不变 + 仍钉可视右下（红绿对照：改回 pane 挂载即红「viewport Y unchanged after content scroll」失败，实锤漂移路径）。
  - **A9 主题钉**：colorScheme light/dark 双 context 同视口，按钮几何逐项一致 + 双主题可见。
- **checkJs**：257 < baseline 260（零新增，-3 为重写消除的存量）。
- **截图实证**（滚动态 scrollTop=1200，按钮 box 两主题同为 x:1213 y:785 40×40）：shots/01-light-scrolled-pinned.png、shots/02-dark-scrolled-pinned.png。

## 文件

- `src/main/resources/web/js/viewers/shared.js` — host 挂载/座位导出/padding 补偿/换态事件
- `src/main/resources/web/js/canvas.js` — 单例 + 活动页签同步 + 事件监听
- `src/main/resources/web/css/split.css` — hidden 钉死 + 注释层理
- `tests/canvas-file-refresh.spec.mjs` — 作用域迁移 + A8/A9 新钉
- 本报告 + 2 截图
