import state from './state.js';
import { t, getLocale } from './i18n.js';// 本机设备显示名读数（设备角标本名批 2026-10-27）：deviceRoster 单一读取点
// （花名册 self + configData.selfDevice 两源缓存），utils 只消费其同步读数面。
import { currentSelfDeviceName } from './deviceRoster.js';
// voice 块补封批 2026-10-31（作者令「封存所有会话的 voice 块——应随全双工 TTS
// 一起封存，漏网」）：<voice> TTS 输出族与全双工同 flag（voiceEnabled，缺席封存）。
// featureFlags 只依赖 state.js，无导入环。
import { voiceEnabled } from './featureFlags.js';
// output-block-gate 批（作者令「把 output 块相关的内容先关闭……」）：
// outputBlockEnabled 读 nebflow.json `outputBlock.enabled`（缺席 = 关），
// 与渠道剥离面同一键。同模块、无导入环。
import { outputBlockEnabled } from './featureFlags.js';
// 胶囊单实现批 2026-11-06（作者令「回到底部的按钮不要自己造，要跟新消息通知/
// 搜索聊天记录跳转使用同一套」）：↓N / 回到底部 / 搜索回跳 = 同一工厂、同一类名
// `.scroll-new-pill`、同一形态声明（css/chat.css）。
import { createScrollPill } from './scrollPill.js';
import { countUnread } from './unreadCount.js';

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
 * @property {(pane: HTMLElement) => void} [dispose] - OPTIONAL teardown (阅读/
 *   编辑体验优化批 2026-11-01). Called by fileViewers.disposeViewer() exactly
 *   once per mount: before the same pane is re-rendered and when the tab is
 *   torn down (canvas.js disposeTabEditor → every close path). Implement it
 *   for anything a pane does NOT own: document/window listeners, timers,
 *   ResizeObserver/IntersectionObserver/MutationObserver, pdf.js documents and
 *   workers, viewer-created object URLs. Element-scoped listeners (including
 *   pane descendants) die with the DOM and need no cleanup. Omitting it is
 *   legal and means "nothing outside the pane survives".
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

// === External-input safe mode (fm-md-social batch, 2026-11-02) ==============
//
// The agent sessions (chat.js / persistence.js / askDock / canvas md viewer …)
// all render through renderMarkdownWithMath. The friend/group/device faces
// (messages.js fillBubble) rendered bodies as raw textContent — agent-generated
// structured text arrived as visible `**`/`- ` syntax. Fix: wire the SAME
// pipeline into those faces (one renderer, no second implementation), with a
// `safe` mode for EXTERNAL input: a friend/group/device peer's message is
// untrusted — a full trust tier above agent output — so raw HTML, non-http(s)
// link schemes and KaTeX trust extensions are refused there.
//
// Safe-mode contract (per-call via opts.safe on renderMarkdownWithMath):
//   · marked `html` tokens (block + inline raw HTML) are ESCAPED to literal
//     text — `<img onerror>` / `<script>` can never enter the DOM;
//   · `link` hrefs are scheme-allow-listed (http/https/mailto; protocol-
//     relative forced to https); everything else degrades to label text;
//     allowed links carry target=_blank rel="noopener noreferrer nofollow"
//     and class md-ext (the face confirms before navigating — messages.js
//     delegate, modal.js __showConfirm);
//   · `image` tokens never auto-fetch remote bytes: rendered as an md-ext
//     link chip to the sanitized src (tracking-pixel refusal), alt text kept;
//   · KaTeX runs with trust:false (no \href/\includegraphics escapes).
// Everything else — code fences + hljs, tables, lists, math, mermaid
// placeholders, output blocks, mention/msg-token chips, copy buttons — is the
// unmodified shared pipeline (single source; agent faces keep trust mode).
//
// 🔴 Fail-closed: safe mode with no usable marked.Renderer degrades the WHOLE
// text to escapeHtml output — never falls through to the trusting path.

/** Scheme allow-list for external links (lowercase; exact scheme match). */
const SAFE_LINK_SCHEMES = ['http', 'https', 'mailto'];
/** Defensive cap: no href longer than this is emitted at all. */
const SAFE_HREF_MAX_LEN = 2048;

/** Sanitize one external href. Returns the URL to emit or null (= refuse →
 *  the anchor degrades to its label text). Bare relative/anchor hrefs are
 *  refused: inside a chat bubble they would resolve against the app origin. */
function safeExternalHref(raw) {
  const s = String(raw || '').trim();
  if (!s || s.length > SAFE_HREF_MAX_LEN) return null;
  const m = s.match(/^([a-zA-Z][a-zA-Z0-9+.-]*):/);
  if (m) return SAFE_LINK_SCHEMES.includes(m[1].toLowerCase()) ? s : null;
  if (s.startsWith('//')) return 'https:' + s; // protocol-relative → pin https
  return null;
}

/** @type {any} */
let _safeRenderer = null;

/** Build (once) the hardened marked renderer. Null = library shape unknown →
 *  callers fail closed. */
function safeMarkedRenderer() {
  if (_safeRenderer !== null || typeof marked === 'undefined') return _safeRenderer;
  const Base = /** @type {any} */ (marked).Renderer;
  if (typeof Base !== 'function') return null;
  const r = new Base();
  // Raw HTML (block + inline): escaped to visible literal text. marked v12
  // hands over either a string (string form) or a token object (loose form).
  r.html = (html) => escapeHtml(typeof html === 'string' ? html : String((html && html.text) || ''));
  // Links: allow-list + hardened anchor. Label renders inline-token content
  // when the token form carries tokens, else the plain label.
  r.link = (href, title, text) => {
    const t = /** @type {any} */ (href && typeof href === 'object' ? href : { href, title, text });
    const url = safeExternalHref(t.href);
    const label = t.tokens && t.tokens.length
      ? /** @type {any} */ (r.parser ? r.parser.parseInline(t.tokens) : escapeHtml(String(t.text || t.href || '')))
      : escapeHtml(String(t.text != null ? t.text : t.href || ''));
    if (!url) return label; // refused → plain label text, no anchor at all
    const titleAttr = t.title ? ` title="${escapeHtml(String(t.title))}"` : '';
    return `<a href="${escapeHtml(url)}"${titleAttr} target="_blank" rel="noopener noreferrer nofollow" class="md-ext">${label}</a>`;
  };
  // Images: never auto-fetch. Allowed-src images render as an external link
  // chip (same confirm gate as links); refused srcs degrade to alt text.
  r.image = (href, title, text) => {
    const t = /** @type {any} */ (href && typeof href === 'object' ? href : { href, title, text });
    const url = safeExternalHref(t.href);
    const alt = String(t.text || t.alt || '');
    if (!url) return escapeHtml(alt);
    const titleAttr = t.title ? ` title="${escapeHtml(String(t.title))}"` : '';
    return `<a href="${escapeHtml(url)}"${titleAttr} target="_blank" rel="noopener noreferrer nofollow" class="md-ext md-ext-img">${escapeHtml(alt || url)}</a>`;
  };
  _safeRenderer = r;
  return r;
}

/**
 * Markdown-ness detector for the fm faces: bodies WITHOUT any markdown
 * syntax keep the byte-identical plain-text face (A3 regression gate);
 * bodies WITH syntax route through the shared markdown pipeline. Single
 * source, imported by messages.js — no second implementation.
 *
 * The guard list mirrors what the pipeline actually transforms (headings,
 * lists, bold/italic/strike, fences, inline code, links, images, tables,
 * blockquotes, math). Single `*` emphasis and single-`_` italics are
 * deliberately NOT detected (multiplication / snake_case false positives);
 * `$x$` inline math uses the same guarded shape the renderer pairs on.
 * @param {string} text
 * @returns {boolean}
 */
