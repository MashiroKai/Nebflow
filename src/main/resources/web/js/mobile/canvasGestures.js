// mobile/canvasGestures.js — 画板 bottom sheet 边缘手势（作者令 2026-10-23 ⑦）。
//
// 呈现（mobileLayer.css 同批）：手机画板 = **从下方弹出的卡片（bottom sheet）**
// —— 底缘锚定 + 顶部留聊天头一线，入场自下滑入（mhSheetIn）。
//
// 手势面（⑦ 令口径：内部手势优先，边缘手势才触发收合/关闭）：
//   · 底缘带（面板底部 40px，iOS home-indicator 同位，可见抓手）**下滑跟手**
//     → 释放越阈（|dy|≥120 或快速轻甩）= 收起整叠（closeCanvas —— 文档保留在
//     页签数据模型里，保留状态不销毁；未越阈回弹）。
//   · 左/右缘带（宽 26px，胶囊以下底缘带以上，无视觉件）向内横滑越阈
//     （|dx|≥64）= 关闭该文档卡（navController.closeCard 既有邻卡落位）。
//   · 顶部边缘 = 卡头胶囊（.canvas-header），下滑收起/上滑换卡/左右关卡/
//     长按引用钮 —— 整带归 navController（UI 批⑨ + ⑦ 令改写），本控制器不碰。
//   · 内容区（#canvas-content）**零劫持**：滚动/缩放/横滚/内部手势全部原生
//     （touch-action:pan-y 既有）；旧「内容区横滑切卡」随 ⑦ 令退役（与
//     「左右滑 = 关闭该卡片」语义冲突，切卡保留卡头上滑一条通路）。
//
// 实现取 Pointer Events（触屏/鼠标统一）。手势只绑四条缘带元素
//（touch-action:none），互不劫持，也绝不碰内容区。

import { closeCanvas, isCanvasOpen } from '../canvas.js';
import { activeDocId, closeCard, logEvent, touchLayer } from './navController.js';

const EDGE_ZONE_PX = 40;      // 底缘上滑带高度（iOS home-indicator 同位；高于横向滚动条带）
const SIDE_ZONE_PX = 26;      // 左右缘带宽度（窄带：仅贴边意图触发，不碍内容横滚）
const COLLAPSE_THRESHOLD = 120; // 底缘下滑收起触发位移（px）
const COLLAPSE_VELOCITY = 0.6;  // 快甩收起速度阈值（px/ms）
const MAX_FOLLOW = 260;       // 下滑跟手最大位移（px）
const CARD_CLOSE_PX = 64;     // 侧缘内滑关卡触发位移（px）
const AXIS_LOCK_PX = 8;       // 轴锁（与卡头胶囊/deck 同档）

function canvasRoot() { return document.getElementById('canvas-panel'); }
function slideEl() { return document.getElementById('canvas-slide'); }

function isInteractiveTarget(el) {
  return !!el.closest('button, a, input, textarea, select, label, [contenteditable="true"]');
}

