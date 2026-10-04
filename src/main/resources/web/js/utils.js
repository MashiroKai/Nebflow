import state from './state.js';
import { t, getLocale } from './i18n.js';

// ── Exported protocol typedefs (P2-3 core contracts) ──────────────────────

/**
 * One unresolved local file reference reported by a backend tool (Card payload
 * `warnings` / Pop `popFile` item `warnings`). Renderer:
 * cardRegistry.renderCardWarnings.
 * @typedef {Object} FileRefWarning
 * @property {string} [ref] - the verbatim reference as the agent wrote it
 * @property {string|null} [resolvedPath] - resolved filesystem path, when one exists
 * @property {string} [reason] - failure code: not-found / unresolvable / extension-not-allowed / size-exceeded / not-regular-file / other
 * @property {string} [detail] - human-readable reason
 * @property {number} [count] - identical references collapsed into this entry
 */

/**
 * Viewer render context - the payload canvas.js/fileViewers.js hands to a
 * viewer's render(). Binary viewers receive no `content` (backend omits it).
 * @typedef {Object} ViewerContext
 * @property {string} itemType - registry key ('code', 'markdown', 'image', ...)
 * @property {string} [content] - text content (absent for binary files)
 * @property {string} [absPath] - absolute path (resolve relative assets against its dir)
 * @property {string} [fileName]
 * @property {number} [size] - bytes
 * @property {string} [path] - path relative to the workspace root
 * @property {string} [rootPath] - workspace root
 * @property {{pageStart?: number}} [anchor] - #303 C3: document-reference jump target (pdf page)
 * @property {FileRefWarning[]} [warnings] - unresolved local references (Pop's popFile item); rendered as the html-card-warning notice above the frame
 */

/**
 * Viewer protocol object - every module under viewers/ default-exports this
 * shape and fileViewers.js registers it by `name`.
 * @typedef {Object} ViewerProtocol
 * @property {string} name - itemType this viewer handles
 * @property {string} label - human label
 * @property {string[]} extensions - file extensions this viewer claims
 * @property {boolean} binary - true when the backend sends no text content
 * @property {number} priority - higher wins on extension conflicts
 * @property {(pane: HTMLElement, ctx: ViewerContext) => (void | Promise<void>)} render
 */

// === Lottie spinner JSON (rotating ring) ===
export const spinnerJson = {
  "v":"5.7.4","fr":60,"ip":0,"op":60,"w":48,"h":48,"nm":"spinner","ddd":0,"assets":[],
  "layers":[{
    "ddd":0,"ind":1,"ty":4,"nm":"ring","sr":1,
    "ks":{
      "o":{"a":0,"k":100},
      "r":{"a":1,"k":[{"i":{"x":[0.833],"y":[0.833]},"o":{"x":[0.167],"y":[0.167]},"t":0,"s":[0]},{"t":60,"s":[360]}]},
      "p":{"a":0,"k":[24,24,0]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}
    },
    "ao":0,
    "shapes":[{
      "ty":"gr",
      "it":[
        {"ty":"el","d":1,"s":{"a":0,"k":[36,36]},"p":{"a":0,"k":[0,0]}},
        {"ty":"st","c":{"a":0,"k":[1,1,1,1]},"o":{"a":0,"k":100},"w":{"a":0,"k":3},"lc":2,"lj":2,"d":[{"n":"d","v":{"a":0,"k":90}},{"n":"g","v":{"a":0,"k":50}}]},
        {"ty":"tr","p":{"a":0,"k":[0,0]},"a":{"a":0,"k":[0,0]},"s":{"a":0,"k":[100,100]},"r":{"a":0,"k":0},"o":{"a":0,"k":100}}
      ]
    }],
    "ip":0,"op":60,"st":0,"bm":0
  }]
};

// === Lottie spinner initialization ===
const _spinners = {};

export function initSpinner() {
  for (const id of ['lottie-spinner']) {
    const el = document.getElementById(id);
    if (el) {
      _spinners[id] = lottie.loadAnimation({
        container: el, renderer: 'svg', loop: true, autoplay: false,
        animationData: spinnerJson
      });
    }
  }
}

export function playSpinner() {
  const id = state.getActiveView?.()?.dom?.lottieSpinnerEl?.id;
  if (id && _spinners[id]) _spinners[id].play();
}

export function stopSpinner() {
  const id = state.getActiveView?.()?.dom?.lottieSpinnerEl?.id;
  if (id && _spinners[id]) _spinners[id].stop();
}

// === Markdown initialization ===
export function initMarkdown() {
  marked.setOptions({ breaks: true, gfm: true, headerIds: false });
}

// === @-mention rendering (mention-render batch, 2026-09-27) ===
//
// Message text naming a MOUNTED project (`@<name>` at word start, the name
// ending at a whitespace boundary) renders as a blue clickable span that
// opens the project workspace in the side file explorer (the click delegate
// lives in mentionComplete.js). Matching runs INSIDE this shared markdown
// pipeline — the VOICEBLOCK/MATHBLOCK placeholder-restore pattern — so the
// streaming, finished and history-restored faces stay byte-consistent, and
// it reuses the ONE project list + fetch cache owned by mentionComplete.js.
//
// The list reaches this module through a provider (wired once in main.js)
// instead of a direct import: utils.js is imported by 29 files, and a new
// module-graph edge from here into mentionComplete → … → branding.js would
// interact with branding's import-order contract (see branding.js header).
//
// Zero-regression contract: text with NO matching @token renders
// byte-identically — the scan is a pure pass-through when nothing matches,
// and code segments (fenced / inline) are split out before scanning, so
// `@` inside code and mid-word `@` (email addresses) never transform.
//
// LRU correctness: `_mdCache` is keyed by raw text, so the shared list's
// generation is part of the key (see renderMarkdownWithMath) — a list change
// starts a new key space and cached HTML can never contradict the list that
// produced the current spans.

/**
 * A mounted project as exposed by the shared mention list.
 * @typedef {{name: string, workspace?: string}} MentionProject
 */

/** @type {null | (() => {projects: MentionProject[], gen: number})} */
let _mentionProvider = null;

/**
 * Wire the shared project-list readout (main.js, once at boot). The provider
 * is the ONLY data path: this module never fetches and never caches projects.
 * @param {() => {projects: MentionProject[], gen: number}} fn
 */
export function setMentionListProvider(fn) {
  _mentionProvider = fn;
}

/**
 * Current provider state; the safe fallback is an empty list (renders stay
 * plain text until wiring/list arrival).
 * @returns {{projects: MentionProject[], gen: number}}
 */
function mentionListState() {
  if (!_mentionProvider) return { projects: [], gen: 0 };
  try {
    const s = _mentionProvider();
    if (s && Array.isArray(s.projects)) return { projects: /** @type {MentionProject[]} */ (s.projects), gen: s.gen };
  } catch (_) { /* a provider hiccup must never break rendering */ }
  return { projects: [], gen: 0 };
}

const MENTION_WHITESPACE_RE = /\s/;

/**
 * Longest mounted-name match at a word-start '@'. Case-insensitive against
 * the list; `canonical` always carries the list's verbatim name (the span's
 * visible text stays the slice as typed). The match must END at a whitespace
 * boundary (or end of segment), so a longer token like `@Name-x` never
 * matches the name `Name`, and names containing spaces extend across them
 * (full-name longest match, same semantics as the composer's insert).
 *
 * @param {string} text segment being scanned
 * @param {number} at index of the '@'
 * @param {MentionProject[]} projects read-only shared snapshot
 * @returns {{canonical: string, len: number} | null}
 */
