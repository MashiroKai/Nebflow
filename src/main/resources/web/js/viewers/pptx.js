// viewers/pptx.js — PPTX viewer (info + download link; no reliable client-side renderer).
// Migrated verbatim from fileViewers.js (viewPptx).

import { escapeHtml, formatSize } from './shared.js';
import { ticketUrl } from '../nfTicket.js';

/** PPTX viewer — no reliable client-side library, show info + download link.
 *
 *  2026-09-11 (C batch): async, and the download is minted AT CLICK TIME.
 *  This is the extreme case of R8's deferred-use problem — the user may press
 *  "Download to view" minutes after the tab opened, so a ticket baked into the
 *  anchor's `href` at render time would either be expired by then or would sit
 *  in the DOM as a credential (T2). `<a download>` failures are also invisible
 *  to `onerror`, so "mint on click" is the only self-healing point available.
 *  Until the click there is NO credential in the document at all. */
async function viewPptx(pane, { absPath, fileName, size }) {
  pane.innerHTML = `
    <div class="canvas-md-viewer" style="display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;color:var(--color-text-muted);">
      <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round" opacity="0.4">
        <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/>
      </svg>
      <div style="text-align:center;">
        <div style="font-weight:600;color:var(--color-text);margin-bottom:4px">${escapeHtml(fileName || 'PowerPoint')}</div>
        <div style="font-size:12px;opacity:0.6">${formatSize(size || 0)}</div>
      </div>
      <a class="nf-pptx-download" href="#" download="${escapeHtml(fileName || 'presentation.pptx')}" style="font-size:13px;color:var(--color-primary);text-decoration:none;padding:6px 16px;border:1px solid var(--color-primary);border-radius:8px;">Download to view</a>
    </div>`;

  const link = /** @type {HTMLAnchorElement|null} */ (pane.querySelector('a.nf-pptx-download'));
  if (!link || !absPath) return;
  link.addEventListener('click', async (e) => {
    e.preventDefault();
    const url = await ticketUrl(absPath);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName || 'presentation.pptx';
    document.body.appendChild(a);
    a.click();
    a.remove();
  });
}

export default {
  name: 'pptx',
  label: 'PPTX',
  extensions: ['.ppt', '.pptx'],
  binary: true,
  priority: 0,
  render: viewPptx,
};
