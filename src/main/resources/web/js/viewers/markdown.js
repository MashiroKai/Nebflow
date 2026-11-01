// viewers/markdown.js — Markdown viewer (rendered preview + source toggle).
// Migrated verbatim from fileViewers.js (viewMarkdown).
//
// wiki-graph batch (2026-10-14): Obsidian-compatible document face —
//   · `[[wikilinks]]` render as clickable cross-document references
//     (resolution through the /api/wiki/graph index; unresolved targets stay
//     visible and explain themselves on click).
//   · Reference jumps carry an anchor (block `^id` / heading / `L<line>`) —
//     the viewer scrolls the target paragraph to the top and flash-highlights
//     it (paper-style "first mention must explain or reference" reading flow).
//   · Floating glass toggles: in-file find (same family as the source toggle)
//     and "open in wiki graph".

import { addSourceToggle, addFindToggle, routeLocalHref, openLocalFileTab, openExternalUrlTab } from './shared.js';
//（readerChrome 挂载已随 Canvas 改造批⑦退役：2026-10-31 作者令「阅读器视角
//  仅电子书格式生效（epub/mobi/azw3 等）」——md 退出统一阅读 chrome，正文
//  滚回 .canvas-md-scroll 原生滚动面；阅读位记账（pane._mdScrollPos +
//  canvas-tab-activated 恢复）是 pane 级机制、不依赖 chrome，逐字保留。）
import { mintTickets, stripCredentialParams, decodePathParam } from '../nfTicket.js';
// 二次加载清剿批（2026-10-24 · #816 同法）：文档图片字节改经 **nfFileCache 账本
// 单点**——`nfViewerUrl` 命中零抓取 / 未命中取数即入账；blob 解码失败自愈逐出
// （`nfCacheDrop`，见下方 __nfMdImgFail）。
import { nfViewerUrl, nfCacheDrop } from '../nfFileCache.js';
import { noteAuthRejected } from '../sessionGate.js';
import { t } from '../i18n.js';
import { isMdHtmlFile } from '../mdHtmlGate.js';
// mermaid-md batch (2026-10-23): explicit hydration at the primary document
// face (this viewer + wiki doc opens). The global observer in main.js covers
// every other markdown face; this call is idempotent (state-attribute guard).
import { hydrateMermaidIn } from '../mermaidRenderer.js';

/** Scroll one heading to the TOP of the markdown content scroller — and of nothing
 *  else.
 *
 *  Why not `Element.scrollIntoView({ block: 'start' })` (what this path used until
 *  2026-09-20): the CSSOM View algorithm is NOT scoped to the nearest scroll
 *  container. It walks the target's entire ancestor chain and aligns the target
 *  against EVERY scrollable box on the way, each one clamped to its own scroll
 *  range — and `overflow: hidden` boxes are scroll containers too (they are just
 *  not user-scrollable). So one TOC click also wrote whatever residual scroll each
 *  ancestor happened to carry, including the root scroller: pointer-up on a
 *  directory item moved the whole client (author report 2026-09-20 22:56, Windows:
 *  「不仅是内容跳转了，nebflow 客户端整体也上移了一点」). Measured in the harness
 *  (tests/mdscroll-toc-scroll.spec.mjs): with a residual on the root scroller the
 *  click moved `documentElement.scrollTop` 0 → +N and `window.scrollY` 0 → +N; with a
 *  residual inside the canvas panel `#canvas-panel.scrollTop` moved with it. The
 *  intended effect is one container's own scrollTop, so it is written directly and
 *  nothing else may move — same discipline as messages.js `revealQuoteTarget`
 *  (「🔴 不使用 scrollIntoView：它会把滚动写进任一 overflow:hidden 祖先」).
 *
 *  `block: 'start'` semantics are preserved exactly: the heading's top edge lands on
 *  the scrollport's top edge, clamped to [0, max]. A heading near the document end
 *  therefore cannot be aligned — that clamp is the browser's own behaviour, not a
 *  precision regression (harness judges the landing against the same clamped
 *  expectation).
 *
 *  @param {HTMLElement} mdViewer `.canvas-md-viewer` — the scroller is its parent
 *    (this viewer builds `<div class="canvas-md-scroll"><div class="canvas-md-viewer">`)
 *  @param {HTMLElement} target the heading to bring to the top */
function scrollHeadingToTop(mdViewer, target) {
  const scroller = /** @type {HTMLElement|null} */ (mdViewer.closest('.canvas-md-scroll'));
  // No scroller in the chain (an unexpected host mounted this viewer) ⇒ nothing to
  // scroll. Deliberately NOT a scrollIntoView fallback: that is the leak itself.
  if (!scroller) return;
  const max = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
  const delta = target.getBoundingClientRect().top - scroller.getBoundingClientRect().top;
  scroller.scrollTo({
    top: Math.max(0, Math.min(max, scroller.scrollTop + delta)),
    behavior: 'smooth',
  });
}

// ── Source analysis (block map + ^block-id extraction) ────────────────
// The rendered graph needs two maps back into the SOURCE the user's links
// point at: which source line each rendered top-level block came from (line
// anchors `L<n>`), and where each `^block-id` lives. Block-id markers are
// stripped from the rendered string — Obsidian hides them in preview too.