function matchMentionAt(text, at, projects) {
  const after = text.slice(at + 1);
  let bestCanonical = '';
  let bestLen = 0;
  for (const p of projects) {
    const name = p && typeof p.name === 'string' ? p.name : '';
    if (!name || after.length < name.length) continue;
    if (after.slice(0, name.length).toLowerCase() !== name.toLowerCase()) continue;
    const tail = after.slice(name.length);
    if (tail && !MENTION_WHITESPACE_RE.test(tail[0])) continue;
    if (name.length > bestLen) {
      bestLen = name.length;
      bestCanonical = name;
    }
  }
  return bestLen > 0 ? { canonical: bestCanonical, len: bestLen } : null;
}

/**
 * Replace word-start `@<mounted-name>` occurrences in a prose segment with
 * MENTIONBLOCK placeholders (collected into `out`), restored as spans after
 * marked.parse. Pure function: no match ⇒ byte-identical string out.
 *
 * @param {string} seg prose segment (code already split out)
 * @param {Array<{canonical: string, slice: string}>} out collection sink
 * @returns {string}
 */
function protectMentions(seg, out) {
  const { projects } = mentionListState();
  if (projects.length === 0) return seg;
  let result = '';
  let i = 0;
  while (i < seg.length) {
    const at = seg.indexOf('@', i);
    if (at < 0) {
      result += seg.slice(i);
      break;
    }
    // Word-start gate: '@' must stand at segment start or after whitespace
    // (parseMentionToken precedent in mentionComplete.js) — 'foo@' never hits.
    const wordStart = at === 0 || MENTION_WHITESPACE_RE.test(seg[at - 1]);
    const hit = wordStart ? matchMentionAt(seg, at, projects) : null;
    if (hit) {
      out.push({ canonical: hit.canonical, slice: seg.slice(at + 1, at + 1 + hit.len) });
      result += seg.slice(i, at) + 'MENTIONBLOCK' + (out.length - 1) + 'END';
      i = at + 1 + hit.len;
    } else {
      result += seg.slice(i, at + 1);
      i = at + 1;
    }
  }
  return result;
}

/**
 * Restore MENTIONBLOCK placeholders as clickable mention spans. Every
 * dynamic value goes through escapeHtml; the attribute value additionally
 * quote-escapes because escapeHtml (a text-node serializer) does not escape
 * `"` and this value lands inside a double-quoted attribute.
 *
 * @param {string} html
 * @param {Array<{canonical: string, slice: string}>} blocks
 * @returns {string}
 */
function restoreMentions(html, blocks) {
  return html.replace(RE_MENTION_RESTORE, (m, idx) => {
    const rec = blocks[parseInt(idx, 10)];
    if (!rec) return m;
    const attr = escapeHtml(rec.canonical).replace(/"/g, '&quot;');
    return '<span class="mention-tag" data-project="' + attr + '">@' + escapeHtml(rec.slice) + '</span>';
  });
}

// === KaTeX math rendering - protect math blocks from Markdown processing ===
// Bounded LRU cache for rendered markdown HTML. History restore and session
// switching re-render identical content; caching avoids repeated
// marked.parse + KaTeX.renderToString work.
// - Key: `${parseVoice}${markedLoaded}${katexLoaded}${locale}|${mentionGen}|${text}` —
//   captures every factor that changes the output (voice parsing, library
//   load state, i18n locale used by the copy button, the @-mention project
//   list generation (mention-render batch: a list change must not let cached
//   HTML contradict the current list), and the raw text).
// - Capacity: 200 entries. Map preserves insertion order; on get we
//   delete+set to move the entry to the end (most recent); on set we evict
//   the first (least recently used) key when over capacity.
const MD_CACHE_CAP = 200;
const _mdCache = new Map();

// ---------- Precompiled hot-path regexes (修法 2, 2026-09-30) ----------
// Every streaming frame re-runs these over the WHOLE accumulated text. A regex
// literal in a function body allocates a fresh RegExp on each evaluation; on a
// per-frame hot path that is pure waste. String.replace/split always start from
// index 0 for a global regex, so hoisting them out is behaviour-preserving.
const RE_VOICE = /<voice>([\s\S]+?)<\/voice>/g;
const RE_CODE_SPLIT = /(```[\s\S]*?(?:```|$)|~~~[\s\S]*?(?:~~~|$)|`+[^`\n]*?`+)/g;
const RE_MATH_DISPLAY_DOLLAR = /\$\$([\s\S]+?)\$\$/g;
const RE_MATH_DISPLAY_BRACKET = /\\\[([\s\S]+?)\\\]/g;
const RE_MATH_INLINE_DOLLAR = /(^|[^\\$])\$(?![\s$])((?:\\\$|[^$\n])*?[^\s$])\$(?!\d)/g;
const RE_MATH_INLINE_PAREN = /(^|[^\\])\\\((\S(?:[^\n]*\S)?)\\\)/g;
// The <pre> wrap + close pair used to run as TWO whole-string replace passes.
// They are disjoint (an opening tag can never be a `</pre>`), so ONE alternation
// pass emits byte-identical output while scanning the HTML only once.
const RE_PRE_TAG = /<pre[^>]*>|<\/pre>/g;
const RE_VOICE_RESTORE = /VOICEBLOCK(\d+)END/g;
const RE_MENTION_RESTORE = /MENTIONBLOCK(\d+)END/g;
const RE_IMG_TAG = /<img\b(?![^>]*\bclass=)/g;

export function renderMarkdownWithMath(text, parseVoice = true, opts) {
  if (!text) return '';
  // Streaming bypass (mem-diagnosis 20260907 D3): per-frame stream renders
  // re-parse the SAME growing text — every intermediate snapshot used to
  // enter this LRU and evict real entries until the hit rate hit 0. Stream
  // renderers pass { cache:false } and go straight to the parser; only
  // finish*/history renders (stable text) populate the cache.
  if (opts && opts.cache === false) return _renderMarkdownWithMath(text, parseVoice);
  const key = `${parseVoice ? 1 : 0}${typeof marked !== 'undefined' ? 1 : 0}${typeof katex !== 'undefined' ? 1 : 0}${getLocale()}|${mentionListState().gen}|${text}`;
  const hit = _mdCache.get(key);
  if (hit !== undefined) {
    _mdCache.delete(key);
    _mdCache.set(key, hit);
    return hit;
  }
  const html = _renderMarkdownWithMath(text, parseVoice);
  _mdCache.set(key, html);
  if (_mdCache.size > MD_CACHE_CAP) {
    _mdCache.delete(_mdCache.keys().next().value);
  }
  return html;
}

function _renderMarkdownWithMath(text, parseVoice) {
  if (typeof marked === 'undefined') return escapeHtml(text);
  // Extract <voice>...</voice> blocks before any markdown processing (only for AI output, not thinking)
  const voiceBlocks = [];
  if (parseVoice) {
    let vp = text.replace(RE_VOICE, (m, content) => {
      voiceBlocks.push(content.trim());
      return `VOICEBLOCK${voiceBlocks.length - 1}END`;
    });
    return _renderMarkdownInternal(vp, voiceBlocks);
  }
  return _renderMarkdownInternal(text, voiceBlocks);
}

function _renderMarkdownInternal(protected_, voiceBlocks) {
  const mathBlocks = [];
  const mentionBlocks = [];
  // Math is protected BEFORE marked.parse and restored AFTER it on the whole
  // HTML string, so the protection must be blind to code regions — otherwise
  // a `$` inside code (shell/JS snippets) is paired up and the KaTeX markup is
  // injected into <code>, corrupting the code text. Code regions are split out
  // first and passed through untouched: odd segments of the split are code
  // (fenced ``` / ~~~ or `inline` spans), even segments are math-eligible prose.
  const protectMath = (seg) => {
    // Note: uses capturing group instead of lookbehind for Safari < 16.4 compatibility
    const add = (math, display) => {
      mathBlocks.push({ display, math: math.trim() });
      return `MATHBLOCK${mathBlocks.length - 1}END`;
    };
    // Display math: $$...$$ and \[...\]
    seg = seg.replace(RE_MATH_DISPLAY_DOLLAR, (m, math) => add(math, true));
    seg = seg.replace(RE_MATH_DISPLAY_BRACKET, (m, math) => add(math, true));
    // Inline math: $...$ and \(...\). The opening delimiter must not be followed
    // by whitespace (or another $) and the closing one must not be preceded by
    // whitespace nor followed by a digit — otherwise currency prose such as
    // "$100 到 $200" pairs up and swallows the text in between.
    seg = seg.replace(RE_MATH_INLINE_DOLLAR,
      (fullMatch, prefix, math) => prefix + add(math, false));
    seg = seg.replace(RE_MATH_INLINE_PAREN,
      (fullMatch, prefix, math) => prefix + add(math, false));
    return seg;
  };
  protected_ = protected_
    .split(RE_CODE_SPLIT)
    .map((seg, i) => (i % 2 === 1 ? seg : protectMentions(protectMath(seg), mentionBlocks)))
    .join('');
  let html = marked.parse(protected_, { headerIds: false });
  // Wrap <pre> blocks with a copy button and close the wrapper in the SAME pass
  // (修法 2: one whole-string scan instead of two). The alternation keeps the
  // original per-tag output byte-for-byte: every `<pre…>` gets the wrap opener +
  // button + the tag itself ($&), every `</pre>` becomes `</pre></div>`.
  html = html.replace(RE_PRE_TAG, (tag) => {
    if (tag === '</pre>') return '</pre></div>';
    return '<div class="code-block-wrap"><button class="code-copy-btn" onclick="window.copyCode(this)" title="' + t('chat.copy') + '"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg><span>' + t('chat.copy') + '</span></button>' + tag;
  });
  // Restore math blocks as KaTeX
  mathBlocks.forEach((block, i) => {
    const token = `MATHBLOCK${i}END`;
    if (typeof katex !== 'undefined') {
      try {
        const rendered = katex.renderToString(block.math, {
          displayMode: block.display,
          throwOnError: false,
          strict: 'ignore',
          trust: true
        });
        html = html.replace(token, rendered);
      } catch (e) {
        html = html.replace(token, block.display ? `$$${block.math}$$` : `$${block.math}$`);
      }
    } else {
      html = html.replace(token, block.display ? `$$${block.math}$$` : `$${block.math}$`);
    }
  });
  // Restore voice blocks as clickable green spans
  html = html.replace(RE_VOICE_RESTORE, (m, idx) => {
    const i = parseInt(idx);
    const vtext = voiceBlocks[i] || '';
    return '<span class="voice-block" data-voice-index="' + i + '">' + escapeHtml(vtext) + '</span>';
  });
  // Restore mention blocks as clickable blue spans (shared-list longest match).
  html = restoreMentions(html, mentionBlocks);
  // Tag images for lightbox zoom (click → full preview). Skip imgs that
  // already carry a class (raw HTML in markdown) to avoid duplicate attrs.
  html = html.replace(RE_IMG_TAG, '<img class="nf-zoom-img"');
  return html;
}

