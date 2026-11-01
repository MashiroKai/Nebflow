---
type: batch
status: final
date: 2026-11-01
topic: 手机端手势三件批——图谱触摸即拖（去显式拉手）+ 手势仲裁（内容操作优先）+ 次级面板边缘返回（iOS 同款）
---

# 手机端手势三件批（2026-11-01，作者 14:09 令）

关联：作者 14:09 派单原文三件——①图谱拖动去显式拉手（默认可直接拖动/平移，双指缩放保留现状，不需要模式切换）②手势冲突治理（内容区起手的横滑归内容操作，切卡手势限定在非交互内容区/边缘起手；原则=内容操作优先、误切率趋零）③次级面板边缘返回（所有手机端次级面板统一左缘右滑返回）。验收口径：手机视口 390×844、Chromium + WebKit 双引擎、拖图/调尺寸零误切卡、每个次级面板边缘右滑真实返回、桌面 1280 回归不受影响、零 console error。关联文档：[[20261031_193000_弹窗退场批__delegate-nebflow-4c0dfd9f]]（移动页面收编模型=本批手势宿主）、[[20261031_161500_mobile-topbar-batch__delegate-nebflow-53e293d1]]（手机层导航家族）、[[20261101_125600_mobile-chat-blank-hotfix__delegate-nebflow-0df6b25c]]（手机详情页几何先例）。在飞批（#1281 统一按钮设计稿）工作区/分支零触碰。

## ① 图谱触摸即拖（去显式拉手）

**实证**：「拉手」按钮在图谱页代码中**不存在**（页面按钮扫描命中 0，spec T1b 防复活钉钉死不存在态）。拖不动的真因 = `.wiki-canvas` 无 `touch-action` 声明（默认 `auto`）→ 浏览器手势接管触屏拖动 → 约 2 个 pointermove 后 pointercancel，指针管线从未活过。修 root cause 而非造开关。

修法：

- `src/main/resources/web/css/split.css:3340-3344`：`.wiki-canvas` 加 `touch-action: none`（画布无滚动语义；桌面鼠标路径零影响——touch-action 只约束触屏/滚轮默认行为，wheel 本就 preventDefault）。
- `src/main/resources/web/js/wikiGraph.js:1273-1420`（bindCanvas 重写）：pointers Map 簿记（1281）；双指 pinch（1282，baseDist/baseScale/mid，zoomTarget = baseScale×dist 比，1337）走既有 damped zoomTarget 管线（rAF 25% 追帧），锚点 = 双指中点；pinch 中 view.dragging/panning 清零、退指 2→1 恢复 panning + lastPointer 重置、`pinched`（1283）钉死本次序列点选；endDrag 用 canvasXY 坐标。文件头 27-31 行批注（**禁做任何 pan-mode/拉手式显式模式切换**）。
- 桌面 wheel 缩放/中键平移管线零改动（T6b/T6c 回归钉）。

## ② 手势仲裁（内容操作优先、误切率趋零）

修前实证（图片卡）：抓手开 + 横拖 → `card-switch` 误发 + 图片不平移 + `.panning` 类滞留——切卡手势的 host `setPointerCapture` 抢占 viewport 事件流是图片拖动死 chain 的根因之一。

修法（`src/main/resources/web/js/mobile/navController.js`）：

- 621 行 `CONTENT_GESTURE_SELECTOR` = `.canvas-image-viewport, .wiki-canvas, .fm-viewport, [data-nf-content-gesture]`（末项 = 后续内容手势面的登记逃逸口）。
- bindDocSwipe（936）三闸：内容手势面起手 return（983）——零接管、零 capture 抢占；双指 `isPrimary===false` 即解除已武装切卡（pinch 语义优先）；`swipeSuppressed`（626）让路边缘返回（993），dropSwipePane 清内联 transform、pointerdown 自愈。
- 普通内容区横滑切卡**保留**（T2f/T4 回归钉：仲裁没杀功能）。

## ③ 次级面板边缘返回（iOS 同款）

`navController.js:1044-1255`：`EDGE_BAND_PX=20`（左缘带宽，1055）、`EDGE_AXIS_PX=8`（轴向判定）、`EDGE_FLICK_MS=200`（快弹窗，1057）；`bindEdgeBack`（1075，capture 相位，`stack.length>1` 才认领——dock 根页与聊天根一级面永不进来）；阻尼跟手 `edgeDamp`（1070）；过阈 `max(80, w*0.22)` 或快弹 `dx>28&&dt<200`（1123）提交 `backOut()`（1255，pop 一层，与「<」返回钮同源），否则弹簧回位。挂点在 ensureContainer（401）——详情页进出零重绑，全详情页统一覆盖。

