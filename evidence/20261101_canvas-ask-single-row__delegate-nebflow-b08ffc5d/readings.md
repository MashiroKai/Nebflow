# readings — 单行排布批实拍与规格读数（2026-11-01）

## 实拍矩阵（shots-before / shots-after 各 16 帧）

引擎 × 主题 × 面 × 态：{chromium, webkit} × {dark, light} × {desktop 1600×1000, mobile 390×844} × {collapsed, expanded（含一轮 mock 问答）}。

- before 帧 = git archive HEAD 原树（stash 前 main tip 92ad94893）实拍，复现作者批注的两行堆叠（ask 悬层 + 功能钮第二行）。
- after 帧 = 本批工作树实拍：功能簇贴左 / ask 独右一行两端对齐；对话框钉按钮行上一行（fab 顶上方 10px 缝）向上展开。
- 全部 32 页面上下文零 pageerror、零 console error（WebKit service-worker fetch 绕过 page.route 实证 → 服务器侧 /api、/agents 兜底，web 树本体不受影响）。

## 规格读数汇总

| spec | 本批树 | HEAD 原树（stash 实证） | 结论 |
|---|---|---|---|
| canvas-ask-dock | 75 pass / 0 fail（含新钉 T1f/T1g/T9b2） | — | 本批验收主钉 |
| canvas-file-refresh | 8 pass + A2 红 | 8 pass + A2 红 | A2 存量红（strict-mode 双 viewer，与本批无关） |
| mobile-canvas-sheet | 全绿（T8 重钉单行同带） | 全绿（旧让位语义） | 随批重钉 |
| reader-p1 | 56/56 | — | epub 让位联动健在 |
| canvas-capsule-six | 17 PASS / 2 FAIL | 17 PASS / 2 FAIL | 等红 = S4-1/L1 材质存量；S3-1 翻左锚钉后回绿 |
| canvas-glass-unify | 20 PASS / 11 FAIL | 20 PASS / 11 FAIL | 等红 = 材质族存量（统一按钮包批域） |
| canvas-html-toolbar-overlap / canvas-html-zoom | 崩（Fit/Zoom-in title 探测） | 同崩 | 存量；O-gap 已随批改侧向无关 |
| ask-gate-live | harness 同崩 | 同崩 | 存量 |

## 关键几何读数

- 桌面：簇行带（面板系）[24..64] 中线 44px；fab 44px @ bottom 22 → [22..66] 中线 44 ✓ 共中线；dock 底缘 = fab 顶 +10px。
- 手机：fab（48px）@ dock-h(54)+env+16；左锚簇（48px）pane 内 bottom:16，pane 底 = 页底 = dock-h+env → 两带同中线（T9b2 ≤3px 实测 0）。
- A8 教训：右锚免疫面板开合过渡（右缘恒定），左锚随左缘漂移——几何断言前等宽速度收敛（相邻采样 <0.5px）。