export function looksLikeMarkdown(text) {
  if (!text || typeof text !== 'string') return false;
  return /(^|\n)[ \t]{0,3}(#{1,6}[ \t]|\*|-|\+|[>]|\d{1,9}[.)][ \t])/.test(text) // heading / list / quote
    || /(^|\n)[ \t]{0,3}(```|~~~)/.test(text)                                     // fence
    || /\*\*[^*\n]+\*\*/.test(text)                                               // bold
    || /(^|[^\w\\])\*[^*\n]+\*(?!\*)/.test(text)                                  // *italic* (word-bounded)
    || /(^|\n)[ \t]{0,3}(---+|\*\*\*+|___+)[ \t]*($|\n)/.test(text)               // hr
    || /~~[^~\n]+~~/.test(text)                                                   // strike
    || /`[^`\n]+`/.test(text)                                                     // inline code
    || /\[[^\]\n]+\]\([^)\n]+\)/.test(text)                                       // link / image
    || /^\s{0,3}\|.+\|/m.test(text)                                               // table row
    || /\$\$[^$\n]+\$\$/.test(text)                                               // display math
    || /(^|[^\s$])\$(?![\s$])((?:\\\$|[^$\n])*?[^\s$])\$(?!\d)/.test(text)        // inline math (same guards)
    || /(^|[^\w\\])(_{2})[^_\n]+\2/.test(text);                                   // __bold__
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

// === `#` 待办引用渲染（待办结构化批, 2026-11-05 作者令「允许使用 # 来引用待办」）===
//
// 与 `@` 同一条渲染管线、同一套「最长名匹配」算法，只是触发符与边界判据不同：
//   · 触发符 `#`，起始门与 `@` 一致（前置 ASCII 字母/数字不触发，行首/空白/汉字/
//     中文标点之后触发）；
//   · `#` 后紧跟纯数字（任务号形状：井号+数字）或 3/4/6/8 位十六进制（CSS 色值
//     形状：井号+十六进制）**不识别**
//     —— 与补全侧的门禁逐条对齐（同一份判据文字，两处各自实现但规则同源）；
//   · 名字结束边界：空、非字母数字非汉字（标点/空白）—— 中文逗号句号紧跟其后
//     也照样收，这是 `@` 那套"必须空白"判据在中文里的必然放宽；
//   · **识别不到就原样显示**：匹配失败不吞字，扫描只跳过这一个 `#` 继续走。
// 数据面同样走 provider（main.js 注入 todoStore.openItems 快照），本文件不 import
// 待办 store —— 与 @ 提及保持同一个单向依赖形状。

/**
 * 一条可引用的待办（渲染侧只需要名字与详情/ id）。
 * @typedef {{name: string, detail?: string, id?: string}} MentionTodo
 */

/** @type {null | (() => {todos: MentionTodo[], gen: number})} */
let _todoProvider = null;

/**
 * 注入待办清单读口（main.js 启动时一次）。provider 是唯一数据路径：
 * 本模块不取数、不缓存待办。
 * @param {() => {todos: MentionTodo[], gen: number}} fn
 */
export function setTodoListProvider(fn) {
  _todoProvider = fn;
}

/**
 * 当前待办清单状态；安全回落 = 空清单（清单未到 ⇒ 引用按纯文本显示）。
 * @returns {{todos: MentionTodo[], gen: number}}
 */
function todoListState() {
  if (!_todoProvider) return { todos: [], gen: 0 };
  try {
    const s = _todoProvider();
    if (s && Array.isArray(s.todos)) return { todos: /** @type {MentionTodo[]} */ (s.todos), gen: s.gen };
  } catch (_) { /* provider 打嗝不能影响渲染 */ }
  return { todos: [], gen: 0 };
}

/** `#` 之后的 token 若命中色值/任务号形状 ⇒ 不是待办引用。 */
const TODO_DIGIT_HEAD_RE = /^\d/;
const TODO_HEX_SHAPE_RE = /^[0-9a-f]{3}$|^[0-9a-f]{4}$|^[0-9a-f]{6}$|^[0-9a-f]{8}$/i;
/** 名字后续字符必须是"词边界"（非字母数字非汉字）才算完整引用。 */
const TODO_WORD_CONT_RE = /[\p{L}\p{N}_]/u;

/**
 * `#` 处的待办引用最长名匹配。
 *
 * @param {string} text 正在扫描的片段
 * @param {number} at `#` 的下标
 * @param {MentionTodo[]} todos 只读清单快照
 * @returns {{todo: MentionTodo, len: number} | null}
 */
function matchTodoAt(text, at, todos) {
  const after = text.slice(at + 1);
  if (!after) return null;
  // 形状门禁（与补全侧 blockDigitStart / blockHexColor 同源）：任务号与色值一律
  // 不认作待办引用。
  if (TODO_DIGIT_HEAD_RE.test(after)) return null;
  const token = after.split(/[\s，。！？；：、,.!?;:)/\]】」』]/)[0];
  if (token && TODO_HEX_SHAPE_RE.test(token)) return null;
  /** @type {Array<{todo: MentionTodo, len: number}>} */
  const matches = [];
  for (const todo of todos) {
    const name = todo && typeof todo.name === 'string' ? todo.name : '';
    if (!name || after.slice(0, name.length).toLowerCase() !== name.toLowerCase()) continue;
    const tail = after.slice(name.length);
    const suffix = todo.id ? '·' + todo.id : '';
    if (suffix && tail.startsWith(suffix) && !TODO_WORD_CONT_RE.test(tail.slice(suffix.length, suffix.length + 1))) {
      matches.push({ todo, len: name.length + suffix.length });
    } else if (!tail.startsWith('·') && (!tail || !TODO_WORD_CONT_RE.test(tail[0]))) {
      matches.push({ todo, len: name.length });
    }
  }
  const longest = Math.max(0, ...matches.map(match => match.len));
  const winners = matches.filter(match => match.len === longest);
  return winners.length === 1 ? winners[0] : null;
}

/**
 * 把 prose 片段里的 `#<待办名>` 换成 TODOBLOCK 占位符（收集进 `out`），
 * marked.parse 之后再还原成可点 chip。纯函数：无命中 ⇒ 原样返回。
 *
 * @param {string} seg
 * @param {Array<{todo: MentionTodo, slice: string}>} out
 * @returns {string}
 */
function protectTodoRefs(seg, out) {
  const { todos } = todoListState();
  if (todos.length === 0) return seg;
  let result = '';
  let i = 0;
  while (i < seg.length) {
    const at = seg.indexOf('#', i);
    if (at < 0) {
      result += seg.slice(i);
      break;
    }
    // 起始门与 `@` 逐字同源：ASCII 字母/数字之前不触发（`C#` / `a#1`）。
    const wordStart = at === 0 || !/[a-zA-Z0-9]/.test(seg[at - 1]);
    const hit = wordStart ? matchTodoAt(seg, at, todos) : null;
    if (hit) {
      out.push({ todo: hit.todo, slice: seg.slice(at + 1, at + 1 + hit.len) });
      result += seg.slice(i, at) + 'TODOBLOCK' + (out.length - 1) + 'END';
      i = at + 1 + hit.len;
    } else {
      result += seg.slice(i, at + 1);
      i = at + 1;
    }
  }
  return result;
}

/**
 * 还原 TODOBLOCK 占位符为可点待办 chip。动态值一律 escapeHtml；落进双引号
 * 属性的值再转义 `"`（escapeHtml 是文本节点序列化器，不转引号）。
 *
 * @param {string} html
 * @param {Array<{todo: MentionTodo, slice: string}>} blocks
 * @returns {string}
 */
