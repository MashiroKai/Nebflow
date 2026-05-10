// cardRegistry.js — Extensible tool card renderer registry for Nebflow plugins
// Supports exact toolName matching, glob pattern matching, and multiple renderers per pattern.

const exactRenderers = new Map(); // Map<string, renderer[]>
const patternRenderers = [];      // { pattern, regex, renderer }[]

/** Convert a glob-like pattern to a RegExp. Supports * and ? wildcards. */
function globToRegex(pattern) {
  const escaped = pattern
    .replace(/[.+^${}()|[\]\\]/g, '\\$&')
    .replace(/\*/g, '.*')
    .replace(/\?/g, '.');
  return new RegExp('^' + escaped + '$');
}

/** Register a custom card renderer for a tool name or glob pattern.
 *  Multiple renderers can be registered for the same pattern — they are tried in order
 *  until one returns a non-false value.
 *  @param {string} pattern — exact tool name or glob pattern (e.g. "mcp__postgres__*")
 *  @param {(container: HTMLElement, data: ToolCardData) => boolean} renderer
 */
export function registerCardRenderer(pattern, renderer) {
  if (typeof pattern !== 'string' || typeof renderer !== 'function') {
    console.warn('[cardRegistry] Invalid registration: pattern and renderer required');
    return;
  }
  if (pattern.includes('*') || pattern.includes('?')) {
    patternRenderers.push({ pattern, regex: globToRegex(pattern), renderer });
  } else {
    const list = exactRenderers.get(pattern);
    if (list) list.push(renderer);
    else exactRenderers.set(pattern, [renderer]);
  }
}

/** Find all renderers for the given tool name.
 *  If toolName contains newlines (e.g. "Bash\n  (cmd...)"), only the first line is used.
 */
function findRenderers(toolName) {
  const name = toolName.split('\n', 2)[0];
  const results = exactRenderers.get(name) || [];
  for (const entry of patternRenderers) {
    if (entry.regex.test(name)) results = results.concat(entry.renderer);
  }
  return results;
}

/** Render a tool card using registered renderers if available.
 *  Tries each matching renderer in order. If a renderer returns false, the container
 *  is cleared and the next renderer is tried.
 *  Returns true if a renderer succeeded. Returns false if none matched (use default rendering).
 */
export function renderWithRegistry(container, data) {
  const renderers = findRenderers(data.label);
  if (renderers.length === 0) return false;
  for (const renderer of renderers) {
    try {
      const result = renderer(container, data);
      if (result === false) {
        container.innerHTML = '';
        continue; // try next renderer
      }
      return true;
    } catch (e) {
      console.error(`[cardRegistry] Renderer for "${data.label}" failed:`, e);
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