// === Plain-text face with mention spans (user messages) ===
/**
 * Append PLAIN text into `parent`, wrapping word-start `@<mounted-name>`
 * occurrences in .mention-tag spans. Used by the plain-text user-message
 * faces (live send: chat.js renderUserBubble; history restore: persistence.js)
 * which deliberately bypass markdown — user text must never gain markdown
 * interpretation, so this helper only splits text and adds spans. Everything
 * is built via createTextNode/setAttribute/textContent: no HTML string, no
 * injection surface. No match ⇒ a single text node, byte-identical output to
 * a plain textContent assignment.
 *
 * @param {HTMLElement} parent
 * @param {string} text
 */
export function appendTextWithMentions(parent, text) {
  const { projects } = mentionListState();
  if (!projects.length) {
    parent.appendChild(document.createTextNode(text));
    return;
  }
  let rest = text;
  for (;;) {
    const at = rest.indexOf('@');
    if (at < 0) break;
    const wordStart = at === 0 || MENTION_WHITESPACE_RE.test(rest[at - 1]);
    const hit = wordStart ? matchMentionAt(rest, at, projects) : null;
    if (!hit) {
      // Keep scanning after this non-matching '@'.
      parent.appendChild(document.createTextNode(rest.slice(0, at + 1)));
      rest = rest.slice(at + 1);
      continue;
    }
    if (at > 0) parent.appendChild(document.createTextNode(rest.slice(0, at)));
    const span = document.createElement('span');
    span.className = 'mention-tag';
    span.setAttribute('data-project', hit.canonical);
    span.textContent = '@' + rest.slice(at + 1, at + 1 + hit.len);
    parent.appendChild(span);
    rest = rest.slice(at + 1 + hit.len);
  }
  if (rest) parent.appendChild(document.createTextNode(rest));
}

// === One-shot DOM enhancement after the shared list first arrives ===
/**
 * Wrap plain-text `@<mounted-name>` hits in already-rendered bubbles with the
 * same .mention-tag spans the render pipeline emits. Runs ONCE per page load,
 * wired in main.js via mentionComplete.onProjectsArrival: bubbles rendered
 * while the list was still empty show plain text; this makes the styling
 * appear without any user action once the list lands.
 *
 * Pure text-node surgery — no re-render, no scroll writes — so scroll
 * position, msgScrollAnchor compensation and the follow latch are untouched.
 * Scope: message bubbles of the primary chat and agent popups. Skipped
 * subtrees: pre/code, voice blocks, KaTeX, existing mention spans, buttons,
 * svg, script/style — mention styling must never leak into non-prose.
 */
export function enhanceMentionSpansInDom() {
  const { projects } = mentionListState();
  if (!projects.length) return;
  const bubbles = document.querySelectorAll('#chat .bubble, .flow-agent-modal .bubble');
  bubbles.forEach((bubble) => {
    const walker = document.createTreeWalker(bubble, NodeFilter.SHOW_TEXT, {
      /**
       * @param {Node} node
       * @returns {number}
       */
      acceptNode(node) {
        const parent = node.parentElement;
        if (!parent) return NodeFilter.FILTER_REJECT;
        if (parent.closest('pre, code, .voice-block, .mention-tag, .katex, button, svg, script, style')) {
          return NodeFilter.FILTER_REJECT;
        }
        return NodeFilter.FILTER_ACCEPT;
      },
    });
    // Advance BEFORE mutating: splitText/replaceWith below detach nodes, so
    // the walker's continuation must be captured first.
    /** @type {Node | null} */
    let node = walker.nextNode();
    while (node) {
      const textNode = /** @type {Text} */ (node);
      node = walker.nextNode();
      wrapMentionsInTextNode(textNode, projects);
    }
  });
}

/**
 * Splice every mention hit of ONE text node into spans, right-to-left so
 * earlier hit indices stay valid while splitting.
 *
 * @param {Text} textNode
 * @param {MentionProject[]} projects read-only shared snapshot
 */