function bindGestures() {
  const root = canvasRoot();
  if (!root) return () => {};

  // 底缘带（iOS home-indicator 同位，可见抓手）：真实元素承载 touch-action:none。
  let edgeZone = root.querySelector('.mobile-canvas-edgezone');
  if (!edgeZone) {
    edgeZone = document.createElement('div');
    edgeZone.className = 'mobile-canvas-edgezone';
    edgeZone.setAttribute('aria-hidden', 'true');
    root.appendChild(edgeZone);
  }
  // 左右缘带（无视觉件：贴边意图带；避免与内容区横滚/缩放争触点）。
  const sideZones = [];
  for (const side of ['left', 'right']) {
    let z = /** @type {HTMLElement|null} */ (root.querySelector(`.mobile-canvas-edgezone-side[data-side="${side}"]`));
    if (!z) {
      z = document.createElement('div');
      z.className = 'mobile-canvas-edgezone-side';
      z.dataset.side = side;
      z.setAttribute('aria-hidden', 'true');
      root.appendChild(z);
    }
    sideZones.push(z);
  }

  /** @type {{id:number, x:number, y:number, t:number, zone:'bottom'|'side-l'|'side-r',
   *          axis:string|null, follow: number}|null} */
  let g = null;

  const zoneOf = (target) => {
    if (target.closest('.mobile-canvas-edgezone')) return 'bottom';
    const side = target.closest('.mobile-canvas-edgezone-side');
    if (side) return side.dataset.side === 'left' ? 'side-l' : 'side-r';
    return '';
  };

  const followSheet = (dx, dy) => {
    const slide = slideEl();
    if (!slide) return;
    slide.style.transition = 'none';
    slide.style.transform = `translate(${dx}px, ${dy}px)`;
    slide.style.willChange = 'transform';
  };

  const resetSlide = () => {
    const slide = slideEl();
    if (slide) {
      slide.style.transition = '';
      slide.style.transform = '';
      slide.style.willChange = '';
    }
  };

  const onDown = (e) => {
    // 卡片层未亮（未开或面板页签激活被页面接管）时手势不劫持。
    if (!document.body.classList.contains('mf-cards-live')) return;
    if (!isCanvasOpen()) return;
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    const zone = zoneOf(/** @type {Element} */ (e.target));
    if (!zone) return; // 内容区/卡头带：内部手势优先，零劫持
    if (isInteractiveTarget(/** @type {Element} */ (e.target))) return;
    touchLayer();
    g = { id: e.pointerId, x: e.clientX, y: e.clientY, t: Date.now(), zone, axis: null, follow: 0 };
    // 显式指针捕获：touch 有隐式捕获（pointerdown 即锁目标），mouse 没有 ——
    // 不捕获则拖出面板后 move/up 不再经过 root，手势流断（桌面窄窗/自动化
    // 鼠标管线同修）。pointerup 时隐式释放。
    try { root.setPointerCapture(e.pointerId); } catch (_) {}
  };

  const onMove = (e) => {
    if (!g || e.pointerId !== g.id) return;
    const dx = e.clientX - g.x;
    const dy = e.clientY - g.y;
    if (!g.axis && (Math.abs(dx) > AXIS_LOCK_PX || Math.abs(dy) > AXIS_LOCK_PX)) {
      g.axis = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y';
    }
    if (g.zone === 'bottom' && g.axis === 'y' && dy > 0) {
      // 下滑跟手：整张 bottom sheet 随手下移（收起方向）。
      g.follow = Math.min(MAX_FOLLOW, dy);
      followSheet(0, g.follow);
    } else if (g.axis === 'x' && (g.zone === 'side-l' || g.zone === 'side-r')) {
      // 向内横滑跟手（ mild；0.6 系数 = 轻跟手，松手即回弹/关卡）。
      const inward = g.zone === 'side-l' ? Math.max(0, dx) : Math.min(0, dx);
      g.follow = inward * 0.6;
      followSheet(g.follow, 0);
    }
  };

  const onUp = (e, cancelled = false) => {
    if (!g || e.pointerId !== g.id) return;
    const dx = e.clientX - g.x;
    const dy = e.clientY - g.y;
    const dt = Math.max(1, Date.now() - g.t);
    const zone = g.zone;
    const axis = g.axis;
    g = null;
    resetSlide();
    if (cancelled && !axis) return;

    // 底缘下滑 → 收起整叠（保留状态；文档可再开）。越阈或快甩皆判收。
    if (zone === 'bottom' && axis === 'y'
        && (dy >= COLLAPSE_THRESHOLD || (dy > AXIS_LOCK_PX && dy / dt > COLLAPSE_VELOCITY))) {
      logEvent('cards-collapse', { via: 'edge-down' });
      closeCanvas();
      return;
    }
    // 左右缘内滑 → 关闭该文档卡（邻卡落位/收层语义在 closeCard 内）。
    if ((zone === 'side-l' || zone === 'side-r') && axis === 'x'
        && Math.abs(dx) >= CARD_CLOSE_PX && (dt < 900 || cancelled)) {
      const inward = zone === 'side-l' ? dx > 0 : dx < 0;
      if (inward && activeDocId()) {
        logEvent('card-close', { via: 'edge-swipe' });
        closeCard();
      }
    }
  };

  root.addEventListener('pointerdown', onDown, true);
  root.addEventListener('pointermove', onMove, true);
  root.addEventListener('pointerup', onUp, true);
  const onCancel = (e) => { onUp(e, true); };
  root.addEventListener('pointercancel', onCancel, true);

  return () => {
    root.removeEventListener('pointerdown', onDown, true);
    root.removeEventListener('pointermove', onMove, true);
    root.removeEventListener('pointerup', onUp, true);
    root.removeEventListener('pointercancel', onCancel, true);
    root.querySelector('.mobile-canvas-edgezone')?.remove();
    for (const z of sideZones) z.remove();
    resetSlide();
  };
}

// ── 激活 / 拆除 ─────────────────────────────────────────────────

export function activateGestures() {
  const unbind = bindGestures();
  return function teardownGestures() {
    unbind();
  };
}
