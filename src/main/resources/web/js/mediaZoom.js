// mediaZoom.js — 媒体查看器（lightbox 画廊：图片 + 视频）缩放/平移的**纯算术与判定表**。
//
// 为什么单独成模块：把「缩放闭包边界 / 锚点公式 / 滚轮与触控板判别 / 既有手势判定」
// 提炼成零 DOM、零 import 的纯函数，才能被 `node --test`
// （scripts/tests/media-zoom.test.mjs，static 闸内）直接断言——浏览器外的红/绿
// 退出码，而不是只靠视觉。
//
// 单一真值：lightbox.js 是唯一消费者。这里不做任何 DOM 读写，不 import 任何模块；
// 视图层（lightbox.js）只负责把元素尺寸/视口尺寸喂进来、把结果写回 style。
//
// ── 坐标系（全模块唯一一套，写下来免得第二次推导）──────────────────────────
// 元素由浮层 flex 居中、transform-origin 保持 CSS 默认 center ⇒ **视口中心**即元素
// 的变换原点。因此所有平移量 (tx,ty) 与锚点 pivot 都以视口中心为原点：
//   屏幕点（中心相对坐标） p = 内容点 · s + t        （内容点 = 以元素中心为原点）
// 锚点不变式：内容点 p 在缩放前后落在同一屏幕位置 ⇒ t' = p − (p − t)·k，k = s'/s。
// 与 js/viewers/image.js 的 applyScale 同式（那边原点取元素左上，因为 img 在视口
// 里绝对定位到 0,0；两边是同一不变量的两种取原点方式，不是两套缩放）。

/** 最小档 = 适应屏幕（contain）。元素已由 CSS 的 max-width/max-height 排成 contain
 *  盒，所以 s=1 且 t=(0,0) 恰好等于「适应屏幕并居中」——同一档也是复位/回弹目标。 */
export const ZOOM_MIN = 1;

/** 最大档 = 适应档 ×5。取值理由：
 *  · 与既有画廊滚轮档的封顶同值（改前 `Math.min(5, …)`）——一套输入设备一个封顶，
 *    不制造两套手感；捏合与滚轮不会各停在一个数上；
 *  · 直立手机上量级对得上：一张 1170×2532 截图放进 390pt 宽屏、适应档 ≈0.31×原始
 *    像素（宽 92% 留白），×5 ≈ **1.5× 原始像素** = 刚越过 1:1，文字像素级可读；
 *  · 再往上单指平移就变成「在屏幕里找针」，不增加可用信息（Canvas 查看器的 8 档
 *    是桌面鼠标语义，鼠标指点不需要手指遮挡，故不共用它的上限）。 */
export const ZOOM_MAX = 5;

/** 越界阻尼 1/3（iOS UIScrollView 量级；与 js/viewers/image.js 的 RUBBER_RESIST 同值
 *  ——两处各自表达同一手感，不互相 import：那边是手机层 Canvas 页签，这边是全屏浮层）。 */
export const RUBBER_RESIST = 3;

/** 适应态横滑切图：位移过阈或快弹过阈（改前 lightbox.js 的隐式阈值逐字搬来）。 */
export const SWITCH_COMMIT_PX = 72;
export const SWITCH_FLING_PX_PER_MS = 0.45;
/** 放大态边界续滑切图：平移到界后继续拖过这个量才切（微信同款手感）。 */
export const EDGE_SWITCH_PX = 72;
/** 适应态下滑关闭：位移过阈或快弹过阈（改前同值）。 */
export const CLOSE_COMMIT_PX = 110;
export const CLOSE_FLING_PX_PER_MS = 0.5;

/** 滚轮缩放的基数与「一格」跨度：一格（120px 等价）= ×1.2；触控板捏合的每帧
 *  deltaY 通常只有 1–10px ⇒ 因子 ≈1.0015/px，连续平滑。一个常数覆盖鼠标滚轮与
 *  触控板捏合两条腿（改前滚轮档 = ×1.2 逐字保留）。 */
export const WHEEL_ZOOM_BASE = 1.2;
export const WHEEL_ZOOM_SPAN = 120;

/** 触控板/鼠标判别：像素模式下 ≥ 这个跨度的孤立纵向事件视为鼠标滚轮一格。 */
export const WHEEL_NOTCH_PX = 50;
/** 触控板会话粘连窗（ms）：窗口内先出现小步/带 deltaX 的事件 = 这一段是触控板，
 *  随后的快弹大步事件仍按触控板判（快甩一下 deltaY 也能上百，不粘连就会误判成缩放）。 */
export const TRACKPAD_SESSION_MS = 250;

/** 双指捏合的最小基距（px）：两指重合时除以 0 的保护（image.js 同值）。 */
export const PINCH_MIN_DIST = 20;

/**
 * @typedef {{s: number, tx: number, ty: number}} View 缩放档 + 平移量（视口中心为原点）
 * @typedef {{dx: number, dy: number, ds: number, opacity: number}} Gesture 跟手位移层
 */

