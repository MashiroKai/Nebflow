// mobile/canvasGestures.js — 画板 bottom sheet 手势（作者令 2026-10-23 ⑦ →
// 2026-10-27「手机端 Canvas 交互优化」令改写：横滑语义统一 = 切卡）。
//
// 呈现（mobileLayer.css 同批）：手机画板 = **从下方弹出的卡片（bottom sheet）**
// —— 底缘锚定 + 顶部留聊天头一线，入场自下滑入（mhSheetIn）。
//
// 手势面（2026-10-27 令口径：横滑=切上一/下一张、下滑=收起整叠、关卡=显性 X）：
//   · 底缘带（面板底部 40px，iOS home-indicator 同位，可见抓手）**下滑跟手**
//     → 释放越阈（|dy|≥120 或快甩）= 收起整叠（closeCanvas —— 文档保留在
//     页签数据模型里，保留状态不销毁；未越阈回弹）。
//   · **内容区横滑 = 切上一/下一张文档卡**（左滑下一张/右滑上一张，环绕；轴锁
//     10px、阈 56px 或快甩 0.5px/ms；跟手轻位移 + 邻卡名边缘预览 chip）。取代
//     ⑦ 令的「左右缘带内滑关卡」—— 破坏性动作（关卡）收敛到卡头显性 X 钮，
//     横滑语义让给非破坏性的切卡（26px 隐形侧缘带整腿退役）。
//   · 顶部边缘 = 卡头胶囊（.canvas-header），下滑收起/横滑切卡/长按引用钮
//     —— 整带归 navController（同批改写），本控制器不碰。
//   · 内容区纵滚零劫持：touch-action:pan-y 归浏览器；内部横向滚动器（代码块
//     等真横滚元素）起步即让位原生横滚（命中检测 scrollWidth>clientWidth）。
//
// 实现取 Pointer Events（触屏/鼠标统一）。跟手位移走 #canvas-slide transform
// （与底缘收起同一位移通道），松手回弹。

import { closeCanvas, isCanvasOpen } from '../canvas.js';
import { activeDocId, logEvent, switchDocCard, touchLayer } from './navController.js';

const EDGE_ZONE_PX = 40;      // 底缘下滑带高度（iOS home-indicator 同位；高于横向滚动条带）
const COLLAPSE_THRESHOLD = 120; // 底缘下滑收起触发位移（px）
const COLLAPSE_VELOCITY = 0.6;  // 快甩收起速度阈值（px/ms）
const MAX_FOLLOW = 260;       // 下滑跟手最大位移（px）
const AXIS_LOCK_PX = 10;      // 轴锁（横滑切卡/纵滚分区；卡头带 8px 同族档）
const SWITCH_THRESHOLD = 56;  // 横滑切卡触发位移（px）
const SWITCH_VELOCITY = 0.5;  // 快甩切卡速度阈值（px/ms）
const SWIPE_FOLLOW_CAP = 48;  // 横滑跟手位移上限（轻跟手，0.35 系数）

function canvasRoot() { return document.getElementById('canvas-panel'); }
function slideEl() { return document.getElementById('canvas-slide'); }

function isInteractiveTarget(el) {
  return !!el.closest('button, a, input, textarea, select, label, [contenteditable="true"]');
}

