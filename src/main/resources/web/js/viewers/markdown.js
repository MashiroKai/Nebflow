// viewers/markdown.js — Markdown viewer (rendered preview + source toggle).
// Migrated verbatim from fileViewers.js (viewMarkdown).
//
// wiki-graph batch (2026-10-14): Obsidian-compatible document face —
//   · `[[wikilinks]]` render as clickable cross-document references
//     (resolution through the /api/wiki/graph index; unresolved targets stay
//     visible and explain themselves on click).
//   · Reference jumps carry an anchor (block `^id` / heading / `L<line>`) —
//     the viewer scrolls the target paragraph to the top and flash-highlights
//     it (paper-style "first mention must explain or reference" reading flow).
//   · Floating glass toggles: in-file find (same family as the source toggle)
//     and "open in wiki graph".

import { addSourceToggle, addFindToggle, addGraphToggle, routeLocalHref, openLocalFileTab, openExternalUrlTab } from './shared.js';
import { mintTickets, stripCredentialParams } from '../nfTicket.js';
import { t } from '../i18n.js';

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
 *  click moved `documentElement.scrollTop` 0 → +N and `window.scrollY` 0 → +N; with a
 *  residual inside the canvas panel `#canvas-panel.scrollTop` moved with it. The
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

// ── Source analysis (block map + ^block-id extraction) ────────────────
// The rendered graph needs two maps back into the SOURCE the user's links
// point at: which source line each rendered top-level block came from (line
// anchors `L<n>`), and where each `^block-id` lives. Block-id markers are
// stripped from the rendered string — Obsidian hides them in preview too.