/* ── 档位与钳制 ─────────────────────────────────────────────────────────── */

/**
 * 档位钳制 [ZOOM_MIN, ZOOM_MAX]；非有限值回落最小档（坏输入不得放大成 NaN 变换）。
 * @param {number} s @returns {number}
 */
export function clampZoom(s) {
  if (!Number.isFinite(s)) return ZOOM_MIN;
  return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, s));
}

/**
 * 单轴钳制盒（视口中心为原点：内容半宽 = content/2，视口半宽 = viewport/2）。
 * · content ≥ viewport：本轴可平移，钳到 ±(content−viewport)/2；
 * · content < viewport：本轴装得下 ——
 *     pin=true（触屏腿）钉回居中 0（iOS 相册/微信：小图恒居中，拖不走）；
 *     pin=false（鼠标腿）不设界（既有已交付行为：桌面拖拽不受限，双击/复位可复原）。
 * @param {number} content 内容在屏幕上的边长（layout 边长 × 缩放）
 * @param {number} viewport 视口边长
 * @param {boolean} pin
 * @returns {{min: number, max: number, movable: boolean}}
 */
export function axisBounds(content, viewport, pin) {
  const slack = (content - viewport) / 2;
  // slack === 0（内容恰好等于视口）时写 +0 而不是 -0：读数与断言里不出现「-0」。
  if (slack >= 0) return { min: slack === 0 ? 0 : -slack, max: slack, movable: true };
  return pin ? { min: 0, max: 0, movable: false } : { min: -Infinity, max: Infinity, movable: false };
}

/** 越界阻尼：界内原样，界外按 1/RUBBER_RESIST 衰减（±Infinity 界 = 永不为界外）。 */
export function rubber(v, lo, hi) {
  if (v < lo) return lo + (v - lo) / RUBBER_RESIST;
  if (v > hi) return hi + (v - hi) / RUBBER_RESIST;
  return v;
}

/** 越界量（正数 = 超出 lo/hi 的量；界内 0）。用于「已到边界继续拖 = 切图」判定。 */
export function overrun(v, lo, hi) {
  if (v < lo) return lo - v;
  if (v > hi) return v - hi;
  return 0;
}

/* ── 缩放与锚点 ─────────────────────────────────────────────────────────── */

/**
 * 锚点缩放不变式：pivot 下的内容点缩放前后不动。
 * @param {View} view 当前档
 * @param {number} nextScale 目标档（内部钳制）
 * @param {{x: number, y: number}|null} pivot 锚点（视口中心相对坐标；null = 居中缩放）
 * @returns {View}
 */
export function anchorZoom(view, nextScale, pivot) {
  const s = clampZoom(nextScale);
  const k = view.s > 0 ? s / view.s : 1;
  const px = pivot ? pivot.x : 0;
  const py = pivot ? pivot.y : 0;
  return { s, tx: px - (px - view.tx) * k, ty: py - (py - view.ty) * k };
}

/** 滚轮/捏合的单事件缩放因子（同一条公式覆盖鼠标滚轮一格与触控板捏合每帧）。 */
export function wheelZoomFactor(deltaY) {
  const d = Number.isFinite(deltaY) ? deltaY : 0;
  return Math.pow(WHEEL_ZOOM_BASE, -d / WHEEL_ZOOM_SPAN);
}

/**
 * 双指捏合的目标档：baseS × (dist/baseDist)，钳到 [MIN,MAX]；**小于最小档时按
 * 橡皮筋阻尼**（界内 1:1，越下越粘），松手由调用方回弹到适应屏幕。
 * @param {number} baseS @param {number} baseDist @param {number} dist @returns {number}
 */
export function pinchScale(baseS, baseDist, dist) {
  const d0 = Math.max(PINCH_MIN_DIST, baseDist);
  const raw = baseS * (Math.max(1, dist) / d0);
  if (raw < ZOOM_MIN) return ZOOM_MIN + (raw - ZOOM_MIN) / RUBBER_RESIST;
  return clampZoom(raw);
}

/**
 * 双指捏合的完整变换：锚点 = 两指中点（`baseMid` 处的**内容点**保持在指下），
 * 中点自身的位移合成为平移（两指整体拖 = 平移 + 缩放一步到位）。
 * @param {{baseS: number, baseTx: number, baseTy: number, baseMid: {x: number, y: number}}} base
 *   视口中心相对坐标的捏合基线
 * @param {{baseDist: number}} geom 基线指距
 * @param {{x: number, y: number}} mid 当前两指中点（视口中心相对坐标）
 * @param {number} dist 当前指距
 * @returns {View}
 */
export function pinchView(base, geom, mid, dist) {
  const s = pinchScale(base.baseS, geom.baseDist, dist);
  const k = s / (base.baseS > 0 ? base.baseS : 1);
  return {
    s,
    tx: mid.x - (base.baseMid.x - base.baseTx) * k,
    ty: mid.y - (base.baseMid.y - base.baseTy) * k,
  };
}