function wrapMentionsInTextNode(textNode, projects) {
  const data = textNode.data;
  /** @type {Array<{start: number, canonical: string, len: number}>} */
  const hits = [];
  let scan = 0;
  while (scan < data.length) {
    const idx = data.indexOf('@', scan);
    if (idx < 0) break;
    const wordStart = idx === 0 || MENTION_WHITESPACE_RE.test(data[idx - 1]);
    const hit = wordStart ? matchMentionAt(data, idx, projects) : null;
    if (hit) {
      hits.push({ start: idx, canonical: hit.canonical, len: hit.len });
      scan = idx + 1 + hit.len;
    } else {
      scan = idx + 1;
    }
  }
  for (let h = hits.length - 1; h >= 0; h--) {
    const { start, canonical, len } = hits[h];
    textNode.splitText(start + 1 + len); // tail (rest of the node) becomes a sibling
    const mid = textNode.splitText(start); // mid = the '@<slice>' text node
    const span = document.createElement('span');
    span.className = 'mention-tag';
    span.setAttribute('data-project', canonical);
    span.textContent = mid.data;
    mid.replaceWith(span);
  }
}

// === HTML escaping ===
export function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

// === Lucide icons - subtree-scoped replacement ===
// lucide.createIcons() always scans document.querySelectorAll('[data-lucide]')
// (full-DOM walk, 50-200ms per call on large pages). Hot paths (message
// render, list refresh) should call createIconsIn(container) instead, which
// only walks the container subtree. Mimics lucide's own replaceElement:
// kebab name → PascalCase lookup, merges element attrs, applies
// "lucide lucide-<name>" classes. Falls back to global createIcons() when
// called without a root.
export function createIconsIn(root) {
  if (typeof lucide === 'undefined' || !lucide.icons || !lucide.createElement) return;
  if (!root) { lucide.createIcons(); return; }
  const els = [];
  if (root.matches && root.matches('[data-lucide]')) els.push(root);
  els.push(...root.querySelectorAll('[data-lucide]'));
  for (const el of els) {
    const name = el.getAttribute('data-lucide');
    if (!name) continue;
    // Same conversion lucide uses: kebab-case → PascalCase
    const pascal = name.replace(/(\w)(\w*)(_|-|\s*)/g, (m, c, p) => c.toUpperCase() + p.toLowerCase());
    const icon = lucide.icons[pascal];
    if (!icon) continue;
    const [tag, iconAttrs, children] = icon;
    /** @type {Record<string, string>} */
    const elAttrs = {};
    for (const a of el.attributes) elAttrs[a.name] = a.value;
    /** @type {Record<string, string>} */
    const attrs = { ...iconAttrs, 'data-lucide': name, ...elAttrs };
    const classes = ['lucide', `lucide-${name}`, ...(elAttrs.class ? elAttrs.class.split(' ') : [])]
      .map(c => c.trim()).filter(Boolean);
    attrs.class = [...new Set(classes)].join(' ');
    const svg = lucide.createElement([tag, attrs, children]);
    el.replaceWith(svg);
  }
}

// Shorthand alias for escapeHtml
export function esc(s) {
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}

// === Diff formatting (unified diff) ===
export function formatDiff(content) {
  if (!content) return null;
  const lines = content.split('\n');
  // Detect unified diff: must contain at least one @@ ... @@ hunk header
  const isUnified = lines.some(l => /^@@\s+-\d+(?:,\d+)?\s+\+\d+(?:,\d+)?\s+@@/.test(l));
  if (!isUnified) return null;

  let oldLine = 0, newLine = 0;
  const html = lines.map(line => {
    // Parse hunk header to reset line counters (don't render it)
    const hm = line.match(/^@@\s+-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?\s+@@/);
    if (hm) { oldLine = +hm[1]; newLine = +hm[2]; return ''; }
    // Added line - uses new file line number
    if (line.startsWith('+')) {
      const n = newLine++;
      return '<div class="diff-line"><span class="diff-lineno">' + n + '</span><span class="diff-content diff-add">' + esc(line) + '</span></div>';
    }
    // Removed line - uses old file line number
    if (line.startsWith('-')) {
      const n = oldLine++;
      return '<div class="diff-line"><span class="diff-lineno">' + n + '</span><span class="diff-content diff-del">' + esc(line) + '</span></div>';
    }
    // Context line (space prefix) - both counters advance
    oldLine++; newLine++;
    return '<div class="diff-line"><span class="diff-lineno">' + (oldLine - 1) + '</span><span class="diff-content">' + esc(line) + '</span></div>';
  }).filter(Boolean).join('');
  return '<pre>' + html + '</pre>';
}

// === Tool label/summary localization ===

/**
 * Localize a tool label (the first line of tool cards).
 * Backend sends English like "Read(file.scala)" or "Bash\n  (npm run build)".
 * This replaces the tool name portion with the localized equivalent.
 */
export function localizeToolLabel(label) {
  if (!label) return label;
  const firstNewline = label.indexOf('\n');
  const firstLine = firstNewline >= 0 ? label.slice(0, firstNewline) : label;
  const rest = firstNewline >= 0 ? label.slice(firstNewline) : '';

  // "[MCP] toolName" pattern
  if (firstLine.startsWith('[MCP] ')) {
    const mcpName = firstLine.slice(6);
    const localPrefix = t('tool.MCP');
    if (localPrefix !== 'tool.MCP') {
      return '[' + localPrefix + '] ' + mcpName + rest;
    }
    return label;
  }

  // Match "ToolName(args)" - captures tool name and everything inside parens
  const m = firstLine.match(/^(\w+)\((.*)\)$/s);
  if (m) {
    const toolName = m[1];
    const args = m[2];
    const localName = t('tool.' + toolName);
    if (localName !== 'tool.' + toolName) {
      return localName + '(' + args + ')' + rest;
    }
    return label;
  }

  // Bare tool name without parens, e.g. "Bash" (when label is "Bash\n  (cmd)")
  // or "Card" (when label is "Card\n  (title)")
  const bareMatch = firstLine.match(/^(\w+)$/);
  if (bareMatch) {
    const localName = t('tool.' + bareMatch[1]);
    if (localName !== 'tool.' + bareMatch[1]) {
      return localName + rest;
    }
  }

  return label;
}

/**
 * Localize a tool result summary string.
 * Backend sends English like "3 lines", "File created", "2 files matched".
 */
