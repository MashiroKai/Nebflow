// executorIcons.js — per-executor logo marks for the unified delegate face
// (executor-registry batch, 2026-10-03).
//
// Inline-SVG-in-JS is the house convention (canvas close button, errorIcon in
// errorRecovery.js). These are simplified brand-evocative geometric marks, not
// official assets: nebflow = orbit mark, zcode = Z monogram, claude-code =
// Anthropic-style starburst, codex = OpenAI-style knot hexagon, hermes = wing
// monogram. Swapping in official paths later only touches this file.

/**
 * SVG mark for an executor id. Returns an HTML string.
 * @param {string} id executor id (nebflow | zcode | claude-code | codex | hermes)
 * @param {number} [size=18] rendered square size in px
 */
export function executorIcon(id, size = 18) {
  const s = size;
  const wrap = (inner, vb = 24) =>
    `<svg class="executor-icon executor-icon-${esc(id)}" width="${s}" height="${s}" viewBox="0 0 ${vb} ${vb}" fill="none" aria-hidden="true">${inner}</svg>`;
  switch (id) {
    case 'nebflow':
      return wrap(
        `<circle cx="12" cy="12" r="9.2" stroke="url(#exg-nebflow)" stroke-width="2.2"/>
         <circle cx="12" cy="12" r="3.1" fill="url(#exg-nebflow)"/>
         <path d="M12 2.8 A 9.2 9.2 0 0 1 21.2 12" stroke="#8b5cf6" stroke-width="2.6" stroke-linecap="round"/>
         <defs><linearGradient id="exg-nebflow" x1="3" y1="3" x2="21" y2="21"><stop stop-color="#8b5cf6"/><stop offset="1" stop-color="#6366f1"/></linearGradient></defs>`
      );
    case 'zcode':
      return wrap(
        `<rect x="3" y="3" width="18" height="18" rx="5" stroke="#10b981" stroke-width="2"/>
         <path d="M8.2 8.4h7.6L8.6 15.6h7.2" stroke="#10b981" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>`
      );
    case 'claude-code':
      return wrap(
        // Anthropic-style 8-ray starburst
        `<g stroke="#d97757" stroke-width="2.4" stroke-linecap="round">
           <path d="M12 3.5v5"/><path d="M12 15.5v5"/><path d="M3.5 12h5"/><path d="M15.5 12h5"/>
           <path d="M6 6l3.1 3.1"/><path d="M14.9 14.9L18 18"/><path d="M18 6l-3.1 3.1"/><path d="M9.1 14.9L6 18"/>
         </g>`
      );
    case 'codex':
      return wrap(
        // OpenAI-style knot approximation: interlocked hexagon arcs
        `<g stroke="currentColor" stroke-width="1.9" stroke-linecap="round">
           <path d="M12 3.4l7.2 4.15v8.3L12 20l-7.2-4.15v-8.3z"/>
           <path d="M12 7.4l3.9 2.25v4.5L12 16.4l-3.9-2.25v-4.5z"/>
           <path d="M12 3.4v4M19.2 7.55l-3.3 1.9M19.2 15.85L16 13.95M12 20v-3.6M4.8 15.85L8 13.95M4.8 7.55l3.3 1.9"/>
         </g>`
      );
    case 'hermes':
      return wrap(
        // Wing monogram: H with a swept wing stroke
        `<g stroke="#0ea5e9" stroke-width="2.2" stroke-linecap="round">
           <path d="M7.5 4.5v15"/><path d="M16.5 4.5v15"/><path d="M7.5 12h9"/>
           <path d="M16.5 7.2c2.6 0 4.2-.9 5-2.2" stroke-width="1.9"/>
         </g>`
      );
    default:
      // unknown executor: neutral gear-dot
      return wrap(
        `<circle cx="12" cy="12" r="8" stroke="currentColor" stroke-width="2" stroke-dasharray="3 3"/>
         <circle cx="12" cy="12" r="2.4" fill="currentColor"/>`
      );
  }
}

function esc(str) {
  return String(str ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}
