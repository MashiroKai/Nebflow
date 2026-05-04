// cardRegistry.js — Extensible tool card renderer registry for Nebflow plugins
// Supports exact toolName matching and glob pattern matching.

const exactRenderers = new Map();
const patternRenderers = [];

/** Convert a glob-like pattern to a RegExp. Supports * and ? wildcards. */
function globToRegex(pattern) {
  const escaped = pattern
    .replace(/[.+^${}()|[\]\\]/g, '\\$&')
    .replace(/\*/g, '.*')
    .replace(/\?/g, '.');
  return new RegExp('^' + escaped + '$');
}

/** Register a custom card renderer for a tool name or glob pattern.
 *  @param {string} pattern — exact tool name or glob pattern (e.g. "mcp__postgres__*")
 *  @param {(container: HTMLElement, data: ToolCardData) => void} renderer
 */
export function registerCardRenderer(pattern, renderer) {
  if (typeof pattern !== 'string' || typeof renderer !== 'function') {
    console.warn('[cardRegistry] Invalid registration: pattern and renderer required');
    return;
  }
  if (pattern.includes('*') || pattern.includes('?')) {
    patternRenderers.push({ pattern, regex: globToRegex(pattern), renderer });
  } else {
    exactRenderers.set(pattern, renderer);
  }
}

/** Find a renderer for the given tool name. Returns null if none registered. */
export function findRenderer(toolName) {
  if (exactRenderers.has(toolName)) {
    return exactRenderers.get(toolName);
  }
  for (const entry of patternRenderers) {
    if (entry.regex.test(toolName)) return entry.renderer;
  }
  return null;
}

/** Render a tool card using a registered renderer if available.
 *  Returns true if a renderer was found and executed (even if it threw).
 *  Returns false if no renderer matched (caller should use default rendering).
 */
export function renderWithRegistry(container, data) {
  const renderer = findRenderer(data.label);
  if (renderer) {
    try {
      renderer(container, data);
      return true;
    } catch (e) {
      console.error(`[cardRegistry] Renderer for "${data.label}" failed:`, e);
      // Clear partially-rendered content before fallback
      container.innerHTML = '';
    }
  }
  return false;
}

/** Clear all registered renderers. Used primarily in tests. */
export function clearRenderers() {
  exactRenderers.clear();
  patternRenderers.length = 0;
}