export function localizeToolSummary(summary, toolLabel) {
  if (!summary) return summary;

  // Exact match patterns (most specific first)
  const exactMap = {
    'File created': () => t('tool.result.fileCreated'),
    'File updated': () => t('tool.result.fileUpdated'),
    'Edited': () => t('tool.result.edited'),
    'Created': () => t('tool.result.created'),
    'No output': () => t('tool.result.noOutput'),
    'No matches': () => t('tool.result.noMatches'),
    'No files found': () => t('tool.result.noFilesFound'),
    'Timed out': () => t('tool.result.timedOut'),
    'Blocked': () => t('tool.result.blocked'),
    'Interactive blocked': () => t('tool.result.interactiveBlocked'),
    'Background': () => t('tool.result.background'),
    'Sandbox bypassed': () => t('tool.result.sandboxBypassed'),
    'Auto-background': () => t('tool.result.autoBackground'),
  };

  if (exactMap[summary]) {
    const result = exactMap[summary]();
    if (result !== summary) return result;
  }

  // Regex patterns
  // "N lines"
  let rm = summary.match(/^(\d+) lines$/);
  if (rm) return t('tool.result.lines', { n: rm[1] });

  // "N lines of output"
  rm = summary.match(/^(\d+) lines of output$/);
  if (rm) return t('tool.result.linesOfOutput', { n: rm[1] });

  // "N lines fetched"
  rm = summary.match(/^(\d+) lines fetched$/);
  if (rm) return t('tool.result.linesFetched', { n: rm[1] });

  // "N files found"
  rm = summary.match(/^(\d+) files? found$/);
  if (rm) return t('tool.result.filesFound', { n: rm[1] });

  // "N files matched"
  rm = summary.match(/^(\d+) files? matched$/);
  if (rm) return t('tool.result.filesMatched', { n: rm[1] });

  // "N files with matches"
  rm = summary.match(/^(\d+) files with matches$/);
  if (rm) return t('tool.result.filesWithMatches', { n: rm[1] });

  // "N matches"
  rm = summary.match(/^(\d+) matches$/);
  if (rm) return t('tool.result.matches', { n: rm[1] });

  // "N lines added" / "N lines removed" / combo "Na added, Nr removed"
  rm = summary.match(/^(\d+) lines? added$/);
  if (rm) return t('tool.result.lineAdded', { n: rm[1] });
  rm = summary.match(/^(\d+) lines? removed$/);
  if (rm) return t('tool.result.lineRemoved', { n: rm[1] });

  // "Na added, Nr removed"
  rm = summary.match(/^(\d+) lines? added, (\d+) lines? removed$/);
  if (rm) return t('tool.result.lineAdded', { n: rm[1] }) + ', ' + t('tool.result.lineRemoved', { n: rm[2] });

  // "N of M lines"
  rm = summary.match(/^(\d+) of (\d+) lines$/);
  if (rm) return t('tool.result.ofLines', { current: rm[1], total: rm[2] });

  // "Task #N created"
  rm = summary.match(/^Task #(\d+) created$/);
  if (rm) return t('tool.result.taskCreated', { id: rm[1] });

  return summary;
}

// === Delegate prompt builder ===
export function buildDelegatePromptHtml(inputJson) {
  if (!inputJson) return '';
  try {
    const inp = typeof inputJson === 'string' ? JSON.parse(inputJson) : inputJson;
    if (inp.prompt) {
      return '<div class="delegate-prompt"><span class="delegate-prompt-label">Prompt</span><pre class="tool-body-pre">' + esc(inp.prompt) + '</pre></div>';
    }
  } catch (e) { /* not a delegate tool */ }
  return '';
}

// === Tool detail builder ===
export function buildToolDetail(inputJson, label) {
  if (!inputJson) return '';
  try {
    const input = JSON.parse(inputJson);
    const parts = [];
    if (input.file_path) parts.push('file: ' + input.file_path);
    if (input.command) parts.push('cmd: ' + input.command);
    if (input.url) parts.push('url: ' + input.url);
    if (input.query) parts.push('query: ' + input.query);
    if (input.pattern) parts.push('pattern: ' + input.pattern);
    if (input.path) parts.push('path: ' + input.path);
    if (parts.length === 0) return '';
    return '<div class="detail-cmd">' + esc(parts.join('  ')) + '</div>';
  } catch (e) {
    return '';
  }
}

// === Tool card click handler (click to toggle body) ===
export function attachToolClick(card) {
  card.addEventListener('click', () => {
    const body = card.querySelector('.body');
    if (body) body.classList.toggle('open');
  });
}

// === Scroll helpers ===
//
// ── A-branch convergence (fpo-scroll, 2026-09-11) ────────────────────────
// Single source of truth for every "is the viewport still at the bottom?"
// decision in the chat / agent-stream family. Before this, four ad-hoc
// thresholds (40 / 60 / 80 / 100 px) were spread over main.js, utils.js,
// chat.js, turnGroup.js, cardRegistry.js and both agent popups — and two
// bypasses (chat.js renderInjectedBubble, main.js's second-pass timeout)
// yanked the viewport to the bottom regardless of where the user was
// reading. The whole family now reads NEAR_BOTTOM_PX / isNearBottom().

/**
 * NEAR_BOTTOM_PX — one rendered single-line message bubble, MEASURED on a
 * real isolated instance (1440x900, deviceScaleFactor 2, real seeded
 * history), not guessed:
 *
 *   single-line ai bubble height   44.47px  (n=16; min = p25 = median = p75 = max = 44.47)
 *   single-line row height         65.47px  (bubble + footer/copy badge)
 *   row-to-row pitch               75.47px  (median over 49 consecutive rows)
 *
 * The unit is the author's "一行气泡高度" (the bubble itself, not the row
 * box), floored to the integer 44.
 *
 * Readings: the fpo batch row-px-baseline.json (baseline
 * resources) and the fpo batch fixed-results.json
 * (`m1_measure`) — the fixed arm re-measures the same values.
 *
 * Semantics: distance-to-bottom below this = "still at the bottom" → the
 * stream keeps following and no pill is shown. Above it the user is reading
 * history: nothing scrolls under them and the "↓ N" pill appears instead.
 */
export const NEAR_BOTTOM_PX = 44;

/** Distance (px) between the viewport bottom and the content bottom. */
export function scrollBottomDistance(el) {
  if (!el) return Infinity;
  return el.scrollHeight - el.scrollTop - el.clientHeight;
}

/** The single near-bottom judgement (within one message line of the bottom). */
export function isNearBottom(el) {
  return scrollBottomDistance(el) < NEAR_BOTTOM_PX;
}

/**
 * Refresh the follow-intent latch (`view.stream.scrollSnapped`) from live
 * geometry. This is the scroll-listener hook for every view — the latch is a
 * *follow intent* snapshot, so it must only be written where the geometry is
 * authoritative (a real scroll event, or an explicit programmatic jump to the
 * bottom). See the A-branch path table for the staleness analysis.
 *
 * B4 (2026-10-04): ONE geometry probe per scroll event. The judgement computed
 * here doubles as `syncScrollPill`'s input whenever the latch element is the
 * same element the pill measures (`view.dom.chat` — true for the primary
 * window and both popups, which all pass their chat container). The pill sync
 * only re-probes when it is called with no judgement of its own (mutation
 * observer / post-write call sites), where the geometry really may have moved.
 *
 * 🔴 Registration discipline: this is the view's ONLY scroll listener. Call
 * sites must not add a second scroll listener that also calls it — that would
 * put two probes back on every scroll event (the B4 defect).
 */
export function updateScrollSnapped(view, el) {
  if (!view || !view.stream || !el) return;
  const atBottom = isNearBottom(el);
  view.stream.scrollSnapped = atBottom;
  syncScrollPill(view, el === view.dom?.chat ? atBottom : undefined);
}

/**
 * The single follow decision used by every content-append site:
 * follow intent (the latch, captured before the DOM grew) OR live geometry.
 * A site that captures `snapped` at schedule time keeps its own capture (see
 * chat.js rafScrollChat) — everything else calls this.
 */
export function shouldFollowBottom(view, el) {
  if (!view || !view.stream || !el) return false;
  return view.stream.scrollSnapped === true || isNearBottom(el);
}

export function smartScroll() {
  const view = state.getActiveView ? state.getActiveView() : null;
  if (!view) return;
  const chat = view.dom.chat;
  // Capture the follow intent BEFORE the pending render mutates the DOM —
  // the same schedule-time capture the rAF scroll paths use.
  const follow = shouldFollowBottom(view, chat);
  // UI-G (author 2026-10-03 19:59): a subagent / flow popup window is a static
  // display — it does not schedule scroll frames. One synchronous write instead
  // of an animation frame: same final scroll position (克制 ≠ 破功), no rAF on
  // that window. This is the single choke point every auto-scroll goes through
  // (chat.js alone calls it 23 times), so no call site needs its own check.
  if (typeof view.motionEnabled === 'function' && !view.motionEnabled()) {
    if (follow || isNearBottom(chat)) chat.scrollTop = chat.scrollHeight;
    syncScrollPill(view);
    return;
  }
  requestAnimationFrame(() => {
    if (follow || isNearBottom(chat)) chat.scrollTop = chat.scrollHeight;
    syncScrollPill(view);
  });
}

// ── "↓ N new messages" pill ─────────────────────────────────────────────
// Row-level counter of what arrived since the user left the bottom. Only
// REAL new messages count — thinking blocks, tool cards, system rows and
// sub-agent process rows are excluded (2026-09-13 口径; the criterion lives
// in countsAsRealMessage below). State is PER VIEW
// (view.stream.scrollPill), never a global singleton: the primary window
// and every agent popup keep their own count, element and observer.

/** view -> pill record. The WeakMap is the canonical home so a session
 *  switch (resetStream replaces view.stream) cannot orphan the DOM node. */
const _pillRecords = new WeakMap();

/** B4 (2026-10-04): the element a view's pill geometry is measured against —
 *  the same element `initScrollFollow` attaches to. Held here because
 *  `initScrollFollow` may run AFTER `updateScrollSnapped` (main.js attaches the
 *  window's scroll listener above its `initScrollFollow` call), so the pill
 *  itself cannot know the element at decision time. WeakMap ⇒ no leak. */
const _viewChats = new WeakMap();

function viewChat(view) {
  if (!view) return null;
  const el = _viewChats.get(view) || view.dom?.chat || null;
  if (el && !_viewChats.has(view)) _viewChats.set(view, el);
  return el;
}

function pillRecord(view) {
  let rec = _pillRecords.get(view);
  if (!rec) {
    rec = { el: null, count: 0, atBottom: true, obs: null };
    _pillRecords.set(view, rec);
  }
  // Mirror onto the view's own stream state (per-view, as specified).
  if (view.stream && view.stream.scrollPill !== rec) view.stream.scrollPill = rec;
  return rec;
}

/** Where the pill lives: #main for the primary window (position:relative,
 *  hosts #chat + the absolutely positioned #input-area), the modal for an
 *  agent popup (its chat container's closest .flow-agent-modal). */
function pillHost(view) {
  const chat = view && view.dom && view.dom.chat;
  if (!chat) return null;
  const modal = chat.closest ? chat.closest('.flow-agent-modal') : null;
  if (modal) return modal;
  const main = document.getElementById('main');
  if (main && main.contains(chat)) return main;
  return null;
}

function ensureScrollPill(view) {
  const rec = pillRecord(view);
  if (rec.el && rec.el.isConnected) return rec.el;
  const host = pillHost(view);
  if (!host) return null;
  const el = document.createElement('button');
  el.type = 'button';
  el.className = 'scroll-new-pill hidden';
  el.setAttribute('aria-live', 'polite');
  el.addEventListener('click', () => scrollPillDismiss(view));
  host.appendChild(el);
  rec.el = el;
  return el;
}

/** Park the pill just above whatever sits at the bottom of the view:
 *  #input-area (primary, dynamic height) or the popup footer bar. */
function positionScrollPill(view, el) {
  const host = el.parentElement;
  if (!host) return;
  if (host.classList.contains('flow-agent-modal')) {
    const foot = host.querySelector('.flow-agent-footer');
    el.style.bottom = `${(foot ? foot.offsetHeight : 0) + 12}px`;
  } else {
    const inputArea = document.getElementById('input-area');
    el.style.bottom = `${(inputArea ? inputArea.offsetHeight : 54) + 12}px`;
  }
}

/** Re-park a visible pill (called by main.js's #input-area ResizeObserver so a
 *  growing multi-line input can never be covered by the pill). */
export function refreshScrollPill(view) {
  const rec = view ? _pillRecords.get(view) : null;
  if (rec && rec.el && rec.el.isConnected && !rec.el.classList.contains('hidden')) {
    positionScrollPill(view, rec.el);
  }
}

// ── "real new message" criterion (2026-09-13 口径裁定) ───────────────────
// Author 2026-09-12 20:36 (verbatim): 「新消息提醒的数字统计，目前应该是把思考
// 过程，工具都算了，只统计真的新消息。也就是没被自动收起的那些消息。」 The pill
// is the only user-visible counter that swallowed process noise: one turn
// (thinking + 5 tool calls + final reply) read as 9.
//
// COUNTED — what the author actually receives as a message:
//   .row.user (real user message)            W1
//   .row.ai   (assistant visible reply)      W1
//   .row.card-content (card deliverable)     D4
//   .row.error (failed-turn terminal)        D5
//   .row.user + .bubble.injected (Mail / flow / Team bubbles)  D2
//
// NOT COUNTED — process noise / system traffic:
//   .thinking-row  思考过程                   W2
//   .tool          tool calls AND tool results (same class)  W2
//   .system        system rows                D5
//   .notice        system bubbles + compaction status cards
//                  (chat.js renderSystemBubble / buildCompactCardRow) —
//                  the same 系统提醒 family as .system, see D5
//   .agent-row     sub-agent process rows     D3
//   .bg-task-row   background-task receipts — excluded ONLY when the marker
//                  is present; no render-side marker is added for this (D6)
//
// D7: the judgement is STATIC (class-based, evaluated at append time) — the
// count never falls back when a turn later collapses, so the number stays
// monotonic (I-5). D9: the existing "row taller than the viewport" guard in
// initScrollFollow is untouched.
//
// The `.thinking-row` / `.tool` half restates the canonical tuck set from
// `turnGroup.js#isTuckableRow` (`.row.tool` + `.row.ai.thinking-row`, the
// "被自动收起" predicate). It is restated here rather than imported because
// (a) that symbol is module-private to turnGroup.js and (b) importing
// turnGroup.js from utils.js would close a new
// `utils.js → turnGroup.js → chat.js → utils.js` ESM cycle in a module that
// 29 files import (chat.js pulls `NEAR_BOTTOM_PX` out of here). Keep the two
// sets in sync if the tuck set ever changes.
export function countsAsRealMessage(row) {
  if (!row || !row.classList || !row.classList.contains('row')) return false;
  if (row.classList.contains('thinking-row')) return false;
  if (row.classList.contains('tool')) return false;
  if (row.classList.contains('system')) return false;
  if (row.classList.contains('notice')) return false;
  if (row.classList.contains('agent-row')) return false;
  if (row.classList.contains('bg-task-row')) return false;
  if (row.querySelector && row.querySelector('.bg-task-row')) return false;
  return true;
}

/** Recompute pill visibility + count from live geometry for one view.
 *
 *  `atBottom`, when passed, is a judgement already made this frame against the
 *  view's own chat element (see updateScrollSnapped) — reuse it instead of
 *  probing the geometry a second time. B4: at most one probe per scroll event.
 *  Callers that have no judgement of their own (mutation observer, post-write
 *  re-sync) omit it and pay the probe. */
export function syncScrollPill(view, atBottom) {
  if (!view || !view.dom || !view.dom.chat) return;
  const rec = pillRecord(view);
  const el = ensureScrollPill(view);
  if (!el) return;
  if (atBottom === undefined) atBottom = isNearBottom(viewChat(view) || view.dom.chat);
  rec.atBottom = atBottom;
  if (atBottom) rec.count = 0; // reached the bottom (any cause) → nothing unseen
  const show = !atBottom && rec.count > 0;
  el.classList.toggle('hidden', !show);
  if (show) {
    const label = t('chat.newMessages', { n: rec.count });
    if (el.textContent !== label) el.textContent = label;
    positionScrollPill(view, el);
  }
}

/** Pill click: jump to the bottom, clear the counter, resume following. */
function scrollPillDismiss(view) {
  const rec = pillRecord(view);
  const chat = view.dom.chat;
  chat.scrollTop = chat.scrollHeight;
  rec.count = 0;
  if (view.stream) view.stream.scrollSnapped = true;
  syncScrollPill(view);
}

/**
 * Attach the per-view scroll-follow machinery: row-level MutationObserver +
 * the view's ONE scroll listener. Idempotent. Call once per view (primary at
 * startup, popups when their ChatView is created).
 *
 * 🔴 B4 (2026-10-04) — this is the ONLY place a scroll listener is attached for
 * a view. The listener refreshes the follow-intent latch and drives the ↓ N
 * pill from the judgement it already made, i.e. ONE geometry probe per scroll
 * event for both. Before the fix each view had TWO listeners (this one probing
 * for the pill + the call site's own probing for the latch) while the decision
 * sites ran three times per event — 6 forced layout reads per wheel step, the
 * measured B4 defect (`scrollHeightWidth` 360 / 60 steps vs the ≤120 criterion).
 *
 * `opts.onScroll` is the call site's pagination gate (main.js / bgAgentPopup.js
 * scroll-up → older page), run on the same event after the follow update. It is
 * registered with the view's one listener; a later call without it cannot add a
 * second listener (guarded by `rec.obs`).
 */
export function initScrollFollow(view, opts = {}) {
  const chat = view && view.dom && view.dom.chat;
  if (!chat) return;
  const rec = pillRecord(view);
  if (rec.obs) return;
  _viewChats.set(view, chat);
  rec.atBottom = isNearBottom(chat);
  rec.obs = new MutationObserver((mutations) => {
    let addedRows = 0;
    const viewportH = chat.clientHeight || 0;
    for (const m of mutations) {
      // Only rows appended at the END are new messages. `previousSibling` is
      // null for `chat.prepend(...)` (scroll-up pagination loading OLDER
      // messages) — those rows sit above the reading position and must never
      // count as "new".
      if (m.previousSibling === null) continue;
      for (const n of m.addedNodes) {
        if (!(n instanceof HTMLElement) || !n.classList.contains('row')) continue;
        // Only REAL new messages count: thinking blocks, tool calls/results,
        // system rows, sub-agent rows and marked background-task receipts are
        // process noise the turn strips anyway (countsAsRealMessage above).
        if (!countsAsRealMessage(n)) continue;
        // A row that is at least a viewport tall cannot have been below the
        // user's reading position (a thinking placeholder filled in with
        // streamed content reaches that size) — it never counts as "a message
        // you have not seen".
        if (viewportH > 0 && n.getBoundingClientRect().height >= viewportH) continue;
        addedRows++;
      }
    }
    // Rows that arrive while the user is away from the bottom are the only
    // ones that count ("since leaving the bottom"). `rec.atBottom` is the
    // pre-append snapshot: scroll events and the post-append rAF below keep
    // it current, so a big card appended *while pinned* is not counted.
    if (addedRows > 0 && rec.atBottom === false) rec.count += addedRows;
    syncScrollPill(view);
    // After this frame's own auto-scroll (scheduled at the append site) has
    // run, re-read the geometry: a landing at the bottom clears the count.
    requestAnimationFrame(() => syncScrollPill(view));
  });
  rec.obs.observe(chat, { childList: true });
  // The view's ONE scroll listener (B4). Latch + pill share this probe; the
  // caller's pagination gate rides the same event.
  chat.addEventListener('scroll', () => {
    updateScrollSnapped(view, chat);
    if (typeof opts.onScroll === 'function') opts.onScroll();
  }, { passive: true });
  syncScrollPill(view);
}

// === Syntax highlighting for code (highlight.js) ===
const EXT_TO_LANG = {
  js:'javascript',jsx:'javascript',ts:'typescript',tsx:'typescript',mjs:'javascript',cjs:'javascript',
  py:'python',rb:'ruby',rs:'rust',go:'go',java:'java',scala:'scala',kt:'kotlin',kts:'kotlin',
  swift:'swift',c:'c',h:'c',cpp:'cpp',hpp:'cpp',cc:'cpp',cxx:'cpp',
  cs:'csharp',fs:'fsharp',php:'php',pl:'perl',pm:'perl',
  html:'html',htm:'html',xhtml:'html',css:'css',scss:'scss',less:'less',
  xml:'xml',svg:'xml',json:'json',yaml:'yaml',yml:'yaml',toml:'ini',ini:'ini',
  sql:'sql',sh:'bash',bash:'bash',zsh:'bash',fish:'bash',
  md:'markdown',tex:'latex',sty:'latex',cls:'latex',
  vue:'vue',svelte:'svelte',dart:'dart',lua:'lua',r:'r',
  gradle:'groovy',groovy:'groovy',dockerfile:'dockerfile',Dockerfile:'dockerfile',
  makefile:'makefile',mk:'makefile',cmake:'cmake',
  erl:'erlang',hrl:'erlang',ex:'elixir',exs:'elixir',
  clj:'clojure',cljs:'clojure',edn:'clojure',
  proto:'protobuf',graphql:'graphql',gql:'graphql',
  diff:'diff',patch:'diff'
};

export function detectLangFromLabel(label) {
  if (!label) return null;
  // Try to extract file extension from Read label: "Read(file.ext)" or "(/path/to/file.ext)"
  const extMatch = label.match(/\.([a-zA-Z0-9]+)(?:\s|\)|,|$)/);
  if (extMatch) {
    const ext = extMatch[1].toLowerCase();
    return EXT_TO_LANG[ext] || null;
  }
  // Try to extract from file path in second line: '  ("/path/to/file.ext")'
  const pathMatch = label.match(/\/([^/]+\.[a-zA-Z0-9]+)"/);
  if (pathMatch) {
    const ext = pathMatch[1].split('.').pop().toLowerCase();
    return EXT_TO_LANG[ext] || null;
  }
  return null;
}

export function highlightCode(code, label) {
  if (!code || typeof hljs === 'undefined') return null;
  if (code.length < 10) return null;
  // Strip Read tool line number prefixes ("1\t", " 42\t") before passing to hljs
  const clean = code.replace(/^[ \t]*\d+\t/gm, '');
  if (clean.length < 10) return null;
  const lang = detectLangFromLabel(label || '');
  try {
    let result;
    if (lang) {
      if (hljs.getLanguage(lang)) {
        result = hljs.highlight(clean, { language: lang, ignoreIllegals: true });
      } else {
        result = hljs.highlightAuto(clean);
      }
    } else {
      result = hljs.highlightAuto(clean);
    }
    if (result && result.value) {
      return '<pre class="tool-body-pre hljs"><code class="hljs language-' + (result.language || '') + '">' + result.value + '</code></pre>';
    }
  } catch(e) {
    // Fall through to plain text
  }
  return null;
}

/**
 * Render tool content with appropriate highlighting.
 * Returns HTML string for the body, or null if no special rendering applies.
 * Priority: diff > syntax highlight (any tool) > null (plain text)
 */
export function renderHighlightedContent(content, label) {
  if (!content) return null;
  const diffHtml = formatDiff(content);
  if (diffHtml) return diffHtml;
  const hlHtml = highlightCode(content, label);
  if (hlHtml) return hlHtml;
  return null;
}

// === Code copy button handler ===
window.copyCode = function(btn) {
  const wrap = btn.closest('.code-block-wrap');
  const pre = wrap && wrap.querySelector('pre');
  const code = pre ? pre.textContent : '';
  if (!code) return;
  navigator.clipboard.writeText(code).then(() => {
    const span = btn.querySelector('span');
    const orig = span.textContent;
    span.textContent = t('chat.copied');
    btn.classList.add('copied');
    setTimeout(() => {
      span.textContent = orig;
      btn.classList.remove('copied');
    }, 2000);
  }).catch(() => {
    // Fallback: select the code text
    if (pre) {
      const range = document.createRange();
      range.selectNodeContents(pre);
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
    }
  });
};

// === Message copy button factory ===
// Creates a hover-revealed copy button for chat messages (user & AI).
// `text` is the raw text to copy (plain text for user, markdown for AI).
// Only one copy button shows the "copied" checkmark at a time - clicking a
// new one immediately clears the previous, so the checkmark always reflects
// the most recently copied content.
const COPY_SVG = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';
const CHECK_SVG = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>';

let _activeCopyBtn = null;
let _activeCopyTimeout = null;

/** R4（作者裁定 = 要提示）：复制失败**就地**兜底 —— 按钮转错误色 + 一行
 *  `aria-live` 文案（2s 自清）。三面共用一份实现（主对话 `createMsgCopyButton`
 *  的 catch 分支、好友面 messages.js 的 catch 分支都调它）。
 *  🔴 不用 toast（toast 属既有 UI 裁定面，本批边界外）；文案来自 i18n
 *  `chat.copyFailed`（禁硬编码中文）。幂等：同一按钮失败态未清前不叠加第二行。 */
export function markCopyFailed(btn) {
  if (!btn || btn.dataset.nfCopyFailed === '1') return;
  btn.dataset.nfCopyFailed = '1';
  btn.classList.add('copy-failed');
  const host = btn.parentElement;
  if (!host) return;
  const live = document.createElement('span');
  live.className = 'copy-failed-live';
  live.setAttribute('role', 'status');
  live.setAttribute('aria-live', 'polite');
  live.textContent = t('chat.copyFailed');
  host.appendChild(live);
  setTimeout(() => {
    live.remove();
    btn.classList.remove('copy-failed');
    btn.dataset.nfCopyFailed = '0';
  }, 2000);
}

export function createMsgCopyButton(text) {
  const btn = document.createElement('button');
  btn.className = 'msg-copy';
  btn.title = t('chat.copy');
  btn.innerHTML = COPY_SVG;
  btn.onclick = async (e) => {
    e.stopPropagation();
    // Clear any previously active copy button so only one shows checkmark
    if (_activeCopyBtn && _activeCopyBtn !== btn) {
      _activeCopyBtn.innerHTML = COPY_SVG;
      _activeCopyBtn.classList.remove('copied');
    }
    clearTimeout(_activeCopyTimeout);
    try {
      await navigator.clipboard.writeText(text);
      btn.innerHTML = CHECK_SVG;
      btn.classList.add('copied');
      _activeCopyBtn = btn;
      _activeCopyTimeout = setTimeout(() => {
        btn.innerHTML = COPY_SVG;
        btn.classList.remove('copied');
        _activeCopyBtn = null;
        _activeCopyTimeout = null;
      }, 1500);
    } catch (err) {
      console.error('[chat] Copy failed:', err);
      markCopyFailed(btn);   // R4：失败可见兜底（原为「UI 零变化」）
    }
  };
  return btn;
}


/** Check whether a session/agent ID belongs to a background sub-agent
 *  (Delegate sub-agent: "delegate-<agent>-<uuid8>"; SubTask worker:
 *  "subtask-<uuid8>"; Project node: "node-<uuid8>"; Project dispatcher:
 *  "dispatcher-<uuid8>" - backend naming protocol). Single point of truth so
 *  future prefixes only need one change. Used for bg-agent popup routing.
 *  #28 可观测接线: node-/dispatcher- 会话进 subagent 面板（Processing 状态 +
 *  工具调用过程, 与 Delegate/SubTask 同一可观测性标准）。 */
export function isBgAgentId(id) {
  return typeof id === 'string' && (
    id.startsWith('delegate-') || id.startsWith('subtask-') ||
    id.startsWith('subagent-') || id.startsWith('workflow-') ||
    id.startsWith('node-') || id.startsWith('dispatcher-')
  );
}

/** Mid-segment truncation for tool labels (2026-09-03 footer toolline fix):
 *  keep head + '…' + tail within `max` chars. Labels are `Tool(param)` — the
 *  head carries the tool name + param lead, the tail carries the filename /
 *  verb end of the param; both ends identify the call, the middle (long
 *  directory prefixes, URL queries) is the expendable part. Pure string
 *  function — no DOM, safe to assert in node. */
export function truncateMiddle(str, max = 96, tailKeep = 24) {
  const s = String(str ?? '');
  if (s.length <= max) return s;
  const headLen = max - tailKeep - 1; // 1 = the '…' itself
  if (headLen < 1) return s.slice(0, max);
  return s.slice(0, headLen) + '…' + (tailKeep > 0 ? s.slice(-tailKeep) : '');
}

// ── Shared minute-grid ticker (perf-481 A5: stagger / coalesce the cheap polls) ──
//
// WHY a shared ticker instead of one `setInterval(60_000)` per module: several
// independent cheap refreshes (project list fallback, task-panel relative
// timestamps) each owned their own 1-minute interval. Their phases were set at
// module load, so they drift apart and the page emits a burst of unrelated
// requests at arbitrary instants. Two modules on the same nominal period should
// share ONE timer, and that timer should sit on the wall-clock minute boundary
// so a refresh aligns with the user-visible "minute" instead of an arbitrary
// offset from page load.
//
// The ticker is LATE-ARMED: it schedules a self-correcting `setTimeout` to the
// next minute boundary, runs the subscribers once, then re-arms. That keeps the
// timer phase stable across the drift a plain `setInterval` accumulates.
//
// Visibility: a page that is not visible must do ZERO work — the timer stops
// (not just short-circuits) while `hidden` and re-arms on `visibilitychange`,
// so a backgrounded tab holds no pending timer and issues no requests.
//
// The subscriber set is the single source of truth for "who refreshes on the
// minute"; no module may create a second 1-minute polling timer.
/** @type {Set<() => void>} */
const minuteTickSubscribers = new Set();
let minuteTickTimer = null;

/** True while the shared minute ticker is armed (test/observability face). */
export function minuteTickerArmed() {
  return minuteTickTimer !== null;
}

function minuteTickDelay(now = Date.now()) {
  const ms = 60_000 - (now % 60_000);
  // 0-length delay would busy-spin exactly on the boundary; nudge by 1ms.
  return ms === 60_000 ? 1 : ms;
}

function armMinuteTick() {
  if (typeof document !== 'undefined' && document.hidden) {
    // Background ⇒ hold NO pending timer at all (a short-circuit inside the
    // callback would still wake the page once per minute).
    if (minuteTickTimer !== null) {
      clearTimeout(minuteTickTimer);
      minuteTickTimer = null;
    }
    return;
  }
  if (minuteTickTimer !== null) return;
  minuteTickTimer = setTimeout(() => {
    minuteTickTimer = null;
    // Re-arm FIRST so a throwing subscriber can never stop the cadence.
    armMinuteTick();
    for (const cb of [...minuteTickSubscribers]) {
      try { cb(); } catch { /* one subscriber must not break the others */ }
    }
  }, minuteTickDelay());
}

function onVisibilityForMinuteTick() {
  if (typeof document !== 'undefined' && !document.hidden) {
    armMinuteTick();
    // A tab waking up should not wait for the next boundary to catch up.
    for (const cb of [...minuteTickSubscribers]) {
      try { cb(); } catch { /* one subscriber must not break the others */ }
    }
  } else {
    armMinuteTick(); // hidden ⇒ drops the pending timer
  }
}

/**
 * Subscribe to the shared minute-grid tick (runs once per wall-clock minute,
 * only while the document is visible).
 * @param {() => void} cb
 * @returns {() => void} unsubscribe
 */
export function onMinuteTick(cb) {
  if (typeof cb !== 'function') return () => {};
  const first = minuteTickSubscribers.size === 0;
  minuteTickSubscribers.add(cb);
  if (first) {
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', onVisibilityForMinuteTick);
    }
    armMinuteTick();
  }
  return () => {
    minuteTickSubscribers.delete(cb);
    if (minuteTickSubscribers.size === 0 && minuteTickTimer !== null) {
      clearTimeout(minuteTickTimer);
      minuteTickTimer = null;
      if (typeof document !== 'undefined') {
        document.removeEventListener('visibilitychange', onVisibilityForMinuteTick);
      }
    }
  };
}
