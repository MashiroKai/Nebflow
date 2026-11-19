// media-zoom.test.mjs — 媒体查看器缩放状态机 / 闭包边界 / 既有手势判定表的单测。
//
// 这是「缩放边界与状态机」那条可执行闸的本体：纯函数、零浏览器、零端口，
// 由 `node --test scripts/tests/` 执行（static 闸内的 script-tests 一项）。
// 覆盖：
//   Z1 档位钳制（上下限 + 坏输入）
//   Z2 锚点不变式（缩放前后锚点下的内容点不动；居中缩放的退化面）
//   Z3 单轴闭包边界（可移动轴 ±slack；装得下的轴 pin / 自由两种腿）
//   Z4 橡皮筋与越界量（阻尼、界内零越界、±Infinity 自由轴的恒零越界）
//   Z5 捏合（基线锚点 + 中点跟随合成平移；下越最小档的阻尼与钳制）
//   Z6 适应态单指拖动的既有判定表（横滑切图 / 组端不越 / 下滑关闭 / 快弹 / 取消）
//   Z7 滚轮与触控板判别（ctrl、deltaMode、deltaX、会话粘连、孤立一格、shift）
//   Z8 滚轮缩放因子与双击档
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  ZOOM_MIN, ZOOM_MAX, RUBBER_RESIST, WHEEL_NOTCH_PX, TRACKPAD_SESSION_MS,
  clampZoom, axisBounds, rubber, overrun, anchorZoom, pinchScale, pinchView,
  fitDragOutcome, createWheelClassifier, wheelPanDelta, wheelZoomFactor, doubleTapTarget,
} from '../../src/main/resources/web/js/mediaZoom.js';

const near = (a, b, eps = 1e-9) => assert.ok(Math.abs(a - b) <= eps, `${a} !≈ ${b}`);

test('Z1: zoom clamps to [1, 5] and survives bad input', () => {
  assert.equal(ZOOM_MIN, 1);
  assert.equal(ZOOM_MAX, 5);
  assert.equal(clampZoom(0.2), 1);
  assert.equal(clampZoom(99), 5);
  assert.equal(clampZoom(2.5), 2.5);
  for (const bad of [NaN, Infinity, -Infinity, undefined, null]) assert.equal(clampZoom(bad), 1);
});

test('Z2: anchoring keeps the content point under the pivot stationary', () => {
  // 内容点 = (p − t)/s；锚点不变式 = 缩放后投影回同一屏幕点。
  const view = { s: 1, tx: 0, ty: 0 };
  const pivot = { x: 120, y: -80 };
  const next = anchorZoom(view, 2, pivot);
  near(next.s, 2);
  const content = { x: (pivot.x - view.tx) / view.s, y: (pivot.y - view.ty) / view.s };
  near(content.x * next.s + next.tx, pivot.x);
  near(content.y * next.s + next.ty, pivot.y);
  // 居中缩放（pivot=null）退化为「不动平移」。
  const centered = anchorZoom({ s: 2, tx: 30, ty: 40 }, 4, null);
  near(centered.tx, 30 * 2);
  near(centered.ty, 40 * 2);
  // 目标档超上限时按钳制后的档算，锚点仍成立。
  const capped = anchorZoom(view, 40, pivot);
  assert.equal(capped.s, ZOOM_MAX);
});

test('Z3: per-axis closure bounds', () => {
  // 内容大于视口：可平移，边界 = ±(content−viewport)/2。
  assert.deepEqual(axisBounds(1200, 800, false), { min: -200, max: 200, movable: true });
  assert.deepEqual(axisBounds(1200, 800, true), { min: -200, max: 200, movable: true });
  // 内容等于视口：0 宽可移动窗口（既不住也不越）。
  assert.deepEqual(axisBounds(800, 800, false), { min: 0, max: 0, movable: true });
  // 内容小于视口：触屏腿钉回居中，鼠标腿不设界。
  assert.deepEqual(axisBounds(400, 800, true), { min: 0, max: 0, movable: false });
  const free = axisBounds(400, 800, false);
  assert.equal(free.movable, false);
  assert.equal(free.min, -Infinity);
  assert.equal(free.max, Infinity);
});

test('Z4: rubber band damps only beyond the bounds', () => {
  assert.equal(rubber(50, -100, 100), 50);
  assert.equal(rubber(160, -100, 100), 100 + 60 / RUBBER_RESIST);
  assert.equal(rubber(-160, -100, 100), -100 - 60 / RUBBER_RESIST);
  assert.equal(rubber(1e6, -Infinity, Infinity), 1e6);
  assert.equal(overrun(50, -100, 100), 0);
  assert.equal(overrun(160, -100, 100), 60);
  assert.equal(overrun(-160, -100, 100), 60);
  assert.equal(overrun(1e6, -Infinity, Infinity), 0);
});

test('Z5: pinch anchors at the finger midpoint and follows it', () => {
  // 平移中点、放大两倍：中点下的内容点不动。
  const base = { baseS: 1, baseTx: 0, baseTy: 0, baseMid: { x: 100, y: 0 } };
  const g = { baseDist: 100 };
  const held = pinchView(base, g, { x: 100, y: 0 }, 200);
  near(held.s, 2);
  near(held.tx, 100 - (100 - 0) * 2);
  near(held.ty, 0);
  // 中点从 (100,0) 挪到 (160,40)：平移与缩放合成一步。
  const moved = pinchView(base, g, { x: 160, y: 40 }, 200);
  near(moved.tx, 160 - (100 - 0) * 2);
  near(moved.ty, 40);
  // 上越最大档被钳到 ZOOM_MAX。
  assert.equal(pinchScale(1, 20, 10000), ZOOM_MAX);
  // 下越最小档：阻尼（比 1 小的量与拉力成正比、绝不越到 0 以下）。
  const below = pinchScale(1, 200, 100);
  assert.ok(below < 1 && below > ZOOM_MIN - 1);
  near(below, ZOOM_MIN + (0.5 - ZOOM_MIN) / RUBBER_RESIST);
  // 微小指距不除零。
  assert.ok(Number.isFinite(pinchScale(2, 0, 0)));
});