const FENCE_RE = /^\s*(```+|~~~+)/;
const BLOCK_ID_LINE_RE = /^(\s*(?:#{1,6}\s+)?[^\n]*\S)[ \t]+\^([A-Za-z0-9][A-Za-z0-9-]*)[ \t]*$/;

/**
 * @param {string} content raw markdown
 * @returns {{ html: string, blockStarts: number[], blockIds: Map<string, number> }}
 */
function analyzeSource(content) {
  let lines = content.split('\n');
  // Frontmatter is metadata, not content — blank its lines (count preserved,
  // so block-id and block-start line numbers stay in FULL-FILE coordinates
  // and every anchor agrees with the authoring view).
  if (lines[0] && lines[0].trim() === '---') {
    for (let i = 1; i < lines.length; i++) {
      if (lines[i].trim() === '---') { lines = lines.map((l, j) => (j <= i ? '' : l)); break; }
    }
  }
  const blockIds = new Map();
  const stripped = lines.map((line, i) => {
    const m = BLOCK_ID_LINE_RE.exec(line);
    if (m) {
      if (!blockIds.has(m[2])) blockIds.set(m[2], i + 1);
      return m[1];
    }
    return line;
  });
  // Fence-aware block starts: a line begins a new top-level block when the
  // previous line is blank, or when it opens a heading / a first quote line.
  const blockStarts = [];
  let inFence = false;
  let fenceMarker = '';
  for (let i = 0; i < stripped.length; i++) {
    const line = stripped[i];
    const fenceMatch = FENCE_RE.exec(line);
    if (fenceMatch) {
      if (!inFence) {
        inFence = true;
        fenceMarker = fenceMatch[1].slice(0, 3);
        blockStarts.push(i + 1);
      } else if (fenceMatch[1].startsWith(fenceMarker)) {
        inFence = false;
        fenceMarker = '';
      }
      continue;
    }
    if (inFence) continue;
    const prev = i > 0 ? stripped[i - 1] : '';
    const prevBlank = prev.trim() === '';
    const prevQuote = prev.startsWith('>');
    const isHeading = line.startsWith('#');
    const isQuoteStart = line.startsWith('>') && !prevQuote;
    if (i === 0 || prevBlank || isHeading || isQuoteStart) blockStarts.push(i + 1);
  }
  if (blockStarts.length === 0) blockStarts.push(1);
  return { html: stripped.join('\n'), blockStarts, blockIds };
}

/** Tag each rendered top-level element with its source line (data-src-line).
 *  When the element count matches the block count the mapping is exact;
 *  otherwise it degrades proportionally — close enough to flash the right
 *  neighborhood, never silently absent. */
function assignSourceLines(mdViewer, blockStarts) {
  const kids = /** @type {HTMLElement[]} */ (Array.from(mdViewer.children));
  if (!kids.length || !blockStarts.length) return;
  kids.forEach((el, i) => {
    const idx = kids.length === blockStarts.length
      ? i
      : Math.min(blockStarts.length - 1, Math.floor((i / kids.length) * blockStarts.length));
    el.dataset.srcLine = String(blockStarts[idx]);
  });
}

/**
 * Apply a reference-jump anchor to the rendered document: locate the target
 * block, scroll it to the top of the scroller and flash-highlight it.
 * Anchor forms: {kind:'block', id} · {kind:'heading', text} · {kind:'line', line}
 * (the same payload `openLocalFileTab` dispatches; PDF pageStart is consumed
 * elsewhere).
 * @param {HTMLElement} mdViewer
 * @param {any} anchor
 * @param {Map<string, number>} [blockIds]
 */
function applyJumpAnchor(mdViewer, anchor, blockIds) {
  if (!anchor || !mdViewer) return;
  let target = null;
  if (anchor.kind === 'block' && anchor.id && blockIds && blockIds.has(anchor.id)) {
    const line = blockIds.get(anchor.id);
    target = blockAtOrBefore(mdViewer, line);
  } else if (anchor.kind === 'heading' && anchor.text) {
    // Same slug discipline the TOC click handler uses (marked v12 encodes
    // CJK hrefs; ids here are raw).
    const slugCounts = {};
    const slugify = (text) => (text || '')
      .toLowerCase()
      .replace(/[^\w\s\u4e00-\u9fff-]/g, '')
      .trim()
      .replace(/\s+/g, '-')
      .replace(/-+/g, '-');
    const want = slugify(anchor.text);
    mdViewer.querySelectorAll('h1, h2, h3, h4, h5, h6').forEach((h) => {
      if (h.id === want) target = h;
    });
    if (!target) {
      // Fall back to a text match over the headings.
      mdViewer.querySelectorAll('h1, h2, h3, h4, h5, h6').forEach((h) => {
        if (!target && (h.textContent || '').trim() === anchor.text.trim()) target = h;
      });
    }
  } else if (anchor.kind === 'line' && Number.isFinite(anchor.line)) {
    target = blockAtOrBefore(mdViewer, anchor.line);
  }
  if (!target) return;
  scrollHeadingToTop(mdViewer, target);
  // 落点时间戳：阅读位恢复（canvas-tab-activated 腿）在跳后 2s 窗口内让位——
  // 落点滚动是新真源，不被旧阅读位拽回。
  const jumpPane = /** @type {any} */ (mdViewer.closest('.canvas-tab-pane'));
  if (jumpPane) jumpPane._mdJumpAt = Date.now();
  target.classList.remove('nf-jump-flash');
  // Force a reflow so consecutive jumps restart the animation.
  void target.offsetWidth;
  target.classList.add('nf-jump-flash');
  target.addEventListener('animationend', () => target.classList.remove('nf-jump-flash'), { once: true });
}

/** The rendered block whose data-src-line is the greatest one ≤ `line`. */
function blockAtOrBefore(mdViewer, line) {
  let best = null, bestLine = -1;
  mdViewer.querySelectorAll('[data-src-line]').forEach((el) => {
    const l = parseInt(el.dataset.srcLine || '0', 10);
    if (l <= line && l > bestLine) { best = el; bestLine = l; }
  });
  return best || mdViewer.querySelector('[data-src-line]');
}

// ── Wikilink graph index (module-level, TTL-cached) ───────────────────

/** @type {{at:number, data:any}|null} */
let wikiIndexCache = null;
const WIKI_INDEX_TTL = 30_000;

async function wikiGraph() {
  if (wikiIndexCache && Date.now() - wikiIndexCache.at < WIKI_INDEX_TTL) return wikiIndexCache.data;
  const { authHeaders } = await import('../flowHelpers.js');
  const res = await fetch('/api/wiki/graph', { headers: { ...authHeaders() } });
  if (!res.ok) {
    // #762 图谱 403 族：本函数有两条腿，纪律按 authgate 批（2026-10-22）分流——
    //   · 渲染腿（renderWikilinks，文档打开即跑）= 背景面：静默降级（链接留待
    //     点击时重解析），绝不拉重登（nfTicket authgate 批「渲染即重登」禁令同源）；
    //   · 点击腿（onWikilinkClick，用户主动点链接）= 用户面：401/403 交
    //     sessionGate 重认证伞（下方调用点）。err.status 供调用点分流。
    const err = new Error('wiki graph unavailable: ' + res.status);
    /** @type {any} */ (err).status = res.status;
    throw err;
  }
  const data = await res.json();
  wikiIndexCache = { at: Date.now(), data };
  return data;
}

/**
 * Resolve an Obsidian-style link target against the graph index.
 * Returns { absPath } for a local hit, { remote } for a peer doc, or null.
 * 词条引用批 batch 2 (F1): after BOTH document legs fail, a target with no
 * explicit anchor falls through to the TERM REGISTRY third leg — the graph
 * payload already carries `nodes[].terms[]` (batch-1 projection), so this is
 * a pure client-side lookup with zero extra requests. The hit reuses the
 * resolved shape and adds `anchor:{kind:'block', id}` (the definition's block
 * anchor — the deep-link landing) plus `term:{name,definition,rel,anchor,
 * source}` (the hover-bubble payload). Ambiguity never guesses, mirroring the
 * engine's selectTerm: a UNIQUE glossary hit wins, else a unique hit anywhere
 * in the registry scope, else null (the link stays unresolved).
 * @param {string} target raw target (no anchor/alias)
 * @param {string} baseDir the current document's directory ('' when unknown)
 * @param {any} data graph JSON
 * @param {{project?: string|null, allowTerm?: boolean}} [opts] project scopes
 *   the term registry (engine: per-vault); allowTerm=false skips the third leg
 *   (targets the author anchored explicitly are doc+anchor intent, never term
 *   citations — [[GCR#某节]] must not re-route).
 */
function resolveWikiTarget(target, baseDir, data, opts) {
  const norm = (s) => String(s || '').replace(/\\/g, '/').replace(/\/+$/, '').toLowerCase();
  const clean = target.replace(/\\/g, '/').replace(/^\.\/+/, '');
  const withMd = clean.toLowerCase().endsWith('.md') ? clean : clean + '.md';
  const base = norm(baseDir);
  const nodes = (data && data.nodes) || [];
  const projects = (data && data.projects) || [];
  const sameVault = (n) => {
    const p = projects.find((pj) => pj.name === n.project);
    if (!p) return null;
    const vault = norm(p.vault);
    const nodeDir = norm(vault + '/' + n.rel.split('/').slice(0, -1).join('/'));
    return nodeDir === base ? p : null;
  };
  // 1. Vault-relative exact path, preferring THIS document's vault.
  let hit = nodes.find((n) => !n.stub && n.rel.toLowerCase() === withMd && sameVault(n));
  // 2. Unique basename across all vaults (Obsidian's shortest-name rule).
  if (!hit) {
    const byBase = nodes.filter((n) => !n.stub && n.rel.toLowerCase().split('/').pop() === withMd.split('/').pop());
    if (byBase.length === 1) hit = byBase[0];
    else if (byBase.length > 1) {
      // Prefer a doc in the same vault, then self-device docs.
      hit = byBase.find((n) => sameVault(n)) || byBase.find((n) => n.device === 'self') || null;
    }
  }
  if (!hit) {
    // 3. Term registry (词条引用批 batch 2, F1) — documents first, terms fill
    // only the gap (裁定 §9-4: today's doc hits stay doc hits tomorrow).
    if (!opts || opts.allowTerm !== false) {
      const termHit = resolveTermHit(target, data, opts ? opts.project : null);
      if (termHit) return termHit;
    }
    return null;
  }
  const proj = projects.find((pj) => pj.name === hit.project);
  if (!proj) return null;
  if (hit.device === 'self') {
    const sep = proj.vault.includes('\\') ? '\\' : '/';
    return { absPath: proj.vault.replace(/[\\/]+$/, '') + sep + hit.rel.split('/').join(sep) };
  }
  return { remote: { device: hit.device, vault: proj.vault, rel: hit.rel, project: hit.project } };
}

/** Term-registry lookup over the merged graph payload (F1 third leg). Key
 *  normalization mirrors the engine's termTargetKey (trailing doc extension
 *  stripped, case-insensitive); the registry scope is the linking document's
 *  project (the engine derives the registry per vault — the merged view needs
 *  the project filter to reproduce that scope). */
function resolveTermHit(target, data, currentProject) {
  if (!data || !Array.isArray(data.nodes)) return null;
  const key = String(target || '').trim().replace(/\\/g, '/')
    .replace(/\.markdown$/i, '').replace(/\.md$/i, '').toLowerCase();
  if (!key) return null;
  return resolveTermKey(key, data, currentProject);
}

// 词条键索引（B5-1）：`key → [{node, term}]` 只建一次，按 data 对象弱引用缓存。
// 正文裸词识别要为每个落点做一次消解，逐次全量扫描 nodes×terms 在万级词条库
// 上就是 O(链接数 × 词条数)；索引把这一步压成一次建表 + O(1) 查表。
/** @type {WeakMap<object, Map<string, any[]>>} */
const termKeyIndexCache = new WeakMap();

function termKeyIndex(data) {
  let idx = termKeyIndexCache.get(data);
  if (idx) return idx;
  /** @type {Map<string, any[]>} */
  idx = new Map();
  for (const n of data.nodes) {
    if (!n || n.stub || !Array.isArray(n.terms)) continue;
    for (const term of n.terms) {
      const keys = [term.name].concat(term.aliases || [])
        .map((k) => String(k || '').trim().toLowerCase())
        .filter(Boolean);
      for (const k of new Set(keys)) {
        if (!idx.has(k)) idx.set(k, []);
        idx.get(k).push({ node: n, term });
      }
    }
  }
  termKeyIndexCache.set(data, idx);
  return idx;
}

/**
 * 一个已归一化的词条键 → 消解结果（`[[词条]]` 与正文裸词两条路共用同一套
 * 消解裁定：唯一 glossary 命中优先于 doc-intro；同级多命中永不猜）。
 * @param {string} key 小写键
 * @param {any} data graph 载荷
 * @param {string|null} currentProject 作用域工程
 */
function resolveTermKey(key, data, currentProject) {
  const candidates = termKeyIndex(data).get(key) || [];
  if (!candidates.length) return null;
  const scoped = currentProject
    ? candidates.filter((c) => c.node.project === currentProject)
    : candidates;
  const pool = scoped.length ? scoped : candidates;
  // selectTerm mirror (第一批裁定 5): unique glossary hit wins over doc-intro
  // hits; multiple same-tier hits never guess.
  const glossaryHits = pool.filter((c) => c.term.source === 'glossary');
  const pick = glossaryHits.length === 1 ? glossaryHits[0]
    : glossaryHits.length > 1 ? null
    : pool.length === 1 ? pool[0] : null;
  if (!pick) return null;
  const project = (data.projects || []).find((p) => p.name === pick.node.project);
  const resolved = resolvedForNode(pick.node, project);
  if (!resolved) return null;
  const anchorId = String(pick.term.anchor || '').replace(/^#\^/, '');
  if (!anchorId) return null;
  resolved.anchor = { kind: 'block', id: anchorId };
  resolved.term = {
    name: pick.term.name,
    definition: pick.term.definition || '',
    rel: pick.term.rel || pick.node.rel,
    anchor: pick.term.anchor,
    source: pick.term.source || '',
    doc: pick.node.title || '',   // 所在文档标题（气泡面；空则回落 rel 基名）
  };
  return resolved;
}

// ── Term hover bubble ─────────────────────────────────────────────────────
// 词条 hover 气泡机制本体已抽为共享件 ../termTip.js（human-face 批
// 2026-10-30）：docs viewer 与 chat 等无图谱面共用同一份气泡/兜底腿实现。
// 本文件只消费其导出面；深链导航回调在模块装载时注入（onWikilinkClick）。
import { attachTermFace, termTapExpand, hideTermTip, setTermTipNav } from '../termTip.js';
// onWikilinkClick 是函数声明（提升），模块装载时即可注入气泡的深链导航回调。
setTermTipNav(onWikilinkClick);
// 正文裸词识别（B5-1）：匹配规则是纯函数（零依赖、可离线断言），DOM 面的排除
// 判据仍在本文件的 TreeWalker 里 —— 下面 renderAutoTerms 与 renderWikilinks 用
// 同一套 acceptNode 纪律（CODE/PRE/A/.katex 一律不进）。
import { termsForScope, buildTermMatcher } from '../termLexicon.js';

/**
 * 正文裸词识别（B5-1，2026-11-01 文档增强腿）。
 *
 * 与 `[[词条]]` 的关系：显式引用原本就通篇可点（五件已有设施），缺的是**作者
 * 没写括号的裸词**。本 pass 把已注册词条在正文里的**首次出现**变成 `.nf-term`
 * 链接，消解走同一套裁定（`resolveTermKey`），气泡/深链/触屏首击全部复用
 * `attachTermFace` 与既有点击委派 —— 零新机制、零新请求。
 *
 * 排除面（缺一条就会静默出错，见 verify-md-term.mjs 的「0 命中」四条）：
 *   · 代码围栏 / 行内代码（CODE、PRE 子树）—— 把代码里的符号标成链接是最坏坏法；
 *   · 既有链接（A 子树）—— 输出 `<a>` 套 `<a>` 是非法 HTML；
 *   · KaTeX 公式（`.katex` 子树，含其 TeX 源 `<annotation>`）；
 *   · 标题（h1–h6）—— 标题是结构面，词条首现留在正文段落；
 *   · 词条自己的声明文档 —— glossary 里 `- GCR — …` 不加自指链接。
 *
 * @param {HTMLElement} mdViewer
 * @param {any} data graph 载荷（`nodes[].terms[]` = 正文块锚段落派生）
 * @param {string|null} projectName 作用域工程
 * @param {string} [absPath] 当前文档路径（用于剔除「自己的声明文档」）
 * @returns {number} 实际生成的链接数（断言面用）
 */
function renderAutoTerms(mdViewer, data, projectName, absPath) {
  if (!data || !mdViewer) return 0;
  const selfKey = normVaultPath(absPath);
  const terms = termsForScope(data, projectName).filter((term) => {
    // 自己的声明文档不链自己：把 term.rel 还原成绝对路径比较（工程 vault + rel）。
    if (!selfKey || !term.rel) return true;
    const proj = (data.projects || []).find((p) => p.name === term.project);
    if (!proj || !proj.vault) return true;
    return normVaultPath(String(proj.vault).replace(/[\\/]+$/, '') + '/' + term.rel) !== selfKey;
  });
  if (!terms.length) return 0;
  const matcher = buildTermMatcher(terms);
  if (!matcher.size) return 0;

  const walker = document.createTreeWalker(mdViewer, NodeFilter.SHOW_TEXT, {
    acceptNode: (node) => {
      const p = node.parentElement;
      if (!p) return NodeFilter.FILTER_REJECT;
      const tag = p.tagName;
      if (tag === 'CODE' || tag === 'PRE' || tag === 'SCRIPT' || tag === 'STYLE' || tag === 'A') return NodeFilter.FILTER_REJECT;
      if (/^H[1-6]$/.test(tag)) return NodeFilter.FILTER_REJECT;
      if (p.closest('pre, code, a, .katex, .nf-term')) return NodeFilter.FILTER_REJECT;
      return node.nodeValue && node.nodeValue.trim() ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
    },
  });
  const textNodes = [];
  while (walker.nextNode()) textNodes.push(walker.currentNode);

  let made = 0;
  for (const node of textNodes) {
    const value = node.nodeValue || '';
    const hits = matcher.find(value);
    if (!hits.length) continue;
    const frag = document.createDocumentFragment();
    let last = 0;
    for (const hit of hits) {
      if (hit.start > last) frag.appendChild(document.createTextNode(value.slice(last, hit.start)));
      const a = document.createElement('a');
      a.className = 'nf-term';
      a.href = '#';
      a.textContent = hit.text;              // 保留原文大小写
      a.dataset.term = hit.term.name;        // 消解在下面统一做（一次建表，O(1)/命中）
      frag.appendChild(a);
      made++;
      last = hit.end;
    }
    if (last < value.length) frag.appendChild(document.createTextNode(value.slice(last)));
    node.parentNode?.replaceChild(frag, node);
  }

  // 消解 + 挂面：命中才留链接（歧义/越界 = 退回裸文本，绝不猜）。
  mdViewer.querySelectorAll('a.nf-term').forEach((el) => {
    const a = /** @type {HTMLAnchorElement} */ (el);
    const key = String(a.dataset.term || '').trim().toLowerCase();
    const hit = key ? resolveTermKey(key, data, projectName || null) : null;
    if (!hit) {
      a.replaceWith(document.createTextNode(a.textContent || key));
      made--;
      return;
    }
    a.dataset.wikiResolved = JSON.stringify(hit);
    a.dataset.wikiTarget = String(a.dataset.term || '');   // 非空 ⇒ 点击腿走 resolved.absPath 深链（不是本文自锚）
    if (hit.anchor && hit.anchor.kind === 'block' && hit.anchor.id) a.dataset.wikiAnchor = '^' + hit.anchor.id;
    if (hit.term) attachTermFace(a, mdViewer, { term: hit.term });
  });
  return made;
}

/** The canvas tab id hosting this viewer — the single-layer source a reference
 *  jump records (F5 返回原文档；canvas.js openWorkspaceItem 消费). */
function refSourceTabId(mdViewer) {
  const pane = mdViewer && mdViewer.closest ? mdViewer.closest('.canvas-tab-pane') : null;
  return (pane && pane.dataset && pane.dataset.tabId) || undefined;
}

/**
 * Post-render pass: turn `[[wikilink]]` text occurrences into reference
 * anchors. Runs over text nodes so fenced code and inline code stay literal.
 * @param {HTMLElement} mdViewer
 * @param {string} baseDir current document directory
 * @param {string} [absPath] current document path (scopes the term registry)
 */
async function renderWikilinks(mdViewer, baseDir, absPath) {
  let data = null;
  try { data = await wikiGraph(); } catch (e) { data = null; }
  // `![[embed]]` rides the same regex (Obsidian embed syntax): image embeds
  // keep the literal for now, everything else (literature PDFs, figures) is
  // turned into the reference link — one click opens it in canvas.
  const WIKI_TEXT_RE = /(!?)\[\[([^\[\]]+?)\]\]/g;
  const walker = document.createTreeWalker(mdViewer, NodeFilter.SHOW_TEXT, {
    acceptNode: (node) => {
      const p = node.parentElement;
      if (!p) return NodeFilter.FILTER_REJECT;
      const tag = p.tagName;
      if (tag === 'CODE' || tag === 'PRE' || tag === 'SCRIPT' || tag === 'STYLE' || tag === 'A') return NodeFilter.FILTER_REJECT;
      if (p.closest('pre, code, a.nf-wikilink')) return NodeFilter.FILTER_REJECT;
      return node.nodeValue && /\!?\[\[/.test(node.nodeValue) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
    },
  });
  const textNodes = [];
  while (walker.nextNode()) textNodes.push(walker.currentNode);
  for (const node of textNodes) {
    const value = node.nodeValue || '';
    WIKI_TEXT_RE.lastIndex = 0;
    if (!WIKI_TEXT_RE.test(value)) continue;
    WIKI_TEXT_RE.lastIndex = 0;
    const frag = document.createDocumentFragment();
    let last = 0;
    let m;
    while ((m = WIKI_TEXT_RE.exec(value)) !== null) {
      if (m.index > last) frag.appendChild(document.createTextNode(value.slice(last, m.index)));
      const embed = m[1] === '!';
      const inner = m[2];
      const pipe = inner.indexOf('|');
      const body = pipe >= 0 ? inner.slice(0, pipe) : inner;
      const hash = body.indexOf('#');
      const target = hash >= 0 ? body.slice(0, hash).trim() : body.trim();
      const anchorRaw = hash >= 0 ? body.slice(hash + 1).trim() : '';
      const alias = pipe >= 0 ? inner.slice(pipe + 1).trim() : '';
      const a = document.createElement('a');
      a.className = 'nf-wikilink';
      a.href = '#';
      a.textContent = alias || target || anchorRaw;
      a.dataset.wikiTarget = target;      // '' = self-reference ([[#Heading]])
      a.dataset.wikiAnchor = anchorRaw;   // '^id' or 'Heading'
      frag.appendChild(a);
      last = m.index + m[0].length;
    }
    if (last < value.length) frag.appendChild(document.createTextNode(value.slice(last)));
    node.parentNode?.replaceChild(frag, node);
  }
  // Resolution pass (best-effort, after all links exist).
  // 词条引用批 batch 2: the term-registry scope follows the linking document's
  // project (engine derives the registry per vault — graphNodeFor maps this
  // document into its project; outside every vault = merged view scope).
  const currentProject = (absPath && data)
    ? ((graphNodeFor(absPath, data) || {}).project || {}).name || null
    : null;
  mdViewer.querySelectorAll('a.nf-wikilink').forEach((el) => {
    const a = /** @type {HTMLAnchorElement} */ (el);
    const target = a.dataset.wikiTarget || '';
    if (!target) { a.classList.add('nf-wikilink-self'); return; }
    const anchorRaw = a.dataset.wikiAnchor || '';
    const hit = data
      ? resolveWikiTarget(target, baseDir, data, { project: currentProject, allowTerm: !anchorRaw })
      : null;
    if (hit) {
      a.classList.add('nf-wikilink-resolved');
      a.dataset.wikiResolved = JSON.stringify(hit);
      // 词条命中：挂 hover 气泡 + 点击深链（F3；数据在 dataset 里零请求）。
      if (hit.term) attachTermFace(a, mdViewer, { term: hit.term });
    } else {
      a.classList.add('nf-wikilink-unresolved');
      a.title = t('wiki.unresolved');
      // 兜底腿（F3②）：无锚短形引用在载荷里查不到时，hover 打 term API
      // 出泡（词条文档未重扫的 30s 窗口里引用仍然可读）。带锚目标 =
      // 文档+锚意图，永不进词条面。
      if (!anchorRaw) attachTermFace(a, mdViewer, { fallbackName: target });
    }
  });
  // 正文裸词识别（B5-1）：显式 `[[词条]]` 全部就位后再跑（walker 已拒 A 子树，
  // 这里是顺序兜底：绝不把刚生成的 wikilink 文本再链一次）。无词条 ⇒ 零开销、
  // 输出与改造前逐字节相同。
  if (data) renderAutoTerms(mdViewer, data, currentProject, absPath);
}

// ── Backlinks (KG audit ②, 2026-10-28) ────────────────────────────────
// 文档阅读页底部的折叠小节：谁引用了本文（入边）。数据 = /api/wiki/graph 载荷
// （edges.from/to + nodes，30s TTL 共享缓存）——纯前端可算，零新端点。
// 克制形态：<details> 默认收起；文档不在任何 vault 或零入链 ⇒ 整节不渲染
// （repo reports/ 等图外文件保持零噪音）。条目复用 a.nf-wikilink 的委派点击
// 路径（data-wiki-resolved 直填解析结果 ⇒ openLocalFileTab / remote-doc 腿
// 全部免费复用；边锚 '^blk'/'#Heading' 归一成点击腿认识的形态 ⇒ 点开即跳到
// 引用落点段落）。

/** Normalize a Windows/POSIX path for prefix comparison. */
function normVaultPath(p) {
  return String(p || '').replace(/\\/g, '/').replace(/\/+$/, '').toLowerCase();
}

/** The graph node for this absPath (null when the doc is outside every vault). */
function graphNodeFor(absPath, data) {
  if (!absPath || !data) return null;
  const norm = normVaultPath(absPath);
  const projects = data.projects || [];
  for (const p of projects) {
    const vault = normVaultPath(p.vault);
    if (!vault || !norm.startsWith(vault + '/')) continue;
    const rel = norm.slice(vault.length + 1);
    const hit = (data.nodes || []).find((n) => !n.stub && n.project === p.name && String(n.rel || '').toLowerCase() === rel);
    if (hit) return { node: hit, project: p };
  }
  return null;
}

/** Mirror resolveWikiTarget's tail: a graph node → the click-leg resolved shape. */
function resolvedForNode(node, project) {
  if (!node || !project) return null;
  if (node.device === 'self') {
    const sep = project.vault.includes('\\') ? '\\' : '/';
    return { absPath: project.vault.replace(/[\\/]+$/, '') + sep + String(node.rel).split('/').join(sep) };
  }
  return { remote: { device: node.device, vault: project.vault, rel: node.rel, project: project.name } };
}

/** Click-leg anchor form: '^blk' stays, '#Heading' drops the '#', 'L42' has no
 *  click mapping (omitted). */
function clickAnchorForm(edgeAnchor) {
  const a = String(edgeAnchor || '');
  if (!a) return '';
  if (a.startsWith('^')) return a;
  if (a.startsWith('#') && a.length > 1) return a.slice(1);
  return '';
}

/** Append the collapsible backlinks section (incoming graph edges) at the foot
 *  of the rendered document. Best-effort background face: any failure = no
 *  section, never an error surface (the #762 render-leg discipline). */
async function renderBacklinks(mdViewer, absPath) {
  let data = null;
  try { data = await wikiGraph(); } catch (e) { return; }
  const found = graphNodeFor(absPath, data);
  if (!found) return;
  const { node, project } = found;
  const incoming = (data.edges || [])
    .filter((e) => e.to === node.id)
    .map((e) => ({ edge: e, from: (data.nodes || []).find((n) => n.id === e.from) }))
    .filter((x) => x.from && !x.from.stub);
  if (!incoming.length) return;
  const byProject = new Map([[project.name, project]]);
  for (const x of incoming) {
    if (!byProject.has(x.from.project)) {
      byProject.set(x.from.project, (data.projects || []).find((p) => p.name === x.from.project) || null);
    }
  }
  const details = document.createElement('details');
  details.className = 'nf-backlinks';
  const summary = document.createElement('summary');
  summary.textContent = `${t('wiki.backlinks')} · ${incoming.length}`;
  details.appendChild(summary);
  const ul = document.createElement('ul');
  for (const { edge, from } of incoming) {
    const li = document.createElement('li');
    const a = document.createElement('a');
    a.className = 'nf-wikilink nf-backlink';
    a.href = '#';
    a.textContent = from.title || String(from.rel || '').split('/').pop();
    const resolved = resolvedForNode(from, byProject.get(from.project));
    if (resolved) {
      a.dataset.wikiResolved = JSON.stringify(resolved);
      const anchor = clickAnchorForm(edge.anchor);
      if (anchor) a.dataset.wikiAnchor = anchor;
    } else {
      // Cross-project node whose project manifest is absent — no click target.
      a.classList.add('nf-wikilink-unresolved');
    }
    li.appendChild(a);
    if (edge.anchor) {
      const chip = document.createElement('span');
      chip.className = 'nf-backlink-anchor';
      chip.textContent = edge.anchor;
      li.appendChild(chip);
    }
    if (from.project !== project.name && from.project) {
      const tag = document.createElement('span');
      tag.className = 'nf-backlink-project';
      tag.textContent = from.project;
      li.appendChild(tag);
    }
    ul.appendChild(li);
  }
  details.appendChild(ul);
  mdViewer.appendChild(details);
}

/** Handle a click on a rendered wikilink. */
async function onWikilinkClick(a, mdViewer) {
  hideTermTip();
  const anchorRaw = a.dataset.wikiAnchor || '';
  const target = a.dataset.wikiTarget || '';
  // Anchor normalization: '^id' → block jump; otherwise a heading jump.
  let anchor = anchorRaw.startsWith('^')
    ? { kind: 'block', id: anchorRaw.slice(1) }
    : (anchorRaw ? { kind: 'heading', text: anchorRaw } : null);
  // Self-reference (or an anchor-only link in this document).
  if (!target) {
    if (anchor) applyJumpAnchor(mdViewer, anchor);
    return;
  }
  let resolved = null;
  try { resolved = a.dataset.wikiResolved ? JSON.parse(a.dataset.wikiResolved) : null; } catch (e) { resolved = null; }
  if (!resolved) {
    try { resolved = resolveWikiTarget(target, '', await wikiGraph(), { allowTerm: !anchorRaw }); } catch (e) {
      // #762：点击腿是用户主动面——401/403（会话被拒）交 sessionGate 重认证伞，
      // 不再误报「无法解析该链接」；其余错误照旧落 unresolved 提示。
      if (e && (/** @type {any} */ (e).status === 401 || /** @type {any} */ (e).status === 403)) {
        noteAuthRejected('wiki-link');
        return;
      }
      resolved = null;
    }
  }
  if (!resolved) { window.__showToast?.(t('wiki.unresolved'), 'info'); return; }
  // 词条命中落点（F2）：作者未写锚时采用消解产物自带的块锚 —— 深链跳到词条
  // 定义段（applyJumpAnchor 滚动 + nf-jump-flash 高亮的既有通道，零新机制）。
  if (!anchor && resolved.anchor) anchor = resolved.anchor;
  // 来源页签 id（F5 返回原文档；v1 单层来源，每次跳转覆写）。
  const refSourceTab = refSourceTabId(mdViewer);
  // F5 阅读位保真：任何跳转派发前固化来源阅读位并冻结 scroll 记账。新开
  // 页签的同步布局抖动会把还在前台的来源滚动盒瞬时 clamp 回 0 并派发 scroll
  // （harness 实测：事件落在 class 切换前 ~50ms，防无可防）——不冻结真值就被
  // artifact 冲掉。冻结到来源页签下次激活（恢复阅读位）时解除。
  const srcPane = mdViewer.closest('.canvas-tab-pane');
  if (srcPane) {
    const sc0 = srcPane.querySelector('.canvas-md-scroll');
    if (sc0) srcPane._mdScrollPos = sc0.scrollTop;
    srcPane._mdPosPinned = true;
  }
  if (resolved.absPath) {
    openLocalFileTab(resolved.absPath, anchor, refSourceTab);
    return;
  }
  if (resolved.remote) {
    try {
      const { authHeaders } = await import('../flowHelpers.js');
      const url = `/api/wiki/remote-doc?device=${encodeURIComponent(resolved.remote.device)}&path=${encodeURIComponent(resolved.remote.vault + '/' + resolved.remote.rel)}`;
      const res = await fetch(url, { headers: { ...authHeaders() } });
      // #762：远端文档腿同属图谱族用户主动面——401/403 交重认证伞，不再裸
      // remoteFetchFailed toast；其余非 2xx 照旧。
      if (res.status === 401 || res.status === 403) {
        noteAuthRejected('wiki-remote-doc');
        return;
      }
      if (!res.ok) throw new Error(String(res.status));
      const body = await res.json();
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: {
          id: 'wiki-remote:' + resolved.remote.project + ':' + resolved.remote.rel,
          itemType: 'markdown',
          title: (target.replace(/\.md$/i, '')) + ' · ' + resolved.remote.device,
          content: body.content || '',
          readOnly: true,
          anchor,
          refSourceTab,
        },
      }));
    } catch (e) {
      window.__showToast?.(t('wiki.remoteFetchFailed'), 'error');
    }
  }
}

/** Markdown viewer - render formatted markdown (read-only preview) */
  /** 名称双显去重（作者令 2026-10-23 六项① + 2026-10-31 Canvas 改造批⑥
   *  「文件名不重复显示（标签页已显）」强化）：
   *  笔记类文档惯以 `# 与文件名相同的标题` 起笔 —— 阅读面里卡头胶囊/chip
   *  条（文件名）之下再渲染同一份名称，作者读作「底层旧名称元素残留」。此处
   *  在阅读渲染面去重：首个块级元素为 H1 且文本与文件名归一相等 ⇒ 隐藏该
   *  标题（display:none，DOM 与源码不动 —— 源码态/导出零影响；锚点结构保留）。
   *  归一口径（批⑥）：双方都剥扩展名与 `__` 溯源尾（本项目文档出处约定
   *  `…__<chainId>` 落在文件名尾，body 零元数据头——起笔 H1 常带全名或
   *  带尾全名）+ trim + 大小写不敏感；首个 H1 之外一律不动。 */
function dedupeTitleHeading(viewer, fileName) {
  if (!viewer || !fileName) return;
  const first = /** @type {HTMLElement|null} */ (viewer.firstElementChild);
  if (!first || first.tagName !== 'H1') return;
  const text = (first.textContent || '').trim();
  if (!text) return;
  const norm = (s) => s.trim().replace(/\.[^.]*$/, '').replace(/__[^_]*$/, '').trim().toLowerCase();
  if (norm(text) === norm(fileName)) first.style.display = 'none';
}

async function viewMarkdown(pane, { content, absPath, fileName, anchor: jumpAnchor, readOnly }) {
  // Content-unchanged guard. Focus/visibility refreshes re-fetch the same
  // file and re-render every open tab; rebuilding .canvas-md-scroll via
  // innerHTML resets scrollTop and throws the reader back to the top (bug:
  // Canvas md reading position lost on app switch). Same "unchanged never
  // remounts" rule as the Monaco path in canvas.js openWorkspaceItem. The
  // guard also requires a live preview DOM: a source-mode round trip wipes
  // innerHTML before calling back into this function, so scroller is null
  // there and the re-render must proceed. A jump anchor still applies to the
  // existing DOM (re-clicking a reference in an open doc must jump again).
  const scroller = pane.querySelector('.canvas-md-scroll');
  // Source analysis BEFORE the guard: the unchanged-content early-return
  // below still applies jump anchors (re-clicking a reference in an open
  // doc must jump), and a block anchor needs this document's ^id → line
  // map. analyzeSource is pure and cheap (fence-aware line walk), and it
  // MUST run for content-only opens too (no absPath — the remote-doc leg):
  // without it blockIds stays empty and every `#^id` jump into a remote
  // doc silently no-ops (段落级引用批 repaired: block jumps are the whole
  // point of the graph's paragraph-anchored references).
  const analysis = analyzeSource(content || '');
  if (scroller && /** @type {any} */ (pane)._renderedMdContent === content) {
    const existingViewer = pane.querySelector('.canvas-md-viewer');
    if (existingViewer && jumpAnchor) applyJumpAnchor(existingViewer, jumpAnchor, analysis.blockIds);
    return;
  }

  // Capture before rebuild: when content actually changed (external edit),
  // restore the reader's position afterwards so the refresh at worst drifts
  // but never jumps to the top. The scrollTop setter auto-clamps when the
  // new content is shorter than the old scroll offset.
  const prevScrollTop = scroller ? scroller.scrollTop : 0;

  const { renderMarkdownWithMath } = await import('../utils.js');
  let html = renderMarkdownWithMath(analysis.html, false);

  // Directory of this document — resolves BOTH the relative image paths below
  // and the relative link hrefs in the click handler. Computed once, so the
  // image rewrite and the link routing cannot disagree about what a relative
  // path in this document means.
  const baseDir = absPath
    ? absPath.substring(0, absPath.lastIndexOf(absPath.includes('\\') ? '\\' : '/'))
    : '';

  // Resolve relative image paths against the markdown file's directory.
  if (absPath) {
    const IMG_SCAN_RE = /<img\s+[^>]*src="([^"]+)"/g;
    const IMG_REWRITE_RE = /(<img\s+[^>]*src=")([^"]+)(")/g;
    /** Resolve one raw markdown image src to a local absolute path (null =
     *  remote/data/already-proxied — left untouched). */
    const toPath = (src) => {
      // Skip remote, data, and already-resolved URLs
      if (/^(https?:|data:|\/api\/)/.test(src)) return null;
      // The HTML has already been through the markdown parser, which
      // percent-encodes link destinations — `![](assets/截屏 1.png)` and
      // `![](<assets/x 2.png>)` both arrive here as `assets/%E6%88%AA…%201.png`.
      // Decode before resolving, otherwise the value is escaped a second time
      // below (`%25E6…`) and the endpoint looks for a literal `%E6…` file.
      // A src that is not valid escape syntax (e.g. a literal `%` in a name)
      // is used verbatim.
      try { src = decodeURIComponent(src); } catch (e) { /* keep as-is */ }
      // Resolve relative or absolute path.
      // `~` counts as absolute: the /api/nf-file endpoint expands it to the
      // user home (nfFileRoutes: `~` → user.home). The old check treated `~`
      // as relative, producing `<dir>/~/x.png` — a path that never exists, so
      // every `![](~/…)` image in a markdown file 404'd.
      let resolved;
      if (src.startsWith('/') || src.startsWith('~') || /^[A-Za-z]:[\\/]/.test(src)) {
        resolved = src; // already absolute (or ~-anchored)
      } else {
        resolved = baseDir ? baseDir + '/' + src.replace(/^\.\//, '') : src;
      }
      return resolved;
    };
    // Pass 1 — collect every candidate, then mint ONE batched ticket set
    // (dozens of images in a document must not mean dozens of requests).
    // 2026-09-11 (C batch): the credential is now a per-path ticket; the
    // global token never appears in an image URL again (T2).
    const candidates = new Set();
    html.replace(IMG_SCAN_RE, (m, raw) => {
      let src = raw;
      try { src = decodeURIComponent(src); } catch (e) { /* keep as-is */ }
      const p = toPath(src);
      if (p) candidates.add(p);
      return m;
    });
    const tickets = await mintTickets([...candidates]);
    // Pass 2 — rewrite to SHORT-LIVED placeholders (index tokens), synchronously.
    // 二次加载清剿批（2026-10-24 · #816 同法）：出帧前每张图的最终 src 先经
    // **账本单点**解析（下方 hydration）——占位符阶段保证 innerHTML 落地时图已带
    // 终局 src，浏览器不会先对票据 URL 发一轮注定重复的抓取。
    /** @type {{ path: string, url: string, finalUrl?: string }[]} */
    const mdImgs = [];
    html = html.replace(IMG_REWRITE_RE, (m, prefix, raw, suffix) => {
      let src = raw;
      try { src = decodeURIComponent(src); } catch (e) { /* keep as-is */ }
      const p = toPath(src);
      if (!p) return m;
      const t = tickets.get(p);
      const url = `/api/nf-file?path=${encodeURIComponent(p)}${t ? `&ticket=${encodeURIComponent(t)}` : ''}`;
      mdImgs.push({ path: p, url });
      // token sits INSIDE the src="…" value; the fallback attribute rides a
      // second placeholder AFTER the attribute (a literal url here would put
      // markup inside the quoted src value).
      return `${prefix}__NF_MD_IMG_${mdImgs.length - 1}__${suffix} data-nf-md-fb="__NF_MD_FB_${mdImgs.length - 1}__"`;
    });
    // Hydration — every rewritten image resolves its final src through the
    // ledger single point (`nfViewerUrl`): `nf:<path>` hit = blob URL (ZERO
    // fetch — e.g. the same image already rendered in a bubble or opened in
    // Canvas); miss = the existing ticketed fetch + `nfCachePut` (exactly once,
    // so re-opens/re-renders of this doc and Canvas opens of the image reuse
    // the bytes). Failure (mint/fetch) ⇒ the ticketed URL (browser fetches it
    // directly — the pre-batch behavior, verbatim).
    await Promise.all(mdImgs.map(async (e) => {
      try {
        e.finalUrl = await nfViewerUrl({ absPath: e.path, objectUrl: null, pane });
      } catch (err) {
        e.finalUrl = e.url; // 逐字现状：票据 URL 直取（含无票 URL ⇒ 端点 401 ⇒ onerror 失败面）
      }
    }));
    html = html.replace(/__NF_MD_FB_(\d+)__/g, (m, i) => {
      const e = mdImgs[Number(i)];
      // fallback 属性只对「账本 blob src」在场（票据 URL 直取的图，失败面 = 既有 tooltip）。
      return e && e.finalUrl && e.finalUrl !== e.url ? e.url : '';
    });
    html = html.replace(/__NF_MD_IMG_(\d+)__/g, (m, i) => {
      const e = mdImgs[Number(i)];
      return e ? (e.finalUrl || e.url) : m;
    });
    // Broken-image tooltip + blob self-heal (debugging aid + #816-同族语义).
    // The shown URL must never carry a credential (T2 / Δ⑦): the ONE strip
    // implementation lives in nfTicket.js — this module imports it directly
    // (the old inline-attribute handler needed a window publication; the
    // handler is a real function now, so the global is gone). `__nfMdImgFail`
    // = 账本 blob 解码失败的自愈腿单点：坏字节先逐出账本（`nfCacheDrop`），再回落
    // 票据 URL 恰一次；票据 URL 也败 ⇒ 既有 tooltip 失败面（凭据已剥）。
    // 非 blob src = 直达失败面（逐字现状）。（`window.__nfMdImgFail` 仍在发布，
    // 因为 onerror 是内联属性、求值于全局作用域——模块 import 不可见。）
    /** @type {any} */ (window).__nfMdImgFail = (/** @type {HTMLImageElement} */ img) => {
      const fallback = (img.dataset && img.dataset.nfMdFb) || '';
      if (img.src && img.src.startsWith('blob:') && fallback && !img.dataset.nfMdRetried) {
        img.dataset.nfMdRetried = '1';
        const q = /[?&]path=([^&]*)/.exec(fallback);
        const p = q ? decodePathParam(q[1]) : null;
        if (p) void nfCacheDrop('nf:' + p).catch(() => {});
        img.src = fallback;
        return;
      }
    img.style.opacity = '0.3';
    const shown = (img.dataset && img.dataset.nfMdRetried && fallback) || img.src;
    img.title = t('canvas.imgFailed') + ': ' + stripCredentialParams(shown);
    };
    html = html.replace(
      /<img\b/g,
      '<img onerror="window.__nfMdImgFail && window.__nfMdImgFail(this)"'
    );
  }

  pane.classList.remove('scrollable');
  // P0 §6 顶垫共享类（防胶囊遮挡；桌面常态由 split.css 尾块钉，手机层常态垫）。
  //
  // Canvas 改造批⑦（2026-10-31 作者令）：md 退出统一阅读 chrome（阅读器视角
  // 仅电子书格式生效）——正文 DOM 原样自建直接挂 pane（.canvas-md-scroll
  // 自带 absolute/inset:0/overflow-y:auto，split.css），无底栏/TOC 抽屉/
  // 翻页动效；阅读位记账（下方 scroll 监听 + canvas-tab-activated）是 pane
  // 级机制、零 chrome 依赖，逐字保留。
  if (/** @type {any} */ (pane)._nfReaderTeardown) {
    try { /** @type {any} */ (pane)._nfReaderTeardown(); } catch { /* 卸载容错 */ }
    /** @type {any} */ (pane)._nfReaderTeardown = null;
  }
  const mdHost = document.createElement('div');
  mdHost.className = 'canvas-md-viewer';
  mdHost.innerHTML = html;
  const mdScroller = document.createElement('div');
  mdScroller.className = 'canvas-md-scroll canvas-viewer-safe-top';
  mdScroller.appendChild(mdHost);
  pane.appendChild(mdScroller);

  // marked v5+ dropped the `headerIds` option, so add slug ids manually
  // for TOC anchor navigation. Keeps word chars, spaces, CJK, hyphens;
  // strips other punctuation, lowercases, spaces→hyphens.
  // （P1：id 分配前移到 chrome 挂载前——TOC 抽屉条目要带锚 id。）
  {
    const slugCounts = {};
    const slugify = (text) => (text || '')
      .toLowerCase()
      .replace(/[^\w\s一-鿿-]/g, '')
      .trim()
      .replace(/\s+/g, '-')
      .replace(/-+/g, '-');
    mdHost.querySelectorAll('h1, h2, h3, h4, h5, h6').forEach(h => {
      if (h.id) return;
      let slug = slugify(h.textContent);
      if (!slug) slug = 'heading';
      if (slug in slugCounts) { slugCounts[slug]++; slug = `${slug}-${slugCounts[slug]}`; }
      else slugCounts[slug] = 0;
      h.id = slug;
    });
    // Source-line map for reference jumps (only meaningful with a file).
    if (analysis.blockStarts.length) assignSourceLines(mdHost, analysis.blockStarts);
  }
  /** @type {any} */ (pane)._mdScrollPos = /** @type {any} */ (pane)._mdScrollPos || 0;
  // 阅读位保真（词条引用批 batch 2，F5 返回原文档配套）：pane 切换走
  // display:none，Chromium 不保留滚动盒（实测 scrollTop 178→0）。scroll 监听
  // 记账 pane._mdScrollPos，激活时（canvas-tab-activated，canvas.js 只对带
  // 阅读位记账的 pane 派发）恢复；跳后 2s 窗口内不抢 TOC/引用落点。
  const scrollTrack = mdScroller;
  {
    // display:none 化（切走页签）时 Chromium 把滚动盒重置为 0 并派发 scroll——
    // 那是布局 artifact 不是阅读意图，记账必须跳过（否则回来时永远恢复成 0）。
    // 判据用 active 类而非 clientHeight：artifact 事件的派发时序里 clientHeight
    // 读数不可靠（实测守不住），而 classList 是 JS 同步面。
    scrollTrack.addEventListener('scroll', () => {
      if (!pane.classList.contains('active')) return;   // 切走后的 artifact（见上）
      if (pane._mdPosPinned) return;                    // 跳转冻结窗（onWikilinkClick）
      pane._mdScrollPos = scrollTrack.scrollTop;
    }, { passive: true });
  }
  dedupeTitleHeading(mdHost, fileName);
  /** @type {any} */ (pane)._renderedMdContent = content;
  // Jump face: the live analysis stays on the pane so a reference click into
  // an ALREADY-OPEN tab (canvas.js switches without re-rendering) can still
  // resolve block anchors — it dispatches nf-jump-anchor on the pane.
  /** @type {any} */ (pane)._mdAnalysis = analysis;
  if (!/** @type {any} */ (pane)._mdJumpBound) {
    /** @type {any} */ (pane)._mdJumpBound = true;
    pane.addEventListener('nf-jump-anchor', (/** @type {CustomEvent} */ e) => {
      const viewer = pane.querySelector('.canvas-md-viewer');
      const blockIds = /** @type {any} */ (pane)._mdAnalysis?.blockIds;
      if (viewer && e.detail) applyJumpAnchor(viewer, e.detail, blockIds);
    });
    // 阅读位恢复：切换回来（含「返回原文档」钮的单击）时把滚动盒写回记账值。
    pane.addEventListener('canvas-tab-activated', () => {
      // 解冻跳转冻结窗：回到来源页即恢复用户滚动记账权。
      /** @type {any} */ (pane)._mdPosPinned = false;
      if (Date.now() - (/** @type {any} */ (pane)._mdJumpAt || 0) < 2000) return;
      const sc = pane.querySelector('.canvas-md-scroll');
      if (sc && Number.isFinite(/** @type {any} */ (pane)._mdScrollPos)) {
        sc.scrollTop = /** @type {any} */ (pane)._mdScrollPos;
      }
    });
  }

  // Restore the reader's position after a content-changed rebuild. Clamped
  // automatically if the new document is shorter (scrollTop setter).
  const newScroller = pane.querySelector('.canvas-md-scroll');
  if (newScroller && prevScrollTop > 0) newScroller.scrollTop = prevScrollTop;

  // slug id 与 source-line 映射已在 chrome 挂载前落定（P1 TOC 抽屉需要锚 id），
  // 此处只取引用继续链接路由/wiki 面接线。
  const mdViewer = pane.querySelector('.canvas-md-viewer');
  if (mdViewer) {
    // Link routing — the SAME criterion the HTML preview frame runs
    // (`routeLocalHref` in viewers/shared.js, embedded by source into the
    // frame's injected script, so there is one definition and no drift).
    // This pane renders markdown INTO the app document: the preview surface is
    // not a sandboxed frame and has no navigation guard, so an unhandled
    // `[x](doc.md)` navigated the WHOLE application away (URL became /doc3.md,
    // 404, #activity-bar gone). Local paths now open as Canvas tabs, external
    // http(s) links as Canvas URL tabs, `#` anchors still scroll in this pane.
    // 2026-09-12 (canvas-preview nav parity, residual face): a markdown
    // deliverable is the same authored-markup surface as an HTML one and gets
    // the same browser-grade link behaviour.
    mdViewer.addEventListener('click', (e) => {
      const wikilink = e.target instanceof Element ? e.target.closest('a.nf-wikilink, a.nf-term') : null;
      if (wikilink) {
        // 触屏首击开泡（词条引用批 batch 2，移动惯例）：(hover:none) 环境里
        // 词条面的第一次轻点只展开气泡不导航，再点（链接或气泡内）才跳转。
        // 桌面环境此腿恒 false（hover 已预览，点击 = 直接深链跳转）。
        if (termTapExpand(/** @type {HTMLAnchorElement} */ (wikilink), mdViewer)) {
          e.preventDefault();
          e.stopPropagation();
          return;
        }
        e.preventDefault();
        e.stopPropagation();
        onWikilinkClick(/** @type {HTMLAnchorElement} */ (wikilink), mdViewer);
        return;
      }
      const link = e.target.closest('a[href]');
      if (!link) return;
      const href = link.getAttribute('href');
      const route = routeLocalHref(href, baseDir);
      if (route.kind === 'anchor') {
        // TOC anchor: scroll within the pane's content container, not the window,
        // and not any other scrollable ancestor either (scrollHeadingToTop above).
        // marked v12 URL-encodes CJK chars in href="#..." anchors, but heading IDs
        // use raw characters. Try decoded first, fall back to raw.
        if (!href || href === '#') return;
        e.preventDefault();
        const raw = href.slice(1);
        let decoded;
        try { decoded = decodeURIComponent(raw); } catch { decoded = raw; }
        const target = mdViewer.querySelector(`[id="${decoded}"]`) ||
                       mdViewer.querySelector(`[id="${raw}"]`);
        if (target) scrollHeadingToTop(mdViewer, target);
        return;
      }
      // `none`: mailto:/tel:/data:/javascript: — the browser's own business.
      if (route.kind === 'none') return;
      // Never let a link replace the application document: `unresolved` is a
      // relative href in a document opened without a path (no base to resolve
      // against) — it has no target, so the click resolves to nothing at all.
      e.preventDefault();
      if (route.kind === 'external') openExternalUrlTab(route.url);
      else if (route.kind === 'local' && route.path) openLocalFileTab(route.path);
    });

    // Wiki face: wikilinks (async, post-render) + jump anchor + backlinks foot
    // (KG audit ② — incoming edges only, collapsed; absent outside vaults).
    // md/html 类型硬约束闸（设计稿 §8 词条同闸回写；调研稿 §9-1/§9-7）：wikilink
    // 渲染消解（含词条引用第二批 F1 的词条回退——已落本闸内侧）仅对四扩展名
    // 生效；非 md/html 文档 `[[词条]]` 按普通文本渲染不解析、点击无深链。viewer
    // 注册表今天只把 .md/.markdown 指到本件，本闸是防再映射的显式单点（与
    // askDock 同挂 mdHtmlGate.js 白名单）。
    if (absPath && isMdHtmlFile(absPath)) { renderWikilinks(mdViewer, baseDir, absPath); renderBacklinks(mdViewer, absPath); }
    if (jumpAnchor) applyJumpAnchor(mdViewer, jumpAnchor, analysis.blockIds);
    // 词条气泡随滚动收起：fixed 定位气泡不跟随文档滚动，锚点滚走即失位。
    const mdScroller = pane.querySelector('.canvas-md-scroll');
    if (mdScroller) mdScroller.addEventListener('scroll', hideTermTip, { passive: true });
    // Mermaid face: hydrate ```mermaid placeholders (lazy viewport render —
    // js/mermaidRenderer.js). Idempotent; no-op when the doc has no fences.
    hydrateMermaidIn(mdViewer);
  }

  addSourceToggle(pane, viewMarkdown, { content, absPath, fileName, readOnly: readOnly === true });
  // Canvas 改造批⑦⑧（2026-10-31）：md 已无阅读器底栏，角浮件回落单行同簇
  //（右下 48px 间距档：源码 16 · 查找 64 · 壳刷新钮实测续排）；图谱圆钮随
  // 批④退役，词条面走 vault 入口。
  addFindToggle(pane, 64);
}

export default {
  name: 'markdown',
  label: 'Markdown',
  extensions: ['.md', '.markdown'],
  binary: false,
  priority: 0,
  render: viewMarkdown,
};
