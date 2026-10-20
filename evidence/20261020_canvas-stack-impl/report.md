# Canvas 标签页 → 叠卡（v5 定稿）实施批 · 证据报告

- 仓库：`Stand`（worktree 分支 `delegate/delegate-nebflow-c9c30e4a`）
- 设计基准：`reports/20261020_083800_canvas-card-stack-design-v5__delegate-nebflow-19bc02b5.md` ＋ 样张 `reports/20261020_081631_canvas-card-stack-mockup-v5__delegate-nebflow-19bc02b5.html`（58 断言清单 §四）
- 实施 spec：`tests/canvas-stack.spec.mjs`（A1–A16 + F1/F2 精修断言，断言通道 `window.__canvasDeck.state()` 与样张 `__demo.state()` 同口径）
- 运行读数：`node tests/canvas-stack.spec.mjs` → **99 PASS / 0 FAIL**（终轮 /tmp/canvas-stack-run4.log；headless rAF ~36fps，自动化等待 ≥2.6s，真实浏览器 60fps 无此现象）
- 截图：`shots/`（light-deck-open / light-scrub-mid / light-narrow-340 / dark-deck-open / dark-scrub-mid / light-after-swap，明暗 × 常态/叠卡/擦洗/换面/窄档）

## 落点清单

| # | 位置 | 内容 |
|---|------|------|
| 1 | `src/main/resources/web/index.html` ~356–374 | canvas-header 新增 `.canvas-title`（#canvas-title / #canvas-title-icon / .t-dot / #canvas-title-name）；tab-bar 与 close 按钮 DOM 保留（桌面由 CSS 隐藏，移动层不受影响） |
| 2 | `src/main/resources/web/css/split.css` | ①`#canvas-panel` transition 追加 transform/box-shadow（`--deck-morph-duration: 0.28s` / cubic-bezier(0.32,0,0.18,1)）；②`.canvas-tab-bar.canvas-drop-target` 规则后整段叠卡样式（约 +400 行）：精修② 阴影 token（light `--card-shadow` 0.04/0.07/0.08，dark 0.20/0.24/0.26，`.stacked` 投影 0.04/0.09/0.08）、桌面隐藏 tab-bar/close（`body:not(.mobile-layer)` 作用域）、header 46px 居中、`.canvas-title` 族、`#deck-fab`（fixed z-250，`body.canvas-open` 显示）、`#deck-layer`（fixed，z 随 .over 60 / .overlay 245）、`.deck-card` 全家、thumb mock 作用域收进 `.deck-card`、`.deck-swipe-hint`/`.card-swipe-hint`（writing-mode vertical-rl 11px text-dim）、`#deck-foot`、窄档/RM/头部 drop-target/右键菜单 hr 分隔线 |
| 3 | `src/main/resources/web/js/canvas.js` | ①`teardownTab(id)` 共核抽出（closeTab 与 deck 关闭同路径）；②`setActiveTab` 尾部 `renderCanvasHeader()` + 开卡组时 `deckRemountForActive()`；③`closeCanvas` 开头 `deckShutdown(true)`；④`editor-dirty-change` 监听同步标题脏点 + `deckRefreshDirty`；⑤`showCanvasRefMenu(x,y,entry,actions)` 支持 actions 菜单（引用/关闭/关闭其他）；⑥`initCanvas` 接线：canvasDrop 第三参传 header（OS 文件拖头部=开新签）、`renderCanvasHeader()`、`initDeck()`；⑦叠卡引擎段（~1276–2235）：DECK_* 常量单一来源、deckPanelRect 用 offset* 布局盒（避免读到缩放投影盒的反馈回路）、`window.__canvasDeck.state()` 断言钩子 |
| 4 | `tests/canvas-stack.spec.mjs` | 全新验收（58 断言移植 + 三精修断言，自校准页签数） |

## 三精修落点