function restoreTodoRefs(html, blocks) {
  return html.replace(RE_TODO_RESTORE, (m, idx) => {
    const rec = blocks[parseInt(idx, 10)];
    if (!rec) return m;
    const t0 = rec.todo;
    const nameAttr = escapeHtml(t0.name).replace(/"/g, '&quot;');
    const idAttr = escapeHtml(t0.id || '').replace(/"/g, '&quot;');
    const detailAttr = escapeHtml(t0.detail || '').replace(/"/g, '&quot;');
    return '<span class="todo-tag" data-todo="' + nameAttr + '" data-todo-id="' + idAttr
      + '" data-todo-detail="' + detailAttr + '" role="button" tabindex="0">#'
      + escapeHtml(rec.slice) + '</span>';
  });
}

// === KaTeX math rendering - protect math blocks from Markdown processing ===
// Bounded LRU cache for rendered markdown HTML. History restore and session
// switching re-render identical content; caching avoids repeated
// marked.parse + KaTeX.renderToString work.
// - Key: `${parseVoice}${markedLoaded}${katexLoaded}${locale}|${outputBlock}${mentionGen}|${text}` —
//   captures every factor that changes the output (voice parsing, library
//   load state, i18n locale used by the copy button, the output-block gate
//   (output-block-gate 批: a gate flip must not serve pre-flip cached HTML),
//   the @-mention project list generation (mention-render batch: a list change must not let cached
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
const RE_OUTPUT_RESTORE = /OUTPUTBLOCK(\d+)END/g;
const RE_OUTPUT_PAIR = /<report>([\s\S]*?)<\/report>/g;
const RE_OUTPUT_ANY_TAG = /<\/?report>/g;
const RE_MENTION_RESTORE = /MENTIONBLOCK(\d+)END/g;
const RE_TODO_RESTORE = /TODOBLOCK(\d+)END/g;
// msgchip 批（2026-11-01 作者令「消息身份牌系统升级」）：带短名引用形态
// {{msg:sid8-seq6|短名}} → 渲染成可点小牌 chip（牌面 = 短名；点击弹小窗看原消息）。
// 语法与引擎侧单一实现源（scala nebflow.core.msgtokens.MsgTokenRegistry.TokenRegex）
// 同一条规则：sid8 = 8 位 hex、seq = 6 位、短名 = 除 `{` `}` `|` 换行外 1..40 字。
// 🔴 只匹配**带名**形态——旧裸牌 {{msg:sid8-seq6}} 不入本正则（现状渲染逐字节不变）。
// 畸形/超长/空名 = 不匹配 = 原样字面透传（零解析豁免、零崩溃面）；占位符恢复与
// MATHBLOCK 同构（marked 前提取、marked 后恢复；提取只在非代码段——RE_CODE_SPLIT
// 奇偶分段内做，围栏/行内代码里的牌是代码内容，保持字面）。
const RE_MSG_TOKEN_NAMED = /\{\{msg:([0-9a-f]{8})-(\d{6})\|([^{}\n|]{1,40})\}\}/g;
const RE_MSGTOKEN_RESTORE = /MSGTOKENBLOCK(\d+)END/g;
const RE_IMG_TAG = /<img\b(?![^>]*\bclass=)/g;
// mermaid-md batch: marked v12 emits fenced ```mermaid exactly as this shape
// (plain `<pre>`, single language class, escaped body). The body is captured
// as-is (already HTML-escaped by marked) — safe for the double-quoted
// data-mermaid-src attribute because marked escapes `"` to `&quot;`.
const RE_MERMAID_BLOCK = /<pre><code class="language-mermaid"[^>]*>([\s\S]*?)<\/code><\/pre>/g;
// rich-render 批（2026-11-05 作者令 08:55「可以，试试内联svg」）：```svg 围栏走
// 与 mermaid 同一形状的占位替换；安全审查不在这条字符串腿上做（字符串正则挡不住
// 实体/命名空间变体），而在 js/svgFence.js 的「解析成 DOM → 白名单重建」腿做。
const RE_SVG_BLOCK = /<pre><code class="language-svg"[^>]*>([\s\S]*?)<\/code><\/pre>/g;

/** 源码面读「末尾有没有未闭合的围栏」，返回它的 info 词（小写；闭合 = 空串）。
 *  这就是流式判据的第二条（第一条 = 调用点报的 `stream` 事实）：
 *  CommonMark 里未闭合围栏 body 仍是代码块，我们**不改它**——图只在围栏闭合后
 *  出现一次。与 `_humanFencesBalanced` 同一读法（开栏 = 带 info 的 ```，
 *  关栏 = 仅反引号的 ```；开栏期间带 info 的 ``` 是字面内容不是嵌套）。 */
function _trailingOpenFence(text) {
  if (text.indexOf('```') === -1) return '';
  let info = '';
  const lines = text.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!info) {
      const m = /^```+[ \t]*([\w+#.-]*)/.exec(line);
      if (m && m[1]) info = m[1].toLowerCase();
    } else if (/^```+[ \t]*$/.test(line)) {
      info = '';
    }
  }
  return info;
}

/** 属性值转义（escapeHtml 不转引号，属性面必须自己来）。 */
function _attrEscape(/** @type {string} */ s) {
  return escapeHtml(s).replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/**
 * 图围栏占位替换（mermaid / svg 共用；单一实现，不各造一套）。
 * 末尾那个未闭合围栏保持代码块（见 _trailingOpenFence）。
 * @param {string} html
 * @param {RegExp} re
 * @param {string} lang
 * @param {string} openFence
 * @param {string} srcAttr
 * @param {string} cls
 * @returns {string}
 */
function _swapFenceBlocks(html, re, lang, openFence, srcAttr, cls) {
  re.lastIndex = 0;
  let total = 0;
  while (re.exec(html) !== null) total++;
  if (total === 0) return html;
  let seen = 0;
  re.lastIndex = 0;
  return html.replace(re, (full, code) => {
    seen++;
    if (seen === total && openFence === lang) return full; // 未闭合尾围栏 = 仍是代码
    return `<div class="${cls}" ${srcAttr}="${code}"><div class="${cls}-body"><div class="${cls}-ph"></div></div></div>`;
  });
}
// human-face batch (2026-10-30, 设计稿 docs/design/20261030_human-readable-
// presentation.zh-CN.md 二期): marked v12 emits a fenced ```human block in the
// same shape as mermaid (plain <pre>, single language class, escaped body).
const RE_HUMAN_BLOCK = /<pre><code class="language-human"[^>]*>([\s\S]*?)<\/code><\/pre>/g;

export function renderMarkdownWithMath(text, parseVoice = true, opts) {
  if (!text) return '';
  const safe = !!(opts && opts.safe);
  // 流式快照闸（rich-render 批，作者令 2026-11-05 08:51 第 5 条）：`stream:true`
  // ⇒ 本次渲染是「消息还没写完」的中间帧，图围栏一律**保持代码块**（产出零 SVG，
  // 见 _renderMarkdownInternal 的 allowDiagram）。判据单点在管道里，调用点只报
  // 事实（自己是否流式中），不做任何围栏语法判断。
  const stream = !!(opts && opts.stream);
  // Streaming bypass (mem-diagnosis 20260907 D3): per-frame stream renders
  // re-parse the SAME growing text — every intermediate snapshot used to
  // enter this LRU and evict real entries until the hit rate hit 0. Stream
  // renderers pass { cache:false } and go straight to the parser; only
  // finish*/history renders (stable text) populate the cache.
  if (opts && opts.cache === false) return _renderMarkdownWithMath(text, parseVoice, safe, stream);
  // 🔴 safe 是 key 的一部分：同一段文本在两条信任档下产物不同，混键会把
  // 「agent 面可信渲染」与「外部消息安全渲染」互相串味（缓存投毒面）。
  // stream 同理（同名文本在流式档下不产图围栏占位，终态档产）——混键会让
  // 图片在流式中途借缓存漏出。
  // `#` 待办引用代次（待办结构化批 2026-11-05）同理：待办清单变了，已缓存 HTML
  // 里的 chip 就可能指向改名/删除前的旧名 ⇒ 代次进键。
  const key = `${parseVoice ? 1 : 0}${typeof marked !== 'undefined' ? 1 : 0}${typeof katex !== 'undefined' ? 1 : 0}${getLocale()}|${outputBlockEnabled() ? 1 : 0}${mentionListState().gen}.${todoListState().gen}|${safe ? 'S' : 'T'}|${stream ? 'S' : 'F'}|${text}`;
  const hit = _mdCache.get(key);
  if (hit !== undefined) {
    _mdCache.delete(key);
    _mdCache.set(key, hit);
    return hit;
  }
  // 冷启动批⑤（2026-10-30 作者实证「偶发收到的消息以原始 markdown 文本显示
  // 不渲染」）：渲染器**永不抛** —— 修前一枚病态输入让 marked/KaTeX/DOM 后
  // 处理在 rAF 升级批中途抛错，链上后续消息全部滞留 textContent 原始态
  // （persistence.js 两升级批已同步加单元素护栏，双保险）。异常腿回落
  // escapeHtml 纯文本（安全、可读、绝不整行消失，也区别于编辑态显源码）。
  let html;
  try {
    html = _renderMarkdownWithMath(text, parseVoice, safe, stream);
  } catch (e) {
    html = escapeHtml(text);
  }
  _mdCache.set(key, html);
  if (_mdCache.size > MD_CACHE_CAP) {
    _mdCache.delete(_mdCache.keys().next().value);
  }
  return html;
}

function _renderMarkdownWithMath(text, parseVoice, safe, stream) {
  if (typeof marked === 'undefined') return escapeHtml(text);
  // safe 档（外部消息面）：渲染器形态不可用 ⇒ **整段回落转义纯文本**（fail
  // closed），绝不落到可信腿上——外部输入不接受「降级但仍可信」。
  let safeRenderer = null;
  if (safe) {
    safeRenderer = safeMarkedRenderer();
    if (!safeRenderer) return escapeHtml(text);
  }
  // voice 块补封批 2026-10-31（作者令「封存所有会话的 voice 块」，漏网面）：
  // 全双工 TTS 已随 #508 封存（voiceEnabled 缺席即封存），TTS 输出族
  // （<voice> 块 = .voice-block 可点绿字 + VoicePlayer 播报）是同源漏网——
  // 封存态下剥签按普通内联文本渲染（内容零丢失、零 .voice-block、零播放
  // 入口）；features.voice:true 解封时提取路径原样回归（零删除惯例）。
  if (parseVoice && !voiceEnabled()) {
    text = text.replace(RE_VOICE, '$1');
  }
  // output 块语法批（作者令 2026-10-31 16:13，16:16 修订标签式）：成对
  // `<report>…</report>` 标签 = 用户可见内容。提取照 voice 块同构（占位符
  // 先行、marked 后恢复）；平衡闸照 ```human 围栏「整段等闭合」同构——全部
  // 成对闭合才提取，散标签（流式中间态/手误）转义为字面文本：marked 会把裸
  // `<report>` 当 HTML 块把后续内容整段吞掉，必须先行掐断。无标签文本零改动
  // （降级语义：未包裹的旧内容照常全显，不吞历史消息）。
  // output-block-gate 批（作者令「把 output 块相关的内容先关闭……」）：总闸
  // `outputBlock.enabled`（缺席 = 关）关闭 ⇒ 提取分支整体旁路，全部标签走
  // 散标签同一转义腿（字面现形，内容零丢失）＝全文直出，无掩码；与渠道剥离
  // 面同一键（scala OutputBlock.enabledFromConfig）。闸一开提取路径原样回归。
  const outputBlocks = [];
  if (outputBlockEnabled() && (text.indexOf('<report>') !== -1 || text.indexOf('</report>') !== -1)) {
    // 围栏安全批（作者令：块形态就是为了避开 md 语法冲突，提取器不能反被
    // 围栏骗了）：扫描/提取都先过 _maskCodeFences 掩码——围栏内的字面标签
    // 是代码内容，不触发剥离，也吃不到转义（转义会腐蚀围栏体）。与渠道侧
    // 单一实现源（scala nebflow.social.outbound.OutputBlock）同一条规则。
    const masked = _maskCodeFences(text);
    if (_outputTagsBalancedOn(masked)) {
      // 成对闭合 ⇒ 占位替换只落在围栏外的对上：掩码串上找对、按 1:1 偏移
      // 回原文 splice（尾→头，偏移保持有效；块序 = 出现序）。
      const pairs = [];
      RE_OUTPUT_PAIR.lastIndex = 0;
      let pm;
      while ((pm = RE_OUTPUT_PAIR.exec(masked)) !== null) {
        pairs.push({
          start: pm.index,
          end: pm.index + pm[0].length,
          inner: text.slice(pm.index + 8, pm.index + pm[0].length - 9).trim()
        });
      }
      for (let i = pairs.length - 1; i >= 0; i--) {
        const p = pairs[i];
        outputBlocks[i] = p.inner;
        text = text.slice(0, p.start) + `OUTPUTBLOCK${i}END` + text.slice(p.end);
      }
    } else {
      // 散标签（流式中间态/手误）只在围栏外转义为字面文本：marked 会把裸
      // `<report>` 当 HTML 块把后续内容整段吞掉，必须先行掐断。
      const tags = [];
      RE_OUTPUT_ANY_TAG.lastIndex = 0;
      let tm;
      while ((tm = RE_OUTPUT_ANY_TAG.exec(masked)) !== null) {
        tags.push({ start: tm.index, end: tm.index + tm[0].length, tag: tm[0] });
      }
      for (let i = tags.length - 1; i >= 0; i--) {
        const s = tags[i];
        text =
          text.slice(0, s.start) + (s.tag === '<report>' ? '&lt;report&gt;' : '&lt;/report&gt;') + text.slice(s.end);
      }
    }
  }
  // Extract <voice>...</voice> blocks before any markdown processing (only for AI output, not thinking)
  const voiceBlocks = [];
  const msgTokens = [];
  if (parseVoice && voiceEnabled()) {
    let vp = text.replace(RE_VOICE, (m, content) => {
      voiceBlocks.push(content.trim());
      return `VOICEBLOCK${voiceBlocks.length - 1}END`;
    });
    return _renderMarkdownInternal(vp, voiceBlocks, false, outputBlocks, msgTokens, safe, safeRenderer, !stream);
  }
  return _renderMarkdownInternal(text, voiceBlocks, false, outputBlocks, msgTokens, safe, safeRenderer, !stream);
}

// ── <report> user-visible block (output 块语法批 2026-10-31) ────────────────
// 设计稿：docs/design/20261030_human-readable-presentation.zh-CN.md §11。
// 单管线透明面：标签不现形、内容照 markdown 渲染——canvas md viewer / wiki
// docs / flow 卡 / report 卡四面经此 pass 读得了 output 文档；聊天气泡面的
// 「只显块内容 + 角标展开」摘除语义在 chat.js/outputTag.js（不在管线内做，
// 那里需要源文本级的摘除与原文回填）。
//
/** Source-level gate: true only when the text has ≥1 `<report>...</report>` pair
 *  AND every tag is properly paired in order (no stray close, no nesting). The
 *  fast indexOf pre-check keeps the per-frame streaming hot path at one cheap
 *  scan for the overwhelmingly common no-tag case (same shape as
 *  _humanFencesBalanced). 围栏安全：扫描前先过 _maskCodeFences——围栏内的字面
 *  标签是内容不是结构。 */
function _outputTagsBalanced(text) {
  return _outputTagsBalancedOn(_maskCodeFences(text));
}

/** The balance walk on an ALREADY-masked text (fence regions are NUL runs, so
 *  their literal tags are invisible to the scan). */
function _outputTagsBalancedOn(masked) {
  if (masked.indexOf('<report>') === -1) return false;
  let open = false;
  let saw = false;
  const re = /<\/?report>/g;
  let m;
  while ((m = re.exec(masked)) !== null) {
    if (m[0] === '<report>') {
      if (open) return false; // nesting = not the agreed shape
      open = true;
      saw = true;
    } else {
      if (!open) return false; // stray close
      open = false;
    }
  }
  return saw && !open;
}

/** 同长掩码：``` 围栏区逐字符替换为 NUL（换行保留 ⇒ 偏移与原文 1:1）。围栏
 *  安全批（作者令原文：块形态当初就是为了避开 md 语法冲突，提取器不能反被
 *  围栏骗了）：围栏内字面 <report> 是内容不是结构。开栏 = trim 后以 ```
 *  开头的行；关栏 = trim 后仅 ≥3 个反引号的行（CommonMark；同 ```human 闸的
 *  读法）；未闭合围栏跑到底。与渠道侧单一实现源（scala
 *  nebflow.social.outbound.OutputBlock.maskFences）同一条规则。 */
function _maskCodeFences(text) {
  const lines = text.split('\n');
  let fence = 0;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const t = line.trim();
    const ticks = (t.match(/^`+/) || [''])[0].length;
    if (fence) {
      lines[i] = '\u0000'.repeat(line.length);
      if (ticks >= fence && !t.slice(ticks).trim()) fence = 0;
    } else if (t.startsWith('>')) {
      lines[i] = '\u0000'.repeat(line.length);
    } else if (ticks >= 3) {
      lines[i] = '\u0000'.repeat(line.length);
      fence = ticks;
    } else {
      lines[i] = line.replace(/(`+)([\s\S]*?)\1/g, m => '\u0000'.repeat(m.length));
    }
  }
  return lines.join('\n');
}

