// lightbox.js — Click-to-zoom image preview for chat bubbles and Canvas viewers.
//
// Trigger surfaces:
//   1. Markdown images tagged .nf-zoom-img by renderMarkdownWithMath
//      (chat bubbles + canvas markdown viewer) — document click delegation.
//   2. Frames this app renders — the Canvas HTML viewer and the chat Card
//      (both inject viewers/shared.js `imgClickScript()`) — post
//      { _nfImagePreview: { src, path, alt } } to the parent window.
// Close: ESC / backdrop click / close button.

import { t } from './i18n.js';
import { reMint, decodePathParam } from './nfTicket.js';

let overlayEl = null;   // lazily-created singleton
let imgEl = null;
let statusEl = null;
let lastFocus = null;   // focus restore target
// Blob URL currently backing the previewed image (see openLightbox), and an
// open-sequence guard so a slow fetch cannot paint over a newer open.
let objectUrl = null;
let openSeq = 0;

/** The proxied local path behind an /api/nf-file URL ('' when not one). */
function nfPathOf(src) {
  if (typeof src !== 'string' || src.indexOf('/api/nf-file?') === -1) return '';
  const m = /[?&]path=([^&]*)/.exec(src);
  if (!m) return '';
  // imgref batch (2026-09-18): the ONE discipline, imported — a bare `+` in a
  // query string means a space (the JVM form encoder's spelling of a space),
  // so `decodeURIComponent` alone named a path that does not exist and the
  // re-mint asked for the wrong file.
  const p = decodePathParam(m[1]);
  return p === null ? '' : p;
}

/** True when `src` is the contentWindow of a frame THIS APP renders: a chat
 *  Card iframe (`data-nf-card-id`) or a Canvas HTML viewer iframe
 *  (`data-nf-canvas-html`).
 *
 *  card-image-zoom batch (2026-09-28): the `_nfImagePreview` channel used to be
 *  accepted from any same-origin window, and from any frame at all when the
 *  origin was opaque (the srcdoc case). Both the Canvas viewer and the Card are
 *  now producers, so the receiving side states the whitelist explicitly — the
 *  SAME discipline `viewers/shared.js` `isRenderedFrame` and `viewers/html.js`
 *  apply to `_nfOpenLocalFile` / `_nfZoomWheel` / `_nfRefPick`. A foreign
 *  window (another app window, a popup, a page that guessed the message shape)
 *  cannot open the viewer, and the Canvas leg stays in the whitelist.
 *  @param {Window|null} src @returns {boolean} */
function isAppFrameSource(src) {
  if (!src) return false;
  const frames = document.querySelectorAll(
    'iframe[data-nf-card-id], .canvas-tab-pane iframe[data-nf-canvas-html]'
  );
  for (const f of frames) {
    if (/** @type {HTMLIFrameElement} */ (f).contentWindow === src) return true;
  }
  return false;
}

/** The path gate: may `src` be opened as a viewer entry?
 *
 *  Rule (card-image-zoom batch): a `path`-BEARING payload is accepted only when
 *  that path is one the SENDER frame actually renders — i.e. it is the decoded
 *  `path=` value of an `<img>` in that frame's own document, which is the set
 *  of references resolved/ticketed at that card's render time. An arbitrary
 *  path handed over from outside never becomes a viewer entry through the
 *  `path` field.
 *
 *  🔴 Scope, measured (round-1 review, 2026-09-28): this gate constrains the
 *  `path` FIELD ONLY. A payload that omits `path` is passed through
 *  unconditionally (`!msg.path`), and `openLightbox` below then derives the
 *  entry from `nfPathOf(src)` — so a frame that is already an app frame CAN
 *  still name a file it does not render by sending a src-only payload
 *  (`{src: '/api/nf-file?path=%2Fetc%2F…'}`, no `path`). That is the
 *  pre-existing `src` fallback (present on e6d2e0e8 and exercised by every
 *  data-URI / remote-URL preview), not an entry this batch added: the batch
 *  NARROWED the channel (a sender must now be an app frame at all, and a
 *  `path` field must be a path that frame renders) without closing this leg.
 *  Closing it — gating src-only payloads by requiring that the frame renders
 *  that same `currentSrc` — is deferred to its own batch; it is not done here
 *  because the data-URI leg of this batch rides on precisely this fallback.
 *
 *  Returns false only for a path the frame does not render, or one that cannot
 *  be verified (fail closed).
 *  @param {Window} win @returns {(msg: {src: string, path?: unknown}) => boolean} */
