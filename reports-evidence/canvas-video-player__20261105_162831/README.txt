canvas-video-player 批（2026-11-05）证据目录
=============================================

作者报障（16:21 逐字）「现在视频在canvas的占比，和样式都很丑，需要优化一下。」
截图原件：author-report-before-161724.jpg（作者上传件归档，非本机重拍）

一、结论先行
------------
原来只占那么小的**根因 = 只有上限没有尺寸**：
改前 `css/split.css:2894` 只有 `video.canvas-video { max-width:100%; max-height:100% }`。
`max-*` 对替换元素只**封顶**、不撑高 —— 没有任何 width/height 声明的 `<video>` 按
**内在尺寸**布局（320×180 的片子就停在 320×180）。大到超过画布的片子（4K/超宽）
由 max-width 封顶，contain 关系恰好成立，所以看起来只有「小视频有病」。
父容器**不是**问题：`.canvas-video-viewer` 是 height:100% 的居中 flex 列（改前读数
avail 686×832 真值在场）。

二、文件清单
------------
author-report-before-161724.jpg   作者实拍（改前现场）
desktop-small-before.png          改前 · 桌面 1440×900 · 320×180（原生控件压在画面上）
desktop-small.png                 改后 · 同视口同夹具（自研控件条在画面外）
desktop-small-dark.png            改后 · 暗档对照
desktop-portrait-before.png/.png  改前/改后 · 竖屏 360×640
desktop-ultra-before.png/.png     改前/改后 · 超宽 1920×480
desktop-uhd-before.png/.png       改前/改后 · 4K 3840×2160
mobile-small-before.png/.png      改前/改后 · 手机 375×812（hasTouch，真 mobile-layer）
readings-before/<档>.json         改前 DOM 读数（元素盒/painted/留白/重叠/控件）
readings-after/<档>.json          改后 DOM 读数（同上 + 控件热区、六态样式快照）
READINGS-COMPARE.md               前后对照表（由两份 readings 机械生成，不手抄）

读数口径：painted = 画面**实际像素**矩形（obtained by contain 反算：由元素盒与
videoWidth/videoHeight 求 object-fit:contain 的落位），与实现无关；占屏比 = 画面 / pane。

三、复现
--------
BEFORE=1 SHOTS_DIR=reports-evidence/<本目录> node tests/canvas-video-stage.spec.mjs
        （改前取证档：只读不判新律）
SHOTS_DIR=reports-evidence/<本目录> node tests/canvas-video-stage.spec.mjs
        （门禁档：41 条断言，改后必绿）
夹具由 ffmpeg 运行时生成（testsrc 四档宽高比），不入 repo。

四、门禁读数
------------
check-flat-buttons                 EXIT 0（563 个按钮面规则块 / 0 违规 / 0 例外）
check-component-contract           EXIT 0（js/viewers/video.js 命中数全部归零）
check-circular                     EXIT 0
check-self-explaining              EXIT 0
check-design-tokens                EXIT 1 · 唯一红 = js/ws.js 颜色字面量 10→12
                                   （main 存量红，本批未触碰 js/ws.js；HEAD 归档树同读数）
check-js-types                     EXIT 2 · 环境缺 node_modules/typescript（非代码问题）
keyboard-check                     101/109；8 条红与 HEAD 归档树逐条同文（存量红）
tests/canvas-video-viewer.spec.mjs 13/13（原生 controls 断言已改为「controls 退役 ∧ 自研条在位」）
tests/canvas-video-stage.spec.mjs  41/41