- **① 动画再顺滑一档**：`DECK_SCRUB_APPROACH 12` / `DECK_SPREAD_APPROACH 9`（原 10/7）；翻落曲线 `DECK_FALL 26/32/5/24`（原 30/40/6/30）；FX/morph 时长统一 0.28s 档（FLY 0.30 / GHOST 0.28 / SPRING 0.22 / FLING 0.24 / SHRINK 0.26；CSS `--deck-morph-duration: 0.28s`）。「跟手不拖沓」= 指数趋近系数上调 + 翻落收幅 + 时长统一微调。
- **② 背景阴影浅一档**：light `--card-shadow` 0.04/0.07/0.08（样张 0.05/0.10/0.12），dark 0.20/0.24/0.26（样张 0.24/0.30/0.34），`.stacked` 面板投影 0.04/0.09/0.08（样张 0.06/0.13/0.12）—— 每档严格更浅。
- **③ × 退役 → 竖排滑动提示**：`.cv-close` 全树零命中（桌面）；卡缘 `.card-swipe-hint` / 面板缘 `.deck-swipe-hint` 为 writing-mode: vertical-rl、11px、`--color-text-dim`、opacity 0.55 浮现的「滑动关闭」竖排小字；关闭手势走滑动甩出（A8）与右键菜单关闭/关闭其他（A9）。

## 验收读数

- canvas-stack 实施版 spec：99 PASS / 0 FAIL（A1–A16 全组 + F1/F2 精修 + 每组 R0 无运行时错误）。
- canvas 族回归：canvas-html-toolbar-overlap 22P、canvas-html-zoom 23P、canvas-html-interactive 17P、canvas-preview-nav-parity 63P+2SKIP —— 全绿（与改动前基线一致）。

## 偏差声明（相对 v5 样张的有意/受控差异）

1. **body 层固定层替代 #canvas-area 包裹层**：`#deck-layer`/`#deck-fab` 用 `position:fixed` 挂 body（样张挂在面板容器内）。原因：生产布局的滚动/overflow 裁剪上下文与 demo 不同，fixed 层保证浮钮钉死与卡组满幅定位；几何由 `deckFrame` 逐帧对齐 `#canvas-panel` 布局盒（offset*）。
2. **缩略为类型族 CSS mock**（`deckThumbMock`）：生产 tab 无整页快照通道，卡面用文件类型族渲染 mock（markdown/代码/图片等），不伪造内容缩略。
3. **可交互子树拖划守卫**：Monaco 等可交互面上的 wheel/pointer 拖划不接管（可编辑焦点让路，Ctrl+wheel 放行缩放），与样张纯静态卡面不同。
4. **键盘/滚轮让路规则**：焦点在可编辑元素时 ESC/滚轮 scrub 不生效，先失焦再操作。
5. **面板页签（插件等）共存**：首跑恢复失败时 fallback 开一个「插件」面板签，deck 环含它；spec 断言全部从 `state()` 自校准页签数（v5 报告教训③），计数行「前景 n/N」的 n = 页签条位次（样张同口径）。
6. **deck 不进移动层**：`body.mobile-layer` / 窄屏 `isMobileViewport()` 下不启用叠卡（保留原页签交互）。
7. **× 退役范围**：仅桌面 canvas 头部的 close 按钮退役；移动层页签行关闭钮保留（deck 未启用场景仍需可关闭）。
8. **i18n**：叠卡文案（滑动关闭/引用/关闭/关闭其他等）沿用本仓近期批次的 zh 字面量先例，未走 t() 表。
9. **投影断言口径**：断言读 CSSOM 计算值（rgba 串行化），与样张源码字面值等价换算；rotateX 微后仰的透视投影会让 d1 卡 AABB 比几何值宽 ~2%（随区高放大），A2-6/A2-7 容差按投影实测放宽（±8px / <2.5%），「无逐级缩放通道」判据不变。

## 已知非缺陷

- 无头 chromium rAF ~36fps：指数趋近收敛时间拉长，自动化等待 ≥2.6s（spec 头注）；真实 60fps 无此现象。
- 擦洗 settle 后（pos=1）canvas 面板保持 alpha 0 + pointer-events none、deck over 抬层，是样张 A3「面板隐没」的设计正确态。