/** Shared inner-content joiner: every balanced pair's body, blank-line joined —
 *  the chat face (outputTag.js) renders this as the whole visible message.
 *  围栏安全：对掩码串找对、按偏移回原文切片（围栏内的字面标签永不入块）。
 *  output-block-gate 批：总闸关闭 ⇒ null（=「无平衡块」）——hasOutputBlock /
 *  applyOutputFace / defer-output 标记整条链随之熄灭，聊天气泡回全文渲染；
 *  与管线提取面（renderMarkdownWithMath）同一开关。 */
export function extractOutputInner(text) {
  const masked = _maskCodeFences(text);
  if (!_outputTagsBalancedOn(masked)) return null;
  const parts = [];
  RE_OUTPUT_PAIR.lastIndex = 0;
  let m;
  while ((m = RE_OUTPUT_PAIR.exec(masked)) !== null) {
    parts.push(text.slice(m.index + 8, m.index + m[0].length - 9).trim());
  }
  return parts.length ? parts.join('\n\n') : null;
}

// ── ```human filter fence (human-face batch 2026-10-30) ────────────────────
// 设计稿：docs/design/20261030_human-readable-presentation.zh-CN.md §3 —
// 框内卡片化、框外 <details> 抽屉、单管线五面（聊天气泡 / Canvas md viewer /
// wiki docs / flow 卡片 / report 卡片）共用此 pass。
//
/** Source-level gate: true only when the text has ≥1 ```human fence AND every
 *  one is closed. Mirrors CommonMark fence semantics (a closing fence is 3+
 *  backticks with no info string; while a fence is open, a ```human line is
 *  literal content, not a nested opener). The fast includes() pre-check keeps
 *  the per-frame streaming hot path (cache:false) at one cheap scan for the
 *  overwhelmingly common no-fence case. */
function _humanFencesBalanced(text) {
  if (text.indexOf('```human') === -1) return false;
  let open = false;
  let saw = false;
  const lines = text.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!open) {
      if (/^```human[ \t]*$/.test(line)) { saw = true; open = true; }
    } else if (/^```[ \t]*$/.test(line)) {
      open = false;
    }
  }
  return saw && !open;
}

/** Reverse marked's HTML escaping inside a fenced body so the card content can
 *  be re-rendered as markdown. Order matters: &amp; decodes LAST. */
function _decodeHumanEntities(s) {
  return s
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&');
}

// ── 代码块语法高亮（文档渲染批 2026-11-01）──────────────────────────────
// 修前语义：markdown 围栏（```lang）走 marked 原样输出 `<pre><code class=
// "language-x">`，除复制按钮外**没有任何着色**——vendor 里早就躺着
// highlight.min.js 与两套 github 主题 CSS（index.html:48-49,1196），但只有
// 工具调用正文（renderHighlightedContent）用它，文档/对话的代码块一直是裸
// `<pre><code>`。本补丁把同一枚 hljs 接到围栏腿上：零新依赖、零新 vendor。
//
// 口径（三条刻意的收缩，都是为了成本与确定性）：
//   1. 只高亮**带显式且 hljs 认识**的语言标签的围栏；无标签一律不动
//      （highlightAuto 对长文是 O(N·语言数)，流式重渲下不可控）。
//   2. 单块超过 [[FENCE_HIGHLIGHT_MAX_CHARS]] 字节直接放行原文——
//      大块日志不该在渲染路径上做词法分析。
//   3. 未知语言（hljs.getLanguage 返回假）同样放行原文：加个空 hljs 类只会
//      让主题色错位，不如保持现状。
//
// 位置契约：本 pass 必须跑在 mermaid 占位替换（图表不是代码）、human 卡
// 转换（卡不是代码）之后，`<pre>` 复制按钮包裹之前——包裹 pass 按 `<pre>`
// 配对扫描，先包裹会把 `</code></pre>` 挤到新位置。
const FENCE_HIGHLIGHT_MAX_CHARS = 20000;
// marked v12 的围栏形状：`<pre><code class="language-<x>">正文</code></pre>`
// （正文已 HTML 转义；mermaid.md 批的 RE_MERMAID_BLOCK 是同一形状的证据）。
const RE_FENCE_BLOCK = /<pre><code class="language-([\w+#.-]+)"([^>]*)>([\s\S]*?)<\/code><\/pre>/g;

/** 给带语言标签的围栏补 hljs 着色。纯函数：不认识的语言 / 超长 / hljs 缺席 /
 *  无匹配 ⇒ 逐字节原样返回。 */
function _highlightFencedCode(html) {
  if (typeof hljs === 'undefined') return html;
  RE_FENCE_BLOCK.lastIndex = 0;
  const out = html.replace(RE_FENCE_BLOCK, (m, lang, attrs, body) => {
    // 实体解码（marked 转义过一次）→ hljs 自己再转义一次，双向可逆；
    // 不解码就高亮会把 `&lt;` 当字面量切词，token 边界全错。
    const src = _decodeHumanEntities(body);
    if (src.length > FENCE_HIGHLIGHT_MAX_CHARS) return m;
    let res;
    try {
      if (!hljs.getLanguage(lang)) return m;
      res = hljs.highlight(src, { language: lang, ignoreIllegals: true });
    } catch (e) {
      return m; // 任何 hljs 异常都退回原文：渲染器永不抛（冷启动批⑤ 同款纪律）
    }
    if (!res || !res.value) return m;
    return `<pre><code class="language-${lang} hljs"${attrs}>${res.value}</code></pre>`;
  });
  RE_FENCE_BLOCK.lastIndex = 0;
  return out;
}

/** Post-marked transform: split the HTML on closed ```human blocks; render each
 *  fence body as a markdown card; wrap every outside chunk that carries text
 *  in a <details> drawer (whitespace-only chunks pass through untouched). */
function _transformHumanFace(html, humanStream) {
  const parts = [];
  let last = 0;
  let m;
  RE_HUMAN_BLOCK.lastIndex = 0;
  while ((m = RE_HUMAN_BLOCK.exec(html)) !== null) {
    if (m.index > last) parts.push({ kind: 'out', html: html.slice(last, m.index) });
    parts.push({ kind: 'card', code: m[1] });
    last = m.index + m[0].length;
  }
  RE_HUMAN_BLOCK.lastIndex = 0;
  if (!parts.length) return html;
  if (last < html.length) parts.push({ kind: 'out', html: html.slice(last) });
  let result = '';
  for (const p of parts) {
    if (p.kind === 'card') {
      // Inner render with noHuman=true: a nested ```human fence inside a card
      // stays a plain code block (no unbounded recursion).
      const inner = _renderMarkdownInternal(_decodeHumanEntities(p.code), [], true, undefined, [], false, null, humanStream);
      result += '<div class="nf-human-card">' + inner + '</div>';
    } else {
      const text = p.html.replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').trim();
      // 围栏语义定案（作者令 2026-10-30 20:13，重启后生效）：围栏在时 = 只显
      // 围栏内人面子，框外内容一律默认折叠（抽屉可展开），五档无渲染豁免——
      // 备考冲刺档 foldDefault="expand-findings" 的框外不折豁免随之退役（档参数
      // 保留在枚举/覆盖面，findings 展开的呈现语义移交下批 calibrate/渲染面
      // 细做）。无渲染支持的面（纯文本/外发）框外照常直出，信息无损。
      if (!text) {
        result += p.html;
      } else {
        const n = text.split('\n').filter((l) => l.trim()).length;
        const label = escapeHtml(t('human.details')) + ' (' + n + ')';
        result += '<details class="nf-human-details"><summary>' + label + '</summary>' + p.html + '</details>';
      }
    }
  }
  return result;
}

/**
 * msg-token chip 提取（msgchip 批）：非代码段内的带短名引用形态
 * {{msg:sid8-seq6|短名}} → MSGTOKENBLOCK<i>END 占位符（marked 前提取，尾部恢复）。
 * 只在 prose 分段调用（RE_CODE_SPLIT 奇偶分段，同 math/mentions 保护缝）——围栏与
 * 行内代码里的牌是代码内容，保持字面。store 缺席（理论不可达，防御）= 原样返回。
 */
function protectMsgTokens(seg, store) {
  if (!store || seg.indexOf('{{msg:') === -1) return seg;
  return seg.replace(RE_MSG_TOKEN_NAMED, (m, sid8, seq, name) => {
    store.push({ token: m, name });
    return `MSGTOKENBLOCK${store.length - 1}END`;
  });
}

