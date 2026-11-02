// viewers/zoom.js — shared Canvas viewer zoom engine (spec: Canvas 查看器独立缩放).
// One engine for all zoomable viewers (pdf.js pages, images, ...):
//   - ctrl/cmd + wheel over the viewer → content zoom (preventDefault, never
//     reaches the browser's own zoom), anchored at the cursor
//   - toolbar: zoom-out / percentage / zoom-in / fit-reset (glass-control)
//   - cmd/ctrl + =/-/0 while hovering the viewer → zoom/reset; outside the
//     viewer the browser's own zoom keys are untouched
//   - Safari pinch: gesturestart/gesturechange intercepted over the viewer
// The engine owns scale state; the viewer supplies applyScale(scale, pivot)
// which must re-layout/re-render at the new scale and honor the pivot (keep
// the content point under the cursor stationary).

import { createIconsIn } from '../utils.js';
import { t } from '../i18n.js';

/** @type {Set<{pane: HTMLElement, zoomBy: Function, reset: Function}>} */
const hovered = new Set();
let keyBound = false;

/** 手机层判据（单点；与 mobile/bootstrap.js 的 matchMedia 激活判据同源）。
 *  手机端图片查看纯手势批（作者令 2026-11-02 23:26）：手机层 image viewer
 *  不渲染控制栏（S1，规格表 §2.2）——缩放全走手势；桌面腿零触碰。 */
const isMobileLayer = () => document.body.classList.contains('mobile-layer');

function bindGlobalKeys() {
  if (keyBound) return;
  keyBound = true;
  document.addEventListener('keydown', (e) => {
    if (!e.metaKey && !e.ctrlKey) return;
    if (e.key !== '=' && e.key !== '+' && e.key !== '-' && e.key !== '0') return;
    if (hovered.size === 0) return; // not over a zoomable viewer → browser zoom proceeds
    const ctl = hovered.values().next().value;
    e.preventDefault();
    if (e.key === '0') ctl.reset();
    else ctl.zoomBy(e.key === '-' ? 1 / 1.25 : 1.25, null);
  });
}

/**
 * @param {HTMLElement} pane — the .canvas-tab-pane (positioning context)
 * @param {object} opts
 *   min, max           — clamp bounds for scale
 *   getScale()         — current scale
 *   applyScale(s, pivot|null) — re-render at scale s; pivot {x,y} = pane-relative cursor
 *   reset()            — back to fit/100% (viewer-defined)
 *   attachTo           — element the wheel/gesture listeners bind to (default pane)
 *   controls           — 'bar'（默认，桌面共用条）| 'mobile-gesture'（手机层：控制栏
 *                        不建 DOM，条随层切换动态挂/拆——视口跨越 768px 时 pane 不重挂，
 *                        必须动态跟层，否则桌面→窄窗残留死条）
 *   gestureOwned       — () => boolean；true 时引擎的 Safari gesturestart/change 让路
 *                        （手机层双指捏合走 pointer 管线，两路同收会双重缩放）
 * @returns {{ setPct: Function, destroy: Function }}
 */
