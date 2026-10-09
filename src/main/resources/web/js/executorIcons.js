// executorIcons.js — per-executor logo marks for the unified delegate face
// (executor-registry batch, 2026-10-03; icon-native batch 2026-10-10).
//
// Asset policy (icon-native batch): where an official/native mark exists it is
// referenced as a LOCAL, self-hosted asset — no hand-drawn approximations, zero
// runtime external links. Provenance registry: web/vendor/logos/README.md.
//   nebflow      → shipped canonical pixel-logo pair css/logo-{dark,bright}-4.png
//                  (the same artwork the header avatar serves — index.html
//                  <picture> pattern; dark scheme → white art, light → dark art)
//   claude-code  → vendor/logos/claude-code.svg (simple-icons slug `claudecode`)
//   codex        → vendor/logos/codex.svg (simple-icons v13 slug `openai`,
//                  fill→currentColor for theme parity)
//   zcode        → NO public official logo (simple-icons 404) — legacy in-house
//                  Z monogram kept until an official asset exists (declared)
//   hermes       → NO applicable official logo (simple-icons `hermes` is an
//                  unrelated brand) — legacy in-house wing monogram kept (declared)
//
// Inline-SVG-in-JS remains only for the fallback/legacy marks (house convention:
// canvas close button, errorIcon in errorRecovery.js). Every consumer (tree row
// taskList.js, chain badge delegateChainView.js, picker executorsPanel.js) goes
// through executorIcon() — swapping the mark here propagates to all faces.

/** Local asset marks: executor id → asset reference (self-hosted, no CDN). */
const BRAND_ASSET = {
  nebflow: { dark: 'css/logo-dark-4.png', light: 'css/logo-bright-4.png' },
  'claude-code': { src: 'vendor/logos/claude-code.svg' },
  codex: { src: 'vendor/logos/codex.svg' },
};

/**
 * SVG/img mark for an executor id. Returns an HTML string.
 * @param {string} id executor id (nebflow | zcode | claude-code | codex | hermes)
 * @param {number} [size=18] rendered square size in px
 */
export function executorIcon(id, size = 18) {
  const s = size;
  const key = String(id || '');
  const asset = BRAND_ASSET[key];
  const wrap = (inner, vb = 24) =>
    `<span class="executor-icon executor-icon-${esc(key)}" aria-hidden="true">${inner}</span>`;
  if (asset) {
    // Official mark = local <img>/<picture> (asset bytes live in the repo; the
    // renderer fetches them same-origin — zero external links).
    const img = (src) =>
      `<img src="${src}" alt="" width="${s}" height="${s}" draggable="false">`;
    const body = asset.dark
      ? // Theme pair (header pattern): art color follows prefers-color-scheme.
        `<picture><source media="(prefers-color-scheme: dark)" srcset="${asset.dark}">${img(asset.light)}</picture>`
      : img(asset.src);
    return wrap(body);
  }
  const svg = (inner, vb = 24) =>
    `<svg width="${s}" height="${s}" viewBox="0 0 ${vb} ${vb}" fill="none" aria-hidden="true">${inner}</svg>`;
  switch (key) {
    case 'zcode':
      // Legacy in-house mark — no public official ZCode logo found (declared).
      return wrap(
        svg(
          `<rect x="3" y="3" width="18" height="18" rx="5" stroke="#10b981" stroke-width="2"/>
           <path d="M8.2 8.4h7.6L8.6 15.6h7.2" stroke="#10b981" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>`
        )
      );
    case 'hermes':
      // Legacy in-house mark — no applicable official logo (declared).
      return wrap(
        svg(
          `<g stroke="#0ea5e9" stroke-width="2.2" stroke-linecap="round">
             <path d="M7.5 4.5v15"/><path d="M16.5 4.5v15"/><path d="M7.5 12h9"/>
             <path d="M16.5 7.2c2.6 0 4.2-.9 5-2.2" stroke-width="1.9"/>
           </g>`
        )
      );
    default:
      // unknown executor: neutral gear-dot
      return wrap(
        svg(
          `<circle cx="12" cy="12" r="8" stroke="currentColor" stroke-width="2" stroke-dasharray="3 3"/>
           <circle cx="12" cy="12" r="2.4" fill="currentColor"/>`
        )
      );
  }
}

function esc(str) {
  return String(str ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}