/* ── 既有手势判定表（改前隐式阈值 → 可断言的一张表）────────────────────── */

/**
 * 适应态（s=1）单指拖动的结局：横滑切图 / 下滑关闭 / 回弹。
 * 判据与改前 lightbox.js 的既有手势逐字同值（位移阈 + 速度阈 + 组端不上翻）。
 * @param {{dx: number, dy: number, dt: number, atFirst: boolean, atLast: boolean, cancelled: boolean}} d
 * @returns {{kind: 'switch', dir: number}|{kind: 'close'}|{kind: 'spring'}}
 */
export function fitDragOutcome(d) {
  if (d.cancelled) return { kind: 'spring' };
  const dt = Math.max(1, d.dt);
  if (Math.abs(d.dx) > Math.abs(d.dy)) {
    const dir = d.dx < 0 ? 1 : -1;
    const past = Math.abs(d.dx) > SWITCH_COMMIT_PX
      || (Math.abs(d.dx) > 24 && Math.abs(d.dx) / dt > SWITCH_FLING_PX_PER_MS);
    const room = dir > 0 ? !d.atLast : !d.atFirst;
    return past && room ? { kind: 'switch', dir } : { kind: 'spring' };
  }
  const past = d.dy > 0 && (d.dy > CLOSE_COMMIT_PX || (d.dy > 40 && d.dy / dt > CLOSE_FLING_PX_PER_MS));
  return past ? { kind: 'close' } : { kind: 'spring' };
}

/* ── 滚轮 / 触控板判别 ──────────────────────────────────────────────────── */

/**
 * 滚轮事件分类器（有状态：触控板会话粘连）。
 * 判定次序（每条都是「为什么」而不是经验值堆叠）：
 *   1. ctrl/meta ⇒ zoom —— 浏览器把触控板捏合合成为 `wheel{ctrlKey:true}`；鼠标
 *      ctrl+滚轮也走这一腿（同语义：显式缩放）；
 *   2. alt ⇒ ignore（不接管，交给平台）；
 *   3. deltaMode ≠ 0 ⇒ zoom —— 行/页模式只有传统滚轮会产生，触控板恒为像素模式；
 *   4. |deltaX| > 0 ⇒ pan —— 鼠标滚轮不产生横向分量，有横向分量就是双指；
 *   5. 会话粘连窗内 ⇒ pan —— 快甩的 deltaY 也能上百，粘连避免误判成缩放；
 *   6. 孤立的 |deltaY| ≥ WHEEL_NOTCH_PX ⇒ zoom —— 一格滚轮；
 *   7. 其余小步 ⇒ pan（并开启粘连窗）。
 * 反向歧义（残留、如实写在报告里）：孤立的**高分辨率**鼠标滚轮一格（Magic Mouse /
 * 平滑滚轮）若 |deltaY| < 50 且无 deltaX，会被判成平移；纯纵向的双指滚动若一步就
 * 跳 ≥50px 且不在粘连窗内，会被判成缩放。
 * @returns {(e: {deltaX?: number, deltaY?: number, deltaMode?: number, ctrlKey?: boolean,
 *   metaKey?: boolean, altKey?: boolean, shiftKey?: boolean}, now: number) => 'zoom'|'pan'|'ignore'}
 */
export function createWheelClassifier() {
  let trackpadAt = -Infinity;
  return function classify(e, now) {
    const t = Number.isFinite(now) ? /** @type {number} */ (now) : 0;
    if (e.ctrlKey || e.metaKey) return 'zoom';
    if (e.altKey) return 'ignore';
    if (e.shiftKey) { trackpadAt = t; return 'pan'; }
    if ((e.deltaMode || 0) !== 0) { trackpadAt = -Infinity; return 'zoom'; }
    if (Math.abs(e.deltaX || 0) > 0) { trackpadAt = t; return 'pan'; }
    if (t - trackpadAt <= TRACKPAD_SESSION_MS) return 'pan';
    if (Math.abs(e.deltaY || 0) >= WHEEL_NOTCH_PX) return 'zoom';
    trackpadAt = t;
    return 'pan';
  };
}

/** 双指平移的位移量（内容跟手：手指/滚轮往前，内容反向）。Shift+纵滚 = 显式横移。 */
export function wheelPanDelta(e) {
  const dx = Number.isFinite(e.deltaX) ? e.deltaX : 0;
  const dy = Number.isFinite(e.deltaY) ? e.deltaY : 0;
  if (e.shiftKey) return { dx: -dy, dy: 0 };
  return { dx: -dx, dy: -dy };
}

/**
 * 双击放大/还原的目标档：适应 ↔ 双击档（默认 2×，远离两端、双击后仍有捏合余量）。
 * @param {number} s @param {number} dblScale @returns {number}
 */
export function doubleTapTarget(s, dblScale = 2) {
  return clampZoom(s <= ZOOM_MIN * 1.05 ? dblScale : ZOOM_MIN);
}
