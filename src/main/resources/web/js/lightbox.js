// lightbox.js — Click-to-zoom image preview for chat bubbles and Canvas viewers.
//
// Trigger surfaces:
//   1. Markdown images tagged .nf-zoom-img by renderMarkdownWithMath
//      (chat bubbles + canvas markdown viewer) — document click delegation.
//   2. Canvas HTML viewer iframes — injected script posts
//      { _nfImagePreview: { src, alt } } to the parent window.
// Close: ESC / backdrop click / close button.

import { t } from './i18n.js';

let overlayEl = null;   // lazily-created singleton
let imgEl = null;
let statusEl = null;
let lastFocus = null;   // focus restore target

function ensureDom() {
  if (overlayEl) return;
  overlayEl = document.createElement('div');
  overlayEl.className = 'nf-lightbox';
  overlayEl.setAttribute('role', 'dialog');
  overlayEl.setAttribute('aria-modal', 'true');

  const closeBtn = document.createElement('button');
  closeBtn.type = 'button';
  closeBtn.className = 'nf-lightbox-close';
  closeBtn.title = t('lightbox.close');
  closeBtn.setAttribute('aria-label', t('lightbox.close'));
  closeBtn.innerHTML =
    '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
    'stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/>' +
    '<line x1="6" y1="6" x2="18" y2="18"/></svg>';
  closeBtn.addEventListener('click', closeLightbox);

  imgEl = document.createElement('img');
  imgEl.className = 'nf-lightbox-img';
  imgEl.alt = '';

  statusEl = document.createElement('div');
  statusEl.className = 'nf-lightbox-status';

  overlayEl.append(closeBtn, imgEl, statusEl);
  // Backdrop click closes; clicks on the image itself do not.
  overlayEl.addEventListener('click', (e) => {
    if (e.target === overlayEl) closeLightbox();
  });
  document.body.appendChild(overlayEl);
}

export function openLightbox(src, alt) {
  if (!src || typeof src !== 'string') return;
  ensureDom();
  overlayEl.setAttribute('aria-label', t('lightbox.ariaLabel'));
  lastFocus = document.activeElement;

  // Reset to loading state
  imgEl.classList.remove('loaded');
  imgEl.removeAttribute('src');
  statusEl.className = 'nf-lightbox-status loading';
  statusEl.textContent = t('lightbox.loading');
  statusEl.style.display = '';

  imgEl.onload = () => {
    imgEl.classList.add('loaded');
    statusEl.style.display = 'none';
  };
  imgEl.onerror = () => {
    statusEl.className = 'nf-lightbox-status error';
    statusEl.textContent = t('lightbox.loadError');
  };
  imgEl.alt = alt || '';
  imgEl.src = src;

  overlayEl.classList.add('on');
  document.body.classList.add('nf-lightbox-open');
  overlayEl.querySelector('.nf-lightbox-close').focus();
}

export function closeLightbox() {
  if (!overlayEl || !overlayEl.classList.contains('on')) return;
  overlayEl.classList.remove('on');
  document.body.classList.remove('nf-lightbox-open');
  imgEl.onload = null;
  imgEl.onerror = null;
  imgEl.removeAttribute('src');   // stop any in-flight load
  if (lastFocus && lastFocus.focus) lastFocus.focus();
  lastFocus = null;
}

export function initLightbox() {
  // 1. Delegated click on tagged markdown images (chat + canvas md viewer)
  document.addEventListener('click', (e) => {
    const img = e.target.closest('img.nf-zoom-img');
    if (!img) return;
    e.preventDefault();
    openLightbox(img.currentSrc || img.src, img.alt);
  });

  // 2. Image clicks forwarded from Canvas HTML viewer iframes
  window.addEventListener('message', (e) => {
    const d = e.data;
    if (!d || !d._nfImagePreview || typeof d._nfImagePreview.src !== 'string') return;
    // srcdoc iframes report an opaque origin ('null'/'') even with
    // allow-same-origin, so a strict e.origin check would silently drop them.
    // Validate the source instead: must be a live Canvas HTML iframe.
    if (e.origin !== window.location.origin) {
      const frames = document.querySelectorAll('.canvas-tab-pane iframe[data-nf-canvas-html]');
      let fromCanvas = false;
      for (const f of frames) { if (/** @type {HTMLIFrameElement} */ (f).contentWindow === e.source) { fromCanvas = true; break; } }
      if (!fromCanvas) return;
    }
    openLightbox(d._nfImagePreview.src, d._nfImagePreview.alt);
  });

  // 3. ESC closes — capture phase so canvas/sidebar ESC handlers don't
  //    also fire while the lightbox is open.
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && overlayEl && overlayEl.classList.contains('on')) {
      e.preventDefault();
      e.stopPropagation();
      closeLightbox();
    }
  }, true);
}