export function enableViewerZoom(pane, opts) {
  const { min = 0.25, max = 5 } = opts;
  const host = opts.attachTo || pane;
  const mobileGesture = opts.controls === 'mobile-gesture';

  const clamp = s => Math.min(max, Math.max(min, s));
  const zoomBy = (factor, pivot) => {
    const next = clamp(opts.getScale() * factor);
    if (next === opts.getScale()) return;
    opts.applyScale(next, pivot || null);
    setPct(next);
  };

  // ── Toolbar ──────────────────────────────────────────────
  // 手机层 image viewer（controls:'mobile-gesture'）：控制栏**零 DOM**（S1）。
  // 视口跨越（桌面→窄窗 / 窄窗→桌面）时 body.mobile-layer 起落而 pane 不重挂，
  // 故挂一枚层观察器动态拆/挂 —— 拆 = 手机层零命中（N2 判据面），挂 = 回桌面
  // 恢复桌面既有交互（N11 回归面）。
  const bar = document.createElement('div');
  bar.className = 'canvas-zoom-bar';
  let barMounted = false;
  let layerMq = null;
  const onLayerFlip = () => {
    if (!mobileGesture) return;
    if (isMobileLayer()) unmountBar();
    else mountBar();
  };
  const mountBar = () => {
    if (barMounted) return; // 幂等；mobileGesture 的到场判断在调用点（构造分支/onLayerFlip）
    barMounted = true;
    mkBarContent();
    pane.appendChild(bar);
    createIconsIn(bar);
    setPct(opts.getScale());
    if (opts.onBarMount) opts.onBarMount(bar); // viewer 专属钮（image 抓手）随挂随注入
  };
  const unmountBar = () => {
    if (!barMounted) return;
    barMounted = false;
    bar.remove();
  };
  const mkBtn = (icon, label, fn) => {
    // 件三统一批（2026-10-28）：.glass-control 实心 chip 面退役 —— 内钮与查找
    // 条内钮同语言（静默 ghost + hover 抬亮），材质/尺寸由 split.css
    // .canvas-zoom-btn 按 --cv-* token 档供给（sapphire.css 单源）。
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'canvas-zoom-btn';
    b.innerHTML = `<i data-lucide="${icon}"></i>`;
    b.title = label;
    b.setAttribute('aria-label', label);
    b.addEventListener('click', fn);
    return b;
  };
  const pct = document.createElement('span');
  pct.className = 'canvas-zoom-pct';
  const setPct = s => { if (barMounted) pct.textContent = Math.round(s * 100) + '%'; };
  const mkBarContent = () => {
    bar.innerHTML = '';
    bar.appendChild(mkBtn('zoom-out', t('canvas.zoomOut'), () => zoomBy(1 / 1.25, null)));
    bar.appendChild(pct);
    bar.appendChild(mkBtn('zoom-in', t('canvas.zoomIn'), () => zoomBy(1.25, null)));
    bar.appendChild(mkBtn('maximize-2', t('canvas.zoomFit'), () => { opts.reset(); setPct(opts.getScale()); }));
  };
  if (mobileGesture) {
    if (typeof window.matchMedia === 'function') {
      layerMq = window.matchMedia('(max-width: 768px)');
      if (layerMq.addEventListener) layerMq.addEventListener('change', onLayerFlip);
      else if (layerMq.addListener) layerMq.addListener(onLayerFlip);
    }
    if (!isMobileLayer()) mountBar(); // 桌面窄窗开场（非手机层）= 桌面既有形态
  } else {
    mountBar();
  }

  // ── Wheel (ctrl/cmd = browser pinch/zoom gesture) ────────
  const onWheel = (e) => {
    if (!e.ctrlKey && !e.metaKey) return; // plain wheel = scroll, untouched
    e.preventDefault();
    const rect = host.getBoundingClientRect();
    zoomBy(Math.pow(1.0015, -e.deltaY), { x: e.clientX - rect.left, y: e.clientY - rect.top });
  };
  host.addEventListener('wheel', onWheel, { passive: false });

  // ── Safari pinch (gesture events) ────────────────────────
  // GestureEvent 不携带坐标，需用 pointermove 持续追踪最近光标位置，
  // gesture 期间以该位置为 pivot 实现 CAD 式跟随缩放。
  let gestureBase = 1;
  let cursorPos = null;
  const onPointerMove = (e) => {
    const rect = host.getBoundingClientRect();
    cursorPos = { x: e.clientX - rect.left, y: e.clientY - rect.top };
  };
  host.addEventListener('pointermove', onPointerMove);

  const onGestureStart = (e) => {
    // 手机层双指捏合由 viewer 的 pointer 管线接管（gestureOwned，规格表 §2.2 S3
    // 单点缩放）：Safari iOS 同一捏合既发 gesturestart 又发 pointer 事件，两路
    // 同收 = 双重缩放。让路不是注销 —— 桌面触控板 pinch（无 pointer 等价物）照旧。
    if (opts.gestureOwned && opts.gestureOwned()) return;
    e.preventDefault();
    gestureBase = opts.getScale();
    if (!cursorPos) {
      const rect = host.getBoundingClientRect();
      cursorPos = { x: rect.width / 2, y: rect.height / 2 };
    }
  };
  const onGestureChange = (e) => {
    if (opts.gestureOwned && opts.gestureOwned()) return;
    e.preventDefault();
    const next = clamp(gestureBase * e.scale);
    opts.applyScale(next, cursorPos);
    setPct(next);
  };
  host.addEventListener('gesturestart', onGestureStart);
  host.addEventListener('gesturechange', onGestureChange);

  // ── Hover tracking for the shared keydown handler ────────
  const ctl = { pane, zoomBy, reset: () => { opts.reset(); setPct(opts.getScale()); } };
  const enter = () => hovered.add(ctl);
  const leave = () => hovered.delete(ctl);
  host.addEventListener('mouseenter', enter);
  host.addEventListener('mouseleave', leave);
  bindGlobalKeys();

  return {
    setPct,
    destroy() {
      host.removeEventListener('wheel', onWheel);
      host.removeEventListener('pointermove', onPointerMove);
      host.removeEventListener('gesturestart', onGestureStart);
      host.removeEventListener('gesturechange', onGestureChange);
      host.removeEventListener('mouseenter', enter);
      host.removeEventListener('mouseleave', leave);
      hovered.delete(ctl);
      if (layerMq) {
        if (layerMq.removeEventListener) layerMq.removeEventListener('change', onLayerFlip);
        else if (layerMq.removeListener) layerMq.removeListener(onLayerFlip);
      }
      unmountBar();
      bar.remove();
    },
  };
}
