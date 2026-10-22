# 图片叠卡触控板横滑切换批 2026-10-22（作者令，带截图证据）

作者原话（逐字）：「你发的图片叠卡和我发的图片叠卡，除了长按左右拖动来切换，
mac的双指 左右滑动也要支持。」

「图片叠卡」= 聊天气泡里的 WeChat 式媒体叠卡（前景卡 + 右侧偷看层 + 展开钮），
**双侧同一 builder**：LLM Pop 产物（`buildPopArtifactRow`）与用户附件
（`buildUserMediaRow`，「你发的和我发的」）都走 `popArtifacts.js` 的
`buildMediaStack`——改一处，两侧同得。

## 实现（`src/main/resources/web/js/popArtifacts.js`，+49 行，零新 import）

叠卡盒（`.pop-stack-view`）新增 wheel 监听（`passive: false`），手法同
**图谱胶囊环批**（`canvas.js initCapsuleRing` 环上滚轮）——deltaX 累积 + 阈值
步进 + 动量抑制，交互语言全站一致：

- **横滑累积 + 阈值**：`deltaX` 累积过 **56px**（= `RING_WHEEL_STEP` 同档，两指
  一次横扫的量级）翻一页；`deltaMode` 1/2 归一化成 px（同叠卡引擎滚轮）。
- **方向语义与长按拖动一致**：mac 自然滚动双指向左（= 把卡向左翻）`deltaX>0`
  ⇒ 下一张；Shift+纵滚 = 显式横意（环同款仲裁 `|dX|≥|dY| ? dX : shiftKey ? dY : 0`）。
- **动量抑制**：翻页后进入抑制窗，wheel 事件流静默 **≥160ms** 才重新武装——
  惯性滚动尾巴不再重复翻页（一次动量窗口只翻一页）；连续慢滚按累积量翻页。
- **微 deltaX 抖动过滤**：竖向主导的 wheel **不劫持不 preventDefault**（叠卡嵌在
  聊天流里，双指上下滚继续滚聊天列），同时清横滑累积——竖滚斜漂噪声攒不出误翻。
- **与长按拖动共存**：wheel 无按下态、拖动是 pointer 序列，判定窗口天然分开；
  拖划进行中（`sx` 非 null）wheel 直接让路。移动层触摸滑动（`touch-action: pan-y`
  + 既有 pointer 拖划）不动；亮暗无关（零 CSS 改动）。

## 证据（shots/，`node tests/pop-media-swipe-shots.mjs`，12/12 PASS）

真输入实拍：Playwright `mouseWheel` 在叠卡盒心派发双指横扫（= mac 触控板
wheel `deltaX` 流；`deviceScaleFactor` 恒 1——DSF≠1 时 Playwright 按设备比
缩放 wheel delta，会让累积量悄悄减半）。亮暗 × 双侧：

- `1-pop-<dark|light>-before.png` / `2-pop-<dark|light>-after-swipe.png` ——
  LLM Pop 叠卡：横扫前前景 `shot-1.png` → 横扫+惯性尾巴后前景 `shot-2.png`
  （恰翻一页，尾巴未重复翻）。
- `3-user-<dark|light>-before.png` / `4-user-<dark|light>-after-swipe.png` ——
  用户附件叠卡：`u1.png → u2.png`（同款手势）。
- stdout 断言：横扫翻一页且惯性尾巴不重复；纵滚不翻页且聊天列原生滚动
  （`scrollTop 0 → 400`）；双侧初始前景钉真。

## spec（`tests/pop-media-trackpad-swipe.spec.mjs`，7/7 PASS）

真 wheel 事件流（`page.mouse.wheel`）逐条钉契约：

- **T1** 横扫=下一张/反扫=上一张/环序回绕（Pop 叠卡）；
- **T2** 阈值防误触（40px 微 deltaX 不翻，过阈恰翻一页）；
- **T3** 惯性尾巴不重复翻页 + 静默 160ms 后重新武装；
- **T4** 纵滚放行：聊天列实滚、叠卡不动（含斜漂微 deltaX 噪声）；
- **T5** 与长按拖动共存：wheel 后拖动照常、拖划进行中 wheel 让路；
- **T6** 用户侧叠卡同 builder 同款（u1↔u2 回绕）；
- **T7** Shift+纵滚 = 横意翻页。

## 回归（全绿）

- 邻族：`pop-media-alignment` + `pop-media-index` + `pop-artifacts` +
  `attach-bubble-unify` = **60 passed**（`pop-artifacts` P7 在干净 HEAD 树上
  同败 = 既有 flake，与本批无关，本批未触碰其计时面）。
- checkJs：**257 = 基线 257**。
- check-circular：与干净 HEAD 输出逐字节同（既有 SCC[3]
  popArtifacts/reference/msgQuote 原样；本批零新增 import，不入环）。
- `npm run build`：**179 sources 全量入图（orphans: 0）**。

## 明确不做

- 展开态（平铺列）无叠卡可翻，不挂 wheel；单张平铺面同理。
- Canvas 叠卡引擎（deck）滚轮已是纵滚擦洗换卡，不在本批范围。
- 移动层触摸滑动既有通路不动。
