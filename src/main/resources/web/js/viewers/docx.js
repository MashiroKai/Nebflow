// viewers/docx.js — DOCX viewer (mammoth.js, lazily loaded).
// Migrated verbatim from fileViewers.js (viewDocx).

import { escapeHtml } from './shared.js';
import { ticketUrl } from '../nfTicket.js';

/** DOCX viewer — fetch binary, convert to HTML via mammoth.js */
async function viewDocx(pane, { absPath, fileName }) {
  pane.innerHTML = `<div class="canvas-md-viewer" style="display:flex;align-items:center;justify-content:center;color:var(--color-text-muted);opacity:0.5;">Loading document...</div>`;
  if (!absPath) {
    pane.innerHTML = `<div class="canvas-error">No file path provided for DOCX viewer.</div>`;
    return;
  }
  const url = await ticketUrl(absPath);
  try {
    const resp = await fetch(url);
    if (!resp.ok) {
      const detail = await resp.text().catch(() => '');
      throw new Error(`HTTP ${resp.status}${detail ? ': ' + detail : ''}`);
    }
    const arrayBuffer = await resp.arrayBuffer();
    // Load mammoth lazily.
    // mammoth.min.js is a UMD bundle: if Monaco's AMD loader (window.define)
    // is active, the UMD wrapper registers there instead of setting window.mammoth.
    // Fetch as text and eval via new Function with define/module/exports shadowed
    // so the UMD wrapper falls through to the window global branch.
    if (typeof window.mammoth === 'undefined') {
      try {
        const scriptResp = await fetch('vendor/mammoth.min.js');
        const code = await scriptResp.text();
        // Shadow AMD/CommonJS globals so UMD sets window.mammoth
        const exec = new Function('define', 'module', 'exports', code);
        exec(undefined, undefined, undefined);
      } catch (e) {
        throw new Error('Failed to load mammoth.js: ' + e.message);
      }
      if (typeof window.mammoth === 'undefined') {
        throw new Error('mammoth.js loaded but global not initialized');
      }
    }
    const result = await window.mammoth.convertToHtml(
      { arrayBuffer },
      {
        convertImage: window.mammoth.images.imgElement(function(image) {
          return image.read("base64").then(function(imageBuffer) {
            const supported = ['image/png', 'image/jpeg', 'image/jpg', 'image/gif',
              'image/svg+xml', 'image/webp', 'image/bmp', 'image/x-icon'];
            if (supported.includes(image.contentType)) {
              return { src: "data:" + image.contentType + ";base64," + imageBuffer };
            }
            // Unsupported format (EMF/WMF/TIFF from Windows Word)
            return {
              src: 'data:image/svg+xml;utf8,' + encodeURIComponent(
                `<svg xmlns="http://www.w3.org/2000/svg" width="200" height="60"><rect width="100%" height="100%" fill="%23f0f0f0" rx="8"/><text x="100" y="35" text-anchor="middle" font-size="12" fill="%23999">${image.contentType} — browser preview not supported</text></svg>`
              )
            };
          });
        })
      }
    );
    const html = result.value || '<p style="color:var(--color-text-muted)">Document is empty.</p>';
    pane.innerHTML = `<div class="canvas-md-viewer">${html}</div>`;
    pane.classList.add('scrollable');
  } catch (err) {
    pane.innerHTML = `<div class="canvas-error">Failed to render DOCX: ${escapeHtml(err.message)}</div>`;
  }
}

export default {
  name: 'docx',
  label: 'DOCX',
  extensions: ['.doc', '.docx'],
  binary: true,
  priority: 0,
  render: viewDocx,
};
