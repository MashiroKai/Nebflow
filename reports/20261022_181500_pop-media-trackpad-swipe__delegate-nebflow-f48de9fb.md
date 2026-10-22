# 图片叠卡触控板横滑切换批

作者令（2026-10-22，逐字）：「你发的图片叠卡和我发的图片叠卡，除了长按左右拖动
来切换，mac的双指 左右滑动也要支持。」

## 结论

已交付：聊天气泡媒体叠卡（LLM Pop 产物 + 用户附件，同一 builder `buildMediaStack`）
新增 **mac 触控板双指左右滑动翻页**（wheel `deltaX` 横滑累积判别方向，56px 阈值
防误触，160ms 动量窗抑制惯性尾巴重复翻页），与长按左右拖动共存不互斥；纵滚不
劫持聊天列。实现 + spec（7/7）+ 亮暗×双侧截图证据（12/12）一次收绿。

## 实现要点

- 代码：`src/main/resources/web/js/popArtifacts.js` `buildMediaStack`（+49 行，
  零新 import，零 CSS）——叠卡盒挂 `wheel`（`passive:false`）：
  - 环同款仲裁 `|dX|≥|dY| ? dX : (shiftKey ? dY : 0)`；`deltaMode` 1/2 归一化
    成 px（同 deck 滚轮）；
  - 累积过 `POP_SWIPE_STEP_PX = 56`（= canvas.js `RING_WHEEL_STEP` 同档）翻一页，
    方向语义与拖动一致（自然滚动双指向左 `deltaX>0` ⇒ 下一张）；
  - 翻页后抑制：事件流静默 ≥ `POP_SWIPE_QUIET_MS = 160` 才重新武装——一次动量
    窗口只翻一页，惯性尾巴不重复；连续慢滚按累积量翻页；
  - 竖向主导不劫持（原生滚动照旧）+ 清累积（微 deltaX 抖动过滤）；
  - 拖划进行中（`sx !== null`）wheel 让路——两手势判定窗口分开（触控板滑动无
    按下态 / pointerdown 长按才是拖动路）。
- 为什么改这一个点：双侧叠卡同 builder（`buildPopArtifactRow` 与
  `buildUserMediaRow` 都调 `buildMediaStack`）——「你发的和我发的」一处同得；
  历史恢复/原位补丁三路同 builder，无第二真相源。

## 验证

- `tests/pop-media-trackpad-swipe.spec.mjs` **7/7 PASS**（真 `page.mouse.wheel`
  事件流）：T1 翻页/反扫/回绕 · T2 阈值防误触 · T3 惯性不重复+静默再武装 ·
  T4 纵滚放行聊天列实滚 · T5 与拖动共存 · T6 用户侧同款 · T7 Shift+纵滚。
- `tests/pop-media-swipe-shots.mjs` **12/12 PASS** + 8 张实拍
  （`evidence/20261022_pop-media-trackpad-swipe__delegate-nebflow-f48de9fb/shots/`）：
  亮暗 × (Pop 叠卡 shot-1→shot-2 / 用户叠卡 u1→u2) 翻页前后对照。
- 邻族：`pop-media-alignment` + `pop-media-index` + `pop-artifacts` +
  `attach-bubble-unify` 60 passed（`pop-artifacts` P7 干净 HEAD 同败，既有
  flake，与本批无关）。
- checkJs 257 = 基线；check-circular 与干净 HEAD 逐字节同（既有 SCC[3] 原样）；
  build-web 179 sources orphans 0。

## 已知边界 / 未做

- 展开态平铺列与单张平铺面无可翻叠卡，不挂 wheel；Canvas deck 滚轮（纵滚擦洗）
  不在范围；移动层触摸滑动不动。
- Playwright 探针事实：`deviceScaleFactor ≠ 1` 时 wheel delta 按设备比缩放
  （shots 脚本恒 DSF 1）；harness `#popup-chat` 装饰容器与栏右缘重叠会吞右缘
  命中（真实 app 在屏外 getHiddenRoot，T6/用户腿按真实形态关其命中）。