function _renderMarkdownInternal(protected_, voiceBlocks, noHuman, outputBlocks, msgTokens, safe, safeRenderer, allowDiagram) {
  if (!msgTokens) msgTokens = [];
  if (allowDiagram === undefined) allowDiagram = true;
  // human-face batch (2026-10-30): the ```human filter fence renders ONLY when
  // every fence in the source is closed (streaming gate, 设计稿 §7.5 落地口径
  // 「整段等闭合」). An unclosed fence (mid-stream snapshot) falls through to
  // the normal code-block path — everything stays visible, nothing crashes, and
  // the card+drawer layout appears in one step the moment the fence closes.
  //
  // safe 档（外部消息面）关掉 ```human：那是 agent 产出的「人面卡」版式族，
  // 外部来信不该驱动版式分栏（正文照常全显，围栏退回普通代码块，零丢失）。
  const humanOn = !noHuman && !safe && _humanFencesBalanced(protected_);
  const mathBlocks = [];
  const mentionBlocks = [];
  // `#` 待办引用块（待办结构化批 2026-11-05）：与 @ 提及同一条保护/还原管线，
  // 代码段已在 RE_CODE_SPLIT 里被切走 ⇒ 围栏/行内代码内的 `#` 天然 inert。
  const todoBlocks = [];
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
    .map((seg, i) => (i % 2 === 1
      ? seg
      : protectTodoRefs(
        protectMentions(protectMath(protectMsgTokens(seg, msgTokens)), mentionBlocks),
        todoBlocks)))
    .join('');
  // safe 档：**逐次调用**注入硬化渲染器（marked.parse 的 per-call option，
  // 实测不污染后续调用 ⇒ agent 面永远走默认渲染器；禁 marked.use 全局换装，
  // 那会在流式交叠时串档）。降级器缺席 ⇒ 上游已 fail closed（走不到这里）。
  let html = marked.parse(protected_, safeRenderer
    ? { headerIds: false, renderer: safeRenderer }
    : { headerIds: false });
  // Mermaid blocks (mermaid-md batch, 2026-10-23): a ```mermaid fence is a
  // DIAGRAM, not code — swap marked's `<pre><code class="language-mermaid">`
  // for a placeholder div BEFORE the copy-button pass below sees it (so no
  // copy chrome wraps a diagram). The placeholder carries the raw source in
  // `data-mermaid-src` (marked's HTML escaping survives the attribute; the DOM
  // hands the decoded source back at hydration) and js/mermaidRenderer.js
  // renders it asynchronously (lazy viewport, themed, strict-sandboxed). The
  // reserved-height body keeps layout stable while the library loads/renders,
  // and every face that shares this pipeline (canvas md viewer, wiki docs,
  // chat bubbles, flow cards, report cards) gets the same placeholder.
  //
  // rich-render 批（2026-11-05，作者令 08:51 第 5 条 + 08:53 + 08:55）：同一
  // 条腿现在管三件事 —— ① ```mermaid；② ```svg（白名单重建见 svgFence.js）；
  // ③ 流式闸：**两个判据同时成立**才出占位
  //     (a) 本次渲染不是流式快照（调用点报 opts.stream；AI 气泡本身是「整段
  //         只渲一次」的，见 chat.js appendAiText 的注释 —— 这一条护住的是
  //         thinking / dock / popup 这些仍然逐帧重渲的面）；
  //     (b) 该围栏在源码里**已闭合**（_trailingOpenFence）—— 半截围栏仍是代码
  //         块，围栏一闭合就整块出现一次。
  //   理由：逐 token 重渲 = 每帧一次 mermaid.render + 布局抖动，且半截语法必然
  //   报错；两条判据把「产图」钉在「这一版文本已经定稿」上。
  if (allowDiagram) {
    const openFence = _trailingOpenFence(protected_);
    html = _swapFenceBlocks(html, RE_MERMAID_BLOCK, 'mermaid', openFence, 'data-mermaid-src', 'nf-mermaid');
    html = _swapFenceBlocks(html, RE_SVG_BLOCK, 'svg', openFence, 'data-svg-src', 'nf-svg');
  }
  // human-face pass (2026-10-30): a CLOSED ```human fence is a human-face card,
  // not code — its markdown body is rendered into `.nf-human-card`; every
  // outside chunk (before / between / after cards) collapses into a
  // `<details class="nf-human-details">` drawer. Runs BEFORE the <pre> copy-
  // button pass so a card never gets code chrome. Single pipeline ⇒ all five
  // markdown faces (chat bubbles, canvas md viewer, wiki docs, flow cards,
  // report cards) pick it up at once, same as the mermaid pass above.
  if (humanOn) html = _transformHumanFace(html, allowDiagram === false);
  // 代码块语法高亮（文档渲染批 2026-11-01）：图表占位与 human 卡都已出局，
  // 此处剩下的围栏才是真正的代码。跑在复制按钮包裹之前（见 _highlightFencedCode
  // 的位置契约）。
  html = _highlightFencedCode(html);
  // Wrap <pre> blocks with a copy button and close the wrapper in the SAME pass
  // (修法 2: one whole-string scan instead of two). The alternation keeps the
  // original per-tag output byte-for-byte: every `<pre…>` gets the wrap opener +
  // button + the tag itself ($&), every `</pre>` becomes `</pre></div>`.
  html = html.replace(RE_PRE_TAG, (tag) => {
    if (tag === '</pre>') return '</pre></div>';
    return '<div class="code-block-wrap"><button class="code-copy-btn" onclick="window.copyCode(this)" title="' + t('chat.copy') + '"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg><span>' + t('chat.copy') + '</span></button>' + tag;
  });
  // Restore output blocks (output 块语法批 2026-10-31): each pair's body is
  // rendered by a NESTED internal pass (own math/mermaid/<pre>-wrap handling),
  // so the restore runs AFTER the outer <pre> copy-button pass — restoring
  // earlier would double-wrap the inner code blocks. Tags stay invisible
  // (transparent face); the chat removal/expand semantics live in outputTag.js.
  if (outputBlocks && outputBlocks.length) {
    html = html.replace(RE_OUTPUT_RESTORE, (m, idx) => {
      const inner = outputBlocks[parseInt(idx, 10)] || '';
      // 信任档随嵌套下传（safe + 硬化渲染器）：output 块的内容同样来自外部
      // 消息面，不能靠「嵌套一层」逃出白名单。
      return '<div class="nf-output">' + _renderMarkdownInternal(inner, [], true, undefined, [], safe, safeRenderer) + '</div>';
    });
  }
  // Restore math blocks as KaTeX
  // rich-render 批（2026-11-05 作者令 08:53）：KaTeX 从「开屏就装」改为**按需
  // 装载**（index.html 不再挂 275KB 的 defer script）。katex 缺席时这里产出
  // 占位 span —— 占位里直接躺着**原始 LaTeX 文本**，所以「装载腿没跑到」的
  // 形态是回退到源码而不是空白；js/mathRender.js 首次入视口时装库、渲染、
  // 失败态可见。trust 恒 false（作者令：禁 \href/\htmlClass 一类注入面）。
  mathBlocks.forEach((block, i) => {
    const token = `MATHBLOCK${i}END`;
    const raw = block.display ? `$$${block.math}$$` : `$${block.math}$`;
    // 用函数形态替换：替换串里的 `$` 会被 String.replace 当特殊语法吃掉
    // （`$$` → `$`），LaTeX 源码里 `$` 不罕见，必须绕开这条解释链。
    if (typeof katex !== 'undefined') {
      try {
        const rendered = katex.renderToString(block.math, {
          displayMode: block.display,
          throwOnError: false,
          strict: 'ignore',
          // 作者令 2026-11-05 08:53：trust 恒 false —— `\href` / `\htmlClass` /
          // `\includegraphics` 一族在 trust:true 下会产出可点链接与原始 HTML
          // 片段，等于把 marked 那层的白名单在数学腿里绕开。
          trust: false
        });
        html = html.replace(token, () => rendered);
      } catch (e) {
        html = html.replace(token, () => escapeHtml(raw));
      }
      return;
    }
    html = html.replace(token, () => '<span class="nf-math" data-math-src="'
      + _attrEscape(block.math) + '" data-math-display="' + (block.display ? '1' : '0') + '">'
      + escapeHtml(raw) + '</span>');
  });
  // Restore voice blocks as clickable green spans
  html = html.replace(RE_VOICE_RESTORE, (m, idx) => {
    const i = parseInt(idx);
    const vtext = voiceBlocks[i] || '';
    return '<span class="voice-block" data-voice-index="' + i + '">' + escapeHtml(vtext) + '</span>';
  });
  // Restore mention blocks as clickable blue spans (shared-list longest match).
  html = restoreMentions(html, mentionBlocks);
  // Restore `#` 待办引用块同样式 chip（待办结构化批）：未匹配的 `#` 从未被
  // 换进占位符，所以这里只还原真命中，其余文本原样留着（不吞字）。
  html = restoreTodoRefs(html, todoBlocks);
  // Restore msg-token chips (msgchip 批): 带短名引用形态 → 可点小牌（牌面 = 短名）。
  // 交互面（popover/取数）单点在 js/chat/msgTokenChip.js —— 文档级事件委托，不在此绑
  // （innerHTML 重建会掉 listener；委托天然存活于流式/历史/重放全渲染腿）。
  if (msgTokens.length) {
    html = html.replace(RE_MSGTOKEN_RESTORE, (m, idx) => {
      const tok = msgTokens[parseInt(idx, 10)];
      if (!tok) return m;
      return '<span class="msg-token-chip" data-msg-token="' + escapeHtml(tok.token)
        + '" role="button" tabindex="0" aria-label="' + escapeHtml(tok.name + ' · ' + t('chat.msgTokenChipHint'))
        + '" title="' + escapeHtml(t('chat.msgTokenChipHint')) + '">' + escapeHtml(tok.name) + '</span>';
    });
  }
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
  const { todos } = todoListState();
  if (!projects.length && !todos.length) {
    parent.appendChild(document.createTextNode(text));
    return;
  }
  let rest = text;
  for (;;) {
    // 两个触发符里取更靠前的那个（同一次扫描处理 @ 与 #，顺序 = 文本顺序）。
    const atMention = projects.length ? rest.indexOf('@') : -1;
    const atTodo = todos.length ? rest.indexOf('#') : -1;
    /** @type {{ch: string, at: number}} */
    let pick = { ch: '@', at: atMention };
    if (atTodo >= 0 && (atMention < 0 || atTodo < atMention)) pick = { ch: '#', at: atTodo };
    if (pick.at < 0) break;
    const { ch, at } = pick;
    const wordStart = at === 0 || MENTION_WHITESPACE_RE.test(rest[at - 1]);
    const hit = !wordStart
      ? null
      : (ch === '@' ? matchMentionAt(rest, at, projects) : matchTodoAt(rest, at, todos));
    if (!hit) {
      // Keep scanning after this non-matching trigger char.
      parent.appendChild(document.createTextNode(rest.slice(0, at + 1)));
      rest = rest.slice(at + 1);
      continue;
    }
    if (at > 0) parent.appendChild(document.createTextNode(rest.slice(0, at)));
    const span = document.createElement('span');
    if (ch === '@') {
      const mhit = /** @type {{canonical: string, len: number}} */ (hit);
      span.className = 'mention-tag';
      span.setAttribute('data-project', mhit.canonical);
      span.textContent = '@' + rest.slice(at + 1, at + 1 + mhit.len);
      rest = rest.slice(at + 1 + mhit.len);
    } else {
      const thit = /** @type {{todo: MentionTodo, len: number}} */ (hit);
      span.className = 'todo-tag';
      span.setAttribute('data-todo', thit.todo.name);
      span.setAttribute('data-todo-id', thit.todo.id || '');
      span.setAttribute('data-todo-detail', thit.todo.detail || '');
      span.setAttribute('role', 'button');
      span.setAttribute('tabindex', '0');
      span.textContent = '#' + rest.slice(at + 1, at + 1 + thit.len);
      rest = rest.slice(at + 1 + thit.len);
    }
    parent.appendChild(span);
  }
  if (rest) parent.appendChild(document.createTextNode(rest));
}

