// viewers/markdown.js — Markdown viewer (rendered preview + source toggle).
// Migrated verbatim from fileViewers.js (viewMarkdown).

import { getToken, addSourceToggle } from './shared.js';

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

  // Resolve relative image paths against the markdown file's directory.
  if (absPath) {
    const sep = absPath.includes('\\') ? '\\' : '/';
    const dir = absPath.substring(0, absPath.lastIndexOf(sep));
    const tok = getToken();
    html = html.replace(/(<img\s+[^>]*src=")([^"]+)(")/g, (m, prefix, src, suffix) => {
      // Skip remote, data, and already-resolved URLs
      if (/^(https?:|data:|\/api\/)/.test(src)) return m;
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
        resolved = dir ? dir + '/' + src.replace(/^\.\//, '') : src;
      }
      return `${prefix}/api/nf-file?path=${encodeURIComponent(resolved)}&token=${encodeURIComponent(tok)}${suffix}`;
    });
    // Add onerror handler for debugging broken images
    html = html.replace(/<img\b/g, '<img onerror="this.style.opacity=0.3;this.title=\'Failed: \'+this.src"');
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
    // Handle TOC anchor clicks — scroll within the pane, not the window.
    // marked v12 URL-encodes CJK chars in href="#..." anchors, but heading
    // IDs use raw characters. Try decoded first, fall back to raw.
    mdViewer.addEventListener('click', (e) => {
      const link = e.target.closest('a[href^="#"]');
      if (!link) return;
      const href = link.getAttribute('href');
      if (!href || href === '#') return;
      e.preventDefault();
      const raw = href.slice(1);
      let decoded;
      try { decoded = decodeURIComponent(raw); } catch { decoded = raw; }
      const target = mdViewer.querySelector(`[id="${decoded}"]`) ||
                     mdViewer.querySelector(`[id="${raw}"]`);
      if (target) target.scrollIntoView({ behavior: 'smooth', block: 'start' });
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