test('Z6: fit-state drags keep the shipped switch / close gestures', () => {
  const fit = { dt: 300, atFirst: false, atLast: false, cancelled: false };
  // 横滑过阈 ⇒ 切下一张 / 上一张。
  assert.deepEqual(fitDragOutcome({ ...fit, dx: -120, dy: 10 }), { kind: 'switch', dir: 1 });
  assert.deepEqual(fitDragOutcome({ ...fit, dx: 120, dy: -10 }), { kind: 'switch', dir: -1 });
  // 组端：朝组外继续滑 = 回弹，不上翻。
  assert.deepEqual(fitDragOutcome({ ...fit, dx: -120, atLast: true }), { kind: 'spring' });
  assert.deepEqual(fitDragOutcome({ ...fit, dx: 120, atFirst: true }), { kind: 'spring' });
  // 快弹（位移小而速度大）。
  assert.deepEqual(fitDragOutcome({ dt: 40, atFirst: false, atLast: false, cancelled: false, dx: -30, dy: 0 }),
    { kind: 'switch', dir: 1 });
  // 慢而小的位移 = 回弹（纯点按不算拖动，调用方按 DRAG_SLOP 先滤）。
  assert.deepEqual(fitDragOutcome({ ...fit, dx: -30, dy: 0 }), { kind: 'spring' });
  // 下滑关闭（位移阈 / 快弹阈）。
  assert.deepEqual(fitDragOutcome({ ...fit, dx: 5, dy: 160 }), { kind: 'close' });
  assert.deepEqual(fitDragOutcome({ dt: 50, atFirst: false, atLast: false, cancelled: false, dx: 0, dy: 60 }),
    { kind: 'close' });
  assert.deepEqual(fitDragOutcome({ ...fit, dx: 0, dy: 50 }), { kind: 'spring' });
  // 上滑关闭：不成立（关闭只有下向）。
  assert.deepEqual(fitDragOutcome({ ...fit, dx: 0, dy: -200 }), { kind: 'spring' });
  // 取消（pointercancel）= 一律回弹。
  assert.deepEqual(fitDragOutcome({ ...fit, dx: -300, dy: 300, cancelled: true }), { kind: 'spring' });
});

test('Z7: wheel vs trackpad classification', () => {
  const c = createWheelClassifier();
  const ev = (o) => ({ deltaX: 0, deltaY: 0, deltaMode: 0, ctrlKey: false, metaKey: false, altKey: false, shiftKey: false, ...o });
  // ctrl/meta = 触控板捏合或 ctrl+滚轮 ⇒ 缩放。
  assert.equal(c(ev({ deltaY: 3, ctrlKey: true }), 0), 'zoom');
  assert.equal(c(ev({ deltaY: 3, metaKey: true }), 0), 'zoom');
  assert.equal(c(ev({ deltaY: 3, altKey: true }), 0), 'ignore');
  // 行/页模式 = 传统滚轮。
  assert.equal(c(ev({ deltaY: 3, deltaMode: 1 }), 0), 'zoom');
  // 横向分量 = 双指。
  assert.equal(c(ev({ deltaX: 12, deltaY: 4 }), 0), 'pan');
  // 孤立的大跨像素事件 = 滚轮一格。
  assert.equal(c(ev({ deltaY: -120 }), 1000), 'zoom');
  assert.equal(WHEEL_NOTCH_PX, 50);
  // 小步 = 触控板，并开启粘连窗；窗内的快甩大步仍是平移。
  const fresh = createWheelClassifier();
  assert.equal(fresh(ev({ deltaY: 10 }), 0), 'pan');
  assert.equal(fresh(ev({ deltaY: 180 }), 100), 'pan');
  assert.equal(fresh(ev({ deltaY: 180 }), 100 + TRACKPAD_SESSION_MS + 1), 'zoom');
  // shift+滚轮 = 显式横移。
  assert.equal(createWheelClassifier()(ev({ deltaY: 120, shiftKey: true }), 0), 'pan');
});

test('Z8: wheel zoom factor and double-tap target', () => {
  assert.equal(wheelZoomFactor(-120), 1.2);   // 一格 = ×1.2（既有档位逐字保留）
  assert.equal(wheelZoomFactor(120), 1 / 1.2);
  near(wheelZoomFactor(-1), Math.pow(1.2, 1 / 120));
  assert.deepEqual(wheelPanDelta({ deltaX: 6, deltaY: -12 }), { dx: -6, dy: 12 });
  assert.deepEqual(wheelPanDelta({ deltaX: 0, deltaY: 20, shiftKey: true }), { dx: -20, dy: 0 });
  assert.equal(doubleTapTarget(1), 2);
  assert.equal(doubleTapTarget(1.02), 2);
  assert.equal(doubleTapTarget(2), 1);
  assert.equal(doubleTapTarget(5), 1);
});
