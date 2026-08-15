// viewers/markdown.js — Markdown viewer (rendered preview + source toggle).
// Migrated verbatim from fileViewers.js (viewMarkdown).

import { getToken, addSourceToggle } from './shared.js';

/** Markdown viewer — render formatted markdown (read-only preview) */
async function viewMarkdown(pane, { content, absPath, fileName }) {
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
      // Resolve relative or absolute path
      let resolved;
      if (src.startsWith('/') || /^[A-Za-z]:[\\/]/.test(src)) {
        resolved = src; // already absolute
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
