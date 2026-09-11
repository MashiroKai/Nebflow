// viewers/image.js — Image viewer (/api/nf-file) with pan & zoom.
// Zoom: transform scale on the <img> (layout-free), wheel/keys/toolbar via the
// shared viewers/zoom.js engine; pan via pointer drag.

import { escapeHtml, formatSize } from './shared.js';
import { ticketUrl, reMint } from '../nfTicket.js';
import { enableViewerZoom } from './zoom.js';

/** Image viewer — <img> served via /api/nf-file, pannable + zoomable.
 *
 *  2026-09-11 (C batch): async — the URL is minted per open (T1: never early),
 *  and carries a path-bound ticket instead of the global token. A load failure
 *  gets exactly ONE re-mint + retry before the error panel (T3 self-healing);
 *  a second failure is treated as deterministic and is NOT retried again. */
async function viewImage(pane, { absPath, fileName, size }) {
  if (!absPath) {
    pane.innerHTML = '<div class="canvas-error">Cannot display image: no absolute path available.</div>';
    return;
  }
  const img = new Image();
  img.alt = fileName || 'image';
  // Prevent native image drag. <img> is draggable by default — an accidental
  // drag on the image starts a native DnD session, and a wedged session
  // (e.g. drop outside the window, Safari quirks) suppresses click events
  // page-wide, which presents as "canvas tabs stop responding".
  img.draggable = false;
  let retried = false;
  img.onerror = () => {
    if (!retried) {
      // Ticket failures (401 missing, 403 expired) self-heal once; a
      // deterministic failure (400/403 credential-path/404) simply fails the
      // same way again and falls through to the panel below (T4).
      retried = true;
      reMint(absPath).then((next) => {
        if (pane.isConnected) img.src = next;
      });
      return;
    }
    let reason = 'File may be corrupted or not a valid image format.';
    if (size && size < 500) reason += ` (File is only ${size} bytes — likely an error page or placeholder, not a real image.)`;
    pane.innerHTML = `<div class="canvas-error">${reason}<br>Path: ${escapeHtml(absPath)}</div>`;
  };
  img.onload = () => {
    if (!pane.isConnected) return;  // tab closed while the image was loading
    pane.innerHTML = '';
    pane.classList.remove('scrollable'); // zoom/pan viewport manages its own overflow

    const wrap = document.createElement('div');
    wrap.className = 'canvas-image-viewer';
    const info = document.createElement('div');
    info.className = 'image-info';
    info.textContent = `${fileName || ''} — ${formatSize(size || 0)}`;
    wrap.appendChild(info);

    const viewport = document.createElement('div');
    viewport.className = 'canvas-image-viewport';
    viewport.appendChild(img);
    wrap.appendChild(viewport);
    pane.appendChild(wrap);

    // ── Transform state: img at translate(tx,ty) scale(s), origin 0 0 ──
    let s = 1, tx = 0, ty = 0;
    const iw = img.naturalWidth || 1, ih = img.naturalHeight || 1;

    const apply = () => { img.style.transform = `translate(${tx}px, ${ty}px) scale(${s})`; };

    const fit = () => {
      const vw = viewport.clientWidth || 1, vh = viewport.clientHeight || 1;
      s = Math.min(vw / iw, vh / ih, 1);
      tx = (vw - iw * s) / 2;
      ty = (vh - ih * s) / 2;
      apply();
    };
    fit();

    const applyScale = (next, pivot) => {
      const old = s;
      s = next;
      if (pivot) {
        // Keep the content point under the cursor stationary.
        const k = s / old;
        tx = pivot.x - (pivot.x - tx) * k;
        ty = pivot.y - (pivot.y - ty) * k;
      }
      apply();
    };

    enableViewerZoom(pane, {
      min: 0.05, max: 8,
      getScale: () => s,
      applyScale,
      reset: fit,
      attachTo: viewport,
    });

    // ── Pan (pointer drag) ─────────────────────────────────
    let drag = null;
    viewport.addEventListener('pointerdown', (e) => {
      if (e.button !== 0) return;
      drag = { x: e.clientX, y: e.clientY, tx, ty };
      viewport.setPointerCapture(e.pointerId);
      viewport.classList.add('panning');
    });
    viewport.addEventListener('pointermove', (e) => {
      if (!drag) return;
      tx = drag.tx + (e.clientX - drag.x);
      ty = drag.ty + (e.clientY - drag.y);
      apply();
    });
    const endDrag = () => { drag = null; viewport.classList.remove('panning'); };
    viewport.addEventListener('pointerup', endDrag);
    viewport.addEventListener('pointercancel', endDrag);
    // Double-click: toggle fit ↔ 100% (cursor-anchored)
    viewport.addEventListener('dblclick', (e) => {
      const rect = viewport.getBoundingClientRect();
      const pivot = { x: e.clientX - rect.left, y: e.clientY - rect.top };
      if (Math.abs(s - 1) < 0.01) fit();
      else applyScale(1, pivot);
    });
  };
  img.src = await ticketUrl(absPath);
}

export default {
  name: 'image',
  label: 'Image',
  extensions: ['.png', '.jpg', '.jpeg', '.gif', '.svg', '.webp', '.bmp', '.ico', '.avif', '.tiff', '.tif'],
  binary: true,
  priority: 0,
  render: viewImage,
};