function senderRendersPath(win) {
  /** @type {Set<string>} */
  let paths;
  try {
    const doc = /** @type {Document|null} */ (/** @type {any} */ (win).document);
    if (!doc) return () => false;
    paths = new Set();
    doc.querySelectorAll('img').forEach((img) => {
      const p = nfPathOf(/** @type {HTMLImageElement} */ (img).currentSrc
        || /** @type {HTMLImageElement} */ (img).getAttribute('src') || '');
      if (p) paths.add(p);
    });
  } catch (e) {
    // Unreadable frame (navigated cross-origin, discarded context): nothing can
    // be verified, so a path-bearing payload is refused rather than trusted.
    return (msg) => !msg.path;
  }
  return (msg) => !msg.path || paths.has(String(msg.path));
}

/** Drop the blob URL backing the current preview (it is ours to free). */
function releaseObjectUrl() {
  if (!objectUrl) return;
  try { URL.revokeObjectURL(objectUrl); } catch (e) { /* already revoked */ }
  objectUrl = null;
}

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

/**
 * @param {string} src
 * @param {string} [alt]
 * @param {string} [path] proxied local path (derived from `src` when omitted)
 */
export function openLightbox(src, alt, path) {
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
  releaseObjectUrl();

  const seq = ++openSeq;
  const fail = () => {
    if (seq !== openSeq) return;
    statusEl.className = 'nf-lightbox-status error';
    statusEl.textContent = t('lightbox.loadError');
  };
  imgEl.onload = () => {
    imgEl.classList.add('loaded');
    statusEl.style.display = 'none';
  };
  imgEl.onerror = fail;
  imgEl.alt = alt || '';

  const localPath = path || nfPathOf(src);
  if (localPath) {
    // 2026-09-11 (C batch, C2-20 / self-correction ⑧): the card's iframe used
    // to hand the parent a fully-formed URL — credential included — which was
    // then written straight into this img element: a live ticket parked in the
    // parent document (serializable, screenshot-able, visible in devtools) and
    // reused later, so a lightbox opened after the TTL just showed `loadError`.
    // Now the parent mints a FRESH ticket for this path, fetches the bytes
    // itself, and shows a `blob:` URL — `img.src` carries no credential at all,
    // while the one request that mattered carried a newly issued one.
    reMint(localPath)
      .then((url) => fetch(url, { credentials: 'same-origin' }))
      .then((resp) => {
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        return resp.blob();
      })
      .then((blob) => {
        if (seq !== openSeq) return;
        objectUrl = URL.createObjectURL(blob);
        imgEl.src = objectUrl;
      })
      .catch(() => { fail(); });
  } else {
    imgEl.src = src;  // remote / data: URL — nothing to mint, nothing to strip
  }

  overlayEl.classList.add('on');
  document.body.classList.add('nf-lightbox-open');
  overlayEl.querySelector('.nf-lightbox-close').focus();
}

export function closeLightbox() {
  if (!overlayEl || !overlayEl.classList.contains('on')) return;
  overlayEl.classList.remove('on');
  document.body.classList.remove('nf-lightbox-open');
  openSeq += 1;                   // invalidate any in-flight ticket fetch
  imgEl.onload = null;
  imgEl.onerror = null;
  imgEl.removeAttribute('src');   // stop any in-flight load
  releaseObjectUrl();             // and drop the blob behind it
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

  // 2. Image clicks forwarded from the frames this app renders: the Canvas HTML
  //    viewer and (card-image-zoom batch, 2026-09-28) the chat Card.
  window.addEventListener('message', (e) => {
    const d = e.data;
    if (!d || !d._nfImagePreview || typeof d._nfImagePreview.src !== 'string') return;
    // srcdoc iframes report an opaque origin ('null'/'') even with
    // allow-same-origin, so a strict e.origin check would silently drop them.
    // Validate the SENDER instead: it must be a live frame this app renders —
    // Canvas HTML viewer or Card iframe. Returning early keeps a foreign window
    // (any window that guessed the message shape) from opening the viewer, for
    // same-origin senders and opaque ones alike.
    if (!isAppFrameSource(/** @type {Window|null} */ (e.source))) return;
    // Path gate: a path-bearing payload must name a reference that sender
    // frame actually renders — never an arbitrary path passed in from outside.
    if (!senderRendersPath(/** @type {Window} */ (e.source))(d._nfImagePreview)) return;
    openLightbox(d._nfImagePreview.src, d._nfImagePreview.alt, d._nfImagePreview.path);
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
