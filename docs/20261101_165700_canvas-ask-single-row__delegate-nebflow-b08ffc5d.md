---
type: batch
status: done
related: "[[20261101_154500_delegate-rcpt-diffstat__delegate-nebflow-6043f217]]"
---

# canvas 文档视图浮钮组单行排布（2026-11-01 16:18 作者令）

> 批令（作者 16:18，附实拍批注 IMG_1668）：canvas 文档阅读视图浮动按钮改**单行**——功能按钮（搜索/代码视图等红圈全部）**靠左**，ask 按钮**单独靠右**，一行排开（两端对齐），不再上下两行堆叠；ask 展开对话框正好落在按钮行**上一行**（贴着、向上展开）；样式皮肤零改动（玻璃/配色/尺寸归统一按钮包批 delegate-nebflow-8bbf8cf7 管，批文档未落 docs 时以分支为准），本批只动排布；桌面+手机同构同规则。前置位参考 [[20261031_160300_mobile-fab-unify-search]]（悬浮钮 16 栅格先例）。

## 1. 考古与归属裁定

- 目标组件 = **canvas 文档阅读视图**的浮钮组（作者补充确认「这是 canvas 的」）：右下角两层堆叠 = ask 入口钮（`.ask-fab`，被 syncSeat 抬到浮簇上沿+12 的悬层）+ 文档工具簇（`.canvas-source-toggle` 源码 / `.canvas-ref-select` 引用 / `.canvas-find-toggle` 查找 / `.canvas-zoom-bar` 缩放条 / 壳层 `.canvas-refresh-toggle` 刷新）。askDock.js syncSeat 的「角落浮件实测让位」腿就是两行堆叠的机制根源。
- **同形不同族的两组均未触碰**：`#deck-fab`（顶右叠卡视角钮）与 flowMap `.fm-fab`（链归档悬浮钮）只是材质同语言（「.canvas-ref-select 同式」字样），几何零交集；canvas epub 阅读器底栏（readerChrome `.epub-reader-nav`）属 canvas 域内让位对象，联动保持。

## 2. 改法（纯排布，槽值原样镜像）

| 文件 | 改动 |
|---|---|
| `css/split.css` | 工具簇右锚→左锚（source right:16→left:16、ref 64→64、缩放条 16→16、html 缩放条 160→160、窄面板二行槽同步），材质/尺寸 token 零变 |
| `js/viewers/shared.js` | `addFindToggle` 写 `style.left`（调用方槽值 64/112/16 零改）；刷新钮座位计算 `_refreshCornerItems`/`_refreshSeat`/`seatRefreshButton` 同式镜像到 left 轴（first-free-slot 数学不变） |
| `js/askDock.js` | syncSeat 让位腿退役：工具簇左锚化后与右缘 fab 异侧零交叠，实测让位集只剩通栏 epub 底栏（桌面 `.epub-reader-nav`）；桌面 fab 基准 14→22（与簇行共中线：簇面板系行带 [24..64] 中线 44 − fab 半高 22）；手机腿 epub 分支补 dock 联动上抬 |
| `css/ask.css` | `--ask-fab-bottom` 22px（共中线基准）；手机档 fab/dock 底缝 10→16 + `env(safe-area-inset-bottom)`——与左锚簇行（pane 内 bottom:16，pane 底 = 页底 = dock-h+env）同带共中线 |

- 视觉镜像副作用（报告披露）：簇内槽值原样、锚点镜像 ⇒ 簇内左右序随镜像翻转（md 面 [code][search]，原 [search][code]）；角缘序（source 最贴角）保持。作者令未钉簇内序；如需 [search][code] 读序换一对槽值即可。
- 对话框锚点零改动即满足令②：dock bottom = fab-bottom + size + 10px（fab 入行后 dock 底缘即钉 fab 顶上方 10px 缝 = 按钮行上一行贴着）；transform-origin 100% 100% 不变（向上展开）。

## 3. 验证

- `canvas-ask-dock` spec **75 pass / 0 fail**（重钉 T1c 基准 22 + 新增 T1f 单行两端对齐共中线、T1g 对话框钉按钮行上一行 10px 缝、T9b/T9b2 手机 dock-h+16 同带共中线）。
- `canvas-file-refresh` 8 pass + A2 存量红（git stash HEAD 原树同红实证：`.canvas-md-viewer` strict-mode 双命中，与本批无关）；A8/A9 随批重钉（左锚化后过渡中段左缘漂移 → 等宽速度收敛 <0.5px 再量几何；右缘钉改左缘钉）。
- `mobile-canvas-sheet` 全绿（T8 重钉：左锚簇零让位 + 同带断言）。
- `reader-p1` 56/56（epub 底栏让位联动健在）；`canvas-capsule-six` 17/2 = HEAD 等红（S4-1/L1 材质面存量，S3-1 随批翻成左锚 16 钉后回绿）。
- 存量红（HEAD stash 逐项同红实证，非本批）：`canvas-glass-unify` 20/11、`canvas-html-toolbar-overlap` / `canvas-html-zoom`（Fit/Zoom-in 英文 title 探测在本沙箱不命中，崩在断言前；O-gap 方向假设被镜像作废，已随批改侧向无关计算）、`ask-gate-live`（harness 级同崩）。
- 实拍取证：`evidence/20261101_canvas-ask-single-row__delegate-nebflow-b08ffc5d/`——before/after 各 16 帧（Chromium + WebKit × 暗/亮 × 桌面/手机 × 收起/展开含 mock 问答），全帧零 pageerror 零 console error（WebKit service-worker fetch 绕过 page.route 实证后改服务器侧 /api 兜底）。before 帧复现作者批注的两行堆叠，after 帧 = 功能簇贴左 / ask 独右一行 + 对话框钉其上一行。

## 4. 在飞批不干扰声明

统一按钮包批（delegate-nebflow-8bbf8cf7）与卡片家族批（67f23d42）同域在飞：本批 diff 只含定位属性（right→left、bottom 基准、座位数学、让位集），材质/尺寸/配色 token 零触碰——两侧工作区互不越界，合并时与最新 main reconcile。
