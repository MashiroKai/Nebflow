// viewers/pdf.js — PDF viewer rendered with vendored pdf.js (zoomable).
// Replaces the old <iframe src=/api/nf-file> native-plugin approach: the
// browser's built-in PDF plugin swallows/ignores zoom gestures per-browser and
// offers no scriptable zoom API, so Canvas zoom acceptance (ctrl/cmd+wheel
// content zoom, zoom buttons) requires rendering the document ourselves.
//
// pdf.js (UMD build, window.pdfjsLib) is lazy-loaded on first PDF open —
// 320KB script + 1MB worker must not tax sessions that never open a PDF.
//
// P2 批（阅读器体验设计件 §5.2/§5.3，2026-10-30）大文件聪明加载：开书不再
// renderAll() 顺序渲全部页（那是大文件唯一真瓶颈）。视口驱动懒渲染 + 四条
// 可断言护栏（下方常量，改值必须连测试一起改：tests/pdf-lazy-render.spec.mjs）。

import { escapeHtml } from './shared.js';
import { nfViewerUrl } from '../nfFileCache.js';
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

// ── 大文件护栏（设计 §5.3，DOM/队列可断言 —— A7/站3 钉死）──────────────────
/** 护栏边距：视口外 ±N 屏进入渲染候选（IntersectionObserver rootMargin）。 */
const RENDER_MARGIN_SCREENS = 2;
/** 护栏②：单页渲染并发上限。 */
const MAX_RENDER_CONCURRENCY = 2;
/** 护栏①：常驻位图页数上限（canvas 带位图 ⇔ 有 width 内容属性，A7 判据）。 */
const MAX_RESIDENT_BITMAPS = 6;
/** 页尺寸预占（§5.2-3）：≤ 此页数逐页 getPage 量尺寸；超过则抽样均高估算。 */
const FULL_SIZE_SCAN_MAX_PAGES = 2000;
/** 抽样预占的样本数（> 2000 页时）。 */
const SIZE_SAMPLE_COUNT = 50;

/** PDF viewer — viewport-driven lazy rendering, zoomable + scrollable.
 *
 *  `objectUrl` = 附件预览腿（attachmentPreview.js）：整件字节已由鉴权路由取回，
 *  blob: URL 直接交给 pdf.js（它自己发 Range，blob 后端同样支持）⇒ 不触票据链。 */
