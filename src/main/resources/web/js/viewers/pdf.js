// viewers/pdf.js — PDF viewer (<iframe> served via /api/nf-file).
// Migrated verbatim from fileViewers.js (viewPdf).

import { getToken, escapeHtml } from './shared.js';

/** PDF viewer — <iframe> served via /api/nf-file */
function viewPdf(pane, { absPath, fileName }) {
  const tok = getToken();
  const url = `/api/nf-file?path=${encodeURIComponent(absPath)}&token=${encodeURIComponent(tok)}`;
  pane.innerHTML = `
    <div class="canvas-pdf-viewer">
      <iframe src="${url}" title="${escapeHtml(fileName || 'PDF')}" style="width:100%;height:100%;border:none;"></iframe>
    </div>`;
}

export default {
  name: 'pdf',
  label: 'PDF',
  extensions: ['.pdf'],
  binary: true,
  priority: 0,
  render: viewPdf,
};
