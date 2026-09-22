// viewers/markdown.js — Markdown viewer (rendered preview + source toggle).
// Migrated verbatim from fileViewers.js (viewMarkdown).

import { addSourceToggle, routeLocalHref, openLocalFileTab, openExternalUrlTab } from './shared.js';
import { mintTickets, stripCredentialParams } from '../nfTicket.js';

/** Scroll one heading to the TOP of the markdown content scroller — and of nothing
 *  else.
 *
 *  Why not `Element.scrollIntoView({ block: 'start' })` (what this path used until
 *  2026-09-20): the CSSOM View algorithm is NOT scoped to the nearest scroll
 *  container. It walks the target's entire ancestor chain and aligns the target
 *  against EVERY scrollable box on the way, each one clamped to its own scroll
 *  range — and `overflow: hidden` boxes are scroll containers too (they are just
 *  not user-scrollable). So one TOC click also wrote whatever residual scroll each
 *  ancestor happened to carry, including the root scroller: pointer-up on a
 *  directory item moved the whole client (author report 2026-09-20 22:56, Windows:
 *  「不仅是内容跳转了，nebflow 客户端整体也上移了一点」). Measured in the harness
 *  (tests/mdscroll-toc-scroll.spec.mjs): with a residual on the root scroller the
 *  click moved `documentElement.scrollTop` 0 → +N and `window.scrollY` 0 → +N; with
 *  a residual inside the canvas panel `#canvas-panel.scrollTop` moved with it. The
 *  intended effect is one container's own scrollTop, so it is written directly and
 *  nothing else may move — same discipline as messages.js `revealQuoteTarget`
 *  (「🔴 不使用 scrollIntoView：它会把滚动写进任一 overflow:hidden 祖先」).
 *
 *  `block: 'start'` semantics are preserved exactly: the heading's top edge lands on
 *  the scrollport's top edge, clamped to [0, max]. A heading near the document end
 *  therefore cannot be aligned — that clamp is the browser's own behaviour, not a
 *  precision regression (harness judges the landing against the same clamped
 *  expectation).
 *
 *  @param {HTMLElement} mdViewer `.canvas-md-viewer` — the scroller is its parent
 *    (this viewer builds `<div class="canvas-md-scroll"><div class="canvas-md-viewer">`)
 *  @param {HTMLElement} target the heading to bring to the top */
function scrollHeadingToTop(mdViewer, target) {
  const scroller = /** @type {HTMLElement|null} */ (mdViewer.closest('.canvas-md-scroll'));
  // No scroller in the chain (an unexpected host mounted this viewer) ⇒ nothing to
  // scroll. Deliberately NOT a scrollIntoView fallback: that is the leak itself.
  if (!scroller) return;
  const max = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
  const delta = target.getBoundingClientRect().top - scroller.getBoundingClientRect().top;
  scroller.scrollTo({
    top: Math.max(0, Math.min(max, scroller.scrollTop + delta)),
    behavior: 'smooth',
  });
}

