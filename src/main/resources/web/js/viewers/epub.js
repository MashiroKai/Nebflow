// viewers/epub.js — EPUB viewer (paginated reader with chapter navigation).
// Migrated verbatim from fileViewers.js (viewEpub + renderEpubReader).
// ZIP parsing lives in ./shared.js (parseZipEntries / decompressZipEntry).

import { escapeHtml, parseZipEntries, decompressZipEntry } from './shared.js';
import { ticketUrl } from '../nfTicket.js';

/** EPUB viewer — paginated reader with chapter navigation.
 *  Parses ZIP structure, extracts XHTML chapters, renders with CSS column pagination.
 *  Dispatches 'epub-page-change' events for tracking what user is reading. */
async function viewEpub(pane, { absPath, fileName, size }) {
  pane.innerHTML = `<div class="canvas-md-viewer" style="display:flex;align-items:center;justify-content:center;color:var(--color-text-muted);opacity:0.5;">Loading ebook...</div>`;
  if (!absPath) {
    pane.innerHTML = `<div class="canvas-error">No file path provided for EPUB viewer.</div>`;
    return;
  }
  const url = await ticketUrl(absPath);
  try {
    const resp = await fetch(url);
    if (!resp.ok) {
      const detail = await resp.text().catch(() => '');
      throw new Error(`HTTP ${resp.status}${detail ? ': ' + detail : ''}`);
    }
    const buffer = await resp.arrayBuffer();

    const entries = parseZipEntries(buffer);
    if (!entries['META-INF/container.xml']) throw new Error('Not a valid EPUB file');

    // Parse container.xml → OPF path
    const containerRaw = await decompressZipEntry(entries['META-INF/container.xml']);
    const containerDoc = new DOMParser().parseFromString(containerRaw, 'application/xml');
    const rootfileEl = containerDoc.querySelector('rootfile');
    if (!rootfileEl) throw new Error('EPUB: container.xml missing rootfile');
    const opfPath = rootfileEl.getAttribute('full-path');
    const opfDir = opfPath.includes('/') ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : '';

    // Parse OPF — manifest + spine
    const opfRaw = await decompressZipEntry(entries[opfPath]);
    const opfDoc = new DOMParser().parseFromString(opfRaw, 'application/xml');
    const manifest = {};
    opfDoc.querySelectorAll('manifest > item').forEach(item => {
      manifest[item.getAttribute('id')] = {
        href: item.getAttribute('href'),
        mediaType: item.getAttribute('media-type') || '',
      };
    });
    const spineIds = [];
    opfDoc.querySelectorAll('spine > itemref').forEach(itemref => {
      spineIds.push(itemref.getAttribute('idref'));
    });

    // Pre-load all chapter contents
    const chapters = [];
    for (const id of spineIds) {
      const item = manifest[id];
      if (!item) continue;
      const itemPath = (opfDir + item.href).replace(/[^\/]+\/\.\.\//g, '');
      const entry = entries[itemPath];
      if (!entry) continue;
      const html = await decompressZipEntry(entry);
      const bodyMatch = html.match(/<body[^>]*>([\s\S]*?)<\/body>/i);
      const bodyContent = bodyMatch ? bodyMatch[1] : html;
      const clean = bodyContent.replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '');
      const titleMatch = clean.match(/<h[1-6][^>]*>(.*?)<\/h[1-6]>/i);
      const title = titleMatch ? titleMatch[1].replace(/<[^>]+>/g, '').trim() : `Chapter ${chapters.length + 1}`;
      chapters.push({ title, content: clean });
    }

    if (chapters.length === 0) throw new Error('EPUB has no readable chapters');

    renderEpubReader(pane, chapters, fileName || 'EPUB');
  } catch (err) {
    pane.innerHTML = `<div class="canvas-error">Failed to render EPUB: ${escapeHtml(err.message)}</div>`;
  }
}

