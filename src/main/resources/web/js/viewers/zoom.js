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

/** @type {Set<{pane: HTMLElement, zoomBy: Function, reset: Function}>} */
const hovered = new Set();
let keyBound = false;

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
 * @returns {{ setPct: Function, destroy: Function }}
 */
export function enableViewerZoom(pane, opts) {
  const { min = 0.25, max = 5 } = opts;
  const host = opts.attachTo || pane;

  const clamp = s => Math.min(max, Math.max(min, s));
  const zoomBy = (factor, pivot) => {
    const next = clamp(opts.getScale() * factor);
    if (next === opts.getScale()) return;
    opts.applyScale(next, pivot || null);
    setPct(next);
  };

  // ── Toolbar ──────────────────────────────────────────────
  const bar = document.createElement('div');
  bar.className = 'canvas-zoom-bar';
  const mkBtn = (icon, label, fn) => {
    const b = document.createElement('button');
    b.className = 'glass-control canvas-zoom-btn';
    b.innerHTML = `<i data-lucide="${icon}"></i>`;
    b.title = label;
    b.setAttribute('aria-label', label);
    b.addEventListener('click', fn);
    return b;
  };
  const pct = document.createElement('span');
  pct.className = 'canvas-zoom-pct';
  const setPct = s => { pct.textContent = Math.round(s * 100) + '%'; };
  bar.appendChild(mkBtn('zoom-out', 'Zoom out', () => zoomBy(1 / 1.25, null)));
  bar.appendChild(pct);
  bar.appendChild(mkBtn('zoom-in', 'Zoom in', () => zoomBy(1.25, null)));
  bar.appendChild(mkBtn('maximize-2', 'Fit to view', () => { opts.reset(); setPct(opts.getScale()); }));
  pane.appendChild(bar);
  createIconsIn(bar);
  setPct(opts.getScale());

  // ── Wheel (ctrl/cmd = browser pinch/zoom gesture) ────────
  const onWheel = (e) => {
    if (!e.ctrlKey && !e.metaKey) return; // plain wheel = scroll, untouched
    e.preventDefault();
    const rect = host.getBoundingClientRect();
    zoomBy(Math.pow(1.0015, -e.deltaY), { x: e.clientX - rect.left, y: e.clientY - rect.top });
  };
  host.addEventListener('wheel', onWheel, { passive: false });

  // ── Safari pinch (gesture events) ────────────────────────
  let gestureBase = 1;
  const onGestureStart = (e) => { e.preventDefault(); gestureBase = opts.getScale(); };
  const onGestureChange = (e) => {
    e.preventDefault();
    const next = clamp(gestureBase * e.scale);
    opts.applyScale(next, null);
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
      host.removeEventListener('gesturestart', onGestureStart);
      host.removeEventListener('gesturechange', onGestureChange);
      host.removeEventListener('mouseenter', enter);
      host.removeEventListener('mouseleave', leave);
      hovered.delete(ctl);
      bar.remove();
    },
  };
}