const FENCE_RE = /^\s*(```+|~~~+)/;
const BLOCK_ID_LINE_RE = /^(\s*(?:#{1,6}\s+)?[^\n]*\S)[ \t]+\^([A-Za-z0-9][A-Za-z0-9-]*)[ \t]*$/;

/**
 * @param {string} content raw markdown
 * @returns {{ html: string, blockStarts: number[], blockIds: Map<string, number> }}
 */
function analyzeSource(content) {
  const lines = content.split('\n');
  const blockIds = new Map();
  const stripped = lines.map((line, i) => {
    const m = BLOCK_ID_LINE_RE.exec(line);
    if (m) {
      if (!blockIds.has(m[2])) blockIds.set(m[2], i + 1);
      return m[1];
    }
    return line;
  });
  // Fence-aware block starts: a line begins a new top-level block when the
  // previous line is blank, or when it opens a heading / a first quote line.
  const blockStarts = [];
  let inFence = false;
  let fenceMarker = '';
  for (let i = 0; i < stripped.length; i++) {
    const line = stripped[i];
    const fenceMatch = FENCE_RE.exec(line);
    if (fenceMatch) {
      if (!inFence) {
        inFence = true;
        fenceMarker = fenceMatch[1].slice(0, 3);
        blockStarts.push(i + 1);
      } else if (fenceMatch[1].startsWith(fenceMarker)) {
        inFence = false;
        fenceMarker = '';
      }
      continue;
    }
    if (inFence) continue;
    const prev = i > 0 ? stripped[i - 1] : '';
    const prevBlank = prev.trim() === '';
    const prevQuote = prev.startsWith('>');
    const isHeading = line.startsWith('#');
    const isQuoteStart = line.startsWith('>') && !prevQuote;
    if (i === 0 || prevBlank || isHeading || isQuoteStart) blockStarts.push(i + 1);
  }
  if (blockStarts.length === 0) blockStarts.push(1);
  return { html: stripped.join('\n'), blockStarts, blockIds };
}

/** Tag each rendered top-level element with its source line (data-src-line).
 *  When the element count matches the block count the mapping is exact;
 *  otherwise it degrades proportionally — close enough to flash the right
 *  neighborhood, never silently absent. */
function assignSourceLines(mdViewer, blockStarts) {
  const kids = /** @type {HTMLElement[]} */ (Array.from(mdViewer.children));
  if (!kids.length || !blockStarts.length) return;
  kids.forEach((el, i) => {
    const idx = kids.length === blockStarts.length
      ? i
      : Math.min(blockStarts.length - 1, Math.floor((i / kids.length) * blockStarts.length));
    el.dataset.srcLine = String(blockStarts[idx]);
  });
}

/**
 * Apply a reference-jump anchor to the rendered document: locate the target
 * block, scroll it to the top of the scroller and flash-highlight it.
 * Anchor forms: {kind:'block', id} · {kind:'heading', text} · {kind:'line', line}
 * (the same payload `openLocalFileTab` dispatches; PDF pageStart is consumed
 * elsewhere).
 * @param {HTMLElement} mdViewer
 * @param {any} anchor
 * @param {Map<string, number>} [blockIds]
 */
function applyJumpAnchor(mdViewer, anchor, blockIds) {
  if (!anchor || !mdViewer) return;
  let target = null;
  if (anchor.kind === 'block' && anchor.id && blockIds && blockIds.has(anchor.id)) {
    const line = blockIds.get(anchor.id);
    target = blockAtOrBefore(mdViewer, line);
  } else if (anchor.kind === 'heading' && anchor.text) {
    // Same slug discipline the TOC click handler uses (marked v12 encodes
    // CJK hrefs; ids here are raw).
    const slugCounts = {};
    const slugify = (text) => (text || '')
      .toLowerCase()
      .replace(/[^\w\s\u4e00-\u9fff-]/g, '')
      .trim()
      .replace(/\s+/g, '-')
      .replace(/-+/g, '-');
    const want = slugify(anchor.text);
    mdViewer.querySelectorAll('h1, h2, h3, h4, h5, h6').forEach((h) => {
      if (h.id === want) target = h;
    });
    if (!target) {
      // Fall back to a text match over the headings.
      mdViewer.querySelectorAll('h1, h2, h3, h4, h5, h6').forEach((h) => {
        if (!target && (h.textContent || '').trim() === anchor.text.trim()) target = h;
      });
    }
  } else if (anchor.kind === 'line' && Number.isFinite(anchor.line)) {
    target = blockAtOrBefore(mdViewer, anchor.line);
  }
  if (!target) return;
  scrollHeadingToTop(mdViewer, target);
  target.classList.remove('nf-jump-flash');
  // Force a reflow so consecutive jumps restart the animation.
  void target.offsetWidth;
  target.classList.add('nf-jump-flash');
  target.addEventListener('animationend', () => target.classList.remove('nf-jump-flash'), { once: true });
}

/** The rendered block whose data-src-line is the greatest one ≤ `line`. */
function blockAtOrBefore(mdViewer, line) {
  let best = null, bestLine = -1;
  mdViewer.querySelectorAll('[data-src-line]').forEach((el) => {
    const l = parseInt(el.dataset.srcLine || '0', 10);
    if (l <= line && l > bestLine) { best = el; bestLine = l; }
  });
  return best || mdViewer.querySelector('[data-src-line]');
}

// ── Wikilink graph index (module-level, TTL-cached) ───────────────────

/** @type {{at:number, data:any}|null} */
let wikiIndexCache = null;
const WIKI_INDEX_TTL = 30_000;

async function wikiGraph() {
  if (wikiIndexCache && Date.now() - wikiIndexCache.at < WIKI_INDEX_TTL) return wikiIndexCache.data;
  const { authHeaders } = await import('../flowHelpers.js');
  const res = await fetch('/api/wiki/graph', { headers: { ...authHeaders() } });
  if (!res.ok) throw new Error('wiki graph unavailable: ' + res.status);
  const data = await res.json();
  wikiIndexCache = { at: Date.now(), data };
  return data;
}

/**
 * Resolve an Obsidian-style link target against the graph index.
 * Returns { absPath } for a local hit, { remote } for a peer doc, or null.
 * @param {string} target raw target (no anchor/alias)
 * @param {string} baseDir the current document's directory ('' when unknown)
 * @param {any} data graph JSON
 */
function resolveWikiTarget(target, baseDir, data) {
  const norm = (s) => String(s || '').replace(/\\/g, '/').replace(/\/+$/, '').toLowerCase();
  const clean = target.replace(/\\/g, '/').replace(/^\.\/+/, '');
  const withMd = clean.toLowerCase().endsWith('.md') ? clean : clean + '.md';
  const base = norm(baseDir);
  const nodes = (data && data.nodes) || [];
  const projects = (data && data.projects) || [];
  const sameVault = (n) => {
    const p = projects.find((pj) => pj.name === n.project);
    if (!p) return null;
    const vault = norm(p.vault);
    const nodeDir = norm(vault + '/' + n.rel.split('/').slice(0, -1).join('/'));
    return nodeDir === base ? p : null;
  };
  // 1. Vault-relative exact path, preferring THIS document's vault.
  let hit = nodes.find((n) => !n.stub && n.rel.toLowerCase() === withMd && sameVault(n));
  // 2. Unique basename across all vaults (Obsidian's shortest-name rule).
  if (!hit) {
    const byBase = nodes.filter((n) => !n.stub && n.rel.toLowerCase().split('/').pop() === withMd.split('/').pop());
    if (byBase.length === 1) hit = byBase[0];
    else if (byBase.length > 1) {
      // Prefer a doc in the same vault, then self-device docs.
      hit = byBase.find((n) => sameVault(n)) || byBase.find((n) => n.device === 'self') || null;
    }
  }
  if (!hit) return null;
  const proj = projects.find((pj) => pj.name === hit.project);
  if (!proj) return null;
  if (hit.device === 'self') {
    const sep = proj.vault.includes('\\') ? '\\' : '/';
    return { absPath: proj.vault.replace(/[\\/]+$/, '') + sep + hit.rel.split('/').join(sep) };
  }
  return { remote: { device: hit.device, vault: proj.vault, rel: hit.rel, project: hit.project } };
}

/**
 * Post-render pass: turn `[[wikilink]]` text occurrences into reference
 * anchors. Runs over text nodes so fenced code and inline code stay literal.
 * @param {HTMLElement} mdViewer
 * @param {string} baseDir current document directory
 */
async function renderWikilinks(mdViewer, baseDir) {
  let data = null;
  try { data = await wikiGraph(); } catch (e) { data = null; }
  const WIKI_TEXT_RE = /\[\[([^\[\]]+?)\]\]/g;
  const walker = document.createTreeWalker(mdViewer, NodeFilter.SHOW_TEXT, {
    acceptNode: (node) => {
      const p = node.parentElement;
      if (!p) return NodeFilter.FILTER_REJECT;
      const tag = p.tagName;
      if (tag === 'CODE' || tag === 'PRE' || tag === 'SCRIPT' || tag === 'STYLE' || tag === 'A') return NodeFilter.FILTER_REJECT;
      if (p.closest('pre, code, a.nf-wikilink')) return NodeFilter.FILTER_REJECT;
      return node.nodeValue && node.nodeValue.includes('[[') ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
    },
  });
  const textNodes = [];
  while (walker.nextNode()) textNodes.push(walker.currentNode);
  for (const node of textNodes) {
    const value = node.nodeValue || '';
    WIKI_TEXT_RE.lastIndex = 0;
    if (!WIKI_TEXT_RE.test(value)) continue;
    WIKI_TEXT_RE.lastIndex = 0;
    const frag = document.createDocumentFragment();
    let last = 0;
    let m;
    while ((m = WIKI_TEXT_RE.exec(value)) !== null) {
      if (m.index > last) frag.appendChild(document.createTextNode(value.slice(last, m.index)));
      const inner = m[1];
      const pipe = inner.indexOf('|');
      const body = pipe >= 0 ? inner.slice(0, pipe) : inner;
      const hash = body.indexOf('#');
      const target = hash >= 0 ? body.slice(0, hash).trim() : body.trim();
      const anchorRaw = hash >= 0 ? body.slice(hash + 1).trim() : '';
      const alias = pipe >= 0 ? inner.slice(pipe + 1).trim() : '';
      const a = document.createElement('a');
      a.className = 'nf-wikilink';
      a.href = '#';
      a.textContent = alias || target || anchorRaw;
      a.dataset.wikiTarget = target;      // '' = self-reference ([[#Heading]])
      a.dataset.wikiAnchor = anchorRaw;   // '^id' or 'Heading'
      frag.appendChild(a);
      last = m.index + m[0].length;
    }
    if (last < value.length) frag.appendChild(document.createTextNode(value.slice(last)));
    node.parentNode?.replaceChild(frag, node);
  }
  // Resolution pass (best-effort, after all links exist).
  mdViewer.querySelectorAll('a.nf-wikilink').forEach((el) => {
    const a = /** @type {HTMLAnchorElement} */ (el);
    const target = a.dataset.wikiTarget || '';
    if (!target) { a.classList.add('nf-wikilink-self'); return; }
    const hit = data ? resolveWikiTarget(target, baseDir, data) : null;
    if (hit) {
      a.classList.add('nf-wikilink-resolved');
      a.dataset.wikiResolved = JSON.stringify(hit);
    } else {
      a.classList.add('nf-wikilink-unresolved');
      a.title = t('wiki.unresolved');
    }
  });
}

/** Handle a click on a rendered wikilink. */
async function onWikilinkClick(a, mdViewer) {
  const anchorRaw = a.dataset.wikiAnchor || '';
  const target = a.dataset.wikiTarget || '';
  // Anchor normalization: '^id' → block jump; otherwise a heading jump.
  const anchor = anchorRaw.startsWith('^')
    ? { kind: 'block', id: anchorRaw.slice(1) }
    : (anchorRaw ? { kind: 'heading', text: anchorRaw } : null);
  // Self-reference (or an anchor-only link in this document).
  if (!target) {
    if (anchor) applyJumpAnchor(mdViewer, anchor);
    return;
  }
  let resolved = null;
  try { resolved = a.dataset.wikiResolved ? JSON.parse(a.dataset.wikiResolved) : null; } catch (e) { resolved = null; }
  if (!resolved) {
    try { resolved = resolveWikiTarget(target, '', await wikiGraph()); } catch (e) { resolved = null; }
  }
  if (!resolved) { window.__showToast?.(t('wiki.unresolved'), 'info'); return; }
  if (resolved.absPath) {
    openLocalFileTab(resolved.absPath, anchor);
    return;
  }
  if (resolved.remote) {
    try {
      const { authHeaders } = await import('../flowHelpers.js');
      const url = `/api/wiki/remote-doc?device=${encodeURIComponent(resolved.remote.device)}&path=${encodeURIComponent(resolved.remote.vault + '/' + resolved.remote.rel)}`;
      const res = await fetch(url, { headers: { ...authHeaders() } });
      if (!res.ok) throw new Error(String(res.status));
      const body = await res.json();
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: {
          id: 'wiki-remote:' + resolved.remote.project + ':' + resolved.remote.rel,
          itemType: 'markdown',
          title: (target.replace(/\.md$/i, '')) + ' · ' + resolved.remote.device,
          content: body.content || '',
          readOnly: true,
          anchor,
        },
      }));
    } catch (e) {
      window.__showToast?.(t('wiki.remoteFetchFailed'), 'error');
    }
  }
}

/** Markdown viewer - render formatted markdown (read-only preview) */
async function viewMarkdown(pane, { content, absPath, fileName, anchor: jumpAnchor, readOnly }) {
  // Content-unchanged guard. Focus/visibility refreshes re-fetch the same
  // file and re-render every open tab; rebuilding .canvas-md-scroll via
  // innerHTML resets scrollTop and throws the reader back to the top (bug:
  // Canvas md reading position lost on app switch). Same "unchanged never
  // remounts" rule as the Monaco path in canvas.js openWorkspaceItem. The
  // guard also requires a live preview DOM: a source-mode round trip wipes
  // innerHTML before calling back into this function, so scroller is null
  // there and the re-render must proceed. A jump anchor still applies to the
  // existing DOM (re-clicking a reference in an open doc must jump again).
  const scroller = pane.querySelector('.canvas-md-scroll');
  // Source analysis BEFORE the guard: the unchanged-content early-return
  // below still applies jump anchors (re-clicking a reference in an open
  // doc must jump), and a block anchor needs this document's ^id → line
  // map. The content is identical on that path, so one analysis serves both.
  const analysis = absPath ? analyzeSource(content || '') : { html: content || '', blockStarts: [], blockIds: new Map() };
  if (scroller && /** @type {any} */ (pane)._renderedMdContent === content) {
    const existingViewer = pane.querySelector('.canvas-md-viewer');
    if (existingViewer && jumpAnchor) applyJumpAnchor(existingViewer, jumpAnchor, analysis.blockIds);
    return;
  }

  // Capture before rebuild: when content actually changed (external edit),
  // restore the reader's position afterwards so the refresh at worst drifts
  // but never jumps to the top. The scrollTop setter auto-clamps when the
  // new content is shorter than the old scroll offset.
  const prevScrollTop = scroller ? scroller.scrollTop : 0;

  const { renderMarkdownWithMath } = await import('../utils.js');
  let html = renderMarkdownWithMath(analysis.html, false);

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
  // Jump face: the live analysis stays on the pane so a reference click into
  // an ALREADY-OPEN tab (canvas.js switches without re-rendering) can still
  // resolve block anchors — it dispatches nf-jump-anchor on the pane.
  /** @type {any} */ (pane)._mdAnalysis = analysis;
  if (!/** @type {any} */ (pane)._mdJumpBound) {
    /** @type {any} */ (pane)._mdJumpBound = true;
    pane.addEventListener('nf-jump-anchor', (/** @type {CustomEvent} */ e) => {
      const viewer = pane.querySelector('.canvas-md-viewer');
      const blockIds = /** @type {any} */ (pane)._mdAnalysis?.blockIds;
      if (viewer && e.detail) applyJumpAnchor(viewer, e.detail, blockIds);
    });
  }

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
    // Source-line map for reference jumps (only meaningful with a file).
    if (analysis.blockStarts.length) assignSourceLines(mdViewer, analysis.blockStarts);
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
      const wikilink = e.target instanceof Element ? e.target.closest('a.nf-wikilink') : null;
      if (wikilink) {
        e.preventDefault();
        e.stopPropagation();
        onWikilinkClick(/** @type {HTMLAnchorElement} */ (wikilink), mdViewer);
        return;
      }
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

    // Wiki face: wikilinks (async, post-render) + jump anchor.
    if (absPath) renderWikilinks(mdViewer, baseDir);
    if (jumpAnchor) applyJumpAnchor(mdViewer, jumpAnchor, analysis.blockIds);
  }

  addSourceToggle(pane, viewMarkdown, { content, absPath, fileName, readOnly: readOnly === true });
  // Glass toggles — stacked with the source toggle (48px per slot):
  // find at 64px, graph at 112px.
  addFindToggle(pane, 64);
  addGraphToggle(pane, absPath || '', 112);
}

export default {
  name: 'markdown',
  label: 'Markdown',
  extensions: ['.md', '.markdown'],
  binary: false,
  priority: 0,
  render: viewMarkdown,
};