// === User-message face with diagram fences (rich-render 批, 2026-11-05) ===
//
// 作者令 2026-11-05 08:51 第 1 条要求「至少覆盖 agent 回复与用户消息两种气泡
// 面」。用户气泡**整体不走 markdown**（既有裁定：用户输入不得被解释成 markdown，
// 见 appendTextWithMentions 头注），本批不推翻它——只放行**已闭合的图围栏**：
//   · ```mermaid / ```svg 围栏 → 与共享管线同形的占位 div（同一批
//     mermaidRenderer/svgFence 水合，不另起一套渲染）；
//   · 围栏外的一切仍是纯文本（+ @提及 span），零 markdown 解释；
//   · 未闭合围栏（流式中途/手误）整段按纯文本落地——内容零丢失，也不产图。
// 落 DOM 全走 createElement/setAttribute/textContent：无 HTML 串、无注入面。

/**
 * 用户气泡富文本：已闭合的 mermaid/svg 围栏 → 图占位；其余逐字纯文本。
 * @param {HTMLElement} parent
 * @param {string} text
 */
export function appendUserTextWithFences(parent, text) {
  const src = String(text || '');
  // 顺序化切分：一次线性扫描，prose 与围栏交替入列。
  /** @type {Array<{kind: 'text'|'mermaid'|'svg', text: string}>} */
  const parts = [];
  let prose = '';
  const flush = () => { if (prose) { parts.push({ kind: 'text', text: prose }); prose = ''; } };
  if (src.indexOf('```') !== -1) {
    const lines = src.split('\n');
    for (let i = 0; i < lines.length; i++) {
      const open = /^```(mermaid|svg)[ \t]*$/.exec(lines[i]);
      if (!open) { prose += (prose ? '\n' : '') + lines[i]; continue; }
      let j = i + 1;
      while (j < lines.length && !/^```[ \t]*$/.test(lines[j])) j++;
      if (j >= lines.length) {
        // 未闭合：其余全部按纯文本（内容零丢失、不产图）
        prose += (prose ? '\n' : '') + lines.slice(i).join('\n');
        i = lines.length;
        break;
      }
      flush();
      parts.push({ kind: open[1] === 'svg' ? 'svg' : 'mermaid', text: lines.slice(i + 1, j).join('\n') });
      i = j;
    }
  } else {
    prose = src;
  }
  flush();
  for (const part of parts) {
    if (part.kind === 'text') { appendTextWithMentions(parent, part.text); continue; }
    const cls = part.kind === 'mermaid' ? 'nf-mermaid' : 'nf-svg';
    const box = document.createElement('div');
    box.className = cls;
    box.setAttribute(part.kind === 'mermaid' ? 'data-mermaid-src' : 'data-svg-src', part.text);
    const body = document.createElement('div');
    body.className = cls + '-body';
    const ph = document.createElement('div');
    ph.className = cls + '-ph';
    body.appendChild(ph);
    box.appendChild(body);
    parent.appendChild(box);
  }
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
  const { todos } = todoListState();
  if (!projects.length && !todos.length) return;
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
        if (parent.closest('pre, code, .voice-block, .mention-tag, .todo-tag, .katex, button, svg, script, style')) {
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
      wrapMentionsInTextNode(textNode, projects, todos);
    }
  });
}

/**
 * Splice every mention/todo-reference hit of ONE text node into spans,
 * right-to-left so earlier hit indices stay valid while splitting.
 *
 * @param {Text} textNode
 * @param {MentionProject[]} projects read-only shared snapshot
 * @param {MentionTodo[]} [todos] read-only shared snapshot（`#` 引用；缺省 = 不处理）
 */
