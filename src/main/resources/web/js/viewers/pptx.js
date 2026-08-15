// viewers/pptx.js — PPTX viewer (info + download link; no reliable client-side renderer).
// Migrated verbatim from fileViewers.js (viewPptx).

import { getToken, escapeHtml, formatSize } from './shared.js';

/** PPTX viewer — no reliable client-side library, show info + download link */
function viewPptx(pane, { absPath, fileName, size }) {
  const tok = getToken();
  const url = `/api/nf-file?path=${encodeURIComponent(absPath)}&token=${encodeURIComponent(tok)}`;
  pane.innerHTML = `
    <div class="canvas-md-viewer" style="display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;color:var(--color-text-muted);">
      <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round" opacity="0.4">
        <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/>
      </svg>
      <div style="text-align:center;">
        <div style="font-weight:600;color:var(--color-text);margin-bottom:4px">${escapeHtml(fileName || 'PowerPoint')}</div>
        <div style="font-size:12px;opacity:0.6">${formatSize(size || 0)}</div>
      </div>
      <a href="${url}" download="${escapeHtml(fileName || 'presentation.pptx')}" style="font-size:13px;color:var(--color-primary);text-decoration:none;padding:6px 16px;border:1px solid var(--color-primary);border-radius:8px;">Download to view</a>
    </div>`;
}

export default {
  name: 'pptx',
  label: 'PPTX',
  extensions: ['.ppt', '.pptx'],
  binary: true,
  priority: 0,
  render: viewPptx,
};