/** 起步点若落在真横向滚动器内（overflow-x 可滚）→ 原生横滚优先，手势让位。 */
function insideHScroller(el, root) {
  for (let n = el; n && n !== root; n = n.parentElement) {
    const s = /** @type {HTMLElement} */ (n).style;
    if (s && (s.overflowX === 'auto' || s.overflowX === 'scroll')) return true;
    const c = getComputedStyle(n);
    if ((c.overflowX === 'auto' || c.overflowX === 'scroll')
        && n.scrollWidth > n.clientWidth + 1) return true;
  }
  return false;
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
  // 边缘预览 chip（横滑切卡时的邻卡名预告；左右各一，按滑动向点亮）。
  let peekL = /** @type {HTMLElement|null} */ (root.querySelector('.mobile-card-peek[data-side="left"]'));
  let peekR = /** @type {HTMLElement|null} */ (root.querySelector('.mobile-card-peek[data-side="right"]'));
  if (!peekL) {
    peekL = document.createElement('div');
    peekL.className = 'mobile-card-peek';
    peekL.dataset.side = 'left';
    peekL.setAttribute('aria-hidden', 'true');
    root.appendChild(peekL);
  }
  if (!peekR) {
    peekR = document.createElement('div');
    peekR.className = 'mobile-card-peek';
    peekR.dataset.side = 'right';
    peekR.setAttribute('aria-hidden', 'true');
    root.appendChild(peekR);
  }
  const peekShow = (side, text) => {
    const el = side === 'left' ? peekL : peekR;
    const other = side === 'left' ? peekR : peekL;
    other.classList.remove('show');
    if (!text) { el.classList.remove('show'); return; }
    el.textContent = text;
    el.classList.add('show');
  };
  const peekClear = () => { peekL.classList.remove('show'); peekR.classList.remove('show'); };

  /** @type {{id:number, x:number, y:number, t:number, zone:'bottom'|'content',
   *          axis:string|null, follow: number}|null} */
  let g = null;

  const zoneOf = (target) => {
    if (target.closest('.mobile-canvas-edgezone')) return 'bottom';
    if (target.closest('.canvas-header')) return '';      // 卡头带归 navController
    if (target.closest('.mobile-card-switchbar')) return ''; // 切换条自管（钮群）
    // ask dock 表面归属制（canvas ask mode 批 2026-10-28，#988 同律）：气泡列/
    // 输入条/入口钮表面横滑零切卡、纵滚走列内 pan-y 原生滚动——手势零劫持。
    if (target.closest('.ask-dock, .ask-fab')) return '';
    return 'content';
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

  /** 邻卡名（切换预览用）；单卡返回 null。 */
  const neighborLabel = (dir) => {
    const id = activeDocId();
    if (!id) return null;
    const nb = /** @type {any} */ (switchDocCard(dir, true));
    return nb && nb.label ? nb.label : null;
  };

  const onDown = (e) => {
    // 卡片层未亮（未开或面板页签激活被页面接管）时手势不劫持。
    if (!document.body.classList.contains('mf-cards-live')) return;
    if (!isCanvasOpen()) return;
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    const target = /** @type {Element} */ (e.target);
    const zone = zoneOf(target);
    if (!zone) return; // 卡头/切换条：内部手势优先，零劫持
    if (isInteractiveTarget(target)) return;
    if (zone === 'content' && insideHScroller(target, root)) return; // 真横滚器优先
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
    } else if (g.zone === 'content' && g.axis === 'x') {
      // 横滑跟手（轻，0.35 系数封顶 48px）+ 邻卡名边缘预览。
      g.follow = Math.max(-SWIPE_FOLLOW_CAP, Math.min(SWIPE_FOLLOW_CAP, dx * 0.35));
      followSheet(g.follow, 0);
      const dir = dx < 0 ? 1 : -1; // 左滑=下一张、右滑=上一张
      const nb = neighborLabel(dir);
      peekShow(dir === 1 ? 'right' : 'left', nb ? (dir === 1 ? '下一张 · ' : '上一张 · ') + nb : null);
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
    peekClear();
    if (cancelled && !axis) return;

    // 底缘下滑 → 收起整叠（保留状态；文档可再开）。越阈或快甩皆判收。
    if (zone === 'bottom' && axis === 'y'
        && (dy >= COLLAPSE_THRESHOLD || (dy > AXIS_LOCK_PX && dy / dt > COLLAPSE_VELOCITY))) {
      logEvent('cards-collapse', { via: 'edge-down' });
      closeCanvas();
      return;
    }
    // 内容区横滑 → 切上一/下一张（非破坏性；关卡只走卡头 X）。越阈或快甩。
    if (zone === 'content' && axis === 'x' && activeDocId()
        && (Math.abs(dx) >= SWITCH_THRESHOLD
            || (Math.abs(dx) > AXIS_LOCK_PX && Math.abs(dx) / dt > SWITCH_VELOCITY))) {
      const dir = dx < 0 ? 1 : -1; // 左滑下一张、右滑上一张（环绕）
      logEvent('card-switch', { via: 'swipe', dir });
      switchDocCard(dir);
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
    root.querySelector('.mobile-card-peek[data-side="left"]')?.remove();
    root.querySelector('.mobile-card-peek[data-side="right"]')?.remove();
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