function wrapMentionsInTextNode(textNode, projects, todos = []) {
  const data = textNode.data;
  /** @type {Array<{start: number, len: number, canonical?: string, todo?: MentionTodo}>} */
  const hits = [];
  let scan = 0;
  while (scan < data.length) {
    const atMention = projects.length ? data.indexOf('@', scan) : -1;
    const atTodo = todos.length ? data.indexOf('#', scan) : -1;
    let idx = atMention;
    let ch = '@';
    if (atTodo >= 0 && (atMention < 0 || atTodo < atMention)) { idx = atTodo; ch = '#'; }
    if (idx < 0) break;
    const wordStart = idx === 0 || MENTION_WHITESPACE_RE.test(data[idx - 1]);
    const hit = !wordStart
      ? null
      : (ch === '@' ? matchMentionAt(data, idx, projects) : matchTodoAt(data, idx, todos));
    if (hit && ch === '#') {
      const thit = /** @type {{todo: MentionTodo, len: number}} */ (hit);
      hits.push({ start: idx, len: thit.len, todo: thit.todo });
      scan = idx + 1 + thit.len;
    } else if (hit) {
      const mhit = /** @type {{canonical: string, len: number}} */ (hit);
      hits.push({ start: idx, len: mhit.len, canonical: mhit.canonical });
      scan = idx + 1 + mhit.len;
    } else {
      scan = idx + 1;
    }
  }
  for (let h = hits.length - 1; h >= 0; h--) {
    const { start, len, canonical, todo } = hits[h];
    textNode.splitText(start + 1 + len); // tail (rest of the node) becomes a sibling
    const mid = textNode.splitText(start); // mid = the '@<slice>' / '#<slice>' text node
    const span = document.createElement('span');
    if (todo) {
      span.className = 'todo-tag';
      span.setAttribute('data-todo', todo.name);
      span.setAttribute('data-todo-id', todo.id || '');
      span.setAttribute('data-todo-detail', todo.detail || '');
      span.setAttribute('role', 'button');
      span.setAttribute('tabindex', '0');
    } else {
      span.className = 'mention-tag';
      span.setAttribute('data-project', canonical || '');
    }
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

// === 工具卡设备角标（设备角标本名批 2026-10-27 作者令） ===
//
// 作者令原文：「不要用local，本地的也显示本地的设备名」（+ 2026-10-27 07:05 补充
// 裁决：隔离实例 `-iso-<hex8>` 后缀只剥显示面，完整原值放 title/tooltip）。
// 渲染单点：live（chat.js renderTool）与两条历史重放腿（persistence.js localStorage
// 缓存 / 后端历史恢复）共用本 helper——三处逐字同形，禁第二份拷贝。

// 「本机」字面判定词表：引擎侧 `device="local"` 是显式逃生门（fleet.enforceExplicitDevice
// 族），项目 origin 用 "self"（ProjectOrigin.Self）——两字面都按本机解析，大小写不敏感。
const SELF_DEVICE_LITERALS = new Set(['local', 'self']);

/**
 * 设备角标 HTML：入参 = 工具入参的 `device` 原值。
 *   · 本机字面（local/self）⇒ 解析本机设备名：花名册 self（多设备面板同源）→
 *     configData.selfDevice（引擎自身身份；引擎侧无身份文件已含主机名兜底）。
 *     显示名剥 `-iso-<hex8>` 后缀，title 保留完整原值；两源皆缺 ⇒ 空串（不渲染
 *     角标）——**绝不回退「local」字样**。
 *   · 对端名原样直出（现状不变）；空值 ⇒ 空串。
 */
export function deviceTagHtml(rawDevice) {
  const raw = String(rawDevice || '').trim();
  if (!raw) return '';
  let name = raw;
  let titleAttr = '';
  if (SELF_DEVICE_LITERALS.has(raw.toLowerCase())) {
    const self = currentSelfDeviceName();
    if (!self.display) return '';
    name = self.display;
    // 隔离实例（display ≠ full）才挂 tooltip：完整原始名（含 -iso 后缀）只进 title。
    if (self.full && self.full !== self.display) {
      titleAttr = ' title="' + escapeHtml(self.full) + '"';
    }
  }
  return '<span class="tool-device-tag"' + titleAttr + '>' + escapeHtml(name) + '</span>';
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

// ── Status icon helper (ui-status-redesign 2026-10-05, 分叉①=A / ③=lucide) ──
// State markers that must render inside innerHTML templates (state words,
// pills, banners) without waiting for a createIconsIn pass. Renders the SAME
// vendored lucide geometry (kebab→Pascal lookup, currentColor stroke — JS
// strings must never hardcode a color; the consuming CSS class owns the
// semantic token). Falls back to an `<i data-lucide>` placeholder when the
// vendor lib has not loaded yet — a later createIconsIn pass upgrades it.
// Size via width/height attrs (matches agentManager's inline-style usage);
// always aria-hidden when paired with a visible status word — pass
// { labelled: true } for icon-only carriers so the caller attaches
// title/aria-label itself (usage rule ② of the design spec).
const _STATUS_ICON_PASCAL_RE = /(\w)(\w*)(_|-|\s*)/g;
export function statusIcon(name, size = 14, cls = '') {
  let svg = '';
  if (typeof lucide !== 'undefined' && lucide.icons && lucide.createElement) {
    const pascal = name.replace(_STATUS_ICON_PASCAL_RE, (m, c, p) => c.toUpperCase() + p.toLowerCase());
    const icon = lucide.icons[pascal];
    if (icon) {
      const el = lucide.createElement([
        icon[0],
        { ...icon[1], width: String(size), height: String(size), class: `st-ic ${cls}`.trim() },
        icon[2],
      ]);
      svg = el.outerHTML;
    }
  }
  if (!svg) {
    svg = `<i data-lucide="${name}" class="st-ic ${cls}" style="width:${size}px;height:${size}px"></i>`;
  }
  return svg;
}

// Shorthand alias for escapeHtml
export function esc(s) {
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}

// ── P0 面板投影批（设计稿 20261030_background-task-lifecycle §3.4）───────────
// 后台任务健康枚举词表与容错归一（后端 BgHealth.normalize 同款语义，双端
// 兜底）：healthy/suspect/escalated 之外的任何值（缺字段/脏枚举/旧后端）一律
// 回落 'healthy'——面板行永不因脏数据崩，缺字段（旧后端快照）按最保守呈现。
// 返回 '' 表示输入为空串（调用方据此决定零渲染，保持旧行形态不膨胀）。
const BG_HEALTH_WORDS = ['healthy', 'suspect', 'escalated'];
export function bgHealthNorm(raw) {
  const s = String(raw || '').trim();
  if (!s) return '';
  return BG_HEALTH_WORDS.indexOf(s) >= 0 ? s : 'healthy';
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

// ═══ 工具卡折叠单源（ui-unification-20261103-chat-stream → 过程泄漏修复批 2026-11-04）═══
// 「执行命令 — N 行输出」信息密度收敛的**第二版**：默认面 = 一行摘要（工具名 +
// 单行关键参数 + 状态 + 图片引用件），正文与结果默认不进 DOM，整卡点击才装配
// 全文、再点卸下（懒渲染，理由见 mountToolCardBody 头注）。三条默认渲染腿
// （chat.js renderTool + persistence.js 历史与存储两腿）统一经 mountToolCardBody
// 建体；attachToolClick 兼任折叠状态机（整卡可点习惯保留，aria-expanded + title
// 联动，退役的第二行控件见 design-system.css ⑦ 段注）。
// 第一版（6f6b6a81d）的「≤TOOL_PREVIEW_LINES 行可见预览」即作者报的
// 「折叠态被撑长」回归面：TOOL_PREVIEW_LINES / capLines 仅作为 mountToolFold
// 非全文档的兼容读数保留（当前无生产腿走该档）。
export const TOOL_PREVIEW_LINES = 8;

/** card → 全文装配闭包 `(host) => void`（WeakMap 随卡生灭；折叠卡展开时的
 *  全文注入源）。闭包只**装**不返回 HTML 串：图片引用件是带 listener 的
 *  元素，串化会掉接线（tool-card-image-face 批）。 */
const FOLD_BODIES = new WeakMap();

/** delegate 入参的 prompt 原文（buildDelegatePromptHtml 的文本源，供预览截断）。 */
export function delegatePromptRaw(inputJson) {
  if (!inputJson) return null;
  try {
    const inp = typeof inputJson === 'string' ? JSON.parse(inputJson) : inputJson;
    return (inp && typeof inp.prompt === 'string' && inp.prompt) ? inp.prompt : null;
  } catch (e) { return null; }
}

/** 行数封顶：≤ n+1 行原文返回；超出取首 n 行 + 余量计数（「全文 N 行」读数）。 */
function capLines(text, n) {
  const lines = String(text).split('\n');
  if (lines.length <= n + 1) return { text: String(text), more: lines.length - n };
  return { text: lines.slice(0, n).join('\n'), more: lines.length - n };
}

// ══ 工具卡图片引用面（tool-card-image-face 批 2026-11-04）═════════════════
// 作者令：「图片是还是会存在工具卡片泄露出来的问题」。语义裁定：工具卡 = 摘要面
// （命令/参数 + 行数 + 状态），图片**绝不内嵌渲染**——折叠预览与展开全文同权。
// 卡内图片一律折叠为「🖼 图片 ×N」引用件（.tool-img-ref），点击走灯箱单点
// （lightbox.js 唯一出口，本地路径自动票据取数）——绝不内嵌渲染。
//
// 注入路径（本批实证，全库 200k+ 工具行扫描 2026-11-04）：
//   ① 工具结果正文里的 md 图片语法（`![alt](src)`，44 条 /Users|file:|data: 形态：
//      Read md 返图 / Bash cat md / Write 落 md 再回读）—— 本批 string 级折叠；
//   ② Mail 工具卡正文走 renderMarkdownWithMath（chat.js 卡内 markdown 面）——
//      marked 把图片语法渲染成真 <img>（同一批 foldToolCardMedia DOM 兜底收口）。
//
// 面定义：只认**块级独立行**的 md 图片语法（^/$ 锚）——正文行内的 `![…](…)`
// （例如 Bash 输出里碰巧出现的 shell 语法）原样保留，不做图片解读。

/** 独立行 md 图片语法：alt 不跨行，src 不含空白/括号（与链接目标惯例一致）。 */
const RE_TOOL_MD_IMG_LINE = /^[ \t]*!\[([^\]\n]*)\]\(([^()\s]+)[ \t]*\)[ \t]*$/;

/**
 * 工具正文切片：正文行原样保留，连续图片行并成一组（合成「图片 ×N」）。
 * @param {string} src
 * @returns {Array<{kind:'text', text:string}|{kind:'img', paths:string[], alts:string[]}>}
 */
function splitToolBodyImages(src) {
  /** @type {Array<{kind:'text', text:string}|{kind:'img', paths:string[], alts:string[]}>} */
  const blocks = [];
  /** @type {string[]} */
  let buf = [];
  /** @type {{kind:'img', paths:string[], alts:string[]}|null} */
  let cur = null;
  const flushText = () => {
    if (!buf.length) return;
    blocks.push({ kind: 'text', text: buf.join('\n') });
    buf = [];
  };
  for (const line of String(src || '').split('\n')) {
    const m = RE_TOOL_MD_IMG_LINE.exec(line);
    if (m) {
      if (!cur) { flushText(); cur = { kind: 'img', paths: [], alts: [] }; blocks.push(cur); }
      cur.paths.push(m[2]);
      cur.alts.push(m[1]);
      continue;
    }
    cur = null;
    buf.push(line);
  }
  flushText();
  return blocks;
}

/**
 * 「🖼 图片 ×N」引用件（灯箱单点出口；卡内零 <img>）。
 * @param {string[]} paths 图片地址（本地绝对路径 / data: / http(s)）
 * @param {string[]} alts
 */
function toolImgRefEl(paths, alts) {
  const ref = document.createElement('button');
  ref.type = 'button';
  ref.className = 'tool-img-ref';
  const label = t('chat.toolImgRef', { n: paths.length });
  ref.textContent = label;
  ref.setAttribute('aria-label', label);
  ref.dataset.imgCount = String(paths.length);
  ref.addEventListener('click', (e) => {
    e.stopPropagation(); // 不触发卡片折叠状态机（整卡可点）
    // 媒体点击统一批（P16 · 2026-11-11 作者令「电脑端的媒体统一都是canvas打开，
    // 手机端的媒体统一都是直接打开」）：同一分流单点——手机 = 系统直开（铸票/
    // objectURL 链见 mediaOpenRoute）；桌面 = 本机路径直接 Canvas 开件（md 源里
    // 的 /abs/path 形态，readFile 原生可开）；data:/远程 src 桌面维持灯箱。
    // 动态 import：utils 是叶子模块（mediaOpenRoute→popSheet→utils 静态会成环；
    // lightbox/popArtifacts 同理由，与上方灯箱腿同款先例）。
    // mobile-image-lightbox 批（2026-11-17）：「🖼 图片 ×N」引用件本身就是一组
    // ——group = 全部 paths（手机画廊左右滑整组切图）；桌面开首张，逐字不变。
    const items = paths.map((p, i) => ({
      kind: 'image',
      name: alts[i] || p.split('/').pop(),
      src: p,
      path: /^\/(?!\/)/.test(p) ? p : '',
    }));
    const raw = paths[0] || '';
    import('./mediaOpenRoute.js').then((mr) => mr.routeMediaClick(
      items[0] || { kind: 'image', name: alts[0] || raw.split('/').pop(), src: raw, path: /^\/(?!\/)/.test(raw) ? raw : '' },
      (p, n) => import('./popArtifacts.js').then((m) => m.openPopArtifact(p, n)).catch(() => {}),
      () => import('./lightbox.js').then((lb) => lb.openLightbox(raw, alts[0] || '')).catch(() => {}),
      items,
      0
    ));
  });
  return ref;
}

/**
 * 往容器装卡体正文（展开面单源）：文本段走既有高亮/转义管线，图片段装引用件。
 * 过程泄漏修复批（2026-11-04）后**只有展开面**走这里——默认面 = 单行摘要行
 * （label 行 + 图片引用件），正文与结果默认不进 DOM（懒装配）。
 * @param {HTMLElement} container
 * @param {{detailHtml?:string, promptText?:string|null, content?:string|null, label?:string, labelTail?:string}} spec
 * @param {boolean} full true=全文（展开面），false=预览面（≤TOOL_PREVIEW_LINES 行，遗留）
 */
function mountToolFold(container, { detailHtml = '', promptText = null, content = null, label = '', labelTail = '' }, full) {
  if (detailHtml) container.insertAdjacentHTML('beforeend', detailHtml);
  // 摘要行只显 label 首行（单行摘要面）；label 第二行（"Tool(args)\n  (detail)" 形态
  // 的参数行）在这里随展开面回归，内容不因折叠而丢。
  if (labelTail) container.insertAdjacentHTML('beforeend',
    '<div class="detail-cmd">' + escapeHtml(labelTail.trim()) + '</div>');
  if (promptText) {
    const p = full ? { text: promptText } : capLines(promptText, TOOL_PREVIEW_LINES);
    container.insertAdjacentHTML('beforeend',
      '<div class="delegate-prompt"><span class="delegate-prompt-label">Prompt</span><pre class="tool-body-pre">' + escapeHtml(p.text) + '</pre></div>');
  }
  if (!content) return;
  const src = full ? String(content) : capLines(content, TOOL_PREVIEW_LINES).text;
  for (const block of splitToolBodyImages(src)) {
    if (block.kind === 'img') { container.appendChild(toolImgRefEl(block.paths, block.alts)); continue; }
    const highlighted = renderHighlightedContent(block.text, label);
    container.insertAdjacentHTML('beforeend',
      highlighted || '<pre class="tool-body-pre">' + escapeHtml(block.text) + '</pre>');
  }
}

/**
 * DOM 兜底收口：卡内任何漏网 <img>（Mail 卡 markdown 面、未来新腿）一律换成
 * 「🖼 图片 ×N」引用件——工具卡是摘要面，图片永不内嵌（语义单点）。
 * 连续同父图片合成一件。
 * @param {Element|null} root 工具卡元素
 * @returns {number} 折叠掉的 <img> 数
 */
export function foldToolCardMedia(root) {
  if (!root || typeof root.querySelectorAll !== 'function') return 0;
  const imgs = Array.from(root.querySelectorAll('img'));
  if (!imgs.length) return 0;
  const groups = [];
  for (const img of imgs) {
    const last = groups[groups.length - 1];
    if (last && last.parent === img.parentNode && last.tail === img.previousElementSibling) {
      last.items.push(img);
      last.tail = img;
    } else {
      groups.push({ parent: img.parentNode, items: [img], tail: img });
    }
  }
  for (const g of groups) {
    const paths = g.items.map(i => i.getAttribute('src') || '').filter(Boolean);
    const alts = g.items.map(i => i.getAttribute('alt') || '');
    if (paths.length) g.parent.insertBefore(toolImgRefEl(paths, alts), g.items[0]);
    g.items.forEach(i => i.remove());
  }
  return imgs.length;
}

/**
 * 建工具卡体（三条默认腿单源）。
 * 前置：card.innerHTML 已含 icon + .content + .label（本函数只往 .content 组装）。
 *
 * ══ 过程泄漏修复批（作者令 2026-11-04 14:14 · 问题②「折叠态被撑长」）═══════
 * 改前（ui-unification-20261103-chat-stream，commit 6f6b6a81d）：默认面 =
 * `.ds-tool-card__preview` 里 capLines(content, 8) 的**8 行预览** + `.detail-cmd`
 * 命令全文 + 展开控件 —— 卡还没点开就已经 264px 高（实测：24 行输出的 Bash 卡
 * `previewHeight=264px / cardHeight=297px`），作者「之前都只是一行简单的摘要，
 * 点击后才会呈现结果；现在还没点击，内容和结果就已经很长了」。
 *
 * 改后（本批）：默认面 = **一行摘要**——
 *   icon（状态：✓ / ✕）+ label 首行（工具名 — 单行关键参数，CSS nowrap +
 *   ellipsis 超长截断）+ 失败时的「失败」状态词 + 图片引用件（内联 chip）。
 * 正文/结果/命令全文/delegate prompt **默认一律不进 DOM**（懒装配：全文只在
 * 展开瞬间由 FOLD_BODIES 闭包装配，收起即卸）——默认面可见高度 = 0（正文面），
 * 卡片高度 = 一行。
 *
 * 取舍说明（任务要求「懒渲染 vs display:none + aria-hidden 二者择一说明理由」）：
 * 选**懒渲染**。理由 = 本批的判据是「默认态不得出现明细」；display:none 虽然
 * 视觉等价，但 24 行 × N 张卡的正文仍会进 DOM 并参与 hljs 高亮 + 布局度量
 * （#922 perf 台账同族成本），且「DOM 里有」与「默认态不渲染」在机械断言上不可
 * 区分（回归可借 display 反转复发）。懒渲染把「默认态有没有明细」变成 DOM 结构
 * 事实，断言打的是结构而不是样式开关。
 *
 * 返回 { hasBody } —— 真值时腿据此挂 attachToolClick（整卡可点 = 展开/收起）。
 */
export function mountToolCardBody(card, { detailHtml = '', promptText = null, content = null, label = '', labelTail = '' }) {
  const contentEl = card.querySelector('.content');
  if (!contentEl) return { hasBody: false };
  // ── ① 单行摘要面 ──────────────────────────────────────────────────────
  const preview = document.createElement('div');
  preview.className = 'ds-tool-card__preview';
  // label 行（工具名 — 单行关键参数）搬进摘要壳：CSS 单行 nowrap + ellipsis。
  const labelEl = contentEl.querySelector('.label');
  if (labelEl) preview.appendChild(labelEl);
  // 失败必须看得出（作者边界：不许把失败藏起来）——状态词随摘要行（成功由 ✓
  // 图标承载，不再加字：每卡都写「成功」= 纯噪声，visual-style 自解释铁律）。
  if (card.querySelector('.icon.err')) {
    const st = document.createElement('span');
    st.className = 'ds-tool-card__status';
    st.textContent = t('chat.toolStatusFailed');
    preview.appendChild(st);
  }
  // 图片引用件（tool-card-image-face 批语义不变）：卡内图片一律折叠成内联
  // 「🖼 图片 ×N」chip，点击走灯箱单点——摘要行内联，不占额外高度、绝不内嵌。
  if (content) {
    for (const block of splitToolBodyImages(String(content))) {
      if (block.kind === 'img') preview.appendChild(toolImgRefEl(block.paths, block.alts));
    }
  }
  contentEl.appendChild(preview);

  // ── ② 全文槽：空壳（折叠态零内容、零可见高度；.body hook 恒在）────────
  if (!detailHtml && !promptText && !content && !labelTail) return { hasBody: false };
  const body = document.createElement('div');
  body.className = 'body';
  contentEl.appendChild(body);
  const total = (content ? String(content).split('\n').length : 0)
    + (promptText ? String(promptText).split('\n').length : 0);
  card.dataset.nfToolFoldTotal = String(total);
  card.setAttribute('aria-expanded', 'false');
  // 展开提示走 title（无视觉噪声的可达性面；i18n 键沿用同批成对键）
  card.title = t('chat.toolExpandMore', { n: total });
  FOLD_BODIES.set(card, (host) => {
    mountToolFold(host, { detailHtml, promptText, content, label, labelTail }, true);
  });
  return { hasBody: true };
}

// === Tool card click handler（整卡点击；折叠卡走状态机，遗留卡原样开合） ===
export function attachToolClick(card) {
  card.addEventListener('click', () => {
    const body = card.querySelector('.body');
    if (!body) return;
    const buildFull = FOLD_BODIES.get(card);
    if (buildFull) {
      // 折叠状态机：全文只在展开瞬间进 DOM，收起即卸（往返无泄漏）。
      const opening = !body.classList.contains('open');
      if (opening) {
        body.innerHTML = '';
        buildFull(body);          // 装配式（图片引用件带 listener，不能串化）
        body.classList.add('open');
      } else {
        body.innerHTML = '';
        body.classList.remove('open');
      }
      // aria-expanded 与 title 跟着状态走（摘要行常驻可见，不随展开消失）
      card.setAttribute('aria-expanded', opening ? 'true' : 'false');
      card.title = opening
        ? t('chat.toolCollapse')
        : t('chat.toolExpandMore', { n: Number(card.dataset.nfToolFoldTotal || 0) });
    } else {
      body.classList.toggle('open');
      card.setAttribute('aria-expanded', body.classList.contains('open') ? 'true' : 'false');
    }
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
    rec = { el: null, pill: null, count: 0, unreadRows: new Set(), atBottom: true, obs: null };
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
  if (rec.pill && rec.pill.el.isConnected) return rec.pill;
  const host = pillHost(view);
  if (!host) return null;
  const chat = viewChat(view) || view.dom.chat;
  // 胶囊单实现批（2026-11-06）：本体 = `js/scrollPill.js` 唯一工厂，与
  // 好友/群/设备会话窗（messages.js）、搜索回跳（searchReturnPill.js）同一枚
  // `.scroll-new-pill`（同一形态声明 css/chat.css）。本文件只保留 view 级**状态**
  // （count 计数 / 贴底 latch），几何与显隐口径全在组件里 —— 改一处四处同时改。
  rec.pill = createScrollPill({
    host,
    scrollEl: chat,
    // 底部参照件：主窗 = #input-area（多行输入会长高，组件每次落位现算）；
    // agent 弹窗 = 其 footer 条。
    anchor: () => (host.classList.contains('flow-agent-modal')
      ? host.querySelector('.flow-agent-footer')
      : document.getElementById('input-area')),
    onReturn: () => {
      rec.count = 0;
      rec.unreadRows.clear();
      if (view.stream) view.stream.scrollSnapped = true;
    },
  });
  rec.el = rec.pill.el;
  return rec.pill;
}

/** Park the pill just above whatever sits at the bottom of the view.
 *  单实现批：落位公式只在组件里（`scrollPill.park`，视口矩形反算，手机层收编窗
 *  与桌面窗同一式）——本文件不再保留第二份 offset 算术。 */
function positionScrollPill(view, el) {
  const rec = pillRecord(view);
  if (rec.pill) rec.pill.refresh();
}

/** Re-park a visible pill (called by main.js's #input-area ResizeObserver so a
 *  growing multi-line input can never be covered by the pill). */
export function refreshScrollPill(view) {
  const rec = view ? _pillRecords.get(view) : null;
  if (rec && rec.pill) rec.pill.refresh();
}

// ── "real new message" criterion (2026-09-13 口径裁定) ───────────────────
// Author 2026-09-12 20:36 (verbatim): 「新消息提醒的数字统计，目前应该是把思考
// 过程，工具都算了，只统计真的新消息。也就是没被自动收起的那些消息。」 The pill
// is the only user-visible counter that swallowed process noise: one turn
// (thinking + 5 tool calls + final reply) read as 9.
//
// COUNTED — what the author actually receives as a message:
//   .row.user is excluded: it was sent by this session.
//   .row.ai   (assistant visible reply)      W1
//   .row.card-content (card deliverable)     D4
//   .row.error (failed-turn terminal)        D5
//   .row.user + .bubble.injected is excluded under the user-role policy.
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
  if (row.classList.contains('user')) return false;
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
  const pill = ensureScrollPill(view);
  if (!pill) return;
  const chat = viewChat(view) || view.dom.chat;
  if (atBottom === undefined) atBottom = isNearBottom(chat);
  rec.atBottom = atBottom;
  if (atBottom) { rec.count = 0; rec.unreadRows.clear(); } // Reaching bottom marks all seen.
  // 显隐口径单源在组件：无未读但距底 ≥ SHOW_AFTER_PX ⇒ 「回到底部」；
  // 有未读 ⇒ 「↓ N 条新消息」——同一枚胶囊的两个文案档（单实现批 2026-11-06）。
  pill.sync({ count: rec.count, atBottom });
}

/** Pill click 腿已上抬组件（`scrollPill.js` 的 `scrollToBottom`：平滑贴底 +
 *  计数清零 + 尊重 reduced-motion）——本文件不再留第二份点击实现。 */

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
    const arrived = [];
    const viewportH = chat.clientHeight || 0;
    for (const m of mutations) {
      for (const n of m.removedNodes) {
        if (!n.isConnected && rec.unreadRows.delete(n)) rec.count = Math.max(0, rec.count - 1);
      }
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
        if (n.isConnected && rec.atBottom === false && !rec.unreadRows.has(n)) {
          rec.unreadRows.add(n);
          arrived.push({ role: 'ai' });
        }
      }
    }
    // Rows that arrive while the user is away from the bottom are the only
    // ones that count ("since leaving the bottom"). `rec.atBottom` is the
    // pre-append snapshot: scroll events and the post-append rAF below keep
    // it current, so a big card appended *while pinned* is not counted.
    rec.count = countUnread(rec.count, arrived, rec.atBottom);
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
  // ui-status-redesign（盘点 #43 / S6）：词前 circle-x 红图标 + 中性词。
  live.innerHTML = statusIcon('circle-x', 10, 'st-err') + t('chat.copyFailed');
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
    id.startsWith('node-') || id.startsWith('dispatcher-') ||
    // external-executors 批 #1225：外部 CLI delegate 会话（external-<exec>-<id>）。
    id.startsWith('external-')
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