async function viewPdf(pane, { absPath, fileName, anchor, objectUrl }) {
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
    // M2 账本接缝：票据腿经 nfViewerUrl——`nf:<absPath>` 命中 = blob URL 直开
    // （第二次打开零取数；blob 后端同样支持 pdf.js 的取数路径，附件腿先例）。
    const url = await nfViewerUrl({ absPath, objectUrl, pane });
    doc = await pdfjsLib.getDocument({ url }).promise;
  } catch (e) {
    if (!pane.isConnected) return;
    wrap.innerHTML = `<div class="canvas-error">Cannot display PDF: ${escapeHtml(String(e && e.message || e))}<br>Path: ${escapeHtml(absPath || fileName || '')}</div>`;
    return;
  }
  if (!pane.isConnected) return;

  // Canvas per page (1-based address via data-page). 占位只持 style 尺寸
  // （页尺寸预占）；位图 = width/height 内容属性，离屏即撤（护栏①）。
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

  // #303 C3: jump-to-page anchor for document references. 页尺寸预占后
  // style.height 立即在位 ⇒ 锚跳转不再等渲染轮到自己（比 renderAll 时代更稳）；
  // pendingScrollPage 仍兜底「量尺寸完成前就有人跳」的时序。
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

  /** 每页 CSS 尺寸（scale=1 基准；getViewport 线性缩放 ⇒ 任意档位 = 乘 scale）。
   *  null = 尚未量出（预占完成前不可能有渲染）。 */
  const pageDims = new Array(numPages).fill(null);

  /** 只读元数据量一页尺寸（不渲染），量完即 cleanup 页级结构（§5.2-3）。 */
  async function measurePage(i) {
    const page = await doc.getPage(i);
    if (!pane.isConnected) return null;
    const vp = page.getViewport({ scale: 1 });
    const dims = { w: Math.floor(vp.width), h: Math.floor(vp.height) };
    page.cleanup();
    return dims;
  }

  // 初始 scale：fit first page width into the scroll area（既有算法）。
  const firstDims = await measurePage(1);
  if (!firstDims) return;
  const base = firstDims; // scale=1 的首页尺寸
  const avail = (pages.clientWidth || 600) - 32;
  scale = Math.min(Math.max(avail / base.w, 0.25), 3);

  // ── 页尺寸预占（§5.2-3，防滚动跳变）：布局在渲染**之前**就位 ──
  if (numPages <= FULL_SIZE_SCAN_MAX_PAGES) {
    for (let i = 1; i <= numPages; i++) {
      const dims = i === 1 ? firstDims : await measurePage(i);
      if (!dims) return;
      pageDims[i - 1] = dims;
    }
  } else {
    // 大卷抽样：均宽/均高估算全卷（设计允许加载中 ±5% 跳动，避免 1 万次 getPage）。
    let sumW = 0, sumH = 0, samples = 0;
    const stride = Math.max(1, Math.floor(numPages / SIZE_SAMPLE_COUNT));
    for (let i = 1; i <= numPages && samples < SIZE_SAMPLE_COUNT; i += stride) {
      const dims = await measurePage(i);
      if (!dims) return;
      sumW += dims.w; sumH += dims.h; samples++;
    }
    const avg = { w: Math.floor(sumW / samples), h: Math.floor(sumH / samples) };
    for (let i = 0; i < numPages; i++) pageDims[i] = avg;
  }

  /** 按当前 scale 重排**全部**占位（style 尺寸；不动位图）。缩放时离屏页
   *  只换占位尺寸，下次入视口按新 scale 补渲（§5.2-4）。 */
  function applyLayout() {
    for (let i = 0; i < numPages; i++) {
      const d = pageDims[i];
      const c = canvases[i];
      c.style.width = Math.floor(d.w * scale) + 'px';
      c.style.height = Math.floor(d.h * scale) + 'px';
    }
  }
  applyLayout();
  loading.remove();
  if (pendingScrollPage) {
    const t = pendingScrollPage;
    pendingScrollPage = 0;
    scrollToPage(t);
  }
  if (anchor && Number.isFinite(anchor.pageStart)) scrollToPage(anchor.pageStart);

  // ── 懒渲染（§5.2）：IntersectionObserver 视口驱动 + 并发≤2 + 常驻≤6 ──
  /** 命中 ±N 屏的页号集合。 */
  const visible = new Set();
  /** 待渲队列（当前 scale 下位图缺失/ stale 的页）。 */
  const pending = new Set();
  /** 渲染中的页号（护栏②计数面）。 */
  const inFlight = new Set();
  /** 每 canvas 渲染代际：准入时 +1；过时渲染凭代际判「canvas 归谁」，防缩放后
   *  同页双渲染竞争同一 canvas（僵尸位图）与误清新渲染。 */
  const canvasGen = new Array(numPages).fill(0);
  /** 队列耗尽回调（缩放 pivot 补偿等）。 */
  /** @type {Set<() => void>} */
  const quiescentCbs = new Set();

  /** 页的常驻位图 scale；undefined = 无位图。 */
  const renderedScaleOf = (n) => canvases[n - 1].dataset.renderedScale;
  const renderedPages = () => {
    const out = [];
    for (let i = 1; i <= numPages; i++) if (renderedScaleOf(i) !== undefined) out.push(i);
    return out;
  };
  /** 页到视口的距离（页号差近似；视口空时 0 = 不偏爱任何页）。 */
  const distToViewport = (n) => {
    if (visible.size === 0) return 0;
    let d = Infinity;
    for (const v of visible) d = Math.min(d, Math.abs(n - v));
    return d;
  };

  /** 释放一页位图 + 页级结构（离屏 cleanup，§5.2-1 pdf.js 官方 viewer 同范式）。
   *  撤 width/height 内容属性 = A7 的「离屏页无位图」DOM 判据；代际 +1 使在途
   *  旧渲染回来时认识自己已失去 canvas 所有权（它自会回滚，见 renderPage）。 */
  function releasePage(n) {
    const c = canvases[n - 1];
    if (c.dataset.renderedScale === undefined) return;
    canvasGen[n - 1]++;
    c.width = 0;
    c.height = 0;
    c.removeAttribute('width');
    c.removeAttribute('height');
    delete c.dataset.renderedScale;
    doc.getPage(n).then((p) => p.cleanup()).catch(() => {});
  }

  async function renderPage(n, token, gen) {
    const c = canvases[n - 1];
    const page = await doc.getPage(n);
    // 代际/令牌双判：缩放或让位释放后，本渲染的一切写操作即失去合法性。
    if (token !== renderToken || gen !== canvasGen[n - 1] || !pane.isConnected) return; // superseded
    const vp = page.getViewport({ scale: scale * dpr });
    c.width = Math.floor(vp.width);
    c.height = Math.floor(vp.height);
    const ctx2d = c.getContext('2d', { alpha: false });
    // A page render can fail transiently (canvas memory/GPU pressure with
    // very large embedded images, e.g. 5762x4172, at dpr 2 across many
    // pages). The old `catch {}` swallowed that silently, leaving a blank
    // canvas — the user saw "images are empty" with no signal. Retry once
    // and, if it still fails, draw an explicit placeholder so the failure
    // is visible instead of silently blank（§5.3-5 沿用既有纪律）.
    try {
      await page.render({ canvasContext: ctx2d, viewport: vp }).promise;
    } catch {
      if (token !== renderToken || gen !== canvasGen[n - 1] || !pane.isConnected) return; // superseded, not an error
      ctx2d.clearRect(0, 0, c.width, c.height);
      try {
        await page.render({ canvasContext: ctx2d, viewport: vp }).promise;
      } catch (e2) {
        if (token !== renderToken || gen !== canvasGen[n - 1] || !pane.isConnected) return;
        c.dataset.renderFailed = '1';
        const g = c.getContext('2d');
        g.fillStyle = '#f2f3f5';
        g.fillRect(0, 0, c.width, c.height);
        g.fillStyle = '#6b6e76';
        g.font = '13px -apple-system, BlinkMacSystemFont, sans-serif';
        g.textAlign = 'center';
        g.fillText('Page ' + n + ' render failed', c.width / 2, c.height / 2 - 8);
        g.fillText(String(e2 && e2.message || e2).slice(0, 80), c.width / 2, c.height / 2 + 14);
      }
    }
    if (token !== renderToken || gen !== canvasGen[n - 1] || !pane.isConnected) {
      // 已失去所有权但**无人接管**（代际未再变 ⇒ 没有新渲染在用这块 canvas）：
      // 回滚自己的位图，防旧 scale 僵尸位图占位（护栏①的瞬时面）。
      if (gen === canvasGen[n - 1] && pane.isConnected && c.dataset.renderedScale === undefined) {
        c.width = 0;
        c.height = 0;
        c.removeAttribute('width');
        c.removeAttribute('height');
      }
      return;
    }
    c.dataset.renderedScale = String(scale);
  }

  /** 护栏①：常驻位图 > N 页时，释放离视口最远的已渲染页（永不释放视口内页）。 */
  function enforceResidentCap() {
    let guard = 0;
    while (guard++ <= numPages) {
      const resident = renderedPages().filter((n) => !inFlight.has(n));
      if (resident.length <= MAX_RESIDENT_BITMAPS) break;
      const farthest = resident.sort((a, b) => distToViewport(b) - distToViewport(a))[0];
      if (farthest === undefined || distToViewport(farthest) === 0) break; // 全在视口内，不强行释放
      releasePage(farthest);
    }
  }

  /** 队列泵：距离优先、并发≤2、常驻≤6（含在途，瞬时也不超护栏①）。
   *  预算满时**先让位再准入**（释放比候选更远的已渲染页），保证任意时刻
   *  「带 width 属性的 canvas ≤ 6」这一 DOM 判据恒成立（A7 的瞬时不豁免）。 */
  function pump() {
    if (!pane.isConnected) return;
    for (;;) {
      if (inFlight.size >= MAX_RENDER_CONCURRENCY) return;
      let best = -1;
      let bestD = Infinity;
      for (const n of pending) {
        if (inFlight.has(n)) continue; // 同页串行：在途未结时不重复准入（防同 canvas 双渲染）
        const d = distToViewport(n);
        if (d < bestD) { bestD = d; best = n; }
      }
      if (best < 0) return;
      // 常驻预算：rendered + inFlight 视为未来位图；超预算时先释放「比候选页
      // 离视口更远」的已渲染页（不在途、不在视口内），无可让位则候选先等着。
      if (renderedPages().length + inFlight.size >= MAX_RESIDENT_BITMAPS) {
        const evict = renderedPages()
          .filter((n) => !inFlight.has(n) && distToViewport(n) > bestD)
          .sort((a, b) => distToViewport(b) - distToViewport(a))[0];
        if (evict === undefined) return;
        releasePage(evict);
      }
      pending.delete(best);
      inFlight.add(best);
      const token = renderToken;
      const gen = ++canvasGen[best - 1];
      renderPage(best, token, gen).catch(() => {}).finally(() => {
        inFlight.delete(best);
        enforceResidentCap();
        pump();
        if (pending.size === 0 && inFlight.size === 0) {
          const cbs = Array.from(quiescentCbs);
          quiescentCbs.clear();
          for (const cb of cbs) { try { cb(); } catch { /* 回调自身故障不拖垮队列 */ } }
        }
      });
    }
  }

  /** 视口集合变化 / 缩放后：把 可见 ∩ 未按当前 scale 渲 的页入队。
   *  队列本就空 ⇒ 立即兑现 quiescent 回调（缩放 pivot 补偿不得悬挂）。 */
  function scheduleVisible() {
    for (const n of visible) {
      if (renderedScaleOf(n) !== String(scale)) pending.add(n);
    }
    pump();
    if (pending.size === 0 && inFlight.size === 0) {
      const cbs = Array.from(quiescentCbs);
      quiescentCbs.clear();
      for (const cb of cbs) { try { cb(); } catch { /* 回调自身故障不拖垮队列 */ } }
    }
  }

  const io = new IntersectionObserver((entries) => {
    if (!pane.isConnected) return;
    for (const e of entries) {
      const n = Number(/** @type {HTMLElement} */ (e.target).dataset.page);
      if (e.isIntersecting) {
        visible.add(n);
      } else {
        visible.delete(n);
        releasePage(n); // 离开 ±N 屏立即释放（§5.2-1）
      }
    }
    scheduleVisible();
  }, { root: pages, rootMargin: `${RENDER_MARGIN_SCREENS * 100}% 0px`, threshold: 0 });
  for (const c of canvases) io.observe(c);

  // 滚轮缩放（120ms debounce 沿用 §5.2-4）——只重渲当前可见 ±2 屏；离屏页
  // 位图全部失效（旧 scale 无意义），占位按新 scale 重排，下次入视口补渲。
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
      renderToken++; // 在途渲染作废（既有竞态纪律）
      pending.clear();
      for (const n of renderedPages()) releasePage(n);
      applyLayout();
      const done = new Promise((resolve) => quiescentCbs.add(() => resolve(null)));
      scheduleVisible();
      done.then(() => {
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

  const resetScale = () => {
    scale = Math.min(Math.max(((pages.clientWidth || 600) - 32) / base.w, 0.25), 3);
    renderToken++;
    pending.clear();
    for (const n of renderedPages()) releasePage(n);
    applyLayout();
    scheduleVisible();
  };

  enableViewerZoom(pane, {
    min: 0.25, max: 5,
    getScale: () => scale,
    applyScale,
    reset: resetScale,
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