/** Render paginated EPUB reader UI — scroll-based pagination. */
function renderEpubReader(pane, chapters, bookTitle) {
  let chapterIdx = 0;

  pane.innerHTML = `
    <div class="epub-reader">
      <div class="epub-reader-header">
        <span class="epub-chapter-title"></span>
      </div>
      <div class="epub-reader-viewport">
        <div class="epub-reader-content"></div>
      </div>
      <div class="epub-reader-nav">
        <button class="epub-nav-btn epub-prev" title="Previous">‹</button>
        <span class="epub-nav-info"></span>
        <button class="epub-nav-btn epub-next" title="Next">›</button>
      </div>
    </div>`;

  const viewport = pane.querySelector('.epub-reader-viewport');
  const content = pane.querySelector('.epub-reader-content');
  const chapterTitleEl = pane.querySelector('.epub-chapter-title');
  const infoEl = pane.querySelector('.epub-nav-info');
  const prevBtn = pane.querySelector('.epub-prev');
  const nextBtn = pane.querySelector('.epub-next');

  function getTotalPages() {
    return Math.max(1, Math.ceil(viewport.scrollHeight / viewport.clientHeight));
  }

  function getCurrentPage() {
    return Math.round(viewport.scrollTop / viewport.clientHeight);
  }

  function emitTracking() {
    const total = getTotalPages();
    const page = getCurrentPage();
    pane.dispatchEvent(new CustomEvent('epub-page-change', {
      detail: {
        bookTitle,
        chapter: chapterIdx,
        chapterTitle: chapters[chapterIdx].title,
        page,
        totalPages: total,
        totalChapters: chapters.length,
        progress: chapterIdx / chapters.length + (page + 1) / total / chapters.length,
      }
    }));
  }

  function updateUI() {
    const page = getCurrentPage() + 1;
    const total = getTotalPages();
    chapterTitleEl.textContent = chapters[chapterIdx].title;
    infoEl.textContent = `Ch ${chapterIdx + 1}/${chapters.length} · Pg ${page}/${total}`;
    prevBtn.disabled = chapterIdx === 0 && viewport.scrollTop <= 0;
    nextBtn.disabled = chapterIdx === chapters.length - 1 && viewport.scrollTop >= viewport.scrollHeight - viewport.clientHeight - 1;
    emitTracking();
  }

  function loadChapter(idx, scrollPos) {
    chapterIdx = idx;
    content.innerHTML = chapters[idx].content;
    requestAnimationFrame(() => {
      viewport.scrollTop = scrollPos || 0;
      updateUI();
    });
  }

  function nextPage() {
    const bottom = viewport.scrollHeight - viewport.clientHeight;
    if (viewport.scrollTop < bottom - 1) {
      viewport.scrollTo({ top: viewport.scrollTop + viewport.clientHeight, behavior: 'smooth' });
    } else if (chapterIdx < chapters.length - 1) {
      loadChapter(chapterIdx + 1, 0);
    }
  }

  function prevPage() {
    if (viewport.scrollTop > 1) {
      viewport.scrollTo({ top: viewport.scrollTop - viewport.clientHeight, behavior: 'smooth' });
    } else if (chapterIdx > 0) {
      loadChapter(chapterIdx - 1, 999999); // bottom of previous chapter
    }
  }

  prevBtn.addEventListener('click', (e) => { e.stopPropagation(); prevPage(); });
  nextBtn.addEventListener('click', (e) => { e.stopPropagation(); nextPage(); });

  // Click left/right halves to navigate (but not when selecting text)
  viewport.addEventListener('click', (e) => {
    const sel = window.getSelection();
    if (sel && sel.toString().trim().length > 0) return;
    const rect = viewport.getBoundingClientRect();
    if (e.clientX < rect.left + rect.width / 2) prevPage();
    else nextPage();
  });

  // Keyboard navigation
  pane.tabIndex = 0;
  pane.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') { e.preventDefault(); prevPage(); }
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') { e.preventDefault(); nextPage(); }
  });

  // Update indicator on scroll
  let scrollTimer = null;
  viewport.addEventListener('scroll', () => {
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(() => updateUI(), 100);
  });

  // Recalculate on resize
  new ResizeObserver(() => updateUI()).observe(viewport);

  // Initial load
  loadChapter(0, 0);
}

export default {
  name: 'epub',
  label: 'EPUB',
  extensions: ['.epub'],
  binary: true,
  priority: 0,
  render: viewEpub,
};
