# canvas-stack 竖拖换卡（作者补充令）取证

日期：2026-10-20 · 分支：delegate/delegate-nebflow-c9c30e4a（叠卡落地 b5d507737 之上的追加批）

## 令文

叠卡交互加一条：**上下拖动手势也要能切换卡片**（不只鼠标滚轮）。①Pointer Events 竖向拖动=scrub
驱动（pointerdown+move 的 dy 增量喂给既有环形 scrub 引擎，跟手连续，松手可带少量惯性；
触屏/触控板/鼠标拖动统一走 Pointer Events）；②与「左右滑动关闭」按主导轴分工：横向主导=滑动
关闭（原逻辑），纵向主导=scrub 切卡；③既有让路规则保持（Monaco 等可交互子树内手势让路）；
④spec 补断言：竖拖换卡/横滑关闭/主导轴判定三例。

## 变更面

- `src/main/resources/web/js/canvas.js`
  - 拖划手势公共腿（面板面 deck.drag 与环卡面 deck.cardDrag 共用）：`deckDragState` /
    `deckDragTrack`（死区→主导轴一次锁→横纵对称 EMA 速度）/ `deckDragFeedVertical`
    （dy 增量喂给，防回滚段倒灌）/ `deckScrubFeed`（与滚轮同引擎同钳位 pos±M）/
    `deckScrubRelease`（EMA 速度 ×90px 冲程、钳 ±80px=±半卡，×增益换算成卡后并入，
    Math.round 贴靠最近卡——拖动不停半卡交叉态，滚轮保持连续不贴靠）。
  - 主导轴判定：|dx|>|dy| → 横=划走关闭（原 v4⑥/v2⑥ 逻辑逐字保持）；否则纵=scrub 换卡；
    对角等幅平局从轻取纵（scrub 非破坏性）。阈值 8px（DECK_AXIS_LOCK_PX），死区内不锁轴、
    未锁轴松手=点击语义保持（点 canvas=收回卡组 / 点前卡=换入）。
  - M<2（单卡）纵锁降级死轴静默——不做破坏性降级去触发关闭。
  - 触屏通路：deck 态面板 `touchstart` 非交互子树 `preventDefault`（见下缺陷 2）；
    `.deck-card` 补 `touch-action:none`（split.css，卡面纯 chrome）。
  - `__canvasDeck.state()` 断言通道：新增 `gesture {face, axis, dx, dy}` 与
    `consts.dragGain/dragInertia/dragInertiaMax/axisLockPx`；提示行/浮钮 title 纳入「上下拖动换卡」。
- `tests/canvas-stack.spec.mjs`：A17（面板面竖拖换卡 + CDP touch 触屏腿）、A18（环卡面
  竖拖换卡 / 横滑关闭分工）、A19（主导轴判定 + 死区）共 17 断言。

## 首跑缺陷与修复（本批自证）

1. **惯性单位错误**：vy(px/ms)×90 冲程（px）直接并入 target（单位=卡）→ 落点 11.426 而非 2
   （11.426 = round(2) + 9.426；9.426px = 0.105px/ms × 90，EMA 在 40ms 步距下未收敛的真实量级）。
   修复：并入前 ×DECK_DRAG_GAIN（换算后钳 ±80px = ±半卡），落点在任何甩手速度下确定
   （1.625+〔0~0.5〕→ round=2）。
2. **触屏 pointercancel**：触点落在 pane 滚动容器内部，Chromium 取 touch-action 以滚动容器为界
   （临时探针实证：触点链 ta=auto，.stacked 的 touch-action:none 够不着）→ 首个 pointermove 后
   即被原生滚动接管（pointercancel，target 停在 0.163，松手收尾通路未走）。修复：deck 态面板
   touchstart 非交互子树 preventDefault（原生手势不启动；交互子树让路规则触屏层同享；tap 收回
   仍走 pointerup 通路）。探针复跑：10/10 pointermove 全通、落点 2。（诊断探针用后即删。）

## 结果

`node tests/canvas-stack.spec.mjs` —— **123 PASS / 0 FAIL**（本批新增 A17×9 + A18×6 + A19×8
中的 17 条新断言全绿；既有 A1–A16 / F1 / F2 无回归；各节 R0 主文档零 JS 运行时错误）。

shots/：本绿跑截图（明暗/窄档/中途态/换面后，与既有批同套口径重摄）。
