// viewers/image.js — Image viewer (<img> served via /api/nf-file).
// Migrated verbatim from fileViewers.js (viewImage).

import { getToken, escapeHtml, formatSize } from './shared.js';

/** Image viewer — <img> served via /api/nf-file */
function viewImage(pane, { absPath, fileName, size }) {
  if (!absPath) {
    pane.innerHTML = '<div class="canvas-error">Cannot display image: no absolute path available.</div>';
    return;
  }
  const tok = getToken();
  const url = `/api/nf-file?path=${encodeURIComponent(absPath)}&token=${encodeURIComponent(tok)}`;
  const img = new Image();
  img.alt = fileName || 'image';
  // Prevent native image drag. <img> is draggable by default — an accidental
  // drag on the image starts a native DnD session, and a wedged session
  // (e.g. drop outside the window, Safari quirks) suppresses click events
  // page-wide, which presents as "canvas tabs stop responding".
  img.draggable = false;
  img.onerror = () => {
    let reason = 'File may be corrupted or not a valid image format.';
    if (size && size < 500) reason += ` (File is only ${size} bytes — likely an error page or placeholder, not a real image.)`;
    pane.innerHTML = `<div class="canvas-error">${reason}<br>Path: ${escapeHtml(absPath)}</div>`;
  };
  img.onload = () => {
    if (!pane.isConnected) return;  // tab closed while the image was loading
    pane.innerHTML = '';
    const wrap = document.createElement('div');
    wrap.className = 'canvas-image-viewer';
    const info = document.createElement('div');
    info.className = 'image-info';
    info.textContent = `${fileName || ''} — ${formatSize(size || 0)}`;
    wrap.appendChild(info);
    wrap.appendChild(img);
    pane.appendChild(wrap);
    pane.classList.add('scrollable');
  };
  img.src = url;
}

export default {
  name: 'image',
  label: 'Image',
  extensions: ['.png', '.jpg', '.jpeg', '.gif', '.svg', '.webp', '.bmp', '.ico', '.avif', '.tiff', '.tif'],
  binary: true,
  priority: 0,
  render: viewImage,
};