两个坑（已埋已修）：① `claimEdgeBack`（1166）合成 pointercancel 收走起手目标会触发自家 settle → `edgeBack` 被置 null 后 `'claimed' of null` 崩——settle 首行 `if (!e.isTrusted) return;`（1113）守卫；② 可滚动详情页（用量）带内右滑被浏览器滚动接管 pointercancel——bindEdgeBack 尾部 document 级 touchmove preventDefault（1149，横意图才拦；鼠标腿零影响）。

按钮面/纵滑/左滑零劫持（T3g/T3h/T3i 钉）；用量页、图谱页、可滚动详情页边缘右滑均真实返回（T3b/T3d）。

## 验证（全部实跑）

- 验收 spec `tests/mobile-gesture-tribunal.spec.mjs`（新，双引擎 standalone，`node tests/mobile-gesture-tribunal.spec.mjs` / `ENGINE=webkit PORT=8333 …`）：**Chromium 28/28**（CDP 真触屏序列：触摸即拖 vx Δ=150、零 pointercancel、pinch 1.00→2.17、pinch 收指后单指仍平移、图片面横拖零切卡 + 图片随拖平移 + 零 `.panning` 滞留、`.fm-viewport` 零切卡、边缘返回/弹簧/按钮面/dock 边界、T5 零 console）；**WebKit 24 PASS / 0 FAIL**（mouse 腿同 Pointer Events 管线 + touch-action 计算样式/静态钉；触屏专属 4 腿 T1d/T1e/T1f/T2d 按 isTouch 门控跳过）。桌面 1280 腿 T6a-T6e 双引擎全绿（桌面壳判据、CAD 拖/缩放原样、图片抓手门契约原样、零错误）。
- 证据：`evidence/20261101_mobile-gesture-tribunal/`（chromium/webkit × t1-graph-drag / t5-final 共 4 张）。
- 回归（`node <stand>/node_modules/@playwright/test/cli.js test`，worktree 无 node_modules 按仓库惯例走宿主绝对路径）：批 A（mobile-canvas-sheet、mobile-p9-graph、mobile-dock、mobile-p12-settings、mobile-v3、mobile-p11-usage、wiki-graph-ux、wiki-graph-seal、wiki-graph-paragraph、canvas-image-info-retire、mobile-pop-canvas-open、mobile-popup-retire、mobile-topbar、authgate-wiki-graph-403）**87 passed / 2 failed**——2 条失败（wiki-graph-ux R8 panebar 跟随、§F 玻璃材质阴影断言）经宿主 main `b6ceb9d0e` 无批复跑 = **同两条同位置 fail，存量**（断言面=玻璃材质，与本批改动面零交集）；批 B（wiki-graph-multivault + wiki-graph-tip-dup-label，同用 8197 端口串行）：multivault **3 passed**；tip-dup-label 4 条挂（悬停 pill 同屏去重契约）——**stash bisect 实证为存量**：本批三文件 stash 后基线复跑同 4 条同位 fail（本批 diff 皆非肇因）。合计 90 passed / 6 条存量失败，本批归因失败 0。
- 静态闸：check-circular 的 navController↔taskPanel SCC = **存量基线**（main 同样报），本批零新增环；check-js-types 门 = typescript 未安装（main 同样），环境性零增量。
- 测试侧三坑实录（复现细节，与产品代码零关）：T1f 断言笔误 `s1.scale`（s1 已是 number → NaN 恒 FAIL，已修）；pinch 阻尼尾帧需收敛后再快照（`waitGraphSettled`，|scale−zoomTarget|<0.004）；WebKit 偶发 `page.route` 拦截缺口 fetch 泄漏 → 静态服务加 `/api/*` 夹具兜底（同形态应答，泄漏与否都零 404）。

## 边界与申报

- **假设申报**：作者所述「拉手按钮」在图谱页代码中不存在（T1b 实证命中 0）——本批按令的真实意图（去掉显式开关、触摸即拖）落地为：修 touch-action 病根 + 防复活钉，差异如实申报。
- 图片查看器抓手钮（10-30 冷启动批令）语义保留不动（T6d 钉）；触屏双指捏合缩放为本批补齐（此前只有 wheel，作者目标态）。
- 遗留观察（非本批范围）：main 上 6 条存量 spec 失败（wiki-graph-ux R8/§F、wiki-graph-tip-dup-label T1-T4）待归属批处理——本批以 stash bisect/main 复跑双实证与己无关。
