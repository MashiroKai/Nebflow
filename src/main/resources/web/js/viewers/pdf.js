// viewers/pdf.js — PDF viewer rendered with vendored pdf.js (zoomable).
// Replaces the old <iframe src=/api/nf-file> native-plugin approach: the
// browser's built-in PDF plugin swallows/ignores zoom gestures per-browser and
// offers no scriptable zoom API, so Canvas zoom acceptance (ctrl/cmd+wheel
// content zoom, zoom buttons) requires rendering the document ourselves.
//
// pdf.js (UMD build, window.pdfjsLib) is lazy-loaded on first PDF open —
// 320KB script + 1MB worker must not tax sessions that never open a PDF.

import { escapeHtml } from './shared.js';
import { ticketUrl } from '../nfTicket.js';
import { enableViewerZoom } from './zoom.js';

/** @type {Promise<any>|null} */
let _pdfjsReady = null;
/** @returns {Promise<any>} window.pdfjsLib (UMD global injected by the vendored script) */
function loadPdfJs() {
  if (_pdfjsReady) return _pdfjsReady;
  _pdfjsReady = new Promise((resolve, reject) => {
    const w = /** @type {any} */ (window);
    if (w.pdfjsLib) { resolve(w.pdfjsLib); return; }
    const s = document.createElement('script');
    s.src = '/vendor/pdfjs/pdf.min.js';
    s.onload = () => {
      w.pdfjsLib.GlobalWorkerOptions.workerSrc = '/vendor/pdfjs/pdf.worker.min.js';
      resolve(w.pdfjsLib);
    };
    s.onerror = () => reject(new Error('pdf.js load failed'));
    document.head.appendChild(s);
  });
  return _pdfjsReady;
}