/** Markdown viewer - render formatted markdown (read-only preview) */
async function viewMarkdown(pane, { content, absPath, fileName }) {
  // Content-unchanged guard. Focus/visibility refreshes re-fetch the same
  // file and re-render every open tab; rebuilding .canvas-md-scroll via
  // innerHTML resets scrollTop and throws the reader back to the top (bug:
  // Canvas md reading position lost on app switch). Same "unchanged never
  // remounts" rule as the Monaco path in canvas.js openWorkspaceItem. The
  // guard also requires a live preview DOM: a source-mode round trip wipes
  // innerHTML before calling back into this function, so scroller is null
  // there and the re-render must proceed.
  const scroller = pane.querySelector('.canvas-md-scroll');
  if (scroller && /** @type {any} */ (pane)._renderedMdContent === content) return;

  // Capture before rebuild: when content actually changed (external edit),
  // restore the reader's position afterwards so the refresh at worst drifts
  // but never jumps to the top. The scrollTop setter auto-clamps when the
  // new content is shorter than the old scroll offset.
  const prevScrollTop = scroller ? scroller.scrollTop : 0;

  const { renderMarkdownWithMath } = await import('../utils.js');
  let html = renderMarkdownWithMath(content || '', false);

  // Directory of this document — resolves BOTH the relative image paths below
  // and the relative link hrefs in the click handler. Computed once, so the
  // image rewrite and the link routing cannot disagree about what a relative
  // path in this document means.
  const baseDir = absPath
    ? absPath.substring(0, absPath.lastIndexOf(absPath.includes('\\') ? '\\' : '/'))
    : '';

  // Resolve relative image paths against the markdown file's directory.
  if (absPath) {
    const IMG_SCAN_RE = /<img\s+[^>]*src="([^"]+)"/g;
    const IMG_REWRITE_RE = /(<img\s+[^>]*src=")([^"]+)(")/g;
    /** Resolve one raw markdown image src to a local absolute path (null =
     *  remote/data/already-proxied — left untouched). */
    const toPath = (src) => {
      // Skip remote, data, and already-resolved URLs
      if (/^(https?:|data:|\/api\/)/.test(src)) return null;
      // The HTML has already been through the markdown parser, which
      // percent-encodes link destinations — `![](assets/截屏 1.png)` and
      // `![](<assets/x 2.png>)` both arrive here as `assets/%E6%88%AA…%201.png`.
      // Decode before resolving, otherwise the value is escaped a second time
      // below (`%25E6…`) and the endpoint looks for a literal `%E6…` file.
      // A src that is not valid escape syntax (e.g. a literal `%` in a name)
      // is used verbatim.
      try { src = decodeURIComponent(src); } catch (e) { /* keep as-is */ }
      // Resolve relative or absolute path.
      // `~` counts as absolute: the /api/nf-file endpoint expands it to the
      // user home (nfFileRoutes: `~` → user.home). The old check treated `~`
      // as relative, producing `<dir>/~/x.png` — a path that never exists, so
      // every `![](~/…)` image in a markdown file 404'd.
      let resolved;
      if (src.startsWith('/') || src.startsWith('~') || /^[A-Za-z]:[\\/]/.test(src)) {
        resolved = src; // already absolute (or ~-anchored)
      } else {
        resolved = baseDir ? baseDir + '/' + src.replace(/^\.\//, '') : src;
      }
      return resolved;
    };
    // Pass 1 — collect every candidate, then mint ONE batched ticket set
    // (dozens of images in a document must not mean dozens of requests).
    // 2026-09-11 (C batch): the credential is now a per-path ticket; the
    // global token never appears in an image URL again (T2).
    const candidates = new Set();
    html.replace(IMG_SCAN_RE, (m, raw) => {
      let src = raw;
      try { src = decodeURIComponent(src); } catch (e) { /* keep as-is */ }
      const p = toPath(src);
      if (p) candidates.add(p);
      return m;
    });
    const tickets = await mintTickets([...candidates]);
    // Pass 2 — rewrite, synchronously, from the minted set.
    html = html.replace(IMG_REWRITE_RE, (m, prefix, raw, suffix) => {
      let src = raw;
      try { src = decodeURIComponent(src); } catch (e) { /* keep as-is */ }
      const p = toPath(src);
      if (!p) return m;
      const t = tickets.get(p);
      const url = `/api/nf-file?path=${encodeURIComponent(p)}${t ? `&ticket=${encodeURIComponent(t)}` : ''}`;
      return `${prefix}${url}${suffix}`;
    });
    // Broken-image tooltip (debugging aid). The shown URL must never carry a
    // credential (T2 / Δ⑦): the ONE strip implementation lives in
    // nfTicket.js and is published here because an inline attribute handler
    // evaluates in global scope, where a module import is not visible.
    /** @type {any} */ (window).__nfStripCredential = stripCredentialParams;
    html = html.replace(
      /<img\b/g,
      '<img onerror="this.style.opacity=0.3;this.title=\'Failed: \'+(window.__nfStripCredential?window.__nfStripCredential(this.src):this.src)"'
    );
  }

  pane.classList.remove('scrollable');
  pane.innerHTML = `<div class="canvas-md-scroll"><div class="canvas-md-viewer">${html}</div></div>`;
  /** @type {any} */ (pane)._renderedMdContent = content;

  // Restore the reader's position after a content-changed rebuild. Clamped
  // automatically if the new document is shorter (scrollTop setter).
  const newScroller = pane.querySelector('.canvas-md-scroll');
  if (newScroller && prevScrollTop > 0) newScroller.scrollTop = prevScrollTop;

  // marked v5+ dropped the `headerIds` option, so add slug ids manually
  // for TOC anchor navigation. Keeps word chars, spaces, CJK, hyphens;
  // strips other punctuation, lowercases, spaces→hyphens.
  const mdViewer = pane.querySelector('.canvas-md-viewer');
  if (mdViewer) {
    const slugCounts = {};
    const slugify = (text) => (text || '')
      .toLowerCase()
      .replace(/[^\w\s\u4e00-\u9fff-]/g, '')
      .trim()
      .replace(/\s+/g, '-')
      .replace(/-+/g, '-');
    mdViewer.querySelectorAll('h1, h2, h3, h4, h5, h6').forEach(h => {
      if (h.id) return;
      let slug = slugify(h.textContent);
      if (!slug) slug = 'heading';
      if (slug in slugCounts) { slugCounts[slug]++; slug = `${slug}-${slugCounts[slug]}`; }
      else slugCounts[slug] = 0;
      h.id = slug;
    });
    // Link routing — the SAME criterion the HTML preview frame runs
    // (`routeLocalHref` in viewers/shared.js, embedded by source into the
    // frame's injected script, so there is one definition and no drift).
    // This pane renders markdown INTO the app document: the preview surface is
    // not a sandboxed frame and has no navigation guard, so an unhandled
    // `[x](doc.md)` navigated the WHOLE application away (URL became /doc3.md,
    // 404, #activity-bar gone). Local paths now open as Canvas tabs, external
    // http(s) links as Canvas URL tabs, `#` anchors still scroll in this pane.
    // 2026-09-12 (canvas-preview nav parity, residual face): a markdown
    // deliverable is the same authored-markup surface as an HTML one and gets
    // the same browser-grade link behaviour.
    mdViewer.addEventListener('click', (e) => {
      const link = e.target.closest('a[href]');
      if (!link) return;
      const href = link.getAttribute('href');
      const route = routeLocalHref(href, baseDir);
      if (route.kind === 'anchor') {
        // TOC anchor: scroll within the pane's content container, not the window,
        // and not any other scrollable ancestor either (scrollHeadingToTop above).
        // marked v12 URL-encodes CJK chars in href="#..." anchors, but heading IDs
        // use raw characters. Try decoded first, fall back to raw.
        if (!href || href === '#') return;
        e.preventDefault();
        const raw = href.slice(1);
        let decoded;
        try { decoded = decodeURIComponent(raw); } catch { decoded = raw; }
        const target = mdViewer.querySelector(`[id="${decoded}"]`) ||
                       mdViewer.querySelector(`[id="${raw}"]`);
        if (target) scrollHeadingToTop(mdViewer, target);
        return;
      }
      // `none`: mailto:/tel:/data:/javascript: — the browser's own business.
      if (route.kind === 'none') return;
      // Never let a link replace the application document: `unresolved` is a
      // relative href in a document opened without a path (no base to resolve
      // against) — it has no target, so the click resolves to nothing at all.
      e.preventDefault();
      if (route.kind === 'external') openExternalUrlTab(route.url);
      else if (route.kind === 'local' && route.path) openLocalFileTab(route.path);
    });
  }

  addSourceToggle(pane, viewMarkdown, { content, absPath, fileName });
}

export default {
  name: 'markdown',
  label: 'Markdown',
  extensions: ['.md', '.markdown'],
  binary: false,
  priority: 0,
  render: viewMarkdown,
};