/** PDF viewer — all pages rendered to canvases, zoomable + scrollable. */
async function viewPdf(pane, { absPath, fileName, anchor }) {
  pane.innerHTML = '';
  pane.classList.remove('scrollable'); // .pdf-pages is the scroll region
  const wrap = document.createElement('div');
  wrap.className = 'canvas-pdf-viewer';
  const pages = document.createElement('div');
  pages.className = 'pdf-pages';
  const loading = document.createElement('div');
  loading.className = 'pdf-loading';
  loading.textContent = 'Loading PDF…';
  wrap.appendChild(loading);
  wrap.appendChild(pages);
  pane.appendChild(wrap);

  let doc;
  try {
    const pdfjsLib = await loadPdfJs();
    // One ticket covers the whole document: pdf.js issues a Range request per
    // chunk/page, and R4 keeps tickets valid for unlimited reads inside the
    // TTL — a one-shot ticket would break page 2 onwards (and every seek).
    const url = await ticketUrl(absPath);
    doc = await pdfjsLib.getDocument({ url }).promise;
  } catch (e) {
    if (!pane.isConnected) return;
    wrap.innerHTML = `<div class="canvas-error">Cannot display PDF: ${escapeHtml(String(e && e.message || e))}<br>Path: ${escapeHtml(absPath || '')}</div>`;
    return;
  }
  if (!pane.isConnected) return;
  loading.remove();

  // Canvas per page (1-based). Rendering is re-done per zoom level so text
  // stays vector-crisp; a render token cancels stale runs after rapid zooms.
  const numPages = doc.numPages;
  const canvases = [];
  for (let i = 1; i <= numPages; i++) {
    const c = document.createElement('canvas');
    c.className = 'pdf-page';
    c.dataset.page = String(i);
    pages.appendChild(c);
    canvases.push(c);
  }

  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  let scale = 1;
  let renderToken = 0;

  // #303 C3: jump-to-page anchor for document references. A page canvas has
  // no CSS size until its turn in the render loop (a fresh <canvas> is 300x150
  // by default, so sized pages are detected via style.height, not the
  // attribute height). scrollToPage scrolls immediately when dimensions
  // exist, otherwise stores the target in pendingScrollPage, consumed inside
  // renderAll right after that page's canvas gets its size (all preceding
  // pages are sized first — the loop is sequential — so the offset is stable
  // at that point).
  let pendingScrollPage = 0;

  function scrollToPage(n) {
    if (!Number.isFinite(n)) return;
    const target = Math.max(1, Math.min(numPages, Math.floor(n)));
    const c = canvases[target - 1];
    if (!c) return;
    if (c.style.height) {
      const dy = c.getBoundingClientRect().top - pages.getBoundingClientRect().top;
      pages.scrollTop += dy - 12;
    } else {
      pendingScrollPage = target;
    }
  }
  // Exposed for canvas.js: a reference jump to an already-open PDF tab scrolls
  // the live viewer instead of re-rendering.
  /** @type {any} */ (pane)._scrollToPage = scrollToPage;

  async function renderAll() {
    const token = ++renderToken;
    for (let i = 1; i <= numPages; i++) {
      const page = await doc.getPage(i);
      if (token !== renderToken || !pane.isConnected) return; // superseded
      const vp = page.getViewport({ scale: scale * dpr });
      const c = canvases[i - 1];
      c.width = Math.floor(vp.width);
      c.height = Math.floor(vp.height);
      c.style.width = Math.floor(vp.width / dpr) + 'px';
      c.style.height = Math.floor(vp.height / dpr) + 'px';
      if (pendingScrollPage === i) {
        pendingScrollPage = 0;
        scrollToPage(i);
      }
      const ctx2d = c.getContext('2d', { alpha: false });
      // A page render can fail transiently (canvas memory/GPU pressure with
      // very large embedded images, e.g. 5762x4172, at dpr 2 across many
      // pages). The old `catch {}` swallowed that silently, leaving a blank
      // canvas — the user saw "images are empty" with no signal. Retry once
      // and, if it still fails, draw an explicit placeholder so the failure
      // is visible instead of silently blank.
      try {
        await page.render({ canvasContext: ctx2d, viewport: vp }).promise;
      } catch {
        if (token !== renderToken || !pane.isConnected) return; // superseded, not an error
        ctx2d.clearRect(0, 0, c.width, c.height);
        try {
          await page.render({ canvasContext: ctx2d, viewport: vp }).promise;
        } catch (e2) {
          if (token !== renderToken || !pane.isConnected) return;
          c.dataset.renderFailed = '1';
          const g = c.getContext('2d');
          g.fillStyle = '#f2f3f5';
          g.fillRect(0, 0, c.width, c.height);
          g.fillStyle = '#6b6e76';
          g.font = '13px -apple-system, BlinkMacSystemFont, sans-serif';
          g.textAlign = 'center';
          g.fillText('Page ' + i + ' render failed', c.width / 2, c.height / 2 - 8);
          g.fillText(String(e2 && e2.message || e2).slice(0, 80), c.width / 2, c.height / 2 + 14);
        }
      }
      if (token !== renderToken) return;
    }
  }

  // Initial scale: fit first page width into the scroll area.
  const first = await doc.getPage(1);
  const base = first.getViewport({ scale: 1 });
  const avail = (pages.clientWidth || 600) - 32;
  scale = Math.min(Math.max(avail / base.width, 0.25), 3);
  renderAll();

  // #303 C3: initial anchor from a document-reference jump (deferred until
  // the target page is rendered via pendingScrollPage).
  if (anchor && Number.isFinite(anchor.pageStart)) scrollToPage(anchor.pageStart);

  // Wheel zooms re-render continuously — debounce to one render per gesture.
  let debounce = null;
  const applyScale = (next, pivot) => {
    const old = scale;
    scale = next;
    const k = next / old;
    const keep = pivot
      ? {
          contentTop: pages.scrollTop + pivot.y,
          contentLeft: pages.scrollLeft + pivot.x,
        }
      : null;
    clearTimeout(debounce);
    debounce = setTimeout(() => {
      renderAll().then(() => {
        if (!keep) return;
        pages.scrollTop = keep.contentTop * k - pivot.y;
        pages.scrollLeft = keep.contentLeft * k - pivot.x;
      });
    }, 120);
    if (keep) {
      pages.scrollTop = keep.contentTop * k - pivot.y;
      pages.scrollLeft = keep.contentLeft * k - pivot.x;
    }
  };

  enableViewerZoom(pane, {
    min: 0.25, max: 5,
    getScale: () => scale,
    applyScale,
    reset: () => { scale = Math.min(Math.max(((pages.clientWidth || 600) - 32) / base.width, 0.25), 3); renderAll(); },
    attachTo: pages,
  });
}

export default {
  name: 'pdf',
  label: 'PDF',
  extensions: ['.pdf'],
  binary: true,
  priority: 0,
  render: viewPdf,
};
